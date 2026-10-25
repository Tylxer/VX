# R15 · 朋友圈图片下载 —— 基于 SNS 磁盘缓存的取图方案

> 目标微信 8.0.48 / versionCode 2588 GP 版 · LSPosed 模块 `com.lx.wxgroup`
> 本文档只出方案，不写实现代码。实现由后续 Flash lane 按本方案落地。
> 状态：**草稿（分步落盘中）**

---

## 0. 已确证背景（不推翻，直接用）

真机已直证：朋友圈图片早已落盘且为**明文 JPEG**：

```
/sdcard/Android/data/com.tencent.mm/cache/<hash32>/sns/<hex1>/<hex2>/
   snst_<id>   缩略图   532 个，20~70 KB
   snsu_<id>   压缩图   447 个，6~42 KB
   snsb_<id>   ★大图/原图  50 个，1.1~1.5 MB
   sight_<id>&enc<n>[_Vn]  视频  5 个，1.4~8.0 MB
   snstblur_<...>  模糊图   3 个
文件头：ff d8 ff e0 00 10 "JFIF" ⇒ 标准 JPEG，不需要解密
滚动朋友圈那一刻新写 37 个文件 ⇒ 实时写入
权限 -rw-rw---- u0_a363:ext_data_rw ⇒ 模块同 uid，可 FileInputStream 直读
```

现有代码 3 处错配（本次要修的核心）：

| # | 代码现状 | 真机实际 | 后果 |
|---|---|---|---|
| 1 | `imageRootCandidates()` 只给 `$ext/cache`（不带 hash32） | `$ext/cache/<hash32>/sns/<hex1>/<hex2>/` | `cache/<key>` 必然不存在 ⇒ `picked=NONE` |
| 2 | `imageCandidates(md5, roots)` 键用 md5 | 文件名是 `snsb_<20位id>`，键不是 md5 | 键选错，分层也命中不了 |
| 3 | `mediaKeys()` 产出 snsId/无符号 snsId/url 尾段/md5(url) | 后缀是 `mediaId`（20 位十进制） | 全不匹配 |

主会话已否证：`keys[1]` = `Long.toUnsignedString(snsId)`，**不是 mediaId**。mediaId 与 snsId 是两个不同值。

CDN 钩子路线已判为弯路（`ok0.x0#n` 需 5 道门、真机 hitN=0；`C2CDownloadResult.picCachePath` 单张恒空）。不往这里走。

---

## Q1 · mediaId 从哪来（最关键）

### 结论（阶段性，未定稿处标「未确证」）

**文件名的真正来源已找到确切类与字段**：`Lbf3/t0` = `com.tencent.mm.plugin.sns.data.SnsUtil`（方法体 const-string 自报），文件名由 `q54/vm3.d` 经 `t0#s`（fliterPre）变换生成：

- `t0#L(Lq54/vm3)`（`getSnsBigName`）⇒ `"snsb_" + s(vm3.d)`；`vm3.D==4` 时追加视频后缀（`f0(vm3)` + `tl0/c.a(String, vm3.E)`）。
- `t0#V(Lq54/vm3)`（`getSnsThumbName`）⇒ `"snst_" + s(vm3.d)`。
- `t0#e0(Lq54/vm3)`（`getSnsUserName`）⇒ `"snsu_" + s(vm3.d)`（+ 视频后缀）。
- `t0#d0(String)`（`getSnsTmpUserWithoutEnc`）⇒ `"snsu_" + s(str)`。
- `t0#S(Lq54/vm3)`（`getSnsSightName`）⇒ `"sight_" + s(vm3.d)`（+ 视频后缀）。
- `t0#s(String)`（`fliterPre`）：null→`""`；否则疑似**固定去掉前 2 个字符、再跳过前导 '0'**，返回 `substring`。⇒ 解释了 protobuf 原始串可能是 `"00"+20位` 而文件名是 20 位。⚠️ 前缀语义**未确证**，需真机核对。

`q54/vm3`（classes3 def#8946，super `com/tencent/mm/protobuf/a`，42 字段）：`d:String`（mediaId 候选）、`D:I`、`E:J`。`t0` 各 `getSns*Name` 全部以 `vm3.d` 为 id。

### 候选②（`Ltj/k.field_mediaId`）已被强证据否定

`Ltj/h`/`Ltj/k` 的 `field_mediaId` 不是 20 位 mediaId，疑似构造为 `"<pack.snsId>_<reqDownType>"`：

- `Ljf3/f#k` 中 `Ltj/k.field_mediaId` 两处写点（[759]/[760] 与 [982]/[983]），构造式为 `new StringBuilder().append(v0).append('_').append(jf3/a->f).toString()`。
- 追踪 v0：`[263] iget-object v0, Ljf3/a;->o:Ljava/lang/String;`，[258] 日志串 `' pack.snsId: '` 紧邻 ⇒ `field_mediaId = "<pack.snsId>_<reqDownType>"`，与文件名后缀无关。
- ⚠️ 待补查 v0 其余写点（含 `move-object/16` 0x09 等）后定稿，当前标「未最终确证」。

### 候选①（protobuf 媒体对象）路径【已确证完整链路】

**这是最可靠的一条，且静态证据已闭环**。完整解析路径：

```
SnsInfo.content (BLOB)
  → TimeLineObject.parseFrom(content)          // 项目已有 extractText/extractUrls 证明 content 是 TimeLineObject protobuf
    → TimeLineObject.ContentObj:Lq54/i40;      // 字段号 6
      → i40.h:Ljava/util/LinkedList;           // 字段号 8，元素是 [B（媒体对象 BLOB）
        → 对每个元素 vm3 = new Lq54/vm3(); vm3.parseFrom(bytes)
          → vm3.d:Ljava/lang/String;           // 字段号 1 = mediaId
```

DEX 证据（复现命令 + 原始片段）：

