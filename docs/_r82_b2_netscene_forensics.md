# R82(b)-2 取证：微信 NetScene 上传链 hitCheck 分支 / 秒传引用精确 hook 落点

> 定位：为 (b)-2「只发 fileId+aeskey+原图 md5、不发字节秒传引用原图」补全 R81 未闭环的 NetScene 取证（`a13/j0.doScene` / `a13/f1` / `te0/q`→`q54/la0`），拿到精确 hook 落点 + 字段映射。这是实现前取证，不改源码/不碰真机。
> 硬约束：只读。每条结论带可自证锚点（dex code_off / 指令 / 调用者链 / 真机日志行），区分【dex 实证】【真机确定】【推断+置信度】。
> 纪律：dex 有字符串 ≠ 业务调用（须 invoke + 调用者链）；"A 推 B" 标假设；不伪造地址，工具读不到处如实标注。
> 反汇编：`cd ROOT && python _r35_sns_dis.py <mode> <key> [name]`。偏移口径：`body` 打印的 `0xXXXX` 是**指令索引 i（code unit）**，`walk()` 里 `at = code_off + 16 + 2*i` ⇒ **绝对字节偏移 = code_off + 16 + 2*i**。本文同时标注两者，防历史「code_off 与指令偏移+16 混用」坑。

（取证进行中，逐题追加）

---

## Q1. NetScene 上传的入口与分支（含 hitCheck 真相）

### Q1.1 真实链路（调用者链，全 dex invoke 实证）

图片上传（我方 RESEND / stock B6 共用）的真实链路：

```
app/t3.doScene (NetSceneUploadAppAttach, co=0x4d00f8, classes7)   [图片附件]
   └─0x037c invoke mq/k.B8(Ltj/k;)Z
        └─ mq/k.B8 (co=0x1ec? classes7) : 仅 delegate
             └─0x0004 invoke ok0/m1.e(Ltj/k;)Z   ← R81 已证下发点 @0x7b52b4
                  └─0x0048 new ok0/h1(m1, task); 提交线程池; → ok0/m1.m(Z) @0x7b6700
                       └─0x0129 invoke ok0/x0.u(Ltj/k;)I   (classes13, co=0x3a98cc)
                            ├─构造 CdnLogic$C2CUploadRequest, setFilePath(fullpath)
                            ├─0x0102 invoke CdnLogic.startSSUpload(...)   (sendmsgFromCDN=true)
                            └─0x0107 invoke CdnLogic.startC2CUpload(...)  (否则)
```

- 【dex 实证】`mq/k.B8` 方法体只有 3 条指令：`invoke-static ok0/s2.e9()→ok0/m1; ; invoke-virtual ok0/m1.e(task); return`。**B8 是纯 delegate，真正下发点是 `ok0/m1.e`。
- 【dex 实证】`ok0/m1.e` @code_off 0x7b52b4：`if-nez field_fileId` 才保留注入值（0x0020 op=0x39），空则清空为''；`e` 布尔 = isSend。**R80a 注入的 fileId 在 m1.e 里是被保留的（非空跳过清空）**——这反驳了「m1.e 抹掉 fileId」的假设。
- 【dex 实证】`ok0/h1.run` @0x7b37c0：把 task.mediaId 入队 `m1.q(LinkedList)` + `m1.r(HashMap)`，然后 `m1.m(Z)` 排空队列。
- 【dex 实证】`ok0/m1.m` @0x7b6700 是总分派器：根据 task 的 appType/fileType/isSend(Ltj/e vs Ltj/m)/field_fullpath 是否空，分派到 `x0.u`(upload)/`x0.q`(url download)/`x0.n,o,p,j,l,m,t`(各种 download)。上传必走 `x0.u(task)`（0x0129）。

**结论**：我方与 stock 进入 NetScene 的入口完全相同（`app/t3.doScene → mq/k.B8 → ok0/m1.e → m1.m → ok0/x0.u`）。`a13/j0` 是**下载** NetScene（NetSceneDownloadAppAttach），不是上传；R81 把它列为「上传链 a13/j0.doScene」是误归类——它只在「收方复写/下载」时用，本场景（发方秒传）不走它。`a13/f1.C2`/`a13/f2.C2` 是 Ltj/i 上传完成回调 listener（`f2` 是我方 m1e 日志里挂的 listener）。

