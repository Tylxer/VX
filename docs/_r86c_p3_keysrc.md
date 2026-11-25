# R86c P3 查找键来源 —— 独立 fresh-eyes 只读取证

> 子代理 r86c-p3-keysrc｜只读｜工作根 `C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`
> 档位：【源码实证】/【日志实证】/【推断·高|中|低】/【UNKNOWN】
> 现场边界：全探针 off 回读确认，本文不触碰现场；需真机取证处只给方案与判据。
> 源码路径前缀 `wxgroup/app/src/main/java/com/lx/wxgroup/`，下文写 `ForceForward.kt:2542` 等即指该文件。

## 骨架（随问写盘）

- [DONE Q1] P3 查找键来源与语义
- [DONE Q2] 命中/未命中外部分叉条件
- [DONE Q3] g9c 观察与 P3 是否同一路径
- [DONE Q4] stock 为何稳定清晰 / 模块是否破坏 bigimgPath
- [DONE Q5] 根因裁决
- [DONE Q6] 下一步只读取证设计
- [DONE Q7] go/no-go
- [DONE 三件套]（结论 / go-no-go / 最小下一步）
- [DONE ALL]

---

## Q1 P3 查找键来源与语义

### 结论（fresh-eyes 关键修正：是**两个不同的 md5**，不是一个）

`FORWARD P3 origin-by-md5 miss reason=no-exist` 由 `ForceForward.p3OriginFromMd5()` 打出：
- hit 行 `ForceForward.kt:2547`：`FORWARD P3 origin-by-md5 path=<尾24> size=<len>`
- miss 行 `ForceForward.kt:2552`：`FORWARD P3 origin-by-md5 miss reason=<reason>`
- 调用点在 `newP3Listener()` 下载成功回调 `ForceForward.kt:2447`：`p3SrcOf(img) ?: p3OriginFromMd5(img, p3SrcLocalPath)`；预热同步路径另在 `ForceForward.kt:2401` 也调一次。

**键的来源（源码实证）**：`p3OriginFromMd5`（`ForceForward.kt:2514-2557`）遍历 `ImgInfo2` 对象的所有 String 字段，用正则 `([0-9a-fA-F]{32})(?:\.(jpg|jpeg|png|webp))?` 抠出 32 位 md5（`:2522-2529`），从 `localPath` 正则定位 `MicroMsg/<hash>/` 数据根（`:2531-2533`），拼唯一一个路径 `image2/<md5[0:2]>/<md5[2:4]>/<md5>.jpg`（`:2542`）。`p3SrcLocalPath = snap.localPath`（`:2367`）。**这个键代表源 jpg 大图**（微信按内容命名的 source md5），不是缩略图专属键。

**本轮日志里两个键的辨认（日志实证 + 逐字核对）**：

| 观察 | 出处 | 实际 md5 | 角色 |
|---|---|---|---|
| `resolveImage md5=bb7ef5cbb3 bucket=bb/7e ... pick=th_bb7ef5cbb35141ba265292df70d78104` | `_r86c_p3_transient_5x.txt:265`（off1 `:147`、on2 `:547` 同值） | `bb7ef5cbb35141ba265292df70d78104` | 消息 `ref.imgPath` 虚拟 URI 的 md5，经 `MediaGuardLogic.hexToken(imgPath)`（`ForceForward.kt:1324`）取出；`pickImageFile` 只能挑到 `th_` **缩略图** |
| off1 `origin-by-md5 path=929c32b22214fd78eaac.jpg size=335644` | `_r86c_b5_armfix_off1.txt:160` | `e8b43a5eb6f7929c32b22214fd78eaac` | **源图 md5**：`929c32b22214fd78eaac.jpg` 正是 `image2/e8/b4/e8b43a5eb6f7929c32b22214fd78eaac.jpg` 的 `takeLast(24)`（尾 20 位 hex + `.jpg`） |
| 5x `bigimg ready=false path=b35141ba265292df70d78104` | `_r86c_p3_transient_5x.txt:280` | `bb7ef5cbb35141ba265292df70d78104`（尾 24） | 这是 `chosenP3Src.takeLast(24)`（`ForceForward.kt:2737`），`fresh=false` ⇒ 回退的 `p3BigImgSrc`＝快照里的**缩略图路径**，不是 origin-by-md5 的查找键 |
| 5x/on2 `SCD s0b ret path=e8b43a5eb6f7929c32b22214fd78eaac.jpg` | `_r86c_p3_transient_5x.txt:341`、on2 `:522` | `e8b43a5eb6f7929c32b22214fd78eaac` | `s0.b` 返回 `ImgInfo2.e`（大图路径，`StockCdnProbe.kt:231-246`），证明 5x/on2 的记录里**仍持有源图 md5 `e8b43a…`**（未命中时 `p3OriginFromMd5` 不打印路径，故 miss 行看不到它） |