1. **`i40.h` 是媒体对象列表**（字段号 8，元素是 vm3）—— `_cache/_r14_walk.py dis classes3.dex 'Lq54/i40;' op`：
   ```
   [  16] iget-object  v3, v0   Lq54/i40;->h:Ljava/util/LinkedList;
   ...
   [1426] invoke-virtual  Lgv4/a;->j(I)Ljava/util/LinkedList;   ← 字段号 8 的 message 列表
   [1442] check-cast  v5  [B
   [1444] new-instance  v6  Lq54/vm3;
   [1446] invoke-direct  Lq54/vm3;-><init>()V
   [1454] invoke-virtual  Lcom/tencent/mm/protobuf/a;->parseFrom([B)Lcom/tencent/mm/protobuf/a;
   [1457] iget-object  v5, v3  Lq54/i40;->h:Ljava/util/LinkedList;
   [1459] invoke-virtual  Ljava/util/LinkedList;->add(Ljava/lang/Object;)Z
   ```
2. **`vm3.d` 是 mediaId（字段号 1）**—— `_cache/_r14_walk.py dis classes3.dex 'Lq54/vm3;' op` 写分支：
   ```
   [  31] iget-object  v9, v0  Lq54/vm3;->d:Ljava/lang/String;   ← 字段号 1（无 const 前置 = 默认 1）
   [  35] invoke-virtual  Lkv4/a;->j(ILjava/lang/String;)V
   ```
   读分支：
   ```
   [1297] invoke-virtual  Lgv4/a;->k(I)Ljava/lang/String;
   [1301] iput-object  v1, v3  Lq54/vm3;->d:Ljava/lang/String;
   ```
3. **`TimeLineObject.ContentObj` 字段号 6**—— `dis classes3.dex 'Lcom/tencent/mm/protocal/protobuf/TimeLineObject;' op`：
   ```
   [  94] iget-object  v2, v0  TimeLineObject;->ContentObj:Lq54/i40;
   [ 102] invoke-virtual  Lkv4/a;->i(II)V   ← 字段号 6（const v15 #6 预置）
   ```

**为何候选①最可靠**：它是唯一「不依赖下载时序、不依赖运行时 hook、可从已归档的 `sns_archive.content` BLOB 离线解出」的来源。项目里 `sns_archive` 已存了整行快照（含 content BLOB），导出循环里 `row.content` 现成可读，直接 `parseFrom` 就能拿到 `vm3.d`，再套 Q2 的 `MD5(mediaId)` 分桶公式即可定位缓存文件。其余候选都不可靠，见下。

### 候选②（`Ltj/k.field_mediaId`）已被强证据否定

`Ltj/h`/`Ltj/k` 的 `field_mediaId` 不是 20 位 mediaId。DEX 证据：

`Ljf3/f#k` 中 `Ltj/k.field_mediaId` 的写入在 `[759]/[760]` 与 `[982]/[983]`，构造式为 `append(v0)+"_"+append(jf3/a->f)`。追踪 v0 最近写入 `[263] iget-object v0, Ljf3/a;->o`，其上方 `[258]` 日志串 `' pack.snsId: '`。⇒ `field_mediaId = "<pack.snsId>_<reqDownType>"`（如 `"-3447438253133393222_0"`），是 CDN 任务的内部 fileKey，与磁盘文件名后缀 `snsb_<20位mediaId>` **无关**。

（复现：`_cache/_r14_walk.py dis classes3.dex 'Ljf3/f;' k`，重点看 [740]~[760]、[955]~[983]、[250]~[270]。）

### 候选③④（URL 推导 / keys 其它项）均不可靠

- ③URL：`_r14_img.txt` 的 urls 只有 `shmmsns.qpic.cn/mmsns/<46位hash>`、`wxapp.tc.qq.com/.../stodownload?...`，**不含 20 位 mediaId**。`mmsns/` 后跟的是 46 位 CDN hash，与 mediaId（20 位十进制）形态、长度都不同。
- ④keys：`mediaKeys()` 产出 4 类 = `snsId` / `无符号snsId` / url 尾段 / `md5(url)`，**没有任何一项是 mediaId**。`keys[1]` 已被主会话否证（= `Long.toUnsignedString(snsId)`）。

> **未确证残留（仅 1 处，需真机核对）**：`t0#s`（fliterPre）疑似「先去掉前 2 个字符、再跳前导 '0'」。若 protobuf 里 `vm3.d` 原始串带 `"00"` 前缀（如 `"0015038873180398883448"`），则文件名是去掉前缀后的 20 位。**真机判据**：导出时打印 `vm3.d` 原始值与 `t0#s(vm3.d)` 结果对比（或直接 `md5(vm3.d)` 后看 `md5[2]/md5[8]` 是否等于真机 `<hex1>/<hex2>`）。若直接等于，则 `vm3.d` 就是 20 位 mediaId，无前缀；若不等，试 `md5(去前2位)` / `md5(去前导0)`。**本方案落地时一律先做一次「去前导 0」+「去前 2 字符」的候选链**，见 Q3。

## ⛔⛔ 重大订正（2026-10-24 21:3x，R16-V1 复核触发）：`fliterPre` **从下标 0 开始**，不是从 2

> **本条推翻了本文档 §4.3、§7.5（第 5 条）以及 `MomentsLogic.kt:421-434` 的实现与 KDoc，
> 也推翻了「真机 `fliterPre` 自洽 1029/1029」这句话的因果解释。**

**反汇编原文**（`_r14_walk.py dis classes3.dex 'Lbf3/t0;' s`，注意 acc=0x0009 是 static）：

