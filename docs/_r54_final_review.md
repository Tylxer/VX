# R54 分组条仿微X · 装真机前最终独立验证结论

## ① verdict

**可装包。**

- 上一条 P1（wcdb 事务失败不回滚、半行照样提交且不自愈）**已修复**：A~F 六项逐条通过；并由编译产物独立佐证——源码 `GroupConvStore.kt` mtime `2026-11-12 00:14:01 +0800` < `GroupConvStore.class` mtime `00:15:41 +0800`，`javap -p` 显示 `execLog` 返回 `java.lang.Throwable`、`inTransaction` 的 block 为 `Function1<? super java.util.List<java.lang.Throwable>, kotlin.Unit>`，即修复后的签名确已进入字节码，不是陈旧 class 冒充。
- 独立构建：`GRADLE_EXIT=0`、`OK (919 tests)`、`JUNIT_EXIT=0`，且严格按「先编译、后 junit」顺序（基线 919 吻合）。
- P2 候选 G（`ConvFilter.kt:1799` 去掉 `gconvEnabled` 前置）也已改到位；H（默认 OFF 零生效）、I（红线）通过。
- 故 ④ 的 P0 / P1 清单均为 **0 条**，无「需先修」项。

判定边界（避免把静态结论当功能结论）：本 verdict 证明的是「源码逻辑与编译产物正确、无回归」。wcdb 事务三方法反射是否拿得到、`begin`/`end` 后 SQLite 是否真回滚、ON→OFF 热切换时宿主是否重设 per-row listener，均为静态判不了的宿主行为，一律「需真机验证」；上机须按 `op=... FAIL` 日志 + `stat` 两表成对执行纪律（见 ④）。这些不构成装包阻塞。

## ② A~I 逐条结论 + 证据行号

### A · execLog 返回值与日志格式逐字核对

**通过（返回值改造到位；「逐字未变」受限于无 git 历史，见末尾如实标注）。**

- 返回值：`:440` 签名 `private fun execLog(...): Throwable?`。`:441` `val e = runCatching { exec(db, sql) }.exceptionOrNull()`；`:442-444` 成功分支 `say(out, "op=$op user=$user OK")` + **`return null`**；`:445-448` 失败分支 `say(out, "op=$op user=$user FAIL ${e.javaClass.simpleName}: ${msgOf(e)}")` + **`return e`**。⇒ 成功 `null` / 失败 `e`，与要求一致。
- `GCONVSTORE ` 前缀：`TAG = "GCONVSTORE"`（`:50`）+ `say()`（`:512-514` `out.add("$TAG $msg")`）给**每行**加前缀；`postSync`/`postClean`/`postStat` 的失败行（`:105`/`:116`/`:127`）手写同前缀。验收脚本 `tools/_r54_gconv.py:93` 的 `tag="GCONVSTORE"` / `dev.py q GCONVSTORE` 过滤链不受影响。
- 日志格式：`OK` 行以 `op=` 开头、含 `OK`；`FAIL` 行以 `op=` 开头、含 `FAIL` 关键字与解包异常原文（`msgOf` `:452-456`）。相对修复前（`_r54_fix_review.md:52` 记录为 `say(out, "op=.. FAIL ..")`、`:430`），`execLog` 函数体只新增了 `:444`/`:447` 两个 `return`，`say()` 的字符串字面量与前缀逻辑未改动 ⇒ 上机纪律 `dev.py q GCONVSTORE` → `op=... FAIL`（`_r54_fix_review.md:160`、`_r54_gconv_design.md:582`）的匹配口径不受影响。
- ⚠️ **「逐字未变」不能用 diff 证明**：`GroupConvStore.kt` 是 `??` 未跟踪文件（`git status --short`），无 git 历史；现有文档对修复前 FAIL 行的记录是 `op=.. FAIL ..` 简写，无完整字面量。可确认的是四个关键元素（`GCONVSTORE ` 前缀、`op=`、`OK`、`FAIL`）全部在位且判定口径不变；精确逐字比对属「需真机验证」，不硬下结论。

### B · inTransaction 结构

**通过，五项全中。**

