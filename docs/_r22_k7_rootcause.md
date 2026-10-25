# R22 · K7 点 G2 分组显示/点击错乱 根因定案

> 分析人：k7-rootcause（只读源码 + 只写本文件）
> 证据基准：`ConvFilter.kt`、`ConvLayout.kt`、`GroupDb.kt`、`GroupRowView.kt`、`GroupPicker/GroupDetailPage/MemberPage/BatchPicker/GroupRowMenu/EntryReceiver/MarkRead`、
> 真机日志 `wxgroup/_dbg_pull.txt`、`docs/取证结论.md`、`.workbuddy/memory/2026-10-10.md`、`git log`。
> 本版已并入主会话 F10–F15 取证并独立复核（复核结果标注在各节）。

## §0 结论摘要

1. **现象 3（点错人）根因 = 快照下标错位导致「渲染行 ↔ 微信绑定的会话」分叉**。
   已排除 `realPosition`/`hookItemClick` 换算（F11 双证据：`_dbg_pull.txt:17-19` 三层
   `performItemClick` hook 已挂上但 `K7 performClick conv` 计数为 0；`取证结论.md:17`、
   `2026-10-10.md:42` 均写明「点击走每行自己的 OnClickListener，不走 performItemClick」）。
   真根因：`getView` 用 `layout.convIndexOf(position)` 算出的**旧下标 r**（`ConvFilter.kt:1005`）
   调 `wrapped.getView(r, ...)`（`:1016`），微信在 `getView(r)` 里**渲染第 r 条会话并把它绑到
   行自己的 OnClickListener 上** ⇒ 「渲染谁 = 点谁 = 第 r 条」。r 过期时，这一行显示的和点开的
   都是「现在占据旧下标 r 的那个人」，而非「快照时该位置的人」。
2. **现象 4（分组行信息错）根因 = 两条独立成因**：
   - (a) `buildModel()`（`:900-945`）吃**快照** `snaps`（摘要/发送人/时间/未读都按旧下标 `wrapped.getItem(i)`）；
   - (b) **F10**：改成员表的四个入口（GroupPicker / GroupDetailPage / MemberPage / BatchPicker）
     **全都不调** `ConvFilter.refresh()`（已独立逐文件复核，见 §2.5），叠加 `stamp` 不含成员
     （`groups.hashCode()` 无 members 字段），成员增删后列表不重建。
3. **现象 3 与现象 4 是否同根因**：**上游同源、但现象 4 另有独立成因**。共同上游是
   「`snaps` 只在 `snaps.size != n` 时重算（`:829`）+ `stamp` 不含顺序（`:846`）」；
   现象 4 的「成员数错」额外由 F10 造成。修法须两条都治（§5）。
4. **是否 G2 特有**：**非 G2 特有**（§4）。
5. **修复最小改动面**：全在 `ConvFilter`，`ConvLayout` 不改。核心是「快照失效判据改为顺序指纹
   + 成员变更补 refresh」（§5）。

---

## §1 现象 3 逐条推演  [DONE] §1

### 1.1 已排除：`realPosition` 换算（F11 定案）

`hookItemClick`（`ConvFilter.kt:298-343`）hook `performItemClick` 并改 `param.args[1]`，
但真机该 hook **从不触发**：

- 证据 A：`wxgroup/_dbg_pull.txt:17-19` 三行 `K7: hooked …performItemClick`（AdapterView / ListView /
  ConversationListView 全挂上）；但 `grep -c "K7 performClick conv" _dbg_pull.txt` = **0**（`_backlog.txt` 同 0）。
- 证据 B：`docs/取证结论.md:17`（标注「不要推翻」）「行点击走**每行自己的 OnClickListener**，
  不走 `performItemClick`（`onItemClick` hook 零命中）」；`.workbuddy/memory/2026-10-10.md:42` 同义。

⇒ 现象 3 的「点错人」**不可能**由 `realPosition` 换算错引起（那条代码在 8.0.48 上不执行）。

### 1.2 代码内的矛盾（F11 第二问）

`ConvFilter.kt` 内两套说法并存，需修正注释：
- `:680-681`（LongClickRemap KDoc）「点击统一由 [hookItemClick] 在 `performItemClick` 上改参数」——
  **与真机相反**，是历史误记。
