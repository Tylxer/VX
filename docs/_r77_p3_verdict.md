# R77 裁决：R76 P3 字节落盘方案真机失败后的走向

> 本轮档位：Pro（根因推理 + Hook 链路推导 + dex 反汇编取证）
> 作者：general-purpose-1（子代理）
> 产物唯一写操作：本文件；不修改任何源码/测试/配置，不碰 adb/gradle/git，不派子代理。
> 纪律：每条结论带可自证锚点（dex code_off / 真机日志行 / 源码行号 / 文档章节），并区分【源码确定】【真机确定】【推断+置信度】。
> 三条来源纪律：① dex 有字符串 ≠ 业务调用它；② 「A 推 B」时序必须标假设；③ 引用单测必须说明是否变异验证。
> 状态标记：[DONE0]=骨架已建；每答完一问追加写盘；全部完成写 [DONE]。

## 背景锚点

- 坏图：md5 `216b1f7ccd...`，msgId `19733550`，本地仅 36B jpg 占位 + 15KB `th_` 缩略图，无大图。
- R75 双线已确认：清晰度唯一决定量 = stock j2 经 `z3(r0.e)` 指向的本地文件是否为大图字节；读图链 `j2.R()=o2(j2.i)`→doScene `z3(r0.e/f/g)→o6.l→o6.N`；`ori` 不读字节。微X转发清晰靠私有加密 dex 落 `cache/_<uuid>` 大图字节（宿主下载链 R5/j6/B8/C2 全零）。
- R76 方案 A：`DownloadImgService.c`（`d3.F8()`→`svc.c(localId,msgId,TYPE_HD,...)`，TYPE_HD=0，照抄 AutoHdImg.kt:415-479）触发下载 → 复制到逐目标临时文件 → FileSender 第6参 `bigImgPath` 覆盖 q1.j。P0（listener 回读 + @Volatile）后三闸 OK 1082、02:04 装机。
- 02:11 真机 FAIL（docs/派发审计台账.md 末尾「R76-P3 真机正向验证 = FAIL」+ docs/_r76_p3_realdev_fail1.log）：`FORWARD P3 download fired localId=883190 msgId=19733550 r=true`；`bigimg ready=false path=cd59b1268349fd0eb3c46801 fresh=false`；`copy skip: source fails thresholds name=th_216b...`；listener 回调 `ready=false path=none cb=l0`；`FORWARD resend-file p3=false`；`INP j2init str[1]=th_216b...` ⇒ 模糊；文件级 216b...jpg 仍 36B（mtime 变 02:11 尝试覆写但未产出），th_ 仍 15KB。结论：宿主下载入口对此坏图未产出大图字节，P0「异步消费」修复无效（没有字节可消费）。

---

## Q1 stock 微信自带转发这张坏图为什么清晰？（已答）

### Q1.1 一句话结论

【源码确定 + 真机确定】stock 自带转发清晰，**不是**从本地已有大图读字节（坏图本地只有 36B 占位 + 15KB th_），而是走了一条我方此前完全没有追的链：`MsgRetransmitUI.B6` 用坏图消息**自身携带的 CDN 参数**（`cdnbigimgurl`/`aeskey`/`md5`，在 `r0.u` 的 xml 里）触发 **CDN checkExist/下载**；下载完成的回调 `qf4/m0.l0` 把**大图本地路径**写进 `MsgRetransmitUI.g`；`j6` 用 `R5` 以该路径落地新 ImgInfo2 记录（**新 localId**）；`k6` 把 `g` 写进 `q1.j` 并构造 `j2`；最终 `j2.R()=o2(新 localId)` 经 `z3(r0.e)→o6.N` 读到**大图字节**。

**这条链全程 dex 可见（classes7.dex），不依赖微X私有加密 dex。** 它是本轮**推翻前几轮"我方 dex 可见入口对此坏图拿不到大图字节"裁决的决定性新证据**——前几轮只盯住 DownloadImgService(CGI) 与微X私有通道，漏掉了 stock 原生转发自带的 CDN 下载链。

