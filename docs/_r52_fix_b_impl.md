# R52 Pro · lane B 实现报告：feature 层 id 反查接线

> 本文件：仅 `docs/_r52_fix_b_impl.md`（lane B 报告，可写）。
> 独占改动文件：**仅** `wxgroup/app/src/main/java/com/lx/wxgroup/feature/MediaTableGuard.kt`。
> ⛔ 不碰 `data/MediaDeleteLogic.kt` / `test/.../MediaDeleteLogicTest.kt`（lane A）、
> `AutoHdImg.kt` / `EntryReceiver.kt` / `RevokeRescue.kt` / `VoiceFileGuard.kt` / `Keys.kt`；
> 不碰 adb / 不装包 / 不跑 gradle / 不写微信库 / 不改开关默认值。
> 路线：**甲（id 反查法）**。lane B 只做 feature 层「反查 + 接线」，判据纯函数由 lane A 并行实现。

## 0. 决定性真机证据与因果链（复盘，非推断）

```
01:03:59.080 MTG delete table=ImgInfo2 where=id=? reason=shouldBlock-false gen=1
01:04:37.180 MTG stat gen=1 seen=43 matched=1 blocked=0 skipped=1 verdict=REAL_EVENT tokens=1 events=1
01:06:34.879 HDIMG trace ... ctor=n=5 a[0]=Long:0 fld[h=0 i=0 j=0 o=0 q=19640720] img=null
01:06:34.881 HDIMG trace db msgId=19640720 imgPath=THUMBNAIL_DIRPATH://th_3fadbde02a26c4ab4034c3ab3a604e51
```

- 微信撤回删 `ImgInfo2` 走 `delete()`，钩子命中表（`matched=1`）、保护池有 md5（`tokens=1`）、
  `invalidate()` 已跑（`gen=1`），但 `shouldBlock=false` 放行 ⇒ 索引行被删 ⇒ 大图组件参数全 0、
  `img=null` ⇒ **用户看不到大图**。
- 根因：判据只认 md5 token，删除却是 `id=?` 数值主键（`a[0]=Long:0` / `where=id=?`）。
- ⭐ 因果链闭合：`message.imgPath` 在、磁盘文件在（连 `th_…_tmp.jpg` 都被 `VFG` 保住），
  **唯一断点就是这一行被删** ⇒ 预期只需拦住它，不必改渲染层 / 不必重建消息 / 不必额外写库（仍 `[需真机]`）。
- ⛔ 主判据恒为「撤后隔 3~5 分钟点开能否看到**完整大图**」；
  `seen/matched/blocked/skipped/rows/tokens/gen/verdict` 只作定位，⛔ 不当结论。

---

## T1 现状核对（带 `文件:行号`）

### T1.1 `MediaTableGuard.kt` 关键符号逐条（分段 Read，⛔ 未整读 >300 行）

> ⚠️ 下表 `文件:行号` 均为 **R52 探针版（改前）** 的现状；改后行号见 T2 / T3 / T5（本轮新增块另列）。
> 设计文档 `docs/_r52_fix_design.md` 的行号以 **01:26 追加章节后版本**为准（design lane 后续再追加会漂移，引用同时给章节名）。

| 符号 | 位置 | 现状（R52 探针版，已装包验过） |
|---|---|---|
| `object MediaTableGuard` / `TAG="MTG"` / `WCDB_DB` | `:58`, `:61`, `:63` | object 单例；hook `com.tencent.wcdb.database.SQLiteDatabase` |
| `WINDOW_MS=600_000L` | `:70` | 撤回期窗口 10min，与 `VoiceFileGuard.PROTECT_MEDIA_MS`（`VoiceFileGuard.kt:112`）一致 |
| `MAX_EVENTS=64` / `MAX_LOG_KEYS=64` / `LOG_TTL_MS=60_000L` | `:73-75` | 账本明细封顶 / 日志节流 key 封顶 / 60s 节流 |
| `FAKE_ROWS=1` | `:78` | delete 命中时伪装受影响行数 |
| `enabled` / `gen` / `lastIssuedMs` | `:81`, `:85`, `:89` | 三个 `@Volatile` 运行时状态 |
| **`cachedTokens: Set<String>`** | **`:93`** | `@Volatile`，`invalidate()` 重建的存活 token 只读快照；本轮 `cachedRowIds` 与之同构 |
| `mediaPoolRef: Map<String,Long>?` | `:97` | install 期反射到 `VoiceFileGuard.protectedMedia` 的只读引用 |
| 账本 `seen/matched/blocked/skipped` | `:101-104` | 4 个 `AtomicLong` |
| `events` / `logAt` / **`obsLogAt`** | `:107`, `:109`, `:112` | `obsLogAt` 与 `logAt` **分离**（`:110-112` 注释：防热路径高基数 key 挤占 4 个固定 key） |
| **`install(lpparam)`** | **`:116-146`** | 反射类 → `refresh()` → `resolvePool()` → `hookAllMethods(cls,"delete",…)` / `("execSQL",…)`；`installed hooks=…` 日志 |
| **`refresh()`** | **`:149-151`** | 只 `enabled = Kv.getBool(Keys.ANTIREVOKE_MEDIA_DELETE)`；注释明写只在 install/invalidate 调，⛔ 不在 before 调 |
| **`invalidate()`** | **`:159-164`** | `gen++` → `lastIssuedMs=elapsedRealtime()` → `cachedTokens=snapshotLiveTokens()` → `runCatching{refresh()}`；⛔ 本轮反查挂这里 |
| **`stat()`** | **`:167-176`** | `MTG stat enabled/gen/seen/matched/blocked/skipped/verdict/pool/tokens/events/switch` |
| **`resetLedger()`** | **`:185-193`** | 只清 4 计数 + `events/logAt/obsLogAt`；注释明写运行时状态一律不动、与冷启整体清零语义不同 |
| `deleteGuard` / `execSqlGuard` | `:197-216` | `beforeHookedMethod` 各自 try/catch 包 `onBefore`，异常不外泄（保护后续 callback） |
| `Parsed` | `:219-223` | `(table, where, args)` 只读解析结果 |
| **`onBefore(param, execSql)`** | **`:229-289`** | 整段 try/catch；① `!enabled` → ② `args/parse` → ③ `isTargetTable` → ④ 判据 → ⑤ 命中 |
| `fromDelete` / `fromExecSql` | `:292-298`, `:307-328` | 只读取参 / 拆裸 SQL |
| `resolvePool()` | `:336-347` | install 期反射 `protectedMedia`；失败 ⇒ 快照恒空 ⇒ 永不拦（安全降级） |
| **`snapshotLiveTokens()`** | **`:350-360`** | 只收未过期（`v>=now`）key，统一 `lowercase()`；`mediaPoolRef==null` ⇒ 空集 |
| `noteEvent` | `:365-376` | 命中目标表后事件明细去重 + 封顶 |
| `throttledLog` / **`observe()`** / **`emitThrottled()`** | `:380-382`, **`:394-401`**, **`:404-411`** | 全量记账观测：按 `obs|reason|table` 节流、`lazyMsg` 通过节流后才拼、`LogX.i` |
| **`param.result` 赋值点** | **`:274`（execSQL→`null`）/ `:277`（delete→`FAKE_ROWS`）** | 全文件仅此 2 处 |

