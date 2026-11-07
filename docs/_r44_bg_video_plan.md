# R44 · 全后台无感知获取朋友圈视频 —— 技术方案与静态取证（2026-11-08）

> 目标：配合「定时刷新朋友圈」+「自动加载朋友圈」，**全后台无感知**（不拉起界面、不闪屏）地
> 保存特定好友的动态（文字+图片+视频）。本文只出方案与取证结论，不改 `app/src/main` 一行。
>
> 上游结论（R43，直接引用不再重做）：
> - 缓存里的 `sight_*&enc*` 是**明文完整 MP4**（§七），`&enc` 非加密标志；
> - 后台 `msync sync` 不下媒体（§8.1）；滚动只下图片不下视频（§8.2）；根因 = `wifi_on=0` 触发
>   「仅 WiFi 自动播放」→ 不播 → 不下（§8.3）；
> - 裸 HTTP 直连 CDN 拿到的是**密文**（§六），解密在 native 层。

---

## 一、Q1 · `d1#s`（isAutoDownload）返回值语义 [DONE]

**锚点**：`Lcom/tencent/mm/plugin/sns/model/d1;` = `LazyerImageLoader2`。
`#s` 签名 `(Lcom/tencent/mm/plugin/sns/storage/SnsInfo;[I)I`，`[I` 是 3 元 out-param（reason 码）。

### 结论（1-3 行）

1. 返回值是**多义 int**：全局「简单判定」存在字段 `d1->o`，**`o=5` = 允许自动下载**（日志
   `'skip all not auto download case, then auto download.'`），**`o=10` = 拒绝**；另有字段 `d1->p`
   存「拒绝/忙时」细分（`0/1/2`），以及 per-snsId 的 `HashMap q` 缓存最终 verdict。
2. **所有拒绝分支最终都落到 `o=10`**；「允许」唯一出口落 `o=5`。
3. ⚠️ 调用方（`bf3/t0#y0` 渲染器、`SnsCommentDetailUI#m7` 等）调用 `d1#r`（=`s` 的包装）时
   **丢弃返回值**，仅作「预热/缓存」副作用 —— 所以「从调用方 if 判定反推」这条路走不通；真正的
   消费点在视频播放组件内部（读 `d1` 的缓存字段），但**对 hook 而言，只需把 `d1#s` 整体改成
   返回「允许」即可，不必关心调用方怎么判**。

### 拒绝原因 → 返回值映射表（反汇编 `d1#s` 直接读得）

| 拒绝原因（日志原文） | 触发条件 | 落点 |
|---|---|---|
| `user settings can not auto download SNS short video` | 动态配置 `SIGHTAutoLoadNetwork == 3`（用户设为「不自动播放」） | `o=10` |
| `it is not wifi now, and status_only_wifi, not auto download SNS short video.` | `SIGHTAutoLoadNetwork == 2`（仅 WiFi）且当前非 WiFi | `o=10` |
| `it is 2G now, can not auto download SNS short video.` | `s4->n()` 判 2G | `o=10` |
| `config settings can not auto download SNS short video` | `SnsSightNoAutoDownload` 时间区间命中（`yyyyMMddHHmmss` 起止段） | `o=10` |
| `it is busy time now, can not auto download(but need check again) SNS short video` | `SnsSightNotAutoDownloadTimeRange` busy time | `p=1`（`v6!=2`）或 `p=2`（`v6==2`），`o=10` |
| `skip all not auto download case, then auto download.` | 全部检查通过 | **`o=5`（允许）** |

另有 per-mediaId 的 XML 级二次判定 `d1#A`（`parseInfoXml`），读该媒体自带
`SightDownloadControl.G3PlusAutoDownload` / `.WifiAutoDownload` 开关（默认放行）。

### 关键证据（3-10 行）

```
0x006d  if-ne v6, v11, +10          ; v6=SIGHTAutoLoadNetwork 值，v11=3
0x006f  const-string 'user settings can not auto download SNS short video'
0x007d  if-ne v6, v10, +11          ; v10=2（仅WiFi）
0x0081  const-string 'it is not wifi now, and status_only_wifi, ...'
0x008e  if-eqz v9, +9               ; is2G
0x0090  const-string 'it is 2G now, ...'
0x0160  if-eqz v0, +17              ; busy time 判否 → 跳 0x0171
0x0171  const-string 'skip all not auto download case, then auto download.'
0x017b  iput v6, v1, d1;->o:I       ; o = 5（0x017a const/4 v6,5）
0x0183  iput v7, v1, d1;->o:I       ; o = 10（0x0181 const/16 v7,10）
```

