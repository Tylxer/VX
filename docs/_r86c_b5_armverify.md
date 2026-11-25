# R86c B5 arm 入口修复 —— 独立 fresh-eyes Pro 复核（armverify）

> 角色：`r86c-b5-armverify`，**独立 fresh-eyes Pro 复核**（非续用实现 lane `r86c-b5-armfix`，避免自审盲区）。工作根 `C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`。
> 性质 = Hook 链路取值复核 + 双 talker 语义裁决 + 单测锁可信度 + 装机 go/no-go ⇒ **Pro 类**。模型 `HY4-FLASH-FREE`（用户会话临时规则，不写入文件）。
> 档位：【源码实证·高】/【真机实证·高】/【dex 实证·高】/【推断·高|中|低】/【UNKNOWN】；每条带文件:行号锚点；【推断】不写成【已证】。
> 硬纪律：**只读复核**，唯一允许写 = 本文档；禁改源码/单测/任何其它文档，禁跑 gradle/`_junit.sh`，禁碰 adb/装机/设备开关，禁 git。
> 职责 = 证伪/找 BLOCKER，**不为 GO 放宽判据、不虚构 BLOCKER**。判据口径（C2 第二次真实回包 exist false→true + md5Len 0→>0）与 C2 只读红线不变。
> 落盘策略（防打断）：骨架 → 每问写盘留 `[DONE Qn]` → 三件套 → 真 `[DONE ALL]`。**先写文件再回报**。

状态进度：
- [x] 骨架 + 证据锚点总表
- [x] Q1 B1（arm 入口零生效）是否真消除（留 `[DONE Q1]`）
- [x] Q2 B2（双 talker 语义）是否真消除 + A4 实参（留 `[DONE Q2]`）
- [x] Q3 单测反向断言 + 六红线 + 33 例可信度（留 `[DONE Q3]`）
- [x] Q4 装机 go/no-go + 现场口径（留 `[DONE Q4]`）
- [x] 三件套（结论 / go-no-go / 最小下一步）
- [x] 真 `[DONE ALL]`

---

## 证据锚点总表（先落盘）

| 编号 | 事实 | 档位 | 锚点（本次亲核） |
|---|---|---|---|
| V1 | `FileSender.kt:169` 调用 = `armFromForward(ctx.srcTalkerPrefix.trimEnd(':'), ctx.msgSvrId, wxid, ctx.srcMsgId)`；外层 gate `:159` 未动；`:164` `ForwardCdnFieldHook.arm(ctx.sourceChat,…)` 是另一 hook、未动 | 【源码实证·高】 | `FileSender.kt:158-171`（:159/:164/:169） |
| V2 | `srcTalkerPrefix` 真机非空 = `filehelper:`（带尾冒号）；`imgLocalId=885441`、`msgSvrId>0`（外层 gate 已过）；`resend-file ok=true` | 【真机实证·高】 | `_r86c_b5_first_try_raw.txt:254/:253/:291` |
| V3 | 首轮真机零生效唯一行 `B6X armFromForward skip invalid talker/svrId`（旧包用 `ctx.sourceChat` 恒空）；无自建 task/C2 | 【真机实证·高】 | `_r86c_b5_first_try_raw.txt:283`；台账 `:5921` |
| V4 | `armFromForward(talker,msgSvrId,taskTalker=talker,msgId=0L)`；首行 `if(!enabled) return` 未动；`:146 arm(talker,msgSvrId,taskTalker,msgId)` 转发 | 【源码实证·高】 | `B6CheckExistProbe.kt:140-147`（:141/:142/:146） |
| V5 | `arm(talker,msgSvrId,taskTalker=talker,msgId=0L)`；`:189 idForMethod=if(m=="a3") msgSvrId else (if(msgId>0L) msgId else msgSvrId)`；`:190 callMethod(o8,m,talker,idForMethod)`；`:274 put(task,"field_talker",taskTalker)` | 【源码实证·高】 | `B6CheckExistProbe.kt:161/:189/:190/:274` |
| V6 | `Snapshot` 字段 `srcTalkerPrefix: String=""`、`srcMsgId: Long=0L`、`msgSvrId: Long=0L`——新调用三参类型合法、作用域可达 | 【源码实证·高】 | `ForwardLogic.kt:394/:413/:428/:429`；`FileSender.kt:215/:219` |
| V7 | `wiringPresent()` 已不锁错误调用，改调 `fileSenderArmWiringValid`；该函数正断言新串 + 正则禁 `ctx.sourceChat`/`contextChatTalker` 作 talker | 【源码实证·高】 | `B6CheckExistProbeTest.kt:224-241`（:239）/`:525-530` |
| V8 | 测试里 `armFromForward(ctx.sourceChat` 唯一命中 = `:455` M18 变异 needle（注入判红、合法非契约）；`contextChatTalker` 命中仅 KDoc/helper 注释/反向正则 | 【源码实证·高】 | `B6CheckExistProbeTest.kt:455`；grep 亲核 |
| V9 | `@Test`=33、`fun mutation`=19（契约 14 + 变异 19）；M18/M19 均 `mutated != src` + `assertFalse` 判红、纯 JVM 文本断言 | 【源码实证·高】 | `B6CheckExistProbeTest.kt:448-469`；grep 计数 |
| V10 | C2 handler `:291-316` 只读 + `logC2Shape :411-423` 未动；`C2_READ_FIELDS :52` 未动；install `:111-124` 只 findCls；dispatch `:342-361` 仍 B8/m1.e Java 层 | 【源码实证·高】 | `B6CheckExistProbe.kt:52/:111-124/:291-316/:342-361/:411-423` |
| V11 | 主会话三闸实测（本复核只读不重跑、按实测数采信）：compile EXIT=0/20s、定向 OK(33 tests)、全量 OK(1215 tests)、assembleDebug EXIT=0/5s | 【源码实证·高（采信主会话实测）】 | 台账 `docs/派发审计台账.md:5937` |
| V12 | 实现 lane 明确保留 talker 残余 UNKNOWN（未写已证）：`trimEnd(':')` 等价 `msg.C0()`【推断·高】、`a3(talker,msgSvrId)` 本轮真机未直接命中 | 【源码实证·高（ Lane 自述档位）】 | `docs/_r86c_b5_armfix_note.md:86/:155`；源码 KDoc `B6CheckExistProbe.kt:156` |

