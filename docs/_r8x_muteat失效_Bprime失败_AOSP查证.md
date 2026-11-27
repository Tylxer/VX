# r8x「群聊防@」B′ 方案失败 · AOSP 源码查证（Lite 只读档）

> 任务性质：纯源码查证的事实题，唯一正确答案；不改代码、不跑 gradle、不碰 adb、不改开关。
> 现象背景：昵称「测试群聊」的群里 @全体成员仍响铃。B′ 换 id 后真机 `dumpsys notification` 抓到新通道：
> `NotificationChannel{mId='wxgroup_silent_v2', mSound=content://settings/system/notification_sound, mVibrationEnabled=true, mUserLockedFields=38(0x26=SOUND+IMPORTANCE+VISIBILITY), mImportance=4, mAudioAttributes=null, ...}`
> 而建通道代码明确 `setSound(null, null)` + `enableVibration(false)`。

## 取证来源与标签约定

- **[本机源码]**：`C:\Users\lx\AppData\Local\Android\Sdk\sources\android-35\android\app\NotificationChannel.java`（存在，63,771 字节），下文行号均以此文件为准。
- [查不到] 本机 **没有** `NotificationManagerService.java` / `PreferencesHelper.java`（`sources/android-35/com/android/server/notification/` 为空，SDK sources 不含 `frameworks/base/services`）。子节 3 只能以 AOSP 知识 + 联网检索到的 AOSP 原文片段回答，均显式标注。
- 标签：`[源码事实]`=本机源码逐字可读；`[AOSP 源码事实·联网]`=联网取到 AOSP gitiles 原文片段但非本机；`[AOSP 知识]`=AOSP 设计语义/官方文档；`[文档事实]`=Android 官方开发者文档；`[OEM 推断]` / `[推断]` / `[假设]` / `[查不到]` 按字面含义。
- 测试群聊统一以昵称「测试群聊」称呼，不列账号标识。

## 子节 1：`mSound=系统默认铃声 + mAudioAttributes=null` 矛盾组合的语义

### 1.1 `setSound` 是两句直白赋值，没有「null → 默认铃声」的归一化（本机源码）

`NotificationChannel.java:608-611`（[源码事实]）：

```java
public void setSound(Uri sound, AudioAttributes audioAttributes) {
    this.mSound = sound;
    this.mAudioAttributes = audioAttributes;
}
```

方法体只有两句赋值，没有判空、没有默认值替换、没有兼容性兜底。结论：

- `setSound(null, null)` → `mSound = null`（`:609`）、`mAudioAttributes = null`（`:610`）。[源码事实] 即模块建通道那一刻，这两个字段确实都是 `null`。
- `setSound(非 null Uri, null)` → `mSound = 传入的 Uri`、`mAudioAttributes = null`。[源码事实] 「默认铃声 + 空属性」**在语义上就等价于调用了一次 `setSound(默认铃声Uri, null)`**。
- 未调用 `setSound` 时 Java 字段初始值是 `mSound = Settings.System.DEFAULT_NOTIFICATION_URI`（`:259`，即 `content://settings/system/notification_sound`）与 `mAudioAttributes = Notification.AUDIO_ATTRIBUTES_DEFAULT`（`:273`，**非 null**）。[源码事实] 所以「默认铃声 + null 属性」**不是**对象初始默认值，必然经过一次显式的 `setSound(uri, null)`（或等价的直接字段写入）。
- Parcel 往返不会补默认值：反序列化构造函数 `:328-333` 在标志位为 0 时把 `mSound` 置 `null`，`:353` 在 `readInt() <= 0` 时把 `mAudioAttributes` 置 `null`。[源码事实] 即 `(null, null)` 与 `(默认铃声, null)` 都能被 Binder 原样保真。
- `toString()` 在 `:1594`、`:1607` 分别打印 `mSound` / `mAudioAttributes`。[源码事实] dumpsys 中 `mAudioAttributes=null` 是系统侧对象的**真实字段值**，不是 dump 截断。

### 1.2 能产出「默认铃声 + null 属性」组合的路径清单

