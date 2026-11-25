# R86c Q4b refMd5 准入参照收口裁决（别名路由 · 降档重派）

> 任务性质 = 判据设计 + 根因推理 + dex 可达性取证 + 落码规格（**本代理不落码**）。
> 唯一目标：解决「枚举准入参照 refMd5 取值错误」这一会让修复白落码的致命断裂，给出可落码的唯一规格。
> 三档标注：【dex 实证】/【真机实证】/【推断·高|中|低】/【UNKNOWN 未闭合】。
> 落盘策略（防父 turn 被打断）：先建骨架（Q1~Q4 占位）→ 每答完一问立即写盘留 `[DONE Qn]` → 最后三件套 + `[DONE ALL]`。

## 致命断裂（本轮要解决的问题）

前一轮收口 `docs/_r86c_q4b_localpath_fix.md` 的 Q2/Q3 枚举准入 = `md5PrefixOfFile(f) == refPre`，`refPre = md5PrefixOf(refMd5)`，`refMd5 = arm 从 map[".msg.img.$md5"] 取`。但主会话独立复核抓出：

- xml `.msg.img.$md5` = **`ca47f83e`**（源图 md5，B-1 模块自建 task 传入，`docs/_r86c_q4_forensics_raw.md:43`；来源 `B6CheckExistProbe.MAP_KEYS:53` / `:224-227`）。
- 清晰态原图文件字节内容 MD5 = **`cff4f5a5`**（A 态 45522B `image2/88/da/88da…ff.jpg_hevc`，SCD fmd5pre 实测，`docs/_r86c_q4b_forensics_raw.md:19` / `_r86c_q4b_stepA_raw.txt:4`）。
- **ca47f83e ≠ cff4f5a5**。若按前一轮原样落码，枚举用 `refPre=ca47f83e` 去找内容 MD5=`cff4f5a5` 的文件 → **0 命中 → 永远 `injected origin=unavailable` → 保持模糊 → 白落码**。产物把两个 md5 混为一谈。本轮收口。

## 输入清单（按序读毕，全部只读）

1. `docs/_r86c_q4b_localpath_fix.md`（前一轮收口全文 + 末尾主会话独立复核附录）
2. `docs/_r86c_q4b_forensics_raw.md` + `_r86c_q4b_stepA_raw.txt` / `_r86c_q4b_stepAB_raw.txt` / `_r86c_q4b_fullpath_raw.txt`（cff4f5a5=内容 md5 实证 + 完整路径）
3. `docs/_r86c_q4_forensics_raw.md:36-71`（ca47f83e=源图 md5 实证 + 三态对照）
4. `docs/_r86c_q4b_final_verdict.md` + `docs/_r86c_d3_dex_forensics.md`（机制裁决 + fileId body/mapping + x0.u 字段映射）
5. `wxgroup/app/src/main/java/com/lx/wxgroup/core/ForwardCdnFieldHook.kt`（全文，arm/inject/resolveOriginFullpath/Pending）
6. `wxgroup/app/src/main/java/com/lx/wxgroup/probe/B6CheckExistProbe.kt:52-53/:224-227`（xml `.msg.img.$md5` 取值实证）
7. `docs/_r81_r80a_falsified_verdict.md` / `docs/_r82_b2_netscene_forensics.md`（历史反证）
8. 本轮亲跑 dex（偏移自算核准）：`_r35_sns_dis.py bodyx ok0/x0 u` / `onC2CUploadCompleted` / `modelimage/j2 O` / `modelimage/b1 z3`；`_r86c_off.py` 自算 code_off。

---

## Q1 两个 md5 语义（ca47f83e vs cff4f5a5）

**结论（一句话）**：`ca47f83e` = **xml 记录的"源图 md5"**（消息 xml `.msg.img.$md5`，B-1 模块自建 task 从 map 读出当 `field_filemd5` 下发，**从未参与上传字节身份**）；`cff4f5a5` = **本地 `.jpg_hevc` 文件的字节内容 MD5 前 8**（SCD `fmd5pre`，决定回包 `filemd5`→消息 `<img md5>`→肉眼清晰/模糊的**唯一字节身份**）。同一张源图的两个不同量——一个是下载前的源图指纹（发送方记录的原始图片），一个是微信本地转码落盘后的 hevc 字节指纹（重新编码 → 字节变 → MD5 变）——**本就不相等**，前一轮把它们混为一谈是致命错误。【dex 实证·高 + 真机实证·高】

### 各自是什么（含证据）

**(A) `cff4f5a5` = 文件内容 MD5（决定量旁证，字节身份）** ——【真机实证·高 + dex 实证·高】
- 【真机实证】A 态 fullpath = `/data/user/0/.../MicroMsg/89476…/image2/88/da/88da961e8032ad9d6d2f6e95a40596ff.jpg_hevc`（完整路径 `_r86c_q4b_fullpath_raw.txt:4`），`File.length()=45522`、内容 MD5 前 8 = `cff4f5a5`（`_r86c_q4b_stepA_raw.txt:4` 的 `fbytes=45522 fmd5pre=cff4f5a5`；口径 = `StockCdnProbe.kt:468-485` 的 `File.length()` + 全文件 MD5 前 4 字节 hex）。
- 【真机实证】三段同源：`fmd5pre=回调md5=出口 field_filemd5 前8=cff4f5a5`（`_r86c_q4b_forensics_raw.md:30`、`stepAB_raw.txt:10/12`），回调 `hit=1`、肉眼清晰（`stepAB:13` + `forensics_raw:12`）。
- 【dex 实证·本轮自算核准】决定这段字节身份的唯一 Java 入口 = `ok0/x0.u(Ltj/k;)I`（classes13，`co=0x3a98cc`，绝对偏移 `0x3a98dc+2·i`）：`i=0x0009 @0x3a98ee iget-object field_fullpath` → `i=0x000b @0x3a98f2 setFilePath`。本轮 `bodyx ok0/x0 u` 全量 grep `filemd5` **零命中** ⇒ x0.u 不读 `field_filemd5`，请求侧 md5 由 mars native 从 filePath 字节重算。
- 【dex 实证·本轮自算核准】回包身份写回消息：`ok0/x0.onC2CUploadCompleted(String, C2CUploadResult)V`（classes13，`co=0x3aa9bc`，绝对偏移 `0x3aa9cc+2·i`）：`i=0x002a @0x3aaa18 iget-object C2CUploadResult->filemd5` → `i=0x002c @0x3aaa1c iput-object Ltj/f->field_filemd5`；`i=0x001e/0x0020` 同法把 `hitCache` 写入 `field_UploadHitCacheType`（计数器，非判据）。即**消息里的 md5 = 服务端对上传字节回包的 filemd5 = 本地文件内容 MD5**，全程与 xml `$md5` 无关。