---

## 二、Q2 · `j2` 能否被程序化主动触发 [DONE]

### 结论（⛔ 如实说明：它**不是**下载请求）

**`j2` = `NetSceneSnsComment`（发朋友圈评论的 CGI），不是视频下载请求，不能用于触发下载。**

- 构造器 `j2#<init>(Lq54/f95;Ljava/lang/String;I)` 里写死了
  `'/cgi-bin/micromsg-bin/mmsnscomment'`、cmd `213`，类是 `MicroMsg.NetSceneSnsComment`。
- `doScene` 里的 `'doScene(sight_autodownload) snsStatExt:%s'` **只是上报统计扩展**：
  `preloadLayerId=%d&preloadExpId=%d` 被塞进评论请求的 `r95.f`（`kx4`）。`sight_autodownload`
  是「这次评论发生在某条 sight 自动下载上下文中」的**统计标签**，不是下载动作。
- 构造参数语义：`q54/f95` = SNS 对象（字段 `d:J` 是 snsId、`f:q54/e95` 是 object desc）；
  `String` = 评论文本；`int` = 场景 type。三者都在组装 `r95`（评论请求体），与媒体下载无关。

### 关键证据

```
0x0016  const-string '/cgi-bin/micromsg-bin/mmsnscomment'
0x001a  const/16 v1, 213           ; iput j->d（CGI cmd id）
0x005e  const-string 'preloadLayerId=%d&preloadExpId=%d'
0x007b  const-string 'doScene(sight_autodownload) snsStatExt:%s'   ; 纯日志+统计
```

⇒ 团队锚点 #2 是**红鲱**。视频下载的真正入口在 §三。

---

## 三、Q3 · 视频下载完整下游链路 + aeskey 来源 [DONE]

### 3.1 链路（Java 层，全走 native 解密）

```
Lid3/f0（ensureDownload，Flow/协程，调 getSnsSightName 判文件是否存在→postDelayed 调度）
  └─> CDN 传输引擎 ok0 包
        └─> Lok0/j#invoke：把 Ltj/k（下载任务）字段 → Lok0/x0#r 形参
              └─> Lok0/x0#r（startURLDownload，log 'MicroMsg.CdnTransportEngine'）
                    构造 CdnLogic$C2CDownloadRequest{fileKey,url,aeskey,savePath,fileType,customHeader}
                    └─> CdnLogic.startSNSDownload(req, null, null, 0)   ; native
```

`CdnLogic.startSNSDownload` 签名：
`(C2CDownloadRequest, VideoStreamingCallback, DownloadCallback, int)I`
（`Lcom/tencent/mars/cdn/CdnLogic;`，classes13.dex；native，字符串 `startSNSDownload` 在池内但无
const-string 引用 = JNI 注册名）。

### 3.2 url / fileKey / aeskey 来源（`Lok0/j#invoke` 直读 `Ltj/k` 字段）

```
0x0034  iget v5, tj/k->field_mediaId   ; → mediaid
0x0036  iget v6, tj/k->u               ; → url
0x0038  iget v7, tj/k->field_fullpath  ; → 落盘全路径
0x003a  iget v8, tj/k->field_fileType  ; → fileType
0x003c  iget v9, tj/k->field_aesKey    ; → aeskey
0x003e  iget v10, tj/k->field_authKey  ; → authKey
0x0040  iget v11, tj/k->field_totalLen ; → totalLen
0x0043  invoke-virtual/range x0->r(...)
```

而 `tj/k` 的这些字段由上游从 SNS 媒体 protobuf **`Lq54/vm3`** 填入。
`vm3` 就是 `SnsInfo.content → SnsObject.ObjectOperations → media 项` 的结构（字段名被混淆为单字母）：
`d:String`=mediaId、`D:I`=flag、`E:J`=&enc 后缀值、`F:String`=urlMd5，其余 String 字段（B/C/G/I/J/L/M/P/Q/U/f/g/i/o/q/u/w/x/y/z）中藏着 url/fileKey/aeskey。

### 3.3 ⛔ aeskey 能否从 URL 参数推导：**不能**