- `:266-296`（hookItemClick KDoc）整套假设「点会话会走 performItemClick」——同样不成立。
- 但 `GroupRowView.kt:130-133` 与 `ConvFilter.kt:62-64`（rule 2）才是对的：「会话行是每行自己挂监听，
  onItemClick hook 一次没触发过」。

修订建议写入 `docs/取证结论.md`（措辞见 §7.2）：**「`hookItemClick`/`realPosition` 在 8.0.48
是死代码（performItemClick 零命中）；点击由微信行内 OnClickListener 承担；保留该 hook 仅作防御，
不承担任何正确性职责。」**

### 1.3 真根因：`getView` 快照下标错位

`GroupedAdapter.getView`（`ConvFilter.kt:989-1018`）关键三步：

1. `val r = layout.convIndexOf(position)`（`:1005`）—— `r` 是**快照下标**；
2. `val cv = convertView?.takeIf { viewTypeOf(it) == getItemViewType(position) }`（`:1013`）—— 类型对得上才复用；
3. `wrapped.getView(r, cv, parent)`（`:1016`）—— 用旧下标 `r` 实时调微信渲染。

微信 `getView(r)` 内部：取自己的第 r 条会话 → 渲染名字/摘要 → **把「打开第 r 条会话」绑到该行的
OnClickListener**。因此「这一行显示谁」与「这一行点开谁」**都由同一个 r 决定**（一致）。

`r` 来自 `layout`，`layout` 由 `snaps` 算出；`snaps` 只在 `snaps.size != n` 时重算（`:829`）、
`stamp` 又不含顺序（`:846`）。微信「顺序变化、条数不变」时 `r` 即错位。

### 1.4 精确算例（筛选态）

```
初始（快照新鲜，n=5）:
微信顺序 [A, B, X, C, Y]        G2 成员 = {X, Y}
snaps   = [A, B, X, C, Y]       filtered(G2) = [Conv(2)=X, Conv(4)=Y]
显示位0 → getView(2) = X ✓   显示位1 → getView(4) = Y ✓

Y 来新消息 → 微信把 Y 置顶，条数不变 n=5:
微信新顺序 [Y, A, B, X, C]
rebuild(): n=5, snaps.size==5 → snaps 不重算（仍 [A,B,X,C,Y]）
stamp 六项全不变 → :847 直接 return
layout 仍 = filtered(旧snaps) = [Conv(2), Conv(4)]
重绘: 显示位0 → getView(2) = 微信[2]=B（≠X）   显示位1 → getView(4) = 微信[4]=C（≠Y）
点击: 显示位1 的 OnClickListener 绑的是 微信[4]=C → 打开 C
```

即此刻「显示 C、点开 C」**一致地错**；用户看到的行内容不是他以为的 X/Y。

### 1.5 裁决：是「显示 A 开 B」还是「显示谁开谁」？

- **主机制（代码层可定案）**：`r` 算错 ⇒ 渲染的是 `微信[r]` 的内容、绑定的也是 `微信[r]` ⇒
  **「显示谁、开谁」，二者一致**。现象 3 的准确表述应为：
  **「列表行的内容本身已是错的（把旧下标 r 现在占据的人渲染出来了），点进去的人与该行显示的人一致，
  但都不是用户以为的那个成员。」**
- **可能出现「显示 A、开 B」真错位的唯一路径**：`convertView` 复用 + 微信 `getView` **只在新建时
  绑 listener、复用时不清**（复用后 View 保留旧会话的 listener，却显示新内容）。这是微信字节码行为，
  **本地无法定案**（§7.1 给出确证日志形态）。
- 两种表述对应的修法相同（让 r 实时），但**验收判据不同**：
  - 前者验收：点行后打开的会话 == 该行当前显示的名字；
  - 后者验收：点行后打开的会话 == 该行**本应**显示的成员（需要对照组）。

### 1.6 headerCount / 筛选态 / 收纳态偏移核查

`headerCount(list)` = `hvl.headersCount`（`:670-673`），两态共用同一 HVL（只换 `mAdapter`），
headersCount 恒定。`realPosition` 对分组行返回 -1 且点击链路已先行 `groupIdAt` 拦掉。筛选态
`filtered` 无分组行、`convIndexOf` 直接还原原始下标。**无 off-by-one**。现象 3 与 header 无关。

