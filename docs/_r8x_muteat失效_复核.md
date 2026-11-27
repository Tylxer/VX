# 群聊防@ B′ 修复 · 装机前交叉复核（Lite 复核子代理 r8x-review-lite）

> 性质：§四⑥ 硬闸门。独立核查，不 rubber-stamp。只读源码 / 文档；未改任何文件、未跑 gradle、未碰 adb 与设备与开关。
> 复核对象：`MuteAt.kt` 2 行改动 + 新增 `MuteChannelId.kt` / `MuteChannelIdTest.kt` + `docs/_r8x_muteat失效_B2_impl.md`。
> 背景：防@判据全通（`AT muted` 照打），但旧静音通道被用户锁定（mUserLockedFields=0x26 含 SOUND 位），app 覆盖无效 ⇒ 换新 id 绕开 locked；用户已确认响铃现象。
> 群聊指代：昵称「测试群聊」。

## 骨架与进度

- [x] 点1 红线未破
- [x] 点2 引用点安全
- [x] 点3 新 id 生效
- [x] 点4 绕开 locked 原理
- [x] 点5 单测充分性 + 变异
- [x] 点6 遗漏 / 副作用
- [x] 总结论

---

## 点1 · 红线未破

**结论：PASS**

依据：

- `git diff --name-only` 源码侧**只有** `wxgroup/app/src/main/java/com/lx/wxgroup/feature/MuteAt.kt` 一个文件（其余改动为 docs：`docs/舆情状态.md`、`docs/派发审计台账.md`）；新增文件为 `MuteChannelId.kt`、`MuteChannelIdTest.kt` 与 5 份 r8x 文档。
- `git diff -- wxgroup/app/src/main/java/com/lx/wxgroup/feature/MuteAt.kt` 全文仅一个 hunk `@@ -135,8 +135,8 @@`，**恰好 2 行删 / 2 行加**，即 `:138`、`:139`。
- 逐项核对未被触碰的敏感区（行号以当前文件为准）：
  - `shouldMute` `:60-85`（开关 → 作用域 → 正文 → isAtMe → matched）：未动；
  - `MuteAtLogic.groupInScope` / `isAtMe` 调用形态、判定顺序 `:64-81`：未动；
  - `onNotify` `:297-320`（DND → GRP → AT 三分支顺序）：未动；
  - `notificationText` `:87-92`、`parseItem` `:413-432`、`talkerById` `:147-148` / `:434-450`：未动；
  - `rewriteChannel` `:387-409`、`ensureMuteChannel` `:365-385`、`mute` `:354-363`：未动。
- `wxgroup/app/src/main/java/com/lx/wxgroup/core/NotifyMute.kt` 与 `HookEntry.kt` **均不在 `git diff --name-only` 中**（含暂存区为空），未改。

[DONE] 点1

## 点2 · 引用点安全（const val → val get()）

**结论：PASS**

`MUTE_CHANNEL_ID` / `MUTE_CHANNEL_NAME` 由 `const val` 改为 `private val ... get()` 后，全量 grep 找到的引用点逐一判断如下（均在函数体内，无 const-context 依赖）：

| 行号 | 引用形态 | 所在函数 | const-context？ | 判断 |
|---|---|---|---|---|
| `:138` | 定义 `get() = MuteChannelId.VALUE` | — | 否 | 只读属性，编译期无需常量折叠 |
| `:139` | 定义 `get() = MuteChannelId.NAME` | — | 否 | 同上 |
| `:370` | `manager.getNotificationChannel(MUTE_CHANNEL_ID)` | `ensureMuteChannel` | 否（普通实参） | 可用 |
| `:372` | `NotificationChannel(MUTE_CHANNEL_ID, ...)` 构造器第 1 参 | `ensureMuteChannel` | 否（普通实参） | 可用 |
| `:373` | `NotificationChannel(..., MUTE_CHANNEL_NAME, ...)` 构造器第 2 参 | `ensureMuteChannel` | 否（普通实参） | 可用 |
| `:391` | `field.set(notification, MUTE_CHANNEL_ID)`（mChannelId 主路径） | `rewriteChannel` | 否（普通实参） | 可用 |
| `:403` | `field.set(notification, MUTE_CHANNEL_ID)`（字段遍历兜底） | `rewriteChannel` | 否（普通实参） | 可用 |

- 无注解参数、无 `const val X = MUTE_CHANNEL_ID + ...` 之类编译期常量表达式、无 `when` 常量分支依赖。
- 目标值为 `MuteChannelId` 的 `const val` String（编译期内联），每次 getter 调用返回同一稳定字面量，无行为差异；一个 `get()` 属性在 `internal object` 内被同 object 成员访问完全合法。
- 反向核对：`core/NotifyMute.kt:85/86/483/485/508/518` 是**另一套独立常量**，不在本次改动范围，不受影响。

[DONE] 点2

## 点3 · 新 id 生效

**结论：PASS**