**裁决**：任务描述里「resolveImage md5=bb7ef5cbb3 而 P3 path=b35141…像是两个键」是同一缩略图 md5 的 head/tail 展示造成的表象；真正的 P3 查找键是**另一个 md5 `e8b43a…`（源图）**，命中的是 335644B 源 jpg。即「resolveImage 键＝缩略图 md5、`origin-by-md5` 键＝源图 md5」两条并行查找路径。

档位：键的提取/拼径逻辑【源码实证】；两 md5 的辨认与「miss 时源图 md5 仍在记录里」【日志实证】；「`e8b43a…` 即 335644B 文件的内容 md5」【推断·高】（由 off1 hit 的 tail 精确反推，未在 miss 时打全路径，故不写【已证】）。

`[DONE Q1]`

---

## Q2 命中/未命中的分叉条件

**结论：分叉不在键，而在所拼唯一路径上的本地大图文件是否存在（且过准入闸门）。**

1. **`origin-by-md5` 只拼一条路径**（`ForceForward.kt:2542`），无备选。过 `P3AdmitGate.admit(f, true)`（`:2545`），闸门见 `ForceForward.kt:106-131`：
   - `!f.exists() → "no-exist"`（`:122`）——本轮 miss 的字面原因
   - `th_ 前缀 → th-prefix`（`:123`）、`FLOOR=64KB` 以下 `→ below-floor`（`:108`、`:124`）、`bad-shape`（`:125-127`，须匹配 `image2/xx/yy/<32hex>.jpg`）、`magic-mismatch`（`:128`）、`siblingReason`（`:130`、`:158+`）
   - 命中才打 `path=… size=…`（`:2547`）；否则累计 `lastReason` 打 miss（`:2552`）。
2. **`resolveImage` 是另一条选择判据**：`pickImageFile`（`ForwardLogic.kt:366-391`）先滤 36B 坏占位（`:370`），再「大图（非 th_、图片扩展名、字节最大）→ HD → th_ 兜底」（`:372-390`）。因此桶里**只有缩略图**时 resolveImage 仍 `exists=true`（拿 `th_`），而 origin-by-md5 因 `<源md5>.jpg` 不存在而 `no-exist`——两者不矛盾，是两条路径。
3. **预热是「先触发下载、立即同步读盘」**（`ForceForward.kt:2353-2410`）：`:2392-2396` 异步 `svc.c(...,listener)`；不等落盘，紧接着 `:2400-2403` 同步 `p3SrcOf → p3OriginFromMd5 → snap.srcBigImgPath → snap.localPath`。真正置 ready 靠下载回调 `newP3Listener`（`:2432-2464`）。
4. **命中/未命中的现场事实**：
   - off1 `:159` `download fired r=false` 却 `:160` `origin-by-md5 path=929c32b22214fd78eaac.jpg size=335644` ⇒ 大图当时**已在盘**，下载触发结果 `r=false` 不是判据。
   - 5x 5 次 `:278/:379/:464/:540/:658` 全 `download fired r=false`，紧接 5 次 `:279/:380/:465/:541/:659` 全 `miss no-exist`；5/5 `resend-file p3=false`、`bigimg ready=false`（5x origin miss=5、ready=false=5）。
   - 同一 `srcMsgId=19757536/localId=885441/prefix=filehelper:`，off1 命中 335644B、5x/on2 全 no-exist ⇒ 【日志实证·高】「同图不同结果」；归因「本地源 jpg 在/不在」为【推断·高】（无 miss 当时的 `ls` 直接坐实）。
