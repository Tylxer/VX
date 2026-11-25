# R86c 真实发送链落点设计 + dex 可达性取证 + 落码规格（裁决级）

> 角色：R86c 设计子代理（降档重派 · Pro 显式档 402 · 别名路由）。
> 任务性质 = Hook 链路设计 + 接入点判据 + dex 可达性取证 + 落码规格（**不落码**）。
> 核心前提（主会话已核传）：`B6CheckExistProbe.armFromForward`（`FileSender.kt:163`）是**独立只读探测**（C2 回调 `return 0`，不发消息/不建记录/不调 j6）；真实消息由 `FileSender.send` 主链（`q1.j` 路径选择 + `RESEND_FILE`/`l1.a()`）发出。⇒ **补探针三字段只验证机制、不让模块转发清晰。本设计落点必须在真实发送链，让真实发出的消息引用原图 cff4f5a5。**
> 纪律：只读（禁 gradle / adb / 改 wxgroup 源码 / git commit）。三档标注【dex 实证】/【真机实证】/【推断·高|中|低】/【UNKNOWN 未闭合】。「A 推 B」只认 invoke 地址递增/真机时间戳，否则标【假设】；零命中如实记；偏移自算核准（照抄历史偏移标「未核」）。**判据对着用户肉眼清晰/模糊（终审），不对内部计数器自说自话。** 禁把探针当修复、禁把「机制可行」写成「已清晰」、禁把未证的 bigURL 真值/「秒传→清晰」当已证前提。
> 落盘策略：分步。先建骨架（含各问占位）→ 每答完一问立即写盘并留 `[DONE Qn]` → 最后三件套 + `[DONE ALL]`。
> 状态：骨架已建，逐题落盘中。

---

## 输入清单（按序读毕，全部只读）

1. `docs/_r86c_q4_fullpath_verdict.md` + `docs/_r86c_q4_verdict.md` —— Q4 裁决链。核心：决定清晰/模糊 =「最终引用字节身份」（cff4f5a5 原图清晰 / 0ef205c1 另图模糊）；`fileId(305f)` = 引用哪张图的决定量，`fullpath` = hitCheck 门槛（非空即可），`enableHit` = 开关；修复规格选 Y = 非空占位 fullpath + enable_hitcheck=true + fake_sig="" + 保留 fileId=305f。主会话复核附录纠「124B 是路径字符串长度非文件字节数（量纲错误）」，Y 论证改以 fileId 决定量 + X 的 md5 UNKNOWN 为支柱。
2. `docs/_r86c_q4_forensics_raw.md` —— 本轮真机三态（肉眼 A 清晰 B 模糊为终审）。B 段两条 task：B-1 模块自建（fullpathLen=0 未命中）、B-2 微信兜底（fullpath=124 f3a.jpg_hevc、无 fileId、命中 0ef205c1 → 模糊）。
3. `docs/_r79_stock_byte_source.md` —— stock 清晰链机制定案：`MsgRetransmitUI.B6@0x62c98c` 从坏图 xml 取 cdnbigimgurl/aeskey/md5 → `g9.c(r0.u,"msg","")` → `mq/k.B8` checkExist → `b1.j6` 建新记录 → `f2.C2@0x2aa888` 重建带 URL 消息。§4.6 标「URL 透传链是跨方法推断、真机未直接 hook，秒传→清晰是高置信假设非已证」。
4. `docs/_r82_b2_netscene_forensics.md` / `:258-265` 互斥裁决表 —— 占位机制 + 两条清晰路径（原图md5+true+≈36B→清晰；原图md5+true+≈15906B 内容对→清晰）。
5. `docs/_r86c_d3_dex_forensics.md` —— D3 dex 坐实：`m1.m` 分派只看 `task.e`（不看 fullpath）、fullpath null 被规整为 ""；`x0.u` 用 `setFilePath(field_fullpath)` + `field_enable_hitcheck` + `forwardFileid(field_fileId)`；B6 `field_fileId` = `r0.h` 二选一 big/mid URL；坏图无 cdnbigimgurl ⇒ 取 mid。复核附录：native `startC2CUpload` acc=0x109 不可挂（探针 B 死路）。
6. `docs/_r86b_b6step_verdict.md` / `_r86b_b6_reimpl_design.md` —— B6 复刻 no-go（自建 checkExist C2 零命中）；「身份值相同≠输入相同」；bigURL 真值未坐实（源 xml 无 cdnbigimgurl）；M2 命题未证伪。
7. `wxgroup/app/src/main/java/com/lx/wxgroup/core/FileSender.kt` —— 真实发送链：`send(...)` :130 起，`q1.j` 路径选择 :187/:216-229，`RESEND_FILE`/`l1.a()` :252，`armFromForward` :163。
8. `wxgroup/app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt` —— `resendFile` :2696 / `FileSender.send` 调用点 :2741、`p3OriginFromMd5` :2514-2557、`P3AdmitGate` :106-168。
9. `wxgroup/app/src/main/java/com/lx/wxgroup/probe/B6CheckExistProbe.kt` —— 只读探针（C2 return 0、不建记录、不发消息），确证「探针≠修复」。

工具核对：`_r35_adnd_dex.py`（cls/calln/fields；任务所述 `meth` 实为 `calln`）、`_r35_sns_dis.py`（`bodyx <类> <方法>` 指令级、`fields`、`put/get/call`）存在；`_r9_cdn.py`/`_r27_disasm.py`/`_r4_dexref.py` 根目录不存在（已核）。dex 15 个在 `_cache/dex/`，全量反汇编 `_cache/r28_dd7_full.txt`（223MB）。偏移口径：`_r35_sns_dis.py bodyx` 的 `0xXXXX` 是**指令索引 i**，绝对字节偏移 = code_off + 16 + 2·i。

---

## Q1 · 真实发送链现状（从 resendFile → FileSender.send → 消息真正发出）

### 结论（一句话）

