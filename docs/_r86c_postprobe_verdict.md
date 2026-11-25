# R86c Q4b 取证后修复路线裁决（三只读探针真机结果之后）

> 任务性质 = 根因推理 + 选型裁决 + 落码规格；**本代理不落码、不跑 gradle、不碰真机 adb**。唯一允许写的文件 = 本文档。
> 三档标注：【真机实证】/【dex 实证】/【推断·高|中|低】/【UNKNOWN 未闭合】。【推断】不写成【已证】；【UNKNOWN】如实标；判据对肉眼/文件字节，不对内部计数器。
> 落盘策略（防父 turn 打断）：先建骨架（Q1~Q4 占位）→ 每答完一问立即写盘留 `[DONE Qn]` → 三件套 → 真 `[DONE ALL]`。
> 原始日志 `_r86c_probe_forensics_raw.txt`（39 行）在库内未找到文件，但三探针真机事实已由 `docs/_r86c_probe_forensics.md` 逐条转录并标注来源，本裁决以该转录为准，不自行补造原始行。

## 输入清单（按序读毕，全部只读）
1. `docs/_r86c_probe_forensics.md` —— 本轮三探针真机事实 + 表观矛盾 + 交给 Pro 的 3 个裁决问题（核心输入）。
2. `docs/_r86c_q4b_refmd5_verdict.md` —— NO-GO 裁决：Q1/Q2 两 md5 语义、放弃内容 MD5 准入、Q2 选型=候选⑤复用 b1.z3 拼路径；Q3 三 UNKNOWN G1/G2/G3；Q4 互斥判据表。
3. `docs/_r86c_probe_landing_spec.md` —— 三探针落码规格（z3 四参模板、判据常量来源、P-FS 扫描实现）。
4. `docs/_r86c_q4b_forensics_raw.md`（A 45522/cff4f5a5 清晰、B 9554/0ef205c1 模糊）、`docs/_r86c_q4b_final_verdict.md`（决定量=fullpath 字节、x0.u 零 filemd5、fileId 非决定量）。

## 不得推翻的已核事实（逐条标源）
- A 原生转发清晰 = 45522B / `cff4f5a5`；B 模块转发模糊 = 9554B / `0ef205c1`【真机实证·高，`_r86c_q4b_forensics_raw.md:19-20`】。
- 决定量 = `field_fullpath` 指向字节内容 MD5 是否为 `cff4f5a5`；`x0.u` 不读 `field_filemd5`（grep 零命中）；fileId 非决定量（R81/R82 逐字相同仍一清晰一模糊）【真机实证·高 + dex 实证·高，`_r86c_q4b_final_verdict.md` Q1/Q2】。
- `a3` 按 `msgSvrId=?`(w7)、`o2` 按 `id=?`(B7) 两个不同查询键【dex 实证·高，`_r86c_q4b_refmd5_verdict.md` Q3.2】。
- 本轮 G1 = go：`R86C FS verdict scanned=22 a45522=1 cff4=1 hit=1 ambiguous=false`，唯一命中 `e64b3b2.jpg_hevc` 45522B/`cff4f5a5`【真机实证·高，forensics.md:12-16】。
- 本轮 G2 = no-go：四路 z3（false 与 retry true 全同）拼出 e→335644B/`86ff18ad`（源图 jpg）、g→15906B/`f6ab3ac5`（缩略图）、f/j→NA；`hitFields=none`【真机实证·高，forensics.md:18-35】。
- 本轮 G3 = 部分闭合：`a3id=75915761 o2id=7688662 sameObj=false`，但 `a3Fields`/`o2Fields` 逐字全同（e=216b1f7c…jpg / f=NA / g=THUMBNAIL_DIRPATH://th_216b1f7c… / j=NA / l=NA）【真机实证·高，forensics.md:37-40】。
- 三文件名前缀各异：hevc=`e64b3b2`、r0.e=`216b1f7c`、r0.g=`th_216b1f7c`；z3 输入字段里没有指向 hevc 原图的候选【真机实证·高，forensics.md:44】。
- z3 第 4 参 boolean：false/true 两轮拼出完全相同的三条路径 ⇒ 该 UNKNOWN 在本次样本不成立【真机实证·高，forensics.md:46】。

---

## Q1 NO-GO 维持 / 路线改判裁决

**裁决（一句话）**：**结果级 NO-GO 维持（结果级 go 只由肉眼 A→B→A 翻转签发，本次未采肉眼）；但机制/准入级路线必须改判——Q2 候选⑤「复用 b1.z3 拼原图 hevc 路径」已被本次真机证伪为「在 inject 可用的 a3/o2 r0 字段下拼不出 cff4f5a5」，唯一改走候选②「P-FS 文件系统直接定位唯一 cff4f5a5/45522B 文件、把其绝对路径写进 `field_fullpath`（写点仍 m1.e before，复用 enabled+pending 闸门）」。** 【真机实证·高（G1/G2/G3）+ 推断·高（选型）】

### 1.1 核心矛盾定性：不是「字节不在」，是「手段取不到已在的字节」

