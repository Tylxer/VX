# R16 G2：消息种类识别链（msgType 锚点）离线取证

> 缺口 G2：`ForceForward.snapshotNow()` 恒 `kind=UNKNOWN` ⇒ `planFor()` 恒 SKIP ⇒ 强制转发一条发不出。
> 本文件只做**离线静态取证**（不碰 adb / 不跑 gradle），结论分「实证 / 推测」两级。

## 目标

用户在聊天页**长按一条消息**时，从 hook 上下文里拿到：

- **msgId**（long）
- **msgType**（int，`message.type` 或消息对象等价字段）
- 若有媒体：**本地文件路径**
- **localFlag / isSend**（区分自己发的 / 别人发的）

---

## 0. 一句话结论（先读这段）

**最优路线（A，推荐）**：不碰「msgId → DAO 反查」，直接从**长按最深 View → 列表 adapter** 拿消息对象。

```
View.performLongClick 的最深 View（ForceForward 已记在 burstDeepest）
  → 向上找聊天列表（MMChattingListView / ChattingListView / ChattingRecyclerView）
  → getPositionForView(v) 拿 position
  → adapter.getItem(position) 拿 com.tencent.mm.storage.h9（消息对象）
  → h9.getMsgId() / h9.getType() / 沿类链反射 field_isSend / field_imgPath
```

**备选路线（B，兜底）**：`AntiRevoke.wxDb()` 直接 `rawQuery("select * from message where msgId=?")`。
**但 B 必须先有 msgId**，而 msgId 只能来自第 1 节（长按上下文）⇒ **B 完全依赖第 1 节**。见 §4。

---

## 1. 长按回调链：长按消息 item → 弹出消息菜单 → 拿 msgId

### 1.1 结论（实证 + 推测分级）

**不靠「菜单构造点」也能拿到 msgId —— 走 adapter，是最稳的一条。**

任务书提示「消息菜单大概率是 AdapterView，构造菜单时必然知道是哪条消息 ⇒ 从菜单构造点回溯 msgId」。但**菜单构造点本身是混淆类**（`va4.m4` / `va4.j3`，见 `ConvMenuInject.kt` 注释），回溯它要反汇编一长串混淆代码，且菜单是**自绘 PopupWindow、项是 TextView**（`ConvMenuInject.kt:31-50` 实锤），菜单项**不持有 h9 引用**（只有文案）。从菜单回溯 msgId 是死路。

**正确的路是 adapter**：长按的 View 就是消息 item 的一部分，它所在的列表（ListView / RecyclerView）的 adapter **必然**能按 position 取到消息对象。这条链每一步都有公开/未混淆锚点。

### 1.2 聊天列表视图（实证）

`com.tencent.mm.ui.chatting.view.MMChattingListView`（classes4.dex，super `MMPullDownView`）—— 聊天页外层容器：

| 字段（`_r12_dexclass.py` 实证） | 类型 | 含义 |
|---|---|---|
| `T` | `Lcom/tencent/mm/pluginsdk/ui/tools/u3;` | 真正的列表视图 |
| `U` | `Lcom/tencent/mm/pluginsdk/ui/tools/t3;` | adapter |

| 方法（实证） | 签名 |
|---|---|
| `getListView()` | `()Lcom/tencent/mm/pluginsdk/ui/tools/u3;` |
| `getBaseAdapter()` | `()Lcom/tencent/mm/pluginsdk/ui/tools/t3;` |
| `setAdapter` | `(Lcom/tencent/mm/pluginsdk/ui/tools/t3;)V` |

`u3` 是**接口**（仿 ListView API，55 个方法），关键两个：
- `getPositionForView(Landroid/view/View;)I`（**拿 position**）
- `getChildAt(I)Landroid/view/View;` / `getChildCount()I`

`u3` 有三个实现（实证 `implements u3`）：
- `com.tencent.mm.pluginsdk.ui.tools.ChattingListView`（super `ListView`）→ **ListView 形态**
- `com.tencent.mm.pluginsdk.ui.tools.ChattingRecyclerView`（super `RecyclerView`）→ **RecyclerView 形态**
- `com.tencent.mm.plugin.bizui.widget.StoryListView`（朋友圈视频流，**不是**聊天消息，别认错）

`t3` 是 adapter 接口（4 方法）：`getCount()` / `getItem(I)Object` / `getView(...)` / `E(Object)`。

### 1.2b ⛔⛔ **`ChattingRecyclerView.getPositionForView` 恒返回 -1**（R16-V1 复核补，2026-10-24 21:2x）

