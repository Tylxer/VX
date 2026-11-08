# R48 独立反证报告：朋友圈滚动视频自动播放

> 只读反证：预设 `docs/_r48_root_cause_autoplay.md` 的结论可能有错。目标是找漏项、检验硬前提本身、审查「排除」是否过度，而非复述或同意。
> 交付约束：只写本文件；不改任何源码 / 测试 / 配置 / 设备；不碰 adb / 真机 / Gradle / 装包；判据只设计、不执行。

## 0. 范围
- 工作区：`C:\Users\lx\WorkBuddy\2026-09-29-20-23-21`；项目：`wxgroup/`（Kotlin / LSPosed 模块）；宿主：微信 8.0.48。
- 只读对象：git 差分 `5358408→4ac6aae`、R45/R46 源码与接线文件、R44 归档探针、配置默认值（`Keys.Prefs.ALL`）、时间线文档（`docs/_r44_chain.md`、`.workbuddy/memory/2026-11-08.md`）、另一份只读报告 `docs/_r48_root_cause_autoplay.md`。
- 待解释现象：滚动朋友圈时视频自动播放；用户判断「刚完成 R45 朋友圈自动导出视频引起」。

## 1. 方法
1. 先读另一份只读报告，记录其结论与「排除」清单，作为待反驳靶子。
2. 检验硬前提本身：不以用户的时间相关性判断为既定因果；先列竞争解释与可证伪判据。
3. 五个方向独立取证：① 硬前提可证伪性 / 竞争解释；② 时间维度盲点（R44 归档探针是否曾在设备常驻挂 hook）；③ 「路径不相交」排除是否过度；④ 全源码树 grep 找 R45 新增的滚动期可触发运行路径；⑤ 最小、自变量唯一的判据设计与反向对照。
4. 三档红线（进入 / 返回 / 复位）逐档自检，重点检验「关开关后是否应立刻消失」。
5. 分步落盘：骨架 → 每答完 2 个方向追加一次，每步留 `[DONE] <子步>`。
6. 每条结论格式：`[支持|反驳|UNCERTAIN|排除过度] 方向N — 证据: 文件:行号 — 说明`。静态证据不足一律写 `UNCERTAIN`，不因「看起来不相交」写「排除」；「A 推 B」时序结论必须标假设。dex 有字符串 ≠ 该业务调用它。

## 2. 待检验的硬前提（本报告的靶子）
- H1：「R45 引起」是时间相关性判断，不是因果证明；需竞争解释与可证伪判据（现象是否一直存在 / R46 双冷启 / 配置态变化）。
- H2：另一报告的差分结论「R45 新增运行时副作用全在后台 daemon 线程、无一条挂在 `onBindViewHolder` / 滚动回调 / 视频 view 生命周期」是否完整，是否漏掉 R44/R45 期间仍装在设备上的常驻 hook。
- H3：四条候选链（后台同步频率 / CDN 预热共享缓存 / onBind 同步 IO / 解密解锁原生播放）被判「排除」是否过度。
- H4：「模块只写 `/sdcard/Documents/WXGroup/moments/`、与微信缓存路径不相交」是否足以排除共享缓存 / 全局单例 / native 全局状态。
- H5：现象是否可能一直存在（微信原生滚动起播），R45 仅让用户开始留意；或由 R46 装包双冷启、配置态变化引入，而非源码 diff。

## 3. 分步落盘计划
- [x] 骨架（本步）
- [ ] 方向 1 + 2 取证与结论 → `[DONE] 方向1-2`
- [ ] 方向 3 + 4 取证与结论 → `[DONE] 方向3-4`
- [ ] 方向 5 + 三档红线 + 裁决 → `[DONE] 方向5-红线-裁决`

## 4. 结论（分步追加）

> 路径约定：下列 `*.kt` 若未带前缀，均指 `wxgroup/app/src/main/java/com/lx/wxgroup/` 下同名文件（归档探针写作 `docs/_archive/r44_probes/…`）。所有 git 结论均可用文末「最小补充证据」中的命令静态复算。

### 4.1 方向 1（硬前提可证伪性）+ 方向 2（时间维度盲点）

**[反驳] 方向1 — 证据: `docs/_r48_root_cause_autoplay.md:4` / `.workbuddy/memory/2026-11-08.md:34,100,128,151` / `docs/_r44_chain.md:994` — 「R45 引起」是用户的时间相关性判断，另一份报告 :4 把它直接写成「硬前提」并据此把全文限定为「找 R45 引入的哪一条运行时副作用」。但同一天里事件密集：R45 完成 04:11（memory:34）、R46 收口 04:4x（memory:100）、R46 装包+双冷启 `lastUpdateTime 03:58:06 → 16:28:33`（memory:139）、R47 取证 20:12（memory:151）。**用户首次留意/首次报告现象的时点没有任何记录**。⇒ 该前提既未被证实、也可证伪；以它为轴去排除其它解释，等于先把结论塞进了方法。**

**[支持] 方向1 — 证据: `docs/_r44_chain.md:418-425` / `.workbuddy/memory/2026-11-08.md:30` / `docs/_r48_root_cause_autoplay.md:113-115` — 竞争解释 C1「现象一直存在（微信 8.0.48 原生滚动起播），R45 只是让用户开始留意」有硬证据：R44 终局真机记录「微信缓存的 5 个 `sight_*` **全部是明文**（ftyp isom）」「微信在本地解密后才写缓存」，memory:30 补「用户看过的才有」。⇒ **播放器「滚动到该行、本地文件已就绪即起播」的物质条件在 R45 之前就已具备**，与模块写哪个目录无关；另一份报告 Q5（:113-115）自己也不得不承认「微信本地缓存一直是明文」。可证伪判据：R42 基线包（同设备、同配置、关视频导出）滚动同一批视频行，**若基线也自动起播 ⇒ 硬前提被证伪、C1 成立**。⛔ 需装包/真机，本轮不执行。**

**[UNCERTAIN] 方向1 — 证据: `.workbuddy/memory/2026-11-08.md:100-110,128-145` / `git show --stat 4ac6aae` — 竞争解释 C2「R46 装包+双冷启（16:28:33）或 R46 UI 改动引入」：R46 改的是 `PickerTabLogic.selectedFirst` / `PickerPage.Adapter.render` / `ForwardPicker.TabAdapter.reset`（memory:105-106），属**选人组件渲染路径**，不在朋友圈时间线 Adapter；`MomentsAuto.kt` 不在改动面（`git diff --name-status 5358408 4ac6aae`）。⇒ 源码侧连接点弱。但双冷启会 `install()` 重挂 hook、清进程内计数、让 `video_decrypt` 默认 true 首次生效，且用户报障时点与 16:28 的先后无记录。可证伪判据：确认现象首次出现时刻是落在 04:11–16:28 之间还是 16:28 之后；若只在 16:28 之后 ⇒ C2 上升。标 `[假设]`（时序推断，无日志）。**

