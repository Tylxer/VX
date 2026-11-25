# R86b B6 checkExist 最小步真机结果裁决（根因判定 + go/no-go + 替代路线）

> 任务性质：裁决 agent，只出裁决——不落码、不改源码、不装机。本次为降档执行（Pro 余额不足），按最高标准推理，尤其根因排序与 go/no-go 不含糊。
> 输入：`docs/_r86b_b6step_impl_result.md`（落码+5 次真机修复+00:50 裁决数据）、`docs/_r86b_b6step_state_final_raw.txt`（221 行）、`docs/_r86b_b6_reimpl_design.md`（§5.3/§Q4/§Q5/§D1-D4）、`docs/_r86_b2a_lock_source_verdict.md`、`docs/_r86_b2a_pro_verdict_v2.md`、`docs/_r86_b2a_lock_state1_raw.txt`（态① 433 行）、`docs/_r86_b2a_state2_raw.txt`（态②）、`docs/_r79_stock_byte_source.md` §4.6、`docs/_r82_b2_netscene_forensics.md` §Q4.2/§Z.1、源码 `wxgroup/app/src/main/java/com/lx/wxgroup/probe/B6CheckExistProbe.kt`。
> 标注纪律：【真机坐实】/【源码确定·dex】/【推断·高/中/低】；dex 有字符串 ≠ 业务调用；"A 推 B"只认真机时间戳/invoke 递增，否则标假设；零命中如实记；禁止把期望写成证据。
> 核心命题边界：**「checkExist 命中 → 建新记录 → 对端清晰」在 R79 §4.6 是未验证假设，本裁决全程不把它当已证事实推进。**

## 章节进度
- Q1 根因裁决 → 待写
- Q2 go/no-go 裁决 → 待写
- Q3 替代路线裁决 → 待写
- Q4 下一步最小动作 → 待写
- 文末三件套 → 待写

## 输入证据基线（只读，不解释因果）

以下为本轮真机与源码的事实底面，Q1~Q4 全部以此为准，不另造事实。

| 来源 | 事实 |
|---|---|
| `state_final_raw.txt:208` | 源 xml img attrs：`cdnthumburl:194`、`cdnmidimgurl:194`、`md5:32`(=ca47f83e)、`aeskey:32`、`originsourcemd5:32`——**无 cdnbigimgurl**（证伪设计 §5.3「xml 有 cdnbigimgurl」前提） |
| `state_final_raw.txt:209` | 取参 `via=a3 idVia=regex:mid fidLen=194 fidPre=305f0201 aesLen=32 md5Len=32 md5Pre=ca47f83e` |
| `state_final_raw.txt:210` | 下发 `existHook=true via=B8 fields=12`（自建 Proxy 挂上，走 mq/k.B8） |
| `state_final_raw.txt:212` | C2 第一次 `[GT]ColdPool#7 exist=NA md5Len=-1`（空壳，字段未填） |
| `state_final_raw.txt:213` | C2 第二次 `[GT]HotPool#4 exist=false md5Pre= md5Len=0`（真实回包） |
| `state_final_raw.txt:215-217` | 同一转发仍走 `FileSender queued → resend-file imgLocalId=883190 → done`——最小步**没有**拦截发送，未改用户可见结果 |
| `lock_state1_raw.txt:372-375`（态① stock，清晰） | `SCD m1e in task fileId=305f0201... md5=（空） onlycheckexist=false`；`listener.f=com.tencent.mm.modelimage.f2`；`field_fullpath=124B .../eacdf2dd...jpg_`；`c2c enableHit=true only=false fakeSigEmpty=true` |
| `lock_state1_raw.txt:388` | `SCD2 x0cb in md5=ca47f83e md5Len=32 hit=1 exist=true fidLen=194 fidPre=305f0201`（msig=`CUploadCompleted`，上传完成回调） |
| `lock_state1_raw.txt:396-397` | `f2C2 fStrField: field_fileId=305f0201... field_filemd5=ca47f83eba49...`；`field_exist_whencheck=true field_UploadHitCacheType=1` |
| `state2_raw.txt:388,397`（态② 模块，模糊） | `x0cb md5=0ef205c1 hit=3 exist=true fidLen=194`；`f2C2 field_exist_whencheck=true field_UploadHitCacheType=3` |
| `R82:51-57,110,130-131` | dex：task 字段 → C2CUploadRequest：`field_enable_hitcheck→enableHitCheck(0x0085)`（透传 mars 参与 hitCheck）、`field_onlycheckexist→checkExistOnly(0x0063)`（真机恒 false）、`field_fake_bigfile_signature→fakeBigfileSignature(0x00a2)`；`setFilePath(field_fullpath)` 无条件执行 |
| `B6CheckExistProbe.kt:46-50,232-242` | 自建 task 写 12 字段：`d/f/field_mediaId/field_fileId/field_aesKey/field_filemd5/field_talker/field_fileType/field_priority/field_svr_signature/field_onlycheckexist/field_trysafecdn`——**无** `field_fullpath`、**无** `field_enable_hitcheck`、**无** `field_fake_bigfile_signature`；`field_onlycheckexist=false`、`field_mediaId="checkExist_$msgSvrId"`、`field_svr_signature=""` |