- **block 签名**：`:400` `private fun inTransaction(db: Any, out: MutableList<String>, block: (MutableList<Throwable>) -> Unit)`；javap 亦证 `private final void inTransaction(Object, List<String>, Function1<? super List<Throwable>, Unit>)` ⇒ 确为 `(MutableList<Throwable>) -> Unit`（修复前 block 无参）。
- **先 FATAL 再 throw**：`:405` `val failures = ArrayList<Throwable>()`；`:420` `block(failures)` 之后 `:421-424` `if (failures.isNotEmpty()) { say(out, "FATAL 事务内 ${failures.size} 条 SQL 失败，整段回滚（跳过 setTransactionSuccessful）"); throw failures.first() }` ⇒ `say` 在 `throw` 之前，顺序正确。
- **`setTransactionSuccessful` 只在全成功分支**：`:425` `success.invoke(db)` 位于该 `if` 块**之后**，而 `if` 分支必 `throw` ⇒ 只有 `failures` 为空（全成功）才执行到 `:425`。修复前无条件调用的漏洞已消除。
- **finally 保证 endTransaction**：`:426-433` `finally { if (begun) runCatching { end.invoke(db) }.onFailure { say(out, "WARN endTransaction 失败 ...") } }`。成功路径 `:425` 标记成功后由 `end` 提交；失败路径 `throw` 后由 `end` 回滚；`end` 自身失败只打 WARN，不覆盖原异常 ⇒ `endTransaction` 必被调用。
- **begun 标志正确**：`:416` `var begun = false`；`:418` `begin.invoke(db)`；`:419` `begun = true`。`begin.invoke` 抛异常时 `:419` 不执行、`begun` 保持 false，finally 的 `if (begun)` 为 false ⇒ **不调 `endTransaction`**（未 begin 不该 end），正确。
- 背景复核（半行不自愈）仍成立且注释已同步修正：`readExisting()` `:305-310` 取两表并集、缺侧补空（`convMap[u] ?: (0L to 0L)`、`ctMap[u] ?: ""`）；`GroupConvPlan.plan()` `:72-81` 对 `want ∩ have` 只产 `Update`；`repairSql` `:114-116` / `renameSql` `:118-120` 都是 `update …`、对缺失行影响 0 行 ⇒ 缺失侧补不回。KDoc `:83-88` 已把旧的「下一次 due 自然补齐失败的行」改为「半行不会被下一次 sync 自愈…须 `op=gcs sub=clean` 全删重建；事务回滚用于阻止半行产生」，与事实一致。

### C · 降级分支

**通过（调 block、失败即 throw，不静默跳过）；但须澄清降级路径不具原子性。**

- `:406` `if (begin == null || success == null || end == null)` 命中后：`:407-408` 先打 `WARN 事务方法(begin/setTransactionSuccessful/end)反射失败，降级为无事务直写（单线程串行仍保证我方无并发）`；`:409` **`block(failures)`** ✓；`:410-413` `if (failures.isNotEmpty())` 时先打 `WARN 无事务降级下 ${failures.size} 条 SQL 失败，半行可能残留，需 op=gcs sub=clean 全删重建` 再 **`throw failures.first()`** ✓；全成功则 `:414` `return`。⇒ 与主路径同口径，不是「只打日志不抛」。
- ⚠️ **语义澄清（静态可判事实，非待验证推测）**：降级路径没有事务包裹，`throw` 的作用是让外层 `getOrElse` 打 `FATAL` 并停止后续 SQL，**此前已逐条提交的 SQL 不会回滚**。`WARN`（`:411`）与 KDoc（`:397-398`「只是少了失败回滚」）已明说「半行可能残留」。⇒ 真机日志一旦出现「WARN 事务方法…反射失败」，即便随后有 `FATAL`，也**不得**按「整段已回滚」处理，必须按 ④ 的上机纪律 `clean` + `sync` 重建。把「失败即 throw」等同于「失败即回滚」是误读。

互斥说明：主路径（B）是「有事务 ⇒ throw 后 `end` 回滚」；降级路径（C）是「无事务 ⇒ throw 后不回滚」。二者是互斥分支，不可并列成同一句「失败都会回滚」。

### D · sync/clean 两个 block 内 execLog 全量收敛

**零遗漏。**

Grep 全文件交叉计数（两条独立计数，行号逐一比对）：

| Grep 模式 | 命中行数 | 行号 |
|---|---|---|
| `execLog(` | 11 | 1 处定义 `:440` + 10 处调用 `:158 / :159 / :163 / :164 / :170 / :171 / :172 / :173 / :208 / :209` |
| `failures += it` | 10 | `:158 / :159 / :163 / :164 / :170 / :171 / :172 / :173 / :208 / :209` |

