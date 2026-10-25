# R22 Lane B 实现记录

> **最终状态**：6 个成员写入口全部改调 `ConvFilter.refreshMembers()`。
> 这里有个关键坑：一开始按原方案补的是 `ConvFilter.refresh()`，但 `refresh()` **不重读成员缓存**，
> 会被 `rebuild()` 的 stamp 短路吞掉 —— 筛选态下 6 处全是无效功。
> 第 2 章是完整推理，作为后续 review 的判据。最终由 lane A 新增 `refreshMembers()` 解决。

---

## 1. 六处改动清单（最终版）

| # | 文件 | 落库行(改前) | 落库调用 | 改调用的方法 | 状态 |
|---|---|---|---|---|---|
| 1 | `feature/GroupDetailPage.kt` | 262 | `db.removeMember(groupId, member.wxid)` | L266 `ConvFilter.refreshMembers()`（`adapter.remove` 之后） | ✅ |
| 2 | `feature/MemberPage.kt` | 69 | `db.removeMember(groupId, m.wxid)` 单条移出 | L72 `ConvFilter.refreshMembers()`（`adapter.reload()` 之后） | ✅ |
| 3 | `feature/MemberPage.kt` | 85 | `db.clearMembers(groupId)` 清空本组（`UiKit.confirm` 回调内） | L88 `ConvFilter.refreshMembers()`（`adapter.reload()` 之后） | ✅ |
| 4 | `feature/BatchPicker.kt` | 167-168 | `db.addMembers(...)` + `db.removeMembers(...)` | L173 `ConvFilter.refreshMembers()`（两个落库之后、`dismiss()` :175 **之前**） | ✅ |
| 5 | `feature/GroupPicker.kt` | 150/152/155 | 循环体内 `addMember`/`removeMember`；循环后 `addMember` | L161 `if (target != null \|\| added > 0 \|\| removed > 0) ConvFilter.refreshMembers()`（**两个 for 之外**） | ✅ |
| 6 | `feature/GroupManagerPage.kt` | 80 | `for (g in db.listGroups()) n += db.clearMembers(g.id)` | L97 `ConvFilter.refreshMembers()`（**for 之后**，`adapter.reload()` 之前） | ✅ |

统一注释（6 处一致）：
```kotlin
// 成员关系变了 ⇒ 重读 cachedMembers 再重算（refresh() 不重读成员，筛选态下会无效）
```

补充说明：

- **路径**：任务书起初写 `ui/`，实际都在 `feature/` 包（`ui/` 只有 `Panel`/`Rows`/`UiKit`/`AvatarCompose`）。已按实际路径改，**未新建任何文件**。
- **import**：五个文件都是 `package com.lx.wxgroup.feature`，与 `ConvFilter` 同包，**无需 import**。`ConvFilter` 是 `object`（`ConvFilter.kt:74`），`refreshMembers()` 是 public（:511），直接静态调用。
- **`GroupPicker` 的条件保留**：`if (target != null || added > 0 || removed > 0)` —— 用户开了弹窗又点确定、什么都没改时不刷新。
- **「写库成功之后」的判定**：`removeMember`/`addMember` 虽是 `Boolean`，但既有代码不看返回值（`GroupDetailPage:262` 只拿 `n` 打日志，`MemberPage:69` 直接丢弃），保持一致 —— 落库调用之后即刷新。空操作刷新本来就被 stamp 短路，无代价。
- **以「一次用户操作」为单位**：`BatchPicker` 两个落库后调一次；`GroupPicker` 两个 for 结束后调一次；`GroupManagerPage` for 结束后调一次。**没有写在循环体内**。

---

## 1.1 `refresh()` vs `refreshMembers()` 语义区分

这是本轮最重要的接口约定，写进文档防止以后用错：

| 方法 | 语义 | 什么时候调 | 会重读成员吗 |
|---|---|---|---|
| `ConvFilter.refresh()` | **会话数据**变了 | 微信自己刷新、标记已读、分组表变更 | ❌ 不会 |
| `ConvFilter.refreshMembers()` | **分组/成员关系**变了 | 加删成员、清空成员、清空全部分组、备份恢复 | ✅ 会 |

**我这 6 处全部属于后者。**