| # | 路径 | 机制与证据 | 标签 |
|---|---|---|---|
| 1 | 某处代码直接调 `setSound(Settings.System.DEFAULT_NOTIFICATION_URI, null)` | `:608-611` 原样赋值，必然产出该组合。本案是否真的发生：查不到。 | [源码事实]（语义）／[查不到]（本案） |
| 2 | AOSP 系统设置的用户改重要性路径 | AOSP `packages/apps/Settings/.../ImportancePreferenceController.onPreferenceChange`：当用户把重要性从 `< IMPORTANCE_DEFAULT` 调到 `>= IMPORTANCE_DEFAULT` 时执行 `mChannel.setSound(RingtoneManager.getDefaultUri(TYPE_NOTIFICATION), mChannel.getAudioAttributes())`、`mChannel.lockFields(USER_LOCKED_SOUND)`、`mChannel.setImportance(importance)`、`mChannel.lockFields(USER_LOCKED_IMPORTANCE)`、`saveChannel()`。`RingtoneManager.getDefaultUri(TYPE_NOTIFICATION)` 正是 `content://settings/system/notification_sound`；而建通道时 `setSound(null,null)` 使 `getAudioAttributes()` 为 `null` ⇒ **逐项命中 dump 的四个特征**（默认铃声、null 属性、SOUND 位锁、IMPORTANCE 位锁）。 | [AOSP 源码事实·联网]（gitiles 原文片段，非本机）；本案是否发生[查不到] |
| 3 | OEM 在建通道后自行覆盖 `mSound`，沿用旧的 null 属性（如 `setSound(defaultUri, channel.getAudioAttributes())`） | 能精确产出该组合，但无本机/OEM 源码可证。 | [OEM 推断] |
| 4 | 备份/恢复路径 | `populateFromXml` `:1123-1128` 调 `restoreSoundUri`；`:1249`（规范化失败且应用已安装）与 `:1216`（异常兜底）都会返回 `Settings.System.DEFAULT_NOTIFICATION_URI`。**但** `safeAudioAttributes`（`:1429-1439`）必定 `new AudioAttributes.Builder()...build()` 返回非 null ⇒ AOSP 标准恢复得到的是「默认铃声 + 非 null 属性」，**不是**本题组合。只有叠加 OEM 把属性置 null 才可能吻合。 | [源码事实]（恢复产出非 null 属性）＋[OEM 推断]（置 null 的部分） |
| 5 | 同 id 重建时旧通道字段保留、新参数未落地 | 不**产生**该组合，但解释它为何**持续存在**：见子节 3。 | [AOSP 知识] |
| 6 | 该 id 并非首次创建（更早版本/上次安装已建过并被改过），本次代码走了 `getNotificationChannel(id) != null` 的分支未重建 | 能解释「新 id 却带 user lock + 默认铃声」。设备历史查不到。 | [假设] |

> 排除项：`setSound(null, null)` 本身**不可能**产出该组合（`:609` 赋 null）；仅「app 建通道参数已正确且之后无人改 mSound」也**不可能**产出该组合。[源码事实]

### 1.3 关键判定：能否证明「是系统/OEM 建通道后覆盖了 mSound，而不是 app 建通道参数错」？

**不能单凭该组合唯一证成「OEM 覆盖」，但可以证成「建通道之后 mSound 被改写过」。** 分层结论：

1. **[源码事实] 可证**：最终落库对象的 `mSound` 不是 app 调 `setSound(null,null)` 后的 `null`，因此建通道之后必有某一环节把 `mSound` 写成了 `content://settings/system/notification_sound`。单纯「app 参数正确且无人干预」与 dump 矛盾。
2. **[源码事实] 不能证**：写入者是谁。路径 1（app/模块自己或其依赖又调了 `setSound(defaultUri, null)`）与路径 2（AOSP 系统设置用户操作）与路径 3（OEM 定制）在最终对象上**完全同形**，dumpsys 快照没有来源信息，无法区分。[查不到]
3. **[AOSP 源码事实·联网] 重要限定**：AOSP 本身就存在「系统设置按用户操作把 sound 改成默认铃声并锁 SOUND 位」的标准路径（路径 2），所以「系统在建通道后覆盖 mSound」是**被 AOSP 源码直接支持**的解释之一，不是必须归咎于 OEM。但该路径的触发前提是「旧重要性 < DEFAULT 且用户调到 ≥ DEFAULT」，而模块建通道时用的是 `IMPORTANCE_HIGH`(4)，在**完全全新、未被改动**的通道上此前提不成立。[推断] ⇒ 若要走路径 2，必须先有「通道被降到 DEFAULT 以下又被调回」或「OEM 改写了同样的设置逻辑」等前置事件，这些本案均[查不到]。
4. **[源码事实] 不能证「app 建通道参数错」**：`setSound(null,null)` 在 AOSP 语义下的确就是「无声音频属性 + 无声 Uri」，参数本身没错；问题在于之后被改写（以及 SOUND 位被锁导致 app 后续纠正无效）。
5. **[推断] 对 B′ 的影响**：换 id 只能绕开「旧 id 的 user lock」，绕不开「建通道后任意一方把 sound 改回默认并加锁」。因此 dump 出现该组合，应判为 **B′ 未被证实**，而不是「app 建通道参数写错」。

