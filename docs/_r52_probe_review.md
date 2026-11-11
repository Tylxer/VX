# R52 Pro 独立交叉复核报告（只读取证）

- 审查对象：`_cache/r52_probe.diff`（624 行，唯一输入）
- 审查对象构成：前半 = 两个已跟踪文件 diff（EntryReceiver.kt / AutoHdImg.kt，其中 EntryReceiver 混有 R49 阶段二 SnsWriteProbe 的 11 行，不计入本轮）；后半 = `git diff --no-index /dev/null MediaTableGuard.kt`（untracked，R51 新建，无 HEAD 版）
- 红线纪律：本复核**只读取证** —— 未改任何源码 / 未碰 adb / 未装包 / 未跑 gradle / 未 kvset / 未写微信库
- 二元结论：**可以装包**（详见末尾）
- P0 必改项：**0 条**（详见末尾）

## C1 零拦截语义改动（最高优先）
**PASS**

- `param.result` 赋值点实证：Grep 实际源码 `MediaTableGuard.kt` 全文，只有 2 处**赋值** —— `:274` `param.result = null`（execSQL，对应 diff:486）、`:277` `param.result = FAKE_ROWS`（delete，对应 diff:489）；另 `:54`/`:272` 仅为注释文本（`= null`/`= 1` 的说明），非赋值。赋值内容 / 位置 / 所处 `if (execSql) … else …`（diff:483-490）原样。
- 6 条放行分支全部是**不置 result 的裸 return**（每条只多一个纯 `LogX.i` 的 `observe()`）：diff:444 `!enabled`、:448 `a==null`、:452-455 `p==null`、:458-461 非目标表、:467-471 `tokens.isEmpty()`、:476-480 `!shouldBlock`。逐行读 diff 无任何 `param.result`。
- 注册序实证 `HookEntry.kt:224` `VoiceGuard.install` → `:225` `MediaTableGuard.install` → `:243` `SnsGuard.install`，与任务给的 `delete`: VoiceGuard→MediaTableGuard→SnsGuard 一致。⛔「设置 `param.result` ⇒ returnEarly ⇒ 跳过同方法后续 before callback」的风险面与现网**完全相同**：本轮未新增赋值点、未把任一放行分支改成置 result、未改 `install()` 内 `hookAllMethods` 注册（diff:342-347）。
- 白名单 `MediaDeleteLogic.kt:64` `{imginfo2,videoinfo2,wxfileindex2}`（Grep 实证），不含 `voiceinfo` ⇒ 与 VoiceGuard/SnsGuard 天然不争同一条 delete。
- `[推断]` 边界：`MediaTableGuard.kt` 是 untracked 全文（diff:207-212 `new file` / `--- /dev/null`），无 HEAD 版可逐字比对，故「两处赋值与 R51 逐字相同」依据 a_impl T3(:140) 自查 + diff 内 ⛔ 原样注释断言；但「放行分支不置 result」在 diff 里可直接自证，不依赖该推断。

[DONE] C1

## C2 限流是否把证据挤掉
**PASS**

