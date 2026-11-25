# R86c 稳定性验证根因重裁（推翻既有裁决 + 重构机制链 + 修复方向重定向）

> 任务性质 = 只做只读推理与写文档，不落码、不跑 gradle、不碰真机 adb。唯一允许写的文件 = 本文档。
> 被重裁对象：`docs/_r86c_clearness_reverdict.md`（核心结论「22:01 清晰 = 微信原生链自行拼对、非我方修复码；差异变量 = 本地 hevc 缓存状态；先复现再决定；搁置/备选热修」）。
> 触发推翻：`docs/_r86c_stability_forensics.md`（本轮最小稳定性验证拿到 22:01 缺失的关键证据）。
> 三档标注纪律：【真机实证·高|中】/【源码实证·高】/【dex 实证·高】/【推断·高|中|低】/【UNKNOWN 未闭合】。【推断】绝不写成【已证】；判据对**用户肉眼 / 文件字节**，绝不用 `hit`（本轮 0↔3 翻转）或 `UploadHitCacheType` 作判据。每条结论标来源（dbg 行号 / 源码行号）。
> 落盘策略（防父 turn 打断）：骨架 → 每答完一问立即写盘留 `[DONE Qn]` → 三件套 → 真 `[DONE ALL]`。

## 输入清单（按序读毕，全部只读）
1. `docs/_r86c_stability_forensics.md` —— 本轮取证事实固化（最高优先级，含精确行号）。
2. `docs/_r86c_clearness_reverdict.md` —— 被重裁对象（Q1~Q4 + 三件套，217 行）。
3. `wxgroup/_r86c_stability_dbg.txt`（1753 行，可靠源）+ `wxgroup/_r86c_stability_full.txt`（logcat 12695 行，已核：FORWARD/FILE 行 0 条、SCD2 仅 2 条，以 dbg 为准）。
4. 源码：`core/ForwardCdnFieldHook.kt`、`core/FileSender.kt:135-263`、`feature/ForceForward.kt`（P3AdmitGate:106-174 / prepareBigImg:2353-2410 / newP3Listener:2427-2482 / p3SrcOf+p3OriginFromMd5:2484-2557 / copyP3Temp:2563-2606 / 决策与 send:2718-2765 / ctx:1486-1500）、`data/Keys.kt:190,524`。

## 不得推翻的 forensics 原始事实（逐条保留，本次重裁建立在其上）
- 3 次模块转发（22:24:39 / 46 / 56 附近）、toUser=filehelper、入口「转发（模块）」、用户肉眼 3 次均模糊（`_r86c_stability_forensics.md:10-11`）。
- 同一张坏图 216b：`r0.e=216b1f7ccd59b1268349fd0eb3c46801.jpg`（forensics:17）。
- p3 true/false：22:01 p3=true（dbg:448）、22:24 p3=false（dbg:1318/1376）。
- c2c 直读 fullpath `fbytes=9554 fmd5pre=0ef205c1`（dbg:1326/1445/1526）；x0cb 出口 `md5=0ef205c1`（dbg:1416/1506/1592）。
- 探针已 off、现场恢复（forensics:12）。

## Q1′ 决定性变量重定

**先给唯一裁决**：机制②（P3 `bigImgPath` 覆盖 `q1.j`）是本轮 22:01 清晰 / 22:24 模糊的**决定性变量**，且是**我方修复码**；「22:01 清晰 = 我方机制② P3 生效」**成立，置信度【真机实证·高】**。上游触发量是 `origin-by-md5` 能否在本地找到 335644B 源 jpg（22:01 找到→p3=true；22:24 `miss no-exist`→p3=false）。机制①（arm/inject 写 fullpath）两态均 `skip("no localPath")`、零写入，**不是**决定量；srcBigImgPath 22:01 非空、22:24 空，方向一致但属原生链读值而非我方写入，且 22:01 的 P3 覆盖发生在它之后、优先级更高，故非决定量；本地 hevc 缓存状态是**伴随现象/下游结果**，不是决定量本身。

### 1.1 四机制逐条定序与证据

