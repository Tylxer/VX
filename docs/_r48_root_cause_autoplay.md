# R48 独立根因分析：滚动朋友圈时视频自动播放（差分 R42 -> R45）

> 只读分析；不改任何源码/配置/设备；不执行 adb / 真机 / Gradle / 装包。
> 硬前提：该现象 R45 之前没有、R45 之后才有（用户报告，并明确归因「刚刚完成 R45 朋友圈自动导出视频」）。
> 目标：找出 R45 引入的哪一条运行时副作用，使「滚动朋友圈」与「视频起播」之间产生因果链。
> 纪律：相关性不等于因果性；证据不足即写「未定案 / UNCERTAIN」并给可证伪判据；不泛泛讨论微信原生自动播放机制。

## 范围
- 工作区：C:/Users/lx/WorkBuddy/2026-09-29-20-23-21
- 项目：wxgroup/（Kotlin / Android LSPosed 模块）
- 宿主：微信 8.0.48 / 2588 GP；设备 1440x3200
- 差分基线：5358408（R42） -> 4ac6aae（R46 首次提交，一次性带入 R43/R44/R45 全部源码）
- 交付物：仅本文件

## 方法
1. `git diff 5358408 4ac6aae -- <文件>` 取增量；`git show --stat 4ac6aae` 定位改动面。
2. 枚举增量中全部「运行时副作用」：新 hook / 新定时器 / 新广播分支 / 新默认值 / 新 IO / 新网络请求 / 新 DB 读写；逐条区分 R44 引入与 R45 引入。
3. 对每条副作用问：能否影响朋友圈时间线的滚动 / 列表重绑 / 视频 view 状态。
4. 候选链 A（后台同步->列表刷新/自动滚动->起播）、B（下载预热 CDN / 共享缓存）、C（onBind 同步 IO / 重绑）、D（微信原生机制被解锁）逐条做「支持证据 / 反证 / 缺什么证据」。
5. 三档红线自检：进入 / 返回 / 复位（含「关掉自动导出后现象是否应立刻消失」）。
6. 反向对照设计：自变量唯一，只设计不执行。
7. 每条结论标 `[定案|假设|排除|UNCERTAIN] 问题N — 证据: 文件:行号 / git diff / 可复算命令 — 说明`。

## 已知事实清单（前置只读核实，本轮不重复调研，可复核）
1. 基线 5358408(R42) -> 4ac6aae(R46 首提交，带入 R43/R44/R45)。R45 增量用 `git diff 5358408 4ac6aae -- <文件>`。
2. R45 改动面（git show --stat 4ac6aae）：SnsVideoDecProbe.kt 新增 493 行；MomentsExport.kt +512；MomentsLogic.kt +169；Keys.kt +45；EntryReceiver.kt +17；另有 MomentsVideoGateTest.kt 与多份 docs。
3. SnsVideoDecProbe.decryptInPlace(f,key,enclen)：只原地改写调用方传入 f 的前 131072 字节（RandomAccessFile(f,"rw") + fd.sync()），反射宿主 com.tencent.mm.plugin.imgenc.MMIMAGEENCJNI 的 open(J)/transFor/free；dec 探针另写「原路径.dec」。无任何播放 API 调用。
4. MomentsExport.downloadVideo(url,dst,encKey)：HttpURLConnection + UA `WeChat/arm64` + Referer 直下 `.../snsvideodownload?...` CDN，落 `dst.path + ".tmp"`，读 X-encflag / X-enclen，MomentsLogic.shouldDecryptVideo(encKey) 为真则解密 tmp，再 tmp.renameTo(dst)。dst 在 `/sdcard/Documents/WXGroup/moments/<昵称(wxid)>/<yyyy-MM-dd-HHmm>/vid_<snsId>_0.mp4`（模块自有导出目录，非微信缓存）。copyVideosForRow / backfillVideos / vidfill / viddl 四路径共用 countVid()。
5. 模块全树 grep 零命中 autoplay|startPlay|playVideo|MediaPlayer|VideoView|SurfaceView|TextureView|onScrollStateChanged|onScrolled|addOnScrollListener；无 callMethod(...play/start/prepare/setDataSource)。=> 模块没有直接起播。
6. MomentsAuto.kt（R25 已有，R45 未改）：moments.auto_load 默认 true；在 WxRecyclerAdapter.onBindViewHolder after 里，贴底且 MomentsLogic.loadMoreDue(...) 成立时 triggerLoadMore(rv) -> smoothScrollBy(0, 2400)（兜底 scrollBy），节流 1.5s、每会话上限。定时 tick：autoRefresh || autoLoad 任一开即触发 x2(256) 后台同步 -> p1.f(scene) -> 成功后 pokeExport。stat() 输出 refresh/load/interval/ticks/refreshes/bgSync。
7. Keys.kt：moments.video_decrypt 默认 "true"（R45 新增）；moments.auto_export 默认 false；moments.export_list 是名单（当前 4 人）；moments.auto_refresh 默认 false。
8. SnsGuard（R42 已有，非 R45）：hook wcdb updateWithOnConflict before，对朋友圈删除保位 keep=old|10，往 ContentDesc protobuf field5 写 `[已删除]\n` 前缀（会改写 content BLOB）。
9. 文档：docs/_r44_chain.md（R44/R45 全链路 + R46/R47 复核）、docs/conclusions/moments_cdn.md（视频 CDN / 解密 / f25==0 合法明文占 8.76%）、docs/conclusions/moments.md（时间线 ImproveSnsTimelineUI / ImproveRecyclerView / WxRecyclerAdapter.onBindViewHolder）、docs/_r25_moments_bg.md（后台同步链路）、docs/开发回路速查.md（探针 op 表 / logclear / LogX 5000 行环形覆盖）、docs/_r47_review_autoland.md（上一轮独立复核判据风格）。
10. 用户 20:12「不需要验证」=> 本轮禁止改配置 / 装包 / 任何设备写操作 / Gradle；需要真机的判据只列出不执行。

## 逐条结论（分步追加，每步留 [DONE] 标记）
[定案] 问题1 — 证据: `git diff --name-status 5358408 4ac6aae` / `git show --stat 4ac6aae` / `git diff 5358408 4ac6aae -- <文件>` / `git diff --name-only 5358408 4ac6aae | grep -i momentsauto` / docs/_r44_chain.md:382-395,996-1000 — R45 相对 R42 的**全部运行时副作用**清单，并逐条标注「能否影响滚动 / 列表重绑 / 视频 view 状态」。

