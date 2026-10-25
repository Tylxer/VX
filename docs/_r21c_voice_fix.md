# R21c · 防撤回语音后播放失败 —— 实现（voice-fix lane）

状态：**代码与单测已落地，`testDebugUnitTest` 已跑通**（见 §5）
改动范围：
- 新建 `data/VoiceGuardLogic.kt`（零 Android 依赖纯 object）
- 新建 `data/VoiceGuardLogicTest.kt`（JVM 单测，16 条）
- 新建 `feature/VoiceGuard.kt`（hook 实现）
- `HookEntry.kt` 加一行 install
- `feature/AntiRevoke.kt` / `feature/RevokeRescue.kt` / `data/HintTextLogic.kt` / `data/GroupDb.kt` **零改动**

根因（主会话真机定案，`docs/_r21c_voice_root.md` 的 C「删文件」假设已推翻）：
微信撤回语音时**先** `DELETE FROM voiceinfo WHERE FileName IN (SELECT imgPath FROM message WHERE talker IN (...))`
删掉语音下载记录，**再** `UPDATE message` 改撤回形态。我们的 `AntiRevoke` 只拦了第二步 UPDATE，
第一步 DELETE 没拦 ⇒ `message` 行还在（气泡在），但 `voiceinfo` 行没了 ⇒ 播放器按 `imgPath`
去 `voiceinfo` 拿 `Status/TotalLen/NetOffset` 查不到 ⇒ 点播放没反应。

---

## §1 设计选择

### 1.1 持久化方案：选 A（`param.result` 伪装成功），不选 B（快照回插）

| 方案 | 做法 | 代价 | 结论 |
|---|---|---|---|
| A 伪装 | 命中判据时 `param.result = 1`，DELETE 不真执行 | 微信以为删了、实际没删，可能重试（DELETE 幂等，重试无害） | ✅ **选 A** |
| B 快照回插 | 删之前 rawQuery 快照整行、删之后再 INSERT 回去 | delete hook 里两次反射读写 + 处理「微信重试又删」循环 | ❌ |

理由：
1. voiceinfo 是**下载续传记录表**（`Status/TotalLen/FileNowSize/NetOffset` 全是下载进度字段），
   不是消息本体。拦下 DELETE 后，语音文件（voice2/silk）、`message` 行、`imgPath` **都还在**，
   唯一保住的是 voiceinfo 里那条 `Status=99` 的下载记录 —— 播放器正需要它。
2. A 与 `AntiRevoke` 拦 update 是同一思路（`param.result = FAKE_ROWS`），已有成熟的重试节流
   （`blocked` map + `BLOCK_TTL_MS`）可照搬；B 还要在热路径上做同步 rawQuery + 事后 INSERT。
3. DELETE 幂等：微信就算重试删同一行，删不存在的行返回 0 行，无副作用。

### 1.2 子查询（`FileName IN (SELECT imgPath ...)`）怎么办：**保守放弃拦**

真机定案的真实 SQL 正是子查询形态，`whereArgs` 里没有具体 imgPath，从 whereClause 拿不到值。
`shouldBlock` 对子查询**返回 false（放行）**。理由：

- 要拦它只有两条路，两条都更危险：
  - (a) 「先跑这条子查询得到 imgPath 列表再判交集」：要在 delete hook 里解析子查询、提取
    talker 列表、rawQuery 执行子查询 —— 脆弱且贵；而且子查询命中的是**整个会话所有消息的
    imgPath**，一旦误判会把整会话的 voiceinfo 清理整条拦掉（比单条误拦严重得多）。
  - (b) 无条件拦整个子查询 DELETE：等于把「会话级语音清理」整条吞掉，GC 失效、存储只增不减，
    正是本任务第一要避开的坑。
- 保守放弃 = 本修复对子查询形态不生效，但**绝不破坏微信正常清理**。

⚠️ 由此引出 §6 的**头号补取判据**：真机撤回语音时 delete 到底走子查询还是参数化形态？
若主会话实测「撤回时只有子查询形态、且它发生在 record 之后」，则纯判据保守放弃会让修复对
真实场景无效，需走 (a)/(b) 的后续方案。当前实现覆盖的是能精确匹配的参数化形态
（`FileName=?` / `FileName IN(?,...)` / `MsgLocalId=?`）与非参数化内联 hex。

### 1.3 时间窗 N：**60 000ms（60s）**

判据要有「只拦最近 N 秒内刚 `record()` 过的」约束，避免误拦历史清理。N 取 60s：

