# R49 · R-C「复用写时拦截」探针方案独立裁决（lane F / Pro 判据设计）

> 角色：独立裁决 lane F（只读裁决）。本报告不改任何源码/测试/配置/其它报告，唯一写入文件即本文件。
> 工作目录：`C:\Users\lx\WorkBuddy\2026-09-29-20-23-21`
> 裁决对象：lane D `docs/_r49_rc_probe_design.md` 与 lane E `docs/_r49_rc_probe_inventory.md` 之间的 P0 分歧。
> 用户本轮诉求：**仅**验证 R-C「复用写时拦截」技术路线是否可实现，不涉及 R-A / R-B，设计**尽可能全面**的探针。

## 一、分歧陈述（原文锚点）

- **lane D 结论**：决定性判定探针全部复用现成 `op=sql` / `op=snsg sub=blockstat` / `dev.py ui`，**0 次 adb install**；可选调用栈增强最多 1 次；「判死最省只需 L1+W1/W2 两条」。
- **lane E 结论**：现有能力**不够**——`updateWithOnConflict` / `execSQL` 常驻钩子 + `SnsHuntProbe` 全量临时钩子都在，挂载点一个不缺；但 `blockstat` / `trace` **无 `createTime` / `userName` / 键名集 / 调用栈**，无法回答「写事件是否命中三天外行」；硬判据**必须新增** 1 个类 `SnsWriteProbe` + 扩写 3 个文件（`SnsGuard.kt` / `SnsBlockProbe.kt` / `EntryReceiver.kt`），需装包。

⇒ 焦点：判死 R-C 生死，究竟 **0 装包即可**，还是 **必须新增探针 + 装包**。本裁决须二选一或给出第三解，**不得和稀泥**。

## 二、裁决范围与硬约束自检

- 全只读：未触碰 adb / 真机 / Gradle / 装包 / kvset；未修改源码 / 测试 / 配置 / 其它报告。
- 每条结论附 `文件:行号`；不确定处标 `[推断]` / `[需真机]`。
- 必读三份材料：`docs/_r49_3day_forensics.md`(lane A) / `docs/_r49_rc_probe_design.md`(lane D) / `docs/_r49_rc_probe_inventory.md`(lane E)。
- 核源码：`SnsBlockProbe.kt` / `EntryReceiver.kt` / `SnsGuard.kt` / `SnsSqlRewrite.kt`。

[START]
## 三、裁决锚点缩写

> `SnsGuard.kt` / `SnsBlockProbe.kt` / `SnsSqlRewrite.kt` = `wxgroup/app/src/main/java/com/lx/wxgroup/feature/` 下同名文件；`EntryReceiver.kt` = `.../entry/EntryReceiver.kt`；`Keys.kt` = `.../data/Keys.kt`；`SnsHuntProbe.kt` = `.../probe/SnsHuntProbe.kt`；`MomentsLogic.kt` = `.../feature/MomentsLogic.kt`；`CLAUDE.md` = 仓库根。
> 三份受裁报告：`forensics` = `docs/_r49_3day_forensics.md`；`design` = `docs/_r49_rc_probe_design.md`；`inventory` = `docs/_r49_rc_probe_inventory.md`。

---

## V1 · 核源码事实：现成探针到底能输出哪些字段

### 1.1 逐个打开的四处源码（核到行为，不止到函数）

| 文件 | 打开位置 | 核出的行为 |
|---|---|---|
| `SnsBlockProbe.kt` | `Event` `:167-199`、`stat()` `:718-743`、`trace()` `:773-811`、`noteSql()` `:552-598`、`readBits()` `:648-671`、`Bits` `:633-639`、`switch()` `:703-713` | `Event` 13 个构造字段 + `after`/`afterShape` 两个 `@Volatile` 可变字段；`stat()` = 6 行全局计数 + 最近 10 个 `Event.line()`，`out.take(16)` 封顶；`trace` 每 30s 只打 `src/lf/sef` |
| `EntryReceiver.kt` | `op=snsg` `:342-386`、`op=sql` `:652-662`、`op=logclear` `:165-168`、`op=kvset` `:176-183` | `snsg` 只有 `stat/cmts/mid/mpath/hit/block/blockstat/trace` 8 个 sub，未知落 `SNSG unknown sub=`（`:384`）；`blockstat` 直接转发 `SnsBlockProbe.stat()`（`:378-380`），**不做任何加工** |
| `SnsGuard.kt` | `snsBlockGuard` before `:297-307`、`onSnsUpdateBefore` `:356-369`、`onExecSql` `:834-857`、`onSnsSql` `:872-892`、`applySqlRewrite` `:900-914`、`refresh()` `:192-205`、`stat()` `:1635-1637`、`runReadOnlySql` `:1268-1314` | before 里改 `param.args[0]`（SQL 字符串）/`param.args[1]`（ContentValues）；`sqlGuard` 挂在 `execSQL` **before**（`:221-226`）；`stat()` 只报 3 个开关位 + `snsDb!=null`，**零计数** |
| `SnsSqlRewrite.kt` | `:16-28`、`:42-48`、`:65-85`、`:93-98` | `CLEAR_BIT3_ASSIGN`（`:45-48`）只捕获 SET 里 `-9` 常量前的片段；`rewrite()`（`:77-85`）把常量换成 `NOOP_MASK="-1"`（`:42`），**WHERE 一个字节不碰**，`rangeKey()`（`:93-98`）只返回 `"lo..hi"` 字符串 |

### 1.2 现成探针输出字段总表（字段 / 是否输出 / 文件:行号）

| 字段 | `op=snsg sub=blockstat` | `op=snsg sub=trace` | `op=sql` | `op=sns sub=on/catch`（落文件） | 锚点 |
|---|---|---|---|---|---|
| `snsId` | ✅ Event 行 `id=` | ✅ 入参、输出 `id=` | ✅ `select snsId` | ✅ SQL 原文里含 | `SnsBlockProbe.kt:168,:196,:795`；`SnsGuard.kt:1293-1305` |
| **`createTime`** | ✅ **`ct=`**（Event 行） | ❌ 只 `src/lf/sef` | ✅ `select createTime` | ❌ 只有 SQL 原文，无列值 | `SnsBlockProbe.kt:185,:198,:347`；`:654/:662`；`:795-797` |
| **`userName`** | ❌ 结构化输出无 | ❌ | ✅ `select userName` | ⚠️ 仅 SQL 原文里 `userName="…"` 字符串，需离线解析 | `SnsBlockProbe.kt:167-199`；`:654/:663`；`SnsSqlRewrite.kt:11` |
| `sourceType` / 位 | ✅ `before/want/keep/after/shape` | ✅ `src` | ✅ | ⚠️ SQL 原文 | `SnsBlockProbe.kt:195-198,:795` |
| `localFlag` / `serverExtFlag` | ✅ `lf/sef` | ✅ `lf/sef` | ✅ | — | `SnsBlockProbe.kt:198,:797` |
| `content` / `attrBuf` BLOB | ⚠️ 只 `C=` / `A=` 两布尔 | ❌ | ✅ `hex()` 得 TEXT；裸列只报 `<blob NB>` | ❌ | `SnsBlockProbe.kt:179-180`；`SnsGuard.kt:1322-1332` |
| **SQL 原文** | ❌ **不在 `stat()` 返回**，只在 `LogX.w` 采样日志流 | ❌ | ✅（`--es q` 本身） | ✅ 完整 SQL | `SnsBlockProbe.kt:584-595,:624-627`；`EntryReceiver.kt:378-380` |
| **ContentValues 键名集** | ❌ 只有 `keys:Int`（键**数量**）+ `C/A` 两布尔 | ❌ | ❌ | ⚠️ values 原文，非结构化 | `SnsBlockProbe.kt:177-180` |
| **调用者签名 / 调用栈** | ❌ 全类无栈 | ❌ | ❌ | ✅ `FRAMES=24` | `SnsHuntProbe.kt:55`；`inventory:203` |
| 方法名 / before-after 半场 | ❌ | ❌ | ❌ | ✅ 钩子按方法名挂 | `SnsHuntProbe.kt:181-188` |
| `hitS`（是否∈三天外集合 S） | ❌ | ❌ | ⚠️ 能列出 S，但与写事件**无关联通道** | ⚠️ 只能人工离线 join | — |
| `changes()` 命中行数 | ❌ | ❌ | ❌ | ❌（`execSQL` 是 void） | `SnsSqlRewrite.kt:17-19` |
| 全局计数（`sqlClear9`/`updSeen`/`insSeen`…） | ✅ 但**只按通道全局聚合、不按行/时间分桶** | ❌ | ❌ | ❌ | `SnsBlockProbe.kt:720-738` |
| 开关态 / `snsDb!=null` | ❌（在 `op=snsg sub=stat`） | ❌ | ⚠️ 仅 `SQLX no sns db yet` 提示 | ❌ | `SnsGuard.kt:1635-1637`, `:1280` |
| 每窗口清理 / 前台 / 投递自证 | — | — | — | — | `EntryReceiver.kt:165-168`；`dev.py:141-152`（`inventory:79`） |

补充两份「入口真实现状」：`op=snsg sub=sqlspy|srcmap|dump` **不存在**，落到 `SNSG unknown sub=`（`EntryReceiver.kt:384`；`inventory:91-93`）；`op=snsg sub=block --es sub2 on|off` → `SnsBlockProbe.switch()` 只切 `Keys.ANTIREVOKE_SNS_BLOCK`（`SnsBlockProbe.kt:703-713`；`Keys.kt:161` 字面量 `antirevoke.sns_block`），**不写微信库**。

### 1.3 判定 lane E「无 `createTime`/`userName`/键名集/调用栈」是否属实

**四项里 3 项属实、1 项不属实**（逐项给反证或确认）：

| 断言 | 裁定 | 源码判据 |
|---|---|---|
| 无 `createTime` | ❌ **不属实** | `Event` 有 `val ct: String`（`SnsBlockProbe.kt:185`），构造处 `ct = bits?.createTime?.toString() ?: "?"`（`:347`），`Event.line()` 打 `ct=$ct`（`:198`），`stat()` 把最近 10 个 `Event.line()` 一并输出（`:739-741`）。⇒ **`blockstat` 确实输出 createTime，只是字段名叫 `ct`**。lane E `inventory:200`（I4 字段表）写「仅点查能读，未进 `Event`/`blockstat`/`trace` 输出」是**可被源码反证的事实错误**；lane E 在 `inventory:350` 把「`blockstat`/`trace` 字段集：无 `createTime`」列入「已证事实（静态代码可直接确认）」栏，同样错。 |
| 无 `userName` | ✅ 属实（对结构化输出） | `Event` 13 字段与 `line()` 均无 `userName`（`SnsBlockProbe.kt:167-199`）；`trace` 只 `src/lf/sef`（`:795-797`）；`op=snsg` 无 `user` 参数（`EntryReceiver.kt:342-386`）。但 `readBits` 的 SQL 查了 `userName`（`:654/:663`）、`SnsGuard.isSelfAuthor` 在用（`inventory:199`），且 `& -9` 原文采样日志含 `userName="…"`（`SnsSqlRewrite.kt:11`；`inventory:225` 自认）⇒ 严格说是「**半具备**」，不是「完全没有」。 |
| 无键名集 | ✅ 属实 | `Event` 只有 `keys: Int` = 键**个数**（`:177`）+ `hasContent`/`hasAttr` 两个特定键布尔（`:179-180`），无 `Set<String>` 键名。 |
| 无调用栈 | ✅ 属实 | `SnsBlockProbe` 全程不取 `stackTrace`；有 24 帧栈的是临时探针 `SnsHuntProbe`（`SnsHuntProbe.kt:55`）与 `RevokeHuntProbe`（`inventory:50,:203`）。 |

