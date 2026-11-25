# R86c 最小稳定性验证取证固化（2026-11-24 22:24~22:27）

> 性质 = **原始事实固化**，只记录真机日志与来源行号，**不含根因裁决 / 不改写既有裁决文档**。
> 数据源两份，交叉验证后结论一致：
> - logcat 落盘：`wxgroup/_r86c_stability_full.txt`（12695 行，22:24:34~22:25:35）
> - **模块落盘日志（可靠源）**：`wxgroup/_r86c_stability_dbg.txt`（1753 行，19:47~22:31，pull 自 `/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt`）
> - ⚠️ logcat **漏掉** `FORWARD CDN_FIELD` / `FileSender` / `R0ID` 全部行（LogX.kt 类注释已警告「同一进程某些 Log.i 进不了 logcat 缓冲，所有日志同时落盘」）。**必须以落盘日志为准**，否则会误判成「非模块转发」。

## 0. 用户肉眼终审
- 22:24:52 用户「发完了」；本次共 **3 次**模块转发（22:24:39 / 22:24:46 / 22:24:56 附近）。
- AskUserQuestion 确认：入口 = **确实是「转发（模块）」**；肉眼 = **模糊**（与字节判据一致）。
- 探针状态回读：开启时 `R86C OP stat fs=true r0=true` + `SCD STATUS enabled=true ok=12 fail=0`；关闭后 `fs=false r0=false` + `enabled=false`，现场已恢复。

## 1. 决定性对照：22:01（清晰） vs 22:24（模糊）

### 1.1 同一张图（U2 已闭合，不再质疑）
- 两次 `r0.e` 逐字相同 = `216b1f7ccd59b1268349fd0eb3c46801.jpg`（坏图 216b）。
- 均经「转发（模块）」入口，`toUser=filehelper`。

### 1.2 P3 字节落盘决策（**新发现的关键变量**）
| 项 | 22:01 清晰 | 22:24 模糊（39s / 46s 两次同） |
|---|---|---|
| `FORWARD P3 download fired` | `r=true`（dbg:426） | `r=false`（dbg:1294 / 1352） |
| `origin-by-md5` | **`path=b1268349fd0eb3c46801.jpg size=335644` 找到原图**（dbg:427 / 444） | **`miss reason=no-exist` 原图找不到**（dbg:1295 / 1353） |
| `bigimg ready` | 先 false（dbg:428）后 **`ready=true localId=883190 msgId=19733550 cb=l0`**（dbg:445） | `ready=false path=cd59b1268349fd0eb3c46801 fresh=false`（dbg:1296 / 1354） |
| `copyP3Temp` | 成功 | **`copy skip: reason=th-prefix name=th_216b1f7ccd…`**（dbg:1297 / 1355，只有缩略图） |
| `FORWARD resend-file` | **`p3=true`**（dbg:448） | **`p3=false`**（dbg:1318 / 1376） |
| q1.j 写入 | bigImgPath 覆盖 q1.j 为 335644B 源 jpg（FileSender.kt:224-229） | 无覆盖，q1.j 留缩略图路径（FileSender.kt:187） |

### 1.3 我方两套写机制，必须区分（**推翻此前"非我方修复码所致"表述**）
- **机制①** `ForwardCdnFieldHook.arm/inject`（写 `field_fullpath`/`fileId`/`aesKey`）：本次确 `arm skip reason=no localPath`（dbg:1311 / 1369），**没写** → 此套确实未生效。
- **机制②** `ForceForward` P3 bigImgPath（FileSender 第 6 参覆盖 `q1.j`，开关 `Keys.FORWARD_P3_BIGIMG`=`chat.forward_p3_bigimg` 默认 true, Keys.kt:190/524）：22:01 `p3=true` **生效**，22:24 `p3=false` **未生效**。
- ⇒ 此前裁决「22:01 清晰 = 微信原生链自行拼对、非我方修复码」只核了机制①，**漏掉机制② P3**。
- **【主会话二次核验修正 22:3x】**：本文件原写「22:01 `ctx big=` 为空、未打此行」**错误**。真值：dbg:414（22:01:09.646）明确打出 `FORWARD ctx srcMsgId=19733550 prefix=44408694054@chatroom: big=/data/user/0/com.tencent.mm/MicroMsg/894…`（**非空**，被 take(40) 截断）；对比 22:24 两次 dbg:1284/1338 才是 `big=`（空）。⇒ Q1③ 假设的 `srcBigImgPath` 覆盖在 22:01 **确实存在**，但按源码优先级（FileSender.kt:224-229 P3 覆盖在 :218-220 srcBigImgPath 之后、后写覆盖先写），**P3 bigImgPath 优先级更高、仍是最终决定量**。此纠正由重裁子代理发现、主会话核验 dbg:414 属实。