### Q1.2 完整 dex 调用链（全部 classes7.dex，code_off 本轮自算，未抄历史文档）

> 计算法：`_r35_adnd_dex.py` 解析 class_data 的 encoded_method.code_off uleb；`_r35_sns_dis.py` 逐指令解码。所有地址均为 code_item 起点（非「+16 头部」的指令偏移）。

| 步 | 方法 | code_off | 关键动作（逐指令取证） |
|---|---|---|---|
| ① | `MsgRetransmitUI.B6(String,int,t0)V` | `0x62c98c` | `g9.c(r0.u,"msg")` 把消息 xml 解析成 Map（0x00b8），取 `.msg.img.$cdnbigimgurl`/`$cdnmidimgurl`（0x00e8/0x00dd）、`$aeskey`（0x0100）、`$md5`（0x014e）；构造 `tj/k` CDN task（0x0157 `task_MsgRetransmitUI_2`），设 `field_mediaId/fileId/aesKey/filemd5/fileType/talker/priority`，**`field_onlycheckexist=true` + `field_trysafecdn=true`**（0x01be/0x01c0）；`mq/k.B8(tj/k)`（0x01cc）；日志 "check exist fail! download img before retransmit"（0x01d2）后调 `j6`（0x01ec）+ `k6`（0x0207） |
| ② | `mq/k.B8(tj/k)Z` → `ok0/m1.e(tj/k)Z` | → `0x7b52b4` | `B8` 仅一行 `ok0/s2.e9().e(tj/k)`；`m1.e` 日志 "summersafecdn addSendTask"，`new ok0/h1(m1,tj/k)` 提交线程池（0x0048-0x004f）。**这是 CDN 任务真正下发点**（"addSendTask" 字符串在此方法中被 `const-string` 且紧邻 task 提交，非孤立字符串） |
| ③ | `qf4/m0.l0(JJIIObjectIIString,k1)V` | `0x844a0c` | CDN 完成回调：`s0.b(r0)`（0x0051）→ `b1.z3(...)`（0x0057）→ **`MsgRetransmitUI.g = 结果`（0x005d `iput-object`）**；再调 `k6`（0x0089）。**这是"大图字节落盘后把本地路径写回 g"的直接证据** |
| ③b | `modelimage/s0.b(r0)String` | `0x2b429c` | 分支：若 `r0.f()==false` → `d3.O8().m2(r0.r)` 取关联 r0 → 返回其 **`r0.e`**（0x0015）；否则直接返回 `r0.e`（0x0018）。即 **s0.b 的返回值 = ImgInfo2 的 e 字段（大图本地路径）** |
| ④ | `MsgRetransmitUI.j6(r0,h9,String,int,String,String)V` | `0x62d0f8` | `PString.value = this.g`（0x0013-0x0015）；`d3.O8().R5(this.g,...,PString,PInt,PInt)`（0x0037）→ 新 localId；`o2(新localId)`（0x004e）→ `r0.m/o/u8`（0x0053/0x00d9/0x00ef，即新任务落库）；`d3.F8().c(新r0.a,msgId,...)`（0x0109-0x012b）上传 |
| ④b | `modelimage/b1.R5(...)J` → `L5(fv/t0)J` | `0x2a710c` → `0x2a6e94` | R5 组装 `fv/t0`（a/b/d/e/f/g/h/i）后调 L5；L5 `n1` 生成/落地新 r0（0x0013），`PString.value=r0.g`（0x001a-0x001e），`o6.c(z3(r0.c),z3(r0.e))`（0x003a-0x003e，按大图路径复制字节），`p6/a6/u8` 持久化（0x004a/0x0051） |
| ⑤ | `MsgRetransmitUI.k6(...)V` | `0x62d368` | **`q1.j = this.g`（0x00ad-0x00af，大图路径进 ForwardInfo）**；`q1.a()`→`l1`，`l1.a` check-cast `modelimage/j2` → 存 `MsgRetransmitUI.z`（0x0118-0x0120）—— **这就是 stock 转发接到 j2 的桥**；`l1.a()` 入队 + `g6(l1)`（0x0122-0x0125） |
| ⑥ | `modelimage/j2.R()` → `doScene` | `0x2ad864` → `0x2ad99c` | `R()=o2(j2.i)`（R75 §3 已独立复核）；doScene `z3(r0.e/f/g)→o6.l→o6.N` 读字节（R75 §3） |

