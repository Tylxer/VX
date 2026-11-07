# r44 · 后台视频下载 Route C 链路推导与判据设计

> lane: 静态取证（Hook 链路推导 + 判据设计）
> 目标: 全后台无感知保存指定好友朋友圈视频（文字+图片已可，只差视频）
> 起点: `Lid3/e0;#run` → `f4#ea` → `l0#d`（DownloadManager）

## 关键结论速览

- **Route C 判定: 可行**。模块内 `f4#ea()` 取 `l0` 单例 → `SnsInfo.getTimeLine().ContentObj.h` 取 `vm3` → `l0.d(vm3, vm3.e, new bf3/m0(), x7.c)`，全后台无 UI 无闪屏。唯一注意：在带 Looper 的后台线程触发（否则 `l0#h` 走 `checkIdleFile`+`o3#postDelayed` 兜底，也能下载）。
- 发网络的是 `l0#z`(tryStartNetscene)，sight 走 `Ljf3/l`(SnsDownloadSight) + `c3`(SnsCdnDownloadBase) CDN 通道；native 解密在此链路内。
- `m0`=SnsDecodeElment（可传 null）；`x7`=场景标签（传 `x7.c`=timeline）。

---

## 第 1 节 · `l0#f / g / h / i / t` 的完整反汇编与语义 [DONE]

### 1.0 调用链总览（已实测反汇编，classes3.dex）

```
l0#d  (21 units)  addDownLoadSns    → const/4 v7,0x7 → invoke l0#e
l0#e  (33 units)  addDownLoadSns    → 补 '' '' 0xa → invoke l0#f
l0#f  (134 units) addDownLoadSns    → 判空 → invoke l0#h
l0#g  (109 units) addDownLoadSnsAd  → 补 '' '' 0x8 → invoke l0#h   (广告分支)
l0#h  (570 units) addDownLoadSnsImpl → 真实实现：入队 + 触发下载
l0#z  (1743 units) tryStartNetscene  → 真正发 CDN 请求
l0#t  (710 units) onTaskFinish       → CDN 回调（下载完成/失败）
l0#i  (293 units) addDownloadStreamVideoTask → 流视频(streamvideo)分支，走 Ltj/m
```

### 1.1 形参语义（关键结论）

- **`l0#d(vm3, int, m0, x7)Z`**：第 2 个 int 参数 = `vm3.e`（媒体类型）。证据：调用者 `d1#f0` 里 `iget v116, v7, Lq54/vm3;->e:I` 后紧接 `invoke l0;->d`。d 内部只 `const/4 v7,0x7` 后原样透传给 e。
- **`l0#e(vm3,int,m0,x7,String)`**：把 5 个入参 + 新增 `""`(v8)、`""`(v9)、`0xa`(v10) 共 8 参传给 f。
- **`l0#f(vm3,int,m0,x7,String,String,String,int)`**：
  - 开头 `if-nez vm3` → log `"unknow case media is null"` + `return false`（**vm3 判空返回**）。
  - 随后读 `vm3.d`（媒体 URL），`p7#f`（TimelineSmallPicStat.setScene），`x7.equals(x7.c)` 判断是否 timeline 场景。
  - **f 的第 5/6/7 个 String 形参在 d 链路上全是空串**（e 传的 `'' ''`），第 8 个 int = `0xa`(10)。
  - 末尾 `invoke l0#h`。
- **`l0#h(vm3,int,m0,x7,String,String,String,String,String)`** = `addDownLoadSnsImpl`，是真正实现：
  - `f4#B8`(getAccPath) + `bf3/t0#h0` 判断 **isHasSdcard**；false → `return 12`（`const/4 v12,0xc`）。
  - `vm3.d.startsWith("Locall_path")` 或 `startsWith("pre_temp_sns_pic")` → 视为本地图片 → log `"is a local img not need download"` + `return 0`。
  - `bf3/t0#I(vm3.d)` 生成 **requestKey**。
  - 入队：`l0.m`(HashMap, key=vm3.d→elapsedRealtime)、`l0.o`(Map)、`l0.l`(LinkedList, 元素=`bf3/n0`)。
  - **`Lbf3/n0`** = `SnsDownLoadElment`，字段 `a=vm3, b=int(reqDownloadType), c=requestId, d/e/g/h=String, f=x7, i=snsId, j=SparseArray, k=int`。
  - `Looper.myLooper()` 非空 → `MessageQueue.addIdleHandler(k0)`；**为 null → `l0#l`(checkIdleFile，内部 `o3#postDelayed`)**。说明 **h 对 Looper 有兜底，后台线程可走**。
  - 最后 `l0.l.size()>0` → `l0#z`(tryStartNetscene)。

### 1.2 谁真正把下载请求发出去（⭐ 核心答案）

**是 `l0#z`（tryStartNetscene），不是 f/g/h/i 直接发。**

`l0#z` 里，从 `l0.l`(LinkedList) `removeLast` 取 `bf3/n0` 元素，按 `n0.b`(reqDownloadType) 分支：

- 单媒体分支（`if-eqz v6` → "try start download with single media"）：
  - `new Ljf3/a`（`DownloadItem`），填 `a=vm3`、`c=url`(vm3.d 或 vm3.x)、`l=x7`、`s/t=requestId`。
  - `setSight` → `Ljf3/a.e=true`；`setThumb` → `d=true`；`setUrl`。
  - 按 `vm3.e`(type) 分支创建 DownloadTask：
    - sight 视频 → `new Ljf3/l`（**SnsDownloadSight**，`getMediaType` 返回 `0x20`=32）
    - image → `new Ljf3/j`（SnsDownloadImage）
    - thumb → `new Ljf3/m`（SnsDownloadThumb）
    - common → `new Ljf3/h`（SnsDownloadCommonMedia）
  - DownloadTask 基类 = **`Ljf3/f` → super `com/tencent/mm/pluginsdk/model/c3`（SnsCdnDownloadBase）**，通过 `c3#f`（invoke-virtual 于 `l0#a`/`z` 内 `invoke-virtual c3;->f`）**启动 CDN 下载**。这是微信 SNS 的 CDN 下载通道，native 解密在此链路内完成。
- 批量分支（`if-eq v43,0x9` → "batch download"）：走 SparseArray 遍历多 vm3。
- 流视频分支：`l0#i` 内 `new Ltj/m`（`task_DownloadManager`，`field_mediaId`、`field_fileType=100`、`field_requestVideoFormat`），最终 `bm0/m2#F8` → `ok0/m2#e`。

**没有出现 `Ltj/k`/`Ltj/l`/`ok0/x0`/`mq/k#x8` 作为 sight 主通道**——sight 主通道是 `c3`(SnsCdnDownloadBase) + `Ljf3/l`。流视频(`streamvideo`/`vm3.w`)才走 `Ltj/m`(via `l0#i`)。

### 1.3 `bf3/m0` 的角色

`Lbf3/m0` = **`SnsDecodeElment`**（日志类名 `com.tencent.mm.plugin.sns.data.SnsDecodeElment`）：
- 字段：`a=List`(mediaList)、`b=String`(requestId)、`c=int`(decodeType)。
- 方法：`a()`=getDecodeType、`b()`=getMediaList、`c()`=getRequestId、`d(int)`=setDecodeType、`e(String)`=setRequestId。
- 两个构造器：`<init>()`（空 LinkedList）、`<init>(List)`。

**关键结论**：`m0` 是"解码上下文"，`l0#f/g/h` 签名里都带它，但**反汇编中 grep `bf3/m0` 计数为 0**——即 f/g/h 内部根本不读 m0 的字段，只是透传。它最终落进 `Ljf3/a.h`(DownloadItem.m0)。调用方 `d1#f0` 是 `new Lbf3/m0; m0.d(0); m0.e(requestId)` 构造的。⇒ **m0 传 null 或空构造均可**。

### 1.4 `l0#i`（流视频专用）

`addDownloadStreamVideoTask`，`new Ltj/m` + 一堆 `iput`(field_fileType=100, field_requestVideoFormat 按 H265 能力算) + `bm0/m2#F8→ok0/m2#e`。与 sight 主通道无关，是 `streamvideo` 类型视频的旁路。

### 1.5 `l0#t`（回调）

`onTaskFinish`：CDN 完成回调。读 `vm3.d`，按 state 分支，更新 `l0.f`(HashMap time)、回调 `h0`(listener) 的 `onSightFinish`/`onImageFinish`/`onThumbFinish`，`f4#m9`(LazyerImageLoader2) 的 `updateCache`(`d1#c → oe0/h#u`)。**对 Route C 无阻塞**，只是完成后的通知。

## 第 2 节 · `l0#d` 的调用者全表 [DONE]

`_r27_callers.py --class l0 --method d`（跑全 15 dex，约 60s，结果去重后）真实调用者如下（排除 MJExportProject/nativeCreate 等 Maas 误匹配）：

