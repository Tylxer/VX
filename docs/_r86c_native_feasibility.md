# R86c 原生秒传链承载模块分组转发目标 — 只读取证与可行性裁决

> 子代理：r86c-native-feasibility（fresh-eyes Pro · 独立取证）
> 日期：2026-11-25
> 纪律：只读；唯一可写 = 本文件；禁改源码/单测/其它文档，禁跑 gradle/_junit.sh，禁碰 adb/装机/设备开关，禁 git
> 分档：【源码实证】/【dex 实证】/【日志实证】/【推断·高|中|低】/【UNKNOWN】；【推断】不写成【已证】
> 状态：骨架已建立，逐问填充中（每问完成写 `[DONE Qn]`）

## 核心（生死）问题
微信原生秒传链（stock 走的 `MsgRetransmitUI.B6` / `hl0/r1`→`hl0/q1`→NetScene）的目标会话参数，能否被模块「转发（模块）」选人器（多选、含模块分组/微信标签）承载？即：模块能否既保留「转发到模块分组」的核心能力，又走原生 B6 秒传链拿到清晰大图？

## Q1 原生链目标参数口径

**结论：原生链的目标会话 = 单个 String `talker`；多目标被化整为零成 N 条逐 talker 的发送（事件广播），不是"一个带 wxid 列表的参数"。**

- 【dex 实证】落点 `MsgRetransmitUI.z6(String, List)`，signature `(Ljava/lang/String;Ljava/util/List;)V`（`_r29_g1_forward_forensics.md:198`，源 `_r28fw_com_tencent_mm_ui_transmit_MsgRetransmitUI.txt:5759`）。其发送分支 `:5791-5815`：`hl0/s1.a(talker)` → `hl0/r1.b(talker)` → `q1.e(content)` → `q1.h(talker)` → `q1.b()`（`_r29:200-218`）。
- 【dex 实证】"目标会话就是 `talker`（String），选定后直接走 `hl0/r1.b(talker)` + `q1.e(content)` + `q1.b()`，与 `TextSender.send` 完全同构"（`_r29:220`）。
- 【dex 实证】多目标：`z6` 末尾 `:5824-5834` 遍历 `List`，每条 `new SendMsgEvent`（`gj/or.a=talker, b=content, c=type`）→ `IEvent.d()`（异步事件发布），即多选走事件广播、单选走 `q1.b()`（`_r29:222-224`）。
- 【dex 实证】`Select_Conv_User`（String extra）传了即跳过选择器直达发送（`_r29:71`、`:196`）。
- 【dex 实证】图片转发 `qf4/o4.O8` 目标参数是单个 `talker` String（参序 `ctx, talker, fileName, msgType, localPath, remark, fwdInfo, scan`，`_r29:299-302`）。

⇒ 原生链没有任何"任意 wxid 列表参数"形态：它只认**单个 talker String**，批量靠**逐条循环 / 逐条事件**实现。该 talker 是微信会话标识（wxid / 群 id / `filehelper`），可为任意目标会话字符串（【推断·高】：`r1.b(String)` 对 talker 无白名单校验证据，微信 UI 层"最多 9 个会话"限制在选择器而非发送链，见 `ForwardPicker.kt:552-559` 对 PENDING_MAX_TARGETS 的注释）。

`[DONE Q1]`

## Q2 模块选人器产出形态

**结论：模块选人器产出 `List<String>` 的 wxid（已展开、去重、排除），消费端对每个 wxid 逐目标调用一次单 talker 发送链。与 Q1 原生链"单 talker String"形态完全兼容。**

- 【源码实证】`ForwardPicker.Result(val wxids: List<String>, val picked: Int)`，`wxids` 是"**已经展开 + 去重 + 排除**的目标 wxid（同一人被多个分组/标签选中时只发一次）"（`ForwardPicker.kt:56-64`）。
- 【源码实证】`confirm()`：`val wxids = ForwardLogic.resolveTargets(picked, buckets(), exclude)` → `onConfirm(Result(wxids, picked.size))`（`ForwardPicker.kt:377-401`）。
- 【源码实证】`resolveTargets` 把裸 wxid / `g:<id>`（模块分组）/ `t:<name>`（微信标签）展开为去重后的 wxid 列表（`ForwardLogic.kt:254-299`，前缀常量 `PREFIX_GROUP` / `PREFIX_LABEL` 在 `:255/:258`）。
- 【源码实证】批量执行 `for (t in targets)` 逐目标调用一次发送：`nativeForward(snap, t)` 或降级 `send(strategy, snap, t)`（`ForceForward.kt:1938-1948`、`:1977-1979`）。即**模块早已把"多目标"实现为逐目标单 talker 循环**，与原生 `SendMsgEvent` 逐条广播形态同构。

