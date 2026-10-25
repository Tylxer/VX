# R15 · I1 实施记录 —— 朋友圈图片走 SNS 磁盘缓存直读

> 依据：`docs/_r15_sns_cache_plan.md`（Q1/Q2b/Q3/Q4/Q5/Q6；**Q2 公式已作废，一律按 Q2b**）
> Lane：I1（Flash 档）· 纯静态 + 本地编译，**未碰 adb / 未装包 / 未连设备**
> 状态：代码链路就位，`COPY_IMAGES` 仍为 `false`

## 子步骤清单（全部完成）

| # | 子步骤 | 状态 |
|---|---|---|
| 1 | `MomentsLogic.kt` 新增纯函数 `fliterPre` / `snsNameCandidates` / `matchSnsFileName` / `isSnsMediaName` | ✅ |
| 2 | `MomentsExport.kt` 接入索引直扫 + 四态计数 + tmp/rename 拷贝 + 20MB 上限 + stat 追加 | ✅ |
| 3 | 新增 JVM 单测 `SnsCacheMatchTest`（23 例） | ✅ |
| 4 | `tools/gradle_run.py` 编译 + 单测全绿 | ✅ |

---

## 1. 改动文件

### 1.1 `wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsLogic.kt`（+101 行，纯追加）

原有函数签名全部**未动**（`md5Hex` / `mediaKeys` / `imageCandidates` / `imageRootCandidates` /
`imageFileName` / `extractText` / `extractUrls` 保持原样）。新增一节
「SNS 磁盘缓存文件名匹配（R15）」，零 Android 依赖，可在 JVM 单测直接跑。

```kotlin
// 私有常量
private const val SNS_PREFIX_BIG   = "snsb_"   // 原图 1.1~1.5MB
private const val SNS_PREFIX_MID   = "snsu_"   // 压缩 6~42KB
private const val SNS_PREFIX_THUMB = "snst_"   // 缩略 20~70KB
private val SNS_EXCLUDE_PREFIXES = listOf("snstblur_", "wxid_", "sight_")

fun fliterPre(s: String): String
fun snsNameCandidates(mediaId: String): List<String>
fun isSnsMediaName(name: String): Boolean
fun matchSnsFileName(index: Set<String>, cand: String): String?
```

**`fliterPre`** —— 精确复刻 `Lbf3/t0#s`：**从下标 0 起**跳过前导 `'0'`，返回首个非 `'0'` 处到结尾。
`""`→`""`；跳完到结尾→`""`。例 `"15038644089917813396"` → 原样返回 `"15038644089917813396"`。

> ⛔ **订正（R16-F3，2026-10-24）**：本节原文写的是「从下标 2 起」并举例
> `"15038644089917813396" → "38644089917813396"`，**两处都错**。`t0#s` 的 `[19]`
> 是 `const/4 v3, #0`（`i = 0`）。已修正实现与单测，详见 `docs/_r16_v2_review.md` §1。

**`snsNameCandidates`** —— 三级前缀 × 两键（`fliterPre` 结果、`mediaId` 原值）。
前缀优先：`snsb_` > `snsu_` > `snst_`。最多 6 项；`fliterPre` 为空时去掉键的重复项，
自然退化为 3 项（`["snsb_","snsu_","snst_"]`，这些空键件在边界规则下必然匹配不到真机文件）。

**`matchSnsFileName`** —— 两级：精确命中优先；否则前缀兜底，但要求 `cand` 之后的下一个字符
**不是数字**（`snst_150` 不得命中 `snst_1503…`；`&enc` / `_V2` 之类尾巴允许）。
多个候选时取**字典序最小**，保证跨设备确定性。

**`isSnsMediaName`** —— 白名单外层的过滤（建索引时用）：必须 `snsb_`/`snsu_`/`snst_` 开头，
且不以 `snstblur_`/`wxid_`/`sight_` 开头。放在字符串匹配**外面**，三个匹配函数保持纯语义。

