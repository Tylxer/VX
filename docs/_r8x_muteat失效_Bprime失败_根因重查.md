# R8x「群聊防@静音」B′ 失败 · 根因重查定案（2026-11-28 01:5x）

> 性质：主会话亲核综合。Pro lane 三次 402（余额不足），按 §四② 三选一降档：Lite 做 AOSP 源码查证（`_r8x_muteat失效_Bprime失败_AOSP查证.md`，已逐条亲核），因果链/路线/判据由主会话综合并逐条标 [真机实证]/[源码事实]/[假设]。台账 01:4x 条。
> 提及群聊用昵称「测试群聊」。不写模型路由字样。

## 1. 定案摘要（一句话）

**B′ 换 id 失败的真正原因不是「app 建通道参数错」，也不是「旧 id 残留」，而是：全新通道在首次创建的那一刻就被系统/OEM 写成「默认铃声 + 有震动 + SOUND/IMPORTANCE/VISIBILITY 锁位38」——app 传的 `setSound(null,null)` 被覆盖，且在 AOSP「同 id 行为字段首次后固化」语义下，同一 id 再也无法纠正。** 换任何 id 都会在首次创建瞬间重演，故 B′ 路线整体作废。

## 2. 因果链（每条标证据等级）

| # | 环节 | 等级 | 依据 |
|---|---|---|---|
| 1 | 「测试群聊」@全体成员通知到达，hook `notify` 触发 | [真机实证] | `QUIET ADND observe ... -> HANDLED` |
| 2 | 判据链全通（作用域/@判定/开关），调 `mute()` | [真机实证] | `QUIET AT muted talker=测试群聊`、matched/muted=1 |
| 3 | `rewriteChannel()` 把 mChannelId 改成 `wxgroup_silent_v2` | [真机实证] | NotificationRecord `channel=wxgroup_silent_v2` |
| 4 | 新通道首次创建，代码传 `setSound(null,null)+enableVibration(false)` | [源码事实] | MuteAt.kt:365-385；`setSound` 语义 NotificationChannel.java:608-611 |
| 5 | **创建瞬间即被写成「默认铃声 + 有震动 + lock=38」** | [真机实证] | `mCreationTimeMs=01:40:32`=首条@时刻；`mSound=系统默认铃声`、`mVibrationEnabled=true`、`mUserLockedFields=38` |
| 6 | app 的静音参数未落地；AOSP 同 id 固化语义使后续同 id 纠正无效 | [源码事实]+[文档事实] | 官方「创建已存在通道不操作」；Lite 子节3 |
| 7 | 系统按通道配置发声震动 | [真机实证] | `mSoundNotificationKey`/`mVibrateNotificationKey` 指向此通知；NotificationRecord `mIsInterruptive=true`、`mVibration=Composed{...200ms}` |
| 8 | **用户听到响铃 + 震动** | [真机实证·用户耳朵] | 用户明确「有声音有震动」 |

## 3. 三个必须说清的关键判定（来自 Lite 查证 + 主会话时间证据）

1. **不是 app 参数错**：`(默认铃声, null属性)` 这个组合在 AOSP 里必属显式 `setSound(uri,null)` 或等价写入——`setSound(null,null)` 只能产出 `(null,null)`（NotificationChannel.java:608-611）。代码传的没错，是被改写。[源码事实]
2. **不是历史残留**：新通道 `mCreationTimeMs=01:40:32` = 第一条 @ 时刻，是真正首次创建。[真机实证]
3. **不是「首次创建应有 lock=0」的正常结果**：AOSP 标准首次创建 `mUserLockedFields` 应为 0（ natives `clearLockedFieldsLocked` 会清锁位）。真机 38 = SOUND+IMPORTANCE+VISIBILITY，必是创建时/创建瞬间外部写入。[源码事实]+[推断]

