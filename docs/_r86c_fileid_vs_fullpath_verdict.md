# R86c 收口裁决：fileId vs fullpath —— 两轮证据冲突根因裁决 + 落码 go/no-go

> 角色：R86c 收口裁决子代理（降档重派 · Pro 显式档 402 · 别名路由）。
> 任务性质 = 根因推理 + 证据冲突裁决 + 落码 go/no-go（**本代理不落码**）。
> 核心约束：解决「证据线 A（R81/R82：fileId 逐字相同仍一清晰一模糊、fullpath 内容决定）」与「证据线 B（R86c Q4：A/B-2 fullpath 路径形态同、fileId 有无相关）」这一可能让修复重蹈 R80(a) 覆辙的根本矛盾。
> 纪律：只读（禁 gradle / adb / 改 wxgroup 源码 / git commit）。四档标注【dex 实证】/【真机实证】/【推断·高|中|低】/【UNKNOWN 未闭合】。「A 推 B」只认 invoke 地址单调递增 / 真机时间戳；零命中如实记；偏移自算核准。**判据一律对着肉眼清晰/模糊终审，不对内部计数器自说自话。** 禁把「机制可行」写成「已清晰」；禁把已证伪的 R80(a)"只换头"当新方案；禁把本轮未观测的 fullpath 字节数当已知。
> 落盘策略：分步。先建骨架（含各问占位）→ 结论得出立即写盘并留 `[DONE Qn]` → 再三件套 + `[DONE ALL]`。
> 状态：骨架已建，逐题落盘中。

---

## 0. 矛盾描述（要收口的唯一问题）

- **证据线 A（R81/R82，历史）**：
  - R81:44 铁证——注入 stock 的 fileId（body `0f836cfd`）后，`field_filemd5` 仍 `0ef205c1`（缩略图）、`field_thumbfilemd5` 仍 `f6ab3ac5`，`UploadHitCacheType` 3→1，但**对端仍模糊**；R81:12/49-51 结论「fileId 只是头、fullpath 字节才是身，只换头不换身无效」。
  - R82:83 四行（02:42 stock / 22:49 stock 清晰 vs 23:13 我方 / 23:44 R80a 注入后模糊）fileId 串**逐字完全一致**（`305f0201…0f836cfd…`），onlycheckexist 全 false、md5 全空；R82:87-97 唯一 correlated 差异 = `field_fullpath` 内容（stock 36B 占位 `882d…jpg_` 清晰 vs 我方 15906B 缩略图 `a123…jpg_` 模糊）。
  - ⇒ 历史结论：**决定清晰/模糊的是 fullpath 指向的字节内容，fileId 非决定量。**
- **证据线 B（本轮 R86c Q4）**：同一张图，A 微信原生转发清晰 / B 模块转发模糊（肉眼终审）。A：fullpath 路径字符串长 124（`c09.jpg_hevc`）+ fileId=305f（body `0f836cfd`）→ `hit=1 md5=cff4f5a5`（源图身份）→ 清晰；B-1 模块自建 fullpathLen=0 + fileId=305f → 未命中；B-2 微信兜底 fullpath 路径字符串长 124（`f3a.jpg_hevc`）+ **无 fileId** → `hit=3 md5=0ef205c1`（另图）→ 模糊。前一轮子代理据此判「A/B-2 唯一硬差异是 fileId 有无，fileId=引用决定量」，并推荐 hook `x0.u` before 补 fileId=305f + 非空小占位 fullpath + enable=true + fake_sig=""。
- **⚠️ 主会话已纠的量纲错误**：`fullpathLen=124` 是**路径字符串长度**（`StockCdnProbe.kt:437 path?.length`），**A/B-2 的 fullpath 指向文件字节数本轮根本未观测**；「A 是小占位、B-2 是缩略图」都未实测。前一轮设计已被主会话独立复核附录（`_r86c_realsend_fix_design.md:263-274`）标「Q3/Q4 核心主张与 R81 铁证冲突、落码暂缓待 Pro 收口」。
- **本裁决终极问题**：这是真矛盾，还是两轮观测了不同变量？决定「对端引用原图 cff4f5a5」的充分条件到底是什么？前一轮落点是否退回 R81 已证伪的"只换头"？现在能否落码？

---

## 输入清单（按序读毕，全部只读）

