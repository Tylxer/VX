# R51 MediaTableGuard 实现报告（候选 B：独立媒体表 delete 拦截钩子）

> Lane：独占 Pro / Hook 链路实现
> 定性：用户已授权「开始做被撤回图片可打开」；阶段 0 决定性真机取证由主会话并行进行（A/B 未拍板），本 lane 实现**候选 B：新增独立媒体表 delete 拦截钩子**，补齐静态已确认缺口「`ImgInfo2`/`videoinfo2`/`WxFileIndex2` 删除零拦截」。
> 硬约束：不装包 / 不碰真机 / 不跑 gradle / 不改现网开关默认值 / 不写微信库。改动仅限 4 个源码文件 + 本报告。
> 以磁盘源码现状为准；不确定一律标 `[推断]` / `[需真机]`。

## 分步清单
- [x] S0 现状核对（7 类真实签名 / `MediaDeleteLogic` 全部纯函数签名 / 宿主类解析方式 / 开关写法，带 `文件:行号`）
- [x] S1 钩子设计（挂哪两个方法 / before 顺序 / 与 `VoiceGuard`/`SnsGuard` callback 是否冲突 / `execSQL` void 跳过写法与 `[需真机]`）
- [x] S2 `MediaTableGuard.kt` 实现说明（token 集合来源锚点 / try-catch 兜底 / 限流封顶值 / TAG）
- [x] S3 接线与开关（逐文件 diff 行数 / 为什么默认 false 现网零影响）
- [x] S4 三档边界表（进入 / 返回 / 复位，复位逐条分列）
- [x] S5 回滚路径
- [x] S6 装包前复核要点（5~8 条）+ 证据分级三栏 + 盲区

## 证据分级（三栏）

| 已证事实（磁盘源码 / 已落盘文档，带锚点） | 静态推断 `[推断]` | 必须真机 `[需真机]` |
|---|---|---|
| 7 个生产类真实签名（S0.1）；`MediaDeleteLogic` 7 个纯函数签名（`:145,157,198,216,226,241,267`）；`TARGET_TABLES` 不含 `voiceinfo`（`:64`） | install 期反射 `VoiceFileGuard.protectedMedia` private 字段可取到 Map 引用；字段名 `protectedMedia` 未混淆 | 微信 8.0.48 撤回删媒体表**走 `delete()` / `execSQL` / 预编译 `SQLiteStatement`**（`_r49_revokeimg_reference.md:90` 未取证） |
| wcdb `SQLiteDatabase` **无 `deleteWithOnConflict`**（`_r49_revokeimg_reference.md:58,69-86`）⇒ 死代码，不挂 | 多个 `XC_MethodHook` 并存时 before 依次执行；某 callback 置 `param.result` 后其余 before 仍跑完；注册顺序由 `HookEntry` 增量行决定 | `param.result = null` 对 void `execSQL` 是否确实跳过方法体；跳过是否引发微信重试 / 抛异常；是否与 `SnsGuard.sqlGuard` 改写 `param.args[0]` 顺序耦合 |
| `execSQL` 返回 `void`（`_r49_revokeimg_reference.md:215`、`_r51_media_delete_logic.md:399`）⇒ `param.result=1` 语义不适用 | `delete()` 入口置 `result` 即跳过纯 Java 方法体（`VoiceGuard.kt:125-132,186` 已证，属已证而非推断） | `ImgInfo2` 行到底被没被删（Q3 阶段 0，`docs/_r49_revokeimg_reference.md:94-147` 的 2×2 只读判据） |
| `VoiceFileGuard` 无公开 token 集合接口（`protectedMedia` private `:109`、`mediaHit` private `:169`、`stat()` 仅 size `:233-238`） | `WINDOW_MS=600_000L` 与 `VoiceFileGuard.PROTECT_MEDIA_MS`（`:112`）保持同步；`nowMs`/`tokenIssuedMs` 统一 `elapsedRealtime()` 满足单位纪律（`:400`） | `WxFileIndex2` 键列（`MediaDeleteLogic.kt:89-94` 明写待定）；撤回删媒体表 `whereClause` 真实形态（单条 `=` / `IN (SELECT)` / 泛型 `%s=%s`） |
| `File.delete` 已由 `VoiceFileGuard.kt:251-252,306` 全进程拦（图片/视频/语音共用），不重复实现 | `fromExecSql()` 只认最简 `DELETE FROM <单表> WHERE <where>` 形态足够覆盖撤回场景 | 拦住 DB 行后 **UI 是否真显示大图**（`ImgInfo2` 行幸存性是 `[推断]`）；冷启后能否打开；A/B 路线拍板 |
| 掘金 coder-pig 外部实验（只拦 `File.delete` 打不开 / 拦媒体表 delete 后能打开，`_r49_revokeimg_reference.md:100`）——**转引，非本设备实测** | 开关动态生效路径：`kvset` 的 `EntryReceiver.kt:176-200` 不含本类 ⇒ 靠 `record()→invalidate()→refresh()` 或进程重启 | token 反射在真机是否被安全策略/混淆拦截（失败会打 `pool=reflect-failed` 并安全降级为只记账） |
| Q7 三档边界与复位逐条（`_r49_revokeimg_reference.md:265-295`）；`CLAUDE.md:12,58,65-76,81-105` 规则 | Kotlin 侧编译 / JVM 单测**未验证**（本 lane ⛔ 未跑 gradle，由主会话统一执行） | 编译结果 `assembleDebug` / `testDebugUnitTest`（主会话统一执行） |

## 盲区

- ⛔ **native unlink**：`_r21c_voice_file.md:162` 语音侧假设「native unlink」被 `:175-176` 证伪，但**图片侧未单独取证**；`File.delete` hook 拦不到的删除无副本可还原。
- ⛔ **`INSERT OR REPLACE` 旁路**：`AntiRevoke.kt:395-400` 记的覆盖原行不在任何 delete/execSQL 钩子范围。
- ⛔ **微信是否真走 `execSQL` 删媒体表**：`_r49_revokeimg_reference.md:90` 明写未取证；若真机证实走预编译 `SQLiteStatement.executeUpdateDelete`，Q5.2 `:209` 才考虑补挂 statement 层（本轮未挂）。
- ⛔ **UI 是否显示**：拦住 DB 行 ≠ 界面能渲染大图；呈现层/缓存/点开时二次删除（`docs/_r49_revokeimg_reference.md:140`）不在本 lane 范围。
- ⛔ **微信重试删除时机**：窗口外 / 冷启后池空的重试无人拦，图片侧无 backup/restore ⇒ 永久丢失。
- ⛔ **A/B 路线未拍板**：阶段 0 决定性真机取证由主会话并行进行；本 lane 只实现候选 B，不声称 B 一定当选。

[START]

---

## S0 现状核对（磁盘源码为准，逐条带 `文件:行号`）

> 所有 `.kt` 路径前缀省略 `wxgroup/app/src/main/java/com/lx/wxgroup/`。

### S0.1 七个生产类 / 关键类的真实签名

