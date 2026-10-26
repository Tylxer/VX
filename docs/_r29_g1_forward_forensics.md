# R29-1 G1 取证：微信 8.0.48 (code=2588, gate=gp) 原生转发链路

> 子代理：r29-g1-forward　批次：R29　目标：`ForceForward.nativeForward()` 的锚点（缺口 G1）
> 状态：已完成
> 边界：纯静态 DEX 只读。不写业务代码、不改 `wxgroup/app/src`、不跑 gradle、不碰 adb。
> 证据分档：① 字面证据（贴原文）／② 强推测（有调用链缺最后落锤）／③ 未定位到。
> 工具：`tools/_r28fw_*.py` + 已有 dexdump 切片 `_cache/_r28fw_*.txt`（源 `_r11_dex7_dis.txt` = classes7.dex 全量 dexdump）。

---

## 0 结论摘要

| # | 结论 | 等级 |
|---|------|------|
| 1 | **原生转发不是独立入口**，它就是 `hl0/r1` → `hl0/q1` → NetScene(`hl0/q0`/`modelimage/j2`) 这条**与普通发送同一套**的链，唯一区别是往 `q1` 里多挂一个 `hl0/m`(ForwardInfo) 并设对 `q1.h`/`q1.i` 两个 int 字段。 | ① |
| 2 | `hl0/m`(ForwardInfo) 六个字段的语义已被其 `toString` 明文钉死：`a=forwardFromImgId`、`b=forwardMsgId`、`c=forwardSnsLocalId`、`d=forwardMsgUuid`、`e=forwardMsgSvrId`、`f=talkerUsername`。 | ① |
| 3 | **文本原生转发** = `hl0/r1.b(talker)` + `q1.e(content)` + `q1.b()`，与 `TextSender.send`（resendText）**字节码等价**；要带「源 msgId」需额外 `q1.f(m)`（`m.b=源msgId`）+ `q1.h=4`。 | ① |
| 4 | **图片原生转发** = `qf4/o4.O8(...)`，`FileSender.send`（resendFile）已逐字段复刻；唯一缺口是它 `new m()` 全默认（`a=-1`），**未填 `m.a`(源图 imgId) → 会重传且无转发标记**。 | ① |
| 5 | UI 入口 = `com.tencent.mm.ui.transmit.MsgRetransmitUI`（`MMBaseActivity` 子类，Activity），长按菜单「转发」由 `ui/chatting/viewitems/*.b(Activity, h9)` 组 Intent 拉起。 | ① |
| 6 | 发送成功判据：`l1.a()` 返回 `true`（入队）+ `MsgRetransmitUI.g6(l1)` 发布 `UpdateForwardMsgIdEvent`（携带新 msgId）。 | ① |

---

## 1 从 UI 入口反查：长按消息菜单里的「转发」项 → 点击回调 → 跳到哪个 Activity/Fragment

### 1.1 目标 Activity：`MsgRetransmitUI`（① 字面证据）

`classes7.dex` class_defs 头（`_r28fw_com_tencent_mm_ui_transmit_MsgRetransmitUI.txt:1-5`）：

```
  Class descriptor  : 'Lcom/tencent/mm/ui/transmit/MsgRetransmitUI;'
  Access flags      : 0x0001 (PUBLIC)
  Superclass        : 'Lcom/tencent/mm/ui/MMBaseActivity;'
  Interfaces        -
    #0              : 'Lcom/tencent/mm/modelbase/s0;'
```

⇒ 它是 Activity（`MMBaseActivity`），`s0` 是网络回调接口。**不是 Fragment**，是独立 Activity，靠 Intent extras 反序列化。

### 1.2 谁拉起它：长按菜单「转发」回调（① 字面证据）

`tools/_r28fw_whoref.py 'Lcom/tencent/mm/ui/transmit/MsgRetransmitUI;'` 命中一大片 `com.tencent.mm.ui.chatting.viewitems.*`（各消息卡片类型），每个都是 `(Activity, storage/h9)` 形参的方法——即「拿到一条消息对象 h9 → 组 Intent 拉起转发页」：