1. `docs/_r86c_realsend_fix_design.md`（:1-259 正文 + :263-274 主会话独立复核附录）—— 前一轮设计 + 冲突描述。
2. `docs/_r81_r80a_falsified_verdict.md`（:12/:38-52/:67-75/:145）—— 证据线 A 核心（只换头证伪）。
3. `docs/_r82_b2_netscene_forensics.md`（:62-64/:70-97/:113-117/:124-134/:196/:258-265）—— 证据线 A 核心（36B 占位 vs 15906B 缩略图、无 Java 开关、请求 md5 由 filePath 字节重算）。
4. `docs/_r86c_q4_forensics_raw.md` + `_r86c_q4_stepAB_raw.txt`（:14-21）—— 证据线 B 本轮事实（肉眼终审 + A/B-1/B-2 三态 + 时间戳）。
5. `docs/_r86c_q4_fullpath_verdict.md`（:181-188 主会话复核量纲纠正）+ `docs/_r86c_q4_verdict.md`（:31-36/:55/:77-90）—— Q4 裁决链。
6. `wxgroup/app/src/main/java/com/lx/wxgroup/probe/StockCdnProbe.kt:434-463` —— 确认 `fullpathLen=path.length`（路径字符串长度，非字节数）。
7. `docs/_r86c_d3_dex_forensics.md`（:83-99 `x0.u` 字段映射 / :101-106 native 入口 / :285 native hook 死路）—— `setFilePath`/`forwardFileid`/`enableHitCheck` 映射。
8. 真机原始日志交叉核验：`docs/_r81_r80a_falsified_realdev.log:35`（R80a 输出 fileId 全串 + filemd5）、`docs/_r79_dualsample_realdev.log:45`（R79 模糊输出 fileId body `4954a2f9` + filemd5）。

---

## Q1 · 两轮矛盾是否真实（逐条对齐变量）

### 结论（一句话）

**【真机实证·高 + 推断·高】不是真矛盾，而是两轮观测了不同的变量维度：证据线 A 恒定 fileId、变化 fullpath 字节内容 ⇒ 得出"fullpath 内容决定"；证据线 B 只恒定"路径字符串长度 124"（量纲错误），fullpath 实际字节数本轮根本未观测，却变化 fileId 有无 ⇒ 不能孤立出 fileId。更致命的是：本轮 A 清晰态的输出 fileId 全串与 R81 注入后仍模糊那次的输出 fileId 全串逐字相同（`305f…0f836cfd…7c006-74b9-4f91-a941`），却一清晰一模糊——直接证伪"fileId 是清晰决定量"。** 故"唯一硬差异是 fileId 有无"这个前提不成立；两轮不可直接对撞。

### 逐条对齐表（fileId body / fullpath 字节数 / md5 身份 / 肉眼）

| 样本 | 入口 fileId | **输出** fileId（body + uuid） | fullpath 路径形态 | **fullpath 指向文件字节数** | 结果 md5 身份 | 肉眼 |
|---|---|---|---|---|---|---|
| R79 我方（23:13）| 空 | body `4954a2f9` + `c36516a6-…`（`_r79_dualsample_realdev.log:45`）| `a123…jpg_`（`_r82:95` 为 23:44 样本）| 15906B（R82:95，另一轮打点）| `0ef205c1` | 模糊 |
| R81 R80a 注入后（23:44）| `305f`（body `0f836cfd`，R81:44/:8）| **`305f…0f836cfd…7c006-74b9-4f91-a941`**（`_r81_r80a_falsified_realdev.log:35`）| `a123…jpg_` | **15906B**（R82:95 实测，非本轮）| `0ef205c1` | 模糊 |
| R82 stock（22:49）| `305f`（body `0f836cfd`）| 同源（未单独 dump 全串）| `882d…jpg_` | **36B 占位**（R82:93 实测，非本轮）| 未观测（R81:70 自标）；R82:262 判"命中原图"| 清晰 |
| **本轮 A（01:21 stock）**| `305f`（body `0f836cfd`，stepAB:14）| **`305f…0f836cfd…7c006-74b9-4f91-a941`**（forensics:32，与上 R81 行**逐字相同**）| `c09.jpg_hevc`，路径字符串长 124（stepAB:14；`StockCdnProbe.kt:437`）| **未观测（本轮只测路径串长）** | `cff4f5a5`（stepAB:15）| **清晰** |
| 本轮 B-1（01:22:53.433 模块自建）| `305f`（body `0f836cfd`，stepAB:16）| —（未命中）| fullpathLen=0（路径字符串长 0）| 无字节入口 | 无（`hit=0`，stepAB:18）| 未命中（不产出最终消息）|
| **本轮 B-2（01:22:53.610 微信兜底）**| **空**（`fidLen=0`，stepAB:20）| body `4954a2f9` + `52500302a-c3bd-4238-bbce`（forensics:54；与 R79 模糊行同 body）| `f3a.jpg_hevc`，路径字符串长 124（stepAB:20）| **未观测（本轮只测路径串长）** | `0ef205c1`（stepAB:21）| **模糊** |

### 证据链与裁决

