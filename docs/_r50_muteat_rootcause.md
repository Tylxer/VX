# R50「群聊防止被@未生效」纯静态根因推理 + 判据设计

> 状态：**本轮为静态推理，未经真机验证**。所有「假设」均待真机探针证伪，不构成「已确认根因」。
> 边界：本轮**不改代码、不下真机**（不发 adb/install/kvset，不跑场景）。改动方案留待用户授权后的开发轮次。
> 结论来源纪律：dex 里有 SQL 字符串 ≠ 该业务调用它；「A 推 B」时序只认 invoke 地址单调递增 / 真机日志时间戳；结论对着可见事实写，不对着内部状态计数器写。

---

## 1. 完整生效链路分段

> 每段给出「这一环断了，用户看见的现象是什么」。链路按调用顺序排列，任一环断裂即整链失败。

### 1.1 进程闸门（HookEntry.kt:66-74）
- 流程：`handleLoadPackage` 里先判 `packageName == com.tencent.mm`，再判 `isFirstApplication`，再 `VersionGate.isMainProcess(processName)`（= `processName == "com.tencent.mm"`）。
- 断了的现象：微信 `:push` / `:tools` / `:appbrand` 等子进程里通知也会发（注释说明通知只在主进程发，但子进程本身不会执行任何 hook）→ 所有功能无声无息，日志里没有 `package loaded, process=...` 或只有 `skip process=... (not main)`。

### 1.2 VersionGate 签名/版本闸门（HookEntry.kt:98-117，VersionGate.kt:73-91）
- 流程：`bootstrap` 里读宿主 versionName/versionCode/signature → `VersionGate.check`。白名单只有一条 `8.0.48 / versionCode=2588 / signatureSha256=0fe4...`。任一不匹配 → `Reject` → 打 `DISABLED: <reason>` 后 `return`，**整个 bootstrap 不跑**。
- 断了的现象：微信不崩、一切功能（含防止被@）全失效，日志里有一行 `DISABLED: version xxx not whitelisted ...` 或 `signature mismatch: ...`。这是「整个模块都没生效」级别，不是「只有防止被@没生效」。

### 1.3 bootstrap 里 MuteAt.install 挂载（HookEntry.kt:282-285）
- 流程：bootstrap 后半段 `runCatching { MuteAt.install(lpparam) }`。紧接着 DndWindow.install(289)、AutoDndGroup.install(294)。
- 断了的现象：若这一步抛异常被 runCatching 吞掉，日志有 `MuteAt install failed: <异常类名>`，AT 功能静默失效，但 DND / 新群免打扰可能照常。

### 1.4 QuietNotificationBridge.install（MuteAt.kt:166-185）
- 流程：`itemHooked = installItemHook(lpparam)`（挂 NotificationItem 的 `a`）；`notifyHooked = installNotifyHook()`（`XposedBridge.hookAllMethods(NotificationManager::class.java, "notify", ...)`）。**若 notifyHooked 为 false，整个 install 返回 false** ⇒ `MuteAt.registered = false`。
- 断了的现象：日志出现 `QUIET bridge disabled: NotificationManager.notify unavailable`，或 `QUIET AT install registered=false`。通知照常响+震，探针 `status` 显示 `registered=false`。注意：即便 itemHooked=false，只要 notifyHooked=true，install 仍返回 true（`itemHooked` 不影响返回），AT 分支会跑到但 talker 恒 null（见 1.5）。

### 1.5 NotificationItem 解析 id→talker（MuteAt.kt:215-244, 393-412）
- 流程：`findClass("com.tencent.mm.booter.notification.NotificationItem")` → 找实例方法 `a`(nargs=1) → hook 其 before → `parseItem(self)` 两级降级（先正则 `id:...,userName:...` 解析 toString，失败退反射字段 `d`=id、`h`=talker）→ `remember()` 存 `talkerById[id]`。
- 断了的现象：
  - 类找不到：日志 `QUIET item hook skipped: NotificationItem not found`，`itemHooked=false`。此时 notify hook 仍挂着，onNotify 里 `talkerOf(id)` 恒 null ⇒ `groupInScope` 第一行 `room == null → false` ⇒ AT 永不静音。**这是「静默失效」高发点**：用户看不见任何报错，通知照常响。
  - `a(nargs=1)` 找不到：日志 `QUIET item hook skipped: instance a(nargs=1) not found`，同上。
  - 正则/字段都解析失败：`talkerById[id]` 存成 null（三态），`talkerOf` 返回 null，同上。