---

## Q1 根因裁决

### 1.0 前置总纲（必须先说，否则"输入相同"的前提是错的）

问题隐含前提：「fileId/md5/aesKey 全用 stock 的值、且 fileId 逐字与态①相同 ⇒ 输入相同，结果不同 ⇒ 必有实现 bug」。**该前提不成立**，按证据拆开：

- 【真机坐实】态① `hit=1 exist=true` 出自 `SCD2 x0cb ... msig=CUploadCompleted(java.lang.String,CdnLogic$C2CUploadResult)`（`lock_state1_raw.txt:388`）——这是**上传 task 的完成回调**，不是 checkExist 回调。它的 task 是 `SCD m1e in`（`ok0/m1.e`，`:372`），`listener.f=com.tencent.mm.modelimage.f2`（`:374`），且**带** `field_fullpath=124B .../eacdf2dd...jpg_` 与 `c2c enableHit=true`（`:375`）。
- 【真机坐实】本轮 `exist=false` 出自自建 `B8 checkExist task` 的 `Ltj/i.C2`（`state_final_raw.txt:210,213`），该 task `fields=12`，**无** fullpath / enable_hitcheck / fake_bigfile_signature（源码 `B6CheckExistProbe.kt:46-50,232-242`）。
- 【源码确定·dex】R82 已证 `setFilePath(field_fullpath)` 在 `ok0/x0.u` 里**无条件执行**，`field_enable_hitcheck→enableHitCheck(0x0085)` 透传 mars 参与 hitCheck，是"发不发字节/命中哪张图"的决定量之一（`R82:57,110,130-131,196`）；`field_onlycheckexist→checkExistOnly` 真机恒 false、不是开关（`R82:85,131`）。

⇒ **裁决：stock 的"命中"与我们的"未命中"不是同一条链、不是同一类 task。三个身份值相同不代表输入相同——stock 命中还依赖 `fullpath(36B 占位) + enable_hitcheck=true`，我方两样都没有。** 这是所有根因的框架：设计 §5.3 与 impl 全程按"只喂 fileId/aesKey/md5 就够"构造 task，从未对齐 stock 的完整字段集。

### 1.1 根因排序（按对 exist=false 的解释力 × 证据强度）

