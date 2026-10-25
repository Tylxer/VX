# R18 F4B 独立复核报告（lane `f4b-review`）

> 状态：进行中。本节先落盘，后续分节 append。
> 复核范围：MomentsNotifyLogic.kt / MomentsNotify.kt / MomentsExport.kt(notify 相关) / MomentsNotifyLogicTest.kt
> 约束：只读，未修改任何代码文件。

## §0 结论速览

**结论：FAIL —— 有 1 项阻断上真机（F1），建议同批修 F2/F3。**

| # | 级别 | 一句话 | 位置 |
|---|---|---|---|
| F1 | **P0** | 有 Activity 时 `show()` 在**后台无 Looper 线程**直接 `AlertDialog.show()` ⇒ 必抛并被吞 ⇒ latch 被占 ⇒ 主路径永远弹不出窗 | `MomentsNotify.kt:56-58 / 91-97` |
| F2 | **P1** | 「受理未送达」也占 latch（含文档自述要防的「异步放弃」路径） | `MomentsNotify.kt:54-89` + `MomentsExport.kt:286-293` |
| F3 | **P1** | 文档承诺「手动探针 `sub=export` 不弹」**未实现**，探针与 ticker 走同一路径且 `runBlocking` 是 private | `MomentsExport.kt:1199-1202 / :67` |
| F4 | **P1** | `DB_NOT_READY` / `MKDIRS_ITEM` 两个枚举**从未被 emit**，注释把正确行为归错了因 | `MomentsExport.kt:307 / 383-391 / 277-279` |
| F5 | P2 | `WRITE`(弹) vs `MKDIRS_ITEM`(不弹) 的区分站不住 | `MomentsNotifyLogic.kt:36-46 / 82-83` |
| F6 | P2 | `countsAsFailure` 与 `isNotifiable` 实为同一谓词，「解耦」只解了一半 | `MomentsNotifyLogic.kt:142-145` |
| F7 | P2 | `decide()` 纯度未测；多轮用例区分力**有效但是接线仿真**，非真接线 | `MomentsNotifyLogicTest.kt` |
| F8 | P3 | 实现文档若干处已过期 / 与代码不一致 | `docs/_r18_f4b_impl.md` |

> 已排除项（按交办要求不再报）：已去重重复失败算失败的 P0 已修复（`MomentsExport.kt:296` 无条件 `return true`，正确）；编译/单测全绿不再报。

---

## §1 ① 9 态 Reason「弹 / 不弹」划分是否自洽

### 结论：不自洽。`WRITE`(弹) 与 `MKDIRS_ITEM`(不弹) 的区分站不住。

**`MomentsNotifyLogic.kt:36-46` / `:82-83`**

自述判据是「持久性故障弹、瞬时/单条偶发不弹」（`:13`、`:73-81`）。按这条判据逐项对照：

| | `MKDIRS_ITEM` | `WRITE` |
|---|---|---|
| 发生位置 | 循环内，单条 | 循环内，单条 |
| 是否阻断本轮 | 否（`continue`，`:390`） | 否（不设 `stop`，`:442`） |
| 是否自动重试 | 是（下轮 `scanExportedIds` 会把未落盘的 snsId 重新纳入） | 同左 |
| 是否可由同一根因引起 | 是（同一目录权限/AME/I/O 异常） | 是 |
| 判定 | **不弹** | **弹** |

两者在「是否单条、是否偶发、是否自愈、是否需要用户介入」四个维度上完全同构。给 `WRITE` 的辩护理由（`MomentsNotifyLogic.kt:42`「持久性（整盘只读 / 目录被删）」）在代码路径上**基本不可达**：

- **整盘只读**：`:332` 的 `!rootDir.canWrite()` 已经先命中 `READONLY` 并早退，根本走不到写文件；写过程中突然只读属于设备级故障，此时下一次空间/权限检查也必然失败。
- **目录被删**：`:383` 的 `dir.mkdirs()` 会**重建**整条父链（包括被删的中间目录），mkdirs 成功才轮到 `:424` 写文件。所以「目录被删」表现出来的是 `MKDIRS` 或根本无异常，而不是 `WRITE`。
- **真正的 WRITE 触发源**只剩下单条级/瞬时的东西：目标路径被别的进程占用、`.tmp → txt` 的 `renameTo` 跨状态失败（同目录 rename 理论可靠，但 EMM / sdcardfs / 媒体所扫础磁 DOS name 冲突会让个别名字失败）、单个文件名被 Vista SME 策略拒绝。这些恰恰都是「单条偶发」，正是不该弹的那一类。

