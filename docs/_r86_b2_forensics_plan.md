# R86 · 路线 B2（秒传引用）重新取证可执行清单（Pro 档 · 只读取证方案设计）

> lane：`r86-b2-plan`（Pro 档：根因推理 + Hook 链路设计 + 异步判据规划）。会话临时规则：**model = hy4flash-free，该规则不写入任何文件**。
> 红线：只读；**不改源码、不跑 gradle、不碰 adb/设备、不提交、不改 CLAUDE.md 等规则文件**。唯一写操作 = 本文档。
> 结论来源纪律：可参考历史结论为线索，但**不把「期望/推断」写成「真机已证」**。证据分三档，逐条打标：
> - **【真机坐实】** = 真机日志时间行 / 用户肉眼事实 / 文件字节事实（含跨文档转述的一手行，须注出处）；
> - **【源码确定】** = dex 反汇编 code_off / 方法签名 / 字段名 / 源码行号直接可见；
> - **【推断】** = 由已证外推，必须带置信度（高/中），不得升级为事实。
> 一手日志现状：`docs/_r78_stockcdn_realdev_clean.log` / `docs/_r76_p3_realdev_fail1.log` / `docs/_r79_dualsample_realdev.log` / `docs/_r81_r80a_falsified_realdev.log` 在本工作区 glob 全库**不存在**（见 §7 U7）；本文所引日志行均为 R78~R82 文档内的逐行转述，已逐条注出处，复核须由主会话取回原文件或重跑样本。
> 产物性质：**这是「取证方案」，不是实现，也不是历史结论复述**。复用资产 = `probe/StockCdnProbe.kt`（8 hook）与 `core/ForwardCdnFieldHook.kt`（R80a 残留 hook）。

---

## §1 目标与判死边界

### 1.1 B2 要证明的命题（一句话可证伪）

> **命题**：只要让模块转发的消息像 stock 一样「携带源坏图 xml 里的原图 `cdnbigimgurl`/`aeskey` 并触发服务端 `checkExist` 秒传命中、建一条带原图 CDN 引用的新记录」，即使本地只有 36B 占位/15KB 缩略图，对端也能收到清晰原图。

**证伪口径（一次真机三态对照即可定案）**：在「探针全装成功 + 样本以 `ori`/完整 md5 锁定 + 本地原图确认缺失 + 对端肉眼判据」四个前置都成立时，若出现以下任一，则 B2 命题被证伪：
- 模块转发消息 xml **已带**原图 `cdnbigimgurl`、`checkExist` **已命中**（`field_exist_whencheck=true`/回包 `hitCache` 指向原图），但对端**仍模糊**；
- 或 stock 基线与模块转发的全部可观测字段**逐字相同却一清晰一模糊**（⇒ 决定量不在可观测字段，B2-b 无明确改法）；
- 或源坏图 xml **根本无**原图 `cdnbigimgurl`（⇒ B2 无 URL 可引用，命题前提不存在）。

### 1.2 已被真机证伪/判死的子设计（明确「试过且失败」，禁止重做）

| 子设计 | 判定 | 铁证（出处） |
|---|---|---|
| **R80(a) 只改头**：hook `ok0/m1.e` before 补 `tj/k.field_fileId`/`field_aesKey`（`ForwardCdnFieldHook.kt` 现仍在树） | 【真机坐实】**证伪** | 23:44 `fileId` 由空转非空、`305f...` 与 stock 同前缀、`f2C2 field_exist_whencheck=true`、`UploadHitCacheType=1`，但对端仍模糊；`field_filemd5=0ef205c165e99eb0f4a1c0084e65f1aa` 与 R79 空 fileId 时**逐字相同**（R81 §1.2 表，转述 `_r79_dualsample_realdev.log` / `_r81_r80a_falsified_realdev.log`）。补的是「头」，没换「身」。 |
| **R82 原 (b)-2 设计**：补 `field_onlycheckexist=true` + `field_filemd5=原图md5` 走 hitCheck 秒传引用 | 【真机坐实 + 源码确定】**判死** | ① stock 清晰时 `onlycheckexist` 本身就是 `false`（四份日志逐字相同，R82 Z.1）；② `ok0/x0.u@0x3a98cc` 构造 `C2CUploadRequest` **不调 setFilemd5**，请求 md5 由 mars native 从 `setFilePath(fullpath)` 文件字节重算，Java hook 改 `task.field_filemd5` 不进引擎（R82 Q2/Q3 dex 实证）；③ 三字段逐字相同、唯一 correlated 差异 = `field_fullpath` 内容（stock 36B 占位 vs 我方 15906B 缩略图，R82 Z.2）。 |
| **R77/R78 早期落点**：把「`j2.R()` 后让 `r0.e` 指向本地大图字节」当清晰决定量（方案 B 原立论） | 【真机坐实】**推翻** | 22:49 stock 转坏图 216b，`r0.e=882d5de0...jpg` 是 **36B 占位**、新记录 a=884519、cache 无大图、本地 216b=36B，但对端清晰（R79 §1.2，转述 `_r78_stockcdn_realdev_clean.log` :30/:33/:34/:56）。⇒ 清晰度决定量不是 `r0.e` 的本地字节。 |
| **B1**：修 `DownloadImgService`/`R5` 异步下载落盘即可清晰 | 【真机坐实】**证伪** | 02:11 `download fired r=true` 但大图仍 36B、`ready=false`、`copy skip th_`、对端模糊（`docs/_r76_p3_realdev_fail1.log`，R85 §1.0 F8/F9 引；大图未落盘）。注意 R83 曾成功是「本地恰好有 335644B 原图」的 A/B 对照，原图被清理后必失效。 |

> 一句话边界：**这些不是「没试过」，而是「试过且失败」**。B2 只重取「B6 checkExist + 服务端秒传引用」这一条尚未被真机直接观测闭合的链，不重做上表任何一项。

### 1.3 三档证据清单（每档 2~4 条）

**【真机坐实】**
1. 22:49 stock 原生转发坏图 216b：`SCD B6 in/out` + `m1e out ret=true` + `m1e task fileId=305f...（非空）md5=（空）onlycheckexist=false` + `field_fullpath=.../88/2d/882d...jpg_`（36B） + `listener.f=com.tencent.mm.modelimage.f2` + 用户肉眼清晰（R79 §1.2 / R80 对照 A，转述 `_r78_stockcdn_realdev_clean.log` :15/:32/:33/:36/:37）。
2. 23:13 我方模块转发同图：`FORWARD plan RESEND_FILE` + `SCD B6` 命中 0 + `m1e task fileId=（空）` + `field_fullpath` 指向 15906B 缩略图 + `f2C2 field_filemd5=0ef205...` + 用户肉眼模糊（R80 对照 B / R81 §1.2，转述 `_r79_dualsample_realdev.log`）。
3. R80(a) 注入 fileId 后机制全命中但对端仍模糊，`field_filemd5=0ef205...` 跨 R79/R81 不变（R81 §1.2 铁证）。
4. R85 当天 F1~F11：两张图本地均无原图字节（`7ccd...jpg`=15906B 贴阈值误放行；216b 桶仅 36B/15906B，无 335644B），`origin-by-md5 miss`、`copy skip th_`、`ready=false`、`p3=false`（R85 §1.0~§1.3，转述 `_dbg_pull.txt:590-783`）。

**【源码确定】**
1. `MsgRetransmitUI.B6(String,int,t0)V @0x62c98c`：`0x00b6 r0.u` → `0x00bb g9.c(r0.u,"msg","")` → `0x00e8/$cdnbigimgurl`、`0x00fe/$aeskey`、`0x014e/$md5`（R79 §1.1，classes7 本轮自算）。
2. `j2.O(r0;I)Z @0x2acc44`：`field_fileId` 唯一来源 = `g9.c(j2.B,"msg","")` 的 `.msg.img.$cdnbigimgurl`（`0x026c` 覆盖 `cdnmidimgurl`），`j2.B = h9.getContent()` 原样 xml；fileId 空无兜底（R80 §1.1/§2.1）。
3. `ok0/x0.u(tj/k)I @0x3a98cc`：`0x0009 setFilePath(field_fullpath)` 无条件执行；`0x0063→checkExistOnly`、`0x0085→enableHitCheck`；**全指令无 setFilemd5**（R82 Q1.2/Z.3）。
4. `ok0/x0.onC2CUploadCompleted(String,C2CUploadResult)V`：写回 `tj/f` 的 `field_fileId/aesKey/filemd5/UploadHitCacheType/exist_whencheck` **全部来自服务端 `C2CUploadResult` 回包**（R81 §1.1 映射表）；`f2.C2@0x2aa888` 重建 `<msg><img aeskey cdnbigimgurl hdlength md5...>`，整串是局部 StringBuilder + `ot5` 子字段，不挂 String 字段（R80 Q3）。

