# R19 F4B 修复记录（lane `f4b-fix`）

> 输入判据：`docs/_r18_f4b_review.md`（裁定 FAIL，1 项 P0 + 3 项 P1）
> 本轮目标：修 F1(P0) / F2(P1) / ③ 纯函数接线(专审 §5.4) / ④ 判据修正(专审 §2①§5) / ⑤ 补测试
> 硬边界：**只改** `MomentsNotifyLogic.kt` / `MomentsNotify.kt` / `MomentsNotifyLogicTest.kt`；
> `MomentsExport.kt` 由主会话统一接线（接线片段见文末）。
> 状态：进行中，分节 append。

## §0 改动一览

| # | 对应发现项 | 文件 | 一句话 |
|---|---|---|---|
| C1 | F1 (P0) | `MomentsNotify.kt` | branch 1 从「调用线程直接 show」改 `UiKit.runOnMain { ... }` |
| C2 | F2 (P1) | `MomentsNotify.kt` | `show()` 返回 `Boolean`（真的显示出去了），`notify()` 增加 `onOutcome` 送达回调 |
| C3 | 专审 §5.4 | `MomentsNotifyLogic.kt` | 新增 `onFailure(latch, reason, escalated, sender)` 纯函数 + `Gate`（真接线宿主） |
| C4 | 专审 §2① | `MomentsNotifyLogic.kt` | `isNotifiable` 黑名单 → `kindOf()` `when` **表达式**（缺分支编译报错） |
| C5 | F5 / 专审 §1 | `MomentsNotifyLogic.kt` | `WRITE` 与 `MKDIRS_ITEM` 归并为同一类 `ESCALATE`：per-item 本身不弹，同因计数超阈值才升级 |
| C6 | 专审 §5 | `MomentsNotifyLogicTest.kt` | 多轮用例从「接线仿真」改为驱动 `Gate`（真代码）；补 7 组新用例 |

---

## §1 C1+C2：`MomentsNotify.kt`（F1 P0 + F2 P1）

### 改前（`MomentsNotify.kt:54-97`，原文件）

```kotlin
fun notify(msg: MomentsNotifyLogic.Message): Boolean {
    val act = HostContext.activity()
    if (act != null) {
        show(act, msg)          // ⛔ 直接在调用线程（无 Looper 的导出线程）show
        return true             // ⛔ 不管有没有真的弹出去都 return true
    }
    ...
    mainHandler.postDelayed({
        val a = HostContext.activity()
        if (a == null) { LogX.w("... giving up") }   // ⛔ 放弃后没有任何回路告知调用方
        else { show(a, msg) }
    }, RELAUNCH_DELAY_MS)
    return true
}

private fun show(act: Activity, msg: Message) {      // ⛔ 返回 Unit，异常就地吞掉
    runCatching { UiKit.confirm(act, msg.title, msg.body) {} }
        .onFailure { LogX.w("... show failed") }
}
```

### 改后（`MomentsNotify.kt:69-146`）

```kotlin
fun notify(
    msg: MomentsNotifyLogic.Message,
    onOutcome: (delivered: Boolean) -> Unit,
): Boolean {
    val act = HostContext.activity()
    if (act != null) {
        UiKit.runOnMain { onOutcome(show(act, msg)) }   // ← C1：主路径必须过主线程
        return true
    }
    val ctx = HostContext.app() ?: run {
        LogX.w(...); onOutcome(false); return false      // ← C2：没受理也明确告诉调用方
    }
    val launched = runCatching { ... }.getOrElse { false }
    if (!launched) { onOutcome(false); return false }
    mainHandler.postDelayed({
        val a = HostContext.activity()
        if (a == null) { LogX.w("... giving up"); onOutcome(false) }  // ← C2：异步放弃 = 未送达
        else { onOutcome(show(a, msg)) }
    }, RELAUNCH_DELAY_MS)
    return true
}

private fun show(act: Activity, msg: Message): Boolean {
    if (act.isFinishing || act.isDestroyed) { LogX.w(...); return false }   // C2：提前剔除必 BadToken 的场景
    return runCatching { UiKit.confirm(act, msg.title, msg.body) {}; true }
        .getOrElse { LogX.w(...); false }
}
```

