# R86c 泛化判据设计裁决（fresh-eyes 独立 Pro）

> 角色：`r86c-generalize-design`，fresh-eyes 独立 Pro 设计裁决
> 工作根：`C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`
> 档位纪律：【真机实证·高|中】/【源码实证·高】/【dex 实证·高】/【推断·高|中|低】/【UNKNOWN】；【推断】绝不写成【已证】。
> 判据纪律：只对肉眼 / 文件字节裁决；`hit`/`UploadHitCacheType` 只记录不裁决。
> 硬约束：只读；唯一可写文件 = 本文件；不碰源码 / gradle / 单测 / adb / 装机 / git。
> 目标：找到「对任意新图都能把 field_fullpath 指向清晰字节且 fail-closed」的可实现判据，或诚实判不可行；**不为 GO 放宽 fail-closed**。

状态进度：
- [x] Q1 cff4f5a5 语义与实验1清晰态 —— `[DONE Q1]`
- [x] Q2 模糊态→清晰 hevc 对应信号 —— `[DONE Q2]`
- [x] Q3 新判据设计 / 判不可行 —— `[DONE Q3]`
- [x] Q4 go/no-go + 最小下一步 —— `[DONE Q4]`
- [x] 三件套
- [x] 真 `[DONE ALL]`

---

## Q1 —— cff4f5a5 到底是什么？

### Q1-a 一句话结论

**`cff4f5a5` 是「本次上传 task 的 `field_fullpath` 所指向的 `.jpg_hevc` 文件的【字节内容 MD5 前 8】」，它同时等于服务端回包 `C2CUploadResult.filemd5` 与出口消息字段 `field_filemd5` 的前 8 —— 二者同源，不是两个不同的 md5。它不是普遍「清晰态特征」，而是历史 216b 单图的 hevc 内容指纹；实验1 的清晰态是另一值 `484bc088`。目标态判据与模糊门禁都必须重设，不止模糊门禁。**【真机实证·高 + 源码实证·高】

### Q1-b 逐行亲核证据（原始日志）

探针字段语义（源码实证）：
- `StockCdnProbe.kt:442-446`：`path = task.field_fullpath`；`fullpathLen = path.length`；`pathTail = path.takeLast(12)`。
- `StockCdnProbe.kt:470-477`：`fexists/fbytes` = `java.io.File(path).exists()/length()`。
- `StockCdnProbe.kt:478-491`：`fmd5pre` = 对 `File(path).readBytes()` 算 MD5 取前 4 字节 → 8 hex。
- `StockCdnProbe.kt:402-403`：`x0cb md5` = `C2CUploadResult.filemd5`.take(8)（`md5Len=32` 为完整 32hex）。
- `ForwardCdnFieldHook.kt:260-271`：`md5PrefixOfFile` 同口径（文件字节 MD5 前 8）。

实验2（`_r86c_repro216b_raw.txt`，图 md5 `68b471c3bc…`）：
- `:367` 发原图步 `field_fullpath=…/image2/23/72/237236907d8059269019494c99fe8918.jpg_`（SCD-m1e 探针 `.take(120)` 截断显示，见 Q1-c）。
- `:368` `SCD2 c2c in fullpathLen=124 pathTail=918.jpg_hevc fexists=true fbytes=45522 fmd5pre=cff4f5a5`。
- `:389` `SCD2 x0cb in md5=cff4f5a5 md5Len=32 hit=3 exist=true`。
- `:397` 出口 `field_filemd5=cff4f5a52813d02be816367d4c58a8be`。
- ⇒ 同一图：`fmd5pre=cff4f5a5` = 回调 `md5=cff4f5a5` = 出口 `filemd5` 前 8。三值同源。

