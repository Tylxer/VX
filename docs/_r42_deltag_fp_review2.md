# R42 灰标 B1 修复 · 装包前交叉复核（第二视角）

- **档位** = Pro
- **依据** = 装包前交叉复核（跨绑定时序 + 防重入 + 计数自洽）
- **复核员** = tagreview2（独立第二视角，未参与实现）
- **对象** = `tagfix2` 对「冷启首屏灰标漏标（B1）」的修复
- **时间** = 2026-11-07

---

## [DONE-1] 复核范围 + 基线

### 1.1 本轮改动文件（时间戳确认）

| 文件 | 修改时间 | 结论 |
|---|---|---|
| `SnsDeletedTag.kt` | 2026-11-07 03:03:09 | 本轮改动 |
| `SnsDeletedTagLogic.kt` | 2026-11-07 03:07:12 | 本轮改动（最晚，与自报 03:07:12 一致） |
| `SnsDeletedTagLogicTest.kt` | 2026-11-07 03:03:39 | 本轮改动 |

### 1.2 自报「未碰」文件（bit3 三文件 + ids.xml 等）

| 文件 | 修改时间 | 结论 |
|---|---|---|
| `SnsBitLogic.kt` | 2026-11-07 01:17:45 | 早于本轮（bit3 阶段），未碰 ✓ |
| `SnsBlockProbe.kt` | 2026-11-07 01:08:07 | 早于本轮，未碰 ✓ |
| `SnsGuard.kt` | 2026-11-07 01:10:25 | 早于本轮，未碰 ✓ |
| `res/values/ids.xml` | 2026-11-06 23:57:58 | 早于本轮，未碰 ✓ |
| `GroupDb.kt` | 2026-11-06 22:34:42 | 早于本轮，未碰 ✓ |
| `Keys.kt` | 2026-11-06 23:07:22 | 早于本轮，未碰 ✓ |
| `EntryReceiver.kt` | 2026-11-07 00:19:29 | 早于本轮，未碰 ✓ |

**结论**：本轮 B1 修复只动了 3 个文件（1 主逻辑 + 1 纯判据 + 1 测试），bit3 三文件与 `ids.xml` 均未被触碰（时间戳均早于 03:00 本轮窗口），与实现方自报一致。

### 1.3 上一轮修复基线（已通过第一轮复核，本复核不推翻）

- `anchorOffset(passing, learned)`：`learned != null && learned in passing` 沿用；否则 `passing.singleOrNull()`；都无则 null。
- `markDecision`：四条件（styleOn + resolved + snsId≠0 + inDeleted）全真才 SHOW，其余 HIDE。
- `offsets` 以 `adapter.javaClass.name` 为 key，仅 `passing.size == 1` 校准；`d == 0` 不缓存（`offsets.remove(key)`）为刻意设计。

### 1.4 本轮待复核的修法清单（对应实现方自报 1~8）

1. `bound` 类型 `WeakHashMap<View,Long>` → `WeakHashMap<View,BoundEntry>`；新增 `resweeping`。
2. onBind `info==null` 分支不再 `bound.remove(view)`，改写 pending entry。
3. 成功路径写 resolved entry。
4. `:534` 取 `learned`；`:550-552` 校准块未改；`:556-558` 新增 `shouldResweep` 触发。
5. `resweep()` 重写：pending 重扫 + 防重入 + `finally` 复位。
6. `snapshot()`：`synchronized(bound)` 内拷贝，跳过已 GC key。
7. `shouldResweep(before, after)`：`before == null && after != null && after != 0`。
8. 测试 51→54 例（新增 shouldResweep 触发/不触发 + 冷启首屏补标场景）。

---

## [DONE-2] B1 是否真修好（冷启首屏逐步推演）

### 2.0 前置数据模型（确认无误）

封面头造成 `onBindViewHolder(holder, pos)` 的 `pos` 与 `getData()` 下标存在**全局固定错位 O = -1**（`getData()[i]` 显示在 `pos = i+1`）。冷启首屏：

| getData() 下标 | 内容 | 显示 pos |
|---|---|---|
| — | 封面头 | pos0 |
| 0 | verity 新动态（未删） | pos1 |
| 1 | verity 真·被删动态 | pos2 |
| 2 | 另一作者（非同名） | pos3 |

`resolve()` 的窗口 `order = [0, 1, -1, 2, -2]`，`infoAt(list, pos + d)`。

### 2.1 pos1 绑定（新动态 verity）→ 落 pending ✓

