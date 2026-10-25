# R22 · 主会话独立取证事实表（K7 点 G2 分组显示/点击错乱）

> 本文件只记**主会话已亲自核实的静态事实**，不含未经证实的根因推断。
> 根因定案见 `docs/_r22_k7_rootcause.md`（Pro lane 产出）。
> 所有行号基于 2026-10-25 17:xx 工作树（`ConvFilter.kt` 相对 HEAD **无未提交改动**，已 `git diff` 核实）。

---

## §0 报障

真机点击**名为 `G2` 的分组**，内容显示错误。用户确认两个现象：
- **现象 3**：列表看着对，但**点某条打开了别的会话**（点错人）
- **现象 4**：**分组行本身信息错**（成员数 / 最近消息 / 未读数）

未选：「混入非成员会话」「该显示的不见了」。

---

## §1 两个态必须分开看（F15，关键前提）

| 维度 | **收纳态** `filterId == 0` | **筛选态** 点进 G2，`filterId > 0` |
|---|---|---|
| 成员来源 | `ConvFilter.kt:868` **实时读库** `db.memberWxids(g.id)` | `ConvLayout.filtered(snaps, cachedMembers)`（`:856`）⇒ **陈旧 `cachedMembers`**（`:108`） |
| 是否渲染分组行 | **有**（`buildModel` `:870` → `models` → `GroupRowView`） | **无**（单测 `filtered_has_no_group_row`，`ConvLayoutTest.kt:256-266`） |
| 会话序列来源 | `snaps` 快照 | `snaps` 快照 |
| 受 F10（改成员不 refresh）影响 | ❌ **不受**（每轮 rebuild 实时读库） | ✅ **受影响**（`cachedMembers` 陈旧） |

⛔ **更正**：主会话早前（F15）误以为收纳态成员数也来自 `cachedMembers`。**实测代码是实时读库**，
只有**筛选态**吃 `cachedMembers` ⇒ **F10 的破坏面仅在筛选态**，比原先判断窄。

⇒ **用户措辞歧义，两种都要覆盖**：
- 「分组行信息错」只能发生在**收纳态**（那里才有分组行）⇒ 用户应该说的是**点进去之前**主列表里 G2 那一行。
- 「点进去之后内容错」= **筛选态** ⇒ 人员集合（F10）+ 顺序/行内容（F13）。

---

## §2 已确证事实

### F1/F2/F13 · snaps 只在条数变化时重算，两处短路导致「顺序变了不重建」

`ConvFilter.kt:827-848`：
```kotlin
fun rebuild() = synchronized(lock) {
    val n = runCatching { wrapped.count }.getOrDefault(0)
    if (snaps.size != n) snaps = readSnaps(n)        // ① 条数不变 ⇒ snaps 恒旧
    ...
    val stamp = "$n|$enabled|$filterId|$showUnread|${cachedMembers.hashCode()}|${groups.hashCode()}"
    if (snaps.isNotEmpty() && stamp == lastStamp) return@synchronized   // ② 指纹不含顺序
    lastStamp = stamp
```
- `snaps`（`ConvLayout.ConvEntry`: wxid + timeMs）是**全量会话快照**。
- **收新消息时 `n` 不变、`stamp` 六项全不变** ⇒ ① ② 同时命中 ⇒ `snaps` 与 `layout` **都不更新**。

### F3 · observer 确实会把「新消息刷新」打进来（所以 rebuild 是被调用的，只是内部短路）

`ConvFilter.kt:617-630`：
```kotlin
inner.registerDataSetObserver(object : DataSetObserver() {
    override fun onChanged() { runCatching { fa.rebuild(); fa.notifyDataSetChanged() } }
})
```

### F4 · 渲染与点击**共用同一个旧下标** ⇒ 两者一致地错（⚠️ 已按 lane 结论更正）