---

## §2 现象 4 逐条推演（区分收纳态 / 筛选态）  [DONE] §2

### 2.1 先澄清用户口径（F15）

「分组行副标题（成员数/最近消息摘要/未读数）」**只可能出现在收纳态**——只有那里渲染 `GroupRowView`
（`buildModel` → `models` → `getView` 分组行分支 `:992-1003`）；筛选态无分组行（单测
`filtered_has_no_group_row`）。若用户在**点进 G2 之后**看到错，那指的是**点进之前会话列表里 G2
那一行的副标题**（收纳态）。两种都覆盖如下。

### 2.2 收纳态：分组行副标题错的来源

`buildModel`（`:900-945`）输入是 `snaps`（快照）+ `wx = db.memberWxids(g.id)`（实时读库 `:868`）：

- **摘要/发送人/时间错** ← 快照下标错位（§1 同源）：`for (i in snaps.indices)` 找第一个成员命中
  （`:911-920`），命中的是旧下标 `i`，但 `wrapped.getItem(i)` 实时读的是「现在占 i 的人」。
  例：快照 [A,B,X,C,Y]、微信重排 [Y,A,B,X,C]、G2={X,Y} ⇒ 第一个命中 X@2，但 `getItem(2)`=微信[2]=B，
  摘要/发送人全取 B；`timeMs = snaps[2].timeMs` 也是 X 旧位置的时间。
- **成员数错** ← 两道叠加：(1) F10 四入口不 refresh（改完成员列表不重建）；(2) `stamp` 不含成员
  （`groups.hashCode()` 无 members，`Group` data class `GroupDb.kt:1094-1102` 只有
  id/name/color/sortOrder/createdAt/pinned），即便重建被触发也在 `:847` 短路。
  **注意**：收纳态成员数**不是**来自 `cachedMembers`（主会话 F4 的小偏差），而是 `:868` 实时读库；
  但它仍被 stamp 闸门挡住，故表现同样陈旧。
- **未读数错** ← `models` 缓存在 unread 变化时不重算（`stamp` 不含 unread；`MarkRead.clearGroup`
  虽调 `ConvFilter.refresh()`（`MarkRead.kt:56`），但 rebuild 里 stamp 不变 → 短路 → 角标不清）。

### 2.3 筛选态：会错在哪几处（F15 全列）

筛选态 `layout = ConvLayout.filtered(snaps, cachedMembers)`（`:856`），成员集来自**陈旧
`cachedMembers`**（`:108`，仅 `setGroup` `:451` / `refreshCache` `:646` 更新，且 `refreshCache`
仅 `cachedId < 0` 时跑 `:421`）。可错四类：

1. **人员集合错**（F10 直接后果）：显示「已移出的人」/ 漏掉「新加的人」。
2. **顺序错**：`snaps` 快照顺序过期。
3. **行内容错**：`getView` 用旧下标 r 渲染（§1.3）。
4. **未读错**：`models`/`cachedMembers` 陈旧（筛选态虽不渲染分组行，但若成员集合错，列表本身已错）。

### 2.4 `findFriend` 回落 wxid 会不会真机显示原始 wxid（F10/主会话问）

**会。** `sender = db.findFriend(w)?.displayName ?: w`（`:919`）；`findFriend` 查懒加载增量合并的
`friend_cache`（`GroupDb.kt:963-964`、`:878-885`），缓存缺行（群聊未保存好友/未同步）→ 回落 wxid。
`GroupRowView.subtitle`（`:153-165`）有消息时显示 `"$sender：$msg"` ⇒ 副标题直接出现 `wxid_xxx`。
**额外缺陷**：`group_member` 表本有加人时快照的 `display_name`（`GroupDb.kt:733-735`），但
`buildModel` 没用 `membersOf()` 的 `GroupMember.displayName`，绕去 `findFriend`，白丢一层兜底。

### 2.5 成员表所有修改入口是否都调 refresh（F10 独立复核，已确认）

