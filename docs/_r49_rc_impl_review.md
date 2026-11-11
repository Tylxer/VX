# R49 RC 装包前独立交叉复核报告（Pro lane）

> 角色：装包前独立交叉复核 lane（Hook 链路 / 判据 / 异步并发复核）
> 范围：**仅 `app/src/main` 本次 diff**（阶段二探针改动）；R48/R49 文档改动与未跟踪报告不计入代码变更
> 约束：全只读复核，不碰 adb/真机/Gradle/装包/kvset/git 写操作；唯一写入文件为本报告
> 裁定口径：PASS / FAIL；每条附 `文件:行号` 或命令输出；不确定处标 `[推断]` / `[需真机]`

## 复核项清单
- **C1 ⭐⭐ 钩子执行顺序**（致命项）：`SnsWriteProbe` 记录点 vs `SnsSqlRewrite` `& -9 → & -1` 改写，谁先谁后 / 探针实际记录到原文还是已改写 SQL / `& -1` 兜底识别能否还原 snsId 区间·userName·命中面。FAIL ⇒ 判「不可以装包」
- C2 归因三态互斥性：seen / matched 语义、开关关闭零干扰
- C3 R42 现网零影响：`SnsSqlRewrite.kt` 零改动、不改 SQL/参数/返回值、callback 异常 try/catch 兜底
- C4 before 内零查库/零重入：无 DB 查询/反射/文件 IO、冷启 NPE、LogX 刷屏
- C5 开关默认关 + 关时零开销：`Keys` 默认 false、arm/disarm、`op=writeprobe` 全只读
- C6 限流/去重/封顶/冷启：账本封顶、去重键含 userName、60s 节流、冷启清零、dump 单行截断
- C7 行级命中判定正确性：hex() 判 BLOB、`createTime<T0` 边界、秒/毫秒、snsId 区间、userName 过滤、防注入、T0 运行时算
- C8 单测与变异真验证：31 例非等价变异、5 组变异各有对应用例变红、`& -1` 兜底是否有单测
- C9 三档边界：进入/返回/复位逐档设计、复位不写成「同返回」、磁盘位不回滚、arm 前不追溯
- C10 回滚路径 + 装包安全性：撤改动步骤、默认关、`handleLoadPackage`/`prepare` 无新增逻辑、崩溃最小排查
- X1 复核 `docs/_r49_rc_impl_report.md` 的 `[DONE] ALL` 自报与磁盘一致性
- X2 独立跑 `git diff --stat -- app/src/main` + `git status --short`，列真实改动清单、是否超出授权 5 源码文件
- X3 装包前必改项清单（P0/P1）

## 二元结论（速览）

**可以装包**。C1（唯一否决项）PASS，R42 零影响，默认关 + 关时零开销，行级判据正确；唯一 FAIL 为 C6 的 60s 时间窗节流缺失（规范偏差、非致命、P1）。完整裁定见文末「C1~C10 逐条汇总表」与「二元结论」节。

[START]

## S0 范围界定与真实改动清单（X2）

**git 根 / 路径陷阱**：`git rev-parse --show-toplevel` → `C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`；Android 源码在 `wxgroup/app/` 子目录。
⇒ 复核指令里的 `git diff -- app/src/main`（不带 `wxgroup/` 前缀）**实测返回空**；正确路径是 `git diff -- wxgroup/app/src/main`。本复核按后者取真实 diff。

`git diff --stat -- wxgroup/app/src/main`（原样输出）：
```
 wxgroup/app/src/main/java/com/lx/wxgroup/data/Keys.kt         | 18 ++++++++++++++++++
 .../main/java/com/lx/wxgroup/entry/EntryReceiver.kt            | 11 +++++++++++
 .../main/java/com/lx/wxgroup/feature/SnsBlockProbe.kt         |  8 +++++++++
 .../main/java/com/lx/wxgroup/feature/SnsGuard.kt              |  7 +++++++
 4 files changed, 44 insertions(+)
```
`git diff --name-status -- wxgroup/app/src/main`：4 行均为 `M`（无 `A`/`D`/`R`）。
`git status --short` 中 `app/src/main` 相关行（原样）：
```
 M wxgroup/app/src/main/java/com/lx/wxgroup/data/Keys.kt
 M wxgroup/app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt
 M wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsBlockProbe.kt
 M wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsGuard.kt
?? wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsWriteProbe.kt
?? wxgroup/app/src/test/java/com/lx/wxgroup/feature/SnsWriteProbeTest.kt
```
`wc -l` 实测：`SnsWriteProbe.kt` 518、`SnsWriteProbeTest.kt` 350、`SnsSqlRewrite.kt` 152。

**真实改动清单（只看 app/src/main）**：
| # | 文件 | 状态 | 变化 |
|---|---|---|---|
| 1 | `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsWriteProbe.kt` | 新建（未跟踪） | +518 |
| 2 | `wxgroup/app/src/main/java/com/lx/wxgroup/data/Keys.kt` | 改 | +18/-0 |
| 3 | `wxgroup/app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt` | 改 | +11/-0 |
| 4 | `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsBlockProbe.kt` | 改 | +8/-0 |
| 5 | `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsGuard.kt` | 改 | +7/-0 |

`app/src/test` 下另有 1 个新建单测 `SnsWriteProbeTest.kt`（+350，不计入 main）。
⛔ `SnsSqlRewrite.kt` **未出现在 `git status --short` 与 `git diff --name-status`** ⇒ 零改动。
`git status --short` 另有 2 个已改文档（`docs/_r48_autoplay_conclusion.md`、`docs/派发审计台账.md`）、一批未跟踪 `?? docs/_r49_*` / `_r50_*` 报告、以及本报告 `?? docs/_r49_rc_impl_review.md`（唯一允许写入）；按硬约束 4 **全部不计入本次代码变更**。

**X2 裁定：PASS** —— main 下 5 个源码文件（4 改 + 1 新建）+ test 下 1 个单测，与授权「5 个源码文件 ±1 单测」完全一致，**无超出授权的源码改动**；既有 4 文件均为纯新增（+44/-0，0 删除）。

[DONE] S0-范围界定与真实diff(X2)

## S1 源码链路定位（只读锚点汇总，供 C1~C10 引用）

