# R29-5 G2：长按消息 View → 反查消息对象 `storage/h9`（静态 dex 取证）

> 轮次：R29-5 缺口 G2　对象：wxgroup（微信 8.0.48 / 2588 GP 的 LSPosed 模块）
> 只读取证，不写业务代码、不跑 gradle、不碰 adb。证据三档：① 字面 / ② 强推测 / ③ 未定位。
> 工具：`tools/_r28_cls.py` / `_r28_meth.py` / `_r28_strref.py` / `_r28fw_methsig.py` / `_r28fw_where.py`
> 本轮新增：`tools/_r29_g2_clsdump.py`（字段+方法 dump；⚠️ 见 §1 附注的坑）

---

## 0 结论摘要

**核心答案：从长按的 View 出发，走「列表位置 → adapter.getItem(position)」拿 h9，是全链路里唯一有 ① 字面证据、且不依赖主线程内部 hook 的路径。** View 的 tag 只存 holder（`viewitems/f0` 子类），holder 里**没有** h9 字段；h9 是 getView 时传进来的瞬时参数，只能经 adapter 的 `getItem` 取回。

| 路径 | 可行性 | 等级 | 反射调用序列（核心） |
|---|---|---|---|
| A. View→列表位置→`adapter.getItem(pos)` | ✅ 推荐 | ① | 见 §3 |
| B. View 的 tag → holder(`f0`) → `getCurrentMsgInfo(Lib4/c)` | ⚠️ 可用但绕 | ①（tag 存 holder）②（需 ChattingContext） | `view.getTag()` → `f0.getCurrentMsgInfo(Lib4/c)` → `ss.b(n0, Lib4/c)` |
| C. itemView/holder 直接持有 h9 字段 | ❌ 不成立 | ①（无此字段） | — |
| D. Adapter `getView` 里 `convertView.setTag(h9)` | ❌ 不成立 | ①（setTag 塞的是 holder 不是 h9） | — |
| E. Listener 捕获字段持有 h9 | ② 仅菜单回调 | ①（菜单回调 `bi4/z` 有 h9，属 G1 范畴，非 View 反查） | — |

**拿到 h9 后取消息三要素（§4）**：msgId = `getMsgId()J`（公开）；msgType = `getType()I`（公开）；本地路径 = `field_imgPath:String`（private，`getDeclaredField`+`setAccessible`）或 getter `o0()Ljava/lang/String;`。

[DONE] 0 结论摘要

---

## 1 `storage/h9` 类确认（真实类名 + 关键方法签名）

### 1.1 本批 dex 里的消息类是 `com.tencent.mm.storage.h9`，继承链三层（①）

```
python tools/_r28_cls.py storage/h9
=== classes4.dex  (1)
   [15946] Lcom/tencent/mm/storage/h9;
          super=Lte0/y0;
=== classes7.dex  (1)
   [ 2823] Lcom/tencent/mm/plugin/finder/storage/h9;
          super=Lkotlin/jvm/internal/q;     ← ⚠️ 同名不同类，finder 插件，勿混
```

继承链（用 `_r29_g2_clsdump.py` dump 各层 super）：

```
com.tencent.mm.storage.h9   (classes4.dex #15946)  super = te0/y0
te0/y0                       (classes4.dex #15520)  super = jj/m7
jj/m7                        (classes4.dex #8594)   super = k74/f0   ← AutoDBItem 基类，全部 field_* 列在这层
k74/f0                       （DB 基类）
```

### 1.2 关键方法签名（①，`_r28_meth.py` 交叉验证）

```
python tools/_r28_meth.py getMsgId getType getCurrentMsgInfo
   inst virtual Ljj/m7;->getMsgId()J                          ← msgId（未混淆 getter）
   inst virtual Ljj/m7;->getType()I                           ← msgType
   inst virtual Lcom/tencent/mm/storage/h9;->getType()I       ← h9 覆写（转发/日志走这个）
   inst virtual Lcom/tencent/mm/ui/chatting/viewitems/f0;->getCurrentMsgInfo(Lib4/c;)Lcom/tencent/mm/storage/h9;
```