**(B) `ca47f83e` = xml 记录 md5（源图 md5，非决定量）** ——【dex 实证·高 + 真机实证·高】
- 【真机实证】B-1 模块自建 checkExist task：`md5Pre=ca47f83e md5Len=32`，标注"源图 md5"，`fullpathLen=0`，回调 `exist=false md5Len=0`（`_r86c_q4_forensics_raw.md:40-45`）。来源 = `B6CheckExistProbe.MAP_KEYS` 含 `.msg.img.$md5`（`B6CheckExistProbe.kt:53`），`xmlMd5 = map[keyMd5]`（`:222`），日志 `md5Pre=${xmlMd5.take(8)}`（`:227`），写入 `task.field_filemd5`（`:236`）。
- 【dex 实证】`MsgRetransmitUI.B6` 解析 map 四键含 `'.msg.img.$md5'`（classes7 `co=0x62c98c`，`i=0x014e` → v7 → `i=0x01b1 iput field_filemd5`；`docs/_r86c_d3_dex_forensics.md:180-184/197`）。
- 【dex 实证·本轮核准】该值写入的是 `field_filemd5`，而 `x0.u` **不读** `field_filemd5`（零命中）⇒ 它只是 checkExist 锚点候选，**不参与 C2C 上传字节身份**（D3 Q1 §6：`m1.m`/`x0.u` 均不消费 filemd5）。

### 为何同一张"原图"有两个不同 md5（确定解释）

- 【推断·高，dex + 真机联合】`messagesnapshot` 的 xml `.msg.img.$md5`（ca47f83e）记录的是**发送方/下载前源图的指纹**；本地 `image2/<2>/<2>/<32hex>.jpg_hevc`（内容 MD5 cff4f5a5）是微信把该源图**转码/压缩后落盘的 hevc 字节**。两者是同一张图在不同阶段的字节表示：hevc 转码重新编码 → 字节内容变 → MD5 变，故两值必然不同。
- 对齐铁证：A 态 `fmd5pre=回调md5=出口filemd5=cff4f5a5` 三段同源（`forensics_raw:30`），说明上传字节身份 = cff4f5a5，而不是 xml 里的 ca47f83e（后者在 A 态根本没出现在回包链）。B-1 传入 ca47f83e 仍 `exist=false md5Len=0`（`q4_forensics:45`）⇒ **传 xml md5 既不能命中、也不是回包身份**。
- 历史标 UNKNOWN 的收口：`docs/_r86c_q4_verdict.md:48` 与 `_r86c_q4b_final_verdict.md:84/127` 曾标「cff4f5a5 与 ca47f83e 是否同一原图 = UNKNOWN，不影响机制」。本轮把它定性为**源图指纹 vs 本地转码字节指纹，同图不同阶段**（【推断·高】），但**不把它们当作同一可互换的准入量**——这正是断裂所在。

### 置信度与边界
- `cff4f5a5`=文件内容 MD5 / `ca47f83e`=xml 记录 md5 =【真机实证·高 + dex 实证·高】（本轮亲跑核准 x0.u/onC2C/B6 偏移）。
- "两个 md5 为何不等=源图 vs 本地转码 hevc 字节" =【推断·高】（机制链闭合，但未直接反汇编 hevc 转码算法本身 → 不写"已证"）。
- 【UNKNOWN 未闭合（不影响裁决）】hevc 转码的精确算法与 ca47f83e 原始字节是否在本地仍有副本（native `startC2CUpload` acc=0x109 不可挂，D3:285）；本轮不需要这两个 UNKNOWN 来推进准入选型。

`[DONE Q1]`

## Q2 refMd5 取值唯一选型

**结论（唯一推荐）**：**候选⑤ —— 承认本地拿不到可作为参照的 `cff4f5a5`，彻底放弃"内容 MD5 准入（md5PrefixOfFile(f)==refPre）"这条路，改走「直接复用 A 态同款 `b1.z3` 拼路径」**：arm 已持有 `o8=d3.O8()` 与 `r0=a3(talker,msgSvrId)`（`ForwardCdnFieldHook.kt:151-152`），在 inject 用同一 `o8` 调 `b1.z3(r0.e/f/g/j)` 复现 A 态原图 hevc 路径，直接写 `field_fullpath`，**不经任何 hash 匹配**；`Pending` 删除 `refMd5`/`originPath` 字段，不再从 map 取 `.msg.img.$md5` 当参照。候选④（≈45KB+分桶+唯一性+mtime）**降级为运行时 sanity guard**（存在性+体积≈45522B），**不作为准入判据**。①②③全部否决。机制层 dex 可达，但是否落码取决于 Q3 只读取证确认「a3/o2 的 r0 经 z3 拼出的路径确实指向内容 MD5=cff4f5a5 的原图文件」。【dex 实证·高（⑤ 可达）+ 推断·高（选型）+ UNKNOWN（哪个 r0 字段="/Q3 闭合】

### 决定性逻辑：为什么 ①②③ 必然失败（不是取值挑错，是这条路根本不通）

- `cff4f5a5` 不是任何消息字段里"存着的参照值"——**它就是本地 hevc 文件字节的恒等指纹**（Q1）。它只在两处出现：①SCD 事后 `md5PrefixOfFile(fullpath)` 读文件算出；②上传后 mars native 从字节重算、服务端回包 `filemd5`（`x0.onC2CUploadCompleted i=0x002a`，本轮核准）。**模块转发尚未上传时，任何消息字段都不携带 cff4f5a5**。
- 前一轮的 `refPre = md5PrefixOf(refMd5)` 隐含假设"存在一个与文件内容 MD5 相等的参照字符串"。这个假设在真机不成立：xml `$md5`=ca47f83e（源图）、文件名 md5=88da961e…（image2 命名）、fileId body=0f836cfd/d1e5e773/uuid（CDN 结构段）——**三个不同的 md5，没有一个等于内容 md5 cff4f5a5**。⇒ 任何"用外部 refPre 匹配文件字节"的准入都是**不可满足的**，不是把 refPre 换个值就能修。

### 候选逐条评估（dex / 真机可达性 + 是否需落码）

**① xml `.msg.img.$md5`（ca47f83e）—— 否决（已证伪）**
- dex 可达：`g9.c map[".msg.img.$md5"]`（`B6CheckExistProbe.kt:53/222`、`D3:180/184`），arm 现码 `ForwardCdnFieldHook.kt:168` 已读。
- 真机：`md5Pre=ca47f83e`（`q4_forensics:43`）。
- 否决理由：语义=源图 md5≠内容 md5（Q1）；用它当 refPre 枚举 → 0 命中 → `origin unavailable` → 保持模糊。落码 = 白落码。