⇒ 结论：**站不住**。

### 建议判据（替代方案）

不要用「哪个枚举值」静态判「持久 / 偶发」，而要用**「这一轮它持不持久」**当判据 —— 这才是原判据的本意，并且能同时处理两者：

> **per-item 失败本身不弹；但若 `n` 轮内（或同一轮内）同因失败数超过阈值，说明不是偶发而是系统性的，升级为可弹。**

落地建议（任选其一，都要改 `MomentsNotifyLogic`，保持可单测）：

1. **计数升级**（推荐，最小改动）：向 `decide()` 传一个本次的「同类失败累计次数」，超过阈值（例如本轮 ≥ 3 次，或连续 2 轮都有）才 `countsAsFailure && doNotify`；否则 `(false,false)`。这样 `WRITE` 和 `MKDIRS_ITEM` 自然落入同一套规则，不需要再为每个枚举写「手感判断」。
2. **统一降级**：把 `WRITE` 拉到与 `MKDIRS_ITEM` 一致（不弹、不计失败），只保留 `er` 计数与日志 —— 也自洽，但会失去「明确弹性磁盘写不了」的告知能力，不如方案 1。

### 附带证据：这个争议**目前不影响真机表现**

`Reason.MKDIRS_ITEM` **从未被 emit**（见 F4），所以「该不弹」这个决定目前没有任何运行时后果；真正被执行的是「`WRITE` 弹」。而由于 latch 的存在，`WRITE` 最多弹 1 次、直到出现一次 clean 轮为止 —— 所以 F5 是**设计自洽性问题**，不是用户可感知缺陷，因此定 P2 而非 P1。

### 另一处一致性风险：`isNotifiable` 是黑名单

`MomentsNotifyLogic.kt:82` `reason != DB_NOT_READY && reason != MKDIRS_ITEM` ⇒ 新增第 10 个 `Reason` 时**默认「弹」**。`messageFor` 是 `when` 表达式、缺分支编译不过（会逼着补文案），但没人逼你决定它该不该弹。建议改成 `when` **表达式**逐枚举返回 `true/false`（缺分支编译报错），或在测试里加一条「全枚举 × 期望表」断言（见 §5）。

## §2 ② `decide()` 两个字段的组合

### 结论：4 种组合里只用了 3 种，第 4 种不该存在 —— **组合本身没错**，但「两件事已彻底解耦」的说法只成立一半。

**`MomentsNotifyLogic.kt:142-145`**

实测出的组合表：

| `countsAsFailure` | `doNotify` | 何时出现 | 评价 |
|---|---|---|---|
| `false` | `false` | `!isNotifiable(reason)`（`:143`） | ✅ 正确：DB_NOT_READY / MKDIRS_ITEM 既不算失败也不弹 |
| `true` | `true` | 可弹 + 首次（`:144` 且 `shouldFire=true`） | ✅ 正确 |
| `true` | `false` | 可弹 + 去重命中（`:144` 且 `shouldFire=false`） | ✅ 正确，正是 P0 修复的那条 |
| `false` | `true` | **不可达**（`doNotify` 只在 `isNotifiable` 为真分支里算） | ✅ 正确：不该存在。若存在，会出现「弹了但 clean=true ⇒ resetAll」⇒ 同一事故反复弹 |

**没有错配的组合。** 这一项本身是干净的，特别是 `:143` 的提前返回在结构上就排除了 `(false,true)`，比用两个独立布尔拼更稳。

### 但要指出：`countsAsFailure` 与「是否 P0 文档说的解耦」不完全一致

`MomentsNotifyLogic.kt:136` 注释：「`countsAsFailure` 只看故障本身值不值得算失败（[isNotifiable]），与去重无关」—— 这句是真的（与去重无关），但容易读出第二种意思：「`countsAsFailure` 是独立于 notifiability 的判据」。实际 `:143-144` 里 **`countsAsFailure ≡ isNotifiable(reason)`**，两者是同一个函数的两个名字。