| # | 类（`object` 声明） | 真实签名与关键锚点 |
|---|---|---|
| 1 | `feature/VoiceGuard.kt` | `object VoiceGuard` `:51`；`install(lpparam: XC_LoadPackage.LoadPackageParam)` `:113`；`invalidate()` `:103-105`（仅 `gen++`）；`refresh()` `:146-149`（`enabled = Kv.getBool(Keys.ANTIREVOKE_MSG)`）；`cfg()` `:151-154`（3s TTL 刷新，⛔ 内部会查库）；`deleteGuard = object : XC_MethodHook()` `:156`，`beforeHookedMethod` `:157-161` **全程 `runCatching` 包裹**；`onDelete(param)` `:169-210`；`FAKE_ROWS = 1` `:74`、`param.result = FAKE_ROWS` `:186`；`hookAllMethods(cls,"delete",deleteGuard)` `:136`；`deleteWithOnConflict` `:133-135`（⛔ 死代码，`wcdb` 无此方法，`docs/_r49_revokeimg_reference.md:69-86`）；`WINDOW_MS=60_000L` `:61`、`CACHE_TTL_MS=3_000L` `:71`、`LOG_TTL_MS=60_000L` `:109`、`gen` `:94`/`cachedGen` `:90`；表判据 `table.equals("voiceinfo", ignoreCase=true)` `:173-174`；whereArgs 按位置取 `a[2] as? Array<*>` `:177-180` |
| 2 | `feature/SnsGuard.kt` | `install(lpparam)` `:132`；`hookAllMethods(cls,"delete",deleteGuard)` `:149`、**`hookAllMethods(cls,"execSQL",sqlGuard)` `:152`**（已证可挂）；`sqlGuard.beforeHookedMethod` `:222-225` 同款 `runCatching` 包裹；`onExecSql` 处理 `execSQL(String sql)` / `execSQL(String sql, Object[] bindArgs)` `:840-842`；R42 走 `param.args[0] = out` **改写 SQL** `:916`（`SnsSqlRewrite.rewrite` `:77-85`，⛔ 本 lane 不改 `SnsSqlRewrite.kt`） |
| 3 | `data/MediaDeleteLogic.kt` | `object MediaDeleteLogic` `:58`；枚举 `DeleteClass{BLOCKABLE, BULK_OR_UNSAFE, OTHER_TABLE, UNKNOWN}` `:37-49`、`Verdict{NO_EVENT, HOOK_OK_BUT_NOT_MATCHED, REAL_EVENT}` `:52-56`；`EPOCH_MS_MIN = 1_000_000_000_000L` `:61`；`TARGET_TABLES = {imginfo2, videoinfo2, wxfileindex2}` `:64`（**不含 voiceinfo**）；`SINGLE_EQ` `:110-112`、`UNSAFE` `:102-104`；⛔ **只读复用，严禁改此文件**（㉗ lane 已交付） |
| 4 | `feature/RevokeRescue.kt` | `object RevokeRescue` `:42`；`record(msgId: Long, talker: String, revokeContent: String?, extra: String? = null): Boolean` `:78`；`if (ok) VoiceGuard.invalidate()` `:109`；**图片/视频分支 `:121-128`**：`VoiceFileGuard.protectMedia(orig.imgPath)` `:122` + `auditImage(msgId, orig.imgPath)` `:126`；`OrigRow.imgPath` `:160-165`；语音三层 `:129-144` |
| 5 | `HookEntry.kt` | import 区 `:24-43`（逐个显式 import，无通配）；`VoiceGuard.install(lpparam)` `:222-224`（包在 `runCatching{}` 里）；`VoiceFileGuard.install()` `:232-234`；`SnsGuard.install(lpparam)` `:240-242` |
| 6 | `data/Keys.kt` | `data class PrefSpec(key,kind,def,domain,title,min?,max?,unit?,hidden?)` `:26-34`；`const val ANTIREVOKE_MSG = "antirevoke.msg"` `:140`，防撤回域常量区 `:139-197`；`object Prefs` `:389`；`val ALL: List<PrefSpec> = listOf(` `:416`；防撤回域 PrefSpec `:454-476`（`PrefSpec(Keys.ANTIREVOKE_MSG, PrefKind.SWITCH, "false", "防撤回", "防止消息撤回")` `:455`）；`defaultOf(key)` `:567`；`spec(k)` `:564` |
| 7 | `feature/VoiceFileGuard.kt` | `object VoiceFileGuard` `:50`；`protected (语音池)` `:70`；**`private val protectedMedia = ConcurrentHashMap<String, Long>()` `:109`**；`PROTECT_MEDIA_MS = 600_000L`（10 分钟 TTL）`:112`；`fun protectMedia(token: String?, ttlMs: Long = PROTECT_MEDIA_MS)` `:122-160`（`MediaGuardLogic.hexToken` 归一 `:129`）；`private fun mediaHit(path, now)` `:169-170`；`fun shouldBlock(path, now): Boolean` `:222-230`（**语音专用**）；`fun stat(): String` `:233-238`（⛔ **只返回 `mediaPool=<size>`，不暴露 token 集合**）；`MediaGuardLogic.anyAlive` 复位 `hasMedia` `:301`；`File.delete/deleteOnExit` 全进程 hook `:251-252`，`param.result=true` `:306` |

补充只读类：`data/MediaGuardLogic.kt` `MIN_TOKEN=32` `:41`、`MD5_LEN=32` `:55`、`hexToken(raw, minLen=32): String?` `:80-100`、`anyAlive(pool, now)` `:110-113`、`mediaHit` `:125-135`；`data/VoiceGuardLogic.kt` ⛔ 保守性红线 `:23-26,145-157`；`data/Kv.kt` `getBool(key)` `:35-38`（→ `GroupDb.getPref`，查模块自有库）；`util/LogX.kt` `TAG="WXGroup"` `:19` + 1s dedup `:21,68-94` + `MAX_LINES=5000` 环形覆盖 `:22,83-88`（⛔ 项目惯例靠**消息前缀** `VG `/`VFG `/`SNS ` 供 `dev.py q` 过滤，本 lane 用 `MTG `）；`entry/EntryReceiver.kt` `kvset` 分支 `:176-200` 显式列举 `AntiRevoke/SnsGuard/ConvFilter/MomentsAuto/MomentsExport/HintBadge.refresh()`，**不含 MediaTableGuard**。

### S0.2 `MediaDeleteLogic` 全部纯函数真实签名（⛔ 别照抄 prompt 示意名）

| 函数 | 真实签名 | 行号 |
|---|---|---|
| `isTargetTable` | `(table: String?): Boolean` | `:145` |
| `classifyDelete` | `(table: String?, whereClause: String?, whereArgs: Array<out Any?>?, protectedTokens: Set<String> = emptySet()): DeleteClass` | `:157` |
| `extractTokens` | `(table: String?, whereClause: String?, whereArgs: Array<out Any?>?): Set<String>` | `:198` |
| `looksLikeEpochMillis` | `(v: Long): Boolean` | `:216` |
| `isRevocationWindow` | `(nowMs: Long, tokenIssuedMs: Long, ttlMs: Long): Boolean` | `:226` |
| `shouldBlock` | `(table: String?, whereClause: String?, whereArgs: Array<out Any?>?, protectedTokens: Set<String>, nowMs: Long, tokenIssuedMs: Long, ttlMs: Long): Boolean` | `:241` |
| `verdict` | `(seen: Long, matched: Long): Verdict` | `:267` |

⛔ `protectedTokens` 空集 = **纯形状诊断模式**；非空 = **完整判定模式**（`BLOCKABLE` 还要求 token 命中集合，`:184-187`）。`shouldBlock` `:241-260` 已内部串起 `isTargetTable`→`isRevocationWindow`→`classifyDelete`→`extractTokens`，是唯一「要不要拦」出口。

### S0.3 宿主类解析方式 / 开关写法 / 硬约束复核