[DONE] 子节1

## 子节 2：`mUserLockedFields` 的来源

### 2.1 位定义（本机源码，`NotificationChannel.java`）

| 常量 | 值 | 行号 | 含义 |
|---|---|---|---|
| `USER_LOCKED_PRIORITY` | `0x00000001` | `:182` | 免打扰绕过 |
| `USER_LOCKED_VISIBILITY` | `0x00000002` | `:186` | 锁屏可见性 |
| `USER_LOCKED_IMPORTANCE` | `0x00000004` | `:190` | 重要性 |
| `USER_LOCKED_LIGHTS` | `0x00000008` | `:194` | 呼吸灯 |
| `USER_LOCKED_VIBRATION` | `0x00000010` | `:198` | 振动 |
| `USER_LOCKED_SOUND` | `0x00000020` | `:203` | 铃声 |
| `USER_LOCKED_SHOW_BADGE` | `0x00000080` | `:208` | 角标 |
| `USER_LOCKED_ALLOW_BUBBLE` | `0x00000100` | `:213` | 气泡 |

以上全部 [源码事实]。`dumpsys` 的 `38 = 0x26 = 0x20 + 0x04 + 0x02 = SOUND + IMPORTANCE + VISIBILITY`，**[源码事实]**。`LOCKABLE_FIELDS`（`:218-227`）只包含上表 8 项，没有 `0x40` 位；`0x26` 的组合合法。[源码事实]

`mUserLockedFields` 字段声明在 `:267`，**没有显式初始化** ⇒ Java 默认 `int 0`。[源码事实] 只有显式 `lockFields(int)`（`:472-474`，`|= field`）才会置位；`unlockFields`（`:479-481`，`&= ~field`）清位。`hasUserSetImportance()` / `hasUserSetSound()` 分别在 `:1064-1065` / `:1072-1073` 做位测试。[源码事实]

### 2.2 谁会写这些位（可查到的机制）

- **用户改系统设置**：AOSP `Settings` 的 `ImportancePreferenceController.onPreferenceChange` 会 `lockFields(USER_LOCKED_SOUND)` + `lockFields(USER_LOCKED_IMPORTANCE)`。[AOSP 源码事实·联网]
- **NMS 的用户更新路径**：AOSP master 中 `NotificationManagerService` 调 `mPreferencesHelper.updateNotificationChannel(pkg, uid, channel, true /* fromUser */, ...)`（约 `:3038`，来自 AOSP diff 片段）。[AOSP 源码事实·联网] 该 `fromUser=true` 分支会把用户改过的差异锁进 `updatedChannel`。[AOSP 知识]（精确分支语句本机/联网未完整取到，标注[查不到]）
- **XML 备份/恢复**：`NotificationChannel.populateFromXml` `:1151` 直接 `lockFields(safeInt(parser, ATT_USER_LOCKED, 0))`；写侧 `:1337` / `:1413` 把 `getUserLockedFields()` 序列化。⇒ 恢复能把旧锁位原样带回。[源码事实]
- **同 id 对象复制**：`copy()` `:449` 把 `mUserLockedFields` 拷到新对象；Parcel `:343` 原样读写。[源码事实]
- **app 侧**：`lockFields` 是 `@TestApi`/`@hide`（`:471-472`），普通 app 不在正常 API 面内。[源码事实]

### 2.3 回答

**Q：一个刚被 app 首次创建的全新通道，`mUserLockedFields` 应该是多少？**

**应为 `0`。** [源码事实] + [AOSP 源码事实·联网] 理由：

1. app 侧 `new NotificationChannel(...)` 时字段默认 `0`（`:267`）。[源码事实]
2. AOSP master `PreferencesHelper.createNotificationChannel` 存入通道前执行 `clearLockedFieldsLocked(channel)`，而该方法就是 `channel.unlockFields(channel.getUserLockedFields())` —— **把传入对象上已有的锁位全部清掉**。[AOSP 源码事实·联网] ⇒ 即使 app 误传了 lock 位也不会被保留。
3. 全新 id 没有历史 XML 可恢复、没有用户设置操作、没有旧对象可 `copy()`。[推断]

