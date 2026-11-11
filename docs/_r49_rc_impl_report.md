# R49 / 阶段二 R-C「复用写时拦截」结构化写事件探针 SnsWriteProbe 实现报告

> 独占实现 lane。以磁盘源码现状为准，禁止整读 >300 行文件，每条结论附 `文件:行号`。
> 硬约束：只改 5 个源码文件 ±1 单测；唯一允许写的报告文件即本文件；0 装包 / 0 真机 / 0 adb / 0 kvset / 0 git add/commit；不碰 SnsSqlRewrite.kt 的 `& -9 → & -1` 改写逻辑；不改 R42 现网行为。

[START]

## 分步清单（每完成一步立刻追加 [DONE]）

- [x] S0 现状核对：5 个文件真实类名/签名/钩子位置/分支结构/Keys 风格/单测目录写法（带 `文件:行号`）
- [x] S1 设计与判据：W1~W5 落地设计、纯函数签名清单、三档边界表
- [x] S2 SnsWriteProbe.kt：新增类说明
- [x] S3 SnsGuard/SnsBlockProbe 接线：改了哪几行、为什么不会改 R42 行为、before 内零查库
- [x] S4 EntryReceiver/Keys：新增分支与开关、只读性自证
- [x] S5 单测：用例矩阵 + 变异验证结果（≥4 组变红）
- [x] S6 编译自检：命令 + 通过数 + 结果
- [x] ALL：改动文件清单（逐文件行数增减）、`git diff --stat` 输出、风险与盲区、装包前复核要点

---

## [DONE] S0 现状核对（以磁盘为准，行号全部实测）

**文件真实路径 / 行数**（Glob + `wc -l`）：
- `EntryReceiver.kt` 实际在 `entry/EntryReceiver.kt`（**不是**主包，960 行）；`Keys.kt` 在 `data/Keys.kt`（580 行）`app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt:1`（包名 `com.lx.wxgroup.entry`）、`data/Keys.kt:54`（`object Keys`）。
- `feature/SnsGuard.kt`（1638 行，包名 `com.lx.wxgroup.feature`，`SnsGuard.kt:1`）、`feature/SnsBlockProbe.kt`（828 行）、`feature/SnsSqlRewrite.kt`（152 行）、`util/LogX.kt`（143 行）。
- 单测目录 `app/src/test/java/com/lx/wxgroup/{data,feature,core}/*Test.kt`，JUnit4（`org.junit.Test` + `org.junit.Assert.*`），Kotlin 中文反引号函数名：`SnsSqlRewriteTest.kt:1-25`、`SnsSqlTest.kt:1-9`。被测 `SnsSqlRewrite`/`SnsSql` 都是**纯 Kotlin object**（`SnsSqlRewrite.kt:37`，零 Android/Xposed import）。
- `docs/开发回路速查.md`(481 行)、`CLAUDE.md`(339 行)。

**SnsGuard 钩子接线**（`SnsGuard.kt`）：
- 注册 `SnsGuard.kt:148-190`：`execSQL`→`sqlGuard`（`:221-226`，before→`onExecSql`）；`updateWithOnConflict`→`snsUpdateGuard`（`:236-241`，after）**与** `snsBlockGuard`（`:297-307`，before→`onSnsUpdateBefore`），Xposed 允许同方法多回调（`:161-165`）。
- `TABLE = "SnsInfo"`（`SnsGuard.kt:67`）；`onSnsUpdateBefore`（`:356-363`）第一句 `(a[0] as? String) != TABLE` 零反射短路。
- `onExecSql`（`:834-857`）→ `onSnsSql`（`:872-892`）：先 `SnsBitLogic.sqlClear` 判形态 → 可能 `applySqlRewrite`（`:900-915`，把 `param.args[0]` 的 `& -9` 改成 `& -1`）→ 末尾 `SnsBlockProbe.noteSql(sql=**原始 sql 局部变量**, ...)`（`:884-891`）。
- ⭐ `SnsBlockProbe.noteSql`（`SnsBlockProbe.kt:552-598`）第一行 `sqlSns.incrementAndGet()`（`:560`），且 `:583` 注释明确「原文仍是微信原本要执行的那条 —— 判据要靠它，不能存改写后的」⇒ **execSQL 原文的可靠观测点，且改写只动 `param.args[0]`、不动这个 `sql` 局部变量**（`SnsGuard.kt:909`）。
- `runReadOnlySql(sql, maxRows)`（`SnsGuard.kt:1268-1314`）：只允许 `select`/只读 `pragma`、拒内部 `;`、`snsDb` 未捕获回提示；`snsReady()`（`:1035`）、`snsDbHandle()`（`:1043` internal）、`stat(): String`（`:1635`）。
- `refresh()`（`:192-205`）读 `Keys.ANTIREVOKE_SNS` / `ANTIREVOKE_SNS_BLOCK` / `ANTIREVOKE_SNS_SQLBLOCK_OFF` ⇒ **R42 开关耦合点，探针绝不调用、绝不改这些 key**。

