# R86c Q4b 最终根因裁决：两轮证据统一 + 决定量定论 + 根因定案 + 修复路线 go/no-go + 落码规格（裁决级）

> 角色：R86c Q4b 最终根因裁决子代理（降档重派 · Pro 显式档 402 · 别名路由）。
> 任务性质 = 根因推理 + 两轮证据统一 + 修复路线 go/no-go + 落码规格（**本代理不落码**）。
> 目标产物：`docs/_r86c_q4b_final_verdict.md`。
> 纪律：只读（禁 gradle / adb / 改 wxgroup 源码 / git commit）。三档标注【dex 实证】/【真机实证】/【推断·高|中|低】/【UNKNOWN 未闭合】。「A 推 B」只认 invoke 地址单调递增 / 真机时间戳，否则标【假设】；零命中如实记；**偏移自算核准**（本轮已亲跑 `_r35_sns_dis.py bodyx ok0/x0 u` 复核 x0.u 关键指令，照抄历史者标「未核」）。**判据一律对着肉眼清晰/模糊终审，不对内部计数器自说自话。** 禁把「机制可行」写成「已清晰」；禁把已证伪的 R80(a)「只换头」当新方案；禁照抄「36B 占位」假设（本轮 A 态实测 45522B 原图字节）；禁把 fileId 当决定量（R81 已逐字相同反证）。
> 落盘策略（防父 turn 被打断）：先建骨架（含 Q1~Q5 占位）→ 每答完一问立即写盘并留 `[DONE Qn]` → 最后写统一机制/修复路线小结 + 三件套 + `[DONE ALL]`。绝不在最后一次性写结论。

---

## 背景与本轮决定性新事实

补观测真机事实（`docs/_r86c_q4b_forensics_raw.md` + 原始日志 `_r86c_q4b_stepA_raw.txt` / `_r86c_q4b_stepAB_raw.txt`）：**同一张图，A 微信原生转发 = 清晰 / B 模块「转发（模块）」 = 模糊**（用户 02:14 肉眼终审）。首次拿到 fullpath 指向文件的**真实字节数 + 内容 MD5**：

| 态 | fullpath 路径尾 | fbytes | fmd5pre(内容MD5前8) | 入task fileId | 回调 md5/hit | 出口 filemd5 | 出口 fidBody | UploadHitCacheType | 肉眼 |
|---|---|---|---|---|---|---|---|---|---|
| A 原生 | `6ff.jpg_hevc` | **45522** | `cff4f5a5` | 有(fidLen=194, `305f…0f836cfd…7c006-74b9-4f91-a941…`) | `cff4f5a5`/hit=1 | `cff4f5a5…` | `0f836cfd` | 1 | **清晰** |
| B 模块 | `a77.jpg_hevc` | **9554** | `0ef205c1` | 无(fidLen=0) | `0ef205c1`/hit=3 | `0ef205c1…` | `4954a2f9`(新分配) | 3 | **模糊** |

关键伴生（事实，非定性）：fmd5pre = 回调 md5 = 出口 filemd5 前 8（A 全 `cff4f5a5` / B 全 `0ef205c1`）；enableHit/only/fakeSig/thumbfilemd5 两态相同（非区分量）；`fullpathLen=124` 是**路径字符串长度**（`StockCdnProbe.kt:437 path.length`），不是文件字节数。

---

## 输入清单（按序读毕，全部只读）

1. `docs/_r86c_q4b_forensics_raw.md` —— 本轮新事实（最重要，必读第一条）+ 完整 fileId 逐字串。
2. `docs/_r86c_fileid_vs_fullpath_verdict.md`（含主会话复核附录 :240-246）—— 上一轮收口裁决（no-go 因缺观测；统一机制；Q3 前一轮落点 = 退回 R80a 只换头；Q4 补观测规格）。
3. `docs/_r81_r80a_falsified_verdict.md`（:12/:38-52/:67-75/:145）—— 证据线 A：注入 stock fileId 逐字相同仍模糊；`0f836cfd` body / `7c006-74b9-4f91-a941` uuid。
4. `docs/_r82_b2_netscene_forensics.md`（:62-64/:70-97/:124-134/:196/:258-265）—— 证据线 A：fileId 逐字相同一清晰一模糊、fullpath 字节决定、互斥裁决表。
5. `docs/_r86c_d3_dex_forensics.md`（:28-64/:68-158/:275-295）—— `x0.u` setFilePath(field_fullpath)/forwardFileid/enableHitCheck 映射；`m1.m` 分派只看 `task.e`；native `startC2CUpload` acc=0x109 不可挂。
6. `docs/_r86c_realsend_fix_design.md`（:28-64/:93-106/:153-189/:263-275 + 主会话复核附录）—— 真实发送链设计 + 被核验推翻的「补 fileId+Y」方案。
7. `wxgroup/app/src/main/java/com/lx/wxgroup/core/FileSender.kt`（:158-165/:187/:216-229/:233/:252/:260）—— 真实发送链 send / q1.j 三级覆盖 / RESEND_FILE / armFromForward。
8. `wxgroup/app/src/main/java/com/lx/wxgroup/probe/StockCdnProbe.kt:434-494` —— fbytes/fmd5pre/fidFull 打点实现（确认量纲：`File.length()` + MD5 前 4 字节 = fmd5pre）。
9. 本轮亲跑 `_r35_sns_dis.py bodyx ok0/x0 u`（classes13）—— x0.u 关键指令自算核准（见 Q2 表）。

---

## Q1 · 两轮证据是否彻底统一（统一机制）

### 结论（一句话）

**【真机实证·高 + 推断·高】彻底统一，无残留不一致。** 统一机制 = **清晰/模糊的唯一终局决定量是「服务端回包 `C2CUploadResult.filemd5` 写进消息 `<img md5>` 的字节身份是否为源图 `cff4f5a5`」；其 Java 层唯一可控字节入口是 `ok0/x0.u` 的 `setFilePath(field_fullpath)`（i=0x0009→0x000b，本轮自算核准），mars native 从该文件字节重算 md5——`x0.u` 全程不读 `field_filemd5`（本轮 grep 零命中）。** 本轮补观测把上一轮卡住 no-go 的核心 UNKNOWN（fullpath 真实字节数 + 内容）闭合：A=45522B 且内容 MD5=`cff4f5a5`（源图身份）→ 清晰；B=9554B 且内容 MD5=`0ef205c1`（另图身份）→ 模糊。这与 R81/R82 铁证（fileId 逐字相同仍一清晰一模糊、唯一 correlated 差异 = fullpath 字节）**完全同向**，且新增了「fmd5pre = 回调 md5 = 出口 filemd5 前 8」这条从「本地字节 → 服务端回包 → 对端引用」的**直接实测证据链**。