- `learned = offsets[key] = null`（冷启）。
- `d=0`：`getData()[1]`（verity 被删）→ nameOk true；`d=-1`：`getData()[0]`（verity 新）→ nameOk true；其余 false。
- `passing = [0, -1]`。
- `anchorOffset([0,-1], null)`：learned=null，`singleOrNull()`=null（2 个）→ **null**。
- 走 `info==null` 分支（`SnsDeletedTag.kt:409-421`）：`bound[view1] = BoundEntry(adapter,1,0L,false)` + `applyMark(view1,0L,false)`=HIDE。✓ 落 pending。

### 2.2 pos2 绑定（真被删 verity）→ 落 pending ✓

- `d=-1`：`getData()[1]`（verity 被删·自己）→ true；`d=-2`：`getData()[0]`（verity 新）→ true；`d=0`（`getData()[2]` 另一作者）→ false。
- `passing = [-1, -2]`。
- `anchorOffset([-1,-2], null)` → **null**。
- `bound[view2] = BoundEntry(adapter,2,0L,false)` + HIDE。✓ 落 pending。

### 2.3 pos3 绑定（另一作者）→ 校准 + 触发 resweep ✓

- `d=-1`：`getData()[2]`（另一作者·自己）→ true；其余 false。
- `passing = [-1]`，size==1。
- `anchorOffset([-1], null)` = `singleOrNull()` = **-1**。
- 校准（`SnsDeletedTag.kt:550-552`）：`passing.size==1` 且 `d=-1 != 0` → `offsets[key] = -1`。
- `shouldResweep(learned=null, offsets[key]=-1)`（`:556-558`）= `shouldResweep(null, -1)` = **true** → `resweep()`。

> 时序确认：`resweep()` 在 pos3 的 `resolve()` **内部**同步执行（`:556-558` 在 `return byOffset[d]` 之前）。此时 `bound` = {view0 封面 pending, view1 pending, view2 pending}，**pos3 的 entry 尚未写入**（onBind 的 `bound[view3]=...` 在 `resolve` 返回后、`:423-425`）。因此 resweep 的 snapshot 恰好覆盖 view1/view2，不包含 view3。

### 2.4 resweep 重扫 pos2（真被删）→ SHOW（补标成功）✓

`resweep()` 内对 view2（pending pos2）调 `resolve(adapter, 2, view2)`，此时 `learned=-1`：
- `passing = [-1, -2]`（同 2.2）。
- `anchorOffset([-1,-2], -1)`：`learned=-1 in passing` 成立 → 返回 **-1**。
- `d=-1` → `byOffset[-1]` = `getData()[2-1]` = `getData()[1]` = **pos2 自己（verity 被删动态）**。
- `info.snsId = DEL` → `bound[view2] = resolved(DEL)`（`SnsDeletedTag.kt:851`）→ `applyMark(view2, DEL)`（`:852`）。
- `DEL in deleted()` → `markDecision` 四条件全真 → **SHOW** → `ensureMark` 插灰标。✓ **补标成功，B1 核心问题解决。**

### 2.5 resweep 重扫 pos1（新动态）→ HIDE（不误标）✓【本项最关键判定】

`resweep()` 内对 view1（pending pos1）调 `resolve(adapter, 1, view1)`，`learned=-1`：
- `passing = [0, -1]`（同 2.1）。
- `anchorOffset([0,-1], -1)`：`learned=-1 in passing` 成立 → 返回 **-1**。
- `d=-1` → `byOffset[-1]` = `getData()[1-1]` = `getData()[0]` = **pos1 自己（verity 新动态）**。
- `info.snsId = NEW`（新动态）→ `applyMark(view1, NEW)`。
- `NEW in deleted()` → **false** → `markDecision` → **HIDE** → `removeMark`。✓ **不误标。**

**明确结论**：pos1 在 `learned=-1` 下 `anchorOffset` 返回 **-1**，锚到 `getData()[pos1-1] = getData()[0]` = **pos1 自己那条新动态**（不是相邻被删动态）。该数据不在 `sns_deleted` 台账 → 最终 **HIDE，不误标**。

> 容易误读的点：`anchorOffset` 返回的 `-1` 是「相对本格 pos 的全局错位常数」，不是绝对下标。pos1 的 `-1` → `getData()[0]`（自己），pos2 的 `-1` → `getData()[1]`（自己）。两条同名 verity 的 passing 分别是 `[0,-1]` 与 `[-1,-2]`，`-1` 恰好在两者中，且作为固定错位常数对每格都正确地指向「自己的那条」——这正是上一轮 `anchorOffset` 沿用 learned 的设计意图，本轮 resweep 复用它，方向一致，没有引入新的串位。

