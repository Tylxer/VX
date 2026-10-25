# ⛔ F4 修复的致命缺陷：两个 P0（`chatList` 匹配失配 + `getChildAdapterPosition` 已混淆成 `F0`）

**发现时间**：2026-10-24 22:0x ~ 22:2x（纯离线取证，未上机）
**发现者**：§2 的主会话独立查出；§0 由 V2 复核子代理报出、主会话独立核实
**严重级**：**P0（阻断）** —— F4 的 P0-2「修复」实际**完全没生效**，探针在 RecyclerView 形态下依旧拿不到 `pos`。

---

## 0. ⛔⛔ 更靠前的 P0：`chatList` 查找条件恒失配（V2 报，主会话核实）

### 0.1 F4 现在的写法（`ForceForward.kt:806-821`）

```kotlin
while (node != null && guard++ < 12) {
    val n = node.javaClass.name
    if (chatList == null &&
        (n.contains("ChattingListView") || n.contains("ChattingRecyclerView"))
    ) chatList = node
    if (n.contains("MMChattingListView")) { outer = node; break }
    node = node.parent as? View
}
val listKind = when {
    chatList == null -> "none"
    chatList.javaClass.name.contains("Recycler") -> "recycler"
    else -> "listview"
}
```

### 0.2 为什么必然失配

`MMChattingListView.l(Context)` 实际 new 出来的**运行时对象**不是 `ChattingListView` /
`ChattingRecyclerView` 本身，而是它们的**子类**：

```
$ python tools/_r13_findclass.py Lcom/tencent/mm/pluginsdk/ui/tools/ScrollControlRecyclerView
  super = Lcom/tencent/mm/pluginsdk/ui/tools/ChattingRecyclerView;    ← 子类

$ python tools/_r13_findclass.py Lcom/tencent/mm/pluginsdk/ui/tools/ScrollControlListView
  super = Lcom/tencent/mm/pluginsdk/ui/tools/ChattingListView;        ← 子类

$ python tools/_r13_findclass.py Lcom/tencent/mm/ui/chatting/view/l
  super = Lcom/tencent/mm/pluginsdk/ui/tools/ScrollControlListView;   ← 孙类
  <init> (Lcom/tencent/mm/ui/chatting/view/MMChattingListView;Landroid/content/Context;)V
```

三个可能类名的字符串包含实测：

| 运行时类名 | `contains("ChattingListView")` | `contains("ChattingRecyclerView")` | `contains("Recycler")` |
|---|---|---|---|
| `...ScrollControlRecyclerView` | **False** | **False** | True |
| `...ScrollControlListView` | **False** | **False** | False |
| `...ui.chatting.view.l` | **False** | **False** | False |

⇒ **`chatList` 恒 `null`** ⇒ `listKind` 恒 `"none"` ⇒ `diagnose()` 走 `list=none` 分支
⇒ 报「向上 12 层没找到 ChattingListView/ChattingRecyclerView」**（一句误导性的话：
它其实找到了，只是类名不等于那两个字符串）**。

⇒ **F4 与我查出的 `F0`/`G0` 问题（§2）全都白修 —— 代码进不去。**

### 0.3 修法

**不要用运行时类名字符串做模糊匹配**。两条可用路径，按可靠性排序：

**路径 1（最硬）：不用类名，用 `MMChattingListView.getListView()`**
```
MMChattingListView;->getListView()Lu3;   acc=0x0001
  [0] iget-object v0, v1  MMChattingListView;->T:Lu3;
  [4] return-object v0
```
`T` 字段在 `l(Context)` 的 `[155] iput-object` 被赋值，两分支分别 `check-cast
ScrollControlRecyclerView` / `new-instance ui/chatting/view/l`。
⇒ **`outer` 能拿到（`MMChattingListView` 是 keep 的真名），`getListView()` 返回的对象就是列表本身。**
取到后 `as? View`（两者都 extends View）。

