# R86c Q4 根因裁决（降档重派 · Pro 显式档 402 · 别名路由）

> 角色：根因裁决子代理（降档重派）。任务性质 = 根因推理 + Hook 链路分析 + 判据设计 + 修复路线 go/no-go。
> 纪律：只读分析；禁 gradle / 禁 adb / 禁改 wxgroup/app 源码 / 禁 git commit。标注三档；偏移自算核准；判据对着肉眼清晰/模糊。
> 状态：骨架已建，逐题落盘中。

---

## 输入清单

按序读毕（全部只读）：
1. `docs/_r86c_q4_forensics_raw.md` —— 本轮真机事实（肉眼判据 + 三态对照表 + 五问），**总判据 = 肉眼清晰/模糊**。
2. `_r86c_q4_stepAB_raw.txt`（19 行）/ `_r86c_q4_stepA_raw.txt`（13 行）—— A/B 原始日志，含三 task 时间戳与 m1.e / x0cb 回调字段。
3. `docs/_r86c_d3_dex_forensics.md` —— D3 dex 坐实：m1.m 分派只看 task.e（不看 fullpath）、fullpath null 被规整为 ""；x0.u 用 fullpath 做 setFilePath + hitCheck；B6 fileId=big/mid 二选一；fileId 语义非决定量。其 Q2「hook native startC2CUpload」已被主会话复核推翻（native acc=0x109 不可挂）。
4. `docs/_r86b_b6step_verdict.md` —— R86b 自建 checkExist task 缺 fullpath ⇒ checkExist 未命中 ⇒ no-go（当时未做 stock 同图对照）。
5. `wxgroup/.../probe/B6CheckExistProbe.kt` —— 自建 task 的 12 字段（TASK_FIELDS 第 46-50 行，无 fullpath / enable_hitcheck / fake_sig），构造段第 231-242 行。
6. `wxgroup/.../feature/ForceForward.kt` —— `P3AdmitGate`（第 106-168 行，FLOOR=64KB + magic + image2 分桶形态）与 `p3OriginFromMd5`（第 2514-2557 行，拼 `image2/<2>/<2>/<md5>.jpg` 并 64KB+magic 准入）。

补充 dex 核验（本代理独立只读重跑，非照抄历史）：
- `_cache/r28_dd7_full.txt`（classes7 全量反汇编）：`Ltj/f;.field_UploadHitCacheType:I` = field@c5f9；写入点仅构造函数 `tj.f.<init>` i=0x0087 `iput v3(=0)`（默认 0，绝对偏移 0x8c9f3a），**Java 层 const 1/3 写入零命中**；语义判断在 `tj.f.b:()Z`（code_off≈0x8c9bb4）。
- `docs/_r82_b2_netscene_forensics.md:63-64,126-127` 旧映射（3=清晰/1=模糊）与本轮真机（A清晰=1、B模糊=3）直接冲突，本裁决以本轮真机为准。

标注纪律：三档标注；dex 有字符串 ≠ 业务调用；「A 推 B」只认 invoke 递增/真机时间戳，否则标【假设】；零命中如实记；偏移自算核准；判据对着肉眼清晰/模糊。

---

## Q1 · 三条 task 的 fullpath × fileId 组合与「清晰 / 未命中 / 命中但模糊」因果机制

### 结论（一句话）

**【推断·高（核心链有 dex+真机双证）；局部【UNKNOWN 未闭合】】决定清晰/模糊的不是 hit/exist/UploadHitCacheType 这些内部计数器，而是「mars 从 `setFilePath(fullpath)` 指向的本地字节重算出的 md5，对应的云端记录是不是清晰原图」。** 即：**哪份字节进去，就命中并引用哪张图**：
- **A（清晰）**：fullpath 带原图字节 + fileId 秒传锚点 → mars 算 md5=`cff4f5a5`（清晰原图身份）→ 命中原图记录 → 引用原图 → 清晰。
- **B-1（未命中）**：fullpathLen=0（无字节）→ `setFilePath(null/"")` → mars 无字节算 md5 → hitCheck 不成立 → `exist=false`（即使带 fileId + 源图 md5 也无用）。
- **B-2（命中但模糊）**：fullpath 带字节但不是原图（兜底 `f3a.jpg_hevc`）+ 入口无 fileId → mars 算 md5=`0ef205c1`（缩略图/转码图身份）→ 命中该图记录 → 引用缩略图 → 模糊。