## Q1 —— B1（arm 入口零生效）是否真消除

### Q1.1 调用行逐字核对：确实改为新入参（【源码实证·高】）

`FileSender.kt:169` 落盘 = `com.lx.wxgroup.probe.B6CheckExistProbe.armFromForward(ctx.srcTalkerPrefix.trimEnd(':'), ctx.msgSvrId, wxid, ctx.srcMsgId)`（V1），与 armroot Q3 方案 A1 完全一致：第 1 参源 talker、第 2 参 `msgSvrId`、第 3 参目标 `wxid`、第 4 参 `ctx.srcMsgId`（A4）。旧错误调用 `armFromForward(ctx.sourceChat, ctx.msgSvrId)` 在 `FileSender` 源码中已不存在（grep 亲核：仅测试 `:455` 的 M18 needle 保留该串，见 Q3）。

### Q1.2 入参非空与 gate 未动：talker 恒空路径已断开（【源码实证·高 + 真机实证·高】）

- `ctx.srcTalkerPrefix` 真机非空 `_r86c_b5_first_try_raw.txt:254` `prefix=filehelper:`、`:291` `ctx=19757536/filehelper:/`（V2）⇒ `trimEnd(':')` 得到 `filehelper`，**不再 blank**，旧 skip 条件 `:142 talker.isBlank() || msgSvrId<=0L` 的首项已被证伪。
- 外层 gate `FileSender.kt:159` `if (ctx.imgLocalId > 0L && ctx.msgSvrId > 0L)` **未动**；真机 `:291` `imgLocalId=885441>0`、外层进入即 `msgSvrId>0`（V2/V3）⇒ skip 第二项亦被证伪。
- 字段合法性：`ForwardLogic.Snapshot.srcTalkerPrefix: String = ""`（`:429`，非空 String，`trimEnd` 可调用）、`srcMsgId: Long`（`:428`）、`msgSvrId: Long`（`:413`），三参类型与作用域均可达（V6）。主会话 compile EXIT=0（V11）佐证无编译级问题。
- `:164` `ForwardCdnFieldHook.arm(ctx.sourceChat, ctx.msgSvrId, ctx.imgLocalId)` 仍用 `sourceChat`，但这是**主转发链另一 hook**、非 B6X arm 入口，armroot 明确不动（V1），**不构成本轮 B1 残留**。
- ⇒ **B1 在代码层真消除**：100% skip 的恒空入参已被非空 `srcTalkerPrefix.trimEnd(':')` 替换，`armFromForward` 不再因 talker 空早退。

