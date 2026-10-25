# R17-V3 复核：R16b 反射链路修复（listKindOf / chatList 两级兜底 / 候选链 / A0scan / diagnose）

> 复核子代理 V3 | 只读代码 + DEX 缓存 + 结论落盘。未修改任何源码/测试/脚本。
> 时间：2026-10-24
> 对象：`ForceForward.kt` 的 `msgsnapReport()` / `listKindOf()` + `tools/_r16_msgsnap.py` 的 `diagnose()`

---

## 0. 结论摘要

**判定：PASS（可上真机），但带 1 个 P1 + 3 个 P2（均不阻塞）**。

- 两个 P0（`chatList` 失配 / `getChildAdapterPosition` 混淆）**确已修复**，`listKindOf` 继承链遍历正确，`F0`/`G0` 候选链正确，两级兜底优先级顺序正确。
- 主会话自查的 3 处缺陷（抢占 / posFail 矛盾 / listview 无原因）**均已修对**，且我没抓到新的自相矛盾组合（无第 9 行）。
- **P1（不阻塞当前上机）**：`A0scan` 降级路径里的 `a0.invoke(chatList, i) ?: break` 是**错的早退**。窗口从 `first-50` 开始，第一个 position 几乎必然不在可见区 → `A0` 返回 null → 立即 break，扫描永远扫不到真正可见的长按 item。当前版本 `F0`/`G0` 存在、不触发 `noMethod`→`A0scan`，故**不影响本次上机**，但它是「未来版本混淆名再变」时本应兜底却会失效的潜伏 bug。见 §4c。
- **P2-1**：`diagnose()` 的 `_pos_ok` 防御短路是**死代码**（`out` 变量从未被 return，`pos=5 posFail=classCast` 这类禁形组合仍被误判）。见 §5.3。
- **P2-2**：selfaudit 真值表第 4/5 行 `posVia` 列标错（应为 `none`，代码只在成功时赋值 posVia）。见 §3.2。
- **P2-3**：`listKindOf` 的 `nm.contains("ListView")` 会把 `MMChattingListView` 自己判成 `unknown`。见 §2.3。
- 对 selfaudit「缺陷 1 触发前提」的独立核验：**「消息卡片内嵌横向 RecyclerView」在当前 dex 里找不到实证**（`ui/chatting.viewitems.*` 与 `ui/chatting.**` 内继承 RecyclerView 的类均为 0 个），team-lead 的补证方向正确，我补上「跨包卡片 View 会进聊天页」这个边界。见 §7.2。

---

## 1. Q1 · listKindOf 继承链遍历是否正确

**结论：正确，PASS。**

代码（`ForceForward.kt:1054-1071`）：

```kotlin
private fun listKindOf(view: View?): String {
    if (view == null) return "notList"
    var c: Class<*>? = view.javaClass
    while (c != null) {
        when (c.name) {
            "com.tencent.mm.pluginsdk.ui.tools.ChattingRecyclerView" -> return "recycler"
            "com.tencent.mm.pluginsdk.ui.tools.ChattingListView" -> return "listview"
        }
        c = c.superclass
    }
    val nm = view.javaClass.name
    return when {
        nm.contains("Recycler") -> "unknown"
        nm.contains("ListView") || nm.contains("AbsListView") -> "unknown"
        else -> "notList"
    }
}
```

对 `com.tencent.mm.ui.chatting.view.l`（隔两层）逐级走查，我独立用 `tools/_r13_findclass.py` 重取继承链：

```
$ python tools/_r13_findclass.py Lcom/tencent/mm/ui/chatting/view/l
  super = Lcom/tencent/mm/pluginsdk/ui/tools/ScrollControlListView;
$ python tools/_r13_findclass.py Lcom/tencent/mm/pluginsdk/ui/tools/ScrollControlListView
  super = Lcom/tencent/mm/pluginsdk/ui/tools/ChattingListView;
$ python tools/_r13_findclass.py Lcom/tencent/mm/pluginsdk/ui/tools/ChattingListView
  super = Landroid/widget/ListView;
```

循环走查：`c=view.l`（name=`com.tencent.mm.ui.chatting.view.l`，when 不中）→ `c.superclass`=`ScrollControlListView`（不中）→ `c.superclass`=`ChattingListView`（**命中 `return "listview"`**）。

