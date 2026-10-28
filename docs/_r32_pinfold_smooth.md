# R32 折叠置顶聊天跟随：消除「慢半拍 + 跳一下 + 卡顿」

> 用户原话：「折叠和展开时置顶分组和置顶会话直接不流畅」，追问确认：延迟/跳动/卡顿都有，
> **主要出现在收起时（点『折叠置顶聊天』那一刻）**。
> R31 已把功能正确性修通（真机四条全过），本轮**只解决体验，不许回退功能**。
> 行号均为本次改动后的当前行号。

## 0 结论摘要

**根因（一句话）**：折叠态翻转复用「收新消息」的 **250ms 去抖**（`REBUILD_DEBOUNCE_MS`），
且翻转必走 `rebuild()` 全量路径（`configStamp` 含折叠态 ⇒ 指纹变 ⇒ 全量重读 2900 条快照 +
N 次 SQL）。于是「点折叠 → 微信折叠动画结束 → 250ms 后我们才把分组行抽走」，表现为慢半拍 +
二次跳动；全量重建在主线程上再叠一层卡顿。

**修复（3 处，全部不碰滚动锚点、不回退功能）**：
1. `feature/PinFoldLogic.kt`：新增纯函数 `foldAction(applied, detected, cachedCount, currentCount)`
   → `NOOP / RELAYOUT_REUSE / RELAYOUT_REREAD` 三态，把「count 变/不变」和「三态 null/true/false
   不混同」的判据抽成零 Android 依赖、JVM 直接可测的决策。
2. `feature/ConvFilter.kt`：新增 `onPinFoldChanged()` + `rebuildForPinFold()` **即时路径**，
   折叠翻转**一帧内**完成，不再与「收新消息」共用 250ms 去抖；只重算 layout（复用
   snaps / memberSets / models / lastSpecs），不查库、不重算 models；按 count 是否变化分支
   （变 ⇒ 只重读一次快照）。
3. 检测侧仍用 R31 已验证的「扫已布局文字」（100ms 节流）判方向，翻转后直接走即时路径。

**关键事实档位**：`折叠时 wrapped.count 是否变化` —— **②档（无法静态定论）**，但代码按
count 分支写成两种事实下都正确（见 §2）。其余根因均为 ①档字面证据。

[DONE] 0 结论摘要

## 1 根因（改前时序，①档）

改前折叠态翻转的完整链路（`ConvFilter.kt` R31 落地后）：

```
用户点「折叠置顶聊天」
  → 微信折叠（自身动画）
  → list.addOnLayoutChangeListener 触发（同一帧，0–16ms）
  → 100ms 节流（PIN_FOLD_SCAN_MS）→ walkTexts 扫文字判方向
  → fa.onDataChanged()          ← ★ 复用「收新消息」的 250ms 去抖
  → postDelayed(250ms)
  → rebuild() 全量：readSnaps(n) 逐条 getItem + 2 次反射 + rebuildModels N 次 SQL
  → notifyDataSetChanged()
```

R31 真机日志实测的 310ms 空窗（`docs/_r31_pinfold.md` §6.1）：

```
21:16:46.614  K7 pinFold(view) folded=true -> rebuild   ← 检测到翻转
21:16:46.924  K7 pinFold apply folded=true src=view     ← 250ms 去抖 + 全量 rebuild 后才真正生效
```

三宗罪（都由此而来）：
1. **慢半拍**：250ms 去抖是给「收新消息」高频 notify 设计的，折叠是用户主动操作，
   不该等 250ms —— 分组行在微信折叠动画结束后 250ms 才消失。
2. **跳一下**：分组行的消失/出现与微信折叠动画**错相**，形成两段式位移（微信先收、
   我们 250ms 后跟着抽走 3 行），视觉上就是「跳」。
3. **卡顿**：翻转必然走 full 路径（`configStamp` 把 `pinFolded` 算进去 ⇒ 指纹变 ⇒
   R31 的短路失效），2900 条 `getItem` + 反射 + 每组 `db.memberWxids` SQL + 每成员
   `nameOf` 查表全压在折叠后的主线程上。

为什么 `configStamp` 必然导致 full：R31 §2.4 为了让折叠态能穿过 rebuild 短路，
把 `pinFolded` 加进 `configStamp`；副作用是**只要折叠态一变，指纹必变，短路必失效**。
本轮不撤销 R31 这条（它是真机 bug 的守门），而是另开一条**只重算 layout、不走
configStamp 短路判定**的即时分支。