**路径 2（次选）：按继承链判定，不用字符串**
```kotlin
fun isA(o: Any?, fqcn: String): Boolean {
    var c: Class<*>? = o?.javaClass
    while (c != null) { if (c.name == fqcn) return true; c = c.superclass }
    return false
}
```
- `isA(chatList, "com.tencent.mm.pluginsdk.ui.tools.ChattingRecyclerView")` ⇒ recycler
- `isA(chatList, "com.tencent.mm.pluginsdk.ui.tools.ChattingListView")` ⇒ listview
- `view.l` 也在 `ChattingListView` 继承链上（→ `ScrollControlListView` → `ChattingListView`）⇒ **能覆盖**
- 都不是 ⇒ `listKind = "unknown"`，并**把 `chatList.javaClass.name` 原样打进日志**

⚠️ **可观测性要求**：无论走哪条路径，失败时**必须把实际类名写进日志**。
这次事故的直接原因就是「日志只说『没找到 ChattingListView』，没说它到底找到了什么」——
如果有类名，一眼就能看出是子类。

### 0.4 这暴露的方法论问题（比代码 bug 更重要）

**F4 的 `contains` 匹配，在「类没有被继承」的假设下是对的。** 它错在：
- 把「DEX 里存在 `ChattingRecyclerView` 这个类」直接当成「运行时对象就叫这个名」；
- 而 `MMChattingListView.l()` 明明 new 的是子类 —— **这条在 `docs/_r16_g2_msgsnap.md` §1.2b 里
  （我自己写的）就白纸黑字记着 `[56] check-cast ScrollControlRecyclerView`**。

⇒ **我写下了证据，却在写代码时没看一眼。** 这是「证据存在 ≠ 用上证据」。
V2 抓到了这一点，值得记功 —— 它是本轮唯一独立发现的 P0。

> **沉淀**：反射/匹配**运行时**对象时，**永远不要假设 `obj.javaClass.name` 等于 DEX 里那个基类名**。
> 凡是走 `new-instance` 出来的对象，都要先确认它 new 的到底是哪个类。
> 更稳的做法：**用字段/方法返回值拿对象（`getListView()`），而不是靠类名在 parent 链里筛。**

---

## 1. 结论（先说结果）

F4 里这一段（`ForceForward.kt:841-847`）：

```kotlin
val m = runCatching {
    chatList.javaClass.getMethod("getChildAdapterPosition", View::class.java)
}.getOrNull()
```

**必然返回 null**，因为当前线上微信（8.0.48 / 2588 GP）的 `androidx.recyclerview.widget.RecyclerView`
**没有** `getChildAdapterPosition` 这个方法名 —— 它在 dex 里叫 **`F0(Landroid/view/View;)I`**。

后果：
- 主路径（`posVia=childAdapterPosition`）**永远不会命中**；
- 退化路径 `A0scan` 成为唯一手段，而它自己也有问题（见 §4）；
- 若 `A0scan` 也失败，`posFail=recyclerStub` ⇒ 探针依旧失效，只是**失败信息比修之前清晰了一点**。

⇒ **F4 只修好了「报错信息」，没修好「定位能力」。P0-2 实质上未修复。**

---

## 2. 证据链（逐条可复算）

### 2.1 类路径与继承链

```
$ python tools/_r13_findclass.py Lcom/tencent/mm/pluginsdk/ui/tools/ChattingRecyclerView
DEFINED in classes4.dex
  class = Lcom/tencent/mm/pluginsdk/ui/tools/ChattingRecyclerView;
  super = Landroidx/recyclerview/widget/RecyclerView;
  access = 0x0001
  methods(33): ..., getCount, getPositionForView, m, ...
```

> 附带订正：类在 **`com.tencent.mm.pluginsdk.ui.tools`**，不是 `ui.chatting.view`
> （我第一遍按 `ui/chatting/view/ChattingRecyclerView` 查得到 `NOT-DEFINED`，
> 用二进制搜 `ChattingRecyclerView` 才定位到正确包名）。

### 2.2 `RecyclerView` 被混淆后的完整方法表（classes12.dex，160 个方法）

用 `class_defs` 遍历该类的 `class_data`，全部方法里 **名字含 `Child` / `Position` / `ViewHolder` 的只有 5 个**：

