# R8x 后续：横幅恢复方案（横幅弹 + 通知栏 + 不响 + 不振）

> 状态：**查证完成，路线改排：P2 上位、P1 降级**。Pro lane 402 降档 Lite 查证 + 主会话亲核（台账 19:1x 三件套已登记）。
> 用户诉求（2026-11-28 19:12）：**横幅弹 + 通知栏可见 + 不响 + 不振**，四条同时成立——与现役 B 路线（LOW=2，横幅被关）冲突。
> 纪律：判据对着用户可见事实，不对内部计数器；标签 [源码事实]/[真机事实]/[推断]/[假设] 分明；设备由主会话独占，子代理只读。

---

## 0. 现役状态（基线）

- `MuteChannelId.kt`：`VALUE=wxgroup_silent_low` / `IMPORTANCE_LEVEL=2` / `NAME=静音通知（分组模块·低重要性）`。
- `MuteAt.kt:370-382 ensureMuteChannel()`：通道不存在则 `NotificationChannel(id,name,2)` + `setSound(null,null)` + `enableVibration(false)` + `vibrationPattern=null`。
- `MuteAt.kt:281-295 installVibratorHook()`：`SystemVibrator.vibrate` 在 `muteUntilMs`（静音后 1200ms 窗口）内 `param.result=null` 吞掉。
- 真机 dumpsys（19:1x 现场）：
  - `wxgroup_silent_low`：`mImportance=2 / mSound=系统默认铃声 / mAudioAttributes=null / mUserLockedFields=38 / mShowBanner=false / mVibrationEnabled=true`
  - 微信 `message_channel_new_id`：`mImportance=4 / mSound=null / mShowBanner=true / mVibrationPattern=[300,200,300,200] / mVibrationEnabled=true / mAudioAttributes=AudioAttributes(USAGE_NOTIFICATION)`
  - 旧 `wxgroup_silent`/`v2`（已删除）：`mImportance=4 / mSound=系统默认铃声 / mAudioAttributes=null / mShowBanner=true / mUserLockedFields=38`

## 1. 关键矛盾（决定路线成败）

**微信自带通道 `message_channel_new_id` 天生满足「横幅 + 通知栏 + 无声」**（mSound=null + mShowBanner=true + importance=4），唯一多余的是震动 pattern。
我方自建通道无论 HIGH/LOW，都被系统补成 `mSound=系统默认铃声 + mVibrationEnabled=true + lock=38`。

- 01:04 快照 [真机事实]：原生 @全体成员通知 `channel=message_channel_new_id ticker=路过人间: @所有人` ⇒ **@全体成员原生就走这个无声+横幅通道**。
- 反向对照关开关后用户答「响了（有声音或震动）」——选项文案把声音/震动合并，**未区分**。结合原生通道 mSound=null 但有震动 pattern，[推断·待验] 用户感知很可能是**震动**，不是铃声。
- 19:2x 本想做只读取证（关开关→用户发→拆开问三项），因 adb 授权反复掉线暂停；**当前开关状态未知**（最后确认 02:46 = true，19:2x 的 kvset false 未确认执行）。连接恢复后第一件事 = 回读 `chat.mute_at_enabled`。

`[DONE]`

---

## 2. P1 方案查证结论：**静态命门暴露，降级**

### 2.1 本机 AOSP 源码事实（主会话独立查证）
`android-35/android/app/NotificationChannel.java`：
- `:259` `private Uri mSound = Settings.System.DEFAULT_NOTIFICATION_URI;`（字段初值 = 系统默认铃声）
- `:273` `private AudioAttributes mAudioAttributes = Notification.AUDIO_ATTRIBUTES_DEFAULT;`（字段初值非 null）
- `:608-611` `setSound(Uri sound, AudioAttributes audioAttributes)` 只有两句直白赋值：`this.mSound = sound; this.mAudioAttributes = audioAttributes;`
- `:850-852`（Lite 查证）`getAudioAttributes()` 原样返回 `mAudioAttributes`

`android-35/android/app/Notification.java`：
- `:534` `AUDIO_ATTRIBUTES_DEFAULT = new AudioAttributes.Builder()...`（非 null 常量）

[源码事实] 三种调用结果：
- `setSound(null, null)` ⇒ `(null, null)`
- `setSound(null, AUDIO_ATTRIBUTES_DEFAULT)` ⇒ `(null, 非null)`
- `setSound(defaultUri, null)` ⇒ `(默认铃声, null)` ← **与现场 dump 逐字同形**