| 调用者类 | 方法 | 指令偏移 | 性质 |
|---|---|---|---|
| `Lid3/e0;` | `run` | 0x12 | 已知起点（后台刷新朋友圈） |
| `Lcom/tencent/mm/plugin/sns/model/d1;` | `f0` | 0x89 | **⭐ LazyerImageLoader2（preload 侧）** |
| `Lcom/tencent/mm/plugin/sns/model/e1;` | `run` | 0x12 | 后台任务 |
| `Lcom/tencent/mm/plugin/sns/model/h1;` | `run` | 0xc3 | 后台任务 |
| `Lcom/tencent/mm/plugin/sns/model/q1;` | `run` | 0x11b | 后台任务 |
| `Lcom/tencent/mm/plugin/sns/model/t1;` | `run` | 0x4c | 后台任务 |
| `Lcom/tencent/mm/plugin/sns/ui/SnsCommentDetailUI$$b;` | `onClick` | 0x182 | UI 点击 |
| `Lcom/tencent/mm/plugin/sns/ui/bi;` | `j` | 0xa3 | UI |
| `Lid3/k1;` | `run` | 0x31 | 后台任务 |
| `Lid3/l1;` | `run` | 0x45 | 后台任务 |
| `Lid3/z;` | `run` | 0x12 | 后台任务 |
| `Ln00/u5;` | `callback` | 0x49/0x54/0xb6/0xc1 | 回调 |
| `Lnd3/f;` | `e` | 0xa55/0xaff | 网络回调 |
| `Lcom/tencent/mm/plugin/sns/ui/OnlineVideoView;` | `N` | 0x6b | ⭐ **播放器侧** |
| `Lcom/tencent/mm/plugin/sns/ui/SnsCommentDetailUI;` | `W6` | 0x122 | UI 详情 |
| `Lcom/tencent/mm/plugin/sns/ui/SnsSightPlayerUI;` | `onCreate` | 0x32e | ⭐ **播放器侧（Sight 播放页）** |
| `Lcom/tencent/mm/plugin/sns/ui/VideoAdPlayerUI;` | `onCreate` | 0x322/0x424 | 广告播放 |
| `Lnf3/e;` | `invokeSuspend` | 0xb8 | 协程 |
| `Lwh3/n;` | `invokeSuspend` | 0xc0 | 协程 |
| `Ly30/e;` | `invokeSuspend` | 0x147 | 协程 |

### 2.1 关键观察（对 Route C 至关重要）

1. **preload 侧**：`d1#f0`（LazyerImageLoader2）是 `l0#d` 的正式 preload 调用者。它构造 `bf3/m0`（`new m0; m0.d(0); m0.e(requestId)`），然后 `f4#ea` 取 `l0`，`invoke l0#d`。**这是"全后台无感知"的模板**：d1 本身是模型层，无 Activity/Looper 强依赖。

2. **播放器侧**：`SnsSightPlayerUI#onCreate`、`OnlineVideoView#N` 是 UI 侧调用者——但这正是"用户手动点开播放才落盘"的既有事实链路，非后台链路。

3. **大量 `run` 型后台调用者**（e1/h1/q1/t1/k1/l1/z）说明 `l0#d` 被广泛用于异步任务，**本身就可在后台线程无 UI 调用**，无 Activity 上下文硬约束。

4. `id3/e0#run` 是起点，但真正"发网络"在 `l0#z`（见第 1 节），由 idle handler 触发——**d→e→f→h→z 整条链均可后台执行**。

## 第 3 节 · `f4#ea` 是什么 [DONE]

`Lcom/tencent/mm/plugin/sns/model/f4;` = **SnsCore**（日志 tag `com.tencent.mm.plugin.sns.model.SnsCore`）。

### 3.1 `f4#ea` = `getSnsDownManager` ✅ 单例 getter

反汇编（direct, 43 units）：
```
markStartTimeMs('getSnsDownManager', 'SnsCore')
ly70/k1#b → ly70/l#c          // 确保 SnsCore 已初始化
f4#V8                           // 取核心单例
iget f4.E (Lcom/tencent/mm/plugin/sns/model/l0;)   // 字段 E = l0 单例
if-nez → new l0; <init>; iput f4.E
return f4.E
```
**结论：`f4#ea` 返回 `l0`（DownloadManager）单例，懒加载。是拿到 DownloadManager 的唯一正规入口。** 签名 `Lcom/tencent/mm/plugin/sns/model/l0;`（静态 direct）。

### 3.2 `f4#B8` = `getAccPath` ✅

返回 `ly70/z.d`（String）。即账号缓存根路径（`getAccPath`），配合 `bf3/t0#h0` 判断 isHasSdcard。

### 3.3 `f4#F8` = `getAccSnsPath` ✅

`class loe3/a0` + `l93/p2#x8` 取 SNS 缓存根路径。**这是朋友圈媒体缓存根目录**（与分桶 `y5#e` 拼接 sight_ 文件路径用）。

### 3.4 `f4#Na` = `getUIHandler` ✅

返回 `f4.d`（`com/tencent/mm/sdk/platformtools/o3`），即 SNS UI Handler。**注意：o3 是微信通用 Handler，`l0#l` 里用 `o3#postDelayed` 兜底——但 `l0` 自身的字段 `a` 也是 `o3`（见 l0#l：`l0.a` 是 `o3`），说明 DownloadManager 自带 handler 引用，不依赖 Activity。**

### 3.5 其他确认过的 getter

- `f4#gb` = `isInValid`（返回是否 invalid）
- `f4#S9` = `getSnsAsyncQueueMgr`（返回 `f4.A` = `c4`）
- `f4#m9` = `getImageLoader`（返回 `f4.u` = `d1` LazyerImageLoader2）

**三者都是懒加载单例 getter，无需 UI 上下文即可调用。**

## 第 4 节 · ⭐ Route C 可行性判定（最关键）[DONE]

问题：**能否在模块里（无 UI）直接调用 `l0#d(vm3, int, m0, x7)Z` 触发下载？**

### 4.1 `vm3` 从哪来 —— ✅ 可从 SnsInfo.content 反序列化

- `SnsInfo#getTimeLine()` 反汇编：`field_content:[B` → `new TimeLineObject; parseFrom(content)` → `TimeLineObject.ContentObj`（`Lq54/i40;`）。
- `i40.h` = `LinkedList`，`i40#op` 里 `const/16 v5, 8` + `new Lq54/vm3; <init>` —— **field 8 是 `LinkedList<vm3>`（媒体对象列表），媒体对象就在 `i40.h`**。
- `vm3.d` = 媒体 URL，`vm3.e` = 媒体类型(int)，`vm3.x` = 另一 URL（备用）。
- **可调用性结论**：`vm3` 是标准 protobuf 消息（`com/tencent/mm/protobuf/a`），直接 `SnsInfo.getTimeLine().ContentObj.h.get(i)` 即可拿到现成的 `vm3` 实例，**无需自己 parse**。模块里拿到 `SnsInfo` 对象即可（或走已有的 `SnsInfo` 查询链）。

### 4.2 `bf3/m0` 能否传 null —— ✅ 可传 null（或空构造）

- f/g/h 内部 grep `bf3/m0` = **0 次引用**（只透传）。
- m0 是 `SnsDecodeElment`，仅 `d1#f0` 这类 preload 调用者会 `new m0` 填 requestId。DownloadManager 内部不读。
- ⇒ 传 `null` 安全；更稳妥是 `new bf3/m0()`（空 LinkedList 构造）。

### 4.3 `x7` 能否传 null —— ⚠️ 建议传 `x7.c`（timeline），勿传 null

- `l0#f` 里 `sget-object x7.c; x7.equals(x7.c)` 判断场景；`h` 里 `bf3/n0.f = x7`（DownloadElment 存储 scene），`z` 里 `Ljf3/a.l = x7`。
- `x7` 是纯数据对象（字段 `a=String, b=int`），`<clinit>` 预置了 `c='timeline'` 等 13 个静态单例。**传 `x7.c`（timeline）最贴合朋友圈下载语义**；传 null 会走 `if-nez` 分支但可能影响上报统计，不阻断下载。

### 4.4 第 2 个 int 参数（=10 / `0xa`）语义 —— 是 `vm3.e`（媒体类型）

- 证据链：`d1#f0` 内 `iget v116, v7, Lq54/vm3;->e:I` 紧接 `invoke l0#d`，d 原样透传。
- 而 `l0#e` 里 `const/4 v10, 0xa` 是**第 8 参**（f 的最后 int），不是第 2 参。`0xa`(10) 在 `l0#f` 里是 `getRequestType`/`reqDownloadType` 相关常量。
- **Route C 调用时应传 `vm3.e` 的实际值**（视频 sight 通常 `vm3.e` = 某类型值），不要写死 0xa。

### 4.5 是否需要 UI/Activity/Looper —— ✅ 不需要（可全后台）

- `l0#d/e/f` 纯数据入队，无任何 Context/Handler 引用。
- `l0#h`：`Looper.myLooper()` 判空分支——非空→`addIdleHandler`；**为 null→`l0#l`(checkIdleFile，用 `l0.a`(o3) `postDelayed` 兜底)**。说明 DownloadManager 内部用**自带 `o3` handler**，不依赖 Activity/Looper。
- `f4#ea` 内部 `ly70/k1#b → ly70/l#c` 只是确保 SNS 插件已初始化，不 require UI。
- **但注意**：`l0#z` 里的 `addIdleHandler` 需要**有 Looper 的线程**才能及时触发。若在纯线程池（无 Looper）调用，会落到 `checkIdleFile` 分支用 `o3#postDelayed`（o3 绑定主线程或核心线程），依然能触发下载。**结论：全后台无感知可成立，但最好在带 Looper 的后台线程（或主进程 handler）触发。**