### 2.6 封面 pos0 在 resweep 中的处理（附带确认）

view0（封面 pending pos0）：`resolve(adapter, 0, view0)` 时 `d=0`（`getData()[0]`=verity）对封面 texts 无作者名 → `passing=[]` → `anchorOffset([], -1)=null` → 返回 null → `applyMark(view0,0L,false)`=HIDE。且其 `resolve` 内部 `shouldResweep(-1,-1)=false` 不触发递归。✓

### 2.7 小结

| 格 | 首绑 | resweep 重扫 | 最终 |
|---|---|---|---|
| pos0 封面 | pending(HIDE) | 仍 null → HIDE | 无标 ✓ |
| pos1 新 verity | pending(HIDE) | d=-1 → NEW → HIDE | **不误标 ✓** |
| pos2 真被删 verity | pending(HIDE) | d=-1 → DEL → SHOW | **补标 ✓** |
| pos3 另一作者 | resolved(自身) | （不在 snapshot） | 正常 ✓ |

**B1 真修好。**

---

## [DONE-3] 防重入是否真有效

### 3.1 递归环路防护（`resolve` → `resweep` → `resolve`）

- 触发链路：`resolve()` 校准成功后 `shouldResweep` true → `resweep()` → 内部对 pending entry 再 `resolve()`。
- 二次 `resolve()` 时 `learned = offsets[key]` 已是**非 null**（刚校准的 -1），故其 `shouldResweep(learned≠null, ...)` 恒 **false**（`before == null` 不成立）→ 不会再触发 `resweep()`。
- 即便某次 `resolve` 意外满足触发条件，`resweep()` 入口 `if (resweeping) return`（`SnsDeletedTag.kt:830`）仍会拦截——`resweeping=true` 在进入 `resweep` 时已置位，二次进入被短路。
- **结论**：递归环路被「`shouldResweep` 的 `before==null` 前置 + `resweeping` 布尔」双保险封死。✓

### 3.2 `resweeping` 是否覆盖全部入口

三个 `resweep()` 调用点：
1. `resolve()` 尾部（`:556-558`）——UI 线程，同线程递归场景，布尔有效。
2. `setStyle()`（`:886`）——探针命令路径。
3. `purge()`（`:991`）——探针命令路径。

- 若 `setStyle`/`purge` 的 `resweep()` 恰在 `resweeping=true` 时被调用（跨线程极端罕见），入口 `if (resweeping) return` 会跳过本次重扫，后果仅是「本次改样式/清台账的即时重绘被推迟到下次绑定/下次 resweep」，**不产生错误状态**（`refresh()`/`purgeSnsDeleted()` 已在调用 `resweep()` 之前完成，配置/台账已正确落地）。
- 注意：`resweeping` 是普通 `Boolean`（非 `@Volatile`、非 `AtomicBoolean`）。跨线程可见性理论上非严格保证，但 `resweep()` 的 View 操作全部包 `runCatching`（`:841/:845/:848/:852`），且 `resweeping` 的主要职责是**同线程防递归**（单线程下布尔完全可靠），跨线程可见性弱化不构成崩溃风险。

### 3.3 `finally` 是否保证复位

`try { ... } finally { resweeping = false }`（`:832-855`）。`finally` 无条件执行，即使 try 体内抛异常（实际上 try 内关键操作已 `runCatching` 包裹，唯一未包裹的 `v.isAttachedToWindow`（`:835`）是读 `mAttachInfo` 字段，不抛 `CalledFromWrongThreadException`）。✓ 不会卡死在 `resweeping=true`。

### 3.4 `snapshot()` 是否避免 ConcurrentModificationException

- `snapshot()`（`:859-868`）在 `synchronized(bound)` 内把 `bound.entries` **拷贝**到新 `ArrayList`，跳过已 GC 的 key（`e.key ?: continue`）。
- 遍历对象是快照 `snap`，非原 map。遍历期间对原 map 的 `bound.remove(v)` / `bound[v]=...` 不触碰 `snap` 迭代器 → **无 CME**。✓
- `bound[v] = ...`（`:851`）走 `synchronizedMap` 的单次 `put`（内部自带锁），单操作线程安全；与 `synchronized(bound){ remove }`（`:836`）的复合操作虽非同一临界区，但二者都在 UI 线程串行，无真正并发。✓

### 3.5 结论