实验1（`_r86c_cond3_raw.txt`，图 md5 `f01965025d…`）：
- `:745` 同帧 `j2R after paths=[e=90ecbcc4f8462de2b56a413f206a9ad8.jpg f=… g=THUMBNAIL_DIRPATH://th_f01965025d…]`：`e` 是 `.jpg`（源图记录路径），但 c2c 上传的 `field_fullpath` 是 `.jpg_hevc` —— **同帧内 `.jpg` 源记录与 `.jpg_hevc` 上传字节并存、形态不同**（team-lead 主会话交叉核线索，已亲核采纳）。
- `:751` 发原图步 `field_fullpath=…/image2/90/ec/90ecbcc4f8462de2b56a413f206a9ad8.jpg_`（同样 `.take(120)` 截断）。
- `:752` `SCD2 c2c in fullpathLen=124 pathTail=ad8.jpg_hevc fexists=true fbytes=148929 fmd5pre=484bc088`。
- `:770` `x0cb in md5=484bc088 md5Len=32 hit=3 exist=true`。
- `:773` 出口 `field_filemd5=484bc08858403e7b647b9568b68be29f`。
- ⇒ 实验1 清晰态 = `148929B/484bc088`，**不是** `45522/cff4f5a5`。形态与实验2 同类（都是 `.jpg_hevc`），但字节/内容 MD5 完全不同。【源码实证·高 + 真机实证·高】

### Q1-c 「`.jpg_` 还是 `.jpg_hevc`？」—— 决定性消歧

`pathTail=ad8.jpg_hevc` 是 `field_fullpath.takeLast(12)`（`StockCdnProbe.kt:446`）。校验：
- 120 字符 `.jpg_` 路径尾 12 = `06a9ad8.jpg_`（不含 `_hevc`）；
- 124 字符 `.jpg_hevc` 路径尾 12 = `ad8.jpg_hevc`，与日志逐字一致，且 `fullpathLen=124` 吻合。

⇒ **探针读到的 `field_fullpath` 本就是 `.jpg_hevc` 文件（124 字符）**；SCD-m1e 日志里显示成 `.jpg_` 是 `StockCdnProbe.kt:166` 的 `.take(120)` 把末尾 `hevc` 4 字符截掉所致（源码实证·高），不是另一个文件、也不是中间被 inject 改写（全文无 `injected` 日志，`grep` 零命中）。

**判定**：实验1 **确实落了清晰态 hevc** —— `…/image2/90/ec/90ecbcc4f8462de2b56a413f206a9ad8.jpg_hevc`，148929B，内容 MD5 前8=`484bc088`，出口 `field_filemd5=484bc088…`。它是「发原图那刻」生成的 hevc 伴生文件，**不是原图 jpg 的 md5**（`field_filemd5` 由该文件字节算出，见 Q1-b 链条）。「发了原图但本地是另一形态」的假设**不成立**。【真机实证·高】

### Q1-d 为何实验1 是 484bc088 而非 cff4f5a5

两张图身份不同：实验1 源图 md5 `f01965025d…`（cond3:803 `pick=th_f01965025dd11c6774aafd8936c72e24`），实验2 源图 md5 `68b471c3bc…`（repro:425）。hevc 编码内容随源图变化，故清晰态 hevc 的体积/MD5 随之变化：
| 实验 | 源图 md5 | 清晰态 hevc 体积 | 清晰态 hevc 内容 MD5 前8 |
|---|---|---|---|
| 实验1 | `f01965025d…` | 148929 | `484bc088` |
| 实验2 | `68b471c3bc…` | 45522 | `cff4f5a5` |
| 历史 216b | `216b1f7c…` | 45522 | `cff4f5a5` |

两次都走「发原图 → 同方法 before 探针读到 `.jpg_hevc` → `fmd5pre=回调md5=出口filemd5前8`」同一路径（cond3:752/770/773、repro:368/389/397）。⇒ 差异根因是**不同图**，不是不同 hevc 生成路径。【推断·高：路径同构由逐行日志支撑；「生成路径完全相同」未直接 hook 编码器，故不写成已证】

### Q1-e 对历史文档的交叉核对

- `_r86c_q4b_final_verdict.md:17-20/42/56-57`：A 清晰 = 45522B/`cff4f5a5`、B 模糊 = 9554B/`0ef205c1`，且「fmd5pre = 回调 md5 = 出口 filemd5 前8」——本轮实验2 完全复现，实验1 复现机制但**换了值**。
- `_r86c_clearness_reverdict.md:116/184` 早已自标「`45522L`/`cff4f5a5` 是本图热修常量、换图必失效【UNKNOWN】」；本轮实验1 把该 UNKNOWN **升级为真机证伪**。
- `_r86c_stability_reverdict.md:89/128`：cff4f5a5 是 P3 命中后的伴随量、本图常量不泛化——与本节同向。