**核心矛盾「B-2 exist=true 却模糊」的正解：`exist=true` 只说明「命中了某个云端记录」，但命中的是 `0ef205c1`（≠源图），引用的是缩略图字节——「命中」≠「原图字节」。**

### 证据链

1. **dex：fullpath 是唯一字节入口，md5 由字节重算**【dex 实证·高】`ok0/x0.u`（classes13，co=0x3a98cc）：`i=0x0009 iget field_fullpath → i=0x000b setFilePath`；全程不读 `field_filemd5`（D3 §2.2 / R82 Z.3 零命中）⇒ 请求侧 md5 完全由 mars 从 filePath 字节生成，task 传入的 md5Pre 不参与。`field_fileId→forwardFileid`（i=0x001d）只是秒传锚点，不携带字节。
2. **真机 A**【真机实证·高】`fullpathLen=124 pathTail=c09.jpg_hevc` + `fidLen=194 fidPre=305f0201` → `x0cb hit=1 exist=true md5=cff4f5a5` → out `filemd5=cff4f5a5` + `UploadHitCacheType=1` → 肉眼清晰。字节→md5→图身份→肉眼 三环一致。
3. **真机 B-1**【真机实证·高】`fullpathLen=0 pathTail=` + `fidLen=194 fidPre=305f0201` + `md5Pre=ca47f83e` → `x0cb hit=0 exist=false md5Len=0`。与 dex 推断吻合：无字节 ⇒ 无 md5 ⇒ 未命中；fileId 与 md5Pre 均未能挽救。
4. **真机 B-2**【真机实证·高 + 推断·中】`fullpathLen=124 pathTail=f3a.jpg_hevc` + 入口 `fidLen=0` → `x0cb hit=3 exist=true md5=0ef205c1` → out `filemd5=0ef205c1` + `UploadHitCacheType=3` → 肉眼模糊。`0ef205c1` 在 R82/R86 多次被认定为缩略图身份【推断·中：f3a.jpg_hevc 具体是否缩略图未直接反汇编内容，由 md5 身份+肉眼模糊反推】。
5. **时间戳坐实两条 task 的先后与身份**【真机实证·高】B-1 `01:22:53.433 wxg-forward`（模块自建）、B-2 `01:22:53.610 [D]#worker`（晚 177ms，微信兜底）—— 二者非同一 task，B-2 的 `exist=true` 不能替 B-1「翻案」。

### 置信度与边界
- 核心因果（fullpath 字节 → md5 → 命中哪张图 → 清晰/模糊）=【dex 实证 + 真机实证·高】，闭合。
- `cff4f5a5`（A 命中 md5，本文按 forensics 标"=源图"）与 `ca47f83e`（源 xml originsourcemd5、B-1 传入）二者关系 =【UNKNOWN 未闭合】：可能同一原图的不同环节 md5，也可能不同图；本裁决不靠二者相等来推进，只认肉眼一一对应。
- B-2 兜底字节的确切内容属性 =【推断·中】，以 md5=0ef205c1 缩略图身份 + 肉眼模糊为据。

`[DONE Q1]`

## Q2 · UploadHitCacheType=1(A) vs =3(B-2) 语义差异

