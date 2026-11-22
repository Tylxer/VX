# R81 裁决：R80(a) 真机证伪后的根因重裁

> 定位：R80(a)「补 fileId ⇒ 秒传原图 ⇒ 清晰」在 23:44 真机被证伪（fileId 非空 + `UploadHitCacheType=1` + `exist_whencheck=true`，但对端仍模糊）。本文裁决根因 + a/b/c 下一步 + 反向对照。
> 硬约束：只读，不碰 adb/真机。全程中文。每条结论带可自证锚点（dex code_off / 真机日志行 / 文件事实），区分【源码确定】【真机确定】【推断+置信度】。
> 三条纪律：① dex 有字符串 ≠ 业务调用（须 invoke + 调用者链）；② "A 推 B" 标假设；③ 不伪造地址，反汇编工具不足处如实标注。
> 工作目录：`C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`，源码 `wxgroup/`，dex `_cache/dex/classes*.dex`，反汇编 `python _r35_sns_dis.py body <类> <方法>`。

---

## 0. 结论速览（一句话）

【源码确定 + 真机确定，高置信】**R80(a) 补 fileId 只改到了「上传 task 的 fileId 头部」，没改到「实际上传的字节」——`field_fullpath` 仍是 15906B 缩略图，服务端返回的 `field_filemd5` 仍是缩略图 md5（R79 与 R81 完全相同 `0ef205...`），对端拉到的仍是缩略图。fileId 是「头」，`field_fullpath`+`field_filemd5` 才是「身」。stock 走 B6 是因为它**不发本地字节、只引用原图 fileId 秒传**；我方 RESEND_FILE 是**真上传缩略图字节**，补 fileId 只是给缩略图换了个「原图的帽子」。**

---

## 1. 根因：为什么 fileId 非空 + 秒传命中，对端仍模糊

### 1.1 决定性新证据：上传结果字段来自服务端，不来自 task

【源码确定】反汇编 `ok0/x0.onC2CUploadCompleted(String, CdnLogic$C2CUploadResult)V`（classes13.dex，`python _r35_sns_dis.py body ok0/x0 onC2CUploadCompleted`），逐条 `iput` 映射：

| 指令偏移 | 写入 tj/f 字段 | 来源 = C2CUploadResult 的哪个字段 |
|---|---|---|
| `0x0018` | `field_retCode` | `errorCode` |
| `0x0020` | `field_UploadHitCacheType` | **`hitCache`**（服务端判定） |
| `0x0024` | `field_fileId` | **`fileid`**（服务端返回，非 task 值） |
| `0x0028` | `field_aesKey` | **`aeskey`**（服务端返回） |
| `0x002c` | `field_filemd5` | **`filemd5`**（服务端返回） |
| `0x0030` | `field_thumbfilemd5` | `thumbfilemd5` |
| `0x003c` | `field_fileLength` | `fileSize` |
| `0x0040` | `field_midimgLength` | `midfileSize` |
| `0x004e` | `field_exist_whencheck` | **`existOnSvr`** |

**这推翻了 R80 Q1 的隐含前提**：R80 以为「补 task.field_fileId ⇒ 消息 xml 里就有原图 fileId」。实际上：
- 我补的是 **task(tj/k).field_fileId**（上传请求的输入）；
- 对端消息用的是 **tj/f.field_fileId**（上传结果），它 = `C2CUploadResult.fileid`，是**服务端回包决定的**，不直接等于我注入的值。

### 1.2 决定性铁证：`field_filemd5` 跨 R79/R81 不变

【真机确定】对照三份日志的 f2C2 结果：

| 观测 | R79 我方（fileId 空，模糊） | R81 我方（fileId 注入，仍模糊） |
|---|---|---|
| `field_fileId` 结果（前 80） | `305f...` body `4954a2f9` + uuid `c36516a6-…` | `305f...` body `0f836cfd` + uuid `7c006-…` |
| `field_filemd5` 结果 | `0ef205c165e99eb0f4a1c0084e65f1aa` | `0ef205c165e99eb0f4a1c0084e65f1aa`（**完全相同**） |
| `field_thumbfilemd5` | `f6ab3ac5c8e5ed4888458b49a0492e77` | `f6ab3ac5c8e5ed4888458b49a0492e77`（**完全相同**） |
| `UploadHitCacheType` | 3 | 1 |