### 1.0 差分粒度边界（先钉死，它是所有结论的置信度上限）
- `5358408`(R42) → `4ac6aae` 之间**无中间提交**（`git log --oneline` 复核：8fb45a0 / 3534e13 / 4ac6aae / 5358408）。`4ac6aae` 是 R46 首次提交，**一次性带入 R43/R44/R45 三批源码**。⇒ `git diff 5358408 4ac6aae` 得到的是「R42→R46」全量增量，**不等于纯 R45**。纯 R45 只能靠源码注释里的 `R43`/`R45` 标记 + docs/_r44_chain.md 的时间线归属，**无法用 git 精确切分**。用户硬前提里的「R45」在交付语境上等于「这一批视频自动导出功能整体」。
- 改动面（name-status）：源码 8 改 1 新增 —— `data/Keys.kt`、`entry/EntryReceiver.kt`、`feature/ForwardPicker.kt`、`feature/MomentsExport.kt`、`feature/MomentsLogic.kt`、`feature/PickerPage.kt`、`feature/PickerTabLogic.kt`、`feature/SnsVideoDecProbe.kt`(A) + 3 个 test；另有 R44 两个探针移入 `docs/_archive/r44_probes/`（**移出编译单元**）。
- ⛔ **R45 未新增任何 Xposed hook / 定时器 / 滚动监听器**：改动面无 `HookEntry.kt`、无 hook 注册文件；`MomentsAuto.kt`（onBind / onTick / ensureTick / smoothScrollBy 的宿主）**不在改动面**（`git diff --name-only 5358408 4ac6aae | grep -i momentsauto` 输出为空）；`SnsGuard.kt` 亦不在改动面；全树零命中 `onScrollStateChanged|onScrolled|addOnScrollListener`（前置事实 5）。

### 1.1 逐条副作用清单（版本归属 / 落点 / 能否影响滚动·重绑·视频 view）

| # | 版本 | 运行时副作用 | 落点（文件:行） | 能否影响滚动/重绑/视频 view |
|---|---|---|---|---|
| ① | R43 | 新增**网络请求**：`HttpURLConnection` 直下 `…/102/20202/snsvideodownload?…`，UA `WeChat/arm64`（非 const `val`）、`Referer` 恒空 | `MomentsExport.kt` diff 增 `import java.net.*`；`downloadVideo` 新增块（当前 :1380-1470） | **否**（导出轮 daemon 线程；不走微信 `SnsCdnDownloadBase`/`c3` 通道） |
| ② | R43 | 新增**文件 IO**：写 `dst.path+".tmp"` → `renameTo(dst)`，`dst=/sdcard/Documents/WXGroup/moments/<昵称(wxid)>/<yyyy-MM-dd-HHmm>/vid_<snsId>_0.mp4`；单文件 ≤200MB | `downloadVideo` :1380-1470；`copyVideosForRow` :1490-1530 | **否**（模块自有导出目录，见 Q3） |
| ③ | R43 | 新**默认值** `moments.copy_videos = "false"` | `Keys.kt:507-510`（Prefs.ALL） | **否**（只闸导出轮） |
| ④ | R43 | 新增**DB 读**：`SnsGuard.listCurrentSns(ct,sid,PAGE)` 键集分页（`findSnsById` / `backfillVideos`）+ 对 `row.content` BLOB 的**只读** protobuf 扫描 | `MomentsExport.kt:966-990,1754`；`MomentsLogic.snsVideoUrls/snsVideoEncKey` | **否**（后台线程，非 onBind 路径） |
| ⑤ | R43 | 新增 **Kv 写**：`moments.video_fill_cursor`（回填游标，刻意不进 Prefs.ALL） | `Keys.kt` diff 新增常量；`backfillVideos` 末尾 `Kv.putString` | **否** |
| ⑥ | R43 | 新增**广播分支数据**：`moments` 分支加 `id=getLongExtra("id",0L)` 且 `MomentsExport.probe(sub,n)` → `probe(sub,n,id)`；`probe` 内新增 `vidfill` / `viddl` 两个 sub | `EntryReceiver.kt:456-467`；`MomentsExport.kt:1838-1940` | **否**（**被动调试入口**，仅外部广播触发） |
| ⑦ | R43 | 新增 11 个 `AtomicLong` 视频计数 + `stat()` 字段 | `MomentsExport.kt:360-390,1813-1830` | **否**（纯内存计数/日志） |
| ⑧ | R43 | 改常量：`DL_READ_MS` 30s→**120s**、`MAX_VIDEO_BYTES=200MB`、移除 `DL_UA`、新增 `VID_UA`/`VID_REFERER` | `MomentsExport.kt` diff :225-250 | **否**（仅拉长导出轮耗时） |
| ⑨ | **R44** | Route C 探针 `SnsDispatchProbe.kt`(685 行，主动让宿主 `l0#d` 下载) / `VidTrigProbe.kt`(327 行) | `docs/_archive/r44_probes/` | **无运行时副作用**：R44 终局判定「Route C 不需要」（docs/_r44_chain.md:387-395，真机 `l0.l` 全程 size=0、不落盘），R46 收口**移出编译单元**（:996-1000）。⇒ **R44 是取证/推导轮，不产出运行时副作用**。这是「区分 R44 与 R45」的核心结论 |
| ⑩ | **R45** | 新**默认值** `moments.video_decrypt = "true"`（Prefs.ALL 中唯一「默认开」的新开关，装上即生效、无需用户配置） | `Keys.kt:513-516` | **否**（只决定导出副本是否解密） |
| ⑪ | **R45** | 新增 `SnsVideoDecProbe.kt` **493 行**：`decryptInPlace(f,key,enclen)` 用 `RandomAccessFile(f,"r")` 读前 131072 字节 → 反射宿主 `com.tencent.mm.plugin.imgenc.MMIMAGEENCJNI` 的 `open(J)` / `transFor(J[BJJ)` / `free(J)` → `RandomAccessFile(f,"rw")` 写回同区段 + `fd.sync()`；`dec`/`try` 探针另写「原路径.dec」 | `SnsVideoDecProbe.kt:338-456`（尤其 :411-419, :433-439）；调用点 `MomentsExport.kt:1452-1457` | **否**（原地改写的是**调用方传入的导出目录 `.tmp`**；发生在导出轮 daemon 线程）。`open(J)` 实参就是 `key`（:419 `openHandle(instance, openM, key)`）⇒ per-key native 句柄、`free` 配对，非文件路径/非全局句柄；**native 内部是否含全局态静态不可判，标 UNCERTAIN** |
| ⑫ | **R45** | 新增**纯函数/常量**：`protoVarint`、`snsVideoEncKey`(content f8→f5→f25)、`shouldDecryptVideo`、`SNS_TYPE_VIDEO=15`、`isSnsVideo`、`videoUrlCandidates`/`snsVideoUrls`、`PROTO_FIELD_MEDIA_ENC_KEY=25`、`VIDEO_MARKS` 加 `snsvideodownload` | `MomentsLogic.kt` diff 全量（+169 行） | **否**（零副作用，`shouldDecryptVideo` 自注「纯函数，零副作用」） |
| ⑬ | **R45** | 新增**广播分支** `op=viddec`（`EntryReceiver.kt:671-684`）→ `SnsVideoDecProbe.run(api|dec|key|try)`，在 `io{}` 后台协程里跑；`dec`/`try` 会调宿主 native，`key` 只读扫 `sight_*`/`snst_*`/`snsu_*` 文件名提 `&enc<数字>` | `EntryReceiver.kt:671-684`；`SnsVideoDecProbe.kt:54-62,66-90,101-167,171-180` | **否**（被动调试，只有外部发广播才跑） |
| ⑭ | **R45** | 新增**响应头读取** `X-encflag` / `X-enclen`（`readHeaderInt`/`readHeaderLong`），且 `encFlag` **只作日志观测、不参与解密决策** | `MomentsExport.kt` diff 内 `readHeaderInt/Long`；判据注释 :1445-1451 | **否** |
| ⑮ | **R45** | `downloadVideo` 内解密调用点；`copyVideosForRow` 内 `Kv.getBool(Keys.MOMENTS_VIDEO_DECRYPT)` 决定 `encKey` 是否置 0（关开关 = 传 0 = 跳过解密，下载/rename/计数全不变） | `MomentsExport.kt:1452-1457`；`copyVideosForRow` :1500-1510 | **否** |