- G1【真机实证·高】坐实：模块转发同一时刻，MicroMsg 数据根 `image2/` 两级分桶下**存在且仅有一份**内容 MD5=`cff4f5a5`、45522B 的 `.jpg_hevc` 文件（`hit=1 ambiguous=false`）。决定量（fullpath 字节=cff4f5a5）所需的字节**就在本地、唯一、可定位**。
- G2【真机实证·高】坐实：`z3(r0.e/f/g/j,"","",bool)` 四路（false 与 retry true 全同）拼出的三条路径分别是 335644B 源图 jpg（`86ff18ad`）、15906B 缩略图（`f6ab3ac5`）、无路径，**没有一路指向 cff4f5a5/45522B hevc**，`hitFields=none`。
- ⇒ 矛盾不是 R80 式的「拿不到字节」，而是**「字节在、Q2 选型的拼路径手段取不到它」**。故不是回到「补原图落盘」的字节不可获得路线，而是换定位手段。

### 1.2 候选⑤是否已被证伪？严格分层，不过度定性

**对候选⑤的裁决：本次真机把它的「go 前置」（Q2 自己立的 `G2 = z3 某路命中 cff4f5a5`）实测为假 ⇒ 按 Q2 自立的判据，⑤ 的落码前置不成立，路线失据。** 但要严格区分两层，不写成「z3 手段整体不可行」：

- 【真机实证·高·已证伪的部分】**「从本次 inject 可用的 o2 r0（及 a3 r0）字段出发，z3 拼不出 cff4f5a5 hevc 原图路径」**。理由链：z3 是**路径构造器**而非发现器——它只能把输入的相对标识/URI 拼成 image2 路径，无法凭空造出输入里没有的文件名。本次 r0 五字段（e=216b1f7c…jpg / f=NA / g=th_216b1f7c… / j=NA / l=NA）里**没有任何一个携带 hevc 原图的文件名身份**（hevc 前缀 `e64b3b2`，与 `216b1f7c`、`th_216b1f7c` 互不相同，forensics.md:44），故四路必然落到源图 jpg/缩略图/NA。G3 又证 a3Fields 与 o2Fields **逐字全同**，所以「换用 a3 的 r0」也改变不了输入集合。
- 【推断·高】**「换字段/换 r0 也救不回⑤」**：①「改字段」无候选可用——e/f/g/j/l 五个字段的语义已由 `r0.b` 列映射定死（相对标识/URI/摘要，无 hevc 文件名），不存在第六个未被 z3 尝试的 hevc 字段；②「改 r0」在 a3/o2 两个候选中字段全同（G3），j2.O 现场 r0 虽未直接打印，但既然 a3/o2 两条不同查询键都返回同一记录的同字段值，j2.O 的 r0 携带 hevc 文件名的可能性极低（【推断·中】，未直接测，不写成已证）。
- 【UNKNOWN 未闭合（不影响裁决）】**「z3 作为手段整体是否永不可能命中」**不作绝对断言——理论上若某个未测对象/未测字段携带 hevc 文件名身份，z3 仍可拼出。但本次是「三条真机路径全错 + 五个字段无候选 + 两个 r0 字段全同」的三重否证，继续押注⑤需要新增 dex 取证且无正面证据支撑，**不构成可落码依据**。
- ⇒ 精确定性：**「本次 r0 字段下⑤拼不出」是【真机实证】；「⑤整体不可行」是【推断·高】而非【已证】。**按 Q2 自立的 G2 判据，⑤的 go 前置已假 ⇒ ⑤失据。

### 1.3 候选逐条评估（唯一推荐，禁「都可以」）

| 候选 | 本次真机/dex 事实 | 裁决 |
|---|---|---|
| ① 维持 z3 拼路径（改字段 / 改 r0） | G2 四路全不命中【真机实证】；hevc 文件名前缀 `e64b3b2` 不在任何 r0 字段【真机实证】；G3 证 a3/o2 字段逐字全同，换 r0 无解【真机实证】。改字段无第六候选，改 r0 两者同字段。 | **否决**：输入集合里没有 hevc 身份，z3 无法凭空构造；继续押注需新增无正面证据的 dex 取证。 |
| ② 文件系统直接定位（P-FS 扫描升级为生产写路径） | G1【真机实证】已证唯一 cff4f5a5/45522B 文件存在、可现场算内容 MD5 定位、`hit==1 ambiguous==false`；P-FS 读 `task.field_fullpath` 取 MicroMsg 根（`_r86c_probe_landing_spec.md` Q1/Q4），**不需要 r0/a3/o2/z3**，从根上规避 G2/G3 的自洽缺口；写点仍是已挂的 m1.e before，非新 hook 点（`_r86c_q4b_refmd5_verdict.md` Q4 :234）。 | **唯一推荐 ✅**：最短路径从「字节在」到「写对 fullpath」，直接产出决定量所需字节的绝对路径。 |
| ③ 其它（从 r0 其它字段 / DB 找 hevc 文件名） | `r0.b` 列映射 e/g/u/m/f/j/l 全是相对标识/URI/摘要、无绝对路径【dex 实证，refmd5 Q3.2(1)】；本轮五字段无 hevc 名【真机实证】；无 dex 证据表明存在 hevc 文件名字段或 DB 列。 | **否决**：纯猜测、无可达性支撑；即便找到也回到「文件名定位」，而本轮 hevc 文件在不同转存下文件名会变（`e64b3b2` vs A 态路径尾 `a40596ff`，【推断·中】见 Q4），不稳定。 |

