# R52「被撤回图片可打开」修复 · 装包前交叉复核（独立第二视角）

> 复核方：`r52-fix-review`（只读）。⛔ 本轮未改任何源码 / 测试 / 配置，未碰 adb / gradle / 微信库 / 开关默认值。
> 复核对象：`data/MediaDeleteLogic.kt`、`feature/MediaTableGuard.kt`、`test/.../MediaDeleteLogicTest.kt`（均 untracked，`??`）。
> 源码 sha256 与 `_cache/r52_fix.diff` 内记录逐字节一致（`b1893060…` / `a1c4dc3e…` / `c27fb77f…`），快照可信。

---

## 一、一句话结论

**可以装包。P0 = 0 条。**

判定理由：本轮修复的三处「最隐蔽失效面」——① `shouldBlock` 双空集闸门（C2）、② `classifyDelete` id 出口位置（C3）、③ `MediaTableGuard` 反查 SQL 的口径（C5）——经逐行读源码全部正确落地，且 8 个变异体（含 M2a/M2b 两个子变异体）实测全部 ≥1 RED、83 例单测全绿。正向修复本身无阻断装包缺陷；「装包后用户能否看到完整大图」仍受甲-7/甲-1 两条 `[需真机]` 支约束，但那是验收动作而非代码缺陷，不阻断装包。

---

## 二、C1~C14 逐条复核