**【dex 实证·高（语义判断处已定位）+ 真机实证·高（推翻旧映射）】`UploadHitCacheType` 是「结果分类计数器」，不是「清晰/模糊决定量」，更不能解释 A=1 清晰 / B-2=3 模糊。** dex 唯一可证的语义是 `tj.f.b()`：`∈{2,3} ∧ upload_by_safecdn ⇒ 走安全CDN/MD5秒传`，`=1` 不在该集合——即 **3 比 1 更"像"秒传命中**，这与本轮「1 清晰、3 模糊」肉眼事实**方向相反**。R82 旧映射（3=清晰/1=模糊）被本轮真机直接推翻。真正解释清晰/模糊的是 Q1 的 out filemd5（cff4f5a5 vs 0ef205c1）。

### 证据链

1. **字段定义与写入点**【dex 实证·高，自算核准】`Ltj/f;.field_UploadHitCacheType:I` = field@c5f9（classes7）。全量 grep `iput ... field_UploadHitCacheType` = **唯一 1 处**：`tj.f.<init>` i=0x0087 `iput v3,v5,field_UploadHitCacheType`（绝对偏移 `0x8c9e2c+2·0x87=0x8c9f3a`），而 v3 在 i=0x0017 为 `const/4 v3,#0` ⇒ **Java 层只写默认值 0**。业务值 1/2/3 **Java 层 const 写入零命中**（如实记），来自 native 回调结果 `C2CUploadResult` 向 `Ltj/f` 的拷贝（dex 只暴露拷贝后的读出，未反汇编 native marshaller）。⇒ 该字段是**结果字段**，模块无法在入口"设置"它来影响清晰/模糊。
2. **语义判断处 `tj.f.b:()Z`**【dex 实证·高，code_off≈0x8c9bb4，绝对偏移 = 0x8c9bc4 + 2·i】：
   - `i=0x0000 @0x8c9bc4` `iget-boolean v0,v2,field_upload_by_safecdn`；`i=0x0002 @0x8c9bc8` `if-eqz v0 → 0x000e(return 0)`。
   - `i=0x0004 @0x8c9bcc` `iget v0,v2,field_UploadHitCacheType`；`i=0x0006 @0x8c9bd0` `const/4 v1,#2`；`i=0x0007 @0x8c9bd2` `if-eq v1,v0 → 0x000c(return 1)`。
   - `i=0x0009 @0x8c9bd6` `const/4 v1,#3`；`i=0x000a @0x8c9bd8` `if-ne v1,v0 → 0x000e(return 0)`；`i=0x000c @0x8c9bdc` `const/4 v0,#1`；`i=0x000f @0x8c9be2` `return v0`。
   - ⇒ 等价式：`upload_by_safecdn==true && UploadHitCacheType∈{2,3} ⇒ true`（"走 MD5 秒传"分支）；`=1` 不命中该判断。`tj.f.b()` 在 `com.tencent.mm.modelimage` 上传完成链中被调用（如 `1f76d8 @0x1e0 invoke-virtual Ltj/f.b:()Z`，真则进 `isUploadBySafeCDNWithMD5` 日志与 `A13/i0` 场景）。
3. **消费点均为日志/上报，无分支决策**【dex 实证·高】：`2aab9a @0x0181`、`2aabf2 @0x01ad`、`1f76f0 @0x1ec`、`4caa6a @0x037f` 全是 `iget field_UploadHitCacheType → Integer.valueOf → 打日志/上报`，**没有任何按 1 vs 3 决定"发原图还是缩略图"的跳转**（此类跳转由 `tj.f.b()` 的 {2,3} 二分承担，与 1 无关）。
4. **旧映射被真机推翻**【真机实证·高】R82:63-64/126-127 记「36B占位命中云端原图 ⇒ UploadHitCacheType=3 ⇒ 清晰；15906B缩略图命中 ⇒ =1 ⇒ 模糊」。本轮 A（清晰）=**1**、B-2（模糊）=**3**，完全颠倒；且本轮 A/B 为**同一张图 + 肉眼终审**，证据等级高于 R82 的跨样本推断。⇒ 该计数器值随命中场景变化，不构成稳定语义标签。
5. **A/B 的 hit 计数器同样是非决定量**【真机实证·高】A `hit=1` 清晰、B-2 `hit=3` 模糊——hit 值本身也不映射清晰/模糊。与 UploadHitCacheType 一样属内部计数器。

