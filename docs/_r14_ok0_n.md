# R14 取证：`ok0.x0#n` 与朋友圈图片「批量下载」Java 层真实入口

> 子代理：cdn-trigger（**纯静态只读**：未碰 adb / 真机 / `wxgroup/` 下任何源码 / `tools/` 下正式脚本）
> 目标版本：微信 8.0.48（versionCode 2588），DEX = `_cache/dex/classes*.dex`（15 个）
> 临时脚本：`_cache/_r14_walk.py`（自写，正确 opcode 宽度表 + 寄存器还原 + 跨 dex 反查）
> 本文每条结论都附「复现命令 + 原始证据」。只有 grep 命中、无实证的标「未确证」。

> ⚠️ **2026-10-24 订正（`const/4` 字面量曾整体打错）**
> `_cache/_r14_walk.py` 当时对 `const/4`（11n，单 code unit）的字面量解码有 bug，
> 打印的是**下一条指令**的字节。本文 4 处 `const/4` 引用已按修复后的工具重跑订正：
>
> | 位置 | 曾记 | 现值 |
> |---|---|---|
> | `ok0.x0#n` [109] → `startSNSDownload` 第 4 参 | 4 | **0** |
> | `ok0.x0#o` [132] → `startSNSDownload` 第 4 参 | 4 | **0** |
> | `Ltj/h#<init>` [7]/[10]/[13] | 两条 `#1` | **`#-1` / `#0` / `#1`** 三条 |
>
> **结论未变**：`#n` 与 `#o` 的该参数**同为 0**，所以「二者分界只在批量专属字段」的论证依然成立；
> `Ltj/k->E:Z` 仍由构造函数写死为 `true`（`v0=#1`）。详见 `docs/conclusions/moments_cdn.md`。

## 0. 背景：要解释的硬事实

探针 `wxgroup/app/src/main/java/com/lx/wxgroup/probe/SnsCdnProbe.kt`（只读）挂 4 个点，滚朋友圈 3 屏后实测：

| 锚点 | 计数 | 说明 |
|---|---|---|
| `ok0.x0#onC2CDownloadCompleted` | hitCB = +7~+10 | 回调命中，主进程 |
| `jf3.f#k` | hitK = +9 | `SnsCdnDownloadBase#doInBackground` 活跃 |
| `ok0.x0#n` | **hitN = 0** | ⛔ 从未被调用 |
| `com.tencent.mm.plugin.sns.ui.dk#a()` | true | 批量开关默认开 |
| `CDN paths pic` | `""` | `C2CDownloadResult.picCachePath` 读出来是空串 |

---

## Q1. `ok0.x0` 到底有没有名为 `n` 的方法？

### 结论：**有。唯一一个，无重载。**

| 项 | 值 |
|---|---|
| 完整签名 | `Lok0/x0;->n(Ltj/g;)I` |
| 参数 | 1 个：`Ltj/g;`（SNS **批量**下载任务 bean，是 `tj/h` 的子类） |
| 返回 | `I`（int，即 `startSNSDownload` 的返回值） |
| access flags | `0x0001` = `ACC_PUBLIC`，**实例方法**（无 `0x8` static），**有 Java 实现体**（非 native，code_off=0x3a9294） |
| 所在 dex | `classes13.dex`（class_defs def#4301） |
| 原始类名 | `com.tencent.mm.modelcdntran.CdnTransportEngine`（方法体自报 `'MicroMsg.CdnTransportEngine'`） |
| 重载 | **只有 1 个 `n`** ⇒ 探针用 `hookAllMethods` 抓全部重载不是问题所在 |

### 复现命令 + 原始证据

```bash
# 类定义（class_defs 遍历，不是 grep type_ids）
$PY tools/_r13_findclass.py Lok0/x0;
# DEFINED in classes13.dex
#   class = Lok0/x0;   super = Ljava/lang/Object;   access = 0x0001
#   <init> (Ljava/lang/String;Ltj/d;)V
#   methods(67): <init>, keep_callFromJni, a, b, c, d, ... n, o, ...

# 全量方法签名（含真实参数类型，_proto 已修 type_list 解析 bug）
$PY _cache/_r14_fields.py methods 'Lok0/x0;'
```

原始输出（节选，`acc` 为 class_data 里的 access_flags）：

```
== DEFINED in classes13.dex  (def#4301)  Lok0/x0;
   methods total=67  match=67
   n                              (Ltj/g;)I                                                acc=0x0001
   o                              (Ltj/h;)I                                                acc=0x0001
   onC2CDownloadCompleted         (Ljava/lang/String;Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;)V  acc=0x0001
```

⚠️ 交叉验证（防工具单点出错）：`n` 前后是 `m(Ltj/m;)I` / `o(Ltj/h;)I`，`ok0.x0` 共 67 个方法、方法名列表连续完整（`<init> a b c d decodeSessionResponseBuf e f finalize g getSessionRequestBuf h i isUsingWangKaService j k keep_* l m n o onApplyFileIDReceived ... x`），**不是**「所有方法都只有 `<init>(B)V`」那种解析失败症状 ⇒ 结论可信。

### `ok0.x0#n` 的反汇编（决定性证据：它就是批量出口）