**⇒ 但 lane E 的核心结论仍然成立**，理由是它自己给出的 `inventory:221-225`（I4 五条）中最要害的两条：
1. `Event` 只在 `updateWithOnConflict` 的**拦截路径**被创建（`SnsBlockProbe.kt:334`；触发前置是 `onSnsUpdateBefore` 读到 `sourceType` 键，`SnsGuard.kt:366-369` 不带 `sourceType` 直接 `noteUpdateNoSrcKey()` + `return`，**不创建 Event**）⇒ `ct` 只覆盖「带 sourceType 的 update」。
2. R-C 唯一可能有触发机会的写事件 = 进好友主页每进一次 6 条 `UPDATE SnsInfo SET sourceType = sourceType & -9 ...`（`SnsGuard.kt:61`、`SnsSqlRewrite.kt:10-13`、`moments.md:24-25`），走的是 **`execSQL` 通道 → `noteSql()`**（`SnsBlockProbe.kt:552-598`）：**只记分类计数 + `LogX.w` 原文采样，不创建 Event、不输出 `ct`、不与任何行的 `createTime` 做 join**（`inventory:221-225`）。

⇒ 即便 `ct` 存在，也**回答不了「`& -9` 是否命中三天外行」**。lane E 错在把一条「半具备」写成了绝对断言、且把 `Event.ct` 漏核；结论链（挂载点齐、缺行身份结构化关联）没断。

### 1.4 顺带核出 lane D 的一处事实错误（正式裁定留 V3/V5）

lane D `design:184`（P2·W1）声称 `op=snsg sub=blockstat` 输出 `meth/half/table/SQL原文/ContentValues键集/snsId/user/hitS/caller/thread`，`design:296-316`（P4）统一 `RCP_*` TAG、`design:309-310` 的 `RCP_W2 ... gt= lt= hitS=`、`design:310` 的 `RCP_A1 rawMethodCnt/beforeCnt/onSCnt/verdict`。**现成 APK 一行都不会打**：`SnsBlockProbe` 打的日志前缀是 `BLOCK ` / `TRACE `（`:196`、`:586`、`:795`），`op=sql` 是 `SQLX`（`SnsGuard.kt:1293`），`EntryReceiver` 转发前缀 `SNSG `（`EntryReceiver.kt:349`），全仓无 `RCP_` 字面量。⇒ **lane D 把「设计稿里的目标输出」当成了「现成探针已有的输出」**，这是它得出「0 装包能判死」的根因，见 V3。

[DONE] V1

## V2 · ⭐ 最容易漏的假阴性（重点核）：`op=sql` 前后快照差分是否必然假阴性

### 2.1 结论先行

**是，必然假阴性（结构性、非概率性）** —— 只要 B 层改写处于开启态，微信进好友主页的 6 条 `& -9` 清位写事件**真实发生且 `changes()>0`**，但 `op=sql` 前后快照**必然 0 差异**。因此「没变化 ⇒ 判无写事件」在该配置下 100% 错判。这一条直接判掉 lane D 0 装包方案的核心观测手段之一。

### 2.2 源码级判据：改写发生在哪个钩子 / 是否在写库前

完整调用链（每一环都打开核过）：

| 步 | 位置 | 行为 |
|---|---|---|
| ① 注册 | `SnsGuard.kt:152` → `sqlGuard` 定义 `:221-226` | `hookAllMethods(SQLiteDatabase,"execSQL")`，`beforeHookedMethod` → `onExecSql(param)`（`:223`） |
| ② 入口 | `onExecSql` `:834-857` | `sql.contains(TABLE)` 才进 `onSnsSql(sql, param)`（`:853-855`）；句柄捕获分支在 `:842-848` |
| ③ 决策 | `onSnsSql` `:872-892` | 四路 `when`：`!clearsBit3`→`NONE`（`:876`）；`!SnsSqlRewrite.shouldBlock(sql)`→`NONE`（`:879`）；`!cfg().sqlBlockEnabled`→**`OFF` 原样执行**（`:881`）；`else`→`applySqlRewrite(param, sql)`（`:882`）。KDoc `:862-864` 明写「**先决定改不改写，再记账**」 |
| ④ 改写 | `applySqlRewrite` `:900-914` | `SnsSqlRewrite.rewrite(sql)`（`:904`）→ `param.args[0] = out`（`:909`）。KDoc `:895` 原文：「把 `& -9` 换成 `& -1` 并写回 `param.args[0]`（**before 钩子里改，SQL 尚未执行**）」 |
| ⑤ 纯函数 | `SnsSqlRewrite.rewrite` `:77-85`、`CLEAR_BIT3_ASSIGN` `:45-48`、`NOOP_MASK` `:42` | 只把 SET 里的 `-9` 常量换成 `-1`，`head` 改、`tail`（WHERE 起）**逐字拼回**（`:80-84`） |

⇒ **改写发生在 `execSQL` 的 before 半场、SQL 执行之前**（`SnsGuard.kt:895`、`:872-892`），不是 after、不是跳过执行。

### 2.3 为什么「必然」：no-op 改写让库里没有任何可观测差异

- `x & -1 == x`（`SnsSqlRewrite.kt:24`、`:40-42`）⇒ 改写后 SQL 把 `sourceType` **赋回原值**。
- `SnsSqlRewrite.kt:16-21` 类注释明确排除另两种做法，并给出判据：「改写值 ⇒ `changes()` = 命中行数（与原语句**完全一致**）；改 WHERE ⇒ `changes()=0`；跳过执行 ⇒ `changes()` 保留上一条陈旧值」，且「只动 SET 里的一个常量，WHERE 一个字节都不碰 ⇒ **命中集合、`changes()`、后续分页三者全同**」。
- `SnsGuard.kt:62` KDoc 同述：「把 `& -9` 改成恒等掩码 `& -1` 后**照常执行**（**命中集合与 `changes()` 都不变**）」；`:867` 复述。

⇒ 因果链闭合：**写事件真实发生（`execSQL` 被调用、`changes()` = 命中行数 > 0）→ 库里 `sourceType` / 行数 / 命中集合全部不变 → `op=sql` 前后快照逐行相同 → 「无变化」被误读成「无写事件」**。这不是采样/节流造成的偶发漏记，而是 no-op 改写的设计目的本身，故为**必然假阴性**。

### 2.4 ⛔ 关掉哪个开关才能让它不改写（题面硬结论的源码级坐实）

`onSnsSql` 的闸门是 `cfg().sqlBlockEnabled`（`SnsGuard.kt:881`），由 `refresh()` 计算：

```
SnsGuard.kt:195   val block = Kv.getBool(Keys.ANTIREVOKE_SNS) || Kv.getBool(Keys.ANTIREVOKE_SNS_BLOCK)
SnsGuard.kt:199   sqlBlockEnabled = block && !Kv.getBool(Keys.ANTIREVOKE_SNS_SQLBLOCK_OFF)
```

字面量全部核到 `Keys.kt`：`:145` `antirevoke.sns`、`:161` `antirevoke.sns_block`、`:174` `antirevoke.sns_sqlblock_off`、`:306` `antirevoke.sns_album_fix_off`。

- `block` 是 **OR**（`:195`）⇒ ⛔ **只关一个是无效的**：`op=snsg sub=block --es sub2 off` 经 `SnsBlockProbe.switch()`（`:703-713`）只把 `antirevoke.sns_block` 置 false；只要 `antirevoke.sns` 仍为 true，`block` 仍 true、`sqlBlockEnabled` 仍 true ⇒ 改写照旧 ⇒ 差分仍必然假阴性。
- **⛔ 项目硬结论（题面）：临时全放行必须 `antirevoke.sns` 与 `antirevoke.sns_block` 两个都关** —— 源码依据即 `SnsGuard.kt:195` 的 OR。两个都为 false ⇒ `block=false` ⇒ `sqlBlockEnabled=false` ⇒ `onSnsSql` 落 `SqlBlock.OFF`（`:881`），`& -9` 原样执行、库里 bit3 真被清掉（`blkOff` 计数 `SnsBlockProbe.kt:574` 可见）。同时 A 层（写时拦截摘 `sourceType` 键，`SnsGuard.kt:262-263`、`:297-298`）与 `albumFixEnabled` 也一并停 ⇒ 微信写原样落地。
- 第三条路径 `antirevoke.sns_sqlblock_off=true`（`Keys.kt:174`）也能让 `sqlBlockEnabled=false`（`:199`），但 `SnsGuard.kt:95-96`、`:198` 明定它是 **A/B 对照专用调试覆盖**：只停 B 层改写，A 层与补位照旧，**不是「临时全放行」**；且 `inventory:340`（I7 第 8 条）提醒「开关关闭时照样记账（只计数、不摘键）」，复用 `blockstat` 做对照必须同时记录开关快照。
- `kvset` 写法：`op=kvset --es key antirevoke.sns --es val 0` 与 `--es key antirevoke.sns_block --es val 0`（`EntryReceiver.kt:176-183`，参数名是 `key`/`val` 不是 `k`/`v`；`:183` 立刻 `SnsGuard.refresh()` 热生效）。⚠️ **本 lane 硬约束禁 kvset，交主 agent 执行**；`op=kvget` 可只读回读开关态。

### 2.5 即使两个都关，差分方案仍有无法排除的第二重假阴性（不因开关而消失）

1. **`x & -9 == x` 当 bit3 已为 0**：`& -9` 只清 bit3（`SnsSqlRewrite.kt:10-14`）。三天外行若 `sourceType` 本就不含 bit3（`SnsGuard.kt:1635` 的位状态、`forensics:163` 判据④要看 `(sourceType & 8)`），SQL 执行后库值仍不变 ⇒ 差分无变化但写事件命中了该行。
2. **WHERE 白名单把 S 排除在外**：6 条 `& -9` 的完整形态是 `WHERE SnsInfo.userName="<好友>" AND (sourceType in (64 个全含 bit3 的值)) AND (snsId != 0) AND (snsId > A) [AND (snsId < B)]`（`SnsSqlRewrite.kt:10-13`）。若三天外行 `sourceType` 不在那 64 个值里，或 `snsId` 不在分页区间内，SQL 虽执行却**不命中 S** ⇒ 库里 S 行无变化；但「`& -9` 是否覆盖 S」正是 R-C 生死判据本身（`design:34`, `:152`），不能靠「没变化」反推。
3. **A 层摘键吞掉 update 通道的写**：`snsBlockGuard` 的 before 把 `sourceType` 键从 ContentValues 摘掉（`SnsGuard.kt:262-263`、`:297-298`），使那次 `updateWithOnConflict` 写**发生了但清位不落地** ⇒ 库里无变化。两个都关后 A 层停（`:294-295` 说明关掉时「照样记账、不摘键」），但这条说明差分方案在默认配置下还多一重吞写。
4. **`execSQL` 是 void，模块拿不到 `changes()`**：`SnsSqlRewrite.kt:17-19` 明写「三种做法在 Java 侧都『成功』，无法靠返回值区分」⇒ 即使改写关掉，也无法从命中行数佐证，差分只能看行值变化。
5. **异步 / 快照时序**：`op=sql` 是 `io{}` 后台线程异步执行（`EntryReceiver.kt:657-661`），与微信写库线程并发；6 条按 `snsId` 分页的执行时序未知（`SnsSqlRewrite.kt:12`、`:87-97`）。快照窗口与写窗口错位无法排除 `[需真机]`。
6. **冷启清零 / 环形覆盖**：`SnsBlockProbe` 计数纯内存、进程死即清零（`SnsBlockProbe.kt:28`；`inventory:333`），`LogX` 5000 行环形（`inventory:23`、`:337`）⇒ 「0」不能反推「没发生」。