| 项 | 结论 | 证据行号 | 说明 |
|---|---|---|---|
| C1 零回归 | **PASS** | `MediaDeleteLogic.kt:157-198` / `:347-375`；测试 1~6 段 46 方法 | R51 既有 46 例（测试文件第 1~6 段，逐方法计数=46）用 `cls()`/`block()` 老 helper，`protectedRowIds` 默认 `emptySet()`。此时 id 分支 `:184-188` 因 `idMatches(…, emptySet())` 恒 false 不进入、id 出口 `:368-370` 因 `protectedRowIds.isNotEmpty()` 为 false 不进入，token 通道 `:190-198` / `:371-374` 逐字未动。`r52_mut_FINAL.log` 实测 `Tests run: 83, Failures: 0`。 |
| C2 双空集闸门 | **PASS** | `MediaDeleteLogic.kt:360`；`MediaTableGuard.kt:286` | `shouldBlock` 确为 `if (protectedTokens.isEmpty() && protectedRowIds.isEmpty()) return false`（两集皆空才短路）；lane B 侧 `onBefore` 同款 `if (tokens.isEmpty() && rowIds.isEmpty())` 对齐。M2a 变异（改回单空集）实测 3 条 RED（`c7`/`c1`/`c1b`），非等价变异。 |
| C3 id 出口位置 | **PASS** | `MediaDeleteLogic.kt:184-188`（classify）、`:362-370`（shouldBlock） | `classifyDelete` 的 id 分支在 `:190-191` `tokens.isEmpty() ⇒ UNKNOWN` **之前**；`shouldBlock` 的 id 出口在 `:362-364` `classifyDelete!=BLOCKABLE ⇒ false` **之后**、`:371-372` token 复检**之前**。两处齐备。M2b 变异（删 id 出口）实测 3 条 RED（`c7`/`c1`/`c1b`）。 |
| C4 归一化口径 | **PASS** | 删除侧 `:303`/`:308`；反查侧 `MediaTableGuard.kt:466` | 两侧都复用 `MediaDeleteLogic.normalizeId`，无自写归一。`normalizeId` 去空白/去前导 `+`、保留前导 0（`a9`）、拒绝负/小数/空/非数字（`a7`）、不做 `toLong()`（`a8`）。`c9` 用例专杀前导 0 口径不一致 ⇒ false。 |
| C5 反查 SQL 口径 | **PASS**（代码层）+ **`[需真机]`**（列存储形态） | `VoiceFileGuard.kt:129,151`；`MediaTableGuard.kt:388,456-457` | 逐字对照：池 key = `MediaGuardLogic.hexToken(imgPath)` = **裸 32 位 md5 小写**（`VoiceFileGuard.kt:129`），`snapshotLiveTokens()` `:388` 仅 `k.lowercase()`；真机 `imgPath` = `THUMBNAIL_DIRPATH://th_<md5>`。反查 `instr(thumbImgPath, ?)` 绑定的是**裸 md5**，是 `th_<md5>` 的子串 ⇒ 形态一致，**非 P0**。残留 `[需真机]`：`ImgInfo2.thumbImgPath` 列真实存储是 TEXT 还是 BLOB/NULL/`wcf://` 路径（设计 T5 第 4 步 `hex(thumbImgPath)` 留档定）。 |
| C6 线程竞态 | **PASS** + `[需真机]` | `MediaTableGuard.kt:185-192`（反查仅 `invalidate()`）；`RevokeRescue.kt:128` | 反查只在 `invalidate()`（`[GT]ColdPool`，由 `RevokeRescue.record()` 调用），绝不在 `onBefore`（`[D]#worker`）热路径。`cachedRowIds` `@Volatile`，`refresh()` 排在 `lookupRowIds()` 之后尽早发布。**连撤两张不漏**：`snapshotLiveTokens()` 遍历整池（600s TTL 累积），第二次 `invalidate()` 仍含第一张 token ⇒ 不丢。残留：删除先于首次发布到达（~10~25ms）的窗口 `[需真机]`，由 `cost` 打点验收。 |
| C7 保守性红线 | **PASS** | `:165-177` / `:289-297`；`NUMERIC_KEY_COLUMNS` `:230` | `whereClause==null`→BULK_OR_UNSAFE；`UNSAFE` 正则（select/like/is null/in/比较符/and/or/,/;）→BULK_OR_UNSAFE；`whereArgs.size>1`→BULK_OR_UNSAFE；非目标表→OTHER_TABLE；标量列 `status=?`→UNKNOWN（不在 keyColumns）；路径列 `bigImgPath=?` **不在** `NUMERIC_KEY_COLUMNS` ⇒ id 分支不进入、仍走 token 通道。`extractRowIds` 全部闸门复检，无放宽、无「取第一个」。 |
| C8 拦截语义零改动 | **PASS** | `MediaTableGuard.kt:306,309` | `param.result` 仅 2 处：execSQL→`null`（`:306`）、delete→`FAKE_ROWS`（`:309`）。放行分支全部裸 `return`（附 `observe()` 记账），无 `setResult`、无吞异常到结果、无新增写库/文件操作。`lookupRowIds` 只读（`rawQuery`+`BYPASS`）。无新增 import（`AntiRevoke`/`AntiRevokeHint` 同 `feature` 包免 import）。 |
| C9 探针能力保留 | **PASS** | `:195-204`（stat）/ `:213-221`（reset）/ `:526-533`（observe）/ `:497-508`（noteEvent）/ `:130-133`（obsLogAt∥logAt） | `MTG stat enabled/gen/seen/matched/blocked/skipped/verdict/pool/tokens/events/switch` 逐字在；`MTG delete … reason=`/`MTG stat … verdict=` 前缀保留；6 条静默 return 各带 `observe()`；`resetLedger()` 只清 4 计数 + events/logAt/obsLogAt，**不动** `enabled/cachedTokens/cachedRowIds/gen/lastIssuedMs/mediaPoolRef`。 |
| C10 异常降级 | **PASS** | `MediaTableGuard.kt:416-492` | tokens 空→MISS 日志+空集；句柄 null→MISS+空集；逐 token `try/catch` 捕获 `err` 打 MISS；`rows=0`→MISS；Cursor `finally close`（`:469-471`）；`MAX_LOOKUP_TOKENS=32`+`MAX_ROWS_PER_TOKEN=16`+`LOOKUP_BUDGET_MS=20` 封顶（`:433-442`）；整体空→MISS+空集。全部保守放行、不静默、不退化全拦、不凑假 id。 |
| C11 单测与变异 | **PASS** | 测试 `:713-737`（c7）、`:601-616`（b15/b15b）、`:457-463`（a1）；`_cache/r52_mut_*.log` | 83 例=46（R51 零改动）+37（新增 A6/B18/C13）。覆盖 `id=?` 的 Long/String/Int/null、内联数值、非命中、空 rowIds、C7 双空集。8 个变异体全 RED：M1=10、M2a=3、M2b=3、M3=9、M4=2、M5=2、M6=3、M7=2；**M5 被 b15+b15b 两条腿杀死**（`r52_mut_M5.log` 恰好这两条 RED）。无等价变异。 |
| C12 编译调用 | **PASS** | `MediaTableGuard.kt:292-295` | `shouldBlock(p.table, p.where, p.args, tokens, nowMs, lastIssuedMs, WINDOW_MS, protectedRowIds = rowIds)`：7 个位置参 + 尾部命名参数，与签名 `(…, ttlMs, protectedRowIds = emptySet())` 一致；`classifyDelete(…, protectedTokens, protectedRowIds)` `:362` 5 位参一致。无签名不匹配。 |
| C13 回滚路径 | **FAIL**（部分） | `_cache/r52_revert.patch`；`MediaTableGuard.kt:114,190,294,466` | `git apply --check _cache/r52_revert.patch`（正向）exit=0，`r52_r51_MediaDeleteLogic.kt` 与 R51 基线吻合 ⇒ **MediaDeleteLogic.kt 可还原**。但补丁**只覆盖 MediaDeleteLogic.kt**，未还原 `MediaTableGuard.kt` 的 R52 改动（`cachedRowIds`、`lookupRowIds`、`invalidate()` 反查、`onBefore` 命名透传）。仅回滚 MediaDeleteLogic 会导致 MediaTableGuard 引用已删除的 `protectedRowIds` 参数与 `normalizeId` ⇒ **编译失败**。 |
| C14 残留风险 | `[需真机]` | 见下 P1 清单 | 甲-7（行保留但 `h/i/j` 仍填 0）不作定论；甲-1（`thumbImgPath` 列形态）；竞态窗口；`instr` 大小写敏感；execSQL void 跳过写法；`WxFileIndex2` 键列。 |