### 1.6 notify hook 触发 onNotify（MuteAt.kt:246-259, 277-300）
- 流程：微信每条通知 `NotificationManager.notify` before 时进 `onNotify`。取 Notification 对象、取 id，`rememberDump` 记快照，然后按序判定。
- 断了的现象：`onNotify` 没被触发（notify hook 没挂上，见 1.4）→ 连 `extras` 快照都记不下，日志无 `QUIET` 相关行。

### 1.7 onNotify 判定顺序（MuteAt.kt:287-299）
- 顺序（**写死，前两个命中会 return，AT 分支被跳过**）：
  1. `dndEnabled && DndWindow.shouldMute(notification)` → `mute(..., "DND", ...)` return
  2. `Kv.getBool(CHAT_AUTO_DND_NEW_GROUP) && isAutoDndTalker(talker)` → `mute(..., "GRP", ...)` return
  3. `muteAtEnabled && MuteAt.shouldMute(notification, talker)` → `mute(..., "AT", ...)`
- 断了的现象：用户想验证「防止被@」，但 DND 时段开着或该群已被登记为新群免打扰 → 通知被 DND/GRP 分支先静音了，日志里是 `QUIET DND muted` 或 `QUIET GRP muted`，**没有** `QUIET AT muted`。用户看到「防止被@好像没生效（因为是我关 DND 才响的？）」，实则是另一功能抢先静音，AT 根本没被单独验证到。

### 1.8 shouldMute 三层判据（MuteAt.kt:59-77，MuteAtLogic.kt:103-114, 91-96）
- 三层：
  1. `registered && Kv.getBool(CHAT_MUTE_AT_ENABLED)` — 开关与挂载
  2. `groupInScope(talker, nameOf(talker), allow, exclude)` — talker 必须 `@chatroom` 结尾；**排除名单优先**（命中直接 false）；允许名单空=全生效，非空只命中生效；元素是 `xxx@chatroom` 或精确群名
  3. `isAtMe(text, aliases)` — 只认正文 `@所有人` / `@all`(忽略大小写) / `chat.at_aliases`；别名留空则 @具体昵称一律不静音
- 断了的现象：
  - 开关 false → 不静音（最直接）
  - talker 不是 `@chatroom` 结尾（含 talker 解析为空）→ 不静音
  - 群在排除名单 → 不静音（哪怕也在允许名单）
  - 允许名单非空但目标群不在内 → 不静音
  - 正文无 `@所有人`/`@all`/别名，且别名留空 → 不静音

### 1.9 静音落地（MuteAt.kt:334-389）
- 流程：`mute()` 拿 `HostContext.app()` context → `ensureMuteChannel`（Android O+ 建 `wxgroup_silent` 通道，IMPORTANCE_HIGH + setSound(null) + enableVibration(false)）→ `rewriteChannel` 反射改 `Notification.mChannelId` → 开 1.2s 窗口吞 `SystemVibrator.vibrate`。
- 断了的现象：
  - `HostContext.app()` 为 null → `mute()` 直接 return，日志无任何输出（静默）。响+震照旧。
  - `ensureMuteChannel` 失败（API<26，或 `createNotificationChannel` 异常）→ `hotLog("AT mute failed id=...")`
  - `rewriteChannel` 失败（反射 `mChannelId` 找不到且回退遍历也失败）→ 同上 `mute failed`
  - 通道被系统降级：用户手动在系统设置里把 `wxgroup_silent` 通道改成「重要/静音」之外的级别，或系统对某通道静音 → 换通道后仍响/震。

---

## 2. 失效模式清单

> 分类：配置 / 使用方式 / hook挂载 / 判定逻辑 / 落地 / 环境。

