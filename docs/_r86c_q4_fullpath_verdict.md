# R86c Q4 收口裁决：`field_fullpath` 规格冲突（X 真实原图 vs Y 占位+fileId）唯一选型

> 角色：收口裁决子代理（降档重派 · Pro 显式档 402 · 别名路由）。任务性质 = 根因推理 + 判据设计。
> 唯一目标：在前一轮 `docs/_r86c_q4_verdict.md` 判「补 `field_fullpath`」的基础上，解决「`field_fullpath` 该指向什么文件」的 X/Y 互斥冲突，给唯一推荐 + 可机械落码规格。
> 纪律：只读（禁 gradle / adb / 改 wxgroup 源码 / git commit）。四档标注：【dex 实证】/【真机实证】/【推断·高|中|低】/【UNKNOWN 未闭合】。「A 推 B」只认 invoke 递增/真机时间戳；零命中如实记；偏移自算核准。**判据对着肉眼清晰/模糊，不对内部计数器自说自话。**
> 落盘策略：分步。先建骨架 + Q1~Q5 + 最终规格立即写盘；再三件套 + `[DONE ALL]`。
> 状态：骨架 + Q1~Q5 + 最终规格已落盘；三件套 pending。

---

## 0. 冲突描述（要收口的唯一问题）

前一轮裁决 `_r86c_q4_verdict.md` 判自建 `B6CheckExistProbe` 补三字段（`field_fullpath` / `field_enable_hitcheck=true` / `field_fake_bigfile_signature=""`），但**对 `field_fullpath` 指向何种文件留下两个互斥规格**，且主会话独立复核附录（同文件 :202-206）明确指出前一轮未引用 R82 互斥裁决表、未解释为何选 X：

- **规格 X（前一轮 Q4/Q5 建议）**：`field_fullpath` = R83 `p3OriginFromMd5` 拼出的**真实原图大文件**（`image2/<2>/<2>/<md5>.jpg`，64KB+，经 P3AdmitGate 64KB+magic+分桶准入）。理由「mars 从 filePath 字节重算 md5，喂原图字节才能命中 cff4f5a5 原图」。
- **规格 Y（R82 历史实证）**：`field_fullpath` = **小体积占位文件**（R82 为 36B `.jpg_`；本轮 A 为 124B `.jpg_hevc`），靠**原图 fileId(305f) + enableHit=true** 触发服务端秒传引用→清晰；占位字节本身非原图字节源。证据：R82 `:62/70/93-97/124-134/196/207/262-265`、R79 `:10/20/24-26/44`。

本裁决的终审判据 = **用户肉眼清晰/模糊**（本轮 A 清晰 / B 模糊），一切内部计数器（hit / exist / UploadHitCacheType）只作机制旁证，不作结论依据。

### 两轮 stock 清晰态 fullpath 形态事实（禁止强行统一）

| 轮次 / 样本 | fullpath 形态 | 体积 | fileId | enableHit | 肉眼 | UploadHitCacheType |
|---|---|---|---|---|---|---|
| R82（22:49 stock，216b 坏图场景） | `image2/88/2d/882d...jpg_`（后缀 `.jpg_`） | **36B 占位** | 305f… | true | 清晰 | =3（按 R82 旧映射 3=清晰） |
| 本轮 A（01:21 stock） | `c09.jpg_hevc`（后缀 `.jpg_hevc`） | **124B 小文件** | 305f…(len194) | true | 清晰 | =1（本轮映射翻转） |

两者**形态不同**（36B `.jpg_` vs 124B `.jpg_hevc`），但都是**小体积占位**（远小于 64KB 真原图），都不是原图字节源。本裁决分别对待、不强行说成同一形态（见 Q1）。

---

## Q1 · R82「36B 占位」与本轮 A「124B `.jpg_hevc`」是否矛盾？A 段那个文件是不是占位？两轮形态为何不同？

### 结论（一句话）

**【真机实证·高 + 推断·高】不矛盾，也不强行统一。** A 段 124B `c09.jpg_hevc` **是占位/小引用文件，不是原图字节源**（体积 124B ≪ 真原图 64KB+/335KB；`_r86c_q4_forensics_raw.md:21` 自身标注「带原图字节」只是日志措辞，体积证据否定其为完整原图）。两轮 stock 清晰态的 fullpath **角色相同（小占位）但物理形态不同**（36B `.jpg_` vs 124B `.jpg_hevc`），是微信不同场景/版本生成的 ImgInfo2 占位命名差异，非同一文件。

