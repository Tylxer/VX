# R52 撤回图片根因分析（Pro 只读 lane · 重派 / 内置 Hy4）

> **本 lane 性质**：根因推理 + 判据设计 + Hook 时序推导。按 `CLAUDE.md` §四②，主会话不下根因定论，本 lane 只给「可证伪的假设 + 排序 + 下一轮判据」。
> **硬红线（已遵守）**：不改任何源码、不碰 adb、不装包、不跑 gradle、不写微信库、不 `kvset`。唯一写入文件＝本文件。全程只做 Grep / Read(offset,limit)，未整读任何 >300 行文件。
> **分级纪律**：不确定一律标 `[推断]` / `[需真机]`；外部掘金 coder-pig 实验是**二手转引**（`docs/_r49_revokeimg_reference.md:26,100`），**不是本设备实测**，任何结论不得写成「已在本设备证明」。
> **昵称铁律**：好友一律用昵称 **verity** / **测试群聊** / **文件传输助手**，不写裸 wxid。
> **判据纪律**（`docs/项目化配置.md:38-41`）：判据一律对着**用户能看见的事实**（界面大图 / 文件系统里的文件 / 库里的行值 / 日志行）写；⛔ 不许对着 `seen / matched / blocked / row=0 / enabled` 这类内部计数器写判据。
> **分步落盘**：每完成一个子步立刻写盘并留一行 `[DONE] <子步>`；被打断时本文件即为可用半成品。末尾 `[DONE] ALL` 表示全步完成。

## 结论先行（直接回答题目）

**开关开了以后 ≠ 一定能打开。** 三层回答：

1. **已证事实层**：23:55 那次测试 `antirevoke.media_delete=false`，`MTG installed ... enabled=false`（`docs/_r52_device_log_snapshot.md:19,38`），全程零 `MTG` 业务日志 ⇒ R51 新钩子当时 before 第一行即 return，零拦截（`docs/_r52_device_log_snapshot.md:48-50`）。但这**只解释 23:55 那一次**；且 23:55 那张被 verity 撤回的图 `RRV imgAudit row=0`、`big=NULL`（`docs/_r52_device_log_snapshot.md:15,32`），台账本身已提示「大概率不可逆，别拿它当验收样本」（`docs/_r52_device_log_snapshot.md:66-67`）。⇒ **不能用「回头点 23:55 那张」来验开关效果，必须请 verity 重新发一张新图再撤回。**
2. **开关已开层（本轮真问题）**：`kvset true` 已回读、冷启后 `MTG installed ... enabled=true` 已可见（`docs/_r52_device_log_snapshot.md:22-24,43`）。但 `enabled=true` 只保证 `onBefore` 不再第一行 return（`MediaTableGuard.kt:212`），**不保证**：① 删除落在钩子覆盖的通道（`delete`/`execSQL` vs 预编译 statement / `INSERT OR REPLACE` / native unlink）；② 删除到达时 `invalidate()` 已建好 `cachedTokens` 快照（`MediaTableGuard.kt:159,227-231`）；③ 删除 SQL 形态被判据识别（单条 `=`、白名单键列、32 位 token 命中、10 分钟窗口内）；④ 撤回落在本设备「删 `ImgInfo2` 行」这条路（外部实验仅转引）；⑤ 点开时的大图能从服务端取回（`HDIMG fired img=SERVERID://... t=0`）。
3. **真实根因（本 lane 排序，全部 `[推断]`，主会话不下定论）**：按可证伪性与验证成本，**最该先验的不是「开关开没开」，而是四件事**——
   - (a) 撤回时 `ImgInfo2` 行**到底在不在**（`row=0` 是哨兵值，不是「行被删」的证据，见 S2）；
   - (b) 删媒体表走 `delete()` / `execSQL(String)` / `execSQL(String,Object[])` / 预编译 `SQLiteStatement.executeUpdateDelete` / `INSERT OR REPLACE` / native unlink 的**哪一条**（S3 通路表）；
   - (c) 该 DELETE 相对 `RevokeRescue.record() → MediaTableGuard.invalidate()` 的**先后**（S3 核心）；
   - (d) 点开时大图是否依赖 `ImgInfo2` 行 / 需要 CDN 重新下载 / 撤回后服务端是否仍可取（S4 第 1 支）。
   - 「拦住 DB 行 ⇒ 界面能显示大图」只是掘金转引实验的结论，**本设备从未闭环**（`docs/_r52_device_log_snapshot.md:60`）。

## 事实基线（只读，来自台账 ㉞ `docs/_r52_device_log_snapshot.md`）

| 时刻 | 事件（昵称 verity） | 锚点 |
|---|---|---|
| 23:55:52.184 | verity 撤回图片，`msgId=19640471 origType=3`，`AR shape type=268445456 isHint=true` | `_r52_device_log_snapshot.md:12,29` |
| 23:55:52.197 | `RRV record ok=true origType=3 imgPath=THUMBNAIL_DIRPATH://th_5a9a0b372dbc9b4465e776185700f1a7` | `:13,30` |
| 23:55:52.198 | `VFG protectMedia img=5a9a0b372d..(32) ttl=600000ms pool=1` | `:14,31` |
| 23:55:52.201 | `RRV imgAudit msgId=19640471 row=0 status=-999 iscomplete=-999 hdThumb=-999 big=NULL thumb=NULL hd=NA rawBig=null rawThumb=null` | `:15,32` |
| 23:55:52.202 | `AR blocked revoke msgId=19640471` ⇒ `message` 行保住 | `:16,33` |
| 23:55:52.225 | `VFG blocked MEDIA delete img=5a9a0b372d.. path=.../image2/5a/9a/th_5a9a0b372dbc9b4465e776185700f1a7` ⇒ **缩略图文件**保住 | `:17,34` |
| 23:56:32.145 | 冷启重打 installed：`MTG installed hooks=delete,execSQL enabled=false pool=ok switch=antirevoke.media_delete` | `:19,38` |
| 23:58:12.875 | `HDIMG fired msgId=19640498 localId=875524 t=0 img=SERVERID://1487791032802485058 talker=` | `:20,40` |
| 23:58:47 / 23:59:16 | `kvget antirevoke.media_delete = false` / `kvget antirevoke.msg = true` | `:21` |
| 00:00:31 / 00:00:35 | `kvset antirevoke.media_delete=true` / `kvget` 回读 true（写入已复核） | `:22-23,41-42` |
| 00:02:49.062 | 冷启后 `MTG installed hooks=delete,execSQL enabled=true pool=ok` | `:24,43` |

⛔ **两个必须点出的取证疑点**（不得当定论）：
- `HDIMG fired msgId=19640498` 与撤回的 `msgId=19640471` **不是同一个 id**（`:13` vs `:20`）。且 `HDIMG fired` 是模块 `AutoHdImg.fire()` 发起一次 HD 下载时打的日志（`AutoHdImg.kt:307-309,378`），**不是「用户点开了被撤回那张图」的直接证据**。⇒ 用户可见主判据「点开能不能看到大图」必须先确认点开的**就是 verity 23:55 撤回的同一张消息**，否则会拿另一张图的现象给这张图下结论。`[需真机]`
- `HDIMG` 的 `msgId`/`localId` 来自 `t1`（`NetSceneGetMsgImg`）构造参数（`AutoHdImg.kt:17-20,196`），与 `message` 表 `msgId` 的口径是否一致属 `[推断]`（R34 已记 `AutoHdImg` 挂 `t1` 有结构性缺陷，`docs/_r49_revokeimg_forensics.md:51`）。上述 id 不等**也可能只是口径不同**，需核对，不直接判「点错了图」。

---

[DONE] skeleton（头部 + 结论先行 + 事实基线）

## S1 事实分级（三栏表）

> ⛔ 本表最重要的一行：**「`ImgInfo2` 行被删了」目前没有被证明**。`RRV imgAudit row=0` 是「这次查询没拿到行」的哨兵值（`RevokeRescue.kt:253-258,280-286`，见 S2），不是「行被删」的事实。掘金 coder-pig「只拦 `File.delete` 打不开 / 拦媒体表 delete 后能打开」是**二手转引**（`docs/_r49_revokeimg_reference.md:26,100`），**非本设备实测**；`docs/_r52_device_log_snapshot.md:60` 已明确把「拦住 DB 行 ⇒ 界面能显示大图」列为未证。`docs/_r35_revoke_img_anchor.md:121-143`（经 `docs/_r49_revokeimg_forensics.md:139` 转录）自述「撤回到底删了哪些表 —— 无证据，待真机」。