### 1.2 `wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsExport.kt`（+267 行）

**新增 import**：`java.io.FileInputStream`、`java.io.FileOutputStream`。

**新增四态计数器**（方案 Q5，互斥穷尽）：

```kotlin
private val imgRootMiss = AtomicLong(0)   // cache/ 无 32-hex 目录，或 <hash>/sns 不存在
private val imgFileMiss = AtomicLong(0)   // 索引里没有该 mediaId 候选文件（含 mediaId 未知）
private val imgReadFail = AtomicLong(0)   // 找到但读/拷抛异常，或超 20MB
private val imgCopied   = AtomicLong(0)   // tmp + rename 成功
```

**新增类型与函数**：

```kotlin
private class SnsIndex(val files: Map<String, File>, val names: Set<String>, val rootFound: Boolean)
private enum class ImgOutcome { ROOT_MISS, FILE_MISS, READ_FAIL, COPIED }

private fun buildSnsIndex(): SnsIndex
private fun isHex32(s: String): Boolean
private fun externalRoot(): String?
private fun copyOneFromCache(idx: SnsIndex, mediaId: String, dst: File): ImgOutcome
private fun mediaIdsOf(row: GroupDb.SnsRow): List<String>       // TODO：protobuf 未接
private fun copyImagesForRow(idx: SnsIndex, row: GroupDb.SnsRow, dir: File)
private fun countImg(outcome: ImgOutcome, snsId: Long, slot: Int, dst: File)
```

**接入点**（`runBlocking`）：

```kotlin
// 索引单轮建一次；开关关着就不建，别白扫盘
val snsIdx = if (COPY_IMAGES) buildSnsIndex() else null
...
if (ok) {                        // 文本落盘成功后
    ...
    if (snsIdx != null) copyImagesForRow(snsIdx, row, dir)
}
```

**索引直扫**（`buildSnsIndex`）严格按 Q2b：
`Environment.getExternalStorageDirectory()/Android/data/com.tencent.mm/cache` →
过滤「32 位小写 hex」目录 → 找 `sns` → 遍历 `<hex1>/<hex2>/` →
只收 `MomentsLogic.isSnsMediaName` 为真的名字进 `HashMap<String,File>`。
重复名字按先到先得跳过并计数 `dupSkipped`。扫到任一 `sns` 目录即 `rootFound=true`。
`imgRootStorage` 不可用 / `cache` 不存在 / 无 hex32 / 无 `sns` 四种情况都各打一行 `LogX.w`
并以 `rootFound=false` 返回（⇒ 后续一律归 `ROOT_MISS`）。

**拷贝**（`copyOneFromCache`）：
1. `!rootFound` → `ROOT_MISS`
2. `mediaId.isEmpty()` → `FILE_MISS`
3. 按 `snsNameCandidates` 顺序逐个 `matchSnsFileName`，第一个命中即用（原图优先、逐级回退）
4. 一个都没命中 → `FILE_MISS`
5. `src.length() > MAX_MEDIA_BYTES`（20MB，复用现有常量）→ `READ_FAIL` + 日志
6. `FileInputStream` 流式 → `FileOutputStream(<dst>.tmp)`，边写边数字节，超 20MB 立即中止并删 tmp
7. `dst` 已存在先 `delete()`（真机踩过不删 rename 失败）
8. `tmp.renameTo(dst)`；失败删 tmp → `READ_FAIL`
9. 任何异常 → 删 tmp → `READ_FAIL`

**四态互斥穷尽**：`copyImagesForRow` 里每个 slot 必调一次 `countImg`，
`countImg` 用**不带 else 的 `when`** 枚举四个分支，每支都 `incrementAndGet()` + 一行日志。
无 `?: return`、无 `getOrNull()` 静默吞。

**stat 行**（追加 4 字段，位置在 `err=` 之后）：