### 证据链

1. **占位非字节源是已定案**【真机实证·高，R79 `:10/24-26/44`】stock 清晰的字节在 **CDN 服务端**，本地 36B 是 ImgInfo2 新记录的路径占位，不是字节源；`882d...jpg`=36B、`216b...jpg`=36B、`th_216b...`=15906B，本地根本无大图字节却清晰。⇒ 占位文件从来不是靠自身字节内容赢的。
2. **A 段 124B 同样不是原图字节**【真机实证·高 + 推断·高】`fullpathLen=124 pathTail=c09.jpg_hevc`（`_r86c_q4_forensics_raw.md:21`）；124B 远小于 P3AdmitGate 的 `FLOOR=64KB`（`ForceForward.kt:108`），也远小于注释所举真原图 335644B/882950B（`:107`）。`.jpg_hevc` 后缀带 HEVC 转码语义、非合法完整原图名。⇒ 判为占位/小引用文件【推断·高】。
3. **形态差异的解释**【推断·中】R82 的 `.jpg_`（带下划线，"占位/未就绪"语义）与 A 的 `.jpg_hevc`（HEVC 转码路径占位）是**微信不同版本/不同转发场景**的 ImgInfo2 占位命名规范；体积 36→124 差异是占位头信息量差异。**两轮都是"小占位"这一同类角色，物理形态不同，如实并列，不合并。**
4. **真正一致的是「小占位 + 原图 fileId + enableHit」三元组**【真机实证·高】两轮清晰态共同点：fullpath 为小占位（36B/124B）、fileId=305f…、enableHit=true；没有任何一轮靠"原图大字节"走 checkExist 命中。

### 置信度与边界
- "A 段 124B 是占位非原图字节源" =【推断·高】（体积硬证据 + R79 占位定案）。
- "两轮形态差异源于版本/场景占位命名规范" =【推断·中】（无 dex 直接规定命名规则，由后缀语义 + 体积反推）。
- 36B 与 124B 占位文件的**具体字节内容/是否 HEVC 指纹文件** =【UNKNOWN 未闭合】（未反汇编占位文件内容，不编造）。

`[DONE Q1]`

---

## Q2 · mars hitCheck 的 md5 由什么决定：filePath 字节内容，还是 fileId(305f) 锚点 + 任意占位字节？

### 结论（一句话）

**【dex 实证·高 + 真机实证·高 + 推断·高；native 内部合成机制【UNKNOWN 未闭合】】机制定论：三入口分工明确——`fullpath` 是「hitCheck 能否启动」的必要条件（提供非空字节源），`fileId(305f)` 是「引用哪张图（原图 vs 缩略图）」的决定量，`enableHitCheck=true` 是开关；请求侧 md5 **不是**单纯由 filePath 的"原图字节内容"决定。** 引用哪张图靠 fileId 锚点，占位字节只负责让 hitCheck 流程启动。

### 证据链

1. **dex：三个入口都进 C2CUploadRequest**【dex 实证·高，R82 Q1.2 表 / Z.3，`ok0/x0.u` co=0x3a98cc】
   - `0x0009 field_fullpath → setFilePath()`（唯一字节入口）；
   - `0x001d field_fileId → forwardFileid`（转发引用锚点）；
   - `0x0085 field_enable_hitcheck → enableHitCheck`；
   - 全程**不读 field_filemd5**（请求 md5 由 mars native 从 filePath 字节算）——`ok0/x0.u` 过滤 md5 零命中（R82 Z.3）。
2. **fullpath = hitCheck 启动的必要条件**【真机实证·高】B-1 `fullpathLen=0 → hit=0 exist=false md5Len=0`（`_r86c_q4_forensics_raw.md:41-45`）：即便带 fileId(305f)+源图 md5，无字节入口则 hitCheck 根本没跑。A/B-2 `fullpathLen=124 → hit≠0 exist=true`（`:21/:48-50`）。⇒ fullpath 是"能不能命中"的门槛，非"命中谁"的决定量。
3. **fileId = 引用哪张图的决定量（核心反证）**【真机实证·高】A 与 B-2 的 fullpath **同为 124B `.jpg_hevc`**（体积、后缀同形态），唯一硬差异是 fileId 有无：
   - A：`fidLen=194 fidPre=305f` → 命中 `cff4f5a5`（原图身份）→ **清晰**；
   - B-2：`fidLen=0`（无 fileId）→ 命中 `0ef205c1`（缩略图身份）→ **模糊**（`_r86c_q4_forensics_raw.md:48-56`）。
   ⇒ 「同为小占位、fileId 有无决定清晰/模糊」= fileId 主导引用哪张图，占位字节内容不是决定量【推断·高】。
