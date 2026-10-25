# R18 F4b 实现报告：朋友圈自动导出失败改「弹窗告知」

- 日期：2026-10-24
- 定案依据：需求 6.4「失败只弹窗」+ 开工前准备清单 决策 #11（已定：只弹窗，不 Toast、不在设置页留痕）
- 落地范围：仅 `feature/MomentsExport.kt` + 新建 `feature/MomentsNotify.kt` / `feature/MomentsNotifyLogic.kt` + 单测。未动 `HookEntry.kt` / `EntryReceiver.kt` / 任何 `SnsComment*` 文件。

## ① 失败路径逐条「弹 / 不弹」决策表

| 位置 | 原因枚举 | 文案正文 | 弹？ | 理由 |
|---|---|---|---|---|
| `db not ready` | `DB_NOT_READY` | 数据库尚未就绪，稍后会自动重试。 | 否 | 微信启动早期瞬时状态，几秒后恢复，弹窗纯骚扰 |
| `rootDir exists && !isDirectory` | `NOT_DIR` | 导出路径不是文件夹，请检查路径设置。 | 是 | 持久性，用户不处理不会自己好 |
| `!exists && !mkdirs()` | `MKDIRS` | 无法创建导出目录，请检查路径是否可写。 | 是 | 持久性 |
| `!canWrite()` | `READONLY` | 导出目录不可写，请检查存储权限。 | 是 | 持久性 |
| `usableSpace < MIN_FREE_BYTES`（路径检查） | `SPACE` | 存储空间不足，已停止导出，请清理后重试。 | 是 | 持久性 |
| 单条目录 `mkdirs` 失败 | `MKDIRS_ITEM` | 创建导出子目录失败，已跳过该条。 | 否 | 循环内偶发（坏昵称等），不阻断整轮，er 计数已可见 |
| 中途空间不足（`stop=true`） | `SPACE_ABORT` | 导出中途空间不足，已中止本轮导出。 | 是 | 持久性，与 SPACE 分开展示（发生在导出中途，非起步） |
| 写文件失败 | `WRITE` | 写入导出文件失败，已跳过该条。 | 是 | 持久性（整盘只读/目录被删） |
| 整轮抛异常 | `THROW` | 导出过程发生异常，已跳过本轮。 | 是 | 未知故障 |

另：**触发源层**——手动探针 `op=moments sub=export` 走 `runBlocking()` 只返回摘要，不弹窗（诊断入口，弹窗无意义且会骚扰真机对账）。

## ② 去重状态机（`MomentsNotifyLogic.Latch`）

- **粒度 = 按失败原因**，不是全局一个开关。9 个原因各持一个 `fired` 标记（`HashSet<Reason>`），原因之间互不影响。
  - 旧 `pathWarned`/`spaceWarned` 是**轮内日志去重**（每轮开头重置），语义是「同一原因只打一行日志」；弹窗需要**跨轮按住**，二者已删除，替换为独立 latch。
- **只弹一次**：`shouldFire(reason)` 首次 true → `markFired(reason)` 后恒 false。
- **成功才复位**：一轮导出**没有任何「值得弹窗」的故障**（`clean`）→ `resetAll()` 清空全部标记；下一轮再故障重新弹。
  - ~~判断依据：`runBlocking` 里每一处 `notify(reason)` 返回 true 就把 `clean` 置 false；`db not ready` / `MKDIRS_ITEM` / 已去重的重复失败都不算 dirty。~~
  - ⛔ **上面这句是 P0 缺陷的原文，已作废（R18 复核修正，2026-10-24）**：
    「已去重的重复失败不算 dirty」把「本轮没弹」误当成「本轮成功」，
    于是 latch 在第 2 轮被 `resetAll()` 清空 ⇒ **每隔一轮弹一次**。详见文末 §P0 修正。
- **线程安全**：`Latch` 三个方法 `@Synchronized`。导出本身单航班（`running` AtomicBoolean），但 onTick 换线程触发，加锁兜底。

## ③ 后台上下文三条分支（`MomentsNotify`）

1. **有 Activity**（`HostContext.activity()` 非空，用户正在用微信）→ 直接 `UiKit.confirm` 弹。
2. **无 Activity** → 复刻 `EntryReceiver.withForeground` 的拉起路径：`getLaunchIntentForPackage + FLAG_ACTIVITY_NEW_TASK` + `postDelayed(1500ms)`，**再次校验 `HostContext.activity()`**：
   - 仍非空 → 弹；
   - 仍为 null → 记 `w` 日志并放弃，**不无限重试**。
3. **拉起失败 / app context 为 null** → 记日志放弃。

风险收窄：拉前台前先看 activity，绝大多数「正在用微信」的情形直接弹，不打扰；只有微信在后台时才拉起，且一次校验失败即放弃。该风险（后台周期任务可能在用户聊天时弹出）已被决策 #11 明确接受。