**② fileId(305f) body 内的 `0f836cfd`/`d1e5e773`/uuid —— 否决**
- dex 可达：`field_fileId` 来自 `g9.c map[$.cdnbigimgurl]`（`arm:159-161`），写入 `x0.u i=0x001b→0x001d forwardFileid`（本轮 bodyx 核准 `0x3a9912/0x3a9916`）。
- 真机：A body `0f836cfd` / B body `4954a2f9`（`q4b_forensics:23-27`）；这些是 ASN.1 TLV 结构段（类型/身份/秒传锚点）+ ASCII uuid，**无一是 32hex 内容 MD5，无一等于 cff4f5a5**。
- 否决理由：语义上不是内容哈希；且 fileId 被 R81/R82 铁证为非决定量（逐字相同仍一清晰一模糊）。无 dex 路径把它当字节指纹。**不需落码。**

**③ 消息其它字段（cdnbigimgurl/aeskey 等）推导 —— 否决**
- dex 可达：`$.cdnbigimgurl` = CDN URL（=305f fileId），`$.aeskey` = 加密 key（`ForwardCdnFieldHook.kt:159-161`、`B6 i=0x00dd/0x00e8/0x00fe`、`D3:180-185`）。
- 否决理由：URL/key 都不携带本地 hevc 字节的内容 MD5；无任何 dex 变换能把它们变成 cff4f5a5。**不需落码。**

**④ 不靠 md5，靠体积（≈45KB）+ 分桶 + 唯一性 + mtime —— 降级为 guard，不作准入**
- dex/真机可达：`File.length()`/`listFiles()`/`lastModified()` 纯文件 API（现码 `resolveOriginFullpath:238-249` 已枚举两级分桶）；A 态 45522B 实证（`stepA:4`）。
- 否决其为"准入"的理由：体积≠字节身份——另一张同尺寸图也会命中；"≈45KB"是**本图特例**，换图即失效（不通用）；mtime 与转发时刻的关系真机未取证【UNKNOWN】。单靠它无法保证选中的是 cff4f5a5 原图。
- 定位：**保留为⑤路径解析结果存在性/体积 sanity（≈45522B）+ 多候选歧义排除**，不作为决定量。

**⑤ 直接复用 A 态同款 b1.z3 拼路径、不经 hash 匹配 —— 唯一推荐**
- dex 可达·高：
  - `b1.z3(String,String,String,Z)String`（classes7，`co=0x2a6ae4`，绝对偏移 `0x2a6af4+2·i`）本轮反汇编：按前缀分支 `'SERVERID://'`(i=0x0008) / `'THUMBNAIL_DIRPATH://'`(i=0x0045) / `'THUMBNAIL_APPMSG_DIR://'`(i=0x005a)，否则 `qi2/a.b()`(i=0x0041) 取 image2 根 → `g1.a(...)`(i=0x008e) 合成路径。**它就是 A 态生成 `image2/<2>/<2>/<md5>.jpg_hevc` 的构造器**。
  - `j2.O` 调用四处 `b1.z3(r0.e/f/g/j)`（`i=0x0076/0x0080/0x008a/0x0094`，本轮核准 `code_off=0x2acc44`、绝对 `0x2acc54+2·i`），并把结果之一写入 `field_fullpath`（`i=0x0124/0x013f/0x01f8`）。⇒ 模块用**同一 r0** 调同一 z3，可复现 A 态路径。
  - arm 已持有调用所需对象：`o8=d3.O8()`（`:151`）、`r0=a3(talker,msgSvrId)`（`:152`）、`o2(imgLocalId)→ImgInfo2`（`:174`，Q1 已证 r0 恒非 null）。`XposedHelpers.callMethod(o8, "z3", …)` 与现码调用同范式，无需新 hook 点。
- 真机可达：A 态完整路径 `image2/88/da/88da…ff.jpg_hevc`（`_r86c_q4b_fullpath_raw.txt:4`）确为 z3 分桶形态；同点同字段 inject 可读 task（既有结论）。
- 关键 UNKNOWN（→ Q3 前置）：A 态(88da…/45522B) 与 B 态(dbfc…/9554B) 走的是**不同 r0**（不同 imgLocalId / 不同记录），否则同 r0 应拼出同路径。`a3(talker,msgSvrId)` 的 r0 与 `o2(imgLocalId)` 的 r0 哪个指向原图 hevc、**四个 z3 调用哪个是原图 hevc 字段**，静态 dex 不足以定论（j2.O 中 field_fullpath 的寄存器源 v4/v14 需更细数据流）。必须由 Q3 只读探针实测确认，**不许假设**。
- 是否需落码：机制成立则**需落码**（改 `ForwardCdnFieldHook.kt`，见 Q4），但**落码前必须过 Q3**。

### 唯一选型落点

| 维度 | 结论 |
|---|---|
| `refMd5` 字段 | **删除**（Pending 去 `refMd5`/`originPath`），不再从 map 取 `.msg.img.$md5` |
| 准入参照 | **无外部 md5 参照**；改为「z3 复现 A 态路径」+「存在性 & 体积≈45522B sanity」 |
| 决定量对齐 | `field_fullpath` 指向字节（Q1 已定），与选型一致；不改 content-md5 语义，只是不再需要一个拿不到的参照 |
| 闸门 | 复用现成 `enabled`+一次性 pending+TTL（`ForwardCdnFieldHook.kt:36/47/110-119`），普通发图不 arm |
| 可落码性 | dex 可达 ✅；**go 前置 = Q3 证实 r0→z3 路径命中 cff4f5a5** |

### 置信度
- ①②③否决 =【真机实证·高 + dex 实证·高】（三值≠cff4f5a5 实测 + 字段语义 dex）。
- ⑤ dex 可达 + "路径构造即身份、无需 md5 参照" =【dex 实证·高 + 推断·高】。
- "哪个 r0/z3 字段指向原图 hevc" =【UNKNOWN 未闭合 → Q3 只读探针闭合】；未闭合前**不宣称已可落码生效**。

> ⚠️ **主会话交叉引用注记（Q3.2 后补，Q2 正文保留历史原样不推翻）**：本轮亲跑 dex 证 `a3` 按 `msgSvrId=?`(w7) 查、`o2` 按 `id=?`(B7) 查，是**两个不同查询键**，返回的 r0 未必同一条。故 Q2「arm 拿 a3 的 r0、inject 用同一 r0 调 z3」措辞存在自洽缺口——真正写 field_fullpath 的是 **j2.O 现场那个 r0**。落码规格一律以 **Q3.2/Q4 收紧版**（inject 复用 j2.O 现场 r0，非 arm 的 a3 r0）为准，勿照抄本段旧措辞。

