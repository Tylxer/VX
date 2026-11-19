# R73 · 「转发（模块）入口不稳定：有些文字长按有、有些没有」只读取证

> 取证视角：只读（未改源码、未碰 adb/设备/gradle/单测）。执行：r73-entry-presence（hy4flash 临时规则）。
> 宿主：微信 8.0.48 / versionCode 2588，包 com.tencent.mm；模块 com.lx.wxgroup。
> 起点链路（主会话已核实，本文不重复从头挖）：
> `wg4/q.p(II)Z` before → `ForceForward.onShowGridMenu`（ForceForward.kt:317-380）→ 门禁
> `NativeMenuGate.shouldInject(enabled, lastPressAt, now, hasRef)`（:69-77）四条件全满足才 `menu.add`。
> 证据等级：`[源码]`=源码直接可见 ｜ `[真机]`=已落盘日志/文档记载 ｜ `[推断]`=由证据外推 ｜ `[依据不足]`=材料解不出，不编造。

---

## 0 结论先行（一句话）

「有些文字有、有些没有」不是全局有无，而是**同一聊天里按消息/按手势的差异化失败**。从源码看，原生 add 成败只由四条件门禁决定，其中真正会随「这一条消息」波动的只有两项：**`hasRef`（沿 View 链能否取到 h9）** 与 **`lastPressAt` 是否刚被这长按更新（长按 trap 是否命中）**；`enabled` 是全局开关、`age` 时间窗基本恒满足。差异化的最可能根因排序见 §6：① 某些消息类型的长按 View 不进 trap / 进 trap 但取不到消息对象；② 微信在部分消息上连 PopupWindow/原生菜单都不弹（`q.p` 不被调用，add 点不可达）；③ 按「文字」语义可能是同一消息里长按住不同文字 region（气泡 vs 选中文本/链接/引用块），触发的是不同 View、门禁判定不同。

---

## 1 installLongPressTrap 到底 hook 了什么、哪些消息类型会进

### 1.1 hook 目标：`android.view.View` 框架类，两个 `performLongClick` 重载（不是微信的类）

`ForceForward.kt:247-267`：

```kotlin
val cb = object : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) {
        val v = param.thisObject as? View ?: return   // :252 —— 要求调用者是 View
        val now = System.currentTimeMillis()
        synchronized(pressLock) {
            if (now - burstStartAt > BURST_GAP_MS) {  // :255 新一轮 burst(>500ms)才记最深 View
                burstStartAt = now
                burstDeepest = WeakReference(v)       // :257
            }
        }
        lastPressAt = now                             // :260 —— 每次回调都刷新
    }
}
val n = hookAll(View::class.java, "performLongClick", cb)   // :264
LogX.i("FF installed long-press trap (View.performLongClick x$n)")
```

`hookAll`（:1974-1984）遍历 `cls.declaredMethods`，按名字 hook 该类声明的**所有同名重载**，返回的 `n` 即命中数。

- 目标类是 `android.view.View`（框架基类），**不是**任务描述里假设的「两个微信类」。
- `[真机]` 日志 `FF installed long-press trap (View.performLongClick x2)`（`wxgroup/_cache/dbg.txt:18/103/273/...`，全文件多轮均为 x2）：`View` 声明的 `performLongClick` 有 **2 个重载**——`performLongClick()` 与 `performLongClick(FF)Z`（带坐标，hidden），二者都被 hook。另有独立证据：`docs/conclusions/conv_list.md:70`「hook `View.performLongClick`(2 重载)」、`docs/_r55_microx_click_longclick.md:284`「hookAll 扫全部重载」。
- 因此「x2」= 同一基类 `android.view.View` 的两个方法重载，**不是 hook 了两个不同的类**。此处纠正任务起点里的措辞。

### 1.2 哪些消息长按会进 trap：取决于该消息的长按是否最终走到 `View.performLongClick`

hook 在框架基类上 ⇒ 任何 View 子类只要其长按分发最终调用到 `View.performLongClick()`（含带坐标重载），before 回调都会触发，`lastPressAt` 被刷新。差异只在「微信对某种消息是否走这条标准 View 长按路径」：

| 消息类型 | 是否走 `View.performLongClick` trap | 依据 |
|---|---|---|
| 纯文本（普通聊天） | 走（已实测门禁 `age=151`、`ref=true`） | `[真机]` `docs/_r73_l3_review.md:56/71` |
| 图片 / 图片卡片 | **不走**（自定义手势），`pressedView` 不更新 | `[真机]` `docs/_r71_g1_native_forward_verdict.md:66`「图片消息长按不走 View.performLongClick trap（微信图片卡片用自定义手势）」；`docs/_r73_blocked_forward_forensics.md:252/296/308` 同结论 |
| 带链接文字 / 表情 / 引用 / 文件 / 语音 / 视频号 / 拍一拍 / 系统提示 / 撤回提示 | `[依据不足]` 无逐类型的 dex 反汇编或真机日志落盘；只能确定「若其 item View 长按经标准 View 分发则进，若走自定义 onTouch/手势则不进」 | 项目内 `_cache/_s9dump/classes*.txt` 等反汇编产物未记录这些类型的长按 View 类型 |

