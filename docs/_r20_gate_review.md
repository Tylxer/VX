# R20 gate-review —— MomentsExport↔Gate 接线独立复核

> lane: `gate-review`（只读审代码，不改任何业务文件；唯一产出是本文件）
> 复核对象：主会话 R19 手写的 4 处接线 + 1 处删除（未经第二视角即上真机）
> 读数边界：仅读 `MomentsNotifyLogic.kt` / `MomentsNotify.kt` / `MomentsNotifyLogicTest.kt`；
>           `MomentsExport.kt` / `MomentsLogic.kt` / `SnsCdnImage.kt` 现状一律不读（他 lane 正在改）
> 真机已知前提：第 1 轮弹窗出现、第 2/3 轮不弹（`docs/_r19_f4b_verify.md`）⇒ 主路径宏观行为已验证，
>              本轮只找真机覆盖不到的分支（早退轮 / 连续故障轮数 / 异步放弃）

## 复核读数清单（本节说明取数方式，便于他人复核）

| 取数 | 方式 |
|---|---|
| `Gate` 的真实签名/实现 | 通读 `MomentsNotifyLogic.kt`（340 行，全读） |
| `MomentsNotify` 真实签名 | 通读 `MomentsNotify.kt`（138 行，全读） |
| 全仓 `MomentsNotify.notify` 调用点 | `Grep` 全仓（仅取匹配行） |
| `UiKit.runOnMain` / `confirm` 实现 | `Grep` `ui/UiKit.kt`（仅取匹配行 :143-221） |
| `MomentsExport` 的**接线骨架** | 仅靠 team-lead 贴的文本 + 一次 `Grep` 的**匹配行**（`notifyGate|beginRound|endRound|MomentsNotifyLogic|notifyLatch`），**未通读文件** |

⚠️ 上表最后一行是「匹配行级」读数，不是通读。凡涉及 `MomentsExport` 内部**控制流**的结论（谁 return、谁 break）都标了 `[待确认]`，不拿来判 FAIL。

### 从匹配行得到的接线骨架（事实）

```
MomentsExport.kt:182   private val notifyGate = MomentsNotifyLogic.Gate()
MomentsExport.kt:255                 notify(Reason.THROW)              ← 在 notify()(286) / runBlocking()(304) 之前 ⇒ 不在 runBlocking 体内
MomentsExport.kt:286   private fun notify(reason): Boolean =
MomentsExport.kt:292       notifyGate.onFailure(reason) { msg, cb -> MomentsNotify.notify(msg, cb) }
MomentsExport.kt:304       notifyGate.beginRound()                     ← runBlocking 首行
MomentsExport.kt:319       if (notify(Reason.NOT_DIR)) clean = false
MomentsExport.kt:327       if (notify(Reason.MKDIRS)) clean = false
MomentsExport.kt:335       if (notify(Reason.READONLY)) clean = false
MomentsExport.kt:343       if (notify(Reason.SPACE)) clean = false
MomentsExport.kt:404       if (notify(Reason.SPACE_ABORT)) clean = false
MomentsExport.kt:440       if (notify(Reason.WRITE)) clean = false
MomentsExport.kt:456       notifyGate.endRound(clean)                  ← 轮尾，唯一一处
```

`notifyGate` 全仓只出现这 4 处（182/292/304/456）；`notifyLatch` 已无任何**代码**引用。

---

## ① 4 处接线 vs `Gate` API 契约

`Gate` 实际声明（`MomentsNotifyLogic.kt:305-339`）：

```kotlin
class Gate {
    fun beginRound()                                     // = escalation.beginRound()
    fun onFailure(reason: Reason,
                  sender: (Message, onOutcome: (delivered: Boolean) -> Unit) -> Boolean): Boolean
    fun endRound(clean: Boolean)                          // escalation.endRound() ; if (clean) latch.resetAll()
}
```

逐条对：

| # | 接线 | 契约 | 判定 |
|---|---|---|---|
| 1 | `private val notifyGate = MomentsNotifyLogic.Gate()` | `Gate` 无构造参数 | ✔ |
| 2 | `notifyGate.onFailure(reason) { msg, cb -> MomentsNotify.notify(msg, cb) }` | 首参 `Reason` 位置参，次参尾 lambda | ✔ |
| 3 | `notifyGate.beginRound()` 首行 | 无参 | ✔ |
| 4 | `notifyGate.endRound(clean)` 轮尾 | 单参 `Boolean` | ✔ |