**① 自建 task 缺字段：无 `field_fullpath`、无 `field_enable_hitcheck`、无 `field_fake_bigfile_signature`** ——【真机坐实（字段差异）+ 源码确定·dex（字段作用）+ 推断·高（因果）】
- 差异坐实：态① stock task `c2c enableHit=true ... fullpathLen=124`（`lock_state1_raw.txt:375`）vs 我方 `fields=12` 不含这三字段（源码 TASK_FIELDS）。两态 f2C2 的 `field_exist_whencheck` 都能读到 true（`:397`/`state2_raw.txt:397`），证明字段可读、true 形态存在；本轮自建 Proxy C2 第二次读到 false 是真实未命中，**不是探针读不到**。
- dex 作用确定：`setFilePath(field_fullpath)` 无条件执行、mars 从 filePath 字节算 md5 做 hitCheck（`R82:57,125,196`）；`field_enable_hitcheck→enableHitCheck` 是秒传开关（`R82:52,110`）；`field_fake_bigfile_signature→fakeBigfileSignature(0x00a2)`（`R82:53,191`）。
- 因果推断·高（未直接观测）：fullpath=null ⇒ `setFilePath(null)` 无本地字节算 md5，或 m1.m 因无 fullpath 走错分派（设计 §D3 本标【需补 dex 取证】、未闭合）⇒ hitCheck 不命中 ⇒ `exist=false, md5Len=0`。
- **坐实需**：只读取证——hook `ok0/x0.u(tj/k)I` before（R82 H4 落点），打自建 task 的 `field_fullpath`/`field_enable_hitcheck`/`field_fake_bigfile_signature`/`field_onlycheckexist`/`field_fileId`/`field_fileType` 与 `C2CUploadRequest.filePath/forwardFileid/enableHitCheck/checkExistOnly`，对照态① `:375`；并补 D3 反汇编确认无 fullpath 分派。

**② 对比对象错层：把"上传 task 的 fileId"当成"checkExist task 该喂的 fileId"** ——【真机坐实（链不同）+ 推断·中（语义错配）】
- `305f0201...` 出现在态① **m1e 上传 task**（`lock_state1_raw.txt:373`），在态② R80(a) 注入后也逐字相同却仍模糊（`R82:83`/v2 判2）——它是上传 task 的 fileId，不是经真机证明的 checkExist task fileId。
- 设计 §5.3 要求 fileId 取 `.msg.img.$cdnbigimgurl`；本轮 attrs 证源 xml **无此键**（`state_final_raw.txt:208`），impl 改用 `regex:mid`（cdnmidimgurl）取到 `305f0201`——诊断性替换，**未经证明是原图身份**。
- 坐实需：hook/反汇编 stock B6 构造点（`0x01a5-0x01c0`）看 `field_fileId` 实参取哪个 map key，确认 B6 checkExist task 的 fileId 语义。

**③ fileId `305f...` 已过期 / 服务端此刻无此原图** ——【推断·低（被 23:08 stock 命中部分反证）】
- 反证：23:08 stock 用同一 fileId 得 `hit=1 exist=true md5=ca47f83e`（`lock_state1_raw.txt:388`），证明约 1.7 小时前服务端有此原图；本轮 00:50 `exist=false`。若期间被清理理论可能，但无证据。
- 坐实需：**同一时刻**再跑一次 stock 原生转发，看 `x0cb` 是否仍 `hit=1 md5=ca47f83e` + 对端清晰。若 stock 此刻也不命中 ⇒ 根因③坐实；若仍命中 ⇒ 排除③、反证回归①。这是唯一对着用户可见事实（清晰/模糊）的判据。

**④ 自建 Proxy C2 的 `args[3]` 不是 Ltj/f 或字段继承问题** ——【真机反证，可基本排除】
- 反证：C2 两次回调（`state_final_raw.txt:212-213`），形态 `NA → false`，与态①/态② f2C2 的 `NA → true`（`lock_state1_raw.txt:380,397`/`state2_raw.txt:380,397`）同构 ⇒ 第二次是真实回包、字段读取有效；`args[3]` 用 `Ltj/f`（源码 `:257`）与 dex 签名 `C2(String,int,Ltj/e,Ltj/f,Z)I`（设计 §1.1）一致。对象/字段错应恒 NA 或抛异常，不会读到 `exist=false`。
- 坐实需（彻底排除）：C2 加只读日志打 `args[3].javaClass.name` + Ltj/f 全部 String 字段名，确认非空壳。

