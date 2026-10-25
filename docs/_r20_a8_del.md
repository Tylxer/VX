# R20 · lane `A8-del` → `a8-finish`：物理删除「旧 md5 路线」遗留代码

> ## 状态：✅ 全部完成（`A8-del` 交付零阻塞部分，`a8-finish` 收尾剩余全部删除）
>
> 分工：
> - lane `A8-del`（上一轮，被 429 打断）：`SnsCdnImage.Cfg.rewritePath` 删除、
>   `MomentsLogic.md5sFrom` 删除、两处文档订正 —— 见 §2 / §2b / §3。
> - lane `a8-finish`（本轮）：`MomentsExport.kt` 三个旧路线宿主 + dispatch 下架、
>   `MomentsLogic.kt` 6 个符号清空、`MomentsLogicTest.kt` 10 条死测试删除 —— 见 §4。
>
> ⚠️ §1 与 §4.6 的 lane 边界声明是**上一轮的**历史记录，本轮 `a8-finish` 经 team-lead
> 显式授权跨入 `MomentsExport.kt`（限本次列出位置）。
>
> lane 边界（`A8-del` 原声明，保留存档）：本轮**只改** `MomentsLogic.kt` 与 `SnsCdnImage.kt`
> （+ 配套测试/文档）。
> ⛔ 不许碰 `MomentsExport.kt` / `SnsGuard.kt`（lane `sample-src` 在改）/ `MomentsNotify*.kt`。
> ⛔ 不碰 adb、不装包、不操作真机。
>
> 用户 2026-10-25 拍板：**A8 = 删**。依据：`docs/_r19_a4_probe.md`（新路线 SNS 磁盘缓存直读
> 已真机验证通过：`rows=10 rowsHit=4 ids=7 hit=6`；mediaId 路径 `8 → 5 → 1`）。
> 旧 md5 路线已被 R15（分桶命中 0.68%）、R16（4 个真机 md5 全盘 find 零结果）、
> R19（文件名根本不含 md5，是 20 位数字串）三次真机证伪。

---

## 1. 删前 grep 取证（逐个符号，2026-10-25）

### 1.1 符号清单与命中

| # | 符号 | 所在 | grep 命中（除定义处） | 判定 |
|---|---|---|---|---|
| 1 | `MomentsLogic.mediaKeys` | `MomentsLogic.kt:254` | `MomentsExport.kt:524`（logImgProbe）<br>`MomentsExport.kt:908`（tryCopyImages）<br>`MomentsLogicTest.kt:178`<br>`docs/*`、`tools/_moments.py:6,54`（文案） | 🔴 **活调用点 ×2** |
| 2 | `MomentsLogic.md5Hex` | `MomentsLogic.kt:266` | `MomentsLogicTest.kt:184,189,190,191` | 🟡 仅测试 |
| 3 | `MD5_RE`（private） | `MomentsLogic.kt:295` | `MomentsLogic.kt:310`(md5sOf) `:338`(imageCandidates) | 🟡 仅服务被删者 |
| 4 | `MomentsLogic.md5sOf` | `MomentsLogic.kt:309` | `MomentsExport.kt:525`、`MomentsExport.kt:908`<br>`MomentsLogicTest.kt:388,395-397,399,405,406` | 🔴 **活调用点 ×2** |
| 5 | `MomentsLogic.md5sFrom` | `MomentsLogic.kt:313` | **零命中**（全仓无调用） | ✅ 死代码 |
| 6 | `MomentsLogic.imageCandidates` | `MomentsLogic.kt:337` | `MomentsExport.kt:532`、`MomentsExport.kt:914`<br>`MomentsLogicTest.kt:412,429,431` | 🔴 **活调用点 ×2** |
| 7 | `MomentsLogic.pickExisting` | `MomentsLogic.kt:359` | `MomentsExport.kt:533`、`MomentsExport.kt:913`<br>`MomentsLogicTest.kt:437,438,443,444,452` | 🔴 **活调用点 ×2** |
| 8 | `Cfg.rewritePath`（SnsCdnImage） | `SnsCdnImage.kt:85` | `SnsCdnImage.kt:102`（唯一「写」，非读）<br>`Keys.kt:311` 注释、`docs/*`（文案） | ✅ 零读取点 → 可删 |

### 1.2 🔴 阻塞项：`MomentsExport.kt` 里还有 **4 组活调用点**

