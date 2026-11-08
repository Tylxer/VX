# R47 只读独立复核报告

主题：判定「朋友圈自动导出真落盘（P1 / R25）」与「R45 视频解密在生产链路生效（P2）」两条结论的证据链是否成立。

## 复核范围
- 源码：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/` 下 `MomentsExport.kt`、`MomentsAuto.kt`，及 `MomentsLogic.shouldDecryptVideo`、`SnsVideoDecProbe.decryptInPlace`、`Keys.MOMENTS_VIDEO_DECRYPT`、`downloadVideo` 相关调用链。
- 文档：`docs/_r25_moments_bg.md`、`docs/未完成项与计划.md` §2.4、`docs/_r44_chain.md`、`docs/conclusions/moments_cdn.md`。
- 台账：`_kb/moments_cleanup_20261108.txt`、`.workbuddy/memory/2026-11-08.md` L95。
- 命题：P1 是否闭环；P2 是否闭环；三档边界（进入 / 返回 / 复位）是否被证据覆盖。

## 方法
1. 只读。未改动任何源码 / 配置 / 测试；未连接设备，未执行 adb / Gradle / 装包；本次仅新增本结论文件。
2. 以源码为判据：所有结论引用 `文件:行号` + 源码片段；计数器语义、触发路径、解密 gate 均以代码实际分支为准，不以统计数字表象推断。
3. 主会话采集的设备侧事实（文件清单、字节头、txt 正文、estat/stat 计数、时间戳）标注为「原始事实」，因本次不接设备，只复核其「可与源码互证」的部分；无法互证的部分按证据不足降级，不替设备事实背书。
4. 每条问题独立定档：`[PASS]`=该子命题可被现有证据支撑；`[FAIL]`=现有证据否定该子命题；`[UNCERTAIN]`=证据不足，需补充。
5. 报告分块落盘：本文件先建范围 / 方法 / 原始事实清单，随后每答完 2 问追加一块，末尾汇总。

## 原始事实清单（主会话只读取证所得；本次复核「可核对」部分）
F1. `_kb/moments_cleanup_20261108.txt`（mtime 04:21:41）：导出根 `/storage/emulated/0/Documents/WXGroup/moments/` 清理前 239 个一级目录 / 749 文件 / 693MB；04:27 删 236 个、保留 3 个（名单内），剩 12 文件 / 12MB；`.workbuddy/memory/2026-11-08.md` L95 记 04:31 复核仍 3 目录。当时 `moments.export_list` 真值 3 项：`wxid_xij8e08lqr3e22` / `wxid_faryuijzyxj022` / `wxid_l2ocidx1m40021`。
F2. 现（19:4x）同目录：4 个一级目录 / 15 文件 / 25MB。新目录 `东营易泰新材料王琪(wxid_xxg6w848hmz622)` 建于 `2026-11-08 15:40:46`，含 `-3396603525724032237.txt` 1040B @15:40:46、`img_-3396603525724032237_0.jpg` 17378B @15:40:46、`vid_-3396603525724032237_0.mp4` 13195075B @17:10:52；另有 `落浔张桂凤(wxid_xij8e08lqr3e22)/2026-11-05-2141/vid_-3398536920689724692_0.mp4` 9884961B @17:47:23（同目录 txt/img 为 11-05 21:53 旧文件）。
F3. 两个新 mp4 头 16 字节均 `00 00 00 1c 66 74 79 70 69 73 6f 6d 00 00 02 00` = `ftyp isom` 明文 MP4；R45 反向对照实测密文头 `83 2f bc fc 7b d2 85 8b`（随机字节）。
F4. 新 txt 正文：`snsId=-3396603525724032237` / `作者=东营易泰新材料王琪(wxid_xxg6w848hmz622)` / `时间=2026-11-08 13:42` / `类型=15 来源=2 localFlag=2` / 正文「微信小视频」+ 升级模板链接 / 媒体两条 `snsvideodownload` URL。
F5. `op=moments sub=estat`（19:44:27）：`enabled=true images=true videos=true running=false lastRunAt=1794137790361(=19:36:30) runs=15 written=0 skipped=12 listMiss=5988 err=0 imgCopied=0 imgAlready=6 imgNoMedia=1 vidNotVideo=4 vidCopied=2 vidAlready=0 list=4`。`op=moments sub=stat`（MomentsAuto）：`refresh=true load=true interval=1h lastAt=1794134819623(=18:46:59) ticks=94 refreshes=2 bgSync=[ok=2 failed=0 watching=false lastOkAt=18:46:59] timeline binds=358 loads=4 grows=4`。`kvget moments.export_list` = 4 项（新增 `wxid_xxg6w848hmz622`）。
F6. 设备 `lastUpdateTime=2026-11-08 16:28:33`（上一轮装 R46 APK + 双冷启微信）；git 提交 `4ac6aae` 04:57、`3534e13` 16:45。15:40 的 txt/img 早于 16:28 冷启，两个 mp4 晚于冷启。
F7. logcat 环形缓冲已被 `K7 perf` 刷屏覆盖，15:40 的 `export done ... written=1` 与 `poke reason=bg-sync` 原始日志取不到。

## 逐条判定

> 行号引用以 `wxgroup/app/src/main/java/com/lx/wxgroup/` 为根；`F1–F7` 见文首原始事实清单。

### 问题1 · 计数器语义 / `runs=15 written=0` 能否否定 15:40 落盘

[PASS] 问题1 — 证据: `feature/MomentsExport.kt:321-323,341,383-385,751,760-763,862,881-883,908,1813-1833`；`MomentsExport.kt:198-199,208,564-581`；`MomentsLogic.kt:67,1120-1121`；`docs/_r25_moments_bg.md:119,130-134`；F5/F6 — 说明：

**判定：计数器是「进程内累计」，不是「每轮重置」；`runs=15 written=0` 不能用来否定 15:40 的落盘。**

1. 定义处全部是 `MomentsExport` object 成员 `AtomicLong(0)`，全文件无一处 `set(0)` / 归零：`:321-323` `private val runs = AtomicLong(0)` / `written` / `skipped`；`:341` `imgCopied`；`:383` `vidCopied`；`:385` `vidAlready`。全仓 Grep `vidCopied.incrementAndGet()` 仅 `:1593` 一处。
2. `stat()` 打印的是累计值：`:1818` `"lastRunAt=$lastRunAt runs=${runs.get()} written=${written.get()} "`、`:1828` `"vidCopied=${vidCopied.get()} vidAlready=${vidAlready.get()} "`；`probe():1853 "estat" -> stat()` ⇒ F5 的 `runs=15 written=0 skipped=12 vidCopied=2 vidAlready=0` 全是**当前进程自启动以来的累计**。
3. 「每轮重置」的是另一套同名量：`runBlocking()` 内局部 `:760-763 var w=0 / var sk=0 / var lm=0 / var er=0`，`export done` 拼接处 `:908` `"$TAG export done rows=$rows written=$w skipped=$sk listMiss=$lm ..."`。即日志里的 `written=$w` 是**本轮**值、`estat` 里的 `written=` 是**累计**值；R25 文档 `:119` 的 `written=0` 属前者。两者同名不同义，混用会误判。
4. 唯一「重置」来源是**进程重启**：object 单例随宿主进程重建，所有 `AtomicLong` 归零。F6 `lastUpdateTime=2026-11-08 16:28:33`（装 R46 APK + 双冷启）⇒ 15:40:46 的 txt/img 在冷启**之前**，其 `written=1` 已被清零；19:44 的 `runs=15 written=0` 只描述冷启后的 15 轮（这 15 轮名单内 4 人无新动态，`skipped=12` 全部命中去重，自洽）。
5. 可复算命令输出：`python -c "import datetime; tz=datetime.timezone(datetime.timedelta(hours=8)); [print(ms, datetime.datetime.fromtimestamp(ms/1000,tz)) for ms in (1794137790361,1794134819623)]"` ⇒ `1794137790361 → 2026-11-08 19:36:30 +0800`、`1794134819623 → 2026-11-08 18:46:59 +0800`，与 F5 标注一致；`git show -s --format="%h %ci %s"` ⇒ `4ac6aae 2026-11-08 04:57:38 +0800`、`3534e13 2026-11-08 16:45:18 +0800`（HEAD），与 F6 一致。
6. 附带核对（只读 git）：`git show --stat 4ac6aae` 含 `MomentsExport.kt +512 / SnsVideoDecProbe.kt +493 / MomentsLogic.kt +169 / Keys.kt +45`；`3534e13` 只动 `BatchPicker.kt` + 单测 + 文档，**未碰导出/解密链路** ⇒ 16:28 装的 APK 虽早于 `3534e13`，本报告所读导出/解密源码与设备运行版本一致，不存在「读新码、跑旧码」错配。

### 问题2 · `vidCopied` 归属

[FAIL] 问题2 — 证据: `MomentsExport.kt:867,892-895,1537-1538,1545-1598,1593,1739-1806,1779,1906-1919,1924-1947`；`MomentsLogic.kt:893-897`；F2/F5 — 说明：

**判定：`vidCopied=2` 不能证明这两个 mp4 是「导出主循环」落的；源码显示四条路径共用同一个计数收口点，按时序更可能是冷启后的自动视频回填轮。**

调用链（全部汇到 `:1593 vidCopied.incrementAndGet()`）：
- ① 主循环：`runBlocking():867 if (c.copyVideos) copyVideosForRow(row, dir, detail = true, st = null)` —— 仅在 `:859-862` 新写 txt 成功（`written.incrementAndGet()`）之后顺手下视频；
- ② 自动回填：`runBlocking():892-895 if (c.copyVideos && !stop) backfillVideos(root)` → `:1779 copyVideosForRow(row, dir, detail = false, st = st)` —— **每轮导出末尾自动跑**，专门给「txt 已存在但视频缺」的 type=15 补视频（`:1773-1777`）；
- ③ `vidfill` 探针：`probe():1906-1919` → `backfillVideos(c.root)`，同 ②；
- ④ `viddl` 探针：`probe():1924-1947` → `:1939 copyVideosForRow(row, dir, detail = false, st = null)`，注释 `:1921` 明写「走**生产同一条** [copyVideosForRow] 链路（判定+下载+计数）」。
- 四者共同：`:1537 val r = downloadVideo(urls.first(), dst, encKey)` → `:1538 return countVid(r.outcome, ...)` → `countVid():1592-1595 VidOutcome.COPIED -> { vidCopied.incrementAndGet(); st?.let { it.copied++ } }`。`:1501` 注释「全局计数两条路径都照常累加」 ⇒ `st = null`（主循环 / viddl）**照样递增全局 `vidCopied`**。
- `VidOutcome.COPIED`（`:1347-1350` 11 值枚举）对应 `downloadVideo():1467-1472 tmp.renameTo(dst)` 成功的唯一结局 ⇒ `vidCopied=2` = 自进程启动以来恰好 2 次 rename 成功；与 F2 两个新 mp4 数量一致是**强关联**，但**不携带来源信息**。
- 时序反证「主循环」：两个 mp4 是 17:10:52 / 17:47:23（F2），均**晚于** 16:28 冷启；15:40:46 的 txt 是冷启**之前**落的。冷启后 `backfillVideos()` 每轮末尾自动跑，遇「txt 已在、视频缺」的 row 就补（`:1773-1779`）⇒ 这两个视频更符合 ② 的形态。F5 `vidAlready=0`（无「已存在」短路，`:1523,1569-1570`）也支持「冷启后这两个文件是首次下载、无重复」。
- 要归属只能靠被覆盖的日志：`:1594 "vid copied snsId=..."` 仅 `detail=true`（只有主循环 ① 打），或 `:1797-1804 "vid backfill rows=... copied=..."`（②/③ 打），或 `VIDDL ... outcome=copied`（④ 打，`:1943-1946`）。这三行都在 F7 被刷掉的 logcat 里。

### 问题3 · 触发源

[UNCERTAIN] 问题3 — 证据: `MomentsExport.kt:533-544,607-620,615,1861-1873`；`MomentsAuto.kt:175-177,282-305,332-349,340,343,670-684,696-713`；`core/MomentsSyncTrigger.kt:188-205,295-372`；`entry/EntryReceiver.kt:456-469`；`ui/` 目录 Grep `ACTION_PROBE|sendBroadcast|moments|runAsync` 零命中；`docs/_r25_moments_bg.md:96-104,110-134`；`docs/未完成项与计划.md:168-172` — 说明：

**判定：不能据现有证据排除「手动起了一轮」，也不能据『13:42 发布 → 15:40 落盘』判定 P1 已闭环；应降级为「有强证据但未拿到触发源日志」。**

合法触发路径与各自在日志里留下的东西（由源码得出，非照抄文档）：

| 路径 | 代码 | 独有日志 | 需人工 |
|---|---|---|---|
| a 定时 tick | `MomentsExport.onTick():533-544`：`if (!c.autoExport) return` → 空名单安全阀 → `MomentsLogic.exportDue(lastRunAt, now)`（`:1120-1121`，10min 节流）→ `runAsync():543` | **无任何触发源日志**，只有 `export done`（`:908`） | 否 |
| b 后台同步 poke | `MomentsAuto.doRefresh():332-349` 同步成功 → `:343 MomentsExport.pokeExport()` → `pokeExport():607-620` | `MOMENT refresh bg-sent ...`（`MomentsAuto.kt:340`）+ `MOMENT poke reason=bg-sync`（`MomentsExport.kt:615`） | 否（由 `onTick` 到点或 `sub=refresh` force 触发） |
| c 手动 `sub=export` | `EntryReceiver.kt:456-466` → `MomentsExport.probe():1861-1873` → `runAsync()` | `MOMENT export started=true`（`:1868`），随后同一条 `export done` | 是 |
| d 手动 `sub=refresh` / `msync sub=sync` | `MomentsAuto.probe():704-708` / `MomentsSyncTrigger.syncNow():205` | `MOMENT refresh forced ok=...` / `MSYNC OK scene=256 ...`；成功时经 b 间接导出 | 是 |

1. **a / b / c 三条路径共用 `runAsync() → runBlocking()`**（`:564-581`、`:908`），`export done` 行**不含 reason 字段**，产物（txt/img/vid 文件名与内容）逐字节相同 ⇒ 即便 logcat 完整，仅凭 `export done written=1` 也**无法区分** a/b/c；只有 `poke reason=bg-sync` 能把 b 单独认出来，而它正是 R25 §5 判据链（`_r25_moments_bg.md:117,129`）要的那条。
2. `pokeExport` 全仓**唯一**调用点是 `MomentsAuto.kt:343`（Grep `pokeExport` 全仓仅此 + 定义处）；`MomentsSyncTrigger.onGYNetEnd` watcher（`:295-372`）只打 `errType/errCode` 日志、**不触发导出** ⇒ bg-sync 唤醒导出的链唯一。
3. 手动入口盘点：全仓 `MomentsExport.runAsync()` 仅由 `onTick` / `pokeExport` / `probe("export")` 调用；`ui/` 目录对 `ACTION_PROBE|sendBroadcast|moments|runAsync` **零命中** ⇒ 「用户手动点一次导出」在代码里**只能**是 `op=moments sub=export` 广播（探针）。若「该时段均未发探针」属实且涵盖 `sub=export`，**这一条具体路径可排除**；但该陈述是人工陈述、logcat 已被覆盖（F7），**无法反证**，不能写进闭环结论。
4. 更根本的是：即使排除 c，**a（定时 tick）与 b（bg-sync）仍不可分**。13:42 发布 → 15:40 落盘相隔约 2 小时，完全落在 `TICK_MS=5min`（`:198`）＋ `EXPORT_MIN_INTERVAL_MS=10min`（`MomentsLogic.kt:67`）的定时轮覆盖内 ⇒ 定时 tick 独立就能解释落盘，不需要 bg-sync。而 R25 原始判据（`_r25_moments_bg.md:132-134`、`未完成项与计划.md:170-171`）要的是「好友新发一条 → 观察是否真生成新文件」且链路是 `poke reason=bg-sync → export done`；现在只有「文件出现」，没有「哪条路径写的」。
5. F5 的 `MomentsAuto.stat()`（`refresh=true load=true interval=1h refreshes=2 bgSync=[ok=2 ... lastOkAt=18:46:59]`，对应 `MomentsAuto.kt:670-684`）是**冷启后**的进程内累计（`ticks/refreshes/binds/loads/grows` 同为 `AtomicLong`，`:175-181`），`lastOkAt=18:46:59` 晚于 15:40 ⇒ **不能回推**冷启前 15:40 时段发生过 bg-sync。

**降级措辞（建议写进文档）**：「2026-11-08 取得『名单内新好友 13:42 发布 → 15:40 导出目录新增该 snsId 的 txt/img』的落盘实证；但 15:40 那轮的 `export done written=1` 与 `poke reason=bg-sync` 原始日志被 logcat 环形缓冲覆盖，且 16:28 装包双冷启把进程内计数器清零 ⇒ 触发源（定时 tick / 后台同步 poke / 手动 `sub=export`）无法区分，`written>0` 当时是否成立也无计数器留痕。**记为『有强证据但未拿到日志，未闭环』**，不写『已闭环』。」

### 问题4 · `written>0` 判据的正确写法

[PASS] 问题4 — 证据: `MomentsExport.kt:824-830,854-862,868-877,908`；`MomentsLogic.kt:33,163-172,893-897,960-988`；`Keys.kt:387-388`；`docs/未完成项与计划.md:168-172`；Grep `writeText|writeBytes|renameTo|FileOutputStream|RandomAccessFile` 全 `com/lx/wxgroup` — 说明：

**判定：在同一份源码内，「新 snsId 的 txt 从无到有」与 `written>0` 逻辑等价；§2.4 的判据写法正确，但只在「本模块写盘」这一前提下成立。**

1. 去重分支 `:825-830`：`val txt = File(dir, MomentsLogic.textFileName(row.snsId))` → `if (txt.exists()) { exported.add(row.snsId); sk++; continue }`。磁盘已有该 txt ⇒ 永不写、永不 `written++`。
2. 写盘与自增 `:854-862`：`tmp.writeText(MomentsLogic.renderText(item))` → `tmp.renameTo(txt)` → `if (ok) { exported.add(row.snsId); w++; written.incrementAndGet() }`。`written` **只在这一处**自增，且严格在 `renameTo` 成功之后；写失败走 `:868-877`（`er++` / `errs.incrementAndGet()` / 删 tmp），不增 `written`。⇒ `written>0` ⟺ 「至少一次新 txt 落盘成功」，与 `export done written=$w` 的 `w` 同源（`:861`）。§2.4「`written>0` 且磁盘出现新文件」是同一件事的两次观测，不冲突。
3. 模块内**没有第二条写 txt 的路径**：全仓 Grep 的写盘命中为 `MomentsExport.kt:856-857`（txt）、`:1175/1190`（图片 tmp+rename）、`:1421/1467`（视频 tmp+rename）、`SnsVideoDecProbe.kt:161/435`（探针 `.dec` 输出 / 原地改写视频）、`BackupIO.kt:117` / `LogX.kt:84` / `LabelHuntProbe.kt:1189` / `MassSendPage.kt:423`（均与导出根无关）⇒ 导出根下 txt 的**唯一**生产者是 `runBlocking()`。
4. F4 的 txt 正文与 `renderText():960-988` 逐行吻合：`snsId=`（`:962`）/ `作者=`（`:965-972`）/ `时间=`（`:974`）/ `类型=15 来源=2 localFlag=2`（`:975-977`）/ `---- 正文 ----` / `---- 媒体 ----` + URL 列表（`:978-986`）；F2 文件名与 `textFileName():172`（`"$snsId.txt"`，`TEXT_EXT=".txt"` 见 `MomentsLogic.kt:33`）、`fileNameFor():893-897`（`vid_<snsId>_0.mp4`，`ext=mp4 ⇒ prefix=vid`）逐字吻合；目录结构与 `exportDir():163-170`（`<root>/昵称(wxid)/yyyy-MM-dd-HHmm`）、`Keys.kt:387-388 DEFAULT_EXPORT_PATH = "/storage/emulated/0/Documents/WXGroup/moments"` 吻合 ⇒ 这几个文件是本模块格式，不是随手放进去的。
5. **「有没有可能文件是别的路径写的」**：模块内无（见 3）；模块外无法由源码 100% 排除（手工放置、恢复备份、另一模块），但两点反证：① 微X 用 `text.txt` / `img_N.jpg` / `video.mp4` 且在**另一套目录** `/sdcard/Android/data/com.tencent.mm/files/WechatXposed/moments/`（`.workbuddy/memory/2026-11-08.md:96-97`、`docs/_r44_chain.md:481-489`），与 F2 命名/路径均不符；② `SnsVideoDecProbe.dec()` 只写 `原路径.dec`（`:160-161`），不写 `vid_*.mp4`。严谨结论应写「在本模块源码内等价；若引入外部写入者则不等价，现有证据无法排除外部写入」，不能把「格式吻合」当成「绝对排他」。

### 问题5 · P2 生产解密

[FAIL] 问题5 — 证据: `MomentsExport.kt:1417-1472,1456-1466,1485-1491,1517-1519,1537`；`MomentsLogic.kt:790-793,809-814,832-857,859-871`；`SnsVideoDecProbe.kt:338-456,390-398,419-449,477-480`；`Keys.kt:284-295,511-516`；`docs/conclusions/moments_cdn.md:389-436,454-481`；`docs/_r44_chain.md:799-850,907-950` — 说明：

**判定：两个明文 `ftyp` 视频不能证明生产链路调用了原生 `transFor` 解密。明文头是「解密成功」的必要结果，但不是充分证据；源码里至少 3 条不调用 `transFor` 也能合法产出明文 ftyp 的路径。**

生产 gate 的真实形状（先读码得出）：
- `copyVideosForRow():1518-1519`：`val decryptOn = Kv.getBool(Keys.MOMENTS_VIDEO_DECRYPT)`；`val encKey = if (decryptOn) MomentsLogic.snsVideoEncKey(row.content) else 0L`。
- `downloadVideo():1456-1466`：`if (MomentsLogic.shouldDecryptVideo(encKey)) SnsVideoDecProbe.decryptInPlace(tmp, encKey, encLen)`；`shouldDecryptVideo():871 = encKey > 0L`。`X-encflag` 只进日志（`:1459`），**不参与判据**（`:1445-1452`）。
- `snsVideoEncKey():847-857` 从 `content` 的 `f8→f5→f25` 取 key，`f25==0` 或解析失败 ⇒ `0L`；`SnsVideoDecProbe.decryptInPlace():419-449` 才真正 `open → transFor → free`。
- ⚠️ 注释与代码冲突：`Keys.kt:290-293` 与 `:512` 仍写「enc key 提取未落地、key 恒 0、恒走跳过分支」，但 `MomentsLogic.snsVideoEncKey()` 已实现且 `:1519` 已调用（`_r44_chain.md:722-785` 的 key 来源定案、`MomentsLogic.kt:847` 实现可证）。这是 R45 早期遗留的**过期注释**，以代码为准；不能据它推断「生产恒跳过解密」。

不能唯一归因的替代路径（逐条有源码落点）：
1. **CDN 本身就是明文 / `f25==0`**：`shouldDecryptVideo(0)=false` ⇒ `:1462-1465` 打 `vid decrypt skip (encKey=0 encflag=...)`，直接 `:1467 tmp.renameTo(dst)`，落盘即明文 ftyp。`MomentsLogic.kt:832-837` 明写「`f25==0` = 服务端声明未加密」；`_r44_chain.md:814-819` 全库 `type=15` 共 24525 条，`key==0` **2148 条（8.76%）**；`moments_cdn.md:389-401,414-426` 的双向实证（`f25=0 → X-encflag=0 → 落盘 ftyp 明文`）。⇒ 明文视频有近 9% 的合法先验概率，**不需要任何解密**。
2. **`decryptInPlace` 的幂等短路**：即便 `encKey>0` 且进了 `shouldDecryptVideo` 分支，若 tmp 头已是明文，`SnsVideoDecProbe.kt:390-398` 会 `why=ftyp-already-plain` 直接 `return true`，**根本不调 `open/transFor/free`**（`:419-428` 在其后）。此时落盘明文与「原生解密」无关。
3. **模块外写入**：本模块 `copyVideosForRow` 的唯一写盘点是 `downloadVideo()` 的 CDN 下载 + `renameTo`（`:1537,1420-1436,1467`），**代码里没有「从微信 `sight_*` 明文缓存复制」的分支**（`_r44_chain.md:448-454` 只是「未验证的候选路线」，未落地）⇒ 这条在**本模块内不成立**；但模块外（手工放置、其它模块）无法由源码排除，且 F2 两个 mp4 的 mtime（17:10 / 17:47）与 txt（15:40）不同，说明视频是**后补**的，恰好落在问题2 的 `backfillVideos` 形态上。

F4 的 txt 信息不够用：`类型=15 来源=2 localFlag=2` 只说明 `isSnsVideo(type)=true`（`MomentsLogic.kt:790-793`）、会被 `copyVideosForRow` 当视频处理（`vidNotVideo=4` 也说明 F5 那轮扫到 4 条非视频）；两条 `snsvideodownload` URL 只说明 `snsVideoUrls()` 能抽到 `/20202/` 主源（`:809-814`）。**txt 里既没有 `f25`/key，也没有 `X-encflag`、`VIDDEC` 日志** ⇒ 无法判断这两个视频当时 `encKey` 是否 `>0`、是否进了 `shouldDecryptVideo`、是否 `why=decrypted`。`ftyp` 头只证明「落盘文件是可解码的 MP4」，不证明「它曾被解密」。

**要证明「生产链路调用了原生解密」的最小判据**：`MOMENT vid decrypt key=<非0> ok=true encflag=1 enclen=131072`（`:1458-1461`）或 `VIDDEC inplace ... why=decrypted`（`SnsVideoDecProbe.kt:445-448`），且 `kvget moments.video_decrypt=true`、该两行 `content` 的 `f25>0`。这几项本轮**全缺**（F7 logcat 被覆盖）。

### 问题6 · 反向对照的必要性

[UNCERTAIN] 问题6 — 证据: `docs/_r44_chain.md:907-950,968-975`；`docs/conclusions/moments_cdn.md:454-481`；`MomentsExport.kt:1380,1494-1502,1921-1947`；F3/F5/F7 — 说明：

**判定：R45 那次反向对照对「解密层因果性」已足够、无需重做；但它不足以把 P2 标成「已闭环」，且补缺口的方式不是「再做一次通用反向对照」。**

1. R45 反向对照的实际内容（`_r44_chain.md:931-948`）：`kvset moments.video_decrypt false` → 同一条 `op=moments sub=viddl` 生产同链路 → 日志 `vid decrypt skip (encKey=0 encflag=1)` → 落盘头 `83 2f bc fc 7b d2 85 8b`（密文，与 F3 一致）；正向 `VIDDEC inplace ... why=decrypted` + `MOMENT vid decrypt key=3805912539 ok=true` → `00 00 00 1c 66 74 79 70` 且 box 结构自洽。`moments_cdn.md:471-478` 同表。自变量唯一（关开关只停解密，下载/落盘/outcome/计数不变）。
2. 它其实**已经覆盖了 `downloadVideo` 生产函数**：`viddl` 走 `copyVideosForRow()`（`:1939`），后者调 `downloadVideo()`（`:1537`），而 `downloadVideo` 全仓**只有一处**（`:1380`），四条路径共用（问题2 已列）⇒ 从源码角度「`downloadVideo` 里的解密分支有效」在 R45 已被正向 + 反向双向钉死，**再关一次开关重下一条陌生视频只是重复验证，不增加信息**。
3. 但 P2 题目所指「生产链路」应理解为**自动导出链路**（主循环 `:867` / 自动回填 `:892-895`），而非 `viddl` 手动探针（`gate=bypass`，`:1922`）。R45 那次是手动探针；本轮两个明文视频（17:10 / 17:47、冷启后、无探针记录）本可补上「自动链路端到端」这一环，可惜：① 触发源日志缺失（F7），无法排除它们来自 `vidfill` / `viddl`；② 这两个视频自身的 `f25>0`、`kvget moments.video_decrypt` 当时值、`why=decrypted` 日志全缺（问题5），无法排除它们是 `f25==0` 的合法明文。
4. 因此「再做一次通用反向对照（关开关 → 密文）」**补不上**这个缺口——它证明的是「开关仍灵」，不是「这两个视频是解密产物」。若要做对照，必须**绑定到这两个 snsId**：删掉目标 mp4 → `kvset moments.video_decrypt false` → `op=moments sub=viddl --el id <该 snsId>` 重下 → 头应变密文 → 恢复 `true` 再下 → 头 `ftyp`。更省事的是直接取这两个视频的定向日志（见 Q7 补充证据）。
5. 措辞拆分：R45 反向对照 = 「解密能力与自变量唯一」**已闭环**；P2「自动导出生产链路端到端解密」= **有强证据（两个明文 MP4 + `vidCopied=2` + 源码唯一写盘点）但未拿到定向日志，未闭环**。

### 问题7 · 回滚 / 结论边界

[UNCERTAIN] 问题7 — 证据: `MomentsExport.kt:535,609,646-657,892-895,1523,1569-1570,1744,1792,898-900,1830-1831`；`Keys.kt:321,341,491,493`；`MomentsLogic.kt:1120-1121`；F1/F5/F6/F7 — 说明：

**判定：P1、P2 都只能写「有强证据但未拿到日志」，不能写「已闭环」。** 断点在「15:40 那轮的原始日志」与「两个视频的定向解密日志」，两者都被 logcat 环形缓冲覆盖（F7），且 16:28 冷启把进程内累计计数器清零（问题1）。

- P1 边界：`written>0` 判据在源码内与「新 txt 从无到有」等价（问题4），F2/F4 提供了 txt/img 的真实落盘实证，F1 提供「清理后只有 3 目录/12 文件」的基线 ⇒ 「确实生成了新文件」这一半**成立**；但 R25 §2.4 要的另一半「`written>0` 且由自动链路触发」没有日志/计数器留痕（问题3）⇒ **未闭环**。
- P2 边界：源码里解密 gate 与 `downloadVideo` 唯一写盘点都清楚（问题5）、R45 有因果性对照（问题6），本轮有两个明文 MP4 与 `vidCopied=2` 的强关联；但「这两个视频经 `transFor` 而来」与「由自动链路（而非探针/回填）产出」均无定向证据 ⇒ **未闭环**。

## 三档边界自检（逐档）

**[PASS] 进入** — 证据: F1/F2/F4；`MomentsLogic.kt:163-172,893-897,960-988`；`Keys.kt:387-388` — 说明：路径成立。`moments.export_list` 由 3 项变 4 项（新增 `wxid_xxg6w848hmz622`，`kvget`），其 13:42 发布的动态 `snsId=-3396603525724032237` 在 15:40:46 生成 `-3396603525724032237.txt`(1040B) 与 `img_-3396603525724032237_0.jpg`(17378B)；F1 的 04:27 清理基线（3 目录/12 文件/12MB）与 F2 的 4 目录/15 文件/25MB 对得上「第 4 个目录 + 3 个新文件」。文件名/正文格式与 `renderText`/`textFileName`/`fileNameFor`/`exportDir` 逐字吻合。⚠️ 但「自动触发」未证（问题3），故只判「路径成立、触发源未证」。

**[PASS] 返回** — 证据: `MomentsExport.kt:321-385,539,898-900,1640,1744,1747,1792,1830-1831,1523,1569-1570`；`MomentsLogic.kt:1120-1121`；F2/F5/F6；`git show --stat 4ac6aae / 3534e13` — 说明：16:28 装包 + 双冷启后，17:10:52 / 17:47:23 又落两个 mp4。
1. **计数器被重置**：`runs/written/skipped/vidCopied/imgCopied/vidAlready` 全是 object 成员 `AtomicLong`（`:321-385`），进程重建即归零 ⇒ F5 的 `runs=15 written=0` 只描述冷启后，与冷启前 15:40 无冲突（问题1）。
2. **游标续得上**：`MOMENTS_LAST_EXPORT_AT`（`:898`，`Keys.kt:321`）与 `MOMENTS_VIDEO_FILL_CURSOR`（`:1744,1792`，`Keys.kt:341`）都是 **Kv 持久化**，不随进程重建丢失；`exportDue()`（`MomentsLogic.kt:1120-1121`）读持久化 `lastRunAt` 决定节拍，`backfillVideos()` 从持久化游标 `cur.seek(...)`（`:1640,1747`）接着扫 ⇒ 冷启后不会从头重复、也不会丢进度。`stat()` 会打 `fillCursor` / `vidFillCursor`（`:1830-1831`），但 F5 未给这两个字段 ⇒ **「续得上」是源码保证，未取得设备侧实测值**。
3. **没有重复下载**：`copyVideosForRow():1523` `MomentsLogic.imgDone(dstExists, length)` 短路 ⇒ 目标已存在且非空即 `VidOutcome.ALREADY`（`:1569-1570 vidAlready++`），不重下。F5 `vidAlready=0 / vidCopied=2` 与「两个新文件各下一次、无重复」自洽。
4. **代码版本一致**：`git show --stat` 得 `4ac6aae`(04:57) 已含 `MomentsExport.kt +512 / SnsVideoDecProbe.kt +493 / MomentsLogic.kt +169 / Keys.kt +45`，`3534e13`(16:45) 只改 `BatchPicker.kt` 与文档、未碰导出/解密链路 ⇒ 16:28 装的 APK 虽早于 `3534e13`，本报告所读导出/解密源码与设备运行版本一致。

**[FAIL] 复位 — 未覆盖** — 证据: `MomentsExport.kt:535,609,646-657`（仅源码保证）；F5（`enabled=true list=4`，无任何关开关/清名单后的观测） — 说明：**现有证据完全没有覆盖这一档**。F5 只有 `enabled=true`、`list=4` 与冷启后累计计数，没有「关 `moments.auto_export` / 清空 `moments.export_list` 后等下一轮」的任何设备侧观测。源码上确有闸门：`onTick():535 if (!c.autoExport) return`、`pokeExport():609 if (!c.autoExport) return false`、`autoBlockedByEmptyList():646-657`（空名单挡自动路径且只 warn 一次），但**代码有闸门 ≠ 真机验过闸门**。按纪律明确写「未覆盖」，不拿 F5 的 `enabled=true`、不拿「清理后目录数稳定」、不拿问题1 的计数器语义凑数。

## 总体结论

**P1 = 有强证据但未拿到日志，未闭环。** 「好友新发一条 → 导出目录真的生成该 snsId 的新 txt/img」这一半已由 F1/F2/F4 + `renderText`/`textFileName`/`fileNameFor` 格式核对成立；但 R25 §2.4 判据要的 `written>0` 与「`poke reason=bg-sync` 自动链路」在 15:40 那轮没有原始日志（F7），进程内计数器又被 16:28 冷启清零（问题1），定时 tick / bg-sync poke / 手动 `sub=export` 三条触发路径不可区分（问题3）。

**P2 = 有强证据但未拿到日志，未闭环。** 源码 gate（`encKey>0` → `decryptInPlace` → `transFor`）、`downloadVideo` 唯一写盘点、R45 正向 + 反向对照都已核实；本轮两个 `ftyp` 明文 MP4 与 `vidCopied=2` 是强关联证据。但明文 ftyp 不是「调用过原生解密」的充分条件（`f25==0` 合法明文占 8.76%、`isFtyp` 幂等短路、模块外写入均未排除，问题5），且这两个视频的 `f25>0` / `why=decrypted` / 开关当时值 / 自动链路归属全部无定向证据（问题6）；R45 的 `viddl` 对照不能替代自动链路端到端，也无需重做通用反向对照。

## 最薄弱环节

**「15:40 那轮的 `export done written=1` 与 `poke reason=bg-sync` 原始日志被 logcat 环形缓冲（`K7 perf` 刷屏）覆盖，且 16:28 装包 + 双冷启把 `MomentsExport` 全部 `AtomicLong` 累计计数器清零」** —— 落盘事实（文件在）与判据（`written>0` + 触发源）之间断了一环，整条 P1 以及 P2 的「自动链路归属」只剩「文件出现在名单内新好友目录 + 时间戳先后」的**相关性**，没有**因果性**。次级薄弱：两个明文视频没有 `f25` / `X-encflag` / `VIDDEC ... why=decrypted` 定向日志，无法把「明文」唯一归因到 `transFor`。第三档「复位」整档未覆盖，是边界上最大的空白。

## 建议补充证据（最小集，均为具体命令 / 判据）

P1（补触发源 + 补复位档）：
1. 在**下一次装包/冷启之前**先落 logcat（避免再被覆盖）：`adb logcat -G 16M` 后 `adb logcat -v time MOMENT:V VIDDEC:V > _kb/r47_moment.log`；让名单内好友再发一条新动态，抓 `MOMENT poke reason=bg-sync` + `MOMENT export done rows=... written=1 ...` 两行，判据 = 两行齐备、`written>=1`、且时间戳早于新 txt 的 `adb shell ls -l --full-time`。
2. `python tools/dev.py q MOMENT`（或 `adb logcat -d | grep -E "export done|poke reason"`）取当前轮；随即 `op=moments sub=estat` 核对 `runs` 增 1、`written` 同步 +1（期间**不得再冷启**，否则计数器又归零）。
3. `kvget moments.export_list` / `kvget moments.auto_export` / `kvget moments.copy_videos` 记录当时配置真值，与 `op=moments sub=stat` 的 `refresh/load/interval` 一起留档。
4. **复位档（当前未覆盖，必补）**：`kvset moments.auto_export false`（或 `kvset moments.export_list []`）→ 让名单外好友发一条 → 静置 ≥2 个导出周期（`TICK_MS=5min` + `EXPORT_MIN_INTERVAL_MS=10min`，即 >20min）→ `adb shell find /storage/emulated/0/Documents/WXGroup/moments -type f -newermt '2026-11-08 19:50:00' | wc -l`，判据 = **0**；再恢复开关，确认下一轮恢复正常。

P2（把两个具体视频绑定到解密）：
1. 取 `f25`：`adb shell su -c "cp /data/user/0/com.tencent.mm/.../SnsMicroMsg.db /sdcard/_kb_sns.db && chmod 666 /sdcard/_kb_sns.db"` → `MSYS_NO_PATHCONV=1 adb pull /sdcard/_kb_sns.db _kb/sns.db` → 本地 `SELECT hex(content) FROM SnsInfo WHERE snsId IN (-3396603525724032237,-3398536920689724692)`，按 `f8→f5→f25` 解析（同 `MomentsLogic.snsVideoEncKey():847-857` 的 varint 遍历；⛔ **禁止 `cast(content as text)`**，BLOB 遇 `0x00` 会截断，见 `moments_cdn.md:438-452`）。判据 = 两行 `f25 > 0`。
2. `kvget moments.video_decrypt` 判据 = `true`；`op=moments sub=estat` 的 `vidCopied` 在同轮 +2。
3. 清 logcat 后触发一次 `op=moments sub=vidfill`（或等自动回填轮），抓 `MOMENT vid decrypt key=<非0> ok=true encflag=1 enclen=131072`（`MomentsExport.kt:1458-1461`）与 `VIDDEC inplace ... why=decrypted`（`SnsVideoDecProbe.kt:445-448`）；判据 = 两行齐备且 `key` 与第 1 步 `f25` 一致。
4. 若日志仍取不到，做**定向**反向对照（不是通用对照）：删这两个 mp4 → `kvset moments.video_decrypt false` → `op=moments sub=viddl --el id -3396603525724032237` → 头应为密文（随机字节）；`kvset ... true` 重下一次 → 头 `00 00 00 1c 66 74 79 70`。判据 = 同一 snsId 仅因开关翻转而「密文 ↔ 明文」。
5. 排除模块外写入：`adb shell ls -l --full-time` 核对两个 mp4 的 mtime 与同轮 `vidCopied` 时间一致；源码侧已确认 `copyVideosForRow` 无缓存复制分支（问题5 第 3 条），无需再验。

## 复核边界声明
本次只读：未改任何源码 / 配置 / 测试，未连接设备、未执行 adb / Gradle / 装包；仅新增本结论文件。设备侧事实（F1–F7）按主会话采集原样引用，只复核了「可与源码互证」与「可本地复算」的部分（epoch 毫秒时间戳换算、git 提交时间与改动范围、文件名与 txt 正文格式、计数器与 `estat`/`stat` 字段一致性）；未核对的部分（导出目录文件清单、mp4 头字节、设备读出的 txt 内容、`estat`/`stat` 数值本身）不替其背书。

[DONE]