1. **量纲错误坐实**【dex 实证 + 真机实证·高】`StockCdnProbe.kt:437` `val fullpathLen = path?.length ?: -1`（Java String 长度），`:438 pathTail=path.takeLast(12)`；`fidLen=194`/`md5Len=32` 同理均为**字符串长度**（主会话复核附录 `_r86c_q4_fullpath_verdict.md:185`）。⇒ 本轮 A/B-2「同为 124 `.jpg_hevc`」只证明**路径字符串同长同后缀**，不证明两文件字节数相同；"A/B-2 fullpath 路径形态相同"这一步只走了半个变量。
2. **决定性反证：输出 fileId 逐字相同却一清晰一模糊**【真机实证·高】R81 R80a 模糊样本输出 `field_fileId=305f…0f836cfd…7c006-74b9-4f91-a941`（`_r81_r80a_falsified_realdev.log:35`），本轮 A 清晰样本输出 `field_fileId=305f…0f836cfd…7c006-74b9-4f91-a941`（`_r86c_q4_forensics_raw.md:32`，uuid 十六进制 `3736333030362d373462392d346639312d61393431` 解码即 `7c006-74b9-4f91-a941`）——**两串逐字相同**；但 R81 行 `field_filemd5=0ef205c1` 模糊，本轮 A 行 `field_filemd5=cff4f5a5` 清晰。⇒ **fileId 串（含 body 和 uuid）对清晰/模糊零区分力**，前一轮「保住 305f 即清晰」立论被真机直接推翻。
3. **fileId 对结果零区分力的独立佐证**【真机实证·高】R79 空 fileId 模糊（body `4954a2f9`）与 R81 注入 fileId 仍模糊（body `0f836cfd`）：入口 header 变了，`field_filemd5` 两次同为 `0ef205c1`、`thumbfilemd5` 同为 `f6ab3ac5`（R81:44-51）⇒ 注入 fileId 没让服务端改认内容。
4. **证据线 A 的内部一致性**【真机实证·高】R82:83 四行 fileId 逐字相同、onlycheckexist 全 false、md5 全空却一清晰一模糊；R82:87-97 唯一 correlated 差异 = fullpath 内容（36B 占位 vs 15906B 缩略图）。R82:124-134 dex+真机判定"不存在一个设 true 就不发字节的 Java 开关"，`enable_hitcheck` 不决定引用哪张、`fileId/aesKey` 只是 `forwardFileid/forwardAeskey` 转发头。⇒ 证据线 A 自洽。
5. **证据线 B 不能孤立 fileId 的原因**【推断·高】B-2 同时具备「入口无 fileId」与「fullpath 字节未观测」两个未固定变量；A 同时具备「有 fileId」与「fullpath 字节未观测」。两变量至少其一未受控，无法做「唯一硬差异是 fileId」的差分推断。B-2 输出 fileId 为 `4954a2f9`（forensics:54），但其**入口** `fidLen=0`（stepAB:20）⇒ 该输出 fileId 是**结果字段**（`ok0/x0.onC2CUploadCompleted` 回包，`R81:26`），不是入口锚点；把它当"锚点差异"是因果倒置。
6. **时间戳纪律**【真机实证·高】B-1 `01:22:53.433 wxg-forward`、B-2 `01:22:53.610 [D]#worker`（晚 177ms，stepAB:16/20）——两条独立 task，B-2 的 `exist=true` 不能替 B-1「翻案」；A `01:21:27` 与 B `01:22:53` 为不同操作，同一张图由用户 01:24 确认（forensics:9-11）。

### 置信度与边界
- 「非真矛盾、是不同变量维度」=【推断·高】（量纲错误 + 输出 fileId 逐字相同反证 + B-2 双变量未受控）。
- 「证据线 A 的 fullpath 内容决定论」=【真机实证·高】（R82:83/93-97 四行差分，36B/15906B 为另一轮**文件大小**打点，非本轮路径串长）。
- 「本轮 A/B-2 fullpath 文件字节数」=【UNKNOWN 未闭合】（本轮无该观测，禁止把 124 当字节数）。
- 「`cff4f5a5` 与 `ca47f83e` 是否同一原图」=【UNKNOWN 未闭合】（沿用 `_r86c_q4_verdict.md:48` 标注，不影响本裁决）。

`[DONE Q1]`

---

## Q2 · 决定量定论（充分/必要/无关 + 统一机制）

### 结论（一句话）

**【真机实证·高 + dex 实证·高 + 推断·高；native 内部合成与 fullpath 字节充分形态【UNKNOWN 未闭合】】对端引用原图 cff4f5a5 的"结果决定量"= 服务端回包 `filemd5=cff4f5a5`（`ok0/x0.onC2CUploadCompleted` 的 `C2CUploadResult.filemd5` → `tj/f.field_filemd5` → `f2.C2` 写进消息 `<img md5>`），它与肉眼清晰/模糊一一对应；其 Java 层唯一字节入口是 `setFilePath(field_fullpath)`。** fileId：非充分（R81 注入同串仍模糊、输出 fileId 逐字相同却一清晰一模糊）、必要性【UNKNOWN】（B-2 无 fileId 但 fullpath 字节也未测，无法隔离）；fullpath 字节：能启动 hitCheck 为必要（B-1 fullpathLen=0→未命中），但"哪类字节足以命中 cff4f5a5"【UNKNOWN】；enableHitCheck：hitCheck 参与开关（各样本均 true，未做 false 对照，必要性【推断·中】）、非充分；onlycheckexist/请求侧 filemd5/UploadHitCacheType/hit：非决定量。

### 三入口分工（dex 实证，逐指令）

`ok0/x0.u(Ltj/k;)I`，classes13，`code_off=0x3a98cc`，绝对偏移 `=0x3a98dc+2·i`（D3 :20/:23/:83-92）：