| # | 可能原因 | 用户可见现象 | 归类 |
|---|---|---|---|
| M1 | `chat.mute_at_enabled` 默认 "false"，用户未开 | 一切@消息照常响+震，探针 `enabled=false` | 配置 |
| M2 | `chat.at_aliases` 留空，用户测的是「@具体昵称」而非「@所有人」 | @某人的消息照响，@所有人/`@all` 才静音 | 配置 |
| M3 | 目标群在排除名单（排除优先，哪怕也在允许名单） | 该群@消息照响，其他群正常静音 | 配置 |
| M4 | 允许名单非空，但目标群不在内（或群名没配对/没带 `@chatroom`） | 只有名单内群静音，目标群照响 | 配置 |
| M5 | 群名匹配依赖 GroupNameSource，冷启首条通知数据源未装载 → `nameOf` 返回 null → 群名元素退回 talker-only（预期降级，非 bug） | 冷启后第一条@消息若群名没配在名单里则不静音，下一条恢复 | 判定逻辑 |
| M6 | DND 时段开启且在时段内 → onNotify 第 1 分支抢占，AT 被跳过 | 日志只见 `DND muted`，不见 `AT muted` | 判定逻辑 |
| M7 | 新群自动免打扰开着且该群已登记 → onNotify 第 2 分支抢占 | 日志只见 `GRP muted`，不见 `AT muted` | 判定逻辑 |
| M8 | `NotificationItem` 类找不到（微信升级混淆改名） | 日志 `QUIET item hook skipped: NotificationItem not found`，AT 永不静音 | hook挂载 |
| M9 | `a(nargs=1)` 实例方法找不到 | 日志 `QUIET item hook skipped: instance a(nargs=1) not found` | hook挂载 |
| M10 | notify hook 失败（`NotificationManager.notify` hookAllMethods 返回空）→ install 整体返回 false → registered=false | 日志 `QUIET bridge disabled: ...`，一切静音功能（含 DND/GRP）全失效 | hook挂载 |
| M11 | talker 解析为空（NotificationItem 正则/字段都取不到） | 日志 `mapStatus` 里 `known=0`；`AT muted` 永不出现，通知照响 | hook挂载 |
| M12 | 微信在前台验证 → 前台本就不响不振，误判为「没生效」 | 发@消息后没响没震，但这是微信前台固有行为，不是模块失效 | 使用方式 |
| M13 | 装包后未重载（只 force-stop 一次或没重启），跑的是旧代码 | 日志无新探针输出，或 `loaded()` 判定为旧代码 | 环境 |
| M14 | 静音通道 `wxgroup_silent` 被系统降级/用户手动改过 | 换通道后仍响或仍震，日志仍打 `AT muted`（判定命中但落地无效） | 落地 |
| M15 | `HostContext.app()` 为 null（Application.attach 未完成就收到通知，极早冷启） | `mute()` 直接 return，日志无 `AT muted` 也无 `mute failed`，通知照响 | 落地 |

---

## 3. 根因假设（按「可证伪性 + 零验证成本」排序）

> 排序原则：零验证成本 = 一条 `dev.py probe kvget` / `dev.py probe quiet ...` 就能出结论，不改代码、不装包、不跑场景。
> 判据一律**对着用户可见事实 / 日志特征**写；⛔ 不对着内部计数器（`matched` / `muted` / `bound` 等数字）写判据。
> 互斥判据写成「1 或 3」这种互斥总判，不并列。

### H1：开关没开（默认 false）
- 可证伪判据：读 `chat.mute_at_enabled` 当前值。若为 "false" → 假设成立（这是默认值，用户没开）；若为 "true" → 假设证伪，转入 H2/H3。
- 最小探针：`python tools/dev.py probe kvget --key chat.mute_at_enabled --clear`
- 成立日志特征：`PROBE kv chat.mute_at_enabled = false`
- 证伪日志特征：`PROBE kv chat.mute_at_enabled = true`

### H2：别名留空，且用户测的是「@具体昵称」
- 可证伪判据：读 `chat.at_aliases`。若为空串，且用户报障时说的是「群里@我的名字没静音」（不是@所有人）→ 假设成立；若为空但用户测的是 `@所有人`，或非空且已填昵称 → 转 H3/H9。
- 最小探针：`python tools/dev.py probe quiet at aliases --clear`（或 `probe kvget --key chat.at_aliases`）
- 成立日志特征：`aliases=[]`（空列表）
- 证伪日志特征：`aliases=[V, ...]` 非空

### H3：排除名单/允许名单作用域（排除优先 + 允许非空只命中生效）
- 可证伪判据：读 `scope` 与目标群 talker。判据写成互斥总判：「**1**（allow 非空但目标群不在内）或 **2**（目标群在 exclude）或 **3**（两者都不是，作用域不是根因）」。可结合 `map` 探针确认目标群 talker 有没有被解析到。
- 最小探针：`python tools/dev.py probe quiet at scope --clear`；`python tools/dev.py probe quiet at map --clear`
- 成立日志特征：`allow=[...] exclude=[...]` 显示目标群落在 exclude，或 allow 非空但不含目标群/群名
- 证伪日志特征：`allow=[] exclude=[]`（全放行），或 allow 含目标群且 exclude 不含