**【推断】**
1. 【推断·高】stock 的 `B6 → B8(checkExist) → j0.C2 "support second !!" → b1.j6 建新记录 → k6 → j2.O → f2.C2 → 对端拉图` 跨方法/跨进程时序：真机只直接观测到 B6 in/out、m1e、j2R、s0b；`j0.C2`/`f2.C2` 历史零命中是探针挂错类（`qf4/j0.C2` 是 checkExist 回调，上传回调真身 `modelimage/f2.C2@0x2aa888`），**不是分支没走**（R79 §1.5、§4.6）。
2. 【推断·高】请求侧 filemd5 由 mars native 从 `setFilePath()` 文件字节重算 —— 依据 `C2CUploadRequest.filemd5` 不被 Java 设置 ⇒ 唯一字节输入是 `setFilePath`（R82 Q2.2）；跨进程无法本地 dex 全证。
3. 【推断·高】「服务端按内容 md5 认图、fileId 只是头」—— 锚点是 `field_filemd5` 跨 R79/R81 不变这一客户端可观测事实（R81 §1.3），服务端内部机制无法本地全证。
4. 【推断·高】我方仍模糊的根因 = 挂的是**源坏图旧 ImgInfo2 记录**（36B+缩略图），未新增「带有效 `cdnbigimgurl` 的记录」，与 stock 的 `B6→checkExist→b1.j6` 新记录（a=884519）差在「是否新建记录 + 是否触发服务端秒传」（R79 §4.2）；R80(a) 已证只改头不够。

[DONE] §1 目标与判死边界

---

## §2 stock 清晰链的完整机制重建

### 2.1 全链逐环节重建（锚点 + 日志证据 + 证据档位）

样本固定为坏图 **216b**（`216b1f7ccd59b1268349fd0eb3c46801`，localId=883190，msgId=19733550）—— 样本身份闸门用 `INP j2init ori=.../21/6b/216b...jpg` + `m.a=883190`（R79 §3.4 已纠：**不**用 `r0.u contains 216b`，B6 探针按现有写法读不到 u）。

| 环节 | dex 锚点 / 签名 / 字段 | 日志证据（出处） | 证据档位 |
|---|---|---|---|
| ① stock 原生转发入口 | `MsgRetransmitUI.B6(String,int,t0)V @0x62c98c` | `SCD B6 in msig=ui.transmit.MsgRetransmitUI.B6(java.lang.String,int,com.tencent.mm.modelbase.t0)` + `B6 out`（R79 §1.2，转述 `_r78_stockcdn_realdev_clean.log`:15/:32） | 【真机坐实】入口；【源码确定】锚点 |
| ② 从坏图**消息 xml** 解析原图 CDN 参数 | B6 `0x00b6 r0.u`→`0x00bb g9.c(r0.u,"msg","")`→`0x00e8 $cdnbigimgurl`/`0x00fe $aeskey`/`0x014e $md5`；`r0 = b1.a3(talker,msgSvrId) @0x00b2`（局部变量，非入参/字段） | `j2init str[7]=<?xml ...><msg><img aeskey="60af7983188a7701a..."`（R79 §3.2，转述同 log :23-25）；B6 探针 `uLen=-1` 是**探针定位失败**非「xml 无参数」（R79 Q3） | 【源码确定】解析链；【真机坐实】xml 含明文 aeskey；【推断】B6 本次取到 URL（探针未直接读） |
| ③ 构造 checkExist task 探服务端 | B6 `0x0157 new tj/k`、`0x015c d="task_MsgRetransmitUI_2"`、`0x0188 task.f=qf4/j0`、`0x01a5-0x01c0 fileId/aesKey/filemd5/mediaId`、`0x01cc mq/k.B8(task)`；**该 task 不带 field_fullpath**（不发字节） | `SCD B8` 入参 task 字段（现有探针已采，需重取确认）；`onlycheckexist=false` 真机恒值（R82 Z.1） | 【源码确定】task 构造；【真机坐实】onlycheckexist=false；【推断】B8 本次被 stock 调用 |
| ④ 秒传命中建新记录 | `qf4/j0.C2@0x84307c`：`field_exist_whencheck` + `"support second !!"` → `b1.C4@0x2a563c` → `b1.j6@0x2a74f8` 落新 localId | `j2R after localIds=[a=884519 b=0→1034490892219222103]` + `paths e=882d5de0...jpg f=null g=THUMBNAIL_DIRPATH://th_42cb...`（R79 §1.2，转述同 log :33/:34/:56）；新记录 `e`=882d 是 **36B 占位** | 【真机坐实】新 localId + 占位路径；【推断·高】"support second" 秒传分支（j0.C2 未直接 hook） |
| ⑤ 上游 j2 上传 task（真机 m1e 命中的那条） | `j2.O(r0;I)Z@0x2acc44`：`0x00a2 j2.S(=f2)`→`0x00a4 task.f`；`0x0124 field_fullpath=z3(r0.e)`；`0x02dd ok0/m1.e` 下发 | `m1e task fileId=305f... md5=（空）onlycheckexist=false`、`field_fullpath=.../88/2d/882d...jpg_`、`listener.f=com.tencent.mm.modelimage.f2`（R79 §1.2，转述同 log :36/:37） | 【真机坐实】task 字段三件套 + fullpath；【源码确定】j2.O 写字段 |
| ⑥ 上传引擎命中原图、只引用不发字节 | `ok0/x0.u@0x3a98cc`：`setFilePath(field_fullpath)`→mars `startC2CUpload`/`startSSUpload`；`enableHitCheck` 从 36B 占位字节算 md5 → 命中云端原图 | 文件事实：`882d...jpg_`=36B、`216b...jpg`=36B、`th_216b...`=15906B、cache 顶层无大图（R79 §1.2 su ls）；m1e `fileId` 非空而 `md5` 空 | 【真机坐实】36B + 非空 fileId；【推断·高】mars hitCheck 命中原图（native 跨进程，无直接回包 hook） |
| ⑦ 上传完成回调重建带 URL 消息 | `modelimage/f2.C2@0x2aa888`：`0x021e '<msg><img aeskey="'`、`0x0226-0x026c` StringBuilder 拼串、`0x02e7 g9.c`、`0x030e ot5.W`；拼出 `<msg><img aeskey cdnbigimgurl hdlength md5 originsourcemd5>` | 历史 `MSG_HAS_URL` 命中 0 —— **是探针读错对象**（整串是局部 StringBuilder + `ot5` 子字段，不挂 `tj.f`/this String 字段，R80 Q3），非「没重建」 | 【源码确定】拼串链；【推断·高】对端凭 URL 拉图（跨进程未直接观测） |
| ⑧ 对端到清晰 | 对端凭 `cdnbigimgurl` 从 CDN 拉原图 | 用户肉眼清晰（任务给定事实） | 【真机坐实】肉眼；【推断·高】拉图路径 |

### 2.2 决定性回答：stock 的清晰字节到底来自本地文件还是服务端秒传？

**答：【真机坐实 + 推断·高】来自服务端秒传（CDN），不是本地文件；本地 36B 只是记录占位。**