### Q1.2 hitCheck / onlyCheckExist 分支真相（决定性）

【dex 实证】`ok0/x0.u` (co=0x3a98cc, classes13) 构造 `CdnLogic$C2CUploadRequest` 的完整字段映射：

| task(tj/k) 字段 | code_off 指令偏移 | → C2CUploadRequest 字段 |
|---|---|---|
| field_mediaId | 0x0005 | fileKey |
| field_fullpath | 0x0009 | setFilePath() ← **字节来源** |
| field_thumbpath | 0x000e | setThumbfilePath() |
| field_fileType | 0x0013 | fileType |
| field_aesKey | 0x0017 | forwardAeskey |
| field_fileId | 0x001b | forwardFileid ← **转发引用的 fileId** |
| field_talker | 0x0027 | toUser |
| field_sendmsg_viacdn | 0x002f | sendmsgFromCDN（决定 startSSUpload vs startC2CUpload）|
| field_onlycheckexist | 0x0063 | **checkExistOnly** |
| field_enable_hitcheck | 0x0085 | **enableHitCheck** |
| field_fake_bigfile_signature | 0x00a2 | fakeBigfileSignature |
| field_fake_bigfile_signature_aeskey | 0x00ae | fakeBigfileSignatureAeskey |
| field_svr_signature | 0x0096 | bigfileSignature |

**关键：0x0009 setFilePath(field_fullpath) 无条件执行；0x0063/0x0085 把 onlycheckexist/enable_hitcheck 透传给 mars 的 checkExistOnly/enableHitCheck。**

【dex 实证】`C2CUploadRequest` 有 `filemd5` 字段，但 `ok0/x0.u` **从未 set 它**（全 dex `setFilemd5` 的调用者只有 protobuf 的 IlinkC2CDownloadOriginResult，与上传无关）⇒ **请求侧 filemd5 由 mars native 从 filePath 文件字节重算**。这坐实 R81「补 filemd5 会被重算覆盖」。

【真机确定 + dex 实证】矛盾消解：22:49 stock 清晰 vs 23:44 我方模糊，m1e 时 **onlycheckexist 都是 false、fileId 都是同一串 305f...**（日志行 36 / 行 8）。所以「不发字节只引用」**不是靠 onlycheckexist=true**：
- 真正的分水岭是 `field_fullpath` 的内容——stock=36B 占位文件（`882d...jpg_`），我方=15906B 缩略图（`a123...jpg_`）。
- stock 的 36B 占位文件经 `setFilePath` 交给 mars，mars 的 `enableHitCheck` 用它算 md5 → 命中云端原图 → 只引用不传字节（`UploadHitCacheType=3`、`exist_whencheck=true`）。
- 我方传 15906B 缩略图 → hitCheck 命中缩略图 → 真上传缩略图 → 模糊（`UploadHitCacheType=1`，R81 注入后）。

⇒ **R81 §3(b)-2 里「补 onlycheckexist=true + filemd5=原图md5」的假设，前半（onlycheckexist）被真机证伪（stock 自己就是 false），后半（filemd5）被 dex 证伪（请求 filemd5 由 mars 从 filePath 重算，Java hook 改 task.field_filemd5 不进 C2CUploadRequest）。**

---

## 专题：m1e 三字段逐字相同但结果不同（本轮成败关键）

> team-lead 逐字复核补充：stock 清晰与 R80(a) 模糊样本的 `SCD m1e task` 行，fileId / onlycheckexist / md5 三字段**逐字相同**。本节专门解释这个矛盾，并明确「不发字节只引用原图」到底由什么决定。

### Z.1 逐字复核（真机确定，本轮亲自 grep 四份日志）