### Q1.3 字节落点与字段指向（回答"写到哪个文件 / 哪个 r0 字段"）

- **字节来源**：坏图消息 xml (`r0.u`) 里的 `cdnbigimgurl` + `aeskey` → CDN 下载（步②），落到宿主 `image2/<hash>/<md5>.jpg`（CDN 落盘路径由 `s0.b=r0.e` 得到，步③b）。
- **字段指向**：下载完成后 `MsgRetransmitUI.g = z3(s0.b(r0))`（步③）→ `q1.j = g`（步⑤）；j6 的 R5 生成**新 localId**（步④），`j2.R()=o2(新 localId)` 拿到的新 r0 的 **`e` 字段**即为大图本地路径，被 doScene 的 `z3(r0.e)→o6.N` 读取。
- **与我方 P3 的关键差异**：我方 `resendFile` 传的是**原 localId=883190**（真机 `MIO q1.f m[a=883190 ...]`、`INP j2init m.a=883190`，fail log:11/20），`o2(883190).e` 指向 36B 占位/th_ 缩略图 ⇒ 模糊；stock 走的是 **R5 生成的新 localId** ⇒ `o2(新).e` 指向 CDN 下载的大图 ⇒ 清晰。**不是"我方 dex 拿不到字节"，而是我方压根没走 stock 那条会下载并换 localId 的链。**

### Q1.4 证据分档与纪律

- 【真机确定】fail log:55 `MIO j2O in r0.u=<msg><img aeskey="95fee190e508c4fd62570dc7293be732" cdnmidimgurl="305f020100044b3049..." ...>` ⇒ 坏图消息**确实携带 CDN 参数**（aeskey/cdnmidimgurl 明文可见）。用户肉眼：stock 自带转发清晰（team-lead 转述）。
- 【源码确定】上述 6 步 code_off + 逐指令字段写入，均为本轮自算自解；非引用历史现成地址当结论。
- 【推断，中置信】"stock 下载用的是 `cdnbigimgurl`（大图）而非 `cdnmidimgurl`（中图）"：B6 同时解析两者（0x00dd/0x00e8），但**具体选哪个由 `ok0/m1.e` 内部决定，本轮未反汇编到选择点**；且 CDN task 的 `field_onlycheckexist=true` 语义是"先查存在性"，是否真正拉取全字节由 CDN 服务端/客户端状态决定。此项为"A 推 B"时序推断，**标注为假设**，需真机抓 CDN 下载完成日志验证。
- 纪律①：本结论**不**因"B6 里有 `cdnbigimgurl` 字符串"就断言它一定下载大图——关键的"下载发生"证据是步③回调写 `g` + 步④ R5 落地新 localId 的**字段写入指令**，非孤立字符串。
- 纪律②：步②→③ 的"CDN 回调触发 g 写入"是**调用链推断**（B6 提交 task、`m0.l0` 是完成回调、回调内写 g），回调与 B6 的时序关联为【推断，高置信】，非同一方法内的顺序执行。
- 未引用任何单测（本问为纯 dex/真机取证，无单测可引）。

## Q2 我方 DownloadImgService.c 为什么没产出字节？（已答）

### Q2.1 一句话结论

【源码确定 + 真机确定】`svc.c()` 走的是**另一条下载通道**（`modelimage/m.c→m.b→m.h` 构造 `t1` 走 **CGI `getmsgimg`**，`m.onSceneEnd` 回调），它与 Q1 里 stock 转发用的 **CDN addSendTask（`ok0/m1.e`）通道完全不同**。真机 `r=true` 只证明 `c()` 受理入队，不证明字节落地；listener 回调 `cb=l0` 确已触达但 `errType/errCode` 非 0-0（否则源码会打 `ready=true`），且 `o2(localId)` 回读的 jpg 仍 36B ⇒ **此坏图经 CGI 通道拿不到大图字节**。`TYPE_HD=0` 本身与 AutoHdImg 一致、不是错；`t==1` 判重本轮真机未命中（日志 `t=0`）；六参语义与 `args[5]/[6]` 的 errType/errCode 位置都对。**根因是通道选错（CGI 而非 CDN），不是参数或 listener 判定错。**