```
Lbf3/t0;->s(Ljava/lang/String;)Ljava/lang/String;
  [   0] const-string           'fliterPre'          ← 方法自报姓名
  [   7] if-nez                 v6 -> abs 15         ← null 检查
  [  12] const-string           v6  ''               ← null 返回空串
  [  15] invoke-virtual         String.length()
  [  19] const/4                v3  #0               ← ⭐ i 从 0 开始
  [  20] if-ge                  v3, v2 -> abs 34
  [  22] invoke-virtual         String.charAt(I)
  [  26] const/16               v5  #48              ← '0' = 0x30
  [  28] if-eq                  v4, v5 -> abs 31     ← 不是 '0' 就跳出
  [  31] opd8                   v3, v0              ← add-int/lit8（i++）
  [  33] goto                   -> abs 20
  [  34] invoke-virtual         String.substring(I)
```

`[19] const/4 v3, #0` 是铁证：**起点是 0**。语义 = 「跳过前导 `'0'`，返回其余部分」。
本文档原先记的「固定去掉前 2 个字符再跳前导 0」**是错的**。

**为什么「自洽 1029/1029」没能暴露这个错**（这条方法论必须记住）：

我从真机快照 `_cache/_r15_bucket_files.json`（1041 条）里取出 1029 个图片类文件名：

| 事实 | 数值 |
|---|---|
| 图片类文件总数 | 1029 |
| 其中**纯数字**文件名 | 1001（其余 28 条是 `...&enc...` 视频尾巴 / `Locall_path` 等非图片） |
| 纯数字段的**长度** | **100% 都是 20** |
| 纯数字段**首位** | **100% 都是 `'1'`**（从不是 `'0'`） |
| 去重后不同的数字段 | **527** 个，全部 20 位、全部首位非 0 |

⇒ 因为**真机文件名里的数字段从没有前导 0**，所以
「从 0 跳前导 0」（= 原串不变）与「从 2 跳前导 0」（= 砍掉前两位）**在『可见文件名』这一侧永远无法区分**——
两边各自都能和真机文件名"自洽"，但**它们对同一个 mediaId 会给出不同的候选名**。
这就是「1029/1029 自洽」的假象来源：**它验证不了 `fliterPre`，它只是把两侧都调成了同一个错的答案。**

**改正后必须同步的 3 处**：

1. `MomentsLogic.kt:429` `var i = 2` → **`var i = 0`**，KDoc 同步改（现在写的是「从下标 2 开始」）。
2. 本文档 §4.3 的 `fliterPre` 复刻代码、§7.5 第 5 条「从下标 2 起跳过连续 `'0'`」。
3. ⚠️ **`MD5(fliterPre(mediaId))[0]/[1] = 4/1029 (0.39%)` 那一行要重算**（§4.3 表第 4 行）——
   那个 0.39% 是用错的 `fliterPre` 算的，结论方向很可能反过来。
   **但在重算之前，`MD5(mediaId)[0]/[1] = 98.45%` 与 `MD5(mediaId[:20])[0]/[1] = 100%` 两行不受影响**，
   索引直扫方案（Q2b）也不受影响（它不依赖 `fliterPre` 推目录，只依赖文件名）。

**影响面评估（好消息）**：`snsNameCandidates()` 是「`fliterPre(mediaId)` + `mediaId` 原值」双候选，
所以只要 `vm3.d` 原值本身就是那个 20 位数字（很可能是），`mediaId 原值` 这条兜底就能命中，
**真机大概率不会因此漏图**。但这属于「靠兜底蒙对」，不是「算对」——
一旦 `vm3.d` 真带 `"00"` 前缀，就会**稳定漏掉全部原图**。**必须改，不能留着靠兜底。**

> ✅ **已修（2026-10-24）**：`MomentsLogic.fliterPre` 已改 `var i = 0`，KDoc 与单测同步更新，`testDebugUnitTest` 全绿。

---

## Q2 · 分桶目录 `<hex1>/<hex2>` 算法【⚠️ 已作废，改用 Q2b 索引直扫】

> ⛔ **本节原结论（`MD5(mediaId)[2]/[8]`）经真机 1029 个文件验证，命中率仅 0.68%，判定不适用。**
> 保留原文仅供追溯，**I1 实现一律按 Q2b**。订正过程见「Q2 订正记录」。

### ~~结论：`<hex1>` = `MD5(mediaId) 的 hex 第 3 字符（下标 2）`，`<hex2>` = `第 9 字符（下标 8）`~~

完整落盘路径公式：

```
完整路径 = <cache根>/<hash32>/sns/<md5[2]>/<md5[8]>/<snsb_|snst_|snsu_><mediaId>
其中 md5 = MD5(mediaId) 的小写 32 位 hex
```

### DEX 证据链（三层，全部复现可得）

1. **`Ljf3/f#k` [864]~[894] 拼 `imageCachePath`**（复现 `dis classes3.dex 'Ljf3/f;' k`）：
   ```
   [ 864] new-instance  v4  StringBuilder
   [ 869] iget-object  v6, v15  Lq54/vm3;->d;            ← mediaId
   [ 871] invoke-virtual  Ljf3/a;->a(String)              ← getMediaDownloadPath(mediaId)
   [ 878] invoke-virtual  Ljf3/a;->b(Lq54/vm3;)           ← getMediaFileName(vm3)
   [ 885] toString  → v4
   [ 889] const/4 v6 #2
   [ 890] invoke-static  o6.i(String, Z)                  ← VFS 路径标准化（非分桶）
   [ 894] iput-object  v4, v5  BatchSnsReqImageData;->imageCachePath
   ```
2. **`Ljf3/a#a` = `getMediaDownloadPath`**（`dis classes3.dex 'Ljf3/a;' a`）：
   ```
   [  7] invoke-static  f4.F8()                           ← getAccSnsPath() = <sns根>/<hash32>
   [ 11] invoke-static  y5.e(String, String)              ← getMediaFilePath(root, mediaId)
   ```