### 置信度与边界
- 语义判断处指令与偏移 =【dex 实证·高】；「1 vs 3 不能解释清晰/模糊、旧映射被推翻」=【真机实证·高】。
- 1/2/3 各自精确 native 枚举名（如是否已上传/是否引用缓存）=【UNKNOWN 未闭合】：native marshaller 未反汇编，Java 层只见 {2,3} 分类用途，**不外推枚举字面含义**。

`[DONE Q2]`

## Q3 · out filemd5 与 fileId 内嵌值差异：原图字节 vs 转码/另图字节的直接证据？

**【真机实证·高（out filemd5 差异 = 引用不同图身份的直接证据，与肉眼一一对应）；【推断·中】（fileId 内嵌值差异仅为结果性旁证，非独立直接证据）】** 结论：**out `filemd5=cff4f5a5`（A，清晰）vs `0ef205c1`（B-2，模糊）就是"两条链最终引用了不同图字节"的直接证据**，置信度高；但它证明的是"**原图身份 vs 另图身份**"（后者为缩略图/转码图），至于"另图"的具体加工方式（缩略图 or 转码）【UNKNOWN 未闭合】。fileId 内嵌值差异**不能单独**当作直接证据，只能作旁证。

### 证据链

1. **out filemd5 与肉眼一一对应**【真机实证·高】A：out `filemd5=cff4f5a5`（=回调命中 md5）→ 肉眼清晰；B-2：out `filemd5=0ef205c1`（=回调命中 md5）→ 肉眼模糊。两条链各自"命中 md5 = out filemd5"自洽，且 md5 值不同、肉眼结果不同。结合 Q1 的"md5 由 fullpath 字节重算"，**out filemd5 即"被引用的那份字节的身份"**，差异即字节差异。
2. **thumbfilemd5 相同反衬"原图不同、缩略图同一"**【真机实证·高】A 与 B-2 的 `field_thumbfilemd5` 完全相同（`f6ab3ac5...`），而 `field_filemd5` 不同 ⇒ 同一张源图的缩略图一致，但**原图/引用图身份分叉**——B-2 显示的模糊图来自"非 cff4f5a5 的另图"，与"缩略图/转码图身份 0ef205c1"吻合（R82/R86 多次认定 0ef205c1=缩略图身份）。
3. **fileId 内嵌值差异**【真机实证·高（值本身的差异）+ 推断·中（语义解读）】A out fileId 内嵌 `…0204**0f836cfd**…0204**d1e5e773**…`；B-2 内嵌 `…0204**4954a2f9**…0204**55e5e773**…`；前缀同为 `305f020100044b3049020100…`（ASN.1 风格结构），仅内嵌身份字段不同。但**关键反证**：B-2 入口 task `fidLen=0`（无 fileId），out 却出现一个新 fileId ⇒ 这个 fileId 是**消息重建出口按命中 md5=0ef205c1 重新生成的**（结果字段），不是入口带入的锚点。⇒ 内嵌值差异与 filemd5 差异**同源**（都反映"引用了 0ef205c1 那张图"），是旁证而非独立证据；其 ASN.1 各字段的确切语义【UNKNOWN 未闭合】，不外推。
4. **不是"传错 fileId"的证据**【推断·高】A 与 B-1 入口 fileId 同为 `305f0201…`（B-1 有 fileId 仍未命中，见 Q1），B-2 入口根本无 fileId ⇒ 模糊**不是**"入口 fileId 填错"造成，而是"B-2 兜底 task 用非原图字节命中了另图"。这与 D3「fileId 语义非决定量」一致。

