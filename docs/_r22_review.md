# R22 交叉复核（第二视角）

> 复核人：r22-review（只读源码 + 只写本文件）
> 证据基准：`ConvFilter.kt`（1247 行全文）、`ConvLayout.kt`、`ConvReader.kt`、`GroupDb.kt`、
> `GroupDetailPage/MemberPage/BatchPicker/GroupPicker/GroupManagerPage/GroupBar/GroupRowMenu/BackupFlow/BackupPanelPage/EntryReceiver`、
> `docs/_r22_k7_rootcause.md`、`docs/_r22a_impl.md`、`docs/_r22b_impl.md`。
> 未跑 gradle（主会话已 BUILD SUCCESSFUL + 589 单测全绿）。

## 结论摘要

**有条件可以上真机。** R1（快照冻结 → 点错人）与 R2（成员变更不刷新）两条根因的修复判据**正确、无必然崩溃点、无必然点错人缺陷**。必须先做的一件事不是「修代码」，而是**真机计时验证 Q4 的 `readSnaps(2900)` 主线程开销**——这是本轮唯一把「O(1) 判据」升级为「每次 rebuild O(n) 全量读 + 反射」的高频主线程路径，成本静态无法判定（`wrapped.getItem` 的实现体在微信 dex 里），一旦超帧预算会在收消息风暴时卡 UI。其余发现均为建议级（详见下文）。

- 阻塞级（必须先修）：**无**（未发现必然崩溃 / 必然点错人的代码缺陷）。
- 上机闸门（先验证再放行）：**Q4 性能计时**。
- 非阻塞但建议修：Q1 空 wxid 占位与 §5.1 定案冲突、Q6 标记已读后未读角标不清、GroupBar.createGroup 建组不通知、GroupManagerPage.moveGroup 排序不通知、R3 裸 wxid 显示未落地。

---

## Q1 分层失效判据

**结论：判据本身不「漏」，除一处与 §5.1 定案冲突的空 wxid 占位（见下）。**

### 1.1 layout 每一行由哪些输入决定（穷举）

收纳态 `layout = ConvLayout.build(snaps, specs, members, enabled=true)`（`ConvFilter.kt:974`），
`build()`（`ConvLayout.kt:65-139`）产出的行序列只由 4 类输入决定：

| 输入 | 在 build 里的作用 | 变化由谁捕获 |
|---|---|---|
| `convs`(=snaps) 的 **wxid 序列 + 下标** | `ownerOf[convs[i].wxid]` 判归属（:106）；`anchorOf` 找最新成员下标（:104-108）；`Conv(i)` 存原始下标（:121-133） | `orderChanged`（`ConvLayout.kt:174-180`，逐位比 wxid + 比长度） |
| `groups`(GroupRowSpec 的 id/name/pinned/sortOrder) | pinned 组置顶、normal/tail 按 sortOrder 排序、`ConvRow.Group(groupId)`（:82-136） | `configStamp` 的 `groups.hashCode()`（`ConvFilter.kt:912`） |
| `members`(Map<Long, Set<String>>) | `ownerOf` 归属表 + `anchorOf`（:89-114） | 收纳态=`membersVersion`；筛选态=`cachedMembers.hashCode()` |
| `enabled` | false → passthrough（:71） | `configStamp` 的 `$enabled` |

筛选态 `layout = ConvLayout.filtered(snaps, cachedMembers)`（`ConvFilter.kt:957`），
`filtered()`（`ConvLayout.kt:151-158`）只由 `snaps` 的 wxid 序列 + `cachedMembers` 决定。

### 1.2 逐项验证「是否已被 configStamp 覆盖」