```bash
$PY _cache/_r14_walk.py dis classes13.dex 'Lok0/x0;' n
```

```
Lok0/x0;->n(Ltj/g;)I   acc=0x0001  code_off=0x3a9294
  [   8] new-instance           Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadRequest;
  [  10] invoke-direct          Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadRequest;-><init>()V
  [  13] iget-object            Ltj/k;->field_mediaId:Ljava/lang/String;
  [  15] iput-object            ...C2CDownloadRequest->fileKey:Ljava/lang/String;
  [  17] iget-object            Ltj/h;->X:Ljava/lang/String;
  [  19] iput-object            ...C2CDownloadRequest->url:Ljava/lang/String;
  [  21] iget-object            Ltj/h;->Z:Ljava/lang/String;
  [  23] iput-object            ...C2CDownloadRequest->referer:Ljava/lang/String;
  [  25] iget-boolean           Ltj/h;->c1:Z
  [  27] iput-boolean           ...C2CDownloadRequest->isColdSnsData:Z
  [  29] iget-object            Ltj/h;->d1:
  [  31] iput-object            ...->signalQuality:
  [  33] iget-object            Ltj/h;->e1:
  [  35] iput-object            ...->snsScene:
  [  59] iget-object            Ltj/g;->i1:Ljava/lang/String;
  [  61] iput-object            ...->feedId:Ljava/lang/String;          ★ 批量专属
  [  63] iget                   Ltj/g;->j1:I
  [  65] iput                   ...->feedPicCount:I                    ★ 批量专属
  [  67] iget-object            Ltj/g;->l1:[Lcom/tencent/mars/cdn/CdnLogic$BatchSnsReqImageData;
  [  69] iput-object            ...->batchSnsReqImageDatas:[...BatchSnsReqImageData;   ★ 批量专属
  [ 109] const/4                v4             #0
  [ 110] invoke-static          Lcom/tencent/mars/cdn/CdnLogic;->startSNSDownload(
             Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadRequest;
             Lcom/tencent/mars/cdn/CdnLogic$VideoStreamingCallback;
             Lcom/tencent/mars/cdn/CdnLogic$DownloadCallback;I)I
  [ 113] move-result
  [ 114] return
```

⇒ **`ok0.x0#n` 就是「朋友圈九宫格批量下载」的 Java 层最终出口**，它把 `feedId / feedPicCount / batchSnsReqImageDatas` 三个批量专属字段塞进 `C2CDownloadRequest`，然后调 `CdnLogic.startSNSDownload(req, videoCb, downloadCb, 0)`（`startSNSDownload` 本身是 native，不可直接 hook，但 `n` 可 hook）。

对照 `ok0.x0#o(Ltj/h;)I`（**单张** SNS 图片出口，同一 dex）：

```bash
$PY _cache/_r14_walk.py dis classes13.dex 'Lok0/x0;' o
```
```
  [  32] new-instance  C2CDownloadRequest
  [  37] iget  Ltj/k->field_mediaId  -> [39] fileKey
  [  41] iget  Ltj/h->X              -> [43] url
  [  45] iget  Ltj/h->Z              -> [47] referer
  [  49] iget  Ltj/h->p0 -> [51] setSavePath(String)         ★ 单张：落盘路径
  [  54] iget  Ltj/h->c1             -> [56] isColdSnsData
  [  58] iget  Ltj/h->d1             -> [60] signalQuality
  [  62] iget  Ltj/h->e1             -> [64] snsScene
  [  66] iget  Ltj/h->f1             -> [68] snsCipherKey     ★ 单张：SNS 解密 key
  ...
  [ 132] const/4                v5             #0
  [ 133] invoke-static CdnLogic->startSNSDownload(...)I       ★ 同样落在 startSNSDownload
```
⇒ `o` **不写** `feedId / feedPicCount / batchSnsReqImageDatas`，这是二者唯一的功能分界。

---

## Q2. 从 `jf3.f#k`（hitK=+9 证明活跃）正向追踪

### 结论：`jf3.f#k` **不直接**调 `ok0.x0` / `CdnLogic` / `SubCoreCdnTransport` 的任何具名方法；它只把任务丢给 `Lmq/k;->x8(Ltj/k;)Z`（即 `SubCoreCdnTransport.addRecvTask`），之后由 CDN 传输服务的队列 + `tryStart` 分派。

### 身份确证

```bash
$PY tools/_r13_findclass.py Ljf3/f;
# DEFINED in classes3.dex   super = Lcom/tencent/mm/pluginsdk/model/c3;   access = 0x0401
# methods(18): <clinit>, <init>, g, a, b, c, h, i, j, k, l, m, n, o, p, q, r, s
$PY _cache/_r14_walk.py dis classes3.dex 'Ljf3/f;' k
```
方法体开头自报家门（DEX class_defs 直读 + const-string 实证）：
```
  [   2] const-string  'doInBackground'
  [   4] const-string  'com.tencent.mm.plugin.sns.model.download.SnsCdnDownloadBase'
  [  40] const-string  '[image-flow]'
  [  42] const-string  'MicroMsg.SnsCdnDownloadBase'
```

### `jf3.f#k` 全量 invoke 清单（逐字，按 DEX 直读）

