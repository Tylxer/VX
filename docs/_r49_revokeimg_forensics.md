# R49 「被撤回图片可打开」— 只读取证报告

> 只读 lane｜以磁盘现状为准（R33 事故后旧文档描述不可信）｜全只读、不碰 adb/真机/Gradle/装包/kvset
> 证据规则：每条结论附 `文件:行号` 或命令输出锚点；不确定一律标 `[推断]` / `[需真机]`
> 目标：先确认代码是否已实现「被撤回图片可打开」，再决定要不要开发。历史批次 A.2 判据原文：撤一张图，隔几分钟点开 → 能打开；日志 `VFG blocked MEDIA delete`（`docs/开发提效与后续计划.md` 批次 A）。

## 待答问题清单

- [ ] Q1 代码现状：列全 `wxgroup/app/src/main/java/com/lx/wxgroup/` 内所有「防撤回媒体文件」相关类（类名 + 文件:行号 + 一句话职责），逐个判定存活接线 / 已注释摘除。重点 `VoiceFileGuard.kt` / `MediaGuardLogic.kt` / `AutoHdImg.kt` / `HookEntry.kt` / `EntryReceiver.kt` / 任何含 `VFG` 或 `File.delete` 的文件。
- [ ] Q2 日志字符串判据：`VFG blocked MEDIA delete` 或等价日志现在还在不在源码？在哪个类/行/什么条件才打？字符串若已变，抄出实际字符串。
- [ ] Q3 图片撤回会删什么：除 `File.delete` 外还走哪些路径（wcdb delete / updateWithOnConflict / execSQL raw SQL / 缓存索引表 / SnsInfo）；现有代码分别拦了哪几条、漏了哪几条（参考 R42 的 execSQL 按好友批量清位绕过口子）。
- [ ] Q4 是否已实现：二选一 (a) 已实现只差真机验 / (b) 只做了语音、图片缺口 / (c) 部分实现（指出缺哪段），给可自证锚点。
- [ ] Q5 三档边界：①进入（刚撤回）②返回（过几分钟/切页再回/下拉刷新/冷启后）③复位（关开关/清缓存/微信自己重试删除/反向对照关开关再撤一张），各档发生什么、哪些状态残留；**复位档禁写成「同返回」**。
- [ ] Q6 真机验证方案：最小可执行步骤（`python tools/dev.py` 子命令 / 广播 op / 过滤关键字）、用户需配合做什么（好友一律用昵称，不许只报 wxid）、反向对照关哪个开关（准确 Key 名 + 默认值）及关后预期；判据对着「点开图片能不能看到」这个用户可见事实。
- [ ] Q7 失败点与回滚：最可能失败的 2~3 个点 + 每个一条排查命令；验失败就回滚的具体做法（关哪个 Key / 还原哪份备份 / 是否需冷启）。

[START]
[DONE] skeleton
> 锚点约定：下文 `xxx.kt:N` 均相对 `wxgroup/app/src/main/java/com/lx/wxgroup/`；写全路径的（`docs/…`、`CLAUDE.md`、`tools/dev.py`）除外。行号取自本次磁盘 Grep/Read 实测。

## Q1 代码现状 — 防撤回媒体文件相关类全清单

> 结论先行：**没有发现任何一类被注释或摘除**；R33 事故里被写残的 `VoiceFileGuard.kt` / `AutoHdImg.kt` / `MediaGuardLogic.kt` 在磁盘现状下结构完整（`VoiceFileGuard.kt` 1–336 全读、`object` 闭合于 336；`MediaGuardLogic.kt` 1–136 全读、闭合于 136；`AutoHdImg.kt` 1–40 与 455–477 抽读、`object` 闭合于 477）。⚠️ 是否**能编译**本 lane 无权验证（禁 Gradle），标 `[需真机/需编译]`；以下「存活」= 有 install 调用 + 有活代码调用点。

### A. 媒体文件保护核心链（图片/视频 + 语音共用）

| 类 | 文件:行号 | 一句话职责 | 接线判定 |
|---|---|---|---|
| `VoiceFileGuard` | `feature/VoiceFileGuard.kt:50`（object 声明） | hook `java.io.File.delete()/deleteOnExit()`，命中受保护文件则 `param.result=true` 伪装删除成功；**同时管语音池** `protect/shouldBlock`（`:201/:222`）**与图片视频池** `protectMedia/mediaHit`（`:122/:169`） | ✅ 存活：`HookEntry.kt:233` `VoiceFileGuard.install()`；`install()` 在 `:241` 实挂 `delete`/`deleteOnExit`（`:251-252`），`:260` 打 `VFG installed hooks=…` |
| `MediaGuardLogic` | `data/MediaGuardLogic.kt:38` | 零 Android 纯判据：`hexToken`（`:80`，优先取恰好 32 位 hex 段）/ `inMediaDir`（`:103`，`/image2/`、`/video2/`、`/video/`）/ `mediaHit`（`:125`，文件名 `contains` 未过期 token）/ `anyAlive`（`:110`）/`isMd5`（`:58`） | ✅ 存活：被 `VoiceFileGuard.kt:129,144,170,301`、`AntiRevoke.kt:458,479`、`RevokeRescue.kt:246,247` 调用 |
| `AntiRevoke` | `feature/AntiRevoke.kt:70`（类注释） | 拦 `updateWithOnConflict("message", …)` 的撤回改写（`:359-374` 只挂这一个方法）；**`anchorImage()`（`:450-491`）在消息 insert 入 `message` 表时提前把图片 imgPath 入保护池**（`:461` `VoiceFileGuard.protectMedia`） | ✅ 存活：`HookEntry.kt:208` `AntiRevoke.install(lpparam)`；`:384` 打 `AR installed (antirevoke.msg=…)` |
| `RevokeRescue` | `feature/RevokeRescue.kt:33`（类注释） | 撤回被拦下时写 `rescued` 台账并调用 `record()`（`:100-151`）：图片/视频调 `protectMedia`（`:122`）+ `auditImage`（`:126`）；语音调 `protect`（`:131`）/`VoiceBackup.backup`（`:135`）/`scheduleRestore`（`:143`）。`auditImage`（`:245-…`）查 `ImgInfo2` 自证「救没救到文件」 | ✅ 存活：`AntiRevoke.kt:710-711` `RevokeRescue.record(...)` / `applyOne(...)`；`EntryReceiver.kt:566,586` 广播 op 可调 |
| `HookEntry` | `HookEntry.kt:204-261` | Xposed 入口，集中安装全部 hook | ✅ 存活：防撤回四件套 `AntiRevoke.install`(`:208`)、`AntiRevokeHint.install`(`:215`)、`VoiceGuard.install`(`:223`)、`VoiceFileGuard.install`(`:233`)，另有 `SnsGuard`(`:241`)、`AutoHdImg`(`:248`)、`HintBadge`(`:260`)；`:336` `EntryReceiver.register(context)` |
| `EntryReceiver` | `entry/EntryReceiver.kt:40` | 广播 op 入口：`logclear`(`:165-168`)、`kvset`(`:176-208`，改完主动 refresh)、`voicebak`(`:215-239`)、`AR` stat(`:553`)、`ARDEL`(`:586`) | ✅ 存活：`HookEntry.kt:336` register |

### B. 语音侧（R21c 三层，作为「同构对照」列出）