```
$ python -c "...遍历 RecyclerView 的 methods_of..."
count 160
   I0  (Landroid/view/View;)Landroidx/recyclerview/widget/c3;   0x9   ← static
   A0  (I)Landroidx/recyclerview/widget/c3;                     0x1
   B0  (IZ)Landroidx/recyclerview/widget/c3;                    0x1
   D0  (Landroidx/recyclerview/widget/c3;)I                     0x1
   getScrollingChildHelper ()Lf3/h0;                            0x2
   drawChild / getChildDrawingOrder / requestChildFocus / requestChildRectangleOnScreen / setChildDrawingOrderCallback
```

**`getChildAdapterPosition` / `getChildLayoutPosition` / `getChildViewHolder` 一个都不存在。**

### 2.3 全 dex 精确扫描（对照实验，排除「工具查不全」）

只搜方法名（`class_defs` 遍历，非字符串 grep）：

```
$ python -c "...全 15 个 dex 遍历 class_defs+methods_of，匹配 'hildAdapter'/'hildViewHolder'/'hildLayout'..."
SCAN-DONE          ← 零命中
```

```
$ python -c "...匹配 getChildPosition/getChildAdapterPosition/getChildViewHolder/
             findViewHolderForLayoutPosition/findViewHolderForAdapterPosition/getAdapterPosition..."
classes4.dex [('Lcom/tencent/mm/ui/chatting/adapter/n0;', 'getAdapterPosition', '()I'),
              ('Lcom/tencent/mm/ui/chatting/adapter/k;',  'getAdapterPosition', '()I'),
              ('Lcom/tencent/mm/ui/chatting/adapter/l0;', 'getAdapterPosition', '()I'),
              ('Lvb4/c;',                                 'getAdapterPosition', '()I')]
classes7.dex [('Lcom/tencent/mm/ui/chatting/adapter/k0;','getAdapterPosition', '()I'),
              ('Lcom/tencent/mm/ui/chatting/viewitems/f0;','getAdapterPosition', '()I')]
SCAN-DONE
```

**✅ 对照组成立**：同一套扫描逻辑能正常命中 `getAdapterPosition` ⇒ 扫描器有区分力，
`getChildAdapterPosition` 零命中是**事实**，不是工具缺陷。

### 2.4 那 6 个 `getAdapterPosition()I` 是什么

它们全在 `com.tencent.mm.ui.chatting.adapter.*` / `viewitems.*` / `vb4.c` —— 是**微信自己写的
ViewHolder 子类上的简单 getter**（`()I` 无参），**不是** `RecyclerView` 上那个 `(View)I` 的方法，
**用途完全不同**（后者是「给我这个 child View 的 adapter 位置」，前者是「我这个 ViewHolder 自己是第几个」）。
⇒ 不能拿来替代。

### 2.5 正确入口是 `F0`（=`getChildAdapterPosition`）与 `G0`（=`getChildLayoutPosition`）

`RecyclerView` 里 `(Landroid/view/View;)I` 签名的方法**只有两个**：

```
MATCH F0 (Landroid/view/View;)I  0x1
MATCH G0 (Landroid/view/View;)I  0x1
```

反汇编（两者结构对称，差在调 `c3->i()` 还是 `c3->j()`）：

```
Landroidx/recyclerview/widget/RecyclerView;->F0(Landroid/view/View;)I   acc=0x0001
  [   0] invoke-static  I0(View)  → c3
  [   4] if-eqz v1 -> abs 11
  [   6] invoke-virtual c3;->i()I        ← i() = getAdapterPosition
  [  10] goto abs 12
  [  11] const/4 v1, #-1
  [  12] return v1

Landroidx/recyclerview/widget/RecyclerView;->G0(Landroid/view/View;)I   acc=0x0001
  [   6] invoke-virtual c3;->j()I        ← j() = getLayoutPosition
```

`c3` = `RecyclerView$ViewHolder`（`acc=0x401`），其 `i()` / `j()`：

```
c3;->i()I   acc=0x0011
  [0] iget-object v0, v1, c3;->x:Landroidx/recyclerview/widget/RecyclerView;
  [2] if-nez v0 -> abs 6
  [4] const/4 v0,#-1 ; return           ← 没挂 RecyclerView ⇒ -1
  [6] invoke-virtual RecyclerView;->D0(c3)I
  [10] return v0

c3;->j()I   acc=0x0011
  [0] iget v0, v2, c3;->j:I
  [2] const/4 v1,#-1
  [3] if-ne v0, v1 -> abs 7
  [5] iget v0, v2, c3;->f:I
  [7] return v0
```

