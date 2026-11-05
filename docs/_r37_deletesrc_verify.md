# R37-10 复核：好友删动态到底动了什么？（离线 lane，Pro 档）

> 复核对象：`docs/派发审计台账.md` R37-10（:1907-1929）+ R37-11 初步判断（:1933-1960）
> 原结论原文：
> 「好友删动态时，微信**不删行**，只把 `sourceType` 从 10 改成 8（不可见），行和内容全部保留。
> ⇒ `SnsGuard.onDelete`（挂 `SQLiteDatabase.delete`）根本不会被调用 ⇒ `sns_archive` 零新增
> ⇒ 灰标对『好友删动态』永远不会打标。」
>
> lane 约束：只读分析（不碰 `app/src/main` 源码、不 adb、不 commit、不 gradle）。唯一产物 = 本文件。
> 铁律：凡属推断一律标「**假设**」，并附 1 条可证伪它的探针命令。
> 时间：2026-11-01 夜，设备 467cf5bc 日志为二手引用（主会话真机取证，视为可信原始材料）。

---

## §1 逐子命题裁决表

| # | 子命题 | 裁决 | 依据（文件:行） | 证据强度 |
|---|---|---|---|---|
| P1 | 微信不删行 | ✅成立（**单条定点**口径） | 受控实验 5 拍行恒在 `派发审计台账.md:2113-2121`、`:2161-2163`；删除通道**开关关着也打点** `SnsGuard.kt:418`/`:461`；⚠️ 缺口见下方「P1 边界」 | 中 |
| P2 | 只把 sourceType 从 10 改成 8 | ❌**不成立**（四条独立反例；**任何** `sourceType` 变化都不可归因） | ① 受控实验是 `2→10`（**上升**）`派发审计台账.md:2094`/`:2161`；② `2→10` 由模块自己写 `SnsTimelineSourceFix.kt:365-366` 经 `SnsGuard.kt:332-339`；③ 全表分布 `8`=29,685 / `0`=16,330 ⇒ 杂值是常态；④ 21:28:34 现场抓到模块**全表批量 `8→10`**（`pages=30 fixed=500 skip=3500`），与被删与否无关 | **高**（四条独立） |
| P3 | 行和内容全部保留 | ✅成立（行 + 结构化字段）；content 字节层**中** | 5 拍 `type/localFlag/dieBit/priv/flags/ct` 逐字相同 `派发审计台账.md:2121`/`:2162`；无任何代码路径改 content：UPDATE 侧只写 sourceType `SnsTimelineSourceFix.kt:558`/`:372` | 行=高 / content=中 |
| P4 | 灰标挂 DELETE 上 ⇒ 对好友删动态打不了标 | ✅成立（**结论对，依据表述须订正**） | 归档唯一入口 `GroupDb.kt:417`，全项目唯一调用点 `SnsGuard.kt:649`，只由 `onDelete:433`/`onExecSql:470` 进入；`onSnsUpdateAfter`（`:309-368`）**全程无归档**；读端 `SnsDeletedTag.kt:1690` → `GroupDb.kt:537` 只查 `T_SNS` | 高 |

---

## §2 逐条论证（落到代码行，不抄事实链）

### P1 微信不删行 —— ✅成立，但**只在「单条定点 DELETE」口径下**

**支持面**
- 受控实验（verity|1，负 id `-3401376990577159657`）五拍按 id 查库，行恒在：
  删前 `src=2`(23:40) → 删后立即 `2`(23:41) → 下拉 `2`(23:42) → msync `2`(23:43) → 真实刷新后 `10`(23:48)。
  `派发审计台账.md:2113-2121`（表）、`:2160-2163`。
- 模块的删除通道**即使开关关着也会打点**：`SnsGuard.onDelete` 在 `!c.enabled` 时打
  `SNS delete seen (off) snsId=$id`（`SnsGuard.kt:417-419`），`onExecSql` 同款（`:460-462`）。
  ⇒ 「零命中」不是「钩子没装 / 开关没开」，是真的没发生删除调用。