| 时间 / 样本 | `SCD m1e task` 行原文 | 结果 |
|---|---|---|
| 02:42 stock（_r77） | `fileId=305f...626230 md5= onlycheckexist=false` | 清晰 |
| 22:49 stock（_r78 clean） | `fileId=305f...626230 md5= onlycheckexist=false` | 清晰 |
| 23:13 我方早期（_r79） | `fileId=（空） md5= onlycheckexist=false` | 模糊 |
| 23:44 我方 R80a 注入后（_r81） | `fileId=305f...626230 md5= onlycheckexist=false` | 模糊 |

四行的 fileId 串在 02:42 / 22:49 / 23:44 **逐字完全一致**（`305f020100044b304902010002040f836cfd02032f56c10204d1e5e77302046b0010260424326230`），onlycheckexist 全 `false`，md5 全空。

⇒ **决定性推论**：`m1e` 能读到的这三个字段**对「清晰/模糊」零区分力**——它们相同，结果却相反，所以决定量必然在 m1e 探针**没 dump 的字段**里。R81 §问题2 把突破口押在「onlycheckexist 改 true / 补 filemd5」上，方向从根上就是错的（stock 自己就是 false + 空 md5 却清晰）。

### Z.2 差异到底在哪：field_fullpath 的内容（真机确定，硬差异）

同样四份日志的相邻行 `SCD m1e in task ... field_fullpath=`：

| 时间 / 样本 | field_fullpath | 文件大小 | 结果 |
|---|---|---|---|
| 22:49 stock | `.../image2/88/2d/882d5de0e53e75a4c88fcbea0efd75a6.jpg_` | 36B 占位 | 清晰 |
| 23:13 我方 | `.../image2/c8/1a/c81ae4ab9a3b6af956def2cd2b0ab4db.jpg_` | 缩略图 | 模糊 |
| 23:44 我方 | `.../image2/a1/23/a123b9b8725858a5ad3e29e12efc708b.jpg_` | 15906B 缩略图 | 模糊 |

**这是 m1e 相邻行里唯一与结果严格 correlated 的硬差异**：三个字段逐字相同，fullpath 指向的内容（36B 占位 vs 15906B 缩略图）一变，结果就从清晰变模糊。

### Z.3 反汇编确认：请求侧 filemd5 是否从 field_fullpath 计算（dex 实证）

补跑 `ok0/x0.u`（co=0x3a98cc, classes13）全指令，筛 md5/path/digest：

```
0x0007 iput-object C2CUploadRequest->fileKey          ← task.mediaId
0x000b invoke-virtual C2CUploadRequest.setFilePath()  ← task.field_fullpath（唯一字节入口）
0x0010 invoke-virtual C2CUploadRequest.setThumbfilePath()
0x0019 iput-object C2CUploadRequest->forwardAeskey    ← task.field_aesKey
0x001d iput-object C2CUploadRequest->forwardFileid    ← task.field_fileId
0x0065 iput-boolean  C2CUploadRequest->checkExistOnly ← task.field_onlycheckexist
0x0087 iput-boolean  C2CUploadRequest->enableHitCheck ← task.field_enable_hitcheck
```

**关键：过滤 `md5` 零命中——`ok0/x0.u` 连 `task.field_filemd5` 都不读，更不会 set `C2CUploadRequest.filemd5`。** 结合 Q2 已证（全 dex `setFilemd5` 无上传调用者），铁证链闭合：

1. 【dex 实证】请求侧 filemd5 **不在 Java 层产生**：task.field_filemd5（来自 xml）在这个方法里被完全忽略。
2. 【dex 实证 + 推断，高置信】`C2CUploadRequest.filemd5` 由 mars native 从 `setFilePath()` 指向的**文件字节**计算（这是它唯一的字节输入）。
3. ⇒ **「请求侧 filemd5 从 field_fullpath 计算」成立**——但计算发生在 mars native，Java hook 改 `task.field_filemd5` 无从介入。这正是 R81「补 filemd5 会被覆盖」的真正机制。

### Z.4 「不发字节只引用原图」由哪个字段/分支决定（判死：无此 Java 开关）

