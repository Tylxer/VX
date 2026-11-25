# R86c 原始字节可控性 / 可行性裁决（fresh-eyes 只读取证 · Lite 降档重派）

> 本报告只读取证，回答「治本根子 = 发图那一刻本地原图 hevc 字节为何有时缺席、只剩压缩版，能否被模块可靠控制」。
> 可行性分析 ≠ 已实现 ≠ 已验证。分档严格，【推断】绝不写成【已证】。

## 纪律与边界

- 全程只读。唯一可写文件 = 本文件。禁改源码 / 单测 / 其它文档；禁跑 gradle / `_junit.sh`；禁碰 adb / 装机 / 设备 / 开关 / 让用户发图；禁 git。
- 分档：【源码实证】/【dex 实证】/【日志实证】/【推断·高|中|低】/【UNKNOWN】。每条结论带 `文件:行号` / `日志:行号` / dex `code_off` 锚点。
- 已定案结论只引用不重写；锚点见背景段。诚实边界单列，未取证标 UNKNOWN。

## 背景（已定案，仅引用）

- 决定量【真机+dex 实证·高】= 上传 task 的 `field_fullpath` 指向字节 MD5 是否 = 源图转码 hevc `cff4f5a5`（45522B）；模糊态指向 9554B / `0ef205c1` 压缩 hevc。锚点 `docs/_r86c_clearness_reverdict.md:20/:25/:26/:101`。
- G1 真机 A/B 证伪「切原生链治本」：`chat.forward_native=true` 后链真切成功、标记完好，肉眼仍模糊。锚点 `docs/_r86c_g1_ab_verdict.md`、`_r86c_g1_open.txt`。
- `no localPath` 根因【dex 实证】：`o2(imgLocalId)` 返回的 `r0`(ImgInfo2) 七个 String 字段无绝对路径；fullpath 由 `j2.O → b1.z3(r0.e/f/g/j)` 现场拼，VFS 根运行时解析。锚点 `docs/_r86c_q4b_localpath_fix.md:38/:42-49/:75`。
- 下载/分派【dex 实证】：`m1.m` 用 `task.e` 分派，`field_fullpath` 不参与 download/x0.u 分派；download 分支要求 task 是 `Ltj/m`，自建 checkExist task 是 `Ltj/k`。锚点 `docs/_r86c_d3_dex_forensics.md:54/:60/:265`。
- 坏图 xml 无 `cdnbigimgurl` 只有 mid ⇒ 原生 B6 拿不到 big URL；stock 清晰经 R79/R82 归上传链 CUploadCompleted、非 B6。锚点 `docs/_r86c_d3_dex_forensics.md:220-222/:236`。
- R83「md5 直算原图路径喂 fullpath」已提交（`8edd3b5`），泛化性未闭合（`_r86c_clearness_reverdict.md:116` U8）。

---

## Q1 原图 hevc 字节的产生 / 落盘链路

**先厘清量纲（避免跨量纲误判）**：本案有四类不同 md5，不可混比——①源 jpg 文件名/内容 md5（如 `e8b43a5e…`、335644B；`216b1f7c…` 是坏图身份）；②xml `$md5` 源图指纹（如 `ca47f83e`）；③`.jpg_hevc` 伴生文件的**字节内容 MD5 前 8**（`cff4f5a5`/45522B 清晰态、`0ef205c1`/9554B 模糊态）；④转发帧 hevc 文件名前缀（`a77`/`6ff`/`e64b3b2` 等，每次转存新条目）。决定量是③（`_r86c_q4b_forensics_raw.md:19-20/:30-31`，`_r86c_clearness_reverdict.md:20/:26`）。【真机+dex 实证·高，引自已归档】

**产生/落盘链路（fresh-eyes 归纳，分档逐环）**：