- 两张独立 map 实证：diff:321 `logAt`、diff:324 `obsLogAt`（各自 `ConcurrentHashMap<String,Long>()`，注释明写「⛔ 与 logAt 分离」）；`throttledLog()` → `emitThrottled(logAt, …)`（diff:592-594），`observe()` → `emitThrottled(obsLogAt, …)`（diff:606-613），共用 `emitThrottled`（diff:616-623）。
- 限流参数：`MAX_LOG_KEYS = 64`（diff:286）、`LOG_TTL_MS = 60_000L`（diff:287）；`emitThrottled` 逻辑 diff:619-621：已有 key 且未满 60s ⇒ return；**新 key** 且 `map.size>=64` ⇒ return；否则 `map[key]=now` 才 `LogX.i(lazyMsg())`（消息体只在过节流后拼）。
- ⛔ 不会挤掉证据：热路径非目标表高基数 `obs|reason|table` key 最多占满 `obsLogAt` **自身** 64 槽，只让**后续新组合**的观测行打不出（保守降级）；`blocked`/`err`/`cb-del`/`cb-sql` 走 `logAt`（仅 4 个固定 key，diff:320 注释），且 `emitThrottled` 只挡新 key、已有 key 过 60s 仍能再打 ⇒ 证据行永远打得出来。`MTG stat` 是广播直接 `LogX.i(stat())`（diff:38/41），**不经任何节流 map**，不受影响。
- ⚠️ `[需真机]` 残留（非代码缺陷、报告已写）：LogX 5000 行环形覆盖（diff:60 注释）+ `K7 perf` 每秒刷屏会**按时间**冲掉已打出的证据行 —— 属取证纪律，a_impl T4 #9(:182)、b_impl T3 #9(:200)、acceptance §0/§5 均要求「测前 `logclear`、点开立刻 `dev.py q`」。

[DONE] C2

## C3 op=mtg 分支安全
**PASS**

- 分支位置：diff:35-45 `"mtg" -> { … }` 在 `when(op)` 内，紧跟 `"hdimg"`（diff:26-28 上下文行、`op=hdimg` 一行未改）之后、`else`（diff:46 起）之前；a_impl T1(:54) 记 `when(op)` 末尾 `return`。
- `sub` 拼错安全：`when(sub)` 显式 `"stat"`（diff:38）/ `"reset"`（diff:39-42），`else -> LogX.i("MTG unknown sub=$sub")`（diff:43）**只读日志**，⛔ 不 fall-through 到 `kvset`/`writeprobe` 等写库分支（对照 a_impl T1:69 `snsg sub=srcfix` 的 `sub2` 坑掉进 `else->run()`）。防坑说明见 diff:32-34 注释。
- `stat` 打印原文且前缀恒 `MTG stat`：diff:38 `LogX.i(MediaTableGuard.stat())`；`stat()` 返回串以 `"$TAG stat enabled=…"` 起头（diff:382），`TAG = "MTG"`（diff:273）⇒ `dev.py q 'MTG'` 与 `installed`/`blocked`/观测行一把抓。
- `resetLedger()` 只清账本：diff:397-405 逐行 `seen/matched/blocked/skipped.set(0)` + `events/logAt/obsLogAt.clear()`；⛔ `enabled`/`cachedTokens`/`gen`/`lastIssuedMs`/`mediaPoolRef` 一行未动（diff:390-396 注释 + 代码可证）。复位后回打 `stat()`（diff:41）可一眼核对 `gen/enabled/tokens` 保持原值。
- 混入的 R49 SnsWriteProbe 11 行（diff:11-21，`"writeprobe"` 分支）与 `"mtg"` 不冲突；`SnsWriteProbe.kt:342/410` 实证 `object SnsWriteProbe` / `fun export(sub)` 存在 ⇒ 不会因缺符号编译失败。⛔ 未把那 11 行当本轮改动审。

[DONE] C3

## C4 HDIMG trace 触发条件与真实现象是否一致
**PASS** —— 本轮「最容易白装」的一点已排除

