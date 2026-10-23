# R14 CDN 回调取证（只读）

## Q1: `ok0.x0#onC2CDownloadCompleted` 真实签名

✅ **确证（DEX class_defs 直读，非 grep）**：2 个参数，无重载。

```
类：Lok0/x0;   DEFINED in _cache/dex/classes13.dex  class_defs#4301  super=Ljava/lang/Object;  access=0x0001
方法：onC2CDownloadCompleted (Ljava/lang/String;Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;)V   acc=0x0001
     → arg[0] = String fileid
     → arg[1] = C2CDownloadResult   ★「结果对象」就在第 2 位（args[1]），位置没错
返回：void
```

复现命令（⚠️ 第三条 yrgs 后面别加名字子串，shell `;` 会被当命令分隔符）：

```bash
PY=/c/Users/lx/.workbuddy/binaries/python/versions/3.13.12/python
cd /c/Users/lx/WorkBuddy/2026-09-29-20-23-21
"$PY" _cache/_r14_fields.py methods 'Lok0/x0;'       # 打印 67 个方法的完整描述符
```

⇒ **结论：探针里 `param.args.getOrNull(1)` 取结果对象是【对的】**，`pic=none` 不是参数位错。
（同类的 `onDownloadProgressChanged` 实测 `(Ljava/lang/String;JJZ)V`，与接口声明一致 ⇒ 解析器可信。）

### ⚠️ 顺带修掉的一个工具 bug（会影响此前所有跟_params_有关的结论）

`tools/_r13_findclass.py` 的 `Dex._proto()` 用 **uleb128** 解析 proto 的参数列表，但 DEX 的
`type_list` 实际是 `{ u4 size; u2 items[]; }`。后果：**所有形参都被解析成 type_idx=0**
（本批 dex 里 type 0 恰好是 `B`）。症状就是每个方法都显示成 `(BB)V` / `(BBB)V`：

```
   onC2CDownloadCompleted    (BB)V        ← 修之前看到的样子（假）
   onC2CDownloadCompleted    (Ljava/lang/String;Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;)V  ← 修之后
```

我在临时脚本 `_cache/_r14_fields.py` 里重写了 `_proto()`（size=`u4`，元素=`u2`），**未改 tools 下的正式脚本**。
⇒ 之前任何「靠 `methods_of()` 判参数个数/类型」的结论都建议用修好的版本重跑一遍（参数**个数**不受影响，
因为 size 的第一个字节碰巧读对了；只有**类型**是假的）。


## Q2: 结果类真实类名 + 字段清单（重点是落盘路径字段）

### 2.1 真实类全名 / 所在 dex

✅ **确证**：**类名没被改，跟代码里写的一模一样。**

```
全名：Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;
定义：_cache/dex/classes13.dex   class_defs#1569      （全 15 个 dex 里只此一处定义）
父类：Ljava/lang/Object;      access = 0x0001 (public)
static_fields   = 0
instance_fields = 73
methods         = 1   →  只有 <init>()V（public，无其它方法；纯数据壳）
```

复现命令（脚本是我这轮新写的，复用 `tools/_r13_findclass.py` 的 Dex）：

```bash
PY=/c/Users/lx/.workbuddy/binaries/python/versions/3.13.12/python
cd /c/Users/lx/WorkBuddy/2026-09-29-20-23-21
"$PY" _cache/_r14_q2_fields.py 'Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;'
```

同批 `CdnLogic$*Result` 只有 5 个（都只在 classes13.dex / classes12.dex 定义一次，无重复定义）：

```
classes12.dex def#564   CdnLogic$ApplyFileidResult
classes13.dex def#1569  CdnLogic$C2CDownloadResult     ★
classes13.dex def#1571  CdnLogic$C2CUploadResult
classes13.dex def#1573  CdnLogic$CertVerifyResult
classes13.dex def#1574  CdnLogic$CronetTaskResult
```

⚠️ 另外 `classes13.dex def#635` 有个 **`Lcom/tencent/ilink/network/Proto$IlinkC2CDownloadResultOrBuilder;`**
（protobuf 接口，字段 0 / 方法 21，只有 `getTaskIdent/getFileSize/getRecvedBytes` 这类），
**它不是** CDN 结果类，名字里虽然也带 `C2CDownloadResult`，别被 grep 骗了。

⇒ Q1 的签名 `...CdnLogic$C2CDownloadResult;)V` 与这里对得上，**类名、dex 都没问题**。

### 2.2 实例字段清单（字段名 + 类型）