| 入口 | 写成员 | 是否 refresh/setGroup | 证据 |
|---|---|---|---|
| GroupPicker.apply | addMember/removeMember/createGroup | **否**，只 toast | `GroupPicker.kt:134-162` |
| GroupDetailPage 移出成员 | removeMember | **否**；dismiss 只 `GroupBar.refreshAll()`（只刷横向 bar，`GroupBar.kt:1532-1541`→`:1150`，不刷会话列表） | `GroupDetailPage.kt:261-265`、`:152` |
| GroupDetailPage +添加/加入其他分组 | 转 BatchPicker/GroupPicker | **否**（同上述两入口） | `GroupDetailPage.kt:138-140`、`:271` |
| MemberPage 点行移出 | removeMember | **否**，只 `adapter.reload()` | `MemberPage.kt:67-74` |
| MemberPage 清空本组 | clearMembers | **否**，只 `adapter.reload()` | `MemberPage.kt:81-88` |
| BatchPicker 批量加/减 | addMembers/removeMembers | **否**，只 toast+dismiss | `BatchPicker.kt:163-174` |
| GroupRowMenu 置顶/重命名/删除 | setGroupPinned/renameGroup/deleteGroup | **是** | `GroupRowMenu.kt:100,105,124-125` |
| GroupRowMenu 管理成员 | 转 MemberPage | **否**（同 MemberPage） | `GroupRowMenu.kt:107` |
| MarkRead 标记已读 | 改微信对象 | **是**（n>0 时） | `MarkRead.kt:56` |
| EntryReceiver 筛选切换 / kvset 探针 | 无成员写 | setGroup / refresh | `EntryReceiver.kt:152`、`:189` |

**定案：改成员表的四个入口全都不 refresh。** 这是现象 4「成员数/人员名单陈旧」的独立成因。

---

## §3 放大器与触发条件  [DONE] §3

### 3.1 F13 双短路锁死（已逐条复核成立）

`observer.onChanged`（`ConvFilter.kt:621-624`）在微信 notify（新消息/置顶）时**确实会调** `rebuild()`，
但 `rebuild()` 内部两道闸门把新顺序挡死：

- 闸门① `if (snaps.size != n) snaps = readSnaps(n)`（`:829`）：n 不变 ⇒ snaps 恒旧。
- 闸门② `if (snaps.isNotEmpty() && stamp == lastStamp) return@synchronized`（`:847`）：
  `stamp = "$n|$enabled|$filterId|$showUnread|${cachedMembers.hashCode()}|${groups.hashCode()}"`（`:846`）
  六项在新消息场景全不变 ⇒ 直接返回，`layout`/`models` 均不重建。

后果链（已核）：
1. 微信 adapter 第 i 项实时已是「刚收消息的人」；
2. 我们的 `layout` 仍按旧 `snaps` ⇒ `getView(position)` 的 `r` 是旧下标；
3. `wrapped.getView(r)` 渲染「旧第 r 条」内容并把该会话绑到行上 ⇒ 渲染/绑定分叉 ⇒ **现象 3**；
4. `buildModel` 同遍历旧 `snaps` ⇒ 摘要/未读错 ⇒ **现象 4**。

**三条 rebuild 入口（`:623` observer / `:635` install / `:469` refresh）无一条能在「条数不变」时灌入新顺序**。
错误长期驻留，直到 `n` 或某 stamp 分量变化。

### 3.2 F14 非回归、从 v0.2.1 就有（已 git 复核）

`git log -S "snaps.size != n"` 与 `-S "lastStamp"` 均命中 `5221211 feat: K7 会话列表筛选态落地（v0.2.1）`。
`snaps.size != n` 与 `lastStamp` 短路**从 v0.2.1 首版就有**；v0.2.3 只给 stamp 加了 `showUnread`
与 `groups.hashCode()`（`:844` 注释佐证）。故 2026-10-10「筛选态点成员会话开对聊天 ✓」不矛盾——
那次验证时列表顺序没变（刚加成员立刻点，`setGroup` 让 stamp 变 → 重建成功）。

### 3.3 复现必要条件（三步）

1. 进入某分组筛选态（或收纳态）；
2. **不退出**的情况下收到一条新消息（对方发来 / 自己发出导致会话重排、置顶变化）；
3. 此时点行 / 看分组行副标题 ⇒ 出错。