**SnsSqlRewrite.kt**（152 行，纯逻辑，本轮**严禁改**）：`CLEAR_BIT3_ASSIGN`（`:45-48`）、`SNSID_GT/LT`（`:50-51`）、`shouldBlock`（`:65-69`）、`rewrite`（`:77-85`，`& -9`→`& -1`）、`rangeKey`（`:93-98`）、`whereIndex`（`:125-141`）。

**SnsBlockProbe 现有风格**：`object`（`:38`）、`RING=50`（`:43`）、`SQL_SAMPLE_MAX=60`（`:55`）、`SQL_LOG_CHARS=1500`（`:58`）、`record`（`:312-371`，`synchronized(events)` 环形）、`stat(): List<String>`（`:718-743`，TAG `BLOCK ...`）、`switch(arg)`（`:703-713`，写 `Kv.putBool` + `SnsGuard.refresh()`）、日志 `BLOCK w2 sql-clear9(...)`（`:586`）。

**EntryReceiver op 分支**（`entry/EntryReceiver.kt`）：`ACTION_PROBE`（`:63`）→ `when(op)`（`:162`）；`snsg`（`:342-387`，`sub`=`:343`、`sub2`=`EXTRA_SUB2="sub2"` `:75`/`:346`、`io { when(sub) ... }`）；`sql`（`:654-662`，`--es q` + `--ei n` → `io { SnsGuard.runReadOnlySql(q,n) }`）；全限定名风格 `com.lx.wxgroup.feature.SnsGuard.xxx`（`:349` 等）；`private fun io(block: () -> Unit)`（`:89`）。

**Keys 风格**：`const val ANTIREVOKE_SNS="antirevoke.sns"`（`Keys.kt:145`）、`ANTIREVOKE_SNS_BLOCK`（`:161`）、`ANTIREVOKE_SNS_SQLBLOCK_OFF`（`:174`）；`PrefSpec(...)` 默认 `"false"`（`:437-459` 防撤回域）。`Kv.getBool`（`Kv.kt:35-38`）未注册 key → `Prefs.defaultOf` 默认 `""` → **false**（⇒ 新增 `const val` 不加 PrefSpec 也默认关，且可避免与 R42 两开关在 UI 并列造成「耦合」误解）。`Kv.putBool`（`:57`）。

**编译/单测回路**（`开发回路速查.md` + `tools/gradle_run.py`）：
- `python tools/gradle_run.py <task>`：GRADLE=`C:\Users\lx\.workbuddy\binaries\gradle\gradle-8.9\bin\gradle.bat`、JAVA_HOME=`G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot`、`--offline`、clean PATH（`gradle_run.py:11-13,16-37`）；输出过滤含 `tests completed` / `BUILD` / `FAILED`。
- 单测 `testDebugUnitTest`（速查 `:218`，gradle_run.py 默认 task `:28`）；编译必须 `assembleDebug`（速查 `:413` 明确「assembleDebug **不编译** test 源集」，改测试源码须先 `compileDebugUnitTestKotlin` `:402`）。
- ⚠️ `tools/_junit.sh` / `.tmp/mutate2.sh` **磁盘不存在**（Glob 无结果）⇒ 变异验证改用 `gradle_run.py testDebugUnitTest` + 读 `app/build/test-results/testDebugUnitTest/*.xml` 判定具体哪个用例变红。
- `build.gradle.kts:64-75`：`compileOnly("de.robv.android.xposed:api:82")` ⇒ 单测 classpath **无 xposed jar**，纯逻辑 object 必须零 Android/Xposed 引用；`testImplementation("junit:junit:4.13.2")`。
- CLAUDE.md：D1 先查后设计（`:38`）、D4 控制优先于观测（`:48`）、纯判据单测化（`:27`）、判据对着用户可见事实 / 互斥判据别写成并列（速查 `:323,326`）、真机前 diff 交叉复核（`CLAUDE.md:243-249`）。

[DONE] S0 现状核对

## [DONE] S1 设计与判据（W1~W5 落地 + 纯函数签名 + 三档边界）

**架构（零 Android 依赖可自证）**：`SnsWriteProbe.kt` 只有 `package` + **零 import**（桥接层一律用全限定名 `com.lx.wxgroup.util.LogX` / `data.Kv` / `data.Keys` / `feature.SnsGuard`），文件内两个独立 object：
- `SnsWriteProbeCore`：**纯逻辑、零 Android/Xposed 引用**，单测直接测它。与桥接 object 是不同 `.class`，JVM 单测加载 Core 不会触发 Android/Xposed 类加载（绕开 `build.gradle.kts:66` compileOnly 在单测 classpath 缺失）。
- `SnsWriteProbe`：Android 桥接（armed 判断、从 `param.args` 抽字段、导出查库、arm/disarm 写模块 KV）。

