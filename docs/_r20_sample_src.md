# R20 · lane `sample-src`：导出改扫「当前 SnsInfo」（防撤回语义）

> lane 边界：本轮**只改** `MomentsExport.kt` 与 `SnsGuard.kt`。
> 不许碰 `MomentsLogic.kt` / `SnsCdnImage.kt`（lane `A8-del` 在改）/ `MomentsNotify*.kt`。
> 不碰 adb、不装包。

## 0. 需求语义（用户 2026-10-25 原话 + 定案）

> 「选 A（改扫当前 SnsInfo），本功能是用来**搭配定时刷新朋友圈实现防撤回**」

⇒ 导出扫的是**当前朋友圈可见的动态**，在它**还看得见的时候**把图留存下来（预防性留存），
而不是等它被删了再从 `sns_archive` 里抢救。

与现状的冲突（team-lead 上一轮实测）：

| | 扫的库 | 内容 | 与 sns 磁盘缓存 |
|---|---|---|---|
| 现在 | 模块库 `sns_archive` | 历史**已被删除**的动态 | **不重叠**（7 个 mediaId / 72 个缓存文件 0 命中）|
| 目标 | 微信库 `SnsInfo` | **当前可见**的动态 | 就是这些（缓存里放的是当前动态的图）|

⇒ 换数据源后 `imgCopied` 才有可能 > 0。

---

## 1. 打通通路：`SnsGuard.listCurrentSns`

### 1.1 签名与契约（`SnsGuard.kt:526`）

```kotlin
fun listCurrentSns(createTime: Long, snsId: Long, limit: Int): List<GroupDb.SnsRow>?
```

| 参数 | 含义 |
|---|---|
| `createTime` / `snsId` | **复合键集游标**，取「字典序严格更小」的下一页；首轮传 `Long.MAX_VALUE` |
| `limit` | 页大小，内部 `coerceIn(1, 200)` 再拼进 SQL |

返回三态语义里的**关键一态**：

- `List<SnsRow>` 非空 → 这一页的行（`createTime desc, snsId desc`）
- `List<SnsRow>` **空** → 这一源扫完了
- `null` → **拿不到微信库句柄**（`snsDb` 还没捕获到）。**这不是错误**，调用方必须优雅降级

复合游标而不是只按 `createTime <`：朋友圈同一秒多条很常见，单列游标会在页边界
把这些行丢掉（`SnsGuard.kt:521`）。

### 1.2 为什么不让 `snsDb` 变 `public`

`SnsGuard.snsDb` 是一个 **`Any`**（微信库实例是 wcdb 的私有实现，类型在编译期不存在）。
放开可见性等于让调用方自己 `runCatching { rawQueryMethod(db)?.invoke(db, sql, args) }` ——
把三件容易踩的坑复制到每个调用点：

1. **`Method` 绝不能跨库缓存**：AOSP 的 `rawQuery` 拿去调 wcdb 实例会抛
   `IllegalArgumentException: Expected receiver of type android.database.sqlite.SQLiteDatabase`
   （`SnsGuard.kt:412`）。每处都得按 `db.javaClass` 现找。
2. **`rawQuery` 两个重载的第二个参数类型不同**（`Array<String>` vs `Array<Any>`），
   要按 `m.parameterTypes[1]` 分派，写错了是 `invoke` 抛 `IllegalArgumentException`。
3. **游标必须 `close()`**，而且得在 `finally` 里。忘了关 = 微信侧游标泄漏。

所以对外暴露的是「**已经处理完这些坑的一页行**」，句柄留在 `SnsGuard` 内部。
这同时保证 `SnsRow` 的字段填充口径只有一份（见 1.5）。

### 1.3 可见性判据：`(localFlag & 16) != 0` 的依据

微信时间线的真实查询是：

```sql
select *, rowid from SnsInfo where localFlag in (16,2064,18,2066,24,2072,…)
```

白名单里的**每一个值都置了 bit4（16）**（`SnsGuard.kt:290` 的 `VISIBLE_BIT`）。所以：

- 按位判 `(localFlag & 16) != 0` 是那个白名单的**等价超集** —— 不会漏掉任何可见行；
- 微信删动态是**两步**：先把可见位清掉（实测落成 `localFlag=2`），再删行。
  所以 `localFlag=2` 的行必须被排除 —— 它不是「当前可见」，而是「已经被删了但还没来得及删行」。