| 已证事实（带 `文件:行号` 锚点） | 静态推断 `[推断]` | 必须真机 `[需真机]` |
|---|---|---|
| 测试当时 `antirevoke.media_delete=false`，`MTG installed ... enabled=false`，全程零 `MTG` 业务日志 ⇒ R51 钩子零拦截（`docs/_r52_device_log_snapshot.md:19,38,48-50`） | 「撤回时微信先删 `ImgInfo2` 行」——掘金实验外推，本设备无一手证据（`docs/_r49_revokeimg_forensics.md:157,313` 自标 ② 推断） | verity 新撤一张图、隔 3~5 分钟**点开能不能看到大图**（唯一主判据，`docs/开发提效与后续计划.md:89` A.2） |
| 旧链路全部正常：`AR blocked revoke`（`message` 行保住）、`VFG blocked MEDIA delete`（**缩略图文件**保住）、语音侧 `VG`/`VFG` 正常（`docs/_r52_device_log_snapshot.md:16-18,51-52`） | 撤回删媒体表 DELETE **晚于** `RevokeRescue.record()`（异步 `executor.submit`）才到达 hook——沿用 R21c 语音时间线 `+36ms` 的同构外推（`docs/_r21c_voice_file.md:181-190`、`docs/_r49_revokeimg_reference.md:102`） | 撤回时 `ImgInfo2` 行**到底在不在**、行值 `n/bigImgPath/thumbImgPath/status` 快照（`docs/_r49_revokeimg_reference.md:125-145` 的 2×2） |
| `RRV imgAudit row=0 status=-999 iscomplete=-999 hdThumb=-999 big=NULL thumb=NULL hd=NA`（`docs/_r52_device_log_snapshot.md:15,32`）⇒ 见 S2：全部是 `auditImage` 的**哨兵初值**，`row=1` 只在 `moveToFirst()==true` 时置位（`RevokeRescue.kt:253-258,280-286,295,305-306`） | 「`HDIMG fired img=SERVERID://...` 表明大图走服务端 ID 通道、需 CDN 重下」——日志形态支持，但「服务端撤回后是否仍可取 / 是否依赖 `ImgInfo2` 行」未取证（`docs/_r52_device_log_snapshot.md:61`） | 删媒体表走 `delete()` / `execSQL(String)` / `execSQL(String,Object[])` / 预编译 `SQLiteStatement.executeUpdateDelete` / `INSERT OR REPLACE` / native unlink 的**哪一条**（`docs/_r52_device_log_snapshot.md:58-59`） |
| `VFG protectMedia ttl=600000ms pool=1` ⇒ 保护池 token 已入 `VoiceFileGuard.protectedMedia`（`RevokeRescue.kt:122` → `VoiceFileGuard.kt:109,122-160`；`docs/_r52_device_log_snapshot.md:14,31`） | `23:58 HDIMG fired msgId=19640498` 与撤回 `msgId=19640471` 不等 ⇒ 可能点开的是另一张图，也可能只是 `t1` 构造参数与 `message.msgId` 口径不同（`AutoHdImg.kt:17-20,196,378`） | 该 DELETE 相对 `record() → invalidate()` 的真实先后；首撤 `cachedTokens` 是否为空（S3） |
| `MediaTableGuard.refresh()` 只在 `install()` / `invalidate()` 调用；`invalidate()` 挂在 `RevokeRescue.record()` 图片分支 `auditImage` 之后（`MediaTableGuard.kt:121,146-161`；`RevokeRescue.kt:126-128`） | 「冷启使 `installed enabled=true` ⇒ 一定能拦」——冷启只刷新 `enabled`，不刷新 `cachedTokens`（后者只在 `invalidate()` 建），故**不充分**（S3） | 开关已 true + 冷启 enabled=true 后，新撤一张图 `MTG` 是否真的跳过了删除（看 `dev.py q 'MTG'` 的 blocked 行，仅作定位，⛔ 不作判据） |
| `kvset` 的刷新清单是 `AntiRevoke / SnsGuard / ConvFilter / MomentsAuto / MomentsExport / AntiRevokeHint.invalidate / HintBadge`，**不含 `MediaTableGuard.refresh()`**（`EntryReceiver.kt:176-200`；`docs/_r51_media_table_guard_impl.md:171,213,279`） | `op=sql --es q` 走 `SnsGuard.runReadOnlySql`（朋友圈独立库 `sns.db`），对 `ImgInfo2` 会打空（`EntryReceiver.kt:652-661`；`docs/_r49_revokeimg_reference.md:107-121` 明写「通道误用」） | 拦住 DB 行后**界面是否真渲染大图**；呈现层 / 点开时二次删除（`docs/_r49_revokeimg_reference.md:140`） |
| `MediaTableGuard` 只挂 `delete` + `execSQL` 两个方法级 hook，判据白名单 `{imginfo2, videoinfo2, wxfileindex2}` 不含 `voiceinfo`（`MediaTableGuard.kt:127-132`；`MediaDeleteLogic.kt:64`，经 `docs/_r51_impl_review.md:71,158` 核实） | `MediaDeleteLogic` 只认单条 `=`，`IN (SELECT…)` / `IN (?,?)` / 泛型 `%s=%s` / `WxFileIndex2` 未取证键列一律 `BULK_OR_UNSAFE`/`UNKNOWN` ⇒ 放行（`docs/_r51_media_delete_logic.md:75-79,396-400`） | `WxFileIndex2` 键列（`docs/_r51_media_delete_logic.md:52,87` 明写「待定」） |
| `VFG` 的 `File.delete` hook 只覆盖 Java 层 `delete` / `deleteOnExit`，**拦不到 native `unlink()`**（`VoiceFileGuard.kt:35-38,251-252`；`docs/_r49_revokeimg_forensics.md:140`） | `ImgInfo2` 已知 DELETE 字面量全是 `IN (SELECT …)` 整会话形态、单条走泛型 `delete from %s where %s=%s`（`docs/_r35_revoke_img_anchor.md:126-139`，经 `docs/_r49_revokeimg_forensics.md:139` 转录）——**dex 字符串取证 ≠ 该业务调用它**（`docs/项目化配置.md:41`） | 冷启后（10 分钟 TTL 内 / 外）能否打开；微信重试删除落在窗口内还是外（`docs/_r51_media_table_guard_impl.md:243`） |
| 图片/视频分支只有 `protectMedia` + `auditImage`，**无 backup/restore**（`RevokeRescue.kt:121-129`；`docs/_r49_revokeimg_forensics.md:155,282`）；语音才有三层（`:130-145`） | 已被删的 DB 行 / native unlink 掉的磁盘文件**不可回滚**（`docs/_r51_media_table_guard_impl.md:245`、`docs/_r51_media_delete_logic.md:370`） | 反向对照：关 `antirevoke.media_delete` 再撤一张（自变量唯一）后打不开 / 出现撤回提示 |

**S1 结论**：`row=0` 只能读成「那一次 `auditImage` 查询没拿到行」，**不能**读成「`ImgInfo2` 行被删了」。把「行被删」当既成事实去改代码，正是 `CLAUDE.md:48-49` D4 与判据纪律要拦的动作；阶段 0 必须先做 S2/S3 的判别取证。

[DONE] S1（事实分级三栏表 · 明确「ImgInfo2 行被删」未被证明、外部实验仅转引）

## S2 `imgAudit row=0` 的解释空间

### 2.1 先定性质：`row=0 / status=-999 / big=NULL` 是**哨兵值**，不是真值

逐行读 `RevokeRescue.kt:246-302`（`auditImage`）：

- `:253-258` 初值：`big=null`、`thumb=null`、`status=-999`、`iscomplete=-999`、`hdThumb=-999`、`row=0`；
- `:280-281` 只有 `moveToFirst()==true` 才把 `row` 置 `1`；
- `:282-286` `big/thumb` 取列值，`status/iscomplete/hdThumb` 用 `as? Int ?: -999` ⇒ **即使查到行但列值为 null，仍回退 -999**；
- `:295` `hd` 在 `big` 为空或非绝对路径时恒为 `"NA"`（不是 stat 结果）；
- `:305-306` `fileState(null)` 返回 `"NULL"`；
- `:278` `m.invoke(db, sql, args) ?: return@runCatching` ⇒ **Cursor 为 null 时静默 return，不打任何日志**；
- `:259-262` `h==null` 会打 `RRV imgAudit no db handle`；`:290-292` 查询异常会打 `RRV imgAudit query failed`。

⇒ **结论**：`row=0 status=-999 iscomplete=-999 hdThumb=-999 big=NULL thumb=NULL hd=NA rawBig=null rawThumb=null` 全部是「这次查询没拿到一行可用于 stat 的记录」的哨兵初值。它**不证明**「`ImgInfo2` 行被删」、**不证明** status 真值 = -999、**不证明**大图文件不存在（`big=NULL` = 列没读到，≠ 文件 MISS）。把它读成「行被删了」是越界（S1）。`[需真机]` 唯一能定的是：换一条独立通道去查行在不在。

⚠️ 台账摘录的「关键原文」（`docs/_r52_device_log_snapshot.md:30-34`）里**没有** `no db handle` 也没有 `query failed`。若完整日志（`dev.py q`）确实无这两行，则解释 ③ 的两个显式分支可排除，但 `:278` 的 cursor=null 静默 return **不产生任何日志**，仍不能被排除 ⇒ 全部标 `[需真机]`。

