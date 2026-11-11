# R49 · R-C 复用写时拦截 · lane E 只读能力盘点

> 视角：现有代码里已有哪些探针/hook 能直接拿来用，还缺什么。
> 独立 lane，不读、不写 `docs/_r49_rc_probe_design.md`（lane D 探针矩阵设计）。
> 全程只读源码，唯一写入文件 = 本文件。结论附 `文件:行号`；不确定标 `[推断]` / `[需真机]`。

## 待答清单（I1~I7）

- [ ] I1. 现有探针全清单（类名 / 文件:行号 / 存活接线 / 记录什么 / 输出 TAG / 开关限流）
- [ ] I2. 广播入口清单（`op=` / `sub=` / `sub2=` 全表，落函数 / 只读 / 是否写库）
- [ ] I3. R42 六个钩子逐个核（wcdb 方法名签名 / before-after / 改参返回 / 死代码复核）
- [ ] I4. 事件账本现状（blockstat/trace 字段 / 能否回答"写事件命中三天外行" / 缺哪些字段）
- [ ] I5. 只读 SQL 探针真实通道（snsDb 独立库复核 / pragma/hex/行数钳制）
- [ ] I6. 缺口清单（观测能力 × 现有能力差 / 纯配置可跑 vs 必须新增代码）
- [ ] I7. 复用陷阱与证据分级表

[START]

---

## 全局日志底座（I1/I7 共用）

- 所有探针日志统一走 `LogX`：`util/LogX.kt:19` android TAG=`"WXGroup"`，`MAX_LINES=5000` 环形（`:22`），`reset()`（`:108`，`op=logclear` 调）。下表「输出 TAG」实际是**行前缀**（`BLOCK ` / `SNSG ` / `SQLX ` / `PREFIX ` / `RV ` / `SNS ` …），不是独立 android tag。
- 部分取证探针**另写文件** `/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt`（`probe/SnsHuntProbe.kt:53`、`probe/RevokeHuntProbe.kt:53`、`probe/MenuHuntProbe.kt:66`），绕开 5000 行环形。
- 接收器是**动态注册在微信进程**：`HookEntry.kt:336` `EntryReceiver.register(context)`，`EntryReceiver.kt:111-133`（`RECEIVER_EXPORTED`/`FLAG_EXPORTED`）、`:32-38`（注释：Android 8+ 静态注册收不到隐式广播、需 RECEIVER_EXPORTED、后台无前台 Activity 弹窗会 BadTokenException）。⇒ 广播只在**微信进程存活且模块已加载**时投递；UI 类探针还需前台 Activity（`:716-727`、`ActivityResultRelay.kt:174`）。

## I1 · 现有探针全清单

### 三条旧口径更正（重点核）

| 项 | 真实现状 | 证据 |
|---|---|---|
| `SnsSqlSpy` | **全仓 0 命中**，不存在这个类/字符串；`op=snsg sub=sqlspy` 也不存在 | Grep `SnsSqlSpy\|sqlspy\|srcmap` 全 `wxgroup/` 仅命中无关串（如 `snsprefix`）；`EntryReceiver.kt:384` `else -> "SNSG unknown sub=$sub"` |
| `SnsGuard` 内嵌 `blockstat`/`trace` | **不在 SnsGuard**，在 `SnsBlockProbe`（`:718` stat / `:773` trace），经 `op=snsg` 路由；`SnsGuard` 只有 `stat()` | `EntryReceiver.kt:378`、`:381`、`SnsGuard.kt:1635` |
| `sqlUnmatched` 计数 | 声明 `:127`，**累加在 `noteSql` `:593`**（改 `sourceType` 但非 `-3/-9`、非自己补位的 execSQL），输出在 `stat()` `:738` | `SnsBlockProbe.kt:127`/`:593`/`:738` |

### A. 朋友圈 / SNS 域探针与统计类（逐个）

> 「存活接线」= 是否有 `EntryReceiver` op/sub 入口调用它；「开关/限流」含去重、封顶、时间窗。

| 类 | 文件:行号 | 存活接线 | 记录什么 | 输出前缀 | 开关 / 限流 |
|---|---|---|---|---|---|
| `SnsGuard` | `feature/SnsGuard.kt:65` | ✅ 常驻 6 钩子（`install()` `:132`）；`op=snsg sub=stat`→`stat()` | 6 个 wcdb 写方法钩子；`snsDb` 句柄；`& -9` 改写；`stat()` 只报 3 个开关位 + `snsDb!=null`（`:1635-1637`，**无计数**） | `SNS ` / `SNSG ` / `BLOCK fail` | `refresh()` `:192-205`：`ANTIREVOKE_SNS` / `_BLOCK` / `_SQLBLOCK_OFF` / `ALBUM_FIX_OFF`；`cfg()` TTL `:207`；句柄捕获 `CAPTURE_MAX_CALLS=20000` `:982` |
| `SnsBlockProbe` | `feature/SnsBlockProbe.kt:38` | ✅ `op=snsg sub=blockstat\|trace\|block`（`EntryReceiver.kt:375/378/381`）；被 6 个钩子回调 | 事件账本 `Event`（`:167`）+ 4 通道独立计数（update/insert/delete/execSQL `:68-133`）+ execSQL 原文采样 + 30s 采样器 `trace` | `BLOCK ` / `TRACE ` / `BLOCK w2` | **纯内存不写盘**（`:28`，进程死即清零）；`RING=50`（`:43`）、`EV_SHOW=10`（`:46`）、`HIT_LOG_GAP_MS=5s`（`:49`）、`STAT_GAP_MS=60s`（`:52` `maybeStat`）、`SQL_SAMPLE_MAX=60`（`:55` 去重+封顶 `:617`）、`SQL_LOG_CHARS=1500`（`:58`）、`TRACE_EVERY_MS=30s`/`TRACE_MAX=60`（`:61-62`）；`switch(on\|off)` `:703`（写 Kv） |
| `SnsPrefixProbe` | `feature/SnsPrefixProbe.kt:33` | ✅ `op=snsprefix`（`EntryReceiver.kt:665`） | 读/写 `SnsInfo.content` 的 `[已删除]\n` 前缀；`simdrop`/`setsrc2` 写 `sourceType`；`self()` 查 self wxid | `PREFIX ` | **部分 sub 写微信库**：`prefix/strip/restore`（`:115/:151/:176`）、`simdrop`（`:223` `sourceType=0`）、`setsrc2`（`:256` `sourceType=2`）走 `update("SnsInfo",ContentValues,"snsId=?",[id])`（`:19`）；`probe/stat/self` 只读。无节流 |
| `SnsBitLogic` | `feature/SnsBitLogic.kt:29` | ⛔ 纯函数（`:4` 不 import Android/Xposed） | `decide`/`sqlClear`/`looksLikeSourceWrite`/`isOwnFix`/`afterShape` 判据，**不记录任何东西** | 无 | 无（无日志、无 TAG、无开关、无接线） |
| `SnsSqlRewrite` | `feature/SnsSqlRewrite.kt:37` | ⛔ 纯字符串 | `& -9`→`& -1` 改写、`rangeKey`（`:93` 抽 snsId 分页区间）、`shouldBlock` | 无 | 无 |
| `SnsSql` | `feature/SnsSql.kt:22` | ⛔ 纯字符串 | `delete from SnsInfo ... snsId=?` 识别、按序号取 bindArgs | 无 | 无 |
| `SnsHuntProbe` | `probe/SnsHuntProbe.kt:51` | ✅ `op=sns`（`EntryReceiver.kt:296`）；**临时**，开窗才装钩子 | 挂 **wcdb+AOSP 全部读写方法 + SQLiteStatement 执行入口**（`:181-196`），记 `getPath()`/表名/完整 SQL/ContentValues/24 帧调用栈/schema，写文件 `wxgroup_dbg.txt`（`:53`） | `SNS `（`:44`）+ 文件 | `sub=on\|off\|flush\|clear\|stat\|tables\|sample\|index\|ddl\|stmtdiag\|catch`（`:210-270`）；窗口 10~600s（`:70`/`:213`）；`MAX_LINES=260`/`1500`（`:57/:66`）；`FRAMES=24`（`:55`）；到期自动 flush+卸载（`:135`）；`TABLE_HINTS=sns/snsinfo`（`:161`）；`sqlWriteDepth` 去重（`:131`） |
| `RevokeHuntProbe` | `probe/RevokeHuntProbe.kt:51` | ✅ `op=revoke`（`EntryReceiver.kt:289`）；临时 | 挂 `update/updateWithOnConflict/delete/insert/insertWithOnConflict/insertOrThrow/execSQL`（`:127-132`），记 SQL+调用栈，写文件（`:53`） | `RV `（`:163/:313`）+ 文件 | `sub=on\|off\|flush\|stat\|clear`（`:163-180`）；时间窗自动卸载（`:89`）；写方法候选类全挂（`:196/:231`） |
| `SnsViewProbe` | `probe/SnsViewProbe.kt:23` | ✅ `op=snsv`（`EntryReceiver.kt:327`） | 朋友圈 View 树 / 带 snsId 节点 | `SVP`/`SVPR`/`SVPD`/`SVPI`/`SVPT`（`:30+`） | `--ei n 240`（`EntryReceiver.kt:329`）；View 树行数上限（`tree`） |
| `SnsVideoDecProbe` | `feature/SnsVideoDecProbe.kt:38` | ✅ `op=viddec`（`EntryReceiver.kt:676`） | 视频解密 api/dec/key/try | [推断] `VIDDEC` | 无显式限流 |
| `MomentsExport` | `feature/MomentsExport.kt:163` | ✅ `op=moments`（`EntryReceiver.kt:456`） | 扫当前 `SnsInfo` 导出；`stat()` `:1813`；`vidCopied` `AtomicLong` `:383`（`:1593` 累加、`:1828` 输出） | `ME `/`MO` [推断] | 分页/配额钳制（`MomentsLogic.clampProbeN`） |
| `MomentsAuto` / `MomentsSyncTrigger` | `feature/MomentsAuto.kt:63` / `core/MomentsSyncTrigger.kt:72` | ✅ `op=moments` / `op=msync`（`EntryReceiver.kt:456/478`） | `stat()`（`MomentsAuto.kt:670` / `MomentsSyncTrigger.kt:188`） | `MA `/`MSYNC` [推断] | TTL 缓存；`sync` 触发后台同步 |
| `AntiRevoke` | `feature/AntiRevoke.kt:79` | ✅ `op=ardump`/`archeck`（`EntryReceiver.kt:547/613`） | 锚点命中计数 `AtomicLong`（`:231`）、`dumpRecent`（`:939`）；挂 `updateWithOnConflict`（`:371`）/`insertWithOnConflict`（`:382`） | `AR`/`ARDUMP`/`ARCHK` | `refresh()`；窗口/去重 |
| `VoiceGuard` | `feature/VoiceGuard.kt:51` | ✅ 常驻（HookEntry 装） | 挂 `deleteWithOnConflict`（**空集=死代码**，见 I3）与 `delete`（`:133/:136`），拦 voiceinfo 删除 | `VG ` | `enabled=ANTIREVOKE_MSG`（`:147`） |