- **不跳层**：每轮 `c = c.superclass` 只上一级，无 `superclass.superclass` 之类跳步。
- **无错误 break**：`when` 命中即 `return`，这是「找到即返回」，语义正确；未命中才继续。
- **无 NPE**：`while (c != null)` 是终止条件；`Object.superclass` 返回 null，循环在 `c=Object` 之后把 `c` 置 null 退出，**不会在 null 上调用 `.superclass`**。
- **FQCN 正确**：两个 `when` 分支的 FQCN 经 `_r13_findclass.py` 验证均为 classes4.dex 真实定义（`com.tencent.mm.pluginsdk.ui.tools.ChattingRecyclerView` / `...ChattingListView`），包名无笔误。

---

## 2. Q2 · chatList 两级优先级顺序是否还有漏洞

**结论：顺序正确；`getListView()` 不会持有「旧引用」，不存在「listKind 正确但 pos 恒 -1」的漏洞。PASS。**

### 2.1 优先级顺序本身

`ForceForward.kt:838-862`：

```kotlin
if (chatList == null) {
    when (listKindOf(node)) {
        "recycler", "listview" -> chatList = node
        "unknown" -> if (chatListLoose == null) chatListLoose = node
    }
}
...
if (chatList == null && outer != null) {
    chatList = runCatching {
        outer.javaClass.getMethod("getListView").invoke(outer) as? View
    }.getOrNull()
}
if (chatList == null) chatList = chatListLoose
```

顺序：严格命中 → `getListView()` → `loose`。与 selfaudit 描述一致，`unknown`（loose）确实排在 `getListView()` 之后，抢占缺陷已消除。

### 2.2 `getListView()` 是否可能返回「非当前滚动列表」

我独立反汇编 `MMChattingListView.getListView()`（classes4.dex `code_off=0x5194ac`）：

```
=== getListView (insns_size=3) ===
  [  0] iget-object Lcom/tencent/mm/ui/chatting/view/MMChattingListView; Lcom/tencent/mm/pluginsdk/ui/tools/u3; T
  [  2] return-object v0
```

**是纯 `iget-object T` + `return`，无任何缓存/重建逻辑**。它返回的就是 `outer` 实例的 `T` 字段对象。

关键推理链（这是「不会旧引用」的根据）：

1. `outer` 不是凭空拿到的 —— 它来自 `while` 循环里 `v` 沿 `parent` 链上溯时第一个 `contains("MMChattingListView")` 的节点（`ForceForward.kt:844-848`）。
2. 因此 `outer` 一定是「**当前把长按的 View `v` 包在子树里的那个 MMChattingListView 实例**」。列表是 `outer` 的 child（`v` 就挂在列表上），`outer.T` 就是这个列表。
3. 所以 `outer.getListView()` = `outer.T` = **当前正滚动的那个列表**，与 parent 链上能找到的列表是同一对象。

「持有旧引用」要成立，前提是 `outer` 是旧实例 —— 但旧实例不可能出现在 `v` 的 `parent` 链上（`v` 挂在新树上）。若 `v` 已被摘窗，`View.removeView` 会把 `parent` 置 null，`while` 第一次 `node.parent` 就是 null，`outer` 根本拿不到，`getListView()` 不执行 —— 也不会误拿旧引用。

### 2.3 一个边界瑕疵（P2，不阻塞）

`listKindOf` 的 `unknown` 判据里 `nm.contains("ListView")`（`ForceForward.kt:1068`）会命中 **`MMChattingListView` 自己**（类名 `com.tencent.mm.ui.chatting.view.MMChattingListView` 含 `"ListView"` 子串）。

因此当 parent 链上**列表未被严格命中**（未来版本换类）且先遇到 MMChattingListView 时，`chatListLoose` 可能被记成 MMChattingListView 而非列表。但：
- `getListView()` 兜底排在 loose **之前**（`ForceForward.kt:855` → `861`），只要 `getListView()` 成功，loose 不会被用；
- 即便真用到 loose（getListView 也失败），`listKind` 会输出 `"unknown"`，`diagnose()` 走 `list=unknown` 分支，不会误导成「recycler 定位失败」。

故定 P2，不影响上机。

### 2.4 `chatListLoose` 是不是死代码？（team-lead 角度 2）

**结论：不是死代码，但触发概率很低；建议保留，理由是「防未来」而非「防当前」。**

`chatListLoose` 被用上的**充要条件**（`ForceForward.kt:861` `if (chatList == null) chatList = chatListLoose`）是：严格命中失败 **且** `getListView()` 也失败。逐条看 `getListView()` 失败的前提（`ForceForward.kt:855-859`）：