> 这一节推翻本节原先「走 `u3` 接口一次打中、两种形态都可用」的说法。**必须按本节实现。**

**实测反汇编**（`_r14_walk.py dis classes4.dex 'Lcom/tencent/mm/pluginsdk/ui/tools/ChattingRecyclerView;' getPositionForView`）：

```
Lcom/tencent/mm/pluginsdk/ui/tools/ChattingRecyclerView;->getPositionForView(Landroid/view/View;)I
  [   0] const/4                v1             #-1
  [   1] return                 v1
```

**只有两条指令，恒返回 `-1`。** 它是为了满足 `u3` 接口而留的**空桩**。

⇒ **两个后果，都必须记住**：

1. **「走 `u3` 接口」救不了它**。`MMChattingListView.getListView()` 的实现是
   `iget-object v0, v1, T:u3; return-object v0` —— 返回的就是 `T` 字段里那个对象。
   而 `T` 在 `l(Context)` 的 `[155] iput-object v1, v10, T:u3` 处赋值，两个分支分别是：
   - `[ 56] check-cast ScrollControlRecyclerView` → `extends ChattingRecyclerView` ⇒ **RecyclerView 形态**
   - `[150] new-instance Lcom/tencent/mm/ui/chatting/view/l;` → `l extends ScrollControlListView` ⇒ **ListView 形态**

   即「接口反射」和「具体类反射」**最终调到的都是同一个实现**。文档 7.2 原先写的
   「`u3.getPositionForView` 是接口方法，两种形态都实现 ⇒ 一次反射打中」**是错的**：
   接口方法在 RecyclerView 形态下同样返回 -1。

2. **哪种形态由运行时 AB 开关决定，不能静态断定**。
   `l()` 的 `[14] sget adapter/x;->c1:I` → `[18] if-lt v1, 1`（`v1` 被 `op01` 夹成 0/1）→
   `[43] if-eqz v1 -> abs 150` 走 ListView 分支，否则走 RecyclerView 分支。
   `c1` 的赋值点是 `NewChattingTabUI.k()Z` 的 `[17] sput`，值为
   `dh4/e0;->e(Ldh4/g;->v;I)I`（AB 实验配置读取，**默认 4**）⇒ **真机上两种形态都可能出现**。

**✅ 两种形态下唯一都能用的定位路径**：

| 形态 | 判定 | 定位手段 |
|---|---|---|
| ListView | `list=listview` | `getPositionForView` 走 `android.widget.ListView` 继承来的**真实实现**（`ScrollControlListView` 13 个虚方法里**没有**它，未覆盖）⇒ 正常返回 position |
| RecyclerView | `list=recycler` | ⛔ **不能用 `getPositionForView`**（恒 -1）。必须改走 RecyclerView 自身：从被长按的 View 沿 `parent` 链找到该 item 的根 View，再 `RecyclerView.getChildAdapterPosition(View)`，或遍历 `0..getCount()-1` 用 `A0(i)` 取的 ViewHolder 的 `d` 字段与目标 View 比对 |

**探针必须打印 `list=` 并把两种形态分开判读**：`list=recycler` 时出现 `pos=-1` **是预期行为、不是链路故障**，不能据此判「adapter 链不通」。

**`ChattingRecyclerView` 是活类，不是死代码**：`newinst` 反查显示它只被
`ScrollControlRecyclerView.<init>(Context,AttributeSet)` 一处以 `invoke-direct` 调用，
而 `ScrollControlRecyclerView extends ChattingRecyclerView` —— 这是**构造链**，不代表无人使用。

### 1.2c 修复记录（f4-recyclerview，2026-10-24）

针对本节 §1.2b 的空桩问题，`ForceForward.msgsnapReport()` 已按 `listKind` 分派定位：

- **listview 形态**：继续走 `getPositionForView`（`ListView` 继承来的真实实现）。
- **recycler 形态**：不走 `getPositionForView`。改为：
  1. 从被长按 View 沿 `parent` 链向上找到 `parent === chatList` 的节点（item 根 View）；
  2. 反射 `RecyclerView.getChildAdapterPosition(View)`（public 方法，`getMethod` 含继承链）；
  3. 失败/返回 -1 时退化：遍历 `0 until getCount()`，用 `A0(I)` 取 ViewHolder，
     比对其 `d` 字段（`ViewHolder.itemView`）与 item 根 View 是否同一对象。

`posVia` 现在有这几种值：

