# R34 · 折叠/展开「顿」「跳」根因分诊

> 子代理：general-purpose-1（Pro 档：根因推理 + 判据设计）
> 时间：2026-10-28
> 上游必读：`docs/_r32_pinfold_smooth.md`（§8.3 / §9）、`docs/_r31_pinfold.md`
> 独占文件：`feature/ConvFilter.kt`、`data/ConvLayout.kt`、`feature/PinFoldLogic.kt`、`feature/GroupRowView.kt`、本文档
> 禁止：动 adb / 真机 / gradle；禁改 `AutoHdImg.kt` / `VoiceFileGuard.kt` / `MediaGuardLogic.kt` / `GroupDb.kt` / `Sns*`

---

## 0. 现象与已知约束

### 0.1 用户 2026-10-28 23:33 真机反馈

1. 反复折叠/展开「置顶聊天」，**还是会有顿 / 跳一下**。
2. **关掉「分组行」开关后，折叠仍然「会跳一下」**。

### 0.2 已被证伪的假设（不要再往这两个方向改）

| # | 假设 | 证伪证据 |
|---|---|---|
| A | 「跳」= `GroupRowView.build()` 重建整棵 View 树 | 关掉分组行开关后模块不注入分组行，但仍跳 |
| B | §8.3 的 P1「点 A 进 B」 | 同理不是本次跳的来源 |

### 0.3 已知实测（设备 467cf5bc，23:06）

- 折叠 12–16ms（`RELAYOUT_REUSE count=3002`）；展开 51–55ms（`RELAYOUT_REREAD count=3002`）
- 折叠后 `count=2983`、展开后 `3002`，差 19 = 置顶会话数 ⇒ 折叠入口是 inner adapter 的真实 item
- 展开 51–55ms 中 `readSnaps` 只占 6ms，其余在 `ConvLayout.build` 的 O(n) 计算
- **§9.3 自述：count 读数有抖动（同一次折叠曾读到 3002 未变）**
- 改前基线：100ms 节流 + 250ms 去抖 + 全量重建

### 0.4 硬约束（不可回退）

- ⛔ R31 功能：折叠时分组行要跟着收起、展开要恢复
- ⛔ §9.4 补扫修复：`pinScanQueued` + `postDelayed` 窗口末尾补扫
- ⛔ §9.3 裁决：`RELAYOUT_REREAD` 分支必须保留

### 0.5 本次分诊读到的代码事实（①档，行号为当前版本）

| # | 事实 | 位置 |
|---|---|---|
| F1 | `GroupedAdapter.getCount() = layout.size`，而 `layout` 是 `snaps` 的**缓存派生物**；`snaps` 只在 rebuild / REREAD 时重读。**我们报给 ListView 的行数可以脱离 `wrapped.count`** | `ConvFilter.kt:1399`、`:867-874` |
| F2 | `rebuildForPinFold` **刻意不更新 `lastStamp`** ⇒ 折叠后下一次 `rebuild()` 必然走**全量**（`readSnaps` + `rebuildModels` 的 N 次 `db.memberWxids` SQL + N×M 次 `nameOf` 查表 + `ConvLayout.build`） | `ConvFilter.kt:1236-1239`（R32 §3.3 有意为之） |
| F3 | 微信 inner adapter 一 notify ⇒ 我们的转发 observer 立即 `onDataChanged()` ⇒ **250ms 后无条件** `rebuild()` + `notifyDataSetChanged()`（即使 rebuild 短路，notify 也照发） | `ConvFilter.kt:672-683`、`:1193-1203` |
| F4 | `rebuildForPinFold` **没有**动 `observerRebuildQueued`，**也没有** remove 掉 F3 已经排下的 250ms runnable ⇒ **一次折叠必然产生两次 `notifyDataSetChanged()`** | `ConvFilter.kt:1219-1272` |
| F5 | 折叠检测靠 `addOnLayoutChangeListener`（100ms 节流）+ `mainHandler.post` ⇒ 相对微信的数据切换**至少晚 1 帧，最坏晚 116ms + 1 帧**；且 `wrapped.count` 在检测时刻**可能是切换前的值** | `ConvFilter.kt:695-725`、`:1219-1224` |
| F6 | `ConvLayout.build` 里 pinned 分组行是 `if (!pinFolded) for (g in pinned) rows.add(...)` 硬插在最前；关开关时 `build(..., enabled=false)` 走 `passthrough` ⇒ **行数直接 = `snaps.size`** | `ConvLayout.kt:137`、`:86`、`:213-214` |

[DONE] 0 现象与已知约束

---

## 1. 分诊：「顿」与「跳」是不是同一现象？

**结论：不是同一现象，必须分开治。** 但两者在当前代码里**同源于一次折叠的两段式处理**（见 §2 主假设），所以会被用户一起感觉到。

### 1.1 可区分判据

| | 顿（stutter / 卡顿） | 跳（jump / 位置突变） |
|---|---|---|
| 物理含义 | 主线程**单次占用 > 1 帧**（>16.7ms）导致掉帧 | 列表**内容在某一帧发生 y 位移突变**（> 一行高），帧率可能正常 |
| 主判据 | `K7 perf n=N snaps=Xms total=Yms path=full`（`PROBE_WARN_MS=8`）里的 `total`；以及 `pinFold(view) ... -> immediate` → `pinFold fast ...` 两条日志的**时间戳差** | 一次折叠内 `layout.size`（= `fa.getCount()`）的**取值序列**：只应有 1 个值；出现 ≥2 个值 ⇒ 跳 |
| 辅判据 | 同一时刻是否有 `rebuildModels` 的 SQL（`path=full`） | 连续抓屏里同一 wxid 会话行的 `top` 差值 |
| 已可量 | ✅ 现有日志够（`K7 perf` + 两条 pinFold 日志） | ❌ **现有日志不够**，需补 4 条（§4.2） |

### 1.2 决定性区分实验（真机，一次即可）

1. 临时把折叠后的全量 `rebuild()` 降级成「只改 layout、不 `rebuildModels`」（临时探针，验证完还原）。
2. 反复折叠 5 次：
   - **「顿」消失、「跳」依旧** ⇒ 两者不同源，分别治（顿＝主线程耗时，跳＝行数两段式）。
   - **两者都消失** ⇒ 同源（同一次全量 rebuild 既耗时又改行数）。

[DONE] 1 顿/跳分诊

---

## 2. 「跳」的真因候选 + 证真/证伪判据

### ⭐ 主假设（我最看好）：**「行数两段式变化」—— `layout.size` 与 `wrapped.count` 在折叠过程中分两次不同步地对齐**

由 F1+F3+F4+F5 四条代码事实直接推出。完整时序（分组行开关 **OFF** 时同样成立）：