5. **触发条件假设**（【推断·高】）：`prepareBigImg` 同步读盘时 `image2/e8/b4/e8b43a5eb6f7929c32b22214fd78eaac.jpg` 在场且 ≥64KB/magic/非兄弟同字节 ⇒ hit ⇒ `copyP3Temp`（`:2566+`）→ `bigImgPath` → `q1.j` 覆盖为大图；不在场 ⇒ miss ⇒ `p3=false` ⇒ `q1.j` 留缩略图。

档位：闸门分支与「双路径」【源码实证】；5/5 miss、同图异果【日志实证】；「文件在场性」为根因【推断·高】。

`[DONE Q2]`

---

## Q3 g9c 那条观察与 P3 是否同一路径

**结论：不是同一查找路径；`hasBig=true` 不能推出「大图在场」。**

- hook 定义 `StockCdnProbe.kt:576-609`：拦 `com.tencent.mm.sdk.platformtools.g9.c(String,String,String)Map`，在 `before` 只读宿主传入的**待解析 xml 入参**（`:592`）。`hasBig = xml.contains("cdnbigimgurl")`（`:593`）、`bigLen`＝该属性值长度（`:594-595`）、`hasAes/hasMd5/md5Pre`（`:596-599`）、`has216b = xml.contains("216b1f7c")`（`:600`，硬编码的历史图 md5 前缀）。
- 语义：g9c 是微信 CDN 协议 xml 的解析入口；`hasBig=true bigLen=194` 只说明**某条消息的 xml 里带了 194 字符的 `cdnbigimgurl` 字段**，是服务端/CDN 侧的大图 URL 元数据。它既不看本地 `image2/` 桶，也不走 `P3AdmitGate`，与 `p3OriginFromMd5` 的「本地 `<源md5>.jpg` 文件存在性」完全无关。
- 本轮 `_r86c_p3_transient_5x.txt:791/:825` 的 `md5Pre=cff4f5a5`：该值是 xml 里 `md5="…"` 属性的前 8 位（`:598-599`），与 `c2c fmd5pre=cff4f5a5`（`:295` 等）同属 **45522B hevc/CDN 文件的字节 MD5**，不是 335644B 源 jpg 的文件名 md5（`e8b43a…`）。
- **补充证据（主会话提供，本文已只读复核）**：全文 `g9c` 共 272 处 = **271 行** `SCD2 g9c in` + 1 行 hook 安装日志（`:15`）。这些 `SCD2 g9c in` 的上下文集中在会话列表渲染期：`BAR refresh(list)` / `BAR reanchor`（`:788-789`）、`K7 perf snaps` / `K7 debounce`（`:797-798`、`:818-819`、`:831-832`）、`CHATOPEN`（`:795`、`:827`），即批量解析图片元数据，**不在发送链**。同段渲染里多种 `md5Pre` 并存：`f6ab3ac5`（27 次，thumb md5，hasBig=true 如 `:785/:787`）、`ca47f83e`（18 次，hasBig=false bigLen=-1，如 `:793/:800/:823`）、`cff4f5a5`（**全日志仅 2 次**，`:791/:825`，hasBig=true bigLen=194），另有 `a3a4752a`/`9e5f9f8c`/`263b84a6` 等；`has216b=true` 全日志 **0 次**（恒 false）。同段 `:821` 还出现 `SCD s0b ret path=58c7fac9cb76bb5a3ef8024c8b498118.jpg`（又一不同图 md5）。 ⇒ `cff4f5a5` 在 271 行里只占 2 次，是某条已发/渲染项的元数据，不是本轮转发图的大图落盘证据。
- `has216b=false` 只表示 xml 不含历史串 `216b1f7c`，与本案 `e8b43a…`/`bb7ef5…` 无关，全日志恒 false ⇒ 无判别力。
- 因此**不得**据 `hasBig=true md5Pre=cff4f5a5` 写「大图在场」；结合 Q2，5x 的真实链仍是 `origin-by-md5 no-exist`。