### Q2.2 六参语义与调用链（code_off 本轮自算）

- `modelimage/m.c(JJILjava/lang/Object;ILfv/e0;)Z` @ `0x2b1a10`：仅一行 `invoke-virtual/range m.b(JJILjava/lang/Object;ILfv/e0;IZ)I`（0x000d），`return`（0x0016）。**`c` 是 `b` 的薄包装，第 7 个 boolean 参由 c 固定传入。**
- `m.b(JJILjava/lang/Object;ILfv/e0;IZ)I` @ `0x2b1d4c`（regs=24, ins=11）——对应六参：
  | 位置 | 类型 | 我方传入 | 语义（dex 取证） |
  |---|---|---|---|
  | v6 | J (localId) | `883190` | 写入 `k->a`（`new k(JJI)` 0x001a） |
  | v7 | J (msgId) | `19733550` | 写入 `k->b` |
  | v8 | I (type) | `TYPE_HD=0` | 写入 `k->c`（0x001a 的第 3 参） |
  | v9 | Object | `Long.valueOf(msgId)` | 业务附加对象（透传） |
  | v10 | I | `0` | 写入 `k->d`（0x001f `iput v6,k->d`） |
  | v11 | `fv/e0` listener | `newP3Listener` 代理 | `k.a(listener,obj)` 注册（0x0052/0x006f/0x00b9） |

### Q2.3 为什么 `r=true` 却无字节（逐条排除）

1. ** listener 非 null 校验**：`m.b` 开头 `if listener==null` → log "listener is null" → return（0x0007-0x0011）。我方传了代理 listener，**排除**。
2. **判重 `m.e.contains(task)`**：`k.equals/hashCode` 只比较 `a,b,c`=(localId,msgId,type)（`k.equals @0x2b10ec`: 比 a/b/c；`k.hashCode @0x2b1140`: `a_b_c` 拼串 hash）。`m.e` 是"已完成 HashSet"，命中则 log "task already done" 直接 return（0x0021-0x0044）。**此判据是"任务级三元组"，与 `o2(localId).t` 无关**。
3. **`t==1` 判重**（我方 `ForceForward.kt:2286` / AutoHdImg.kt:424）：这是**我方代码自己**的防御（读 ImgInfo2 的 `t==1` 就跳过下载），不是 `m.b` 内部逻辑。真机 fail log:8 `HDIMG fired ... t=0` 证明 `t=0`，**未命中，不是跳过原因**。（P3 的 prepare 日志里 `FORWARD P3 download fired ... r=true` 也证明走到了 `svc.c`。）
4. **真正下载**：`m.h(I)` @（`<init> k 0x2b11ac` 之后）：`new t1(k.a,k.b,k.c,t0,I)`（0x002d-0x003a），`p1.f(t1)` 入 NetScene 队列（0x007f-0x0081）。t1 = CGI `getmsgimg` 图片下载 NetScene。
5. **完成回调**：`m.T(II,k1)` @ `0x2b1f64`、`m.onSceneEnd(II,String,k1)` @ `0x2b24a4` → 调 listener 的 `q`/`l0`（errType/errCode 落 args[5]/[6]，来源 `fv.e0` dex 定义 + 反汇编，见 ForceForward.kt:2319-2324 注释）。真机 `FORWARD P3 bigimg ready=false path=none cb=l0`（fail log:71）证明 **`l0` 回调到达**；源码 ForceForward.kt:2341 要求 `errType==0&&errCode==0&&cbLocal/cbMsg 匹配` 才置 ready，未置 ⇒ **回调携带非成功码**（【推断，高置信】：errType/errCode 至少其一非 0，因源码只在此分支打 `ready=false`，且回调确实到达）。
6. **回读路径门禁**：`o2(localId).e` 指向 36B jpg（fail log:10 `MIO z3 out ...216b....jpg`、台账 su ls 36B）；`p3SrcOf` → `!startsWith("th_") && length>15KB` 三门槛失败 ⇒ ready=false。