1. **清晰态 hevc 字节 = 「发原图那一刻」由微信编码器生成的 `.jpg_hevc` 伴生文件，不是源 jpg 本身**【真机实证·高，引自 `_r86c_generalize_verdict.md:58`】：实验1 `…/90ec…ad8.jpg_hevc` 148929B/`484bc088`（cond3:752/770/773）、实验2 `…/2372…918.jpg_hevc` 45522B/`cff4f5a5`（repro:368/389/397），两例同帧 `.jpg` 源记录与 `.jpg_hevc` 上传字节并存、形态不同（`:43`）。§Q1-d 证 hevc 内容随源图变化（`484bc088`↔`cff4f5a5`），是不同图不是不同生成路径【推断·高】。`pathTail=...jpg_hevc` 已消歧：探针读的本就是 124 字符 `.jpg_hevc`，`.jpg_` 是 take(120) 截断假象【源码实证·高，`:52-56`】。
2. **模块降级链里，清晰的前提是「源 jpg（335644B）本地在场」→ P3 覆盖 `q1.j`**【真机实证·高，引自 `_r86c_stability_reverdict.md:30/:37-41`】：`origin-by-md5` 命中 `path=…b1268349fd0eb3c46801.jpg size=335644`（dbg:427/444）→ `ready=true`→`p3=true`（dbg:445/448）→ `copyP3Temp`（`ForceForward.kt:2566-2606`）→ `FileSender.kt:224-229` 以 `bigImgPath` 覆盖 `q1.j` → 清晰。hevc 状态是此链的**伴随/下游结果**（`:33`），非驱动因。
3. **源 jpg 的落盘 = 宿主真下载器 DownloadImgService.c**【源码+dex 实证，引自 `_r76_p3_bytes_landing_plan.md:52/:60`】：`d3.F8()→m`、`m.c(JJILt0;I)Z`，真下载（getmsgimg CGI 109 → t1 → 落盘 image2），AutoHdImg 已用同款反射（`:60`）。模块 `prepareBigImg` 预热是「先异步 `svc.c(TYPE_HD)` 触发下载、立即同步读盘」（`_r86c_p3_keysrc.md:59`）。
4. **谁触发、何时落盘**：22:01 那次 335644B 源 jpg 的**具体写入时刻/触发者未抓到**【UNKNOWN 未闭合，`_r86c_stability_reverdict.md:74`】——最可能是此前原生转发 / CDN 秒传 / 用户查看原图把源 jpg 落到 `image2/21/6b/`，与「22:01 P-FS 证本地有 45522B hevc」同源【推断·中，同处】。
5. **何时被清理/缺席**：22:24 同路径 `miss reason=no-exist`（dbg:1295/1353×3）、`P3AdmitGate.admit` 首关 `!f.exists()`（`ForceForward.kt:121-122`）【真机+源码实证·高，`_r86c_stability_reverdict.md:59/:66`】。但**消失原因（TTL / 缓存清理 / 换记录）无法判定**【UNKNOWN，`:75`】：三次 r0 的 a3id/o2id 各异、记录 e 字段 `len=0 md5pre=NA`（dbg:1300/1358）。模块自身只删 `cacheDir/_<uuid>` 临时文件、**不碰 image2/ 源图**（`_r86c_p3_keysrc.md:97`，`_r76:177-180`）。
6. **「9554B 压缩版」不是「原图被覆盖」，而是原图缺席时上传链退回缩略图转 hevc**【源码+真机实证·高，引自 `_r86c_p3_keysrc.md:96`、`_r86c_stability_reverdict.md:46-47`】：P3 miss → `q1.j` 不被覆盖、留缩略图 → native 链拼 fullpath → 出口 fbytes=9554/`0ef205c1`。反向证据：stock 原生 B6 用 fileId(305f)+服务端秒传，出口稳定 45522B/`cff4f5a5`（`_r86c_p3_keysrc.md:93`），本地 36B 占位也能清晰（`_r79_stock_byte_source.md`，经 `_r86c_direction_verdict.md:38` 引）。

**本问一句话**：原图 hevc 字节由「发原图那刻」微信编码器生成、随源 jpg 在场而伴随出现；模块只控制「是否触发宿主下载/是否覆盖 `q1.j`」，对**字节本身的落盘时刻、清理时机均无可靠控制**，且触发者/消失原因两处 UNKNOWN。【推断·高（链路综合），逐环档位见上】

`[DONE Q1]`

## Q2 「缺席」根因分类与本场景真因

按四分类逐一裁定，证据强弱分列、不概括：