【dex 实证 + 真机确定，高置信】**不存在一个「设 true 就不发字节」的 Java 字段/分支**。stock 与 my 的 m1e 三字段逐字相同（含 onlycheckexist=false、enableHitCheck 也无差异证据），唯一差异是 fullpath 内容。机制是：

```
field_fullpath（36B 占位 / 15906B 缩略图）
   └─setFilePath → mars enableHitCheck → 从该文件字节算 md5 → 服务端 hitCheck
        ├─命中云端原图（36B 占位触发）→ UploadHitCacheType=3, exist_whencheck=true → 只引用，对端清晰
        └─命中缩略图（15906B 触发） → UploadHitCacheType=1 → 真传缩略图字节，对端模糊
```

- `onlycheckexist`（→checkExistOnly）：真机恒 false，**不是**开关（Z.1）。
- `enable_hitcheck`（→enableHitCheck）：透传给 mars 参与 hitCheck，但它**不决定**「引用哪张图」——引用哪张由 fullpath 字节算出的 md5 决定。
- `field_fileId`/`field_aesKey`：在 `ok0/x0.u` 里只是 `forwardFileid`/`forwardAeskey`（转发头），mars 仍要配 fullpath 字节；R80a 已证「只换头不换身」。

**判死铁证（三条）**：① 三字段逐字相同而结果相反（Z.1 真机四行）；② `ok0/x0.u` 连 field_filemd5 都不读、request.filemd5 由 fullpath 字节算（Z.3 dex）；③ 唯一 correlated 差异是 fullpath 内容 36B vs 15906B（Z.2 真机）。⇒ **stock 也是靠 fullpath 指向占位触发服务端秒传，没有「只发 fileId/md5 不发字节」的开关可hook；(b)-2 按 R81 设计判死。** 可复刻的语义路径是 B6 checkExist 链（见 Q4.1），且它最终仍要落到「消息 xml 带原图 cdnbigimgurl」（R79 定案）。

---

## Q2. filemd5 从哪来、能否伪造

### Q2.1 请求侧（task）filemd5 的两个来源

【dex 实证】存在**两条**上传 task，filemd5 来源不同：

1. **B6 checkExist task**（`MsgRetransmitUI.B6` @0x62c98c）：
   - `0x014e` `.msg.img.$md5` ← `g9.c(r0.u,"msg","")` 解析坏图 xml（`r0.u`=消息 content）
   - `0x01b1` `field_filemd5 ← v0`（即 xml 的 $md5，原图 md5 前缀 305f 那条 xml 里带）
   - **这个 task 不带 field_fullpath**（不上传本地字节），只带 fileId/aesKey/md5 → 探服务端「原图在不在」
2. **t3.doScene 上传 task**（`NetSceneUploadAppAttach` @0x4d00f8）：
   - `0x0288` `v12 ← te0/q.s`（te0/q = `te0/q.u(content xml)` 解析结果）
   - `0x0292` `field_filemd5 ← v12`（**来自消息 xml 解析，不是本地文件重算**）
   - `0x02bb` `field_fullpath ← Ljj/q.field_fileFullPath`（本地字节源）

⇒ 【dex 实证】task 的 `field_filemd5` 初值来自**消息 xml**（`$md5` / `te0/q.s`），R81「请求 filemd5 从 fullpath 重算」的推断**在 Java task 层不成立**——真正在 mars 层重算的是 `C2CUploadRequest.filemd5`。

### Q2.2 能否伪造（关键判定）

【dex 实证】`ok0/x0.u` (co=0x3a98cc) 构造 `CdnLogic$C2CUploadRequest` 时：
- 设了 `fileKey/forwardFileid/forwardAeskey/setFilePath/.../checkExistOnly/enableHitCheck/fakeBigfileSignature...`（见 Q1.2 表）
- **没有调用 `setFilemd5()`**，`C2CUploadRequest.filemd5` 保持默认 null。
- 【全 dex 实证】`C2CUploadRequest.setFilemd5(String)` 的调用者只有 protobuf 的 IlinkC2CDownloadOriginResult（下载），与上传无关。

