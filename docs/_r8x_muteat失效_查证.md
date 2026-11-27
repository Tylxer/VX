# 群聊防@「之前生效现在不静音」纯事实核查（只读取证·未定案）

> 性质：三个客观问题的查证记录。不改动任何源码、开关、配置；不跑 gradle；不碰 adb。
> 标注约定：`[源码事实]` = 本机源码字面可查；`[AOSP 知识]` = AOSP 常量/系统行为，本机未找到对应服务源码逐行引用；`[假设]` = 未证实推断。
> 背景见 `docs/_r8x_muteat失效_只读取证.md`。涉及群聊一律用昵称（测试群聊）。

## 子节 1：`mUserLockedFields=38` 的位含义

### 1.1 位定义表
来源：`[源码事实]` 本机 `C:\Users\lx\AppData\Local\Android\Sdk\sources\android-35\android\app\NotificationChannel.java:182-227`。
本机 `sources` 只有 android-33 / android-35，无 android-34；`platforms\android-34\data\` 下也没有 `NotificationChannel.java`。故采用 android-35 sources 的常量定义（这些常量自 Android 8.0 引入以来位值稳定）。

| 常量 | 值（十六进制） | bit | 含义 |
|---|---|---|---|
| `USER_LOCKED_PRIORITY` | `0x00000001` | bit0 | 用户锁定优先级 |
| `USER_LOCKED_VISIBILITY` | `0x00000002` | bit1 | 用户锁定可见性 |
| `USER_LOCKED_IMPORTANCE` | `0x00000004` | bit2 | 用户锁定重要性（决定响铃/静音等级） |
| `USER_LOCKED_LIGHTS` | `0x00000008` | bit3 | 用户锁定呼吸灯 |
| `USER_LOCKED_VIBRATION` | `0x00000010` | bit4 | 用户锁定震动 |
| `USER_LOCKED_SOUND` | `0x00000020` | bit5 | 用户锁定声音 |
| （无对应常量） | `0x00000040` | bit6 | 本机源码中未定义，忽略 |
| `USER_LOCKED_SHOW_BADGE` | `0x00000080` | bit7 | 用户锁定角标 |
| `USER_LOCKED_ALLOW_BUBBLE` | `0x00000100` | bit8 | 用户锁定气泡 |

`[源码事实]` 同文件 `:218-227` 的 `LOCKABLE_FIELDS` 数组逐项列出上述 8 个可锁定位（不含 bit6）。

### 1.2 `38` / `0x26` 锁了哪几个字段
`38 = 0x26 = 0x20 + 0x04 + 0x02 = bit5 + bit2 + bit1`，即：

- `USER_LOCKED_SOUND`（`0x20`, bit5）—— **声音被用户锁定**
- `USER_LOCKED_IMPORTANCE`（`0x04`, bit2）—— **重要性被用户锁定**
- `USER_LOCKED_VISIBILITY`（`0x02`, bit1）—— **可见性被用户锁定**

未锁：PRIORITY(bit0)、LIGHTS(bit3)、VIBRATION(bit4)、SHOW_BADGE(bit7)、ALLOW_BUBBLE(bit8)。

`[源码事实]` 同文件 `:1064-1074` 给出判定方法，可反推：
```
hasUserSetImportance() { return (mUserLockedFields & USER_LOCKED_IMPORTANCE) != 0; }  // 0x26 → true
hasUserSetSound()      { return (mUserLockedFields & USER_LOCKED_SOUND) != 0; }       // 0x26 → true
```
⇒ 对 `wxgroup_silent` 通道，`hasUserSetSound()` 与 `hasUserSetImportance()` 均为 `true`。

补充事实：dumpsys 显示 `mVibrationEnabled=true`，但 VIBRATION 位（bit4=`0x10`）**未**锁定 ⇒ 震动开启不是「用户锁定」的结果，而是通道当前的字段值。

### 1.3 locked 字段 app 后续 `createNotificationChannel` 覆盖时系统是否忽略
`[AOSP 知识]` AOSP `NotificationManagerService` 对已存在的通道执行 `createNotificationChannel` 时，会保留用户锁定的字段：`mUserLockedFields` 中被置位的字段（此处为声音、重要性、可见性）不会被 app 传入的新值覆盖。即 app 侧再次 `setSound(null,null)` / `enableVibration(false)` / 调 `setImportance()`，对已 locked 的字段**不生效**。
本机 `sources` 目录中**不含** `NotificationManagerService.java`（已检索确认），此条无法逐行引用本机源码 ⇒ 标 `[AOSP 知识]`，不冒充 `[源码事实]`。

### 1.4 [查证事实] 与 [需真机验证] 的边界
- `[查证事实]`：位定义表、`0x26` = SOUND+IMPORTANCE+VISIBILITY 三项、`hasUserSet*()` 判定方法 —— 本机源码可查，结论确定且唯一。
- `[AOSP 知识]`：locked 字段 app 覆盖被系统忽略 —— AOSP 既定行为，本机未找到服务源码。
- `[需真机验证]` / 查不到：`mUserLockedFields=0x26` 是**何时、由谁**写入的（用户手动改通道设置 / OEM（ColorOS）恢复默认 / 系统升级重置），以及它是否就是「之前生效、现在不静音」的根因 —— 现有日志与 dumpsys **无法自证**，不许编。`0x26` 只解释「为何事后纠正通道可能无效」，不等于「根因已证明」。

[DONE] 子节1

## 子节 2：现役静音链路核查（只读源码）

### 2.1 `HookEntry.kt` 接线事实
`[源码事实]` 文件：`wxgroup/app/src/main/java/com/lx/wxgroup/HookEntry.kt`。

- **是否调用 `NotifyMute.install(...)`？—— 否，被注释掉。** 原文在 `:319-321`：
  ```kotlin
  // runCatching {
  //     NotifyMute.install(lpparam)
  // }.onFailure { LogX.e("NotifyMute install failed", it) }
  ```
  注释理由见 `:309-318`：2026-10-17 停用 K9，静音域统一到 `QuietNotificationBridge`（MuteAt / DndWindow / AutoDndGroup 共用一条通知改写通道），双轨并存会让真机结果不可归因；要回退就把下面两行注回来。
- **是否调用 `MuteAt.install(...)`？—— 是，现役安装。** 原文在 `:328-331`：
  ```kotlin
  runCatching {
      MuteAt.install(lpparam)
  }.onFailure { LogX.e("MuteAt install failed", it) }
  ```
- `DndWindow.install(lpparam)` 现役安装：`:333-336`；`AutoDndGroup.install(lpparam)` 现役安装：`:338-341`。
- 四个类的 `import` 仍在（`:20` NotifyMute、`:43` MuteAt、`:47` DndWindow、`:48` AutoDndGroup），但 `import` 只说明类可见，**不代表安装**；安装与否以 `install(...)` 调用行是否被注释为准。

### 2.2 `NotifyMute` vs `MuteAt` 对照表
`[源码事实]` 依据 `core/NotifyMute.kt:85,471-524` 与 `feature/MuteAt.kt:138,354-409`。

| 维度 | `core/NotifyMute.kt`（K9，已停用） | `feature/MuteAt.kt`（现役） |
|---|---|---|
| 通道 id 常量 | `MUTE_CHANNEL_ID = "wxgroup_silent"`（`:85`） | `MUTE_CHANNEL_ID = "wxgroup_silent"`（`:138`） |
| 是否建通道 | 是，`ensureMuteChannel()`（`:478-495`） | 是，`ensureMuteChannel()`（`:365-385`） |
| 建通道条件 | `nm.getNotificationChannel(MUTE_CHANNEL_ID) == null` 才建（`:483`） | `manager.getNotificationChannel(MUTE_CHANNEL_ID) == null` 才建（`:370`） |
| 建时静音参数 | `IMPORTANCE_HIGH` + `setSound(null,null)` + `enableVibration(false)` + `vibrationPattern=null`（`:484-489`） | 完全相同（`:371-379`） |
| 建后日志 | `"K9 mute channel created"`（`:491`） | `"QUIET silent channel created"`（`:381`） |
| 通道已存在时 | 直接 `true`，不纠正（`:493`） | 直接 `true`，不纠正（`:383`） |
| 谁改 `mChannelId` | `setChannelId()`（`:504-524`） | `rewriteChannel()`（`:387-409`） |
| 改法 | 反射 `Notification.mChannelId` 写 `MUTE_CHANNEL_ID`，失败再遍历 String 字段按「当前值等于 channelId」兜底（`:505-523`） | **逐字相同逻辑**（`:388-408`），仅函数名不同 |
| 触发判据 | K9 自身判据（注释称 `isNameChar` / `@all` 边界与现役分叉，`:312-314`） | `MuteAt.shouldMute(notification, talker)`（`:317`） |
| 是否现役 | 否（HookEntry 安装点被注释） | 是（HookEntry 安装） |

**条件是否相同（都只在 channel==null 时建？）**：**相同**。两者都是先 `getNotificationChannel(MUTE_CHANNEL_ID)`，仅当返回 `null` 时才 `createNotificationChannel`；通道已存在则直接 `return true`，**从不重新 `setSound(null)` / 纠正为静音**。

**差异点**：
1. 归属/日志前缀不同（`K9 ...` vs `QUIET ...`）。
2. 改 channelId 的函数名不同（`setChannelId` vs `rewriteChannel`），但实现逐字相同。
3. 触发判据不同（K9 旧判据 vs `MuteAt.shouldMute`）。
4. 现役状态不同（K9 停用、MuteAt 现役）。
5. `[源码事实]` 两处都用**同一个 id** `wxgroup_silent` 建通道 ⇒ 仓库里确有「两处创建同名通道」的代码事实；但当前版本只有 `MuteAt` 安装，故 **K9 与 MuteAt 竞争双写在现役版本不成立**。

### 2.3 回答：本版微信上通知静音落地走哪个类
`[源码事实]` 走 **`feature/MuteAt.kt`**（经 `QuietNotificationBridge` 统一通知改写通道，与 `DndWindow` / `AutoDndGroup` 共用）。`core/NotifyMute.kt` 的类与其通道定义仍保留在仓库，但 `HookEntry` 中安装点被注释（`:319-321`），**不参与运行**。
现场日志侧佐证（`docs/_r8x_muteat失效_只读取证.md` §2.1）：现场日志前缀为 `QUIET ...`（`QUIET silent channel created`、`QUIET AT muted ... talker=测试群聊`），与 `MuteAt` 的日志字面一致，未见 `K9 ...` 日志。

[DONE] 子节2

## 子节 3：`AT muted` 日志的语义边界

### 3.1 `AT muted` 出现在哪个动作之后
`[源码事实]` 依据 `feature/MuteAt.kt:354-363`（`mute()` 函数）与 `:297-320`（`onNotify()`）：

```kotlin
354  private fun mute(notification: Notification, id: Int, reason: String, talker: String?) {
355      val context = HostContext.app() ?: return
356      if (!ensureMuteChannel(context) || !rewriteChannel(notification)) {
357          hotLog("$reason mute failed id=$id")
358          return
359      }
360      muteUntilMs = System.currentTimeMillis() + MUTE_WINDOW_MS
361      muted.incrementAndGet()
362      hotLog("$reason muted id=$id talker=$talker")
363  }
```

- `onNotify()` `:317-319`：`if (muteAtEnabled && MuteAt.shouldMute(notification, talker)) { mute(notification, id, "AT", talker) }` ⇒ `reason = "AT"`，故日志字面为 `AT muted id=... talker=测试群聊`。
- `AT muted` 的**前置动作链**（按行号）：`:355` 拿到 `HostContext` → `:356` **`ensureMuteChannel()` 返回 true 且 `rewriteChannel()` 返回 true**（两个前置都成功，否则走 `:357` 的 `... mute failed` 并 return）→ `:360` 设静音窗口时间戳 → `:361` `muted` 计数器自增 → `:362` 打日志。
- ⇒ `AT muted` 紧跟在「两个前置函数调用返回 true + 本地计数器自增」之后。它之前**没有**任何「系统确认通道真的静音」的动作。

### 3.2 能否证明「系统实际静音成功」？—— 不能
`[源码事实]` `mute()` 全函数（`:354-363`）实际只做四件事：取 Context、确保通道存在、反射改写 Notification 的 `mChannelId`、维护本地状态并打日志。其中：

- `:356` 的 `ensureMuteChannel()`（`:365-385`）**只在通道不存在时**创建为静音（`:370-380`）；通道已存在就直接 `return true`（`:383`），**不检查、也不纠正通道当前是否静音**。所以即便通道当前是 `mSound=content://settings/system/notification_sound` + `mVibrationEnabled=true`（dumpsys 现状），它仍返回 `true`。
- `:356` 的 `rewriteChannel()`（`:387-409`）只把 Notification **对象内存字段** `mChannelId` 改成 `MUTE_CHANNEL_ID` 并返回 `true`（成功点 `:392`，兜底成功点 `:404`），**不读取系统通道的实际 sound / vibration / importance，也不向系统发任何校验请求**。
- `:360-362` 全是本地状态与日志。