**sender 的 lambda 类型核对（重点）**

- 期望：`(Message, onOutcome: (delivered: Boolean) -> Unit) -> Boolean`
- 实参：`{ msg, cb -> MomentsNotify.notify(msg, cb) }`
  - Kotlin 由期望类型反推 ⇒ `msg: Message`、`cb: (Boolean) -> Unit`
  - `MomentsNotify.notify(msg: Message, onOutcome: (delivered: Boolean) -> Unit): Boolean`（`MomentsNotify.kt:69-72`）
  - 把 `(Boolean) -> Unit` 传给带形参名 `(delivered: Boolean) -> Unit`：**形参名不参与类型同一性** ⇒ 合法
  - lambda 体是单表达式且返回 `Boolean` ⇒ 与 sender 的返回类型一致
- ⇒ **类型自洽，可编译**。

**参数顺序**：`onFailure(reason, sender)` — 接线是「位置参 + 尾 lambda」，无顺序问题。
对比 `MomentsNotifyLogic.onFailure(latch, reason, escalated, sender)`（带 `escalated` 默认参）：`Gate.onFailure` **不暴露** `escalated`，由 `Gate` 内部 `escalation.note(reason)` 现算（`:321`）。接线没绕过它 ⇒ 正确，**没有**出现「调用方自己传 escalated」的漂移。

**返回值语义**：`notify()` 现在返回 `Gate.onFailure` 的结果 = `Decision.countsAsFailure`（`MomentsNotifyLogic.kt:289`），与 `if (notify(X)) clean = false` 的用法一致；`MomentsNotify.notify` 的「是否受理」返回值在 `onFailure` 内部被**有意丢弃**（占不占 latch 只看 `delivered` 回调，`MomentsNotifyLogic.kt:287`）。这是 F2 的正解，不是漏接。有测试钉住：`notAcceptedDoesNotOccupyLatchAndRetriesNextRound`（`MomentsNotifyLogicTest.kt:319`）。

> **① 结论：PASS。** 4 处接线与 `Gate` 契约完全自洽，无签名/类型/顺序错误。

---

## ② `beginRound()` 放首行（在 5 条早退 return 之前）是否正确？

### 主会话的理由成立

若 `beginRound()` 放在轮尾，早退轮永远执行不到 ⇒ `Escalation.thisRound` 跨轮累积 ⇒
下一轮 `note(WRITE)` 时 `n` 会带着上轮残留起步 ⇒ **ESCALATE 原因过早升级**（第 2 轮首笔就可能 `n>=3`）。
主会话「放结尾会让计数跨轮累积」的判断 **成立**。

放首行 ⇒ 每轮计数从 0 起。**位置正确。**

### 但主会话只说了一半：放首行的代价

早退轮 `beginRound()` 清了 `thisRound` 却不结算 ⇒ 本轮已记进 `thisRound` 的计数被**下一轮 `beginRound()` 丢弃**。
即：早退轮对 escalation 是「**清零不结算**」。这部分影响见 ③。

> **② 结论：位置正确（PASS）。** 主会话的取舍方向对；副作用不在「计数累积」，而在「计数作废」，见 ③。

---

## ③ `endRound(clean)` 被 5 条早退绕过是否有害（重点推演）

`Gate.endRound` 实际做**两件事**（`MomentsNotifyLogic.kt:329-332`）：

```kotlin
fun endRound(clean: Boolean) {
    escalation.endRound()          // (a) 结算连续故障轮数
    if (clean) latch.resetAll()    // (b) 干净轮复位去重 latch
}
```

早退轮 **两件都跳过**。

### (a) 连续轮数：冻结在 0，不是「一直涨」

`Escalation` 的状态迁移只有三个入口（`:194-217`）：

- `beginRound()` → `thisRound.clear()`
- `note(r)` → `thisRound[r]++`；返回 `n>=3 || streaks[r] >= 1`
- `endRound()` → `thisRound` 里出现过的 `streaks[r]++`；`streaks` 里没在 `thisRound` 出现的**移除**

