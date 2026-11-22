# R73 图片转发「参考微X实现」路线裁决：复用微信原生转发 vs 自建 URI→路径解析

> lane：`r73-microx-image-route`｜**只读取证**：未改 `app/src/main` 任何源码、未跑 adb/装机、未跑我方转发、未跑 gradle、未 git commit。真机步骤只写进本文交主会话执行。
> 目标：判定微X/微信自带转发**图片**时如何拿到真实图片文件路径，裁决我方图片转发该走「复用微信原生转发（让微信解析 URI）」还是「自建 URI→路径解析」。
> 证据等级：【源码确定】= dex 反汇编/项目源码字面｜【真机确定】= 已有真机日志/SQL 实录｜【强推测】/【假设】= 未取证，必附装机验证方法｜【取证缺口】= 材料解不出或未实测。
> 反汇编地址为实跑 `_cache/_r55_disasm.py` / `_cache/_r73_faststr.py` 的 `code_off`，可复核。

## 目录

- [0 一句话裁决](#0-一句话裁决)
- [Q1 微信自带转发图片：真实路径怎么来](#q1-微信自带转发图片真实路径怎么来)
- [Q2 路径 A（Intent→MsgRetransmitUI）对图片是否可行](#q2-路径-aintentmsgretransmitui对图片是否可行)
- [Q3 微X图片转发走了哪条路 / 探针能力](#q3-微x图片转发走了哪条路--探针能力)
- [Q4 三条候选路线对比裁决](#q4-三条候选路线对比裁决)
- [唯一推荐 + 最小改动清单 + 主会话真机验收判据](#唯一推荐--最小改动清单--主会话真机验收判据)
- [DONE](#done)

## 进度

- [x] 建文件 + 目录
- [x] Q1 微信自带转发图片路径来源
- [x] Q2 路径 A 对图片可行性
- [x] Q3 微X图片转发路线 / 探针能力
- [x] Q4 三路线对比裁决
- [x] 唯一推荐 / 最小改动 / 验收判据 + [DONE]

---

## 0 一句话裁决

**推荐 B（自建 URI→路径解析，`_r73_image_path_forensics.md` 方案）：保留我方多目标转发、不打断选人器、路径规则已真机确定；不推荐把路径 A（复用微信 Intent 转发）作为主路线，因为它一次 Intent 只能指定一个目标、会打断我方选人器流程、且 `Retr_Msg_Id` 必须命中 DB 真实消息（未真机验）。** 两个补充：① 本次新发现**宿主 dex 里存在现成 URI→路径解析器 `com.tencent.mm.modelimage.b1.C4()/z3()` + `Z0()`（单例 `d3.O8()`）**——是路线 C 的锚点、也给 B 的桶规则做源头背书，但不改推荐；② 按用户一手观察纠正：**微X转发按钮正常操作不闪退**（仅"点输入法收起"触发），故"**真机只读观察微X图片转发**"可行且是"参考微X怎么实现"的**核心证据来源/首要取证手段**（Q3.4，需重写探针；结果坐实形态、不改"B 保留多目标"的根本裁决）。

---

## Q1 微信自带转发图片：真实路径怎么来

### Q1.1 结论先行

微信自带转发图片**不是**把我方快照里那个 `THUMBNAIL_DIRPATH://th_<md5>` URI 直接 `File()` 打开。真实路径由**宿主 `com.tencent.mm.modelimage` 的现成解析器**从 URI 计算出来。本次在宿主 dex 中**首次定位到该解析器的完整调用链**（【源码确定】，全部实跑反汇编）：

```
d3.O8()                          -> com.tencent.mm.modelimage.b1 单例        @classes7 code=0x2a8eec
  └─ b1.C4(String imgPath, boolean z) : String                              @classes7 code=0x2a563c
       ├─ 若 "THUMBNAIL://"        -> substring(12) -> m2(int) -> r0.g
       └─ 若 "THUMBNAIL_DIRPATH://" -> substring(20) -> 去 "th_" 前缀
            └─ b1.z3(md5, "th_", "", z) : String                            @classes7 code=0x2a6ae4
                 ├─ 若 "SERVERID://" -> Y2(long svrId) -> r0.e
                 ├─ 若 "THUMBNAIL_DIRPATH://" -> substring(23) -> prefix "th_" / "msgth_"
                 ├─ 若 "THUMBNAIL_APPMSG_DIR://" -> substring(29) -> 另一根
                 └─ g1.a(root, name, prefix, ..., isThumb) -> 拼出桶路径
```

**这个 `b1.C4()` 就是「微信内部把 `THUMBNAIL_DIRPATH://th_<md5>` 解析成 `image2/xx/yy/...` 真实路径」的解析器**，与自建方案的 md5→桶规则在语义上完全同构（【源码确定】dex 字面 + 【真机确定】桶规则 4 组样本，见 `_r73_image_path_forensics.md` §1.1）。

### Q1.2 直接证据（逐条）

| 锚点 | 类/方法 | code_off | 说明 |
|---|---|---|---|
| ① 单例入口 | `com.tencent.mm.modelimage.d3.O8():b1` | `0x2a8eec` | 懒加载 `new b1(y70.z.i)` 存入 `d3.d` 字段后返回，无参、static，易反射调用 |
| ② URI 主解析 | `com.tencent.mm.modelimage.b1.C4(String,Z):String` | `0x2a563c` | `startsWith("THUMBNAIL_DIRPATH://")`→`substring(20)`→去 `th_`→`z3(md5,"th_","",z)`；也处理 `THUMBNAIL://` |
| ③ 桶路径构造 | `com.tencent.mm.modelimage.b1.z3(String,String,String,Z):String` | `0x2a6ae4` | 处理 SERVERID / THUMBNAIL_DIRPATH / APPMSG_DIR 三种前缀，最终 `g1.a(...)` 拼路径 |
| ④ 上层封装 | `o63.m.b(h9):String`（日志 tag `MicroMsg.AiScanChatImageUtils`） | `0x7a76e0` | `b1.C4(h9.o0(), true)` + 拼 `"hd"` + `b1.Z0()`；证明宿主自己的业务也走 C4 这条解析链 |
| ⑤ 导出全路径 | `com.tencent.mm.modelimage.b1.Z0(String):String`（"exportToJpgAndGetFullPath"） | `0x2a5d98` | 经 `vfs.q7`/`vfs.v7.k` 拿真实文件路径，必要时导出 jpg |

### Q1.3 对必答题的直接回答

> 用户长按一张图点微信自带「转发」时，微信内部是把 `THUMBNAIL_DIRPATH://th_<md5>` 解析成 `image2/xx/yy/<md5>.jpg`，还是压根不走这个 URI？

【源码确定】**走这个 URI，并有现成解析器**：微信把消息对象 `h9` 的 `field_imgPath`（= `THUMBNAIL_DIRPATH://th_<md5>`，【真机确定】见 `_r73_image_path_forensics.md` §1.3）喂给 `b1.C4()`，`C4` 去前缀取 md5，`z3` 拼出 `image2/<md5[:2]>/<md5[2:4]>/` 桶内路径。这与自建方案的桶规则同源（自建方案是"手工重实现"了 `C4/z3` 的语义）。

> 关键：这个解析发生在哪个类/方法？宿主 dex 里有没有现成的 URI→路径解析器？

【源码确定】**有**：`com.tencent.mm.modelimage.b1.C4()`（`code=0x2a563c`）是主解析器，`z3()`（`0x2a6ae4`）是路径构造，`d3.O8()`（`0x2a8eec`）拿单例。可直接调用或 hook 的锚点已给出（Q1.2 表）。

> ⚠️ 关键限定（【取证缺口】）：本次只证明了「解析器存在且宿主业务（o63.m.b）在用」，**没有逐帧证明「微信自带『转发』按钮点击那一刻」一定经 `C4()`**——因为转发链最终发送走 `qf4/o4.O8` 或 `modelimage/j2`，它们吃的是"已经算好的 localPath"，而"算"这一步在 UI/DB 层。可能经 C4，也可能经 ImgInfo2 查询后再拼。要坐实须真机 hook `b1.C4()` 打印入参/返回（见 Q3.4 探针 D）。但无论走哪条，**都证明"真实路径可由 URI 解析得来"，这正是自建方案 B 的依据**。

---

[DONE-Q1]

## Q2 路径 A（Intent→MsgRetransmitUI）对图片是否可行

### Q2.1 结论

【源码确定 + 强推测】**技术上对单张图片可行，但对"我方多目标转发"有硬约束，不适合作为主路线。** 路径 A 能绕开我方"自建路径解析"——因为它是把 `Retr_Msg_Id` 交给微信，让微信自己去 DB 取 `h9`、自己（经 Q1 的 C4 或 ImgInfo2 链）解析图片真实路径并发图，我方**不需要也不提供 localPath**。但"一次 Intent = 一个目标 + 打断选人器"这两条，与我方"选人器一次选一批目标"的模型直接冲突。

### Q2.2 能否绕开自建路径解析：能

- 【源码确定】`MsgRetransmitUI.onCreate` 反序列化 `Retr_Msg_Id`(long,字段 f)、`Retr_Msg_Type`(字段 e)、`Retr_Scene`(字段 y) 等（`_r16` §1.1 全表）；`l6()`（code_off `0x62d768`）按 `Retr_Scene` 分发，`y==1`（聊天转发）→ 按 `Retr_Msg_Type` 分支 → 图片走 `A6(intent, path)`。
- 【源码确定】`A6(Intent,String)`（code_off `0x62c8f8`）从 Intent 的 `android.intent.extra.STREAM` 取图片，或走 `C6()`；`k6()`（code_off `0x62d368`）是图片/多图落地核心，它从 `this.f`(=源 msgId) 经 `hl0/m` 转发标记、设 `q1.h=19`(多图)/`11`，再 `q1.a()→l1.a()` 发送（`_r16` §1.2）。
- ⇒ 图片消息体由微信从 DB/STREAM 自己取，**我方无需 localPath**。这是路径 A 相对 B 的本质优势（不需自建解析）。

### Q2.3 多目标硬约束（决定性）

【源码确定】`onCreate` 里 `Select_Conv_User` 的处理已逐字节反汇编（`_r55_disasm.py` msgretransmit onCreate，偏移 `0x592–0x5b4`）：

```
0592: const-string v2, "Select_Conv_User"
0594: Intent->getStringExtra(v2)        // ★ getStringExtra —— 单个 String，不是 String[]
05a2: new-instance v3, java.util.ArrayList
05a7: iput-object v3, this.h : List
05a9: ArrayList->add(v1)                // ★ 只 add 一个目标
05ac: this.l6()
05af: iget-object v1, this.h
05b1: this.z6(custom_send_text, v1)     // 带"单元素列表"直接发
```

- **一次启动只能指定一个目标**：`Select_Conv_User` 是 `getStringExtra`（单 String），`ArrayList.add(v1)` 只加一个。给出 `Select_Conv_User` 时走 `z6(String,List)` 直达发送分支（避免选人器），但**只有一个元素**。
- 【源码确定】全宿主 dex 字符串池里 `Select_Conv*` 只有 `Select_Conv_User`/`Select_Conv_Type`/`Select_Conv_NextStep`/`Select_Conv_ui_title`，**没有 `Select_Conv_User_List` 之类多目标键**（实跑 `Dex.strings` 过滤）。`Retr_Msg_Id_List`（字段 j）是"多条源消息"列表，不是目标列表，语义不同。
- ⇒ **N 个目标 = 必须启动 N 次 `MsgRetransmitUI`**（或依赖 `startActivityForResult` 弹 `SelectConversationUI` 让用户手动多选，那样就回到了"打断我方选人器"）。

### Q2.4 会不会打断我方选人器流程 / 需要 RESULT

【源码确定 + 强推测】会打断/需显式处理：

- 【源码确定】不传 `Select_Conv_User` 时，`onCreate` 走 `startActivityForResult(intent→SelectConversationUI, reqCode)`（偏移 `0x581`）+ `overridePendingTransition`，随后靠 `onActivityResult` 回传目标列表——这是**系统自己的选人器流程**，与我方"转发（模块）"选人器是两个独立 UI，二者不能合并。
- 【源码确定】传 `Select_Conv_User` 时走 `z6()` 直达发送（无 RESULT），但只发一个目标。
- 【强推测】在微信进程内用 `startActivity` 起 `MsgRetransmitUI`，会压在我方选人器 Activity/Dialog 之上、抢占焦点；我方选人器选完一批目标后若再逐个起 Activity，UI 栈和返回语义都需重新设计（起一个→发一个→返回→再起下一个）。这是实现与验证成本的主要来源。装机验证方法：主会话按 Q2.5 探针实跑一次"选 2 个目标"观察 UI 栈表现。

### Q2.5 路径 A 的硬约束汇总 + 真机待验项

| 约束 | 等级 | 内容 |
|---|---|---|
| 一次 Intent 只 1 个目标 | 【源码确定】 | `Select_Conv_User=getStringExtra` + 单元素 `ArrayList`，多目标要 N 次启动 |
| 打断我方选人器 / 双选人器冲突 | 【源码确定(结构)+强推测(表现)】 | 系统 `SelectConversationUI` 与我方选人器是两套 UI |
| `Retr_Msg_Id` 必须命中 DB 真实消息 | 【源码确定出处 + 取证缺口(真机)】 | `l6()→storage/j9.u3(msgId)` 查 `h9`；查不到能否转发未真机验（`_r16` §4 探针 3）。我方快照 msgId 来自 `h9`（`_r73_image_path_forensics.md` §2.2），是 DB 真值，满足面较大 |
| 图片确由微信解析出清晰图 | 【强推测】 | 机制上成立，但"对端收到清晰图"未在我方路径 A 真机验过 |

> **Q2 裁决**：路径 A 能绕开自建路径解析（唯一优势），但**多目标/选人器/RESULT 三条硬约束**使其不适合作为我方多目标转发的主路线。可作为"单目标、已 DB 命中"场景的备选，但需先按下面探针真机验收 `Retr_Msg_Id` 查库约束与 UI 栈表现。
>
> **路径 A 最小真机探针（交主会话，本 lane 不碰设备）**：模块加一个 probe sub=`nativeimgA`，从长按图片快照拿 `snap.msgId`，组 `Retr_Msg_Id=snap.msgId, Retr_Msg_Type=3(图片)/110, Retr_Scene=1, Select_Conv_User=<单目标>` 启动 `MsgRetransmitUI`；判据：①对端收到**清晰图**（非 4KB 模糊图）⇒ 微信自解析成功；②单目标能发、2 目标能否连发两次；③UI 是否盖住/打断选人器。开 `logcat -s MicroMsg.LaunchMsgRetransmitUI` 看 `l6/j9.u3` 有无查库失败。

---

[DONE-Q2]

## Q3 微X图片转发走了哪条路 / 探针能力

> ⚠️ **两条前提纠正（team-lead 转达用户一手观察，优先于本文旧记载）**：
> 1. `MicroxFwdProbe.kt` **已不在工作区、也不在 git 历史**（R72 §7.5 一次性取证后用完即弃回退）。R72 的文本探针**无法直接复用**，真机观察微X图片**必须先重写一个只读取证探针**——但这是可执行工作，不是阻断项。
> 2. **微X转发按钮自身不会必然闪退**。R71 §6.1 / R72 §7.1 的 `CalledFromWrongThreadException` 是**特定操作触发**——R72 §7.1 原文「点输入法收起→微X自身闪退→微信冷启→探针内存变量清零」，即闪退由"点输入法收起"这个动作引发，不是转发按钮本身必崩。用户一手观察：正常操作不闪退。
>
> ⇒ **结论：真机只读观察微X图片转发是可行的、且是回答"优先参考微X怎么实现"最权威的手段**，应作为本次裁决的**核心证据来源**（Q3.4 给出可执行方案），而不是只靠静态推断。

### Q3.1 微X图片转发走了哪条路

【取证缺口（静态）+ 真机确定（文本）+ 待真机·核心证据（图片，可执行）】：

- 【取证缺口（静态）】微X业务 dex 静态解不出（`_r72_microx_forward_parse.md` §4：8 个密文容器 + `libwxcoreloader.so::CallMethod` + 字符串混淆），**无法从代码层直接知道微X图片转发走哪条路**。
- 【真机确定（仅文本）】R72 §7 只读探针实证：微X**文本**转发走原生 `hl0/q0.<init>` 链，`type=522`，`forwardMsgId=0`，线程 `wx_forward_send_thread_*`，对端无"转发"标记；未触发 `q1.f/q1.a|b/m.<init>` hook（入队方式与我方 G1 不同）。**图片从未在真机上观测过**（R72 §6.1 的 H3 hook 只记录了 type=522 文本样本）。
- 【待真机·核心证据来源】微X图片转发走 A/B/C 哪条路、**图片真实路径从哪来**，静态 dex 解不出 ⇒ 唯一权威答案是 Q3.4 的只读真机观察。观察前不预设结论；【强推测】仅作预期：微X大概率沿用文本链的自建线程 + 原生发图 NetScene（`modelimage/j2` / `hl0/q0` type=110）+ `forwardMsgId=0`，与文本同构。

### Q3.2 `MicroxFwdProbe.kt` 源码当前状态

【源码确定】**当前工作区不存在 `MicroxFwdProbe.kt`，且 git 全部历史也没有**（实跑 `find app -iname "*MicroxFwdProbe*"` 为空；`git log --all -- <该路径>` 无记录；`probe/` 目录只有 18 个其它探针）。R71 §7 / R72 §7.5 载明一次性取证后回退、用完即弃。⇒ 真机观察微X图片**不是"复用现成探针"，而是"按 R72 §6.1 清单重写一个只读探针"**（最小 hook 集见 Q3.4.1）。这是一次独立但**可执行**的取证工作，本 lane 只写方案、不碰设备/源码。

### Q3.3 type=110 是不是图片，依据是什么

【源码确定】**110 是图片 NetScene 类型，522 是文本 NetScene 类型**。本次从 `MsgRetransmitUI.g6`（code_off `0x62d018`）反汇编逐字节坐实（这是 R72 §6.1"522 文本/110 图片"说法的更硬出处）：

```
0006: const/16 v1, #522
0008: k1.getType() -> v0
000c: if-ne v1, v0  ...            // 522 分支：instance-of hl0/q0 -> q0.f(msgId) -> h6
0025: const/16 v1, #110
0027: k1.getType() -> v0
0029: if-ne v1, v0  ...            // 110 分支：instance-of modelimage/j2 -> j2.S().getMsgId() -> h6
```

- `g6` 在读"转发完成的新 msgId"时，对 `getType()==522` 走 `hl0/q0`（文本 NetScene，`_r71` §3 已钉死 q0.type=522），对 `getType()==110` 走 `com.tencent.mm.modelimage.j2`（图片 NetScene，本次实跑 `j2.S():h9` code_off `0x2ad8e4` 确认其携带消息对象）。
- ⇒ **type=110=图片、type=522=文本，【源码确定】**。注意 R72 §6.1 把 110 归到 `q0.<init>` 的 `getType()` 观察点上不够精确——真正的 110 是 `modelimage/j2` 而非 `hl0/q0`；`q0.getType()` 恒 522（文本）。⇒ **探针设计影响（见 Q3.4.1）：图片不能只 hook `q0`，必须同时 hook `modelimage/j2` 与 `qf4/o4.O8`。**

### Q3.4 首要取证手段：只读真机观察微X图片转发（可执行方案）

> 定位：这是回答"微X图片转发怎么实现"的**核心证据来源**，优先级高于静态推断。用户一手观察确认正常操作不闪退 ⇒ 方案可执行。全程只读（hook before/after 打印，不改 param、不替宿主发消息、不改微X、不跑我方转发）。交主会话持设备执行，本 lane 不碰设备。

#### Q3.4.1 需重写的只读探针最小 hook 集

参照 `_r72_microx_forward_parse.md` §6.1 的 6 点（H3 q0.ctor / H4 l1.a / H1 q1.f / H5a q1.a / H5 q1.b / H2 m.ctor），针对图片补 3 个关键锚点（均【源码确定】，code_off 实跑）：

| # | hook 点 | 签名/位置 | 观察目标（图片） |
|---|---|---|---|
| H3 | `hl0/q0.<init>(String,String,int,int,long)` | classes7 `0x711560`，`hookAllConstructors`+5 参过滤 | 读**第 5 参 forwardMsgId** 与 `getType()`；图片时读 type（注意 110 真正属于 j2，见 H7） |
| H4 | `hl0/l1.a()` | classes7 `0x710360` | 真入微信原生发送队列的**硬判据** |
| H1 | `hl0/q1.f(hl0.m)` | R36 §1.1 | 打印 `m.a~f` 六字段；`m.a`(forwardFromImgId)≠-1 ⇒ 微X有我方缺失的 CDN imgId 路径 |
| H5a / H5 | `hl0/q1.a()` / `q1.b()` | R36 §6 | 读 `q1.h` 子类型（4 文本/13 图片/19 多图）与入队路径 via=a\|b |
| H2（可选） | `hl0/m.<init>` | R36 §1.1 | 佐证 m 构造（可能零日志，不据此反推） |
| **H7（图片新增·关键）** | `com.tencent.mm.modelimage.j2` 全部构造 + `j2.S():h9` | classes7 `<init>` 起 `0x2ae6b4`；`S()` @`0x2ad8e4` | **图片 NetScene 构造/来源**；`j2.S()` 拿 h9 → 读 `field_imgPath`/type；Q3.3 证明 110 属于 j2 而非 q0，故图片必须 hook j2 |
| **H8（图片新增·关键）** | `qf4/o4.O8(Context,String,String,int,String,String,hl0/m,ScanCodeInfo)` | classes7 `0x845720` | **路线 B 判据**：微X自建路径后直接发图必调 O8；打印**第 5 参 localPath**（喂 `q1.r`，`_r16` §1.3）——看是否 `image2/...` 真实路径 |
| **H9（图片新增·关键）** | `com.tencent.mm.modelimage.b1.C4(String,Z):String` | classes7 `0x2a563c`（单例 `d3.O8` @`0x2a8eec`） | **URI→路径解析判据**：打印入参 URI + 返回值。C4 被调且返回 `image2/...` ⇒ 微X复用宿主解析器（路线 A/C）；零调用而图仍发出 ⇒ 走 ImgInfo2/STREAM 另一条 |

> 最小集 = H3/H4/H1/H5a/H5 + H7/H8/H9（H2 可选）。默认关，窗口内只点一次。日志格式沿用 R72 §6.1 的 `MFP xxx` 行（tag=MFP, op=mfwd）。

#### Q3.4.2 操作步骤（强调不碰输入法）

1. 按 Q3.4.1 hook 集**重写并安装**只读探针（tag=MFP, op=mfwd, default off）。
2. 广播 `am broadcast -a com.lx.wxgroup.PROBE --es op mfwd --es sub on` 开启；再发 `am broadcast -a com.lx.wxgroup.PROBE --es op mfwd`（无 sub）回显，确认 `enabled=true` 且 H3/H4/H7/H8/H9 均为 `OK`（R72 F2 硬前置，否则空日志无效）。
3. **长按要转发的那张图 → 点微X「转发」按钮 → 选目标**。⚠️ **全程不要点输入法收起**——这是 R71/R72 唯一记录的闪退触发动作；用户一手观察：正常操作不闪退。
4. 发送发生后**立刻 `sub=off`**；只看窗口内 MFP 日志（R72 F4 归因协议，窗口内不得触发其它消息发送，否则 q0/l1.a/O8 产生同形日志无法归因）。
5. 记录两类事实：窗口内 MFP 日志 + 对端收到的图（清晰 or 模糊，用户肉眼 + 长按「保存图片」比对字节数/分辨率）。

#### Q3.4.3 判据：微X图片走 A / B / C，是否拿到清晰图

| 观察组合 | 判定 | 含义 |
|---|---|---|
| `MsgRetransmitUI` 被 Intent 拉起（hook `onCreate`/`l6`/`A6`/`C6` 命中，见下方缺口）+ `b1.C4` 被调并返回 `image2/...`，且**无 O8 显式 localPath** | **路线 A**（微信自带转发、微信自解析） | 微X把消息交给微信自己解析路径；我方若照抄仍受"一次一目标"硬约束（Q2.3） |
| `qf4/o4.O8` 命中且第 5 参 localPath 为真实 `image2/...` 路径，无 MsgRetransmitUI 启动 | **路线 B**（自建路径后 O8 发图） | 微X自行解析路径再用原生发图接口 ⇒ **直接印证我方 B 是正确的"参考微X"实现** |
| `modelimage/j2` 构造 + `l1.a queued`，但 O8/C4 零调用 | **路线 C′（其它：自建 NetScene 直发）** | 与微X文本链同构（自建线程 + 原生 NetScene，不经 O8/C4），对应我方 G1 形态 |
| 任一组合下 `m.a`(forwardFromImgId)≠-1 | 附加发现 | 微X有我方缺失的 CDN imgId 路径，是"不重传/带标记"关键差异 |
| 对端图字节数/分辨率 ≈ 源大图（≫ `th_` 3~8KB） | 清晰图 | 微X挑对大图；≈4KB ⇒ 挑错到缩略图（`_r73_image_path_forensics.md` §4） |

> ⚠️ 判 A 的锚点缺口【取证缺口】：判"路线 A"需额外 hook `MsgRetransmitUI.onCreate` 读 Intent extras 有无 `Retr_Msg_Id`/`Select_Conv_User`——本 lane 未展开该 hook 的运行时探针实现，主会话若要严格判 A 需补这一锚点（静态签名见 `_r16` §1.1）。若只判 B/C′，用 H8/H9/H7 已足够。
>
> ⚠️ 若真机意外闪退（误触输入法）：微信冷启会清零探针内存变量 → 该样本无效，按 R72 §7.1 教训重来（这正是 F2 前置校验的必要性）。闪退前窗口内日志可作弱参考，不作硬判据。

---

[DONE-Q3]

## Q4 三条候选路线对比裁决

### Q4.1 四维对比表

| 维度 | A. 复用微信自带转发（Intent→MsgRetransmitUI） | B. 自建 URI→路径解析（`_r73_image_path_forensics.md`） | C. hook 宿主 URI→路径解析器（`b1.C4/z3`） |
|---|---|---|---|
| **图片能否真发清晰图** | 【强推测】能：微信自己从 DB/STREAM 取原图解析（Q1/Q2），但**我方未真机验过路径 A 发图**；若 `Retr_Msg_Id` 未命中 DB 则可能失败 | ✅【真机确定路径+强推测发送】能：桶规则 4 组真机样本实证，桶内按"含 md5、非 th_、字节最大"挑 `<md5>.jpg/.png` 大图（40~194KB）；清晰度须装机验收 | 【源码确定解析器】能：`b1.C4` 把 URI 解析成真实路径（Q1）；但 C4 具体返回大图还是 th_ 路径未逐个验（证据⑤ C4 返回值未打印），且同 B 仍需接我方发送链 |
| **是否保留多目标转发** | ❌【源码确定】**一次 Intent 只能 1 目标**（`Select_Conv_User=getStringExtra`+单元素 List），N 目标要 N 次启动；打断我方选人器 | ✅【源码确定】完全保留：在 `snapshotOf` 回填 localPath，下游 `runBatch` 原样多目标发送，零 UI 改动 | ✅【源码确定】同 B（只是替换 localPath 来源），保留多目标 |
| **宿主崩溃风险** | 中【强推测】：起宿主 Activity + `j9.u3` 查库 + 双选人器栈冲突；`Retr_Msg_Id` 喂错可能流程异常；G1 原生链已有"对端无标记"约束（R71） | 低【源码确定】：全程只读 IO（`listFiles`/`exists`）、`runCatching` 全包、不写宿主对象、不起 Activity | 中低【强推测】：反射调用宿主 `b1.C4/d3.O8`，签名/单例获取需真机验；hook 比反射风险高；C4 内部 `g1.a` 可能触发下载/IO 副作用未取证 |
| **实现与验证成本** | 高：新增 probe sub、组 Intent、`Retr_Msg_Id` 查库真机验、UI 栈重设计、2 目标连发验收 | 低（已成型）：`_r73_image_path_forensics.md` §0 已给完整最小改动（~20+46+40 行纯函数+宿主薄壳+单测），路线清晰 | 中高：需反射/hook 锚点真机验（单例 `d3.O8`、`C4` 重载、返回值形态），且要与 B 的"挑大图"逻辑取舍（C4 可能只给 th_ 路径） |

### Q4.2 裁决推理

1. **用户诉求优先级**：用户明确要"保留我方『转发（模块）』多目标转发能力 + 图片真发出去" + "**优先参考微X怎么实现的**"。
2. **"参考微X"的最权威答案 = Q3.4 真机观察（核心证据来源，优先于静态推断）**：用户一手观察纠正了"微X按钮必闪退"的旧前提（闪退仅由"点输入法收起"触发，正常操作不闪退）⇒ **真机只读观察微X图片转发是可执行的首要取证手段**，本文不预设结论，按 Q3.4.3 的 A/B/C 判据由主会话执行后回填。已知仅文本链（R72 §7：自建线程 + `q0` type=522 + `forwardMsgId=0` + 无标记），【强推测】图片与文本同构。**但裁决的鲁棒性不依赖观察结果**：若观察落 B（自建路径后 O8）⇒ **直接印证推荐 B**；若落 A（Intent 自解析）或 C′（自建 NetScene）⇒ 我方仍受"一次一目标"硬约束（Q2.3）与"对端无标记"host 约束（R71），**照抄微X仍不能作为我方多目标主路线**。⇒ 无论微X走哪条，推荐 B 不变；观察的价值在于坐实"参考微X"的具体形态与确认路径来源是否清晰。
3. **A 的致命约束**：多目标（源码确定一次一目标）+ 选人器冲突 + RESULT/UI 栈，与"保留多目标"直接冲突；且 `Retr_Msg_Id` 查库约束、路径 A 发清晰图**都未真机验**。即使 Q3.4 观察到微X走 A，也不改变 A 对我方的不可行性。按 team-lead 裁决规则应明确说明并转荐 B。
4. **C 的定位**：C 不是独立的转发路线，而是"B 的路径来源可以换成 hook 宿主解析器"。它比 B 更"原生"，但：① 返回值形态（大图 or th_）未验、② 反射/hook 锚点需真机验、③ `b1.C4/z3` 内部可能引入下载副作用（未取证）、④ 成本高于已成型的 B。**C 可作为 B 未来优化项，不作为本次推荐**；Q3.4 的 H9 探针正好可顺带验明 C4 返回值形态，为未来是否转 C 留数据。

### Q4.3 唯一推荐

**推荐 B（自建 URI→路径解析）；不把 A 作为主路线；C 记为后续可评估项。** 唯一需要写入决策依据的新事实是：**宿主确实有现成解析器（`b1.C4/d3.O8`），所以 B 的"手工重实现桶规则"并非凭空猜测，而是与微信原生解析语义一致**——这提升了 B 的正确性置信度，但不改变"B 保留多目标、A 不保留"的根本裁决。

---

[DONE-Q4]

## 唯一推荐 + 最小改动清单 + 主会话真机验收判据

### 唯一推荐路线（一句话）

**走 B：`snapshotOf` 的 IMAGE 分支新增 `resolveImagePath()`，用 `MediaGuardLogic.hexToken(uri)` 取 md5 → 在 `MicroMsg/*/image2/<md5[:2]>/<md5[2:4]>/` 单桶内按"含 md5、非 `th_`、图片扩展名、字节最大"挑真实大图回填 `localPath`+`fileExists=true`，再走我方现有发送链多目标发出；解析失败保留 URI 让 `planFor` 安全 SKIP、全程 `runCatching`。**（新证据：该桶规则与微信宿主 `b1.C4/z3` 原生解析语义一致，正确性有源头背书；A 因一次一目标+选人器冲突不作主路线，C 记为后续可评估项。）

### 最小改动清单（本 lane 未动一行源码；沿用 `_r73_image_path_forensics.md` §0）

| # | 文件 | 改动 | 行数级 |
|---|---|---|---|
| 1 | `feature/ForwardLogic.kt` | 新增纯函数 `imageBucket(md5)` / `pickImageFile(files, md5)`；不改 `Snapshot` 结构、`planFor`/`canResendFile` 不动 | ~20 |
| 2 | `feature/ForceForward.kt` | 新增 `resolveImagePath(imgPath)` 宿主薄壳（runCatching 全包、多 root 遍历、日志一行）；`snapshotOf` IMAGE 分支替换 `path` 取值（§5.1 C） | ~40 + 6 |
| 3 | `app/src/test/.../ImagePathLogicTest.kt`（新增或并入 ForwardLogicTest） | `hexToken`/`imageBucket` 四组真机样本/`pickImageFile` 五种边界/`planFor(IMAGE,fileExists)` 单测 | ~40 |
| 4 | 文档 | 本文件；不改 `docs/取证结论.md` 等既有结论 | 已落盘 |

⛔ 不改：`ForwardNativeLogic`/`FileSender`/`AntiRevoke`/任何 Key 默认值；不开 `chat.forward_native`；不做全树扫描默认化；不引下载；不改微X、不跑 adb/装机（真机步骤交主会话）。

### 主会话真机验收判据（对着用户可见事实）

0. **【核心证据·首要】微X图片转发只读观察**：按 Q3.4 重写探针（H3/H4/H1/H5a/H5+H7/H8/H9）→ 长按图→点微X转发→选目标（**不碰输入法**）→ 看窗口内 MFP 日志→立刻 off；按 Q3.4.3 判微X图片走 A/B/C′ 与是否清晰图。**这一步是"参考微X怎么实现"的最权威答案，先于/并行于 B 的落地执行**；结果只用于坐实形态，不改"推荐 B"结论（Q4.2 第 2 条）。
1. **主判据**：默认开关下长按一张**已点开过（大图已下载）**的图片 → 我方"转发（模块）"选**多个**目标 → 每个目标 `FORWARD resend-file kind=IMAGE ok=true` 且对端**收到清晰图片**（保存后字节数/分辨率 ≈ 源 `<md5>.jpg/.png`，明显大于 `th_<md5>` 的 3~8KB）。⇒ 同时验证"图片真发出"与"多目标保留"两条。
2. **日志侧**：`FORWARD resolveImage kind=IMAGE md5=<前10> bucket=<xx/yy> roots=n hit=<第几个> pick=<文件名> exists=true` 恰好一行/每图；无 `FORWARD native image stage=MATERIAL` / `FORWARD resend-file no local file` 失败行（`ForceForward.kt:1880,2027` 两处 MATERIAL 命门同时解开）。
3. **安全 SKIP**：长按一张未下载大图的图片（桶内仅 `th_`）→ 明确选择/警告；完全没有文件 → `planFor` SKIP + 一行 warn，**无空消息、无 FATAL EXCEPTION、无 ANR**。
4. **反向对照**：把 `ref.imgPath` 临时改回原 URI 复现同一张图 ⇒ 仍 `FORWARD plan ... SKIP ... kind=IMAGE`，证明 1 的 PASS 确由本改造带来。
5. **（可选）路线 A / C 的加验**：若日后要重评 A，按 Q2.5 探针验 `Retr_Msg_Id` 查库 + 2 目标连发 + UI 栈；若重评 C，用判据 0 的 H9（`b1.C4` hook）打印入参/返回值，确认 C4 返回的是大图还是 th_ 路径。

### 证据等级汇总

| 结论 | 等级 |
|---|---|
| 宿主存在 URI→路径解析器 `b1.C4/z3`、`d3.O8` 单例、`b1.Z0` 导出 | 【源码确定】（本次实跑反汇编 code_off） |
| `getType()=522`=文本 NetScene `hl0/q0`、`110`=图片 NetScene `modelimage/j2` | 【源码确定】（`MsgRetransmitUI.g6` 反汇编） |
| `Select_Conv_User` 单 String→单目标、无多目标 extra | 【源码确定】（`onCreate` 反汇编 + dex 字符串池过滤） |
| 桶规则 `md5[0:2]/[2:4]`、大图 `<md5>.jpg/.png`、thumb 模糊 | 【真机确定】（4 组样本，见 `_r73_image_path_forensics.md`） |
| 微X文本走 q0(type=522,forwardMsgId=0,自建线程)、对端无标记 | 【真机确定】（R72 §7） |
| 微X图片走哪条路 | 【待真机·核心证据】（正常操作不闪退 ⇒ Q3.4 只读观察可执行；静态仅【强推测】与文本同构）；探针需重写（工作区+git 历史均无） |
| 微X按钮闪退 | 【前提已纠正】：仅"点输入法收起"触发，非转发必然；用户一手观察正常操作不闪退 |
| 路径 A 图片能发清晰图 / `Retr_Msg_Id` 查库约束 / UI 栈表现 | 【强推测+取证缺口】（Q2.5 给真机探针） |
| 推荐 B 保留多目标、发清晰图、宿主零崩溃 | 【源码确定(结构)+强推测(图片端到端)】（验收判据 0~4 待主会话坐实，0 为微X观察核心证据） |

---

`[DONE]` R73 微X图片转发路线裁决：宿主确实有 `b1.C4/z3` URI→路径解析器（Q1 新发现，给 B 正确性背书），但路径 A 因"一次一目标+选人器冲突+Retr_Msg_Id 查库未验"有硬约束，不推荐作主路线；**唯一推荐 B（自建 URI→路径解析，保留我方多目标转发）**，C 记为后续可评估项。按用户一手观察纠正两条前提：微X转发按钮正常操作不闪退（仅"点输入法收起"触发），`MicroxFwdProbe.kt` 工作区+git 历史均无 ⇒ 需重写只读探针；Q3.4 把"微X图片转发真机只读观察"定为回答"参考微X怎么实现"的**核心证据来源/首要可执行手段**（最小 hook 集 H3/H4/H1/H5a/H5+H7/H8/H9 + 不碰输入法步骤 + A/B/C′ 判据）。最小改动 ~106 行、4 处；真机验收 0~4 条（0=微X观察）交主会话执行。全程只读取证，未碰设备/源码/gradle/git。