**钩子注册**（`SnsGuard.kt`）：
- `:152` `hookAllMethods(cls, "execSQL", sqlGuard)` —— **execSQL 全程只有 1 个 callback**。
- `:158` `hookAllMethods(cls, "updateWithOnConflict", snsUpdateGuard)`（after）、`:163` `... snsBlockGuard)`（before）—— 同方法 2 个 callback，均为 R42 既有。
- `:221-226` `sqlGuard.beforeHookedMethod` = `runCatching { onExecSql(param) }`（全包异常）。
- `:297-308` `snsBlockGuard.beforeHookedMethod`：新增 `:302-305` 探针短路，其后 `:306` 独立 `runCatching { onSnsUpdateBefore(param) }`。
- ⛔ 探针**未新增任何 `XC_MethodHook`**，只在既有 callback 内插入 `if (isArmed()) { runCatching { ... } }`。

**execSQL 原文链（C1 命门）**：
- `SnsGuard.kt:844` `val sql = a[0] as? String`（局部不可变引用）。
- `SnsGuard.kt:861` `onSnsSql(sql, param)`；`:889` 命中时 `applySqlRewrite(param, sql)`；`:891-898` `SnsBlockProbe.noteSql(sql = sql, ...)`。
- `SnsGuard.kt:916` `runCatching { param.args[0] = out }` —— **只替换数组元素，不改局部 `sql`**。
- `SnsBlockProbe.kt:552-568` `noteSql` 的 `sql` 形参纯透传；`:564-567` 探针 `captureExecSql(sql)` 在 `:568` `sqlSns.incrementAndGet()` 之前。
- `SnsWriteProbe.kt:381-399` `captureExecSql(sql)`：`sql = sql`（`:389`）存**完整未截断原文**；`:384` `parseClear9(sql)` 解析结构化字段。

**`SnsSqlRewrite.kt`（152 行，零改动）**：`:45-48` `CLEAR_BIT3_ASSIGN` 正则、`:77-85` `rewrite()` 纯函数（`-9`→`-1`）、`:93-98` `rangeKey()`；**无 import、无 Xposed hook、无 callback 注册** ⇒ 改写只能在调用方 before 内发生。

**只读 SQL 通道**：`SnsGuard.runReadOnlySql`（`SnsGuard.kt:1275-1321`）白名单 `select`/只读 pragma、拒内部 `;`（`:1278-1286`）、`snsDb==null` 回提示（`:1287`）、输出格式 `SQLX r1 <c0> | <c1>`（`:1309`）。与 `SnsWriteProbe.kt:451-452`（`resolveT0`）、`:483-489`（`parseCountRow`）解析一致。

**开关**：`Keys.kt:385`（diff 新增 `const val SNS_WRITE_PROBE = "sns.write_probe"`，KDoc 注明故意不进 `Prefs.ALL`）；`Kv.kt:35-38` `getBool` 仅 `"true"/"1"` 为真 ⇒ 默认 `false`；`SnsWriteProbe.kt:267-271` `@Volatile armed = false`（运行时以此为准，非 KV）。

**全局引用点**（`Grep SnsWriteProbe` in `app/src/main`）：仅 `EntryReceiver.kt:669`、`SnsBlockProbe.kt:564-565`、`SnsGuard.kt:302-303`、`Keys.kt:370`(KDoc) 及 `SnsWriteProbe.kt` 自身。**无** `handleLoadPackage` / `prepare` / `install` / 模块加载入口引用。

[DONE] S1-源码链路定位

## C1 ⭐⭐ 钩子执行顺序 / 探针记录到 `& -9` 还是 `& -1`

**裁定：PASS**（不触发「不可以装包」的否决条件）。

**① 两者并非「两个独立 before callback」，而是同一个 callback 内的函数调用**
- `execSQL` 只注册了 1 个 `XC_MethodHook`：`SnsGuard.kt:152` `hookAllMethods(cls, "execSQL", sqlGuard)`。
- `SnsSqlRewrite.kt` 是纯逻辑 object（`:37`，无 `import`、无 `XC_MethodHook`、无注册代码），改写函数 `rewrite()`（`:77-85`）由 `SnsGuard.applySqlRewrite`（`:907-922`）在 `sqlGuard.beforeHookedMethod → onExecSql → onSnsSql` 链内调用。
- ⇒ 用户担心的「Xposed 同一方法多 callback 谁先谁后」在本链路**不成立**：不存在两个竞争 before。`updateWithOnConflict` 虽有 2 个 callback（`:158`/`:163`），但改写不在这条路上，与 C1 无关。
- 题面把改写定位为「`SnsSqlRewrite.kt:46` 在 before 里改写」属于行号/机制记忆偏差：`:46` 实际是正则 `CLEAR_BIT3_ASSIGN` 定义，真正的写回在 `SnsGuard.kt:916`。

**② 同一 callback 内的先后顺序：改写先、捕获后，但捕获拿到的是原文**
- `SnsGuard.kt:844` `val sql = a[0] as? String` —— 把 `param.args[0]` 的当时值读进**局部不可变引用**。
- `SnsGuard.kt:879-898` `onSnsSql`：`when` 分支 `:889` 先执行 `applySqlRewrite(param, sql)` → `:891` 后执行 `SnsBlockProbe.noteSql(sql = sql, ...)`。
- `SnsGuard.kt:916` 改写动作是 `param.args[0] = out`，**只替换数组第 0 个元素的引用**；Kotlin `String` 不可变，局部变量 `sql` 仍指向原始 `& -9` 字符串（`SnsBlockProbe.kt:562-563` 既有契约亦明确「原文……不能存改写后的」）。
- `SnsBlockProbe.kt:552-568` `noteSql` 形参 `sql` 纯透传，`:565` `captureExecSql(sql)` → `SnsWriteProbe.kt:389` `sql = sql` 存完整原文。
- ⇒ **探针实际记录到的是 `& -9` 原文**，`parseClear9` 的 `CLEAR9_SET`（`SnsWriteProbe.kt:98-101`）可正常识别，`matched` 不会恒 0。

**③ 即便万一记录到 `& -1`，兜底识别完整且能还原命中面（双保险）**
- `SnsWriteProbe.kt:98-101` `CLEAR9_SET = Regex("""...(-9|-1)\b""")`，`:131` `val rewritten = m.value.contains("-1")`；`:114-116` KDoc 明确「必须返回非 null 且 `rewritten=true`，识别成『已改写』而不是『无写事件』」。
- `:132-137` `parseClear9` 从（已改写的）SQL 中仍解析出 `userName`（`USER_NAME` 正则 `:106`）、`lo/hi`（`:102-103`）、`sourceTypes`（`SRC_IN` `:105`），返回 `Clear9(table,userName,sourceTypes,lo,hi,rewritten,rawSql)`。
- `:463-472` `fillHits` 用 `c9.lo/hi/userName` 构造 `HitCond(t0,...)` → `buildHitCountSql` 查只读 count；`:394-395` 账本 `snsIds`/`userName` 也来自 `c9`。
- ⇒ 命中面（snsId 区间 + userName + 白名单）**可完整还原**，行级命中判定无从算起的担忧不成立。
- 单测覆盖：`SnsWriteProbeTest.kt:79-89` `rewritten_minus1_is_recognized_not_absent`、`:146-154` `minus1_with_user_and_range_rewritten`（断言 `rewritten=true` 且 `userName/lo/hi` 均还原）。