> 类名含 `Stat`/`Trace`/`Spy` 的**类**：仅 `RestoreStats`（`data/BackupIO.kt:45` data class）、`SyncStats`（`data/Friend.kt:44` data class）——都是备份/同步结果结构，非探针；**无 `Spy` 类、无 `Trace` 类**；`trace` 只是 `SnsBlockProbe` 的函数名（`:773`）。

### B. `probe/` 目录其余 UI / 取证探针（紧凑）

| 类 | 文件:行号 | 角色 | 前缀 | 是否挂 wcdb 写方法 |
|---|---|---|---|---|
| `ChatListProbe` | `probe/ChatListProbe.kt:34` | 会话列表 View 树 | `CLP `（`:67`） | 否 |
| `ChatOpenProbe` | `probe/ChatOpenProbe.kt:25` | 打开聊天 Fragment | `CHATOPEN`（`:41`） | 否 |
| `ConvProbe` | `probe/ConvProbe.kt:32` | 会话行/窗口/菜单 | `ConvProbe`/`CONVLP`/`WIN`/`MENU` | 否 |
| `ConvTimeProbe` | `probe/ConvTimeProbe.kt:27` | 会话时间字段 dump（`:34`） | [推断] `CT` | 否 |
| `DexProbe` | `probe/DexProbe.kt:19` | DexKit 类枚举 | `DexProbe:`（`:26`） | 否 |
| `FilterProbe` | `probe/FilterProbe.kt:23` | ListView/Filterable | `FP `（`:55`） | 否 |
| `LabelHuntProbe` | `probe/LabelHuntProbe.kt:64` | 标签数据源/只读 SQL/表结构 | `LABEL`（`:66`） | **是**（`catcher()` 挂 DB 方法 `:290`；只读 SQL 白名单 `:486`） |
| `MenuHuntProbe` | `probe/MenuHuntProbe.kt:58` | 菜单 hook 点+栈+View 树 | `MH `（`:119`）+ 文件 `:66` | 否（挂 Activity/View 方法 `:240`） |
| `MenuProbe` | `probe/MenuProbe.kt:23` | 长按联系人 | `MenuProbe`/`LONGPRESS`（`:70`） | 否 |
| `MuteSetterProbe` | `probe/MuteSetterProbe.kt:49` | 免打扰 setter 栈/状态 | `DNDREAD`/`dndset`（`:123`） | 否（挂 setter 签名回调 `:417`） |
| `SendProbe` | `probe/SendProbe.kt:37` | 发送接口签名枚举/测试发消息 | `SEND `（`:174`） | 否 |
| `UiProbe` | `probe/UiProbe.kt:26` | Fragment/Activity/RecyclerView | `UiProbe`/`ACT `/`FRAG `（`:76/:124/:176`） | 否 |
| `SnsHuntProbe` / `SnsViewProbe` / `RevokeHuntProbe` | 见 A 表 | 见 A 表 | 见 A 表 | 前两者见 A，`RevokeHuntProbe` **是** |

> 结论：**能观测「wcdb 写库事件」的现成能力只有 3 套**——① `SnsGuard` 常驻 6 钩子（最小、只在 `sourceType` 时记账）；② `SnsHuntProbe`（`op=sns`，临时窗口、SQL+栈+文件，最全）；③ `RevokeHuntProbe`（`op=revoke`，临时窗口，偏撤回）。`LabelHuntProbe` 也挂 DB 方法但为标签数据源取证、带只读 SQL 白名单。

## I2 · 广播入口清单（`EntryReceiver.kt`）

两套 `when(op)` 分派：**ACTION_PROBE**（`EntryReceiver.kt:160-695`，`op=` 主探针入口）与 **ACTION_BACKUP**（`:155-157` → `backup()` `:741`，备份入口）。另有 `ACTION_BAR/MODE/FILTER/PANEL`（`:138-159`、`:699`）不用 `op`。`sub`=一级子命令（`getStringExtra("sub")`），`sub2`=二级（`EXTRA_SUB2` `:75`），`n`/`sec`/`id`/`q`/`val`/`sql`/`table`/`user`/`key` 等为参数。

### 特别核对（用户点名的分支，按真实现状，非旧文档）

| 入口 | 真实现状 | 证据 |
|---|---|---|
| `op=snsg sub=stat` | ✅ `SnsGuard.stat()`：只 3 个开关 + `snsDb!=null`，**无计数** | `EntryReceiver.kt:349`、`SnsGuard.kt:1635` |
| `op=snsg sub=blockstat` | ✅ `SnsBlockProbe.stat()`：5 行计数 + 最近 10 事件（≤16 行） | `EntryReceiver.kt:378`、`SnsBlockProbe.kt:718` |
| `op=snsg sub=trace` | ✅ `SnsBlockProbe.trace(firstLong(intent,"id"))`：每 30s 点查 1 个 snsId，最多 30min | `EntryReceiver.kt:381`、`SnsBlockProbe.kt:773` |
| `op=snsg sub=sqlspy` | ❌ **无此分支** → `SNSG unknown sub=sqlspy` | `EntryReceiver.kt:348-385` 的 `when(sub)` 无 `sqlspy`；全仓无 `SnsSqlSpy` 类 |
| `op=snsg sub=srcmap` | ❌ **无此分支** → `SNSG unknown sub=srcmap` | 同上 |
| `op=snsg sub=dump` | ❌ **无此分支** → `SNSG unknown sub=dump`（`dump` 只在 `op=hintbadge sub=dump` `:603`、`MuteSetterProbe` `:97`、`ForceForward` `:1213`） | `EntryReceiver.kt:384` |
| `op=snsg sub=block` | ✅ `SnsBlockProbe.switch(sub2=on\|off)`，只切 Kv 开关（不写微信库） | `EntryReceiver.kt:375`、`SnsBlockProbe.kt:703` |
| `op=sql` | ✅ `SnsGuard.runReadOnlySql(q, n)`，`n` 默认 60 但被 `clampProbeN` 钳到 50；仅 `select`/只读 `pragma` | `EntryReceiver.kt:654-661`、`SnsGuard.kt:1268` |
| `op=snsprefix` | ✅ `SnsPrefixProbe.run(sub,id)`；**真实 sub 比注释多**：`probe\|prefix\|strip\|restore\|stat\|simdrop\|setsrc2\|self`（注释 `:664` 只写了前 4 个） | `EntryReceiver.kt:665-672`、`SnsPrefixProbe.kt:44-57` |