**`onBefore` ④ 现状（`:253-268`，逐字）**：
```
val tokens = cachedTokens
if (tokens.isEmpty()) { skipped++; observe(execSql,p,"tokens-empty"); return }   // ← 本轮要对齐的短路
val nowMs = SystemClock.elapsedRealtime()
if (!MediaDeleteLogic.shouldBlock(p.table, p.where, p.args, tokens, nowMs, lastIssuedMs, WINDOW_MS)) {
    skipped++; observe(execSql,p,"shouldBlock-false"); return
}
```
⑤ 命中（`:270-284`）：按 `execSql` 二选一置 `param.result`，`blocked++`，`throttledLog("block", …)` 打
`table/where/reason/gen` 5 字段。放行分支都是**不置 result 的裸 return**（`:232/236/242/248/255/266`），
保护同方法上 `VoiceGuard`/`SnsGuard` 的后续 before callback。

### T1.2 反查可用的 DB 句柄来源（自行确认，非照抄）

- **入口**：`AntiRevoke.msgQueryHandle(): Pair<Any, Method>?`（`AntiRevoke.kt:851-855`），
  `internal` 同模块同包（`MediaTableGuard` 也在 `com.lx.wxgroup.feature`）⇒ **可直接调用**。
  返回 `(msgDb, rawQueryMethod)`；任一缺失返回 `null`（`:847-849` 明写「调用方据此打一行说明，
  **不要**退到别的东西上重试」，`:819-826` 记过退 `lastDb` 写错库的教训）。
- **是不是 `EnMicroMsg.db` / `ImgInfo2` 所在库**：`msgDb` 在写库 hook 里按
  `table == TABLE_MESSAGE` 捕获 `param.thisObject`（`AntiRevoke.kt:515-516`）；
  `wxDb()`（`:828`）与 `:817-826` 注释确认它就是 **message 表所在的那个消息库实例**。
  `ImgInfo2` 与 `message` **同库**：设计文档 `:158` 引 `_r35_revoke_img_anchor.md:73-79` 的关联式
  `SELECT bigImgPath,thumbImgPath FROM ImgInfo2, message` 证明两表可联查（同库才可）；
  真机 `op=label sub=sql` 走 `AntiRevoke.wxDb()` **主库通道**已能查到 `ImgInfo2`
  （设计文档 `:414` 明写 `op=sql` 走朋友圈 `snsDb` 会 `no such table`，只能验通道、不能当主库判据）。
  ⇒ **句柄可用**，且 `msgDb` 在 `AntiRevokeHint` 读取侧 hook 里也会 `noteMsgDb(param.thisObject)`
  提前捕获（`AntiRevokeHint.kt:206-212`），冷启后微信一查 message 表即就绪，撤回发生时通常非空。
  `[需真机]`：`ImgInfo2` 与 `message` 同库这一条靠 `op=label sub=sql` 复核（设计文档 T5 第 4 步）。
- **`rawQuery` 反射取法**：`AntiRevoke.rawQueryMethod(db)`（`:1061-1075`）与
  `RevokeRescue.rawQueryMethod(db)`（`:566-579`）同款：在 `db.javaClass.methods` 里找
  `name=="rawQuery" && 2 参 && 第 1 参 String && 第 2 参 String[] 或 Object[]`，带 `@Volatile` 缓存。
  ⚠️ **参数数组类型必须跟查到的形参一致**（`AntiRevoke.kt:889-893`、`RevokeRescue.kt:172-176`）：
  `Array<String>::class.java` ⇒ `arrayOf(md5)`；否则 `arrayOf<Any>(md5)`。
  不一致会 `IllegalArgumentException`，表现就是「查不到」（项目早期踩过）。
  Cursor 用法（`RevokeRescue.kt:177-210,278-284,685-696`）：
  `m.invoke(db, sql, args)` → `XposedHelpers.callMethod(cur,"moveToFirst")` →
  `getLong(0)` / `getColumnIndex` → `finally { callMethod(cur,"close") }`。
- **`AntiRevokeHint.BYPASS` 前缀必要性**：`BYPASS = "/*w*/"`（`AntiRevokeHint.kt:62`）。
  `SqlHideCond.isMessageSelect`（`:391-401`）只认 `select` 开头 + `from message`，加前缀后不以
  `select` 开头 ⇒ 不改写（`:397` 注释明写）。`AntiRevokeHint.install` 同时 hook 了
  `rawQuery` 与 `rawQueryWithFactory`（`:177-183`），本次反查走 `rawQuery` 会进该钩子。
  ⚠️ 本次 SQL 查 `ImgInfo2`、不含 `message` 表，`isMessageSelect` 本就返回 false ⇒ **严格说不改写也成立**；
  但项目所有诊断 SQL 统一带 BYPASS（`RevokeRescue.kt:179,269,687`、`AntiRevoke.kt:951`），
  设计文档 `:158` 亦要求带 ⇒ **按惯例带上**，零成本、防未来 hook 扩展误伤。

### T1.3 句柄不可用时的替代方案（`[需真机]`，⛔ 不凭猜测硬写）

- `msgQueryHandle()==null` / `m.invoke` 抛异常 / `rawQuery` 找不到 ⇒ **不重试、不退 `lastDb`**，
  直接判 MISS：留一行日志，`cachedRowIds=emptySet()` 保守放行（设计文档 `:162-166`）。
- 若真机证实 `ImgInfo2` 不在 `msgDb`（与设计文档 `:158` 结论相悖）⇒ 本轮**停手按 D3 回头**
  （`CLAUDE.md:45-46`），先复核通道，⛔ 不自己另找库硬写、不退化成乙「窗口内目标表全拦」。
- `[需真机]` 清单：`thumbImgPath` 是否 BLOB 列、池内 lowercase md5 与列内大小写是否一致、
  `instr(thumbImgPath, ?)` 在 BLOB/TEXT 混型下的实际返回值（详见 T2.3）。

[DONE] T1

---

## T2 ⭐ 反查实现（本 lane 核心）

### T2.1 新增的字段 / 常量（与 `cachedTokens` 同构）

| 新增 | 位置 | 说明 |
|---|---|---|
| `@Volatile private var cachedRowIds: Set<String> = emptySet()` | `MediaTableGuard.kt:106-114` | 与 `cachedTokens`（`:102-104`）同构、同一 `gen` 重建；只在 `invalidate()` 写，before 只读局部快照 |
| `MAX_LOOKUP_TOKENS = 32` | `:87` | 兜底限流：一次反查最多逐个查 32 个 md5；超出打 `id-lookup cap` warning |
| `MAX_ROWS_PER_TOKEN = 16` | `:88` | 单 token 最多取 16 行 id（SQL `limit 16` + 循环上限双保险） |
| `LOOKUP_BUDGET_MS = 20L` | `:89` | 超时保护，与 `docs/_r52_fix_design.md:160`「超过 20ms 要评估竞态」同口径 |

⛔ 反查日志**不节流**：它只在 `invalidate()`（一次撤回触发一次）跑，每条都是验收证据；
⛔ 热路径 `observe()` / `emitThrottled()` 的 60s 节流、`MAX_LOG_KEYS=64`、`obsLogAt` 与 `logAt` 分离
**逐字原样未动**（下一轮真机验收全靠它们）。

### T2.2 `invalidate()` 改动（`:174-192`）