证据链（按强度排列）：
1. 【真机坐实·文件事实】stock 转发时 `r0.e=882d...jpg` 指向的文件 **36B**、源坏图 `216b...jpg` **36B**、`th_216b...` **15906B**，cache 无大图 —— **本地不存在可供 `o6.N`/`z3(r0.e)` 读出的大图字节**（R79 §1.2 su ls）。
2. 【真机坐实】同一样本用户肉眼清晰 —— 若字节来自本地，36B/15906B 都不足以产出清晰原图；必然有外部字节源。
3. 【真机坐实】`m1e task fileId=305f...（非空）md5=（空）` + 新记录 a=884519 —— 与「服务端已有原图、只报 fileId / 只引用」一致（R79 §1.2）。
4. 【推断·高】机制解释 = B6 从坏图 xml 读出原图 CDN 身份 → checkExist 秒传命中 → 重建带 `cdnbigimgurl` 的新记录 → 对端拉 CDN 原图。R82 Z.2 的硬差异进一步坐实方向：三字段逐字相同，`field_fullpath` 从 36B 占位换成 15906B 缩略图，结果就从清晰变模糊 —— **不是「有没有 fileId」，而是 fullpath 字节触发 mars hitCheck 命中「原图」还是「缩略图」**。
5. 【推断·高·必须标注限制】「`j0.C2 → b1.j6 → k6 → j2.O → f2.C2 → 对端拉图`」是跨方法/跨进程推断：历史真机**未直接 hook** `j0.C2`/`f2.C2`（挂错 `qf4/j0.C2`），「秒传命中建新记录 → 清晰」是多源汇聚的高置信假设，**不是单一回调直接观测**。这正是 §4 只读探针要闭合的缺口。

### 2.3 尚未有真机证据的环节（禁止写成确定）

- **U-a**：B6 是否真在本次调用 `g9.c(r0.u)` 取到 `$cdnbigimgurl/$aeskey/$md5`（B6 探针 `uLen=-1` 定位失败，现有证据只有 j2init 的同对象 xml 旁证）—— 需 §4 `g9.c` 入参探针。
- **U-b**：`qf4/j0.C2` 的 `"support second !!"` 分支是否本次命中、`b1.j6` 新记录的 out path —— 需 §4 `SCD-CDNcb`/`SCD-b1j6` 重取。
- **U-c**：`ok0/x0.onC2CUploadCompleted` 回包的 `filemd5/hitCache/existOnSvr/fileid` —— **权威内容判据，历史从未直接观测**，需 §4 `x0cb` 探针（R82 H5）。
- **U-d**：`CdnLogic.startC2CUpload/startSSUpload` 的 `C2CUploadRequest.filePath`（是否 36B 占位）与 `filemd5`（是否 null）—— 需 §4 `c2c` 探针（R82 P2）。
- **U-e**：`f2.C2` 重建的 `<msg>` 整串是否真带 `cdnbigimgurl` —— 需 §4 `g9.c` 入参探针（`f2.C2` 内 `0x02e7`），历史 `MSG_HAS_URL` 扫 String 字段必然零命中。
- **U-f**：对端「凭 URL 拉原图」跨微信服务端，本地无法 dex 全证 —— 只能靠对端肉眼 + 长按字节数代理判据。

[DONE] §2 stock 清晰链的完整机制重建

---

## §3 B2 相对于微X的等价性论证

对照 `docs/_r74_microx_impl_recheck.md`：微X的「成功」分两层 —— **字节来源**（加密 dex 私有下载/解码，不可复制）+ **终局**（让 stock j2 经 `z3(r0.e)` 读到字节，宿主通用链，可复制）。B2 走的是 stock 自己的 B6 秒传方向，与微X三层对照如下。

### 3.1 三层等价性逐层判定

| 层 | 微X实现（R74） | B2（B6 checkExist 秒传引用） | 是否必须等价 | 判定依据 |
|---|---|---|---|---|
| **字节来源** | 【真机确定】加密 dex 私有下载/解码：23:11 `R5/j6/B8/C2` 下载链全零 + `m.a=-1` + `r0.u=EMPTY`，字节落 `cache/_<uuid>`（发后删）；Java 层不可复制（R74 Q1③/Q2） | 【真机坐实 + 推断】字节在 **CDN 服务端**：stock 本地仅 36B 占位却清晰；B6 从坏图 xml 取原图 CDN 身份，checkExist 秒传引用（§2.2） | **不必等价** | 两者来源根本不同（私有下载 vs 服务端秒传），但**清晰只要求「对端拿到原图字节」**，不要求字节由谁下载。R74 已证微X字节来源不可复制；R79 进一步证 stock 清晰根本不靠本地字节 —— B2 恰好绕开了这个不可复制层，这是 B2 相对「照抄微X」的最大优势。 |
| **终局 `z3(r0.e)`** | 【源码确定 + 推断·中】让 `o2(j2.i)` 返回的 ImgInfo2 记录 `r0.e` 指向本地大图文件（`cache/_<uuid>`），j2 读图唯一链 `o2(j2.i)→r0→z3(r0.e)→o6.l()`（R74 Q3(b)） | 【真机坐实】新记录 `r0.e=882d...jpg` 是 **36B 占位**，仍清晰；清晰度决定量**不是** `r0.e` 的本地字节（§1.2 / §2.2） | **不必等价（关键差异）** | R74「终局 = `z3(r0.e)` 指大图字节」是微X方案；R79 已用 36B 占位清晰**推翻**它作为清晰决定量。B2 的 `r0.e` 只需指向一个能触发服务端秒传的占位引用（stock 用 36B），**不需要**照抄微X的「`r0.e` 指向本地大图字节」。⇒ 这层不必等价也能清晰。 |
| **上传 task 字段** | 【真机确定】`m.a=-1`、`r0.u=EMPTY`、`ori=cache/_<uuid>` 是烟幕；清晰度与 `m.a`/`r0.u`/`q1.*`/`ori` 全无关，唯一决定量是 `z3(r0.e)` 指向的文件（R74 Q3(a)） | 【真机坐实 + 源码确定】决定量 = 消息 xml 带原图 `cdnbigimgurl` + `checkExist` 命中；`m.a`/`q1`/`ori` 同样不是决定量（R80(a) 只改 fileId 头已证伪）；task 的 `field_fileId` 由 `j2.O` 从 `j2.B` 自解析（R80 §1.1） | **部分等价** | 与微X一致的是「`m.a`/`r0.u`/`q1.*` 不是决定量」这一否定性结论；B2 必须等价的是**「消息 xml 带原图 cdnbigimgurl」**（= 微X `j2.B` 含原图 CDN 身份的同类条件），而不是微X的「把字节写进 `r0.e`」。 |

### 3.2 B2 成功的最小机制判据（不需要照抄微X任何一层）

按必要性从小到大，四条全中即 B2 成功（1~3 为客户端可观测，4 为终极）：

1. **【决定量】转发消息 xml（j2.B / `f2.C2` 重建串）携带源坏图的原图 `cdnbigimgurl` + 配对 `aeskey`** —— 来源：`b1.a3→r0.u→g9.c(u,"msg","")` 的 `.msg.img.$cdnbigimgurl/$aeskey`（R80 §2.3 / R79 §4.3）。
2. **【秒传命中】`mq/k.B8` checkExist 探测到服务端原图存在**：`field_exist_whencheck=true`（`qf4/j0.C2` "support second" 分支）+ 回包 `hitCache` 指向原图而非缩略图（R82 P1）。
3. **【字节源正确】上传 task 的 `field_fullpath` 指向「能触发原图 hitCheck 命中」的内容**：stock 是 36B 占位；**不能**是 15906B 缩略图（R82 Z.2 硬差异）。这一条是 R80(a) 只改头失败的根因 —— 头对了身不对。
4. **【终极·人眼】对端肉眼清晰**：长按保存字节数 ≫15KB、≈源图分辨率；2+ 目标均收到。`ok=true`/`onGYNetEnd 0/0` 不算（历史假阳性，R74 Q4(d)）。

> **净结论**：B2 与微X只在「消息 xml 带原图 CDN 身份」这一**否定的/身份层面**等价；微X的字节来源层（不可复制）与 `r0.e` 大图字节终局层（已被 36B 清晰推翻）**都不必等价**。B2 的可行性不依赖复制微X任何一层，而是依赖「stock 自己走 B6 秒传」这条被 R79/R82 锁定、但尚未被真机直接 hook 闭合的链 —— 这正是本方案 §4 要取证的对象。