### 1.2 对「滚动 / 列表重绑 / 视频 view 状态」的总判定
- **[定案] 没有任何一条 R45 副作用挂在 `WxRecyclerAdapter.onBindViewHolder`、RecyclerView 滚动回调或视频 view 生命周期上**。`onBind` 路径（`MomentsAuto.kt:446-499`）与 `SnsGuard` 的 `updateWithOnConflict` before 钩子（`SnsGuard.kt:158-165`）都是 R25/R42 已有且 R45 未改。
- 唯一「间接」可能：⑪ 在**宿主进程内**首次引入对宿主 native `MMIMAGEENCJNI` 的反射调用，①⑧ 引入后台网络/磁盘负载。若它们与主线程争抢 IO/CPU，理论上能改变滚动观感 —— **[假设]**，静态零证据，不作定案。
- ⚠️ **文档与代码冲突（独立复核须纠正）**：`Keys.kt:290-292` 与 `Keys.kt:512` 的注释称「enc key 提取当前未落地 ⇒ key 恒 0、恒走跳过分支」，但 `MomentsLogic.snsVideoEncKey(blob)` 已在本批 diff 中从 content protobuf f8→f5→f25 取 key，`copyVideosForRow` 里 `encKey = if (decryptOn) snsVideoEncKey(row.content) else 0L`，且 docs/_r44_chain.md:915-948 真机日志 `key=3805912539 … why=decrypted` / 反向对照 `encKey=0 … skip` 证明解密**确实在跑**。⇒ **注释是过时留痕，以代码与真机日志为准**，⑪ 在设备上真会执行。不能采信注释去判「R45 没解密」。
- ⚠️ **配置态盲区（静态 diff 看不见）**：`copy_videos` 默认 false、`auto_export` 默认 false（`Keys.kt:491,507-510`）。若设备保持默认，①②④⑤⑧⑪⑮ **全部不会自动发生**（只有手动 `viddl`/`vidfill`/点「导出」才跑）。docs/_r44_chain.md:461 记「`moments.copy_videos` 已置 true」、R47 节记 `export_list` 4 项、`written>0`，说明设备大概率已改配置；但**当前 Kv 态不在源码 diff 内**，是 Q6 最大缺口。

[排除] 问题2 — 证据: `MomentsAuto.kt:194-219,221-234,267-305,332-349,446-499,511-527` / `MomentsExport.kt:540-544,564-620,646-657` / `core/MomentsSyncTrigger.kt:88` / `Keys.kt:485-493` / 可复算: `git diff --name-only 5358408 4ac6aae | grep -i momentsauto`（空）与 `git diff 5358408 4ac6aae -- wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsExport.kt | grep -nE '^[+-].*(onTick|runAsync|runBlocking|exportDue|pokeExport|ensureTick)'` — 「`moments.auto_export` 是否让 `x2(256)` / `pokeExport` / 自动滚动的**频率或触发条件**变化」定案为**否**；候选链 A 的「频率/触发条件变化」分支被排除。

逐跳调用链：
1. `MomentsAuto.install()`(:194-219) → `ensureTick(FIRST_TICK_MS=20s)`(:212) → ticker 每 `TICK_MS=60s` 一拍(:82,:267-280)。**R25 已有，R45 未改**。
2. `onTick()`(:282-305)：`val c=cfg()` → `if (!c.autoRefresh && !c.autoLoad) return`(:295) → `MomentsLogic.nextRefreshAt(...)`(:302) → `doRefresh(now)`(:304)。`cfg()`(:231-234, 221-229) 只读 `MOMENTS_AUTO_REFRESH` / `MOMENTS_AUTO_LOAD` / `MOMENTS_REFRESH_INTERVAL_H`，**不读 `moments.auto_export`**。
3. `doRefresh()`(:332-349)：`MomentsSyncTrigger.syncNow(force)` → `x2(256)`（`core/MomentsSyncTrigger.kt:88 SCENE_SYNC=256`）→ 成功后**无条件** `runCatching { MomentsExport.pokeExport() }`(:343)。⇒ `pokeExport()` 的**调用**与 `auto_export` 无关。
4. `pokeExport()`(`MomentsExport.kt:607-620`)：`val c=cfg(); if (!c.autoExport) return false`(:608-609) → 空名单安全阀(:610,646-657) → `POKE_MIN_INTERVAL_MS` 节流(:611-614) → `runAsync()`(:619)。⇒ `auto_export` **只决定导出轮是否真起**，不决定 1~3 的投递/调用。
5. 自动滚动：`onBind()`(:446-499) `if (!cfg().autoLoad) return`(:461) → `loadMoreDue`(:489) → `triggerLoadMore(rv)`(:495) → `smoothScrollBy(0,2400)`(:511-527)。条件**只有 `autoLoad`**。
6. 默认值(`Keys.kt:485-493`)：`auto_refresh="false"` / `auto_load="true"` / `auto_export="false"` / `export_list="[]"`。`auto_load` 默认 **true** ⇒ `onTick` 自 R25 起一直在跑，R45 **没有任何新增开关能改变它**。

自变量唯一性复核：
- `MomentsAuto.kt` 全文件**不在改动面**（name-only grep 为空）⇒ 上面 1/2/3/5 的源码逐字未变。
- `git diff ... -- MomentsExport.kt | grep` 只命中 2 行 `export installed` 日志文本（新增 `videos=${config.copyVideos}`）+ 2 行 `backfillVideos` 注释 ⇒ **导出轮的 onTick 闸门 / `exportDue` / `runAsync` 单航班 / `pokeExport` 节流 / `ensureTick` 全部未改**。
- 「dex 里有字符串 ≠ 该业务调用它」：`moments.auto_export` 的消费点只在 `MomentsExport.cfg()` 与 `pokeExport()`；`MomentsAuto.kt` 全文不出现该 key。不能因配置表里登记了这一项就推定它参与同步/滚动判定。

**如实保留的间接变化（不是频率、不是触发条件）**：`pokeExport` 触发的那一轮导出，R45 后在 `copy_videos=true` 时多了 `copyVideosForRow`（主循环 `MomentsExport.kt:863`）与 `backfillVideos`（:888-891,1711-1800），单轮耗时变长（同步 HTTP、readTimeout 120s、≤200MB、128KB 解密 + `fd.sync()`、`listCurrentSns` 分页扫库）。⇒ 变的是**单轮负载**，`x2(256)` / `pokeExport` 调用 / `smoothScrollBy` 的**频率与触发条件逐字不变**。`[假设]`：若该后台负载与主线程争 IO/CPU 间接改变滚动观感，静态无证据，不作定案。

[DONE] Q1-Q2
[排除] 问题3 — 证据: `MomentsExport.kt:1029-1116`（`buildSnsIndex`）/ `MomentsLogic.kt:394-406,451-452`（`SNS_EXCLUDE_PREFIXES`）/ `MomentsExport.kt:1380-1470`（`downloadVideo`）/ `SnsVideoDecProbe.kt:27,160-161,171-180,338-456` / docs/_r44_chain.md:387-395,418-424,996-1000 — 「模块用 `WeChat/arm64` UA 请求同一 `snsvideodownload` URL，让微信播放器缓存已就绪而立刻起播」**不成立：路径不相交，排除**。