- 现场原文：`HDIMG fired msgId=19640543 localId=0 t=0 img=null`（正常图 `img=SERVERID://… localId=875524`）。
- **`img` / `imgPath` 口径实证（读 `AutoHdImg.kt:411-498` 源码）**：`:421` `val img = storage?.let { … o2(localId) }` 是 ImgInfo 对象；`:424` `val imgT = … getIntField(img,"t")`；`:425` `val imgPath = imgPathOf(img)`（`imgPathOf` 定义 `:306-315`，遍历 String 字段取第一个含 `://` 或长度 24..40 的值）；**`:482` `LogX.i("$TAG fired … t=$imgT img=${imgPath?.take(36)} …")` ⇒ 日志字段 `img=` 打印的值就是 `imgPath` 变量**（字段名与变量名不同名，但确为同一值），`t=` 就是 `imgT`。
- 触发判据（diff:173 / 源码:399）：`if (imgPath != null && imgPath.isNotBlank() && localId != 0L) return`，即 `imgPath==null || isNullOrBlank() || localId==0` 才继续；传入的是**同一个** `imgPath`（diff:200 / 源码:497 `traceIfAbnormal(msgId, localId, imgPath, imgT, ctorTrace)`），形参名也叫 `imgPath`（diff:169 / 源码:395），日志行 `img=${imgPath?.take(48)}`/`t=$imgT`（diff:176-177）与 `HDIMG fired` 同口径。
- ⇒ 现场 `img=null`（`imgPath=null`）+ `localId=0` **两个条件都命中，探针必触发**，不会「永不触发」；正常图 `imgPath` 非空 + `localId≠0` ⇒ 三条件全 true ⇒ 第一行 return，**零额外日志、不会误触发**。日志 `img` 字段 ↔ `imgPath` 变量 ↔ trace 形参三者同值，`t` ↔ `imgT` 亦同值，**无口径错**。`ctorTrace` 构造条件 `localId == 0L`（源码:264）与现场一致 ⇒ 第一行带真实构造参数/栈，不是 `n/a(localId!=0)`。
- ⚠️ `[需真机]` 次级边界（非 P0，b_impl §2.7 已诚实标注）：① 若出现「`imgPath=null` 但 `localId≠0`」形态，第一行打 `ctor=n/a(localId!=0)`、db 行照打；② 若某正常图 `imgPathOf` 启发式返回 null 而 `localId≠0`，会误触发一次（被 `traceSeen` 去重到 64 条）——既有 `imgPathOf` 边界、非本轮引入；③ `svc.c`（:475）若抛异常则 `fire()` 提前结束、:497 不执行，但现场有 `HDIMG fired`（说明 r 有返回值）⇒ 目标样本不受影响。

[DONE] C4

## C5 HDIMG 前置与放置点
**PASS**

- 前置开关：`AutoHdImg.kt:192` `if (!Kv.getBool(Keys.CHAT_AUTO_HD_IMG)) return` 是 `ctorHook.afterHookedMethod`（:190）**第一行**，挡在 `HDIMG sig`(:241)、`HDIMG fired`(:482)、`traceIfAbnormal`(:497) 全部之前 ⇒ 开关关时整条 ctorHook（含 `pool.execute{ fire() }`）不执行。acceptance §0(:15) 记 00:47 已 `kvget` 回读 `chat.auto_hd_img=true`；`Keys.kt:442` 默认 `"false"`（Grep 实证），装包 `-r` 不清 KV ⇒ 现场须保持开。`auto_hd_allow/exclude` 默认 `"[]"`（`Keys.kt:445-446`）⇒ 名单分支 :436-472 整段跳过。
- 放置点：源码 `:497` `traceIfAbnormal(...)` 在 `HDIMG fired`(:482) 之后、`if (r == true)`(:480)/`else`(:483) 两条 `svc.c` 分支**之外**（if/else 块闭合后）⇒ r 真/假都能看到。与 diff:190-201 一致。
- ⚠️ `[需真机]` 已知提前 return 不走 :497：`imgT==1`(:426 `already`)、名单拦截(:463-470 `skipScope`)。默认 `"[]"` ⇒ 被撤回图必到 :497（b_impl §2.7:122-124 / §4.5:275 已标）。

[DONE] C5

## C6 DB 只读查询安全
**PASS**

