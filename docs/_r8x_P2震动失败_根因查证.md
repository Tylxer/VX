# R8x P2 震动 hook 失败根因查证（2026-11-28 20:0x）

> 状态：主会话亲核 + Lite 源码查证（Pro 402 降档，台账三件套已登记）。产物分步落盘，防父 turn 打断。

## 0. 结论摘要（先给，细节后补）

P2「保留原生通道」成功（横幅弹 + 通知栏可见 + 通道未改写），但「吞震动」失败（用户确认有震动），且另有铃声矛盾。`installVibratorHook` 是当前唯一吞震动手段，其失败可能来自：`SystemVibrator` 找类失败（classLoader）/ hook 覆盖的重载与实际调用链不符 / 通知震动绕过 `SystemVibrator` 走 `VibratorManagerService`。修复须先消除 hook 观测盲区（加安装与拦截日志），再按实际调用链补 hook，不能用「猜测换 hook 点」反复装机试错。

`[DONE]`

---

## 1. 现场事实（真机 19:59，昵称「测试群聊」发 @所有人）

| 项 | 值 | 来源 |
|---|---|---|
| 用户听感 | 横幅弹 ✅ / 通知栏可见 ✅ / **有铃声 ❌ / 有震动 ❌** | AskUserQuestion 逐项确认 |
| AT 分支 | `QUIET AT muted id=-1521557830 talker=...@chatroom rewrite=false`，`matched=1 muted=1` | 真机日志 19:59:20 |
| 通知快照 | `channel=message_channel_new_id ticker=路过人间: @所有人 title=测试群聊` | probe extras |
| 通道字段 | `mId=message_channel_new_id mImportance=4 mSound=null mVibrationPattern=[300,200,300,200] mVibrationEffect=Composed{repeat=-1} mVibrationEnabled=true mShowBanner=true mUserLockedFields=25` | dumpsys notification --noredact |
| NotificationRecord | `vibrate=null sound=null flags=SHOW_LIGHTS\|AUTO_CANCEL` | dumpsys |
| vibrate hook 状态 | **无日志**（installVibratorHook 成功/失败均不打日志，findClass 失败静默 return） | 全日志 grep `vibrat` = 0 行 |

`[DONE]`

---

## 2. 三个嫌疑（待 Lite 查证收敛）

### 2.1 嫌疑 A：classLoader 找类失败（第一嫌疑 → 已减弱）
`installVibratorHook` 用 `XposedHelpers.findClass("android.os.SystemVibrator", lpparam.classLoader)`，`lpparam.classLoader` 是微信 app loader，`SystemVibrator` 是 framework 类。若 findClass 失败会静默 return ⇒ hook 从未挂上。
- **反证**：项目内 `android.view.View` / `android.widget.AdapterView` / `android.app.Activity` 等多个 framework 类 hook 都用 `lpparam.classLoader`，真机长期有效（GconvPage/UiProbe）。双亲委派下 app loader 通常能委托到 boot loader ⇒ **此嫌疑减弱但仍未被直接证实**（无 vibrator hook 安装日志）。
- 判定：[推断] 大概率不是主因，但必须加安装日志坐实。

### 2.2 嫌疑 B：hook 覆盖的重载与实际调用链不符
- [本机源码事实] `android/os/SystemVibrator.java:198` 只声明 **1 个** `vibrate` 重载：`vibrate(int uid, String opPkg, VibrationEffect effect, String reason, VibrationAttributes attributes)`（@Override 父类抽象方法）。
- [本机源码事实] `android/os/Vibrator.java` 父类另有 7 个 `vibrate` 重载（L361/380/414/446/470/488/508 + 抽象 L519）。
- [本机源码事实] `SystemVibrator.vibrate(...)` 内部 → `mVibratorManager.vibrate(...)` → `SystemVibratorManager.java:135`。
- `hookAllMethods(SystemVibrator.class, "vibrate")` 若只 hook 声明方法，则只覆盖那 1 个 5 参重载。若通知震动走 `Vibrator.vibrate(VibrationEffect)` 或系统服务直连，则漏。
- 判定：[假设] 待 Lite 给出 hookAllMethods 精确语义 + NMS 调用链后收敛。

