# R73 · 微信 8.0.48「官方屏蔽转发入口」只读取证

> 子代理：r73-l1-dex（只读取证）　批次：R73
> 目标：查清微信 8.0.48（versionCode 2588, GP 版）"官方屏蔽转发入口"的机制，为「我方在那个场景补一个转发入口」提供可执行落点。
> 纪律：只读（不碰 adb/设备/gradle/源码/commit）。判据纪律：只认 dex 原文 + 源码行号 + 真机日志时间戳；「A 推 B」标 `[推断]`/`[需真机]`，不冒充 `[已证实]`；反汇编地址必须实跑脚本输出，不许手写猜。
> 工具：`python _cache/_r14_walk.py dis <dex> "<L类;>" <方法>` / `callers` / `fieldref` / `newinst`；`python _cache/_r55_disasm.py <L类;> <方法>`（跨全 dex）；`python _cache/_r55_arsc.py <资源id>`；`tools/_r28_meth.py` / `_r28_cls.py` / `_r28_strref.py` / `_r28fw_whoref.py`。
> 证据档：`[已证实]`=dex/资源/源码直接可见 ｜ `[推断]`=由已证实外推 ｜ `[需真机]`=有锚点无实测 ｜ `[取证缺口]`=材料解不出。

---

## 0 结论摘要

| # | 结论 | 等级 |
|---|------|------|
| 1 | 长按菜单是**自绘 PopupWindow + ListView(TextView 行)**（真机 `_lplog.txt`/`_r55menu.xml` 已证）；菜单项由 `va4/r3`（Menu 门面）承载，构建入口是各消息策略的 `g0(r3,View,ContextMenuInfo,h9)Z`（= `onCreateContextMenu`）与 component 的 `onCreateMMMenu(r3)`。`va4/r3.c(...)` 只做 `new va4/s3 → add 到 list`，**没有 setVisible 开关**——「可见」等价于「被 add」。 | `[已证实]` |
| 2 | 「转发」项 = **menu item id 111**，标题资源 `0x7f1040d3`=`转发`，图标 `0x7f0f0529`；由几十个 `viewitems/*.g0` 各自 add。代表：`b9.g0`@classes3 `0x55768c`、`fo.g0`@classes4 `0x5204c8`、`k.g0`@classes3 `0x55bd98`。点击处理器 `viewitems/*.e0(MenuItem,Lib4/c,h9)Z`（id 111/118）组 `Retr_*` Intent 拉 `MsgRetransmitUI`。 | `[已证实]` |
| 3 | **宿主对「转发项是否 add」是逐消息策略的条件分支，不存在全局 `removeItem(111)`**（全 dex 扫 `removeItem`，只命中 116=收藏等）。`ep.g0`（文本消息 `ChattingItemText`）顶部有 early-return 门（`t2()`/`p2()`/type），条件不满足则**整段菜单构建被跳过**（`return 1`）——这是最像「官方屏蔽」的 dex 层落点。 | `[已证实]`（门存在）/ `[推断]`（即用户所指场景） |
| 4 | 另有**服务器/实验开关** `RepairerConfig_GlobalNewForwardSwitch`，经 `ml1.d.b()`（本地 `dh4.e0.j` + 服务端 `ml1.d.h` 回退）读取；消费者是 `ChattingItemText.onContextItemSelected` 的 lambda（`ep$$a`/`wo$$a`），值 ==1 走「新转发」UI（`qf4.j.x8`）。它改的是**点击后流程**，不是菜单项可见性——但「新转发」与「转发项缺失」是否同一开关族 `[需真机]`。 | `[已证实]`（开关+消费者）/ `[需真机]`（与屏蔽场景的关系） |
| 5 | AFF 反垃圾协议 `ForwardMsgViewInfo` + `OpenForwardMsgViewSpamRequest/Response`（携带 msgId/msgSource/msgType/fromUserName 等）——结构上是「服务端决定能否开转发视图」的强候选，但**全 dex 无业务层调用者**（疑在 native/AFF），静态无法坐实。 | `[已证实]`（协议结构）/ `[取证缺口]`（业务判定） |
| 6 | 微X侧不只「改菜单结构」，其转发走**自建 NetScene**（`q0` type=522 / 微X线程 / 不触发 `q1.f`/`l1.a`，R72 §7）——即微X有**独立入口 + 独立发送链**，绕过宿主条件菜单项。我方发送链（G1/G2）已通，缺的正是「那个场景的入口」。 | `[推断]`（微X入口机制）/ `[已证实]`（微X发送链） |
| 7 | 推荐落点：**方案 A（放宽注入判据，用「聊天上下文+h9 存在」替代「菜单词 hits≥2」）为主 + 方案 B（hook `g0`/`onCreateMMMenu` 或长按 View 直拉选人器）兜底**；二者都要先真机判定「屏蔽时长按菜单还在不在（场景 a/b）」。方案 C（强制宿主显示转发项）风险最高，不推荐。 | 见 §5 |