`refreshMembers()` 实现（`ConvFilter.kt:511-521`，lane A 提供）：
```kotlin
fun refreshMembers() {
    val id = currentGroupId()
    val db = GroupDb.current()
    cachedMembers = if (id > 0 && db != null) {
        runCatching { db.memberWxids(id) }.getOrDefault(emptySet())
    } else {
        emptySet()
    }
    membersVersion++
    refresh()
}
```
三件事：① 重读 `cachedMembers`（修筛选态）② 递增 `membersVersion`（修收纳态，见 :927-929）③ 再走原 `refresh()` 保留下拉锚点等逻辑。

⚠️ 注意它**不碰 `cachedId`**。作者注释（:500-509，方案 X）解释了原因：
`cachedId` 由 `setGroup()` 独占管理，若在这里 `refreshCache()` 全量重读，
会在 `setGroup` 写 `FILTER_GROUP` 的竞态窗口内读到旧值并覆盖新 `cachedId`（丢失更新）。
所以只重读 `cachedMembers` —— 这个取舍是对的，我原本建议的「直接调 `refreshCache()`」在这一点上不够严谨。

---

## 2. ⛔ 核心分析：为什么筛选态下 `refresh()` 无效

**这一节是本轮最有价值的发现。若没有它，上面 6 处 refresh 在筛选态下全是无效功。**

### 2.1 `refresh()` 的完整实现（原文，ConvFilter.kt:460-481）

```kotlin
/** 分组/成员数据变了（增删改分组、加删成员）之后调一次，让列表重算 */
fun refresh() {
    mainHandler.post {
        if (rebuildPending) return@post
        rebuildPending = true
        mainHandler.postDelayed({
            rebuildPending = false
            eachWrapper { fa, list ->
                rememberAnchor(list)
                fa.rebuild()
                // 复位放在 notify **之前**：此刻还没有「数据变化」，
                // 微信不会把这次置位当成下拉手势。（放在 notify 之后 —— 无论是
                // 同批还是延后 —— 都会把小程序面板整个展开，真机实测。）
                //
                // 注意还原的是**原锚点**而不是 0：见 [headerAnchor] 的注释，
                // 拉到 0 会把微信的超高 header 顶回来，反而制造出一次"下拉"。
                restoreAnchor(list)
                fa.notifyDataSetChanged()
            }
        }, REBUILD_DEBOUNCE_MS)
    }
}
```

**关键点：整个函数体只有 `post` → `eachWrapper` → `fa.rebuild()`。
没有任何一处读库、没有任何一处给 `cachedMembers` 赋值。**

注释也直说了它的定位是「让列表重算」，**不是**「重读成员数据」。二者是两件事。

### 2.2 `cachedMembers` 被赋值的全部位置（grep 全量结果）

```
:108  private var cachedMembers: Set<String> = emptySet()        ← 声明（初值）
:451      cachedMembers = if (target > 0) { ... }                ← 仅 setGroup() 内
:646      cachedMembers = if (cachedId > 0) { ... }              ← 仅 refreshCache() 内
:846      ... ${cachedMembers.hashCode()} ...                    ← 只读（stamp）
:856      ConvLayout.filtered(snaps, cachedMembers)              ← 只读（真正喂给布局）
```

**全仓只有两处写入**：

1. `setGroup(id)`（:446-458）—— 用户点分组条切组时调用。
   调用点：`EntryReceiver:152`、`GroupBar:1314/1449/1521`、`GroupRowMenu:124`。
2. `refreshCache()`（:645-653）—— **只在 `currentGroupId()` 里 `cachedId < 0` 时懒调一次**（:421）：

```kotlin
// :420-423
fun currentGroupId(): Long {
    if (cachedId < 0) refreshCache()   // ← 只在「还没从库里读过」时读一次
    return cachedId.coerceAtLeast(0)
}
```

`cachedId` 初值是 `-1L`（:105），一旦被 `setGroup`/`refreshCache` 写成 ≥0，**这个条件就永久为假**。
也就是说：**进程启动后，成员集合只会在「用户点一次分组条」时刷新一次，之后再也不刷新。**

`refreshCache()` 原文（:645-653）：