### 1.4 fullpath → 出口字节链（本次首次拿到完整链，22:01 缺失）
- `R86C R0 verdict hitFields=none` + `retryTrue hitFields=none`（dbg:1304/1309、1362/1367）：z3 四路拼不出原图 hevc。
- `arm skip reason=no localPath`（dbg:1311/1369）⇒ forward task 的 fullpath **完全由微信原生链拼**。
- `SCD m1e task fileId= md5= onlycheckexist=false`（dbg:1324）+ `SCD m1e in task listener.f=… field_fullpath=…/image2/1d/e2/1de2aa73d1fb7f0db046d6b9fad5a95e.jpg_ field_midimgpath=null`（dbg:1325）：fileId 空。
- **`SCD2 c2c` 直读 fullpath 指向字节**（dbg:1326 / 1445 / 1526）：
  ```
  fullpathLen=124 pathTail=95e.jpg_hevc  fexists=true fbytes=9554 fmd5pre=0ef205c1  fidLen=0 enableHit=true
  fullpathLen=124 pathTail=7bf.jpg_hevc  fexists=true fbytes=9554 fmd5pre=0ef205c1  fidLen=0 enableHit=true
  ```
  ⇒ 出口 fullpath 指向 **9554B/`0ef205c1` 压缩 hevc**，非 45522B/`cff4f5a5` 原图。
- **`SCD2 x0cb` 出口 md5**（dbg:1416 / 1506 / 1592）：`md5=0ef205c1 md5Len=32`（`hit` 字段 0↔3 翻转，**不作判据**）。
- P-FS（logcat）`scanned=22 a45522=0 cff4=0 hit=0 ambiguous=false`（22:25:13.282）+（dbg:1403/1495/1568 三次 `scanned=23/22/22 a45522=0 cff4=0 hit=0`）：本地无原图 hevc。（对照：22:01 dbg:472 `scanned=22 a45522=1 cff4=1 hit=1`）

### 1.5 因果链（事实层，不含裁决）
```
本地无 335644B 源 jpg（origin-by-md5 miss no-exist）
 ⇒ P3 bigimg ready=false ⇒ p3=false ⇒ q1.j 留缩略图路径
 ⇒ arm skip no localPath（机制①未写）⇒ fullpath 由原生链拼
 ⇒ SCD2 c2c 直读 fullpath → 9554B/0ef205c1 压缩 hevc
 ⇒ x0cb 出口 md5=0ef205c1 ⇒ 肉眼模糊
```
22:01 反向：
```
本地有 335644B 源 jpg（origin-by-md5 hit）
 ⇒ P3 bigimg ready=true ⇒ p3=true ⇒ q1.j 被 bigImgPath 覆盖为原图
 ⇒ 出口清晰 ⇒ 肉眼清晰
```

## 2. 对既有裁决的影响（**需 Pro 重裁，主会话不自裁**）
1. `docs/_r86c_clearness_reverdict.md` Q1 核心结论「22:01 清晰 = 微信原生链自行拼对、非我方修复码」——**遗漏机制② P3 bigImgPath（p3=true）**。**该结论需重裁**。（注：原写「srcBigImgPath 覆盖与 ctx big= 为空矛盾」已随 §1.3 修正作废——22:01 big= 非空但优先级低于 P3，见 §1.3 二次核验修正。）
2. 此前「差异变量 = 本地 hevc 缓存状态」需**精确化**为「`origin-by-md5` 能否找到 335644B 源 jpg → P3 是否落盘 → p3 true/false」。
3. 修复方向随之改变：不再是 P-FS 写死 `cff4f5a5`/写 fullpath，而是让 **P3 在原图不在本地时能拿到原图**（触发下载 / copy），或修正 `origin-by-md5` 的查找条件。
4. `U4`（22:01 出口 md5 UNKNOWN）仍 UNKNOWN（22:01 未开 SCD 主探针，dbg 22:01 段无 c2c/x0cb），但 22:01 的 P3 证据已补上，机制链不再单靠推断。
5. `hit`（0↔3 翻转）、`UploadHitCacheType` 仍只记录不裁决（纪律不变）。