```
MOMENT export stat enabled= images=false running= lastRunAt= runs= written=
  skipped= listMiss= err= imgRootMiss= imgFileMiss= imgReadFail= imgCopied= list= root=
```

**新增只读探针 `sub=snsidx`**（不加会没法定点复核索引是否建起来）：

```
MOMENT snsidx rootFound=<bool> names=<count> sample=[...]
```

`probe` 的帮助串已同步为 `stat|path|export|img|imgtry|snsidx|imgurl|imgdl|scan`。

`COPY_IMAGES` **保持 `false`**，只把注释更新为「代码链路已就位，等 V1 复核 PASS 再开」。

### 1.3 `wxgroup/app/src/test/java/com/lx/wxgroup/feature/SnsCacheMatchTest.kt`（新增，23 例）

| 分组 | 覆盖 |
|---|---|
| `fliterPre`（5 例） | `"15038644089917813396"→"15038644089917813396"`、`""→""`、`"00"→""`、`"123"→"123"`、`"1500"→"1500"`、`"12"→"12"`、`"12a0b"→"12a0b"` |
| `snsNameCandidates`（4 例） | 6 项精确顺序、`pre` 空退化为 3 项、空 mediaId → 空列表、首项必是 `snsb_` |
| `matchSnsFileName`（7 例） | 精确命中、`&enc` 尾巴、`_V2` 尾巴、`snst_150` 不误配 `snst_1503…`、空集合→null、空候选→null、多尾巴取字典序最小 |
| 排除规则（5 例） | `snstblur_src_` / `wxid_` / `sight_` 全 false、三级前缀全 true、`snsblurt_`/`sightad_`/随机名 false |
| 端到端（1 例） | 索引里同时有模糊图+头像+视频+真图，匹配必须落到 `snsu_` 真图 |

---

## 2. 编译与单测实际输出

构建一律走 `tools/gradle_run.py`（未直接调 `gradlew`）。

```
$ python tools/gradle_run.py :app:compileDebugKotlin
EXIT=0
BUILD SUCCESSFUL in 15s

$ python tools/gradle_run.py :app:testDebugUnitTest
EXIT=0
BUILD SUCCESSFUL in 6s
```

测试结果汇总（`app/build/test-results/testDebugUnitTest/*.xml` 全量聚合）：

```
tests=588  skipped=0  failures=0  errors=0
```

其中新增套件：

```
<testsuite name="com.lx.wxgroup.feature.SnsCacheMatchTest"
           tests="23" skipped="0" failures="0" errors="0">
```

⇒ 编译 EXIT=0，全库 588 例（含新增 23 例）零失败零错误。

---

## 3. mediaId 链路进度

| 环节 | 状态 |
|---|---|
| `SnsInfo.content` BLOB 可读（`row.content`） | ✅ 现成 |
| `TimeLineObject.parseFrom` → `ContentObj`(6) → `i40.h`(8) → `vm3.d`(1) | ❌ **未接**（本 lane 明确不做 protobuf 反射） |
| `fliterPre(mediaId)` 与文件名后缀对齐 | ✅ 纯函数已实现并单测 |
| `<前缀>_<key>` 候选生成 + 边界匹配 | ✅ 已实现并单测 |
| 索引建表（cache/<hash32>/sns/<hex1>/<hex2>） | ✅ 已实现（探针 `sub=snsidx` 可现场验证） |
| 拷贝 + 四态计数 | ✅ 已实现 |

**当前行为**：`mediaIdsOf()` 返回 `emptyList()` ⇒ 若 `COPY_IMAGES` 打开，
每条动态会步入 `imgFileMiss` 并打一行
`MOMENT img noMediaId snsId=… (protobuf 未接，见 TODO R15-I1)`。
即：链路已通到「有 mediaId 就能拷」的边界，缺的只是 mediaId 的上游。