**【源码确定 + dex 实证·高 + 真机实证·高】完整链 = `resendFile`→`FileSender.send`→`r1.a(IMAGE)`造 `q1`→逐字段写 `q1.j`→`q1.a()`→`t81/g.a(q1)` 按 `q1.h=13` 把 `q1.j` 路径塞进 `j2` 构造器→`j2.O(r0)` 据 `r0` 组 `tj/k` 上传 task（`field_fullpath=z3(r0.e)`，`checkUseCdn=true` 时从消息 xml 写 `field_fileId`）→`m1.e`/`x0.u`→mars→`f2.C2` 重建消息→`l1.a()` 真正入队发出。** `q1.j` 的最终本地文件路径由**三级覆盖**决定：`bigImgPath`(R76 P3 落盘，最高) > `forwardCtx.srcBigImgPath`(原生大图) > `snap.localPath`(默认，多为 `th_` 缩略图/ori)。**B-2 出现无 fileId 的根因 = `j2.O` 走 `checkUseCdn=false` 本地分支时只写 `field_fullpath`、不写 `field_fileId`；只有 `checkUseCdn=true` 分支才从消息 xml 的 `$cdnmidimgurl`/`$cdnbigimgurl` 写 fileId**——坏图 xml 无 cdnbigimgurl（真机 `state_final:208`）⇒ 兜底链无 fileId 锚点。

### 证据链（源码行号 + dex 锚点）

**① 调用链（源码确定）**
- `ForceForward.resendFile` `ForceForward.kt:2696` → `FileSender.send(kind,target,snap.localPath,snap.imgLocalId,snap,bigImgPath)` `:2741`（第 3 参 = `snap.localPath`，第 4 参 = imgLocalId，第 6 参 = P3 bigImgPath）。
- `FileSender.send` `FileSender.kt:130`：`:166-171` `File(path)` 存在性检查；`:173-181` `r1.a(o1=IMAGE)` 造 `q1`；`:184-192` 逐字段写 `q1.i/b/a/j/k/l/n/p/o`；`:199-231` `hl0/m` 造转发信息 `m.a=imgLocalId`、写 `m.b/q1.r/q1.j`、写 `q1.f(m)`；`:233` `q1.h=13`；`:236` `q1.a()→l1`；`:248` `MsgRetransmitUI.g6(l1)`（只读）；`:252` `l1.a()` 真正 execute/入队。
- **探针是独立旁路、不在发送主链**：`FileSender.kt:158-165` 在发送前调 `ForwardCdnFieldHook.arm` 和 `B6CheckExistProbe.armFromForward`（后者 `:163`）。`B6CheckExistProbe.kt:251-273` 的 Proxy C2 回调**只读 exist/md5 后 `return 0`（:266）**，`:19-22` 红线明记「绝不调 j6 / 不写宿主字段 / 不发消息」⇒ **补它只观测，不改变上面任何一步发出的消息**。

**② `q1.j` 最终路径的三级决定量（源码确定 + dex 实证）**
- 默认：`q1.j = path = snap.localPath`（`FileSender.kt:187`；path 来自 `resendFile` 第 3 参 `snap.localPath` `:2741`）。
- 覆盖 1（原生大图）：`forwardCtx.srcBigImgPath` 非空则 `q1.j=srcBigImgPath`（`FileSender.kt:216-220`）。
- 覆盖 2（P3 落盘，最高优先级）：`bigImgPath` 存在 + >15KB + 非 `th_` 前缀则 `q1.j=bigImgPath`（`FileSender.kt:224-229`；`bigImgPath` = `copyP3Temp(chosenP3Src)` `ForceForward.kt:2740`）。
- dex：`t81/g.a(q1)→l1` `classes7`，`i=0x0022 iget q1.j` → `i=0x002c o6.l(q1.j)` 取大小（决定"大图不压缩"，印证 `FileSender.kt:47-50` 注释）；随后**多处 `q1.j`(v7) 传入 `j2` 构造器**（`i=0x009c/0x00cb/0x00fb/0x0137/0x016a/0x01b3 iget-object v7,v0,Lhl0/q1;->j` → `new-instance j2` → `invoke-direct/range j2.<init>`），`q1.h`（constructorId=13）在 `i=0x0009/0x0045` 读出作分派。绝对偏移口径：`t81/g.a` code_off 本轮未单独自算（标「未核」）；`j2.O` code_off=`0x2acc44`（R79 自算，本文沿用标「未核」），绝对 = `0x2acc54+2·i`。

**③ `j2.O` 组 task：fullpath 与 fileId 的双分支（dex 实证·高，本轮 `bodyx` 实跑）**
- task 构造：`i=0x0098 new tj/k`、`i=0x009d d="task_NetSceneUploadMsgImg"`、`i=0x00a4 task.f=j2.S`（listener=f2）。
- `field_fullpath`：`i=0x0076/0x0080/0x008a/0x0094 z3(r0.e/f/g/j)` 解析路径 → `i=0x0124/0x013f/0x01f8 iput field_fullpath`（= `z3(r0.e)`，占位/缩略图路径，印证 R79 `:36`）。
- **`field_fileId` 双分支（B-2 无 fileId 根因）**：`i=0x0201 x1.g()`（checkUseCdn）；`i=0x0225 if-nez → 0x0238`（走 CDN 分支）：`i=0x0238 j2.B` → `i=0x023e g9.c(j2.B,"msg","")` → `i=0x0249/0x0251 map[$.cdnmidimgurl]→field_fileId`、`i=0x0264/0x026c map[$.cdnbigimgurl]→field_fileId`（覆盖 mid）、`i=0x027c/0x0284 map[$.aeskey]→field_aesKey`；`checkUseCdn=false` 则落 `i=0x01f8` 本地 fullpath 分支，**整段 fileId 写入被跳过 ⇒ task 无 fileId**。
- 下发：`i=0x02d9 ok0/s2.e9()` → `m1`（即 `m1.e` 上传链，与 D3 `m1.m→x0.u` 一致）。`x0.u` 内 `field_fileId→forwardFileid`（D3 Q2 i=0x001b/0x001d）、`field_fullpath→setFilePath`（i=0x0009/0x000b）、`field_enable_hitcheck→enableHitCheck`（i=0x0085/0x0087）。

**④ B-2 无 fileId 的真机印证（真机实证·高）**
- 真机 A（stock 清晰）：m1e task `fileId=305f0201…`、`fullpath=124B eacdf2dd…jpg_`（`_r86b_b6step_verdict.md:27-28`）——走 CDN 分支，fileId 来自 xml。
- 真机 B-2（模块兜底，模糊）：`fullpath=124B f3a.jpg_hevc`、`fidLen=0`、`md5=0ef205c1`（`_r86c_q4_forensics_raw.md:47-56`）——**正是 checkUseCdn=false 本地分支的形态**：有 fullpath 字节、无 fileId 锚点 ⇒ 命中缩略图身份 ⇒ 肉眼模糊。
- 坏图 xml 无 `$cdnbigimgurl`（`state_final:208`，真机）⇒ 即便走 CDN 分支，B6/兜底也拿不到 big URL（D3 Q4）。这与「B-2 命中另图 0ef205c1」自洽。