[DONE] §3 B2 相对于微X的等价性论证

---

## §4 只读探针方案（核心可执行清单）

### 4.0 复用资产与只读纪律

**现有资产（只读探针，不改）**：
- `probe/StockCdnProbe.kt` 现有 **8 hook**，标签前缀 `SCD`，logcat tag 沿用 `WXGroup`（`LogX`）：`SCD-B6`（`MsgRetransmitUI.B6@0x62c98c`）、`SCD-m1e`（`ok0/m1.e@0x7b52b4`）、`SCD-CDNcb`（`qf4/j0.C2@0x84307c`，checkExist 回调）、`SCD-s0b`（`modelimage/s0.b@0x2b429c`）、`SCD-b1j6`（`modelimage/b1.j6@0x2a74f8`）、`SCD-j2R`（`modelimage/j2.R@0x2ad864`）、`SCD-f2C2`（`modelimage/f2.C2@0x2aa888`，上传完成回调）、`SCD-B8`（`mq/k.B8`，checkExist 下发）。现状可用：`op=stockcdn sub=on|off` 广播翻转，`status()` 回显 `enabled/installed/ok/fail` + 每 hook 一行（`:371-388`）。
- `core/ForwardCdnFieldHook.kt`：**R80(a) 残留写 hook**（`m1.e before` 会 `setObjectField(task,"field_fileId"/"field_aesKey")`），已被真机证伪。**取证期间必须保证它不写**——但「保持不 arm」在操作上做不到（模块转发链会无条件调 `arm`，详见 §4.0.1），必须择一处置 (a)/(b)/(c)，否则污染「模块转发模糊」基线。本方案**不修改它**（处置是 B2-a 的前置装机动作，见 §4.0.1）。

**只读纪律（新增/扩展 hook 必须逐条满足，照抄 `StockCdnProbe` 头部红线 `:23-42`）**：
1. `before/after` **只打日志**；绝不写 `param.args[...]`、绝不写 `param.result`、绝不 `setObjectField`。
2. **绝不主动调用任何宿主业务方法**（`b1.R5/j6/o2/z3/Y2`、`mq/k.B8`、`ok0/m1.e`、`g9.c` 等都不可在回调里替宿主调）；只读 = `getObjectField/getBooleanField/getLongField` + `Field.get`，各自 `runCatching`。
   - ⚠️ 特例：`b1.a3`/`g9.c` 的**主动调用**会触发业务语义，判据读取须靠「hook 这些方法在宿主自己调用时的入参」实现（见 P5），不得在别的回调里主动调。
3. `enabled` 首行 `if (!enabled) return`（零反射、零字符串拼接）；hook 常驻安装，广播翻转，默认 false。
4. 每个 hook 用 `guard` 包；单钩失败只记 `FAIL` + 一条 WARN，不崩微信、不影响其它 hook；回调体全包 `runCatching`。
5. 日志裁剪：普通 String `take(80)`、路径 `take(120)`、`cdnbigimgurl/fileId/aesKey` **只打「是否存在 + 长度/前缀」**，绝打完整值；`takeLast(24)` 与完整 md5 同时打（应对 R85 §1.1 tail24 分桶陷阱）。
6. 签名漂移用 `hookAllMethods(类,方法名,cb)` 兜底 + 回调打 `msig=`；`install` 幂等，`status()` 回显供前置校验。
7. **本方案不改 `ForwardCdnFieldHook`（写 hook），不新增任何写操作**；新增 hook 只用于取数。

#### 4.0.1 R80(a) 残留写 hook 的取证期处置（样本污染操作缺口修补）

> 补 §4.0 资产清单「保持不 arm」的**可操作实现**，不改 §1~§3 任何结论、不新增写操作。以下均为【源码确定】（源码行号直接可见，本轮逐行复核）。

**为什么「保持不 arm」做不到（操作缺口）**：
- `FileSender.kt:158-162`：模块转发链在 `forwardCtx != null && imgLocalId>0 && msgSvrId>0` 时**无条件**调 `ForwardCdnFieldHook.arm(...)`，没有「模块转发但不 arm」的调用路径 —— 「只要不主动调就不 arm」在操作上不成立。
- `ForwardCdnFieldHook.kt:125-152`：`arm()` 自身主动调宿主业务方法 `d3.O8()/a3`（`:133-135`）与三参 `g9.c(u,"msg","")`（`:139-141`）；只要 `r0.u` 取到、`g9.c` 返回的 `.msg.img.$cdnbigimgurl`/`$cdnmidimgurl` 非空，即 `pending=...` + `enabled=true` 开闸门（`:149-151`）。
- 闸门一开，`m1.e before`（`:72-79`）就 `inject()` 写 `task.field_fileId/field_aesKey`（`:110-113`）并打 `CDN_FIELD injected`（`:115`）。`install()`（`:57-91`）无任何开关门，`HookEntry.kt:136-138` 无条件安装；`enabled` 仅由 arm 成功打开、TTL_MS=2000（`:34`）或发送结束关。
- **净结论**：态②（模块转发模糊）基线只要源图带 CDN URL，`CDN_FIELD injected` **必然发生**，命中 §4.2 判读铁律 4「样本作废」⇒ 三态对照断裂。故取证前必须在下列 (a)/(b)/(c) 中择一落地，光靠「不主动调」无效。

**三条可执行处置（每条标注代价与是否碰源码）**：

| 处置 | 做法 | 代价 | 碰源码/装机 |
|---|---|---|---|
| **(a) 摘 hook 重打包（推荐）** | B2-a 取证包构建时把 `HookEntry.kt:136-138` 的 `ForwardCdnFieldHook.install(lpparam)` 整段摘掉，重打取证 APK | 最干净：从根上没有 m1.e 写 hook，态②不可能被污染；需构建/装新包 | **改源码 + 需装机**（B2-a 前置装机动作，不是本 lane 做） |
| **(b) arm 加默认关闭开关门** | 新增 `Keys` 键（如 `chat.b2_cdnwrite`，默认 false）+ `arm()` 首行 `if (!keyOn) { skip("gate off"); return@runCatching }` guard，`enabled` 默认 false 不变 | 保留 hook 但默认不 arm；多一处开关状态要回读核验 | **改源码 + 需装机** |
| **(c) 只选 arm 会 skip 的样本** | 不改包、改样本：仅取 `arm()` 主动 skip 的图 —— `talker.isBlank()` / `msgSvrId<=0` / `imgLocalId<=0`（`:127`）、`loader==null`（`:131`）、`r0.u==null`（`:137`）、`g9.c==null`（`:141`）、`fileId.isNullOrBlank()`（`:145-148`）任一成立（日志 `arm skip reason=...`、无 `injected`） | 零改包零装机；但样本受限，且**若 B2 目标坏图恰好都带 CDN URL（216b 即此类），arm 必成功、(c) 不可用** | 不改源码、不装机 |

> **推荐 (a)**，一句话理由：B2-a 本就要为 E1~E4 扩展 hook 装一次新取证包，顺手把残留写 hook 从 `HookEntry.kt` 摘掉是零额外装机成本，且从根上保证态②不被 `CDN_FIELD injected` 污染；(b)/(c) 分别多一次开关状态核验、多一条「样本必须命中 skip」的硬约束。
>
> ⚠️ **(c) 与 B2 命题前提冲突**：B2 要求源坏图 xml 带原图 `cdnbigimgurl`（§3.2 决定量、§6.2 go 门槛 3），而 `arm skip reason=no fileId`/`r0.u null` 的图恰恰**没有可引用 URL**，采到的态②不能用于判定 B2（等同 §6.2 no-go `g9c hasBig=false`）。故 (c) 只能当「先跑通流程」的过渡，不能作为 B2 有效基线的处置。

### 4.1 探针清单（复用 8 + 扩展 4；扩展项标注「新增·只读」）

