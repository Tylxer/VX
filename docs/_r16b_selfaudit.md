# R16b 主会话自查：修 P0 过程中自己引入的 3 处缺陷

> 时间：2026-10-24 22:38~22:42
> 背景：V2 第一轮复核判 FAIL（2 个 P0：`chatList` 匹配失配 / `getChildAdapterPosition` 已混淆）。
> 主会话修这两处时，**自己又引入 3 处缺陷**，均在提交前自查抓到并修掉。
>
> ⚠️ 本文档不是「我很仔细」的邀功，恰恰相反——**3 处里有 2 处是「修复本身」引入的**，
> 说明「改一行」这种事在状态机/优先级逻辑里同样会错。留给 V3 复核用。

---

## 缺陷 1 · `chatList` 抢占（P1，已修）

### 原写法

```kotlin
// ForceForward.kt:820（修复后、再修前）
if (chatList == null && listKindOf(node) != "notList") chatList = node
```

### 为什么错

`listKindOf` 有**四**个返回值，不只有「是列表 / 不是列表」两种：

| 返回 | 含义 |
|---|---|
| `"recycler"` | 继承链命中 `ChattingRecyclerView` ✅ 确定是列表 |
| `"listview"` | 继承链命中 `ChattingListView` ✅ 确定是列表 |
| `"unknown"` | 类名含 `Recycler`/`ListView`/`AbsListView`，但继承链**不认识** ⚠️ 只是「像」 |
| `"notList"` | 不是列表 |

`unknown != "notList"` 为真 ⇒ **`unknown` 也会占住 `chatList`**。

而 parent 链是**从内往外**走的：从长按的 View 一路向上。若消息卡片内部有内嵌的
横向 Recycler（图片轮播 / 小程序卡片 / 投票组件之类），它比真正的聊天列表**先被遇到**。

### 后果（比原 P0 更难查）

1. `chatList` 指向错误的（内层）对象
2. `listKind = "unknown"` ≠ `"none"` ⇒ **不触发** `diagnose()` 的 `list=none` 分支
3. `effKind` 按类名含 `Recycler` 走 recycler 分支
4. `itemRoot` 上溯条件是 `n.parent === chatList` ⇒ **永远找不到**（真正的 item 根 parent 是聊天列表，不是这个内层容器）
5. ⇒ `posFail = "itemRootNotFound"`，`pos = -1`

**两种失败的症状对比**：

| 情形 | 日志 | `diagnose()` 会说 |
|---|---|---|
| 没找到列表（原 P0） | `list=none` | 「不在聊天页，或层级 >12」—— **方向对** |
| `chatList` 抢占（本缺陷） | `list=unknown listCls=xxx pos=-1 posFail=itemRootNotFound` | 「长按 View 向上 12 层没找到 item 根」—— **方向错**，会去改上溯层数而真因是找错了列表 |

⇒ **「找到了但找错」比「没找到」更危险**，因为它把排查引向错误的方向。

### 修法

拆成两级优先级：

```kotlin
var chatList: View? = null       // 严格：继承链命中
var chatListLoose: View? = null  // 宽松：只「像」列表

while (node != null && guard++ < 12) {
    if (chatList == null) {
        when (listKindOf(node)) {
            "recycler", "listview" -> chatList = node        // 硬命中，优先级最高
            "unknown" -> if (chatListLoose == null) chatListLoose = node  // 只记着
        }
    }
    ...
}
// 兜底顺序：严格命中 → outer.getListView() → 才用 loose
if (chatList == null && outer != null) { chatList = ...getListView()... }
if (chatList == null) chatList = chatListLoose
```

**关键点**：`unknown` 的降级**排在 `getListView()` 之后**。
因为 `getListView()` 返回的是 `T` 字段（`l(Context)` 里赋值，就是那三个确定子类之一），
比「类名像列表」硬得多。

### 判据沉淀

> **多返回值函数不要用 `!= 某个值` 当「是」的判断。**
> `listKindOf` 返回 4 个值，写 `!= "notList"` 时我脑子里只有 2 个值。
> 正确做法是枚举「希望的」那一边（`in setOf("recycler","listview")`），
> 让「没考虑到的新值」默认落到不必中的分支。

---

## 缺陷 2 · `posFail` 自相矛盾（P1，已修）