### 2.3 嫌疑 C：铃声来源矛盾
通道 `mSound=null` + NotificationRecord `sound=null`，但用户听到铃声。可能：①用户把震动嗡嗡声误归为铃声（19:36 关开关时也判「无铃声」，两次数值一致，此解释更自洽）；②ColorOS 对 HIGH importance 通知补默认提示音；③并发其他通知（19:59 前后日志未见别的通知到达，dumpsys 活跃通知里微信这条是唯一新的）。
- 判定：[假设] 倾向 ①，须单独只读取证区分，但不影响主路线（震动是核心）。

`[DONE]`

---

## 2.4 ⭐ 天然 A/B 对照坐实「vibrate hook 从未生效」（主会话逻辑推断）

已存在两组真机实验，唯一自变量是通道配置：

| 实验 | 通道 | muteUntilMs 窗口 | vibrate hook | 用户听感 |
|---|---|---|---|---|
| 旧 B 路线验收（`9a3752c` 之后） | 改写 LOW + `enableVibration(false)` + `vibrationPattern=null` | 同样设 1200ms | 同一份代码 | **不振** ✅ |
| P2（19:59） | 保留原生 HIGH + `mVibrationPattern=[300,200,300,200]` | 同样设 1200ms | 同一份代码 | **有震** ❌ |

两次 hook 代码、窗口逻辑完全一致，唯一差异是通道。若 hook 生效，两次都该不振 ⇒ **结论：vibrate hook 实际上从未吞到通知震动**，旧 B 的「不振」完全归功于 LOW 通道关震动。这与 §2.3 的历史更正吻合，且把嫌疑 A/B/C 收敛为一个根因：**app 进程 hook 根本拦不到 system_server 触发的通知震动**（通道配置能生效是因为 system_server 读它，hook 不行）。

- 标签：[推断·强]，依据是两组真机听感的唯一自变量对照（非源码时序推断）。
- 若此结论成立 ⇒ P2「保留原生通道 + app 进程 hook 吞震」架构上不可行，必须换路线。

`[DONE]`

---

## 3. 源码查证（主会话独立核查，Lite 结果消息丢失故不引用其原文）

### 3.1 vibrate hook 覆盖面 [本机源码事实]
- `android/os/SystemVibrator.java:198-201`：**只声明 1 个** `vibrate` 重载 —— `vibrate(int uid, String opPkg, VibrationEffect effect, String reason, VibrationAttributes attributes)`（@Override 父类抽象方法）。
- `android/os/Vibrator.java`：另声明 7 个重载（L361 `vibrate(long)` / L380 / L414 `vibrate(long[],int)` / L446 / L470 `vibrate(VibrationEffect)` / L488 / L508 `vibrate(VibrationEffect,VibrationAttributes)` / L519 抽象 5 参）。
- `XposedBridge.hookAllMethods(clazz, "vibrate", cb)` 只扫 clazz **自己声明**的方法（项目内定案注释 `ConvFilter.kt:422`、`ActivityResultRelay.kt:197` 同款语义）⇒ 现役 hook 对 `SystemVibrator` 只覆盖那 1 个 5 参方法，覆盖不到父类 `Vibrator` 的 7 个重载。
- `SystemVibrator.vibrate(5参)` 内部 → `mVibratorManager.vibrate(...)`（L205）→ `SystemVibratorManager.java:135` → `mService.vibrate(...)`（binder 到 system_server）。

### 3.2 classLoader 陷阱 [推断·减弱]
- [本机源码事实] `SystemVibrator` 是 framework 类（boot classloader 加载）。项目内 `android.view.View`（`RedPacket.kt:387`）、`android.widget.AdapterView`（`GconvPage.kt:333`）、`android.app.Activity`（`UiProbe.kt:136`）均用 `lpparam.classLoader` 或 app loader 找 framework 类且真机长期有效 ⇒ 双亲委派可达，**findClass 大概率成功**。但现役代码 findClass 失败静默 return 无日志，故未能直接证实，已加 `QUIET VIB probe ... found=true/false` 安装日志坐实。