### 统一机制（把两轮全部事实装进一个因果链）

```
setFilePath(field_fullpath 指向的本地字节)          ← x0.u i=0x0009/0x000b（唯一字节入口，本轮自算核准）
   └─ mars native 从该文件字节算 md5（请求侧 filemd5 Java 不产生；x0.u 零命中 field_filemd5）
        └─ 服务端回包 C2CUploadResult.filemd5 ──→ ok0/x0.onC2CUploadCompleted i=0x002c → tj/f.field_filemd5
                                              └─→ f2.C2 写入消息 <img md5>（R81:93-100）
                                                    ├─ md5=cff4f5a5（源图身份）→ 对端拉原图 → 肉眼清晰
                                                    └─ md5=0ef205c1（另图/缩略图身份）→ 对端拉缩略图 → 肉眼模糊
```

本轮决定性证据——**fmd5pre = 回调 md5 = 出口 filemd5 前 8 三态同源**（`_r86c_q4b_forensics_raw.md:30`）：
- A：`fullpath(45522B)` 内容 MD5=`cff4f5a5` → 回调 `md5=cff4f5a5 hit=1`（stepAB:10）→ 出口 `field_filemd5=cff4f5a52813d02be816367d4c58a8be`（stepAB:12）→ 肉眼清晰。
- B：`fullpath(9554B)` 内容 MD5=`0ef205c1` → 回调 `md5=0ef205c1 hit=3`（stepAB:20）→ 出口 `field_filemd5=0ef205c165e99eb0f4a1c0084e65f1aa`（stepAB:23）→ 肉眼模糊。
- 即：**喂进去的字节内容 MD5 一路原样传到对端 `<img md5>`**，中间没有 fileId/开关能改写它。这是上一轮只有【推断】、本轮升级为【真机实证】的关键一环。

### 逐样本对齐（两轮四态全部落入同一机制）

| 样本 | fileId（入口） | fullpath 字节 | fmd5pre/回包md5 | 肉眼 | 是否落入统一机制 |
|---|---|---|---|---|---|
| R82 stock（22:49） | `305f/0f836cfd` | 36B 占位（R82:93，文件内容=源图指纹类） | 命中原图（R82:126-127/262） | 清晰 | ✅ 字节身份=源图 |
| R81 R80a 注入后（23:44） | `305f/0f836cfd`（与 stock **逐字相同**） | 15906B 缩略图（R82:95） | `0ef205c1`（R81:44-51） | 模糊 | ✅ 字节身份=另图 |
| 本轮 A（02:11） | `305f/0f836cfd`（有） | **45522B 原图字节**（本轮实测） | `cff4f5a5` | 清晰 | ✅ 字节身份=源图 |
| 本轮 B（02:12） | 无（fidLen=0） | **9554B 另图字节**（本轮实测） | `0ef205c1` | 模糊 | ✅ 字节身份=另图 |

**决定性统一点**：R81/R82 已证「fileId 逐字相同却一清晰一模糊 ⇒ fileId 零区分力」；本轮两态在 fileId 上「有/无」不同、在 fullpath 字节上「源图/另图」也不同，但**无论哪种组合，肉眼结果都严格跟随 fullpath 字节身份**（A 源图字节→清晰、B 另图字节→模糊），且 fmd5pre 链条证明这是因果而非相关。上一轮「唯一硬差异是 fileId 有无」的 B 态推断，因本轮字节观测补齐而被归入**伴生变量**（见 Q2）。

### 关键校准：修正上一轮的「36B 占位」方向（本轮未复现）

- **【真机实证·高】** 上一轮与 R82 反复出现的「stock 清晰靠 36B 小占位触发秒传」在**本轮 A 态未复现**：本轮 A 清晰态 fullpath = **45522B 原图字节**（fmd5pre=`cff4f5a5`），既不是 36B 占位、也不是 `fullpathLen=124`（那是路径串长，量纲已纠，`StockCdnProbe.kt:437`）。
- **【推断·高】** 因此统一的不是「占位 vs 字节」这个形态维度，而是更本质的**「fullpath 字节内容 MD5 是否 = 源图身份 `cff4f5a5`」**：R82 的 36B 占位与本轮 A 的 45522B 原图是两种不同形态，但都让服务端认出源图身份；两条模糊态（15906B / 9554B）都是另图身份。**修复选型必须对齐本轮事实（原图字节），不能照抄 36B 占位路径**（Q4 硬约束）。

### 附注：计数器映射两轮翻转（非矛盾，是计数器非决定量的旁证）

`UploadHitCacheType`：R82 旧映射 3=清晰 / 1=模糊（R82:63-64）；本轮 A=1 清晰 / B=3 模糊（stepAB:13/24）——**完全翻转**。这不是两轮矛盾，而是证明该计数器与肉眼无固定映射、**绝非决定量**（Q2 ④）。`hit`(1↔3) 同理。判据一律对肉眼。

### 置信度与边界

- 「两轮彻底统一 / 统一机制」=【真机实证·高（fmd5pre 三态同源链 + 两轮四态对齐）+ 推断·高（fileId 零区分力的跨轮归纳）】。
- 「统一变量 = fullpath 字节内容 MD5 是否源图身份」=【真机实证·高】（本轮 fmd5pre/回调/出口三段实测 + R82:83/93-97 四行差分）。
- 【UNKNOWN 未闭合（不影响本裁决）】`cff4f5a5` 与 `ca47f83e` 是否同一原图（沿用 `_r86c_q4_verdict.md:48`，均为源图身份、肉眼均清晰，不影响机制）；native hitCheck 内部如何由字节合成回包 md5（客户端可观测层已闭合，算法不外推，`startC2CUpload` acc=0x109 不可挂，D3:285）。

