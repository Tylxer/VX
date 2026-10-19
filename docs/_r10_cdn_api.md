# M2 CDN API 与 hook 点枚举

> lane: M2（纯离线静态取证）
> 目标：枚举 `com.tencent.mars.cdn.CdnLogic` 的方法、DownloadCallback 接口、BatchSnsReqImageData 构造/读取点、startSNSDownload 调用点，产出候选 hook 点清单。
> 环境：已反编译 dex（classesN.dex），工具 `tools/_r6_snsdex.py` / `_r6_snsdis.py` / `_r4_dexref.py`
> 标注约定：✅实测（有命令输出支撑） / 🟡推断（据上下文合理推测） / ⛔未知
> native 方法一律标注：**Xposed 不可直接 hook**

---

## 候选 hook 点清单

（逐步证据产出后填充，按「能拿到什么」价值降序）

> 排序依据：**能拿到什么**（是否能直接拿到"已解密图片文件路径 / 完整下载请求 / 错误码"）。
> `ok0.x0` = `CdnTransportEngine`（✅由 log tag `MicroMsg.CdnTransportEngine` 实测确认）；
> `jf3.f` = `SnsCdnDownloadBase`（✅由 const-string `com.tencent.mm.plugin.sns.model.download.SnsCdnDownloadBase` 实测确认）。

| # | 类名#方法名 | 参数 | 能截获的数据 | 方法类型 | Xposed 可行性 |
|---|---|---|---|---|---|
| 1 | `ok0.x0`(CdnTransportEngine) # `onC2CDownloadCompleted` | `(String fileid, CdnLogic$C2CDownloadResult)` | ★**结果全量**：`result.errorCode`、`result.picCachePath`（**图片落盘路径**）、`fileSize`/`recvedBytes`、`picIndex`、`batchImageFileKey`、`batchImageNeedRetry[]`、`realUsedURL`、`httpStatusCode` | **java**（接口实现，有 code_item） | ✅ **可 hook**（推荐主用） |
| 2 | `ok0.x0` # `n` | `(tj.g req)I` | ★**批量下载请求全量**：`req.l1 = [BatchSnsReqImageData]`（含 `url`/`fileKey`/`decryptKey`/`imageCachePath`/`picIndex`/`totalFileSize`）+ `req.i1`(feedId) + `req.j1`(feedPicCount)。在调 native **之前**，可读写 | **java** | ✅ **可 hook**（推荐主用，还能改入参） |
| 3 | `jf3.f`(SnsCdnDownloadBase) # `k` | `([String)Integer`（AsyncTask doInBackground） | ★**唯一的 `BatchSnsReqImageData` 构造点**：new 之后立刻 `iput` 全部 6 个字段（`url`/`fileKey`/`decryptKey`/`imageCachePath`/`picIndex`/`totalFileSize`）。可改 `imageCachePath` 控制落盘 | **java** | ✅ 可 hook |
| 4 | `ok0.x0` # `onDownloadProgressChanged` | `(String, long, long, boolean)` | fileid + 已收/总量 + 完成标志（可用于判断任务是否真的在跑） | **java** | ✅ 可 hook |
| 5 | `jf3.d` # `C2` | `(String, int, tj.e, tj.f, boolean)I` | ★**唯一的 `BatchSnsReqImageData` 字段读取点**（读 `picIndex`×5、`retry`×1）。是"批量回调有没有被真正触发"的探针 | **java** | ✅ 可 hook，但 ⚠️ dex 内**直接调用点 = 0**（🟡多态/接口回调触发），触发时机待真机验证 |
| 6 | `com.tencent.mm.plugin.sns.ui.dk` # `a` | `()Z` | 批量链路的**开关**（内部调 `CdnLogic.allowBatchImageDownload()`）。强制返回 true 可尝试打开批量下载 | **java** | ✅ 可 hook（🟡推断其返回值决定批量开关） |
| 7 | `ok0.j` # `invoke` / `ok0.m1` # `m` | `(Object)Object` / `(boolean)V` | `ok0.x0.n()` 的**上游调用方**（Kotlin lambda 包装），可确认批量入口是否真的被调用 | **java** | ✅ 可 hook |
| 8 | `CdnLogic$C2CDownloadRequest` # `setBatchSnsReqImageDatas` / `setSavePath` / `setFileKey` / `setUrl` / `setAeskey` / `setSnsCipherKey` | builder 链 | 单个字段的 setter | **java** | ✅ 可 hook，⚠️**但抓不到主链路**——`ok0.x0.n` 里是**直接 `iput` 字段赋值**，不走 setter（✅实测：n() 中全是 `iput-object/iput`，无 `invoke setXxx`） |
| 9 | `CdnLogic` # `createSNSDownload` | `(String, String, int, int, String)C2CDownloadRequest` | SNS 请求对象构造 | **java static** | ✅ 可 hook，⚠️ 但**覆盖不到主链路**——全 dex 仅 2 个调用点（`hj0.t.invokeSuspend` + 内部 `createGroupNoticeDownload`） |
| 10 | `hj0.t` # `invokeSuspend` / `hj0.s` # `onC2CDownloadCompleted` | 协程 | 另一条（小众）SNS 下载路径 | **java** | ✅ 可 hook |
| ⛔ | `CdnLogic` # `startSNSDownload` | `(C2CDownloadRequest, VideoStreamingCallback, DownloadCallback, int)I` | 本是"所有 SNS 下载的总入口"，能拿到完整 request + callback | **NATIVE** acc=0x109 | ⛔ **Xposed 不可直接 hook**（无 Java 实现体）。需改 hook 其调用方 #2，或用 native inline hook（Dobby/AndHook） |
| ⛔ | `CdnLogic` # `allowBatchImageDownload` | `()Z` | 批量开关的底层判定 | **NATIVE** acc=0x109 | ⛔ **Xposed 不可直接 hook**（改 hook #6） |
| ⛔ | `CdnLogic` # `startC2CDownload` / `startURLDownload` / `startHttpsDownload` / `startHttpVideoStreamingDownload` / `startVideoStreamingDownload` / `startCronetFileDownload` / `startCronetSimpleRequest` | `(C2CDownloadRequest, DownloadCallback)I` 等 | 其它下载入口 | **NATIVE** | ⛔ **Xposed 不可直接 hook** |
| ⛔ | `CdnLogic` # `getSnsImagePrivateProtocolAvalible` / `setSnsImagePrivateProtocolAvalible(Z)` / `setSnsImageStreamProtocolAvalible(Z)` / `setSnsDistanceData(III[B)` | — | SNS 私有协议开关 | **NATIVE** | ⛔ **Xposed 不可直接 hook** |
| ⛔ | `CdnLogic$DownloadCallback` # `onC2CDownloadCompleted`（接口声明） | 同 #1 | 同上 | **ABSTRACT** acc=0x401 | ⛔ **不可 hook 接口声明**（无实现体），必须 hook 具体实现类（即 #1） |