- **宿主类解析**：`XposedHelpers.findClass("com.tencent.wcdb.database.SQLiteDatabase", lpparam.classLoader)` 包 `runCatching{}.getOrNull()`，`null` ⇒ `LogX.w(...)` + `return`（`VoiceGuard.kt:114-120`）；挂法固定 `XposedBridge.hookAllMethods(cls, "delete"/"execSQL", guard)` + `.onSuccess{ if(it.isNotEmpty()) ok += ... }` + `.onFailure{ LogX.e(...) }`（`VoiceGuard.kt:133-143`、`SnsGuard.kt:149-154`）。
- ⛔ **不挂 `deleteWithOnConflict`**：wcdb 无此方法（`docs/_r49_revokeimg_reference.md:58,69-86`；`VoiceGuard.kt:125-132` 自述死代码）。
- **开关**：`Kv.getBool(key)`（`Kv.kt:35-38`），默认值只在 `Prefs.ALL` 定义一次（`Kv.kt:10-11`、`Keys.kt:567`）；⛔ `Kv.getBool` 会经 `GroupDb.getPref` **查模块自有库**，故按硬约束「before 内严禁查库」**不能**在 before 里调用，必须缓存为 `@Volatile enabled` 字段。
- ⛔ **`execSQL` 返回 `void`**：`param.result = 1` 伪装受影响行数不适用（`docs/_r49_revokeimg_reference.md:215`、`docs/_r51_media_delete_logic.md:399`）；跳过方法体用 `param.result = null`（void 方法），写法 `[需真机]`。
- **`docs/_r51_media_delete_logic.md` 接线要点**：`:408` B 路线「先最便宜 `isTargetTable`，再 `shouldBlock(...)`；两个 `delete` 回调靠白名单互斥；`RevokeRescue.record()` 台账成功后调新钩子 `invalidate()`」；`:409`「`protectedTokens` 由『已救消息 + 撤回期』集合提供，`nowMs/tokenIssuedMs/ttlMs` 统一毫秒口径」；`:400` 单位纪律（统一 `elapsedRealtime()` 或 13 位 ms）。
- **Q5.2 候选 B 表** `docs/_r49_revokeimg_reference.md:204-216`：新增 `feature/MediaTableGuard.kt` 挂 `delete` + `execSQL`；`HookEntry.kt:223` 之后加 1 行；`RevokeRescue.kt:122` 里加 `MediaTableGuard.invalidate()`（**无参**，同 `VoiceGuard.invalidate()` 范式）；`:214` 两个 delete 回调靠表名白名单天然互斥；`:215` execSQL 热路径最便宜判断最先做、void 写法 `[需真机]`、`File.delete` 已由 `VoiceFileGuard` 全进程拦不重复实现；`:216` 回滚路径。
- **Q7 三档边界** `docs/_r49_revokeimg_reference.md:265-295`（7.1 主表 / 7.2 复位逐条 / 7.3 残留总表）；**Q9/必读**：`docs/_r49_revokeimg_forensics.md:29,83-86,118-120,132-140,148-157`（`AntiRevoke.kt:461` 消息到达时**提前入池**）、`docs/_r21c_voice_file.md:16,115,175-193`（`File.delete` hook 真机有效、36ms 时间线）、`CLAUDE.md:12,58,65-76,81-105`（昵称 / `dev.py q` / 真机纪律 / `installed` 验挂 / 派发规则）。

### S0.4 ⚠️ 磁盘现状下的关键取舍（标 `[推断]`）

`VoiceFileGuard` **没有任何公开的 token 集合只读接口**：`protectedMedia` 是 `private`（`:109`）、`mediaHit` 是 `private`（`:169`）、`stat()` 只返回 size（`:233-238`）、`shouldBlock` 是语音专用（`:222`）；`RevokeRescue` 也无公开 token 集合（Grep 全文件仅 `record()` 与 `:189` 的 `LinkedHashMap` 快照，无池）。而本 lane ⛔ 严禁改 `VoiceFileGuard.kt` / 严禁在 `RevokeRescue.kt` 加超过 1 行 / `EntryReceiver.kt` 不在允许写入清单。
⇒ 按任务给的 fallback「用 `RevokeRescue` 已记录的 token 集合」，且 `Q5.2:211` 明确要求 `invalidate()` **无参**，本实现选择：**`MediaTableGuard` 在 `install()` 期一次性反射拿 `VoiceFileGuard.protectedMedia` 的 Map 引用（非热路径、只读），在 `invalidate()` 时把「存活 token 的 key」快照进自己的进程内缓存**（= `RevokeRescue` 经 `protectMedia` 记录的那份集合的只读快照）。反射失败 ⇒ 缓存恒空 ⇒ `shouldBlock` 恒 false ⇒ **只记账、永不拦**（安全降级）。⛔ 不写微信库、不查库、不在 before 里反射/IO。此选择标 `[推断]` + `[需真机]`，S2/S6 会再列备选与复核点。

[DONE] S0 · 现状核对完成：7 类真实签名 / `MediaDeleteLogic` 7 个纯函数签名（`MediaDeleteLogic.kt:145,157,198,216,226,241,267`）/ 宿主类解析（`VoiceGuard.kt:114-143`、`SnsGuard.kt:152`）/ 开关写法（`Keys.kt:455`、`Kv.kt:35`）逐条带锚点；`VoiceFileGuard` 无公开 token 集合接口是磁盘硬事实（`:109,169,233`），token 来源方案已标 `[推断]`。

---

## S1 钩子设计

### S1.1 挂哪两个方法

| 方法 | 挂法 | 范式锚点 |
|---|---|---|
| `com.tencent.wcdb.database.SQLiteDatabase.delete` | 方法级 `XposedBridge.hookAllMethods(cls, "delete", deleteGuard)` | `VoiceGuard.kt:136`（已证可挂、且 `delete()` 纯 Java，入口置 `param.result` 即跳过方法体 `:125-132`） |
| `com.tencent.wcdb.database.SQLiteDatabase.execSQL` | 方法级 `XposedBridge.hookAllMethods(cls, "execSQL", execSqlGuard)` | `SnsGuard.kt:152`（已证可挂，覆盖 `execSQL(String)` / `(String,Object[])` `:840-842`） |

⛔ **不挂 `deleteWithOnConflict`**：wcdb 无此方法 ⇒ 死代码（`docs/_r49_revokeimg_reference.md:58,69-86`；`VoiceGuard.kt:125-135`）。
⛔ 两个 guard 各自 `beforeHookedMethod` **整个逻辑包在 `try/catch(Throwable)` 里**，catch 内只打节流日志、绝不把异常抛回宿主（否则「回调抛异常会阻断后续 callback」可能破坏现网 R42 / 语音拦截，同款 `runCatching` 见 `VoiceGuard.kt:158-160`、`SnsGuard.kt:216-217,223-224`）。
`install()` 找不到宿主类 / 两个 hook 都挂空 ⇒ 打明确失败日志 + `return`，不抛异常（`VoiceGuard.kt:117-120,139-142`）。

### S1.2 before 顺序（最便宜 → 最贵；⛔ 热路径零查库/零反射/零文件 IO/零高频日志）

1. **`if (!enabled) return`** —— 开关关时**唯一一个 boolean 判断**，零额外动作（无字符串解析、无对象分配、无日志）。`enabled` 是 `@Volatile` 内存字段，**不在 before 里调 `Kv.getBool`**（那会查库，`Kv.kt:35-38`）。
2. `try {` 包住以下全部逻辑。
3. `seen++`（只读账本，仅 enabled 时计；关时因第 1 步直接 return 不增长）。
4. **取参**：`delete` ⇒ `a[0]=table`、`a[1]=whereClause`、`a[2]=whereArgs`（按位置取，同 `VoiceGuard.kt:169-180`；wcdb 有 `String[]`/`Object[]` 两种重载，`MediaDeleteLogic` 形参是 `Array<out Any?>?`，直接传 `Array<*>` 零拷贝）；`execSQL` ⇒ 从 `a[0]` 裸 SQL **只读解析**出 `(table, where, args)`（⛔ 不修改 SQL 原文、不改 `param.args`、不生成/拼接 SQL），`a[1]` 的 `Object[]` 作为 `whereArgs`。
5. **`MediaDeleteLogic.isTargetTable(table)`** —— 最便宜判据先行（`:145`、`:163`）；非白名单（含 `voiceinfo`、`null`）⇒ 直接放行，天然与 `VoiceGuard` 互斥。
6. 命中 ⇒ `matched++`；未命中 ⇒ return（账本其余项不动）。
7. **进程内 token 快照为空 ⇒ `skipped++` + return**（不确定一律放行，`MediaDeleteLogic.shouldBlock:251`）。
8. **`MediaDeleteLogic.shouldBlock(table, where, args, cachedTokens, nowMs, lastIssuedMs, WINDOW_MS)`**（`:241-260`，内部已串 `isTargetTable` / `isRevocationWindow` / `classifyDelete` / `extractTokens`）—— ⛔ feature 层**不重写任何判据**，只负责准备 `protectedTokens` 与统一毫秒口径的时钟（`:409`、`:400`）。
9. `false` ⇒ `skipped++` + return（保守性红线：`whereClause==null` 删全表 / `<` `>` `LIKE` `IS NULL` / `IN (SELECT …)` / 键列未取证 / token 抽不出 / 窗口外，**一律放行**，`MediaDeleteLogic.kt:14-22,165-188`；`VoiceGuardLogic.kt:23-26,145-157`）。
10. `true` ⇒ **拦**：`delete` 用 `param.result = 1`（同 `VoiceGuard.kt:186` 伪装成功、跳过方法体）；`execSQL` 用 `param.result = null`（void，`[需真机]`）。`blocked++` + 60s 节流日志。