4. **"md5 由原图字节内容决定"被证伪**【推断·高】36B `.jpg_` / 124B `.jpg_hevc` 占位字节的 MD5 在物理上不可能等于完整原图 md5 `cff4f5a5`，但 stock 两轮都命中 `cff4f5a5` 且清晰。⇒ 请求侧 md5 绝非"原图字节内容"的函数；`cff4f5a5` 是服务端凭 fileId(305f) 原图锚点返回的身份，占位字节只是触发令牌。
5. **R82 自洽印证**【真机实证·高，R82 `:126-134/196/207`】「mars 从 filePath 算 md5 做 hitCheck：命中云端原图（36B 占位触发）→ 只引用；命中缩略图（15906B）→ 真传缩略图」。R82 把"命中谁"归因于占位字节内容，但本轮 A/B-2 同为 124B 占位却命中不同图 ⇒ **修正为 fileId 锚点主导**，占位字节内容只是触发条件之一。

### 置信度与边界
- "fullpath 是启动门槛、fileId 是引用决定量、enableHit 是开关" =【dex 实证 + 真机实证·高】，闭合。
- "请求 md5 非原图字节内容函数，原图身份靠 fileId 锚点" =【推断·高】（36B/124B 占位 md5≠原图 md5 的物理事实 + A/B-2 同占位不同结果）。
- **native hitCheck 内部如何综合 `forwardFileid` 与 `setFilePath` 字节算出最终 md5=cff4f5a5** =【UNKNOWN 未闭合】（native marshaller/hitCheck 未反汇编，Java 层只见两个入口并存；不外推其合成算法）。

`[DONE Q2]`

---

## Q3（核心选型）· 唯一推荐：X（真实原图大文件）还是 Y（占位 + 保留 fileId）？

### 结论（一句话）

**【真机实证·高 + 推断·高】唯一推荐 Y：`field_fullpath` 指向一个非空小占位文件（≈36B~124B，stock 同款即可）+ 保留原图 fileId(305f…) + enableHit=true + fakeSig=""。不选 X。** 理由是 Y 与两轮 stock 清晰真机形态一致、被 R82 `:262` 路径①明确列为已走通；X 押注在未证的「R83 字节 md5=cff4f5a5」前提上，且 B-2 已演示"字节路线"会命中缩略图→模糊。

### 选型对比（代价 + 证据）

| 维度 | X（R83 真实原图大文件） | Y（小占位 + 原图 fileId）【推荐】 |
|---|---|---|
| 与两轮 stock 清晰形态 | ❌ 不符——stock 从未用大字节走 checkExist 命中 | ✅ 完全一致（36B `.jpg_` / 124B `.jpg_hevc` 都是小占位） |
| R82 `:262-263` 裁决表 | 近亲路径②（15906B 内容对→清晰），但非 stock 实测主路 | ✅ 路径①（≈36B + 原图md5 + true → 清晰·秒传引用） |
| 决定量命门（Q2） | 押注"字节内容 md5=cff4f5a5"——该前提【UNKNOWN】，且 md5 非字节内容决定 | ✅ 押注 fileId(305f) 锚点——A 真机已证其主导原图引用 |
| B-2 反证风险 | ⚠️ B-2 正是"带 124B `.jpg_hevc` 字节"却命中 0ef205c1 缩略图→模糊 | ✅ Y 保留 fileId，规避 B-2 的无 fileId 陷阱 |
| md5=cff4f5a5 前提 | 【UNKNOWN 未闭合】（前一轮 Q1/Q3/Q4 自标；cff4f5a5 vs ca47f83e 关系未证） | 不依赖该前提（引用哪张图靠 fileId，见 Q2） |
| 落码成本 | 高：需暴露 `p3OriginFromMd5` 包装 + 过 P3AdmitGate 64KB+magic+分桶；拼径可能失败（R76 真机曾 miss） | 低：指向已存在小占位文件 / 构造固定小占位，无 magic/64KB 要求 |
| 判据对着肉眼 | 需先证字节 md5 相等，链条长 | 直接复刻 A 段三元组，肉眼翻转可验证 |