| task 字段 | 指令（i / 绝对偏移） | → C2CUploadRequest | 角色裁决 |
|---|---|---|---|
| `field_fullpath` | `i=0x0009 iget → i=0x000b setFilePath`（0x3a98ee）| `filePath` | **唯一字节入口**；`x0.u` 全程不读 `field_filemd5`（grep 零命中，D3:92 / R82 Z.3:113-117）⇒ 请求 md5 由 mars native 从该文件字节重算 |
| `field_fileId` | `i=0x001b iget → i=0x001d iput`（0x3a98f6）| `forwardFileid` | **转发头/锚点**；只携带"引用哪张"的声明，不携带字节；非充分（Q1 证据 2/3） |
| `field_enable_hitcheck` | `i=0x0085 iget-boolean → i=0x0087 iput`（0x3a9942）| `enableHitCheck` | hitCheck 开关；参 `R82:131`/`D3:86` |
| `field_onlycheckexist` | `i=0x0063 → i=0x0065`（0x3a9902）| `checkExistOnly` | 真机恒 false（R82 Z.1:83、stepAB:14/16/20 `only=false`）⇒ **非开关**【真机实证·高】 |
| `field_fake_bigfile_signature` | `i=0x00a2 → i=0x00a4`（0x3a9980）| `fakeBigfileSignature` | 占位签名；各清晰/模糊样本 fakeSigEmpty 均 true（stepAB:14/16/20），无区分力 |

- 结果字段权威来源：`ok0/x0.onC2CUploadCompleted` 的 `C2CUploadResult` → `tj/f`（`R81:20-32` 表：`field_filemd5←filemd5`、`field_fileId←fileid`、`field_UploadHitCacheType←hitCache`、`field_exist_whencheck←existOnSvr`）。⇒ 入口注入的 task 字段 ≠ 对端消息用的结果字段，这是 R81 证伪"补 fileId"的机制根因（`R81:34-36`）。
- `UploadHitCacheType`/`hit` 是结果分类计数器：R82 旧映射（3=清晰/1=模糊）被本轮 A=1 清晰、B-2=3 模糊直接推翻（`_r86c_q4_verdict.md:55/66-67`），**不作决定量**。

### 能同时解释两轮全部事实的统一机制

**【推断·高】** 清晰/模糊的唯一终局分叉 = "服务端最终把哪份字节身份写进 `C2CUploadResult.filemd5` 并回填到消息 `<img md5>`"：

```
setFilePath(fullpath 指向的本地字节)  ─┐
forwardFileid(fileId 头/锚点)        ─┼→ mars native hitCheck（内部合成【UNKNOWN 未闭合】）
enableHitCheck=true（参与开关）       ─┘        │
                                                ├─ 回包 filemd5=cff4f5a5（源图身份）→ f2.C2 写 md5=cff4f5a5 → 对端拉原图 → 清晰
                                                └─ 回包 filemd5=0ef205c1（另图/缩略图身份）→ 对端拉缩略图 → 模糊

B-1：fullpathLen=0 → setFilePath("") → 无字节 → hitCheck 不成立 → hit=0/exist=false（stepAB:18）
```

逐样本代入：
- **A 清晰**：fullpath 字节（本轮未测）经 hitCheck → 回包 `cff4f5a5`（stepAB:15）→ out `filemd5=cff4f5a5`（forensics:31）→ 肉眼清晰。
- **R82 stock 清晰**：36B 占位 `882d…jpg_`（R82:93，**另一轮文件大小**）经 hitCheck → 命中原图（`R82:126-127/262`）→ 清晰。
- **R81 R80a 模糊**：注入 fileId `0f836cfd` 但 fullpath 仍 15906B 缩略图（R82:95）→ 回包仍 `0ef205c1`（R81:44-51）→ 模糊。**这正是"只换头不换身"**。
- **B-2 模糊**：fullpath 字节（本轮未测）+ 入口无 fileId → 回包 `0ef205c1`（stepAB:21）→ 肉眼模糊。
- **B-1 未命中**：无字节入口 → 未启动 hitCheck。

### 必要/充分/无关 判定表

| 变量 | 判定 | 依据 |
|---|---|---|
| `C2CUploadResult.filemd5=cff4f5a5` | **结果决定量**（充分且必要，对肉眼） | out filemd5 与肉眼一一对应（Q1 表；`_r86c_q4_verdict.md:77-82`）|
| `fullpath` 非空 | **必要**（启动 hitCheck） | B-1 `fullpathLen=0 → hit=0/exist=false`（stepAB:16/18；D3:44-47 null 被规整为 ""）|
| `fullpath` 具体字节内容 = "能命中 cff4f5a5 的那一类" | **必要但充分形态【UNKNOWN】** | R82:93-97/124-134（36B 占位 vs 15906B 缩略图）；本轮字节数未观测；36B 占位的具体内容是否可复刻 =【UNKNOWN】（`_r86c_q4_fullpath_verdict.md:47`）|
| `fileId(305f/0f836cfd)` | **非充分**；必要性【UNKNOWN】 | 非充分：R81:44 + Q1 证据 2；必要性无法隔离：B-2 无 fileId 同时 fullpath 字节未测；且 B-2 输出 fileId 是结果字段（R81:26）|
| `enable_hitcheck=true` | 参与 hitCheck 的**开关**；必要【推断·中】、非充分 | R82:131 / D3:86；各样本均 true，无 false 对照 |
| `onlycheckexist` | **无关**（真机恒 false） | R82 Z.1:83；stepAB:14/16/20 |
| `field_filemd5`（task 侧） | **无关**（x0.u 不读，请求 md5 由字节重算） | R82 Z.3:113-117；D3:92 |
| `UploadHitCacheType` / `hit` | **无关**（结果计数器，映射两轮翻转） | `_r86c_q4_verdict.md:55/66-67` |

