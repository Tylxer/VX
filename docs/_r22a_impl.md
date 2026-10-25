# R22 Lane A 实现记录

> 角色：wxgroup 项目（微信 Xposed 模块）实现 lane A。
> 根因（已定案）：`ConvFilter.kt:829` 只在会话**条数**变化时重读快照，
> 收新消息导致**重排但条数不变**时 snaps 与 layout 永久冻结 ⇒ 点错人。
> 本 lane 负责「分层失效」判据 + `orderChanged` 纯函数 + observer 去抖。

## 改动清单

### 1. `ConvLayout.kt` 新增 `orderChanged` 纯函数

- 位置：`filtered()` 与 `passthrough()` 之间。
- 签名：`fun orderChanged(old: List<ConvEntry>, new: List<ConvEntry>): Boolean`
- 逻辑：`old.size != new.size` → true；逐项比对 `wxid`，任一不同 → true；否则 false。
- **只看 wxid，不看 timeMs**。KDoc 写明了原因：layout 的锚点只依赖「wxid 顺序 + 归属」，
  timeMs 变化不移动分组行位置，只有 wxid 重排才动锚点，才需要重算 layout。
- 时间复杂度 O(n)，不分配大对象（无字符串拼接、无 Map），2900 项量级可安全每 rebuild 调用。

### 2. `ConvFilter.kt` `rebuild()` 分层失效（核心）

改动点（`GroupedAdapter.rebuild()`，约 :853-932）：

1. **每次都 `readSnaps(n)` 拿新快照**（`fresh`）。旧代码 `if (snaps.size != n) snaps = readSnaps(n)`
   只在条数变化时重读 —— 这是冻结根因。新快照既用于判「顺序变没变」，也提供最新 timeMs。
2. 新增局部 `orderDirty = fresh.size != snaps.size || ConvLayout.orderChanged(snaps, fresh)`。
3. 三层分支：
   - **顺序/条数变**（orderDirty）→ `snaps = fresh`，走完整重建（layout + models + memberSets）。
   - **顺序没变、配置也没变、但 timeMs 变**（`!orderDirty && configStamp == lastStamp`
     且 `timeFingerprint(fresh) != lastTimeFp`）→ `snaps = fresh`，**layout 复用**，只 `rebuildModels`。
   - **全都没变** → 直接 `return`（timeMs 指纹相同）。
4. **stamp 扩充**：原有 6 项（`n`/`enabled`/`filterId`/`showUnread`/`cachedMembers.hashCode()`/
   `groups.hashCode()`）原样保留，改名为 `configStamp`（明确它是「配置指纹」）。
   顺序变化用 `orderChanged` 判，时间变化用新增的 `timeFingerprint` 判 —— 三者分离，
   满足「能区分仅 timeMs 变 与 完全没变」。

新增私有方法：

- `rebuildModels(db, groups, members, showUnread)`：抽出收纳态 models 重算，供完整重建与
  轻量路径复用（避免两处重复代码）。
- `timeFingerprint(snaps): Long`：累加 timeMs 的 Long 哈希，O(n) 不分配对象。
  用它区分「摘要变了」与「完全没变」。KDoc 注明了局限：unread/digest 不在 `readSnaps`
  产出里，只能拿 timeMs 当代理；「unread 变但 timeMs 没变」的边缘场景会滞后到下次 rebuild。

### 3. 收纳态成员集合缓存（`memberSets` 字段）

- 新增 `@Volatile private var memberSets: Map<Long, Set<String>>`。
- **完整重建时**才 `db.memberWxids(g.id)` 查库并写入 `memberSets`；**轻量路径**直接复用，
  避免「每帧都查库」（收纳态分组多时是 N 次 SQL 查询）。
- 为什么安全：成员增删改走 `ConvFilter.refreshMembers()`（§6），会递增 `membersVersion`
  强制完整重建、重读 memberSets。