### 为什么唯一选 Y（不许两可的理由）
1. **证据等级**：两轮 stock 清晰态（R82 22:49 + 本轮 A 01:21）都是「小占位 + fileId + enableHit」，这是**唯一被真机反复验证的清晰配方**【真机实证·高】；X 没有任何一轮真机证据支撑其走 checkExist 命中原图。
2. **机制对症**（Q2）：引用哪张图由 fileId 主导，不是由 fullpath 字节内容主导。X 优化错了变量（字节内容），Y 优化的是决定量（保住 fileId）+ 满足启动门槛（非空占位）。
3. **风险规避**：B-2 反证"带字节但无 fileId"→模糊。X 若不慎让 fileId 丢失或字节 md5 落 0ef205c1，直接重演 B-2。
4. **代价**：Y 无需 P3AdmitGate 拼径（该 gate 是 (b)-1 真上传原图字节路线才需要，checkExist 秒传引用根本不要求图片内容合规——stock 36B `.jpg_` 连合法 jpg 后缀都不是仍能清晰）。

> R82 `:263` 路径②承认"传字节但内容对"也能清晰，故 X 理论上非绝对不可行；但题目要求唯一推荐，**基于证据充分度/命门变量/风险/代价，唯一选 Y，不选 X**。

`[DONE Q3]`

---

## Q4 · 若选 X，R83 真实原图文件 md5 是否可能 ≠ cff4f5a5？不等会怎样？（对已弃选 X 的风险回答）

### 结论（一句话）

**【UNKNOWN 未闭合 + 推断·高】可能 ≠ cff4f5a5（前一轮已自标该 UNKNOWN，本轮无法闭合）。若不等：mars 用该字节算出的 md5 去 hitCheck，会命中另图（如 B-2 的 `0ef205c1` 缩略图身份）或干脆不命中→对端模糊，即 X 失败。** 这正是本轮弃 X 选 Y 的关键风险之一。

### 证据链
1. **前提未证**【UNKNOWN 未闭合，前一轮 `_r86c_q4_verdict.md:48/120/152`】`cff4f5a5`（A 命中 md5）与 `ca47f83e`（源 xml md5 / B-1 传入 md5Pre / R83 拼径用的 md5）关系未证；R83 `p3OriginFromMd5` 从 img 抠的 md5 拼 `image2/<2>/<2>/<md5>.jpg`（`ForceForward.kt:2537-2548`），其文件字节 md5 是否恰为 cff4f5a5 无任何证据。
2. **不等的后果 = B-2 翻版**【推断·高，真机实证旁证】B-2 带 124B 字节但命中 `0ef205c1`（≠源图身份）→ 引用缩略图 → 肉眼模糊（`_r86c_q4_forensics_raw.md:50-56`）。X 若字节 md5 落非 cff4f5a5，大概率重演此结果。
3. **拼径本身可能失败**【真机实证·中，R76】P3AdmitGate 64KB+magic+分桶严格准入（`ForceForward.kt:121-131/2545`），R76 真机曾出现 `origin-by-md5 miss`；拼不出则 X 直接无 fullpath，退回 B-1 未命中态。

### 置信度与边界
- "X 的 md5=cff4f5a5 前提" =【UNKNOWN 未闭合】，本轮拒绝把它写成已证。
- "若不等→命中另图/不命中→模糊" =【推断·高】（B-2 真机反证）。
- 若实现 lane 坚持验证 X：真机判据 = 落码后看 `x0cb md5` 是否 = cff4f5a5 且肉眼清晰；若落 `0ef205c1` 或其他 → X 失败，立即改 Y（不与内部计数器纠缠）。

`[DONE Q4]`

---

## 最终落码规格（Y 方案 · 唯一 · 可机械实现）

