# 群聊防@ 静音失效修复 B 实现记录（降 IMPORTANCE 到 LOW）

> 档位：Lite 实现子代理（r8x-b-lite），严格按 team-lead 行级规格执行。
> 根因重大反转：见台账 02:1x 条 + `docs/_r8x_muteat失效_验收判据.md` §4-7。
> 修复方案：B —— 把防@静音通道 importance 从 `IMPORTANCE_HIGH(=4)` 降到 `IMPORTANCE_LOW(=2)`，并换用全新通道 id `wxgroup_silent_low`。
> 本文件为新起 note，不覆盖 `docs/_r8x_muteat失效_B2_impl.md`（B′ 换 id 的历史记录）。

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
| 1 | `wxgroup/app/src/main/java/com/lx/wxgroup/feature/MuteChannelId.kt` | 修改 | `VALUE`→`wxgroup_silent_low`；`NAME`→`静音通知（分组模块·低重要性）`；新增 `IMPORTANCE_LEVEL=2` 与 `isLowImportance(level)`；`isMute` 严格相等不变 |
| 2 | `wxgroup/app/src/main/java/com/lx/wxgroup/feature/MuteAt.kt` | 修改（仅 1 行） | `:374` 建通道第三参 `NotificationManager.IMPORTANCE_HIGH` → `MuteChannelId.IMPORTANCE_LEVEL` |
| 3 | `wxgroup/app/src/test/java/com/lx/wxgroup/feature/MuteChannelIdTest.kt` | 修改 | 原 5 条同步新 id + 两条旧 id 都断言不命中；新增 4 条 importance 用例（共 9 条） |
| 4 | `docs/_r8x_muteat失效_B_impl.md` | 新增 | 本记录（不覆盖 B2_impl.md） |

**明确未改**（按规格）：`ensureMuteChannel` 的 null 判断、`setSound(null,null)`/`enableVibration(false)`/`vibrationPattern=null`、`rewriteChannel`、`MUTE_CHANNEL_ID`/`MUTE_CHANNEL_NAME` getter、判据链（`shouldMute`/`groupInScope`/`isAtMe`/`onNotify` :297-320/`notificationText`/`parseItem`/`talkerById`）、`core/NotifyMute.kt`、`HookEntry.kt`、任何开关。`IMPORTANCE_LEVEL` 是纯 Int 常量，`NotificationChannel(id,name,importance:Int)` 第三参直接匹配，无需 import Android。

## 行级 diff 摘要

```diff
--- a/wxgroup/app/src/main/java/com/lx/wxgroup/feature/MuteChannelId.kt
+++ b/wxgroup/app/src/main/java/com/lx/wxgroup/feature/MuteChannelId.kt
@@
-    const val VALUE = "wxgroup_silent_v2"
-    const val NAME = "静音通知（分组模块 v2）"
+    const val VALUE = "wxgroup_silent_low"
+    const val NAME = "静音通知（分组模块·低重要性）"
+    const val IMPORTANCE_LEVEL = 2
+    fun isLowImportance(level: Int): Boolean = level == IMPORTANCE_LEVEL
     fun isMute(id: String?): Boolean = id == VALUE

--- a/wxgroup/app/src/main/java/com/lx/wxgroup/feature/MuteAt.kt
+++ b/wxgroup/app/src/main/java/com/lx/wxgroup/feature/MuteAt.kt
@@ -371,7 +371,7 @@ internal object QuietNotificationBridge {
                 val channel = NotificationChannel(
                     MUTE_CHANNEL_ID,
                     MUTE_CHANNEL_NAME,
-                    NotificationManager.IMPORTANCE_HIGH,
+                    MuteChannelId.IMPORTANCE_LEVEL,
                 ).apply {
```

- 单测：原 5 条中 `isMuteMatchesTheExactNewId` 的 `wxgroup_silent_v2`→`wxgroup_silent_low`、`legacyIdDoesNotMatch` 扩为两条旧 id 都断言 false、`suffixDoesNotMatchBySubstring` 后缀同步；新增 `importanceLevelIsLow/IsNotHigh/IsNotMin`、`isLowImportanceUsesStrictEquality` 四条。

## 为什么降 importance 能绕开根因（引用真机证据）

根因重大反转（台账 02:1x 条 + `docs/_r8x_muteat失效_验收判据.md` §4-7）：防@判据链全通（`QUIET AT muted` 照打），失效在落地层——

1. **`importance=4(HIGH)` 是代码自己写的**，在 `MuteAt.kt:374` 用 `NotificationManager.IMPORTANCE_HIGH` 建通道。不是系统把 LOW 套成 HIGH。
2. **系统补参是双向的**：`setSound(null)` 被系统落库回退为默认铃声 `content://settings/system/notification_sound`、`enableVibration(false)` 被补成 `mVibrationEnabled=true`，并自动置 `mUserLockedFields=38`（含 SOUND 位）。`mAudioAttributes=null`（通道对象）+ NotificationRecord `mAttributes` 非 null 的矛盾证明：代码传了 null，是系统在创建瞬间覆盖。
3. **死结**：HIGH 天然带声震行为 + 横幅；即便通道声震被压，`importance=4` 的横幅/响铃路径仍在（`验收判据` §6：`mSoundNotificationKey`/`mVibrateNotificationKey` 都指向该通知、`mIsInterruptive=true`）。
4. **真机全机 1319 通道普查证据**：ColorOS 允许 LOW（339 个）/ MIN（25 个）通道**合法存在**，且这些低重要性通道震动几乎全关 ⇒ **降 importance 到 LOW 可绕开「HIGH+有声」的死结**，让系统根本不走响铃/横幅路径。本轮按用户诉求选 LOW（保留通知栏），不取 MIN。