### Q1-f 档位与定论

| 结论 | 档位 | 来源 |
|---|---|---|
| cff4f5a5 = hevc 文件内容 MD5 前8，且 = 回调 md5/出口 filemd5 前8（同源） | 【真机实证·高 + 源码实证·高】 | cond3:752/770/773；repro:368/389/397；StockCdnProbe.kt:446/470-491/402-403 |
| `pathTail=...jpg_hevc` ⇒ 探针读的是 `.jpg_hevc`；`.jpg_` 是 take(120) 截断假象 | 【源码实证·高 + 真机实证·高】 | StockCdnProbe.kt:166/446；cond3:751-752 |
| 实验1 落了清晰态 hevc（148929B/484bc088），非原图 jpg md5 | 【真机实证·高】 | cond3:752/770/773 |
| cff4f5a5/45522 与 0ef205c1/9554 均为 216b 单图指纹，非普适判据 | 【真机实证·高】 | 本节表 + clearness_reverdict:116/184 |
| 差异根因是不同图（非不同生成路径） | 【推断·高】 | cond3:803 vs repro:425 + 两帧链条同构 |

**明确裁决「目标态固定值判据能否继续用」——不能。** `45522/cff4f5a5` 与模糊门禁 `9554/0ef205c1` 同为历史 216b 单图指纹；两轮实验里发原图清晰态分别是 `148929/484bc088` 与 `45522/cff4f5a5`，形态同为 `.jpg_hevc` 但字节全不同。**目标态判据若留在代码里（REF_MD5_PRE=cff4f5a5 / TARGET_LEN=45522，`ForwardCdnFieldHook.kt:63-65`），对新图必然 0 命中**——与模糊门禁失效是同一个根因，不能只撤模糊门禁而保留目标态。两个门禁都必须换成与具体图无关的泛化（相对）信号。

**主会话交叉核实纳入**：team-lead 独立核到 `:745 j2R e=.jpg` 与 `:752 c2c fullpath=.jpg_hevc` 同帧并存，进一步坐实「发原图那步确实生成并用 148929B/484bc088 的 `.jpg_hevc` 上传」，与本裁决同向，已写入 Q1-b。

`[DONE Q1]`

---

## Q2 —— 模糊态与清晰 hevc 的可靠对应信号

### Q2-a 一句话结论

**真机字段里唯一稳定把本次转发与同根清晰 hevc 关联的信号是「同一个 `/MicroMsg/<32hex>/` 数据根」；但它只是必要非充分（同根是多图共享，枚举必然多候选）。源图 md5 / r0.e/f/g / g9c 在模块转发 `m1.e before` 那帧稳定为空或不可得；basename 与体积/MD5 都逐图变化；mtime 邻近是弱信号。一个此前被低估的强信号是「转发帧 `HDIMG fired img=<basename>` 的 basename 恰等于发原图时清晰 hevc 的 basename」（两例成立、且早于 m1.e）——但机制未经 dex 证明，只能【推断·中】。 ⇒ 在「只写 field_fullpath 一个字段」约束下，没有任何一条信号能单独给出「唯一且已证正确」的普适映射。**【真机实证·高 + 源码实证·高 + 推断·中】

### Q2-b 逐条评估（逐条档位）