**一句话结论**：主 hook 点 = `ok0.x0`（CdnTransportEngine）的 `n(tj.g)` 抓请求 + `onC2CDownloadCompleted(String, C2CDownloadResult)` 抓结果。
`startSNSDownload` 本身是 native，只能"绕过去 hook 它的调用方和回调"。

---

## 逐步证据

### Step 0 准备

```bash
PY=/c/Users/lx/.workbuddy/binaries/python/versions/3.13.12/python
cd /c/Users/lx/WorkBuddy/2026-09-29-20-23-21
```

```bash
ls -la _cache/dex/*.dex
```

- ✅实测：15 个 dex（`classes.dex` + `classes2..15.dex`），全部同一时间戳（Oct 13 00:33），来自**同一个 APK** 的多 dex 拆分。
- ✅实测：`CdnLogic` 的类型/方法**引用**散布在 classes2/3/4/5/7/8/9/10/11/12/13，但**只有 classes13.dex 带 `<init>()`V** ⇒ **classes13.dex 是 `CdnLogic` 的类定义所在 dex**（其余是 method_id 引用）。
- 后续所有"方法是否有代码体"判定均以 **classes13.dex** 为准。

[DONE]

---

### Step 1 `Lcom/tencent/mars/cdn/CdnLogic;` 全部方法（含 native 判定）

命令：

```bash
$PY tools/_r6_snsdex.py types 'CdnLogic'
$PY tools/_r6_snsdex.py methods 'Lcom/tencent/mars/cdn/CdnLogic;'
# native 判定：读 classes13.dex 的 class_data_item -> encoded_method.access_flags (0x100 = ACC_NATIVE)
```

✅实测：`CdnLogic` 类访问标志 `0x1`（public），`static_fields=133 / instance_fields=0 / direct=99 / virtual=2`。

#### 1.1 与 sns / 图片 / 下载 / 回调 相关的方法（逐个签名）

