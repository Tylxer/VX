# R73-L6 独立交叉复核结论

- 复核人：r73-l6-review（hy4flash 模型，临时规则）
- 复核对象：wxgroup（LSPosed 模块，宿主微信 8.0.48 / versionCode 2588，包 com.lx.wxgroup）
- 复核范围（未提交工作区）：
  - `app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt`：`snapshotNow()` 改用 `staleViewForProbe()`（~795）、`onForwardClick()` 日志追加 `stale=true`（~789）、`staleViewForProbe()` KDoc 改为「长按快照取源（忽略 TTL）」（~1951-1963，实现不变）
  - `app/src/test/java/com/lx/wxgroup/feature/SnapshotSourceTest.kt`（新建，4 用例）
- 纪律：只复核、未改任何源码、未调用 adb、未装包、未碰设备；未运行 gradle（按分工由 team-lead 统一执行）

## 总结论

**可装机（进入真机测试）**。

- 无 P0 / P1 阻断项。核心改动正确且最小：点击链取快照从带 10s TTL 的 `pressedView()` 换到忽略 TTL 的 `staleViewForProbe()`，直接命中已实证的根因（慢选 11.5s → 快照退化 UNKNOWN → planFor 判 SKIP → 已发送 0 个目标）。
- 快选路径（<10s）两函数指向同一 View，零回归。
- 异常安全：全链路 `runCatching` 兜底 + NULL 安全，未发现可致点击崩溃的路径。
- 唯一需要盯的风险是「陈旧 View 被回收重绑 → 转发错消息」（详见边界 1）；在常规交互流下该场景概率很低，不构成打回理由，但真机测试需重点观察，并建议补一条低成本的日志/校验加固（P2，非阻断）。
- `stale=true` 恒真日志（P3）与单测中 1 个自证式用例（P3）为改进项，不阻断装机。

---

## 边界 1：View 回收复用（慢选 >10s 期间滚动聊天）

**机制事实（读码确认）**
- `burstDeepest` 是在 `installLongPressTrap()` 的 `View.performLongClick` before 回调里写入的 `WeakReference<View>`，只在新一轮 burst（与上次间隔 > `BURST_GAP_MS=500ms`）时更新（`ForceForward.kt:247-266`）。此后直到下一次长按，它一直指向那次长按最深处的 View。
- `msgRefOf(v)` **不是靠 View 身份取消息，而是靠位置**：沿 parent 链找列表 → `positionOf(list, v)`（`recycler` 走 `F0/G0/…`，`listview` 走 `getPositionForView`）→ `adapter.getItem(pos)` → h9（`ForceForward.kt:843-905`、`912-938`）。这是本边界的关键：快照内容取决于「这个 View **当前**位于列表的哪个 position」，而不是它被长按那一刻的位置。

**最坏后果**
- 若在 >10s 窗口内用户滚动了聊天、且 `burstDeepest` 指向的 View 仍挂在列表上但已被 RecyclerView/ListView **回收并重绑到另一条消息**，`positionOf` 会返回它**当前**的 position，`getItem(pos)` 返回的是**另一条消息的 h9**。`snapshotOf` 于是产出一条「msgId / 文本 / 图片路径 / talker 全错」的 Snapshot。该快照经 `openTargetPicker` → `execute` → `planFor`（此时 kind 往往是支持的真实类型，**不会** SKIP）→ 把**错误的消息**发给选中的目标（可能几百个分组/标签）。
- 这**严格比 SKIP 更差**：SKIP 只是「一条没发」，而错发是「把不该发的内容发给了很多人」，用户可见且不可撤回。注意旧 KDoc 曾明确写过这条红线——「生产路径**必须**继续用 `[pressedView]`（过期就当作没有长按，**避免拿陈旧 View 去发错消息**）」（见本轮 diff 删除的原文）。本轮改动是**刻意拆掉了这道保护**。