| # | 信号 | 在转发帧是否稳定可得 | 关联强度 | 误判场景 | 档位 |
|---|---|---|---|---|---|
| 1 | 同数据根 `/MicroMsg/<32hex>/` 下枚举 `image2/**/*.jpg_hevc` | 是：`field_fullpath` 自带根（cond3:423、repro:287 均为 `89476c7fc82a9f977bc8a901d68b7d37`） | 必要不充分：根是整个账号共享，历史多图同根；两实验清晰 hevc 都在同根但不同桶（repro:368 `23/72` vs :470 `90/c8`） | 同根存在多张图的 hevc → 多候选 → 歧义 | 【真机实证·高（存在性/可得性）；推断·高（不充分）】 |
| 2a | 源图 md5（消息 xml `$md5` / `pick=th_<md5>hd`） | 转发帧 `task field md5=` 为空（cond3:422、repro:286：`md5= md5Len=0`）；`SCD2 g9c` 转发帧 `hasBig=false hasAes=false hasMd5=false md5Pre=NA`（cond3:825-826/410、repro:466-467） | 源图 md5 是**源图身份**，不是目标 hevc 内容 md5（历史 `_r86c_q4b_final_verdict.md:34` 已证用 xml `$md5` 参照必 0 命中） | 用它做参照 → 必然 0 命中或命中错图 | 【真机实证·高（不可得 + 不等价）】 |
| 2b | `j2.R` 的 `r0.e`/`r0.f`/`r0.g` | `arm()` 可读（`ForwardCdnFieldHook.kt:304/323-327`），但本两轮日志**无 R86C R0 行**（probeEnabled 默认关）；历史 `clearness_reverdict:178` 证 `r0.e=216b1f7c…jpg` 可得 | 弱：`z3(r0.e/f/g/j)` 四路历史探针 `hitFields=none`（`clearness_reverdict` U 表），r0.e 指源图身份、非清晰 hevc 路径 | 拿不到清晰 hevc 路径；四路 z3 全不命中 | 【UNKNOWN（本两轮未开探针）；源码实证·高（字段可读）；推断·中（可用性）】 |
| 2c | g9c `cdnbigimgurl`/`aeskey`/`md5`（`SCD2 g9c in hasBig/hasAes/hasMd5`） | 转发帧 `hasBig=false bigLen=-1 hasAes=false hasMd5=false md5Pre=NA`（cond3:825-826、repro:466-467）；发原图帧才有 `hasBig=true md5Pre=...` | 弱：即使发原图帧有值，也属源图/服务端字段，不含本地清晰 hevc 路径 | 转发帧恒空 → 无法做本地候选准入 | 【真机实证·高（转发帧不可得）】 |
| 3 | **转发帧 `HDIMG fired img=<basename>`** | **早于 m1.e**：cond3:826 `img=90ecbcc4f8462de2b56a413f206a9ad8.jpg` at 18:57:16.264 < m1.e :423；repro:455 `img=237236907d8059269019494c99fe8918.jpg` at 19:08:19.055 < m1.e :285 | **强（两例）**：该 basename 恰等于发原图时清晰 hevc 的 basename（cond3:826 ↔ :752 `90ec...ad8`；repro:455 ↔ :367 `23723690...918`），而模糊 hevc basename 不同（cond3:424 `1753107c...`、repro:288 `90c81640...`） | 若 HDIMG fired 异步晚于 m1.e → 捕获不到；若源图是接收图/非本端发原图 → 可能无同名 hevc；HDIMG img 指向别图 → 写错图 | 【真机实证·高（两例 basename 相等 + 时序）；推断·中（「HDIMG img 必为清晰 hevc basename」的机制未经 dex 证明）】 |
| 4 | basename ↔ hevc basename 直接关系 | 转发帧 field_fullpath basename（`90c81640…064` / `1753107c…9c1`）≠ 清晰 hevc basename | 弱：但清晰 hevc basename = 上项 HDIMG img basename；P3 origin basename 亦是其尾缀（repro:404 `59269019494c99fe8918.jpg` ⊂ `23723690…918`；cond3:832 `2de2b56a413f206a9ad8.jpg` ⊂ `90ecbcc4…ad8`），**但 P3 origin 在 m1.e 之后**（repro:404>.288、cond3:832>.424）⇒ 写点不可得 | HDIMG/P3 来源不同步时错配 | 【真机实证·高】 |
| 5 | 时间戳 / mtime 邻近性 | 清晰 hevc 是「发原图那刻」落盘瞬态文件：实验2 发原图 19:08:10 / 转发 19:08:19 相隔 9s；实验1 18:57:00 / 18:57:16 相隔 16s | 弱-中：可作「最近 N 秒内落盘」筛选；未直接 stat hevc mtime（日志只有探针 `t=System.currentTimeMillis()`） | 同根近期另发过别的原图 → 多个「近期 hevc」→ 歧义；或清晰 hevc 已被清理 → 0 候选 | 【真机实证·中（仅时间差，无 hevc mtime 实测）；推断·高（误判风险）】 |
| 6 | 体积显著更大（clear ≫ blur） | 实验2 clear 45522 vs blur 13935（3.3×）；实验1 clear 148929 vs blur 1709（87×） | 弱：倍数逐图差异巨大，且同根其他图 hevc 可能更大 | 同根另一张近期大图 hevc 也「显著更大」→ 误选；或清晰 hevc 恰好较小 → 漏选 | 【真机实证·高（两例数值）；推断·高（不可作普适阈值）】 |
| 7 | `field_thumbfilemd5` 恒定（同图两态相同） | 转发帧 task 字段空；只在 f2C2/x0cb **回调后**出现：cond3:773 与 :862 均 `6d59226f…`；repro:397 与 :500 均 `910133fc…` | 中：能证明「同图」，但不能指明清晰 hevc 路径；且回调晚于 `m1.e before` | 写时刻不可得；作参照仍无法定位候选 | 【真机实证·高（恒定 + 时序晚）】 |

