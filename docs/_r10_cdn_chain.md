# M1 CDN 图片下载调用链取证

> lane: M1（纯离线静态取证）｜目标：微信 8.0.48（`_cache/dex` 共 15 个 dex，classes*.dex）
> 约束遵守：未调用 adb、未改 `wxgroup/app/src/main/`、未改 `docs/取证结论.md`；本轮只写本文件。
> 工具：`tools/_r6_snsdex.py`、`tools/_r6_snsdis.py`、`tools/_r4_dexref.py`
> ⚠️ `_r6_snsdis.py` 的偏移是估算值，仅用于判相对顺序；本轮所有寄存器级结论均来自下面第 0 步的自写解析器。

## 结论摘要

1. **✅ `createSNSDownload` 全 APK 只有 1 个外部调用点，且它不在朋友圈（Sns）链路上。**
   该调用点 `Lhj0/t;->invokeSuspend` 实际是 **MJTemplate Flutter 插件的 Maas 音乐短视频 demo 素材下载**
   （同类方法体里出现 `'MicroMsg.MJTemplateApiFlutterPlugin'`、`'maas_music_demo_resource'`、
   `'MicroMsg.Maas.MusicVideoResManager'`）。第 2 个引用是 `CdnLogic.createGroupNoticeDownload` 的内部互调，与外部业务无关。
   ⇒ **直接 hook `createSNSDownload` 大概率抓不到朋友圈图片请求。**

2. **✅ 朋友圈图片下载不走 `createSNSDownload` / `startSNSDownload`，走的是 `SubCoreCdnTransport.addRecvTask`。**
   真正的 Sns 图片下载在 `Ljf3/f;->k([Ljava/lang/String;)Ljava/lang/Integer;`
   （`SnsMethodCalculate` 标记原名 = `com.tencent.mm.plugin.sns.model.download.SnsCdnDownloadBase`，方法名 `doInBackground`），
   尾巴两行日志为 `'[%s][media:%s]SubCoreCdnTransport addRecvTask succ/failed mediaId:%s'`。
   ⇒ 要拿带鉴权的请求体，应 hook `addRecvTask`（或其下游 `Lok0/x0;->o(Ltj/h;)I`，后者才是调 `startSNSDownload` 的地方）。

3. **✅ 批量图片开关 `dk.a()` = `CdnLogic.allowBatchImageDownload() && dk.j`，是「与」不是直接返回。**
   任一为假即 `return false` 并打印 `'groupDownloadDisable!!!'`；两者都真才 `return true`。
   其中 `dk.j` 是**非 final 静态布尔**，只在 `dk.<init>` 里由一次服务调用结果赋值（🟡赋值来源未逐条确认）。
   ⇒ 这个开关**至少有两道门**：native 侧的 `allowBatchImageDownload()` + Java 侧的 `dk.j`，都可成为 hook/翻转点。

4. **✅ `startSNSDownload` 共 12 个调用点，全部属于「通用 CDN 传输引擎」而非 Sns 业务**：
   `CdnTransportEngine`/`CdnTransportService`(MicroMsg.CdnTransportService, 'summersafecdn ...')、`MicroMsg.OnlineVideoService`、
   小程序 appbrand 视频封面、红包 luckymoney、`Lok0/x0;`、`Lhj0/t;`。
   ⇒ `Lok0/x0;->o(Ltj/h;)I` 是 Sns 图片最终落到 `startSNSDownload` 的最靠近出口。

5. **🟡 fileKey / aeskey / snsCipherKey 在本版本与 Sns 图片链路无直接数据流关系。**
   `CdnLogic$BatchSnsReqImageData`(url/decryptKey/fileKey/imageCachePath/picIndex/retry/totalFileSize) 确实被使用，
   写满这些字段的是 `Ljf3/f;->k`（同一个 SnsCdnDownloadBase）与 `Ljf3/d;->C2`；
   Sns 数据侧来源字段为 `Ljf3/a;` 的 `.a`(Lq54/vm3; Sns MediaObj protobuf) / `.c` / `.f` / `.l`，
   以及 `Ljf3/f;` 的 `.h` / `.j` / `m()` 结果，落地到 CDN task bean `Ltj/h;` 的 `X/Y/Z/d1/e1/f1/x0/y0/a1..h1`。
   `C2CDownloadRequest.fileKey/url/aeskey/snsCipherKey` 的精确对应关系 ⛔ 未知（字段名全被混淆）。

---

## 逐步证据