### 2.2 四种互斥解释 + 每种「只读、一次能跑完」的判别实验

> 探针通道纪律（先读，否则 SQL 会打空）：
> - `op=sql --es q … --grep SQLX`（`dev.py probe sql --q …`）走 `SnsGuard.runReadOnlySql`，用的是**朋友圈独立库 `snsDb`**（`EntryReceiver.kt:652-661`；`docs/_r49_revokeimg_reference.md:107-121`），对主库 `ImgInfo2` 会返回 `no such table` / `no sns db yet` ⇒ **只能用来先验通道，不能当主库判据**。
> - 能打主库的通道是 `--es op label --es sub sql --es sql '…'` → `LabelHuntProbe.runSql()` → `AntiRevoke.wxDb()`（`LabelHuntProbe.kt:246,478,1028`；`EntryReceiver.kt:307-318`），只放行 `select` / `explain` / 只读 `pragma`（`LabelHuntProbe.kt:458-468`），全只读不写库。
> - ⛔ `tools/dev.py probe` **不映射 extra `sql`**（`tools/dev.py:254-271` 只映射 `op/sub/sub2/val/q/key/n`；`docs/_r49_revokeimg_forensics.md:283` 同述）⇒ `sub=sql` 必须**手工 `am broadcast`** 或用 `tools/` 现成脚本。
> - `op=label sub=on` 是挂 rawQuery 钩子（`LabelHuntProbe.kt:150,159`，窗口到期自动卸载 `:134`），用于**捕获/确认主库 DB 句柄**与观测原始 SQL；`sub=sql` 才是执行只读 SQL。两者按 `sub=on` → `sub=sql` 顺序各发一次即可，一次微信前台会话内跑完，不装包、不冷启。
> - 判别一律对着**库里的行值 / 磁盘文件 / 界面**写，⛔ 不对着 `row=0 / seen / matched / blocked` 写判据。

| # | 互斥解释 | 一次能跑完的只读判别实验（优先 `op=label sub=on` + `sub=sql`） | 判别结果怎么读 |
|---|---|---|---|
| **①** | **撤回时微信已先删 `ImgInfo2` 行**（行真没了） | 请 **verity** 在「**测试群聊**」发图后**不点开**，先取基线：`--es op label --es sub sql --es sql "select count(*) as n, hex(bigImgPath) as big_hex, status, iscomplete from ImgInfo2 where msglocalid=<该图 msgId>"`；撤后 3~5 秒再取**同一条**（SQL 形态 `docs/_r49_revokeimg_reference.md:131-136`，BLOB 用 `hex()` 不用 `cast as text`）。同一会话内两次快照，一次跑完 | 撤前 `n≥1`、撤后 `n=0` ⇒ ① 成立（行真被删）；这是 2×2 判据里「索引行没了」那一格（`docs/_r49_revokeimg_reference.md:142`） |
| **②** | **`auditImage` 关联口径本来就查不到**：`where msglocalid=? or msgSvrId=?` 绑的是 `msgId`（`RevokeRescue.kt:268-277`），而 `svr = imgAnchorOf(msgId)?.svrId ?: 0L`；到达锚点拿不到时**只查 `msglocalid=?`**（`:274`）。若 `ImgInfo2.msglocalid`/`msgSvrId` 与 `message.msgId` 不是同一口径，行明明在也查不到 | `--es op label --es sub sql --es sql "select id, msglocalid, msgSvrId, hex(bigImgPath) from ImgInfo2 where bigImgPath like '%5a9a0b372d%' or thumbImgPath like '%5a9a0b372d%' limit 5"`（键列 `docs/_r49_revokeimg_reference.md:85`）；再一条 `pragma table_info(ImgInfo2)` + `select msglocalid, msgSvrId from ImgInfo2 order by id desc limit 5` 看主键口径 | 若能按 md5 查到行、但 `msglocalid` ≠ `19640471` ⇒ ② 成立（**行其实在，是审计 SQL 绑错键**）。此时「行被删」被直接证伪，R51 要修的不是删表拦截而是关联口径 |
| **③** | **db handle 未捕获 / 查询异常被吞 / Cursor 为 null**：`AntiRevoke.msgQueryHandle()==null`（`RevokeRescue.kt:259-262`）、`runCatching` 吞异常（`:290-292`）、`:278` Cursor null 静默 return | 先 `--es op label --es sub on`（挂 rawQuery 钩子捕获主库句柄，`LabelHuntProbe.kt:159,1028`），再 `sub=sql` 跑 ① 那条；同时 `dev.py q 'RRV imgAudit|no db handle|query failed|MTG' 40` 复核完整日志有没有 `no db handle` / `query failed` 两行 | 出现 `no db handle` ⇒ 句柄未捕获；出现 `query failed` ⇒ 查询抛异常；两者都无但 `op=label sub=sql` 能查到行 ⇒ `:278` cursor=null 静默 return 或 ②。③ 成立时 `row=0` 与「行在不在」**完全无关**，是审计通道自身失效 |
| **④** | **该图片行根本不落 `ImgInfo2`**（大图走 `SERVERID://` 服务端通道、或落在 `WxFileIndex2` / `videoinfo2` / `AppMessage` 等其它表；`docs/_r52_device_log_snapshot.md:20,61`） | `--es op label --es sub sql --es sql "select name from sqlite_master where type='table' and (name like '%img%' or name like '%file%' or name like '%video%')"`（列库表先例 `LabelHuntProbe.kt:380,671,734`）；再按 `HDIMG fired img=SERVERID://1487791032802485058` 的 id 反查各表；配合 `dev.py q 'VFG dir' 20` 看磁盘是否只有 `th_` 缩略图、无大图/`<md5>hd`（`VoiceFileGuard.kt:334`；23:55 的 `VFG dir n=10` 里确有多张 `th_` 与 `.png`，`docs/_r52_device_log_snapshot.md:35`） | 各媒体表均无该行、磁盘只有缩略图、且点开走 `SERVERID://` ⇒ ④ 成立（**本设备就没有 `ImgInfo2` 行可删**，R51 拦 delete 的整个前提不成立）。此时「开关开了仍打不开」的根因移到 S4 第 1 支（CDN/服务端） |

**互斥性**：①/②/④ 都表现为 `moveToFirst()==false` ⇒ `row=0`，区别在「独立通道查行」的结果（行没了 / 行在但键不同 / 行本就不在这表）；③ 是查询根本没正常执行。四者用上面 ①②③ 三条实验的结果两两可分，不需要装包、不需要改开关。

### 2.3 S2 给主会话的一句话

**先验 ②（最便宜、最可能）：用 `op=label sub=sql` 按 md5 直接查 `ImgInfo2` 有没有这一行。** 若行在且只是 `msglocalid` 对不上 `msgId`，则「`row=0` ⇒ 行被删」这条推理链当场塌掉，R51 的拦 delete 方向就不是当务之急；若行真没了，再按 ① 的撤回前后快照确认是不是**撤回这一动作**删的。⛔ 无论哪种，都别拿 `row=0` 当「已删」的既成事实去改代码（D4 / 判据纪律）。

[DONE] S2（4 种互斥解释 + 每种一次可跑只读实验 + 哨兵值判定）

## S3 时序推演（核心）

### 3.1 三条硬事实（磁盘源码，带行号）

| 事实 | 锚点 |
|---|---|
| `enabled` 是 `@Volatile private var enabled = false`（进程内初值），`refresh()` 内 `enabled = Kv.getBool(Keys.ANTIREVOKE_MEDIA_DELETE)` | `MediaTableGuard.kt:81,146-148` |
| `refresh()` **只有两个调用点**：`install()` 里 `runCatching { refresh() }`；`invalidate()` 里 `runCatching { refresh() }`。`onBefore` 内**绝不**查库 | `MediaTableGuard.kt:121,160`；`docs/_r51_impl_review.md:132,172` |
| `invalidate()` = `gen++` + `lastIssuedMs = elapsedRealtime()` + **`cachedTokens = snapshotLiveTokens()`** + `refresh()`；token 快照来自 install 期一次性反射的 `VoiceFileGuard.protectedMedia` | `MediaTableGuard.kt:156-161,307-331`；`docs/_r51_media_table_guard_impl.md:151-153` |
| `kvset` 分支刷新清单 = `AntiRevoke / SnsGuard / ConvFilter / MomentsAuto / MomentsExport / AntiRevokeHint.invalidate / HintBadge`，**不含 `MediaTableGuard.refresh()`**（也不含 `VoiceGuard`/`VoiceFileGuard`，两者各有 3s TTL cfg 缓存） | `EntryReceiver.kt:176-200`；`docs/_r51_media_table_guard_impl.md:171,213,279` |
| `invalidate()` 挂在 `RevokeRescue.record()` **图片/视频分支末尾**：`protectMedia`（`:122`）→ `auditImage`（`:126`，一次同步 `rawQuery` + `moveToFirst` + `File.exists`，`RevokeRescue.kt:268-289`）→ `MediaTableGuard.invalidate()`（`:128`） | `RevokeRescue.kt:121-128`；`docs/_r51_impl_review.md:113,323` |
| `onBefore` 三道闸：`:212` `if(!enabled) return` → `:227-231` `cachedTokens.isEmpty()` ⇒ `skipped++ return` → `:233-239` `MediaDeleteLogic.shouldBlock(...)`（叠加 10 分钟窗口）⇒ 命中才置 `param.result` | `MediaTableGuard.kt:209-260` |