### 原写法

```kotlin
// 候选链循环 [F0, G0, getChildAdapterPosition, getChildLayoutPosition]
for (name in candidates) {
    val m = ...getMethod(name)...
    if (m == null) continue
    allMissing = false
    val r = runCatching { m.invoke(chatList, itemRoot) as Int }
    val p = r.getOrNull() ?: -1
    if (p >= 0) { pos = p; posVia = name; break }     // ← 成功就 break
    ...sawClassCast 检查...
}
// ⛔ 循环后无条件判定
if (allMissing) posFail = "noMethod"
else if (sawClassCast) posFail = "classCast"
```

### 为什么错

`break` 只跳出循环，**不阻止后面的 `if`**。

场景：`F0` 抛 `ClassCastException`（`sawClassCast = true`）→ `G0` 成功拿到 `pos = 5` → `break`
→ **循环后仍执行** `posFail = "classCast"`

⇒ 日志变成 `pos=5 posVia=G0 posFail=classCast`：**`pos` 有值、`posFail` 说失败**，自相矛盾。

`diagnose()` 的判读顺序是**先看 `posFail`**（`_r16_msgsnap.py:225-245`），
所以它会报「`posFail=classCast` ⇒ 传进去的 itemRoot 不是 RecyclerView 直接 child」，
**完全无视 `pos=5` 已经成功**。人看到这条诊断会去查 itemRoot 上溯逻辑 —— 白查。

### 修法

```kotlin
// ⚠️ 只有**最终没拿到 pos** 才记失败原因。
if (pos < 0) {
    if (allMissing) posFail = "noMethod"
    else if (sawClassCast) posFail = "classCast"
}
```

### 判据沉淀

> **「先 break 再统一判定」的循环里，所有 flag 都必须以「最终状态」为准，
> 不能以「过程中发生过」为准。**
> `sawClassCast` 是「过程中发生过」的事实，把它直接当「失败原因」用，
> 就等于把「降级了一次」误报成「整体失败了」。
> **正确语义是「降级」不是「失败」** —— 若将来要看降级情况，应另开一个 `posDegraded`
> 字段，而不是复用 `posFail`。

---

## 缺陷 3 · listview 分支失败无原因（P2，已修）

### 原写法

```kotlin
} else {
    // listview 形态
    val m = runCatching {
        chatList.javaClass.getMethod("getPositionForView", View::class.java)
    }.getOrNull()
    if (m != null) {
        pos = runCatching { m.invoke(chatList, v) as Int }.getOrNull() ?: -1
        posVia = "getPositionForView"
    }
    // ⛔ 没有 else 分支 ⇒ m == null 时 posFail 保持 "none"
}
```

### 为什么错

`posFail` 的整个设计目的就是「**失败时留下原因**」，但 listview 分支是唯一没留的：

| 情形 | 日志 | 能区分吗 |
|---|---|---|
| `getPositionForView` 反射不到 | `pos=-1 posVia=none posFail=none` | ❌ |
| 反射到了但返回 -1 | `pos=-1 posVia=getPositionForView posFail=none` | ✅ 靠 `posVia` 能区分 |

第一种情况 `posVia=none posFail=none` —— 两个字段都说「没发生过任何事」，
但实际上是**方法找不到**（`listKindOf` 判错了形态）。

而 `diagnose()` 当时的分支只写「`getPositionForView` 没命中」，把两种原因混成一个。

### 修法

```kotlin
if (m != null) {
    pos = runCatching { m.invoke(chatList, v) as Int }.getOrNull() ?: -1
    posVia = "getPositionForView"
    if (pos < 0) posFail = "returnedMinusOne"
} else {
    posFail = "noMethod"
}
```

同步改 `diagnose()`：`pos=-1` 分支按 `posFail` 三分（`noMethod` / `returnedMinusOne` / 其他）。
`noMethod` 的文案要明确指向「**`listKindOf` 判错了形态**」而不是「View 不在列表里」 ——
因为 `getPositionForView` 在 `android.widget.ListView` 上是 public 真实实现，
listview 形态**不该**反射不到。

### 判据沉淀