- **历史根因（准确归因，见 §6）**：成员增删**只改 `group_member` 表**，全仓 6 处调用点
  无一处改分组表 ⇒ `groups.hashCode()` 恒不变。旧代码「成员改完列表不更新」的根因不是
  stamp 本身，而是两点叠加：
  1. **筛选态**：`refresh()` 从头到尾**不重读 `cachedMembers`** —— 它只在 `setGroup()` /
     `refreshCache()` 被赋值。所以 `cachedMembers.hashCode()` 虽在 stamp 里，却一直是旧值，
     自然撞不上「指纹变化」。
  2. **收纳态**：成员集本就不在 stamp 的任何一项里，且旧代码没有任何「成员变了」的信号
     （没有版本号），完整 rebuild 根本不会被触发。
  §6 的 `refreshMembers()` + `membersVersion` 同时补上了这两点。

### 4. observer 去抖（`onDataChanged`）

- `install()` 里 `inner.registerDataSetObserver` 的 `onChanged()` 原为
  `fa.rebuild(); fa.notifyDataSetChanged()`（同步直调）。
- 改为 `fa.onDataChanged()`：新增 `GroupedAdapter.onDataChanged()`，用 `mainHandler` +
  `REBUILD_DEBOUNCE_MS` 去抖（复用一个 `mainHandler`，未新造 Handler），
  加 `observerRebuildQueued` 标记（主线程串行）。
- **为什么不走 `refresh()`**：`refresh()` 里有 `rememberAnchor` + `restoreAnchor`，
  后者会 `setSelectionFromTop(0, anchor)` 把列表滚回顶部 —— 那是给「用户主动操作
  （切分组/标记已读）」用的；收消息时用户可能正滚在列表中段，滚回顶部会打断阅读。
  所以 observer 保留「只 rebuild + notify」语义，但套轻量去抖。KDoc 已写明理由。

### 5. 单测（`ConvLayoutTest.kt`）

新增 5 条 `orderChanged` 用例：

- `orderChanged_different_length_returns_true`：长度不同 → true（含双向）。
- `orderChanged_reordered_returns_true`：条数相同、wxid 顺序不同 → true（两种重排）。
- `orderChanged_same_order_different_time_returns_false`：仅 timeMs 变 → **false**（关键判据）。
- `orderChanged_identical_returns_false`：完全相同 → false。
- `orderChanged_empty_boundaries`：空列表边界（空 vs 空 → false；空 vs 非空 → true，双向）。

### 6. `refreshMembers()` + `membersVersion`（协作补丁，lane B 发现）

背景：lane B 在 6 个成员入口补了 `ConvFilter.refresh()`，但发现 `refresh()` 不重读
`cachedMembers`，且成员变化不改变 `n/enabled/filterId/showUnread/cachedMembers/groups`
这 6 项 —— 筛选态会撞 stamp 短路，收纳态撞 `memberSets`（不在 configStamp 里）短路。

改动（全部在 `ConvFilter.kt`）：

- 新增对象级 `@Volatile private var membersVersion = 0`：成员关系单调版本号。
- 新增 `fun refreshMembers()`：
  - `currentGroupId()` 取当前筛选 id（仅在 `cachedId < 0` 时懒读一次，属正常懒初始化）；
  - `id > 0` 且 db 就绪 → `cachedMembers = db.memberWxids(id)`（筛选态靠 configStamp 里
    `cachedMembers.hashCode()` 变化自然触发全量重建）；
  - `membersVersion++`（收纳态破短路）；
  - `refresh()`。
- `GroupedAdapter` 新增 `private var lastMembersVersion = -1`；`rebuild()` 增加
  `membersDirty = membersVersion != lastMembersVersion`，为 true 时跳过轻量分支走全量重建，
  并在全量重建末尾 `lastMembersVersion = membersVersion` 消费掉。每个 wrapper 各自维护
  `lastMembersVersion`，多 wrapper 各自正确消费。