- `aeskey` 是 `vm3`（媒体 protobuf）的**独立 String 字段**，不是 URL 的 `encfilekey` / `token`。
- `encfilekey` 只是 fileKey 的编码/别名；`token` 是鉴权时效参数。裸 HTTP（R43 §六）只能下到密文，
  正是因为没有 aeskey。
- ⇒ **C1 路线（自己下载 + 借 native 解密）可行但前提是：解析 vm3 拿到 aeskey**，再构造
  `C2CDownloadRequest` 调 `startSNSDownload`（或经 `ok0/s2->c9()` 单例的 `startURLDownload`）。

### 3.4 对 Q3 的结论

- 「谁最终走到 native」：`Lok0/x0#r` → `CdnLogic.startSNSDownload`（native）。
- url/fileKey/aeskey 全部来自 SNS 媒体 protobuf `q54/vm3`（经 `tj/k` 中转）。
- aeskey **不可**从 URL 参数推导，必须解析 protobuf。这是 Route C 的**唯一硬门槛**。

---

## 四、Q4 · `bf3/t0#S` 路径生成 + 分桶算法 [DONE]

### 4.1 `sight_` 文件名生成

`bf3/t0#S` = `SnsUtil.getSnsSightName(Lq54/vm3)`（classes3.dex）：

```
name = SnsUtil.s(vm3.d)                 ; s = fliterPre：去 mediaId 前导 '0'
if vm3.D == 1:  name += SnsUtil.f0(vm3) ; f0 = getUrlMd5（vm3.F 非空则用之，否则 md5(protobuf 字节)）
name = tl0/c#a(name, vm3.E)            ; += "&enc" + (vm3.E 或 314159265)
```

`tl0/c#a(String,long)`（classes2.dex）：`name + String.format("&enc%d", (long==0?314159265:long))`。
⇒ **`&enc<n>` 就是字面 "&enc" + 一个 long 字段值，默认占位 314159265，不是加密标志**（印证 R43 §七）。
`sight_` 前缀由调用方 `y5#a`（`buildCdnSightDownloadFileName`）在拼接完整路径时加上（`sight_` 字符串
在池内 id 76850，但全 dex 无 const-string 直接引用 = 经 StringBuilder 拼装或池内死串）。

### 4.2 ⭐ 分桶算法已解出（O(1) 定位）

`y5#e` = `SnsPathHelper.getMediaFilePath(root, mediaId)`（classes3.dex）：

```
0x0041  mediaId.getBytes()
0x0045  ai/j->g([B)               ; MD5(mediaId) → 32 位 hex
0x0058  append(md5.charAt(0)); append('/')   ; 第 1 级目录 = md5[0]
0x0069  append(md5.charAt(1)); append('/')   ; 第 2 级目录 = md5[1]
0x0089  return "<md5[0]>/<md5[1]>/"
```

⇒ **分桶 = `MD5(mediaId)` 的 hex 前两个字符，逐级建目录**：

```
完整路径 = <cache>/<hash32>/sns/<md5(mediaId)[0]>/<md5(mediaId)[1]>/sight_<mediaId去0>[<urlMd5>]&enc<n>
```

⇒ **O(1) 可定位**，不需要 `sns/**/` 全树扫。`y5#b`（`buildCdnSightDownloadSavePath`）正是
`root + y5#e(root,mediaId) + y5#a(vm3,'')` 三段拼接。

### 4.3 待真机确认

- `<hash32>` 是否随账号/重装变化（R14 已疑）；`f4->F8()` 返回值末尾是否已含 `/sns`。
- 对「无 `sight_` 文件但 mediaId 已知」的动态，用 `MD5(mediaId)[0..1]` 拼路径即可命中；命中不了再
  回退 glob（保持与 R14 一致的兜底）。

---

## 五、Q5 · 全后台无感知方案设计 [DONE]

### 5.1 判据回顾：为什么「滚动 + hook 自动播放」不够

R43 §8.4 的三层可行性：文字✅（后台同步）、图片⚠️（要滚）、视频❌（要「让它播」）。
「让它播」= 视频项被渲染且 `isAutoDownload` 放行。**后台（无 UI）不渲染 → 不下载**。
⇒ 满足「不拉起界面、不闪屏」的**唯一**路线是 **Route C：主动下发 native 下载**。
Route A（hook `d1#s` 放行）只能作为「用户主动打开朋友圈时顺带下视频」的**增强**，不能单独达成全后台。

### 5.2 推荐实现：Route C 为主 + Route A 兜底