⇒ 【dex 实证，高置信】上传请求的 filemd5 **由 mars native 层 `startC2CUpload`/`startSSUpload` 从 `setFilePath()` 指向的文件字节重算**。Java 层 hook 改 `task.field_filemd5` **不会**进入 C2CUploadRequest，因此「把 filemd5 改成原图 md5」在上传引擎层面无效——除非同时让 `setFilePath()` 指向的就是原图字节文件（那已落入 (b)-1 P3 字节落盘）。

【可自证锚点】
- `ok0/x0.u` code_off 0x3a98cc，指令 0x0009 `setFilePath(field_fullpath)`，全程无 setFilemd5（`python _r35_sns_dis.py body ok0/x0 u`）。
- `C2CUploadRequest` 字段表含 `filemd5`（`fields 'Lcom/tencent/mars/cdn/CdnLogic$C2CUploadRequest;'`）。
- `setFilemd5` 调用者扫描：`call setFilemd5` 无上传链命中。

---

## Q3. te0/q → q54/la0 字段映射（R81「未闭环」的真相对：这条链在上传里不存在）

【dex 实证，调用者链】`call Lq54/la0;` 全 dex 只有 `a13/j0` 的 3 个构造函数 `new q54/la0`，无其它类引用。`call Lte0/q;` 显示 te0/q 被 `t3.doScene`/`a13/j0.doScene`/`a10/i.run` 等通过 `te0/q.u(xml)` 使用。**te0/q 与 q54/la0 之间没有任何 invoke 关系**。

⇒ 【dex 实证】R81 §5「te0/q → q54/la0 字段映射只追到一半」是把两条独立链误连：
- **te0/q**（co=0x8c2458 `u(String)`，classes7）= AppMessage 消息 xml 解析器（`g9.c(xml,"msg","")`），被上传 NetScene `t3.doScene` 和下载 NetScene `a13/j0.doScene` 共用，用来从 content xml 解析 `cdnbigimgurl(J)/aeskey(R)/md5(s)/length(l)/filetype(t)` 填进 `tj/k`。
- **q54/la0**（co=0x8077a0 compareContent / 0x80796c op，classes7）= **NetSceneDownloadAppAttach 的 protobuf 请求体**（`a13/j0` 内部 `check-cast → q54/la0`，写 g/h/i/j/p/r 字段后 `dispatch()`）。它属于**下载** CGI（`startSNSDownload`/`dispatch`），**不在上传链**。

### 真正决定「发不发字节 / 用哪个 fileId/md5」的字段（全量）

综合 Q1.2 + Q2 + B6/t3 反汇编，上传到 mars 的字段全集（在 `ok0/x0.u` code_off 0x3a98cc）：

| 决定量 | task 字段 | → C2CUploadRequest | 值来源（dex 锚点） |
|---|---|---|---|
| 字节源 | field_fullpath | filePath (0x0009) | stock=36B占位 / 我方=15906B缩略图（真机 :37/行9） |
| 引用 fileId | field_fileId | forwardFileid (0x001b) | 消息xml `cdnbigimgurl`（B6 0x01a7 / t3 0x028c） |
| 引用 aeskey | field_aesKey | forwardAeskey (0x0017) | 消息xml `$aeskey`（B6 0x01ad / t3 0x0290） |
| md5 | field_filemd5 | **不进 request**，mars从filePath重算 | task 初值=xml md5（B6 0x01b1/t3 0x0292），但被忽略 |
| 秒传开关 | field_enable_hitcheck | enableHitCheck (0x0085) | t3.o 构造入参（t3 0x02f0-0x02f2） |
| 存在性检查 | field_onlycheckexist | checkExistOnly (0x0063) | 真机恒 false（stock & 我方）|
| 假签名 | field_fake_bigfile_signature | fakeBigfileSignature (0x00a2) | Ljj/q.field_fakeSignature |
| 假签名key | field_fake_bigfile_signature_aeskey | fakeBigfileSignatureAeskey (0x00ae) | Ljj/q.field_fakeAeskey |
| 大文件签名 | field_svr_signature | bigfileSignature (0x0096) | Ljj/q.field_signature |
| 走SS/C2C | field_sendmsg_viacdn | sendmsgFromCDN (0x002f) | 决定 startSSUpload vs startC2CUpload |