| 类 | 文件:行号 | 一句话职责 | 接线判定 |
|---|---|---|---|
| `VoiceGuard` | `feature/VoiceGuard.kt:188-197` | 拦 `DELETE FROM voiceinfo`，顺带 `VoiceBackup.restore` | ✅ 存活：`HookEntry.kt:223`；开关 `Keys.ANTIREVOKE_MSG`（`:147`） |
| `VoiceGuardLogic` | `data/VoiceGuardLogic.kt:10,147` | 纯判据：`voiceinfo` 的 delete 识别（whereClause 为 null = 整体清理，不拦） | ✅ 存活：`VoiceGuard` 调用 |
| `VoiceBackup` | `feature/VoiceBackup.kt:19,200-202` | 三层 `backup / restore / scheduleRestore`；**仅语音分支被调**（`RevokeRescue.kt:129-144` 的 `isVoice`） | ✅ 存活（语音专用）；`EntryReceiver.kt:222-235` 有 `voicebak` 探针 |
| `VoiceBackupLogic` | `data/VoiceBackupLogic.kt:12,98` | 纯字符串：`isValidImgPath`(26 位 hex)/`imgPathOfVoiceFile` | ✅ 存活：`VoiceFileGuard.kt:202,223` |

### C. 防撤回域周边 / 间接相关

| 类 | 文件:行号 | 一句话职责 | 与本主题关系 / 接线 |
|---|---|---|---|
| `AntiRevokeHint` | `feature/AntiRevokeHint.kt:185` | 读取侧（`rawQueryWithFactory`）隐藏已躺在库里的撤回提示 | ✅ 存活：`HookEntry.kt:215`；**不碰媒体文件**，只管显示 |
| `HintBadge` | `feature/HintBadge.kt:161,164` | `onBindViewHolder` 上画「已阻止撤回」角标，按 msgId 反查 `rescued` 表 | ✅ 存活：`HookEntry.kt:260`；**不碰媒体文件** |
| `AutoHdImg` | `feature/AutoHdImg.kt:15-40,177,183` | 「自动加载原图」：挂 `t1.<init>`(NetSceneGetMsgImg)，后台下 HD 原图；开关 `Keys.CHAT_AUTO_HD_IMG` | ✅ 存活：`HookEntry.kt:248`。⚠️ **不是防撤回模块**，但决定「原图是否已在磁盘」；R34 定案它挂 `t1` 有结构性缺陷（未点开的图不一定建 `t1`，`AntiRevoke.kt:430-445`），故 `AntiRevoke.anchorImage` 补了「消息到达」锚点 |
| `SnsGuard` | `feature/SnsGuard.kt:193-199` | 朋友圈防撤回（独立库 `SnsMicroMsg.db`）：`updateWithOnConflict` 写时拦截 + `execSQL` 识别计数 | ✅ 存活：`HookEntry.kt:241`。**与聊天图片撤回不同库**；Q3 用它对照「execSQL 绕过」口子 |

**已废弃/被替代（非摘除，但别误当成现役链路）**：
- `AutoHdImg` 的 `downloadType==1` 闸门 —— `AutoHdImg.kt:24-37` 明写「已废，真机 `HDIMG installed=true seen=14 fired=0 skipOther=14`」。
- `RevokeRescue` 插负数 msgId 提示行方案 —— `HookEntry.kt:253-258` 明写「已废」，改 `HintBadge` 角标。
- 朋友圈「路线乙 事后补位/归档」 —— `HookEntry.kt:239`、`MomentsExport.kt:92,106,158` 明写「已随乙方案删除」。
- 语音/图片**都**不再走「算 md5 分桶找文件」：`VoiceFileGuard.kt:101-103` 明写图片用「删除发生时文件名含 imgPath」把 O(1) 路径计算整个绕开（R42 的 md5 分桶结论适用于**语音** `voice2/<md5[0:2]>/<md5[2:4]>/msg_<imgPath>.amr`）。

## Q2 日志字符串判据 `VFG blocked MEDIA delete` — 仍在，未过时

**结论：字符串原样还在源码，没有改名、没有被删。** 锚点 `feature/VoiceFileGuard.kt:312`：

```kotlin
LogX.i("VFG blocked MEDIA delete img=${token.take(10)}.. name=$name path=$path")
```

实际输出形如 `VFG blocked MEDIA delete img=0c947a1d.. name=<文件名> path=/data/…/image2/…`（`token.take(10)` 只打 token 前 10 位，不是完整 md5）。

### 什么条件才会打这行（`beforeHookedMethod`，`VoiceFileGuard.kt:263-315`）

依次全部满足，缺一行都不会出现：

1. **hook 已挂**：`install()`（`:241-260`）成功 hook 到 `java.io.File.delete` / `deleteOnExit`（`:251-252`），日志出现 `VFG installed hooks=delete,deleteOnExit`（`:260`）。挂不上会打 `VFG: no hook installed, voice file guard disabled`（`:257`）。
2. **落到媒体分支**：文件名不满足语音分支（`:272` 的 `msg_` 前缀 + `.amr` 后缀），或语音分支 `shouldBlock` 未命中（`:275`）。
3. **`hasMedia == true`**（`:294`，否则直接 `return`）—— 由 `protectMedia()` 成功入池时置位（`:155`）；池子全过期时 `:301` 复位为 false。
4. **`mediaHit(path, now) != null`**（`:297-303`）：路径含 `/image2/`、`/video2/`、`/video/`（`data/MediaGuardLogic.kt:61,103-105`）**且**文件名 `contains` 某个未过期 token（`MediaGuardLogic.kt:125-133`），token 期望为 32 位 md5（`:41,:55`）。
5. **总开关开**：`on()` 为 true（`:304`），即 `Kv.getBool(Keys.ANTIREVOKE_MSG)`（`antirevoke.msg`，`:192`；3 秒 TTL 缓存 `:185-189`）。⛔ 开关关着时**只拦不打**（`:277,:304`）。
6. **过了日志节流**：同一 `m:$token` 60 秒内只打一次（`:309-311`，`LOG_TTL_MS=60_000` 见 `:79-80`）。

打这行之前已执行 `param.result = true`（`:306`，伪装「删除成功」、文件不真删）与 `blockedMediaCount++`（`:307`）；打之后调 `dumpDir(self)`（`:313`，`:330-334` 列同目录最多 8 个文件带字节数，附 `VFG dir n=…`）。

### 谁把 token 放进池（上游入口）

- `feature/RevokeRescue.kt:122` `VoiceFileGuard.protectMedia(orig.imgPath)` —— **撤回那一刻**，图片/视频分支（`:121` `TYPE_IMAGE || TYPE_VIDEO`）。
- `feature/AntiRevoke.kt:461` `VoiceFileGuard.protectMedia(imgPath)` —— **消息到达时**（`anchorImage`，`:450-491`），提前入池以消掉「`File.delete` 异步」与「撤回 UPDATE 被拦」的先后竞态（理由见 `:441-445`）。
- 图片/视频保护窗口 `PROTECT_MEDIA_MS = 600_000L`（10 分钟，`:111-112`）；语音 `PROTECT_MS = 60_000L`（`:76`）。

### 等价 / 相关字符串清单（若历史判据写法不同，可对照）