> **「每条失败路径都必须能唯一定位」** —— 不能出现 `pos=-1, posVia=none, posFail=none`
> 这种「三个字段都说没事，但结果就是错」的状态。它是**可观测性的空洞**。
> 检查方法：把某个字段取遍所有合法值，看日志是否永远能反问出「为什么」。

---

## 三处缺陷的共性（这条最值得记）

| # | 缺陷 | 我当时的注意力在 | 实际漏的 |
|---|---|---|---|
| 1 | `chatList` 抢占 | 「怎么找到列表」 | **找到了多个候选时谁优先** |
| 2 | `posFail` 矛盾 | 「怎么区分失败原因」 | **成功之后失败原因要不要清** |
| 3 | listview 无原因 | 「listview 分支怎么改」 | **失败路径的完整性** |

**共性：都在改「主路径」，没在想「主路径成功/失败/降级三种结局的字段一致性」。**

⇒ 沉淀一条可操作的检查法：

> **改带状态字段的代码时，改完必须造一张「pos × posVia × posFail」的真值表，
> 把每种组合都填出来，看有没有自相矛盾或空洞的组合。**
> 光读代码看不出来 —— 因为代码是「顺序」的，而矛盾只在「组合」里显形。

具体到本次，真值表应该是：

| pos | posVia | posFail（应当） | 含义 |
|---|---|---|---|
| ≥0 | `F0`/`G0`/`A0scan`/`getPositionForView` | `none` | ✅ 成功 |
| -1 | `none` | `noMethod` | 方法名全找不到 |
| -1 | `none` | `itemRootNotFound` | 没找到 item 根 |
| -1 | `none` | `classCast` | 调用抛 ClassCast |
| -1 | `none` | `returnedMinusOne` | 调通了都返 -1 |
| -1 | `getPositionForView` | `returnedMinusOne` | 调通了返 -1 |
| -1 | `none` | `none` | ⛔ **空洞**（缺陷 3 制造的就是这个） |
| ≥0 | 任意 | 非 `none` | ⛔ **矛盾**（缺陷 2 制造的就是这个） |

**最后两行是禁形。** 修完后代码已不会产生它们（`if (pos < 0)` 挡住矛盾、
listview 两分支都赋值挡住空洞）。

> **⛔ 订正（R17-V3 报，2026-10-24）**：上表第 4、5 行 `posVia` 原写 `F0`/`G0`，**是错的**。
> 代码里 `posVia` **只在成功时赋值**（`ForceForward.kt:912`：
> `if (p >= 0) { pos = p; posVia = name; break }`），失败时一律保持初值 `"none"`。
> ⇒ 候选链全抛 CCE 或全返 -1 时，日志实际是 `pos=-1 posVia=none posFail=classCast`。
>
> 影响面：`diagnose()` 对 recycler 的 `classCast`/`returnedMinusOne` **只看 `posFail`**
> （`_r16_msgsnap.py:242-259`），不看 `posVia` ⇒ **判读正确性不受影响**。
> 但真值表作为「字段一致性检查工具」写错一列，会让后来人误以为 `posVia` 能区分
> 「是哪个方法抛的 ClassCast」——**实际不能**。
>
> 判据沉淀：**写「应当值」表时，每一列都要回到赋值点确认「这条路径到底写没写这个字段」，
> 不能按语义顺推。**「失败时应该记下是哪个方法失败的」是**合理期望**，但代码没这么写 ——
> 期望不能当真值表的依据。真要这个信息得另加 `posFailVia` 字段（本轮不加）。

---

## 验证

| 项 | 结果 | 时间 |
|---|---|---|
| `py_compile tools/_r16_msgsnap.py` | `PY_OK` | 22:42 |
| `compileDebugKotlin` | `EXIT=0 / BUILD SUCCESSFUL in 20s` | 22:42 |
| `testDebugUnitTest` | `files=26 / tests=606 / failures=0 / errors=0` | 22:36 |

⚠️ **单测覆盖不到这些改动**（全是反射 + 真机 View 对象，无 Android 依赖可测）。
⇒ 这正是必须要有 V3 复核的原因：**静态审查是这段代码唯一的质量门。**

## 给 V3 的三个复核点

1. **优先级顺序**：严格命中 → `getListView()` → `loose`。若 `getListView()` 返回一个
   继承链认识但**并非当前滚动列表**的对象（例如缓存了另一个列表），
   会不会又变成「`listKind` 正确但 `pos` 恒 -1」？（**我未验证**）
