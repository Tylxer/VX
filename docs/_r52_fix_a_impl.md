# R52 Fix · Lane A（Pro 实现：纯判据 + 单测 + 变异验证）

> 路线 **甲（id 反查法）**。本 lane 独占两个文件：
> `wxgroup/app/src/main/java/com/lx/wxgroup/data/MediaDeleteLogic.kt`、
> `wxgroup/app/src/test/java/com/lx/wxgroup/data/MediaDeleteLogicTest.kt`。
> ⛔ 不碰 `feature/MediaTableGuard.kt`（lane B 独占）、不碰 adb / 装包 / gradle / 微信库 / 开关默认值。

## 决定性证据（已证非推断，⛔ 结论对着「能否看到大图」写）

01:03:59 **verity** 撤回图片时：
```
MTG delete table=ImgInfo2 where=id=? reason=shouldBlock-false gen=1
MTG stat gen=1 seen=43 matched=1 blocked=0 skipped=1 verdict=REAL_EVENT tokens=1 events=1
```
钩子命中白名单表、保护池有 md5 token、撤回窗口已开，但 `shouldBlock=false` 放行 ⇒
`ImgInfo2` 索引行被删 ⇒ 点开时 `HDIMG trace … a[0]=Long:0 … img=null`（**用户看不到大图**）。
根因：现有 `extractTokens` 只认 26/32 位 md5，而这条删除是 **`ImgInfo2 where=id=?`（数值主键）**。
放行点：`MediaDeleteLogic.kt:182` `tokens.isEmpty() ⇒ UNKNOWN` → `:253-255` →
`MediaTableGuard.kt:266` 打 `shouldBlock-false`。
⛔ `isRevocationWindow` 是 **true（通过）**，`id` 列**在** `IMG_SPEC.keyColumns`(`:78`)，别修错地方。
⛔ `seen/matched/blocked/skipped` 只作定位，不当结论；`MTG blocked` 也不当「修好了」的结论。

---

## T1 现状核对（真实签名 + 行号）

读法：Grep 定位 + `Read(offset,limit)` 分段（文件 273 行 / 427 行，均 >300 行禁止整读，已分段）。

### `MediaDeleteLogic.kt`（273 行，纯 Kotlin，零 Android 依赖）

| 元素 | 行号 | 真实形态 |
|---|---|---|
| `enum class DeleteClass` | `:37-49` | `BLOCKABLE` / `BULK_OR_UNSAFE` / `OTHER_TABLE` / `UNKNOWN` 四态互斥 |
| `object MediaDeleteLogic` | `:58` | 单例，全部纯函数挂这里 |
| `TARGET_TABLES` | `:64` | `setOf("imginfo2","videoinfo2","wxfileindex2")`，⛔ 不含 `voiceinfo` |
| `data class TableSpec(keyColumns, tokenLen)` | `:66` | |
| `IMG_SPEC` | `:76-82` | `keyColumns` `:77-80` **含 `"id"`（`:78`）**、`tokenLen=32`；⛔ `status` 等标量列故意不在 |
| `UNSAFE` Regex | `:102-104` | `\bselect\b|\blike\b|\bis\s+(not\s+)?null\b|\bin\b|!=|<>|<=|>=|<|>|\band\b|\bor\b|,|;` |
| `SINGLE_EQ` Regex | `:110-112` | `matchEntire`；group1=列名（可选引号/方括号），group2=右值 |
| `normalizeTable` | `:114-118` | trim → 去 `"`/`` ` ``/`[`/`]`/`'` → lowercase |
| `specFor` | `:120-126` | when 分派 4 张 spec |
| `collectHex` | `:133-136` | 按 `len` 抽 hex，边界断言 |
| `isTargetTable` | `:145-148` | |
| **`classifyDelete`** | **`:157-189`** | 签名 `:157-162` = `(table, whereClause, whereArgs: Array<out Any?>?, protectedTokens: Set<String> = emptySet()): DeleteClass`；`:181` `extractTokens(...)`、`:182` `if (tokens.isEmpty()) return UNKNOWN`、`:184-187` 完整模式保护集复检、`:188` `return BLOCKABLE` |
| **`extractTokens`** | **`:198-213`** | 签名 `:198-202` = `(table, whereClause, whereArgs: Array<out Any?>?): Set<String>`；双通道 `:205-211`（args + 内联），按表分派 26/32 位 |
| `looksLikeEpochMillis` | `:216` | |
| `isRevocationWindow` | `:226-233` | 半开区间，严格 `< ttlMs` |
| **`shouldBlock`** | **`:241-260`** | 签名 `:241-249` = `(table, whereClause, whereArgs, protectedTokens: Set<String>, nowMs, tokenIssuedMs, ttlMs): Boolean`（**7 个必填参数、无默认值**）；`:250` `isTargetTable`、`:251` `if (protectedTokens.isEmpty()) return false`、`:252` 窗口闸门、`:253-255` `classifyDelete(...)`、`:256-259` token 复检 |
| `verdict` | `:267-271` | 三态互斥 |

### `MediaDeleteLogicTest.kt`（427 行，JUnit4，`org.junit.Assert.*`）

| 元素 | 行号 | 真实形态 |
|---|---|---|
| import | `:3-6` | `assertEquals` / `assertFalse` / `assertTrue` / `Test` |
| `class MediaDeleteLogicTest` | `:18` | `:426` 闭合 |
| 常量 | `:20-30` | `MD5_A`/`MD5_B`/`MISS32`/`HEX26`/`HEX31`/`HEX33`/`HEX34`/`BAD`/`NUM`/`PROTECTED` |
| **`block(...)` helper** | **`:33-41`** | `MediaDeleteLogic.shouldBlock(table, w, args, protected, now, issued, ttl)` —— **7 个位置参数** |
| **`cls(...)` helper** | **`:43-48`** | `MediaDeleteLogic.classifyDelete(table, w, args, protected)` —— **4 个位置参数** |
| 46 例 `@Test` | `:52-425` | 表名白名单 / 四态 / UNSAFE / extractTokens / 窗口 / shouldBlock / verdict |

### 7 契约怎么接最不侵入