### Q2.4 对任务子问题的直接回答

- **`TYPE_HD=0` 是否正确？** 【推断，中置信】值本身与 AutoHdImg 一致、无证据表明错；但 Q1 证明 stock 拿清晰大图靠的是 CDN（其 task 的 `field_fileType` 是另一套值），而 `m.b` 的 type 参数只用于 `k` 判等 + `t1` 构造，**不是"原图/CDN"开关**。换 type 值**不会**把 CGI 通道变成 CDN 通道 ⇒ 不是修复点。
- **`svc.c` 六参语义 / listener 判定 / `args[5]/[6]`**：【源码确定】均正确（Q2.2/Q2.3），非失败原因。
- **`d3.O8().o2(localId)` 的 `t` 字段判重误跳过？** 【真机确定+源码确定】否：真机 `t=0` 未命中；且该判重是我方代码防御而非宿主 `m.b` 逻辑。
- **根因**：CGI `getmsgimg` 通道对此坏图（已无大图、仅占位+缩略图）不产出大图字节；`r=true` 是受理假阳性（与 R34「`svc.c` 返回 true 只=任务受理」一致，RevokeRescue.kt:222）。**没有任何参数/判重/回调修法能让这条通道产出它拿不到的字节。**

### Q2.5 单测提及与变异验证说明

- R76 为 P3 加了 `R76P3WireTest` 等单测（三闸 OK 1082，台账 R76 段）。这些**是 JVM 静态单测，锁的是"参数传递/字段覆盖/两态门控"的代码契约**，**不验证真机下载是否产出字节**。
- 变异验证：R76 P0 修复有 M4~M8 静态变异全判红（台账），证明单测对**代码改动**敏感；但**没有任何单测能覆盖"CGI 通道对特定坏图是否产出大图字节"这一真机事实**——这正是方案 A 被证伪而单测全绿的原因。本问结论不依赖单测，依赖 dex + 真机日志。

## Q3 有没有比当前下载姿势更可靠的 dex 可见拿字节入口？（已答）

> 评估口径：能否让坏图（`216b...`，本地仅占位+缩略图）的**出站 `z3(r0.e)` 指向大图字节**。逐条给前置条件/可行性/风险。

### 候选① `z3("SERVERID://"+msgSvrId)` → `Y2(msgSvrId)` 解析服务端大图路径 + 触发下载

- 【源码确定】`b1.z3(String,String,String,Z)Z` @ `0x2a6ae4`：遇 `SERVERID://` → `substring(11)` 取 msgSvrId → `Long.valueOf` → **`b1.Y2(J)` @ `0x2a363c`**（按 msgSvrId 查 ImgInfo2 表）→ 取 `r0.e`（R75 §4 已独立复核，本轮 code_off 重算一致）。
- 前置条件：`msgSvrId` 可取——【源码确定】`Snapshot.msgSvrId`（`ForwardLogic.kt:388`）来自 `h9.field_msgSvrId`（`ForceForward.kt:1544`），resendFile 经 snap 拿到（`ForceForward.kt:2232`）。
- **可行性判定：❌ 不能拿字节**。`z3/Y2` 只**解析**服务端引用为本地路径（R76 plan §2 表已标"只解析不下载"）；坏图 ImgInfo2 记录的 e 指向 36B 占位/th_，解析结果仍非大图。它不触发任何下载——下载是 Q2 的 CGI 或 Q1 的 CDN 的事，**z3 不是下载入口**。
- 风险：低（只读解析），但**作为字节来源无用**。

### 候选② stock 原生转发链里"拿到大图字节"的直接调用 —— 本轮重点