`[DONE Q2]`

## Q3 字节可获得性前置 + r0/z3 映射收口 + 落码 go/no-go 前置清单

> 本轮亲跑 dex（偏移自算，非照抄）：`_r86c_off.py` 自算 `a3 code_off=0x2a36b0`、`o2=0x2a52a0`、`j2.O=0x2acc44`、`b1.z3=0x2a6ae4`（classes7）；`_r35_sns_dis.py bodyx` 反汇编四方法 + `r0.b`。标注严格分档。

### Q3.1 字节可获得性前置：模块转发同一时刻本地是否真有 cff4f5a5 的 45522B hevc 文件？

**结论：【UNKNOWN 未闭合】。** 静态 dex / 现有落盘日志**都无法证明**「模块转发当场，本地存在内容 MD5=cff4f5a5 的 45522B hevc 文件且路径=A 态 88da 路径」。已知事实只覆盖 A 态自身，**不能外推到 B 态时刻**：

- 【真机实证·高】A 态（02:11 原生转发）`fullpath=.../image2/88/da/88da961e8032ad9d6d2f6e95a40596ff.jpg_hevc`，45522B、内容 MD5=`cff4f5a5`（`_r86c_q4b_forensics_raw.md:19`、`_r86c_q4b_stepA_raw.txt:4`；口径 `StockCdnProbe.kt:468-485` File.length + 全文件 MD5 前 4B hex）。这是**A 原生转发当场**微信当场拉/生成的文件。
- 【真机实证·高】B 态（02:12 模块转发）`fullpath=.../a77.jpg_hevc`，9554B、`0ef205c1`（`forensics_raw:20`）。即**模块转发时落地的是另一条 9554B 文件**，A 态那份 45522B 未被喂进 task。
- 【推断·中，未闭合】88da 原图文件可能仍在 image2 缓存（微信一般不立即删原图），但**模块转发同一时刻它是否仍在、路径是否逐字不变、内容 MD5 是否仍=cff4f5a5**，现有日志没有任何一条在 B 态时刻读过这份文件——主会话复核附录点 2 的质疑成立。

**静态能否闭合：不能。** 这是纯运行时文件系统事实，dex 看不到；必须有真机只读探针。给主会话的**只读探针最小规格**（交 Flash lane 落码 + 真机取证，本代理不落码）：

| 探针 | 挂钩点 / 手段 | 读什么（只读，不改宿主/不发消息/不建记录） | 判据 |
|---|---|---|---|
| P-FS | 不开闸，模块「转发（模块）」操作**同一时刻**用文件系统只读扫描 `root+/MicroMsg/<hash>/image2/` 两级分桶下 `*.jpg_hevc` | 每个候选 `path.takeLast(16)` + `File.length()` + 全文件 MD5 前 8 hex | 存在 `length≈45522 && md5pre==cff4f5a5` 的候选 ⇒ **字节可获得 go**；0 命中 ⇒ no-go（字节不可获得，禁硬凑）；>1 命中 ⇒ 歧义，需按 mtime/path 排除或多候选报错 |
| P-R0 | 现成 SCD 探针 / 只读反射，模块转发当场打印 `o8.o2(imgLocalId)` 的 r0 的 `e/f/g/j` 四字段原值（take(80)）+ `d3.O8().z3(...)` 四路返回路径 tail + 各自 `File.length()`/`md5pre` | 四路 z3 路径哪一路命中 `cff4f5a5`/45522B | 确定**哪个字段组**在真机指向原图 hevc（闭合 Q3.2） |
| P-R0ID | 同一时刻打印 `a3(talker,msgSvrId)` 的 r0 与 `o2(imgLocalId)` 的 r0 的对象身份（`System.identityHashCode`）与关键字段 e/f/g/j/l 值 | 两者是否同一对象、字段是否逐字相同 | 判定二者是否指向同一条图片记录（闭合 Q3.2 自洽性） |

- 纪律：三探针全只读——不写 `param.args`、不调宿主业务方法改状态、不发消息、不建 db 记录；日志只打 tail/len/md5pre/identityHashCode，不打完整 hash 或路径。
- 【UNKNOWN 未闭合】在 P-FS 真机跑出结果前，**不宣称字节可获得**；Q2 候选⑤的 go 前置挂在这里。

### Q3.2 r0/z3 映射收口（核心要害：严格审视 Q2 选型自洽性）

**结论（先说要害）：Q2 选型在 dex 层可达，但「arm 拿 a3 的 r0、inject 用同一 o8 调 z3 拼路径」这一表述存在**自洽性缺口**——本轮 dex 亲跑证明 a3 与 o2 是**两个不同 DB 查询键**、返回的 r0 未必同一条记录；而 Q2 真正需要的 r0 是**j2.O 现场用的那个 r0**。若 a3 的 r0 ≠ j2.O 的 r0，z3 拼出的仍可能是模糊图。**【dex 实证·高（映射事实）+ UNKNOWN 未闭合（哪个 r0 指向原图）】**，落码前必须 P-R0/P-R0ID 实测闭合。**

#### (1) dex 实证：a3 与 o2 是两个不同查询（本轮亲跑，非照抄）

- 【dex 实证·高】`b1.a3(String,long)→r0`（classes7，`co=0x2a36b0`，绝对 `0x2a36c0+2·i`）：`i=0x0005 a1.b(talker)` → `i=0x000a const-string 'msgSvrId=?'` → `i=0x0023 invoke-virtual b1.w7(...)` 查库 → `i=0x0027 moveToFirst` → `i=0x002d r0.b(Cursor)` → `i=0x0033 return v0`。**a3 按 `msgSvrId=?` 查，用 `w7`。**
- 【dex 实证·高】`b1.o2(Long)→r0`（classes7，`co=0x2a52a0`，绝对 `0x2a52b0+2·i`）：`i=0x0005 longValue` → `i=0x000e const-string 'id=?'` → `i=0x0028 invoke-virtual b1.B7(...)` 查库 → `i=0x0034 r0.b(Cursor)` → `return v0`。**o2 按 `id=?`（imgLocalId）查，用 `B7`。**
- ⇒ 二者经**不同 where 子句、不同查询方法**构造 r0，**不保证同一行**。Q2 里 "a3 的 r0" 与 "o2 的 r0" 不能默认互换——这正是 Q2 自洽性缺口的技术根。
- 【dex 实证·高】`r0.b(Cursor)` 列映射（本轮 `bodyx modelimage/r0 b`）：`e←getString(4)`、`g←(5)`、`u←(12)`、`m←(16)`、`f←(18)`、`j←(20)`、`l←(22)`；七者全是 DB cursor 字符串（相对标识/URI/摘要），无绝对路径（与 localpath_fix Q1 一致）。