**④ 决定性陷阱（库快照差分假阴性）已避开**：探针在 before 直接观测 SQL 原文（`SnsWriteProbe.kt:14-17` KDoc 明确），不靠 `op=sql` 库值差分；`stat()` 档①提示（`:314`）亦写明「已改写 `& -1`，只看 before 原文账本」。

**风险**：`[推断]` Xposed 回调内 `param.args[0] = out` 在改写后微信真正执行的 SQL 是 `& -1`，但账本与判定只依赖局部变量 `sql`，与执行结果无关 —— 该结论依赖「`sql` 是值传递的局部引用」这一 Kotlin 语义，源码层面确定，无需真机即可判定。真机仍需确认进主页确实走 `execSQL`（阶段一已证 6 条），非 `[需真机]` 项。

[DONE] C1-钩子执行顺序

## C2 归因三态互斥性

**裁定：PASS**（带 1 项已诚实登记的口径风险）。

- `SnsWriteProbe.kt:255-258` `verdict(seen, matched)` 为 **`if / else if / else` 互斥三态**：`seen==0`→「无观测机会」；`seen>0 && matched==0`→「挂对了但判据不识别」；`matched>0`→「有真实写事件」。⛔ 未写成并列；单测 `SnsWriteProbeTest.kt:338-343` 专门锁住「`seen==0` 优先，不被 `matched>0` 拉到第三态」（`verdict(0,3)` 仍为「无观测机会」）。
- `seen` 口径：`SnsWriteProbe.kt:282` `record()` 每次无条件 `seen.incrementAndGet()`；`record` 只由 `captureExecSql`（`:385`）/ `captureUpdate`（`:360`）调用，而两者均被调用方 `if (SnsWriteProbe.isArmed())` 短路（`SnsGuard.kt:302`、`SnsBlockProbe.kt:564`）⇒ **seen 只在开关打开后 +1**，且仅对涉及 `SnsInfo` 的写事件（`SnsWriteProbe.kt:383` `sql.contains("SnsInfo")`、`:357` `table != "SnsInfo" return`）。不是「任何 execSQL」全计。
- `matched` 口径：`:283` `parseClear9(ev.sql) != null` 才 +1。
- 开关关闭时：两处接线均只有 `if (isArmed())` 一个 boolean 判断后短路，**完全不干扰**（无解析、无计数、无日志）。

**风险**：`captureUpdate` 构造的 `WriteEvent.sql = null`（`SnsWriteProbe.kt:364`）⇒ UPDATE 通道只贡献 `seen`、不贡献 `matched`。若真机某窗口只发生 `updateWithOnConflict` 而无目标 `execSQL` `& -9`，`seen>0 && matched=0` 会显示「判据不识别」，需结合 `stat()` 的 `execSql=` 计数（`:313`）判读，勿误读成「判据坏了」。实现报告风险 4（`_r49_rc_impl_report.md:231`）已主动登记此口径，非隐瞒 ⇒ 不判 FAIL。

## C3 R42 现网零影响

**裁定：PASS**。

- `SnsSqlRewrite.kt` 零改动：`git status --short` / `git diff --name-status -- wxgroup/app/src/main` 均无此文件（S0）；全文 152 行读毕，`rewrite()`（`:77-85`）逐字保留。
- 既有 4 文件 diff 均为**纯新增 +44/-0**（S0），0 删除、0 改动既有逻辑行：
  - `SnsGuard.kt:299-305` 仅在 `snsBlockGuard.before` 最前面插入探针短路；`:306` 原 `runCatching { onSnsUpdateBefore(param) }` 逐字保留 ⇒ `keep = old | 10` / bit3 写回 / `[已删除]\n` 前缀链路未动。
  - `SnsBlockProbe.kt:560-567` 仅在 `noteSql` 首行、`sqlSns++`（`:568`）之前插入探针短路；原有计数与 `SqlBlock` 分类（`:571-605`）逐字保留。
- 探针不改 SQL / 参数 / 返回值：`captureExecSql`（`SnsWriteProbe.kt:381-399`）只读 `sql`；`captureUpdate`（`:354-374`）只读 `param.args`，`cvKeys/cvSourceType`（`:492-503`）用反射**读** ContentValues 的 `keySet()`/`get()`，不写回；无 `param.result`、无 `param.args[i] =` 赋值（全文件 Grep 无该模式）；`export`（`:410-442`）无 insert/update/delete/execSQL。
- 无绕过/重复触发 R42：`arm/disarm`（`:431-440`）只 `Kv.putBool(KEY,...)` + `setArmed()`，**不调 `SnsGuard.refresh()`、不碰任何 `ANTIREVOKE_*` key**（`SnsWriteProbe.kt:408` KDoc + `:432-439` 实证）。
- ⛔ **callback 异常致后续 callback 不执行 → 破坏 R42 的担忧不成立**：探针未新挂 callback（S1）；两处新增代码都在 `runCatching { }.onFailure { LogX.w }` 内（`SnsGuard.kt:303-304`、`SnsBlockProbe.kt:565-566`），且 `sqlGuard.before`（`:223`）、`snsBlockGuard.before` 的 R42 逻辑（`:306`）各有独立 `runCatching`；探针内部 `cvKeys`/`cvSourceType`/`callerOf` 亦各自 `runCatching`（`:492/:499/:506`）。即便 `SnsWriteProbeCore.record`（`:281`）抛异常，也会被接线处外层 `runCatching` 吞掉，绝不外抛到微信写库路径。
- 无新增对微信库的 insert/update/delete/execSQL：`app/src/main` 引用点仅 4 处（S1），探针唯一「写」是 `Kv.putBool`（模块自有配置库）。

[DONE] C2-C3-归因三态与R42零影响

## C4 before 内零查库 / 零重入

**裁定：PASS**（armed 时有非 DB 的轻量开销，见风险）。