### ACTION_PROBE 全部 `op=` 分支表

> 「只读」= 不写微信库；「写微信库」列标 ⚠️ 的是会改微信数据/发消息/写模块自己的库。带 `io{}` 的表示丢后台线程（`EntryReceiver.kt` 多处注释：主线程直接读库会 ANR）。

| op | 子命令 / 关键参数 | 落到哪个函数 | 只读 | 写微信库 |
|---|---|---|---|---|
| `logclear` | — | `LogX.reset()` `:166` | 否（清模块日志） | 否 |
| `convtime` | — | `ConvTimeProbe.dump()` `:170` | ✅ | 否 |
| `kvset` | `key`,`val` | `Kv.putString` + 7 个 `refresh()` `:176-208` | 否（写模块 Kv） | 否。【硬约束：本 lane 禁碰 kvset】 |
| `voicebak` | `sub=backup\|restore\|chain\|ls`,`sql` | `VoiceBackup.*` `:215-238` | `chain/ls` ✅ | 否（读写模块备份文件） |
| `room` | `sub=sync\|list\|schema\|row`,`w` | `ChatroomSource.*` `:244-266` | `list/schema/row` ✅ | 否（`sync` 写模块 GroupDb） |
| `menuhunt` | `sub=on\|off\|flush\|tree\|win\|clear`,`sec` | `MenuHuntProbe.command` `:270-273` | ✅（挂临时 UI 钩子） | 否 |
| `mute` | `sub=on\|off\|stat\|dump\|dndread\|dndset\|dndhook`,`sub2` | `MuteSetterProbe.command` `:280-285` | `stat/dndread` ✅ | ⚠️ `dndset` 改微信免打扰设置 |
| `revoke` | `sub=on\|off\|flush\|stat\|clear`,`sec` | `RevokeHuntProbe.command` `:289-292` | ✅ | 否 |
| `sns` | `sub=on\|off\|flush\|stat\|tables\|sample\|clear\|index\|ddl\|stmtdiag\|catch`,`table`,`sec` | `SnsHuntProbe.command` `:296-302` | ✅ | 否 |
| `label` | `sub=...`,`table`,`sql`,`n`,`user`,`sec` | `LabelHuntProbe.command` `:307-318` | ✅（`sql` 只读白名单 `:486`） | 否 |
| `snsv` | `sub=tree\|tagged\|ids\|recycler\|tagdump`,`n`,`cls` | `SnsViewProbe.*` `:327-340` | ✅ | 否 |
| `snsg` | `stat\|cmts\|mid\|mpath\|hit\|block\|blockstat\|trace`, 未知→`SNSG unknown sub` | 见上「特别核对」 | ✅ | 否（`block` 仅写 Kv） |
| `snsc` | — | 仅日志「功能已物理删除」`:398-401` | ✅ | 否 |
| `quiet` | `sub=at\|pat\|dnd\|grp`,`sub2` | `PatBlock/MuteAt/DndWindow/AutoDndGroup.probe` `:420-439` | ✅（纯内存） | 否 |
| `redpacket` | `sub` | `RedPacket.probe` `:442-444` | ✅ | 否 |
| `moments` | `sub=stat\|env\|act\|refresh\|load\|rvmethods\|export\|img\|scan\|path`,`n`,`id` | `MomentsAuto.probe` / `MomentsExport.probe` `:456-468` | `stat` ✅ | 否（`export/img` 写文件；`refresh/load` 撩前台） |
| `msync` | `sub=sync\|watch\|unwatch\|stat` | `MomentsSyncTrigger.*` `:478-497` | `stat` ✅ | 否（`sync` 触发同步请求） |
| `forward` | `sub`,`talker`,`val` | `ForceForward.probe` `:504-521` | `stat` ✅ | ⚠️ `fileb` 复制文件+入网；`pick` 开选择器 |
| `broadcast` | `sub=start\|pause\|resume\|stop` | `MassSendPage.probe` `:528-530` | `stat` ✅ | ⚠️ `start` **真发消息**（`:525-526`） |
| `send` | `sub=probe\|test`,`val` | `SendProbe.command` `:537-540` | `probe` ✅ | ⚠️ `test` 真发一条测试消息（`:535-536`） |
| `ardump` | `sub`,`n` | `AntiRevoke.dumpRecent` `:547-557` | ✅ | 否 |
| `arins` | `talker`,`text` | `RevokeRescue.insertHint` `:562-568` | ✅ | ⛔ **已废**：恒返回 0、不写库（`:559-561`） |
| `ardel` | `id` | `RevokeRescue.deleteHints` `:575-586` | 否 | ⚠️ 删微信 `message` 表历史负 msgId 行（`:570-573`） |
| `hintbadge` | `sub=stat\|dump\|fake\|unfake`,`id` | `HintBadge.*` `:599-609` | `stat/dump` ✅ | 否（`fake/unfake` 改内存 View） |
| `archeck` | `talker`,`n` | `AntiRevoke.checkHide` `:613-620` | ✅ | 否 |
| `kvget` | `key` | `Kv.getString` `:624-640` | ✅ | 否 |
| `who` | `val` | `ContactSqlSource.who` `:644-650` | ✅ | 否 |
| `sql` | `q`,`n` | `SnsGuard.runReadOnlySql` `:654-661` | ✅ | 否 |
| `snsprefix` | `sub`,`id` | `SnsPrefixProbe.run` `:665-672` | `probe/stat/self` ✅ | ⚠️ `prefix/strip/restore/simdrop/setsrc2` 写 `SnsInfo` |
| `viddec` | `sub=api\|dec\|key\|try`,`arg`,`off`,`len` | `SnsVideoDecProbe.run` `:676-685` | ✅ [推断] | 否 |
| `hdimg` | — | `AutoHdImg.stat()` `:688-689` | ✅ | 否 |
| （已删除）`cdn`/`lucky`/`convtop`/`marktest`/`listgeom`/`dragarm` | — | 注释说明随探针删除（`:388-389`、`:446-452`、`:691-694`） | — | — |
| `else` | — | `LogX.w("ENTRY PROBE unknown op=$op")` `:695` | — | — |

### ACTION_BACKUP 的 `op=` 分支（`backup()` `:741`）

| op | 参数 | 函数 | 只读 | 写库 |
|---|---|---|---|---|
| `export` | — | `BackupIO.export` `:742-752` | 否 | 写备份文件（模块 GroupDb 导出） |
| `list` | — | `BackupIO.scan` `:754-764` | ✅ | 否 |
| `restore` | `file`,`replace` | `BackupFlow.restore` `:766-789` | 否 | ⚠️ 写模块 GroupDb（非微信库） |
| `plan` | `file`,`replace` | `BackupFlow.preview` `:796-818` | ✅（dry-run，`:792`「一个字节都不写库」） | 否 |
| `selftest` | — | （`:826`） | ✅ [推断] | 否 |
| `saf` | `sub2=launch\|cancel\|import` | `ActivityResultRelay` / SAF `:858-910` | 否 | 否（SAF 文件） |

[DONE] I1
[DONE] I2

## I3 · R42 六个钩子逐个核

挂载类统一是 `com.tencent.wcdb.database.SQLiteDatabase`（`XposedHelpers.findClass` `SnsGuard.kt:135-140`），全部经 `XposedBridge.hookAllMethods` 注册（`install()` `:132-190`）。注释 `:178-181` 明确「**常驻**的只有 delete / execSQL / updateWithOnConflict / insert(WithOnConflict) 六个」——是 6 次**注册**（`updateWithOnConflict` 挂了 after+before 两个回调，`insert` 有 `insertWithOnConflict`+`insert` 两个重载）。