### 3.2 删媒体表相对 `record()` 的三种先后（**这是「首撤漏拦」的判定点**）

```
撤回事件 → AntiRevoke 拦 updateWithOnConflict(message)  [AntiRevoke.kt:371,387-392]
        → RevokeRescue.record() [同步]
             ├─ :109  VoiceGuard.invalidate()
             ├─ :122  VoiceFileGuard.protectMedia(...)      ← 池里写入本次 32 位 token
             ├─ :126  auditImage(...)  ← 同步查 ImgInfo2，耗时把 :128 往后推
             └─ :128  MediaTableGuard.invalidate()          ← 这一刻才：refresh()+cachedTokens 快照+lastIssuedMs
        → 微信 executor.submit(vj0.p) 异步删媒体表 / File.delete   [RevokeRescue.kt:110-113；R21c +36ms]
```

| 情形 | `enabled`（内存） | `cachedTokens` | `onBefore` 结果 | 结论 |
|---|---|---|---|---|
| **A 删在 `record()` 之前**（另一线程/事务先清 `ImgInfo2`/`videoinfo2`/`WxFileIndex2`，再回调撤回处理） | **旧值**：若 kvset 是在本次 `install()` 之后写的且没冷启，`installed` 读的是 `false` ⇒ `enabled=false` | 上次撤回的陈旧快照或**空**（冷启后必空） | `:212` 直接 return，或 `:228-231` `skipped++ return` | ❌ **首次撤回漏拦**。这正是题目点名的「即使开关已写库，内存 `enabled` 仍可能为 false」 |
| **B 删与 `record()` 同批 / 紧邻同步**（撤回 UPDATE 与媒体表 DELETE 在同一调用栈或同一事务；DELETE 的 hook 回调早于 `:128`） | 同上，`refresh()` 还没跑 | 同上，快照还没建 | 同 A | ❌ 漏拦。且 `auditImage`（`:126`）是同步 DB 查询，**把 `invalidate()` 又往后推了几毫秒到几十毫秒**，扩大了这个窗口（`docs/_r49_revokeimg_forensics.md:230` 建议撤后等 3~5 秒才观测，侧面说明有异步/延迟） |
| **C 删在 `record()` 之后（异步）** —— R21c 语音同构时间线「UPDATE 被拦 → +2ms 登记 → +4ms 备份 → **+36ms 删除被拦**」（`docs/_r21c_voice_file.md:181-190`），`RevokeRescue.kt:110-113` 自述「异步删除还没跑」 | `invalidate()` 已 `refresh()` ⇒ true | 已含本次 token | 三闸全过 ⇒ `param.result` 跳过删除 | ✅ **唯一能拦住的时序** |
| **C′ `record()` 后重试** | true | 10 分钟内仍在 | 窗口内每次被拦（60s 节流只打 1 行）；**TTL 外 / 冷启后池空** ⇒ `isRevocationWindow=false`、`hasMedia=false`（`VoiceFileGuard.kt:301`）⇒ 无人拦 | ⚠️ 行被删且图片侧**无 backup/restore** ⇒ 永久丢失（`docs/_r51_media_table_guard_impl.md:243,245`） |

⇒ **关键裁决**：能否拦住，取决于「DELETE 到达 `delete`/`execSQL` hook 时，`:128` 的 `invalidate()` 是否已跑完」，而**不是**取决于「`kvget` 是不是 true」或「`installed enabled=true` 有没有出现」。`docs/_r51_impl_review.md:136`（C4）已如实记下同源的「事件驱动刷新 ≤10min 窗口」，只是方向相反（关开关后仍可能拦）；本轮把它反向用到「开开关后仍可能不拦」。该先后**本设备未取证** `[需真机]`。

### 3.3 「冷启使 `installed enabled=true`」是否必要前置？——**必要（之一），但不充分**

**必要（之一），理由是硬源码 + 设备实证**：
1. `kvset` 只写 `Kv`（`EntryReceiver.kt:179`）并刷新 6 个类（`:182-200`），**不会**改 `MediaTableGuard.enabled`（`:81`）。
2. `enabled` 只有两条刷新路径：`install()`（进程启动/冷启）与 `invalidate()`（下一次撤回的 `record()` 图片分支）。
3. 设备实证链完整：23:56:32 冷启 `install()` 读到 `enabled=false`（`_r52_device_log_snapshot.md:19,38`）→ 00:00:31 `kvset true` + 00:00:35 回读 true（`:22-23,41-42`，此时 `installed` **没有**重打）→ 00:02:49 冷启 `install()` 才打出 `enabled=true`（`:24,43`）。⇒ **冷启是把 true 刷进内存 `enabled` 的已证且（在不改 `EntryReceiver` 的前提下）唯一可靠路径**。
4. 若想靠「下一次撤回的 `invalidate()`」代替冷启，就掉进 3.2 的 A/B：那次 `invalidate()` 本身晚于撤回事件起点，删除若在它之前到达，`enabled` 仍是 false ⇒ 首撤漏拦。⇒ 不冷启，连「`enabled=true`」这一最低条件都未必满足。

**但不充分（比「必要」更重要，⛔ 别把冷启当验收通过）**：
- 冷启只跑 `install()` = `refresh()` + `resolvePool()` + 挂两个 hook（`MediaTableGuard.kt:113-143`）；**`cachedTokens` 只在 `invalidate()` 里由 `snapshotLiveTokens()` 建**（`:159`）。
- 冷启后进程内集合**整体清零**（`docs/_r51_media_table_guard_impl.md:231`：账本 4 计数 / `events` / `logAt` / `cachedTokens` / `gen` / `lastIssuedMs` 全清）。⇒ 冷启后、第一次撤回的 `:128` 之前，`cachedTokens` **恒为空**，`onBefore:228-231` 必然 `skipped++ return`。
- ⇒ **冷启只排除了「`if(!enabled) return`」这一道闸，没有排除「`tokens.isEmpty()`」这一道**。删除若落在首撤 `invalidate()` 之前，冷启与否都漏拦。

**⇒ 综合裁定**：`installed enabled=true` 是「开关生效」的**必要前置条件之一**，但**不是「能打开」的充分条件**；真正的充分条件链是「开关 true → 冷启刷 `enabled` → 新撤一张图触发 `record()→invalidate()` 建好 `cachedTokens` → 之后（且仅在 10 分钟窗口内）到达的媒体表 DELETE 被判据识别并跳过删除 → 且大图能从本地/CDN 取回」。缺任一环都打不开。这个链路上每一环都 `[需真机]`，**没有一环能靠日志里的 `enabled=true` 推定**。

### 3.4 删除的「真实通路」逐条标注当前钩子是否覆盖

> 「真实通路」= 微信撤回时可能用来抹掉 `ImgInfo2` / `videoinfo2` / `WxFileIndex2` 行或 `image2` 文件的**全部**落点。⛔ 本设备**未取证**究竟走哪条（`docs/_r52_device_log_snapshot.md:58-59`）；下表是「覆盖矩阵」，不是「实测结论」。

