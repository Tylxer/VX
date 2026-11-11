# R52 性能缺陷实现报告（r52-perf-fix）

> 根因：`ImgInfo2` 实测 842,935 行，旧 R52 甲 `instr(thumbImgPath, ?)` 无法走索引 ⇒ 全表扫 84 万行 TEXT ⇒ 单条 `rawQuery` 原子阻塞 97s。本轮把反查改成「svr 索引命中为主 + 主键逆向扫描兜底」，彻底移除全表扫。

---

## 0. 改动摘要（带行号）

| 文件 | 位置 | 改动 |
|------|------|------|
| `MediaTableGuard.kt` | `:193` | `invalidate()` → `invalidate(msgId: Long = 0L)`，体内 `lookupRowIds(tokens, msgId)` |
| `MediaTableGuard.kt` | `:80-93` | 常量区：删 `MAX_LOOKUP_TOKENS`，新增 `MAX_RECENT_SCAN=500`，保留 `MAX_ROWS_PER_TOKEN=16` / `LOOKUP_BUDGET_MS=20L`，KDoc 重写 |
| `MediaTableGuard.kt` | `:428-542` | `lookupRowIds(tokens)` → `lookupRowIds(tokens, msgId)`，重构为 via=svr 主路径 + via=recent 兜底，删除 `instr` 全表扫 |
| `MediaTableGuard.kt` | `:545-549` | 新增 `thumbMatchesToken()` 应用层 token 校验助手 |
| `RevokeRescue.kt` | `:128` | `MediaTableGuard.invalidate()` → `MediaTableGuard.invalidate(msgId)` |

验收四闸门（实测，见 T4）：
1. `assembleDebug + compileDebugUnitTestKotlin` ⇒ **BUILD SUCCESSFUL**
2. `tools/_junit.sh` ⇒ **OK (901 tests)**
3. `grep -c "instr(" MediaTableGuard.kt` ⇒ **0**
4. `grep -c "param.result" MediaTableGuard.kt` ⇒ **4**（改前即 4，一字未动）

---

[DONE] T1

## T1 改前现状（行号 + 原文片段）

### `invalidate()` — MediaTableGuard.kt 原 `:185-192`

```kotlin
fun invalidate() {
    gen++
    lastIssuedMs = SystemClock.elapsedRealtime()
    val tokens = snapshotLiveTokens()
    cachedTokens = tokens
    cachedRowIds = lookupRowIds(tokens)
    runCatching { refresh() }
}
```

### `lookupRowIds()` — MediaTableGuard.kt 原 `:416-492`（关键片段）

```kotlin
private fun lookupRowIds(tokens: Set<String>): Set<String> {
    val t0 = SystemClock.elapsedRealtime()
    if (tokens.isEmpty()) { ...; return emptySet() }
    val handle = AntiRevoke.msgQueryHandle()
    if (handle == null) { ...; return emptySet() }
    val (db, m) = handle
    ...
    for (md5 in tokens.sorted()) {
        if (queried >= MAX_LOOKUP_TOKENS) { capped = true; ...; break }
        if (SystemClock.elapsedRealtime() - t0 > LOOKUP_BUDGET_MS) { capped = true; ...; break }
        queried++
        ...
        // ⚠️ WCDB 两个 rawQuery 重载的第 2 形参类型不同：String[] 传 arrayOf(md5)，Object[] 传 arrayOf<Any>(md5)
        val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
            arrayOf(md5)
        } else {
            arrayOf<Any>(md5)
        }
        val sql = AntiRevokeHint.BYPASS +
            "select id from ImgInfo2 where instr(thumbImgPath, ?) > 0 limit $MAX_ROWS_PER_TOKEN"
        val cur = m.invoke(db, sql, args)
        ...
    }
    if (out.isEmpty()) { ...; return emptySet() }
    ...
}
```