### Q1.3 关键残余：新 talker 能否作 `a3(talker,msgSvrId)` 源会话键 —— 仍【推断·高 → 真机 arm 自检日志升格】，未被写成【已证】

这是 armroot Q2.3 明确列出的 UNKNOWN，本复核逐条确认实现 lane **没有越级声称**：

- **已证部分**：`srcTalkerPrefix` 非空【真机实证·高】；同一 `ref.talker` 经 `d3.O8()→Q2(talker,msgId)` 真机取到 `imgLocalId=885441`（`:253 src=Q2`）作旁证【真机实证·高】；a3 的 talker 语义 = 源消息存储会话【dex 实证·高】（armroot E9/E10）。
- **未证部分**：① `a3(talker,msgSvrId)` 本轮真机**未被直接调用成功**（真机成功的是 Q2），新 talker 能否命中 a3 源记录属【推断·高】；② `trimEnd(':')` 去掉尾冒号是否等价 stock `msg.C0()` 原始形态属【推断·高】。两项均由源码内建自检日志在真机一次性闭合：
  - `B6CheckExistProbe.kt:193` `B6X arm try $m r0=FAIL …` / `:198` `u=blank` / `:205-210` `uLen=… map=… keys=…` / `:215` `B6X arm attrs $m …` / `:223` `no_xml via=$xmlVia` ——见 V10 锚点区（arm 自检段实际落点 `:188-225`，比 armroot 引的 `:187-204` 略有行号漂移，语义不变）。
- **档位诚实性核对**：实现 lane note `:86/:155` 自述「属【推断·高 / UNKNOWN】，由修复后 arm 自检日志在真机一次性闭合，本 lane 不写成已证」；源码 KDoc `:156` 仅陈述取值来源「来自 `srcTalkerPrefix.trimEnd(':')`」，未声称 a3 已命中（V12）。⇒ **未发现把【推断】写成【已证】**。
- **裁决**：该残余不升级 BLOCKER——它是「改码后装机必须一并判读的验收项」，armroot Q4.3 第 5 条与本次 Q4 现场口径已规定「若 a3 失败/`no_xml` ⇒ 记 UNKNOWN 交复核、不得写通过」，有明确逃生口，不属静态零生效路径。

`[DONE Q1]`

## Q2 —— B2（双 talker 语义）是否真消除 + A4 实参

### Q2.1 签名 / 转发 / 落点逐条核（【源码实证·高】）

| 核对项 | 落盘实证 | 判定 |
|---|---|---|
| `armFromForward` 签名 `:140` | `fun armFromForward(talker: String, msgSvrId: Long, taskTalker: String = talker, msgId: Long = 0L)` | ✓ 默认参 `taskTalker=talker`/`msgId=0L` |
| 函数体转发 `:146` | `arm(talker, msgSvrId, taskTalker, msgId)` | ✓ 四参一一透传、无错位 |
| `arm` 签名 `:161` | `fun arm(talker: String, msgSvrId: Long, taskTalker: String = talker, msgId: Long = 0L)` | ✓ 同上，手工入口零改动兼容 |
| 按方法选参 `:189` | `val idForMethod = if (m == "a3") msgSvrId else (if (msgId > 0L) msgId else msgSvrId)` | ✓ a3→msgSvrId；Q2→msgId；`msgId<=0` 回退 msgSvrId |
| 读源 xml `:190` | `callMethod(o8, m, talker, idForMethod)`（m∈{a3,Q2}） | ✓ 两方法**都用源 talker** |
| 目标落点 `:274` | `put(task, "field_talker", taskTalker)` | ✓ 目标 wxid，非源 talker |
| mediaId `:270` | `field_mediaId = "checkExist_$msgSvrId"` | ✓ 未混用 msgId |

> 档位说明：armfix note §1.3 把回退条件写成 `msgId <= 0L`，源码 `:189` 实际是 `if (msgId > 0L) msgId else msgSvrId`——两者**逻辑等价**（>0 取 msgId、否则 msgSvrId），仅为表述差异，非缺陷【源码实证·高】。

### Q2.2 语义四问逐条裁（【dex 实证·高 + 源码实证·高 + 真机实证·高（旁证）】）