`c3;->d` = `itemView`（`RecyclerView$LayoutParams;->d` 是 `c3`，`I0(View)` = `view.getLayoutParams().d`）。

### 2.6 独立结论：`c3;->d` 确实是 itemView，`A0(i)` 返回 c3

`I0`（static）= `if (v==null) null; else ((RecyclerView.LayoutParams)v.getLayoutParams()).d`
⇒ LayoutParams 的 `d` 字段存 ViewHolder，反向即 ViewHolder 的 `d` 字段存 itemView（AndroidX 标准结构）。
已由 `A0` 的 `[41] iget-object v4, v3, c3;->d:Landroid/view/View;` 二次印证 —— `A0` 内部拿 `c3.d` 去问
`LayoutManager;->k(View)Z`（= `isViewPartiallyVisible`），**该 API 的形参就是 child View** ⇒ `c3.d` = itemView 成立。

---

## 3. 为什么这个缺陷没被 V1 / F4 发现

- **V1**：它的任务是审「`getPositionForView` 恒 -1」，它照着我给的思路验了「`u3` 接口救不了」，但**没有回头验
  `getChildAdapterPosition` 在混淆 dex 里存不存在** —— 因为它把这当成「修复建议」，不在它的审查范围里。
- **F4**：它拿到的任务描述里，我直接写了「反射 `getChildAdapterPosition(View)`（`RecyclerView` public 方法，
  `getMethod` 含继承链）」。**这句话是我的错** —— 「是 AndroidX 的 public 方法」为真（源码层面），但
  **「微信 dex 里保留了这个名字」为假**。F4 忠实地按我的错误判据实现，没能反过来质疑判据。
- **根因（值得沉淀）**：**「AndroidX 有这个 public API」≠「微信 dex 里这个名字还在」。**
  R8 会重命名**所有**非 keep 的名字，包括第三方库的 public 方法 —— 除非 keep 规则显式保留。
  微信对 `androidx.recyclerview` 显然没有全量 keep（否则 `A0/B0/I0/D0/F0/G0` 不会都被改成两字符名）。

  ⇒ **凡是反射 AndroidX / 三方库的方法名，必须先在 `_cache/dex/` 里实测该名字是否存在，不能用「它应该是 public」当判据。**
  这与本项目已沉淀的「`grep` 命中 ≠ 类定义」是同一族错误：**把源码世界的常识当成 dex 世界的事实**。

---

## 4. 附带发现：`A0scan` 退化路径的三个问题

### 4.1 `A0(I)` 形参是 **adapter position**，不是 child 索引

`A0(I)` 的完整逻辑（`classes12.dex`，`acc=0x0001`）：

```
[   0] iget-boolean v0, v5  RecyclerView;->I:Z
[   2] const/4 v1, #0
[   3] if-eqz v0 -> abs 6
[   5] return-object v1                  ← I == true ⇒ 直接返回 null
[   6] iget-object v0, v5   RecyclerView;->h:Landroidx/recyclerview/widget/j;   (ChildHelper)
[   8] invoke-virtual j;->h()I           = getChildCount()
[  13] if-ge v2, v0 -> abs 55           ← 循环：遍历所有 child
[  17] invoke-virtual j;->g(I)View       = getChildAt(i)
[  21] invoke-static I0(View) → c3
[  27] c3;->q()Z                        = isRemoved()/isInvalid()
[  33] D0(c3)I                          = getPosition(ViewHolder)
[  37] if-ne v4, v6 -> abs 52           ← 只在 position == 形参 时命中
```

⇒ `A0(pos)` 的语义是 **「给我 position=pos 的那个 ViewHolder」**（内部遍历 child 逐个比对）。

**F4 的用法**（`ForceForward.kt:855-859`）：
```kotlin
for (i in 0 until count) {          // count = getCount() = adapter itemCount
    val vh = a0.invoke(chatList, i) // A0(i)：找 position==i 的 ViewHolder
    val iv = XposedHelpers.getObjectField(vh, "d") as? View
    if (iv === itemRoot) { scanHit = i; break }
}
```

**语义上是**对的（「找 position 使 `A0(position).d == itemRoot`」），但：