3. **`y5#e` = `getMediaFilePath`（分桶核心）**（`dis classes3.dex 'Lcom/tencent/mm/plugin/sns/model/y5;' e`）：
   ```
   [ 65] invoke-virtual  {v11}  String->getBytes()[B       ← mediaId
   [ 69] invoke-static  {v4}  Lai/j;->g([B)                ← MD5
   [ 72] move-result-object  v4                            ← v4 = md5 hex（32 位）
   [ 73] new-instance  v5  StringBuffer
   [ 75] invoke-direct  {v5,v10}  StringBuffer-><init>(String)   ← v10 = root
   [ 78] invoke-virtual  {v4}  length()I
   [ 85] if-lt  v6, v8(=#8)  → skip                        ← 长度 ≥8
   [ 88] invoke-virtual  {v4, #2}  charAt(I)               ← md5.charAt(2)
   [ 92] append(C); [ 95] append("/")
   [103] if-lt  v6, v9(=#9)  → skip                        ← 长度 ≥9
   [105] invoke-virtual  {v4, v8(=#8)}  charAt(I)          ← md5.charAt(8)
   [109] append(C); [112] append("/")
   [130] toString → 返回 root + md5[2] + "/" + md5[8] + "/"
   ```
4. **`ai.j#g` = MD5 转小写 hex**（`dis classes5.dex 'Lai/j;' g`）：
   ```
   [  7] const-string  v1  'MD5'
   [  9] invoke-static  MessageDigest->getInstance(String)
   [ 13] update([B); [ 16] digest()[B
   [  4] fill-array-data  [C  ← "0123456789abcdef"
   ```
5. **`f4.F8()` = `getAccSnsPath`**（`dis classes3.dex 'Lcom/tencent/mm/plugin/sns/model/f4;' F8`）：方法名 const-string 自报 `'getAccSnsPath'`，返回当前账号的 sns 缓存根（即真机观察到的 `<cache>/<hash32>/sns`，与 `_r14_sns_cache.md` 目录结构吻合）。

### 与真机目录交叉验证

`_r14_sns_cache.md` 记录 `cache/<hash32>/sns/<hex1>/<hex2>/`，且 `<hex1>/<hex2>` 是**单字符** hex（本设备 sns 下 19 个子目录）。公式 `md5[2]/md5[8]` 产出的正是两个单 hex 字符，完全自洽。

> 结论：~~**无需 glob 绕开**。拿到 mediaId 后可直接算出分桶目录，`listFiles` 只遍历一级即可命中。~~
> **该结论作废**，见 Q2b。

---

## Q2b · 【最终设计】索引直扫 —— 不算分桶，直接建「文件名 → 路径」索引

### 为什么放弃公式

主会话用真机 1029 个真实缓存文件做了两轮穷举（`tools/_r15_bucket*.py`，数据快照 `_cache/_r15_bucket_files.json`）：

| 候选公式 | 命中 |
|---|---|
| `MD5(mediaId)[2]/[8]`（原 Q2 写法） | **7/1029 = 0.68%** |
| `MD5(mediaId)[0]/[1]` | 1013/1029 = 98.45% |
| `MD5(mediaId[:20])[0]/[1]` | 1029/1029 = 100% |
| `MD5(fliterPre(mediaId))[0]/[1]` | 4/1029 = 0.39% |

> ⚠️ 本行 `4/1029` 是用**修正前**的错误 `fliterPre`（`i=2`）算的，**数值已作废**，需用 `i=0` 重算。
> 其余两行（`MD5(mediaId)[0]/[1]=98.45%`、`MD5(mediaId[:20])[0]/[1]=100%`）不受影响。

同时确认 `y5#e`（全 15 dex **唯一**分桶实现，`tools/_r15_scanpath.py` 扫描结果：只有 `y5#e` 和 `jf3/a#a` 两个方法含 `getMediaFilePath` 字符串，后者只是转调）反汇编确实是 `charAt(2)` / `charAt(8)`。

**⇒ DEX 与真机数据矛盾，且 `y5#e` 属于「批量请求图片」（`BatchSnsReqImageData.imageCachePath`）这条支线，不是本地看图实际落盘用的桶。**

结论：**分桶公式在当前版本上不可靠，不要把实现赌在公式上。**
另有一个更硬的理由：I1 手上只有 `vm3.d`（mediaId），而真机文件名里那段数字是 `fliterPre(mediaId)`（**从下标 0 起**跳前导 0，`t0#s` 反汇编确认），
**两者长度都不稳定**（真机 mediaId 长度实测 20/22/23/24/25/26/27/28 共 8 种），任何「截断到固定长度再算 md5」的公式都会在边界上翻车。

> ⛔ **订正（R16-F3，2026-10-24）**：本行原文写的是「去前 2 位 + 跳前导 0」，**那是错的**。
> `t0#s` 的 `[19]` 是 `const/4 v3, #0`（`i = 0`），不是 `#2`。正确语义 = **从下标 0 开始**
> 跳前导 `'0'`，例 `"15038644089917813396"` 原样返回、`"0015038873180398883448"` →
> `"15038873180398883448"`。已按 `i=0` 修正 `MomentsLogic.fliterPre` 与单测（606 全绿）。
> ⚠️ **上表 `MD5(fliterPre(mediaId))[0]/[1] = 4/1029` 那一行数值随之作废**，需用 `i=0` 重算；
> 其余两行不受影响。本行数字保留原样，仅供追溯当时的脚本输出（`tools/_r15_bucket*.py` 亦为旧 `i=2` 版本）。

### ✅ 最终方案：一次性遍历建索引

`sns/` 下的文件总量极小（本机 **1029 个**，19 个桶目录），全量 `listFiles` 一轮是毫秒级。
**不猜桶、不算 md5，直接把所有文件名收进内存表，按名查。**