### H4：DND / 新群自动免打扰分支抢占（onNotify 顺序）
- 可证伪判据：互斥总判——「**1**（`chat.dnd_enabled` 为 true 且当前在时段内）或 **2**（`chat.auto_dnd_new_group` 为 true 且该群已被登记）或 **3**（两开关都关，抢占不是根因）」。发一条真@消息后看日志里出现的是 `DND muted` / `GRP muted` 还是 `AT muted`。
- 最小探针：`python tools/dev.py probe kvget --key chat.dnd_enabled --clear`；`python tools/dev.py probe kvget --key chat.auto_dnd_new_group --clear`；`python tools/dev.py probe quiet dnd window --clear`
- 成立日志特征：`chat.dnd_enabled = true` + `window ... active=true`；或 `chat.auto_dnd_new_group = true`；发消息后日志 `QUIET DND muted` / `QUIET GRP muted`（无 `AT muted`）
- 证伪日志特征：两开关均 false；发消息后日志出现 `QUIET AT muted`（说明 AT 分支正常走到）

### H5：微信前台验证造成的假阴性（使用方式）
- 可证伪判据：用户报障时是否已退后台。判据——「**1**（发@消息时微信在前台）或 **2**（已退后台仍响+震）」。前台不响不振是微信固有行为（notify_mute.md 已实证），不能据此判「功能失效」。
- 最小探针：无探针，属使用方式核对。配合 `dev.py q 'QUIET'` 看静音发生与否（需退后台重发一次）。
- 成立日志特征：前台发消息时无 `QUIET AT muted`（因为静音通道本就不响，且通知链路仍在跑，但响震观察不到）
- 证伪日志特征：退后台重发后，日志出现 `QUIET AT muted id=... talker=xxx@chatroom`，且物理上不响不振 → 功能其实是好的

### H6：装包未重载（需冷启两次）
- 可证伪判据：日志里是否有新代码特征（如 `loaded()` 的 `logclear` 判定，或 `QUIET ADND installed pid=<新pid>`）。判据——「**1**（日志无新代码输出 / pid 与上次相同）或 **2**（有新代码输出，重载没问题）」。dev.py 的 `restart()` 双次 force-stop 就是为此。
- 最小探针：`python tools/dev.py q 'QUIET' 40` 看 install 行与 pid；或用 dev.py 的 `loaded()` 流程
- 成立日志特征：无 `QUIET AT install registered=...` / `QUIET bridge installed ...` 新行；`logclear` 返回 `unknown op=logclear`（旧代码）
- 证伪日志特征：出现 `QUIET bridge installed itemHooked=...` / `QUIET AT install registered=...`（新代码已在跑）

### H7：NotificationItem 类或 `a(nargs=1)` 找不到
- 可证伪判据：日志里有没有 `QUIET item hook skipped`。判据——「**1**（`NotificationItem not found`）或 **2**（`instance a(nargs=1) not found`）或 **3**（无 skip，item hook 已挂上）」。若 1/2 成立，则 talker 恒 null，AT 永不静音。
- 最小探针：`python tools/dev.py q 'QUIET item hook' 20`（冷启日志里）；或 `probe quiet at status --clear` 看 bridge 段 `item=`
- 成立日志特征：`QUIET item hook skipped: NotificationItem not found` 或 `... instance a(nargs=1) not found`
- 证伪日志特征：`QUIET bridge installed itemHooked=true`

### H8：notify hook 失败 → install 返回 false
- 可证伪判据：日志里有没有 `QUIET bridge disabled: NotificationManager.notify unavailable`。判据——「**1**（有 disabled 行，registered=false）或 **2**（无，notify hook 已挂）」。若成立，DND/GRP/AT 三个静音功能全失效。
- 最小探针：`python tools/dev.py q 'QUIET bridge' 20`；或 `probe quiet at status --clear`
- 成立日志特征：`QUIET bridge disabled: NotificationManager.notify unavailable`；`QUIET AT install registered=false`
- 证伪日志特征：`QUIET bridge installed itemHooked=...` 且 `QUIET AT install registered=true`

### H9：talker 解析为空
- 可证伪判据：`mapStatus` 里 `known=`（已解析到 talker 的数量）与 `tracked=`（已见 id 数）对比。判据——「**1**（tracked>0 但 known=0，id 拿到却解析不出 talker）或 **2**（known>0，talker 能解析）」。结合 `grpname/grplist` 探针确认目标群 talker。
- 最小探针：`python tools/dev.py probe quiet at map --clear`
- 成立日志特征：`tracked=N known=0`（N>0）
- 证伪日志特征：`tracked=N known>0`

### H10：GroupNameSource 懒加载冷启首条降级（群名匹配暂退 talker-only）
- 可证伪判据：读 `grpstat` 的 `ready/count`。判据——「**1**（`ready=false count=0`，数据源没装载完）或 **2**（`ready=true count>0`，群名表已就绪）」。若 1，且用户名单里用的是**群名**（非 `xxx@chatroom`），冷启首条@消息不会静音（预期降级，下一条恢复）。
- 最小探针：`python tools/dev.py probe quiet at grpstat --clear`；必要时 `probe quiet at grpname --clear` 触发重装后复看
- 成立日志特征：`GROUPNAME stat ready=false ... count=0`
- 证伪日志特征：`GROUPNAME stat ready=true ... count=N`（N>0）；或 `grplist` 显示目标群名已在表内