### 置信度与边界
- out filemd5 差异 = 引用不同图字节的直接证据 =【真机实证·高】（肉眼一一对应 + thumbmd5 反衬）。
- "另图 = 缩略图身份 0ef205c1" =【推断·高】（R82/R86 身份表 + 肉眼模糊）；"转码"具体加工 =【UNKNOWN 未闭合】。
- fileId 内嵌值差异作为直接证据 =【推断·中】（结果性旁证，入口无 fileId 反证其为重建产物，ASN.1 语义未坐实）。
- `cff4f5a5` 与 `ca47f83e` 是否同一原图 =【UNKNOWN 未闭合】（同 Q1 边界），不影响"out filemd5 决定引用哪张图"的结论。

`[DONE Q3]`

## Q4 · 修复路线核心：补 field_fullpath 能否像 A 命中 cff4f5a5 出清晰图？dex 级 go/no-go

**裁决：条件性 go —— 机制级成立、值得落码+真机验证，但 dex 只能证明"补 fullpath 走的是正确的命中机制"，不能证明"补了就必然命中 cff4f5a5"。** 必须把两条机制分开（这是本题最大的认知陷阱）：

### 机制辨析（关键：补 fullpath 走哪条路）

- **机制 A：分派（m1.m）—— 补 fullpath 与此无关，是伪命题。** 【dex 实证·高，D3 Q1 已坐实】自建 checkExist task 经 `mq/k.B8`→`m1.e`（i=0x002b `iput e=true`）下发，`ok0/m1.m` 的 `i=0x00a0 if-eqz(e)` 不跳，顺序落到 `i=0x0129 invoke x0.u`。全程 `field_fullpath` 只在 i=0x00a4（日志长度）、i=0x00f6（null 规整为 ""）被读，**不参与任何分派跳转**。⇒ 自建 task **本来就已经进 x0.u 上传链**，补 fullpath 不会改变分支，指望"补 fullpath 让它走对路"不成立。
- **机制 B：命中（x0.u → mars hitCheck）—— 补 fullpath 的唯一真实作用位。** 【dex 实证·高，D3 Q2 2.2 + R82 Z.3】`ok0/x0.u` `i=0x0009 iget field_fullpath → i=0x000b setFilePath`（唯一字节入口）；`field_enable_hitcheck→enableHitCheck`（i=0x0085/0x0087）透传参与 mars hitCheck；x0.u 全程不读 `field_filemd5`（请求 md5 由 mars 从 filePath 字节重算）。⇒ **fullpath 是"命中条件"**：给它一份能命中原图的字节，mars 才算得出目标 md5。B-1 真机 `fullpathLen=0 → hit=0 exist=false` 正是这条机制缺失的实证；A 真机 `fullpathLen=124 → hit=1 md5=cff4f5a5 → 清晰` 是它成立的实证。

### 落码规格要点（能否只补 fullpath？—— 不能，建议三字段一起补）

- **fullpath**：给 R83 `p3OriginFromMd5` 拼出的 `image2/<md5[0:2]>/<md5[2:4]>/<md5>.jpg`（经 P3AdmitGate 64KB+magic+分桶形态准入）⇒ 本地**真实原图字节**，命中目标的物质基础。
- **enable_hitcheck=true**：A 态①实测 `c2c enableHit=true`；自建 B6CheckExistProbe TASK_FIELDS **未写该字段**（默认 false）⇒ 即便塞了 fullpath，hitCheck 开关未开仍可能不命中。必须补。
- **fake_bigfile_signature**：对齐态① `fakeSigEmpty=true`（即传空串占位）；自建 task 未写，dex 中 x0.u i=0x00a2 读它→fakeBigfileSignature。补齐以对齐 A 的完整字段集。
- **fileId / aesKey**：A 与 B-1 入口 fileId 同为 `305f0201…`、B-2 入口无 fileId 仍命中另图 ⇒ fileId **不是**此次要补的决定量（D3 Q4 已证零区分力）；aesKey 已填。不必为"补 fileId"另起动作。

### go/no-go 判据（对着肉眼，不靠内部计数器）