> 改动点唯一：`wxgroup/app/src/main/java/com/lx/wxgroup/probe/B6CheckExistProbe.kt` 的 `arm()` 构造段（:230-242）。
> 本代理不落码，规格交 Flash lane 机械实现。字段类型已由前一轮 dex 核对（`field_fullpath:L String` / `field_enable_hitcheck:Z` / `field_fake_bigfile_signature:L String`），现有 `put` 三重载已覆盖，无需新工具。

| 字段 | 值 | 来源 / 操作 | 是否改动 |
|---|---|---|---|
| `field_fullpath` | **非空小占位文件路径**（目标 ≈36B~124B，stock 同款即可；**绝不用 P3AdmitGate 拼的原图大文件路径**） | 优先级①：复用源图/消息 ImgInfo2 记录里已存在的占位路径（R79 `:43-46` 的 `r0.e=882d...jpg` 类占位）；优先级②：若无，在 `cacheDir` 构造一个固定小占位文件（体积几十~百字节即可，**无需 JPEG magic/64KB 校验**）并取其路径 | **补（核心）** |
| `field_enable_hitcheck` | `true` | 字面值，A 态实测 enableHit=true | **补** |
| `field_fake_bigfile_signature` | `""`（空串） | 对齐 A 态 fakeSigEmpty=true | **补** |
| `field_fileId` | **保留原图 fileId `305f…`（len194）**（B-1 当前已带，原样保留） | 现有 `fileId`（从消息 xml 解析）——Y 的决定量，务必保留、不可清空 | **保留（关键，勿动）** |
| `field_filemd5` | 保持现状（`xmlMd5`，如 `ca47f83e`） | 现有 :222-223/:236；`ok0/x0.u` 不读它，不影响 | 不动 |
| `field_aesKey` | 保持现状 | 现有 :220/:235 | 不动 |
| `field_onlycheckexist` | `false`（保持） | 现有 :241；真机恒 false | 不动 |
| 其它字段（mediaId/talker/fileType/priority/svr_signature/trysafecdn） | 保持现状 | 现有构造段 | 不动 |

### 落码要点（防踩坑）
- **fullpath 只要求非空字节入口**：stock 36B `.jpg_`（非合法 jpg 后缀）都能清晰 ⇒ 占位文件不需要合法图片内容，不要套 P3AdmitGate 的 magic/64KB/分桶校验（那是真上传原图字节路线才需要）。
- **fileId 是命门**：Y 成败取决于 `305f…` 是否随 task 进 `C2CUploadRequest.forwardFileid`（`ok0/x0.u 0x001d`）。B-1 已带 305f，保持即可；若误清空则重演 B-2 无 fileId→模糊。
- **不追的假目标**：不补 `field_filemd5` 为原图 md5（x0.u 不读）、不追 UploadHitCacheType/hit 计数器（前一轮已证非决定量）。

### 最小真机验证步（判据对肉眼）
1. 双探针 SCD + B6X 全开，广播 `op=b6step sub=on`（本轮已验证 11 hook 全 OK）。
2. 跑模块「转发（模块）」→ 读自建 task（`SCD2 c2c in`）：`fullpathLen>0`（小占位非 0）`enableHit=true` `fakeSigEmpty=true` `fidLen=194 fidPre=305f`。
3. 看 `x0cb`：`hit≠0 exist=true md5=cff4f5a5`（原图身份，**非** `0ef205c1`）。此步为机制旁证。
4. **对端肉眼清晰/模糊为终审**：清晰 → go 坐实；仍模糊且 md5=`0ef205c1` → 优先查 fileId 是否真进 forwardFileid（Q2 命门），修正后重测；仍失败 → 如实记录 no-go，不靠内部计数器自说自话。

---

## 三件套

① **冲突是否收口（X/Y 唯一选型）？**
**已收口，唯一选 Y（小占位 fullpath + 保留原图 fileId(305f) + enableHit=true + fakeSig=""），不选 X。** 依据：两轮 stock 清晰态（R82 36B `.jpg_` + 本轮 A 124B `.jpg_hevc`）都是「小占位 + 原图 fileId」配方【真机实证·高】；Q2 证明引用哪张图由 fileId 主导、占位字节只管启动 hitCheck，X 押注的「R83 字节 md5=cff4f5a5」前提【UNKNOWN 未闭合】且 B-2 已演示字节路线命中缩略图→模糊。R82 `:262` 路径①与 A 段真机双证 Y，X 无真机支撑。