后果（语义层面，非用户可感知）：`clean` 的真正含义不是「本轮成功导出」，而是「**本轮没发生任何值得弹窗的故障**」。于是下面这轮会被判 clean 并 `resetAll()`：

- 全部 500 条动态都因为坏昵称 `mkdirs` 失败（`er=500`、`w=0`、一个文件都没写出来）；
- `rows=0`（数据库为空，什么都没干）。

前者按常识不是成功。虽然它不会造成「反复弹」（latch 已 Rearmed 的原因如果还坏，本轮必然再次 `notify` ⇒ clean=false），但它让「成功才复位」这个不变量变得不准确，**是未来重构时容易踩的语义地雷**。

### 建议修法

- 短期（不动行为）：把 `:143` 写成 `Decision(countsAsFailure = false, doNotify = false)` 时**显式注释** "`countsAsFailure` 目前等价于 `isNotifiable`，二者将来若分家必须同步改 `MomentsExport.notify()` 的 `clean` 语义"；
- 更正确（建议）：新增独立谓词 `countsAsFailure(reason)`（默认 `true` for 所有失败），让 `decide` 返回 `Decision(countsAsFailure(reason), isNotifiable(reason) && latch.shouldFire(reason))`；把「一轮到底算不算成功」的定义权显式化，而不是藏在 notifiability 里。

---

## §3 ③ `markFired` 的时机（**本节含 P0**）

### F1（P0）：有 Activity 这条**主路径**在后台无 Looper 线程上弹 AlertDialog，必抛必吞

**位置**：`MomentsNotify.kt:54-58`（branch 1）+ `:91-97`（`show`）
**调用链**：`MomentsExport.runAsync():241-256` 起 `Thread { ... runBlocking() }`，该线程 `isDaemon=true`、**从未 `Looper.prepare()`** ⇒ `MomentsExport.notify():286` ⇒ `MomentsNotify.notify():56` ⇒ `show(act, msg):57` ⇒ `UiKit.confirm():213-220`。

`UiKit.confirm` 的实现是裸 `AlertDialog.Builder(ctx)...show()`（`UiKit.kt:213-220`），**内部没有任何主线程跳转**。后果按 Android 框架行为推：

1. `AlertDialog` 继承 `Dialog`，`Dialog` 的字段初始化里有 `mListenersHandler = new ListenersHandler(this)`（`extends Handler` 且走无参构造）⇒ 无参 `Handler()` 内部 `Looper.myLooper()` 为 null ⇒
   `RuntimeException: Can't create handler inside thread Thread[moments-export,...] that has not called Looper.prepare()`。
2. 即便某 ROM 的 `Dialog` 不建 Handler（或线程碰巧有 Looper），下一步 `WindowManager.addView` 会在**导出线程**上创建 `ViewRootImpl`（其 `mHandler` 同样要求 Looper，且只允许创建它的线程操作视图）⇒ `CalledFromWrongThreadException` / `BadTokenException`。

两种情况下异常都被 `:92` 的 `runCatching { }` 吞掉，只留一行 `w` 日志；而 `notify()` 在 `:58` **无条件 `return true`**。

⇒ 完整失效链：
```
用户正在前台(& Activity 非空) + 导出失败
   → show() 抛 RuntimeException → 被吞 → notify() 返回 true
   → MomentsExport.notify():288 markFired(reason)
   → 该原因**永不再弹**（直到出现一次 clean 轮）
而 clean 轮只有在不再发生该故障时才出现 ⇒ 这个故障恰好再也不会被修好前不复位
   ⇒ 用户从头到尾一次都没看到弹窗，导出失败这件事完全静默
```

**这是本轮复核唯一需要阻断的事项**：它不是 BadTokenException 的偶发竞态，而是「用户在前台」这个最该弹的场景下**稳定失败**。反讽的是 branch 2（微信在后台）走 `postDelayed` 到主线程，反而能弹出来。

### 决定性反证：同一份代码里已有正确写法

`BackupPanelPage.kt:162-173` 是**完全同构**的场景（后台线程跑耗时操作 → 失败/成功 → 弹 AlertDialog），它的写法是：

```kotlin
Thread { val r = runCatching { ... }.getOrNull()
    UiKit.runOnMain {                          // ← 回主线程
        AlertDialog.Builder(ctx)...show()
    } }
```