### 置信度与边界
- 三入口分工 + 结果字段来源 =【dex 实证·高】（逐指令 + 绝对偏移，D3/R81 自算）。
- out filemd5 ↔ 肉眼一一对应 =【真机实证·高】。
- 统一机制（哪份字节进去就引用哪张图）=【推断·高】（两轮差分 + R82:124-134 排除 Java 开关）。
- **native hitCheck 内部如何由 `forwardFileid` + `setFilePath` 字节合成 `cff4f5a5`** =【UNKNOWN 未闭合】（native marshaller 未反汇编；不外推算法；D3:285 证实 native 入口 acc=0x109 不可挂）。
- **本轮 A/B-2 fullpath 实际字节数与内容、36B 占位是否可任意构造** =【UNKNOWN 未闭合】。

`[DONE Q2]`

## Q3 · 前一轮落点是否有效（是否退回 R81 只换头）

### 结论（一句话·不许两可）

**【真机实证·高 + dex 实证·高】前一轮推荐的「hook `ok0/x0.u` before 补 fileId=305f + 非空小占位 fullpath + enable=true + fake_sig=""」——其"fileId 是决定量"的立论前提已被 Q1 证据 2 直接推翻（输出 fileId 逐字相同却一清晰一模糊），规格本身若 fullpath 仍指缩略图则精确退回 R81 已证伪的"只换头"；而"非空小占位"不足以保证命中 cff4f5a5（R82 要求特定 36B 占位或内容正确的字节，范围远窄于"非空+非大缩略图"），且 36B 占位的具体字节内容 =【UNKNOWN 未闭合】。⇒ 该规格不能作为已证方案落码，判 no-go（待 Q4 补观测）。**

### 裁决证据

1. **退回"只换头"的条件已明确**【真机实证·高】R81:12/44-51/114 铁证：注入 stock fileId（body `0f836cfd`）后 fileId 头与 stock 逐字相同，但 fullpath 仍指 15906B 缩略图（`a123…jpg_`，R82:95）⇒ `field_filemd5` 仍 `0ef205c1`、对端仍模糊。前一轮规格只把 fullpath 约束为"非空 + 非大缩略图"（`_r86c_realsend_fix_design.md:172/184`），若据此保留 B-2 的 `f3a.jpg_hevc` 路径（本轮字节数未测，可能正是缩略图/转码图），则与 R81 R80a 完全同构——**就是重蹈覆辙**。
2. **"非空小占位"不充分**【真机实证·高 + 推断·高】R82:258-265 互斥裁决表只列两条清晰路径：①`原图md5 + enableHit=true + ≈36B（占位）` → 清晰·秒传引用；②`原图md5 + true + ≈15906B 内容对` → 清晰。核心限定是**字节身份**（≈36B 特定占位 或 内容对），不是"非空 + 体积不大"。前一轮的门槛（`File(path).length()>64KB` 或 `th_` 前缀才换占位，`design:172`）宽泛得多——例如一个 6KB 或 100KB 的非 `th_` `.jpg_hevc` 文件会通过门槛却未必命中原图；B-2 的 124 路径串文件本轮未测字节，无法证明它落在"≈36B 占位"这一档。
3. **36B 占位的字节内容不可凭空复刻**【UNKNOWN 未闭合】R82:93 只记 36B 占位路径 `882d…jpg_`，`_r86c_q4_fullpath_verdict.md:47` 明确「36B 与 124B 占位文件的具体字节内容/是否 HEVC 指纹文件 =【UNKNOWN 未闭合】（未反汇编占位文件内容，不编造）」。前一轮"自建几十字节占位即可"（`design:172/184`）把"小"当充分，缺字节内容依据。
4. **前一轮立论的量纲前提已崩**【真机实证·高】前一轮唯一翻盘点 =「A/B-2 唯一硬差异是 fileId 有无」（`design:140/162`），建立在「A/B-2 fullpath 同为 124B 占位形态」上；`StockCdnProbe.kt:437` 证明 124 是路径字符串长，`_r86c_q4_fullpath_verdict.md:185` 主会话附录已纠。前提既崩，Q3/Q4 结论失去支柱（主会话复核附录 `design:268-273` 已判「暂缓」）。
5. **dex 可达性 ≠ 结果成立**【dex 实证·高 + 诚实边界】hook `x0.u` before 四字段直读直写确属实（D3:75-99：`param.args[0]`=task v6，四字段均 `iget v6` 直读，`i=0x0009/0x001d/0x0085/0x00a2`），但 dex 只证"字段能写进 C2CUploadRequest"，不证"写进去就命中 cff4f5a5"——native hitCheck 合成【UNKNOWN 未闭合】（D3:285 native hook 死路，Q2）。