| 分类 | 内容 | 本场景证据 | 裁定 |
|---|---|---|---|
| ①本地没下载原图（xml 无 big URL 只有 mid） | 坏图 attrs 无 `cdnbigimgurl`、只有 mid（305f0201）【真机实证·高，`_r86c_d3_dex_forensics.md:220-222`，`state_final:208`】；B6 用 `r0.h` 二选一，坏图取不到 big URL（`D3:219-222`） | 但**同一串 305f 在 stock 清晰态命中原图**（`D3:226`，`lock_state1_raw.txt:388 fidPre=305f0201 hit=1 md5=ca47f83e`）；R82 四样本 fileId 逐字相同仍一清晰一模糊（`D3:226`）⇒ mid 语义**零区分力**【真机反证·高，`D3:227`】 | **部分成立但不充分**：xml 无 big URL 是事实，但「拿不到 big URL⇒模糊」被真机反证；不是本场景决定量。降为【推断·低】 |
| ②下载了被压缩版覆盖/清理 | 假设原图曾被下载又被 9554B 压缩版覆盖 / LRU 清理 | **无直接证据**：模块只删 cacheDir 临时文件、不碰 image2/（`_r86c_p3_keysrc.md:97`）；22:24 源 jpg 消失原因（TTL/清理/换记录）【UNKNOWN，`_r86c_stability_reverdict.md:75`】。且 9554B 是「原图缺席时缩略图转 hevc」而非覆盖产物（`Q1:6`） | **未证伪也未坐实**【UNKNOWN】，且机制上「覆盖」说法不成立；不采纳为真因 |
| ③字节其实在本地只是没喂进 fullpath（R83 子情形） | R83「md5 直算原图路径喂 fullpath」已提交（`8edd3b5`），针对「原图在场但没喂对」 | 两轮真机全文**无 `injected` 日志** ⇒ 现 hook 从未写入 field_fullpath【真机实证·高，`_r86c_generalize_verdict.md:203-204`】；且该判据是 216b 单图常量、换图必失效（`clearness:116` U8） | **本场景未发生**（hook 未写入）；作为机制方向【推断·中-高】但当前无效、泛化 NO-GO |
| ④其它：本地源 jpg 在场性（真正决定量） | P3 `origin-by-md5` 能否在本地找到 335644B 源 jpg → p3 true/false → 是否覆盖 `q1.j` | **真机 4 例 1:1 对应**：22:01 命中→p3=true→清晰；22:24×3 no-exist→p3=false→模糊（`_r86c_stability_reverdict.md:30/:49`，dbg:427/444/448↔1295/1318）；机制①arm/inject 两态均 skip 零写入（dbg:443/1311/1369） | **本场景真因**【真机实证·高】：决定性变量 = 源 jpg 本地在场性→P3 覆盖 `q1.j`，非 fileId、非 arm 写 fullpath。旧裁决「原生链自行拼对」已作废（`stability:117`） |

**竞争理论（未消解，诚实标注）**：B2/R79 揭示 stock 清晰决定量可能是「xml `cdnbigimgurl`/`aeskey`/`md5` + 服务端秒传 `"support second !!`"，本地 36B 占位也清晰】（`_r86c_direction_verdict.md:38`，`_r86c_generalize_verdict.md:164`）。「本地字节在场」与「服务端秒传身份」是**两条未做干预实验区分的因果链**【UNKNOWN，`generalize:164`、`direction:103`】。本场景模块转发实测倾向本地字节链（p3 与肉眼 1:1），但**不能排除**秒传链参与；Q5 升级判据必须对此设闸。

**根因分层裁决**：直接决定量=源 jpg 在场性→P3 覆盖【真机实证·高】；上游"为何有时不在场"=下载触发者/清理时机【UNKNOWN】；根子层面「发图那一刻原图字节为何缺席」=「本地源 jpg 未在场 + P3 无字节可用时回退缩略图」，其中"未在场"的机制（没下载 vs 被清）**未闭合**【UNKNOWN】。【推断·高（真因归类），竞争链 UNKNOWN】

`[DONE Q2]`

## Q3 可控性（能否保证在场 / 在场时喂对 fullpath）

严格区分两个问题，不可混为一谈：