**Route C（主）· 主动 native 下载** —— 触发链：

1. **节拍**：复用 `MomentsAuto` 定时刷新 tick 结束后（此时 `SnsInfo` 已由后台同步更新到库），
   或复用 `MomentsExport.backfillVideos` 的独立游标/单航班 CAS/空间闸门（R43 `_r43_video_export.md` 已定义）。
2. **进入**：对每条「名单内好友」且 `row.type == 15` 的动态：
   - 解析 `SnsInfo.content` 的 media protobuf（`vm3`），取 `url` / `fileKey` / `aeskey` / `mediaId`。
     ⚠️ 字段名被混淆，需先做一次「vm3 字段 ↔ url/fileKey/aeskey」真机映射（见 5.5 必验项）。
   - 构造 `CdnLogic$C2CDownloadRequest{fileKey, url, aeskey, savePath, fileType, customHeader}`。
   - 经 `ok0/s2->c9()` 单例调 `startURLDownload`，或直接 `CdnLogic.startSNSDownload(req,null,null,0)`。
3. **落盘**：native 解密后写 `sns/<md5(mediaId)[0]>/<md5(mediaId)[1]>/sight_<mediaId>&enc<n>`
   （Q4 O(1) 路径）。随后按 mediaId 精确定位 → 校验 `ftyp` 头 → 拷入导出目录（复用 `MomentsExport`
   的 `fileNameFor` / `tmp+rename` / 计数口径）。

**Route A（兜底）· hook `d1#s` 强制放行**：仅当「微信在前台且用户滚到该好友动态」时，
把「仅 WiFi / busy time」判成「允许」，让自动播放顺带下视频（解决 R43 §8.3 的 `wifi_on=0` 卡点）。
实现点：hook `Lcom/tencent/mm/plugin/sns/model/d1;->s` 返回 `o=5`（允许），**只对名单内好友生效**
（先查 `SnsInfo.userName` 是否在导出名单，不在则走原方法）。

### 5.3 与现有组件的接入点

| 组件 | 接入方式 |
|---|---|
| `MomentsAuto`（定时刷新/自动加载） | 定时刷新 tick 结束后作为 Route C 的**触发节拍**；不改其内部逻辑 |
| `MomentsExport`（导出） | 复用 `backfillVideos` 游标/单航班/空间闸门/`fileNameFor`/tmp+rename/计数收口 `countVid` |
| `MomentsLogic` | 新增 `isSnsVideo(type==15)`、`snsVideoUrls` 已具备（R43）；**新增** vm3 protobuf 解析器拿 aeskey |
| `SnsGuard.listCurrentSns` | 已 select `type`（R43 §三），直接按 `type==15` + 名单过滤 |

### 5.4 失败分类与计数器口径（不污染既有计数）

沿用 R43 视频独立 11 个 `VidOutcome`（`vidNotVideo/vidNoUrl/vidNoUa/vidHttp4xx/vidHttp5xx/
vidTooLarge/vidWrongType/vidIoFail/vidSpace/vidCopied/vidAlready`），**零共享**图片四态
（`imgCopied/imgFileMiss/imgNoMedia`）。Route C 新增（只动 `vid*`）：

- `vidNoAesKey`：解析 vm3 拿不到 aeskey（Route C 特有，**不混入** `vidNoUrl`）；
- `vidDispatchFail`：`startSNSDownload` 返回非 0 / 抛异常；
- `vidLandMiss`：下发后轮询落盘路径（O(1) 路径 + glob 兜底）超时未出现明文文件。

计数收口仍在 `countVid`（唯一入口，无静默 else）。

### 5.5 三条红线

**红线 1 · 进入边界**（同时满足才下发 Route C）：
1. 开关 `moments.copy_videos == true`；
2. `isSnsVideo(row.type)`（`type==15`）；
3. `snsVideoUrls(row.content)` 非空（有 `/20202/`）；
4. **作者在导出名单内**（`userName` 命中名单，否则不下载）；
5. 解析出 `aeskey` 非空（否则 `vidNoAesKey` 直接 return，不发网络）；
6. `dir.usableSpace >= MIN_FREE_BYTES`（落盘前判，不足不写一字节）。

