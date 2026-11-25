# R86c B5 arm 入口零生效修复 —— 实现 lane 落码记录（armfix）

> 角色：`r86c-b5-armfix`，Hook 链路取值改动 + 双 talker 语义拆分 + 判据接线锁重设的**实现 lane**（Pro 档）。工作根 `C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`，源码根 `wxgroup/`，Git Bash 用正斜杠。
> 性质 = Hook 链路取值 + 双 talker 语义 + 判据接线锁 ⇒ **Pro 类**（子代理任务）。模型 `HY4-FLASH-FREE`（用户会话临时规则，不写入任何文件）。
> 档位：【源码实证·高】/【真机实证·高】/【dex 实证·高】/【推断·高|中|低】/【UNKNOWN】；【推断】不写成【已证】，每条带文件:行号锚点。
> 硬纪律：只改 `wxgroup/app/src/main/java/com/lx/wxgroup/core/FileSender.kt`、`wxgroup/app/src/main/java/com/lx/wxgroup/probe/B6CheckExistProbe.kt`、`wxgroup/app/src/test/java/com/lx/wxgroup/probe/B6CheckExistProbeTest.kt`、新产物本文档；**禁碰 adb / 禁装机 / 禁设备开关 / 禁跑 gradle / 禁跑 `_junit.sh` / 禁 git commit**（三闸由主会话统一跑）。
> 裁决依据：`docs/_r86c_b5_armroot_verdict.md`（Q2 talker 语义 / Q3 方案 A / Q3.3 六红线 / Q4.3 最小下一步）、`docs/_r86c_b5_fix_note.md` §2（降级判据）、`docs/_r86c_b5_review2.md` Q4.2（现场判据口径）。
> 落盘策略（防打断）：骨架 + §1 写盘 → 三处源码/单测逐处改 → 每步留 `[DONE §n]` → 最后真 `[DONE ALL]`。**先改文件再回报，产物以落盘为准。**

状态进度：
- [x] 骨架 + §1 改动清单（每条 anchor + 改前改后）
- [x] §2 双 talker 语义说明
- [x] §3 单测锁补丁 + 变异表（最终例数）
- [x] §4 六红线逐条核对
- [x] §5 三闸待跑
- [x] §6 诚实边界
- [x] 真 `[DONE ALL]`

---

## §1 改动清单（每条 anchor + 改前 / 改后）

> 全部为【源码实证·高】（改前/改后均以落盘文本为准）；改动动机归属裁决文档的【真机实证·高】根因（E1~E7）与【dex 实证·高】talker 语义（E9~E11），实现 lane 不自称已真机复验。

### §1.1 A1 — `wxgroup/app/src/main/java/com/lx/wxgroup/core/FileSender.kt:167`（含 A4 第 4 参）

- 改前（`:167`）：
  `com.lx.wxgroup.probe.B6CheckExistProbe.armFromForward(ctx.sourceChat, ctx.msgSvrId)`
- 改后（`:167`）：
  `com.lx.wxgroup.probe.B6CheckExistProbe.armFromForward(ctx.srcTalkerPrefix.trimEnd(':'), ctx.msgSvrId, wxid, ctx.srcMsgId)`
- 参数语义：第 1 参 = 源 talker（`srcTalkerPrefix` 真机非空 `filehelper:`，`trimEnd(':')` 去尾冒号归一到 a3 的 `field_talker` 形态）；第 2 参 = `msgSvrId`（19 位服务端 id，外层 gate `:159` 已证 `>0`）；第 3 参 = `wxid`（`send` 形参，`FileSender.kt:132/:148` 现成可达，作 `task.field_talker` → `toUser`）；第 4 参 = `ctx.srcMsgId`（源消息本地 id，`ForwardLogic.kt:428` 定义，`FileSender.kt:213` 已用，A4 供 Q2 回退）。
- 作用域可达性实证：`FileSender.kt:135` `forwardCtx: ForwardLogic.Snapshot? = null`，`:158` `forwardCtx?.let { ctx -> ... }`；`wxid` 为 `send` 形参（`:130-137`）。**不改**外层 `if (ctx.imgLocalId > 0L && ctx.msgSvrId > 0L)` gate（`:159`）。
- 注释（`:160-162` 的「⛔ 绝不能用 ctx.sourceChat」既有警告保留，另在 `:165-167` 上方补 B6X 专属说明）：a3 源 talker 改用 `srcTalkerPrefix.trimEnd(':')`，`field_talker` 用目标 `wxid`，二者不可混用。
- 不涉及 `ForwardCdnFieldHook.arm(ctx.sourceChat, ...)`（`:164`，另一 hook，按硬任务不改）。