- 10 个调用点与 10 个 `?.let { failures += it }` **行号集合完全相同**；`execLog(` 比 `failures += it` 恰好多 1，多出的正是 `:440` 的函数定义（定义行不含 `?.let`）。⇒ 不存在「调用了 execLog 却没收 failures」的漏改点。
- 逐处形态均为 `execLog(...)?.let { failures += it }`，与要求逐字一致。
- 分布与预期完全吻合：`sync` 的事务 block（`:153-177`）8 处 —— Delete 2（`:158 deleteConv`、`:159 deleteContact`）+ Update 2（`:163 repair`、`:164 rename`）+ Insert 4（`:170 insertPreDelConv`、`:171 insertPreDelContact`、`:172 insertConv`、`:173 insertContact`）；`clean` 的事务 block（`:207-210`）2 处（`:208 cleanDelConv`、`:209 cleanDelContact`）。
- 结论：**零遗漏，没有漏掉任何一处**。两个 block 之外（`stat()` / `readAll()` / `backupRows()`）本就没有写 SQL 的 `execLog`，无需改。

### E · FAIL 即停的可观测信号

**通过，构成互斥可观测信号。**

- 异常被外层兜住：`throw failures.first()`（`:423`）从 `inTransaction` 冒泡到 `sync()` 的 `runCatching`（`:143`）→ `:186-189` `.getOrElse { e -> say(out, "FATAL ${e.javaClass.simpleName}: ${msgOf(e)}"); out }`，打 `FATAL` 后原样返回日志行 ✓；`clean()` 同理（`:196` → `:223-226`）。`msgOf`（`:452-456`）把 `InvocationTargetException` 解包成真实 `SQLiteException` 原文，故 FATAL 行带底层原因。
- `sync done own=` **不会**被打印：`:183` `say(out, "sync done own=$own other=$other")` 与 `:184` 的 `sync done contactOwn=…` 位于 `inTransaction`（`:153-177`）**之后**的 `:178-184` countOf 段内；`throw` 会跳过整段直接进 `getOrElse` ⇒ 两句 `sync done` 都不执行。`clean` 的 `:216` `clean done …` 同理不打印。
- ⇒ 信号互斥：正常成功才出现 `sync done own=` / `sync done contactOwn=`；任一 SQL 失败则出现 `op=... FAIL` + `GCONVSTORE FATAL …` 且**没有** `sync done`。上机纪律据此判「FAIL 即停」，不会出现「FAIL 与 done 同时出现」的误导（这正是修复前 `FAIL` 与半行落库并存的排查陷阱）。
- 补充：`getOrElse` 已在 `sync()`/`clean()` 内层兜住，异常**不会**冒泡到 `postSync` 的 `runCatching`（`:91-106`），故 `:105` 的 `postSync fail` WARN 不触发；日志侧只有 `GCONVSTORE FATAL …`（配合 `op=... FAIL`）。
- ⚠️ 该「停」是**停止本轮后续 SQL 与后续 countOf**，不是回滚已写行；原子性由 B 的事务路径保证，降级路径见 C。

### F · txMethod 不缓存 Method

**通过，每次重扫、零缓存。**

- `:385-390` `txMethod(db, name): java.lang.reflect.Method? = runCatching { db.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() } }.getOrNull()`：每次调用都重新 `db.javaClass.methods.firstOrNull`，返回的 `Method?` 只作局部值使用；**类体内不存在任何 `Method` 字段 / Map / 缓存容器**（全文 `:48-515` 已通读，`object GroupConvStore` 的字段只有 `TAG`/`CONV`/`CONTACT`/`BACKUP_NAME`/`OWNERSHIP_STAMP`/`worker`/`lastSyncMs`/`SYNC_THROTTLE_MS`）。
- `:401-403` `inTransaction` 每次进入都分别 `txMethod(db, "beginTransaction")` / `txMethod(db, "setTransactionSuccessful")` / `txMethod(db, "endTransaction")`，无一次缓存复用 ✓。
- 与类 KDoc `:29-34` 的硬约束一致：wcdb 主库与 AOSP 模块库是两套 `SQLiteDatabase` 实现，跨库缓存的 `Method` 调另一个实例会抛 `IllegalArgumentException`；`rawQuery` 双重载也会让 `findMethodExact` 静默找不到 ⇒ 一律 `Any` + 每次重扫。
- 旁证：`query()`（`:352-363`）、`exec()`（`:366-379`）同样是每次 `db.javaClass.methods.firstOrNull`，无字段级 Method 缓存。⇒ 未因本轮事务改造引入任何 Method 缓存。

### G · P2：ConvFilter 去掉 gconvEnabled 前置

**通过（去前置到位、OFF 零差异保持）；「残留 listener 真被清掉」需拆成静态可判与宿主行为两层。**