### 置信度与边界
- 调用链、`q1.j` 三级覆盖、探针旁路 =【源码确定】（逐行）。
- `t81/g.a` q1.j→j2 构造、`j2.O` fullpath/fileId 双分支 =【dex 实证·高】（本轮 `bodyx` 实跑；code_off 沿用 R79 标「未核」）。
- B-2 无 fileId 根因（checkUseCdn=false 跳过 fileId 写入）=【推断·高】（dex 分支 + 真机 fidLen=0 形态一致；未直接 hook 到 `x1.g` 运行时值，标【假设】成分）。
- `j2.B` 与 `r0.u` 是否逐字同源、`x1.g` 本轮实际返回值 =【UNKNOWN 未闭合】（未 hook、未反汇编 `x1.g` 语义）。

`[DONE Q1]`

---

## Q2 · 可行落点枚举（dex 可达性 + 工程量 + 风险）

### 结论（一句话）

**【dex 实证·高 + 真机实证·高；bigURL 真值【UNKNOWN 未闭合】】要让真实消息引用原图 cff4f5a5，可达落点只有两处真正作用在"真实发出的消息"上：③在 `j2.O`/`x0.u` 的真实上传 task 上补齐 fileId+fullpath+enable_hitcheck+fake_sig（dex 全可达、改动最小、对齐 A 真机形态）；②在 `f2.C2` 重建出口改 `<img>` 拼串（dex 可达但 cdnbigimgurl 值来源于结果字段 `Ltj/f.fileId`、且依赖未证的"带 URL⇒清晰"假设）。①R79 B6 复刻已被 R86b 真机 no-go（自建 checkExist C2 零命中）且依赖未坐实的"原图 bigURL 真值"。** 唯一工程可达且证据最硬的是 ③。

### 候选 ① · R79 B6 复刻（checkExist 命中 → b1.j6 建带 cdnbigimgurl 新记录 → f2.C2 重建消息，替换真实发送）

- **机制链（dex 全可见，【dex 实证·高】，R79 §1.1）**：`MsgRetransmitUI.B6@0x62c98c`（从坏图 xml 取参数）→ `mq/k.B8@0x78f618` checkExist → `qf4/j0.C2@0x84307c`（"support second !!" 秒传）→ `b1.j6@0x2a74f8` 建新记录 → `k6@0x62d368` → `j2.O@0x2acc44` → `f2.C2@0x2aa888` 重建带 URL 消息。
- **dex 可达性**：✅ 每一跳 dex 可见、可反射调用。**但**本轮 `bodyx f2.C2` 实证：`cdnbigimgurl=` 拼串值取 `Ltj/f.field_fileId`（i=0x0235/0x023d/0x0254），是**上传结果字段**，不是入参——即"带 cdnbigimgurl 的新消息"是 **checkExist/上传命中后由 mars 结果回填**的，复刻链必须先让 checkExist 真命中。
- **工程量**：高。需复刻整条 B6 参数构造 + 自建 listener 捕获秒传 + 调 `b1.j6` 建记录 + 拦截/替换真实 `FileSender.send`（否则双发）。
- **风险（决定性）**：
  - **真机已 no-go**：R86b 自建 checkExist task 下发成功但 C2 第二次回包 `exist=false md5Len=0`（`_r86b_b6step_verdict.md:89/97`），零命中；头号根因是自建 task 缺 fullpath/enable_hitcheck/fake_sig（D3 Q1/Q4）。
  - **前提被证伪**：源坏图 xml 无 `$cdnbigimgurl`（真机 `state_final:208`），D3 Q3/Q4 证 B6 用 `r0.h` 二选一，坏图只能取 mid（305f0201，mid URL 语义），"原图 bigURL 真值"【UNKNOWN 未闭合】。拿 mid 冒充 big = 把期望当证据（R86b Q3(a)）。
  - **"checkExist 命中→对端清晰"是 R79 §4.6 未验证假设**，R86b 三件套③明确「M2 命题待补证」。
  - ⇒ **判定：dex 可达但前提未坐实 + 真机已 no-go，不可作为落点。**

### 候选 ② · 在 `FileSender.send` 构造消息 xml 处直接写 cdnbigimgurl/aeskey 进 `<img>` 字段

- **dex 可达性（本轮 `bodyx` 实证）**：⚠️ **落点不在发送侧**。`FileSender.send` 只写 `q1` 字段 + `l1.a()`（`FileSender.kt:184-252`），不拼消息 xml；真正拼 `<msg><img aeskey="…" cdnbigimgurl="…">` 的是 **`f2.C2@0x2aa888`（上传完成回调，classes7）**：
  - `i=0x021e const-string '<msg><img aeskey="'`、`i=0x0205 '" hdlength="'`、`i=0x020e '" cdnthumbaeskey="'`、`i=0x0216 '" cdnbigimgurl="'`、`i=0x021a '" cdnmidimgurl="'`、`i=0x0201 '" originsourcemd5="'`。
  - **拼串取值来源**：`i=0x022d` aeskey、`i=0x0235/0x023d/0x0254` cdnbigimgurl/cdnmidimgurl/cdnthumburl **全部取 `Ltj/f.field_fileId`/`field_aesKey`（结果字段）**；`i=0x0298` filemd5 取 `Ltj/f.field_filemd5`。即"xml 里的 cdnbigimgurl"是 mars 上传/秒传结果的回填，**不是发送前可从 snap 直接注入的入参**。
  - 想在 `f2.C2` hook 拼串返回值改写 `<img>` 字段 dex 上可行（`f2.C2` 是 Java 方法、可 hook after 改 `param.result` 拼出的 content），但 cdnbigimgurl 真值仍需从 `Ltj/f.field_fileId` 或外部取——又回到"原图 bigURL 真值未坐实"。
- **工程量**：中-高。需 hook `f2.C2`、解析/重建 xml content、处理落库时机与覆盖已发消息风险（R86b Q3(b)②已列）。
- **风险**：依赖"消息 xml 带 cdnbigimgurl ⇒ 对端清晰"**未验证假设**（R82 §Q4.2、R86b Q3(b)③）；bigURL 真值【UNKNOWN】；覆盖已发消息风险。**判定：dex 可达但落点语义错位 + 依赖未证假设，非首选。**