| # | 通路 | 当前钩子 | 覆盖判定 |
|---|---|---|---|
| 1 | `SQLiteDatabase.delete(String table, String whereClause, String[]\|Object[] whereArgs)` | `VoiceGuard.kt:136`（只认 `voiceinfo`）、`MediaTableGuard.kt:127`（白名单 `{imginfo2,videoinfo2,wxfileindex2}`）、`SnsGuard.kt:149`（`SnsInfo`） | ✅ **挂到了**，命中后 `param.result=FAKE_ROWS(1)` 跳过纯 Java 方法体（`docs/_r49_revokeimg_reference.md:85`）。⚠️ 但 `MediaDeleteLogic` 只认**单条 `=`**，见 S4 第 3 支 |
| 2 | `execSQL(String sql)` | `SnsGuard.kt:152`（**只识别/计数 + 改 `param.args[0]`，不拦**，`docs/_r49_revokeimg_forensics.md:122`）、`MediaTableGuard.kt:130`（`fromExecSql` 只读解析最简 `DELETE FROM <单表> WHERE <where>`，命中 `param.result=null`） | ⚠️ **部分覆盖**：void 用 `param.result=null` 跳过的写法 `[需真机]`（`docs/_r51_media_table_guard_impl.md:137` 列 4 条待验）；若真机不成立则该通道漏拦（安全方向，不崩微信） |
| 3 | `execSQL(String sql, Object[] bindArgs)` | `hookAllMethods` 覆盖所有重载（`MediaTableGuard.kt:130`）；`SnsGuard.onExecSql` 处理 `(String,Object[])`（`docs/_r51_media_table_guard_impl.md:51`） | ⚠️ **部分覆盖**：`whereArgs` 从 `a[1]` 取，但 `whereArgs.size>1` 被判 `BULK_OR_UNSAFE` ⇒ 放行（`docs/_r51_media_delete_logic.md:75`）；同 2 的 void 风险 |
| 4 | 预编译 `SQLiteStatement.executeUpdateDelete()`（直调） | **生产代码零挂点**：`RevokeHuntProbe.kt:122-125` 明写「故意不挂」；`docs/_r49_revokeimg_reference.md:66-67,81` 记探针曾挂过（`SnsHuntProbe.kt:190-196`、`docs/_r27_clear_api_forensics.md:8`），但生产侧**没挂** | ❌ **未覆盖**。wcdb `delete()` 纯 Java、入口置 result 会跳过整个方法体 ⇒ `executeUpdateDelete()` 通常根本不执行（`docs/_r49_revokeimg_reference.md:85`）；**但若微信绕过 `delete()` 直调 statement**，两个 hook 都够不着（`docs/_r51_media_table_guard_impl.md:33` 盲区） |
| 5 | `INSERT OR REPLACE` / `insertWithOnConflict` 覆盖原行（`message.imgPath` 被抹） | `AntiRevoke.kt:382` 挂 `insertProbe` **只看不拦**（`:395-400` 明写「怀疑走了 INSERT OR REPLACE，没证实之前不敢直接拦」） | ❌ **未覆盖**。不在任何 `delete`/`execSQL` 钩子范围（`docs/_r49_revokeimg_forensics.md:141`） |
| 6 | `native unlink()` | `VoiceFileGuard.kt:35-38` 自述 hook 只覆盖 Java 层 `File.delete`/`deleteOnExit`，**拦不到 native** | ❌ **未覆盖**。图片/视频侧**无 backup/restore**（`RevokeRescue.kt:121-129`；`docs/_r49_revokeimg_forensics.md:140,155,282`）⇒ 一旦发生即永久丢失、无副本可搬回 |
| 7 | `java.io.File.delete()` / `deleteOnExit()`（磁盘文件） | `VoiceFileGuard.kt:251-252` 全进程挂、`param.result=true`（`:306`）、`VFG blocked MEDIA delete`（`:312`） | ✅ **覆盖**——**这就是 23:55 保住 `th_` 缩略图文件的那一支**（`docs/_r52_device_log_snapshot.md:17,34`）。⛔ 但它保的是**文件**，不是 **DB 行**；文件在 + 行没了照样打不开（S4 第 2 支） |
| 8 | （对照）`updateWithOnConflict("message", …)` 撤回改写 | `AntiRevoke.kt:371,387-392` 拦、`param.result=FAKE_ROWS` | ✅ **覆盖**（`AR blocked revoke`，`docs/_r52_device_log_snapshot.md:16,33`）。⚠️ 但 `message` 行保住 **≠** `ImgInfo2` 行保住 |

**S3 一句话**：钩子矩阵里 ✅ 的三条（1/7/8）恰好对应 23:55 日志里已经正常工作的 `AR blocked` + `VFG blocked`；而 R51 新增的 `MediaTableGuard` 只补了 1/2/3 里的「媒体表」部分，**4/5/6 仍然全裸**，且 1/2/3 还受 3.2 的时序窗口与判据保守性两道闸。⇒ 「开关开了就一定能打开」在静态层面即可判为**不成立**。

[DONE] S3（时序推演 · 三种先后 / 冷启必要但不充分 / 删除通路逐条覆盖矩阵）

## S4 开开关后仍打不开的根因清单（按「可证伪性 + 验证成本」排序）

> **判据纪律（本表铁律）**：每条的「用户能看见的判据」⛔ **不许**对着 `seen / matched / blocked / row=0 / enabled / gen / verdict / skipped` 这类**内部计数器/哨兵值**写（`docs/项目化配置.md:38-41`；`CLAUDE.md:156`「结论有没有可自证的锚点」）。可用的用户可见事实只有三类：**① 屏幕上有没有那张完整大图**（主判据）、**② 库里那一行在不在**（独立只读 SQL 的 `n≥1 / n=0`，不是 `RRV imgAudit row=`）、**③ 磁盘上那个文件在不在**（`VFG dir` / `File.exists`）。日志行只作**定位辅助**（`dev.py q`），⛔ 不单独当判据。
> **排序依据**：可证伪性 = 用户眼睛能否一眼判真伪；验证成本 = 是否一次微信前台会话、不装包、不冷启、不改源码。⛔ 以下「若成立该怎么改」均为**给主会话的建议**，本 lane 不改任何源码。
> **共性前提**：所有条目都假设「`antirevoke.media_delete=true` 已 kvget 回读、冷启后 `MTG installed enabled=true` 可见、请 **verity** 在「**测试群聊**」**重新发一张新图并撤回**」（⛔ 不拿 23:55 那张不可逆的老图当样本，`docs/_r52_device_log_snapshot.md:66-67`）。

### 第 1 支（最便宜、最可证伪、与 DB 行正交）：大图走 `SERVERID://` 服务端 ID 通道

| 项 | 内容 |
|---|---|
| **假设** | `HDIMG fired msgId=19640498 localId=875524 t=0 img=SERVERID://1487791032802485058`（`docs/_r52_device_log_snapshot.md:20,40`；`AutoHdImg.kt:307-321,378`）表明：点开时这张图在 `ImgInfoStorage` 里是**服务端 ID 虚拟路径**而非本地绝对路径，`t=0`（`AutoHdImg.kt:320` `imgT`）表示**原图未下完** ⇒ 点开大图可能要**走 CDN 重新下载**。「是否依赖 `ImgInfo2` 行」「是否需要 CDN 重下」「撤回后服务端是否仍可取」「`SERVERID://` 指向的资源撤回后是否被回收」**四条全部 `[需真机]`**（`docs/_r52_device_log_snapshot.md:61`）。⇒ **即使 R51 把 DB 行全保住，这一支仍可独立让图打不开。** |
| **用户能看见的判据** | 在「**测试群聊**」里点开**verity 刚撤回的那一条消息的气泡**，屏幕上出现**完整清晰大图**（PASS）；若出现转圈不停 / 空白 / 「图片已过期或已被清理」/ 只剩模糊缩略图 / 一直停在加载条 ⇒ 这一支成立方向。⛔ 不看 `HDIMG fired` 的次数、不看 `t`、不看任何计数器。 |
| **一次能跑完的取证手法** | 同一前台会话内做**两图对照**：(a) 请 verity 发一张**不撤回**的图，点开 → 记录界面结果 + `dev.py q 'HDIMG\|CDN\|NetSceneGetMsgImg' 20`；(b) 请 verity 再发一张并撤回，隔 3~5 分钟点开 → 同样记录。再补一条只读 `--es op label --es sub sql --es sql "select hex(bigImgPath), hex(thumbImgPath), status, iscomplete from ImgInfo2 where msglocalid=<该图 msgId>"` 看大图路径是本地绝对路径还是 `SERVERID://`/`wcf://` 虚拟路径（`RevokeRescue.kt:238-243` 自标 `NA`/`[假设]`）。一次会话、不装包、不冷启。 |
| **若成立该怎么改** | 根因不在「拦 DB 删除」而在「大图从哪来」：转向 R49 Q5 **候选 C**（到达即把原图下载/备份到模块自有目录，`docs/_r49_revokeimg_reference.md:218-230`）或补「点开前按 `SERVERID` 触发 CDN 重下」的触发链；⛔ 但要先按 D4（`CLAUDE.md:48-49`）确认 A/B 确实拦不住删除才退到补救，且候选 C 自己有「到达时大图未下载完 → 备份到 0 字节」「项目无播放路径 hook ⇒ 缺 restore 触发点」两个已知失效口（`:227-228`）。另需主会话核对 `SERVERID` 资源在撤回后是否仍可从服务端取（外部网络行为，本 lane 不取证）。 |

### 第 2 支（题目点名必含）：文件被 `VFG` 保住、但**索引行没了**