### §1.2 A2 — `B6CheckExistProbe.kt:138` + `:144`（`armFromForward` 签名与函数体）

- 改前 `:138`：`fun armFromForward(talker: String, msgSvrId: Long) {`
- 改后 `:138`：`fun armFromForward(talker: String, msgSvrId: Long, taskTalker: String = talker, msgId: Long = 0L) {`
- 改前 `:144`：`arm(talker, msgSvrId)`
- 改后 `:144`：`arm(talker, msgSvrId, taskTalker, msgId)`
- 首行 `if (!enabled) return`（`:139`）与 `if (talker.isBlank() || msgSvrId <= 0L)` skip（`:140-143`）**完全不动**（红线 2：off/on 反向对照自变量唯一）。
- 默认值 `taskTalker = talker` / `msgId = 0L` 保证手工入口零改动兼容。

### §1.3 A3 — `B6CheckExistProbe.kt:156` / `:183-184` / `:268`（`arm` 签名、按方法选参、field_talker 落点）

- 改前 `:156`：`fun arm(talker: String, msgSvrId: Long) {`
- 改后 `:156`：`fun arm(talker: String, msgSvrId: Long, taskTalker: String = talker, msgId: Long = 0L) {`
- 改前 `:184`：`val r0r = runCatching { XposedHelpers.callMethod(o8, m, talker, msgSvrId) }`（`a3`/`Q2` 都传 `msgSvrId`）
- 改后 `:183-185`：`m` 分支选参后 `callMethod(o8, m, talker, idForMethod)`；`a3` → `msgSvrId`，`Q2` → `msgId`（`msgId <= 0L` 回退 `msgSvrId` 兼容手工通道）。保留两条路径的 `runCatching` / `B6X arm try $m ...` 日志结构。
- 改前 `:268`：`put(task, "field_talker", talker)`
- 改后 `:268`：`put(task, "field_talker", taskTalker)`（目标 wxid，stock「toUser=目标会话」语义，E11/Q2.4）。
- KDoc `:133-137` / `:152-155` 与 `:175-178` 注释同步更新为双 talker + msgId 语义（详见 §2）。

### §1.4 单测 — `B6CheckExistProbeTest.kt:84` / `:217-234` / 新增契约锁 + M18/M19

- 改前 `:84`：`"fun arm(talker: String, msgSvrId: Long)" in it`（精确锁旧签名，改签名后必失败）
- 改后 `:84`：精确锁新签名 `"fun arm(talker: String, msgSvrId: Long, taskTalker: String = talker, msgId: Long = 0L)" in it`。
- 改前 `:232`：`sender.any { "B6CheckExistProbe.armFromForward(ctx.sourceChat, ctx.msgSvrId)" in it }` —— **把错误调用精确锁成契约**，正是「三闸全绿却真机零生效」的盲区根源。
- 改后 `wiringPresent()`：正断言新调用串（含 `ctx.srcTalkerPrefix.trimEnd(':')`、`wxid`、`ctx.srcMsgId`）+ 反向断言 `sender` 中不得有 `ctx.sourceChat`/`contextChatTalker` 作为 `armFromForward` 的 talker 入参（锁死不回归）。
- 新增 `dualTalkerWiringContract()`：`put(task, "field_talker", taskTalker)` 存在、`arm`/`armFromForward` 签名含 `taskTalker`/`msgId` 默认参、`arm(talker, msgSvrId, taskTalker, msgId)` 函数体转发、Q2 按 msgId 选参（纯 JVM 源码文本断言，与现有 30 例同构，不实例化探针）。
- 新增真变异 M18（新调用串改回 `ctx.sourceChat` ⇒ 判红）、M19（`field_talker` 改回 `talker` ⇒ 判红），各带 `mutated != src` 前置真改断言 + `assertFalse` 判红。
- 最终例数：30 → **33**（契约 13 → 14，变异 17 → 19）；以实际落盘数为准，详见 §3。