| 字符串 | 位置 | 含义 |
|---|---|---|
| `VFG blocked file delete img=… path=…` | `VoiceFileGuard.kt:286` | **语音**分支拦下 |
| `VFG blocked MEDIA delete img=… name=… path=…` | `VoiceFileGuard.kt:312` | **图片/视频**分支拦下（历史判据所指） |
| `VFG protectMedia img=… ttl=…pool=… raw=…` | `VoiceFileGuard.kt:156-159` | 入池成功 |
| `VFG protectMedia SKIP no-token raw=…` | `VoiceFileGuard.kt:136` | 取不到 token ⇒ 这张图**没被保护** |
| `VFG protectMedia token len=… != 32 tok=… raw=…` | `VoiceFileGuard.kt:145-148` | token 非 32 位 ⇒ `contains` 几乎必不命中（静默失效告警） |
| `VFG installed hooks=…` | `VoiceFileGuard.kt:260` | 判「功能已加载」的心跳（CLAUDE.md 上机第一件事要的 `installed`） |
| `VFG dir n=…` | `VoiceFileGuard.kt:334` | 命中目录快照 |
| `RRV record … imgPath=…` | `RevokeRescue.kt:100-104` | 撤回台账，区分「没救到文件」与「救到了还打不开」 |
| `RRV imgAudit …` | `RevokeRescue.kt:230-243`（格式注释） | 查 `ImgInfo2` 自证 big/thumb/hd 在不在 |
| `AR imgAnchor …` / `AR imgAnchor BAD md5 …` | `AntiRevoke.kt:486-490 / :481-484` | 消息到达锚点 / token 异常告警 |

### 核对历史判据的两点提醒

- 批次 A.2 的 `VFG blocked MEDIA delete` **未过时**，但它现在**专指图片/视频媒体分支**（计数器 `blockedMediaCount`）；语音是 `VFG blocked file delete`（`:286`）。写验收脚本时别只 grep 一条就以为全链路通了。
- ⛔ **这条日志不是「功能已启用」心跳，只在真的拦下一次删除时打**。`grep VFG blocked MEDIA delete` = 0 行**不能**反推功能失效：可能这张图微信没走 `File.delete`（缓存已无原图 / 走 native `unlink`），也可能被 60 秒节流吞掉（`VoiceFileGuard.kt:79-80`）。`VoiceFileGuard.kt:126` 自己记录了「真机 `grep VFG` = 0 行」曾误导排查半个轮次。判「已加载」请用 `VFG installed hooks=`（`:260`），判「已入池」请用 `VFG protectMedia`（`:156`）。
- `[需真机]` 上述触发链在 8.0.48 上是否全程走通仍待验：`image2` 下文件名是否真的含 32 位 md5、`hd` 是否等于 `bigImgPath + "hd"`，`RevokeRescue.kt:238-243` 自己标了 `NA` / `[假设]` 未取证。R31g 已实证的只是 `imgPath` 形态为 `THUMBNAIL_DIRPATH://th_<32 位 md5>`（`MediaGuardLogic.kt:20-22`）。

[DONE] Q1-Q2
## Q3 图片撤回会删什么 — 拦了哪几条、漏了哪几条

### 3.1 删除面分层（以磁盘现状 + 既有取证文档为锚）

| 层 | 微信动作 | 证据锚点 | 现有代码是否拦 |
|---|---|---|---|
| ① SQL 改写 | `UPDATE message SET type=…, content="…撤回了一条消息" … WHERE msgId=?`（`updateWithOnConflict`） | `HookEntry.kt:205-206`；`docs/_r35_revoke_img_anchor.md:146` 标「① 真机」 | ✅ `AntiRevoke.revokeGuard` 拦（`AntiRevoke.kt:371,387-392`） |
| ② SQL 直删 / raw SQL | `DELETE FROM message WHERE talker IN …`、`DELETE FROM ImgInfo2 WHERE msglocalid IN (…)`、`DELETE FROM videoinfo2 …`、`DELETE FROM AppMessage …`，以及泛型单条构造器 `delete from %s where %s=%s` | `docs/_r35_revoke_img_anchor.md:126-135`（全 dex 扫 `DELETE FROM` 的结果） | ⚠️ 见 3.3 漏口 |
| ③ 索引 / 信息表 | `ImgInfo2`（8.0.48 真实表名，列含 `bigImgPath/thumbImgPath/status/iscomplete/hashdthumb`，索引 `msgLocalIdIndex`） | `docs/_r35_revoke_img_anchor.md:36-64`；`RevokeRescue.kt:269-273`（只读 `select`） | ❌ 只**只读审计**，不拦任何删除（`RevokeRescue.kt:243` 自述「本函数只读」） |
| ④ 磁盘文件 | `File.delete(image2/<桶>/<md5> / <md5>hd / th_<md5>)`，异步（`executor.submit(vj0.p)` 之后） | `RevokeRescue.kt:110-120`；`VoiceFileGuard.kt:97-99`；`MediaGuardLogic.kt:121-123`；`docs/_r35_revoke_img_anchor.md:148` | ✅ `VoiceFileGuard.deleteGuard` 全局拦（`:251-252,306`） |
| ⑤ 语音专属（对照） | `DELETE FROM voiceinfo WHERE FileName=?` | `VoiceGuardLogic.kt:10,147`；`docs/_r35_revoke_img_anchor.md:147`（R21c 真机） | ✅ `VoiceGuard` 挂 `delete` / `deleteWithOnConflict`（`VoiceGuard.kt:133,136`） |
| ⑥ 朋友圈（不同库） | `UPDATE SnsInfo SET sourceType = sourceType & -9 WHERE SnsInfo.userName="wxid_…" AND (…)` **按好友批量清位**，走 `execSQL` | `docs/_r42_block_result.md:100-106` | ⚠️ `SnsGuard` 挂了 `execSQL`（`SnsGuard.kt:152`）但**只识别/计数，不拦**；`:112` 明写「我们的拦截只挂在 `updateWithOnConflict`，这条 SQL 走 `execSQL`，**完全不拦**」 |

> ⛔ 重要澄清：`SnsInfo` / `& -9` 属**朋友圈独立库 `SnsMicroMsg.db`**（`HookEntry.kt:236-238`），不是「聊天里被撤回的图片」那条链。题目点它是因为它**证明了微信确实用 `execSQL` 发按好友批量的 raw SQL 清理**（`docs/_r42_block_result.md:100-112`），这个口子对聊天侧是同构风险。

### 3.2 现有代码实际挂的 hook（逐条对照，别把「有探针」当成「有拦截」）

| 宿主方法 | 挂点 | 行为 |
|---|---|---|
| `wcdb SQLiteDatabase.updateWithOnConflict` | `AntiRevoke.kt:371` | ✅ **拦**撤回改写（唯一拦截点）；`:359-360` 明写「只挂 updateWithOnConflict，挂一个就够」 |
| `wcdb SQLiteDatabase.insertWithOnConflict` | `AntiRevoke.kt:382` | ⚠️ **只看不拦**：`insertProbe` 是探针 + `anchorImage` 提前入池（`:377-383,395-400`） |
| `wcdb SQLiteDatabase.delete` / `deleteWithOnConflict` | `VoiceGuard.kt:133,136`；`SnsGuard.kt:149` | 只服务 `voiceinfo`（`VoiceGuardLogic.kt:10`）与 `SnsInfo`；**聊天 `message` / `ImgInfo2` 的 delete 无专门拦截** |
| `wcdb SQLiteDatabase.execSQL` | 仅 `SnsGuard.kt:152`（朋友圈库） | **聊天防撤回侧零挂点**（`AntiRevoke.kt:357-384` 全段无 `execSQL`） |
| `java.io.File.delete` / `deleteOnExit` | `VoiceFileGuard.kt:251-252` | ✅ 全进程拦，图片/视频/语音共用（`param.result=true` 于 `:306`） |