- 句柄/方法来源实证：`AntiRevoke.kt:851` `internal fun msgQueryHandle(): Pair<Any, Method>?`（Grep 实证）⇒ diff:136 `val (db, m) = h` 解构类型正确；`db` 是 AntiRevoke 写库 hook 已捕获的 `msgDb`、`m` 是已缓存 `rawQueryMethod`（`AntiRevoke.kt:858/1061-1073`）⇒ ⛔ 不新开连接、不扫类、不引入耗时反射。
- 只读：`AntiRevokeHint.kt:62` `internal const val BYPASS = "/*w*/"`（Grep 实证）；diff:142 / 源码:368 `sql = BYPASS + "select imgPath from message where msgId=? limit 1"` —— 纯 `select`，无 insert/update/delete/execSQL，⛔ 不写库。`m.invoke(db, sql, bind)`（:143/:369）与既有 `AntiRevoke.kt:1033` 同款写法（`bind` 静态类型 `Any`，Kotlin 不展开数组 ⇒ rawQuery 收到 `[sql, bindArgs]`）；`bind` 按 `m.parameterTypes[1]` 认 `String[]`/`Object[]` 两个 WCDB 重载（diff:139-141）。
- 句柄 null 明确打 `no db handle`（diff:135 / 源码:361），**不静默**；查询任一失败 `.getOrElse { "query err …" }`（diff:154 / 源码:380）；`null cursor`/`no row`/`null col` 各有明确返回（diff:144-149）；cursor `finally { runCatching { close() } }`（diff:150-152）。
- ⛔ 全程 `runCatching`，不抛到 `fire()` 外层；`XposedHelpers.callMethod` 仅 3 次读 cursor。

[DONE] C6

## C7 热路径开销与 try/catch
**PASS**（附 2 条 `[推断]` 观察项，均非 P0）

- `Throwable().stackTrace` 只在 `localId == 0L` 时抓一次（源码:264 → `ctorTraceOf` :341），正常图 `ctorTrace=null`，零栈抓取、零字符串拼接；`ctorTraceOf` 每入参 `.take(40)`、整行 `.take(600)`（diff:107/121）。
- `traceSeen`（diff:64 `ConcurrentHashMap.newKeySet<Long>()`）按 msgId 去重、上限 64（diff:174 / 源码:400 `if (traceSeen.size >= 64 || !traceSeen.add(msgId)) return`）；DB 只读查询挂在去重闸门**之后**（源码:405 在 :400 之后），不是每次点击都跑。
- 异常吞噬实证：`instInt`/`instLong` 内部 `runCatching{}.getOrNull()`（源码:292-296），`a.toString()` 包 `runCatching`（:333），`readMsgImgPath` 全 `runCatching`（:363-380）；`traceIfAbnormal` 位于 `fire()` 尾部，而 `fire()` 被 `runCatching { fire(...) }.onFailure{…}` 包裹（源码:270）⇒ ⛔ 不外泄到宿主、不阻断 `SQLiteDatabase.delete/execSQL` 上后续 callback（R42/语音拦截）。
- `fire()` 只把 2 参改 3 参、多传一个只读 `String?`（diff:185-186 / 源码:411），`param.args` 未被改（`ctorTraceOf` 只读，diff:76/99 注释）。
- ⚠️ `[推断]` 观察项 1：`traceIfAbnormal` 若意外抛异常，会被 :270 的 `onFailure` 记为 `fail++` + `done.remove(msgId)` + `HDIMG fire err`，把一次**已成功触发 `svc.c`** 的下载误记为失败并允许重试 —— 但 trace 内部三个可抛点（LogX.w / readMsgImgPath 全 runCatching / `lastTalker.take`）实际都不抛，且只影响模块自有计数器、不影响宿主。
- ⚠️ `[推断]` 观察项 2：`ctorTraceOf` 在微信主线程 `afterHookedMethod` 内、无外层 try/catch 包裹；虽内部已逐点 runCatching，`StringBuilder`/`Throwable` 的极端情形（OOM）理论上仍可外泄。属既有 `afterHookedMethod` 无兜底设计，本轮未扩大。

[DONE] C7