`[DONE Q1]`

## Q2 · 决定量定论（充分 / 必要 / 无关 / 伴生）

### 结论（一句话）

**【真机实证·高 + dex 实证·高 + 推断·高】决定「对端引用源图 `cff4f5a5`（肉眼清晰）」的充分必要条件 = 上传 task 的 `field_fullpath` 指向的字节内容 MD5 = 源图身份 `cff4f5a5`（本轮 A 态实测 45522B 原图字节满足、B 态 9554B 另图不满足）。** 逐项：① fullpath 字节内容 = **决定量（充分且必要，唯一 Java 字节入口）**；② fileId 有无 = **非充分（R81 逐字相同反证）+ 本轮伴生（A 有/B 无与字节身份共变，不构成独立决定量）**，必要性【推断·低，机制上非必需】；③ enableHit=true = **hitCheck 参与开关（必要【推断·中】、非充分）+ 两态相同非区分量**；④ UploadHitCacheType 1 vs 3 = **结果计数器（映射两轮翻转），无关**。

### 四变量裁决表

| 变量 | 判定 | 依据（文件+行号/偏移） |
|---|---|---|
| **① `field_fullpath` 指向字节内容 MD5** | **充分且必要（决定量）** | 充分：本轮 A 45522B/fmd5pre=`cff4f5a5`→出口 filemd5=`cff4f5a5`→清晰（stepAB:4/10/12）；必要：B 9554B/另图→`0ef205c1`→模糊（stepAB:15/20/23）；R82:83/93-97 四行差分（36B 源图占位清晰 vs 15906B 缩略图模糊）。唯一字节入口 = `x0.u i=0x0009 iget field_fullpath → i=0x000b setFilePath`（本轮自算核准，见下表） |
| **② fileId 有无（305f/0f836cfd）** | **非充分【真机实证·高】；本轮伴生；必要性【推断·低，机制非必需】** | 非充分铁证：R81:44 注入同串 `0f836cfd` 后 filemd5 仍 `0ef205c1`、仍模糊（"只换头"证伪）；R82:83 四行 fileId 逐字相同仍一清晰一模糊。本轮 A 有/B 无只是与 fullpath 字节共变（A 源图字节、B 另图字节）⇒ 被①吸收为伴生。机制：`x0.u i=0x001b field_fileId → i=0x001d forwardFileid` 只作**转发头/锚点**，不携带字节、不影响请求 md5。出口 fidBody（A `0f836cfd` 回显 / B `4954a2f9` 新分配）是**结果字段**（R81:26 onC2CUploadCompleted），非入口锚点，因果不可倒置 |
| **③ `field_enable_hitcheck=true`** | **hitCheck 参与开关：必要【推断·中】、非充分；两态相同⇒非区分量** | `x0.u i=0x0085 iget → i=0x0087 iput enableHitCheck`（本轮自算核准）；R82:131/D3:86：参与 hitCheck 但不决定引用哪张。本轮 A/B 均 `enableHit=true`（stepAB:4/15）⇒ 对清晰/模糊零区分力。无 false 对照，必要性降为【推断·中】 |
| **④ `UploadHitCacheType` 1 vs 3（=回调 `hit` 1↔3）** | **无关（结果计数器，映射两轮翻转）** | R82 旧映射 3=清晰/1=模糊（R82:63-64）；本轮 A=1 清晰/B=3 模糊（stepAB:13/24），完全翻转；`onC2CUploadCompleted` 它来自服务端 `hitCache`（R81:25）。无稳定映射、不可作决定量或验收判据 |

### dex 实证表（本轮亲跑 `_r35_sns_dis.py bodyx ok0/x0 u` 自算核准）

`ok0/x0.u(Ltj/k;)I`，classes13.dex，`code_off=0x3a98cc`，绝对字节偏移 = `code_off + 16 + 2·i = 0x3a98dc + 2·i`（本轮自算，纠正历史偏移笔误——D3:84-88 对 i=0x1b/0x63/0x85/0xa2 的绝对值标注有误，此处以自算为准）：

| task(tj/k) 字段 | 指令（i / 本轮自算绝对偏移） | → C2CUploadRequest | 角色 |
|---|---|---|---|
| `field_fullpath` | `i=0x0009 @0x3a98ee iget` → `i=0x000b @0x3a98f2 setFilePath` | `filePath` | **唯一字节入口**；请求 md5 由 native 从此处字节重算 |
| `field_fileId` | `i=0x001b @0x3a9912 iget` → `i=0x001d @0x3a9916 iput` | `forwardFileid` | 转发头/锚点，非充分 |
| `field_onlycheckexist` | `i=0x0063 @0x3a99a2` → `i=0x0065 @0x3a99a6` | `checkExistOnly` | 真机恒 false（stepAB:4/15），无关 |
| `field_enable_hitcheck` | `i=0x0085 @0x3a99e6` → `i=0x0087 @0x3a99ea` | `enableHitCheck` | hitCheck 开关，非充分 |
| `field_fake_bigfile_signature` | `i=0x00a2 @0x3a9a20` → `i=0x00a4 @0x3a9a24` | `fakeBigfileSignature` | 两态 fakeSigEmpty=true，无区分力 |
| native 入口 | `i=0x0102 @0x3a9ae0 startSSUpload` / `i=0x0107 @0x3a9aea startC2CUpload` | — | acc=0x109 不可挂（D3:285），不改判 |

**零命中如实记**：本轮 `bodyx ok0/x0 u` 输出中 grep `filemd5` **无命中**——坐实 `x0.u` 不读 `field_filemd5`，请求侧 md5 非 Java 注入，改 task.field_filemd5 无效（R82 Z.3:113-117）。

### 充分条件的精确表述（供 Q4 落码对齐）