### 1.4 为什么②与已核事实及既有闸门完全兼容

- **决定量对齐**：②写入的 `field_fullpath` 就是 cff4f5a5 文件的绝对路径，`x0.u setFilePath` 从该字节重算 md5 → 回包 filemd5=cff4f5a5 → 对端拉原图（与 `_r86c_q4b_final_verdict.md` 统一机制一致）。
- **写点不新增**：m1.e before 与 fileId/aesKey 同 hook、同次 pending 消费，早于 `x0.u setFilePath`（refmd5 Q4 :234 已定），消解主会话复核附录「x0.u 是新 hook 点」的阻断。
- **G2/G3 缺口自动消解**：②完全不消费 r0 字段与 z3 ⇒ Q3.2 的「inject 复用哪条 r0 / z3 哪路是 hevc」自洽缺口对②不成立（该缺口只绑死⑤）。
- **闸门/隔离不变**：`enabled` 默认 false、一次性 pending、TTL 2000ms、`clear()` 失败回退、普通发图 `forwardCtx==null` 不 arm —— 全部沿用（`_r86c_probe_landing_spec.md` Q3.3 / refmd5 Q4 改动6）。
- 落码规格见 Q2。

### 1.5 置信度
- G1/G2/G3 真机值 =【真机实证·高】；「⑤在本次 r0 字段下被证伪」=【真机实证·高】；「换字段/换 r0 救不回⑤」=【推断·高】；「z3 整体永不可行」=【UNKNOWN 未闭合，不作断言】；「②为唯一推荐且与既有闸门兼容」=【推断·高（选型）+ 真机实证·高（G1 可定位）】。
- **结果级 go 仍挂起**：本次未采肉眼，②是否真让肉眼翻清晰须 Q3 的 A→B→A 翻转坐实，不预判。

`[DONE Q1]`

## Q2 改路线后的逐行落码规格

> 规格对象 = Flash lane；本代理只出规格不改码。起点 = 现有 `ForwardCdnFieldHook.kt` / `StockCdnProbe.kt` 探针码（`_r86c_probe_landing_spec.md` Q4 已落的三只读探针实现）。核心变化：**把 P-FS 从只读探针升级为生产路径解析器**，在 `inject()` 用它产出 `field_fullpath` 的绝对路径；②路线不消费 r0/z3，故 refmd5 Q4/landing spec 中「j2.O 现场 r0 / z3 四路 / resolveOriginPathByZ3」那套规格对②整体作废。

### 2.0 先回答特别提问：P-FS 是否推翻「放弃内容 MD5 准入」？——**部分推翻，分两层**

- 【真机实证】**推翻其"技术前提"**：refmd5 Q1/Q2 放弃内容 MD5 准入的理由是「模块转发时拿不到可作为参照的 `cff4f5a5`」——具体指 xml `$md5`=ca47f83e / 文件名 md5 / fileId body 三个外部值**没有一个等于** cff4f5a5，故 `md5PrefixOfFile(f)==refPre(外部)` 的准入**不可满足**。本轮 P-FS 证明：**不需要外部参照**——用 `md5PrefixOfFile()`（`StockCdnProbe.kt:262-273` 现成口径）**现场对候选文件算内容 MD5** 即可，且 `cff4f5a5` 本身是已知的决定量目标（来自 A 态基线，`_r86c_q4b_forensics_raw.md:19`）。「内容 MD5 不可现场计算」这一隐含技术前提**被 P-FS 真机推翻**。
- 【推断·高】**不推翻其"外部参照语义"结论**：refmd5 Q1/Q2 的另一个结论仍然成立——**没有任何消息字段携带 cff4f5a5 可作逐图参照**（三个外部 md5 都不是它）。P-FS 能定位，靠的是「体积≈45522 + 命中数==1 + 已知目标 cff4f5a5」的组合，而不是找到了一个外部参照值。所以准确说法是：**放弃的是「拿外部 refMd5 做匹配准入」，不是放弃「现场算内容 MD5 做字节身份校验」**。②路线正是把现场内容 MD5 从「不可得参照」重新定位为「可算的决定量校验」。
- 【UNKNOWN 未闭合】**泛化边界**：`cff4f5a5` 是**本图 A 态原图字节的内容 MD5**，不是可逐图推广的常量；换一张图其内容 MD5 必不同，而新图的「正确原图 MD5」无任何消息字段可提供（同上外部参照结论）。⇒ ②路线的 `md5pre==cff4f5a5` 准入对**本验证图有效**，对换图泛化**不自动成立**（见 2.5）。**结论性一句**：P-FS 把「内容 MD5 校验」从死路救活为②路线的核心准入手段，但救活的只是「现场算 + 已知目标」这一形态，不是「外部参照匹配」。

### 2.1 改动面总览