### 逐条理由

| 位置 | 理由 |
|---|---|
| `MomentsNotify.kt:76` branch 1 | **F1(P0)**：调用方 `MomentsExport.runAsync():243` 起的是 `isDaemon=true`、**从未 `Looper.prepare()`** 的线程；`UiKit.confirm`（`UiKit.kt:213-220`）是裸 `AlertDialog.Builder().show()`，无主线程跳转 ⇒ 必抛 `Can't create handler inside thread ... that has not called Looper.prepare()`（或随后 `CalledFromWrongThreadException`）。改法照抄同仓库已实证写法 `BackupPanelPage.kt:167`。**这是唯一能在「用户正在前台」场景下把窗弹出来的路径。** |
| `MomentsNotify.kt:134-146` `show()` 返回 `Boolean` | **F2**：`BadTokenException`（`isFinishing` 竞态 / token 失效）在旧实现里等价于「已受理」⇒ 占 latch ⇒ 用户没看到窗却再也不弹。只有 `show()` 自己知道有没有真的落地。 |
| **不套外层 `runCatching`** | `UiKit.runOnMain`（`UiKit.kt:156-167`）内部自带 `runCatching` 且不回传结果；外层再套一层会让异常被吞两次、结果永远取不到。⇒ 捕异常只保留在 `show()` 一处。 |
| `act.isFinishing / isDestroyed` 前置检查 | 把最常见的 BadToken 竞态在**弹之前**判掉，让「没送出去」变成一个安静的 `false` 而不是一条事后日志。minSdk=26，`isDestroyed` 可用。 |
| 两处 `onOutcome(false)` | **F2 异步分支**：旧代码 `postDelayed` 后立即 `return true`，1.5s 后拿不到 Activity 只打一行日志 ⇒ 文档自述的「异步放弃属环境问题，下轮重试」在代码里**从未成立**。 |
| 用户在弹窗上点「取消」仍算送达 | 只弹一次的设计意图不变：`show()` 成功返回 true ⇒ 占 latch。 |

### ⚠️ 兼容性（并行期间必须）

旧调用点 `MomentsExport.kt:286` 是**单参**调用。为了让主会话并行改 `MomentsExport.kt` 期间工程仍能编译、本 lane 能跑单测，
`MomentsNotify.kt:117-126` 保留了一个 `@Deprecated` 的单参重载（内部转为带回调的重载 + 记一行 w 日志）。
**主会话完成接线后请删掉这个重载** —— 它不具备「未送达不占 latch」的能力。

---

## §2 C3：`MomentsNotifyLogic.onFailure` + `Gate`（专审 §5.4）

### 问题

专审 §5：13 条多轮用例是对 `MomentsExport.notify()` 的**仿真**（手抄三行接线）。可复现的反例：

1. 把 `:288` 的 `markFired` 挪到 `if (d.doNotify)` 之外 ⇒ F2 复活，测试全绿；
2. 把 `:296` 的 `return true` 改回「返回是否弹了」⇒ **上一个 P0 复活**，测试全绿。

而 F1(P0) 恰恰住在没被覆盖的那层接线里。

### 改后（`MomentsNotifyLogic.kt:278-303`）

```kotlin
fun onFailure(
    latch: Latch,
    reason: Reason,
    escalated: Boolean = false,
    sender: (Message, onOutcome: (delivered: Boolean) -> Unit) -> Boolean,
): Boolean {
    val d = decide(latch, reason, escalated)
    if (!d.countsAsFailure) return false
    if (d.doNotify) {
        sender(messageFor(reason)) { delivered -> if (delivered) latch.markFired(reason) }
    }
    return true
}
```

