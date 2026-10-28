# R31 折叠置顶聊天：分组行不跟着收起 —— 修复记录

> 用户需求：① 分组置顶不要右侧「置顶」两字；② 分组置顶要能被「折叠置顶聊天」收入。
> 真机反馈：「折叠时分组行停在最上面，没跟着收起」。
> 行号均为本次改动后的当前行号。

## 0 结论摘要

**真因（① 字面证据）**：默认配置下 `chat.group_row_enabled = "true"`
（`Keys.kt:291`）⇒ `GroupBar.build()` 走 `MODE_NONE` 直接 return
（`GroupBar.kt:397-403`），`GroupBar.syncPinFold()` / `applyFold()` / `FOLDED_H`
那一整套是**死代码**。用户看到的分组行是 `ConvFilter.GroupedAdapter` 经
`ConvLayout.build()` 插入的行，而它（`ConvLayout.kt:119`）把 pinned 分组行
**硬插在最前**，与微信置顶折叠毫无联动 ⇒ 折叠后仍停在最上面。

**修复（4 处）**：
1. `ConvLayout.build()` 加 `pinFolded: Boolean = false`，折叠态不插 pinned 分组行。
2. `ConvLayout.ConvEntry` 加 `unreadable` 标记，把「读不到对象」与「对象不是会话」分开。
3. `ConvFilter.readSnaps()` 对 `getItem == null` 的占位打上 `unreadable = true`。
4. `ConvFilter` 在 `install(list)` 挂布局监听**扫已布局文字**判折叠态，翻转时触发重建；
   `rebuild()` 把判据传给 `ConvLayout.build(pinFolded = ...)`。

**等级**：真因 ① 字面证据；判据本身 ② 强推测（建立在「折叠行是 inner adapter 的真实
item」这一前提上，需真机证伪；扫文字那一路不依赖该前提，是主判据）。

## 1 曾经修错的方向（留档，避免下一轮重走）

最初判断「折叠时 `refresh() → animateLaneHeight()` 把 lane 从 `FOLDED_H` 撑回自然高度，
而 `syncPinFold()` 状态未翻转就不重设 ⇒ 不自愈」。这个分析在 **GroupBar 内部是成立的**，
但它服务的是「关掉『启用分组行』开关」后的旧机制 —— 默认用户走 `MODE_NONE`，
`bar.lane` 从未被 addView 到任何窗口，改它的高度没有视觉结果。

已保留这部分修复（`GroupBar.kt` 的 `syncPinFold` 自愈 + 两条可区分日志）——
**后改为不提交**：它对默认用户零效果，而留着会让「折叠」这件事有两套实现、两套判据，
下一轮排查更容易走错。`GroupBar.kt` 保持原样，旧机制的这个 bug 记进遗留项（§7.4）。

## 2 最终实现

### 2.1 `data/ConvLayout.kt`

```kotlin
data class ConvEntry(val wxid: String, val timeMs: Long = 0L, val unreadable: Boolean = false)

fun build(convs, groups, members, enabled: Boolean = true, pinFolded: Boolean = false): Layout {
    ...
    if (!pinFolded) for (g in pinned) rows.add(ConvRow.Group(g.id))
```

- `pinFolded` 默认 `false` ⇒ 现有 15+ 处调用点与单测行为**逐字节不变**。
- 折叠时只跳过「分组行」的插入；`ownerOf` 仍然包含 pinned 组 ⇒ pinned 组的成员会话
  照旧被收纳隐藏。**不能**因为折叠就把成员会话放出来（那会造成同一批人既在列表里
  又在分组里）。

### 2.2 `feature/ConvFilter.kt`

```kotlin
// readSnaps：区分两种空 wxid
if (item == null) { out.add(ConvLayout.ConvEntry("", unreadable = true)); continue }

// rebuild：两路判据，扫文字优先
ConvLayout.build(snaps, specs, members, enabled = true,
    pinFolded = pinFoldedByView ?: PinFoldLogic.detectPinFolded(snaps))

// install：折叠态靠扫文字判（布局回调，100ms 节流）
list.addOnLayoutChangeListener { ... walkTexts(list) { ... } ... 翻转则 fa.onDataChanged() }
```

扫文字判据（微信 8.0.48 文案已实查，见 §3.1）：
- `"折叠置顶聊天"` ⇒ 展开态
- 长度 ≤ 12 且以 `"个置顶聊天"` 结尾 ⇒ 折叠态
- 都扫不到 ⇒ 保持上一次结论（首次按展开处理）

### 2.3 `feature/PinFoldLogic.kt`（数据层兜底判据，零 Android 依赖）

```kotlin
fun detectPinFolded(convs: List<ConvLayout.ConvEntry>): Boolean {
    val first = convs.firstOrNull() ?: return false
    if (first.unreadable) return false          // getItem==null 占位 ⇒ 判不出
    if (first.wxid.isNotBlank()) return false   // 顶部是真实会话 ⇒ 展开
    return convs.drop(1).any { it.wxid.isNotBlank() }  // 需后续真实会话佐证
}
```