- 【真机确定】`field_filemd5` 是服务端对**实际上传内容**回报的 md5。R79（空 fileId）与 R81（注入原图 fileId）**该值逐字相同** ⇒ 服务端两次处理的**内容都是同一个缩略图**，注入 fileId 没有让服务端改认「原图」。
- 【推断，高置信】`0ef205...` = 缩略图内容 md5（15906B），非原图 md5。理由：原图本地文件 `216b1f7ccd59b1268349fd0eb3c46801.jpg`（335644B）文件名即其 md5 前缀，与 `0ef205...` 不符；且 R79/R81 的 `field_fullpath` 均指向缩略图（15906B），md5 应一致。
- 【真机确定】`field_fileId` 的 body 从 `4954a2f9` 变到 `0f836cfd`（=stock 的 body），**只是客户端回显了注入的 fileId 头部**（见 1.3），不是内容身份变化——内容身份由 `field_filemd5` 锁死，它没变。

### 1.3 fileId 结构：为什么 body 变了但内容没变

【推断，中置信】`field_fileId`（微信 CDN fileId）是 `0_` + 嵌套 protobuf 头 + 尾 uuid：
- 头含 `0f836cfd`/`4954a2f9` 这类 4 字节「类型/身份」段 + `0203..`/`0204..` 若干段 + `046b..` + `0424` + 36 字节 uuid（ASCII hex）。
- 我注入的是原图完整 `$cdnbigimgurl`（194 字符），它带原图头 `0f836cfd` + 原图 uuid。
- 服务端回包 fileid = 客户端头的 `0f836cfd` 部分被回显 + **新分配的 uuid `7c006-74b9-4f91-a941`**（≠ 原图 uuid）。头回显了、内容（md5）仍是缩略图 ⇒ **服务端把我注入的 fileId 当成「新上传的头」，但实际落库内容 = 我传的缩略图字节。**

⚠️ 本条属推断：服务端内部「秒传按 md5 认内容、不按 fileId 认内容」跨进程无法本地 dex 全证，锚点是 `field_filemd5` 跨 R79/R81 不变这一**客户端可观测事实**。

### 1.4 stock 与我的真正差异（不再停在「fileId 空/非空」）

| 维度 | stock B6（清晰） | 我方 RESEND_FILE（模糊） |
|---|---|---|
| j2.B（消息 xml） | **原图 xml**（`<img aeskey=… cdnbigimgurl=… length=…>`，R78 日志 `:23-25` 有明文 aeskey） | **全新 xml**（无 cdn 字段，只有缩略图本地路径；R79 `INP j2init` str[1]=`th_216b…`、无 xml 内容） |
| field_fullpath | `882d…jpg_` = **36B 占位**（引用标记） | `a123b9b8…jpg_` = **15906B 缩略图**（真内容） |
| field_fileId（task） | 305f…（j2.O 从原图 xml 解析） | 305f…（R80a 我注入，同串） |
| 实际上传字节 | 36B 占位（秒传原图） | 15906B 缩略图（真上传缩略图） |
| field_filemd5（结果） | 未观测（R80 限制1） | `0ef205…`（缩略图） |
| 对端 | 清晰 | 模糊 |

【源码确定】`j2.O @0x2acc44` 只写 4 组字段（来自 j2.B xml）：`field_fileId`(cdnbigimgurl)、`field_aesKey`(aeskey)、`field_midFileLength`(length)、`field_totalLen`(hdlength)。**它不写 `field_filemd5`**——`field_filemd5` 由上传 NetScene 从 `field_fullpath` 本地文件**重算**（m1e 日志 `md5=` 为空即证：下发时还没算）。

【推断，高置信】综合 1.1~1.3：**stock 的「清晰」不是靠 fileId 一个字段，而是靠「j2.B 带原图完整 CDN 身份 + field_fullpath 是 36B 占位（不是真内容）⇒ 服务端走秒传引用原图」。我方的「模糊」根因是 RESEND_FILE 从缩略图重建消息 ⇒ field_fullpath 是真缩略图 ⇒ 服务端真上传缩略图字节，注入 fileId 只换头不换身。**

---

## 2. dex 取证：两条链的差异方法

### 2.1 已核锚点（本轮实跑 `_r35_sns_dis.py`，标注 dex 分片）

| 方法 | code_off | dex | 本轮结论 |
|---|---|---|---|
| `j2.O(r0;I)Z` | `0x2acc44` | classes7 | field_fileId=cdnbigimgurl(0x026c 覆盖 0x0251 的 cdnmidimgurl)、field_aesKey(0x0284)、field_midFileLength/field_totalLen(0x025e/0x0260/0x0278/0x027a)。**不写 field_filemd5** |
| `j2.<init>(II)` | `0x2ae6b4` | classes7 | j2.B = h9.getContent()（历史，未重验） |
| `f2.C2(String,int,Ltj/e,Ltj/f,Z)I` | `0x2aa888` | classes7 | 重建 `<msg><img aeskey cdnmidimgurl cdnbigimgurl cdnthumburl cdnthumbaeskey length hdlength md5 originsourcemd5>`，字段来自 tj/f（结果）+ j2.B（旧 xml，0x01bb 起 `a.c(j2.B)` 再解析） |
| `ok0/x0.onC2CUploadCompleted` | classes13.dex | **本轮新增** | 结果字段 = C2CUploadResult 回包（见 1.1 表） |
| `ok0/m1.e(tj/k)Z` | `0x7b52b4` | classes7 | 下发点（R80 已核，未重验） |