**纯函数 / 结构清单**（全部在 `SnsWriteProbeCore`，零 Android 依赖）：

| 签名 | 语义 / 判据 |
|---|---|
| `data class Clear9(table,userName,sourceTypes:List<Int>,lo:Long?,hi:Long?,rewritten:Boolean,rawSql)` | `parseClear9` 结果；`rewritten=true` = `& -1` 已改写态 |
| `fun parseClear9(sql: String?): Clear9?` | 目标 SQL 解析；`null`=非目标。`null`/空→null；非 `update` / 非 `SnsInfo`→null；SET 里 `sourceType = sourceType & -9`→`rewritten=false`；**`& -1`→`rewritten=true`（防假阴性关键：必须识别成「已改写」而非「无写事件」）**；`& -3` / 普通 UPDATE / SELECT / DELETE→null；大小写/空格变体用 `IGNORE_CASE`+`\s*`；抽 `userName`、`sourceType in (...)` 白名单、`snsId > A` / `< B` 区间 |
| `fun isOlder3dSec(createTime: Long, t0Sec: Long): Boolean` | 只认 **10 位 Unix 秒** `[1e9,1e10)` 且严格 `< t0Sec`；13 位毫秒(`>=1e12`) → false（防秒/毫秒混淆）；**等于 T0 不算三天外** |
| `fun inOpenRange(snsId: Long, lo: Long?, hi: Long?): Boolean` | `snsId > lo`、`snsId < hi`（严格开区间）；null 边界=不限 |
| `fun userMatches(rowUser: String?, targetUser: String?): Boolean` | target 空=不限定；否则必须相等（不匹配→false） |
| `fun rowHit(createTime,snsId,rowUser,cond: HitCond): Boolean` | 三者 `&&` 组合，单行命中判据 |
| `fun hitOlder3d(rows: List<Row>, cond: HitCond): Long` | 批量命中**条数**（判据的纯函数镜像；导出查库用等价 SQL）；变异「恒返回 0」打这里 |
| `data class HitCond(t0Sec, lo, hi, userName)` | 命中条件（T0 由 `select max(createTime)` 动态算，不硬编码 1793884838） |
| `fun sqlQuote(s: String): String` | `'`→`''` 转义（userName 拼进 SQL 前必用，防注入） |
| `fun buildHitCountSql(cond): String` | 生成只读 `select count(*),min(snsId) from SnsInfo where createTime < T0 and snsId > A and snsId < B and userName='...' and hex(content)<>'' and hex(attrBuf)<>''`；**BLOB 判空一律 `hex()`、禁 `cast(... as text)`**；数字直接拼、userName 走 `sqlQuote`；内部无 `;`（过 `runReadOnlySql` 白名单） |
| `fun verdict(seen: Long, matched: Long): String` | 三态**互斥**：`seen==0`→「无观测机会」；`seen>0 && matched==0`→「挂对了但判据不识别」；`matched>0`→「有真实写事件」。if/else if/else，⛔ 绝不并列 |
| `data class WriteEvent(ts,kind,sql,cvKeys,cvSourceType,table,where,snsIds,userName,caller,hitOlder3d:Long?,hitSample:String?)` | W1 全字段；`hitOlder3d/hitSample` before 阶段为 null（**before 禁查库**），导出 `dump` 时回填 |
| `fun record(ev): Boolean` / `stat(): List<String>` / `dump(): List<String>` / `reset()` | seen+1；`parseClear9` 非 null→matched+1；按 **`kind|sql|userName`** 去重；环形封顶 `RING=400`；reset 清账本+计数 |
| `@Volatile var armed` + `arm()/disarm()/isArmed()` | 进程内总开关，默认 false（冷启清零）；`isArmed()` = 热路径唯一动作 |

**W1** 接线：`SnsGuard.snsBlockGuard.before`(UPDATE，`SnsGuard.kt:297`) 与 `SnsBlockProbe.noteSql` 首行(EXECSQL 原文点，`SnsBlockProbe.kt:552`)，`isArmed()` 短路；`RING=400` + `kind|sql|userName` 去重 + 进程内结构（冷启天然清零）+ **before 内零日志/零查库**（日志只在 `op=writeprobe` 导出时打，避开 LogX 5000 行环形 / K7 每 250ms 刷屏）。
**W2** before 只忠实记录 SQL 原文 + 纯函数 `parseClear9` 解析出的区间/白名单/userName，**零查库**；导出时 `SnsWriteProbe.export` 用 `SnsGuard.runReadOnlySql`（select 白名单）查 `count(*),min(snsId)` 回填 `hitOlder3d/hitSample`。
**W3** `seen` 口径 = **SnsInfo 相关**的 UPDATE(before) + EXECSQL(含 `SnsInfo`) 次数；`matched` = `parseClear9` 非 null 次数；三态由 `verdict()` 互斥给出。
**W4** `Keys.SNS_WRITE_PROBE="sns.write_probe"` 默认 false（**不进 PrefSpec**，避免与 R42 两开关 UI 并列造成耦合误解）；`EntryReceiver` 新增 `op=writeprobe`（`sub=stat|dump|reset|arm|disarm`，照 `sql` 分支 `:654-662` 用 `io {}` + 全限定名 + `LogX.i("WP $l")`）；arm/disarm **只**写 `Kv.putBool(Keys.SNS_WRITE_PROBE,...)` + 改内存 armed，⛔ 不调 `SnsGuard.refresh()`、不写任何 `ANTIREVOKE_*` key、不碰微信库。