- `MuteAt.kt:138` → `MuteChannelId.VALUE`；`MuteAt.kt:139` → `MuteChannelId.NAME`。
- `MuteChannelId.kt:6` `const val VALUE = "wxgroup_silent_v2"`，`:7` `const val NAME = "静音通知（分组模块 v2）"`。
- 旧值 `wxgroup_silent` / `静音通知（分组模块）` 在 diff 中被删除，`VALUE ≠ "wxgroup_silent"`（字符串层面为严格不等，且有 `MuteChannelIdTest:20` 断言）。
- 实际写入链闭环：`:371-373` 建通道用新 id/名 → `:376` `setSound(null,null)` → `:391`/`:403` 把 `mChannelId` 改为**同一个** `MUTE_CHANNEL_ID` getter 的返回值。即「建通道的 id」与「通知落点的 id」同源，不存在一个走新、一个走旧的错配。
- `MuteChannelId.kt:9` `fun isMute(id: String?): Boolean = id == VALUE` —— Kotlin `==` 为严格值相等（非空安全等值），**不是** `contains` / `startsWith` / `in`；注释 `:8` 也明确禁止子串。

[DONE] 点3

## 点4 · 绕开 locked 的原理成立

**结论：PASS（附带必须真机核验的残留风险，非静态可证伪项）**

对照 `ensureMuteChannel()` `:365-385`：

- `:366` 仅 API < O 返回 false（无关）；
- `:370` 分支条件为 `manager.getNotificationChannel(MUTE_CHANNEL_ID) == null`；换成从未创建过的新 id `wxgroup_silent_v2` 后，首次命中 @ 时该 id 无通道 ⇒ 条件为真 ⇒ **确实进入创建路径**；
- `:371-380` 创建时一次性设置 `IMPORTANCE_HIGH` + `setSound(null,null)` + `enableVibration(false)` + `vibrationPattern=null`；`:383` 返回 true。因新 id 首次创建不存在 `mUserLockedFields`，系统不会忽略这些参数 —— B′ 因果成立。
- `IMPORTANCE_HIGH` 未变 ⇒ 横幅/角标/通知栏行为不变，只抑制声震；优于 IMPORTANCE_MIN 方案。

已排查的反例：

1. **旧通道残留**：旧 `wxgroup_silent` 仍带 user lock，但代码不再读/写它；不影响新通道。
2. **same-id 复活 tombstone**：Android 对用户删除过的通道会保留「已删除 + 用户字段」记录，重建同 id 会复活锁。**不适用** —— 新 id 是本次首次引入，从未被创建/删除/手改。
3. **并发创建**：`mute()` 可能并发调用，但 `createNotificationChannel` 幂等，不存在部分创建导致半锁状态。
4. **OEM 自动重建/重置通道（ColorOS 等）**：静态无法排除，根因定案 §4 B′ 命门已登记；由装包后 `dumpsys notification --noredact | grep wxgroup_silent_v2` 核验 `mSound=null` / `mVibrationEnabled=false` / `mUserLockedFields` 无 SOUND 位(0x20) 兜底。若不满足即 B′ 失败，按失败回退处理，**不得放行到用户听感验收**。
5. **用户日后手改新通道**：会复现失效，属用户行为，已登记边界，不构成装机阻断。

[DONE] 点4

## 点5 · 单测充分性 + 变异

**结论：PASS（附一个小缺口，不阻断装机）**

`MuteChannelIdTest.kt` 5 条 @Test 覆盖矩阵：

| 用例 | 旧 id 不命中 | 新 id 命中 | null | 空串 | 子串 |
|---|---|---|---|---|---|
| `:19` newValueDiffersFromLegacyLockedId（新 ≠ 旧） | ✔(断言) | — | — | — | — |
| `:24` isMuteMatchesTheExactNewId | — | ✔ | — | — | — |
| `:31` legacyIdDoesNotMatch | ✔ | — | — | — | — |
| `:36` nullAndEmptyDoNotMatch | — | — | ✔ | ✔ | — |
| `:42` suffixDoesNotMatchBySubstring | — | — | — | — | ✔ |

要求的「旧 id 不命中 / 新 id 命中 / null / 空 / 子串」**五类全覆盖**。测试为纯 JVM（零 Android/Xposed 依赖），与 `src/test` 同包同 JUnit4 风格一致（既有 `PinFoldLogicTest:6` 已用 `assertNotEquals`，依赖具备）。

变异复核（按 note 记录 + 静态推演逐条对账；本代理硬约束未跑 gradle，真跑结果取自 `docs/_r8x_muteat失效_B2_impl.md` 记载的主会话实测）：