**[支持] 方向1 — 证据: `Keys.kt:485-516` / `.workbuddy/memory/2026-11-08.md:69,140,142,152-155,166-170` / `docs/_r48_root_cause_autoplay.md:68,143` — 竞争解释 C3「配置态变化而非源码 diff」是当前**证据最厚**的竞争解释：① `Prefs.ALL` 默认值 `auto_refresh=false`(:485) / `auto_load=true`(:486) / `auto_export=false`(:491) / `export_list="[]"`(:493) / `copy_videos=false`(:508) / `video_decrypt=true`(:514)。默认态下 R45 新增的网络/IO/解密副作用**不会自动发生**；`video_decrypt=true` 是唯一「装上即生效、无需用户配置」的新默认值。② 名单人数在变：04:27 清理时 `moments.export_list` 是 **3 项 wxid**（memory:69），20:12 已含**第 4 位好友** `东营易泰新材料王琪(wxid_xxg6w848hmz622)`（memory:152-155），其视频 13:42 发布、17:10 落 13MB MP4、17:47 补 9.8MB MP4 —— 均在 16:28 双冷启**之后**；memory:142 记 R46 装包「全程未点确定 ⇒ 名单配置仍 4 人未改」。⇒ **第 4 位好友的加入时点无记录（介于 04:27 与 20:12 之间，`[假设]`），但它使「导出轮覆盖到新好友的视频」成为一个与 R45 源码无关的新自变量**。③ memory:166-168 记用户 20:12 禁止改配置/验证 ⇒ **设备当前 Kv 全量快照从未取过**，配置态是静态 diff 的结构性盲区（另一份报告 :68/:143 也承认，却仍把主候选压在「R45 导出轮」上）。可证伪判据：`kvget` 全量快照 + 现象是否只发生在名单好友/已导出视频行；清 `export_list` 后现象若消失 ⇒ C3 成立。**

**[UNCERTAIN] 方向1 — 证据: `.workbuddy/memory/2026-11-08.md:30` / `docs/_r44_chain.md:423` — 竞争解释 C4「微信侧/系统侧状态变化」：微信自身自动播放开关（WiFi/移动数据）、用户「看过的」视频累积出的 `sight_*` 明文缓存数量随使用增长（memory:30 明说「用户看过的才有」）。这是纯宿主侧自变量，与模块任何版本无关；静态不可判。可证伪判据：同设备对照微信设置 + 统计 `sight_*` 数量与现象命中率是否正相关。**

**[反驳] 方向2 — 证据: `docs/_r44_chain.md:996-1010` / `.workbuddy/memory/2026-11-08.md:114-115` / `git diff --name-status 5358408 4ac6aae` / `git log --all --oneline --full-history -- '*SnsDispatchProbe.kt'` — ⛔ **本报告最重要的一条**：R44 两个探针在 R44/R45 期间**确实在编译单元内、确实有接线、确实上过真机**。`_r44_chain.md:998-1005` 原文：`probe/SnsDispatchProbe.kt`(685 行) 与 `probe/VidTrigProbe.kt`(327 行) 移到 `docs/_archive/r44_probes/`，「从源码树摘掉全部接线」——`HookEntry.kt` 删 2 行 import + 2 行 `prepare(...)`（:1002-1003，摘完 diff 归零）、`EntryReceiver.kt` 删 `vidtrig`/`dispatch` 两个 when 分支（:1004-1005）；:1009 明写它们是 **`??` 未跟踪**文件。memory:114-115 同证。git 侧交叉验证：`git log --all --full-history -- '*SnsDispatchProbe.kt' '*VidTrigProbe.kt'` 只返回 `4ac6aae` 一次；`git diff --name-status 5358408 4ac6aae` 只有 `A docs/_archive/r44_probes/*.kt`、**无配套 `D`**、`HookEntry.kt` 根本不在改动面、`EntryReceiver.kt` 的 diff 只有 `+id` 与 `+viddec` 两处新增。**⇒ 用户方向 2 的硬前提成立；另一份报告 Q1⑨（:56）「R44 是取证/推导轮，不产出运行时副作用」「移出编译单元」以及 Q1 全表（:44-62）「R45 相对 R42 的全部运行时副作用清单」是把「R46 归档后的仓库状态」误当成「R44/R45 期间设备装包态」。`git diff` 对 `??` 未跟踪文件结构性失明 —— 这是差分级的结构性盲区，不是措辞小疵。R44 终局还留着 `op=moments sub=dispatch sub3=real` 的真机实证（`docs/_r44_chain.md:393-395`），即探针代码在设备上真跑过。**

**[UNCERTAIN] 方向2 — 证据: `docs/_archive/r44_probes/SnsDispatchProbe.kt:11-30,50-52,150-157,176-298,628-645,678-684` / `docs/_r44_chain.md:393-395` / `.workbuddy/memory/2026-11-08.md:27` — 「探针把视频写进微信自己的缓存目录，让播放器滚动到该行检测到文件已就绪而自动起播」：**机制上可能、真机无落盘实证，标 UNCERTAIN，不写「排除」也不写「支持」**。机制侧：探针 KDoc :11-18 自述目标就是「主动让微信自己的下载器去下」，链路 :23-30 写 `l0#d → l0#h → l0#z → Ljf3/l(SnsDownloadSight) → c3(SnsCdnDownloadBase) → native 解密 + 落盘明文 MP4`；:176-298 是真下发代码（`m.invoke(l0,…)` :270-278、手动 `callZ(l0)` :287、三轮 `findSight` 落盘复查 :292-297）；`snsRoots()` :638-645 明确根是 `.../cache/<hash>/sns/<x>/<y>/`（:631-633 注释「`sight_*` 实际落在 `cache/<hash>/sns/`」），`findSight()` :678-684 按 `MD5(mediaId)` 分桶找 `sight_*`。⇒ **探针自己就在读写微信播放器用的那套 `sight_*` 缓存路径**，这正是另一份报告 Q3（:88-96）用「路径不相交」排除时完全没有覆盖的路径（它只审了 R45 `MomentsExport` 写 `/sdcard/Documents/WXGroup/moments/`）。反证侧：R44 终局三轮 `probe/real/real-looper` 全部 `l0.l`/`l0.e` 队列恒 `size=0`、手动 `l0#z()` 无变化、**不落盘**（:393-395；memory:27）。⇒ 「探针成功写入过微信缓存」无实证；但「队列恒 0」只覆盖当时那批样本与那三次调用，不能外推为「该代码路径在任何时刻都不可能落盘」。静态无法证伪 ⇒ `UNCERTAIN`。**