**还差什么**：`mediaIdsOf` 的 protobuf 实现（唯一改动点，签名已定：
`List<String>`，按 `i40.h` 中媒体对象顺序返回 `vm3.d`）。

---

## 4. 已知未做项

1. **protobuf 解析未接**（`mediaIdsOf` 返回空）。按任务书要求不自行发明反射解析；
   `TODO(R15-I1)` 注释已就地标注。
2. **真机未验证**：`COPY_IMAGES = false`，未上设备。V1 复核需覆盖方案 Q5 的 6 条判据，
   重点两条：滚 3 屏后 `imgCopied>0`；破坏 cache 目录后 `imgRootMiss>0`。
3. **视频未做**（`sight_` 被白名单排除，符合方案「本轮只导图片」）。
4. **增量补图未做**（文本已导出的动态不会重进导出循环，见方案 Q6 的取舍）。
5. `imgtry` / `imgRoots` / `imageCandidates` 等旧 md5 路线**原样保留未删** —— 它们是
   历史取证资产，本轮不重构；新链路走独立的 `buildSnsIndex`，两条路互不影响。

---

[DONE]

---

## 主会话复核（2026-10-24 03:4x，独立复现，未听子代理自报）

| 项 | 复核方式 | 结果 |
|---|---|---|
| 编译 | 主会话自己跑 `tools/gradle_run.py :app:compileDebugKotlin` | ✅ EXIT=0 / BUILD SUCCESSFUL |
| 单测 | 主会话自己跑 `:app:testDebugUnitTest` | ✅ EXIT=0 |
| 测试数量 | 解析 `build/test-results/testDebugUnitTest/*.xml` 全量聚合 | ✅ `tests=588 failures=0 errors=0`（25 个 testcase XML） |
| 新测试真实存在 | `SnsCacheMatchTest.kt` 23 例，XML 有对应 `tests="23" failures="0"` | ✅ **真的跑了，不是编的** |
| 四态互斥穷尽 | 读 `MomentsExport.kt:707-719` 的 `when` 分支 | ✅ 四态各有 `incrementAndGet()`，无 `?: return` 吞异常 |
| 索引单轮建一次 | `MomentsExport.kt:263` 在导出入口建，非循环内 | ✅ |
| 空前缀误配 | 主会话手算 `matchSnsFileName`：`cand="snsb_"` 时因「下一字符非数字」被拒 | ✅ 不误配 |
| stat 行 | `MomentsExport.kt:945-946` 四个字段都在 | ✅ |

> ⚠️ 我一度误报「新函数零测试覆盖」，原因是只在 `MomentsLogicTest.kt` 里 grep ——
> 子代理把测试建在了**独立的** `SnsCacheMatchTest.kt`。**教训：grep 覆盖要看全目录，别只看同名的那个文件。**

### ✅ 已由 V1 否证：`fliterPre` 二次截断**不是 bug**（我原来的判断错了）

我当初的担忧：真机文件名存的是 `fliterPre(vm3.d)` 的结果，若上游再喂已砍过的串，会砍第二次 ⇒ 匹配失败。

**V1 复核 + 主会话独立实测，双双证明这个担忧不成立**：

`snsNameCandidates` 把 **`mediaId` 原值也放进了候选键**（`MomentsLogic.kt:426-428`），所以：

```
真机文件名 = snsb_38329270049772087478

情形A 上游返回 vm3.d 原值 15038329270049772087478
  候选: snsb_38329270049772087478   ← 命中（fliterPre 砍一次，正确）
        snsb_15038329270049772087478

情形B 上游已返回被砍过的串 38329270049772087478
  候选: snsb_329270049772087478     ← fliterPre 砍了第二次（错）
        snsb_38329270049772087478   ← 原值候选精确命中 ✅
```

⇒ **双候选天然兜住了两种情形**。我原来说「不能兜住」是错的。