- **去前置**：当前工作区 `ConvFilter.kt:1799` 为 `if (rowGid == null && cv?.getTag(GCONV_ROW_TAG_KEY) != null) { cv = null }`，**已无 `gconvEnabled &&`**（对照 `_r54_fix_review.md:64` 修复前 `:1796` 的 `if (gconvEnabled && rowGid == null && …)`）。`:1794-1796` 注释明确写出理由：ON→OFF 热切换后开关已关，但带我方 tag 的 View 仍在回收池，残留 listener 必须照样清掉。该文件为本轮 `git diff` 的 `+147 -5` 变更面，当前条件即修复后形态。
- **OFF 零差异保持**：`rowGid`（`:1780-1782`）`if (gconvEnabled) runCatching { … }.getOrNull() else null` ⇒ OFF 恒 null；打标 `v.setTag(GCONV_ROW_TAG_KEY, gid)` 只在 `:1824` `if (gconvEnabled)` 块内的 `:1837` 执行，OFF 从不打 tag ⇒ 正常场景回收池无带我方 tag 的 View ⇒ `cv?.getTag(KEY) != null` 恒 false ⇒ `:1800` `cv = null` 不触发，cv 维持 `:1789` 的类型校验结果。唯一命中的正是 ON→OFF 热切换的残留 View。代价仅 OFF 每个复用行多一次 `getTag(int)` 稀疏表查找，热路径开销微小。
- **残留 listener 的清理机制（如实描述，不写成「已 setOnClickListener(null)」）**：代码没有对普通行清空 listener，而是把带我方 tag 的 View 判为污染、`cv = null` 丢弃，让 `:1804` `wrapped.getView(r, cv=null, parent)` 新建干净 View。`:1797-1798` 注释说明不无条件 `setXxxListener(null)` 的原因（会连微信刚设的 listener 一起清掉，普通行反而点不动）。
  - 静态可判：该污染 View **不再被回贴到真实可见行**（这正是 B 方案「源头阻断污染 View 被复用」的本意，`_r54_fix_review.md:67`）。
  - **需真机验证**：宿主在 `convertView == null` 时是否新建带自身 listener 的 View、微信复用 convertView 时是否重设 per-row `OnClick`/`OnLongClick` listener（与 `_r54_fix_review.md:69` 同一前提）。若宿主在这两处都不重设，仅靠丢弃 cv 不足以保证残留 listener 完全不生效。验收 SOP 冷启后回收池为空，该残留不必然触发，故定 P2 而非装包阻塞。
- `GCONV_ROW_TAG_KEY = 0x47525643`（diff 新增于 `:86-93` 一带）：`(0x47525643 >>> 24) = 0x47 = 71 >= 2`，满足 AOSP `View.setTag(int, Object)` 对 key 高字节的要求；未用 `generateViewId()`、未用无 key 的 `setTag(obj)` ⇒ 不覆盖微信自己的 tag。该魔数是否与宿主已有 keyed tag 冲突属「需真机验证」。

### H · OFF 路径零生效

**通过，生命线成立。**