| 方法 | 签名 | 类型 | Xposed |
|---|---|---|---|
| `startSNSDownload` | `(C2CDownloadRequest, VideoStreamingCallback, DownloadCallback, int)I` | **NATIVE** acc=0x109 | ⛔ 不可直接 hook |
| `createSNSDownload` | `(String, String, int, int, String)C2CDownloadRequest` | java static acc=0x9 | ✅ 可 hook |
| `createSNSVideoDownload` | `(C2CDownloadRequest, String, int)C2CDownloadRequest` | java static | ✅ 可 hook |
| `createSNSUpload` | `(String, int, int, String)C2CUploadRequest` | java static | ✅ 可 hook |
| `startC2CDownload` | `(C2CDownloadRequest, DownloadCallback)I` | **NATIVE** acc=0x109 | ⛔ |
| `startURLDownload` | `(C2CDownloadRequest, DownloadCallback)I` | **NATIVE** | ⛔ |
| `startHttpsDownload` | `(C2CDownloadRequest, DownloadCallback)I` | **NATIVE** | ⛔ |
| `startHttpMultiSocketDownloadTask` | `(C2CDownloadRequest, DownloadCallback)I` | **NATIVE** | ⛔ |
| `startHttpVideoStreamingDownload` | `(C2CDownloadRequest, VideoStreamingCallback, DownloadCallback, int)I` | **NATIVE** | ⛔ |
| `startVideoStreamingDownload` | `(C2CDownloadRequest, VideoStreamingCallback, DownloadCallback, int)I` | **NATIVE** | ⛔ |
| `startCronetFileDownload` | `(C2CDownloadRequest, DownloadCallback)I` | **NATIVE** | ⛔ |
| `startCronetSimpleRequest` | `(C2CDownloadRequest, DownloadCallback)I` | **NATIVE** | ⛔ |
| `allowBatchImageDownload` | `()Z` | **NATIVE** acc=0x109 | ⛔ |
| `getSnsImagePrivateProtocolAvalible` | `()Z` | **NATIVE** | ⛔ |
| `setSnsImagePrivateProtocolAvalible` | `(Z)V` | **NATIVE** | ⛔ |
| `setSnsImageStreamProtocolAvalible` | `(Z)V` | **NATIVE** | ⛔ |
| `setSnsDistanceData` | `(int,int,int,[B)V` | **NATIVE** | ⛔ |
| `queryVideoMoovInfo` | `(C2CDownloadRequest, [J)Z` | **NATIVE** | ⛔ |
| `cancelTask` | `(String)V` | **NATIVE** | ⛔ |
| `cancelDownloadTaskWithResult` | `(String, C2CDownloadResult)I` | **NATIVE** | ⛔ |
| `taskExist` | `(String)Z` | **NATIVE** | ⛔ |
| `queryDownloadedSize` | `(String, [J)I` | **NATIVE** | ⛔ |
| `queryContinuousSize` | `(String, J, [J)I` | **NATIVE** | ⛔ |
| `withAesEcbDecrypt` | `(C2CDownloadRequest, String)C2CDownloadRequest` | java static | ✅ 可 hook |
| `withXorDecrypt` | `(C2CDownloadRequest, String)C2CDownloadRequest` | java static | ✅ 可 hook |
| `makeVideoPreload` / `makeVideoStreaming` | `(C2CDownloadRequest…)C2CDownloadRequest` | java static | ✅ 可 hook |
| `InitSavePath` | `(String, AppCallback, String, Z)V` | java static | ✅ 可 hook |
| `Initialize` | `(String, AppCallback, String,String,String,String, Z)V` | java static | ✅ 可 hook |
| `UnInitialize` | `()V` | java static | ✅ 可 hook |
| `isFileReady` | `(String)I` | java static | ✅ 可 hook |
| `queryFreeSpace` | `(String)J` | java static | ✅ 可 hook |
| `getRecentConnectRecords` | `(int,int)[Lcom/tencent/mars/mm/ConnectRecord;` | java static | ✅ 可 hook |
| `doCertificateVerify` / `…WithDetail` | `(String,[[B)I` / `(String,[[B)CertVerifyResult` | java static | ✅ 可 hook |
| `getSystemProperty` / `getUSBState` / `getLoadLibraries` | — | java / **NATIVE(private)** | 见备注 |

#### 1.2 ✅实测：`CdnLogic` 全部 NATIVE 方法清单（classes13.dex，acc & 0x100）