**Q：如果全新 id 首次创建后 `mUserLockedFields=38`，AOSP 标准行为下可能吗？**

**不可能由「全新 id 的首次创建」这一事件本身产生。** [源码事实] + [AOSP 知识] `38` 需要至少一个写锁事件发生在创建之后（或创建并非真正"首次"）。可列举的解释：

| # | 解释 | 证据/判定 | 标签 |
|---|---|---|---|
| 1 | 用户在系统设置里改了该通道的声音/重要性（→ SOUND+IMPORTANCE 位），并改了锁屏可见性（→ VISIBILITY 位） | SOUND+IMPORTANCE 有 AOSP 源码路径；VISIBILITY 位的控制器语句未取到原文。本案用户是否操作：查不到 | [AOSP 源码事实·联网]（前两位）＋[推断]（VISIBILITY 位）＋[查不到]（本案） |
| 2 | 系统从备份/旧配置恢复了该 id 的 XML，`locked` 属性带回 `0x26` | 机制为源码事实；但全新 id 不应有备份记录，除非设备曾有过该 id（换机/刷机/旧测试包） | [源码事实]（机制）＋[假设]（本案发生过） |
| 3 | OEM 默认策略在建通道/通知设置里主动 `lockFields(0x26)` | 能解释「首次创建即带锁」，无 OEM 源码可证 | [OEM 推断] |
| 4 | app/模块（或其依赖）调用了 `lockFields(0x26)` | API 为 `@hide`/`@TestApi`，正常业务不会调；可能性低但语义上可行 | [假设] |
| 5 | 该 id 不是真正"首次"：更早的构建/上次安装已经建过 `wxgroup_silent_v2` 并被改过，本次 `getNotificationChannel(id) != null` 直接跳过创建 | 与 dump 中 `mVibrationEnabled=true`（建通道时明明 `enableVibration(false)`）以及 SOUND 位被锁同时吻合 | [假设] |
| 6 | 系统/OEM 在用户无感知情况下重置/恢复通道配置为「有声有震默认档」 | 能同时解释声音、振动、三个锁位；无日志证据 | [OEM 推断] |

> **反证要点**：`0x26` **不含** `USER_LOCKED_VIBRATION`（`0x10`）。[源码事实] 若是「用户逐项手改」，通常振动改动也会锁 `0x10`；dump 却是 `mVibrationEnabled=true` 且振动位未锁。这更偏向「整通道配置被恢复/重建为默认档」或「本次创建分支根本没走」，而不是单纯用户逐项手调。[推断]，不能定案。

**结论**：`mUserLockedFields=38` 出现在所谓「全新 id 首次创建」后，在 AOSP 标准行为下**不属于正常首次创建结果**；它证明创建之后（或之前同 id 存在时）发生过写锁事件，但**不能唯一确定**写入方是用户、系统恢复、OEM 还是 app。[查不到]

[DONE] 子节2

## 子节 3：`createNotificationChannel` 同 id 覆盖的字段合并语义

### 3.1 本机能力边界

- [查不到] 本机 `sources/android-35` 中 **没有** `NotificationManagerService.java`，也没有 `PreferencesHelper.java`（`com/android/server/notification/` 为空）。本节结论来自 AOSP 知识、Android 官方开发者文档，以及联网取到的 AOSP gitiles 原文片段；凡属此类均显式标注，**不冒充本机源码事实**。
- 本机可确认的相关事实：`NotificationChannel.setSound` 的 javadoc `:605-606` 写明 "Only modifiable before the channel is submitted to `NotificationManager.createNotificationChannel(NotificationChannel)`"。[源码事实]

### 3.2 AOSP 的实际合并语义

**(a) 调用链**：AOSP master `NotificationManagerService` 把 `createNotificationChannel` 委托给 `mPreferencesHelper.createNotificationChannel(pkg, uid, channel, true /* fromTargetApp */, ...)`（约 `:4083`，来自 AOSP diff 片段）。[AOSP 源码事实·联网]

**(b) 可见的 `PreferencesHelper.createNotificationChannel` 原文片段**（AOSP master，联网）：