- 【源码确定】即 Q1 的 `MsgRetransmitUI.B6`（CDN addSendTask，`ok0/m1.e(tj/k)` @ `0x7b52b4`）→ 完成回调 `qf4/m0.l0`（`0x844a0c`）写 `g` → `j6` 的 `R5`（`0x2a710c`）→ 新 localId 的 r0.e 指向大图。
- 前置条件：
  1. **msgSvrId / cdn 参数**：坏图消息 `r0.u` 自带 `cdnbigimgurl`/`aeskey`/`md5`——【真机确定】fail log:55 已见明文；B6 用 `g9.c(r0.u,"msg")` 解析（0x00b8）。
  2. **构造 `tj/k` task**（B6 0x0157-0x01c0 逐字段给出），调 `ok0/m1.e`（0x7b52b4）下发。
  3. **接收完成回调** `fv.e0`/`qf4/m0` 的 `l0`（0x844a0c）——需一个 listener 代理拿回 `g`。
- **可行性判定：✅ 唯一被真机证明能对此坏图拿到清晰大图的 dex 可见通道**（stock 自身就用它）。这是我方当前 DownloadImgService(CGI) 未覆盖的路径。
- 风险：
  - 【推断，中置信】**接入形态未验证**：模块转发不经过 `MsgRetransmitUI` Activity，需在模块流程里主动调 `ok0/m1.e` + 自建 listener 收 `g`。该 task 的 `field_onlycheckexist=true` 是否真拉全字节、回调是否只回 `MsgRetransmitUI` 实例字段（`m0.l0` 内 `iput-object this.h.g`）而非通用 listener 出参，是本方案最大不确定点——**若回调只写 Activity 字段，模块非 Activity 上下文拿不到 g**。这是"A 推 B"假设，须真机探针验证。
  - 【推断，中置信】CDN 参数选择（big vs mid）与 aesKey 有效性依赖消息状态；若消息已被服务端清理，CDN 也可能 404（与 stock 清晰的事实矛盾，故本坏图上大概率有效）。
  - 不碰红线：只读 CDN 参数、不改 ImgInfo2（除非走 R5 落新记录，属新 localId，不覆写原记录）。

### 候选③ `b1.q1(J,[B,PString,PInt,PInt)Z` / `o6.N(String,II)[B` 底层读字节

- 【源码确定】`b1.q1` @ `0x2a2fa0`：构造 `"SERVERID://"+msgId` → `z3(...)` → `x.B([B)Bitmap`，把本地记录字节读进 buffer；`o6.N` 按路径读文件字节（R75 §4）。
- 前置条件：大图**已在本地**。
- **可行性判定：❌ 作为字节来源不可行**（坏图无本地大图，读出来是 36B 占位/15KB th_）；【推断，高置信】**但可作为候选②拿到字节后的"复制/搬运"工具**（R76 plan §3.2 末选定位一致）。
- 风险：低；单独用无意义。

### Q3.4 排序结论

| 候选 | 能否解坏图字节死结 | 定位 |
|---|---|---|
| ① z3 SERVERID→Y2 | ❌ 只解析不下载 | 可保留作路径解析副手，非字节来源 |
| **② stock CDN 链（B6/m1.e/m0.l0/R5）** | **✅ 唯一真机证明可行且 dex 可见** | **首选字节来源；最大风险=回调接入形态** |
| ③ q1/o6.N | ❌ 读本地，坏图无大图 | 仅作②之后的拷贝末选 |

**关键**：deadlock 的本质不是"dex 不可见"，而是"我方选了 CGI 通道（Q2）而非 stock 的 CDN 通道（Q1/Q3②）"。候选②把"字节来源死结"从"不可解"变为"有 dex 可见路径，但接入形态待真机收敛"。

## Q4 裁决（已答）：选 (b)

### Q4.1 三选一结论

> **裁决 = (b) 转方案 B（Hook `j2.R()` after 写 `r0.e/f` 指向自有字节），但字节来源必须从 Q3② 的 stock CDN 链取，不能再用 DownloadImgService.c。**

