# R29-F1：首次打开通用选择器「群聊」Tab 缺失的修复设计

## 根因（已核实）

`PickerTabs.roomTab()` 读 `db.allChatrooms()`，返回空直接 `return null` ⇒ 群聊 Tab 整个不出现。
而 `friend_cache` 里的群聊行只能由 `ChatroomSource.syncBlocking()` 扫 `rcontact` 补入，
唯一生产触发点是 `ContactSync.doSync` 收尾（`:200`）。用户若从没打开过通讯录，
`doSync` 不跑 ⇒ `cached=0 runs=0 lastSync=never` ⇒ 任何名单功能的第一次打开都看不到群聊 Tab。

## 方案总览

1. `PickerTabs.all()` 入口补一次 `ChatroomSource.runIfNeeded()`（异步、立即返回）。
2. `ChatroomSource` 扫完群聊后，经**弱引用监听器**在主线程回调 `PickerPage`。
3. `PickerPage` 收到回调后**原地重建** Tab 按钮行与列表，保留 selected / 当前 Tab 名 / 搜索词。

---

## 问题 1：补跑在哪个时机触发

**选定：`PickerTabs.all()` 入口。**

候选与取舍：

- **`PickerTabs.all()` 入口（选定）**：这是四 Tab 的**唯一构建漏斗**，`MainPage.onEnterSpec`
  与 `MassSendPage.onEnterSpec` 都走它。在这里调一次 `runIfNeeded()` 用一行代码覆盖全部
  通用选择器入口，且**天然发生在「页面即将打开」这一刻** —— 正好让下面的刷新回调有活着的
  `PickerPage` 可更新。`runIfNeeded()` 内部起 daemon 线程，立即返回，不拖 UI。
- **各调用方打开选择器前**：语义相同，但两处（Main/MassSend）各写一遍，未来新增入口易漏；
  而且「打开前」与「all() 构建 Tab」本来就是同一时刻，拆两处没有额外收益。弃。
- **模块启动时**：能提前把群聊暖好，但有两个硬伤：① 启动时 `GroupDb.current()` 未必 ready，
  `shouldRun` 会因 db 为 null 直接 false（`ChatroomSource.shouldRun` 首行 `if (db == null) return false`），
  补跑被静默吞掉；② 用户可能在启动后 2.4s 内（全表扫耗时）就打开选择器，仍然看不到群聊，
  只是把「必现」降级成「概率」，没根治。弃。

结论：`PickerTabs.all()` 里 `ChatroomSource.runIfNeeded()` 一行，配合下面的完成回调闭环。

[DONE] 问题1-触发时机

---

## 问题 2：UI 线程绝不阻塞

全表扫 `rcontact` 真机实测 **2449ms / 841 个群**，同步跑就是卡死。本方案里没有任何一步把它放到主线程：

- `runIfNeeded()` 只在调用线程做 `AtomicBoolean.compareAndSet` + `Thread.start()`，**不做任何 DB 操作**；
  真正的 `shouldRun`（一次 count 查询）+ `syncBlocking`（全表扫）都在名为 `wxg-chatroom` 的 **daemon 线程**里执行。
  这条节流语义（`loading` CAS + 线程内 double-check `shouldRun`）**原样保留，未改动**。
- `syncBlocking` 的唯一生产调用点仍是 `ContactSync.doSync` 收尾（后台线程）+ 上述 daemon 线程；探针走 `io{}`。
- 完成后的回调通过 `Handler(Looper.getMainLooper())` post 回主线程，但回调里只做**轻量**动作：
  重建 Tab 行 + `adapter.reset` + 重放搜索词。这些是 `PickerTabs.all()` 初次打开时本来就在主线程做的事
  （读的是 `friend_cache` 缓存表，千行级、毫秒级），**不包含** 2.4s 的全表扫。

结论：全表扫永远不在 UI 线程；UI 线程只做它本就在做的缓存表读取与视图重建。

[DONE] 问题2-UI不阻塞

---

## 问题 3：扫完之后已打开页面怎么刷新

采用**原地刷新**（不 `Panel.refresh()` 整页重建），理由与细节：

### 为什么不用 `Panel.refresh()`

`Panel.refresh()` 会整页 `build()` 重来：搜索框被重建 ⇒ 丢掉焦点与文本；Tab 行、Adapter 全部重建。
虽然 `selected` / `currentTab` / `keyword` 是 `PickerPage` 实例字段、实例没变不会丢，但搜索框文本
得额外写回（现有 `build()` 并不回填 `keyword`），还要处理「关掉的旧页把别的页面刷掉」的身份校验
（`Panel` 不暴露「当前页是不是我」）。原地刷新则**天然不碰搜索框**，且对「已关掉的旧页」天然无害
（对 detached 视图调方法不崩溃）。取舍：原地刷新多存两个视图引用，换来无生命周期坑。