- 渲染：`getView` `:1005` `val r = layout.convIndexOf(position)` → `wrapped.getView(r, cv, parent)`（`:1016`）
- 点击：**不走** `performItemClick`（F11），走微信在 `getView` 时绑到行上的 OnClickListener，绑的**也是** `r`
- ⛔ **早前主会话写的 F7「渲染用快照、点击用实时」是错的**，已推翻：
  两路的下标源**同为** `layout.convIndexOf`（快照），取数**同为** `wrapped.getItem`（实时）。
  真正的不对称不是「谁读实时」，而是**时间上的**：下标 `r` 在快照时刻指向 A，在点击时刻指向 B。
- ⇒ **「看着对、点错」的唯一成立方式**：该行**已渲染（显示 A）之后、点击之前**发生重排，
  且该行**未触发重绘**。若重排发生在渲染前，则表现为「混入非成员 / 该显示的不见」——同根因，不同时间面。
- ⚠️ 「渲染后、点击前是否真会不重绘」本地**无法证**（取决于微信 p3 的行内 listener 是按位置还是按对象绑定）
  ⇒ 列入 §7 待真机。**验收时必须覆盖这个时序**，否则可能测不出来。

### F5 · `buildModel` 同样吃旧快照 ⇒ 分组行副标题「最新消息/时间/未读」卡在旧值

`ConvFilter.kt:900-945`：遍历 `snaps.indices` 找 `snaps[i].wxid in wx` 的第一条当「最新」，
`unread += ConvReader.unread(wrapped.getItem(i))`。
⚠️ **注意**：它用旧下标 `i` 去 `getItem(i)` 实时读 ⇒ **读到的是「现在占这个位置的人」**，
可能根本不是该组成员。例：G2={X,Y}，快照 [A,B,X,C,Y]（X@2），重排为 [Y,A,B,X,C]
⇒ `buildModel` 遍历到 X@2，读 `getItem(2)` = B（非成员）⇒ 摘要/发送人/未读全取自 B。
⇒ `snaps` 陈旧 + `stamp` 短路导致 `models` 不重算（`:870` 在 `else` 分支内，被 ② 挡掉）。

### F5b · 独立的显示缺陷：`findFriend` 取不到就回落成原始 wxid

`ConvFilter.kt:919` `db.findFriend(w)?.displayName ?: w`；而 `group_member` 表**本来就存了
`display_name`**（加人时快照），`buildModel` 却绕去查 `friend_cache`（懒加载增量，可能缺行）
⇒ 副标题直接显示 `wxid_xxx`。与快照错位无关，但会放大现象 4 的观感。

### F10 · 四个改成员入口**全都不调** `ConvFilter.refresh()`（grep `ConvFilter` 结果为空）

| 入口 | 位置 | 改完做了什么 | refresh |
|---|---|---|---|
| `GroupDetailPage` 移出成员 | `GroupDetailPage.kt:262` | 只 `adapter.remove(member)` | ❌ |
| `MemberPage` 点成员移出 | `MemberPage.kt:69` | 只 `adapter.reload()` | ❌ |
| `MemberPage` 清空本组 | `MemberPage.kt:84` `clearMembers` | 只 `adapter.reload()` | ❌ |
| `BatchPicker` 批量应用 | `BatchPicker.kt:167-168` | 只 toast + dismiss | ❌ |
| `GroupPicker` 勾选应用 | `GroupPicker.kt:148-155` | 只 toast | ❌ |
| （对照）`GroupRowMenu` | `:100/105/125` | 有 `refresh()` / `setGroup` | ✅ |

`cachedMembers` 只在 `setGroup()`（`:451`）与 `refreshCache()`（`:646`）赋值，
而 `refreshCache()` 仅在 `cachedId < 0` 时被 `currentGroupId()`（`:421`）触发 ⇒ **一进筛选态就不再自动刷新**。

### F11 · 历史定案与代码矛盾，已查实「performItemClick 零命中」