**[反驳] 方向2 — 证据: `docs/_archive/r44_probes/SnsDispatchProbe.kt:81-83,89-103` / `docs/_archive/r44_probes/VidTrigProbe.kt:15,79,92-99,117-172,247-252` / `docs/_r44_chain.md:1004-1005` — 「常驻 hook 在每次滚动时触发下载」这一强假设被反驳，但**不能因此把探针整体判为无关**：`SnsDispatchProbe` **全文没有任何 Xposed hook 注册**（无 `findAndHookMethod`/`hookMethod`/`hookAllMethods`），`prepare(lpparam)` :81-83 只存 classLoader，`run(arg,mode)` :89 由 `EntryReceiver` 的 `dispatch` 广播分支被动调用（用法 :50-52；接线 :1004-1005）⇒ **广播驱动、一次性、无 hook**。`VidTrigProbe` 确有真 hook（`findAndHookMethod` :132-135/:145-153、`XposedBridge.hookMethod` :215），但 `install()` 只在 `command(sub2="on")` 时调用（:96-99/:117-172），`off` 走 `unhook()`（:98-99/:247-252）；且所有 hook 都是 **before 只读**：:15 明写「不改任何参数、不 return 替代值、不阻断原方法」，:196-213 只 `counter.incrementAndGet()` + 打日志。⇒ 探针不是「进朋友圈页面就自动常驻挂 hook」，而是「手动广播才挂/才动作」；一旦 `on` 过，hook 常驻到 `off` 或冷启，但纯观测不改宿主行为。**这条同时反驳了用户方向 2 里「常驻 hook」的最强版本，也反驳了另一份报告 Q1⑨「完全无副作用」的最干净版本**。**

**[UNCERTAIN] 方向2 — 证据: `docs/_r44_chain.md:996-1010,990-992` / `.workbuddy/memory/2026-11-08.md:84-95,147` / `docs/_archive/r44_probes/SnsDispatchProbe.kt:155-157` — 「R46 摘除接线后，这个候选是否应当消失？若用户现在仍能复现，对 R44 探针假设是支持还是反证？」必须**分两层**回答，单层回答必错：
- **接线层**：R46 摘线后探针不在 APK、`dispatch`/`vidtrig` 分支不存在 ⇒ 不再产生**新的**探针动作。若假设是「探针持续在后台反复触发微信下载」，则现在仍能复现是**反证**（且该假设已被上一项「广播驱动」否决）。
- **持久化层**：探针（或微信自己）**已落盘的 `sight_*` 明文缓存不随 APK 摘线/重装/冷启而删除** —— 它在 `cache/<hash>/sns/`，既不在模块导出目录，也不在 `/sdcard/` 那一批被清理的临时文件里（memory:84-95 的清理明确只针对 `/sdcard/` 取证临时文件与模块导出目录；:147 记设备侧只清 `_r46*` 临时文件）。`SnsDispatchProbe.kt:155-157` 自身就有「已落盘，不需要下载」的 `cacheHit` 短路，说明**缓存一旦存在即长期有效**。⇒ 若假设是「R44/R45 期间曾留下持久缓存，使特定视频行滚动即播」，则 R46 摘线**不使候选消失**，现在仍复现**不构成反证，反而是弱支持**，并给出可证伪判据：现象应**集中在名单好友 / 被 `dispatch` 过的 snsId / 用户「看过的」那 5 个 `sight_*` 对应的行**，而非时间线上任意视频行；且**冷启微信后仍应存在**（缓存不随进程内计数清零）。若现象是全时间线任意视频行普遍出现 ⇒ 该持久缓存假设被证伪，回到 C1/C4。
- ⇒ 综合：`UNCERTAIN`，并明确**「现在仍能复现」本身不能单独裁决 R44 探针假设**，必须先固定「现象分布 + 冷启后是否仍在」这两个观测量。**

**[排除过度] 方向2 — 证据: `docs/_r48_root_cause_autoplay.md:56,88-96,110-119,138` — 另一份报告 Q1⑨ 以「R44 终局判定 Route C 不需要」「探针已移出编译单元」为由，把 R44 探针整族判为「无运行时副作用」，并在 Q3（:94）与 Q5（:117/:138）用「Route C 已作废」顺手排除掉「模块触发微信下载器写微信缓存」这条路径。这是**排除过度**：① 「R46 归档后的仓库状态」≠「R44/R45 期间设备装包态」（见上，探针是 `??` 未跟踪、上过真机）；② 「Route C 对导出目标多余、当时三轮未落盘」是**目标层与单次实验层**的结论，不等于「该代码路径在宿主进程里从未产生任何副作用/从未留下缓存」；③ Q3 的「路径不相交」只核对了 R45 `MomentsExport` 的落盘目录，未覆盖 `SnsDispatchProbe.snsRoots()` :638-645 指向的 `cache/<hash>/sns/`。⇒ 这三条「排除」应降级为 `UNCERTAIN`。**

[DONE] 方向1-2

### 4.2 方向 3（「路径不相交」排除是否过度）+ 方向 4（漏掉的运行路径）

**[排除过度] 方向3 — 证据: `docs/_r48_root_cause_autoplay.md:88-96,110-119,206` / `MomentsExport.kt:1382-1389,239,250` / `docs/_archive/r44_probes/SnsDispatchProbe.kt:23-30,638-645` / `SnsVideoDecProbe.kt:171-184` — 「路径不相交，排除」至少在两处过度：① Q3（:88-96）只核对了 **R45 `MomentsExport`** 写的 `/sdcard/Documents/WXGroup/moments/`，据此断言「与微信缓存路径不相交」，却漏了 R44 `SnsDispatchProbe.snsRoots()` 明确指向的 `.../cache/<hash>/sns/<x>/<y>/sight_*`（:631-633,:638-645）与 R45 `SnsVideoDecProbe.key()` 只读扫描 `sight_*` 文件名（:171-184）—— 模块**能读**微信缓存路径，「不相交」不是全局事实。② Q3（:96）与 Q5（:117）把「微信播放器『滚动即播』的判据是**本地** `sight_*` 是否存在」当作已证事实来排除 CDN 侧，但同一份报告「最薄弱环节 3」（:206）自己写「播放器起播判据（`OnlineVideoView#N` / `SnsSightPlayerUI`）…**静态不可判**」⇒ **自相矛盾**：用未证判据做排除依据，逻辑不成立。按项目纪律（dex 有字符串 ≠ 该业务调用它；静态不足写 UNCERTAIN），这几条应降级为 `UNCERTAIN`。**