**兼容性裁决**：Q1 原生链只吃单个 talker String、批量靠逐条；Q2 模块产出 List<wxid> 且消费端本就逐条调用 ⇒ 目标形态天然兼容，无结构性冲突。`target: String` 直接喂 `hl0/r1.b(talker)` / `q1.h(talker)` 即可（`ForceForward.kt:2242`、`:192`）。

⚠️ 唯一待证点：原生 UI 层"最多选 9 个会话"限制是否继承到模块批量链——**未取证**（`ForwardPicker.kt:552-561`，`PENDING_MAX_TARGETS=0` 暂不限）；但这只影响目标数上限，不影响"能否承载分组目标"的形态结论。

`[DONE Q2]`

## Q3 「原生转发不可用」根因

**结论：根因是 G1 开关 `Keys.FORWARD_NATIVE` 默认 false（刻意默认关），导致 `nativeState()` 恒返回 `UNKNOWN`，`planFor` 必然选降级链。不是"前置条件缺失"，而是设计上的默认闸门。**

判定链逐环带锚点：
- 【日志实证】真机每发打 `FORWARD plan strategy=RESEND_FILE reason=原生转发不可用，降级重发文件 picked=1 targets=1 kind=IMAGE textLen=0`（`_r86c_q6_fire1.txt:944`）；`_r86c_b5_armfix_off1.txt:158` 同类。
- 【源码实证】日志产生点：`execute()` 调 `ForwardLogic.planFor(snap, nativeState())` 并按 `plan.strategy` 打 `FORWARD plan strategy=... reason=...`（`ForceForward.kt:1899-1905`）。
- 【源码实证】策略选择：`planFor` 仅当 `nativeState == AVAILABLE && ForwardNativeLogic.supportsNative(kind)` 才返回 `NATIVE`；否则落到 `RESEND_TEXT` / `RESEND_FILE`，reason 字面量就是"原生转发不可用，降级重发文件"（`ForwardLogic.kt:457-481`，TEXT 分支 `:468`、FILE 分支 `:478`）。
- 【源码实证】`nativeState()`：`if (!Kv.getBool(Keys.FORWARD_NATIVE)) return UNKNOWN`——开关关时连 nativeProbe 都不做、直接 UNKNOWN（`ForceForward.kt:2034-2047`，尤其 `:2046`）。开时才 `nativeProbe()` 探测 `hl0.r1`/`hl0.m` 类 + `r1.b(String)` 方法，过则 AVAILABLE（`:2056-2074`）。
- 【源码实证】`Keys.FORWARD_NATIVE = "chat.forward_native"`，注释明确**默认 false 且刻意**（"未经真机验证的反射写宿主对象"）（`Keys.kt:167-181`）。
- 【源码实证】额外前置：`supportsNative(kind)` 只放行 TEXT/IMAGE（`ForwardNativeLogic.kt:62-83`）；其余消息种类即便开关开也降级（这是种类限制，不是本日志里 IMAGE 的场景）。

⇒ **本日志场景（kind=IMAGE）"原生转发不可用"的唯一根因 = 开关 `chat.forward_native` 为 false**（【源码实证·高】+【日志实证】）。开法 `kvset chat.forward_native true` 后重启（`ForceForward.kt:2044`、`Keys.kt:178`、`_r36_forward.md:159`）。

`[DONE Q3]`

## Q4 G1 开关与 FileSender 现状

**结论：关 = 降级链（FileSender.send），开 = Q1 原生链（nativeForwardImage），二者共用同一 `hl0/r1→hl0/q1→NetScene` builder。R73 已在两条链都补齐 B6 大图路径字段（m.a/m.b/q1.r/q1.j），但 G1 开关从未经真机验证。**