```
t=0ms     用户点「折叠置顶聊天」
t=0ms     微信改数据：inner.count 3002→2983，inner.notifyDataSetChanged()
            ├─ 我们的转发 observer.onChanged() → fa.onDataChanged()
            │    → post { observerRebuildQueued=true
            │             postDelayed(250ms){ rebuild(); notifyDataSetChanged() } }   ← F3
            └─ ListView 的 observer 已挪到 fa ⇒ ListView 此刻【不】因微信的 notify 重排
t≈0–16ms  微信自己 requestLayout / 动画 → list.onLayout → addOnLayoutChangeListener
            → 100ms 节流判定 → scanPinFold() → 扫到「19个置顶聊天」⇒ folded=true
            → LogX「K7 pinFold(view) folded=true -> immediate」
            → fa.onPinFoldChanged(true) → mainHandler.post { rebuildForPinFold(); notify() }
t≈+1帧    rebuildForPinFold:
            currentCount = wrapped.count      ← ★ 可能是 2983（已切换）也可能是 3002（未切换，§9.3 实测两种都出现过）
            · 读到 2983 ⇒ REREAD：snaps=readSnaps(2983)，layout 变成 2983 行  ← 第 1 次行数变化（−19）
            · 读到 3002 ⇒ REUSE ：snaps 不变，layout 仍是 3002 行             ← 第 1 次【没变】
            pinApplied=true → LogX「K7 pinFold fast ... count=...」
            → notifyDataSetChanged()          ← 第 1 次 notify
t≈+250ms  F3 排下的延迟块：rebuild()
            lastStamp 因 F2 已失效 ⇒ 【全量】：readSnaps + rebuildModels(N 次 SQL + nameOf)
                                            + ConvLayout.build   ← 【顿】的来源
            + notifyDataSetChanged()          ← 第 2 次 notify
            ★ 若 t≈+1帧 时读到的是 3002，这里才读到 2983
              ⇒ layout.size 3002 → 2983                          ← 第 2 次行数变化（−19）⇒ 【跳】
```

展开方向完全对称（+19 行，插在最前 ⇒ 下方所有行下移 ≈1700px，**跳得更明显**）。

**为什么它与「关掉分组行开关仍跳」完全相容**：开关关时 `layout = passthrough(snaps)`（F6），
`layout.size == snaps.size`，而 `snaps.size` 一样要等 `readSnaps` 才更新 ⇒ **同样是两段式**，
而且一次就是 ±19 行。✅ 用户第 2 条实测不但没否掉它，反而是它的强证据。

**为什么它与 §9.3 的 count 抖动相容**：§9.3 明说「同一次折叠曾读到 3002 未变」——
这正是上面时序里「抢跑分支」的实测证据。抖动不是噪声，是**竞态本身**。

#### 证真判据（可执行）

- **J1**：一次折叠后 1 秒内，`layout.size` / `fa.getCount()` 出现 **≥2 个不同取值**。
  （需 §4.2 补的日志；现有日志只能间接推）
- **J2**：两次取值之间的间隔 ≈ **250ms**（= `REBUILD_DEBOUNCE_MS`），且第 2 次伴随
  `K7 perf ... path=full`（全量）。
- **J3**：`K7 pinFold fast ... count=N` 的 N 与随后 `K7 perf n=M ...` 的 M **不相等**（抢跑分支）。

#### 证伪判据

- 若一次折叠内 `layout.size` 自始至终只有一个值，且没有第 2 次 notify ⇒ 本假设证伪，
  立即转向候选 ④a（ScrollDock 位置钳制）与候选 ①（微信原生）。

---

### 候选 ① 微信原生折叠/展开动画或布局本身的跳动

**证真**：LSPosed 里**彻底取消勾选本模块**（不是关开关！），重启微信，反复折叠 5 次。
若**仍跳** ⇒ ① 为真，我们能做的只剩「把我们的处理塞进微信那一段动画的同一帧」。

**证伪**：取消勾选后**不跳** ⇒ 与我们有关。

> ⚠️ **最关键的一句话**：用户那条「关掉分组行开关后仍跳」的实测**没有**证伪「我们」——
> `ConvFilter.install` 不看 `CHAT_GROUP_ROW_ENABLED`，开关关掉后模块**仍然**换掉了 adapter、
> 仍然 hook 了 `setAdapter` / `correctTooHigh` / `LongClickRemap`、仍然走
> `onPinFoldChanged` 与 `onDataChanged` 的**两次** `notifyDataSetChanged`。
> 那条实测只否掉了「分组行 View 树重建」（假设 A），**没否掉「我们仍在 notify」**。

---

### 候选 ② `ConvLayout.kt:137` 硬插分组行导致列表结构突变

**已部分证伪**：关开关后 `lastSpecs` 为空，`build` 走 `passthrough`（F6），根本不插分组行，
却仍跳 ⇒ **分组行本身不是跳的来源**。

**残留风险（只影响展开方向）**：`RELAYOUT_REREAD` + pinned 行插在最前 ⇒ 展开时一次性多
`19 + pinned组数` 行。

**证真/证伪**：看 `action=` 落在哪个方向。
- 实测 折叠=`RELAYOUT_REUSE`、展开=`RELAYOUT_REREAD`。用户说**折叠也会跳** ⇒ 折叠方向的跳与 ② 无关。
- 若展开方向的跳在「分组行开关 OFF」时**依旧存在** ⇒ ② 对展开方向也不是主因（此时 layout 是 passthrough）。

**结论**：② 不是主因，但它是「展开比折叠跳得更狠」的**放大项**。⛔ 不许为此回退 R31 功能
（折叠时分组行必须跟着收起）。

---

### 候选 ③ `ConvFilter.kt` adapter 层 notify 行为

拆成三个可独立证伪的子项：

| 子项 | 内容 | 证真判据 | 证伪判据 |
|---|---|---|---|
| ③a | F3：**250ms 去抖块无条件 `notifyDataSetChanged()`**，即使 `rebuild()` 短路也发 | 折叠后 ~250ms 出现第 2 次 `notify`（需 §4.2 日志） | 只有 1 次 notify |
| ③b | `getView` 用**旧 layout** 的下标 `r` 去 `wrapped.getView(r, cv, parent)`；`r >= wrapped.count` 时微信返回 null/抛 ⇒ 落到 `placeholder(parent)`（**0 高度裸 View**）⇒ 一行消失，下一帧恢复 | 日志里出现 `r >= count` 的 `getView`；或抓屏里出现空白行 | 从不出现 |
| ③c | `notifyDataSetChanged()` 后 `RecycleBin` 串类型（viewTypeCount 只在 install 时 `setViewTypeCount` 过一次）⇒ `cv` 被弃用 ⇒ 整屏**新建**行 ⇒ 那一帧极重 | `K7 perf` 的 `total` 在 notify 那一帧飙高；`getView` 里 `cv != null && !isGroupRow(cv)` 的比例 | 复用率正常 |

③a 与主假设是**同一处的两种表述**：F3+F4 共同造成「两次 notify」，而 F2 保证第 2 次是全量
且可能改行数。③a 是主假设的**必要条件**之一。

---

### 候选 ④ 其它（补充）

#### ④a ⭐ ScrollDock / `correctTooHigh` 的位置钳制（次看好）