**会不会真的发生（缓释事实）**
1. 快照是在**点菜单项的当下**（`onForwardClick → snapshotNow`）取的，发生在 `openTargetPicker` **之前**。选人器里挑人的那段时间不计入这个窗口，也不会让已取到的 `snap` 变化。
2. 该窗口内原生 PopupWindow 菜单处于显示态；滚动聊天列表通常先使菜单 `dismiss`，而菜单一旦 dismiss 就没有「我方格子」可点。要触发 `onForwardClick` 必须菜单仍在，因此「先滚动、再点同一菜单项」在常规交互里基本互斥。
3. 因此「>10s 窗口内滚动 + 恰好该 View 被重绑 + 菜单仍存活」是窄场景。其余陈旧情形都安全降级：
   - View 已 detached → parent 链找不到列表 → `msgRefOf` 返回 null → kind=UNKNOWN → `planFor` 首行 SKIP（与修复前一致，不更差）；
   - WeakReference 已被 GC → `burstDeepest?.get()` 为 null → 同上 SKIP。

**结论与建议（不改变「忽略 TTL」已定方案）**
- 不是 P0，不阻断装机；但它是本轮唯一「可能比 SKIP 更差」的点，真机测试要专门验证「慢选后发出的内容是否就是长按的那条」。
- 建议 P2 加固（任选其一，均为附加保护，不改忽略 TTL 的取源）：
  - A（最稳）：长按抓取时同时记下稳定的消息身份（如 `msgId/msgSvrId`），点击时重取后比对；不一致则降级 SKIP 或按 msgId 重新定位。
  - B（最省）：`snapshotNow` 里增加 `v.isAttachedToWindow` 校验，并在 `msgRefOf` 后核对 `talker` 是否等于 `currentChatTalker()`，不一致则退化 UNKNOWN/SKIP。
  - C（最低成本、且对本次真机测试就有价值）：在点击日志里补 `msgId=${snap.msgId} svrId=${snap.msgSvrId} attached=${v?.isAttachedToWindow}`，用现场数据确认没有错发；再据实测决定是否上 A/B。
- 我倾向：本次先按 C 装机取证，若真机出现「慢选后发出的消息对不上」，立即按 A 修。

---

## 边界 2：快选回归（窗口内 <10s）

**结论：零回归。** 读码逐项对比：

| 项 | `pressedView()`（旧源，951-955） | `staleViewForProbe()`（新源，1961-1963） |
|---|---|---|
| 取 View | `burstDeepest?.get()` | `burstDeepest?.get()` |
| 同步 | `synchronized(pressLock)` | `synchronized(pressLock)` |
| 差异 | 多一句 `now - burstStartAt > 10_000L → null` | 无 TTL 判断 |

- 两个函数取的是**同一个 `WeakReference` 的同一个 `get()`**，且加同一把锁。对 <10s 的点击，TTL 判断恒为 false，两者返回值完全相同 → `snapshotOf(...)` 结果逐字段一致 → 快选路径零回归。✓
- 唯一行为差异恰是修复目标：>10s 时旧源返回 null、新源仍返回 View。不存在「快选时新源反而拿到不同 View」的情况。

---

## 边界 3：异常安全（null / detached / 读不到内容）

**结论：未发现点击崩溃路径。** 分层兜底如下：

1. 最外层：`onForwardClick()` 整体包在 `runCatching { … }.onFailure { LogX.e("FF forward click failed", it) }`（784-792）。即便下游抛异常，也只记日志，不会把异常抛回宿主点击回调，不会崩微信。
2. 取源：`staleViewForProbe()` 是 `synchronized(pressLock) { burstDeepest?.get() }`，`burstDeepest` 为 null 时返回 null，NULL 安全，不抛（`ForceForward.kt:1961-1963`）。
3. 快照：`snapshotOf(null)` 走 `v?.let{…}.orEmpty()` → `kind=UNKNOWN`、`text=""`、`path=""`，返回一条合法的空 Snapshot，不抛（809-828）。`extractText` 对每个 `TextView.text` 读包 `runCatching`；`msgRefOf` 调用点也包在 `runCatching` 内（811）；`msgRefOf` 内部每一步反射取值均逐个 `runCatching`（890-895）；`File(path).exists()` 同样 `runCatching`（822）。
4. detached View：`extractText` 仍能遍历其子树（不抛）；`msgRefOf` 沿 parent 链找不到 recycler/listview 时返回 null → 退化为 UNKNOWN Snapshot → `planFor` 首行判 SKIP（`ForwardLogic.kt:333`）。
5. 下游：`openTargetPicker(UNKNOWN snap)` 只记录日志并打开选人器（`ForceForward.kt:1000-1010`），`execute` 把 UNKNOWN 交给 `planFor` → SKIP → toast「未实际发送」，全程无未捕获异常（1028-1046、1129-1146）。

