# R8x B 路线装机前交叉复核（防@静音降 importance）

> 独立交叉复核，不 rubber-stamp。只读源码/git diff，不改任何文件、不跑 gradle、不碰 adb/设备/开关。
> 复核对象：`MuteChannelId.kt`、`MuteAt.kt`（git diff 相对 HEAD 共 3 行）、`MuteChannelIdTest.kt`（9 条 @Test）、`docs/_r8x_muteat失效_B_impl.md`。
> 真机听感与三闸由主会话负责，本代理未独立执行；下述变异红法为静态推演。
> 提及群聊一律用昵称「测试群聊」，不以 wxid 代替。

## 进度（骨架 → 逐点追加）

（按 6 点推进，逐条追加）
[DONE] 点1
[DONE] 点2
[DONE] 点3
[DONE] 点4
[DONE] 点5
[DONE] 点6
[DONE] ALL

---

## 点1 红线未破 — PASS

依据（行号以工作区文件为准）：

- `git diff -- wxgroup/app/src/main/java/com/lx/wxgroup/feature/MuteAt.kt` 相对 HEAD **仅 3 行变化**，且只有两个 hunk：`@@ -135,8 +135,8 @@`（:138/:139）与 `@@ -371,7 +371,7 @@`（:374）。
  - :138/:139 `private const val MUTE_CHANNEL_ID / MUTE_CHANNEL_NAME` → `private val ... get() = MuteChannelId.VALUE / NAME`（B′ 既有改动）。
  - :374 `NotificationManager.IMPORTANCE_HIGH` → `MuteChannelId.IMPORTANCE_LEVEL`（B 新增改动）。
- 明确未动：`shouldMute`(:60-85)、`notificationText`(:87-92)、`probe`(:94-126)、`install`(:186-205)、`installItemHook`(:235-264)、`installNotifyHook`(:266-279)、`installVibratorHook`(:281-295)、`onNotify`(:297-320，判定顺序 DND→GRP→AT 原样)、`rememberDump`(:328-331)、`describe`(:333-345)、`channelIdOf`(:347-350)、`mute`(:354-363)、`ensureMuteChannel` 的 null 判断(:370)与 `setSound(null,null)`(:376)/`enableVibration(false)`(:377)/`vibrationPattern=null`(:378)、`rewriteChannel`(:387-409)、`parseItem`(:413-432)、`remember`/`talkerOf`(:434-450)、`hotLog`/`status`/`mapStatus`/`ITEM_RE`(:452-471)。
- `MuteAtLogic.kt`（`groupInScope`/`isAtMe`）、`core/NotifyMute.kt`、`HookEntry.kt` 均未出现在 `git status --short` 的 modified 列表 ⇒ 未改。工作区 main/test 下仅 `MuteAt.kt` 为 M，另两个为 B′ 新增（`??`）后 B 叠加。

[DONE] 点1

---

## 点2 importance=2 语义正确 — PASS（依据 AOSP 知识，非本机源码实证）

- `MuteChannelId.kt:11` `const val IMPORTANCE_LEVEL = 2`；:8-9 注释标明 LOW=2 / HIGH=4 / MIN=1。
- AOSP `android.app.NotificationManager` 常量定义（API 24 引入通道至今未变）：`IMPORTANCE_NONE=0 / MIN=1 / LOW=2 / DEFAULT=3 / HIGH=4 / MAX=5`。故 **LOW=2 写对，HIGH=4、MIN=1 亦对，无常量笔误**。
- 本机无 Android SDK sources：`ANDROID_HOME`/`ANDROID_SDK_ROOT` 未设置，`AppData/Local/Android/Sdk/sources` 下无 `NotificationManager.java`。因此本项标 [AOSP 知识]，不冒充 [本机源码实证]。
- 语义匹配用户诉求（判据 §8「保留通知栏、只不响」）：LOW(2) 通知仍在状态栏/通知栏显示、静音、不弹 heads-up 横幅；MIN(1) 折叠/不显示状态栏图标、更隐蔽，不选；HIGH(4) 带声震+横幅，是死结根源，排除。
- 类型：`const val IMPORTANCE_LEVEL = 2` 推断为 `Int`，与 `NotificationChannel(String, String, Int)` 第三参匹配。

[DONE] 点2

---

## 点3 换 id 必要性 + 旧 id 全排除 — PASS