### 候选 ③ · 在真实发送的上传 task（`j2.O`/`x0.u` 的 `tj/k`）补齐 Y 三字段 + 保住 fileId

- **dex 可达性（【dex 实证·高】，本轮 + D3 实跑）**：
  - 真实 `RESEND_FILE`/`l1.a()` 的上传 task 由 `j2.O@0x2acc44` 构造（`task_NetSceneUploadMsgImg`，listener=f2），经 `m1.e`(co=0x7b52b4)→`m1.m`(co=0x7b6700)→`x0.u`(co=0x3a98cc, classes13)。D3 Q1 已证**必走 x0.u**（分派只看 `task.e`，与 fullpath 无关）。
  - **四个 Y 字段在 task 侧全部可读写、映射清楚**（D3 Q2 2.2）：`field_fullpath→setFilePath`(i=0x0009/0x000b)、`field_fileId→forwardFileid`(i=0x001b/0x001d)、`field_enable_hitcheck→enableHitCheck`(i=0x0085/0x0087)、`field_fake_bigfile_signature→fakeBigfileSignature`(i=0x00a2/0x00a4)。`x0.u` 实例方法 `ins=2`，`param.args[0]`=task(v6)，before 可直接 `get/setObjectField` / `setBooleanField`。
  - **j2.O 原生就写这些字段**（本轮 bodyx）：`field_fullpath=z3(r0.e)`(i=0x0124 等)、`field_enable_hitcheck=j2.p`(i=0x00d9)、`field_fileId=xml cdn big/mid`(i=0x0251/0x026c，仅 checkUseCdn=true 分支)、`field_aesKey`(i=0x0284)。⇒ 落点 = **hook `x0.u` before（或 `j2.O` 返回前），修正 task 的这四个字段**，使其对齐 A 真机形态（fileId=305f、`fullpath` 非空占位、`enable=true`、`fakeSig=""`）。
  - A 真机 task 形态（`_r86b_b6step_verdict.md:27-28`）：`fileId=305f0201…` + `fullpath=124B 占位` + `enableHit=true` + `fakeSigEmpty=true` → `x0cb hit=1 md5=ca47f83e/cff4f5a5` → 肉眼清晰。**③正是把模块 task 补成这一形态。**
- **工程量**：低-中。一个 hook 点（`x0.u` before）+ 四个字段 set；fileId 来源可复用 `ForwardCdnFieldHook.arm` 的 a3→r0.u→g9.c 范式（见 Q4）；fullpath 用占位即可（无需 P3AdmitGate 拼原图）。
- **风险**：
  - **fileId 决定量必须真进 `forwardFileid`**（Q4 fullpath verdict Q2）——A/B-2 同占位、fileId 有无决定清晰/模糊【真机实证·高】；必须保住 305f。
  - **native hitCheck 内部如何由 fileId+字节合成 md5=cff4f5a5** =【UNKNOWN 未闭合】（D3/R82 一致标注）；结果级必须真机肉眼闭合，不预设"补了就清晰"。
  - 改动真实 task 属**侵入性改动**（非探针），需 A→B→A 反向对照确认用户可见翻转。
  - ⇒ **判定：dex 全可达、工程量最低、对齐唯一被真机反复验证的清晰配方，是唯一可推荐落点（Q3 展开）。**

### 三候选对照

| 维度 | ①B6 复刻 | ②f2.C2 改 xml | ③真实 task 补 Y 字段【推荐】 |
|---|---|---|---|
| 作用于真实发出的消息 | 是（替换发送） | 部分（出口回填） | **是（发送 task 本身）** |
| dex 可达 | ✅ 全可见 | ⚠️ 落点在结果回调、值来自 Ltj/f | ✅ task 四字段全可读写 |
| 真机验证的链 | ❌ C2 零命中 no-go | ❌ 未验证假设 | ✅ A 态同型 task 清晰 |
| bigURL 真值依赖 | ❌ 未坐实（源 xml 无 big） | ❌ 未坐实 | ✅ 不依赖 big（靠 fileId 锚点） |
| 工程量/侵入 | 高 / 高（双发风险） | 中-高 / 中 | 低-中 / 中（真实 task） |
| 与已验事实一致 | 否 | 否 | **是（fileId 决定量 + 占位门槛）** |

`[DONE Q2]`

---

## Q3 · 最优落点唯一推荐（四判据，不许两可）

### 结论（一句话·唯一）

**【dex 实证 + 真机实证·高】唯一选 ③：hook `ok0/x0.u`（classes13 co=0x3a98cc）的 before，在真实 `RESEND_FILE` 上传 task（`tj/k`）上补齐/保住 Y 四字段——`field_fileId=305f…`（决定量）、`field_fullpath`=非空占位、`field_enable_hitcheck=true`、`field_fake_bigfile_signature=""`，使模块发出的 task 对齐 A 段真机清晰形态。不选 ①（R79 B6 复刻，真机 no-go + bigURL 未坐实）、不选 ②（f2.C2 改 xml，落点语义错位 + 依赖未证假设）。**

### 四判据裁决

| 判据 | ①B6 复刻 | ②f2.C2 改 xml | ③真实 task 补 Y【唯一推荐】 |
|---|---|---|---|
| **能否让对端真正引用原图（肉眼清晰）** | 依赖 checkExist 真命中→但 C2 零命中 no-go；且 bigURL 真值未坐实 ⇒ 无法保证引用 cff4f5a5 | 值来自 `Ltj/f.fileId` 结果字段，改 xml 仍绕不开"mars 命中哪张图"；依赖未证"带 URL⇒清晰" ⇒ 无法保证 | **直接把决定量 fileId(305f) + 占位 + enable 写进真实上传 task，对齐 A 段 `hit=1 md5=cff4f5a5` 清晰形态**【真机实证·高】 |
| **是否经真机验证的链** | ❌ R86b 自建 checkExist no-go；"秒传命中→清晰"是 R79 §4.6 未证假设 | ❌ 从未真机验证 | ✅ A 段 stock task（fileId=305f+占位+enable=true）真机清晰；本方案复刻该 task 形态 |
| **工程量 / 侵入性** | 高（整条 B6 链 + 替换发送 + 建记录） | 中-高（hook f2.C2 + 重建 xml + 落库风险） | **低-中（单 hook x0.u before + 4 字段）**；侵入真实 task 但可控、可反向对照 |
| **与已验证事实一致性** | 悖于 R86b no-go + D3 Q4（坏图无 big） | 悖于 f2.C2 值来源（结果字段非入参） | ✅ 一致于 Q4 fullpath verdict「fileId=引用决定量、fullpath=门槛、enable=开关」+ A/B-2 真机对照 |

