# 群聊防@ 静音失效修复 B′ 实现记录

> 档位：Lite 实现子代理（r8x-lite-impl），严格按 team-lead 行级规格执行。
> 根因定案：`docs/_r8x_muteat失效_根因定案.md`（防@判据全通，但静音通道 `wxgroup_silent` 被用户锁定 mUserLockedFields=0x26、SOUND 位在内，app 覆盖无效）。
> 修复方案：B′ —— 换用全新静音通道 id，绕开被 user-locked 的旧通道。

## 进度

（按改动推进，逐条追加）
[DONE] 改动1
[DONE] 改动2
[DONE] 改动3
[DONE] 改动4
[DONE] 改动5

## 改动文件清单

| # | 文件 | 动作 | 说明 |
|---|---|---|---|
| 1 | `wxgroup/app/src/main/java/com/lx/wxgroup/feature/MuteChannelId.kt` | 新增 | 纯 object、零 Android/Xposed import；`VALUE`/`NAME`/`isMute` 单一真源 |
| 2 | `wxgroup/app/src/main/java/com/lx/wxgroup/feature/MuteAt.kt` | 修改（仅 2 行） | `:138`/`:139` 两个 `private const val` 改为指向 `MuteChannelId` 的 `get()` 属性 |
| 3 | `wxgroup/app/src/test/java/com/lx/wxgroup/feature/MuteChannelIdTest.kt` | 新增 | 5 条 JVM @Test，覆盖新 id 命中、旧 id 不命中、null/空串、防子串 |
| 4 | `docs/_r8x_muteat失效_B2_impl.md` | 新增 | 本记录（含变异验证与验收步骤） |

**明确未改**（按规格）：`core/NotifyMute.kt`、`HookEntry.kt`、`shouldMute`/`groupInScope`/`isAtMe`/`onNotify`(:297-320)/`notificationText`/`parseItem`/`talkerById`/判定顺序、任何开关。`core/NotifyMute.kt:85` 仍持旧 id，但按根因定案该安装点已在 `HookEntry.kt:319-321` 注释停用，非现役竞争。

## 行级 diff 摘要

```diff
--- a/wxgroup/app/src/main/java/com/lx/wxgroup/feature/MuteAt.kt
+++ b/wxgroup/app/src/main/java/com/lx/wxgroup/feature/MuteAt.kt
@@ -136,8 +136,8 @@ internal object QuietNotificationBridge {
     private const val ITEM_CLASS = "com.tencent.mm.booter.notification.NotificationItem"
-    private const val MUTE_CHANNEL_ID = "wxgroup_silent"
-    private const val MUTE_CHANNEL_NAME = "静音通知（分组模块）"
+    private val MUTE_CHANNEL_ID get() = MuteChannelId.VALUE
+    private val MUTE_CHANNEL_NAME get() = MuteChannelId.NAME
```

- 引用点 `:370`、`:372`、`:373`、`:391`、`:403` **一行未动**：`get()` 属性在 `ensureMuteChannel()`/`rewriteChannel()` 内照常可用（这些引用均在函数体内，无 const-context 依赖，故 `const val` → `val get()` 可安全替换）。
- 实际写入的通道 id 来自 `MuteChannelId.VALUE = "wxgroup_silent_v2"`，通道名来自 `MuteChannelId.NAME = "静音通知（分组模块 v2）"`。
- `MuteChannelId.kt`：新增文件，全文见源码；`isMute` 用严格相等 `id == VALUE`，禁止 `contains`/`startsWith`。
- `MuteChannelIdTest.kt`：新增 5 条 @Test（详见「变异验证结果」表）。

## 为什么新 id 能绕开 locked

1. AOSP `NotificationManagerService` 的「用户锁定字段」（`mUserLockedFields`）是按**通道 id**存储的。旧通道 `wxgroup_silent` 现场实测 `mUserLockedFields=0x26`（SOUND + IMPORTANCE + VISIBILITY 位在内），因此 app 侧 `setSound(null)` 在**已存在**的该通道上被系统忽略。
2. 新 id `wxgroup_silent_v2` 是一个从未被创建/被用户改过的通道，首次创建时不存在 user lock。`ensureMuteChannel()` 的分支条件是 `getNotificationChannel(id) == null`，换新 id 后首次 @ 必然走创建分支，`IMPORTANCE_HIGH + setSound(null,null) + enableVibration(false) + vibrationPattern=null` 在首次创建时真正落地。
3. `IMPORTANCE_HIGH` 保持不变 ⇒ 通知横幅/角标照常，只抑制声音与震动（这正是 B′ 优于 B「换 IMPORTANCE_MIN」之处——后者会让横幅消失）。
4. 判据链（`shouldMute`/作用域/@识别/命中顺序）一行未动，仍按根因定案「判据全通」，修复只作用于落地层。

## 变异验证结果

> 子代理按硬约束未跑 gradle（只做静态推演）；**主会话已在三闸后真跑两组长变异验证**（改源码 → compileDebugUnitTestKotlin → `_junit.sh` 全量 → 确认红 → 恢复），下表「变异后实际」列已由主会话用真跑结果回填并与静态推演逐条比对一致。两组皆先备份原文件、验证后 `cp` 恢复，最终 `VALUE=wxgroup_silent_v2`、`isMute=id==VALUE` 复核无误、全量 1222 tests 全绿、assembleDebug 通过。

### M1：把 `isMute` 改成 `id?.contains(VALUE) == true`