```
gen++
lastIssuedMs = SystemClock.elapsedRealtime()
val tokens = snapshotLiveTokens()
cachedTokens = tokens
cachedRowIds = lookupRowIds(tokens)     // ← 新增：同一份 token 局部快照反查
runCatching { refresh() }
```

- 反查**排在 `refresh()` 之前**：删除真机仅隔 10~25ms（`01:03:59.051 RRV record` → `01:03:59.080 MTG delete`），
  越早发布 `cachedRowIds` 越赶得上。
- 用**同一份局部 `val tokens`** 同时喂两个快照，避免二次读 `@Volatile cachedTokens` 造成两者不同代。
- ⛔ **绝不在 `onBefore`（`[D]#worker`）查库**：反查是同步 `rawQuery`，只放在
  `RevokeRescue.record()` 图片分支的 `[GT]ColdPool` 线程（`RevokeRescue.kt:121-128` 顺序：
  `protectMedia()` → `auditImage()` → `MediaTableGuard.invalidate()`），符合 `AntiRevoke.kt:863` 的约束。
- `stat()`（`:194-204`）、`resetLedger()`（`:206-221`）、`resolvePool()`、`snapshotLiveTokens()` **逐字未动**
  （`resetLedger` 注释仅补了 `cachedRowIds` 这个运行时状态名，语义不变：它仍是判定依据，不被清）。

### T2.3 `lookupRowIds()` 实现（`:396-492`）

**SQL 口径（决定性证据）**：
```sql
/*w*/select id from ImgInfo2 where instr(thumbImgPath, ?) > 0 limit 16
```
- 按 **md5 查 `thumbImgPath`**：真机 `THUMBNAIL_DIRPATH://th_<md5>`，`?` 绑 `snapshotLiveTokens()`
  里 lowercase 的 32 位 md5。
- ⛔ **不靠 `origImgMD5`**（多为 NULL）、⛔ **绝不沿用 `auditImage` 的 `where msglocalid=? or msgSvrId=?`**
  口径（真机 `RRV imgAudit row=0`，甲的头号失效场景，`RevokeRescue.kt:269-273`）。
  ⭐ 本轮补充的实测列形态：**`msglocalid` 恒 NULL、`msgSvrId` 是 19 位长 ID、`msgId` 是 8 位**
  ⇒ 用 `msglocalid=?` / `msgSvrId=?` 关联**必然 `row=0`**，不是「偶尔查不到」而是**口径根本对不上**；
  这正是必须改按 md5 查 `thumbImgPath` 的决定性理由（⛔ 与 `msglocalid=msgId` 同型，绝不沿用）。
- ⛔ **库句柄只用微信主库**：`ImgInfo2` 与 `message` 同库（`AntiRevoke.msgQueryHandle()` 的 `msgDb`）；
  设计文档 `:414` 明写 `op=sql` 走**朋友圈 `snsDb` 会 `no such table: ImgInfo2`**，只能验通道、
  **不能当主库判据** ⇒ ⛔ 绝不用朋友圈库句柄。微信 DB 类是
  `com.tencent.wcdb.database.SQLiteDatabase`（本文件 `WCDB_DB`，`:63`），反射取 `rawQuery` 的写法
  照抄 `AntiRevoke` / `RevokeRescue` 既有代码（见下「句柄 / 反射 / BYPASS」）。
- ⛔ 用 `instr()` 而非 `LIKE`：BLOB 列上 `LIKE` 可能失效，项目已踩坑；`instr` 对 BLOB 做字节/字符查找。
- ⚠️ **`[需真机]` 头号风险**：`thumbImgPath` 若以 BLOB 存放，SQLite `instr(BLOB, TEXT)` 可能返回 NULL
  ⇒ 全 `rows=0`。设计文档 `:418` 明写 BLOB 列一律 `hex()` 不用 `cast as text`（首个 NUL 截断假阴性）。
  **不凭猜测硬写替代 SQL**：由 `rows=0` 的 MISS 日志暴露后，按 **D3** 停手，用
  `op=label sub=sql` + `hex(thumbImgPath)` 复核列形态，再决定改口径（例如 `hex()` 侧比对），
  ⛔ 不退化成乙「窗口内目标表全拦」。
- 其它 `[需真机]`：池内 lowercase md5 与列内大小写是否一致；`instr` 全表扫描（`ImgInfo2` 无该列索引）
  的实际耗时是否 ≤10ms（设计文档 `:160`）；`id` 列是否确为数值主键。

**句柄 / 反射 / BYPASS**：
- `AntiRevoke.msgQueryHandle()`（`AntiRevoke.kt:851-855`）→ `(msgDb, rawQueryMethod)`；
  `null`（库未抓到 / rawQuery 没找到）⇒ 直接 MISS，⛔ 不退 `lastDb`、不重试（`:819-826` 教训）。
- `bindArgs` 按 `m.parameterTypes[1]` 二选一（`AntiRevoke.kt:889-893`、`RevokeRescue.kt:172-176`）：
  `String[]` ⇒ `arrayOf(md5)`，否则 `arrayOf<Any>(md5)`；传错会 `IllegalArgumentException`。
- SQL 前缀 `AntiRevokeHint.BYPASS = "/*w*/"`（`AntiRevokeHint.kt:62`），防自家 `rawQuery` /
  `rawQueryWithFactory` 钩子（`:177-183`）改写；项目所有诊断 SQL 同款（`RevokeRescue.kt:179,269,687`）。
- Cursor：`moveToFirst` → `getLong(0)` → `moveToNext` 循环 → `finally { close() }`（范式见
  `RevokeRescue.kt:177-210,278-284,685-696`）。每个 token 的查询各自 `runCatching` 包住，
  单个失败不影响其它 token，异常只记 `err=<Class>`。
- ⛔ **只读**：零写操作 / 零建表 / 零建索引 / 不改 `param.args` / 不写微信库。

**归一**：`MediaDeleteLogic.normalizeId(raw)?.let { ids.add(it) }` —— 直接复用 lane A 纯函数
（`MediaDeleteLogic.kt:251`，已落地、public），与 `extractRowIds` / `idMatches` **完全同口径**，
⛔ 不自己重写（`docs/_r52_fix_design.md:276` 的静默失效警告）。`getLong(0)` 返回 `Long`，
`normalizeId` 的 `v.toString().trim()` 得到十进制数字串，与 `where=id=?` 的 `Long:875556` 归一一致。

### T2.4 ⭐ 打点日志（验收硬判据）

| 日志 | 触发 |
|---|---|
| `MTG id-lookup md5=<md5> rows=<n> ids=[…] cost=<ms>` | 单 token 命中（`rows>=1`），**设计文档 `:425` 要求 `rows>=1`** |
| `MTG id-lookup MISS md5=<md5> rows=0 cost=<ms>` | 单 token `rows=0`（⛔ 不静默） |
| `MTG id-lookup MISS md5=<md5> rows=0 err=<Class> cost=<ms>` | 单 token 异常 |
| `MTG id-lookup MISS reason=tokens-empty rows=0 cost=0` | 保护池快照为空 |
| `MTG id-lookup MISS reason=handle-null rows=0 cost=<ms>` | `msgQueryHandle()==null` |
| `MTG id-lookup MISS total rows=0 tokens=<n> capped=<b> cost=<ms>` | 整体一行都没拿到 |
| `MTG id-lookup done tokens=<n> ids=<m> capped=<b> cost=<ms>` | 汇总，成功发布 `cachedRowIds` |
| `MTG id-lookup cap ...` / `MTG id-lookup budget ...` | 触发封顶 / 20ms 预算（warning） |