- **模块读微信缓存，但只建「图片」索引、且只读**：`buildSnsIndex()`(:1039-1116) 遍历 `<ext>/cache/<hash32>/sns/<hex1>/<hex2>/`，`files: HashMap<String,File>` 只收 `MomentsLogic.isSnsMediaName` 为真的名字 —— 白名单 `snsb_`/`snsu_`/`snst_`，**明确排除 `snstblur_`/`wxid_`/`sight_`**（`MomentsLogic.kt:406`、:451-452，索引 KDoc :1031-1032 原文「所以后续匹配天然不会命中头像和视频」）。该索引供 `copyImagesForRow` 把微信缓存里的图**拷出**到导出目录（读，不写）。
- **模块写的是自有导出目录**：`dst = File(dir, MomentsLogic.fileNameFor(row.snsId, 0, VIDEO, null)) = vid_<snsId>_0.mp4`，`dir = MomentsLogic.exportDir(root, …)` = `/sdcard/Documents/WXGroup/moments/<昵称(wxid)>/<yyyy-MM-dd-HHmm>/`；tmp = `dst.path + ".tmp"`，解密也只改这个 tmp（`decryptInPlace(tmp, …)`，`MomentsExport.kt:1457`）。`tmp.renameTo(dst)` 只在同一导出目录内改名。
- **微信播放读的是自己的 `sight_*` 明文缓存**：`cache/<hash>/sns/e/c/sight_<mediaId>…&enc<key>_V2`（docs/_r44_chain.md:418-419；`SnsVideoDecProbe.kt:27,175` 的文件名正则 `^(sight|snst|snsu)_(.+?)&enc(\d+)(?:_V2)?$`）。与 `vid_*.mp4` **路径不相交、文件名不相交、inode 不相交**。
- **碰 `sight_*` 的两处都是只读或写到别处**：`SnsVideoDecProbe.key(arg)`(:171-180) 只读扫目录提 `&enc<数字>`（被动 `viddec` 探针）；`dec`/`try`(:101-167) 另写「原路径.dec」到**调用方 arg 指定的路径**，且只在手动广播时跑。`decryptInPlace`(:338-456) 只 `RandomAccessFile(f,"rw")` 改**传入的 f**。
- **不走微信下载器**：`downloadVideo` 是模块自己 `URL(url).openConnection()` 开的 `HttpURLConnection`，UA `WeChat/arm64`、无 Referer，**不经**宿主 `Ljf3/l`(SnsDownloadSight) / `c3`(SnsCdnDownloadBase) / `l0#d`(DownloadManager)。R44 那条「主动调宿主 `l0#d` 让微信下载器把视频写进自己缓存」的 Route C 已被真机证伪并整体作废（docs/_r44_chain.md:387-395：`l0.l` 全程 size=0、不落盘），探针也已移出编译单元（:996-1000）。⇒ **当前实现里不存在「模块触发微信下载器写微信缓存」这条路径**。
- ⇒ 结论：**路径不相交，排除**。可复算：`git show 4ac6aae:wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsExport.kt | grep -nE 'sight_|snst_|renameTo|exportDir|HttpURLConnection|User-Agent'`；`git show 4ac6aae:wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsLogic.kt | grep -n 'SNS_EXCLUDE_PREFIXES'`。
- **[UNCERTAIN，无因果链] 保留项**：同一 `…/snsvideodownload?…` URL 被模块先请求过，CDN **服务端**缓存可能更热，微信自己后续下载可能更快。但微信播放器「滚动即播」的判据是**本地** `sight_*` 缓存是否存在，不是 CDN 响应快慢；且模块不触发微信下载器。故这最多改变「微信自己缓存建立的速度」，不能推出「模块请求 → 播放器缓存就绪 → 起播」。静态无法证伪 CDN 侧预热，**但不构成可定案的因果链**。

[排除] 问题4 — 证据: `MomentsAuto.kt:194-234,400-527` / `SnsGuard.kt:158-165,215-222,298,533,702-713,1099` / `MomentsExport.kt:564-580,966-990,1754,1452-1457` / `MomentsLogic.kt:624-670,777-870` / 可复算: `git diff --name-only 5358408 4ac6aae | grep -E 'MomentsAuto|SnsGuard'`（空） — 「R45 在 `onBind` / `SnsGuard` before 这条高频路径上新增同步 DB 读 / 网络 / 大 IO / `content` BLOB 改写，导致列表重绑或视频 view 重建」**排除**（R45 未动这条路径）。

- `MomentsAuto.onBind()`(:446-499) 逐段：`binds.incrementAndGet()`(:403) → adapter 前缀过滤(:449) → 首次见具体类自愈补挂 `hookAdapterClass`(:456-459) → `if (!cfg().autoLoad) return`(:461) → `holderView`/`rvOf`/`dataList`(:463-473) → `lastSize` grow 日志(:476-482) → `loadMoreDue`(:489) → `triggerLoadMore(rv)`(:495) → `smoothScrollBy(0,2400)`(:511-527)。**无 DB 读、无网络、无 BLOB 写、不识别 item 是不是视频**（全文无 mediaType / type==15 判断）。`cfg()`(:231-234) 有 `CFG_TTL_MS=3s` 缓存，`refresh()`(:221-229) 只读 3 个 Kv key。
- **R45 新增的 Kv key 不进 onBind 热路径**：`moments.copy_videos` / `moments.video_decrypt` / `moments.video_fill_cursor` 均不在 `MomentsAuto.refresh()` 的读取列表（只读 `MOMENTS_AUTO_REFRESH`/`MOMENTS_AUTO_LOAD`/`MOMENTS_REFRESH_INTERVAL_H`）⇒ onBind 每次重绑的同步 IO **数量不变**。
- `SnsGuard` 的 `updateWithOnConflict` before 钩子注册于 `SnsGuard.kt:158-165`；保位 `keep = old|10`(:533) 与往 `content` protobuf field5 写 `[已删除]\n` 前缀(:702-713) 是 **R42 已有**。`SnsGuard.kt` **不在** `git diff --name-only 5358408 4ac6aae` 里 ⇒ R45 未改。而且它只在「删除/更新 SnsInfo」时触发，不是滚动时必然触发。
- **R45 新增的 DB 读在后台线程，不在 onBind**：`findSnsById`(:966-990) / `backfillVideos`(:1754) 用 `SnsGuard.listCurrentSns(ct,sid,PAGE)` 键集分页（:1099，**只读**）；执行体是 `runAsync()` 起的 daemon 线程（`MomentsExport.kt:564-580`，`isDaemon=true, name="moments-export"`）或手动 `vidfill`/`viddl` 广播，**不在 `onBind` 主线程路径**。
- **R45 新增的大 IO 也在后台**：HTTP 下载（1MB~77.8MB）、`RandomAccessFile` 读写前 128KB、`fd.sync()`，全在导出轮 / 被动 `viddec` 探针内。
- **`content` BLOB 改写**：R45 的 `snsVideoEncKey(blob)` / `protoVarint(b,field)`（`MomentsLogic.kt` diff 新增）是对 `ByteArray` 的**只读** varint 扫描，不写回 DB；唯一改写 `content` 的是 R42 的 `SnsGuard` prefix 方案。
- ⇒ **[排除]**「R45 在 onBind 路径新增同步 IO → 列表重绑 / 视频 view 重建」。
- **[假设] 「A 推 B」时序结论（必须标假设）**：若导出轮后台线程恰在用户滚动时并发跑，会争抢磁盘 IO / CPU / native 库，理论上可能让主线程掉帧、`onBind` 被延后，从而改变「视频行进入可视区」的时序，进而影响微信原生起播的命中时刻。**这是纯时序推断，静态无证据、无日志支撑，不作定案**；可证伪判据见末尾「最小补充证据」。

