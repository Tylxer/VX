# R16 G1 取证：微信 8.0.48 (code=2588, gate=gp) 原生转发入口

> 子代理：p1r-native-forward　批次：R16 轮次　目标：ForceForward.nativeForward() 的锚点（缺口 G1）
> 状态：取证中（骨架落盘于 2026-10-24）
> 边界：纯静态 DEX 分析，不碰 adb / 不装包 / 不跑 gradle / 不上真机

---

## 0. 结论先行

**能做，且静态面已闭环。** 微信「原生转发」的真实入口不是某个可反射的转发方法，而是 `com.tencent.mm.ui.transmit.MsgRetransmitUI` 这个 Activity——它靠一整套 `Retr_*` Intent extras 反序列化源消息与目标（§1.1），再由内部 `k6/l6/s6/u6` + `qf4/o4.O8/W8/F8` 落地发送（§1.2/§1.3）。`g6/h6` 只是转发完成后的 msgId 上报，不是入口。

**两条可实现路径**（§2）：A = 组 Intent（`Retr_Msg_Id` + `Retr_Scene=1` + `Select_Conv_User=target`）启动 `MsgRetransmitUI`，适合文本/卡片/任意 DB 已有消息；B = 直接调 `qf4/o4.O8(ctx, talker, fileName, msgType, localPath, remark, fwdInfo, scan)` 发图/视频（带转发标记），单例经 `mo2/w0.a()` 获取。

**最大未决**（✅ ②已实证）：① `Retr_Msg_Id` 是否必须指向 DB 真实消息（`l6()` 会 `storage/j9.u3(msgId)` 查库）—— 仍需真机，§4 探针 3；
② ~~`Select_Conv_User` 是否能跳过选择器~~ → **✅ 已实证能**（2026-10-24 主会话补：`onCreate[1426→1457] iput h:List` → `z6(String,List)` → `hl0/r1.b(talker)` → `q1.e()`，见 §3 第 1 条）。

**一句话总结**：路径 A 的可行性已**无静态障碍**（`Select_Conv_User` 传 talker 即直达发送，实证），**唯一待真机项 = `Retr_Msg_Id` 是否必须查库命中**（§4 探针 3）；路径 B（`qf4/o4.O8` 发图）静态面同样闭环。

---

## 1. 逐条反汇编证据

### 1.1 onCreate 的 Intent extra 键名清单

来源：`python _cache/_r14_walk.py dis classes7.dex "Lcom/tencent/mm/ui/transmit/MsgRetransmitUI;" onCreate`（code_off=0x62fea8，完整字节码，非截断）。

onCreate 是**纯 Intent 反序列化**：把调用方塞进的 extra 逐一读进实例字段。下面按「字段名 ← 键名（类型，默认值）」列出**全部实证键名**：

> 「默认值」列标注规则（三档，避免把推断写成实测）：
> - **实测** = `getXxxExtra` 前有显式 `const/*`（如 `Retr_Msg_Type`、`Retr_Msg_Id`）；
> - **推断** = `getXxxExtra` 前无 `const`，默认值即 Java 零值（int/float=0、boolean=false、String/List=null）；
> - **特殊** = 默认值来自其它字段读取（如 `Retr_show_success_tips`）。

