
# R8x B 路线通道震动方案 — 真机证伪结论（2026-11-28 20:2x）

> 结论：**B1（改原生通道）与 B2（clone 新通道）均真机证伪**。Pro 402 降档 Lite 查证 + 主会话亲核；Lite `broute-lite` 结果消息超时未归，本文件结论全部基于主会话独立源码查证 + 真机受控实验。

## 1. 真机实验（昵称「测试群聊」/ 通道探针）

### B1 直接改原生通道 — 证伪
- 探针 `chantest`：反射改 `message_channel_new_id` 的 `mVibrationEnabled=false` + `vibrationPattern=null` + `createNotificationChannel` 覆盖。
- 结果：探针报「set vib=false 成功」，但 dumpsys 复核 `mVibrationEnabled=true` / `mVibrationPattern=[300,...]` 未变；再 `chanread` 读内存对象也回到 `vib=true` ⇒ **改动被系统整体还原**。
- ⚠️ **位解读更正（收尾时核本机 AOSP `NotificationChannel.java:182-203`）**：`USER_LOCKED_PRIORITY=0x01 / VISIBILITY=0x02 / IMPORTANCE=0x04 / LIGHTS=0x08 / VIBRATION=0x10 / SOUND=0x20`。故 `mUserLockedFields=25=0x19=16+8+1=VIBRATION+LIGHTS+PRIORITY` —— **VIBRATION 位本来就锁着**！先前「25 不含 VIBRATION(2) 位、理论上可改」是**把位值记错**（误用旧的 2/1 编码），作废。B1 改不动完全符合 AOSP `createNotificationChannel` 对 locked 字段不覆盖的语义（见 Lite 查证 `PreferencesHelper.createNotificationChannel:411-433`），不是 OEM 特例。

### B2 clone 专用通道 — 证伪
- 探针 `chanclone`：新建 `wxgroup_chan_clone_test`，clone 原生通道 `mSound=null` + `mAudioAttributes=非null` + 显示字段，关震动，锁 SOUND 位（lock=25）。
- app 侧刚创建读到 `sound=null vib=false lock=0` → dumpsys 系统侧立刻变成：
  - `mSound=content://settings/system/notification_sound`（补默认铃声）
  - `mVibrationEnabled=true`（补回震动）
  - `mUserLockedFields=30`（重写锁位）
  - `mImportance=4 / mShowBanner=true`（横幅保住）
- ⇒ **任何新建通道都被系统/OEM 无条件补默认铃声 + 补震 + 重写锁位**，与 B′ 当年失败同机制。clone 精确复制 null 声音 + 非null 属性也救不了。

## 2. 关键源码事实（主会话独立核查）
- `mShowBanner` 不在 AOSP `NotificationChannel`（`chanread` 报 NoSuchFieldException）⇒ ColorOS OEM 私有字段。
- `mUserLockedFields` 位：SOUND=1 / VIBRATION=2 / IMPORTANCE=4 / LIGHTS=8 / MAX_VOL=16。原生 25 锁 SOUND/LIGHTS/MAX_VOL，不锁 VIBRATION；clone 被系统重写成 30(=2+4+8+16)。
- `createNotificationChannel` 覆盖已存在通道：已存在同 id 时 app 侧无法覆盖受保护字段（真机实证，无论锁位）。

## 3. 三方证伪汇总（用户诉求「横幅+通知栏+不响+不振」四条同时成立）
| 路线 | 机制 | 结果 |
|---|---|---|
| P2 保留原生通道 + 微信进程 hook 吞震 | hook 在微信进程，震动由 system_server 触发 | ❌ 运行时实证：`QUIET VIB hit` 全零 |
| B1 直接改原生通道震动字段 | 反射改字段 + create 覆盖 | ❌ dumpsys 复核被还原 |
| B2 clone 专用通道 | 新 id + null 声音 + 锁 SOUND 位 | ❌ dumpsys 复核被补声补震 |
| 旧 B 路线（现役，已提交） | 改自建 LOW 通道 | ✅ 不响不振+通知栏可见，**但横幅不弹** |

## 4. 结论与建议
- 在「不 hook 系统框架、不改微信原生通道」前提下，本机 ColorOS 16 **无路可走**——四条诉求无法同时成立。
- 剩余唯一理论正解 = **A：hook system_server**（LSPosed 勾选系统框架，hook NMS/VibratorHelper 震动入口），需用户勾选 + 重启、结构改动最大。
- 建议：要么用户接受旧 B 路线（横幅不弹），要么走 A。已停止盲试，待用户拍板。
- ⚠️ 当前工作树是探针版（chanread/chantest/chanrestore/chanclone + clone 探针），**提交/收尾前须移除全部探针**；已装机的 APK 也是探针版。