```kotlin
private fun refreshCache() {
    val db = GroupDb.current() ?: return
    val id = db.getPref(PrefKeys.FILTER_GROUP, NONE.toString()).toLongOrNull() ?: NONE
    cachedId = id.coerceAtLeast(0)
    cachedMembers = if (cachedId > 0) {
        runCatching { db.memberWxids(cachedId) }.getOrDefault(emptySet())
    } else {
        emptySet()
    }
}
```

⚠️ 注意它也写 `cachedId` —— 这是最终没有直接复用它、而是新写 `refreshMembers()` 的原因（见 2.6 方案 X 论证）。

### 2.3 `rebuild()` 的 stamp 短路（ConvFilter.kt:846-848）

```kotlin
val stamp = "$n|$enabled|$filterId|$showUnread|${cachedMembers.hashCode()}|${groups.hashCode()}"
if (snaps.isNotEmpty() && stamp == lastStamp) return@synchronized
lastStamp = stamp
```

stamp 的六个组成项：

| 组成项 | 含义 | 用户「移出一个成员」时会变吗 |
|---|---|---|
| `n` | 微信 adapter 的会话总数 | ❌ 不变（移出分组不影响微信会话数） |
| `enabled` | `CHAT_GROUP_ROW_ENABLED` 开关 | ❌ 不变 |
| `filterId` | 当前筛选的分组 id | ❌ 不变（还在看同一个组） |
| `showUnread` | `CHAT_SHOW_UNREAD` 开关 | ❌ 不变 |
| `cachedMembers.hashCode()` | **筛选态用的成员集** | ❌ **不变**（见 2.2，refresh 不重读） |
| `groups.hashCode()` | 分组表 | ❌ **不变**（见 2.4，`Group` 不含成员） |

**六项全不变 ⇒ stamp 与上次完全相同 ⇒ `return@synchronized` ⇒ rebuild 在第二行就退出，一个 View 都没碰。**

`refresh()` 唯一副作用是 `fa.notifyDataSetChanged()`，但那是在 `rebuild()` **之后**才调的；
`rebuild()` 提前 return 不抛异常，`notifyDataSetChanged()` 照常执行 ——
于是微信用自己的 adapter 重画一遍，**画的还是 `ConvLayout.filtered(snaps, cachedMembers)` 那份旧布局**（:856）。
表现为：列表闪一下，分组行的人数/名单一个字都没变。这解释了用户报障的「点进去刷新了但数字不对」。

### 2.4 `Group` 为什么不含成员（GroupDb.kt:1094-1102）

```kotlin
data class Group(
    val id: Long,
    val name: String,
    val color: Int,
    val sortOrder: Int,
    val createdAt: Long,
    /** 手动置顶。置顶的分组排在会话列表最前（见 listGroups 的 ORDER BY） */
    val pinned: Boolean = false,
)
```

**六个字段全是分组自身的属性，成员关系在独立的 `group_member` 表里。**
所以 `groups.hashCode()` 天然对成员增删不敏感 —— 这是设计使然（成员可能有几百条，不该进 value class），
但也正是 R2 的根因：**stamp 里唯一与成员有关的项就是 `cachedMembers`，而它不更新。**

对照注释（:837-840）也能看出作者早就意识到 stamp 要「包含分组本身」，但当时是为了修「置顶/重命名不刷」：

```
// ⚠️ 指纹**必须包含分组本身**：置顶 / 重命名 / 删分组改的是分组表，
// 会话数和成员集都没变 —— 少了这一项，改完置顶列表纹丝不动
// （真机实测：DB 里 pinned 已经是 1，分组行还留在原位）。
// Group 是 data class，hashCode 覆盖 id/name/pinned/sortOrder，够用了。
```

**同一个坑，成员维度上又踩了一次。**

### 2.5 推理链（一句话版）

```
用户移出成员
  → DB 里 group_member 少一行 ✅（落库是真的成功了）
  → 若只调 ConvFilter.refresh()（原方案）⚠️
      → 它只 post 一个 rebuild，不重读成员 ⚠️
          → rebuild() 算 stamp
              → cachedMembers 还是旧的（没人给它新值）
              → groups 本来就不含成员
              → 六项全同 → return@synchronized ⛔
                  → notifyDataSetChanged() 重画旧布局
                      → 分组行人数/名单不变 ❌ ← 用户看到的现象
  → 改调 ConvFilter.refreshMembers()（最终方案）✅
      → 重读 cachedMembers（筛选态变）
      → membersVersion++（收纳态强制重查 memberSets）
      → 短路不成立 → 完整重建 → 分组行正确 ✅
```