1. 目的：区分「撤回刚发生」与「历史清理」。撤回的 voiceinfo DELETE 与撤回几乎同时发生
   （同一次撤回流程/重试风暴内，秒级）；历史清理（删会话、空间 GC）发生在语音被 record 之后
   数分钟到数天。60s 足够覆盖前者、远离后者。
2. 60s = `AntiRevoke.BLOCK_TTL_MS`，跟现有「同一个 msgId 的已拦过记忆」语义一致。
3. 短窗口的代价：若微信重试风暴把 DELETE 拖到 record 之后 60s 以上才重发会漏拦 —— 但见 §6
   的时序判据：若 DELETE 在 record 之前就已删掉行，拦重发也救不回已删的行，把 N 拉长到覆盖
   7.5 分钟风暴收益不大，反而扩大「误拦历史清理」的风险敞口。

### 1.4 受保护集合怎么来（时间窗落地）

`rescued` 表 `orig_type=34` 且 `at > now-60s` 的 msgId + 这些 msgId 的 `message.imgPath`。
⚠️ `GroupDb.Rescued` 没暴露 `at`/`imgPath`，所以 `VoiceGuard` 直读 `rescued` 表（模块自己的
AOSP 库）+ 反查 message 表拿 imgPath。这是对 GroupDb 内部 schema 的**轻耦合**，若将来 GroupDb
补了公开 API 可换过去（见 §4）。

---

## §2 判据表（输入形态 → 期望输出 → 理由）

| 输入 (table, whereClause, whereArgs) | 输出 | 理由 |
|---|---|---|
| table ≠ "voiceinfo"（message/rconversation/null） | false | 只认这张表 |
| table="voiceinfo"，whereClause=null | false | `delete(voiceinfo,null,null)`=删全表=整体清理 |
| rescuedImgs 为空 | false | 没有受保护标识 |
| whereClause 含 `select`（子查询） | false | 拿不到具体值，保守放弃 |
| whereClause 不含 `FileName/MsgLocalId` 的 `=`/`IN`（`<` `>` `LIKE` `IS NULL` `!=`） | false | 清理形态，绝不拦 |
| `FileName=?` + args 命中 rescued imgPath | true | 精确命中 |
| `FileName=?` + args 不命中 | false | 不拦 |
| `FileName IN(?,?,?)` + 部分命中 | true | 任一命中即拦 |
| `MsgLocalId=?` / `MsgLocalId IN(...)` + 命中 msgId | true | 精确命中 |
| whereArgs=null 但 whereClause 内联 26-hex 命中 | true | 非参数化形态，正则抽 hex |
| whereClause 有 `FileName=` 但抽不出 hex / args 非 hex | false | 拿不到可匹配值 |
| 大写 hex | true（hexTokensFrom 归一为小写） | 大小写不敏感 |
| 25/27/34 位 hex | false | 长度不对不误认 |

⚠️ **「26 位 hex」是实测值**：team-lead 任务描述写「24 位」，但真机 8 个独立样本
（6 条被救语音 imgPath + 2 条对照 FileName）实测**全是 26 位 hex**，24 是笔误。
判据按 26 位匹配（见 §6 补取项 3）。

---

## §3 测试清单（16 条 `@Test`，全部锁真机样本）

| # | 用例 | 锁什么 |
|---|---|---|
| 1 | nonVoiceinfoTableIsNotBlocked | 表名≠voiceinfo 一律 false（含 null 表名） |
| 2 | nullWhereClauseIsNotBlocked | 删全表（whereClause=null）不拦 |
| 3 | fileNameEqHit | `FileName=?` 命中 → true（含无空格 `=` 变体） |
| 4 | fileNameEqMiss | `FileName=?` 不命中 → false |
| 5 | fileNameInPartialHit | `FileName IN(?,?,?)` 部分命中 → true |
| 6 | msgLocalIdEqHit | `MsgLocalId=?` 命中 msgId → true |
| 7 | msgLocalIdInHit | `MsgLocalId IN(?,?)` 命中 → true |
| 8 | inlineHexMatchedWhenArgsNull | whereArgs=null 但 clause 内联 hex → 抽出并命中 |
| 9 | subqueryIsNotBlocked | 子查询（真机真实 SQL 形态）→ false（保守） |
| 10 | emptyRescuedIsNotBlocked | rescuedImgs 空 → 一律 false |
| 11 | noHexExtractableIsNotBlocked | clause 有 `=` 但抽不出 hex → false |
| 12 | uppercaseHexHandled | 大写 hex 抽出来、归一为小写、能命中 |
| 13 | wrongLengthHexNotRecognized | 25/27/34 位 hex 不误认 |
| 14 | cleanupOperatorsAreNotBlocked | `<` `>` `LIKE` `IS NULL` `!=` 一律不拦（关键保守性） |
| 15 | hexTokensFromExtractsMultiple | 一次抽多个 hex |
| 16 | hexTokensFromNullAndEmpty | null/空/无 hex 返回空表 |

