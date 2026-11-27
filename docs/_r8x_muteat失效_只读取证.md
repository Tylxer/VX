# 群聊防@「之前生效现在不静音」现场只读取证（未定案）

> 状态：**只读取证完成，根因未定案**。用户选「先别改，再抓更多证据」；本轮不改代码、不装机、不改开关。
> 机制归属：防@静音落地的根因推理 / Hook 链路 = **Pro 类**，须派 `custom-local:deepseek-v4-pro` 子代理，主会话不自己定性。
> 输出纪律：对着用户可见事实 / 日志行 / 系统 dumpsys 字段写，不对内部计数器写最终判据。

## 1. 用户口述与判据
- 口述：**之前生效、现在不静音了**；触发 = `@全体成员 / @所有人`。
- 用户 AskUserQuestion 回答：①「刚才 01:03 那条实际听到什么」= **没注意/不确定**（⇒ 声音这个最终现象**未得用户确认**，其它都是客观证据）；② 路线 = **先别改，再抓更多证据**。

## 2. 现场客观证据（2026-11-28 00:5x ~ 01:0x，设备 467cf5bc，微信 8.0.48/2588 前台）
### 2.1 判据链路全通（两次复现一致）
- `status`：`registered=true enabled=true matched=1 bridge=installed=true item=true at=true dnd=true muted=1 autoDnd=0 allOnly=true`
- 00:53:11：`QUIET silent channel created` → `QUIET AT muted id=-1521557830 talker=测试群聊`
- 复现 01:03:10：`QUIET AT muted id=-1521557830 talker=测试群聊`（清日志后重新抓）
- 01:04 快照：`status ... matched=2 muted=2`；extras `channel=message_channel_new_id ticker=路过人间: @所有人 title=测试群聊`
- ⇒ 模块加载 / Hook 挂载 / talker 解析 / groupInScope / isAtMe / `mute()` 调用 **全部通过**；`AT muted` 日志 = `mute()` 里 `muted.incrementAndGet()` 之前两步成功。
- ⚠️ `AT muted` **只证明判据通过 + 通道改写函数返回 true**，不证「系统实际静音」。

### 2.2 静音通道系统侧字段（dumpsys notification --noredact）
```
NotificationChannel{mId='wxgroup_silent', mImportance=4,
  mSound=content://settings/system/notification_sound,
  mVibrationEnabled=true, mVibrationPattern=null,
  mUserLockedFields=38, mLastNotificationUpdateTimeMs=0, ...}
```
- `mSound=系统默认铃声` + `mVibrationEnabled=true`：通道**当前有声有震**。
- 但代码 `MuteAt.kt:365-384 ensureMuteChannel()` 只在 `getNotificationChannel()==null` 时 `setSound(null,null)+enableVibration(false)`；通道已存在则**直接 return true，从不纠正**。
- `mUserLockedFields=38`（十六进制 0x26）= bit1+bit2+bit5 —— 具体位含义 / 是否系统重置导致 = **待 Pro 定性**。
- 通知栏侧 `StatusBarNotification(channel=wxgroup_silent ... sound=null vibrate=null)`：Notification 对象本身无声音字段，是否响全靠 channel；OEM（ColorOS/omarea 场景调度器在场）可能有额外处理。

### 2.3 scope / 别名（排除「名单没配」方向）
- `allow` 17 个群全是裸 `xxx@chatroom`，`exclude=[]`，`aliases=[V]`；映射见台账/本文 §4。
- 名单里**没有** `g:`/`t:` 前缀 ⇒ R50 的 H13（前缀不展开）方向**不适用本轮**。

### 2.4 代码历史（排除「我们改坏了」方向）
- `git log -S 'setSound(null'` / `-S 'wxgroup_silent'` 均只有 `d281bc3`（初始版本）；`git diff 2371543..HEAD MuteAt.kt` 静音参数零改动。
- ⇒ 静音参数逻辑**从初始版本至今未变**；「之前生效现在不静音」不能靠「代码改坏」解释。

### 2.5 新变量：两处创建同名通道
- `feature/MuteAt.kt:138,365-384` 与 `core/NotifyMute.kt:85,476-490` **都用 `wxgroup_silent` 这个 id、都 `setSound(null)` 建通道**。
- `core/NotifyMute` 是否现役（`HookEntry` 是否调用）本轮未核 ⇒ 必须查清是否两处竞争创建、谁先谁后。

## 3. 待 Pro 定性的问题（派单范围）
1. `mUserLockedFields=0x26` 精确位含义（对照 AOSP `NotificationChannel` USER_LOCKED_* 常量）；能否解释「通道被改回有声」。
2. 通道创建的时序与竞争：`MuteAt.kt` vs `core/NotifyMute.kt` 谁是现役、有无双写；`ensureMuteChannel` 只在 null 时建是否 = 根因。
3. 「之前生效、现在不静音」的因果链：从日志/dumpsys 能自证的最小根因是什么；哪些仍是假设必须标假设。
4. 判据设计：**对用户可见事实**（响铃/震动 or 系统通道字段）而非内部计数器；正向 + 反向对照各一套；变异验证。
5. 现有 A/B/C 三路线（A 强制纠正通道 / B 换 IMPORTANCE_MIN / C 只清 Notification 字段）各自失效场景与推荐排序；⛔ 本轮不改代码，只出结论。

## 4. allow 17 群昵称映射（规则：提及群聊用昵称）
58783890225=集团军⚽️菜馆｜43373831312=集团军枪迷分部⑥副本｜20073811998=集团军枪迷分部⑥｜17537745989=集团军戏迷分部｜50132147768=集团军幻兽！｜43809452760=眼子钻粉总部5群｜49973196994=小宁不是笨蛋｜54018653836=小王的桌游小伙伴们（宁波）｜44408694054=🐈不岛狼人杀交流群｜20926361015=库迪学府壹号狼人杀馆🐺｜48592199109=Funny Club·狼人杀精神股东🐺｜34473014968=壹和·明州府训练营⛺｜55926011763=观城狼人杀🐺｜52179633198=秘境森林🌲狼人杀.｜18777744571=工人阶级大团结之集团君7.4.1｜49543624181=123456｜45424365325=测试群聊