> **唯一无法从日志自证的一环**：究竟是「OEM 定制的通道创建钩子」还是「AOSP 系统设置路径被触发」写入的 lock=38。Lite 联网查到 AOSP `ImportancePreferenceController.onPreferenceChange` 有逐项命中四特征的标准路径，但其前提是「旧重要性<DEFAULT 被调到≥DEFAULT」，与本案 IMPORTANCE_HIGH 直建不符。[查不到] 诚实标注为 [假设·OEM 通道创建钩子]，不冒充已证明。

## 4. 路线重设计（B′ 已废，逐条评估）

| 路线 | 能否绕过本根因 | 失效场景 | 代码量/风险 | 用户可见体验 | 评价 |
|---|---|---|---|---|---|
| **C 清 Notification 对象 sound/vibrate/defaults** | ❌ 不能 | **已排除**：现场 NotificationRecord `flags=SHOW_LIGHTS\|AUTO_CANCEL`，本就无 DEFAULT_SOUND/DEFAULT_VIBRATE；声震完全由通道决定 | 小 | 保留 | **出局** |
| **B IMPORTANCE_MIN 专用通道** | ✅ 能（系统层保证不响不震，且不依赖被锁的 sound 字段） | 通道 id 若同样被 OEM 钩子套 importance=4 则需实测；无横幅/角标 | 中/低 | ❌ 通知不弹横幅（静音≠不打扰场景可接受） | **首选候选** |
| **A 强制纠正通道（同 id）** | ❌ 不能 | SOUND 位已锁 + 同 id 固化语义，app 覆盖无效（Lite 子节3） | 小 | 保留 | **出局** |
| **B′ 换 id** | ❌ 已证伪 | 首次创建即被套声震+lock38 | — | — | **出局** |
| **D hook 系统 NotificationManagerService.enqueue / 通道创建** | ✅ 大概率能（在发声决策前拦截） | 深度 hook 系统进程，风险高、版本耦合强、可能触发微信/系统稳定性问题 | 大/高 | 可控 | **备选（B 失败后再上）** |
| **E DND/免打扰白名单机制** | ⚠️ 部分 | 项目已有 DndWindow/AutoDndGroup，但免打扰是按会话静音整个通知，粒度/时序需重新设计；且 DND 可能被 OEM 同样限制 | 中/中 | 通知可能被完全抑制 | 备选 |
| **F 借用系统已有真正静音通道** | ⚠️ 未坐实 | 需微信本进程内有 sound=null 的通道；本轮查 `default_message` 分散多 app，无证据表明微信有一个静音通道可借 | 小/低 | 保留 | **暂不成立**，需再取证 |

### 推荐排序与理由

1. **首选 B（IMPORTANCE_MIN 专用通道）**：唯一一个「不依赖 app 可改的 sound 字段、由系统语义保证静音」的轻量方案。代价是通知不弹横幅——对「防@静音」这个诉求是可接受的（用户要的是不响，不是必须看到横幅）。**必须先做一次只读小实验**：建一个 IMPORTANCE_MIN 测试通道，看它的 mImportance 是否被 OEM 钩子也套成 4（若被套则 B 也废，直接上 D）。
2. **备选 D（hook 系统通知入队/发声决策）**：B 失败后的正解，能精确拦截声震决策，但深度高、风险大，需单独评审。
3. **E / F**：作为补充探索，不作为主线。

## 5. 判据设计（对用户可见事实，不靠 internal counter）

### 正向（修后静音）
- **系统字段**：`dumpsys notification --noredact` 中新通道 `mSound=null` 且 `mVibrationEnabled=false` 且（B 路线）`mImportance=0/最低`；**且** `mSoundNotificationKey`/`mVibrateNotificationKey` 不再指向该通知。
- **用户听感**：昵称「测试群聊」发 @全体成员，用户确认**未响未震**。
- **通知可见性**（B 路线）：确认通知栏里通知仍在（静音≠吞通知），横幅退化是预期代价。

### 反向（关开关复现响铃，证明因果可逆）
- `kvset chat.mute_at_enabled=false` 回读 false → 同群再发 @全体成员 → 用户确认**响铃恢复** → 恢复开关回读 true。
- 互斥关系：正向「未响」与反向「响铃」必须互斥；若关开关后仍不响，说明静音不是本功能落地（可能是系统 DND 等外部因素），判据失败。