- `docs/取证结论.md`：「行点击走每行自己的 OnClickListener，不走 `performItemClick`（`onItemClick` hook 零命中）」
- `wxgroup/_dbg_pull.txt:16-20`（真机日志）：三条 `K7: hooked ...performItemClick` **都装上了**
- 但同一日志 `K7 performClick conv` **出现 0 次**（`_dbg_pull.txt` / `_backlog.txt` grep 计数均 0）
- `.workbuddy/memory/2026-10-10.md`「关键实测结论」原文同 F11 上半句，并称「WeChat 在 `getView` 时就把正确的会话绑在行上了」

⇒ **`realPosition()` 的重映射路径是死代码**。推论与修复要点：
- 修「点错人」**不能靠改 `realPosition`**，必须让**传给 `wrapped.getView` 的 `r` 实时正确**。
- ⚠️ 死代码留着有风险：将来若微信改回走 `performItemClick`，会突然生效且用的是旧 `layout`。
  ⇒ 修复时应**要么删掉这条 hook，要么让它与实时 layout 一致**。

### F14 · 不是新回归，v0.2.1 起就有这个设计缺口

`git show` 对比：
- `5221211`（v0.2.1）`:659` `if (snaps.size != n) snaps = readSnaps(n)`、`:665-667` stamp 短路 **已存在**
- `f117396`（v0.2.3）只给 stamp 增加 `showUnread` 与 `groups.hashCode()`，**未改短路条件**

⇒ 解释 2026-10-10「筛选态点成员会话开对聊天 ✓」为何当时通过：
那次是**刚改完成员立刻点**，`setGroup()` 重算 `cachedMembers` 使 stamp 变化 ⇒ 重建成功 ⇒ 顺序是新的。
**只要在该分组停留期间来一条新消息（顺序变、条数不变），就会错。**

---

## §3 复现必要条件（待真机确认）

1. 进入 G2 筛选态（或停在收纳态主列表）；
2. **不退出**的情况下收到新消息（或自己发出）导致会话重排；
3. 此时：点行 → 打开的人与该行显示内容/用户预期不符；看 G2 分组行 → 副标题仍是旧消息与旧未读。

若用户实际是「点进去就错、无需新消息」⇒ 还有第二成因，优先查 **F10 `cachedMembers` 陈旧**（筛选态人员集合错）。

---

## §4 待真机确证（Pro lane §7 同结论）

| # | 问题 | 状态 |
|---|---|---|
| Q1 | 微信 p3 adapter 在 `getView` 给行绑的 listener 是**按位置**还是**按会话对象** | 决定「看着对、点错」的时序，本地不可证 |
| Q2 | 重排是否必经 `notifyDataSetChanged`（不 notify 则「看着对」窗口更长） | 待真机 |
| Q3 | 全量 `readSnaps(2900)` 的真实耗时（决定修复方案去抖是否够） | 待真机 |
| Q4 | `friend_cache` 缺行比例（决定 wxid 泄漏出现频率） | 待真机 |

---

## §5 修复方向（详见 `_r22_k7_rootcause.md` §5，此处只记要点）

**改 `ConvFilter`，`ConvLayout` 不动**（后者是纯函数，29 条单测全绿，错在喂给它的 `snaps` 过期）。

1. `rebuild()` 的 `:829` 由「size 不等才重读」改为「始终重读 + 比较」，
   分层失效：wxid 序列变 ⇒ 重算 layout+models；仅 timeMs 变 ⇒ 只重算 models；全同 ⇒ 才提前返回。
2. `stamp` 不再是最终闸门，退化为「快速跳过 DB 重读」。
3. `observer.onChanged`（`:621-624`）加 250ms 去抖（复用 `:462-479` 的 `rebuildPending`）。
4. 四个改成员入口（`GroupPicker` / `GroupDetailPage` / `MemberPage` / `BatchPicker`）落库后补 `ConvFilter.refresh()`。
5. （可选）`buildModel` 发送人回落改走 `group_member.display_name` 再退 `findFriend`，修 wxid 泄漏。

⛔ **不采用**：把顺序指纹塞进 `stamp`（遍历代价与重读同价，且前 K 条指纹漏中部重排）。

---

[DONE] §0-§5 主会话事实表（已按 Pro lane 结论更正 F4/F7/F15）