### 为什么"不许两可"——唯一性的硬理由

1. **只有 ③ 作用在"真实发出的消息"的命门上**：Q4 fullpath verdict Q2 + 本轮真机 A/B-2 已证——A 与 B-2 同为小占位 fullpath，**唯一硬差异是 fileId 有无**：A 有 305f→命中 cff4f5a5→清晰；B-2 无 fileId→命中 0ef205c1→模糊。③ 正是"保住 fileId + 满足门槛"，优化的是**决定量**；① ② 优化的是"URL 透传/建记录"，是未证假设链条上的旁变量。
2. **只有 ③ 的链被真机反复验证**：两轮 stock 清晰态（R82 36B `.jpg_` + 本轮 A 124B 路径）都是「小占位 + 原图 fileId + enableHit」的 task 形态，③ 逐字段复刻它；① 的 C2 零命中、② 的"带 URL⇒清晰"均无真机支撑。
3. **① ② 都踩已被点名的禁区**：R86b no-go（①）、R86b Q3「bigURL 真值未坐实 + 落库覆盖风险」（②）——把期望写成证据，违反纪律。
4. **③ 不依赖未证前提**：不需要"原图 bigURL 真值"、不需要"md5=cff4f5a5 已证"——它只要求"保住原图 fileId 锚点 + 给非空占位触发 hitCheck"，这两条都是真机已证的充分形态；**结果级仍以肉眼终审闭合**（Q5），但不把机制通误写成已清晰。

### 边界（诚实标注）
- ③ 的"补字段让模块 task 对齐 A 形态"是【推断·高】（机制对症 + 字段差异对齐 + A 真机同型）；**"对齐后必然肉眼清晰"不是已证结论**——native hitCheck 由 fileId+字节合成 md5=cff4f5a5 的内部算法【UNKNOWN 未闭合】，且需真实 acquisition 的 fileId 确为 305f 原图锚点（真机 `fidPre=305f0201`，Q1④）。故 Q5 用 A→B→A 肉眼翻转坐实，不靠计数器。
- 若真机证明"补字段后 `x0cb` 仍 `md5=0ef205c1` 且肉眼模糊"，则 fileId 未真正进 `forwardFileid` 或 305f 非此刻原图锚点 ⇒ 按 Q5 判据表处置，如实 no-go，不改判 ①/②。

`[DONE Q3]`

---

## Q4 · Y 方案如何接入最优落点（精确字段+方法+行号级规格）

### 结论（一句话）

**【dex 实证 + 真机实证·高；fileId 来源已验证、fullpath 字节数【UNKNOWN 未闭合】】落点 = hook `ok0/x0.u(Ltj/k;)I` 的 before（`param.args[0]`=真实上传 task），一次性补/校正四字段：`field_fileId` 取 `ForwardCdnFieldHook.arm` 同款 `d3.O8().a3→r0.u→g9.c` 已解析出的 `305f…`（决定量，必须=原图锚点）、`field_fullpath` 给非空小占位（优先复用 stock 同源 `z3(r0.e)`，次选自建几十字节占位，**绝不指向 15906B 缩略图**）、`field_enable_hitcheck=true`、`field_fake_bigfile_signature=""`。** 这是把 Q4 fullpath verdict 的 Y 规格从"探针 task"平移到"真实 task"，唯一差别是真实链 B-2 本就无 fileId、需主动补而非"保留"。

### 前置辨析：本方案的 fileId 来源与 R80(a) 失败的区别（关键，防重蹈覆辙）

- **R80(a) 已试过且失败**：`ForwardCdnFieldHook.kt:97-116` 的 `inject()` 在 `ok0/m1.e before` 把 `field_fileId`/`field_aesKey` 补进同一真实 task（`:110/:112`），fileId 来源 `:125-150` 正是 `d3.O8().a3→r0.u→g9.c(u,"msg","")→map[$.cdnbigimgurl|$.cdnmidimgurl]`。**R81/R82 双证伪**：注入后 `m1e task` 的 fileId `305f…` 与 stock 逐字相同却仍模糊（`_r82_b2_netscene_forensics.md:72-85` Z.1 四行），因当时 fullpath 指 15906B 缩略图（`a123…jpg_`，R82:95）⇒ "只换头不换身"无效（R82:132）。
- **本轮为何可翻盘**：本轮 A/B-2 的 fullpath **同为 `.jpg_hevc` 124 字符串占位形态**（A `c09.jpg_hevc` / B-2 `f3a.jpg_hevc`，`_r86c_q4_forensics_raw.md:21/48`），唯一硬差异是 **fileId 有无**（A `fidLen=194 fidPre=305f`，B-2 `fidLen=0`）⇒ md5 从 cff4f5a5 翻到 0ef205c1、肉眼清晰翻模糊【真机实证·高】。⇒ 本方案 = R80(a) **补 fileId 头** + **把 fullpath 身改为 stock 同款小占位**（R80(a) 缺这一步）+ enable/fakeSig 对齐，正是 Z.2/Z.4 与 Q4 verdict 两个裁决的交集。
- **诚实边界**：A/B-2 的 fullpath **文件字节数本轮未观测**（`fullpathLen=124` 是路径**字符串长度**，主会话复核附录已纠量纲错误）——"A 是小占位"是【推断·高】非实测。故 fullpath 取"stock 同源路径"优先、真机必须看 out filemd5 与肉眼，不预设字节数（Q5）。

### 精确落码规格（交 Flash lane 机械实现；本代理不落码）

**Hook 点（唯一）**：`ok0/x0.u(Ltj/k;)I`，classes13，`code_off=0x3a98cc`（D3 自算，本文沿用标「未核」）；`regs=7 ins=2` ⇒ `beforeHookedMethod` 的 `param.args[0]` 就是真实 task(v6)。用 `XposedBridge.hookMethod` 精确签名（参数 `findClass("tj/k",cl)`，返回 int），找不到则 `hookAllMethods("u")` 兜底 + 打 msig。闸门：一次性 pending + TTL（复用 `ForwardCdnFieldHook` 的 `enabled/pending` 范式，`ForwardCdnFieldHook.kt:43-51/97-108`），只在模块转发的那一次 task 注入，防污染普通发图。