- **默认 OFF**：`Keys.kt` 本轮新增 `GCONV_MODE = "chat.gconv_enabled"`，`PrefSpec(Keys.GCONV_MODE, PrefKind.SWITCH, "false", …)` 默认值 `"false"`；`ConvFilter.kt:1243` `gconvEnabled = runCatching { Kv.getBool(Keys.GCONV_MODE) }.getOrDefault(false)` ⇒ 未开时 `false`。
- **`|| false`**：`:1344` `if (groups.isEmpty() || gconvEnabled)`，OFF 时第二项 `false` ⇒ 与原 `groups.isEmpty()` 逐字等价，`ConvLayout.build(... enabled=false)` 的 passthrough 分支行为不变 ✓。
- **各 `&&` 短路 / effEnabled**：`:1248` `val effEnabled = enabled && !gconvEnabled` ⇒ OFF 时 `effEnabled == enabled`；`:1271` `configStamp(enabled = effEnabled)`、`:1312` `if (filterId == NONE && db != null && effEnabled)` 与改前逐字等价；`:1326` `db == null || !enabled` 保持**原 `enabled`**（未改成 effEnabled）✓。翻转 `GCONV_MODE` 时 `effEnabled` 才会变，属 ON 路径行为。
- **getView 整块跳过**：`:1824` `if (gconvEnabled) { … }` OFF 时不执行 ⇒ `:1837` `setTag`、`:1842` `setOnClickListener`、`:1843` `setOnLongClickListener` 全不跑；`:1780-1782` `rowGid` 短路为 null；`:1799` 清理条件因无带 tag View 不命中（见 G）✓。
- **kickGconvSync() 首行 return**：`:651-654` `if (!runCatching { Kv.getBool(Keys.GCONV_MODE) }.getOrDefault(false)) return` ⇒ OFF 直接返回、不 post ✓；`onDataChanged` `:1509` `if (gconvEnabled) GroupConvStore.postSync()` ⇒ OFF 不 post ✓。
- **AntiRevoke.wxDb() 完全不调**：ConvFilter.kt 全文 Grep `AntiRevoke.wxDb` **0 命中**；gconv 生产侧仅在 `GroupConvStore.kt:142`（sync）/`:195`（clean）/`:232`（stat）三处，且都只在 `postXxx` 投递的 `gconv-store` 单一后台线程内执行；OFF 连 `postSync` 都不发 ⇒ 该调用**完全不发生** ✓。
- **三个未改动项**：`getItemViewType`（`:1908-1913`）、`getViewTypeCount`（`:1915` `override fun getViewTypeCount(): Int = groupType + 1`）、`PinFoldLogic.configStamp`（调用点 `:1269`，实现体不在 ConvFilter.kt）均不在本轮 `git diff` 的任何 hunk 内，签名与实现未被改动 ✓。
- 唯一非零差异：`rebuild()` 每次多一次 `Kv.getBool(GCONV_MODE)`（`:1243`），量级可忽略（与 `_r54_fix_review.md:104` 一致）。

### I · 红线

**通过（5 项中第 4 项含「无 git 历史」的如实标注）。**

- **无新增宿主 hook**：ConvFilter.kt 本轮 `git diff` 新增代码里的 View 操作只有 `v.setTag`（`:1837`）、`v.setOnClickListener`（`:1842`）、`v.setOnLongClickListener`（`:1843`），均为 View 级 API；`GroupConvStore.kt` 全文 Grep `findAndHookMethod|hookAllMethods|hookAllConstructors|XposedBridge` **0 命中**（`:378` 等处只有 `XposedHelpers.callMethod` 反射 invoke，非注册 hook）。既有 `XposedBridge.hookAllMethods`（`:434`）、`XposedHelpers.findAndHookMethod`（`:461`/`:490`）都在 diff hunk 之外，非本轮新增 ✓。`EntryReceiver.kt` 的 `op=gcs` 只调 `postSync(force=true)`/`postClean()`/`postStat()`，不挂 hook。
- **无 `setText` 碰标题**：`setText` 在 ConvFilter.kt 仅出现于注释 `:1821`/`:1823`（写明「标题是宿主自绘 View 而非 TextView」、不碰标题、不 setText），实际代码无 `setText` 调用 ✓；改名统一由 `sync()` 写 `rcontact.nickname`。
- **组件未删**：`GroupedAdapter` 定义在 `ConvFilter.kt:1057`；`GroupRowView.kt` / `ConvLayout.kt`（以及 `AntiRevoke.kt` / `LogX.kt`）均存在（Glob 命中）；`git status --short` 无 ` D` 删除行 ✓。
- **GroupConvPlan 的 SQL 生成与 plan 口径**：全文 `:1-132` 通读，`plan()`（`:67-83`，Delete→Update→Insert，`want ∩ have` 只产 `Update`、`:81` 才产 `Insert`）、`repairSql`/`renameSql`（`:114-120` 均为 `update …`）、`ownWhere()`（`:89` `substr(username,1,6)='wxgrp_'` 宽口径）、`groupIdOf`/`isOurs`（`:30-42` 窄口径）、`quote()`（`:92`）、`insertConvSql`/`insertContactSql`（`:99-111`）、`deleteConvSql`/`deleteContactSql`（`:127-131`）与 `_r54_fix_review.md` 描述及 `docs/_r54_gconv_design.md` §10.3 定案一致，`GroupConvPlanTest.kt` 18 例在 919 内全过。⚠️ 该文件同为 `??` 未跟踪、无 git 历史 ⇒ 「相对上一版未变」静态判不了，只能确认「当前实现与定案一致且单测覆盖」，如实标注（同 `_r54_fix_review.md:113`）。
- **`GCONVSTORE ` 前缀保留**：`TAG = "GCONVSTORE"`（`GroupConvStore.kt:50`）+ `say()`（`:512-514`）；`postSync`/`postClean`/`postStat` 失败行（`:105`/`:116`/`:127`）手写同前缀；`tools/_r54_gconv.py:93` `tag = "GCONVSTORE"` 与 `dev.py q GCONVSTORE` 过滤链不变 ✓。