`allowBatchImageDownload` / `calcFileCrc32` / `calcFileMD5` / `calcMP4IdentifyMD5` /
`cancelDownloadTaskWithResult` / `cancelTask` / `cancelUploadTaskWithResult` / `createAeskey` /
`createUniqueFilekey` / `getLoadLibraries`(private) / `getRecentAverageSpeed` / `getRecentAverageSpeed2` /
`getRecentDownloadInfo` / `getSnsImagePrivateProtocolAvalible` / `getTaskRecentAverageSpeed` /
`httpMultiSocketDownloadTaskState` / `isVideoDataAvailable` / `markVideoBufferingStatus` /
`onCreate`(private) / `pauseHttpMultiSocketDownloadTask` / `queryAvailableQuota` / `queryContinuousSize` /
`queryDownloadedSize` / `queryFilesizeLimit` / `queryM3U8Data` / `queryVideoMoovInfo` / `requestVideoData` /
`resumeHttpMultiSocketDownloadTask` / `setAppCallback`(private) / `setCdnInfo` / `setCdnInfoParams` /
`setConfig` / `setDebugIP` / `setDownloadQuota` / `setFlowLimitPerHour` / `setFlowReportParams` /
`setLegacyCdnInfo` / `setRSAPublicKeyParams`(private) / `setSnsDistanceData` /
`setSnsImagePrivateProtocolAvalible` / `setSnsImageStreamProtocolAvalible` / `setToUserCiper`(private) /
`setUseIPv6Cdn` / `setVcodec1Limit` / `startBatchCheckHitUpload` / **`startC2CDownload`** /
`startC2CUpload` / `startCronetFileDownload` / `startCronetSimpleRequest` / `startFtnUpload` /
`startHttpMultiSocketDownloadTask` / `startHttpVideoStreamingDownload` / `startHttpsDownload` /
**`startSNSDownload`** / `startSSUpload` / `startURLDownload` / `startVideoStreamingDownload` /
`taskExist` / `triggerPreConnect` / `updateVideoPlaybackTimetick`

> **关键结论**：所有 `start*Download` / `start*Upload` 入口**全部是 native**，无一例外。
> ⇒ Xposed **不能**直接 hook `CdnLogic.startSNSDownload` 本身去改返回值/拿结果。
> ⇒ 可行路径只有两条：(a) hook **Java 侧的构造/包装方法**（`createSNSDownload`、`setBatchSnsReqImageDatas`、`setSavePath`…）改**入参**；
> (b) hook **`DownloadCallback` 的具体实现类** 的 `onC2CDownloadCompleted` 拿**结果**。

#### 1.3 ✅实测：是 java（有 code_item）的 `CdnLogic` 方法（可 hook）

`<init>` / `InitSavePath` / `Initialize` / `UnInitialize` /
`createC2CBigfileDownload` / `createC2CBigfileUpload` / `createC2CDownload` / `createC2CExistCheck` /
`createC2CSSUpload` / `createC2CUpload`(×4 重载) / `createC2CVideoDownload` / `createFavDownload` /
`createFavUpload` / `createFinderImageUpload` / `createFinderVideoUpload` / `createFreshC2CUpload` /
`createFriendsImageUpload` / `createFriendsVideoUpload` / `createGroupNoticeDownload` /
`createGroupNoticeUpload` / `createHLSVideoDownload` / **`createSNSDownload`** / `createSNSUpload` /
`createSNSVideoDownload` / `doCertificateVerify` / `doCertificateVerifyWithDetail` /
`getRecentConnectRecords` / `getSystemProperty` / `getUSBState` / `isFileReady` / `makeVideoPreload` /
`makeVideoStreaming` / `queryFreeSpace` / `withAesEcbDecrypt` / `withAeskey` / `withDebugIP` /
`withTimeout` / `withXorDecrypt`

⚠️ 备注：`withDebugIP` / `withTimeout` 的 acc=0x1（**非 static**，public 实例方法）——签名与其他 `with*` 不一致，疑为反混淆残留/多版本合并，真机 hook 时需按 dex 实际签名匹配。🟡推断

[DONE]

---

### Step 2 `CdnLogic$DownloadCallback` 接口方法（含参数类型）

命令：解析 classes3.dex 中 `CdnLogic$DownloadCallback` 的 class_data_item。

✅实测：`DownloadCallback` 定义于 **classes3.dex**，类访问标志 `0x601`（public | interface | abstract）。