**一句话**：dex 层已确认「转发项 = menu id 111、由各消息策略 `g0` 条件 add、文本消息 `ep.g0` 有 early-return 门、另有服务端新转发开关与 AFF 反垃圾协议」；但「用户所指场景究竟命中哪个门 / 屏蔽时长按菜单是否还在」必须真机判定——判据见 §6。

`[DONE] 0 结论摘要`

---

## 1 长按消息菜单「转发」项是怎么生成/显示的

### 1.0 菜单载体（真机 `[已证实]`）

- `_lplog.txt:22:13:36.162`：`ConvMenu cand: [...]`；`_lplog.txt:22:13:36.466`：`menu window not found (windows=1)`。
- `_r55menu.xml`：根 `FrameLayout → FrameLayout → android.widget.ListView → 多个 android.widget.TextView`（文案如「解散聊天分组」「标为未读」「删除该聊天」）。
- ⇒ 微信长按菜单是**自绘 PopupWindow + ListView**，行是 TextView。这正是我方 `ForceForward.findMenuBox` 假定的结构（`ForceForward.kt:330-`）。

### 1.1 菜单构建入口：`va4/r3` Menu 门面 + 策略回调

`va4/r3`（`_r29_g2_clsdump.py` dump，classes7 `class_defs#10581`）是实现了 `android.view.Menu` 的门面，内部维护 `List<MenuItem> d`。add 类方法：`add(IIII)`/`add(CharSequence)`/`b(III)`/`c(IIILjava/lang/CharSequence;I)`/`d(IICharSequence)`/`f(ICharSequence)` 等；删除：`removeItem(I)`/`removeGroup(I)`；可见组开关：`setGroupVisible(IZ)`。

`va4/r3.c(groupId,itemId,order,title,icon)` 反汇编（`classes7.dex code=0x8f9f2c`，`_r55_disasm` 实跑）：

```
0000: new-instance v4, Lva4/s3;
0002: iget-object v0, v1, va4.r3.f : Context
0004: invoke-direct {v4}, va4.s3-><init>(Context,int,int)   ; itemId=v3, order=v2
0007: iput-object v5, v4, va4.s3.i : CharSequence           ; 标题
0009: invoke-virtual {v4, v6}, va4.s3->setIcon(I)           ; 图标
000c: iget-object v2, v1, va4.r3.d : List
0010: invoke-virtual {v2, v4}, ArrayList->add(Object)Z      ; ★ 加入菜单列表
0013: return-object v4
```

⇒ **`c()`/`f()`/`d()` 只 `add`，返回的 `MenuItem` 上没有 `setVisible(false)`**；在此框架里「可见」=「被 add」，移除靠显式 `removeItem`。这是 §2/§3 判断的机制基础。

菜单项来源有两类回调：
- **消息策略**：`viewitems/s0`（`ChattingItem` 基类）子类的 `g0(Lva4/r3;Landroid/view/View;Landroid/view/ContextMenu$ContextMenuInfo;Lcom/tencent/mm/storage/h9;)Z` —— 对应 `onCreateContextMenu(Menu,View,info,MsgInfo)`。
- **component**：`ui/chatting/component/*` 与部分 `ui/chatting/*` 的 `onCreateMMMenu(Lva4/r3;)V`。

### 1.2 「转发」项 = item id 111，标题 0x7f1040d3

全 dex 扫 `onCreateMMMenu`/`g0` 中 `const/16 vX #111` + `const vX #2131771603`，命中几十个 `viewitems/*.g0`。代表证据（均实跑反汇编）：

- **`viewitems/b9.g0`**（classes3.dex `code=0x55768c`）：
  ```
  [ 10] if-eqz v10 -> +30 (abs 40)        ; v10=h9，为 null 则不加
  [ 17] const/16 v2, #111                  ; ★ item id 111
  [ 19] const/4 v3, #0
  [ 26] const v9, #2131771603              ; ★ 标题资源 = 转发
  [ 29] Resources->getString(I)
  [ 33] const v5, #2131690793              ; 图标 res/an/a90.svg
  [ 37] va4.r3->c(IIILjava/lang/CharSequence;I)Landroid/view/MenuItem;
  [ 40] const/4 v7, #1 ; [41] return v7
  ```