而且 `UiKit.runOnMain`（`UiKit.kt:148-167`）的注释白纸黑字写着设计约束：「**回调里改 UI/弹窗必须回主线程**这条约束很容易漏（漏了的表现是偶发 `CalledFromWrongThreadException` 或 toast 不显示）」。`MomentsNotify` 正是漏了这条。

**建议修法（必须）**：branch 1 也过主线程 ——

```kotlin
val act = HostContext.activity()
if (act != null) {
    UiKit.runOnMain { show(act, msg) }   // 现在是直接在调用线程上 show
    return true
}
```

同时这解决了本节的下一个问题（见 F2）：`runOnMain` 内部自带 `runCatching`，吞异常的位置不变，但**返回值得重新定义**。

### F2（P1）：「受理但未送达」一律占 latch —— 包括文档自己声称要防的那条路径

即使按 F1 修好了线程，`show()` 的 `runCatching`（`:92`）还是会吞掉真实故障（`BadTokenException` 在 `activityRef` 检查之后到 `show()` 落地之间的 `isFinishing` 竞态、无焦点窗口 `WindowManager$BadTokenException: Unable to add window -- token android.os.BinderProxy@... is not valid` 等）。回答交办里的直接提问：

> **如果弹窗实际抛了 `BadTokenException`，`notify()` 会返回什么？**

**返回 `true`**。`show()` 返回 `Unit`，异常在 `show()` 内被吞，`:58` 紧接着 `return true`。⇒ **确实会被误判成受理成功并占住 latch，用户没看到窗却再也不弹**。交办里担心的这个场景真实存在，不只是理论问题。

更严重的是**异步分支自己打自己脸**：`MomentsNotify.kt:39-42` 的设计说明写着

> 「若把『异步放弃』当成成功，则 latch 被白占、该故障再也不弹。⇒ 受理即占用，异步放弃属环境问题，下轮重试。」

但 `:79-88` 的实现是：拉起后 `postDelayed(1500ms)`，**立刻 `return true`**；1.5s 后若 `HostContext.activity()` 仍为 null，只打一行日志就放弃 —— **没有任何回路把这个失败告诉调用方**。`MomentsExport.notify():288` 早已经在上一条语句就把 latch 占了。于是「异步放弃 ⇒ 白占 latch ⇒ 再也不弹」这个文档自述的缺陷，**在代码里原样存在**，且「下轮重试」不可能发生（`shouldFire` 已 false）。只有出现一次 clean 轮才能解冻；而 persistent 故障恰恰永远产不出 clean 轮。

### 建议修法（F1 + F2 一次改掉）

把「占用 latch」从「受理」改为**「送达」**，两处 need-stop：

```kotlin
// MomentsNotify
fun notify(msg: MomentsNotifyLogic.Message, onOutcome: (delivered: Boolean) -> Unit): Boolean

// 有 Activity: UiKit.runOnMain { runCatching { UiKit.confirm(...) }.onFailure{...} } → 结果回调 onOutcome
// 异步: postDelayed { val a = HostContext.activity(); if (a == null) onOutcome(false) else showThen(a, onOutcome) }
```

`MomentsExport.notify()` 相应改成：`d.doNotify` 为真时调用 `MomentsNotify.notify(msg) { delivered -> if (delivered) notifyLatch.markFired(reason) else LogX.w("... not delivered") }`，`notify()` 仍返回 `countsAsFailure`（保持 P0 修复）。这样：

- 送达才知道 ⇒ 不会白占；
- 「异步放弃」⇒ `onOutcome(false)` ⇒ 下轮真的会重试（与文档口径一致）；
- 新分支「已弹但用户点了『取消』」仍算送达（只弹一次的设计意图不变）。

> 注：`UiKit.runOnMain` 内的 `runCatching` 会把异常吞掉且不回传，所以要么在 `MomentsNotify.show()` 里自己拿 onion 结果（推荐：`show` 改成返回 Boolean），要么就不用 `runOnMain`、直接 `Handler.post { }` 自己管结果。别两者都用（双层吞异常，结果永远拿不到）。

---

## §4 ④ `resetAll()` 的触发点

**位置**：`MomentsExport.kt:455` `if (clean) notifyLatch.resetAll()`

### `runBlocking()` 里共有 5 条提前 return（不是 3 条）