1. `outer == null`：parent 链 12 层没遇到 `MMChattingListView`（不在聊天页，或层级 >12）。→ 此时 `getListView()` 跳过，`chatList` 仍 null，**落到 loose**。
2. `getMethod("getListView")` 抛异常：`outer` 不是 MMChattingListView（未来加子类且 `contains` 误命中）。极端，但可能。
3. `invoke` 返回 null（`T` 字段为 null，`l()` 未初始化）。→ `as? View` 得 null → `chatList` 仍 null，**落到 loose**。

而「不在聊天页」时 `chatListLoose` 大概率**也是 null**（parent 链上没有列表状对象）⇒ loose 本身也拿不到。所以 team-lead 说的「兜住不在聊天页的退化场景」**论证站不住**——不在聊天页时 loose 多半也是 null，靠它兜不住。

**但 loose 仍有保留价值**，理由换一个：**它防的是「`getListView()` 恰好失败、而 parent 链上又有一个 `unknown` 类名容器」的窄场景**（例如聊天页列表被换成新实现类、`listKindOf` 判 `unknown`，但 parent 链能遇到它；此时若 `getListView()` 因某些原因失败，loose 能把它捞出来并打 `listCls`，让人看到「真实的列表类名」）。这个场景比「不在聊天页」更窄、更少，但它提供的 `listCls` 可观测性对「未来微信换类」的诊断有价值——**这是「防未来」而非「防当前」**。

**明确建议：保留。** 删掉它省不了几行、也提不了性能（一次字符串 `contains`）；保留它在「未来换类 + getListView 失败」时能多打一个 `listCls`，代价为零。**不是死代码，是低成本哨兵。**（与 V2 §6.8-B 的「loose 不是永远用不上」结论方向一致，但我把它的「兜住不在聊天页」这个具体论证否掉了——那个场景 loose 自己也兜不住。）

---

## 3. Q3 · pos/posVia/posFail 真值表有没有第 9 行

**结论：没有第 9 行（无新的自相矛盾/空洞组合）。但发现 selfaudit 真值表第 4、5 行的 `posVia` 列标错。**

### 3.1 完整路径枚举（recycler 分支）

变量流转：`pos`/`posVia`/`posFail` 初值 `-1`/`"none"`/`"none"`（`ForceForward.kt:873-875`）。

| 路径 | pos | posVia | posFail |
|---|---|---|---|
| itemRoot 上溯失败 | -1 | none | itemRootNotFound（`:928`） |
| 候选链命中（`F0`/`G0`/原名 invoke>=0） | ≥0 | 命中名 | none（`if(pos<0)` 不执行） |
| 候选链全 getMethod 失败 | -1 | none | noMethod（`:924`） |
| 候选链有方法、全抛 ClassCast | -1 | **none**（见 3.2） | classCast（`:925`） |
| 候选链有方法、全返 -1 | -1 | **none**（见 3.2） | returnedMinusOne（`:966`） |
| A0scan 命中 | ≥0 | A0scan | none（`:963` 显式置） |
| A0scan 未命中 | -1 | none | noMethod（保持，见 3.3） |
| listview `getPositionForView` 命中 | ≥0 | getPositionForView | none |
| listview 反射不到 | -1 | none | noMethod（`:979`） |
| listview 调通返 -1 | -1 | getPositionForView | returnedMinusOne（`:977`） |

### 3.2 ⚠️ selfaudit 真值表两行 posVia 列标错（不阻塞，但要纠正）

selfaudit 表（`_r16b_selfaudit.md:223-224`）写：

```
| -1 | `F0`/`G0` | `classCast` | 调用抛 ClassCast |
| -1 | `F0`/`G0` | `returnedMinusOne` | 调通了都返 -1 |
```

但代码里 `posVia` **只在成功时赋值**（`ForceForward.kt:912` `if (p >= 0) { pos = p; posVia = name; break }`）。失败时 `posVia` 保持 `"none"`。所以这两行的 `posVia` 实际是 `"none"`，不是 `F0`/`G0`。

影响：`diagnose()` 对 recycler 的 `classCast`/`returnedMinusOne` 判读**只看 `posFail`**（`_r16_msgsnap.py:230-242`），不看 `posVia`，所以**不影响判读正确性**。但真值表本身作为「字段一致性检查工具」写错了 posVia 列，会让后来人误以为 `posVia` 能区分「哪个方法抛的 ClassCast」——实际不能。**这是 selfaudit 的一处不精确，我独立指出。**