| 字段 | 值 / 类型 | 来源 / 取法（精确） | 写入 |
|---|---|---|---|
| `field_fileId` | `305f…`（len194，原图锚点）【决定量】 | 复用 `ForwardCdnFieldHook.arm` 已算出的 `fileId`（`ForwardCdnFieldHook.kt:142-144`：`map[$.cdnbigimgurl] ?: map[$.cdnmidimgurl]`，坏图走 mid=305f）。在 `FileSender.send` `:160` 调 `arm` 时把它连同 flag 交给本 hook 的 pending；或本 hook before 内现取 `d3.O8().a3(talker,msgSvrId)→r0.u→g9.c(u,"msg","")`（`ForwardCdnFieldHook.kt:132-144` 逐行范式，真机已验证）。**务必保住 305f，不得清空**（B-2 fidLen=0→模糊的反面） | `setObjectField(task,"field_fileId",fid)` |
| `field_fullpath` | **非空小占位路径**（String）【门槛】 | 优先级①：保留 `j2.O` 原生 `field_fullpath=z3(r0.e)`（`j2.O i=0x0124`，本轮 A 段 `c09.jpg_hevc` 即此路径）——只要它非空且**非 15906B 缩略图**即达标；优先级②：若原生指向大缩略图（`File(path).length()>64KB` 或以 `th_` 开头），在 `cacheDir` 自建几十~百字节占位文件取路径（**无需 JPEG magic/64KB/P3AdmitGate**——那是真上传原图字节路线才要；stock `.jpg_` 非合法后缀仍清晰）。**绝不用 `p3OriginFromMd5` 的原图大文件**（X 方案已弃，md5=cff4f5a5 前提【UNKNOWN】） | 仅当不满足门槛时 `setObjectField(task,"field_fullpath",path)`；满足则不动 |
| `field_enable_hitcheck` | `true`（Z）【开关】 | 字面值。对齐 A 态 `enableHit=true`（`forensics_raw.md:23`）；`j2.O i=0x00d9` 原生写 `j2.p`，可能被置 false ⇒ 显式置 true | `setBooleanField(task,"field_enable_hitcheck",true)` |
| `field_fake_bigfile_signature` | `""`（空串 String） | 对齐 A 态 `fakeSigEmpty=true`（`forensics_raw.md:23`）；`x0.u i=0x00a2` 读它 → `fakeBigfileSignature` | `setObjectField(task,"field_fake_bigfile_signature","")` |
| `field_filemd5` | 不动 | `x0.u` 全程不读（D3 Q2 负值证据 / R82 Z.3），改了也无效；保持 `xmlMd5`(=ca47f83e) 现状 | 不改 |
| `field_aesKey` | 不动/可补 | R80(a) `:112` 曾补 aesKey；本方案 fileId 已足，aesKey 保持 `j2.O i=0x0284` 原生值，需补则同 `ForwardCdnFieldHook.inject` | 可选 |
| `field_onlycheckexist` | `false`（保持） | R82 Z.1 真机恒 false、非开关 | 不改 |

**接入调用点**：`FileSender.send` `FileSender.kt:158-165` 的 arm 段已调 `ForwardCdnFieldHook.arm(ctx.sourceChat, ctx.msgSvrId, ctx.imgLocalId)`（`:160`）与 `B6CheckExistProbe.armFromForward`（`:163`，只读、保留不动）。新 hook 复用 `ForwardCdnFieldHook` 的 pending/enable 交接即可，无需改 `FileSender.send` 主链任何一行（q1.j 三级覆盖、`l1.a()` 保持）。

**为什么 hook `x0.u` 而非 `m1.e`**：`m1.e`（`ForwardCdnFieldHook` 已挂）是 task 入队前置字段，`x0.u` 是 `C2CUploadRequest` 构造前一刻、D3 Q2 已证 task 六字段全 `iget v6` 直读、写入后紧接着 `setFilePath`/`forwardFileid`，**字段落点与 mars 消费点最近、最不易被中间逻辑覆盖**；两者都可达，`x0.u` 更精确。（可选：直接扩展 `ForwardCdnFieldHook.inject` 在 `m1.e before` 补同四字段，省一个 hook，但需确认 m1.e→x0.u 之间无覆盖。）

**防踩坑**：
- `field_fullpath` 只要求"非空字节入口 + 非大缩略图"，不要套 P3AdmitGate 64KB+magic+分桶（R82:62-64 stock 36B 占位清晰为证）。
- fileId 是命门：必须真进 `C2CUploadRequest.forwardFileid`（`x0.u i=0x001d`）；验证用 SCD-c2c / x0.u before 读 task 的 `fidLen` 与 `fullpath` 尾缀。
- 不追假目标：不补 `field_filemd5` 为原图 md5（x0.u 不读）、不追 UploadHitCacheType/hit 计数器（Q4 verdict Q2 已证非决定量）。
- 闸门必须一次性 + TTL：无 pending / 超时 / 发送失败（`FileSender.kt:260` `ForwardCdnFieldHook.clear()`）⇒ 不碰 task，普通发图逐字节不变。

`[DONE Q4]`

---

## Q5 · 判据 + go/no-go（肉眼终审）

### 结论（一句话）

**【判据设计；机制级【dex 实证·高】、结果级必须真机肉眼闭合】唯一 go 判据 = 对端肉眼"清晰"（终审），辅助看 SCD 探针 `x0cb`/out `filemd5` 是否从 `0ef205c1` 翻回源图身份（`cff4f5a5`/`ca47f83e`）；计数器 hit/exist/UploadHitCacheType 只作机制旁证。落码后跑 A→B→A 反向对照，肉眼翻转成立才 go 坐实；若补字段后仍模糊或 dex 落点失效，按互斥表回退并如实 no-go，不靠计数器自说自话。**

### 落码后真机验收最小步