按 lane 规则第 1 条「发现还有活调用点就**停下来报告**，不要删」，对 **#1/#4/#6/#7**
暂不删除，先报 team-lead 协调。实际调用它们的是 `MomentsExport.kt` 的**两个旧 md5 探针**：

| 探针 | 位置 | 触发方式 | 调用链 |
|---|---|---|---|
| `logImgProbe()` | `MomentsExport.kt:522-550`（调用点 `:429`「导出时自动打」、`:1343` `sub=img`） | `op=moments sub=img` + 导出主流程自动 | `mediaKeys`(:524) → `md5sOf`(:525) → `imageCandidates`(:532) → `pickExisting`(:533) |
| `tryCopyImages()` | `MomentsExport.kt:888-937`（调用点 `:1346` `sub=imgtry`） | `op=moments sub=imgtry` | `md5sOf(mediaKeys(...))`(:908) → `imageCandidates`(:914) → `pickExisting`(:913) |

⇒ 这两个函数本身就是**旧 md5 路线在仓内的最后宿主**。它们不删，`MomentsLogic` 侧的
4 个符号就删不掉（删了 `MomentsExport.kt` 直接编译失败）。
而 `MomentsExport.kt` 属于 lane `sample-src`（见 `docs/_r20_sample_src.md` 的 lane 边界声明）。

**已采取的处置**：Concurrent 阻塞上报 team-lead（见 §1.3），同时先推进**不受阻塞**的部分
（`Cfg.rewritePath` 删除 + 文档订正），不在等待期空转。
⇒ **最终由 lane `a8-finish` 收尾**：team-lead 采用方案 B（显式授权跨边界改 `MomentsExport.kt`，
且 `sample-src` 已落盘冻结），两个探针与 `MomentsLogic` 侧 6 个符号已全部删除，见 §4。

### 1.3 上报与协调记录

- 向 `team-lead` 发出阻塞报告 + 两个可选方案（见本文件 §1.2 及后续 append）。
- 方案 A（推荐）：由 lane `sample-src` 在其本轮改动里删掉 `logImgProbe` / `tryCopyImages`
  两个旧探针及其 dispatch（`:429` `:1343` `:1346`），`A8-del` 随后删 `MomentsLogic` 侧符号。
- 方案 B：team-lead 显式授权跨边界改 `MomentsExport.kt`（需 sample-src lane 先落盘冻结）。
  ⇒ ✅ **最终采用 B**，由 lane `a8-finish` 执行，结果见 §4。

---

## 2. 已完成 · `SnsCdnImage.Cfg.rewritePath` 删除 ✅

零调用点证据（删前跑）：

```
$ grep -rn "rewritePath" wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsCdnImage.kt
81:    // ⚠️ 字段 `rewritePath`（`sns.cdn_rewrite_path`）**当前零消费点** ...   ← 注释
85:    private data class Cfg(val enabled: Boolean, val rewritePath: Boolean)   ← 声明（唯一）

$ grep -rn "\.rewritePath" <全仓>
（零命中 → 全仓没有任何读取点）

$ grep -rn "REWRITE_PATH" wxgroup/app/src/
SnsCdnImage.kt:102   Kv.getBool(Keys.SNS_CDN_REWRITE_PATH)   ← 唯一出现，且是「写」不是「读」
Keys.kt:199          const 定义
Keys.kt:311          注释
Keys.kt:321          PrefSpec 登记（hidden = true）
PrefsVisibilityTest.kt:36  测试断言「该 key 仍然存在但不上屏」
```

实际改动（`SnsCdnImage.kt`）：

| 位置 | 改动 |
|---|---|
| `:81-85` | `private data class Cfg(val enabled: Boolean, val rewritePath: Boolean)` → `private data class Cfg(val enabled: Boolean)`；上方注释改写为「⛔ 已删于 R20（A8 收敛）」+ 判死依据 + 「key 仍留在 Keys.kt，不动」的说明 |
| `:102` | `Cfg(Kv.getBool(Keys.SNS_CDN_IMG), Kv.getBool(Keys.SNS_CDN_REWRITE_PATH))` → `Cfg(Kv.getBool(Keys.SNS_CDN_IMG))`，留注释说明「原来的第二参唯一作用是给零读取点字段赋值」 |

⛔ **刻意没做**（超边界 / 有连带风险，留给后续 lane）：

- 没删 `Keys.SNS_CDN_REWRITE_PATH` const 与它那条 `PrefSpec`：那是 `Keys.kt`（别的 lane 正在改），
  且删 key 会影响 `BackupFlow.knownKeys` 的备份合并语义 + `PrefsVisibilityTest` 断言。
  key 现在退化成「登记了但全仓无消费点」，已在 `Keys.kt:311` 注释里有说明。