---

## §4 风险与回滚路径

### 风险
1. **误拦微信正常清理（最严重）**：若 rescuedImgs 混进「很久以前 record 的语音」（时间窗失效），
   会把历史清理的 voiceinfo DELETE 拦掉 ⇒ 语音下载记录只增不减。缓解：60s 时间窗 + 只认
   `=`/`IN` 精确形态，`<` `>` `LIKE` 等清理形态与子查询一律放行。
2. **重试风暴刷日志**：命中后微信可能反复重试同一条 DELETE。缓解：`blockedLog` 按
   `whereClause+args` 做 key，同 key 60s 只打一条。
3. **对 GroupDb schema 的轻耦合**：直读 `rescued.msg_id/at` 字面量，GroupDb 改表结构会失效。
   缓解：全程 `runCatching`，失效只表现为「不拦」（功能退化，不崩，不影响其它功能）。
4. **hook 未生效**：wcdb 没找到 delete 重载时静默失效。缓解：install 里对
   `deleteWithOnConflict`/`delete` 分别打「installed / NOT found」日志（`VG installed ...`）。

### 回滚（从快到慢）
1. **开关关**：`VoiceGuard.cfg()` 读 `antirevoke.msg`，关闭即不拦（且时间窗内没有新 record，
   受保护集合自然为空）。
2. **去接线**：`HookEntry.kt` 注释掉 `VoiceGuard.install(lpparam)` 一行（我加的那块）。
3. **删文件**：`VoiceGuard.kt` / `VoiceGuardLogic.kt` / `VoiceGuardLogicTest.kt` 三个新文件删除，
   `HookEntry.kt` 撤掉 import + install 块，即完全回到改动前。

---

## §5 验证结果

`python tools/gradle_run.py testDebugUnitTest`（必须用这个脚本，直接 gradle 会
`ClassNotFoundException: test worker`）：

```
EXIT=0
BUILD SUCCESSFUL
```

汇总（解析 `app/build/test-results/testDebugUnitTest/TEST-*.xml`）：

```
files=29 tests=564 failures=0 errors=0
```

- 基线 548 条 + 本 lane 新增 16 条 = **564**，无 failures / errors，未倒退。
- `VoiceGuardLogicTest` 单独：`tests=16 failures=0 errors=0`。

---

## §6 需要主会话真机补取的判据

1. **⛔ 时序（最关键，决定方案 A 是否真能救回语音）**：撤回语音时，voiceinfo 的 DELETE 与
   message 的 UPDATE 谁先谁后？DELETE 是只发一次，还是跟着 UPDATE 一起被重试风暴重发？
   - 若 DELETE 恒在 record 之前（team-lead 所述「先 DELETE 后 UPDATE」的字面时序），且不重发，
     则第一次 DELETE 删行时我们还没 record、拦不住，A 只能拦「重发」，救不回已删的行 ⇒ 需要
     B（快照+回插）或「record 后按 message 数据重建 voiceinfo 行」的后续方案。
   - 若 DELETE 在 UPDATE 之后 / 会被重发，则 A 直接生效。
2. **DELETE 运行时形态**：真机撤回语音时 `delete("voiceinfo", ...)` 的 whereClause/whereArgs
   到底长什么样？是子查询 `FileName IN (SELECT imgPath FROM message WHERE talker IN (...))`，
   还是参数化 `FileName IN (?,?,?)` / `FileName=?`？—— 决定纯判据「保守放弃子查询」会不会让
   修复对真实场景完全无效（见 §1.2）。
3. **imgPath 长度**：8 个样本全是 26 位 hex（24 是笔误），需确认是否有别的长度
   （跨版本/跨消息类型），若有则 `HEX` 正则要改成范围匹配。
4. **加载与命中日志**：撤回语音时日志里是否出现 `VG installed`（hook 装没装上）与
   `VG blocked voiceinfo delete`（判据命中没命中）。

[DONE]