防重入有效：递归被封死、`finally` 保证复位、`snapshot` 避免 CME、遍历期间改原 map 安全。

---

## [DONE-4] pending 复用安全性

### 4.1 snapshot 与 onBind 是否交错

`resweep()` 是**同步方法**，从 `onBind` 的 after 钩子（`resolve` 内部）在 **UI 线程**串行调用；`snapshot()` 在 `resweep` 开头一次性完成拷贝，遍历期间 UI 线程被占用，RecyclerView 的 `onBindViewHolder` 不会中途插入。因此 **snapshot 与 onBind 不交错**（单线程 + 同步执行）。✓

### 4.2 过期 pos 是否可能锚错

pending view 被复用并重新绑定后，`onBind` 会 `bound[v] = 新 entry`（`:418` 或 `:424`）覆盖旧 entry。由于 `resweep` 同步执行、snapshot 先于覆盖，遍历到的 entry 一定是「当下」的绑定状态，**不会用过期 pos 锚错**。

唯一理论风险是「探针线程 `setStyle`/`purge` 的 `resweep` 与 UI 线程 `onBind` 并发」，此时可能出现「snapshot 拿到旧 entry，随后 onBind 覆盖，resweep 用旧 pos 重扫」。但：
- 该路径 `resolve(旧adapter, 旧pos, v)` 的结果只会落到**同一个 view** 的 `bound[v]`，随后该 view 的下一次 `onBind` 又会覆盖为正确值；
- `resolve` 的 `anchorOffset` 在已校准时沿用 learned，方向一致，最坏是「本次重扫 HIDE 或 SHOW 一帧旧结论」，不会产生**持久**错标（下次绑定必然纠正）。
- 这是上一轮就存在的线程模型，非本轮引入。**非阻塞**，仅记录。

### 4.3 `!isAttachedToWindow` 清理是否足够

- 已回收（detach）且未复用的 view：`!isAttachedToWindow` true → `bound.remove(v)`（`:835-837`），避免对已摘除的 view 做无谓 resolve/applyMark。✓
- 已复用（重新 onBind）的 view：bound 已被新 entry 覆盖，`isAttachedToWindow` true，走新 entry 的 resolve。✓
- 正常冷启首屏：RecyclerView 已 attach，itemView 随 RecyclerView attach，`isAttachedToWindow` 在 after 钩子时为 true（View 的 attach 随父链递归）。✓ 清理逻辑足够。

**结论**：pending 复用安全，无阻塞项。

---

## [DONE-5] 计数自洽

### 5.1 `match - hits` 恒成立性

- `match++` 只在 `applyMark` 的 SHOW 分支（`:681`）；`hits++` 只在 `ensureMark` 的 `addView` 成功（`:720`）。
- 每次 SHOW 必调 `ensureMark`，`ensureMark` 先 `removeMark` 再 `addView`（幂等）。故 `hits <= match` 恒成立，`match - hits >= 0`。
- `resweep` 重放 `applyMark`（resolved 视图 `:841`、pending 补标 `:852`）走的是**同一条** `match++`→`ensureMark`→`hits++` 链路，两者**同步增长**，差值不变。✓ **`match - hits == 0` 在「addView 全部成功」前提下仍恒成立**（addView 失败时差值 >0 暴露异常，这是原设计意图，非回归）。

### 5.2 resolved/missed/missLogged 是否被重复计数

- `resolved++` 只在 `onBind` 成功路径（`:423`），`resweep` 内**不** `resolved++`。✓
- `missed++` 只在 `onBind` 的 `info==null` 分支（`:410`），`resweep` 内 pending 重扫失败走 `applyMark(v,0L,false)`（`:848`）**不** `missed++`。✓
- `missLogged` 只在 `onBind`（`:411-413`），`resweep` 不碰。✓
- `resolve()` 本身无 resolved/missed 计数副作用（只改 `offsets`/`lastMiss`/触发 resweep）。✓

**结论**：计数自洽，无重复计数。

### 5.3 stat 读数是否误导（非阻塞备注）

冷启首屏 pos1/pos2 首绑 `resolve` 失败计入 `missed`（各 +1），resweep 补标成功**不**回补 `resolved`。因此 `stat` 里 `missed` 反映「首绑歧义次数」、`resolved` 反映「首绑成功次数」，二者之和 ≠ 最终标出的条数。这是诊断语义的轻微偏移，**不影响 `match`/`hits`**（用户真正关心的「插了几条灰标」仍准确）。**非阻塞，仅记录**：若日后要精确，可在 resweep 补标成功时 `resolved++` 并 `missed--`，但当前不构成装包障碍。