### 3.3 effKind 的 unknown 降级与 posFail 的交互（team-lead 特别问）

`effKind`（`ForceForward.kt:879-884`）：

```kotlin
val effKind = when {
    listKind == "recycler" -> "recycler"
    listKind == "listview" -> "listview"
    listKind == "unknown" && chatList.javaClass.name.contains("Recycler") -> "recycler"
    else -> "listview"
}
```

`listKind=="unknown"` 降级走 recycler 时，`posFail` 会经历：itemRoot 上溯（可能 `itemRootNotFound`）→ 候选链（几乎必 `noMethod`，因为 unknown 对象不是 `RecyclerView` 后代，`F0`/`G0`/`A0` 都 getMethod 失败）→ A0scan（`A0` 也 getMethod 失败，runCatching 吞 → scanHit=-1）→ posFail 保持 `noMethod`。

**关键：`list` 字段输出的是 `listKind`（`"unknown"`），不是 `effKind`**（`ForceForward.kt:862` + `:1032`）。所以 `diagnose()` 会先命中 `list=unknown` 分支（`_r16_msgsnap.py:218`），报「把类名加进 listKindOf」，**不会**误入 recycler 的 `posFail=noMethod` 分支。`effKind` 的 unknown 降级产生的 `posFail=noMethod` 是**被 `list=unknown` 更早拦住的**，方向正确，无矛盾。

### 3.4 无第 9 行

- `pos>=0` ⇒ `posFail` 必 `"none"`（三条成功路径都保证），无「矛盾」行。
- `pos=-1` 且 `posFail="none"` 的「空洞」只在 `chatList==null` 或 `v==null` 时产生（`:876` 不进入），此时 `list=none` 或 `view=none`，`diagnose()` 的最早两个分支（`:205`/`:212`）先 return，**空洞被挡住，不漏判**。
- `chatList!=null && v!=null` 时，recycler 的 `:966` 兜底 + listview 的两路赋值保证 `posFail` 恒非 `"none"`。

---

## 4. Q4 · A0scan 降级路径

### 4a. `getFirstVisiblePosition` 是真实现，非空桩 ✅

我独立反汇编 `ChattingRecyclerView.getFirstVisiblePosition`（classes4.dex `code_off=0x48a464`，`_r12_dexclass.py` 确认 `virtual_methods` 里 `0x1 getFirstVisiblePosition code=0x48a464`）：

```
[ 0] invoke       RecyclerView->getLayoutManager()
[ 3] move-result-object v0
[ 4] const/4 v1, -1                       ; 默认 -1
[ 5] instance-of v2, v0, LinearLayoutManager
[ 7] if-eqz v2 -> +9                      ; 非 LinearLayoutManager → 返回 -1
[ 9] check-cast v0, LinearLayoutManager
[11] invoke       LinearLayoutManager->w()I
[14] move-result v0
[15] move v1, v0                          ; 把 w() 结果放进 v1
[16] return v1
```

（寄存器解析：raw units `0xf112`=const/4 v1,-1；`0x0220`=instance-of v2,v0；`0x0101`=move v1,v0；`0x010f`=return v1。）

`LinearLayoutManager.w()`（classes12.dex `code=0x16f8bc`）反汇编 invokes：`getChildCount` → `LinearLayoutManager.A` → `LayoutManager.getPosition`，即标准 `findFirstVisibleItemPosition`。**真实现，非空桩。** 非 LinearLayoutManager 时返回 -1，代码里 `if (first >= 0)`（`:949`）已覆盖。

### 4b. `for (i in 0..-1)` 是空循环，不是倒序 ✅

`from..to` 生成的是**升序** `IntRange`（step=1）。当 `count==0` 时 `to = minOf(first+50, -1) = -1`，`0..-1` 的 `isEmpty` 为 true（`first > last`），**循环体一次都不执行**，不会倒序、不会抛异常。语义正确（count==0 时不扫描）。

### 4c. ⚠️ `a0.invoke(chatList, i) ?: break` 早退是错的（P1，不阻塞当前上机）

代码（`ForceForward.kt:956-961`）：

```kotlin
for (i in from..to) {
    // A0 在 I 字段早退时（layout 进行中）第一次就返回 null，直接放弃
    val vh = a0.invoke(chatList, i) ?: break
    val iv = XposedHelpers.getObjectField(vh, "d") as? View
    if (iv === itemRoot) { scanHit = i; break }
}
```