> 要让「对端引用源图 `cff4f5a5`（肉眼清晰）」：**充分且必要**地在真实发送链的上传 task 上，使 `field_fullpath` 指向**字节内容 MD5 = 源图 `cff4f5a5`** 的本地文件（本轮 A 态同源、45522B 级）；`enableHit=true` 作为机制开关必须保持（否则 hitCheck 不成立，B-1 先例 `fullpathLen=0→hit=0`，上一轮 Q2:120）；fileId 只作转发头保留（非决定量，不需为「清晰」而补）；不要动 filemd5 / 不追 hit/UploadHitCacheType 计数器。

### 置信度与边界

- 四变量判定、dex 字段映射、零命中 =【dex 实证·高（本轮自算核准）+ 真机实证·高】。
- 「fileId 非充分」=【真机实证·高】（R81 逐字相同 + R82 四行）；「fileId 必要性低/机制非必需」=【推断·低】（本轮无「字节对+无 fileId」的独立样本，但机制上 filemd5 由字节算，且 R81 证明补 fileId 不改结果）。
- 「fullpath 字节充分形态」=【真机实证·高（本轮 45522B 原图字节已实证清晰）】——**本轮不再需要「36B 占位是否可复刻」这个 UNKNOWN**（那是上一轮的卡点，现由 A 态原图字节实证替代）。
- 【UNKNOWN 未闭合（不影响定论）】native hitCheck 内部合成算法（不外推）；`cff4f5a5` 与 `ca47f83e` 是否同一原图。

`[DONE Q2]`

## Q3 · 根因定案（喂错字节 vs 缺 fileId + q1.j 三级定位）

### 结论（一句话）

**【真机实证·高 + dex 实证·高】坐实：模块转发模糊的根因 = 真实发送链 `FileSender.send` 的 `q1.j` 停留在「默认级」`q1.j = snap.localPath`（`FileSender.kt:187`），把 9554B 另图字节（fmd5pre=`0ef205c1`）经 `j2.O → field_fullpath → x0.u setFilePath` 喂给 fullpath（指向错字节）；而非「缺 fileId」。** 体积门槛强约束定位：`q1.j` 三级覆盖中，覆盖 2（P3 `bigImgPath`，`FileSender.kt:224-229`）因「>15KB」且 `P3AdmitGate.FLOOR=64KB`（`ForceForward.kt:108/124`）对 9554B 拒绝而**缺席**；覆盖 1（原生大图 `srcBigImgPath`，`FileSender.kt:216-220`）本轮未把原图字节覆盖进来 ⇒ `q1.j` 停在默认级缩略图路径（`a77.jpg_hevc`，9554B）。dex 锚点：`q1.j → j2 构造 → j2.O i=0x0124 z3(r0.e) → field_fullpath`（realsend design Q1③，code_off `0x2acc44`「未核」）→ `x0.u i=0x0009→0x000b setFilePath`（本轮自算核准 `0x3a98ee/0x3a98f2`）。

### 裁决证据

1. **「喂错字节」=【真机实证·高】** B 态 fullpath 指向文件 `fbytes=9554 fmd5pre=0ef205c1`（stepAB:15），fmd5pre = 回调 md5 = 出口 filemd5 前 8（stepAB:20/23）⇒ 服务端认的、对端拉的都是这份 9554B 另图字节。这是肉眼模糊的直接因，链条闭合、非相关。
2. **「非缺 fileId」=【真机实证·高】** R81:44 铁证：注入与 stock 逐字相同的 fileId（body `0f836cfd`）后 filemd5 仍 `0ef205c1`、仍模糊；R82:83 四行 fileId 逐字相同仍一清晰一模糊；本轮 fmd5pre 链条证明 md5 由字节算、`fileId` 只进 `forwardFileid`（x0.u i=0x001d）不改内容。B 态 fidLen=0 是 `j2.O` 走 `checkUseCdn=false` 本地分支跳过 fileId 写入的**结果**（realsend design Q1③/Q1④），是伴生、不是模糊的因。
3. **q1.j 三级定位（源码 + 体积门槛 + dex）**：
   - 默认级 `q1.j = path = snap.localPath`（`FileSender.kt:187`）——缩略图/ori 本地路径，B 态 9554B `.jpg_hevc` 符合此级。
   - 覆盖 1 原生大图 `forwardCtx.srcBigImgPath`（`FileSender.kt:216-220`，`isNotBlank()` 才覆盖）——若生效应指向大图字节，B 态 9554B 小图说明未生效【推断·中，运行时值未 hook】。
   - 覆盖 2 P3 落盘 `bigImgPath`（`FileSender.kt:224-229`）——条件 `exists && length>15*1024 && !name.startsWith("th_")`；9554B < 15360B，**不达门槛**；且 `bigImgPath` 来源 `copyP3Temp(chosenP3Src)`（`ForceForward.kt:2740`）必过 `P3AdmitGate.admit`（FLOOR=64KB，`ForceForward.kt:108/124`），9554B 必被 `below-floor` 拒。⇒ 覆盖 2 在本轮必缺席。
   - **⇒ 选错发生在默认级未被覆盖**：`q1.j` 最终 = 9554B 另图路径，喂进 `field_fullpath`。
4. **dex 传导实证**【dex 实证·高】：`q1.j` 经 `t81/g.a` 多处传入 `j2` 构造器（realsend design Q1②，标「未核」）；`j2.O` 的 `field_fullpath=z3(r0.e)`（i=0x0124 等，code_off `0x2acc44`「未核」）；`x0.u i=0x0009 @0x3a98ee` 读 `field_fullpath`、`i=0x000b @0x3a98f2 setFilePath`（本轮自算核准）。

### 关键附带发现（直接喂给 Q4）：64KB 门槛与本轮原图冲突

**【真机实证·高 + 推断·高】** 本轮 A 态源图字节仅 **45522B**（stepAB:4），**小于 `P3AdmitGate.FLOOR=64KB=65536B`**（`ForceForward.kt:108`），且 A 态路径尾是 `.jpg_hevc`（非 `P3AdmitGate.IMAGE2_RE` 要求的 `image2/xx/yy/<md5>.jpg` 形态，`ForceForward.kt:114-115/125-128`）。⇒ **照抄 R83 `p3OriginFromMd5`（strictShape=true + 64KB + magic，`ForceForward.kt:2514-2556`）在本轮这张图上会被 `below-floor` 与 `bad-shape` 双重拒绝，拿不到 45522B 原图字节**。这是 Q4 选型必须处理的硬约束（详见 Q4）。