73 个实例字段、**全部 public、无 static 字段**（按 `class_data_item` 声明顺序，= dex 里 field_idx 升序）。
出处：`classes13.dex` def#1569 的 `class_data_item → encoded_field[]`（每项 2 个 uleb：field_idx_diff + access_flags）。

| 字段 | 类型 |
|---|---|
| argInfo | `Ljava/lang/String;` |
| availableBytes | `J` |
| averageConnectCost | `I` |
| averageRequestCost | `I` |
| averageRequestSize | `I` |
| averageSpeed | `I` |
| batchImageFileKey | `Ljava/lang/String;` |
| batchImageNeedRetry | `[Lcom/tencent/mars/cdn/CdnLogic$BatchSnsReqImageData;` |
| batchPicFeedId | `Ljava/lang/String;` |
| cSeqCheck | `I` |
| clientIP | `Ljava/lang/String;` |
| connectCostTime | `I` |
| cronetTaskResult | `Lcom/tencent/mars/cdn/CdnLogic$CronetTaskResult;` |
| crossNet | `Z` |
| currentFileSize | `J` |
| delayTime | `I` |
| detailErrorCode | `I` |
| detailErrorType | `I` |
| dnsCostTime | `I` |
| enQueueTime | `J` |
| endTime | `J` |
| errorCode | `I` |
| fileSize | `J` |
| fileType | `I` |
| fileid | `Ljava/lang/String;` |
| firstConnectCost | `I` |
| firstRequestCompleted | `Z` |
| firstRequestCost | `I` |
| firstRequestDownloadSize | `I` |
| firstRequestSize | `I` |
| fromCronet | `Z` |
| httpResponseHeader | `Ljava/lang/String;` |
| httpStatusCode | `I` |
| isResume | `Z` |
| isSnsImageProtocolAvailable | `Z` |
| lastNetType | `I` |
| lastSvrPort | `I` |
| moovCompleted | `Z` |
| moovCost | `I` |
| moovFailReason | `I` |
| moovRequestTimes | `I` |
| moovSize | `I` |
| netConnectTimes | `I` |
| **picCachePath** | `Ljava/lang/String;` |
| picIndex | `I` |
| previousCompletedSize | `I` |
| profile | `Ljava/lang/String;` |
| realUsedURL | `Ljava/lang/String;` |
| receiveCostTime | `I` |
| recvedBytes | `J` |
| requestCompletedCount | `I` |
| requestTimeoutCount | `I` |
| requestTotalCount | `I` |
| serverIP | `Ljava/lang/String;` |
| snsVideoInfo | `Lcom/tencent/mars/cdn/CdnLogic$VideoInfo;` |
| snsVideoProfile | `Lcom/tencent/mars/cdn/CdnLogic$SNSVideoProfile;` |
| startTime | `J` |
| svrFallbackCount | `I` |
| systemErrorDescribe | `Ljava/lang/String;` |
| taskStartTime | `J` |
| traceId | `J` |
| traceMsg | `Ljava/lang/String;` |
| transforMsg | `Ljava/lang/String;` |
| transportProtocol | `I` |
| transportProtocolError | `I` |
| tryWritenBytes | `J` |
| usePrivateProtocol | `Z` |
| usedSvrIps | `[Ljava/lang/String;` |
| videoCdnMsg | `Ljava/lang/String;` |
| videoFlag | `Ljava/lang/String;` |
| videoFormat | `I` |
| waitResponseCostTime | `I` |
| xErrorNo | `Ljava/lang/String;` |

代码里现在读的 6 个字段（`SnsCdnImage.kt:244-250`）**全部在表内且类型匹配**：

| 代码 | 字段 | dex 实际类型 | 判定 |
|---|---|---|---|
| `getIntField(r,"errorCode")` | errorCode | `I` | ✅ |
| `getObjectField(r,"picCachePath") as? String` | picCachePath | `Ljava/lang/String;` | ✅ 名字/类型都对 |
| `getLongField(r,"fileSize")` | fileSize | `J` | ✅ |
| `getLongField(r,"recvedBytes")` | recvedBytes | `J` | ✅ |
| `getIntField(r,"picIndex")` | picIndex | `I` | ✅ |
| `getObjectField(r,"batchPicFeedId")` | batchPicFeedId | `Ljava/lang/String;` | ✅ |
| `getObjectField(r,"batchImageNeedRetry") as? Array<*>` | batchImageNeedRetry | `[…$BatchSnsReqImageData;` | ✅ |

