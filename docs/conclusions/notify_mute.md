## 通知静音（K9 / NotifyMute）
- 通知**只在主进程** `com.tencent.mm` 发出（`:push` 一条没有）→ 装 bootstrap 即可，DB 直接可用。
- **「来自哪个群」只能从 `NotificationItem` 拿**（extras 无 chatroom id，contentIntent 反射取不到）。字段 `d`=id(int)、`h`=talker。`NotificationItem.a` 是**实例方法**（nargs=1）→ `hookAllMethods(cls,"a")` 拿得到 thisObject。取法两级降级：先正则解析 `toString()`（`id: ...,msgId: ...,userName: ...,unreadCount: ...`，功能性格式比混淆字段名稳），失败退字段名。
- `notify(String tag,int id,Notification)`，tag 恒 null，**id 与 `NotificationItem.d` 一一对应且稳定**。
- **响铃和震动不在 Notification 对象上**（sound/vibrate 全 null，defaults=0），是系统按 channel `message_channel_new_id` 播的（`[300,200,300,200]`）→ 改 Notification 字段**没用**，正解是**反射换 `mChannelId` 为自建静音通道**（IMPORTANCE_HIGH + setSound(null) + enableVibration(false)），通知栏/角标/heads-up 全部原样，连提示音也一起掐掉。
- **微信前台不响铃不震动** → 验证必须 `input keyevent 3` 退后台，否则探针抓不到。
- 来电判据（取自真机 VOIP 通知，**放行逻辑未真机验证**）：`category=call` / channel 以 `voip_` 开头 / `fullScreenIntent != null`，任一即可。
- ⚠️ PrefKind 要和读法对齐：TEXT 项（`chat.at_aliases`）**不能**用 `Kv.getList()`（按 JSON 数组解析，裸文本返回空列表）。昵称匹配防短名误伤：`@V` 会命中 `@Verity` → 要求 @alias 后不是字母/数字/下划线/短横线。
- 验证静音类功能**必须做反向对照**（关开关再发一条，确认恢复响+震）。

## 「群消息免打扰」开关落点 —— ✅ **已找到（2026-10-18 01:40 setter 栈回溯全链打通）**

> 之前的「仍未找到」标题作废。通过 `MuteSetterProbe` 逐层栈回溯 + dexdump 反编译
> `ChatroomInfoUI`，已拿到**脱离 UI、可后台复用**的免打扰 setter 完整调用链。

**现状**：`feature/AutoDndGroup.kt` 是降级实现 —— 只把该群通知改写到自建静音通道
（`QuietNotificationBridge` 换 `mChannelId`），**微信自己的「消息免打扰」开关没有被打开**。
现在已找到正解 setter，可回填升级为真免打扰（见下方 §真免打扰 setter）。

### ✅ 免打扰 UI 链路（真机栈回溯实锤，2026-10-18）

```
MMSwitchBtn.onTouchEvent（点开关，自绘 View，非 CompoundButton）
  → com.tencent.mm.ui.base.preference.c.onStatusChange(boolean)   [Runnable gg4.o1.run]
    → com.tencent.mm.ui.base.preference.x.a()                     [Preference 框架分发]
      → ChatroomInfoUI.onPreferenceTreeClick(q, CheckBoxPreference) [line 1276]
        → 读 Preference.r(key)=="room_notify_new_msg" 分支
          → 翻转字段 ChatroomInfoUI.I (boolean)
          → ChatroomInfoUI.N6()   [line 149]  刷新 UI（写 SP + setTitleMuteIconVisibility）
```

### ✅ 真免打扰 setter（脱离 UI，`AutoDndGroup` 直接调这三步即可）

反编译 `ChatroomInfoUI.onPreferenceTreeClick` 的 `room_notify_new_msg` 分支，关键字节码：

```kotlin
// 1. 拿「群聊设置工厂」q64/f（服务定位：mz/e.x8(String) → q64/f）
val factory: com.tencent.mm.roomsdk...  // = mz/e.x8(chatroomUsername) 返回 q64/f
// 2. 构造「免打扰」请求，int = 免打扰状态位（1=开 / 0=关，来自翻转后的 ChatroomInfoUI.I）
val req = factory.c(chatroomUsername, muteStateInt)   // q64/f.c:(String,I) -> roomsdk/model/factory/a
// 3. 提交（发 oplog CGI 给服务端，RoomOpLogCallbackFactory + OplogServiceResultEvent）
req.b()                                                 // factory/a.b:()V
```