### 结论边界
- 若真机后续证明"fileId + 任意小占位"确能让 out filemd5 落回 cff4f5a5 且肉眼清晰，则本方案可被经验救活——但那是**新证据**，不是现有证据支持的结论；现有证据下必须判 no-go，不得先落码再赌。
- 本判定不否决"更稳路径 = 直接复用 A 段实测清晰的 fullpath 来源（`j2.O` 的 `z3(r0.e)`，`_r86c_q4_fullpath_verdict.md:187`）"——但那是**改 fullpath 字节来源**的方案，已不是前一轮"fileId 决定量"的规格，需按 Q4 补观测后再定。

`[DONE Q3]`

---

## Q4 · 最小补观测（是否需要一轮只读取证）

### 结论（一句话）

**【推断·高】需要一轮"只读取证"补齐本轮缺失的观测：A/B-2 的 fullpath 指向文件实际字节数 + 内容（md5 前缀），以及 x0.u before 时 task 的 `field_fullpath` 真值与其对应文件字节——不补就无法隔离"fileId 有无"与"fullpath 字节"两个未受控变量，也就无法裁决 Q2/Q3。** 落点规格：扩展现有 `ok0/m1.e` before 的 SCD-c2c 只读探针（同一 hook 点，零装机/零落码），在读到 `field_fullpath` 路径串后**只读** `File(path).length()` 与文件字节 MD5 前缀；配合已有 SCD x0cb / f2C2 回调读数；native 入口 `startC2CUpload` 挂不上（acc=0x109，D3:285），不碰。

### 探针落点规格（只读红线）

- **主探针（扩展现有 SCD-c2c，不改源码）**：hook `ok0/m1.e(Ltj/k;)Z` before（classes7 `co=0x7b52b4`，`StockCdnProbe.kt:425-467` 已有此 hook），在现有字段之上追加**只读**项：
  1. `field_fullpath` 路径串 `path`（`getObjectField`）→ `java.io.File(path).exists()`、`File(path).length()`（**实际文件字节数**，这是本轮缺失量）；
  2. 对 `File(path)` 字节流算 MD5，打**前 8 位**（不打印全串/不上传），用于判"是否 = cff4f5a5 或 0ef205c1 或占位头"；
  3. `field_fileId` 本次取证允许打**完整 body + uuid**（A/B-2 各一次，用于坐实 Q1 证据 2 的逐字比对），取证结束回归"只打 len+prefix"；
  4. 保留现有 `fullpathLen/pathTail/fidLen/fidPre/md5Pre/md5Len/enableHit/only/fakeSigEmpty`（`StockCdnProbe.kt:460-462`）+ **时间戳 + 线程名 + 毫秒**（用于 A/B-1/B-2 差分与"同一张图"闸门）。
- **辅助读数（已有 hook，无需新增）**：SCD x0cb after 读 `C2CUploadResult.md5/hit/exist/fileid`（stepAB:15/18/21），SCD f2C2 out 读 `field_filemd5/fileId/UploadHitCacheType/thumbfilemd5`（forensics:29-34/51-56）。
- **x0.u before 的 `C2CUploadRequest.filePath` 真值如何拿**：request 是 x0.u 的局部变量 v0、native 入口 `acc=0x109` 不可挂（D3:99/:285），**无法直接读 request**。但 dex 已证 `x0.u i=0x0009 iget field_fullpath → i=0x000b setFilePath`（D3:83）是 request.filePath 的**唯一来源**，故读 `m1.e before` 的 `field_fullpath` + 对应 `File.length()`/MD5 即**等价得到 filePath 真值**，不必也不应挂 native。
- **红线**：只读；不写 `param.args`、不调宿主业务方法（`j6`/`b1.j6`/`g9.c` 等一律不调）、不写宿主字段、不发消息；不改 wxgroup 源码、不跑 gradle/adb、不提交；广播开关 `enabled` 默认 false，取证时临时开、结束关；同一张图（用户确认）跑 A（原生转发）+ B（模块转发）各一次，肉眼清晰/模糊为终审。
- **可复用的既有条件**：双探针 SCD+B6X 已 11 hook 全 OK、APK 未改动（forensics:16），设备只读状态满足零落码取证（D3 复核附录 C:288-292）。

### 若不需要补观测的理由（不成立）
现有证据已足以判 **no-go**（Q3），但不足以判 **go 的唯一正确规格**——因为卡点正是"哪类 fullpath 字节足以命中 cff4f5a5"【UNKNOWN】。故"直接落码"不可行；"直接给裁决"只能给 no-go + 补观测规格，不能给 go 规格。

`[DONE Q4]`

## Q5 · 总 go/no-go

### 结论（一句话）

**【推断·高】现在 no-go（暂缓落码）。卡在两个 UNKNOWN：①本轮 A/B-2 的 fullpath 指向文件实际字节数与内容（本轮只测路径串长 124，量纲错误）；②"哪类 fullpath 字节足以命中 cff4f5a5 / 36B 占位是否可任意构造"未证，导致 fileId 必要性无法隔离（B-2 无 fileId 同时 fullpath 字节未测）。必须按 Q4 补一轮只读取证、对着肉眼清晰/模糊闭合后才能定 go 规格；任何现在落码的方案要么退回 R81 已证伪的"只换头"，要么赌未观测变量。**