- **`viewitems/fo.g0`**（classes4.dex `code=0x5204c8`）：同样 `[29] const/16 v2 #111` + `[41] const v1 #2131771603` + `[53] va4.r3->c(...)`；随后 `[56] removeItem(116)`（删的是「收藏」，不是转发）。
- **`viewitems/ic.g0`**（classes7.dex `code=0x5cd9b8`）：`[126] const/16 v8 #111` + 同标题资源 + `va4.r3->c(...)`。
- 资源解析（`_r55_arsc.py` 实跑）：`0x7f1040d3 (2131771603) → '转发'`；`0x7f0f0529 (2131690793) → 'res/an/a90.svg'`。

⇒ **[已证实]** 微信原生「转发」菜单项 = `va4/r3.c(..., itemId=111, title=0x7f1040d3, icon=0x7f0f0529, ...)`，由消息策略各自定义；id 118 是另一组 viewitem（`ui/vi`，语音/视频号类）的转发项 id。

### 1.3 点击处理器：id 111/118 → MsgRetransmitUI

- `viewitems/ad.e0`（classes4.dex `code=0x51b8b4`，trace 类名 `ChattingItemAppMsgLocationShare$...From.onContextItemSelected`）：`[4] const/16 v0 #111` → `[7] if-eq v11,v0` → `[10] const-class MsgRetransmitUI` → 组 `Retr_Msg_content`/`Retr_Msg_Type`/`Retr_Msg_Id` Intent → `startActivity`。
- `viewitems/bd.e0`（`0x51cdd4`）同为 id 111；`viewitems/ui.e0`（`0x52cd40`）/`vi.e0`（`0x52df68`）为 id 118。
- `bi4/z.onMMMenuItemSelected`（classes3.dex `code=0x21f194`）内的 `MsgRetransmitUI` 引用（`@0x21fb3e`，R29 §1.2 已记）属**图片查询组合** `ImageQueryComposeOp.dealImageToRetransmit` 分支（`[1186] 'dealImageToRetransmit: '`），拉起时带 `Retr_Compress_Type/Retr_Msg_Type/Retr_Msg_Id/Retr_File_Name`（`[1221-1227]`），是**另一条**转发触发点，不是长按菜单的总分发。

⇒ **[已证实]** 长按菜单「转发」的标准链路：`g0 add id111` → 用户点 → `e0(MenuItem,Lib4/c,h9)` 判 id → 组 `Retr_*` Intent → `MsgRetransmitUI`。这与 R16/R29 的发送链取证一致。

`[DONE] 1 长按消息菜单「转发」项是怎么生成/显示的`

---

## 2 "官方屏蔽"的宿主判定是什么

> 结论先行：dex 层存在**三类**能阻止「转发」项出现的宿主机制；「用户所指场景命中哪一个」尚未真机坐实。逐条列分支与证据等级。

### 2.1 机制 A：`g0` 里的 early-return / 条件 add（逐消息策略，`[已证实]` 存在）

`ep.g0`（文本消息 `ChattingItemText`，classes7.dex `code=0x5c1c48`）顶部（`_r14_walk` 实跑）：

```
[  4] iget-object v2, v1, jj/m7.G : String
[  6] if-eqz v2 -> +8 (abs 14)
[  8] const-string v3, 'announcement@all'
[ 10] String->contains(v3)                ; G 字段含 "announcement@all"？
[ 14] h9->t2()Z                            ; ★ 门 1
[ 18] const/4 v3, #1
[ 19] if-nez v2 -> +18 (abs 37)            ; t2() 真 → 跳到 add 流程
[ 21] h9->p2()Z                            ; ★ 门 2
[ 25] if-nez v2 -> +12 (abs 37)            ; p2() 真 → 跳到 add 流程
[ 27] h9->getType()I
[ 31] const v4, #1107296305 (0x42000031)
[ 34] if-eq v2, v4 -> +3 (abs 37)          ; type==0x42000031 → 跳到 add 流程
[ 36] return v3                            ; ★ 三条件都不满足 → 直接 return 1，整段菜单不构建
[ 37] ... (正常 add 流程，含 [57] const v14 #2131771603=转发)
```

门方法反汇编（classes4.dex，`_r55_disasm` 实跑）：
- `h9.t2()`（`0x493754`）：`getType() ∈ {1, 11, 21, 31, 36, 0x42000031}` → true，否则 false。
- `h9.p2()`（`0x4936ac`）：`getType()==0x12000031`(301989937) → true。

⇒ **[已证实]** 文本消息的 `g0` 有 early-return：类型不属于上述集合时**整段菜单项都不 add**（含转发）。`[推断]` 这更像「某些消息类型不可长按菜单」，与用户说的「与消息类型无关」**不完全吻合**，故不一定是用户所指场景——`[需真机]` 用探针确认屏蔽场景里 `g0` 是否走了 `return 1`。