1. **a3 用源 talker（读源 xml）✓**：`:190` 传 `talker`，其值来自 `FileSender:169` 的 `ctx.srcTalkerPrefix.trimEnd(':')`；a3 `a1.b(talker)`+`msgSvrId=?` 的库/表选择需源会话（armroot E9/E10，【dex 实证·高】）。
2. **field_talker 用目标 wxid（toUser）✓**：`:274` 用 `taskTalker`，其值来自 `FileSender:169` 第 3 参 `wxid`（`send` 形参，`FileSender.kt:132`）；stock B6 的 `field_talker→toUser`=目标会话（armroot E11，【dex 实证·高 + 真机实证·高】；首轮真链 `field_toUser=wxid_xlrbelf6hv0w22`，`_r86c_b5_first_try_raw.txt:325`）。
3. **Q2 回退用 msgId ✓**：`:189` Q2 取 `msgId`（=`ctx.srcMsgId`，真机 `19757536`，`:254`），与真机验证过的 `Q2(talker,msgId)`（`imgLocalIdOf`，`:253 src=Q2`）同构；`msgId<=0` 回退 msgSvrId 兼容手工通道，修掉 armroot Q1.3-P1 的实参错配【源码实证·高】，真机触发有效性【推断·中】。
4. **手工 `arm(talker,svrid)` 零改动兼容 ✓**：`EntryReceiver.kt:311` `B6CheckExistProbe.arm(talker, svrid)` 只传两参，`taskTalker` 默认=talker、`msgId` 默认 `0L`（`:161`）⇒ 行为与修复前一致【源码实证·高】。
- **有无写反/写串**：全文件 grep 核对——`talker` 仅出现在 `:190` 的 a3/Q2 查询键与 `:142/:167` 的 blank 校验；`taskTalker` 仅出现在 `:146` 转发与 `:274` field_talker。**未发现 talker 双语义写反/写串**（V4/V5）。

### Q2.3 跨作用域参数传递一一对应（【源码实证·高】）

`FileSender` 与 `B6CheckExistProbe` 两个作用域的位置映射逐段对齐，无错位：

```
FileSender.kt:169   armFromForward( srcTalkerPrefix.trimEnd(':') , msgSvrId , wxid  , srcMsgId )
                                         │                         │         │        │
B6CheckExistProbe:140  fun armFromForward(      talker            , msgSvrId , taskTalker , msgId )
                                         │                         │         │        │
B6CheckExistProbe:146                    arm(   talker            , msgSvrId , taskTalker , msgId )
                                         │                                   │        │
B6CheckExistProbe:161  fun arm(              talker            , msgSvrId , taskTalker , msgId )
                                         │                                   │        │
落点                              :190 a3/Q2 查询键                    :274 field_talker  :189 Q2 选参
                                  = 源会话                            = 目标 wxid     = 源本地 id
```

- 第 1 参源 talker 一路透传到 `:190`（源语义），第 3 参 `wxid` 一路透传到 `:274`（目标语义），第 4 参 `srcMsgId` 只在 `:189` 供 Q2 选参——**三条职责链互不交叉**。
- ⇒ **B2 在代码层真消除**（【源码实证·高】，语义依据【dex 实证·高】）。

`[DONE Q2]`

## Q3 —— 单测反向断言 + 六红线 + 33 例可信度

### Q3.1 盲区根源是否真拔（【源码实证·高】）

- **旧错误锁已不在**：`wiringPresent()`（`:224-241`）现调 `fileSenderArmWiringValid(sender)`（`:239`），**不再**含 `sender.any { "B6CheckExistProbe.armFromForward(ctx.sourceChat, ctx.msgSvrId)" in it }` 这类把错误调用锁成契约的断言（armroot Q4.3 第 2 条指出的盲区根源）。
- **正断言 + 反向断言均落盘**：`fileSenderArmWiringValid`（`:525-530`）——
  - 正：`newCall = "B6CheckExistProbe.armFromForward(ctx.srcTalkerPrefix.trimEnd(':'), ctx.msgSvrId, wxid, ctx.srcMsgId)"`，要求 `code.any { newCall in it }`；
  - 反：`badTalker = Regex("B6CheckExistProbe\\.armFromForward\\((ctx\\.sourceChat|contextChatTalker)")`，要求 `code.none { badTalker.containsMatchIn(it) }`；
  - 返回 `any(newCall) && none(badTalker)`——**两个条件同时成立才算绿**。