## ③ 构建数字

| 命令（严格按此顺序） | 结果 |
|---|---|
| `cd wxgroup && ./gradlew compileDebugUnitTestKotlin assembleDebug --offline -q --console=plain` | **GRADLE_EXIT=0**（耗时 26s） |
| 回仓库根 `bash tools/_junit.sh` | **OK (919 tests)** / **JUNIT_EXIT=0**（`Time: 0.949`，JUnit 4.13.2） |

- 基线 `OK (919 tests)` 吻合，**顺序未反**：先编译后 junit。
- `_junit.sh` 不自编译（已通读 `tools/_junit.sh:1-55`）：`:36-38` 只把 `MAIN=app/build/tmp/kotlin-classes/debug` 与 `TEST=app/build/tmp/kotlin-classes/debugUnitTest` 作为已编译目录检查存在性，`:44-45` 用 `find . -name '*Test.class'` 枚举已编译 class，`:52-53` 以 `java -cp "$MAIN;$TEST;junit;hamcrest;kotlin-stdlib;gson" org.junit.runner.JUnitCore $CLASSES` 直接跑 ⇒ 若编译没跑到或漏改源码，`919` 不会变成假判据。
- 编译确覆盖本轮改动的独立佐证：`GroupConvStore.kt` 源码 mtime `2026-11-12 00:14:01 +0800` < `app/build/tmp/kotlin-classes/debug/com/lx/wxgroup/feature/GroupConvStore.class` mtime `00:15:41 +0800`；`javap -p` 输出 `private final java.lang.Throwable execLog(Object,String,String,String,List<String>)` 与 `private final void inTransaction(Object,List<String>,Function1<? super List<Throwable>,Unit>)` ⇒ 修复后的返回签名 / block 签名确已进入字节码。
- `debugUnitTest` 下 `*Test.class` 共 **45** 个（含 `GroupConvPlanTest.class`），与 45 个 `*.kt` 测试文件一致；45 个测试文件全部只 import `org.junit.Assert.*` / `org.junit.Test`（全目录 Grep 证实），无 android/Xposed/mock 依赖。

## ④ P0 / P1 / P2 清单（精确修改建议，不改源码）

### P0：0 条

首轮 P0 的实质（两入口并发写同一 wcdb 连接、主线程全表读写库）已由 `postSync`/`postClean`/`postStat` 全部投递到 `gconv-store` 单一后台线程闭环（`GroupConvStore.kt:59-61`、`:89-130`）；本轮事务改造未新增任何生产入口，也未把 `sync/clean/stat` 暴露给主线程。

### P1：0 条

上一条 P1（「事务失败不回滚 + 半行不自愈」）已由 A~F 六项闭环，编译产物签名佐证（③）。**无需再改源码**。与 ① 的「可装包」一致：不存在「需先修 N 条」的阻塞项，P1 清单为空与 verdict 不矛盾。

### P2：0 条阻塞项（下列为观察 / 记录项，不构成装包前置）

- **P2-1（已修，无需再改）**：`ConvFilter.kt:1799` 已去掉 `gconvEnabled` 前置，见 G。上轮建议的 `rowGid == null && cv?.getTag(GCONV_ROW_TAG_KEY) != null` 已是当前形态。
- **P2-2（已修，无需再改）**：`GroupConvStore.kt:83-88` 的 KDoc 已改为「半行不会被下一次 sync 自愈…须 `op=gcs sub=clean` 全删重建；事务回滚用于阻止半行产生」，与 `plan()` 事实一致，上轮「注释与事实不符」已闭环。
- **观察项（不需改码）**：`rebuild()` 每次多一次 `Kv.getBool(GCONV_MODE)`（`ConvFilter.kt:1243`），量级可忽略；`op=gcs sync` 的 `force=true` 会推进 `lastSyncMs`（`GroupConvStore.kt:98`），60s 内 `refresh()` 兜底被吞，验收 SOP 的 force 已覆盖。

### 精确建议（不改源码，均为后续动作 / 验收操作）