```bash
$PY _cache/_r14_walk.py dis classes3.dex 'Ljf3/f;' k > _cache/_r14_jf3f_k3.txt
grep -E "invoke" _cache/_r14_jf3f_k3.txt | sed -E 's/^ *\[ *[0-9]+\] +//' | sed -E 's/ +\{n=[0-9]+\} +/ /' | sort -u
```

**与 CDN 相关的关键 5 个**（其余是 StringBuilder / 日志 / VFS / 字符串处理）：

| # | invoke | 完整签名 | 所在 dex | 语义 |
|---|---|---|---|---|
| 1 | `invoke-direct Ltj/g;-><init>()V` | `Ltj/g;-><init>()V`（[630]/[632]） | classes3 | ★ **批量**任务 bean 构造（`f==9` 分支） |
| 2 | `invoke-direct Ltj/h;-><init>()V` | `Ltj/h;-><init>()V`（[672]/[674]） | classes3 | ★ **单张**任务 bean 构造（else 分支） |
| 3 | `invoke-direct Lcom/tencent/mars/cdn/CdnLogic$BatchSnsReqImageData;-><init>()V` | 同上（[806]/[808]） | classes3 | 批量请求项，逐个填 `url/picIndex/totalFileSize/decryptKey/imageCachePath/fileKey` |
| 4 | `invoke-direct Ljf3/d;-><init>(Ljf3/f;Ltj/h;J)V` | 同上（[1668]/[1672]） | classes3 | CDN 下载回调对象（`SnsCdnDownloadBase$1`） |
| 5 | `invoke-virtual Lmq/k;->x8(Ltj/k;)Z` | `Lmq/k;->x8(Ltj/k;)Z`（[1687]） | classes3 | ★ **唯一出口** = `SubCoreCdnTransport.addRecvTask` |

⛔ **`jf3.f#k` 内没有任何 `Lok0/x0;->*`、`CdnLogic->*`、`SubCoreCdnTransport->*` 的调用**（全量 invoke 去重清单里没有一条）。R10 文档里"`jf3.f#k` → `ok0/x0#o`"是**跨进程的调用链推断**，不是同一方法体内的直接调用。

**尾部原始证据**（方法出口）：
```
  [1668] new-instance           Ljf3/d;
  [1672] invoke-direct          Ljf3/d;-><init>(Ljf3/f;Ltj/h;J)V
  [1675] iput-object            Ltj/k->f:Ltj/i;               # 挂回调
  [1677] const-class            Lnq/e0;
  [1679] invoke-static          Ld94/n0->c(Ljava/lang/Class;)Ld94/m;
  [1683] check-cast             Lnq/e0;
  [1685] check-cast             Lmq/k;
  [1687] invoke-virtual         Lmq/k->x8(Ltj/k;)Z            ← addRecvTask
  [1691] if-eqz                 -> +15 (abs 1706)
  [1699] const-string           '[%s][media:%s]SubCoreCdnTransport addRecvTask succ mediaId:%s'
  [1712] const-string           '[%s][media:%s]SubCoreCdnTransport addRecvTask failed mediaId:%s'
```

**批量/单张分支原始证据**（`if-ne` = 不等则跳，偏移由 22t 第 2 个 code unit 解出）：
```
  [ 624] iget                   Ljf3/a->f:I                   # ★ f = reqDownType（见下方日志实证）
  [ 626] const/16               #9
  [ 628] if-ne                  -> +37  (abs 665)      # f != 9 → 跳到 665
  [ 630] new-instance           Ltj/g;                 # f == 9 → 批量任务 bean ★
  [ 632] invoke-direct          Ltj/g;-><init>()V
  [ 635] const-string/jumbo     'task_SnsCdnDownloadBase_1'
  ...
  [ 665] invoke-virtual         Ljf3/a->c()Ljava/lang/String;   # ← else 分支
  [ 669] invoke-static          Lcom/tencent/mm/vfs/o6->v(...)Z
  [ 672] new-instance           Ltj/h;                 # f != 9 → 单张任务 bean ★
  [ 674] invoke-direct          Ltj/h;-><init>()V
  [ 677] const-string/jumbo     'task_SnsCdnDownloadBase_2'
  ...
  [ 762] instance-of            Ltj/g;
  [ 764] if-eqz                 -> +174 (abs 938)      # 不是 tj/g 就跳过批量填充
  [ 767] check-cast             Ltj/g;
  [ 769] iput-object            Ltj/g->i1:Ljava/lang/String;    # feedId
  [ 771] iget                   Ljf3/a->r:I
  [ 773] iput                   Ltj/g->j1:I                     # feedPicCount
  [ 779] iput                   Ltj/g->k1:I                     # 图片总数
  [ 781] new-array              [Lcom/tencent/mars/cdn/CdnLogic$BatchSnsReqImageData;
  [ 783] iput-object            Ltj/g->l1:[...BatchSnsReqImageData;
  ... 循环填每一项：
  [ 853] iput-object            ...BatchSnsReqImageData->url:
  [ 855] iput                   ...->picIndex:I
  [ 858] iput                   ...->totalFileSize:I
  [ 862] iput-object            ...->decryptKey:            ← Lq54/vm3->L
  [ 894] iput-object            ...->imageCachePath:        ← o6.i(StringBuilder, 2)  ★ Java 侧落盘路径
  [ 918] iput-object            ...->fileKey:
```