### 置信度与边界

- 「根因 = 喂错字节、非缺 fileId」=【真机实证·高】（fmd5pre 链 + R81/R82 反证）。
- 「q1.j 停在默认级」=【推断·中】：体积门槛（9554B<15KB、<64KB）强支持覆盖 2 缺席；但本轮未直接 hook `q1.j`/`srcBigImgPath` 运行时值【UNKNOWN 未闭合】，覆盖 1 为何未覆盖原图需真机确认。
- `j2.O`/`t81/g.a` code_off 沿用历史标「未核」；x0.u 关键偏移本轮自算核准。

`[DONE Q3]`

## Q4 · 修复路线唯一 go/no-go + 落码规格

### 结论（一句话）

**【真机实证·高 + dex 实证·高 + 推断·高】go（机制级 + 规格级），但带两个必须写进规格的硬修正，且「已清晰」仍须真机肉眼闭合（不预判）。** 上一轮 no-go 的两大 UNKNOWN（fullpath 真实字节数 + 内容）本轮已闭合，决定量与根因均已坐实，修复方向唯一 = **让真实发送链上传 task 的 `field_fullpath` 指向「字节内容 MD5 = 源图 `cff4f5a5`」的原图字节文件**。go 的前提是规格必须同时满足 R81/R82（不退「只换头」）+ 本轮补观测（字节要对、且**原图是 45522B 而非 36B 占位、也不是 15906B 缩略图**）：① **准入判据改为「内容 MD5 == 源图 md5」，不得用 `P3AdmitGate` 的 64KB 体积门槛当决定量**——本轮原图 45522B < 64KB，照抄 `p3OriginFromMd5` 的 64KB+magic 会被 `below-floor` 拒（Q3 附带发现）；② 写点在真实上传 task 的 `field_fullpath`（`x0.u before`，紧邻 `setFilePath`），**不是**补 fileId、不是改 filemd5、不是 36B 占位。

### 为什么现在 go（no-go 前提已消失）

| 上一轮 no-go 理由 | 本轮状态 |
|---|---|
| 本轮 A/B fullpath 真实字节数/内容未观测，量纲错误（把路径串长 124 当字节） | ✅ 闭合：A=45522B/cff4f5a5、B=9554B/0ef205c1（`StockCdnProbe.kt:468-485` 实测） |
| 「36B 占位是否可复刻」UNKNOWN 卡住充分形态 | ✅ 绕开：本轮 A 用**原图字节本身**（45522B）即清晰，不需要复刻占位 |
| fileId 必要性未隔离 | ✅ 判定非决定量（R81 逐字相同反证 + 本轮 fmd5pre 链），不再作为卡点 |
| 「补 fileId+小占位」规格退回 R80a 只换头 | ✅ 新规格改字节，不换头 |

⇒ 裁决从「因缺观测暂缓」升级为「观测闭合、方向唯一、可落码（真机验收坐实）」。

### 唯一正确的落码规格（交实现 lane，本代理不落码）

**写点（唯一）**：hook `ok0/x0.u(Ltj/k;)I` 的 `before`（classes13 `co=0x3a98cc`；`param.args[0]`=真实上传 task，D3 Q2 2.1 已证 = v6），在 `i=0x000b setFilePath` 消费前 `setObjectField(task,"field_fullpath", 原图字节路径)`。这是离字节消费点最近、最不易被中间逻辑覆盖的写点（`x0.u i=0x0009 @0x3a98ee` 读 fullpath → `i=0x000b @0x3a98f2` setFilePath，本轮自算核准）。
- 与 `FileSender.kt:224-229`（q1.j 的 `bigImgPath` 覆盖）的关系：q1.j 是更上游的同一语义点，但它自带 `>15KB` 与 `P3AdmitGate 64KB` 门槛、且 `j2.O` 还会 `z3(r0.e)` 再解析，覆盖链更长；**首选 `x0.u before` 直写 `field_fullpath`**，q1.j 三级覆盖保持不动（避免动已闭环链路）。
- 闸门：复用 `ForwardCdnFieldHook.arm`（`ForwardCdnFieldHook.kt:121-152`）的一次性 pending + TTL，只在模块转发那一次 task 注入；失败/`clear()`（`FileSender.kt:260`）/超时不碰 task，普通发图逐字节不变。

**原图字节路径的解析（关键，两处硬修正）**：
1. 来源仍走 R83 已验范式 `p3OriginFromMd5`（`ForceForward.kt:2514-2556`）：从 img 抠源图 md5 → 从 localPath 取 `MicroMsg/<hash>` 根 → 拼 `image2/<md5[0:2]>/<md5[2:4]>/<md5>.jpg`。
2. **修正 A（准入判据）**：不能以 `P3AdmitGate.admit(f,true)`（`ForceForward.kt:125-129` 的 64KB FLOOR + strictShape magic + image2 形态）为放行条件——本轮原图 45522B < `FLOOR=65536`（`ForceForward.kt:108`），会被 `below-floor` 拒；且 A 态 fullpath 是 `.jpg_hevc` 形态，非 `IMAGE2_RE`（`ForceForward.kt:114-115`）。**改为内容校验：读该候选文件的字节 MD5，== 源图 md5（cff4f5a5，来自消息 xml `$md5` / `a3→r0.u→g9.c`）即放行**。这是唯一与本轮真机事实一致的准入（决定量 = 字节内容 MD5，Q2①）。**不要全局下调 `P3AdmitGate.FLOOR`**——那会回退 R85 止血；用独立的「上传 fullpath 原图字节解析器 + MD5 准入」，作用域仅限本次 task 的 `field_fullpath`。
3. **修正 B（字节可获得性前置）**：必须在模块转发的真实设备上有「内容 MD5=cff4f5a5」的本地文件（p3OriginFromMd5 拼出的 image2 路径，或 `.jpg_hevc` 缓存）。这是 go 的**唯一真机前置**：Q5 第一步先只读确认该文件存在且 MD5 命中；拿不到 ⇒ 本规格无从写入原图字节 ⇒ 按 Q5 回退 no-go，不得改用「空占位/缩略图」硬凑。