`[DONE §1]`

`[DONE §2a]` — `FileSender.kt:167` A1 调用行改为 `armFromForward(ctx.srcTalkerPrefix.trimEnd(':'), ctx.msgSvrId, wxid, ctx.srcMsgId)`，并补 B6X 专属语义注释；外层 gate 与 `:164` 的 ForwardCdnFieldHook 调用未动。已落盘。

---

## §2 双 talker 语义说明（为什么必须拆开）

> 语义依据来自裁决文档 Q2（talker=源消息存储会话，【dex 实证·高】）与 Q2.4（`field_talker → toUser`=目标会话，【dex 实证·高 + 真机实证·高】）；本 lane 按此接线，不重新裁决语义。

| 参数 | 取值来源 | 语义 / 用途 | 写入落点 | 档位 |
|---|---|---|---|---|
| `talker` | `ctx.srcTalkerPrefix.trimEnd(':')`（本轮真机 `filehelper:` → `filehelper`） | **源消息存储会话**：`a3(talker, msgSvrId)` / `Q2(talker, msgId)` 的查询键，用于 `d3.O8() → r0.u → g9.c(u,"msg","")` 读源 xml | 只进 `:190` `callMethod(o8, m, talker, idForMethod)` | 非空【真机实证·高】；去冒号等价 a3 talker【推断·高】；语义【dex 实证·高】 |
| `taskTalker` | `wxid`（`send` 形参，目标会话） | **目标会话**：stock B6 的 `toUser`，决定自建 checkExist 发给谁 | `:274` `put(task, "field_talker", taskTalker)` | 【dex 实证·高 + 真机实证·高】 |
| `msgSvrId` | `ctx.msgSvrId`（19 位服务端 id） | `a3` 首选入参 + `field_mediaId = "checkExist_$msgSvrId"` | `:190`（a3）/ `:269`（mediaId） | 【源码实证·高】 |
| `msgId` | `ctx.srcMsgId`（源本地 id） | `Q2` 回退入参；`msgId <= 0L` 时回退 `msgSvrId` 兼容手工通道 | `:189` 选参 → `:190` | 【源码实证·高】，真机有效性【推断·中】 |

- **拆开的硬理由**：`a3` 的 talker 参与 `a1.b(talker)` 的表/库选择 + `msgSvrId=?` 行过滤（裁决 E9/E10），必须用源会话；`field_talker → toUser`（E11）是目标会话。两者混用会让自建 checkExist 的 toUser 偏离目标会话【推断·中，可能影响 C2 回包形态】。修复前 `arm()` 把同一 `talker` 同时用于两处，是裁决 B2 的根。
- **不选目标 wxid 作 a3 talker**：目标 wxid + 源 msgSvrId 极可能 `r0=null` 取不到源 xml（裁决 Q2.2，【dex 实证·高 + 推断·高】）。
- **不选 `ctx.sourceChat` 作任何一只**：它由 `currentChatTalker()` 读取 Activity intent 的 `Chat_User` extra，恒空（裁决 E5/E6），是本次真机零生效的直接原因【真机实证·高 + 源码实证·高】。
- **手工通道兼容**：`armFromForward(talker, msgSvrId)` 与 `arm(talker, msgSvrId)` 仍可只传前两参（`taskTalker` 默认 = `talker`、`msgId` 默认 `0L`），`EntryReceiver.kt:313` 的 `B6CheckExistProbe.arm(talker, svrid)` 零改动【源码实证·高】。
- **残余 UNKNOWN（不掩盖）**：`srcTalkerPrefix.trimEnd(':')` 是否等于 stock `msg.C0()` 的原始形态、`a3` 用该 talker 本轮真机未直接命中（真机验证过的是 `Q2(talker, msgId)` 取到 imgLocalId=885441）——见裁决 Q2.3，属【推断·高 / UNKNOWN】，由修复后 arm 自检日志在真机一次性闭合，本 lane 不写成已证。