| 方法 | 签名 | 形态 | 参数里有什么 |
|---|---|---|---|
| `onC2CDownloadCompleted` | `(Ljava/lang/String;Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;)V` | **abstract** acc=0x401 | ①`String fileid` ②`C2CDownloadResult`（**含 `picCachePath` 文件路径**、`errorCode`、`fileSize`、`recvedBytes`、`picIndex`、`batchImageFileKey`、`batchImageNeedRetry`、`realUsedURL`、`httpStatusCode`） |
| `onDownloadProgressChanged` | `(Ljava/lang/String;JJZ)V` | **abstract** acc=0x401 | ①`String fileid` ②`long` 已收 ③`long` 总量 ④`boolean` |
| `onDownloadNotifyPeerUploadFinish` | `(Ljava/lang/String;ZJ)V` | java（有默认实现 acc=0x1） | `String`, `boolean`, `long` |

> ⛔ **没有字节数组**：回调参数里**没有 `byte[]`**，图片不是以字节数组回传，而是由 native 层直接落盘，
> 回调只给**路径**（`C2CDownloadResult.picCachePath`）或**错误码**（`errorCode`）。
> ⛔ **没有 `filePath` 参数名**，但 `C2CDownloadResult.picCachePath` 就是落盘路径（见下）。

✅实测：`CdnLogic$C2CDownloadResult` 定义于 classes13.dex，**73 个字段**，关键几个：

```
errorCode            I                                  <- 错误码
detailErrorCode      I / detailErrorType I
picCachePath         Ljava/lang/String;                 <- ★ 图片落盘路径
picIndex             I
fileid               Ljava/lang/String;
fileSize J / recvedBytes J / currentFileSize J
realUsedURL          Ljava/lang/String;                 <- 真正请求的 URL（含鉴权参数？待验证）
httpStatusCode       I
batchImageFileKey    Ljava/lang/String;                 <- ★ 批量图片返回
batchImageNeedRetry  [Lcom/tencent/mars/cdn/CdnLogic$BatchSnsReqImageData;   <- ★ 需重试项
batchPicFeedId       Ljava/lang/String;
isSnsImageProtocolAvailable Z
usePrivateProtocol   Z
fileType I / argInfo / transforMsg / systemErrorDescribe / xErrorNo
```

#### 2.1 附：其它回调接口（✅实测）

`CdnLogic$VideoStreamingCallback`（classes12.dex，0x601 interface）：
`onDataAvailable(String,JJ)V` / `onDownloadToEnd(String,JJ)V` / `onM3U8Ready(String,String)V` /
`onMoovReady(String,JJ,VideoInfo)V` / `onMoovReadyWithFlag(String,JJ,String)V` /
`onPreloadCompletedWithResult(String,JJ,C2CDownloadResult)V` —— 全部 abstract。

`CdnLogic$AppCallback`（classes12.dex，0x601 interface）：
`isUsingWangKaService(I)Z` / `onBadNetworkProbed()V` / `onTaskTearDown(String,DownloadTaskProfile,String)V` /
`reportFlow(IIII)V` / `reportFlowWithTag(String,I,J,J,J,J)V` / `reportGroupIDKey([I,[I,[I,I,Z)V` /
`reportIDKey(J,J,J,Z)V` / `reportKV(J,String,Z,Z,J)V` / `reportKVNoFreqLimit(J,String)V` /
`requestGetCDN(I)V` / `requestSnsGetCdnDistance(III)V` / `resolveHost(String,Z,[I)[String` —— 全部 abstract。

#### 2.2 ⚠️ hook 可行性关键提醒

- 接口方法本身是 **abstract，没有实现体** ⇒ Xposed **不能** hook 接口声明（`findAndHookMethod("com.tencent.mars.cdn.CdnLogic$DownloadCallback", …)` 会因"没有实现"失败或永不触发）。
- 必须 hook **具体实现类的覆写方法**。🟡推断：微信侧实现类待 Step 4 反查调用点后确定（见 Step 4 产出）。
- 备选做法：用 `XposedHelpers.findAndHookConstructor` / 或 hook 所有 `implements DownloadCallback` 的类（枚举 dex 中 `C2CDownloadResult` 的读取点反推）。

✅实测补充：全 dex 扫描出**同时实现 `onC2CDownloadCompleted` 且有 code_item** 的类共 16 个，其中与本 lane 相关的：