档位：hook 字段语义【源码实证】；g9c 与 P3 非同一路径【源码实证＋日志实证】；渲染期分布（271 行 `SCD2 g9c in`、`cff4f5a5` 仅 2 次、`has216b=true` 0 次）【日志实证】；「cff4f5a5 属 hevc/CDN 文件字节 md5 而非源 jpg 名 md5」【推断·中-高】（来自 c2c/x0cb 与 f2C2 `field_filemd5` 的多处同值比对，未反查该 45522B 文件实体）。裁决不变：**不得由 g9c 写「大图在场」**。

`[DONE Q3]`

---

## Q4 stock 为何稳定清晰 / 模块是否破坏 bigimgPath

**结论：stock 走原生 B6 链、带完整 CDN 凭证秒传大图；模块转发走 FileSender、依赖本地源 jpg。没有证据表明模块「改写/破坏」了查找键或原生 bigimgPath 写入——它是在原生转发不可用时绕过该链，退到「本地文件 + P3」路径。**

- stock（`_r86c_b5_armfix_stock.txt`）：`:181` `SCD B6 in MsgRetransmitUI.B6`、`:199` `c2c fbytes=45522 fmd5pre=cff4f5a5`、`:221` `x0cb md5=cff4f5a5 hit=1`、`:223` `f2C2 field_filemd5=cff4f5a52813d02be816367d4c58a8be field_thumbfilemd5=f6ab3ac5…`。整份 stock 日志**没有** `FORWARD P3` / `FORWARD resend-file` / `FileSender queued` 行 ⇒ 不走模块 FileSender。原生 B6 把 `fileId/aesKey/filemd5` 交给上传器，服务端引用/秒传 45522B 大图，出口稳定 cff4f5a5。
  - 注：`:182` `B6 r0.u hasCdnBigImgUrl=false` 是探针在 `r0.u` 上没看到该键，但出口 f2C2 带齐 CDN 凭证，故不影响「原生链完整」的判读（探针覆盖盲区，非大图缺失）。
- 模块侧（`_r86c_b5_armfix_off1.txt`）：`FORWARD plan strategy=RESEND_FILE reason=原生转发不可用，降级重发文件`（`:158`）⇒ 明确是「原生转发不可用」才降级到 FileSender。
- 模块链：`ForceForward.kt:1909` 调 `prepareBigImg`；`:2741` `FileSender.send(..., bigImgPath)`。`FileSender.kt:193` 先把 `q1.j` 写成 `path`（快照解析出的**缩略图**），`:224-226` 仅在 `srcBigImgPath` 非空时覆盖为源图大图路径，`:230-235` 仅在 P3 `bigImgPath` 非空且 >15KB 且非 `th_` 时最终覆盖。P3 没命中 ⇒ 两级覆盖都不发生 ⇒ `q1.j` 留缩略图 ⇒ 出口 9554B/0ef205c1（`5x:295/:310`）。
- **模块是否"破坏"bigimgPath**：`p3SrcOf`（`ForceForward.kt:2485-2500`）与 `p3OriginFromMd5`（`:2514-2557`）全程只读 ImgInfo2/文件系统，不写宿主对象/DB；`prepareBigImg` 只调用 `DownloadImgService.c`（`:2392-2396`），未写 `ImgInfo2` 的大图路径字段。清理逻辑只删 `cacheDir/_<uuid>` 临时文件（`:2564`、`:2754-2756`），不碰 `image2/` 源图。模块确实写 `q1.j/m.a/q1.r`（`FileSender.kt:209/216/220/225/233`、`ForceForward.kt:2302-2319`），但这些写入是**在降级链里补全上下文**，不是擦掉原生 B6 的大图写入（降级链里原生 B6 根本没走）。
- 根因候选排序：① 原生转发不可用 ⇒ 降级 FileSender（【日志实证】off1 `:158`）；② 降级后 P3 依赖本地源 jpg，miss 则缩略图（【源码实证】+【日志实证】）；③ "模块 hook 改写查找键"【推断·低，倾向否证】（同键 `e8b43a…`、只读实现、同图 off 能命中）。