**红线 2 · 返回边界**（绝不下载、绝不发网络）：
- `copy_videos==false` → 两处调用点前置 `if`，零网络零写入；
- `type != 15` → `vidNotVideo`；名单外好友 → 直接跳过（不计入 `vid*` 或记入独立 `vidNotTarget`）；
- `bits`/`content` 拿不到 → `vidNoUrl`/`vidNoAesKey`；URL 无 `/20202/` → `vidNoUrl`；
- 空间不足 → `vidSpace`；已存在非空 → `vidAlready`；
- `startSNSDownload` 失败 → `vidDispatchFail`；落盘轮询超时 → `vidLandMiss`（临时）。

**红线 3 · 复位边界**（批量遍历不串味）：
- Route C 的 `vid*` 计数与图片四态、Route A 的 hook 状态**互不相干**；
- 每条动态的判定用**局部变量**，无「上次失败」共享 flag；游标 `cur.advanceTo` 在 `continue` 前**无条件推进**；
- Route A 的 hook 只在「进入方法时」读名单，**名单外立即回退原逻辑**，不留跨条状态。

### 5.6 分步计划

1. **（真机）vm3 字段映射**：对一条 type=15 动态 dump `SnsInfo.content` 的 media protobuf，
   反查 `url`/`fileKey`/`aeskey` 分别落在 vm3 哪个混淆字段（决定 Route C 能不能写解析器）。
2. **（真机）startSNSDownload 最小调用**：用第 1 步的 url/fileKey/aeskey 手工构造
   `C2CDownloadRequest`，验证 `startSNSDownload(req,null,null,0)` 能否在**不拉起 UI** 的情况下落盘明文。
3. **（代码）** 在 `MomentsLogic` 加 vm3 解析 + `vidNoAesKey` 等 3 个新计数；
   在 `MomentsExport` 加 Route C 下发路径（复用 backfill 游标/闸门）。
4. **（代码）** Route A 兜底 hook（`d1#s` 放行，名单内生效）。
5. **（真机）三红线验收**：名单外好友不下、bits 拿不到不下、非 15 不下；批量遍历上一条失败不影响下一条。

### 5.7 必在真机验证（不可离线定论）

- ⚠️ **vm3 的 aeskey 字段号**（本文只确认「在 vm3 的 String 字段里」，未定位到具体字段名）——
  Route C 的硬前提。
- ⚠️ `startSNSDownload` 的 `VideoStreamingCallback/DownloadCallback` 传 null 是否可接受。
- ⚠️ 落盘根路径 `f4->F8()` 是否含 `/sns`、`<hash32>` 稳定性。
- ⚠️ 后台进程（无 Activity）下发 CDN 下载是否会被系统/微信自身策略拦截。

---

## 附 · 证据锚点速查

| 锚点 | 位置 |
|---|---|
| `d1#s`（isAutoDownload）反汇编 | `.tmp/d1_s3.txt`（classes3.dex） |
| `j2`（NetSceneSnsComment） | classes3.dex，`<init>`+`doScene`（cmd 213） |
| `ok0/x0#r`（startURLDownload） | classes13.dex |
| `ok0/j#invoke`（tj/k → x0#r 字段映射） | classes7.dex |
| `CdnLogic.startSNSDownload` | classes13.dex（native，含 CdnManager 重载） |
| `bf3/t0#S/#f0`（getSnsSightName/getUrlMd5） | classes3.dex |
| `tl0/c#a`（&enc 后缀） | classes2.dex |
| `y5#e`（分桶 MD5(mediaId)[0..1]） | classes3.dex |

---

## 主会话追加取证（2026-11-08 00:2x）

### A. content protobuf 完整结构（type=15）

`SnsInfo.content` = `TimeLineObject`。媒体对象在 **field 8.5**（`ContentObj` 下的 `MediaObject`）：

| 路径 | 内容 | 样例（snsId -3397093125619445258） |
|---|---|---|
| `8.5.1` | mediaId | `15049650948694479362` |
| `8.5.2` | media type | `6` |
| `8.5.3` | 标题/描述 | `给家长带盐` |
| `8.5.4` | **主视频 URL（20202）** | `http://szzjwxsns.video.qq.com/102/20202/snsvideodownload?encfilekey=…` |
| `8.5.6` | 封面 URL（20250） | `…/20250/snsvideodownload?…` |
| `8.5.19` | 32-hex 候选 key | `6b487fcf10886b8f79cfb882c67ba1e8` |
| `8.5.26` | 32-hex 候选 key | `7e15613de91fb5e84cab9e3faaf9eb04` |
| `8.5.33` | 32-hex 候选 key | `6228b801a7c1491a78bc5c07290b33e4` |
| `8.5.39` | DRM 信息 | `all` + `CAISBAgeEAEoAjAc` |