- before 路径 `captureExecSql`（`SnsWriteProbe.kt:381-399`）：`sql.isNullOrBlank`/`contains("SnsInfo")`/`parseClear9`(正则)/`whereClause`/`rangeText`/`record`(内存 `ArrayList`+`HashSet`+`AtomicLong`)。**无 DB 查询、无 `runReadOnlySql`、无文件 IO**。
- `captureUpdate`（`:354-374`）：只读 `param.args` 的 `table/where/cv`；`cvKeys/cvSourceType`（`:492-503`）是 `getMethod("keySet")`/`get("sourceType")` 的**对象反射**（读 ContentValues 键，不是反射查库）；`callerOf`（`:506-517`）用 `Throwable().stackTrace` 取调用者签名。
- 全文件 `runReadOnlySql` 仅出现在导出阶段 `resolveT0`（`:448`）、`fillHits`（`:470`），由 `EntryReceiver.kt:667-671` 的 `io {}` 子线程触发，**不在 before**（`SnsWriteProbe.kt:404-409` KDoc 明确）。`EntryReceiver.kt:663-673` 新增分支用 `io { ... }`，查库不落主线程。
- 冷启 `snsDb` 未捕获：before 路径**完全不引用 `snsDb`**；`runReadOnlySql` 有 `val db = snsDb ?: return listOf("SQLX no sns db yet...")`（`SnsGuard.kt:1287`）保护，`resolveT0`（`:451-452`）取不到 `SQLX r` 行即返回 null，`stat` 打印「跳过行级命中」（`:415-418`）⇒ **无 NPE**，账本 `seen/matched/三态` 仍可见。
- LogX 刷屏：before 路径零 `LogX` 调用（仅接线处 `.onFailure` 异常时 warn，正常路径不打）；日志只在 `op=writeprobe` 导出时由 `EntryReceiver.kt:670` `LogX.i(l)` 打 ⇒ **不会触发 K7 perf 每 250ms / LogX 5000 行环形刷屏**（`SnsWriteProbe.kt:34` 注释）。

**风险**：`[推断]` armed 时每条事件都执行 `Throwable().stackTrace`（`callerOf`，`:506-517`）与两次 `getMethod` 反射（`cvKeys/cvSourceType`），属非零耗时（虽非 DB/IO）。默认关时零开销；armed 且进主页（阶段一每进一次 6 条 `& -9`）量级很小，主线程卡顿风险低，但真机 armed 后建议留意；建议后续把 `callerOf` 改为按需/采样（列入 X3 P1）。

## C5 开关默认关 + 关时零开销

**裁定：PASS**。

- `Keys.kt:385`（diff `@@ -365,6 +365,24 @@` 内）`const val SNS_WRITE_PROBE = "sns.write_probe"`；KDoc（`:370-384`）明确「默认 `false`（`Kv.getBool` 未设置即 false）」「故意不进 `Prefs.ALL`」。`Kv.kt:35-38` 实证 `getBool` 仅 `"true"/"1"` 返回真，未注册 key 走 `Prefs.defaultOf` 空串 ⇒ false。
- 运行时另有一层内存开关 `SnsWriteProbe.kt:267-268` `@Volatile private var armed = false`；`isArmed()`（`:347`）读它，冷启（进程重建）天然清零。
- 关时两处接线**只有 `if (SnsWriteProbe.isArmed())` 一个 boolean 判断 + 分支短路**（`SnsGuard.kt:302-305`、`SnsBlockProbe.kt:564-567`）：无字符串解析（正则在 `capture*` 内部，关时不进）、无对象分配、无日志。
- ⛔ 「开关关着但 hook 仍常驻并做解析」的漏网：**不存在**。常驻 hook（`sqlGuard`/`snsBlockGuard`）是 R42 既有，探针未新增 hook（S1）；关时探针代码路径被短路，解析函数不被调用。
- `arm`/`disarm`（`SnsWriteProbe.kt:431-440`）只 `com.lx.wxgroup.data.Kv.putBool(KEY, true/false)` + `SnsWriteProbeCore.setArmed(...)`：只改模块自有 KV + 进程内 boolean，⛔ 不写微信库、不调 `SnsGuard.refresh()`、不碰 `ANTIREVOKE_*`。
- `op=writeprobe` 全分支只读微信库：
  - `stat`（`:411-422`）/`dump`（`:423-426`）→ `fillHits` → `runReadOnlySql`（`select` 白名单）；
  - `reset`（`:427-430`）→ 纯内存 `SnsWriteProbeCore.reset()`；
  - `arm`/`disarm` → 仅模块 KV；`else`（`:441`）→ 仅 usage 文案。
  - 唯一「写」是模块配置库 `Kv.putBool`，不涉微信库。
- 广播 `op=writeprobe` 在 `EntryReceiver.kt:663-673` 的 `when(op)` 内，与既有 `sql` 分支同风格（`io {}` + 全限定名），无其它副作用。

**风险**：`[推断]` `SnsWriteProbe` object 首次被 `isArmed()` 访问时初始化（`CLEAR9_SET` 等顶层正则、AtomicLong、集合）；该 `if (SnsWriteProbe.isArmed())` 调用**不在** `runCatching` 内（`SnsGuard.kt:302`、`SnsBlockProbe.kt:564`）。若类初始化期正则编译异常（编译期/单测已覆盖，概率极低）会外抛。建议把 `isArmed()` 一并纳入 `runCatching` 或确认无初始化期异常（X3 P1）。另报告风险 6（`_r49_rc_impl_report.md:233`）：KV 持久 `true` 但冷启后 `memArmed=false`，运行时以内存为准 ⇒ 真机冷启后须重新 `arm`，属设计而非缺陷。

[DONE] C4-C5-before零查库与开关零开销

## C6 限流 / 去重 / 封顶 / 冷启

**裁定：FAIL（局部、非致命）** —— 「60s 节流」未实现；其余子项 PASS。列入 X3 **P1**，不触发 P0 否决。

- 账本封顶：`SnsWriteProbe.kt:35` `RING=400`；`record`（`:286-295`）超 400 时 `events.removeAt(0)` 并同步 `dedup.remove(old.dedupKey)`；单测 `SnsWriteProbeTest.kt:309-315` 验证 450 条后剩 400、最老 50 条被淘汰。**PASS**。
- 去重键：`WriteEvent.dedupKey`（`:78-79`）`= kind|sql|userName`，**含 `userName`**；单测 `:291-296`「不同 userName 的同形 SQL 必须都入账」、`:299-306`「同 key 折叠但 `seen`/`matched` 不去重」。**PASS**。
- ⛔ **60s 节流：FAIL**。`Grep 60_000|60000|节流|throttle|elapsed|cooldown|interval` on `SnsWriteProbe.kt` → `No matches found`；全文 518 行**无任何时间窗节流**（无 `STAT_GAP_MS`/`HIT_LOG_GAP_MS` 之类）。而设计文档 `_r49_rc_probe_design.md:60`（`STAT_GAP_MS=60s`）、`:75`（「探针必须自带限流：同 snsId 5s 只记一条、stat 60s 一条、SQL 原文去重上限 60、计数不去重」）、`:324`（「A1 汇总 60s 一条」）明确要求。实现以「**永久 dedup + RING 环形封顶 + 导出由人工广播触发（无自动轮询）**」替代时间窗。
  - **风险实评（不夸大）**：进主页每进一次 6 条 `& -9`（阶段一），同形 SQL 被永久去重；反复进主页若 `snsId` 区间变化会新增条目，但环形封顶 400 内存有界；before 零日志、日志只在手动 `op=writeprobe` 导出时打 ⇒ **无 K7/LogX 刷屏、无 OOM、不导致判据失效**。属规范偏差而非功能缺陷，故列 P1 而非 P0。