**`rows=0` 或异常时的行为**：`out.isEmpty()` ⇒ `cachedRowIds = emptySet()` **保守放行**，
⛔ **必须留一行 MISS 日志**（上表 6 个分支每个都打），⛔ 不静默、⛔ 不退化成乙、
⛔ 不凑假 id、⛔ 不改开关。设计文档 `:162-166`、T5 `:425/:446` 规定 `rows=0` **当场停手按 D3 回头**，
不继续点图下结论。放行后既有 md5 token 通道逐字不动 ⇒ 甲是增量一条分支，不替换原 `BLOCKABLE` 路径。

### T2.5 ⚠️ 竞态（ColdPool 反查 vs `[D]#worker` 删除，真机间隔仅 10~25ms）

**`cost` 打点如何证明赶得上**：
- `t0` 在 `lookupRowIds()` 入口（`:417`），`total` 在汇总（`:482`），逐条 `cost` 是单 token 一次
  `rawQuery` + 遍历耗时。
- 真机读 `dev.py q 'MTG|RRV'`，把 `MTG id-lookup done ... cost=<ms>` 的时间戳与
  `RRV record ok=true`、`MTG delete table=ImgInfo2 where=id=?` 的时间戳对齐：
  **若 `id-lookup done` 早于 `MTG delete` 且 `total < 间隔`** ⇒ 快照在删除前发布，赶得上；
  若 `total > 间隔` 或 `id-lookup` 时间戳晚于 `MTG delete` ⇒ **反查来不及**，第一条已被删且不可回滚，
  按 **D3** 停手（设计文档 `:181` 甲-4）。
- `LOOKUP_BUDGET_MS=20L` 是硬保护：超 20ms 主动 `break` 并打 `id-lookup budget` warning，
  避免拖垮 ColdPool 线程；设计文档 `:160` 目标 ≤10ms（`auditImage` 真机约 2ms 量级，但那是按索引查，
  ⛔ 本轮 `instr(thumbImgPath)` 无索引，`[需真机]` 实测）。

**连撤两张时快照整体重建会不会漏掉第一条**：
- `invalidate()` 每次都是 `cachedTokens = tokens` / `cachedRowIds = lookupRowIds(tokens)`
  **整体重建**（不是增量合并），用同一份局部 `tokens` 快照。
- 保护池 `protectedMedia`（`VoiceFileGuard.kt:109`）是**累积**的：`protectMedia()` 只 put、不因
  `invalidate()` 清空，10min TTL 内两张的 md5 都在池里 ⇒ 第二次重建的 `cachedTokens` 含两张 md5，
  `lookupRowIds` 用 `tokens.sorted()` 逐个查 ⇒ **两张的 id 都进 `cachedRowIds`**（前提：第一张行还在）。
- ⚠️ **会漏第一条的两种情形**：
  1. 第一张的 `ImgInfo2` delete 落在其反查完成之前（10~25ms 竞态）⇒ 行已被删、**不可回滚** ⇒
     第二次反查按 md5 已查不到第一张 id ⇒ 第一条漏掉。这正是 `cost` 打点要证伪/证实的点。
  2. `cachedTokens`（`:189`）与 `cachedRowIds`（`:190`）是**两次独立 `@Volatile` 赋值**，
     中间有一个微窗口（一次 `rawQuery` 的时间）：`onBefore` 若恰好在中间读到 `tokens` 新、
     `rowIds` 旧（空），则**这一次** delete 的 id 通道暂时失效（走原 token 通道）；下一次 delete
     会读到齐备的快照。⛔ 不改成单快照对象（leader 要求与 `cachedTokens:102` 同构，且 `stat()` /
     `resetLedger()` 语义要保住）。
- ⚠️ `MAX_LOOKUP_TOKENS=32` + `tokens.sorted()` 字典序封顶：池内 token >32 时只查前 32，
  可能漏 `[需真机]`；由 `id-lookup cap` warning 暴露。真机常见 `VFG ... pool=1` ⇒ 不触发。

[DONE] T2

## T3 `onBefore` 接线（最小改动）

### T3.1 改动前后（`:281-300`）

**改前**（R52 探针版）：
```kotlin
val tokens = cachedTokens
if (tokens.isEmpty()) { skipped++; observe(execSql,p,"tokens-empty"); return }
if (!MediaDeleteLogic.shouldBlock(p.table, p.where, p.args, tokens, nowMs, lastIssuedMs, WINDOW_MS)) {
    skipped++; observe(execSql,p,"shouldBlock-false"); return
}
```

**改后**：
```kotlin
val rowIds = cachedRowIds          // 进入判据前一次性局部快照，避免并发变化
val tokens = cachedTokens
if (tokens.isEmpty() && rowIds.isEmpty()) {          // ← 双空集闸门
    skipped.incrementAndGet()
    observe(execSql, p, "tokens-empty")              // reason 字符串保留（保住 dev.py q 过滤 key）
    return
}
val nowMs = SystemClock.elapsedRealtime()
if (!MediaDeleteLogic.shouldBlock(
        p.table, p.where, p.args, tokens, nowMs, lastIssuedMs, WINDOW_MS,
        protectedRowIds = rowIds      // ← 本轮唯一新增
    )
) {
    skipped.incrementAndGet()
    observe(execSql, p, "shouldBlock-false")
    return
}
```

### T3.2 逐条纪律核对

- ⭐ **进入 ④ 之前读一次 `val rowIds = cachedRowIds` 局部快照**：只在这一处读，后续判据与
  `shouldBlock` 调用都用这个局部值，避免 `@Volatile` 字段在判据中途被 `invalidate()` 改写。
- ⭐ **双空集闸门对齐**：`tokens.isEmpty() && rowIds.isEmpty()` 与 lane A
  `MediaDeleteLogic.shouldBlock:360` 的 `protectedTokens.isEmpty() && protectedRowIds.isEmpty()`
  严格一致。四态覆盖：
  | tokens | rowIds | 行为 |
  |---|---|---|
  | 空 | 空 | 这里直接 return（skipped++ + `tokens-empty` 观测），与 lane A 内部一致 |
  | 空 | **非空** | ⛔ **不 return**，进 `shouldBlock` ⇒ id 通道生效（反查命中而池已过期的关键路径） |
  | 非空 | 空 | 不 return，原 md5 token 通道逐字不动（反查 MISS 时的保守路径） |
  | 非空 | 非空 | 两条通道都在 |
  ⛔ **没有自己补一套「tokens 空就 return」**把 id 通道挡掉（设计文档 C7 / M2 抓的正是这个漏改）。
- ⭐ **唯一新增**就是 `shouldBlock(...)` 末尾的命名参数 `protectedRowIds = rowIds`；
  ⛔ 未改 `MediaDeleteLogic` 任何签名、未新增 SQL 到纯函数类、未改纯函数类任何一行。
- **命中后 `param.result` 赋值点仍只有原有 2 处且原样**：
  `:306` `param.result = null`（execSQL）、`:309` `param.result = FAKE_ROWS`（delete）。
  ⛔ ⑤ 命中块（`:302-316`：`blocked++` + `throttledLog("block", …)` 的 `table/where/reason/gen` 5 字段）
  逐字未动。