**其余字段逐项裁决（明确「是否还需补」）**：

| 字段 | 裁决 | 理由 / 取法 |
|---|---|---|
| `field_fullpath` | **唯一要改的字段**：原图字节路径（内容 MD5=cff4f5a5） | 决定量字节入口；x0.u i=0x0009→0x000b |
| `field_fileId` | **非决定量，不作为修复手段**；建议保留/对齐 A 字段集（低风险项，**不宣称致清晰**），来源范式 `d3.O8().a3(talker,msgSvrId)→r0.u→g9.c(u,"msg","")→map[$.cdnbigimgurl] ?: map[$.cdnmidimgurl]`（`ForwardCdnFieldHook.kt:132-144`） | R81 逐字相同仍模糊证伪其为决定量；Q2② 伴生。补它只为字段对齐，防「字节对但缺锚点」的少见情形，不是清晰成因 |
| `field_enable_hitcheck` | **保持 true**（必须） | hitCheck 参与开关（Q2③），B-1 先例 fullpath 空→hit=0；A/B 两态本就 true，无需「新补」 |
| `field_fake_bigfile_signature` | 对齐 `""`（低风险，非决定量） | A 态 fakeSigEmpty=true；x0.u i=0x00a2→0x00a4，不影响字节 |
| `field_filemd5` | **不动** | x0.u 全程不读（本轮 grep 零命中）；改了无效（R82 Z.3） |
| `field_onlycheckexist` | 保持 false | 真机恒 false、非开关（R82 Z.1） |
| `q1.j` 三级覆盖 / `l1.a()` | **不动** | 保持已闭环发送链；写点放在 x0.u 更靠近消费点 |

**同时满足两轮的核验**：
- R81/R82：本规格换的是 fullpath 字节（身），不是 fileId 头 ⇒ 不退「只换头」；fileId 只作字段对齐、不宣称决定。
- 本轮补观测：fullpath 指向 45522B 原图字节（fmd5pre=cff4f5a5），非 36B 占位、非 9554B 缩略图 ⇒ 字节对齐 A 清晰态。

### go 的边界（禁止越界表述）

- 【推断·高，非已证】「改 fullpath 为原图字节后肉眼清晰」仍是**机制级推断 + 真机待验**，本轮只证 A 态原图字节→清晰、B 态另图字节→模糊，未做「A→B→A」落码后翻转。禁止写「已清晰」。
- 【UNKNOWN 未闭合，随规格携带】模块转发设备上「MD5=cff4f5a5 原图字节文件」的可获得性（Q5 前置 1）；native hitCheck 内部合成算法（不改判，字节决定论已在客户端可观测层闭合）。
- 【UNKNOWN 未闭合】覆盖 1 `srcBigImgPath` 运行时为何未指原图（Q3），不影响写点规格（x0.u 直写绕过覆盖链）。

`[DONE Q4]`

## Q5 · 落码后真机验收判据（肉眼终审 + 互斥表）

### 结论（一句话）

**【判据设计；机制级【dex 实证·高】、结果级必须真机肉眼闭合】唯一 go 坐实判据 = 对端肉眼「清晰」（终审），且辅助看 SCD `fmd5pre`/回调 `md5`/出口 `field_filemd5` 三者从 `0ef205c1` 翻回源图 `cff4f5a5`；计数器 `hit` / `UploadHitCacheType` 只作旁证（映射两轮翻转，绝不作结论）。落码后跑「原图字节可获得性只读确认 → A(清晰)→B(模糊) 基线 → 开闸模块转发 → A→B→A 肉眼翻转」，翻转成立才 go 坐实；仍模糊/不可获得/崩溃按互斥表处置或回退。**

### 落码后真机验收最小步

1. **前置只读确认（go 的唯一前置，Q4 修正 B）**：不开闸，用现有 SCD 只读探针/文件系统读源图 md5 对应的本地文件（`p3OriginFromMd5` 拼的 image2 路径或 `.jpg_hevc` 缓存），确认存在 + 内容字节 MD5 前 8 == `cff4f5a5`。拿不到 ⇒ 直接按互斥表「字节不可获得」回退，不落码生效。
2. **基线对照**：不开闸，同图跑 A 原生转发（肉眼清晰，记 fmd5pre=45522B/cff4f5a5）、B 模块转发（肉眼模糊，记 9554B/0ef205c1），站住本轮事实。
3. **开闸落码**：装含本规格 APK，双探针 SCD+B6X 全开；模块「转发（模块）」→ `arm` 装 pending（含 fileId 对齐 + 原图字节路径）→ `x0.u before` 写 `field_fullpath`=原图字节路径。
4. **读真实 task + 出口（机制旁证，非结论）**：SCD-c2c / x0.u before 看 `fbytes≈45522 fmd5pre=cff4f5a5`（**关键：写进去的字节对不对**）、`enableHit=true`、`fakeSigEmpty=true`；x0cb/出口 `field_filemd5=cff4f5a5`（非 0ef205c1）；fileId 对齐项 `fidPre=305f`（旁证，不决定）。
5. **肉眼终审**：对端收图肉眼清晰/模糊——**唯一 go 判据**。 auxiliary 计数器 `hit`/`UploadHitCacheType` 两轮翻转，只记录不裁决。

### 互斥判据表（清晰→go / 仍模糊→下一步 / 崩溃→回退）