**结论：只调 `refresh()` 是必要的（不加连 rebuild 都不会触发），但不充分。
必须让 `cachedMembers`/`memberSets` 在刷新路径上被重读，或者用一个显式版本号打破 stamp 短路。**

### 2.6 修复方案（最终采纳方案 C）

**方案 A**：`refresh()` 开头补 `refreshCache()`。
- 优点：一行。缺点：`refreshCache()` 会重读 `cachedId`，与 `setGroup()` 写 `FILTER_GROUP` 存在丢失更新竞态（lane A 在 :500-509 有详细论证）。
- 结论：**不够严谨，未采纳**。

**方案 B**：不改 `refresh()`，改 stamp —— 把 `cachedMembers.hashCode()` 换成 `db.allMemberWxids().hashCode()`。
- 缺点：全表查询，且 `rebuild()` 被微信高频调用，会把 stamp 从纯内存变成每帧一次 SQL。**不推荐**。

**方案 C（✅ 最终采纳）**：新增 `fun refreshMembers()`（`ConvFilter.kt:511-521`）。

```kotlin
fun refreshMembers() {
    val id = currentGroupId()          // 只读 cachedId，不写（避开 setGroup 竞态）
    val db = GroupDb.current()
    cachedMembers = if (id > 0 && db != null) {
        runCatching { db.memberWxids(id) }.getOrDefault(emptySet())
    } else {
        emptySet()
    }
    membersVersion++                   // 让收纳态的 memberSets 也强制重读
    refresh()                          // 复用原重建逻辑（锚点、去抖都保留）
}
```

它同时解决了 2.7 提到的收纳态问题 —— `membersVersion` 被纳入 rebuild 的短路判断：

```kotlin
// :927-929
val membersDirty = membersVersion != lastMembersVersion
if (!membersDirty && !orderDirty && configStamp == lastStamp && snaps.isNotEmpty()) {
    ...
    return@synchronized
}
```

**只要 `refreshMembers()` 被调过，`membersDirty` 必为 true，短路必然不成立，两种态都能刷新。**
这比我原本提的两个方案都干净 —— 它把「成员变了」做成一个**显式版本号**，
而不是靠猜 stamp 里哪个字段会变。这是正确的方向：**隐式指纹判断不适用于「表外的关联数据」。**

我这 6 处调用点因此全部改调 `refreshMembers()`。

### 2.7 ⚠️ 归一态（filterId == 0）是另一个坑 —— 方案 A 治不了，方案 C 治得了

筛选态（`filterId > 0`）走 `cachedMembers`（:856），重读 `cachedMembers` 即可。

但**归一态**（没选分组，显示全部分组行）走的是**另一条分支**：

```kotlin
// :867-871 附近，完整 rebuild 分支
for (g in groups) {
    val wx = runCatching { db.memberWxids(g.id) }.getOrDefault(emptySet())
    members[g.id] = wx
    newModels[g.id] = buildModel(db, g, wx, showUnread)
}
models = newModels
```

这里的成员集合是从**库现查**的（正确），但它被存进 `memberSets`，且**只有跑完 stamp 检查后才会执行**。
`refreshCache()` 只影响 `cachedMembers`，**完全影响不到这条分支** —— stamp 里没有 `memberSets` 相关项，
六项依旧全同，依旧短路。

⇒ 若只按方案 A 改，**归一态下用户移出成员，分组行同样不更新**。

方案 C 通过 `membersVersion` 解决了：`membersDirty` 为 true 时无条件走完整重建，`memberSets` 必然重读。

> 注：lane A 已同步更正了 `ConvFilter:789-792` 那段旧注释（原文写「若某条成员增删路径既没改分组表、
> 也没调 refresh，收纳态的成员集合会滞后」—— 这个结论是错的，实际是「调了 refresh 也没用」）。
> 现在 `memberSets` 的注释已经改成「成员变化都走完整重建」，与实际实现一致。