**[UNCERTAIN] 方向3 — 证据: `docs/_archive/r44_probes/SnsDispatchProbe.kt:23-30,150-157,337-346,585-603,678-684` / `VidTrigProbe.kt:26-33,52-63` / `MomentsExport.kt:1382-1389,1418-1419,239,250` — 「微信 SNS 视频下载是否为全局单例 / 有基于 URL 的内存或 DB 去重表；模块用 UA `WeChat/arm64` + 相同 `snsvideodownload` URL 直连是否可能被微信侧感知为『该 URL 已下载完成』」：
- **单例：有证据**。`getDownloadManager()` :585-603 先反射 `f4#ea()`、失败退化读 **`f4.E` 静态字段**（:597），KDoc :23 明写「getSnsDownloadManager → `l0` **单例**（懒加载）」；`l0.l`/`l0.e` 是队列（:337-346,:210-211）。⇒ 微信侧存在**进程级单例下载管理器**。
- **去重：至少有文件级判重**。`findSight()` :678-684 按 `MD5(mediaId)` 分桶找 `sight_*`，:155-157 有「已落盘，不需要下载」短路；`VidTrigProbe.kt:57-61` 记 `l0` 的 `d/e/f/h/i/g` 是不同下载任务入口，`ok0.x0#r` :26-33 是 CDN 传输引擎。⇒ 微信侧至少按 mediaId 判「已落盘」，但是否有**基于 URL 的跨通道去重表**未知。
- **模块侧：不经微信通道**。模块是 `URL(url).openConnection() as HttpURLConnection`（`MomentsExport.kt:1384`），UA `WeChat/arm64`（:250,:1389）、`Referer` 恒空（:239,:1388），只多读 `X-encflag`/`X-enclen`（:1418-1419）；不经过 `l0`/`c3`/`ok0.x0#r`，不写 SnsInfo DB、不发广播、不回写路径（全树 grep 无 `ContentValues` 写回 SnsInfo）。
- ⇒ **但「不被感知」不能被静态证明**：进程内是否存在全局 `HttpResponseCache`、微信是否维护 URL→下载状态表、CDN 服务端预热是否改变微信自己建立 `sight_*` 的时机，**三者静态全部不可判**。另一份报告 :96 虽写了 `[UNCERTAIN，无因果链] 保留项`，却在同一段用「播放器判据是本地缓存、不是 CDN 快慢」把它压成「不构成可定案的因果链」⇒ 结论方向是「排除」。**本报告的裁决：`UNCERTAIN`，不写「排除」，也不写「支持」**；静态证据无法排除即不得排除。**

**[UNCERTAIN] 方向3 — 证据: `SnsVideoDecProbe.kt:13-24,32-36,275,282-289,306-322,400-427,460-462` / `.workbuddy/memory/2026-11-08.md:46` / `docs/_r48_root_cause_autoplay.md:58,116,120` — 「`MMIMAGEENCJNI` 反射是否可能改动宿主 native 全局状态、间接影响微信播放器解密」：
- **共享 .so**：:14「native 在 `libwechatcommon.so`」、:42 `SO_NAME`；`soLoaded()` :460-462 读 `/proc/self/maps` 判断该库是否已加载。`loader()` :275 = `SnsGuard.hostLoaderHandle()` ⇒ 反射在**宿主进程内**执行，与微信自己的解密**同进程、同 .so**。
- **句柄表形态**：KDoc :17-24 的 API 是 `open(long key) → long handle` / `transFor(handle, buf, off, len)`（原地 XOR）/ `free(handle)`。`openHandle` :306-309 返回的 `long h` 是 native 侧句柄。这种形态通常意味着 native 维护上下文/句柄表，**静态无法证明该表是实例私有还是进程级静态全局**（ISAAC-64 上下文、文件句柄表同理）。
- **是否每次新建实例：是**。`newInstance(cls)` :282-289 每次 `getDeclaredConstructor().newInstance()`（失败退第一个构造器 null 填充）；`decryptInPlace` :410、`dec` :105、`tryDec` :198 各自新建。⇒ 另一份报告 Q1⑪ 说的「每次新建实例」成立。**但 Java 实例 ≠ native 上下文隔离**：若 native 用静态全局状态，`newInstance` 无任何隔离作用；「新建实例」不能推出「无全局态」。
- **是否真的 `free`：配对调用属实，但有不可观测的泄漏窗口**。`decryptInPlace` :424-427、`dec` :153-157、`tryDec` :248-252 全是 `try { transFor } finally { freeHandle }`；`freeHandle` :320-322 调 `free(h)`。但 ① `freeHandle` 用 `runCatching` **吞异常且不记日志**（无 `.onFailure`）⇒ free 失败不可观测；② `h < 0` 时 :420-422 直接 `return false` **不调 free**，若 native 此时已分配上下文即泄漏。
- **⛔ 文档互斥（必须标，不得只采信一方）**：`SnsVideoDecProbe.kt:32-33` KDoc 写「方法全是**实例方法**（`public final` 非 `static`）」，而 `.workbuddy/memory/2026-11-08.md:46` 写「解密走微信自身 `MMIMAGEENCJNI.transFor`（**static native**，原地 XOR）」。若实际是 static native ⇒ **必然是进程级全局状态**，`newInstance` 完全无意义。两份文档互斥，静态不可判。
- ⇒ **`UNCERTAIN`**。另一份报告 Q1⑪（:58）本已标「native 内部是否含全局态静态不可判，标 UNCERTAIN」，但 Q5（:116「两者不共享文件、不共享句柄、不共享缓存目录」、:120「不改变微信侧任何状态」）又把 UNCERTAIN 收成定案 ⇒ **排除过度**。**

**[支持] 方向4 — 证据: 全树 grep（命令见下）/ `MomentsExport.kt:411,529,564-579` / `EntryReceiver.kt:112,127,671-684` / `HookEntry.kt:150-155` — 独立 grep 全源码树，**未发现 R45 新增任何会在滚动时被触发的 `ContentObserver` / `FileObserver` / 新增 `BroadcastReceiver` / `Handler.post` 定时器 / `Timer` / 线程 / DB 写**：
- `ContentObserver|registerContentObserver|FileObserver` 全树 **零命中**；`onScrollStateChanged|onScrolled|addOnScrollListener` 零命中；`MediaPlayer|VideoView|TextureView|SurfaceView|setDataSource|startPlay|playVideo|autoplay` 零命中（独立复核了另一份报告前置事实 5，结论一致）。
- `BroadcastReceiver` 只在 `EntryReceiver.kt:112,127`（R25 起的**动态注册调试广播入口**，R45 只加 `id` 参数与 `viddec` 分支，均在 `io{}` 后台协程、且需外部广播触发）；`HookEntry.kt:150-155` 明写热路径定位探针一律不再安装。
- `GroupBar.kt:286` 注释命中「`AbsListView.trackMotionScroll` 每次滚动都会走」，但那是**会话列表**顶部条、且属被摘除的定位探针语境，**与朋友圈时间线无关**，排除。
- ⇒ 可复算命令（只列，不执行设备侧）：`git grep -nE 'ContentObserver|registerContentObserver|FileObserver|onScrollStateChanged|onScrolled|addOnScrollListener|MediaPlayer|VideoView|setDataSource|startPlay|playVideo|autoplay' 4ac6aae -- wxgroup/app/src/main/java`（应为空）；`git grep -nE 'BroadcastReceiver|registerReceiver|Handler\(|postDelayed|Timer\(|Thread\(|execSQL|ContentValues' 4ac6aae -- wxgroup/app/src/main/java`（逐条核对版本归属，均为 R25/R42 已有）。排除理由：命中项全部落在 `git diff --name-status 5358408 4ac6aae` 之外的文件，R45 改动面（`Keys.kt`/`EntryReceiver.kt`/`MomentsExport.kt`/`MomentsLogic.kt`/`SnsVideoDecProbe.kt` + 3 个选人组件）内无新增滚动触发器。**