## ④ 单测覆盖点（`MomentsNotifyLogicTest`，7 用例全过）

1. `persistentFailuresAreNotifiable`：7 个持久性原因都 `isNotifiable=true`。
2. `transientFailuresAreSilent`：`DB_NOT_READY` / `MKDIRS_ITEM` 为 false。
3. `everyReasonHasMessage`：9 原因均有非空正文 + 统一标题。
4. `messagesAreDistinctPerReason`：9 条正文互不相同（分类别糊成一坨）。
5. `latchFiresOncePerReason`：同原因只弹一次。
6. `latchIsPerReason`：不同原因互不影响。
7. `resetAfterSuccessReallows`：resetAll 后重新可弹。

## ⑤ 构建输出

- `:app:compileDebugKotlin --offline` → **EXIT=0**，`BUILD SUCCESSFUL in 17s`
- `:app:testDebugUnitTest --offline` → **EXIT=0**，`BUILD SUCCESSFUL in 18s`
- 新增用例：`MomentsNotifyLogicTest` tests=7 failures=0 errors=0

## 关键设计判断（摘要）

- **哪些失败不弹**：`DB_NOT_READY`（瞬时）、`MKDIRS_ITEM`（循环内偶发）、手动 `sub=export`（诊断触发）。
- **去重粒度**：按 9 种失败原因各一个 latch，成功导出（整轮 clean）才全复位。


---

## §P0 修正 · latch 复位判据用错（复核发现，2026-10-24）

### 缺陷

原接线：

```kotlin
var clean = true
if (notify(Reason.WRITE)) clean = false      // notify 返回「有没有弹窗」
...
if (clean) notifyLatch.resetAll()
```

而 `notify()` 在 latch 已触发时返回 `false`（去重命中）⇒ 第 2 轮 `clean=true`
⇒ `resetAll()` 清空 ⇒ 第 3 轮又弹。**变成「每隔一轮弹一次」。**

多轮模拟（逐行照抄原接线）：

| 场景 | 修前 | 修后 |
|---|---|---|
| `SPACE_ABORT` 连续 5 轮 | 弹 **3** 次 | 弹 **1** 次 |
| `WRITE` 连续 5 轮 | 弹 **3** 次 | 弹 **1** 次 |
| `WRITE`/`SPACE_ABORT` 交替 5 轮 | 弹 **3** 次 | 各 1 次（共 2） |

典型走查：
```
第1轮 clean=False fired=['WRITE']     ← 弹
第2轮 clean=True  fired=∅             ← 没弹，但 latch 被清了
第3轮 clean=False fired=['WRITE']     ← 又弹
```

### 根因（一句话）

**「本轮有没有弹窗」≠「本轮有没有失败」。** 前者是通知策略，后者是状态判定。
用前者当后者，去重命中就会被误读成成功。

### 修法

1. `MomentsNotifyLogic` 新增纯函数 `decide(latch, reason): Decision`，
   把两件事拆成两个字段：
   - `countsAsFailure` = `isNotifiable(reason)`（**与去重无关**）
   - `doNotify` = `latch.shouldFire(reason)`
2. `MomentsExport.notify()` 改为只调 `decide()` + 接线，返回值语义改为
   「本轮是否发生了该类故障」（不再是「有没有弹」）。
3. `MomentsNotify.notify()` 改为返回 `Boolean` = **是否受理**：
   有 Activity 直接弹 → true；无 Activity 拉起 + 延迟校验 → true（已受理）；
   拿不到 app context / 拉起抛异常 → false。
   **只有受理成功才 `markFired`** —— 否则「弹窗没送出去」会被误记为「已经通知过」。
4. `runBlocking` 里 `if (notify(...)) clean = false` 这行**不用改**，改完语义它自然正确。

### 红/绿验证（关键）

新增 6 条测试，其中 3 条在缺陷版本上是**红的**：

```
MomentsNotifyLogicTest > fiveConsecutiveFailuresNotifyExactlyOnce FAILED
MomentsNotifyLogicTest > alternatingReasonsEachNotifyOnce FAILED
MomentsNotifyLogicTest > decideAlreadyFiredStillCountsAsFailure FAILED
```

恢复正确实现后全绿。**这条红→绿证明测试真的能抓住这个缺陷**，不是摆设。

### 判据沉淀

> **只测单个方法的行为，测不出「多个方法接起来跑多轮」的错误。**
> 原有 7 条测试把 `Latch` 的 `shouldFire` / `markFired` / `resetAll` 各自测得很好，
> 但没有一条跑「5 轮循环」。缺陷恰恰只在**跨轮**暴露。
>
> **推论**：凡是「有状态的去重 / 退避 / 熔断」逻辑，测试必须至少有一条
> **多轮驱动**用例，断言「N 轮里总动作次数 == 期望值」。
> 单方法测试全绿 ≠ 状态机正确。