**正确做法（V1 给的建议，采纳）**：
- ⛔ **不要**加「不以 15/14 开头就只放原值」的形状校验 —— 脆弱且会误伤。
- ✅ **钉死 `mediaIdsOf()` 的契约 = 返回 `vm3.d` 原值**，并在真机验证第一步**打印原值 + fliterPre 结果 + 实际文件名**三者对比，现场确认「只砍一次」。

---

## 第二轮（I2）：接 protobuf + 修 P2

> 触发：`docs/_r15_v1_review.md` 唯一 P0（`mediaIdsOf()` 返回空，`imgCopied` 恒 0）+ 两条 P2。
> 约束：纯静态 + 本地编译，**不碰 adb / 不装包 / 不连设备**。

### 改动的文件（4 个）

| 文件 | 改动 |
|---|---|
| `MomentsLogic.kt` | 新增 3 个纯函数 + 3 个私有常量 + 私有 `readVarint`（文件尾部新增一节，位置在原图下载节之前） |
| `MomentsExport.kt` | `mediaIdsOf` 接通；`copyImagesForRow` 加对账日志；`imgNoMedia` 独立计数；`buildSnsIndex` 三层 listFiles 显式判空；`copyOneFromCache` 返回值加命中名；注释与 `COPY_IMAGES` 说明同步 |
| `SnsProtoTest.kt` | **新建**，18 个用例，全部手工构造 protobuf 字节 |
| `docs/_r15_i1_impl.md` | 本节 |

⛔ 未改：`SnsCommentLogic.kt`、`docs/_r15_sns_cache_plan.md`、`docs/_r15_v1_review.md`、
`fliterPre` / `snsNameCandidates` / `matchSnsFileName` / `isSnsMediaName`（四者逻辑一字未动）。

### 一、【P0】protobuf 走线格式（不是反射）

链路（方案 Q1 定稿，未改结构）：
```
content --字段6(wt2)--> ContentObj --字段8(wt2, repeated)--> 媒体对象 --字段1(wt2)--> d = mediaId
```

新增的 3 个纯函数（`MomentsLogic.kt`，零 Android 依赖）：

- `protoSegments(b, field): List<ByteArray>` —— 通用线格式遍历，`when(tag and 0x07L)` 覆盖
  **0(varint) / 1(fixed64,+8) / 2(length-delimited) / 5(fixed32,+4)**，3/4(group) 直接作废。
  结构断裂（字段号 0 / varint 越界 / 长度越界 / 跳字节出界）⇒ **返回空列表**，不返回半截。
- `protoString(b, field): String?` —— 取第一个 wt2 段的 UTF-8，没有则 null。
- `mediaIdsFromContent(content): List<String>` —— 一步到位，逐层用上面两个走；任一层缺失 ⇒ 空列表。

**为什么不是反射**：`q54/vm3` / `i40` / `TimeLineObject` 这些混淆名每次升级都可能变（换包就没了），
而**线上协议的字段号是稳定的**。所以只按字段号 + 线格式手走字节，不引入 protobuf 库，
⛔ 不调 `vm3.parseFrom`。

**`mediaIdsOf` 的最终契约**：

1. 返回 **`vm3.d` 原值**（一个字符都不加工：不砍前 2 位、不跳前导 0、不截断、不加 `snsb_` 前缀）；
2. **顺序 = 字节顺序**（= 动态里的图片顺序），`slot` 序号靠它；
3. `content == null` / 空 / 结构断裂 / 无字段 6 / 无字段 8 / 元素无字段 1 ⇒ **`emptyList()`**，
   **绝不抛异常**（上层有计数兜底）。

### 二、【P0 配套】现场对账日志

`copyImagesForRow` 对**每条** mediaId 打一行（含 `hit=NONE`，⛔ 无 `?: return` 吞）：

```
MOMENT img mid snsId=<id> raw=<vm3.d 原值> pre=<fliterPre(raw)> cand0=<第一个候选名> hit=<命中文件名|NONE>
```