`[DONE §2b]` — `B6CheckExistProbe.kt:133-146`（KDoc + `armFromForward` 签名/函数体）、`:154-161`（KDoc + `arm` 签名）、`:180-190`（选参注释 + 按方法选参）、`:274`（`field_talker` 用 `taskTalker`）已落盘；`EntryReceiver.kt:313` 手工调用因默认参零改动。已落盘。

---

## §3 单测锁补丁 + 变异表（最终例数）

### §3.1 锁补丁逐条（全部 `wxgroup/app/src/test/java/com/lx/wxgroup/probe/B6CheckExistProbeTest.kt`）

1. **拔掉盲区根源（`:232` → `wiringPresent()`）**：改前把错误调用 `"B6CheckExistProbe.armFromForward(ctx.sourceChat, ctx.msgSvrId)"` 精确锁成契约；改后由 `fileSenderArmWiringValid()` 校验：① 新串 `B6CheckExistProbe.armFromForward(ctx.srcTalkerPrefix.trimEnd(':'), ctx.msgSvrId, wxid, ctx.srcMsgId)` 必须存在；② 反向断言 `sender` 中不得有 `B6CheckExistProbe.armFromForward(ctx.sourceChat` 或 `B6CheckExistProbe.armFromForward(contextChatTalker`（不限定该行其它内容，锁死不回归）。
2. **`probeShapeMatchesContract()` arm 签名锁（原 `:84`）**：旧锁 `"fun arm(talker: String, msgSvrId: Long)"` 已不适用新签名（会误判红），改为逐字锁 `"fun arm(talker: String, msgSvrId: Long, taskTalker: String = talker, msgId: Long = 0L)"`。
3. **新增 `dualTalkerWiringContract()`（第 14 条契约锁）**：`dualTalkerWiringValid()` 校验 `put(task, "field_talker", taskTalker)`、两条新签名、`arm(talker, msgSvrId, taskTalker, msgId)` 函数体转发、Q2 选参串 `if (m == "a3") msgSvrId else (if (msgId > 0L) msgId else msgSvrId)`。纯 JVM 源码文本断言，与现有 30 例同构，不实例化探针、零 Android/Xposed 依赖。
4. **KDoc 头**：锁层次补第 15 条、变异记录补 M18/M19（纯注释）。

### §3.2 新增真变异表（M18/M19，均带 `mutated != src` 前置 + `assertFalse` 判红）

| 变异 | 真实改动（源文本） | 判红测试 | 判红对象 |
|---|---|---|---|
| M18 | `FileSender.kt` 新调用串 → `armFromForward(ctx.sourceChat, ctx.msgSvrId)` | `mutationSourceChatTalkerRegressionIsDetected` | `fileSenderArmWiringValid` |
| M19 | `B6CheckExistProbe.kt` `put(task, "field_talker", taskTalker)` → `..., talker)` | `mutationFieldTalkerFallbackToSourceIsDetected` | `dualTalkerWiringValid` |

### §3.3 最终例数（静态核对，非三闸结果）

- `grep -c '@Test'` = **33**；`grep -c 'fun mutation'` = **19** ⇒ 契约 **14** + 变异 **19**（基线 30 = 13 + 17，新增 1 契约 + 2 变异）。
- 静态核对（本 lane 仅做文本核对，**不是**三闸）：上述所有 needle 在落盘源码中均恰好命中 1 次，`FileSender` 中 `armFromForward(ctx.sourceChat` / `armFromForward(contextChatTalker` 命中 0 次。
- **三闸未跑**：按硬纪律，compile / 定向 B6CheckExistProbeTest / 全量 / assembleDebug 一律由主会话统一执行；本 lane 不自称全绿。若定向闸例数不是 33，以主会话实测为准并回报差异。