`Ljf3/a->f` = **reqDownType** 的实证（同一方法内的日志格式串 + 实参顺序）：
```
  [1367] const-string '[%s][media:%s]attachSnsImgTaskInfo success reqDownType:%d taskInfo:%s'
  [1375] iget-object  Ltj/k->field_mediaId
  [1380] iget         Ljf3/a->f:I      → [1382] Integer.valueOf  → 数组 [2]
```

---

## Q3. 谁调用了 `ok0.x0#n`？真正的批量入口是谁？

### 结论：**调用点 = 2 处**（不是 0），都在 `classes7.dex`；`n` 是**真实存在的批量入口**，hitN=0 是**门控**问题，不是「方法不存在/没被调用者」。

```bash
$PY _cache/_r14_walk.py callers 'Lok0/x0;' n
# === classes7.dex  目标 method_idx: m50342=Lok0/x0->n(Ltj/g;)I
#    Lok0/j;    Lok0/j->invoke(Ljava/lang/Object;)Ljava/lang/Object;    via invoke-virtual
#    Lok0/m1;   Lok0/m1->m(Z)V                                          via invoke-virtual
# === classes13.dex  目标 method_idx: m58386（定义所在 dex，dex 内无调用点）
# 合计调用点 2 处
```

**独立工具交叉验证**（`tools/_r4_dexref.py`，另一个作者写的、不同的 opcode 判定逻辑，结论一致 ⇒ 排除我脚本的系统性误判）：

```bash
$PY tools/_r4_dexref.py callers classes7.dex 'Lok0/x0;->n'
# 目标 method_idx: [50342]
# === 调用点（2 处）===
#    Lok0/j;  -> Lok0/j->invoke(Ljava/lang/Object;)Ljava/lang/Object;
#    Lok0/m1; -> Lok0/m1->m(Z)V
```

### 两个调用者的身份

| 调用者 | 原始类名 / 身份 | 证据 |
|---|---|---|
| `Lok0/m1;->m(Z)V` | `CdnTransportService#tryStart` | 方法体内 const-string `'MicroMsg.CdnTransportService'`、`'summersafecdn tryStart queue:%d'`、`'summersafecdn id:%s cdnautostart:%s ...'` |
| `Lok0/j;->invoke(Object)Object` | Kotlin `Function1` lambda，`super = Lkotlin/jvm/internal/m`；由 `Lok0/n;->f2(Lok0/n;Ljava/util/concurrent/ConcurrentHashMap;Ltj/k;Lkotlinx/coroutines/flow/a2;)V` `new-instance` 出来；`Lok0/n` 的 super 是 `Lb80/g`（`androidx/lifecycle` 系） | `newinst Lok0/j;` 输出 + `findclass Lok0/j;` |
| （上游）`Lmq/k;->z8(Ltj/k;)Z` → `ok0.m1#m` | addRecvTask 的下游 | `callers Lok0/m1; m` 输出 |

### `ok0.m1#m`（tryStart）里的分派原始证据

```bash
$PY _cache/_r14_walk.py dis classes7.dex 'Lok0/m1;' m > _cache/_r14_ok0m1_m.txt
```
```
  [ 755] iget-boolean           Ltj/k->E:Z
  [ 757] if-eqz                 -> +36  (abs 793)      ⚠️ if-eqz = 为零跳 ⇒ E==false 就跳过整段 SNS 分派
  [ 759] instance-of            Ltj/g;
  [ 761] if-eqz                 -> +15  (abs 776)      # 不是 tj/g → 776
  [ 764] check-cast             Ltj/g;
  [ 766] invoke-static          Lok0/s2->c9()Lok0/x0;
  [ 770] invoke-virtual         Lok0/x0->n(Ltj/g;)I    ← ★ 批量
  [ 776] instance-of            Ltj/h;
  [ 778] if-eqz                 -> +209 (abs 987)
  [ 781] check-cast             Ltj/h;
  [ 783] invoke-static          Lok0/s2->c9()Lok0/x0;
  [ 787] invoke-virtual         Lok0/x0->o(Ltj/h;)I    ← ★ 单张
```

`ok0.j#invoke` 里是**同样结构**的一段（`[172] iget-boolean Ltj/k->E` / `[174] if-eqz` / `[176] instance-of Ltj/g` / `[186] n` / `[192] instance-of Ltj/h` / `[202] o`），说明新旧两套 CDN 传输服务都走同一套分派规则。

### 真正的「批量请求入口」链（完整，自顶向下）