### 4.6 有没有更省事的入口

- `l0#b(String,SparseArray,SparseArray,x7,II)` = `addBatchDownloadSns`：批量下载，内部遍历 SparseArray 过滤 `Locall_path`/`pre_temp_sns_pic`，然后走同一套入队+`z`。**适合一次性下多条**，但构造 SparseArray 比单条 d 麻烦。
- `l0#j(String,j0)` = `addReporter`：仅登记 reporter，不下载。❌
- `d1#f0`（LazyerImageLoader2）**本身**就是 preload 单视频入口，但它内部还带 SightPlayImageView/VideoSightView 的 UI 分支。**不如直接 `l0#d` 干净**。

### 4.7 结论（一句）

**Route C 可行**：模块内可 `f4#ea()` 取 `l0` 单例 → 从 `SnsInfo.getTimeLine().ContentObj.h` 取 `vm3` → `l0.d(vm3, vm3.e, null(或new m0), x7.c)` 触发下载，**全后台无 UI 无闪屏**；唯一注意点是需在带 Looper 的后台线程（或借 `f4#Na` 的 o3 handler）触发以让 idle handler 及时出队。

## 第 5 节 · 三红线（进入 / 返回 / 复位）[DONE]

前提：Route C 已判可行。三条边界判据全部对着"用户能看见的事实"写，不写内部计数器。

### 红线 1 · 进入（什么条件下才允许触发下载）

满足**全部**才发 `l0#d`：
1. **开关在**：用户侧功能开关为开（后台保存朋友圈视频开关）。
2. **名单命中**：`vm3` 所属 SnsInfo 的 `userName`（好友 username）在"指定好友名单"内。
3. **是视频**：`vm3.e`（媒体类型）为视频类型（sight 或 streamvideo），且 `vm3.d`(URL) 非空。
4. **未落盘**：目标文件（按 `f4#F8` + 分桶 `MD5(mediaId)` 前两位 + `sight_` 前缀拼出的完整路径）**不存在**才发；已存在直接跳过。
5. **空间闸门**：`f4#B8`(accPath) 所在存储剩余空间 > 阈值（例如 200MB）才发。

⛔ 上述是**串行且**条件，任一不满足则不发（不是并列 or）。

### 红线 2 · 返回（什么条件下必须直接 return 不发网络）

满足**任一**即 return：
1. `vm3 == null`（对应 `l0#f` 的 `if-nez → "unknow case media is null"` 行为，必须主动挡住）。
2. `vm3.d` 为空串，或 `vm3.d.startsWith("Locall_path")` / `startsWith("pre_temp_sns_pic")`（本地图，`l0#h` 会 return 0，但我们在入口先挡，省一次调用）。
3. **界面在前台可见**：检测到朋友圈页面/任意 `SnsSightPlayerUI` 等 Activity 处于 resumed 状态（用户正在看朋友圈）→ 直接 return，**避免与用户手动播放抢下载、避免闪屏**。
4. 网络不可用（无 wifi/移动数据）。

⛔ 任一命中即 return，不做任何网络动作。

### 红线 3 · 复位（批量遍历如何不串味）

1. **游标无条件推进**：遍历 SnsInfo 列表时，每处理完一条（无论是否触发下载、是否 return），游标 `i++` 无条件执行；失败/跳过不 `continue` 前必须已推进游标。
2. **不共享 flag**：每条 SnsInfo 的"是否已处理/是否已落盘"判定，只用**该条自己的** mediaId 计算本地路径存在性，不用任何全局/静态布尔标记（避免前一条的 false 污染后一条）。
3. **单条失败不中断整体**：`l0#d` 返回 false（如 isHasSdcard=false）只记录该条，继续下一条，不回滚已触发成功的其他条。

## 第 6 节 · 下一步最小真机验证清单 [DONE]

给主会话的可执行清单（只 3 项，每项含明确分叉）。

### 验 1 · 挂 `l0#d` 入口，确认后台可触发且不闪屏
- Hook：`com.tencent.mm.plugin.sns.model.l0#d(vm3,int,m0,x7)Z`（用 `f4#ea` 拿单例后直接调，或 hook 看入参）。
- 日志：打印 `vm3.d`(URL)、`vm3.e`(type)、`m0`、`x7`，以及返回布尔。
- 操作：后台线程（带 Looper）里手动调 `l0.d(某好友视频的 vm3, vm3.e, new bf3/m0(), x7.c)`。
- 判据：
  - **若** `l0#d` 返回 true 且后续 `l0#h` 日志出现 `addDownLoadSnsImpl ... add to net lists`、`l0#z` 出现 `tryStartNetscene ... start download with single media` **且** `sight_` 目录新落盘完整 MP4 **则** Route C 全链路成立，进入量产。
  - **若** `l0#h` 返回 12（`isHasSdcard false`）**则** 存储路径/权限有问题，先修 `f4#B8` 指向的 accPath。

### 验 2 · 确认 CDN 解密落盘的是明文 MP4（而非密文）
- Hook：`Ljf3/f`(SnsCdnDownloadBase) 或其 `c3#f` 下载完成回调、`l0#t`(onTaskFinish) 的 `onSightFinish`。
- 日志：打印最终文件路径 + 文件头 4 字节（`00 00 00 1c` 即 `ftyp` MP4 box 头）。
- 操作：验 1 触发下载后，等回调。
- 判据：
  - **若** 落盘文件头是 `00 00 00 1c ftyp...`（完整 MP4）**则** native 解密在该链路内完成，直接可存。
  - **若** 落盘仍是密文（无 ftyp）**则** `c3` 这条 CDN 通道不解密，需回退到 `l0#i` 的 `Ltj/m`(streamvideo) 分支或用户手动播放链路，Route C 判定降级为"需真机验证"。

### 验 3 · 后台/前台互斥（红线 2.3）不闪屏
- Hook：`SnsSightPlayerUI#onCreate` / `SnsCommentDetailUI#W6` 等 UI 入口，打 resumed/paused 状态。
- 日志：触发下载瞬间，前台 Activity 是哪个。
- 操作：验 1 成功的前提下，在前台停留在朋友圈页面时再次尝试触发下载。
- 判据：
  - **若** 前台是朋友圈/播放页时我们的 hook 拦到"直接 return 未调 l0#d"**则** 红线 2.3 判据生效，全后台无感知达成。
  - **若** 触发时屏幕闪现了播放页/Sight UI**则** 说明有 UI 侧回调（`h0#onSightFinish` 等）被误触发，需在 `l0#t` 回调处加 UI 抑制。

---

[ALL-DONE]

---

## ⛔ 主会话复核：本文一处硬错已纠正（2026-11-08 01:3x）

### 错在哪

本文 §1.1 / §4.1 / §5 多处断言 **「`vm3.d` = 媒体 URL」**。**这是错的**。

`vm3.d` = **mediaId**（不是 URL）。三条独立证据：

1. **`bf3/t0#S`（getSnsSightName）**：`name = SnsUtil.s(vm3.d)` 得到 `sight_` 文件名主体，
   真机落盘文件名是 `sight_<20位数字>&enc<n>` ⇒ `vm3.d` 是 20 位 mediaId。
2. **`y5#e(root, mediaId)`**：`MD5(mediaId)` 取 hex 前两位建目录。`d1#f0` / `id3/f0#f` 两处
   都是 `iget vm3.d` 后直接喂给 `y5#e` ⇒ `vm3.d` 必须是 mediaId 才能算出分桶。
3. **R43 §A 真机 protobuf**：`8.5.1` = `15049650948694479362`（20 位数字）= mediaId；
   `8.5.4` = `http://szzjwxsns.video.qq.com/.../20202/snsvideodownload?...`。
   而 §二 已用 `op` 字段序证明 `8.5.1 = no1.d`、`8.5.4 = no1.g`。

### 正确的是

| 字段 | proto | 语义 |
|---|---|---|
| `vm3.d` | 8.5.1 | **mediaId**（20 位数字串） |
| `vm3.e` | 8.5.2 | 媒体类型 int（子类型，不是 `type==15`） |
| `vm3.g` | 8.5.4 | **视频主 URL（/20202/）** |
| `vm3.i` | 8.5.6 | 封面 URL（/20250/） |
| `vm3.h` | 8.5.5 | 与 `g` 成对的 int（疑似文件大小/时长） |

**`l0#z` 反汇编直接坐实**（URL 在 `z` 里取，不在 `h` 里取）：

```
0x01e9  iget-object v105, v6, Lq54/vm3;->x  ; 分支 A（某类型）用 x
0x0210  iget-object v233, v14, Lq54/vm3;->g ; 分支 B 用 g
0x0220  iget-object v105, v6, Lq54/vm3;->g  ; ← 主分支（v155==9 附近，sight 视频）
0x0222  iget  v110, v6, Lq54/vm3;->h        ;   与 g 成对
0x022a  iget-object v104, v6, Lq54/vm3;->i  ; 封面分支
0x022f  iget-object v105, v6, Lq54/vm3;->B  ; 另一分支
0x0249  iget-object v105, v6, Lq54/vm3;->g
```

`vm3.d` 在 `z` 里出现 6 次，全部是**拼落盘路径 / 当 map key / 当 requestKey 输入**，
**没有一次当 URL 传给 `Ljf3/a.c`（DownloadItem.url）**。

### 对判据的影响（重要，会影响真机验证判据）