**注释的推理不成立，break 是错误早退。** `A0(i)` = `findViewHolderForAdapterPosition(i)`（反汇编见 `_r16_f4_defect_finding.md:270-283`），它**只遍历当前 attach 的 child**，对「position 不在可见区」返回 null——这是**常态**，不是只有「I 字段早退」这一种 null 原因。

窗口计算（`:947-955`）：

```kotlin
from = (first - 50).coerceAtLeast(0)
to   = minOf(first + 50, count - 1)
```

长按的 item position `P` 落在可见区 `[first, first+visibleCount)`。但扫描 `i` 从 `first-50` 开始：`first-50 .. first-1` 这 50 个 position **都不在可见区**（它们在列表上方，ViewHolder 已被回收）⇒ `A0(first-50)` 返回 null ⇒ `?: break` 立即退出。

**后果**：当 `first >= 50`（列表滚过 50 条）时，A0scan **第一次迭代就 break，永远扫不到 P**。只有当 `first < 50`（列表几乎在顶部，`from` 被 coerce 到 0 且 0 恰好在可见区）才可能命中。

正确写法应是 `?: continue`（跳过 null 继续找），或把窗口起点设为 `first`（只扫可见区）。

**定级 P1，不阻塞本次上机**：A0scan 只在 `posFail=="noMethod"` 时触发（`:935`），而当前版本 `F0`/`G0` 存在，候选链不会 `noMethod`，故 A0scan **本次不会执行**。但它是「未来版本混淆名再变、候选链失效」时本应兜底、却会静默失效的潜伏 bug。注意：A0scan 失败后 `posFail` 保持 `noMethod`（`scanHit=-1`，`:966` 的 `posFail=="none"` 条件不满足），所以日志仍报 `posFail=noMethod`，不会暴露「A0scan 也试过且失败」——可观测性被 break 缺陷二次掩盖。

---

## 5. Q5 · diagnose() 判读顺序 + 输入构造验证

### 5.1 顺序本身：不漏判 ✅

分支顺序（`tools/_r16_msgsnap.py:205-285`）：`view=none` → `attached=false` → `_pos_ok` 防御短路（见 5.3）→ `list=none` → `list=unknown` → `list=recycler`（内含 posFail 四态 + `<missing>` + 未知 posFail 兜底）→ `pos=-1`（listview 的 posFail 三分）→ `adapter=none` → `msgId=none` → 成功。

team-lead 特别问的「`list=recycler 且 posFail=noMethod`」与「`list=unknown`」先后：**两者 `list` 字段互斥**（一个值），不存在先后冲突。

关键核对：

1. **`list` 字段 = `listKindOf(chatList)` 的结果**（`ForceForward.kt:862`），不是内部 `effKind`。unknown 降级走 recycler 时 `list` 仍是 `"unknown"`，`diagnose()` 在 `:234` 先 return，**不会**落进 recycler 的 `posFail=noMethod` 分支。✓

2. **listview 真失败正确区分**：`list=listview, pos=-1, posFail=noMethod` 落到 `:276 pos=-1` 分支 → `pf=noMethod` → `:280` 报「getPositionForView 反射不到，listKindOf 判错了形态」。✓

3. **recycler 预期成功不误报**：`list=recycler, pos>=0, posFail=none` → `:263` `pass` → 落到 adapter/msgId 判读。✓

### 5.2 输入构造验证（team-lead 角度 3，实测跑通）

我直接 `import` 该模块、造 12 个字段组合喂 `diagnose()`，结果：

| 组合 | 输出判读 | 是否正确 |
|---|---|---|
| `recycler pos=5 posFail=none via=F0`（正常成功） | OK「adapter 链通了」 | ✅ |
| `recycler pos=-1 fail=noMethod` | 「候选方法名全不存在 ⇒ 代码 bug」 | ✅ |
| `recycler pos=-1 fail=classCast` | 「itemRoot 不是直接 child」 | ✅ |
| `recycler pos=-1 fail=itemRootNotFound` | 「断在第一步」 | ✅ |
| `recycler pos=-1 fail=returnedMinusOne` | 「方法调通但返 -1」 | ✅ |
| `recycler pos=-1 fail=none`（空洞禁形） | 「posFail 是未预期值 ⇒ 版本不匹配」 | ✅（新版文案，诚实） |
| `listview pos=-1 fail=noMethod` | 「listKindOf 判错了形态」 | ✅ |
| `listview pos=-1 fail=returnedMinusOne` | 「View 不在该列表里」 | ✅ |
| `list=unknown pos=5 fail=none` | 「类名不认识，加进 when」 | ✅（假 pos 被拦） |
| `list=unknown pos=-1 fail=noMethod` | 「类名不认识，加进 when」 | ✅（unknown 早退） |
| `view=none` / `attached=false` / `list=none` | 各自环境早退 | ✅ |