档位：stock 原生链与模块链分工【日志实证＋源码实证】；"模块未破坏键"【推断·中-高】（只读代码路径已查，但未逐 hook 审计所有写点）。

`[DONE Q4]`

---

## Q5 根因裁决

**最可能根因（【推断·高】）**：本地 335644B 源图 jpg `image2/e8/b4/e8b43a5eb6f7929c32b22214fd78eaac.jpg` 在模块转发触发 P3 查找时**不在盘**（或被清理/尚未落盘），导致 `p3OriginFromMd5` 唯一拼径过 `P3AdmitGate` 返回 `no-exist` ⇒ `bigImgPath=null` ⇒ `FileSender` 的 `q1.j` 两级覆盖都不发生，留缩略图 ⇒ 出口 9554B/0ef205c1、肉眼模糊。命中侧（off1）同图同本地源图 md5 时文件在盘（335644B），出口 45522B/cff4f5a5、清晰。

候选排除/降级：
- **"P3 查找键被模块改写"**【推断·低，倾向否证】：键提取/拼径全程只读（`ForceForward.kt:2485-2500/:2514-2557`）；miss 时 `SCD s0b` 仍返回 `e8b43a…jpg`（5x:341/on2:522），说明记录里源图 md5 在；off1 用同键命中。无写点证据支持"改写"。
- **"源 jpg 字节在本地不稳定"**【推断·中】：off1 有 335644B、5x 全无，是**在场性**不稳定；字节内容稳定性本身未被观察（miss 时无文件可比对），不能升档。
- **"下载回调失败/迟到"**【推断·中-低】：5x 5 次 `download fired r=false`；但 off1 同样 `r=false` 却命中在盘文件 ⇒ `r` 不是判据；回调成功与否只是能否补救在场性的次级因素。
- **"闸门误拒在盘大图"**【推断·低】：off1 同形态文件（335644B `image2/e8/b4/<md5>.jpg`）过了 `strictShape=true` 闸门，误拒概率低；但未在 miss 时打 `exists/size/gate`，留作 Q6 C2 证伪口。

**证伪条件（任一命中即推翻主裁决）**：
1. Q6 探针显示 miss 时拼径文件 `exists=true size≈335644 magic=FFD8FF` 却仍 `no-exist/bad-shape/thumb-sibling` ⇒ 闸门或拼径错（C2），非在场性。
2. 显示 miss 时提取键不是 `e8b43a…` 而是缩略图 md5 `bb7ef5…` ⇒ 键选择错（C3）。
3. 显示 `p3BigImgReady=true` 且 `copyP3Temp` 通过但出口仍 0ef205c1 ⇒ 下游 `FileSender`/上传器问题（C4）。

`[DONE Q5]`

---

## Q6 下一步只读取证设计（主会话执行；本文仅方案）

**边界**：当前现场全探针 off，主会话先回读确认；本方案只读，不改源码/不装机/不触碰开关，不要求用户发图。用户肉眼 A→B→A 只在最终修复验收阶段（B6 全量功能 + 用户终审）才做，现在不做。

**样本与现场**：固定同图 `srcMsgId=19757536 / localId=885441 / prefix=filehelper:`、同目标 `wxid_xlrbelf6hv0w22`，B6X 全 off，自变量仅"转发时刻源 jpg 是否在盘/导出是否完成"。

**只读探针（建议，需主会话评估是否短期开启；每条都是对现有日志的纯追加）**：
1. `ForceForward.p3OriginFromMd5` miss 行（`ForceForward.kt:2552`）补字段：`md5Head=<前8> pathTail=<image2 尾段或尾40> exists=<bool> size=<len> gate=<reason> cand=<候选数>`。这是当前最大可观测性缺口——miss 时既不打键也不打路径（同 `_r85_thumbnail_regression_verdict.md:226` 的诉求）。
2. `ForceForward.prepareBigImg`（`:2400-2407`）补 `p3SrcOf/p3OriginFromMd5` 结果的 tail + `img.t` 值，确认预热同步那一刻拿到什么。
3. `newP3Listener` 失败分支（`:2460-2468`）补 `cbLocal/cbMsg/errType/errCode`，确认下载回调是否真的成功过。
4. 复用已存在的只读 hook，不新增：`SCD s0b`（`StockCdnProbe.kt:231-246`，看 `ImgInfo2.e`）、`SCD2 c2c`（fbytes/fmd5pre）、`SCD2 x0cb`（md5/hit）、`SCD f2C2`（`field_filemd5`/`UploadHitCacheType`）；stock 对照再加 `SCD B6`（`StockCdnProbe.kt:576+` 附近已有）。