### Q2-c 关键约束复核

硬约束：**只写 `task.field_fullpath` 一个字段**，不新增 hook、不改 P3/q1.j/DB/r0.e。由此：
- 信号 3（HDIMG img basename）虽强，但要复用已挂载的 AutoHdImg hook 把 img 捕获进模块内存（`AutoHdImg.kt` 已在运行，`HDIMG fired` 日志存在，`FileSender.kt:163` 的 `markTarget` 已是模块内调用点）——需在模块内存新增状态、并依赖 HDIMG fired 严格早于 m1.e。这不写宿主字段、不新增 hook，**但要把 AutoHdImg 的捕获接入 pending 链**，属于规格扩展而非现成能力。
- 信号 4 的 P3 origin、信号 2a/2c 在写点恒空或更晚——都不能进判据。
- 信号 5/6 都是相对/弱信号，单独使用在多图场景必然歧义 → fail-closed 不写（安全但漏修）。

### Q2-d 档位与定论

| 结论 | 档位 | 来源 |
|---|---|---|
| 同数据根是唯一稳定关联信号，但必要不充分 | 【真机实证·高 + 推断·高】 | cond3:423/752 vs :424；repro:287/368 vs :288/367 |
| HDIMG img basename = 清晰 hevc basename 且早于 m1.e（两例） | 【真机实证·高（现象）；推断·中（机制）】 | cond3:826↔752、repro:455↔367 |
| 源图 md5 / r0.e/f/g / g9c 在转发帧不可得或不等价 | 【真机实证·高 + UNKNOWN(r0 本两轮)】 | cond3:422/825-826、repro:286/466-467；clearness_reverdict:178 |
| mtime 邻近、体积显著更大均为弱信号，有误判/漏判场景 | 【真机实证·中 + 推断·高】 | cond3:752/424、repro:368/288 |
| 约束内没有「唯一且已证正确」的普适映射 | 【推断·中-高】 | 本节全表 + Q2-c |

`[DONE Q2]`

---

## Q3 —— 设计新判据（或判不可行）

### Q3-a 两个候选判据（均去掉固定字节/MD5）

> 共同前提：`hit`/`UploadHitCacheType` 只记录不裁决；`hit=3`（cond3:770）/ `hit=0`（cond3:855、repro:495）翻转已证非决定量。
>
> **绝对值禁用清单（team-lead fail-closed 提醒，已纳入）**：新判据**不得**出现 `9554`/`1709`/`13935`（三态模糊态实测体积，cond3:217/repro:470/历史9554）、`0ef205c1`/`547fe3ee`/`0a1bc04d`（三态模糊态 MD5）、`45522`/`148929`/`cff4f5a5`/`484bc088`（清晰态实测）中的任何一个作为准入常量。它们只能作 note 里的历史样本佐证，绝不进代码。判据一律用「当前帧与同根候选的相对关系」（更大 / 不同 md5 / basename 匹配），不写死任何具体字节数或内容 MD5。