- `h9.A1()J` 反汇编为 `invoke-super jj/m7->getCreateTime`，**不是** msgId，勿误用。
- `jj/m7.getMsgId()J` / `getType()I` 是**未混淆名**（`getMethod("getMsgId")` 可直接反射命中 public 方法）。

### 1.3 附注：本工具（`_r29_g2_clsdump.py`）对接口/抽象类的方法解析会错位（② 已知坑）

`_r29_g2_clsdump.py` 对 `pluginsdk/ui/tools/t3`、`eb4/q` 这类**接口**的方法列表解析出重复项（同一签名 ×N），原因同 R28 坑 2（abstract 方法无 `code_off`，access 位未标 abstract 时 uleb 错位）。**字段解析不受影响**（字段恒 `diff+access` 两个 uleb，无条件分支）。本报告所有「方法名→类」的结论一律以 `_r28_meth.py` 为准，`_r29_g2_clsdump.py` 只用于字段与普通类方法。

[DONE] 1 storage/h9 类确认

---

## 2 候选路径逐条验证

### 2.1 View tag：存的是 holder（`f0` 子类），不是 h9（①）

反汇编具体消息卡片 `viewitems/b8`（extends `s0`）的 `L(LayoutInflater, View)`：

```
python tools/_r27_disasm.py --class 'Lcom/tencent/mm/ui/chatting/viewitems/b8;' --method L | grep -iE "setTag|getTag|new-instance"
   0x0002  invoke-virtual View->getTag
   0x0008  new-instance viewitems/xi
   0x0011  new-instance viewitems/d8        ← d8 extends f0（ViewHolder 子类）
   0x0015  invoke-direct viewitems/d8-><init>
   0x0018  invoke-virtual View->setTag      ← 把 d8 holder 塞进 convertView 的 tag
```

结论：**消息卡片根 View 的 tag = `viewitems/f0` 子类 holder**（各消息类型一套：`d8`/`a9`/`i7`…）。tag 里**没有 h9**。

### 2.2 itemView/holder 基类字段里有没有直接持 h9？（① 无）

`viewitems/f0`（ViewHolder 基类，classes7.dex #5329）实例字段（`_r29_g2_clsdump.py`）：

```
[inst fld] chatHolder  : Lcom/tencent/mm/ui/chatting/adapter/n0;   ← 聊天 holder（含 position）
[inst fld] chattingItem: Lcom/tencent/mm/ui/chatting/viewitems/s0;  ← 消息策略对象
[inst fld] convertView : Landroid/view/View;
[inst fld] avatarIV/timeTV/userTV/checkBox/...                     ← 都是子 View
```

`viewitems/s0`（ChattingItem 策略基类）实例字段：`e/f/g/h/i/j/n/o/p/q/r/s`（全是别的辅助类型，无 h9）。

**结论：holder 和 item 里都没有 `Lcom/tencent/mm/storage/h9;` 字段。** h9 是 `getView`/`e(f0,h9,n0,Lib4/c)` 时的瞬时参数，不落字段。

### 2.3 Adapter `getView`：`getItem(pos)` 才是 h9 的出处，setTag 塞的是 holder（①）

`adapter/h`（`android.widget.BaseAdapter` 子类，classes7.dex #4815）关键成员：

```
[inst fld] u : Lib4/c                                  ← ChattingContext
[inst fld] K : Landroid/widget/ListView                ← 列表引用
[inst fld] v : LayoutInflater
inst virtual ->getItem(I)Lcom/tencent/mm/storage/h9;   ← ★ 权威 getItem
inst virtual ->getItem(I)Ljava/lang/Object;            ← bridge
inst virtual ->getView(ILandroid/view/View;Landroid/view/ViewGroup;)Landroid/view/View;
static direct ->e(f0, h9, n0, Lib4/c)V                  ← 填充 holder
```

反汇编 `adapter/h.getView`（`_r27_disasm.py`，节选）：