**W5 三档边界表（逐档不省略）**：

| 档 | 触发 | 账本 / 计数行为 | 判据要点 |
|---|---|---|---|
| ① 进入 | 刚进好友主页那一刻 | 阶段一实测每进一次发 **6 条 `& -9` 清 bit3 raw SQL**（WHERE=userName+64 个含 bit3 的 sourceType 白名单+snsId 区间、**无 createTime 窗口**）⇒ `seen/matched` 增长、账本入账 | ⛔ 「进主页前后 `op=sql` 库快照差分」**必然假阴性**：`SnsSqlRewrite.kt:46` 已把 `& -9`→`& -1`（no-op），SQL 照执行但库值不变。只能看 before 原文账本 |
| ② 返回 | 切页回来 / 下拉刷新 / 后台同步回来 / **冷启** | 切页/刷新/同步：账本**继续追加、不重置、不错乱**；**冷启：账本进程内结构 ⇒ seen/matched/明细全清零（预期，不是 bug）** | 冷启后须重新 `arm` 才有观测；清零是进程内结构的自然结果，不写回任何状态 |
| ③ 复位 | 关探针开关 / 关两个 R42 开关（`antirevoke.sns` + `antirevoke.sns_block` **都关**）/ 清缓存 / 对方改回「全部可见」 | 关 `sns.write_probe` ⇒ 账本**停止增长**（已有明细保留供最后一次 dump）；**⛔ 不回滚任何已写进微信库的位** | 已写进微信库的位是**磁盘状态，不随开关/冷启回滚**；`arm` 之前的写事件**不会被追溯记录**；⛔ **复位 ≠ 返回**：返回只是切页/刷新（账本继续），复位是开关/缓存/对方设置变更且不回滚磁盘位 |

**不改 R42 现网逻辑自证**：本轮**不碰** `SnsSqlRewrite.kt`（`& -9→& -1` 逐字保留）；探针不改 `param.args`、不改 `ContentValues`、不改 SQL、不改返回值、不调 `refresh()`、不写 `ANTIREVOKE_*`；两处新增行都在既有逻辑**之前**且被 `isArmed()` 短路（关=除一个 boolean 判断外零动作）；R42 写时拦截 / bit3 写回 / `[已删除]\n` 前缀行为零变化。

**变异映射预排（S5）**：`<`→`<=` 打 `isOlder3dSec` → `createTime==T0` 用例红；`-9/-1` 白名单判定改恒真 → `non_target`/`bit1_only` 红；区间 `>`/`<` 互换 → `inOpenRange` 红；去重键去 `userName` → dedup 用例红；`hitOlder3d` 恒返回 0 → 计数用例红。

[DONE] S1 设计与判据

## [DONE] S2 SnsWriteProbe.kt（新增）

文件：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsWriteProbe.kt`（新建）。
结构：**两个独立 object，整文件零 import**（桥接一律全限定名），Core 的 `.class` 可零 Android 依赖地在纯 JVM 单测里加载。

- `SnsWriteProbeCore`（纯逻辑，零 Android/Xposed 引用）：
  - 常量 `RING=400` / `MAX_DUMP=60`；`enum Kind{UPDATE,EXECSQL}`；data class `Clear9` / `HitCond` / `Row` / `WriteEvent`（W1 全字段 + `dedupKey=kind|sql|userName` + `line()`）。
  - `parseClear9(sql)`:SET 正则 `(-9|-1)\b`（IGNORE_CASE + `\s*`），只认 `UPDATE`+`SnsInfo`+顶层 WHERE 之前的清位赋值；`-1`→`rewritten=true`（防假阴性）；`-3`/SELECT/DELETE/普通 UPDATE→null；抽 userName / `sourceType in (...)` 白名单 / `snsId > A < B` 区间；`topLevelWhereIndex` 引号+括号+词边界。
  - `isOlder3dSec`（10 位秒 `[1e9,1e10)` + 严格 `<`，13 位毫秒直接 false）、`inOpenRange`（严格开区间）、`userMatches`、`rowHit`、`hitOlder3d`。
  - `sqlQuote`（`'`→`''`）、`buildHitCountSql`（只读 count，`hex(content)<>''` / `hex(attrBuf)<>''`，**禁 cast**，userName 转义，无 `;`）。
  - `verdict`（if/else if/else 三态互斥）、`whereClause` / `parseUserName` / `parseSnsId` / `rangeText`。
  - 账本：`AtomicLong seen/matched` + `ArrayList events` + `HashSet dedup` + `@Volatile armed`（默认 false）；`record`（seen+1 / matched+1 / 去重 / 环形淘汰）、`snapshot`（浅拷贝，导出回填同引用）、`stat`（摘要+三态+三档 3 行提示）、`dump`（封顶）、`reset`、`setArmed/isArmed/counts`。