#### (2) dex 实证：j2.O 里 z3 四路 → field_fullpath 的精确数据流（本轮亲跑）

- 【dex 实证·高】`j2.O(r0,I)Z`（classes7，`co=0x2acc44`，绝对 `0x2acc54+2·i`），四处 `invoke-virtual b1.z3`（每处前先 `d3.O8()` 取 b1、iget 一个 r0 字段做参）：
  - `i=0x0072 r0.e → z3 @0x0076`，`i=0x007e r0.f → z3 @0x0080`，`i=0x0088 r0.g → z3 @0x008a`，`i=0x0092 r0.j → z3 @0x0094`（绝对偏移分别 `0x2accca/ccce/ccd2/ccd6`）。
  - 返回路径写入 task：`i=0x0124/0x013f field_fullpath`、`i=0x0126/0x0141 field_thumbpath`、`i=0x0128/0x0143 field_midimgpath`，以及 hevc 分支 `i=0x01f8 field_fullpath`。**四个 z3 结果的寄存器源在 `i=0x0098` 后经 `new Ltj/k` 与 `0x011e n1.b`、`0x0122` 重排，v4/v13/v14/v11 各自对应哪一路需更细数据流**——本轮**只能确认「field_fullpath 由 z3 返回值写入，且 hevc 分支单独在 0x01f8 写一次」**，**不能静态锁定「e/f/g/j 四者中哪一个就是原图 hevc 字段」**。
  - 【推断·中】结合 z3 分支语义：`z3(r0.e)` 常走 `SERVERID://`/默认 image2 根 → 倾向**原图 big/hevc**；`z3(r0.f/g)` 常带 `THUMBNAIL_*` 前缀 → 倾向 thumb/mid（与 field_thumbpath/midimgpath 对齐）。故**最可能是 r0.e 这一路对应 A 态原图 hevc**。但这是前缀语义推断，非运行时定值 ⇒ 标【推断·中】，**不许写成已证**。
- 【dex 实证·高】`b1.z3(String,String,String,Z)String`（classes7，`co=0x2a6ae4`，绝对 `0x2a6af4+2·i`）：`i=0x0000 a8.F0` 空值短路 → `i=0x0008 'SERVERID://'` 分支（substring→Long→`Y2(J)→r0.e` 再递归）→ `i=0x0041 qi2/a.b()` 取 image2 根 → `i=0x0045 'THUMBNAIL_DIRPATH://'`、`i=0x005a 'THUMBNAIL_APPMSG_DIR://'` 前缀分支 → `i=0x008e g1.a(...)` 合成路径 → `i=0x0092 return`。**它就是 image2 分桶路径构造器**，机制可达。

#### (3) 对 Q2 选型的裁决（严格，不含糊）

- Q2 说 "arm 已持有 a3 的 r0、o8，inject 用同一 o8 调 b1.z3(r0.e/f/g/j)" ⇒ **dex 层可达成立**（o8=b1、z3 签名匹配、j2.O 确有此调用范式）【dex 实证·高】。
- 但 **"r0 自洽"未成立**：(1) 证 a3≠o2 查询；(2) 证真正写 field_fullpath 的 r0 是 j2.O 现场那个 r0，`e/f/g/j` 哪路是原图 hevc 仍未静态锁定。若 arm 误把 a3 的 r0 当 z3 输入，而 a3 按 `msgSvrId` 查到的是**另一条记录**（甚至缩略图记录），z3 拼出仍为 9554B 模糊路径 ⇒ **Q2 选型自我瓦解**。【推断·中（风险）+ UNKNOWN 未闭合（实测值）】
- **收紧后的正确做法**：inject 应复用 **j2.O 现场那一个 r0**（即 task 生成时已用的 r0），而非 arm 另取 a3 的 r0；并在落码前用 P-R0 确认「r0.e 那一路 z3 结果 == 内容 cff4f5a5 的 88da 路径」。该点**必须真机只读闭合，禁止写"已可落码生效"**。

### Q3.3 能否进 Q4 落码：go/no-go 前置清单

> 全部为**只读观测**，须为真才允许落码；任一不满足 ⇒ no-go，不硬凑。

| # | 必须观察到的前置事实（判据对着字节/肉眼，不对内部计数器） | 状态 | 不满足时 |
|---|---|---|---|
| G1 | 模块转发同一时刻，文件系统存在 `*.jpg_hevc`，`length≈45522` 且内容 MD5 前 8 == `cff4f5a5`（P-FS） | 【UNKNOWN 未闭合】 | no-go：字节不可获得，另案补原图落盘能力 |
| G2 | P-R0 确认 `z3` 某一路（预期 r0.e）返回的 88da 路径，其文件 `length=45522`/`md5pre=cff4f5a5`，且该路写入 `field_fullpath` | 【UNKNOWN 未闭合】 | no-go：r0/z3 映射错，写进去仍是模糊字节 |
| G3 | P-R0ID 确认 inject 复用的 r0 **就是 j2.O 现场写 fullpath 的那条**（a3 的 r0 与 o2 的 r0 若不同，须改用 j2.O 的 r0） | 【UNKNOWN 未闭合】 | no-go：Q2 选型自洽性缺口，拼错记录 |
| G4 | 已核不推翻的 dex/真机事实全部成立（决定量=fullpath 字节、x0.u 不读 filemd5、fileId 非决定量、A=45522/cff4f5a5 清晰、B=9554/0ef205c1 模糊） | 【真机实证·高 + dex 实证·高】已闭合 | 任一推翻则整体重裁 |

- **当前总判定：NO-GO（落码暂缓）**——G1/G2/G3 三项只读观测未闭合，G4 已闭合。机制层（dex 可达）go，但**落码准入层 unclosed ⇒ no-go**。禁把"机制可行"写成"已可落码生效"。
- 闭合路径：交 Flash lane 落 P-FS/P-R0/P-R0ID 三只读探针 → 真机取证 → 若 G1∧G2∧G3 全真，转 Q4 落码；否则维持 no-go 并按 Q4 互斥表处置。

`[DONE Q3]`

## Q4 完整落码规格 + go/no-go 互斥判据表