### B. ⛔ 自己解密不可行（已实测）

用另一条已下载的密文（`-3398536920689724692` → `.tmp/sns/v.mp4`，9,884,961 字节）做对照：
该条 content 里的三个 32-hex 候选 `0270f0f5…` / `be75013f…` / `778c845c…`，
分别用 **AES-128-ECB** 与 **AES-128-CBC(IV=0)** 解前 64 字节 ⇒ **全部为随机字节，无 `ftyp`**。

⇒ 微信 SNS CDN 的加密**不是标准 AES-ECB/CBC**（native 侧另有算法，见 `_r10_cdn_api.md` 的 `decryptKey`）。
⇒ **「纯离线解密」这条路关闭**，必须让微信 native 参与解密。

### C. ⭐ 找到更好的入口：`Lid3/f0#f(SnsInfo, vm3, x7)V`

反汇编 `Lid3/f0#f`（日志 tag `AdMediaDownloadHelper.triggerDownloadMediaSight`）：

```
iget-object v5, Lq54/vm3;->d:Ljava/lang/String;      ← 取 mediaId
invoke-static Lcom/tencent/mm/plugin/sns/model/f4;->F8   ← 缓存根
invoke-static Lcom/tencent/mm/plugin/sns/model/y5;->e   ← getMediaFilePath（MD5 分桶）
invoke-static Lbf3/t0;->S                              ← getSnsSightName
invoke-virtual Ljava/lang/String;->concat              ← 拼完整路径
new-instance Lid3/e0; … o3->postDelayed               ← 投递下载任务
```

**价值**：签名为 `(SnsInfo, vm3, x7)V` —— 只要我们能反序列化出 `vm3`，
就能让它自己完成「路径计算 + 下载任务投递 + aeskey 使用」，
**完全不需要我们知道 aeskey 是哪个字段**。

⚠️ 但该类日志 tag 带 `ad`（广告），**是否对普通朋友圈视频通用尚未确认** —— 这是下一步真机验证的核心。

---

## [DONE-VT] `sub=vidtrig` 最小验证探针（2026-11-08）

新增探针类 `probe/VidTrigProbe.kt`，入口 `op=moments sub=vidtrig sub2=on|off|stat`。
⛔ 只挂 hook + 打日志，**不改参数、不 return 替代值、不阻断原方法**；不 commit、不装包、不跑真机。

### VT-1 两个 hook 点的精确签名（逐字，从反汇编取）

**F · 触发下载投递点**（`_cache/_x2_full.txt:937390`）

```
Lid3/f0;.f(Lcom/tencent/mm/plugin/sns/storage/SnsInfo;Lq54/vm3;Lcom/tencent/mm/storage/x7;)V
```

- `id3.f0` = `com.tencent.mm.plugin.sns.ad.timeline.helper.AdMediaDownloadHelper`
  （类内 const-string 自证），方法 `f` = `triggerDownloadMediaSight`。
- 方法体关键指令（`6071d0..6072d6`）：`if-nez v5(→vm3)` / `if-nez v4(→SnsInfo)` 判空 →
  `iput-boolean v0, v5, vm3.N:Z` → `iget-object v2, v5, vm3.d:String`（mediaId）→
  `invoke-static {v4,v5} Lia3/c0.b(SnsInfo,vm3)V` → `f4.F8()`（缓存根）→ `y5.e`（MD5 分桶）→
  `bf3/t0.S`（getSnsSightName）→ `String.concat`（拼完整路径）→ `o6.k`（文件存在判）→
  `f4.Na()` 拿 `o3` → `new Lid3/e0(vm3, x7)` → `o3.postDelayed(runnable, 0)`。
- 寄存器映射（regs=7/ins=4）：`v4=SnsInfo(args[0])`、`v5=vm3(args[1])`、`v6=x7(args[2])`。

**R · CDN 下载下发点**（`_cache/_r14_ok0j_invoke.txt:35` 调用方钉死）

```
Lok0/x0;.r(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;IZ)I
```

`r` 的 8 个形参语义（由调用方 `ok0/j#invoke` 的 `iget` 顺序反推，逐字）：