折叠后行数骤减 19（≈1700px），内容高度可能跌破一屏 ⇒
`AbsListView` 的 `cannotScrollUp/Down`、`correctTooHigh`（已被我们 hook 吞掉）、
`ScrollDock` 的事后钳制三者交互，可能用 `offsetChildrenTopAndBottom` / `setTranslationY`
把**整块内容平移**一次 ⇒ 视觉上就是「整页跳一下」。

- **与分组行开关无关** ✅（开关关时行数同样骤减 19）
- **证真**：抓 `child0.top` 序列 / ScrollDock 自己的日志，看折叠后是否出现一次 **≠ 行高整数倍**
  的整块平移（若是 19×行高 ⇒ 主假设；若是任意值 ⇒ ④a）。
- **证伪**：`child0.top` 全程稳定在 `headerAnchor`（−2713）。

#### ④b `ConvLayout.build` 的 O(n) 计算 ≈ 45ms（**这是「顿」，不是「跳」**）

展开 51–55ms 里 `readSnaps` 只 6ms ⇒ 其余 ~45ms 在 `ConvLayout.build`。
但折叠（REUSE，不 readSnaps）只 12–16ms，**同样是 3002 项 build 却差 3 倍** ⇒
这 45ms 里很可能**不全是 build**，还混了「`mainHandler.post` 在等微信自己的展开动画让出主线程」。

- **证真/证伪**：在 `rebuildForPinFold` 里把 `tRead`（readSnaps）与 `tBuild`（ConvLayout.build）
  **分开打点**（现在只有合在一起的一条 `pinFold fast`）。若 `tBuild` ≈ 12ms 而总差 51ms ⇒
  差额是主线程被微信占用的等待时间 ⇒ **「顿」有一部分不是我们的**，改为「把我们的处理错开
  微信动画」而不是「继续优化 build」。

#### ④c 微信 3046px header 高度在折叠时变化

- **证真**：`child0.top` 在折叠前后 `headerAnchor` 值改变（`K7 anchor X -> Y` 日志）。

[DONE] 2 真因候选

---

## 3. 修复方案（针对主假设）

> ⛔ 全部方案均**不回退**：R31 折叠收起/展开恢复功能、§9.4 `pinScanQueued` 补扫、§9.3 `RELAYOUT_REREAD` 分支。

### 3.1 方案 P1（推荐，治「跳」的核心）：让 250ms 那次全量 rebuild **不再重复改行数**

**思路**：F3 排下的 250ms rebuild 是主假设里「第 2 次行数变化」的载体。但它同时是 R32 §3.3
刻意留的**安全兜底**（把状态刷新到折叠后真实值），**不能直接删**。
正确做法是**保留它、但让它不再产生第二次视觉变化**：
把 250ms 后的「全量重建」换成「**只对齐、只兜底，行数若已一致则不 notify**」。

**改动点**（全部在 `feature/ConvFilter.kt`，约 10 行）：

1. 给 `rebuild()` 加返回值 `Boolean`（`true` = layout 或 models 发生变化，需要 notify）：
   - `short-circuit` 分支 → `false`
   - `light` 分支（只重算 models）→ `true`
   - `full` 分支 → `true`
   - ⚠️ 这是**纯增量**：三个 `return@synchronized` 点各加一个布尔字面量，不改任何判据。
2. `onDataChanged()` 的延迟块改成：
   ```kotlin
   mainHandler.postDelayed({
       observerRebuildQueued = false
       val changed = runCatching { rebuild() }.getOrDefault(true)
       // ⚠️ 只有真的变了才 notify：notifyDataSetChanged 会强制 ListView 重排 +
       // 整屏 getView 重绑，是「250ms 后又抖一下」的直接来源。
       if (changed) runCatching { notifyDataSetChanged() }
   }, REBUILD_DEBOUNCE_MS)
   ```
3. `refresh()` 里的 `fa.rebuild(); restoreAnchor(list); fa.notifyDataSetChanged()` **不动**
   （那是用户主动操作路径，必须无条件刷新）。

**判据（改后要看什么）**：
- 折叠后 1 秒内 `layout.size` 只出现 **1 个取值** ⇒ 跳消失。
- 折叠后仍会出现一条 `K7 perf ... path=full`（全量还是会走一次，因为 lastStamp 失效）——
  这是**预期**的，只是**不再伴随第二次 notify / 第二次行数变化**。

**风险**：
- `rebuild()` 的 `false` 判漏（某种变化没被算进「changed」）⇒ 漏刷新。
  缓解：`short-circuit` 是唯一返回 `false` 的分支，而它的语义就是「输入指纹相同 + 时间指纹
  相同 + 摘要版本没变」= 确实什么都没变。风险低。
- 折叠若是「抢跑分支」（第 1 次读到 3002），250ms 那次 rebuild 仍会真改行数 ⇒ **仍然跳**。
  ⇒ P1 单独不够，**必须配 P2**。

**回滚**：改 3 处、`rebuild()` 返回值改回 `Unit` 即可，无数据/无持久化副作用。

---

### 3.2 方案 P2（推荐，与 P1 配套）：把「行数对齐」从「折叠检测」里解耦，交给 count 变化本身

**思路**：折叠检测（扫文字，100ms 节流 + 1 帧 post）天生会晚于/早于微信的数据切换，
**它不适合负责行数**。行数应该由「微信自己的数据变了」这个**最早、最准**的信号负责 ——
也就是我们已经在监听的 `inner` 的 `DataSetObserver.onChanged()`。

**改动点**（`feature/ConvFilter.kt` 的 `inner.registerDataSetObserver` 里，约 15 行）：

```kotlin
inner.registerDataSetObserver(object : DataSetObserver() {
    override fun onChanged() {
        // R34：微信的【行数】变了（折叠/展开 ±19，或新建会话 ±1）时，
        // 立刻把 layout 的行数对齐，不等 250ms 去抖。
        // 目的：避免「我们报给 ListView 的行数」与 wrapped.count 不同步 ——
        // 那会让 ListView 在任何一次重排里按旧下标渲染，下一次 notify 时
        // 一整块（18~19 行）出现/消失，视觉上就是「跳一下」。
        //
        // ⚠️ 只对齐【行数】，不判折叠方向 —— 方向仍由 scanPinFold 的扫文字决定
        //    （R31/R32 已真机验证）。两者职责分离，互不干扰。
        // ⚠️ 门限 |Δ| >= 2：收新消息一般是 ±1（不命中，仍走 250ms 去抖），
        //    折叠/展开是 ±19（命中）。这样不会把高频的收消息变成高频全量重建。
        val n = runCatching { wrapped.count }.getOrDefault(0)
        if (n > 0 && kotlin.math.abs(n - snaps.size) >= 2) {
            runCatching { fa.alignCountNow(n) }
            return
        }
        runCatching { fa.onDataChanged() }
    }
    ...
})
```

`GroupedAdapter` 新增（约 8 行）：