### 三组变异验证（用于单测/逻辑层）
- V1：把「是否静音通道」判定从严格等于改成 contains → 子串用例必须红。
- V2：把通道 importance 常量从 MIN 改回 HIGH → importance 判据用例必须红。
- V3：把「开关关闭时不静音」分支反转 → 反向对照用例必须红。
- ⚠️ 每组变异先确认**有语义**（生产侧真调用），再跑；静态推演不算数。

## 6. 当前处置与下一步

- **不改码、不装机、不碰开关**。B′ 代码留树无害（新通道等效旧通道），但路线作废，**不回退**（避免制造无谓 diff）。
- **下一步（需用户同意）**：先做 B 路线的只读小实验——建一个 IMPORTANCE_MIN 测试通道，dumpsys 看 OEM 是否也把它套成 importance=4/有声。用事实决定 B 是否可行，再谈落码。这样避免在「OEM 会不会套 MIN 通道」这个未知上盲目下注。

[DONE] 因果链 / [DONE] 关键判定 / [DONE] 路线重设计 / [DONE] 判据设计 / [DONE] 处置 / [DONE] ALL

---

## 附：「为什么以前可以、现在不行」——时间线取证（2026-11-28 02:0x，回答用户追问）

> 用户追问直击前版定案漏洞：若「系统/OEM 建通道必套声震+lock38」是常态，模块从 10 月初始版本起就该失效，不该有「以前生效」期。本节补时间维度。

### 已查证的时间/版本事实
| 事实 | 值 | 来源 |
|---|---|---|
| 微信版本/更新 | 8.0.48 (2588)，`lastUpdateTime=2026-11-26 08:14` | `dumpsys package com.tencent.mm` |
| 系统 | Android 16 / ColorOS `PJD110_16.0.3.500(CN01)`，构建 2026-01-14 | getprop |
| 模块静音参数 | 从初始版本 d281bc3（10月）至今**从未改过** | 前轮 git log -S 查证 |
| 防@端到端验证 | R56（11-16）只验编译/单测/`probe judge` 文本判据，**无真机听感**；最初 notify_mute.md（10-25）有「换通道后通知栏/角标/heads-up 原样、提示音掐掉」的真机结论，但未留反向对照原始日志 | docs/_r56 / docs/conclusions/notify_mute.md |
| 微信自身通道 | `message_channel_new_id` 有**两份**：主 user(0) `sound=null/lock=25`，分身 user(999 MultiApp) `sound=系统铃声/lock=0` | dumpsys notification |

### 分析（分等级，不冒充已证明）
1. **[真机实证] 微信 11-26 更新是关键时间变量**：用户报障「之前生效现在不静音」的时间点紧邻微信更新；同日（11-26）还在排查 R86c「转发图片变模糊」，两个报障同天出现，强烈提示微信 8.0.48 这次更新改变了通知/媒体行为。**但「更新改了通知通道行为」是 [假设]，无新版本 dex/log 逐字对比证据。**
2. **[源码事实] 早期能静音的机制成立过**：10-25 真机结论明确「响铃震动是系统按 channel 播、换 mChannelId 到自建静音通道即可掐掉提示音」——当时的 `message_channel_new_id` 是 `sound=null`（仅震动 pattern），模块自建通道 `setSound(null)` 无声无震，换过去就静音。这条机制在当时的微信+系统版本下成立。
3. **[真机实证] 现在失效的机制**：自建通道（wxgroup_silent / _v2）无论新旧 id，首次创建瞬间就被写成「默认铃声+有震+lock38」；微信自己的 `message_channel_new_id` 也出现「有声版本」（分身 user）。⇒ 现在声震来源不再只是「通知落到哪个 channel」这一条静态链，系统/OEM 在通道创建/落库阶段介入更深。
4. **[查不到] 微信 11-26 具体改了什么**：无新旧版本 dex 对比、无更新前通道快照，无法坐实「微信更新导致通道被重建/被系统接管」这一环。诚实标 [假设]。
5. **[推断] 「以前可以」的证据强度**：用户主观体验「以前生效」可信（不否定），但项目文档里**没有 10 月那次静音的反向对照原始日志**（关开关复现响铃），R56 也只验文本判据。所以「以前真静音了」缺硬日志支撑，只有 10-25 的结论性文字 + 用户记忆。