- 冷启清零：`Core` 的 `events`/`dedup`/`seen`/`matched`/`armed` 均为进程内实例字段（`SnsWriteProbe.kt:261-268`），冷启天然清零；`stat()` 档②（`:315`）写明「冷启账本进程内结构⇒全清零(预期,非bug),须重新 arm」；单测 `:329-335` 验证默认关 + 开关往返。符合三档边界预期。**PASS**。
- `dump` 单行截断：`WriteEvent.line()`（`:81-93`）展示层 `sql.take(200)`、`where.take(120)`，`dump()`（`:321-328`）封顶 `MAX_DUMP=60` 行。**判据不残缺**：`ev.sql` 存的是 `captureExecSql` 的**完整未截断原文**（`:389`），`fillHits` 用 `parseClear9(ev.sql)`（`:463`）解析完整 SQL，并单独 append 结构化 `snsIds`/`userName`（`:88-89`）；`hitOlder3d` 靠 `Clear9` 字段而非 `line()` 文本 ⇒ 截断只影响人眼可读性。**PASS**。

## C7 行级命中判定的正确性

**裁定：PASS**。

- `buildHitCountSql`（`SnsWriteProbe.kt:237-245`）：`and hex(content)<>'' and hex(attrBuf)<>''` 判 BLOB，**禁用 `cast(... as text)`**（`:234` KDoc）；单测 `:253-263` 断言 `hex(content)`/`hex(attrBuf)` 存在且 `!contains("cast(")`。
- 边界：`createTime < ` 严格（`:239`），`isOlder3dSec`（`:200-203`）`createTime < 1e9 或 >= 1e10` 直接 false ⇒ 10 位秒 `[1e9,1e10)`、13 位毫秒（`>=1e12`）排除；严格 `< t0Sec` ⇒ **等于 T0 不算三天外**（单测 `:165-168`、`:178-189`）。
- snsId 区间：`inOpenRange`（`:206-210`）严格开区间（`snsId <= lo`/`>= hi` 排除），`null` 边界不限；SQL 侧 `snsId > lo` / `< hi`（`:240-241`）与真机 `snsId > A [AND < B]` 一致（单测 `:192-205`、`:278-286`）。
- userName 过滤：`userMatches`（`:213-214`）target 空不限、否则相等；`fillHits`（`:465-468`）userName 解析失败**保守跳过**（不加条件会查全表高估），正确；`buildHitCountSql` 仅在非空时拼 `userName=`（`:242`）。
- **`T0` 运行时算**：`resolveT0()`（`:447-454`）`select max(createTime) from SnsInfo` → `mx - 3*86400`（10 位秒），**非写死常量**（单测里的 `1793884838L` 只是测试固定值）；`snsDb` 未捕获时返回 null 并跳过行级命中（`:415-418`）。**PASS**（写死常量才 FAIL）。
- 防注入/转义：`sqlQuote`（`:229`）`'`→`''`；`T0/lo/hi` 为 `Long` 类型安全直接拼；`userName` 走 `sqlQuote`（`:242`）；`runReadOnlySql` 的 `select` 白名单 + 拒内部 `;` fail-closed（`SnsGuard.kt:1278-1286`）。单测 `:266-269` 验证 `x' OR 1=1 --` → `userName='x'' OR 1=1 --'`；`:278-286` 验证无 `;`、无 `<=`/`>=`。
- 导出链路格式一致性：`runReadOnlySql` 输出 `SQLX r1 <c0> | <c1>`（`SnsGuard.kt:1309`），`resolveT0`（`:451-452`）与 `parseCountRow`（`:483-489`）的 `substringAfter("SQLX r1 ")`/`split("|")` 解析匹配，`maxRows=1` 只取 `r1` ⇒ 链路可用。

**风险**：`[推断]` `sqlQuote` 未处理 `;`，若真机 `userName` 含 `;`，`runReadOnlySql` 会 fail-closed 拒绝该条查询（该事件跳过命中，**不会写库**）；真实 wxid 不含 `;`，风险低（X3 P1）。另 `hitOlder3d` 纯函数（`:223-224`）是导出 SQL 的「等价镜像」，一致性靠单测锁住、**未做真机对账**（报告风险 3，`_r49_rc_impl_report.md:230`）。

[DONE] C6-C7-限流与行级判定

## C8 单测与变异是真验证吗

**裁定：PASS**（带 1 项盲区登记）。

- `SnsWriteProbeTest.kt` 350 行，逐个数 `@Test` **确为 31 例**（`:64/78/91/96/102/110/117/128/135/145/156` = parseClear9 11；`:164/170/177/184/191/200/207/215/223/241` = 判据 10；`:252/265/271/277` = SQL 构造 4；`:290/298/308/317/328/337` = 账本/三态 6；合计 31）。`@Before`（`:38-43`）`core.reset()` + `setArmed(false)` 做用例隔离。
- **非等价变异可杀性逐组核对**（对照报告 S5 表 `_r49_rc_impl_report.md:162-170`，源码级独立复算）：
  - M1 `isOlder3dSec` `<`→`<=`（`:202`）：`createTime_equal_T0_is_not_older_3d`（`:165-168`）、`hit_count_mixed_rows`（`:231` 的 `==T0` 行 ⇒ 计数 2→3）、`rowHit_combines_three_predicates`（`:245` 的 `assertFalse` 反转为失败）⇒ 3 红，与报告一致。
  - M2 `CLEAR9_SET` 白名单恒真（`:98-101`）：`bit1_only_minus3_is_not_target`（`:92-94`）、`plain_sourceType_write_is_not_target`（`:97-100`）⇒ 2 红；`where_only_expression_is_not_source_write`（`:129-133`）因 `parseClear9` 截顶层 WHERE 之前的 SET 段（`:126-128`）仍为 null，不假阳性；非 SnsInfo 表仍 null ⇒ 与报告一致。
  - M3 `inOpenRange` `>`/`<` 互换（`:206-210`）：`range_open_boundaries`（`:192-198`）、`range_null_bounds_unbounded`（`:201-205`）、`hit_count_mixed_rows`（`:224-239`）、`rowHit_combines_three_predicates`（`:244` 的 `assertTrue` 反转）⇒ 4 红，与报告一致。
  - M4 `dedupKey` 去 `userName`（`:79`）：`dedup_key_includes_username`（`:291-296`）⇒ 1 红。
  - M5 `hitOlder3d` 恒 0（`:223-224`）：`hit_count_mixed_rows`（`:238` 断言 `2L`）⇒ 1 红。
  - ⇒ 5 组变异**各自都有对应用例变红**，且每组都打在真正决定结果的行、配了边界用例（等于 T0、`& -3`/加位写、开区间端点、同形不同 userName、混合行计数），**无「等价变异假验证」**。