**结论：10/12 组合判读正确，无误导。** 唯一有问题是下面这个：

### 5.3 ⛔ 发现：`_pos_ok` 防御短路是死代码（P2，不阻塞）

`diagnose()` 新增的「防御性短路」（`_r16_msgsnap.py:212-227`）：

```python
_pos_ok = False
try:
    _pos_ok = int(fields["pos"]) >= 0
except (ValueError, TypeError):
    _pos_ok = False
if _pos_ok and fields["posFail"] not in ("none", "<missing>"):
    out.append((True, "pos=%s 已拿到（posVia=%s），但 posFail=%s 与之矛盾 ⇒ "
                      "**以 pos 为准** ..." % (...)))
```

注释（`:212-217`）声称这是为了拦住「`pos=5 posFail=classCast` 这种禁形组合被误判失败」。但**它没生效**：

- `out.append(...)` 把「以 pos 为准」这条 OK 消息塞进 `out`；
- 随后所有分支（`list=none`/`list=unknown`/`list=recycler` 的四态/`pos=-1`）都是 `return [...]` **直接返回新列表，从不读取 `out`**；
- `out` 变量在函数里**除这一处 append 外从未被读取、从未被 return** ⇒ `out` 是死变量。

实测喂 `list=recycler, pos=5, posFail=classCast`（本应不可能，但老 APK/手工构造可产生）：

```
[X] list=recycler 且 posFail=classCast ⇒ 传进去的 itemRoot 不是 RecyclerView 的直接 child...
```

——**它仍给出误导文案**，「以 pos 为准」那条被 `return` 丢弃，用户看不到。即「声称防御、实际没防御」。

**定级 P2，不阻塞**：新 APK 用 `if (pos < 0)`（`ForceForward.kt:923`）已保证「pos>=0 时 posFail 必为 none」，不产生该组合；只在判读老 APK / 手工构造日志时才误导。

**修法建议**（只读，不代改）：把 `out.append(...)` 改成 `return [(True, "...以 pos 为准...")]`（短路条件满足时直接 return），或把整个函数重构为「先 append 到 out、函数末尾统一 `return out`」。

> 注：本节初稿曾报「`:250` 旧文案残留 recyclerStub 措辞」的 P2。复核期间该脚本已被同步更新（`_r16_msgsnap.py:266-275` 现在是「posFail 未预期值 ⇒ 版本不匹配」的新文案），**旧文案 P2 已消除**，改为上述 `_pos_ok` 死代码这个新发现。

---

## 6. Q6 · 编译/运行期风险

静态审查 `msgsnapReport()` / `listKindOf()` 全段：

| 风险项 | 结果 |
|---|---|
| `!!` | **无**（本段 0 处；grep 确认 `msgsnapReport` 范围内无 `!!`） |
| 空指针 | 无：`chatList?.javaClass?.name ?: "none"`（`:1032`）；`chatList==null` 检查（`:862`）；`chatList.javaClass.name` 在 `if (chatList != null && v != null)` 块内（`:876`/`:879`）；`itemRoot != null` 保护（`:894`/`:935`）；`a0.invoke ?: break` 保证 `vh` 非 null（`:958`） |
| 无限循环 | 无：两个 while 都有 `guard++<12`/`g2++<12`；`listKindOf` 的 while 靠 `c!=null` 终止（superclass 链必到 Object→null）；候选链 for 固定 4 次；A0scan for 有界 |
| `as? View` 链式 | 安全：`node.parent as? View`（parent 可能非 View，`as?` 返回 null）；`as? String`（`:1026`） |
| `runCatching` 吞异常 | 得当：所有反射失败都有字段痕迹（`posFail`/`adapter=none`/`msgId=none`/`imgPath=none`），无「吞掉该抛的」情况。`ClassCastException` 用 `r.exceptionOrNull()` + cause 链展开（`:914-918`）单独捕获，未与 noMethod 混淆 |
| 编译 | `compileDebugKotlin EXIT=0`（已知）；静态未见编译/类型问题 |