**根因定位**：`where instr(thumbImgPath, ?) > 0` 是对 84 万行 TEXT 的逐行子串扫描，无法走任何索引（`serverImgInfoIndex` / `msgLocalIdIndex` / `origImgMD5_index` 全用不上）。`LOOKUP_BUDGET_MS` 只在「每个 token 开始前」检查，单条 `rawQuery` 原子不可中断，预算形同虚设（真机 `cost=97166ms`）。

### 调用点 — RevokeRescue.kt 原 `:128`

```kotlin
MediaTableGuard.invalidate()
```
（位于 `if (orig.type == TYPE_IMAGE || TYPE_VIDEO)` 块内，`msgId` 变量同作用域可见）

---

[DONE] T2

## T2 新实现

### 2.1 签名

```kotlin
fun invalidate(msgId: Long = 0L) {
    gen++
    lastIssuedMs = SystemClock.elapsedRealtime()
    val tokens = snapshotLiveTokens()
    cachedTokens = tokens
    cachedRowIds = lookupRowIds(tokens, msgId)   // ← 多传 msgId
    runCatching { refresh() }
}
```

### 2.2 `lookupRowIds(tokens: Set<String>, msgId: Long)` 两段式

#### 分支顺序（固定）

1. `tokens.isEmpty()` → `MISS reason=tokens-empty rows=0 cost=0` → `emptySet()`
2. `msgQueryHandle()` == null → `MISS reason=handle-null` → `emptySet()`
3. 主路径 via=svr（`msgId > 0`）
4. 兜底 via=recent
5. 全空终判 → `MISS total`

#### 主路径 via=svr 的 SQL（两条，各 try/catch + Cursor finally close）

a) `message.msgId → msgSvrId`

```sql
select msgSvrId from message where msgId=? limit 1
```
绑定：`bindArgs(msgId.toString())`。`getLong(0)` 取 svr；`svr<=0` ⇒ 打 `via=svr MISS reason=svr<=0` 后转 recent。

b) `ImgInfo2.msgSvrId → id, thumbImgPath`（命中 `serverImgInfoIndex`）

```sql
select id, thumbImgPath from ImgInfo2 where msgSvrId=? limit 16
```
绑定：`bindArgs(svr.toString())`。逐行 `getLong(0)`→`normalizeId`，`getString(1)`→`thumbImgPath`。

> ⚠️ **covering index 权衡（如实记录）**：`serverImgInfoIndex` 是 covering index，若只 `select id` 则索引里直接取，**连回表都不需要**。但为了防串号，本实现必须把 `thumbImgPath` 加进 select 列 ⇒ 退化为「普通索引查找 + 回表」。这是**有意的取舍**：回表 ≤16 次，仍远快于 84 万行全表扫；⛔ 不为了保住 covering index 而省掉 thumbImgPath 校验（防串号优先）。

#### ⭐ 校验逻辑（防 svrId 串号 / 多图共用 svrId）

```kotlin
val id = MediaDeleteLogic.normalizeId(XposedHelpers.callMethod(cur, "getLong", 0))
val thumb = XposedHelpers.callMethod(cur, "getString", 1) as? String
if (id != null && thumbMatchesToken(thumb, tokens)) out.add(id)
```

```kotlin
private fun thumbMatchesToken(thumb: String?, tokens: Set<String>): Boolean {
    if (thumb == null) return false
    val t = thumb.lowercase()
    return tokens.any { t.contains(it) }
}
```
只有 `thumbImgPath`（`THUMBNAIL_DIRPATH://th_<md5>`）里含保护池 token 的行才采纳。

#### 兜底 via=recent 的 SQL

```sql
select id, thumbImgPath from ImgInfo2 order by id desc limit 500
```
无参（`m.invoke(db, sql, null)`）。主键逆向扫描毫秒级，应用层按 token `contains` 匹配，`scanned` 累计实际扫过的行数。

#### 参数绑定（复用原有分支，未重写）

```kotlin
fun bindArgs(v: String): Any =
    if (m.parameterTypes[1] == Array<String>::class.java) arrayOf(v) else arrayOf<Any>(v)
```
与旧 `:451-455` 的 `String[]` vs `Object[]` 判断完全同源，只是抽成局部函数供 3a/3b 两处复用。