### no-go 的三条硬理由
1. **前一轮立论被真机直接推翻**：输出 fileId 全串逐字相同（R81 R80a 模糊 vs 本轮 A 清晰，`_r81_r80a_falsified_realdev.log:35` ↔ `_r86c_q4_forensics_raw.md:32`）却一清晰一模糊 ⇒ "保住 fileId 即清晰"不成立（Q1 证据 2）。
2. **规格不充分**："非空小占位"覆盖的字节范围远宽于 R82 互斥裁决表要求的"≈36B 特定占位 / 内容正确"（R82:258-265），且 36B 占位字节内容【UNKNOWN 未闭合】（`_r86c_q4_fullpath_verdict.md:47`）。
3. **结果级无真机闭环**：前一轮自己也承认"补后清晰"是【推断·高】非已证（`design:146/227`），native hitCheck 合成算法【UNKNOWN 未闭合】（D3:285 native 死路）；在缺失 fullpath 字节观测的情况下，机制通≠结果清晰，禁止把"机制可行"写成"已清晰"。

### 补什么观测后才能定（go 的前置条件）
- 跑 Q4 只读取证，拿到 A / B-1 / B-2 三条 task 的 **fullpath 实际文件字节数 + MD5 前缀** + 完整 fileId（逐字比对）+ 时间戳，且肉眼对照成立（同一张图 A 清晰 / B 模糊）。
- 据观测二选一收敛：
  - **若 A 的 fullpath 字节确为小占位（≈36B 级）、B-2 为另图字节（缩略图/转码）** ⇒ 决定量是 fullpath 字节，前一轮"fileId 决定量 + 任意小占位"规格**作废**；唯一正确规格改为"精确复刻 A 段同源 fullpath 字节来源（`j2.O` 的 `z3(r0.e)`，`_r86c_q4_fullpath_verdict.md:187`）或复刻 36B 占位字节内容"，仍以 A→B→A 肉眼翻转坐实。
  - **若 A 与 B-2 的 fullpath 字节确为同一类（同为占位且字节相近）而仅 fileId 有无不同** ⇒ 才回到"fileId 必要"命题，但仍须满足 Q2 的 fullpath 非空门槛，且必须真机 out filemd5=cff4f5a5 + 肉眼翻转；否则仍 no-go。
- 两种分支都要求终审判据 = **肉眼清晰/模糊**（A→B→A 翻转），计数器 hit/exist/UploadHitCacheType 只作旁证。

### 若未来 go，唯一正确规格必须同时满足两轮证据（本轮不签发）
- 不依赖"只换头"（R81:12/44）：fullpath 字节来源必须可说明为何命中 cff4f5a5，而非仅"非空 + 非大缩略图"。
- 不把本轮未观测字节当已知：规格须绑定 A 段实测同源 fullpath 或 Q4 证实的占位字节内容。
- fileId 只作转发头/锚点保留（非决定量），enable=true/fake_sig="" 仅对齐 A 字段集，不宣称其单独致清晰。
- 结果级以肉眼终审，不以计数器自说自话。

`[DONE Q5]`

---

## 统一机制 / UNKNOWN

**统一机制（【推断·高】，详见 Q2）**：清晰/模糊的终局决定量是服务端回包 `filemd5=cff4f5a5`（经 `ok0/x0.onC2CUploadCompleted` → `tj/f.field_filemd5` → `f2.C2` 写入消息 `<img md5>`），与肉眼一一对应；Java 层唯一可控字节入口 = `ok0/x0.u` 的 `setFilePath(field_fullpath)`（i=0x0009/0x000b）。fileId 只是 `forwardFileid` 转发头（i=0x001d），非充分、必要性未隔离；enable_hitcheck 是 hitCheck 参与开关（i=0x0085/0x0087）；onlycheckexist/请求 filemd5/UploadHitCacheType/hit 均非决定量。两轮表面冲突的真正来源 = 证据线 B 把"路径字符串长 124"误当"fullpath 字节形态相同"，从而错误地做了"唯一差异是 fileId"的差分。

**未闭合清单（必须携带）**：
- 【UNKNOWN 未闭合】本轮 A/B-2 fullpath 指向文件的**实际字节数与内容**（本轮只测路径串长；`StockCdnProbe.kt:437`）。
- 【UNKNOWN 未闭合】"≈36B 占位"的具体字节内容 / 是否可任意构造 / 是否 HEVC 指纹文件（`_r86c_q4_fullpath_verdict.md:47`）。
- 【UNKNOWN 未闭合】native hitCheck 内部如何由 `forwardFileid` + `setFilePath` 字节合成 `cff4f5a5`（native marshaller 未反汇编；`startC2CUpload` acc=0x109 不可挂，D3:285）。
- 【UNKNOWN 未闭合】`cff4f5a5` 与 `ca47f83e` 是否同一原图（沿用 `_r86c_q4_verdict.md:48`）。
- 【UNKNOWN 未闭合】fileId 必要性：B-2 无 fileId 但 fullpath 字节同未观测，无法隔离。