| # | 契约 | 接入点 | 为什么不侵入 |
|---|---|---|---|
| 1 | `normalizeId` 新增 | object 内新函数 | 不碰任何既有函数体 |
| 2 | `extractRowIds` 新增 | object 内新函数 | 复用 `isTargetTable`/`SINGLE_EQ`/`UNSAFE`/`NUMERIC_KEY_COLUMNS`，⛔ 不新增正则、不碰 `extractTokens` |
| 3 | `isNumericKeyDelete` 新增 | object 内新函数 | 同上，形状判定与取值分离 |
| 4 | `idMatches` 新增 | object 内新函数 | 纯集合运算 |
| 5 | `classifyDelete` 追加 `protectedRowIds` | `:161` 之后加带默认值末尾参；`:181-182` **之前**插 1 条分支 | 既有 `:163-180` 全部 UNSAFE 闸门逐字不动；「否则原逻辑逐字不变」 |
| 6 | `shouldBlock` 追加 `protectedRowIds` | `:248` 之后加带默认值末尾参；`:251` 改双空集闸门；`:253` 透传 | `:250`/`:252`/`:256-259` 位置与语义不变 |
| 7 | `NUMERIC_KEY_COLUMNS` 新增 | object 内 `private val` | 与 4 张 spec 并列，⛔ 不改 spec |

⭐ **带默认值参数是硬要求**：`cls`/`block` 两个 helper 及 46 例全是**位置参数**，末尾追加带默认值参数后
旧调用点零改动即可全绿；lane B 用**命名参数** `protectedRowIds = cachedRowIds` 调用 `shouldBlock`（命名参数
不受位置约束，追加在末尾即可）。⛔ id 通道与 token 通道**各自独立**，禁止把 id 集合塞进 `extractTokens`
（会破坏 `:24-27` 两套 token 长度分派这条已证事实）。

`[DONE] 骨架`
`[DONE] T1`

---

## T2 实现 7 个契约

改动文件：`MediaDeleteLogic.kt`（本 lane 独占）。⛔ 未碰 `MediaTableGuard.kt`、未接线、未读 `Kv`、
未改 `param.result`、未生成/拼接任何 SQL、未写微信库；**反查 DB 仍只留在 lane B 的 `invalidate()`**。

### 7 契约逐条落地（⛔ 签名一字不改，改动后行号）

| # | 契约 | 落点 | 实现要点 |
|---|---|---|---|
| 1 新增 | `normalizeId` | `:251-258` | `v?.toString()?.trim()` → 去前导 `+` → `all { it in '0'..'9' }` 才返回原串；⛔ **无 `toLong()`**，负数/小数/空/非数字/`null`/全角一律 `null`，前导 0 保留 |
| 2 新增 | `extractRowIds` | `:284-311` | **双通道**（`whereArgs` 归一 **与** `whereClause` 内联数值右值），白名单表 + 单列精确 `=` + 列 ∈ `NUMERIC_KEY_COLUMNS` 才输出；`stripQuotes`(`:233`) 只剥成对引号/方括号 |
| 3 新增 | `isNumericKeyDelete` | `:266-275` | 纯形状判定，复用 `isTargetTable` / `UNSAFE` / `SINGLE_EQ` / `NUMERIC_KEY_COLUMNS`；⛔ 签名无 `whereArgs`，不解析它 |
| 4 新增 | `idMatches` | `:314-317` | 交集判定，⛔ 任一为空 ⇒ false |
| 5 修改 | `classifyDelete` | 签名 `:157-163`（新参 `:162`）、新分支 `:183-189` | 在 `tokens.isEmpty() ⇒ UNKNOWN` **之前**插 `isNumericKeyDelete(...) && idMatches(extractRowIds(...), protectedRowIds) ⇒ BLOCKABLE`；否则**原逻辑逐字不变**（仅注释编号顺延） |
| 6 修改 | `shouldBlock` | 签名 `:347-357`（新参 `:355`）、双空集闸门 `:360`、透传 `:362`、id 出口 `:367-371` | `:252` 窗口闸门（现 `:361`）位置与语义不变；`:251`（现 `:360`）改双空集；透传 `protectedRowIds`；⛔ 并新增 id 出口（见下） |
| 7 新增 | `NUMERIC_KEY_COLUMNS` | `:230` | `setOf("id","msglocalid","msgsvrid","msgid")`，⛔ **刻意不含** `bigimgpath`/`thumbimgpath` 等路径列 |

⭐ **为什么 `shouldBlock` 必须多加一条 id 出口**（契约表未明写，但不加则修复无效）：
`classifyDelete` 返回 `BLOCKABLE` 后，原 `:256-259` 还会做 `extractTokens(...)` 二次复检；id 通道
（`id=?`）**不产出 26/32 位 md5** ⇒ tokens 恒为空 ⇒ 会被 `tokens.isEmpty() ⇒ false` 短路 ⇒
lane B 拿到恒 false ⇒ 真机依旧 `HDIMG … img=null`（**看不到大图**）。故在 token 复检前插入
`if (protectedRowIds.isNotEmpty() && idMatches(extractRowIds(...), protectedRowIds)) return true`。
⛔ 该支在 `protectedRowIds` 为空时恒 false ⇒ **token 通道逐字不变**，46 例回归全绿即证。

### 保守性红线（⛔ 一律放行，未放宽任何既有 UNSAFE 判据）

`whereClause == null` / 空串、`<` `>` `<=` `>=` `!=` `<>`、`LIKE`、`IS NULL`、
`IN (?,?)` / `IN (SELECT …)`、`AND` / `OR` 多条件、`;` 多语句、`whereArgs.size > 1`、
占位符数量与 args 不齐、非白名单表（含 `voiceinfo`）、非数值键列（`status` / `bigImgPath` /
`thumbImgPath`）⇒ `extractRowIds` 空集 / `isNumericKeyDelete` false ⇒ 落到原 token 通道或
`BULK_OR_UNSAFE` / `UNKNOWN`。⛔ 新增分支**位于 `classifyDelete:163-180` 全部闸门之后**，
没有任何绕过；只认单条 `col=?`（或内联 `col=数字`）。

### 验证链
- `_cache/r52_jvm.sh`：JDK 21 + `kotlin-compiler-embeddable 2.0.21` + `kotlin-stdlib 2.0.21`
  直编两份纯 Kotlin 源，`org.junit.runner.JUnitCore` 跑测试，⛔ 全程**不经 gradle** / 不装包 / 不碰 adb。
- 实现后立刻跑：**原版 46 例 `OK (46 tests)`**，证明带默认值末尾参数对旧调用点零行为变化。

`[DONE] T2`