## C8 编译风险
**PASS**

- Kotlin 语法/import：MediaTableGuard.kt import 全量（diff:215-225）覆盖 `SystemClock/Keys/Kv/MediaDeleteLogic/LogX/XC_MethodHook/XposedBridge/XposedHelpers/XC_LoadPackage/ConcurrentHashMap/AtomicLong`；`VoiceFileGuard` 同包、`HashSet`/`Sequence` 自动。AutoHdImg.kt 新增用 `java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()` **全限定名**（diff:64，无需 import）、`AntiRevoke`/`AntiRevokeHint` 同包 `com.lx.wxgroup.feature`、`XposedHelpers` 既有 import ⇒ ⛔ 无需新增 import（与 a_impl T5:225 / b_impl §2.7:135 一致）。`when(sub)`、`runCatching`、尾随 lambda `emitThrottled(obsLogAt, …) {…}`、`ifBlank`、`Array<out Any?>`、字符串插值均合法；`ifBlank` 在仓库既有代码（`ForceForward.kt:496`）已用（b_impl §2.7）。
- 符号存在性 Grep 实证：`MediaDeleteLogic.kt:145/241/267` `isTargetTable/shouldBlock/verdict`、`AntiRevoke.kt:851` `msgQueryHandle(): Pair<Any,Method>?`、`AntiRevokeHint.kt:62` `BYPASS`、`SnsWriteProbe.kt:342/410` `object SnsWriteProbe`/`fun export`。`MediaDeleteLogic.verdict(s,m)` 返回 `Verdict`，stat() 用字符串插值 `${…}`（diff:384）合法。
- 未使用变量：新形参/局部变量全被使用（`ctorTrace`→fire→traceIfAbnormal、`imgT`→`t=$imgT`、`type`→`type=$type`、`lazyMsg`→`LogX.i(lazyMsg())`、`thisObj/args`→ctorTraceOf）。`emitThrottled` 单一签名（diff:616）、原 `throttledLog(key:String,msg:String)` 4 个调用点（diff:415/425/492/499）仍 `(String,String)`，无重载歧义（a_impl T5:226）。
- ⛔ 嵌套块注释（R51 `Unclosed comment` 教训）：Grep `/\*` 扫全 diff，唯一非 `/**` 的 `/*` 是 diff:130 `` `AntiRevokeHint.BYPASS`（`/*w*/`）`` —— `/*` 与 `*/` **成对**，Kotlin 块注释支持嵌套（depth 1→2→1→0，外层 KDoc 的 `*/` 在 diff:133 正常闭合）⇒ 不会重现 `` `feature/*` `` 缺尾 `*/` 的 EOF 误报。
  - ⚠️ `[推断]` 提醒：该写法依赖 Kotlin 注释嵌套语义 + `/*w*/` 成对；若改成 `/*w`（缺尾）会立刻重现 R51 故障。本轮**无需改**。
- 两 lane 并发写不同文件无共享符号冲突：lane A 独占 `MediaTableGuard.kt`+`EntryReceiver.kt`，lane B 独占 `AutoHdImg.kt`；`EntryReceiver` 的 `"mtg"` 只以全限定名引用 `MediaTableGuard.stat/resetLedger`，`"hdimg"` 分支（diff:26-28）原样；lane B 只引用既有 `AntiRevoke`/`AntiRevokeHint`。无同名 top-level 声明、无互相引用。
- ⚠️ `[需真机]`：未跑 gradle（本复核红线），编译结论为静态推导；R49 混入 11 行调用的 `SnsWriteProbe.export` 已实证存在。

[DONE] C8

## C9 三档边界表
**PASS**（附 1 条非 P0 字面口径不一致提示）

> 复核后注：两 lane 在初次交付后各自修订了报告（#11 及 lane A 协作修订）。以下按**当前磁盘版本重新实测**，结论不变。