- 本文 §5「红线 1 第 3 条」「红线 2 第 2 条」里写 `vm3.d` 非空 / `startsWith("Locall_path")`
  ⇒ **应改为 `vm3.g`**（URL）。`vm3.d`（mediaId）永远非空，拿它做「URL 非空」判据是死判据。
- 「`vm3.d.startsWith("Locall_path")`」实际是 **mediaId 以 `Locall_path` 开头**（本地生成的假 mediaId），
  这条**是对的**（微信确实用 `Locall_path` / `pre_temp_sns_pic` 前缀标记本地伪媒体）。
  所以是两个不同判据被混在了一句话里，正确拆法是：
  - 判「本地伪媒体」→ 看 `vm3.d`（mediaId）前缀 ✅
  - 判「有没有 URL」→ 看 `vm3.g` ⛔ 不是 `vm3.d`

### 不受影响的结论（仍然成立）

- Route C 可行：`f4#ea()` → `l0` 单例 → `vm3` 从 `getTimeLine().ContentObj.h` 取 ✅
- `vm3` 不需要自己 parse ✅
- `m0` 可传 null / 空构造 ✅
- `x7` 传 `x7.c`（timeline）✅
- 全后台无 UI 成立（`l0.a` handler 来自 `f4#Na`）✅
- 真正发网络的是 `l0#z`（tryStartNetscene）→ `Ljf3/l`（SnsDownloadSight）→ `c3`（SnsCdnDownloadBase）✅
- ⭐ **`d1#f0` 是普通视频的 preload 入口**（日志 `setsight`，签名带 `VideoSightView`），
  证实「普通视频走 `l0#d`，不走 `id3/f0#f`」—— 这正是本轮要判的岔路，**已判明**。

---

## ⭐ 真机实测结论（2026-11-08 02:0x，主会话）—— 两条推断被证伪

### 实测事实（全部有日志/文件系统证据）

| # | 事实 | 证据 |
|---|---|---|
| F1 | `f4#ea()` 可调，`f4#F8()` 可调，`f4#Na()` 可调 | `dispatch l0=l0`、`F8raw=.../MicroMsg/<hash>/sns/`、`Looper (main, tid 2)` |
| F2 | `l0#d` 签名匹配 **`vm3,int,m0,x7`**，且返回 **`true`** | `l0#d n=1 sig=vm3,int,m0,x7` + `l0#d returned true` |
| F3 | **不落盘**：3s/6s/12s 三轮复查全「无」，40s 手查也无 `sight_<mediaId>` | 桶 `sns/d/a/` 里只有 11-07 的 `snst_`/`snsu_`，无 `sight_` |
| F4 | **改到 `f4#Na` 的 main Looper 上执行，结果完全相同**（`returned=true`，仍不落盘） | `post=true（Looper (main, tid 2)）` + `l0#d(looper) returned=true` + `落盘[+3s]=无` |

### 被证伪的两条推断

1. ⛔ **「裸 Thread 没有 Looper ⇒ 走 `l0#l` 兜底所以下不了」—— 证伪**。
   F4 显示在 main Looper 上调用同样不落盘。`l0#h` 的 Looper 分支**不是**卡点。
2. ⛔ **「`l0#d` 返回 true 就代表会下载」—— 证伪**。
   `d` 的 `true` 只代表「入队成功」，不代表 `l0#z` 发起了网络。

### 剩余假设（按可信度排序，待下一轮验）

1. **`l0#z` 没被触发**：`l0#h` 末尾是 `l0.l.size()>0 → l0#z`，但 `z` 由
   `MessageQueue.addIdleHandler` 或 `l0#l` 的 `postDelayed` **异步**驱动。
   主线程极忙（日志里 `K7 perf` 每 3s 一次 + `full rebuild`）⇒ idle handler 迟迟不跑。
   **验法**：反射读 `l0.l`（LinkedList）看有没有残留元素；有残留 = 入队了没出队。
2. **`x7` 场景对象不对**：探针传的是 `x7.c`=`timeline@0`，但真实下载走的是
   `d1#f0`（`LazyerImageLoader2`，日志 `setsight`）里的场景。`l0#f` 开头有
   `x7.equals(x7.c)` 分支 —— 若后续逻辑要求「非 timeline」就会走空。
   **验法**：hook `l0#f` 的 before 打 8 个入参 + 返回值，看被哪条前置判据挡回。
3. **`m0` 传 null 不行**：本次 `m0=false`（`new bf3/m0()` 构造失败）。
   文档说 f/g/h 不读 m0，但 `m0` 会落进 `Ljf3/a.h`(DownloadItem)，**下游 `c3` 未验**。
   **验法**：先修 m0 构造（看构造器真实签名），再重跑。

### 已确认为**不成立**的历史猜测（别再重试）

- ❌ `F8 + "sns"` 拼路径 —— `F8` 末尾**已带** `/sns/`，不存在 `sns/sns`。
- ❌ 缓存根在 `MicroMsg/<hash>/sns` —— 真机上该目录**不存在**；
  真实根是 **`cache/<hash>/sns`**（`F8` 把 `MicroMsg` 换成 `cache` 即得，hash 相同）。
- ❌ 文件名是 `sight_<mediaId>&enc<n>` —— 真机是
  **`sight_<mediaId><32位hex>&enc<n>[_V2]`**，mediaId 后**直接跟 32 位 hex**，没有分隔符。

### 已确认为**成立**的部分（可直接用于正式实现）

- ✅ **分桶公式** `MD5(mediaId)` hex 前两位逐级建目录：4 个真机样本逐一对上
  （`15049717216742806078`→`e/c`、`15048712562070008502`→`5/6`、`14851818046781395541`→`3/6`）。
- ✅ **`vm3` 字段**：`d`=mediaId(20 位)、`e`=subType(实测视频=6)、`g`=视频 URL(/20202/)、`i`=封面(/20250/)。
- ✅ **解析链路**：`content` → `TimeLineObject.parseFrom` → `ContentObj.h` → `vm3` 全通。

---

## ⛔⛔ R44 终局（2026-11-08 02:20）· R44 前提整体作废

> 本轮真机把整条链路走通了，结论是：**Route C 完全不需要，而 R43「裸 HTTP 可下明文视频」
> 是错的**。真正的卡点不在下载调度，而在**下载回来的字节是加密的**。

### 一、Route C 判定：不需要（推翻本文第 1 节）

本文开头的「Route C 可行」结论**技术上没错**（`l0#d` 确实能调通、返回 true），
但**目标上多余**：视频不需要微信的 DownloadManager，也不需要 hook 解密，
只需要理解「CDN 返回的字节前 128KB 是密文」。

真机实证（`op=moments sub=dispatch sub3=real`）：`returned=true`，但 `l0.l`/`l0.e`
队列**全程 size=0**，手动踢 `l0#z()` 也无变化、不落盘。三轮（probe / real / real-looper）
全部无产出。**该链路对我们是死路，且不必再走。**

### 二、⭐ 真机实测：视频**能下载**，但**前 128KB 是密文**

`kvset moments.export_list []`（解除名单限制）后跑 `op=moments sub=export`，
一轮产出 **14 个 `vid_*.mp4`（226KB ~ 16MB）**，全部真实落盘：

```
MOMENT vid copied snsId=-3397093125619445258 dst=vid_-3397093125619445258_0.mp4
... (共 14 条，含 sily1988 / 陈静 / 科盛机械丁凯 等)
```

但逐个查文件头，**14/14 全是密文**（无一个 `00 00 00 ?? 66 74 79 70`）：

| 文件 | 头 8 字节 |
|---|---|
| `vid_-3397010543238508024_0.mp4` | `e7 1e 69 c6 c3 f1 00 59` |
| `vid_-3397093125619445258_0.mp4` | `01 b1 e5 7b 7e fe 2f 5b` |
| …（其余 12 个同样随机） | |

### 三、⭐ 决定性证据：同一视频，微信缓存是明文、我们下载是密文

`mediaId=15049717216742806078` 的视频（snsId `-3397026857690197446`）：
- 微信缓存：`cache/<hash>/sns/e/c/sight_15049717216742806078a2319f...&enc1370298124_V2`
  → 头 `00 00 00 1c 66 74 79 70 69 73 6f 6d` = **ftyp isom，明文 MP4**
- 我们下载：`vid_-3397026857690197446_0.mp4` → 头 `46 a4 5d d0 63 1b a2 f3` = **密文**
- **两者长度完全相同：1350560 字节**

且微信缓存的 5 个 `sight_*` **全部是明文**（同样 ftyp 头）。
⇒ 结论：**CDN 返回的就是密文，微信在本地解密后才写缓存。R43 只验了响应头
（200 / video/mp4 / 字节数）就判定「成功」，没验正文。**

### 四、加密结构（本轮已精确测定）

对同一视频的明文 P 与密文 E 做 `P XOR E` 得密钥流 KS：

- `KS[0..131071]` 非零且伪随机（16384 个 8 字节块**全部唯一**，无周期）
- `KS[131072..]` **全为 0** ⇒ **只加密前 131072 字节（128KB）**，之后明文
- 首块 `46a45dcc056fdb83`，正是密文头与明文头的 XOR，自洽