| `posVia` | 含义 |
|---|---|
| `none` | 未进入定位（`chatList`/`v` 为空，或方法反射失败） |
| `getPositionForView` | listview 形态，走 `ListView` 真实实现 |
| `childAdapterPosition` | recycler 形态，`getChildAdapterPosition` 命中 |
| `A0scan` | recycler 形态退化扫描命中 |

输出串新增 `posFail=` 字段：recycler 形态所有定位手段都失败时打 `recyclerStub`
（把「形态导致的预期 -1」与「真故障」显式区分开，不再让 `pos=-1` 孤零零混在输出里）。

`tools/_r16_msgsnap.py` 的 `diagnose()` 判据同步调整：
- `list=recycler` 且（`posFail=recyclerStub` 或 `pos=-1` 但 `posVia` 表明已试过 recycler 专用路径）
  ⇒ 判读为「RecyclerView 形态下 getPositionForView 恒 -1，属已知预期，请检查 posVia 是否已是 childAdapterPosition」，
  **不**报「adapter 链不通」。
- `list=listview && pos=-1` ⇒ 这才是真的定位失败。

### 1.2d ⛔ F4 修复的主路径失效：`getChildAdapterPosition` 已被混淆成 `F0`（F5 修，2026-10-24）

> 这一节推翻 §1.2c 里「recycler 形态走 `RecyclerView.getChildAdapterPosition(View)`」的说法。
> **该方法在当前微信 dex 里不存在 —— F4 修复的主路径是死代码。**

**dex 证据（F5 逐条复现，见 `docs/_r16_f5_fix.md` §1）**：

1. `androidx.recyclerview.widget.RecyclerView`（classes12.dex）**被 R8 混淆**。
   160 个方法里**没有** `getChildAdapterPosition`；`(Landroid/view/View;)I` 签名的方法**只有两个**：
   - `F0` = `getChildAdapterPosition`（`I0(View)→c3`，null 返回 -1，否则 `c3.i()`）
   - `G0` = `getChildLayoutPosition`（同构，调 `c3.j()`）
2. `c3` = `RecyclerView$ViewHolder`（acc=0x401），`c3.i()`=getAdapterPosition、`c3.j()`=getLayoutPosition、`c3.d`=itemView。
3. `ChattingRecyclerView.getCount()` = `getAdapter()==null ? 0 : getAdapter().getItemCount()`（实锤，供 A0 扫描用）。

**正确候选链**（`ForceForward.msgsnapReport()` 已按此实现）：

```
arrayOf("F0", "G0", "getChildAdapterPosition", "getChildLayoutPosition")
```

- 逐个 `getMethod(name, View::class.java)`，第一个能拿到且 `invoke` 返回 `>= 0` 的胜出；
- `posVia` 记录**实际命中的名字**（`"F0"` / `"G0"` / `"getChildAdapterPosition"` / `"getChildLayoutPosition"`），不写抽象名；
- **顺序很重要**：`F0`/`G0` 在前（当前版本可用），原名在后（未混淆版本兜底）。

**`posFail` 三态**（替代原单一 `recyclerStub`）：

| `posFail` | 含义 |
|---|---|
| `none` | 成功（`pos >= 0`） |
| `noMethod` | 候选方法名全部不存在（`getMethod` 全抛）⇒ 代码 bug，要更新候选链 |
| `itemRootNotFound` | 向上 12 层没找到 `parent === chatList` 的节点 |
| `returnedMinusOne` | 方法存在且被调用，但都返回 -1（ViewHolder 已回收/移除） |

`A0(I)` 扫描降级为最后兜底：仅当 `noMethod` 时才跑，且用
`getFirstVisiblePosition()` 起点 + ±50 窗口（不再几千条全扫，规避主线程长按回调的 ANR 风险）。

**沉淀（铁律）**：

> **「AndroidX 有 public API」≠「微信 dex 里名字还在」。**
> 反射任何三方库方法名前，必须先到 `_cache/dex/` 实测 ——
> 微信对 AndroidX 库也做 R8 混淆，`getChildAdapterPosition` 这种「公开名」会被重命名成 `F0`。
> 上一轮 F4 忠实照抄了「反射 `getChildAdapterPosition`」的示意，主路径因此是死代码；
> 这一轮 F5 已用反汇编坐实混淆名并落到候选链。

### 1.3 消息 adapter（实证）

`t3` 的实现里有一个关键接口 `Leb4/q`（classes4.dex, access=`0x0601`），它就是**消息 adapter 的接口**：