**⑤ `onlycheckexist=false` 填错** ——【源码确定 + 真机坐实 → 排除】
- 对其存疑，核实后**不是根因**：R82 Z.1 四样本（02:42/22:49 stock 清晰、23:13/23:44 我方模糊）`onlycheckexist` 全 false（`R82:78-83`）；态① `:375` 亦 `only=false`；`R82:85,131` 明判"真机恒 false，不是开关"。设计 §5.3 指定 false、源码 `:241` 填 false——**无误**。
- 需坐实：无（已四次真机坐实）。

**⑥ 其它低概率** ——【推断·低】
- (a) `field_mediaId="checkExist_$msgSvrId"`（源码 `:233`）非 stock 的 `n1.b("checkExist",msgId,"","")`（R82 H2）；但 mediaId 只过 `ok0/m1.e` 校验，不影响 fileId/aesKey/md5 身份（源码注释 `:29-35`）。
- (b) `field_svr_signature=""`、`field_fileType=2`、`field_priority=0` 按常规值填，未逐字段对过 stock B6 task；R82/D4 已证这些不进命中判据。
- (c) C2 读取过早：下发 22.899 → 第二次 23.056 = 157ms；stock m1e 17.921 → x0cb 18.323 = 402ms（`lock_state1_raw.txt:372,388`）。但第二次 `md5Len=0` 空串与"未命中回包"一致，非典型时序症状。

### 1.2 Q1 结论

**头号根因是①（自建 checkExist task 缺 fullpath/enable_hitcheck/fake_sig，mars 侧不具备命中条件），其次②（fileId 语义错配 + 设计 §5.3 的 cdnbigimgurl 前提被真机证伪）。③需同时刻 stock 对照排除；④⑤已可排除，⑥低概率。** ①的因果环节（fullpath=null 如何导致 exist=false）目前是【推断·高】而非【真机坐实】——坐实它正是 Q4 第一件事，**不能跳过取证直接改写实现当成已证**。

[DONE Q1]

## Q2 go/no-go 裁决

### 2.1 按设计 §Q4 判据 D 的硬判读

- 设计 §5.3 唯一裁决点原文：`C2` 出现 `field_exist_whencheck=true` 且 `field_filemd5` 非 `0ef205` ⇒ 命中进第二步；否则按 Q4 判据 D 回退、全量复刻 no-go。
- 本轮实测：`state_final_raw.txt:213` `exist=false md5Pre= md5Len=0`（第二次真实回包）；第一次 `exist=NA` 是空壳，不参与判读。**未命中，零命中如实记，不写"差一点命中"。**
- 判据 D 原文（设计 §4.1）：ON + 模糊 + `未命中 / md5Pre=0ef205 / j6 未拿到 id` ⇒ `checkExist 未命中或源 URL 失效 ⇒ 回退，不硬推`。本轮严格落入"未命中"分支。
- **总裁决：判全量 B6 复刻 no-go，回退停止实现。** 不是"暂缓观察"，不是"补个字段再试"——按设计唯一裁决点，未命中即回退；硬推等于违反铁律 4（不许把期望写成证据）。

### 2.2 必须区分的两件事（边界不许混）

| 命题 | 本轮结论 | 证据 |
|---|---|---|
| M1「最小步 checkExist 机制失败」 | **坐实（失败）** | 自建 B8 task 下发成功（`via=B8 fields=12 existHook=true`），Proxy C2 被调用两次，第二次 `exist=false md5Len=0` ⇒ 我方主动下发的 checkExist 未命中原图 |
| M2「B2 命题被证伪」 | **未证伪，仍待补证** | B2 命题 =「秒传引用机制能让仅缩略图时清晰」。态① stock 实测 `hit=1 exist=true` + 肉眼清晰（`lock_state1_raw.txt:388` + 锁源裁决 §1），说明**秒传引用机制本身在真机成立**；本轮证伪的是"按设计 §5.3 字段构造的 checkExist task 能命中"这一**路径假设**，不是 B2 命题 |