```
UI：SnsMultiLineImageLineMgr 系（com.tencent.mm.plugin.sns.ui.dk）
  ┌ dk#e(SparseArray mediaObjs, SparseArray imageViews, int, int, x7, String)   ← 由 Llh3/g2#C / Lxh3/j0#D 调用
  │     ├─ [80] dk.a() == false → 逐张：l0#j(...) + dk#h(...)        （单张路径）
  │     └─ [99] dk.a() == true && mediaObjs.size() > 0 → dk#i(...)   （★ 批量路径）
  └ dk#d(PhotosContent, TimeLineObject, String, ...)  = setImages     ← 由 com.tencent.mm.plugin.sns.ui.item.s0#p(...)
        │                                                              （BaseTimeLineItem 子类）与 SnsCommentDetailUI 调用
        ├─ [629]/[873] if-nez v35 → 跳过
        ├─ [631]/[875] dk.a() == false → 跳过（[635]/[879] if-eqz）
        ├─ [637]/[881] SparseArray.size() <= 0 → 跳过（[641]/[885] if-lez）
        └─ [655]/[899] dk#i(...)                                     （★ 批量路径，另一条）
                 ↓
              dk#i(...)  = setSnsMultiImg                            （★ 两条路在此汇合）
                   日志 'enter group img download logic!'（MicroMsg.LazyerImageLoader2）
                   → [329] new com.tencent.mm.plugin.sns.model.o1(...) + Executor.execute
                        = LazyerImageLoader2$4
                        → [98] l0#b(String, SparseArray, SparseArray, x7, I, I)
                             = DownloadManager#addBatchDownloadSns
                             → [274] new Lbf3/n0(String, SparseArray, 9, String, x7, I)
                                     ↑ requestType(b) = 9   ★ 批量的身份标记
                             → [493] l0#z()  = tryStartNetscene
                                  → [1245] new Ljf3/a(String, SparseArray, SparseArray, n0.k)
                                           → jf3/a.f = reqDownType = 9
                                  → 选下载器：jf3/j(SnsDownloadImage) / jf3/h / jf3/l / jf3/m / jf3/g
                                  → [1594] jf3/f#j()  → jf3/f#k（doInBackground）
                                       → [630] new Ltj/g  ★（reqDownType==9）
                                       → [1687] Lmq/k#x8(Ltj/k;)Z = addRecvTask
                                            → ok0.m1#m(Z) = CdnTransportService#tryStart
                                                 → [770] ok0.x0#n(Ltj/g;)I  ★★
                                                      → CdnLogic.startSNSDownload(req, videoCb, dlCb, 0) [native]
```

---

## Q4. 触发条件：什么时候走「批量」而不是「流式单张」？

### 结论：**共 5 道门，串联。缺任何一道都落到 `ok0.x0#o`（单张）。**

| # | 门 | 判据（DEX 直读） | 我们的状态 |
|---|---|---|---|
| G1 | `dk.a() == CdnLogic.allowBatchImageDownload() && dk.j` | `dk#a` 反汇编（R10 已确证）；`dk#e` [80]/[84] `if-nez` | ✅ true（探针实测） |
| G2 | **批量 UI 入口被调用**：`dk#i` 只有 2 个调用点（`callers dk#i` 实证）：<br>• `dk#e`（← 调用方只有 `Llh3/g2#C` 与 `Lxh3/j0#D` 两个时间线渲染器）<br>• `dk#d`（= `setImages`，← `com.tencent.mm.plugin.sns.ui.item.s0#p(...)`，是 `BaseTimeLineItem` 子类；以及 `SnsCommentDetailUI$46#onViewAttachedToWindow` 与其 `m7()`）<br>另外 `dk#d` 在 `dk.a()` 之前还有一道 `if-nez v35`（[629]/[873]） | `callers dk#i` / `callers dk#e` / `callers dk#d` 输出 | 🟡 未确证 |
| G3 | `mediaObjs SparseArray.size() > 0` | `dk#e` [105]/[109] `if-lez → 126`（126 = 逐张分支）；`dk#d` [637-641]/[881-885] 同样 | 🟡 |
| G4 | `dk#i` 里 `mediaObjs.size() == imageViews.size()` 且不等于 5（5 是「不匹配」哨兵） | `dk#i` [61] `if-ne` / [66] `if-ne v6(5), v15 → 91`，不等则 [68-89] 打 `'setSnsMultiImg check params, mediaSize(%d) != imgViewSize(%d).'` 并 return | 🟡 |
| G5 | `dk#i` 里 **至少有一张没命中缓存**（`needDownload.size() != 0`） | `dk#i` [238-297] 逐张 `d1#i(...)` → `t0#g(...)`；命中就 `'setSnsMultiImg, cache hit.'`；[310]/[317] `if-eqz → 350`（直接 return，不下载） | 🟡 |
| G6 | 下游：任务 bean 必须是 `Ltj/g`（即 `jf3/a.f(reqDownType) == 9`） | `ok0.m1#m` [755][759]；`jf3/f#k` [624-630] | ⛔ 未满足（hitN=0） |

### 关键门 G6 的细节（已确证）

- `ok0.m1#m` / `ok0.j#invoke` 的第一道判断 `Ltj/k->E:Z` **恒为 true**（对 SNS 任务）：
  ```bash
  $PY _cache/_r14_walk.py dis classes3.dex 'Ltj/h;' '<init>'
  #  [   0] invoke-direct  Ltj/k-><init>()V
  #  [   7] const/4        v0  #-1
  #  [  10] const/4        v0  #0
  #  [  13] const/4        v0  #1
  #  [  14] iput-boolean   Ltj/k->E:Z        ★ 构造函数里写死 = true（v0=#1）
  ```
  `Ltj/k->E` 的全部写点（`fieldref`）只有 `Ltj/k#<init>`（classes7 [144]）与 `Ltj/h#<init>`（classes3 [14]），**没有任何业务代码改写它** ⇒ 这道门对所有 SNS 任务都是开的。