---

## [DONE-6] 回归红线逐项核验

| # | 红线项 | 实际代码 | 结论 |
|---|---|---|---|
| 1 | `:551` `d==0 → offsets.remove(key)` 一字未动 | `SnsDeletedTag.kt:550-552`：`if (passing.size == 1) { if (d != 0) offsets[key] = d else offsets.remove(key) }` | ✓ 未动 |
| 2 | `MARK_KEY` 仍是 `R.id.wxgroup_sns_mark` | `SnsDeletedTag.kt:107`：`private val MARK_KEY: Int = R.id.wxgroup_sns_mark` | ✓ 非 `generateViewId()` |
| 3 | `tailIndex()` 跳过末尾 1px 分隔线 | `SnsDeletedTag.kt:736-743`：判 `last.javaClass.name == "android.view.View" && height <= dp(2)` → `childCount-1` | ✓ 未动 |
| 4 | `MARK_LEFT_DP=72` | `SnsDeletedTag.kt:96`：`private const val MARK_LEFT_DP = 72` | ✓ |
| 5 | `isTimelineLoss` 只盯 bit1 | `SnsDeletedTagLogic.kt:57-58`：`(old and BIT_TIMELINE)!=0 && (new and BIT_TIMELINE)==0`，`BIT_TIMELINE=2`（`SnsBitLogic.kt:32`） | ✓ 不盯 bit3 |
| 6 | `shouldRecord` 仍排除自己 | `SnsDeletedTagLogic.kt:79-80`：`isTimelineLoss && !isSelf` | ✓ |
| 7 | `onBlocked` 记录链路 + 后台 writer 未改 | `SnsDeletedTag.kt:235-253`（writer 单线程 executor + `gen.incrementAndGet` 在写库成功后）；`SnsGuard.kt:492`、`:545` 调用点参数 `(id, bits, want)` 不变 | ✓ |
| 8 | bit3 三文件 + ids.xml 未碰 | 时间戳：`SnsBitLogic.kt` 01:17:45 / `SnsBlockProbe.kt` 01:08:07 / `SnsGuard.kt` 01:10:25 / `ids.xml` 11-06 23:57:58，均早于本轮 03:00 窗口 | ✓ |

**结论**：全部 8 条红线通过，无一被本轮改动破坏。

---

## [DONE-7] 测试真实性（独立跑测）

### 7.1 独立复跑结果

- 命令：PowerShell 工具，`$env:PATH='G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot\bin;C:\Windows\System32;C:\Windows'`，`gradlew.bat testDebugUnitTest --rerun-tasks -q --console=plain`。
- 退出码 `GRADLE_EXIT=0`。
- 解析 `app/build/test-results/testDebugUnitTest/*.xml`：**41 类 / 815 例 / 0 失败 / 0 错误 / 0 跳过**。
- XML `timestamp=2026-11-06T19:13:31`（UTC，= 本地 2026-11-07 03:13:31），与本次运行时间吻合，**非旧缓存**（`--rerun-tasks` 强制重跑，文件 mtime 03:13）。✓

**结论**：与实现方自报「41 类 / 815 例 / 0 失败」完全一致，独立复核通过。

### 7.2 新增 3 例覆盖质量

`SnsDeletedTagLogicTest.xml` 中 `tests=54`，新增 3 例均在列，且通过：

| 新增用例 | 覆盖点 | 是否充分 |
|---|---|---|
| `should_resweep_triggers_on_first_nonzero_calibration` | `shouldResweep(null,-1)=true`、`shouldResweep(null,1)=true`（触发分支，含正/负错位） | ✓ |
| `should_resweep_does_not_trigger_on_zero_or_non_first_calibration` | `(null,0)=false`、`(null,null)=false`、`(-1,-1)=false`、`(-1,null)=false`（不触发三分支全覆盖） | ✓ |
| `scenario_cold_start_resweep_recovers_real_deleted_mark` | 冷启首屏时序：pos2 校准前 `anchorOffset([-1,-2],null)=null`(HIDE) → pos3 校准 learned=-1 → resweep 重扫 `anchorOffset([-1,-2],-1)=-1` → `markDecision` SHOW | ✓ |

`shouldResweep` 三分支（`before==null` / `after!=null` / `after!=0`）各有正反例锁定，覆盖充分。

### 7.3 覆盖局限（非阻塞，如实记录）