### 第 0 步 — 自写「寄存器级」反汇编器（临时 inline python，未落盘） `[DONE]`

理由：`tools/_r6_snsdis.py` 只输出 opcode 族 + 操作数，**不输出寄存器**，无法回答「参数 n 来自哪个寄存器/字段」。
本轮用 `$PY - <<'PY'` heredoc 方式跑了一个完整 dalvik 解码器（全 opcode 宽度表 + packed/sparse-switch payload 对齐跳过 +
`35c` 的 `{vC,vD,vE,vF,vG}` 正确 nibble 拆分 + `3rc` 起始寄存器 + `const/4` 按 1 code unit 宽）。
所有关键结论都用**两种独立方法交叉验证**：
(a) 线性解码； (b) **offset 级超集扫描**——在每个 dex 里按 2 字节对齐扫描所有 `invoke-*` 候选位置，再反查其所属 `code_item`，
该方法不受线性解码失步影响。

### 第 1 步 — 定位 `Lhj0/t;` 并解出 `createSNSDownload` 5 个实参 `[DONE]`

```bash
$PY tools/_r6_snsdex.py types 'hj0/t'
# === classes3.dex (1) ===
#     Lhj0/t;
$PY tools/_r6_snsdex.py fields 'Lhj0/t;'
#    [23884] d  Ljava/lang/Object;
#    [23885] e  I
#    [23886] f  Lq54/gk5;
$PY tools/_r6_snsdex.py methods 'Lhj0/t;'
#    <init>(Lq54/gk5;Lkotlin/coroutines/Continuation;)V / create / invoke / invokeSuspend
$PY tools/_r4_dexref.py callers classes3.dex 'Lhj0/t;-><init>('
#    Lhj0/t;->create(...)                （协程自身 create）
#    Lhj0/u;->invokeSuspend(...)         ← 上一层挂起 lambda
$PY tools/_r6_snsdex.py fields 'Lq54/gk5;'
#    classes3: [38009] d I / [38010] e Ljava/lang/String; / [38011] f Ljava/lang/String;
#    方法含 compareContent(Lcom/tencent/mm/protobuf/a;) → 是 protobuf 生成的 message
```

`invokeSuspend` 寄存器级关键片段（自写解析器输出，节选）：

```
[  29] const-string     v12 <- '$it'
[  31] iget-object      v1  <- v11.f :Lq54/gk5;          # this.f（协程捕获的入参对象）
[  49] const-string     v3  <- 'maas_music_demo_resource'
[  51] invoke           Lcom/tencent/mm/vfs/h7;->b(Ljava/lang/String;)Ljava/lang/String;  {v3}
[  54] move-result-object v3                              # v3 = VFS 根目录
[  71] iget-object      v3  <- v1.f :Ljava/lang/String;   # gk5.f
[  80] invoke           Lcom/tencent/mm/vfs/o6;->i(Ljava/lang/String;Z)Ljava/lang/String; {v3,v2}
[  83] move-result-object v3                              # v3 = dir + '/' + gk5.f 的绝对本地路径
[ 133] iget-object      v7  <- v1.f :Ljava/lang/String;
[ 148] const-string     v7  <- ' already cached at '
[ 168] const-string     v6  <- ', url: '
[ 173] iget-object      v6  <- v1.e :Ljava/lang/String;   # ← 日志里 url 位置的实参
[ 193] iget-object      v4  <- v1.f :Ljava/lang/String;
[ 195] iget-object      v7  <- v1.e :Ljava/lang/String;
[ 197] iget             v8  <- v1.d :I
[ 201] const            v2  #20304
[ 204] const            v2  #20302
[ 206] const            v8  #251
[ 208] invoke  Lcom/tencent/mars/cdn/CdnLogic;->createSNSDownload(
              Ljava/lang/String;Ljava/lang/String;IILjava/lang/String;)
              Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadRequest;
              {v4, v7, v8, v2, v3}
[ 211] move-result-object v2                              # C2CDownloadRequest
[ 212] new-instance    v4 <- Lhj0/s;
[ 214] invoke  Lhj0/s;-><init>(Lq54/gk5;Ljava/lang/String;Lcom/tencent/mm/vfs/j6;
              Lkotlin/coroutines/Continuation;)V {v4, v1, v3, v6, v12}
```

**参数推断（`createSNSDownload(p1,p2,p3,p4,p5)`）**