**[反驳] 方向4 — 证据: `MomentsExport.kt:411,529,564-579` / `docs/_r48_root_cause_autoplay.md:48-62,85` — 「R45 新增运行时副作用**全在后台 daemon 线程**」措辞不严谨：`mainHandler` :411 = `Handler(Looper.getMainLooper())`，`ensureTick` :529 用 `mainHandler.postDelayed(ticker, delay)` 把 ticker **投递到主线程**；实际执行体 `runAsync()` :564-579 才起 daemon 线程（`isDaemon=true, name="moments-export"`）。⇒ 准确说法是「**调度在主线程 Handler、执行体在后台 daemon**」。这组代码 R25/R26 已有、R45 未改（另一份报告 Q2 已证 `MomentsExport` diff 只命中日志文本与注释），故**不改变裁决**，但「全在后台」不能作为排除主线程争用/时序影响的硬依据；若后台负载假设成立，主线程 ticker 与滚动的时序耦合是静态不可判的 `[假设]`。**

**[UNCERTAIN] 方向4 — 证据: `SnsGuard.kt:149-178,221-224,249,957-1016,1099-1101` / `MomentsExport.kt:779,976,1648,1754` / `SnsSqlRewrite.kt:4,65-85` / `docs/_r48_root_cause_autoplay.md:98-106` — 「`SnsSqlRewrite` / `SnsGuard` 的 hook 是否因 R45 的 `content` 读取而新增调用」：
- `SnsSqlRewrite.kt` 是**纯字符串函数**（:4「不 import 任何 Android / Xposed 类」，`shouldBlock`/`rewrite` :65-85），无 hook、无 DB 调用，且**不在** `git diff --name-status 5358408 4ac6aae`；R45 的 `snsVideoEncKey(blob)` / `protoVarint` 是对 `ByteArray` 的**只读 varint 扫描**，不经过 SQL 层、不调用 `SnsSqlRewrite` ⇒ 这一支**排除**。
- `SnsGuard` 的 hook **比另一份报告 Q4 描述的宽**：:149-171 用 `XposedBridge.hookAllMethods` 常驻挂了 `delete`(:149) / `execSQL`(:152) / `updateWithOnConflict` after(:158) + before(:163) / `insertWithOnConflict`(:168) / `insert`(:171) 共 **6 个**，:178 注释自证「**常驻**的只有 delete / execSQL / updateWithOnConflict / insert(WithOnConflict)」——是**所有表所有 DB 写**都进 hook（内部再按表名过滤），不是「只在删除/更新 SnsInfo 时触发」。Q4（:102）的表述是简化，不能当「高频路径无 hook」的证据。
- 这些 hook 是 **R42 已有、`SnsGuard.kt` 不在 R45 改动面** ⇒ 注册数量未变。R45 新增的 `SnsGuard.listCurrentSns` 调用（`MomentsExport.kt:779,976,1648,1754`）是**读**（`rawQueryMethod(db).invoke`，`SnsGuard.kt:1099-1101`），不触发写 hook；`SnsVideoDecProbe.loader()` :275 读的是已缓存 `hostLoader` 字段（`SnsGuard.kt:1046`），不新增 hook 调用 ⇒ **主线排除**。
- **但保留 `UNCERTAIN` 残留**：`SnsGuard.kt:986` 还有一个 `hookAllMethods(cls, "rawQuery", captureHook)`，:957 称「拿到句柄之前唯一多出来的钩子」、:981/:994 称「认输上限…就卸载（防常驻）」「拿到句柄即卸载」。`listCurrentSns` 通过反射 `rawQuery` 读库，若该 captureHook 在导出轮首次读时**尚未卸载**，模块自己的读也会穿过 hook。Xposed 的 `hookAllMethods` 替换方法入口，**反射 `Method.invoke` 同样会经过 hook**。⇒ 「卸载时机」与「导出轮首次读」的先后静态不可判，**不得写「完全排除」**，标 `UNCERTAIN`。另按项目纪律：`SnsVideoDecProbe` 里有 `sight_*` 正则（:175）与 `MMIMAGEENCJNI` 字符串，但 `viddec` 分支只在外部广播时跑（`EntryReceiver.kt:671-684` 的 `io{}`），**dex/源码有字符串 ≠ 该业务在生产路径调用它**。**

[DONE] 方向3-4

### 4.3 方向 5（判据设计与反向对照）+ 三档红线 + 裁决

> ⛔ **以下只设计，一律不执行**：用户 20:12「不需要验证」，本轮禁止改配置 / 装包 / 设备写操作 / 真机 / Gradle。

#### 4.3.1 共同前置（固定，保证自变量唯一）

- 同一设备、微信 8.0.48、同一段朋友圈时间线、同一批视频行、同一观察窗口长度。
- `auto_load` / `auto_refresh` / `copy_videos` / `video_decrypt` **全程保持基线值不动**。⚠️ `MomentsAuto.onTick` 条件是 `autoRefresh || autoLoad` 且两者共享同一 `lastRefreshAt` 节拍（`docs/_r48_root_cause_autoplay.md:149,157`），单动其一不总是只停一件事 ⇒ 这两项在本表内绝不作为自变量。
- **每组只改一个 key**；**改完不冷启**（冷启会清进程内计数/缓存、重挂 hook，是第二个自变量）；等 `cfg()` 的 TTL 过期 + 至少一次 tick + 在途 `runAsync()` 的 `finally` 复位后再观察。
- **sham 对照**：每组都发同样一次 `kvset`（实验组写新值、对照组写回原值），使「发 `PROBE` 广播 + 主线程 `onReceive` + 写 Kv」成为共同前置。若某组必须冷启，则**所有组一律冷启**（把冷启变成共同前置），并另列 G2 单独考察冷启。

#### 4.3.2 ⛔ 两个被点名的陷阱（先答，否则整表无效）

1. **关 `moments.auto_export` 后是否需要冷启才生效：按源码不需要。** `MomentsExport.onTick` / `pokeExport` 每轮读 `cfg()`，而 `cfg()` 有 `cfgAt` / `CFG_TTL_MS = 3_000L` 缓存（`MomentsExport.kt:195,296,491-492`，实测 `if (elapsedRealtime() - cfgAt > CFG_TTL_MS) refresh()`）⇒ 只需等 **3s TTL 过期 + 下一次 tick + 在途轮 `runAsync()` 的 `finally` 复位**（`MomentsExport.kt:533-544,607-620,564-579`）。若为了「让配置生效」顺手双冷启，冷启会清零进程内计数/缓存、重新 `install()` 挂一遍 hook、重跑 ticker（`HookEntry.kt:98-341`），**自变量从 1 个变成 2 个以上**，判据失效。⇒ 冷启只能作全组共同前置，或作为 G2 的唯一自变量。
2. **`kvset` 改配置本身会成为新的自变量：会。** `kvset` 走 `com.lx.wxgroup.action.PROBE` 广播 → `EntryReceiver.onReceive` 跑在**主线程**（`EntryReceiver.kt:85`）；写 Kv 改变 `cfg()` 读到的值、可能触发 `refresh()`；若同时改多个 key 或伴随装包/冷启，自变量不唯一。⇒ 必须 **sham 对照 + 单 key + 固定窗口 + 不冷启**。