- 没动 `enabled` 字段：`Cfg` 变成单字段 data class 是**可接受**的中间态
  （保留 data class 形态，`stateLine()` / `enabled()` 读法不变，改动面最小）。

## 2b. 已完成 · `MomentsLogic.md5sFrom` 删除 ✅

零调用点证据（**唯一一个真正零命中的符号**）：

```
$ grep -rn "md5sFrom" wxgroup/ tools/ docs/
MomentsLogic.kt:313   定义本身
MomentsLogic.kt:319   imageCandidates KDoc 里的符号引用
⇒ 无调用点、无测试、无探针、无文档外部引用
```

| 位置 | 改动 |
|---|---|
| `MomentsLogic.kt:312-314` | 删 `md5sFrom()` 函数体（3 行），原处留「⛔ 已删于 R20」注释 + 为什么删（A4 定案 SNS 缓存直读，md5 输入假设三次真机证伪） |
| `MomentsLogic.kt:319` | imageCandidates KDoc 的符号清单里去掉 `[md5sFrom]`（避免 KDoc 指向已删符号） |

## 3. 已完成 · 文档订正 ✅

订正方式：**加「已作废」标记 + 指向 `docs/_r19_a4_probe.md`，不动历史正文**（取证过程有保留价值）。

| 文件 | 位置 | 改动 |
|---|---|---|
| `docs/需求功能清单.md` | §6.4 朋友圈表格，**紧接 R2/R3 两行（:323-324）之后**新增一行 | 新行标题 `【订正】上面 R2 / R3 两行的 md5 结论`（2026-10-25 R20），正文写明：⛔ 已作废于 R20 + 三次真机证伪（R15 分桶 0.68% / R16 全盘 find 零结果 / R19 文件名是 20 位数字串不含 md5）+ ✅ 正解 mediaId 路径 `8→5→1`、`rows=10 rowsHit=4 ids=7 hit=6` + 指向 `docs/_r19_a4_probe.md`；并写明旧 md5 代码正在本 lane 删除、证据见 `docs/_r20_a8_del.md`。R2/R3 两行**原样保留** |
| `docs/取证结论.md` | §6.4b 标题下的三路线汇总表之后 | 新增引用块「⛔ 2026-10-25 R20 补订正」：区分「实测仍为真」（①②的观察）与「推论已作废」（→图没落盘）；写明真文件名是 20 位数字串、mediaId 路径已由 `6→8→1` 改 `8→5→1`、R19 实测数字、指向 `docs/_r19_a4_probe.md` 与 `docs/_r20_a8_del.md`；并单独强调 **③（裸 url 恒 400）仍成立**，只是与「图片能不能留存」已无关 —— 防止新人反过来说「R20 说图片能存，那 ③ 也被推翻了」 |

## 4. MomentsLogic 侧符号删除（✅ 已完成，lane `a8-finish`）

🔴 → ✅：`MomentsExport.kt` 侧的两个旧 md5 探针（`logImgProbe` / `tryCopyImages`）已由 lane
`a8-finish` 先行物理删除，本表 6 个符号随后照表删除完毕。

### 4.1 `MomentsExport.kt` 侧宿主下架（步骤 1）

| # | 位置 | 删除内容 |
|---|---|---|
| 1 | `logImgProbe(row, nick)` 整个函数（含 KDoc） | 旧路线唯一「只打日志」出口 |
| 2 | `imgRoots()` 整个函数（含 KDoc） | 其内部的 `MomentsLogic.imageRootCandidates(...)` 两处调用随之消失（符合预期） |
| 3 | `tryCopyImages(limit)` 整个函数（含 KDoc） | 旧路线唯一「真拷贝」出口 |
| 4 | dispatch 的 `"img" ->` / `"imgtry" ->` 两个分支 | `op=moments sub=img` / `sub=imgtry` 同步下架 |
| 5 | `else ->` 的 `subs=` 列表 | 去掉 `img`、`imgtry` |
| 6 | `private const val IMG_PROBE_MAX = 3` | 只服务 `logImgProbe` 的限流常量 |
| 7 | `private val imgProbeLogged = AtomicInteger(0)` | 同上；`import ...AtomicInteger` 随之全文件零引用 ⇒ 一并删 |
| 8 | `private val imgSeq = AtomicLong(0)`（含 KDoc） | 只有 `logImgProbe` 的日志序号用它 |
| 9 | `runBlocking()` 内 `imgProbeLogged.set(0)` | 每轮复位 |
| 10 | 导出主循环 `if (imgProbeLogged.get() < IMG_PROBE_MAX) logImgProbe(row, nick)` | 图片探针的自动触发点 |
| 11 | 文件顶 KDoc `:50-59` | 标题「旧 md5 路线仍在」→「已于 R20 物理删除」+ 判死依据 + 指向新链路 |
| 12 | `logImgUrls()` KDoc / `imgurl` 分支注释 / `probe()` KDoc | 去掉对已删 `logImgProbe` 的活引用（改成「已删的旧探针」措辞） |