- `groups` 列表：**覆盖**。`groups.hashCode()`，`Group` 是 data class（`GroupDb.kt:1094-1102`，字段 id/name/color/sortOrder/createdAt/pinned），name/pinned/sortOrder/color/createdAt 任一变化都改 hashCode；listGroups 按 `pinned DESC, sort_order ASC, id ASC`（`GroupDb.kt:567`）排序，**顺序**变化也改 hashCode。
- 每个分组的 `pinned`/`sortOrder`/`name`：**覆盖**（同上，落在 Group.hashCode + 列表顺序）。
- 成员集合：**覆盖**。收纳态走 `membersVersion`（`ConvFilter.kt:925` `membersDirty`），筛选态走 `cachedMembers.hashCode()`（:912）。成员增删确实**不会**改 `groups.hashCode()`（`Group` 无 members 字段，`GroupDb.kt:1094-1102`），但成员变化一律经 `refreshMembers()` 递增 `membersVersion`（:519），故不依赖 groups。
- `enabled`：**覆盖**（`$enabled`，:912）。
- `filterId`：**覆盖**（`$filterId`，:912）。
- `showUnread`：**覆盖**（`$showUnread`，:912）。
- `n`（会话条数）：**覆盖**。`orderChanged` 先比 `size`（`ConvLayout.kt:175`），`orderDirty` 里还有显式 `fresh.size != snaps.size`（`ConvFilter.kt:921`）。

### 1.3 反例穷举（「orderChanged 返 false 但 layout 该重算」）

layout 重算的两个充分条件：wxid 序列变（含长度变）或归属/锚点变。

- 序列变 → `orderChanged` 返 true，已覆盖。
- 归属变但序列不变：归属 `ownerOf` 只由 `members` 决定（`ConvLayout.kt:89-92`），`members` 变 → 收纳态 `membersDirty` / 筛选态 `cachedMembers.hashCode`，已覆盖。
- 锚点变但序列不变：`anchorOf[gid]` = 第一个 `ownerOf[convs[i].wxid]==gid` 且 `i ∉ taken` 的 i（:99-114）。序列不变 + 归属不变 ⇒ `anchorOf` 不变。**无反例。**
- 「某一行的 `Conv(index)` 与 `Group(groupId)` 相对归属变了」：归属 = `ownerOf[convs[i].wxid]`，仅随 `members` 变（已捕获）。**穷举后无反例。**

### 1.4 一处与 §5.1 定案冲突（Q1 唯一的「漏」）

`readSnaps` 在 `wrapped.getItem(i)` 返回 null 时塞 `ConvEntry("")` 占位（`ConvFilter.kt:1044`），
`orderChanged` 把 `""` 当普通值逐位相等比较（`ConvLayout.kt:176-177`）。这与根因定案 §5.1 的明文
「任何 null 都视为『顺序未知』→ 保守强制重算，**不允许用空 entry 参与相等比较（否则会漏判/误判）**」直接冲突。

- 漏的场景：连续两次 rebuild 里，**同一位置 i 都读到 null**（`""` == `""` → orderChanged 返 false），
  但底层第 i 条会话在两次读取之间已换人。此时 layout 不重算，第 i 行按旧 `Conv(i)` 渲染，若它是群成员会被当普通行渲染/错位，直到下一次 `getItem(i)` 成功才自愈。
- 现实性：null 是瞬态的（adapter 重载窗口），250ms 去抖使相邻两次 rebuild ≥250ms，null 通常已解析；
  故**自愈概率高**，但它是「判据不再绝对正确」的唯一裂缝，且是显式违背定案。
- 可写成单测的反例（修复后应断言 true）：
  ```
  old = convs("A", "", "C")   // 位置1 getItem 返回 null
  new = convs("A", "", "C")   // 位置1 仍 null，但底层已换人
  assertTrue(ConvLayout.orderChanged(old, new))   // 空 wxid = 顺序未知 → 必须重算
  ```
  修复一行：`orderChanged` 里 `if (old[i].wxid.isBlank() || new[i].wxid.isBlank()) return true`。

---

## Q2 去抖与点错人

**结论：不会「点错人」。用户点分组行进筛选态后立刻点行，看到的是旧收纳态且自洽（显示谁开谁）；不存在「layout 已换、snaps 未换」的渲染组合。**

### 2.1 点分组行后立刻点行的时序

`setGroup(gid)`（`ConvFilter.kt:457-469`）**同步**完成 `cachedId/cachedMembers/db.setPref`，随后 `refresh()` 只是 `mainHandler.postDelayed(250ms)`（:476-490）。故 0–250ms 内：

- `layout` 仍是**旧收纳态**（rebuild 还没跑，:947 未执行）；
- 可见行仍是旧收纳态内容；
- 会话行的 OnClickListener 是上一次渲染时由 `wrapped.getView(r)` 绑定的（`ConvFilter.kt:1169`，r 来自旧 layout 的 `convIndexOf`，:1158）。