---

## 三、问题分档清单

### P0（阻断装包）—— 0 条

无。C1~C12 全部 PASS，正向修复代码正确、单测+变异真实有效。

### P1（可装包，但需真机确认 / 需关注）

1. **甲-7 · 行保留后 `h/i/j` 是否恢复**（`[需真机]`，⛔ 不作定论）
   - 位置：`docs/_r52_fix_design.md:186` 甲-7 / `:240` 团队长点名支。
   - 修法：装包后按设计 T5 第 9 步——verity 发**新图**撤回 → 撤后 `ImgInfo2` 快照确认 `n: 1→1` → 点开读 `HDIMG trace` 的 `fld[h/i/j/o]` 与 `img=`；主判据仍是「能否看到完整大图」。若行在但 `h/i/j=0`，按 D3 停手，⛔ 不加第三条判据。

2. **甲-1 · `thumbImgPath` 列真实存储形态**（`[需真机]`）
   - 位置：`MediaTableGuard.kt:456-457` 反查 SQL；`docs/_r52_fix_design.md:180` 甲-1。
   - 风险：若 `ImgInfo2.thumbImgPath` 是 BLOB（含 NUL）或 NULL 或 `wcf://` 虚拟路径，`instr(BLOB, TEXT)` 可能恒 0/null ⇒ `rows=0` ⇒ 保守放行。
   - 修法：验收时 `MTG id-lookup rows=<n>` 必须 `>=1`；`rows=0` 即按 D3 停手，用 `op=label sub=sql` 的 `hex(thumbImgPath)` 留档复核列形态。

3. **反查竞态窗口**（`[需真机]`）
   - 位置：`MediaTableGuard.kt:185-192`（反查）vs `:257`（`onBefore` 在另一线程）。
   - 风险：删除先于 `cachedRowIds` 首次发布到达（真机 ~10~25ms），则行已删、不可回滚。
   - 修法：核对 `MTG id-lookup … cost=<ms>` 与 `MTG delete` 时间戳，`cost` 需显著小于删除到达间隔；超 20ms 要评估。

4. **execSQL void 跳过写法**（`[需真机]`，既有，非本轮新增）
   - 位置：`MediaTableGuard.kt:303-306` `param.result = null`。
   - 风险：LSPosed 对 void 方法是否确实跳过、微信是否重试删除未取证。仅当撤回删除真走 execSQL 时才触发（决定性证据已证走 `delete()`，故本轮主路径不依赖此）。

### P2（建议改进）