```
Leb4/q（access 0x0601 interface，★ 实为 48 个方法，不是 4 个）
  getItem(I)Lcom/tencent/mm/storage/h9;   ← ★ 直接返回消息对象 h9（具体类，非 Object）
  getCount()I
  ... 另 46 个（B(JII)V / O(Jh9)V / s0(h9)V / getDoubleClickListener 等）
```

> ⚠️ **R16-V 更正（主会话手工解析 `method_ids` 复核）**：本节初稿写「`eb4/q` 是 4 方法接口」，
> **实测 48 个方法**（`_r12_dexclass.py` 与直接扫 `method_ids` 两种方式一致）。
> 但**核心判据不受影响**：`getItem (I)Lcom/tencent/mm/storage/h9;` 逐字为真 —— 返回具体类 h9，
> 不是 `Object`，所以「`adapter.getItem(pos)` 直接拿到 h9」这条结论**成立**。

`implements Leb4/q` 的三个类（都在 classes7.dex，**均已实测存在协变返回 `getItem(I)Lcom/tencent/mm/storage/h9;`
+ 桥接方法 `getItem(I)Ljava/lang/Object;`**）：
- `com.tencent.mm.ui.chatting.adapter.h`（super `BaseAdapter`，ctor `(Lib4/c; ListView;)V`）→ **ListView 形态的消息 adapter**
- `com.tencent.mm.ui.chatting.adapter.i0`（super `ju2/s0`，ctor 带 `u3` + `MvvmList`）→ RecyclerView/MVVM 形态
- `com.tencent.mm.ui.chatting.adapter.x`（super `ki4/k0`，ctor `(Lib4/c; u3;)V`）→ RecyclerView 形态

**adapter `h` 的反汇编（实证）**：
```
getItem(I)h9   →  iget List w ; invoke List.get ; return   （字段 w = Ljava/util/List;，即消息列表）
```
三个 adapter 都有 `getItem(I)Lcom/tencent/mm/storage/h9;`（返回值是**具体类型 h9**，不是 Object）。

⇒ **长按消息 item 的 View → 向上找列表（u3 实现）→ `getPositionForView(v)` → `adapter.getItem(pos)` 拿 h9**。全程无混淆、无 DAO 反查。

### 1.4 长按第一站（已由 ForceForward 实现，实证）

`ForceForward.installLongPressTrap()`（`ForceForward.kt:155-175`）hook `View.performLongClick` 的 before，已把**最深 View** 记进 `burstDeepest`（WeakReference），并带 `BURST_GAP_MS=500` / `BURST_TTL_MS=10_000`。

⇒ `snapshotNow()` **已经拿得到长按的 View**（`pressedView()`，`ForceForward.kt:471-475`），只差「从 View 到 h9」这一段。这一段就是 §1.2/§1.3 的 adapter 链。

### 1.5 待真机确认（推测，标 ⚠️）

- 聊天页到底是 **ListView 形态**（`ChattingListView` + adapter `h`）还是 **RecyclerView 形态**（`ChattingRecyclerView` + adapter `i0`/`x`）。两者 `getPositionForView` / `getItem` 都可用，**dump 脚本要两条路都试**（见 `tools/_r16_msgsnap.py` 头注释）。
- 消息 item 的 View 层级：`viewitems/a`（Kotlin 基类，ctor `(h9; Context; Lib4/m;)V`）只存了 `b:Context`，**没存 h9**；h9 存在 40+ 个 `ui/chatting` 子类里（`viewitems/ao.g`、`bc.d`、`c6.d`、`fr.d`…）。**但路线 A 不需要从 View 反解 h9**，adapter 会重新给，所以这一层不构成障碍。

---

## 2. msgId → 消息对象（DAO）

### 2.1 结论

**路线 A 不需要这节**（adapter 直接给 h9）。本节是**路线 B 的原料**：只拿到 msgId、拿不到 h9 时，反查消息对象。

### 2.2 DAO 类（实证）

`com.tencent.mm.storage.j9`（classes4.dex，super `k74/q0`，106 方法）—— 消息表 DAO。