- **a_impl T4（当前版，结构行号未变）**：① 进入 4 类单列（:161-164）；② 返回含 **②-a TTL 保护池 10min（时间维度，单列）:166**、**②-a2 TTL imgAnchor 30min（时间维度，单列）:167**、**②-b 冷启进程维度（单列）:168** —— :154 明写「TTL 与冷启各自单列、不许合并」；③ 复位档 :170 标题「逐条分列 · 每条写清除条件与残留 · ⛔ 不写『同返回』」，10 条逐条（:174-183），#10 :183「已被删的 DB 行 / 文件不可回滚」原文保留。
  - ✅ 字面口径（已闭环）：#9 :182 标题现为「**日志 5000 行环形覆盖导致旧证据消失** ⭐ 观测专属（项目简称；真实行为＝满 5000 行 `writeText` 整文件清空重来 + 留 `log rotated` 标记 + `lines=0`，⛔ 非逐行 FIFO）」—— r52-probe-a 已按建议补回字面短语（本 lane 重读实测 :182），与 b_impl :209 一致；精确化说明与「`log rotated` 标记可排除『钩子没被调用』」排除项判据全部保留，两者不冲突。⇒ C9 **字面与实质均满足**。
- **b_impl T3（当前 354 行版，重读实测）**：②-A TTL 时间维度**单列** :176、②-B 冷启进程维度**单列** :186（:183-184 明写「不许合并成一句『过一会儿就好了』」）；③ 复位 :197 标题「逐条：清除条件 + 残留，⛔ 不写『同返回』」，**10 条逐条分列** :201-210、每条两列齐全；#9 :209 标题「**日志 5000 行环形覆盖**导致旧证据消失」**字面短语保住**（真实行为精确化只作括注），#10 :210「已被删的 DB 行 / 文件不可回滚」保留。
- **C12（b_impl T5 当前版重读）**：:306 标题「≤5 行，对着用户能看见的事实写」、:308-309 明写「⛔ 绝不拿 `seen/fired/row=0/blocked` 等内部计数当结论」；:311-315 仍 **5 条**，每条以「用户看不到大图 / 能看到大图」收口，:315 收口句「三者都以『界面能否看到大图』收口，⛔ 不以任何计数器收口」原文保留（仅追加半句「均须先过第 1 条的 `log rotated` 前提」）。第 1 条新增「先确认无轮转标记」是**取证前提**（有标记＝判不了），⛔ 非把内部计数当结论。
- ⇒ 两份报告 ①/②/③ 齐全、TTL 与冷启各自单列、复位逐条分列、#10 齐全、判据收口到用户可见事实；**C9/C12 仍 PASS**，二元结论不变。

[DONE] C9
[DONE] 复核（协作修订后重读）

## C10 回滚路径
**PASS**（附 1 条文档口径冲突提示，非 P0）

- a_impl T5（:189-201）：明确 `MediaTableGuard.kt` **untracked（`??`，R51 新增、从未 commit）**、无 HEAD 版可 `git checkout --`（:198）、⛔ **别 `rm` 整个文件**（R51 两个 hook 要保留）、只能逐段移除本 lane 新增；`EntryReceiver.kt` 有前轮未提交改动（R49 `op=writeprobe`）且 lane B 并发改 `AutoHdImg.kt` ⇒ ⛔ 不能整文件 checkout、只精确删 `"mtg"` hunk（:199）。⛔ 禁 `git checkout .` / `git reset --hard` / `git clean -fd`（b_impl :224-225、acceptance §7 :78-79 明列）。
- b_impl T4（:209-227）：本轮唯一源码改动 `AutoHdImg.kt` 用单文件 `git checkout -- …/AutoHdImg.kt`（HEAD 干净，:217-218），⛔ 严禁三条全量回退命令（:224-225）。
- acceptance §7（:73-79）：同样明确 `MediaTableGuard.kt` 未跟踪、`EntryReceiver.kt` diff 混有 **R49 阶段二 SnsWriteProbe 的 11 行**、只撤本轮新增分支、禁三条全量回退。
- ⚠️ `[提示]` 文档口径冲突（不影响装包、装包前建议统一）：acceptance §7 :76 写 lane A 回滚「`MediaTableGuard.kt`（未跟踪文件，**删掉即回 R51 之前状态**）」，与 a_impl T5 :198「⛔ **别 `rm` 整个文件** —— R51 的两个 hook 要保留」**相矛盾**；若照 acceptance 执行会把 R51 媒体表 delete 拦截一并删除，超出「只撤本轮观测」范围。以 a_impl 口径为准。