**结论：无空指针、无 `!!`、无无限循环、无 `as?` 链式隐患、`runCatching` 使用得当。** 唯一实质风险是 §4c 的 `?: break` 逻辑缺陷（正确性问题，非崩溃）。

---

## 7. 对 _r16b_selfaudit.md 结论的独立核验 + 对 V2 §6/§6.8 的独立抽查

### 7.1 selfaudit 三缺陷核验

| selfaudit 结论 | V3 独立判断 |
|---|---|
| 缺陷 1（`chatList` 抢占，`unknown` 占位）已修 | ✅ 认可修法。`unknown` 只记 `chatListLoose` 且排在 `getListView()` 之后，抢占路径消除。**但触发前提存疑，见 7.2** |
| 缺陷 2（posFail 自相矛盾）已修 | ✅ 认可。`if (pos < 0)` 挡住「成功后又报失败」，`break` 后不再无条件判 `sawClassCast` |
| 缺陷 3（listview 分支无失败原因）已修 | ✅ 认可。两路都赋值（`noMethod`/`returnedMinusOne`），空洞消除 |
| 真值表 8 行 | ⚠️ **第 4、5 行 `posVia` 列标错**（应为 `none` 而非 `F0`/`G0`，见 §3.2）。不影响判读，但表不精确 |
| 「修完后代码已不会产生禁形」 | ✅ 认可（§3.4 独立枚举确认） |

### 7.2 ⚠️ 缺陷 1 的触发前提「消息卡片内嵌横向 RecyclerView」—— 我独立 DEX 枚举：**在当前 dex 里找不到实证**

team-lead 已补证（写进 `_r16b_selfaudit.md` 补证 2），我独立复算并用**更严的包过滤**确认：

- 全 16 dex 里继承链含 `RecyclerView` 的类共 **96 个**（我脚本枚举，与 team-lead 数字一致）。
- 其中 `com.tencent.mm.ui.chatting.viewitems.*`（消息 item 的 View 实现包）里继承 RecyclerView 的 = **0 个**（独立复算）。
- `com.tencent.mm.ui.chatting.**` 全包下继承 RecyclerView 的 = **0 个**（独立复算）。
- 消息 item 基类 `viewitems/a` 的继承链 = `viewitems/a → java/lang/Object`（**不是 View 子类**，说明 viewitems 里真正的 item View 是别的类；但无论哪个，该包内无任何 RecyclerView 后代）。
- 96 个 RecyclerView 子类里，名字含 card/msg/chat 的集中在 `plugin/game/chatroom`、`plugin/game/ui/message`、`plugin/appbrand`（小程序桌面）、`plugin/finder`（视频号）、`plugin/sns/ad`、`msgsubscription`（订阅号）。**无一个在 `ui/chatting` 包内。**

**我的结论：team-lead 的「补证 2」方向正确——「消息卡片内嵌横向 RecyclerView」在当前微信 8.0.48 dex 里找不到实证，缺陷 1 的触发场景是想当然。**

但我要补一个 team-lead 补证没点透的边界（这是价值所在）：

> **「不在 ui/chatting 包内」≠「不会出现在聊天页 UI 里」。** 聊天页的消息卡片确实会**嵌入跨包 View**——典型如 `plugin/appbrand` 的小程序卡片、`plugin/finder` 的视频号卡片、`msgsubscription` 的订阅号卡片。这些卡片的 View 树里**确实可能**有 RecyclerView（如 `AppBrandRecentView`、`BizNativeFinderRecyclerView` 等 96 个之一）。但这些是**别的插件/功能页的 View 被塞进消息 item**，不是聊天主列表自身的结构；且：
> 1. 它们出现在聊天页的概率、以及出现时是否恰好在「长按消息」那条 item 的 parent 链上，**需真机**；
> 2. 即便出现，缺陷 1 的修法（`unknown` 不抢占、只作 loose 兜底）**对这两种情况都正确**——因为 `unknown` 容器无论是内嵌卡片还是真列表，都不该抢占 `chatList`。

**所以我的独立判定：缺陷 1 的**修法**正确保留（`unknown` 不抢占的语义是对的），但**触发场景要改写**：不是「内嵌横向 RecyclerView 先被遇到」（dex 里无实证），而是「跨包卡片 View 被塞进聊天页时其内部 RecyclerView 可能出现在 parent 链上（需真机）」。team-lead 的「补证 2」我**认可其方向**，但补上「跨包卡片 View 会进聊天页」这个它没展开的边界，把「想当然」升级成「有边界条件的低概率风险」。