### 3.3 漏的几条（按风险排序）

1. **`execSQL` raw SQL 通道，聊天侧完全没挂**（R42 同构口子）。`docs/_r42_block_result.md:110-116` 实证：`sqlUnmatched=6` 条带 `sourceType` 的 SQL 未匹配、走 `execSQL` 完全不拦，结论原文「**风险等级：中**……只要微信哪天把『好友删动态』也做成 `sourceType & -3` 的按人批量 SQL，路线甲会静默失效，且我们连『被绕过』都看不出来」。→ 若微信对 `message.imgPath` / `ImgInfo2` 也用 raw SQL 清列或删行，`AntiRevoke` 一样静默失效；而 `File.delete` 只保住文件，**行/索引没了照样点不开**（R21c 教训：`VoiceFileGuard.kt:25-26`「播放要求表里有记录 + 磁盘有文件齐备」）。
2. **`ImgInfo2` 索引行是否被删未取证、且无拦截**。`docs/_r35_revoke_img_anchor.md:121-143` 标题即「撤回到底删了哪些表 —— **无证据，待真机**」：`:138` 明写没找到单条 `DELETE FROM ImgInfo2 WHERE msglocalid = ?` 字面量，单条删除走泛型 `delete from %s where %s=%s`（`:132-139`）；`:141-143` 明写「『撤回时 `ImgInfo2` 行还在』目前是 ② **推断**……**未取证，待真机**」。若行被删，`RRV imgAudit` 会 `row=0`（`_r35_revoke_img_anchor.md:210`）。现有 `VoiceGuard.deleteGuard` 只认 `voiceinfo`，不认 `ImgInfo2`。
3. **`native unlink` 拦不到、图片侧无兜底**。`VoiceFileGuard.kt:35-38` 自述 hook 只覆盖 Java 层 `File.delete/deleteOnExit`，拦不到 native `unlink()`。语音侧有 `RevokeRescue.kt:129-144` 的 `protect → backup → scheduleRestore` 三层（R21c 闭环，全文 `docs/_r21c_voice_file.md`）；**图片/视频分支只有 `protectMedia` + `auditImage`（`RevokeRescue.kt:121-127`），没有 backup/restore**。实锤：`VoiceBackup.kt:111` `needsBackup(origType)`「只在 `type=34` 时动作」，`:127` 源路径硬编码 `voice2/<md5 分桶>/msg_<imgPath>.amr`。⇒ 图片一旦走 native unlink，文件永久丢失、无副本可搬回。
4. **`INSERT OR REPLACE` 覆盖原行未拦**。`AntiRevoke.kt:395-400` 明写「有两条被撤回的消息（type=10002）在日志里**没有对应的 update**，怀疑是走了 `INSERT OR REPLACE`。在没证实之前不敢直接拦」——`insertWithOnConflict` 至今只看不拦（`:382`）。若撤回走它，`message.imgPath` 会被抹掉。
5. **`AntiRevoke` 未挂 `delete`**：`:359-360` 明确只挂 `updateWithOnConflict`；撤回若改用 `delete("message", …)` 直删行，`probe/RevokeHuntProbe.kt:124-132` 也把 `delete` 列为撤回可能通道，全链无拦截。

[DONE] Q3-Q4

## Q4 是否已实现 — 结论 **(c) 部分实现**

**不是 (b)**：图片防撤回**不是缺口**，主链已写且全部存活接线（Q1 实证）：
- 消息到达提前入池 `AntiRevoke.kt:450-491`（`:461` `VoiceFileGuard.protectMedia`），消掉「`File.delete` 异步」竞态（`:441-445`）；
- 撤回时再入池 `RevokeRescue.kt:121-123` + 只读自证 `auditImage`（`:126,245`）；
- `VoiceFileGuard.kt:291-315` 拦 `File.delete` 并打 `VFG blocked MEDIA delete`（`:312`），图片 TTL 10 分钟（`:111-112`）；
- 判据字符串与历史批次 A.2 一致（Q2）。

**不是 (a)**：不是「只差真机验」，存在**静态即可确认的结构性缺口**（Q3 3.3）：
- 缺①：图片/视频**没有 backup/restore 兜底**（`RevokeRescue.kt:121-145` 对照语音三层；`VoiceBackup.kt:111,127` 只服务 type=34）；
- 缺②：聊天侧**没挂 `execSQL` / `delete` / `INSERT OR REPLACE` 拦截**（`AntiRevoke.kt:357-384`），R42 已实证同构 raw SQL 绕过口子（`docs/_r42_block_result.md:112`）；
- 缺③：`ImgInfo2` 行是否幸存 **[推断]**，无取证（`docs/_r35_revoke_img_anchor.md:121-143`）。

**且全链未真机闭环（可自证锚点）**：
- `docs/开发提效与后续计划.md:82,89`：A.2「被撤回图片能打开 / 撤一张图，隔几分钟点开 / 能打开；日志 `VFG blocked MEDIA delete`」仍列在「**批次 A · 装包 1 次：… 4 项待验**」表里 ⇒ 待验，非已验。
- `docs/_r35_revoke_img_anchor.md:26`「1.5 真机验证命令与预期日志（§5，**需主会话执行**）」、`:292`「真机验证（**需要主会话执行**，本 lane 不碰 adb）」。
- ⛔ `:320` 小节标题是「**预期日志**」，`:331-347` 那些 `AR imgAnchor …` / `VFG protectMedia …` / `RRV imgAudit …` 是**预期输出样例，不是真机实录**，别当实证引用。
- 本次只读 Grep：全仓 `*.txt` 中 `VFG blocked MEDIA delete|VFG protectMedia|RRV imgAudit|AR imgAnchor` → **`No matches found`**（命令输出见取证过程）；`docs/_r35_revoke_img_anchor.md:148` 虽自述「由 VoiceFileGuard 拦（① 真机，R30）」，但**无可核查的独立真机日志锚点**，标 `[需真机]`。
- 语音侧才是已闭环的那个：R21c 三层 `protect / backup / scheduleRestore`（`docs/_r21c_voice_file.md`，`RevokeRescue.kt:129-144`）；R42 定案 `File.delete` hook 实测有效（`36ms`）且分桶是 md5 不是 imgPath 自身 —— 那是**语音**路径 `voice2/<md5[0:2]>/<md5[2:4]>/msg_<imgPath>.amr`（`VoiceBackup.kt:33`），图片用「删除时文件名含 token」绕开了 O(1) 算路径（`VoiceFileGuard.kt:101-103`）。

**⇒ 对批次 A.2 的处置建议**：这是**验证任务**，先按 Q6 跑一次真机闭环（含反向对照），别先动代码。若「点开能看」成立 ⇒ 判 (a) 收口；若失败，按 Q3 3.3 的 ①②③ 顺序定位（先看 `RRV imgAudit` 的 `row/big/thumb/hd`，再看有没有 `VFG blocked MEDIA delete`，最后查 `execSQL`），再决定要不要补开发。

