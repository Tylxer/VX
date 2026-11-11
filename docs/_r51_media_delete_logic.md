# R51 · 媒体表删除拦截判据（纯函数 / JVM 单测 / 变异验证）

> **Lane**：判据设计（独占 Pro lane）。阶段 0 真机决定性取证由主会话并行进行，A/B/C 路线尚未拍板；本 lane 只做两条路线共用、不依赖真机结论的「零 Android 依赖纯判据层」。
> ⛔ 不实现任何 Hook 接线、不碰现有生产类、不改任何开关语义、不跑 gradle、不碰 adb / 真机 / 装包 / kvset。
> 产物仅 3 个文件：`MediaDeleteLogic.kt`（纯判据）、`MediaDeleteLogicTest.kt`（JVM 单测）、本报告。

## 分步清单
- [ ] S0 现状核对（Grep/Read 源码 + 必读材料 + 现有单测写法，逐条带 `文件:行号`）
- [ ] S1 判据契约（函数签名 / 互斥态 / 保守性红线 / 两套 token 长度分派）
- [ ] S2 `MediaDeleteLogic.kt` 落盘
- [ ] S3 `MediaDeleteLogicTest.kt` 单测落盘（≥25 例，逐条标注抓哪个错误实现）
- [ ] S4 变异验证（Python 复刻等价逻辑，≥4 组各自变红）
- [ ] S5 报告：三档边界表 / 证据分级三栏 / 已知盲区 / 接线要点

[START]

## S0 现状核对（以磁盘源码为准，逐条带 `文件:行号`）

[DONE] S0 现状核对 · 结论：现有唯一 delete 纯判据是 `object VoiceGuardLogic`，**只服务 `voiceinfo`**；本 lane 新增的 `MediaDeleteLogic` 与 7 个既有生产类零重叠，可独立落盘、不接线即零行为变化。现有单测统一用 JUnit4 `org.junit.Assert.*` + `org.junit.Test`，照此模仿。

### 源码锚点（生产类）
| 事实 | 锚点 |
|---|---|
| `object VoiceGuardLogic`，`TABLE="voiceinfo"`，26 位 hex 正则 `HEX`，`EQ_OR_IN` 只认 `filename\|msglocalid`，`SUBQUERY` 放行 `select` | `VoiceGuardLogic.kt:35,38,48,55-58,66` |
| `hexTokensFrom`（小写归一、带 hex 边界断言）；`cacheUsable` = `cachedSize>0 && cachedGen==gen && now-cachedAt<ttlMs`；`stampAfterBuild` 空集记 `now-ttlMs` | `VoiceGuardLogic.kt:75-78,107-114,125-126` |
| ⛔ 保守性红线「无条件拦 delete 会破坏微信 GC；只拦命中已救消息 + 撤回期的单条删除；`null` 删全表 / `<` `>` `LIKE` `IS NULL` / 子查询整会话删除必须放行」 | `VoiceGuardLogic.kt:23-26,145-157` |
| `object MediaGuardLogic`：`MIN_TOKEN=32`、`MD5_LEN=32`、`isMd5`、`MEDIA_DIRS`、`hexToken`（优先恰好 32 位段）、`inMediaDir`、`anyAlive`、`mediaHit` | `MediaGuardLogic.kt:38,41,55,58,61,80-100,103-107,110-113,125-135` |
| `VoiceGuard.kt:136` 已 `hookAllMethods(cls,"delete",deleteGuard)`（方法级，任何表 delete 都回调）；`:125-132` 注释坐实 wcdb **无 `deleteWithOnConflict`**（死代码）；`:133-135` 仍挂它返回空集；`:147` `refresh()` 读 `ANTIREVOKE_MSG`；`:169-186` `onDelete` 按位置取参 | `VoiceGuard.kt:125-147,169-186` |
| `VoiceFileGuard.kt:251-252` 挂 `java.io.File.delete/deleteOnExit` 全进程；`:291-315` 图片/视频分支；`:306` `param.result=true`；`:301` `anyAlive` 复位 `hasMedia`；`:312` 60s 节流日志 | `VoiceFileGuard.kt:251-252,291-315` |
| `RevokeRescue.kt:109` `VoiceGuard.invalidate()`；`:121-128` 图片/视频 `protectMedia`+`auditImage`（**无 backup/restore**）；`:129-144` 语音三层 `protect→backup→scheduleRestore` | `RevokeRescue.kt:100-145` |
| `AntiRevoke.kt:359-360` 明写「只挂 `updateWithOnConflict`」；`:371` 实挂；`:382` `insertProbe` 只看不拦；`:450-472` `anchorImage`（`:461` 提前入池、`:470` `IMG_ANCHOR_TTL_MS`、`:472` `imgAnchor`）；`:479` `isMd5` 告警 | `AntiRevoke.kt:359-382,450-479` |
| `const val ANTIREVOKE_MSG = "antirevoke.msg"` | `Keys.kt:140` |

### 证据与先例锚点（必读材料）
| 事实 | 锚点 |
|---|---|
| Q1 逐条对账表（delete 钩子有、判据只认 1 张表；`ImgInfo2`/`videoinfo2`/`WxFileIndex2` 零拦截） | `_r49_revokeimg_reference.md:28-34,36-47` |
| Q2 wcdb 硬结论：`deleteWithOnConflict` 不存在（死代码）；`delete()` 纯 Java，入口置 `result` 即跳过方法体 ⇒ 无需挂 `executeUpdateDelete` | `_r49_revokeimg_reference.md:51-85,69-83` |
| 掘金决定性实验转录：只拦 `File.delete` 时仅语音能打开；补 `executeUpdateDelete()` 伪装返回 1 后图片/视频都能打开 | `_r49_revokeimg_reference.md:100` |
| Q5 三候选 / Q6 D2 路线表（A 扩 `VoiceGuardLogic` / B 新增独立钩子 / C 到达即备份） | `_r49_revokeimg_reference.md:182-250` |
| Q7 三档边界主表 + 复位逐条（关开关 / 重试删除 / TTL 过期 / 清缓存，不写「同返回」）+ 五项状态残留 | `_r49_revokeimg_reference.md:260-295` |
| Q8 用例矩阵（7 组，末列逐条「抓哪个错误实现」）+ 11 个变异点 + 拒绝等价变异假验证 | `_r49_revokeimg_reference.md:298-350,315-343` |
| Q9 真机判据 / 反向对照 / **好友一律用昵称（verity、测试群聊、文件传输助手）** | `_r49_revokeimg_reference.md:354-409,405-409` |
| `ImgInfo2` 表结构（DDL 列）+ 索引 | `_r35_revoke_img_anchor.md:36-64` |
| `ImgInfo2` DELETE 形态：全是 `IN (SELECT …)` 删会话；单条走泛型 `delete from %s where %s=%s`；**无单条字面量、待真机**；`:130` 实为 `DELETE FROM AppMessage WHERE msgId IN (SELECT …)` | `_r35_revoke_img_anchor.md:121-143`（尤其 `:126-135,138-143`） |
| 删除面分层 / 漏口（聊天侧 `execSQL` 零挂点；`ImgInfo2` 只读审计不拦删除） / (c) 部分实现 | `_r49_revokeimg_forensics.md:111-166` |
| 五阶段流程「阶段1 纯逻辑+单测」；`:27` 零 Android 依赖纯判据；D1 先查后设计 / D4 控制优先于观测；判据设计=Pro 档 | `CLAUDE.md:20-30,27,38-49,107-112` |
| 单测写法：`package`+`import org.junit.Assert.*`+`org.junit.Test`+`class`+`@Test fun`；清理形态用例；缓存用例**精确断言** `assertEquals(7_000L,…)` | `VoiceGuardLogicTest.kt:1-28,147-154,227-251`；`BackupCodecTest.kt:1-15` |
| 变异验证先例：`_mut.py` 6 组全红；等价变异 0 条不算验证；M0 等价对照保持绿 | `_r37_name_anchor.md:252-268`；`_r21c_voice_file.md:136-146`；`_r42_bit3_impl.md:234-250` |