| # | 文件 | 改动 | 锚点 |
|---|---|---|---|
| 1 | `core/ForwardCdnFieldHook.kt` | ① 新增生产解析器 `resolveOriginPathByFS(task)`；② `inject()` 在拿 task 后调它、命中才写 `field_fullpath`；③ 新增 `originUnavailable()` 日志分支 | `inject()` :108-133；现 `resolveOriginFullpath`(:219-252) 删除/停用 |
| 2 | `probe/StockCdnProbe.kt` | 复用 `md5PrefixOfFile`(:262-273) 与 P-FS 扫描范式（把 :209-242 的只读扫描抽成供①调用的无副作用工具，或复制同口径）；**只读探针 `SCD-r86cfs` 保留作回归旁证，默认关不变** | :262-273 / :499 后扫描块 |
| 3 | `entry/EntryReceiver.kt` | **不改**（r86c op 仅控探针；生产闸门仍由 `FileSender.send` 的 `forwardCtx` 驱动） | — |

**明确不动**：`HookEntry.kt`、`FileSender.kt` 调用签名、`Pending` 结构（②不需要 r0/z3/参照字段）、`enabled`+pending+TTL 闸门、fileId/aesKey 注入、`q1.j` 三级覆盖、`x0.u`/`j2.O`、filemd5/onlycheckexist/enableHit —— 与 refmd5 Q4「不动」清单一致。

### 2.2 准入（P-FS 扫描升级为生产解析）——逐行规格

`resolveOriginPathByFS(task): String?` 全程 `runCatching`，只返回「唯一命中候选的绝对路径」或 `null`：

```
1) root 来源：XposedHelpers.getObjectField(task,"field_fullpath") as? String
   → Regex("/MicroMsg/[0-9a-fA-F]{32}/").find(...) 取数据根（不硬编码 hash；范式 landing spec :202-207）
   → root = fullpath.substring(0, hit.range.last + 1)
   取不到 fullpath/root ⇒ log "origin=unavailable reason=no_root" → return null（安全降级，不硬凑）
2) 扫描范围：java.io.File(root+"image2").listFiles() 两级分桶，取 name.endsWith(".jpg_hevc") 的文件
   （与只读探针 :210-216 同范围；每个候选独立 runCatching）
3) 每个候选只读量：len = f.length()；md5pre = md5PrefixOfFile(f)（现成口径 :262-273，
   File.length + 全文件 MD5 前 4B hex；跳过 >32MB 防卡）
4) 命中判据（三项全真才算一个命中）：
      f.isFile
   && len == 45522L                    // 本图精确值；见 2.5 泛化边界
   && md5pre == "cff4f5a5"             // 本图已知决定量目标；现场算，非外部参照
5) 命中数必须 == 1；
      ==0 ⇒ log "origin=unavailable reason=fs-no-hit" → return null
      >1  ⇒ log "origin=unavailable reason=fs-ambiguous hit=N" → return null（歧义不硬选）
6) 命中 ⇒ return 唯一候选 f.absolutePath
7) 日志只打 tail=absolutePath.takeLast(16) / len / md5pre / hit，不打完整路径或 hash
```

- **mtime 是否用于排除多候选：不加为准入、不作自动裁决**【推断·中 + UNKNOWN】。本轮 `hit==1 ambiguous=false` 根本无多候选；而 mtime 与转发时刻的关系真机未取证（`_r86c_q4b_refmd5_verdict.md` Q3.1 候选④已标该 UNKNOWN）。规格纪律：`hit>1` 一律按「歧义 → no-go/不写」，**不按 mtime 自动挑一个**（那等于用未证判据选字节，违反「判据对文件字节」）。若后续真机补证 mtime 与当次转发强相关，可作为**额外排除项另案加**，本次不引入。

### 2.3 写点时序（inject m1.e before，非 arm）

```
inject()（m1.e before，沿用现码 :108-133 顺序）：
  :110-116 TTL 校验 → :118-119 一次性清 pending → :120 取 task
  【新增，在清 pending/取 task 之后】
  val origin = resolveOriginPathByFS(task)
  if (origin == null) {
      LogX.w("$TAG injected origin=unavailable reason=<上一步具体原因>")
      return                      // 不写 task 任何字段，保持现状模糊，绝不硬凑
  }
  setObjectField(task,"field_fileId",p.fileId)     // 低风险字段对齐，非决定量
  setObjectField(task,"field_aesKey",p.aesKey)
  setObjectField(task,"field_fullpath",origin)     // 决定量唯一写点
  LogX.i("$TAG injected origin=true originTail=${origin.takeLast(16)} len=45522 md5pre=cff4f5a5")
```

- **为何在 inject 而非 arm**：`resolveOriginPathByFS` 需要以**本次 task 的 `field_fullpath`** 反推 MicroMsg 数据根，该值只有 m1.e before 拿到 `param.args[0]` 后才有；`arm()` 发生在 `FileSender.send` 起点、task 尚未构造（`_r86c_probe_landing_spec.md` Q1 公共时点关系），无法从 task 取根。故 arm 保持「读 map + 装 pending + 开闸」的职责收缩态（refmd5 Q4 改动2），文件系统解析只在 inject 消费时跑一次。
- **写点可达性**：m1.e before 早于 `x0.u setFilePath`（refmd5 Q4 :234 自算核准 `0x3a98ee/0x3a98f2`），写入的 fullpath 会被 x0.u 消费；**不新增 x0.u hook**，消解主会话复核附录的阻断。
- 不写 `filemd5`（x0.u 零命中）、不动 `enableHit`/`onlycheckexist`/`fakeSig`/`q1.j`/`l1.a()`。

### 2.4 闸门 / TTL / 失败回退（全部沿用，零新增状态）