改法（作用于 `MuteChannelId.isMute`）：`id == VALUE` → `id?.contains(VALUE) == true`。

| 用例 | 期望 | 变异后实际 | 说明 |
|---|---|---|---|
| 1 newValueDiffersFromLegacyLockedId | 绿 | 绿 | VALUE 未变，仍 ≠ 旧 id |
| 2 isMuteMatchesTheExactNewId | 绿 | 绿 | `VALUE.contains(VALUE)`、`"wxgroup_silent_v2".contains(VALUE)` 均为 true |
| 3 legacyIdDoesNotMatch | 绿 | 绿 | `"wxgroup_silent".contains("wxgroup_silent_v2")` = false |
| 4 nullAndEmptyDoNotMatch | 绿 | 绿 | `null?.contains` → null；`""` 不含 VALUE |
| 5 suffixDoesNotMatchBySubstring | 绿 | **红** | `"wxgroup_silent_v2_extra".contains("wxgroup_silent_v2")` = true，误命中 |

结论：M1 有语义，**仅第 5 条变红**，符合规格预期（子串误命中被锁死）。

### M2：把 `VALUE` 改回旧 id `"wxgroup_silent"`

改法（作用于 `MuteChannelId.VALUE`）：`"wxgroup_silent_v2"` → `"wxgroup_silent"`。

| 用例 | 期望 | 变异后实际 | 说明 |
|---|---|---|---|
| 1 newValueDiffersFromLegacyLockedId | 绿 | **红** | `assertNotEquals("wxgroup_silent", VALUE)` 失败 |
| 2 isMuteMatchesTheExactNewId | 绿 | **红** | `assertEquals("wxgroup_silent_v2", VALUE)` 失败；`isMute("wxgroup_silent_v2")` 亦变 false |
| 3 legacyIdDoesNotMatch | 绿 | **红**（附带） | `isMute("wxgroup_silent")` 变 true，旧 id 反而命中 |
| 4 nullAndEmptyDoNotMatch | 绿 | 绿 | null/空串仍不命中 |
| 5 suffixDoesNotMatchBySubstring | 绿 | 绿 | `"wxgroup_silent_v2_extra" == "wxgroup_silent"` = false |

结论：M2 有语义，**第 1/2 条必须红**（已满足），第 3 条附带变红（旧 id 反向命中，进一步佐证换 id 生效）。

### 恢复确认

两组变异均已把 `MuteChannelId.kt` 恢复为原状：`VALUE = "wxgroup_silent_v2"`、`isMute = id == VALUE`。
最终文件逐行复核见「行级 diff 摘要」。

## 已知边界

- 旧通道 `wxgroup_silent` 会**残留在系统设置里，不动它**：不删除、不覆盖、不纠正，避免触碰用户既有系统设置。它只是不再被本功能写入。
- 用户若日后在系统设置里手改新通道 `wxgroup_silent_v2` 的声音/重要性，会再次复现失效——这是用户行为，无法根治（根因定案 §4 B′ 命门已登记）。
- `core/NotifyMute.kt:85` 仍保留旧 id；按根因定案其安装点已在 `HookEntry.kt:319-321` 注释停用，非现役竞争。本次按规格未改这两个文件。
- `isMute` 目前仅供 JVM 单测与后续做严格判据复用；hook 侧写 id 通过 `MuteChannelId.VALUE` 属性，二者同源，不会字符串漂移。
- 本代理**未跑 gradle / 未装机 / 未跑场景 / 未改任何开关**；变异验证由主会话真跑坐实（见上节），三闸与真机由主会话统一执行。
- ⚠️ 主会话亲核裁定：`isMute()` 生产侧未被调用（hook 侧只用 `VALUE`/`NAME` 写通道），故 M1/M2 变异的红只发生在单测层、不影响生产行为；曾考虑「反射回读后调 isMute 校验」使变异有生产语义，但那是给已证全通的静音落地链加新变量、违反「不动判据链」红线，**已否决**。现行设计下 `isMute` 的契约是「严格相等判定」，单测锁死常量不漂移即达成目的，接受此边界。
- 提及测试群聊一律用昵称「测试群聊」，不以 wxid 代替。

## 真机验收步骤

> 以下步骤由主会话在装包后执行，本代理只描述、不执行。

1. **装包**：主会话统一编译安装（本代理未装机）。
2. **通道字段核验**：`dumpsys notification` 查新通道 `wxgroup_silent_v2`，须同时满足 `mSound=null`、`mVibrationEnabled=false`、`mUserLockedFields` **无 SOUND 位**。这是「首次创建落地成功」的硬证据。
3. **正向听感**：在昵称「测试群聊」里发一条 **@全体成员**。三项须**同时**成立才算静音成功：①用户确认未听到铃声/震动；②通知仍在通知栏（静音不是吞通知）；③角标/横幅照常（IMPORTANCE_HIGH 保留）。
4. **反向复现（可逆性）**：`kvset chat.mute_at_enabled=false` → 回读确为 false → 在同一个昵称「测试群聊」再发 @全体成员 → 用户确认**响铃/震动恢复**。证明静音确由本功能落地、可逆、无持久副作用。
5. **判据红线**：`matched`/`muted`/`AT muted` 等内部计数器一律不作最终判据（本次教训：`muted=2` 却是假象）；最终判据必须含「通道字段 + 用户听感 + 通知仍在栏」。
6. **收尾**：验收后把开关恢复（`kvset chat.mute_at_enabled=true`），并回读确认。

[DONE] ALL