## 为什么用 LOW 不用 MIN

用户诉求明确：**保留通知栏、只是不响**。
- `IMPORTANCE_LOW(=2)`：通知仍在状态栏/通知栏显示、静音、不弹横幅——正好符合。
- `IMPORTANCE_MIN(=1)`：通知更隐蔽（折叠、无声、不显示状态栏图标），会牺牲「通知仍在栏」这一可感知性，不符合用户诉求（§8 用户选「保留通知栏、只不响」）。
- 因此常量取 2，禁止写死 4 或 1；单测用 3 条断言把 2/4/1 锁死。

## 为什么必须换 id

- 旧 id `wxgroup_silent`（初代）与 `wxgroup_silent_v2`（B′）都已以 `IMPORTANCE_HIGH` 在真机落地，并被系统写入 user-lock（`mUserLockedFields=38/0x26`）；按 Android 规则**已存在通道的 importance 不可被 app 覆盖**，同 id 再 `createNotificationChannel` 只会保持旧 HIGH 固化，降 importance 无效。
- 新 id `wxgroup_silent_low` 从未创建，`ensureMuteChannel()` 的 `getNotificationChannel(ID)==null` 分支必然首次创建，`importance=2` 才能真正写进去。
- 通道名改为「静音通知（分组模块·低重要性）」，便于用户在系统设置里一眼分辨新旧通道。
- 两个旧 id 在单测里都断言 `isMute=false`，防止 id 漂移回退到被 lock 的旧通道。

## 变异验证表（预期，主会话真跑）

> ✅ **主会话已真跑（非推演）**：三闸全绿后，逐组备份→改源码→编译→`_junit.sh` 全量→确认红→cp 恢复→复验。M1/M2 红法与下表推演**逐条吻合**，恢复后 1226 全绿 + assembleDebug EXIT=0。变异有语义。

### M1：把 `IMPORTANCE_LEVEL` 改成 `4`（HIGH）

改法：`MuteChannelId.IMPORTANCE_LEVEL` `2` → `4`。

| 用例 | 原期望 | 变异后 |
|---|---|---|
| importanceLevelIsLow (`==2`) | 绿 | **红** |
| importanceLevelIsNotHigh (`!=4`) | 绿 | **红** |
| isLowImportanceUsesStrictEquality (`isLowImportance(2)==true`) | 绿 | **红**（2 不再等于新 LEVEL） |
| importanceLevelIsNotMin (`!=1`) | 绿 | 绿 |
| id 相关 5 条 | 绿 | 绿 |

结论：重要性相关用例（1/2/isLowImportance）必须红，符合规格。恢复 `IMPORTANCE_LEVEL=2` 后全绿。

### M2：把 `VALUE` 改回 `wxgroup_silent_v2`

改法：`MuteChannelId.VALUE` `"wxgroup_silent_low"` → `"wxgroup_silent_v2"`。

| 用例 | 原期望 | 变异后 |
|---|---|---|
| newValueDiffersFromLegacyLockedId | 绿 | 绿（仍 ≠ `wxgroup_silent`） |
| isMuteMatchesTheExactNewId (`VALUE=="wxgroup_silent_low"`) | 绿 | **红** |
| legacyIdsDoNotMatch (`isMute("wxgroup_silent_v2")==false`) | 绿 | **红**（旧 v2 反向命中） |
| suffixDoesNotMatchBySubstring | 绿 | 绿 |
| importance 相关 4 条 | 绿 | 绿 |

结论：VALUE/旧 id 相关用例必须红，符合规格。恢复 `VALUE="wxgroup_silent_low"` 后全绿。

### 恢复确认要求

两组变异后必须把 `MuteChannelId.kt` 恢复为 `VALUE=wxgroup_silent_low`、`NAME=静音通知（分组模块·低重要性）`、`IMPORTANCE_LEVEL=2`，并复核全量单测全绿。

## 已知边界

- 用户若日后在系统设置里手改新通道 `wxgroup_silent_low` 的 importance/声音，仍可能失效——用户行为无法根治（同 B′ 命门）。
- 旧通道 `wxgroup_silent`、`wxgroup_silent_v2` 残留系统设置里**不动**：不删、不覆盖、不纠正，避免触碰用户既有设置；只是不再写入。
- 若 OEM 把新通道 importance 提回 4 ⇒ B 证伪（判据 §8.5），转 G′/D，不再换 id 或改 importance；B 代码留树无害（LOW 等效降级）。
- 本代理未跑 gradle / 未装机 / 未跑场景 / 未改开关；三闸、真跑变异、装包前复核、真机验收均由主会话统一执行。
- 提及群聊一律用昵称「测试群聊」，不以 wxid 代替。

## 真机验收步骤（主会话装包后执行，本代理只描述不执行）

1. **装包**：主会话统一编译安装（本代理未装机）。
2. **静态判据（dumpsys）**：查新通道 `wxgroup_silent_low`，须 `mImportance=2`、`mSound=null`、`mVibrationEnabled=false`、`mUserLockedFields` 不含 SOUND 位(0x20)；NotificationRecord 须 `channel=wxgroup_silent_low`、`importance=2`。任一不符 ⇒ 停，先查 importance 是否被 OEM 提回 4。
3. **正向听感**：在昵称「测试群聊」发 **@全体成员**，用户确认三条同时成立：**通知栏能看到 + 不响铃 + 不震动**。
4. **反向复现（可逆性）**：`kvset chat.mute_at_enabled=false` 回读确认 → 同群再发 @全体成员 → 确认**响铃恢复**；随后 `kvset chat.mute_at_enabled=true` 复位回读。`AT muted`/`muted` 计数器一律不作判据。

[DONE] ALL