⇒ **`streaks` 唯一能 +1 的地方是 `endRound()`**。早退轮不调它，所以早退原因的 streak **既不会涨、也不会清零，而是冻结**；
又因为下一轮 `beginRound()` 会把 `thisRound` 清掉，早退原因根本没机会被结算 ⇒ **streak 恒为 0**。

**以 READONLY 为例逐轮推演**（READONLY = `Kind.ALWAYS`）：

| 轮 | beginRound | note | escalated | isNotifiable | doNotify | clean | endRound | streaks[READONLY] |
|---|---|---|---|---|---|---|---|---|
| 1 | thisRound={} | n=1 | false | **true(ALWAYS)** | true → 弹 → markFired | false | **跳过** | 0 |
| 2 | 清掉 {R:1} | n=1 | false | true | **false**（latch 已占） | false | **跳过** | 0 |
| 3..n | 同上 | n=1 | false | true | false | false | **跳过** | 0 |

⇒ 真机「第 1 轮弹、2/3 轮不弹」成立，且**与 streak 无关**（由 latch 独立保证）。✔ 与 `docs/_r19_f4b_verify.md` 一致。

### (a) 当前为什么无害 —— 判据上的硬理由

`isNotifiable(reason, escalated)`（`:122-126`）：

```kotlin
Kind.SILENT   -> false        // ⛔ 不看 escalated
Kind.ALWAYS   -> true         // ⛔ 不看 escalated
Kind.ESCALATE -> escalated    // ✅ 只有这类看
```

5 条早退路径涉及的原因：
`DB_NOT_READY`(SILENT) / `NOT_DIR`(ALWAYS) / `MKDIRS`(ALWAYS) / `READONLY`(ALWAYS) / `SPACE`(ALWAYS)
⇒ **全部不读 `escalated`** ⇒ streak 恒 0 对它们**零行为影响**。

`WRITE` / `MKDIRS_ITEM`（唯一两个 ESCALATE）都在循环内（`:404` 是 SPACE_ABORT、`:440` 是 WRITE），在 5 条早退之后 ⇒ **能走到 `:456` endRound** ⇒ streak 正常结算。有测试钉住：`perItemFailureNotifiesOnceAfterEscalation`（`Test:411`）。

**⇒ (a) 当前 PASS。**

### (b) 干净早退轮不复位 latch：方向保守，可接受

- 4 条 ALWAYS 早退：`clean=false` ⇒ 就算调了 `endRound` 也不 reset ⇒ 跳过**无差异**。
- `db not ready` 早退：`notify(DB_NOT_READY)` 返回 `false` ⇒ `clean` 保持 `true` ⇒ 跳过后 latch **不复位**。
  语义上正确（「没跑成功 ≠ 故障修好了」），且只是**延后一轮**——下一次真正跑完的干净轮仍会 `endRound(true)` 复位。
  保守方向（少弹），不会造成隔轮骚扰。

### ⚠️ 但这里有个**文档/注释缺口**（这才是真问题）

`MomentsExport.kt:284` 的注释（匹配行可见）写的是：

> 「这样 `db not ready` 的早退不会 reset latch，语义与 `MomentsNotifyLogic.isNotifiable` 一致」

⇒ 主会话在决定「早退绕过 endRound」时，**只论证了 (b) 那一半，完全没意识到 (a) 也会被跳过**。
`Gate.endRound` 的 KDoc（`MomentsNotifyLogic.kt:324-328`）同样只写「早退 = 失败轮，不该 resetAll」，**没提 escalation 结算**。

**将来会咬人的具体场景**：任何人把一个 ESCALATE 原因（`WRITE`/`MKDIRS_ITEM`）挪到或新增在早退分支
（例如「导出根目录预检时发现子目录不可写，提前 return」）⇒ 该原因 streak 恒 0 ⇒ **永远升不了级 ⇒ 永久静默漏弹**。
这种失效**没有任何日志、不会崩溃、单测也不会红**，是典型静默失效——比弹错更危险。

---

### 发现汇总（① ② ③）

**F-1｜P2｜早退绕过 `endRound` 使 escalation 结算被跳过，且未被任何注释/测试覆盖**