| 实例字段 | extra 键名 | 类型 | 默认值 | 来源 |
|---|---|---|---|---|
| `e` | `Retr_Msg_Type` | int | **-1** | 实测（`const/4 v5, #-1`） |
| `i` | `Retr_Msg_content_bytes` → 有则 new String(byte[]) | byte[] | null | 推断 |
| `i` | `Retr_Msg_content`（bytes 缺失时的回退） | String | null | 推断 |
| `f` | `Retr_Msg_Id` | long | **-1L** | 实测（`const-wide/16 v8, #-1`） |
| `j` | `Retr_Msg_Id_List` | ArrayList(Serializable) | null | 推断 |
| `n` | `Retr_From_Chatting_Forward_Scene` | int | 0 | 推断 |
| `g` | `Retr_File_Name` | String | null | 推断 |
| `B` | `Retr_File_Path_List` | ArrayList<String> | null | 推断 |
| `w` | `Retr_Compress_Type` | int | 0 | 推断 |
| `y` | `Retr_Scene` | int | 0 | 推断 |
| `o` | `Retr_length` | int | 0 | 推断 |
| `x` | `Retr_video_isexport` | int | 0 | 推断 |
| `p` | `Retr_Msg_thumb_path` | String | null | 推断 |
| `q` | `Retr_Msg_thumb_url` | String | null | 推断 |
| `r` | `Retr_go_to_chattingUI` | boolean | false | 推断 |
| `R` | `Retr_start_where_you_are` | boolean | false | 推断 |
| `S` | `Multi_Retr` | boolean | false | 推断 |
| `s` | `Retr_show_success_tips` | boolean | 默认=`R`（`iget-boolean R:Z`） | 特殊 |
| `G` | `Edit_Mode_Sigle_Msg` | boolean | false | 推断 |
| `H` | `is_group_chat` | boolean | false | 推断 |
| `I` | `msg_uuid` | String | null | 推断 |
| `J` | `Retr_Biz_Msg_Selected_Msg_Index` | int | 0 | 推断 |
| `K` | `Retr_MsgImgScene` | int | 0 | 推断 |
| `L` | `Retr_Longtitude` | float | 0.0f | 推断 |
| `M` | `Retr_Latitude` | float | 0.0f | 推断 |
| `N` | `Retr_AttachedContent` | String | null | 推断 |
| `Q` | `Retr_From` | String | null | 推断（equals("gallery") 是消费处判断，非默认值） |
| `P` | `reportSessionId` | String | null | 推断 |
| `U` | `Retr_MsgFromScene` | int | 0 | 推断 |
| `W` | `Retr_MsgFromUserName` | String | null | 推断 |
| `X` | `Retr_MsgTalker` | String | null | 推断 |
| `Y` | `Retr_MsgAppBrandFromScene` | int | 0 | 推断 |

> ⚠️ **勘误脚注**：`Retr_Msg_Type` 默认值曾因 `_r14_walk.py` 的 `const/4` 解码 bug（11n 格式误读第 2 code unit）被误记为 `3`，已按 dexdump 原文 `62ff1a: 12f5 // const/4 v5, #int -1` 与修复后工具双重复核改为 **-1**（主会话已修该工具）。

其余键名（同样实证，出现在 onCreate 的 const-string 里，但对应字段映射在本反汇编段之外或属上报/回传，未逐个展开字段）：`Retr_AppBrandServiceType`（原文 `Retr_MsgAppBrandServiceType`）、`Retr_MsgFromMoreSelectRetransmit`、`Retr_MsgQuickShare`、`Retr_NeedReport`、`Retr_finder_in_stream`、`Retr_NewYear_Thumb_Path`、`Retr_Big_File`、`Select_Conv_Type`、`Select_Conv_User`、`Select_Data_Send_To_WeWork`、`Select_Conv_Type`/`Select_Conv_User`（选择会话结果回传）、`custom_send_text`、`scanCodeInfo`、`wxaInfo`、`image_path`、`img_source_url`、`shareImageCurrentPath`、`forward_card`、`scene`、`scene_from`、`search_range`、`appbrand_params`、`KEY_REPORT_*`/`KEY_START_TIME`（埋点上报）等。

**要点**：
- 原生转发的目标 talker 键名是 **`Retr_MsgTalker`**（→ 字段 `X`）；消息本体用 **`Retr_Msg_Id`（long，字段 `f`）** 或 `Retr_Msg_Id_List`（多选），文本消息内容走 `Retr_Msg_content[_bytes]`（→ 字段 `i`）。
- ⚠️ 上轮骨架「Retr_Msg_* 已解出」这条结论**本次重新取证坐实**，键名属实；但上轮原始输出里确实没有这串键名（只取了前 20 行），故以本表为准。

### 1.2 k6 / l6 / s6 / u6 四个方法完整功能