此时点某行 → 打开的是「该行当前显示的那条会话」→ **显示谁开谁，自洽，不点错**。唯一体验差异是用户「以为进了筛选态、却点到了收纳态的一行」，这是 ≤250ms 的瞬态，不是正确性缺陷。

### 2.2 「layout 已换、snaps 未换」不存在

`getView` 的渲染路径**只读 `layout`**（`groupIdAt` :1145、`convIndexOf` :1158），**不读 `snaps`**；`snaps` 只被 `wxidOf`（诊断日志 :855）和 `buildModel`（rebuild 内、同一把锁 :1064-1074）读取。`layout` 是 `@Volatile` 引用、单次赋值（:947），且 rebuild 与 getView 都在主线程串行（rebuild 三条入口 `refresh`/`onDataChanged`/`install` 全经 `mainHandler`，:473/:1021/:265-266），不存在撕裂读。所以「layout 新、snaps 旧」的组合不会出现在渲染路径上。

### 2.3 两条去抖链的先后与覆盖

`refresh()` 用 `rebuildPending`（:121/:474-477），`onDataChanged()` 用 `observerRebuildQueued`（:850/:1022-1023），**两个独立标记**。两者同时排队时各发一个 `postDelayed`，均落在同一 `mainHandler` 队列，**严格按 post 顺序串行执行**，不会并发写字段。第二个 rebuild 是幂等的（configStamp/orderDirty/membersDirty 全匹配 → `return@synchronized`，:931 或 :947 重算同一结果）。唯一副作用是极端下多跑一次 rebuild，无正确性影响。

### 2.4 下标自洽确认

- `getView`：layout 下标 → `convIndexOf` → 原始下标 r → `wrapped.getView(r)`（:1158-1169），自洽。
- `getItemViewType`/`isEnabled` 同走 `convIndexOf` 映射（:1228/:1241），自洽。
- `getItem`/`getItemId` 直接把入参当**原始下标**转发（:1133-1137），不经过 convIndexOf——这是文件头注释（:1103-1118）已用真机日志钉死的「双语义」，且点击链路根本不经过 `getItem`（performItemClick 零命中，点击走行内 OnClickListener），本轮未改动，非本轮回退。

---

## Q3 已销毁 list / 并发遍历

**结论：不会崩。** 三点证据：

1. `wrappers` 只装 `ConversationListView`（`hookSetAdapter` 的 `list.javaClass.name == LIST_CLASS` 闸门，`ConvFilter.kt:262`）。GroupDetailPage/MemberPage/BatchPicker 弹窗里的 ListView 是**另一批** ListView，**从未进 `wrappers`**——所以「dialog dismiss 之后 wrappers 里还有 list」这个前提就不成立；`refreshMembers()` 的 `eachWrapper` 只会碰到主会话列表。
2. `eachWrapper` 用 `ArrayList(wrappers.entries)` 快照（`ConvFilter.kt:551`）+ 逐项 `runCatching`（:553-554）。`wrappers` 是 `Collections.synchronizedMap(WeakHashMap)`（:87）：`new ArrayList(entries)` 走 `SynchronizedCollection.toArray()`（同步在 mutex 上复制），与 `wrappers.remove`（:264）/`wrappers[list]=`（:627/:675）互斥，**不会 ConcurrentModificationException**。
3. 即便某条 list 已 detach，`setSelectionFromTop`/`notifyDataSetChanged` 对已 detach 但未回收的 View 是无害的（仅置内部状态/标脏），且被 `runCatching` 兜住，最坏一条 `K7 rebuild failed` 日志，不崩微信。

补充：`wrappers` 的写（remove/put）与读（eachWrapper）都在主线程（setAdapter 钩子的 afterHookedMethod 主线程，:265 `mainHandler.post{ensure}`；install 经 ensure 主线程），WeakHashMap 的 GC 惰性清理（expungeStaleEntries）不递增 modCount，迭代安全。无并发改风险。

---

## Q4 性能

**结论：真实回退、量级待真机计时，是唯一的「上机闸门」。**