- `scenario_cold_start_resweep_recovers_real_deleted_mark` 是**纯函数模拟**：串起 `anchorOffset` + `markDecision` 两个纯函数，但**没有真正执行 `resweep()` 方法本体**（`resweep`/`snapshot`/`isAttachedToWindow`/`bound` 覆盖写是 `SnsDeletedTag` 私有逻辑，耦合 Android `View`，JVM 单测无法加载）。真正的遍历时序（DONE-2 的 2.4/2.5、DONE-3、DONE-4）靠**人工走查**保障，无自动化兜底。
- 这是 Android/View 耦合代码的常态局限，不构成装包阻塞；建议后续若有条件，把 `resweep` 的「pending 决策 + 覆盖写」抽成不依赖 View 的纯状态机再补单测。

### 7.4 变异测试评估

- 实现方自报「8 个语义变异全红 + 1 个等价变异（De Morgan 改写）全绿」，但**变异脚本/输出未落盘**（`wxgroup/` 下仅见针对 `SnsSqlRewrite.kt` 方案 B 的 `_mut_b.py`，无针对 `shouldResweep` 的变异脚本），故变异过程**无法独立复现**。
- 从代码逻辑独立判断：`shouldResweep = before==null && after!=null && after!=0` 的语义变异（各条件反转、`&&`→`||`、去掉某条件、返回常量）都应与 7.2 的正反例冲突而变红；De Morgan 等价改写 `!(before!=null || after==null || after==0)` 与原文逻辑恒等，应全绿。该结论**可被 7.2 的 6 个 shouldResweep 断言独立支撑**（即便脚本缺失，单测本身已锁住三分支真值）。
- **非阻塞备注**：建议实现方把本轮变异脚本落盘（如 `_mut_b1.py`），否则后续复核者无法复现「全红/全绿」声明。

---

## [DONE-8] 可装包裁决

### 总裁决：✅ 可以装包

**无阻塞项。**

### 核心结论速览

1. **B1 真修好**：冷启首屏 pos2 真被删动态在 pos3 校准后经 `resweep` 补标成功（SHOW）；**pos1 新动态不误标**——`learned=-1` 下 `anchorOffset([0,-1],-1)` 返回 -1，锚到 `getData()[pos1-1]=getData()[0]` = pos1 自己（新动态，不在台账）→ HIDE。
2. **防重入有效**：递归被「`shouldResweep` 的 `before==null` 前置 + `resweeping` 布尔」双保险封死；`finally` 保证复位；`snapshot` 避免 CME。
3. **pending 复用安全**：`resweep` 同步执行于 UI 线程，snapshot 与 onBind 不交错；`!isAttachedToWindow` 清理足够。
4. **计数自洽**：`match-hits` 恒 ≥0 且同步增长；`resolved/missed/missLogged` 不重复计数。
5. **回归红线**：8 条全部通过，`d==0→remove` 一字未动、MARK_KEY 正确、bit3 三文件 + ids.xml 未碰。
6. **独立跑测**：41 类 / 815 例 / 0 失败 / 0 错误 / 0 跳过，与自报一致，时间戳为本轮。

### 非阻塞建议（列全，不阻断装包）

| # | 建议 | 位置 | 性质 |
|---|---|---|---|
| 1 | `resweep()` 遍历本体（snapshot/isAttachedToWindow/bound 覆盖写）无 JVM 单测覆盖，仅靠人工走查；建议抽成纯状态机补测 | `SnsDeletedTag.kt:829-868` | 测试覆盖 |
| 2 | 本轮变异脚本未落盘，无法复现「8 红 + 1 绿」；建议落盘 | `wxgroup/_mut_b1.py`（建议新增） | 可复现性 |
| 3 | `resweep` 补标成功后 `resolved`/`missed` 不回补，stat 的「首绑 vs 最终」语义轻微偏移（`match/hits` 不受影响） | `SnsDeletedTag.kt:846-852` | 诊断语义 |
| 4 | `resweeping` 为非 `@Volatile` 布尔，跨线程（探针 `setStyle`/`purge` vs UI 线程）可见性非严格保证；当前 View 操作已 `runCatching` 兜底，无崩溃风险，若追求严格可改 `AtomicBoolean` | `SnsDeletedTag.kt:188` | 线程可见性 |

以上 4 项均为**非阻塞**，不影响「能不能装包」。建议装包后真机按「冷启 → 直接进朋友圈首屏」路径验证 pos2 灰标是否秒出、pos1 是否保持无标，作为最后一公里确认。

---