- `enabled` 默认 false（`ForwardCdnFieldHook.kt:47`）；非本次转发不 arm；普通发图 `forwardCtx==null ⇒ 不进 arm ⇒ :85 if(!enabled)return`，逐字节不变（`_r86c_probe_landing_spec.md` Q3.3）。
- 一次性 pending：inject 注入前即清（:118-119）；TTL 2000ms（:36/:110-116）；多目标每目标独立 arm、解析只在 pending 存在时各跑一次。
- 失败回退：`no_root` / `fs-no-hit` / `fs-ambiguous` / `runCatching` 异常 → 均 `origin=unavailable` 且**不写任何字段**，保持模糊现状；`FileSender.kt:260 clear()` 清 pending 防串味。
- r86c 探针开关与生产闸门**完全隔离**：`SCD-r86cfs` 只读探针默认关、不写 task、不参与 pending/TTL（landing spec Q3.3）；生产解析器只读文件、唯一写 fullpath。二者互不串扰。

### 2.5 只读红线如何不破坏（写 fullpath 是唯一允许写）

- `resolveOriginPathByFS` 内部只用 `java.io.File.exists/isFile/length/readBytes` + `java.security.MessageDigest`（纯 JDK 只读 API），**不写 task、不调宿主业务方法、不发消息、不建 db 记录**——与只读探针同口径。
- 整个修复链路**唯一写入** = `setObjectField(task,"field_fullpath",origin)`（决定量写点）。其余字段（fileId/aesKey）是既有低风险对齐，不算新增写入面；禁止 `param.args[..]=`、`setIntField`/`setBooleanField`、调 `j6/R5/B8/send`。
- 保留只读探针 `SCD-r86cfs` 作回归旁证时，它仍**只读不改 host**（landing spec 4.5 硬锁），与生产解析器代码分离。

### 2.6 换图泛化风险（诚实边界）

| 常量 | 本图事实 | 是否可推广 | 规格处置 |
|---|---|---|---|
| `45522L` | 本图 A 态原图字节体积【真机实证】 | 【UNKNOWN】换图体积几乎必变（不同图不同字节数），本轮只测一张图 ⇒ 不能当作通用常量 | 验证期用**精确值** 45522L（放宽到范围会混入非原图文件，削弱字节身份判据）；标注为「本图热修常量」。若将来要泛化，体积改范围须另做真机取证且仅作 guard，不能单独作准入。 |
| `cff4f5a5` | 本图 A 态原图字节内容 MD5【真机实证】，来源 = A 态基线已知值，**不是任何消息字段** | 【UNKNOWN】换图内容 MD5 必不同，新图目标 MD5 无字段来源（Q1/Q2 外部参照结论）⇒ 泛化缺目标值 | 验证期作为已知决定量目标硬编码、现场算文件 MD5 匹配；日志始终记录选中文件的 len/md5pre，便于换图失效时诊断。**不宣称对其它图自动生效**。 |
| hevc 文件名 | 本轮命中 `e64b3b2.jpg_hevc`，A 态路径尾 `a40596ff.jpg_hevc`【真机实证，推断·中为「同一内容不同转存名」】 | 【推断·中】文件名不随内容稳定 ⇒ 文件名定位不可靠 | 这正是否决①/③、选②（按内容定位）的依据；②对文件名变化不敏感。 |
| mtime | 未取证与转发时刻关系【UNKNOWN】 | 不作准入/不作裁决 | hit>1 一律 no-go，不按 mtime 自动选（2.2）。 |

> **泛化结论**：②路线是本验证图的**字节身份级热修**（准入精确、唯一命中），不是通用「任意图自动定位原图」方案。通用化卡在「换图的目标 MD5/体积无从获得」，这是【UNKNOWN 未闭合】，不得写成已解决。

`[DONE Q2]`

## Q3 判据与验证

### 3.1 唯一 go 坐实：仍是肉眼 A→B→A 翻转（不变）

**结论【判据设计；机制级【真机实证】，结果级须真机肉眼闭合】**：唯一 go 坐实仍是 **肉眼 A(原生清晰) → B(模块模糊) → 开闸 B(应翻清晰) → A(仍清晰)** 翻转成立。本次三探针是只读取证、明确未采肉眼（forensics.md:7-8），**字节证据（G1 go）只证明「cff4f5a5 字节在本地且唯一」，不证明「把它写进 fullpath 后对端肉眼会清晰」**——后者是待验证的因果，不能由 G1 的「字节在」越级推出。`origin=true` / 写入 fullpath 的 `fmd5pre=cff4f5a5` / 出口 `filemd5=cff4f5a5` 只是机制旁证（证明字节写对），非终审。

### 3.2 是否需要补一轮基线肉眼？——**需要**

- 【真机实证】基线肉眼事实已有先例：`_r86c_q4b_forensics_raw.md:11-13` 用户 02:14 确认同图 A 清晰 / B 模糊。但那是另一时点、另一 APK 状态下采集；本轮取证（forensics.md）明确「本次转发为只读取证，未要求用户肉眼判清晰/模糊」。
- 【推断·高】故落码后验收必须**补一轮基线肉眼**：在同一验证会话、同一张图上先不开闸跑 A(原生→清晰，记 `fbytes≈45522 fmd5pre=cff4f5a5`) 与 B(模块→模糊，记 `fbytes≈9554 fmd5pre=0ef205c1`) 站住基线，再开闸跑模块转发看 B 是否翻清晰、A 是否仍清晰。理由：① 消除「本轮取证图与 A 态基线图是否同一张」的时点歧义；② 结果级 go 只认肉眼翻转，缺本轮肉眼就缺终审；③ 成本低（一组两发 + 肉眼确认），收益是闭合唯一 go 判据。
- ⇒ **补一轮基线肉眼确认 = 必须**，不以「字节证据已足」替代。