- 两者不等价：M1 失败只说明"这条复刻路径在当前字段构造下走不通"；M2 要被证伪，必须证明"即使秒传引用成功也无法让仅缩略图时清晰"——本轮没有这个证据，反而态①给出反向证据（stock 清晰）。
- 但诚实记代价：B6 复刻是 v2 自审后裁定的**主落点**（v2 §2、设计 §5.3）。主落点实测失败，对"通过 B6 复刻达成 B2"的可达性构成重大打击；它把 B2 的实现路径推回开放状态（Q3），但不改写 B2 命题真值。

### 2.3 本轮未覆盖的判据（诚实标注，不影响 no-go）

- 未跑 A/B 反向对照（OFF→ON→OFF），未看对端清晰/模糊翻转：`state_final_raw.txt:215-217` 显示转发照旧走 `FileSender.send(imgLocalId=883190)`，最小步没拦截发送——**用户可见结果本轮无变化**。
- 这不构成"no-go 证据不足"：判据 D 是 listener 观测判据（`未命中`即触发），不需要对端判据；且"未命中"是零命中事实，不是推断。

[DONE Q2]

## Q3 替代路线裁决

设计 §5.4 路线：放弃复刻 B6，改在 `modelimage/f2.C2` / q1 消息重建出口，把源 xml 原图 `cdnbigimgurl/aeskey/md5` 直接补进发出消息的 content，跳过 checkExist 建新记录，对准 R82 §Q4.2「剩余问题在转发消息 xml 是否带 cdnbigimgurl」。裁决如下。

### (a) attrs 能否作"原图身份已稳定读到"依据 —— 部分能，**但不能作"原图 big URL 已稳定读到"依据**

- 【真机坐实】`state_final_raw.txt:208-209`：`md5:32=ca47f83e`（R82/R86 一致认定 ca47=原图身份、0ef205=缩略图身份）、`cdnmidimgurl:194`(=305f0201)、`aeskey:32` 稳定读到。
- 【关键缺口】同 attrs 证明**无 cdnbigimgurl 键**。`cdnmidimgurl` 是 mid 图 CDN 地址，不是 big 图地址；`305f0201` 虽与态① stock fileId 逐字同，但 Q1② 已指出那是上传 task 的 fileId，未证明等于 checkExist/B6 语义下的原图 big URL。
- 结论：替代路线需要补进 content 的 `cdnbigimgurl=` 值**本轮没有真值可取**。拿 305f0201（mid URL）冒充 big URL 补进去，等于用未经证明的值填判据字段——重蹈铁律 4。故 (a) 只能作"原图身份 md5 已稳定读到"，**不能**作"原图 URL 已稳定读到"。

### (b) 会不会重蹈 R80(a) / v1 —— 不重蹈这两条，但引入新风险

- R80(a) 改的是**上传 task 头**（fileId/aesKey），已被 R81/R82 双证伪：注入 fileId 后与 stock 逐字同、仍模糊（`R82:83`）；态② fileId 同为 305f0201 却 `hit=3`/`md5=0ef205`（`state2_raw.txt:388`）。替代路线改的是**发出消息的 content 字段**，作用层级不同（v2 判5 的修正落点），不重蹈"改头"。
- v1 改 fullpath 指 36B 占位（因果倒置，v2 判5 推翻）。替代路线不碰 fullpath，不重蹈。
- **新风险（必须记）**：① 补进 content 的 URL 若非真 big URL（见 (a)），对端仍模糊——这是"补错值"而非"改头"，但同样失败；② 设计 §5.4 自认「f2.C2 的 `<msg>` 拼串写在哪条字段/哪个出参、何时落库、覆盖是否影响已发消息」未取证，直接改有覆盖已发消息的风险；③「消息 xml 带 cdnbigimgurl ⇒ 对端清晰」本身是 R82 §Q4.2 待解问题，未被真机坐实——**替代路线同样有把期望当证据的风险，不比原路线低**。