- **唯一命中确为 M18 needle**：grep 亲核 `armFromForward(ctx.sourceChat` 在测试文件仅 `:455` 一处，位于 M18 的 `.replace(needle, "...armFromForward(ctx.sourceChat, ctx.msgSvrId)")`（`:453-456`），是**注入判红的变异 needle**，合法、非契约（V8）。`contextChatTalker` 命中仅 `:31` KDoc、`:524` helper 注释、`:528` 反向正则三处，无一是把常量写进契约。
- **新增契约锁**：`dualTalkerWiringContract()`（`:245-247`）→ `dualTalkerWiringValid`（`:533-541`）同时核四项：`put(task, "field_talker", taskTalker)`、两条新签名（`:535-536`）、`:539` 函数体转发 `arm(talker, msgSvrId, taskTalker, msgId)`、`:540` Q2 选参串 `if (m == "a3") msgSvrId else (if (msgId > 0L) msgId else msgSvrId)`。`probeShapeMatchesContract:90` 另锁新 `arm` 签名。
- **⇒ 盲区根源真拔**，且加了防回归的反向锁（【源码实证·高】）。

### Q3.2 M18/M19 是否真变异判红、非等价、纯 JVM（【源码实证·高】）

| 变异 | 判红机制 | 真改 / 非等价 / 纯 JVM |
|---|---|---|
| M18（`:448-459`） | needle=新调用串 → 替换为 `armFromForward(ctx.sourceChat, ctx.msgSvrId)` → `assertFalse(fileSenderArmWiringValid(mutated))` | `mutated != src` 前置（`:457`）；替换后正断言 false、反向正则命中 ⇒ 必判红；非等价（改变源码真值）；纯 `String.replace` + 文本断言，无 Android/Xposed 实例化 |
| M19（`:461-469`） | needle=`put(task, "field_talker", taskTalker)` → 替换为 `…, talker)` → `assertFalse(dualTalkerWiringValid(mutated))` | `mutated != src` 前置（`:467`）；`dualTalkerWiringValid` 首项 `put(...,taskTalker)` 不成立 ⇒ 必判红；非等价；纯文本 |

- 计数亲核：`grep -c '@Test'` = **33**、`grep -c 'fun mutation'` = **19** ⇒ 契约 14 + 变异 19（基线 30 = 13+17，+1 契约 +2 变异），与 armfix note §3.3 一致（V9）。
- 全部为 `source()` 读文本 + `codeLines()` 过滤注释行（`:474-497`），不实例化探针、零 Android/Xposed 依赖——**纯 JVM**（【源码实证·高】）。

### Q3.3 六红线逐条核（【源码实证·高】）

| # | 红线 | 本次亲核 | 判定 |
|---|---|---|---|
| 1 | C2 Proxy 只读 | handler `:291-316`：`if(!enabled) return`、`logC2Shape(f)`、只 `getObjectField` 读 exist/md5、返回 0；`logC2Shape :411-423` 只打类名/字段名/长度；无 `setObjectField`/`callMethod`/`j6`/`send`/`FileSender` | 未破 |
| 2 | off/on 自变量唯一 | `armFromForward:141 if (!enabled) return`、`arm:163-166 if (!enabled)` 均在；新增 talker/wxid/msgId 是固定入参取值，不随 b6step 翻转 | 未破 |
| 3 | `arm()` 内 `d3.O8()→g9.c` 只读 | `:184 callStaticMethod(d3,"O8")`、`:202 callStaticMethod(g9,"c",uTry,"msg","")` 保持；仅 `:190` id 实参按方法选参，未写消息表/宿主字段 | 未破 |
| 4 | install 只找类不 hook | `:111-124` 全 `findCls(...)`；无 `findAndHookMethod`/`hookAllMethods` | 未破 |
| 5 | 不碰 native | dispatch `:342-361` 仍 `:348 callMethod(inst,"B8",task)` / `:356 callMethod(m1,"e",task)` Java 层；未 hook `startC2CUpload`/`startSSUpload` | 未破 |
| 6 | 判据口径不变 | `C2_READ_FIELDS :52 = listOf("field_exist_whencheck","field_filemd5")` 未动；`md5Pre`/`hit`/`idVia` 只记录不裁决（`:306-308`/`:245-249`/`:264-265`）；B5 三字段与常量未动 | 未破 |