```
0x000a  invoke-virtual/range adapter/h->getItem   ← position → h9
0x0025  invoke-virtual jj/m7->getMsgId            ← 日志打印 msgId（坐实 getItem 返回的是 h9）
0x003d  invoke-virtual storage/h9->getType
0x0139  invoke-static viewitems/vs->b             ← 拿 s0 策略对象
0x013f  invoke-virtual viewitems/s0->L            ← 建 itemView + holder
0x0143  invoke-virtual View->getTag               ← 取回 holder
```

`adapter/h.e(f0,h9,n0,Lib4/c)` 反汇编：`f0.setChatHolder(n0)` → `s0.F/l/k/R/j/i(...)` 填充 UI。**h9 只读不存。**

### 2.4 Listener 捕获字段（② 非本缺口所需）

长按菜单点击回调 `bi4/z.onMMMenuItemSelected(MenuItem, I)`（G1 §1.2 已证，classes3.dex）持有 h9，但那走的是「菜单项 → h9」的 G1 转发入口，不是「View → h9」。本缺口（模块拿的是长按 View，不是菜单回调参数）不采用。

### 2.5 View → 位置 → adapter.getItem（① 推荐，本缺口唯一全 ① 路径）

**列表容器（①，`_r28_cls.py`）**：

```
com.tencent.mm.pluginsdk.ui.tools.ChattingListView    super=android.widget.ListView     ← 未混淆名，可靠
com.tencent.mm.pluginsdk.ui.tools.ChattingRecyclerView super=androidx.recyclerview.widget.RecyclerView
com.tencent.mm.ui.chatting.view.MMChattingListView     super=MMPullDownView              ← 外壳，getBaseAdapter()/getListView()
```

**position 定位（①）**：
- ListView 形态：`getPositionForView(View)I`（ListView 继承来的真实实现）。
- RecyclerView 形态：`ChattingRecyclerView.getPositionForView(View)I` 是**空桩**（反汇编仅 `const/4` + `return`，忽略形参）。真实实现是 androidx `RecyclerView` 的两个**混淆名**：

```
python tools/_r28fw_methsig.py '(Landroid/view/View;)I' | grep RecyclerView
   virtual androidx/recyclerview/widget/RecyclerView;->F0(Landroid/view/View;)I   ← getChildAdapterPosition
   virtual androidx/recyclerview/widget/RecyclerView;->G0(Landroid/view/View;)I   ← getChildLayoutPosition
```

**adapter 获取（①）**：
- `MMChattingListView.getBaseAdapter() → pluginsdk/ui/tools/t3`（未混淆名，优先）。
- `ChattingListView.m() → pluginsdk/ui/tools/t3`（混淆名，退回）。

**getItem 返回值类型（①，`_r28_meth.py getItem`）**：

```
inst virtual Lcom/tencent/mm/pluginsdk/ui/tools/t3;->getItem(I)Ljava/lang/Object;       ← 包装/基类，返 Object
inst virtual Leb4/q;->getItem(I)Lcom/tencent/mm/storage/h9;                              ← 底层接口，返 h9
inst virtual Lcom/tencent/mm/ui/chatting/adapter/h;->getItem(I)Lcom/tencent/mm/storage/h9;   ← 具体适配器（返 h9）
inst virtual Lcom/tencent/mm/ui/chatting/adapter/h;->getItem(I)Ljava/lang/Object;       ← bridge
inst virtual Lcom/tencent/mm/ui/chatting/adapter/i0;->getItem(I)Lcom/tencent/mm/storage/h9;  ← 具体适配器（返 h9）
inst virtual Lcom/tencent/mm/ui/chatting/adapter/x;->getItem(I)Lcom/tencent/mm/storage/h9;   ← 具体适配器（返 h9）
```

**结论**：`getBaseAdapter()`/`m()` 静态返回 `t3`（`getItem` 返 Object），运行时实际是 `h`/`i0`/`x` 之一（返 h9）。模块反射 `getItem(int)` 拿回 `Object` 后按名字取字段即可，无需编译期引用 h9。

[DONE] 2 候选路径逐条验证

---

## 3 推荐路径 + 完整反射调用序列（伪代码，不写进 src）

> 与模块现有探针 `ForceForward.msgsnapReport()` 已实现链路一致（R16 真机验证过），本轮补充 dex ① 证据。