1. **基线对照（先站住）**：同图跑 stock 原生转发 → 肉眼清晰（A）；模块转发 → 肉眼模糊（B），记下 A 的 `x0cb md5` 与 out `filemd5`（应=cff4f5a5/ca47f83e 源图身份，非 0ef205c1）。
2. **开闸落码**：装含本方案的 APK，双探针 SCD+B6X 全开；模块「转发（模块）」→ `FileSender.send :160 arm` 装 pending → `x0.u before` 补四字段。
3. **读真实 task（机制旁证，非结论）**：SCD-c2c / x0.u before 看 `fidLen=194 fidPre=305f`、`fullpathLen>0`（非空占位、尾缀非大缩略图）、`enableHit=true`、`fakeSigEmpty=true`；看 `x0cb` 是否 `hit≠0 exist=true md5` 落回源图身份（**非** 0ef205c1）。
4. **肉眼终审**：对端收到图，**肉眼清晰 = go 坐实**；仍模糊 = 按下表处置。

### 互斥判据表（清晰→go / 仍模糊→下一步 / 崩溃→回退）

| 观测（按优先级，肉眼最高） | 机制旁证（辅助） | 裁决 | 下一步 |
|---|---|---|---|
| **对端肉眼清晰**（A→B→A 反向对照翻转成立：清晰→模糊→清晰） | out filemd5 落回源图身份（非 0ef205c1）；task 四字段齐全 | **go 坐实，修复成立** | 固化 hook + 闸门；保留 SCD 探针回归 |
| 对端肉眼清晰，但 out filemd5 仍=0ef205c1 / 计数器矛盾 | 以肉眼为准 | **go（肉眼终审压倒计数器）**，但记异常待查 | 追加一轮取数确认 md5 身份映射，不改判 go |
| **仍模糊**，但 task 已 `fidLen=194 fidPre=305f` + 四字段齐全 + `x0cb md5=0ef205c1` | 字段齐全却命中另图 | **机制通、字节/锚点选错 → 未坐实，非 no-go 终局** | ①查 fullpath 是否仍指 15906B 大缩略图，改自建小占位再测一次；②查 305f 是否此刻服务端原图锚点（同刻 stock 对照是否清晰）；③仍失败如实记 no-go，不硬推 |
| **仍模糊**，task `fidLen=0`（fileId 没进 forwardFileid） | 决定量缺失 | **本方案接入失败（pending 没命中 / x0.u 未挂 / arm 失败）** | 查 `ForwardCdnFieldHook arm skip` / TTL / `x0.u` hook 是否生效；修接入后重测一轮 |
| **仍模糊**，`x0cb hit=0 exist=false md5Len=0`（未命中） | hitCheck 未启动 | **fullpath 门槛/enable 未生效** | 查 `field_fullpath` 非空且非大缩略图、`field_enable_hitcheck=true` 是否真写入 `C2CUploadRequest`；修正再测一轮 |
| **微信崩溃 / 发送失败 / 双发消息** | hook 抛异常 / `l1.a()=false` | **no-go，立即回退** | 关闭广播开关（若设计为可热关）或卸载该 hook；恢复 R80(a) 现状；按 `FileSender.kt:260 clear()` 清 pending 防串味 |

### go/no-go 总判据（对着肉眼）

- **go（坐实）**：仅当肉眼清晰 + A→B→A 翻转成立；计数器/md5 旁证方向一致更好，矛盾则以肉眼为准并记异常。
- **条件性 go（机制通、待闭合）**：字段齐全但肉眼仍模糊且 md5=0ef205c1 ⇒ 按表第三行调整 fullpath 字节来源/查锚点后再测一次；这不是 go，也不是终局 no-go。
- **no-go（如实记）**：① dex 落点真机不可达（`x0.u` hook 失败且无兜底）；② 接入后 fileId 进不去 forwardFileid 且修接入无效；③ 补字段后回收字段齐全、fullpath 已占位、同刻 stock 仍清晰但模块体质仍命中 0ef205c1 ⇒ 说明"缩略图场景秒传引用让该版本对端清晰"在该机不成立（B2 命题被证伪），停止改动；④ 崩溃/双发。

### 未闭合前提（no-go 触发候选，必须如实携带）

- **原图 bigURL 真值【UNKNOWN 未闭合】**：源坏图 xml 无 `$cdnbigimgurl`（`state_final:208`），fileId 取的是 mid（305f0201，mid URL 语义，D3 Q4）——本方案不依赖 bigURL 真值（靠 fileId 锚点），但若真机证明 mid 锚点此刻不指向原图，则第三判据触发。
- **"fileId+占位字节→md5=cff4f5a5"的 native 合成算法【UNKNOWN 未闭合】**：Java 层只见 `forwardFileid` + `setFilePath` 两个入口并存，不外推其合成；"补了就清晰"是【推断·高】非已证，必须肉眼闭合。
- **A/B fullpath 文件字节数【UNKNOWN 未闭合】**：`fullpathLen=124` 是路径字符串长度非字节数（主会话复核附录），未反汇编 c09/f3a 实际字节 ⇒ 不预设字节数，靠 out md5 + 肉眼判读。
- **"秒传命中→对端清晰"是 R79 §4.6 未验证假设**：R86b 三件套③记「B2 命题待补证」；本方案是对该假设的真机检验，不是以它为前提宣称已清晰。

`[DONE Q5]`

---

## 最优落点小结

**唯一落点 = ③：hook `ok0/x0.u(Ltj/k;)I` before（classes13 co=0x3a98cc，本文沿用 R79/D3 自算偏移标「未核」），在真实 `RESEND_FILE` 上传 task 上补 Y 四字段——`field_fileId=305f…`（决定量）+ `field_fullpath` 非空小占位 + `field_enable_hitcheck=true` + `field_fake_bigfile_signature=""`，闸门复用 `ForwardCdnFieldHook` 一次性 pending/TTL。**

- 不碰探针（`B6CheckExistProbe` 保持只读）、不改 `FileSender.send` 主链 q1.j/l1.a()、不调 gradle/adb/不改源码/不提交（本代理只出规格）。
- 与 R80(a) 的本质差别：R80(a) 只补 fileId 头、fullpath 仍是 15906B 缩略图 ⇒ 失败；本方案 fileId 头 + fullpath 身改 stock 同款小占位 + enable/fakeSig 对齐，是两个裁决（R82 Z.2/Z.4 + Q4 verdict）的交集。
- 证据等级：dex 可达性【dex 实证·高】；"补后清晰"【推断·高】，结果级必须真机肉眼闭合；bigURL 真值、native md5 合成算法、fullpath 字节数均【UNKNOWN 未闭合】随判据携带。

---

## 三件套