### H11：静音通道被系统降级
- 可证伪判据：日志已出现 `QUIET AT muted`（判定+改写都跑了）但设备仍响/震。判据——「**1**（有 `AT muted` 但物理上仍响/震，落地无效）或 **2**（无 `AT muted`，判定未命中，回 H1~H10）」。需真机在系统通知设置里核对 `wxgroup_silent` 通道级别。⛔ 本轮不下真机，此假设仅登记为「日志出现 AT muted 后仍响震」时再核对的落点。
- 最小探针：`python tools/dev.py q 'QUIET AT muted' 20`
- 成立日志特征：有 `QUIET AT muted id=...` 但用户仍听到响/震
- 证伪日志特征：有 `QUIET AT muted` 且物理上已不响不振

---

## 4. 反向对照设计

### 4.1 自变量唯一
- 只翻转 `chat.mute_at_enabled`（true ↔ false），其余一律不动。用 `kvset` 改开关后**必须冷启两次**（`dev.py restart`），因为 MuteAt.shouldMute 第一层闸门是每次调用都实时读 `Kv.getBool`（不是启动时读一次）——理论上热刷即可，但为排除模块重载不确定性，仍按冷启两次走。
- ⚠️ 临时全放行时**必须同时关掉**另外两个会抢占 AT 分支的开关：
  - `chat.dnd_enabled = false`（否则时段内 onNotify 第 1 分支先 return）
  - `chat.auto_dnd_new_group = false`（否则已登记群 onNotify 第 2 分支先 return）
  否则「关 mute_at 开关」后通知仍可能被 DND/GRP 静音，得出「开关无效」的**假阴性**。

### 4.2 「关了开关，钩子照挂」的观测意义
- 事实：`QuietNotificationBridge.install` 有 `installed` 幂等锁，MuteAt / DndWindow / AutoDndGroup 三者**共用同一条 hook**。关 `chat.mute_at_enabled` 只让 `MuteAt.shouldMute` 第一层闸门返回 false，**notify hook 仍然挂着、onNotify 仍然在跑**。
- 观测意义（两点）：
  1. 关开关后若恢复响+震 → 证明「静音」确实是 AT 分支落地，且静音通道改写是**可逆、无持久副作用**的（不是改了 Notification 对象字段就永久失效那种）。这是正向对照的核心证据。
  2. 关开关后若**仍**静音 → 说明静音不是 AT 分支干的，而是 DND（第 1 分支）或 autoDnd（第 2 分支）抢先 return 造成的 → 直接定位到失效模式 M6/M7，把「防止被@」与「另外两个静音功能」归因拆开。
- 结论：反向对照里「关了 AT 开关」这一动作的价值，不在于关掉 hook，而在于**排除抢占分支 + 证明 AT 落地可逆**。

---

## 5. 复位边界

- **本轮不改代码、不下真机**：本文件产出即止，不授权任何 `adb` / `install` / `kvset` / 跑场景 / 改源码 / 改现有文档 / commit / 碰设备动作。
- **假设复位流程**：按第 3 节顺序，用最小探针逐条证伪后，把「下一个未证伪的假设」提为当前工作假设：
  1. H1 读开关 → 若 true 转 H2/H3（读 aliases + scope）
  2. H2/H3 均排除（别名已填、作用域放行）→ 转 H4（查 DND/autoDnd 开关与时窗）并真发一条@消息看 `DND/GRP/AT` 哪种 `muted` 出现
  3. H4 排除（两开关关、仍无 `AT muted`）→ 转 H7/H8（看 `item hook skipped` / `bridge disabled`）
  4. H7/H8 排除（itemHooked=true、registered=true）→ 转 H9（`map` 探针 known/tracked）
  5. H9 排除（known>0）→ 转 H10（`grpstat` ready）或回 H5/H6（前台假阴性 / 未重载）
  6. 全部判定层排除、且日志已出现 `AT muted` 但仍响震 → 才进入 H11（通道降级，需真机核对系统通知设置）
- ⛔ **禁止**把「需真机验证」写成「已确认根因」：本文件所有「假设成立」均为「待真机探针证伪」的候选，非定论。
- 改动方案（如「改某行代码」「调 hook 点」）**不在本轮范围内**，留待用户授权后的开发轮次。

---

[DONE]