---

## T3 单测 A/B/C 三组

改动文件：`MediaDeleteLogicTest.kt`（本 lane 独占）。追加 **37 例**（A 6 / B 18 / C 13），
合计 **83 tests 全绿**；⛔ R51 既有 **46 例零改动**（`block()` / `cls()` 两个位置参数 helper 原样保留）。

| 组 | 用例 | 覆盖（任务点名项加 ⭐） |
|---|---|---|
| A | `a1/a4/a6/a7/a8/a9` | `Long`/`Int`/`String`/`null`、trim、前导 `+`、非法值（负数/小数/非数字/空/全角）、`Long.MAX_VALUE` 及更长数字串不 `toLong()`、前导 0 保留 |
| B | `b1..b17` | ⭐`id=?` 各类型、⭐多参数、⭐`IN (…)`、⭐`LIKE`、⭐`whereClause==null`、⭐**内联数值右值** `id=875556` / `id='875556'`（`execSQL` 无 bindArgs）、`msglocalid`/`msgSvrId`/`msgid`、空 where、⭐非目标表/`voiceinfo`、⭐标量列 `status`、⭐路径列、⭐比较符/多条件/多语句、⭐引号列名 |
| C | `c0..c11` | `idMatches` 交集/空集、`classifyDelete` BLOCKABLE/UNKNOWN、`shouldBlock` 全链、⭐非撤回窗口、非目标表、`ttl=0`/时钟回拨、⭐⭐ **C7**、token 通道回归、前导 0 口径、重复/多余 id、`verdict` 回归 |

⭐ **C7**（`c7EmptyTokensButNonEmptyRowIdsStillBlocks`）用**全命名参数**直接调用
`MediaDeleteLogic.shouldBlock(table=…, protectedTokens=emptySet(), …, protectedRowIds = ROW_IDS)`，
模拟 lane B 的调用形态，断言 `protectedTokens` 空但 `protectedRowIds` 非空时仍 `true`；
并同时断言 `classifyDelete(..., protectedRowIds = ROW_IDS) == BLOCKABLE`。
它是专抓「忘记把 `shouldBlock:251` 改成双空集闸门」的用例（实测另有多条 id 通道用例一并变红）。

⛔ **两处按函数签名契约实现、与设计文档表格措辞的差异（如实标注，非擅自改契约）**：
1. 设计文档 B7 表格写「`isNumericKeyDelete=false`」，但硬契约签名
   `isNumericKeyDelete(table: String?, whereClause: String?): Boolean` **无 `whereArgs` 入参**，
   无法感知「`id=?` 带 2 个 args」这种批量。本实现按签名做纯形状判定（`id=?` 形状为 true）；
   多参数保守性由 `extractRowIds` 的 `size>1 ⇒ ∅` **与** `classifyDelete:170 ⇒ BULK_OR_UNSAFE`
   **双保险**承担。`b7` 断言 `extractRowIds=∅` + `BULK_OR_UNSAFE` + `shouldBlock=false`，
   结论仍是任务要求的「一律放行」。
2. 设计文档 M5 的 RED 用例 B15/C8 用的是 **md5 右值**，**不足以杀死**「把路径列扩进
   `NUMERIC_KEY_COLUMNS`」这个变异（md5 非数字，`extractRowIds` 仍为空）。故补
   `b15bPathColumnWithNumericRightValueStillRejected`：路径列 + **数值右值** + `protectedRowIds`
   命中时仍须 `∅ / UNKNOWN / false`。实测 M5 被 `b15`、`b15b` 两条 RED 杀死（T4）。
3. 设计文档 T3 订正后新增 **C12 / M2b**（契约纪律「id 出口」条 + C 组 C12，与 C7 并列的
   「第二个 shouldBlock 漏改面」：
   只改 `classifyDelete` 不补 `shouldBlock` 的 id 出口）。C12 语义**已由** `c1` / `c7` / `c1b`
   的 `assertTrue(blockId(...))` 覆盖（删出口 ⇒ 三条 RED），故**未新增同名用例**、83 例总数不变；
   本轮按订正**补 M2b 子变异并实测 RED**（设计侧把 M2 拆成 M2a/M2b、**仍算一组**；
   `Failures=3` 恰好这三条），见 T4（双口径：`7/7` 组 / `8/8` 变异体）。

`[DONE] T3`

---

## T4 变异验证 M1~M7（方式①：**7 组 / 8 个变异体全部实测 RED**，无「待主会话执行」）

> ⭐ **双口径统一（与设计侧 `docs/_r52_fix_design.md` 修订后 T3 一致）**：
> 设计侧保持 **M1~M7 七组编号不变**，把 **M2 拆成两个子变异** ——
> **M2a**（`:251` 双空集闸门漏改，C7 抓）/ **M2b**（漏加 `shouldBlock` 的 id 出口，C12+C1 抓），
> 仍算**一组**。本脚本的键名 `M2` 即设计侧 **M2a**，`M2b` 即设计侧 **M2b**。
> ⇒ 汇总同时给两个数：**`7/7` 组全 RED**（设计侧口径）、**`8/8` 变异体全 RED**（实现侧口径），
> 两者不矛盾，`python _cache/r52_mutate.py report` 会自动双口径输出。
> M2b 语义（`docs/_r52_fix_design.md` T3「契约纪律 id 出口」条 + C12，行号见 T7）：
> 只改 `classifyDelete` 不补 `shouldBlock` 的 id 出口 ⇒ 数值主键 `extractTokens` 恒空集、
> `:257 return false` 把 `BLOCKABLE` 短路 ⇒
> 修复完全无效，且 `classifyDelete` 单测全绿、极难发现。本轮据订正补齐并**实测 RED**。

### 执行方式（如实写明走了哪条）
走 **方式①**：⛔ 不经 gradle。用 JDK 21 + `~/.gradle/caches` 里的
`kotlin-compiler-embeddable 2.0.21` / `kotlin-stdlib 2.0.21` / `junit 4.13.2` /
`hamcrest-core 1.3` / `kotlinx-coroutines-core-jvm 1.6.4` / `trove4j`，
`java -cp … org.jetbrains.kotlin.cli.jvm.K2JVMCompiler` **直编真实的两份 Kotlin 源文件**，
再用 `org.junit.runner.JUnitCore` 跑**同一份 83 例用例表**。