### 开/关分别走哪条链
- **关（现状，默认）**：`nativeState()=UNKNOWN` → `planFor=RESEND_FILE` → `runBatch` 调 `send(strategy, snap, t)` → `resendFile` → `FileSender.send(...)`（`ForceForward.kt:1977-1979`、`FileSender.kt:130-137`）。
- **开**：`nativeState()=AVAILABLE`（当 `nativeProbe()` 过）→ `planFor=NATIVE` → `runBatch` 调 `nativeForward(snap, t)`（`ForceForward.kt:1947-1948`）→ 按 kind 分派 `nativeForwardText`（`:2121`）或 `nativeForwardImage`（`:2200`）（`:2094-2099`）。
- 【源码实证】两条链都是 Q1 原生链同一套：均 `r1.a(o1)` → 逐字段写 q1 → `attachForwardInfo`/`q1.f(m)` → `q1.h=13` → `q1.a()` → `MsgRetransmitUI.g6(l1)` → `l1.a()`（`FileSender.kt:183-266`、`ForceForward.kt:2233-2285`）。区别只在外层入口，builder/NetScene 完全相同 ⇒ **开了就是 Q1 的原生链**。

### 是否已复刻 B6 大图路径（R73 现状，非 R29 老快照）
- 【源码实证】`nativeForwardImage` 的 `attachForwardInfo`：`m.a = if (snap.imgLocalId>0) snap.imgLocalId else -1`（`ForceForward.kt:2297-2302`）；`m.b = snap.srcMsgId>0 ? : fi.msgId`（`:2303-2306`）；`q1.r = snap.srcTalkerPrefix`（`:2315-2317`）；`q1.j = snap.srcBigImgPath` 覆盖缩略图路径（`:2318-2320`）。
- 【源码实证】`FileSender.send` 同款补齐：`m.a`（`FileSender.kt:208-209`）、`m.b`（`:215-217`）、`q1.r`（`:219-221`）、`q1.j`（`:224-226`），并另有 P3 字节落盘第 6 参 `bigImgPath`（`:228-235`）。
- 【源码实证】注释描述语义：`m.a>0 ⇒ j2 见 m.a>0 → d3.O8().o2(m.a) 挂 ImgInfo2 记录 → SERVERID://→Y2(msgSvrId) + ot5 CDN 凭证 ⇒ 服务端引用/秒传 ⇒ 清晰大图`（`FileSender.kt:200-203`、`ForwardNativeLogic.kt:24-29`）。
- ⇒ 【源码实证·高】R29 §5.2 / `_r36_forward.md:86` 说的"`new m()` 全默认 a=-1、漏填"是**老快照**；R73 已在两条链填 `m.a`，"漏填 a=-1"仅在取不到 imgLocalId（≤0）时安全降级（`ForwardNativeLogic.kt:24-29`、`ForceForward.kt:2299`）。

### `_r36_forward.md` 判据与真机状态
- 【源码实证】判据（开 `chat.forward_native true` 后）：①转文本出 `FORWARD native ok` 且**对端肉眼见"转发"标记**（唯一有效判据）；②转图同上，`m.a` 恒 -1 ⇒ 会重传属预期；③反向关开关 ⇒ 日志无 `FORWARD native probe`、标记消失；④任何失败落 `FORWARD native fail stage=...` 且消息仍发出（回落生效）（`_r36_forward.md:213-218`）。
- 【源码实证】`FORWARD native ok` 只证明"入队 true"，不证明对端见标记，判据只能是肉眼 1/2（`:218`）。
- 【源码实证】`_r36_forward.md:224-229` 明确"待真机（未做，需用户指令）"：§6 四条判据从未执行。本轮搜到的三份真机日志（fire1/off1/stock）中**无** `FORWARD native ok` / `FORWARD native probe` 行（【日志实证】：Grep `原生转发不可用|FORWARD plan` 仅命中降级 reason 行）⇒ G1 开关真机处于**关闭、未验证**态。
- ⚠️ 注意 `_r36_forward.md:86` 记"m.a 无①档 CDN imgId 获取路径 ⇒ 恒 -1"是 R36 老约束；R73 已用 `d3.O8().Q2/a3 → r0.a` 取源图 imgLocalId(>0) 覆盖（`ForwardNativeLogic.kt:24-28`），属后续更新，不推翻该文档判据本身。