```kotlin
/**
 * R34：微信行数突变时的【即时对齐】。
 * 只做 readSnaps + ConvLayout.build，不动 models / memberSets / 折叠方向，
 * 且把 observerRebuildQueued 置位以避免 250ms 后再来一次重复的。
 */
fun alignCountNow(n: Int) = synchronized(lock) {
    snaps = readSnaps(n)
    layout = if (lastSpecs.isEmpty()) ConvLayout.build(snaps, emptyList(), emptyMap(), enabled = false)
             else ConvLayout.build(snaps, lastSpecs, memberSets, enabled = true,
                                   pinFolded = pinApplied ?: false)
    lastStamp = ""            // 破短路：下一次 rebuild 仍会补一次全量兜底
    observerRebuildQueued = true   // 抑制 onDataChanged 已经排下的那一次
    LogX.i("K7 alignCount n=$n layout=${layout.size}")
    notifyDataSetChanged()
}
```

**为什么这不会回退 §9.3 / R31 / §9.4**：
- `RELAYOUT_REREAD` 分支**原样保留**：`rebuildForPinFold` 照旧按 `cachedCount == currentCount`
  三选一，只是此时 `snaps.size` 通常已经等于 `currentCount` ⇒ 大概率走 `RELAYOUT_REUSE`
  （只换 layout，不重复 readSnaps）—— 这正是 R32 想要的快路径，不是回退。
- 折叠方向仍由 `scanPinFold` 的扫文字决定，`pinApplied` 只在 `rebuildForPinFold` 里改 ⇒
  R31「折叠时分组行跟着收起」不变。
- `pinScanQueued` 补扫完全不动。

**判据**：
- `K7 alignCount n=2983 layout=2983` 出现在 `K7 pinFold(view) ... -> immediate` **之前或同帧**。
- 折叠后 1 秒内 `layout.size` 只出现 1 个取值（`2983`）。
- 折叠方向的 `action=` 变成 `RELAYOUT_REUSE`（因为 count 已对齐）⇒ 折叠耗时从 12–16ms 再降。

**风险**：
- 收新消息时若一次新增 ≥2 条会话（少见，如批量同步）⇒ 触发一次即时全对齐（≈10–20ms）。
  可接受（本来 250ms 后也要做一次）。
- `onChanged()` 里同步 `notifyDataSetChanged()`，若微信正处于自己的 layout 过程中 ⇒
  ListView 允许（`mDataChanged=true` + `requestLayout`），但若真机出现 `IllegalStateException`
  ⇒ 退化为 `mainHandler.post { ... }`（晚 1 帧，仍远好于 250ms）。**先按同步写，真机若报错再改 post。**
- `observerRebuildQueued = true` 会吞掉一次兜底 rebuild ⇒ 靠 `lastStamp = ""` 保证下一次
  `onDataChanged`（收消息/用户操作）仍会补一次全量。

**回滚**：删掉 `alignCountNow` 调用与函数本体即可。

---

### 3.3 方案 P3（候补，先取证再定）：ScrollDock 位置钳制（候选 ④a）

**暂不改**，等 §4.3 的 `child0.top` 序列取证结果。
若确认为 ④a，改动应在 `ScrollDock`（**不在我的独占清单里**）⇒ 需 team-lead 转派。

### 3.4 不建议现在做的

- **优化 `ConvLayout.build` 的 O(n)**：展开 51–55ms 的构成还没拆开（见 ④b），
  盲改可能优化了一个只占 12ms 的东西。先按 §4.4 打点。
- **动 `getCount()` 的语义**（比如直接返回 `wrapped.count`）：会破坏「分组行收纳后行数变少」
  这个根基，风险远高于收益。

### 3.5 落盘决策

> **本轮不改代码。** 理由：主假设成立与否取决于一个 ②档事实 ——「微信那一次 count 切换
> 发生在我们检测之前还是之后」，§9.3 只给了「有抖动」的观察，没有给「一次折叠内
> `layout.size` 变化了几次」的直接证据。而 P1/P2 都会新增一次主线程工作，
> 在主假设证伪的情况下等于白加一顿。
> **先按 §4 取证，一轮就能钉死。** 取证包里的日志补丁我已在 §4.2 写成可直接粘贴的形态，
> 批下来我 5 分钟内落盘。

[DONE] 3 修复方案

---

## 4. 真机取证清单（交主会话执行）

> 我不能碰设备/adb/gradle，以下全部需要主会话执行。
> 目标：**一轮**取证同时钉死「主假设」「候选 ①」「④a / ④b」。

### 4.1 操作序列（每组 5 次，共 3 组）

| 组 | 前置条件 | 操作 | 目的 |
|---|---|---|---|
| **G-A** | 分组行开关 **ON** | 折叠→展开 ×5，每次间隔 ≥2s | 主假设（J1/J2/J3） |
| **G-B** | 分组行开关 **OFF** | 折叠→展开 ×5 | 验证「与开关无关」；隔离候选 ② |
| **G-C** | LSPosed 里**取消勾选模块**，重启微信 | 折叠→展开 ×5，**肉眼 + 录屏** | 判候选 ①（微信原生跳不跳） |

G-C 是**唯一能证伪「锅在我们」**的实验，优先级最高，但只需肉眼观察，不依赖日志。

### 4.2 需要补的日志（补丁，等批准后由我落盘）

全部加在 `feature/ConvFilter.kt`，**纯日志、零行为改变**，且都有节流/条件，不会刷屏
（一次折叠最多十几条）：

1. `inner.registerDataSetObserver.onChanged()` 开头：
   ```kotlin
   LogX.i("K7 innerChanged count=${runCatching { wrapped.count }.getOrDefault(-1)} layout=${fa.size}")
   ```
   → **判据 J-a**：这行的 `count` 与 `layout` 的差值 = 不同步窗口的大小（期望看到 19）。

2. `onDataChanged()` 的延迟块里（`rebuild()` 前后各一条）：
   ```kotlin
   LogX.i("K7 debounce before count=${...} layout=${fa.size}")
   ... rebuild() ...
   LogX.i("K7 debounce after  count=${...} layout=${fa.size}")
   ```
   → **判据 J-b**：`before` 与 `after` 的 `layout` 若不等 ⇒ **第 2 次行数变化成立 ⇒ 主假设证真**。

3. `rebuildForPinFold` 出口那条已有日志扩成：
   ```kotlin
   LogX.i("K7 pinFold fast folded=$detected action=$action count=$currentCount snaps=${snaps.size} layout=${layout.size}")
   ```
   → **判据 J-c**：`snaps.size` 与 `count` 不等 = 抢跑分支。

4. `rebuild()` 里把 `readSnaps` 与 `ConvLayout.build` **分开打点**，并在折叠窗口内无条件输出
   （现有的 `probeCost` 只在 >8ms 时打，会把 12ms 的 REUSE 分支漏掉 —— 不对，
   12ms > 8ms 会打；但为了确定，把阈值临时降到 0 或加一条 `path=full` 强制日志）。
   最小改法：在 `full` 分支出口加
   ```kotlin
   LogX.i("K7 full rebuild snaps=${snapsCost}ms total=${android.os.SystemClock.uptimeMillis() - tRebuild}ms n=$n layout=${layout.size}")
   ```
   → **判据 J-d**：`snapsCost` vs `total - snapsCost` ⇒ 拆开 ④b 的 45ms 到底是 build 还是等主线程。