---

## 3. R3 建议：`displayName` 回落

### 3.1 现状

`ConvFilter.kt:919`：
```kotlin
sender = runCatching { db.findFriend(w)?.displayName }.getOrNull() ?: w
```

**同样的写法在 :927 还有一处**（A4-c：整组没消息时显示前 3 个成员名），容易被漏：
```kotlin
val names = if (timeMs == 0L && wx.isNotEmpty()) {
    wx.take(3).map { w ->
        runCatching { db.findFriend(w)?.displayName }.getOrNull() ?: w    // :927
    }
}
```

`findFriend` 查的是 `friend_cache` 表。群内未加好友的人不在里面 ⇒ 回落成裸 wxid。
用户报障的「名单不对」很可能就是这里显示了一串 `wxid_xxxxx`。

### 3.2 调研：`GroupDb` 有没有现成方法？

**没有。** 全部成员相关方法：

| 方法 | 行 | 能否按 wxid 单查名字 |
|---|---|---|
| `addMember` / `removeMember` / `addMembers` / `removeMembers` / `clearMembers` | 723-811 | ❌ 写操作 |
| `memberWxids(groupId)` | 814 | ❌ 只返回 `Set<String>`，无名字 |
| `allMemberWxids()` | 831 | ❌ 同上，全表 |
| `membersOf(groupId)` | 840 | ⚠️ 有名字，但**必须传 group_id**，返回整组 |
| `countMembers(groupId)` | 868 | ❌ 只数个数 |

`group_member` 表结构（:84-91）：
```sql
CREATE TABLE IF NOT EXISTS group_member (
    group_id     INTEGER NOT NULL,
    wxid         TEXT    NOT NULL PRIMARY KEY,   -- ← wxid 是主键！
    wxid_type    INTEGER NOT NULL DEFAULT 0,
    display_name TEXT,
    added_at     INTEGER NOT NULL
)
```

**`wxid` 是主键** ⇒ 按 wxid 单查是主键命中，一行，成本极低。

### 3.3 推荐做法

在 `GroupDb` 新增（位置建议紧跟 `membersOf` :849 之后，与其它成员查询放一起）：

```kotlin
/** 按 wxid 反查 group_member 里快照的备注名（wxid 是主键，单行命中） */
fun findMember(wxid: String): GroupMember? {
    if (wxid.isBlank()) return null
    readableDatabase.query(
        T_MEMBER, null, "wxid=?", arrayOf(wxid), null, null, null
    ).use { cursor ->
        return if (cursor.moveToFirst()) cursor.toMember() else null
    }
}
```
（`private fun Cursor.toMember()` 已存在于 :1054，同文件内可直接用。）

然后 **:919 和 :927 两处都**改成三级回落：

```kotlin
// :919
val sender = runCatching {
    db.findFriend(w)?.displayName
        ?: db.findMember(w)?.displayName
}.getOrNull()?.takeIf { it.isNotBlank() } ?: w
```

```kotlin
// :927
wx.take(3).map { w ->
    runCatching {
        db.findFriend(w)?.displayName ?: db.findMember(w)?.displayName
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: w
}
```

注意加了 `.takeIf { it.isNotBlank() }` —— 原代码只判 null 不判空串，
`displayName` 是 `TEXT` 可空列，写入时 `put("display_name", f.displayName)` 也可能落空串，
不判的话会出现「名字是空白」的行。

### 3.4 为什么是这个顺序

**为什么 `findFriend` 优先，而不是 `findMember` 优先？**
`friend_cache` 是通讯录的**实时快照**，用户改了备注立刻反映；
`group_member.display_name` 是**加人那一刻**的快照（`addMember` 时写入，:734）。
前者更新，所以好友优先、群成员兜底。**顺序反了会显示旧备注。**

**为什么用 `findMember` 而不是 `membersOf`？**
`membersOf` 要 `group_id` 入参，而这里只有 `wxid`，根本调不了。
就算能调，它返回整组（几十~几百行）只为取一个名字，是 N+1 放大；
而 `findMember` 是主键单行。**`buildModel` 是在 `rebuild()` 里按会话循环调用的，
每条会话一次查询，必须用最便宜的那个。**