[DONE] Q3-Q4
## Q5 三档边界（进入 / 返回 / 复位）

> 项目硬要求逐档写；**复位档不与返回档合并**。`docs/_r44_chain.md:1026` 原话：「三档边界（**R42-N1N6 教训：只写「进入 / 返回」会漏掉复位路径**）」。R42 实证：`docs/_r42_n1n6_verify.md:60-83,125`「滚回顶部 / 下拉刷新」是唯一复位路径，曾因复位判据 `pos <= prev` 把封面重绑误判成复位，导致灰标「先消失再补回」的可见闪烁；修法是 `pos == prev` 才前移（`_r42_n1n6_impl.md:419,429`）。

### 5.1 三档边界主表

| 档 | 触发 | 系统动作（锚点） | 用户可见 | 状态残留 |
|---|---|---|---|---|
| **① 进入**（刚撤回那一刻） | 好友撤回一张图片 | `AntiRevoke.revokeGuard` before 拦 `updateWithOnConflict`（`AntiRevoke.kt:371,387-392`）⇒ `message` 行保持 type=3 / `imgPath` 在；`RevokeRescue.record` 写 `rescued` 台账（`:100-104`）+ `protectMedia`（`:122`）+ `auditImage`（`:126,245`）；`anchorImage` 早在消息到达时就已入池（`AntiRevoke.kt:461`）；微信异步 `executor.submit(vj0.p)` → `File.delete(image2/…)` 被 `deleteGuard` 命中 ⇒ `param.result=true`（`VoiceFileGuard.kt:306`）+ `VFG blocked MEDIA delete`（`:312`）+ `dumpDir`（`:313`）。R21c 同构时间线实证：`docs/_r21c_voice_file.md:181-190`（撤回拦截 +2ms 登记、+4ms 备份、**+36ms 删除被拦**） | 点开该图**能看到大图**（前提：文件在盘 + `ImgInfo2` 行在，`docs/_r35_revoke_img_anchor.md:344`「`big=OK(n>0)` = 大图在盘上 = 点开必然能看」） | 保护池 token（TTL **10 分钟**，`VoiceFileGuard.kt:111-112`）、`hasMedia=true`（`:155`）、`logAt` 记 1 次（60s 节流，`:79-80`）、`imgAnchor` 1 项（30 分钟 TTL，`AntiRevoke.kt:494`）、`rescued` 台账 1 行、hook 全挂、**图片侧无备份文件**（`RevokeRescue.kt:121-127` 只 `protectMedia`+`auditImage`） |
| **② 返回**（隔几分钟 / 切页再回 / 下拉刷新 / 冷启后） | 纯 UI 重建或进程重启，**不产生新的撤回 UPDATE** | 切页/下拉刷新只读 `message` + `ImgInfo2`（`RevokeRescue.kt:269-273` 是这类查询的形态），池与 hook 不变，文件仍在 ⇒ 能打开；**冷启**：`HookEntry` 重新 `install()` 一次（`HookEntry.kt:208-260`；`[推断]` Xposed 常规行为，本 lane 未验证），**内存状态全丢**（`protectedMedia` / `hasMedia` / `logAt` / `imgAnchor` / `convBlocked` / `lastDb`），持久化状态保留（`rescued` 台账、`image2` 原文件、微信库行、语音 `voice_bak` 备份） | 隔几分钟点开**仍能看到**（在 10 分钟 TTL 内）；切页/下拉刷新仍能看到；**冷启后是否仍能看到标 `[需真机]`** —— 池空了，若冷启后微信重新发起删除（补拉/重试）无人拦。`docs/_r42_prefix_refactor.md:71` 明写「第二次写库 = 竞态 + **冷启/下拉刷新被覆写**」的同类风险 | `auditImage` 的 Cursor 每次 `close()`（`RevokeRescue.kt:287`），**不残留游标**；`AntiRevoke.msgQueryHandle()` 持有的 DB 句柄是长期缓存（`AntiRevoke.kt:844` 注释「`msgDb` 在 updateWithOnConflict 里捕获」） |
| **③ 复位**（关开关 / 清缓存 / 微信自己重试删除 / 反向对照关开关再撤一张） | 见 5.2 逐条，**与返回档本质不同** | 见 5.2 | 见 5.2 | 见 5.2 + 5.3 残留总表 |

### 5.2 ⛔ 复位档逐条（**不许写成「同返回」**）

| 复位动作 | 发生什么 | 与返回档的本质差异 |
|---|---|---|
| **关掉防撤回开关 `antirevoke.msg=false`** | `VoiceFileGuard.refresh()` 只改 `enabled`/`cfgAt`（`VoiceFileGuard.kt:191-194`），`on()` 有 **3 秒 TTL 缓存**（`:185-189`）⇒ 关后**最多 3 秒内仍会拦**；3 秒后 `deleteGuard` 每次仍跑（hook **不卸载**，`install()` 无 unhook），但 `on()=false` 直接 `return`（`:277,:304`）。`AntiRevoke.config` 由 `kvset` 主动 `refresh()`（`EntryReceiver.kt:182`）。⛔ **保护池 `protectedMedia` 不清空**，旧 token 靠 TTL 自然过期 ⇒ 关后短时间内再开，旧 token 可能仍在保护 | 返回档 hook 与开关都**不变**；复位档开关翻转 + 有 3 秒延迟 + hook 常驻空转 + 池残留。这是唯一能区分「配置态 vs 已装代码」的档（`docs/_r48_review_autoplay.md:167`「复位档从无观测」的教训） |
| **清缓存** | `op=logclear` 只清 LogX 环形缓冲（`EntryReceiver.kt:165-168`），**不动池/台账/文件**。若指微信「存储空间 → 清缓存」，会删 `image2` 下文件：开关**开着**时 `File.delete` 被拦 ⇒ 存储只增不减（`VoiceFileGuard.kt:67-68,175-177` 自述「比播放失败严重得多」）；开关**关着**时真删 | 返回档**不会删文件**；这是唯一能真正把已保护图片删掉的路径 |
| **微信自己重试删除** | `VoiceFileGuard.kt:64` 注释「微信可能重试」；`docs/_r42_bit3_design.md:287`「防撤回侧实测 **5 秒 360 次**」「每次重试仍是 … ⇒ 再次命中」。重试落在 **TTL 内** ⇒ 每次都被拦，但 60s 节流只打 1 行日志；重试落在 **TTL 外或冷启后池空** ⇒ **无人拦、文件被删** | 返回档默认「删除只发生一次且已被拦」；复位档把「重试时机」变成自变量。⛔ 语音侧还有 `scheduleRestore` 兜底，图片侧**没有**（Q3 3.3③）⇒ 一旦漏拦即永久丢失。此即题目所警示的「R42 漏掉滚回顶部这类唯一复位路径」同型事故 |
| **反向对照：关开关再撤一张** | `antirevoke.msg=false` 后 `AntiRevoke` 不再拦 UPDATE ⇒ 微信正常把 `message` 行改成撤回提示；`RevokeRescue.record()` 不触发 ⇒ **不入池、不写台账、不打 `RRV imgAudit`、不打 `VFG blocked MEDIA delete`**（`docs/_r35_revoke_img_anchor.md:347-348`「三条日志全部消失，且撤回提示正常出现」）；`File.delete` 因 `on()=false` 放行 ⇒ 图片真删 | 返回档 `message` 行与台账**都还在**；复位档**原文消失**。用户可见：点开是「已撤回」提示 / 图片打不开 —— 这是反向对照的硬判据 |