5. `restoreAnchor` / `ScrollDock` 侧：抓 `child0.top`。
   最小改法：在 `onPinFoldChanged` 的 post 块里首行和末行各打一次
   ```kotlin
   LogX.i("K7 top0=${list.getChildAt(0)?.top} first=${list.firstVisiblePosition}")
   ```
   → **判据 J-e**：两次 `top0` 若相差非行高整数倍 ⇒ ④a（ScrollDock/整块平移）；
   若相差 19×行高 ⇒ 主假设。

### 4.2-bis 补丁**已落盘**（2026-10-28，team-lead 批准）

全部在 `feature/ConvFilter.kt`，**纯日志、零行为改变**，未动任何判据与时序。
（`git diff --stat`：`ConvFilter.kt | 55 +++++++++++++++++++++-`）

| # | 落在哪 | 行号 | 日志标签 | 触发频率 |
|---|---|---|---|---|
| 0 | `GroupedAdapter` 新增只读访问器 `val snapSize: Int get() = snaps.size` | 959–964 | — | 无副作用 |
| 1 | `inner.registerDataSetObserver.onChanged()` | 674–683 | `K7 innerChanged count=… snaps=… layout=…` | **只在 `inner.count != snaps.size` 时打**（不同步才打，收消息不刷屏） |
| 2 | `onDataChanged()` 的 250ms 延迟块 | 1224–1232 | `K7 debounce count=… snaps=… layoutBefore=… layoutAfter=…` | 每秒最多 4 条（去抖上限） |
| 3 | `rebuildForPinFold()` 出口（原日志扩字段） | 1316–1322 | `K7 pinFold fast folded=… action=… count=… snaps=… layout=…` | 每次翻转 1 条 |
| 4 | `rebuild()` 全量分支出口 | 1144–1151 | `K7 full rebuild n=… snaps=Xml total=Yms layout=…` | 只在走 full 时 |
| 5 | `onPinFoldChanged()` 的 post 块 | 1254–1270 | `K7 pinFold notify folded=… before[top=… first=…] after[…]` + 60ms 后 `K7 pinFold settled folded=… top=… first=…` | 每次翻转 2 条 |

与 §4.2 原方案的**两处偏差**（都是为降噪/保真，已在此标出）：
- 第 1 条改成**只在 `count != snaps.size` 时打**：收纳态下 `layout.size` 本来就 ≠ `wrapped.count`
  （分组行取代了会话行），拿 `layout` 当同步判据会一直误报；`snaps.size` 才是 `wrapped.count`
  的真正镜像。
- 第 5 条加了 **60ms 后的 `settled` 补读**：`notifyDataSetChanged()` 只 requestLayout、
  不同步生效，紧跟其后读到的 `child0.top` 还是重排前的值，判不出位移量。

### 4.3 「看什么值」速查表

| 想钉死的问题 | 看哪条日志 / 哪个值 | 什么结果 ⇒ 什么结论 |
|---|---|---|
| 跳是不是「行数两段式」 | `K7 debounce before/after` 的 `layout=` | 两者不等 ⇒ **主假设证真**（治 P1+P2） |
| 我们有没有抢跑 | `K7 pinFold fast ... snaps=N count=M` | `N != M` ⇒ 抢跑，必配 P2 |
| 不同步窗口多大 | `K7 innerChanged count=X layout=Y` | `|X-Y| == 19` ⇒ 与主假设量级吻合 |
| 锅是不是微信的 | G-C 组肉眼/录屏 | G-C 仍跳 ⇒ **候选 ① 真**，停止改我们这边 |
| 是不是整块平移（④a） | `K7 top0=` 两次之差 | 非行高整数倍 ⇒ ④a，转派 ScrollDock |
| 顿的 45ms 是什么 | `K7 full rebuild snaps=Xms total=Yms` | `Y-X` 仍大 ⇒ build 慢；两者都小但端到端 51ms ⇒ 在等微信主线程 |

### 4.4 交付判定

取证回来后按下列规则出结论，**不需要再跑第二轮**：

- `debounce before/after` 的 `layout` 不等 → 落 P1 + P2。
- G-C 仍跳 → 只落 P1（减顿），不落 P2，并上报「跳是微信原生」。
- `top0` 非整行高平移 → 上报 team-lead 转派 ScrollDock（不在我独占范围）。

[DONE] 4 真机取证清单

---

---

## 6. G-C 结果 + 新现象「折叠时屏幕往上滑」

### 6.1 G-C 结论（team-lead 提供）

模块在 LSPosed 里取消勾选、重启微信后折叠**仍跳** ⇒ **「跳」判定为微信原生行为**。

⇒ 本文档 §2 主假设（「跳 = 我们的行数两段式变化」）**证伪为「不是跳的主因」**。
但它描述的**机制本身仍然成立**（我们确实会晚 250ms 再变一次行数），只是那个机制造成的
观感被用户归到了下面 §6.2 的「上滑」而不是「跳」。

### 6.2 新现象：打开模块，折叠时屏幕往上滑（模块关掉没有）

### 6.3 ④a（ScrollDock / correctTooHigh 主动平移）—— **已用①档代码字面排除**

| # | 事实 | 位置 |
|---|---|---|
| S1 | `ScrollDock.pin` 是全项目**唯一**主动位移列表的地方（`offsetChildrenTopAndBottom`），但它有 `guardPin(list) = ConvFilter.wraps(list) && ConvFilter.currentGroupId() > 0` ⇒ **只在筛选态生效** | `ScrollDock.kt:84-87`、`:177-205` |
| S2 | 折叠发生在**收纳态**（`filterId == NONE`）⇒ `guardPin` 为 false ⇒ **pin 根本不执行** | `ScrollDock.kt:179` |
| S3 | `correctTooHigh` 的触发条件是 `lastPosition == mItemCount - 1`，即**内容不足一屏**。收纳态 2983 行远超一屏 ⇒ **不触发**；我们对它的处理是「吞掉」（`param.result = null`），吞掉 = **少一次下移**，方向也不可能造成上滑 | `ScrollDock.kt:147-169`、`ConvFilter.kt:1525-1556` |
| S4 | 对称的 `correctTooLow` 条件是 `firstTop - padTop > 0`，而停靠位 `firstTop = -2713` 恒为负 ⇒ 不动作（真机已实测 36 次调用 0 次位移） | `ScrollDock.kt:143-145` |

⇒ **上滑不是主动平移，是「行数变化没被补偿」**。

### 6.4 上滑机制（②档，已落地日志可一轮证真）