> 结论（问题1）：hook 目标是 `android.view.View` 的 2 个 `performLongClick` 重载（真机 x2），非两个微信类。纯文本进、图片卡片明确不进；其余类型依据不足，需真机探针补证。**注意**：hook 基类 View 的方法，会被所有子类继承调用命中，覆盖面本身很广；真正造成「有/没有」的不是 hook 目标，而是 §2/§3 的「取不取得到 h9」和 §5 的时序。

---

## 2 msgRefOf 沿 View 链取 h9 的完整逻辑与失败点

`ForceForward.kt:843-905`（生产路径，KDoc :830-842 列为 G2 ①链）。完整链路与每一步失败条件：

```kotlin
private fun msgRefOf(v: View): MsgRef? {
    // 1) 向上 ≤12 层找列表 + 外层容器 MMChattingListView
    var node: View? = v; var list: View? = null; var outer: View? = null; var guard = 0
    while (node != null && guard++ < 12) {
        if (list == null) when (listKindOf(node)) {           // :852 只认继承链精确命中
            "recycler", "listview" -> list = node            // :853
        }
        if (node.javaClass.name.contains("MMChattingListView")) { outer = node; break }  // :856
        node = node.parent as? View                           // :860
    }
    // 兜底：MMChattingListView.getListView() 直接拿列表
    if (list == null && outer != null)
        list = runCatching { outer.javaClass.getMethod("getListView").invoke(outer) as? View }.getOrNull()  // :863-867
    if (list == null) return null                             // :868 失败点A

    // 2) position
    val pos = positionOf(list, v)                             // :871
    if (pos < 0) return null                                  // :872 失败点B

    // 3) adapter：优先 getBaseAdapter()，退回混淆名 m()
    var adapter: Any? = null
    if (outer != null) adapter = runCatching { outer.javaClass.getMethod("getBaseAdapter").invoke(outer) }.getOrNull()  // :877
    if (adapter == null) adapter = runCatching { list.javaClass.getMethod("m").invoke(list) }.getOrNull()            // :880
    if (adapter == null) return null                          // :882 失败点C

    // 4) getItem(pos) → h9（Object，不强转）
    val h9 = runCatching {
        adapter.javaClass.getMethod("getItem", Int::class.javaPrimitiveType).invoke(adapter, pos)
    }.getOrNull() ?: return null                              // :885-887 失败点D

    // 5) 按名字反射取值
    val msgId   = runCatching { h9.javaClass.getMethod("getMsgId").invoke(h9) as? Long }.getOrNull() ?: 0L   // :890
    val msgType = runCatching { h9.javaClass.getMethod("getType").invoke(h9) as? Int }.getOrNull() ?: 0     // :891
    ...
    return MsgRef(... kind = ForwardLogic.kindOf(msgType, ...))  // :896-904
}
```

`positionOf`（:912-938）：recycler 走混淆候选链 `F0 / G0 / getChildAdapterPosition / getChildLayoutPosition`（:928），前提是能从 `v` 沿 parent 向上找到「parent===list 的 itemRoot」（:919-926，找不到 return -1）；listview 走 `getPositionForView`（:935-937）。`listKindOf`（:1924-1949）只认继承链精确命中 `ChattingRecyclerView` / `ChattingListView`（:1929-1930），其余类名含 ListView/Recycler 的判 `unknown`、含 `MMChattingListView` 判 `notList`（:1944，专防外壳占位）。

### 2.1 哪些消息的 View 链拿不到 h9 ⇒ hasRef=false ⇒ 门禁挡死

`hasRef` 计算在 `onShowGridMenu:336-338`：`v = pressedView()`；`hasRef = v != null && msgRefOf(v) != null`。因此**只要 v 为 null 或 msgRefOf 在上述任一失败点 return null，门禁必挡**。按失败点归因：