每处删除点均留 `// ⛔ 已删于 R20（A8 收敛）：<符号名> —— …` 注释，格式与本文件 §2/§2b 一致。

### 4.2 `MomentsLogic.kt` 侧符号清空（步骤 2）

| 符号 | 处理 |
|---|---|
| `mediaKeys()` | 删（含 KDoc） |
| `md5Hex()` | 删 |
| `MD5_RE`（private） | 删；其 KDoc 的取证精华**已迁移**到同位置注释块（BLOB 混着 32 位小写 hex / 4 个真机 md5 全盘 find 零结果 / `reader_-255_<md5>.jpg` 是公众号配图 / R15 缓存目录 + 文件名不含 md5 / 正解索引直扫），其余删 |
| `md5sOf()` | 删（含 KDoc） |
| `imageCandidates()` | 删（含 KDoc） |
| `pickExisting()` | 删（含 KDoc） |
| `import java.security.MessageDigest` | 删（删后全文件零引用） |
| `imageRootCandidates()` KDoc 里对 `[pickExisting]` 的引用 | 改写成「已于 R20 删除的 `pickExisting`」，避免 KDoc 指向已删符号 |

⛔ **`imageRootCandidates()` 保留**（`MomentsLogicTest.kt:408-426` 的
`imageRootCandidatesUseExternalMicroMsgLayout` 仍是活测试；函数本身与新路线无关但也未作废）。

⛔ 未动（新路线在用）：`mediaIdsFromContent` / `fliterPre` / `snsNameCandidates` /
`protoSegments` / `protoString` / `readVarintPublic` / `isSnsMediaName` / `matchSnsFileName` /
`extractUrls` / `extractText` / `idsByPath` / `PROTO_FIELD_*`。

### 4.3 死测试清除（步骤 3）

`MomentsLogicTest.kt` 删除 10 个 `@Test`（原行号见下方旧表）：

```
mediaKeysIncludeSignedUnsignedAndUrlTail      md5HexIs32Chars
md5sOfOnlyKeepsLowercase32Hex                 md5sOfRejectsUppercaseAndWrongLength
md5sOfDedupesAndKeepsEmpty                    imageCandidatesProduceBothLayoutsPerRoot
imageCandidatesIgnoreBadRootsAndBadMd5        pickExistingReturnsFirstHit
pickExistingNullWhenNothingExists             pickExistingSwallowsThrowingProbe
```

两处删除点各留一段 `// ⛔ 已删于 R20（A8 收敛）` 注释 + 判死依据。
`@Test` 计数 60 → 50。import 复核：`assertNull` / `assertFalse` / `assertEquals` / `assertTrue`
在其它测试中仍有使用，**全部保留**，无新增未使用 import。

⛔ `imageRootCandidatesUseExternalMicroMsgLayout` 及 `imageRootCandidates*` 相关测试**未删**。

### 4.4 删除后 `grep` 零引用确认

```
$ grep -rn "MomentsLogic\.(mediaKeys|md5Hex|md5sOf|imageCandidates|pickExisting|md5sFrom)" wxgroup/app/src
（零命中）
$ grep -rn "imgProbeLogged|IMG_PROBE_MAX|logImgProbe|imgRoots|tryCopyImages|imgSeq" --include=*.kt wxgroup/
MomentsExport.kt / MomentsLogic.kt / MomentsLogicTest.kt 中仅剩「⛔ 已删于 R20」注释里的符号名
（无任何活引用）
$ grep -rn "AtomicInteger" wxgroup/.../MomentsExport.kt
（零命中，import 已删）
$ grep -rn "MessageDigest" wxgroup/.../MomentsLogic.kt
（零命中，import 已删）
```

### 4.5 ⚠️ 遗留项：`tools/_moments.py --img` 已失效（未处理，超本 lane 边界）

