# R43 · 朋友圈视频导出 —— 实现落点与三红线确认（2026-11-07）

> 上游取证：`docs/_r43_video_probe.md`。本文档记录**代码怎么落** + 主会话真机实测的三轮结论。
> 判据一句带过：`SnsInfo.type == 15` = 朋友圈原生视频（真机 24517 行，99.98% 含
> `snsvideodownload` URL）；真视频域名是 `snsvideodownload`，**不是** `stodownload`
> （后者是 type=28 视频号分享）。

## 一、真机实测结论（写死，不再当「未定论」）

### 1. 下载成败的**唯一决定因素是 User-Agent**（三轮矩阵，2026-11-07）

| UA 值 | 结果 |
|---|---|
| `WeChat` / `MicroMessenger` / `WeChat/arm64` | **200** 9,614,961 |
| `wechat`（小写）、`Weixin` | 400 |
| `Mozilla/5.0` / `okhttp/4.9.0` / `Dalvik/...` / `Android` / `curl/8.0` | 400 |
| 空（不发 UA 头） | 200（**服务端特例，不可依赖**） |

⇒ 判据：**UA 含大小写敏感的 `WeChat` 或 `MicroMessenger`，缺则恒 400**；版本号/机型/Chrome
版本**都不参与校验**（`MicroMessenger/8.0.48.2580 ... SM-G991B ...` 那种随微信升级过期）。
Referer 加与不加**结果相同**。
⇒ 实现：`VID_UA = "WeChat/arm64"`（不含会过期的字段），`VID_REFERER = ""`；
空 UA 时**不发请求**、直接记 `noUa`（因为「空 UA 也 200」不可依赖）。

### 2. `/20202/` 是视频主源，`/20250/` 是封面图（不是备用源）

| 路径 | + UA 结果 |
|---|---|
| `/20202/` | 200, `video/mp4`, 9,614,961 |
| `/20250/` | 200, **`image/jpg`, 32,312**（封面图） |

⇒ 视频**只取 `/20202/`**；`20250` **不回退、不下载**（下回来是封面 jpg，还会让主源真实失败被
一次无意义的「成功」掩盖）。封面默认不做（`copy_videos` 只管视频）。见 `videoUrlCandidates`。

### 3. token 有效期落在 7~30 天之间（体积量级同测）

| 动态年龄 | 结果 |
|---|---|
| 1.4h | 200, 9.6 MB |
| 1d | 200, 1.0 MB |
| 3d | 200, 17 MB |
| 7d | 200, 77.8 MB（curl 30s 只收 31MB 超时） |
| 30d | **400 size=0** |

⇒ 早于有效期（≈30 天）就下不回来，属**永久失败**，不回退不重试；7 天内的历史动态仍可补 ⇒
回填轮有实际价值。体积 1MB~77.8MB ⇒ 单文件上限给 **200MB**（覆盖实测并留余量）。

## 二、改动点清单

### `data/Keys.kt`
- 新增开关 `Keys.MOMENTS_COPY_VIDEOS = "moments.copy_videos"`（默认 false，进 `Prefs.ALL`
  朋友圈域「导出视频」，与「导出图片」并列）。
- 新增内部状态键 `Keys.MOMENTS_VIDEO_FILL_CURSOR`（不进 `Prefs.ALL`，纯运行状态）。

### `feature/MomentsLogic.kt`
- `SNS_TYPE_VIDEO = 15` + `isSnsVideo(type)`（权威判据）。
- 订正 `VIDEO_MARKS = listOf("snsvideodownload", "stodownload")`（补真视频域名）。
- `videoUrlCandidates`：只留 `snsvideodownload` 且路径段是 `/20202/` 的（封面 20250 丢弃）。
- `snsVideoUrls(blob)` = `videoUrlCandidates(extractUrls(blob))`。

### `feature/MomentsExport.kt`
- `Config.copyVideos` + `refresh()` 读开关。
- 常量：`VID_UA="WeChat/arm64"`（非 const，留 `isEmpty()` 运行时判定）、`VID_REFERER=""`、
  `MAX_VIDEO_BYTES=200MB`、`DL_CONNECT_MS=10s`（连不上快速失败）、`DL_READ_MS=120s`。
- 独立计数器 11 个（`vidNotVideo`/`vidNoUrl`/`vidNoUa`/`vidHttp4xx`/`vidHttp5xx`/
  `vidTooLarge`/`vidWrongType`/`vidIoFail`/`vidSpace`/`vidCopied`/`vidAlready`），
  **与图片四态零共享**。
- `downloadVideo(url, dst): VidResult`（tmp+rename，失败删 tmp；200 但 CT 非 video → `wrongType`；
  4xx→`http4xx`（永久）、5xx→`http5xx`（临时））。
- `copyVideosForRow(row, dir, detail, st): VidResult`（生产 + `viddl` 探针共用；六条进入边界短路）。
- `countVid`（唯一收口点，覆盖 11 个 `VidOutcome`，无静默 else）。
- `backfillVideos(root)`（独立回填轮：独立游标、复用单航班/空间闸门）。
- `probe(sub, n, id)` 增 `viddl` 子命令 + `findSnsById` 有界查找（`VDDL_SCAN_MAX_ROWS`）。

### `entry/EntryReceiver.kt`
- `moments` 分派多读一个 `--el id`（`getLongExtra`），透传给 `MomentsExport.probe(sub, n, id)`。