[DONE] C10

## C11 现网零影响
**PASS**（附措辞澄清）

- 默认开关值未改：diff 中**无 Keys.kt hunk**；Grep 实证 `Keys.kt:442` `chat.auto_hd_img="false"`、`:445-446` `auto_hd_allow/exclude="[]"`、`:457` `antirevoke.media_delete="false"` 均为既有默认值。
- 正常图片路径零额外日志：`localId≠0` ⇒ `ctorTrace=null`（源码:264，零抓栈/零拼串）、`traceIfAbnormal` 第一行 return（:399）⇒ 零 `LogX.w`、零 DB 查询；只多 :264 一次 `if` 比较 + :497 一次调用 + :399 三次比较。
- `onBefore` 开关关时第一行即 return（diff:444 `if (!enabled) { observe(...); return }`）：不做 SQL 解析、不改 `param`、不查库、不反射、不文件 IO、不新增 `Kv.getBool`（diff:254 明写 `Kv.getBool` 只在 `install()`/`invalidate()`）。
- ⚠️ 措辞澄清：R51 原注释的「零字符串解析 / **零对象分配**」本轮严格字面已**不成立** —— `observe()` 会构造 1 个短 key `"obs|disabled|-"` + 1 次 `ConcurrentHashMap.get` + 1 次 `SystemClock.elapsedRealtime()`，60s 节流通过时打 1 行 `LogX.i`（a_impl T5:205-208 已如实披露、diff:250-254 顶部注释已同步更新）。这是为「区分钩子没被调用 vs 开关关」付的**有界极小代价**，无拦截语义变化、无 param/DB 触碰 ⇒ 就「现网零语义影响 + 正常图零额外日志」判 PASS。

[DONE] C11

## C12 判据纪律
**PASS**

- 主判据对着用户可见事实：`_r52_probe_acceptance.md:6`「⛔ 主判据始终是**用户能不能看到完整大图**，不拿任何内部计数当结论」；§5(:58-62) 三种读法全部以「用户看不到大图」收口；§6(:69)「用户可见判据同为『能否看到大图』」。
- b_impl T5（:291-300，≤5 行）：:294 明写「⛔ 绝不拿 `seen/fired/row=0/blocked` 等内部计数当结论」，:296-300 每条以「用户看不到大图 / 能看到大图」收口，:300「三者都以『界面能否看到大图』收口，⛔ 不以任何计数器收口」。
- a_impl 是**实现/账本视角**报告：T4 :155 明写「本表只描述账本与日志证据的状态迁移」、T5 :228 是探针**验活**判据（「应出 ≥1 行」），⛔ 未把 `seen/matched/blocked/skipped` 当「图片能否打开」的结论。
- acceptance §4(:53-54) 的 `n_before/n_after`、`MTG stat` 的 `seen/skipped` 仅用于取证过程判「行是否被删 / 钩子有没有被调用」，最终 §5 仍以「能否看到大图」收口。
- ⛔ 全三份报告未发现把内部计数直接当作用户可见结论的表述。好友一律昵称 **verity** / **测试群聊** / **文件传输助手**，无裸 wxid。

[DONE] C12

## 结论 / P0

### 二元结论：**可以装包**