- 编译/运行链：`_cache/r52_jvm.sh`
- 变异注入 + 恢复 + hash 核验：`_cache/r52_mutate.py`
- 执行器（逐组 `apply → 编译+JUnit → restore`）：`_cache/r52_run_mutants.sh`
- 证据日志：`_cache/r52_mut_M1.log … M7.log` + `_cache/r52_mut_M2b.log`（8 份）
- ⛔ **方式②备用交付物也一并产出**：`_cache/r52_mut_M1.patch … M7.patch` + `r52_mut_M2b.patch`
  （8 个变异补丁）+ 上述 3 个脚本，主会话编译后可直接 `bash _cache/r52_run_mutants.sh` 复跑。

⛔ **本轮 7 组 / 8 个变异体全部实测**，无虚报；每个变异体 `restore` 后打印 hash，末尾 `verify` 显示
`源码 == 变异前备份: True | sha256=b1893060affd3b34`，且恢复后全量回归 **`OK (83 tests)`**。

### 8 个变异体（设计侧 7 组：M2 含 M2/M2b 两个子变异；⛔ 每个写「为什么有语义」，无等价变异）

| # | 注入点 / 变异内容 | **为什么有语义**（非等价改写） | **实测结果** |
|---|---|---|---|
| **M1** | `classifyDelete` 新分支 `idMatches` 取反 `!idMatches` | 语义反转：本该拦的 `id=?` 放行、不该拦的反被拦，直接改变「要不要拦」唯一出口 | **RED** `Tests run=83 Failures=10`：`c1, c1b, c2, c3, c7, c9, c10` + R51 `protectedMissIsUnknown, nonExtractableTokenIsUnknown, missingArgForPlaceholderIsUnknown` |
| **M2** | `shouldBlock:251` 改回 `protectedTokens.isEmpty()`（漏改双空集闸门） | 语义漏改：id 通道被空 `protectedTokens` **静默短路** ⇒「单测看似绿、真机恒放行」的假验证，本次最易犯的错 | **RED** `Failures=3`：`c7`（专抓）, `c1`, `c1b` |
| **M3** | `normalizeId` 改 `v as? String`（只认 String、删 trim） | 语义丢失：真机 `Object[]` 里是 `Long`（决定性证据），全抽不出 id ⇒ **修复等于没修** | **RED** `Failures=9`：`a1, a4, a8, b1, b6, b17, c1, c7, c10` |
| **M4** | `extractRowIds` 挪走 `UNSAFE` + `size>1` 两道保守闸门 | 语义放宽：批量清理 / 多语句 / 多条件被拦 ⇒ **微信 GC 失效**（`MediaDeleteLogic.kt:14-22` 红线） | **RED** `Failures=2`：`b16`, `b16b` |
| **M5** | `NUMERIC_KEY_COLUMNS` 扩进 `bigimgpath`/`thumbimgpath` | 语义串味：md5 路径列混入数值通道，破坏 `:24-27` 两套 token 长度口径 | **RED** `Failures=2`：`b15`, `b15b` |
| **M6** | `shouldBlock:252` 窗口闸门短路（旁路 `isRevocationWindow`） | 语义越权：窗口外的 `id=?` 删除也被拦 ⇒ 撤后 10 分钟外微信正常 GC 被堵 | **RED** `Failures=3`：`c4`, `c6`, R51 `shouldBlockRejectsOutsideWindow` |
| **M7** | `extractRowIds` 删掉通道 2（`whereClause` 内联右值） | 语义缺口：`execSQL(String sql)` 无 bindArgs 时 `args=null`，`id=875556` 抽不出 ⇒ 漏一条真实通道 | **RED** `Failures=2`：`b5`, `c1b` |
| **M2b** ⭐ | 删掉 `shouldBlock` 的 id 出口（`classifyDelete==BLOCKABLE` 后仍走 `:256-259` token 复检） | 语义漏改（**与 M2 并列的第二个 shouldBlock 漏改面**）：数值主键 `extractTokens` 恒空集 ⇒ `:257 return false` 把 `BLOCKABLE` 短路；`classifyDelete` 单测仍全绿、真机恒放行，极难发现（C12 专抓） | **RED** `Failures=3`：`c7`, `c1`, `c1b` |

**汇总（`python _cache/r52_mutate.py report` 双口径实测输出）**：
- `[report] 变异体至少 1 条 RED: 8/8`
- `[report] 设计侧 7 组口径（M2 组含 M2/M2b 两个子变异）: 7/7`
⛔ 无一组 / 无一变异体全绿、无等价变异。
每组 RED 证据（JUnit 失败用例名 + 断言堆栈）见 `_cache/r52_mut_M*.log`。
M1/M2/M3/M4 四组分别覆盖「出口反转 / 闸门漏改 / 类型归一 / 保守边界」四个互不重叠语义面；
**M2 组的第二个子变异 M2b 补齐第五个语义面「shouldBlock 的 id 出口」**（仍归在 M2 组、
不新增第 8 组编号，与设计侧修订后 T3 一致）。
⭐ 设计侧 C12 的语义**已由** `c1` / `c7` / `c1b` 的 `assertTrue(blockId(...))` 实测覆盖
（M2b 即为其变异实证，`Failures=3` 恰好是这三条），故未再新增同名用例、83 例总数不变。

⛔ **如实说明两处细节（不虚报、不掩盖）**：
- M2 除专抓用例 `c7` 外，`c1` / `c1b` 也变红（它们同样用空 `protectedTokens` + 非空
  `protectedRowIds`）。这不是用例缺口，反而说明双空集闸门的影响面被多条用例交叉覆盖。
- M4 的 `b7`（`id=?` 带 2 个 args）**未**变红：删掉 `size>1` 闸门后，`extractRowIds` 还有
  「占位符数量与 args 数量对齐」这道闸门兜底（多层保守，属预期）。M4 的语义面
  （比较符 / 多条件 / 多语句 / `OR` 批量）由 `b16`、`b16b` 杀死，仍 ≥1 条 RED。

### 文件复位核验（⛔ 硬要求）
- 每组 `apply` 前断言「当前源码 == 备份」；`restore` 后打印 `已恢复，hash 一致 sha256=b1893060affd3b34`；
- 末尾 `python _cache/r52_mutate.py verify` ⇒ **`True`**；
- 末尾 `bash _cache/r52_jvm.sh test` ⇒ **`OK (83 tests)`**；
- ⛔ 变异只改 `MediaDeleteLogic.kt` 主源，**测试文件全程未动**；`MediaTableGuard.kt` 全程未碰
  （`git status --short` 复核仍为 `??`）。