`[DONE §3]` — 三处源码/单测改动全部落盘；静态计数 33 例 / 19 变异，needle 全部命中、反向串 0 命中。三闸待主会话。

---

## §4 六红线逐条核对（破坏即作废）

> 本 lane 改动面严格限于 `FileSender.kt:165-169`、`B6CheckExistProbe.kt:133-146/:154-161/:180-190/:274`、`B6CheckExistProbeTest.kt`（锁/注释）。以下逐条在落盘源码上核对（【源码实证·高】，本 lane 未运行）。

| # | 红线 | 核对结果 | 档位 |
|---|---|---|---|
| 1 | C2 Proxy 只读（不调 j6 / 不发消息 / 不写宿主字段） | **未破**。handler 仍在 `:291-317`、`logC2Shape` 仍在 `:411` 且整段未动；`:292` `if (!enabled) return@InvocationHandler defaultOf(...)`、`:300` `logC2Shape(f)`、`:306-308` `md5Pre`/`md5Len` 只打日志；无 `setObjectField`/`callMethod`/`j6`/`send`/`FileSender` 注入（单测 `c2CallbackReadOnly` 仍在） | 【源码实证·高】 |
| 2 | off/on 反向对照自变量唯一 | **未破**。`armFromForward:141` 首行 `if (!enabled) return` 保持；`arm:163-166` `if (!enabled)` 保持。新增的 talker/wxid/msgId 只是入参取值，不引入第二个开关 | 【源码实证·高】 |
| 3 | `arm()` 内 `d3.O8()→g9.c` 只读 | **未破**。`:184` `callStaticMethod(d3,"O8")`、`:202` `callStaticMethod(g9,"c",uTry,"msg","")` 保持；本次只把 `:190` 的 id 实参改为按方法选参（`a3`=msgSvrId、`Q2`=msgId），未写消息表/宿主字段 | 【源码实证·高】 |
| 4 | install 只找类不 hook | **未破**。`:111-124` 未动，仍为 `findCls(...)`；无 `findAndHookMethod`/`hookAllMethods` 注入（单测 `probeShapeMatchesContract` / `noNativeHook` 仍在） | 【源码实证·高】 |
| 5 | 不碰 native | **未破**。dispatch 仍 `:348` `callMethod(inst,"B8",task)`、`:356` `callMethod(m1,"e",task)` 的 Java 层；未 hook `startC2CUpload` / `startSSUpload` | 【源码实证·高】 |
| 6 | 判据口径不变 | **未破**。`C2_READ_FIELDS = listOf("field_exist_whencheck","field_filemd5")`（`:52`）未动；C2 第二次真实回包 `exist` false→true + `md5Len` 0→>0、`md5Pre`/`hit`/`idVia` 只记录不裁决，全部未改；B5 三字段写入与常量未动 | 【源码实证·高】 |

`[DONE §4]`

---

## §5 三闸待跑（本 lane 未跑，主会话统一跑）

> 硬纪律：本 lane 禁跑 gradle / `_junit.sh` / adb，不自称已跑、不写"全绿"。下表全部**待主会话**执行。本 lane 仅做了静态 grep 计数与 needle 命中核对（见 §3.3），这不等于三闸。

| 闸 | 命令（cwd=`wxgroup/`） | 状态 | 备注 |
|---|---|---|---|
| 闸 0（前置） | `python ../tools/gradle_run.py compileDebugUnitTestKotlin` | **待主会话** | 签名加了默认参，须确认编译通过 |
| 闸 1 | `bash ../tools/_junit.sh com.lx.wxgroup.probe.B6CheckExistProbeTest` | **待主会话** | 预期 **33 tests**（14 契约 + 19 变异） |
| 闸 2 | `bash ../tools/_junit.sh`（全量） | **待主会话** | 基线 1212 tests，本 lane 新增 3 例 ⇒ 以实测为准 |
| 闸 3 | `python ../tools/gradle_run.py assembleDebug` | **待主会话** | 装机前提 |