### 5.3 状态残留总表（逐项，跨档）

| 状态 | 载体 | 进入 | 返回（含冷启） | 复位 | 清除条件 |
|---|---|---|---|---|---|
| 已挂 hook | 进程级 `XposedBridge.hookAllMethods` | 全挂（`HookEntry.kt:208-260`） | 冷启重新挂；其余不变 | **永不卸载**，只随进程死 | 进程死亡 |
| 图片保护池 `protectedMedia` + `hasMedia` | `VoiceFileGuard.kt:109,119` | 有 token | 切页/下拉在；**冷启丢** | **不清空**，靠 TTL/`anyAlive` 复位（`:299-301`） | 10 分钟 TTL 或进程死 |
| 语音保护池 / `logAt` 节流 | `VoiceFileGuard.kt:70,79` | 语音 60s / 日志 60s | 冷启丢 | 同上 | TTL / 进程死 |
| `imgAnchor` | `AntiRevoke.kt:467-472,494` | 1 项 | 切页在；冷启丢 | 不清 | 30 分钟 TTL / 256 上限 / 进程死 |
| `rescued` 台账 | 模块自己 DB（`GroupDb.kt` `T_RESCUED`） | +1 行 | **跨冷启保留** | **跨关开关保留**（只有 `ARDEL` / `RevokeRescue.sync` 会动，`EntryReceiver.kt:586`、`RevokeRescue.kt:416,429`） | 显式删除或恢复显示同步 |
| 备份文件 `voice_bak/` | 磁盘（`VoiceBackup.kt:42-45`） | **图片侧没有**；语音有 | 保留 | 保留 | 手工/微信 GC |
| 微信库行 + `image2` 原文件 | 微信数据目录 | 被保住 | 保留 | 关开关后重试删除/清缓存可真删 | 见 5.2 |
| 游标 | `auditImage` 的 `Cursor` | 开→**close** | 无 | 无 | `RevokeRescue.kt:287` 已 close |
| 开关缓存 | `VoiceFileGuard.on()` / `AntiRevoke.config` | 生效 | 不变 | **3 秒 TTL 延迟**（`:185-189`） | `kvset` 主动 refresh（`EntryReceiver.kt:182`） |

[DONE] Q5-Q6

## Q6 真机验证方案（最小可执行）

> 本 lane 不碰 adb / 真机 / 装包 / Gradle；以下为主会话执行清单。`CLAUDE.md:29`：「真机 `python tools/dev.py loop <场景> --grep '关键字'`，**必须做反向对照**（关开关再复现一次）」；`CLAUDE.md:73-79` 上机前三件事。

### 6.1 上机前三件事（漏一件 = 白跑一轮）

| # | 动作 | 命令 / 锚点 |
|---|---|---|
| 1 | 模块真加载 | `python tools/dev.py q 'VFG installed' 20` → 必须见 `VFG installed hooks=delete,deleteOnExit`（`VoiceFileGuard.kt:260`）；顺带 `q 'AR installed' 20`（`AntiRevoke.kt:384`）。`CLAUDE.md:75-76` 判据始终是「日志里有没有 `installed`」 |
| 2 | 清 LogX | `python tools/dev.py probe logclear --grep 'PROBE logclear'`（`EntryReceiver.kt:165-168`；`tools/dev.py:250-253` 亦支持任意 probe 加 `--clear` 先发 logclear）。写满是环形覆盖不是停写，不用重启微信（`CLAUDE.md:78`） |
| 3 | 微信在前台 | 人工确认。`CLAUDE.md:79`「微信在前台（后台广播不投递）」；`tools/dev.py:102` 同述「`am broadcast` 的 completed 只证明 AMS 收下了广播；微信在后台时它不投递」 |

⚠️ 每次带 `--grep` 必须见 **≥1 行匹配**，否则判「探针没执行」，不解读内容（`docs/_r35_revoke_img_anchor.md:318` 闸门三；`tools/dev.py:295-299`）。

### 6.2 最小步骤表（正向）

| 步 | 命令 / 动作 | 期望（用户可见优先） |
|---|---|---|
| 0 | `python tools/dev.py probe kvset --key antirevoke.msg --val true --grep 'PROBE kvset'` | 开关打开；`EntryReceiver.kt:176-182` kvset 后立刻 `AntiRevoke.refresh()`，不用重启微信（`docs/_r35_revoke_img_anchor.md:298`） |
| 0b | `python tools/dev.py probe kvset --key debug.log --val true --grep 'PROBE kvset'` | 开日志（`docs/_r35_revoke_img_anchor.md:299`） |
| 0c | `python tools/dev.py probe sql --q 'select count(*) from ImgInfo2' --grep SQLX` | 证 `ImgInfo2` 表在（`docs/_r35_revoke_img_anchor.md:302`）；0 行 ⇒ 表名赌错，整条审计作废（`:322`） |
| 1 | **请好友发图**：见 6.4 | 收到图，**不要点开**（`docs/_r35_revoke_img_anchor.md:305`） |
| 2 | `python tools/dev.py q 'AR imgAnchor' 20` | `AR imgAnchor … type=3 md5=…(32) seen=1`（`AntiRevoke.kt:486-490`）；`BAD md5` ⇒ imgPath 形态变了（`_r35_revoke_img_anchor.md:335`） |
| 3 | `python tools/dev.py q 'VFG protectMedia' 20` | 入池 `ttl=600000ms pool=1`（`VoiceFileGuard.kt:156-159`） |
| 4 | **对方撤回那张图** | 等 3~5 秒后撤（R21c 同链 +36ms 完成删除拦截，`docs/_r21c_voice_file.md:181-188`） |
| 5 | `python tools/dev.py q 'RRV imgAudit' 20` | `big=OK(n>0)` 是**唯一采纳的 PASS 判据**（`docs/_r35_revoke_img_anchor.md:344`）；`row=0` ⇒ `ImgInfo2` 行没了（`:210`）；`big=MISS` ⇒ 大图当时就没下载过，要靠「到达即预下载」（`:345`） |
| 6 | `python tools/dev.py q 'VFG blocked MEDIA delete' 20` | 历史批次 A.2 判据字符串（`docs/开发提效与后续计划.md:89`；`VoiceFileGuard.kt:312`）。⛔ **0 行不能直接判失败**（60s 节流 / 微信没走 `File.delete`，见 Q2 提醒） |
| 7 | `python tools/dev.py probe ardump --grep 'AR cfg' 20` | 锚点体检（`docs/_r35_revoke_img_anchor.md:312`） |
| 8 | ⭐ **用户可见主判据**：隔 **3~5 分钟**（仍在 10 分钟 TTL 内）回到该聊天，**点开那张被撤回的图片** | **能看到大图**（不是转圈 / 空白 / 「图片已过期或已被清理」）。再各做一次：切页面再回来点开、下拉刷新后点开、**冷启后点开**（Q5 返回档，冷启档 `[需真机]`） |

### 6.3 反向对照（硬要求）