| # | 方法 | 签名（wcdb 形态） | 钩子对象 / 注册行 | before / after | 改参数 / 返回值 |
|---|---|---|---|---|---|
| 1 | `delete` | `delete(String table, String whereClause, String[]\|Object[] whereArgs)`（`:816-817`） | `deleteGuard` `SnsGuard.kt:149`，定义 `:214-219` | **before**（`beforeHookedMethod` `:215` → `onDelete` `:819`） | ❌ 不改。`onDelete` `:819-831` 只 `noteDelete` 计数（旁证） |
| 2 | `execSQL` | `execSQL(String sql)` / `execSQL(String sql, Object[] bindArgs)`（`:833`） | `sqlGuard` `:152`，定义 `:221-226` | **before**（`:222` → `onExecSql` `:834`） | ✅ **会改** `param.args[0]`：方案 B 把 `& -9`→`& -1`（`applySqlRewrite` `:900-914`，`:909` 写回），其余原样透传（`:852`） |
| 3 | `updateWithOnConflict`（after 半场） | `updateWithOnConflict(String table, ContentValues values, String whereClause, String[] whereArgs, int conflictAlgorithm)` [推断：wcdb 标准 5 参，参 `SnsGuard.kt:356-364` 按 `a[0]=table`/`a[1]=values` 取值] | `snsUpdateGuard` `:158`，定义 `:236-247` | **after**（`:237` → `onSnsUpdateAfter` `:809`） | ❌ 不改。`onSnsUpdateAfter` `:809-812` **空函数**（补位随乙方案删除，仅保留注册）。`SnsBlockProbe.kt:209` 亦称其为空函数 |
| 4 | `updateWithOnConflict`（before 写时拦截） | 同上 | `snsBlockGuard` `:163`，定义 `:297-307` | **before**（`:298` → `onSnsUpdateBefore` `:356`）+ **after**（`:303` → `onSnsUpdateBlockAfter` `:751`） | ✅ **会改** `param.args[1]`（ContentValues）：before 摘 `sourceType` 键或写回保底值（`applyBlock` `:530-562`；`CvApi.remove/putInt` `:311-321`）；after 只 `readBits` 回查+记账，不改 |
| 5 | `insertWithOnConflict` | `insertWithOnConflict(String table, String nullColumnHack, ContentValues values, int conflictAlgorithm)` [推断] | `snsInsertGuard` `:168` | **after**（`:250` → `onSnsInsertAfter` `:784`） | ❌ 不改。`countInsertSource` `:798` 自注「只计数，不写库、不改 values」 |
| 6 | `insert` | `insert(String table, String nullColumnHack, ContentValues values)` [推断] | `snsInsertGuard` `:171`（复用同一回调） | **after**（同上） | ❌ 不改 |
| （非常驻） | `rawQuery` | `rawQuery(String, String[]\|Object[])` | `installCapture` `:176`，定义 `:985-1016` | **before**（`:998`） | ❌ 不改；只捕获 `snsDb` 句柄，拿到即 `unhookCapture()` 自我卸载（注释 `:174-177`、`:956-970`） |

> 兜底：无任何 `SnsGuard` 钩子改 `param.result`/返回值（对比 `VoiceGuard` 会 `param.result=FAKE_ROWS` `VoiceGuard.kt:186`，那是语音域，非 R-C）。

### 两个专项复核

- **「wcdb `SQLiteDatabase` 无 `deleteWithOnConflict` ⇒ 死代码」仍成立**：证据在 `feature/VoiceGuard.kt:125-135`——注释明确「wcdb 的 `SQLiteDatabase` **没有** `deleteWithOnConflict`（与 AOSP 不同，已用 `tools/_r21c_verify_sql.py` 核方法表）。写库相关方法只有 delete/deleteDatabase/insert/insertOrThrow/insertWithOnConflict/update/updateWithOnConflict/execSQL×3/rawQuery×2。所以下面那个 hook 返回空集、不会生效，日志只会是 `VG installed hooks=delete`」，`:133` 的 hook 仅保留「兼容未来版本」，真正生效的是 `:136` 的 `delete`。`SnsGuard` 根本未挂 `deleteWithOnConflict`（6 钩子见上表）。⇒ 结论**静态仍成立**；`[需真机]` 才能 100% 锁定 8.0.48/2588 这一具体包的方法表，但项目内无反例，且即便未来补了该方法，R-C 判据走的是 `updateWithOnConflict`/`execSQL`，不受影响。
- **`execSQL` 钩子存在**（`SnsGuard.kt:152`/`:221`/`:834`）；**`sqlUnmatched` 在 `SnsBlockProbe.noteSql` `:593` 累加**，链路由 `SnsGuard.onSnsSql` `:884` 调用 `noteSql`；输出在 `stat()` `:738`。注意它只统计「改 `sourceType` 但既非 `&-3` 也非 `&-9`、也非自己补位」的 execSQL（`:589-597`），**不是「所有写事件」计数**。

### ⇒ I3 核心结论：观测「三天外行上有没有写事件」要不要新挂方法？

**不需要新挂方法，但「加日志」必须新增代码（要装包）。**

1. R-C 生死判据的两个写事件 `updateWithOnConflict` / `execSQL` **都已被常驻钩子覆盖**（`SnsGuard.kt:158/163`、`:152`）。`SnsHuntProbe` 更在 `op=sns sub=on\|catch` 时额外挂 `update/updateWithOnConflict/delete/insert/insertWithOnConflict/insertOrThrow/replace/replaceOrThrow/execSQL/rawQuery/query` + `SQLiteStatement.executeUpdateDelete/executeInsert/execute`（`probe/SnsHuntProbe.kt:181-196`），连预编译语句路径都覆盖（`:171-179` 注释）。⇒ **观测点已存在，不必新挂方法**。
2. 但现有钩子**只在极窄条件下做事**：`onSnsUpdateBefore` 对「ContentValues 不含 `sourceType`」的写只 `noteUpdateNoSrcKey()` 计数后 `return`（`SnsGuard.kt:366-369`）；`onExecSql` 只在 `sql.contains("SnsInfo")` 时进 `onSnsSql`（`:853`），`noteSql` 记的是**分类计数 + 原文采样**（`SnsBlockProbe.kt:552-598`），无行身份结构化字段。二者都**不记录 `createTime`/`userName`/`snsId` 区间/`ContentValues` 键名/调用栈**。
3. ⇒ 复用姿势：在 `SnsGuard.onSnsUpdateBefore` / `onExecSql`（或 `SnsBlockProbe.noteSql` 链路）里**增加观测字段**，或新增一个轻量 `SnsWriteProbe` 由现有 6 钩子回调。**挂载点复用、观测代码新增 ⇒ 属于「必须新增代码（要装包）」**。
4. **纯现成 op（零装包）只能做间接推断**：`op=sql`（`SnsGuard.runReadOnlySql`，只读查 `SnsInfo`）可列「三天外行清单 + 位/行数」，做**写前/写后结果对照**；`op=snsg sub=blockstat` 可看 `sqlClear9`/`sqlUnmatched`/`updSeen` 是否 >0 判断「是否发生过写」。但二者都**无法把写事件定位到具体三天外行**（详见 I4/I6）。

[DONE] I3

## I4 · 事件账本现状（`SnsBlockProbe`）

### `blockstat` = `SnsBlockProbe.stat()`（`SnsBlockProbe.kt:718-743`）

输出：**5~6 行全局计数**（`:720-738`：`hit/blocked/nonZero/skipOff/last`、skip 系列、drop 系列、self、`upd/ins`、`w2 delSns/delRow/sqlSns/sqlSrc/sqlClear/sqlClear9/sqlBlock9/blkOff/blkFail/rng/max/sqlOwn/sqlUnmatched`）+ **最近 `EV_SHOW=10` 个 `Event.line()`**（`:739-741`），`out.take(16)` 封顶（`:742`）。`op=snsg sub=stat` 走的是 `SnsGuard.stat()`（`EntryReceiver.kt:349`），**只有 3 个开关位 + `snsDb!=null`，无计数**（`SnsGuard.kt:1635-1637`）——两者别混。

### `trace` = `SnsBlockProbe.trace(id)`（`:773-785`）

对**单个已知 snsId** 每 30s 点查一次 `readBits`，最多 60 次（30min），`id=0` 停止；`traceTick` `:788-811` 只打 `src/lf/sef`（`:795-797`），**不打 createTime/userName、不观测写事件**。

### 字段对照表（用户点名的 7 项）

| 字段 | 现有？ | 证据 / 说明 |
|---|---|---|
| `snsId` | ✅ | `Event.snsId` `:168`；`readBits(db, snsId)` `:648`；`Event.line()` `id=` `:196` |
| `userName` | ⚠️ **半具备，账本里没有** | `Event` 无此字段；但 `Bits.userName` `:638` + `readBits` SQL `:654` 查了 `userName`，`SnsGuard.isSelfAuthor(bits?.userName)` `:522/:652` 在用。`stat()`/`trace()` 输出均不含 |
| `createTime` | ⚠️ **半具备，账本里没有** | `Bits.createTime` `:637`、`:662`，`readBits` SQL `:654` 查了 `createTime`（`MomentsLogic.kt:197` 注明 SnsInfo.createTime 是**秒**）——**仅点查能读，未进 `Event`/`blockstat`/`trace` 输出** |
| SQL 原文 | ❌ 结构化无 | `noteSql` 只把原文采样进 `sqlSamples`/`sqlSamples9`（`:624-627`）并 `LogX.w`（`:586/:595`）；**不在 `Event`、不在 `stat()` 返回**；去重 + 上限 60（`:617-622`）+ 单行 1500 字符（`:58`） |
| `ContentValues` 键集 | ⚠️ **只有键数量** | `Event.keys: Int` = 键**个数**（`:178`、W3 说明 `:177`）+ `hasContent`/`hasAttr` 两个特定键布尔（`:179-180`）；**无键名集合** |
| 调用者签名 / 调用栈 | ❌ | `SnsBlockProbe` 全程无栈；有栈的是临时探针 `SnsHuntProbe`（`FRAMES=24` `:55`）/ `RevokeHuntProbe`（`:314`） |
| 时间戳 | ✅（粗） | `Event.clock` = `hms()` `HH:mm:ss`（`:182`/`:826`，无日期/毫秒）；`lastHitMs` 用 `System.currentTimeMillis()`（`:746`） |
| **按 `userName` 过滤** | ❌ | `stat()` 无参数、`trace(id)` 只按单个 snsId、`op=snsg` 无 `user` 参数（`EntryReceiver.kt:342-386`）；`LabelHuntProbe.command` 有 `user`（`EntryReceiver.kt:313`）但那是标签探针 |