**⚠️ 边界（必须写进结论，否则结论会被误用）**
1. **批量清理通道是结构性盲区**。`SnsSql.matchDeleteCall`（`SnsSql.kt:51-59`）只认
   `whereClause` 里出现 `snsId=`；注释明写 `createTime < ?` / `snsId < ?` 这类过期清理
   **一律不拦**（`SnsSql.kt:15-16`、`:54`）。⇒ 「不删行」的证据只覆盖**单条定点**删除，
   不覆盖「按时间窗批量清旧动态」。
2. **`matchDeleteCall` 有已知误取首参 bug**：台账 `:1900-1903` 记到一条
   `SNS blocked delete snsId=9223372036854775807 snap=false`（= `Long.MAX_VALUE` 假值），
   说明存在某类 `whereClause` 让它解析出哨兵值 ⇒ **噪声条**会污染 `blocked` 计数与
   `T_SNS` 判据（`argAt` 的序号推导 `SnsSql.kt:95-115` 在参数类型非 Long/Int/String 时返回 null，
   但越界/形态异常时仍可能溢出）。
3. **受控实验唯一一次强制同步失败**：`onGYNetEnd errType=1`（`派发审计台账.md:2117`、`:2127`），
   微信对高频同步节流。⇒ 「三拍 src 不变」只证明**本地存储层此刻没动**，
   **封不死**「删除延迟落地」这条口子（R37-14 里 123 是**过了一阵**才 `10→8`，延迟分钟级，`:2056`）。

**可证伪探针**（若 P1 错，这条会红）：
```bash
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
  --es op snsg --es sub stat
# 预期（P1 成立）：blocked/archived 不变；若出现新的
# "SNS blocked delete snsId=<目标id> snap=true" ⇒ P1 被推翻
```

---

### P2 「只把 sourceType 从 10 改成 8」 —— ❌不成立（三个方向都错）

**① 方向错：受控实验里 src 是 `2 → 10`，是上升不是下降**
- 删前基线 `src=2`（`派发审计台账.md:2094`），真实刷新后 `src=10`（`:2161`）。
- `2 or 8 = 10` ⇒ 补的是 **bit3（相册位）**。

**② 作者错：`2→10` 是模块自己写的，不是微信写的**
```
SnsGuard.onSnsUpdateAfter                      SnsGuard.kt:309
  ├─ ① 表名 != "SnsInfo" → 返回                 :313
  ├─ ② 读 ContentValues.sourceType（反射）       :316
  ├─ st != 0 分支：                              :325-348
  │    (st and ALBUM_BIT) == 0                   :332
  │    && bypass != true                         :332
  │    && SnsTimelineSourceFix.isAlbumTarget(id) :334   ← 窄台账闸门
  │    ⇒ SnsTimelineSourceFix.patchAlbumBit(...) :339
  └─ st == 0 分支：延迟 350ms 批量补             :354-367
```
```
SnsTimelineSourceFix.patchAlbumBit              SnsTimelineSourceFix.kt:364
  val target = curSrc or SnsTimelineSrcLogic.ALBUM_BIT   :365   ← 2 | 8 = 10
  if (target == curSrc) return 0                          :366
  if (!enabled()) return 0                                :367
  if (isPrivate(curSrc)) return 0                         :370   ← 私密位闸门
  updateSourceType(db, snsId, target)                     :371
```
`ALBUM_BIT = 8`（`SnsTimelineSrcLogic.kt:44`）。`isAlbumTarget` 查的是**窄台账**
`listSrcFixedIds`（`SnsTimelineSourceFix.kt:284-294`），60s TTL 缓存。

⇒ 台账 `:2219` 那句「模块确实写了库」是对的，但**写的是相册位，与「删除」无关**：
它只是「微信按服务端值整行 upsert 把 10 打回 2 时，模块把 bit3 补回去」的日常补位
（`SnsGuard.kt:326-331` 的 R30 §6.2 注释即是此意）。

**③ 唯一一次真的 `10→8` 是清 bit1 的副作用，且不是受控实验那条**
- 123 的 src 从 `10` 变 `8`（`派发审计台账.md:2048`、`:2056-2057`）
  = `& ~2` = 清 **bit1（时间线位）**，形态 `UPDATE SnsInfo SET sourceType = sourceType & -3`
  （`SnsTimelineSrcLogic.kt:81-82` 的 `CLEAR_SQL_RE`）。
- 但「10 改成 8」这个**表述本身**是错的：它是**位清除**（`& -3`），目标不是「8」这个字面值，
  只是恰好 10 清掉 bit1 剩 8。若行原本是 `2`，清 bit1 后是 `0` 而不是 8。