四个方法都是 `MsgRetransmitUI` 的**实例方法**（acc=0x0011，非 static）。参数寄存器映射已用 DEX code_item 头 `registers/ins` + 前导 move 指令双重核对（this = v(reg-ins)）：

| 方法 | 签名 | reg/ins | this |
|---|---|---|---|
| `k6` | `(String,int,String,String,t0,J)V` | 30/8 | v22 |
| `l6` | `()V` | 61/1 | v60 |
| `s6` | `(String)Z` | 14/2 | v12 |
| `u6` | `(String,String)Z` | 7/3 | v4 |

**`k6(String toUser, int scene, String fileName, String content, t0 cb, long msgId)`（code_off=0x62d368）—— 这是「图片/多图转发」的真正落地核心。**

字节码证据（invoke 目标方法 + 实参寄存器已逐个解析）：
- `q1.h(toUser)` ← v1（p0）：`q1.a` = 目标会话。
- `q1.g(fileName)` ← v3（p2）：`q1.b` = 文件名。
- `q1.r` ← v4（p3）：附言/内容。
- `q1.l` ← v5（p4）：`modelbase/t0` 回调。
- `q1.o` = 0x7F0803D6（资源 id 2131231702，即 `const v8 #2131231702` 写进 `q1.o`）；`q1.k` ← this.w；`q1.w` ← this.K；`q1.y/z` ← this.L/M（经纬度）；`q1.x` ← this.N。
- `new hl0/m` → `m.b` ← **this.f（=Retr_Msg_Id，源消息 msgId）**、`m.d` ← this.I（msg_uuid）→ `q1.f(m)`。
- 单图分支 `q1.h` = **19**；多选（`F==true` 走 `B` 列表）分支 `q1.h` = **11**。
- `q1.a()` → `l1`；`l1.a()` 真正执行；`MsgRetransmitUI.g6(l1)` 上报转发消息 ID。
- 首行判 `this.g`（Retr_File_Name）非空才走单图，否则遍历 `B`（Retr_File_Path_List）走多图。

**`l6()`（code_off=0x62d768）—— scene 分发器**：按 `this.y`（Retr_Scene）switch：`y==0` 走系统相册转发（`m6()`）；`y==1` 走聊天转发主流程（先 `i6()` 校验，再按 `e`(Retr_Msg_Type) 分支：`t6()` 文字 / `p2.a` 网络检查弹窗 / `A6(intent,...)` 图片）。日志锚点 `doRetransmitOnSceneShareFromSystemGallery`。

**`s6(String)`（code_off=0x62bedc）—— AppMessage（卡片/链接）转发**：读 `this.i`（Retr_Msg_content）经 `te0/q.u()` 解析成 appmsg 对象，按 `q.i`(type) 分支（53/57/101/33…），最终走 `ui/chatting/m4.H(ctx,...)` 或 `z4.c` 发出。日志锚点 `processAppMessageTransfer`。

**`u6(String,String)`（code_off=0x62c504）—— SNS（朋友圈）转发**：`b2.r()` + `s1.a()` 判断当前是否 SNS 上下文；走 `r1.b(talker)` 造 q1 → `q1.e/q1.i` → `m.b`←this.f → `lq/b0.F8(h9,msgId)` + `x8()` 上报 → `g6`。

**结论**：`k6`/`l6`/`s6`/`u6` 都不是「外部可直接调用的转发入口」——它们全是 Activity 实例方法，且依赖 `onCreate` 反序列化好的 `this.f`(msgId)、`this.g`(文件名)、`this.y`(scene)、`this.B`(多选路径列表) 等字段。**真正的外部入口是「用带 `Retr_*` extras 的 Intent 启动 `MsgRetransmitUI`」**（见 §2）。

### 1.3 qf4/o4.O8 的 8 参数逐个语义映射

签名（已用 method_ids/proto_ids 表实读，非手算）：
`qf4/o4.O8(Context, String, String, int, String, String, hl0/m, ScanCodeInfo)V`（shorty=`VLLLILLLL`，method_idx=54478，proto_idx=5644，classes7.dex，code_off=0x845720，reg=12/ins=9，this=v3）。