| 观测（肉眼最高，字节/MD5 辅助） | 机制旁证 | 裁决 | 下一步 |
|---|---|---|---|
| **对端肉眼清晰 + A→B→A 翻转成立**（清晰→模糊→清晰） | 写入 fullpath 的 `fbytes≈45522 fmd5pre=cff4f5a5`；出口 `filemd5=cff4f5a5` | **go 坐实，修复成立** | 固化 `x0.u before` 写点 + 闸门；SCD 探针回归只打前缀 |
| 肉眼清晰，但出口 filemd5/计数器矛盾 | 以肉眼为准 | **go（终审压倒旁证）**，记异常待查 | 追加一轮取数确认 md5 身份映射，不改判 go |
| **仍模糊**，写入 fullpath `fmd5pre=0ef205c1` / `fbytes≈9554`（字节没换成原图） | pending 未命中 / 解析器落到缩略图 / 64KB 门槛误拒 | **接入失败或准入判据错（非方案证伪）** | 查 `arm skip`/TTL/`x0.u` hook 是否生效；查解析器是否被 P3AdmitGate 64KB 拦截（Q4 修正 A）；修后重测一轮 |
| **仍模糊**，写入 fullpath `fmd5pre=cff4f5a5` 但出口 `filemd5=0ef205c1` | 字节对但回包仍认另图 | **机制未闭环（native hitCheck 未认原图）** | 同刻 stock 对照是否清晰；核对 fileId 对齐项（305f）是否进 forwardFileid；仍失败如实 no-go，不硬推 |
| **前置 1 失败**：本地无 `MD5=cff4f5a5` 原图字节文件 | 解析器拼路径不存在 / MD5 不符 | **go 前置不满足 → no-go（字节不可获得）** | 补「原图字节下载/落盘」能力（另案），不得用空占位/缩略图硬凑 |
| **微信崩溃 / 发送失败 / 双发消息** | hook 抛异常 / `l1.a()=false` | **no-go，立即回退** | 关广播开关或卸 hook；恢复现状；按 `FileSender.kt:260 clear()` 清 pending 防串味 |

### 判据纪律（对着肉眼，不对计数器）

- 唯一坐实条件 = **肉眼清晰 + A→B→A 翻转**；`fmd5pre`/`filemd5=cff4f5a5` 是必要的机制旁证（证明字节对），但不是终审。
- `hit`(1↔3)、`UploadHitCacheType`(1↔3) 两轮映射翻转（Q1 附注 / Q2④），**不得**作为清晰/模糊的判据或「机制通」的证据。
- 「字节写对了但肉眼仍模糊」≠ 已证伪方案，按表区分接入失败 / 机制未闭环 / 字节不可获得三种，如实处置。
- 禁止把「机制可行」写成「已清晰」；结果级 go 只由真机肉眼翻转签发。

`[DONE Q5]`

---

## 统一机制 / 修复路线小结

**统一机制（【真机实证·高 + 推断·高】）**：清晰/模糊的唯一终局决定量 = 服务端回包 `C2CUploadResult.filemd5` 写进消息 `<img md5>` 的字节身份是否为源图 `cff4f5a5`。唯一 Java 字节入口 = `ok0/x0.u` 的 `setFilePath(field_fullpath)`（`i=0x0009→0x000b`，本轮自算核准 `0x3a98ee/0x3a98f2`）；请求 md5 由 mars native 从该文件字节重算（`x0.u` 不读 `field_filemd5`，本轮 grep 零命中）。本轮补观测给出决定性证据链：`fullpath 字节内容 MD5(fmd5pre) = 回调 md5 = 出口 filemd5 前 8`，A 全 `cff4f5a5`（清晰）/ B 全 `0ef205c1`（模糊）。fileId 只进 `forwardFileid`（转发头），R81 逐字相同反证其非决定量；enableHit 是 hitCheck 开关；onlycheckexist/filemd5/hit/UploadHitCacheType 均非决定量。两轮表面冲突的真因 = 上一轮把「路径串长 124」误当 fullpath 字节形态，且未观测真实字节——本轮实测后冲突消失。

**修复路线小结（go，带两硬修正）**：在 `ok0/x0.u before` 把真实上传 task 的 `field_fullpath` 改为「字节内容 MD5=源图 `cff4f5a5`」的原图字节路径（本轮 A 态 45522B）。硬修正 A：准入用**内容 MD5 命中**，不得照抄 `P3AdmitGate` 64KB 体积门槛（45522B < 64KB 会被 `below-floor` 拒）；硬修正 B：前置确认设备上存在该原图字节文件，否则 no-go。fileId 仅字段对齐（a3→r0.u→g9.c 已验范式）、不宣称致清晰；enableHit 保持 true；filemd5 不动；q1.j/l1.a() 不动。结果级以肉眼 A→B→A 翻转坐实，不预判「已清晰」。

**未闭合清单（随规格携带，不影响本裁决方向）**：
- 【UNKNOWN 未闭合】模块转发设备上「MD5=cff4f5a5 原图字节文件」的可获得性（Q4 前置 / Q5 前置 1）——go 的唯一真机前置。
- 【UNKNOWN 未闭合】native hitCheck 内部由字节合成回包 md5 的算法（客户端可观测层已闭合，算法不外推；`startC2CUpload` acc=0x109 不可挂，D3:285）。
- 【UNKNOWN 未闭合】`cff4f5a5` 与 `ca47f83e` 是否同一原图（均源图身份、均肉眼清晰，不影响机制）。
- 【UNKNOWN 未闭合】覆盖 1 `srcBigImgPath` 运行时为何未指原图、本轮 `q1.j` 运行时值未直接 hook（Q3；x0.u 直写绕过覆盖链，不影响规格）。

---

## 三件套

**① 两轮证据是否统一（统一机制 or 如实 UNKNOWN）？**
**彻底统一（无残留不一致）。** 统一机制 = 清晰/模糊唯一决定量是服务端回包 `filemd5` 写进消息 `<img md5>` 的字节身份是否为源图 `cff4f5a5`；唯一字节入口 = `x0.u setFilePath(field_fullpath)`，mars 从该文件字节重算 md5；本轮 `fmd5pre=回调md5=出口filemd5前8` 三态同源把「本地字节→服务端回包→对端引用」直接实证。A=45522B 原图字节→清晰 / B=9554B 另图字节→模糊，与 R81/R82（fileId 逐字相同仍一清晰一模糊）完全同向；上一轮 no-go 的两大 UNKNOWN（真实字节数+内容、占位形态）本轮闭合。「36B 占位触发秒传」本轮未复现——A 态靠原图字节本身清晰，规格据此改「字节内容 MD5=cff4f5a5」为准入，不照抄占位路径。

**② 现在落码 go/no-go（一句话）？**
**go（机制级+规格级，带两硬修正），但结果级「已清晰」必须由真机肉眼 A→B→A 翻转坐实、不预判；写点=真实上传 task 的 `field_fullpath`（`x0.u before`），非补 fileId、非只换头、非 36B 占位。**