1. **回滚路径不完整**（对应 C13 FAIL）
   - 位置：`_cache/r52_revert.patch`（仅 MediaDeleteLogic.kt）；`MediaTableGuard.kt:114,190,294,466`。
   - 修法：补一份 `MediaTableGuard.kt` 的 R51 基线（含 `cachedRowIds`/`lookupRowIds`/`invalidate()` 反查/`onBefore` 透传的整段反向 diff），使回滚后能编译；否则真机若需回退，仅凭现有补丁会留下编译失败的半成品。

2. **`instr` 大小写敏感**（低概率）
   - 位置：`MediaTableGuard.kt:456-457`。
   - 风险：池 key 恒小写（`hexToken` 归一），若 `thumbImgPath` 存的是大写 md5（真机证据为小写，概率低），`instr` 二进制比较会 miss ⇒ 保守放行。
   - 修法：真机 `hex(thumbImgPath)` 留档确认大小写；若发现大写，可 `lower(thumbImgPath)` 或 `instr(lower(thumbImgPath), lower(?))`。

3. **`getLong` 对 TEXT 型 id 的边界**（低概率，防御性提示）
   - 位置：`MediaTableGuard.kt:463` `callMethod(cur, "getLong", 0)`。
   - 风险：`ImgInfo2.id` 若为 TEXT 主键（决定性证据的 `id=?` 数值主键表明为 INTEGER，概率低），`getLong` 行为不确定。建议真机 `op=label sub=sql` 顺带确认 `id` 列类型。

---

## 四、装包后真机验收最小判据

- **唯一主判据**：请 verity 发**新图**撤回后，隔 3~5 分钟**点开能否看到完整大图**（非缩略图/灰图/「已过期/已撤回」提示）。
- **`MTG blocked` / `seen` / `matched` / `rows` / `tokens` / `gen` 只作定位，⛔ 不作结论**：`blocked` 可能拦错对象；`rows>=1` 只证明反查命中，不证明 UI 显示大图。
- **验收前必须先 `op=logclear`**：`LogX.kt:83-87` 满 5000 行是 `writeText` 整文件清空重写（非逐行 FIFO），不先清空会把 `RRV record`/`MTG blocked`/`HDIMG trace` 证据整批冲掉。
- 关键对照：`MTG id-lookup md5=<md5> rows=<n>` 需 `>=1`；`MTG delete table=ImgInfo2 where=id=? reason=blocked`（从 R51 的 `shouldBlock-false` 翻转）；点开时 `HDIMG trace` 的 `fld[h/i/j/o]≠0`、`img=SERVERID://…`。若行在（`n:1→1`）但 `h/i/j=0`、`img=null` ⇒ 命中甲-7，按 D3 停手。

---

## 五、三档边界复核结论

针对「进入 / 返回 / 复位」，复核 `op=mtg` 探针与 `resetLedger` 语义（`EntryReceiver.kt:708-718`、`MediaTableGuard.kt:195-221`）：

| 档 | 结论 | 说明 |
|---|---|---|
| ① 进入 | **PASS** | 冷启 `install()`（hook + `resolvePool()`，`cachedTokens`/`cachedRowIds` 空）；首次 `record()→invalidate()` 重建两份快照。无残留误判。 |
| ② 返回 | **PASS** | 纯 UI 返回不改快照；TTL 到期 → `isRevocationWindow` false → 不再拦（账本/hook/开关仍在）；冷启 → 进程内全清零。三者各自独立、未混并。 |
| ③ 复位 | **PASS** | `op=mtg sub=reset` → `resetLedger()`（`:213-221`）**只清账本**（seen/matched/blocked/skipped/events/logAt/obsLogAt），⛔ 不动 `enabled/cachedTokens/cachedRowIds/gen/lastIssuedMs/mediaPoolRef`。`EntryReceiver.kt:716` `else -> "MTG unknown sub=$sub"` 只读落日志，绝不掉进写库分支。 |

**⛔「滚回顶部 / `pos <= prev` 不是复位触发条件」核查结论**：本轮 R52 修复范围内（`MediaDeleteLogic` + `MediaTableGuard`）**不存在**任何 `pos`/`prev`/滚动位置类逻辑，唯一复位信号是显式广播 `op=mtg sub=reset`。未发现实现方把「滚回顶部 / 位置回退」误当作复位触发条件的现象。`resetLedger` 与「冷启整体清零」语义严格区分（后者是进程死亡重建 object，前者是账本归零），二者未混淆。

---

[DONE] ALL