| 排序 | 机制 | 22:01（清晰） | 22:24（模糊×3） | 定性 |
|---|---|---|---|---|
| **1 决定量** | 机制② P3 `bigImgPath` 覆盖 `q1.j` | `download r=true`(dbg:426)→`origin-by-md5 path=…b1268349fd0eb3c46801.jpg size=335644`(dbg:427/444)→`bigimg ready=true localId=883190 cb=l0`(dbg:445)→`resend-file p3=true`(dbg:448)。源码：`copyP3Temp`(ForceForward.kt:2740/2566-2606) 得临时大图→`FileSender.kt:224-229` 以 `bigImgPath` 覆盖 `q1.j` | `download r=false`(dbg:1294/1352)→`origin-by-md5 miss reason=no-exist`(dbg:1295/1353)→`bigimg ready=false path=cd59b1268349fd0eb3c46801 fresh=false`(dbg:1296/1354)→`copy skip reason=th-prefix`(dbg:1297/1355)→`resend-file p3=false`(dbg:1318/1376) | **决定性变量·我方修复码**【真机实证·高】。`p3` 与肉眼清晰/模糊 1:1 对应（本轮 1 例清晰 / 3 例模糊，无一例外）。 |
| **2 非决定量** | srcBigImgPath（原生 `MsgRetransmitUI.g` 读值） | `FORWARD ctx … big=/data/user/0/com.tencent.mm/MicroMsg/894...`(dbg:414) —— **非空** | `FORWARD ctx … big=`(dbg:1284/1338) —— **空** | 方向一致但**非决定量**：①它是原生链读值（ForceForward.kt:1486-1513 仅把 resolveImage 结果透传进 snap，非我方写宿主字段）；②`FileSender.kt:218-220` 对 `q1.j` 的覆盖排在 P3 的 `:224-229` 之前，P3 为真时后者覆盖前者；③22:01 真正落地的是 P3 复制到 cacheDir 的临时原图（dbg:445 path=…b1268349fd0eb3c46801.jpg≈335644B 源 jpg），不是 srcBigImgPath 直接落地。 |
| **3 非决定量** | 机制① arm/inject 写 `field_fullpath`/`fileId`/`aesKey` | `FORWARD CDN_FIELD arm skip reason=no localPath`(dbg:443) | 同 `arm skip reason=no localPath`(dbg:1311/1369) | **两态均零写入**，对结果无贡献【真机实证·高】。源码：`ForwardCdnFieldHook.arm()` 找不到绝对路径即 skip；`inject()` 仅在 pending 命中且 `originPath!=null` 时写 `field_fullpath`（ForwardCdnFieldHook.kt:108-134,180-192）。注意 U6 升级：这次有**直接日志**（旧裁决 U6 仅【推断·高】）。 |
| **4 伴随量** | 本地 hevc 缓存状态 | `R86C FS verdict scanned=22 a45522=1 cff4=1 hit=1`(dbg:472)，存在 45522B/`cff4f5a5` 原图 hevc(dbg:452) | `a45522=0 cff4=0 hit=0`(dbg:1403/1495/1568)，仅 9554B/`0ef205c1` 压缩 hevc | 旧裁决把它当「差异变量」，本轮精确化为**下游伴随**：P3 拿到源 jpg 时会同时产生 45522B hevc，拿不到就剩 9554B。它不是驱动 `q1.j` 写入的因，而是 `origin-by-md5` 命中与否的**结果**。 |

### 1.2 决定性因果链（逐环标源）
```
22:01  本地有 335644B 源 jpg（origin-by-md5 path=…size=335644，dbg:427/444）
  ⇒ P3 download r=true（dbg:426）+ listener 回读 ready=true cb=l0（dbg:445）
  ⇒ p3=true（dbg:448）⇒ copyP3Temp 成临时原图（源码 ForceForward.kt:2740/2566-2606）
  ⇒ FileSender.kt:224-229 用 bigImgPath 覆盖 q1.j（优先级高于 srcBigImgPath 的 :218-220）
  ⇒ 出口清晰（用户肉眼，forensics:11）+ P-FS cff4=1（dbg:472，伴随）⇒ 肉眼清晰【真机实证·高】
22:24  本地无源 jpg（origin-by-md5 miss no-exist，dbg:1295/1353，×3）
  ⇒ bigimg ready=false（dbg:1296/1354）+ copy skip th-prefix（dbg:1297/1355）
  ⇒ p3=false（dbg:1318/1376）⇒ q1.j 不被 P3 覆盖（FileSender.kt:224-229 整块跳过）
  ⇒ arm 仍 skip（dbg:1311/1369，机制①零写入）⇒ fullpath 由原生链拼
  ⇒ SCD2 c2c 直读 fullpath：fbytes=9554 fmd5pre=0ef205c1（dbg:1326/1445/1526）
  ⇒ x0cb 出口 md5=0ef205c1（dbg:1416/1506/1592）⇒ 肉眼模糊（forensics:11）【真机实证·高】
```
两态唯一系统性翻转的量 = **P3 是否拿到源 jpg 并覆盖 q1.j**（dbg:427/445/448 ↔ 1295/1296/1318），且与**用户肉眼**逐次吻合。`hit`（dbg:1416 hit=0 / 1506 hit=3 / 1592 hit=0）在同样模糊的三次间 0↔3 翻转，**印证纪律：hit 不作判据**。