**① 最优落点唯一推荐（一句话）**
**hook `ok0/x0.u` before，在真实 `RESEND_FILE` 上传 task 补 Y 四字段（fileId=305f 原图锚点 + 非空小占位 fullpath + enable_hitcheck=true + fake_sig=""），使模块发出的 task 对齐 A 段真机清晰形态——不碰探针、不替换发送、不依赖 bigURL 真值。**

**② dex 可达性 go/no-go（一句话）**
**go（落点级）**：`x0.u` 的 task 四字段全部 `iget v6` 直读直写、`setFilePath`/`forwardFileid`/`enableHitCheck` 映射逐指令坐实（D3 Q2 + 本轮 bodyx），`j2.O` 原生已写这些字段、hook 点可达；候选①②③中仅③同时满足"作用于真实消息 + 经真机验证链 + 不依赖未证 bigURL"。**但结果级 go 必须以真机肉眼清晰坐实，dex 只证机制不证结果。**

**③ 落码最小步定义 + 真机判据（肉眼终审）**
**最小步 = 只加一个 `x0.u before` hook（复用 `ForwardCdnFieldHook` 的 pending/TTL 闸门），在模块转发那一次 task 上 set 四字段：`field_fileId`=`arm` 已解析的 305f、`field_fullpath`=非空小占位（首选保留 `j2.O` 原生 `z3(r0.e)`，若指向 >64KB 缩略图则改自建几十字节占位）、`field_enable_hitcheck=true`、`field_fake_bigfile_signature=""`；不动 filemd5/onlycheckexist/q1.j/l1.a()、不发额外消息。** 真机判据：先 A(清晰)→B(模糊) 基线 → 开闸跑模块转发 → 读 SCD-c2c/x0.u before 看 `fidLen=194 fidPre=305f` + `fullpath` 非空占位 + `enableHit=true` + `fakeSigEmpty`（机制旁证）→ **对端肉眼清晰为终审**；清晰即 go 坐实，仍模糊且 md5=0ef205c1 则查 fullpath 字节/锚点后再测一次，字段没进 forwardFileid 则修接入，未命中则修门槛，崩溃/双发立即回退——计数器 hit/exist/UploadHitCacheType 一律不作结论依据。

---

[DONE ALL]

> 回传摘要：产物 `docs/_r86c_realsend_fix_design.md`。Q1 真实链 `resendFile→FileSender.send→q1.j 三级覆盖→t81/g.a→j2.O→m1.e/x0.u→f2.C2→l1.a()`，探针是旁路；B-2 无 fileId 根因 = `j2.O` checkUseCdn=false 分支跳过 fileId 写入。Q2 三候选：①B6 复刻真机 no-go+bigURL 未证、②f2.C2 值来自结果字段 Ltj/f.fileId、③真实 task 补 Y 字段唯一可达。Q3 唯一选③。Q4 规格 = hook x0.u before 补四字段，fileId 取 a3→r0.u→g9.c 已验 source，fullpath 非空小占位，与 R80(a)"只换头"区别在补身。Q5 肉眼终审互斥表 + 未闭合前提。三件套：落点③ / dex go（结果级肉眼坐实）/ 单 hook 四字段最小步。

---

## 主会话独立复核附录（2026-11-24 01:6x，事实核验）—— ⛔ Q3/Q4 核心主张与 R81 铁证冲突，不可直接落码

按硬规则逐条核验，发现**一个决定性问题**：

1. **✅ Q1 调用链 + Q2 三候选排除**：源码行号与 dex 分支（`j2.O` checkUseCdn=false 跳过 fileId）属实；①B6 复刻真机 no-go、②f2.C2 值来自结果字段 `Ltj/f.fileId` 属实。这部分核验通过。
2. **⛔ Q3/Q4「保住 fileId=305f(0f836cfd) 就清晰」与 R81 真机铁证直接冲突**：
   - 子代理立论（Q3:140 / Q4:162）=「本轮 A/B-2 唯一硬差异是 fileId 有无，A 有 `305f…0f836cfd`→清晰，故保住 fileId 是决定量」。
   - **R81:44 表格铁证**（`docs/_r81_r80a_falsified_verdict.md`）：`field_fileId` body `0f836cfd` = **stock 的 body**，但也 = **R80(a) 注入该 fileId 后仍模糊**那次；两行 `field_filemd5` 同为 `0ef205c1`（缩略图）、`field_thumbfilemd5` 同为 `f6ab3ac5`，注入 fileId 后 `UploadHitCacheType` 由 3→1 但仍模糊。R82:83 四行「fileId 逐字完全一致仍一清晰一模糊」。⇒ **「注入 stock 的 fileId(0f836cfd) 就能清晰」在 R81 已被真机直接证伪**，子代理把已证伪的"只换头"路线当成新方案。
   - 本轮 f2C2 出口实测也吻合该映射：A(清晰) body=`0f836cfd`、B-2(模糊) body=`4954a2f9`（=R81:44 中"我方空 fileId 模糊"的 body）。
3. **子代理唯一理论翻盘点也不成立/未证**：它称与 R80(a) 的差别是「额外把 fullpath 改成小占位」。但：①本轮 A/B-2 的 `fullpathLen=124` 是**路径字符串长度**（`StockCdnProbe.kt:437 path.length`，主会话已纠量纲），**文件字节数本轮未观测**，"A 是小占位"是推断非实测；②R82:62/95 实测 stock 清晰靠 36B 占位、模糊靠 15906B 缩略图——决定量是 **fullpath 内容**，而子代理 fullpath 规格只要求"非空+非大缩略图"，与 R82 的"36B 占位触发秒传"并不等价。
4. **裁决**：Q1/Q2 可用（调用链 + 候选排除）；**Q3/Q4 的「落点③补 fileId+占位 fullpath 即可清晰」不能作为已证方案落码**——它要么退回 R81 已证伪的"换头"（若 fullpath 仍指缩略图），要么赌一个本轮未观测的变量（fullpath 文件字节数）。若要继续，须先 Pro 收口「R81 铁证（fileId 非决定量、fullpath 内容才是）与本轮 A/B-2（fileId 有无相关）为何不一致」这一根本矛盾，否则任何落码都是重蹈 R80(a)。
5. 本附录只记事实核验，不替代 Q1~Q5 正文；产物状态维持 `[DONE ALL]`，但**落码 go 判断被本附录推翻为「暂缓、待 Pro 收口」**。