#### 日志格式（每个出口一行，不节流）

| 出口 | 日志 |
|------|------|
| tokens 空 | `$TAG id-lookup MISS reason=tokens-empty rows=0 cost=0` |
| 句柄 null | `$TAG id-lookup MISS reason=handle-null rows=0 cost=$cost` |
| svr 拿不到 | `$TAG id-lookup via=svr MISS msgId=$msgId svr=$svr reason=svr<=0\|$err cost=$cost` |
| svr 命中 | `$TAG id-lookup via=svr msgId=$msgId svr=$svr rows=$n ids=$ids cost=$cost` |
| svr 无匹配行 | `$TAG id-lookup via=svr MISS msgId=$msgId svr=$svr reason=no-match\|$err cost=$cost` |
| recent 命中 | `$TAG id-lookup via=recent scanned=$n rows=$n ids=$ids cost=$cost` |
| recent 未命中 | `$TAG id-lookup MISS via=recent scanned=$n rows=0 cost=$cost` |
| 全空终判 | `$TAG id-lookup MISS total rows=0 tokens=$queried capped=$capped cost=$cost` |

#### 机制依据（EXPLAIN QUERY PLAN 实测，非估算）

三条 SQL 的执行计划已在真机主库 `EnMicroMsg.db` 上 `EXPLAIN QUERY PLAN` 实证：

```
message  : SEARCH TABLE message USING INTEGER PRIMARY KEY (rowid=?)
ImgInfo2 : SEARCH TABLE ImgInfo2 USING COVERING INDEX serverImgInfoIndex
```

| 用途 | SQL | 执行计划 | 定性 |
|---|---|---|---|
| 主路径① | `select msgSvrId from message where msgId=?` | `SEARCH TABLE message USING INTEGER PRIMARY KEY (rowid=?)` | O(log n)，实测 |
| 主路径② | `select id, thumbImgPath from ImgInfo2 where msgSvrId=?` | `SEARCH TABLE ImgInfo2 USING COVERING INDEX serverImgInfoIndex` | 索引查找 ≤16 行回表，实测 |
| 兜底 | `select id, thumbImgPath from ImgInfo2 order by id desc limit 500` | `SCAN TABLE ImgInfo2`（**无 `USE TEMP B-TREE FOR ORDER BY`**） | 推断扫 500 行即停，`[需真机]` |
| ~~旧路径~~ | ~~`select id from ImgInfo2 where instr(thumbImgPath,?)>0`~~ | 无可用索引 | **842,935 行全表扫，实测 97166ms** |

要点：

1. 第一步走 **INTEGER PRIMARY KEY（rowid）** ⇒ O(log n)，非全表扫。
2. 第二步走 **COVERING INDEX** ⇒ 索引里就有 `id`+`msgSvrId`，最理想；只因加 `thumbImgPath` 校验退化为索引查找 + ≤16 次回表（见上面权衡，可接受）。
3. 兜底 `SCAN TABLE` **且无 temp B-tree** ⇒ 顺序由 rowid/主键自然逆序提供，`limit 500` 扫到 500 行即停，不遍历 842,935 行。但这是**执行计划形态推断，非计时实测**，`[需真机]` 验 `cost`。
4. **这是本轮「97166ms → 毫秒级」的机制依据，不是估算。**

#### 成本对比与硬验收指标

| 路径 | 期望耗时 |
|---|---|
| 旧路径 `instr` 全表扫 842,935 行 | 实测 **97166ms** |
| 新主路径（message 主键 1 行 + ImgInfo2 索引 ≤16 行回表） | 期望 **< 10ms** |
| 新兜底（主键逆扫 500 行 + 应用层 contains） | 期望 **< 50ms** |

⛔ **硬验收指标**：真机 `cost` 必须从 97166ms 降到 **< 100ms**（由主会话装包 + 真机验 `cost` 打点；本轮只保证实现形态正确，不跑真机）。