这条判据与 `restoreVisibility` 里的 `VISIBLE_BIT` 是**同一个常量**，不是两处各写一个 16。

### 1.4 句柄捕获：为什么必须补一条 `rawQuery` 钩子

`snsDb` 原来**只**在「DELETE 命中 SnsInfo」时被记下来（`isSnsDb`，`SnsGuard.kt:298`）。
而定时刷新朋友圈走的是 INSERT/REPLACE —— **永远不产生 DELETE** ⇒ 句柄恒为 `null` ⇒
导出恒 0 行，且日志只会显示「归档也没几条」，根本看不出真因是句柄没拿到。

所以 `installCapture` 挂了 `rawQuery` 作为**唯一**的额外钩子，并且**拿到就自我卸载**
（`unhookCapture`）：之后是零钩子，不是「常驻一层开销」。卸载前每次调用只做
「`snsDb != null` 判空 + `sql.contains("SnsInfo")`」。

### 1.5 只取 7 列：`attrBuf` / `postBuf` **不读**

```sql
select snsId,userName,createTime,type,sourceType,localFlag,content from SnsInfo …
```

朋友圈 `content` 动辄几十 KB，一页 100 行再搭两个 BLOB 就是几百 MB，
后台线程 OOM 会把微信带走（与 `GroupDb.listSnsArchiveBefore` 同一条理由）。

⚠️ `SnsRow` 对 `subType` / `serverExtFlag` / `head` / `stringSeq` / `attrBuf` / `postBuf`
**六个字段都没有默认值**（只有 `at` 有），所以这六个必须**显式**填 `0` / `null`，见 §7。

⛔ **不要拿这里的半行去归档**（`GroupDb.archiveSns`）：那是用一个残缺快照覆盖掉
`readRow` 存下来的完整快照，而 `archiveSns` 是「首条为准」，覆盖不回来。

---

## 2. 导出改扫当前动态：`SnsScanCursor` 的双源设计

`SnsScanCursor`（`MomentsExport.kt:102`）是**纯逻辑类** —— 不碰 Android、不碰数据库、
不碰 `GroupDb`。它存在的唯一理由是：`MomentsExport` 自己是 `object`，静态初始化带着
`Handler` / `Kv` / `Prefs` 一串 Android 依赖，JVM 单测碰不得。把游标抠出来才能测。

### 2.1 状态

| 字段 | 含义 |
|---|---|
| `src` | `Src.CURRENT`（主，微信库当前动态）或 `Src.ARCHIVE`（补，模块归档表） |
| `ct` / `sid` | 键集游标；`MAX_VALUE` = 该源的最新一页 |
| `seen` | 本轮已处理过的 snsId（**每轮新建**，不跨轮继承） |

### 2.2 主循环（`MomentsExport.kt:455-500`）

⚠️ 行号随主会话的 `bail()` 重构（§8）下移过，`SnsScanCursor` 类本体在 `:102-151`，**不受影响**。

```
while (rows < MAX_ROWS_PER_RUN && !stop) {
    page = when (cur.src) {
        CURRENT -> runCatching { SnsGuard.listCurrentSns(cur.ct, cur.sid, PAGE) }
                       .onFailure { LogX.w("... THREW ...") }   // 异常留痕，不静默
                       .getOrNull()                             // null = 句柄没拿到 或 查询抛了
        ARCHIVE -> runCatching { db.listSnsArchiveBefore(cur.ct, cur.sid, PAGE) }.getOrNull()
    }
    page == null      -> 记日志（curNoDb++）→ nextSource() → 继续       // ⚠️ 降级，不弹窗
    page.isEmpty()    -> nextSource() 或 break
    for (row in page) { cur.advanceTo(...); if (!cur.first(...)) continue; … }
}
```

⚠️ `CURRENT` 分支的 `.onFailure` 是 R20b 复核补的：早先是一句
`runCatching{…}.getOrNull()`，把「**正常降级**（句柄未捕获 ⇒ `listCurrentSns` 主动返回 null）」
与「**反射/查询抛异常**」压成同一个 `null`，且异常**零日志**；而下面 `page == null`
那段日志写死成 `sns db not captured` —— 若 null 真来自异常，这条日志就是**误导**，
现场会往完全错的方向排查。补 `.onFailure` 后两种来源在日志里可区分
（`source=current THREW XxxException: …` vs `source=current UNAVAILABLE`），
而 `.getOrNull()` 的降级语义保留：异常同样退回扫归档、不中断本轮。