```
Lok0/x0;   #onC2CDownloadCompleted (String, C2CDownloadResult)V   @classes13.dex   ★ CdnTransportEngine
Lok0/x0;   #onDownloadProgressChanged (String,JJZ)V                @classes13.dex
Lok0/x0;   #onDownloadNotifyPeerUploadFinish (String,ZJ)V          @classes13.dex
Lhj0/s;    #onC2CDownloadCompleted / #onDownloadProgressChanged    @classes3.dex
Ldj0/b; Le44/c; Lfg0/j0; Lsm/c; Lw42/h; Lxb2/h; Lz11/b; Lep3/d; Lpe2/i;   （其它业务）
Lcom/tencent/mm/plugin/collect/model/voice/h;  @classes9.dex
Lcom/tencent/mm/plugin/luckymoney/model/g3;    @classes9.dex
Lcom/tencent/mm/plugin/appbrand/video/player/thumb/z;  @classes13.dex
```

> ⇒ **`ok0.x0`（CdnTransportEngine）是微信 CDN 下载的统一回调中枢**，且它自己就 implements 了 `DownloadCallback`。

[DONE]

---

### Step 3 `BatchSnsReqImageData` 的构造点与读取点

命令：

```bash
# 3a 构造点
$PY tools/_r4_dexref.py callers classes3.dex 'Lcom/tencent/mars/cdn/CdnLogic$BatchSnsReqImageData;-><init>('
# 3b 字段读写点（自写扫描：遍历 15 个 dex 的 class_data -> code_item，按 dalvik 指令长度表逐条解码 0x52..0x6D）
```

#### 3.1 类定义（✅实测）

`CdnLogic$BatchSnsReqImageData` 定义于 **classes3.dex**，类访问标志 `0x1`，只有 1 个方法 `<init>()V`（java，有 code_item），7 个字段：

```
decryptKey       Ljava/lang/String;
fileKey          Ljava/lang/String;
imageCachePath   Ljava/lang/String;
picIndex         I
retry            Z
totalFileSize    I
url              Ljava/lang/String;
```

#### 3.2 构造点（✅实测，全 dex 唯一）

```
目标 method_idx: [4341]  Lcom/tencent/mars/cdn/CdnLogic$BatchSnsReqImageData;-><init>()V   @classes3.dex
=== 调用点（1 处）===
   Ljf3/f;
       -> Ljf3/f;->k([Ljava/lang/String;)Ljava/lang/Integer;
```

即 **只有 `jf3/f.k` 一个地方 `new` 它**。`jf3/f` 的真实类名 ✅实测 = `com.tencent.mm.plugin.sns.model.download.SnsCdnDownloadBase`
（其 `k()` 首条 const-string 为 `'doInBackground'` + `'com.tencent.mm.plugin.sns.model.download.SnsCdnDownloadBase'`），
`k([String)Integer` = AsyncTask 的 `doInBackground`。

#### 3.3 字段读写点（✅实测，全 15 dex 扫描结果）

```
######## classes3.dex  目标 field_id(7个): decryptKey/fileKey/imageCachePath/picIndex/retry/totalFileSize/url
   BatchSnsReqImageData  #<init>   iput(-object/-boolean)  url/fileKey/imageCachePath/decryptKey/picIndex/totalFileSize/retry   （默认值）
   Ljf3/d;               #C2       iget          picIndex         x5
   Ljf3/d;               #C2       iget-boolean  retry            x1
   Ljf3/f;               #k        iput          picIndex         x1
   Ljf3/f;               #k        iput          totalFileSize    x1
   Ljf3/f;               #k        iput-object   decryptKey       x1
   Ljf3/f;               #k        iput-object   fileKey          x1
   Ljf3/f;               #k        iput-object   imageCachePath   x1
   Ljf3/f;               #k        iput-object   url              x1
```

其余 14 个 dex **无**任何 `BatchSnsReqImageData` 字段引用。

**解读（✅实测支撑）**：
- **写入方唯一**：`jf3/f.k`（SnsCdnDownloadBase.doInBackground）—— 它 `new` 出来后立刻把 6 个字段全部赋值（`retry` 除外，只走 `<init>` 默认值）。
- **读取方唯一**：`jf3/d.C2(String,int,tj.e,tj.f,boolean)int` —— 读 `picIndex` 和 `retry`。
  `jf3/d` ✅实测构造于 `jf3/f.k` 内（`new-instance Ljf3/d;`，`<init>(Ljf3/f;Ltj/h;J)V`），是 `jf3/f` 的内部回调对象。
  ⚠️ `jf3/d.C2` 在 dex 内**直接调用点 = 0** ⇒ 🟡推断：由接口/native 侧多态回调触发（即"批量下载完成回调"）。