**msgId → h9 的候选（按签名）**：
| 方法 | 签名 | 反汇编证据 | 判定 |
|---|---|---|---|
| `u3` | `(J)Lcom/tencent/mm/storage/h9;` | `const-string 'msgId=?'` + `new h9` + `convertFrom(Cursor)` + `moveToFirst` | ★ **强候选 `getByMsgId`**（单 long 参数，WHERE msgId=?） |
| `b3` | `(String;J)h9` | `const-string 'createTime=? AND'` | talker + createTime（不是 msgId） |
| `z3` / `u8` / `L3` | `(String;J)h9` | 未反汇编 | talker + 时间戳类 |
| `f8` | `(String;String)h9` | 未反汇编 | 可能 talker + msgSvrId |
| `B8` / `L5` / `Q8` / `U7` | `(String)h9` | 未反汇编 | talker → 最新一条 |

**`u3(J)` 反汇编（实证）**：
```
invokes: new h9 ; new cn ; j9.R9 ; StringBuilder ; z.f ; Cursor.moveToFirst ; h9.convertFrom ; Cursor.close
const-string: ['msgId=?', '']
```
⇒ `select * from message where msgId=?` 然后 `convertFrom(cursor)` 填进一个新 `h9`。**这就是 `getByMsgId`**。

### 2.3 ⛔ 关键坑：不能 newInstance（实证）

`j9` **无静态 `getInstance`**，唯一构造：
```
<init>(Lz84/z; Lcom/tencent/mm/storage/h8; Lcom/tencent/mm/storage/o8;)V
```
其中 `h8` / `o8` 都是 **interface（access 0x0601，无可 new）** ⇒ **不能用 `XposedHelpers.newInstance()` 造**，必须从微信已有实例取。

**但模块没有现成的 j9 实例**（j9 是微信内部 DAO，模块没 hook 过它的构造）⇒ **路线 B 不直接用 j9.u3，而是用 AntiRevoke.wxDb() 的 rawQuery**（见 §4）。

---

## 3. 消息对象 → msgType / 本地路径

### 3.1 消息对象三层继承（实证）

```
com.tencent.mm.storage.h9          (classes4.dex, access 0x0001, implements Cloneable)
  └ te0/y0                          (只有 static G1 + getDBInfo()，无实例字段)
      └ jj/m7                       (★ 真正持有全部 field_*，PRIVATE)
          └ k74/f0
```

### 3.2 字段（实证，`jj/m7` 实例字段，**字段名未混淆**）

| 字段名 | 类型 | 对应 |
|---|---|---|
| `field_msgId` | `J` | msgId |
| `field_type` | `I` | msgType |
| `field_isSend` | `I` | isSend（自己/别人） |
| `field_content` | `String` | 文本内容 / appmsg 的 XML |
| `field_imgPath` | `String` | 图片本地路径 |
| `field_talker` | `String` | 会话 wxid |
| `field_createTime` | `J` | 时间戳 |
| `field_lvbuffer` | `[B` | 扩展二进制（语音/视频等） |
| `field_msgSvrId` | `J` | 服务端 msgId |
| `field_status` / `field_flag` / `field_isShowTimer` / `field_talkerId` / `field_bizChatId` / `field_bizChatUserId` / `field_bizClientMsgId` / `field_fromUsername` / `field_toUsername` / `field_historyId` / `field_msgSeq` / `field_transContent` / `field_transBrandWording` / `field_solitaireFoldInfo` | — | 其余列 |

### 3.3 getter（实证，**名字未混淆，可直接反射**）

`jj/m7` 的 getter 反汇编（iget → field 映射，全部实证）：

| getter | 返回字段 |
|---|---|
| `getMsgId()J` | `field_msgId` |
| `getType()I` | `field_type` |
| `getContent()String` | `field_content` |
| `getCreateTime()J` | `field_createTime` |
| `getPrimaryKeyValue()Object` | （主键） |
| `getTableName()String` | （表名 message） |
| `p0()I` | `field_isSend` ← **isSend 无未混淆 getter，只有混淆名 p0** |
| `F0()String` | `field_toUsername` |
| `C0()String` | `field_talker` |
| `r0()[B` | `field_lvbuffer` |

`h9` 自己的转发 getter（反汇编实证）：`getType()I` → `jj/m7.getType`；`getCreateTime()J` → `jj/m7.getCreateTime`（但 h9.getCreateTime 有额外 share_msg 逻辑，见下）；`A1()J` → `jj/m7.getCreateTime`（纯转发）。

### 3.4 `snapshotNow()` 的反射路径（草案，可直接用）

```kotlin
// msgId（未混淆 getter，继承自 jj/m7）
msgInfo.javaClass.getMethod("getMsgId").invoke(msgInfo) as Long
// msgType（未混淆 getter）
msgInfo.javaClass.getMethod("getType").invoke(msgInfo) as Int
// isSend：字段在父类 jj/m7，PRIVATE，沿类链找 field_isSend
XposedHelpers.getObjectField(msgInfo, "field_isSend") as? Int
// 图片本地路径：同上沿类链找 field_imgPath
XposedHelpers.getObjectField(msgInfo, "field_imgPath") as? String
```