### 1.3 对 forensics §1.3 的一处证据纠正（不推翻任何原始事实）
forensics:33 称「22:01 的 `ctx … big=`（srcBigImgPath）为空（dbg:1308 反证本次为空；22:01 未打此行）」。**本轮 dbg 原文不符**：dbg:414（22:01:09.646）明确打出 `big=/data/user/0/com.tencent.mm/MicroMsg/894...`（非空，被 `take(40)` 截断）；22:24 的 `big=` 空见 dbg:1284/1338。据此修正两点（均不触碰 forensics 受保护的原始事实：3 次转发 / 肉眼模糊 / p3 true-false / c2c-x0cb 值）：
- 22:01 srcBigImgPath **非空**【真机实证·高·dbg:414】，故 Q1③ 假设的「srcBigImgPath 覆盖」并非「与 big=为空矛盾」——它确实存在，但**不是决定量**（理由见 1.1 排序第 2）。
- 决定清晰的是**我方 P3 写出的临时原图**，不是 srcBigImgPath 本身。旧裁决把「22:01 清晰」归因于「原生链自行拼对」的**核心结论作废**，因为它只核了机制①（arm skip）漏掉机制②（P3）。

`[DONE Q1]`
## Q2′ origin-by-md5 miss 根因

**唯一裁决**：22:24 的 `origin-by-md5 miss reason=no-exist`（dbg:1295/1353）是**文件真的不在**——即按微信 `image2/<md5[0:2]>/<md5[2:4]>/<md5>.<ext>` 分桶规则直算出的原图绝对路径上 `File.exists()==false`，**不是**查找条件/分桶/命名规则写错。判据：`P3AdmitGate.admit()` 第一关 `if(!f.exists()) return "no-exist"`（ForceForward.kt:121-122）明确产出该 reason；而 22:01 用**同一套代码、同一分桶规则**找到 `path=…b1268349fd0eb3c46801.jpg size=335644`（dbg:427/444）。【真机实证·高 + 源码实证·高】

### 2.1 查找逻辑逐行核对（ForceForward.kt:2514-2557）
- a. 从 `img` 的 String 字段 regex 抠 32 位 md5（扩展名可选、缺省补 `.jpg`）——:2522-2529。
- b. 从 `localPath` 用 `Regex("/MicroMsg/[0-9a-fA-F]{32}/")` 取数据根，**不硬编码 hash**——:2530-2533。
- c. 拼 `root + "image2/${md5[0:2]}/${md5[2:4]}/${md5}.$ext"`——:2542（标准微信分桶）。
- d. `P3AdmitGate.admit(f, true)` 严格判据：存在 / 非 th_ 前缀 / >64KB / 形状 / magic / 兄弟比——:2545 + :121-131。放行记 `path=… size=…`（:2547）；否则记 `lastReason`，循环完 `miss reason=$lastReason`（:2552）。
- ⇒ 22:24 打出的 `no-exist` **只可能来自**:122 `!f.exists()`。查找条件/分桶/命名规则本身在 22:01 已自证可用，不存在「规则写错导致 miss」。