- **失败点 A（list 找不到，:868）**：从长按 View 向上 12 层内既没有继承链命中 `ChattingRecyclerView`/`ChattingListView` 的节点，也没有 `MMChattingListView` 外壳。典型：`pressedView` 指向的不是聊天消息 item（会话列表、输入栏、图片浏览浮层、系统提示/拍一拍这类可能不走标准消息 item 容器的行）。`[源码]` + `[真机]` `docs/_r73_l3_review.md:59` 已确认「hasRef=true 几乎等价于聊天消息长按」的反面即其他菜单 hasRef=false。
- **失败点 B（pos<0，:872）**：`v` 的 parent 链找不到 parent===list 的 itemRoot（recycler），或 `getPositionForView(v)` 返回 -1（listview）。`[真机]` 文档明确指出陈旧/已摘下的 View 必然 -1（`msgsnapReport` KDoc `ForceForward.kt:1668-1672`）；R73-L6 review（`docs/_r73_l6_review.md:26-29`）指出 recycler 的 View 被回收重绑后 positionOf 取的是「当前 position」⇒ 拿到的是**另一条消息的 h9**。
- **失败点 C（adapter 拿不到，:882）**：`getBaseAdapter()` 与混淆名 `m()` 都取不到（`[真机]` 文档要求优先 `getBaseAdapter()`，:877；`m()` 是唯一混淆兜底，:880，`docs/_r73_l3_review.md` 未记此步失败但实际存在版本漂移风险）。
- **失败点 D（getItem 返回 null，:886）**：position 越界/数据已变。
- **依赖的 id/tag/字段小结**：完全不依赖 View 的 id 或 tag，靠 **parent 链结构 + 列表类名继承链 + 固定 getter 名/字段名**（`getMsgId/getType/getContent/field_imgPath/field_talker/field_msgSvrId`）。任一混淆名变化（换版本）或父链结构变化都会静默失败。

> 结论（问题2）：`msgRefOf` 靠「从 v 沿 parent 链找聊天列表 → 定位 position → adapter.getItem → 按名取 h9 字段」。图片卡片/系统提示/非消息 item、已回收重绑的 View、类名或 getter 混淆漂移，都会让它返回 null，直接把门禁 `hasRef` 挡死。这与「有些文字有、有些没有」高度吻合（同一聊天里不同消息类型/不同 View 结构 ⇒ 有的能取到 h9 有的取不到）。

---

## 3 pressedView() / burstDeepest 的记录条件与丢失分支

### 3.1 记录条件

`pressedView()`（:951-955）：

```kotlin
private fun pressedView(): View? = synchronized(pressLock) {
    val ref = burstDeepest ?: return null
    if (System.currentTimeMillis() - burstStartAt > BURST_TTL_MS) return null   // :953 TTL=10s
    ref.get()                                                                    // WeakReference 解引用
}
```

状态字段：`burstStartAt`（:193）、`burstDeepest: WeakReference<View>`（:194）、`lastPressAt`（:197）。三个关键常量：`BURST_GAP_MS=500`（:139）、`BURST_TTL_MS=10_000`（:142）、`LONG_PRESS_WINDOW_MS=4000`（:136）。

写入只在 trap before 回调（:250-261）发生：

| 事件 | burstDeepest | lastPressAt | 说明 |
|---|---|---|---|
| `now - burstStartAt > 500ms`（新一轮） | 置为当前 View 的弱引用（:257） | 刷新为 now（:260） | 「最深处先跑，每个 burst 只认第一个」 |
| `now - burstStartAt <= 500ms`（同 burst 内后续 View） | **不动**（保留第一个/最深那个） | 刷新为 now（:260） | 注释 :245 的设计意图 |

### 3.2 是否存在「trap 进了但没记 pressedView」/「记了但被清掉」分支

存在的分支（均 `[源码]`，部分 `[推断]` 后果）：

1. **同 burst 后续 View 不覆盖（:255-258）**：`q.p` 的门禁（:336）读的是 `pressedView()`=burstDeepest。若一次长按里**第一个** performLongClick 命中的 View 不是消息 item 根、而是某个内层子 View（文本被包在多层布局里），而真正能取到 h9 的 View 在同 burst 内后触发 ⇒ burstDeepest 停在那个「最深层子 View」。若那个子 View 的 parent 链同样能到 list，则无碍；若它是 `WebView`/自绘 span/独立浮层子 View，则 `msgRefOf` 在失败点 A/B 返回 null ⇒ 门禁挡死。**该分支正是「同一条消息里不同文字区域长按结果不同」的候选解释**（链接/引用/表情可能落在不同的内层 View）。

2. **TTL 过期（:953，10s）**：门禁处的 `age`（:333 `now - lastPressAt`）要求 ≤4000ms，而 `pressedView` 的 TTL 是 10000ms。**正常长按后立刻弹菜单，age 远小于 4s，TTL 不会在此挡。** 但注意：TTL 基于 `burstStartAt`，若同一 burst 被一次超长手势或连续事件刷新过 burstStartAt 后停滞 >10s，理论上仍返回 null——概率低。`[真机]` `docs/_r73_l6_review.md:127-128` 明确「门禁处 add 在 4s 窗内，TTL 不触发，用 pressedView 正确」。

3. **WeakReference 被 GC（:194/:954）**：`burstDeepest` 是弱引用。若长按的 View 在菜单弹出前被回收（列表滑动、item 复用、界面重绘），`ref.get()` 返回 null ⇒ `hasRef=false` ⇒ 门禁挡死。`[推断]` 概率中等偏低（4s 窗内通常 View 仍存活），但在滑动中长按或 RecyclerView 回收时可能发生，且解释了「偶发」。