### 2.3 常量

- 删 `MAX_LOOKUP_TOKENS`（不再逐 token 查）。
- 保留 `MAX_ROWS_PER_TOKEN = 16`（svr 路径 `limit 16`）。
- 新增 `MAX_RECENT_SCAN = 500`（兜底扫描行数上限）。
- 保留 `LOOKUP_BUDGET_MS = 20L`（声明存在但不再作为拦慢 SQL 的手段，注释已说明）。

### 2.4 绝对保留项核对

- `param.result` 仍只有 2 处赋值：`execSQL` → `null`（`:306`）、`delete` → `FAKE_ROWS`（`:309`），一字未改；`grep -c "param.result"` = 4（含 2 行注释）。
- 放行仍裸 `return`，未 `setResult`、未吞异常。
- `op=mtg sub=stat|reset`、`resetLedger()`、6 条静默 return、`obsLogAt`/`logAt`、账本、`MTG delete reason=` / `MTG stat verdict=`、60s 节流、`MAX_LOG_KEYS` —— 全部逐字保留。
- 零新增写操作、零新增 import（`bindArgs`/`thumbMatchesToken` 均为文件内私有函数，无新 import）。
- `cachedRowIds` 仍 `@Volatile`。
- 每个查询各自 try/catch，Cursor `finally close`。

---

[DONE] T3

## T3 三档边界表

### 进入

| 条件 | 走哪条 |
|------|--------|
| `tokens.isEmpty()` | 直接返回（不查库），`MISS reason=tokens-empty` |
| `msgQueryHandle()` == null | 直接返回（不查库），`MISS reason=handle-null` |
| `msgId > 0` 且 `message.msgSvrId > 0` | 主路径 via=svr（`serverImgInfoIndex`） |
| `msgId > 0` 但 `svr <= 0`（查不到/异常/无该行） | 打 `via=svr MISS reason=svr<=0` 后转 recent 兜底 |
| `msgId <= 0`（默认值 / 非正数） | 跳过主路径，直接 recent 兜底 |
| svr 命中但 thumbImgPath 校验后无匹配行 | 打 `via=svr MISS reason=no-match` 后转 recent 兜底 |

### 返回

| 出口 | 返回 | 日志 |
|------|------|------|
| tokens 空 | `emptySet()` | `MISS reason=tokens-empty` |
| 句柄 null | `emptySet()` | `MISS reason=handle-null` |
| svr 命中 ≥1 行 | 返回 `out`（非空） | `via=svr ... rows=$n ids=$ids` |
| recent 命中 ≥1 行 | 返回 `out`（非空） | `via=recent ... rows=$n ids=$ids` |
| recent 未命中 / 异常 | `emptySet()` | `MISS via=recent ... rows=0` + `MISS total ... rows=0` |

（`emptySet()` 共 4 个出口：tokens-empty、handle-null、recent 未命中即 return emptySet、及兜底查询异常落入同一条 recent MISS 路径。）

### 复位（⛔ 关键，历史教训）

`cachedRowIds` 的**唯一**写入点在 `invalidate()`（`:198`），每次 `invalidate()` 都无条件重建（`gen++` 同批）。因此：

- **窗口过期**：`cachedRowIds` 本身不按窗口主动清；但下次 `invalidate()` 会用新的 `snapshotLiveTokens()`（已剔除过期 token）重建，旧的 id 快照随之被覆盖。
- **`gen` 自增**：`invalidate()` 里 `gen++` 与 `cachedRowIds = lookupRowIds(...)` 同批执行，二者永远同一代。
- **`op=mtg sub=reset`**：走 `resetLedger()`，**只清账本**，⛔ **不动** `cachedRowIds`（以及 `enabled`/`cachedTokens`/`gen`/`lastIssuedMs`/`mediaPoolRef`），注释已明确。
- ⛔ **「滚回顶部 / `pos <= prev` 不是复位触发条件」**：本实现里不存在任何「位置回退即复位」逻辑，`cachedRowIds` 只由 `invalidate()` 覆盖，与消息列表滚动/位置无关。