| idx | 形参 | 来源字段（`Ltj/k`） | 本轮日志字段 |
|---|---|---|---|
| 0 | String | `field_mediaId` | mediaId |
| 1 | String | `u`（url） | **url** |
| 2 | String | `field_fullpath`（落盘全路径） | **save** |
| 3 | int | `field_fileType` | ft |
| 4 | String | `field_aesKey` | **aes** |
| 5 | String | `field_authKey` | **fileKey** |
| 6 | int | `field_totalLen` | — |
| 7 | boolean | 常量 0 | — |

- `C2CDownloadRequest` 的字段对应（`_cache/_r14_ok0m1_m.txt:448-471` 侧证）：
  `fileKey=field_mediaId`、`url=u`、`savePath=field_fullpath(setSavePath)`、
  `aeskey=field_aesKey`、`snsCipherKey=…`。
- ⚠️ 注意 `r` 的「fileKey」**不是** `C2CDownloadRequest.fileKey`（那个是 mediaId），
  而是 `field_authKey`（= encfilekey 之外的另一个 key）。钉 aeskey 只认 `aes=`（idx 4）。

**字段名钉死**（供 hook 取数）：
- `SnsInfo.snsId` 字段在父类 `Ljj/ca` 上：`Ljj/ca;.field_snsId:J`（`_x2_full.txt` SnsInfo.getNewSnsInfo）。
- `vm3.mediaId` 字段：`Lq54/vm3;.d:Ljava/lang/String;`（`_x2_full.txt:607228`）。

**未挂钩（本轮判据不需要）**：`d1#s`（isAutoDownload，`o=5` 允许 / `o=10` 拒绝，见 §一）。
它是「让视频播不播」的闸门，不是「下载触没触发」的闸门 —— 本轮要证的是后者，
所以只挂 F/R 两个点；`d1` 的放行改造是 Route A 的事，留给下一轮。

### VT-2 每个点打什么日志

```
VIDTRIG F id=<snsId> media=<mediaId> caller=<c1.m1 -> c2.m2 -> c3.m3>
VIDTRIG R url=<url前80字符> aes=<原值> fileKey=<原值> save=<落盘路径>
VIDTRIG on  hooks=<n> f=<true|false> r=<true|false>
VIDTRIG stat F=<n> R=<n> hooks=<n>
```

- `F` 的 `caller` 取调用栈前 3 帧（滤掉 xposed/模块/reflect/id3.f0 自身）——
  **这是区分「广告调用」还是「普通朋友圈视频调用」的核心判据**。
- `R` 的 `aes` 是钉死 aeskey 的关键：直接与 content 里 `8.5.19 / 8.5.26 / 8.5.33`
  三个 32-hex 比对。

### VT-3 真机验证步骤（主会话按这个跑）

```
adb shell am broadcast -a com.lx.wxgroup.action.PROBE --es op moments --es sub vidtrig --es sub2 on -p com.tencent.mm
```

1. 进朋友圈 → 点开**一条普通好友的视频**（播放页）。
2. `adb pull /sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt` → grep `VIDTRIG`。
3. 期望看到的判定：
   - **若普通视频命中**：出现 `VIDTRIG F id=<负snsId> media=<20位mediaId> caller=<…>`，
     且 `caller` 里**不含** `AdMediaDownloadHelper` 相关调用链（而是 sns 时间线/播放器类）；
     随后紧跟 `VIDTRIG R url=<…20202…> aes=<32-hex> fileKey=<…> save=<…sight_…>`。
   - **若只有广告命中**：`F` 只在广告场景出现、caller 带 `ad` 字样；普通视频不出现 `F`，
     只可能出现 `R`（走另一条触发链）。
4. 钉 aeskey：把 `R` 行的 `aes=` 与同一条 content 的三个 32-hex 候选逐字比对。
5. `sub2=stat` 看 `F`/`R` 命中计数；`sub2=off` 卸载。

### VT-4 两条后续路径的落点

- **若 `id3/f0#f` 只在广告场景命中**（普通视频不出现 `F`）：
  - 说明普通朋友圈视频**不走** `AdMediaDownloadHelper.triggerDownloadMediaSight`，
    走的是另一条链（大概率 `d1`/`LazyerImageLoader2` 侧或播放器侧直接调 `ok0/x0#r`）。
  - 下一轮：反汇编 `R` 行的 `caller`（即 `ok0/x0#r` 的**上层**调用栈）找普通视频的真实触发入口；
    同时回到 §三 `Lok0/j#invoke` 上游，追 `tj/k` 是从哪个 SNS 组件填进来的。
  - 落点文档：`docs/_r44_bg_video_plan.md` 新增「普通视频触发链」小节，锚点固化后再改 Route C。