#### 4.3.3 判据表（操作 → 预期现象 → 排除谁）

| 组 | 唯一自变量 | 操作 | 预期现象 → 排除谁 |
|---|---|---|---|
| G0 | 无（空白对照） | 只做 sham `kvset`（写回原值）+ 等同样长的窗口 | 现象仍在 = 有效基线；**若消失** ⇒ `kvset`/广播/等待本身影响现象，全表失效须重设计 |
| G1 | 模块是否参与 | 装回 `5358408`(R42) 对应包、关视频导出、同配置滚动同一批行（⛔ 装包，本轮不执行） | **基线也播** ⇒ H-A（微信原生）成立，H-B/H-C/H-D 全部判死，**硬前提「R45 引起」被证伪**；**基线不播** ⇒ H-A 判死，模块侧存活 |
| G2 | 「已挂 hook / 进程内状态」是否参与 | **只冷启微信**，不改任何配置、不装包，冷启后立刻观察同一批行 | **仍播** ⇒ 与进程内计数/已挂 hook 无关（hook 冷启会重挂而非消失），指向 H-A 或 H-B 的**持久缓存**分支；**消失** ⇒ 指向进程内状态，H-B「已挂 hook」分支存活 |
| G3 | `moments.auto_export` 开关值 | 关 `auto_export`（等 TTL + tick + 在途轮结束，不冷启），sham 写回 | **消失** ⇒ 因果依赖「当前导出轮活动」，H-C/H-D 存活、H-A/H-B 削弱；**仍在** ⇒ 排除 H-C/H-D 的当前轮，指向 H-A 或 H-B（已落盘缓存 / 已装代码） |
| G4 | 「导出轮真跑」vs「开关值」 | 清 `export_list`（`auto_export` 保持开），`autoBlockedByEmptyList` 拦自动路径 | 与 G3 **联立**：G3 消失 & G4 消失 ⇒ 自变量是「导出轮是否真跑」；G3 消失 & G4 仍在 ⇒ 自变量是 **`auto_export` 这个值本身**（须 `git grep -n 'MOMENTS_AUTO_EXPORT' 4ac6aae -- wxgroup/app/src/main/java` 复核消费点全集）；G3 仍在 & G4 仍在 ⇒ 导出轮无关 |
| G5 | 名单 / 第 4 位好友（配置态） | 名单回退到 3 人（移除第 4 位），其余不动 | **只在该好友视频行消失、其余行仍在** ⇒ H-D 成立且现象是**局部**的；**全时间线任意视频行普遍起播** ⇒ H-A，与名单无关 |
| G6 | 解密层 / 宿主 native 反射 | 关 `video_decrypt`（`copyVideosForRow` 把 `encKey` 置 0 ⇒ 只停 `decryptInPlace`；下载/rename/落盘/计数全不变，`docs/_r44_chain.md:931-948` 真机已证自变量唯一） | **消失** ⇒ 指向 R45 特有的 `MMIMAGEENCJNI` 反射（方向 3 的 native 全局态 UNCERTAIN 坐实）；**仍在** ⇒ 排除解密层，H-C 只剩「后台 IO/网络负载」假设 |
| G7 | 微信持久 `sight_*` 缓存 | 清 `cache/<hash>/sns/` 下 `sight_*`（⛔ 设备写操作，本轮不执行），**不改模块任何配置** | **消失** ⇒ H-B（持久缓存）/ H-A 的本地文件条件坐实；**仍在** ⇒ 「播放器起播判据 = 本地 `sight_*` 存在」被证伪，另一份报告 Q3/Q5 的排除依据彻底失效 |
| G8 | 无（纯观测） | 只记录现象**分布**：是否集中在名单好友 / 已导出 `vid_*` 对应行 / 用户「看过的」那 5 个 `sight_*` 对应行 | **局部集中** ⇒ H-B/H-C/H-D；**普遍出现** ⇒ H-A。这是分辨 H-B 与 H-A 最廉价、自变量为 0 的一手判据 |

> 四者对应关系：H-A = 微信原生滚动起播（现象一直存在）；H-B = R44 Route C 探针 / 已落盘持久 `sight_*` 缓存（已装代码 + 持久文件）；H-C = R45 导出轮后台负载（由配置态激活）；H-D = 配置态变化（开关值 / 名单）。G1 判 H-A 与「模块是否有关」；G2+G7+G8 判 H-B；G3+G4+G6 判 H-C/H-D；G5 判 H-D 的局部性。**任何「A 推 B」的时序结论均为 `[假设]`，须日志时间戳对齐，不得写成定案。**

#### 4.3.4 三档红线逐档自检（⛔ 缺一档算失败）

**进入** — 打开朋友圈时间线：`MomentsAuto.registerLifecycle.onActivityResumed` 重置 `sessionCount=0`/`timelineActive=true`（`docs/_r48_root_cause_autoplay.md:175`），`MomentsExport` **无进入钩子**；首个视频动态进入可视区：`onBind`(:446-499) 不识别 item 是否视频（无 `mediaType`/`type==15` 判断）⇒ R45 无「视频进入可视区」入口；刚加入导出名单后的第一次滚动：`export_list` 变化由 `MomentsExport.refresh()/cfg()` 在**下次 tick** 才读到（有 `cfgAt` TTL），不立即触发导出 ⇒ 与滚动无同步因果。⚠️ memory:152-155 记第 4 位好友视频 17:10/17:47 才落盘，属**返回/异步档**，不是进入档。⇒ 进入档**无 R45 新增副作用被触发**。

**返回** — 滚到底「加载更多」后新一批上屏：`onBind` 的 `grow` 响应但 R45 未改，新动态要等下一轮导出才下载；切好友相册/详情页再回时间线：`onActivityResumed` 重置 `sessionCount`/`lastRv`，R45 无相关钩子；后台 `x2(256)` 同步成功后刷新并 `pokeExport()`（`MomentsAuto.kt:343`）——**这是 R45 唯一与返回档有交集的触发点**，但导出轮与「列表刷新 / 视频起播」之间无源码级连接，只能作 H-C 的后台负载 `[假设]`；`pokeExport` 之后导出轮在 daemon 线程单航班跑；**R46 装包双冷启之后**（memory:139，`lastUpdateTime → 16:28:33`）：`install()` 重挂 hook、重装 ticker、`video_decrypt` 默认 true 生效，且 16:28 之后才有第 4 位好友的 MP4 落盘（17:10/17:47）⇒ 返回档与 C2/C3 竞争解释直接耦合。