4. **「记了但被清掉」**：源码中 `burstDeepest` **没有显式置 null 的分支**（除被新 burst 的 WeakReference 覆盖）。R73-L6 改动（`snapshotNow` 改用 `staleViewForProbe`，忽略 TTL）只影响**点击后的快照**，不影响门禁处的 `pressedView()`（门禁仍用带 TTL 的版本，:336）。故「被清掉」只剩 GC 与 TTL 两种软失效。

5. **microX / 第三方模块改菜单结构**：`[真机]` `docs/_r71_g1_native_forward_verdict.md:76`、`docs/_r73_blocked_forward_forensics.md:213` 记载微X在消息长按菜单注入「微X模块」项改结构，导致旧自绘路径 `FF classified CHAT_MSG but no box found`。但**该问题是自绘路径的 findMenuBox，原生 add（`q.p` before）不依赖菜单文案/box**，故不影响原生门禁——仅影响点击接管 `bindMyItemClick` 是否能找到我方 TextView（那是另一条链，用户现象是「入口不出现」而非「出现点不动」，可排除为主因）。

> 结论（问题3）：有 4 个可能的差异化/偶发分支——①同 burst 第一个 View 非消息根（最常见，按文字区域/消息类型分化）；②弱引用 View 被 GC（偶发）；③TTL=10s（门禁处基本不触发）；④无显式清空。最值得优先验证的是 ①。

---

## 4 classifyMenu 是否影响原生 add

**确认：原生 add 完全不依赖 `classifyMenu`。**

- `ForwardLogic.classifyMenu`（`ForwardLogic.kt:140-146`）只被自绘路径 `injectWhenReady`（`ForceForward.kt:550-616`，调用点 :553）使用，而自绘路径的入口 `installPopupInject()` 在 `install()` 里被注释停用（`ForceForward.kt:236  // installPopupInject()`），生产不执行。
- `injectWhenReady` 与 `findMenuBox`（:652-680）、`addItems`（:687-737）整套自绘逻辑是停用的死代码（保留供降级，`docs/_r73_l3_review.md:75`）。
- 原生 add 路径 `onShowGridMenu`（:317-380）经 grep 核实**没有调用** `classifyMenu`：它只做「验签名 → 取 `y` menu → `NativeMenuGate.shouldInject` 四条件 → `findItem` 幂等 → `menu.add` → 打日志」。`menu.add` 的放行与消息类型/菜单词归类无关。
- 因此**不存在「某些菜单类型被 classifyMenu 挡住而不 add」的情况**。分类词表 `CHAT_MENU_WORDS`（`ForwardLogic.kt:116-120`）只影响停用的自绘注入与探针，不影响本轮入口存在性。

> 结论（问题4）：原生 add 不依赖 classifyMenu；菜单归类不会造成本轮「有些有、有些没有」。可排除该方向。

---

## 5 4000ms 时间窗是否可能误伤

四条件中时间项 `now - lastPressAt <= 4000ms`（`NativeMenuGate.kt:76`，同 `ForceForward.LONG_PRESS_WINDOW_MS:136`）。`lastPressAt` 的更新时机见 §3.1——每次 `performLongClick` before 都刷新为 now（:260）。

分析：

- **正常长按**：trap 在 `View.performLongClick` before 就刷新 `lastPressAt`（链路最早期，`docs/_r73_native_menu_inject.md:34/52`），菜单在长按后很快由 `q.p` 构建并 before-hook add，间隔通常毫秒级（`[真机]` 门禁日志 `age=151ms`，`docs/_r73_l3_review.md:56`）⇒ 远小于 4000ms，不误伤。
- **可能误伤的窄场景** `[推断]`：用户**持续按住直到超过 4s 才由宿主弹出菜单**（多数长按菜单在长按阈值约 500ms 时就弹，不会拖到 4s；但若某消息类型的菜单在松手时才弹，或菜单被延迟/二次触发，`age` 可能 >4000ms ⇒ 门禁挡死）。这类「按住特别久才弹菜单」正好会表现成「有些消息没有入口」。属可真机验证的次要嫌疑。
- **lastPressAt 被别的 View 刷新拉新的风险**：同一次手势里 View 链多个 View 连续 performLongClick，lastPressAt 取最后一次（:260），只会让 age **更小**（更放行），不会误伤。
- **与 TTL 的关系**：门禁 add 在 4s 内，`pressedView` 的 10s TTL 不会在此触发（`docs/_r73_l6_review.md:127-128` 已确认）；10s TTL 只影响**点击后**快照（R73-L6 已改 `staleViewForProbe` 绕过），与入口是否出现无关。