| 位 | 实参 | 来源 | 置信度 | 依据 |
|---|---|---|---|---|
| p1 | String | `Lq54/gk5;.f` | 🟡 推断（偏向 fileKey/资源名） | 同一个 `.f` 在 `[71]` 被用于拼本地文件名 `dir + '/' + gk5.f`，说明它是**文件名/id 而非 url**；且它不是日志里 `, url: ` 后面那个字段 |
| p2 | String | `Lq54/gk5;.e` | ✅ 确定是 **url** | 同一方法内日志构造顺序 `[133]gk5.f + ' ' + [143]gk5.d + ' already cached at ' + 本地路径 + ', fileSize: ' + size + ', url: ' + [173]gk5.e`，即 `.e` 落在 `url:` 之后 |
| p3 | int | 常量 **251** | ✅ 确定是常量 | `[206] const v8 #251`，直接覆盖 `[197]` 读到的 `gk5.d`；语义（fileType?）⛔未知 |
| p4 | int | 常量 **20302 / 20304**（`[199]` 分支二选一） | ✅ 确定是常量 | `[201] const v2 #20304 → [203] goto → [204] const v2 #20302`；语义（scene/flag?）⛔未知 |
| p5 | String | `v3` = `o6.i(<VFS 根>/<gk5.f>, bool)` 的返回值 | ✅ 确定是**本地保存绝对路径** | 同一寄存器 `v3` 紧接着被用于 `q7.a(v3)` 建文件对象做「已缓存则跳过」判断；且它是 `Lhj0/s;-><init>` 的第 2 个参数（String 位），回调靠它定位文件 |

> ⛔ `gk5.d`(int) 只参与 `[199]` 的分支判断与日志，未作为实参传入 `createSNSDownload`。

**功能归属** `[DONE]`：同一 `invokeSuspend` 内 `'maas_music_demo_resource'`、`'MicroMsg.Maas.MusicVideoResManager'`；
上层 `Lhj0/u;` ← `Lhj0/n;` → 内含 `'MicroMsg.MJTemplateApiFlutterPlugin'`、`'getResourceList'`、`getResourceList: `、
`com.tencent.pigeon.MJTemplateApi$Result.success`。
⇒ **这是 MJTemplate(Flutter) 的 Maas 音乐短视频 demo 素材下载，与朋友圈无关（✅确定）。**

### 第 2 步 — `dk.a()Z` 如何用 `allowBatchImageDownload()` `[DONE]`

```bash
$PY tools/_r6_snsdex.py types 'plugin/sns/ui/dk'       # → classes3.dex
$PY tools/_r6_snsdex.py fields 'Lcom/tencent/mm/plugin/sns/ui/dk;'   # j:Z 不在 static-final 名单里 ⇒ 非 final
$PY tools/_r6_snsdis.py classes3.dex 'Lcom/tencent/mm/plugin/sns/ui/dk;' a
```
（自写解析器复算，完整方法 35 code unit，全部消费无失步）：

```
[   0] const-string v0 <- 'groupDownloadEnable'
[   2] const-string v1 <- 'com.tencent.mm.plugin.sns.ui.SnsMultiLineImageLineMgr'
[   4] invoke  SnsMethodCalculate.markStartTimeMs(String,String)   {v0,v1}
[   7] invoke  Lcom/tencent/mars/cdn/CdnLogic;->allowBatchImageDownload()Z   {}
[  10] move-result v2
[  11] ifz     v2                      # allowBatchImageDownload()==false → 跳 disable 分支
[  13] sget-boolean Lcom/tencent/mm/plugin/sns/ui/dk;.j -> v2 :Z
[  15] ifz     v2                      # dk.j==false → 跳 disable 分支
[  17] invoke  SnsMethodCalculate.markEndTimeMs(String,String)  {v0,v1}
[  20] const/4 v0 #1
[  21] return  v0                      # return true
[  22] const/4 v2 #0
[  23] const-string v3 <- 'MicroMsg.SnsMultiLineImageLineMgr'
[  25] const-string v4 <- 'groupDownloadDisable!!!'
[  27] invoke  l2.j(String,String,[Object)   {v3,v4,v2}
[  30] invoke  SnsMethodCalculate.markEndTimeMs(String,String)  {v0,v1}
[  33] const/4 v0 #0
[  34] return  v0                      # return false
```