### 3.5 交付说明

⚠️ 按任务书要求 R3 **只做调研**，所以：
- 我**没有**把 `findMember` 写进 `GroupDb.kt`
- 我**没有**改 `ConvFilter.kt:919/927`

方法体和改法都写在上面，主会话或 lane A 批准后可直接落。

---

## 4. 边界检查结论

### 4.1 `eachWrapper` 有兜底 —— 不会崩

`ConvFilter.kt:510-516`：
```kotlin
private fun eachWrapper(block: (GroupedAdapter, ListView) -> Unit) {
    val snapshot = ArrayList(wrappers.entries)
    for ((list, fa) in snapshot) {
        runCatching { block(fa, list) }.onFailure { LogX.w("K7 rebuild failed: ...") }
    }
}
```

- 逐项 `runCatching`：单个 list 抛异常不影响其它 list。
- 先快照再遍历：避免遍历时 map 被改抛 `ConcurrentModificationException`。
- `wrappers` 是 `Collections.synchronizedMap(WeakHashMap<ListView, GroupedAdapter>())`（:87）：
  **WeakHashMap 的 key 是弱引用**，ListView 被回收后 entry 自动消失，不会持有已销毁的 View。

⇒ **即使弹窗 dismiss、Activity 销毁，最坏情况只是 rebuild 抛异常被吃掉并记一条 `K7 rebuild failed` 日志，不会崩。**

### 4.2 落库与 dismiss 的先后 —— 6 处全在 dismiss 之前

| 入口 | 落库行 | 刷新行 | dismiss 行 | 判定 |
|---|---|---|---|---|
| `GroupDetailPage` | 262 | 266 | 无（`setItems`，选中即自动关） | 在同一次点击回调内，先于弹窗回收 ✅ |
| `MemberPage` 单条 | 69 | 72 | 无（列表项点击，dialog 常驻） | ✅ |
| `MemberPage` 清空 | 85 | 88 | 无（`UiKit.confirm` 回调，dialog 常驻） | ✅ |
| `BatchPicker` | 167-168 | 173 | **175** | 在 dismiss **之前** ✅ |
| `GroupPicker` | 150-155 | 161（`apply()` 内） | **127**（`apply()` 返回后） | 在 dismiss **之前** ✅ |
| `GroupManagerPage` | 95 | 97 | 无（`PanelPage`，页面常驻） | ✅ |

`BatchPicker` / `GroupPicker` 都是 `AlertDialog`，刷新都排在 `dismiss()` 之前 ——
**不存在「在 dismiss 回调里才刷新、操作不到已销毁 list」的隐患。**

### 4.3 去抖不会丢最后一次落库

`refresh()` 的守卫（:462-479）：
```kotlin
if (rebuildPending) return@post      // 已有待执行的，直接跳过
rebuildPending = true
mainHandler.postDelayed({ rebuildPending = false; eachWrapper {...} }, REBUILD_DEBOUNCE_MS)
```
`REBUILD_DEBOUNCE_MS = 250L`（:82）。

250ms 内连续多次落库（`BatchPicker` 一次点确定就落两次库）只会触发一次 rebuild，**但不会漏**：
pending 窗口结束时执行的那次 rebuild 读的是**当时最新的库**。
唯一理论盲区是「落库恰好发生在 rebuild 执行之后、`rebuildPending` 复位之前」——
但那需要落库在另一个线程且时序卡在极窄的窗口内；我的 6 处全在主线程，与 `postDelayed` 同一队列，不会交错。

### 4.4 线程 —— 全部主线程

`GroupDetailPage.open` / `MemberPage.show` 自己就 `mainHandler.post`；
`BatchPicker` / `GroupPicker` / `GroupManagerPage` 的按钮回调本来就在主线程。
`refresh()` 内部再 `post` 一层，双保险。

---

## 5. 副作用提示：`refresh()` 语义未变，既有调用方不受影响

最终方案是**新增** `refreshMembers()`，**没有改 `refresh()`** —— 所以既有调用点行为完全不变。
这是方案 C 相对方案 A 的额外好处：零回归风险。

现有 `refresh()` 调用点（review 时确认它们**应该**保持 `refresh()`，不要误改）：