### 2.6 对 lane D 0 装包方案的直接裁定

lane D 把「`op=sql` 建立 S + 前后快照」当作判死基座（`design:174` L1/L3、`:301` 前后结果对照、`inventory:301` A 类第 4 条），并声称「判死最省只需 L1+W1/W2 两条」（`design:157`、`:275`）。

**V2 裁定：该基座不成立。** 在默认（B 层改写开启）配置下快照差分是必然假阴性；两个开关都关后仍有 §2.5 的 6 类混淆，其中 1/2/3 是**结构性**的，不是工程噪声。⇒ lane D 的「0 装包足够判定 R-C 生死」在 V2 这一关已被否决（后续 V3/V4 给出另外两条独立否决理由）。

[DONE] V2

## V3 · 0 装包方案的能力上界（把「纯现成 op 能观测到的全部事实」列全）

### 3.1 全清单：逐条标注能否回答 R-C 生死判据「三天外行上有无写库事件」

| # | 现成能力（op / 入口） | 能观测到的**全部**事实 | 能否回答「S 上有无写事件」 | 锚点 |
|---|---|---|---|---|
| 1 | `op=sql` → `SnsGuard.runReadOnlySql` | 朋友圈独立库 `SnsMicroMsg.db` 上任意 `select` / 只读 pragma；`pragma table_info(SnsInfo)` 的 18 列；`snsId/userName/createTime/sourceType/localFlag/type/content/attrBuf`；BLOB 须 SQL 内 `hex()`（裸列只 `<blob NB>`）；`clampProbeN` 钳 **50 行**；`snsDb` 空返回 `SQLX no sns db yet` | ❌ **不能**。只能看「结果状态」，看不到事件；且 V2 已证改写开启时状态必然不变 | `EntryReceiver.kt:654-662`；`SnsGuard.kt:1268-1314`, `:1322-1332`, `:1280`, `:70`；`MomentsLogic.kt:1026/:1032` |
| 2 | `op=snsg sub=stat` → `SnsGuard.stat()` | 仅 `blockEnabled` / `sqlBlock` / `albumFix` / `snsDb!=null` 四个值，**零计数** | ❌ 不能 | `EntryReceiver.kt:349`；`SnsGuard.kt:1635-1637` |
| 3 | `op=snsg sub=blockstat` → `SnsBlockProbe.stat()` | 6 行**全局**计数（`hit/blocked/nonZero/skipOff`、`updSeen/updZero/updNoSrcKey`、`insSeen`、`delSns/delRow/sqlSns/sqlSrc/sqlClear/sqlClear9/sqlBlock9/blkOff/blkFail/rng/max/sqlOwn/sqlUnmatched`）+ 最近 10 个 `Event.line()`（`id/at/before/want/keep/keys/C/A/blk/after/shape/lf/sef/**ct**`），`out.take(16)` | ⚠️ **只能答「全局是否发生过某类写」，不能答命中哪一行**；`ct` 只在 update 拦截通道的 Event 里 | `EntryReceiver.kt:378-380`；`SnsBlockProbe.kt:718-743`, `:195-198` |
| 4 | `op=snsg sub=trace --el id` | 对**单个已知 snsId** 每 30s 点查 `src/lf/sef`，最多 60 次（30min） | ❌ 不能。是「位随时间」，**不是写事件观测**；且不打 createTime/userName | `EntryReceiver.kt:381-383`；`SnsBlockProbe.kt:773-811`, `:795-797` |
| 5 | `op=snsg sub=block --es sub2 on\|off` | 切 `antirevoke.sns_block` 开关（`Kv`，不写微信库）+ `SnsGuard.refresh()` | ❌ 不能（只是改配置） | `EntryReceiver.kt:375-377`；`SnsBlockProbe.kt:703-713`；`Keys.kt:161` |
| 6 | **`op=sns sub=on\|catch` → `SnsHuntProbe`（0 装包最全）** | 临时窗口 10~600s 内挂 `update/updateWithOnConflict/delete/insert*/replace*/execSQL/rawQuery/query` + `SQLiteStatement.executeUpdateDelete/executeInsert/execute`；**完整 SQL 原文 + 24 帧调用栈 + `getPath()`/表名/ContentValues/schema**，落 `wxgroup_dbg.txt` 绕开环形 | ⚠️ **能答「发生过什么 SQL / 谁调用」，但不能结构化答「是否命中 S」**：无 createTime 列值、无行身份展开、需人工拉文件 + 与 `op=sql` 的 S 手工 join | `EntryReceiver.kt:296-302`；`SnsHuntProbe.kt:53`, `:55`, `:57`, `:66`, `:70`, `:181-196`；`inventory:49`, `:305` |
| 7 | `op=revoke sub=on` → `RevokeHuntProbe` | 另一套临时 DB 写方法 + 调用栈，落同一文件 | ⚠️ 同 #6，偏撤回域，仅可交叉验证 | `EntryReceiver.kt:289-292`；`inventory:50`, `:306` |
| 8 | `op=snsv` → `SnsViewProbe`；`dev.py ui <文本>` | 朋友圈 View 树 / 带 snsId 节点；界面文本断言（可见条数） | ❌ 不能答写事件，但能答 P1.6「用户能否看见」 | `EntryReceiver.kt:327-340`；`design:211`；`inventory:84` |
| 9 | `op=moments sub=refresh\|load`；`op=msync sub=sync` | 撩前台 / 触发后台同步 | ❌ 非观测，只是制造写的前置动作 | `EntryReceiver.kt:456-468`, `:478-497` |
| 10 | `op=logclear`；`op=kvget`；`op=who`；`op=label sub=sql`；`op=snsprefix sub=probe\|stat\|self`；`op=ardump/archeck` | 清日志环、只读回读配置、wxid↔昵称、探针只读态、撤回域计数 | ❌ 不能（辅助层） | `EntryReceiver.kt:165-168`；`inventory:129`, `:113`, `:96`；`CLAUDE.md:17` |
| 11 | ⚠️ `op=snsprefix sub=simdrop\|setsrc2 --el id` | 在指定 snsId 上写 `sourceType=0`/`=2` | ❌ **不能证 R-C**：①写微信库（本 lane 禁用）；②是**模块自己发的写**，不是微信写 | `inventory:290`, `:309`, `:312`；`EntryReceiver.kt:665-672` |

### 3.2 计数类日志能否定位到具体 `snsId` / `userName` —— 明确裁定：**不能**

| 计数 / 输出 | 真实语义 | 能否定位行身份 | 锚点 |
|---|---|---|---|
| `blockstat` 前 6 行全部计数 | **进程内全局聚合 `AtomicInteger`**（update/insert/delete/execSQL 四路独立，**不合成总数**），不按 snsId / userName / createTime 分桶 | ❌ 不能 | `SnsBlockProbe.kt:68-133`, `:720-738`；`inventory:222`, `:334` |
| `sqlUnmatched` | 仅在 `isSourceWrite && !clearBit1 && !clearBit3 && !own` 时累加 ⇒ **「改了 sourceType 但形态没认出来」的 execSQL**；大量不带 `sourceType` 的 SnsInfo 写**根本不进此计数** | ❌ 连「所有写事件」都代表不了 | `SnsBlockProbe.kt:589-597`, `:593`；`inventory:341` |
| `sqlClear9` | 只说明「识别到过 `& -9` 形态的 execSQL」 | ❌ 不说命中哪一行 / 哪个好友 | `SnsBlockProbe.kt:565-566` |
| `rng` / `max=Nx@lo..hi` | `stat()` 唯一带 `snsId` 区间的输出：但只在 `SqlBlock.BLOCKED`（**改写开启**）时由 `noteBlock9Range` 记录，且只保留「被拦次数最多的那一个」区间 `block9RangeMaxKey`，**不是 6 条全量、不是每条的 gt/lt/userName** | ⚠️ 至多一条最热区间；关掉改写（`OFF`）时**完全不记** | `SnsBlockProbe.kt:567-570`, `:601-613`, `:737` |
| `Event.line()` 的 `id=` / `ct=` | 是 snsId 与 createTime，但 `Event` **只在 `updateWithOnConflict` 拦截路径创建**（`SnsBlockProbe.kt:334`；`SnsGuard.kt:366-369` 不带 sourceType 早退不创建） | ⚠️ 只覆盖 update 拦截通道；**决定性的 `& -9`（execSQL 通道）无 Event** | `SnsBlockProbe.kt:167-199`, `:334-348` |
| `BLOCK w2 sql-clear9` 采样日志 | SQL 原文里含 `userName="<好友>"` 与 `snsId > A [and < B]`（`SnsSqlRewrite.kt:11-12`），**可离线解析** | ⚠️ 但只在 `LogX.w` 日志流（**不在 `stat()` 返回**）、去重封顶 60（`:617-622`）、单行截 1500 字符（`:58`）、LogX 5000 行环形覆盖 ⇒ 关键原文可能丢；且**仍无 createTime**，需与 `op=sql` 的 S 人工 join | `SnsBlockProbe.kt:584-595`, `:55`, `:58`；`inventory:201`, `:225`, `:337` |

### 3.3 能力上界一句话

**0 装包能答**：Q3 前置（S 是否存在 / BLOB 完整 / `createTime` 单位 / 位状态 / 重复行）、界面可见条数、**全局**是否发生过某类写、以及（靠 `SnsHuntProbe` 文件 + 人工 join）写事件的 SQL 原文与调用栈。
**0 装包不能答**：**「三天外集合 S 上是否真的发生 `updateWithOnConflict` / `execSQL` 写库事件」** —— 这正是 lane A `forensics:260`、`:272` 与 lane D `design:133`（P1.3）共同点名的 R-C 生死判据。缺的不是挂载点（6 个常驻钩子 + `SnsHuntProbe` 全量临时钩子都在，`inventory:177`、`:324`），而是**「写事件 ↔ 行身份（snsId/区间/userName）↔ `createTime`」的结构化关联**（`inventory:227`, `:324`）。

[DONE] V3

## V4 · 差分方案的替代性：有没有「不新增探针 + 关掉 B 层改写 + 前后快照 + blockstat 计数」的充分判据组合？

### 4.1 先把「最可能的候选组合」完整写出来（C0），再逐条证伪