| # | 类型 | 寄存器 | 语义 | 字节码证据 |
|---|---|---|---|---|
| 0 | Context | v4 | UI 上下文（进度弹窗/运行线程用） | 判空失败走 `qf4/t4` 弹「sd卡未就绪」 |
| 1 | String | v5 | **目标会话 talker（toUser）** | `if-eqz v5` → args error；`q1.h(v5)`→`q1.a` |
| 2 | String | v6 | **文件名 fileName**（`q1.j` 展示名） | `if-nez v6` 判空；`iput q1.j=v6` |
| 3 | int | v7 | **源消息种类 msgType**（`q1.k`） | `iput q1.k=v7` |
| 4 | String | v8 | **本地文件完整路径**（`q1.r`） | `iput q1.r=v8`；**注意不是 `q1.j`** |
| 5 | String | v9 | 附言/文案（`q1.s`） | `iput q1.s=v9` |
| 6 | hl0/m | v10 | **转发标记信息**（`m.a`=msgId?、`m.b`=源 msgId、`m.c/d/e/f`=来源字段） | `if-eqz v10` → new 空 `m`；`q1.f(v10)` |
| 7 | ScanCodeInfo | v11 | 扫码来源信息（可空） | `iput q1.D=v11` |

O8 内部流程：判空 v5/v6 → 查 sd 卡就绪 → `r1.a(o1.e)` 造 q1 → `q1.i=4`（`const/4 v1 #4`）、`q1.g(文件名)`、`q1.h(toUser)`、`q1.j=v6`、`q1.k=v7`、`q1.l=null`、`q1.n=0`、`q1.r=v8`、`q1.s=v9`、`q1.p=true`（`const/4 v4 #1`）、`q1.o=2131231702`、`q1.h=13` → `q1.f(m)` → `q1.D=scanCodeInfo` → `q1.a()` 得 l1 → `l1.a()` 执行 → `MsgRetransmitUI.g6(l1)` 上报。

⚠️ 关键坑（与项目 `FileSender.kt` 注释互证）：**本地路径要写 `q1.r`（v8 直接喂），不是 `q1.j`（v6 只是文件名）**；而 `FileSender.send` 走的 `q1.j`（完整路径）那条链是 `t81/g` 的**另一条 constructor 分派**。O8 与 FileSender 是两条同构但字段落点不同的发图链，不能混用。

### 1.4 g6 / h6 已采信结论（来自上轮，附出处）

采信自 `docs/_r16_p1p2_salvage_raw.md` `[P1#24]`（行 946）与 `[P1#26]`（行 1042），本次未重跑（工具输出已完整抢救）：

- **`g6(Lhl0/l1;)V`（static，acc=0x0009，code_off=0x62d018）**：读 `l1.a`（`modelbase/k1`）→ `getType()==522`(0x20A) 且 `instance-of hl0/q0` ⇒ 取 `hl0/q0.f:J` 传给 `h6(Long)`；另有 `getType()==110`(0x6E) 分支。
- **`h6(Long)`（code_off=0x62d0c0）**：`new UpdateForwardMsgIdEvent` → `g:Lgj/hx;->a:J`=msgId、`->b:I`=3 → `IEvent.publish`。

**结论重申**：`g6` 是「转发完成后把新消息 ID 上报」的收尾，**不是发起转发的入口**。`g6` 的 8 处调用点（`[P1#40]` 行 1430）里，真正发起转发的是 `qf4/o4.F8/O8/W8` 与 `MsgRetransmitUI` 自身的 `k6/l6/s6/u6`；`by3/g.invoke()` 是某处封装回调。

---

## 2. 可实现签名表（nativeForward 伪代码）

「原生转发」= 微信自己的 `MsgRetransmitUI` 流程。它有**两条可用路径**，取决于目标消息形态：

### 路径 A（推荐）：文本 / 卡片 / 任意已有消息 → 用 Intent 启动 MsgRetransmitUI