```
t=0ms     微信改数据：inner.count 3002→2983，inner.notifyDataSetChanged()
          ⚠️ ListView 的 DataSetObserver 已被我们挪到 fa（ConvFilter.kt:661-668）
          ⇒ 微信这次 notify 【不会】让 ListView 重排
          ⇒ 屏幕上还是 3002 行的内容，一直不动
t≈16ms    scanPinFold 判出 folded=true → rebuildForPinFold
          currentCount 读到的还是 3002（§9.2 实测 action=RELAYOUT_REUSE count=3002）
          ⇒ 【抢跑】：这一轮一行都没减
          ⇒ notifyDataSetChanged() 之后屏幕上【没有位移】
t≈250ms   onDataChanged 的延迟块 rebuild()
          这次才读到 2983 ⇒ snaps=2983 ⇒ layout 2983
          ⇒ notifyDataSetChanged() ⇒ 【19 行一次消失，下方内容整体上移 ≈1600–1700px】
          ⇒ 用户看到的是「点了之后停住，约 0.25s 后整屏往上滑一大截」
```

**一句话：上滑 = 我们的行数变化比微信晚了 250ms。不是位移算错，是位移迟到。**

为什么模块关掉就没有：模块关掉后 ListView 的 observer 还在微信自己的 adapter 上，
微信 notify 的那一帧就重排 ⇒ 19 行的位移**同帧发生** ⇒ 观感是「跳」（G-C 的结论）。
模块打开后这 19 行被我们推迟到 250ms 后 ⇒ 观感变成「滑」。

### 6.5 回答 team-lead 的三问

**Q1：上滑是「行数变化没被补偿」还是 `correctTooHigh`/ScrollDock 主动平移？**
→ **行数变化**（③已用 S1–S4 四条①档代码字面排除主动平移）。
可区分判据（已落地的日志直接给出）：
| 看什么 | 什么值 ⇒ 什么结论 |
|---|---|
| `K7 pinFold settled folded=true top=X first=Y` 与折叠前 `top` 的差 | ≈ 19 × 行高（1600–1700px）⇒ **行数变化**；非整倍数 ⇒ 主动平移（④a 复活，转派 ScrollDock） |
| 日志里出现 `ScrollDock pin …px` | 若折叠时出现 ⇒ `currentGroupId()>0`（筛选态），与 S2 矛盾，需重新看 |

**Q2：第二次 notify 是不是上滑的直接来源？P1 落完是否就能消失？**
→ **是来源，但 P1 不够**。抢跑分支下第二次 notify **确实改了行数**
（`layoutBefore=3002 / layoutAfter=2983`）⇒ `changed = true` ⇒ **P1 仍会 notify**。
P1 只消除了「什么都没变时的无谓整屏重排」（对「顿」有收益），
**不能**消除上滑 ⇒ **P1 是优化，不是修 bug**。
（要让它变修 bug，得把判据从「rebuild 有没有变」换成「行数有没有变 + 这次是不是折叠引发」，
那等于把 P2 的逻辑塞进 P1，风险更高，不建议。）

**Q3：是否需要保留滚动位置（记录 firstVisibleItem + top，rebuild 后 restore）？**
→ **不需要，而且有害**。两条理由：
1. 折叠时列表**停在顶部**（`firstVisiblePosition = 0`、`child0.top = -2713`），
   恢复成 `(0, -2713)` 是 **no-op**；而那 19 行是**从可视区里消失**的，
   不是从可视区上方消失的 —— 位置补偿救不了它，位移是折叠的必然结果，只是**来晚了**。
2. R32 明确禁止在这条路径碰锚点：`setSelectionFromTop` 曾把微信 3046px 的 header
   顶出来制造假下拉（`ConvFilter.kt:1212-1217`、`:569-582`）。
⇒ 正确做法是**让第一次就把 count 拿对**，而不是事后补偿位置。

### 6.6 ⚠️ 对「不落 P2」的异议（请 team-lead 裁决）

P2 被否掉的理由是「它针对的是跳，而跳已证伪为原生」。
但 **P2 的机制（在 `onChanged` 里即时对齐 count）真正消除的是「我们的第二次行数变化」
= 上滑**，与「跳是不是原生」无关：

- 跳 = 微信自己那一帧的位移 ⇒ 改不掉（G-C 已证）。
- 上滑 = **我们**多出来、且**迟到 250ms** 的那次 19 行位移 ⇒ **P2 正好消掉它**。

建议把 P2 的目标从「消除跳」重新定义为「消除上滑」，并在 §6.5-Q1 的判据
（`K7 debounce layoutBefore != layoutAfter` + `K7 pinFold fast count=3002 snaps=3002`）
确认抢跑后落盘。**在拿到那两条日志之前我不动 P2。**

### 6.7 P1 已落盘（team-lead 授权）

| 位置 | 行号 | 改动 |
|---|---|---|
| `fun rebuild()` → `fun rebuild(): Boolean` + KDoc | 1003–1019 | 返回「有没有真变」 |
| `short-circuit` 分支 | 1090 | `return@synchronized` → `return@synchronized false` |
| `light` 分支 | 1101 | `return@synchronized` → `return@synchronized true` |
| `full` 分支末尾 | 1165 | 新增 `true`（作为 `synchronized` 块末端表达式） |
| `onDataChanged()` 延迟块 | 1242 / 1250 | `val changed = runCatching { rebuild() }.getOrDefault(true)` + `if (changed) runCatching { notifyDataSetChanged() }`，并把 `changed` 打进 `K7 debounce` 日志 |

- **安全性**：`false` 只由「完全没变」那条分支返回（配置指纹相同 + 顺序没变 + 时间指纹相同
  + 摘要版本没变 + 成员版本没变），语义上就是「这次 rebuild 一行都没动」，漏刷新风险为零；
  `getOrDefault(true)` 保证 rebuild 抛异常时退回原来的无条件刷新。
- **未动**：`refresh()`（471）与 `install()`（738）里的 `fa.rebuild()` 仍是无条件 notify
  （用户主动操作路径，必须刷新）；`rebuildForPinFold`（1324）的 `rebuild()` 返回值忽略。
- **边界**：只改了 `feature/ConvFilter.kt`。未碰 `AutoHdImg` / `VoiceFileGuard` /
  `MediaGuardLogic` / `ListResolver`（后者另一条 lane 在改）。

[DONE] 6 G-C 结果与上滑分诊

---

---

## 7. 新线索：`BAR pinFold ... -> collapse 340ms`（GroupBar 旧机制）

> 日志（23:57:49–23:58:12，模块开启）里 `K7 pinFold(view) ...` 与
> `BAR pinFold ... -> collapse/expand 340ms` **成对出现、间隔 2ms**。

### 7.1 ⭐ 结论（①档代码字面，不用推理）

**上滑的真因就是 GroupBar 的这条 340ms 折叠动画 —— 它改的是「ListView header 的占用高度」。**

链路：
1. `attachHeader` 把 `bar.lane` 写进 `FixedViewInfo.view` ⇒ **lane 是会话列表的 header**
   —— `GroupBar.kt:497`（`XposedHelpers.setObjectField(info, "view", bar.lane)`）。