| 步 | 动作 | 判据 | 现成性 |
|---|---|---|---|
| C0-1 | `op=kvset --es key antirevoke.sns --es val 0` 且 `--es key antirevoke.sns_block --es val 0`（**两个都关**，`SnsGuard.kt:195`）⇒ `sqlBlockEnabled=false`（`:199`）、A 层摘键也停（`:294-295`） | `op=snsg sub=stat` 回读 `blockEnabled=false sqlBlock=false` | 现成（⚠️ kvset 本 lane 禁执行，交主 agent） |
| C0-2 | 进主页**前** `op=sql`：`select snsId,createTime,sourceType,(sourceType&8) bit3 from SnsInfo where userName='<wxidA>' and createTime < <T0>`，存为 S 前快照 | S 非空、位状态已知 | 现成 |
| C0-3 | 微信前台 → 进入「昵称A」主页 → 逐屏翻页 | 触发 6 条 `& -9`（`moments.md:24-25`） | 需用户配合 |
| C0-4 | 进主页**后** 同一条 `op=sql` 取 S 后快照，按 `snsId` 逐行比对 `sourceType` | 「有行变了 ⇒ 写命中 S」 | 现成 |
| C0-5 | `op=snsg sub=blockstat` 读 `sqlClear9` / `blkOff` / `updSeen` / `sqlUnmatched` | 「计数 >0 ⇒ 发生过写」 | 现成 |
| C0-6 | 合成判据：`sqlClear9>0` ∧ S 中有行 `sourceType` 变化 ⇒ **有写命中 S（R-C 有触发机会）**；否则 ⇒ **无写命中 S（R-C 证死）** | — | — |

### 4.2 裁定：**C0 不构成充分判据**。逐条点名无法排除的混淆

| # | 混淆 | 为什么 C0 排除不掉 | 锚点 |
|---|---|---|---|
| 1 | **`x & -9 == x`（bit3 已为 0）** | `& -9` 只清 bit3；三天外行若 `sourceType & 8 == 0`，SQL 原样执行后库值仍不变 ⇒ C0-4「无变化」是假阴性，但写事件确实命中该行 | `SnsSqlRewrite.kt:10-14`, `:24`；V2 §2.5-1 |
| 2 | **WHERE 白名单 / 分页区间把 S 排除** | 6 条 `& -9` 的 WHERE 是 `userName="<好友>" AND sourceType in (64 个全含 bit3 的值) AND snsId != 0 AND snsId > A [and < B]`；S 行可能根本不在命中集合里。「SQL 执行了」≠「写落在 S 上」，而**「是否落在 S 上」正是 R-C 要判的生死假设本身** | `SnsSqlRewrite.kt:10-13`, `:87-97`；`moments.md:24-25`；`design:34`, `:152` |
| 3 | **`execSQL` 是 void，`changes()` 不可观测** | 模块拿不到命中行数，C0-4 只能看「行值变没变」，无法确认「命中集合里有没有 S 行」 | `SnsSqlRewrite.kt:17-19`；V2 §2.5-4 |
| 4 | **其它列 / 不带 `sourceType` 的写** | 微信对 S 行的写可能是 `content`/`attrBuf`/其它列的 UPDATE，不体现在 `sourceType` 差分里；`blockstat` 的 `updSeen`/`updNoSrcKey` 只给全局计数 | `SnsBlockProbe.kt:730`；`SnsGuard.kt:366-369` |
| 5 | **异步写 / 快照窗口错位** | `op=sql` 是 `io{}` 后台线程（`EntryReceiver.kt:657-661`），与微信写库线程并发；6 条按 `snsId` 分页的执行时序未知，前后快照可能整个错过写窗口 `[需真机]` | `EntryReceiver.kt:657-661`；`SnsSqlRewrite.kt:12` |
| 6 | **冷启清零 / 计数口径** | `SnsBlockProbe` 计数纯内存、进程死即清零 ⇒ `blockstat` 的「0」不能反推「没发生」。（精确口径：`stat()` 读的是 `AtomicInteger` 全量计数，**不受** 60s 汇总节流 / 5s 命中节流影响，节流只作用于日志行 — `inventory:335`；但 `dev.py q` 拉日志受 LogX 5000 行环形覆盖影响 `inventory:337`） | `SnsBlockProbe.kt:28`, `:49`, `:52`；`inventory:333-337` |
| 7 | **`snsDb` 未捕获 ⇒ 快照恒 0 行** | `op=sql` 返回 `SQLX no sns db yet` 会被误读成「S 为空 / 没写」；须先 `op=snsg sub=stat` 看 `snsDb=true`（`SnsGuard.kt:1637`） | `SnsGuard.kt:1280`；`inventory:338` |
| 8 | **全局计数不定位行身份** | `sqlClear9>0` 可能来自「昵称B」或三天内行的写；`=0` 也可能是窗口错位/冷启清零；`sqlUnmatched` 连「所有写事件」都代表不了 | V3 §3.2；`SnsBlockProbe.kt:589-597`, `:720-738` |
| 9 | **开关关闭时照样记账** | 用 `blockstat` 做对照必须同时记录开关快照，否则把「开关关」误读成「没写事件」 | `SnsGuard.kt:294-295`；`inventory:340` |
| 10 | **`SnsHuntProbe` 补原文也有顺序混淆** | 0 装包最全的 `op=sns sub=on\|catch` 能抓完整 SQL + 24 帧栈，但：①临时窗口 10~600s、全量噪音 `MAX_LINES=260`、需人工拉 `wxgroup_dbg.txt`；②无 createTime 列值、无行身份展开、要与 `op=sql` 的 S 手工 join；③它与 `SnsGuard.sqlGuard` 同为 `hookAllMethods` 的 before，LSPosed 回调顺序不确定 ⇒ **可能看到已被改写成 `& -1` 的 SQL** 而非微信原本的 `& -9` `[推断][需真机]` | `SnsHuntProbe.kt:53`, `:55`, `:57`, `:70`, `:181-196`；`SnsGuard.kt:900-914` |
| 11 | **「区间相交」≠「行级命中」** | 即使拿到 6 条 `& -9` 的 `gt/lt`，也只能判「区间 ∩ S 是否非空」；`execSQL` 的实际命中集合由 WHERE 决定，`changes()` 不可观测 ⇒ 判不了「写事件实际命中 S 的哪些行」。而 lane A 的生死假设是「服务端不下发 ⇒ 本地不会执行写」（`forensics:189`、`:345` 转引），需要的是**行级**的「有/无写」 | `forensics:189`, `:239`, `:260`, `:272`；V3 §3.3 |

### 4.3 对 lane E「半自动零新增代码」方案的复核

lane E 自己也留了退路：若接受「临时探针 + 人工拉文件 + 手工 join」的半自动方案，则零新增代码也能**勉强**取证，但证据强度最弱（`inventory:324`, `:358`）。本裁决采纳「最弱证据」这一定性，并补两条它没点名的硬伤：
- 它仍受 §4.2-1/2/3（结构性混淆）与 §4.2-10（hook 顺序）影响；
- 它**只回答「SQL 原文/调用栈」**，不回答「写事件按 `createTime` 归属到三天外行」，而这正是硬判据的定义（`inventory:227`, `:351`）。

⇒ **V4 结论：不存在不新增探针的充分判据组合。** 要从「全局计数 / SQL 原文 / 快照差分」跨到「行级写事件 + `createTime` 归属」，必须有 lane E B 类那种**按 `snsId`/`rangeKey` 展开并 join `createTime` 的结构化观测代码**（`inventory:317-322`），即**必须新增代码 ⇒ 装包**。C0 及其变体最多只能作为**阶段一的弱线索 / 前置排除**，不得用作三态判定的决定性输入。

[DONE] V4

## V5 · ★ 最终裁决：**选 (c) 分两阶段**（并明确判 lane D 错、lane E 方向对但结论过强）

> ⛔ 本节不和稀泥。先各自定性，再给唯一解与唯一触发条件。

### 5.1 判 lane D：**核心结论错误**（三条独立否决，任一条即可推翻「0 装包足够判定 R-C 生死」）

| # | 否决理由 | 锚点 |
|---|---|---|
| D-1 | **V2 假阴性**：`SnsSqlRewrite.kt:46` 的 `& -9 → & -1` no-op 改写在 `execSQL` before 里改 `param.args[0]`（`SnsGuard.kt:900-914`、`:895`），SQL **照常执行且命中集合/`changes()` 不变**（`SnsSqlRewrite.kt:16-21`；`SnsGuard.kt:62`）⇒ `op=sql` 前后快照必然 0 差异。lane D 的 L1/L3 基座（`design:174`, `:301`）失效。 | `SnsSqlRewrite.kt:16-21`, `:42-48`, `:77-85`；`SnsGuard.kt:62`, `:872-892`, `:895`, `:900-914` |
| D-2 | **把设计稿输出当成现成能力**：lane D 判死所需的 `hitS` / `beforeCnt` / `onSCnt` / `W2 gt,lt,inList` / `RCP_*` TAG（`design:184`, `:296-316`, `:309-310`）**现成 APK 一行都不打**；现成前缀是 `BLOCK ` / `TRACE ` / `SQLX ` / `SNSG `（`SnsBlockProbe.kt:196`, `:586`, `:795`；`SnsGuard.kt:1293`；`EntryReceiver.kt:349`）。要产生这些行，本身就**必须新增代码 ⇒ 装包**。 | `design:184`, `:296-316`；V1 §1.4；V3 §3.2 |
| D-3 | **行身份不可定位**：`blockstat` 全是全局聚合计数（`SnsBlockProbe.kt:720-738`），`& -9` 走 `noteSql` 不创建 Event（`:552-598`）、无 `createTime` 关联、SQL 原文只在去重封顶 60 的日志流 ⇒ 无法把「写事件」归属到三天外行。 | V3 §3.2-3.3；`inventory:221-225` |

⇒ lane D「决定性判定探针全部复用现成 `op=sql`/`blockstat`/`dev.py ui`，**0 次 adb install**」「判死最省只需 L1+W1/W2 两条」（分歧原文；`design:157`, `:275`, `:443`）**不成立**。

### 5.2 判 lane E：**方向正确，但结论过强，且有一处事实错误**

- ✅ **对的部分**：「挂载点一个不缺（`updateWithOnConflict`/`execSQL` 常驻钩子 + `SnsHuntProbe` 全量临时钩子都在），缺的是把写事件与 `createTime`/`userName` 关联的结构化观测代码」——与 V3/V4 完全一致（`inventory:177`, `:227`, `:324`, `:358`）。「硬判据必须新增代码 ⇒ 装包」在「S 非空」分支上**成立**。
- ❌ **错的部分 1（事实错误）**：「`blockstat`/`trace` 无 `createTime`」**不属实**，`Event.ct = bits?.createTime`（`SnsBlockProbe.kt:347`）且 `line()` 打 `ct=$ct`（`:198`）、`stat()` 输出最近 10 事件行（`:739-741`）。lane E `inventory:200`、`:350` 把它写成「已证事实」是错的。所幸 `ct` 只在 update 拦截通道、`& -9` 的 execSQL 通道无 Event ⇒ **不影响其核心结论**（V1 §1.3）。
- ❌ **错的部分 2（结论过强）**：lane E 的「必须装包」是**无条件**的；但 lane A 已把 Q3「三天外行本地是否还在」定为 R-A/R-B/R-C **共有的唯一前置**，并明确「①不成立 ⇒ 整题收口 / 只留 R-A 或收口」（`forensics:129`, `:169`, `:271-273`）。若 S 为空或 BLOB 空，**0 装包即可判 R-C 证伪**，lane E 在这一分支上多算了一次装包。