### 2.2 P1 命门（Lite 第 3 题回填，决定性）
- [AOSP 源码·联网，Lite] Settings 调高重要性路径（`ImportancePreferenceController`）传的是 **`mChannel.getAudioAttributes()`**，不是字面 null。
- 若通道此前被 `setSound(null,null)` 置成 null 属性 ⇒ `getAudioAttributes()` 返回 null ⇒ 产出 `(默认铃声, null)` = 现场 dump 逐字命中。
- ⇒ **P1 传非 null 属性不能阻止补声**：属性从 null 变非 null，但 `setSound(defaultUri, 非null属性)` 照样把 mSound 写成默认铃声。P1 的核心假设（非 null 属性能阻断补声）在这条路径下不成立。
- 诚实边界：补声写入者身份仍 [查不到]（OEM 钩子 / 系统设置路径 / 其他三者同形）；P1 若靠「OEM 检测属性 null 才补声」的分支仍可能成立，但**没有正证据支撑，不值得优先装机试**。

### 2.3 heads-up 与 mShowBanner（Lite 第 4 题回填）
- [AOSP 源码·联网] 横幅必要条件 = `importance >= IMPORTANCE_HIGH`（非充分，还要满足 flags/打断策略）。
- [AOSP 源码·联网] **`mShowBanner` 不是 AOSP 标准字段**，属 ColorOS OEM 扩展 ⇒ 不能靠 AOSP 语义推断它在 LOW 下被关的机制，只能认现场事实 `mShowBanner=false`。
- ⇒ 要横幅，importance 必须回 HIGH；自建 HIGH 又有补声死结（§2.2）⇒ **P1 两头受堵**。

`[DONE]`

---

## 3. 三档红线（进入 / 返回 / 复位边界）

> 按 R42-N1N6 教训，三档缺一不可；只写「命中/未命中」会漏掉唯一的复位触发路径。

### 3.1 进入边界
- 仅当 `muteAtEnabled && shouldMute(notification, talker)` 且会话在 allow 名单、@全体成员命中时，才允许改写通知表现（P2 = 借微信原生通道 + 吞震动）。
- 判据链 `shouldMute/groupInScope/isAtMe` 一行不改；DND/GRP/AT 优先级顺序不变（DND→GRP→AT）。
- `bits == null` / self 取不到 ⇒ 不排除、照拦（R42 self-exclude 硬红线继承）。
- 未命中会话、普通消息、@单个成员（allOnly=true）一律不改。

### 3.2 返回边界
- 改写后：通知仍在系统队列、ticker 在、badge 不变、横幅按目标通道 `mShowBanner=true` 表现。
- 用户可见：横幅弹、下拉通知栏可见、不响铃、不振。
- `AT muted` 只是判据通过日志，**不是最终判据**；最终判据 = dumpsys + 用户听感三项拆开问。

### 3.3 复位边界（最易漏）
- **关开关**：`muteAtEnabled=false` ⇒ AT 分支不进，`mChannelId` 保持微信原生 ⇒ 恢复原生横幅 + 震动，不留改写。
- **滚回顶部 / 退出聊天 / 切会话**：`muteUntilMs` 是全局 1200ms 时间窗，`installVibratorHook` 只在窗口内吞 `SystemVibrator.vibrate`；窗口过后任何会话震动恢复 ⇒ 不会因非命中路径把静音残留或误触发。
- **新会话/其他会话通知**：`talkerById` 按通知 id 映射，`remember(item)` 每条通知重解析；不存在状态粘到下一个会话。
- **开关来回切**：`kvset` 只改 bool，不改通道对象；关→开时通道已存在（`ensureMuteChannel` null 判断直接 return），不重复创建。

`[DONE]`

---

## 4. 四维判据 + 变异设计

### 4.1 静态判据（dumpsys）
- **P2 目标（微信原生通道）**：`channel=message_channel_new_id` / `mImportance=4` / `mSound=null` / `mShowBanner=true` / 通知 Record 无 `mIsInterruptive=true`、无 `mSoundNotificationKey`/`mVibrateNotificationKey`。
- 震动：`installVibratorHook` 在静音窗口内拦截日志存在（如加计数），且用户听感不振。
- 成立 = 全中；任一不符 ⇒ 停，不进正向验收。

### 4.2 正向真机判据（用户可见，四条**分别**问，不可合并）
1. 横幅弹不弹？ 2. 下拉通知栏可见否？ 3. 有铃声吗？ 4. 有震动吗？

### 4.3 反向 A/B 对照
关 `chat.mute_at_enabled=false` → 用户再发 @全体成员 → 恢复原生横幅 + 震动（若原生只震不响，则应只恢复震动）→ 再开回 true。自变量唯一 = 该开关，同群同 @类型。