### 2.2 f2.C2 重建的消息字段（决定对端拉什么）

【源码确定】`f2.C2` 拼串常量（`0x01f5~0x021e`）＝ `<msg><img aeskey="…" cdnmidimgurl="…" cdnbigimgurl="…" cdnthumburl="…" cdnthumbaeskey="…" cdnthumblength="…" length="…" hdlength="…" md5="…" originsourcemd5="…"/>`。

关键映射（反汇编 `iget` 追踪）：
- `cdnmidimgurl` 与 `cdnbigimgurl` 都取 `tj/f.field_fileId`（结果回包的 fileid）；
- `length` ← `field_midimgLength`、`hdlength` ← `field_fileLength`、`md5` ← `field_filemd5`；
- `originsourcemd5` ← `r0.l`（或从旧 xml `g9.c` 再解析 `$originsourcemd5`）。

⇒ 【源码确定】**对端消息的 `md5` = `field_filemd5`（=缩略图 `0ef205...`），`hdlength` = `field_fileLength`（=上传内容长度）。这些字段在我方路径下都是缩略图的值，与「fileId 是否注入」无关。**

### 2.3 为什么 MSG_HAS_URL 探针零命中（R80 Q3 结论不推翻，但补一个落点）

【源码确定】R80 Q3 已证：重建串是 StringBuilder 局部产物 + 写 protobuf `ot5`，不挂 String 字段。本轮补充：**结果字段的权威来源是 `ok0/x0.onC2CUploadCompleted` 的 `C2CUploadResult` 回包**，若要看「对端真正读到什么」，最优探针不是扫 tj/f 的 String 字段，而是：
1. hook `ok0/x0.onC2CUploadCompleted` 的 `afterHookedMethod`，读 `param.args[1]`（C2CUploadResult）的 `fileid/filemd5/hitCache/existOnSvr` —— 一处覆盖 R79/R81 的全部差异；
2. 或 hook `f2.C2` 内 `g9.c` 入参（R80 3.3 已给）。

---

## 3. 最终 a/b/c 下一步

### (a) 补字段最小改动 —— 【判死，不再作为首选】

【源码确定 + 真机确定】R80(a)「只补 fileId/aesKey」已证伪，且证伪原因不是「补错字段名」，而是**补的字段对结果无决定权**：结果 fileId/filemd5 由服务端回包决定，回包内容由 `field_fullpath` 的字节决定。因此：
- 补 `field_fileId` → 结果 fileId 头变了、内容没变（1.2 铁证）；
- 若加补 `field_filemd5` → 会被上传 NetScene 从 `field_fullpath` 重算覆盖（m1e 日志 `md5=` 空即证下发后才算），补了也白补；
- **唯一能让 (a) 成立的条件 = 同时把 `field_fullpath` 指向原图字节**，但那就不是「补字段」，而是「换上传内容」，落入 P3 方向（见下）。

### (b) 复刻 B6 原生转发链 —— 【降级为「内容正确」的务实等价，不整套复刻】

【推断，中高置信】R80 否决「整套复刻 B6」的理由是「checkExist task 与上传 task 是两条、两个 listener，复刻极易挂错回调」。本轮证伪后，(b) 的**目标**应从「照抄 B6 的 checkExist 步骤」收敛为**「让上传内容 = 原图字节」**，两条等价路径：

1. **P3 字节落盘（已有代码在树）**：`field_fullpath` 指向本地大图（335644B 原图，23:44 已存在 `image2/21/6b/216b1f7c….jpg`）。FileSender 现有第 6 参 `bigImgPath` + `pickImageFile` 已具备「挑大图、滤 36B」能力，只需确认转发时**大图已可拿到**（23:44 mtime 显示已落盘，但 23:13 曾 `bigimg ready=false`，是下载未完成——时序问题，非链路不可行）。
2. **checkExist 秒传引用（B6 真身）**：让客户端**只发 fileId + aeskey + 原图 md5，不上传字节**。这要求补 `field_onlycheckexist=true` + `field_filemd5=原图 md5`，并让 NetScene 走「hitCheck」而非「上传」。⚠️ 本轮未追到 NetScene 里 hitCheck 的完整分支（反汇编工具对 `a13/j0.doScene`/`a13/f1` 的 `te0/q`/`q54/la0` 链只追到一半），**此路径的精确落点仍缺 2 个钩子位，不冒充已定。**