- **性能**：`getCount()` 是 adapter 的 itemCount（聊天列表可能几千条），每次 `A0(i)` 内部又要遍历 ~10 个 child
  ⇒ 最坏 `几千 × 10` 次反射/方法调用，**在主线程（长按回调里）**。真机上很可能卡出 ANR 或者至少几百 ms 卡顿。
- **正确性只能靠 A0 内部的 `D0(c3)`**，而 `D0` 是 `getPosition(ViewHolder)`（会做 `isRemoved` 等修正），
  语义没问题，但**依赖 `I` 字段那个早退分支不触发**。

### 4.2 `I` 字段：`setAdapter` 时置 true，layout step3 末尾置 false

- `RecyclerView.<init>`：`[74] const/4 v14,#0` → `[77] iput-boolean v14, I` ⇒ **初值 false**
- `Y0(Z)`（`setAdapter` 内部调的那个）：`[5] const/4 v7,#1` → `[6] iput-boolean v7, I` ⇒ **置 true**
- `setAdapter`：`[94] invoke-virtual Y0(Z)` —— 看上下文 `[82] const/4 v1,#1`（传的是 `true`），
  **无条件调用**，不在任何 if 分支里。
- `j0()`（= **layout step 3**，末尾调 `LayoutManager.onLayoutCompleted`）：`[679] iput-boolean v2, I`
  且 `[14] const/4 v2,#0` ⇒ **置 false**

⇒ 推论：`A0` 在「`setAdapter` 之后、下一次 layout 完成之前」返回 null；layout 完成后恢复可用。

> ⚠️ **这一条我给的是推论，不是实锤**：`j0` 全程 700+ 条指令，`v2` 有可能在中途被改写（我只确认了
> `[14] const/4 v2,#0` 一处赋值，没有穷举全部写点）。**判死需要在真机上 `A0(0)` 实测是否为 null。**
>
> 但无论推论是否成立，§4.1 的性能问题和 §2 的名字问题都**独立成立**。

### 4.3 结论：退化路径「能跑但很脆」

- 功能上**可能**成立（只要 `I==false`）；
- 性能上**不可接受**（几千 × 10 次调用在主线程）；
- 且它是**唯一手段**（因为 §2 的主路径名字不存在）⇒ 整个 P0-2 修复的可靠性全押在这一条上。

### 4.4 ⚠️ 额外陷阱：`ChattingRecyclerView` 上另有两个「按下标取 View」的方法，**只有一个是对的**

`ChattingRecyclerView` 有 33 个方法，其中三个返回 `View`、看着都像「按下标取 item 视图」：

| 方法 | 反汇编实质 | 可用性 |
|---|---|---|
| `s(II)Landroid/view/View;` | `[0] invoke-virtual A0(I)` ← **用第 1 个形参** → `c3.d`（itemView） | ✅ **正确**：`s(pos, ?)` 拿 pos 的 itemView |
| `v(I)Landroid/view/View;` | `[0] invoke-virtual getFirstVisiblePosition()` → `[3] A0(上面那个)` → `c3.d` | ⛔ **忽略传入的形参**，永远返回**第一个可见项**的 View。**看着像 `getChildAt(i)`，实际是常量函数** |
| `K(I)Landroid/view/View;` | 遍历 `F1`/`G1` 两个 `SparseArray` 做 `findViewById` | ⛔ 是 header/footer 视图查找，与 position 无关 |
| `p(II)I` | `[0] return v2` —— 单条指令，**恒返回第 2 个形参** | ⛔ 空桩 |

> **`v(I)` 这个坑最阴**：签名 `(I)Landroid/view/View;` 与 `ListView.getChildAt(int)` 完全一致，
> 任何人照着「ListView 有 `getChildAt(i)`」的直觉去调，都会拿到**第一个可见项**而不是第 i 项，
> 而且**不会抛异常、不会返回 null**（除非列表空）⇒ 静默给错值。
>
> ⇒ **在这个类上，凡是要「按 position 取 View」，只能用 `s(pos, _)` 或直接 `A0(pos)` + `.d`。**
> `v(i)` / `p(a,b)` 一律不能用。

### 4.5 为什么会有这么多空桩？**因为这些方法在微信代码里根本没人调用**