`[DONE] T4`

---

## T5 三档边界表（进入 / 返回 / 复位逐条）

> ⛔ 本 lane **零接线**：以下描述「甲路线在 lane B 完成 `invalidate()` 反查 + `cachedRowIds`
> 发布后」的**预期**状态，**全部 `[需真机]` 才能定**；本轮只改判据，纯函数类落地即零行为变化，
> 现网微信行为完全不变。好友一律 **verity** / **测试群聊** / **文件传输助手**。
> 主判据始终 = **撤回后隔几分钟点开被撤回的图，能否看到完整大图**；
> ⛔ 不拿 `seen/matched/blocked/skipped/row=0/verdict` 当结论。
> 依据 `docs/_r52_fix_design.md`「## T4 三档边界表」章节（01:45 版起于 `:362`）、
> `_r51_media_table_guard_impl.md:220-247`。⛔ 契约以「## T3 判据纯函数化 + 单测契约」章节的
> **7 条函数签名表**为准（⛔ 设计文档行号两次顺延，一律以章节标题定位，详见 T7）；
> 旧 `:215-223` 已是甲-7 风险分析，不作实现依据。

### ① 进入（冷启 install / 首次撤回 / kvset 开关 / 保护池 token 与 rowIds 登记）

| 触发 | 发生什么 | 用户可见 | 状态残留 |
|---|---|---|---|
| **冷启 `install()`** | `MediaTableGuard.install`：反射 `VoiceFileGuard.protectedMedia`、挂 `delete`/`execSQL` hook、读 `antirevoke.media_delete` 开关、打 `MTG installed` | 无 | hook 全挂；`cachedTokens` / ⭐`cachedRowIds` **空**、`gen=0`、`lastIssuedMs=0`、账本 4 计数全 0 |
| **首次撤回 `record() → invalidate()`** | **verity** 在「**测试群聊**」撤图 ⇒ `RevokeRescue.record()` 图片分支 `protectMedia(orig.imgPath)` 登记 32 位 md5 到保护池；⭐ lane B `invalidate()` 按 md5 **只读反查** `ImgInfo2` 的 `id`、发布 `cachedRowIds`（与 `normalizeId` 同口径归一）；随后微信 `delete("ImgInfo2","id=?",…)` 到达 ⇒ `idMatches` 命中 ⇒ 跳过删除 | 撤回瞬间无 UI 变化 | 池 token、`cachedTokens`、`cachedRowIds`、`gen`/`lastIssuedMs`、账本；`message` 行 + `ImgInfo2` 行 + `image2` 文件都在；⛔ 图片侧**无备份文件** |
| **`kvset antirevoke.media_delete` 开关** | 开关写模块自有库；`[推断]` `refresh()` 只在 `install()`/`invalidate()` 调，单独 kvset **不改内存 `enabled`**，须等下一次撤回或冷启 | 无 | 开关落库，内存 `enabled` 可能仍旧值 |
| **保护池 token 与 rowIds 登记** | `protectedMedia[md5] = now+600000`、`hasMedia=true`；⭐ `cachedRowIds` 由反查结果按 `gen` 重建（进程内 `@Volatile` 集合，与 `cachedTokens` 同构） | 无 | 池 1 条（10min TTL）、`hasMedia=true`、`cachedRowIds` 1 条 |

### ② 返回（撤后几分钟点开 / 切页回来 / 下拉刷新）

⛔ **TTL 时间维度** 与 **冷启进程维度** 各自单列，⛔ 不与「切页回来」合并、⛔ 两维度互不合并。

| 档 | 触发 | 发生什么 | 用户可见 | 状态残留 |
|---|---|---|---|---|
| **② 纯 UI 返回** | 撤后几分钟点开大图 / 切页回来 / 下拉刷新（**无新撤回 UPDATE**） | 只读 `message` + `ImgInfo2`；`cachedRowIds` 不变、`isRevocationWindow` 仍 true；若此刻微信重试 `delete("ImgInfo2","id=?",…)` ⇒ `idMatches` 命中 ⇒ 跳过删除 | ⭐ **主判据：点开能否看到完整大图** | `auditImage` 游标每次 `close()`；账本不清零 |
| **②-a TTL（时间维度，单列）** | 撤回后满 **10 分钟**（保护池 `PROTECT_MEDIA_MS=600_000`）/ **30 分钟**（imgAnchor `IMG_ANCHOR_TTL_MS`） | 保护池到期 ⇒ `anyAlive` 复位 `hasMedia=false`、`WINDOW_MS` 到期 ⇒ `isRevocationWindow=false` ⇒ `shouldBlock` 恒 false ⇒ **不再拦**；imgAnchor 另有 256 上限淘汰，**不同载体、不同时长** | TTL 后若微信重试删除 ⇒ 行被删、**图打不开** | ⛔ 池从「保护态」变「裸奔态」；**账本仍在、hook 仍在、开关仍开、`cachedRowIds` 仍在**（只是窗口判 false）；⛔ 不与冷启合并 |
| **②-b 冷启（进程维度，单列）** | 微信进程被重建 | 重新 `install()`、`MTG installed` 重打；`cachedTokens` / ⭐`cachedRowIds` / `gen` / `lastIssuedMs` / **账本 4 计数 + `events`(≤64) + `logAt`(≤64) 全部整体清零**；`verdict` 回 `NO_EVENT` | 冷启后能否仍打开 **`[需真机]`**（池空，若冷启后微信重新发起删除无人拦） | ⛔ **与 TTL 分列**：TTL 只让池过期、账本与 hook 都在；冷启是**进程内集合整体清零**；持久化态（`rescued` 台账、`image2` 文件、语音 `voice_bak/`）保留 |

### ③ 复位逐条（⛔ 10 条齐全，每条写清除条件与残留，⛔ 不许写「同返回」）