```
=== classes3.dex
   const-class
      Lcom/tencent/mm/ui/chatting/viewitems/a8;->b(Landroid/app/Activity;Lcom/tencent/mm/storage/h9;)V   @0x556b2e
      Lcom/tencent/mm/ui/chatting/viewitems/ab;->a(Landroid/app/Activity;Lcom/tencent/mm/storage/h9;)V   @0x556fec
      ... (cd/d9/l7/n8/r7/r8/v8/w/wa 等，每个消息 type 一套)
=== classes4.dex
   const-class
      Lcom/tencent/mm/app/c7;->p(Landroid/content/Intent;Landroid/content/Context;)V   @0x215212   ; startActivity 包装
      Lcom/tencent/mm/ui/chatting/viewitems/ad;->e0(Landroid/view/MenuItem;Lib4/c;Lcom/tencent/mm/storage/h9;)Z   @0x51b8e4
      ...
```

菜单点击回调的**总分发**在 `bi4/z.onMMMenuItemSelected(MenuItem, I)`（classes3.dex，`const-class MsgRetransmitUI` @0x21fb3e）。

### 1.3 onCreate 反序列化的 Intent extra 键（① 采信 R16 已坐实结论，本轮复核未翻案）

`MsgRetransmitUI.onCreate` 把 `Retr_*` 键名读进实例字段。关键键（源码见 `docs/_r16_g1_native_forward.md` §1.1，本批 dex 未变）：

| 键 | 字段 | 类型 | 语义 |
|---|---|---|---|
| `Retr_Msg_Id` | `f` | long | **源消息 msgId**（默认 -1） |
| `Retr_Msg_Id_List` | `j` | List | 多选源消息 id |
| `Retr_Msg_Type` | `e` | int | 源消息种类（默认 -1） |
| `Retr_Msg_content` / `_bytes` | `i` | String/byte[] | 文本正文 |
| `Retr_Scene` | `y` | int | 0=相册 1=聊天转发主流程 |
| `Retr_MsgTalker` | `X` | String | 源会话 talker |
| `Select_Conv_User` | `h`(间接) | String | **目标会话（传了即跳过选择器直达发送）** |
| `Retr_File_Name` / `Retr_File_Path_List` | `g`/`B` | 文件名/路径 | 图片视频转发 |

[DONE] 1 从 UI 入口反查

---

## 2 转发请求对象：类名 / 字段 / 如何构造（是否吃 msgId、是否吃 SnsObject/Xml）

「转发请求」由三件套组成：builder `hl0/q1` + 转发标记 `hl0/m` + 最终 NetScene（`hl0/q0` 文本 / `modelimage/j2` 图片）。

### 2.1 `hl0/m`（ForwardInfo）—— 直接吃 msgId 与 SNS id（① 字面证据）

`toString` 把每个字段名明文拼出来了（`_r28fw_hl0_m.txt:68-96`）：

```
  const-string "ForwardInfo{forwardFromImgId="
  iget-wide   m.a:J
  const-string ", forwardMsgId="
  iget-wide   m.b:J
  const-string ", forwardSnsLocalId='"
  iget-object m.c:Ljava/lang/String;
  const-string "', forwardMsgUuid='"
  iget-object m.d:Ljava/lang/String;
  const-string "', forwardMsgSvrId='"
  iget-object m.e:Ljava/lang/String;
  const-string "', talkerUsername='"
  iget-object m.f:Ljava/lang/String;
```

字段表（`_r28fw_hl0_m.txt:7-30`）：