⚠️ 注意：`XposedHelpers.getObjectField` **沿父类链找**（`ConvMenuInject.kt:691` 注释明确），所以对 `field_isSend` / `field_imgPath`（声明在 `jj/m7`，对象实际是 `h9`）能命中。

### 3.5 msgType 常量对账

| kind | type 值 | 依据 |
|---|---|---|
| 文本 | 1 | `AntiRevoke.kt:31` 实测 `type=1 content=123`（文本） |
| 图片 | 3 | `ForwardLogic` 惯例，**未在 8.0.48 逐条对账（推测）** |
| 语音 | 34 | 同上 |
| 视频 | 43 | 同上 |
| 名片 | 42 | 同上 |
| 定位 | 48 | 同上 |
| appmsg（文件/链接等） | 49 | 同上；子类型看 content XML 的 `<appmsg><type>` |
| 系统/撤回 | `0x10000000 or 10000/10002` | `AntiRevoke.kt:23-27` 实测 |

### 3.6 本地文件路径（实证 / 推测分开）

- **图片**：`field_imgPath`（实证字段存在；语义「图片路径」按列名+惯例判断，**待真机 dump 确认非空**）。
- **语音 / 视频 / 普通文件**：⚠️ **推测**。h9 持有 `H1(c9)` / `I1(a9)` / `J1(f9)` 三个子对象，但 `a9`（1 字段 `a:String`）、`f9`（`a:Z b:J`）方法表为空、`c9` 是 interface —— 没能从静态证据坐实「哪个子对象给语音/视频/文件路径」。
  - 语音（type=34）路径极可能藏在 `field_lvbuffer` 或 `field_imgPath`；
  - 视频（type=43）路径可能复用 `field_imgPath`；
  - 普通文件（type=49 + appmsg type=6）路径在 `field_content` 的 XML 里（`<appmsg>...<fileext>...`）。
  **这些必须靠真机 dump 一次抓全**（脚本把 imgPath 一起打出来，见 `tools/_r16_msgsnap.py`）。

---

## 4. 备选路线（AntiRevoke.wxDb() rawQuery）

### 4.1 ⛔ 依赖关系（任务书点名，必须写清）

```
路线 B 前提：先有 msgId。
msgId 从哪来？→ 只有第 1 节（长按上下文 / adapter.getItem(pos).getMsgId()）。
⇒ 路线 B 完全依赖第 1 节成立。
  第 1 节不通，路线 B 就是「有 SQL 没参数」，一条都查不了。
```

### 4.2 路线 B 是什么（实证）

模块已持有**微信主库（message 表所在库）句柄**：`AntiRevoke.wxDb()`（`AntiRevoke.kt:643`），它是 `updateWithOnConflict` hook 在 `table=="message"` 时捕获的（`AntiRevoke.kt:342-343`），**只认 message 表所在库，不退 lastDb**（多库坑，`AntiRevoke.kt:634-642`）。

配套有现成查询句柄：`AntiRevoke.msgQueryHandle()` → `(db, rawQueryMethod)`，`rawQueryMethod` 已处理 WCDB 的 `String[]` vs `Object[]` 重载（`AntiRevoke.kt:876-890`）。

⇒ 拿到 msgId 后：
```kotlin
val (db, m) = AntiRevoke.msgQueryHandle() ?: return
val cur = m.invoke(db, "select msgId,type,isSend,imgPath,talker,content from message where msgId=?", bindArgs(msgId))
```

### 4.3 路线 A vs B 取舍

- **A 优先**：adapter.getItem(pos) 直接给 h9，零 SQL、零库竞争，`getType/getMsgId` 未混淆。
- **B 兜底**：当「拿不到列表/adapter」（比如消息菜单不是从列表长按弹出，而是从别的入口）时，用 msgId 反查。但 B 需要 msgId，而 msgId 得先有 h9 或 hook 到写库 —— 死锁。
  ⇒ **B 的唯一现实用途是：A 拿到了 msgId 但想绕开「h9 对象被回收/字段读不到」时，用 msgId 回库查**。它不解决「连 msgId 都没有」的情况。

---

## 6. ⛔ 边界声明

### 6.1 实证（有反汇编 / 真机证据，可直接采信）