- `MuteChannelId.kt:6` `VALUE = "wxgroup_silent_low"` ≠ 初代 `wxgroup_silent` ≠ B′ `wxgroup_silent_v2`。
- 必要性（直接采信 team-lead 背景 + 判据 §4/§5/§7）：v2 首次创建即被系统套 lock38+默认铃声+震动、用户确认「有声音有震动」、`mCreationTimeMs=01:40:32` 证明首次创建；已 user-lock 的通道同 id 不可覆盖 ⇒ 必须换全新 id 才能让 `ensureMuteChannel()` 的 `getNotificationChannel()==null` 分支首次写入 importance=2。
- 单测：`legacyIdsDoNotMatch`(:32-35) 断言 `isMute("wxgroup_silent")==false`、`isMute("wxgroup_silent_v2")==false`；`newValueDiffersFromLegacyLockedId`(:20-22) 断言 VALUE ≠ `wxgroup_silent`；`isMuteMatchesTheExactNewId`(:25-29) 断言 VALUE == `wxgroup_silent_low` 且严格相等命中。
- `MuteChannelId.kt:15` `isMute(id) = id == VALUE`，:14 注释明禁 `contains`/`startsWith`；hook 侧 :138 与单测同源，无字符串漂移。
- NAME（:7）改为「静音通知（分组模块·低重要性）」，便于系统设置分辨新旧，不影响 id/逻辑。

[DONE] 点3

---

## 点4 根因反转论证与证据一致 — PASS（附 1 条溯源标注，非矛盾）

- B_impl.md:59「importance=4 是代码自己写的，在 `MuteAt.kt:374` 用 `IMPORTANCE_HIGH`」—— 与 git diff 原文 `-NotificationManager.IMPORTANCE_HIGH` 一致，[源码事实] 非假设。
- B_impl.md:60「setSound(null) 被系统补默认铃声、enableVibration(false) 被补 true、mUserLockedFields=38」—— 与判据 §4（mSound=系统铃声 / mVibrationEnabled=true / lock=38）、§7（mCreationTimeMs=首次创建、通道 `mAudioAttributes=null` vs Record `mAttributes` 非 null）一致，[真机实证]。
- B_impl.md:61「importance=4 死结」—— 与判据 §6（`mSoundNotificationKey`/`mVibrateNotificationKey` 指向该通知、`mIsInterruptive=true`、flags 无 `DEFAULT_SOUND/DEFAULT_VIBRATE`）一致。
- B_impl.md:62「全机通道普查 LOW/MIN 合法存在且震动几乎全关」—— 与判据 §4（全机 1324 通道 `mUserLockedFields` 分布，非全局套 38）同向，支持「LOW 可合法落地」的推断。
- **未把假设冒充实证**：B_impl.md:114-120「已知边界」明确「若 OEM 把新通道 importance 提回 4 ⇒ B 证伪」、本代理未装机/未跑 gradle/未改开关；判据 §8.2 把 `mImportance=2` 列为待真机验证的核心判据 ⇒ 方案本身是「待验证」而非「已验证」。根因定案 §2 的诚实边界（0x26 写入者未知、听感待确认）未被 B_impl.md 推翻或越界写成事实。
- **溯源标注（非证据冲突，建议 team-lead 知悉）**：B_impl.md:62 写「全机 1319 通道普查」并给出 LOW(339)/MIN(25)，而判据 §4 记录「全机 1324 通道」；docs 目录 grep 未命中 1319/339/低重要性等关键字。两数字系不同普查时点/统计项，方向一致、不构成矛盾，但 B_impl.md 引用的 1319 数据在 docs 内无落盘出处。若以 docs 为准建议统一为 1324；不影响 B 论证方向。

[DONE] 点4

---

## 点5 单测充分性 + 变异 — PASS（静态推演；真跑为主会话声明）

- 9 条 @Test 覆盖核对：
  - LOW=2：`importanceLevelIsLow`(:49-51, `assertEquals(2, LEVEL)`)
  - 排 HIGH=4：`importanceLevelIsNotHigh`(:53-56, `assertNotEquals(4, LEVEL)`)
  - 排 MIN=1：`importanceLevelIsNotMin`(:58-61, `assertNotEquals(1, LEVEL)`)
  - 严格相等：`isLowImportanceUsesStrictEquality`(:63-68, (2)=true/(4)=false/(0)=false)
  - 新 id：`isMuteMatchesTheExactNewId`(:25-29)
  - 旧 id 两条：`legacyIdsDoNotMatch`(:32-35) + `newValueDiffersFromLegacyLockedId`(:20-22)
  - null/空串：`nullAndEmptyDoNotMatch`(:38-41)
  - 子串：`suffixDoesNotMatchBySubstring`(:44-46)
- **M1**（LEVEL 2→4）静态推演：红 `importanceLevelIsLow`（assertEquals(2,4)）、`importanceLevelIsNotHigh`（assertNotEquals(4,4)）、`isLowImportanceUsesStrictEquality`（assertTrue(isLowImportance(2)) → 2==4=false）；绿 `importanceLevelIsNotMin` 与 5 条 id 用例 ⇒ **恰好 3 条红**，与 B_impl.md:88-94 及任务给定红法逐条吻合。
- **M2**（VALUE→`wxgroup_silent_v2`）静态推演：红 `isMuteMatchesTheExactNewId`（assertEquals("wxgroup_silent_low",VALUE)）、`legacyIdsDoNotMatch`（assertFalse(isMute("wxgroup_silent_v2")) → 反向命中）；绿 `newValueDiffersFromLegacyLockedId`（v2 仍 ≠ `wxgroup_silent`）、`suffixDoesNotMatchBySubstring`、null/空串、4 条 importance ⇒ **恰好 2 条红**，与 B_impl.md:103-108 及任务给定红法逐条吻合。
- 结论：能锁死 `IMPORTANCE_LEVEL` 与 `VALUE` 两类常量漂移。
- **标注（非 FAIL）**：B_impl.md:80 声称主会话已真跑 M1/M2、恢复后 1226 全绿 + assembleDebug EXIT=0；本代理受硬约束未跑 gradle，上述红法为静态推演并与声称逐条比对一致，真跑结果为主会话声明，未独立复现。