- ⛔ **`& -1` 兜底有单测覆盖**：`rewritten_minus1_is_recognized_not_absent`（`:78-89`，断言 `rewritten=true` 而非 null）、`minus1_with_user_and_range_rewritten`（`:145-154`，断言 `userName/lo/hi` 均还原）⇒ 用户要求的「若没覆盖标风险」**不适用**，此项反而 PASS（与 C1③ 互证）。
- **盲区（如实登记）**：31 例全部只测 `SnsWriteProbeCore` 纯函数/内存账本；**桥接层 `SnsWriteProbe`（`captureUpdate`/`captureExecSql`/`export`/`resolveT0`/`fillHits`）零单测**（依赖 Android，纯 JVM classpath 无 xposed jar，见报告 S0 `:49`）。特别是 `resolveT0`/`parseCountRow` 与 `runReadOnlySql` 的 `SQLX r1` 格式耦合、**改写前原文接线**均未在单测覆盖，只由本复核做源码级核对（S1/C1/C7）⇒ `[需真机]` 导出链路首次实跑需对账。
- 自报「全量 818 单测 0 失败、5 组变异全红、`assembleDebug`+`testDebugUnitTest` SUCCESSFUL」：本复核硬约束 1 **禁止跑 Gradle**，故**未独立复跑**，仅核对用例矩阵与变异映射的源码级自洽性；该自报不计入 C8 的 PASS 依据（见 X1）。

## C9 三档边界

**裁定：PASS**。

三档设计说明分布在 `SnsWriteProbeCore.stat()`（`SnsWriteProbe.kt:302-318` 的 3 行提示）、实现报告 S1 W5 表（`_r49_rc_impl_report.md:84-90`）、报告装包复核要点（`:245`）：
- **① 进入**（`stat` `:314` / 报告 `:88`）：「刚进好友主页那一刻」触发 6 条 `& -9` raw SQL（WHERE=userName + 64 个含 bit3 白名单 + snsId 区间、无 createTime 窗口）⇒ `seen/matched` 增长；⛔ 明写「进主页前后 `op=sql` 库快照差分**必然假阴性**（已改写 `& -1`），只看 before 原文账本」。✓
- **② 返回**（`stat` `:315` / 报告 `:89`）：用户列的「切页 / 下拉刷新 / 后台同步 / 冷启」在报告表 `:89` 逐项齐备；账本继续追加不重置；冷启进程内结构全清零（预期非 bug），须重新 `arm`。✓
- **③ 复位**（`stat` `:316` / 报告 `:90`）：用户列的「关探针开关、同时关 `antirevoke.sns` + `sns_block` **两个**开关、清缓存、对方改回全部可见」在报告表 `:90` **逐项齐备**；账本停止增长（明细保留供末次 dump）。
  - ⛔ **没有写成「同返回」**：`stat` `:316` 结尾明写「⛔ 复位≠返回」，报告 `:90` 亦写「⛔ **复位 ≠ 返回**：返回只是切页/刷新（账本继续），复位是开关/缓存/对方设置变更且不回滚磁盘位」，两者语义明确区分。✓
  - 「已写进微信库的位是**磁盘状态，不随开关/冷启回滚**」：报告 `:90` 明写；`stat` `:316` 写「磁盘已写的位不回滚」。✓
  - 「`arm` 之前的写事件不追溯」：报告 `:90` 明写；`stat` `:316` 写「arm 前不追溯」。✓

**风险**：`stat()` 的三档提示是简版（档③未列「关两个 R42 开关/清缓存/对方改回全部可见」），完整设计说明在报告 S1 表；真机人员若只看 `stat` 输出可能漏读复位档细节 ⇒ 建议按报告 S1 表判读（非 FAIL）。

[DONE] C8-C9-单测变异与三档边界

## C10 回滚路径 + 装包安全性

**裁定：PASS**。