- 于是 **真正的分水岭只剩 `instanceof Ltj/g`**。
- `Ltj/g` 在整个 APK 里**只有一个 `new-instance`**：
  ```bash
  $PY _cache/_r14_walk.py newinst 'Ltj/g;'
  # === classes3.dex
  #    Ljf3/c;   Ljf3/c->run()V                                     check-cast
  #    Ljf3/d;   Ljf3/d->C2(String,I,Ltj/e;Ltj/f;Z)I                check-cast
  #    Ljf3/f;   Ljf3/f->k([Ljava/lang/String;)Ljava/lang/Integer;  check-cast,instance-of,new-instance  ★ 唯一构造点
  # === classes7.dex （ok0.i1#run / ok0.j#invoke / ok0.m1#m）全部只是 check-cast + instance-of
  ```
- `jf3/f#k` 里 `new Ltj/g` 的唯一条件：`Ljf3/a->f == 9`（`[624] iget` / `[626] const 9` / `[628] if-ne → 665`，665 之后是 `new Ltj/h`）。
- `jf3/a->f` 的写点只有两处（`fieldref Ljf3/a; f`）：
  - `Ljf3/a#<init>(String, SparseArray, SparseArray, I)`（第 4 参）
  - `Ljf3/a#i(I)Ljf3/a;`
  - 而这两个的调用点**都只有 `Lcom/tencent/mm/plugin/sns/model/l0;->z()V`**（`DownloadManager#tryStartNetscene`）一处。
- `l0#z` 里：[1225] `if-ne v11, #9 → 1249`（1249 是 `new Ljf3/a(String)` 单参构造 = 非批量）；等于 9 才走 [1227] `new Ljf3/a(String, SparseArray, SparseArray, Lbf3/n0->k)`。
- `requestType == 9` 的唯一产地：`l0#b` = `addBatchDownloadSns`
  ```
  [ 274] new-instance           Lbf3/n0;           # com.tencent.mm.plugin.sns.data.SnsDownLoadElment
  [ 276] const/16               #9
  [ 287] invoke-direct/range    Lbf3/n0-><init>(Ljava/lang/String;Landroid/util/SparseArray;I
                                                Ljava/lang/String;Lcom/tencent/mm/storage/x7;I)V
  ```
  构造体映射（`dis Lbf3/n0 '<init>'` 实证）：`p1→i, p2→j(mediaObjs), p3→b, p4→c, p5→f, p6→k` ⇒ **第 3 参 9 落在 `b`，而 `b` 就是 `getRequestType`**（`l0#z` [289] `iget Lbf3/n0->b:I` 紧跟 const-string `'getRequestType'` / `'...SnsDownLoadElment'`）。

### 对 hitN=0 的解释（最重要的一条）

`hitK = +9` 说明 `SnsCdnDownloadBase#doInBackground` **确实跑了 9 次**，但每一次走到 [628] 时 `reqDownType != 9`，于是全部新建 `Ltj/h` → `addRecvTask` → `ok0.m1#m` → `[759] instance-of Ltj/g` 为假 → `[787] ok0.x0#o(Ltj/h;)I`（单张路径）→ `startSNSDownload`。
**`ok0.x0#o` 才是这次真机跑的那个方法，`n` 一次都没轮到。** 探针钩错了分支：`n` 只在「九宫格整组批量拉取」时才会被调到。

另外 `Ljf3/j / Ljf3/g / Ljf3/h / Ljf3/l / Ljf3/m` 全都**没有覆写 `k`**（各自方法表里只有 `l / q / h / o / t / v / x / r`，无 `k`）⇒ `jf3.f#k` 是唯一的 `doInBackground`，hitK 覆盖全部下载类型，这一点排除了「子类覆写导致基类钩子漏掉批量调用」的可能。

### 真机判据（如何验证）

1. 钩 `Lok0/x0;->o(Ltj/h;)I`，滚朋友圈 3 屏：**期望计数 > 0**（应与 hitK 同量级）。若 `o` 有命中而 `n` 为 0 ⇒ 证实「走了单张路径」。
2. 钩 `Lcom/tencent/mm/plugin/sns/ui/dk;->e(...)` 与 `->i(...)`，滚朋友圈：**期望计数 > 0**。若 `e` 命中且 `dk.a()` 为 true 但 `i` 为 0 ⇒ 卡在 G3（`mediaObjs.size()==0`）。
3. 钩 `Lcom/tencent/mm/plugin/sns/model/l0;->b(...)`（addBatchDownloadSns）：**期望计数 > 0**。为 0 ⇒ 卡在 G2~G5。
4. 钩 `Ljf3/a;->i(I)Ljf3/a;` 打印入参：看到 `9` ⇒ 批量已启动；只看到非 9 ⇒ 批量未启动。
5. 强推批量的实验判据：Xposed 里把 `dk.j` 反射置 true（`dk.a()` 已 true），再**清空 SNS 图片缓存**（让 G5 一定成立），滚到一个**多图（≥2 张）**的 feed：期望 `l0#b` 与 `ok0.x0#n` 同时出现计数 > 0。