### 两处如实标注
- `[需真机]` `WxFileIndex2` 的键列：**未取证**。`_r49_revokeimg_reference.md:313` 明写「`WxFileIndex2` 待定 `[需真机]`」；`_r35_revoke_img_anchor.md:130` 命中的是 `AppMessage WHERE msgId IN (SELECT …)`，不是 `WxFileIndex2` 的 DDL。本 lane 对 `WxFileIndex2` 只给**候选键列 + 不确定即放行**，不写成事实。
- `[推断]` 任务点名的 `docs/_r37_tag_paint.md` 在本工作区 `Glob **/docs/*r37*` **无匹配**（实际存在 `_r37_name_anchor.md` / `_r37_export_probe.md` / `_r37_review2.md` / `_r37_deletesrc_verify.md`）；Python 复刻变异的方法按 `_r37_name_anchor.md:252-268` 的 `_mut.py` 先例执行，文件名差异不影响方法。

## S1 判据契约（J1）

[DONE] S1 判据契约 · 结论：6 个必交纯函数 + 2 个顶层枚举（`DeleteClass` / `Verdict`）+ 2 个辅助纯函数（`looksLikeEpochMillis` / 内部 `specFor`），全部零 Android 依赖；**四态 / 三态一律互斥**，任何不确定一律 `false`（放行）。

### 1.1 函数契约表（签名可微调处已在「为什么这么分」列说明）
| 函数 | 签名 | 契约 | 边界 / 不确定即放行 | 为什么这么分 |
|---|---|---|---|---|
| `isTargetTable` | `(table: String?): Boolean` | 表名归一化（去首尾空白 + 去 `"`/`` ` ``/`[`/`]`/`'` 引号变体 + 转小写）后命中 `{imginfo2, videoinfo2, wxfileindex2}` 才 true | `null`/空串/纯空白 → false；**`voiceinfo` 必须 false**；`ImgInfo2X` / `imgInfo2_backup` / `message` / `rconversation` → false | 白名单**刻意不含 `voiceinfo`**：语音由既有 `VoiceGuard` 管（`VoiceGuardLogic.kt:38`），两个回调重复拦会重复计数/重复日志。用 `*equals*` 语义（归一化后 `in set`）而非 `contains`，防止白名单外溢 |
| `classifyDelete` | `(table, whereClause, whereArgs: Array<out Any?>?, protectedTokens: Set<String> = emptySet()): DeleteClass` | 四态互斥归因，见 1.2；默认 `protectedTokens=emptySet()` = **纯形状诊断模式**（不要求命中）；非空集 = **完整判定模式**（BLOCKABLE 还要求命中） | 非白名单 → `OTHER_TABLE`；`whereClause==null`/不安全形态 → `BULK_OR_UNSAFE`；键列未取证 / 占位符取不到值 / 抽不出 token / 未命中保护集 → `UNKNOWN` | **签名微调说明**：题目要求「BLOCKABLE＝单条、命中已救 token」，故把保护集作为第 4 参（带默认值）。默认值让 A/B 两路线能在不查保护池时单独复用形状分类做诊断；`shouldBlock` 用完整模式。判据不持有任何外部状态，纯函数 |
| `extractTokens` | `(table, whereClause, whereArgs): Set<String>` | 从 `whereArgs` **与** `whereClause` 内联字面量双通道，按表分派 token 长度抽「恰好该长度、前后非 hex 边界」的 hex 段，统一小写返回 | 表无 spec → 空集；`null` / 空 / 非法字符 / 长度不对（31/33/34 位）/ `arrayOf(null,…)` → 不收（不抛异常）；**26 位不能命中图片表、32 位不能命中语音表** | ⛔ 只做**正则解析**、不生成/拼接任何 SQL（无注入面）。长度按表分派而非一个正则，依据 `MediaGuardLogic.kt:31-36`「长度随消息类型变是已证实的事实」 |
| `isRevocationWindow` | `(nowMs, tokenIssuedMs, ttlMs: Long): Boolean` | 半开窗口 `0 <= nowMs-tokenIssuedMs < ttlMs`；另含单位自洽闸门 | `ttlMs<=0` → false；`nowMs<issuedMs`（时钟回拨）→ false；**等于 TTL 不在窗口内**；13 位 ms 与 10 位 s 混用（数量级不自洽）→ false；溢出 → false | 边界沿用 `VoiceGuardLogic.kt:114` 的严格 `<` 与 `MediaGuardLogic.kt:111` 的 `>=` 口径；单位闸门防「把 Unix 秒当毫秒」静默延长窗口 |
| `shouldBlock` | `(table, whereClause, whereArgs, protectedTokens: Set<String>, nowMs, tokenIssuedMs, ttlMs): Boolean` | 组合入口：`isTargetTable && classifyDelete(...,protectedTokens)==BLOCKABLE && token∈protectedTokens && isRevocationWindow` | ⛔ **任一环节不确定立即 false**；空保护集 / 表不命中 / 批量形态 / 窗口外 / token 抽不出 / token 未命中 ⇒ 全部放行 | 唯一「要不要拦」的出口，接线侧只调它；把保守性集中在一个函数，便于 A/B 共用、便于单测钉死 |
| `verdict` | `(seen: Long, matched: Long): Verdict` | 三态互斥归因，见 1.4 | `seen<=0`（含负异常、`seen=0,matched>0`）→ `NO_EVENT`；`matched<=0` → `HOOK_OK_BUT_NOT_MATCHED`；否则 `REAL_EVENT` | 用 `when` 单值返回，**不写成并列布尔**；对应「压根没删 / 挂对但判据不识别 / 有真实删除」，供阶段 0 真机 A/B 归因 |

辅助：`looksLikeEpochMillis(v: Long): Boolean = v >= EPOCH_MS_MIN`（`EPOCH_MS_MIN = 1_000_000_000_000L`，13 位最低值 ≈ 2001-09-09）；`private fun specFor(normalizedTable): TableSpec?`（键列集 + token 长度）。

### 1.2 `DeleteClass` 四态互斥（按优先级短路，不是并列布尔）
| 态 | 触发（自上而下首个命中） | 语义 | 接线动作 |
|---|---|---|---|
| `OTHER_TABLE` | `!isTargetTable(table)` | 不是本 lane 的媒体表（含 `voiceinfo` / `null`） | 放行，交给既有 `VoiceGuard` |
| `BULK_OR_UNSAFE` | 表命中但 `whereClause==null`（删全表）/ 空串 / 含 `select`、`like`、`is [not] null`、`in`、`<`、`>`、`<=`、`>=`、`!=`、`<>`、`and`、`or`、`,`、`;` / `whereArgs.size>1` | ⛔ 微信 GC / 整会话清理 / 多值批量形态，**保守性红线** | 放行（拦了会破坏微信 GC，`VoiceGuardLogic.kt:23-26`） |
| `UNKNOWN` | 精确 `=` 但列名不在该表键列白名单 / `?` 数量与 `whereArgs` 不符 / 抽不出合法 token / 完整模式下未命中保护集 | 表对了、形状像单条，但**信息不足或键列未取证** | 放行（不确定即放行） |
| `BLOCKABLE` | 单列精确 `=` + 键列命中 + token 合法 + （完整模式）命中保护集 | 唯一可拦形态：命中已救消息的单条删除 | 交由 `shouldBlock` 再叠加窗口后拦 |

⛔ 与 `VoiceGuardLogic.kt:55-58` 的差异（有意收紧，报告写清）：既有语音判据允许 `IN (?,?,?)` 字面量多值命中；本 lane 把**所有 `IN`（含字面量列表）**归入 `BULK_OR_UNSAFE`，只认单个 `=`。理由：`_r35_revoke_img_anchor.md:126-135` 里 `ImgInfo2` 的已知删除全是 `IN (SELECT…)` 整会话形态，单条走 `delete from %s where %s=%s`（`:132`）；收紧后「不确定即放行」，不会误伤 GC。