### 2.3 切源规则

- `CURRENT` 页空 **或** 返回 `null` ⇒ `nextSource()`，`src` 变 `ARCHIVE`，
  `ct`/`sid` **双双复位成 `MAX_VALUE`**（归档是另一张表，它自己的最新页也从最大值开始；
  不复位就会用当前源最后一行的时间戳当上界，把归档里更新的全漏掉）。
- `ARCHIVE` 页空 ⇒ `nextSource()` 返回 `false` ⇒ 主循环 `break`。
- `src == ARCHIVE` 时 `nextSource()` **恒返回 false 且不改 `src`** —— 主循环的 `when`
  只有两个分支，来个第三态就整轮扫 0 行。

⚠️ **`null` 不是故障**：微信这次启动还没碰过朋友圈库 ⇒ 句柄未捕获 ⇒ 优雅降级成只扫归档
（= 2026-10-25 之前的行为），只打一行日志，**不弹窗、不判本轮失败**
（`curNoDb` 只在第一次打日志，避免每轮刷屏）。

### 2.4 每轮上限

`PAGE = 100`、`MAX_ROWS_PER_RUN = 500`。BLOB 一页一页读，内存有界；
导出是 5 分钟一跳的常驻任务，不存在「一轮必须扫完」的要求。

---

## 3. 与归档按 `snsId` 去重

`SnsScanCursor.first(snsId, exported)`：

```kotlin
fun first(snsId: Long, exported: Set<Long>): Boolean =
    snsId !in exported && seen.add(snsId)
```

两个判据**互相独立**，短路顺序不能反：

| 判据 | 语义 | 生命周期 |
|---|---|---|
| `exported` | 磁盘上已有 `<snsId>.txt` | **跨轮**（扫目录得来）|
| `seen` | 本轮已处理 | **本轮** |

- 同一条动态在「当前源」和「归档」里都有（刚发就被删的那种）⇒ 只导出一次；
- `exported` 命中时**不写 `seen`** —— 否则本轮后续对这条的一切判断被永久锁死；
- ⭐ **被 `first()` 返回 false 跳过的行，`advanceTo()` 仍必须调**。键集分页的条件是
  `createTime < ? or (createTime = ? and snsId < ?)`；游标不跟着走 ⇒ 下一页拿到**同一页** ⇒
  同样被跳过 ⇒ 直到 `rows` 涨到 500 才退出。看上去「跑完了」，其实只扫了第一页。
  主循环里 `advanceTo` 写在任何 `continue` **之前**，就是为了这个。

`exported` 集合还有一层兜底：进主循环前扫目录（`SCAN_MAX_IDS = 20_000` 封顶），
写文件前再问一次 `txt.exists()`（防扫描上限漏掉），命中就 `exported.add` 并 `sk++`。

---

## 4. 「预防性留存」语义写进了哪些 KDoc

需求语义不是只写在本文档里，而是**钉在会被人读到的三个位置**，改代码前必读：

| 位置 | 内容 |
|---|---|
| `MomentsExport.kt:20-30` | 类头「⚠️ 需求语义（用户 2026-10-25 定案，改代码前先读这段）」：预防性留存 ≠ 删除后抢救；R19 实测 7 个 mediaId / 72 个缓存文件 0 命中，所以是数据源选错，不是取图链路坏了 |
| `MomentsExport.kt:32-44` | 数据链：① 微信库 `SnsInfo`（主）→ ② 模块库 `sns_archive`（补）→ 按 `snsId` 去重 → 渲染 → 落盘；并写明 ①拿不到**不是故障** |
| `SnsGuard.kt:493-524` | `listCurrentSns` 的 KDoc：独立一节「⚠️ 语义：这是「防撤回」，不是「删除后抢救」」——sns 磁盘缓存 `…/cache/<hash32>/sns/…/snst_xxx` 里放的正是**当前可见**动态的图；扫归档时 `imgCopied` 恒 0 是数据源错了。后面紧跟「## 可见性过滤」讲 `(localFlag & 16) != 0` |
| `SnsGuard.kt:114-118` | 为什么必须补 `rawQuery` 捕获钩子：定时刷新只 INSERT/REPLACE，不产生 DELETE ⇒ 句柄恒 null |
| `SnsGuard.kt:294-307` | `installCapture` 的 KDoc「## 为什么必须有它」，同一段语义的第四处复述（改钩子时最容易漏看的地方）|
| `MomentsExport.kt:89-100` | `SnsScanCursor` 的 KDoc：两条坑（跨源去重 / 切源）都只在真机跑满一轮才暴露；`:100` 点名「见 `MomentsExportScanTest`」|