- **位置**：`MomentsExport.kt` 5 条早退 return（`:319/327/335/343` 等）；`MomentsNotifyLogic.kt:324-328`（Gate.endRound KDoc）
- **论证**：见 ③(a)。当前**零行为影响**（早退原因全为 SILENT/ALWAYS，不读 `escalated`），但设计意图只论证了「不 reset latch」，漏了「不结算连续轮数」。一旦 ESCALATE 原因进入早退路径 ⇒ 永久漏弹，且静默无痕。
- **建议修法**（三选一，推荐第 1 个）：
  1. `Gate` 加 `fun abortRound() = escalation.endRound()`（**只结算、不 reset**），5 条早退改成 `notify(X).also { clean = false; notifyGate.abortRound(); return ... }`。语义显式，且可加单测。
  2. 早退分支直接调 `notifyGate.endRound(clean = false)`（等价，因为 clean 本来就 false）。
  3. 最小改动：在 `Escalation` 的 KDoc + `Gate.endRound` KDoc 里钉死「**早退路径只允许出现 SILENT/ALWAYS 原因**」，并补一条单测：早退轮 + `WRITE` ⇒ 必须仍能升级。

**F-2｜P2｜`MomentsNotifyLogic.kt:173` 的注释与代码事实相反**

- **位置**：`MomentsNotifyLogic.kt:173`
  > 「⚠️ 调用方漏了 [beginRound] 只会让『本轮计数』退化成『累计计数』（**更保守**），**不会误弹**。」
- **论证**：对 `Kind.ESCALATE` 原因，累计计数会让 `n` 更快冲到 `THIS_ROUND_THRESHOLD=3` ⇒ **更早升级 ⇒ 更容易弹 ⇒ 更激进**，不是「更保守」。后半句「不会误弹」也因此不成立（会**提前**弹）。对 ALWAYS/SILENT 确实无影响（不读 escalated）。
- **建议修法**：改成「漏调 `beginRound` 会让本轮计数退化为累计计数 ⇒ 对 [Kind.ESCALATE] 原因是**提前升级（更激进）**，对 SILENT/ALWAYS 无影响」。

**F-3｜P2｜`notify(Reason.THROW)` 落在 `beginRound`/`endRound` 窗口之外**

- **位置**：`MomentsExport.kt:255`（在 `notify()` 定义 `:286` 与 `runBlocking()` `:304` 之前 ⇒ 不在 `runBlocking` 体内，应在 `runAsync()` 的 catch 里）
- **论证**：
  - 该轮**既不** `beginRound()` **也不** `endRound()` ⇒ 本轮 `thisRound[THROW]` 恒为 1（下一轮 beginRound 清掉）、`streaks[THROW]` 恒 0 ⇒ THROW 的 escalation 完全空转。THROW 是 ALWAYS（不读 escalated）⇒ 对 THROW 自身无影响。
  - 但：异常轮里**已经记进 `thisRound` 的 `WRITE`/`MKDIRS_ITEM` 计数被整轮丢弃** ⇒ 若「报 WRITE」与「末尾抛异常」交替出现，WRITE 的连续轮数只能靠正常轮积累 ⇒ **升级延迟一轮**（不是永不升级，且 THROW 本身是 ALWAYS 会弹一次兜底）。
- **建议修法**：把 `notify(Reason.THROW)` 挪进 `runBlocking()` 内部的 try/catch（放在 `:456 endRound` 之前）；或在 `runAsync()` 的 catch 里补一句 `notifyGate.endRound(clean = false)`。

---

## ④ 删掉单参兼容重载后有没有漏网调用点

**全仓 `Grep MomentsNotify\.notify` 结果（代码文件只 2 条命中）**：

```
wxgroup/.../feature/MomentsExport.kt:292   notifyGate.onFailure(reason) { msg, cb -> MomentsNotify.notify(msg, cb) }   ← 双参 ✔
wxgroup/.../feature/MomentsNotifyLogic.kt:298  （KDoc 示例，非调用）双参 ✔
```

其余命中全部在 `docs/*.md`（历史文档），不影响编译。

**`MomentsNotify.kt` 现状（138 行全读）**：
- 只剩 `fun notify(msg: Message, onOutcome: (delivered: Boolean) -> Unit): Boolean`（`:69-72`）
- 原单参兼容重载已删除，原地留说明注释（`:115-117`）
- 全文件再无 `@Deprecated`