**问题 A：模块能否「保证」发图前原图字节在场？——不能保证（物理不可达）。**
- 原图字节的落盘由宿主下载器 `DownloadImgService.c` 完成（`_r76_p3_bytes_landing_plan.md:52/:60`），触发靠 `svc.c(TYPE_HD)`；22:24 实测 `download r=false`（dbg:1294/1352，宿主未成功启动/被拒），模块无法强制宿主下载成功【真机实证·高，`_r86c_stability_reverdict.md:76`】。
- `prepareBigImg` 是「先触发下载、立即同步读盘」，**不等落盘**；真正置 ready 靠异步 listener 回调（`_r86c_p3_keysrc.md:59`）。下载成功受 CGI / 服务端 / 网络 / 缓存策略制约，多因素不在模块控制内【推断·高】。
- R76 曾设计「主动下载+等待落盘+复制临时文件」方案（A 主 / B 备，`:80-118`），但要求「禁止在发送事务里等下载」否则 ANR（`:225-229`），即同步保证在场与不 ANR 冲突；且该方案**未经真机验证**（本任务禁用 adb，无法补验）。
- 写入时刻/触发者、清理时机两处 UNKNOWN（Q1:4/5）⇒ 无法建立「必须在 T 时刻在场」的闭环控制。
- **结论**：模块可「尝试触发下载 + 有机会时命中」，**不能保证**字节在场；最多做到「在场时能利用」，做不到「缺席时造出原图」。这是**必要条件层面的不可控**【推断·高】。

**问题 B：字节在场时能否可靠定位并喂对 fullpath？——现状失效，泛化判据 NO-GO。**
- 定位障碍：`r0`(ImgInfo2) 七个 String 字段无绝对路径，fullpath 由 `j2.O→b1.z3(r0.e/f/g/j)` 现场拼、VFS 根运行时解析（`_r86c_q4b_localpath_fix.md:38/:42-49/:75`）；从 `r0` 自定位数据根是结构性失败（M1 BLOCKER，`_r86c_direction_verdict.md:63`）。
- 写点障碍：m1.e before 可反射写 `field_fullpath`（`_r86c_d3_dex_forensics.md:75-90`），但 R83 判据是单图常量（`45522L`/`cff4f5a5`），换图必 0 命中【真机证伪·高，`_r86c_generalize_verdict.md:24/:87`】；两轮实测无 `injected` 日志、hook 从未生效（`:203-204`）。
- 泛化判据 NO-GO（0.75）：三条硬伤——无唯一身份信号、fail-closed 与安全冲突、B2/R79 竞争理论未排除（`_r86c_generalize_verdict.md:185-192`）。
- 换 hook 点（B6/checkExist）：全链 Java 可达、可绕 native 死路【dex 实证·高，`_r86c_direction_verdict.md:111-124`】，但优于 0.60 置信、仅到「只读最小步+干预实验」层，核心假设「补字段后 checkExist 能否命中」仍【UNKNOWN，`:88/:180`】。
- 切原生链（G1 `chat.forward_native`）：真机 A/B 已证伪——`strategy=NATIVE`+`marked=true`+fail 0 链真切成功，肉眼仍模糊（`_r86c_g1_ab_verdict.md:26-49`）；走原生链是必要非充分条件（`:51`）。

**问题 A/B 交叉结论**：即使 B（喂对 fullpath）做到极致，也只在「字节本就在场」时有效，救不了 A 的缺席态；而 A 的缺席又不可由模块保证。两条路各自都未闭合，叠加后**模块对"发图那一刻原图字节在场"这一根子没有可靠控制权**。【推断·高】

`[DONE Q3]`

## Q4 泛化性（目标值从哪取、能否不硬编码）

**45522L/`cff4f5a5` 是本图常量、换图必失效——已真机证伪，不再是 UNKNOWN**【真机实证·高，`_r86c_generalize_verdict.md:24/:74/:87`】：实验1 清晰态 `148929/484bc088`、实验2 `45522/cff4f5a5`、历史 216b `45522/cff4f5a5`，形态同为 `.jpg_hevc` 但字节全不同（`:62-67`）。模糊态门禁 `9554/0ef205c1`、`1709`、`13935` 亦逐图变化（`:142`）。目标态判据若留在代码（`REF_MD5_PRE=cff4f5a5`/`TARGET_LEN=45522`，`ForwardCdnFieldHook.kt:63-65`）对新图必 0 命中（`:87`）。

**目标值候选来源逐一评估（能否支撑不硬编码）**：