```
// 0. 起点：长按最深 View（模块已有 pressedView()，注意 TTL）
View v = pressedView()                      // 过期/为 null → 直接放弃

// 1. 向上 ≤12 层找列表容器，按「继承链」判定形态（不要用类名 contains）
View list = null; String kind = "none";
for (View n = v; n != null && depth < 12; n = n.getParent()) {
    String cn = n.getClass().getName();
    if (cn.equals("com.tencent.mm.pluginsdk.ui.tools.ChattingRecyclerView")) { kind = "recycler"; list = n; break; }
    if (cn.equals("com.tencent.mm.pluginsdk.ui.tools.ChattingListView"))    { kind = "listview"; list = n; break; }
}
// 兜底：外层 MMChattingListView（含 "MMChattingListView" 的类名可安全 contains）→ getListView()

// 2. position（分形态）
int pos = -1;
if (kind == "listview") {
    pos = (int) list.getClass().getMethod("getPositionForView", View.class).invoke(list, v);
} else if (kind == "recycler") {
    View itemRoot = /* 从 v 沿 parent 向上到 parent==list 的那个 */;
    for (String m : new String[]{"F0", "G0", "getChildAdapterPosition", "getChildLayoutPosition"}) {
        try { pos = (int) list.getClass().getMethod(m, View.class).invoke(list, itemRoot); }
        catch (NoSuchMethodException e) { continue; }
        if (pos >= 0) break;
    }
    // pos 仍 <0 → A0scan：getFirstVisiblePosition() 起窗口，A0(i) 拿 ViewHolder 比对 d==itemRoot
}

// 3. adapter
Object adapter = outer.getClass().getMethod("getBaseAdapter").invoke(outer);  // MMChattingListView
if (adapter == null) adapter = list.getClass().getMethod("m").invoke(list);    // ChattingListView/RecyclerView 混淆名

// 4. h9（反射 getItem，不做编译期强转）
Object h9 = adapter.getClass().getMethod("getItem", int.class).invoke(adapter, pos);
//   h9 的运行时类是 storage/h9 或 jj/m7 子类；List.getPositionForView 含 header 偏移时需减 header 数
```

**取舍理由**：
- 选 A（adapter）而非 B（tag→holder→`getCurrentMsgInfo`）：A 不依赖 `Lib4/c`（ChattingContext），B 还得再从 adapter/Activity 挖 ChattingContext，多一层且无增益——因为 `ss.b(n0, Lib4/c)` 内部最终也是 `n0.getAdapterPosition()` → `Lib4/c.c.manager.a.a().getItem(pos)`，兜回同一位置。
- 反射成本：A 全程标准 API + 一次 `getItem` 反射，主线程可完成（getView 本身就在主线程）。
- 混淆稳定性：列表容器类名（`ChattingListView`/`ChattingRecyclerView`/`MMChattingListView`）是 R8 keep 名（①），`getMsgId`/`getType` 是未混淆 getter（①）；唯一混淆点是 RecyclerView 的 `F0/G0` 与 adapter 的 `m()`，已给候选链兜底。

[DONE] 3 推荐路径

---

## 4 从 h9 取 msgId / msgType / 本地路径（具体成员 + 签名）

字段全在父类 `jj/m7`（classes4.dex），**access = 0x2（PRIVATE）**（①，实测）：

| 需要 | 方法（公开，优先） | 字段（private，需 setAccessible） | 签名/类型 |
|---|---|---|---|
| msgId | `getMsgId()` | `field_msgId` | `()J` / `J` |
| msgType | `getType()`（h9 覆写） | `field_type` | `()I` / `I` |
| 图片本地路径 | —（无未混淆 getter） | `field_imgPath` | `Ljava/lang/String;` |
| 视频本地路径 | — | `field_imgPath`（② 同一字段） | `Ljava/lang/String;` |
| 方向（我发/他发） | — | `field_isSend` | `I` |
| 正文 | `getContent()` | `field_content` | `()Ljava/lang/String;` / `String` |
| talker | —（混淆名 `C0()`） | `field_talker` | `String` |