- 主会话三闸后须 fresh-eyes 再核 diff：重点 Q3.3 六红线 + A1/A3 双 talker + A4 实参 + §4 逐条；三闸未全绿前**不装机 / 不提交 / 不声称肉眼清晰**。
- 变异判红的"真实改源码文本 → 判红 → 恢复"动作，本 lane 以 `mutated != src` + `assertFalse` 写在单测内（与 M1~M17 同构）；实际执行由闸 1 完成。

`[DONE §5]`

---

## §6 诚实边界（talker 推断待升格 / C2 命中≠清晰 / 肉眼不变 / 清晰须 B6 全量+A→B→A）

- **talker 取值仍属【推断·高】，待真机 arm 自检日志升格**【源码实证·高 + 真机实证·高（旁证）】：`srcTalkerPrefix=filehelper:` 非空已由真机坐实、同一 talker 经 `Q2(talker,msgId)` 取到 `imgLocalId=885441` 有旁证，但 `a3(talker,msgSvrId)` 本轮真机**未被直接调用成功**，`trimEnd(':')` 后是否等价于 stock `msg.C0()` 属【推断·高】；残余 UNKNOWN（裁决 Q2.3）由修复后 arm 自检日志（`B6X arm try $m r0/uLen/map/keys`）在真机一次性闭合。本 lane **不写成已证**。
- **根因本身是【真机实证·高】**：现包 `armFromForward` 对每笔转发 100% 走 skip（E1~E6），这是本次改码的动机；但**改码后是否真机不再 skip、C2 是否翻转**，本 lane 未装机、未验证，结论【UNKNOWN】。
- **C2 命中 ≠ 清晰**【推断·中 → 现场升格 / UNKNOWN】：判据仍是 `exist` false→true + `md5Len` 0→>0（fix note §2.2、review2 Q4.2），`md5Pre`/`hit`/`idVia` 只记录不裁决身份；即便翻转成立，也只证「三字段能启动 checkExist 秒传探测」这一机制信号，**不等于命中原图、不等于已清晰**。若现场 off 态也 `exist=true`，判据失效 ⇒ 如实【UNKNOWN】，不写"通过"。
- **肉眼不会有任何变化**：本探针 C2 只读 + `return 0`，不调 `j6` / 不发消息 / 不写宿主字段 / 不挂 native；自建 checkExist 与真实转发消息零耦合。探针自身**不会**让用户看到 A→B→A。
- **清晰须后续 B6 全量功能 + 用户肉眼 A→B→A 终审**：出口 filemd5 翻源图身份 + 用户肉眼确认属后续 B6 全量功能与终审范畴，不属本实验。
- **单测只是文本锁**：33 例全绿 ≠ B5 通过；只防拼写级契约回归（特别是 `ctx.sourceChat` 作为 arm talker 的回归），覆盖不了运行时机制有效性。
- **未触碰项**：未碰 `ForwardLogic.kt` / `ForceForward.kt`（源 talker 与目标 wxid 现成可达）、判据常量、C2 handler、`resolveFullpath`、`TASK_FIELDS`/`MAP_KEYS`/`LISTENER_*`、`HookEntry`/`EntryReceiver` 接线；未碰 adb / 装机 / 设备开关 / gradle / `_junit.sh` / git commit。

真 `[DONE ALL]`

> 交付声明：产物 `docs/_r86c_b5_armfix_note.md`（本文本，§1~§6 全部落盘）+ `FileSender.kt` + `B6CheckExistProbe.kt` + `B6CheckExistProbeTest.kt`。A1/A2/A3/A4 全部落码，双 talker 语义拆分、Q2 的 msgId 实参修配、单测 `wiringPresent()` 盲区锁拔除 + 反向断言 + 新增契约锁 + M18/M19 真变异（静态计数 33 例 / 19 变异）均已落盘；§4 六红线逐条核对未破；§5 三闸待主会话统一跑、本 lane 未跑也未装机/提交；所有【推断】未写成【已证】。