| 项 | 内容 |
|---|---|
| **假设** | 23:55 `VFG blocked MEDIA delete ... path=.../image2/5a/9a/th_5a9a0b372d...` 只保住**缩略图文件**（`docs/_r52_device_log_snapshot.md:17,34`；`VoiceFileGuard.kt:251-252,306`）。`File.delete` hook **不碰 DB**（`docs/_r49_revokeimg_forensics.md:120,134`）。若撤回同时删掉 `ImgInfo2`/`videoinfo2`/`WxFileIndex2` 行，则「文件在盘 + 行没了」⇒ 点开照样打不开——**这是外部掘金 coder-pig 实验的结论，转引、非本设备实测**（`docs/_r49_revokeimg_reference.md:100-105`；`docs/_r52_device_log_snapshot.md:60`）。R51 的 `MediaTableGuard` 就是为补这一支而写。 |
| **用户能看见的判据** | 仍是**点开被撤回那张图能不能看到完整大图**（主判据）；辅助用「库里那一行在不在」——撤前/撤后各取一次 `ImgInfo2` 行快照，看**那一行是否从库里消失**（`n≥1 → n=0`），以及**磁盘上 `th_` 缩略图文件是否还在**（`VFG dir`）。⛔ 不拿 `RRV imgAudit row=0` 当判据（S2 已证它是哨兵值）。 |
| **一次能跑完的取证手法** | 2×2 快照（`docs/_r49_revokeimg_reference.md:125-145`）：收图不点开时 `--es op label --es sub sql --es sql "select count(*) as n, hex(bigImgPath), status, iscomplete from ImgInfo2 where msglocalid=<msgId>"` 记基线；verity 撤回 3~5 秒后**同一条**再取一次；同时 `dev.py q 'VFG dir\|MTG' 20` 看磁盘文件与拦没拦（仅定位）。若 `n≥1→n=0` 而磁盘 `th_` 文件仍在 ⇒ 文件在、行没了，外部根因在本设备成立。（`op=sql --es q` 打不到主库，见 S2 通道纪律） |
| **若成立该怎么改** | 这正是 R51 候选 A/B 的目标：拦媒体表 delete（`docs/_r49_revokeimg_reference.md:188-216`）。⛔ 但**动手前必须先按 S3 通路表证「删行走 `delete()` 而非 `execSQL`/预编译 statement/`INSERT OR REPLACE`」**——否则扩判据也覆盖不到（`docs/_r49_revokeimg_reference.md:46,90`）。且按 D3（`CLAUDE.md:45-46`），若这一支连续两轮被证伪，须回头问「是不是问题选错了」，别再改判据。 |

### 第 3 支（静态即可确认的高概率漏拦口）：判据保守性把真实 DELETE 放行了

| 项 | 内容 |
|---|---|
| **假设** | `MediaDeleteLogic` 只认**单条精确 `=`** + 白名单键列 + 32 位 token 命中保护集 + 10 分钟窗口；`whereClause==null` / `<` `>` `LIKE` `IS NULL` / **`IN`（含 `IN (SELECT…)` 与字面量列表）** / `whereArgs.size>1` / `WxFileIndex2` 键列未取证 ⇒ 一律 `BULK_OR_UNSAFE` / `UNKNOWN` ⇒ **放行**（`docs/_r51_media_delete_logic.md:75-79,396-400`；`MediaDeleteLogic.kt:102-104,157-189`）。而 dex 扫到的 `ImgInfo2` 已知 DELETE **全是 `IN (SELECT …)` 整会话形态**、单条走泛型 `delete from %s where %s=%s`（`docs/_r35_revoke_img_anchor.md:126-139`）。⇒ 即使删除确实走 `delete()`，R51 也可能**一条都不拦**（`docs/_r49_revokeimg_reference.md:201` 自己列这是候选 A 的最大失效口）。 |
| **用户能看见的判据** | 点开被撤回那张图**能不能看到完整大图**；辅助看撤后 `ImgInfo2` 那一行**是否还在库里**（`n`）。⛔ 不看 `skipped / matched / verdict / blocked`。 |
| **一次能跑完的取证手法** | 撤后取一次行快照（`op=label sub=sql`，同第 2 支）+ `dev.py q 'MTG' 40` 看有没有 `MTG blocked delete/execSQL` 那一行（**只用于定位有没有走到拦截分支，不作判据**）；再用 `LabelHuntProbe` 的 rawQuery 观测钩子（`--es op label --es sub on`）把撤回瞬间真实 DELETE 的 `whereClause` 原文抓出来（一次会话、全只读）。 |
| **若成立该怎么改** | 拿真实 `whereClause` 形态后，由主会话拍板是否放宽 `BULK_OR_UNSAFE`（`docs/_r49_revokeimg_reference.md:254`）；⛔ **放宽不能破保守性红线**「无条件拦 delete 会破坏微信 GC，只拦命中已救消息 + 撤回期的单条删除」（`VoiceGuardLogic.kt:23-26,145-157`；`docs/项目化配置.md:41`）；并补 `WxFileIndex2` 键列（当前 `[需真机]`，`docs/_r51_media_delete_logic.md:52,87`）。 |

### 第 4 支（S3 核心）：首撤时序漏拦 —— 删除早于 `record()→invalidate()`

| 项 | 内容 |
|---|---|
| **假设** | `invalidate()` 排在 `RevokeRescue.record()` 图片分支**末尾**（`RevokeRescue.kt:128`，在 `protectMedia` `:122` 与同步 `auditImage` `:126` 之后）；删除若早于它到达 hook，`enabled` 仍是旧值、`cachedTokens` 为空 ⇒ `onBefore:212` 或 `:228-231` 直接放行（S3 A/B）。冷启只修 `enabled`，不修 `cachedTokens` 空。 |
| **用户能看见的判据** | 请 verity 在「**测试群聊**」**连撤两张图**（同一会话、间隔几秒）：**第一张点开打不开、第二张能打开** ⇒ 首撤时序窗口的可证伪现象（两张都在 10 分钟 TTL 内、同一进程、自变量只有「先后」）。⛔ 不看 `gen / cachedTokens / skipped`。 |
| **一次能跑完的取证手法** | 两张图撤后各取一次 `ImgInfo2` 行快照 + 各点开一次记录界面结果；`dev.py q 'RRV record\|MTG\|VFG' 40` 看两条 `record` 与拦截行的时间戳差（仅定位）。一次会话跑完，不装包、不冷启（冷启反而会清掉池，是第二个自变量，`docs/_r48_review_autoplay.md:105`）。 |
| **若成立该怎么改** | ① 把 `MediaTableGuard.invalidate()` 前移到 `record()` 图片分支**最前**（`protectMedia` 之后、`auditImage` 之前），缩短窗口；或改由消息到达锚点 `AntiRevoke.anchorImage`（`AntiRevoke.kt:461`）提前建 `cachedTokens`；② 给 `EntryReceiver.kvset` 的刷新清单补 `MediaTableGuard.refresh()`（`EntryReceiver.kt:182-200`），消除「开关写库不刷内存」这个根；③ 两条都属改源码/接线，须按 `CLAUDE.md:107-112` 判 Pro 档派子代理，本 lane 不动手。 |

### 第 5 支（成本最高，可能要新探针/新装包）：删除走未覆盖通路

| 项 | 内容 |
|---|---|
| **假设** | 微信撤回删媒体表/文件实际走 S3 通路表的 **4/5/6**：预编译 `SQLiteStatement.executeUpdateDelete()` 直调、`INSERT OR REPLACE` 覆盖原行、native `unlink()`。这三条当前钩子**全未覆盖**；图片侧无 backup/restore ⇒ 漏拦即永久丢失（`RevokeRescue.kt:121-129`）。 |
| **用户能看见的判据** | 撤后 **库里那一行还在不在**（`n`）+ **磁盘上那个文件还在不在**（`VFG dir` / `File.exists`）；以及点开能不能看到大图。⛔ 「日志里没出现 `blocked`」**不能**当判据（60s 节流 / native 无日志，`docs/_r49_revokeimg_forensics.md:107`）。 |
| **一次能跑完的取证手法** | 撤前/撤后 `op=label sub=sql` 行快照 + `dev.py q 'VFG dir\|VFG blocked\|MTG' 20` 文件快照；要区分是哪条通路，需 rawQuery/statement 层观测钩子（`LabelHuntProbe` 的 `--es op label --es sub on`，或 R49 阶段二 `op=writeprobe sub=arm`，`EntryReceiver.kt:663-665`）——这一步若现成探针不够，可能要主会话补一次装包，成本最高。 |
| **若成立该怎么改** | 按真实通路补挂：预编译 statement 层（Q5.2 `:209` 才考虑，wcdb `delete()` 纯 Java 通常不必）、`insertWithOnConflict` 拦截（`AntiRevoke.kt:395-400` 至今只看不拦）、或 R49 候选 C「到达即备份到模块自有目录」兜底 native unlink。⛔ 每条都要先真机证「确实走这条路」，别照着 dex 字符串猜（`docs/项目化配置.md:41`「dex 有 SQL 字符串 ≠ 该业务调用它」）。 |

### 第 6 支（取证疑点，必须排进去）：呈现层 / 点开时二次删除 / `msgId` 口径