[DONE] 1 根因

## 2 关键事实取证：折叠时 `wrapped.count` 是否变化（②档）

决定「能不能只换 layout、不重读快照」。结论：**②档，无法静态定论，但证据偏向 count 不变**。
代码按 count 分支（§3），两种事实下都正确。

### 2.1 已确证的字面证据（①档）

| # | 证据 | 来源 |
|---|---|---|
| 1 | 会话列表 inner adapter `p3` 是**游标适配器**：`p3 extends i3 extends BaseAdapter`，`i3.getCount()` 反汇编为游标 `getCount()`（`invoke-virtual La94/m;->a` 后 `return`），不是 `List.size()` | `_cache/_s9dump/classes10.txt:223081` 反汇编 `i3.getCount` |
| 2 | 「折叠置顶聊天」是一个 `gt3:TextView`，**渲染在某条真实会话行内部**（`item=r4 field_username='wx__c_19d94cc78fb'`），不是独立行/header | `_v2log.txt` / `_w3log.txt` 长按菜单 dump（`ConvMenu cand: [...gt3:TextView]'折叠置顶聊天' item=r4 ...`） |
| 3 | 折叠态的会话（`field_parentRef="message_fold"`）**仍出现在 `wrapped.getItem(i)` 序列里**（`#6 wxid_mhxybu5e5fhv12 ... field_parentRef="message_fold"`） | `_topprobe.txt` / `_v2log.txt` 顶部会话字段 dump |

### 2.2 推理（②档，须标假设）

- 假设（②）：折叠入口「N个置顶聊天」**不是** inner adapter 的真实 item —— 依据是证据 2
  （「折叠置顶聊天」文本长在会话行内部）与证据 3（折叠会话仍留在 `getItem` 序列）。若此
  假设成立，折叠时 `wrapped.count` **不变**，快路径可 `RELAYOUT_REUSE`（只换 layout）。
- 反向假设（②）：若「N个置顶聊天」是真实 item（R31 `detectPinFolded` 的写法前提），则
  折叠时 count **变**（N 条置顶 → 1 条入口），必须 `RELAYOUT_REREAD`。

**两个假设无法靠现有静态证据裁决**（dex 混淆、折叠逻辑在 UI 层与游标层交织），所以代码
写成 `foldAction` 按 `cachedCount == currentCount` 分支，两条路都正确；单测把两条分支都钉死。

> 为什么 R31 的 `detectPinFolded` 仍按「真实 item」写：那是**数据层兜底**（扫文字扫不到时），
> 不是主判据。真机上主判据一直是扫文字（R31 §6.2 实测 4 层深度），`detectPinFolded` 单独
> 是否命中从未被隔离验证（R31 §7.1 遗留）。本轮不动它，只是给它配了一个「count 变才用它
> 重读」的互补快路径，两种事实下都不会用错。

[DONE] 2 关键事实取证

## 3 方案（改后时序对比）

### 3.1 改后时序

```
用户点「折叠置顶聊天」
  → 微信折叠（自身动画）
  → 布局回调 → 100ms 节流 → walkTexts 判方向（同改前）
  → fa.onPinFoldChanged(folded)            ← ★ 即时，无 250ms 去抖
  → mainHandler.post（下一消息循环，≈0ms）
  → rebuildForPinFold(folded)：foldAction 三选一
       · NOOP             ：没翻转，return（重复报同方向）
       · RELAYOUT_REUSE   ：count 未变 ⇒ 复用 snaps，只 ConvLayout.build 换 layout
       · RELAYOUT_REREAD  ：count 变   ⇒ readSnaps 一次，再换 layout（仍不查库/不重算 models）
  → notifyDataSetChanged()
```

对比（最坏情况，100ms 节流满额）：

| 阶段 | 改前 | 改后 |
|---|---|---|
| 扫文字判方向 | ≤100ms 节流 | ≤100ms 节流（不变） |
| 去抖 | **250ms** | **0ms** |
| 重算 | full（读 2900 快照 + N SQL） | REUSE ≈ O(n) 纯计算（无反射无 SQL）；REREAD 只多一次读快照 |
| 总计 | ≈360ms | ≈110ms（REUSE 约 1ms 级别） |

### 3.2 为什么不碰滚动锚点（要求 3）