### 2.3 落盘路径字段判定

✅ **此类【有】路径字段，且只有一个：`picCachePath`（`Ljava/lang/String;`）。名字没改、没被混淆、没搬家。**

但真正的问题不是「字段名错」，而是——**这个字段在 Java 层根本没人写真实值**。

#### 证据 A：全 15 个 dex 里，`picCachePath` 只有 1 条写指令、0 条读指令

对 `field_ids` 里 `CdnLogic$C2CDownloadResult.picCachePath` 的 field_idx，
在**每个 dex 的原始字节**里滑窗搜 `iget-object(0x52)/iput-object(0x5B)/sget-object(0x62)/sput-object(0x6B)`：

```
classes13.dex   field_idx=7391   iput-object  x1
（其余 14 个 dex：field_ids 里根本没有这个字段）
TOTAL = 1 条写，0 条读
```

复现：`"$PY" _cache/_r14_q2_refs.py 'picCachePath' 'Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;'`

#### 证据 B：那唯一一条写在 `<init>` 里，写的是空串 `''`，不是路径

把 classes13.dex 的 code_item 反汇编（脚本 `_cache/_r14_q2_init.py`，只解 `<init>` 会用到的 opcode）：

```
code_off = 0x238288   insns_size = 153 (u2) = 306 字节
命中：iput-object picCachePath  Ljava/lang/String;   this=v5 <= v3 = ''
       insn_off = 0x23837a
其中 v3 来自:  1a 03 00 00   const-string v3, string@0x0000
而 classes13.dex 的 strings[0] == ''
```

⇒ **构造函数把它初始化成空串 `''`**。71/73 个字段都在 `<init>` 里被赋默认值
（数值=0、String=`strings[0]`=''）；只有 `isSnsImageProtocolAvailable`、`usedSvrIps` 两个没在 `<init>` 里赋值。

#### 结论

| 项 | 判定 |
|---|---|
| 字段名错了？ | ❌ 没错，dex 里就是 `picCachePath` |
| 字段在别的类里？ | ❌ 就在这个类，`classes13.dex def#1569` |
| Java 层有人填真实路径？ | ❌ 全 dex 只有 `<init>` 里写 `''` |
| ⇒ 谁该填它？ | **native（Mars / lib 层）用 JNI `SetObjectField` 填**；dex 里看不到是必然的 |

**真机读到 `pic=none` 的最合理解释**：native 没给 `picCachePath` 赋值 ⇒ 字段停留在 `<init>` 的默认 `''`。
注意 `'' ≠ null`：`SnsCdnImage.kt:245` 的 `as? String` 拿到的是 `""`，
`SnsCdnProbe.kt:396` 打 `pic=none` 是因为 `File("").parentFile == null` ⇒ `lastPicParent` 为 null。
⇒ **「静默 null」不是「字段不存在/读不到」，是「字段存在但值是空串」**。
（XposedHelpers 若真找不到字段会抛 `NoSuchFieldError`，被 `runCatching` 吞掉才是「静默」；本轮静态取证不支持这种可能。）

### 2.4 有路径字段但拿不到值 → 下一步去哪找

（2.3 已确认有路径字段，所以这里不是「无字段」分支，而是「字段有、值空」的下一步。）

#### 候选 1（★ 最该去的地方）：请求侧的 `BatchSnsReqImageData.imageCachePath`

「结果侧」的 `picCachePath` 是 native 填的、Java 层零写入；
而「请求侧」的同语义字段是 **Java 代码自己填的**，能看到真实落盘路径：

```
类：Lcom/tencent/mars/cdn/CdnLogic$BatchSnsReqImageData;
字段（6 个，全 public）：
   decryptKey      Ljava/lang/String;
   fileKey         Ljava/lang/String;
   imageCachePath  Ljava/lang/String;      ★ 真正的落盘路径
   picIndex        I
   retry           Z
   totalFileSize   I
   url             Ljava/lang/String;
```

写点（classes3.dex，`iput-object` 全 dex 只有 2 条）：

```
classes3.dex  field_idx=1964
  HIT Lcom/tencent/mars/cdn/CdnLogic$BatchSnsReqImageData; <init> ()V        insn=0x237290   ← 默认 ''
  HIT Ljf3/f;  k ([Ljava/lang/String;)Ljava/lang/Integer;  insn=0x62d250      ← ★ 真实路径在这里写
```