1. `MMChattingListView.getBaseAdapter()/getListView()` 与字段 `T(u3)/U(t3)`（`_r12_dexclass.py`）。
2. `u3` 接口有 `getPositionForView`；实现类 `ChattingListView`（ListView）/ `ChattingRecyclerView`（RecyclerView）（implements 关系实测）。
3. 消息 adapter 接口 `Leb4/q.getItem(I)h9` 及三个实现 `adapter/h`、`adapter/i0`、`adapter/x`（均 `getItem(I)h9`）。
4. `adapter/h.getItem` 反汇编 = `iget List w; List.get`（消息列表字段 `w`）。
5. `jj/m7` 的 `field_msgId/field_type/field_isSend/field_imgPath/...` 及未混淆 getter `getMsgId/getType/getContent/getCreateTime`（iget 映射反汇编）。
6. `h9.getType()I` → `jj/m7.getType`（反汇编）。
7. DAO `j9.u3(J)h9` = `WHERE msgId=?` + `convertFrom`（反汇编）；`j9` 无静态工厂、构造参数是 interface（不能 newInstance）。
8. `AntiRevoke.wxDb()` / `msgQueryHandle()` / `rawQueryMethod` 的 WCDB 重载处理（`AntiRevoke.kt` 源码）。
9. type 值：`1`=文本、撤回=`0x10000000|10000/10002`（`AntiRevoke.kt` 真机注释）。

### 6.2 推测（未坐实，真机 dump 前不得当作事实）

1. 聊天页实际走 ListView 还是 RecyclerView 形态（两个都准备了，待真机）。
2. type 值 3/34/43/42/48/49 的 8.0.48 逐条对账（沿用 ForwardLogic 惯例）。
3. 语音/视频/普通文件的本地路径具体在哪个字段（`field_imgPath` / `field_lvbuffer` / content XML / 子对象 H1/I1/J1）。
4. `field_imgPath` 对图片是否恒非空。

### 6.3 铁律（`ForceForward.kt:50` 原话）

> **严禁猜微信内部 API —— 猜错就是崩微信。**

所以：
- §1/§3 的 `getMsgId/getType/field_imgPath/field_isSend` 是**未混淆名字 + 反汇编坐实**，可反射；
- §2 的 `j9.u3` 是**反汇编坐实**的 DAO，但**没有现成 j9 实例**，不硬造；
- §3.6 的语音/视频/文件路径**没有坐实**，dump 脚本只读不打，绝不猜字段名去写。

---

## 7. R16-V 主会话独立复核（2026-10-24，手工解析 DEX `method_ids` / `field_ids`）

> 复核方式：**不采信子代理结论**，直接读 `classes4.dex` / `classes7.dex` 的
> `method_ids` 与 `field_ids` 表打印真实签名（脚本 `/tmp/chk.py` 思路见下）。

### 7.1 逐条核对结果

| # | 子代理断言 | 复核结果 |
|---|---|---|
| 1 | `MMChattingListView.getListView()Lu3;` / `getBaseAdapter()Lt3;` / `setAdapter(t3)V` | ✅ **完全属实** |
| 2 | `MMChattingListView` 字段 `T:u3` / `U:t3` | ✅ 属实（另 14 个字段也对得上） |
| 3 | `u3` 是接口、55 方法、含 `getPositionForView(View)I` | ✅ 属实（`0x401` = public abstract，NO-CODE） |
| 4 | `eb4/q` 是消息 adapter 接口，`getItem(I)Lcom/tencent/mm/storage/h9;` | ✅ **getItem 签名属实**；❌ **但方法数是 48 不是 4**（已在 §1.3 订正） |
| 5 | 三个实现 `adapter/h`、`adapter/i0`、`adapter/x` 都有 `getItem(I)h9` | ✅ **全部属实**；且三者**同时**有协变版 `(I)h9` 与桥接版 `(I)Object` |
| 6 | 继承链 `h9 → te0/y0 → jj/m7 → k74/f0` | ✅ **逐级核实**（`h9.super=te0/y0`，`te0/y0.super=jj/m7`，`jj/m7.super=k74/f0`） |
| 7 | `jj/m7` 的 `field_msgId J` / `field_type I` / `field_isSend I` / `field_imgPath String` / `field_lvbuffer [B` | ✅ **全部属实**（实取 24 个 `field_*`，类型逐一吻合） |
| 8 | `getMsgId()J` / `getType()I` / `getContent()String` / `getCreateTime()J` 未混淆 | ✅ 属实（`jj/m7` in classes4.dex） |
| 9 | `p0()` = `field_isSend` | ✅ 方法存在，`p0()I` 签名吻合（语义未反汇编，采信其 iget 结论） |