| 项 | 内容 |
|---|---|
| 关哪个 Key | **`antirevoke.msg`**（常量 `Keys.ANTIREVOKE_MSG`，`data/Keys.kt:140`） |
| 默认值 | **`"false"`** —— `data/Keys.kt:437` `PrefSpec(Keys.ANTIREVOKE_MSG, PrefKind.SWITCH, "false", "防撤回", "防止消息撤回")`；`Keys.kt:139,436` 注释「防撤回域（6 项，v6.3 定：**全部默认关闭**）」 |
| 命令 | `python tools/dev.py probe kvset --key antirevoke.msg --val false --grep 'PROBE kvset'`（`docs/_r35_revoke_img_anchor.md:315`） |
| ⚠️ 等 3 秒 | `VoiceFileGuard.on()` 有 3 秒 TTL 缓存（`VoiceFileGuard.kt:185-189`），关后立刻复现会误判「开关无效」 |
| 复现动作 | 再请同一位好友、同一个群发一张图并撤回（**只改这一个自变量**，`docs/_r48_review_autoplay.md:105`「每组只改一个 key；改完不冷启，冷启是第二个自变量」） |
| **预期用户可见** | 消息变成「xxx 撤回了一条消息」提示 / 原文消失，**点开图片打不开**；日志侧 `AR blocked revoke` / `RRV imgAudit` / `VFG blocked MEDIA delete` **三条全部消失，撤回提示正常出现**（`docs/_r35_revoke_img_anchor.md:347-348`） |
| 配套调试 Key（可选） | `antirevoke.debug_self`（`Keys.kt:190-197`，默认 false）：开着可自己撤自己验链路，但**验完记得关**，否则自己也撤不回（`:195`）。⚠️ 它会引入第二个自变量，做严格单变量对照时不要用 |
| 别关错 | `antirevoke.restore_view`（`Keys.kt:188`）只是**显示层伪装**，`Keys.kt:180-186` 明写「防撤回照常在拦，消息原文一直躺在库里」⇒ 关它**不能**做反向对照 |

### 6.4 用户需要配合做什么（好友一律用昵称，遵守昵称铁律）

- **昵称铁律**：`docs/_r19_M4M6M7_真机验证.md:8-14` 原文「目标对象（**昵称 + id 并列，遵守昵称铁律**）」，表内：昵称 **verity**、**文件传输助手**、**测试群聊**。R21c 语音闭环用的也是 verity：`docs/_r21c_voice_file.md:29`「群『测试群聊』好友 verity 发的语音」、`:178`「被撤回的语音：`测试群聊` 群里 verity 发的」。
- 主方案：请 **verity** 在「**测试群聊**」里**发一张图**，用户侧**先别点开**，等 3~5 秒后请 **verity** 撤回；正向、反向各做一次（共两张图）。用户自己负责：隔几分钟点开、切页再回点开、下拉刷新后点开、冷启后点开，并**用眼睛确认能不能看到大图**。
- 备选（verity 不方便时）：用「**文件传输助手**」自己发一张图再自己撤回，须先开 `antirevoke.debug_self=true`（`Keys.kt:190-197`），验完关掉。⚠️ 严格单变量对照优先 verity。
- ⛔ 判据对着用户能看见的事实：`docs/开发提效与后续计划.md:89` A.2 的「**能打开**」是主判据，日志 `VFG blocked MEDIA delete` 只是辅助；不要只报 `blockedMediaCount` 这类内部计数器（`CLAUDE.md` 硬规则：判据要对着用户能看见的事实写）。

[DONE] Q5-Q6

## Q7 失败点与回滚

### 7.1 最可能失败的 3 个点（每个一条排查命令）

| # | 失败点 | 根因 / 锚点 | 排查命令（只读） |
|---|---|---|---|
| 1 | **hook 没挂 / `installed` 日志缺失**（上机第一件事，`CLAUDE.md:75-76`） | `VoiceFileGuard.install()` 有三种失败日志：`java.io.File not found`（`:244`）、`no hook installed`（`:257`）、`VFG installed hooks=…`（`:260`）；`AntiRevoke` 另有 `AR installed`（`AntiRevoke.kt:384`）／`updateWithOnConflict not found`（`:374`） | `python tools/dev.py q 'VFG installed' 20`；0 行再 `python tools/dev.py q 'AR installed' 20`。补救由主会话跑 `python tools/dev.py loop <场景> --grep 'VFG\|AR'`（`tools/dev.py:342-360` 会自动 `loaded()` → lspfix → 双 force-stop 重试） |
| 2 | **微信缓存已无原图 / token 静默失效** | `big=MISS`（大图当时就没下载过，`docs/_r35_revoke_img_anchor.md:345`）、`big=NA`（`wcf://` 虚拟路径，`:238-240`）、`BAD md5` / `VFG protectMedia SKIP no-token` / `token len != 32`（`AntiRevoke.kt:481-484`、`VoiceFileGuard.kt:136,145-148`）。⛔ `adb shell` 读不到 `MicroMsg/<hash>/image2`（权限 700、无 root），只能靠模块自己 `dumpDir` 看（`VoiceFileGuard.kt:326-328`） | `python tools/dev.py q 'RRV imgAudit\|BAD md5\|SKIP no-token\|!= 32' 20`；目录快照 `python tools/dev.py q 'VFG dir' 20`（`VoiceFileGuard.kt:334`）；表形态 `python tools/dev.py probe sql --q 'select bigImgPath,thumbImgPath,status,iscomplete from ImgInfo2 order by id desc limit 5' --grep SQLX`（`docs/_r35_revoke_img_anchor.md:303`） |
| 3 | **`ImgInfo2` 行被删 / `execSQL` 旁路 / 保护池 TTL 过期后重试漏拦** | `row=0` ⇒ 表名赌错或撤回把行删了（`docs/_r35_revoke_img_anchor.md:210`）；`docs/_r35_revoke_img_anchor.md:121-143` 明写「撤回到底删了哪些表 —— 无证据，待真机」；`grep VFG blocked MEDIA delete` = 0 行**不能**判失败（60s 节流 / native unlink，见 Q2）；`AR imgAnchor` 0 行 ⇒ 插入不走 `insertWithOnConflict`，到达锚点根本没接通（`_r35_revoke_img_anchor.md:336`） | `python tools/dev.py probe sql --q 'select count(*) from ImgInfo2' --grep SQLX`（`_r35_revoke_img_anchor.md:302`）＋ `python tools/dev.py q 'RRV imgAudit\|AR imgAnchor\|VFG blocked MEDIA delete' 20` |

### 7.2 「验失败就回滚」具体做法

**① 关哪个 Key（准确名 + 默认值）**

```bash
python tools/dev.py probe kvset --key antirevoke.msg --val false --grep 'PROBE kvset'
```

- `antirevoke.msg` = `Keys.ANTIREVOKE_MSG`（`data/Keys.kt:140`），默认 **`"false"`**（`Keys.kt:437`，`Keys.kt:139,436` 注释「防撤回域全部默认关闭」）⇒ 关回默认即恢复出厂行为。
- 若 6.3 备选方案开过 `antirevoke.debug_self`（`Keys.kt:190-197`），一并 `--key antirevoke.debug_self --val false`（`:195` 警告「验证完记得关掉，否则你自己也撤不回消息了」）；`debug.log` 同样关回。
- ⚠️ 关后**等 >3 秒**：`VoiceFileGuard.on()` 有 3 秒 TTL 缓存（`VoiceFileGuard.kt:185-189`），立刻复现会误判「开关无效」。`kvset` 内部已主动 `AntiRevoke.refresh()`（`EntryReceiver.kt:182`）。