### 4.4 变异设计
| 变异 | 改法 | 预期红 |
|---|---|---|
| M1 | 目标通道从 `message_channel_new_id` 改成自建 `wxgroup_silent_low` | 静态 mImportance=4 / mShowBanner=true / mSound=null 三条红 |
| M2 | 关掉 `installVibratorHook`（`param.result=null` 改为放行） | 用户听感「不振」红 / 拦截计数红 |

⚠️ 变异先确认有语义（非等价变异）：M1/M2 都是行为字段改动，非等价，0 条红 = 假验证。

`[DONE]`

---

## 5. 路线改排与风险

### ⭐ P2 上位：借微信原生 `message_channel_new_id`
- **依据**：该通道天生 `mSound=null / mShowBanner=true / importance=4`，真正满足「横幅 + 通知栏 + 无声」；只剩震动 pattern 一个问题，而 `installVibratorHook` 现成可在静音窗口吞震动。
- **实现方向**：对命中的 @全体成员通知，不再改写 `mChannelId` 到自建通道（保持微信原生 `message_channel_new_id`），只靠 `installVibratorHook` 吞震动。改动可能小到「AT 分支跳过 rewriteChannel + 吞震动」。
- **代价/风险**：
  - 借宿主通道，宿主更新可能改字段（11-26 微信更新正是环境变化点）⇒ 须静态判据每次装机复核。
  - 用户若在系统设置给 `message_channel_new_id` 改了铃声 ⇒ 全局微信消息都响（用户行为，非本模块可控）。
  - 震动 hook 是 1200ms 全局窗口，覆盖是否完整须真机验；窗口外震动恢复是设计行为。
  - `installVibratorHook` 吞的是 `SystemVibrator.vibrate`，若微信改用别类震动 API 则漏。⚠️ **更正（2026-11-28 19:5x 主会话亲核）：此前「现场已验证过有效」是无实据的过度断言**。全库检索仅 R50 文档描述过该设计（`docs/_r50_muteat_rootcause.md:60`），无任何单独验证记录；B 路线验收的「不振」是通道降 LOW + `enableVibration(false)` 与 hook 同时生效，自变量混淆，不能归因到 hook。P2 保留原生 HIGH 通道（带震动 pattern [300,200,300,200]）后，**震动能否被 hook 吞掉是唯一成败点，标 [假设]，必须真机正反对照验证**：开开关不振 + 关开关有震，才证 hook 有效；若开开关仍震 → hook 漏吞，需另查微信震动 API（P2 失败，转增强 hook 或 P4）。

### P1 降级：自建 HIGH + 非 null 音频属性
- 命门见 §2.2：传非 null 属性在 Settings 回填路径下照样被补声，静态优势不成立；无正证据支撑，不优先装机。
- 保留为 P2 失败后的备选（届时须真机小实验确认 OEM 是否按「属性 null」判断补声）。

### P4 后备：hook 系统发声
- 挂系统框架、LSPosed 勾选 + 重启，结构改动最大；P2/P1 都失败才考虑。

### P3 不用：反射改 ColorOS 私有 `mShowBanner`
- OEM 私有字段、非 AOSP；heads-up 还看 importance，收益不确定。

`[DONE]`

---

## 6. 下一步

- [x] 本机 AOSP `setSound` / `getAudioAttributes` / `AUDIO_ATTRIBUTES_DEFAULT` 语义（§2.1）
- [x] Lite 回填：Settings 传 `getAudioAttributes()`、heads-up 必要非充分、`mShowBanner` 是 OEM 字段（§2.2/§2.3）
- [x] 原生 @全体成员通知通道 = `message_channel_new_id`（§1）
- [x] **设备恢复后回读 `chat.mute_at_enabled`**（19:34 回读 true；19:3x 关开关取证后已复位 true）
- [x] 只读取证：关开关 → 用户发 @全体成员 → 拆开问三项（19:36）：**横幅弹 ✅ / 无铃声 ✅ / 有震动 ✅** ⇒ P2 前提坐实（原生 `message_channel_new_id` 只震不响）
- [x] 落码（2026-11-28 19:5x）：`MuteRoute.kt` 纯判据 + `MuteAt.mute()` 按 reason 分流 + `MuteRouteTest.kt` 5 条；三闸绿（1231=1226+5）、真跑变异 M1 红2/M2 红4 恢复全绿、Lite 复核中
- [ ] **交叉复核返回后装机** → 正反对照 + 四维判据逐项验收（重点：震动 hook 成败点，见 §5 更正）→ 恢复开关 → 提交

`[DONE]`

---

[ALL DONE]