### 限流 / 去重 / 封顶 / 冷启

| 策略 | 值 | 证据 |
|---|---|---|
| 存储 | **纯内存、不建表不写盘**（进程死即清零） | `SnsBlockProbe.kt:28`；计数是 `AtomicInteger`（`:68-133`）/ `AtomicLong`（`:221-222`）——注：用户口径说 `AtomicLong`，实际**主计数是 `AtomicInteger`**，仅时间戳/节流用 `AtomicLong`，冷启清零这点一致 |
| 事件环 | `RING=50`；`blockstat` 只显示最近 `EV_SHOW=10`；总行数 ≤16 | `:43`/`:46`/`:742` |
| 命中日志节流 | 同一 snsId `HIT_LOG_GAP_MS=5s`（`allowLog` `:488-500`） | `:49` |
| 汇总自动日志 | `STAT_GAP_MS=60s`（`maybeStat` `:502-513`）——**60s 内最多一条** | `:52` |
| execSQL 原文 | `sampleSql` 去重 + `SQL_SAMPLE_MAX=60`（`:617-622`），`-9` 样本独立配额（`:626-627`）；`SQL_LOG_CHARS=1500` | `:55`/`:58` |
| 告警 | `warnOnce` 最多 8 类（`:688-692`）；`noteDelete` 只打前 5 条（`:535`） | 同上 |
| trace | 30s × 60 = 30min；同一 id 单采样器，重复调用替换 | `:61-62`/`:770-771` |

### ⇒ I4 明确结论：**`blockstat`/`trace` 不能回答「写事件是否命中三天外行」**

1. 判「三天外」必须有该行 `createTime` 与 `now-3d` 比较；`Event` 无 `createTime`/`userName`，`stat()`/`trace()` 输出均不含（`:195-198`、`:795-797`）。
2. `blockstat` 全是**进程内全局聚合计数**（`updSeen`/`sqlSns`/`sqlClear9`/`sqlUnmatched`…），**不按行、不按时间分桶**；`sqlUnmatched`/`sqlSrc` 只说明「出现过改 sourceType 的 SQL」，不说命中哪一行。
3. `trace(id)` 是「按已知 snsId 周期回查位」，**不是写事件观测**；且它打的是行当前 `src/lf/sef`，不是写事件本身。
4. `Event` 只在「拦截逻辑被触发」（ContentValues 含 `sourceType`）时创建（`:366-370`）；**不带 `sourceType` 的普通 SnsInfo 写只进 `updNoSrcKey` 计数，无事件行**——而三天外行上的写未必带 sourceType。
5. `& -9` 裸 SQL（`SnsGuard.kt:61`、`SnsSqlRewrite.kt:11`）原文其实含 `userName="<好友>"` 与 `snsId > A [and snsId < B]` 区间，理论上可反查命中集合，但原文只在 `LogX.w` 采样日志（去重封顶 60/1500 字符），**进不了结构化账本，也没有与 `createTime` 的 join**。

**最小缺失字段集**：① `createTime`（判三天外的硬前提，可从 `readBits` `:648` 复用但须写进事件/输出）；② `userName`（按好友过滤 / 关联「三天可见」范围）；③ execSQL 通道的**行区间展开或 `rangeKey`+`changes()` 命中数**（现成 `SnsSqlRewrite.rangeKey` `:93` 可复用）；④ SQL 原文 / `ContentValues` **键名集合** / 调用者签名或栈（区分「微信写」vs「模块改写/自己补位」）；⑤ 按 `userName` 或 `createTime < now-3d` 的过滤/关联能力（`op=sql` 有 select 旁路可做「三天外行清单」，但须与写事件做时间关联）。

[DONE] I4

## I5 · 只读 SQL 探针的真实通道（`op=sql` → `SnsGuard.runReadOnlySql`）

### 调用链与库句柄来源

```
EntryReceiver.kt:654-661  op=sql --es q "select …" [--ei n 60]
  → io { SnsGuard.runReadOnlySql(q, n) }                (SnsGuard.kt:1268)
      → val db = snsDb ?: "SQLX no sns db yet…"          (:1280)
      → rawQueryMethod(db)（按 db.javaClass 现找，不跨库缓存）(:1281, SnsBlockProbe.kt:679-686 同款)
      → cap = MomentsLogic.clampProbeN(maxRows)           (:1282)
      → m.invoke(db, s, null) → Cursor → 逐行 cellOf     (:1284-1305)
```

- `snsDb` = `private var snsDb: Any?`（`SnsGuard.kt:118`），由 `isSnsDb(db)`（`:926-949`）赋值：反射 `getPath()` 判 `p.endsWith(DB_MARK)`，`DB_MARK = "SnsMicroMsg.db"`（`:70`）；真机路径样例 `/data/user/0/com.tencent.mm/MicroMsg/<md5>/SnsMicroMsg.db`（`:24`）；注释 `:69`「朋友圈是**独立的库**，不是主消息库 `EnMicroMsg.db`」。
- 捕获时机：`execSQL` 命中含 `SnsInfo` 的 SQL 时（`onExecSql` `:842-848`）、`delete` 命中（`isSnsDb`）、以及 `installCapture` 的 `rawQuery` 临时钩子（`:176`、`:985-1016`，`CAPTURE_MAX_CALLS=20_000` 认输上限 `:982`）。`snsDbHandle()`（`:1043`）供 `SnsBlockProbe.readBits`/`trace` 复用（`:793`、`:648`）。

### ⇒ 独立复核 lane C Q2（`docs/_r49_revokeimg_reference.md` Q2）：**结论正确**

`op=sql` 走的是**朋友圈独立库 `SnsMicroMsg.db`**，不是聊天主库。证据：`SnsGuard.kt:70/:69/:24`、`:1245`「查 `sns.db`」、`:1280` 固定用 `snsDb`、`rawQueryMethod(db)`（`:1281`）只在这一个实例上跑。`ImgInfo2` 等聊天库表在 `EnMicroMsg.db`（`GroupDb.kt` 系列、`AntiRevoke.kt` 的 `msgDb` 另走捕获，参见 `AntiRevoke.kt:844` 注释），**静态推断查不到** ⇒ lane C 成立。

### ⇒ 对「查 `SnsInfo` 三天外行」是否有影响：**没有影响，反而是对的库**

| 证据 | 行号 |
|---|---|
| 真机删除现场 `db=…/SnsMicroMsg.db` + `table=SnsInfo` | `SnsGuard.kt:24-25` |
| `TABLE="SnsInfo"`；`DB_MARK` 判据就是「句柄路径以 SnsMicroMsg.db 结尾」才记 `snsDb` | `:67`、`:943` |
| `readBits` 的 `select sourceType,localFlag,serverExtFlag,createTime,userName from SnsInfo where snsId=$snsId` 正是用同一个 `snsDbHandle()` 跑通 | `SnsBlockProbe.kt:654`、`:648`、`:793` |
| `probeMediaIds` 亦在 `snsDb` 上 `select snsId,content from SnsInfo order by createTime desc` | `SnsGuard.kt:1355-1366` |

⇒ 三天外行清单可用现成只读通道：`op=sql --es q "select snsId,userName,createTime,sourceType from SnsInfo where createTime < <now-3d秒> order by createTime desc"`（`createTime` 单位**秒**，见 `MomentsLogic.kt:197` 的「别盲乘 1000」提示）。`[需真机]` 确认阈值口径与是否存在归档/分表。

### 三个白名单细节（I5 点名）