用途：真机上直接看 `raw` / `pre` / `hit` 三者，确认 `fliterPre` **只砍了一次**（V1 判据 #1）。

为了让 `hit` 能打出来又不重复扫索引，`copyOneFromCache` 的返回值从 `ImgOutcome` 改成
`ImgResult(outcome, hit)`；四态判定逻辑**一行未改**（`ImgResult` 只是多带一个字段）。

### 三、【P2】两条合规

1. **`buildSnsIndex` 三层 `listFiles` 不再静默**（V1 P2-2）
   旧：`runCatching { x.listFiles() }.getOrNull().orEmpty()` —— null 与「空目录」混为一谈。
   新：显式判 null → `LogX.w("... listFiles failed at <层> layer path=<path>")` → `continue`。
   同时 `listFail` 计数进汇总日志 `sns index built ... listFail=N`，真机可一眼看有没有读不到。
2. **`imgFileMiss` 口径统一**（V1 P2-1）
   新增 `imgNoMedia: AtomicLong`（按**动态**计）；`ids.isEmpty()` 只加它，
   **不再**加 `imgFileMiss`（后者严格按 **mediaId** 计）⇒ 方案 Q5 判据 #5
   「四态之和 = 已尝试 mediaId 总数」重新严格成立。`stat()` 行已追加 `imgNoMedia=N`。
   ⚠️ 这个计数同时是 P0 的现场观测点：`imgNoMedia` 恒等于已导出动态数 ⇒ protobuf 照样没通。

### 四、单测 `SnsProtoTest.kt`（18 例，全手工字节）

| # | 用例 | 断言 |
|---|---|---|
| 1 | `singleMediaIdIsParsed` | 1 个 id |
| 2 | `mediaIdIsReturnedVerbatim` | 原值不改（含 `!= fliterPre(raw)` 反证） |
| 3 | `threeMediaIdsKeepByteOrder` | 3 个 id，顺序 = 字节序 |
| 4 | `orderFollowsBytesNotSorting` | 字典序与字节序相反时跟字节序 |
| 5 | `nullAndEmptyReturnEmpty` | null / 空数组 ⇒ 空 |
| 6 | `truncatedBytesReturnEmpty` | 逐字节截断**全程不抛**；越界长度 ⇒ 空 |
| 7 | `brokenWireFormatReturnsEmpty` | 字段号 0 / varint 截断 / group ⇒ 空 |
| 8 | `missingFieldsReturnEmpty` | 缺 6 / 缺 8 / 元素缺 1 / id 空串 ⇒ 空 |
| 9 | `skipsOtherFieldsAndStillFindsContentObj` | 顶层混 3(wt0)/7(wt2)/9(wt1)/11(wt5) 仍取到 |
| 10 | `skipsOtherFieldsInsideContentObjAndMedia` | ContentObj 内混 2(wt0)/5(wt2) 仍取 2 个 |
| 11 | `mediaWithExtraFieldsStillReadsId` | 媒体对象带 42 字段形态仍取 d |
| 12 | `protoSegmentsReturnsAllMatchingSegments` | 多段 + 非目标字段被跳过 |
| 13 | `protoSegmentsRejectsBadFieldNumber` | field ≤ 0 ⇒ 空 |
| 14 | `protoStringFirstOnlyAndNullOnMissing` | 只取第一个 / 缺字段 null / 空数组 null |
| 15 | `protoStringHandlesUtf8` | 中文不烂码 |
| 16 | `endToEndCandidatesFromParsedId` | 解析值 → `fliterPre`/`snsNameCandidates`/`matchSnsFileName` 全对 |
| 17 | `endToEndAlreadyTrimmedIdStillHits` | 已砍过的串仍精确命中（复验 V1 结论） |
| 18 | `endToEndMultipleIdsMapToDistinctFiles` | 多图映射到不同文件名（slot 不互相覆盖） |