**反向验证**：`notifyGate` 全仓仅 4 处、`notifyLatch` 已无任何代码引用 ⇒ 不存在「某处还在用旧 latch + 旧单参 notify」的残留路径。

> **④ 结论：PASS，零漏网。**

**F-4｜P2｜`MomentsExport.kt:267` 的 KDoc 引用了已不存在的符号 `[notifyLatch]`**

- **位置**：`MomentsExport.kt:267`（匹配行可见：「按跨轮 [notifyLatch] 决定是否真的弹」）；`:170`/`:174` 的描述文字也仍按旧字段写
- **论证**：字段已改名 `notifyGate`（`:182`），`notifyLatch` 已不存在。`[notifyLatch]` 在 KDoc 里是 unresolved 引用 —— **不阻断编译**（Kotlin 不校验 KDoc 链接），但 IDE 标黄、且这段描述会误导后续维护者以为还有一条独立的 latch 路径。
- **建议修法**：`:267` 的 `[notifyLatch]` → `[notifyGate]`；`:170`/`:174` 的字段 KDoc 同步改成描述 `Gate`（latch + escalation 两个状态一起管）。

---

## ⑤ 有没有引入新的「双层吞异常」

两层的位置与通道：

| 层 | 代码 | 通道 |
|---|---|---|
| A | `MomentsNotify.show()` = `runCatching { UiKit.confirm(...); true }.getOrElse { log; false }`（`MomentsNotify.kt:130-136`） | **返回值**（Boolean），异常**不外抛** |
| B | `UiKit.runOnMain` = `runCatching { Handler(main).post { runCatching(block)... } }`（`UiKit.kt:156-167`） | **异常**（吞掉 block 抛出的 Throwable） |

调用形态（`MomentsNotify.kt:76`）：`UiKit.runOnMain { onOutcome(show(act, msg)) }`

### 推演

1. 弹窗失败（BadToken / token 失效 / Activity 已 finish）⇒ `UiKit.confirm(...).show()` 抛异常 ⇒ **层 A 捕获**，返回 `false`。
2. `false` 是 `show()` 的**返回值**，被直接喂给 `onOutcome(...)` ⇒ **`onOutcome(false)` 一定被调用**。
3. 层 B 的 `runCatching(block)` 只在 `block` **抛异常**时才生效。而 `block = { onOutcome(show(act,msg)) }` 里：
   - `show()` 已经被层 A 包住，**不会抛**；
   - `onOutcome` = `{ delivered -> if (delivered) latch.markFired(reason) }`（`MomentsNotifyLogic.kt:287`），**不会抛**。
   ⇒ 正常路径下**层 B 根本不会触发**。

**⇒ 两层 `runCatching` 位于互不重叠的两条通道（异常通道 vs 返回值通道），没有形成「吞掉 false」的通路。`onOutcome` 不会永远收不到 `false`。**

佐证：`MomentsNotify.show` 的 KDoc（`:122-123`）已明确写「这里是**唯一**捕异常的地方…**别双层吞**」⇒ 这次接线**没有**再包一层，遵守了约定。
有测试钉住：`notDeliveredDoesNotOccupyLatchAndRetriesNextRound`（`Test:332`）、`asyncGiveUpRetriesNextRound`（`Test:346`）。

> **⑤ 结论：PASS，无新增双层吞。**

### 但有一个**残留**缺口（非本次引入）

**F-5｜P2｜`UiKit.runOnMain` 的 `post` 自身失败时，回调永不被调用，且与「送达失败」无法区分**

- **位置**：`UiKit.kt:161-166` + `MomentsNotify.kt:76`
- **论证**：`Handler(main).post{}` 若失败（Looper 已退出 / Handler 创建失败），`block` 永不执行 ⇒ `onOutcome` **永远不被调用**；而 `notify()` 已经 `return true`（受理）。
  - **安全性**：Gate 的语义是「没收到 `true` 就不占 latch」⇒ 下轮重试 ⇒ **安全方向**，不会白占。
  - **但**：若该状况**持续**发生 ⇒ 每轮都尝试、每轮都静默失败 ⇒ 真机表现为「导出坏了但从不弹窗」，日志只有一行 `runOnMain post failed`，与「用户没看到窗」无法区分，排障时看不出来。