1. **把 4 个 `??` 源码纳入版本控制**：`GroupConvStore.kt`、`GroupConvPlan.kt`、`GroupConvProbe.kt`、`GroupConvPlanTest.kt`（`HookListProbe.kt` 同）。当前它们无 git 历史，导致 ② A 的「日志格式逐字未变」与 ② I 的「GroupConvPlan 口径未变」都**只能静态确认、无法用 diff 证明**；后续任何复核都会重复这个盲区。由实现者 `git add` 纳入跟踪即可，本 lane 未执行、也未 commit。
2. **真机若出现「WARN 事务方法(begin/setTransactionSuccessful/end)反射失败」**（`GroupConvStore.kt:407`）：按 C 处理——这是**无事务降级**，`throw`/`FATAL` 不回滚已写 SQL。立即 `op=gcs sub=clean` 再 `op=gcs sub=sync`（force），用 `stat` 确认 `convOwn == contactOwn` 且与分组数一致；并记下 wcdb 实际的事务方法名，供后续决定是否需要补反射兜底（当前三个方法名是硬编码假设）。
3. **验收 SOP 保持冷启**：ON→OFF 热切换的残留 View（G）依赖宿主回收池行为，冷启后回收池为空可规避。

### 需真机验证（静态判不了，不硬下结论）

1. wcdb 三个事务方法反射是否拿得到、一次 sync 在真机的耗时与是否占主线程。
2. `begin → block → end` 在 wcdb 单连接上是否真的整段回滚（SQLite 事务语义）；制造一条必然失败的 insert 时 `stat` 两表是否成对。
3. 宿主在 `convertView == null` 时是否新建带自身 listener 的 View、微信复用 convertView 时是否重设 per-row `OnClick`/`OnLongClick` listener（G 清理机制的共同前提）。
4. `View.setTag(int, Object)` 的 `0x47525643` 是否与该宿主 View 已有 keyed tag 冲突。
5. `op=gcs` 异步 post 后 `tools/_r54_gconv.py` 的 `WAIT=2.5` 是否够 worker 跑完 sync（`_r54_fix_review.md:122`）。
6. `execLog` FAIL 行的「逐字未变」与 `GroupConvPlan.kt`「相对上一版口径未变」——两者受限于无 git 历史，须靠真机日志 / 后续纳入版本控制后比对。

### 上机纪律

- 一旦 `dev.py q GCONVSTORE` 出现 `op=... FAIL` 或 `GCONVSTORE FATAL …`，或 `stat` 的 `convOwn != contactOwn` / `own` 与分组数不符 ⇒ **立即停止**：先 `op=gcs sub=clean` 再 `op=gcs sub=sync`（force），确认两表行数成对；不得据该轮下「分组行是否正确」的结论。
- 正常成功的可观测信号（互斥）：有 `sync done own=` / `sync done contactOwn=`、无 `FATAL`、无 `op=... FAIL`。失败轮则相反（见 E）。

### 必做 4 · 是否需要补单测的判定

**判定：不可行 / 不必补。静态核对已足够，真机靠 `op=... FAIL` 日志 + `stat` 两表成对验证。** 具体障碍（不含糊带过，逐条列出）：

1. **`inTransaction` 是 `private`**（`GroupConvStore.kt:400`，javap 证 `private final`）⇒ JVM 单测要么反射 `setAccessible(true)` 并手工构造 `Function1<List<Throwable>, Unit>`，要么把生产代码可见性放宽为 `internal`（改 `app/src/main`，属改源码且超出「最小改法」，本 lane 边界禁止）。
2. **类本身依赖 Android / Xposed，现有 JVM 单测设施加载不了**：`GroupConvStore` import `HostContext`、`LogX`（`LogX.kt:3-4` import `android.util.Log` 与 `de.robv.android.xposed.XposedBridge`）、`android.os.SystemClock`（`:92`），并在 `:378` 等处用 `XposedHelpers.callMethod`。而 `app/build.gradle.kts:66` 的 Xposed 依赖是 `compileOnly`（运行时由框架提供、不进 test classpath），`:72-74` 注释明确「纯 JVM 单测：只覆盖不依赖 Android 的逻辑」且唯一 test 依赖是 `junit:junit:4.13.2`（无 mockk / Mockito / Robolectric）；`tools/_junit.sh:52` 的 classpath 只有 `MAIN;TEST;junit;hamcrest;kotlin-stdlib;gson`，**无 android.jar、无 Xposed jar、无 mock 框架** ⇒ 现有设施里加载 `GroupConvStore` 会缺类，且 `AntiRevoke.wxDb()` 是 object 单例静态方法，无法注入 fake db 到 `sync()`/`clean()` 端到端测。
3. **即便绕开上述障碍只测 `inTransaction`，覆盖也是半条链路**：它只能验证「`failures` 非空 ⇒ 不调 `success.invoke`」；而「`execLog` 确能返回 `e`」已由 A 逐行核对、「10 个调用点全部 `?.let { failures += it }`」已由 D 的两次 Grep 逐行交叉计数（10 调用 : 10 let，行号集合相同）穷尽，单测只是重复静态结论。
4. **wcdb 事务方法反射可用性、`begin`/`end` 的真实回滚语义本就不是 JVM 能模拟的**：该链路的正确性最终必须落在真机日志（`op=... FAIL` / `FATAL` / 无 `sync done`）与 `stat` 的 `convOwn`/`contactOwn` 成对对账上。