⛔ 全程**不改 `ContentValues` / SQL 原文 / `param.args` / 任何 DB 返回值**（除「跳过删除」这一目的本身）；**不新增** insert/update/delete/execSQL 调用；不写微信库。

### S1.3 与 `VoiceGuard` / `SnsGuard` 既有 callback 是否冲突

| 方法 | 并存 before callback | 互斥依据 |
|---|---|---|
| `delete` | `VoiceGuard.deleteGuard`（`VoiceGuard.kt:136`）、`SnsGuard.deleteGuard`（`SnsGuard.kt:149`）、本 lane `deleteGuard` | `VoiceGuard` 表判据只认 `voiceinfo`（`:173-174` / `VoiceGuardLogic.kt:38`）；`SnsGuard` 只服务朋友圈独立库 `SnsInfo/SnsComment`（`docs/_r49_revokeimg_forensics.md:52`）；本 lane `TARGET_TABLES={imginfo2,videoinfo2,wxfileindex2}`（`MediaDeleteLogic.kt:64`）**不含 voiceinfo** ⇒ 三者交集为空，不会重复拦/重复计数（`docs/_r49_revokeimg_reference.md:214`、`_r51_media_delete_logic.md:401`） |
| `execSQL` | `SnsGuard.sqlGuard`（`SnsGuard.kt:152`）、本 lane `execSqlGuard` | `SnsGuard.sqlGuard` 只在 SQL 含 `SnsInfo` 且 `& -9` 时改 `param.args[0]`（`:916`）+ 计数；本 lane 解析**只读**、且只在 `isTargetTable` 命中后才动作 ⇒ 与 R42 写时拦截**零交集** |

- Xposed/LSPosed 允许多个 `XC_MethodHook` 挂同一方法，before 依次执行；`[推断]` 某 callback 置 `param.result` 后 `returnEarly=true` 会阻止原方法体，但**其余 before callback 仍会跑完** ⇒ 本 lane 必须用 try/catch 保证不抛异常打断后续 callback（`[需真机]` 复核此顺序语义）。
- 注册顺序：本 lane `install` 加在 `VoiceGuard.install`（`HookEntry.kt:223`）之后、`SnsGuard.install`（`:241`）之前 ⇒ 本 lane 的 `delete`/`execSQL` callback 先于 `SnsGuard.sqlGuard` 注册（`[推断]`）。
- `File.delete` 已由 `VoiceFileGuard.kt:251-252,306` 全进程拦（图片/视频/语音共用），**本 lane 不重复实现**。

### S1.4 `execSQL` void 的跳过写法（`[需真机]`）

- ⛔ `execSQL` 返回 `void`（`docs/_r49_revokeimg_reference.md:215`；`_r51_media_delete_logic.md:399`）⇒ `delete()` 那套 `param.result = 1`「伪装受影响行数」语义**不适用**（void 无返回值可伪装，`SnsSqlRewrite.kt:16-21` 亦说明 void 在 Java 侧「成功」不可靠返回值区分）。
- 本实现在 before 命中时 **`param.result = null`**：Xposed 由 `setResult` 置 `returnEarly`，原 `execSQL` 方法体不执行，void 忽略该返回值。
- 备选 `XC_MethodReplacement`（`:215` 提到）：⛔ **不采用** —— 它无条件替换整个方法、无法按判据条件化，且会覆盖 `SnsGuard.sqlGuard` 的识别/计数。
- ⛔ `[需真机]` 待验 4 条：① LSPosed 对 void 方法 `param.result = null` 是否确实跳过方法体（且不抛类型转换异常）；② 跳过 `execSQL` 后微信是否重试删除或抛异常（同 `VoiceGuard.kt:36-37` 的重试风险）；③ 8.0.48 撤回删媒体表**究竟走 `delete()` / `execSQL` / 预编译 `SQLiteStatement`**（`_r49_revokeimg_reference.md:90` 明写未取证，Q5.2 `:209` 通常不必挂 statement 层）；④ 本 lane 置 `result` 与 `SnsGuard.sqlGuard` 改写 `param.args[0]` 是否有 callback 顺序耦合。
- ⛔ `execSQL` 裸 SQL 保守解析：只认 `DELETE FROM <单表> WHERE <where>` 最简形态；`whereClause==null`（删全表）/ 含 `;`（多语句）/ 解析不出 ⇒ **一律放行**；`where` 里的 `IN (SELECT…)` / `LIKE` / `<` `>` / `AND`/`OR` / 多值由 `MediaDeleteLogic.UNSAFE`（`:102-104,169`）判 `BULK_OR_UNSAFE` 放行，`WxFileIndex2` 键列未取证 ⇒ `UNKNOWN` 放行（`MediaDeleteLogic.kt:89-94`）。

[DONE] S1 · 挂 `delete` + `execSQL` 两个方法级 hook（范式 `VoiceGuard.kt:136` / `SnsGuard.kt:152`）；before 顺序 10 步、关时仅 1 个 boolean 判断；三 callback 靠表名白名单天然互斥；`execSQL` void 用 `param.result = null` 跳过并标 4 条 `[需真机]`；不挂 `deleteWithOnConflict` 死代码。

---

## S2 `feature/MediaTableGuard.kt` 实现说明（新建，358 行）

### S2.1 token 集合来源（锚点 + `[推断]`）