- **建议修法**：`UiKit.runOnMain` 改成返回 `Boolean`（post 是否成功），`MomentsNotify.notify` 在 `false` 时 `onOutcome(false) + return false`；最低成本也要把日志文案区分开（「post 失败，本轮视为未送达」）。

**F-6｜P2｜异步交付结果可能跨轮污染 latch（复活 R18 的 P0 形状，可达性低）**

- **位置**：`MomentsNotify.kt:102-110`（`postDelayed(1500ms)` 后回调）+ `MomentsNotifyLogic.kt:287`（回调里 `markFired`）
- **论证**：回调晚于本轮 `endRound` 到达时才出问题 ——
  1. 第 1 轮早退（如 READONLY）→ 异步投递，1.5s 后回 `onOutcome(true)`；本轮 0.1s 就结束了；
  2. 若 1.5s 内**又起了一轮**（手动探针 `op=moments sub=export` / `MomentsExport.refresh()`）且该轮 clean ⇒ `endRound(true)` ⇒ `resetAll()`；
  3. 1.5s 到第 1 轮的回调 `markFired(READONLY)` ⇒ latch 被**复活** ⇒ 下一次真实 READONLY 故障**不弹**（漏弹方向）。
  - 自动周期是 5 分钟，1.5s 窗口内起下一轮**只能靠手动触发** ⇒ 可达性低，但方向是**漏弹**（危险方向）。
  - 现有测试 `asyncGiveUpRetriesNextRound`（`Test:346`）只仿真了回调 `false` 的情况，**没有**覆盖「回调 true 但晚于下一轮 endRound」。
- **建议修法**：`Gate.onFailure` 给每次投递编 round 序号（或每次投递前 `pendingToken++`），回调到达时比对，过期回调**丢弃**（不 markFired）。或补一条单测钉住现状，至少把风险写进 KDoc。

**F-7｜P2｜`Gate` 的三个公开方法未加 `@Synchronized`**

- **位置**：`MomentsNotifyLogic.kt:311 / 318 / 329`
- **论证**：`Latch` / `Escalation` 的内部方法都是 `@Synchronized`，但 `Gate.endRound` 是「`escalation.endRound()` + `latch.resetAll()`」**两步两个锁**，不是原子对。`runBlocking` 靠 `running` AtomicBoolean 单航班 ⇒ **当前无实际风险**；但异步回调在主线程改 latch、导出线程在读 latch，两者确实跨线程。
- **建议修法**：给 `Gate` 的三个方法加 `@Synchronized`（开销可忽略，与内部锁同级）；或至少在 `endRound` 上标注「两步非原子，依赖调用方单航班」。

---

## 待确认（不判 FAIL，但需主会话/他 lane 一句话答复）

**OQ-1｜`:404` 的 SPACE_ABORT 之后是 `break` 还是 `return`？**

若是 `return` ⇒ 又是一条绕过 `:456 endRound` 的路径 ⇒ 该轮内已记的 `WRITE` 计数整轮作废（同 F-3 的形状）。
若是 `break` ⇒ 落到 `:456`，正常结算。
（SPACE_ABORT 自身是 ALWAYS ⇒ 无论哪种都不会影响它自己弹不弹。）

**OQ-2｜`MomentsExport.probe(sub="export", n)` 是否走 `runBlocking()`？**

`EntryReceiver.kt:479` 调 `MomentsExport.probe(sub, n)`；`MomentsNotifyLogic.kt:15` 写「手动探针 `sub=export` 不弹，只回摘要」。
若 `probe` 直接复用 `runBlocking()` ⇒ 探针轮也会走 `beginRound`/`endRound` ⇒ 一个 clean 的探针轮会 `resetAll()` ⇒ **R18 的「隔轮弹」骚扰形状可能借探针复活**（真实故障轮弹 → 探针轮 clean → reset → 真实故障轮再弹）。
⇒ 请确认 `probe` 的抑制机制是「不调 notify」还是「不进 runBlocking」。当前**无证据表明有问题**，故不判 FAIL。

---

## 未执行的验证（如实记录）

- **未跑 JVM 单测**（`MomentsNotifyLogicTest`）：他 lane 正在改 `MomentsExport.kt` / `MomentsLogic.kt` / `SnsCdnImage.kt`，
  此刻跑 `gradlew test` 很可能因**他人中间态**编译失败，红/绿都不说明问题 ⇒ 留给收口阶段跑。
  本轮所有结论均由**读码 + 逐轮推演 + 既有测试覆盖**得出。