### 1.3 按表分派 spec（两套 token 长度）
| 表 | token 长度 | 键列白名单（归一化小写） | 取证状态 |
|---|---|---|---|
| `voiceinfo`（**不在白名单**） | **26** | `filename, msglocalid` | 已证（`VoiceGuardLogic.kt:30-31,48`）；仅供 `extractTokens` 表达「32 位不能命中语音表」，`shouldBlock` 恒不拦 |
| `ImgInfo2` | **32** | `id, msgsvrid, msglocalid, bigimgpath, thumbimgpath, origimgmd5, midimgpath, hevcpath, originsourcemd5` | 列来自 `_r35_revoke_img_anchor.md:41-48` DDL + 任务给定 8 列；⛔ `status/iscomplete/hashdthumb` 是标量列、值非 hex token，**故意不列入**（`status=?` → `UNKNOWN`） |
| `videoinfo2` | **32** | `filename, msglocalid, msgid, msgsvrid` | 任务给定键列 `filename`（`:129` `DELETE FROM videoinfo2 WHERE filename IN (SELECT …)`）；余为 `[推断]` 补齐 |
| `WxFileIndex2` | **32** | `msgid, msglocalid, filename, msgsvrid` | ⛔ **键列 `[需真机]`**（`_r49_revokeimg_reference.md:313` 明写「待定」；`_r35_revoke_img_anchor.md:130` 是 `AppMessage` 不是本表）。故 `UNKNOWN` 分支极易命中，天然放行 |

### 1.4 `Verdict` 三态互斥
| `seen` | `matched` | `Verdict` | 归因 |
|---|---|---|---|
| `<=0` | 任意 | `NO_EVENT` | 压根没删（含 `seen=0,matched>0` 的脏数据，保守归此） |
| `>0` | `<=0` | `HOOK_OK_BUT_NOT_MATCHED` | 挂对了但判据不识别 |
| `>0` | `>0` | `REAL_EVENT` | 有真实删除（且被判据识别） |

### 1.5 与现网行为的隔离声明
- 本 lane **不接线**：`MediaDeleteLogic` 不被任何 `feature/*` 引用，不注册 hook、不读 `Kv`、不改 `param.result`、不写库 ⇒ **零行为变化**。
- 纯函数不依赖 `SystemClock` / `LogX` / `ContentValues` / 任何 `android.*` / `de.robv.android.xposed.*` ⇒ 可直接 JVM 单测。
- `[需真机]` 真要拦哪些表、要不要放宽 `IN`、删除是否走 `execSQL`/预编译语句，均由阶段 0 主会话取证后拍板；本判据只提供「可表达且默认放行」的能力。

## S2 `MediaDeleteLogic.kt` 落盘

[DONE] S2 MediaDeleteLogic.kt · 结论：`wxgroup/app/src/main/java/com/lx/wxgroup/data/MediaDeleteLogic.kt` 已新建（272 行），**零 import**（仅 Kotlin 标准库 `Regex/Set/HashSet/LinkedHashSet/String.lowercase`），未引用任何 `android.*` / `de.robv.android.xposed.*` / `LogX` / `Kv` / `ContentValues`；不被任何 `feature/*` 引用 ⇒ 不接线即零行为变化。

| 符号 | 行号 | 说明 |
|---|---|---|
| `enum class DeleteClass`（4 态）/ `enum class Verdict`（3 态） | `MediaDeleteLogic.kt:37` / `:52` | 顶层枚举，单测可直接引用；互斥用 `when` / 自上而下短路实现，非并列布尔 |
| `object MediaDeleteLogic` / `EPOCH_MS_MIN` / `TARGET_TABLES` | `:58` / `:61` / `:64` | 白名单 `{imginfo2, videoinfo2, wxfileindex2}`，**不含 `voiceinfo`** |
| `VOICE_SPEC`(26) / `IMG_SPEC`(32) / `VIDEO_SPEC`(32) / `WXFILE_SPEC`(32, `[需真机]`) | `:69` / `:76` / `:84` / `:91` | 两套 token 长度 + 键列白名单，见 S1.3 |
| `UNSAFE` / `SINGLE_EQ` | `:102` / `:110` | 清理形态黑名单；单列精确 `=`。`SINGLE_EQ` 用 `matchEntire` 隐含全匹配，正则内**不写 `^`/`$`**，避开 Kotlin 字符串模板对 `$` 的转义陷阱 |
| `normalizeTable` / `specFor` / `collectHex` | `:114` / `:120` / `:133` | 去空白+引号变体+小写；按表分派；带 `(?<!hex)`/`(?!hex)` 边界断言的定长 hex 抽取 |
| `isTargetTable` | `:145` | J1-① |
| `classifyDelete` | `:157` | J1-②，四态短路顺序 `OTHER_TABLE → BULK_OR_UNSAFE → UNKNOWN → BLOCKABLE`（8 步，见 KDoc 编号） |
| `extractTokens` | `:198` | J1-③，args + 内联双通道，只解析不生成 SQL |
| `looksLikeEpochMillis` / `isRevocationWindow` | `:216` / `:226` | 单位自洽闸门 + 半开窗口 + TTL 严格 `<` |
| `shouldBlock` | `:241` | J1-⑤，最便宜判断先做，任一不确定 `false` |
| `verdict` | `:267` | J1-⑥，`when` 单值返回 |

关键实现决策（与 S1 契约一一对应）：
- `classifyDelete` 第 4 参 `protectedTokens: Set<String> = emptySet()`：默认纯形状诊断、非空完整判定，正是 S1.1「签名微调」的落点。
- `UNSAFE` 把 `IN` 字面量列表也判 `BULK_OR_UNSAFE`（S1.2 有意收紧）；`whereArgs.size>1` 同样收紧。
- `collectHex` 用普通字符串而非 `"""…"""`，因为 `{len}` 需要 Kotlin 模板插值；`SINGLE_EQ`/`UNSAFE` 用 `"""…"""` 且不含字面 `$`。
- `WxFileIndex2` 键列在 `MediaDeleteLogic.kt:89-95` KDoc 明标 `[需真机]`，不写成事实。

## S3 `MediaDeleteLogicTest.kt` 单测（J2）

[DONE] S3 单测 · 结论：`wxgroup/app/src/test/java/com/lx/wxgroup/data/MediaDeleteLogicTest.kt` 已新建，**46 个 `@Test`**（≥25），分 6 组；照 `VoiceGuardLogicTest.kt:1-28` 写法（`package` + `import org.junit.Assert.*` + `org.junit.Test` + `class` + `@Test fun`，`assertEquals/assertTrue/assertFalse`）。**每个用例名与 S4 Python 变异验证的 case 名逐条同名同期望**，便于「变红了哪几条」直接对回 Kotlin 源文件。