- 综上：null / detached / 读不到内容三种情况都**安全降级到 SKIP**，不会因本次改动新增崩溃面。

---

## 边界 4：日志语义（`stale=true` 恒真）

**结论：不影响功能，但语义有歧义，属 P3 改进项。**

- 现状：`onForwardClick()` 的日志把 `stale=true` 写成**硬编码常量**（789 行），无论 View 实际年龄是 1s 还是 11.5s 都输出 `stale=true`。
- 正面：它确实是一个「修复路径已上线」的标记——测试类 KDoc 明说「点击日志应带 stale=true 便于真机确认」。真机只要看到 `stale=true`，就能确认 `snapshotNow` 走的是 `staleViewForProbe()` 这条新链。这个价值成立。
- 风险：从字面上读，`stale=true` 表示「这次用了陈旧 View」；但快选（<10s）也恒为 true，排障者可能误判「明明是秒点，为什么日志说 stale」，进而怀疑 View 已失效或错发（与边界 1 的担心叠加，放大误读）。
- 建议（二选一，非阻断）：
  - 改为反映真实状态：`ageMs = now - burstStartAt`（在 `pressLock` 内读），日志输出 `stale=${ageMs > BURST_TTL_MS} ageMs=$ageMs`；
  - 或把标记名改为表达「取源函数」而非「View 新旧」，如 `src=staleProbe`，并补 `msgId/svrId/attached`（与边界 1 的 C 项合并一次做完）。

---

## 边界 5：单测有效性（是否自证式假验证）

**结论：3/4 用例对「本轮改动」具备真实的变更锁（含变异检测能力）；1/4 是自证式玩具，无回归保护。整体可接受，不阻断装机。**

逐用例评估（`SnapshotSourceTest.kt`）：

| 用例 | 类型 | 是否锁住生产语义 |
|---|---|---|
| `slowPickBeyondTtlStillGetsSnapshot`（32-46） | 纯逻辑复刻：自己定义局部 `pressed(now)` / `stale()` 并断言 | **否**。完全不调用生产代码，`pressed/stale` 是测试内手写的玩具函数；即使生产代码被改坏它也照样绿。作用是「把为什么要换源」写成可执行文档，不是回归测试。测试文件 KDoc 对此有自述。 |
| `snapshotNowUsesStaleSource`（51-61） | 源码文本断言：定位 `snapshotNow` 行，断言含 `snapshotOf(staleViewForProbe())` 且不含 `snapshotOf(pressedView())` | **是**。直接锁住本轮核心改动。 |
| `staleSourceStillIgnoresTtl`（64-69） | 源码文本断言：`staleViewForProbe` 函数体含 `burstDeepest?.get()`、不含 `BURST_TTL_MS` / `currentTimeMillis` | **是**。锁住「没把 TTL 偷偷加回去」。 |
| `staleSourceKdocUpdatedAndClickLogHasStaleFlag`（72-77） | 源码文本断言：KDoc 含「长按快照取源（忽略 TTL）」、不含旧「探针专用」、源码含 `stale=true` | **是**。锁住文档与日志标记同步更新。 |