⇒ 建议**不新建任何测试文件**。若将来确要补，最小路径是「把事务编排抽成不依赖 Android/Xposed 的纯函数 + 注入 fake db（提供 `beginTransaction`/`setTransactionSuccessful`/`endTransaction`/`execSQL`，并让某条 `execSQL` 抛异常）」，属架构改动，非本轮装包前置条件；本 lane 按要求不写测试、也不改 `app/src/main`。

## ⑤ 实际读过的文件清单

**源码全文**
- `wxgroup/app/src/main/java/com/lx/wxgroup/feature/GroupConvStore.kt`（1-516，未跟踪文件，直接 Read 全文）
- `wxgroup/app/src/main/java/com/lx/wxgroup/data/GroupConvPlan.kt`（1-132）

**源码区段 / 检索**
- `wxgroup/app/src/main/java/com/lx/wxgroup/feature/ConvFilter.kt`：`:645-662`、`:1238-1352`、`:1495-1516`、`:1770-1864`、`:1898-1925`；另全文件 Grep `setText|findAndHookMethod|hookAllMethods|hookAllConstructors|AntiRevoke.wxDb|class GroupedAdapter|configStamp|GCONV_MODE|GroupConvStore.`
- `GroupConvStore.kt` 全文 Grep `execLog(`、`failures += it`（两条独立计数，用于 D 交叉验证），另 Grep `findAndHookMethod|hookAllMethods|hookAllConstructors|XposedBridge`（0 命中）
- `wxgroup/app/src/main/java/com/lx/wxgroup/data/Keys.kt`、`wxgroup/app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt`：`git diff` 全文
- `wxgroup/app/src/main/java/com/lx/wxgroup/util/LogX.kt`：import 检索
- `wxgroup/app/src/main/java/com/lx/wxgroup/feature/AntiRevoke.kt`、`GroupRowView.kt`、`data/ConvLayout.kt`：存在性检索（Glob）

**构建 / 测试配置**
- `wxgroup/app/build.gradle.kts`（`:50-79`）、`tools/_junit.sh`（1-55）
- `wxgroup/app/src/test/**/*.kt`：45 个文件（Glob）+ 全目录 Grep import 形态（仅 `org.junit.*`）

**编译产物**
- `wxgroup/app/build/tmp/kotlin-classes/debug/com/lx/wxgroup/feature/GroupConvStore.class`（`javap -p` 查签名 + mtime）
- `wxgroup/app/build/tmp/kotlin-classes/debugUnitTest` 下 45 个 `*Test.class`、`GroupConvPlanTest.class`

**git / 文档**
- `git status --short`、`git diff --stat -- wxgroup/app/src/main`、`git diff -- ConvFilter.kt / Keys.kt / EntryReceiver.kt`
- `docs/_r54_fix_review.md`（1-202，全文）；`docs/` 目录 Grep `op=... FAIL|op=... OK|GCONVSTORE `（上机纪律所在行）

**独立跑过的命令**
- `cd wxgroup && ./gradlew compileDebugUnitTestKotlin assembleDebug --offline -q --console=plain` ⇒ `GRADLE_EXIT=0`
- `bash tools/_junit.sh` ⇒ `OK (919 tests)` / `JUNIT_EXIT=0`
- `javap -p -classpath … com.lx.wxgroup.feature.GroupConvStore`、`stat`/`find`/`git diff`/`git status`

**未读 / 未验证**：`GroupRowView.kt` / `ConvLayout.kt` / `AntiRevoke.kt` / `LogX.kt` 实现体（仅存在性与 import 检索）、`PinFoldLogic.configStamp` 实现体、`GroupConvProbe.kt` / `HookListProbe.kt`、`tools/dev.py` / `tools/_r54_gconv.py` 全文、`docs/_r54_gconv_design.md` 全文（仅 Grep 命中行）、任何 dex / 微信宿主字节码。未连设备、未跑 adb、未 `git commit`、未改 `app/src/main`；唯一写入为报告 `docs/_r54_final_review.md`。

## ⑥

[DONE]