> 结论（问题5）：4000ms 窗对常规长按不会误伤；仅在「按住 >4s 才弹菜单」的少见手势下可能挡死，属次要嫌疑，可用 gate closed 日志的 `age=` 值直接判定（若失败时 `age` >4000 即证实）。

---

## 6 综合排序：最可能的 3 个根因 + 真机验证判据 + 修法方向

> 统一的前置观测手段：`op=forward sub=stat` 看 `nativeInjectedOk` / `FF NATIVE added` / `FF NATIVE installed wg4.q.p x1`（`docs/_r73_l3_review.md:118`）；门禁拒绝时看节流日志 `FF NATIVE gate closed enabled=.. age=.. talker=.. ref=..`（`ForceForward.kt:344-347`，节流 1s）。这条日志是本轮最重要的「分诊台」：`ref=false` 指向根因1，`ref=true 但仍不开` 几乎不会发生（四条件已排除 talker），需要看 `age` 是否 >4000（根因3）。

### 根因①（概率最高）：长按 View 取不到 h9（hasRef=false）——按消息类型/文字区域分化

- **机制**：门禁四条件里唯一随「这一条消息」波动的硬条件。任何使 `pressedView()` 为 null 或 `msgRefOf` 在失败点 A/B/C/D 返回 null 的情况，都直接挡死 add（§2.1、§3.2）。典型：图片卡片（明确不走 trap）、系统提示/拍一拍/引用块等非标准消息 item、同 burst 第一个 View 是内层子 View、View 被 GC/回收重绑、getter 混淆漂移。
- **真机可执行的验证判据**：
  1. 对「有入口」和「没入口」的两条消息，各长按一次，抓 logcat：
     - 看 `FF NATIVE gate closed ... ref=false age=<4000` ⇒ 证实 hasRef 挡死（失败点在 ref）。
     - 看是否出现 `FF NATIVE added` ⇒ 成功样本。
  2. 用只读探针 `op=forward` 的 `msgsnap` / `snap`（`ForceForward.kt:1661 msgsnapReport`、:1598 snapReport）对两条消息分别取快照，对比 `view=`/`list=`/`pos=`/`h9=` 字段：失败样本应出现 `view=none` 或 `list=none`/`pos=-1`/`h9=none`（图片样本已知 `view=none` 且 ageMs 不更新，`docs/_r71...:66`）。
  3. 对照动作：在**同一消息**里分别长按「纯文字气泡中部」与「链接/引用/@文字/表情」区域——若不同文字 region 成败不同，坐实 §3.2-①「同 burst 第一个 View 非消息根」。
- **建议修法方向（仅描述，不改码）**：让门禁的 hasRef 判定更鲁棒——例如 trap 命中时**沿 parent 链向上寻找第一个能取到 h9 的 View**（而非只认 burst 第一个 deepest）；或对「能进标准消息长按但 `msgRefOf` 失败」的消息，退而用消息 item 的 `tag`/子 View 文本等次要素取证；对图片等自定义手势消息，补 hook 其 onLongClick/双击路径以刷新 pressedView。

### 根因②（概率中高）：微信在部分消息/场景连菜单都不弹 ⇒ add 点 `q.p` 根本不被调用

- **机制**：整条注入依赖 `wg4/q.p(II)Z` before 被触发。若某消息类型长按后宿主不构建/不显示 PopupWindow（场景 b），`onShowGridMenu` 根本不执行，门禁日志也不会出现，`nativeInjectedOk` 不置位——表现同样是「没有入口」。`[真机]` `docs/_r73_l3_review.md:75`、`docs/_r73_native_menu_inject.md:25/187/216` 与 `docs/_r73_blocked_forward_forensics.md:187-197` 明确这是已知失败场景且当前无降级。另 `q.p` 早退检查（`e()/isShowing()/d()/swiping`，`docs/_r73_l3_review.md:90`）也会让 before-hook 即便触发也无法 add。
- **真机可执行的验证判据**：
  1. 长按失败消息时抓 logcat：若**既没有** `FF NATIVE added`、也**没有** `FF NATIVE gate closed`（说明 hook 没进到门禁），并 `uiautomator dump` 确认无菜单 ListView ⇒ 场景 b，`q.p` 未调用。
  2. 抓 `FF NATIVE no y-menu on q instance`（:328）或 `FF NATIVE hook threw`（:308）⇒ hook 进了但取 menu/反射失败。
  3. `op=forward sub=stat` 看 `popupsSeen` 计数是否随失败样本增长（探针 :538）。
- **建议修法方向（仅描述）**：为场景 b 另开「长按 View 直取 h9 + ForwardPicker」兜底入口（不依赖 PopupWindow），文档 §6 已规划；或 hook 更早的消息长按分发点（`wg4/a.onItemLongClick` / `wg4/b.onLongClick`，`docs/_r73_native_menu_inject.md:36`）在菜单构建前 add。