② **落码 go/no-go（一句话）**：
**go（Y 方案）——补「小占位 fullpath + 保留 305f fileId + enableHit=true + fakeSig=""」复刻了两轮 stock 清晰三元组、机制对症、代价最低，值得落码；但结果级仍需对端肉眼清晰翻转才算 go 坐实，不以 hit/exist/UploadHitCacheType 计数器替代。**

③ **最小真机验证步（判据对肉眼）**：
**双探针全开跑模块转发 → 读自建 task（fullpathLen>0 小占位 / enableHit=true / fidLen=194 fidPre=305f）→ 看 `x0cb md5=cff4f5a5`（机制旁证，非 0ef205c1）→ 对端肉眼清晰为终审**；清晰即 go 坐实，仍模糊且 md5=0ef205c1 则先查 fileId 是否真进 `forwardFileid`（Q2 命门）再测，仍失败如实记 no-go。

---

[DONE ALL]

> 回传摘要：产物 `docs/_r86c_q4_fullpath_verdict.md`。Q1 不矛盾、A 段 124B 是占位非原图字节源、两轮形态不同不统一（36B `.jpg_` vs 124B `.jpg_hevc`，均小占位）。Q2 fullpath=启动门槛、fileId=引用决定量、enableHit=开关，请求 md5 非原图字节内容函数，native 合成【UNKNOWN】。Q3 唯一选 Y 不选 X。Q4 若 X md5≠cff4f5a5【UNKNOWN】→命中另图/不命中→模糊（B-2 翻版）。Q5 落码规格见表，核心=小占位 fullpath + 保留 305f fileId。三件套：冲突已收口 / go（Y）/ 肉眼终审最小步。

---

## 主会话独立复核附录（2026-11-24 01:4x，事实核验）

按硬规则独立核验，发现**一处量纲错误 + 一个可绕过的更稳路径**：

1. **❌ Q1「A 段 124B 占位」的量纲错误**：子代理称「A 段 `fullpathLen=124` 是占位文件体积≪64KB」。但查打点源码 `StockCdnProbe.kt:437`：`val fullpathLen = path?.length ?: -1`——这是**路径字符串长度（124 个字符），不是文件字节数**。`fidLen=194`/`md5Len=32` 同理都是字符串长度。⇒ **本轮日志根本没有观测 fullpath 指向文件的字节数**，「A 段 fullpath 是 36B 还是 124B 还是原图大文件」在本轮是【未观测变量】，子代理 Q1 据此下的「体积≪64KB 故为占位」不成立。R82 的「36B 占位 vs 15906B 缩略图」是另一轮、另一打点下的**文件大小**观测，不能与本轮路径长度混用。
2. **✅ 选型 Y 的方向仍成立，但论证要换支柱**：Y 不选 X 的核心理由不依赖「A 是占位」，而依赖两条硬事实：①A/B-1 入口 fileId 同为 `305f…`、B-2 入口无 fileId→命中另图 0ef205c1 模糊 ⇒ **fileId 是「引用哪张图」的决定量**（真机实证，与 Q2 一致）；②X 押注 R83 字节 md5=cff4f5a5【UNKNOWN】且 B-2 已演示字节路线命中另图。这两条不含量纲错误，Y=「保留真实 fileId + 给一个能触发 hitCheck 的非空 fullpath」仍是最稳配方。
3. **⭐ 更稳的落码路径（绕过「占位字节数未观测」）**：与其纠结 fullpath 该是 36B 还是真实原图，**最保险是直接复用 A 段实测清晰的那条 fullpath 来源**——即 stock 原生转发时 `m1.e` task 里 `field_fullpath` 的路径生成方式（`j2.O` 里 `z3(r0.e)`，R79 `:36/37`），让自建 task 的 fullpath 与 A 段逐字同源。若无法复用，则采用 Y 的「任意非空小占位文件」并**必须真机看 out filemd5 是否=cff4f5a5 + 肉眼**，不预设字节数。
4. **结论**：采纳 Y 选型，但 Q1 论证撤回「124B=占位体积」表述，改以 fileId 决定量 + X 的 md5 UNKNOWN 风险为支柱；落码时 fullpath 取「能复刻 A 段同源路径」优先、任意小占位次之，判据一律肉眼终审。本附录只记事实核验，不改 Q2~Q5 正文选型。