这与公开逆向资料一致（52pojie / 看雪《4.0微信朋友圈媒体解密全解析》）：
> 视频**仅前 128KB** 加密；算法 ISAAC-64 生成密钥流后 XOR；
> 密钥来自 SnsInfo content XML 的 `<enc key="数字" />`（**不是** URL 里的 `encfilekey`）；
> `rand()` **逆序消费** `randrsl[]`；每个 64-bit 密钥块 XOR 前按两个 32 位半字各自大端翻转。

### 五、❌ 本轮没做成的：ISAAC-64 复现（未闭环，别当已完成）

已按上述反编译结论实现并穷举 **96 种组合**（seed 编码 × 消费方向 × 输出方向 ×
预混轮数 × 字节序翻转），用真机明文/密文对做判据，**最高命中 3/256 字节 = 随机期望**。

⇒ 说明还有至少一个未知实现细节（最可能是 `init_state(state,&seed,1)` 的种子
布局，或 `rand()` 出池索引写法）。**这条路需要继续逆向才能闭环。**

### 六、⚠️ 一个更省事的候选路线（未验证，待定）

微信自己已经解密并缓存了 5 个 `sight_*`。**直接复制这些明文缓存**可绕过解密：
- 优点：零逆向、零网络、零 UA 依赖、必定明文
- 缺点：只有「用户自己看过的视频」才有缓存（5 个 vs 朋友圈全量），
  且要做好「缓存文件 → snsId」的映射（文件名含 mediaId，可 O(1) 反查）
- 判据：`sight_<mediaId>` 前缀匹配 + 浏览器级 ftyp 校验

### 七、其余不变的事实

- 名单判定**没坏**：`listMiss=499` 是因为当时名单配了 3 个 wxid，
  而最新 500 条动态的作者都不在其中 —— **行为正确**。清空名单后立刻 `written>0`。
- 图片链路**正常**：同一轮导出产生大量 `img copied`，`sight_*/snsu_*` 索引直扫有效。
- `moments.copy_videos` 已置 **true**。

---

## ⭐⭐ R44 续（2026-11-08 02:45）· 微X 有保存功能（前判错误）+ 找到微信官方解密 API

> 前文第六节把「微X 只有转发语义、不下载保存」写成了结论。**用户指出这是错的，真机实证也证明是错的。**
> 本节同时给出**真正的解法**：朋友圈视频解密不需要复现 ISAAC-64，直接调微信自己的 native API。

### 一、⛔ 自我更正：微X 确实有保存功能（不是转发）

上一轮我查的是 `/sdcard/WechatXposed/`、`MicroMsg/Download`、`DCIM`，**全空**，
于是判定「微X 不保存」。**查错目录了。**

真实位置是 **app 私有目录**（`run-as` 域，普通 `adb shell` 看不了，必须 `su`）：

```
/sdcard/Android/data/com.tencent.mm/files/WechatXposed/
├── downloads/videos/     ← 「保存朋友圈视频」落点（当前空）
├── downloads/images/     ← 「保存朋友圈图片」落点（当前空）
├── moments/              ← 「导出朋友圈动态」落点（**有实战产物**）
│   ├── <昵称> (<wxid>)/<yyyy-MM-dd-HHmm>/
│   │   ├── text.txt       正文
│   │   ├── img_N.jpg      图片
│   │   ├── video.mp4      视频 ★
│   │   └── url.txt        外链分享类动态的链接
│   └── _imgdl/            下载临时区（密文残骸）
└── 微X模块_<wxid>_settings.json   配置导出（明文 JSON，值仍是密文）
```

微X「朋友圈」设置页（`com.fkzhang.wechatxposed/.MainActivity` → 设置 → 朋友圈）实测条目：

| 条目 | 说明 |
|---|---|
| 保存目录 | `.../WechatXposed/downloads/videos` + `.../downloads/images` |
| **导出朋友圈动态** | 开关 |
| 批量导出朋友圈动态快捷 / 批量导出朋友圈动态 | — |
| **自动导出朋友圈动态** | 附注原文：**「需要开启定时刷新和自动加载朋友圈功能」** |
| 路径 | `.../WechatXposed/moments`（可「预览」） |
| 设置自动导出名单 | — |
| 定时刷新朋友圈 + 自动加载朋友圈动态 + 刷新间隔 | 与 R44 目标同构 |

⇒ **微X 与 R44 的设计几乎一模一样**（定时刷新 + 自动加载 + 自动导出 + 名单）。
⇒ 它也有 `sns_save` / `settings_save_dir` 配置项（`FKZ_WX_DATA` 库）。

### 二、⭐ 决定性证据：微X 导出的视频是**明文**

微X 在 2026-08-31 18:50 导出了一个 13,885,521 字节的 `video.mp4`。文件头：

```
00 00 00 20 66 74 79 70 69 73 6f 6d 00 00 02 00   ┌ ftyp isom
69 73 6f 6d 69 73 6f 32 61 76 63 31 6d 70 34 31   └ iso2 avc1 mp41
```

box 结构完整自洽：

```
box @0         size=32        type=ftyp
box @32        size=15490     type=moov
box @15522     size=8         type=free
box @15530     size=13869991  type=mdat     ← 32+15490+8+13869991 = 13885521 = 文件长
```

`mdat` 首字节 `00 00 00 02 09 f0 | 00 00 00 1d 27 64 00 33`：
AVCC 长度前缀 + `0x27`（H.264 **SPS** NAL，profile=100 High）⇒ **真·可解码明文流**。

对照：同一目录 `_imgdl/vid_-3412979636140764498_0.mp4`（2,826,203 字节）头是随机字节 = **密文**。
⇒ **微X 的下载缓存是密文，导出成品是明文 ⇒ 它一定做了解密。**

### 三、⭐⭐ 解密在哪：微信自己的 native API（不用逆向 ISAAC）

我上一轮想自己复现 ISAAC-64（96 组合全败）。**这个方向本来就错了** —— 微信有现成 API。

在设备 so 里按 `isaac` 字符串定位，命中 `libwechatcommon.so`：

```
$ grep -a -i isaac  /data/app/*/com.tencent.mm*/lib/arm64/*.so
HIT libmmnode.so
HIT libwechatcommon.so
```

`libwechatcommon.so` 里 ISAAC 段落（`0xc08c4` 起）原文：

```
ISAAC
what -->%s
isaac init %llu %ld %p
isaac open seed str %s
Java_com_tencent_mm_plugin_imgenc_MMIMAGEENCJNI_free  %ld
Java_com_tencent_mm_plugin_imgenc_MMIMAGEENCJNI_seek  %ld %ld
ISAAC64
seekForPosition  newPostion: %ld pos %ld
seed for %d  %llu
```

`libwechatcommon.so` 导出的 JNI 全表：

```
Java_com_tencent_mm_plugin_imgenc_MMIMAGEENCJNI_open__J                      open(long)     → long
Java_com_tencent_mm_plugin_imgenc_MMIMAGEENCJNI_open__Ljava_lang_String_2    open(String)   → long
Java_com_tencent_mm_plugin_imgenc_MMIMAGEENCJNI_transFor                     transFor(...)  → long
Java_com_tencent_mm_plugin_imgenc_MMIMAGEENCJNI_seek
Java_com_tencent_mm_plugin_imgenc_MMIMAGEENCJNI_free
```

Java 侧类（`classes7.dex`）完整签名：

```java
Lcom/tencent/mm/plugin/imgenc/MMIMAGEENCJNI;   extends Object
  open(J)J                        // 用 long key 开
  open(Ljava/lang/String;)J       // 用 seed 字符串开
  readByte(J[B)[B                // ⚠ dex 内零调用
  seek(JJI)J
  transFor(J[BJJ)J               // ★ 核心：handle, buf, off, len（原地改写）
  free(J)J
```

调用者（唯一的）是 `Ltl0/b extends java.io.FilterOutputStream`：

```java
Ltl0/b;-><init>(Lcom/tencent/mm/vfs/j6;J)   // (VFS 文件, key)
Ltl0/b;->write([BII)V   →  MMIMAGEENCJNI.transFor(J[BJJ)J
Ltl0/b;->close()V       →  MMIMAGEENCJNI.free(J)J
```

⚠️ `tl0/b` 的**唯一**构造方是 `com.tencent.mm.plugin.exdevice.model.p.a(String,J)`
（智能硬件/蓝牙设备），**与朋友圈无关** ⇒ 这是「加密写出去」的通用流，不是我们要的读方向。
`readByte` / `open(J)` 在 dex 内**零调用** ⇒ 给外部（插件/反射）用，或 dead code。

### 四、⭐⭐ 更硬的证据：**文件名里就带 key**

微信自己缓存的明文视频，文件名形如：

```
cache/22139c91134dbeddc19d20be980dee7f/sns/e/c/
  sight_15049717216742806078a2319f43372974c153bc2256e103aaec&enc1370298124_V2
        └──── mediaId ────┘└────── 32 hex hash ──────┘└─ key ─┘ └版
```

- `sight_` = 视频；`snst_` = 缩略图；`snsu_` = 封面
- `&enc<十进制数字>` = **ISAAC 种子的十进制值**（`_V2` 后缀表示新格式）
- 分桶前缀 `sns/e/c/` 是 mediaId 的某种 hash，**不是** mediaId 前两位

这就是上一轮公开资料说的「密钥来自 `<enc key="数字">`」。**现在有了直接落点**：
不用解 XML，文件名里就有。