### 原地刷新具体做什么

`PickerPage.onRoomsReady()`（主线程）：
1. 用注入的 `refreshTabs: () -> List<Tab>` 重新调 `PickerTabs.all()`（重读缓存表）；
2. 新旧 Tab **名称**完全一致 ⇒ 直接 return（避免每次通知都无谓重建/打日志）；
3. 否则 `currentTab = resolveCurrentIndex(旧当前 Tab 名, 新 Tab 名)`；
4. 重建 Tab 按钮行（移除旧行、清空 `tabBtns`、按需重新 `tabRow()` 插回 `counter` 之后）；
5. `adapter.reset(新当前 Tab 的 sections)` + 若 `keyword` 非空重放 `adapter.filter(keyword)` + `refreshCounter()`。

### 三个「不能丢」如何保证

- **不能丢已勾选项**：`selected` 是实例字段 `LinkedHashSet`，原地刷新**根本不碰它**（切 Tab 也从不碰，
  这是 R24 的硬约束，本次依旧遵守）。
- **不能跳回第一个 Tab**：`currentTab` 按**名字**保留（`resolveCurrentIndex`），群聊只是插到第 1 位、
  不改变其它 Tab 名，所以用户停在哪个 Tab 就还在哪个 Tab。只有「名字已失效」的防御场景才回第 0 个。
- **不能清空搜索框**：原地刷新不重建 `EditText`，`keyword` 字段也不动，刷新后按当前 `keyword` 重放过滤，
  列表状态与搜索框文字保持一致。

[DONE] 问题3-扫完刷新

---

## 问题 4：失败 / 并发重入 / 用户已经关掉页面

- **失败**：`syncBlocking` 里 db 未 ready、wx db 未 ready、rawQuery 缺失、查询失败、0 行群聊都会返回 -1
  或提前 return，**不触发回调**（只在 `written > 0` 时才 `notifyRooms`）。于是「扫失败」不会让页面无谓刷新，
  页面保持原状（无群聊 Tab），下次打开再触发重试（`shouldRun` 因 `cached==0` 仍为 true）。
- **并发重入**：`loading` CAS 保证同一时刻只有一个扫描线程；线程内 double-check `shouldRun` 防止
  启动窗口期被别的路径补过群聊后重复全表扫。**这两层原样保留**（硬约束 3）。回调通知本身幂等：
  多个监听器各自 `runCatching` 包裹，单个监听器抛异常不影响其它。
- **用户已经关掉页面**：监听器用 `WeakReference` 存回调，`PickerPage` 自己用字段强引用住回调 ⇒
  页面活着回调就活着、页面被回收后弱引用自动失效，**不泄漏**。极端窗口（页面已 pop 但尚未被 GC）
  里回调可能仍触发：此时 `onRoomsReady` 操作的是一棵已 detach 的视图树，`adapter.reset` / `refreshCounter`
  都是无害调用，**不会崩、不会刷到别的页**（这正是选「原地刷新」而非 `Panel.refresh()` 的关键收益）。
- **空 Tab 防御**：`onRoomsReady` 若拿到空的新 Tab 列表直接 return，不把页面刷成空白。

[DONE] 问题4-失败并发关页

---

## 纯函数抽取 + 单测

新建 `feature/PickerTabLogic.kt`（**零 Android 依赖**，与 `SnsTagLogic` 同约定），三个纯函数：

1. `needsFullSync(cachedCount, lastSyncMs, nowMs, intervalMs)` —— 「是否需要补跑」。
   与 `ChatroomSource.shouldRun` 逐字同判据（`cached==0 → true`；否则 `now-last > interval`）。
   `shouldRun` 改为调用它，行为不变。
2. `planTabRefresh(oldNames, newNames, currentName)` —— 「刷新时如何合并新旧 Tab」。
   新旧名完全一致返回 `null`（跳过刷新、不打日志）；否则返回新当前下标。
3. `resolveCurrentIndex(currentName, newNames)` —— 「当前 Tab 名失效时选哪个」。
   按名字找，找不到（含 null / 空列表）回第一个（0）。

单测 `feature/PickerTabLogicTest.kt`（JVM，JUnit4）共 **10 个用例**：
`cachedZeroAlwaysRuns` / `neverFullSyncedRuns` / `freshCacheSkips` / `staleCacheRuns` /
`exactlyAtIntervalSkips` / `planReturnsIndexWhenChanged` / `planReturnsNullWhenUnchanged` /
`currentTabKeptByName` / `missingCurrentFallsBackToFirst` / `emptyNewListFallsBackToZero`。