**复位（逐项点名，不漏）** — ① 滚动停止 `SCROLL_STATE_IDLE`：模块全树零命中 `onScrollStateChanged`/`onScrolled`/`addOnScrollListener` ⇒ **不感知滚动停止，无任何复位动作**；② 视频行滑出屏幕：无 view 生命周期钩子 ⇒ 无复位；③ 退出朋友圈页：`onActivityPaused` 只置 `timelineActive=false`、`onActivityDestroyed` 空实现（`docs/_r48_root_cause_autoplay.md:191`）⇒ **R45 无页面级复位**，导出轮 daemon 线程不因退出页面停止；④ 关掉 R45 相关任一开关：`onTick`/`pokeExport` 的 early return 会停**新**导出轮（但需等 `cfg()` TTL），**在途轮不中断**（`runAsync()` 单航班 `running` CAS + `finally` 复位，可达 120s×N 条）、已下载 `vid_*.mp4` 不清理、`x2(256)` 已投递的同步与 `MOMENTS_LAST_*` / `video_fill_cursor` 游标不回滚、**已挂 hook 不卸载**、进程内计数不清零、微信 `sight_*` 缓存与 `libwechatcommon.so` 与开关无关；⑤ 冷启微信：清的是**进程内计数/缓存**并**重新 `install()` 挂一遍 hook（重挂，不是消失）**，**Kv 持久状态、已落盘文件、微信 `sight_*` 缓存不随冷启清零** ⇒ 别把「冷启复位」误当「全部复位」。
- ⭐ **特别回答（用户点名，分辨「配置态」与「已装代码」的关键，不得略过）**：**若根因是「已挂 hook」或「已下载/已落盘缓存」，关开关后现象不应立刻消失。** hook 在 `install()` 时挂一次、关开关不触发 `unhook()`（只有 `VidTrigProbe.command("off")` 或进程冷启才卸载/重挂）；`sight_*` 明文缓存是持久文件，既不随 Kv 开关变化、也不随模块进程冷启删除（`SnsDispatchProbe.kt:155-157` 的 `cacheHit` 短路说明缓存一旦存在即长期有效）。⇒ **若关开关后同一滚动会话内立刻消失** ⇒ 因果更可能在**配置态驱动的当前轮活动**（H-C/H-D），而非已挂 hook / 持久缓存（H-B）；**若关开关后仍在，且冷启微信后仍在** ⇒ 指向 H-B（持久缓存）或 H-A（微信原生），与 R45 开关无关；**若必须「关开关 + 冷启 + 清 `sight_*`」才消失** ⇒ H-B 坐实。⚠️ 本项目踩过「只写进入/返回、漏掉复位」的坑：`.workbuddy/memory/2026-11-08.md:165` 记 R47 复核点名「三档边界：进入 PASS、返回 PASS、**复位整档未覆盖**」；另一份报告的复位结论（:188-194）仍是纯静态推演，无任何观测。

---

## 5. 对另一份只读报告结论的裁决：**部分反驳**

| 条目 | 裁决 | 具体理由 |
|---|---|---|
| 硬前提「R45 引起」直接采信（:4） | **反驳** | 时间相关性判断，无首次出现时点；11-07 22:41 → 11-08 20:12 内 R44/R45/R46/R47 事件密集；至少 4 个竞争解释（方向 1） |
| Q1⑨「R44 无运行时副作用 / 移出编译单元」+ Q1「R45 全部副作用清单」（:44-62,:56） | **反驳（结构性）** | R44 探针是 `??` 未跟踪、在 `app/src/main/` 编译单元内、有 HookEntry/EntryReceiver 接线、上过真机（`docs/_r44_chain.md:996-1010`、memory:114-115）；`git diff` 对其失明 ⇒ 清单不完整 |
| Q3「路径不相交，排除」（:88-96） | **反驳 / 排除过度** | 漏 `SnsDispatchProbe.snsRoots()` 的 `cache/<hash>/sns/` 与 `SnsVideoDecProbe.key()` 读 `sight_*`；且用自认「静态不可判」（:206）的播放器判据做排除，自相矛盾 |
| Q5「两条互不相干的链 / 不改变微信侧任何状态」（:110-120） | **反驳 / 排除过度** | Q1⑪（:58）已标 native 全局态 UNCERTAIN，Q5 收成定案；KDoc「实例方法」与 memory:46「static native」互斥；`free` 真配对但 `freeHandle` 吞异常 + `h<0` 不 free |
| Q4「SnsGuard 只在删除/更新 SnsInfo 时触发」（:98-106） | **部分反驳（结论维持、理由更正）** | `SnsGuard.kt:149-178` 实为 6 个常驻 `hookAllMethods` 覆盖所有 DB 写 + 1 个一次性 `rawQuery` captureHook(:986)；但该文件不在 R45 改动面、R45 新增读不触发写 hook ⇒ 结论可维持；`listCurrentSns` 反射 `rawQuery` 与 captureHook 卸载时序静态不可判 ⇒ 保留 UNCERTAIN |
| Q2「`auto_export` 不改变同步/滚动频率」（:70-86）、前置事实 5「模块无播放 API/滚动监听器」（:29） | **同意** | 独立复核：`MomentsAuto.kt` 不在改动面；全树 grep 零命中播放/滚动 API；仅把「全在 daemon」更正为「调度在主线程 Handler（:411,:529）、执行体在 daemon」 |
| Q7 判据设计（:147-170） | **部分反驳** | ① 「每组改完双冷启」把冷启与配置更改捆绑，未做 `kvset` sham 对照、未答「关开关是否需冷启」（按源码不需）；② 没有一组专门考察「已挂 hook / 已落盘持久缓存」在关开关后是否立刻消失（:169 反把「立刻消失」判成反预期，未与 H-B 持久缓存挂钩）；③ 无 R42 基线对照作为「判死硬前提」的决定性一组，仅列为「最小补充证据」第 5 项 |
| 总体结论「证据不足 / 有主候选但未定案」（:200-201） | **部分反驳** | 「证据不足」方向同意，但成因不止「配置态不可见」（:204），还有更前置的**设备装包态 ≠ git 提交态**；且主候选排序须重排（见下） |

## 6. 最可能根因排序（含置信度）