- **回滚所需动作**（基于 S0 真实清单）：
  1. 删 2 个新建文件：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsWriteProbe.kt`、`wxgroup/app/src/test/java/com/lx/wxgroup/feature/SnsWriteProbeTest.kt`（均为未跟踪，`git clean`/直接删即可，不涉及 revert）。
  2. revert 4 个已改文件各自的新增行：`data/Keys.kt`（+18）、`entry/EntryReceiver.kt`（+11，即移除 `op=writeprobe` 分支）、`feature/SnsBlockProbe.kt`（+8）、`feature/SnsGuard.kt`（+7）。
  3. `SnsSqlRewrite.kt` **零改动，无需任何操作**；`docs` 报告可留可删。
  4. 重新 `assembleDebug` 并装包（回滚后验证）。因 4 文件都是 +44/-0 纯新增、无删除既有行，revert 无冲突风险。
- **开关默认关 ⇒ 装上去即使探针有 bug 也不影响微信现网**：`Core.armed=false`（`:268`）+ `Kv.getBool` 默认 false（S1）；关时两处接线只有 `if (isArmed())` 一个 boolean 判断（C5），探针解析/账本/查库全不执行。`SnsWriteProbe` 是 Kotlin `object`，**懒加载**：首次 `isArmed()` 才初始化（正则编译 + 空集合 + AtomicLong），无 DB/网络/文件/反射微信库副作用。`Keys.kt` 只加编译期 `const val`；`EntryReceiver.kt` 只在 `when(op)` 加分支（广播到达才执行）。
- ⛔ **模块加载瞬间无新增逻辑（最高风险项不存在）**：`Grep SnsWriteProbe` in `app/src/main`（S1）引用点仅 4 处，**无** `handleLoadPackage` / `prepare` / `install` / `IXposedHookLoadPackage` / `Application.onCreate` 引用；`Keys.kt` 无 `init{}`；`SnsWriteProbe.kt` 两个 `object` 无 `init{}`。⇒ 装包后微信启动路径零新增代码。
- **残留风险**：`[推断]` `SnsWriteProbe` 首次初始化的 `isArmed()` 调用不在 `runCatching` 内（C5 风险），若类初始化抛异常会外抛到 before；概率极低（正则编译期确定、单测已加载 Core）。列 X3 P1。

**装包后若微信崩溃：最小排查与回滚步骤**
1. **先判相关性**：探针默认关，未发 `--es op writeprobe --es sub arm` 前其代码路径完全短路 ⇒ 未 arm 就崩溃，基本与本次 diff 无关，先查 R42 既有链路/基线。
2. **即时关探针**（不重装）：`adb shell am broadcast ... --es op writeprobe --es sub disarm`（只改模块自有 KV + 内存 `armed`），观察崩溃是否停止；这一步即可让所有探针代码路径短路。
3. **看日志**：logcat 过滤 `WP ` / `WP captureExecSql err` / `WP captureUpdate err`（`SnsBlockProbe.kt:566`、`SnsGuard.kt:304` 的 `.onFailure` warn）/ `SNS execSQL hook err` / `SNS block hook err`；若探针异常已被 `runCatching` 吞掉，只会留 warn、不会中断 R42。
4. **若仍崩**：说明与探针无关（R42 拦截链本身未改），按基线排查；不要卸装常驻 R42 开关。
5. **彻底回滚**：删 2 新建 + revert 4 文件 + 重新编译装包；`SnsSqlRewrite.kt` 不用动。
6. ⛔ **库值不回滚**：已写进微信库的位是磁盘状态，回滚代码/关开关/冷启都不会撤销（`_r49_rc_impl_report.md:90`）；若真机上观察到库位变化，需按既有 R42 流程单独处理，别误当探针残留。

[DONE] C10-回滚路径与装包安全性

## X1 复核实现报告 `[DONE] ALL` 段自报与磁盘一致性

**裁定：PASS（自报与磁盘一致，无夸大）**。逐条比对 `_r49_rc_impl_report.md:192-255`：

| 自报内容 | 磁盘实测 | 一致? |
|---|---|---|
| `SnsWriteProbe.kt` +518、`SnsWriteProbeTest.kt` +350（`:198-199`） | `wc -l` 518 / 350 | ✓ |
| Keys +18 / EntryReceiver +11 / SnsBlockProbe +8 / SnsGuard +7（`:200-203`） | `git diff --stat` 4 行 18/11/8/7 | ✓ |
| 既有 4 源码合计 **+44/-0**、纯新增 0 删除（`:206`） | 18+11+8+7=44；`name-status` 全 `M` 无 `D`；diff 正文仅 `+` 行 | ✓ |
| 2 个新文件 868 行（`:206`） | 518+350=868 | ✓ |
| **`SnsSqlRewrite.kt` 零改动**（`:207`） | `git status --short` / `git diff --name-status` 均无该文件（S0） | ✓ |
| R42 写时拦截/bit3 写回/`[已删除]\n` 前缀零改动（`:207`） | 4 文件 diff 均为纯新增、既有逻辑行逐字保留（C3） | ✓ |
| `git diff --stat` 输出含 `docs/_r48_autoplay_conclusion.md` +13、`派发审计台账` +62，合计 119 insertions（`:211-219`） | `git status --short` 中这两个 docs 仍为 `M`；报告 `:224` 明确声明「**非本 lane 改动**」 | ✓（诚实隔离，未把文档算进本 lane） |
| 31 用例（`:150`）、5 组变异全红（`:160-170`） | 实测 31 个 `@Test`；变异映射源码级复算自洽（C8） | ✓（映射），未独立复跑 |
| 全量 818 单测 0 失败 / `assembleDebug`+`testDebugUnitTest` SUCCESSFUL（`:158,180-186`） | 本 lane 硬约束**禁止 Gradle**，无法独立复跑 | ⚠️ 未验证（不计入夸大判定） |
| 未 `git add`/commit、未装包（`:188`） | `git status --short` 显示 4 `M` + 2 `??` 均未暂存，与声明一致 | ✓（无反证） |

**非夸大的行号说明**：报告 S0（`:24-25`）记 `SnsGuard.kt` 1638 / `EntryReceiver.kt` 960 / `Keys.kt` 580 / `SnsBlockProbe.kt` 828 行，我实测改后为 1645 / 971 / 598 / 836 —— 差值恰为 +7/+11/+18/+8，即 S0 记录的是**改动前基线**，S3/S4 用改后行号（`:302`、`:564`）并已标注 ⇒ 属现状核对，不是「声称零改动但 diff 里其实改了」那类夸大。
**唯一描述性偏差**（与 C1① 一致）：报告多处把改写点写作「`SnsSqlRewrite.kt:46`」（如 `:88`、`:242`），实际 `:46` 是正则定义、写回在 `SnsGuard.kt:916`；属行号记忆偏差，不影响「库快照差分假阴性」这一实质结论，且报告 `:129`、`:242` 已正确写出「`applySqlRewrite` 只改 `param.args[0]`」的机制。

[DONE] X1-自报一致性

## X2 结论（已在 S0 落盘）

`git diff --stat -- app/src/main`（无前缀）返回空；真实路径 `wxgroup/app/src/main` 下 **5 个源码文件**（4 改 + 1 新建）+ `app/src/test` 下 **1 个单测**，与授权「5 个源码文件 ±1 单测」一致，**无超出授权**。`SnsSqlRewrite.kt` 零改动。文档改动与未跟踪报告按硬约束 4 排除。

## X3 装包前必改项清单（P0 / P1）

**P0（阻断装包）：无。**
唯一否决项 C1（用户明确「这一条 FAIL 就必须判不可以装包」）已 PASS；R42 零影响、默认关、模块加载无新增逻辑、行级判据均 PASS。

**P1（建议修/书面豁免，不阻断本轮装包、不导致真机白跑）**：
1. **C6 · 补时间窗节流**：`SnsWriteProbe.kt` 全文无 60s/5s 节流（Grep `No matches found`），与设计文档 `_r49_rc_probe_design.md:60/75/324` 的「同 snsId 5s 只记一条、stat 60s 一条」规范有偏差。当前替代机制（永久 `dedupKey` 去重 + `RING=400` 环形封顶 + 导出由人工广播触发无自动轮询 + before 零日志）已保证内存有界、无刷屏 ⇒ 建议补节流或书面豁免。
2. **C4/C10 · armed 热路径开销与初始化兜底**：`callerOf()` 的 `Throwable().stackTrace`（`SnsWriteProbe.kt:506-517`）与 `cvKeys/cvSourceType` 反射（`:492-503`）在 armed 时每条事件执行；建议按需/采样。另接线处 `if (SnsWriteProbe.isArmed())`（`SnsGuard.kt:302`、`SnsBlockProbe.kt:564`）**不在 `runCatching` 内**，建议把 `isArmed()` 一并纳入或确认 object 初始化无异常（概率极低）。
3. **C7 · `sqlQuote` 未处理 `;`**（`SnsWriteProbe.kt:229`）：极端 `userName` 含 `;` 时 `runReadOnlySql` 会 fail-closed 拒绝该条命中查询（该事件跳过、不写库）。建议一并过滤/转义。
4. **C8 盲区 · 桥接层导出链路无单测**：`resolveT0`/`parseCountRow` ↔ `runReadOnlySql` 的 `SQLX r1` 格式耦合、改写前原文接线均无单测，仅本复核做了源码级核对 ⇒ `[需真机]` 首次 `stat` 时与 `op=sql` 对账一次。
5. **真机操作前提（非代码改动）**：先 `--es op writeprobe --es sub arm` → 让微信刷一次朋友圈开库（`snsDb`，否则 `resolveT0()` 返回 null、行级命中跳过但账本仍可见）→ 进好友主页 → `sub=stat` 看 `seen/matched/verdict/hitOlder3dTotal` → `sub=dump` → 取完证 `sub=disarm`。冷启后 KV 持久 `true` 但 `memArmed=false`，**须重新 arm**（报告 `:233/243`）。

## C1~C10 逐条 PASS/FAIL 汇总表

| 项 | 裁定 | 关键锚点 | 风险 |
|---|---|---|---|
| **C1 ⭐⭐ 钩子顺序** | **PASS** | `SnsGuard.kt:152`(execSQL 仅 1 callback)、`:844`(局部 `sql`)、`:889→:891`(改写先/捕获后)、`:916`(只改 `param.args[0]`)、`SnsBlockProbe.kt:562-568`、`SnsWriteProbe.kt:98-101/131-137`(`-1` 兜底) | 记录到 `& -9` 原文；`& -1` 兜底能还原 userName/lo/hi/白名单，有单测 `Test:79-89/146-154` ⇒ 不触发否决 |
| C2 三态互斥 | PASS | `SnsWriteProbe.kt:255-258`、`Test:338-343` | UPDATE 通道只计 seen 不计 matched，须结合 `execSql=` 判读（报告已登记） |
| C3 R42 零影响 | PASS | S0 零改动清单；`SnsGuard.kt:299-306`、`SnsBlockProbe.kt:560-568`、`SnsWriteProbe.kt:354-399/431-440` | 无新增 callback；异常被双层 `runCatching` 吞掉 |
| C4 before 零查库 | PASS | `SnsWriteProbe.kt:381-399/354-374/447-480`、`SnsGuard.kt:1275-1287` | armed 时 `Throwable().stackTrace` 非零开销（P1） |
| C5 默认关/零开销 | PASS | `Keys.kt:385`、`Kv.kt:35-38`、`SnsWriteProbe.kt:267-271/431-440`、`SnsGuard.kt:302`、`SnsBlockProbe.kt:564` | `isArmed()` 初始化不在 runCatching（P1） |
| C6 限流/去重/封顶/冷启 | **FAIL（局部）** | `SnsWriteProbe.kt:35/79/281-295/302-328`；Grep 60s 节流 `No matches found`；设计文档 `:75` | 仅 60s/5s 时间窗节流缺失；封顶/去重/冷启/dump 判据均 PASS；非致命、列 P1 |
| C7 行级命中判据 | PASS | `SnsWriteProbe.kt:200-245/447-480`、`SnsGuard.kt:1275-1309`、`Test:165-168/253-286` | `;` 未转义（fail-closed 兜底，P1）；纯函数与 SQL 等价性未真机对账 |
| C8 单测/变异 | PASS | `SnsWriteProbeTest.kt` 31 例；M1~M5 各有对应用例变红；`& -1` 兜底有 `:79-89/:146-154` | 桥接层零单测（P1）；818/编译自报未独立复跑（禁止 Gradle） |
| C9 三档边界 | PASS | `SnsWriteProbe.kt:314-316`、`_r49_rc_impl_report.md:84-90/245` | `stat()` 是简版，完整复位档在报告 S1 表 |
| C10 回滚/装包安全 | PASS | S1 全局引用点仅 4 处；无 `handleLoadPackage`/`prepare` 引用；S0 回滚清单 | object 初始化兜底（P1） |
| X1 自报一致性 | PASS | `_r49_rc_impl_report.md:192-255` 逐条比对 | 编译/单测自报超出只读权限未复跑；`SnsSqlRewrite.kt:46` 行号记忆偏差 |
| X2 真实改动清单 | PASS | `git diff --stat/name-status`、`git status --short`（S0） | `git diff -- app/src/main` 无前缀返回空，须用 `wxgroup/app/src/main` |
| X3 必改项 | **P0 无** | 见上 | 5 项 P1，均不阻断装包 |

## 二元结论

# 可以装包

- **C1（唯一否决项）PASS**：`execSQL` 全程只有 1 个 `sqlGuard` callback，改写与捕获在同一 callback 内；改写只动 `param.args[0]`（`SnsGuard.kt:916`），探针经 `noteSql(sql = sql)` 拿到的是**改写前 `& -9` 原文**（局部不可变引用，`SnsGuard.kt:844`）。即便万一拿到 `& -1`，`parseClear9` 的 `(-9|-1)` 兜底识别 + `Clear9` 还原 userName/lo/hi/白名单（`SnsWriteProbe.kt:98-137`）仍可算命中面，且有 2 个单测锁住 ⇒ **不会出现 `matched` 恒 0 的假阴性**。
- **R42 零影响（C3 PASS）**：`SnsSqlRewrite.kt` 零改动；4 文件 +44/-0 纯新增；探针不改 SQL/CV/返回值、不新增 callback、异常被 `runCatching` 吞掉、arm/disarm 只写模块 KV。
- **默认关 + 关时零开销（C5 PASS）、模块加载无新增逻辑（C10 PASS）** ⇒ 装上去即使探针有 bug 也不影响微信现网。
- **行级判据（C7 PASS）**：`hex()` 判 BLOB、`createTime < T0` 严格、10 位秒、`T0` 运行时算、userName 转义、导出格式与 `runReadOnlySql` 匹配。
- **唯一 FAIL 是 C6 的 60s 时间窗节流缺失**，属设计文档规范偏差；替代机制（永久去重 + RING 封顶 + 手动导出 + before 零日志）已保证有界、无刷屏、不使判据失效 ⇒ 列 P1，**不阻断本轮装包**。

[DONE] X3-必改项与逐条汇总及二元结论

[DONE] ALL