| # | 行 | 条件 | 是否过 `:455` | `clean` 值 | 语义 |
|---|---|---|---|---|---|
| R1 | `:307` | `GroupDb.current() == null`（db not ready） | **否** | `true` | 空转轮，不 reset ✅ |
| R2 | `:322` | root 存在但非目录 | 否 | `false` | 失败轮 ✅ |
| R3 | `:330` | root 不存在且 mkdirs 失败 | 否 | `false` | 失败轮 ✅ |
| R4 | `:338` | root 不可写 | 否 | `false` | 失败轮 ✅ |
| R5 | `:346` | 起步空间不足 | 否 | `false` | 失败轮 ✅ |

外加两条**非 return 退出**：`:408` 的 `stop=true; break`（`SPACE_ABORT`）和循环自然结束 —— 两者都会落到 `:455`，且 `clean=false`，正确。

⇒ 5 条 return 的语义**全部正确**：早退 = 失败轮，不 reset；早退本身也顺带保证了 `clean=true` 的 R1 不会误 reset。我把 5 条逐条走完，**没有发现第四条被漏掉的 return**。

### 但 R1 的「正确」是靠巧合，注释归错了因（这就是我要补的第四条之外的坑）

`MomentsExport.kt:277-279` 的注释写：

> ⚠️ 注意 `DB_NOT_READY` / `MKDIRS_ITEM` 仍返回 `false`：**这样 `db not ready` 的早退不会 reset latch**

这句**归因是错的**。真实机制是：

1. `Reason.DB_NOT_READY` **根本没接进 `notify()`** —— `:307` 是裸 `return "$TAG export skip: db not ready"`，**一行都没碰 `notify`**；
2. 所以「不 reset」是因为 **`:307` 的 return 绕过了 `:455`**，跟 `notify` 返回什么**毫无关系**。

危险在于：如果有人日后把 `:307` 改成「打个日志继续往下走」或把 reset 逻辑挪进某个汇总块，注释会让人以为「DB_NOT_READY 返回 false」已经兜住了 —— 实际上 `:307` 早退一旦消失，`clean` 会保持 `true` 并在 `:455` 触发 **`resetAll()`**，把此前所有 persistent 原因的去重状态全清掉。

同理 `MKDIRS_ITEM`：`:383-391` 里循环内 `mkdirs` 失败只有 `er++` + 日志 + `continue`，**也没调 `notify(...)`**（见 F4）。注释里说它「返回 false」是描述一个不存在的调用。

### 建议修法

- 把 `:277-279` 的注释改成：**「这两个原因当前未接线（`:307` 早退 / `:390` continue），所以走不到 `notify`；即便接了，`notify` 返回 false 也**不能**阻止 `:455` 的 reset —— 阻止 reset 的是早退/continue 本身，别依赖返回值」**；
- 若确实想让「单项故障也不算 clean」由返回值统一兜住，就得给 `notify` 的返回值安排成既能早退又能计 clean 的形式，别混着用两套机制。

---

## §5 ⑤ 13 条单测有没有「测了个寂寞」的

逐个走查 `MomentsNotifyLogicTest.kt`：

| # | 用例 | 行 | 判断 | 说明 |
|---|---|---|---|---|
| 1 | `persistentFailuresAreNotifiable` | :22-31 | ⚠️ 弱 | 逐个 assertTrue，值反了能抓到；但**新增 Reason 时不报错**（`isNotifiable` 是黑名单语义，默认「弹」） |
| 2 | `transientFailuresAreSilent` | :38-42 | ⚠️ 弱 | 同上，反向：新 Reason 若本来该不弹，测试不会提醒你加进来 |
| 3 | `everyReasonHasMessage` | :47-54 | ✅ 有效 | `values()` 遍历 + 非空 + 统一标题，漏文案能抓 |
| 4 | `messagesAreDistinctPerReason` | :58-61 | ✅ 有效 | 真能抓「两个原因糊同一句」 |
| 5 | `latchFiresOncePerReason` | :66-73 | ✅ 有效 | 状态转移齐全（true→false→false） |
| 6 | `latchIsPerReason` | :77-82 | ✅ 有效 | 覆盖「原因互不挤占」 |
| 7 | `resetAfterSuccessReallows` | :86-96 | ✅ 有效 | `assertEquals(2,size)` 是真断言，非空集合检查就有意义 |
| 8 | `decidePersistentFirstTimeIsFailureAndNotify` | :113-119 | ✅ 有效 | 但**缺 latch 纯度断言**（见下） |
| 9 | `decideAlreadyFiredStillCountsAsFailure` | :127-135 | ✅ 有效 | P0 正中心，明示了「去重命中 ≠ 成功」 |
| 10 | `decideTransientIsNeitherFailureNorNotify` | :138-146 | ✅ 有效 | 覆盖 (false,false) |
| 11 | `fiveConsecutiveFailuresNotifyExactlyOnce` | :162-179 | ✅ **有区分力** | 见下方红/绿推演 |
| 12 | `successRoundResetsSoNextFailureNotifiesAgain` | :185-212 | ⚠️ 有点虚 | 「成功轮」直接用 `latch.resetAll():201` 代替，**没模拟「clean 导致 reset」这条因果链**；`:204` 用 `run {}` 包一层无意义（删掉等价） |
| 13 | `alternatingReasonsEachNotifyOnce` | :217-236 | ✅ **有区分力** | 见下方推演 |