---

## 三件套

**① 两轮矛盾是否收口（统一机制 or 如实 UNKNOWN）？**
**部分收口：判定为"非真矛盾"——两轮观测了不同变量维度；证据线 A 恒定 fileId 变 fullpath 字节、证据线 B 只恒定路径串长（量纲错误）变 fileId，不可对撞；且本轮 A 与 R81 R80a 输出 fileId 逐字相同却一清晰一模糊，直接证伪 fileId 决定量。统一机制已给出（结果决定量 = 回包 filemd5=cff4f5a5；字节入口 = setFilePath(field_fullpath)；fileId 非充分）。但"哪类 fullpath 字节足以命中 cff4f5a5 / 36B 占位可否复刻"仍【UNKNOWN 未闭合】，需 Q4 补观测，不能报"完全收口"。**

**② 现在落码 go/no-go 一句话？**
**no-go（暂缓）——前一轮"补 fileId=305f + 非空小占位"规格要么退回 R81 已证伪的"只换头"，要么赌本轮未观测的 fullpath 字节；结果级未经肉眼闭环，禁止落码。**

**③ 补观测最小步（no-go 分支）**：
**只读取证一轮：扩展现有 `ok0/m1.e` before 的 SCD-c2c 探针，只读追加 `File(field_fullpath).length()`（实际字节数）+ 文件字节 MD5 前缀，并打完整 fileId（本次取证一次）+ 时间戳/线程名，配合已有 SCD x0cb/f2C2 回调；同一张图跑 A（原生，清晰）+ B（模块，模糊）各一次，肉眼终审。native 入口挂不上（acc=0x109）不碰，读 task field 即等价 request.filePath（x0.u i=0x0009→0x000b）。拿到 A/B-2 实际字节数 + 内容后，按"A 为小占位/B-2 为另图字节 ⇒ 改复刻 A 同源 fullpath 字节"或"字节同类仅 fileId 不同 ⇒ 才回到 fileId 必要命题"二选一收敛，任一 go 仍需 A→B→A 肉眼翻转坐实。**

[DONE ALL]

> 回传摘要：产物 `docs/_r86c_fileid_vs_fullpath_verdict.md`。Q1 非真矛盾（不同变量维度；输出 fileId 逐字相同却一清晰一模糊，证伪 fileId 决定量；124 是路径串长非字节数）。Q2 结果决定量=回包 filemd5=cff4f5a5，字节入口=setFilePath(field_fullpath)，fileId 非充分/必要性 UNKNOWN，enableHit 开关，onlycheckexist/filemd5/hit 非决定量。Q3 前一轮落点 no-go（退回只换头 + 非空小占位不充分 + 36B 字节内容 UNKNOWN）。Q4 需只读取证（扩展 SCD-c2c 读 File.length+MD5 前缀+全 fileId+时间戳，native 死路不碰）。Q5 总 no-go，卡 A/B-2 fullpath 实际字节与可命中占位形态两 UNKNOWN，补观测后二选一收敛。三件套：部分收口 / no-go / 补观测最小步。

---

## 主会话独立复核附录（2026-11-24 01:7x，事实核验）—— ✅ no-go 结论核验通过

1. **✅ Q1 核心铁证核验属实，且比子代理所述更强**：子代理称「本轮 A 与 R81 R80a 输出 fileId 逐字相同却一清晰一模糊」。独立核对：本轮 A（清晰）f2C2 输出 fileId 尾部 ASCII-hex `323037633030362d373462392d346639312d61393431` = `206c006-74b9-4f91-a941`，与 `docs/_r81_r80a_falsified_verdict.md:58` 记录的 R80a 注入后模糊态服务端新分配 uuid `7c006-74b9-4f91-a941` **同一个**（前导 `20`=hex 空格填充）；body 均 `0f836cfd`、前缀均 `305f0201…`。⇒ **两态输出 fileId 全串几乎逐字相同（含相同服务端 uuid）却一清晰一模糊** ⇒ fileId 决定量被彻底证伪，决定量只能是 fullpath 字节。子代理 Q1 铁证成立。
2. **✅ Q3「退回 R81 只换头」核验属实**：前一轮「补 fileId + 非空小占位」中，fileId 已被 R81 证伪为决定量；「非空小占位」范围远宽于 R82 实测的「36B 特定占位内容」，且本轮 fullpath 字节数未观测（`fullpathLen=path.length`）。⇒ no-go 方向正确，不可落码。
3. **✅ Q4/Q5 补观测建议合理**：native `startC2CUpload` acc=0x109 不可挂（`_r10_cdn_api.md` 铁证），读 task `field_fullpath` 指向 `File.length()`/MD5 前缀等价且只读安全。补观测后再定 go/no-go 符合判据纪律。
4. **结论**：采纳 no-go（暂缓落码）。本附录只做事实核验，不改正文裁决。下一步=补一轮只读取证拿 A/B-2 fullpath 实际字节数，落点/规格见正文 Q4。