| 项 | 内容 |
|---|---|
| **假设** | ① 行与文件**都在**却仍打不开 ⇒ 呈现层/缓存/点开时二次删除（`docs/_r49_revokeimg_reference.md:140`）；② 23:58 的 `HDIMG fired msgId=19640498` 与撤回的 `msgId=19640471` **不等**（`docs/_r52_device_log_snapshot.md:13,20`），且 `HDIMG fired` 是模块发起 HD 下载的日志而非「用户点开」的直接证据（`AutoHdImg.kt:378`）⇒ 「打不开」的观测**可能根本不是对 verity 那张被撤回的图做的**（也可能只是 `t1` 构造参数与 `message.msgId` 口径不同，`[推断]`）。 |
| **用户能看见的判据** | **当面确认点开的就是「测试群聊」里 verity 撤回的那一条消息气泡**（界面上那条「撤回了一条消息」位置对应的图），并看到**完整大图**。⛔ 不用「日志里 msgId 相等」当判据（口径可能不同），用界面定位那条消息。 |
| **一次能跑完的取证手法** | 撤回后先在同一聊天界面里**定位那一条消息**再点开，肉眼确认是不是同一张；同时 `dev.py q 'HDIMG\|RRV record\|AR blocked' 40` 把 `HDIMG` 的 `msgId/localId` 与 `RRV record msgId` / `AR blocked revoke msgId` 并排核对（仅定位口径，不作判据）。一次会话跑完。 |
| **若成立该怎么改** | 若是 ①：修呈现层 / 补 `insertWithOnConflict` 二次删除拦截；若是 ②（点错图）：**这不是代码缺陷，是验收动作错** —— 重做「请 verity 发→撤→隔几分钟点开同一条」这一组，`_r52_device_log_snapshot.md:65-70` 的下一步清单已给。若 `HDIMG` 的 msgId 口径确实与 `message.msgId` 不同，修 `AutoHdImg` 的取值口径（`AutoHdImg.kt:196` 从 `t1` 构造参数取）。 |

### S4 排序说明（为什么这个顺序）

- **第 1 支排最前**：可证伪性最高（用户点两张图一眼判）、验证成本最低（不装包/不冷启/一次会话），且**与「DB 行保没保住」正交**——即使第 2~5 支全修好，这一支仍可独立让图打不开；它同时是台账明确列为「未证」的那一条（`docs/_r52_device_log_snapshot.md:61`）。
- **第 2 支**：题目点名必含，且是 R51 的设计目标；用撤前/撤后两条只读 SQL 即可证伪，成本低。
- **第 3/4 支**：静态即可确认存在（`docs/_r51_media_delete_logic.md:396-400` 与 S3 源码），但要把「放行」和「时序」从「拦住了」里分离出来，需配合 `op=label sub=on` 观测，成本中等。
- **第 5/6 支**：第 5 支可能要补探针/装包（成本最高）；第 6 支可证伪性高但属「验收动作/口径」问题，排在最后先排除，避免把「点错图」误当成代码根因。

[DONE] S4（6 支根因清单 · 含 SERVERID 大图通道支 + 文件保住索引行没了支 · 判据全对用户可见事实）

## S8 行动建议（≤10 行）

1. **现在立刻**：⛔ 别回头点 23:55 那张老图（已不可逆，`docs/_r52_device_log_snapshot.md:66-67`）；先 `dev.py q 'MTG\|RRV\|VFG\|HDIMG\|no db handle\|query failed' 40` 补拉 23:55 完整日志，确认有没有 `RRV imgAudit no db handle` / `query failed`（S2 ③）。
2. **现在立刻**：按 S2 ② 先验最便宜的假设——`--es op label --es sub on` 再 `--es sub sql --es sql "select id,msglocalid,msgSvrId,hex(bigImgPath) from ImgInfo2 where bigImgPath like '%5a9a0b372d%' limit 5"` 按 md5 查行在不在（先验通道：`dev.py probe sql --q 'select count(*) from ImgInfo2' --grep SQLX` 打空属预期，朋友圈库通道够不到主库）。
3. **需要用户配合**：请 **verity** 在「**测试群聊**」重新发一张图，用户**先别点开**；收图时取一次 `ImgInfo2` 行快照，等 3~5 秒请 verity 撤回，撤后再取**同一条**快照（2×2 基线）。
4. **需要用户配合**：撤后隔 3~5 分钟（10 分钟 TTL 内）回到该聊天，**点开被撤回那一条消息的气泡**看有没有完整大图；再各做一次切页回来、下拉刷新后点开。
5. **需要用户配合**：**另做一次**「冷启后点开」——⛔ 不与下拉刷新合并，冷启是第二个自变量（`docs/_r48_review_autoplay.md:105`）。
6. **什么算 R51 成功**：verity 新撤的图隔几分钟点开**能看到完整大图**，且撤后 `ImgInfo2` 那一行**仍在库里**（独立 SQL 的 `n≥1`）；配合第 7 行反向对照变打不开 ⇒ 判成功并收口。
7. **反向对照自变量唯一**：**只**把 `antirevoke.media_delete` 关回 false（`kvset`），⛔ 不冷启、`antirevoke.msg` 保持 true、`debug.log` 保持 true、不动 verity 与「测试群聊」；请 verity 再发一张撤掉，预期变「撤回提示 / 点开打不开」。
8. **什么算证伪**：开关已 true、冷启 `enabled=true`、新撤一张、`ImgInfo2` 行与 `image2` 文件**都在**，但点开仍看不到大图 ⇒ 证伪「拦 DB 行 ⇒ 能打开」，转向 S4 第 1 支（`SERVERID://` / CDN）与第 6 支（呈现层 / msgId 口径）。
9. **若撤后行消失**：先按 S3 通路表用 `op=label sub=on` 证走 `delete()` 还是 `execSQL`/预编译 statement/`INSERT OR REPLACE`，再决定放宽判据或补钩子；⛔ 没证通道前不改代码（D1 先查后设计 / D4 控制优先于观测，`CLAUDE.md:38-49`）。
10. **全程纪律**：好友只用昵称 **verity** / **测试群聊** / **文件传输助手**；判据只写「界面大图 / 库行 `n` / 磁盘文件」，⛔ 不写 `seen/matched/blocked/row=0/enabled/gen`；本 lane 不改源码、不碰 adb、不装包、不 kvset。

---

## 三档边界表（① 进入 / ② 返回 / ③ 复位）

> 语境：`antirevoke.media_delete=true` 已写入、`kvget` 回读 true、冷启后 `MTG installed enabled=true`；以下「接线后」预期**全部 `[需真机]` 才能定**。依据 `docs/_r49_revokeimg_reference.md:260-295`、`docs/_r51_media_table_guard_impl.md:220-247`、`docs/_r51_media_delete_logic.md:347-382`。⛔ **② 档里 TTL（时间维度）与冷启（进程维度）各自单列，不许合并**（`docs/_r51_media_delete_logic.md:360`）；⛔ **③ 复位档逐条分列，不许写「同返回」**。

### 主表（① 进入 / ② 返回）