**没有断言恒真的用例**，`.assertEquals` 都用得对，`firedReasons()` 快照 API 也不是摆设（#7/#9 用了）。

### 交办特别问的两条：能不能区分「正确实现」与「每隔一轮弹一次的缺陷实现」？

**能。** 我把缺陷实现（`countsAsFailure = doNotify`，即 P0 原始形态）代进测试里的仿真循环，逐轮推：

**#11（同一原因 5 轮）**

| 轮 | `shouldFire` | `doNotify` | 缺陷 `countsAsFailure` | `clean` | 是否 reset | 缺陷弹数 | 正确弹数 |
|---|---|---|---|---|---|---|---|
| 1 | true | true | true | false | 否 | 1 | 1 |
| 2 | false | false | **false** | **true** | **是** | 1 | 1 |
| 3 | true | true | true | false | 否 | **2** | 1 |
| 4 | false | false | false | true | 是 | 2 | 1 |
| 5 | true | true | true | false | 否 | **3** | 1 |

⇒ 断言 `1` 在缺陷版拿到 `3`，**测试红**。✅ 有区分力（与实现文档 §P0 红/绿记录一致）。

**#13（WRITE / SPACE_ABORT 交替 5 次）**

| 次序 | 原因 | `doNotify` | 缺陷 `countsAsFailure` | `clean` | reset? | 缺陷计数 | 正确计数 |
|---|---|---|---|---|---|---|---|
| 1 | WRITE | true | true | false | 否 | W=1 | W=1 |
| 2 | SPACE_ABORT | true | true | false | 否 | S=1 | S=1 |
| 3 | WRITE | false | **false** | **true** | **是** | W=1 | W=1 |
| 4 | SPACE_ABORT | true | true | false | 否 | **S=2** | S=1 |
| 5 | WRITE | false | false | true | 是 | W=1 | W=1 |

⇒ `assertEquals(1, counts[SPACE_ABORT])` 在缺陷版拿到 `2`，**测试红**。✅ 有区分力。

### 但有一个**结构性缺陷**：这些多轮用例测的是「接线的仿真」，不是接线本身

`:151-157` 的注释写得很诚实（"模拟 `MomentsExport.runBlocking` 的真实接线"）——问题是**仿真副本与真实接线会漂移**：

- 测试里「受理成功才 markFired」是手写的一行 `latch.markFired(...)`（`:173`）；
- 真实的这句在 `MomentsExport.kt:286-288`，**没有任何测试覆盖它**。

后果：**把 `MomentsExport.notify()` 改坏，13 条测试一条都不会红。** 具体可复现的例子：

1. 有人把 `:288` 的 `markFired` 移到 `if (d.doNotify)` 外面（受理失败也占 latch）⇒ F2 场景复活 ⇒ 测试全绿；
2. 有人把 `:296` 的 `return true` 改回「返回是否弹了」（P0 原状）⇒ **P0 复活，测试全绿** —— 因为这也动的是接线而不是 `decide()`。

而 §3 的 P0（F1）恰恰就住在这段没有被覆盖的真接线里。这不是实现者的疏漏 —— `MomentsNotify` / `MomentsExport.notify()` 依赖 Android，JVM 单测在结构上就够不着它 —— 但这一点必须记录在案，别让「13 条全绿」给人虚假的安全感。