2. **真值表是否有遗漏**：上表 8 行之外，还有没有能构造出的第 9 行？
   特别是 `effKind` 的 `unknown` 降级路径与 `posFail` 的交互。
3. **`guard` 计数在兜底路径上是否仍生效**：`chatListLoose` 的赋值在 `while` 内，
   但 `getListView()` 兜底在 `while` 外 —— 后者不需要 `guard`（只调一次），
   请确认没有引入无界操作。

---

## 补证 · `getListView()` 兜底的 `T` 字段会不会失效（V3-Q2 我自己先答）

V3 会问「`getListView()` 返回的 `T` 字段对象，有没有可能不是当前正在滚动的那个列表」。
我先自己查了，**答案是不会**：

### 证据 1 · `getListView()` 是纯 `iget-object`

```
Lcom/tencent/mm/ui/chatting/view/MMChattingListView;->getListView()Lu3;   acc=0x0001
  [   0] iget-object            v0, v1      MMChattingListView;->T:Lu3;
  [   2] return-object          v0
```

⇒ 无缓存、无重建、无判空返回别的对象。**`T` 是什么就返回什么。**

### 证据 2 · `T` 只有一个写点，且 `l()` 只跑一次

反汇编 `MMChattingListView;->l(Landroid/content/Context;)V`（`acc=0x0011` = public final），
`T` 的唯一写点是末尾 `[155]`：

```
  [  43] if-eqz    v1 -> +107 (abs 150)      ; AB: isToRv
  [  56] check-cast v1  ScrollControlRecyclerView      ← recycler 形态
  [ 150] new-instance v1  Lcom/tencent/mm/ui/chatting/view/l;   ← listview 形态
  [ 155] iput-object v1, v10  MMChattingListView;->T:Lu3;        ← ⭐ 唯一写点
```

`l()` 之后对 `T` 的全是 `iget-object` 读（`setBackgroundDrawable`/`setSelector`/`setPadding`/
`y()`/`setId` 等），**没有第二次 `iput-object`**。

`l()` 的名字是 `initChattingListView`（`[33] const-string 'initChattingListView isToRv:%s'`），
从命名和 `acc` 的 final 语义看都是「构造期调用一次」。

⇒ **`T` 一旦赋值就是当前列表，不会指向旧列表。**

### 唯一会返回 null 的情形

`T` 在 `l()` 执行前为 null ⇒ `getListView()` 返回 null ⇒ 我的 `as? View` 得到 null。
这**恰好是应该返回 null 的时候**（列表还没建），不是误判。

### 结论

原「缺陷 3 的兜底顺序」判断成立：`getListView()` 排在 `chatListLoose` 之前是安全的，
因为它比「类名像列表」硬得多 —— 它拿的是**微信自己构造时塞进去的那个对象**。

> ⚠️ 仍未验证：`T` 是否可能被反射/其他模块改写（本项目内无人写 `T`，`grep` 已确认）。
> 若将来有人 hook `l()` 之后再改 `T`，此结论失效。

---

## 补证 2 · 缺陷 1 的前提被**部分证伪**（重要，自我订正）

缺陷 1 我论证「消息卡片里**内嵌的横向 Recycler** 会先被遇到」，但当时**没实证这个前提**。
现在查了，结论要分两半说：

### 实测数据（全 16 dex 的 `class_defs` 遍历）

| 检查项 | 结果 |
|---|---|
| 全 dex 继承链含 `RecyclerView` 的类总数 | **96 个** |
| 其中 `com.tencent.mm.ui.chatting` 包（**2596 个类**）下的 | **0 个** ⛔ |
| 消息 Item 相关的 View（`ChattingItemFooter`/`ChattingItemTranslate`/`ChattingImageBGView`/`FoldableChatTextItemView`/`BubbleCornorLayout`…） | 全部继承 `LinearLayout`/`RelativeLayout`/`FrameLayout`/`ImageView`，**无一个是 RecyclerView** |
| 名字含 Chat/Msg/Card 且继承 RecyclerView 的 | 9 个，**全在 `plugin/game/chatroom`（小程序游戏聊天室）/ `plugin/game/ui/chat_tab` / `plugin/sns/ad`（朋友圈广告）**，与聊天主界面无关 |