1. **`wrapped.getItem(i)` 是否廉价**：`wrapped` 是 `com.tencent.mm.ui.conversation.p3`（微信内层 adapter），`getItem` 是普通接口方法（非反射），常规实现为列表/数组取元素，**预期 O(1)**；但其实现体在微信 dex 里，**静态无法 100% 确证**（根因定案 §7.3 也把「readSnaps 2900 真实耗时」列为待真机确证项）。
2. **反射成本**：`ConvReader.username/timeMs` 字段查找已缓存（`usernameCache`/`timeCache`，`ConvReader.kt:37-38`），warm 后是 `f.get(conv)` 反射读；2900 项 × 2 = 5800 次反射读 + 2900 次 `ConvEntry` 分配，估算 warm 后约 2–5ms/次。
3. **是否主线程每 250ms 一次**：是。`readSnaps` 在 `rebuild()`（`ConvFilter.kt:918`）内，rebuild 经 `mainHandler` 主线程执行；`onDataChanged` 去抖 250ms（:1024-1028）把频率封顶在 4 次/秒。收消息风暴时 ≈ 4×(2–5ms) = 8–20ms/秒 主线程纯 CPU + 2900×4 个 ConvEntry 的 GC 压力。
4. **滚动时可见行重绘**：`notifyDataSetChanged`（onDataChanged 内 :1027）会触发可见行重新 getView（约十几行 → `wrapped.getView`）。但这与旧代码一致（旧代码每条消息都同步 notify，新代码反而 250ms 合并，**notify 频率下降**）；新增的是每次 rebuild 的 `readSnaps(n)` 全量读，而非重绘。
5. **更省写法**：根因定案 §5.2 已给出 O(1)「count + 首末 wxid 指纹 + 每 256 抽一」方案，代价是「中部局部重排漏检」。本实现选了 O(n) 全量（绝对正确）换取性能，属有意的正确性优先取舍。**建议**：若真机计时 readSnaps > ~10ms，退化为「先比 count + 首末 wxid，不符才全量 `readSnaps`」两段式，把常态成本压回 O(1)。

**判定：无法静态断定 getItem 成本 → 上机前必须计时验证；这是「有条件可以」里的那个条件。**

---

## Q5 refreshMembers 竞态

**结论：方案 X 成立，竞态实际不存在。** 证据是「9 个调用点的同步段全在主线程」：

| 调用点 | 位置 | 所在线程 |
|---|---|---|
| GroupDetailPage | `GroupDetailPage.kt:266` | memberMenu 的 AlertDialog 回调，主线程 |
| MemberPage 单条 | `MemberPage.kt:72` | `list.setOnItemClickListener`，主线程 |
| MemberPage 清空 | `MemberPage.kt:88` | `UiKit.confirm` 回调，主线程 |
| BatchPicker | `BatchPicker.kt:173` | 确定按钮 OnClickListener，主线程 |
| GroupPicker | `GroupPicker.kt:161` | positive 按钮 OnClickListener → `apply()`，主线程 |
| GroupManagerPage 收口 | `GroupManagerPage.kt:68` | 长按 actions 的 AlertDialog 回调，主线程 |
| GroupManagerPage 新建 | `GroupManagerPage.kt:80` | `UiKit.input` 回调，主线程 |
| GroupManagerPage 清空 | `GroupManagerPage.kt:97` | `UiKit.confirm` 回调，主线程 |
| BackupFlow | `BackupFlow.kt:215` | `restore()` 调用方全在主线程（见下） |

`BackupFlow.restore` 的三个调用方：`EntryReceiver` "restore" op（`EntryReceiver.kt:713`，`handle` 由 `onReceive` 主线程直调，**不在 `io{}` 里**，:85-113）；"selftest" op（:778，同样不在 `io{}`）；`BackupPanelPage.doRestore`（`BackupPanelPage.kt:281`，dialog 回调主线程）。故 `restore()` 里 `refreshMembers()` 的同步段（`currentGroupId()` + `db.memberWxids`）在主线程。

`setGroup()` 也在主线程（分组行 onTap `ConvFilter.kt:1151`、`GroupRowMenu:124`、`GroupBar` 切换、`EntryReceiver` ACTION_FILTER——全主线程）。

⇒ `refreshMembers()` 的 `currentGroupId()`（`ConvFilter.kt:512`）在 `cachedId < 0` 时触发 `refreshCache()`（:685-694）读 `FILTER_GROUP`，与 `setGroup()` 的 `db.setPref` 同线程串行，**不存在「读到旧 FILTER_GROUP 覆盖新 cachedId」的窗口**。方案 X 的「丢失更新」担忧是**理论风险**：只有未来某处把 `refreshMembers()` 挪到后台线程（并卡在 `cachedId<0` 与 `setGroup` 写库之间的窄窗）才会成真。当前 9 点全主线程 ⇒ 安全。