| 问 | 答 | 证据 |
|---|---|---|
| `pragma table_info(SnsInfo)` 是否放行 | ✅ **放行，且必须带括号** | `:1271` `low.startsWith("pragma") && pragmaReadOnly(low)`；`pragmaReadOnly` `:1234-1242` 含 `(` 时取 `substringBefore('(')` 查 `PRAGMA_WITH_ARG`；`PRAGMA_WITH_ARG` `:1216-1220` **含 `table_info`**。无括号的 `pragma table_info` 会落 `PRAGMA_NO_ARG`（`:1223-1226`，不含）⇒ 拒 |
| `hex()` 能否出现在 select 里 | ✅ **能** | 白名单只看**前缀** `startsWith("select")`（`:1271`），之后只拒内部 `;`（`:1277-1279`），无任何 token/函数黑名单 ⇒ `select hex(content) from SnsInfo …` 放行。`cellOf` `:1322-1333` 对 BLOB 只报 `<blob NB>`（不走 `getString`，见 `:1319-1320` 的踩坑说明），故 `hex(blob列)` 正是读 BLOB 的正确姿势。`[需真机]` 确认该 wcdb 编译了内建 `hex()` |
| 行数钳制 `clampProbeN` 上限 | **50 行** | `MomentsLogic.MAX_PROBE_N = 50`（`:1026`）、`clampProbeN(n)=n.coerceIn(1,MAX_PROBE_N)`（`:1032`）；`runReadOnlySql` `:1282` 取 `cap`、`:1296` `if (n>=cap) break`、`:1305` 打 `SQLX rows=$n`。`op=sql` 的 `--ei n` 默认 **60**（`EntryReceiver.kt:656`）会被钳到 50；传更大也最多 50 |

补充：输出前缀 `SQLX`（`:1274/:1293`）；`with` 开头拒（防 `WITH…DELETE`，注释 `:1255-1257`）；`pragma foo=bar` 拒（`:1236`）；接收器 exported，白名单同时是防第三方借模块写微信库的**安全边界**（`:1264-1266`）。

## I6 · 缺口清单（观测能力 × 现有能力差）

> 目标判据：三天外 `SnsInfo` 行上 **是否真的发生 `updateWithOnConflict` / `execSQL` 写库事件**。
> 「是否只读」= 该观测手段自身是否不写微信库（本 lane 全只读，写库类现成 op 单列警示）。

| # | 观测能力 | 现有是否具备 | 复用哪个现成探针 / 钩子 | 若不具备：需新增什么（类与函数级） | 落在哪个文件 | 是否只读 |
|---|---|---|---|---|---|---|
| 1 | 列「三天外」行清单（snsId/userName/createTime/sourceType） | ✅ 具备 | `op=sql` → `SnsGuard.runReadOnlySql`（`:1268`）+ `clampProbeN`（`MomentsLogic.kt:1032`） | — | — | ✅ |
| 2 | `SnsInfo` 列结构 / BLOB 内容核对 | ✅ 具备 | `op=sql` 的 `pragma table_info(SnsInfo)` / `select hex(...)`（白名单见 I5） | — | — | ✅ |
| 3 | 捕获 `updateWithOnConflict` 写事件（发生/次数） | ⚠️ 半具备 | `SnsGuard` 常驻钩子 `:158/:163`；计数 `updSeen/updZero/updNoSrcKey`（`SnsBlockProbe.kt:95-96/:92`）经 `blockstat` 输出 | 只**全局计数**，无行身份；需在 `SnsGuard.onSnsUpdateBefore`（`:356`）补「每次写落一条结构化记录」 | `feature/SnsGuard.kt` + `feature/SnsBlockProbe.kt` | ✅ |
| 4 | 捕获 `execSQL` 写事件 + SQL 原文 | ⚠️ 半具备 | `SnsGuard.sqlGuard`（`:152`）→ `noteSql`（`SnsBlockProbe.kt:552`）；原文采样 `sqlSamples/sqlSamples9`（`:624-627`）；`op=sns sub=on\|catch` 的 `SnsHuntProbe` 全量 SQL+栈+文件 | 采样**去重封顶 60 / 1500 字符**、不在结构化账本；需让 `noteSql` 增 `rangeKey`/`userName`/`hitRows` 参数并结构化落账 | `feature/SnsBlockProbe.kt`（`noteSql`）、`feature/SnsSqlRewrite.kt`（`rangeKey` 已存在 `:93`） | ✅ |
| 5 | 判定写事件命中的**行身份**（snsId / 区间 / userName） | ❌ 不具备（结构化） | `Event.snsId`（`:168`，仅 update 通道）、`SnsSqlRewrite.rangeKey`（`:93`，仅 `& -9` 的 snsId 区间）、`readBits`（`:648`） | 新增 `SnsWriteProbe.onWrite(...)`：从 `whereClause/whereArgs` 抠 snsId（复用 `SnsSql.argAt` `feature/SnsSql.kt:69`）、从 SQL WHERE 抠 `userName`（形态见 `SnsSqlRewrite.kt:11`）；execSQL 记 `rangeKey` 而非单 id | `feature/SnsBlockProbe.kt` / 新建 `feature/SnsWriteProbe.kt` | ✅ |
| 6 | 判定命中行是否**三天外**（createTime < now-3d） | ❌ 不具备 | `readBits` 已查 `createTime`（`:654/:662`）但**未与写事件关联** | 新增 `SnsWriteProbe.classify(snsId)`：用 `SnsGuard.snsDbHandle()` + `SnsBlockProbe.readBits` 取 `createTime`，与 `now-3*86400` 比较，写进事件 | `feature/SnsWriteProbe.kt`（新建）、`feature/SnsGuard.kt`（在 before 钩子里回调） | ✅ |
| 7 | 区分「微信写」vs「模块改写/自己补位」 | ⚠️ 半具备 | `sqlOwn`（`SnsBlockProbe.kt:126`）、`SnsBitLogic.isOwnFix`（`SnsBitLogic.kt:231`）、`isSelfAuthor`（`SnsGuard.kt:652`）、方案 B 改写标记 `SqlBlock`（`:244-256`） | 需在写观测记录里带上 `block`/`own`/`selfExcluded` 标记；调用栈更可靠（见 #8） | `feature/SnsWriteProbe.kt` | ✅ |
| 8 | 调用者签名 / 调用栈（定位触发场景：进好友主页？定时刷新？） | ❌ 常驻侧不具备 | 临时探针 `SnsHuntProbe`（`FRAMES=24` `probe/SnsHuntProbe.kt:55`、栈输出 `:313` 同款于 `RevokeHuntProbe.kt:314`） | 新增轻量栈采样 `SnsWriteProbe.stackOf()`（只在该写命中 SnsInfo 时取，`NOISE` 过滤复用 `SnsHuntProbe.kt:145-154`）+ 控频；或长期开 `op=sns sub=catch` 拉文件 | 新建 `feature/SnsWriteProbe.kt`；或 `probe/SnsHuntProbe.kt` | ✅ |
| 9 | 按 `userName` 过滤 / 关联「三天可见」范围 | ❌ 不具备 | `op=sql` 可 `where userName='…'`（只读旁路）；`LabelHuntProbe` 有 `user` 参数但是标签域 | 新增 `SnsWriteProbe.byUser(user)` + `EntryReceiver.kt` 的 `op=snsg` 增 `sub=write --es user <wxid>` 分支 | `feature/SnsWriteProbe.kt` + `entry/EntryReceiver.kt` | ✅ |
| 10 | 观测结果不被 5000 行环形覆盖 / 可导出 | ⚠️ 半具备 | `SnsHuntProbe`/`RevokeHuntProbe`/`MenuHuntProbe` 写 `wxgroup_dbg.txt`（`probe/SnsHuntProbe.kt:53`）；`LogX` 是 5000 行环形（`util/LogX.kt:22`） | 新增 `SnsWriteProbe.flushToFile()`（复用 `SnsHuntProbe` 写文件写法）或独立 ring；否则会被 `K7 perf`（`ConvFilter.kt:1347`）、微信热路径刷掉 | 新建 `feature/SnsWriteProbe.kt` | ✅ |
| 11 | 写前/写后行状态对照（sourceType 位 / 行数变化） | ✅ 具备（手动） | `op=sql` 前后各查一次；`op=snsg sub=trace --el id`（`SnsBlockProbe.kt:773`）看单 id 位随时间 | 若要自动化，新增 `SnsWriteProbe.snapshot(createTime<X)` 在写事件前后各取一次 | `feature/SnsWriteProbe.kt` | ✅ |
| 12 | 可控触发写事件 / 制造一次已知写验证钩子通 | ⚠️ 半具备（且是**模块写**） | `op=snsprefix sub=simdrop\|setsrc2 --el id`（`SnsPrefixProbe.kt:223/:256` 写 `sourceType`）、`op=moments sub=refresh\|load`（`EntryReceiver.kt:456`）、`op=msync sub=sync`（`:478`） | ⚠️ `simdrop/setsrc2` 是 **NOT 只读**（写微信 `SnsInfo`），且制造的是**模块自己发的写**，**不能证明 R-C**（微信是否写）。若要「微信真实进主页」的触发，只能靠真机操作 | — | ⚠️ 否 |
| 13 | 「是否发生过写」的全局快速判据 | ✅ 具备 | `op=snsg sub=blockstat`：`sqlClear9`/`sqlUnmatched`/`sqlSns`/`updSeen`/`insSeen`/`delSns`（`SnsBlockProbe.kt:733-738`） | — | — | ✅ |
| 14 | 覆盖「预编译语句层写」`SQLiteStatement.executeUpdateDelete` | ⚠️ 常驻侧不具备 | `SnsGuard` 6 钩子**不含** statement 层；`SnsHuntProbe` 含（`:190-196`，注释 `:171-179` 提醒「设置类写可能走预编译路径」） | **[推断] R-C 不必新挂**：`& -9` 走 `execSQL`、`sourceType=0` 走 `updateWithOnConflict`，均已被 #3/#4 覆盖。仅当真机发现写事件只出现在 statement 层，才需在 `SnsGuard.install()` 补挂 | `feature/SnsGuard.kt`（`install()` `:132`） | ✅ |