### 2.2 已证 vs UNKNOWN
| 项 | 定性 | 来源 |
|---|---|---|
| 22:01 该原图**存在**（335644B） | 【真机实证·高·已证】 | dbg:427/444 `path=… size=335644`；dbg:412 `resolveImage …pick=216b1f7ccd…jpg exists=true` |
| 22:24 该原图**不存在**（同路径 no-exist） | 【真机实证·高·已证】 | dbg:1295/1353 `miss reason=no-exist`；dbg:1282/1336 `resolveImage …pick=th_216b1f7ccd…`（只有缩略图） |
| miss 根因 = 文件真不在，**非**查找/分桶/命名问题 | 【真机实证·高 + 源码实证·高·已证】 | dbg:427 vs 1295 + ForceForward.kt:121-122/2542/2552 |
| 差异机制：22:01 源 jpg 从何而来 | 【推断·中·未闭合】 | 22:01 前后无「原图落盘写出」的专门日志；最可能是**前一次操作（22:01 前，如更早原生转发 / CDN 秒传 / 用户查看原图）把 335644B 源 jpg 落到 `image2/21/6b/`**——与 forensics:66「22:01 P-FS 证本地已有 45522B 原图 hevc」同源。本轮 dbg 只证明「22:01 在 / 22:24 不在」，未直接抓到「何时写入」。【UNKNOWN 未闭合】具体写入时刻/触发者。 |
| 22:24 为何不在（TTL / 缓存清理 / 换记录） | 【UNKNOWN 未闭合】 | 本轮无 cache-evict / TTL 日志；`r0` 的 a3id/o2id 三次各异（dbg:1298/1356 等）显示每次是不同的 ImgInfo2 对象，但同图同 bucket。`ServerID`/`d3.O8().o2()` 记录字段 e 在 22:24 为 `len=0 md5pre=NA`（dbg:1300/1358），印证记录里原图字段已空。**无法据现有日志判定是被清理、换记录还是 TTL**；不得写成已证。 |
| `download fired r=false`（dbg:1294/1352）的含义 | 【源码实证·高·已证为"宿主下载调用返回 false"】 | ForceForward.kt:2392-2396：`r`=`svc.c(localId,msgId,TYPE_HD,…)` 的返回值。22:01 `r=true`、22:24 `r=false` 是**同一调用不同返回**；它说明宿主侧本次 HD 下载未成功启动/被拒，与 origin-by-md5 miss 同向，但 `r` 不是判据（判据对字节/肉眼）。 |

### 2.3 对「是否可修、修哪」的定向
- 文件真不在 ⇒ **不是「改 origin-by-md5 查找条件」的问题**（规则对、同码 22:01 命中）——该方向【排除】作为根因修复。
- 真正的薄弱点是**原图不在本地时 P3 没有拿到字节的路径**：`download r=false`（dbg:1294/1352）+ `origin-by-md5 no-exist`（dbg:1295/1353）双失败，`ready=false` 后 `chosenP3Src` 回退到 `p3BigImgSrc`（缩略图），`copyP3Temp` 被 `th-prefix` 拒（dbg:1297/1355, ForceForward.kt:2574-2577），最终 `bigImgPath=null`→`p3=false`（源码 :2740）。⇒ 修复应落在「让 P3 在原图不在本地时可靠拿到原图」（详见 Q3′），而非改查找分桶。

`[DONE Q2]`
## Q3′ 修复方向与判据

**唯一裁决**：**推翻「搁置热修 / P-FS 写 fullpath」方向**。既有裁决（_r86c_clearness_reverdict.md:95/122/208）把修复押在「P-FS 写 `field_fullpath`=本图热修常量 45522B/`cff4f5a5`」，并把「模块转发必模糊」当前提——该前提已被 22:01 反例证伪，且本轮证明决定量不是 arm/inject 写 fullpath（机制①两态均 skip、零写入，dbg:443/1311/1369）。**本轮不改码，仅定方向**：修复重定向到「**让机制② P3 在原图不在本地时仍能可靠拿到 335644B 源 jpg 并覆盖 q1.j**」这一条主轴。

### 3.1 为何推翻旧方向
- P-FS 写 fullpath 针对的是机制①/field_fullpath，但本轮 22:01 清晰恰恰发生在 **arm skip、fullpath 由原生链拼**的情况下（dbg:443 + forensics:37）；机制①两态零写入 ⇒ 写 fullpath **不是**决定量。旧方向打错了靶。
- `cff4f5a5`/45522B 是**本图热修常量**（旧裁决 U8【UNKNOWN】泛化能力），且本轮显示它只是 P3 命中后的**伴随 hevc**（dbg:452/472）；把它写死进 fullpath 既不能覆盖「原图根本没下载」的 22:24 态，也不泛化。
- 「先复现再决定 / 搁置」（旧裁决 Q2/Q3）的动机是「单样本、不稳定、出口 md5 缺」。本轮已复现 3 次模块转发（22:24:39/46/56），P3 miss→p3=false→45522B 缺失→0ef205c1 出口→肉眼模糊 **3/3 稳定复现**；机制链完整（见 Q1.2），「搁置观察」的前提已不满足。