⇒ **「发不发字节」的决定量 = `field_fullpath` 指向的文件内容 + `enableHitCheck`。mars 从 filePath 算 md5 做 hitCheck：命中云端原图→只引用（stock 36B 占位触发）；命中缩略图→真传缩略图（我方）。onlycheckexist/filemd5 不是决定量。**

---

## Q4. 精确 hook 落点清单 + 可行性判定

### Q4.0 判定（先给结论）

**【dex 实证 + 真机确定，高置信】(b)-2 按 R81 的原始设计（补 `onlycheckexist=true` + `filemd5=原图md5` 让上传引擎秒传引用）不可行，判死。** 铁证三条：
1. `field_onlycheckexist` 决定量被真机证伪：22:49 stock 清晰时 `onlycheckexist=false`（R78 log 行36），与模糊的我方完全相同。它不是「不发字节」开关。
2. `field_filemd5` 决定量被 dex 证伪：`ok0/x0.u` (0x3a98cc) 构造 `C2CUploadRequest` 时**不调 setFilemd5**，请求 md5 由 mars 从 `setFilePath(fullpath)` 文件字节重算。hook 改 `task.field_filemd5` 不进引擎。
3. 「不发字节」的真正决定量 = `field_fullpath` 的内容：stock=36B 占位（mars hitCheck 命中云端原图），我方=15906B 缩略图（hitCheck 命中缩略图）。这个字段无法靠「只填 fileId/md5」绕过——只要 fullpath 指向缩略图，mars 就传缩略图字节。

**但**：stock 确实存在「只发 CDN 参数、不发字节」的合法机制 = B6 的 **checkExist task**（dex 全可见、可复刻），所以 (b)-2 的**目标语义**可以借 B6 链实现——只是它不等价于「改上传 task 字段」，而是「复刻 B6 的 checkExist→建新记录→带原图 cdnbigimgurl 的转发消息」。这条已在 R79 §4.3 给出落点，本次取证**完全印证**，并补足 R81 缺的 2 个钩子位（见下）。

### Q4.1 若坚持「复刻 B6 秒传引用」的最小 hook 规格（可直接给实现 lane）

> 目标：让转发消息 xml 带原图 cdnbigimgurl（R79 定案的决定量），靠服务端已有原图秒传，不发本地缩略图字节。

| # | hook 方法（类+签名） | code_off / dex | 时序 | before/after 操作 | 值来源 |
|---|---|---|---|---|---|
| H1 | `com.tencent.mm.ui.transmit.MsgRetransmitUI.B6(String,int,t0)V` | 0x62c98c / classes7 | B6 入口 | before：只读解析 `b1.a3(talker,msgSvrId)→r0`；`g9.c(r0.u,"msg","")` 取 `.msg.img.$cdnbigimgurl/$aeskey/$md5`（**样本闸门用 `INP j2init ori=.../216b...jpg` + `m.a=883190`，不用 r0.u contains 216b**） | 坏图 216b 自身消息 xml |
| H2 | `mq.k.B8(tj/k)Z`（或 `ok0.m1.e(tj/k)Z` @0x7b52b4） | mq/k.B8 classes7 / m1.e 0x7b52b4 | checkExist task 下发 | before：构造 task（listener=自建 Ltj/i，不依赖 Activity）写 `field_fileId=cdnbigimgurl`、`field_aesKey=$aeskey`、`field_filemd5=$md5`、`field_mediaId=checkExist_clientid`、**不设 field_fullpath**（关键：不发字节）；`field_onlycheckexist` 保持默认 false（真机值） | H1 解析值；clientid=`n1.b("checkExist",msgId,"","")` |
| H3 | `b1.j6(String,[B,I,J,Z,String,PString,PInt,PInt)J`（自建 listener 的 C2 内触发） | b1.j6 0x2a74f8 / classes7 | 秒传命中回调 `field_exist_whencheck=true` 时 | after：读新 localId；确认新记录 xml 带原图 cdnbigimgurl（只读探针 P2 验证） | qf4/j0.C2 回包 tj/f |
| H4（补 R81 缺位之一） | `ok0.x0.u(tj/k)I` | 0x3a98cc / classes13 | 上传 task 构造 C2CUploadRequest（after） | **只读探针**：after 读 `C2CUploadRequest.checkExistOnly/enableHitCheck/forwardFileid/filePath` 打日志；确认 filePath=36B 占位、forwardFileid=原图 | task 字段 |
| H5（补 R81 缺位之二） | `ok0.x0.onC2CUploadCompleted(String,C2CUploadResult)V` | 0x3aa9bc / classes13 | 上传结果回包 | **只读探针**：after 读 `C2CUploadResult.filemd5/hitCache/existOnSvr/fileid` —— R81 裁决的权威判据字段 | 服务端回包 |