### ⛔ 两类清单（关键区分）

**A. 纯配置 / 现成 op 就能跑（现有 APK 发广播即可，零编译零装包；除标注外只读）**

1. `op=sql --es q "select snsId,userName,createTime,sourceType from SnsInfo where createTime < <now-3d秒> order by createTime desc" [--ei n 50]` → 三天外行清单（cap 50）。
2. `op=sql --es q "pragma table_info(SnsInfo)"` → 确认列名（必须带括号）。
3. `op=sql --es q "select hex(content) from SnsInfo where snsId=…"` → BLOB 核对。
4. `op=sql` 在「进好友主页前 / 后」各查一次 → **写前/写后结果对照**（sourceType 位、行数是否变）。
5. `op=snsg sub=stat` → 3 开关 + `snsDb!=null`（句柄是否捕获）。
6. `op=snsg sub=blockstat` → 全局计数（`sqlClear9`/`sqlUnmatched`/`updSeen`/`insSeen`/`delSns`/`sqlSns`）判断「是否发生过写」。
7. `op=snsg sub=trace --el id <snsId>` → 单个已知行位随时间（W1，非写事件本身）。
8. `op=sns sub=on --ei sec 150` / `sub=catch`（+ `sub=off`/`sub=stat`/`sub=tables`/`sub=sample`）→ **现成最全的写观测**：全 DB 写方法 + SQLiteStatement + SQL 原文 + 24 帧栈 + 落 `wxgroup_dbg.txt`（绕开环形）。代价：临时时间窗、全量噪音、无 createTime join、无 userName 结构化过滤、需拉文件。
9. `op=revoke sub=on --ei sec 90`（`RevokeHuntProbe`）→ 另一套临时 DB 写方法 + 栈取证（偏撤回域，可交叉验证）。
10. `op=moments sub=refresh|load` / `op=msync sub=sync` → 触发朋友圈刷新（产生写的前置动作）。
11. `op=logclear` → 清 `LogX` 环形（**只清日志行，不动 `SnsBlockProbe` 的 `AtomicInteger` 计数**）。
12. ⚠️ `op=snsprefix sub=simdrop|setsrc2 --el id` → 指定 snsId 上制造一次 `sourceType` 写，**NOT 只读、且是模块写不是微信写**，只能验钩子链路，不能证 R-C。

> A 类的**能力天花板**：能回答「三天外行有哪些」「全局是否发生过写」「写前后状态有没有变」，**不能回答「某一/某些三天外行上是否真的发生了写事件」**——因为 #5/#6/#9 的结构化关联不存在（见 I4 的 5 条理由）。要跨过这一步必须进 B 类。

**B. 必须新增代码（要装包；新增内容全部只读观测，不写微信库）**

| 新增项 | 类与函数级 | 落在文件 |
|---|---|---|
| B1 写事件结构化账本 | 新建 `SnsWriteProbe`：`onUpdateWrite(param)` / `onExecSqlWrite(sql, bindArgs, rangeKey)` / `classify(snsId)`（复用 `SnsGuard.snsDbHandle()` + `SnsBlockProbe.readBits` 取 `createTime`，比 `now-3d`） / `byUser(user)` / `stackOf()`（复用 `SnsHuntProbe.NOISE`） / `flushToFile()`（复用 `SnsHuntProbe.kt:53` 写法） | **新建** `feature/SnsWriteProbe.kt` |
| B2 在现有 6 钩子里回调（**不新挂方法**） | `SnsGuard.onSnsUpdateBefore`（`:356`）与 `onExecSql`（`:834`）在零反射短路之后调用 `SnsWriteProbe.onUpdateWrite/onExecSqlWrite` | `feature/SnsGuard.kt` |
| B3 `Event` / `noteSql` 扩字段 | `SnsBlockProbe.Event` 增 `createTime`/`userName`/`cvKeys:Set<String>`/`caller`/`rangeKey`；`noteSql` 增 `rangeKey/userName/hitRows` 参数 | `feature/SnsBlockProbe.kt` |
| B4 广播读出口 | `EntryReceiver.kt` 的 `op=snsg` 增 `sub=write [--es user <wxid>] [--el ct <秒>]` → 转发 `SnsWriteProbe.byUser(...)`/`stat()` | `entry/EntryReceiver.kt` |
| B5 区间→行展开（execSQL 通道） | 复用 `SnsSqlRewrite.rangeKey`（`feature/SnsSqlRewrite.kt:93`）+ `SnsSql.argAt`（`feature/SnsSql.kt:69`）把 `snsId > A and snsId < B` 与三天外清单做 join | `feature/SnsWriteProbe.kt`（复用既有两文件） |
| B6（条件性，[推断] 非必需）补挂 statement 层 | 仅当 A8 真机日志显示写只出现在 `SQLiteStatement` 层：在 `SnsGuard.install()`（`:132`）增 `hookAllMethods` | `feature/SnsGuard.kt` |

**结论**：判定 R-C 所需的**挂载点一个都不缺**（`updateWithOnConflict`/`execSQL` 常驻钩子 + `SnsHuntProbe` 全量临时钩子都在）；缺的是「把写事件与行的 `createTime`/`userName` 关联起来」的**结构化观测代码**。最少需新增 **1 个类 `SnsWriteProbe`（+ 对 `SnsGuard.kt` / `SnsBlockProbe.kt` / `EntryReceiver.kt` 3 个文件的钩子回调与字段扩写）**；若接受「临时探针 + 人工拉文件 + 手工 join」的半自动方案，则 **零新增代码**也能勉强取证，但证据强度最弱（见 I7 证据分级）。

[DONE] I5
[DONE] I6

## I7 · 复用陷阱与证据分级

### 复用现成探针时的坑（逐条附 `文件:行号`）