### `feature/MomentsLogicTest.kt`
- 5 条新单测（`isSnsVideo` / `kindOfUrl` 认 `snsvideodownload` / `videoUrlCandidates` 只留 20202 /
  无 20202 空 / `snsVideoUrls` 抽 20202）。

## 三、三条红线（书面确认）

### 红线 1 · 进入边界（**同时满足才下载**）
`copyVideosForRow` 按序短路，缺一即 return：
1. 调用方闸 `c.copyVideos == true`（主循环 / 回填两处调用前判断）；
2. `MomentsLogic.isSnsVideo(row.type)`（`type == 15`）；
3. `snsVideoUrls(row.content)` 非空（含 `/20202/` 视频 URL）；
4. UA 非空（`VID_UA` 空 → `noUa`，不发请求）；
5. 目标文件不存在/为空（`imgDone` 短路）；
6. `dir.usableSpace >= MIN_FREE_BYTES`（落盘前判，不足不写一字节）。

### 红线 2 · 返回边界（**这些情况绝不下载、绝不发网络**）
- `copy_videos == false` → 两处调用点前置 `if`，一次网络不发、一个字节不写；
- `type != 15` → `vidNotVideo`；
- URL 缺失（无 `/20202/`）→ `vidNoUrl`（20250 封面被 `videoUrlCandidates` 丢弃，也归此类）；
- UA 空 → `vidNoUa`；
- 空间不足 → `vidSpace`；
- 目标已存在非空 → `vidAlready`；
- HTTP 4xx（token 过期/永久）→ `vidHttp4xx`，5xx（临时）→ `vidHttp5xx`；
- 超过 200MB → `vidTooLarge`；
- 200 但 CT 非 video（封面/HTML/tar）→ `vidWrongType`；
- 连接/读/写/rename 异常 → `vidIoFail`，tmp 必删。

### 红线 3 · 复位边界（新增状态**不污染**既有链路、**不跨条串味**）
- 视频 11 个计数器全新一套，与图片四态零共享，`countVid` 只动 `vid*`。
- 上一次失败不影响下一条：每条动态的判定是局部变量，无「上次失败」共享 flag；
  游标 `cur.advanceTo` 在 `continue` 之前无条件推进（与图片同规矩）。
- 图片/视频回填游标各自独立，开/关互不干扰。

## 四、六个问题的结论

### Q1 类型判定放哪层
`row.type == 15` 是权威（决定「有没有朋友圈视频」）；`kindOfUrl`/`VIDEO_MARKS` 只用于
「URL 是不是视频地址」，参与 `videoUrlCandidates` 过滤，**不参与**「要不要下」判定。

### Q2 主源选择
只有 `/20202/` 一个候选（`videoUrlCandidates` 已过滤掉 20250 封面）；无回退。每 snsId 每轮
最多 1 次下载，无重试循环（token 过期 = 永久失败）。跨轮由 `MAX_BACKFILL_PER_RUN` + 独立游标
轮转兜底，不会对历史动态轮番轰炸。

### Q3 回填轮怎么覆盖视频
另开 `backfillVideos`（数据源/判据/计数口径/游标都不同）；共用空间闸门 + 单航班 CAS。

### Q4 失败分类
11 类（见红线 1/2），每类计数器 + 明细日志（`detail` 时）；`stat()` 全量可见。视频失败不弹窗
（400 是 30 天以上老动态的常态）。

### Q5 安全边界
- 上限 `MAX_VIDEO_BYTES = 200MB`（实测 1/9.6/17/77.8MB）。
- 落盘前 `usableSpace` 检查 + 流式累计字节数超限中断 + 删 tmp。
- tmp + rename 原子落盘，rename 失败/异常删 tmp。
- 开关关闭 = 零网络零写入。

### Q6 Referer / UA
UA 是决定因素（已测死），`VID_UA="WeChat/arm64"`；Referer 无影响，留 `""`。空 UA 不依赖，记 `noUa`。

## 五、真机验证方法（样本已由主会话查好）

1. 开开关：`kvset moments.copy_videos true`（或设置页「朋友圈 → 导出视频」）。
2. **单条探针**（推荐，避免整轮导出写上千文件）：
   ```
   adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
     --es op moments --es sub viddl --el id -3398536920689724692
   ```
   看日志：`VIDDL id=... gate=bypass type=15 hit=true url=有 outcome=copied bytes=<n> file=<path> ms=<ms>`。
3. 反向对照样本：
   | 好友 | 视频数 | 最新视频 snsId | 年龄 | 预期 |
   |---|---|---|---|---|
   | `wxid_xij8e08lqr3e22` | 114 | `-3398536920689724692` | 49.3h（token 有效） | `copied` 落盘 mp4 |
   | `wxid_l2ocidx1m40021` | 3 | `-3411766190398565717` | 487h（20 天） | 预计 `http4xx` |
   | `wxid_faryuijzyxj022` | 31 | `-3497392618428222810` | 3322h（138 天） | 必然 `http4xx` |

   用第 1 条（应 200 落盘 mp4）+ 第 3 条（应 `http4xx`）各跑一次做反向对照。
4. `op=moments sub=estat` 看 `vidCopied`/`vidHttp4xx`/`vidNoUrl` 计数与 `vidFillCursor` 是否推进；
   `sub=vidfill` 打印一行 `MOMENT vid backfill ...` 聚合。

### 已知边界（非阻塞）
- 30 天以上的动态 token 必过期 → `vidHttp4xx`（永久失败，不回退不重试），属预期。
- 77MB 级长视频弱网下可能超 `DL_READ_MS=120s` → `ioFail`（临时，游标轮转后下轮再试）。