| 调用点 | 场景 | 该用哪个 |
|---|---|---|
| `core/MarkRead.kt:56` | 标记已读（会话数据变，成员没变） | `refresh()` ✅ 正确 |
| `feature/GroupRowMenu.kt:100/105/125` | 分组置顶/改名/删除（分组表变，靠 `groups.hashCode()` 过 stamp） | `refresh()` ✅ 正确 |
| `entry/EntryReceiver.kt:189` | 快捷方式触发 | `refresh()` ✅ 正确 |
| `data/BackupFlow.kt:211` | **备份恢复写成员** | ⚠️ **应该是 `refreshMembers()`** |

### ⚠️ 遗留：`BackupFlow.kt:211` 用错了方法

原文（:208-211）注释写着「必须走 `ConvFilter.refresh()` 而不是直接 notify」：
```kotlin
// ⚠️ 必须走 `ConvFilter.refresh()` 而不是直接 notify：它内部会
runCatching { com.lx.wxgroup.feature.ConvFilter.refresh() }
```

备份恢复是**写成员表**的操作，属于「分组/成员关系变了」，应该用 `refreshMembers()`。
用 `refresh()` 的话，恢复完筛选态下的分组行人数不会更新 —— **这是个既存的 bug**，
不会被本次修复覆盖（因为 `refresh()` 本身没改）。

> `BackupFlow.kt` 归主会话管（team-lead 明确说过「`data/BackupFlow.kt:175` 主会话自己改」），
> 我只记录，**未改**。建议把 :211 改成 `ConvFilter.refreshMembers()`。

---

## 6. 自检

- [x] 6 个落库点全覆盖：`GroupDetailPage:262` / `MemberPage:69` / `MemberPage:85` / `BatchPicker:167-168` / `GroupPicker:150,152,155` / `GroupManagerPage:80`
- [x] 6 处全部改调 `ConvFilter.refreshMembers()`，**无 `refresh()` 残留**（grep 已确认）
- [x] 所有调用都在**写库之后**，无失败分支误加
- [x] 批量操作**循环外只调一次**：`GroupPicker:161`（两个 for 之外）、`GroupManagerPage:97`（for 之后）
- [x] `MemberPage` 两处都改了（单条移出 + 清空本组）
- [x] `GroupPicker` 的 `if (target != null || added > 0 || removed > 0)` 条件**保留**，仅换方法名
- [x] 6 处注释统一为「成员关系变了 ⇒ 重读 cachedMembers 再重算（refresh() 不重读成员，筛选态下会无效）」
- [x] 未改 `ConvFilter.kt` / `ConvLayout.kt` / 任何 test / `data/BackupFlow.kt`
- [x] 同包无需 import；`ConvFilter` 是 `object`，`refreshMembers()` 为 public，静态调用正确
- [x] 未跑 gradle（按要求留给主会话统一编译）

### 最终落点（grep 实证）

```
BatchPicker.kt:173               ConvFilter.refreshMembers()
GroupDetailPage.kt:266           ConvFilter.refreshMembers()
GroupManagerPage.kt:68           ConvFilter.refreshMembers()     // 长按操作 onChanged 收口（他人补）
GroupManagerPage.kt:80           ConvFilter.refreshMembers()     // 新建分组成功（他人补）
GroupManagerPage.kt:97           ConvFilter.refreshMembers()     // 清空全部分组成员（我）
GroupPicker.kt:161               if (target != null || added > 0 || removed > 0) ConvFilter.refreshMembers()
MemberPage.kt:72                 ConvFilter.refreshMembers()
MemberPage.kt:88                 ConvFilter.refreshMembers()
```

> `GroupManagerPage` 另有 2 处（:68 长按操作 `onChanged` 收口、:80 新建分组成功）是在我改完之后由他人补的，
> 都是正确的补充 —— 新建分组、重命名/置顶/删除同样会改分组行该显示的内容。
> :68 用 `onChanged` 收口一处覆盖 4 个操作，思路比逐个分支补更好。这三处互不冲突。

`git diff --stat`：5 files changed, 46 insertions(+), 5 deletions(-)
（`BatchPicker` / `GroupDetailPage` / `GroupPicker` / `MemberPage` / `GroupManagerPage`）

[DONE]