### 5.3 唯一解：**(c) 分两阶段**（不是「两者都有道理」，是「D 错 / E 补一个前置闸门」）

| 阶段 | 装包 | 做什么 | 能判到哪一步 |
|---|---|---|---|
| **阶段一 · 0 装包前置排除** | **0 次** | 上机三件事（`installed` / `op=logclear` / 微信前台）+ 先刷一次朋友圈捕获 `snsDb`，然后只跑 Q3 §3.2 的四条只读 `op=sql`（`pragma table_info(SnsInfo)` 带括号、②三天外行 + `hex()` 完整性、③`older3d` 计数、④位状态）+ `datetime(createTime,'unixepoch')` 校准 `<T0>` 单位 | 判定**共性前置**：S 是否存在 / `content`/`attrBuf` 是否完整 / `createTime` 单位是否校准 |
| **阶段二 · 1 次装包硬判据** | **1 次**（所有改动攒齐一次 `adb install`，`CLAUDE.md:62`） | 新增 `SnsWriteProbe` + 扩写 3 文件，做「写事件按 `snsId`/`rangeKey` + `createTime` 结构化关联」、`& -9` 区间展开、A1 互斥归因、三档边界、干扰排除 | 判定 R-C 生死（可实现 / 证伪 / 证据不足） |

### 5.4 ⭐ 进入阶段二的**唯一、可复算**触发条件（题面硬要求）

> **触发条件 T**（单条 SQL 的布尔结果，无主观解释空间）：
>
> ```sql
> select snsId,userName,createTime,sourceType,
>        length(content) as clen, length(attrBuf) as alen,
>        hex(substr(content,1,16)) as chead, hex(substr(attrBuf,1,16)) as ahex
> from SnsInfo
> where userName='<wxidA>' and createTime < <T0>
> order by createTime desc
> ```
>
> **T =（返回行数 > 0）AND（至少一行满足 `clen>0` AND `chead<>''` AND `alen>0`）AND（`<T0>` 已用 `datetime(createTime,'unixepoch')` 与界面最新一条反推校准）**。
> 锚点 `forensics:140-171`（判据 ②）、`:114`（Q3「没有真机取证」）、`:259`/`:262`（单位校准列在「必须真机才能定」）。

- **T = true ⇒ 进入阶段二，装 1 次包**。此时「S 非空且内容完整」已坐实，剩下唯一未知就是 R-C 生死假设「S 上是否有写事件」，按 V2/V3/V4 必须靠新增结构化探针回答。
- **T = false ⇒ 阶段一即收口，R-C 证伪，装包 0 次**：0 行或 `clen=0`/`chead=''` ⇒ 无米之炊，R-A/R-B 一起收口（`forensics:169`, `:271`, `:273`）。
- **可复算性**：同一真机、同一 `<wxidA>`、同一已校准 `<T0>`、同一条 SQL ⇒ 同一布尔值；`op=sql` 走 `runReadOnlySql` 只读白名单（`SnsGuard.kt:1268-1282`），任何操作员/复核 lane 重跑得到同一结果。⛔ 不引入「人工权衡」「看日志涨没涨」这类不可复算量（`design:74`, `:337` 明令字节增长不算执行自证）。
- **唯一性**：阶段一**不**用 `blockstat` 计数、`SnsHuntProbe`、界面条数做触发（V2/V3/V4 已证这些都不可靠）；触发只由这一条 SQL 决定。

### 5.5 为什么不选 (a) / 纯 (b)

- **(a) 0 装包足够**：被 V2（必然假阴性）、V1 §1.4 + V3（现成无 `hitS`/行身份）、V4（无充分组合）三条独立否决。
- **纯 (b) 无条件装包**：在 `T = false` 分支上浪费一次 `adb install`，且违背 lane A 已定的「最小第一步 = 四条只读 `op=sql` + 抓现成事件账本」（`forensics:271-273`）与 `CLAUDE.md:26`「取证一次重启抓完所有问题」的省轮次纪律。
- ⇒ **(c) 是严格更优**：把 D 唯一站得住的部分（Q3 前置 0 装包可判）保留为阶段一，把 E 唯一站得住的部分（硬判据必须新增代码）收敛为「仅当 T=true 才发生」的阶段二。

### 5.6 装包次数与新增探针数（先给结论，V7 展开改动面）

- **最少装包次数 = 0**（T=false 直接收口）；**完整判死/判活闭环 = 1 次**（T=true 时一次攒齐装完，`CLAUDE.md:62`；lane D 的「可选调用栈增强最多 1 次」并入同一次，不另开轮次 — `design:270`）。
- **必须新增的探针 = 1 个类 `SnsWriteProbe`**；配套**扩写 3 个既有文件**（`SnsGuard.kt` / `SnsBlockProbe.kt` / `EntryReceiver.kt`），均非新增类（`inventory:317-324`）。
- **旁证锚点**：`forensics:272` 最小第一步第 2 条本就要求「同一真机抓 `SnsBlockProbe` 事件账本看三天外行上是否真的出现写事件」，而 V3 已证现成账本**做不到** ⇒ 这一步只能由阶段二的新增探针补上。

[DONE] V5

## V6 · 最终探针清单总表（合并 lane D 设计 × lane E 盘点，去重后按四层排序）

> **前提编号**沿用 lane D 判据树（`design:126-147`）：P1.1 三天外行仍在+BLOB 完整 / P1.2 `createTime` 单位校准 / **P1.3 三天外集合 S 上存在写库事件（决定性·一票否决）** / P1.4 写事件发生时 before 钩子确实回调 / P1.5 写回后不被二次覆盖 / P1.6 保位⇒翻页用户可见。
> **只读性**：除标 ⚠️ 者外全部「只读·不写微信库」（账本纯内存 `SnsBlockProbe.kt:28`、`op=sql` 白名单 `SnsGuard.kt:1271`）。**装包列**：只有阶段二三条需要，且属**同一次** `adb install`（V5 §5.3）。
> **去重**：合并 D 的 L/W/A/U/T/C/X 七层与 E 的 I1-I7；剔除空分支 `op=snsg sub=sqlspy|srcmap|dump`（`EntryReceiver.kt:384`）、`op=revoke`（并入 P-06 作交叉验证）、重复项 L2/L4/W5/A3/A4/U2/X5（并入相邻编号）、以及 lane D 的 `RCP_*` 目标输出格式（由 P-02 新增探针统一承接，不单列）。

### ① 决定性判据层

| 编号 | 观测什么 | 复用/新增 | 挂点或 op | 只读性 | 输出字段 | 对应前提 | 装包 | 用户配合 |
|---|---|---|---|---|---|---|---|---|
| **P-01** | **Q3 前置 + 唯一触发条件 T**：三天外行 S 存在性、`content`/`attrBuf` BLOB 完整性、位状态、`older3d` 计数、`createTime` 单位校准（`forensics:140-171` 四条 SQL） | 复用现成 | `op=sql` → `SnsGuard.runReadOnlySql`（`EntryReceiver.kt:654-662`、`SnsGuard.kt:1268`） | 全只读 | `snsId,userName,createTime,sourceType,clen,alen,chead,ahex,bit1,bit3,older3d,T0` | **P1.1 / P1.2（阶段一唯一触发 T）** | **0** | ✅ 请「昵称A」设三天可见且设前已有三天外历史动态 |
| **P-02** | **结构化写事件账本**：`updateWithOnConflict`/`execSQL`/`delete`/`insert*` 每次写按 `snsId`/`rangeKey`/`userName` + `createTime` 关联，产出 `hitS`（是否∈S） | **新增 `SnsWriteProbe`**（V7） | 复用 `SnsGuard` 6 常驻钩子 before/after 回调（`SnsGuard.kt:149-180`、`:356`、`:834`），**不新挂方法** | 全只读（内存账本+落文件） | `ts,meth,half,table,snsId,rangeKey,userName,createTime,hitS,cvKeys:Set<String>,caller,thread,sqlHash,shape` | **★ P1.3 决定性 / P1.4** | **1（阶段二）** | ✅ 微信前台进「昵称A」主页并翻页 |
| **P-03** | **`& -9` 专项**：进主页 6 条清位 SQL 的 `userName` + `snsId` 区间展开、与 S 求交、`hitRows` | **新增**（复用 `SnsSqlRewrite.rangeKey:93`、`SnsSql.argAt:69`） | `execSQL` before → `onSnsSql`（`SnsGuard.kt:872-892`） | 全只读 | `seq(1..6),mask,userName,gt,lt,inList,hitS,hitRows,sqlHash` | **★ P1.3 决定性** | **1（同一次）** | ✅ 进「昵称A」主页逐屏翻页 |
| **P-04** | **A1 互斥归因**：底层方法进入数 vs before 回调数 vs S 上命中数，二选一定性（`design:201-205`） | **新增**（计数扩写 + `verdict`） | 6 常驻钩子 | 全只读 | `rawMethodCnt,beforeCnt,onSCnt,verdict∈{NO_WRITE,WRITE_NO_HOOK,WRITE_ON_S,WRITE_OFF_S}` | **P1.3 / P1.4** | **1（同一次）** | ❌ |
| **P-05** | 六钩子加载自证 | 复用现成 | `dev.py q 'SNS installed'`（`SnsGuard.kt:186-188`；`CLAUDE.md:75-76`） | 只读 | `installed=delete,execSQL,updateWithOnConflict,updateWithOnConflict.before,insertWithOnConflict,insert` / `blockEnabled` / `allSix` | P1.4 | 0 | ❌ |

### ② 强证据层