**④ 「只」字更不成立 —— 微信至少有两种清位 SQL，模块只认一种**
- `isSourceClearSql` **只匹配 `& -3`**：`CLEAR_SQL_RE = sourcetype\s*=\s*\(?\s*sourcetype\s*&\s*\(?\s*-3\b`
  （`SnsTimelineSrcLogic.kt:81-89`）。
- 真机抓到过 **`& -9`（清 bit3）** 的按作者清位：
  `UPDATE SnsInfo SET sourceType = sourceType & -9 WHERE SnsInfo.userName="wxid_xlrbelf6hv0w22" AND (sourceType in (8,264,10,266,...))`
  （`派发审计台账.md:1970-1973`）。
- 这条**不被 `isSourceClearSql` 认领** ⇒ 只走 `noteUnmatchedSourceWrite`
  （`SnsGuard.kt:207-210`）：**只打 3 条采样日志，不补位、不记账、不归档**。

**⑤ ⭐ 第三条独立反例（2026-11-02 21:28:34 现场）：模块自己在**全表批量** `8 → 10`**
```
SNS srcfix snsId=-3411920557204098535 8 -> 10 rows=1
SNS srcfix snsId=-3411921946274557426 8 -> 10 rows=1     …（连续多条，全是 8 -> 10）
SNS srcstat snap=3000 fixed=500 skip=3500 priv=3 gone=0 fail=0 err=0 new=0 pages=30 ledger=7686 held=2834
SNSG SNS stat enabled=true blocked=2 archived=0 snsDb=true
```
`pages=30` / `fixed=500` / `skip=3500` ⇒ 这是 `SnsTimelineSourceFix.run()` 的**按页全表扫描**
（`SnsTimelineSrcLogic.PAGE_SIZE=100` × `MAX_SNAPSHOT_PAGES=30`，`SnsTimelineSrcLogic.kt:54-58`），
补的是**台账里所有缺 bit1 的行**，与「某条动态被谁删了」**没有任何因果关系**。
目标动态（`-3401376990577159657`）此刻仍是 `8`，**只是还没轮到它那一页**。

⇒ ⛔ **必须修正 R37-17 的一处反向推断**：台账 `:2161`/`:2219` 曾把「用户刷新后 `src 2→10`」
当作「模块确实对这条动了手」的证据。**现在看它只是全表批量补位的副作用**，
与该条是否被删**无关**。凡引用「模块对这条写过库」做推理的地方，一律作废。

**⑥ 第四条独立反例：全表 `sourceType` 分布证明「位缺失」在正常动态里遍地都是**
21:24 真机 `select sourceType,count(*) from SnsInfo group by sourceType`：

| sourceType | 行数 | 说明 |
|---|---|---|
| 2 | 158,853 | 只有 bit1（时间线） |
| **8** | **29,685** | **只有 bit3，无 bit1** —— 与「被删」同形 |
| 10 | 28,477 | bit1\|bit3 |
| 130 | 16,742 | 128\|2 = **正常折叠动态**（`docs/conclusions/moments.md:206-207`） |
| **0** | **16,330** | **死来源，正是「清 bit1 后」的同形态** |
| 22 / 84 / 6 / 86 / 70 / 4 / 68 / 12 | 200 | 杂值 |

⇒ `src=8` 有 **29,685 条**、`src=0` 有 **16,330 条**。这两个值恰好分别是
「10 清 bit1」与「2 清 bit1」的结果形态 ⇒ **用「bit1 清了 / src 变成 8 或 0」判「被删」，
在正常库里会命中几万条**。这不是「用错了台账」，是**判据本身没有区分度**。

**⚠️ 一处源码注释与实测冲突，引用时必须降级为「假设」**
`SnsTimelineSourceFix.kt:221` 写「正常动态 sourceType 天生就是 10」——
**与实测不符**：全表 `2` 占 158,853（绝对多数），最近 12 条按 `createTime desc` 里
10 条是 `2`、2 条是 `0`，**一条 10 都没有** ⇒ 现状是 **`2` 为主**。
（`fixAlbum:213-235` 的 `already=229` 事故正建立在这句错误前提上：
它以为「天生 10 含 bit8」，实测正常动态多为 `2`、根本不含 bit8。）
⛔ 凡引用该注释推理的地方，必须标「**假设**」并注明与实测冲突。