| # | 复位动作 | 清除条件（何时发生） | 清掉什么 / 残留什么 |
|---|---|---|---|
| 1 | **关 `antirevoke.media_delete`** | `kvset false`；`[推断]` 因 `refresh()` 只在 `install()`/`invalidate()` 调，须等**下一次 `record()→invalidate()`** 或冷启才把 `enabled=false` 刷进内存 | 清：`before` 首行 `if(!enabled) return` ⇒ **账本停止增长、不再拦**；残留：hook **不卸载**只空转、`protectedMedia`/`cachedTokens`/`cachedRowIds` **不清空**（旧 token 靠 TTL 自然过期）；⛔ 不残留任何对微信库的写入 |
| 2 | **关 `antirevoke.msg`** | `kvset false` 后**下一次撤回**即生效（`AntiRevoke` 不再拦 UPDATE） | 清：`RevokeRescue.record()` 不触发 ⇒ `protectMedia` 与 `invalidate()` 都不跑 ⇒ 池不新增、`cachedTokens`/`cachedRowIds` 不重建、账本不增长、`message` 行被微信改写、**原文消失**；残留：`VoiceFileGuard.on()` 3 秒 TTL（关后最多 3 秒内仍拦 `File.delete`）、既有 `rescued` 台账仍在 |
| 3 | **保护池 10min TTL** | 自 `protectMedia` 起满 `PROTECT_MEDIA_MS = 600_000ms` | 清：`anyAlive` 复位 `hasMedia=false`、`WINDOW_MS` 到期 ⇒ `isRevocationWindow=false` ⇒ 不再拦；⛔ `protectedMedia` 条目**不主动删**，仅 `size>256` 时淘汰；残留：池容器 / 账本 / hook / 开关 / `cachedRowIds` 全在（**时间维度**复位，⛔ 不与冷启合并） |
| 4 | **imgAnchor 30min TTL** | `IMG_ANCHOR_TTL_MS` 到期 / 256 上限淘汰 / 进程死 | 清：消息到达锚点失效 ⇒ `auditImage` 取不到 `svrId`、退化为只按 `msglocalid` 查；⛔ **不清空整个集合、冷启才丢**；残留：与保护池 **不同载体、不同时长**，三条独立清除 |
| 5 | **账本冷启清零** | 微信进程死亡重建（**唯一条件**） | 清：`seen`/`matched`/`blocked`/`skipped` 4 个 `AtomicLong` + `events`(≤64) + `logAt`(≤64) + `cachedTokens` + ⭐`cachedRowIds` + `gen` + `lastIssuedMs` **进程内整体清零**；`verdict` 回 `NO_EVENT`；残留：持久化态（`rescued` 台账、`image2` 文件、语音 `voice_bak/`） |
| 6 | **hook 随进程死才卸** | `install()` **无 unhook**；关开关 / TTL 过期 / 清缓存**都不卸载** | 清：**仅进程死亡**时 `delete`/`execSQL` 上的 callback 才真正消失；残留：关开关后 callback 仍常驻但空转（`enabled=false` 直接 return） |
| 7 | **微信重试删除落在窗口外或冷启池空** | 撤回 600s **之后**的重试，或冷启后 `protectedMedia`/`cachedRowIds` 为空时的重试 | 清：无人拦 ⇒ `ImgInfo2` 行被真删、`param.result` 不置；池空时 `:251` 双空集闸门直接 return，甚至不进 `shouldBlock`（`skipped` 也不增）；残留：⛔ 图片侧**无 backup/restore** ⇒ **漏拦即永久丢失** |
| 8 | **清缓存** | `op=logclear` 或微信「存储空间→清缓存」删 `image2` | 清：`op=logclear` **只清 LogX 环形缓冲**，⛔ 不动池 / 台账 / `cachedRowIds` / 文件；微信清缓存时开关**开着** ⇒ `File.delete` 被 `VFG` 拦（存储只增不减），**关着** ⇒ 真删文件；残留：DB 行不受影响 |
| 9 | ⭐ **LogX 满 5000 行整文件清空重来** | 落盘日志行数达 `MAX_LINES = 5000` 的那一刻 | 清：**整个文件一次性清空重写**，只留一行 **`log rotated`** 标记 + 之后的新日志；⛔ **非逐行 FIFO**（不是删最老一行）：撤回到验收之间的 `RRV record` / `VFG protectMedia` / `MTG blocked` / `HDIMG` 证据行可能**整批消失**；残留：池 / 账本 / `cachedRowIds` / hook / 文件**一概不动**，行数计数器归零后重新累加 ⇒ 直接威胁取证链，验收前必须 `op=logclear` 并控制日志量 |
| 10 | ⛔ **已被删的 DB 行 / 文件不可回滚** | `beforeHookedMethod` 未能阻止删除（判据放行 / `execSQL` void 写法 `[需真机]` / native `unlink`） | 清：`ImgInfo2` 行一旦被删、`image2` 文件一旦被删，**无副本可还原**；残留：`message.imgPath` 可能仍在、`th_` 缩略图可能被 `VFG` 保住，但 `HDIMG … img=null` ⇒ 组件拿不到路径 ⇒ **不可逆终态，不属于任何「返回」路径，验收必须请 verity 重发新图** |

`[DONE] T5`

---

## T6 回滚路径 + 装包前复核要点

### 回滚路径（⛔ 只 revert 本 lane 两个文件）
- `wxgroup/app/src/main/java/com/lx/wxgroup/data/MediaDeleteLogic.kt`
- `wxgroup/app/src/test/java/com/lx/wxgroup/data/MediaDeleteLogicTest.kt`
⛔ **不碰** `feature/MediaTableGuard.kt`（lane B 独占）、不改开关默认值、不碰 adb / 装包。

⛔ **注意索引状态**：本轮开始时这两个文件在 git 里是 **untracked（`??`）**（`git status --short`
实见），不在索引中 ⇒ `git checkout -- <file>` / `git revert` 对未跟踪文件**无效**；且若先
`git add` + commit 再 `git revert`，会把这两个文件**直接删掉**，R51 原判据内容并不在 git 历史里。

**因此给出一条可执行的精确回滚补丁**（已实测 `git apply --check` 通过）：
```
git apply _cache/r52_revert.patch      # 修复后 → R51 原判据（只改 MediaDeleteLogic.kt）
```
- `_cache/r52_revert.patch`：`MediaDeleteLogic.kt` 8 处改动逐条反向（7 个新增函数整段删除、
  2 处带默认值参数删除、`classifyDelete` 新分支删除、`shouldBlock` 双空集闸门改回单空集、
  id 出口删除、透传复原、KDoc 与注释编号复原）；改动是**纯增量**、无交叉依赖。