> **前置**：Q3 裁定「落码暂缓 / G1∧G2∧G3 未闭合」。故本节规格是**条件规格**——仅在 Q3 三只读探针全真、go 前置闭合后才允许 Mechanical 落码；未闭合前落码 = 白落码。本代理只出规格不改码。
> **起点 = 当前旧码**：`ForwardCdnFieldHook.kt` 现码仍是 R80 旧版——`Pending(fileId, aesKey, originPath, ts)`（:54-59），`arm()` 用 `o2(imgLocalId)` 遍历 img 的 String 字段找 `/MicroMsg/` 路径（:174-181）= 真机 `skip("no localPath")` 已证伪态；`resolveOriginFullpath(img, localPath, contentMd5)`（:219）依赖 localPath + 从 img 抠 32hex 候选。以下规格把它整体替换。

### Q4.1 完整落码规格（逐行可照写）

#### 改动面总览
- **唯一改动文件**：`wxgroup/app/src/main/java/com/lx/wxgroup/core/ForwardCdnFieldHook.kt`。
- **不动**：`HookEntry.kt`（m1.e hook 已装成功）、`FileSender.kt`（arm 三参调用签名保持）、P3AdmitGate / q1.j 三级覆盖 / copyP3Temp / j2.O / x0.u / fileId 注入以外的字段 / 任何其它文档。

#### 改动 1 — `Pending`（:54-59）：字段替换
```
删除 originPath: String?；改为持有 Q3 收敛后的决策产物（二选一，见下"选型 A/B"）：
  选型 A（Q2 候选⑤ · z3 现场复现，推荐）：Pending(fileId, aesKey, ts)
      —— 不再携带 originPath；路径在 inject 用 j2.O 现场 r0 调 z3 现拼。
  选型 B（若 P-FS 证实字节在 fixed path、P-R0ID 证实可用 arm 的 r0）：
      Pending(fileId, aesKey, z3R0: Any, ts) —— arm 存 j2.O 现场 r0（非 a3 的 r0），inject 用它调 z3。
  两选型共同：删除所有 md5 参照字段（refMd5/contentMd5/originPath 全删）——Q1/Q2 已证 cff4f5a5 拿不到作参照，放弃内容 MD5 准入。
```
- 类注释（:49-53）同步：originPath 删除说明；注明「路径来源 = inject 现场 z3 复现 j2.O 原图 hevc 路径；准入 = 存在性 + 体积≈45522 + 可选只读 md5pre==cff4f5a5 sanity；命中数必须==1」。

#### 改动 2 — `arm()`（:142-196）：删 img/localPath 前置，职责收缩为"读 map + 装 pending + 开闸"
- 保留 :143-165（参数校验 / findClass / `o8=d3.O8()` / `r0=a3(talker,msgSvrId)` / `r0.u` / `g9.c` map / fileId / aesKey 读取，全部只读）。
- **删除** :166-186 整段：
  - `contentMd5 = map[".msg.img.$md5"]`（:168-172）—— refMd5 参照删除，不再读 xml md5；
  - `img = o2(imgLocalId)`（:174-175）—— 删除该独立 img 获取（它按 `id=?` 查，与 a3 的 `msgSvrId=?` 不同，Q3.2 已证；改由 inject 用 j2.O 现场 r0）；
  - `localPath` 遍历 img 找 `/MicroMsg/`（:176-181）—— 已证伪的 `skip("no localPath")` 根因，直接删；
  - `resolveOriginFullpath(img, localPath, contentMd5)` + `originPath==null skip`（:182-186）—— arm 阶段解析整体移除。
- **改 pending 构造**（:188）：按选型 A `Pending(fileId, aesKey.orEmpty(), now)`；或选型 B 加 `z3R0`（从 j2.O 现场取得——须 P-R0ID 确认取法后再定，未确认前**不写死选取 a3 的 r0**）。
- **改日志**（:190）：`armed fileIdLen=..`（不再打 originTail；arm 已无 origin）。
- 签名不变 `arm(talker, msgSvrId, imgLocalId)`——`imgLocalId` 保留但函数体不再用（Kotlin 未用参数告警：沿用 :144 的 `imgLocalId<=0` 校验继续消费，或加 `@Suppress("UNUSED_PARAMETER")`）。
- **失败回退**：fileId/aesKey 读不到 → `skip("no fileId")` 关闸（同现码 :162-165），不 arm、保持现状模糊。

#### 改动 3 — `inject()`（:108-133）：加 z3 拼路径 + 写 field_fullpath（决定量写点）
顺序（保留 TTL :110-116、一次性清 pending :118-119、task 获取 :120 不变）：
```
val task = param.args?.getOrNull(0) ?: return   // = j2.O new 出的 Ltj/k，其字段已被 j2.O 写好（含 field_fullpath 当前缩略图路径）
// ① 取 j2.O 现场 r0（Q3.2：必须复用写 fullpath 的那条 r0，非 arm 的 a3 r0）
//    取法依赖 P-R0ID 结论；未闭合前禁止落码。候选：从 task 反射 j2.O 挂上的 r0 字段，
//    或在 arm 由 P-R0ID 确认的对象上保存引用。
// ② 用同一 o8=b1 调 z3(r0.e/f/g/j) 复现原图 hevc 路径（Q3.2(2)：预期 r0.e 那一路）
val originPath = resolveOriginPathByZ3(o8, r0)  // 返回 null=未命中/歧义，安全降级
if (originPath == null) {
    LogX.w("$TAG injected origin=unavailable reason=z3-no-hit")
    return   // 不写 task 任何字段，保持现状模糊，不硬凑
}
// ③ 命中才写三字段（fullpath 是决定量；fileId/aesKey 仅字段对齐，非决定量）
setObjectField(task,"field_fileId", p.fileId)   // 低风险对齐，不宣称致清晰
setObjectField(task,"field_aesKey", p.aesKey)
setObjectField(task,"field_fullpath", originPath)  // 决定量：离 x0.u setFilePath 最近写点（同 m1.e before hook）
LogX.i("$TAG injected fileIdLen=.. aesKeyLen=.. origin=true originTail=${originPath.takeLast(16)}")
```
- **写点位置**：与 fileId/aesKey 同一 hook（m1.e before）、同一次 pending 消费、在「清 pending :118 + 拿 task :120」之后。m1.e before 早于 x0.u `setFilePath`（`x0.u i=0x0009→0x000b`，D3 Q2 本轮自算 `0x3a98ee/0x3a98f2`），故在此写 fullpath 会被 x0.u 消费——与 final_verdict 复用的 x0.u before 写点等价，且**无需新 hook 点**（消解主会话复核附录「x0.u 新 hook 点」风险）。
- **不碰 filemd5**（x0.u 零命中）、**不动 enableHit**（保持 j2.O 已写的 true）、**不动 onlycheckexist/fakeSig/q1.j/l1.a**。