实测这一条：`sight_...(enc1370298124)_V2`，大小 **1,350,560 字节**，头 `00 00 00 1c 66 74 79 70 69 73 6f 6d` = 明文 ftyp isom。

### 五、下一步（最小验证，别跳步）

1. **写探针调 `MMIMAGEENCJNI`**：`open(long key)` → `transFor(h, buf, 0, 131072)` → 比对明文。
   用真机上现成的 `sight_*&encNNN` 文件反查密文（或重新下载一份密文），
   判据 = 解密后头 8 字节是否 `00 00 00 ?? 66 74 79 70`。
2. 若 `transFor` 方向是「加密」而非「解密」（XOR 对称，同一函数应当双向等价），
   则失败原因只可能是 handle 内部状态机方向 ⇒ 改试 `open(String)` 传 `enc` 数字的字符串形。
3. 成功后接进 `MomentsExport.backfillVideos`：
   下载密文 → `transFor(h, buf, 0, min(131072, len))` → 写盘。**只改前 128KB**。

### 六、⛔ 本条纠正的旧错误

- 旧：「微X 对朋友圈视频的能力是「转发/保存小视频」…这些都是「转发/分享」语义，
  不是「下载到本地文件」」 → **错**。微X 有完整保存/自动导出，且有实战产物。
- 旧：「微X 不碰解密，所以它也拿不到明文」 → **错**。它导出的正是明文。
- 旧的 ISAAC-64 自研复现 → **作废，不必再做**。微信有现成 API，逆向的性价比是负的。

### 七、补充实证（2026-11-08 02:5x）· `&enc` 后缀语义纠正

⚠️ 我曾推断「文件名带 `&enc` ⇒ 该文件是密文」。**实测推翻**：

| 文件 | 头 8 字节 | 实际 |
|---|---|---|
| `snsu_15045853952410596053...&enc2400862626` | `ff d8 ff e0 00 10 4a 46` | **明文 JPEG**（JFIF） |
| `snsu_15029837027798626823`（无 enc） | `ff d8 ff e0 00 10 4a 46` | 明文 JPEG |
| `sight_15048712562070008502...&enc2825813808` | `00 00 00 1c 66 74 79 70` | **明文 MP4** |

⇒ `&enc<十进制>` **不是**「本文件是密文」，而是「**下载这条媒体时用的解密参数**」
（缓存元数据，用来在重新下载时复现同一密钥流）。
微信落盘的全部是解密后的明文。

⇒ 生产实现取 key 有两条路：
1. **文件名**（`&enc<数字>`）—— 仅对「微信已缓存过」的视频可用；
2. **SnsInfo `content` 的 `<enc key="数字">`** —— 对全量动态可用，是正式路线。

本轮探针用「调用方显式传 key」的方式验证 API 可行性（不依赖上面两条取 key 路径），
API 打通后再决定取 key 的实现。

---

## ✅✅✅ R45 闭环（2026-11-08 02:55）· 微信 API 解密成功，R44 视频卡点彻底解决

### 一、决定性验证结果（真机，一次成功）

探针 `op=viddec sub=try`，参数 `密文|明文|key`：

```
VIDDEC try enc=/sdcard/Android/data/com.tencent.mm/files/_r45/enc.mp4
           key=1370298124 n=131072 r=0
           hit256=256 firstDiff=-1 allSame=true
           head8=00 00 00 1c 66 74 79 70
```

- `hit256=256` 前 256 字节**全部命中**
- `firstDiff=-1` **没有任何一个字节不一致**
- `allSame=true` **131072 字节完全一致**
- `head8=00 00 00 1c 66 74 79 70` = **标准 ftyp 明文 MP4**

⇒ **用 `MMIMAGEENCJNI.transFor(handle, buf, 0, 131072)` 能 100% 还原明文。**

### 二、API 真身（运行时实测，与静态 dex 不完全一致）

```
VIDDEC api so=libwechatcommon.so loaded=true
VIDDEC api class=com.tencent.mm.plugin.imgenc.MMIMAGEENCJNI found=true
VIDDEC api methods=6
  public static native long   free(long)
  public static native long   open(long)
  public static native long   open(String)
  public static native byte[] readByte(long,byte[])
  public static native long   seek(long,long,int)
  public static native long   transFor(long,byte[],long,long)
```

⛔ **6 个方法全是 `static native`**（静态 dex 里解析出来是 `direct` 未标 static，
容易误判成实例方法）。⇒ 反射 `invoke(null, ...)`，**不需要 new 实例**。

### 三、完整解法（生产实现照此写）

```
1. 下载密文到临时文件（现有 downloadVideo 已能做）
2. key = SnsInfo content 的 <enc key="数字">（或缓存文件名 &enc<数字>）
3. Class<?> c = Class.forName("com.tencent.mm.plugin.imgenc.MMIMAGEENCJNI",
                              false, 宿主 ClassLoader)
4. long h = (long) c.getMethod("open", long.class).invoke(null, key)
5. byte[] buf = 读文件前 min(131072, size) 字节
6. c.getMethod("transFor", long.class, byte[].class, long.class, long.class)
        .invoke(null, h, buf, 0L, (long) buf.length)   // 原地改写
7. c.getMethod("free", long.class).invoke(null, h)     // finally
8. buf 覆盖回文件前段 → 明文 MP4
```

**只处理前 131072 字节**；`size <= 131072` 的整文件处理。

### 四、踩过的坑（都已在代码里注释）

| 坑 | 现象 | 正解 |
|---|---|---|
| Kotlin 无 `Long.TYPE` | 编译失败 `Unresolved reference 'TYPE'` | `Long::class.javaPrimitiveType!!` |
| 文件权限 | `read-fail`：`/sdcard/_r45_enc.mp4` 属 `u0_a269 media_rw` | 放微信自己的 `files/` 下，`chown u0_a363` |
| 方法看似实例方法 | 静态 dex 解析不含 static 标记 | **运行时用 `api` 子命令确认** |
| `adb shell` 二次解析 | 广播无反应 | 整条命令用**单引号**包住 |
| 改包后模块不重载 | 日志停滞、广播无响应 | **冷启两次**（`dev.py restart` 连跑两次） |

### 五、R44 剩余工作（不再有技术未知）

- [x] 证明可以拿到明文视频
- [ ] 把 `transFor` 接进 `MomentsExport.copyVideosForRow` 的下载后处理
- [ ] key 提取：从 SnsInfo `content` protobuf 里取 `<enc key>`（全量可用）
- [ ] 反向对照：关开关后视频仍为密文（证明确实是本改动生效）

---

## ✅ R45 key 来源定案（2026-11-08 02:58）· key 就在 SnsInfo.content 的 protobuf 里

### 一、完整 protobuf 结构（真机 dump + 手工解析，字段号已确证）

对 snsId `-3397026857690197446` 的 `content`（1547 字节）完整解析：

```
TimeLineObject
├─ f1 = "15049717216019354170"         ← post id（**不是** mediaId）
├─ f2 = "wxid_vj9bd0jhq5rv22"          ← 作者
├─ f3 = 0
├─ f4 = 1794066097                     ← createTime
├─ f5 = 正文（96B，"咱能一直保持在钻1…"）
├─ f6 / f7 = 各种空对象
├─ f8 = SnsObject  ← ⭐ 媒体段在这里
│   ├─ f1 = "Sight"                   ← 媒体类型：视频
│   ├─ f2 = 15                        ← type（与 MomentsLogic.SNS_TYPE_VIDEO 一致）
│   ├─ f3 = "微信小视频"
│   ├─ f4 = 一个 support.weixin.qq.com 的提示页 URL
│   └─ f5 = MediaObject（len=995）   ← ⭐⭐ 核心
│       ├─ f1  = "15049717216742806078"              ← **mediaId**
│       ├─ f2  = 6                                    ← subType（视频=6）
│       ├─ f4  = len317                               ← CDN URL（snsvideodownload?encfilekey=…）
│       ├─ f19 = "857085ac4bb467f258f29c79876d13ba"
│       ├─ **f25 = 1370298124**                       ← ⭐⭐⭐ **解密 key**
│       ├─ f26 = "a2319f43372974c153bc2256e103aaec"  ← 文件名里那 32 位 hex
│       ├─ f33 = "9e33b0287a389973aa7c94ea7ac45b0c"
│       └─ f5/f6/f7/f8/f24/f28/f31/f34/f38/f39/f42 = 各种 0 / 小对象
└─ f9..f28 = 更多空对象
```

### 二、三方交叉验证（完全吻合）

| 来源 | mediaId | hash | key |
|---|---|---|---|
| protobuf f5:f1 / f5:f26 / f5:f25 | `15049717216742806078` | `a2319f43…aaec` | `1370298124` |
| 缓存文件名 `sight_<mediaId><hash>&enc<key>_V2` | 同 | 同 | 同 |

⇒ **缓存文件名 = protobuf 三个字段的拼接**，一一对应，无歧义。

### 三、⛔ 一个把我坑住的假结论

我用 SQL 探针查 `instr(cast(content as text), "1370298124") = 0`，
一度判断「key 不在 content 里」。**这个判断是错的**：

`cast(BLOB as TEXT)` 遇到含 `0x00` 的二进制 protobuf 时，
SQLite 会**在第一个 NUL 处截断**（`substr(...)` 查出空白也是同因），
所以 `instr` 永远搜不到后面的内容。