- 本地缓存落地：`com.tencent.mm.storage.q4.o0(String, h4) -> int`（持久化会话行）、
  `com.tencent.mm.contact.g.c2(int)`（内存态）。
- 关键类/方法锚点（dexdump 已实锤签名）：
  - `Lq64/f;` 方法 `c(Ljava/lang/String;I)Lcom/tencent/mm/roomsdk/model/factory/a;`
    （还有 `b(String,boolean)`、`d(h4,boolean)` 变体）
  - `Lcom/tencent/mm/roomsdk/model/factory/a;` 方法 `b()V`（提交）、`a()V`（取消？）
  - `Lmz/e;.x8(String)` 返回 q64/f 的服务定位
- ⚠️ 待补一坑：`muteStateInt` 的确切取值（1/0 还是位掩码）需真机验证
  （`c(String,int)` 的 int 语义）；`mz/e` 的获取方式（`d94/n0.c(Lmz/e;)` 服务定位）。

### ⛔ 已证伪的候选（2026-10-17 23:xx，零用户操作 SQL + 目录实况，不要重复排查）

### ⛔ 已证伪的候选（2026-10-17 23:xx，零用户操作 SQL + 目录实况，不要重复排查）

| 候选 | 证伪证据 | 结论 |
|---|---|---|
| `userinfo2` per-chatroom key | `sid like '%MUTE%'` 只命中 2 行**全局布尔**：`USERINFO_CHATROOM_MUTE_DETAIL_CLIENT_GRAY_BOOLEAN=true`、`USERINFO_SETTING_CARE_MODE_MUTE_BOOLEAN=false`，value **不含 `@chatroom`** | `CHATROOM_MUTE_DETAIL` 是「群免打扰详情功能」的**客户端灰度开关**，不是每群状态 → 出局 |
| `rconversation`（30 列全） | 开关对照法（`mute1→手动→mute2`）已证明 30 列零变化；`unReadMuteCount>0` 全库 0 行 | 整表出局 |
| `chatroom` 表 | 33 列无 mute 命名列；`roomdata` BLOB(3650B) 的 hex 不含 mute/silent 明文（`instr(lower(hex(roomdata)),'6d757465')=0`） | 出局 |
| `shared_prefs` | `muteprefs` 返回 `files=144` 但**零 `file=` 命中** | 出局 |
| MMKV `notify_key_prefwxid_*` | 文件里 key=`58783890225%40chatroom`（`@` 被 URL 编码为 `%40`），value 是 `com.tencent.preference.notification.key.all.notified.msgid` 的**消息 id 列表** | 是「已通知消息缓存」，不是免打扰开关 |
| MMKV `#mmkv_ringtone_*` | `ringtone_new_calling_info` 每 key 一个 wxid，value JSON 含 `channelId:"voip_norify_channel_silent..."` | 是**来电/voip 铃声**，不是消息免打扰 |

### 🔑 存量分布纠正（一条记错的旧结论）

- 群总数 **752**（chatmode：0→165群 / 1→583群 / 2→4群）；`attrflag`：67108864→587群 / 0→165群。
- **正确对应**：`attrflag=67108864` 的 587 群 = `chatmode=1(583)+2(4)`；`attrflag=0` 的 165 群 = `chatmode=0`。
- ⚠️ 之前记「chatmode(0/1/2) 与 attrflag(0/67108864) 严格一一对应（165/583/4）」是**错的**：chatmode 是 3 值分类（含单聊），attrflag 才是二元的群态位。两者都不是免打扰开关。

### 📌 下一步正解：setter 栈回溯（不是找列）

> ✅ **本节已于 2026-10-18 01:40 完成**：setter 栈回溯全链打通，结论见上文
> 「✅ 免打扰 UI 链路」与「✅ 真免打扰 setter」。以下保留为当初的推理路径，
> **勿当作未办事项**。候选分析原文已归档至 `docs/_archive/免打扰落点候选分析.md`。

本地 SQLite + MMKV + SP 已全排除 → 按 `docs/_archive/免打扰落点候选分析.md` §3.7 的最终兜底：
**hook 公开 Android API 抓 setter**，定位微信设置页「消息免打扰」开关的点击处理类，
直接调它的 setter（不必知道写到哪，省掉「写库→内存缓存不一致→UI 不刷新」全部坑）。