> 开关键名沿用 kvset 风格：新增开关 `chat.b2_forensics`（默认 **false**），与现有 `op=stockcdn` 并列；建议复用 `op=stockcdn sub=on|off` 广播通道（新增 `sub=b2on|b2off|b2status` 或独立 `op=b2probe`），**不得**触碰 `chat.forward_p3_bigimg`/`chat.forward_native` 等已闭环开关。所有新 hook 挂到 `StockCdnProbe.install` 的同款 guard 体系，复用 `hookRobust`/`dumpStringFields`/`dumpIntFields`/`findObjectOfType`。

| # | hook 目标（类.方法 / 签名 / dex 锚点） | 要采的字段（只读） | 日志 label 格式 |
|---|---|---|---|
| **E1** | `ok0.x0.onC2CUploadCompleted(String, C2CUploadResult)V` @`0x3aa9bc`（classes13）【新增·只读】 | `param.args[1]`（C2CUploadResult）读 `filemd5`（**回包 md5，权威内容判据**）、`hitCache`(→UploadHitCacheType)、`existOnSvr`(→exist_whencheck)、`fileid`（长度+前缀）；对照 `R79/R81` 的 `0ef205...` | `SCD2 x0cb in md5=${前缀} md5Len=? hit=? exist=? fidLen=? fidPre=?` |
| **E2** | `CdnLogic.startC2CUpload` / `startSSUpload`（native static，hook static 入口）【新增·只读】 | `param.args[0]`（C2CUploadRequest）读 `filePath`（长度+尾缀→判 36B/15906B）、`forwardFileid`（长度+前缀）、`checkExistOnly`、`enableHitCheck`、`filemd5`（是否 null） | `SCD2 c2c in pathLen=? pathTail=? forwardFidLen=? checkExistOnly=? enableHit=? reqMd5Null=?` |
| **E3** | `com.tencent.mm.sdk.platformtools.g9.c(String,String,String)Map` @`0x4fd510` 的 **before**【新增·只读】 | `p.args[0]` = 待解析 xml 串：只读判断 `contains("cdnbigimgurl")`/`contains("aeskey")`/`contains("216b1f7c")`，打各 key 值**长度**与 md5 前缀；**不打完整 xml/aeskey**。覆盖 B6 `0x00bb` 与 `f2.C2 0x02e7` 两处宿主自调 | `SCD2 g9c in hasBig=? bigLen=? hasAes=? hasMd5=? md5Pre=? has216b=?` |
| **E4** | `qf4.j0.C2(String,int,tj/e,tj/f,boolean)I` @`0x84307c`（复用 `SCD-CDNcb` 扩展 after） | before 读 `field_exist_whencheck` + `"support second !!"` 特征；after 读 `param.result`（新 localId） | `SCD2 j0C2 exist_whencheck=? retNewLocalId=?`（现有 `SCD CDNcb in f field_exist_whencheck=...` 保留） |
| **R1** | `SCD m1e`（现有）：`task.field_fileId/field_aesKey/field_filemd5/field_onlycheckexist` + `listener.f` + `field_fullpath` | 见 `StockCdnProbe.kt:145-166`；**额外补 `field_enable_hitcheck`/`field_fake_bigfile_signature` 是否空**（R82 Q4.1 H2 关注） | `SCD m1e in task fileId=${长度} md5= onlycheckexist=false ...` + `SCD2 m1e task hit=? fakeSigEmpty=? fidLen=?` |
| **R2** | `SCD-B6`（现有 + 修读取落点）：`B6(String,int,t0)V` 入参 + `t0` 字段 | 现有 `findObjectOfType(a,this,r0)` 拿不到 r0（`uLen=-1`，R79 Q3）；改为**只读挂 `b1.a3` after 读返回 r0 的 `u`**（见 E3 的宿主自调范式），或仅靠 E3/`j2init` 取 xml，不再依赖 B6 探针的 r0.u | 保留 `SCD B6 in msig=...`；`r0.u hasCdnBigImgUrl` 行标注「探针限制，非证据」 |
| **R3** | `SCD-b1j6`（现有）：`b1.j6(...PString...)J` after 读 newLocalId + `PString.value` | 新记录路径出参（`882d...` 占位 vs 真大图） | `SCD b1j6 ret newLocalId=$id path=$path`（现有） |
| **R4** | `SCD-f2C2` + `SCD-j2R`（现有）：回包字段 + 终局 r0 路径 | `field_exist_whencheck`/`field_UploadHitCacheType`/`fStrFields`；`r0.a/b/e/f/g` | 现有 `SCD f2C2 in f ...` / `SCD j2R after ...`（保留） |

**关键字段采集映射（任务点名项全部覆盖）**：
- B6 入参/回包 checkExist 结果 → E4（`j0C2 exist_whencheck`+新 localId）+ R1（`m1e onlycheckexist`）+ `SCD-B8 in/out ret`
- `fileId/aesKey` 来源 → E3（`g9c hasBig/hasAes`）+ R1（`m1e task fileId` 长度）+ E2（`forwardFidLen`）
- `cdnbigimgurl` → E3（`hasBig`/`bigLen`，只长度）+ `SCD f2C2 MSG_HAS_URL`（只命中/长度）
- `task.fullpath` → R1（`SCD m1e field_fullpath`，tail+完整尾部）+ E2（`pathLen/pathTail`）
- `z3(r0.e)` 的 out → R4（`SCD-s0b ret path` + `SCD-j2R paths=[e/f/g]`）
- 回包 md5 → E1（`x0cb md5` 前缀，权威）+ R4（`f2C2 fStrFields field_filemd5`）

### 4.2 三态区分与判读规则

**三态定义**：
- **态① stock 原生转发清晰（基线）**：用户走微信自带「长按→转发」，不经模块。
- **态② 模块转发模糊（现状）**：用户走模块「转发（模块）」，`ForwardCdnFieldHook` 未写 task（须靠 §4.0.1 处置保证；模块转发链本身无条件调 `arm`，不能只靠「不主动调」）。
- **态③ 模块转发清晰（目标）**：同态②路径，但对端清晰（B2 复刻生效后的期望态，取证阶段先判「是否出现」）。

**逐日志判读规则（互斥，不满足即判无效样本/判死）**：

| 判据行 | 态① stock 清晰（基线预期） | 态② 模块模糊（现状预期） | 态③ 模块清晰（目标预期） |
|---|---|---|---|
| `SCD B6 in/out` | **必命中**（入口闸门） | **必 0**（`RESEND_FILE`，R80 对照 B） | 视实现：走 B6 复刻则命中，自建 task 则 0（不作为闸门） |
| `SCD m1e task fileId` | `305f...` 非空 | 空（若残留 hook 误 arm 则非空 ⇒ 样本污染，重来） | 非空且指向原图 |
| `SCD2 g9c hasBig`（E3） | 源 xml 应为 `hasBig=true` | `hasBig=true` 但**没走到 checkExist**（R79 §4.2 推断） | `hasBig=true` 且后接 `j0C2 exist_whencheck=true` |
| `SCD2 j0C2 exist_whencheck`（E4） | `true`（"support second" 命中）【待真机坐实】 | `true` 但命中缩略图（R79 对照 B 的 `exist_whencheck=true`） | `true` 且命中原图 |
| `SCD2 c2c pathLen`（E2） | ≈36（占位）【待真机坐实】 | ≈15906（缩略图） | ≈36（占位） |
| `SCD2 x0cb md5`（E1，**权威内容判据**） | ≠`0ef205...`（原图 md5）【历史未观测】 | `0ef205...`（缩略图，R81 铁证） | ≠`0ef205...`（原图 md5） |
| `SCD2 x0cb hit` / `exist` | 指向原图 | =3 / true 但内容是缩略图 | 指向原图 |
| `SCD b1j6 newLocalId` | 新 id（884519 类）+ path=882d 占位 | 新 id + path=缩略图 | 新 id + path=占位/带 URL |
| `SCD s0b` / `SCD j2R paths` | 大图路径 or 占位路径（R79 未定，需重取） | `th_` 缩略图路径 | 占位/原图引用 |
| 对端肉眼 | **清晰**（基线事实） | **模糊**（现状事实） | **清晰**（目标） |