- `_cache/r52_r51_MediaDeleteLogic.kt`：由当前源码反向重建的 R51 原版，供核对。
  自检 `looksLikeEpochMillis@:216 / isRevocationWindow@:226 / shouldBlock@:241 / verdict@:267`
  与 T1 基线逐行吻合（重建 272 行，与 T1 的 273 差异仅为文件末尾换行计数口径）。
- 生成脚本：`python _cache/r52_revert.py`。
- ⛔ 回滚 `MediaDeleteLogicTest.kt` 时只需删掉第 7 节追加的 37 例（A/B/C 三组）与
  `ROW_ID`/`ROW_IDS`/`clsId`/`blockId` 四个新增成员；R51 既有 46 例与两个 helper 原样保留。
- `_cache/r52_orig_MediaDeleteLogic.kt` 是**变异验证前**的修复后干净版备份
  （sha256 `b1893060affd3b34`），⛔ **它不是 R51 原版**，只用于确认「变异验证未污染源码」。

### 默认值参数如何保证旧调用点零行为变化（⛔ 不是兼容 hack，是硬要求）
- `classifyDelete` 追加 `protectedRowIds: Set<String> = emptySet()` **排在 `protectedTokens` 之后**；
  `shouldBlock` 追加 `protectedRowIds: Set<String> = emptySet()` **排在 `ttlMs` 之后**。
  两者都是**末尾带默认值参数**，调用方不传时取 `emptySet()`。
- 既有 `cls()` / `block()` 两个 helper 用**位置参数**传前 4 / 前 7 个参数 ⇒ 完全不受影响；
  R51 既有 **46 例零改动**，实测追加参数后立刻 `OK (46 tests)`，最终 `OK (83 tests)`。
- `protectedRowIds = emptySet()` 时：`idMatches(..., emptySet())` 恒 false ⇒ `classifyDelete`
  新分支不命中 ⇒ 落到原 `tokens.isEmpty() ⇒ UNKNOWN`；`shouldBlock` 双空集闸门在
  `protectedTokens` 非空时仍不短路 ⇒ **token 通道逐字不变**。
- lane B 用**命名参数** `protectedRowIds = cachedRowIds` 调用 `shouldBlock`（C7 已实测演示），
  命名参数不受位置约束，追加在末尾即可，⛔ 无需改任何旧调用点签名。

### 装包前复核要点
1. `MediaDeleteLogic.kt` 零 `android.*` / `Kv` / `LogX` / `XposedHelpers` / DB / SQL 拼接；
   反查 DB 只在 lane B 的 `invalidate()`，纯函数只消费 `cachedRowIds` 快照。
2. ⭐ `cachedRowIds` 的归一口径必须与 `normalizeId` **完全一致**（否则 `idMatches` 恒 false，
   又是一次静默失效）——这是 lane B 侧最需要复核的接口契约。
3. `NUMERIC_KEY_COLUMNS` 不得含路径列；`id` / `msglocalid` / `msgsvrid` / `msgid` 四列齐全。
4. 主判据是「**verity** 撤回**新图**后隔 3~5 分钟点开能否看到**完整大图**」，⛔ 不拿
   `MTG blocked` / `seen` / `blocked` / `verdict` 日志当结论；⛔ 已被删的老图不可回滚。
5. 验收前 `op=logclear`，避免 T5 #9「LogX 满 5000 行整文件清空」把取证链整批冲掉。

### 可能拖慢热路径的点（应无）
- 新增 4 个纯函数只在 **DELETE 回调**（`beforeHookedMethod`）里被调，且 `classifyDelete`
  先做 `isTargetTable` + `whereClause` 形状闸门，绝大多数无关表 / 批量形态在
  `:164-170` 就返回，⛔ 进不到 `extractRowIds` 的 `SINGLE_EQ.matchEntire` / 字符串归一。
- `normalizeId` / `idMatches` 是 O(串长) / O(集合大小) 的纯内存运算，`NUMERIC_KEY_COLUMNS`
  是不可变 `Set` 常量；⛔ 无 IO、无反射、无 DB、无锁、无对象分配爆炸。
- 窗口闸门 `:361`、双空集闸门 `:360` 都在最前，id 通道未启用（`protectedRowIds` 空）时
  几乎零额外开销。⛔ **结论：热路径无新增耗时点**。

`[DONE] T6`

---

## T7 续派复核（team-lead 01:26 更正 + 三档红线逐条对照）

### 契约基准更正（⛔ 设计文档行号会漂移，一律以**章节标题**定位）
`docs/_r52_fix_design.md` 于 01:26 追加「因果链闭合后的增量复核」章节、01:45 又追加
`T3-rev` 订正，全文已到 **463 行**，行号**两次顺延**。⛔ 故本报告对设计文档的引用一律以
**章节标题 `## T3 判据纯函数化 + 单测契约` + 条目名**（7 条签名表 / B7 / B15b / C12 / M2 / M5）
为准，下列行号仅为 **01:45 快照**、不作硬绑定：
- `## T3` 标题 `:258`（01:26 版 :256 → 01:45 版 :258）；7 条签名契约表 `:266-272`
  （`| 6 修改 ⭐` `:271` 已把「id 出口」写成硬要求）；契约纪律「id 出口」条 `:279`；
- C12 `:337`、M2（拆 M2a/M2b、仍算一组）`:348`、验收口径 `7/7` `:355`、
  `[DONE] T3-rev` `:360`、`## T4` `:362`。
旧 `:215-223` 现已是甲-7 风险分析，⛔ **不作实现依据**。
⭐ 两次订正**只补说明 / 拆 M2 / 换 RED 用例，7 条函数签名内容逐字未变**；
本 lane 实现逐条对照该 7 条签名，**一字不改**；
team-lead 点名易漏四点全部落实：
1. `classifyDelete` 新分支插在 `tokens.isEmpty() ⇒ UNKNOWN` **之前**（`:183-189` vs 原 `:181`），
   否则原逻辑逐字不变；
2. `extractRowIds` **双通道**：`:303` `whereArgs` 归一 **与** `:308` `whereClause` 内联右值；
   `IN` / `LIKE` / 比较符 / 多条件一律空集（作为**测试负例**，不是要支持它们）；
3. `normalizeId`（`:251-258`）只做字符串归一、⛔ 不 `toLong()`；前导 0 保留 ⇒
   C9 `0875556` 为 false；`NUMERIC_KEY_COLUMNS`（`:230`）刻意不含路径列；