⚠️ 关键事实（22:45 已确认）：聊天信息页三个开关是**无文本自定义 View**（uiautomator
拿不到 text/checked），不一定是 `CompoundButton` → 探针要 hook 多个候选（`CompoundButton.setChecked`
/ `SwitchCompat` / `Switch` / `View.performClick` 兜底），命中即打 16 帧栈。

**已落地的取证探针**（同一 op，新一组 sub；日志前缀 `MUTE`）：
```
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
     --es op label --es sub mutetables                       # 候选表清单（表名/列名命中 + 行数）
     --es op label --es sub mute1 --es user 49543624181@chatroom
   # ⚠️ 然后**在手机上手动打开**这个群的「消息免打扰」
     --es op label --es sub mute2 --es user 49543624181@chatroom   # 自动出 DIFF
     --es op label --es sub mutediff | muteclear
     --es op label --es sub muteprefs --es user 49543624181@chatroom
```
- dump 的是**整行全列**（不是挑出来的几列），两侧按 `(库, 表, rowid) → 列名` 对齐做差分；
  纯判据在 `feature/MuteHuntLogic.kt`（23 条 JVM 单测）。全程只读、`io{}` 后台线程、每个库调用都包 `runCatching`。
- SQL 构造刻意两件事：① 不用任何预设字段名，`where` 是**实测列清单**的 or；
  ② 列一律 `cast(... as text)` —— SQLite 的字符串函数作用在 BLOB 上不转文本，不加 cast 就永远匹配不到 BLOB 里的 wxid。
- ⛔ 表/列名关键词（`MuteHuntLogic.STRONG_TABLE_HINTS` / `WEAK_TABLE_HINTS` / `COLUMN_HINTS`）
  **只是 dump 范围的启发式，不是锚点**。锚点只能来自 `mute1 → 手动开关 → mute2` 的 diff 行（`MUTE CHG`）。

**⛔ 待取证的三件事**（拿到真机输出后填这里，同时可回填 `AutoDndGroup.applyOne`）：
1. 开关真的写在哪个库 / 表 / 列？（diff 行直接给）
2. 值是布尔还是位标志/掩码？（若只翻位，写回时需先读旧值或运算）
3. 是否需要同时改写别处（服务端同步 / 会话行缓存）才会在界面上正确显示？

**还没做但值得做的第三取证路线**（比 diff 更直接，但成本高）：在窗口内 hook wcdb 的写路径
（`execSQL` / `SQLiteStatement.executeUpdateDelete`），用户切开关时打印真实 SQL。
前置条件：确认微信这次写走的是 `execSQL` 还是预编译语句（后者要额外反射取 SQL 文本），
并且必须按 wxid 或开关类列名过滤 + 窗口自动到期，否则刷屏。本轮没做，留到下轮。

### ✅ 第三路线已实现：`op=sns sub=catch`（2026-10-17 lane/mute-hunt）

`probe/SnsHuntProbe.kt` 加了 `allWrites` 开关 + `catchKey` 关键词过滤，**不依赖用户操作时机**：
```
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
     --es op sns --es sub catch --es table "@chatroom" --ei sec 600
# → 用户在手机上随便切哪个群的「消息免打扰」，日志立刻出现该动作写入的表/列
adb shell ... --es op sns --es sub catchoff        # 提前关
```
- **为什么原来会误判成「微信没写库」**：`recordSql` 默认只用 `TABLE_HINTS`(sns*) 与
  `KEYWORDS`(delete/snsid) 过滤，免打扰写的表名两者都不含 → 写操作被**静默丢弃**。
  `catch` 绕开这层过滤（只放行非 query 调用）。
- `--es table <子串>` 为空则记录全部写操作（**只适合 10~20 秒短窗**，否则刷屏）；
  给子串（如 `@chatroom`）则要求 SQL 或绑定参数含该子串 —— 这才是长时间挂着的正确姿势。
- ⚠️ `catch` 自带 `install()` + 开窗，**不需要先 `sub=on`**（只置标志不挂钩子，日志永远空）。
- 窗口上限已从 180s 提到 **600s**（手动操作需要）。
- ⚠️ **`mute1 → mute2` 的 26 秒间隔那次「零变化」是无效结论**：微信当时不在前台
  （`topResumedActivity` 是别的 app），**后台时广播不投递** → 两次 dump 其实都是同一份旧数据，
  用户根本没来得及切开关。下次跑 `mute*` 前先 `am start` 把微信拉前台。

---