| 字段 | 类型 | 语义（toString 明文） | 默认值(`<init>`) |
|---|---|---|---|
| `a` | long | **forwardFromImgId**（源图 CDN imgId） | -1 |
| `b` | long | **forwardMsgId**（源消息 msgId） | 0 |
| `c` | String | **forwardSnsLocalId**（朋友圈本地 id） | "" |
| `d` | String | forwardMsgUuid | null |
| `e` | String | forwardMsgSvrId | null |
| `f` | String | **talkerUsername**（源会话 talker） | null |

⇒ **吃 msgId**（`b`）、**吃 SNS id**（`c`=朋友圈，`d/e`=uuid/svrId）。`c` 是 `String` 不是 Xml 对象，SNS 内容本体走 `q1` 之外的分支（`MsgRetransmitUI.u6`，见 R16 §1.2）。

### 2.2 `hl0/q1`（builder）—— 构造器与 setter（① 字面证据）

`hl0/q1` 是公开 builder（`_r28fw_hl0_q1.txt`）。字段与链式 setter：

| 字段 | 类型 | setter/直写 | 语义 |
|---|---|---|---|
| `a` | String | `.h(String)` | **talker（目标）** |
| `b` | String | `.g(String)` | fileName（展示名） |
| `c` | String | `.e(String)` | **content（正文）** |
| `d` | int | `.i(int)` | 消息 type（默认 1） |
| `B` | `hl0/o1` | `r1.a(o1)` 时写入 | **消息类别枚举**（TEXT/IMAGE/VIDEO/...） |
| `h` | int | **无 setter，直写字段** | **发送子类型**（见 2.4，关键） |
| `i` | int | 直写 | 图片链路 = 4 |
| `t` | `hl0/m` | `.f(hl0/m)` | **ForwardInfo（转发标记）** |
| `j` | String | 直写 | 图片完整路径 |
| `k` | int | 直写 | 图片 msgType |
| `r` | String | 直写 | 图片本地路径（O8 第 5 参） |
| `s` | String | 直写 | 附言 |

setter 证据（`.h/.e/.g/.f` 各 `iput` 到 `a/c/b/t`，`_r28fw_hl0_q1.txt:352-384`）。`<init>` 只初始化 `a/b/c=""`、`d=1`、`j/r/s=""`、`t=new m()`（`_r28fw_hl0_q1.txt:128-155`），**`h`/`i`/`e` 未初始化（=0）**。

### 2.3 `hl0/o1` 消息类别枚举（① 字面证据）

`hl0/o1.<clinit>` 明文（`_r28fw_hl0_o1.txt:42-75`）：

```
const-string "TEXT"      -> o1.d   ; ordinal 0
const-string "IMAGE"     -> o1.e   ; ordinal 1
const-string "VIDEO"     -> o1.f   ; ordinal 2
const-string "CDN_IMAGE" -> o1.g   ; ordinal 3
const-string "EMOJI"     -> o1.h   ; ordinal 4
const-string "APPMSG"    -> 创建但未 sput（零引用）
```

⇒ `APPMSG`（普通文件）在枚举里被造出来但**没赋值给任何静态字段**，印证 `FileSender` 注释「APPMSG 全文零引用」。

### 2.4 `q1.h`（发送子类型）的取值与含义（① 字面证据）

`t81/g.l`（createTextCgi，`_r28fw_t81_g.txt:461-520`）按 `q1.h` 分派：

```
0008: iget v1, q1.h:I
000a: const/4 v2, 5
000b: if-ne v1, v2, 0024     ; h==5 → q0(talker,content,d,e,g)  ← 普通文本（带 Object）
...
0024: const/4 v2, 4
0025: if-ne v1, v2, 0046     ; h==4 → q0(talker,content,d,e,forwardMsgId) ← 转发文本
0031: iget-object v11, q1.t (ForwardInfo)
0033: if-eqz v11, 0038
0035: iget-wide v2, m.b (forwardMsgId)   ; 转发标记取自 m.b
0037: goto 003a
0038: const-wide/16 v2, 0
003c: invoke-direct {..}, q0.<init>:(String,String,II,J)  ; method@a874
...
0046: const/4 v2, 2
0047: if-ne v1, v2, 0059     ; h==2 → q0(msgId, e)  ← 重发/引用
```