> 行号说明：armfix note §4 引 handler `:291-317`、`logC2Shape :411`、`:52`，本次源码实核为 handler `:291-316`、`logC2Shape :411-423`、`C2_READ_FIELDS :52`——属**行号漂移**，语义逐条一致，不影响红线判定【源码实证·高】。

### Q3.4 三闸与 33 例可信度（【源码实证·高（采信主会话实测）】）

- 主会话实测（本复核**只读不重跑**）：compile EXIT=0/20s、定向 `B6CheckExistProbeTest` **OK (33 tests)**、全量 **OK (1215 tests)**（基线 1212+3）、assembleDebug EXIT=0/5s（V11，台账 `:5937`）。静态计数 33/19 与之一致。
- **33 绿 ≠ arm 真在真机落地、更 ≠ B5 通过**：这 33 例全是源码文本断言锁，只防拼写级契约回归（尤其 `ctx.sourceChat` 作 arm talker 的回归），**覆盖不了运行时机制有效性**——`trimEnd(':')` 是否真命中 a3、C2 是否真翻转、自建 task 是否真被下发，文本锁一概不证。实现 lane note `:160` 亦自述「33 例全绿 ≠ B5 通过」。**不得当通过章**。
- 正向价值：M18/M19 证明锁有真判红能力、非假绿；反向断言把「三闸全绿却真机零生效」的文本盲区堵上——这是本轮修复在**静态层**最实质的改进。

`[DONE Q3]`

## Q4 —— 装机 go/no-go + 现场口径

### Q4.1 裁决 = **GO**（装机 go；BLOCKER 清单为空）【裁决】

逐条对 armroot Q4.1「条件性 GO 的五项前置」验收：

| 前置 | 验收 | 档位 |
|---|---|---|
| ① 实现 lane 按方案 A 落码（A1~A3 必做，A4 强烈建议） | ✓ A1 `FileSender:169`、A2 `:140/:146`、A3 `:161/:189/:190/:274`、A4 `:189` 全部落盘（Q1/Q2） | 【源码实证·高】 |
| ② 单测改接线锁（拔错误调用 + 反向断言） | ✓ `wiringPresent→fileSenderArmWiringValid` 正/反断言 + `dualTalkerWiringContract` + M18/M19（Q3.1/Q3.2） | 【源码实证·高】 |
| ③ 主会话三闸全绿 | ✓ compile EXIT=0 / 定向 OK(33) / 全量 OK(1215) / assembleDebug EXIT=0（V11） | 【源码实证·高（采信实测）】 |
| ④ fresh-eyes 复核六红线不破 | ✓ 本次 Q3.3 逐条核，六条全未破 | 【源码实证·高】 |
| ⑤ 重装后按 review2 Q4.2 口径跑 off→on→off + stock 对照 | ⏳ 装机现场执行项（GO 之后的验证动作，非静态阻断项） | — |

- **为什么不是 NO-GO**：armroot 判现包 NO-GO 的根因是「`sourceChat` 恒空 ⇒ 100% skip ⇒ 判据无可判读自建 C2」。该恒空入参已被非空 `srcTalkerPrefix.trimEnd(':')` 替换（Q1.2），双 talker 已拆开（Q2），盲区锁已拔（Q3.1）——**导致真机零生效的两条 BLOCKER（B1/B2）在代码层均已消除，且未发现新的零生效路径或判据污染**。
- **为什么不为显严格虚构 BLOCKER**：`a3` 命中与 `trimEnd(':')` 等价性属 armroot Q2.3 已登记的【推断·高 / UNKNOWN】，源码内建自检日志 + 现场「`no_xml` 记 UNKNOWN 不写通过」的逃生口已覆盖（Q1.3），属验收项而非静态阻断；`ForwardCdnFieldHook.arm(ctx.sourceChat,…)`（`:164`）是另一 hook、非 B6X 链。二者均不升级 BLOCKER。
- **岗位纪律**：本复核职责 = 证伪/找 BLOCKER；既然不存在 BLOCKER，如实给 GO，**不为 GO 放宽判据、不把 33 绿当 B5 通过章**（Q3.4）。

### Q4.2 BLOCKER 清单

**空。** 原 B1（talker 恒空零生效）、B2（arm 单一 talker 双语义）经本次独立复核确认在代码层真消除（Q1/Q2/Q3）。

非 BLOCKER 的残留/验收项（如实降级、不掩盖）：