- **若普通视频也命中 `id3/f0#f`**：
  - 说明 `triggerDownloadMediaSight` 是**通用入口**（类名带 ad 只是包路径误导），
    那 Route C 可直接复用 §C 的路线：反序列化 `vm3` → 调 `id3/f0#f(SnsInfo, vm3, x7)`，
    **不需要知道 aeskey 字段号**（它内部自己用）。
  - 但「反序列化 vm3 + 构造 x7」仍需一次最小验证（x7 空/占位是否可接受），
    落点接 §5.6 第 1 步（vm3 字段映射）+ 第 2 步（startSNSDownload 最小调用）。


---

## R44 补充 · Route C 探针真机执行单（2026-11-08）

> 目的：把「文字/图片已完成，只差视频」这最后一块变成**可上机执行的步骤单**，
> 避免真机上现编命令。

### 探针入口

```
# 1) 先列候选（不知道哪个 snsId 是视频动态时）
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
  --es op moments --es sub dispatch --es sub2 list --es sub3 12

# 2) 挑中一条，只看不打（默认 probe）
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
  --es op moments --es sub dispatch --es sub2 <snsId> --es sub3 probe

# 3) probe 全绿后才真下发
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
  --es op moments --es sub dispatch --es sub2 <snsId> --es sub3 real
```

⚠️ `snsId` 是 **64 位 Long 且可能为负** ⇒ 必须走 `--es`（String），不能 `--el`。
   本仓库 `tools/dev.py` 的 `bc()` 已对 `--es` 后的值做二次单引号包壳，
   ⛔ 不要手敲 `adb shell am broadcast`（`adb shell` 会二次解析，负号/空格会被吃掉）。

### 判据（按顺序，任一条不过就停下）

| # | 看什么 | 期望 | 不过说明 |
|---|---|---|---|
| C1 | `SNSPUSH no classLoader` | **不出现** | `HookEntry` 漏 prepare，或模块未注入 |
| C2 | `SNSPUSH row miss` | 不出现 | snsId 不在当前库前 3000 条内 |
| C3 | `SNSPUSH parseFrom null` | 不出现 | `content` 不是合法 protobuf / 类名漂移 |
| C4 | `ContentObj null` / `no media` | 不出现 | `h` 字段名漂移，或该动态纯文字 |
| C5 | 打印的 `url` 含 `/20202/` | **出现** | 字段错位（`g` 不是视频 URL） |
| C6 | `cacheFile=` 路径形如 `<root>/sns/x/y/sight_<mediaId>` | 合理 | `F8` 取根失败 |
| C7 | `real` 后 `l0#d returned` 不是 `EXCEPTION` | true/false 都算通过 | 反射匹配失败，看 `l0#d sig=` |
| C8 | 落盘出现明文 MP4（`ff` 头 / 可播放） | **核心终判** | 见下方失败分支 |

### ⚠️ 已知未验前提（必须在 C7 之后立刻判）

`EntryReceiver.io {}` 是**裸 `Thread{}`，没有 Looper**。而 `l0#h` 里是：

```
Looper.myLooper() != null → queue.addIdleHandler(...)
Looper.myLooper() == null → l0#l (checkIdleFile) + o3#postDelayed 兜底
```

「兜底那条也能下」目前**只是反汇编推断，没有真机证据**。
⇒ 若 C7 通过（`d` 返回 true）但**迟迟不落盘**，第一嫌疑就是这条，
   而不是「微信不肯下」。此时的正解是**把调用切到带 Looper 的线程**，
   或直接 `f4#Na()` 取 `o3` Handler 自己 post 一次（`l0.a` 就是它）。

### 失败分支速查

- `l0#d returned false`：说明进到 `l0#f` 被某个前置判据挡了（`vm3` 空 / 类型不认 / 已存在）。
  下一步 hook `l0#f` 的 `before` 打全部 8 个入参，对齐反汇编里的分支。
- `l0#d returned EXCEPTION` + `NoSuchMethod`：`l0` 里没有 4 参的 `d`，
  或第 3 参不是 `bf3/m0`。探针已打 `l0#d sig=`，照它改。
- `f4#ea` 返回 null：退化为读 `f4.E` 静态字段（`f4#ea` 内部就是懒加载写回 `E`）。