折叠时列表**必然停在顶部**（「折叠置顶聊天」入口在最上，用户点它就得在顶部）。所以
折叠路径不需要、也不能 `restoreAnchor`：`setSelectionFromTop(0,0)` 会把微信 3046px
header 顶出来制造假下拉（历史坑，`ConvFilter.kt` 顶部注释 + `rememberAnchor` 注释）。
`rebuildForPinFold` 只 `layout = ...` + `notifyDataSetChanged()`，不调任何
`setSelectionFromTop` / `rememberAnchor`。即便将来出现「滚在中段时折叠」（理论上点不到
入口，但防御性保证）：不碰锚点 ⇒ 列表绝不会被弹回顶部。

### 3.3 lastStamp 刻意不更新（代价与收益）

`rebuildForPinFold` **不更新 `lastStamp`**。后果：折叠后下一次 `rebuild()`（收新消息触发）
会算出新指纹 ≠ 旧 lastStamp ⇒ 走一次全量重建，把 snaps / models / memberSets 全部刷新到
折叠后真实状态。这是**有意为之**：
- 折叠是低频用户操作，多一次全量重建可忽略；
- 换来「折叠后数据绝不残留旧状态」的安全兜底（结构若真变了，全量重建是唯一能对齐下标
  的手段）；
- 若用户折叠后又展开（净零），lastStamp 恰好回到一致，下一次 rebuild 照常短路。

（注：正常负载下 readSnaps ≈ 4–9ms，见 `_a4.txt`；`_r31h.txt` 里的 70–80ms 是 SNS 导出
后台任务把主线程压满的异常态，不代表折叠路径常态。）

[DONE] 3 方案

## 4 被否决的候选及原因（要求 4：检测侧更早信号）

检测侧 100ms 节流的副作用：**最坏多 100ms**（若上次扫描 <100ms 前，本次翻转要等下一次
布局回调越过节流窗）。100ms 人眼几乎无感，且实测 WeChat 折叠动画本身数百 ms，节流不是
瓶颈（真正的瓶颈是 250ms 去抖，已消除）。

评估过的两个「更早时机信号」：

### 候选 A：对「折叠置顶聊天」行挂 `OnTouchListener`（ACTION_UP 预判）—— **否决**

风险：`GroupedAdapter.getView` 转发的会话行点击由微信自己绑在行上（`ConvFilter.kt`
顶部注释 + `LongClickRemap` 注释：`onItemClick` hook 零命中，行级 OnClickListener 才是
真的）。给这行额外挂 OnTouchListener（即使返回 false）会**改变触摸分发顺序**，可能干扰
微信的行点击判定与无障碍事件，而折叠入口行本身是微信的行、不是我们插的分组行 ——
触碰它的监听风险完全不可控。任务明令「能不用就不用」，否决。

### 候选 B：`inner.registerDataSetObserver.onChanged()` 里判 count 变化走即时路径 —— **否决**

否决理由（关键）：`onChanged()` 在微信 `notifyDataSetChanged()` 时触发，**早于视图重新
布局**。此时 `walkTexts` 扫到的还是**旧文案**（视图没换新），判不出翻转方向；而数据层
`detectPinFolded` 只在「count 变 + 折叠入口是空 username 真实 item」这一种事实下才可靠
（②档假设），count 不变那一种事实下恒返回 false。强行在 onChanged 里走即时路径，要么
用错方向（旧文案），要么退回全量 rebuild（重读 2900 快照，违背要求 2）。**收益不稳定、
风险高**，否决。

### 选定：保留布局监听（100ms 节流）作唯一检测源

R31 已真机验证「扫已布局文字」是最接近用户所见事实的可靠判据（4 层深度、上限 8 层）。
本轮不改它的机制，只把它的**下游**从 250ms 去抖换成即时路径。`count 是否变化` 的判断
不在检测侧做，而在快路径 `rebuildForPinFold` 内部做（读一次 `wrapped.count`，与
`snaps.size` 比较），这正是把「两种事实都正确」收敛到一处的做法。

[DONE] 4 被否决的候选

## 5 单测

新增 6 条（`PinFoldLogicTest.kt`，原有 16 条保留，合计 22 条）：