### 对路线的影响（修正前版定案）
- 前版把根因写成「系统/OEM 建通道必套声震」是**过度概括**：若必套，10 月不可能成功。更准确的表述是：**「某个 11-26 前后的环境变化（微信更新为首嫌疑）使通道创建/落库行为改变，导致模块这套『自建静音通道』方案在当前环境失效」**。
- 这**不改变**路线结论（C/A/B′ 仍出局、B 待实验、D 备选），但改变了叙事：不是「方案从来就有缺陷」，是「方案的环境前提变了」。
- 新增待查变量：分身 user(999) 的微信是否也装了模块、是否参与通知收发——可能影响「有时静音有时响」的观感，待用户确认是否观察到分身消息。

---

## 附2：微X / 同类模块的实现方式参考（2026-11-28 02:0x，用户要求）

> 用户问「参考一下微X的实现方式」。微X本体（com.fkzhang.wechatxposed）闭源、业务 dex 在加密 assets（R72 定案静态不可解，本轮再验证：可见 classes.dex 2.4MB 里 `静音/通知/channel/mute` 相关业务字符串 0 命中，只有 Kotlin stdlib）。但搜到一个**同类且更直接的开源参考**：LSPosed 模块库收录的 `SystemedNotificationBlocker`（xjyzs，2026-10 活跃，功能字面就是「屏蔽指定群聊的 @所有人 消息」），已 clone 到 `%TEMP%\snblock` 只读研究，核心 `MainHook.kt` 197 行。

### 核心实现（源码原文，MainHook.kt:38-107）
```kotlin
// 1. hook 系统框架，不是微信进程
if (lpparam.packageName == "android" || lpparam.packageName == "system") {
  val notificationManagerClass = Class.forName(
    "com.android.server.notification.NotificationManagerService", false, lpparam.classLoader)
  XposedBridge.hookAllMethods(notificationManagerClass, "enqueueNotificationInternal",
    object : XC_MethodHook() {
      override fun beforeHookedMethod(param: MethodHookParam) {
        val pkg = param.args[0] as? String ?: ""
        if (pkg == "com.tencent.mm") {
          val notification = param.args[6] as Notification
          if (notification.channelId == "message_channel_new_id") {
            val extras = notification.extras
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            var text = extras.getString(Notification.EXTRA_TEXT) ?: ""
            param.args[5] = System.currentTimeMillis().toInt() // 防覆盖/防撤回
            // @所有人 → 名单命中 → 直接吞掉通知
            if ("@所有人 " in text) {
              var shouldBlock = !blacklistMode
              for (group in groupsSet) if (group in title) shouldBlock = blacklistMode
              if (shouldBlock) param.result = null    // ← 通知根本不入队（屏蔽，不是静音）
              else if (removePrefix) extras.putCharSequence(EXTRA_TEXT, text.removePrefix("@所有人 "))
            }
            // 接龙消息静音 → 改到系统已有通道（不自建！）
            if (": #接龙" in text && "1. " in text && shouldMuteGroupNote[title] > 0)
              XposedHelpers.setObjectField(notification, "mChannelId", "reminder_channel_id")
          }
        }
      }
    })
}
```

