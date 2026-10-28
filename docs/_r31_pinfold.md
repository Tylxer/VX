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

**修复（5 处）**：
1. `ConvLayout.build()` 加 `pinFolded: Boolean = false`，折叠态不插 pinned 分组行。
2. `ConvLayout.ConvEntry` 加 `unreadable` 标记，把「读不到对象」与「对象不是会话」分开。
3. `ConvFilter.readSnaps()` 对 `getItem == null` 的占位打上 `unreadable = true`。
4. `ConvFilter` 在 `install(list)` 挂布局监听**扫已布局文字**判折叠态，翻转时触发重建；
   `rebuild()` 把判据传给 `ConvLayout.build(pinFolded = ...)`。
5. ⭐ **折叠态进 `GroupedAdapter` 的配置指纹**（`PinFoldLogic.configStamp`）——
   这是真机才暴露的第二个根因：不进指纹会被 rebuild 的短路直接吞掉，
   日志打了 `folded=true -> rebuild` 而列表纹丝不动（详见 §2.4）。

**等级**：两处真因均 ① 字面证据 + 真机复现/修复验证（§6）；
「折叠行是不是 inner adapter 的真实 item」仍 ② 强推测，但**不影响功能** ——
扫文字那一路是主判据且已真机跑通（深度实测 4 层，上限 8 层）。

**真机状态**：✅ 四条用例全过（冷启保持、展开、收起、折叠态切 Tab 往返）。

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

### 2.4 ⭐ 折叠态必须进 `rebuild` 的配置指纹（真机才暴露的第二个根因）

```kotlin
// PinFoldLogic.configStamp(...) 抽成纯函数，末项就是折叠态
fun configStamp(n, enabled, filterId, showUnread, membersHash, groupsHash, pinFolded: Boolean?): String =
    "$n|$enabled|$filterId|$showUnread|$membersHash|$groupsHash|$pinFolded"
```

第一版漏了这一项，真机现象很迷惑：日志明明打了
`K7 pinFold(view) folded=true -> rebuild`，置顶分组行却**纹丝不动**。

原因是 `rebuild()` 开头有短路（`ConvFilter.kt:1004`）：

```kotlin
if (!membersDirty && !orderDirty && configStamp == lastStamp && snaps.isNotEmpty()) { ... return }
```

折叠/展开时会话条数、顺序、分组、筛选态**全都没有变化**，只有折叠态变了。
不进指纹 ⇒ 指纹相同 ⇒ 直接 return，`ConvLayout.build` 根本没被调用。

⚠️ 同族坑：`showUnread` 当年也是这么漏的（代码注释里就写着）。凡「只影响布局、
不影响数据」的开关，都必须进配置指纹。

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

新增 21 条，全绿（合计 786 条 / 42 类 / 0 失败）：

- `ConvLayoutTest` 5 条：折叠隐藏 pinned 行、默认参数等于展开态（防回归）、无 pinned
  分组时折叠是 no-op、折叠不放成员会话出来、折叠不影响筛选路径。
- `PinFoldLogicTest` 16 条：`detectPinFolded` 各分支（首项折叠行 + 后续真实会话、
  首项真实会话、非首项、空表、占位首项、只有一条折叠行、整列读不到）+ `shouldInsertPinned`
  + 与 `ConvLayout.build()` 的一致性。
  **其中 3 条是「配置指纹」守门用例**（§2.4 那个真机 bug）：
  `stampChangesWhenOnlyPinFoldedChanges`（null/false/true 三者两两不等）、
  `stampIsStableForSameInputs`、`stampStillCoversTheOldSixFields`（老六项任一变化必须改指纹）。

## 5 变异验证（**实跑**，非推演）

| 变异 | 结果 |
|---|---|
| `if (!pinFolded)` → `if (!pinFolded \|\| true)` | 红 2 条：`pinFolded_hides_pinned_group_rows`、`pinFolded_doesNotLeakPinnedMemberConvs` |
| 删掉 `if (first.unreadable) return false` | 红 1 条：`unreadablePlaceholderAtHeadIsNotFolded` |
| `configStamp` 去掉 `\|$pinFolded` | 红 1 条：`stampChangesWhenOnlyPinFoldedChanges` |
| `configStamp` 写成 `${pinFolded == true}`（null 与 false 混同） | 红 1 条：同上 |