[DONE] Q3-Q4
[排除] 问题5 — 证据: `MomentsExport.kt:1452-1457`（`decryptInPlace(tmp, …)`）/ `SnsVideoDecProbe.kt:338-456` / `MomentsLogic.kt:536-539,835,868` / docs/conclusions/moments_cdn.md:395-402,416-426 / docs/_r44_chain.md:418-424 — 「R45 之前视频是密文/未缓存所以滚动到播不了，R45 之后文件变明文/被预下载，于是滚动到就立刻播」在**当前实现下不成立**。

- **模块解密的是导出目录副本，微信播放读的是自己的缓存**：`decryptInPlace` 被调用时传的是 `tmp = File(dst.path + ".tmp")`（`MomentsExport.kt:1457`），即 `/sdcard/Documents/WXGroup/moments/.../vid_<snsId>_0.mp4.tmp`；`RandomAccessFile(f,"rw")` 只改这个传入的 f 前 131072 字节（`SnsVideoDecProbe.kt:435-439`）。微信播放器读的是 `cache/<hash>/sns/e/c/sight_<mediaId>…&enc<key>_V2`（docs/_r44_chain.md:418-419）。**路径不相交（见 Q3）**，⇒「文件变明文」发生在微信读不到的地方。
- **「R45 之前视频是密文/播不了」这个前提本身不成立**：
  - `f25==0`（服务端声明未加密的合法明文）占 **2148 / 24525 = 8.76%**（`MomentsLogic.kt:536-539`；docs/conclusions/moments_cdn.md:395-402、:416-426 双向实证：`f25==0` 时 `X-encflag=0`、落盘头就是 `ftyp` 明文）。即 R45 之前也有约 8.76% 的视频在 CDN 侧本就是明文。
  - 更关键：这 8.76% 说的是 **CDN 返回形态**，而**微信本地缓存一直是明文**：docs/_r44_chain.md:423「微信缓存的 5 个 `sight_*` **全部是明文**」、:424「CDN 返回的就是密文，**微信在本地解密后才写缓存**」。⇒ 微信播放器能不能播，从来不取决于模块导出的副本是密文还是明文。
- **微信自己的解密与模块 R45 解密是两条互不相干的链**：微信侧解密发生在它自己的 `SnsDownloadSight`/`c3` CDN 通道内（docs/_r44_chain.md:424、:62）；R45 的 `MMIMAGEENCJNI.open(key)/transFor/free` 是模块在导出轮里对**自己下载回来的副本**补做一次解密（Q1 ⑪）。两者不共享文件、不共享句柄、不共享缓存目录。
- **「被预下载」也不成立**：模块下载落到外部导出目录 `vid_<snsId>_0.mp4`，不是微信缓存；微信播放器的预加载/起播判据只看本地 `sight_*` 缓存是否存在。模块的 HTTP 请求既不触发微信下载器（Route C 已作废，Q3），也不把路径回写给微信。
- **要使该链成立必须证明「微信侧读取了 `/sdcard/Documents/WXGroup/moments/.../vid_*.mp4`」——源码零证据**：模块无 `ContentValues` 写回、无 SnsInfo DB 更新、无发给微信的广播、无 hook 改播放数据源/路径、无文件名映射回写（Q1 全表 + Q3 路径核对）。
- ⇒ **[排除]** 当前实现下该链不成立。若用户确实观察到「滚动即播」，微信侧读取的仍是它自己的 `sight_*` 缓存路径，与 R45 的解密/预下载无因果关系。
- 附注：`moments.video_decrypt` 默认 `"true"`（`Keys.kt:513-516`）是唯一「装上即生效、无需用户配置」的新默认值，但 `isFtyp` 幂等短路（`SnsVideoDecProbe.kt:390-398`）与 `shouldDecryptVideo` 纯函数（`MomentsLogic.kt` diff）都只作用于导出副本字节，不改变微信侧任何状态。

[UNCERTAIN] 问题6 — 证据: 综合 Q1（`git diff 5358408 4ac6aae` 全量副作用表）/ Q2（`MomentsAuto.kt` 逐跳调用链）/ Q3（`MomentsExport.kt:1029-1116` 路径核对）/ Q4（`MomentsAuto.kt:446-527` + `SnsGuard.kt` 不在改动面）/ Q5（`MomentsLogic.kt:536-539` + docs/_r44_chain.md:418-424） / `Keys.kt:485-516` / docs/_r44_chain.md:461,1161-1185 — **静态证据不足以定案**；四条候选链中，A 的「频率/触发条件」被排除、B/C/D 全被排除或降为假设。

### 排序后的候选（最多 3 个）

**候选 ①（主候选，置信度：低 / UNCERTAIN）：由配置态激活的「R45 导出轮后台副作用」间接改变了滚动时的时序/负载，使微信原生自动播放被暴露。**
- 支持证据：`copy_videos=true` 后，每一轮导出新增同步 HTTP 下载（readTimeout 30s→**120s**、单文件 ≤200MB、真机实测 1MB / 9.6MB / 17MB / 77.8MB）+ 128KB 解密 + `fd.sync()` + `backfillVideos` 的 `listCurrentSns` 分页扫库（`MomentsExport.kt` 增量 +512 行、`Keys.kt:225-250`、docs/_r44_chain.md:907-948）；`video_decrypt` 默认 true 使 `MMIMAGEENCJNI` 反射调用**首次在宿主进程内**执行。这是 R45 相对 R42 **唯一新增的、能在宿主进程里持续跑的副作用族**。R47 节记 `export_list` 4 项、`written>0`（docs/_r44_chain.md:1161-1185），说明导出轮确实在设备上跑过。
- 反证：全部在 `runAsync()` 起的 daemon 线程（`MomentsExport.kt:564-580`，`isDaemon=true, name="moments-export"`），单航班 CAS；模块无播放 API、无 hook、无滚动监听器（前置事实 5 + Q1）；`MomentsAuto.kt` 未改（Q2）；落盘路径与微信缓存不相交（Q3/Q5）；onBind 热路径同步 IO 未变（Q4）。**源码里找不到「滚动 ↔ 起播」的任何连接点**。
- 缺什么证据：① 设备当前 **Kv 全量快照**（`auto_export` / `copy_videos` / `export_list` / `video_decrypt` / `auto_load` / `auto_refresh` 的实际值）；② 现象时刻与导出轮日志（`MOMENT export` / `VIDDL` / `VIDDEC` / `MOMENT refresh bg-sent`）的**时间相关性**；③ 微信原生自动播放开关状态与 R42 基线同配置对照。