同类门在别的策略里以 `if-nez/if-eqz` 直接夹住 add，代表：
- `viewitems/k.g0`（`0x55bd98`）：`[30] viewitems/y.a.b(h9)Z` → `[36] if-eqz -> 跳过 id111 的 add`；能 add 时 `[32] removeItem(116)`（删收藏）。
- `viewitems/b9.g0`（`0x55768c`）：`[10] if-eqz v10(h9)` → 跳过 add。
- `viewitems/fo.g0`（`0x5204c8`）：`[61] if-eqz v0` → 后续分支；`[68] ib4.c.x()Z` 条件。

### 2.2 机制 B：服务端/实验开关「新转发」（`[已证实]` 开关与消费者）

- 开关类 `RepairerConfigGlobalNewForwardSceneChattingTypeTextSwitch`（classes2.dex）：
  - `a()`（key）`0x43e440` → `"RepairerConfig_GlobalNewForwardSwitch"`；
  - `b()`（说明）`0x43e458` → `"新转发开关(场景：聊天，类型：文本)"`；
  - 同族还有 `...SnsTypeImgSwitch` / `...SnsTypeVideoSwitch`（朋友圈场景）。
- 读取链 `ml1.d.b(Lh64/c;)I`（classes4.dex `0x6dd110`）：
  ```
  0004: dh4.e0->j(config)I           ; 本地配置值
  0008: if-nez v1 -> 0019            ; 非0 用本地
  000a: config->i()String            ; key
  000e: dh4.e0->f(config)I           ; 默认值
  0014: ml1.d->h(key,默认,1,0)I       ; ★ 服务端/实验配置回退
  ```
- 消费者 `ep$$a.run()`（classes7.dex `0x5c12d4`，trace `ChattingItemText$ChattingItemTextTo.lambda$onContextItemSelected$1`）与 `wo$$a.run()`（`0x604850`，`...TextFrom.lambda$onContextItemSelected$0`）：
  ```
  0019: new RepairerConfigGlobalNewForwardSceneChattingTypeTextSwitch
  001e: ml1.d->b(config)I  -> v3
  0024: const/4 v10,#0 ; const/4 v4,#1
  0027: (v3==1) ? ...                  ; ★ 开关值==1 决定走新转发
  003c: wg->Q0(h9,Z)String             ; 取正文
  004e: qf4.j->x8(Activity,String)Intent
  006a: 'startActivity'
  ```
- `n33/a.c()Ljava/util/Set;`（classes2）以 `const-class` 把这些开关类注册进配置集合。

⇒ **[已证实]** 开关存在且被 `ChattingItemText` 的转发点击 lambda 消费，值 ==1 时用 `qf4.j.x8` 新转发 UI。`[推断]` 新转发 UI 的会话选择面板可能就是「官方改版入口」。但该开关控制的是**点击后流程**（lambda 在 `onContextItemSelected` 里），不是菜单项是否 add——它是否同时导致「转发项被换成/隐藏」`[需真机]`。

### 2.3 机制 C：AFF 反垃圾协议（服务端决定能否开转发视图，`[已证实]` 结构 / `[取证缺口]` 判定）

- `com.tencent.wechat.aff.proto.ForwardMsgViewInfo`（classes14.dex）：字段 `msgId/msgSource/msgType/newMsgId/msgSeq/createTime/fromUserName/toUserName/appMsgInnerType`（builder 的 `getXxx/clearXxx` 全在 `_r14_walk newinst` 输出里，`_r55_disasm` 复核 `getMsgSource()`@`0x18db48` = `iget-object msgSource_`）。
- `OpenForwardMsgViewSpamRequest/Response`（classes12/13.dex）：Request 含 `baseRequest` + `msgInfo(ForwardMsgViewInfo)`；Response 含 `baseResponse`。名字直译「开启-转发消息视图-反垃圾」。
- 全 dex 反查（`_r28fw_whoref` / `_r14_walk newinst`）：**只有 protobuf 自身 builder 引用**，无任何业务类 `new-instance` 或调用点；`_r27_callers` 对 `getMsgSource` 的命中全是 native stub 噪声。

⇒ **[已证实]** 协议结构完整、字段是「一条待转发消息的画像」；`[取证缺口]` 真正的判定在 native（AFF / `libwxcoreloader.so`，R72 §4.3 已定案 .so 符号剥除）或以反射/动态方式调用，静态材料看不到「服务端返回『禁止转发』→ 隐藏项」的那一步。`[推断]` 这是「与消息类型无关的官方主动屏蔽」最贴合的候选（服务端按反垃圾策略决定），但**静态不能坐实**。