### 根因③（概率中低）：4000ms 时间窗 / 同 burst 首个 View 的时序误伤

- **机制**：两类时序问题——(a) 用户按住 >4s 才弹菜单 ⇒ `age>4000` 被时间窗挡（§5）；(b) 同 burst 里第一个 performLongClick 命中的非消息根 View 被记为 burstDeepest，后续能取 h9 的 View 不覆盖（§3.2-①），造成按「同一消息不同文字区域」分化的成败。
- **真机可执行的验证判据**：
  1. 失败时看 `FF NATIVE gate closed ... age=`：若 `age>4000` ⇒ 时间窗误伤坐实（根因③a）；若 `age<4000` 且 `ref=false` ⇒ 归到根因①。
  2. 同一条消息分别快速长按 / 按住 5s 再松手，对比成败 ⇒ 验证时间窗。
  3. 同一消息不同文字区域长按（链接/引用/表情 vs 纯文字），看成败与 `ref=` ⇒ 验证③b。
- **建议修法方向（仅描述）**：时间窗改为「以宿主实际弹菜单时刻为基准」而非 `lastPressAt`，或适度放宽窗口但仍保留防其它浮层误注入；burstDeepest 改为「同 burst 内若后来 View 能取到 h9 则升级覆盖」，避免锁死在首个非消息 View。

### 三根因概率排序与判据速查

| 排序 | 根因 | 一句话判据（失败样本看哪条日志/做哪个动作） |
|---|---|---|
| ① | hasRef=false（View 取不到 h9，按消息类型/文字区域分化） | `gate closed ... ref=false age<4000`，或 msgsnap 出现 `view=none`/`pos=-1`/`h9=none` |
| ② | 菜单不弹 ⇒ `q.p` 不调用 | 失败时**无** `gate closed` 也无 `FF NATIVE added` + UI dump 无菜单 ListView |
| ③ | 时间窗/burst 时序误伤 | `gate closed ... age>4000`；或同消息「快按有、久按无」/「纯文字有、链接区域无」 |

> 三根因可同时存在、按消息类型各自命中。优先用「分诊台日志」把每个失败样本归到 ①②③ 之一，再按对应方向修。

---

## 7 证据不足声明（未编造任何 dex 地址/类名）

- 未逐类型反汇编 dex 确认「带链接/表情/引用/文件/语音/拍一拍/系统提示/撤回」各自长按走的具体 View 类；项目 `_cache/_s9dump/classes*.txt` 等反汇编产物未记录这些类型的长按 View 链，故 §1.2 表格对除纯文本、图片外的类型标注「依据不足」。
- 未实机跑 logcat 验证「有些文字有、有些没有」的当前样本；§6 的判据是基于源码与既有真机文档（`_r71`、`_r73_l3/l6`、`_r73_blocked`）推导的可执行验证方案，非本次实测结论。
- 未确认 `q.p` 的 `e()/isShowing()/d()/swiping` 早退在失败群体中的命中比例（`docs/_r73_l3_review.md:90` 仅记风险）。
- 未验证微X共存对我方**原生** add 的影响（既有证据只证明其影响停用的自绘路径）。

---

## 8 焦点修正专章：为什么聊天页「最新一条消息」长按不注入，历史消息可以

> 用户补充的决定性现象：同一聊天窗口里，**最新一条消息**没有「转发（模块）」按钮，历史消息有。
> 这是「位置（最后一条）」维度的差异，不是消息类型维度。本章逐条回答 team-lead 的 5 个问题，保留前文结论。

### 8.0 首要结论：源码里不存在任何「最新一条消息」专门分支

grep + 通读确认：`ForceForward.kt` 的 trap（:247-267）、`msgRefOf`（:843-905）、门禁（:336-351）全部是**位置无关**的通用逻辑——对列表里任意一条消息走完全相同的路径。**没有任何 `last`/` newest`/position==count-1 之类的判断**。
⇒ 「最新 vs 历史」的差异**不可能来自源码分支**，只能来自**运行时数据**：要么最新消息那条 View 在 `msgRefOf` 链上取不到 h9（`hasRef=false`），要么它的长按没更新/没命中 trap，要么它弹出的菜单根本不是 `q.p` 那条。下文按此三类展开。

### 8.1 问题1：最新消息是否走 trap 的两个 performLongClick？是否用不同 View 类/holder 状态？