---

## Q5. 可直接 hook 的替代锚点清单（按「能否拿到 url / aeskey / 落盘路径」排序）

> 「能否 hook」判据：access 里**没有** `0x100 (ACC_NATIVE)`，即 code_off != 0。`CdnLogic.start*Download` 全部 native ⇒ 只能钩调用方/回调。

| 排序 | 类名#方法名 | 完整签名 | dex | 参数语义 / 能拿到什么 | 期望频次 |
|---|---|---|---|---|---|
| **1** | `ok0.x0#o` | `Lok0/x0;->o(Ltj/h;)I` | classes13 | **单张 SNS 图片出口**。`Ltj/h.X`=url、`.Z`=referer、`.p0`=**落盘绝对路径**（→ `setSavePath`）、`.f1`=**snsCipherKey**、`.c1`=isColdSnsData、`.d1`=signalQuality、`.e1`=snsScene；`Ltj/k.field_mediaId`=fileKey | **热路径**：每张图 1 次（先钩它验证） |
| **2** | `ok0.x0#n` | `Lok0/x0;->n(Ltj/g;)I` | classes13 | **批量出口**。`Ltj/g.i1`=feedId、`.j1`=feedPicCount、`.l1[]`=`BatchSnsReqImageData[]`（每项含 `url/decryptKey/fileKey/imageCachePath/picIndex/totalFileSize`）；url/referer 同 `o` | **冷路径**：每次批量 1 次（现状 0） |
| **3** | `jf3.f#k` | `Ljf3/f;->k([Ljava/lang/String;)Ljava/lang/Integer;` | classes3 | `SnsCdnDownloadBase#doInBackground`。能拿到 `Ljf3/f.f : Ljf3/a`（`.c`=url、`.s/.t`=aesKey/authKey、`.a : Lq54/vm3` 的 `.L`=decryptKey、`.I`=urlEncKey、`.E`=id）以及**构造出的 `BatchSnsReqImageData`**（仅批量分支） | 每次下载 1 次（实测 +9） |
| **4** | `CdnLogic$BatchSnsReqImageData` 字段 | `url / decryptKey / fileKey / imageCachePath / picIndex / retry / totalFileSize` | classes13（定义）/ classes3（写点） | **Java 侧唯一写点就在 `jf3/f#k`**（`[853]/[862]/[894]/[918]`）⇒ 在 `k` 里反射读这些字段能拿到**带鉴权的完整请求体** | 仅批量分支 |
| **5** | `l0#b` | `Lcom/tencent/mm/plugin/sns/model/l0;->b(Ljava/lang/String;Landroid/util/SparseArray;Landroid/util/SparseArray;Lcom/tencent/mm/storage/x7;II)V` | classes3 | `DownloadManager#addBatchDownloadSns` — **批量入队点**，两个 SparseArray 就是 mediaObjs / imageViews | 每次批量 1 次 |
| **6** | `dk#i` | `Lcom/tencent/mm/plugin/sns/ui/dk;->i(Ljava/lang/String;Landroid/util/SparseArray;Landroid/util/SparseArray;ILcom/tencent/mm/storage/x7;I)V` | classes3 | UI 层批量入口（`setSnsMultiImg`），能判断「为何没进批量」 | 每个多图 feed 渲染 1 次 |
| **7** | `dk#e` / `dk#a` | `dk->e(SparseArray;SparseArray;IILx7;Ljava/lang/String;)V` / `dk->a()Z`（static, acc=0x9） | classes3 | 批量开关决策点 | `e`：每个多图项 1 次 |
| **8** | `ok0.m1#m` | `Lok0/m1;->m(Z)V` | classes7 | `CdnTransportService#tryStart`，**所有** CDN 任务的分派中枢；[755] 处的 `Ltj/k` 就是当前任务 | **极热**（所有类型） |
| **9** | `Lmq/k#x8` | `Lmq/k;->x8(Ltj/k;)Z` | classes3 等 | `SubCoreCdnTransport.addRecvTask`，任务刚入队时的 `Ltj/k` 全字段 | 每次下载 1 次 |
| **10** | `ok0.x0#onC2CDownloadCompleted` | `(Ljava/lang/String;Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;)V` | classes13 | 结果回调（已验证 hitCB>0）。⚠️ `picCachePath` 只在批量时才有值，见 Q6 | 每次完成 1 次 |

补充：`ok0.x0#r(String,String,String,I,String,String,I,Z)I`、`ok0.x0#k(...)`、`ok0.x0#q(...)` 是 C2C/其它业务的入口，**不是** SNS 图片，不要误钩。

---

## Q6（加分）`picCachePath` 为什么是空串

### 结论：**它在 Java 层只在构造函数里被赋成 `""`，此后全 APK 再无任何 Java 写点 ⇒ 只有 native（mars）会填它，而且只有「批量 SNS」这条 native 路径会填。**

### 证据 1：字段确实存在，初值就是空串