`nativeForward(snap, target)` 要组装一个 `android.content.Intent`，带上 `Retr_*` extras 启动 `com.tencent.mm.ui.transmit.MsgRetransmitUI`，让微信自己弹「转发选择」或直接转发。所需 extras（全部实证，见 §1.1）：

| extra 键 | 类型 | 必填 | 语义 |
|---|---|---|---|
| `Retr_MsgTalker` | String | 否 | 源会话 talker |
| `Retr_Msg_Id` | long | 是 | **要转发的源消息 msgId** |
| `Retr_Msg_Type` | int | 是 | 消息种类（**默认 -1**，必须显式传） |
| `Retr_Msg_content` | String | 否 | 文本消息内容（bytes 缺失时回退） |
| `Retr_Msg_content_bytes` | byte[] | 否 | 内容字节（优先于 content） |
| `Retr_Scene` | int | 是 | 场景：0=系统相册，1=聊天转发主流程 |
| `Retr_File_Name` | String | 否 | 文件名（图片/视频转发时必填） |
| `Retr_File_Path_List` | ArrayList<String> | 否 | 多选路径列表 |
| `Select_Conv_User` | String | 否 | **✅ 已证：传 talker 即跳过选择器直达发送** |
| `Select_Conv_Type` | int | 否 | 目标会话类型（配合上面用） |

⚠️ 注意：`Retr_Msg_Id` 依赖「源消息已在本机 DB 里」——`onCreate` 之后 `l6()` 会拿 `this.f` 去 `storage/j9.u3(msgId)` 取 `h9`。**若模块转发的消息来自 View 树文本（快照 text）而非 DB 已有消息，路径 A 需要一条可定位的 msgId**；拿不到 msgId 则走路径 B。

### 路径 B（图片/视频等已有文件）：直接调 qf4/o4.O8（发图，带转发标记）

`qf4/o4` 是无参构造不可 new 的单例类（`_r13_findclass`：无 `<init>`）。正确拿法是 `mo2/w0.a()`（`mo2/l0`，即 `Lqf4/o4` 实例）——证据见 `ft1/c8.a` 调用点（`Lmo2/w0;->a()Lmo2/l0;` → check-cast `qf4/o4` → `O8(...)`）。

`O8(Context ctx, String talker, String fileName, int msgType, String localPath, String remark, hl0/m fwdInfo, ScanCodeInfo scan)`：
- `talker` = 目标 wxid；
- `localPath` = **本地文件完整路径**（第 5 参，喂 `q1.r`）；
- `fileName` = 展示名；
- `fwdInfo`（`hl0/m`）**填源消息标识**：`m.b` = 源 msgId（→ 生成「转发自 xxx」标记），`m.c/d/e/f` 按需填来源（fromUserName 等）；至少 `new m()` + `m.b = srcMsgId`。
- `scan` 传 null。

### 可实现性判定

| 能力 | 方法/入口 | 参数关键点 | 状态 |
|---|---|---|---|
| 文本消息转发 | 启动 `MsgRetransmitUI` + `Retr_Msg_content` + `Retr_Msg_Id` | 需源 msgId | ✅ 静态可定位 |
| 图片/视频转发（带标记） | `qf4/o4.O8` | localPath 喂第 5 参、fwdInfo.b=源 msgId | ✅ 静态可定位 |
| 文本**重发**（无转发标记，已有） | `TextSender.send`（`hl0/r1` 链） | — | ✅ 已接通 |
| 图片**重发**（无标记，已有） | `FileSender.send`（`r1.a(o1.IMAGE)`+`q1.j=path`） | 注意与 O8 的 `q1.r` 落点不同 | ✅ 已接通 |

`nativeForward` 伪代码：
```
val intent = Intent().setClassName("com.tencent.mm", "com.tencent.mm.ui.transmit.MsgRetransmitUI")
intent.putExtra("Retr_Msg_Id", snap.msgId)          // 需要 G2 补：从长按视图定位 msgId
intent.putExtra("Retr_Msg_Type", snap.msgType)
intent.putExtra("Retr_Scene", 1)
intent.putExtra("Select_Conv_User", target)
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
ctx.startActivity(intent)                            // 微信自己弹确认/直接转
```