| 档 | 触发 | 系统动作（锚点） | 用户可见 | 状态残留 |
|---|---|---|---|---|
| **① 进入**（刚撤回那一刻） | 请 **verity** 在「**测试群聊**」撤回一张图片 | `AntiRevoke.revokeGuard` 拦 `updateWithOnConflict`（`AntiRevoke.kt:371,387-392`）⇒ `message` 行保住；`RevokeRescue.record()` 图片分支 `protectMedia`（`:122`）→ `auditImage`（`:126`）→ `MediaTableGuard.invalidate()`（`:128`）；微信随后异步 DELETE 到达 `delete`/`execSQL` hook（`RevokeRescue.kt:110-113`；R21c `+36ms`）⇒ 若 `cachedTokens` 已建且判据命中则跳过删除（S3 C），否则漏拦（S3 A/B） | **点开该图能不能看到完整大图**（主判据）；辅助：库里 `ImgInfo2` 那一行撤后是否还在、磁盘 `th_` 文件是否还在 | `protectedMedia` token（**10 分钟 TTL**，`VoiceFileGuard.kt:112`）、`cachedTokens` 快照、`gen`/`lastIssuedMs`、MTG 账本 4 计数、`imgAnchor` 1 项（**30 分钟 TTL**，`AntiRevoke.kt:470`）、`rescued` 台账 1 行、hook 全挂、**图片侧无备份文件**（`RevokeRescue.kt:121-129`）、微信库行 + `ImgInfo2` 行 + `image2` 文件在（若拦住） |
| **② 返回**（隔几分钟点开 / 切页回来 / 下拉刷新） | 纯 UI 重建，**不产生新的撤回 UPDATE** | 切页/下拉刷新只读 `message` + `ImgInfo2`（`RevokeRescue.kt:268-277` 查询形态），池、`cachedTokens`、hook 不变、文件仍在 ⇒ 窗口内能打开 | 隔几分钟点开 / 切页回来 / 下拉刷新**仍能看到完整大图**（10 分钟 TTL 内） | `auditImage` 的 Cursor 每次 `close()`（`RevokeRescue.kt:287-288`）不残留游标；账本不清零；`AntiRevoke.msgQueryHandle()` 的 DB 句柄是长期缓存 |
| **②-a TTL（时间维度，单列）** | 撤回后满 **10 分钟** | `PROTECT_MEDIA_MS=600_000L`（`VoiceFileGuard.kt:112`）到期 ⇒ `MediaGuardLogic.anyAlive`（`MediaGuardLogic.kt:110-113`）把 `hasMedia` 复位 false（`VoiceFileGuard.kt:301`）；`MediaTableGuard.WINDOW_MS=600_000L`（`MediaTableGuard.kt:70`）同步到期 ⇒ `isRevocationWindow=false` ⇒ `shouldBlock` 恒 false ⇒ **不再拦** | 若此后发生删除（微信 GC / 重试）⇒ 行被删、图打不开 | **清除条件**：到期；**残留**：账本仍在、hook 仍在、开关仍开、`cachedTokens` 不自动清（靠窗口判定失效）、池从「保护态」变「裸奔态」。⛔ **不与「切页回来」合并**：切页回来池还在，TTL 到期池失效 |
| **②-b 冷启（进程维度，单列）** | 微信进程被重建 | `HookEntry` 重新 `install()` ⇒ `MTG installed` 重打（`enabled` 从 KV 重读）、`mediaPoolRef` 重新反射；`cachedTokens` / `gen` / `lastIssuedMs` / **账本 4 计数 + `events` + `logAt` 全部进程内清零**（`docs/_r51_media_table_guard_impl.md:231`），`verdict` 回 `NO_EVENT` | **冷启后能否仍打开 `[需真机]`**（池空，若冷启后微信重新发起删除无人拦） | **清除条件**：进程死；**残留**：持久化态保留（`rescued` 台账、`image2` 原文件、语音 `voice_bak/`、微信库行）。⛔ **与 TTL 分列**：TTL 只让池过期、账本与 hook 都在；冷启是进程内集合**整体清零** + hook 重装 |

### ③ 复位档（逐条分列 · 每条写清除条件与残留 · ⛔ 不写「同返回」）

| 复位动作 | 发生什么 | 清除条件 | 残留 |
|---|---|---|---|
| **关 `antirevoke.media_delete`** | `refresh()` 只在 `install()`/`invalidate()` 调，`kvset` 刷新清单不含本类（`EntryReceiver.kt:176-200`）⇒ 关后 `enabled` 仍是旧值，要等下一次撤回 `record()→invalidate()→refresh()` 或进程重启才刷成 false（`docs/_r51_media_table_guard_impl.md:237`）；刷成 false 后 `onBefore:212` 第一行 return | 下一次撤回的 `invalidate()` 或进程重启（**不是即时**） | 关后 ≤10 分钟窗口内仍可能拦（`docs/_r51_impl_review.md:136`）；hook **不卸载**只空转；`protectedMedia` 不清空、旧 token 靠 TTL 自然过期；账本停止增长；不残留任何对微信库的写入 |
| **关 `antirevoke.msg`** | `AntiRevoke` 不再拦 UPDATE ⇒ `RevokeRescue.record()` 不触发 ⇒ 图片分支 `protectMedia` 与 `MediaTableGuard.invalidate()` 都**不跑**；`VoiceFileGuard.on()` 3 秒 TTL 缓存（`VoiceFileGuard.kt:185-189`）关后最多 3 秒内仍拦 `File.delete` | `kvset` 主动 `AntiRevoke.refresh()`（`EntryReceiver.kt:182`）+ 3 秒 TTL 到期 | 原文消失、`rescued` 台账不写、池不新增、`cachedTokens` 不重建、账本不增长、hook 常驻；`rescued` 台账**跨关开关保留**（模块自有库，只有 `ardel`/`RevokeRescue.sync` 会动） |
| **保护池 10 分钟 TTL** | `PROTECT_MEDIA_MS`（`VoiceFileGuard.kt:112`）到期 ⇒ `MediaGuardLogic.anyAlive`（`:110-113`）把 `hasMedia` 复位 false（`VoiceFileGuard.kt:301`），`mediaHit` 返回 null | 到期（`anyAlive`）、或进程死 | 旧 token 仍在 `protectedMedia` Map 里但不再命中；`hasMedia=false`；快判回「连 path 都不取」的零开销（`:299-301`）；**与 imgAnchor 30 分钟 TTL 不同载体不同时长** |
| **`imgAnchor` 30 分钟 TTL** | `AntiRevoke.kt:470` `IMG_ANCHOR_TTL_MS` + `:468-470` 256 上限淘汰 | 30 分钟 TTL / 256 上限 / 进程死 | **不清空、冷启丢**（`docs/_r49_revokeimg_reference.md:292`）；与保护池 10 分钟 TTL 单独复位 |
| **账本冷启清零** | `seen`/`matched`/`blocked`/`skipped`（4 个 `AtomicLong`）+ `events`(≤64) + `logAt`(≤64) + `cachedTokens` + `gen` + `lastIssuedMs` **全部进程内**，冷启整体清零 | **仅进程死亡** | 清零后 `verdict` 回 `NO_EVENT`；⛔ **与 TTL 分列**：TTL 到期时账本仍在、hook 仍在，冷启是整体清零 |
| **hook 随进程死才卸** | `HookEntry.kt` 的 `install()` **无 unhook**；关开关 / TTL 过期 / 清缓存都不卸载 `delete`/`execSQL` 上的 callback | **仅进程死亡**（`docs/_r49_revokeimg_reference.md:289`） | 关开关后 callback 常驻但空转（`enabled=false` 时第一行 return）；⛔ 与「运行时降级」和「删源码彻底回滚（需重装 + 重启）」两种语义区分（`docs/_r51_impl_review.md:279`） |
| **重试落在窗口外 / 冷启池空** | 微信重试删除（`VoiceGuard.kt:64`；`docs/_r42_bit3_design.md:287`「5 秒 360 次」）：窗口内每次被拦、60s 节流只打 1 行；窗口外或冷启后 `cachedTokens` 空 ⇒ `isRevocationWindow=false` / `tokens.isEmpty()` ⇒ **无人拦、DB 行被删** | **无**（这是漏拦终态，无清除动作） | ⛔ 图片侧**无 backup/restore**（`RevokeRescue.kt:121-129`）⇒ 行/文件一旦被删即**永久丢失**；语音侧还有 `scheduleRestore` 兜底，图片没有 |
| **清缓存** | `op=logclear` 只清 LogX 环形缓冲（`EntryReceiver.kt:165-168`），**不动池/台账/文件**；若指微信「存储空间→清缓存」删 `image2`：开关**开着** ⇒ `File.delete` 被拦 ⇒ 存储只增不减（`VoiceFileGuard.kt:67-68,175-177`）；开关**关着** ⇒ 真删 | `logclear` 只清日志；真删文件只在「关开关 + 微信清缓存」同时成立 | 返回档**不会删文件**；这是**唯一**能真正把已保护图片删掉的路径 |
| **已被删的行 / 文件不可回滚** | R51 只能在 `beforeHookedMethod` **阻止删除发生**，**没有任何 restore 能力**：`ImgInfo2` 行一旦被删、`image2` 文件一旦被 native `unlink`（`VoiceFileGuard.kt:35-38`）删除，均无副本可还原（`docs/_r51_media_table_guard_impl.md:245`、`docs/_r51_media_delete_logic.md:370`） | **无**（**不可逆终态**） | 不属于任何「返回」路径；⛔ 返回档前提是「行与文件都还在」；23:55 那张老图即落在此态，别拿它当验收样本 |

---

## 落盘日志（分步）

[DONE] skeleton（头部 + 结论先行 + 事实基线）
[DONE] S1（事实分级三栏表 · 明确「ImgInfo2 行被删」未被证明、外部实验仅转引）
[DONE] S2（4 种互斥解释 + 每种一次可跑只读实验 + 哨兵值判定）
[DONE] S3（时序推演 · 三种先后 / 冷启必要但不充分 / 删除通路逐条覆盖矩阵）
[DONE] S4（6 支根因清单 · 含 SERVERID 大图通道支 + 文件保住索引行没了支 · 判据全对用户可见事实）
[DONE] S8（≤10 行行动建议）
[DONE] 三档边界表（① 进入 / ② 返回，TTL 与冷启各自单列 / ③ 复位逐条分列，未写「同返回」）

> 红线自检：全程只 Grep / Read(offset,limit)，未整读 >300 行文件；未改任何源码 / 测试 / 配置 / 其它文档；未碰 adb / 真机 / Gradle / 装包 / kvset / 微信库；唯一写入文件＝本文件。好友一律用昵称 **verity** / **测试群聊** / **文件传输助手**。外部掘金实验仅作转引、未当本设备实测。

[DONE] ALL