### 补测建议（按性价比排序）

1. **把真接线搬进可测层**：在 `MomentsNotifyLogic` 里加一个 sender 形参的纯函数，让测试跑的是**跟上线同一份代码**：
   ```kotlin
   /** @return 本轮是否发生故障；@param sender 返回「是否送达」 */
   fun onFailure(latch: Latch, reason: Reason, sender: (Message) -> Boolean): Boolean {
       val d = decide(latch, reason)
       if (!d.countsAsFailure) return false
       if (d.doNotify && sender(messageFor(reason))) latch.markFired(reason)
       return true
   }
   ```
   `MomentsExport.notify()` 退化为一行：`return MomentsNotifyLogic.onFailure(notifyLatch, reason, MomentsNotify::notify)` —— 不改库的纯度约束（lambda 类型无 Android 依赖）。这样「受理失败不占 latch / P0 不复活」都由真实代码保证。**这一条同时是 F2 的修法载体。**
2. **测 `decide()` 的纯度**：调用 `decide` 后断言 `latch.firedReasons().isEmpty() && latch.shouldFire(reason)`。目前若有人让 `decide` 自己 `markFired`（文档 `:139` 明确禁止），**13 条用例一条都不红**（#11 因 markFired 幂等仍得 1，#13 同理）。
3. **补「受理失败 ⇒ 不占 latch ⇒ 下轮重试」**用例：用 `onFailure(latch, r) { false }` 驱动 2 轮，断言触发次数为 2。
4. **Reason 全覆盖断言**：遍历 `values()` 与一张显式期望表对比（`when` 表达式 + `else -> fail`），把 F5/§1 里的黑名单风险钉死。
5. 顺手：删除 `:204` 无意义的 `run {}`；把 #12 的成功轮改成走 `clean=true → resetAll` 的仿真回路，而不是直接调 `resetAll()`。

---

## §6 文档 ↔ 代码一致性复核（交待的「之前 P0 的根源」这一类问题）

| 出处 | 文档/注释口径 | 代码实际 | 判定 |
|---|---|---|---|
| `MomentsExport.kt:67`、`MomentsNotifyLogic.kt:15`、`_r18_f4b_impl.md:21` | 「手动探针 `sub=export` **走 `runBlocking()`**，只回摘要**不弹**」 | `probe():1199-1202` 调的是 **`runAsync()`**，与 ticker 完全同一条路径 ⇒ **会弹窗、会占 latch**；且 `runBlocking` 是 **`private`**（`:306`），文档描述的调用方式在编译期就不可能 | ❌ **不一致（F3）** |
| `MomentsExport.kt:300` 「同步跑一轮（探针在 io 线程里直接调）」 | 同上 | `runBlocking` private，外部无法调 | ❌ 不一致（F8） |
| `MomentsExport.kt:277-279` | 「DB_NOT_READY / MKDIRS_ITEM 仍返回 false ⇒ 早退不会 reset latch」 | 两原因**从未接线**；不 reset 的原因是 `:307` 的 return 绕过 `:455` | ❌ **归因错误（F4）** |
| `MomentsNotify.kt:12` / `:20` | 「拿上下文 → **主线程**弹 `UiKit.confirm`」 | branch 1 在**调用线程**（无所谓 Looper 的导出线程）上直接弹；只有 branch 2 过了 `mainHandler` | ❌ **不一致（F1）** |
| `MomentsNotify.kt:39-42` | 「受理即占用，异步放弃属环境问题，**下轮重试**」 | 异步放弃时 latch 已被占，`shouldFire` 恒 false ⇒ **下轮不会重试** | ❌ **自相矛盾（F2）** |
| `_r18_f4b_impl.md:18` | `WRITE` 理由：「持久性（整盘只读 / 目录被删）」 | 整盘只读被 `:332` `READONLY` 先截；目录被删会被 `:383` `mkdirs()` 重建 | ❌ 理由不成立（F5） |
| `_r18_f4b_impl.md:16` | `MKDIRS_ITEM` 处理为「不弹」 | 该原因**根本没接线**，行为上无法观察 | ⚠️ 描述悬空（F4） |
| `_r18_f4b_impl.md:45/59` | 「7 用例全过」 | 现为 **13 条** | ⚠️ 已过期（F8） |
| `_r18_f4b_impl.md:25` | 「9 个原因各持一个 `fired` 标记」 | 9 个都有 `fired` 槽位，但其中 2 个永不进入 | ⚠️ 描述悬空 |
| `_r18_f4b_impl.md:29-32` 已作废那句 | 已划删除线并标注 P0 | 与现行代码一致 | ✅ 一致 |