| 取证结果 | 裁决 |
|---|---|
| 补三字段后模块转发 → out filemd5 落回源图身份（非 0ef205c1）且**对端肉眼清晰**，A→B→A 对照翻转成立 | **go 坐实**，修复路线成立 |
| out md5 变化但仍 ≠ 源图 / 肉眼仍模糊 | 机制通、字节选错 → 调整 fullpath 指向的字节再试一次 |
| 补 fullpath 后仍 `exist=false md5Len=0` | 回退，转 Q5 替代路线 |

### 置信度与边界
- "补 fullpath 不改变分派、只影响命中（机制 B）" =【dex 实证·高】，闭合。
- "补 fullpath + enableHit=true + fakeSig 能让自建 task 命中 cff4f5a5 原图" =【推断·中】：机制对症、字段差异已对齐 A，但**dex 无法证明 R83 拼出的字节 md5 恰为 cff4f5a5**——`cff4f5a5`(A 命中 md5) 与 `ca47f83e`(B-1 传入 md5Pre/源 xml md5) 关系【UNKNOWN 未闭合】（同 Q1/Q3 边界），必须以真机肉眼闭合，不许把"机制通"写成"已清晰"。
- 补 fullpath 是**必要条件非充分条件**：三字段缺一仍可能失败。

`[DONE Q4]`

## Q5 · 可直接落码的 XposedHelpers 规格 / 替代路线候选

因 Q4 判**条件性 go**，给可直接落码规格（子代理本轮**不落码**，规格交实现 lane）。三字段一次补齐，`B6CheckExistProbe.arm()` 是唯一改动点。

### 落码规格（改 `B6CheckExistProbe.kt` 的 `arm()`，构造 task 段第 230-242 行）

```kotlin
// 位置：arm() 内、val task = XposedHelpers.newInstance(k) 之后、
//       现有 put(task, "field_mediaId", ...) 之前/之后均可（同段连续）。
// 现有 TASK_FIELDS(第46-50行) 需追加 3 个字段名以便日志字段计数对齐。

// ① field_fullpath: String —— R83 已能拼出的源图真实路径（64KB+magic 已验）
val originPath = /* 见下方「路径来源」 */   // String?
if (originPath != null) put(task, "field_fullpath", originPath)   // 复用现有 put(Any?,String,String?) 重载

// ② field_enable_hitcheck: boolean(Z) —— A 态① enableHit=true；自建默认 false 必须补
put(task, "field_enable_hitcheck", true)      // 走现有 put(Any?,String,Boolean) 重载

// ③ field_fake_bigfile_signature: String —— 对齐态① fakeSigEmpty=true(空串占位)
put(task, "field_fake_bigfile_signature", "")  // String 类型，空串占位
```

- **字段类型（dex 已核，D3 §3.1）**：`field_fullpath : Ljava/lang/String;`；`field_enable_hitcheck : Z`（boolean）；`field_fake_bigfile_signature : Ljava/lang/String;`。写入用 `XposedHelpers.setObjectField / setBooleanField`（即现有 `put` 三个重载已覆盖，无需新增工具）。
- **不改的字段**：`field_fileId`（A 与 B-1 同值 `305f0201…`、零区分力）、`field_filemd5`（B-1 已填源图 md5，且 x0.u 不读它）、`field_aesKey` 均保持现状；`field_onlycheckexist=false` 维持（真机恒 false）。
- **路径来源（按优先级）**：
  1. 首选复用 `ForceForward.p3OriginFromMd5(img, localPath)`（`ForceForward.kt:2514-2557`）的产出：它从 img 抠源图 md5 → 按 `image2/<2>/<2>/<md5>.jpg` 拼绝对路径 → `P3AdmitGate.admit(f,true)`（64KB+magic+分桶形态）准入，返回已验真实原图路径。该函数当前 `private`、在 object 内，需在 ForceForward 暴露一个 `internal/public` 只读包装（如 `fun resolveP3OriginForProbe(...)`），或把拼装逻辑原样复制到 probe 内（保持 64KB+magic 判据不变）。
  2. 兜底：在 `arm()` 用已有的 `xmlMd5`（第 222-223 行从 `$md5` 读到、`md5Pre=ca47f83e` 那串）按 `root + image2/${md5[0:2]}/${md5[2:4]}/$md5.jpg` 拼，`root` 从 `localPath`/宿主数据根正则取，**同样过一次 P3AdmitGate 64KB+magic 校验**（校验失败则不写 fullpath，回退现状，避免塞缩略图）。
  - ⚠️ 边界：`xmlMd5=ca47f83e` 与 A 命中 `cff4f5a5` 关系【UNKNOWN 未闭合】（Q1/Q3/Q4 同）⇒ 落码后**必须真机看 out filemd5 与肉眼**，不预设 p3 路径字节的 md5 恰等于 cff4f5a5。