**⛔ P2 的裁决要升级表述（不只是「10→8 不成立」）**
> **`sourceType` 的任何变化（2→10 / 10→8 / 8→0 / →0）都不能用于归因，更不能用于判据。**
> 原因：这个字段**至少有两个写入主体**（微信的清位 SQL `& -3`/`& -9`、模块的批量补位 `| 10`），
> 且两者的写入**都与「这条动态是否被删」无因果关系**。观察到一个位变化，
> 无法反推是谁写的、更无法反推为什么写。

**可证伪探针**（若 P2 错——即微信真的把 10 设成 8——这条会红）：
```bash
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
  --es op snsg --es sub srcfix --es sub2 dump --es val "<目标负id>"
# 关键：同时盯 albumpatch 计数
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
  --es op snsg --es sub srcstat
# 若 src 变成 8 而 "SNS albumpatch" 计数未增 ⇒ 是微信清的（10&~2）；
# 若同时出现 "SNS albumpatch snsId=... 2 -> 10" ⇒ 是模块补的（P2 的表述不成立）
```

---

### P3 行和内容全部保留 —— ✅成立（行 + 结构化字段）；content 字节层只有中等强度

**支持面**
- 受控实验 5 拍：`type=2 localFlag=2 dieBit=false priv=Long.MIN flags=ExtFlag ct=11-01 23:38`
  **逐字相同**（`派发审计台账.md:2121`、`:2162`）。
- 123 四次查库（23:02 / 23:09 / 23:18 / 23:25）逐字相同（`:2017-2019`、`:2081`）。
- **代码侧无任何改 content 的路径**：
  - `onDelete` → `restoreVisibility` 只写 `localFlag`（`SnsGuard.kt:486-496`），且好友删动态**不触发**；
  - `onSnsUpdateAfter` → `patchAlbumBit` 只写 `sourceType`（`SnsTimelineSourceFix.kt:371` → `updateSourceType:1059`）；
  - `reFixAfterClear` 只发 `update ... set sourceType = sourceType | 10`（`SnsTimelineSourceFix.kt:558`）；
  - `saveAsync` 写的是**模块的** `wxgroup.db`（`GroupDb.kt:417`），不碰微信库。

**⚠️ 强度为何只给「中」（content 字节层）**
受控实验打的是 `SnsGuard.probe`/`dumpRows` 的结构化字段（`SnsTimelineSourceFix.kt:1024-1058`、
`:776-843`），**没有 content 长度/哈希**。严格地说「content 字节未变」是
「无改写路径 + 相邻字段全同」推出来的，未做逐字节比对。
真机补这一档只需跑 `op=snsg sub=srcfix --es sub2 one --es val <id>` 记 `content.length` 前后。

---

### P4 灰标挂 DELETE 上 ⇒ 对「好友删动态」打不了标 —— ✅成立，但**依据表述必须订正**

**⛔ 先订正 R37-10 原文的一处事实错误**
原文（`派发审计台账.md:1922`）写「`SnsGuard.onDelete`（挂在 `SQLiteDatabase.delete`）」——
**实际挂了三条**，不是一条（`SnsGuard.kt:111-123`）：
```
XposedBridge.hookAllMethods(cls, "delete",               deleteGuard)      :112
XposedBridge.hookAllMethods(cls, "execSQL",              sqlGuard)         :115
XposedBridge.hookAllMethods(cls, "updateWithOnConflict", snsUpdateGuard)   :121   ← R27 补
```
`SnsGuard.kt:133` 的自述也明确「常驻的只有 delete / execSQL / updateWithOnConflict 三个」。
⇒ **正确的表述是**：「**归档**（灰标的唯一数据源）挂在 DELETE 通道（含 execSQL 形态）上；
UPDATE 通道只做补位、**不归档**，所以好友删动态打不上标」。

**漏挂盘点（逐条核对，不是凭印象）**