### 7.2 ⛔ 复核发现的两处真实错误（子代理示意代码不能照抄）

**错误 1：`ChattingListView` 没有 `getPositionForView`**

实测 `ChattingListView`（super=`android.widget.ListView`）的 `method_ids` 里
**只有 `m()Lt3;` 一个相关方法**，没有 `getPositionForView`。它只能走 `ListView` 继承来的同名方法。
`ChattingRecyclerView`（super=`RecyclerView`）**自己声明了** `getPositionForView(Landroid/view/View;)I`。

⇒ 子代理示意代码里的 `list.javaClass.getMethod("getPositionForView", View::class.java)`
对 ListView 形态会**抛 NoSuchMethodException**（`getMethod` 只找 public 且含继承，
`ListView.getPositionForView` 确实是 public，所以实际能拿到 —— 但**不是** `ChattingListView` 声明的）。

✅ **实际采用的更稳路径**：`MMChattingListView.getListView()` 返回 `u3`，
`u3.getPositionForView` 是**接口方法**，两种形态都实现 ⇒ 一次反射打中。

**错误 2：取 adapter 只有混淆名 `m()`，没有 `getAdapter`**

实测 `ChattingListView` / `ChattingRecyclerView` **都只有 `m()Lt3;`**。
子代理写的 `getMethod("m") ?: getMethod("getAdapter")` 后半段是**猜的**（`getAdapter` 不存在）。

✅ **实际采用**：优先 `MMChattingListView.getBaseAdapter()`（**未混淆名**），退回 `m()`。

### 7.3 ⚠️ 一个子代理没提的关键点：`t3.getItem` 返回的是 `Object`

`t3` 接口签名实测为 `getItem(I)Ljava/lang/Object;`（**不是** h9）。
只有 `eb4/q` 与三个实现类才有协变返回 `(I)Lcom/tencent/mm/storage/h9;`。
而 `getBaseAdapter()` 的**静态类型是 `t3`** ⇒ 拿回来是 `Object`。

⇒ 探针里**不能写 `as? h9` 强转**（`h9` 是微信类，模块无编译期引用，也不需要），
一律按名字反射 `getMsgId()` / `getType()` / `getField("field_isSend")`。

### 7.4 已落地的接线（本文件对应的模块改动）

`ForceForward.kt` 已加 `sub=msgsnap` 分支 + `msgsnapReport()`，`else -> "subs=..."` 同步更新。
输出格式（脚本 `MSGSNAP_PREFIX` 逐字匹配）：

```
FORWARD MSGSNAP talker=... view=... ageMs=N attached=true|false|n/a list=listview|recycler|none \
  pos=N posVia=... adapter=... adapterVia=getBaseAdapter|m()|none h9=... \
  msgId=N|none type=N|none isSend=N|none imgPath=...|none menu=文案+文案
```

比子代理原方案多打了 `ageMs` / `attached` / `posVia` / `adapterVia` / `h9` 五个字段。

### 7.5 ⛔ 两个会导致「假失败」的真机坑（已用探针字段规避）

**坑 1：TTL 只有 10 秒，而 adb 广播经常超过 10 秒**

`pressedView()`（`ForceForward.kt:471`）带 `BURST_TTL_MS = 10_000L` 过期判断 ——
生产路径必须这样（不然会拿陈旧 View 发错消息）。但真机取证时
「手长按 → adb 发广播」这个间隔**经常 > 10 秒**（`am start` + sleep + adb 往返），
直接调 `pressedView()` 会**稳定拿到 null**，输出成 `view=none`，
极易被误判成「adapter 链不通」。

⇒ 探针改走新加的 `staleViewForProbe()`（**忽略 TTL**，只读最近一次长按的 View），
并额外打 `ageMs=`。真机上这个值明显 > 10000 属**正常**，不是异常。

**坑 2：陈旧 View 可能已从窗口摘下，`getPositionForView` 必然 -1**

`ListView.getPositionForView` 对不在某列表里的 View 返回 `-1`。
陈旧 View 若已被回收/移出，`pos=-1` 是**View 的问题**，不是链路的问题。

⇒ 探针额外打 `attached=`（`isAttachedToWindow`）。
判读顺序必须是：先看 `attached`，`false` 就**重做长按缩短间隔**，别急着怀疑 adapter 链。

---