**推荐顺序**：先验 (b)-1（P3 字节落盘，代码已在树、改动面小、可复用 FileSender 第 6 参），再评估 (b)-2（秒传引用，需补 NetScene 取证）。

### (c) 务实降级 —— 【立即可用，与 (b) 并行】

【真机确定】零代码：坏图走微信原生转发（长按→转发）已两次证清晰（R78 22:49 / R80 对照 A）。模块侧加「坏图检测提示」，检测判据可复用 `field_filemd5`/`field_fullpath` 缩略图特征，提示「原图存云端，建议用系统转发获得清晰图」。

### 推荐（一句话）

**放弃「补 fileId」这条路（已证伪）。首推 (b)-1 P3 字节落盘（把原图 335644B 字节真正发出去）；(c) UX 提示作为不依赖任何代码改动的兜底并行落地；(b)-2 秒传引用作为「原图本地拿不到」时的退路，需先补 NetScene 取证。**

---

## 4. 反向对照设计（一锤定音）

> 原则：每条判据是「用户能看见的事实」或「客户端可观测的字段值」，不是内部计数器；互斥判据并列成互斥格，不写成「都看」。

| 判据 | 观测（hook 后重跑我方转发同一张 216b） | 裁决 |
|---|---|---|
| **P1 内容判据**（决定性） | `ok0/x0.onC2CUploadCompleted` 回包的 `C2CUploadResult.filemd5` | `=0ef205…`（缩略图）⇒ 内容没变，任何「补 fileId」类方案都判死；`≠0ef205…`（原图 md5）⇒ 内容换成功，方向对 |
| **P2 字节判据** | 上传 task 的 `field_fullpath` 指向文件大小 | `≈15906B` 缩略图 ⇒ 仍在传缩略图；`≈335644B` 原图 ⇒ 字节落盘生效（(b)-1） |
| **P3 秒传判据**（(b)-2 专用） | task 的 `field_onlycheckexist` 与 `field_filemd5` | `onlycheckexist=true` 且 `filemd5=原图 md5` ⇒ 走秒传引用；否则仍在上传 |
| **P4 对端事实** | 用户肉眼看对端收到的图 | 清晰 ⇒ 方案成立；模糊 ⇒ 方案失败（**唯一终极判据，P1~P3 都是它的代理**） |

**互斥裁决表**（一次跑完）：

| P1 filemd5 | P2 fullpath | P4 对端 | 结论 |
|---|---|---|---|
| 原图 md5 | 原图 335KB | 清晰 | ✅ (b)-1 成立 |
| 原图 md5 | 缩略图 15KB | 清晰 | 秒传引用生效，(b)-2 成立 |
| 缩略图 md5 | 缩略图 15KB | 模糊 | 内容未换（现状），方案未生效 |
| 缩略图 md5 | 原图 335KB | 模糊 | fullpath 换了但上传被拒/重算，查 NetScene |

---

## 5. 纪律与限制（如实标注）

- 【源码确定】1.1（onC2CUploadCompleted 映射表）、2.1/2.2（j2.O / f2.C2 字段读写）——本轮从 classes7.dex / classes13.dex 二进制实跑 `_r35_sns_dis.py` 自算，非引用历史。
- 【真机确定】1.2（field_filemd5 跨 R79/R81 逐字相同、UploadHitCacheType 3→1、field_fileId body 变化）——取自 `docs/_r79_dualsample_realdev.log` 与 `docs/_r81_r80a_falsified_realdev.log`。
- 【推断，高置信】「服务端按内容 md5 认图、fileId 只是头」——跨进程无法本地 dex 全证，锚点是 field_filemd5 不变这一客户端可观测事实。
- 【推断，中置信】1.3 fileId 结构（头回显 + 新 uuid）、2.3「结果字段来源 = C2CUploadResult」对「对端消息 = ot5 子字段」的传导。
- 【未完成取证】NetScene 上传链 `a13/j0.doScene`/`a13/f1` 的 hitCheck 分支（(b)-2 依赖）只追到一半，`te0/q`→`q54/la0` 字段映射未闭环，**不冒充已定**。若派 (b)-2，需先补这两个钩子。
- 纪律③：本轮不引用单测；R80a 的 10 例单测覆盖的是「hook 后 field_fileId 非空」，不覆盖「结果 field_filemd5 不变」，故单测绿 ≠ 真机对。

[DONE]