| 通道 | 是否挂 | 依据 | 好友删动态会走它吗 |
|---|---|---|---|
| `SQLiteDatabase.delete` | ✅ | `SnsGuard.kt:112` | 否（受控实验零命中） |
| `execSQL` | ✅（before+after） | `SnsGuard.kt:115`、`:176-189` | 否（同上） |
| `updateWithOnConflict` | ✅（after） | `SnsGuard.kt:121`、`:241-246` | **是**（整行 upsert）⇒ 但**不归档** |
| `update()` 本身 | 未挂，**但不漏** | `update` 内部必走 `updateWithOnConflict`（`SnsGuard.kt:233`） | — |
| `deleteDatabase` | 未挂 | 全 `app/src/main/java` 零引用（仅 `VoiceGuard.kt:127` 注释提及） | 否（整库删，与本场景无关） |
| `insert` / `insertWithOnConflict` | 未挂（有意） | `SnsGuard.kt:133-135` 明写「挂上去等于给每次写库加一层开销」 | 无关 |
| `rawQuery` | 挂了但**拿到句柄即自我卸载** | `SnsGuard.kt:575`、`:615-621`、`:129-132` | 只读，无关 |

⇒ **没有漏挂**。灰标打不上标的根因**不是钩子挂少了**，而是
「好友删动态走 UPDATE 通道，而 UPDATE 通道的出口只写 `sourceType`、**不写 `T_SNS`**」。

**归档/打标数据链（唯一调用点，代码可证）**
```
SnsGuard.onDelete  ──► saveAsync(row) ──► GroupDb.archiveSns ──► T_SNS(sns_archive)
   SnsGuard.kt:433        :642-657            GroupDb.kt:417          GroupDb.kt:50
SnsGuard.onExecSql ──► saveAsync(row) ──►（同上）
   SnsGuard.kt:470
SnsGuard.onSnsUpdateAfter ──► patchAlbumBit  ──► 不归档（:309-368 全程无 saveAsync）
```
读取端：
```
SnsDeletedTag.loadIds ──► GroupDb.snsTagIds ──► SELECT sns_id FROM sns_archive
   SnsDeletedTag.kt:1690      GroupDb.kt:537-544
```
`archiveSns` 全项目**唯一**调用点只有 `SnsGuard.kt:649`（已 grep 确认）。
⇒ 只要 DELETE 通道不触发，`T_SNS` 就**结构上**不可能新增，灰标必然打不上。

**⛔ R31h 红线在代码里的落点（本 lane 未触碰、也未主张回退）**
- `GroupDb.snsTagIds` 的 KDoc（`GroupDb.kt:516-536`）逐字记录了事故：
  旧判据是 `T_SNS ∪ (T_SRC WHERE fixed_orig IS NOT NULL)`，补位范围扩到全量后
  `fixed_orig` 涨到 **3447 条** ⇒ 正常动态全被判「已删除」。
- 现判据只查 `T_SNS`（`GroupDb.kt:540`）。代价明写在 `:533`：
  「走 `UPDATE sourceType=0` 那条路径被删的动态不再有灰标」—— **这正是 P4 描述的现状**。
- `SnsDeletedTagLogic.taggedIds` 的 KDoc（`SnsDeletedTagLogic.kt:62-64`）同样红线：
  「⛔ 曾经传 `GroupDb.listSrcFixedIds()`（补位台账）…… R31h 真实事故」。
- ⇒ **本 lane 的任何建议都不得把 `T_SRC` / `fixed_orig` / 补位日志重新拉回「已删」判据**。
  新增判据必须是**独立列 / 独立信号**，见交付 2。
- ⛔ **2026-11-02 追加**：`sourceType` / `serverExtFlag` / `localFlag` / `subType`
  这四个**行字段整类**也必须排除（真机实测在同一作者 10 条上完全同形，见交付 2 §1.5）。

---

## §5 附带待解点：`blocked=2 archived=0` —— 归档写入链路

21:28:34 的 `SNSG SNS stat enabled=true blocked=2 archived=0 snsDb=true`
（对照 11-01 基线 `blocked=1 archived=0`，`派发审计台账.md:2096`）⇒ 拦截又发生了一次，
**但 `sns_archive` 仍然零行**。这是**独立于灰标判据**的第二个问题，值得单独看。