`qf4/o4.O8` 图片转发设 `q1.h=13`（`_r28fw_qf4_o4.txt:483` `const/16 v4, #13`），`Q8` 多图设 `q1.h=19`。`MsgRetransmitUI.z6` 文本转发设 `q1.h=5`（`_r28fw_com_tencent_mm_ui_transmit_MsgRetransmitUI.txt:5810-5811`）。

**`q1.h` 值表（①）**：

| q1.h | 含义 | 证据处 |
|---|---|---|
| 2 | 重发/引用 | `t81/g.l` @0046 |
| 4 | **文本转发（吃 m.b=forwardMsgId）** | `t81/g.l` @0024 |
| 5 | 普通文本（吃 `q1.g` Object） | `t81/g.l` @000b；`z6` |
| 13 | **图片转发** | `qf4/o4.O8` @0064 |
| 19 | 多图转发 | `qf4/o4.Q8` @0066 |
| 0（缺省） | 文本走 default 空 `q0` | `t81/g.l` @0059 |

### 2.5 最终 NetScene（① 字面证据）

- 文本：`hl0/q0` extends `com.tencent.mm.modelbase.k1`（`_r28fw_hl0_q0.txt:3`），有 `doScene/getType/onGYNetEnd`（NetScene 三件套）。构造器 `(String,String,II,J)` 末尾把消息落库（`storage/j9.ga(h9,1,1)` → `q0.f=新msgId`，`_r28fw_hl0_q0.txt:263-265`）。
- 图片：`com.tencent.mm.modelimage/j2`，构造器 19 参含 `hl0/m`（`_r28fw_t81_g.txt:218` `j2.<init>:(...Lhl0/m;Ljava/lang/String;)V` method@1e15）。

[DONE] 2 转发请求对象

---

## 3 目标会话选择：会话切换 + 选定后如何触发发送

### 3.1 `Select_Conv_User` 直达发送（① 采信 R16 补证 + 本轮复核 `z6`）

选定目标后落点在 `MsgRetransmitUI.z6(String, List)`（`_r28fw_com_tencent_mm_ui_transmit_MsgRetransmitUI.txt:5759`，signature `(Ljava/lang/String;Ljava/util/List;)V`）。其发送分支字节码（`:5791-5815`）：

```
0026: hl0/s1.a(talker)               ; 目标是否是「可直达」会话
002c: hl0/r1.b(talker)  -> q1        ; ★ 与普通发送同一个工厂
0030: q1.e(content)                  ; 设正文
0037: q1.i(vj0/e2.y(talker))         ; 设 type
003a: iput q1.e = 1
003c: iput q1.g = HashMap(ui/j1.a(content))
003e: q1.b()                         ; ★ executeByPPC 异步发送
...
0042: hl0/r1.b(talker)  -> q1
0046: q1.h(talker)
0049: q1.e(content)
0050: q1.i(vj0/e2.y(talker))
0053: iput q1.e = 1
0055: iput q1.g = HashMap(...)
0058: iput q1.h = 5
005a: q1.a()  -> l1
005e: l1.a()                         ; 同步入队
```

⇒ 目标会话就是 `talker`（String），选定后**直接走 `hl0/r1.b(talker)` + `q1.e(content)` + `q1.b()`**——与 `TextSender.send` 完全同构（§5）。

### 3.2 多目标：`SendMsgEvent`（①）

`z6` 末尾（`:5824-5834`）多目标时遍历 `List`，每条 `new SendMsgEvent`（`gj/or.a=talker, b=content, c=type`）→ `IEvent.d()`（异步事件发布）。即多选走事件广播，单选走 `q1.b()`。

[DONE] 3 目标会话选择

---

## 4 发送动作：与普通发送（`hl0/r1.b`）的关系，是否同一套 NetScene