### 2.4 归纳：「能走到不显示转发项」的分支清单

| 机制 | 触发条件 | 作用点 | 等级 | 与「用户场景」吻合度 |
|---|---|---|---|---|
| A1 | `h9.t2()/p2()/type` 不满足 → `ep.g0` `return 1` | 文本消息整段菜单不构建 | `[已证实]` | 中（按类型，非「与类型无关」） |
| A2 | `if-eqz(h9)` / `y.a.b(h9)` 等条件不满足 | 跳过 id111 的 `va4.r3.c()` add | `[已证实]` | 中 |
| B | `RepairerConfig_GlobalNewForwardSwitch` 值 | 点击后走新/旧转发 UI | `[已证实]` 值读取；`[需真机]` 是否影响可见性 | 待定 |
| C | AFF `OpenForwardMsgViewSpam` 服务端返回 | 决定能否开「转发视图」 | `[取证缺口]` | 高（但不可静态证） |
| — | 全局 `removeItem(111)` / `MenuItem.setVisible(false)` | — | **`[已证实]` 不存在**（全 dex 扫 `removeItem` 只命中 116 等；聊天菜单无 `setVisible`） | — |

`[DONE] 2 "官方屏蔽"的宿主判定是什么`

---

## 3 屏蔽时长按菜单本身还在不在

> 分两种场景，直接决定我方 `ForceForward` 注入点还能不能用。

### 3.1 dex 侧能确定的

- 菜单**显示**（`PopupWindow.showAsDropDown/showAtLocation`）与菜单**项构建**（`g0`/`onCreateMMMenu` 调 `va4/r3.c()`）是**两条独立流程**：
  - 显示由 `View.performLongClick` → 宿主 PopupWindow 逻辑触发（`ForceForward.kt:178-207` 已 hook 这两方法）；
  - 项构建由各策略 `g0` 决定，机制 A1 的 `ep.g0 return 1` 只是**不 add 项**，代码里**没有任何 `dismiss()`/不 show 的联动**。
- ⇒ `[推断]` 就算 `g0` 走了 early-return（转发项不 add），**长按仍会弹 PopupWindow**（可能只有「复制/全选」等通用项，或空列表）——这更接近场景 (a)：**弹菜单但无转发项**。但也可能宿主对「零项菜单」直接不 show，这属 `[需真机]`。

### 3.2 两种场景对我方注入点的影响

| 场景 | dex 依据 | 我方 `ForceForward` 现状 | 判定 |
|---|---|---|---|
| (a) 弹菜单但无转发项 | `g0 return 1` 只影响 add，不影响 show `[推断]` | `tryInject`（`ForceForward.kt:213`）仍会触发；但 `classifyMenu` 要 hits≥2、`findMenuBox` 要同容器 hits≥2（`:330-`）——若剩余项不足 2，会 `MISS`/`no box found`（R71 §6.2 已观测微X共存触发过） | **注入点仍在，判据太严** |
| (b) 完全不弹菜单 / 结构大变 | `[需真机]`（dex 无 dismiss 联动，无法排除） | `showAsDropDown/showAtLocation` after 不触发 → `tryInject` 根本不进 → 现成注入点失效 | **需另找触发路径** |

⇒ 关键分水岭：**屏蔽时长按是否还有 PopupWindow show**。真机只要抓 `ForceForward` 的 `FF popupsSeen`/`FF no chat menu` 日志与 UI dump 即可区分（见 §6）。

### 3.3 从 dex 看「构建与添加是否同一流程」

是同一流程：`g0`/`onCreateMMMenu` 内部既做条件判断又直接 `va4/r3.c(...)` add（§1.2/§2.1 证据），**没有独立的「先建空菜单再过滤」阶段**。因此「转发项缺失」一定发生在 `g0` 执行期（add 被跳过或 early-return），不会在显示后再被移除（无 `setVisible`/`removeItem(111)`）。这支持方案 C「hook `g0` 判定」在理论上有落点，但风险见 §5。

`[DONE] 3 屏蔽时长按菜单本身还在不在`

---

## 4 微X在那场景如何提供入口（侧证）

> 纪律：微X业务 dex 加密解不出（R72 §4 定案），**不静态挖微X**；只从宿主侧 + R71/R72 真机结论推断。

### 4.1 已知事实（`[已证实]`，来自 R71/R72 文档）