4. **C2 / C3 期望 `UNKNOWN`**（`c2` / `c3` 实测断言 `UNKNOWN` + `shouldBlock=false`）；
   `idMatches` 任一为空 false；**C7** 专抓 `:251` 双空集闸门漏改，M2 实测让 `c7` RED。

### ⛔ `tools/_junit.sh` 的 46/83 陷阱（本轮实测，主会话必读）
`bash tools/_junit.sh com.lx.wxgroup.data.MediaDeleteLogicTest` 本轮输出 **`OK (46 tests)`**，
⛔ **这不是本 lane 源码的验证结果**：该脚本（`:36-38`）只读 gradle 预编译产物
`app/build/tmp/kotlin-classes/debugUnitTest`，而其中 `MediaDeleteLogicTest.class` 的 mtime 是
**11-10 23:28**（R51 旧 46 例），主会话编译闸门尚未跑 ⇒ 新增 37 例根本不在 class 里。
- ⛔ **不能**拿这个 46 例结果当「单测通过」或「零代码改动」的判据
  （`tools/_junit.sh:16-17` 自身也警告过「默认值当全过」是假判据）。
- ✅ 本 lane 用 `_cache/r52_jvm.sh` **直编当前两份真实 .kt 源码**（不经 gradle、不依赖预编译 class），
  本轮复验输出 **`OK (83 tests)`**；源码 hash 核验 `b1893060affd3b34`（与变异前一致，未被污染）。
- 主会话跑完编译闸门后，`tools/_junit.sh` 应变为 `OK (83 tests)`；变异验证**必须重编译**，
  故仍走直编链 `_cache/r52_jvm.sh`（`_junit.sh` 只读已有 class，无法验证 mutant）。
- 另：脚本位于**项目根** `tools/_junit.sh`，且脚本自身 `:19-20` 已 `cd wxgroup`；
  正确调用是**从项目根** `bash tools/_junit.sh`（`cd wxgroup && bash tools/_junit.sh` 找不到脚本）。

### 三档红线逐条对照（team-lead 续派要求）
| 档 | 红线 | 落实情况 |
|---|---|---|
| **进入** | 只认白名单表 + 撤回窗口 + `id=?` 形态 | `isTargetTable` → `UNSAFE` → `SINGLE_EQ` → `NUMERIC_KEY_COLUMNS` 四道闸门（`:163-180` + `:288-296`）；`isRevocationWindow`（`:361`）位置与语义不变 |
| **进入** | `whereClause==null` / 非数值列 / `LIKE` / `IN` 不误拦 | 一律 `emptySet()` / `UNKNOWN` / `BULK_OR_UNSAFE`；`b8/b9/b10/b14/b15/b16` 负例全绿；⛔ 未放宽任何既有 UNSAFE 判据 |
| **进入** | `protectedRowIds` 为空时旧 md5 通道照常工作 | `idMatches(…, emptySet())` 恒 false ⇒ 新分支不命中 ⇒ 落原 `extractTokens`；`c8` + R51 46 例回归全绿 |
| **返回** | `param.result` 赋值只在 `MediaTableGuard`（非本 lane 文件） | 本 lane 两个文件**零 `param.result`**；纯函数只返回 `Boolean` / `DeleteClass` / `Set` |
| **返回** | 纯函数只返回判据，不查库 / 不写库 / 不吞异常 | 零 `android.*` / `Kv` / `LogX` / DB / SQL 拼接；`v?.toString()?.trim() ?: return null`、`?: continue` 是**显式不确定即放行**，非 catch 吞错；非法值返回 `null` / `emptySet()` |
| **返回** | `rows=0` 由 lane B 保守放行 | lane B 已确认：反查 `rows=0` / 异常 ⇒ `cachedRowIds=emptySet()` + 留 MISS 日志；对应本 lane `idMatches(候选, ∅)` 恒 false ⇒ 放行 |
| **复位** | 窗口过期 / `gen` 自增 / `resetLedger` 归零后不得残留缓存 id 命中 | ⭐ 关键：纯函数**不持有任何状态**，`protectedRowIds` 每次由调用方（lane B 的 `rowIds` 一次性局部快照）传入 ⇒ 上游 TTL 过期 / `gen` 重建 / 账本清零后传入的是新快照或空集，**不存在模块内残留缓存 id**；`idMatches(候选, ∅)` 恒 false |
| **复位** | ⛔ LogX 满 5000 行是 `writeText` **整文件清空重来**（非逐行 FIFO） | T5 #9 已单列：只留一行 `log rotated` 标记，撤回到验收间证据行可能整批消失 ⇒ 验收前必须 `op=logclear` 并控制日志量，**日志「没有」≠「没发生」** |
| **复位** | ⛔ 已被删的 `ImgInfo2` 行不可回滚；修复后必须请 **verity** 发新图验收 | T5 #10 已单列：不可逆终态，`HDIMG … img=null`；⛔ 绝不拿老图验收 |

### 本轮复验结论（源码早已落盘，非本轮重写）
team-lead 续派消息里「报告 124 行全待填 / 源码 mtime 11-10 23:28 / Grep 零新增函数」是
**父 turn 打断前的旧快照**；本轮开工前实测：源码 `MediaDeleteLogic.kt` 387 行（mtime 11-11 01:35）、
测试 765 行 / 83 例（01:32）、报告（**本轮开工前**）321 行 / 8 个 `[DONE]` / 无待填、
**r52-fix-b** 已读 `normalizeId:251` / `shouldBlock:355` 确认签名一致并据此完成接线。
⛔ 故本轮**未改写最终源码与测试文件的内容**（避免与 lane B 并发写冲突；变异注入均为临时写 +
`restore` 自动还原、末尾 hash 核验一致），只做：契约逐条复核 → 直编复验 83 例 →
据设计侧订正**补 M2b 子变异并重跑（口径统一为 7/7 组、8/8 变异体全 RED）** → 本报告补齐 + 契约行号更正。
⚠️ 注意：重跑变异时的 `restore` 会**重写文件**（内容逐字不变），故 `MediaDeleteLogic.kt` 的
mtime 由 01:35 更新为 01:47；⛔ **判断「有没有被改」必须看 hash（`b1893060affd3b34`）而不是 mtime**，
本轮 `verify` 已确认与变异前逐字一致。`MediaDeleteLogicTest.kt` 全程未动。

`[DONE] T7`

---

`[DONE] ALL`