### 3.3 `hit` / `UploadHitCacheType` 仍绝不作判据

- 【真机实证·高】两计数器映射两轮完全翻转：R82 旧映射 3=清晰/1=模糊，本轮 A=1 清晰/B=3 模糊（`_r86c_q4b_final_verdict.md` Q1 附注 / Q2④）；`UploadHitCacheType` 来自服务端 `hitCache`、`hit` 是回调计数器，与肉眼无固定映射。
- 纪律：只记录、不裁决；不得用其「翻转」当「机制通」或「清晰」证据，也不得因其异常改判肉眼结果。

### 3.4 ②路线（P-FS 写 fullpath）go/no-go 互斥判据表

> 判据一律对肉眼清晰/模糊（最高）+ 写入 fullpath 的文件字节/MD5（辅助），**不对 hit/UploadHitCacheType 等内部计数器**。

| 观测（肉眼最高，字节/MD5 辅助） | 机制旁证 | 裁决 | 下一步 |
|---|---|---|---|
| **补基线后肉眼清晰 + A→B→A 翻转成立**（清晰→模糊→开闸清晰→清晰） | `injected origin=true`；写入 fullpath 的 `len=45522 md5pre=cff4f5a5`；出口 `filemd5=cff4f5a5` | **go 坐实，修复成立** | 固化 P-FS 生产解析写点 + 闸门/TTL；SCD 探针回归只打前缀，r86c op 发 off |
| 肉眼清晰但旁证矛盾（origin 日志缺失/计数器异常，图却清晰） | 以肉眼为准 | **go（终审压倒旁证）**，记异常待查 | 补一轮取数确认日志信号，不改判 |
| **字节在但写错路径**：仍模糊，写入 fullpath `md5pre=86ff18ad`(335644B 源图 jpg) 或 `f6ab3ac5`(15906B 缩略图) 或 `0ef205c1`(9554B 另图) | 解析器误选候选 / root 取错分桶 / 写点被 j2.O z3 再覆盖 | **接入失败（非方案证伪）** | 查 `R86C FS` 扫描行与 `injected originTail`、查写点是否在 x0.u setFilePath 前最后写入；修后重测，**不硬凑** |
| **字节不在**：`origin=unavailable reason=fs-no-hit`，P-FS `hit=0` | 模块转发同一时刻无 cff4f5a5/45522B 文件（与本次 G1 go 相反，属新情形） | **go 前置不满足 → no-go（字节不可获得）** | 补「原图字节下载/落盘」能力（另案）；禁用缩略图/占位硬凑 |
| **多候选歧义**：`origin=unavailable reason=fs-ambiguous hit=N(N>1)`，P-FS `ambiguous=true` | 同刻出现 >1 个 45522B/cff4f5a5 候选（本轮为 `hit=1`，属未发生的新情形） | **no-go（歧义，不自动选）** | 如实记录各候选 tail/len/md5pre/mtime；按 Q2.2 不由 mtime 自动挑，待另案补证排除规则 |
| 仍模糊，但写入 fullpath `md5pre=cff4f5a5` 且出口 `filemd5=0ef205c1` | 字节对了但回包认另图 | **机制未闭环（native hitCheck 未认原图）** | 同刻 stock 对照；核对 fileId 对齐(305f)是否进 forwardFileid；仍失败如实 no-go，不硬推 |
| G2/G3 类风险（z3/r0 缺口） | ②路线不消费 r0/z3 ⇒ 该类不适用 | **不适用（路线已改判）** | 仅在回退到①路线时才按旧表 G2/G3 处置 |
| **微信崩溃 / 发送失败 / 双发消息** | hook 抛异常 / `l1.a()=false` | **no-go，立即回退** | 恢复 HookEntry install 注释态；`FileSender.kt:260 clear()` 清 pending 防串味 |

### 3.5 验收最小步（配合 3.2 的补基线）

1. **前置只读确认**：不开闸，跑 P-FS 确认 `hit=1 ambiguous=false`（即本次 G1 已证的状态在本机复现）。
2. **补基线肉眼**：不开闸，同图 A 原生转发（肉眼清晰，记 45522/cff4f5a5）、B 模块转发（肉眼模糊，记 9554/0ef205c1）。
3. **开闸落码**：装含②规格 APK，`forwardCtx!=null` 触发 arm，inject 用 P-FS 写 fullpath。
4. **机制旁证（非结论）**：读 `injected origin=true` + 写入 fullpath 的 `len/md5pre=cff4f5a5` + 出口 `filemd5=cff4f5a5`。
5. **肉眼终审**：对端 B 翻清晰、A 仍清晰 ⇒ go 坐实；否则按 3.4 互斥表处置。计数器只记录不裁决。

`[DONE Q3]`