- 返回值仍是「**本轮是否发生故障**」（= `countsAsFailure`），保持上一个 P0 的修复，调用方 `clean` 语义不变。
- 占用 latch 的时机从「**受理**」改为「**送达**」：`delivered=true` 才 `markFired`。
- `sender` 的函数类型是 `(Message, (Boolean)->Unit) -> Boolean`，**不含任何 android.\* 类型** ⇒ 本文件的「零 Android 依赖」约束没破，JVM 单测照样跑得到。

### 状态宿主 `Gate`（`MomentsNotifyLogic.kt:305-339`）

`onFailure` 还需要「本轮 / 连续轮」的升级计数才能决定 `escalated`，所以再包一层 `Gate`（持有 `Latch` + `Escalation`）：

```kotlin
class Gate {
    fun beginRound()                                     // 轮开头：清本轮计数
    fun onFailure(reason, sender): Boolean               // = MomentsNotifyLogic.onFailure(latch, reason, escalation.note(reason), sender)
    fun endRound(clean: Boolean)                         // 结算 streak；clean 才 latch.resetAll()
    fun firedReasons(): Set<Reason>                      // 快照，单测断言用
}
```

⇒ 「多轮只弹一次」「未送达/异步放弃下轮重试」从此由**会被单测跑到的这份代码**保证，而不再由接线处手写三行保证。

---

## §3 C4+C5：判据修正（专审 §2① / F5）

### §3.1 `isNotifiable` 黑名单 → `when` 表达式（`MomentsNotifyLogic.kt:80-126`）

**改前**：`reason != DB_NOT_READY && reason != MKDIRS_ITEM` —— 新增第 10 个 `Reason` 时默认「弹」，没人逼你决定它该不该弹。

**改后**：

```kotlin
enum class Kind { SILENT, ALWAYS, ESCALATE }

fun kindOf(reason: Reason): Kind = when (reason) {   // 缺分支 ⇒ 编译报错
    DB_NOT_READY -> SILENT
    NOT_DIR      -> ALWAYS
    MKDIRS       -> ALWAYS
    READONLY     -> ALWAYS
    SPACE        -> ALWAYS
    SPACE_ABORT  -> ALWAYS
    THROW        -> ALWAYS
    MKDIRS_ITEM  -> ESCALATE
    WRITE        -> ESCALATE
}

fun isNotifiable(reason: Reason, escalated: Boolean = false): Boolean = when (kindOf(reason)) {
    SILENT   -> false
    ALWAYS   -> true
    ESCALATE -> escalated
}
```

单测侧配了显式期望表（`everyReasonHasAnExplicitKind` / `staticNotifiabilityMatchesExpectedTable`）：新增枚举不同步更新期望表 ⇒ 断言直接挂。

### §3.2 `WRITE`(弹) 与 `MKDIRS_ITEM`(不弹) 的区分：撤销，改为「同因计数超阈值才升级」

自述判据「持久性故障弹、瞬时/单条偶发不弹」在两者身上无法区分 —— 它们在「循环内单条 / 不阻断整轮 / 下轮自动重试 / 可由同一根因引起」四维度完全同构，而给 WRITE 的辩护（整盘只读 / 目录被删）在代码路径上不可达（前者被 `READONLY` 先截，后者会被 `mkdirs()` 重建）。

⇒ 二者同归 `Kind.ESCALATE`，由 **`Escalation`（`MomentsNotifyLogic.kt:177-224`）** 按「这一轮它持不持久」决定：

| 阈值 | 值 | 语义 |
|---|---|---|
| `THIS_ROUND_THRESHOLD` | 3 | 同一轮里同因失败 ≥3 次 ⇒ 不是偶发 |
| `CONSECUTIVE_ROUNDS` | 2 | 已连续第 2 轮报同一原因 ⇒ 一上来就升级 |