- **hook 面**：trap hook 的是框架 `android.view.View` 的两个 `performLongClick` 重载（§1.1，真机 x2），对所有 View 子类生效。因此**只要最新消息的长按最终分发到标准 `View.performLongClick`，trap 就会命中**，与"最新/历史"无关。
- **最新消息走不同 View 类/holder 状态**：`[依据不足]` 项目所有 dex 反汇编产物（_s9dump、_chatroom4_dis 等）与真机日志都**没有**逐条记录"最新消息的 holder 类型/动画态/未读态/发送中态"与长按路径的对应关系。唯一相关的已知事实是「新消息可能带未读分隔线/时间条装饰 View」——但本项目材料未坐实。
- **可确定的运行时差异点（源码）**：trap 里 `burstDeepest` 只在 `now - burstStartAt > BURST_GAP_MS(500ms)` 时更新（:255-258）。若用户**快速连续**长按（先历史、后最新，间隔 <500ms），最新消息那次**不会更新** `burstDeepest`，它仍指向历史消息 View（结果反而"有按钮"，非本现象）；反之单次长按最新消息会正常更新。⇒ 时序本身不解释"最新缺失"，但 `burstDeepest` 指向的具体 View 是谁是分水岭（见 8.4）。

### 8.2 问题2：为何 `msgRefOf(pressedView())` 对最新消息取不到 h9？——纠正"h9 未绑 View tag"假设

**先纠正 team-lead 的假设**：`msgRefOf`（:843-905）**完全不读 View 的 tag 或 id**。它取 h9 靠 `adapter.getItem(pos)`（:885-887）：parent 链找列表 → `positionOf` 定位 position → adapter.getItem → 按名反射字段。`docs/_r16_g2_msgsnap.md:227` 已明确「消息 item 基类 `viewitems/a` 没存 h9，h9 存在 40+ 子类里，但路线 A 不需要从 View 反解 h9，adapter 会重新给」。
⇒ **"最新消息的 h9 还没绑定到 View tag/id"在本代码里不是可能的机制**——代码从不读 tag。真正决定成败的是「最新消息那条 View 能否定位到一个有效的 `pos` 并让 `getItem(pos)` 返回非 null」。

`msgRefOf` 对最新消息可能失败的具体节点（`[源码]` 机制 + `[推断]` 归因）：

1. **失败点 B-a：`positionOf` 的 recycler 分支找不到 itemRoot（:919-926，return -1）**。该分支要求从长按 View 沿 parent 链 12 层内找到「parent === list 的那一层」。若最新消息长按命中的 deepest View 落在一个**非标准容器**（如刚 bind 未完成、动画中的气泡、装饰器 View），parent 链可能接不到 list ⇒ pos=-1 ⇒ hasRef=false ⇒ 门禁挡死。
2. **失败点 B-b：recycler 的 `getPositionForView` 恒 -1（:935-937，listview 分支）**。`docs/_r16_g2_msgsnap.md:106-109/437-445` 已坐实 `ChattingRecyclerView.getPositionForView` 是恒 -1 空桩，只有 `F0/G0` 混淆链可用；若最新消息所在列表被 `listKindOf` 误判成 listview/unknown 而走了 `getPositionForView`，pos=-1。这是已知坑，但对所有消息一致，除非最新消息处于不同列表容器。
3. **失败点 B-c：position 越界 / `getItem(pos)` 返回 null（:885-887）**。`docs/_r21d_badge_chain.md:17` 反汇编铁证：聊天 adapter 的 `getItem(pos)` 自带 `pos - headerCount` 换算（q0.getItem: `g.getItem(pos - g0())`），正常消息坐标可用。但若 `positionOf` 对最新消息返回的 pos 恰好落在**列表末尾的 footer/装饰项**或越界，`getItem` 返回 null ⇒ hasRef=false。**最新消息位于列表最底，是唯一可能紧邻 footer/边界的那一条**——这是"最新特殊"的合理候选 `[推断]`，需真机 `pos` 字段坐实。
4. **失败点 A/C/D**：list 找不到（:868）、adapter 取不到（:882）、getItem 抛异常（:886）——这些对最新/历史一致，不解释位置差异。

### 8.3 问题3：trap 对最新消息记录了吗？gate closed 是 `ref=false` 还是 age 超窗？

- `pressedView()`/burstDeepest 的记录条件见 §3.1：单次长按最新消息会正常写入 `burstDeepest=最新View` + `lastPressAt=now`。
- **分诊判据（真机可执行）**：长按最新失败消息，看节流日志 `FF NATIVE gate closed enabled=.. age=.. talker=.. ref=..`（:344-347）：
  - `ref=false` 且 `age` 很小（<4000）⇒ 证实 trap 命中（lastPressAt 已更新），但 `msgRefOf` 取不到 h9 ⇒ 归 8.2，重点看失败点 B。
  - `age` > 4000 ⇒ 时间窗误伤（§5），但长按到弹菜单通常毫秒级，可能性低。
  - **既无 `gate closed` 也无 `FF NATIVE added`** ⇒ `onShowGridMenu`/门禁根本没进来 ⇒ 归 8.4（菜单路径不同或 q.p 没被调）。