#### 3.4 "批量下载图片"这条链路通不通 —— ✅实测链路还原

```
jf3/f.k([String)Integer            (SnsCdnDownloadBase#doInBackground)
   ├─ new-instance  Lcom/tencent/mars/cdn/CdnLogic$BatchSnsReqImageData;      [806]
   ├─ invoke        BatchSnsReqImageData-><init>()V                           [808]
   ├─ iput-object   BatchSnsReqImageData->url / decryptKey / imageCachePath / fileKey
   ├─ iput          BatchSnsReqImageData->picIndex / totalFileSize
   └─ iput-object   Ltj/g->l1 : [Lcom/tencent/mars/cdn/CdnLogic$BatchSnsReqImageData;   [783]
                    ↓
ok0/x0.n(Ltj/g)I                    (CdnTransportEngine，classes13.dex)   ★批量入口
   ├─ new-instance  CdnLogic$C2CDownloadRequest
   ├─ iget  Ltj/g->i1  -> iput C2CDownloadRequest->feedId
   ├─ iget  Ltj/g->j1  -> iput C2CDownloadRequest->feedPicCount
   ├─ iget  Ltj/g->l1  -> iput C2CDownloadRequest->batchSnsReqImageDatas      ★
   ├─ iput  fileKey/url/referer/isColdSnsData/signalQuality/snsScene/
   │        concurrentCount/bizid/apptype/fileType/downloadBehavior/msgType/debugIP
   └─ invoke  CdnLogic->startSNSDownload(req, videoCb, downloadCb, int)I      [110]   -> NATIVE
                    ↓
(回调) ok0/x0.onC2CDownloadCompleted(String fileid, CdnLogic$C2CDownloadResult result)
                    ↓
(批量结果消费) jf3/d.C2(...)  —— 读 BatchSnsReqImageData.picIndex / retry
```

✅实测：`Ltj/g` 字段（classes13.dex）= `i1:String`(feedId) / `j1:I`(feedPicCount) / `l1:[BatchSnsReqImageData`。
✅实测：`ok0/x0.n` 的**直接调用方** = `ok0/j.invoke(Object)` 与 `ok0/m1.m(boolean)`（classes7.dex，Kotlin lambda 包装）。
✅实测：批量开关 `CdnLogic.allowBatchImageDownload()` 的唯一调用方 = `com.tencent.mm.plugin.sns.ui.dk.a()`（classes3.dex）。

> **结论**：链路在代码层面**是通的**（构造 → 传参 → 调 native → 回调 → 消费，全链路闭环，无断点）。
> ⛔但**运行时是否真的走这条路径**（服务端 `allowBatchImageDownload` 是否放行、`isSnsImageProtocolAvailable` 等）**静态无法判定**，必须真机验证。

[DONE]

---

### Step 4 `CdnLogic->startSNSDownload(` 的调用点

命令：

```bash
for d in classes3.dex classes13.dex classes9.dex classes7.dex classes12.dex classes2.dex; do
  $PY tools/_r4_dexref.py callers $d 'Lcom/tencent/mars/cdn/CdnLogic;->startSNSDownload('
done
```

✅实测：全 dex 共 **12 处**调用点。

| dex | 调用类 # 方法 | 备注 |
|---|---|---|
| classes13 | `ok0/x0` # `j(Ltj/m;)I` | CdnTransportEngine |
| classes13 | `ok0/x0` # `m(Ltj/m;)I` | CdnTransportEngine |
| classes13 | **`ok0/x0` # `n(Ltj/g;)I`** | ★ **批量 SNS 图片入口**（`tj/g.l1` = BatchSnsReqImageData 数组） |
| classes13 | `ok0/x0` # `o(Ltj/h;)I` | CdnTransportEngine |
| classes13 | `ok0/x0` # `p(Ltj/m;)I` | CdnTransportEngine |
| classes13 | `ok0/x0` # `r(String,String,String,int,String,String,int,boolean)I` | const-string `'startURLDownload: mediaid:%s, savepath:%s'` |
| classes13 | `com.tencent.mm.plugin.appbrand/video/player/thumb/z` # `b(Ltj/k;I)I` | 小程序视频缩略图 |
| classes3 | `hj0/t` # `invokeSuspend(Object)Object` | Kotlin 协程路径（同时它也是 `createSNSDownload` 的调用方） |
| classes9 | `com.tencent.mm.plugin.luckymoney.model.f4` # `b(String,String)V` | 红包 |
| classes7 | `ok0/j` # `invoke(Object)Object` | Kotlin lambda |
| classes7 | `ok0/m1` # `m(boolean)V` | |
| classes7 | `ok0/m2` # `r(boolean)V` | |