[DONE] 纯函数与单测

---

## 变异验证（不跑 gradle，静态推演；实际 `gradle test` 由主会话统一编译执行）

> 硬约束「不许跑 gradle」，故此处用**逐变异对照测试断言**的方式推演「改坏 → 哪个测试变红」。

| # | 变异 | 哪个测试变红 | 推演 |
|---|------|------------|------|
| M1 | `needsFullSync` 把 `cachedCount == 0` 改成 `cachedCount != 0` | `cachedZeroAlwaysRuns`（断言 `assertTrue(...0...)` 变 false） | 首次打开不再补跑 → F1 复现 |
| M2 | `needsFullSync` 把 `>` 改成 `>=` | `exactlyAtIntervalSkips`（临界点断言 `assertFalse` 变 true） | 临界抖动，恰好到期时反复全表扫 |
| M3 | `needsFullSync` 把 `nowMs - lastSyncMs > intervalMs` 写成 `<` | `staleCacheRuns` / `neverFullSyncedRuns` 变红 | 过期的不补、没过期的反而补，逻辑反了 |
| M4 | `resolveCurrentIndex` 改成**恒返回 0**（等价「回到第一个」） | `currentTabKeptByName`（`群聊→1`、`微信标签→3` 断言失败） | 违反「不能跳回第一个 Tab」 |
| M5 | `resolveCurrentIndex` 找不到时返回 `-1`（等价清空/越界） | `missingCurrentFallsBackToFirst`、`emptyNewListFallsBackToZero` | 越界崩 / 列表错位 |
| M6 | `planTabRefresh` 去掉 `oldNames == newNames → null`，恒返回下标 | `planReturnsNullWhenUnchanged` 变红 | 每次通知都无谓重建 + 刷日志（违反「不要每个都打」） |
| M7 | `planTabRefresh` 恒返回 null（`return null`） | `planReturnsIndexWhenChanged` 变红 | 群聊补完页面永不刷新 → F1 不闭环 |

结论：三条判据的关键变异都会被至少一个既有用例抓红；`selected` 的「不丢失」靠结构性不变量
（`onRoomsReady` 不碰 `selected` 字段，`build()` 不重建实例），不靠纯函数，故不在此表内。

[DONE] 变异验证

---

## 改动文件清单

| 文件 | 改动 |
|------|------|
| `feature/PickerTabLogic.kt`（新增） | 三条纯判据（零 Android 依赖） |
| `core/ChatroomSource.kt` | `shouldRun` 改调 `needsFullSync`；`runIfNeeded` 加「命中补跑/跳过补跑」两条日志；新增弱引用监听器 `addRoomListener` + 主线程 `notifyRooms`，`syncBlocking` 成功且 `written>0` 时通知 |
| `feature/PickerTabs.kt` | `all()` 入口 `ChatroomSource.runIfNeeded()` 一行触发 |
| `feature/PickerPage.kt` | 构造参数 `tabs` 由 `val` 变 `var`（可原地改写）；新增 `refreshTabs` 提供器参数（默认 null）；`build()` 存 `rootView`/`tabRowView`；新增 `bindRoomListener`/`onRoomsReady`/`applyTabList` 原地刷新 |
| `feature/MainPage.kt` | `PickerPage(...)` 增传 `refreshTabs = { PickerTabs.all(...) }` |
| `feature/MassSendPage.kt` | 同上 |
| `feature/PickerTabLogicTest.kt`（新增） | 10 个 JUnit 用例 |

**未改动**：`data/GroupDb.kt`（他人 lane 独占）、`core/ContactSync.kt`（现有调用点保持不动）。
`runIfNeeded` 的 `loading` CAS + 线程内 double-check `shouldRun` 节流语义**原样保留**。

---

## 遗留风险

- **窗口期仍可能看不到群聊**：`runIfNeeded()` 是异步的，全表扫约 2.4s。用户打开选择器后若**立刻**看，
  群聊 Tab 是「扫完后原地补出」，期间有 2~3s 空窗。这是「绝不阻塞 UI」的必然代价，与现状（永远没有）相比
  是严格改善；不选「打开前同步阻塞扫」。
- **弱引用 + 原地刷新的兜底窗口**：页面已 pop 但尚未被 GC 时回调可能仍触发，但只操作 detached 视图树，
  无害（见问题 4）。
- **ForwardPicker（转发选择器）** 不在本次范围：它自建 sections、不经过 `PickerTabs.all`，但读的是同一个
  `friend_cache`，群聊数据一旦被 `ChatroomSource` 补入它也能读到；其「首次打开触发补跑」由 forward lane
  自行决定，此处仅提示。

[DONE]