| 来源 | 能否作判据 | 证据 |
|---|---|---|
| xml `$md5`（`ca47f83e` 等） | **不能** | 转发帧 `task field md5=` 为空（`md5Len=0`，cond3:422/repro:286）；且源图 md5 是**源图身份**、非目标 hevc 内容 md5，用它参照必 0 命中【真机实证·高，`_r86c_generalize_verdict.md:106`】 |
| `r0.e/f/g`、`j2.R` | **不能** | `z3(r0.e/f/g/j)` 四路探针 `hitFields=none`（`clearness:178`）；r0 七字段无完整路径、自定位 M1 BLOCKER（`_r86c_direction_verdict.md:63`） |
| g9c `cdnbigimgurl`/`aeskey`/`md5` | **不能** | 转发帧 `hasBig=false hasAes=false hasMd5=false md5Pre=NA`（cond3:825-826、repro:466-467）；发原图帧才有值，属服务端字段不含本地 hevc 路径（`generalize:108`） |
| 本地扫描「同数据根枚举 hevc」 | **必要非充分** | 根是整个账号共享、多图同根 → 多候选歧义【真机实证·高 + 推断·高，`generalize:105`】 |
| 转发帧 `HDIMG fired img=<basename>` | **较强但未证机制** | 两例 basename 恰等于清晰 hevc basename 且早于 m1.e（cond3:826↔752、repro:455↔367），但「HDIMG img 必为清晰 hevc basename」未经 dex 证明【推断·中，`generalize:109/127`】 |
| mtime 邻近 / 体积显著更大 | **弱信号** | 倍数逐图差异巨大（3.3×~87×）、同根他图 hevc 可能更大、clean 也可能较小【推断·高，`generalize:111-112`】 |

**裁决**：现有证据**不能支撑不硬编码的判据**。`m1.e before` 帧里唯一稳定信号是「同数据根」（必要非充分），最强的 HDIMG basename 是【推断·中】机制未证；按 fail-closed（命中数必须 ==1、0/>1 不写）必然在多图场景漏修，放宽则违背安全约束【推断·中-高，`_r86c_generalize_verdict.md:130/188-192`】。两个泛化候选（A basename 精确重建 / B 同根+近期+更大+唯一）均过不了 B2/R79 竞争理论、视觉替换与对端解码风险仍在（`:164-166/:172-175`）。**NO-GO（0.75）维持**，不为凑 GO 放宽 fail-closed（`:185/:192`）。【推断·中-高】

`[DONE Q4]`

## Q5 可行性裁决 + go/no-go

**三档裁决：②条件可行（且仅限「只读取证 + 干预实验」层），不到「落码治本」层。**

| 层 | 裁决 | 依据 |
|---|---|---|
| 根子「保证原图字节在场」 | **③不可行（模块不可靠控制）** | 问题 A 物理不可达（Q3）：下载由宿主控制、`r=false` 不可强制、触发者/清理时机 UNKNOWN、同步等待与 ANR 冲突 |
| 「在场时喂对 fullpath」的泛化判据 | **③不可行（当前证据）** | R83 单图常量真机证伪、泛化 NO-GO(0.75)、hook 从未写入（Q3-B/Q4） |
| 换 hook 点 B6/checkExist | **②条件可行，仅到只读取证+干预实验** | 全 Java 可达、对症 B2/R79，但核心「补字段后能否命中」UNKNOWN、confidence 0.60（`_r86c_direction_verdict.md:180/:184`） |
| 切原生链 G1 | **③不可行（已证伪）** | 链真切成功肉眼仍模糊（`_r86c_g1_ab_verdict.md:49`） |
| 微X复刻 | **③不可行（不优先投入）** | 字节来源加密 dex 不可复制、判据两次证伪（direction:181） |

**go 仅到何层**：只对「B6 方向只读取证最小步 B1~B4 + 一轮 B5 干预实验」给条件性 go（`_r86c_direction_verdict.md:160-168/:219`）；**不 GO 到全量落码、不宣称"改一处代码治本"**（G1:58 明确无此靶点）。本任务只读取证范围内，连 B1~B5 也未执行。