---

## 5. `COPY_IMAGES` 仍为 `false`

```kotlin
const val COPY_IMAGES = false   // MomentsExport.kt:165
```

原因（常量处的 KDoc 写得很直白）：

- 命名规则 R15 已取证：缓存目录 `/sdcard/Android/data/com.tencent.mm/cache/<hash32>/sns/<hex1>/<hex2>/`，
  文件名是 `snsb_` / `snsu_` / `snst_` + mediaId 派生，**不是 md5**（旧 md5 路线命中率 0.68%，
  公式路线已废弃，正解为建文件名索引 + 候选名匹配，见 `buildSnsIndex` / `copyImagesForRow`）；
- 代码链路已就位，`mediaId` 来源也已接通（`MomentsLogic.mediaIdsFromContent`），
  但**真机复核还没做**，`imgCopied` 到底能不能 > 0 未经验证；
- ⛔ **严禁猜规则硬写**。

本轮换了数据源，也只是让 `imgCopied > 0` **有了可能**，不等于已验证。所以保持 `false`。
文本文件里已带媒体 URL 段（`renderText` 的「---- 媒体 ----」），内容不丢。

---

## 6. 本轮新增测试：`MomentsExportScanTest`

`MomentsExport.kt:100` 的 KDoc 一直引着「见 `MomentsExportScanTest`」，
但**全仓 grep 零命中** —— 这个类此前根本不存在。R20 补上：
`wxgroup/app/src/test/java/com/lx/wxgroup/feature/MomentsExportScanTest.kt`，
纯 JVM（`SnsScanCursor` 无 Android 依赖），JUnit4 + `org.junit.Assert.*`，无新依赖。

7 条 `@Test`，逐条对应 KDoc 里写明的性质：

| # | 测试 | 锁的真机故障 |
|---|---|---|
| 1 | `sameSnsIdIsDedupedAcrossSources` | 跨源去重：当前源 `first()=true` → 切归档 → 同 id `first()=false`；同游标内再现仍 `false`；**新游标**（下一轮）重新为 `true` |
| 2 | `nextSourceSwitchesToArchiveAndResetsCursor` | 切源必须把 `ct`/`sid` 双双复位成 `MAX_VALUE`；不复位则归档第一页被当前源的旧上界截掉，比它新的全漏扫 |
| 3 | `archiveIsLastSourceAndSwitchReturnsFalse` | 归档是最后一个源：`nextSource()=false` 且 `src` 不变。返回 true 会重复扫归档；改出第三态则主循环 `when` 走空 |
| 4 | ⭐ `cursorAdvancesEvenWhenRowIsSkipped` | 被去重跳过的行仍需 `advanceTo`。不推进 ⇒ 键集分页原地打转 ⇒ 死循环 / 一轮只扫第一页 |
| 5 | `cursorWalksForwardAcrossPages` | 连续多页严格往「更旧」方向走（键集分页不倒流） |
| 6 | `distinctNegativeSnsIdsDoNotCollide` | 两个**低 32 位相同、高 32 位不同**的负 Long（`toInt()` 完全一样）必须各自 `true`；`toInt()` 截断会静默吞掉一条 |
| 7 | `exportedHitDoesNotPolluteSeen` | `exported` 命中 ⇒ `false` 且**不写 `seen`**；随后 `first(id, emptySet())` 必须为 `true`。锁住「磁盘已导出」与「本轮已处理」是两个独立判据，以及短路顺序 |

第 5 条是任务要求之外的补充（多页推进），用来覆盖「单页推进对、跨页方向错」这种半对的情况。

### 空跑结果（本轮实测）

```
cd /c/Users/lx/WorkBuddy/2026-09-29-20-23-21 && python tools/gradle_run.py testDebugUnitTest

EXIT=0
BUILD SUCCESSFUL in 11s

files=26  tests=518  failures=0  errors=0  skipped=0
```