> 若用户实际「点进去就错、无需新消息」，则主因是 `cachedMembers` 陈旧 + F10 四入口不 refresh
> （§2.3），即**第二个成因**，需按 §5-B 修。

---

## §4 是否 G2 特有  [DONE] §4

**非 G2 特有。** `rebuild()`/`buildModel()`/`ConvLayout.build/filtered` 无任何按 `g.id` 特判
（`ConvFilter.kt:827-877`、`:900-945`、`ConvLayout.kt:65-158`）。任何分组的成员只要经历
「顺序变化、条数不变」，都同样错。G2 被先注意到，是因该组「成员近期有新消息 / 成员多 / 位置显眼」
触发概率高——非根因，是暴露概率。

---

## §5 修复判据设计 + 选项取舍  [DONE] §5

### 5.1 快照失效判据（唯一真值源）

**真值源 = `wrapped.getItem(i)` 实时读到的 wxid 序列**（不是 `ConvEntry.timeMs`）。
理由：`ConvLayout.build/filtered` 的锚点/筛选映射只依赖「wxid + 下标」（`ConvLayout.kt:102-114`
只用 `convs[i].wxid`，`timeMs` 仅用于分组行显示时间）。所以：

- **L 层（layout 失效）** ⟺ 实时 wxid 序列（顺序或数量）变化。
- **M 层（models 失效）** ⟺ 任一成员 wxid 下标变化 / timeMs 变化 / unread 变化。

**降级路径**：`readSnaps` 里 `wrapped.getItem(i) == null` 时当前放空 entry 占位（`:890-892`，
为保下标对齐）；做失效判据时，**任何 null 都视为「顺序未知」→ 保守强制重算**，不允许用空 entry
参与相等比较（否则会漏判/误判）。

### 5.2 判据不能「每帧重读 2900 条」（性能约束）

`readSnaps` 对 2900 项各调一次 `wrapped.getItem` + `ConvReader.username` + `ConvReader.timeMs`
（`:886-897`），约 3 次反射/项 ≈ 8700 次反射，主线程不可高频执行。折中：

- **轻量顺序指纹（O(1)）**：`onChanged()` 里只读 `count` + `getItem(0).wxid` + `getItem(count-1).wxid`
  （2 次反射），与上次 `(count, firstWxid, lastWxid)` 比较；**不符才** `readSnaps(n)` 全量。
- **承认残余风险**：中部重排（首末不变）会漏检。要绝对正确必须 O(n) 全量比对。缓解：指纹再抽
  每 256 项一项 wxid（O(n/256)），把漏检窗口压到「一个 256 块内部的局部重排」；仍非绝对。
- **去抖**：把 `observer.onChanged`（`:621-624`）从「每条消息立即 rebuild」改为与 `refresh()`
  一致的 250ms 去抖（复用 `rebuildPending`/`REBUILD_DEBOUNCE_MS`，`:462-479`），把全量读压到
  4 次/秒上限。

结论：**绝对正确 = O(n) 全量比对**；**可接受代价 = O(1) 首末指纹 + 抽样 + 250ms 去抖**，并把
「中部局部重排可能漏检」作为显式已知残余写入注释。若无「既要 O(1) 又要绝对正确」的判据存在，
不要硬造。

### 5.3 stamp 必须并入顺序指纹

现状 `stamp`（`:846`）不含顺序，是第二道闸门短路的根因。修法二选一：

- 方案 A：`stamp` 增加一个「顺序指纹分量」（如上面 O(1)/抽样的 wxid 序列 hash）；
  指纹变 ⇒ stamp 变 ⇒ 短路解除 ⇒ 重算。
- 方案 B：保留 stamp 作「输入不变快速跳过」，但把顺序变化做成独立 flag `orderChanged`，
  短路条件改为 `if (snaps.isNotEmpty() && stamp == lastStamp && !orderChanged) return`。

推荐 B（语义清晰、可单测）。无论 A/B，**顺序变化必须能解开 `:847` 的 return**，否则修了 `:829`
仍被第二道闸门锁死。

### 5.4 最小充分修复集（每点单独治什么、漏了残留什么）