## 3. 待 Pro 裁决的问题
- Q1′：机制② P3 bigImgPath 是否才是 22:01 清晰 / 22:24 模糊的**决定性变量**？机制①、srcBigImgPath 各自排第几？
- Q2′：`origin-by-md5 miss no-exist` 的根因（文件真的不在？查找条件有误？分桶/命名规则？），是否可修、修哪。
- Q3′：是否推翻「搁置热修」改判为「修 P3 原图获取」；若是，判据与最小验证方案。
- Q4′：`docs/_r86c_clearness_reverdict.md` 哪些结论保留、哪些改写、哪些作废，逐条给定性。

> 落盘策略：本文件为取证 input，Pro 裁决产物写独立文件。全程只读取证，未改码、未装机、未动开关（探针已 off）。

---

## 4. 第2步对照验证（23:18~23:20，用户「继续」授权，唯一变量=源 jpg 本地存在性）

> 本节为新增取证事实，不改动 §1~§3 任何受保护原始记录。数据源 `wxgroup/_r86c_step1b_dbg.txt`、`wxgroup/_r86c_step2_dbg.txt`（模块落盘日志，可靠源）。

### 4.1 用户操作
- 第1步（23:18 前）：用户反馈「没有查看原图按钮」⇒ 老图 216b 在微信 UI 已无原图下载入口（【真机实证·中】，用户口述）。
- 第2步①：用户从**相册**找到「和 216b 一模一样的原图」，勾选「原图」发到**文件传输助手**。上传链铁证：23:18:00.980 `SCD2 c2c … pathTail=dc8.jpg_hevc fbytes=45522 fmd5pre=cff4f5a5`（dbg step1b:4010）；23:18:16.938 `R86C FS verdict scanned=22 a45522=1 cff4=1 hit=1`（dbg:4038）；23:18:17.970 `x0cb md5=cff4f5a5`（dbg:4055）。⇒ 该原图的 **hevc 缓存**（45522B/cff4f5a5）确实落盘。
- 第2步②：用户回群里 216b 老消息，走「转发（模块）」发文件传输助手 ×2，肉眼确认**模糊**。

### 4.2 第2步②两次模块转发（dbg step2）字节判据
| 判据 | 第1次 23:19:37 | 第2次 23:19:43 |
|---|---|---|
| 模块链铁证 | `R0ID`(:4395) + `arm skip no localPath`(:4408) + `FileSender queued`(:4414) + `resend-file ok=true`(:4415) | 同(:4459/:4472/:4478/:4479) |
| `origin-by-md5` | `miss reason=no-exist`(:4392) | `miss reason=no-exist`(:4456) |
| `P3 download fired` | `r=false`(:4391) | `r=false`(:4455) |
| `bigimg ready` | `false`(:4393) | `false`(:4457) |
| `copy skip` | `th-prefix`(:4394) | `th-prefix`(:4458) |
| `resend-file p3` | **`false`**(:4415) | **`false`**(:4479) |
| P-FS verdict | `a45522=0 cff4=0 hit=0`(:4496) | `a45522=0 cff4=0 hit=0`(:4564) |
| c2c 出口 | `fbytes=9554 fmd5pre=0ef205c1`(:4423) | 同(:4512) |
| x0cb 出口 md5 | `0ef205c1`(:4522) | `0ef205c1`(:4589) |
| 肉眼 | 模糊（用户确认） | 模糊（用户确认） |