**候选 ②（假设，置信度：低，且与硬前提冲突）：配置变更（启用自动导出时顺带改了 `auto_load` / `auto_refresh` 或微信自身设置）放大了 R25 已有的自动滚动 / 后台同步，把视频行送进可视区触发微信原生起播。**
- 支持证据：`MomentsAuto.onBind` 的 `triggerLoadMore(rv) → smoothScrollBy(0,2400)`（`MomentsAuto.kt:511-527`）是模块侧**唯一能「制造滚动」**的代码；`auto_load` 默认 **true**（`Keys.kt:486`）⇒ `onTick` 自 R25 起一直在跑并 `x2(256)` 同步（:282-305）；`x2(256)` 成功后 `pokeExport`（:343）⇒ 同步成功/列表刷新与「新一批动态上屏」确实会同时发生。
- 反证：这条链 **R25 就有、R45 逐字未改**（`git diff --name-only 5358408 4ac6aae | grep -i momentsauto` 为空）。若它成立，现象应在 R25 装包后就出现，与「R45 之前没有、之后才有」的硬前提**直接矛盾**。这是判死候选 ② 的关键对照。
- 缺什么证据：**R42 基线装包 + 同一设备同一配置（关视频导出）** 下是否也存在滚动自动播放。若基线也播 ⇒ 硬前提不成立、候选 ② 成立且 R45 只是时间巧合；若基线不播 ⇒ 候选 ② 判死、候选 ① 存活。

**候选 ③（已排除，列出以证伪「最直觉」的解释）：R45 解密让微信缓存里的视频变明文 / CDN 预热让播放器缓存就绪 → 滚动即播。**
- 支持证据：**无**（仅「时间上与 R45 同时发生」的相关性）。
- 反证：Q3 全链条路径不相交 + `sight_` 被图片索引明确排除（`MomentsLogic.kt:406`）；Q5 的 `f25==0` 8.76% 与「微信缓存一直是明文」证伪「之前播不了」前提；模块写的是导出目录副本；Route C（触发微信下载器写缓存）已被 R44 终局作废并归档。
- 缺什么证据：无（已排除，不列入定案候选）。

### 置信度与「硬前提」的张力（必须写清，否则会把相关性写成因果性）
- 总体置信度：候选 ① 低（UNCERTAIN，静态只能确认「新增副作用存在」，无法确认因果）、候选 ② 低且与硬前提矛盾、候选 ③ 排除 ⇒ **有主候选但未定案，且更接近「证据不足」**。
- **硬前提的张力**：`4ac6aae` 一次性带入 R43/R44/R45；而源码里**唯一能制造滚动**（`MomentsAuto.smoothScrollBy`）与**唯一能制造列表刷新**（`x2(256)` → `MomentsSyncTrigger`）的代码都是 **R25**、R45 未改。⇒ 若「R45 之前没有、之后才有」成立，自变量**极可能不在源码 diff 里，而在配置态（Kv）或微信侧状态**：默认值 `auto_export=false` / `copy_videos=false` / `export_list="[]"` / `auto_load=true` / `auto_refresh=false` / `video_decrypt=true`（`Keys.kt:485-516`），若设备保持默认，R45 新增的网络/IO/解密副作用**根本不会自动发生**（只有手动 `viddl`/`vidfill`/点导出才跑）。docs/_r44_chain.md:461 记「`moments.copy_videos` 已置 true」、R47 节记 `export_list` 4 项，说明配置已被改过 —— 但**配置变更不是 git diff 能看见的**，这是本次差分分析的结构性盲区。
- ⚠️ 因此本报告**不把「R45 装包」与「滚动自动播放」的相关性写成因果性**；只给出上面可证伪的候选与判据。

[DONE] Q5-Q6
[定案] 问题7 — 证据: `MomentsExport.kt:533-544,607-620,646-657,696-710,564-580,1452-1457` / `MomentsAuto.kt:282-305,446-499` / `MomentsLogic.kt` diff `shouldDecryptVideo` / `Keys.kt:485-516` / docs/_r44_chain.md:931-948（真机已证 `video_decrypt` 自变量唯一） — 反向对照设计（**自变量唯一；只设计，不执行**）。对照设计本身可静态复算，故标 `[定案]`；每组「预期现象」均为**未验证假设**。

共同前置（每组都固定，保证自变量唯一）：同一设备、同一微信版本、每改一组后**双冷启**、观察同一段朋友圈时间线的同一批视频动态；其余 5 个开关保持基线值不动。⚠️ 陷阱：`MomentsAuto.onTick` 的条件是 `autoRefresh || autoLoad`（:295）且两者**共享同一 `lastRefreshAt` 节拍**（:292-294 注释「不会叠加成双倍频率」）⇒ 单独关 `auto_load` 或 `auto_refresh` 并不总是「只停一件事」，下面逐组标注。

| 组 | 唯一自变量 | 源码侧实际变化 | 预期现象 → 排除谁 |
|---|---|---|---|
| 1 | 关 `moments.auto_export`（其余不动） | `MomentsExport.onTick`:535 与 `pokeExport`:609 两处 `if (!c.autoExport) return` 生效 ⇒ **新导出轮不再启动**；`x2(256)` 投递、`pokeExport()` 的**调用**、`smoothScrollBy` 全部不变（Q2） | **消失** ⇒ 排除候选 ②（自动滚动/同步）与候选 ③，锁定「导出轮」为必要环节，候选 ① 存活。**仍在** ⇒ 排除候选 ① 与 `auto_export` 本身，指向候选 ② / 微信原生 / 配置态 |
| 2 | 清空 `moments.export_list`（`auto_export` **保持开**） | `autoBlockedByEmptyList(c)`:646-657 拦下自动路径 ⇒ 导出轮不跑；但 `auto_export` 仍开、`x2(256)`/`pokeExport` 调用仍发生（内部 `return false`） | **消失** ⇒ 需要「导出轮**真跑**」，候选 ① 存活；**仍在** ⇒ 与导出轮无关，指向候选 ②。与组 1 **联立**：两组都消失 ⇒ 自变量是「导出轮是否真跑」；组1 消失 / 组2 仍在 ⇒ 自变量是 `auto_export` 这个**开关值本身**而非导出轮 —— 源码里它只被 `onTick`/`pokeExport`/`cfg` 读，需 `git grep -n 'MOMENTS_AUTO_EXPORT' 4ac6aae -- wxgroup/app/src/main/java` 复核是否另有消费点 |
| 3 | 关 `moments.auto_load`（须先把 `auto_refresh` **固定为 true** 并记「前置固定」，否则 `||` 耦合会让自变量变成「自动滚动+后台同步」两个） | `onBind` 的 `smoothScrollBy(0,2400)` 停（:461）；`onTick` 因 `autoRefresh` 仍开而照常跑 ⇒ 自变量唯一 = **自动滚动** | **消失** ⇒ 指向候选 ② 的「自动滚动把视频行送进可视区」，且**与硬前提矛盾**（R25 就有），须回头查配置态与 R42 基线；**仍在** ⇒ 排除「自动滚动」，滚动本身不是必要条件（用户手动滚也会播） |
| 4 | 关 `moments.video_decrypt`（其余不动） | `copyVideosForRow` 把 `encKey` 置 0 ⇒ 只停 `decryptInPlace`（含宿主 `MMIMAGEENCJNI.open/transFor/free` 反射）；下载 / rename / 落盘 / `outcome=COPIED` / 计数**全部不变**（docs/_r44_chain.md:931-948 真机已证自变量唯一；`MomentsLogic.shouldDecryptVideo` 注释「关开关由上层把 encKey 置 0 实现」） | **消失** ⇒ 指向**解密层 / 宿主 native 反射**是必要环节（最反直觉、最值得追的一条）；**仍在** ⇒ 排除解密层，指向下载/网络负载或更早的 `auto_export`/`auto_load`/微信原生 |
| 5 | 关 `moments.auto_refresh`（`auto_load` **保持开**） | `onTick` 条件 `autoRefresh || autoLoad`：只要 `auto_load` 开，**tick 仍跑、`x2(256)` 仍投递、`pokeExport` 仍调用** ⇒ 单独关它**不改变后台同步频率** | **现象不变** ⇒ 排除 `auto_refresh` 单独作用，并印证「两开关共享同一节拍」的设计（:292-294）；**现象变化** ⇒ 说明存在源码外影响，须复核 `MomentsSyncTrigger` / `nextRefreshAt` 的消费点。与组 3 **联立（两个都关）** 才能彻底停掉 `onTick` 后台同步 + 自动滚动，这是判死/判活候选 ② 全链的关键 |