### (c) 要不要新增 dex 取证、取什么 —— 要，但不是现在立刻转路线

按优先级列 4 项必取：
1. 【最高，与 Q1① 共用】`ok0/m1.m` 对无 `field_fullpath` 的 checkExist task 的分派（设计 §D3）→ 确认 fullpath=null 是否走错分支；这项决定"补字段能否救活原路线"。
2. `modelimage/f2.C2` 的 `<msg><img ...>` 拼串指令、写入字段/出参、落库时机与覆盖影响（设计 §5.4 自列）。
3. `tj/k` 字段全集里 `field_enable_hitcheck` / `field_fake_bigfile_signature` 的偏移与 **B6 原 task 的实际赋值**——设计 §1.1 字段表漏列这两项，导致 impl TASK_FIELDS 也漏写。
4. B6 `0x01a5-0x01c0` 的 `field_fileId` 实参来源，判断 `cdnmidimgurl`(305f0201) 是否等价于原图 big URL 语义（回应 (a) 缺口）。

**Q3 裁决：替代路线保留为候选，当前不启动。** 两个前提未闭合——(a) 原图 big URL 真值未坐实、(c) f2.C2 拼串落点未取证；且它同样依赖未验证的"带 URL ⇒ 清晰"假设。先做 Q4② 只读取证坐实 Q1①；若坐实且补字段能让 checkExist 命中，优先复活原路线（对准已知差异、成本更低）；若取证否定，再启动替代路线的 dex 取证。

[DONE Q3]

## Q4 下一步最小动作

### 4.1 三选一裁决：**选 ② 补一个只读取证坐实 Q1 某根因**

- 不选 ① 直接回退停止：回退是 Q2 的结论（停止全量实现），但**回退 ≠ 不再取证**；Q1① 仍停在【推断·高】，若不再取证就永久丢失"原路线能否救活"的判断依据，Q3 替代路线也会带着同一批未闭合前提启动。
- 不选 ③ 立即转 Q3 替代路线：Q3 已裁"保留候选、当前不启动"——big URL 真值未坐实、f2.C2 拼串落点未取证。
- 选 ②：一次只读取证同时服务三件事——坐实 Q1①（头号根因）、排除 Q1③（服务端过期）与 Q1④（C2 读错对象）、为 Q3 取舍提供前提。**本轮裁决不执行以下动作，仅给下一步指令；执行须另起任务，继续遵守"只读、不写宿主字段、不调 j6、不发消息"红线。**

### 4.2 具体动作（4 步，全部只读取证）

1. **【真机·只读】hook `ok0/x0.u(tj/k)I` before**（R82 H4 落点）：读自建 checkExist task 的 `field_fullpath`(长度+尾缀)、`field_enable_hitcheck`、`field_fake_bigfile_signature`(是否空)、`field_onlycheckexist`、`field_fileId`(长度+前缀)、`field_fileType`，并读 `C2CUploadRequest.filePath/forwardFileid/enableHitCheck/checkExistOnly`；打印与态① `lock_state1_raw.txt:375` 的 `fullpathLen=124 enableHit=true only=false fakeSigEmpty=true` 逐字段对照。**不改任何 task 字段。**
2. **【真机·只读】Proxy C2 加打 `args[3].javaClass.name` + Ltj/f 全部 String 字段名/长度**：确认第二次 `exist=false` 来自真实 Ltj/f 对象而非空壳/错对象（排 Q1④）。
3. **【真机·对照】同一时刻跑一次 stock 原生转发**（目标固定 filehelper，logclear 分隔）：看 `x0cb` 是否仍 `md5=ca47f83e hit=1 exist=true` + **对端肉眼清晰**；同次再看我方模块转发是否仍模糊。这是唯一对着用户可见事实的判据（排 Q1③）。
4. **【静态 dex·可并行】补 D3 反汇编**：`ok0/m1.m 0x00a4-0x0129` 的 if 链，确认 `field_fullpath=null + field_fileId非空` 落到 `x0.u` 还是 download 分支；顺带取 `tj/k` 里 `field_enable_hitcheck`/`field_fake_bigfile_signature` 的字段偏移与 B6 `0x01a5-0x01c0` 实际赋值（Q3(c) 第 1/3/4 项）。