### 3.2 修复方向重定向（按优先级，仅定方向不落码）
1. **主轴·让 P3 在原图不在本地时拿到源 jpg**：22:24 的失效点是 `download r=false`（dbg:1294/1352）+ `origin-by-md5 no-exist`（dbg:1295/1353）双失败。方向 = 在 `p3OriginFromMd5` 未命中时，走**主动 HD 下载并等待/回读落盘**（复用 prepareBigImg 的 `svc.c(TYPE_HD)` 姿势，ForceForward.kt:2391-2396；补 listener 成功后回读 ready 的等待，:2427-2482），或扩展源字节获取途径（如 CDN 凭证 / SERVERID 引用），使 `bigImgPath` 非空→`p3=true`→`q1.j` 被原图覆盖。【推断·中·方向】
2. **次轴·不放弃 srcBigImgPath 兜底**：22:01 `big=` 非空（dbg:414）说明原生链可提供原图路径；但 22:24 `big=` 空、无法兜底。可评估在 `p3=false` 且 `srcBigImgPath` 非空时仍写 `q1.j`（FileSender.kt:218-220 已具备），前提是确认其指向 335644B 源 jpg 而非缩略图。
3. **明确不做**：不改 `origin-by-md5` 分桶/命名规则（Q2 已排除）；不写死 fullpath 常量（U8 不泛化）；不依赖 `hit`/`UploadHitCacheType` 作为成功判据（本轮 hit 0↔3 翻转）。

### 3.3 最小只读验证方案（不落码 / 不跑 gradle / 不碰 adb 开关）
> 本轮只定方向；下列为**交主会话/实现 lane 在后续轮执行**的只读方案草稿，本次不执行。判据对**用户肉眼 + 文件字节**，`hit`/`UploadHitCacheType` 只记录不裁决。

| 步 | 动作（只读） | 控制变量 | 记录 / 判据 |
|---|---|---|---|
| 0 | 先持续落盘 dbg+logcat（22:01 出口 md5 曾因缓冲刷丢，U4；且 logcat 漏 FORWARD 行，本轮已证）——确保 `origin-by-md5`/`ready`/`resend-file`/c2c/x0cb 全留档 | — | 起点时间戳 |
| 1 | 固定同一张 216b 图、同一入口「转发（模块）」、toUser=filehelper；**先确保本地无源 jpg**，连发 ≥3 次 | 图/入口/target 固定；源 jpg 状态=无 | 每次肉眼；旁证 `origin-by-md5`/`p3`/c2c `fmd5pre`/x0cb `md5`。预期模糊、`p3=false`、`md5=0ef205c1` |
| 2 | 人为制造源 jpg 在本地（如先触发原图下载/查看，使 `image2/21/6b/216b…jpg`=335644B 落盘；只读确认存在），同图同入口再发 ≥2 次 | 仅「源 jpg 本地存在性」翻转 | 肉眼；旁证 `origin-by-md5 path=…size=335644`/`p3=true`/c2c 原图字节。预期清晰——**这是验证「源 jpg 在 ⇒ P3 覆盖 ⇒ 清晰」的决定性一步** |
| 3 | 收尾广播探针 off、stat 回读 fs=false r0=false；结束落盘 | — | 现场恢复 |

**胜负判据（唯一）**：肉眼清晰/模糊为终审，文件字节（`fmd5pre`、`origin-by-md5 size`、出口 `md5`）作机制旁证；`p3` true/false 可作「我方机制是否生效」的旁证但**不替代肉眼**（22:01/22:24 肉眼已定）。若步 2 在源 jpg 存在下仍模糊 ⇒ 说明还需别的上游条件，方向再修；若步 2 清晰、步 1 模糊且 `p3` 随之翻转 ⇒ 坐实「P3 原图获取」为修复靶点。任何一轮 `hit`/`UploadHitCacheType` 异常均只记录不改判。

`[DONE Q3]`
## Q4′ 既有裁决逐条处置

对 `docs/_r86c_clearness_reverdict.md` 逐条标【保留】/【改写为…】/【作废】，并说明本轮新证据如何改写。