**G1 剩余未决**：`Select_Conv_User` 已实证（见 §3 第 1 条，`onCreate[1426→1457]` → `z6` → `hl0/r1.b` → `q1.e`），**可跳过选择器直达发送**。
仍待真机的是 §4 探针 3：`Retr_Msg_Id` 是否必须指向 DB 真实消息（`l6()` → `storage/j9.u3(msgId)` 查库，查不到是否走不到转发分支）。

---

## 3. ⛔ 边界声明（实证 vs 推测）

**实证（反汇编坐实，可直接采信）**：
1. `MsgRetransmitUI.onCreate` 的全部 `Retr_*` Intent extra 键名及字段映射（§1.1 表）。
2. `k6/l6/s6/u6` 的签名、static 属性、参数寄存器、核心字段落点（§1.2）。
3. `qf4/o4.O8` 的 8 参数类型/寄存器/字段落点（§1.3）。
4. `g6/h6` 是「转发完成后的 msgId 上报」，非转发入口（§1.4）。
5. `qf4/o4` 无无参构造、需经 `mo2/w0.a()` 拿单例。

**推测（标 ⚠️，未追到消费点或未真机验证）**：
1. ✅ **`Select_Conv_User` 已从「强推测」升级为「实证」（2026-10-24 主会话补证）**：
   它就在 `onCreate` 自己的尾部，链路完整闭合 ——
   ```
   [1426] const-string 'Select_Conv_User'
   [1428] Intent->getStringExtra
   [1442] new-instance ArrayList
   [1447] iput-object v3, this.h:Ljava/util/List;    ; 目标列表装进 this.h
   [1449] ArrayList->add(v1)                          ; add(Select_Conv_User 的值)
   [1452] MsgRetransmitUI->l6()V                      ; scene 分发
   [1455] iget-object v1, this.h
   [1457] MsgRetransmitUI->z6(String, List)V           ; ★ 直接带目标列表发送
   ```
   而 `z6(String, List)`（`code_off=0x631160`）里 `[44] invoke-static Lhl0/r1;->b(String)Lhl0/q1;`
   → `[48] q1->e(String)` —— 这正是本项目已记录的发送锚点（`hl0/r1.b(talker)` → `q1.e()`）。
   ⇒ **`Select_Conv_User` 传一个 talker 就能跳过选择器、直达发送**，不是「仅初始筛选」。
   （`Select_Conv_Type` 只在 `e`(Retr_Msg_Type) 分支里作前置判断，见 `[939]`，语义未单独追。）
2. ⚠️ `Retr_Scene` 的取值 0/1 语义（0=相册 1=聊天）来自 `l6()` 的分支日志锚点，未 100% 锁定全部取值域。
3. ⚠️ `hl0/m` 各字段（a/b/c/d/e/f）的**精确**语义：`b`=源 msgId 有 `k6`/`O8` 的 `iput m.b = this.f`（=Retr_Msg_Id）坐实；`a/c/d/e/f` 的精确含义是**推测**（`a` 疑似新消息 id、`c/d/e` 疑似 fromUserName/uuid/附言，未逐一追）。
4. ⚠️ `qf4/o4.O8` 的 `q1.h=13` 分支是否「带转发标记」：`g6` 的存在说明该链会生成新 msgId，但「是否显示『转发自』标记」未真机验证。
5. ⚠️ 路径 A 中 `Retr_Msg_Id` 能否直接接受**外部构造的 msgId**（而非 DB 已有）：`l6()`→`storage/j9.u3(msgId)` 会查库，若查不到 `h9` 可能走不到转发分支——这条**必须真机验证**（见 §4）。

---

## 4. 真机验证方案（最小探针集）

> 设计原则：不碰 adb / 不装包 / 不上真机（本任务只**设计**命令，不执行）。探针用 Xposed 模块已有的广播入口（`op=forward` 系列）或 `am broadcast` 直接触发，每条给出「期望日志 = 通过」。

### 探针 1：确认 `MsgRetransmitUI` 能被 Intent 拉起（验证路径 A 可启动性）