1. R71 §6.2：微X共存时我方 `FF classified CHAT_MSG but no box found`——微X在消息长按菜单**注入了「微X模块」项**，改变了菜单结构 ⇒ 微X确实 hook 了长按菜单。
2. R72 §7 真机：点微X转发 → `MFP q0 ctor ... type=522, forwardMsgId=0`，线程 `wx_forward_send_thread_*`（微X自建线程）；**`q1.f`/`q1.a|b`/`m.<init>` 零日志** ⇒ 微X走的是与 G1 builder 不同的入队路径（自建 NetScene/封装）。
3. R71 §6.1：微X转发按钮自身会闪退（`CalledFromWrongThreadException`，`wx_features_base_thread`），与我方无关。

### 4.2 宿主侧推断（`[推断]`）

- 若官方屏蔽的是「**菜单里的转发项**」（机制 A/B/C），那么微X能提供的入口极可能是**自绘菜单项直连选人/发送**，绕过宿主 `g0` 的条件 add——证据：事实 1（微X注入菜单项）+ 事实 2（微X走自建发送链，不依赖宿主 `MsgRetransmitUI` 的 builder）。这与 R16 §1 已证的「原生转发 Activity 入口 = `MsgRetransmitUI` + `Select_Conv_User`/`Retr_*`」并不冲突：微X**可以不走**那个 Activity，直接组 q0/NetScene。
- 壳资源里有 AndroidX `ShareCompat` 文案（`分享对象`/`与%s分享`，R72 §3.1）——`[推断]` 微X选人面板可能复用系统分享组件风格，但 R72 已定案这是**壳级线索，不能证明业务实现**。

### 4.3 边界（明确不可越）

- `[取证缺口]` 微X到底 hook 哪个宿主点来加那个入口、是否直接拉 `MsgRetransmitUI`、是否在屏蔽场景专门加项——全部在加密 dex/native 里，静态解不出。
- `[需真机]` 用户所说「那个场景微X有转发入口」本身需在真机复现并确认（本次只有 R71/R72 在普通场景的证据）。
- **不要把「微X有入口」等同于「微X能带转发标记」**：R72 §7.4 已证微X对端同样无标记。

`[DONE] 4 微X在那场景如何提供入口（侧证）`

---

## 5 我方补入口的可行落点（候选方案）

> 前提（§3）：方案 A 适配场景 (a)，方案 B 覆盖 (a)+(b)。发送链不动（G1/G2 已通）。

### 方案 A：放宽 `ForceForward` 的菜单归类/容器判据（`[推荐·主案]`）