### 4.1 Q1~Q4 主结论
| 旧条目 | 处置 | 本轮新证据如何改写 |
|---|---|---|
| Q1 核心结论：「22:01 清晰 = 原生链自行拼对、非我方修复码、差异变量=本地 hevc 缓存状态」【推断·中-高】(旧:35) | **【作废】改判为**：22:01 清晰 = 我方机制② P3 生效（拿 335644B 源 jpg 覆盖 q1.j），差异变量 = `origin-by-md5` 能否找到源 jpg→p3 true/false；hevc 状态是伴随量 | dbg:427/444/445/448（22:01 P3 全链）vs dbg:1295/1296/1318（22:24 全链）；机制①两态 skip（dbg:443/1311/1369）。旧结论只核 arm/inject、漏 P3，被直接推翻 |
| Q1 复核更新（换图候选被反驳、差异锁定缓存状态）(旧:37) | **【改写】**：换图排除【保留】；「差异=缓存状态」精确化为「差异=`origin-by-md5` 命中→P3 覆盖」 | forensics:17 同图 216b 保留；P3 链为本轮新增 |
| Q1①原生转发排除【源码实证·高】(旧:43) | **【保留】** | 本轮入口确为「转发（模块）」、arm 铁证仍成立（forensics:11, dbg:443/1311/1369） |
| Q1②换图排除【真机实证·高】(旧:44) | **【保留】** | forensics:17 逐字同图 |
| Q1③「j2.O 兜底/srcBigImgPath 覆盖=唯一最可能」【推断·中-高】(旧:45) | **【作废】其「唯一最可能」与「原生自行拼对」定性**；改写为「srcBigImgPath 22:01 非空（dbg:414）但非决定量；决定量是我方 P3」 | dbg:414 纠正「big=为空」；P3 覆盖排在 srcBigImgPath 之后且优先级更高（源码 :224-229 vs :218-220） |
| Q1④探针副作用排除【dex 实证·高】(旧:46) | **【保留】** | 无新证据触动 |
| Q1⑤判据有误排除【真机实证·高】(旧:47) | **【保留】** | 判据仍对肉眼/字节 |
| Q1⑥其它/APK 差异【推断·低】(旧:48) | **【保留】并强化** | 本轮同 APK 同会话翻转，进一步排除 APK 因素 |
| Q2 唯一推荐「先复现再决定、不立即落码」(旧:87) | **【作废】改判为**「已复现 3 次、机制链闭合，取消搁置；仅定修复方向、本轮不改码」 | forensics:10 三次转发；22:24 miss→p3=false→0ef205c1→肉眼模糊 3/3 |
| Q2 2.1 ①结果级 NO-GO 维持、理由改写为「未做 A/B 完整对照+稳定性未证+出口 md5 缺」(旧:93) | **【改写】**：NO-GO（结果级）保留，但理由换为「本轮仅补 P3 字节链，未做源-jpg 存在性 A/B 翻转对照；出口 md5 仅 22:24 有、22:01 仍缺（U4）」 | 三次同态复现已证「模糊」稳定，但缺「源 jpg 在⇒清晰」的正向对照（Q3 步 2） |
| Q2 2.1 ②需补肉眼基线【真机实证+推断·高·升级首要】(旧:94) | **【保留并升级】** | 本轮已补 3 次肉眼基线（均模糊），仍需 Q3 步 2 正向对照 |
| Q2 2.1 ③改判 P-FS 写 fullpath 热修【前提证伪、备选保留】(旧:95) | **【作废】**「作为备选修复保留」——本轮证机制①两态零写入、cff4f5a5 是伴随量且本图常量不泛化 | dbg:443/1311/1369 + Q3.1 |
| Q2 2.2「必模糊」全称命题证伪 / 存在性事实成立(旧:99-100) | **【保留】** | 22:01 清晰反例 + 22:24×3 模糊双存，均成立 |
| Q2 2.2b 换图追问处置(旧:105-107) | **【保留】** | 同图已 closure，无新证据 |
| Q2 2.3「先复现再决定」六理由(旧:111-116) | **【作废】**「复现成本极低/先复现」的搁置逻辑——复现已完成；其余「泛化硬伤」理由转入 Q3.1 保留 | 本轮即复现产物 |
| Q2 2.4 决策分叉表(旧:120-124) | **【改写】**：分叉变量从「换记录/换图」换为「源 jpg 本地存在性」（Q3 步1↔步2）；「稳定机制→搁置」分支删除 | P3 miss→p3 false 稳定三连，非偶发 |
| Q3 最小验证方案 + 判据(旧:130-167) | **【改写】**：判据纪律（肉眼/字节、hit 不作判据、步0落盘、探针 off）**保留**；变量与控制流程换为 Q3.3 | 本轮证 logcat 漏 FORWARD 行、hit 0↔3 翻转，印证旧纪律 |
| Q4 U1 差异根因【推断·中-高·未闭合】(旧:177) | **【改写为闭合】**：差异根因 = P3 能否拿到源 jpg，判定量【真机实证·高】；仅「22:01 源 jpg 写入时刻」仍 UNKNOWN | Q1.2 全链 + Q2.2 |
| Q4 U2 同图【真机实证·高·闭合】(旧:178) | **【保留】** | forensics:17 |
| Q4 U3 清晰偶发/稳定【UNKNOWN】(旧:179) | **【改写为部分闭合】**：22:24 模糊态 3/3 稳定（forensics:10 + 全链），但「源 jpg 在⇒清晰」正向稳定仍【UNKNOWN】待 Q3 步2 | 三次翻转一致 |
| Q4 U4 22:01 出口 md5【UNKNOWN】(旧:180) | **【保留 UNKNOWN】**：本轮 22:01 段仍无 c2c/x0cb（SCD 主探针 22:01 未开，dbg 22:01 段止于 472），无法取回；不得据 P-FS hit=1 越级断言出口 md5 | forensics:67 明示；dbg 22:01 段无 x0cb |
| Q4 U5 22:01 `field_fullpath` 实际指向【UNKNOWN】(旧:181) | **【保留 UNKNOWN，但影响降级】**：仍无 22:01 直接 c2c/fullpath 观测；但 P3 链（dbg:427/445/448）+ 肉眼清晰已使「机制链不再单靠推断」（forensics:67） | 缺 22:01 c2c，U5 不闭合 |
| Q4 U6 arm skip【推断·高·非直接日志】(旧:182) | **【改写为【真机实证·高】】**：22:01 有直接日志 dbg:443 `arm skip reason=no localPath`（旧裁决时 raw 被前缀过滤未含此行）；22:24 dbg:1311/1369 同 | dbg:443/1311/1369 本轮可靠源直证 |
| Q4 U7 探针副作用【dex 排除+残余 UNKNOWN】(旧:183) | **【保留】** | 无新证据 |
| Q4 U8 P-FS 泛化【UNKNOWN】(旧:184) | **【保留并升级为「已判定不适用」】**：本轮证 cff4f5a5 是伴随量、机制①零写入，写死 fullpath 方向作废（Q3.1） | Q1/Q3 | 
| Q4 U9 srcBigImgPath/q1.j 运行时值【UNKNOWN】(旧:185) | **【部分闭合】**：22:01 `big=/data/.../MicroMsg/894...`（dbg:414，非空但 take(40) 截断、未打全尾段）；22:24 `big=`（dbg:1284/1338，空）。完整指向是否 335644B 源 jpg 仍【UNKNOWN】，但「覆盖存在」已观测 | dbg:414/1284/1338 纠正旧「为空」 |
| 已闭合项清单(旧:188-196) | **【大部分保留】**；其中「差异变量为本地 hevc 缓存状态」(旧:196) **【改写】**为「差异变量为 `origin-by-md5`→P3 原图获取」 | hevc 降为伴随量 |
| 三件套①（原生链自行拼对、非我方修复码）(旧:205) | **【作废】**，改判为「我方 P3 生效」 | 见 4.1 Q1 |
| 三件套②（先复现再决定、P-FS 备选保留）(旧:208) | **【作废】**，改判为「取消搁置、重定向到 P3 原图获取、本轮不改码」 | 见 Q3 |
| 三件套③（验证方案：换记录/换图）(旧:211) | **【改写】**变量换为「源 jpg 本地存在性」；纪律保留 | 见 Q3.3 |