- **(a) 修下载姿势继续方案 A —— 否决**：Q2 已证明失败根因是**通道选错**（CGI `getmsgimg` 不产字节），非参数/`TYPE_HD`/listener/`t==1` 判重问题；`svc.c` 的 type 参数只用于 `k` 判等和 `t1` 构造（Q2.3），换值无法把 CGI 变 CDN。真机 `jpg 仍 36B` 是硬证据。继续调参 = 在错误通道上空转。
- **(c) 判"dex 可见入口对该坏图拿不到大图字节，P3 不可行" —— 否决**：Q1 已推翻该前提。stock 自带转发用 dex 可见的 CDN 链（`B6→m1.e→m0.l0→R5`）对**同一张坏图**拿到清晰大图；fail log:55 证明坏图消息自带可用 CDN 参数。断言"dex 不可见/不可行"与真机事实矛盾。
- **(b) —— 选定**：字节来源死结**已解**（Q3②）；方案 B 的落点（`j2.R()` after 写 `r0.e/f`）在 R76 plan §3.3 已 dex 论证、且不依赖"新 localId 必须是 R5 产物"——只要让 `R()` 返回的 r0 的 `e/f` 指向大图文件，doScene 的 `z3(r0.e)→o6.N` 就读到大图。**字节从 CDN 拿到后，无需 stock 的 Activity 上下文**即可注入。

### Q4.2 最小改动方向（仅设计，不落码）

1. **字节来源（替换 prepareBigImg 的 DownloadImgService.c 调用）**：照抄 `MsgRetransmitUI.B6` @ `0x62c98c` —— 从 `d3.O8().o2(localId)` 的 `r0.u` 用 `g9.c(r0.u,"msg")` 取 `cdnbigimgurl`/`aeskey`/`md5`，构造 `tj/k`（`task_MsgRetransmitUI_2`，字段见 Q1.2 步①），调 `ok0/m1.e(tj/k)` @ `0x7b52b4` 触发 CDN 下载。
2. **拿回大图路径**：挂只读 listener 代理接 `qf4/m0.l0` @ `0x844a0c`（或其上级 `fv.e0` 回调），回调里按 `s0.b(r0)` @ `0x2b429c` → `z3(r0.e)` @ `0x2a6ae4` 得到大图本地路径；**关键是 listener 必须从回调出参拿路径，不能像 stock 那样写 Activity 字段 `MsgRetransmitUI.g`**（模块无该 Activity）。【推断，中置信，须真机探针验证回调出参是否带 r0/path】
3. **字节落自有临时文件**：按 R76 plan §3.1/§3.2 把 CDN 下载好的大图复制到逐目标 `cache/_<uuid>.jpg`（三门槛 `exists && >15KB && !th_`）。
4. **注入点（方案 B）**：Hook `modelimage/j2.R()` @ `0x2ad864` after，`setObjectField(param.result,"e",tempPath)` + `"f"`（双写，R76 plan §3.3：`r0.l` 写 j、`r0.n` 写 f，必须直接写 e）。交接用 ThreadLocal/TTL Map（R76 plan §8.4 待取证项）。**R() after 是唯一同时满足"在 o2 之后"和"在 doScene 读字节之前"的窗口**。
5. **红线不变**：不动 `CONSTRUCTOR_ID_IMAGE=13`、不动已闭环 m.a/q1.f/q1.h、`b1.u8` 仍不调（CDN 下载用宿主 task，不主动持久化我方路径）。

### Q4.3 真机判据（照抄 R76 plan §6，互斥可验收）

- **正向**：F2 after `MIO z3 out path=.../<md5>.jpg`（非 th_） + 对端字节 ≫15KB + 肉眼清晰 + F2 before `isThumb=false`；多目标各自路径不同。
- **反向**：只切 P3 行为开关开→关，关后回 `th_<md5>`/对端 ~15KB/模糊（自变量唯一）。
- **不倒退**：普通本地大图 P3 开/关都清晰。
- **本轮新增前置探针（b 成立的门槛）**：先只读 hook `ok0/m1.e`/`qf4/m0.l0`，真机确认 ① CDN addSendTask 被触发、② 完成回调能拿到大图路径出参（非仅写 Activity 字段）。**若此项证伪（模块拿不到回调出参且无法绕开 Activity），则 (b) 退化为不可行，此时才落 (c) 并如实标注限制** —— 但不能不经此验证就直接判 (c)。