⇒ `jf3.f#k([String)Integer` 就是 **已经在代码里的 `jf3.f#k` 钩子**（`SnsCdnProbe.kt:217`）。
现在它只当「计数探针」，但真正该抓的是它构造出来的 `BatchSnsReqImageData` 的 `imageCachePath`。
⚠️ 该文件头注释说「它是方法体局部变量，反射拿不到」——建议复核：局部变量在 `before` 拿不到，
但可以在 `after` 里取 `param.result`（返回 `Integer` 没用），或者退一步 hook `ok0.x0#n(Ltj/g;)I`
的 **before** 反射 `tj/g.l1[]` 数组元素（数组元素是**堆对象**，能拿到），读每个元素的 `imageCachePath`。
这条路径的路径值由微信 Java 侧拼出，**不依赖 native 是否填 `picCachePath`**。

#### 候选 2：`arg[0]` 那个 String 是不是路径？

**❌ 不是。** 它是 `fileid`：

- Q1 已确认签名第 1 参是 `Ljava/lang/String;`；
- 结果类自己也有同名字段 `fileid : Ljava/lang/String;`（`classes13.dex` field_idx=7372，同样只有 `<init>` 里 1 条写）；
- 语义上 CDN 回调的 arg[0] 是 task/fileid，与落盘路径无关。
⇒ **别把 arg[0] 当路径用。**

#### 候选 3：同一个接口上的另外两个回调

`Lok0/x0;` 里带 `C2CDownloadResult` 的回调不止一个：

```
onC2CDownloadCompleted          (Ljava/lang/String;Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;)V
onPreloadCompletedWithResult    (Ljava/lang/String;JJLcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;)V   ← 同类结果对象
keep_onDownloadProgress         (Ljava/lang/String;Ltj/e;)I
onDownloadProgressChanged       (Ljava/lang/String;JJZ)V
```

`onPreloadCompletedWithResult` 传的是**同一个** `C2CDownloadResult`，若「预加载」通路与「批量下载」通路
在 native 侧共用填值代码，可以拿它做交叉验证：如果它在真机上也 `picCachePath == ''`，
就基本坐实「本版本 native 不填这个字段」，那时应该彻底改用候选 1。

#### 候选 4（兜底）：不靠回调，直接按约定拼路径

`C2CDownloadResult` 里能确定拿到的是 `fileid` / `batchImageFileKey` / `picIndex` / `fileType`，
配合 `BatchSnsReqImageData.imageCachePath` 的目录（微信自己算的），在宿主进程里
`ls` 该目录按 mtime 取最新文件。**这是纯 Java、不依赖 native 字段**，作为降级方案最稳。

#### 一句话

> `picCachePath` 字段**存在且名字正确**，但它只被 `<init>` 写成 `''`，
> **Java 层没有任何代码给它赋真实路径**（全 dex 仅 1 条 `iput`、0 条 `iget`），
> 真实路径在 native 侧；真机读到空串 ⇒ 本版本 native 没填。
> ⇒ 下一步优先改抓 **请求侧 `BatchSnsReqImageData.imageCachePath`（写点 `jf3.f#k` / `ok0.x0#n` 的 `l1` 数组）**。

---

## 本轮新增的可复用脚本（都在 `_cache/`，未动 `tools/` 与任何 `.kt`）

| 脚本 | 作用 |
|---|---|
| `_cache/_r14_q2_fields.py` | 按类名子串列 static/instance 字段 + 方法（复用 `tools/_r13_findclass.py` 的 Dex） |
| `_cache/_r14_q2_refs.py` | 传 (字段名, 类名)，统计全 dex 的 `iget/iput/sget/sput-object` 命中数 |
| `_cache/_r14_q2_where.py` | 定位某条字段写指令属于哪个方法（classes13.dex 专用常量） |
| `_cache/_r14_q2_init.py` | 反汇编 `C2CDownloadResult.<init>()V`，列出 73 字段的默认值 |

复现：

```bash
PY=/c/Users/lx/.workbuddy/binaries/python/versions/3.13.12/python
cd /c/Users/lx/WorkBuddy/2026-09-29-20-23-21
"$PY" _cache/_r14_q2_fields.py 'Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;'
"$PY" _cache/_r14_q2_refs.py   'picCachePath' 'Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;'
"$PY" _cache/_r14_q2_refs.py   'imageCachePath' 'Lcom/tencent/mars/cdn/CdnLogic$BatchSnsReqImageData;'
"$PY" _cache/_r14_q2_init.py
```

[DONE]