**判读铁律**：
1. **互斥而非并列**：E1 `md5=0ef205...` 与「内容换成功」互斥；命中前者即判「仍在传缩略图」，不看其它字段自我安慰。
2. **禁止把期望写成证据**：上表标「【待真机坐实】」的行是**预期**，只有真机 logcat 实际出现该行才算证据；历史日志没出现过的字段（E1/E2/E3）不因其「应该在」就当已证。
3. **E2 `reqMd5Null=true` 是判死铁证的复验**：若为真，印证 filemd5 由 mars 重算、Java hook 改 task.field_filemd5 无效（R82 Q2）——这决定 B2-b 绝不能走「改字段」老路。
4. **样本污染判据**：态②若出现 `SCD m1e fileId=非空` 或 `CDN_FIELD injected` ⇒ `ForwardCdnFieldHook` 被 arm，本次样本作废（它已证伪，会混淆判定）。
5. **`MSG_HAS_URL` 零命中不算「没重建」**：E3 的 `g9c hasBig` 才是重建串的直接观测（R80 Q3）。

[DONE] §4 只读探针方案（核心可执行清单）

---

## §5 真机操作步骤与三档红线

> 本节是**给主会话/用户的操作清单**。本 agent 不碰设备、不代替用户点微信。命令均为主会话执行的只读/广播命令，格式沿用 `docs/开发回路速查.md`。

### 5.1 三档红线（逐条硬约束）

**进入边界红线（取证前）**
1. **只读取证**：不改宿主对象、不写库（`op=sql` 只读 select/pragma；不碰 `snsg sub=show` 这类写探针）、不装包状态下先确认探针 hook 全装成功。
2. **先确认已装-hook 数**：`python tools/dev.py probe stockcdn b2status --grep 'SCD STATUS' --n 5`（或 `adb shell am broadcast -a com.lx.wxgroup.action.PROBE --es op stockcdn -p com.tencent.mm` 后拉日志），判据 `SCD STATUS enabled=true installed=true ok=12 fail=0`（8 复用 + 4 扩展；若扩展未装则 ok=8 并**明确标注 E1~E4 未覆盖**，不得带病取证）。任一 hook FAIL ⇒ 停，先修挂载。
3. **`logclear` 后冷启**：先 `python tools/dev.py probe logclear --grep ...` 清日志+计数归零（**不 force-stop**）；再冷启（`python tools/dev.py restart` 双次 force-stop 确保模块重载），判据日志出现 `SCD STATUS ... installed=true`。
4. **样本身份用 ori/完整 md5 锁定，禁止 tail24**：以 `INP j2init ori=.../21/6b/216b1f7ccd...jpg` + `m.a=883190` + 完整 md5 `216b1f7ccd59b1268349fd0eb3c46801` 三重锁定（R85 §1.1：日志 `path=` 是 `takeLast(24)`，误分桶到 `b1/0e`）。
5. **确认本地原图是否存在的只读命令**（主会话 su shell，只读）：
   `ls -l /data/user/0/com.tencent.mm/MicroMsg/89476c7fc82a9f977bc8a901d68b7d37/image2/21/6b/`（216b 桶）与 `.../7c/cd/`（7ccd 桶）、`.../88/2d/`（stock 新记录占位）；判据：无 335644B 文件、仅 36B/15906B ⇒ 「本地无原图」坐实。
6. 微信须前台（后台广播不投递）；`ForwardCdnFieldHook` 的状态按第 7 条确认（不能只看「没主动调」）。
7. **R80(a) 残留写 hook 处置闸门（见 §4.0.1，取证前必做）**：主会话拉 `python tools/dev.py q 'FORWARD CDN_FIELD' 40`，**确认无 `CDN_FIELD injected`**，并严格区分 `arm skip reason=...`（未注入，安全）与 `armed fileIdLen=...` / `CDN_FIELD injected`（已注入，污染）。按处置分支各有一条进入判据：
   - **(a)**：取证包已摘 `HookEntry.kt` 的 `ForwardCdnFieldHook.install`，冷启后 `q 'FORWARD CDN_FIELD' 40` **零命中**（连 `hook ok0.m1.e`/`arm skip` 都无）⇒ 可进入；
   - **(b)**：开关（如 `chat.b2_cdnwrite`）回读 `now=false`，且 `arm()` 首行 guard 生效 —— 模块转发后仅见 `arm skip reason=gate off`、无 `armed`/`injected` ⇒ 可进入；
   - **(c)**：源样本命中 `arm skip reason=...`（invalid args / r0.u null / no fileId 等）且无 `injected`，并满足 §4.0.1 关于「样本 URL 与 B2 前提」的限制 ⇒ 可进入；
   - 以上任一不满足 ⇒ 停，先按 §4.0.1 处置再做三态，不得带污染进入。

**返回边界红线（取证后归档）**
1. **区分真机已证 vs 推断**：只有 logcat 实际出现的行 + 用户肉眼事实才算【真机坐实】；§4.2 表内「预期/待坐实」行、跨方法时序一律标【推断】，不得写成证据。
2. **禁止把期望日志写成证据**：E1/E2/E3 历史从未观测，若本次仍零命中，如实记「探针零命中/落点待修」，不写「应当命中所以命中」。
3. 三态各自要采到以下日志行才算**有效样本**（缺任一即作废重来）：
   - **态①**：`SCD B6 in`+`SCD B6 out` + `SCD m1e out ret=true` + `SCD m1e task fileId=非空` + `SCD2 c2c pathLen≈36` + `SCD2 x0cb md5≠0ef205`（或如实标注未采到）+ 用户肉眼清晰；
   - **态②**：`FORWARD plan RESEND_FILE`（或 `SCD B6` 命中 0）+ `INP j2init ori=216b`/`m.a=883190` + `SCD m1e task fileId=空` + `SCD2 c2c pathLen≈15906` + `SCD2 x0cb md5=0ef205` + 用户肉眼模糊；
   - **态③**：同态②入口 + `SCD2 g9c hasBig=true` + `SCD2 j0C2 exist_whencheck=true` + `SCD2 c2c pathLen≈36` + `SCD2 x0cb md5≠0ef205` + 用户肉眼清晰。
4. **对端肉眼的判据写法（不用内部计数器）**：接收方长按收到的图 → 保存/查看字节数 **≫15KB、≈源图分辨率**；2+ 目标均收到清晰图。`ok=true`/`onGYNetEnd 0/0`/`addSendTask ret=true` 均不算（历史假阳性）。
5. 三态必须**互斥归档**：一清晰一模糊的两次转发，可观测字段若有差异，逐行并列；若逐字相同却结果相反 ⇒ 记「决定量在不可观测处」，B2-b 判死。

**复位边界红线（取证后恢复现场）**
1. **探针默认关**：`python tools/dev.py probe stockcdn b2off --grep 'SCD enabled' --n 3` + `op=stockcdn sub=off`；`ForwardCdnFieldHook` 保持未 arm（不调用 `arm`）。
2. **开关回读复核**（`kvset` 回显会照打已破坏的值，必须回读）：广播后拉日志确认 `SCD STATUS enabled=false` / `kvget chat.b2_forensics` 回 `now=false`。
3. **清日志**：`op=logclear` 一次，确认计数归零（不用 force-stop 掩盖现场）。
4. **临时文件清理**：本方案不落盘新文件；若实现 lane 后续产生 `cache/_<uuid>` 中转，须确认发后 `stat` 为 No such file（微X物证，R74 Q4(c)）；本 agent 不创建临时文件。
5. **不改持久化配置**：不动 `chat.forward_p3_bigimg`/`chat.forward_native`/`chat.gconv_*` 等既有键值；`chat.b2_forensics` 仅在需要时翻转并回读，结束后回默认 false。
6. **不提交、不改源码、不跑 gradle**：取证产物只有日志与本文档。

### 5.2 用户操作步骤（编号清单，先说步骤再点；本 agent 不代替用户点微信）