**候选 A：源图 basename 精确重建（最强，但依赖新捕获链）**
- **进入条件**：`pendingMap[field_talker]` 命中 + TTL 内 + 反射可读 `field_fullpath` + 从已挂载 AutoHdImg 的 `HDIMG fired img=<basename>` 捕获到 `srcBase`（模块内存，`markTarget` 时按 wxid 关联）。
- **模糊态识别**：当前 `field_fullpath` 的 basename ≠ `srcBase`（转发帧 hevc 是新 task 名，`1753107c…`/`90c81640…`）；若相等说明已是清晰 hevc 形态 → 不写。
- **目标构造 / 返回条件**：同数据根下枚举 `image2/**/*.jpg_hevc`，取 basename == `srcBase` 的文件，**命中数必须 ==1** 才写其绝对路径。
- **fail-closed 边界**：`srcBase` 捕获不到 / 枚举 0 或 >1 / 反射异常 / TTL 超时 / basename 已相等 → 一律不写，不 remove 他人条目。
- **实现落点**：仍 `m1.e before`（`ForwardCdnFieldHook.inject`）；但需把 AutoHdImg 捕获的 `srcBase` 接入 `pendingMap` 的 value（模块内存，不写宿主字段、不新增 hook）。
- **相对旧判据改了什么**：删掉 `BLUR_LEN=9554`/`BLUR_MD5_PRE=0ef205c1`/`TARGET_LEN=45522`/`REF_MD5_PRE=cff4f5a5` 全部固定值，改 basename 精确匹配；不再要求「原文件 9554B」这道门禁。
- **误判场景**：① HDIMG fired 异步晚于 m1.e → 捕获不到 → 不写（安全漏修）；② 清晰 hevc 被 LRU 清理 → 0 候选 → 不写；③ HDIMG img 指向别图/接收图无同名 hevc → 写到错图 hevc（视觉内容替换 + 对端 hevc 解码兼容 UNKNOWN）；④ 同 basename 跨目录不可能（root keyed）。

**候选 B：同根 + 近期 + 显著更大 + 唯一（纯现场枚举，最贴合「不新增 hook」）**
- **进入条件**：`pendingMap[field_talker]` 命中 + TTL 内 + `field_fullpath` 可读 + `pathTail` 以 `.jpg_hevc` 结尾（Q1-c 已证写点本就是 hevc）。
- **模糊态识别**：当前 field_fullpath 指向 hevc，且同根存在「mtime 更近、体积更大」的其它 hevc → 判为模糊态。
- **目标构造 / 返回条件**：枚举同根 `image2/**/*.jpg_hevc`（上限 `MAX_SCAN_FILES`），候选准入：`mtime ∈ [now-W, now]`（W≈90s）、`len > 当前 hevc len`、**不固定字节阈值**、内容 md5 前8 ≠ 当前 hevc；**命中数 ==1** 才写。
- **fail-closed 边界**：候选 0 或 >1 / 扫描超限 / 反射异常 / TTL 超时 → 不写。
- **实现落点**：`m1.e before`，纯现场枚举，不新增 hook、不接入任何其它 hook 内存——最贴合硬约束。
- **相对旧判据改了什么**：全部固定值换成相对比较（更大 + 近期 + 唯一）。
- **误判场景**：① 同根近期发过别的图 → 多候选 → fail-closed 不写（漏修）；② 清晰 hevc 已清理 → 0 候选；③ 当前已是清晰态但同根有更大 hevc → 误写错图；④ 转发延迟久 → 超窗漏修。

### Q3-b 过 B2/R79 竞争理论与「视觉替换 + 对端解码兼容」风险