```kotlin
// 一次遍历，缓存到本次导出作用域内（不要跨轮复用：用户随时会滚出新图）
// 返回 Map<文件名, File>
private fun buildSnsIndex(): Pair<Map<String, File>, Boolean /*rootFound*/> {
    val idx = HashMap<String, File>(2048)
    val cacheRoot = File(externalRoot(), "cache")
    val hashDirs = cacheRoot.listFiles()?.filter {
        it.isDirectory && it.name.length == 32 && it.name.all { c -> c in '0'..'9' || c in 'a'..'f' }
    } ?: emptyList()
    var rootFound = false
    for (h in hashDirs) {
        val sns = File(h, "sns")
        if (!sns.isDirectory) continue
        rootFound = true
        for (b1 in sns.listFiles() ?: emptyArray()) {          // <hex1>
            if (!b1.isDirectory) continue
            for (b2 in b1.listFiles() ?: emptyArray()) {        // <hex2>
                if (!b2.isDirectory) continue
                for (f in b2.listFiles() ?: emptyArray()) {
                    if (f.isFile) idx[f.name] = f
                }
            }
        }
    }
    return idx to rootFound
}
```

查表时**按候选名列表逐个试**（不依赖任何单一公式）：

```kotlin
// mediaId = vm3.d 原值；fliterPre 精确复刻 t0#s
private fun fliterPre(s: String): String {
    if (s.isEmpty()) return ""
    var i = 2
    while (i < s.length && s[i] == '0') i++
    return if (i >= s.length) "" else s.substring(i)
}

// 候选名（按优先级：原图 > 压缩 > 缩略）
private fun nameCandidates(mediaId: String): List<Pair<String, String>> {
    val pre = fliterPre(mediaId)
    val keys = LinkedHashSet<String>()
    keys.add(pre); if (pre.isNotEmpty()) keys.add(mediaId)   // 双候选兜住 fliterPre 误判
    val out = ArrayList<Pair<String, String>>()
    for (tier in listOf("snsb_" to "big", "snsu_" to "mid", "snst_" to "thumb"))
        for (k in keys) out.add(tier.first + k to tier.second)
    return out
}
```

**命中规则必须用「精确名 或 前缀名」双判**：
真机里 `sight_` 是 `sight_<20位>&enc<hash>[_Vn]`、`snstblur_src_` 是 `snstblur_src_<20位>`，
即 `<前缀><数字>` 后面**可能还有不定长尾巴**。所以查表逻辑：

```kotlin
fun lookup(idx: Map<String, File>, name: String): File? {
    idx[name]?.let { return it }
    // 兜底：文件名以该 name 开头（处理 &enc / _V2 这类尾巴）
    return idx.entries.firstOrNull { it.key.startsWith(name) && isBoundary(it.key, name) }?.value
}
// isBoundary：name 之后的下一个字符不是数字（避免 snst_150 误配 snst_1503...）
```

### 与公式方案的成本对比

| | 索引直扫 | 公式直算 |
|---|---|---|
| listFiles 次数 | 1 轮全量（≈19 桶） | 每条 mediaId 1 次 |
| 依赖分桶公式 | ❌ 不需要 | ✅ 必须全对 |
| mediaId 长度异常（20~28） | 自动兼容 | 易翻车 |
| 文件名带尾巴（`&enc`） | 前缀匹配兜住 | 需另写规则 |
| 代码量 | ~35 行 | ~20 行 + 一堆边界 |

**⇒ 采用索引直扫。** 副作用是「找不到」的归因变清晰：索引建不起来 = `imgRootMiss`；索引有但查不到名 = `imgFileMiss`。

**真机开销实测**（主会话 2026-10-24 03:4x）：`cache/` 下只有 **1 个** 32 位 hex 目录（`22139c91…`），
`sns/` 下 **19 个桶**、合计 **1041 个文件**。用 adb 逐个 `ls` 模拟三层遍历花了 22.08s，
但那全是 adb 往返开销；**Kotlin 侧是单进程 `File.listFiles()`，无往返，实际在毫秒级**。
⇒ 单轮建一次索引的成本可忽略，**绝对不要每条动态重建**（那才是真慢）。


### 已确认的补充事实（I1 可直接依赖）

1. **同一 mediaId 的三种前缀必在同桶**：真机 539 个 mediaId 中 432 个同时有 `snsb_`/`snsu_`/`snst_`，跨桶数 = **0**。⇒ 桶只由 mediaId 决定，与文件名前缀无关。
2. **`sight_`（视频）与 `snstblur_src_` 遵循同一分桶**（各 5/5、3/3 命中 `MD5(<前20位>)[0:2]`），但**本方案只导图片**，视频留后续。
3. **`wxid_<32位>_` 是头像缓存**，不是朋友圈图片，**必须排除**（按前缀白名单只取 `snsb_/snsu_/snst_`）。
4. **`snstblur_` 是模糊占位图**，语义不是内容，**导出排除**。
5. **mediaId 链路可行性已复核**（主会话 2026-10-24 03:4x 补）：
   - `row.content`（BLOB）在导出循环里**现成可读** —— `MomentsExport.logImgProbe()` 已经在调
     `MomentsLogic.extractUrls(row.content)` 读同一个 BLOB，所以「拿 content」这一步零成本。
   - 项目里**已有手写 protobuf varint 解析范式**：`SnsCommentLogic.kt:856` 的 varint 读取器，
     以及 `SnsCdnProbe.kt:590` 的反射 `readString(obj, field)`。**不需要引入 protobuf 库**，
     也不需要 `vm3.parseFrom` 反射 —— 按字段号手动走一遍 varint/wire-type 即可取出 `vm3.d`。
   - 要点：`SnsInfo.content` → `TimeLineObject`(顶层) 字段 **6** = `ContentObj`(嵌套 message)
     → `ContentObj` 字段 **8** = 媒体列表（repeated message）→ 每个元素是 `vm3`(SnsMedia)
     → `vm3` 字段 **1** = `d`(String) = **mediaId**。
   - ⚠️ **不许用反射调 `vm3.parseFrom`**：那会把实现绑死在 `q54/vm3` 这个混淆名上，微信一升级就崩；
     手动走 wire-format 只依赖**字段号**，字段号在协议里稳定得多。

---