### J2 用例矩阵（逐条注明「抓哪个看似合理的错误实现」）
| # | 用例名 | 抓的错误实现 |
|---|---|---|
| 1 | `targetTablesAreRecognized` | 用 `==` 不忽略大小写漏 `imginfo2`；白名单漏 `videoinfo2`/`WxFileIndex2` |
| 2 | `nonTargetTablesAreRejected` | ⛔ `table.contains("ImgInfo2")` 误收 `ImgInfo2X`/`imgInfo2_backup`；把 `voiceinfo` 也加进白名单 ⇒ 两个回调重复拦 |
| 3 | `quotedAndWhitespaceTableVariantsAreRecognized` | 不做 `trim()`/去引号 ⇒ SQL 里 `"ImgInfo2"` / `[ImgInfo2]` / `` `VIDEOINFO2` `` 全漏 |
| 4 | `singleEqWithArgIsBlockable` | 只认内联值不认 `whereArgs`；键列白名单漏 `msglocalid`/`bigImgPath` |
| 5 | `singleEqInlineLiteralIsBlockable` | 「不带 `whereArgs` 就一律放行」；不从 `THUMBNAIL_DIRPATH://th_<md5>` 路径形态里抽 md5 |
| 6 | `filenameEqForVideoinfo2IsBlockable` | `videoinfo2` 复用 `ImgInfo2` 的列集 ⇒ `filename` 判不出来 |
| 7 | `nullWhereClauseIsBulkOrUnsafe` | ⛔ 删掉 `whereClause==null` 判断 ⇒ 删全表被拦 ⇒ 破坏微信 GC |
| 8 | `comparisonOperatorsAreBulkOrUnsafe` | 只认 `=` 却没排除 `<`/`>`/`<=`/`>=`/`!=`/`<>` ⇒ 清理形态被拦 |
| 9 | `likeAndIsNullAreBulkOrUnsafe` | `LIKE` / `IS NULL` / `IS NOT NULL` 被当单条删除拦 |
| 10 | `subqueryInSelectIsBulkOrUnsafe` | ⛔ **保守性红线**：把子查询放行删掉 ⇒ `IN (SELECT …)` 整会话清理被拦、微信 GC 失效 |
| 11 | `inLiteralListIsBulkOrUnsafe` | 把 `IN (?,?)` 字面量多值当单条拦（本 lane 有意收紧） |
| 12 | `otherTableIsOtherTableClass` | 白名单恒真；把 `voiceinfo`/`message` 归到本 lane 而非 `OTHER_TABLE` |
| 13 | `andOrMultiConditionIsBulkOrUnsafe` | `AND`/`OR` 多条件 SQL 被 `=` 正则误当单条拦 |
| 14 | `unknownColumnIsUnknown` | 不校验键列白名单 ⇒ `status=?`/`talker=?` 这类清理被拦 |
| 15 | `missingArgForPlaceholderIsUnknown` | `?` 占位符取不到 `whereArgs` 仍强行拦 |
| 16 | `nonExtractableTokenIsUnknown` | 抽不出合法 token 时默认拦（而非放行） |
| 17 | `protectedMissIsUnknown` | 「形状对就拦」，不校验是否命中已保护集合 |
| 18 | `protectedHitIsBlockable` | 不做 lowercase 归一 ⇒ 大写 token 静默漏保护（`MediaGuardLogic.kt:76-78` 记过此型） |
| 19 | `multipleWhereArgsIsBulkOrUnsafe` | 不检查 `whereArgs.size` ⇒ 多值批量被拦 |
| 20 | `extractTokensFromArgsAndInline` | 只查 `whereArgs` 漏内联 SQL，或只查内联漏 args（双通道缺一） |
| 21 | `extractTokensUppercaseNormalized` | 返回值不 lowercase ⇒ 与保护池小写 key 对不上 |
| 22 | `extractTokensRejects26HexOnImageTable` | ⛔ 26/32 统一成一个正则 / 长度判断恒真 |
| 23 | `extractTokensRejects32HexOnVoiceTable` | 同上（反向）；或把 `voiceinfo` 也用 32 位口径 |
| 24 | `extractTokensVoiceTableUses26` | 语音口径误用 32；或干脆不给 `voiceinfo` 分派口径 |
| 25 | `extractTokensRejectsWrongLengthAndIllegalChars` | 删掉 `(?<!hex)`/`(?!hex)` 边界 ⇒ 31/33/34 位、嵌在长串里的前缀误收；下限改回 16 ⇒ 纯数字串误收 |
| 26 | `extractTokensHandlesNullArgs` | `null` args 触发 NPE；空串/空白被当合法 token |
| 27 | `extractTokensIgnoresUnknownTable` | 非白名单表也抽 token ⇒ 越权识别 |
| 28 | `revocationWindowInsideIsTrue` | 窗口恒 false（该拦不拦） |
| 29 | `revocationWindowAtTtlIsFalse` | ⛔ 把严格 `<` 写成 `<=`（TTL 边界差 1ms，`VoiceGuardLogic.kt:114` 同型） |
| 30 | `revocationWindowJustBeforeTtlIsTrue` | 差一错误 / `<=` 反向误伤 |
| 31 | `revocationWindowClockRollbackIsFalse` | 不判时钟回拨 ⇒ `now<issued` 被当负 delta 或溢出值误判 |
| 32 | `revocationWindowNonPositiveTtlIsFalse` | `ttl<=0` 不设防 ⇒ 窗口恒真或语义错位 |
| 33 | `revocationWindowRejectsMixedEpochUnits` | ⛔ 不做单位自洽闸门 ⇒ 13 位 ms 与 10 位 s 混用；同时钉住「两个都是 13 位 ms」不被误伤 |
| 34 | `looksLikeEpochMillisDistinguishesSeconds` | 把 10 位 Unix 秒当 13 位 epoch 毫秒 |
| 35 | `shouldBlockFullChain` | ⛔ `shouldBlock` 恒 false；或漏叠加 `isRevocationWindow` |
| 36 | `shouldBlockRejectsNonTargetTable` | 组合入口不检查 `isTargetTable` / 白名单恒真 |
| 37 | `shouldBlockRejectsBulkForms` | 组合入口绕过 `classifyDelete` 直接按表拦（保守性红线失守） |
| 38 | `shouldBlockRejectsEmptyProtected` | 空保护集仍拦（无的放矢） |
| 39 | `shouldBlockRejectsOutsideWindow` | 不叠加窗口 / TTL 边界 `<=` |
| 40 | `shouldBlockRejectsTokenMiss` | 形状对就拦，不校验 token 命中 |
| 41 | `shouldBlockRejects26HexOnImageTable` | ⛔ 26/32 长度恒真 |
| 42 | `shouldBlockAllUncertainPathsReturnFalse` | 「不确定时返回 true」；`WxFileIndex2` 键列 `[需真机]` 却敢拦 |
| 43 | `verdictNoEvent` | ⛔ 三态写成并列 / 用 `matched` 判 `NO_EVENT` |
| 44 | `verdictHookOkButNotMatched` | `seen>0,matched=0` 被误归 `NO_EVENT` 或 `REAL_EVENT` |
| 45 | `verdictRealEvent` | `matched>0` 被误归 `NO_EVENT` |
| 46 | `verdictStatesAreMutuallyExclusive` | 并列布尔导致同一输入归多态；枚举态数被改动 |

覆盖对照 `_r49_revokeimg_reference.md:315-343` Q8 矩阵：组 1/2（白名单）、3/4（token 双长度）、5（清理/子查询/删全表）、6（命中/大小写/空集/长度边界）、TTL 边界（缓存口径）**全覆盖并扩到 46 例**；`verdict` 三态互斥与「13 位 ms / 10 位 s」为本轮新增。

## S4 变异验证（J3）

[DONE] S4 变异验证 · 结论：因本 lane **禁跑 gradle**，按项目先例 `_r37_name_anchor.md:252-268` 的 `_mut.py` / `_r29_f1_chatroom_trigger.md:132`（不跑 gradle 的复刻推演），用 **Python 复刻纯判据的等价逻辑**经 `python - <<'PY'`（stdin）实跑；**脚本不落盘任何文件**（工作区仍只有 3 个交付物），不碰 adb / 真机 / kvset。`Python 3.13.14` 实跑输出：

```
BASELINE cases=46 GREEN=46 RED=0
M1_whitelist_always_true              : RED=2  ['nonTargetTablesAreRejected', 'otherTableIsOtherTableClass']
M2_subquery_allow_to_block           : RED=4  ['likeAndIsNullAreBulkOrUnsafe', 'subqueryInSelectIsBulkOrUnsafe', 'inLiteralListIsBulkOrUnsafe', 'andOrMultiConditionIsBulkOrUnsafe']
M3_token_len_always_true             : RED=4  ['extractTokensRejects26HexOnImageTable', 'extractTokensRejects32HexOnVoiceTable', 'extractTokensRejectsWrongLengthAndIllegalChars', 'shouldBlockRejects26HexOnImageTable']
M4_shouldBlock_always_false          : RED=1  ['shouldBlockFullChain']
M5_verdict_parallel_instead_of_excl  : RED=3  ['verdictNoEvent', 'verdictHookOkButNotMatched', 'verdictStatesAreMutuallyExclusive']
M6_ttl_inclusive                     : RED=2  ['revocationWindowAtTtlIsFalse', 'shouldBlockRejectsOutsideWindow']
MUTATION_GROUPS=6  ALL_RED=True
```