| 编号 | 观测什么 | 复用/新增 | 挂点或 op | 只读性 | 输出字段 | 对应前提 | 装包 | 用户配合 |
|---|---|---|---|---|---|---|---|---|
| **P-06** | `SnsHuntProbe` 全量临时钩子：全 DB 写方法 + SQLiteStatement + **完整 SQL 原文 + 24 帧栈**，落 `wxgroup_dbg.txt`；`op=revoke` 作交叉验证 | 复用现成 | `op=sns sub=on` 或 `catch`（`EntryReceiver.kt:296-302`；`SnsHuntProbe.kt:181-196`） | 只读（落模块文件） | SQL 原文、`getPath()`、表名、ContentValues、调用栈 | P1.3 / P1.4（弱·需人工 join，V4 §4.2-10） | 0 | ✅ 窗口 10~600s 内进主页 |
| **P-07** | `blockstat` 6 行全局计数 + 最近 10 个 Event 行（含 `ct`/`keys`/`shape`） | 复用现成 | `op=snsg sub=blockstat`（`EntryReceiver.kt:378-380`） | 只读 | 全局计数 + `Event.line()` | P1.3 弱证据 / P1.5 | 0 | ❌ |
| **P-08** | `BLOCK w2 sql-clear9` SQL 原文采样日志（含 `userName`/`snsId` 区间） | 复用现成（日志流） | `dev.py q 'BLOCK w2'`（`SnsBlockProbe.kt:586`） | 只读 | SQL 原文（去重≤60、单行 1500 字符） | P1.3 弱证据 | 0 | ❌ |
| **P-09** | 开关态与 `snsDb` 句柄 | 复用现成 | `op=snsg sub=stat`（`SnsGuard.kt:1635-1637`） | 只读 | `blockEnabled,sqlBlock,albumFix,snsDb` | P1.4 / 干扰 | 0 | ❌ |
| **P-10** | **C4 阳性对照**：请「昵称A」删一条动态，确认账本能抓到 `want=0` 写（`moments.md:1-19`、`SnsGuard.kt:34-36`） | 复用现成 | 同 P-02/P-07 | 只读（删动态是**用户行为**，探针不写库） | 抓到/未抓到 `want=0` | A1 归因有效性自证（抓不到=探针坏，不得判死） | 0 | ✅ 请「昵称A」删一条动态 |
| **P-11** | **C2 阴性对照**：「昵称B」全部可见，建正常写事件基线 | 复用现成 | 同 P-02/P-07，`userName='<wxidB>'` | 只读 | 基线写事件数 | 排除探针失效假阴性 | 0 | ✅ 请「昵称B」保持全部可见 |
| **P-12** | **C1 反向对照**：`sns_block` off **且** `antirevoke.sns` off（⛔ 两个都关，`SnsGuard.kt:195`），复现一次 | 复用现成开关 + **kvset 交主 agent** | `op=snsg sub=block --es sub2 off` + `op=kvset --es key antirevoke.sns --es val 0`（`EntryReceiver.kt:176-183`） | ⚠️ kvset 写**模块配置库**（非微信库；本 lane 禁执行） | 开关态、关后写事件基线 | 排除「写事件是模块自造」 | 0（改配置非装包） | ✅ 配合复现 |
| **P-13** | W3 `afterShape` 四态 / `before→after` 写回形态 | 复用现成（Event.shape）+ P-02 增强 | `blockstat`（`SnsBitLogic.afterShape`） | 只读 | `shape∈{rm,rw,nb,un}`、`keep` | P1.5 | 0 | ❌ |
| **P-14** | U1/U3 界面：首屏/翻页可见条数、**首屏与翻页分离**（`wm:40-41`） | 复用现成 | `dev.py ui <文本>`（`design:211`；`CLAUDE.md:59`） | 只读（uiautomator，不写库） | `scene,uiVisible,older3d,older3dShown,firstScreenHasOlder` | P1.6 | 0 | ✅ 翻页 |
| **P-15** | L5/W5 重复行风险（`snsId` 无唯一约束 `moments.md:214-217`） | 复用现成 | `op=sql`：`select snsId,count(*) c from SnsInfo group by snsId having c>1` | 只读 | 重复 `snsId` 清单 | P1.5 / 复位档 | 0 | ❌ |

### ③ 边界三档层（进入 / 返回 / 复位逐档，复位≠返回 `forensics:230-245`）

| 编号 | 观测什么 | 复用/新增 | 挂点或 op | 只读性 | 输出字段 | 对应前提 | 装包 | 用户配合 |
|---|---|---|---|---|---|---|---|---|
| **P-16** | T1/T2 逐档统一时基与子场景标记（进入 / 返回 / 复位） | 复用现成 | `op=logclear` + 档位标签 + `dev.py q` | 只读 | `ts,phase,mark,预期,实测` | Q6 三档 / 全 | 0 | ✅ 切页、下拉、后台同步、冷启 |
| **P-17** | T3 冷启后 `snsDb` 重捕获时刻 | 复用现成 | `dev.py q 'sns db captured'`（`SnsGuard.kt:1016`、`:1280`） | 只读 | 捕获时刻 | 复位档 / 干扰 | 0 | ✅ 冷启 |
| **P-18** | C3 self-exclude：「昵称C」=自己，动态写应被放行 | 复用现成 | `SnsGuard.isSelfAuthor`（`SnsGuard.kt:50-54`） | 只读 | 自己行是否被保位 | P1.5 / 竞态 | 0 | ✅ 确认「昵称C」为自己 |
| **P-19** | 复位档残留：库位/`[已删除]\n` 前缀磁盘态、对方改回全可见后的重复条、清缓存后 S 是否还在 | 复用现成 | `op=sql` + `dev.py ui` + P-15 | 只读 | 位/前缀/重复条/S 行数 | Q6 复位档（残留不回滚 `_r42_purge_report.md:51-56`） | 0 | ✅ 改回全部可见 / 清缓存 |

### ④ 干扰排除层

| 编号 | 观测什么 | 复用/新增 | 挂点或 op | 只读性 | 输出字段 | 对应前提 | 装包 | 用户配合 |
|---|---|---|---|---|---|---|---|---|
| **P-20** | X1 LogX 5000 行环形规避：**每窗口前** `op=logclear`，窗口末立刻拉回本地 | 复用现成 | `op=logclear`（`EntryReceiver.kt:165-168`；`dev.py:250-253`） | 只读（清模块日志） | `chk=logclear,ok` | 全 | 0 | ❌ |
| **P-21** | X2 专属 TAG + `dev.py q` 过滤；用「目标 TAG 出现」而非日志字节增长判执行（`K7 perf` 刷屏） | 复用现成 | `dev.py q '<TAG>'`（`design:74`, `:337`） | 只读 | `TAG_present` | 全 | 0 | ❌ |
| **P-22** | X3 微信前台 + `dbg_size` 回读 + 广播投递自证（`Broadcast completed` 不算） | 复用现成 | `am start -n com.tencent.mm/.ui.LauncherUI` + `dev.py probe` 三道闸（`design:76`） | 只读（拉起前台） | `前台,dbg_growth,installed` | 全 | 0 | ✅ 保持微信前台 |
| **P-23** | X4 `snsDb` 捕获时机（先刷一次朋友圈） | 复用现成 | `op=snsg sub=stat` / `dev.py q 'sns db captured'` | 只读 | `snsDb=true` | P-01 可用性（`SnsGuard.kt:1280`） | 0 | ✅ 先刷一次朋友圈 |
| **P-24** | X6 广播二次解析（`_sq` 自动包单引号）+ 写完回读复核；`--el` 仅 signed long，无符号 snsId 走 `--es` | 复用现成 | `dev.py probe --q/--val`（`dev.py:20`, `:66-81`, `:226`） | 只读 | `ok` | P-01 正确性 | 0 | ❌ |
| **P-25** | X7 旧代码/加载自证：`logclear` 出现且不含 `unknown op=logclear` + P-05 六钩子齐全 | 复用现成 | `dev.py q`（`dev.py:141-152`） | 只读 | `installed,not_stale` | 全 | 0 | ❌ |
| **P-26** | 昵称↔wxid 映射（汇报一律昵称，`CLAUDE.md:12-18`） | 复用现成 | `op=label sub=sql --es sql 'select username,nickname,conRemark from rcontact …'` | 只读 | 昵称 / wxid 映射 | 汇报纪律 | 0 | ❌ |

### ⛔ 标红：本质上要写微信库 / 写配置库的探针，及只读替代

| 探针 | 为什么本质要写 | 本轮处置 | 只读替代 |
|---|---|---|---|
| **「验证 P1.5/P1.6 完整闭环」**：真启用 R-C 生产逻辑——`snsBlockGuard` before 写回 `sourceType`（`SnsGuard.kt:262-263`、`:297-298`）、`attachPrefix` 补 content BLOB（`:716`） | **写微信库** | ⛔ 本轮不实现 R-C、不写生产逻辑（硬约束） | P-01（L3 现有位状态）+ P-10（C4 阳性对照：R42 删动态场景已跑通同机制，`moments.md:1-19`、`wm:37-42`）+ P-13（W3 `afterShape` 只读形态）。三者**只给间接证据** ⇒ 三态里落「证据不足」不粉饰（`design:251`） |
| **P-12 的 `op=kvset antirevoke.sns`** | 写**模块配置库**（非微信库） | ⛔ 本 lane 禁 kvset，交主 agent 在阶段二轮统一执行 | `op=kvget` 只读回读开关态 + 消费关后的 `blockstat` 结果 |
| **`op=snsprefix sub=simdrop\|setsrc2 --el id`** | 写微信 `SnsInfo.sourceType`（`inventory:290`, `:309`） | ⛔ **禁用**：是模块自己发的写，**不能证明 R-C**（微信是否写） | P-10（请用户删一条动态 = 真实微信写） |

> 全表 **26 条**：需装包的只有 **P-02 / P-03 / P-04（同一次）**；其余 **23 条全部 0 装包**。需用户配合的共 13 条，统一在 V8 §S0 一次交代。

[DONE] V6

## V7 · 装包与改动面（仅在 V5 触发条件 T=true 时发生，**1 次**）

### 7.1 必须新增 / 修改的文件清单（到类与函数级）

| # | 文件 | 新增/扩写 | 类与函数级改动 | 预估行数 `[推断]` |
|---|---|---|---|---|
| 1 | **`wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsWriteProbe.kt`** | **新建 1 个类** | `object SnsWriteProbe`：`onUpdateWrite(param)` / `onExecSqlWrite(sql, bindArgs, rangeKey)` / `classify(snsId)`（用 `SnsGuard.snsDbHandle()` + `SnsBlockProbe.readBits` 取 `createTime`，比 `now-3*86400`，单位**秒** `MomentsLogic.kt:197` 转引 `inventory:200`） / `byUser(user)` / `stackOf()`（复用 `SnsHuntProbe.NOISE` 过滤，`inventory:286` 转引 `SnsHuntProbe.kt:145-154`） / `stat()` / `flushToFile()`（复用 `SnsHuntProbe.kt:53` 写文件写法） / `switch()`（默认关） / `Event` 输出 P-02 字段集 | ~200~300 |
| 2 | `feature/SnsGuard.kt` | 扩写 | `onSnsUpdateBefore`（`:356`）与 `onExecSql`（`:834`）在**零反射短路之后**（`:360`、`:853`）各加一行回调 `SnsWriteProbe.onUpdateWrite/onExecSqlWrite`；**不新挂方法**（6 个常驻钩子复用，`SnsGuard.kt:149-180`）。条件性 B6：仅当真机日志显示写只出现在 `SQLiteStatement` 层时，才在 `install()`（`:132`）补 `hookAllMethods`（`inventory:292`, `:322`，`[推断]` 非必需） | ~20~40 |
| 3 | `feature/SnsBlockProbe.kt` | 扩写 | `Event`（`:167-199`）增 `userName` / `cvKeys:Set<String>` / `caller` / `rangeKey`；⚠️ **`createTime` 已有 `ct` 字段（`:185`, `:347`），不必重复新增**，只需把 `ct` 纳入结构化输出口径（V1 §1.3 订正 lane E 的事实错误）。`noteSql()`（`:552-598`）增 `rangeKey/userName/hitRows` 参数并结构化落账；`stat()`（`:718-743`）增一行 `write` 汇总 | ~60~100 |
| 4 | `entry/EntryReceiver.kt` | 扩写（**接线类**） | `op=snsg` 的 `when(sub)`（`:348-385`）增 `sub=write [--es user <wxid>] [--el ct <秒>]` 分支 → 转发 `SnsWriteProbe.byUser()/stat()`（**接在 `:384` 的 `else` 之前**，避免落 `SNSG unknown sub=`） | ~10~20 |
| 5 | `data/Keys.kt` | 扩写（**接线类**） | 可选新增 `PROBE_RC_ENABLED = "probe.rc.enabled"` 常量（默认 false，`design:284`）；不改 `antirevoke.*` 四个既有键（`Keys.kt:145/:161/:174/:306`） | ~2~5 |