`[DONE Q4]`

## Q5 可行性裁决（含隐藏假设核查）

### 隐藏假设核查：原生链的"清晰"是否真来自服务端秒传、与本地大图在场无关？

**裁决：不成立（部分崩塌）。原生 B6 链的清晰并非无条件"与本地大图无关"；对我方验证图而言，清晰的决定量仍是本地 hevc 缓存字节状态。**

证据链：
- 【日志实证】stock 原生转发清晰态：`SCD2 c2c in ... fexists=true fbytes=45522 fmd5pre=cff4f5a5`（`_r86c_b5_armfix_stock.txt:199`）、`x0cb in md5=cff4f5a5 ... hit=1 exist=true`（`:221`）、`field_exist_whencheck=true field_UploadHitCacheType=1`（`:224`）。**清晰态本地确有 45522B 原图 hevc 字节在场**。
- 【日志实证 + 推断·高】同一张 216b 图，22:01 模块转发那次清晰，定性为"本地 hevc 缓存恰为 45522B/`cff4f5a5` 原图时，原生链 `q1.j→j2.O→z3(r0)` 自行把 `field_fullpath` 指到原图字节"；历史 B 态模糊 = 同一图在该时刻只缓存 9554B/`0ef205c1` 压缩 hevc（`_r86c_clearness_reverdict.md:35`、`:45`、`:205`、`:25`）。决定量 = `field_fullpath` 指向字节的 MD5 是否源图 `cff4f5a5`（`:26`）。
- 【日志实证 + 推断·高】⇒ **即便走原生链，若本地只有压缩 hevc、缺原图字节，`q1.j→j2.O→z3` 同样拼到 9554B/`0ef205c1` → 模糊**。"走原生链"不是清晰的充分条件；清晰是**本地 hevc 缓存状态依赖**（`_r86c_clearness_reverdict.md:87`、`:101`）。
- 【dex 实证 + 推断·中】存在一个对立的两路径汇聚模型：出口 filemd5=源图身份可由 (a) 本地 `field_fullpath` 指向源图字节，或 (b) 服务端秒传 fileId(305f)+xml `cdnbigimgurl` 命中（本地 36B 占位也清晰，R79）达成；二者汇聚到 `x0.u setFilePath`/回包 filemd5，**"两条上游链谁主导我方模块转发"仍 UNKNOWN**（`_r86c_direction_verdict.md:98-103`）。而**我方坏图 216b 的 xml 无 `cdnbigimgurl`、只有 mid `cdnmidimgurl(305f)`**（`_r86c_direction_verdict.md:165`、`_r86b_b6_reimpl_design.md:166` 引 b6step:21/D3:220-222）⇒ 路径 (b) 在我方坏图上未证可用。
- ⇒ 综合：治本前提"原生链秒传、与本地大图无关"**只在服务端秒传路径 (b) 成立时为真，而我方图恰恰缺该路径的 key**；落回路径 (a) 则仍依赖本地原图字节在场。故前提**部分崩塌**，不可作为已证基石。

### 生死问题裁决

**② 条件可行（偏谨慎），非①可行、亦非③根本冲突：**
- **形态层面 = ①可行【源码实证·高】**：Q1 原生链只吃单 talker String、Q2 模块产出 List<wxid> 且消费端已逐条调用，Q4 原生链实现（`nativeForwardImage`）+ B6 大图字段（m.a/m.b/q1.r/q1.j）均已落盘 ⇒ "模块分组目标能上原生链"**无结构性冲突**。
- **清晰层面 = 未证 / 条件可行【推断·中】**：G1 开关从未真机验证（`_r36_forward.md:229`），`m.a>0 是否真机驱动 j2 走 ImgInfo2/CDN 秒传 → 出口 filemd5 翻源图身份 → 用户肉眼清晰`这条因果**全靠源码注释**（`FileSender.kt:200-203`），本轮三份真机日志无任何 `FORWARD native ok` 行。且用户实测该场景原生转发按钮被屏蔽、B6 不会被触发（`_r86b_b6_reimpl_design.md:70`），模块只能靠反射直调 builder 链，这条链的语义正确性正是 G1 默认关的理由（`Keys.kt:170-174`）。
- ⇒ **净裁决：形态可承载已证；清晰度治本未证。走原生链是清晰的必要非充分条件，需一轮真机 A/B 才能从"条件可行"升为"可行"。**