- `SnsWriteProbe`（桥接，全限定名）：`isArmed()`（热路径唯一 boolean 判断）、`captureUpdate(args)`（只读 `param.args`：table/where/CV keySet/sourceType）、`captureExecSql(sql)`（改写前原文）、`export(sub)`（stat/dump/reset/arm/disarm）、`resolveT0`（`select max(createTime)` 动态算 T0）、`fillHits`（导出阶段只读查库回填）、`parseCountRow`、`cvKeys/cvSourceType` 反射（runCatching 吞异常）、`callerOf`（armed 时才取 stackTrace）。

**红线自证**：`captureUpdate/captureExecSql` 全程只读、不改 `param.args`/CV/SQL/返回值、不查库、不写日志；`export` 只调 `SnsGuard.runReadOnlySql`（select 白名单）+ `Kv.putBool(KEY)`（模块配置库），不调 `SnsGuard.refresh()`、不写 `ANTIREVOKE_*`、不 import/不改 `SnsSqlRewrite.kt`。

[DONE] S2 SnsWriteProbe.kt

## [DONE] S3 SnsGuard/SnsBlockProbe 接线（已 Grep 复核落盘）

- `SnsGuard.kt`：`snsBlockGuard.beforeHookedMethod`（原 `:297-302`）**最前面**新增 7 行 → 现 `SnsGuard.kt:302-304`：
  `if (SnsWriteProbe.isArmed()) { runCatching { SnsWriteProbe.captureUpdate(param.args) }.onFailure { LogX.w(...) } }`。
- `SnsBlockProbe.kt`：`noteSql`（原 `:552-560`）函数体首行、**`sqlSns.incrementAndGet()` 之前**新增 8 行 → 现 `SnsBlockProbe.kt:564-566`：
  `if (SnsWriteProbe.isArmed()) { runCatching { SnsWriteProbe.captureExecSql(sql) }.onFailure { LogX.w(...) } }`。
  放在 `sqlSns++` 之前 ⇒ seen 覆盖**所有**含 SnsInfo 的 execSQL（不被 `if (!isSourceWrite) return` 提前过滤掉）。
- 两文件与 SnsWriteProbe 同包 `com.lx.wxgroup.feature` ⇒ **零 import 改动**；SnsBlockProbe 已有 `import LogX`（`:8`）。

**为什么不会改 R42 现网行为**：① 两处新增行都在既有逻辑**之前**且被 `isArmed()` 短路 —— 探针关时 `before` 里**除一个 boolean 判断外零额外动作**（红线）；② 开时也只做 `runCatching` 只读采集，异常只 `LogX.w`、绝不向微信写库路径抛；③ 不改 `param.args`、不改 `ContentValues`、不改 SQL、不改返回值、不调 `SnsGuard.refresh()`、不写 `ANTIREVOKE_*`；④ `SnsSqlRewrite.kt` 的 `& -9→& -1` 改写**逐字未动**，R42 写时拦截 / bit3 写回 / `[已删除]\n` 前缀全部原样。

**before 内零查库**：`captureUpdate/captureExecSql` 只读参数 + 纯函数解析 + 入内存账本；唯一的库查询 `fillHits` 只在 `op=writeprobe` 导出时（EntryReceiver 的 `io {}` 线程）调 `SnsGuard.runReadOnlySql`（select 白名单）。