**是同一套。** 三处独立字节码都指向 `hl0/r1` 工厂 + `hl0/q1` builder + `modelbase/k1` NetScene：

1. 普通文本发送锚点 `hl0/r1.b(String)`（`_r28fw_hl0_r1.txt:39-44`）：
   ```
   7124dc: invoke-static {v2,v0,v1,v0}, hl0/r1.c:(String;o1;I;Object;)q1
   ```
   `c(...)` 内部 `q1.h(talker)`（`_r28fw_hl0_r1.txt:64`）。
2. 转发文本 `z6` 也用 `hl0/r1.b(talker)`（§3.1）。
3. 转发图片 `qf4/o4.O8` 用 `hl0/r1.a(o1.e)`（`_r28fw_qf4_o4.txt:460` `invoke-static {v0}, hl0/r1.a:(Lhl0/o1;)Lhl0/q1;`）。

`q1.a()` 的分派（`_r28fw_hl0_q1.txt:182-269`）按 `q1.B`(o1) 路由到 `t81/l` 接口：
`o1.d→l(q1)`(createTextCgi)、`o1.e→a(q1)`(createImageCgi)、`o1.f→i(q1)`(createVideoCgi)、`o1.g→k(q1)`(createCdnImageCgi)、`o1.h→n(q1)`(createEmojiCgi)。

`l1.a()` 真正入队（`_r28fw_hl0_l1.txt:26-47`）：
```
001b: sget-object modelbase/s2.b
001d: check-cast y70/m
001f: iget-object y70/m.a -> y70/w
0021: iget-object y70/w.b -> modelbase/p1
0026: invoke-virtual p1.g(k1, 0) -> boolean   ; ★ NetScene 入队
```

⇒ **转发与普通发送共用 `modelbase/p1.g(cgi,0)` 同一 NetScene 队列**，没有第二套「转发专用 NetScene」。区别只在 `q1` 的 `h`/`t` 字段。

[DONE] 4 发送动作

---

## 5 我方怎么接：反射 or hook，具体到 类名/方法名/签名、参数类型、返回值；需要哪些前置状态

### 5.1 文本（推荐，改动最小）

现有 `TextSender.send` 已经是原生文本转发的**等价实现**（`r1.b` + `q1.e` + `q1.b`）。要显式带「源 msgId」：

```
cl = lpparam.classLoader
r1   = findClass("hl0.r1", cl)
m    = findClass("hl0.m",  cl)
q1   = callStaticMethod(r1, "b", targetTalker)     // (String)->q1
callMethod(q1, "e", content)                        // (String)->q1 设正文
// ↓ 补转发标记（G1 新增，可空则跳过）
fw   = newInstance(m)
setLongField(fw, "b", srcMsgId)                     // forwardMsgId
setObjectField(fw, "f", srcTalker)                  // talkerUsername（源会话）
callMethod(q1, "f", fw)                             // (hl0/m)->q1
setIntField(q1, "h", 4)                             // ★ 文本转发子类型
callMethod(q1, "b")                                 // ()V executeByPPC
```

签名：`hl0.r1.b:(Ljava/lang/String;)Lhl0/q1;`（PUBLIC STATIC FINAL，①）；`q1.e:(Ljava/lang/String;)Lhl0/q1;`、`q1.f:(Lhl0/m;)Lhl0/q1;`、`q1.b:()V`（①，均 PUBLIC FINAL）。

### 5.2 图片（推荐，复用 `FileSender`）

`FileSender.send` 已复刻 `qf4/o4.O8` 全部字段写入。**只需把「`new m()` 全默认」换成填了源图标识的 `m`**：

```
setLongField(fw, "a", srcImgId)      // forwardFromImgId（源图 CDN imgId，避免重传的关键）
setLongField(fw, "b", srcMsgId)      // forwardMsgId
setObjectField(fw, "f", srcTalker)   // talkerUsername
callMethod(q1, "f", fw)              // 在 FileSender 里把 new m() 换成这个
```