## Q3 · 三级文件优先级 + 回退链

### 三级文件的精确命名（全部来自 `Lbf3/t0`，`dis classes3.dex 'Lbf3/t0;' <m>` 复现）

| 方法 | const-string 自报 | 前缀常量 | 文件名 | 真机样本 |
|---|---|---|---|---|
| `t0#V` | `getSnsThumbName` | `snst_` | `snst_<fliterPre(vm3.d)>` | 缩略图 20~70KB |
| `t0#e0` | `getSnsUserName` | `snsu_` | `snsu_<fliterPre(vm3.d)>` | 压缩图 6~42KB |
| `t0#L` | `getSnsBigName` | `snsb_` | `snsb_<fliterPre(vm3.d)>` | ★大图/原图 1.1~1.5MB |
| `t0#S` | `getSnsSightName` | `sight_` | `sight_<fliterPre(vm3.d)>&enc<n>[_Vn]` | 视频 |
| `t0#N` | `getSnsBlurName` | `snsblurt_` | `snsblurt_...` | 模糊图 |
| `t0#P` | `getSnsPhotoName` | `snsb_` | 同 snsb_ | — |

（`t0#P` 也叫 `snsb_`，是「照片大图」别名；`t0#K`=`sightad_` 广告视频，导出应排除。）

### 视频后缀来源（`sight_<id>&enc<n>[_Vn]`）

当 `vm3.D == 4`（视频类型）时，各 `getSns*Name` 追加：
- `f0(vm3)` = `getUrlMd5`：优先返回 `vm3.F`（url 的 md5），否则 `MD5(vm3.toByteArray())`（`t0#f0` 反汇编确认）。
- `tl0/c.a(String, vm3.E:J)` 用 `vm3.E`（long）再拼 `&enc<n>[_Vn]` 段。

**图片导出不涉及此分支**（图片 `vm3.D != 4`，直接返回基础名），但列出以说明 `sight_` 为何带后缀。

### 优先级 + 回退链（建议）

```
对每个 mediaId（每条动态可能多张，i40.h 逐个）：
1. snsb_<mediaId>            ← 首选：大图/原图，1.1~1.5MB，语义 = 原图
2. 回退 snsu_<mediaId>       ← 压缩图，能看
3. 回退 snst_<mediaId>       ← 缩略图，仅 20~70KB，最后兜底
4. 三者都无 → 记 miss，不回退 CDN url（见下）
```

**导出语义建议 =「优先原图、能看就行」**：
- 需求 `6.4` 原文是「`text 文本 + 原图`」，语义倾向原图。
- 但真机缓存里 `snsb_` 只有 50 个、`snsu_` 有 447 个、`snst_` 有 532 个 —— **大部分 mediaId 只有缩略/压缩图**（用户没点开大图，微信就不会下 `snsb_`）。
- 因此按「原图优先、逐级回退」落地：有 `snsb_` 拷 `snsb_`；否则拷 `snsu_`；否则拷 `snst_`。这样既满足「原图」语义（存在时必取原图），又不会因原图缺失而漏掉「能看」的图。
- 导出文件扩展名：缓存文件无扩展名，统一按 JPEG 处理（真机文件头 `ff d8 ff e0 + JFIF` 已证），文件名沿用 `img_<snsId>_<index>.jpg`（`MomentsLogic.imageFileName`）。

**三者都没有时是否回退 CDN url**：
- **不回退**。理由（已定死的实测结论，见 `需求功能清单.md` 6.4）：图片 CDN（`shmmsns.qpic.cn`）裸 HTTP **8 个样本全部 400**，加 Referer/UA/`?tp=jpg` 均无效 ⇒ 图片需要微信侧鉴权，`downloadMedia` 对图片恒失败。
- 视频（`stodownload`）反而 200 可下，但**本方案专攻图片**，视频路径（`sight_` 缓存直读 / url 下载）留作后续，不混入本方案。
- 结论：三者都无 = 该条动态图片**本轮跳过 + 计数 `missMedia`**，不触发任何 HTTP。

---

## Q4 · 模块内读取正确姿势

### 路径获取：沿用 `Environment.getExternalStorageDirectory()`

现有 `MomentsExport.imgRoots()` 已用：
```kotlin
android.os.Environment.getExternalStorageDirectory().absolutePath + "/Android/data/com.tencent.mm"
```
**这是对的，继续沿用**，理由：
- 微信 release 包 `debuggable=false`，`run-as` 不可用；模块跑在**微信进程内、同 uid（u0_a363）**，对 `/sdcard/Android/data/com.tencent.mm/**`（微信自己的外部专属目录）有直接读写权限。
- Android 11+ 的「作用域存储」（Scoped Storage）**不拦截应用自己的专属目录**（`Android/data/<包名>/` 属「应用专属外部存储」，同包名进程可直读，无需 SAF / 无需 `MANAGE_EXTERNAL_STORAGE`）。
- 目标机 PJD110 是 Android 14/15 量级，但结论不变：**同 uid 进程读自己的 `Android/data` 专属目录，不受 scoped storage 限制**。
- 不建议改用 `context.getExternalFilesDir()`：那会落到 `Android/data/com.tencent.mm/files`，而缓存实际在 `Android/data/com.tencent.mm/cache`，两者不同根，用 context API 反而拿错根。

### `imgRoots()` 健壮性评估 + 新增 `cache/<hash32>` 逻辑

现状 `imgRoots()` 只扫 `MicroMsg/` 下的 32 位 hash，**漏了 `cache/` 下的 hash32**（本次错配 #1）。需新增：

```
外部根 = /sdcard/Android/data/com.tencent.mm
缓存根 = 外部根/cache
  → listFiles() 扫 cache/ 下所有「32 位小写 hex」目录（hash32）
  → 每个 hash32 下必有 sns/ 子目录（sns 根）
  → 对外部根使用「索引直扫」（Q2b）：遍历 cache/<hash32>/sns/<hex1>/<hex2>/ 建 Map<文件名,File>，
    不再对每个 mediaId 做 md5 分桶计算（原 Q2 的 md5[2]/md5[8] 公式命中率仅 0.68%，已作废）
```