⇒ **6/6 组语义变异各自变红，最少 1 条、最多 4 条，无 0 条**；baseline 46/46 绿。⛔ 不存在 `_r21c_voice_file.md:146` 那种「等价变异 0 条变红」或 `_r21c_voice_sqlform.md:438-439` 那种「只断言 `>0` 的假绿」——每条用例都断言精确值（`assertEquals` 精确枚举/集合/布尔），见 S3 矩阵最后一列。

### 改坏哪一行 / 抓哪个错误实现 / 条数
| 组 | 注入变异（改坏 Python `BASE` 的哪一行） | 对应 Kotlin 源码行 | 变红条数 | 变红用例（= 抓的错误实现） |
|---|---|---|---|---|
| M1 | `is_target_table` 整个函数体换成 `return True` | `MediaDeleteLogic.kt:145-149` 的 `return t in TARGET_TABLES` → `true` | **2** | 白名单恒真 ⇒ 误收 `voiceinfo`/`message`/近似表名 |
| M2 | `UNSAFE` 正则删掉 `select\|like\|is null\|in\|and\|or\|,\|;`，只留比较符 | `MediaDeleteLogic.kt:102-104` `UNSAFE` | **4** | ⛔ 子查询/清理形态被拦 ⇒ 破坏微信 GC（核心红线） |
| M3 | `collect` 里 `{len}` → `{26,32}`（长度判断恒真） | `MediaDeleteLogic.kt:133-136` `collectHex` 的 `Regex(...{$len}...)` | **4** | ⛔ 26/32 统一口径 ⇒ 语音 token 命中图片表、错长 hex 误收 |
| M4 | `should_block` 首行插 `return False` | `MediaDeleteLogic.kt:241-258` `shouldBlock` | **1** | `shouldBlock` 恒 false ⇒ 该拦的不拦（唯一守这条的用例） |
| M5 | `verdict` 两个 `if` 的判据变量 `seen`/`matched` 互换 | `MediaDeleteLogic.kt:267-271` `verdict` 的 `when` 两分支 | **3** | ⛔ 三态互斥写成并列/错配 ⇒ 归因错位 |
| M6 | `return d < ttl` → `return d <= ttl` | `MediaDeleteLogic.kt:226-233` `isRevocationWindow` | **2** | TTL 边界差 1ms（`<=` 误把到点判为窗口内） |

题目点名的 5 个变异点（白名单恒真 / 子查询放行改拦 / 26-32 恒真 / `shouldBlock` 恒 false / `verdict` 并列）**全部覆盖**，另加 M6（TTL 边界）。

### 逐函数等价对应（Kotlin ↔ Python 复刻）
| Kotlin (`MediaDeleteLogic.kt`) | Python `BASE` | 一致性 |
|---|---|---|
| `normalizeTable` `:114` | `norm(t)`：`strip().strip(QUOTES).lower()` | 同（引号字符用 `chr()`/`\x22` 构造，正则语义一致） |
| `isTargetTable` `:145` | `is_target_table` | 同 |
| `specFor` `:120` + 4 个 `TableSpec` `:69-95` | `SPECS` 字典（键列集 + 26/32） | 同 |
| `collectHex` `:133` | `collect(s, ln, out)`：`(?<!hex)[0-9a-fA-F]{ln}(?!hex)` | 同（`%d` 插值 ↔ Kotlin `$len` 模板） |
| `extractTokens` `:198` | `extract_tokens`：args + 内联双通道 | 同 |
| `UNSAFE` `:102` / `SINGLE_EQ` `:110` | `UNSAFE` / `SINGLE_EQ` | 同（`matchEntire` ↔ `re.fullmatch`） |
| `classifyDelete` `:157`（8 步短路） | `classify`（同序 8 步） | 同 |
| `looksLikeEpochMillis` `:216` / `isRevocationWindow` `:226` | `looks_ms` / `window` | 同 |
| `shouldBlock` `:241` | `should_block` | 同 |
| `verdict` `:267` | `verdict` | 同 |

### 实跑脚本主体（判据复刻 + 变异注入 + 执行框架；46 条用例以 `add(name, got, exp)` 登记，名字与期望逐条对应 S3 矩阵）
```python
# -*- coding: utf-8 -*-
# 经 `python - <<'PY'` 从 stdin 执行；不落盘、不跑 gradle。
BASE = r'''
import re
TARGET = {"imginfo2", "videoinfo2", "wxfileindex2"}
EPOCH_MS_MIN = 1_000_000_000_000
QUOTES = chr(34) + chr(96) + "[" + "]" + chr(39)
SPECS = {
    "voiceinfo": ({"filename", "msglocalid"}, 26),
    "imginfo2": ({"id", "msgsvrid", "msglocalid", "bigimgpath", "thumbimgpath",
                  "origimgmd5", "midimgpath", "hevcpath", "originsourcemd5"}, 32),
    "videoinfo2": ({"filename", "msglocalid", "msgid", "msgsvrid"}, 32),
    "wxfileindex2": ({"msgid", "msglocalid", "filename", "msgsvrid"}, 32),
}
def norm(t):
    if t is None: return None
    t = t.strip().strip(QUOTES).lower()
    return t if t else None
def is_target_table(table):                 # M1 锚点
    t = norm(table)
    if t is None:
        return False
    return t in TARGET
def spec_for(t): return SPECS.get(t)
def collect(s, ln, out):                    # M3 锚点
    pat = r"(?<![0-9a-fA-F])[0-9a-fA-F]{%d}(?![0-9a-fA-F])" % ln
    for m in re.finditer(pat, s):
        out.add(m.group(0).lower())
def extract_tokens(table, where, args):
    sp = spec_for(norm(table))
    if sp is None: return set()
    keys, ln = sp
    out = set()
    if args is not None:
        for a in args:
            if a is None: continue
            collect(str(a), ln, out)
    if where is not None: collect(where, ln, out)
    return out
UNSAFE = r"(?i)\bselect\b|\blike\b|\bis\s+(not\s+)?null\b|\bin\b|!=|<>|<=|>=|<|>|\band\b|\bor\b|,|;"   # M2 锚点
SINGLE_EQ = r"\s*[\[\x22'`]?([A-Za-z_][A-Za-z0-9_]*)[\]\x22'`]?\s*=\s*(.+?)\s*"
def classify(table, where, args, protected=None):
    if protected is None: protected = set()
    if not is_target_table(table): return "OTHER_TABLE"
    w = where.strip() if where is not None else None
    if w is None or w == "": return "BULK_OR_UNSAFE"
    if re.search(UNSAFE, w): return "BULK_OR_UNSAFE"
    if args is not None and len(args) > 1: return "BULK_OR_UNSAFE"
    sp = spec_for(norm(table))
    if sp is None: return "UNKNOWN"
    keys, ln = sp
    m = re.fullmatch(SINGLE_EQ, w)
    if m is None: return "UNKNOWN"
    col = m.group(1).lower()
    if col not in keys: return "UNKNOWN"
    ph = m.group(2).count("?")
    if ph > 0 and (0 if args is None else len(args)) != ph: return "UNKNOWN"
    toks = extract_tokens(table, w, args)
    if not toks: return "UNKNOWN"
    if protected:
        normed = set(p.lower() for p in protected)
        if not any(t in normed for t in toks): return "UNKNOWN"
    return "BLOCKABLE"
def looks_ms(v): return v >= EPOCH_MS_MIN
def window(now, issued, ttl):               # M6 锚点
    if ttl <= 0: return False
    if now < issued: return False
    if looks_ms(now) != looks_ms(issued): return False
    d = now - issued
    if d < 0: return False
    return d < ttl