### 4.2 处理 U4 / U5 / U9 专项说明
- **U4（22:01 出口 md5）**：【UNKNOWN·保留】。22:01 未开 SCD 主探针，dbg 22:01 段（400-479）无任何 `SCD2 c2c`/`x0cb`；无法取回出口 md5。forensics:67 亦明确。本轮**不**用 P-FS `hit=1`（dbg:472）越级推「22:01 出口 md5=cff4f5a5」。
- **U5（22:01 fullpath 指向）**：【UNKNOWN·保留，影响降级】。无 22:01 直接 c2c/field_fullpath 日志；但 22:01 的 P3 字节链 dbg:427/445/448 已补，使「清晰由我方 P3 所致」不再单靠「决定量+肉眼」推断（forensics:67）。U5 本身（指到哪个文件）仍不闭合。
- **U9（srcBigImgPath/q1.j 运行时值）**：【部分闭合·改写】。旧裁决假设「本次 srcBigImgPath 覆盖未观测、且以为 22:01 big=为空」；本轮 dbg:414 直证 22:01 `big=` 非空、dbg:1284/1338 直证 22:24 空。故「覆盖是否存在」已由 UNKNOWN 升为【真机实证·高】；但 `take(40)` 截断、未打全路径尾段，是否精确指向 335644B 源 jpg 仍【UNKNOWN】。