2. `applyFold` 用 340ms 的 `ValueAnimator` **逐帧写 `lane.layoutParams.height`**，
   从自然高度降到 `FOLDED_H = 1`，每帧 `lane.requestLayout()`
   —— `GroupBar.kt:643-672`（`FOLD_MS = 340`，`:623`；`FOLDED_H = 1`，`:635`）。
3. header 逐帧变矮 ⇒ **列表内容整体上移同样的量，历时 340ms** ⇒ 就是用户说的
   「收起（折叠）时屏幕会往上滑」。量级参照 `GroupBar.kt:631` 的注释：自然高度 **1040px**。

它比「行数突变」更贴合「滑」：340ms 的逐帧位移是**肉眼可见的平滑移动**，
而行数突变是单帧的（= 「跳」）。

### 7.2 但**不是在 MODE_NONE 下**跑的 —— 请修正 team-lead 的前提

R31「默认 MODE_NONE ⇒ 那套是死代码」的结论**结构上仍然成立**：MODE_NONE 下这条动画
**根本进不去**，有两道 guard：

| guard | 位置 | 作用 |
|---|---|---|
| `if (bar.mode == MODE_LIST) { ... syncPinFold(bar) }` | `GroupBar.kt:939-950` | MODE_NONE 时压根不调用 `syncPinFold` |
| `if (bar.mode != MODE_LIST \|\| bar.nativeHeader.not()) return` | `GroupBar.kt:589` | 第二道，双保险 |

⇒ **日志里 `BAR pinFold ... -> collapse 340ms` 会打出来，本身就证明了那台机上的 bar
是 `MODE_LIST`，不是 `MODE_NONE`。** 而 mode 的取值（`GroupBar.kt:385-389`）：
```kotlin
val mode = if (Kv.getBool(Keys.CHAT_GROUP_ROW_ENABLED)) MODE_NONE
           else db.getPref(PrefKeys.BAR_MODE, MODE_LIST)   // 默认 MODE_LIST
```
`Keys.CHAT_GROUP_ROW_ENABLED` 的默认值是 `"true"`（`Keys.kt:291`），
`Kv.getBool` 只有读不到/被显式写成 false 才返回 false（`Kv.kt:35-38`）。

⇒ **⇒ 那台机当时「启用分组行」开关是【关】着的**（大概率是 G-B 那次测完没开回来）。
这也解释了为什么「关掉分组行开关后折叠仍跳」——**那条「跳」很可能根本不是
ConvFilter，而是 GroupBar 旧机制的这条 340ms 动画。**

### 7.3 由此要修正的两条旧结论

| 旧结论 | 修正 |
|---|---|
| §2 主假设「跳 = ConvFilter 的行数两段式变化」 | **降级为次要**。开关 OFF 时的「跳」首先该怀疑 GroupBar 的 340ms 动画。**先把这条修掉，再看还剩什么。** |
| §3「顿来自折叠后那次全量 rebuild」 | **证伪**。日志 `K7 perf n=2983 snaps=5ms total=9ms path=full` ⇒ 全量只要 **9ms**（不到一帧），不是顿的来源。顿更可能是 340ms 动画期间每帧 `requestLayout()` 叠加 ConvFilter 的整屏重绑。 |

`K7 perf ... n=2983 total=9ms` 同时说明：折叠后 `wrapped.count` 确实是 2983（§9.3 的重述），
且全量重建很便宜 ⇒ **R32 §3.3 那条「刻意不更新 lastStamp」的代价其实可以忽略**。

### 7.4 修复方案（GroupBar.kt **不在我的独占清单**，需 team-lead 指派/授权）

#### 选项 1（我推荐，语义最正，但要拍板）：开关 OFF 不再退回旧机制

`GroupBar.kt:385-389` 改成开关 OFF 也走 `MODE_NONE`：
```kotlin
// 开关关掉 = 用户不要任何分组行 UI。旧机制横条也是 UI，
// 退回它既违背开关语义，又会带出 340ms 的折叠位移。
val mode = MODE_NONE
```
- ✅ 340ms 动画彻底不跑 ⇒ 上滑消失；顺带修掉「关了开关还有横条」的语义矛盾。
- ⛔ **砍掉 R31 刻意留的「可回退到旧机制」这条路**。若将来 ConvFilter 的 adapter 插行
  出真机问题，没有退路。这是设计取舍，需用户/team-lead 拍板。
- 回滚：改回一行。

#### 选项 2（风险最小，一行）：MODE_LIST 下不再响应 pinFold

在 `syncPinFold`（`GroupBar.kt:588`）开头加一条直接 return（等价于删掉 R31 在旧机制上的
「分组横条跟着折叠收起」）。
- ✅ 一行、零连带；`bar.pinFolded`（`:146`）全文只有 `:601` 一处写入、**无读取方**，
  删掉不影响任何其它逻辑（已 grep 确认）。
- ⛔ 回退了 R31 在**旧机制**上的那个功能；但默认走 MODE_NONE，该功能日常根本不执行，
  实际价值很低。
- 与选项 1 的差别：横条还在（开关 OFF 时用户仍会看到），只是不再折叠。

#### 选项 3（否决）：`applyFold` 改成不改占用高度

只改 `visibility` / `translationY` 的话 header 占位不变 ⇒ 折叠后原地留一大块空白
（`GroupBar.kt:628-633` 的注释就是在讲这个坑）。否决。

#### 选项 4（否决）：保留动画、补偿位移

`animateLaneHeight` 的注释（`GroupBar.kt:705-706`）已明说：
「lane **变短**无需补偿（内容上移……永远到不了阈值）」—— 那是针对「面板误触发」说的，
但同样说明 **header 变矮引起的上移是 AOSP 的必然结果，ListView 层面无法抵消**
（列表在顶部，没有可滚的空间）。否决。

### 7.5 决定性取证（一轮）

| 想确认 | 怎么做 / 看什么 |
|---|---|
| 现在到底是不是 MODE_LIST | 抓安装日志：`BAR mode=none, ConvFilter owns group rows`（`GroupBar.kt:402`）还是 `BAR inject mode=list NATIVE header`（`:409`）。**打出后者 ⇒ 确认。** |
| 上滑是不是它 | 选项 2 改完（或直接把开关**打开**）再折叠：若上滑消失 ⇒ 确认。最快的办法其实是**先把「启用分组行」开关打开再折叠一次** —— 开关 ON ⇒ MODE_NONE ⇒ 动画不跑。 |
| 位移量级 | `K7 pinFold settled folded=true top=…` 与折叠前的差 ≈ 1040px 量级 ⇒ 是 lane 高度；≈19×行高 ⇒ 是行数变化 |

**建议主会话先让用户把「启用分组行」开关打开再折叠一次** —— 这不需要改代码就能把
GroupBar 这条与 ConvFilter 那条彻底分开，成本最低。

[DONE] 7 GroupBar collapse 340ms 分诊

---

---