其中 `MomentsExportScanTest`：`tests=7 failures=0 errors=0 skipped=0`。

⚠️ 本轮跑了三次，前两次红都**不是本 lane 的问题**，记下来便于下一轮识别：

1. `EXIT=1`，`MomentsExport.kt:535 Unresolved reference 'logImgProbe'` ——
   A8-del lane 删 `logImgProbe()` 时漏清 `runBlocking()` 里的调用点
   （连带 `imgProbeLogged` / `IMG_PROBE_MAX`，见 §6.1）。同批 lane 随后清掉，重跑即绿。
2. `EXIT=1`，`MomentsLogicTest.kt` 一片 `Unresolved reference 'imageCandidates' / 'pickExisting'` ——
   A8 收敛删 `MomentsLogic` 的旧 md5 路线函数时，测试还没同步。等并行 lane 落定后重跑即绿
   （`MomentsLogicTest` 从 56 条降到 49 条）。

**本 lane 没有改任何 main 源文件**，只是恰好撞在别人的编译窗口里。
最终确认时 `MomentsLogic.kt` 已稳定 ≥3 分钟，工作树是绿的。

---

## 6.1 编译期踩到的 lane 间错（第 3 处，已由 A8-del 侧清掉）

`logImgProbe` / `imgProbeLogged` / `IMG_PROBE_MAX` 三者在 R20 被判定为「旧 md5 路线的遗留探针」
整批删除，但 `runBlocking()` 主循环里还剩：

```kotlin
imgProbeLogged.set(0)                                                 // :435
if (imgProbeLogged.get() < IMG_PROBE_MAX) logImgProbe(row, nick)      // :540
```

清掉后编译通过。教训与 §7 同源：**跨 lane 删符号前先全仓 grep 调用点**，
`compileDebugKotlin` 是唯一的验收闸。

---

## 7. 主会话已修的 2 处 lane 间接口错

上一轮 lane `sample-src` 被 429 限流打断，代码主体落盘但**两处跨文件接口没对齐**，
由主会话收口时修掉。记在这里，避免下一轮有人「顺手改回去」：

### 7.1 `Cfg(false, false)` → `Cfg(false)`

实际位置是 `SnsCdnImage.kt:79`（`@Volatile private var cfg = Cfg(false)`）。

R20 的 A8 收敛把 `Cfg` 的第二个字段 `rewritePath` 删了（该 key 全仓零读取点，
批量下载路线 2026-10-24 已定案放弃），`private data class Cfg(val enabled: Boolean)`
从两参变一参（`SnsCdnImage.kt:92`），但**初值那里还写着两参** ⇒ 直接编译不过。

⇒ 改成 `Cfg(false)`。**不要再加第二个参数**：真要新增开关，得先在
`Cfg` 里加带默认值的字段，而不是在调用点硬塞。

（`SnsCdnImage.kt` 不在本 lane 边界内，本次只是把已修的事实记档。）

### 7.2 `SnsRow` 六个必填字段显式填 `0` / `null`

`GroupDb.SnsRow`（`GroupDb.kt:359-375`）**只有 `at` 有默认值**
（`val at: Long = System.currentTimeMillis()`），其余 **13 个字段全部必填**。
`liveRows` 只查了 7 列，剩下 6 个必须显式传：

```kotlin
subType = 0,
serverExtFlag = 0,
head = null,
stringSeq = null,
attrBuf = null,
postBuf = null,
```

⚠️ **是 6 个，不是 5 个**。`content` 已经在 SELECT 列表里传进去了，所以不算缺；
缺的是上面这 6 个。编译器对每个字段**各报一条**
`No value passed for parameter 'subType'`（`compileDebugKotlin` 实测 6 条）。

填 `0`/`null` 与「留默认值」在语义上等价：下面的读法本来就不碰它们（只吃 7 列）。

⛔ 推论：**以后所有只查部分列的 `SnsRow` 构造点**都必须显式填齐，
不要指望默认值；也别指望 `copy()` 补 —— `copy()` 只能改已有实例的字段，
而「缺列的完整快照」根本不该被造出来（拿半行去 `archiveSns` 会用残缺快照覆盖掉
`readRow` 存下的完整快照，`archiveSns` 是「首条为准」，覆盖不回来）。

✅ **这条改进项已落实**：`GroupDb.kt:346-357` 的 `SnsRow` KDoc 现在有独立一节
「## ⛔ 只有 `at` 有默认值 —— 其余 13 个字段**必须显式写**」，
并引用了本次 R20 踩坑实例。