1. **`blockstat` 计数进程内、冷启清零**：`SnsBlockProbe.kt:28`「纯内存：不建表、不写盘。进程死了账本就没了」；主计数是 `AtomicInteger`（`:68-133`）——⚠️ 用户口径说 `AtomicLong`，实际仅时间戳/节流用 `AtomicLong`（`:221-222` lastHitMs/lastStatEl），**冷启清零这点一致**。⇒ 微信重启/模块重载后全归零，「0」不能反推「没发生过」。
2. **`vidCopied` 多路径共用先例**：`MomentsExport.kt:383` `vidCopied=AtomicLong`、`:1593` 累加、`:1828` 进 `stat()`。一个计数器被多条复制路径共用 ⇒ 看到数字分不清是哪条路径产生。对照 `SnsBlockProbe.kt:65`/`:23-24` 刻意「update/insert/delete/execSQL 四路各自独立计数，⛔ 不合成总数」——复用任何「总数」前先确认是否合并了通道。`[推断]`「四条路径」具体条数未逐条清点，按用户给的前例记录。
3. **60s 节流 / 5s 命中节流 / 采样封顶 ⇒ 「0 行」不能反推失效**：`maybeStat()` `:502-513`（`STAT_GAP_MS=60_000` `:52`，自动汇总最多 60s 一条）；`allowLog` `:488-500`（`HIT_LOG_GAP_MS=5_000` `:49`，同一 snsId 命中日志节流）；注释 `:485-486` 明说「微信对同一条 update 失败重试可以到几百次（防撤回那边实测 5 秒 360 次），不节流会把 5000 行环形日志冲干净」；`noteDelete` 只打前 5 条（`:535`）；`sampleSql` 去重 + `SQL_SAMPLE_MAX=60`（`:617-622`）。⇒ **判断「是否发生」要看计数，不要看日志行数**。
4. **`K7 perf` 刷屏**：`ConvFilter.kt:1347` `"K7 perf n=… snaps=…ms total=…ms path=…"`（`:1397` 引用；`ScrollDock.kt:69` 亦提 `K7 perf … scrolling=`），高频 rebuild 日志会挤占 `LogX` 环形。⇒ 复用日志型探针前 `op=logclear`（`EntryReceiver.kt:165-168`），优先用落文件的 `SnsHuntProbe`（`probe/SnsHuntProbe.kt:53`）。
5. **日志环形覆盖**：`LogX.MAX_LINES=5000`（`util/LogX.kt:22`）、`reset()` `:108`；`SnsBlockProbe.kt:31` 亦自述「LogX 是 5000 行环形，`updateWithOnConflict` 是微信所有表的热路径，放开打会把关键判据挤掉」。⇒ 长窗口观测必须落文件或及时导出。
6. **`snsDb` 未捕获 ⇒ `op=sql` 恒 0 行**：`runReadOnlySql` `:1280` 返回 `"SQLX no sns db yet（先让微信刷一次朋友圈）"`；`SnsPrefixProbe.probe/prefix` `:103-104` 同样 `PREFIX no db`；`installCapture` 认输上限 `CAPTURE_MAX_CALLS=20_000`（`:982`、`:1005-1008`「迟迟碰不到 SnsInfo（用户从不打开朋友圈）也认输卸载」）。⇒ 「SQLX 0 行」可能是句柄没拿到，**不是没有三天外行**；先 `op=snsg sub=stat` 看 `snsDb=true`（`SnsGuard.kt:1637`）。
7. **微信前台广播才投递**：接收器**动态注册在微信进程**（`HookEntry.kt:336`、`EntryReceiver.kt:111-133`、`:32-38`）；UI 类探针需前台 Activity（`withForeground` `:716-727`、`ActivityResultRelay.kt:174` "no host activity（微信不在前台？先切回微信再试）"）；`onReceive` 在主线程（`:85`、`:546` 注释），读库一律包 `io{}`。⇒ 微信进程被杀/未 attach 时广播不投递；`op=sns sub=on|catch` 的临时钩子只在窗口内且需进程存活。对 R-C：常驻钩子是进程内的（进程在即会触发），但**把结果读回来**必须微信进程存活 + 前台配合。
8. **A/B 开关语义（补充）**：`SnsGuard.refresh()` `:192-205`——`blockEnabled = ANTIREVOKE_SNS || ANTIREVOKE_SNS_BLOCK`；`sqlBlockEnabled = block && !ANTIREVOKE_SNS_SQLBLOCK_OFF`；`albumFixEnabled = !ALBUM_FIX_OFF`。且「开关关闭时**照样记账**（只计数、不摘键）」（`SnsGuard.kt:294`、`SnsBlockProbe.kt:294-295`）。⇒ 复用 `blockstat` 做对照必须同时记录开关快照，否则把「开关关」误读成「没写事件」。
9. **`sqlUnmatched` 不是「未匹配写事件总数」（补充）**：`SnsBlockProbe.kt:589-597` 仅在 `isSourceWrite && !clearBit1 && !clearBit3 && !own` 时累加，即「改了 sourceType 但形态没认出来」的 execSQL；**大量不带 sourceType 的 SnsInfo 写根本不进此计数**。别拿它当写事件总数。
10. **`op=snsg sub=sqlspy|srcmap|dump` 是空分支（补充 I2）**：落到 `EntryReceiver.kt:384` `SNSG unknown sub=`。旧文档若写了这些命令，照发只得到一行 unknown，易误判「探针没生效」。

### 三栏证据分级表

| 已证事实（静态代码/注释可直接确认） | 静态推断（代码逻辑推得，未经本包真机复核） | 必须真机才能定 |
|---|---|---|
| 6 个常驻钩子方法名 + before/after + 是否改参（`SnsGuard.kt:148-189`、`:214-307`、`:900-914`） | wcdb 这 6 个方法的**确切签名**（`updateWithOnConflict` 5 参、`insertWithOnConflict` 4 参等，按 `a[0]=table`/`a[1]=values` 取值 + 标准 SQLite 形态推得；`SnsPrefixProbe.kt:25` 给出 `update(String,ContentValues,String,String[])I`） | 8.0.48/2588 具体包 `com.tencent.wcdb.database.SQLiteDatabase` 实际方法表（`deleteWithOnConflict` 是否真无、`execSQL` 重载数）——项目结论见 `VoiceGuard.kt:125-135` + `tools/_r21c_verify_sql.py` |
| `execSQL` 钩子存在（`:152`/`:834`）；`sqlUnmatched` 在 `noteSql :593` 累加、输出 `:738`；`sqlClear9`/`rangeKey`（`SnsBlockProbe.kt:125`、`SnsSqlRewrite.kt:93`） | `op=sql` 只查 `SnsMicroMsg.db`（由 `DB_MARK="SnsMicroMsg.db"` `:70` + `snsDb` 链路 `:1280` 推得，与 lane C Q2 一致） | `SnsMicroMsg.db` 里 `SnsInfo.createTime` 的单位/时区、是否存在归档分表（`MomentsLogic.kt:197` 提示是秒） |
| `blockstat`/`trace` 字段集：无 `createTime`/`userName`/键名集/调用栈（`SnsBlockProbe.kt:167-199`、`:718-743`、`:788-811`） | `hex()` 能被 wcdb `rawQuery` 编译（白名单放行是已证；SQLite 内建函数是否编入是推断） | 「三天外」阈值口径（服务端下发 vs 本地计算）、三天可见范围是否按 `userName` 而非 `createTime` 圈定 |
| `pragma table_info(SnsInfo)` 放行（`:1216-1242`）、`clampProbeN` 上限 **50**（`MomentsLogic.kt:1026/:1032`）、`op=sql` 默认 n=60 被钳（`EntryReceiver.kt:656`） | 三天外行上的写**未必带 `sourceType`** ⇒ `Event` 可能覆盖不到（I4 理由 4，基于 `onSnsUpdateBefore :366-369` 早退） | **R-C 生死判据本身**：进好友主页后，三天外 `SnsInfo` 行上是否真发生 `updateWithOnConflict`/`execSQL` 写事件；若服务端不下发 ⇒ 本地不写 ⇒ `snsBlockGuard` before 空转 ⇒ R-C 证伪 |
| `SnsSqlSpy`/`sqlspy`/`srcmap`/`dump` 在 `op=snsg` 下不存在；`snsprefix` 写库 subs 真实清单（I2、`SnsPrefixProbe.kt:44-57`） | 6 条 `& -9` SQL 的 WHERE 含 `userName` + `snsId` 区间（`SnsSqlRewrite.kt:11` 为历史真机样本，是否每好友/每次一致未定；`SnsGuard.kt:61`、`docs/conclusions/moments.md:24-25` 为用户给的背景） | 6 条 `& -9` 是否覆盖三天外行、命中行数 `changes()`、是否只在「三天内」区间翻页 |
| 冷启清零 / 5s+60s 节流 / 60 条采样封顶 / 5000 行环形（`SnsBlockProbe.kt:28/:49/:52/:55`、`LogX.kt:22`）；`op=sns sub=on|catch` 时间窗 10~600s（`SnsHuntProbe.kt:70/:213`） | `simdrop`/`setsrc2` 制造的 `update` 会被 6 钩子看到（`SnsPrefixProbe.kt:228-229`/`:261-262`）⇒ 可用于自检钩子连通，但**是模块写、不能证 R-C** | 观测窗口内微信是否前台、广播是否投递、`snsDb` 是否已捕获（决定 `op=sql` 是否 0 行） |
| 接收器动态注册于微信进程、`RECEIVER_EXPORTED`（`EntryReceiver.kt:111-133`、`:32-38`） | `SQLiteStatement.executeUpdateDelete` 层写是否参与 R-C（推断不参与，依据 `SnsHuntProbe.kt:171-179` 注释 + `& -9` 走 `execSQL`） | 是否需要 I6-B6 补挂 statement 层（取决于 A8 真机日志里写事件是否只出现在语句层） |

### 一句话总览

挂载点（`updateWithOnConflict` / `execSQL` 常驻钩子 + `SnsHuntProbe` 全量临时钩子）**一个都不缺**；缺的是「把写事件与行的 `createTime`/`userName` 关联」的结构化观测代码。最省路径：**零新增代码**可用 A 类现成 op 做半自动取证（强度最弱）；要拿到「三天外行上到底有没有写事件」的硬判据，须落 B 类：**新增 1 个 `SnsWriteProbe` + 扩写 `SnsGuard.kt`/`SnsBlockProbe.kt`/`EntryReceiver.kt` 3 个文件**（全部只读观测，不写微信库，需装包）。

[DONE] I7
[DONE] ALL