#### 改动 4 — `resolveOriginPathByZ3` 新增 + `resolveOriginFullpath` 旧函数处置
```
resolveOriginPathByZ3(o8: b1, r0): String? = runCatching {
  1) 四路 z3 候选：z3(r0.e)/z3(r0.f)/z3(r0.g)/z3(r0.j)，各得路径 path_e/f/g/j（Q3.2(2) dex 调用范式）
  2) 对每路做只读 sanity：File.exists && length>0 && 体积 sanity(≈45522B, 容差可配) && 可选 md5PrefixOfFile(path)==cff4f5a5（作 sanity 非准入，Q1 已证拿不到作外部参照）
  3) 命中数必须==1（歧义/0命中→null）；预期 path_e 命中（推断·中，须 P-R0 真机确认）
  4) 全程 runCatching；日志只打 tail/len/md5pre，不打完整值
}.getOrNull()
```
- **旧 `resolveOriginFullpath(img, localPath, contentMd5)`（:219-252）删除或停用**：它依赖 localPath + 从 img 抠 32hex 候选 + map 参照 md5——三者分别对应已证伪的 no localPath、映射 UNKNOWN、cff4f5a5 拿不到。保留只会误导。
- **工具函数**：`admitOrigin`(:255-259)、`md5PrefixOfFile`(:262-273) 可复用；`md5PrefixOf`(:276-279) 若不再解析 refMd5 可删。禁 P3AdmitGate（FLOOR=65536 拒 45522B；IMAGE2_RE 不认 `.jpg_hevc`）。
- **关键约束（对齐 Q2 候选④降级定位）**：体积≈45522 + 存在性 + 命中数==1 = sanity guard，**不是准入决定量**；真正决定量仍是"写进去的字节内容=cff4f5a5"，由 x0.u→native md5→回包 filemd5 闭环。sanity 只排除明显错图，不能替代肉眼/字节终审。

#### 改动 5 — 日志契约变更（影响 Q4.2 旁证）
- 消失：`CDN_FIELD armed ... originTail=...`（arm 无 origin）。
- 新增：`armed fileIdLen=..`（arm）；`injected ... origin=true originTail=<16>`（inject 命中）/ `injected origin=unavailable reason=z3-no-hit`（未命中）。

#### 改动 6 — 闸门隔离（沿用现成，零新增状态）
- `enabled` 默认 false（:47）；非本次转发不 arm；一次性 pending（:118 注入前即清）；TTL 2000ms（:36/:110-116）；失败 `clear()`（FileSender.kt:260）。
- 普通发图 `forwardCtx==null ⇒ 不 arm ⇒ :85 if(!enabled)return ⇒ 逐字节不变`。多目标每目标独立 arm，解析只在 pending 存在时跑一次。

### Q4.2 go/no-go 互斥判据表（判据一律对肉眼清晰/模糊；hit/UploadHitCacheType 绝不作判据）

> 唯一坐实 = **肉眼清晰 + A(清晰)→B(模糊)→开闸B(应翻清晰)→A(清晰) 翻转成立**。`fmd5pre`/出口 `filemd5=cff4f5a5`/`origin=true` 是机制旁证（证明字节写对），非终审。计数器 `hit`(1↔3)、`UploadHitCacheType`(1↔3) 两轮映射翻转（`final_verdict:78/101`），只记录、不裁决。

| 观测（肉眼最高，字节/MD5 辅助） | 日志/机制旁证 | 裁决 | 下一步 |
|---|---|---|---|
| **肉眼清晰 + A→B→A 翻转成立**（清晰→模糊→开闸清晰→清晰） | `injected origin=true`；写入 fullpath 的 `fbytes≈45522 fmd5pre=cff4f5a5`；出口 `filemd5=cff4f5a5` | **go 坐实，修复成立** | 固化 inject 现场 z3 拼路径写点 + 闸门；SCD 探针回归只打前缀 |
| 肉眼清晰但旁证矛盾（origin 日志缺失/计数器异常，图却清晰） | 以肉眼为准 | **go（终审压倒旁证）**，记异常待查 | 补一轮取数确认日志信号，不改判 |
| **仍模糊，且 `injected origin=unavailable` / 无 origin 日志 / 写入 fullpath `fmd5pre=0ef205c1`、fbytes≈9554** | z3 四路未命中 / 命中数>1 歧义 / 取错 r0（a3≠j2.O 现场 r0）/ pending 未命中 / TTL / install | **接入失败或 r0/z3 映射错（非方案证伪）** | 查 P-R0/P-R0ID 现场值、skip 原因、TTL、install；修后重测 |
| **仍模糊，但 `injected origin=true` 且写入 fullpath `fmd5pre=cff4f5a5`，出口 `filemd5=0ef205c1`** | 字节对了但回包认另图 | **机制未闭环（native hitCheck 未认原图）** | 同刻 stock 对照；核对 fileId 对齐(305f)是否进 forwardFileid；仍失败如实 no-go，不硬推 |
| **Q3 前置 G1 失败**：模块转发同一时刻无 `MD5=cff4f5a5` 原图字节文件 | P-FS 0 命中 / z3 拼路径不存在或 md5 不符 | **go 前置不满足 → no-go（字节不可获得）** | 补"原图字节下载/落盘"能力（另案），禁空占位/缩略图硬凑 |
| **Q3 前置 G2/G3 失败**：z3 哪路是原图 hevc / inject 复用哪条 r0 未闭合 | P-R0/P-R0ID 未跑或结论为否 | **no-go（r0/z3 映射未闭合）** | 先落只读探针取证，闭合后再落码；禁假设通过 |
| **微信崩溃 / 发送失败 / 双发消息** | hook 抛异常 / `l1.a()=false` | **no-go，立即回退** | 恢复 HookEntry install 注释态；`FileSender.kt:260 clear()` 清 pending 防串味 |

### Q4.3 判据纪律 + go/no-go 收口
- 唯一 go 坐实 = 肉眼清晰 + A→B→A 翻转；`hit`/`UploadHitCacheType` 两轮翻转**不得**作清晰/模糊判据或"机制通"证据。
- "字节写对但肉眼仍模糊" ≠ 已证伪方案，按表区分接入失败 / r0-z3 映射错 / 机制未闭环 / 字节不可获得，如实处置。
- **当前总判定：NO-GO（落码暂缓）**——Q3 G1/G2/G3 未闭合；Q4 规格为条件规格，仅在三只读探针全真后生效。禁写"已清晰"，结果级 go 只由真机肉眼翻转签发。