```java
if (fromTargetApp) {
    channel.setBypassDnd(r.priority == Notification.PRIORITY_MAX);
}
if (fromTargetApp) {
    channel.setLockscreenVisibility(r.visibility);
    channel.setAllowBubbles(existing != null ? existing.getAllowBubbles()
            : NotificationChannel.DEFAULT_ALLOW_BUBBLE);
    channel.setImportantConversation(false);
}
clearLockedFieldsLocked(channel);
...
channel.setImportanceLockedByCriticalDeviceFunction(r.defaultAppLockedImportance ...);
channel.setOriginalImportance(channel.getImportance());
r.channels.put(channel.getId(), channel);
...
void clearLockedFieldsLocked(NotificationChannel channel) {
    channel.unlockFields(channel.getUserLockedFields());
}
```

[AOSP 源码事实·联网] 注意两点：`clearLockedFieldsLocked` 清的是**传入对象**上的锁位（防止 app 自带锁位入库），而 `existing != null` 分支的存在说明系统确实区分「同 id 已存在」。该 `existing` 分支的**精确逐字代码本机和联网片段均未完整取到**——[查不到]，因此下文对合并语义的判定以官方文档 + AOSP 知识为依据，不虚构语句。

**(c) 官方语义**：Android 开发者文档明确写明：*"Creating an existing notification channel with its original values performs no operation, so it's safe to call this code when starting an app."* [文档事实] 同页还说明：通道提交后重要性及其他行为无法再由代码更改，只能由用户在系统设置中改。[文档事实] `deleteNotificationChannel` 的 API 注释（经 AOSP 社区原文转引）也说明：删除后以同 id 重建，会**沿用删除前全部设置**。[AOSP 知识／文档转引，非本机源码]

**回答二选一问题**：

> app 用相同 id 再次 `createNotificationChannel` 且参数不同（原通道有声、新参数无声），系统会「完全忽略新参数」还是「用新参数覆盖未锁定字段、保留已锁定字段」？

**两者都不精确。准确表述是：以「保留已存在通道」为默认，行为字段（sound / vibration / importance 等）在首次创建后即固化，同 id 再 create 不会用新参数重置；用户锁定位的字段 app 更是无权改动。只有极少数白名单元数据（name/description、尚未入组时的 group、blockable 等）以及"施压级别"的字段可按规则更新，且 app 不能提高 importance、不能推翻用户已设值。** [AOSP 知识] + [文档事实] + [AOSP 源码事实·联网]（白名单字段与 `clearLockedFieldsLocked` 片段）

- 对「原通道有声、新参数 `setSound(null,null)`」这一具体情形：新参数**不会**把旧通道的 `mSound` 清成 `null`。[AOSP 知识] [文档事实] 这与旧通道 dump 中 `mSound=系统默认铃声` 且 SOUND 位被锁、app 反复 `setSound(null)` 无效的现象一致。[推断]
- 因此更接近题目中的「完全忽略新参数」一端，而不是「覆盖未锁定字段、保留已锁定字段」——因为连**未锁定**的 sound/vibration 在首次创建后也不能被 app 通过同 id create 改写。[AOSP 知识]

### 3.3 对「换 id 后新通道首次创建」场景的影响

1. **正面（B′ 的设计依据）**：换 id 后目标通道是真正的新通道，首次 `createNotificationChannel` 时不存在"已存在通道"需要保留，`setSound(null,null)` / `enableVibration(false)` / `IMPORTANCE_HIGH` 按 app 参数落地。[AOSP 知识] 前提是该 id **此前从未被创建过**，且创建后没有发生子节 1/2 所列的写锁事件。[假设边界]
2. **反面（B′ 的命门）**：新 id 一旦被人（用户、系统恢复、OEM）写入过一次，`mSound` / `mUserLockedFields` 就会被 AOSP 的"同 id 不重置"语义**永久固化**：后续再用同一新 id `createNotificationChannel(setSound(null,null))` 也无效，只能再换一个 id。[AOSP 知识] + [文档事实]
3. **不能靠删除再建同一 id 复位**：同 id 重建会继承旧设置。[AOSP 知识／文档转引，非本机源码]
4. **B′ 现场判读**：dump 显示 `wxgroup_silent_v2` 已经是「默认铃声 + 有声有震 + SOUND/IMPORTANCE/VISIBILITY 锁定」，说明这个"新通道"要么并非首次创建（子节 2 解释 5），要么首次创建后立刻被写锁（子节 1 路径 2/3）。无论哪种，AOSP 的同 id 合并语义都决定 **B′ 的静音参数已无法在同一 id 上补救**。[推断] 这个结论只说明"同 id 纠正无效"，不唯一指认覆盖者。[查不到]

[DONE] 子节3

[DONE] ALL