- 用只读探针 `sub=msgsnap`（`ForceForward.kt:1661`，输出格式见 `docs/_r16_g2_msgsnap.md:472-475`）对最新消息取值，与历史消息对比关键字段：
  - `view=none` ⇒ trap 没记或 WeakReference 被 GC；
  - `attached=false` ⇒ View 已摘下，`getPositionForView` 必 -1（`docs/_r16_g2_msgsnap.md:492-498`），须缩短长按间隔重测；
  - `pos=-1` ⇒ 定位失败（区分 `posVia=recyclerStub`=已知空桩 vs 真失败）；
  - `list=none`/`h9=none` ⇒ 失败点 A/D。
- **注意** `msgsnap` 走 `staleViewForProbe`（忽略 TTL）+ `ageMs`，真机 ageMs 常 >10s 属正常（`docs/_r16_g2_msgsnap.md:481-490`），别误判。

### 8.4 问题4：最新消息是否走不同菜单路径（`wg4/q.p` 之外）？

- 微信长按菜单的标准构建链是 `View.performLongClick → wg4/a.onItemLongClick → r3.clear() → g0/onCreateContextMenu → q.n→q.o→q.p`（`docs/_r73_native_menu_inject.md:31-50`）。另有 `wg4/b.onLongClick` 入口（`docs/_r73_l3_review.md:33`）。
- 若最新消息（如未读/新消息/特殊态）长按走的是**另一套菜单**（气泡菜单、不同 PopupWindow、或 `q.p` 早退：`e()/isShowing()/d()/swiping`，`docs/_r73_l3_review.md:90`），则 `onShowGridMenu` 不执行 ⇒ 不会有 `gate closed` 也不会有 `added` ⇒ 表现正是"最新消息无按钮"。
- `[依据不足]` 现有 dex/日志**没有**记录"最新一条消息长按走的是不是不同菜单路径"。这是与 8.2 并列的主要假设，必须靠真机日志（有无 gate closed/added + UI dump 菜单文案）区分。

### 8.5 问题5：最小修法方向（只描述，不改码）

按根因归类给方向：

| 若真机判据指向 | 最小修法方向 |
|---|---|
| `ref=false`（8.2，取不到 h9） | 在 `msgRefOf` 失败点 B 加**降级取 ref 路径**：trap 命中时除记 View 外，同步缓存一条从菜单构建点 `g0(r3,view,info,h9)` 拿到的 h9（该 hook 第 4 参即 h9，`docs/_r73_native_menu_inject.md:40/83`），门禁 `hasRef` 改为「View 链 h9 或 g0 缓存 h9 任一非空」；最新消息即便 View 定位失败也能用它放行。 |
| `pos=-1`/越界（8.2-B） | `positionOf` 的 recycler itemRoot 循环（:919-926）在 12 层内找不到时，补一次「从 v 向下找已知消息 item 容器再回跳」；或对 `getItem(pos)` 越界时改用 `getCount()-1`（最后一条）兜底并校验 msgId 非空。 |
| trap 没捕获（8.1） | 补 hook 点：除 `View.performLongClick` 外，再 hook `wg4/a.onItemLongClick` / `wg4/b.onLongClick`（dex 已定位，`docs/_r73_native_menu_inject.md:36`），在其 before 直接用第 2 参 view + info.position 记 pressed view/ref，绕开 performLongClick 的分发差异。 |
| 菜单路径不同（8.4） | 若最新消息菜单不过 `q.p`，把注入点从 `q.p` 前移到更早的公共入口 `q.n()/q.o()` 或 `r3.clear()`/`g0` after（`docs/_r73_native_menu_inject.md:81/83`），覆盖所有菜单构建路径；注意加幂等与更严门禁防误注入。 |
| `age`>4000（8.3） | 放宽时间窗或改用菜单构建时刻为基准（§6 根因③方向）。 |

### 8.6 针对新焦点的根因排序（替换/细化 §6 的概率判断）

1. **（最高）最新消息 `msgRefOf` 取不到 h9 ⇒ `ref=false` 门禁挡死**：尤以 `positionOf` 对末端消息返回 -1/越界（`getItem` 落 footer）为甚。判据：gate closed `ref=false` + msgsnap `pos=-1`/`h9=none`/`attached=false`。
2. **（中高）最新消息长按走不同菜单路径 / `q.p` 早退 ⇒ add 点不可达**：判据：无 gate closed 也无 added + UI dump 菜单结构异常。
3. **（中低）时序/弱引用**：500ms burst 卡住、WeakReference 被 GC、`age` 超窗——这些对位置不敏感，单独解释"最新缺失"的概率较低，但可叠加。

> 三假设可由一次真机操作分流：长按最新失败消息 → 看 `gate closed` 的 `ref=`/`age=` 有无 + `msgsnap` 的 `view/attached/pos/h9`。`[依据不足]` 处（最新消息 holder 类型、菜单路径）不做编造，留待真机补证。

[DONE]