行为保持项（重要）：

- **未越过阈值的单条失败仍 `countsAsFailure=false`** ⇒ 不影响 `clean`（有回归用例 `perItemBelowThresholdKeepsRoundClean`）；
- `Escalation.endRound()` **不看 clean**（单条失败不算失败 ⇒ 整轮可能仍 clean；若跟着 clean 清零就永远攒不到连续轮数）；
- 调用方漏了 `beginRound()` 只会让「本轮计数」退化成「累计计数」（**更保守，不会误弹**）；
- ` decide(latch, reason, escalated = false)` 保持向后兼容签名，既有用例继续编得过。

附带按专审 §2 的建议给 `decide` 补了一条注释：**`countsAsFailure` 目前等价于 `isNotifiable`，将来若分家必须同步改 `MomentsExport` 里 `clean` 的语义**（`MomentsNotifyLogic.kt:249-252`）。

---

## §4 C6：测试（`MomentsNotifyLogicTest.kt`）

**13→28 条**。原有 13 条**一条没删**（名字全保留），语义变了就同步改；多轮三条从「接线仿真」改为驱动 `Gate`（真代码）。

### 保留并修订的 13 条

| 用例 | 变更 |
|---|---|
| `persistentFailuresAreNotifiable` | 移除 `WRITE`（改判据后它归 `ESCALATE`） |
| `transientFailuresAreSilent` | 增加 `WRITE` 未升级时的断言 |
| `everyReasonHasMessage` / `messagesAreDistinctPerReason` | 未动 |
| `latchFiresOncePerReason` / `latchIsPerReason` / `resetAfterSuccessReallows` | 未动 |
| `decidePersistentFirstTimeIsFailureAndNotify` | 用例主体换 `NOT_DIR`（仍是「首次必弹」语义） |
| `decideAlreadyFiredStillCountsAsFailure` | 仍是上一轮 P0 正中心，改传 `escalated=true` |
| `decideTransientIsNeitherFailureNorNotify` | 增加 `WRITE` |
| `fiveConsecutiveFailuresNotifyExactlyOnce` | **改驱动 `Gate`**（不再是仿真） |
| `successRoundResetsSoNextFailureNotifiesAgain` | **走真因果链**：干净轮 → `endRound(clean=true)` → 内部 `resetAll()`；不再直接调 `resetAll()` 绕过；删掉旧的无意义 `run {}` |
| `alternatingReasonsEachNotifyOnce` | 换成两个 `ALWAYS` 原因（`READONLY`/`SPACE_ABORT`）——`WRITE` 现在受升级门槛支配，不适合做「原因互不挤占」的载体 |

### 新增 15 条

| 用例 | 锁什么 | 对应 |
|---|---|---|
| `everyReasonHasAnExplicitKind` | 全枚举 × `Kind` 期望表，**新增枚举不更新表就红** | 专审 §1 黑名单风险 |
| `staticNotifiabilityMatchesExpectedTable` | 未升级 / 已升级两种口径的全枚举快照 | 同上 |
| `escalatedPerItemFailureIsNotifiable` | 升级后 `WRITE`/`MKDIRS_ITEM` 变「可弹」 | F5 |
| `decidePerItemBelowThresholdIsSilent` | 同格阈内 `(false,false)` vs 升级后 `(true,true)` | F5 |
| `perItemEscalatesOnSameRoundThreshold` | 本轮 ≥3 才升级，前 2 次静默 | F5 |
| `perItemEscalatesOnConsecutiveRounds` | 连续第 2 轮首笔即升级 | F5 |
| `cleanRoundClearsEscalationStreak` | 中间干净轮清零连续计数 | F5 |
| `perItemBelowThresholdKeepsRoundClean` | 阈内单条失败**不算**本轮失败（保持原有 clean 语义） | F5 |
| `perItemFailureNotifiesOnceAfterEscalation` | Gate 多轮驱动：静默→升级弹 1 次→之后不再弹 | F5 + §5.4 |
| `sameRoundThresholdEscalatesPerItemFailure` | 一轮 3 笔，第 3 笔才弹 | F5 + §5.4 |
| `decideDoesNotMutateLatch` | `decide` 纯度（专审 §5.2：有人顺手 markFired 时只有这条会红） | 专审 §5.2 |
| `notAcceptedDoesNotOccupyLatchAndRetriesNextRound` | sender 返回 false ⇒ 每轮重试、latch 始终空 | F2 |
| `notDeliveredDoesNotOccupyLatchAndRetriesNextRound` | `delivered=false` ⇒ **不占 latch**、下轮重试（F2 正中心） | F2 |
| `asyncGiveUpRetriesNextRound` | 异步放弃（回调晚于本轮结束才到 false）⇒ 下轮真会重试 | F2 |
| `onFailureReturnsCountsAsFailureNotDelivered` | 返回值是「有没有故障」不是「有没有弹出去」（上一轮 P0 关键一行） | P0 + §5.4 |