## Q4 UNKNOWN 诚实标注（不得写成已证）

> 本轮只闭合了 G1/G2/G3 与本图字节定位手段，以下仍未闭合；凡【推断】均不升级为【已证】。

| # | 未闭合项 | 本轮掌握的事实 | 标注 | 对本裁决的影响 |
|---|---|---|---|---|
| U1 | 源图 jpg（r0.e 拼出，`fd0eb3c46801.jpg` 335644B/`86ff18ad`）与 hevc 原图（`e64b3b2.jpg_hevc` 45522B/`cff4f5a5`）的精确字节关系 | 两者文件身份不同（前缀/体积/md5 均不同）；G2 证 r0.e 拼出的是源图 jpg 而非 hevc。对照 refmd5 Q1 已定「源图 vs 本地转码 hevc 字节」机制（ca47f83e vs cff4f5a5）。 | 【推断·中】：二者很可能是同图不同阶段（源 jpg 与本地转码 hevc），但本轮**未直接证明 `86ff18ad` 这个 jpg 就是 `cff4f5a5` hevc 的源**（无转码哈希链取证）；不写「已证」。 | 不影响②路线（按内容 MD5 定位，不关心与源图 jpg 的关系）；若误选 jpg 按 Q3.4「字节在但写错路径」处置。 |
| U2 | 换一张图体积是否还是 45522 | 本图 A 态 45522B【真机实证】；仅测一张图。 | 【UNKNOWN 未闭合】：换图体积几乎必变，本轮无第二张图对照；`45522L` 标为本图热修常量。 | ②路线验证期用精确值；泛化须另案取证（Q2.5）。 |
| U3 | 换一张图的「正确原图 MD5」从哪里来 | `cff4f5a5` 来自本图 A 态基线；Q1/Q2 已证无消息字段携带内容 MD5（xml `$md5`=ca47f83e、文件名 md5、fileId body 均非）。 | 【UNKNOWN 未闭合】：逐图目标 MD5 无字段来源；P-FS 现场能算任意文件 MD5，但「该拿哪个 MD5 当目标」对换图无解。 | ②路线泛化的核心卡点；不宣称通用。 |
| U4 | a3/o2 对象不同但字段逐字相同的深层原因 | G3：`a3id=75915761 o2id=7688662 sameObj=false`，`a3Fields`/`o2Fields` 全同；dex 证 a3 按 `msgSvrId=?`(w7)、o2 按 `id=?`(B7) 两条不同查询键。 | 【推断·高】：两条不同查询各自 `new` 出独立 r0 对象（`System.identityHashCode` 不同），但 where 命中同一条图片记录 ⇒ 字段逐字相同；【UNKNOWN】：j2.O 现场 r0 是否又是第三个对象、其字段是否与 a3/o2 全同（本轮未直接打印 j2.O 现场 r0 的身份/字段）。 | ②路线**不消费 r0**，故该 UNKNOWN 对②不成立（只绑死①/⑤）；若回退到 z3 路线则仍须闭合。 |
| U5 | mtime 与转发时刻的关系（能否用于多候选排除） | 本轮 `hit=1 ambiguous=false`，无多候选可测。 | 【UNKNOWN 未闭合】：refmd5 Q3.1 候选④已标此 UNKNOWN，本轮未补证。 | Q2.2 规格不按 mtime 自动选；hit>1 一律 no-go。 |
| U6 | hevc 文件名的稳定性（是否始终 `e64b3b2…`） | 本轮命中尾 `e64b3b2.jpg_hevc`，A 态路径尾 `a40596ff.jpg_hevc`（refmd5 Q1 :36）。 | 【推断·中】：同一内容可能在不同转存下FileName 不同；未直接证明两者字节同源（同 U1）。 | 支持「按内容定位优于按文件名定位」的选型，但不写成「文件名必不稳定」的已证结论。 |
| U7 | 结果级「写 cff4f5a5 字节后肉眼翻清晰」 | 本轮 G1 证字节在且唯一；A 态历史肉眼清晰（45522/cff4f5a5）；本轮未采肉眼。 | 【UNKNOWN 未闭合】：「字节在」≠「写入后对端清晰」，因果须落码后 A→B→A 肉眼翻转坐实。 | 结果级 go 挂起（Q3.1/3.2）；机制级选型不受影响。 |

### 已闭合项（如实注明，不再标 UNKNOWN）
- G1 字节可获得 =【真机实证·高·闭合】（hit=1 ambiguous=false）。
- G2 「本次 o2 r0 四路 z3 拼不出 cff4f5a5」=【真机实证·高·闭合】；「z3 整体永不可行」不作断言（Q1.2）。
- G3 「a3/o2 不同对象、同字段同记录」=【真机实证·高·部分闭合】（对象身份与字段已测；深层原因 U4 仍推断）。
- z3 第 4 参 boolean 是否影响路径 =【真机实证·高·闭合（本次样本）】：false/true 两轮拼出完全相同的三条路径 ⇒ 该 UNKNOWN 在本次样本不成立（forensics.md:46）；不再需要 landing spec 的「retry true」兜底作为有效区分手段（本轮已证两轮一致）。

`[DONE Q4]`

---

## 三件套