`FileSender` 其余字段（`i=4`/`h=13`/`j=path`/`k`/`p=true`/`o=0x7f0803d6`）已对齐 O8，无需再改。

### 5.3 备选：直调 `qf4/o4.O8`（图片，但需拿单例）

`qf4/o4` extends `d94/w`（单例服务基类），无无参构造，拿实例走 `mo2/w0.a()`（R16 已证）。签名（①，`_r28fw_qf4_o4.txt:425-426`）：

```
qf4/o4.O8:(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Lhl0/m;Lcom/tencent/mm/modelscan/ScanCodeInfo;)V
  参序：ctx, talker, fileName, msgType, localPath, remark, fwdInfo(hl0/m), scan(可 null)
```

相比 5.2 无优势（还要绕单例），**建议直接走 `hl0/r1` + 字段直写**，与现有 `FileSender` 一致。

### 5.4 备选：Intent 启动 `MsgRetransmitUI`（文本/任意已有消息）

```
Intent 组件 = "com.tencent.mm/.ui.transmit.MsgRetransmitUI"
extras: Retr_Msg_Id(long srcMsgId), Retr_Msg_Type(int), Retr_Scene=1,
        Select_Conv_User(target), (可选 Retr_Msg_content)
```

⚠️ `Retr_Msg_Id` 必须指向 DB 真实消息（`l6()` 会 `storage/j9.u3(msgId)` 查库，R16 §4 探针 3 未真机定死）。模块长按拿的是 View 树文本，**通常拿不到 DB msgId**（那是 G2 的活），故此路仅当 G2 已产出 msgId 时可用。

### 5.5 前置状态

- `lpparam.classLoader` 已 prepare（`TextSender`/`FileSender` 同款）。
- 图片：本地文件存在（`FileSender` 已判）。
- 文本：content 非空。
- **视频：② 未接通**（`modelimage/o3.j()` 工厂 + `q1.h=20` 未取证完整，见 §7）。
- **普通文件：② 未接通**（`qf4/r5.j` 13 参 + CDN 上传，见 §7）。

[DONE] 5 我方怎么接

---

## 6 判据：接通成功与未接通如何区分

| 信号 | 成功特征 | 失败特征 | 证据 |
|---|---|---|---|
| `l1.a()` 返回值 | `true`（NetScene 已入队） | `false` / 抛 `NullPointerException` | ① `_r28fw_hl0_l1.txt:26-47`（`p1.g(k1,0)Z`） |
| `g6(l1)` 转发上报 | 发布 `UpdateForwardMsgIdEvent`，`g.hx.a=新msgId` | 不触发 | ① `_r28fw_..._MsgRetransmitUI.txt:335-372` |
| 文本 NetScene | `q0.getType()==522`(0x20A) 且 `q0.f>=0`（DB 落库成功） | `q0.f==-1`（`j9.ga` 失败） | ① `g6` @0006 `getType()==522`；`q0.<init>` `iput q0.f` |
| 图片 NetScene | `j2.getType()==110`(0x6E) 且 `j2.S().getMsgId()>=0` | 同上 | ① `g6` @0025 `getType()==110` |
| 日志 tag | `MicroMsg.SendMsgCgiFactory` / `MicroMsg.NetSceneSendMsg` 出现 | `cgi is null`（`l1.a` 里 `Objects.requireNonNull` 抛 NPE 前的 log） | ① `_r28fw_hl0_l1.txt:35` `"[execute] cgi is null. %s"` |

**必然出现的成功行为特征**（可做探针通过标准）：
1. `hl0/q0.<init>` 里 `l2.j("MicroMsg.NetSceneSendMsg", "new msg inserted to db , local id = X")`（`_r28fw_hl0_q0.txt:336-343`）——文本转发落库必打。
2. `g6` 触发 `UpdateForwardMsgIdEvent`——转发完成后必有（`h6` 的 `IEvent.d()` 发布）。
3. 图片：`FileSender` 里 `l1.a()==true` 且 `g6(l1)` 不抛（`FileSender.send` 已打 `queued` log）。