| 项 | 档位 | 处置 |
|---|---|---|
| `a3(talker,msgSvrId)` 用新 talker 真机未直接命中；`trimEnd(':')` 等价 stock `C0()`【推断·高】 | 【推断·高 / UNKNOWN】 | 装机必核 arm 自检日志（Q4.3 第 4 条），失败记 UNKNOWN 交复核、不写通过 |
| A4 的 Q2 用 `msgId` 真机触发有效性 | 【推断·中】 | 现场仅作诊断记录，不改判据 |
| `ForwardCdnFieldHook.arm(ctx.sourceChat,…)`（`:164`）仍用恒空值 | 【源码实证·高】 | 另一 hook、非 B6X 判据链，armroot 明确不动，不改 |

### Q4.3 装机现场判据口径（GO 路径，按 review2 Q4.2 + armroot Q4.3 第 5 条）

1. **开关与对照**：双探针 SCD + B6X 全开 → `b6step on`；**同会话 off→on→off 三次回包 + stock 同步对照**，确认 `exist` 翻转与三字段同进同退；若 off 态也 `exist=true` ⇒ 判据失效、如实判【UNKNOWN】、**不得写「通过」**。
2. **唯一判据**：Proxy C2 **第二次真实回包**（第一次 `exist=NA` 空壳不参与判读）`field_exist_whencheck` 由 b6step 基线 `false` 翻到 `true` **且** `field_filemd5` 长度 `0`→`>0`；`logC2Shape` 须显示 `cls`=Ltj/f、String 字段非全空壳（排除读错对象/空壳）。
3. **只记录不裁决**：`md5Pre`（含 `cff4f5a5`/`ca47f83e`）、`hit`、`UploadHitCacheType`、`idVia` 一律**只记录、不裁决身份**。
4. **arm 自检日志必须一并核**（本轮新增要求）：`B6CheckExistProbe.kt:193/:198/:205-215/:223` 的 `B6X arm try a3 r0=… / uLen / map / keys / attrs`、`B6X arm skip reason=no_xml`、`B6X arm xml via=… idVia=…`（`:254-258`）、`B6X arm sent …`（`:328-331`）、`dispatch both paths FAIL`（`:324`）须同时出现且可被判读。**若 `a3` 失败（r0=FAIL / u=blank）或 `no_xml` ⇒ 按 armroot Q2.3 残余 UNKNOWN 记录并交复核，不得写「通过」**。
5. **现场统一声明**（非代码 BLOCKER，必须写进口径）：C2 变化只证「三字段能启动 checkExist 秒传探测」，**≠清晰**；本探针不发消息/不调 j6/不写宿主字段，**肉眼不会有任何变化**；清晰须后续 B6 全量功能 + 用户肉眼 A→B→A 终审（review2 Q4.2-4、fix note §6）。

`[DONE Q4]`

## 三件套（交付）

### ① Q1~Q4 一句话结论 + 档位

- **Q1**：B1 代码层真消除——`FileSender.kt:169` = `armFromForward(ctx.srcTalkerPrefix.trimEnd(':'), ctx.msgSvrId, wxid, ctx.srcMsgId)`，源 talker 真机非空 `filehelper:`（`_r86c_b5_first_try_raw.txt:254/:291`）、外层 gate `:159` 未动且 `imgLocalId=885441>0`/`msgSvrId>0`，恒空 skip 路径断开（【源码实证·高 + 真机实证·高】）；**残余**「新 talker 能否命中 `a3(talker,msgSvrId)` / `trimEnd(':')` 等价 `C0()`」仍【推断·高 → 真机 arm 自检日志升格】，实现 lane 未写成已证（V12），不升级 BLOCKER。
- **Q2**：B2 代码层真消除——`:140/:161` 双 talker 签名带默认参、`:146` 四参透传、`:189` `idForMethod = if(m=="a3") msgSvrId else (if(msgId>0L) msgId else msgSvrId)`、`:190` a3/Q2 用源 talker、`:274 field_talker` 用目标 wxid；`EntryReceiver.kt:311 arm(talker,svrid)` 因默认参零改动兼容；跨作用域参数一一对应无错位（【源码实证·高】，语义依据【dex 实证·高】）；A4 的 Q2-msgId 真机有效性【推断·中】。
- **Q3**：盲区根源真拔——`wiringPresent()` 不再锁错误调用，`fileSenderArmWiringValid` 正断言新串 + 正则禁 `ctx.sourceChat|contextChatTalker` 作 talker，唯一 `armFromForward(ctx.sourceChat` 命中 = `:455` M18 needle（合法、非契约）；M18/M19 真变异判红、非等价、纯 JVM；33 例（14 契约+19 变异）计数亲核一致；六红线逐条未破（【源码实证·高】）；三闸按主会话实测数采信（33/1215/assembleDebug EXIT=0）；**33 绿仅是文本锁，≠ arm 真机落地、更≠ B5 通过，不得当通过章**。
- **Q4**：**GO**（BLOCKER 清单空）【裁决】——armroot 五项前置 ①~④ 全验、⑤ 为装机后验证动作；不为 GO 放宽判据、不虚构 BLOCKER；现场口径不变（review2 Q4.2：off→on→off + stock 对照，C2 第二次真实回包 exist false→true + md5Len 0→>0、`logC2Shape` 非空壳，md5Pre/hit/idVia 只记录不裁决；arm 自检日志必核，`no_xml`/a3 失败记 UNKNOWN 交复核；C2≠清晰、肉眼不变、清晰须 B6 全量 + 用户 A→B→A）。