⚠️ 与 `MicroMsg/` 的 hash 是**不同的值**（真机已证：内部 `89476c7f…`、外部 `bfa6fa81…`/`22139c91…`），所以 `cache/` 下的 hash32 必须**独立扫**，不能用 MicroMsg 的 hash 推。`cache/` 下通常只有 1 个 hash32（当前登录账号），扫描代价 = 一次 `listFiles()` 一层的量级，可忽略。

### 读 + 拷贝实现要点

| 点 | 方案 |
|---|---|
| 读源 | `FileInputStream(src)` 流式读（缓存文件是明文 JPEG，无需解密） |
| 拷贝 | `src.inputStream().use { it.copyTo(dst.outputStream()) }`，8KB 缓冲（沿用 `DL_BUF`） |
| 大小上限 | 复用 `MAX_MEDIA_BYTES = 20MB` 兜底：缓存文件到不了这个量级（snsb_ 最大 1.5MB），超了多半读错，中止并计数 |
| 同名覆盖 | 沿用现有「tmp + rename」原子落盘：先写 `<dst>.tmp`，成功再 `renameTo(dst)`；dst 已存在时先 `delete()`（真机踩过「不删 rename 失败」） |
| 失败隔离 | 单个 mediaId 读失败**只计数 + 一行日志**，继续下一个，绝不中断整轮导出（沿用「静默跳过 + 日志」约定，但见 Q5：四态必须可见，不许 `?: return` 吞） |
| 空间 | 沿用 `MIN_FREE_BYTES = 32MB` 下限，每 N 个文件查一次 |

### ⛔ 要不要 hook：**不需要，纯文件读取即可（证实）**

- mediaId 来源：从 `sns_archive.content` BLOB **离线 `parseFrom`** 解出（Q1），不依赖任何运行时 hook。`sns_archive` 是 SnsGuard 拦 DELETE 时存的整行快照，导出循环里 `row.content` 现成。
- 分桶目录：**不算分桶**，用 Q2b 的索引直扫（`listFiles` 一次性建表后按名查）。
- 文件名：`snsb_/snsu_/snst_ + fliterPre(mediaId)` 纯字符串变换（Q1/Q3）。
- 读文件：同 uid `FileInputStream` 直读（Q4 开头）。

整条链路**零 hook、零 HTTP、零解密**。与任务书判断一致。唯一「运行时依赖」是「缓存文件已经落盘」——这由用户滚动朋友圈触发（真机已证滚屏即时写入 37 个文件），不是 hook 触发。

---

## Q5 · 可观测性（四态独立计数，不可静默）

### 新增计数字段（`MOMENT ... stat` 行可见）

对每个 mediaId 的取图结果，落到**四个独立 AtomicLong**（建议命名）：

| 字段 | 语义 | 判定条件 |
|---|---|---|
| `imgRootMiss` | **没找到目录** | `cache/` 下扫不到 32 位 hash，或 `sns/` 根不存在 |
| `imgFileMiss` | **目录在但无该 mediaId 文件** | sns 根存在，但 `snsb_/snsu_/snst_` 三个文件名都 `!exists()` |
| `imgReadFail` | **找到了但读失败** | `exists()` 为真，但 `FileInputStream` 打开/拷贝抛异常 |
| `imgCopied` | **成功** | tmp + rename 落盘成功 |

四态是**互斥且穷尽**的：每个 mediaId 必落入且仅落入其中一态。**禁止** `?: return` 静默吞——四态必须显式 `when` 分支 + 各自计数。

### stat 行格式（建议）

```
MOMENT export stat enabled= images=true running= lastRunAt= runs= written= skipped=
  listMiss= err= imgRootMiss= imgFileMiss= imgReadFail= imgCopied= list= root=
```

（`images=` 由 false 改 true；新增 4 个 img 计数。现有 `stat()` 已有 runs/written/skipped/listMiss/err 字段，直接追加。）

### 每条真机判据（跑什么 → 期望什么数字）

| # | 操作 | 期望 |
|---|---|---|
| 1 | `op=moments sub=img`（探针，改版后打印 `vm3.d` + 三级文件 exists 状态） | 每条带图动态能打出 `mediaId=` + `snst_/snsu_/snsb_` 各自的 `exists` 布尔 |
| 2 | 滚 3 屏朋友圈（触发缓存写入）→ `op=moments sub=imgtry` | `imgCopied>0`，且 `imgCopied ≥ 命中行数`（多图动态 > 1） |
| 3 | 对**没滚到**的历史动态跑导出 | `imgFileMiss>0`（目录在、文件无），**不是** `imgRootMiss`（目录一定能扫到） |
| 4 | 手动把 cache 目录改个名（临时破坏）→ 跑导出 | `imgRootMiss>0`（判定「没找到目录」分支被走到） |
| 5 | `op=moments sub=stat` | 四个 img 计数都在行里可见，且四态之和 = 已尝试的 mediaId 总数 |
| 6 | 滚屏后立即导出**同一批**两次 | 第二次 `imgCopied` 不重复累加（幂等，见 Q6） |

---

## Q6 · 失败回退与安全边界

### 缓存不存在 / 被清理时

- 表现：`imgFileMiss`（目录在、文件无）或 `imgRootMiss`（整个 cache 被清）。两者都已独立计数，**不静默**。
- 处理：该 mediaId 本轮跳过，文本文件照常导出（`renderText` 已带「---- 媒体 ----」URL 段，内容线索不丢）。图片缺失不影响文本导出完整性。

### 是否主动触发微信下载