**execSQL 原文可靠性（防假阴性）**：`noteSql` 的 `sql` 是 `SnsGuard.onSnsSql(`:884-891`) 传进来的**原始局部变量**（`noteSql(sql = sql, ...)`）；`applySqlRewrite`（`:900-915`）只改 `param.args[0]`，不动这个局部变量 ⇒ 探针记录的是改写前的 `& -9`，而 `parseClear9` 对 `& -1` 的识别是纯函数层的健壮性兜底（单测覆盖）。

[DONE] S3 SnsGuard/SnsBlockProbe 接线

## [DONE] S4 EntryReceiver/Keys（已 Grep 复核落盘）

- `Keys.kt`：在**「调试」域**末尾（`DEBUG_K7_SNAP_PROBE` 之后、`object Keys` 闭合前）新增
  `const val SNS_WRITE_PROBE = "sns.write_probe"`（现 `Keys.kt:385`）+ KDoc。
  ⛔ **故意不进 `Prefs.ALL`**：① 避免与防撤回域 `ANTIREVOKE_SNS`/`ANTIREVOKE_SNS_BLOCK` 在设置页并列造成「耦合」误解；② 默认 `false` 由 `Kv.getBool`（`Kv.kt:35-38`，未注册 key → `Prefs.defaultOf` 默认 `""` → false）保证，无需注册。物理位置在调试域（`:348`）而非防撤回域（`:139`）。
- `EntryReceiver.kt`：在 `sql` 分支（原 `:654-662`）之后新增 `op=writeprobe` 分支（现 `:663-673`），照 `sql` 分支既有风格（`val sub` + `io {}` + 全限定名 + `LogX.i(l)`），全部委托 `SnsWriteProbe.export(sub)`；`sub=stat|dump|reset|arm|disarm`。

**只读性自证（所有分支）**：
- `stat`/`dump`：行级命中判定只调 `SnsGuard.runReadOnlySql`（`SnsGuard.kt:1268-1279`，select/只读 pragma 白名单、拒内部 `;`）+ 纯内存账本；⛔ 不写微信库。
- `reset`：只清进程内账本 + 计数，不改 armed、不碰库。
- `arm`/`disarm`：只 `Kv.putBool(Keys.SNS_WRITE_PROBE, ...)`（**模块自有配置库**）+ 改内存 `@Volatile armed`。
- 全部：不改 `ContentValues`、不改 SQL、不改返回值、不调 `SnsGuard.refresh()`、不写任何 `ANTIREVOKE_*` key、不 import/不改 `SnsSqlRewrite.kt`。

[DONE] S4 EntryReceiver/Keys

## [DONE] S5 单测 + 变异验证

**单测文件**：`wxgroup/app/src/test/java/com/lx/wxgroup/feature/SnsWriteProbeTest.kt`（新建，纯 JVM、JUnit4、零 Android 依赖；`@Before` 复位账本单例 + `setArmed(false)` 隔离）。**31 个用例**。

**用例矩阵**（T1 逐条覆盖）：
- `parseClear9`（11）：真机 `& -9` 原文（userName/64 个白名单/snsId 下界/无 createTime 窗口）、**`& -1` 识别成「已改写」而非「无写事件」**（防假阴性关键）、`& -3`→null、加位写 `sourceType=10`→null、SELECT/DELETE/其它表/`updateXxx`→null、NULL/空/空白→null、大小写+空格变体、WHERE-only 同名表达式→null、区间上下界、`& -1`+user+区间。
- `hitOlder3d` 判据（10）：`createTime==T0` 不算三天外（严格 `<`）、10 位秒接受、**13 位毫秒不得混淆**、区间开闭（lo/hi 边界、null 无界）、userName 不匹配/空 target、`hitOlder3d` 混合 8 行只命中 2 行、`rowHit` 三判据 `&&`。
- 只读 SQL 构造（4）：`hex(content)`/`hex(attrBuf)` 且禁 `cast(`、userName 注入 `x' OR 1=1 --` 转义成 `x''`、`sqlQuote`、无 `;` 且严格 `<`/`>`。
- 账本 / 三态（6）：**去重键含 userName**、同 key 折叠但 seen 不去重、环形封顶 RING=400 淘汰最老 50、`reset` 清账本+计数但不动 armed、默认关 + 开关往返、`verdict` 三态互斥（`seen=0` 优先，⛔ 不并列）。

**基线**：`python tools/gradle_run.py testDebugUnitTest` → `BUILD SUCCESSFUL`；读 `app/build/test-results/testDebugUnitTest/*.xml`：**全量 818 用例 / failures=0 / errors=0 / skipped=0**，其中 `SnsWriteProbeTest` 31/0/0。

**变异验证（5 组，全部变红；脚本 finally 自动还原并 assert 还原后文件与基线字节一致）**：

| 组 | 改了哪一行（`SnsWriteProbe.kt`） | 变红用例 | failures |
|---|---|---|---|
| M1 三天外 `<`→`<=` | `isOlder3dSec`: `return createTime < t0Sec` → `<=` | `createTime_equal_T0_is_not_older_3d`、`hit_count_mixed_rows`、`rowHit_combines_three_predicates` | 3 |
| M2 `& -9` 白名单匹配改恒真 | `CLEAR9_SET` 正则 `...(-9|-1)\b` → `sourceType\s*=.*` | `bit1_only_minus3_is_not_target`、`plain_sourceType_write_is_not_target` | 2 |
| M3 区间 `>`/`<` 互换 | `inOpenRange`: `snsId <= lo`/`>= hi` → `>= lo`/`<= hi` | `range_open_boundaries`、`range_null_bounds_unbounded`、`rowHit_combines_three_predicates`、`hit_count_mixed_rows` | 4 |
| M4 去重键去掉 `userName` | `dedupKey`: `kind\|sql\|userName` → `kind\|sql` | `dedup_key_includes_username` | 1 |
| M5 `hitOlder3d` 恒返回 0 | `rows.count{...}.toLong()` → `0L` | `hit_count_mixed_rows` | 1 |

⛔ 防「等价变异假验证」：每组都打在**真正决定结果的那一行**（速查 `:330`），且配了能杀它的边界用例（等于 T0、`& -3`/加位写、开区间端点、同形不同 userName、混合行计数），0 组出现「0 条红」。脚本打印 `ALL 5 mutants restored OK`。

[DONE] S5 单测 + 变异验证

## [DONE] S6 编译自检

按 `docs/开发回路速查.md` §六/§八 + `tools/gradle_run.py`（该脚本已封装 clean PATH 去带空格脏条目 + `JAVA_HOME=G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot` + `--offline` + `GRADLE=gradle-8.9\bin\gradle.bat`，Gradle 不在 PATH）：

| 命令 | 结果 |
|---|---|
| `python tools/gradle_run.py testDebugUnitTest` | `EXIT=0` / `BUILD SUCCESSFUL in 21s` |
| `python tools/gradle_run.py assembleDebug`（⛔ **不跑 build**） | `EXIT=0` / `BUILD SUCCESSFUL in 7s` |

**全量单测通过数**（读 `app/build/test-results/testDebugUnitTest/*.xml`，43 个 XML）：
`tests=818 / failures=0 / errors=0 / skipped=0`；其中 `SnsWriteProbeTest`：31 tests / 0 failures。
这是**变异脚本 finally 还原后复跑**的结果 ⇒ 5 组变异已全部干净还原、源码回到基线。
`assembleDebug` 编译产物自检通过（main 源集含新增 `SnsWriteProbe.kt` + 4 处接线全部编译进 APK）。

⛔ 本轮**未** `adb install`、未装包、未碰真机、未 `kvset`、未 `git add` / `git commit`（装包与真机由主会话负责）。

[DONE] S6 编译自检

## [DONE] ALL

### 改动文件清单（逐文件行数增减，本 lane 实际改动面）

| 文件 | 类型 | 行数增减 |
|---|---|---|
| `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsWriteProbe.kt` | **新建** | +518（untracked，`git diff --stat` 不含，单列） |
| `wxgroup/app/src/test/java/com/lx/wxgroup/feature/SnsWriteProbeTest.kt` | **新建单测** | +350（untracked，单列） |
| `wxgroup/app/src/main/java/com/lx/wxgroup/data/Keys.kt` | 改 | +18 / -0 |
| `wxgroup/app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt` | 改 | +11 / -0 |
| `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsBlockProbe.kt` | 改 | +8 / -0 |
| `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsGuard.kt` | 改 | +7 / -0 |
| `docs/_r49_rc_impl_report.md` | **新建**（唯一允许写的报告） | 分步追加，骨架→S0→…→ALL |

既有 4 个源码文件合计 **+44 / -0**（纯新增，0 删除，未动任何既有逻辑行）；2 个新文件 868 行。
⛔ `SnsSqlRewrite.kt` **零改动**；`R42 现网写时拦截 / bit3 写回 / [已删除]\n 前缀` 零改动。

### `git diff --stat` 输出（原样）

```
 docs/_r48_autoplay_conclusion.md                   | 13 +++++
 ...256\241\350\256\241\345\217\260\350\264\246.md" | 62 ++++++++++++++++++++++
 .../app/src/main/java/com/lx/wxgroup/data/Keys.kt  | 18 +++++++
 .../java/com/lx/wxgroup/entry/EntryReceiver.kt     | 11 ++++
 .../java/com/lx/wxgroup/feature/SnsBlockProbe.kt   |  8 +++
 .../main/java/com/lx/wxgroup/feature/SnsGuard.kt   |  7 +++
 6 files changed, 119 insertions(+)
```

`git diff --numstat`（我方 4 个既有源码）：`18/0 Keys.kt`、`11/0 EntryReceiver.kt`、`8/0 SnsBlockProbe.kt`、`7/0 SnsGuard.kt`。
`git status --short` 里本 lane 的 untracked 新文件：`?? .../feature/SnsWriteProbe.kt`、`?? .../test/.../SnsWriteProbeTest.kt`、`?? docs/_r49_rc_impl_report.md`。

⚠️ **非本 lane 改动声明**：`git diff --stat` 里的 `docs/_r48_autoplay_conclusion.md`(+13)、`docs/派发审计台账.md`(+62)，以及 `?? docs/_r49_*` 中除 `_r49_rc_impl_report.md` 外的一批报告（`_r49_3day_forensics` / `_r49_rc_probe_design` / `_r49_rc_stage1_result` 等），**均为其它 lane / 主会话的在途产物，本 lane 从未触碰、未还原、未修改**。本 lane 未改任何其它源码 / 配置 / 报告 / 文档；⛔ 未 `git add` / `git commit`。主会话做「真机前 diff 交叉复核」时请按上表隔离本 lane 改动面。

### 风险与盲区（诚实登记，留给真机）

1. **行级命中依赖 `snsDb` 已捕获**：`runReadOnlySql` 在 `snsDb==null` 时回 `no sns db yet` ⇒ `resolveT0()` 返回 null ⇒ stat/dump **跳过**行级命中（但账本 `seen/matched/三态` 仍可见）。真机须先让微信刷一次朋友圈开库。
2. **R-C ②「拦住写之后界面能否显示三天外动态」本轮不解决**：探针只回答 ① 行级命中，② 留给真机人工判读（W5 已写明）。
3. **`hitOlder3d` 是判据的纯函数镜像**，导出查库用 `buildHitCountSql` 生成的**等价** SQL；两者一致性靠单测锁住，**未做真机对账**。
4. **seen/matched 口径**：seen = 所有 SnsInfo 相关 UPDATE + EXECSQL 次数；matched **只**统计 `parseClear9` 非 null（`& -9`/`& -1` execSQL）。UPDATE 通道记 seen 不算 matched ⇒ `seen>0 & matched=0` 时须结合 stat 的 `execSql=` 计数判读，别把「只有 UPDATE 写事件」误读成「判据不识别」。
5. **`& -1` 在真机 before 里通常不出现**：探针接 `noteSql`，拿到的是改写前 `& -9` 原文；`parseClear9` 对 `& -1` 的识别是纯函数层健壮性兜底（防假阴性契约），由单测覆盖、**未在真机验证**。
6. **KV 持久值 vs 内存开关**：`arm` 写 KV 持久值，但运行时以进程内 `armed` 为准、冷启默认 false ⇒ 冷启后 `kv=true` 但 `memArmed=false`，须重新 `arm`；stat 同时打印两者供判读。
7. **CV 键集反射**：wcdb 的 ContentValues 若无 `keySet()` 则 `cvKeys` 为空（"能取到就取"），不影响核心判据（SQL 原文 + `parseClear9`）。
8. **白名单解析依赖 `sourceType in (...)` 形态**：若真机 SQL 换成 OR 链，`sourceTypes` 为空，但区间/userName 仍能解析，**不影响行级命中**（命中 SQL 不依赖白名单字段）。
9. **本轮 0 真机**：只到 `assembleDebug` 编译自检。探针默认关 ⇒ 装包后现网行为零变化，须真机 `arm` 后才有观测。

### 给主会话的「装包前复核要点」

1. **`git diff` 正文交叉复核**（CLAUDE.md §四⑥）：按上表隔离本 lane 改动面；确认 `SnsSqlRewrite.kt` 零改动、既有 4 源码是 +44/-0 纯新增；⛔ 别把其它 lane 的 `docs/_r48_*` / `派发审计台账` 计入本 lane。
2. **核两处 armed 短路位置**：`SnsGuard.kt:302`（`snsBlockGuard.before` 最前，`onSnsUpdateBefore` 之前）、`SnsBlockProbe.kt:564`（`noteSql` 首行、`sqlSns++` 之前）—— 关时**除一个 boolean 判断外零动作**。
3. **核防假阴性命门**：`noteSql` 的 `sql` 是**改写前原文**（`SnsGuard.kt:884-891` 传 `sql=sql` 局部变量，`applySqlRewrite:909` 只改 `param.args[0]`）；⛔ 绝不能把探针移到 after / 改读 `param.args[0]`。
4. **真机操作顺序**：`--es op writeprobe --es sub arm` → 让微信刷一次朋友圈（开库）→ 进好友主页 → `sub=stat`（看 seen/matched/verdict + `hitOlder3dTotal`）→ `sub=dump`（明细，封顶 60）→ 取完证 `sub=disarm`。
5. **三开关独立**：临时全放行仍须 `antirevoke.sns` 与 `antirevoke.sns_block` **两个都关**；`sns.write_probe` 是第三个独立开关，不与它们耦合、不改 R42 行为。
6. **三档边界按 S1 表判读**：冷启账本清零是**预期非 bug**；已写进微信库的位是磁盘状态、不随开关/冷启回滚；⛔ 复位 ≠ 返回。
7. **② 界面显示**需人工判读，不靠账本；账本只证明「写事件行级命中三天外行」。

### 红线自检（返回前逐条确认）

- **进入边界**：`SnsWriteProbe` 默认关（`Keys.SNS_WRITE_PROBE` 默认 false + `Core.armed` 默认 false），关时 `SnsGuard`/`SnsBlockProbe` before 里除 `isArmed()` 一个 boolean 判断外零额外动作。✅
- **返回边界**：账本进程内，切页/刷新/同步只追加不重置、不错乱；冷启清零是预期。✅
- **复位边界**：`disarm` 后账本停止增长（`isArmed()` 短路，明细保留供末次 dump）；⛔ 无残留任何对微信库的写入；⛔ `SnsSqlRewrite.kt` 未改。✅
- **全只读**：探针不写微信库、不改 `ContentValues`、不改 SQL、不改 `param.args`、不改返回值；导出只走 `runReadOnlySql`(select 白名单) + `Kv.putBool`(模块自有配置库)。✅

[DONE] ALL