反查调用点（`_r14_walk.py callers`，跨全部 15 个 dex）：

```
ChattingRecyclerView;->getPositionForView   → 合计调用点 0 处
ChattingRecyclerView;->s(II)View            → 合计调用点 0 处
ChattingRecyclerView;->v(I)View             → 合计调用点 0 处
ChattingRecyclerView;->K(I)View             → 合计调用点 0 处
```

**四个都是 0。** 这解释了空桩的来源：
- `ChattingRecyclerView` 是为了**兼容 `u3` 接口**（`ChattingListView` 的抽象）而写的适配层；
- 微信自己的代码**从不用这个类走 position 相关逻辑**（它自己的 RecyclerView 路径走的是别的地方）；
- 所以适配层里那些「为了满足接口而必须存在」的方法，**随便写个能编译通过的最小实现**就行了 ——
  `return -1`、`return v2`、`return A0(getFirstVisiblePosition())`。

⇒ **判据沉淀（最重要的一条）**：
**「类里有这个方法」≠「方法有实现」。** 尤其当这个方法**零调用点**时，
它极可能是「为满足接口而留的占位」。**在反射调用任何方法前，先 `callers` 反查一下有没有人用它 ——
零调用的，必须 `dis` 看实现，不能凭签名/常识推断行为。**

这条与已有的「`grep` 命中 ≠ 类定义」「AndroidX 有 API ≠ dex 里有名字」构成同一族的三个坑：
**都是把「世界应该怎样」当成「这个世界实际怎样」。**
>
> 这条和 `getPositionForView` 恒 -1、`p(II)` 恒返回第 2 参数 一样，**同属「微信为了满足某个接口
> 而留的占位实现」**。已确认该类的空桩/伪实现至少有 3 个（`getPositionForView`、`p`、`v`），
> 用之前**必须逐个反汇编确认**，不能凭签名猜。

---

## 5. 修法建议（给下一条 Pro lane）

**不要**继续用 `getChildAdapterPosition` 原名。三条可选，按优先级：

### 方案 A（推荐）：反射 `F0`，`G0` 兜底，并保留原名做版本鲁棒
```kotlin
val cands = arrayOf("F0", "G0", "getChildAdapterPosition", "getChildLayoutPosition")
// 逐个 try，第一个返回 >=0 的胜出；posVia 记录实际命中的名字
```
- `F0(View)I` = `getChildAdapterPosition` → `I0(v).i()` → `D0(c3)`（会做 `isRemoved` 等修正）
- `G0(View)I` = `getChildLayoutPosition` → `I0(v).j()` → 直接读 `c3.f`（更轻，不做修正）
- 两者都只需 **一次** 调用，**没有循环**，性能远优于现在的 `A0scan`
- 保留原名是为了版本鲁棒（若某版本没混淆原名也能中）
- ⚠️ 混淆名是**版本绑定**的：换微信版本可能变。所以 `posVia` 必须记录**实际命中的名字**，
  且要有一条「全部候选都不中」的显式失败信息（区别于「命中了但返回 -1」）

**注意**：`F0`/`G0` 是 `RecyclerView` 自己的 public 方法，`chatList.javaClass` 是子类
（`ScrollControlRecyclerView`），`getMethod` 会沿继承链找到 —— 与 `F4` 原来打的 `getChildAdapterPosition`
走同一条反射路径，**唯一区别是名字**。所以这处改动很小。

⚠️ **但有一个新的崩溃风险要处理**：`F0` 内部第一步是 `I0(View)`，而 `I0` 是
```
[ 4] View.getLayoutParams()
[ 8] check-cast → RecyclerView$LayoutParams      ← 类型不匹配会抛 ClassCastException
[10] iget-object → LayoutParams.d (ViewHolder)
```
⇒ 若传进去的 View **不是 RecyclerView 的直接 child**（`getLayoutParams()` 不是
`RecyclerView$LayoutParams`），`F0` 会抛 `ClassCastException` 而**不是返回 -1**。
`A0` 内部同样有 `I0`，所以退化路径也有这个问题。