- **放行分支仍是不置 result 的裸 return**（`:260/264/270/276/289/299` 一带）：
  保护同方法上 `VoiceGuard` / `SnsGuard` 的后续 before callback —— 置 `param.result` 会让
  Xposed `returnEarly=true`，同方法后续 callback 的 before 虽仍跑但方法体已跳过；放行时保持裸 return
  才不干扰现网 R42 / 语音拦截链。
- **6 条静默 return 的限流全量记账逐字保留**：`observe()` / `emitThrottled()` / `obsLogAt` 与
  `logAt` 分离 / 60s 节流 / `MAX_LOG_KEYS=64` / `resetLedger()` / `op=mtg sub=stat|reset` 账本导出
  全部原样；⛔ 未删、未削弱。
- `stat()` 输出格式**未改**（`enabled/gen/seen/matched/blocked/skipped/verdict/pool/tokens/events/switch`
  逐字保留）⇒ `op=mtg stat` 账本语义零影响；`cachedRowIds` 大小改由 `MTG id-lookup done ids=<m>`
  日志观测，不混进账本。

[DONE] T3

## T4 三档边界表（进入 / 返回 / 复位逐条）

> 语境：描述「甲接线后」的预期状态，**全部 `[需真机]` 才能定**；主判据恒为
> 「撤后隔 3~5 分钟点开能否看到**完整大图**」（⛔ 不拿 `seen/matched/blocked/rows/tokens/gen` 当结论）。
> 好友只用 **verity** / **测试群聊** / **文件传输助手**。

### ① 进入档（状态如何被建立）

| # | 场景 | 建立的状态 |
|---|---|---|
| 1 | **冷启首屏（进程启动 → `install()`）**（`:116-146`） | `findClass(WCDB_DB)` → `refresh()` 读 `Keys.ANTIREVOKE_MEDIA_DELETE`（`Keys.kt:141`，默认 `false`）→ `resolvePool()` 反射 `VoiceFileGuard.protectedMedia` → `hookAllMethods(cls,"delete"/"execSQL")`；`enabled` 由开关定、`gen=0`、`cachedTokens`/`cachedRowIds=emptySet()`、账本 4 计数全 0；打 `MTG installed hooks=… enabled=… pool=… switch=…` |
| 2 | **首次撤回 `record()→invalidate()`** | `RevokeRescue.kt:121-128` 顺序：`VoiceFileGuard.protectMedia(orig.imgPath)`（池登记 md5、ttl 600000ms）→ `auditImage()` → `MediaTableGuard.invalidate()`；后者 `gen++`、`lastIssuedMs=now`、`snapshotLiveTokens()` 取存活 md5、`lookupRowIds()` 反查 `ImgInfo2.id` 并打 `MTG id-lookup …`、发布 `cachedTokens`/`cachedRowIds`、`refresh()` 热更新开关 |
| 3 | **`kvset` 开关** | `antirevoke.media_delete=true` 后 ⛔ **不会立刻生效**：`Kv.getBool` 只在 `install()` / `invalidate()` 调（`:148-151` 注释），须等下一次 `record()→invalidate()` 或冷启才把 `enabled=true` 刷进内存（`docs/_r52_fix_design.md:436-437` 的 kvset→kvget→冷启三段） |
| 4 | **保护池 token 与 rowIds 登记** | 两条**独立**通道：token（26/32 位 hex）由 `VFG protectMedia` 登记进 `protectedMedia`（`VoiceFileGuard.kt:151`）；rowIds（十进制数字串）由本轮 `lookupRowIds()` 反查登记进 `cachedRowIds`。⛔ 不把 id 塞进 `extractTokens`（会破坏两套 token 长度分派） |

> ⛔ **反查只在 `invalidate()` 做，⛔ 绝不挪到 `onBefore` 热路径**：删除发生在 `[D]#worker`
> （`01:03:59.080 MTG delete`），反查在 `RevokeRescue.record()` 的 `[GT]ColdPool` 线程，
> 两侧有 **10~25ms 竞态**（T2.5 详述，靠 `MTG id-lookup … cost=<ms>` 打点证明赶得上）。
> `onBefore` 一行查库都没有，只在 ④ 开头读一次 `cachedRowIds` 的局部快照。

### ② 返回档（用户回来时状态还在不在）

| # | 场景 | 预期（用户可见事实） |
|---|---|---|
| 1 | **撤后几分钟点开（3~5min）** | ⭐ **主判据**：仍在 10min 窗口内、`cachedRowIds` 在（进程未死）、`ImgInfo2` 行被拦住保留、磁盘 `th_…_tmp.jpg` 被 `VFG` 保住 ⇒ 预期能看到**完整大图**；`[需真机]`（甲-7：行保留后 `h/i/j` 是否恢复，本设备从未观测） |
| 2 | **切页回来** | 进程未死，快照与 hook 都还在；`ImgInfo2` 行仍在 ⇒ 仍可见。`[需真机]` |
| 3 | **下拉刷新** | 同上；`message` 分页重新查不影响 `ImgInfo2` 索引行（已被拦住保留）⇒ 仍可见。`[需真机]` |
| 4 | **撤后满 5 分钟再点开一次** | 设计文档 `:433` 补测项：只要 <10min 窗口内，预期仍可见。`[需真机]` |
| 5 | **`cachedRowIds` 为空**（反查 MISS / 句柄 null / 异常 / 冷启后池空） | ⭐ **旧 md5 通道照常**：双空集闸门因 `tokens` 非空不触发，`shouldBlock(protectedRowIds=emptySet())` 走 lane A 原有 `classifyDelete` + `extractTokens` 判据 ⇒ 甲是**增量**一条分支，⛔ 不替换 / 不削弱既有 `BLOCKABLE` 路径 |
| 6 | **判据真命中（拦下）** | `blocked` **+1**、`skipped` **不增**（⑤ 命中块才 `blocked++`，放行分支才 `skipped++`）；`param.result` 仍 `1`（delete，`FAKE_ROWS`）/ `null`（execSQL）**保持原样**；⛔ 账本语义、callback 顺序零改动 |

#### ②-a TTL **时间维度**（单列，⛔ 不与冷启合并）

- **保护池 10min**：`VoiceFileGuard.PROTECT_MEDIA_MS = 600_000L`（`VoiceFileGuard.kt:112`）与
  `MediaTableGuard.WINDOW_MS = 600_000L`（`:70`）等长。超过 10min ⇒ `snapshotLiveTokens()` 不收过期
  token、`isRevocationWindow` 关闭、`shouldBlock` 恒 false ⇒ **放行**。
  ⚠️ `cachedTokens` / `cachedRowIds` 是**已发布的快照**，⛔ **不会因 TTL 到点自动清空**（只在下次
  `invalidate()` 重建或进程死亡时变）；但窗口闸门已关 ⇒ 旧快照不构成过度拦截。`[需真机]`
- **`imgAnchor` 30min**：`AntiRevoke.IMG_ANCHOR_TTL_MS = 30 * 60_000L`（`AntiRevoke.kt:494`），
  `:470` `removeIf { now - at > IMG_ANCHOR_TTL_MS }` 清理。⛔ 本轮反查**不读** imgAnchor（只读
  `thumbImgPath`），故它只作为返回档的**另一条**时间边界单列，不影响 `cachedRowIds`。`[需真机]`