### 真机验证最小步（落码后）

1. 开 SCD+B6X 双探针（现有），广播 `op=b6step sub=on`；
2. 跑模块转发 → 看 `SCD2 c2c in` 自建 task 是否 `fullpathLen>0 pathTail=.jpg enableHit=true fakeSigEmpty=true`（字段已补齐的读数确认）；
3. 看 `x0cb` 是否 `hit=1 exist=true md5=cff4f5a5`（或至少非 0ef205c1）；
4. **对端肉眼清晰/模糊为终审**；按 Q4 判据表做 A→B→A 或至少 B(补字段)→肉眼翻转。仍模糊即沿 Q4 表第二行调整 fullpath 字节来源，不硬推。

### 若 Q4 后续真机判 no-go —— 替代路线候选（按代价排序）

- **候选1（f2.C2 出口重建）**：在 `modelimage/f2.C2` 消息重建出口，用已验的原图身份直接补进发出消息 content 的 `<img>` 字段，跳过 checkExist 建记录。代价：R86b Q3(a) 已指出"原图 big URL 真值未坐实（源 xml 无 cdnbigimgurl）"、`f2.C2` 拼串落点未取证，且依赖"带 URL⇒清晰"未验证假设——**同原路线一样有把期望当证据的风险**，需先取 dex 落点，非现成方案。
- **候选2（放弃）**：若补三字段真机仍不能让肉眼清晰，说明"仅缩略图场景秒传引用让对端清晰"在本机版本不成立（B2 命题被证伪），如实记录、停止改动。

`[DONE Q5]`

## 修复路线小结

- **根因再定位**：模块模糊的直接成因不是"入口 fileId 填错/缺失"，而是 **B-2 兜底 task 用非原图字节命中并引用了 0ef205c1（另图），而非 A 的 cff4f5a5（原图）**；B-1 自建 task 因无 fullpath 未命中，是诱因链上游。
- **不要追的假目标**：补 fullpath 改不了分派（本就进 x0.u）、UploadHitCacheType/hit 计数器不是决定量（1/3 映射被真机推翻）。
- **真正的修复杠杆**：在自建 checkExist task 补齐 `field_fullpath`（R83 真实原图路径）+ `field_enable_hitcheck=true` + `field_fake_bigfile_signature=""`，让 mars 有原图字节算 md5、开 hitCheck、对齐 A 字段集。
- **裁决**：条件性 go，机制级 dex 坐实、结果级需真机肉眼闭合；落码是必要非充分，A→B→A 肉眼翻转才算救活。

---

## 三件套

① **本轮真机是否坐实「自建 task 缺 fullpath = 模块模糊根因」？**
**部分坐实、且需改写因果表述**：真机坐实「B-1 自建 task 无 fullpath ⇒ 未命中（hit=0/exist=false）」【真机实证·高】，坐实「模块最终模糊是 B-2 兜底 task 引用 0ef205c1 另图而非 cff4f5a5 原图」【真机实证·高】；但「缺 fullpath」只是 B-1 未命中的机制（dex 坐实 fullpath=x0.u 唯一字节入口+命中条件），**不是 B-2 模糊的直接因**——B-2 恰是带 fullpath 却带错字节。严格表述应为：**根因 = 最终被引用的字节身份错了（另图 0ef205c1），缺 fullpath 使自建路径失效、把结果交给了走错字节的微信兜底**。「缺 fullpath = 模糊根因」的简化命题**不成立**，判据对着肉眼清晰/模糊得此修正。