```bash
am broadcast -a com.lx.wxgroup.PROBE --es op forward --es sub pick
# 期望：日志出现 "FF probe pick" + "picker opened (synthetic snapshot)"
# （仅验证菜单/选择器链路，不真正发）
```

更直接的静态面验证（改由模块加一条 sub 后）：
```
# 设计意图：模块新增 probe sub="nativeprobe"，组装 Retr_Msg_Id=某真实 msgId、
# Retr_Scene=1、Select_Conv_User=target，startActivity 到 MsgRetransmitUI
am broadcast -a com.lx.wxgroup.PROBE --es op forward --es sub nativeprobe --es talker <wxid>
# 期望日志（通过标准）：
#   1) 无 ClassNotFoundException / ActivityNotFoundException；
#   2) 微信切到转发确认页（人工观察）；
#   3) 日志 "FORWARD native anchor ok"（模块加埋点）
```

### 探针 2：验证 `qf4/o4.O8` 发图带转发标记（验证路径 B）

```bash
# 设计意图：模块新增 sub="nativeimg"，内部 mo2/w0.a() 拿 o4 单例，
# O8(ctx, talker=目标wxid, fileName=test.jpg, msgType=3,
#     localPath=/sdcard/某真实图片, remark="", fwdInfo=null, scan=null)
am broadcast -a com.lx.wxgroup.PROBE --es op forward --es sub nativeimg --es talker <wxid> --es val <本地图片绝对路径>
# 期望日志（通过标准）：
#   1) "FORWARD nativeimg sent=true"；
#   2) 对端会话收到图片，且**这条图片消息在发送者侧有『转发』语义**（若 fwdInfo.b 填了源 msgId 则显示转发标记）
```

### 探针 3：验证 `Retr_Msg_Id` 依赖 DB 的约束（判定路径 A 是否必须真实 msgId）

```bash
# 设计意图：模块 probe sub="nativefake"，用**不存在的** msgId（如 -2）组 Intent 启动
am broadcast -a com.lx.wxgroup.PROBE --es op forward --es sub nativefake --es talker <wxid>
# 期望日志（通过标准）：
#   - 若日志 "l2.e/processAppMessageTransfer error" 或 UI 停在原页 → 证明 Retr_Msg_Id 必须真实，
#     路径 A 的输入必须来自 G2（长按定位 msgId），不能凭空造；
#   - 若转发流程照常走 → 说明 msgId 只作上报，可接受外部构造（则 G1 闭环更简单）
```

**三条探针合并的最小首验顺序**：先跑探针 1（能拉起 = 入口有效）→ 探针 2（能发图 = O8 落点正确）→ 探针 3（定死路径 A 的 msgId 约束）。任一探针失败，回退结论并缩小 §3 的推测范围。

---

## 进度日志

- [DONE] 建文档骨架
- [DONE] onCreate 全量 Intent extra 键名清单（Retr_MsgTalker / Retr_Msg_Id / Retr_Msg_content 等 ~33 个键，字段映射坐实）
- [DONE] k6/l6/s6/u6 四方法完整功能（k6=图片/多图转发落地核心，l6=scene 分发，s6=AppMessage，u6=SNS）
- [DONE] qf4/o4.O8 八参数逐个语义映射（v5=talker、v8=本地路径→q1.r、v6=文件名、v10=转发标记 m）
- [DONE] g6/h6 采信结论复核（转发后 msgId 上报，非入口）
- [DONE] 可实现签名表（路径 A=Intent 启动 MsgRetransmitUI，路径 B=直接调 O8）
- [DONE] 边界声明（实证 vs ⚠️推测 五条）
- [DONE] 真机验证最小探针集（3 条 am broadcast 设计 + 通过标准）
- [DONE] 勘误 const/4 解码 bug：Retr_Msg_Type 默认值 3→-1，全表默认值补「实测/推断/特殊」来源标注
- [DONE] 采纳主会话补证：Select_Conv_User 升级为实证（onCreate[1426→1457]→z6→r1.b→q1.e），路径 A 无静态障碍