---

[DONE] T4

## T4 编译与单测实测输出

### 第 1 步（先编译）

```
> Task :app:compileDebugKotlin
> Task :app:compileDebugJavaWithJavac NO-SOURCE
> Task :app:dexBuilderDebug
> Task :app:mergeDebugGlobalSynthetics UP-TO-DATE
> Task :app:processDebugJavaRes UP-TO-DATE
> Task :app:mergeDebugJavaResource UP-TO-DATE
> Task :app:bundleDebugClassesToCompileJar
> Task :app:mergeProjectDexDebug
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebug
> Task :app:compileDebugUnitTestKotlin

BUILD SUCCESSFUL in 46s
37 actionable tasks: 6 executed, 31 up-to-date
```

### 第 2 步（编译后跑单测）

```
JUnit version 4.13.2
.........(901 个点，单行).........
Time: 0.38

OK (901 tests)
```

### 四闸门

| 闸门 | 结果 |
|------|------|
| `grep -c "instr(" MediaTableGuard.kt` | `0` ✅ |
| `grep -c "param.result" MediaTableGuard.kt` | `4` ✅（改前即 4） |
| BUILD SUCCESSFUL | ✅ |
| OK (901 tests) | ✅ |

---

[DONE] T5

## T5 回滚（两文件当前 git 状态实测）

```
 M wxgroup/app/src/main/java/com/lx/wxgroup/feature/RevokeRescue.kt
?? wxgroup/app/src/main/java/com/lx/wxgroup/feature/MediaTableGuard.kt
```

### `MediaTableGuard.kt`（untracked，`??` —— 无 git 基线，`git revert`/`git checkout --` 会删文件，⛔ 禁止）

本轮在该文件内改动了 4 个块，回滚 = 反向还原这 4 块：

1. `invalidate()`（`:178-200`）：KDoc + 签名 `fun invalidate(msgId: Long = 0L)` 改回 `fun invalidate()`；体内 `cachedRowIds = lookupRowIds(tokens, msgId)` 改回 `cachedRowIds = lookupRowIds(tokens)`；删掉 `@param msgId` 注释段。
2. 常量区（`:80-93`）：KDoc 改回旧「R52 甲 封顶」文案；补回 `private const val MAX_LOOKUP_TOKENS = 32`；删 `private const val MAX_RECENT_SCAN = 500`。
3. `lookupRowIds`（`:402-542`）：整段（含 KDoc + 两段式实现）改回旧逐 token `instr` 版本（旧 `:394-500`）。
4. `thumbMatchesToken`（`:544-549`）：删除该助手函数。

### `RevokeRescue.kt`（`M`）

本轮只动 1 行（`:128`）：`MediaTableGuard.invalidate(msgId)` 改回 `MediaTableGuard.invalidate()`。

> ⚠️ 注意：`RevokeRescue.kt` 的 `M` 状态在本轮之前就已存在（该行 `MediaTableGuard.invalidate()` 是上一条 lane 未提交的 R52 甲 改动）。`git diff` 相对 HEAD 只显示 `+ MediaTableGuard.invalidate(msgId)` 这一行。回滚只撤我这一行的 `(msgId)` 参数，不要把整行删掉（否则会连带丢掉上一 lane 的调用点）。

---

[DONE] T6

## T6 残留风险（⛔ 未真机验证，均为推断）

### 6.1 `ImgInfo2.msgSvrId` 覆盖率实测（两档数据，如实记录）

真机主库 `EnMicroMsg.db` 实测：

```sql
select count(1) as total,
       sum(case when msgSvrId is null or msgSvrId=0 then 1 else 0 end) as nosvr
from ImgInfo2
-- → total=842935 | nosvr=260099  ⇒ 全表仅 69.1% 有 msgSvrId
```

```sql
select id, msgSvrId from ImgInfo2 order by id desc limit 12
-- 876176/175/174/173/172/171/170/169/168/167/165 均有 svrId；仅 876166 为 NULL
-- ⇒ 最近 12 行 11 行有值，≈ 92%
```