**规律**：这一轮的文档口径偏差集中在「描述一个未被接线的机制」和「描述一个想要但没做到的线程/回调契约」两种形态 —— 与上一个 P0（「文档一句错口径 ⇒ 代码照着写」）是同一类。建议在合入前按上表逐条对齐，至少 F1/F2/F3/F4 四条对应的注释必须改写，否则下一个改这段代码的人会照着注释做出错判断。

---

## [DONE]

### 结论：**FAIL**

**阻断项（必须修完才能上真机）：1 项**

- **F1（P0）** `MomentsNotify.show()` 在无 Looper 的后台导出线程上直接 `AlertDialog.show()`，异常被 `runCatching` 吞掉而 `notify()` 仍返回 `true` ⇒ latch 被占 ⇒ **用户在前台时（唯一「应该直接弹」的场景）稳定弹不出窗，且此后永不再弹**。
  - 修法：`MomentsNotify.kt:56-58` 的 branch 1 改 `UiKit.runOnMain { show(act, msg) }`（参照同仓库 `BackupPanelPage.kt:167` 的正确写法）。
  - 判断依据：`MomentsExport.runAsync():243` 的 daemon `Thread` 从未 `Looper.prepare()`；`UiKit.confirm():213-220` 无主线程跳转；`UiKit.runOnMain` 注释已明确「后台回调改 UI 必须回主线程」。

**强烈建议同批改（不阻断但不改会污染真机取证/对账）：3 项**

- **F2（P1）** 「受理未送达」占 latch，包括文档自述要防的异步放弃路径 ⇒ 改为「送达才占」（`show()` 回传结果 → 回调 `markFired`）。与 F1 同一个改动面，拆开做是浪费。
- **F3（P1）** 「手动探针 `sub=export` 不弹」文档承诺未实现，探针 = `runAsync()` 会弹窗并占 latch；且文档说它走 `runBlocking()`，而 `runBlocking` 是 `private`。
- **F4（P1）** `DB_NOT_READY` / `MKDIRS_ITEM` 两个枚举从未 emit（真接线只有 7 处），注释把「早退不会 reset latch」归功于 `notify` 返回值，实际是早退绕过了 `:455` —— 归因错误，是下一次重构的坑。

**可排后续：4 项**

- F5（P2）`WRITE` 弹 / `MKDIRS_ITEM` 不弹的区分不自洽，建议改用「同因失败计数是否过阈值」当判据。
- F6（P2）`countsAsFailure ≡ isNotifiable`，「彻底解耦」只解了一半；`clean` 的实际含义是「无值得弹窗的故障」而非「本轮成功」。
- F7（P2）13 条单测无恒真断言、两条多轮用例经推演确实有区分力（缺陷版红），但**全部是对接线的仿真** ⇒ 改坏 `MomentsExport.notify()` 不会红（这正是本次 P0 的住所）；另缺 `decide()` 纯度断言与「受理失败不占 latch」用例。
- F8（P3）实现文档 ④「7 用例」过期（现 13）；`runBlocking` private 与注释矛盾。

### 对发现项的整体判断

逻辑层（`MomentsNotifyLogic`）写得相当扎实：`decide()` 的两个字段、latch 的 per-reason 语义、`Decision` 的 4 种组合里不可达的那种都处理对了，上一个 P0 也确实修好了（`MomentsExport.kt:296` 无条件 `return true` 是关键一行）。**问题全部出在有 Android 依赖的那一层（线程、上下文、回调契约）以及「文档描述了一个没接线的机制」上** —— 前者这条 P0 单靠 JVM 单测无论如何也测不出来（它住在 `MomentsNotify`，而 `MomentsNotify` 恰恰是这三层里唯一完全没有测试的一层），所以建议在合入前至少人工走一遍「用户正在前台 + 导出失败」这条真机路径确认 F1 修好了。