✅实测：`ok0/x0` 的真实类名 = **CdnTransportEngine**（其方法内 const-string `[15759] 'MicroMsg.CdnTransportEngine'`）。
✅实测：`ok0/x0` 是唯一**同时**（a）调用 `startSNSDownload` 6 次、（b）自己 implements `DownloadCallback` 的类 ⇒ **微信 CDN 下载的统一中枢**。

附带 ✅实测：`CdnLogic->createSNSDownload(` 全 dex 仅 **2 个**调用点
（`hj0/t.invokeSuspend` @classes3；`CdnLogic.createGroupNoticeDownload` 内部调用 @classes13）
⇒ **主链路不经过 `createSNSDownload`**，它是弱 hook 点（见清单 #9）。

[DONE]

---

### Step 5 hook 点可行性补充说明

- ✅实测：**所有 `start*Download` / `start*Upload` 均为 `ACC_NATIVE`（0x109）**，无 Java 实现体，
  ⇒ **Xposed 不可直接 hook**。要拦下载入口，必须 hook **Java 侧调用方**（`ok0/x0.n/j/m/o/p/r`）。
- ✅实测：`DownloadCallback` 的 3 个方法里有 2 个是 `ACC_ABSTRACT`（0x401），1 个（`onDownloadNotifyPeerUploadFinish`）在接口里有默认实现。
  ⇒ 接口声明本身不可 hook，必须 hook **实现类**（`ok0/x0` 等）。
- 🟡推断：`picCachePath` 是"图片解密后落盘路径"（字段名直指 cache path），但**是否在 400 失败时也非空**需真机验证。
- ✅实测：回调参数里**没有 `byte[]`**，图片不经 Java 层字节数组回传 ⇒ 拿数据的唯一方式是拿**路径**后自己读文件。
- ⚠️ 混淆类名 `ok0/x0`、`jf3/f`、`jf3/d`、`tj/g` 在真机上需按**当前包内实际签名**匹配；`SnsMethodCalculate.markStartTimeMs` 的 const-string 是可用的**类名锚点**（`com.tencent.mm.plugin.sns.model.download.SnsCdnDownloadBase`、`MicroMsg.CdnTransportEngine`）。

[DONE]

---

## 待真机验证项

1. ⛔ **`ok0.x0` / `jf3.f` 的运行时类名与 dex 是否一致**——LSPosed hook 时用 `ok0.x0` 还是别的 obfuscated 名（不同微信小版本会变）。建议先用 `SnsMethodCalculate` 的 log tag 或 `MicroMsg.CdnTransportEngine` 定位。
2. ⛔ **`C2CDownloadResult.picCachePath` 是否真的非空且可直接读**——这是"拿到图片文件"的关键。若为空，退而求其次看 `fileid` + `savePath`/`imageCachePath` 反推。
3. ⛔ **失败时 `errorCode` 的值域**——裸 url 恒 400 的对应 `errorCode` / `httpStatusCode` 是多少，能否区分"鉴权失败"与"网络失败"。
4. ⛔ **`jf3/d.C2` 到底由谁触发**（dex 内 0 直接调用点）——若真机从不触发，说明批量链路被服务端/开关关闭。
5. ⛔ **`allowBatchImageDownload()`（native）运行期返回值**——若恒 false，批量链路实际不通，应改走单图路径（`ok0/x0.j/m/p` 传 `tj/m`）。
6. ⛔ **`ok0/x0.n`（批量）vs `ok0/x0.j/m/p`（单图，传 `tj/m`）到底走哪条**——需在朋友圈划图时打点看哪个方法被触发。
7. ⛔ **改 `BatchSnsReqImageData.imageCachePath` / `C2CDownloadRequest.savePath` 是否能把图片落到可读目录**（SDCARD 权限/沙箱限制）。
8. ⛔ **native hook 兜底方案**：若必须拦 `startSNSDownload`，需换 Dobby/AndHook 之类的 inline hook 挂 `libmarsxlog`/`libstlport`/mars so 中的 JNI 实现，静态层面**未定位到对应 so 与符号**（本 lane 未做 so 分析）。
9. 🟡 `withDebugIP` / `withTimeout` 的访问标志为 `0x1`（public **非 static**），与其它 `with*` 的 `0x9` 不一致——真机按签名匹配时注意。