**X/Y 决策结论：方案 X**（只重读 `cachedMembers`，不动 `cachedId`）。理由：`cachedId`
由 `setGroup()` 独占管理（写库 + 写缓存），是「当前筛选哪个分组」的唯一事实来源；
`refreshCache()` 全量重读会从 `FILTER_GROUP` pref 重读 `cachedId`，在 `setGroup` 写库的
竞态窗口内可能读到旧值并覆盖（cachedId 丢失更新，尤其 BackupFlow 可能后台线程调用）。

## 为什么每一处这么改

- **`orderChanged` 只看 wxid**：layout 的位置映射由「会话归属 + 会话在微信列表里的下标」
  决定，两者都不依赖 timeMs。把 timeMs 算进顺序判据会让「仅时间变化」被误判成「重排」，
  白白做 O(n) 全量重建。
- **`timeFingerprint` 与 `orderChanged` 分离**：顺序变化决定「layout 要不要重算」，
  时间变化决定「models 摘要要不要刷新」。两者必须分开判，才能做到「仅 timeMs 变时不重算 layout」。
- **`readSnaps` 每次都读**：要拿到最新 wxid 顺序（判重排）和最新 timeMs（刷摘要），
  没有更便宜的替代信号。成本靠两层缓解：observer 去抖（250ms 合并）+ layout 复用
  （顺序没变时不跑 `ConvLayout.build`）。
- **`memberSets` 只在完整重建时查库**：轻量路径高频触发，成员查询是 N 次 SQL。
  成员变化走 `refreshMembers()`（递增 `membersVersion`）强制完整重建，所以轻量路径
  复用的 memberSets 永远是「最近一次成员变更后」的最新值，复用安全。

## 交叉复核自检结果

- [x] 「仅 timeMs 变」时 layout 复用了吗？—— 是。`!orderDirty && configStamp == lastStamp`
  且 timeFp 变 → 只 `rebuildModels`，`layout` 不动。
- [x] `orderChanged` 对「条数相同、顺序不同」返回 true？—— 是，单测 `orderChanged_reordered_returns_true` 覆盖。
- [x] `stamp` 扩充后原有 6 个短路场景还有效吗？—— 是。6 项原样保留在 `configStamp`，
  任一变化 → `configStamp != lastStamp` → 完整重建，短路逻辑不变。
- [x] 有没有动 `readSnaps` 占位语义 / `realPosition` / `build` / `filtered`？
  —— 没动。`readSnaps` 的「空 entry 占位、绝不 continue 跳过」原样保留；`realPosition`、
  点击位置映射、`ConvLayout.build/filtered` 一行未改。

## 还剩什么风险

1. **readSnaps 每次全量读的性能**：2900 项 × (getItem + username/timeMs 反射) 每次去抖重建
   都要跑一遍。已靠 250ms 去抖 + layout 复用压住，但真机滚动 + 高频收消息时是否仍流畅
   需要上机验证（本次只编译验证语法，未上真机）。
2. **timeFingerprint 只覆盖 timeMs**：unread/digest 变化而 timeMs 不变时，分组行摘要会滞后
   到下次 rebuild。已注释，属可接受边缘场景。
3. **收纳态成员集刷新依赖 `refreshMembers()` 被正确调用**：§6 修好了机制，但前提是 lane B
   的 6 个成员入口 + BackupFlow 都改成调 `refreshMembers()`（而非 `refresh()`）。若漏改某处，
   那处的成员变更仍会被短路。
4. **`filterId == NONE` 引用了外层 `NONE` 常量**：GroupedAdapter 是 ConvFilter 的嵌套类，
   可访问对象成员（与既有的 `currentGroupId()`/`cachedMembers` 同款），编译已确认。

## 编译验证

命令（沿用 dev.sh 的 PATH/JAVA_HOME/GRADLE_USER_HOME 环境，用项目 gradlew，未直接调 gradle、
未跑 assembleDebug）：

```
sh gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon --console=plain
```

结果：
- 首轮 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin` → **BUILD SUCCESSFUL in 44s**。
- §6 补丁后 `:app:compileDebugKotlin` → **BUILD SUCCESSFUL in 20s**。
- 主源集 + 测试源集均无 `e:` 错误、无 warning。

[DONE]