（附带：`cachedId`/`cachedMembers`/`membersVersion` 均 `@Volatile`（:104-119），即便未来跨线程，单字段可见性也有保障；真正的脆弱点仅是 refreshCache 的「读-改-写 cachedId」非原子。）

---

## Q6 timeFingerprint 代理有效性

**结论：「unread 变但 timeMs 不变」真实存在，且不是边缘场景——就是「标记已读」。代理会造成用户可见的未读角标不清，属已知、已注释、但比 KDoc 措辞更常见。**

- `readSnaps` 只产出 `wxid + timeMs`（`ConvFilter.kt:1047`），`timeFingerprint` 只滚 `timeMs`（:1004-1008），unread/digest 不在指纹里。
- `ConvReader.unread`（`ConvReader.kt:61-71`）读的是会话对象的未读字段；**收新消息**会 bump `field_conversationTime`（timeMs），所以「新消息带来的 unread++」被 timeMs 代理覆盖 ✅。
- **反例**：`MarkRead.clearGroup` 把未读清零，**不改 conversationTime** ⇒ timeMs 不变 ⇒ 轻量路径 `fp == lastTimeFp` 直接 `return@synchronized`（`ConvFilter.kt:931`）⇒ `rebuildModels` 不执行 ⇒ 分组行未读角标**不清零**，直到下一条消息 bump timeMs。
- 用户可见现象：在分组里「标记已读」，回到列表分组行角标仍是旧值。这是现象 4「未读是旧的」的一个子场景，**本轮未修**（MarkRead 不在 9 文件内，且它仍调 `refresh()` 而非 `refreshMembers()`；即便改 refreshMembers 也无效，因为标记已读既不改变 member 也不改变 timeMs）。
- 可接受性：非「点错人」，非崩溃，属陈旧显示；KDoc（:995-1003）已显式声明此局限，但把「unread 变而 timeMs 不变」说成「极端情况」**低估了频率**——标记已读是高频日常操作。建议 MarkRead 走一条强制 `rebuildModels` 的路径（或在 timeFingerprint 里并入 unread 指纹，代价是 readSnaps 多读 2900 次 unread）。

---

## Q7 其它发现

1. **Q1 空 wxid 占位与 §5.1 冲突**（详见 Q1.4，`ConvLayout.kt:176`）：判据唯一裂缝，建议修。
2. **`GroupBar.createGroup`（bar 模式建组）不通知会话列表**（`GroupBar.kt:1500-1507` 只 `refresh(bar)`，未调 `ConvFilter.refresh*`）：新建分组行要等下一次消息才出现在会话列表。这是分组表级变更（会被 groups.hashCode 捕获），但**缺少触发**；与本轮 `GroupManagerPage.kt:80` 给新建分组补了 `refreshMembers()` 形成同功能两条入口不一致。既有问题，非本轮回退，自愈于下次消息。
3. **`GroupManagerPage.moveGroup`（▲▼ 排序）不通知**（`GroupManagerPage.kt:171-179` 只 `reload()`）：sort_order 变了但会话列表分组行顺序不刷新，同样自愈于下次消息。既有问题。
4. **R3（裸 wxid 显示）本轮未落地**：`buildModel` 的 `db.findFriend(w)?.displayName ?: w`（`ConvFilter.kt:1072`、:1080）仍是调研状态（`_r22b_impl.md` §3 明确「只做调研，未改」）。群内未加好友的人仍显示 `wxid_xxx`。这是三条根因之一，**未在本轮 9 文件内修复**，建议单独追踪。
5. **空分组 / groups 为空 / db 为 null / enabled=false 组合**：已核无崩溃。`db==null || !enabled` → passthrough（:948-953）；`groups.isEmpty()` → passthrough（:960-963）；`enabled=false` 与筛选态组合时 passthrough 优先级最高（when 第一分支），`cachedId` 仍保留，重新启用后自动回到筛选态。`refreshCache()` 在 `db==null` 时 `?: return` 早退（:686），`currentGroupId()` 回落 0，无 NPE。
6. **空 wxid 占位对 build/filtered 的影响**：`build()` 的 clean 过滤 blank（`ConvLayout.kt:75-80`）、`filtered()` 判 `isNotBlank()`（:155），blank snap 不会被误归组，不会污染归属表。
7. **`lastMembersVersion` 初值 -1（:847）** 保证首帧强制全量重建，`lastStamp` 初值 ""（:841）同理，首帧路径正确。
8. **`orderDirty = fresh.size != snaps.size || orderChanged(...)` 首项冗余**（:921）：`orderChanged` 已比 size（`ConvLayout.kt:175`），无副作用，仅冗余。