等价 Java（✅确定）：
```java
public static boolean a() {              // acc=0x9 → public static
    markStartTimeMs("groupDownloadEnable", "com.tencent.mm.plugin.sns.ui.SnsMultiLineImageLineMgr");
    if (CdnLogic.allowBatchImageDownload() && dk.j) {
        markEndTimeMs(...); return true;
    }
    l2.j("MicroMsg.SnsMultiLineImageLineMgr", "groupDownloadDisable!!!", ...);
    markEndTimeMs(...); return false;
}
```

`dk.j` 的读写点（全量扫描 `classes3.dex`，field_idx 14579）`[DONE]`：

```
-- who reads/writes dk.j --
  Lcom/tencent/mm/plugin/sns/ui/dk;-><init>   (acc=0x10001)   [ 80] sput-boolean
  Lcom/tencent/mm/plugin/sns/ui/dk;->a        (acc=0x9 静态) [ 13] sget-boolean
```
`<init>` 尾部（两种解析器交叉一致）：
```
[  61] invoke  Ld94/n0;->c(Ljava/lang/Class;)Ld94/m;     # 取 ISomethingService
[  67] sget    Lfj1/y;.qF :Lfj1/y;
[  72] invoke  Lkl1/d;->H8(Lfj1/w;I)I
[  80] sput    Lcom/tencent/mm/plugin/sns/ui/dk;.j :Z
```
⇒ `dk.j` **只在构造时写入一次**，值来自 `Lkl1/d->H8(Lfj1/w;I)I` 这类服务/配置查询（🟡推断：`<init>` 末段解码寄存器略有偏差，未逐条固化），
**不是编译期常量**，也不是服务端下发后立即失效的临时量 ⇒ 具备运行时可塑性。
结论：**批量下载图片确实是一个「可翻的开关」，但有两道门**（`allowBatchImageDownload()` 与 `dk.j` 串联 `&&`）。

### 第 3 步 — `startSNSDownload` 的真实发起者 `[DONE]`

offset 级超集扫描全部 15 个 dex（同时也用线性解码跑了一遍，两者结果完全一致）：

```bash
# 线性解码扫描结果
=== RESULT (desync methods: 9882) ===
### createSNSDownload  [classes3.dex]  (1)   Lhj0/t;->invokeSuspend
### createSNSDownload  [classes13.dex] (1)   Lcom/tencent/mars/cdn/CdnLogic;->createGroupNoticeDownload
### startSNSDownload   [classes13.dex] (7)   com/tencent/mm/plugin/appbrand/video/player/thumb/z->b
                                            Lok0/x0;->j / ->m / ->n / ->o / ->p / ->r
### startSNSDownload   [classes3.dex]  (1)   Lhj0/t;->invokeSuspend
### startSNSDownload   [classes7.dex]  (3)   Lok0/j;->invoke ; Lok0/m1;->m ; Lok0/m2;->r
### startSNSDownload   [classes9.dex]  (1)   com/tencent/mm/plugin/luckymoney/model/f4;->b
### allowBatchImageDownload [classes3.dex] (1) com/tencent/mm/plugin/sns/ui/dk;->a
```

Sns 侧全量相关 API 调用点（同样两法一致）：
```
## allowBatchImageDownload   Lcom/tencent/mm/plugin/sns/ui/dk;->a [classes3.dex]
## createSNSDownload         CdnLogic.createGroupNoticeDownload [classes13.dex] ; Lhj0/t->invokeSuspend [classes3.dex]
## createSNSUpload           CdnLogic.createGroupNoticeUpload [classes13.dex]
## setSnsDistanceData        plugin/game/model/a0->h ; ui/chatting/viewitems/w->p ; ok0/x1->onGYNetEnd ; qc4/x->e  (均 classes3.dex)
## startSNSDownload          （见上，12 处，无一处位于 plugin/sns/*）
```

各 `startSNSDownload` 调用点的身份，用字符串标定：
```
classes7  Lok0/j;->invoke   'MicroMsg.CdnTransportEngine'  'startDownload: biz:%d, mediaid:%s, savepath:%s'
classes7  Lok0/m1;->m       'MicroMsg.CdnTransportService' 'summersafecdn tryStart send file:%d thumb:%d, field_svr_signature[%s], field_aesKey[%s], field_fileType[%d], field_mediaId[%s] ...'
classes7  Lok0/m2;->r       'MicroMsg.OnlineVideoService'  'start task %s streaming download. ret %d '
classes13 Lok0/x0;->o       (签名 o(Ltj/h;)I —— 与 Sns 图片同一个 task bean)
classes13 appbrand/video/player/thumb/z->b
classes9  luckymoney/model/f4->b
classes3  Lhj0/t->invokeSuspend  (MJTemplate)
```