⛔ **教训：BLOB 列一律用 `hex()` 导出后在本地解析，禁止 `cast as text` 做内容检索。**

### 四、生产实现取 key 的正解

```kotlin
// 在 MomentsLogic 里加：
//   content 的 f8 → f5 → f25（varint）即 key；f1 即 mediaId
// 解析方式：手写最小 protobuf 遍历（不引入依赖）
fun snsVideoEncKey(content: ByteArray?): Long
```

⚠️ 不能按固定偏移读 —— 字段顺序可变、正文长度可变，**必须真解析 varint tag**。
⚠️ `f25` 可能缺失（老格式 / 非加密视频）⇒ 返回 0 表示「不需要解密」。
⚠️ **`_V2` 后缀与 key 无对应关系**：`_V2` 出现与否都在 f25 里，只是缓存命名的新旧格式。

### 五、附带发现的响应头（可用于校验）

```
X-encflag: 1              ← 服务端明确告知「已加密」
X-enclen: 131072          ← **加密长度由服务端给，不必硬编码**
X-snsvideoflag: xV0
X-Verify-Code: f005d69e3a31608fae3df00a90343e72   ← 与 key 无派生关系（已验证）
```

⇒ 生产实现可以读 `X-enclen` 决定解密长度（默认 131072），`X-encflag=0` 时跳过解密。

---

## ✅ R45 全量统计补强（2026-11-08 03:15）· `f25` 是加密标志的权威镜像

拉完整朋友圈库（`_kb/sns.db`，748MB）做全量核算，不再靠单样本推断。

### 一、全库 `type` 分布（有 content 的 262647 条）

| type | 条数 | 含义 |
|---|---|---|
| 1 | 160439 | 图文 |
| 3 | 29888 | 链接 |
| **15** | **24525** | **朋友圈原生视频** |
| 2 | 22882 | 纯文字 |
| 28 | 16959 | 视频号分享 |
| 7 | 5249 | — |

### 二、`type=15` 全量 key 提取（24525 条，零抽样）

```
key > 0（加密）  : 22377  (91.24%)
key == 0（明文） :  2148  ( 8.76%)
解析不出 f8      :     0        ← 算法零失败
```

### ③ 关键旁证：`f25 > 0` 严格局限在 `type=15`

22377 条有 key 的记录**全部**是 `type=15`；type 1/2/3/7/28 **一条都没有**。
⇒ 取 key **不需要先判 type**，取到 `>0` 就一定是该解密的那条。

### 四、多 media 的 key 一致性（68637 条多 media 动态）

| 情形 | 条数 |
|---|---|
| 全部 `f25 == 0` | 68637 |
| 非 0 且全相同 | 0 |
| **多个不同非 0** | **0** |
| **第一个 0、后面非 0** | **0** |

⇒ 从未出现「拿错 media 的 key」这种情形。遍历取第一个 `>0` 与取 `firstOrNull()` 结果等价，
但保留遍历更抗版本漂移。

### 五、真机下载响应头双向对照

| `content.f25` | `X-encflag` | `X-enclen` | 文件头 | 结论 |
|---|---|---|---|---|
| `0` | **0** | **0** | `00 00 00 1c 66 74 79 70` | **明文** |
| `1370298124` | 1 | 131072 | 密文 | 需解密 |
| `3660390825` | 1 | 131072 | 密文 | 需解密 |
| `3784293545` | 1 | 131072 | 密文 | 需解密 |

⇒ **`X-enclen` 恒为 131072**（3/3 加密样本一致）⇒ 生产可硬编码，不必读响应头。
⇒ **`f25 == 0` ⇒ 文件本身就没加密**，对它 XOR 会把好文件毁掉。

### 六、幂等检查的必要性（离线实证）

用真机已解出的密钥流（`_kb/ks.bin`）复刻 `decryptInPlace` 全流程：

```
plain 头8 : 00 00 00 1c 66 74 79 70   isFtyp: True
enc   头8 : 46 a4 5d d0 63 1b a2 f3   isFtyp: False
解密后与明文完全一致 : True

=== 对已解密的再 XOR 一次 ===
误二次解密后头8 : 46 a4 5d d0 63 1b a2 f3   ← 退回密文
```

⇒ `isFtyp` 短路**必须有**，否则重试路径会把明文毁成密文。

### 七、`isFtyp` 判据修正

旧写法 `b[0..2]==0 && b[4..7]=="ftyp"` 隐含「box 长度 < 0x1000000」。
box 长度是 4 字节大端，理论上可到 `FF FF FF FF`，那时 `b[0] != 0` 会漏判。
**改为只看 `b[4..7] == "ftyp"`**（前 4 字节是长度，任意值都合法），保留 `b.size >= 8`。
再加上 `if (n < 12) return false`（ftyp box 最小 12 字节）省掉短文件推演。

---

## ✅✅ R45 生产接入 + 真机闭环（2026-11-08 04:11）· 视频解密全链路验收通过

### 一、装包前交叉复核（独立子代理视角，只读）

派独立子代理对 4 个文件逐项复核 8 个问题，结论：

| 项 | 结论 |
|---|---|
| `f25==0` 跳过链路 | PASS（`snsVideoEncKey → encKey=0 → 不调 decryptInPlace`，且 `decryptInPlace` 内还有 `key<=0` 双保险） |
| ⭐ `encFlag != 0` 判据 | **真缺陷** |
| `encLen` 溢出/OOM | PASS（`minOf(effLen, size)` 被 200MB 上限封顶；`runCatching` 兜 Throwable） |
| `isFtyp` 放宽误判 | PASS（≈2⁻³²，误判只是「跳过解密留密文」，不会写坏文件） |
| 短读保护 | PASS（`got<n` 拦截，写回不撑大文件） |
| `encKey`/`dst` 配对 | PASS（urls/encKey/dst 同源同一 `row`） |
| 开关自变量唯一 | PASS（关掉只停解密，下载/rename/计数全不变） |
| 单测覆盖空白 | **真缺陷**（`downloadVideo` 判据零覆盖，等价变异可全绿） |

### 二、⭐ 修掉的真缺陷：`X-encflag` 不该参与解密决策

原判据 `if (encKey > 0L && encFlag != 0)`，`readHeaderInt` 对**头缺失/非法**返回 0。
后果：CDN 一旦没吐 `X-encflag`（代理剥离、大小写/多值、`toIntOrNull` 失败），
即使 `f25>0` 的 22377 条加密视频也**全部跳过解密**，静默留成密文
（不报错、`outcome=COPIED`、计数照常，只能 pull 出文件头才发现）。

**定案**：`f25` 来自本地 DB（全库 24525 条零解析失败）是权威信号，
`X-encflag` 只是同源副本。用可靠本地信号 AND 不可靠网络信号 = 把整体可靠性降到网络侧。
⇒ 改为 **`if (encKey > 0L)` 唯一判据**；`encFlag` 只留日志观测。
⇒ 对「f25>0 但文件实为明文」的防护由 `decryptInPlace` 的 `isFtyp` 幂等短路贴身兜底。

顺带加固 `decryptInPlace` 的 `enclen`：`(…).coerceAtMost(DEF_LEN)`，
把网络侧不可信输入钳在 131072（全库加密样本恒为此值）。

### 三、真机决定性验证（正向 + 反向对照，自变量唯一）

装包 `app-debug.apk`（md5 `9af20bc4…`），冷启 ×2。
生产同链路探针 `op=moments sub=viddl --el id <snsId>`。

**正向**（开关 `true`，`snsId=-3398590550888279432`，key=3805912539，362429 字节）：

```
04:07:37.323 VIDDEC inplace path=vid_-3398590550888279432_0.mp4.tmp
             key=3805912539 n=131072 ok=true why=decrypted ms=130 size=362429
04:07:37.325 MOMENT vid decrypt key=3805912539 ok=true encflag=1 enclen=131072
04:07:37.375 VIDDL ... outcome=copied bytes=362429 ms=1965
```

拉回落盘文件**逐字节核验**（不只信日志）：

```
size = 362429
头16 = 00 00 00 1c 66 74 79 70 69 73 6f 6d 00 00 02 00
box: ftyp 28 @0 + moov 12567 @28 + free 8 @12595 + mdat 349826 @12603 = 362429 ✓
```

⇒ **结构自洽的明文 MP4**。

**反向对照**（`kvset moments.video_decrypt false -> now=false`，
另一条 `snsId=-3398616070237253067`，key=2965254400，28325688 字节，
curl 先验 `X-encflag: 1` 确认源本身是加密的）：

```
04:09:46.656 MOMENT vid decrypt skip (encKey=0 encflag=1) dst=vid_-3398616070237253067_0.mp4
04:09:46.675 VIDDL ... outcome=copied bytes=28325688 ms=19884
```

拉回文件头：

```
反向 头16 = 83 2f bc fc 7b d2 85 8b b5 db 71 6c 99 50 c5 de   ftyp=False（密文）
正向 头16 = 00 00 00 1c 66 74 79 70 69 73 6f 6d 00 00 02 00   ftyp=True（明文）
```

⇒ **明文确实是解密带来的，不是服务端本来就给明文**；且关开关只停解密，
下载/落盘/outcome/计数全部不变 ⇒ 自变量唯一。

收尾已 `kvset moments.video_decrypt true -> now=true` 恢复。

### 四、单测与变异验证