- **未碰 adb / 真机 / 装包**（lane 约束）。
- **未修改任何业务源文件**，唯一产出是本文件。

---

## [DONE]

### 结论：**PASS**（可以上真机，**0 条阻断项**）

| 审项 | 结论 |
|---|---|
| ① 4 处接线 vs `Gate` API 契约 | **PASS** —— 签名/参数顺序/sender lambda 类型/返回值语义全部自洽，可编译 |
| ② `beginRound()` 放首行 | **PASS** —— 主会话判断成立（放结尾会跨轮累积、过早升级）；代价是「清零不结算」，不构成本轮缺陷 |
| ③ `endRound` 被 5 条早退绕过 | **PASS（当前）** —— 连续轮数**冻结在 0 而非一直涨**；早退原因全为 SILENT/ALWAYS，判据**不读 `escalated`** ⇒ 零行为影响；真机「1 弹 2/3 不弹」由 latch 独立保证，与 streak 无关。但**设计意图只论证了一半**（漏了 escalation 结算），留 P2 |
| ④ 单参重载删除无漏网 | **PASS** —— 全仓仅 `MomentsExport.kt:292` 一处调用，双参；无 `@Deprecated` 残留 |
| ⑤ 无新增双层吞异常 | **PASS** —— 两层 `runCatching` 分处**异常通道 / 返回值通道**，互不重叠；`show()` 的 `false` 必达 `onOutcome` |

### 阻断项清单

**无 P0，无 P1，无阻断项。**

### 非阻断发现（全部 P2，共 7 条 + 2 条待确认）

| ID | 严重度 | 一句话 | 是否本次引入 |
|---|---|---|---|
| **F-1** | P2 | 早退绕过 `endRound` 使 escalation 结算被跳过，未被任何注释/测试覆盖；未来若 ESCALATE 原因进早退分支 ⇒ **永久静默漏弹** | 是（接线设计缺口） |
| **F-2** | P2 | `MomentsNotifyLogic.kt:173` 注释「漏 beginRound 更保守」与代码相反（实为**更激进**） | 否（既有注释错误） |
| **F-3** | P2 | `notify(THROW)`（`:255`）在 begin/endRound 窗口外 ⇒ 该轮 escalation 空转、WRITE 计数整轮作废 ⇒ 升级**延迟一轮** | 是 |
| **F-4** | P2 | `MomentsExport.kt:267` KDoc 引用已不存在的 `[notifyLatch]` | 是（改名未同步） |
| **F-5** | P2 | `runOnMain` 的 `post` 失败 ⇒ 回调永不触发，日志与「送达失败」无法区分（安全方向，但持续发生时表现为「从不停窗」且难排障） | 否 |
| **F-6** | P2 | 异步 1.5s 回调晚于下一轮 `endRound(true)` ⇒ latch 被复活 ⇒ **漏弹**（需手动触发才可达，现有测试只覆盖 `cb(false)`） | 否 |
| **F-7** | P2 | `Gate` 三方法未 `@Synchronized`，`endRound` 两步非原子（当前单航班无风险） | 否 |
| OQ-1 | — | `:404` SPACE_ABORT 之后 break 还是 return | — |
| OQ-2 | — | `probe(sub=export)` 是否走 `runBlocking`（若走 ⇒ 隔轮弹可能借探针复活） | — |

### 最严重的一条

**F-1**（P2）：早退轮绕过 `endRound` 的**决策只论证了「不该 reset latch」这半边，完全没意识到连续轮数结算也被跳过了**。
当前**无害**（早退原因全是 SILENT/ALWAYS，`isNotifiable` 不读 `escalated`），
但它是一颗**静默地雷**——任何人往早退分支加一个 `WRITE`/`MKDIRS_ITEM`，该原因的 streak 就恒为 0 ⇒ 永远升不了级 ⇒ 用户再也不会收到通知，
且**不崩、无日志、单测不红**。建议按 F-1 的修法 1（加 `Gate.abortRound()`）或修法 3（钉死约束 + 补单测）在**收口前**处理，不必阻塞本轮真机。