### ⭐ 特别回答：关掉自动导出后，现象是否应「立刻」消失？
**按源码：不会立刻消失，但残留多数不构成现象的持续触发源。**
- **会立刻停的**：`MomentsExport.onTick`:535 与 `pokeExport`:609 两个自动入口 early return ⇒ **新的导出轮不再启动**。
- **不会立刻停 / 客观残留（逐项点名）**：
  1. **在途导出轮不中断**：`runAsync()` 单航班 `running` CAS(:565)，已 start 的 daemon 线程要跑完才在 `finally` 里 `running.set(false)`(:575-578)；`runBlocking()`(:696-710) 自身**不检查** `autoExport`。⇒ 关开关后，本轮剩余 `copyVideosForRow` / `backfillVideos` 仍会跑完（可达 120s readTimeout × N 条、≤200MB/条）。**至少要等一个在途轮结束**。
  2. **已下载文件**：`vid_*.mp4` 留在 `/sdcard/Documents/WXGroup/moments/...`，不清理；`imgDone` 幂等使下轮也不重下。
  3. **已刷新的数据 / 持久 Kv**：`x2(256)` 已投递的 NetSceneSnsSync、已写入 SnsMicroMsg.db 的新动态、`MOMENTS_LAST_REFRESH_AT` / `MOMENTS_LAST_EXPORT_AT` / `moments.video_fill_cursor` 游标，都不回滚。
  4. **已挂 hook 不卸载**：`MomentsAuto` 的 `onBindViewHolder` / `setAdapter`、`SnsGuard` 的 `updateWithOnConflict` 在进程 `install()` 时挂一次；关开关不卸载，`ensureTick` 的 Handler 定时器也不停（`onTick` 只是 early return）。
  5. **进程内计数/缓存**：`sessionCount`/`lastSize`/`lastRv`、`running`/`lastPokeAt`/`lastRunAt`、11 个 `vid*` AtomicLong、`hookedAdapters`/`hookedMethods` 集合 —— 进程内不清零。
  6. **微信侧**：`libwechatcommon.so` / `MMIMAGEENCJNI` 已加载、微信自己的 `sight_*` 明文缓存已存在 —— 与模块开关无关。
- **精确判据**：上述残留里**只有 ① 在途轮**可能继续产生 R45 副作用；②③④⑤⑥ 都不参与微信播放路径（Q3/Q5 路径不相交），**不构成「滚动自动播放」的持续触发源**。⇒ 关 `auto_export` 后等「在途轮 `finally` 复位 + 一次双冷启」，现象应消失；若双冷启后仍播 ⇒ 排除候选 ①；若**同一滚动会话内立刻消失**，反而说明因果不在导出轮（导出轮不受开关即时中断）—— 源码里找不到「与开关同生命周期」的状态，故「立刻消失」是**反预期**，须复核配置消费点。
- ⚠️ 冷启边界：冷启清的是**进程内计数/缓存**并**重新 `install()` 挂一遍 hook**（hook 不是消失、是重挂）；**Kv 持久状态、已落盘文件、微信 `sight_*` 缓存不随冷启清零**。别把「冷启复位」误当成「全部复位」。

### 三档红线自检（进入 / 返回 / 复位，逐档点名；本项目踩过「只写进入/返回、漏掉复位」的坑）

**进入**
- 用户打开朋友圈时间线：`MomentsAuto.registerLifecycle.onActivityResumed` → `sessionCount=0`、`timelineActive=true`(:371-379)；`onBind` 开始高频触发（R25）。`MomentsExport` **无进入钩子**（导出轮只由 `onTick`/`pokeExport` 驱动，不因进页面触发）。
- 首个视频动态进入可视区：`onBind`(:446-499) **不识别 item 是不是视频**（全文无 mediaType / `type==15` 判断），只认 adapter 前缀 + `loadMoreDue` ⇒ R45 **没有「视频进入可视区」的入口**。
- 刚把某好友加入导出名单后的第一次滚动：`export_list` 变化由 `MomentsExport.refresh()/cfg()` 在**下次 tick** 才读到（有 `cfgAt` TTL），**不立即触发导出**（要等 `exportDue` 或 `pokeExport`）⇒ 「刚加名单」与「滚动」无同步因果。
- ⇒ 进入档**无 R45 新增副作用被触发**；若现象在进入即现，指向微信原生或 R25 的 `onBind`。

**返回**
- 滚动到底触发「加载更多」后新一批动态上屏：`onBind` 的 `grow`(:476-481) 会响，但 R45 未改；新动态上屏后，若 `copy_videos=true`，要等**下一轮导出**（tick/poke）才去下载这些视频 —— 不是上屏当场。
- 切到好友相册/详情页再回时间线：`onActivityResumed` 重置 `sessionCount`、`timelineActive`、`lastRv` 弱引用重建（`createHook`:419-443）；R45 无相关钩子。
- 后台 `x2(256)` 同步成功后列表刷新：`doRefresh` 成功后 `pokeExport()`(`MomentsAuto.kt:343`) ⇒ 若 `auto_export && copy_videos && 名单非空` 则起导出轮（含下载+解密）。**这是 R45 唯一与「返回档」有交集的触发点**。
- `pokeExport` 之后：导出轮在 daemon 线程跑（`MomentsExport.kt:564-580`），单航班 CAS。
- R46 装包双冷启之后：`install()` 重挂 hook、重装 ticker、`MomentsSyncTrigger.install`，`MomentsExport.install()` 日志新增 `videos=${config.copyVideos}`；`moments.video_decrypt` 默认 true 生效（唯一无需配置即生效的新默认值）。
- ⇒ 「返回档」唯一可能的 R45 因果窗口是「`x2(256)` 成功 → `pokeExport` → 导出轮」，但导出轮与「列表刷新 / 视频起播」之间**没有源码级连接**（Q3/Q5），只能作为「后台负载」假设（候选 ①）。