**设备侧只读交叉验证（主会话执行）**：转发前 `adb shell ls -l` 两个桶目录 `.../MicroMsg/89476c7fc82a9f977bc8a901d68b7d37/image2/e8/b4/` 与 `image2/bb/7e/`，记录 `e8b43a5eb6f7929c32b22214fd78eaac.jpg` 的 size/mtime 与 `th_bb7ef5…` 的 size；若大图在，只读 `md5sum` 确认内容。全程不写文件、不删缓存。

**一锤定音判据（互斥）**：
- **C1**：miss 行 `exists=false` + `s0b=e8b43a…jpg` + `ls` 无 335644B 文件 ⇒ **源 jpg 本地缺席坐实** ⇒ 无"键修复"靶点，归档或另立"确保转发前原图落盘"的独立需求。
- **C2**：miss 行 `exists=true size≈335644 magic=FFD8FF` 但 gate 非 null ⇒ 闸门/拼径 bug ⇒ narrow go。
- **C3**：miss 行提取键 ≠ `e8b43a…`（如 `bb7ef5…`）⇒ 候选键选择 bug ⇒ narrow go。
- **C4**：`p3BigImgReady=true` + `copyP3Temp` 过 + 出口仍 `0ef205c1` ⇒ 下游 bug ⇒ narrow go。

`[DONE Q6]`

---

## Q7 go/no-go

**建议：no-go（不为"P3 查找键 / bigimgPath"继续投入修复）；只对 Q6 的一轮只读取证给条件性 go；默认归档收尾。**

理由：
1. 键本身没坏——命中与未命中用同一个源图 md5 `e8b43a…`，off1 已证明它能命中 335644B 大图并清晰；"修键"会去改一个非缺陷，风险是破坏已清晰的 off/stock 侧。
2. 分叉变量是**本地大图在场性**这一环境/时序条件，不是模块逻辑错误。要让它稳定，得改"转发前先把原图落盘"的工作流（功能级变更，R76/R85 已探过），爆炸半径大，且须 B6 全量 + 用户 A→B→A 终审——当前无授权、无必要。
3. 已定案红线不推翻：固定字节/MD5 不泛化、微X 全复刻 no-go、C2 命中≠清晰、B6X 探针肉眼不变、talker 整体真机实证·中、B5 机制只是信号·推断·高。
4. 翻转条件：仅当 Q6 命中 **C2/C3/C4**（确属代码缺陷）时，才 narrow go 做最小修复并走 B6 全量 + 用户肉眼 A→B→A；否则维持归档。

`[DONE Q7]`

---

## 三件套

- **结论**：P3 查找键 = `ImgInfo2` 里的**源图 md5** `e8b43a5eb6f7929c32b22214fd78eaac`（hit 文件 `image2/e8/b4/…jpg`，335644B），与 `resolveImage` 的缩略图 md5 `bb7ef5cbb35141ba265292df70d78104` 是**两条并行路径**；`no-exist` 最可能＝本地源 jpg 缺席【推断·高】。g9c `hasBig=true` 是 CDN xml 字段，与 P3 本地查图无关，不得据此写"大图在场"。stock 走原生 B6 秒传清晰，模块因原生转发不可用降级 FileSender、依赖本地大图。
- **go/no-go**：**no-go**（修键/改 bigimgPath）；条件性 go 仅限 Q6 一轮只读取证；默认归档。
- **最小下一步**：主会话回读确认探针全 off 后，按 Q6 补 1 条 miss 诊断日志（`md5Head/pathTail/exists/size/gate/cand`）+ `ls` 两个 image2 桶目录，对同图做一轮只读取证，用 C1/C2/C3/C4 裁决；不装机、不开用户肉眼验收。

`[DONE ALL]`