def should_block(table, where, args, protected, now, issued, ttl):   # M4 锚点
    if not is_target_table(table): return False
    if not protected: return False
    if not window(now, issued, ttl): return False
    if classify(table, where, args, protected) != "BLOCKABLE": return False
    toks = extract_tokens(table, where, args)
    if not toks: return False
    normed = set(p.lower() for p in protected)
    return any(t in normed for t in toks)
def verdict(seen, matched):                 # M5 锚点
    if seen <= 0: return "NO_EVENT"
    if matched <= 0: return "HOOK_OK_BUT_NOT_MATCHED"
    return "REAL_EVENT"
'''

MUTATIONS = {
  "M1_whitelist_always_true": ("def is_target_table(table):\n    t = norm(table)\n    if t is None:\n        return False\n    return t in TARGET",
                               "def is_target_table(table):\n    return True"),
  "M2_subquery_allow_to_block": (r'''UNSAFE = r"(?i)\bselect\b|\blike\b|\bis\s+(not\s+)?null\b|\bin\b|!=|<>|<=|>=|<|>|\band\b|\bor\b|,|;"''',
                                 r'''UNSAFE = r"!=|<>|<=|>=|<|>"'''),
  "M3_token_len_always_true": (r'''    pat = r"(?<![0-9a-fA-F])[0-9a-fA-F]{%d}(?![0-9a-fA-F])" % ln''',
                               r'''    pat = r"(?<![0-9a-fA-F])[0-9a-fA-F]{26,32}(?![0-9a-fA-F])"'''),
  "M4_shouldBlock_always_false": ("def should_block(table, where, args, protected, now, issued, ttl):\n    if not is_target_table(table):\n        return False",
                                  "def should_block(table, where, args, protected, now, issued, ttl):\n    return False\n    if not is_target_table(table):\n        return False"),
  "M5_verdict_parallel_instead_of_exclusive": ("def verdict(seen, matched):\n    if seen <= 0:\n        return \"NO_EVENT\"\n    if matched <= 0:\n        return \"HOOK_OK_BUT_NOT_MATCHED\"\n    return \"REAL_EVENT\"",
                                               "def verdict(seen, matched):\n    if matched <= 0:\n        return \"NO_EVENT\"\n    if seen <= 0:\n        return \"HOOK_OK_BUT_NOT_MATCHED\"\n    return \"REAL_EVENT\""),
  "M6_ttl_inclusive": ("    return d < ttl", "    return d <= ttl"),
}

def run(src):
    ns = {}; exec(compile(src, "<replica>", "exec"), ns)
    cases = build_cases(ns)   # 46 条 add(name, got, exp)，逐条同名同期望于 S3 矩阵
    return len(cases), [n for n, got, exp in cases if got != exp]

total, base_reds = run(BASE)
print("BASELINE cases=%d GREEN=%d RED=%d" % (total, total - len(base_reds), len(base_reds)))
for mname, (old, new) in MUTATIONS.items():
    assert old in BASE, "变异锚点未命中（假变异）"
    n, reds = run(BASE.replace(old, new, 1))
    print("%s : RED=%d  %s" % (mname, len(reds), reds))
```

### 拒绝「等价变异假验证」自检（对照 `_r49_revokeimg_reference.md:343-350`）
- ✅ baseline **46/46 全绿**：复刻与 S3 期望自洽；每个变异从 `BASE` 字符串 `replace` 派生，`BASE` 本身从未就地改动 ⇒ 等价于「逐组改坏→跑→还原」，还原态即 baseline 绿（同 `_r42_bit3_impl.md:234-250`）。
- ✅ 每组先 `assert old in BASE`，**锚点未命中即判假变异**（防「改了不相干的一行却报变红」）。
- ✅ 6/6 语义变异**各自变红**，变红用例与其「抓的错误实现」逐条对应 S3 最后一列；无 0 条组。
- ✅ 用例均**精确断言**（枚举 / 集合 / 布尔 / TTL 精确边界），不用 `>0` 之类弱断言。
- `[已知边界]` 本轮**未单独设 M0 等价变异对照组**（项目先例 `_r42_bit3_impl.md:242` 有）；6 组均为语义变异，非等价变异，故不影响「有区分力」结论。
- ⛔ `[推断]` **Python 复刻 ≠ Kotlin 编译/执行**：禁跑 gradle，本轮未验证 `.kt` 实际编译与 JVM 运行结果；`assembleDebug` / `testDebugUnitTest` 由主会话统一执行。复刻与 Kotlin 逐函数同构（上表），且 baseline 全绿，但这是**静态等价性**而非编译证据。

## S5 三档边界 / 证据分级 / 已知盲区 / 接线要点

[DONE] S5 报告与三档边界 · 结论：三档边界**复位档逐条独立写、不写「同返回」**；证据按「已证事实 / 静态推断 / 必须真机」三栏分级；A/B 接线要点只给要点、不写实现代码、不指挥改文件。

> ⚠️ 语境：本 lane **未接线**，以下三档描述的是「未来 A/B 任一路线接上本判据后」的预期状态，**全部 `[需真机]` 才能定**；当前现网行为因本 lane 零接线而**完全不变**。举例好友一律用昵称：**verity**、**测试群聊**、**文件传输助手**（不写裸 wxid）。

### 5.1 三档边界主表
| 档 | 触发 | 发生什么（锚点） | 状态残留 |
|---|---|---|---|
| **① 进入**（刚撤回那一刻） | 请 **verity** 在「**测试群聊**」发一张图并撤回（`_r49_revokeimg_reference.md:358`） | `AntiRevoke.kt:371` 拦 `updateWithOnConflict` 改写；`RevokeRescue.kt:122` `protectMedia`；`AntiRevoke.kt:461` `anchorImage` 早在消息到达时已把 md5 提前入池；`VoiceFileGuard.kt:251-252,306` 拦 `File.delete`。**媒体表 DELETE 是否拦取决于未来 A/B 接线**——本 lane 只提供判据，当前 `ImgInfo2`/`videoinfo2`/`WxFileIndex2` 仍零拦截（`_r49_revokeimg_reference.md:31,101`） | 保护池 token（**10 分钟 TTL**，`VoiceFileGuard.kt:112`）、`hasMedia=true`、`imgAnchor` 1 项（**30 分钟 TTL**，`AntiRevoke.kt:470`）、`rescued` 台账 1 行、hook 全挂、**图片侧无备份文件**（`RevokeRescue.kt:121-127`）、微信库行 + `ImgInfo2` 行 + `image2` 原文件在 |
| **② 返回**（隔几分钟点开 / 切页回来 / 下拉刷新 / **冷启**） | 纯 UI 重建或进程重启，**不产生新的撤回 UPDATE** | 切页/下拉刷新只读 `message` + `ImgInfo2`（`RevokeRescue.kt:268-277` 查询形态），池与 hook 不变、文件仍在 ⇒ 能打开（前提 DB 行在，`_r49_revokeimg_reference.md:270`）。**冷启单列**：`HookEntry` 重新 `install()`，`protectedMedia`/`hasMedia`/`logAt`/`imgAnchor` **进程内集合全清零**；持久化状态（rescued 台账、`image2` 原文件、微信库行）保留；冷启后若微信重新发起删除（补拉/重试）而池空 ⇒ **无人拦** `[需真机]`（`_r49_revokeimg_reference.md:270,278`） | 隔几分钟点开：池仍在（10 分钟 TTL 内）；切页/下拉刷新：池与 hook 都不变；**冷启：池空、hook 重装、台账/文件保留**。`auditImage` Cursor 每次 `close()` 不残留（`RevokeRescue.kt:286-288`） |
| **③ 复位** | 见 5.2 逐条，**与返回档本质不同** | 见 5.2 | 见 5.2 + 5.3 |

⛔ **② 档里「TTL」与「冷启」各自单列，不与「切页回来」合并**：TTL 是**时间维度**（池随时间过期、由 `anyAlive` 复位快判），冷启是**进程维度**（进程内集合整体清零、hook 重装），两者触发条件与残留完全不同（`_r49_revokeimg_reference.md:279` 与 `:270` 分列）。

### 5.2 ⛔ 复位档逐条（不写「同返回」）
| 复位动作 | 发生什么 | 与返回档的本质差异 |
|---|---|---|
| **关 `antirevoke.msg`**（`Keys.kt:140`） | 开关翻转；`VoiceFileGuard.on()`/`VoiceGuard.cfg()` 有 **3 秒 TTL 缓存**（`VoiceFileGuard.kt:185-189`、`VoiceGuard.kt:151-154`）⇒ 关后最多 3 秒内仍可能拦；`AntiRevoke` 由 `kvset` 主动 `refresh()`（`EntryReceiver.kt:181-182`）。⛔ **hook 不卸载**（`install()` 无 unhook），**随进程死才卸**，关后空转；`protectedMedia` **不清空**，旧 token 靠 TTL 自然过期 ⇒ 关后短时间内再开，旧 token 可能仍在保护 | 返回档 hook 与开关**都不变**；复位档开关翻转 + 3 秒延迟 + hook 常驻空转 + 池残留（`_r49_revokeimg_reference.md:277`） |
| **TTL 过期** | `PROTECT_MEDIA_MS=600_000L`（`VoiceFileGuard.kt:112`）到期 ⇒ `mediaHit` 返回 null、**`MediaGuardLogic.anyAlive`（`MediaGuardLogic.kt:110-113`）把 `hasMedia` 复位 false**（`VoiceFileGuard.kt:301`），池清空、快判回「连 path 都不取」的零开销（`VoiceFileGuard.kt:299-301`） | 返回档池**还在**；TTL 过期后再发生删除**无人拦** ⇒ 从「保护态」变为「裸奔态」，返回档无此转变（`_r49_revokeimg_reference.md:279`） |
| **`imgAnchor` 30 分钟 TTL** | `AntiRevoke.kt:470` `IMG_ANCHOR_TTL_MS` + `:468-470` 256 上限淘汰；**不清空、冷启丢**（`_r49_revokeimg_reference.md:292`） | 与保护池 10 分钟 TTL **不同载体、不同时长**，单独复位 |
| **微信重试删除** | 重试落在 TTL 内 ⇒ 每次被拦、60s 节流只打 1 行日志（`VoiceFileGuard.kt:309-313`）；落在 **TTL 外或冷启后池空 ⇒ 无人拦、文件被删**。⛔ 语音侧有 `scheduleRestore` 兜底（`RevokeRescue.kt:137-144`），**图片侧没有**（`:121-127`）⇒ 一旦漏拦即永久丢失（`_r49_revokeimg_reference.md:278`） | 返回档默认「删除只发生一次且已被拦」；复位档把「重试时机」变成自变量，是 R42「漏掉唯一复位路径」同型事故 |
| **清缓存** | `op=logclear` 只清 LogX 环形缓冲（`EntryReceiver.kt:165-168`），**不动池/台账/文件**。若指微信「存储空间→清缓存」删 `image2`：开关**开着** ⇒ `File.delete` 被拦 ⇒ 存储只增不减（`VoiceFileGuard.kt:67-68,175-177`）；开关**关着** ⇒ 真删 | 返回档**不会删文件**；这是唯一能真正把已保护图片删掉的路径（`_r49_revokeimg_reference.md:280`） |
| ⛔ **已删的 DB 行 / 磁盘文件不可回滚** | 本判据只能在 `beforeHookedMethod` **阻止删除发生**，**没有任何 restore 能力**：`ImgInfo2` 行一旦被删、`image2` 文件一旦被 native `unlink`（`VoiceFileGuard.kt:35-38`）删除，均无副本可还原（图片侧无 backup/restore，`RevokeRescue.kt:121-127` 对照语音 `:129-144`） | 返回档前提是「行与文件都还在」；这是**不可逆**的终态，不属于任何「返回」路径 |

### 5.3 复位档状态残留总表
| 状态 | 载体 | 复位后残留 | 清除条件 |
|---|---|---|---|
| 保护池 token | `VoiceFileGuard.protectedMedia`（`:109,151-155`） | **不清空**，旧 token 仍在 | **10 分钟 TTL**（`:112`）后由 `MediaGuardLogic.anyAlive`（`MediaGuardLogic.kt:110-113`）把 `hasMedia` 复位；或进程死 |
| 备份文件 | `VoiceBackup.kt:42-45` `voice_bak/` | **图片侧没有**（`RevokeRescue.kt:121-127`）；语音备份保留 | 手工 / 微信 GC；图片无副本可还原 |
| 已挂 hook | `HookEntry.kt:208-262` | **永不卸载**，`install()` 无 unhook，关开关后空转 | **仅进程死亡** |
| 10 分钟 TTL | `VoiceFileGuard.kt:112` | 到期后 `hasMedia=false`（`:301`） | 到期 |
| DB 行 | `message` / `ImgInfo2` / `rescued` | `rescued` 台账跨关开关、跨冷启保留（模块自有库）；微信库行关开关后重试删除/清缓存**可真删** | 显式删除 / 微信删除 |
| 补充 `imgAnchor` | `AntiRevoke.kt:467-472` | 不清，冷启丢 | **30 分钟 TTL** / 256 上限 / 进程死 |
| 补充 `logAt` 节流 | `VoiceFileGuard.kt:79-80` | 冷启丢 | 60s / 进程死 |
| 补充 开关缓存 | `VoiceFileGuard.on()` `:185-189`、`VoiceGuard.cfg()` `:151-154` | **3 秒 TTL 延迟** | `kvset` 主动 `refresh()`（`EntryReceiver.kt:181-182`） |

### 5.4 证据分级三栏
| 已证事实（带锚点） | 静态推断 `[推断]` | 必须真机才能定 `[需真机]` |
|---|---|---|
| `VoiceGuardLogic.kt:38,48,55-58` 现有 delete 判据只认 `voiceinfo`、26 位 hex、`filename\|msglocalid` | `ImgInfo2`/`videoinfo2`/`WxFileIndex2` 撤回单条删除走 `SQLiteDatabase.delete()`（可能走 `execSQL`/预编译，`_r49_revokeimg_reference.md:46,90`） | `ImgInfo2` 行撤回后是否被删（阶段 0 决定性取证，`_r49_revokeimg_reference.md:94-105`） |
| `MediaGuardLogic.kt:40-55` 图片 token 是 32 位 md5；`MediaGuardLogic.kt:31-36` 长度随消息类型变 | 撤回期 `ImgInfo2` 行幸存（lane B 明写推断，`_r35_revoke_img_anchor.md:141-143`） | 撤回时媒体表 DELETE 的真实 `whereClause` 形态（单条 `=` / `IN (SELECT)` / 泛型 `%s=%s`，`_r35_revoke_img_anchor.md:126-139`） |
| `VoiceGuard.kt:136` 已挂 wcdb `delete`；`:125-132` wcdb **无 `deleteWithOnConflict`**（死代码） | 本判据键列白名单中 `videoinfo2` 的 `msgid/msgsvrid`、`WxFileIndex2` 全部键列为推断补齐 | `WxFileIndex2` 键列（`_r49_revokeimg_reference.md:313` 明写「待定」） |
| `AntiRevoke.kt:371` 只挂 `updateWithOnConflict`；`:461` `anchorImage` 提前入池 | Python 复刻与 Kotlin 逐函数静态等价（S4 表），但**未编译** | 删除走 `delete()` / `execSQL` / 预编译 `SQLiteStatement`（决定 A 是否够用） |
| `RevokeRescue.kt:122` 图片/视频 `protectMedia`；`:109` `VoiceGuard.invalidate()` | 三档边界中「接线后」的预期行为（A/B 未拍板） | 冷启后能否打开、`image2` 文件名/`hd` 形态、native `unlink` 是否发生 |
| `VoiceFileGuard.kt:251-252,306` `File.delete` 全进程拦；`:112` 10 分钟 TTL；`MediaGuardLogic.kt:110-113` `anyAlive` | `verdict` 的 `seen`/`matched` 计数语义由接线侧定义 | A/B/C 路线拍板；Kotlin 侧 `assembleDebug` / `testDebugUnitTest` 结果（主会话统一执行） |
| `Keys.kt:140` `antirevoke.msg`；`_r35_revoke_img_anchor.md:36-64` `ImgInfo2` DDL；`:126-135` 删除形态；`_r49_revokeimg_reference.md:100` 掘金决定性实验转录 | 本 lane 新增的 4 态/3 态归因与保守性收紧是否适配真机 SQL | 三档边界的实际用户可见结果（让 **verity** 在「**测试群聊**」撤图后点开能否看到大图） |
| **本轮落盘事实**：`MediaDeleteLogic.kt`（6 纯函数 + 2 枚举，零 Android 依赖）、`MediaDeleteLogicTest.kt`（46 例）；Python 复刻 baseline **46/46 绿**、**6/6 组变异各自变红**（S4） | — | — |

### 5.5 已知盲区
- ⛔ `WxFileIndex2` 键列 `[需真机]`：判据对该表基本走 `UNKNOWN` ⇒ 放行，不会误拦，但可能漏拦。
- ⛔ 只认**单个 `=`**：若真机证实撤回用 `IN (?,?)` 字面量或 `IN (SELECT …)` 单条形态，本判据会漏拦（有意保守，需真机后由主会话决定是否放宽 `BULK_OR_UNSAFE`）。
- ⛔ 不覆盖 `execSQL` / `INSERT OR REPLACE` / 预编译 `SQLiteStatement` / native `unlink`：纯判据只解析 `delete(table, whereClause, whereArgs)` 三参（`_r49_revokeimg_reference.md:215` 记 `execSQL` 返回 void，`param.result=1` 语义不适用）。
- ⛔ 单位纪律：`isRevocationWindow` 只挡「13 位 ms 与 10 位 s **混用**」；若接线侧把 `nowMs` 与 `tokenIssuedMs` **都**传成 10 位秒，秒差会被当毫秒差处理。接线侧必须统一传 `SystemClock.elapsedRealtime()` 或 13 位 `currentTimeMillis()`。
- ⛔ 不含 `voiceinfo`（有意）：语音由既有 `VoiceGuard` 管；若 A/B 两路线都接线，须确保两个 `delete` 回调靠白名单互斥，别重复拦/重复计数。
- ⛔ **未跑 gradle**：`.kt` 编译与 JVM 单测实际执行未验证（`[推断]` 由 S4 静态等价复刻支撑）；`M0` 等价变异对照组未设。
- ⛔ `verdict(seen, matched)` 的计数口径（`seen`/`matched` 各代表什么事件）由接线侧定义，本 lane 只给互斥归因函数。
- ⛔ 报告提到的 `docs/_r37_tag_paint.md` 在本工作区 Glob 无匹配（S0 已标 `[推断]` 文件名偏差），Python 复刻方法按 `_r37_name_anchor.md:252-268` 先例执行。

### 5.6 给主会话的「接线要点」（仅要点，⛔ 不写实现代码、不指挥改文件）
- **A 路线（扩 `VoiceGuardLogic`）**：在既有 `VoiceGuard.kt:169-186` `onDelete` 的表判据处，把 `MediaDeleteLogic.isTargetTable` / `classifyDelete` / `shouldBlock` 当纯判据调用；⛔ 别把 `VoiceGuardLogic.hexTokensFrom`（`VoiceGuardLogic.kt:75-78`，**26 位**）直接套到图片表，必须按 S1.3 分派 26/32 两套长度；`VoiceGuardLogic.kt:107-114` `cacheUsable` 的 `gen`/`cachedGen` 缓存与 `RevokeRescue.kt:109` `VoiceGuard.invalidate()` 时序要对齐——**台账 `record()` 写成功之后**再 `invalidate()`，否则连撤第二条会命中陈旧缓存（R21c 同型漏洞）。
- **B 路线（新增独立钩子）**：新 `feature` 类的 `delete`（及若真机证实需要的 `execSQL`）回调里，先做最便宜的 `isTargetTable`，再 `shouldBlock(...)`；两个 `delete` 回调靠白名单互斥（既有 `VoiceGuard.kt:173-174` 只认 `voiceinfo`，本 lane 白名单**不含** `voiceinfo`）；同样在 `RevokeRescue.record()` 台账成功后调用新钩子的 `invalidate()`（同 `:109` 范式）以解决 `gen` 缓存陈旧。
- **两路线共同**：`protectedTokens` 由「已救消息 + 撤回期」集合提供，`nowMs/tokenIssuedMs/ttlMs` **统一毫秒口径**（别混 10 位秒）；命中后在 `before` 里 `param.result` 伪装成功（wcdb `delete()` 纯 Java，入口置 result 即跳过整个方法体、`executeUpdateDelete()` 不执行，`_r49_revokeimg_reference.md:85`）；⛔ 绝不写微信库、不改 `ContentValues`、不改 SQL、不改任何开关语义；`execSQL` 返回 void，`delete()` 那套伪装受影响行数的语义不适用（`_r49_revokeimg_reference.md:215`）。
- **时序与验证**：真机前先按 Q3 判据证「`ImgInfo2` 行是否被删 + 走哪条 SQL 通道」；`gen` 缓存与 `RevokeRescue.invalidate()` 的时序是 A/B 共有的「连撤两条」风险点；`verdict` 三态用于把「压根没删 / 挂对但判据不识别 / 有真实删除」分开归因。⛔ 好友一律用昵称（**verity** / **测试群聊** / **文件传输助手**）。

### 5.7 红线自检（返回前逐条确认）
| 项 | 结论 |
|---|---|
| 进入边界 | ✅ 判据默认「不确定即放行」（`shouldBlock` 任一环节不确定 `false`）；本 lane **不接线**，不因新增代码改变任何现网行为 |
| 返回边界 | ✅ TTL（10 分钟、`anyAlive` 复位）与冷启（进程内集合清零）在 5.1/5.2 **单独写**，不与「切页回来」合并 |
| 复位边界 | ✅ 关 `antirevoke.msg` / TTL 过期 / `imgAnchor` 30 分钟 TTL / 微信重试删除 / 清缓存 **逐条写**，未写「同返回」；⛔ 已删的 DB 行与磁盘文件**不可回滚**单列 |
| 全只读 | ✅ 不改微信库、不改 `ContentValues`、不改 SQL、不改返回值、不碰设备；未跑 gradle、未 `git add`/`commit`、未碰 adb/真机/装包/kvset |
| 好友昵称 | ✅ 报告举例只用 **verity** / **测试群聊** / **文件传输助手**，无裸 wxid |
| 文件约束 | ✅ 仅 3 个新文件：`MediaDeleteLogic.kt`、`MediaDeleteLogicTest.kt`、`docs/_r51_media_delete_logic.md`；未改任何既有源码/测试/配置/文档 |

---

## 落盘日志（分步）
[DONE] S0 现状核对 · 7 个生产类 + 4 份必读材料 + 单测写法逐条带锚点；`WxFileIndex2` 键列 `[需真机]`
[DONE] S1 判据契约 · 6 纯函数 + 2 枚举，四态/三态互斥，默认放行，两套 token 长度分派
[DONE] S2 MediaDeleteLogic.kt · 272 行零 Android 依赖，`MediaDeleteLogic.kt:37-272`
[DONE] S3 单测 · `MediaDeleteLogicTest.kt` 46 例，逐条标注抓哪个错误实现
[DONE] S4 变异验证 · Python 复刻 baseline 46/46 绿，6/6 组语义变异各自变红（2/4/4/1/3/2）
[DONE] S5 报告与三档边界 · 三档（复位逐条）/ 证据三栏 / 盲区 / A-B 接线要点

[DONE] ALL