**证伪 / 升级条件（明确分档）：**
- 升①可行：开 `chat.forward_native true` 重启后，日志出 `FORWARD native probe=true` + `FORWARD native image queued ... sub=13`（`ForceForward.kt:2071/:2277`），且出口 `filemd5` 翻到源图身份（`cff4f5a5`/`ca47f83e`），且**用户肉眼 A→B→A 翻转成立**（`_r36_forward.md:214-218`、`_r86c_direction_verdict.md:199`）。
- 降③不可行：开 G1 后仍 `FORWARD native fail stage=SEND` 或发出无转发标记/空壳消息，或 `nativeProbe` 因混淆名变失败，或出口 md5 仍 `0ef205c1` 而肉眼模糊 ⇒ 原生反射链本场景不可用，须放弃①。
- 前提崩塌确认：若证得"原生清晰纯靠服务端秒传 (b)、本地 36B 占位也清晰"在我方图成立，则 m.a 本地字节路径无用，①的治本机理失效，须转 B6 补字段方向（该方向自身亦 UNKNOWN，`_r86c_direction_verdict.md:180`）。

`[DONE Q5]`

## Q6 go/no-go 与最小下一步

**裁决：条件 go（仅到"一轮真机 A/B 取证"层），不直接 GO 到"切换默认链 / 全量落码"。**

- **为何值得投**：①形态可承载已源码坐实（Q1+Q2）；②原生链实现与 B6 大图字段均已落盘（Q4），取证成本极低——唯一自变量就是一个已存在的开关 `chat.forward_native` 的开/关，零改码；③这是所有方向里唯一"不改宿主字段写入面、只切现有链"的路径，风险最小（`_r86c_direction_verdict.md:113` 对侵入性变更的顾虑在此不适用）。
- **为何不直接 GO**：清晰治本前提崩塌风险未排除（Q5 隐藏假设部分不成立）+ G1 从未真机验证 + 原生按钮被屏蔽场景只能靠反射链（语义正确性正是默认关的理由）。若直接切默认链，可能发出无标记/空壳消息（`Keys.kt:170-174`）。

### 最小真机取证方案（由主会话执行；本代理不碰现场）

**复位边界声明**：现场全探针保持 off 回读确认，本代理全程未开任何探针、未碰 adb/设备/开关；以下为建议布法，不由本代理执行。

| 步 | 动作（主会话执行） | 控制变量 | 记录 |
|---|---|---|---|
| 0 | 落盘 logcat（防出口 md5 被环形缓冲刷掉，`_r86c_clearness_reverdict.md:136`）；广播 stat 回读确认 r86c/stockcdn 等探针均 off | — | 起点时间戳 |
| 1 | 基线：同图同记录，A=微信原生转发、B=模块「转发（模块）」各 ≥2 次 | 图/记录固定 | 逐发肉眼清晰/模糊 + 对端观感 |
| 2 | **唯一自变量 = `kvset chat.forward_native true` 后重启**（`ForceForward.kt:2044`），重复步 1 的 B | 仅 G1 开关翻转，其余开关冻结 | 是否有 `FORWARD native probe=true`、`FORWARD native image queued`、有无 `FORWARD native fail stage` |
| 3 | 反向：`kvset chat.forward_native false` + 重启，重复 B | 开关回关 | 日志无 `FORWARD native probe`、标记消失（闸门管得住） |
| 4（可选旁证） | 只读开 stockcdn 探针看 `SCD-c2c`/`SCD-x0cb` 出口 md5 是否翻到源图身份；取证后 off | 随步 | 出口 md5 只作机制旁证，不作判据 |