**真正的 Sns 图片入口**（✅确定，来自第 4 步反查）：
```
Ljf3/f->k()  ==  com.tencent.mm.plugin.sns.model.download.SnsCdnDownloadBase # doInBackground(String[])
    [   4] const-string 'com.tencent.mm.plugin.sns.model.download.SnsCdnDownloadBase'
    [  42] const-string 'MicroMsg.SnsCdnDownloadBase'  [  40] '[image-flow]'
    [1672] invoke  Ljf3/d;-><init>(Ljf3/f;Ltj/h;J)V
    [1687] invoke  Lmq/k;->x8(Ltj/k;)Z            # ← 猜测为 SubCoreCdnTransport.addRecvTask 🟡
    [1699] const-string '[%s][media:%s]SubCoreCdnTransport addRecvTask succ mediaId:%s'
    [1712] const-string '[%s][media:%s]SubCoreCdnTransport addRecvTask failed mediaId:%s'
```
⇒ 调用链为：`SnsCdnDownloadBase.doInBackground` → **(SubCoreCdnTransport.addRecvTask, TaskInfo=`Ltj/h;`)** → 通用 CDN 传输队列 → `Lok0/x0;->o(Ltj/h;)I` → `CdnLogic.startSNSDownload(...)`。

### 第 4 步 — 字段溯源：Sns 侧数据怎么填进 CDN 请求 `[DONE]`

`CdnLogic$BatchSnsReqImageData` 使用点（✅确定，全 dex 扫描 new-instance + 字段访问）：
```
classes3.dex
  new-instance  Ljf3/f;->k
  new-instance  com/tencent/mm/plugin/sns/ui/SnsNotInterestUI->initView
  url           com/tencent/mm/plugin/sns/ui/SnsAdNativeLandingPagesPreviewUI->onCreate
  decryptKey / fileKey / imageCachePath / picIndex / retry / totalFileSize
                Ljf3/f;->k
  picIndex / retry   Ljf3/d;->C2
```
⇒ 把 `BatchSnsReqImageData` **写满**的正是 `Ljf3/f;->k`（SnsCdnDownloadBase.doInBackground，同一方法），所以批量/单张图片的
HttpRequest 组装都在这一个方法里完成 —— **这是最应该 hook 的单个位置**。

`Ltj/h;`（CDN task bean，detail：字段 `X Y Z a1 b1 c1(Z) d1 e1 f1 g1 h1 p0 x0 y0`）的赋值窗口（自写解析器，`[1098]~[1243]`）：
```
[1098] iget   v3 <- v0    Lq54/vm3;.E:J                 # Sns MediaObj protobuf 的整数字段
[1109] iget   v1 <- v11   Ljf3/a;.a:Lq54/vm3;           # ← Sns 数据侧：MediaObj protobuf
[1111] iget   v3 <- v1    Lq54/vm3;.E:J
[1127] iput   v0 <= v2    Ltj/h;.f1:Ljava/lang/String;  # ← StringBuilder(...) + vm3.E
[1135] iget   v0 <- v11   Ljf3/a;.c:Ljava/lang/String;
[1137] iput   v0 <= v2    Ltj/h;.X:Ljava/lang/String;   # ← Ljf3/a.c
[1139] iget   v0 <- v7    Ljf3/f;.j:Ljava/lang/String;
[1141] iput   v0 <= v2    Ltj/h;.Y:Ljava/lang/String;   # ← Ljf3/f.j
[1145] iput   v0 <= v2    Ltj/h;.Z:Ljava/lang/String;   # ← 同 v0（与 Y 同源）
[1149] invoke Ljf3/f;->m(v7,v0)
[1153] iput   v0 <= v2    Ltj/h;.x0:[Ljava/lang/String; # ← Ljf3/f.m() 结果
[1157] invoke Ljf3/f;->m(v7,v0)
[1161] iput   v0 <= v2    Ltj/h;.y0:[Ljava/lang/String; # ← Ljf3/f.m() 结果
[1163] iget   v0 <- v7    Ljf3/f;.h:I
[1165] iput   v0 <= v2    Ltj/h;.a1:I                   # ← Ljf3/f.h
[1169] iput   v0 <= v2    Ltj/h;.b1:I
[1173] iput   v0 <= v2    Ltj/h;.c1:Z
[1196] iput   v0 <= v2    Ltj/h;.d1:Ljava/lang/String;  # ← StringBuilder + i0.d(...)（疑似 mediaId）
[1198] iget   v0 <- v11   Ljf3/a;.l:Lcom/tencent/mm/storage/x7;
[1200] iget   v0 <- v0    Lcom/tencent/mm/storage/x7;.a:Ljava/lang/String;
[1202] iput   v0 <= v2    Ltj/h;.e1:Ljava/lang/String;  # ← Ljf3/a.l.a
[1204] iget   v0 <- v11   Ljf3/a;.f:I
[1215] iput   v0 <= v2    Ltj/h;.g1:I                   # ← Ljf3/a.f
[1219] iput   v0 <= v2    Ltj/h;.h1:I
```