```bash
$PY _cache/_r14_fields.py fields 'Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;'
#    instance  picCachePath                       Ljava/lang/String;   acc=0x0001
#    instance  batchPicFeedId                     Ljava/lang/String;   acc=0x0001
#    instance  batchImageFileKey                  Ljava/lang/String;   acc=0x0001
#    instance  batchImageNeedRetry                [...BatchSnsReqImageData;  acc=0x0001
```
（共 73 个字段；`picCachePath` 与三个 `batch*` 字段相邻 ⇒ 语义上属于同一组「批量图片结果」字段，与探针 `hitNoField=0` 一致）

```bash
$PY _cache/_r14_walk.py dis classes13.dex 'Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;' '<init>'
#  [  12] const-string           v3  ''
#  [  16] iput-object            v3 -> ...->fileid:Ljava/lang/String;
#  ...
#  [ 111] iput                   v4 -> ...->picIndex:I
#  [ 113] iput-object            v3 -> ...->picCachePath:Ljava/lang/String;      ★ = ""
#  [ 115] iput-object            v3 -> ...->batchPicFeedId:Ljava/lang/String;    ★ = ""
#  [ 117] iput-object            v3 -> ...->batchImageFileKey:Ljava/lang/String; ★ = ""
```

### 证据 2：全 dex 写点扫描——只有 `<init>` 一处

```bash
$PY _cache/_r14_walk.py fieldref 'Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;' picCachePath
# === classes13.dex  目标 field_idx: {7391: '...->picCachePath:Ljava/lang/String;'}
#    ...C2CDownloadResult;  ...C2CDownloadResult-><init>()V      [113] iput-object
# （15 个 dex 全扫，仅此 1 处，且是构造函数内的初始化）

$PY _cache/_r14_walk.py fieldref '...C2CDownloadResult;' batchImageFileKey   # 同样只有 <init> [117]
$PY _cache/_r14_walk.py fieldref '...C2CDownloadResult;' batchPicFeedId      # 同样只有 <init> [115]
```

⇒ **没有任何 Java 代码（含所有 dex、所有 `iput-object`）给 `picCachePath` 赋过非空值**。它要么保持 `""`，要么由 JNI 直接改对象内存（mars 的 `onC2CDownloadCompleted` 回调对象就是在 native 侧填的）。

### 与真机实测的一致性

`hitPicE=7 / hitPicNE=0` 且 `n` 从未被调用 ⇒ 这 7 次回调全部来自**单张路径**（`o` → `startSNSDownload`），native 侧走的是单张结果填充分支，**不填 `picCachePath`**。所以「读 `C2CDownloadResult.picCachePath`」这条路线在单张场景下**永远拿不到东西**，是死路。

### 替代方案（按推荐度）

1. **单张路径拿落盘路径**：钩 `ok0.x0#o(Ltj/h;)I`，读入参 `Ltj/h->p0`（它被 `setSavePath()` 用掉，就是最终落盘绝对路径）；同时 `Ltj/h->f1` = snsCipherKey、`X` = url。
2. **批量路径拿落盘路径**：钩 `jf3/f#k` 或钩 `ok0.x0#n`，读 `Ltj/g->l1[i].imageCachePath` —— 这个字段**是 Java 侧自己填的**（`jf3/f#k` [894]，值 = `o6.i(StringBuilder(...), 2)`），不依赖 native。
3. **结果侧**：`C2CDownloadResult` 里可用的是 `fileid`、`realUsedURL`、`httpResponseHeader`、`fileSize`、`errorCode`、`isSnsImageProtocolAvailable` 这些（同样由 native 填，但语义不依赖批量开关）；批量专属的 `batchPicFeedId` / `batchImageFileKey` / `batchImageNeedRetry[]` 只有进了 `n` 才有值。
4. **不推荐**：继续读 `picCachePath`。判据：只有当 `ok0.x0#n` 计数 > 0 时，它才可能非空。

---

## 附录：本轮自建工具与踩坑记录

- `_cache/_r14_walk.py`（临时脚本，未改 `tools/`）：mmap 流式读 dex，字符串/类型/方法/字段**按需解码 + 缓存**；**完整 opcode 宽度表**线性步进（修正了 `tools/_r6_snsdis.py` 的 `_width()` 表：`invoke-virtual 0x6c` 被当 2 units、`new-instance 0x22` / `check-cast 0x1f` / `instance-of 0x20` 被当 1 unit、`filled-new-array 0x24/0x25` 被当 5 units —— `invoke-virtual` 是最常见指令，按 2 units 步进会导致后续**全部错位**）；另修正分支偏移：`21t/20t` 取第 2 个 code unit、`22t` 取第 2 个 code unit、`10t(goto)` 取自身高字节有符号 8 位、`30t` 取 32 位。
- 子命令：`dis <dex> <L类;> <方法名> [签名子串]` / `callers <L类;> <方法名>` / `fieldref <L类;> <字段名>` / `newinst <L类;>`。
- 交叉验证纪律：凡关键结论都用两个独立实现跑一遍（`_r14_walk.py` + `tools/_r4_dexref.py`，或 `_r14_fields.py` + `tools/_r13_findclass.py`）。`ok0.x0#n` 的 2 个调用点、方法签名，均已双实现一致。
- 已遵守：`if-eqz` = 为零跳 / `if-nez` = 非零跳；找类定义一律走 `class_defs` 遍历（`def_index`），不用 `type_ids` 下标。

[DONE]