`tools/_moments.py:6` 的 usage 文案与 `:54` 的 `moments("img", 5)` 仍指向已删的
`op=moments sub=img` 分支 ⇒ 现在跑 `--img` 只会拿到 `MOMENTS export subs=stat|path|...`
（`when` 落到 `else`），**不再产出对账日志**。同一脚本内 `--img` 也是旧 md5 路线的对账工具。

处置建议：**删掉 `--img` 选项与其 usage 行**，图片对账统一改用
`op=moments sub=snsprobe`（新链路只读探针）。本 lane 未动，因为任务清单未列 `tools/`，
且删选项要同步改 usage / 判据链文案（`tools/_moments.py:17-18` 也写着「命名规则未取证」，
同样过时）。留给后续 lane 或主会话一并处理。

### 4.6 原始预备清单（保留存档，行号为删除前）

| 符号 | 位置 | 连带处理 |
|---|---|---|
| `mediaKeys()` | `MomentsLogic.kt:246-264` | KDoc 整段删；`import java.security.MessageDigest` 一并删 |
| `md5Hex()` | `MomentsLogic.kt:266-271` | 删后 `MessageDigest` 全文件零引用 ⇒ 必须删 import |
| `MD5_RE`（private） | `MomentsLogic.kt:275-295` | 含 24 行 R2/R15 取证 KDoc；建议**保留其中 6 行精华**迁到 `md5sOf` 原处注释，其余删 |
| `md5sOf()` | `MomentsLogic.kt:297-310` | 承接 MD5_RE 的取证精华 |
| `imageCandidates()` | `MomentsLogic.kt:316-349` | KDoc 删 |
| `pickExisting()` | `MomentsLogic.kt:351-364` | KDoc 删 |
| `imageRootCandidates()` | `MomentsLogic.kt:745-790` | ✅ **已确认保留**：`MomentsLogicTest` 有 4 条活 `@Test` 在用 |

对应要删的**死测试**（`MomentsLogicTest.kt`，均属被删死代码自身的测试，不含新路线断言）：

```
mediaKeysIncludeSignedUnsignedAndUrlTail()      :177-185   ✅ 已删
md5HexIs32Chars()                               :188-192   ✅ 已删
md5sOfOnlyKeepsLowercase32Hex()                 :374-389   ✅ 已删
md5sOfRejectsUppercaseAndWrongLength()          :393-400   ✅ 已删
md5sOfDedupesAndKeepsEmpty()                    :403-407   ✅ 已删
imageCandidatesProduceBothLayoutsPerRoot()      :410-423   ✅ 已删
imageCandidatesIgnoreBadRootsAndBadMd5()        :426-432   ✅ 已删
pickExistingReturnsFirstHit()                   :435-439   ✅ 已删
pickExistingNullWhenNothingExists()             :442-445   ✅ 已删
pickExistingSwallowsThrowingProbe()             :448-456   ✅ 已删
⇒ 合计 10 个 @Test
```

⛔ **绝对不动**（新路线在用，删了就崩）：`mediaIdsFromContent()` / `fliterPre()` /
`snsNameCandidates()` / `protoSegments()` / `protoString()` / `readVarintPublic()` /
`isSnsMediaName()` / `matchSnsFileName()` / `extractUrls()` / `extractText()`。

## 5. 验收

**待主会话统一跑 `testDebugUnitTest` 回填**（`files= tests= failures= errors=`）。

本 lane 按硬约束**未跑任何 gradle 任务**（另一 lane 并发构建，会触发
`FileAnalysisException: size in bytes: -1`），正确性靠读码 + 逐符号 `grep` 零引用确认
（见 §4.4）。已做的人工静态核对：

- 三个文件花括号配平：`MomentsExport.kt` 268/268、`MomentsLogic.kt` 73/73、
  `MomentsLogicTest.kt` 61/61（`awk` 计数）。
  ⚠️ `MomentsExport.kt` 的计数含主会话同期落的 F-1 改动
  （`runBlocking()` 开头新增局部函数 `bail(msg)` + 5 处 `return bail(...)`，约 +2 对花括号），
  删除前后皆由本 lane 复核过配平；`MomentsExport.kt` 现 1357 行。
- `MomentsLogicTest.kt` 的 `@Test` 计数：60 → **50**（净删 10）。
- 未使用 import 复核：`AtomicInteger`（MomentsExport）与 `MessageDigest`（MomentsLogic）
  已随之删除；测试文件四个 `assert*` 与 `ZoneId` 仍有使用，全部保留。

---

[DONE]