| # | 改动点 | 位置 | 单独看能治 | 漏了会残留 |
|---|---|---|---|---|
| 1 | `snaps` 失效判据：`size != n` → 顺序指纹/全量比对 | `ConvFilter.kt:829` | 现象 3 + 现象 4 摘要/时间/未读（快照错位） | 现象 3/4 快照错位部分残留 |
| 2 | `stamp`/短路并入顺序 | `ConvFilter.kt:846-847` | 同上（缺了 #1 白修） | 即使 #1 生效，第二道闸门仍短路 |
| 3 | `observer.onChanged` 去抖 | `ConvFilter.kt:621-624` | 性能护栏（非正确性） | 全量重读可能卡 UI，但功能对 |
| 4 | F10：四成员入口补 `ConvFilter.refresh()` | GroupPicker/GroupDetailPage/MemberPage/BatchPicker | 现象 4 成员数/人员名单（筛选态） | 成员增删后列表名单仍陈旧 |
| 5 | `buildModel` 发送人兜底改用 `GroupMember.displayName` | `ConvFilter.kt:919`、`:927` | 副标题不再泄漏 wxid | 显示原始 wxid 仍出现 |
| 6（可选防御） | `buildModel` 改 wxid 键实时遍历（仿 `itemsOf` `:804-816`） | `ConvFilter.kt:911-922` | #1 落地后冗余；若 #1 漏检中部重排仍可兜底 | 中部重排漏检时摘要仍错 |

**#1 + #2 是现象 3 的必要充分集；#1+#2+#4 是现象 4 的必要充分集。**

### 5.5 F10 接线方案：逐个补 refresh vs 收口到 GroupDb（比较）

**方案 A（推荐）：四个入口逐个补 `ConvFilter.refresh()`。**
- 优点：职责留在 feature 层，不破坏 `GroupDb`（数据层）的纯净；`refresh()` 本身已
  `mainHandler.post + 250ms 去抖`（`:462-479`），批量加人（addMembers 事务内多次写）也只触发一次重算。
- 缺点：依赖「每个入口都记得加」；未来新入口可能漏。

**方案 B：收口到 `GroupDb` 的写方法（addMember(s)/removeMember(s)/clearMembers）统一触发。**
- 优点：一处收口，天然不漏。
- 缺点（不推荐）：
  1. **层次依赖倒置**：`GroupDb` 被 `ConvFilter` 依赖（读 `memberWxids`），再反向 `GroupDb → ConvFilter`
     形成环；数据层不该知道 UI/列表。
  2. **并发/重入风险**：`GroupDb` 写方法可能跑在后台线程（`BackupFlow`/`ContactSync` 等
     `GroupDb.kt:175`、`:156` 调用方），`refresh()` 虽 post 主线程，但会放大「DB 事务中途 rebuild
     读到半成品」的窗口；且 `rebuild` 内 `db.listGroups()/memberWxids()` 是在主线程读同一 DB，
     与写事务并发，极端下读到旧数据。
  3. 批量 `addMembers` 在单事务里，收口会 post 多次 refresh，虽被去抖合并，但语义混乱。

**结论：方案 A**，并把四个入口的「写完成员后」统一接到 `ConvFilter.refresh()`；为防未来漏接，
可在 `GroupPicker/MemberPage/BatchPicker/GroupDetailPage` 的成员写操作处加一行注释约定
「改成员表必须 `ConvFilter.refresh()`」（不引入新抽象，避免过度设计）。

---

## §6 新增单测用例清单  [DONE] §6

> 在 `ConvLayout` 抽一个纯函数 `fun ConvLayout.orderChanged(a: List<ConvEntry>, b: List<ConvEntry>): Boolean`
> （只比 wxid 序列，不含 timeMs），把 §5.1 的 L 层判据钉成可测。全部落在 `ConvLayoutTest.kt`。

### A 族：顺序变化（含「顺序变化但条数不变」）

| # | 用例名 | 输入 | 期望 |
|---|---|---|---|
| 1 | `orderChanged_identical_false` | a=[A,B,C], b=[A,B,C] | false |
| 2 | `orderChanged_reorder_same_count_true` | a=[A,B,C], b=[C,A,B] | true |
| 3 | `orderChanged_count_change_true` | a=[A,B], b=[A,B,C] | true |
| 4 | `orderChanged_time_only_false` | a=[(A,100),(B,200)], b=[(A,101),(B,201)] | false（timeMs 归 M 层） |
| 5 | `orderChanged_blank_wxid_counted` | a=[A,""], b=[A,B] | true（空 wxid 占位也算位置变化） |