**变异验证（按任务要求给出结论）**
- 静态推演（我未跑 gradle，按分工由 team-lead 执行）：
  - 若把 `snapshotNow` 改回 `snapshotOf(pressedView())` → `snapshotNowUsesStaleSource` 的第一个 `assertTrue`（55 行）必然失败 → **测试红**。✓ 核心改动被锁住。
  - 若有人把 TTL 判断塞回 `staleViewForProbe` 函数体 → `staleSourceStillIgnoresTtl` 红。✓
  - 若 KDoc / 日志回退 → `staleSourceKdocUpdatedAndClickLogHasStaleFlag` 红。✓
  - 结论：**具备针对本轮 diff 的变异检测能力**，不是「怎么改都绿」的纯自证。
- 仍需如实指出的局限：
  - 用例 2/3/4 是**文本断言而非语义测试**。它们只能抓住「这行字被改掉」，抓不住「函数行为变了但字符串没变」（例如 `msgRefOf` 内部回归），也不值得被当成行为级回归网。
  - 实现较脆弱：`first { it.trimStart().startsWith("private fun snapshotNow") }` 依赖精确签名前缀；`functionBody` 的花括号配平假设 KDoc 内无花括号（当前满足，但脆弱）。源码路径靠 `user.dir` 向上找 6 层（81-93），在 Gradle 默认 `user.dir=模块目录` 时可命中，但换工作目录会失效。
- 建议（P3，非阻断）：team-lead 在装机前实跑一次「变异验证」——临时把 `snapshotNow` 改回 `pressedView()`，确认 `snapshotNowUsesStaleSource` 变红后再改回；并明确把 `slowPickBeyondTtlStillGetsSnapshot` 视为文档性用例，不把它当作质量门。

---

## 边界 6：与其它取快照调用点的一致性 / 有无遗漏的 SKIP 路径仍走 `pressedView`

**结论：一致，无遗漏。** 全仓 `app/src` 扫描结果：

`snapshotOf(` 的全部生产调用点：
- `ForceForward.kt:795` `snapshotNow()` → `snapshotOf(staleViewForProbe())`（本轮改）✓
- `ForceForward.kt:1599` `snapReport()` → `snapshotOf(staleViewForProbe())`（本轮之前已是忽略 TTL，探针/取证用）✓ —— 与本次点击链**取源一致**，无分歧。
- `ForceForward.kt:809` 为函数定义本身。
- `openTargetPicker`（1000）与 `pick` 自检（1534）接收/构造 Snapshot，不涉及从 View 取源。

`pressedView()` 在生产中的**唯一残留调用**是 `ForceForward.kt:336`（`installNativeMenuInject` 的门禁处，用于算 `hasRef`）：
- 该处处于**菜单构建期**，紧跟长按之后，受 `LONG_PRESS_WINDOW_MS=4000ms` 门禁约束（332-341），远小于 `BURST_TTL_MS=10000ms`，TTL 不会触发 → 此处用 `pressedView()` 结果正确。
- 它**不是转发快照路径、也不是 SKIP 路径**，只决定「是否注入菜单项」，与本轮修复无关，无需改动。

因此：
- 不存在「某条转发/SKIP 路径仍用 `pressedView()` 取快照」的遗漏；
- `pressedView()` 现已无生产快照调用者（仅剩门禁用），`staleViewForProbe()` 成为点击链与探针链的统一取源，语义一致。✓

---

## 最终裁决

**可装机（进入真机测试）**。无 P0/P1 阻断项。

装机前/中需 team-lead 关注（非阻断）：
1. **P2 · 重点观察项**：慢选 >10s 后发出的消息是否就是长按那条（边界 1 的错发风险）。建议按 C 方案补 `msgId/svrId/attached` 到点击日志，现场确认；若对不上立即按 A 方案修。
2. **P3**：跑一次单测 + 变异验证（把 `snapshotNow` 改回 `pressedView()` 应让 `snapshotNowUsesStaleSource` 红），确认源码锁有效。
3. **P3**：`stale=true` 恒真建议改为条件输出或改名为 `src=staleProbe`（边界 4）。

复核纪律声明：全程未修改任何源码（仅新增本复核文档）、未调用 adb、未装包、未连接设备、未运行 gradle。

[DONE]