1. **H-A 微信 8.0.48 原生滚动起播（现象一直存在，R45 只是让用户开始留意）— 中（≈0.45）**。唯一不需要任何未证因果链的解释：R44 终局已有 5 个明文 `sight_*` 缓存（`docs/_r44_chain.md:423`、memory:30）、`auto_load=true` 自 R25 起制造滚动（`Keys.kt:486`）、模块全树零播放 API。缺 R42 基线对照。
2. **H-D 配置态变化（第 4 位名单好友加入 / 打开 `auto_export` / `video_decrypt=true` 默认生效）— 中低（≈0.25）**。默认态下 R45 副作用不自动发生（`Keys.kt:485-516`）；名单 04:27 是 3 人、20:12 是 4 人（memory:69,152-155）；第 4 位好友视频 17:10/17:47 才落盘（16:28 冷启之后）；Kv 全量快照从未取（memory:166-168）。
3. **H-B R44 探针 / 持久 `sight_*` 缓存（已装代码 + 已落盘缓存，不随关开关消失）— 低（≈0.15）**。探针 `??` 未跟踪却上过真机且 `snsRoots()` 指向微信缓存（`_r44_chain.md:996-1010`）；但真机三轮不落盘（:393-395）是硬反证。可证伪：现象是否局部集中 + 冷启/清缓存后是否仍在。
4. **H-C R45 导出轮后台负载间接改变滚动时序 — 低（≈0.10）**。另一份报告的主候选，但源码里找不到「滚动 ↔ 起播」任何连接点，且需先假设配置态已激活；静态零证据，纯 `[假设]`。
5. **C2 R46 装包双冷启 / UI 改动 — 极低（≈0.05）**。UI 改动在选人组件、不在朋友圈时间线；仅双冷启带来的状态重置与时序耦合不可排除。

## 7. 最薄弱环节

1. **硬前提本身**：用户「R45 引起」无首次出现时点记录；R42 基线(11-07 22:41) → R45(04:11) → R46 收口(04:4x) → 双冷启(16:28:33) → R47(20:12) 全挤在约 22 小时内，时间相关性无法定位自变量。
2. **设备装包态 ≠ git 提交态（最前置）**：R44 探针是未跟踪文件、上过真机、R46 才归档摘线 ⇒ `git diff 5358408 4ac6aae` 结构性漏项，比「Kv 不可见」更早发生。
3. **配置态全量快照缺失**：`auto_export`/`copy_videos`/`export_list`/`video_decrypt`/`auto_load`/`auto_refresh` 当前值从未取；第 4 位好友加入时点无记录（`[假设]` 介于 04:27 与 20:12 之间）。
4. **微信侧黑盒**：播放器起播判据、`MMIMAGEENCJNI` native 是实例态还是静态全局、`sight_*` 建立时机；`SnsVideoDecProbe.kt:32-33` 与 memory:46 对 `transFor` 是否 static 互斥，静态不可判。
5. **复位档从无观测**：R47 复核已点名「复位整档未覆盖」（memory:165）；「关开关后是否立刻消失」这一分辨「配置态 vs 已装代码/持久缓存」的关键判据，至今只有静态推演。

## 8. 最小补充证据（只列命令与判据，⛔ 本轮一律不执行）

静态可复算（本轮已做，列出供复核）：
- `git log --all --oneline --full-history -- '*SnsDispatchProbe.kt' '*VidTrigProbe.kt'` — 判据：若出现 R44/R45 期提交 ⇒ 探针进过 git，差分须重算；若仍只有 `4ac6aae` ⇒ 确认为未跟踪装包态盲点（本轮结果：仅 `4ac6aae`）。
- `git diff --name-status 5358408 4ac6aae` / `git diff 5358408 4ac6aae -- wxgroup/app/src/main/java/com/lx/wxgroup/HookEntry.kt`（应为空） / `git show 4ac6aae:…/HookEntry.kt | grep -nE 'SnsDispatch|VidTrig|prepare'`（应为空） — 判据：确认「摘线后 diff 归零」。
- `git grep -nE 'MOMENTS_AUTO_EXPORT|autoExport|CFG_TTL_MS|cfgAt' 4ac6aae -- wxgroup/app/src/main/java` — 判据：确认 `auto_export` 消费点全集与 `cfg()` TTL，静态回答「关开关是否需冷启」。
- `git grep -nE 'MMIMAGEENCJNI|transFor|static.*native' 4ac6aae -- wxgroup/app/src/main/java` + `grep -n 'static native\|实例方法' .workbuddy/memory/2026-11-08.md wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsVideoDecProbe.kt` — 判据：解 KDoc 与 memory 的 static/实例互斥。
- `git grep -nE 'ContentObserver|registerContentObserver|FileObserver|onScrollStateChanged|onScrolled|addOnScrollListener|MediaPlayer|VideoView|setDataSource|startPlay|playVideo|autoplay' 4ac6aae -- wxgroup/app/src/main/java`（应为空） — 判据：确认 R45 无滚动期触发器。

需真机/日志（⛔ 本轮禁止，仅列判据）：
- `kvget` 全量快照 — 判据：`auto_export=false` 或 `copy_videos=false` ⇒ H-C 判死、硬前提须重述为「配置变更」；两者皆 true 且名单非空 ⇒ H-C 存活。
- `logclear` → 复现滚动 → 拉 LogX 环形日志，对齐 `MOMENT refresh bg-sent` / `MOMENT export` / `VIDDL` / `VIDDEC inplace` / `MOMENT vid copied` 时间戳 — 判据：起播时刻与上述日志无稳定先后 ⇒ H-C 判死。
- 按 4.3.3 表做自变量唯一反向对照（G0–G8）— 判据见表；⛔ 其中 G1（装 R42 基线包）、G7（清微信 `sight_*` 缓存）涉及装包/设备写，本轮禁止。
- 微信播放器侧 hook / 文件访问日志 — 判据：若微信从未打开 `/sdcard/Documents/WXGroup/moments/.../vid_*.mp4` ⇒ 路径核对得终局确认；若打开 ⇒ 重开 Q5。

## 9. 三档红线逐档自检结论

| 档 | 自检结论 |
|---|---|
| 进入 | **无 R45 新增副作用被触发**；`onBind` 不识别视频行；「刚加名单第一次滚动」与导出无同步因果（导出要等下次 tick）。若进入即现 ⇒ 指向 H-A 或 R25 `onBind`，不指向 R45。 |
| 返回 | 唯一 R45 交集是「`x2(256)` 成功 → `pokeExport` → 导出轮」，但无源码级「起播」连接，仅 H-C 的后台负载 `[假设]`；R46 双冷启(16:28:33) 与第 4 位好友 MP4(17:10/17:47) 落盘都落在返回档，与 C2/C3 竞争解释耦合。 |
| 复位 | **模块无滚动级 / 页面级复位**；关开关只停新导出轮，在途轮、已落盘 `vid_*`、Kv 游标、已挂 hook、进程内计数、微信 `sight_*` 缓存全部残留；冷启只清进程内状态且**重挂** hook，不删持久缓存。**若根因是已挂 hook 或已下载缓存，关开关后现象不应立刻消失** —— 这一档是分辨「配置态（H-C/H-D）」与「已装代码 / 持久缓存（H-B）」的关键，且至今零观测（memory:165 已点名同类缺口），不得略过。 |

[DONE] 方向5-红线-裁决
[DONE]