#### ②-b 冷启 **进程维度**（单列，⛔ 与 ②-a 是两套独立机制，不许合并）

- 微信进程死亡 → `MediaTableGuard` **object 整体重建**：`enabled` 需重读、`gen=0`、
  `lastIssuedMs=0`、账本 `seen/matched/blocked/skipped` + `events/logAt/obsLogAt` **整体清零**、
  **`cachedTokens` 与 `cachedRowIds` 整体清零**、`mediaPoolRef` 需重新反射、delete/execSQL hook
  需重新 `install()`。
- ⚠️ **与 TTL 的本质区别**：即使 10min 墙钟时间**还没走完**，只要冷启，一律回到「未登记」态 ⇒
  撤后必须**同一次微信进程生命周期内**点开才有保护；冷启后重试删除落在空池上，行被真删且不可回滚。
  `[需真机]`

### ③ 复位档（10 条逐条：清除条件 + 残留，⛔ 不写「同返回」）

> ⛔ **R42-N1N6 教训**（`docs/_r42_n1n6_impl.md:419,429`、`_r42_n1n6_verify.md:60-83,125`、
> `_r49_revokeimg_forensics.md:171`）：**滚回顶部 / `pos <= prev` 不是复位触发条件**。
> 历史坑是复位判据写成 `pos <= prev`，把「封面重绑（pos=0）」误判成复位，导致灰标
> 「先消失再补回」的可见闪烁；修法是 **`pos == prev` 才前移**，`pos < prev`（含封面 pos=0）
> 一律保持 `prev` 不变 —— `pos=0` 是「从来就是头部」的位置，**绝不触发复位**。
> ⇒ 本轮：**切页回来 / 下拉刷新 / 滚回顶部都不改 `MediaTableGuard` 任何状态**
> （`cachedTokens` / `cachedRowIds` / `gen` / 账本 / hook / 开关全保持）。
> ⭐ **本轮只有三种情况改状态**：
> ① **窗口过期**（撤后 >10min，`isRevocationWindow` 关 ⇒ 判据恒放行）；
> ② **`gen` 自增重新反查**（下一次 `record()→invalidate()` 整体重建 `cachedTokens`/`cachedRowIds`）；
> ③ **`op=mtg sub=reset`**（`resetLedger()` **只清账本** `seen/matched/blocked/skipped/events/logAt/obsLogAt`，
> ⛔ **不动** `enabled` / `cachedTokens` / `cachedRowIds` / `gen` / `lastIssuedMs` / `mediaPoolRef`）。

| # | 复位项 | 清除条件 | 残留 |
|---|---|---|---|
| 1 | **关 `antirevoke.media_delete`** | `kvset …=false` 后等下一次 `record()→invalidate()` 的 `refresh()` 或冷启 `install()` 把 `enabled=false` 刷进内存 | `cachedTokens`/`cachedRowIds`/`gen`/`lastIssuedMs` 仍在、hook 仍挂（只走 `!enabled` 的 `disabled` 观测分支）；反向对照正是靠它（`docs/_r52_fix_design.md:436`） |
| 2 | **关 `antirevoke.msg`**（`Keys.kt:140`） | 防撤回总开关关 ⇒ `RevokeRescue.record()` 不再触发 ⇒ 不再 `protectMedia()` / 不再 `invalidate()` | 已有 `cachedTokens`/`cachedRowIds` 快照、已挂 hook、已登记 `protectedMedia` 条目都还在（等各自 TTL / 冷启）；窗口内若仍来一条目标表 delete，钩子仍按快照判据处理。`[推断]` |
| 3 | **保护池 10min TTL** | `VoiceFileGuard.protectMedia()` 的 `removeIf { v < now }`（`VoiceFileGuard.kt:152-153`）；`snapshotLiveTokens()` 只收 `v>=now` | ⛔ `cachedTokens`/`cachedRowIds` **不随池条目过期自动清**（只在下次 invalidate 重建）；但 `WINDOW_MS` 同长 10min，窗口关 ⇒ 恒放行 |
| 4 | **`imgAnchor` 30min TTL** | `AntiRevoke.kt:470` `removeIf { now - at > IMG_ANCHOR_TTL_MS }` | 锚点 map 清理不影响 `cachedRowIds`；本轮反查不读 imgAnchor，仅审计关联式用。`[需真机]` |
| 5 | **账本冷启清零** | 进程死亡 → object 重建 | ⛔ **无残留**（账本纯进程内内存）；但 `op=mtg sub=reset`（`resetLedger():213-221`）**只归零账本**，`cachedTokens`/`cachedRowIds`/`enabled`/`gen`/`lastIssuedMs` 一律不动，两者语义不同、⛔ 不合并 |
| 6 | **hook 随进程死才卸** | 微信进程死亡（LSPosed 钩子随进程卸载） | ⛔ 无「运行时卸钩」接口；`enabled=false` 只是逻辑旁路、钩子恒在，进程活着期间 `seen` 仍计数 |
| 7 | **重试落在窗口外或冷启池空** | `isRevocationWindow(...)` 为 false（撤后 >10min），或冷启后 `cachedTokens`/`cachedRowIds` 空、`mediaPoolRef` 未反射到 | 删除**不再被拦**（保守放行）⇒ `ImgInfo2` 行被真删 ⇒ 大图不可见且**不可回滚**；`docs/_r51_media_table_guard_impl.md:243` 记过 5 秒 360 次重试风暴 |
| 8 | **清缓存**（微信「设置→通用→存储空间→清理缓存」） | 微信主动清 `image2` 目录 / 发 `DELETE … IN (SELECT …)` 批量形态 | ⛔ 本轮判据对批量形态一律放行（`extractRowIds` 不解析 `IN`/`LIKE`）⇒ **不被误拦**（甲优于乙：乙会破坏微信 GC）；但被清掉的磁盘文件与索引行**不可回滚** |
| 9 | **LogX 满 5000 行整文件清空重来** | 落盘行数 ≥ `MAX_LINES=5000`（`LogX.kt:22`）⇒ `:83-87` `writeText("$ts ---- log rotated: 已达 5000 行，清空重来（最新日志始终保留）----\n")` + `lines=0` | ⛔ **留 `log rotated` 标记**、⛔ **非逐行 FIFO**：整文件一次性清空，之前所有 `MTG id-lookup` / `MTG blocked` / `HDIMG trace` 证据**整批丢失**；设计文档 `:411` 要求验收前先 `op=logclear` 规避。轮转后新日志继续写、1s dedup（`LogX.kt:21`）仍在 |
| 10 | **已被删的 DB 行与文件不可回滚** | ⛔ **无清除条件**（任何开关 / 复位 / 冷启都救不回） | 一旦 `ImgInfo2` 索引行被 `delete()` 真删、或 `image2` 文件被 `File.delete` 真删，本轮「只拦删除、不写库、不重建消息」路线**不提供回滚**；⛔ 下一轮也不许为此写回微信库（红线）。只能靠「下次撤回时提前拦住」保护 ⇒ ⭐ **修复装包后必须请 verity 在「测试群聊」/「文件传输助手」发一张新图并撤回做验收**（⛔ 不能复用被删的老图；反向对照也必须用新图，`docs/_r52_fix_design.md:436`） |

[DONE] T4

## T5 回滚 + 装包前复核要点

### T5.1 文件状态（已实测，非推断）