- **M1**（`isMute` 改 `id?.contains(VALUE) == true`）：`:43` `"wxgroup_silent_v2_extra".contains(VALUE)` = true ⇒ `assertFalse` 失败；其余 4 条仍绿（null 因 `== true` 得 false、旧 id 不含新 id、`VALUE.contains(VALUE)` 为 true、断言 1 不涉及）。⇒ **恰好 1 条红**，能锁死「子串误命中」语义。
- **M2**（`VALUE` 改回 `"wxgroup_silent"`）：`:20` `assertNotEquals` 红；`:25` `assertEquals("wxgroup_silent_v2", ...)` 红（且 `isMute("wxgroup_silent_v2")` 变 false）；`:32` `isMute("wxgroup_silent")` 变 true ⇒ 红；`:37-38` null/空仍不命中 → 绿；`:43` 后缀串不相等 → 绿。⇒ **恰好 3 条红**，能锁死「常量漂移回被锁旧 id」。
- 两组变异均有语义、非等价变异，红数与 note 记录一致；原文件已恢复 `VALUE="wxgroup_silent_v2"`、`isMute=id==VALUE`（本次读取源码确认）。

缺口（诚实登记，不阻断）：

- `NAME` 无任何断言；若 `NAME` 被改回旧显示名，5 条测试不会红。但 `NAME` 仅通道显示文案、不参与 locked 绕开或通知落点，属可接受边界。
- `isMute()` 生产侧未被调用（hook 侧只用 `VALUE`/`NAME` 写通道）；M1/M2 的红只在单测层，不直接改变生产行为。其作用是把「严格相等 + 新 id」的契约钉在单测里防将来漂移；note §96 已由主会话裁定接受该边界，且按红线不宜再加生产侧校验。

[DONE] 点5

## 点6 · 遗漏 / 副作用

**结论：PASS（一处 dead code 已核实为停用，无需同步改）**

- 全量 grep `wxgroup_silent`（`wxgroup/app/src/main` + `wxgroup/app/src/test`）：
  - `core/NotifyMute.kt:85` 硬编码旧 id（`:86` 旧 name，`:483/485/508/518` 使用）—— 按规格未改。
  - `MuteChannelId.kt:3` 仅注释提及旧 id；`MuteChannelIdTest.kt:13/20/32` 仅断言用旧 id。
  - **不存在其它需要同步修改却漏改的硬编码**。
- 独立核实「`NotifyMute.kt` 非现役竞争」：`HookEntry.kt:319-321` 处 `NotifyMute.install(lpparam)` **整段被注释**（注释说明 2026-10-17 停用，静音域统一到 `QuietNotificationBridge`），紧随其后的是 `MuteAt.install(lpparam)` 正常启用。⇒ 旧 id 那套代码不会在运行时写通道，不会覆盖新链路的 `mChannelId`。残留风险：若将来注回这两行，会双写旧 locked 通道；本次按规格保留，属已知边界。
- 包与引用：`MuteChannelId.kt:1` 与 `MuteAt.kt:1` 同为 `package com.lx.wxgroup.feature`；测试 `:1` 也同包 ⇒ 直接按类名引用，**无需 import**，不存在包错配/找不到符号问题。
- `MuteChannelId.kt` 仅 Kotlin 标准库 `object` + `const val` + `fun`，无 Android / Xposed / 反射 import ⇒ 无新增编译依赖风险；文件置于 `src/main/java/com/lx/wxgroup/feature/`，路径与包一致。
- 未触碰任何开关 / KV / 名单 / 判据；无持久化副作用。旧通道在系统设置中残留属预期，不动用户设置。

[DONE] 点6

---

## 总结论

**装机 GO。**

六点全部 PASS。改动是「换 id 绕开 user-locked 通道」的最小外科手术：判据链与全部敏感路径零改动，2 行替换 + 单一真源 object + 5 条 JVM 回归，引用点无 const-context 风险，首次创建必然走静音创建分支，变异验证能锁死常量漂移与子串误命中，全量 grep 无漏改硬编码，包结构可直接编译。

放行附带的**强制装机后闸门**（不满足即 NO-GO 回退，不得仅凭 `AT muted` 判定）：

1. `dumpsys notification --noredact | grep wxgroup_silent_v2` 必须见 `mSound=null`、`mVibrationEnabled=false`、`mImportance=4`、`mUserLockedFields` 无 SOUND 位(0x20)。
2. 昵称「测试群聊」发 @全体成员：用户确认未响未震、通知仍在栏、横幅/角标照常；日志仍有 `QUIET AT muted talker=测试群聊`（判据链未被动）。
3. 反向：`kvset chat.mute_at_enabled false` 回读 false → 同群再发 → 响铃/震动恢复；随后恢复开关并回读 true。
4. 计数器 `matched/muted/bound` 一律不作最终判据。

已知且被接受的边界（不阻断本次装机）：`NAME` 无单测断言；`isMute` 仅测试侧使用；`core/NotifyMute.kt` 旧 id 为停用代码的死字面量；OEM 若自动重建/重置通道或用户日后手改新通道，会复现失效，需按失败回退（不再换第三个 id，改走 B 或上报）。

[DONE] ALL