**复用但不改**：`SnsSqlRewrite.rangeKey`（`SnsSqlRewrite.kt:93-98`）、`SnsSql.argAt`（`SnsSql.kt:69`，`inventory:283` 转引）、`SnsBitLogic.afterShape`（`SnsBitLogic.kt:137-178`）、`SnsHuntProbe` 写文件与 NOISE 过滤、`SnsBlockProbe.readBits`（`:648-671`）。

**总量** ≈ **290~465 行** `[推断]`。⚠️ 与 lane A Q4 给「R-C 路线 150~300 行」（`forensics:186`）不是同一件事：那是**实现 R-C 生产逻辑**（before 写回 `sourceType` / `attachPrefix` 写 BLOB），本轮是**只读探针**，不写微信库。

### 7.2 是否写微信库：**零写**（探针全只读）

| 观测动作 | 写微信库？ | 依据 |
|---|---|---|
| `SnsWriteProbe` 事件账本 | ❌ 不写 | 纯内存 `AtomicInteger`/ring（`SnsBlockProbe.kt:28`, `:68-133`）；落盘只写模块自己的 `wxgroup_dbg.txt`（`SnsHuntProbe.kt:53`） |
| `classify(snsId)` 取 `createTime` | ❌ 不写 | `readBits` 是 `select ... from SnsInfo where snsId=…`（`SnsBlockProbe.kt:654`） |
| P-01 阶段一四条 `op=sql` | ❌ 不写 | `runReadOnlySql` 白名单只放 `select`/只读 pragma、拒 `with`/内部 `;`（`SnsGuard.kt:1271`, `:1255-1262`, `:1277-1279`） |
| `op=snsg sub=block` / `kvset` | ❌ 不写微信库 | `switch()` 只切 Kv（`SnsBlockProbe.kt:703-713`）；kvset 写**模块配置库**（`EntryReceiver.kt:176-183`） |

⛔ **标红（V6 已列，此处呼应）**：唯三「本质要写」的是 ①真启用 R-C 生产逻辑（写微信库，本轮**不实现**）、②`kvset antirevoke.sns`（写模块配置库，本 lane **禁执行**、交主 agent）、③`op=snsprefix sub=simdrop|setsrc2`（写微信 `SnsInfo`，**禁用**，是模块写不能证 R-C）。三者只读替代见 V6 标红表；**本轮装进去的探针代码一个字节都不写微信库**。

### 7.3 按 `CLAUDE.md` §四① 分包下限：应派 **≥2 个实现 lane（Pro 档）**

- 判定依据（**不是自评忙不忙**）：「本轮改动落在几个不重叠的功能文件」（`CLAUDE.md:85`）；「**新建**的功能文件（探针/脚本/新模块）**单独计数**；接线类改动（加分支、加注册、改常量）**不计数**」（`:100-102`）；「数之前先 `git diff --stat` 把新建文件单列出来看」（`:102`）。
- 本轮计数：
  - 新建功能文件：`SnsWriteProbe.kt` → **1**
  - 独立功能文件（扩写）：`SnsGuard.kt` → **1**、`SnsBlockProbe.kt` → **1**
  - 接线类（**不计**）：`EntryReceiver.kt` 加 `sub=write` 分支、`Keys.kt` 加常量
  - ⇒ **3 个不重叠功能文件** ⇒ 按 `CLAUDE.md:89`「≥3 个独立功能文件 ⇒ **≥2 个**」⇒ **至少 2 个实现 lane**。
- **档位**：改动落在 `execSQL`/`updateWithOnConflict` 的 before 半场、反射 `readBits`、写库热路径 ⇒ 属 **Hook/反射/异步时序级改动**，按 `CLAUDE.md:111`、`:116` 判 **Pro**（「审一个 Hook/反射/异步时序/并发改动 ⇒ Pro，即使 diff 只有 10 行」）。按 `:117-124`「⛔ Pro 类活不许主会话自己做」必须开子代理；按 `:149-152`「① 与 ② 冲突取严」⇒ **至少 2 个 `model="custom-local:deepseek-v4-pro-202606"` 的 Pro 实现 lane**。
- **配套**：判据（区间展开 / `createTime` 三天外分类 / A1 归因 verdict）按 `CLAUDE.md:27`、`:63` 放进**零 Android 依赖类 + JVM 单测**（范式 `SnsTagLogic.kt`），可另派 Flash lane（`CLAUDE.md:112`）；**复核 lane 与实现 lane 分开计数**（`:93`）⇒ 本裁决 lane F 只读复核**不计入**实现 lane 数。
- **流程纪律**：台账「先记后派」（`CLAUDE.md:185-190`，`spawn 传的 model 串` 必填）；「派完立刻在同一 turn 继续做实事、派发零等待成本」（`:144-146`）；所有改动**攒齐一次 `adb install`**（`:62`），并按 `CLAUDE.md:26`「一次重启抓完所有问题」把阶段二的 P-02/P-03/P-04 + W4/A3 可选增强合并进同一包。

[DONE] V7

## V8 · 真机步骤 + 三档边界 + 三态判定表（最终可执行）

### S0 · ⭐ 需要用户配合做什么（必须先做；⛔ 提及好友一律用昵称，wxid 仅作 SQL 参数）

1. 请好友**「昵称A」**把朋友圈设为「允许查看的范围 = 最近三天」，且**设之前相册里已有三天以外的历史动态** —— 否则 S 为空、触发条件 T=false，阶段一即收口（`forensics:169`）。
2. 请好友**「昵称B」**保持「全部可见」，作阴性对照（P-11 / `design:231`）。
3. 确认**「昵称C」= 自己**，作 self-exclude 对照（P-18 / `SnsGuard.kt:50-54`）。
4. 阶段二需「昵称A」再配合：**删一条动态**（P-10 阳性对照）、**改回全部可见**、**清缓存**（P-19 复位档）。
5. 拿 wxid↔昵称映射：`python tools/dev.py probe label sql --q "select username,nickname,conRemark from rcontact where nickname like '%<昵称>%'" --grep PROBE`（`CLAUDE.md:17`）。⛔ 汇报/日志转述/步骤里**只写昵称**，不许只报 wxid（`CLAUDE.md:12-18`）。

### S1 · 上机三件事 + 加载自证 + 三条硬纪律

```
python tools/dev.py restart                      # 双次 force-stop，模块重载、LogX 归零(dev.py:110-121)
python tools/dev.py q 'SNS installed'            # P-05：确认 6 个钩子齐全(SnsGuard.kt:186-188)
python tools/dev.py probe logclear --clear        # 或任意 probe 带 --clear；P-20
python tools/dev.py q 'logclear'                  # P-25：出现 logclear 且不含 unknown op=logclear(dev.py:141-152)
am start -n com.tencent.mm/.ui.LauncherUI        # P-22：每次广播前微信前台
python tools/dev.py q 'sns db captured'           # P-23：确认 SNS sns db captured via rawQuery(SnsGuard.kt:1016)
```
先刷一次朋友圈让 `snsDb` 被捕获；若报 `SQLX no sns db yet`（`SnsGuard.kt:1280`）再刷一次（`inventory:338`）。

| 硬纪律 | 正确做法 | 锚点 |
|---|---|---|
| **`op=logclear` 时机** | **每个观测窗口前**必做（不是每重启一次）；窗口结束**立刻** `dev.py q` 拉回本地持久化，防 LogX 5000 行环形覆盖 | `CLAUDE.md:78`；`design:73`, `:335`；`inventory:23` |
| **微信前台要求** | 每次广播前 `am start -n com.tencent.mm/.ui.LauncherUI`；`Broadcast completed` 只证明 AMS 收下、**不证明投递**（接收器动态注册在微信进程 `HookEntry.kt:336`、`EntryReceiver.kt:111-133`）⇒ 用 `dbg_size` 增长 + 目标 TAG 出现双重确认 | `design:76`, `:240`；`dev.py:275-299` |
| **广播参数二次解析** | ①设备 sh 二次解析 ⇒ `--q` 值须再包一层单引号，`dev.py probe` 的 `_sq()` 自动包（`dev.py:20`, `:66-81`），写完 `dev.py q 'SQLX'` 回读复核（P-24）；②`op=kvset` 用 `--es key … --es val …`（不是 k/v，`EntryReceiver.kt:176-179`）；③`--el` **仅收 signed long**，无符号 snsId 会静默丢弃 ⇒ 走 `--es` 字符串（`dev.py:226`）；④`op=sql` 的 `--ei n` 默认 60 被 `clampProbeN` 钳到 **50**（`MomentsLogic.kt:1026/:1032`）；⑤`pragma table_info(SnsInfo)` **必须带括号**才放行（`SnsGuard.kt:1216-1242`）；⑥BLOB 只用 `hex()`，⛔ 禁 `cast(content as text)`（首个 NUL 截断假阴性，`docs/conclusions/moments_cdn.md:350-352`）；⑦TAG 大小写敏感，只读 SQL 是 `SQLX` 不是 `sql`（`dev.py:298-299`）；⑧action 恒为 `com.lx.wxgroup.action.PROBE -p com.tencent.mm`（`dev.py:95-96`） | `inventory:77`, `:266-270`；`design:78` |

### S2 · 阶段一（0 装包）：跑唯一触发条件 T

```
# 每条自带 --clear；每条完 dev.py q 'SQLX' 回读复核（P-24）
python tools/dev.py probe sql --q "pragma table_info(SnsInfo)" --grep SQLX --clear
python tools/dev.py probe sql --q "select snsId,userName,createTime,sourceType,length(content) clen,length(attrBuf) alen,hex(substr(content,1,16)) chead,hex(substr(attrBuf,1,16)) ahex from SnsInfo where userName='<wxidA>' and createTime < <T0> order by createTime desc" --grep SQLX --n 60 --clear
python tools/dev.py probe sql --q "select count(*) total,sum(case when createTime < <T0> then 1 else 0 end) older3d,min(createTime),max(createTime) from SnsInfo where userName='<wxidA>'" --grep SQLX --clear
python tools/dev.py probe sql --q "select snsId,createTime,sourceType,(sourceType&2) bit1,(sourceType&8) bit3 from SnsInfo where userName='<wxidA>' and createTime < <T0>" --grep SQLX --clear
python tools/dev.py probe sql --q "select snsId,createTime,datetime(createTime,'unixepoch') from SnsInfo where userName='<wxidA>' order by createTime desc limit 3" --grep SQLX --clear   # <T0> 单位校准
python tools/dev.py q 'SQLX'
```
- **T = false**（0 行 / `clen=0` / `chead=''` / 单位未校准）⇒ **R-C 证伪，装包 0 次，S3~S6 全跳过**（V5 §5.4）。
- **T = true** ⇒ 进入 S3（**1 次装包**）。⚠️ 阶段一**不发** `op=snsg sub=write`、不拉 `RCP_*`（这两样要阶段二装包后才有）；也**不**用 `blockstat`/界面条数做触发（V5 §5.4 唯一性）。