`OK (773 tests)`。四组变异全部验证过有效性：

| 变异 | 结果 |
|---|---|
| `f25 → f24`（字段号错） | 3 红 ✓ |
| 去掉 `k > 0L` 过滤 | **首轮全绿（假验证）** ⇒ 补「首个 media f25=0、第二个 777」用例后变红 ✓ |
| 删掉回退路径 | 1 红 ✓（后续实现简化，该用例已随之移除） |
| 无正 key 返回 `null` 而非 `0L` | **首轮全绿（假验证）** ⇒ 补「主路径明文 + 旧路径有 key」陷阱用例后变红 ✓ |

> 两组「一开始全绿」说明：**只写正向用例等于没验证**，
> 必须主动构造能区分「正确实现」与「看似合理的错误实现」的输入。

### 五、⚠️ 本轮发现的待办（**1 / 2 已由 R46 修掉**，3 如实保留）

1. ✅ **R46 已修**：`downloadVideo` 判据抽成 `MomentsLogic.shouldDecryptVideo` 纯函数，
   新建 `MomentsVideoGateTest`（5 用例 + 4 组变异），`downloadVideo` 只换调用、
   日志/落盘/计数逐字不变。详见本文末「R46」节。
2. ✅ **R46 已修**：`copyVideosForRow` 在 `dir.usableSpace` 判空之前补
   `if (!dir.exists()) dir.mkdirs()`。生产 `export` 分支早已建目录 ⇒ 幂等无副作用。
3. ⚠️ **仍保留**：`encFlag` 修复的收益无法在真机直接复现 —— 真机 CDN 稳定返回
   `X-encflag: 1`，新旧判据行为一致。修复消除的是「头缺失时的静默失效」这一**潜在**
   风险，不是本轮实测到的故障。如实标注，不写成已复现的 bug。

> R45 当时原文（历史留痕，**勿据此 reopen**）：① `viddl` 不建目录 ⇒ `File.usableSpace=0`
> 假报空间不足（导出根实测 46GB 空闲），当时靠 `adb shell mkdir -p` 手工绕过；
> ② `downloadVideo` 判据零单测覆盖，等价变异可全绿；③ `encFlag` 修复收益未真机复现。

### 六、⛔ 真机操作踩坑（会浪费半小时）

- ⛔ **广播 action 必须是 `com.lx.wxgroup.action.PROBE`，`-p` 必须是 `com.tencent.mm`**。
  写成 `-a com.lx.wxgroup.PROBE -p com.lx.wxgroup` 时，
  `Broadcast completed: result=0` **照常返回但压根不投递**（假象）。
  正确姿势直接用 `python tools/dev.py probe`（它的 `bc()` 已处理）。
- ⛔ `kvset` 参数是 `--es key <k> --es val <v>`；
  `kvget` 会把所有配置打出来，TAG 是 `PROBE kv`（不是 `KV`）。
- ⛔ `viddl` 的 `--el id` 值要包单引号（设备 sh 二次解析），负 snsId 尤其注意。
- ⛔ `adb shell` 下创建的文件属 `u0_a269 media_rw`，微信进程 `u0_a363` 读不了；
  要 `su -c chown u0_a363:u0_a363`。
- ⛔ `adb pull` 前 `export MSYS_NO_PATHCONV=1`，否则 Git Bash 改写 `/sdcard/...`。

## R46 收口（2026-11-08 04:4x）

### 一、R44 废弃探针归档（不删内容，移出编译单元）

`probe/SnsDispatchProbe.kt`（685 行，Route C 主动让微信下载器下视频）与
`probe/VidTrigProbe.kt`（327 行，视频触发链只读诊断）已移到
`docs/_archive/r44_probes/`，并从源码树摘掉全部接线：

- `HookEntry.kt`：删 2 行 import + 2 行 `prepare(...)` ⇒ 该文件 diff 相对 HEAD **归零**（实证：
  R44 对它的唯一改动就是这 4 行）。
- `entry/EntryReceiver.kt`：`moments` 分支删 `sub2`/`sub3` 局部声明与
  `vidtrig` / `dispatch` 两个 when 分支。`EXTRA_SUB2` 常量**保留**（346 / 874 行另有使用）。
- 复核：`grep -rn "SnsDispatchProbe|VidTrigProbe" wxgroup/app/src/main/java/` 返回空。

**为什么是归档而不是删除**：Route C 已真机证伪（「完全不需要」）、ISAAC-64 96 组合全败作废，
但这两个是 `??` **未跟踪**的新文件，直接 `rm` 会永久丢掉 R44 取证内容，git 里也捞不回来。
⇒ 移出 `app/src/main` 即不再进 APK，内容仍可查。

### 二、选人组件「已选排前」需求覆盖面（只读核实）

`Keys.kt` 里 6 个 `PrefKind.ENTER` 配置（允许/排除名单 ×2、原图允许/排除、
**设置自动导出名单**、群发选择发送目标）全部经 `MainPage` → `PickerPage`；
另有 `MassSendPage` 的群发选人、`ForwardPicker` 的转发目标 ⇒
**排序只需落在 `PickerPage.Adapter` 与 `ForwardPicker.TabAdapter` 两个展开可见行的入口**
即覆盖全部同类组件。

### 三、实现：稳定分区 + 只在 render 重排

- 纯判据 `PickerTabLogic.selectedFirst(items, selected, idOf)`：`partition` 后 `picked + rest`。
  **稳定分区不是排序** —— 已选整体在前，两组内部各自保持原相对顺序。
- 落点：`PickerPage.Adapter.render` 与 `ForwardPicker.TabAdapter.reset` 各一行调用。
  两者都是各自组件**唯一**展开可见行的入口，且 `filter` 也走同一入口。
- ⭐ **三档边界**（R42-N1N6 教训：只写「进入 / 返回」会漏掉复位路径）：
  | 档 | 触发 | 行为 |
  |---|---|---|
  | 进入 | 首次打开（`Adapter.init → reset → render`） | 已选排前 |
  | 返回 | 切 Tab / 搜索 / **清空搜索** / 群聊补跑刷新 | 重新 render ⇒ 按当时 selected 排前 |
  | 复位 | 反选 / 清空 / 取消勾选后的下一次 render | 按**最新** selected 重排，不缓存旧顺序、不写回 `source` |
- ⛔ **勾选当下只 `notifyDataSetChanged()`、不重排**：否则用户点一行该行立刻跳走。
  排序与勾选解耦，是这次刻意选 `render` 而非「点击回调」的原因。
- `selected` 是页面级 `LinkedHashSet`（`PickerPage:77` / `ForwardPicker:70`），
  以引用传给 Adapter ⇒ render 时读到的永远是最新勾选；跨 Tab 不清空。
- ⚠️ 边界：分区是**按段内**做的，段标题与分段结构保持不变（不跨段搬运条目）。

### 四、验证（2026-11-08，全在主机构建，未上真机）

- `testDebugUnitTest` **786 条全绿**（R45 基线 773 + 新增 13：`PickerSelectedFirstTest` 8 + `MomentsVideoGateTest` 5），
  `failures=0 errors=0 skipped=0`；`assembleDebug` **BUILD SUCCESSFUL**。
- **7 组变异全部变红**（没有一组等价变异假绿），逐组红条数：

  | 变异 | 红条数 | 说明 |
  |---|---|---|
  | A `in` → `!in` | 4 | 判定反转 |
  | B `picked + rest` → `rest + picked` | 4 | 前后段颠倒 |
  | C `+ .reversed()` | 7 | 组内顺序打乱 |
  | D `>0` → `>=0` | 1 | `f25==0` 明文被误解密 |
  | E `>0` → `!=0` | 1 | 负值被误解密 |
  | F 恒 `true` | 2 | 明文/负值被误解密 |
  | G 恒 `false` | 3 | 加密视频静默留密文 |

  恢复源码后复跑 BUILD SUCCESSFUL ⇒ 变异脚本没留残留。
- **静态交叉复核 16 项**：15 项直接 PASS；1 项「`X-encflag` 未参与判据」首次报 FAIL，
  复核为**检查器没剥注释**造成的假阳性 —— `encFlag != 0` 只出现在 1448 行警示注释里，
  实际代码判据是 `MomentsLogic.shouldDecryptVideo(encKey)`，`encFlag` 仅出现在日志字符串。
  ⇒ 实际 16/16 PASS。这条按判据纪律如实记录，不静默抹掉。

### 五、⏳ 仍未收口（不是 bug，是验证/覆盖边界，别当已完成）

1. **R25「自动导出真落盘」仍缺一次决定性真机证据**：只验到
   `export done rows=7 written=0 skipped=7`（幂等生效，但当时名单内没有新动态）。
   需某位名单好友新发一条朋友圈后观察 `written>0` 才算闭环。
2. **覆盖边界**：后台同步 `x2(256)` 只拉最新一批、非历史；导出读「当前 SnsInfo」单源
   ⇒ 库里没有的历史动态不会自动补导。
3. **本轮未装包上真机**（无人值守）：改动是 UI 排序 + 纯函数抽取 + 死代码归档，
   构建/单测/变异/静态复核全绿，但「列表视觉顺序」这一条**没有真机截图证据**。
   按用户级硬规则「真机测试前必须交叉验证代码变更」，本轮以「装包前等价物」替代，
   未跨过装包这一步。