---

## 必须先修（阻塞级）

**无。**

上机闸门（放行前必须做，非代码修改）：对 `readSnaps(2900)` 做主线程计时（`dev.py` 探针在 rebuild 前后打 `SystemClock.uptimeMillis`）。若单次 > ~10ms，回退到根因定案 §5.2 的两段式「先比 count + 首末 wxid，不符才全量 readSnaps」，把常态成本压回 O(1)，接受中部局部重排漏检并写注释。

---

## 建议修（非阻塞）

| # | 位置 | 问题 | 改法 |
|---|---|---|---|
| 1 | `ConvLayout.kt:176-177` | 空 wxid 占位参与相等比较，违背 §5.1「null=顺序未知」 | `if (old[i].wxid.isBlank() \|\| new[i].wxid.isBlank()) return true`，补单测 `orderChanged(convs("A","","C"), convs("A","","C")) == true` |
| 2 | `ConvFilter.kt:1004-1008` / `core/MarkRead.kt` | 标记已读后未读角标不清（timeMs 代理盲区） | MarkRead 走强制 `rebuildModels` 路径，或 timeFingerprint 并入 unread（代价 2900 次 unread 读） |
| 3 | `GroupBar.kt:1500-1507` | bar 模式建组不通知会话列表 | 建组成功后补 `ConvFilter.refreshMembers()` |
| 4 | `GroupManagerPage.kt:171-179` | ▲▼ 排序不通知会话列表分组行顺序 | move 成功后补 `ConvFilter.refresh()`（group 表变更走 groups.hashCode 即可） |
| 5 | `GroupDb.kt` / `ConvFilter.kt:1072,1080` | R3 裸 wxid 未落地（`findFriend ?: w`） | 按 `_r22b_impl.md` §3.3 落地 `findMember` 三级回落 |

---

## 确认无问题的点

（下一轮不必重复审）

1. `rebuild()` 三条入口（refresh / onDataChanged / install）全部经 `mainHandler` 主线程串行，`layout` 单次 `@Volatile` 赋值，渲染路径只读 `layout` 不读 `snaps`——无「半新半旧」组合。
2. 两条去抖链（`rebuildPending` / `observerRebuildQueued`）独立标记、同队列串行、无字段互相覆盖。
3. `eachWrapper` 的 `ArrayList(wrappers.entries)` 快照受 `Collections.synchronizedMap` 的同步 `toArray()` 保护，不会 CME；`wrappers` 只含 ConversationListView，弹窗 ListView 不在其中。
4. 9 个 `refreshMembers()` 调用点全部主线程，`setGroup()` 也主线程，方案 X 无竞态。
5. `orderChanged` 对「条数相同、顺序不同」返回 true（单测 `orderChanged_reordered_returns_true` 覆盖）；「仅 timeMs 变」返回 false（`orderChanged_same_order_different_time_returns_false` 覆盖），分层判据与实现一致。
6. `configStamp` 六项（n/enabled/filterId/showUnread/cachedMembers.hashCode/groups.hashCode）对 groups 的 name/pinned/sortOrder/顺序变化全覆盖（Group 是 data class + listGroups 按序返回）。
7. 空分组、groups 为空、db 为 null、enabled=false、首帧初始化（lastMembersVersion=-1/lastStamp=""）均无崩溃路径。
8. `GroupRowMenu` 删除/重命名/置顶走 `refresh()`，靠 `groups.hashCode()` 变化触发全量重建并重读 memberSets（deleteGroup 级联清成员的场景也被覆盖），无需改 refreshMembers。
9. 批量入口（BatchPicker/GroupPicker/GroupManagerPage 清空）都在循环外/落库后调一次 `refreshMembers()`，去抖合并，无「循环内重复刷新」。

[DONE]