四处均还原后复绿（786/0/0）。第 3、4 条就是 §2.4 那个真机 bug 的守门变异。


## 6 真机验证（**已实跑，2026-10-28 21:14–21:17，机型 467cf5bc / 微信 8.0.48**）

装包：`assembleDebug` EXIT=0，APK 3,489,632 B，`pm lastUpdateTime=21:14`，冷启两次。

### 6.1 实测结果（四条全过）

| # | 操作 | 期望 | 实测 |
|---|---|---|---|
| 1 | 冷启后（记忆态=折叠，`19个置顶聊天`） | 分组行不出现 | ✅ 无 G1/G2/G3 |
| 2 | 点 `19个置顶聊天` 展开 | 分组行回到置顶区首位 | ✅ G1/G2/G3 出现 |
| 3 | 点 `折叠置顶聊天` 收起 | 分组行消失 | ✅ 全部消失 |
| 4 | 折叠态下切到通讯录再切回 | 保持折叠、分组行仍不出现 | ✅ 无重影、无复现 |

日志（每次翻转各一对，`apply` 那条是新增的「真正传进 build」的证据）：

```
21:15:20.026  K7 pinFold(view) folded=false -> rebuild
21:15:20.346  K7 pinFold apply folded=false src=view
21:16:46.614  K7 pinFold(view) folded=true -> rebuild
21:16:46.924  K7 pinFold apply folded=true src=view
```

### 6.2 顺带确认的两件事

- **文案判据与真机逐字一致**，且**两种状态的文案不同**（这点比原设计假设更精确）：
  - 折叠态：置顶区首位一行 `N个置顶聊天`（本次 `19个置顶聊天`）
  - 展开态：`N个置顶聊天` 那行消失，改在置顶区**末尾**出现 `折叠置顶聊天`
  ⇒ 两个判据分支都不会误触，`"折叠置顶聊天"` 不是设置页文案。
- **折叠行在视图树里的深度**：ListView 往下 4 层（`16 LinearLayout → 17 → 18 → 19 TextView`），
  远小于 `walkTexts` 的 8 层上限，所以扫得到。

### 6.3 排查时怎么区分三种失败

1. **判据没命中**：折叠后分组行还在，且**没有** `K7 pinFold(view)` 日志 ⇒ 扫文字没捞到，
   要查那行的真实视图层级与深度。
2. **命中但没生效**：有 `folded=true` 日志、**没有** `pinFold apply ...` ⇒ rebuild 被短路
   （指纹漏项，见 §2.4）。本次第一版就是这一种。
3. **生效了但仍不对**：两条日志都有，分组行还在 ⇒ 查该分组是不是真 `pinned`
   （非置顶分组行本来就不该跟着折叠）。

## 7 遗留风险

1. **折叠行到底是不是 adapter item 仍未定论**（②强推测）。真机证明的是「扫文字这条
   路径可用」，数据层兜底 `detectPinFolded` 是否也能独立命中**没有被单独隔离验证**
   （两次都优先走了扫文字）。不阻塞功能，但下次若微信改文案，兜底判据可能是哑的。
2. **扫文字深度 8 层**：本次实测只需 4 层，余量充足；微信若加深层级会静默失效
   （表现 = 分组行不跟随，不会误伤别的功能）。
3. **首帧时序**：首次进入会话列表时还没布局过，`pinFoldedByView` 为 null，退回数据层
   判据；若数据层也判不出，按展开处理（宁可多显示，不藏入口）。
4. **旧机制（关掉「启用分组行」开关）未修**：那条路径下 `GroupBar.syncPinFold()`
   确实有「被 `refresh → animateLaneHeight` 撑回自然高度后不自愈」的 bug（② 强推测，
   代码链已读通、未真机复现）。本次不动它，需要时按 §1 的分析加两行自愈即可。
5. **测试期发现的旁支问题（未修）**：会话列表里有一行显示原始 wxid
   `wx__c_19dae8ea92a` 而不是昵称 —— 属于「昵称兜底」缺口，与本次折叠无关，记在这里
   免得丢。

---

[DONE]