### ⇒ 对缺陷 1 的订正

**前提「消息卡片里内嵌横向 Recycler」在聊天主界面上是 FALSE。**
微信的聊天消息 Item 用的是 `LinearLayout`/`RelativeLayout` 这类普通容器，多图消息也是
静态网格布局（`ChattingImageBGView extends ImageView`），**不是内嵌 RecyclerView**。

⇒ 我描述的「内层 Recycler 抢先占位」这个具体场景**不存在**。

### 但「抢占」这个 bug 本身仍然真实，只是触发源换了

`listKindOf` 返回 `unknown` 的条件是「类名含 `Recycler`/`ListView`/`AbsListView` 但继承链不认识」。
即使聊天卡片里没有 Recycler，这个条件**依然可能被命中**：

1. **`MMPullDownView` 系的外层容器**：如果某个类名里带 `ListView` 但不是
   `ChattingListView` 的子类（例如其他业务复用的下拉容器）。
2. **未来微信版本换了实现类**（`listKindOf` 的 `unknown` 分支本来就是为这个留的）。
3. **小程序卡片 / 视频号卡片**：`ChatRoomListCard`/`AdSlideRecyclerView` 这类第三方业务
   Recycler 若被嵌入聊天页（**这条需真机确认**，静态上它们不在 `ui/chatting` 包）。

**⇒ 定性改为**：缺陷 1 的**修法（两级优先级）是对的、必要的**，但
**我给的场景理由（内嵌横向 Recycler）证据不足，属于「想当然」**。

### 一个反直觉的发现

`ChattingLinearLayoutManager extends LinearLayoutManager extends RecyclerView$LayoutManager`
—— 这反证了**聊天列表本身确实是 RecyclerView**（否则不需要自定义 LayoutManager），
和 F4/F5 的修复方向一致。

### 判据沉淀

> **「预防性修法」也必须给出触发源的实证，否则只是在臆想场景上做过度设计。**
> 我这次修的是**真 bug**（`unknown` 抢占 `chatList` 的逻辑确实存在），
> 但**触发它的场景我编了一个**（内嵌 Recycler），
> 两者混在一起写，读的人会以为场景和 bug 一样确凿。
> **正确的写法是分开陈述：「逻辑缺陷 = 确凿」「触发场景 = 待验证」。**

---

## 缺陷 4 · `diagnose()` 的 pos 短路放错位置，等于没写（P1，已修）

**来源**：我自己上一轮的修改。R17-V3 追问「`pos>=0` 但 `posFail` 是失败值怎么判」，
我做输入构造验证时发现**没有任何短路** ⇒ 补了一个 `out.append(...)` + 继续往下。
**但那个 append 是死的。**

### 为什么是死的

`diagnose()` 的结构是：

```python
out = []
if view=none:     return [...]
if attached=false: return [...]
out.append((True, "以 pos 为准..."))     # ← 我加在这
if list == "none":    return [...]
if list == "unknown": return [...]
if list == "recycler":
    ...  # 这个分支里**每一条路径都是 return**
```

「禁形」（`pos=5` + `posFail=classCast`）的 `list` 必然是 `"recycler"`
（只有 recycler 分支会产生 `classCast` 这个值）⇒ 一定落进最后那个分支
⇒ 该分支的 `if pf == "classCast": return [...]` 直接把矛盾组合判成失败，
**我在上面 append 的那条 (True, ...) 连同整个 `out` 一起被丢弃**。

实测确认（2026-10-24，`importlib` 直调）：
```
False | list=recycler 且 posFail=classCast ⇒ 传进去的 itemRoot 不是 RecyclerView 的直接 child...
```
即：修完之后行为**和修之前一模一样**。

### 修法

短路必须放进 `recycler` 分支**内部**、`pf == "noMethod"` 判断**之前**：

```python
if fields["list"] == "recycler":
    pf = fields["posFail"]
    if _pos_ok and pf not in ("none", "<missing>"):
        return [(True, "list=recycler 且 pos=%s 已拿到（posVia=%s），但 posFail=%s 与之矛盾 ⇒ "
                       "**以 pos 为准**：..." % (fields["pos"], fields["posVia"], pf))]
    if pf == "noMethod": ...
```