**关键落点说明**：
- H2 是 R81 说的「缺 2 个钩子位」里真正缺的——它是**新建一条 checkExist task**（`new tj/k`，`task_MsgRetransmitUI_2`），与我方 RESEND 的上传 task（listener=f2）是两条独立 task、两个 listener。R80 曾担忧「复刻极易挂错回调」，正解是自建 Ltj/i listener（H3），不复用宿主 qf4/j0。
- H4/H5 是本轮新补的取证探针落点（R81 完全没追到 `ok0/x0.u` 和 C2CUploadRequest），它们坐实了「filemd5 不进 request」「onlycheckexist 恒 false」两条判死铁证。
- **不建议** hook `ok0/x0.u` 去强改 `C2CUploadRequest.filemd5`（setFilemd5）——因为即便设了原图 md5，fullpath 仍指向缩略图，mars 仍传缩略图字节；且会破坏 sendmsgFromCDN 分支。

### Q4.2 与 (b)-1（P3 字节落盘）/ (c) 的关系

- (b)-2 的语义（不发缩略图、让对端拿原图）**已由 stock B6 链实现**，且 R79 已把我方链路改成走该链（`o2(源localId)`、ForceForward/FileSender 已就位）。本轮取证表明：**剩余问题不在上传引擎 hook，而在转发消息 xml 是否带原图 cdnbigimgurl**——这与 R79 结论一致。
- 因此 (b)-2 应**收敛并入 R79 的 B6 复刻方向**，不再作为独立「改上传 task 字段」方案。若实现 lane 要验证，用 Q5 只读探针确认 H2 的 checkExist 是否命中原图即可。
- 若 checkExist 命中后对端仍模糊 → 查 H3 建的新记录 xml 是否带 cdnbigimgurl（R81 §1.4 的核心）。

---

## Q5. 只读探针判据（规格，不写代码；范式照抄 StockCdnProbe.kt）

> 前缀建议 `SCD2`，沿用 LogX/WXGroup tag。只读纪律：before/after 只打日志，绝不写 param/调宿主业务方法；enabled 广播开关（默认 false）；每个 hook 用 guard 包；String take(80)、路径 take(120)、fileId 只打长度+前缀不打全串。

### 探针 P1（判「checkExist 是否走通 + 是否命中原图」）—— 最重要

- hook：`qf4.j0.C2(String,int,tj/e,tj/f,boolean)I` @0x84307c（checkExist 完成回调，classes7）
  - before：`param.args[3]`（tj/f 结果）读 `field_exist_whencheck`（boolean）、`fileId`（长度+前缀）、`filemd5`、`field_thumbimgLength`；打印 `SCD2 j0C2 exist_whencheck=? fidLen=? md5=?`
  - after：读 `param.result`（long=新 localId）若 `exist_whencheck=true`（即 "support second !!" 分支）→ 打印 `SCD2 j0C2 HIT newLocalId=?`（这是秒传命中原图的直接证据）
- hook：`ok0.x0.onC2CUploadCompleted(String,C2CUploadResult)V` @0x3aa9bc（classes13）
  - after：`param.args[1]`（C2CUploadResult）读 `filemd5/hitCache/existOnSvr/fileid`；打印 `SCD2 x0cb md5=? hit=? exist=? fidLen=?`
  - **判据**：`md5=原图md5前缀(非0ef205)` ⇒ 内容换成功；(b)-2/（B6 复刻）方向对；`md5=0ef205...` ⇒ 仍在传缩略图