### 变异验证（证明这些用例不是恒真）

对**真接线**（`MomentsNotifyLogic.onFailure`）做两处注入，跑一次 `testDebugUnitTest`：

| 注入 | 结果 |
|---|---|
| A：`{ delivered -> if (delivered) markFired }` → `{ _ -> markFired }`（未送达也占 latch = F2 原状） | **`asyncGiveUpRetriesNextRound` + `notDeliveredDoesNotOccupyLatchAndRetriesNextRound` 红**（2 failed / 28） |
| B：`return true` → `return d.doNotify`（返回「有没有弹」= 上一轮 P0 原状） | **`fiveConsecutiveFailuresNotifyExactlyOnce` / `alternatingReasonsEachNotifyOnce` / `perItemFailureNotifiesOnceAfterEscalation` / `onFailureReturnsCountsAsFailureNotDelivered` 红**（4 failed / 28） |

⇒ 对比专审 §5 的结论：**改坏真接线，13 条一条都不红**；现在同样两处改动分别能红 2 条 / 4 条。两处注入均已还原。

### 单测结果

最后一次全量运行结果：

```
FINAL files= 25   tests= 518   failures= 0   errors= 0   skipped= 0
MomentsNotifyLogicTest: tests= 28  failures= 0  errors= 0
```

> 说明：本 lane 三次运行期间仓库总数从 513 → 518，是**并行 lane** 在往 `MomentsLogicTest`（54→59）加用例，与本改动无关；
> 三次运行的共同点是 `failures=0 errors=0`，`MomentsNotifyLogicTest` 恒为 28/28 绿。

命令：`C:/Users/lx/.workbuddy/binaries/python/versions/3.13.12/python.exe tools/gradle_run.py :app:testDebugUnitTest --offline`
（`BUILD SUCCESSFUL`，`EXIT=0`；数字取自 `app/build/test-results/testDebugUnitTest/*.xml`）

---

## §5 遗留项（不在本 lane 的改动范围，记录给主会话）

| 项 | 说明 |
|---|---|
| F3（P1）「手动探针 `sub=export` 不弹」 | 探针 `probe():1199-1202` 调的是 `runAsync()`，与 ticker 同路径 ⇒ 会弹窗、会占 latch。要改就得给 `Gate`/`MomentsExport.notify()` 加「本轮来自探针」的抑制参数，属 `MomentsExport.kt`，本 lane 未动。 |
| F4（P1）`DB_NOT_READY` / `MKDIRS_ITEM` 从未 emit | `MomentsExport.kt:307` 早退没接 `notify`、`390` 只剩 `continue`。若日后接入，枚举槽位与文案都已就绪（Kind 已给值）。注意：接 `MKDIRS_ITEM` 后它走 `ESCALATE`，不再「永不弹」。 |
| F6（P2）`countsAsFailure ≡ isNotifiable` | 已在 `MomentsNotifyLogic.kt:249-252` 补注释锁定，行为未改。 |
| ⚠️ 行为变化提醒 | **`WRITE` 不再「失败就弹」**：现在要同轮 3 次或连续 2 轮才升级。若 A4 真机取证脚本靠 `WRITE` 立刻弹窗来验证，需改成连续两轮失败或一轮内 3 次失败。 |