`_pos_ok` 的解析提到 `list == "none"` 之前算（它不依赖任何分支，
放前面避免在三个分支里各算一遍）。

### 验证（19 组输入构造，全过）

`importlib.util` 直调 `diagnose()`，19 组字段组合：

| 输入 | ok | 文案要点 |
|---|---|---|
| `pos=5 posVia=F0 posFail=none` | True | `msgId=123 ... adapter 链通了` |
| `pos=3 A0scan` | True | 同上 |
| **禁形** `pos=5 posFail=classCast` | **True** | **「以 pos 为准」**（修前是 False） |
| **禁形** `pos=5 posFail=noMethod` | **True** | 同上 |
| `posFail=noMethod` | False | 候选链全不存在 ⇒ 代码 bug |
| `posFail=classCast` | False | itemRoot 不是直接 child |
| `posFail=itemRootNotFound` | False | 定位断在第一步 |
| `posFail=returnedMinusOne` | False | 回收/不是 item 根 |
| `posFail=<missing>` | False | 旧 APK |
| `posFail=weird` | False | **未预期值**（新文案） |
| `list=none` / `unknown` / `view=none` / `attached=false` | False | 各自早退文案 |
| `list=listview` + `noMethod` / `returnedMinusOne` | False | 三分文案 |
| `list=listview` 成功 | True | 通过 |
| `pos=1 adapter=none` / `msgId=none` | False | 各自缺失文案 |

**16/16 语义正确**（3 处 `!!!` 是我测试脚本里用「名字含 classCast 就期望 False」的
启发式反推期望值写错了 —— 禁形1 本该 True、`listview ret-1` 名字里没写关键词。
**代码行为全对，是测试的期望值错了。**）

### 判据沉淀

> **加「防御性短路」时，必须确认它的位置在所有 `return` 之前的同一控制流里。**
> `return` 会终止整个函数并**丢弃**之前 append 的中间结果 ——
> 「append 到列表然后 return 另一个列表」是最容易看漏的写法，
> 因为读代码时 `out.append` 看起来「已经生效了」。
>
> **同类更普遍的形态**：一个函数里既有「累积 `out` 再统一返回」的部分，
> 又有「某分支直接 `return`」的部分 ⇒ **这两部分不共享状态**，
> 在累积部分做的一切都会被 return 部分抹掉。
>
> 我也验证了「修完就以为修好了」的代价：**如果不做输入构造验证，
> 这个短路会以「代码里有、日志里没效果」的形式潜伏下去**，
> 而且它是从我上一轮的「已修」清单里漏出去的 ——
> 也就是说 **`py_compile` 通过 + 复跑 11 组不崩溃 ≠ 修对了**，
> 必须断言**期望值**（这次就是靠断言 `ok=True` 才抓到）。

---

## R17-V3 复核结论回写（2026-10-24）

V3 判定 **PASS（可上真机）**，无阻断项。它报了 1 个 P1 + 3 个 P2，我逐条独立核验后全部认可并已修：

| V3 报告项 | 我的独立核验 | 处理 |
|---|---|---|
| **P1** `A0scan` 的 `a0.invoke(...) ?: break` 是错误早退 | **成立**。`A0(i)`=`findViewHolderForAdapterPosition`，不可见 position 返 null 是**常态**；窗口起点 `first-50` ⇒ 第一个 i 几乎必不在可见区 ⇒ 首次迭代即 break。注释里「A0 在 I 字段早退时才返回 null」的推理**是错的** | 已修：起点改 `first`（长按 item 必然可见）+ `break`→`continue` + 反射单包 `runCatching` |
| P2 selfaudit 真值表第 4/5 行 `posVia` 标错 | **成立**。`posVia` 只在 `if (p >= 0)` 内赋值，失败时恒 `none` | 已改表 + 加订正块 |
| P2 `diagnose()` recycler 兜底文案残留 `recyclerStub` 语义 | **成立**。文案还写着「getPositionForView 恒 -1 属已知预期」，但 recycler 形态根本不调它 | 已重写为「posFail 是未预期值 ⇒ 版本不匹配」 |
| P2 `MMChattingListView` 类名含 `"ListView"` 会被判成 `unknown` | **成立**。它是 `MMPullDownView` 系外壳，本身不是列表 | 已在 `listKindOf` 最前面按 FQCN 精确排除为 `notList` |