⇒ `[源码事实]` `AT muted` 只证明「判据通过 + `ensureMuteChannel`、`rewriteChannel` 两个函数返回 true」，**不能证明系统实际静音成功，也不能证明用户实际未听到提示音**。与只读取证文件 §2.1 的判断一致。

### 3.3 `rewriteChannel` 返回 true 但通道有声音，`AT muted` 会不会照样打？—— 会
`[源码事实]` **会照打**。逐行依据：

1. `:356` 只消费 `rewriteChannel()` 的布尔返回值；该返回值在反射写字段成功时即为 `true`（`:392`；兜底路径 `:404`），**与通道是否有声音没有任何数据关联**——`rewriteChannel()`（`:387-409`）通篇没有调用 `getNotificationChannel` / `getSound` / `isVibrationEnabled()`。
2. 同条件里的 `ensureMuteChannel()` 在通道已存在时恒返回 `true`（`:383`），不会因为「通道有声」而失败。
3. 于是两个前置条件都满足 → `:361` 计数器自增 → `:362` 照打 `AT muted id=... talker=测试群聊`。

⇒ `[源码事实]` 即使 `wxgroup_silent` 通道当前 `mSound=系统默认铃声`、`mVibrationEnabled=true`、`mUserLockedFields=0x26`（SOUND 位被锁），`AT muted` 依然会出现。**该日志不是「静音有效」的判据。**

### 3.4 判据边界小结
- `AT muted` 能证明：`[源码事实]` onNotify 判据链通过（`muteAtEnabled && MuteAt.shouldMute`，或同链路 DND/GRP 分支）、`mute()` 被调用、`ensureMuteChannel()` 与 `rewriteChannel()` 均返回 `true`。
- `AT muted` 不能证明：系统通道实际无声无震动、用户实际未听到提示音。
- 对用户可见的正确判据应落在：系统通道字段（dumpsys 的 `mSound` / `mVibrationEnabled` / `mImportance` / `mUserLockedFields`）与用户实际听感，而非内部计数器（见 `docs/_r8x_muteat失效_只读取证.md` §2.1、§3.4）。

[DONE] 子节3

[DONE] ALL