```
$ git status --short -- wxgroup/.../MediaTableGuard.kt docs/_r52_fix_b_impl.md
?? docs/_r52_fix_b_impl.md
?? wxgroup/app/src/main/java/com/lx/wxgroup/feature/MediaTableGuard.kt
$ git ls-files --error-unmatch wxgroup/.../MediaTableGuard.kt
error: pathspec ... did not match any file(s) known to git
```

⇒ `MediaTableGuard.kt` 是 **untracked**（R51 新建、**无 HEAD 版**），本轮报告也是 untracked。
工作区另有 R48~R52 大量在途改动。

### T5.2 ⛔ 回滚纪律

- ⛔ **不能 `git checkout -- <file>`**（无 HEAD 版，checkout 只会报错 / 删不掉）、
  ⛔ **不能 `rm` 整文件**（会把 R51 探针版整份丢掉）。
- ⛔ **严禁** `git checkout .` / `git reset --hard` / `git clean -fd`（会连带清掉 R48~R52 在途改动）。
- ✅ **唯一回滚方式：精确移除本轮新增的 6 块**（用 `Edit` 逐块还原，不动其余任何一行）：

| # | 新增块 | 位置 | 还原方式 |
|---|---|---|---|
| 1 | 3 个常量 + KDoc | `:80-89` | 整块删除 |
| 2 | `cachedRowIds` 字段 + KDoc | `:106-114` | 整块删除 |
| 3 | `invalidate()` KDoc 新增段 + `val tokens` / `cachedTokens=tokens` / `cachedRowIds=lookupRowIds(tokens)` | `:179-191` | 还原成原 4 行（`cachedTokens = snapshotLiveTokens()` + `runCatching { refresh() }`） |
| 4 | `resetLedger()` KDoc 里补的 `cachedRowIds` 字样 | `:208` | 删掉该词（纯注释，语义无影响） |
| 5 | `onBefore` ④ 的注释 / `val rowIds` / 双空集 / `protectedRowIds = rowIds` | `:281-295` | 还原成原 `if (tokens.isEmpty())` + 原 7 参 `shouldBlock(...)` 调用 |
| 6 | `// ---- id 反查` 分隔注释 + `lookupRowIds()` 整个函数 | `:395-492` | 整块删除 |

回滚后 `MediaTableGuard.kt` 应逐字回到 R52 探针版（`install/refresh/invalidate/stat/resetLedger/onBefore`、
账本、观测限流、`param.result` 2 处全部原样），⛔ 不残留任何反查痕迹。

### T5.3 装包前复核要点（逐条回答）

**① 反查是否可能在主线程阻塞？**
- ⛔ **不在主线程，也不在 `[D]#worker` DB 热路径**。反查只在 `invalidate()`，由
  `RevokeRescue.record()` 图片分支调用（真机 `01:03:59.051 RRV record` 打在 `[GT]ColdPool#9`，
  撤回回调线程池），`onBefore` 一行查库都没有。
- 但同步 `rawQuery` 仍占 ColdPool 线程 ⇒ 三道兜底：`LOOKUP_BUDGET_MS=20L`（超时 `break` +
  `id-lookup budget` warning）、`MAX_LOOKUP_TOKENS=32`、`MAX_ROWS_PER_TOKEN=16`（SQL `limit 16`）。
  句柄 `null` ⇒ 直接 MISS 返回，零查库、零阻塞。
- ⚠️ `[需真机]`：`instr(thumbImgPath,?)` **无索引、可能全表扫**，`ImgInfo2` 行数大时单次
  `rawQuery` 可能 >10ms；用 `MTG id-lookup … cost=<ms>` 与 `MTG delete` 时间戳对比实测。
  若 `cost` 逼近/超 20ms 或与删除时间戳倒挂 ⇒ **按 D3 停手**，⛔ 本轮不改成异步（会引入新竞态、
  且超出本 lane 范围）。

**② 是否引入新写操作？（应为零）**
- 新增代码只做：`rawQuery`（**SELECT**）、`LogX.i/w`、`MediaDeleteLogic.normalizeId`（纯函数）、
  `snapshotLiveTokens()`、`HashSet`/`ArrayList`、`@Volatile` 赋值。
- ⛔ 零 `insert` / `update` / `delete` / `execSQL` 写；不改 `param.args`；不新增 `param.result` 赋值点；
  不写微信库、不写模块自有库（`refresh()` 里原有的 `Kv.getBool` 是**读**）、不建表 / 不建索引。
- ✅ **结论：新写操作 = 0**。

**③ 是否影响 `op=mtg stat` 账本语义？**
- `stat()`（`:194-204`）**逐字未动**：仍输出 `enabled/gen/seen/matched/blocked/skipped/verdict/pool/tokens/events/switch`；
  `verdict` 仍由 `MediaDeleteLogic.verdict(seen, matched)` 计算，⛔ **不看 `cachedRowIds`**。
- `resetLedger()`（`:213-221`）逐字未动（仅 KDoc 补了个字段名）；`skipped` / `blocked` 增减位置、
  `observe()` / `emitThrottled()` / `obsLogAt` 60s 节流 / `MAX_LOG_KEYS=64` 全部原样。
- 唯一行为变化：`shouldBlock` 多一条 id 通道 ⇒ 命中时 `blocked++`、放行时 `skipped++`，
  **账本仍是「真拦次数 / 放行次数」，语义不变**。
- ✅ **结论：零影响**，`dev.py q 'MTG stat'` 过滤口径不变；`cachedRowIds` 大小改由
  `MTG id-lookup done ids=<m>` 观测，不混进账本。

**④ `param.result` 赋值点计数**
- 全文件仍 **2 处**：`:306` `param.result = null`（execSQL）、`:309` `param.result = FAKE_ROWS`（delete）。
  `:54` / `:304` 只是注释里的提及。⛔ 反查路径零 `param.result`。

**⑤ 与 `VoiceGuard` / `SnsGuard` callback 顺序**
- `install()`（`:116-146`）用 `hookAllMethods(cls,"delete"/"execSQL", …)` 注册自己的 before；
  `VoiceGuard.deleteGuard` 只认 `voiceinfo`、`SnsGuard` 用朋友圈独立库（`SnsInfo/SnsComment`），
  与 `MediaDeleteLogic.TARGET_TABLES = {imginfo2, videoinfo2, wxfileindex2}`（不含 voiceinfo）
  **靠表名白名单天然互不重叠**（文件头 `:25-29`）。
- ⛔ **放行分支仍是不置 `param.result` 的裸 return** ⇒ 不触发 `returnEarly`，同方法上
  `VoiceGuard` / `SnsGuard` 的后续 before callback 照常执行；`:317-319` 的 catch 也保证异常不外泄。
- ⛔ 命中分支仍只在自己这 2 处置 result，⛔ 未新增 hook、未改注册顺序 ⇒ **callback 链顺序零变化**。

### T5.4 静态复核清单（⛔ 本轮不跑 gradle / 不装包 / 不碰 adb）

- ✅ Kotlin 括号闭合：已 Read 文件末尾 `:544` 的 `}`（object 闭合）。
- ✅ **零新增 import**：`AntiRevoke` / `AntiRevokeHint` 与 `MediaTableGuard` 同包 `com.lx.wxgroup.feature`；
  `XposedHelpers` / `SystemClock` / `LogX` / `MediaDeleteLogic` 原已 import（`:3-13`）。