**判据（对肉眼，不对计数器）**：
- A 清晰 / B 模糊 / 开 G1 后 B 清晰 / 关 G1 后 B 模糊 ⇒ **A→B→A 翻转成立 + G1 开关可回退** ⇒ ①升为可行，可进入"是否切默认"的独立决策。
- 开 G1 后仍模糊、或出 `FORWARD native fail stage=SEND`、或 `nativeProbe=false`、或发空壳 ⇒ ①否，归档。
- **唯一终审 = 用户肉眼**；`hit`/`UploadHitCacheType`/`FORWARD native ok`（仅证明入队）一律不作清晰判据（`_r36_forward.md:218`、`_r86c_clearness_reverdict.md:153`）。

### 诚实边界

可行性分析 ≠ 已实现 ≠ 已验证：形态兼容【源码实证·高】；原生链代码已落盘【源码实证·高】；但"开 G1 → 对端清晰"从未真机观测【UNKNOWN】，出口 md5 翻转与肉眼 A→B→A 均未取得。清晰须 B6 全量 + 用户肉眼 A→B→A 终审（`_r86c_direction_verdict.md:199`）；肉眼属【用户报告·非日志可核】。

`[DONE Q6]`

## 三件套

**① 生死问题一句话裁决 + 分档**
微信原生秒传链的目标会话只吃单个 String talker、批量靠逐条（Q1【dex 实证·高】），模块选人器产出 List<wxid> 且消费端本就逐目标单 talker 调用（Q2【源码实证·高】），形态**完全兼容、无结构性冲突**；原生链实现与 B6 大图字段（m.a/m.b/q1.r/q1.j）均已落盘（Q4【源码实证·高】）。但"清晰"并非"走原生链"自动获得——隐藏假设部分崩塌：清晰决定量仍是本地 hevc 缓存字节（原图 45522B/cff4f5a5 vs 压缩 9554B/0ef205c1），是我方图缺 cdnbigimgurl 的服务端秒传 key（Q5【日志实证 + 推断·中】），且 G1 开关从未真机验证。故裁决 = **② 条件可行（偏谨慎）**：形态可承载已证、清晰治本未证，走原生链是必要非充分条件。

**② 证伪 / 升级条件**
- 升①可行：开 `chat.forward_native true` + 重启后 `FORWARD native probe=true` + `FORWARD native image queued` + 出口 filemd5 翻源图身份 + **用户肉眼 A→B→A 翻转**（四者同时）。
- 降③不可行：开 G1 后仍模糊 / `FORWARD native fail stage=SEND` / `nativeProbe=false` / 发空壳 / 出口 md5 仍 0ef205c1。
- 前提崩塌确认：证得原生清晰纯靠服务端秒传 (b)、本地 36B 占位也清晰在我方图成立 ⇒ m.a 路径失效，转 B6 补字段方向。

**③ 最小下一步 + 责任方**
一轮真机 A/B，唯一自变量 = `chat.forward_native` 开关（零改码）：步 0 落盘 logcat + stat 回读探针全 off；步 1 同图同记录 A 原生/B 模块各 ≥2 次肉眼；步 2 开 G1 重启重复 B；步 3 关 G1 重启重复 B；步 4（可选）只读 stockcdn 看出口 md5 旁证后 off。判据对肉眼、不对 `hit`/计数器；`FORWARD native ok` 仅证明入队。**责任方：主会话执行真机 + 用户肉眼终审；本代理全程只读取证，未碰现场。** go 仅到取证层，不直接切默认链/全量落码；清晰须 B6 全量 + 用户肉眼 A→B→A 终审。

---

> **交付声明**：Q1~Q6 + 三件套全部落盘，真 `[DONE ALL]`（非骨架预置假标记）。全程只读，唯一写文件 = `docs/_r86c_native_feasibility.md`；未改源码、未跑 gradle/单测、未碰 adb/设备/开关、未 git。未推翻硬结论（C2 命中≠清晰、talker 整体真机实证·中、B5 机制推断·高、Q6 no-go 归档、固定字节/MD5 不泛化、微X 全复刻 no-go、清晰须 B6 全量+肉眼终审、肉眼用户报告非日志可核）。所有【推断】未写成【已证】：隐藏假设"原生链与本地大图无关"经比对判为部分崩塌【推断·中】，G1 真机未验如实标【UNKNOWN】；存活裁决为条件 go（仅取证层）。每条结论带 文件:行号 / 日志:行号 锚点；分档严格。

[DONE ALL]