② **修复路线 go/no-go（一句话）**：
**条件性 go——补 `field_fullpath`+`field_enable_hitcheck`+`field_fake_bigfile_signature` 走的是正确的命中机制（dex 坐实，非分派机制），值得落码+真机验证；但 dex 不能证明所补字节必命中 cff4f5a5，须以对端肉眼清晰为终审翻转才算 go 坐实。**

③ **是否值得再落码 + 真机（最小步定义）**：
**值得**。最小步 = 仅改 `B6CheckExistProbe.arm()` 构造段三行（fullpath 取 R83 已验原图路径 / enable_hitcheck=true / fake_sig=""），不动 fileId/aesKey/md5、不改判据、不发消息外的宿主逻辑；跑一轮模块转发（SCD+B6X 双探针）：读自建 task `fullpathLen>0 enableHit=true` → `x0cb` md5 落回源图身份（非 0ef205c1）→ **对端肉眼清晰**为终审；清晰即 go 坐实，仍模糊按 Q4 判据表调整 fullpath 字节来源一次，仍失败则转 Q5 候选1/2。

---

[DONE ALL]

---

## 主会话独立复核附录（2026-11-24 01:3x，事实核验，非改判）

按硬规则对裁决关键断言逐条独立核验，结果如下：

1. **R82 旧映射「3=清晰/1=模糊」确实被本轮真机推翻** ✅：`docs/_r82_b2_netscene_forensics.md:63/126` 原文为 3=清晰/1=模糊；本轮 A(清晰)=1、B-2(模糊)=3，颠倒属实。子代理事实成立。
2. **`tj.f.b()` 的 ∈{2,3} 语义判断** ✅ 独立反汇编 `tj.f.b:()Z`（`_cache/r28_dd7_full.txt:1845386`，code_off 8c9bb4）：`8c9bc4 iget field_upload_by_safecdn` → `if-eqz→return0`；`8c9bcc iget field_UploadHitCacheType` → `const2 if-eq→return1`；`const3 if-ne→return0`。指令与偏移逐字吻合，判定属实。
3. **自建 task 确实缺三字段** ✅：`B6CheckExistProbe.kt:46-50 TASK_FIELDS` 无 fullpath/enable_hitcheck/fake_sig，构造段 :230-242 未写。落码规格前提成立。

### ⚠️ 待收口的规格冲突（主会话发现，子代理 Q4/Q5 未处理）
子代理 Q4/Q5 建议「补 `field_fullpath` = R83 拼出的**真实原图大文件**（64KB+，image2 路径），mars 从字节重算 md5 命中 cff4f5a5」——这与 R82 的**占位文件机制**存在张力，且未引用 R82 裁决表：
- R82 `_r82_b2_netscene_forensics.md:62/126/196/262` 铁证：**A 段 stock 清晰靠的是 36B 占位文件 + 原图 fileId(305f) 触发秒传引用**（`field_fullpath` 内容对命中起决定作用，但 36B 占位内容本身非原图）；15906B 缩略图命中缩略图→模糊。
- R82 `:262-265` 互斥裁决表列了**两条**清晰路径：①`原图md5 + enableHit=true + ≈36B → 清晰（秒传引用）`；②`原图md5 + true + ≈15906B → 清晰（传字节但内容对）`。子代理选的「真实原图大文件」语义接近②，但**未论证为何不采用更省事、且 A 段实测走通的①（36B 占位路径）**，也未说明 R83 真实字节的 md5 是否等于 cff4f5a5（Q1/Q4 自己标 UNKNOWN）。
- **影响**：直接决定落码内容（写 36B 占位路径 vs 写真实原图路径）与成功率，属 Pro 类根因推理，主会话不自裁 → 已据此再派一轮 Pro 收口（见台账）。本附录只记录事实冲突，不改 Q1~Q5 正文结论。