**字段名溯源结论（直接回答任务第 4 问）**

- ✅ Sns 数据侧的**来源字段**（全部为混淆名）：
  `Ljf3/a;->a : Lq54/vm3;`（**Sns MediaObj protobuf**，读其 `E:J`）、`Ljf3/a;->c : String`、`Ljf3/a;->f : I`、
  `Ljf3/a;->l : Lcom/tencent/mm/storage/x7;`（再取其 `.a : String`）、`Ljf3/f;->h : I`、`Ljf3/f;->j : String`、`Ljf3/f;->m(...) : String[]`。
  `Lq54/vm3;` 即朋友圈 MediaObj protobuf（同包 `q54/vm3` 在 `dk.c()/dk.f()/dk.g()` 等 Sns UI 方法中作为
  `TimeLineObject` 的图片项出现），**它是 fileKey/aeskey 类信息的最上游**（🟡推断：具体哪个 Sns protobuf 字段对应 aeskey 未验证）。
- 🟡 落地到 CDN 侧的**目标字段**：`Ltj/h;` 的 `X / Y / Z / d1 / e1 / f1 / x0 / y0 / a1 / b1 / c1 / g1 / h1`。
  注意 `Y` 与 `Z` 来自同一寄存器、`a1` 与 `b1` 来自同一寄存器 —— 典型的「(urlList, fallbackUrlList)」或「(aeskey, keyVersion)」同源双写模式。
- ⛔ `C2CDownloadRequest` 的 `fileKey` / `aeskey` / `snsCipherKey` **无法在本版本静态 trace 到 Sns 字段**：
  因为 Sns 图片根本不调用 `createSNSDownload`（第 1 步结论），这三个字段在 APK 里没有 `createSNSDownload` 之外的写入路径被观察到。

---

## 待真机验证项

1. **`Ljf3/f;->k` 是否是唯一的图片下载出口**：hook `com.tencent.mm.plugin.sns.model.download.SnsCdnDownloadBase#doInBackground`，
   打印入参 `String[]` 与内部构造的 `BatchSnsReqImageData.url / decryptKey / fileKey`，观察是否覆盖所有 Sns 图片（含九宫格第 2~9 张、广告图、长图）。
2. **`Lmq/k;->x8(Ltj/k;)Z` 是否就是 `SubCoreCdnTransport.addRecvTask`**：先确认接口 `Lmq/k;` 的原始类名（建议用 Xposed 打印
   `method.getDeclaringClass().getName()`），再在返回值处打印 `TaskInfo`(`Ltj/h;`) 的全部字段，即可拿到 url/aeskey/鉴权头。
3. **`Lok0/x0;->o(Ltj/h;)I` 内部对 `startSNSDownload` 的 4 个实参怎么拼**：这是离 native 最近的一站，
   hook 它可直接看到 `C2CDownloadRequest.fileKey/url/aeskey/snsCipherKey` 的真实取值，进而反推 Sns protobuf 字段映射。
4. **`dk.j` 是否可被安全翻=true、以及 `allowBatchImageDownload()` 开了之后行为如何**：
   两者都开 → 观察是否走批量 `BatchSnsReqImageData` 路径（猜想：批量路径的请求体更完整，更容易复现鉴权），以及 `groupDownloadDisable!!!` 是否消失。
5. **`CdnLogic.allowBatchImageDownload()` 本身是什么实现**：它是 native（mars）方法吗？如果是，
   静态看不到实现，需靠 hook 返回值 + 反射改 `dk.j` 两侧分别验证。
6. **重采样验证 `hj0/t` 链路无 Sns 流量**：真机上 hook `createSNSDownload`，刷朋友圈时应当**完全无回调**（或只有 MJTemplate 相关流量），
   以此确认「hook createSNSDownload 抓不到 Sns 图片」这一静态结论。