`[DONE Q4]`

---

## 三件套（Q3/Q4 收口）

**① 两个 md5 语义是否收口、refMd5 该取什么？**
**收口。** `ca47f83e` = xml `.msg.img.$md5` 记录的**源图 md5**（B-1 模块自建 task 传入 field_filemd5，x0.u 不读，非决定量）；`cff4f5a5` = 本地 `.jpg_hevc` 文件**字节内容 MD5 前 8**（A 态 45522B，决定回包 filemd5→肉眼清晰的唯一字节身份）。同一张图不同阶段、本就不等（【dex 实证·高 + 真机实证·高】语义；【推断·高】"源图 vs 本地转码 hevc 字节"）。**refMd5 取值结论 = "放弃该参照"**：三个外部 md5（xml `$md5`=ca47f83e / 文件名 md5=88da… / fileId body=0f836cfd|d1e5e773|uuid）无一等于内容 md5 cff4f5a5，"用外部 refPre 匹配文件字节"的准入**不可满足**，不是换值能修（Q1/Q2 已定，不得推翻）。故 Q4 规格删除 Pending 的 refMd5/contentMd5/originPath 全部 md5 参照字段，改走 "inject 现场 z3 复现 A 态原图 hevc 路径"；体积≈45522 + 存在性 + 命中数==1（+ 可选 md5pre==cff4f5a5 sanity）仅作 guard，**不作准入决定量**。

**② Q3 字节可获得性 + r0/z3 映射能否静态闭合？若不能的只读探针最小判据是什么？**
**不能静态闭合，三项均【UNKNOWN 未闭合 → no-go】。** (a) 字节可获得性：A 态 88da/45522B 是原生转发当场生成的，模块转发同一时刻该文件是否仍在/路径是否同/内容 MD5 是否仍=cff4f5a5——现有日志无一条在 B 态时刻读过它，dex 也看不到文件系统 ⇒ UNKNOWN。(b) r0/z3：本轮亲跑 dex 坐实 `a3` 按 `msgSvrId=?`、`o2` 按 `id=?` 是**两个不同查询键**（不同 where、不同方法 w7/B7），返回的 r0 未必同一条记录；`j2.O` 写 field_fullpath 用的是它现场那个 r0，e/f/g/j 四路 z3 哪一路是原图 hevc 需更细数据流，静态只到【推断·中：r0.e 最可能】。若 inject 误用 a3 的 r0（Q2 原表述），z3 可能拼出模糊图 ⇒ Q2 选型自洽性缺口未闭合。**只读探针最小判据**（交 Flash lane 落码 + 真机取证，本代理不落码）：P-FS = 模块转发同一时刻扫 image2 两级分桶 `*.jpg_hevc`，须存在 `length≈45522 && md5pre==cff4f5a5`（0 命中 no-go）；P-R0 = 打印四路 z3 路径 tail + 各自 len/md5pre，锁定哪一路命中 cff4f5a5 且写入 field_fullpath；P-R0ID = 打印 a3 的 r0 与 o2 的 r0 的 identityHashCode + e/f/g/j/l 值，判定是否同一对象。三探针全只读（不改宿主/不发消息/不建记录），G1∧G2∧G3 全真才转落码。

**③ 落码 go/no-go 一句话 + Flash lane 最小改动清单 + 真机肉眼判据？**
**go/no-go 一句话：当前 NO-GO（落码暂缓）——机制层 dex 可达 go，但 Q3 的 G1 字节可获得性 / G2 r0-z3 映射 / G3 inject 复用哪条 r0 三项只读观测未闭合，落码准入层 no-go，禁假设通过、禁写"已可落码生效"；三只读探针全真后才转 go。**
**Flash lane 最小改动清单（仅 1 文件 `ForwardCdnFieldHook.kt`，当前是旧码 originPath/no-localPath 失败态）**：(a) `Pending`(:54-59) 删除 originPath 及所有 md5 参照字段，改为持有 fileId/aesKey/ts（+ 可选现场 r0）；(b) `arm()`(:142-196) 删除 :166-186 的 `contentMd5`/`o2(imgLocalId)`/`localPath` 遍历/`resolveOriginFullpath` 前置，职责收缩为"读 map + 装 pending + 开闸"，日志去 originTail；(c) `inject()`(:108-133) 用 j2.O 现场 r0（非 a3 的 r0，须 P-R0ID 确认）调 `b1.z3(r0.e/f/g/j)` 复现原图 hevc 路径，命中才写 `field_fullpath`（与 fileId/aesKey 同 m1.e before hook 同次消费，无需新 x0.u hook），未命中不写任何字段；(d) 新增 `resolveOriginPathByZ3`（四路 z3 + 存在性/体积≈45522/命中数==1 sanity + 可选 md5pre sanity，禁 P3AdmitGate），旧 `resolveOriginFullpath` 删除/停用；(e) 日志契约改 `armed fileIdLen` / `injected origin=true originTail=<16>` / `injected origin=unavailable reason=z3-no-hit`；闸门/TTL/clear 沿用现成，`HookEntry.kt`/`FileSender.kt` 不动。
**真机肉眼判据（唯一 go 坐实）**：落码前只读确认 cff4f5a5 字节存在 + z3 路径命中 + r0 复用正确 → 基线 A 清晰(45522/cff4f5a5)/B 模糊(9554/0ef205c1) → 开闸模块转发 → 看 `injected origin=true` + 写入 fullpath 的 fmd5pre 与出口 filemd5 翻回 cff4f5a5（机制旁证）→ **肉眼 A→B→A 翻转为唯一 go 坐实**；仍模糊-but origin 没解析到 / 仍模糊-but 字节对了回包错 / 字节不可获得 / r0-z3 映射未闭合 / 崩溃按 Q4.2 互斥表处置；`hit`/`UploadHitCacheType` 两轮翻转绝不作判据。

---

> 收口回报（本代理）：Q3 `[DONE Q3]` + Q4 `[DONE Q4]` + 三件套全部落盘，末尾 `[DONE ALL]` 为真（非骨架预置假标记）。Q1/Q2 正文未改、未推翻；若 dex 事实与之冲突另记：本轮亲跑 `a3=msgSvrId查询`/`o2=id查询` 两不同键，构成对 Q2 "复用 a3 的 r0"表述的自洽性缺口，已在 Q3.2/Q4 作为 UNKNOWN + 收紧亮点标注，交主会话裁决是否需微调 Q2 措辞（未擅改 Q2 正文）。

[DONE ALL]