| 项 | 实现 | 锚点 |
|---|---|---|
| 选的接口 | ⛔ `VoiceFileGuard` **无**公开 token 集合只读接口（`protectedMedia` private `:109`、`mediaHit` private `:169`、`stat()` 仅 size `:233-238`、`shouldBlock` 语音专用 `:222`）；`RevokeRescue` 亦无公开池。⇒ 采用任务 fallback「用 `RevokeRescue` 已记录的 token 集合」 | S0.4 |
| 取法 | `install()` 里 `resolvePool()` **一次性反射** `VoiceFileGuard::class.java.getDeclaredField("protectedMedia")` + `isAccessible=true` + `f.get(VoiceFileGuard) as? Map<String, Long>`，全程 `runCatching`；**非 before 热路径** | `MediaTableGuard.kt:resolvePool()`；`VoiceFileGuard.kt:109` |
| 快照 | `invalidate()` 里 `snapshotLiveTokens()` 遍历该 Map，只收 `v >= now`（未过期）的 key，`lowercase()` 归一，存入 `@Volatile cachedTokens: Set<String>`；before 只读该快照，**零分配/零反射/零 IO** | `MediaTableGuard.kt:invalidate()/snapshotLiveTokens()`；`MediaGuardLogic.hexToken` 归一 `:99`；`MediaDeleteLogic.extractTokens` lowercase `:135` |
| 同一份集合 | `VoiceFileGuard.protectMedia(orig.imgPath)`（`RevokeRescue.kt:122`）与 `AntiRevoke.anchorImage`（`:461`，消息到达提前入池）写入的就是 `protectedMedia`；撤回时 `record()` 图片分支会再 `protectMedia` 一次，新增 `invalidate()` 排在它**之后** ⇒ 快照必含本次 token | `RevokeRescue.kt:121-128`、`docs/_r49_revokeimg_forensics.md:83-86` |
| 降级 | 反射失败（字段改名/混淆/安全限制）⇒ `mediaPoolRef=null` ⇒ `cachedTokens` 恒空 ⇒ `MediaDeleteLogic.shouldBlock:251` 恒 false ⇒ **只记账、永不拦**，`installed` 日志带 `pool=reflect-failed` | `MediaTableGuard.kt:resolvePool()` / `onBefore()` |
| ⛔ 不写库 | 全程只读内存 Map；不查 `GroupDb`、不查微信库、不新增任何 insert/update/delete/execSQL 调用；不改 `ContentValues` / SQL 原文 / `param.args` | 全文 |
| `[推断]` 备选 | 若主会话认定反射 private 字段不可接受：改为 `MediaTableGuard` 自建进程内池 + `invalidate(token: String? = null)` 带默认参数、`RevokeRescue` 那 1 行改 `MediaTableGuard.invalidate(orig.imgPath)`（仍 1 行、只加不改），由 `MediaGuardLogic.hexToken` 归一入池。⛔ 但 `Q5.2:211` 明确要求 `invalidate()` **无参**，故本轮未采用 | 见 S6 复核要点 |

### S2.2 判据全部下移，feature 层零重写

`onBefore()` 只调用 `MediaDeleteLogic.isTargetTable` / `shouldBlock`；`stat()` 调用 `MediaDeleteLogic.verdict(seen, matched)`。`classifyDelete` / `extractTokens` / `isRevocationWindow` 由 `shouldBlock`（`:241-260`）内部串起 —— ⛔ 本文件**没有任何**表名白名单、token 长度正则、窗口算术、SQL 形态判据。`fromExecSql()` 只是把裸 SQL **只读拆参**成 `(table, where, args)` 三元组喂给纯判据，不生成/拼接 SQL。

### S2.3 try/catch 兜底（装包前复核重点）

`deleteGuard` / `execSqlGuard` 的 `beforeHookedMethod` 各包一层 `try/catch(Throwable)`，`onBefore()` **整个逻辑再包一层** `try/catch(Throwable)`，catch 内用 `runCatching { throttledLog(...) }` 包住节流日志 ⇒ **双层保险、绝无异常外泄**。同款范式 `VoiceGuard.kt:158-160`、`SnsGuard.kt:216-217,223-224`；理由：回调抛异常会阻断同方法上的后续 callback，可能破坏现网 R42 写时拦截 / 语音拦截。

### S2.4 开关 / 缓存 / 账本 / 限流 / TAG

| 项 | 值 / 实现 |
|---|---|
| 开关 | `Keys.ANTIREVOKE_MEDIA_DELETE`（新增，默认 `"false"`）；`enabled` 为 `@Volatile` 内存字段；`refresh()` 调 `Kv.getBool` **只在 `install()` / `invalidate()`**（非 before，因 `Kv.kt:35-38` 会查库） |
| `invalidate()` | `gen++` + `lastIssuedMs = elapsedRealtime()` + 重建 `cachedTokens` 快照 + `refresh()`；与 `VoiceGuard.invalidate()`（`:103-105`）同款「清缓存 / gen 自增」语义。`[推断]`：`kvset` 的 `EntryReceiver.kt:176-200` 不含本类，开关动态生效靠撤回时 `record()→invalidate()→refresh()` 或进程重启 |
| 窗口时钟 | `nowMs` 与 `tokenIssuedMs` 统一 `SystemClock.elapsedRealtime()`，`WINDOW_MS = 600_000L`（与 `VoiceFileGuard.kt:112` 一致，`[推断]` 需同步）；符合 `_r51_media_delete_logic.md:400` 单位纪律 |
| 账本 | `seen` / `matched` / `blocked` / `skipped` 四个 `AtomicLong`（进程内、冷启清零）；`verdict(seen,matched)` 三态由 `MediaDeleteLogic:267` 给出；`skipped` 口径 = **命中目标表后**按保守判据放行（非白名单表/解析失败不计入）；⛔ 不写微信库 |
| 去重 / 封顶 | `events: ConcurrentHashMap<String,Long>` 按 `形如|表|where前缀` 去重、封顶 `MAX_EVENTS=64`（超出只留累计计数）；`logAt` 60s（`LOG_TTL_MS`）按 key 节流、封顶 `MAX_LOG_KEYS=64`；`LogX` 另有 1s dedup（`:21,68-94`）+ `MAX_LINES=5000` 环形覆盖（`:22,83-88`）双保险 |
| TAG | `MTG`（消息前缀）；`installed` 一行 `MTG installed hooks=delete,execSQL enabled=<值> pool=ok|reflect-failed switch=antirevoke.media_delete`，`dev.py q 'MTG'` 可验挂（`CLAUDE.md:75-76`）。⛔ `LogX` 的 logcat TAG 固定 `WXGroup`（`:19`），项目惯例靠消息前缀区分（`VG `/`VFG `/`SNS `） |
| `stat()` | 一行字符串含 `enabled/gen/seen/matched/blocked/skipped/verdict/pool/tokens/events/switch`，供广播导出（M3 本轮不接，见 S3） |

### S2.5 拦下时的唯一副作用

- `delete`：`param.result = FAKE_ROWS(1)`（同 `VoiceGuard.kt:186`，入口置 result 即跳过纯 Java 方法体）。
- `execSQL`：`param.result = null`（void，`[需真机]`）。
- ⛔ 除此之外**不改** `ContentValues` / SQL 原文 / `param.args` / 任何 DB 返回值；不新增任何 DB 调用；不写日志到高频路径（命中才打、且节流）。

[DONE] S2 · `MediaTableGuard.kt` 新建完成；token 集合 = install 期一次性反射 `VoiceFileGuard.protectedMedia`（`:109`）+ invalidate 期存活 key 快照，反射失败降级为「只记账永不拦」（`[推断]`）；双层 try/catch 兜底；账本 4 计数 + `verdict` 三态 + `events≤64` 去重 + 60s 日志节流 + `MAX_LOG_KEYS≤64` 封顶；TAG `MTG`；判据零重写。

---

## S3 接线与开关（逐文件 diff 行数）

### S3.1 逐文件 diff（只读 `git diff` 复核，2026-11-10）

| 文件 | 本 lane 改动 | diff 行数 | 锚点 |
|---|---|---|---|
| **新建** `feature/MediaTableGuard.kt` | 整个功能类（`object MediaTableGuard`） | 新建 ~300 行 | S2 |
| 改 `HookEntry.kt` | ① `import com.lx.wxgroup.feature.MediaTableGuard`；② `VoiceGuard.install(lpparam)` 之后 1 行 `MediaTableGuard.install(lpparam)`（复用既有 `runCatching{}` 块，⛔ 只加不改） | **+2 行** | import 区 `:35` 后；注册 `:223` 后（`git diff` 显示 `@@ -33,6 +33,7` / `@@ -221,6 +222,7`） |
| 改 `feature/RevokeRescue.kt` | 图片/视频分支 `auditImage` 之后 1 行 `MediaTableGuard.invalidate()`（同包无需 import；排在 `VoiceFileGuard.protectMedia` `:122` 之后） | **+1 行** | `:127` 后（`git diff` 显示 `@@ -125,6 +125,7`） |
| 改 `data/Keys.kt` | ① `const val ANTIREVOKE_MEDIA_DELETE = "antirevoke.media_delete"`；② `PrefSpec(Keys.ANTIREVOKE_MEDIA_DELETE, PrefKind.SWITCH, "false", "防撤回", "实验：拦截撤回图片/视频媒体表 delete")` | **+2 行** | 常量 `:140` 后；`Prefs.ALL` 防撤回域 `:455` 后 |
| **新建** `docs/_r51_media_table_guard_impl.md` | 本报告 | 新建 | — |