### B 族：重排后布局/点击映射必须对

| # | 用例名 | 输入 | 期望 |
|---|---|---|---|
| 6 | `filtered_reorder_same_count_maps_back` | convs=[A,B,X,C,Y]→[Y,A,B,X,C], members={X,Y} | filtered 后 `convIndexOf`=[3,0]，`dump`="C:X \| C:Y" |
| 7 | `build_reorder_anchor_follows_newest` | 同上，组{G2:{X,Y}} | 分组行锚到 Y@0（新最新位），X 收纳隐藏 |
| 8 | `build_reorder_no_member_leak` | convs 顶部插入非成员 Z 并重排 | Z 不得被当作 G2 成员（未归组则保留为普通会话行） |
| 9 | `click_position_maps_back_after_reorder` | convs=[A,B,X,C,Y]→[Y,A,B,X,C], 组{G2:{X,Y}} | 逐位 `convIndexOf`/`groupIdAt` 还原到新顺序原始下标；分组行 -1 |

### C 族：成员集合变化后 layout 必须变（F10 对应，纯逻辑表达）

| # | 用例名 | 输入 | 期望 |
|---|---|---|---|
| 10 | `build_member_added_changes_layout` | convs 不变，members 从 {X} 改为 {X,Y} | 用**同一份 convs** 两次 build，`dump` 不同（Y 被收纳、组行锚点/成员集变） |
| 11 | `build_member_removed_changes_layout` | members 从 {X,Y} 改为 {X} | `dump` 中 Y 从「收纳隐藏」变为「普通会话行 C:Y」 |
| 12 | `filtered_member_removed_vanishes` | convs 不变，members {X,Y}→{X} | filtered 只剩 X，Y 不再出现 |

C 族说明：`ConvLayout` 是纯函数，无法直接表达「refresh 是否被调用」；它表达的是**判据的输入**
——只要 `refresh` 被正确触发（§5.4-#4），喂给 `build/filtered` 的新成员集就会产出新布局。
「refresh 被调用」这一行为是 `ConvFilter` 的 UI 职责，留在真机/集成验证（§7）。

---

## §7 仍需真机才能确证的部分  [DONE] §7

### 7.1 现象 3 的「显示 A / 开 B」是否真实存在（核心待确证）

需一条真机日志判断微信 `getView(r)` 是「按位置绑 listener」还是「按对象绑 listener」、以及复用是否重绑。
期望日志形态：在 `wrapped.getView` 前后各打一行 `LogX.i("K7 getView r=$r wxidNow=${wxidNow(r)}")`，
配合点击时打印实际打开的 `wxid`；若「显示的人 wxid」≠「点开的人 wxid」则坐实复用串味（§1.5 第二种），
否则坐实「一致地错」（§1.5 第一种）。**在拿到该日志前，现象 3 的准确表述用「一致地错」这一版。**

### 7.2 取证结论修订措辞（F11 第二问）

建议在 `docs/取证结论.md:17` 后追加一条：
> **（R22 修订）`ConvFilter.hookItemClick`/`realPosition` 在 8.0.48 上是死代码**：三层
> `performItemClick` hook 均已挂上（`_dbg_pull.txt:17-19`）但 `K7 performClick conv` 零命中，
> 会话行点击由微信行内 `View.OnClickListener` 承担。该 hook 仅作防御保留，不承担正确性职责；
> `ConvFilter.kt:680-681`、`:266-296` 注释与之矛盾，应一并订正。

### 7.3 其余

- **全量 `readSnaps(2900)` 真实耗时**：决定 §5.2 去抖+指纹后是否仍卡；若卡需找微信侧廉价
  「顺序版本号/稳定 itemId」。
- **`friend_cache` 缺行比例**：决定 `findFriend` 回落 wxid（`:919`）在真机的出现频率，以及是否
  必须改用 `group_member.display_name` 兜底（§5.4-#5）。
- **中部重排漏检率**：§5.2 抽样指纹的真实漏检窗口，需真机压测「首末不变、中部重排」是否常见。

[DONE] ALL