**升级/证伪条件（对**用户肉眼**，不对计数器）**：
- **升级为可行**：需同时满足——① B5 自建 checkExist 补字段后 Proxy C2 `exist=true` 且 `field_filemd5` 翻到源图身份（非 `0ef205c1`）；② 出口 filemd5 翻源图身份；③ **用户肉眼 A→B→A 翻转**（原生清晰→模块模糊→开闸清晰→回退模糊）。三者缺一不可（`_r86c_direction_verdict.md:166/:199`）。
- **证伪/回退**：B5 C2 仍 `exist=false md5Len=0` 或出口不翻、肉眼不翻转 ⇒ B6 方向 no-go、收尾归档、现 hook 保持默认关（direction:166/:184）；任一 `hit`/`UploadHitCacheType` 异常**只记录不裁决**（clearness:153）。
- **对根子的证伪口**：若某轮「源 jpg 在盘（335644B）+ p3=true」却肉眼模糊，或「源 jpg 不在盘」却清晰 ⇒ Q1/Q2 决定的在场性因果链被推翻，须另寻机制（`_r86c_stability_reverdict.md:107`）。

`[DONE Q5]`

---

## 三件套 ①生死问题一句话裁决 + 分档

**一句话裁决**：治本根子 =「发图那一刻本地源 jpg（335644B）是否在场 → P3 能否覆盖 `q1.j` → 决定喂给上传链的是清晰 hevc（45522B/`cff4f5a5`）还是缩略图转的模糊 hevc（9554B/`0ef205c1`）」；该在场性由宿主下载器控制、模块无法可靠保证，字节在场时的泛化喂对判据又被真机证伪，**故模块对此根子目前不可靠可控——②条件可行仅限只读取证+干预实验层，不 GO 到落码治本。**

分档：
- 决定量=fullpath 字节、p3 与肉眼 1:1、G1 原生链证伪、泛化常量真机证伪 =【真机实证·高，引自已归档】
- 下载/分派 dex 链、r0 无绝对路径、B6 全链 Java 可达 =【dex 实证·高，引自已归档】
- 「模块对根子无可靠控制权」「A/B 两问题叠加不可控」=【推断·高】（Q3 综合归纳）
- 「字节在场仍可能受服务端秒传链主导」=【UNKNOWN】（B2/R79 竞争未做干预实验）
- 触发者、清理时机、22:01 出口 md5 =【UNKNOWN 未闭合】

## 三件套 ②证伪 / 升级条件

见 Q5「升级/证伪条件」表。核心：升级唯一靠**用户肉眼 A→B→A 三条件同时成立** + 出口 filemd5 翻源图身份 + B5 C2 命中；证伪靠 B5 C2 仍零命中 / 肉眼不翻转 / 在场性因果链反例。计数器只记录不裁决。

## 三件套 ③最小下一步 + 责任方

1. **首选（B6 方向只读取证）**：执行 B1~B4——现有双探针补字段只读 + Proxy C2 加打 Ltj/f 类名全字段 + 同时刻 stock 对照 + 源 xml cdnbigimgurl 核对（排服务端过期/读错对象）。责任方：用户真机 + team-lead 汇总；本子代理不碰设备。
2. **干预实验 B5**：基线站住后，由实现 lane 另起任务补齐三字段、保留 305f、自建 checkExist 下发、Proxy C2 只读命中。门槛 = C2 命中 + 出口 filemd5 翻源图身份 + 用户肉眼 A→B→A 三者同时成立。责任方：实现 lane + 用户肉眼终审。
3. **备选微X方向**：M1~M2（≥3 图验证 HDIMG basename == 清晰 hevc basename 且早于 m1.e + 终局 z3(r0.e) 可观测），不成立即否。责任方：用户真机 + team-lead。
4. **默认出口**：任一步失败 → 收尾归档、现 hook 保持默认关。责任方：team-lead。

`[DONE ALL]`

---

> 交付声明：Q1~Q5 + 三件套全部落盘，`[DONE Qn]` 与 `[DONE ALL]` 均为真（非骨架预置假标记）。全程只读取证：未改源码/单测/其它文档，未跑 gradle/`_junit.sh`，未碰 adb/装机/设备/开关/未让用户发图，未 git。唯一写文件 = `docs/_r86c_origbytes_feasibility.md`。未推翻任何已归档结论（决定量=fullpath 字节、p3 决定量、G1 证伪、泛化 NO-GO、dex 分派链等均引用不重写）。所有【推断】未写成【已证】；触发者/清理时机/22:01 出口 md5/竞争链均如实标 UNKNOWN。判据对用户肉眼/文件字节，`hit`/`UploadHitCacheType` 只记录不裁决。