### Q4.4 置信度与残留风险

- 【源码确定】Q4.2 全部 code_off 与字段写入；Q4.3 判据来自 R76 plan §6（已 dex 论证）。
- 【推断，中置信】最大残留 = **CDN 完成回调的出参形态**：stock 的 `m0.l0` 把路径写进 `MsgRetransmitUI.g` 实例字段（Q1.2 步③），模块非 Activity 需另找出参或自建 listener；此项是本方案唯一可能翻车点，须 Q4.3 前置探针收敛。
- 【推断，低-中置信】R5 参数 U1 语义（R76 plan §8.2）、`field_onlycheckexist=true` 是否真拉全字节——若 B6 的 checkExist 仅探存在不拉字节，则需把 task 的 onlycheckexist 置 false 强制下载（改动点小，但偏离照抄，须单独验证）。
- **总体裁决置信度：中高**。比 (a) 有本质优势（换到真机证明有效的通道），比 (c) 更符合证据（stock 已证可行）；成功与否系于一个可快速真机验证的前置探针。

---

## 结论

- **Q1**：stock 自带转发清晰，靠 `MsgRetransmitUI.B6` 用坏图消息自带的 CDN 参数触发 CDN 下载、`qf4/m0.l0` 把大图路径写回 `MsgRetransmitUI.g`、`j6/R5` 换新 localId，最终 `j2.R()=o2(新).e→z3→o6.N` 读到清字节——**整条链 dex 可见，非微X私有通道**。
- **Q2**：我方失败根因是走了 CGI `getmsgimg`（`m.c→m.b→m.h→t1→p1.f`）而非 CDN；`TYPE_HD=0`、六参、`q/l0` 的 `args[5]/[6]` 均正确，`t==1` 真机未命中（t=0），`r=true` 只是受理假阳性；没有参数修法能让 CGI 通道产出它拿不到的字节。
- **Q3**：唯一能对此坏图拿到大图字节的 dex 可见入口是候选②（stock CDN 链）；`z3/Y2` 只解析、`q1/o6.N` 只读本地，都不是下载入口；字节来源死结从"不可解"变为"有路径，接入形态待验证"。
- **Q4 最终裁决 = (b)**：转方案 B（Hook `j2.R()` after 写 `r0.e/f`），字节来源改走 Q3② 的 stock CDN 链；以"CDN 完成回调出参可取大图路径"的真机前置探针为成立门槛，证伪则退 (c)。

### 是否发现推翻前几轮裁决的新证据

**是，且是本轮决定性发现**：R75/R76 把方向定为"坏图大图字节只能来自微X私有加密 dex、我方 dex 可见入口拿不到、P3 字节来源死结不可解"。本轮从 dex 反汇编（自算 code_off，未抄历史地址）证明——**stock 微信自带转发对同一张坏图就走 dex 可见的 CDN 下载链拿到清晰大图**（`MsgRetransmitUI.B6 0x62c98c → ok0/m1.e 0x7b52b4 → qf4/m0.l0 0x844a0c → R5 0x2a710c → j2.R 0x2ad864`），且真机 fail log:55 证明坏图消息自带明文 CDN 参数。前几轮"只盯 DownloadImgService(CGI) 与微X私有通道、漏掉 stock 原生转发的 CDN 链"是方向性走偏。P3 不应判不可行，应转方案 B 并换字节来源。

---

证据基线（可自证）：code_off 由 `_r35_adnd_dex.py`/`_r35_sns_dis.py` 对 `_cache/dex/classes7.dex`（微信 8.0.48/2588，权威源 `_cache/mm.apk`）逐字节解析得出；真机事实取自 `docs/_r76_p3_realdev_fail1.log` 与 `docs/派发审计台账.md` R76 段；源码行取自 `wxgroup/app/src/main/java/...`（AutoHdImg.kt:424/475-479、ForceForward.kt:2286/2296-2301/2319-2365、ForwardLogic.kt:432-455）。全程只读，未改任何源码/测试/配置。

[DONE]