- `foldAction_noopWhenAppliedEqualsDetected` —— 没翻转（同方向重复上报）⇒ NOOP。
- `foldAction_flipReuseWhenCountUnchanged` —— 快路径成立条件：翻转 + count 未变 ⇒ REUSE。
- `foldAction_flipRereadWhenCountChanged` —— 必须重读条件：翻转 + count 变 ⇒ REREAD。
- `foldAction_nullAppliedIsNotNoop` —— applied=null（从未应用）与布尔不等 ⇒ 恒触发重建。
- `foldAction_threeStatesDoNotMix` —— null/false/true 三态两两不混同（false==false 才 NOOP）。
- `flipRelayoutPutsPinnedRowsCorrectly` —— 翻转后 pinned 行位置正确（展开在最前、折叠被收掉）。

覆盖了任务要求的最小集：快路径成立条件（count 未变）、必须重读条件（count 变）、
翻转后 pinned 行位置、三态 null/true/false 不混同。

[DONE] 5 单测

## 6 未验证项

1. **count 是否变化仍未①档定论**（§2 ②档）。真机验证时加一条日志即可裁决：折叠/展开
   前后 `K7 pinFold fast ... count=N` 的 N 是否变（已在新路径打了 `count=`）。
2. **`detectPinFolded` 兜底是否独立命中**仍未隔离验证（R31 §7.1 遗留，本轮未动它）。
3. **候选 B 的否决前提**「onChanged 早于视图重布局」是 AOSP 时序推理（②档），未真机
   抓帧验证；但既然已否决 B、保留布局监听（①档已验证可靠），该前提不影响结论。
4. **真机体验**：本轮不跑 gradle / 不碰 adb，编译与真机由主会话统一执行。新路径日志
   `K7 pinFold(view) folded=X -> immediate` + `K7 pinFold fast folded=X action=Y count=N`
   用于核对「即时生效 + 走了哪条分支」。

[DONE] 6 未验证项

## 7 变更文件清单

| 文件 | 改动 | 档位 |
|---|---|---|
| `wxgroup/app/src/main/java/com/lx/wxgroup/feature/PinFoldLogic.kt` | +`FoldAction` 枚举 +`foldAction()` 纯函数 | 判据设计 ①（纯逻辑） |
| `wxgroup/app/src/main/java/com/lx/wxgroup/feature/ConvFilter.kt` | +`lastSpecs` 字段、`onPinFoldChanged()`、`rebuildForPinFold()`；布局监听下游改即时；rebuild 三分支写 lastSpecs | 反射/异步时序 ②（count 分支） |
| `wxgroup/app/src/test/java/com/lx/wxgroup/feature/PinFoldLogicTest.kt` | +6 条用例 | 判据 ①（纯逻辑） |

未动：`ConvLayout.kt`（只读）、`GroupRowView.kt`、`HookEntry.kt`、`GroupDb.kt`、`GroupBar.kt`。
`ConvFilter.getView` 里分组行 `GroupRowView.build(...)` 调用形态保持原样。

每条判据是否①档：
- `foldAction` 的 NOOP / count 分支逻辑：①（纯函数语义，字面成立）。
- 「count 变 ⇒ 折叠入口是真 item」/「count 不变 ⇒ header」：②（§2，假设已标）。
- 「onChanged 早于视图重布局」：②（推理，未真机抓帧）。

[DONE] 7 变更文件清单

---

[DONE] ALL

---

## 8 主会话复核与修正（2026-10-28 22:2x）

### 8.1 复核方式变更（如实记录）

原计划派 Pro 子代理 `r32-review` 做第二视角复核，**两次都因上游 400（PacRpcClient 超时 /
管道断开）中断**（`r32-pinfold-sync` 收尾时 25m35s 处失败、`r32-review` 41s 处失败，
后者连 `docs/_r32_review.md` 都没建出来）。改由**主会话逐 hunk 读完 762 行 diff + 对着源码
交叉核对**完成复核。档次低于原计划（不是独立视角），已在此标注，待后续有独立 lane 可补。

### 8.2 逐条核对结论