- ✅ **编译耦合点已核对就绪**（lane A 已落地）：
  `MediaDeleteLogic.normalizeId(v: Any?): String?`（`:251`，public、`v?.toString()?.trim()`）；
  `shouldBlock(..., protectedRowIds: Set<String> = emptySet())`（`:355`，第 8 参，双空集闸门 `:360`）。
  ⛔ 本轮未改 `MediaDeleteLogic.kt` / 单测任何一行；装包前由集成方统一编译。
- ✅ 观测能力逐条保留：`op=mtg sub=stat|reset`、`observe()` 全量记账、`obsLogAt`/`logAt` 分离、
  60s 节流、`MAX_LOG_KEYS=64`、`blocked` 行 5 字段、`resetLedger()`、`param.result` 2 处。
- ⚠️ **⛔ 别拿 `bash tools/_junit.sh` 的 `OK (46 tests)` 当验收判据**（lane A 提醒）：gradle 预编译
  class 还是 11-10 23:28 的旧版（主会话编译闸门没跑），新增 37 例不在里面 ⇒ 跑出来是 **46**（旧集），
  主会话编译后应是 **83**；lane A 的直编链 `_cache/r52_jvm.sh` 已复验 **83 tests 全绿**。
  ⛔ 本 lane 不跑 gradle / 不跑测试（红线），此条只作装包前复核提醒；⛔ 不拿单测数当「修复成立」
  的结论（唯一主判据仍是撤后能否看到完整大图）。
- ⚠️ **⛔ 判断源码有没有被改要看内容 hash、不看 mtime**（lane A 提醒，与上一条同类陷阱）：
  变异验证 `restore` 会重写文件 ⇒ mtime 从 01:35 变 01:47 但**内容 hash 不变**（lane A 实测
  `b1893060affd3b34`）；反向也可用 `touch` 伪造 mtime。⛔ 我上一轮给 team-lead 的「mtime 01:33
  未动」只作辅助，**一律以内容 hash 为准**。本轮实测
  `MediaTableGuard.kt` `sha256 = a1c4dc3e5205b0440198706a552fdf79ca5593e4905024a16a67f5663e043083`
  （29621B、16 处新符号、`param.result` 赋值仅 `:306`/`:309` 两处）⇒ 与上一轮写盘内容一致、未被重写。
- ⛔ **[需真机] 未验项**（不预设成立）：`instr(thumbImgPath, ?)` 在 BLOB 列上的实际返回、
  md5 大小写一致、`cost` 能否赶在 delete 前、拦住后 `h/i/j` 是否恢复（甲-7）、
  **撤后能否看到完整大图**（唯一主判据）。`rows=0` ⇒ 当场停手按 D3 回头，⛔ 不硬上乙全拦。

[DONE] T5

---

## 补充（本轮 team-lead 续派复核，增量补齐）

> 上一轮报告与源码**已在磁盘**。⛔ 判断源码是否被改**看内容 hash、不看 mtime**
> （lane A：变异验证 `restore` 会重写文件、mtime 变但 hash 不变；也可 `touch` 伪造 mtime）：
> 本轮实测 `MediaTableGuard.kt` `sha256=a1c4dc3e5205b0440198706a552fdf79ca5593e4905024a16a67f5663e043083`
> （29621B、16 处新符号、`param.result` 赋值仅 `:306`/`:309`）⇒ 与上一轮写盘内容一致、未被重写。
> 本轮不重做源码，只补齐续派点名的报告红线。

| # | 补齐项 | 落在 |
|---|---|---|
| 1 | 实测列形态 `msglocalid` 恒 NULL / `msgSvrId` 19 位 / `msgId` 8 位 ⇒ `auditImage` 口径**必然 `row=0`** | T2.3 |
| 2 | ⛔ 句柄只用微信主库 `msgDb`；`op=sql` 走朋友圈 `snsDb` 会 `no such table: ImgInfo2`，⛔ 不用 | T2.3 |
| 3 | 进入档「冷启首屏」措辞 + ⛔ 反查只在 `invalidate()`、`[D]#worker` 与 `ColdPool` 10~25ms 竞态 | T4 ① |
| 4 | 返回档补 `cachedRowIds` 为空时**旧 md5 通道照常**、拦下后 `blocked`+1 / `skipped` 不增 / `param.result` 原样 | T4 ② #5/#6 |
| 5 | ⛔ **R42-N1N6**：滚回顶部 / `pos <= prev` **不是复位触发条件**；只有「窗口过期 / `gen` 自增重新反查 / `op=mtg sub=reset`（只清账本、⛔ 不动 `enabled`/`cachedTokens`/`cachedRowIds`/`gen`/`lastIssuedMs`/`mediaPoolRef`）」三种改状态 | T4 ③ 抬头 |
| 6 | ⛔ 已被删行不可回滚 ⇒ 修复后**必须请 verity 发新图验收**（⛔ 正反对照都不许复用老图） | T4 ③ #10 |
| 7 | ⭐ design 文档行号**去脆化**：所有 `_r52_fix_design.md:NNN` 行号均为 **01:26 版快照**，design lane 后续追加（01:45 版）会继续漂移 ⇒ 定位一律以**章节标题 + 条目名**为准（`## T3 判据纯函数化 + 单测契约` / `### 新增 / 修改的函数签名契约` / 7 条签名表），行号只作快照。lane A 01:45 版实测 `## T3` :258、`### 新增/修改…` :262、7 条签名 :266-272、`## T4` :362（我 01:26 版写的「小标题 :260」是旧版位置，差 2 行、不影响定位）。全篇 14 处引用已同步到 01:26 版；T1.1 加注记说明其行号为**改前**现状、源码行号未误伤 | 全篇 + T1.1 |
| 8 | ⚠️ ⛔ `bash tools/_junit.sh` 输出 `OK (46 tests)` 是**旧预编译 class**（新增 37 例不在内），别当全过；主会话编译后应是 83，lane A 直编链已复验 83 全绿。⛔ 本 lane 不跑 gradle/测试 | T5.4 |
| 9 | ⚠️ ⛔ **判断源码是否被改看内容 hash 不看 mtime**（lane A：变异验证 restore 会重写文件、mtime 变但 hash 不变；也可 touch 伪造）。本轮实测 `MediaTableGuard.kt` `sha256=a1c4dc3e…e043083`（29621B、16 处新符号、`param.result` 仅 :306/:309）⇒ 与上一轮写盘一致 | T5.4 |
| 10 | ⭐ lane A 逐字核实两次订正后 **7 条签名内容完全没变**（只补说明 / M2 拆 M2a·M2b / M5 换 RED 用例）⇒ 本 lane `protectedRowIds=rowIds` 命名参数、复用 `normalizeId`、双空集闸门对齐**三点与最新文档仍严格一致、无需任何调整**；`idMatches(候选, ∅) 恒 false ⇒ 放行` 与 `cachedRowIds=emptySet()` 保守放行闭环一致 | T3 接线 |

源码侧逐条复核（⛔ 本轮未改一行）：`cachedRowIds` :106-114、`invalidate()` :185-192、
`lookupRowIds()` :396-492、onBefore ④ :281-300、`param.result` 仍 :306/:309 两处、
`stat()` / `resetLedger()` / 限流观测逐字未动、零新写操作。

[DONE] 补充

---

[DONE] ALL