**② 还原哪份备份**

- ⛔ **图片/视频侧没有备份可还原**：`RevokeRescue.kt:121-127` 图片分支只有 `protectMedia` + `auditImage`；`VoiceBackup.kt:111` `needsBackup(origType)`「只在 `type=34` 时动作」；备份目录 `MicroMsg/<hash>/voice_bak/`（`docs/_r21c_voice_file.md:116`）是语音专用。⇒ 图片一旦真被删，**只能请 verity 重发一张 / 让微信重新下载原图**，不要去 `voice_bak` 里找图片（Q3 3.3③、Q4 缺①）。
- 语音侧若在验证中产生了 `voice_bak/`：还原用 `op=voicebak --es sub restore --es sql <imgPath>`（`entry/EntryReceiver.kt:210-213,224-227`；`docs/_r21c_voice_file.md:132,220`）。⚠️ `tools/dev.py` 的 `probe` 只映射 `op/sub/sub2/val/q/key/n`（`tools/dev.py:254-270`），**不映射 extra `sql`** ⇒ 该 op 必须手工广播，写成 `probe voicebak restore` 会拿到 `img=null`（`EntryReceiver.kt:217`）。
- 台账：`op=ardel`（`EntryReceiver.kt:575,586`）可清验证产生的 `rescued` 行，但**通常不必** —— 台账在模块自己的库里，关开关后不生效，不影响微信。

**③ 是否需要冷启**

- **不需要**。`EntryReceiver.kt:181` 明写「改完立刻重读，**不用重启微信**（防撤回的开关走这条）」；`CLAUDE.md:78`「`op=logclear` 清 LogX……**不用重启微信**」。
- ⛔ 反而**禁止为回滚而冷启**：`docs/_r48_review_autoplay.md:105`「**改完不冷启**（冷启会清进程内计数/缓存、重挂 hook，是**第二个自变量**）」。冷启只用于专门验 Q5 返回档的「冷启后点开」。
- 残留自清：`protectedMedia` 10 分钟 TTL 后由 `anyAlive` 把 `hasMedia` 复位（`VoiceFileGuard.kt:112,152-154,299-301`）；`imgAnchor` 30 分钟 TTL（`AntiRevoke.kt:494`）；hook 随进程死才卸（`install()` 无 unhook）。
- **恢复现场**：`python tools/dev.py probe logclear --grep 'PROBE logclear'` 清掉验证日志；若刚做完反向对照，记得把 `antirevoke.msg` **开回 true**，否则用户下次用会以为防撤回坏了。

[DONE] Q7-回滚

## 总结论

**结论：(c) 部分实现。** 图片防撤回的「拦 `File.delete`」主链**已写且全部存活接线**（Q1/Q2/Q4），不是 (b) 那种纯缺口；但**不是 (a)**「只差真机验」——存在静态即可确认的三段缺口（Q3/Q4）：① 图片/视频**无 backup/restore 兜底**（语音有 R21c 三层）；② 聊天侧**未挂 `execSQL` / `delete` / `INSERT OR REPLACE`**，R42 已实证同构 raw SQL 绕过口子（`docs/_r42_block_result.md:112`）；③ `ImgInfo2` 行是否幸存 **[推断]**（`docs/_r35_revoke_img_anchor.md:121-143`）。且全链**未真机闭环**：A.2 仍列在待验表（`docs/开发提效与后续计划.md:82,89`），`_r35_revoke_img_anchor.md:320` 的日志是「**预期日志**」非实录，全仓 `*.txt` Grep `VFG blocked MEDIA delete|RRV imgAudit|VFG protectMedia|AR imgAnchor` → `No matches found`。

**处置建议（这是验证任务，先别开发）**：按 Q6 跑一次真机闭环（含反向对照、用户可见判据「隔几分钟点开能不能看到大图」）→ PASS 则判 (a) 收口并补 Q5 冷启/复位档观测；FAIL 则按 Q3 3.3 的 ①②③ 顺序定位（`RRV imgAudit` 的 `row/big/thumb/hd` → 有没有 `VFG blocked MEDIA delete` → `ImgInfo2` / `execSQL` 通道），再决定补哪段开发。

| 交付件 | 位置 |
|---|---|
| 7 问逐条答案 | Q1 §A/B/C 表、Q2、Q3、Q4、Q5、Q6、Q7（上文） |
| 结论 (a/b/c) | **Q4 + 本总结论 = (c)** |
| 三档边界表 | Q5 §5.1 主表 + §5.2 复位逐条 + §5.3 状态残留总表（**复位档独立，未写成「同返回」**） |
| 真机步骤表 | Q6 §6.1 上机三件事 + §6.2 最小步骤 + §6.3 反向对照 + §6.4 用户配合（好友用昵称 **verity** / **测试群聊** / **文件传输助手**） |

### 证据分级

| 级别 | 内容 |
|---|---|
| **已取证事实（带锚点）** | 类名/行号/接线（`HookEntry.kt:208-260`、Q1 全表）；`VFG blocked MEDIA delete` 字符串仍在 `VoiceFileGuard.kt:312`；图片保护窗 10 分钟（`:111-112`）；`Keys.ANTIREVOKE_MSG="antirevoke.msg"` 默认 `"false"`（`Keys.kt:140,437`）；`VoiceBackup.backup` 只 `type=34`（`VoiceBackup.kt:111,127`）；`AntiRevoke` 只挂 `updateWithOnConflict`（`AntiRevoke.kt:359-360,371`）；R42 的 `execSQL` 按好友批量清位「完全不拦」（`docs/_r42_block_result.md:100-116`）；R21c 语音 +36ms 拦删除时间线（`docs/_r21c_voice_file.md:181-190`）；昵称铁律（`docs/_r19_M4M6M7_真机验证.md:8-14`） |
| **静态推断** `[推断]` | `image2` 下文件名含 32 位 md5、`hd` = `bigImgPath+"hd"`（`RevokeRescue.kt:238-243` 自标 `NA`/`[假设]`）；「撤回时 `ImgInfo2` 行还在」（`docs/_r35_revoke_img_anchor.md:141-143` 自标 ② 推断）；冷启后 `HookEntry` 重新 install（Xposed 常规行为）；`execSQL`/`INSERT OR REPLACE` 对聊天侧的绕过是**同构外推**，非聊天侧实测 |
| **必须真机才能定** `[需真机]` | 隔几分钟点开能不能看到大图（A.2 主判据）；冷启后是否仍能看到；关开关再撤一张的反向对照；`VFG blocked MEDIA delete` 是否真的打（60s 节流/可能没走 `File.delete`）；`ImgInfo2` 行是否被撤回删除；native `unlink` 是否存在；R33 事故后三个文件**能否编译**（本 lane 禁 Gradle，只验证了结构完整） |

> 落盘批次：`[DONE] skeleton` → `[DONE] Q1-Q2` → `[DONE] Q3-Q4`（Q3/Q4 各留一行）→ `[DONE] Q5-Q6`（Q5/Q6 各留一行）→ `[DONE] Q7-回滚` → 下行为 ALL。全程只写本文件，未改任何源码/测试/配置/结论文件，未碰 adb / 真机 / Gradle / 装包 / kvset。

[DONE] ALL