C1~C12 全部 **PASS**，**P0 必改项 = 0 条**。

判定依据（最高优先 C1 与最易白装 C4 均已实证排除）：
- 拦截语义零改动：`param.result` 赋值仍只有 2 处且原样，6 条放行分支仍是不置 result 的裸 return，注册序未动；
- 观测链路自洽：`op=mtg` 分支只读安全、`resetLedger()` 只清账本、两张节流 map 分离不挤掉证据；
- `HDIMG trace` 触发口径与现场 `img=null localId=0` 一致（日志 `img` 字段 = `imgPath` 变量 = trace 形参），探针必触发、正常图不误触发；
- DB 查询只读、异常全吞、编译符号齐备、嵌套注释成对、两 lane 无共享符号冲突、三档边界表/回滚/判据纪律齐全。

### 装包前次要提示（均非 P0，不影响本次装包）
1. `[提示]` **文档口径冲突**：acceptance §7:76「`MediaTableGuard.kt` 删掉即回 R51 之前状态」 vs a_impl T5:198「⛔ 别 `rm` 整文件，R51 两个 hook 要保留」——以 a_impl 为准，回滚只撤本轮观测。
2. `[需真机]` **验收窗口是最大残留风险**：`chat.auto_hd_img` 默认 `false`（`Keys.kt:442`）、装包 `-r` 不清 KV，须保持现场 `true`（acceptance §0 已 kvget 回读）；`auto_hd_allow/exclude` 保持 `[]`；微信前台；点开后**立刻** `dev.py q`，防 LogX 5000 行环形 + `K7 perf` 每秒刷屏把证据冲掉。
3. `[推断]` `traceIfAbnormal` 若意外抛异常会被 `fire()` 外层 `runCatching` 记为 `fail` + `done.remove`，污染模块自有计数（不外泄宿主）；可抛点已逐点 `runCatching`，概率极低。
4. `[推断]` `/*w*/`（diff:130）依赖 Kotlin 块注释嵌套 + 成对，安全；⛔ 别改成缺 `*/` 的形态（R51 `Unclosed comment` 教训）。
5. `[需真机]` 未跑 gradle（红线），编译结论为静态推导；R51 `execSQL` 置 `param.result = null` 是否真跳过方法体仍未取证（`MediaTableGuard.kt` 注释 diff:264-268 已标 `[需真机]`）——**R51 既有行为、本轮零改动，不构成本轮装包阻断**。
6. ✅ **已闭环**（原非 P0 提示）：协作修订后曾出现「两报告 #9 字面口径不一致」—— b_impl #9(:209) 保留「日志 5000 行环形覆盖」短语、精确化作括注，a_impl #9(:182) 标题一度改为「日志 5000 行写满清空重来…」丢了字面短语。经提醒后 r52-probe-a 已补回（本 lane 重读实测 :182 标题含该短语，括注精确化与 `log rotated` 排除项判据保留），两报告字面一致。C9 **字面与实质均 PASS**；源码零改动，二元结论「可以装包」不变。

### 红线自检
本复核**只读取证**：未改任何源码、未碰 adb、未装包、未跑 gradle、未 kvset、未写微信库；唯一写入文件＝本报告 `docs/_r52_probe_review.md`。`EntryReceiver.kt` 混入的 R49 SnsWriteProbe 11 行未计入本轮改动。不确定项均标 `[推断]` / `[需真机]`。

### 落盘日志（分步）
[DONE] 骨架
[DONE] C1
[DONE] C2
[DONE] C3
[DONE] C4
[DONE] C5
[DONE] C6
[DONE] C7
[DONE] C8
[DONE] C9
[DONE] C10
[DONE] C11
[DONE] C12
[DONE] 结论 / P0
[DONE] 协作修订后重读复核 + 次要提示 #6 同步
[DONE] #6 已闭环（a_impl 补回字面短语，本 lane 重读实测）

[DONE] ALL