- **B2/R79 竞争理论**：`_r79_stock_byte_source.md:24/62/187` 证 stock 清晰的决定量可能是「消息 xml 的 cdnbigimgurl/aeskey/md5 + 服务端秒传命中（`"support second !!"`）」，本地 36B 占位也能清晰——即**换本地 fullpath 字节未必改变出口身份**。本两轮 `hit=3`（cond3:770、repro:389）正是秒传命中特征。两个候选都只换 field_fullpath 指向的本地字节，不动 fileId/aesKey/xml，**因此都是「缓解」而非「解决」**：若决定量真在服务端秒传身份，换字节后出口 `filemd5` 可能仍不变。决定性缺口＝**未做「强制把模糊 field_fullpath 改成同根清晰 hevc → 出口 filemd5 是否翻转、对端肉眼是否清晰」的 A/B 干预实验**；目前只有「发原图态本地 hevc=C 且清晰」的观测相关，无干预因果。【推断·中】
- **视觉相同内容替换**：候选 A/B 一旦误选同根别图 hevc，会把不同视觉内容的字节喂给上传 → 对端显示错图，比「模糊」更严重。两例里清晰/模糊视觉上同图，但那是「恰好同源」，判据本身不能保证。【推断·高（误选后果）】
- **对端 hevc 解码兼容**：换 hevc 字节后对端能否稳定解码、是否会因格式/参数差异回退或失败 —— 本两轮无对端解码观测，**【UNKNOWN】**。现有清晰态本身是 hevc，故风险与现状同量级，但「误选别图 hevc」会放大。

### Q3-c 档位与定论

| 结论 | 档位 | 来源 |
|---|---|---|
| 候选 A（basename 精确重建）可实现但依赖未证机制与时序 | 【推断·中】 | Q3-a + Q2-b#3 |
| 候选 B（同根+近期+更大+唯一）最贴合约束但多图必歧义 | 【推断·中-高】 | Q3-a + Q2-b#5/#6 |
| 两候选均只换本地字节，过不了 B2/R79 竞争理论 | 【推断·中】 | Q3-b + r79:24/62 |
| 视觉替换/对端解码兼容风险仍在 | 【推断·高 / UNKNOWN】 | Q3-b |

`[DONE Q3]`

---

## Q4 —— go / no-go + 最小下一步

### Q4-a 裁决

**NO-GO（对「在 only-field_fullpath-write + m1.e before 约束下，可落码、可真机验证的普适判据」）。confidence = 中-高（0.75）。**

理由（三条硬伤，均为「未证」而非「已证可行」）：
1. **无唯一身份信号**：Q2 已证目标态/模糊态都逐图变化（Q1 真机证伪硬编码），m1.e before 帧里能唯一指向「本次源图对应清晰 hevc」的信号要么不可得（源 md5/g9c/r0），要么只是必要非充分的「同根」。唯一强的 HDIMG basename 信号是【推断·中】，机制未证。
2. **fail-closed 与安全冲突**：唯一贴合「不新增 hook」的候选 B 在多图场景必然多候选 → 按 fail-closed 不写（漏修）；一旦放宽到「取最大/取最新」就违反「不为 GO 放宽 fail-closed」。候选 A 虽精确，但要接入 AutoHdImg 内存捕获（规格扩展）且依赖时序。
3. **竞争理论未排除**：B2/R79 服务端秒传与本地字节两条因果链未做干预实验区分；换字节能否稳定驱动清晰没有 A/B 因果证据。

**不为 GO 放宽 fail-closed**：现有 hook 的「命中数必须 ==1、0/>1 不写」必须保留；我不会为了给 GO 把它改成「取最大/取最新」。

### Q4-b 若转方向：替代方向粗评（不在本约束内）

| 方向 | 粗评 | 依据 |
|---|---|---|
| 换 hook 点（B6/g9.c 拿 cdnbigimgurl/aeskey/md5 + checkExist 秒传建新记录） | 潜力中-高，脱离「只写 field_fullpath」约束，机制 dex 全可见 | r79:187/211-214 |
| 复刻微X（让转发消息 xml 带源图 cdnbigimgurl，从根上解决） | 潜力高，工作量最大 | `_r86c_microx_route_verdict.md` |
| 收尾归档（承认状态依赖：本地 hevc 缓存命中才清晰，现 hook 默认关 + 文档标注 UNKNOWN） | 最低风险，推荐作为本轮默认 | clearness_reverdict:87/208 |