1. **准备**：用户确认接收方设备在线、可长按看图；主会话完成 §5.1 进入边界（探针 ok=12/fail=0、logclear、冷启、样本锁定、原图存在性只读核）。
2. **采集态①（基线）**：用户**长按 216b 坏图 → 选微信原生「转发」**（不经模块）→ 发给目标 → 用户肉眼判清晰；接收方长按保存看字节数。主会话拉日志归档 B6/m1e/c2c/x0cb 行。
3. **采集态②（现状）**：**前置：必须先完成 §4.0.1 的 (a)/(b)/(c) 之一（推荐 (a)），否则样本作废、不要开始采集**；用户**长按同一张 216b 坏图 → 选「转发（模块）」**→ 选目标 → 发送 → 用户肉眼判模糊；主会话按 §5.1 进入边界第 7 条确认无 `CDN_FIELD injected`（区分 `arm skip reason=...`）并拉日志。
4. **采集态③（若已装 B2 复刻版）**：同步骤 3 路径再发一次 → 用户肉眼判是否清晰；主会话按 §4.2 判 `g9c/j0C2/c2c/x0cb` 四行。
5. **反向对照（可选但强烈建议）**：`chat.b2_forensics` 关 → 回读 `now=false` → 冷启 → 同图重发，确认回退到态②模糊（自变量唯一）。
6. **复位**：主会话按 §5.1 复位边界关探针、回读、清日志；用户确认微信恢复正常转发。
7. **回报**：用户只回报肉眼结果（清晰/模糊 + 接收方字节数量级）；字段判读由主会话完成，不要求用户读日志。

[DONE] §5 真机操作步骤与三档红线

---

## §6 路线对照与裁决建议

### 6.1 三条路线六维对照

| 维度 | **B2-a：纯扩展只读探针先取证（本方案推荐先做）** | **B2-b：在 B2-a 证据支持下改 task/listener 复刻秒传** | **放弃 B：继续用 A 止血 + 提示用户原生转发** |
|---|---|---|---|
| 核心动作 | 在 `StockCdnProbe` 扩展 E1~E4 四个只读 hook + `chat.b2_forensics` 开关，采 E1 回包 md5/E2 filePath/E3 g9c xml/E4 j0C2 秒传结果；不改 param/不调宿主/不写库；跑 §5 三态对照 | 复刻 B6：`b1.a3→r0.u→g9.c` 取原图 CDN 参数（R79 §4.3）→ 自建 `tj/k` checkExist task（`task_MsgRetransmitUI_2`、fileId/aesKey/mediaId、**不设 fullpath**、自建 `Ltj/i` listener 不依赖 Activity）→ `mq/k.B8` 探测 → `field_exist_whencheck=true` 时 `b1.j6` 建新记录 → 转发消息 xml 带 `cdnbigimgurl`（R82 Q4.1 H1~H3） | 保持 R85 路线 A（`P3AdmitGate` 六规则）止血：只消除假 `p3=true`、失败 reason 可区分；模块加 UX 降级提示「原图存云端，建议用系统转发获得清晰图」（R80 §务实建议 / R81(c)） |
| 代码量 | 小~中：4 个只读 hook（范式现成）+ 1 开关 + 状态接线 + 单测（沿用 StockCdnProbeTest 范式）；**零业务源码改动** | 大：自建 task/listener 生命周期 + 多目标 UUID 隔离 + onGYNetEnd 0/0 清理；R80 曾否决「整套复刻 B6」、R76~R82 六轮卡在此（R85 §3.1） | 极小：A 已离线三闸绿（未装机），UX 仅文案 + 失败分支 |
| 是否写库 | **否**（只读字段/日志） | **是**：`b1.j6` 建新记录（写宿主 ImgInfo2/消息库）—— 注意这违反本 lane「不写库」，须由实现 lane 另立需求拍板 | 否（文案只读/提示） |
| 竞态 | 低：只读、开关默认关；仅多目标日志交错需 label 区分 | 高：checkExist task 与上传 task 是**两条 task、两个 listener**（R79 Q2），复刻极易挂错回调（R80 否决理由）；多目标 task/listener 0/0 清理竞态（R74 §9.5） | 无 |
| 失效场景 | `g9.c`/`c2c`/`x0cb` 若签名漂移挂不上 ⇒ E1~E3 零命中，需按 `msig=` 修落点（不猜）；样本/账号隔离挂错类（R78 曾挂错 f2） | ① 源 xml `cdnbigimgurl` 已损坏/过期；② CDN 服务端无该原图（fileId `305f...` 过期）；③ checkExist 命中但新记录 xml 缺 `cdnbigimgurl`；④ 挂错 listener（qf4/j0 vs f2） | ① 用户仍得不到清晰图（A 明确不承诺清晰）；② UX 文案属产品行为，需用户拍板 |
| 验证成本 | 中：一次装机 + §5 三态真机 + 拉日志判读；**不跑 gradle（本 lane 不装包，由主会话另派实现 lane 接线）** | 高：dex 自算锚点 + 真机多轮 + 对端肉眼 + 反向开关对照（历史 R76/R77/R78/R80/R81/R82 六轮均卡在此） | 低：真机看提示文案 + 用户确认 |

### 6.2 推荐顺序与 go/no-go 门槛

**推荐：先做 B2-a（纯扩展只读探针先取证，零写库零改上传链）。** 理由：
- B2 全链的**唯一未闭合缺口**是「B6 checkExist 秒传命中 → 新记录带 `cdnbigimgurl` → 对端拉原图」这段跨方法/跨进程时序（§2.3 U-a~U-f），历史从无 E1/E2/E3 的权威字段真机数据；不先取证就上 B2-b，等于重演 R80(a)「机制命中但对端仍模糊」的被动。
- B2-a 零写库、零上传链改动、竞态低、失败也不污染现有功能，是**最便宜的证伪器**：一次三态对照即可判定 B2 命题成立与否。

**go/no-go 门槛（什么证据出现才值得进入 B2-b）**：

进入 B2-b 必须**同时**满足（全部为【真机坐实】，缺一即 no-go）。**态②有效性前置 = §4.0.1 的 (a)/(b)/(c) 处置择一已落地并经 §5.1 第 7 条确认无 `CDN_FIELD injected`；未处置时态②样本按 §4.2 铁律 4 作废，其数据不得用于下列门槛判定**：
1. 态②基线复现（依赖上述前置）：`SCD2 x0cb md5=0ef205...` + `SCD2 c2c pathLen≈15906` + 对端模糊（证明当前症结确在「传的是缩略图字节」）；
2. 态①基线成立：`SCD2 c2c pathLen≈36` + `SCD2 x0cb md5≠0ef205`（或历史 fileId 非空 + 36B + 肉眼清晰）+ `SCD2 j0C2 exist_whencheck=true`（证明 stock 秒传方向真实、且命中原图）；
3. 决定量可观测：`SCD2 g9c hasBig=true`（坏图 xml 确有原图 `cdnbigimgurl`，B2 有 URL 可引用）—— 若为 `false`，B2 命题前提不存在，直接 no-go；
4. **因果闭环**：态③（若已装复刻版）出现 `g9c hasBig=true` + `j0C2 exist_whencheck=true` + `c2c pathLen≈36` + `x0cb md5≠0ef205` + 对端清晰，且**关 `chat.b2_forensics` 反向对照回退模糊**（自变量唯一）。

**no-go 判死信号（出现任一即停止 B2，转放弃 B）**：
- `SCD2 g9c hasBig=false`（源 xml 无原图 URL）；
- `SCD2 x0cb md5=0ef205...` 且 `c2c pathLen≈36` 且对端模糊（占位字节命中却仍模糊 ⇒ 新记录 xml 缺 cdnbigimgurl，B2-b 也救不了，查 `b1.j6` 后仍失败则停）；
- 态①与态②全部可观测字段逐字相同却一清一糊（决定量在不可观测处，B2-b 无明确改法）；
- E1/E2/E3 反复挂不上且 `msig=` 显示签名漂移无法定位（机制不可达，退 (c)）。

> 裁决一句话：**先 B2-a 取证把 B2 从「高置信推断」变成「真机坐实或证伪」，再决定是否值得承担 B2-b 的写库/竞态/工程量；在当前 E1/E2/E3 零数据的前提下直接上 B2-b，等同重赌 R80(a)。**