**解读（⛔ 不要写成「覆盖率只有 69%，所以主路径不可靠」）**：

- 全表 69% 是被**历史旧图**拉低的（26 万行 `nosvr` 主要是早年记录）。
- **新插入行 11/12 ≈ 92%** ⇒ 撤回场景（图刚发、行刚插入）主路径 `via=svr` **大概率命中**。
- ⇒ 架构判断不变：**主路径 svr 优先，`via=recent` 兜底补漏**。兜底覆盖的是那 ~8% 例外（如 `id=876166`），不是主力。
- ⛔ **新发图真实覆盖率仍需真机采样**，⛔ 不要用任一个数字（69% 或 92%）推断「必然失败」或「必然成功」。

### 6.2 `thumbImgPath` 校验可行性（已实测，非推断）

上次真机 `instr(thumbImgPath, '1a7b9f5d918f9d80ce5c8065d3dcb5e7')` 精确命中 `id=876171` ⇒ **`ImgInfo2.thumbImgPath` 列确实包含裸缩略图 md5 子串**（形态 `THUMBNAIL_DIRPATH://th_<md5>` 或含该子串）。故 `via=svr` 里「校验 thumbImgPath 含池 token」的步骤可行，可放心用来防串号。

### 6.3 逐项 `[需真机]`

1. **[需真机] 硬指标 `cost`**：`cost` 必须从 97166ms 降到 **< 100ms**。本轮只保证实现形态（索引命中 + 无全表扫），真实 `cost` 由主会话真机 `via=svr` / `via=recent` 日志打点验证。
2. **[需真机] `message.msgSvrId` 是否恒非 0**：若某消息 `msgSvrId` 为 0/NULL，落到 recent 兜底。
3. **[需真机] `via=recent` 真实耗时**：执行计划是 `SCAN TABLE ImgInfo2` 且**无 `USE TEMP B-TREE FOR ORDER BY`** ⇒ 顺序由 rowid/主键自然逆序提供，`limit 500` 扫到 500 行即停、不遍历 842,935 行。但这是**执行计划形态推断，非计时实测**，`cost` 由主会话真机验。
4. **[需真机] recent 500 行是否够覆盖 2 分钟撤回窗口**：若 2 分钟内 ImgInfo2 新增 >500 行（多图群聊高频），被撤回行可能掉出窗口 ⇒ 拦截失效（退化成旧「侥幸」状态）。
5. **[需真机] `thumbImgPath` 校验是否可能误杀**：若某些行 `thumbImgPath` 为 NULL / BLOB / 非 `THUMBNAIL_DIRPATH://th_<md5>` 形态，`getString` 取不到 ⇒ 该行被拒。这是**保守方向**（宁可不拦，不误拦），但可能漏救。
6. **[需真机] `msgSvrId` 串号 / 多图共用 svrId**：主路径靠 thumbImgPath 校验兜底，但若两条不同图共用同一 `msgSvrId` 且 thumbImgPath 都含 token，会把两条都收进 `out`（多收属于保守方向，不会误删）。
7. **[需真机] `execSQL` 置 `param.result = null` 的跳过语义**（承自旧实现，非本轮引入）：未验证微信是否因此不重试删除。

### 6.4 兜底备选写法（只写建议，⛔ 不实现、不切换）

若主会话真机 `cost` 显示 `SCAN TABLE` 兜底仍有疑问，可考虑语义等价的更保险写法：

```sql
select id, thumbImgPath from ImgInfo2 where id > (select max(id) from ImgInfo2) - 500
```

—— 用 `max(id)` 先定界，再按主键范围取，边界更明确。⛔ 是否采用由主会话真机 `cost` 数据决定，本轮**不自行切换**。

⛔ 以上不给出「能/不能」的结论，全部留真机样本验证（观察 `via=svr` / `via=recent` / `MISS total` 三行日志与 `cost` 打点）。

---

[DONE] ALL