### S3 · 阶段二（仅 T=true；1 次装包，一次重启抓完）

1. 按 V7 清单改码 → **攒齐一次 `adb install`**（`CLAUDE.md:62`）→ `dev.py restart` → 重跑 S1 三件事。
2. `op=snsg sub=stat` 记开关快照（P-09）；按需 `op=snsg sub=block --es sub2 on`（`EntryReceiver.kt:375-377`）。
3. 微信前台 → 进入**「昵称A」**主页 → 逐屏翻页（每进一次 6 条 `& -9`，`moments.md:24-25`）。
4. 决定性判据（窗口前 `op=logclear`）：
```
python tools/dev.py probe snsg write --es user <wxidA> --el ct <T0> --grep 'RCP_W|SNSG' --clear   # P-02/P-03（新增分支，装包后才有）
python tools/dev.py probe snsg blockstat --grep SNSG --clear                                      # P-07
python tools/dev.py q 'BLOCK w2|SQLX|SNS installed|RCP_KEY'                                      # P-05/P-08
python tools/dev.py ui <昵称A>                         # P-14 首屏/翻页可见条数
python tools/dev.py ui <三天外动态文本片段>              # P-14 U3 首屏/翻页分离
```
5. 对照窗口（**同一重启内完成**）：P-10 C4（请「昵称A」删一条动态 → 账本须抓到 `want=0`）、P-11 C2（「昵称B」全可见基线）、P-12 C1（`sns_block` off **且** `antirevoke.sns` off，⛔ 两个都关，P-18 self-exclude）。
6. 窗口末：`dev.py q 'RCP_|SQLX|SNSG|SNS '` 全部拉回本地持久化。

### S4 · 返回档 / S5 · 复位档：逐子场景各清一次日志

每个子场景前 `am start ...`（前台）+ `op=logclear`，逐档重跑 P-02/P-03/P-07/P-14；冷启额外记 P-17 `SNS sns db captured` 时刻；复位档重跑 P-01/P-15/P-19。

### 8.5 三档边界逐档观测项（Q6 三档；**复位 ≠ 返回**）

| 档 | 子场景 | 该跑哪些探针 | 预期（用户可见事实） | 状态残留 | 锚点 |
|---|---|---|---|---|---|
| **①进入** | 刚设三天可见 / 刚进「昵称A」主页 | P-01(T) / P-02 / P-03 / P-05 / P-14 / P-22 / P-23 | 首屏只三天内；翻页**理论上**可能带出 S 中 `&2!=0` 的行 `[推断]`；6 条 `& -9` 按 `snsId` 区间分页 | 模块钩子/账本内存态、`snsDb` 句柄（拿到即卸载 captureHook `SnsGuard.kt:1016-1017`） | `forensics:53`, `:222`, `:232`；`wm:40-41`；`moments.md:24-25` |
| **②返回** | 切页再进 / 下拉刷新 / 后台同步回来 / 冷启 | 每子场景前 P-20 + 重跑 P-02/P-03/P-07/P-14；冷启加 P-17 | 切页再进每进一次 6 条清位写；下拉刷新用新响应覆盖内存列表 ⇒ 首屏回三天内、翻页查表仍可能带出库里的行；后台同步（`NetSceneSnsSync` getType=214）响应不含三天外 id；冷启钩子 reinstall + `snsDb` 重捕获，但**库里的位是磁盘态、冷启后仍在** ⇒ 反而可能比刷新后更容易翻到 | 账本内存态重建/冷启清零（`SnsBlockProbe.kt:28`）；**库磁盘态不变** | `forensics:238`, `:242`, `:266`；`moments.md:259-260`, `:263`；`MomentsSyncTrigger.kt:76` |
| **③复位** | 关开关（`sns_block` off + `antirevoke.sns` off，**两个都关**）/「昵称A」改回全部可见 / 清缓存 | P-01 重跑 / P-15 / P-19 / P-14 / P-12 | 判据停、界面恢复微信原生（只剩三天内）；改回全可见后被保位旧行与真实响应**可能重复 / 顺序异常** `[推断]`；清缓存若 `SnsInfo` 行被清 ⇒ 三天外行整个消失 `[需真机]` | ⚠️ **已写进微信库的 `sourceType` 保底值 / `[已删除]\n` content 前缀是磁盘状态，不随开关/冷启回滚**；R42 已删 `undoInsert/rollbackStep/restore/show` ⇒ **当前源码无任何回滚路径**（`_r42_purge_report.md:51-56`）；重复行风险（`snsId` 无唯一约束 `moments.md:214-217`） | `forensics:239`, `:243-245`；`wm:42`；`SnsPrefixProbe.kt:67` |

> 复位档最容易漏的三点（`forensics:241-245`）：①关开关 ≠ 恢复原状（磁盘位不回滚）；②对方改回全部可见引入新重复源（R31h 先例）；③清缓存是唯一可能把「本地行还在」前提整个抹掉的动作，但微信清缓存对 `SnsInfo` 的实际行为**无取证** ⇒ `[需真机]`。

### 8.6 ★ 三态判定表（什么结果 ⇒ R-C 可实现 / 证伪 / 证据不足）

| # | 观测结果（探针输出组合） | 判定 | 依据 |
|---|---|---|---|
| **V-1** | **T = false**（阶段一 ② 返回 0 行，或行在但 `clen=0`/`chead=''`，或 `<T0>` 未校准） | **R-C 证伪**（共性前置证伪，R-A/R-B 同收口，**0 装包**） | `forensics:167-169`, `:271`, `:273`；V5 §5.4 |
| **V-2** | T=true 且 A1 `verdict=NO_WRITE`：进入/返回/复位**全时序窗口**内 S 上 `onSCnt=0`、6 条 `& -9` 区间 ∩ S = ∅；且 P-10/P-11 对照都抓到写、P-05 六钩子齐全、P-22 投递 ok | **★ R-C 证伪（证死·强证据）** | 生死假设坐实：服务端不下发 ⇒ 本地无写 ⇒ before 空转（`forensics:189`, `:239`, `:260`, `:272`；`design:351-352`, `:362`） |
| **V-3** | `verdict=WRITE_OFF_S`（有写、before 也回调，但 `onSCnt=0`，写全落三天内行） | **R-C 证伪**（等同 V-2） | `design:355`, `:363` |
| **V-4** | `verdict=WRITE_ON_S` ∧ W3 `shape∈{BLOCKED_BY_REWRITE,BLOCKED_BY_REMOVE}` ∧ P-14 翻页 `older3dShown>0`（P1.1+P1.3+P1.4+P1.5+P1.6 全成立） | **R-C 可实现** | `wm:40-42`；`SnsBitLogic.kt:142`, `:175`；`design:353` |
| **V-5** | `verdict=WRITE_NO_HOOK`（`rawMethodCnt>0` 但 `beforeCnt=0`） | **证据不足（工程挂点问题，非路线证伪）**：挂错类/签名不匹配/钩子未注册 ⇒ 修挂点重测，⛔ 不得记成「没有写事件」 | `design:203`, `:354` |
| **V-6** | S 有写、before 命中，但 P-14 翻页仍 `older3dShown=0`；或把**首屏空**当失败 | **证据不足 / 部分**（P1.6 未定）。首屏解决不了是已知效果上限，不得当 R-C 失败 | `wm:40-41`；`moments.md:259-260`；`design:357-358` |
| **V-7** | P-20~P-26 任一自证失败（前台/投递/`TAG_present`/`snsDb_captured`/`not_stale`/`installed` 缺项）致数据不可信 | **证据不足**：先修干扰排除层，⛔ 不对过期数据下结论 | `design:356`；`dev.py:275-299`；`CLAUDE.md:73-79` |
| **V-8** | 任何「必须写库才能闭环」的验证未做（真 before 写回后是否被二次覆盖、补 content 后界面能否渲染） | **证据不足**：本轮不实现 R-C，V6 标红的只读替代只给间接证据，不粉饰 | `design:251`, `:358` |

⛔ **不得误判为证死的三种**（`design:365`）：V-5（有写没拦到）、P-14 首屏空（已知效果上限）、V-7（干扰致数据不可信）—— 一律落「证据不足」。

### S6 · 收口

把全部 `dev.py q` 结果按 §8.6 映射三态，把「①S 是否成立 + ②S 上是否有写事件」贴回 Q4 路线对照表交用户拍板（`forensics:273`；`CLAUDE.md:42-43` D2）。**本裁决 lane 到此为止，不实现 R-C、不写生产逻辑、不碰 R-A/R-B。**

---

## 九、裁决自检 + 最终结论速览

| 项 | 裁定 |
|---|---|
| **最终裁决** | **(c) 分两阶段**（V5 §5.3），非「两者都有道理」 |
| **lane D** | ❌ **错**：核心结论「0 装包足够判定 R-C 生死」被 V2（no-op 改写必然假阴性）、V1 §1.4 + V3（把 `RCP_*`/`hitS` 设计稿输出当现成能力）、V4（无充分差分组合）三条独立否决 |
| **lane E** | ✅ 方向对（硬判据须新增代码+装包），但 **①** 事实错误「无 `createTime`」（实际 `Event.ct`，`SnsBlockProbe.kt:185/:198/:347`）；**②** 结论过强（漏了 Q3 前置 0 装包排除 ⇒ T=false 分支可 0 装包收口） |
| **最少装包次数** | **0**（T=false 收口）/ **1**（T=true，所有改动攒齐一次 `adb install`，`CLAUDE.md:62`） |
| **必须新增探针数** | **1 个类 `SnsWriteProbe`**（+ 扩写 `SnsGuard.kt`/`SnsBlockProbe.kt`/`EntryReceiver.kt` 3 个既有文件） |
| **实现 lane 下限** | **≥2 个 Pro 实现 lane**（`CLAUDE.md:89`, `:111`, `:117`, `:149-152`） |
| **进入阶段二唯一触发条件 T** | 单条只读 SQL 布尔值：S 行数>0 且 `clen>0`/`chead<>''`/`alen>0` 且 `<T0>` 已校准（V5 §5.4） |

**只读自检**：全程未修改任何源码 / 测试 / 配置 / 其它报告；未触碰 adb / 真机 / Gradle / 装包 / kvset；唯一写入文件 = 本 `docs/_r49_rc_probe_verdict.md`。三份必读材料 `forensics` / `design` / `inventory` 全部读过；核源码 `SnsBlockProbe.kt` / `EntryReceiver.kt` / `SnsGuard.kt` / `SnsSqlRewrite.kt` / `Keys.kt` / `SnsHuntProbe.kt` / `CLAUDE.md`，均按 `offset/limit` 分段（单段 ≤100 行），未整读 >300 行文件。每条结论附 `文件:行号`；未坐实项标 `[推断]` / `[需真机]`；好友一律「昵称A / 昵称B / 昵称C」，wxid 仅作 SQL 参数。

[DONE] V8
[DONE] ALL