**为什么收紧到「只看首项」**：折叠行必然在置顶区首位。若改成「任意位置出现空 wxid
非占位项即折叠」，微信往列表里插别的特殊行时会误判成折叠 ⇒ 置顶分组行被藏掉，
用户进不了分组。假阴性（折叠判成展开）= 现状，可接受；假阳性代价高得多。

## 3 判据依据与已排除项

### 3.1 文案（已排除错误可能）

`aapt2 dump strings _cache/base.apk` 实查微信 8.0.48：

```
String #20938 : %d个置顶聊天
String #20939 : 折叠置顶聊天
```

与判据一字不差。**这两句是资源字符串，dex 字符串池里搜不到**（`_r28_astr.py 置顶`
无命中），只能从 APK 资源取 —— 记下来，免得下一轮又去 dex 里白找。

### 3.2 为什么还要扫文字（不只靠数据层）

折叠行究竟是不是 `wrapped.getItem(i)` 能取到的 item，目前**无法静态定论**：
若它是微信自己 addHeaderView 的 header，就不进 adapter 数据，数据层判据恒返回 false。
扫文字不依赖这个前提，所以作为主判据；数据层作为扫不到时（首帧 / 文案变异）的兜底。

### 3.3 短路不会挡住折叠状态变化

`GroupedAdapter.rebuild()` 的 `orderDirty = fresh.size != snaps.size ||
ConvLayout.orderChanged(snaps, fresh)`（`ConvFilter.kt:933`）在折叠/展开时必然为 true
（条数变了，或首项从有值变空；`orderChanged` 对空 wxid 一律返 true）⇒ 必走 full rebuild
⇒ `ConvLayout.build()` 被重新调用。**无需**把折叠态塞进 `configStamp`。

## 4 单测

新增 18 条，全绿（合计 783 条 / 42 类 / 0 失败）：

- `ConvLayoutTest` 5 条：折叠隐藏 pinned 行、默认参数等于展开态（防回归）、无 pinned
  分组时折叠是 no-op、折叠不放成员会话出来、折叠不影响筛选路径。
- `PinFoldLogicTest` 13 条：`detectPinFolded` 各分支（首项折叠行 + 后续真实会话、
  首项真实会话、非首项、空表、占位首项、只有一条折叠行、整列读不到）+ `shouldInsertPinned`
  + 与 `ConvLayout.build()` 的一致性。

## 5 变异验证（**实跑**，非推演）

| 变异 | 结果 |
|---|---|
| `if (!pinFolded)` → `if (!pinFolded \|\| true)` | 红 2 条：`pinFolded_hides_pinned_group_rows`、`pinFolded_doesNotLeakPinnedMemberConvs` |
| 删掉 `if (first.unreadable) return false` | 红 1 条：`unreadablePlaceholderAtHeadIsNotFolded` |

两处均还原后复绿（783/0/0）。

## 6 真机怎么判

装包后（`assembleDebug` EXIT=0，APK 3.49MB）：

1. **成功**：折叠「置顶聊天」⇒ 置顶分组行跟着消失，列表顶部只剩「N个置顶聊天」；
   点开 ⇒ 分组行回到原位。日志：`K7 pinFold(view) folded=true -> rebuild`，
   紧随一次 `ConvLayout.build`。
2. **判据没命中**（折叠行不是 adapter item 且扫不到文字）：分组行仍停最上面，且
   **没有** `K7 pinFold(view)` 日志 ⇒ 说明扫文字也没捞到，需要看 `walkTexts` 深度
   （当前 8 层）和那行的真实视图结构。
3. **判据命中但没生效**：有 `folded=true` 日志但分组行还在 ⇒ 查该分组是不是 `pinned`
   （非置顶分组行不受影响，本来就不该跟着折叠）。

## 7 遗留风险

1. **前提待证**：折叠行是不是 adapter 真实 item。真机日志能一次定性（见 §6.2）。
2. **扫文字深度 8 层**：微信若把「N个置顶聊天」埋在更深的子树里会扫不到，表现为
   判据永不命中（= 现状，不会误伤）。
3. **首帧时序**：首次进入会话列表时还没布局过，`pinFoldedByView` 为 null，
   退回数据层判据；若数据层也判不出，按展开处理（宁可多显示，不藏入口）。
4. **旧机制（关掉「启用分组行」开关）未修**：那条路径下 `GroupBar.syncPinFold()`
   确实有「被 `refresh → animateLaneHeight` 撑回自然高度后不自愈」的 bug（② 强推测，
   代码链已读通、未真机复现）。本次不动它，需要时按 §1 的分析加两行自愈即可。

---

[DONE]