### 4.3 判据（对着用户可见事实，互斥不并列）

| 取证结果 | 裁决 | 下一步 |
|---|---|---|
| 自建 task `fullpath=null`/`enable_hitcheck` 未设，而 stock `enableHit=true/fullpathLen=124`；且 stock 同时刻仍 `hit=1` + 对端清晰 | **Q1①坐实、Q1③排除** | 另起实现任务补字段后**重跑 A→B→A 反向对照**；只有对端"模糊→清晰→模糊"翻转成立才算救活，否则维持 no-go |
| stock 同时刻 `x0cb` 不再是 `hit=1/ca47f83e` 或对端不清晰 | **Q1③坐实（服务端侧）** | 回退停止，不修字段、不硬推；如实记服务端状态变化 |
| C2 的 `args[3]` 类名非 Ltj/f 或 Ltj/f 字段全空 | **Q1④坐实** | 修探针对象读取后重跑最小步（仍只读） |
| ①②③均否定但自建 C2 仍 `exist=false` | 头号根因未闭合 | 维持 no-go，转 Q3(c) 启动替代路线 dex 取证（先取 f2.C2 拼串落点） |

### 4.4 每步风险与回退点

- 步骤 1 风险：新增 x0.u hook 与现有 `SCD-c2c` 探针重入/日志串味。回退点：只加只读探针、不改 task 字段与判据；广播 `op=b6step sub=off` 即关，现有转发逐字节不变。
- 步骤 3 风险：跑 stock 原生转发会**实际发出一条消息**（用户可见）。回退点：目标固定 filehelper、logclear 分隔、标为取证样本，不污染 A/B 样本；若用户不允许发消息，则该步降级为"只读查服务端存量"，Q1③保持【推断·低】不升级。
- 步骤 4 风险：dex 反汇编可能不闭合。回退点：D3 不闭合**不影响 Q2 no-go 裁决**（只影响根因确定性），不阻塞回退，不得为等它而拖延停止实现。

[DONE Q4]

## 文末三件套

- **① Q2 总裁决（一句话）**：**全量 B6 复刻 no-go，回退停止实现——本轮自建 B8 checkExist 的 C2 真实回包 `exist=false md5Len=0`，未命中原图，按设计 §5.3 唯一裁决点与 §Q4 判据 D 必须回退；"复刻后清晰"是未验证假设，不硬推。**
- **② Q4 下一步最小动作**：**选②——补一轮只读取证（x0.u before 读自建 task 的 fullpath/enable_hitcheck/fake_sig + C2 打 Ltj/f 类名与全字段 + 同时刻 stock 对照对端清晰/模糊 + 并行补 D3 dex），坐实 Q1①并排除③④；本轮不执行，另起任务。**
- **③ B2 命题总判定**：**待补证（未坐实、未证伪）。** 本轮证伪的是"按设计 §5.3 字段构造的 checkExist task 能命中"这一路径假设；态① stock `hit=1`+肉眼清晰说明秒传引用机制本身成立，但"checkExist 命中→建新记录→对端清晰"仍是 R79 §4.6 未验证假设，Q3 替代路线同样未验证。

[DONE ALL]