**复位**（逐项点名，不漏）
- 滚动停止 `RecyclerView.SCROLL_STATE_IDLE`：模块**无** `onScrollStateChanged`/`onScrolled`/`addOnScrollListener`（全树零命中，前置事实 5）⇒ **模块不感知滚动停止，无任何复位动作**。
- 视频行滑出屏幕：模块无 view 生命周期钩子 ⇒ 无复位。
- 退出朋友圈页 `onPause/onDestroy`：`MomentsAuto` lifecycle 只处理 Resumed/Paused(:371-384)，`onActivityPaused` 只置 `timelineActive=false`(:383)，`onActivityDestroyed` 是**空实现**(:390) ⇒ **R45 无页面级复位**；导出轮 daemon 线程不因退出页面停止。
- 关掉上述任一开关：见上「特别回答」（在途轮不中断、文件/DB/hook/计数残留）。
- 冷启微信（进程内计数/缓存清零）：`ticks`/`refreshes`/`binds`/`snsBinds`/`loads`/`grows`、`sessionCount`、`lastSize`、`lastRv`、`running`、`lastPokeAt`、`lastRunAt`、11 个 `vid*` AtomicLong、`hookedAdapters`/`hookedMethods` 全清零，hook 重新 `install()`（**重挂，不是消失**）；**Kv 持久状态不复位**、已下载 `vid_*.mp4` 不删、微信 `sight_*` 缓存不删、`libwechatcommon.so` 随进程重新加载。
- ⇒ **轮级复位存在**：`runAsync()` 的 `finally { running.set(false) }`(`MomentsExport.kt:575-578`，注释明确「漏了它一次异常之后就再也不会导出」)。**开关级 / 页面级 / 滚动级复位在 R45 全部缺失**。判据：若现象真由导出轮引起，关开关后必须等「在途轮 `finally` 复位 + 冷启」才干净；冷启后仍播 ⇒ 排除候选 ①。

[DONE] Q7-红线

---

## 总体结论：证据不足（有主候选但未定案）
综合 Q1-Q5：`git diff 5358408 4ac6aae` 的全部 R45 运行时副作用中，**没有任何一条挂在 `WxRecyclerAdapter.onBindViewHolder`、RecyclerView 滚动回调或视频 view 生命周期上**；候选链 A 的「频率/触发条件变化」被 Q2 排除，B（CDN 预热/共享缓存）被 Q3 以「路径不相交」排除，C（onBind 同步 IO/重绑）被 Q4 排除，D（原生机制被解密/预下载解锁）被 Q5 排除。四条候选链的全部**源码级连接点**均已排除或降为假设。最可能的是**候选 ①（由配置态激活的 R45 导出轮后台负载间接改变滚动时序）**，但它与「视频起播」之间不存在任何静态可证的因果连接。⇒ **不把「R45 装包」与「滚动自动播放」的相关性写成因果性；静态证据不足以定案。**

## 最薄弱环节
1. **配置态不可见（最致命）**：`auto_export` / `copy_videos` / `export_list` / `video_decrypt` / `auto_load` / `auto_refresh` 的**设备当前值**不在 git diff 里；而默认值 `auto_export=false` / `copy_videos=false` / `export_list="[]"` 下，R45 新增的网络/IO/解密副作用**根本不会自动发生**。整个差分分析建立在「用户改过配置」这一未验证假设上（docs/_r44_chain.md:461 记 `copy_videos` 已置 true、R47 节记 `export_list` 4 项，但无全量快照）。
2. **R42 基线对照缺失**：没有「同设备、同配置、关视频导出」的 R42 基线观察，既无法判死候选 ②，也无法验证「R45 之前没有」这一硬前提本身是否成立。
3. **微信侧黑盒**：`MMIMAGEENCJNI` native 内部是否含全局状态、播放器起播判据（`OnlineVideoView#N` / `SnsSightPlayerUI`）、`sight_*` 缓存建立时机，静态不可判。
4. **时间相关性缺失**：现象时刻与导出轮 / `x2(256)` / `pokeExport` / `VIDDEC` 日志无对齐证据；LogX 5000 行环形覆盖（docs/开发回路速查.md）可能已冲掉 R45 装包前后的日志。
5. **差分粒度**：`4ac6aae` 一次性带入 R43/R44/R45，无法用 git 精确切出「纯 R45」；而唯二能「制造滚动」(`MomentsAuto.smoothScrollBy`) 与「制造列表刷新」(`x2(256)`) 的代码都是 **R25** 且 R45 未改 ⇒ 若硬前提成立，自变量极可能在源码 diff 之外。

## 最小补充证据（只列命令与判据，⛔ 本轮一律不执行）
> 静态可复算（本轮已做，列出供复核）：`git diff --name-status 5358408 4ac6aae`；`git diff 5358408 4ac6aae -- <文件>`；`git diff --name-only 5358408 4ac6aae | grep -iE 'momentsauto|snsguard'`（应为空）；`git grep -n 'MOMENTS_AUTO_EXPORT' 4ac6aae -- wxgroup/app/src/main/java`（确认 `auto_export` 消费点全集）；`git show 4ac6aae:wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsAuto.kt | grep -n 'smoothScrollBy\|autoLoad\|autoRefresh'`。
>
> 以下均**需真机/日志，⛔ 本轮不执行**；姿势按 docs/开发回路速查.md 的 `kvget` / `logclear` / LogX 环形日志 / `op=moments` 探针表。
1. `kvget`（读 Kv 全量快照）— 判据：若 `moments.auto_export=false` **或** `moments.copy_videos=false` ⇒ R45 新增副作用不会自动发生 ⇒ **候选 ① 判死**，硬前提需重述为「配置变更」而非「R45 源码」；若两者皆 true 且 `export_list` 非空 ⇒ 候选 ① 存活，继续 2。
2. `op=moments sub=stat`（`MomentsAuto.probe`，输出 `refresh/load/interval/ticks/refreshes/bgSync`）+ `MomentsExport.stat()`（`videos/vidCopied/vidAlready/fillCursor/vidFillCursor`）— 判据：`ticks/refreshes/vidCopied` 是否随时间增长；若全为 0 ⇒ 导出轮没在跑 ⇒ 候选 ① 判死。
3. `logclear` → 复现「滚动朋友圈」→ 拉 LogX 环形日志，对齐 `MOMENT refresh bg-sent` / `MOMENT autoload` / `MOMENT export` / `VIDDL` / `VIDDEC inplace` / `MOMENT vid copied` 的**时间戳** — 判据：若起播时刻与上述任一日志**无稳定先后关系** ⇒ 候选 ① 判死；若每次起播都紧跟 `VIDDEC`/`VIDDL` ⇒ 候选 ① 升级为主嫌，须补 4。
4. 按 Q7 组 1/组 4 做**自变量唯一**的反向对照（关 `auto_export` / 关 `video_decrypt`，各双冷启）— 判据：见 Q7 表格「预期现象 → 排除谁」。
5. **R42 基线对照**（同设备、同配置、`copy_videos` 关，装回 `5358408` 对应包）— 判据：若基线**也**滚动自动播放 ⇒ **硬前提不成立**，候选 ② 成立、R45 只是时间巧合；若基线不播 ⇒ 候选 ② 判死、候选 ① 存活。⛔ 涉及装包/真机，本轮禁止，仅列为决定性判据。
6. 微信侧读取路径取证（播放器侧 hook / 文件访问日志）— 判据：若微信从未打开 `/sdcard/Documents/WXGroup/moments/.../vid_*.mp4` ⇒ Q3/Q5 的「路径不相交」得到终局确认；若打开了 ⇒ 需重开 Q5。

[DONE] 总结
[DONE]