---

## 8. 行号漂移说明（并发 lane 改动的副作用）

本文档里的行号是 **2026-10-25 02:25 的工作树快照**。`MomentsExport.kt` 在两个 lane 并发改动后
整体下移过，读旧记录时注意：

| 改动 | 影响 |
|---|---|
| 主会话加 `bail(msg)` 局部函数 + 5 处 `return bail(...)`（F-1：早退轮必须 `abortRound()` 才能被算成失败轮）| `:383` 之后整体 **+~5 行** |
| A8-del / a8-finish 删 `logImgProbe` / `imgRoots` / `tryCopyImages` 及其派发 | 删了几十行，但其后在 `runBlocking()` **之后**，不影响主循环 |
| R20b 复核给 `CURRENT` 分支补 `.onFailure` 留痕（§2.2）| 主循环内 **+10 行** |

**不受影响**：`SnsScanCursor` 类本体在 `:102-151` —— 在全部改动点**之前**，
`MomentsExportScanTest` 因此完全不受行号漂移牵连（它只按符号测，不按行号）。

**当前关键锚点**（改代码前以此为准，别信记忆）：

| 符号 | 行 |
|---|---|
| `internal class SnsScanCursor` | `MomentsExport.kt:102` |
| `SnsScanCursor.first` / `.advanceTo` / `.nextSource` | `:136` / `:124` / `:144` |
| `const val COPY_IMAGES` | `MomentsExport.kt:165` |
| `fun bail` | `MomentsExport.kt:390` |
| `while (rows < MAX_ROWS_PER_RUN && !stop)` | `MomentsExport.kt:455` |
| `cur.advanceTo(row.createTime, row.snsId)` | `MomentsExport.kt:495` |
| `fun listCurrentSns` | `SnsGuard.kt:526` |
| `VISIBLE_BIT = 16` | `SnsGuard.kt:290` |
| `data class SnsRow` | `GroupDb.kt:359` |

✅ 上表在 **2026-10-25 02:30 收工前逐条 `grep` 复核过一遍**，全部命中。

---

## 9. 收工前的两处补记

### 9.1 `SnsRow` KDoc 已补（原「遗留改进项」已闭环）

§7.2 提到的建议已由主会话落实：`GroupDb.kt:346-357` 现在有独立一节
「## ⛔ 只有 `at` 有默认值 —— 其余 13 个字段**必须显式写**」，
写明「直觉是留默认值，但它们没有默认值，编译会直接报 6 条」，
并引用了 `SnsGuard.liveRows()` 的 R20 实例，还额外补了一条我没想到的推论：
**别指望用 `copy()` 补** —— `copy()` 只能改已有实例的字段，而「缺列的完整快照」
根本不该被造出来（半行进 `archiveSns` 会用残缺快照覆盖 `readRow` 的完整快照，
且 `archiveSns` 是「首条为准」，覆盖不回来）。这条比我原来的表述更严密。

### 9.2 `tools/_moments.py` 的静默失效（本轮额外发现，已修）

`tools/_moments.py:56` 的 `--img` 分支原本发 `sub=img` —— 那是**旧 md5 候选对账探针**，
在 R20 的 A8 收敛里被物理删除（见 `docs/_r20_a8_del.md`）。`MomentsExport` 对未知
`sub` 值不报错、只是什么都不做 ⇒ **该脚本静默无输出**：不报错、不打日志、也没有任何
「探针已不存在」的提示。跑的人只会以为「设备上没数据」。

已改成新链路的两条只读探针：先 `sub=snsidx`（看缓存索引能不能建起来）、
再 `sub=snsprobe --ei n 20`（mediaId 命中对账），判据文案也换成新链路的：
- `names>0` 才有得比；
- `hit=<文件名>` = 链路通；
- `names=0` 多半是冷启清空了缓存，**不是链路坏**。

📌 教训：**一个没人会日常跑的脚本静默失效，比代码 bug 更难发现** ——
它不在编译闸、不在单测、不在真机日志里，只在「下次真需要它的时候」暴露，
而那时人已经在排查别的问题了。凡是被删除的探针/入口，必须全仓 grep
（含 `tools/`、`docs/` 的命令示例），不能只 grep `app/src/`。

---

[DONE]