- **改哪个文件**：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt`（`injectWhenReady` `:234-300`、`findMenuBox` `:330-`）+ `ForwardLogic.kt`（`classifyMenu` `:140-146`、`CHAT_MENU_WORDS` `:116-120`）。
- **hook 点**：不变（`PopupWindow.showAsDropDown/showAtLocation` after，`:200-207`）——只在**场景 (a)** 有效。
- **判定条件**：把「命中 ≥2 个 `CHAT_MENU_WORDS`」放宽为「处于聊天消息上下文 + 能定位 h9/adapter + 菜单含任意 1 个聊天词（复制/删除/多选/引用…）」即注入；`findMenuBox` hits 阈值从 2 降到 1，或退化为「取 PopupWindow 内容视图里第一个 ListView 容器 + 任一行 TextView 做样式样本」。
- **风险**：误注入——会话列表菜单（CONV_LIST）、图片浏览菜单、输入栏「+」面板等也可能被当成聊天菜单。**必须**用「长按窗口 `lastPressAt`（`:217`）+ 当前 Activity 是聊天页 + view 链能 `adapter.getItem` 拿到 h9」三重上下文收紧，否则会把「转发到多个」挂到会话列表（R29 明确「最贵误判」）。
- **是否需真机**：是——先判场景 (a/b)（§6-1），再调阈值看 `FF injected` 是否出现且无误注入。

### 方案 B：另找触发路径，直连选人器 / `MsgRetransmitUI`（`[推荐·兜底，覆盖场景 (b)]`）

- **改哪个文件**：`ForceForward.kt` 新增一个 hook（或新 `probe`/`feature` 文件）；复用 `ForwardPicker` + 已有发送链（G1/G2）。
- **hook 点候选**（dex 已定位，`[已证实]` 存在，`[需真机]` 验证哪个在被屏蔽场景仍触发）：
  1. `viewitems/*.g0`（`onCreateContextMenu`）——hook after，读 `h9`（第 4 参），当宿主没 add id111 时我方主动加项/弹入口；对文本消息锚 `ep.g0`@classes7 `0x5c1c48`。
  2. `va4/r3.c(IIILjava/lang/CharSequence;I)`（`0x8f9f2c`）after——观察是否有 id111 被 add；这是最贴近「转发项是否出现」的探针点。
  3. 长按 View（`View.performLongClick`，已 hook）直接取 h9（`adapter.getItem`，G2 路径 `docs/_r29_g2_msg_object.md` §3）→ 走我方 `ForwardPicker` 选人 + G1 发送，完全不依赖宿主菜单。
- **判定条件**：拿到 h9（msgId/msgType/talker）即可弹我方选人器；发送用已通的 `hl0/r1→q1→q0` 链。
- **风险**：hook `g0` 属宿主菜单内部，多消息类型各一套、签名一致但类多；`va4/r3.c` 全局 add 频繁、需按 itemId 过滤避免刷屏。方案 B-3（长按 View 直取 h9）最稳但要处理「图片卡片自定义手势不触发 performLongClick」（R71 §5 已踩坑）。
- **是否需真机**：是——尤其验证被屏蔽场景里哪个 hook 点仍触发。

### 方案 C：hook 宿主「屏蔽判定」强制显示转发项（`[不推荐]`）

- **hook 点**：`ep.g0` early-return（`:19/:25/:34`）、`ml1.d.b`/`dh4.e0` 新转发开关、或 AFF `OpenForwardMsgViewSpam` 响应（native，难）。
- **判定条件**：强制让 `g0` 走到 add id111 / 让开关 ==1。
- **风险**：改宿主行为——消息类型本不支持转发时强开会转发失败/崩溃；服务端反垃圾策略被绕可能违规；AFF 判定在 native 无法 hook。**边际收益低、风险高**，仅作对照实验，不作为产品方案。

### 推荐与理由

**A 为主、B 为兜底**：A 改动最小、复用已通链路，前提是场景 (a)；B（尤其 B-3）不依赖宿主菜单结构，是场景 (b) 的唯一可行路径，且与微X「绕过菜单项自建入口」的策略同构（§4）。C 不推荐。**A/B 都要先真机判定场景 a/b（§6），并确认拿得到 h9（G2 已提供路径）。**

`[DONE] 5 我方补入口的可行落点（候选方案）`

---

## 6 真机判据清单（交主会话持设备执行）

> 目标：用最小只读观察，判定 (1) 屏蔽场景命中哪个机制、(2) 屏蔽时长按菜单是否还在、(3) 哪个 hook 点仍触发。不改宿主发送行为。

### 6.1 现有可复用探针（`wxgroup/app/src/main/java/com/lx/wxgroup/probe/`）

| 探针 | tag / op | 用途 |
|---|---|---|
| `MenuProbe.kt` | 菜单 | dump 当前 PopupWindow 内容视图的 TextView 文案/容器结构 |
| `MenuHuntProbe.kt` | 菜单 |  hunt 长按菜单窗口、列候选容器 |
| `MicroxFwdProbe.kt` | `MFP` / `mfwd` | 侧观察微X转发（`q0 ctor`/`l1.a`/`q1.f`，R72 §6） |
| `ForceForward`（自身日志） | `FF` | `popupsSeen`/`lastKind`/`classified ... no box found`/`injected`（`ForceForward.kt:222/241/264/291`） |
| `DexProbe.kt` / `HookListProbe.kt` | — | 辅助确认类/方法可 find、hook 是否挂上 |

（R71 的 `ForwardNetSceneProbe`/FNP 与 R72 的 MFP 部分代码曾被回退；需按 R72 §6.3 的「前置校验」重新确认 enabled/hook OK 再解读空日志。）

### 6.2 最小观察方案（按顺序）

1. **复现屏蔽场景**：由用户指出「官方屏蔽」的具体会话/消息（用户称与消息类型无关，需确定是不是特定公众号/客服/视频号会话）。长按该消息，`uiautomator dump` 存 UI XML。
2. **判场景 a/b**（§3 分水岭）：
   - dump 里**有**菜单 ListView 且无「转发」行 → 场景 (a)：方案 A 可用。
   - dump 里**完全没有**菜单窗口/浮层 → 场景 (b)：必须方案 B。
   - 交叉验证 `FF popupsSeen` 是否 +1、`FF no chat menu after N tries` 是否出现。
3. **判机制 A/B/C**（§2）：开只读 hook（新增最小探针）打印被屏蔽那次长按的：
   - `h9.getType()`、`h9.t2()`、`h9.p2()`、`jj/m7.G`（是否含 `announcement@all`）——命中 A1/A2 则 `g0` 走 early-return；
   - `ml1.d.b(RepairerConfigGlobalNewForwardSceneChattingTypeTextSwitch)` 值——==1 则命中 B（新转发）；
   - 是否出现 AFF 相关 CGI（抓 `OpenForwardMsgViewSpam` 请求日志）——有则命中 C（服务端）。
4. **判 hook 点可达性**（方案 B）：确认被屏蔽场景里 `viewitems/ep.g0`/`va4/r3.c` 是否仍被调用（各加一行 before 日志）；`View.performLongClick` 是否仍触发（图片卡片可能不触发，R71 §5）。
5. **微X侧证**（可选）：开 MFP，前置校验 enabled=true+H3/H4 OK（`op=mfwd` 无 sub 回显），只点一次微X入口后立刻 off，看时间窗内是否 `q0 ctor type=522`/线程 `wx_forward_send_thread_*`（R72 §6.3/§7 归因协议）。

### 6.3 什么结果证明哪个方案

| 观察结果 | 结论 | 采用 |
|---|---|---|
| 有菜单、无转发行；`FF` 触发但 `MISS`/`no box found` | 场景 (a)，注入点在、判据太严 | **方案 A**（放宽 `classifyMenu`/`findMenuBox`） |
| 有菜单、无转发行；`ep.g0` 走 `return 1`（t2/p2/type 不满足） | 机制 A1，确认官方按类型屏蔽 | A + 校验收紧避免误注入 |
| 无菜单窗口；`FF popupsSeen` 不增 | 场景 (b) | **方案 B**（hook `g0`/`r3.c` 或长按 View 直取 h9） |
| `ml1.d.b(新转发开关)==1` 且点转发走 `qf4.j.x8` | 机制 B，官方改版入口 | B 为主，A 仅作补充 |
| 抓到 `OpenForwardMsgViewSpam` 且服务端拒绝 | 机制 C，服务端主动屏蔽 | B（绕菜单自建入口），不动机制 C |
| `performLongClick` 不触发（图片类） | B-3 在该消息类型失效 | 优先 B-1/B-2（hook `g0`/`r3.c`） |
| 微X入口 MFP 命中 `q0 ctor type=522`+微X线程 | 微X自建入口+自建链，印证 §4 | 参照其「绕菜单」策略走 B |

### 6.4 纪律

- 全程只读：探针仅 before/after 打印，不改 `param`、不主动替宿主调 `q1.a()/q1.b()/l1.a()` 发消息（唯一允许的 getter 是 `getType()`，R71 已用无副作用）。
- 空日志不得直接解读为「没走转发链」——先做 R72 §6.3 的前置校验（探针 on + hook OK）。
- 归因靠时间窗 + 线程名（微X `wx_*` 线程）；窗口内不得触发其它消息发送，避免同形日志污染。

`[DONE] 6 真机判据清单`

---

## 附：本轮实跑反汇编/资源地址索引（可复核）

| 目标 | 位置 | 命令 |
|---|---|---|
| `va4/r3.c` add 语义 | classes7 `code=0x8f9f2c` | `_r55_disasm Lva4/r3; c` |
| `b9.g0` add id111/转发 | classes3 `code=0x55768c` | `_r14_walk dis classes3.dex L...b9; g0` |
| `fo.g0` add id111 + removeItem(116) | classes4 `code=0x5204c8` | 同上 |
| `ic.g0` add id111 | classes7 `code=0x5cd9b8` | 同上 |
| `ep.g0` early-return 门 | classes7 `code=0x5c1c48` | `_r14_walk dis classes7.dex L...ep; g0` |
| `h9.t2` / `h9.p2` | classes4 `0x493754` / `0x4936ac` | `_r55_disasm L...h9; t2/p2` |
| `ad.e0` 转发点击 id111 | classes4 `code=0x51b8b4` | `_r55_disasm L...ad; e0` |
| `bi4/z.onMMMenuItemSelected` 图片转发 | classes3 `code=0x21f194`（转发 @0x21fb3e） | `_r14_walk dis classes3.dex Lbi4/z; onMMMenuItemSelected` |
| `NewForward...TextSwitch.a/b` | classes2 `0x43e440`/`0x43e458` | `_r55_disasm` |
| `ml1.d.b` 配置读取 | classes4 `code=0x6dd110` | `_r55_disasm Lml1/d; b` |
| `ep$$a.run`/`wo$$a.run` 开关消费者 | classes7 `0x5c12d4`/`0x604850` | `_r55_disasm` |
| `ForwardMsgViewInfo.getMsgSource` | classes14 `code=0x18db48` | `_r55_disasm` |
| 资源 `0x7f1040d3`=转发 / `0x7f0f0529`=a90.svg | — | `_r55_arsc.py` |

`[DONE]`

[DONE]