⚠️ `git diff --stat` 里 `Keys.kt` 共 `20 ++`、`EntryReceiver.kt` / `SnsGuard.kt` / `SnsBlockProbe.kt` / 若干 docs 的改动是**其它并行 lane（R49 阶段二 R-C 写事件探针等）在同一共享工作树的改动，非本 lane 所写**；本 lane 对 `Keys.kt` 只加了上表 2 行，⛔ 未碰 `EntryReceiver.kt` / `SnsGuard.kt` / `SnsBlockProbe.kt` / `SnsSqlRewrite.kt` / `VoiceGuard.kt` / `VoiceFileGuard.kt` / `data/VoiceGuardLogic.kt` / `data/MediaDeleteLogic.kt` / 任何测试与配置。⛔ 未 `git add` / `git commit`。

### S3.2 为什么默认 `false` 时现网零影响（红线自检）

1. **默认关**：`antirevoke.media_delete` 的 `PrefSpec.def = "false"`，与防撤回域其余项一致（`Keys.kt:455` 范式，`Kv.kt:10-11` 默认值只定义一次）；`Kv.getBool` 未设置即 false（`Kv.kt:35-38`）。
2. **before 关时零开销**：`install()` 里 `refresh()` 读到 `enabled=false`；`onBefore()` 第一行 `if (!enabled) return` 是唯一动作 —— 无字符串解析、无对象分配、无日志、无查库、无反射。`seen` 也不增长 ⇒ **账本停止增长**（红线）。
3. **独立于 `antirevoke.msg`**：本类只读 `Keys.ANTIREVOKE_MEDIA_DELETE`，**从不读** `Keys.ANTIREVOKE_MSG`；`refresh()` / `invalidate()` 不触碰 `AntiRevoke` / `VoiceGuard` / `VoiceFileGuard` / `SnsGuard` 的任何字段。设备实测 `antirevoke.msg=true` 的现有防撤回链路（拦 UPDATE、voiceinfo delete、`File.delete` 全进程拦、R42 写时拦截）**开关、判据、`param.result` 全部原样**。
4. **hook 空转但无语义**：`delete`/`execSQL` 上各多一个空转 before callback（一次 volatile 读 + return）；⛔ 不改 `param.result`、不改 `param.args`、不写库、不新增 DB 调用。多个 callback 并存由 Xposed 依次调用，本 lane 双层 try/catch 保证不抛异常阻断后续（S1.3）。
5. **`invalidate()` 接线范围最小**：只在 `RevokeRescue.record()` **图片/视频分支**加 1 行（`:121-128`），文本/语音分支一行未动；`VoiceGuard.invalidate()`（`:109`）原样保留。
6. ⛔ **不改任何既有开关默认值**、不改 `SnsSqlRewrite.kt`、不改 `ContentValues`/SQL 处理；⛔ 不残留任何对微信库的写入。

### S3.3 M3 广播导出：**本轮不做**（按任务 fallback）

`EntryReceiver.kt` **不在**本 lane 允许写入的 4 个源码文件清单内；其 `kvset` 分支（`:176-200`）显式列举 6 个 `refresh()`，`op` 分派（`:156` 起）无 `mediatable`/`AR stat` 现成空分支，加 `op=mediatable sub=stat|reset` 需动共享文件大改 ⇒ 按 M3 约束「若需要大改 `EntryReceiver.kt` 就不做」。
⇒ **stat 暂通过 `dev.py q 'MTG'` 读 installed 日志**（`CLAUDE.md:58,75-76`），导出分支留待真机结论后补；`MediaTableGuard.stat()` 已提供一行字符串，将来 3 行内可接（`LogX.i(MediaTableGuard.stat())`）。

[DONE] S3 · 逐文件 diff：`MediaTableGuard.kt` 新建 / `HookEntry.kt` +2（1 import + 1 注册，复用既有 runCatching）/ `RevokeRescue.kt` +1（图片/视频分支 invalidate）/ `Keys.kt` +2（常量 + `PrefSpec def="false"`）；默认关时 before 仅 1 个 boolean 判断、账本停止增长、与 `antirevoke.msg` 已开启链路零交集；M3 广播导出不做，stat 走 `dev.py q 'MTG'`。

---

## S4 三档边界表（进入 / 返回 / 复位）

> 语境：候选 B、A/B 未拍板、阶段 0 决定性真机取证由主会话并行进行；以下「接线后」预期**全部 `[需真机]` 才能定**，当前 `antirevoke.media_delete` 默认 `false` ⇒ 现网行为**完全不变**。好友一律用昵称：**verity** / **测试群聊** / **文件传输助手**（`CLAUDE.md:12`，不写裸 wxid）。依据 `docs/_r49_revokeimg_reference.md:265-295`、`docs/_r51_media_delete_logic.md:351-382`。

### S4.1 三档主表（⛔ ② 档里「TTL」与「冷启」各自单列，不与「切页回来」合并）