### 7.3 对 V2 §6/§6.8 判 PASS 的独立抽查

V2 判 PASS（`_r16_v2_review.md` §6.7/§6.8）。我抽查其关键论证，结果：

| V2 结论 | 我抽查结果 |
|---|---|
| Q1 listKindOf 继承链 3 类全命中（§6.1） | ✅ 一致。我独立 `_r13_findclass.py` 重取 super 链，`view.l` 第 3 轮命中 `ChattingListView` |
| Q2 getListView 纯 iget T、u3 仅 3 实现全 View 子类（§6.3） | ✅ 一致。我独立反汇编 `getListView`（`code_off=0x5194ac`）= `iget T + return`；`u3` 实现类 3 个且全 View 后代 |
| Q3 真值表 12 行无矛盾无空洞（§6.8-C） | ✅ 一致（§3.4 独立枚举，第 12 行「chatList/v=null」被 diagnose 早退兜住非空洞）。**但我多抓一处：V2 的 12 行表里「F0 返-1/G0 命中」等降级场景的 `posVia` 列同样标成最终胜出者，与 selfaudit 第4/5行的 posVia 错误同源——都不影响判读，属表不精确** |
| Q4 A0scan 各锚点 public + `0..-1` 空区间（§6.6） | ⚠️ **部分不一致**：V2 说 `a0.invoke(...) ?: break`「立即退出不继续空转，✅」——**我判定这是 bug（§4c）**，break 会让 A0scan 在 `first>=50` 时永远扫不到可见项。V2 漏掉了这个 |
| Q5 diagnose 顺序（§6.2） | ⚠️ **部分不一致**：V2 论证「假 pos 被 list=unknown 早退兜住」成立；但 V2 没做输入构造验证，**漏掉了 `_pos_ok` 防御短路是死代码**（§5.3）这个可观测性问题 |
| Q6 静态审查（§6.6） | ✅ 一致：无 `!!`、无 NPE、无死循环；`:868` 的 `var chatList: View?` 智能转换隐患（P2）认可 |

**总结论**：V2 的 PASS **方向正确，我认可**（两个 P0 确已修好、无阻断项）。但 V2 漏了两处：① `?: break` 的 A0scan 逻辑缺陷（V2 反而当成「✅ 防空转」）；② `_pos_ok` 防御短路死代码（V2 没做输入构造，没发现）。这两处都是 P1/P2、**不推翻 PASS**，但说明「纯读顺序 + 不喂输入」的复核方式会漏「可观测性」和「兜底逻辑」两类 bug。

---

## 8. 未验证项（需真机）

1. 聊天页实际走 listview 还是 recycler 形态（AB 开关 `adapter/x;->c1` 默认 4，两种都可能）—— 需真机。
2. item 根 View 真实层级深度（12 层封顶是否够）—— 需真机。
3. `F0`（getAdapterPosition）vs `G0`（getLayoutPosition）返回值在 item 未移动时等价，运行时是否有动画/移动 —— 需真机。
4. `field_imgPath` 对图片是否恒非空；语音/视频/文件路径在哪个字段 —— 需真机。
5. `MMChattingListView` 在列表重建期间 `T` 字段与视图树是否可能短暂不一致（§2.3 的理论窗口）—— 需真机。

---

## 9. 最终判定

**PASS，可上真机。**

- 阻断项（P0/P1 阻断级）：**无**。
- P1（不阻塞）：A0scan 的 `?: break` 早退缺陷（§4c）—— 当前版本不触发，但应改 `?: continue`（或窗口起点改 `first`）。
- P2（不阻塞）：
  1. `diagnose()` 的 `_pos_ok` 防御短路是死代码，禁形组合仍被误判（§5.3，建议改 `return [...]`）。
  2. selfaudit 真值表第 4/5 行 `posVia` 列标错（§3.2）。
  3. `listKindOf` 的 `nm.contains("ListView")` 会把 `MMChattingListView` 判成 `unknown`（§2.3）。
- 对 selfaudit / V2 的独立核验：两轮都判 PASS、方向正确；但 V2 漏了 `?: break` 缺陷和 `_pos_ok` 死代码两处，属「不喂输入」复核方式的盲区（§7.3）。