[DONE] §6 路线对照与裁决建议

---

## §7 UNKNOWN 清单与补证命令

> 以下命令均由**主会话**执行；本 agent 不碰 adb/设备。命令格式沿用 `docs/开发回路速查.md`：`python tools/dev.py q` 只读拉日志、`python tools/dev.py probe ...` 广播探针、`probe sql --q` 只读查库（仅 select/pragma/with）、su shell `ls`/`md5sum` 只读查桶。每条「不猜」，只给补证动作。

| ID | UNKNOWN（当前无数据） | 当前证据边界 | 需要主会话用哪条只读命令 / 哪段真机操作补证 |
|---|---|---|---|
| U1 | stock 清晰字节**是否真的来自服务端秒传而非本地残留**（§2.2 核心结论的最后一块拼图） | 【真机坐实】本地 36B/15906B、fileId 非空 md5 空、肉眼清晰；【推断·高】`j0.C2→j6→f2.C2→对端拉图` 时序、`j0.C2`/`f2.C2` 历史零命中（挂错类） | 装 E1/E2/E4 后跑态①：主会话 `python tools/dev.py q 'SCD2 x0cb|SCD2 c2c|SCD2 j0C2|SCD b1j6' 60`；判据 `x0cb md5≠0ef205` + `c2c pathLen≈36` + `j0C2 exist_whencheck=true` + `b1j6 newLocalId` 同现 ⇒ 服务端秒传坐实 |
| U2 | **`cdnbigimgurl` 是否在坏图消息 xml 里真实存在**、B6 是否取到（B6 探针 `uLen=-1` 是定位失败） | 【真机坐实】j2init `str[7]` 读到明文 `aeskey=60af...`（R79 §3.2）；【推断】B6 本次取到 URL | 装 E3 后跑三态：`python tools/dev.py q 'SCD2 g9c' 60`；判据 `g9c hasBig=true bigLen>0`。或用只读 SQL 查消息 content：`python tools/dev.py probe sql --q "select content from message where msgId=19733550" --grep SQLX`（注意 BLOB 列 `getString` 判空的坑，开发回路速查 §1.4） |
| U3 | **B6 回包 fileId 与上传字节的关系**（为何 R80(a) fileId 注入后仍模糊） | 【真机坐实】`fileId` 头 `0f836cfd` 被回显但 uuid 新分配、`field_filemd5=0ef205` 不变（R81 §1.3）；【推断·中】服务端按 md5 认内容 | 装 E1+E2 后跑态②：`python tools/dev.py q 'SCD2 x0cb|SCD2 c2c|SCD m1e in task' 60`；对照 `x0cb md5` 与 `m1e fileId`，若头同 md5 仍 `0ef205` ⇒ 坐实「只换头不换身」 |
| U4 | **R80(a) 机制命中仍模糊的最终解释**是否已由「fullpath 决定 hitCheck 命中对象」完全解释 | 【真机坐实】`onlycheckexist=false`/fileId 同串/fullpath 36B vs 15906B（R82 Z.1/Z.2）；【推断·高】mars 从 filePath 重算 md5 | 装 E2 后：`python tools/dev.py q 'SCD2 c2c reqMd5Null=true' 40` ⇒ 复验 `C2CUploadRequest.filemd5=null`、请求 md5 由 native 重算（R82 Q2 判死铁证的运行时复验） |
| U5 | **多目标并发隔离**：checkExist task / 新记录 / `cache/_<uuid>` 是否每目标一份、0/0 清理 | 无数据；R74 §9.5 标 UNKNOWN，R79/R82 未覆盖 | 实现 lane 若进入 B2-b：多目标转发后只读 `ls -l /data/user/0/com.tencent.mm/cache/_*` 看临时文件数 + E2/E1 label 按目标区分 `pathLen` 组数 + 对端逐目标肉眼；本轮 B2-a 阶段标 UNKNOWN 不猜 |
| U6 | **`f2.C2` 重建的 `<msg>` 整串是否真带 `cdnbigimgurl`**（R80 Q3：整串是 StringBuilder 局部产物，扫 String 字段零命中） | 【源码确定】`f2.C2@0x2aa888` 拼串 + `g9.c` + `ot5.W`；【推断·高】对端凭 URL 拉图；无真机直接观测 | 装 E3（hook `g9.c` before 读 args[0]）后跑三态：`python tools/dev.py q 'SCD2 g9c|SCD f2C2 MSG_HAS_URL' 40`；`g9c hasBig=true` 即等价于看到重建串含 URL（覆盖 f2.C2 三分支） |
| U7 | **一手日志原文件不在工作区**：`_r78_stockcdn_realdev_clean.log` / `_r76_p3_realdev_fail1.log` / `_r79_dualsample_realdev.log` / `_r81_r80a_falsified_realdev.log` glob 全库均无 | 本文所有日志行均为 R78~R82 文档的逐行转述（已注出处），非本轮独立 grep | 主会话如需字节级复核：从历史归档/会话缓存取回上述 `.log` 原文件并 `grep`；或按 §5 重跑三态样本、用 `python tools/dev.py q` 现拉新日志（新日志为第一手） |
| U8 | **本地原图存在性**：216b 桶已知无 335644B，但 7ccd 桶（`7ccddbfb...jpg`=15906B）与 `f64b...` 图原图是否存在未只读核 | R85 §5 U1/U4/U5：7ccd 真桶未 ls、错桶 b1/0e 已证无对照；f64b 未查 | 主会话 su 只读：`ls -l /data/user/0/com.tencent.mm/MicroMsg/89476c7fc82a9f977bc8a901d68b7d37/image2/7c/cd/` 与 `.../f6/4b/`；若有 ≫15KB 原图 ⇒ 该图属「可本地救回」，与 B2 前提无关需单列；若无 ⇒ 与 B 同族 |
| U9 | **`ok0/x0.onC2CUploadCompleted` 回包字段**（filemd5/hitCache/existOnSvr/fileid）—— B2 成败的权威判据，历史从未被直接 hook | 零数据（R81/R82 均指出这是最优探针落点但没挂） | 装 E1 后跑三态：`python tools/dev.py q 'SCD2 x0cb' 60`；这是 §6 go/no-go 门槛 1/2/4 的判定来源，必须采到，采不到则 B2-a 不算完成 |
| U10 | **R80(a) 残留写 hook 是否在取证包中已摘除/门控**（态②样本污染风险） | 无数据；【源码确定】`FileSender.kt:158-162` 模块转发必调 `arm()`、`ForwardCdnFieldHook.kt:125-152` 取到 fileId 即 `enabled=true`、`:110-115` 写字段并打 `injected`，`install()` 无开关门、`HookEntry.kt:136-138` 无条件装 | 主会话冷启取证包后 `python tools/dev.py q 'FORWARD CDN_FIELD' 40`：出现 `CDN_FIELD injected` 或 `armed fileIdLen=...` ⇒ **未处置**（态②样本作废，按 §4.0.1 补做 (a)/(b)/(c)）；仅 `arm skip reason=...` 或零命中 ⇒ 已处置（(a) 零命中、(b)/(c) 为 skip 行） |

### 7.1 取证纪律自检（本方案自身）

- 本文所有「真机坐实」均带一手出处（文档行号 / 转述日志行），且已标注一手 `.log` 当前不在工作区（U7）；未把任何 §4.2 预期行、§2.3 未观测环节写成证据。
- 三档证据分层贯穿 §1.3 / §2.1 / §2.2 / §2.3；「server 秒传」「mars 重算 md5」「跨方法时序」均标【推断·高】并给出限制。
- E1/E2/E3 是**规格**不是数据；其「零命中」的处置已写入 §4.2 判读铁律与 §7 U9，不冒充已证。
- 未改源码、未跑 gradle、未碰设备、未提交、未改规则文件；`model=hy4flash-free` 仅为会话临时规则，未写入任何文件。
- 产物 = 本文档 `docs/_r86_b2_forensics_plan.md`。

[DONE] §7 UNKNOWN 清单与补证命令

[DONE]

[DONE] §4.0/§5.1/§5.2/§6.2/§7 污染处置修订