**链路（代码可证，`saveAsync` 是唯一路径）**
```
onDelete  → blocked.incrementAndGet() → readRow(db,id) → saveAsync(row)   SnsGuard.kt:427 / :424 / :433
onExecSql → blocked.incrementAndGet() → readRow(db,id) → saveAsync(row)   SnsGuard.kt:467 / :464 / :470
saveAsync : if (row == null) { LogX.w("SNS archive skipped: no snapshot"); return }   :643-646
            GroupDb.current()?.archiveSns(row) ?: false                               :649
            if (ok) archived.incrementAndGet()                                        :650
archiveSns: snsId==0 → false；hasSns() → true（不插入也返回 true）；
            insertWithOnConflict(CONFLICT_IGNORE) != -1L                  GroupDb.kt:417-440
```

**三种可能，按可能性排序（都可一行日志分流）**

| # | 原因 | `archived` | 决定性日志 |
|---|---|---|---|
| 1 | **`readRow` 返回 null ⇒ `snap=false`**（`saveAsync` 早退，**根本没调** `archiveSns`） | 不增 | `SNS blocked delete snsId=... **snap=false**` + `SNS archive skipped: no snapshot` |
| 2 | **`GroupDb.current()` 为 null**（模块库没开） | 不增 | `SNS archived snsId=... **ok=false**` |
| 3 | `archiveSns` 插入失败（CONFLICT_IGNORE 撞主键 / 异常） | 不增 | `SNS archived ... ok=false` 或 `SNS archive failed: ...` |

**⛔ #1 概率最高，且有同形前科**：台账 `:1900-1903` 已记到
`SNS blocked delete snsId=9223372036854775807 **snap=false** total=1`
—— `matchDeleteCall` 把某类 `whereClause` 误取首参溢出成 `Long.MAX_VALUE`（§P1 边界第 2 条）。
**假 id 点查必然查不到行** ⇒ `snap=false` ⇒ 归档跳过。
⇒ 若 `blocked=2` 里有一条是这种假 id，`archived=0` 是**自洽的**：
**不是归档链路坏了**，是**拦到了不存在的行**（`snap=false` 这个字段正是为此存在的）。

**定性探针（一行分流）**
```bash
adb shell logcat -d | grep -E "SNS blocked delete|SNS archived|SNS archive skipped|SNS archive failed"
# ① 看到 "snap=false" ⇒ 原因 1 ⇒ 归档链路没问题，要修的是 SnsSql.matchDeleteCall 的越界分支
# ② 看到 "ok=false"   ⇒ 原因 2/3 ⇒ 才是归档链路真坏了
```
⚠️ 这条**不影响**灰标判据的设计结论（`T_SNS` 无论有没有数据都覆盖不到好友删动态，P4 已证），
但它决定「自己删自己的动态」这个场景到底有没有留档 ⇒ 建议单独开一条待办。

---

## §3 裁决摘要（一句话版）

| # | 一句话 |
|---|---|
| P1 | ✅ **不删行**（仅「单条定点 DELETE」口径；批量清理通道对模块不可见） |
| P2 | ❌ **错，且要升级表述**：`sourceType` 的**任何**变化都**不可归因**（微信清位 + 模块全表批量补位，两个写入主体都与「是否被删」无因果）。四条独立反例 |
| P3 | ✅ **行与结构化字段全部保留**；content 字节层证据强度中（未逐字节比对，但无改写路径） |
| P4 | ✅ **结论对，依据表述错**。不是「只挂了 DELETE」，而是「**归档只挂在 DELETE 通道**；UPDATE 通道只补位不归档」；钩子一个都没漏挂 |

**（交付 1 完）**

---

## §4 交叉引用

- 交付 2（灰标「确实被删」正向判据设计）：`docs/_r37_graytag_datasource.md`
  - §1 逐条否掉 `sourceType` 位 / 补位日志 / DELETE 拦截三个候选（含 R31h 反例原文）
  - §2 候选评分表：H3b（`x2#U`/`x2#O`）为唯一「服务端下发的正向信号」
  - §3 判据表（11 个组合，含 `blocked` ↔ `restored` 互斥）
  - §5 双向对照验证方案 + 需新增的探针字段（N1~N5，未改代码）

- ⛔ 与 P4 相关的一条**待办**（属实现，本 lane 未做）：`GroupDb.snsTagIds`（`GroupDb.kt:533`）
  的 KDoc 里「已知代价」那句话现在有了对应的解法候选，但**落地前必须先跑 §5.2 的 U1 实验**。

[DONE]