**① NO-GO 维持还是改判、唯一路线推荐一句话？**
**结果级 NO-GO 维持（结果级 go 只由肉眼 A→B→A 翻转签发，本次未采肉眼）；机制/准入级路线改判——Q2 候选⑤「复用 b1.z3 拼路径」已被本轮真机在「本次 o2/a3 r0 字段下」证伪（四路全不命中、hevc 文件名身份不在任何字段、a3/o2 字段逐字全同），失据；唯一改走候选②：把 P-FS 从只读探针升级为生产路径解析器，现场算内容 MD5 定位唯一 cff4f5a5/45522B 文件、把其绝对路径写进 `field_fullpath`（写点仍 m1.e before，复用 enabled+pending+TTL 闸门，仅此一处写入）。**【真机实证·高 + 推断·高】严格定性：「本次 r0 字段下⑤拼不出」=【真机实证】；「z3 整体不可行」不作断言。

**② 改路线的逐行落码规格要点（写点/准入/闸门/泛化）？**
- **写点**：`ForwardCdnFieldHook.inject()`（m1.e before）在「清 pending(:118-119) + 取 task(:120)」之后调 `resolveOriginPathByFS(task)`，命中才依次写 `field_fileId`/`field_aesKey`（低风险对齐）+ `field_fullpath`（决定量唯一写点）；早于 `x0.u setFilePath`，不新增 x0.u hook。不写 filemd5、不动 enableHit/only/fakeSig/q1.j。
- **准入**：由 `task.field_fullpath` 经 `/MicroMsg/[0-9a-fA-F]{32}/` 取数据根 → 扫 `image2/` 两级分桶 `*.jpg_hevc`；命中判据 = `isFile && len==45522L && md5PrefixOfFile==cff4f5a5`（现场算，非外部参照），且**命中数必须==1**；0 命中或 >1 歧义一律 `origin=unavailable` 不写字段、不硬凑；`runCatching` 全包；日志只打 tail/len/md5pre。mtime 不作准入/不作自动裁决（UNKNOWN，hit>1 直接 no-go）。
- **闸门/TTL/回退**：`enabled` 默认 false、一次性 pending、TTL 2000ms、失败 `clear()`、普通发图 `forwardCtx==null` 不 arm —— 全部沿用，零新增状态；r86c 探针 op 与生产闸门完全隔离。
- **只读红线**：解析器只用 `java.io.File`/`MessageDigest` 只读 API，不写 task 其它字段、不调宿主业务方法、不发消息、不建 db 记录；全链路唯一写入 = `setObjectField(task,"field_fullpath",origin)`；保留只读探针作回归旁证但默认关、不碰 host。
- **泛化**：`45522L`/`cff4f5a5` 是**本验证图**的精确体积与内容 MD5（【真机实证】本图 / 【UNKNOWN】换图），标为本图热修常量；换图目标 MD5 无字段来源、体积必变 ⇒ 不宣称通用化。P-FS「现场算 MD5」把「放弃内容 MD5 准入」的**技术前提**部分推翻（不需外部参照即可现场校验字节身份），但**未推翻**「无逐图外部参照可用」的语义结论。

**③ go/no-go 判据 + 是否需补肉眼基线 + Flash lane 最小改动？**
- **唯一 go 坐实** = 肉眼 A(原生清晰)→B(模块模糊)→开闸 B(翻清晰)→A(仍清晰) 翻转成立；`origin=true`/写入 fullpath `md5pre=cff4f5a5`/出口 `filemd5=cff4f5a5` 仅机制旁证；`hit`/`UploadHitCacheType` 两轮翻转绝不作判据。
- **需补一轮基线肉眼**：本轮为只读取证未采肉眼，落码后验收须同图同会话先补 A 清晰/B 模糊基线再做 A→B→A 翻转，不以字节证据替代终审。
- **互斥判据**（Q3.4 表）：肉眼清晰+翻转→go；清晰但旁证矛盾→go 记异常；字节在但写错路径（落 335644B jpg/15906B 缩略图/9554B 另图）→接入失败非证伪；字节不在（fs-no-hit）→no-go 字节不可获得；多候选歧义（hit>1）→no-go 不自动选；字节对但回包 0ef205c1→机制未闭环；崩溃→立即回退清 pending。
- **Flash lane 最小改动**：仅 `core/ForwardCdnFieldHook.kt`（新增 `resolveOriginPathByFS` + `inject()` 内命中写 fullpath + `origin=unavailable` 日志分支；旧 `resolveOriginFullpath` 删除/停用）；`StockCdnProbe.kt` 复用 `md5PrefixOfFile` 与 P-FS 扫描口径（只读探针保留作旁证、默认关）；`EntryReceiver.kt` 不改；`HookEntry.kt`/`FileSender.kt`/闸门/`q1.j`/fileId 注入等一律不动。

---

> 交付声明：Q1~Q4 + 三件套全部落盘，`[DONE ALL]` 为真（非骨架预置假标记）。全程只读推理，未落码、未跑 gradle、未碰真机 adb；未推翻任何已核事实（A=45522/cff4f5a5 清晰、B=9554/0ef205c1 模糊、决定量=fullpath 字节、x0.u 零 filemd5、fileId 非决定量、a3≠o2 查询键、G1/G2/G3 本轮真机值）；UNKNOWN 如实标注，推断未写成已证。

[DONE ALL]