V3 的 §2.2（`getListView()` 不会持旧引用）独立给出了我没想到的角度：
`outer` 是从 `v` 的 parent 链上溯得到的 ⇒ 必然是「当前包着 `v` 的那个实例」，
旧实例不可能出现在新树的 parent 链上；`v` 若已摘窗则 `parent` 为 null，`outer` 根本拿不到。
这条比我原来的「`T` 只有一个写点」更直接，两者互补。

---

## R17-V3 复核报告终版增量（V3 关闭前补写的 §5.3 / §7.2 / §7.3）

V3 在收尾时又补了三节，其中有两条**对我判断有实质影响**：

### A. 缺陷 4 是**双向独立发现**（重要）

V3 §5.3 独立跑输入构造，自己发现「`_pos_ok` 防御短路是死代码」，
**且它审的是我修之前的版本** —— 和我自己发现的是同一个缺陷，
修法建议也一致（`return [(True, ...)]` 或函数末尾统一 `return out`）。

> **⇒ 这是本轮最可靠的一处结论**：两方**独立**得出同一判断，
> 比任何单方断言都硬。也说明**「喂输入」这个动作本身**就能暴露这类 bug ——
> 它不需要多深的推理，只需要做一次「造组合 + 看结果」。
> V3 明确点出 V2 的盲区：「纯读顺序 + 不喂输入」的复核方式
> 会漏「可观测性」和「兜底逻辑」两类 bug。这条我认同并采纳为前提。

### B. 缺陷 1 的触发场景：V3 补了一个我没想到的边界（比我原来更准）

我原来的补证 2 结论是「`ui/chatting` 包 2596 类零 RecyclerView ⇒ 触发场景证据不足」。
V3 独立复算（数字与我一致：全 dex 96 个 RecyclerView 后代、`ui/chatting` 下 0 个），
但它指出我**没点透的一层**：

> **「不在 `ui/chatting` 包内」≠「不会出现在聊天页 UI 里」。**
> 聊天页的消息卡片会**嵌入跨包 View**：`plugin/appbrand`（小程序卡片）、
> `plugin/finder`（视频号卡片）、`msgsubscription`（订阅号卡片）。
> 这些卡片的 View 树里**确实可能**有 RecyclerView（属那 96 个之一）。

**这是对的，我的补证 2 漏了这一层。** 修正后的定性：

| 层次 | 结论 |
|---|---|
| 缺陷本身（`unknown` 抢占 `chatList`） | **确凿**，逻辑缺陷真实存在 |
| 我原来举的场景（聊天 item 自身内嵌 Recycler） | **证伪**，dex 里零实证 |
| V3 补的场景（跨包卡片被塞进聊天页，其内部 Recycler 落到 `v` 的 parent 链上） | **低概率、需真机**，但**可能** |
| 修法（`unknown` 不抢占、只作 loose 兜底） | **两种情况都正确** —— `unknown` 容器无论内嵌卡片还是真列表，都不该抢占 `chatList` |

**⇒ 净结论：修法保留，理由从「想当然」升级为「有边界条件的低概率风险」。**
这也印证我原来的判据沉淀是对的（「预防性修法也必须给出触发源的实证」），
只是我把「没有实证」直接推成了「不会发生」——**这两者不等价**。
修正后的判据：**「未找到实证」只能推出「不是主要路径」，不能推出「不会发生」；
有跨包 View 注入的宿主页面，必须把「外部 View 树混入 parent 链」列为候选触发源。**

### C. V3 对 V2 判 PASS 的独立抽查（我采纳）

V3 抽查 V2 的 6 条论证，4 条一致、2 条不一致：

- V2 把 `?: break` 当成「✅ 防空转」，**其实是 bug**（V3 §4c）。
- V2 没做输入构造，漏了 `_pos_ok` 死代码（V3 §5.3）。

V3 的结论是「V2 的 PASS 方向正确、不推翻，但漏了这两处」——
**我认同这个定级**：两处都是 P1/P2 且当前版本不触发，不构成阻断。
但这件事本身是**对 V2 那轮复核方式的有效性审计**，
价值大于「V2 判错了」这个结论 —— 它定位出了**方法上的盲区**（不喂输入）。