### 探针 P2（判「是否真不发字节 / 用哪个 fileId」）

- hook：`ok0.x0.u(tj/k)I` @0x3a98cc（classes13，upload request 构造）
  - before：`param.args[0]`（tj/k）读 `field_fullpath`（长度+尾缀）、`field_fileId`（长度+前缀）、`field_filemd5`、`field_onlycheckexist`、`field_enable_hitcheck`、`field_fake_bigfile_signature`（是否空）；打印 `SCD2 x0u in fullpathLen=? fidLen=? md5=? only=? hit=? fakeSigEmpty=?`
  - after：hook 返回前拿不到 C2CUploadRequest（局部变量），改为 hook `CdnLogic.startC2CUpload` / `startSSUpload`（native static）：
    - before：`param.args[0]`（C2CUploadRequest）读 `filePath`/`forwardFileid`（长度+前缀）/`checkExistOnly`/`enableHitCheck`/`filemd5`（是否 null）；打印 `SCD2 c2c pathLen=? forwardFidLen=? checkExistOnly=? enableHit=? reqMd5=?`
    - **判据**：`reqMd5=null` ⇒ 印证 filemd5 由 mars 重算（判死铁证）；`pathLen≈36` ⇒ stock 占位模式；`pathLen≈15906` ⇒ 仍传缩略图
- 配合现有 `SCD-m1e`（StockCdnProbe 已有）：`onlycheckexist=false` 在 stock 与我方都应复现。

### 互斥裁决表（一次跑完，用户可见事实 / 客户端字段）

| P1 md5 | P1 exist_whencheck | P2 pathLen | 对端 | 裁决 |
|---|---|---|---|---|
| 原图md5 | true | ≈36B | 清晰 | ✅ B6 checkExist 秒传引用成立 = (b)-2 语义达成 |
| 原图md5 | true | ≈15906B | 清晰 | 字节也传了但内容对（(b)-1 生效），(b)-2 非必要 |
| 0ef205 | false | ≈15906B | 模糊 | 现状：仍在传缩略图，未走秒传 |
| 0ef205 | true | ≈36B | 模糊 | checkExist 命中但新记录 xml 缺 cdnbigimgurl，查 H3 |

### 纪律提醒
- 不碰 adb/真机（本任务取证性质）；探针仅给实现 lane 规格。
- 样本身份固定：216b 坏图、`INP j2init ori=216b` / `m.a=883190`（R79 Q3 已纠：不用 r0.u contains 216b）。
- 纪律①：dex 有 "support second !!" 字符串 ≠ 本次真实命中，须 `field_exist_whencheck=true` + `existOnSvr` 真机字段双重确认。

---

## 附：本次取证可信度分层
- 【dex 实证】Q1 全链路 invoke、Q1.2 C2CUploadRequest 字段映射、Q2 setFilemd5 调用者、Q3 te0/q↔q54/la0 无调用关系、B6 checkExist task 构造、f2.C2 拼串、qf4/j0.C2 建记录 —— 均由本轮 `_r35_sns_dis.py` 对 classes7/classes13.dex 实跑自算。
- 【真机确定】onlycheckexist=false（stock & 我方，R78 log 行36/行8）、filemd5 跨 R79/R81 恒 0ef205、fullpath 36B vs 15906B、UploadHitCacheType 3→1 —— 取自 `docs/_r78_stockcdn_realdev_clean.log` / `_r81_r80a_falsified_realdev.log`。
- 【推断，高置信】mars native 从 filePath 重算 md5（C2CUploadRequest.filemd5 不被 Java 设置 ⇒ 唯一来源是 native）；B6→checkExist→j6→f2.C2 时序（跨方法，真机未直接 hook 每步）。
- 【未做】只读不碰真机，探针未实跑；所有真机判据为规格，待实现 lane 验证。

[DONE]