**不主动触发**。理由：
1. 「触发下载」的可行手段是 hook 微信下载器（`SnsCdnDownloadBase` / `SubCoreCdnTransport`），但那条路已被 `_r14_ok0_n.md` 判定为弯路（批量出口需 5 道门、真机 hitN=0、单张 `picCachePath` 恒空），且**违背「不 hook」的本方案定位**。
2. 「打开大图」触发落盘依赖 UI 自动化（点击大图），不可控、有把微信撩到前台的风险，且 2026-10-19 R4 已实测「点开大图也不落盘」。
3. 本方案定位就是**被动读取已落盘缓存**：滚屏时微信自己会写缓存（已证实时写入），模块只负责在导出时把已存在的图拷走。用户没滚到的动态，图片留到下次（缓存不保证全覆盖，这是「缓存直读」的固有边界，不是缺陷）。

### 幂等性 / `scanExportedIds` 是否够用

- 现有 `scanExportedIds` 扫导出目录里已存在的 `<snsId>.txt`，据此跳过「已导出的动态」。**图片与文本同属一个动态目录，文本去重 = 图片去重**（文本在 = 该动态已导出过 = 图片也处理过）。
- 结论：**`scanExportedIds` 对图片幂等已够用**，无需单独为图片建去重。但注意两点：
  1. 图片落盘用的是 `img_<snsId>_<index>.jpg`（含序号），同名覆盖策略见 Q4（先删后 rename），重复导出不会产生重复文件，只会覆盖同名。
  2. 若某动态「文本导出成功、图片当时 miss」后想补图，需要先删掉该动态的 txt 让它重进导出（或单独跑 `sub=imgtry` 探针）。本方案不为此做增量补图机制（超出「缓存直读」范围，留作后续可选）。


---

## 附：复现命令清单

```bash
PY=/c/Users/lx/.workbuddy/binaries/python/versions/3.13.12/python

# Q1 字段映射
$PY _cache/_r14_fields.py fields 'Lq54/vm3;'        # d:D/E/F 字段
$PY _cache/_r14_walk.py dis classes3.dex 'Lq54/i40;' op   # i40.h 媒体列表（字段号 8）
$PY _cache/_r14_walk.py dis classes3.dex 'Lq54/vm3;' op   # vm3.d 字段号 1
$PY _cache/_r14_walk.py dis classes3.dex 'Lcom/tencent/mm/protocal/protobuf/TimeLineObject;' op  # ContentObj 字段号 6

# Q2 分桶算法
$PY _cache/_r14_walk.py dis classes3.dex 'Ljf3/f;' k          # [864]~[894] 拼 imageCachePath
$PY _cache/_r14_walk.py dis classes3.dex 'Ljf3/a;' a          # getMediaDownloadPath
$PY _cache/_r14_walk.py dis classes3.dex 'Lcom/tencent/mm/plugin/sns/model/y5;' e  # 分桶核心 charAt(2)/charAt(8)
$PY _cache/_r15_dis.py classes5.dex 'Lai/j;' g                 # MD5 hex
$PY _cache/_r14_walk.py dis classes3.dex 'Lcom/tencent/mm/plugin/sns/model/f4;' F8  # getAccSnsPath

# Q3 文件名前缀
for m in L P V e0 S N K; do $PY _cache/_r14_walk.py dis classes3.dex 'Lbf3/t0;' $m | grep const-string/jumbo; done
$PY _cache/_r14_walk.py dis classes3.dex 'Lbf3/t0;' s          # fliterPre
$PY _cache/_r14_walk.py dis classes3.dex 'Lbf3/t0;' f0         # getUrlMd5（视频后缀）
```

---

## 结论速览（给 team-lead 的 10 行摘要）

1. **方案可行**：朋友圈图片走 SNS 磁盘缓存直读，**不 hook、不发 HTTP、不解密**。
2. **Q1 mediaId 来源（最可靠一条，已确证）**：`SnsInfo.content` BLOB → `TimeLineObject.ContentObj`(字段6) → `i40.h`(字段8, LinkedList<媒体BLOB>) → `vm3.parseFrom` → **`vm3.d`（字段1，String）= mediaId**。项目 `sns_archive` 已存 content，离线可解，无需运行时 hook。
3. **候选②已否证**：`Ltj/k.field_mediaId` = `"<pack.snsId>_<reqDownType>"`，是 CDN 内部 fileKey，与文件名无关。
4. **候选③④不可靠**：url 无 mediaId（`mmsns/<46位hash>`）；`keys` 四项无一命中。
5. **`fliterPre` 已确证**（订正原「未确证」）：`t0#s` 反汇编 = 从下标 2 起跳过连续 `'0'`，再 `substring`。真机 `fliterPre` 自洽 1029/1029。
6. **⛔ Q2 分桶公式已作废**（订正原第 6 条）：`MD5(mediaId)[2]/[8]` 在真机 1029 个文件上只命中 **0.68%**。⇒ **改用 Q2b 索引直扫**（遍历 `sns/*/*/` 建 `Map<文件名,File>`，不算分桶）。真机媒体 ID 长度有 8 种（20~28），任何定长截断公式都会翻车。
7. **Q3 优先级**：`snsb_`(原图) → `snsu_`(压缩) → `snst_`(缩略)，逐级回退；三者都无记 miss，不回退 CDN（图片恒 400）。排除 `wxid_`(头像)、`snstblur_`(模糊占位)。
8. **Q4 读取姿势**：沿用 `Environment.getExternalStorageDirectory()` + 同 uid 直读，新增扫 `cache/` 下 hash32；**不 hook**。
9. **Q5 四态计数**：`imgRootMiss`/`imgFileMiss`/`imgReadFail`/`imgCopied`，互斥穷尽，stat 行可见。
10. **Q6**：不主动触发下载；幂等靠现有 `scanExportedIds`（文本去重=图片去重）。

> **本文件状态**：Q1/Q3/Q4/Q5/Q6 有效；**Q2 已改由 Q2b 取代**（2026-10-24 主会话真机反推订正）。

[DONE]