### 4.3 决定性反例与关键区分（事实层，不下裁决）
- **对照失败**：唯一变量「源 jpg 本地存在性」**未翻转成功**。第1步制造的是 `image2/.../<md5>.jpg_hevc`（45522B/cff4f5a5，P-FS 口径），而 P3 `p3OriginFromMd5` 找的是 `image2/21/6b/216b1f7ccd59b1268349fd0eb3c46801.jpg`（335644B 源 jpg，源码 `ForceForward.kt:2510-2530`）。**两者是不同文件、不同后缀、不同字节量**；相册发原图只落了 hevc，未落 335644B 源 jpg ⇒ `origin-by-md5` 仍 `no-exist`。
- 22:01 清晰那次恰好 `origin-by-md5 path=…b1268349fd0eb3c46801.jpg size=335644`（dbg:427/444）——该源 jpg 当时在，本次对照始终没让它重现。
- 两次肉眼模糊 + 字节判据全模糊，`hit` 3/0 翻转仍只记录不裁决。
- **待 Pro 裁决**：相册「发原图」造不出 P3 要的源 jpg ⇒ 「修 P3 原图获取」方向在真实用户操作下是否可行？P3 依赖的 335644B 源 jpg 是否本就是不可控/偶发状态？修复靶点是否需再重定向（如改用 hevc 45522B 字节落盘 / 强制下载源图并落 P3 认得的路径 / 另择锚点）？本轮不改码。
- 探针状态：第2步后已 r86c + stockcdn 双 off 回读确认（`fs=false r0=false`、`SCD enabled=false`）。

---

## 5. V2 只读实证（23:44，用户「V2 发完」，第3轮产物 Q3 V2 门槛）

> 数据源 `wxgroup/_r86c_v2_dbg.txt`（1155 行，模块落盘可靠源）。开 `microimg`(MIO 13 hook OK) + `stockcdn`(SCD 12 hook OK) 双只读探针；用户同一张 216b 老图经「转发（模块）」发 filehelper。第3轮裁决把 V2 设为落码前置门槛：验证「`r0.e → z3(r0.e) → field_fullpath`」在我方转发链上是否对应成立。

### 5.1 三条对应行（同一次转发，23:44:54~55）
- `SCD j2R after paths=[e=de291b561b364deb702a8c0972e45c76.jpg f=null g=THUMBNAIL_DIRPATH://th_8e3e176f6b03119447a104b0d5c45ef2]`（dbg:1038，另 :1068/:1071/:1080/:1087/:1105 多次一致）。
- `MIO z3 in s0=de291b561b364deb702a8c0972e45c76.jpg isThumb=true` → `MIO z3 out path=…/image2/de/29/de291b561b364deb702a8c0972e45c76.jpg`（dbg:1043-1044，另 :1072-1073/:1091-1092/:1110-1111 一致）。
- 出口：`SCD2 c2c fbytes=9554 fmd5pre=0ef205c1`(dbg:1060)、`x0cb md5=0ef205c1`(dbg:1077)；`FORWARD resend-file ok=true p3=false`(dbg:1040)；`origin-by-md5 miss no-exist`(dbg:1001)。

### 5.2 判定
- **V2 成立【真机实证·高】**：`r0.e` 值 `de291b56….jpg` 与 `z3 in s0` **逐字一致**，`z3 out` 正是由它解析出的 `image2/de/29/de291b56….jpg` ⇒ 写 `r0.e` 确能决定 `z3 out`，第3轮「`z3(r0.e)` 终局路径在我方链上闭合」前提成立。另 dbg:1011 `z3 in=THUMBNAIL_DIRPATH://th_216b… → out=image2/21/6b/th_216b…` 亦证 z3 把字段名解析成完整路径。
- **重要限定（落码时机风险）【真机实证·高 + 推断·中】**：此时 `r0.e=de291b56….jpg`（新 32 位 md5）、`j2R localIds=[a=886554]`（dbg:1037），均非老图 216b/883190（`FORWARD resend-file imgLocalId=883190` dbg:1040）。说明 `j2.R()` 拿到的 r0 在发送流程中已被重建/换成新记录。⇒ 落码若写 `r0.e`，时机必须精确在**新记录建立之后、读字节之前**，否则写入会被随后重建覆盖（印证第3轮 Q2.0「时机三步严格有序」）。
- **未解矛盾仍悬置**：本次 `r0.e` 指向 `de291b56….jpg`（非 36B 占位），出口仍 9554B/0ef205c1 模糊——与 `_r86_b2_forensics_plan.md:31`【真机坐实】「22:49 stock 的 r0.e=36B 占位却清晰」两条现象并存，**r0.e 指向字节量 ≠ 清晰度充分条件**仍未消解。V2 只证「链闭合」，未证「写大图字节→清晰」。
- 探针状态：V2 后 microimg + stockcdn 双 off 回读（`MIO STATUS enabled=false ok=13`、`SCD STATUS enabled=false ok=12`），现场干净。未改码/未装机/未提交。