**这个风险有多大？** 我查过了 `q0`：
```
$ python tools/_r13_findclass.py Lcom/tencent/mm/pluginsdk/ui/tools/q0
  super = Lcom/tencent/mm/pluginsdk/ui/tools/q3
  methods(5): <init>, E, getItem, getItemId, getView

$ python tools/_r13_findclass.py Lcom/tencent/mm/pluginsdk/ui/tools/q3
  super = Landroidx/recyclerview/widget/w1;      ← Adapter，不是 View
  methods(14): getItemCount, getItemViewType, onBindViewHolder, onCreateViewHolder, ...
```
⇒ **`q0` 是 Adapter 包装（`ChattingListView.setAdapter` 风格的 `ListView` adapter 桥接到
`RecyclerView.Adapter`），不会往 RecyclerView 里塞额外 View 层。** 所以「`itemRoot` 是直接 child
但 LayoutParams 类型不对」这个具体场景**概率很低**。

⚠️ **我在先前一条给 F5 的消息里说「q0 是 View wrapper、classCast 有现实可能」—— 那句话是错的，
已在此订正。** 实际概率低，但**仍建议单独分态**，理由不是「概率高」，而是：
- `runCatching` 会把任何 `invoke` 期异常都吞成 `null`，与「方法名不存在」混淆；
- 分态的成本只有几行代码，而误诊的代价是**整轮真机白跑**。

⇒ 保留「`posFail` 要为 `getMethod` 期异常与 `invoke` 期异常分开」的要求，但**不要夸大成
「有现实可能」**。这是「猜测要标明、订正要留痕」的一个典型：我先猜错，再查证，再订正。

### 方案 B：绕过 RecyclerView，直接从 ViewHolder 侧问
`I0(itemRoot)` 拿 `c3`，再调 `c3.i()`（`c3` 的 `i()`，`acc=0x0011` public final）。
- 链路：`I0`（static，`view.getLayoutParams().d`）+ `c3.i()`
- 优点：语义清楚（「这个 ViewHolder 在第几位」）
- 缺点：还是两次混淆名依赖，且 `i()` 内部照样调 `D0`，**不比方案 A 更好**，只是少一层参数校验

### 方案 C：彻底不用位置，直接从 item View 反解 msgId
项目已有结论：`viewitems/*` 的 40+ 子类里存着 h9（`viewitems/ao.g`、`bc.d`、`c6.d`、`fr.d`…）。
- 但「哪些字段、哪些子类」需要一轮完整静态取证，成本高
- **不建议在本轮做**，留作长期优化

### 无论哪个方案，都要补的三件事
1. **删掉 `A0scan` 或把它改成「只在 `F0`/`G0` 全失败时才跑，且加 `count` 上限」**
   —— 现在它是「几千 × 10 次反射跑在主线程」，真机有卡顿/ANR 风险。
2. `posFail` 的取值要能区分三种情况（现在混成一个 `recyclerStub`，会重演「伪装成 adapter 链不通」）：
   - `noMethod`：所有候选方法都不存在
   - `itemRootNotFound`：找不到 `parent === chatList` 的节点
   - `returnedMinusOne`：方法存在且被调用，但返回 -1
3. **`posVia` 记录实际命中的混淆名**（`F0` / `G0` / 原名），否则下次换版本无从判断。

---

## 6. 这次事故的教训（写进轮末自审）

1. **我给出的判据错了，子代理照做，双层都没拦住。** 我在派 F4 时把「AndroidX public 方法」当成了
   「dex 里名字可用」。F4 忠实执行，没有反向质疑判据 —— 而它按 §四② 是 Pro 档，**应该有质疑判据的义务**。
   ⇒ 下轮派 Pro lane 时，prompt 里要加一条：**「给你的判据若有任何一条你无法在本地证据里复现，必须先报『判据存疑』再动手」**。
2. **V2 复核能不能抓到这条，是一次真实的能力检验。** 如果 V2 也没抓到，说明「复核 lane」的价值被高估，
   以后关键路径必须**主会话自己做一遍独立复算**（这次就是靠这个抓到的）。
3. **「修好了报错信息 ≠ 修好了功能」**：F4 让 `pos=-1` 变得有解释（`posFail=recyclerStub`），
   但**定位能力一点没恢复**。评审时要盯「能力是否恢复」，不是「日志是否更好看」。