### Q4-c 「写后清晰」处于什么档位

**【UNKNOWN / 推断·低】**。本两轮 `grep` 全文**无 `injected` 日志**（cond3/repro 零命中），即现 hook 两次都未写入 field_fullpath；历史 Q4B 的清晰是微信原生链 / 本地 hevc 缓存状态依赖所致，**不是本 hook 写入的结果**。因此「本 hook 写后清晰」从未被真机直接观测，不得写成已证。

**真机 A/B 终审必须由用户肉眼做**：任何候选落码后，唯一坐实条件＝用户对端肉眼「清晰」且 A→B→A 翻转成立；`fmd5pre`/出口 `filemd5` 只是机制旁证，`hit`/`UploadHitCacheType` 只记录不裁决。

### Q4-d 最小下一步与责任方

1. **落码前只读取证（不开闸，责任方：用户真机 + team-lead 汇总）**：转发帧记录 ① `HDIMG fired img` basename ② 清晰 hevc basename ③ 两者 mtime ④ 同根 hevc 数量；换 ≥3 张图（含「本端发原图后转发」与「接收图转发」两类）验证「HDIMG img basename == 清晰 hevc basename」是否普适。若不成立 → 候选 A 直接否。
2. **决定性干预实验（责任方：用户真机）**：强制把模糊 field_fullpath 改成候选指向的同根 hevc，读出口 `filemd5` 是否从模糊身份翻到清晰身份，并由用户肉眼判清晰。这是区分 B2/R79 竞争、坐实字节决定论的唯一手段；无此实验不落码。
3. **仅当①成立且②翻转成立**，再进入规格评审（责任方：team-lead）：候选 A 规格化（AutoHdImg 捕获接入 pending、basename 精确匹配、命中==1、TTL、fail-closed 全部保留、独立开关默认关、单测反向断言「多候选/无候选/异步晚到一律不写」）。
4. **默认动作**：若①②任一不成立 → 按 Q4-b 收尾归档方向处理，现 hook 保持默认关。

`[DONE Q4]`

---

## 三件套（交付）

① **三问一句话结论 + 档位**：
- Q1：`cff4f5a5` 是发原图那份 `.jpg_hevc` 的内容 MD5 前8（＝回调 md5＝出口 filemd5 前8，同源），是 216b 单图指纹而非清晰态普适特征；实验1 清晰态是 `484bc088/148929B`，目标态判据也必须重设。【真机实证·高 + 源码实证·高】
- Q2：唯一稳定关联是「同数据根」（必要非充分）；HDIMG basename 是两例成立的强信号但机制【推断·中】；其余字段在 m1.e before 不可得或是弱信号；约束内无「唯一且已证正确」的普适映射。【推断·中-高】
- Q3：候选 A（basename 精确重建）/ 候选 B（同根+近期+更大+唯一）均去掉固定字节/MD5，但都过不了 B2/R79 竞争理论，视觉替换与对端解码风险仍在。【推断·中】

② **go/no-go + confidence**：**NO-GO**，confidence **中-高（0.75）**。三条硬伤：无唯一身份信号、fail-closed 与安全冲突、竞争理论未排除；不为 GO 放宽 fail-closed。

③ **最小下一步与责任方**：先做「落码前只读取证（≥3 图验证 HDIMG basename 普适性）」+「决定性干预实验（强制改字段看出口 filemd5 翻转 + 肉眼清晰）」，责任方 = 用户真机 + team-lead 汇总；两者都成立才进入候选 A 规格评审，否则收尾归档、现 hook 默认关。「写后清晰」=【UNKNOWN/推断·低】，A/B 终审必须由用户肉眼做。

`[DONE ALL]`

> 交付声明：Q1~Q4 + 三件套全部落盘，真 `[DONE ALL]`（非骨架预置）。全程只读，唯一写文件 = 本文档；未改源码、未跑 gradle/单测、未碰 adb/设备、未 git commit/push。判据只对肉眼/文件字节，`hit`/`UploadHitCacheType` 只记录不裁决。所有【推断】未写成【已证】；「写后清晰」如实标 UNKNOWN。