## 8. GroupBar 证伪 + P2 落盘

### 8.1 GroupBar 那条作废（team-lead 实测）

**开关 ON 和 OFF 两种情况下上滑都在。** 开关 ON ⇒ `MODE_NONE` ⇒ 两道 guard
（`GroupBar.kt:939-950` / `:589`）让 340ms 动画根本不跑 ⇒ **它不是上滑来源**。
§7 的结论作废，**GroupBar.kt 一行都不改**（选项 1/2 都不动）。

回到 §6.4 主假设，它现在是唯一候选：
> 上滑 = 我们的行数变化迟到 250ms。

G-C 已把「跳」判给微信原生，「滑」判给我们 —— 两者分离清楚。

### 8.2 P2 已落盘（team-lead 授权，只改 `feature/ConvFilter.kt`）

| 位置 | 行号 | 改动 |
|---|---|---|
| 新增常量 `COUNT_JUMP = 2` + KDoc | 103–114 | 行数突变门限 |
| `inner.registerDataSetObserver.onChanged()` | 694–707 | `abs(inner.count - fa.snapSize) >= COUNT_JUMP` ⇒ **即时** `fa.alignNow()`；打 `K7 innerJump` |
| 新增 `GroupedAdapter.alignNow()` + KDoc | 1247–1266 | 即时 `rebuild()`（P1 的 `changed` 决定是否 notify）+ `K7 alignNow` |

**与原方案的偏差（重要，已在此标出）**：§3.2 原计划是 `alignCountNow()` 自己写
`readSnaps + ConvLayout.build`。落盘版改成 **直接复用 `rebuild()`**，理由两条：
1. `rebuild()` 的分支逻辑（开关关 / 筛选态 / 无分组 / 折叠方向）是现成且真机验证过的；
   自己写一份会踩「`lastSpecs` 为空时到底是 `passthrough` 还是 `filtered`」的坑
   —— 筛选态下 `lastSpecs` 也为空，走 passthrough 就错了。
2. 真机实测一次全量 `rebuild()` 只要 **9ms**（`K7 perf n=2983 snaps=5ms total=9ms path=full`），
   远低于一帧，没必要为了省 `rebuildModels` 再维护一套平行实现。

**三条硬约束均未触碰**：
- R31「折叠时分组行跟着收起」—— 方向仍由 `scanPinFold` 扫文字决定，`alignNow` 不碰
  `pinApplied`、不碰 `pinFoldedByView`。
- §9.4 `pinScanQueued` 补扫 —— 未动。
- §9.3 `RELAYOUT_REREAD` 分支 —— 原样保留（只是现在多数情况会走 `RELAYOUT_REUSE`，
  因为 snaps 已对齐；那正是 R32 想要的快路径，不是回退）。

**未动**：`observerRebuildQueued`、`onDataChanged` 的 250ms 兜底（models / memberSets 刷新必须保留）。

### 8.3 已知残留（不建议现在追）

`alignNow()` 在 t=0 跑时 `pinFoldedByView` 还是**旧值**（扫文字要等布局回调），
所以这一次会把 19 行去掉、但**保留 pinned 分组行**；t≈16ms `rebuildForPinFold(true)`
再把 pinned 分组行收掉。

⇒ 仍有两段，但第二段只有 **1–3 行**（pinned 分组数），16ms 内完成，
对比原来的「19 行 + 250ms」已经不是一个量级。要彻底消掉这一段，就得在 t=0
用数据层 `detectPinFolded` 判方向 —— 而 §9.4 实测它误判过一次
（`pinFold apply folded=false src=data`），风险大于收益，**先不动**。

### 8.4 复验判据（装机后看什么）

| 看什么 | 期望 | 含义 |
|---|---|---|
| `K7 innerJump count=2983 snaps=3002` | 出现在折叠瞬间（t≈0） | P2 生效，行数即时对齐 |
| `K7 alignNow snaps=2983 layout=… changed=true` | 紧跟 `innerJump` | 即时重建完成 |
| `K7 pinFold fast folded=true action=RELAYOUT_REUSE count=2983 snaps=2983` | **`snaps == count`**（不再是 3002/3002 抢跑） | 抢跑消失 |
| `K7 debounce ... layoutBefore=X layoutAfter=X changed=false` | 两个数**相等** | 250ms 那次不再改行数 ⇒ **上滑应消失** |
| 肉眼 | 折叠瞬间即完成，不再「停一下再滑」 | 用户侧确认 |

若 `K7 debounce` 仍 `layoutBefore != layoutAfter` ⇒ 说明还有别的行数变化源，需再查。

[DONE] 8 GroupBar 证伪与 P2 落盘

---

## 5. 变更记录

| 时间 | 内容 | 档位 |
|---|---|---|
| 2026-10-28 | 建骨架 + §0 现象/约束/代码事实（F1–F6） | ①（代码字面） |
| 2026-10-28 | §1 顿/跳分诊 + 区分判据 | ①（代码字面）+ ②（区分实验） |
| 2026-10-28 | §2 主假设「行数两段式」+ ①②③④ 候选与判据 | ②（时序推理，待 §4 取证升①） |
| 2026-10-28 | §3 方案 P1/P2（含改动点与回滚）+ P3 候补 | ②（方案，未落盘） |
| 2026-10-28 | §4 取证清单（G-A/G-B/G-C 三组 + 5 条日志补丁） | ①（可直接执行） |
| 2026-10-28 | **§4.2 的 5 条日志补丁已落盘**（team-lead 批准），见 §4.2-bis，行号已记 | ①（已落盘，待编译） |
| 2026-10-28 | G-C 结果：「跳」判为微信原生；§2 主假设作为「跳的主因」证伪 | ①（真机） |
| 2026-10-28 | §6 新现象「折叠上滑」分诊：④a 用 S1–S4 排除；机制＝我们的行数变化迟到 250ms | ②（时序推理，已落地日志可一轮证真） |
| 2026-10-28 | **P1 已落盘**（team-lead 授权），见 §6.7 行号表 | ①（已落盘，待编译） |
| 2026-10-28 | §7 GroupBar `collapse 340ms`：开关 OFF 时它确实改 ListView header 占用高度（①档） | ①（代码字面） |
| 2026-10-28 | §8.1 **GroupBar 证伪**：开关 ON/OFF 上滑都在 ⇒ 它不是上滑来源，§7 结论作废，`GroupBar.kt` 不改 | ①（真机实测） |
| 2026-10-28 | §8.2 **P2 已落盘**（team-lead 授权），见行号表；改复用 `rebuild()` 而非自写 `alignCountNow` | ①（已落盘，待编译） |

### 代码变更范围

- ✅ 已落：`feature/ConvFilter.kt` 的 5 处纯日志 + 1 个只读访问器（§4.2-bis）、P1（§6.7）。
- ⛔ 未落：P2（**我对「不落 P2」有异议，见 §6.6 —— P2 消的是上滑不是跳**）、P3（④a 已排除，暂无对象）。

[DONE] 5 变更记录