### ② go/no-go + BLOCKER 清单

**装机 = GO。BLOCKER 清单 = 空。**
- 原 B1（talker 恒空零生效）【真机实证·高 + 源码实证·高】→ 已由 `FileSender.kt:169` 新入参在代码层消除（Q1）。
- 原 B2（arm 单一 talker 双语义）【dex 实证·高 + 推断·中】→ 已由 A2/A3/A4 双 talker + Q2 选参在代码层消除（Q2）。
- 非 BLOCKER 残留：a3 命中 / `trimEnd(':')` 等价【推断·高 / UNKNOWN】、A4 真机有效性【推断·中】、`:164` 另一 hook 未动【源码实证·高】——均有现场逃生口或属他链，不阻断本轮 GO。
- 现场口径不放宽：C2 变化只证三字段能启动 checkExist 探测、≠清晰；探针肉眼不变；清晰须后续 B6 全量功能 + 用户肉眼 A→B→A 终审。

### ③ 最小下一步（GO 路径：现场判据口径）

1. **team-lead（装机现场，判据口径）**：按 Q4.3 / review2 Q4.2 执行——双探针 SCD+B6X 全开 + `b6step on`，同会话 off→on→off 三次回包 + stock 同步对照；判据 = 第二次真实回包 `exist` false→true 且 `md5Len` 0→>0 + `logC2Shape` 非 Ltj/f 空壳；md5Pre/`hit`/`UploadHitCacheType`/`idVia` 只记录不裁决；off 态也 `exist=true` ⇒ 判【UNKNOWN】不写通过。
2. **现场必核 arm 自检日志**（Q4.3 第 4 条，本轮新增）：`B6X arm try a3 r0=… / uLen / map / keys / attrs`、`no_xml`、`arm xml via=… idVia=…`、`arm sent …`、`dispatch FAIL` 须可判读；若 a3 失败或 `no_xml` ⇒ 按 armroot Q2.3 残余 UNKNOWN 记录并交复核，**不得写通过**——这是 talker【推断·高】升格为【真机实证·高】的唯一途径。
3. **口径下发前（纯文档，不改码/不改锁）**：若 review2 R1~R3 文档 residue 尚未并入现场口径，按台账 `:5911-5912/:5917` 收口（不阻断本轮 GO）。
4. **判据翻转成立后**：仅记「checkExist 秒传探测可被三字段启动」机制信号，**不得**越级写「已清晰/问题已解决」；清晰结论留待后续 B6 全量功能 + 用户肉眼 A→B→A 终审。

真 `[DONE ALL]`

> 交付声明：产物 `docs/_r86c_b5_armverify.md`（本文本），证据锚点总表 + Q1~Q4 + 三件套全部落盘、真 `[DONE ALL]`。全程只读：唯一写文件 = 本文档；未改源码/单测/任何其它文档，未跑 gradle/`_junit.sh`，未碰 adb/装机/设备开关，未 git。所有【推断】未写成【已证】，每条带文件:行号锚点。职责 = 证伪/找 BLOCKER：独立确认 B1/B2 在代码层真消除、六红线未破、33 例非假绿但仅文本锁，给装机 GO 而不为 GO 放宽判据、不把 33 绿当 B5 通过章、不虚构 BLOCKER；残余 a3/talker【推断·高】与现场口径如实保留。