### 与我方现役方案的三个决定性差异
| 维度 | 我方 MuteAt.kt | SystemedNotificationBlocker |
|---|---|---|
| hook 位置 | 微信进程 `NotificationManager.notify` | **系统进程** `NotificationManagerService.enqueueNotificationInternal` |
| @所有人 处置 | 改 mChannelId 到自建静音通道（保留通知、静音） | **`param.result = null` 直接吞通知**（通知不弹、不响、不震） |
| 静音通道 | 自建 `wxgroup_silent(_v2)` ← 被套声震+lock38 | **借用系统自带 `reminder_channel_id`** ← 不自建、无被套风险 |
| 抗微信更新 | 弱（微信混淆/通道变化就失效，11-26 已中招） | 强（只依赖 NMS 稳定的 `enqueueNotificationInternal` + 通知 extras，与微信内部无关） |
| 群名单匹配 | talker（wxid/群名复杂映射） | `title`（通知标题=群昵称，简单 contains） |

### 对 R8x 的启示（分等级）
1. **[源码事实]** 「借用系统已有静音通道 `reminder_channel_id`」直接绕开我方 B′ 的死结——不自建通道就没有「首建被套声震+lock38」。这是比 B（自建 IMPORTANCE_MIN）更省事、更可靠的路线。
2. **[源码事实]** 系统级 hook 能直接 `param.result=null` 吞通知——若用户接受「@全体成员的通知干脆不弹」，这是最彻底的解决（连响带通知一起没了）。
3. **[推断]** 我方要保留「通知在栏但不响」，可借鉴其「借用系统通道」思路但不吞通知：hook 点移到系统 NMS、把 mChannelId 改成 `reminder_channel_id`（或一个系统自带的无声通道），既绕开自建通道污染、又不依赖微信内部。
4. **[风险/待验证]** ①我方模块当前是 LSPosed 作用域挂微信，改挂「系统框架」需要模块结构改动（新增 hook 入口 + LSPosed 勾选系统框架 + 可能需要重启手机）；②`reminder_channel_id` 在 ColorOS 16 上是否存在、是否真静音，需只读 dumpsys 核实；③`param.result=null` 在 `beforeHookedMethod` 里吞通知的写法在 Android 16 上是否仍有效，需实测。
5. **[结论]** 新增路线 G（借鉴微X同类实现）：hook 系统 NMS + 借用系统静音通道，作为接下来优先评估的方向（比 B 的「自建 MIN 通道」更贴合已验证的实现）。**本轮不改码/不装机。**

### 附2 补充：路线 G 可行性只读核实（同轮，主会话）
1. **❌ `reminder_channel_id` 在本机非静音**：ColorOS 16 上两份均为 `mSound=content://settings/system/notification_sound / mVibrationEnabled=true`（imp=3 lock=1d / imp=2 lock=18）。⇒ 微X同类模块借用的这个通道在原生 Android 可能静音、在本机**不成立**，直接抄 id 无效。附2 §启示1 需据此修正：**「借用系统通道」这条路在本机要先找真正 sound=null 的通道 id，不能假设 reminder_channel_id 静音。**
2. **⚠️ hook 入口需结构性改动**：`HookEntry.kt:74` 写死 `if (lpparam.packageName != VersionGate.HOST_PACKAGE) return`，只挂微信。改挂系统框架（`android`/`system`）需新增 hook 入口分支 + LSPosed 勾选系统框架 + 可能重启手机，属于独立开发项。
3. **✅ 系统级 hook 的「吞通知」思路仍成立**：`enqueueNotificationInternal` 的 `beforeHookedMethod` 里 `param.result=null` 是纯拦截，不依赖通道/声震/微信内部。若用户接受「@全体成员干脆不弹通知」，这是最彻底的路线（G′），但牺牲「通知栏保留」。
4. **本机确有一个 sound=null 的系统通道**：`permission reminders` / `Appointment_reminder`（`mSound=null / mVibrationEnabled=false / importance=2-3 / lock=0`），但分属其它 app uid（如 systemui/permission controller），微信通知能否落到别的 uid 的通道 id 上 = **高度存疑**（Android 通道按 uid+package 隔离），未验证，不作为推荐。
5. **修正后的路线评估**：G′（系统 NMS hook + `param.result=null` 吞通知）> B（自建 IMPORTANCE_MIN，但需先验证 OEM 是否套 MIN）> D（hook 系统发声决策，成本最高）。**G 的「借 reminder 通道」子方案因本机通道有声而作废。**