`[DONE Q4]`
---

## 三件套

**① 决定性变量一句话 + 置信度。**
**决定性变量 = 机制② P3 `bigImgPath` 覆盖 `q1.j`：22:01 我方 P3 拿到 335644B 源 jpg（`origin-by-md5 path=…size=335644` dbg:427/444 → `ready=true` dbg:445 → `p3=true` dbg:448）→ `FileSender.kt:224-229` 用临时原图覆盖 `q1.j` → 肉眼清晰；22:24 源 jpg 不在（`miss reason=no-exist` dbg:1295/1353×3）→ `p3=false`（dbg:1318/1376）→ `q1.j` 不被覆盖 → 出口 9554B/`0ef205c1`（dbg:1326/1416）→ 肉眼模糊 3/3。机制①（arm/inject）两态均 skip、零写入（dbg:443/1311/1369），srcBigImgPath 22:01 非空但非决定量（dbg:414），hevc 缓存状态是伴随量。旧裁决「原生链自行拼对、非我方修复码」**作废**。** 判定「22:01 清晰 = 我方 P3 生效」**成立，置信度【真机实证·高】**（p3 true/false 与肉眼、与字节链逐次吻合，1 例清晰 / 3 例模糊无一例外）。

**② 修复方向。**
**推翻「搁置热修 / P-FS 写 fullpath」**：机制①两态零写入、cff4f5a5 为本图伴随常量，写 fullpath 打错靶且不泛化。重定向到**让 P3 在原图不在本地时可靠拿到 335644B 源 jpg**（HD 下载等待/回读 + 源字节兜底），使 `bigImgPath` 非空→`p3=true`→`q1.j` 被原图覆盖。不改 `origin-by-md5` 分桶规则（已证文件真不在，非规则问题）、不依赖 hit/`UploadHitCacheType`。本轮**不改码**，仅定方向。

**③ 最小只读验证方案。**
**交后续 lane 执行（本次不执行）**：步 0 先持续落盘 dbg+logcat（22:01 出口 md5 曾因缓冲刷丢、logcat 漏 FORWARD 行）；步 1 固定同图/同入口/同 target、**确保本地无源 jpg** 连发 ≥3 次（预期模糊、`p3=false`、`md5=0ef205c1`）；步 2 仅翻转「源 jpg 本地存在性」（使 `image2/21/6b/216b…jpg`=335644B 落盘）再发 ≥2 次（预期 `origin-by-md5 path=…size=335644`、`p3=true`、清晰）——这是决定性对照；步 3 探针 off + stat 回读。**判据**：肉眼清晰/模糊终审，文件字节（`fmd5pre`/`size`/出口 `md5`）作旁证，`p3` 为机制旁证不替代肉眼，`hit`/`UploadHitCacheType` 只记录不裁决。若步 2 源 jpg 在仍模糊则方向再修；若随源 jpg 存在性翻转且 `p3` 同步翻转，坐实 P3 原图获取为修复靶点。

---

> **交付声明**：Q1′~Q4′ + 三件套全部落盘，`[DONE Q1]` / `[DONE Q2]` / `[DONE Q3]` / `[DONE Q4]` / `[DONE ALL]` 均为真（非骨架预置假标记）。全程只读推理，未落码、未跑 gradle、未碰真机 adb 开关。未推翻 forensics 任何受保护原始事实（3 次模块转发、肉眼模糊、p3 true/false、c2c `fbytes=9554`/`fmd5pre=0ef205c1`、x0cb `md5=0ef205c1`）；对 forensics §1.3「22:01 big=为空」的表述基于 dbg:414 作了证据纠正并明确标注、不属受保护事实。所有【推断】未写成【已证】；U4（22:01 出口 md5）、U5（22:01 fullpath 指向）保留 UNKNOWN，U9 部分闭合，U6 升级为【真机实证·高】；「源 jpg 22:01 写入时刻 / 22:24 消失原因」如实标【UNKNOWN 未闭合】。判据对用户肉眼/文件字节，未用 `hit`（本轮 0↔3 翻转）或 `UploadHitCacheType` 作判据。唯一写文件 = 本文档。

[DONE ALL]