| # | 检查项 | 结论 | 证据 |
|---|---|---|---|
| 1 | 快路径是否只重算 layout | ✅ | `rebuildForPinFold` 只在 REREAD 分支 `readSnaps`，不调 `rebuildModels`、不查 `db.memberWxids` |
| 2 | 不更新 `lastStamp` 会不会让 layout 停在中间态 | ✅ 不会 | `configStamp` 含 `pinFoldedByView`（1006），下次 `rebuild()` 指纹必不同 ⇒ 走全量，且全量用 `pf`（1089-1096）建 layout，方向正确 |
| 3 | 折叠→展开（净零）是否被短路冻住 | ✅ 不会 | 两次翻转都走快路径改 layout；`lastStamp` 旧值 `pinFolded=false` 与当前一致，短路时 layout 已是展开态，一致 |
| 4 | 复用是否串类型（分组行 ↔ 微信会话行） | ✅ 不会 | 会话行分支自带 `viewTypeOf(it) == getItemViewType(position)` 守卫（1408-1412），类型不符即弃用 convertView |
| 5 | `bind` 对非本类骨架静默返回会不会渲染空行 | ✅ 不会 | 接线处先 `isGroupRow(convertView)`，命中才 bind，否则 `create`（1387-1388） |
| 6 | 头像异步回调错位 | ✅ 不会 | `AvatarLoader` 回调前 `tagMatches(imageView.tag, wxid)`；换人时置 `tag=null` + `setImageDrawable(null)` + 首字层顶回可见 |
| 7 | 监听器是否真的只建一次 | ✅ | `setOnClickListener` / `setOnLongClickListener` 仅在 `create`，回调读 `v.tag as? Holder` 的当前 `onTap/onLongTap` |
| 8 | 点击是否会点到上一个分组 | ✅ 不会 | `bind` 每次无条件写 `h.gid` / `h.onTap` / `h.onLongTap`（早于一切短路） |
| 9 | 单测是否钉住判据 | ✅ | `foldAction` 三条分支 × 正反 + `null` 三态 + 翻转后行位置，共 6 例；变异 `applied==detected`→`!=`、`cachedCount==currentCount`→`!=` 均会变红 |

### 8.3 发现并修复的**新引入缺陷**（R32-1 遗留，P1）

`rebuildForPinFold` 原来的顺序是「先 `snaps = readSnaps(currentCount)` → 再判
`lastSpecs.isEmpty()` 早返回」，留下一个**快照已换、layout 未换**的错位窗口：

- 触发条件：分组行开关关掉（`CHAT_GROUP_ROW_ENABLED=false`）或筛选态或没有分组时，
  `lastSpecs` 为空，但**微信自己的折叠入口仍在**（它是 `wrapped` 的真实 item，由我们的
  adapter 透传渲染），折叠态翻转时 `walkTexts` 照样能扫到文案 ⇒ 进 `rebuildForPinFold`。
- count 变了（REREAD）时 `snaps` 被替换成新长度，`layout` 却还是旧下标 ⇒
  `getView` 按旧下标去 `wrapped.getView(r, ...)` 取行，**点 A 可能进 B**（取越界时被
  `runCatching` 吞成占位空行）。
- 修复：把 `lastSpecs.isEmpty()` 判定**提到 REREAD 之前**；且该分支若 action 是 REREAD，
  置 `lastStamp = ""` 后立刻补一次 `rebuild()`（全量），保证 `layout` 与 `wrapped` 对齐。
  这条分支本来就没有分组行，不存在「分组行慢半拍」的观感问题，正确性优先。
- 为什么之前没暴露：R31/R32 的真机验证都是在**分组行开启**状态下做的，从没走过这条分支。

### 8.4 仍未解决 / 需真机裁决

1. **`wrapped.count` 折叠时是否变化**仍是 ②档（拿不到微信自身 adapter 的 `getCount` 语义
   字面证据）。代码按两种事实分支写成，两种都正确；真机看日志
   `K7 pinFold fast ... action=RELAYOUT_REUSE|RELAYOUT_REREAD` 即可裁决。
2. **流畅度本身**只能真机看：预期从「250ms 去抖 + 全量重建（≈2900 次 getItem + 反射 +
   N 次 SQL）」降到「一帧内的 O(n) 纯计算」，且分组行不再重建树。是否达到「跟手」需人眼确认。
3. `post` 到下一消息循环再多一帧（≈8-16ms），肉眼不可辨，但确实不是「同帧」。

### 8.5 变异实跑（复核 §8.2 第 9 项）

只跑 `PinFoldLogicTest`（22 例），避免全量噪声：

| 变异 | 位置 | 结果 | 是否等价变异 |
|---|---|---|---|
| `applied == detected` → `applied != detected` | `foldAction` NOOP 判据 | 5 红 | 否（精确命中 NOOP 语义） |
| `cachedCount == currentCount` → `!=` | `foldAction` 分支判据 | 3 红 | 否（精确命中 REUSE/REREAD 分支） |

两组均精确变红后还原，还原后 `grep` 复核两处判据文本，全量 802 例重新跑绿。