**③ 若 go：唯一正确规格 + 最小真机步（若 no-go 不适用）：**
**规格**：hook `ok0/x0.u(Ltj/k;)I` before（classes13 `co=0x3a98cc`，`param.args[0]`=真实 task），在 `i=0x000b setFilePath` 前 `setObjectField(task,"field_fullpath", 原图字节路径)`，路径来源走 `p3OriginFromMd5`（`ForceForward.kt:2514-2556`）拼 image2 路径；**准入改为「文件字节 MD5 == 源图 cff4f5a5」**（硬修正 A：不得用 `P3AdmitGate` 64KB 体积门槛，45522B<64KB 会被 below-floor 拒、A 态 `.jpg_hevc` 非 IMAGE2_RE；不下调全局 FLOOR，用独立解析器）；fileId 仅按 `a3→r0.u→g9.c`（`ForwardCdnFieldHook.kt:132-144`）对齐字段集、不宣称致清晰；enableHit 保持 true、fakeSig=""、filemd5 不动、onlycheckexist 保持 false、q1.j 三级覆盖与 l1.a() 不动；闸门复用 `ForwardCdnFieldHook` 一次性 pending+TTL，失败清 pending。**最小真机步**：①只读确认本地存在 MD5=cff4f5a5 原图字节文件（否则 no-go）；②A 原生清晰 / B 模块模糊基线；③开闸跑模块转发，读 SCD 写入 fullpath 的 `fmd5pre` 与出口 `filemd5` 是否=cff4f5a5（旁证）；④**肉眼清晰 + A→B→A 翻转 = go 坐实**，仍模糊/字节不可获得/崩溃按 Q5 互斥表处置。`hit`/`UploadHitCacheType` 两轮翻转，绝不作判据。

[DONE ALL]

> 回传摘要：产物 `docs/_r86c_q4b_final_verdict.md`。Q1 两轮彻底统一——决定量=回包 filemd5 写进 `<img md5>` 的字节身份，入口=`x0.u setFilePath(field_fullpath)`，本轮 `fmd5pre=回调md5=出口filemd5前8` 直接实证；36B 占位未复现，A 靠 45522B 原图字节清晰。Q2 决定量定论：①fullpath 字节内容=充分必要；②fileId 非充分+伴生、必要性低；③enableHit 开关必要非充分；④UploadHitCacheType 无关（两轮翻转）。Q3 根因坐实=q1.j 停在默认级喂 9554B 另图字节（非缺 fileId），dex 锚点 j2.O z3→field_fullpath→x0.u setFilePath。Q4 go（带两硬修正：准入用内容 MD5 非 64KB 门槛；前置确认原图字节可获得），写点=x0.u before 改 field_fullpath。Q5 肉眼终审 A→B→A 翻转 go 坐实，互斥表含字节不可获得/仍模糊/崩溃处置。三件套：统一 / go / 单 hook 写 field_fullpath 规格 + 最小真机步。

---

## 主会话独立复核附录（2026-11-24 02:2x，不盲从）

对裁决三处高风险断言做了源码/dex 独立核验：

1. **✅ FLOOR 门槛属实（Q3 附带发现 / Q4 修正 A 成立）**：`ForceForward.kt:108 FLOOR=64*1024L=65536`，`admit(): f.length() <= FLOOR → below-floor`；`IMAGE2_RE` 只匹配 `image2/xx/yy/<32md5>.<ext>`。故 45522B 会 below-floor、`.jpg_hevc` 会 bad-shape，照抄 `p3OriginFromMd5(strictShape=true)` 必失败——修正 A 方向正确。

2. **✅ q1.j 三级覆盖属实（Q3）**：`FileSender.kt:187` 默认 `setObjectField(q1,"j",path)`；`:216-220` 原生大图 `srcBigImgPath` 覆盖；`:224-229` P3 `bigImgPath` 覆盖且门槛 `exists && length>15*1024 && !th_`。

3. **⚠️ 发现裁决未充分处理的两处落码阻断风险（需 Pro 收口，主会话不自裁）**：
   - **写点 x0.u 是全新 hook 点**：项目当前**没有**任何 `ok0/x0.u` hook（grep `x0`+`"u"` 零命中），现有 `ForwardCdnFieldHook` 挂 `m1.e before` 且其 `pending` 由 m1.e 消费。裁决 Q4「x0.u before 复用 ForwardCdnFieldHook 的 pending+TTL」表述不准——x0.u 需新增 findClass/findMethod/hook 安装 + 独立 pending 消费逻辑（或改在已挂的 m1.e before 写 fullpath）。写点可达性与最小改动面需重新裁决。
   - **原图字节可获得性这条 go 前置被放在「落码后」确认，顺序错**：裁决修正 A 叫「p3OriginFromMd5 拼 image2 路径 + 内容 MD5 准入」，但本轮 A 态清晰的 45522B 文件路径尾是 `.jpg_hevc`（`6ff.jpg_hevc`），**不在 image2 分桶**，p3OriginFromMd5 拼不出它；拼出的 image2 候选是否存在/MD5 是否=源图，裁决自己也标 UNKNOWN（:255）却放到 Q5 落码后前置 1。⇒ 应先做**落码前的只读取证**（用现有 SCD 探针/文件系统确认设备上到底有没有内容 MD5=cff4f5a5 的本地文件、在什么路径），拿到答案再定 go 落码，否则白落码。
   - 另需核验：即便拿到原图字节文件，写 `field_fullpath` 后 must 保证 `x0.u setFilePath` 消费的是该文件而非被 `j2.O z3(r0.e)` 再解析覆盖（裁决 dex 锚点 `j2.O` 标「未核」）。

**结论**：裁决的**机制层**（决定量=fullpath 字节内容、fileId 非决定量、两轮统一）与主会话核验一致，可信；但**落码规格层**有两处未闭合（x0.u 新 hook 点 + 原图字节可获得性前置顺序），不能直接照规格落码。下一步 = 派 Pro 针对性收口这两点；其中「原图字节可获得性」优先用**零落码只读取证**先证（现有探针 + 文件系统），符合「真机前交叉验证」纪律。