| 档 | 触发 | 发生什么（本 lane + 锚点） | 用户可见 | 状态残留 |
|---|---|---|---|---|
| **① 进入**（刚撤回那一刻） | **verity** 撤回一张图片 | `AntiRevoke.revokeGuard` 拦 `updateWithOnConflict`（`AntiRevoke.kt:371`）⇒ `message` 行保住；`RevokeRescue.record()` 图片分支 `VoiceFileGuard.protectMedia(orig.imgPath)`（`:122`）写 `protectedMedia`（`:109,151`）；**新增 `MediaTableGuard.invalidate()`**（`:127` 后，排在 protectMedia 之后）⇒ `gen++`、`lastIssuedMs=now`、反射池存活 key 快照进 `cachedTokens`、`refresh()` 读开关；微信随后 `delete("ImgInfo2", …)` 或 `execSQL("DELETE FROM ImgInfo2 WHERE …")` 到达 ⇒ `isTargetTable` 命中 → `shouldBlock`（命中 32 位 token + 撤回期窗口）⇒ `param.result=1`（delete）/ `param.result=null`（execSQL void，`[需真机]`）**跳过删除** | 点开该图**理论上能看到大图**（前提：`ImgInfo2` 行与 `image2` 文件都在；**UI 是否真显示 `[需真机]`**） | `protectedMedia` token（10 分钟 TTL）、`hasMedia=true`、`cachedTokens` 快照、`gen`/`lastIssuedMs`、账本 `seen/matched/blocked`、`MTG installed` 日志（验挂）、hook 全挂、**图片侧无备份文件**（`RevokeRescue.kt:121-127`）、微信库行 + `ImgInfo2` 行 + 原文件在 |
| **② 返回**（隔几分钟点开 / 切页回来 / 下拉刷新 / **冷启**） | 纯 UI 重建或进程重启，**不产生新的撤回 UPDATE** | 切页/下拉刷新只读 `message` + `ImgInfo2`；池与 `cachedTokens` 不变、窗口仍成立 ⇒ 若此刻微信重试删媒体表仍被拦；隔几分钟点开（10 分钟 TTL 内）同理 | 隔几分钟点开 / 切页回来 / 下拉刷新**仍能看到**（窗口内） | **`auditImage` 的 Cursor 每次 close()**（`RevokeRescue.kt:286-288`）不残留游标；账本不清零 |
| **②-a TTL（时间维度，单列）** | 撤回后满 10 分钟 | `PROTECT_MEDIA_MS=600_000L`（`VoiceFileGuard.kt:112`）到期 ⇒ `MediaGuardLogic.anyAlive`（`MediaGuardLogic.kt:110-113`）把 `hasMedia` 复位 false（`VoiceFileGuard.kt:301`）；本 lane `WINDOW_MS` 同步到期 ⇒ `isRevocationWindow` false ⇒ `shouldBlock` 恒 false ⇒ **不再拦** | 若此后发生删除（微信 GC/重试）则行被删、图打不开 | ⛔ **与「切页回来」不合并**：池从「保护态」变「裸奔态」；账本仍在、hook 仍在、开关仍开 |
| **②-b 冷启（进程维度，单列）** | 微信进程被重建 | `HookEntry` 重新 `install()` ⇒ `MTG installed` 重新打、`mediaPoolRef` 重新反射；`cachedTokens`/`gen`/`lastIssuedMs`/**账本 4 计数 + `events` + `logAt` 全部清零**；`verdict` 回 `NO_EVENT` | **冷启后能否仍打开 `[需真机]`**（池空，若冷启后微信重新发起删除无人拦） | ⛔ **与 TTL 分列**：TTL 只让池过期、账本与 hook 都在；冷启是进程内集合整体清零（`_r51_media_delete_logic.md:360`、`_r49_revokeimg_reference.md:270`）；持久化态（`rescued` 台账、`image2` 原文件、语音 `voice_bak/`）保留 |

### S4.2 ⛔ ③ 复位档逐条（**不写「同返回」**，逐条写载体与清除条件）

| 复位动作 | 发生什么（本 lane） | 与返回档的本质差异 |
|---|---|---|
| **关 `antirevoke.media_delete`（新开关）** | `refresh()` 只在 `install()`/`invalidate()` 调 ⇒ `[推断]` 关后要等**下一次撤回**的 `record()→invalidate()` 才把 `enabled=false` 刷进内存（`kvset` 的 `EntryReceiver.kt:176-200` 不含本类，无主动 refresh；或进程重启由 install 读）；刷成 false 后 before 第一行 `if (!enabled) return` ⇒ **账本停止增长**、不拦、hook **不卸载**只空转；**池不清空**、旧 token 靠 TTL 自然过期 | 返回档开关/池/hook 都不变；复位档开关翻转 + hook 常驻空转 + 池残留 + 账本停增。⛔ 不残留任何对微信库的写入 |
| **关 `antirevoke.msg`** | `AntiRevoke` 不再拦 UPDATE ⇒ `RevokeRescue.record()` 不触发 ⇒ 图片分支 `protectMedia` 与新增 `MediaTableGuard.invalidate()` 都**不跑** ⇒ 池不新增、`cachedTokens` 不重建、账本不增长；`VoiceFileGuard.on()` 3 秒 TTL（`:185-189`）关后最多 3 秒内仍拦 `File.delete` | 返回档原文与台账都在；复位档原文消失、台账不写、池不新增、账本静止 |
| **保护池 10 分钟 TTL 后 `anyAlive` 复位** | `PROTECT_MEDIA_MS`（`VoiceFileGuard.kt:112`）到期 ⇒ `MediaGuardLogic.anyAlive`（`:110-113`）把 `hasMedia` 复位 false（`VoiceFileGuard.kt:301`）；本 lane `WINDOW_MS` 同步到期 ⇒ `isRevocationWindow` false ⇒ 不再拦 | 返回档池**还在**；TTL 是**时间维度**的单独复位，⛔ 不与「切页回来」合并（`_r49_revokeimg_reference.md:279,287`） |
| **imgAnchor 30 分钟 TTL** | `AntiRevoke.kt:470` `IMG_ANCHOR_TTL_MS` + `:468-470` 256 上限淘汰；**不清空、冷启丢**（`_r49_revokeimg_reference.md:292`）；与保护池 10 分钟 TTL **不同载体、不同时长**，单独复位 | 返回档 imgAnchor 不变；复位档按 30 分钟 / 256 上限 / 进程死三条独立清除 |
| **进程内账本冷启清零** | `seen`/`matched`/`blocked`/`skipped`（4 个 `AtomicLong`）+ `events`(≤64) + `logAt`(≤64) + `cachedTokens` + `gen` + `lastIssuedMs` **全部进程内**，冷启整体清零；`MediaDeleteLogic.verdict(seen,matched)` 回 `NO_EVENT` | ⛔ 与 TTL **分列**：TTL 到期时账本仍在、hook 仍在；冷启是**进程维度**整体清零，触发条件与残留完全不同 |
| **hook 随进程死才卸** | `HookEntry.kt:222-224` 的 `install()` **无 unhook**；关开关 / TTL 过期 / 清缓存都不卸载 `delete`/`execSQL` 上本 lane callback，只空转；**仅进程死亡**才真正消失（`_r49_revokeimg_reference.md:289`） | 返回档 hook 常驻且生效；复位档 hook 常驻但可能空转（`enabled=false`） |
| **微信重试删除** | `VoiceGuard.kt:36-37,64` 记「微信可能重试」、`docs/_r42_bit3_design.md:287` 防撤回侧实测 **5 秒 360 次**；重试落在窗口内 ⇒ 每次被拦但 60s 节流只打 1 行；落在窗口外或冷启后池空 ⇒ **无人拦、DB 行被删**；⛔ 图片侧**无 backup/restore**（`RevokeRescue.kt:121-127`）⇒ 漏拦即永久丢失 | 返回档默认「删除只发生一次且已被拦」；复位档把「重试时机」变成自变量（R42「漏掉唯一复位路径」同型事故） |
| **清缓存** | `op=logclear` 只清 LogX 环形缓冲（`EntryReceiver.kt:165-168`），**不动池/台账/文件**；若指微信「存储空间→清缓存」删 `image2`：开关**开着** ⇒ `File.delete` 被拦 ⇒ 存储只增不减（`VoiceFileGuard.kt:67-68,175-177`）；开关**关着** ⇒ 真删 | 返回档**不会删文件**；这是唯一能真正把已保护图片删掉的路径（`_r49_revokeimg_reference.md:280`） |
| ⛔ **已被删的 DB 行 / 文件不可回滚** | 本 lane 只能在 `beforeHookedMethod` **阻止删除发生**，**没有任何 restore 能力**：`ImgInfo2` 行一旦被删、`image2` 文件一旦被 native `unlink` 删除，均无副本可还原（`_r51_media_delete_logic.md:370`）。⛔ 另：`execSQL` void 跳过写法若真机不成立 ⇒ 该通道删除不会被阻止 ⇒ 行被删即不可逆 `[需真机]` | 返回档前提是「行与文件都还在」；这是**不可逆终态**，不属于任何「返回」路径 |

[DONE] S4 · 三档边界：①进入=刚撤回那一刻；②返回=隔几分钟点开/切页回来/下拉刷新/冷启，且 **TTL（时间维度）与冷启（进程维度）各自单列、不与「切页回来」合并**；③复位逐条写「保护池 TTL 后 `anyAlive` 复位」「imgAnchor 30 分钟 TTL」「进程内账本冷启清零」「hook 随进程死才卸」「已被删的 DB 行/文件不可回滚」，未写「同返回」。

---

## S5 回滚路径

按 `docs/_r49_revokeimg_reference.md:216`（B 相对 A 的回滚优势：不改任何既有文件语义）：

1. **删 `HookEntry.kt` 那 1 行注册** `MediaTableGuard.install(lpparam)`（连同那 1 行 `import`）⇒ `delete`/`execSQL` 上本 lane 的两个 callback 不再注册；`VoiceGuard.install(lpparam)` 及其 `runCatching{}` 原样。
2. **关开关** `antirevoke.media_delete`（默认已是 `"false"`，回滚时不必改任何默认值）⇒ before 第一行 `if (!enabled) return` 空转、账本停止增长、绝不拦。
3. **两个新文件整体移除**：`feature/MediaTableGuard.kt`、`docs/_r51_media_table_guard_impl.md`（本报告）。
4. 清掉另外两处纯增量接线：`RevokeRescue.kt` 删那 1 行 `MediaTableGuard.invalidate()`；`Keys.kt` 删 1 个常量 + 1 条 `PrefSpec`。
5. ⛔ **不用冷启**：关开关后 before 只做一个 boolean 判断，hook 空转但无任何语义；账本是进程内结构、随进程死自然消失；⛔ **不残留任何对微信库的写入**；⛔ 不修改 `SnsSqlRewrite.kt`。回滚后 `antirevoke.msg=true` 的现有防撤回链路（AntiRevoke / VoiceGuard / VoiceFileGuard / SnsGuard R42）**逐字原样**。
6. ⛔ 回滚不触碰真机 / 不 `adb install` / 不 `git add` / `git commit`；只删 4 个增量行 + 2 个新文件。

[DONE] S5 · 回滚 = 删 `HookEntry` 1 行注册 + 关开关 + 两个新文件整体移除（+ 清 2 处增量接线）；⛔ 不用冷启、不残留微信库写入、不改 `SnsSqlRewrite.kt`、现网链路逐字原样。

---

## S6 装包前复核要点（给主会话第二视角，逐条核对 `git diff`）

| # | 复核点 | 判据 / 锚点 |
|---|---|---|
| 1 | **callback 顺序**：`delete` 上并存 3 个 before（`VoiceGuard.kt:136` / `SnsGuard.kt:149` / 本 lane），`execSQL` 上 2 个（`SnsGuard.kt:152` / 本 lane）；本 lane 注册在 `VoiceGuard.install` 之后、`SnsGuard.install` 之前 | `[需真机]` 复核 `param.result` 置位后其余 before 是否仍跑完、`returnEarly` 是否影响 `SnsGuard.sqlGuard` 的 `param.args[0]` 改写（`:916`）；三方表名白名单交集为空 ⇒ 不重复拦/计数 |
| 2 | **`execSQL` void 语义**：命中用 `param.result = null` 跳过；⛔ 不采用 `XC_MethodReplacement`（无条件替换会覆盖 `SnsGuard.sqlGuard`）；`delete` 用 `param.result = 1` | `docs/_r49_revokeimg_reference.md:215`；写法 `[需真机]`：是否真跳过方法体、微信是否重试、是否抛类型转换异常 |
| 3 | **保守判据**：只有 `MediaDeleteLogic.shouldBlock(...)==true` 才拦；`whereClause==null` 删全表 / `<` `>` `LIKE` `IS NULL` / `IN (SELECT …)` / 键列未取证 / token 抽不出 / 窗口外 **一律放行** | `MediaDeleteLogic.kt:14-22,165-188,241-260`；`VoiceGuardLogic.kt:23-26,145-157`；`fromExecSql()` 只认最简 `DELETE FROM <单表> WHERE <where>` |
| 4 | **try/catch**：`beforeHookedMethod` + `onBefore` **双层** `try/catch(Throwable)`，catch 内 `runCatching { throttledLog(...) }`；绝不外泄异常阻断后续 callback（保护现网 R42 / 语音拦截） | 同款 `VoiceGuard.kt:158-160`、`SnsGuard.kt:216-217,223-224` |
| 5 | **默认关零开销**：`if (!enabled) return` 是 `onBefore` 第一行；`Kv.getBool` **只在 `install()`/`invalidate()`**（非 before，因 `Kv.kt:35-38` 查库）；关时无字符串解析 / 对象分配 / 日志、账本不增长 | `Keys.kt` 新 `PrefSpec def="false"`；红线自检 |
| 6 | **不写库 / 全只读**：账本 4 个 `AtomicLong` + `events` + `logAt` 纯进程内；⛔ 不改 `ContentValues` / SQL 原文 / `param.args` / 任何 DB 返回值（除跳过删除本身）；⛔ 不新增 insert/update/delete/execSQL 调用；`stat()` 只读 | `MediaTableGuard.kt` 全文；⛔ 未改 `data/MediaDeleteLogic.kt`、`SnsSqlRewrite.kt` |
| 7 | **文件名互斥**：`TARGET_TABLES` 不含 `voiceinfo`（`MediaDeleteLogic.kt:64`）；`VoiceGuard` 只认 voiceinfo、`SnsGuard` 只认朋友圈独立库；⛔ 未挂 `deleteWithOnConflict` 死代码；⛔ 未重复实现 `File.delete`（`VoiceFileGuard.kt:251-252,306`） | `docs/_r49_revokeimg_reference.md:214`、`_r51_media_delete_logic.md:401` |
| 8 | **与 R42 / `antirevoke.msg` 无交集**：⛔ 未改 `SnsSqlRewrite.kt` / `SnsGuard.kt` / `VoiceGuard.kt` / `VoiceFileGuard.kt` / `AntiRevoke.kt` / `data/VoiceGuardLogic.kt`；新开关**独立**于 `antirevoke.msg`（从不读它）；`RevokeRescue` 仅图片/视频分支 +1 行 | 设备实测 `antirevoke.msg=true` 链路零变化；`git diff --stat` 中 `EntryReceiver.kt`/`SnsGuard.kt`/`SnsBlockProbe.kt` 的改动是**其它并行 lane**，非本 lane |
| 9 | **token 反射来源 `[推断]`**：确认是否接受 install 期一次性反射 private 字段 `VoiceFileGuard.protectedMedia`（`:109`）；反射失败会打 `pool=reflect-failed` 并降级「只记账永不拦」 | 备选方案见 S2.1（自建池 + `invalidate(token)` 带默认参数，需放宽 `RevokeRescue` 那 1 行为带参，与 `Q5.2:211` 无参要求冲突） |
| 10 | **开关动态生效 `[推断]`**：`kvset` 的 `EntryReceiver.kt:176-200` 不含本类 refresh；开/关要等下一次撤回 `record()→invalidate()→refresh()` 或进程重启 | 真机建议先 `kvset` 开开关再撤一张图，用 `dev.py q 'MTG'` 看 `installed enabled=` 与 `pool=`；M3 广播导出本轮未做 |

## 交付与纪律声明（M5）

⛔ **本 lane 未跑 gradle、未装包、未碰设备、未跑 adb / kvset / 真机**；编译与全量单测由**主会话统一执行**（`assembleDebug` / `testDebugUnitTest`，`CLAUDE.md:71`）。⛔ 未 `git add` / `git commit`。⛔ 只写允许的 4 个源码文件 + 1 个报告：`MediaTableGuard.kt`（新建）、`HookEntry.kt`、`RevokeRescue.kt`、`Keys.kt`、`docs/_r51_media_table_guard_impl.md`（新建）。以磁盘落盘文件与 `git diff` 为准。

---

## 落盘日志（分步汇总）

[DONE] S0 现状核对 · 7 类真实签名 / `MediaDeleteLogic` 7 纯函数 / 宿主类解析 / 开关写法，逐条带 `文件:行号`；`VoiceFileGuard` 无公开 token 接口为磁盘硬事实
[DONE] S1 钩子设计 · 挂 `delete`+`execSQL`、before 10 步顺序、三 callback 表名白名单互斥、`execSQL` void `param.result=null` 标 `[需真机]`
[DONE] S2 实现说明 · `MediaTableGuard.kt` 新建、token 反射来源锚点、双层 try/catch、限流封顶值、TAG `MTG`、账本 `verdict` 三态
[DONE] S3 接线与开关 · 逐文件 diff（+2/+1/+2/新建/新建）、默认 `false` 现网零影响、M3 广播导出不做
[DONE] S4 三档边界 · 进入/返回/复位，TTL 与冷启分列、复位逐条不写「同返回」
[DONE] S5 回滚路径 · 删 1 行注册 + 关开关 + 两新文件移除，⛔ 不用冷启
[DONE] S6 装包前复核要点 · 10 条 + 证据分级三栏 + 6 项盲区 + M5 纪律声明

[DONE] ALL