### 3.3 跨进程根因 [推断·强，天然 A/B + AOSP 链]
- 通知震动发起方 = system_server 的 `NotificationManagerService` / `VibratorHelper`（AOSP 标准架构，本机 sources 无 `com/android/server/` 故标 [架构事实·未逐行核]）；那里 `context.getSystemService(Vibrator.class)` 拿到的是 **system_server 进程的 SystemVibrator 实例**。
- 本模块 LSPosed scope 只有 `com.tencent.mm`（装机回读 `[OK] scope 含 com.tencent.mm`）⇒ hook 只注入微信进程，**拦不到 system_server 进程的 vibrate 调用**。
- 通道改写之所以能生效（`enableVibration(false)` 让旧 B 不振），正因为 system_server 读通道配置决定震不震；hook 在微信进程改不了 system_server 的行为。
- 与 §2.4 天然 A/B 对照结论一致 ⇒ **P2「保留原生通道 + 微信进程 hook 吞震」架构上不可行**。

`[DONE]`

---

## 4. 红线三档 + 结论

（占位，见后续补全）

`[PENDING]`

---

## 5. ⭐ 最小验证定案（2026-11-28 20:21，运行时实证）

**探针**：`installVibratorHook` 加三档观测日志（安装 found/hooked + 拦截 hit 方法签名/inWindow/swallow + 临时多挂 Vibrator/SystemVibratorManager 只观测），装配装机 + 冷启。

**冷启安装日志（20:10:02）**：
```
QUIET VIB probe class=android.os.SystemVibrator        found=true hooked=1
QUIET VIB probe class=android.os.Vibrator              found=true hooked=-1  (hookAllMethods 抛异常，父类抽象方法)
QUIET VIB probe class=android.os.SystemVibratorManager found=true hooked=1
```
⇒ classLoader 陷阱**排除**（三类都 found=true），现役吞震 hook **确实挂上**（SystemVibrator hooked=1）。

**通知时刻（20:21:58，昵称「测试群聊」@所有人）**：
```
QUIET AT muted id=-1521557830 talker=...@chatroom rewrite=false
（QUIET VIB hit = 0 条，三个被 hook 的类全部零命中）
```

**定案**：hook 挂上了但微信进程**零 vibrate 调用** ⇒ 通知震动 100% 由 system_server 进程触发，微信进程 hook 架构上拦不到。P2「保留原生通道 + 微信进程 hook 吞震」**不可行，非参数问题**。与 §2.4 天然 A/B、§3.3 AOSP 链三方一致。

**探针状态**：当前树里是临时探针版（多挂两个类 + 日志），观测完成后须移除，不留生产。

`[DONE]`

---

## 6. 修复方向（三选一，待用户拍板）

| 方向 | 做法 | 代价/风险 |
|---|---|---|
| **A hook 系统框架** | LSPosed 勾选 system_server / Android Framework，hook NMS 或 VibratorHelper 的震动入口，`@所有人` 命中时吞 | 需用户 UI 勾选 + 重启；结构改动最大；但这是唯一能真正掐 system_server 震动的路径 |
| **B 改微信原生通道震动字段** | 反射拿 `message_channel_new_id`，`mVibrationEnabled=false` 后 `createNotificationChannel` 覆盖 | ⚠️ 位解读已更正：`mUserLockedFields=25=0x19=VIBRATION(0x10)+LIGHTS(0x08)+PRIORITY(0x01)`，**VIBRATION 位本就锁着**，AOSP `createNotificationChannel` 不覆盖 locked 字段 ⇒ 真机已证伪（改动被还原）|
| **C 接受现状 / P4 吞通知** | 保留横幅+通知栏但有声有震，或学 SystemedNotificationBlocker 吞通知（通知栏不可见） | C 不满足用户四条诉求 |

推荐 A（唯一正解）或 B（低成本有副作用）。声震都靠 system_server，微信进程侧已无解。

`[DONE]`