---

## [DONE]

### 结论：**PASS**（判定依据：review 列出的必修项 F1/F2 + 专审 §5.4 接线 + §2①/§1 判据均已修，28 条单测全绿，且经变异验证有区分力）

改动文件 **3 个**（未触碰 `MomentsExport.kt` / adb / 真机）：

| 文件 | 改动 |
|---|---|
| `wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsNotify.kt` | `notify()` 加送达回调、主路径过 `UiKit.runOnMain`、`show()` 返回 Boolean、兼容重载（待删） |
| `wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsNotifyLogic.kt` | `Kind`/`kindOf` when 表达式、`isNotifiable(escalated)`、`Escalation`、`decide(escalated)`、`onFailure()`、`Gate` |
| `wxgroup/app/src/test/java/com/lx/wxgroup/feature/MomentsNotifyLogicTest.kt` | 13 → 28 条 |

单测（最后一次全量）：`files= 25  tests= 518  failures= 0  errors= 0`；
其中 `MomentsNotifyLogicTest: tests= 28  failures= 0  errors= 0`（总数 513→518 是并行 lane 在往 `MomentsLogicTest` 加用例，与本改动无关）

> ⚠️ F1(P0) 的修正**无法由 JVM 单测证明**（`MomentsNotify` 依赖 Android，纯 JVM 环境够不着）。
> 本 lane 未动 adb / 未装包。建议在真机上确认一次「用户正在前台 + 导出失败 ⇒ 窗真的弹出来」。

### ⛔ 主会话需同步改 `MomentsExport.kt`：4 处

```kotlin
// ①  MomentsExport.kt:177（声明）
// 改前
private val notifyLatch = MomentsNotifyLogic.Latch()
// 改后
private val notifyGate = MomentsNotifyLogic.Gate()

// ②  MomentsExport.kt:281-297（notify 整体退化成一个委派）
// 改前：手写 decide + MomentsNotify.notify + markFired + return true
// 改后：
private fun notify(reason: MomentsNotifyLogic.Reason): Boolean =
    notifyGate.onFailure(reason) { msg, cb -> MomentsNotify.notify(msg, cb) }

// ③  MomentsExport.kt:306 `runBlocking()` 的**第一条语句**（早退那几条 return 之前）
    private fun runBlocking(): String {
        notifyGate.beginRound()          // ← 新增：清本轮同类失败计数
        val db = GroupDb.current() ?: return "$TAG export skip: db not ready"
        ...

// ④  MomentsExport.kt:455（轮结尾）
// 改前
        if (clean) notifyLatch.resetAll()
// 改后
        notifyGate.endRound(clean)
```

**旧的 7 处调用点 `notify(Reason.X)`（:250 THROW / :321 NOT_DIR / :329 MKDIRS / :337 READONLY / :345 SPACE / :406 SPACE_ABORT / :442 WRITE）一行都不用改** —— 它们都走上面那个 private `notify(reason)` 委派。

配套可选：
- 接线完成后删掉 `MomentsNotify.kt` 里的 `@Deprecated` 单参重载（`MomentsNotify.kt:117-126`，本 lane 为保并行编译而留）；
- `MomentsExport.kt:277-279` 的注释（「DB_NOT_READY/MKDIRS_ITEM 仍返回 false ⇒ 早退不会 reset latch」）按 review §4 改写成「不 reset 的原因是早退绕过了 `:455`，与返回值无关」。