### 五、验收实测

```
$ python tools/gradle_run.py :app:compileDebugKotlin
BUILD SUCCESSFUL in 17s          EXIT=0

$ python tools/gradle_run.py :app:testDebugUnitTest
BUILD SUCCESSFUL in 20s          EXIT=0

测试结果统计（解析 build/test-results/testDebugUnitTest/*.xml）
tests=606  failures=0  errors=0  skipped=0
  └ SnsProtoTest.xml: tests=18 failures=0 errors=0
```

**606 > 588** ⇒ 新用例确实跑了（588 + 18 = 606，正好对上，说明没有旧用例被吞）。

`COPY_IMAGES` 仍为 **false**（`MomentsExport.kt:57`）。

### 六、残留问题（留给真机验证，不在本 lane）

1. **字段号未经真机样本验证**：6 / 8 / 1 来自 DEX 反汇编（`kv4/a->i(II)` / `gv4/a->j(I)`），
   静态闭环但**没有真机 content BLOB 跑通一次**。上真机第一件事就是看 `img mid` 日志
   有没有 `raw=` 值 —— 全是 `imgNoMedia` 就说明字段号猜错，回头改这 3 个常量即可
   （探测点集中在 `PROTO_FIELD_*`，不用动解析代码）。
2. **`imgCopied` 仍为 0 的另一种可能**：mediaId 解对了但缓存里压根没文件（方案 Q3 已记录
   「图片可能从未落盘」的历史观测）。现在四态能区分这两种情况了 —— 这正是本轮修 P2 的意义。
3. `img noMediaId` 日志文案已从「(protobuf 未接…)」改成「(protobuf 无媒体/结构断裂)」，
   真机上看到它时**不要再以为是没接**。
4. `LogX` 有 1 秒**同内容**去重：同一条动态里若出现两个**完全相同**的 mediaId，
   第二行 `img mid` 会被吞（内容一致，信息无损；不同 mediaId / 不同 snsId 不受影响）。
   若真机上要求「一行不多一行不少」，再给该行加自增序号（`imgSeq` 已有，改格式即可）。

[DONE]


---

## 第三轮（主会话）：补一个不依赖 COPY_IMAGES 的只读探针

**问题**：`COPY_IMAGES=false` 时 `copyImagesForRow` 根本不跑，I2 加的 `MOMENT img mid` 对账日志也就不会打。
但 V1 明确要求真机第一步**看对账日志确认 fliterPre 只砍一次** —— 这就自相矛盾：
验的人被验的东西锁住了。

**做法**（主会话直接改，Flash 类活）：`MomentsExport.kt` 新增 `probeSnsCache(limit)`，
接 `op=moments sub=snsprobe --ei n <条数>`，**完全不读 `COPY_IMAGES`、不拷贝任何文件**：

```
MOMENT snsprobe idx rootFound=<bool> names=<索引文件名数> files=<map大小>
MOMENT snsprobe snsId=.. ids=0 content=<字节数>B                    ← 解不出 mediaId
MOMENT snsprobe snsId=.. ids=<n> #<slot> raw=.. pre=.. cand0=.. hit=<名|NONE>
MOMENT snsprobe sum rows=.. noMedia=.. withIds=.. hit=..
```

**判据（现场三步走）**：
1. `noMedia` 接近 `rows` 且 `content` 字节数 > 0 ⇒ **字段号错**，改 `MomentsLogic.PROTO_FIELD_*`
2. `withIds>0` 但 `hit=NONE` ⇒ mediaId 拿到了、文件名不匹配，拿 `raw`/`pre` 跟真机文件名对比
3. `hit=<name>` ⇒ **链路通**，才允许开 `COPY_IMAGES` 进 M1

**验证**：`:app:compileDebugKotlin` EXIT=0；`:app:testDebugUnitTest` EXIT=0，`tests=606 failures=0 errors=0`（零回归）。