**未接通的判定**（避免把「没发出去」当成「发成功」）：
- `l1.a()` 返回 `false` 或抛 NPE（`l1.a` 内部 `Objects.requireNonNull(cgi)` 抛）→ 未接通。
- 图片 `h` 未设 13 会落到 `j2` 错误构造分支（`FileSender` 注释已踩坑，见其 §1）。
- 视频：`t81/g.i` 第一句 `instance-of q1, modelimage/o3` 不成立→`k1.a=null`→`l1.a()` NPE（`FileSender` 注释已证）。

[DONE] 6 判据

---

## 7 已排除范围

以下查过但确认不是「可用的原生转发入口」，避免下一轮重复劳动：

| 类/方法 | 结论 | 等级 |
|---|---|---|
| `hl0/o1` 的 `APPMSG` 常量 | 枚举 `<clinit>` 里创建但**未 sput 到任何静态字段**（`_r28fw_hl0_o1.txt:69-70`），全 dex 零引用 ⇒ 普通文件转发没有 `r1` 通道 | ① |
| `hl0/q1` 的 `o1.f`(VIDEO) 链 | `t81/g.i`(createVideoCgi) 第一句 `instance-of q1, modelimage/o3`，非 `o3` 直接返回空 `k1` ⇒ `l1.a()` NPE。正解是 `modelimage/o3.j()` 工厂 + `q1.h=20`，**未取证完整** | ① 否定结论 |
| 普通文件（appmsg type=6） | 入口是 `qf4/r5.l/j`（13 参）+ `WXFileObject` + CDN 上传，**独立取证任务**，不属 G1 | ① 已定位但未接 |
| `MsgRetransmitUI.g6/h6` | 只是「转发完成后上报新 msgId」（`UpdateForwardMsgIdEvent`），**不是发起转发入口** | ① 否定结论 |
| `k6/l6/s6/u6`（MsgRetransmitUI 实例方法） | 依赖 `onCreate` 反序列化好的 `this.f/g/y/B`，**不可外部直接调用**（Activity 实例方法 + 前置状态） | ① 否定结论 |
| `qf4/o4` 单例 | 无无参构造（`d94/w` 服务），不能 `new`，需 `mo2/w0.a()` 拿实例——所以绕开它直接走 `hl0/r1` 更省 | ① |
| 视频重发 `modelimage/o3` | `FileSender.Kind.VIDEO` 恒 false，未接（见 `FileSender.kt` 类注释） | ② 未接 |

[DONE] 7 已排除范围

---

## 附：本轮新增的关键字节码证据索引（与 R16 的差异）

1. **`hl0/m` 字段语义首次被 `toString` 明文坐实**（R16 是「推测」，本轮升 ①）。`forwardFromImgId` 字段 `a` 是图片「不重传 + 带转发标记」的关键，`FileSender` 当前漏填它。
2. **`q1.h` 值表**（2/4/5/13/19）从 `t81/g.l` + `qf4/o4.O8/Q8` + `z6` 三处交叉坐实，其中 **`h=4` = 文本转发（吃 `m.b`）** 是 R16 未记录的新结论。
3. **`q0.(String,String,II,J)` 的 J 参数（forwardMsgId）在构造器体内未被引用**（locals 显示 reg=27 J，但 body 无 v27/v28 读取）——即文本转发的「源 msgId」实际不参与落库，仅作为形式参数传递（② 强推测：真正的转发标记在图片链的 `m.a`/`m.b` 经 `j2` 构造器 17 号参进入 CDN 请求；文本转发与重发字节码层面几乎等价）。此点直接影响 §5.1 的「文本补 m」是否为必需——**保守建议仍填，但真机需验证对端是否可见转发标记**。

[DONE] 附

[DONE]