[DONE] 点5

---

## 点6 遗漏 / 副作用 — PASS（附 2 条需主会话知悉）

- 全量 grep（`wxgroup/app/src`，关键词 `wxgroup_silent`/`wxgroup_silent_v2`/`IMPORTANCE_HIGH`/`LOW`/`MIN`/`LEVEL`）残留分类：
  - **正确同步、无需再改**：`MuteChannelId.kt:6/8/9/11/13`、`MuteChannelIdTest.kt:13/21/26/28/33/34/45/50/55/60`、`MuteAt.kt:374`（已改 `IMPORTANCE_LEVEL`）、:138/:139（getter 指向 `MuteChannelId`）。
  - **已停用类残留、无害**：`core/NotifyMute.kt:50/85/474/485` 仍持旧 id `wxgroup_silent` 与 `IMPORTANCE_HIGH`。`HookEntry.kt:309-321` 明确注释「2026-10-17 停用」且 `NotifyMute.install(lpparam)`(:319-321) 被注释，现役为 MuteAt（`HookEntry.kt:328-331`）；根因定案 §2 第 27 行亦排除双写竞争 ⇒ 非现役、不产生竞争通道。
  - **需主会话知悉 ①（真实副作用，非阻断）**：`AutoDndGroup.kt:31` KDoc 仍写「IMPORTANCE_HIGH + setSound(null) + enableVibration(false)」，且 AutoDndGroup 的即时静音复用 `QuietNotificationBridge.ensureMuteChannel()`（`AutoDndGroup.kt:32-33/506`、`MuteAt.kt:313-315` 的 GRP 分支）⇒ 通道降 LOW 后，**新加群自动免打扰的即时静音也不再弹横幅**。LOW 仍保留通知栏显示、符合「不响」诉求，但与该注释的历史描述不符。建议主会话把 :31 注释同步为 LOW（或注明共用通道已降 importance），并在真机验收「测试群聊」时一并观察新群免打扰通知的横幅行为。本代理按硬约束未改该文件。
  - **需主会话知悉 ②**：点 4 的 1319/1324 口径差异（溯源标注）。
- `MuteChannelId.kt` 与 `MuteAt.kt` 同属 `com.lx.wxgroup.feature` 包 ⇒ 无需 import；`MuteAt.kt:1-19` import 列表无 `MuteChannelId`，正确。
- 类型检查：`const val IMPORTANCE_LEVEL = 2` 为 `Int`，匹配 `NotificationChannel(id, name, importance:Int)`；:138/:139 getter 返回 `String`，与常量原类型一致；所有引用（:370/:372/:374/:391/:403）均在函数体内运行时上下文，无 const-context 依赖 ⇒ 无编译风险（三闸编译由主会话负责，本代理未跑 gradle）。

[DONE] 点6

---

## 总结论：装机 **GO**

6 点全部 PASS。红线（判据链/停用路径/开关）未破；importance=2 语义正确且类型安全；换 id 必要且两个旧 id 被单测全排除；根因反转论证与判据 §4-7 真机证据无矛盾、未把假设冒充实证；9 条单测覆盖 2/4/1 + 旧 id + null + 子串，M1/M2 红法静态推演与声称逐条吻合，能锁死常量漂移。

**附带 2 条非阻断标注（供主会话知悉，不影响装机）**：① `AutoDndGroup.kt:31` 注释过时，且新群自动免打扰共用该 LOW 通道 ⇒ 即时静音不再弹横幅（仍留通知栏，符合「不响」诉求）；② B_impl.md 的 1319 vs 判据 §4 的 1324 统计口径差异、docs 内 1319 无落盘出处。

**装机前仍须主会话完成**：三闸（compileDebugUnitTestKotlin / `_junit.sh` 全量 1226 / assembleDebug）；判据 §8.2 静态验收（`mImportance=2` 为核心）、§8.3 正向听感（「测试群聊」里通知栏可见 + 不响 + 不震）、§8.4 反向复现（关开关响铃恢复）。若静态验收发现 importance 被 OEM 提回 4 ⇒ 按 §8.5 转 G′/D，B 代码留树无害。本代理未装机、未跑 gradle、未碰设备/开关。

[DONE] ALL