**imgPath getter 的混淆名映射（①，反汇编坐实）**：

```
python tools/_r27_disasm.py --class 'Ljj/m7;' --method o0 | grep iget
   0x0000  iget-object v16, v1, Ljj/m7;->field_imgPath:Ljava/lang/String;
```
⇒ `o0()Ljava/lang/String;` = `field_imgPath` 的 getter（其余映射：`C0()→field_talker`、`l0()→field_fromUsername`、`F0()→field_toUsername`、`getContent()→field_content`）。

**取字段的正确姿势**（字段 private 且在父类 `jj/m7`，不是 `h9` 自己）：
- `XposedHelpers.getObjectField(h9, "field_imgPath")`（内部沿类链 `getDeclaredField`+`setAccessible`）。
- 或 `getDeclaredField` 遍历 `h9.getClass().superclass` 链找 `jj/m7` 再取。

**视频路径 ② 说明**：`jj/m7` 无 `field_videoPath` 之类独立字段；按微信惯例视频消息（`getType()==43`）的 `field_imgPath` 即 .mp4 本地路径。属强推测，真机需验。

[DONE] 4 从 h9 取成员

---

## 5 失败与边界

| 场景 | 表现 | 区分特征 |
|---|---|---|
| 长按 TTL 过期（10s） | `pressedView()==null` | 探针用 staleView 绕过；生产应放弃 |
| View 已摘窗 | `getPositionForView` 返回 -1 | `isAttachedToWindow==false`，与「链路不通」是两件事 |
| RecyclerView 形态用了 `getPositionForView` | 恒 -1 | 它是空桩（①），必须走 F0/G0/A0scan |
| RecyclerView 混淆名失效（未来版本） | F0/G0 都 NoSuchMethod | 候选链含未混淆原名 `getChildAdapterPosition` 兜底 |
| `getBaseAdapter()`/`m()` 拿不到 | adapter==null | 退回另一路；两路都 null 报 `adapter=none` |
| `getItem(pos)` 返回 null | h9==null | 列表有 header/footer 时 pos 要减 `getHeaderViewsCount()` |
| `getItem` 返回类型是 `t3` 包装 | 返 Object 非 h9 | 仍按名字反射 `getMsgId`，不要 `as h9` 强转 |
| msgId==0 / imgPath 空 | 图片未下载本地 | CDN 图 `field_imgPath` 可能为空，转发需先下载（另缺口） |

[DONE] 5 失败与边界

---

## 6 已排除范围

- `com.tencent.mm.plugin.finder.storage.h9`：finder 插件消息类，非聊天消息（①，super=kotlin）。
- `com.tencent.mm.storage.MsgInfo`：字符串在 classes7.dex 出现过，但**不在任何 dex 的 class_defs**（`_r28_cls.py` 零命中）——本版本已更名 `h9`，属历史残留字符串，勿反射此名。
- holder/itemView 直接持有 h9 字段：不存在（①，f0/s0 字段清单）。
- `convertView.setTag(h9)`：不存在（①，setTag 塞的是 holder `f0` 子类）。
- `ChattingRecyclerView.getPositionForView`：空桩，不能用于 RecyclerView 定位（①）。
- `h9.A1()J`：是 `getCreateTime`，不是 msgId（①）。

[DONE] 6 已排除范围

---

## 附：本轮新增/复用工具与证据索引

- 新增 `tools/_r29_g2_clsdump.py`：dump 类字段+方法（字段可靠；接口方法解析有已知错位坑，方法结论一律用 `_r28_meth.py` 复核）。
- 关键反汇编：`adapter/h.getView`（getItem→h9）、`adapter/h.e`（填充不存 h9）、`viewitems/b8.L`（setTag=holder）、`ss.b`（pos→manager→getItem）、`ChattingRecyclerView.getPositionForView`（空桩）、`jj/m7.o0`（→field_imgPath）。
- 关键交叉验证：`_r28_meth.py getItem`（t3/eb4/q/h/i0/x）、`_r28_meth.py getMsgId/getType`（jj/m7）、`_r28fw_methsig.py '(View)I'`（RecyclerView F0/G0）。

[DONE]
