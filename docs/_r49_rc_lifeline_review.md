# R49 · R-C「复用写时拦截」生死假设独立复核（lane G / Pro）

> 角色：独立复核 lane G（判据设计 / 根因推理 / Hook 链路）。全只读；全程未碰 adb / 真机 / Gradle / 装包 / kvset。
> 唯一写入文件：本文件。
> 复核对象：既有取证 `docs/_r49_3day_forensics.md` Q4/Q6 与裁决 `docs/_r49_rc_probe_verdict.md` 所载 R-C **生死假设**——
> 「服务端不下发三天外 SnsObject ⇒ 微信本地根本不会对这些行执行 update/execSQL ⇒ snsBlockGuard 的 before 钩子空转 ⇒ R-C 证伪」。
> 本轮新事实（主会话阶段一真机只读 `op=sql`，SnsMicroMsg.db，`snsDb=true`）：
> - `SnsInfo` 共 262,822 行，`createTime` 为 10 位 Unix 秒（min≈1337694993 / 2012，max≈1794144038 / 2026-11-08）。
> - `T0 = 1794144038 - 3*86400 = 1793884838`；三天外行 = **262,154 条（99.75%）**，且全部 `length(content)>0`、`length(attrBuf)>0`（wc=262154 wa=262154）。
> - 最新 3 条 `hex(substr(content,1,16))` 均为 `0A14…` protobuf 头，`sourceType` 见 10 / 2。
> 待核反例：项目硬结论「进好友主页每进一次执行 6 条 `UPDATE SnsInfo SET sourceType = sourceType & -9`」，若 WHERE 不带 createTime 窗口且按 userName 批量清位，则会命中三天外行 ⇒ 三天外行上存在写事件 ⇒ before 钩子不空转 ⇒ 生死假设可能被证伪（往 R-C 可行方向）。

## 取证约束与方法
- 只读 Grep/Read 定位，禁止整读 >300 行文件（一律 offset/limit）。
- 每条结论附 `文件:行号`；不确定处标 `[推断]`，需上机才能定的标 `[需真机]`。
- 每节完成即追加落盘，并留 `[DONE] <子步>`；全文末尾 `[DONE] ALL`。

---

## R1 · 六条 `& -9` 的真实 WHERE（SQL 原文表）

### 1.1 "6 条"的出处（先坐实触发面）
- `docs/conclusions/moments.md:24-25`：「`& -9` **不是只在删除流程执行**：进一次好友主页就执行 **6 条**（首屏 + 分页），按 `snsId` 区间分页、`sourceType in (64 个含 bit3 的值)`」。
- `docs/_r42_bit_paths.md:187`：「此前多次认为『`& -9` 只在好友删除流程出现』。本次**实测：进一次好友主页就执行 6 条**，其中一条正好命中目标动态」。
- `docs/_r42_bit_b_report.md:112`：「进一次主页 **+6 左右为正常**」。
- ⚠️ **条数口径差异（诚实标注）**：`docs/_r42_bit_paths.md:211` 记「22:03 再进主页 `& -9` **SQL×8**（首屏 + 分页）」，`:217` 记 `sqlClear9=8 sqlBlock9=8`；即不同轮次实测为 6 / 8。本复核按题面硬结论统一用「6 条」口径，但**不把 6 当定值**，它是"每次进主页按分页批量发一组"的约数 `[推断]`。

### 1.2 真机 SQL 原文表（逐条抄出，非转述）

| # | 来源 | 原文 | 性质 |
|---|---|---|---|
| ① | `SnsSqlRewrite.kt:10-13`（头注释，明写「真机实测，见 `docs/_r42_bit_paths.md` §8.3」） | `UPDATE SnsInfo SET sourceType = sourceType & -9` / `WHERE SnsInfo.userName="<好友>" AND (sourceType in (8,264,10,...))  -- 64 个值全含 bit3，含 10` / `AND (snsId != 0) AND (snsId > A) [AND (snsId < B)]  -- 按 id 区间分页` | **代码里的权威模板**（改写判据的设计依据） |
| ② | `docs/_r42_bit_paths.md:192-198`（§8.3「实测的完整 SQL（方案 B 的设计依据）」） | 同上，逐字：`WHERE SnsInfo.userName="<好友>"` / `AND (sourceType in (8,264,10,266,12,268,...,478)) -- 64 个值，全含 bit3，含 10` / `AND (snsId != 0)` / `AND (snsId > A) [AND (snsId < B)] -- 按 id 区间分页，不是全表` | **最权威真机原文** |
| ③ | `docs/_r42_block_result.md:103-106`（删除流程 `execSQL` 钩子抓到的按好友批量清位） | `UPDATE SnsInfo SET sourceType = sourceType & -9` / `WHERE SnsInfo.userName="wxid_xlrbelf6hv0w22"` / `AND (sourceType in (8,264,10,266,12,268,14,270,24,280,26,282,28,284,30,286,72,328,74,330,76,332,78,334,88,3…))` | 真机；⚠️ **该样本正文未显示 `snsId` 区间**（`:110-111` 记 `sqlSrc=6 sqlUnmatched=6`），且 `:112` 明说这条走 `execSQL`、`updateWithOnConflict` 完全不拦 |
| ④ | `docs/_r42_bit_b_report.md:260-263`（方案 B 真机日志原文） | `BLOCK w2 sql-clear9(已拦 -9→-1) rng=-3397803690084199920..*` / `UPDATE SnsInfo SET sourceType = sourceType & -9` / `WHERE SnsInfo.userName="wxid_xlrbelf6hv0w22"` / `AND (sourceType in (8,264,10,266,12,268,...)) AND (snsId != 0) AND (snsId > -3397803690084199920)` | 真机；**只有下界、无上界** ⇒ `rangeKey` = `<lo>..*`（`SnsSqlRewrite.kt:96-97`） |
| ⑤ | `docs/未完成项与计划.md:324`（`SnsSqlSpy` 旁听的**相册 SELECT**，非 UPDATE，用于对照白名单） | 时间线 `... where (sourceType & 2 != 0) AND stringSeq < '…' order by createTime desc`；相册 `WHERE SnsInfo.userName="wxid_…" AND (sourceType in (8,264,10,266,12,268,14,270,…,222,478)) AND type in (1,2,3,…,101,50) order by head desc` ⇒ **64 个值全含 bit3，无 createTime 窗口** | 白名单枚举与"无时间窗"的独立佐证 |
| ⑥ | `SnsBlockProbe.kt:586` + `docs/_r42_bit_b_report.md:120-123`（`sql-clear9` 日志格式） | `BLOCK w2 sql-clear9(<tag>) rng=<lo>..<hi> ${sql.take(SQL_LOG_CHARS)}`；`<tag>` ∈ 已拦 / 未拦·开关关 / 未拦·改写失败 / 未拦·未到拦截线；`:583` 明写「原文仍是**微信原本要执行的**那条」 | 0 装包可离线解析的 SQL 原文通道（R6 用） |

### 1.3 逐条回答题面 4 问

| 问 | 判定 | 锚点 |
|---|---|---|
| **WHERE 里有没有 `createTime` / 时间窗口？** | **❌ 没有**。6 条 `& -9` 的 WHERE 只由 `userName` + `sourceType in (64 值)` + `snsId != 0` + `snsId > A [and < B]` 构成，**无任何 `createTime` / 时间谓词**。 | `SnsSqlRewrite.kt:11-12`；`docs/_r42_bit_paths.md:194-197`；`docs/_r42_bit_b_report.md:262-263`；`docs/未完成项与计划.md:324`「无 createTime 窗口」；`docs/_r49_3day_forensics.md:88`、`:213`（相册 SQL 本来就没有 createTime 窗口） |
| **有没有 `userName=`？** | **✅ 有**，`WHERE SnsInfo.userName="<好友>"` ⇒ **按好友批量清位**，不是单行 id 定位。 | `SnsSqlRewrite.kt:11`；`docs/_r42_bit_paths.md:194`；`docs/_r42_block_result.md:104`；`docs/_r42_bit_b_report.md:262` |
| **有没有 `snsId > A [and < B]` 区间？** | **✅ 有**，`(snsId != 0) AND (snsId > A) [AND (snsId < B)]`，注释明写「**按 id 区间分页，不是全表**」。上界**可选**：样本④只有下界；`docs/_r42_bit_paths.md:227` 记「只有上界的分页（`snsId < B`）`rangeKey` 解析不出 key，记成 `?`」。 | `SnsSqlRewrite.kt:12`、`:50-51`（`SNSID_GT`/`SNSID_LT` 正则）、`:93-98`（`rangeKey`）；`docs/_r42_bit_paths.md:196-197`、`:227` |
| **命中集合由什么决定？** | 三个条件 **AND 交集**：① `userName` = 该好友；② `sourceType ∈ 白名单`——**64 个值全部含 bit3(8)**，形如 `8,264,10,266,12,268,14,270,…,222,478`（即 `8/10/12/14/24/26/28/30/72…222` 及其 `+256` 变体），**不含 0，也不含纯 2**（2 只有 bit1、无 bit3）；③ `snsId` 落在 `(A, B)` 或 `> A` 且 `!= 0`。`& -9` = `~8` 只清 ALBUM_BIT(8)、不动 TIMELINE_BIT(2)（`docs/_r42_block_result.md:108`）。 | `docs/未完成项与计划.md:324`；`SnsSqlRewrite.kt:11-12`、`:30`（边界：只认 `UPDATE`+`SnsInfo`+SET 清位）；`docs/_r42_block_result.md:105`、`:108` |

### 1.4 与 memory 佐证的一致性
题面 memory「`& -9` 的 WHERE 命中集合 `sourceType IN (8,264,10,266,12,268,14,270,…,222,478)` 不含 0」——**与 `docs/未完成项与计划.md:324`、`docs/_r42_bit_paths.md:195` 完全吻合**，且同样**没提 createTime**。⇒ 反例的前提（WHERE 无时间窗）在 5 个独立来源上成立。

[DONE] R1

---

## R2 · 命中集合是否覆盖三天外行

### 2.1 先把"覆盖"拆成三道闸，别只看"无 createTime"
WHERE 无 `createTime`（R1）只说明**没有按时间排除**，是「覆盖」的**必要非充分**条件。一行三天外 S 行要被 `& -9` 真正命中，必须同时过：

| 闸 | 条件 | 三天外行（262,154 条）现状 | 锚点 |
|---|---|---|---|
| G1 | `userName = "<好友>"` | ⚠️ **新事实的 262,154 条是全库计数，未按 `userName` 拆分**，属于「昵称A」的有多少、id 范围如何**未知** | 题面新事实；`docs/_r49_rc_probe_verdict.md:246` 阶段一只判 S 是否存在 |
| G2 | `sourceType ∈ 64 值白名单`（全含 bit3） | ⚠️ 新事实只说「最新 3 条 `sourceType` 有 **10 / 2**」；**`2` 不在白名单**（白名单全含 bit3(8)，2 只有 bit1）。若三天外行早已被清成 2/0，`& -9` 根本不匹配它们 | 题面新事实；`docs/未完成项与计划.md:324`；`docs/_r49_rc_probe_verdict.md:201`（V4 混淆 #2）、`:200`（#1 bit3 已为 0） |
| G3 | `snsId ∈ (A, B)` / `> A` 且 `!= 0` | ❌ **完全未知**：A/B 数值、生成规则均未取证 | `SnsSqlRewrite.kt:12`；`docs/_r49_rc_probe_verdict.md:210`（V4 混淆 #11「区间相交 ≠ 行级命中」） |

⇒ **结论先摆**：**不能判「覆盖」，也不能判「不覆盖」——证据不足**。「WHERE 无 createTime」推翻的是「按时间窗把三天外行排除」这一条理由，但 G1/G2/G3 任一不满足都会让 6 条 SQL 空转在三天外行之外。

### 2.2 `snsId` 区间到底是按什么分页的（题面点名）
把项目里三套"分页/游标"分开，别混：

| 分页 | 形态 | 出处 | 与 `& -9` 的关系 |
|---|---|---|---|
| 时间线 | `... AND stringSeq < '…' order by createTime desc` | `docs/未完成项与计划.md:324`；`docs/_r49_3day_forensics.md:87` | **字符串游标 `stringSeq`**，不是 `snsId` 数值 ⇒ `& -9` 的 `snsId > A` **不是**时间线分页 |
| 相册 SELECT | `WHERE userName=… AND sourceType in (64 值) AND type in (…) order by head desc` | `docs/未完成项与计划.md:324`；`docs/_r42_bit_paths.md:196` 注「不是全表」 | **无 `snsId` 区间谓词** ⇒ `& -9` 的区间也**不是**相册查询谓词本身 |
| 相册**请求**游标 | `ic5.f = MaxId` / `ic5.d = FirstPageMd5` / `ic5.h = LastRequestTime` | `docs/未完成项与计划.md:320` | `[推断]` MaxId 若是 snsId 类游标，A/B 可能取自「服务端本次下发的那批 id」 ⇒ 若如此则**恰好只覆盖本次下发批次、不覆盖三天外行，原假设成立** |
| `& -9` 清位 UPDATE | `(snsId != 0) AND (snsId > A) [AND (snsId < B)]`，代码注释「按 id 区间分页，不是全表」 | `SnsSqlRewrite.kt:12`、`:50-51`、`:93-98`；`docs/_r42_bit_paths.md:196-197` | **独立的第三种分页**，`A` 是下界游标、`B` 上界可选；真机样本只有下界（`-3397803690084199920..*`，`docs/_r42_bit_b_report.md:260-263`） |

⚠️ **关键不可反推性**：`snsId` 是 64 位（负）整数（真机 `-3397803690084199920`，`docs/_r42_bit_b_report.md:260`），与 `createTime` 的 **10 位 Unix 秒**（题面新事实 min=1337694993 / max=1794144038）**无换算关系** ⇒ **拿到 A/B 数值也无法从数字本身判断它是否框住三天外行**，必须回库 join `snsId ↔ createTime`。这正是 R6 想做但做不彻底的事。

### 2.3 往"覆盖"方向的强语义证据（但不能当定论）
`docs/_r42_bit_paths.md:76-88` 记一次进主页的全部写入，其中：
- `:79` 「**服务端未返回、本地有 bit3 → 清 bit3**，`10 → 2`，**25 条**」；
- `:87-88` 「AAA 的 `10` 型里最早一条是 `1651043545` = 2022-04-27，仍在相册位 ⇒ **不存在『只保留最近 N 条』的分页策略，被清的 25 条只能是『服务端这次没返回』**」。

⇒ 若「三天可见 ⇒ 三天外 = 服务端未返回」成立，那么**`& -9` 清位的触发语义天生就是冲着"服务端没下发的行"去的**，「服务端不下发」非但不是「无写」的理由，反而可能是**触发写的原因**。这直接动摇生死假设的因果方向。

⛔ **但两点不能搬用**：① `:83` 明记该轮「清 bit3 走的是 `update` 写 `2`，**不是 `& -9`**（`sqlSrc=0`，裸 SQL 一次都没执行）」——那是**删除/私密场景**，与三天可见场景不是同一触发条件；② 该证据只证明「区间会滚到旧行」，**未证明 6 条 `& -9` 在三天可见态下也这么滚** `[需真机]`。

### 2.4 往"不覆盖"方向的同等分量证据
- `docs/_r49_rc_probe_verdict.md:201`（V4 混淆 #2）原文：「S 行可能根本不在命中集合里。『SQL 执行了』≠『写落在 S 上』，而**『是否落在 S 上』正是 R-C 要判的生死假设本身**」。
- `docs/未完成项与计划.md:329`：相册渲染还有 **SQL 白名单 → `type ∈ {1,15}` → `isDieItem()`** 三道门槛，即使 `sourceType` 保住也不代表行能显示。
- `docs/_r49_3day_forensics.md:213`：「真正挡住的是响应列表里没有这些 id」——若 6 条的 A/B 由响应批次生成（2.2 第三行），则三天外 id 根本不进区间。

### 2.5 判定所缺的证据（逐条列，⛔ 不猜）
1. **6 条 `& -9` 在「三天可见 + 进昵称A主页」场景下的逐条真机原文**：每条的 `userName`、完整 `in(...)`、`snsId` 的 `gt`/`lt`（现有 `stat()` 只给**最热一条** `block9RangeMaxKey`，`SnsBlockProbe.kt:601-613`；`docs/_r49_rc_probe_verdict.md:172`）。
2. **「昵称A」三天外行的 `snsId` 分布**：`min/max/count` 及按 `snsId` 排序后的边界（新事实只给了全库 262,154 条，未按人拆分）⇒ 需 `op=sql` 补一条 `select count(*),min(snsId),max(snsId) from SnsInfo where userName='<昵称A>' and createTime < <T0>`（`op=sql` 只读白名单可跑，`SnsGuard.kt:1268-1282`）。
3. **S 行的 `sourceType` 分布**：`select sourceType,count(*) ... group by sourceType`，判有多少落在 64 值白名单（`docs/_r49_3day_forensics.md:163-164` 判据④）。
4. **A/B 的生成规则**：来自响应 `MaxId/FirstPageMd5`（`docs/未完成项与计划.md:320`）还是本地相册分页 `lastId`？需 `SnsHuntProbe` 的 24 帧调用栈（`docs/_r49_rc_probe_verdict.md:158`）或多次进主页看区间是否滚动/是否 `rng` 恒为同一值。
5. **行级命中而非区间相交**：`execSQL` 是 void、`changes()` 不可观测（`SnsSqlRewrite.kt:17-19`、`docs/_r49_rc_probe_verdict.md:202`、`:210`）⇒ 即使区间相交，也需结构化探针把「写事件 ↔ snsId ↔ createTime」关联起来才能定（V3 §3.3 `:179`）。

### 2.6 R2 结论
**证据不足，不下覆盖判定。** 可以确定的只有一句：R1 已证 WHERE **不含 createTime**，因此「按时间窗把三天外行排除」这条理由**不成立**；生死假设「服务端不下发 ⇒ 无写」的**推理链由此断裂**（见 R4）。但「6 条 `& -9` 是否真的落在 262,154 条三天外行上」，受 G1/G2/G3 与 A/B 生成规则制约，**0 装包现有证据判不了** `[需真机]`。

[DONE] R2

---

## R3 · 触发机会裁决（execSQL before 钩子 / 双开关）

### 3.1 `execSQL` before 钩子走到 `SnsSqlRewrite` 的完整条件
（逐环核过，与 `docs/_r49_rc_probe_verdict.md:98-102` V2 §2.2 的调用链一致，本 lane 独立复核无异议）

| 步 | 位置 | 行为 |
|---|---|---|
| ① 注册 | `SnsGuard.kt:152-154`；`sqlGuard` `:221-226` | `hookAllMethods(SQLiteDatabase,"execSQL")`，`beforeHookedMethod` → `onExecSql(param)`（`:223`） |
| ② 入口闸门 | `SnsGuard.kt:834-857` | `a[0] as? String`；`:842-848` 顺手捕获 `snsDb`；**`:853` `sql != null && sql.contains("SnsInfo")`** 才进 `:854` `runCatching { onSnsSql(sql, param) }` |
| ③ 决策（四路 when） | `SnsGuard.kt:872-883` | `!kind.clearsBit3` → `NONE`（`:876`）；`!SnsSqlRewrite.shouldBlock(sql)` → `NONE`（`:879`）；**`!cfg().sqlBlockEnabled` → `OFF` 原样执行**（`:881`）；`else` → **`applySqlRewrite(param, sql)`**（`:882`） |
| ④ 改写 | `SnsGuard.kt:900-914` | `SnsSqlRewrite.rewrite(sql)`（`:904`）→ `param.args[0] = out`（`:909`），KDoc `:895`：「**before 钩子里改，SQL 尚未执行**」 |
| ⑤ 记账（**无条件**） | `SnsGuard.kt:884-891` | 无论 `NONE/OFF/BLOCKED/FAILED`，`SnsBlockProbe.noteSql(...)` **都调用** → `sqlClear9` 增长 + `sql-clear9` 采样日志（`SnsBlockProbe.kt:565-587`） |

⇒ **走到 `SnsSqlRewrite` 的充分条件 = 三真**：`kind.clearsBit3`（`SnsBitLogic.sqlClear` 形态识别）∧ `SnsSqlRewrite.shouldBlock(sql)`（`UPDATE` + `SnsInfo` + SET 里 `sourceType = ... & -9`；`SnsSqlRewrite.kt:65-69`、`:109-114`）∧ `cfg().sqlBlockEnabled`。**但注意：三真只决定"改不改参数"，不决定"钩子触不触发、SQL 执不执行"**。

### 3.2 ⛔ 决定性的一点：改写**不阻止执行**
- `SnsSqlRewrite.kt:16-21` 类注释明确排除「改 WHERE 恒假」「跳过执行」，选定「改写值」：`x & -1 == x`（`:24`、`:40-42`），「只动 SET 里的一个常量，**WHERE 一个字节都不碰** ⇒ **命中集合、`changes()`、后续分页三者全同**」。
- `SnsGuard.kt:62` KDoc：「把 `& -9` 改成恒等掩码 `& -1` 后**照常执行**（命中集合与 `changes()` 都不变）」；`:867` 复述。
- `SnsGuard.kt:881` 的 `OFF` 分支同样是「**原样执行**，但要能被看见」，`blkOff` 计数（`SnsBlockProbe.kt:574`）。

⇒ **微信是否发出并执行这 6 条 `& -9`，与模块开关完全无关**（开关只改 `param.args[0]` 的字符串常量）。所以「三天外行上有没有写事件」这个问题，**答案不取决于开关态**；开关只决定模块是「拦」还是「放行」。这把 R3 与 R4 的因果理顺了：**「before 钩子空转」这个断言，其真假只由「微信是否执行命中三天外行的 `& -9`」决定**，而 R1/R2 已证这条 SQL 的 WHERE 无时间窗、每进主页必发（`moments.md:24-25`、`docs/_r42_bit_paths.md:187`）。

### 3.3 双开关与题面硬结论的源码坐实
```
SnsGuard.kt:195   val block = Kv.getBool(Keys.ANTIREVOKE_SNS) || Kv.getBool(Keys.ANTIREVOKE_SNS_BLOCK)
SnsGuard.kt:199   sqlBlockEnabled = block && !Kv.getBool(Keys.ANTIREVOKE_SNS_SQLBLOCK_OFF)
```
- `block` 是 **OR**（`:195`）⇒ ⛔ **只关一个无效**：`op=snsg sub=block --es sub2 off` 经 `SnsBlockProbe.switch()`（`:703-713`）只置 `antirevoke.sns_block=false`；只要 `antirevoke.sns` 仍 true，`block` 仍 true ⇒ `sqlBlockEnabled` 仍 true ⇒ 改写照旧（`docs/_r49_rc_probe_verdict.md:125`）。
- ⇒ **题面硬结论「临时全放行必须 `antirevoke.sns` 与 `antirevoke.sns_block` 两个都关」成立**，源码依据即 `:195` 的 OR；两个都 false ⇒ `block=false` ⇒ `sqlBlockEnabled=false` ⇒ `onSnsSql` 落 `OFF`（`:881`），A 层摘 `sourceType` 键也停（`SnsGuard.kt:262-263`、`:297-298`），微信写原样落地、库里 bit3 真被清（`docs/_r49_rc_probe_verdict.md:126`）。
- 第三条路径 `antirevoke.sns_sqlblock_off=true`（`Keys.kt:174`）只停 B 层、A 层照旧，是 **A/B 对照专用调试覆盖**，**不是**「临时全放行」（`SnsGuard.kt:95-96`、`docs/_r49_rc_probe_verdict.md:127`）。本 lane 禁 kvset，仅作判据陈述。

### 3.4 ⚠️ 独立复核指出的一处口径差异（"默认开关态"有两种读法）
| 口径 | 取值 | 后果 |
|---|---|---|
| **出厂默认** | `Keys.kt:436-438` `PrefSpec(Keys.ANTIREVOKE_SNS, PrefKind.SWITCH, "false", ...)`；`:139`「防撤回域（6 项，v6.3 定：**全部默认关闭**）」；`antirevoke.sns_block` 亦 false（`Keys.kt:157-158`） | `block=false` ⇒ `sqlBlockEnabled=false` ⇒ `SqlBlock.OFF`，`& -9` **原样执行、bit3 被清**；但 `noteSql` 仍记账、钩子仍过 |
| **裁决 V2/V5 语境的"默认"** | `docs/_r49_rc_probe_verdict.md:90`「只要 **B 层改写处于开启态**」、`:143`「在**默认（B 层改写开启）配置**下」 | `sqlBlockEnabled=true` ⇒ `SqlBlock.BLOCKED`，`-9→-1` no-op 改写，快照差分必然假阴性 |

⛔ 二者**不矛盾但口径不同**：前者是源码出厂值，后者是「用户为验证 R42 已打开主开关」后的取证态。题面硬结论「必须两个都关才全放行」隐含真机当时**两个都开着**（否则无需关）。⇒ **真机实际态需 `op=kvget` 只读回读确认** `[需真机]`；本复核不预设。

### 3.5 R3 答案：默认开关态下，三天外行上到底有没有可拦的写事件
分两层，不许混：

1. **微信侧「写事件」**：**有**（**前提 R2 命中成立**）。进好友主页必发 6 条 `& -9`（`moments.md:24-25`、`docs/_r42_bit_paths.md:187`），SQL 照常执行、`changes() = 命中行数 > 0`，与模块开关无关（3.2）。
2. **模块侧「钩子触发 / 可拦」**：**也与开关无关地触发**。`onSnsSql` 在 `sql.contains("SnsInfo")` 时必被调（`:853-855`），`noteSql` 四路都记（`:884-891`）。差别仅在 `sqlBlockEnabled=true` 时多一次 `-9→-1` 改参数（`BLOCKED`），`=false` 时落 `OFF` 不改写。⇒ **「before 空转」在两种开关态下都不成立**（只要 SQL 命中三天外行）。
3. **但「可拦 ≠ 已拦」**：若真机处于出厂默认（全关，`OFF`），模块**没有执行保位动作**，`& -9` 会把 bit3 清掉；此时写事件虽可被观测、却**没有被拦住**，不能拿来证明 R-C 有效。要让「拦住写」成立，需主开关开启（`block=true`），而按 `:195` OR，这等价于**两个开关不能同时关**——恰与题面硬结论互为反面。

⇒ **裁决**：默认（无论哪种口径）开关态下，**三天外行上存在「可被 before 钩子观测、可在启用态下被拦」的写事件**；「钩子空转」这一生死假设的支柱被撤掉。但**「有写事件」不等于「拦住写能显示三天外动态」**，且 R2 的命中覆盖仍证据不足 ⇒ 见 R4，不得越级。

[DONE] R3

---

## R4 · R-C 生死假设重判

### 4.1 三档定义（先立判据再落档）
| 档 | 含义 |
|---|---|
| **A 空转证伪** | 「服务端不下发 ⇒ 本地无写 ⇒ before 空转」成立 ⇒ R-C 直接判死 |
| **B 不空转但未证明可行** | 写事件存在/钩子不空转，但「覆盖 S」与「拦住写 ⇒ 界面显示」至少一项未证 |
| **C 可行** | 已证 6 条写落在三天外行上，且拦住后界面确实显示三天外动态 |

### 4.2 逐环拆生死假设（原假设的四段因果）
| 环 | 原文 | 复核判定 | 锚点 |
|---|---|---|---|
| ① | 服务端不下发三天外 SnsObject | ✅ **保留**（架构事实，非本轮争点） | `docs/未完成项与计划.md:345`；`docs/_r49_3day_forensics.md:212`、`:345` 转引 |
| ② | ⇒ 微信本地**根本不会**对这些行执行 `update`/`execSQL` | ❌ **被证伪**。每进好友主页必发 **6 条 `UPDATE SnsInfo SET sourceType = sourceType & -9`**（`moments.md:24-25`、`docs/_r42_bit_paths.md:187`），WHERE **无 createTime 时间窗**（R1），按 `userName` 批量 + `snsId` 区间（R1）；且 `docs/_r42_bit_paths.md:79`、`:87-88` 明记清位语义就是「**服务端未返回、本地有 bit3 → 清 bit3**」「不存在只保留最近 N 条的分页策略」——「不下发」非但不是「无写」的理由，反而**可能正是触发写的原因** | R1 全表；`docs/_r42_bit_paths.md:76-88` |
| ③ | ⇒ `snsBlockGuard` / `execSQL` 的 before 钩子**空转** | ❌ **连带证伪**。before 钩子在 `sql.contains("SnsInfo")` 时无条件触发、`noteSql` 四路都记账（R3 §3.1 `:853-855`、`:884-891`），改写不阻止执行（R3 §3.2） | `SnsGuard.kt:834-857`、`:872-892`；`SnsSqlRewrite.kt:16-21` |
| ④ | ⇒ R-C 证伪 | ❌ **结论撤下**。②③已断，④不再有支撑 |

⇒ **生死假设要「翻转」**：从「A 空转证伪」**上移到「B 不空转但未证明可行」**。原取证 `docs/_r49_3day_forensics.md:189`（Q4「最大风险：服务端不下发 ⇒ 微信本地根本不会对这些行执行 `update`/`execSQL` ⇒ before 钩子没有触发机会」）、`:239`（Q6「若三天外行上没有任何写库事件 … 钩子空转 ⇒ 整条路线证伪」）、`:259`（「R-C 空转风险」），以及裁决 `docs/_r49_rc_probe_verdict.md:466`（V-2 判死理由「服务端不下发 ⇒ 本地无写 ⇒ before 空转」）**均需按本结论订正**：它们把「不下发」直接推成「无写」，跳过了本地清位写这条通道。

### 4.3 ⛔ 绝不跳到 C：把「有写事件」与「拦住写能让界面显示」分开
题面明确要求分离。以下四条独立限制，任一未闭环都只能停在 B：

1. **覆盖 S 未证（R2）**：`snsId` 区间来源、白名单归属、S 行 `userName` 拆分、行级命中 vs 区间相交，全部 `[需真机]`（`docs/_r49_rc_probe_verdict.md:201`、`:210`；`docs/_r42_bit_paths.md:83` 那次清位走 `update` 写 `2` 而非 `& -9`）。
2. **白名单要已含 bit3**：新事实「最新 3 条 `sourceType` 有 **10 / 2**」——`2` 不在 64 值白名单（全含 bit3）；若三天外行早已是 2/0，`& -9` 不匹配（`docs/未完成项与计划.md:324`；`docs/_r49_rc_probe_verdict.md:200-201`）。
3. **首屏由网络响应重建，写时拦截解决不了首屏**：`docs/_r42_wechatmagician.md:40-41`「首屏 **解决不了**（首屏由网络响应重建）」（两个方案同）；`docs/_r49_3day_forensics.md:239` Q6 R-C「效果上限 = 保住 `sourceType` ⇒ 翻页能翻到，**首屏仍解决不了**」。⇒ 即使拦住写，效果上限只是「翻页/相册能翻到」，**不是「原生界面完整突破三天可见」**。
4. **翻页也还有三道门槛 + 位与服务端不一致**：`docs/未完成项与计划.md:329`（SQL 白名单 → `type ∈ {1,15}` → `isDieItem()`）；下拉刷新/后台同步用新响应覆盖内存列表（`docs/_r49_3day_forensics.md:238-239`）；`sourceType` 写回后与服务端状态不一致（`docs/_r42_wechatmagician.md:42`）；R-C 是**写微信库**路线且有持久残留、无回滚（`docs/_r49_3day_forensics.md:187`、`:239`；`docs/_r42_purge_report.md:51-56`）——这些是**副作用/效果上限**，不是可行性证据。

补一刀：`& -9` 拦的是**清 bit3**（保住已有位），R-C 的 A 层保的是**已有 `sourceType`**；二者都**不能凭空给从没进过相册的行补 bit3**，也不能让响应列表凭空多出三天外的 id（`docs/_r49_3day_forensics.md:213`「真正挡住的是响应列表里没有这些 id」）。「有写」与「能显示」之间还隔着整个渲染链。

### 4.4 R4 最终判定
> **判 B 档：「不空转但未证明可行」。**
> - **要翻转**：生死假设「服务端不下发 ⇒ 无写 ⇒ 空转 ⇒ R-C 证伪」**不再成立**，A 档（空转证伪）必须撤下；`docs/_r49_3day_forensics.md` Q4/Q6 与 `docs/_r49_rc_probe_verdict.md` 的「证伪」措辞需订正为「未证明」。
> - **不越级**：⛔ **绝不写成「R-C 可实现」**。写事件存在只是把 R-C 从「已死」救回「待证」，R2 的命中覆盖与 4.3 的「拦住写 ⇒ 界面显示」因果链均未闭环。
> - **准确表述**：本轮只证明「**本地清位写通道存在、WHERE 无时间窗 ⇒ 钩子有触发机会**」；R-C 的可行性判据应从「S 上有没有写」升级为「6 条 `& -9` 是否行级命中 S ∧ 拦住后翻页是否显示三天外动态」（见 R5、R7）。

[DONE] R4

---

## R5 · 对裁决报告的影响（V2 / V5 修订）

### 5.1 V2（no-op 改写导致 `op=sql` 前后快照必然假阴性）→ **保留，不修订**
- **结论**：V2 的断言与「6 条是否命中三天外行」**无关**。它只说：只要 B 层改写开启，6 条 `& -9` **真实发生且 `changes()>0`**（`docs/_r49_rc_probe_verdict.md:90`、`:112`），而 `-9→-1` 只动 SET 常量、WHERE 一字节不碰、SQL 照常执行（`SnsSqlRewrite.kt:16-21`、`SnsGuard.kt:62`、`:867`）⇒ 库里 `sourceType`/行数/命中集合全不变 ⇒ 快照必然 0 差异。这条因果**不因 WHERE 覆盖范围改变而改变**。
- **反而被本轮强化**：V2 §2.1 的前提「6 条 `& -9` 清位写事件**真实发生**」现在有项目硬结论直接背书（`moments.md:24-25`、`docs/_r42_bit_paths.md:187`），不再只是推断。
- **V2 §2.5-2 已预先容纳本轮反例**（`:133`）：「WHERE 白名单把 S 排除在外 … 若三天外行 `sourceType` 不在那 64 个值里，或 `snsId` 不在分页区间内，SQL 虽执行却不命中 S」——与 R2 的 G2/G3 完全一致，无需补写。
- **可选的一句话补充（非修订）**：在 V2 §2.1 加注「6 条写事件的存在性由 `moments.md:24-25` / `_r42_bit_paths.md:187` 坐实」。**V2 裁定「0 装包快照差分基座不成立」继续有效。**

### 5.2 V5（分两阶段、T=true 才装包）→ **框架保留，措辞与阶段二判据需修正**
| 项 | 判定 | 理由 |
|---|---|---|
| **阶段一（0 装包前置排除）** | ✅ **保留** | 触发条件 T 只判「共性前置：S 是否存在 / `content`/`attrBuf` 完整 / `createTime` 单位校准」（`docs/_r49_rc_probe_verdict.md:246`、`:262`），**不受**本轮反例影响；本轮新事实（262,154 条 S 行、全部 BLOB 非空）恰好已把 T 跑成 true。 |
| **「T=true 才装 1 次包」省轮次纪律** | ✅ **保留** | `CLAUDE.md:62` 一次攒齐；R6 已证 0 装包不足以给决定性判据，阶段二仍必要。 |
| **判 lane D 错 / lane E 方向对但结论过强** | ✅ **保留** | `docs/_r49_rc_probe_verdict.md:226-240`、`:272-274` 的三条否决（V2 假阴性 / 现成无 `hitS` 行身份 / 无充分组合）**不因本轮反例失效**——反例只改「未知量是什么」，不改「0 装包判不了行级命中」。 |
| **§5.4 `:265`「剩下**唯一未知**就是 R-C 生死假设『S 上是否有写事件』」** | ⚠️ **必须修正** | 「是否有写事件」已因 R1/R3 变成**大概率有**（`& -9` 每进主页必发、WHERE 无时间窗），不再是唯一未知。至少还有：①6 条命中集合是否**行级覆盖** S（R2）；②S 行 `sourceType` 是否在白名单（R2 G2）；③**拦住写后界面能否显示**（R4 4.3 首屏上限 / 三门槛）。措辞应改为「剩下待证的是『写是否落在 S 上』与『拦住写能否让界面显示三天外动态』」。 |
| **§5.3 阶段二表格「判定 R-C 生死」的观测定义** | ⚠️ **必须修正** | `:247` 现写「写事件按 `snsId`/`rangeKey` + `createTime` 结构化关联」，方向对，但**隐含以「有没有写」为判据**；应升级为「**行级命中 + 拦住写 → 界面显示的因果**」（见 5.3）。 |
| **§5.4 T=false 分支 `:266`「R-C 证伪」措辞** | ⚠️ **建议修正** | T=false 只证「共性前置不成立 ⇒ 无米之炊」（`docs/_r49_3day_forensics.md:169`、`:271`、`:273`），**不是「证死写事件不存在」**。建议写成「R-C 因无米之炊而不可行（非生死假设被证死）」，避免与 R4 翻转后的口径打架。 |
| **V-2 判死条目 `:466`「生死假设坐实：服务端不下发 ⇒ 本地无写 ⇒ before 空转」** | ⚠️ **必须修正** | 该「坐实」的依据已被 R4 ②③ 推翻。判死仍可保留为「证伪（强证据）」档，但**理由要换**：不再是「空转」，而是「全时序窗口内 6 条 `& -9` 区间 ∩ S = ∅ ∧ 写事件行级未落在 S 上 ∧ 对照组都抓到写」。 |

### 5.3 ⭐ 特别回答：若 `& -9` 确实命中三天外行，阶段二 `SnsWriteProbe` 的观测重点该改成什么
> **从「有没有写」改成「写落在哪些行 + 拦住写之后用户能不能看见」**，具体五条（按优先级）：

1. **从「计数」升级为「逐条 WHERE 结构化展开」**：现成 `stat()` 只给最热**一条** `block9RangeMaxKey`（`SnsBlockProbe.kt:601-613`；`docs/_r49_rc_probe_verdict.md:172`）。探针必须逐条产出 6 条的 `userName` / 完整 `in(...)` 白名单 / `snsId` 的 `gt` 与 `lt`（含「只有上界」形态，`docs/_r42_bit_paths.md:227`）。
2. **从「区间相交」升级为「行级命中」**：按 `snsId` 区间 join `SnsInfo.createTime`，产出 `hitS`（命中集合 ∩ 三天外行数）与 `onSCnt`（S 上的写事件数）。⛔ V4 §4.2-11（`docs/_r49_rc_probe_verdict.md:210`）已证「区间相交 ≠ 行级命中」，而 `execSQL` 是 void 拿不到 `changes()`（`SnsSqlRewrite.kt:17-19`、`:202`）⇒ 只能靠结构化关联，不能靠快照。
3. **新增「白名单 / 位状态归属」判据**：S 行当前 `sourceType` 是否 ∈ 64 值（决定 `& -9` 是否真命中）、bit1/bit3 现状、`type` 与 `isDieItem()` 三门槛（`docs/未完成项与计划.md:329`）。这是 R2 G2 的落地。
4. **新增「拦住写 → 界面显示」的因果观测**：三档边界（进入/返回/复位）× **首屏与翻页分离**（`docs/_r42_wechatmagician.md:40-41`、P-14 `docs/_r49_rc_probe_verdict.md:312`），记录 `older3dShown` / `firstScreenHasOlder`。⛔ 首屏空是**已知效果上限**，不得当 R-C 失败（`:470` V-6）。
5. **保留并强化对照**：P-10 阳性对照（删一条动态）、P-11 阴性对照（「昵称B」全部可见）、A1 互斥归因、干扰排除（`docs/_r49_rc_probe_verdict.md:309`、`:396`）。因为「写事件几乎必然存在」，**「探针有没有抓到」比「有没有写」更关键**——对照组是唯一能排除「探针失效假阴性」的锚。

一句话：**阶段二不再回答「R-C 会不会空转」，而是回答「6 条清位写到底落在哪几行、把它拦下来之后翻页能不能真的翻出三天外动态」**。

[DONE] R5

---

## R6 · 0 装包还能多判一步吗

### 6.1 题面拟议的做法（复述，确认可行性边界）
利用 `BLOCK w2 sql-clear9` 日志（`SnsBlockProbe.kt:586`）打出的**微信原本要执行的 SQL 原文**（`:583` 明写「原文仍是微信原本要执行的那条」），离线解析出 `userName` 与 `snsId > A [and < B]`，再用 `op=sql` 查这些 `snsId` 的 `createTime`（`op=sql` 走只读白名单 `SnsGuard.kt:1268-1282`），做只读 join 判「写事件是否命中三天外行」。裁决 V3 §3.2 第 10 条（`docs/_r49_rc_probe_verdict.md:174`）承认「**可离线解析**」。

⇒ **能多判一步吗？能，但只能判到「区间 ∩ S 是否几何相交」这一层弱线索，判不到「写事件行级落在 S 上」，更判不到 R-C 生死。**

### 6.2 漏洞逐条（题面点名 5 条全部核到源码，另补 4 条）

| # | 漏洞 | 源码 / 报告锚点 | 对 join 的具体破坏 |
|---|---|---|---|
| 1 | **去重封顶 60** | `SnsBlockProbe.kt:55` `SQL_SAMPLE_MAX = 60`；`:617-622` `sampleSql`：`into.size >= 60 return false`、`into.contains(sql) return false`；`docs/_r49_rc_probe_verdict.md:174` | 进一次主页约 6 条，10 次即满；且按**整条 SQL 去重**，同一区间反复进主页只记一次 ⇒ 后面出现的新区间（尤其「只有上界 `snsId < B`」形态）被静默丢掉，`rng` 样本不完备 |
| 2 | **单行截 1500 字符** | `SnsBlockProbe.kt:58` `SQL_LOG_CHARS = 1500`；`:586` `sql.take(SQL_LOG_CHARS)`；`docs/_r42_bit_block_report.md:97-98`「200 会在 `IN (...)` 中间截断」 | 64 值 `IN` 列表很长，若 WHERE 尾部还有 `AND (snsId < B)` 或 `type in (...)`，可能被切掉 ⇒ 解析出的区间**缺上界**，join 条件不完整 |
| 3 | **LogX 5000 行环形覆盖** | `docs/_r49_rc_probe_verdict.md:174`、`:137`（V2 §2.5-6）、`inventory:337`；`SnsBlockProbe.kt:28`（计数纯内存、进程死即清零） | `dev.py q 'BLOCK w2'` 是**事后拉日志**，进主页前后若产生大量其它 `LogX` 行，`sql-clear9` 原文可能被冲掉 ⇒ 「没抓到」不能反推「没发生」 |
| 4 | **异步时序** | `docs/_r49_rc_probe_verdict.md:136`（V2 §2.5-5）、`:204`（V4 §4.2-5）；`EntryReceiver.kt:657-661` `op=sql` 走 `io{}` 后台线程；`SnsSqlRewrite.kt:12`、`:87-97` 6 条按 `snsId` 分页执行时序未知 | 日志时间戳与 `op=sql` 快照窗口可能错位；6 条分页的 A/B 可能来自不同轮次 ⇒ 把不同批次的区间拼在一起 join 会串批 |
| 5 | **`& -1` 改写后 `changes()` 不变 / 拿不到命中行数** | `SnsSqlRewrite.kt:17-19`（`execSQL` 是 void、三种做法 Java 侧都「成功」）、`:24`（`x & -1 == x`）；`docs/_r49_rc_probe_verdict.md:202`（V4 #3）、`:210`（V4 #11）；`docs/_r42_bit_b_report.md:127`「**没有 `sqlBlock9Rows`**」 | **最致命**：改写/原样执行都不改变命中集合，`execSQL` 又拿不到 `changes()` ⇒ 即使区间 ∩ S 非空，也只能说「几何相交」，**不能说「这些 S 行真的被 UPDATE 命中」**。而 R-C 生死要的正是行级的「有/无写」（`docs/_r49_rc_probe_verdict.md:210`） |
| 6 | **`rangeKey` 本身不完整**（补充） | `SnsSqlRewrite.kt:93-98`：只 `SNSID_GT` 抠下界，抠不出下界返回 `null`；`:96` 上界缺省记 `*`；`docs/_r42_bit_paths.md:227`「只有上界的分页 `rangeKey` 解析不出，记成 `?`」 | `rng=<lo>..<hi>` 字段对「只有上界 / 无下界」形态直接退化成 `?` ⇒ 这类 6 条**完全无法离线解析** |
| 7 | **白名单 / 三门槛不在 join 里**（补充） | `docs/_r49_rc_probe_verdict.md:201`（V4 #2）、`:200`（#1）；`docs/未完成项与计划.md:329` | 即使 `snsId` 相交，S 行若 `sourceType ∉` 64 值、`type ∉ {1,15}`、`isDieItem()` 为真，`& -9` 也不命中 ⇒ join 结果**高估**命中 |
| 8 | **身份映射 / 结果集钳制**（补充） | `docs/_r49_rc_probe_verdict.md:153`（`op=sql` `clampProbeN` 钳 **50 行**）、`:162`（`op=who` wxid↔昵称） | 日志里是 `userName="wxid_…"`，S 用 `<wxidA>`，需 `op=who` 映射；S 有 262,154 条不可能全量拉回 ⇒ 只能写 `select count(*) ... where snsId > A and createTime < T0` 这类**聚合只读 SQL**（可行），但拿不到逐行清单 |
| 9 | **开关态混淆**（补充） | `docs/_r49_rc_probe_verdict.md:208`（V4 #9）；`SnsGuard.kt:881` | `sqlClear9`/`blkOff` 口径必须同时记开关快照，否则把「开关关」误读成「没写事件」 |

### 6.3 是否值得先跑 → **明确建议：值得，但只当"弱线索 / 前置排除"，不得当判定**
**建议先跑一次**，理由与边界都很清楚：

- **值得的理由**：① 成本 = 0 装包、纯只读；② 它能把 R2 §2.5 里「判定所缺证据 #1/#2」（6 条的 `A/B` 数值、三天外行的 `snsId` 范围）从「完全未知」变成「有具体数值」，让阶段二 `SnsWriteProbe` 的判据可以**预先把区间写死**、缩小观测面；③ 若日志显示 6 条区间完全落在三天内、或 `rng` 全是 `?`、或原文被 1500 字符截断，可**提前暴露「日志通道不够用」**，避免装包后才发现探针设计错；④ 顺带用 `op=sql` 补出 R2 §2.5 的两条聚合查询（按「昵称A」拆分的 `snsId` 范围、`sourceType` 分布），这两条**阶段一本来就该加**。
- **跑法（最小）**：`op=logclear` 清环 → `op=snsg sub=stat` 记开关态 + 确认 `snsDb=true` → 微信前台 → **只进「昵称A」主页一次**（避免去重封顶与串批）→ **立刻** `dev.py q 'BLOCK w2 sql-clear9'` → 离线解析 → 与 `op=sql` 聚合结果只读 join。
- **⛔ 三条硬边界**：① 结果**只写入阶段一「弱线索」栏**，不得作为 R-C 三态判定的决定性输入（`docs/_r49_rc_probe_verdict.md:218` V4 结论）；② **绝不**因「区间 ∩ S 非空」宣布 R-C 可行，也不因「没抓到日志」宣布证伪；③ 若日志残缺/被冲掉，**不重试纠缠**，直接进阶段二（R7 Step 2）。
- **一句话**：这一步的价值是**给装包那一次"校准判据"**，不是**替代装包** ⇒ 与 V5「T=true 才装 1 次包」不冲突，反而让那 1 次更准。

[DONE] R6

---

## R7 · 修正后的最小验证路径

> 目标：最短落到「**R-C 可实现 / 证伪 / 证据不足**」的明确判定（三态收敛，不悬着）。允许 **1 次装包**。⛔ 全程提及好友一律用**昵称**（「昵称A」= 目标好友，「昵称B」= 对照好友），**不裸报 wxid**；R1 表格里的 `wxid_…` 串为**真机 SQL/日志原文照抄**（R1 要求逐字抄录），非好友指代。wxid↔昵称映射用 `op=who` 只读完成（`docs/_r49_rc_probe_verdict.md:162`）。

### 7.1 路径（4 步，只有 Step 2 装包 1 次）

| 步 | 装包 | 动作 | 观测 | 判据 / 去向 |
|---|---|---|---|---|
| **Step 0**<br>阶段一补强 | **0** | `op=snsg sub=stat`（确认 `snsDb=true`、记开关态）、`op=kvget` 回读 `antirevoke.sns`/`antirevoke.sns_block`（R3 §3.4 口径差异）；跑触发条件 T（`docs/_r49_rc_probe_verdict.md:253-262`）；**新增两条只读 `op=sql`**：①`select count(*),min(snsId),max(snsId),min(createTime),max(createTime) from SnsInfo where userName='<昵称A映射的wxid>' and createTime < <T0>`；②`select sourceType,count(*) from SnsInfo where userName=… and createTime < <T0> group by sourceType` | S_A（「昵称A」的三天外行）行数 / `snsId` 范围 / `sourceType` 分布 | **T=false**（0 行 / `clen=0` / 单位未校准）⇒ **证伪（无米之炊）**，0 装包收口；**T=true** ⇒ 进 Step 1。本轮新事实已把 T 跑成 true（262,154 条、BLOB 全非空），但**未按人拆分**，Step 0 两条必须补 |
| **Step 1**<br>0 装包弱线索（= R6 §6.3） | **0** | `op=logclear` → 微信前台 → **只进「昵称A」主页一次** → 立刻 `dev.py q 'BLOCK w2 sql-clear9'` → 离线解析 6 条 `userName` / `snsId gt/lt` / `in(...)` → 与 Step 0 的 S_A 做只读 join | 6 条区间与 S_A 的 `snsId` 范围是否相交；`rng` 是否残缺 / `?` / 被 1500 字符截断 | **只记入"弱线索"**：相交 ≠ 命中（`docs/_r49_rc_probe_verdict.md:210`）；日志残缺**不重试、不纠缠**，直接 Step 2。⛔ 此步**不得**给三态判定 |
| **Step 2**<br>唯一 1 次装包 | **1 次**（`adb install`，所有改动攒齐一次，`CLAUDE.md:62`；`docs/_r49_rc_probe_verdict.md:385`） | 新增 `SnsWriteProbe` + 扩写 3 文件（`docs/_r49_rc_probe_verdict.md:279`），实现 R5 §5.3 五条：①6 条 WHERE 逐条结构化展开；②按 `snsId` join `createTime` 产 `hitS`/`onSCnt`；③白名单 / 位状态 / `type` / `isDieItem` 归属；④三档边界 × 首屏翻页分离；⑤P-10/P-11 对照 + A1 互斥归因 | `hitS`、`onSCnt`、逐条 `gt/lt`、`sourceType` 前后值、`older3dShown`、`firstScreenHasOlder`、对照组是否抓到写 | 进 Step 3 判据表 |
| **Step 3**<br>判定 | 0 | 按 7.2 判据表收敛 | — | 输出「可实现 / 证伪 / 证据不足」**之一** |

### 7.2 判据表（三态收敛，⛔ 不许把「首屏空」当失败）
| 结果 | 条件 | 锚点 |
|---|---|---|
| **证伪**（无米之炊） | `T=false`（0 行 / `clen=0` / `chead=''` / `<T0>` 未校准）⇒ 0 装包收口 | `docs/_r49_3day_forensics.md:169`、`:271`、`:273`；`docs/_r49_rc_probe_verdict.md:266` |
| **证伪**（强证据） | T=true ∧ 进入/返回/复位**全时序窗口**内 `hitS=0` ∧ 6 条区间 ∩ S_A = ∅ ∧ P-10/P-11 对照都抓到写 ∧ 六钩子齐全 ⇒ 写事件**行级未落在**三天外行上 | `docs/_r49_rc_probe_verdict.md:466`（V-2，**理由须按 R5 §5.2 换掉「空转」措辞**） |
| **可实现** | T=true ∧ `hitS>0` ∧ 拦住写后**翻页** `older3dShown>0`（首屏允许为空）∧ 三档边界可复现 ∧ 对照组正常 | `docs/_r42_wechatmagician.md:40-41`；`docs/_r49_rc_probe_verdict.md:312`（P-14） |
| **证据不足** | `hitS>0` 但翻页仍 `older3dShown=0`；或仅有首屏空；或对照失败 / 开关态混淆 / 日志丢致数据不可信 | `docs/_r49_rc_probe_verdict.md:470`（V-6）、`:474`（V-7 三种不得误判为证死） |

⚠️ **「证据不足」必须保留为第三态**：`docs/_r49_rc_probe_verdict.md:474` 已列「不得误判为证死的三种」——V-5 有写没拦到、首屏空、干扰致数据不可信，一律落此档。

### 7.3 ⭐ 三档边界（进入 / 返回 / 复位）里最容易漏的一档：**③复位**
`docs/_r49_3day_forensics.md:241-245` 明写「三档最容易漏的点（**复位档**）」，本复核确认，理由四条：

1. **复位 ≠ 返回**：返回时模块仍启用、钩子仍在、库位仍在，只是内存列表被新响应覆盖（`:239` R-C ②）；复位时**模块能力关闭或外部条件归零**（关开关 / 冷启 / 清缓存 / 「昵称A」改回全部可见）（`:232`）。⛔ 复位档**不许写成「同返回」**。
2. **已写进库的位是磁盘状态，不随开关/冷启回滚**：R-C 写回的 `sourceType` 保底值与 `[已删除]\n` content 前缀是**持久微信库残留**（`docs/_r42_wechatmagician.md:42`；`docs/_r49_3day_forensics.md:239`），且 R42 已把 `undoInsert`/`rollbackStep`/`restore`/`show` **整段删除、当前源码无任何回滚路径**（`docs/_r42_purge_report.md:51-56`、`:243`）。
3. **冷启 ≠ 下拉刷新**：刷新用新响应覆盖内存列表，冷启是进程重启 ⇒ `snsDb` 句柄与钩子都要重建（`_r20b_sns_live_review.md:91-97`、`SnsGuard.kt:1280`），但**库里的位/前缀活过冷启**（`docs/conclusions/moments.md:263`、`:242`）⇒ 反而可能比刷新后更容易翻到。
4. **「昵称A」改回全部可见**引入新重复源：服务端恢复下发后，被保位的旧行与真实响应并存，造成重复/顺序异常 `[推断]`（`:244`、R31h 事故先例 `docs/_review_r31h_sns.md:115-119`）；**清缓存**是唯一可能把「本地行还在」前提整个抹掉的动作，但微信清缓存对 `SnsInfo` 的实际行为**无取证** ⇒ `[需真机]`（`:245`）。

⇒ **判据表必须在复位档单独观测一次** `hitS` / 界面 / 库位，且单独记录「关开关」「冷启」「清缓存」「改回全部可见」四个子动作，不得并入返回档。

### 7.4 需要用户 / 操作员配合（⛔ 一律昵称）
1. 请好友**「昵称A」**把朋友圈设为「允许查看的范围 = **最近三天**」，且**设之前相册里已有三天以外的历史动态**——否则 S_A 为空、T=false，阶段一即 0 装包收口（`docs/_r49_rc_probe_verdict.md:393`）。请提供「昵称A」的**昵称**用于 `op=who` 映射（报告不裸写 wxid）。
2. 请好友**「昵称B」**保持朋友圈**全部可见**，作阴性对照基线（P-11，`docs/_r49_rc_probe_verdict.md:309`）。
3. 阶段二请**「昵称A」**再配合三次动作：**删一条动态**（P-10 阳性对照）、**改回全部可见**、**清缓存**（P-19 复位档）（`docs/_r49_rc_probe_verdict.md:396`）。
4. 操作员配合：`op=logclear` + **单次**进「昵称A」主页 + 立刻拉 `BLOCK w2` 日志（Step 1）；装包那一次 `adb install` 由主 agent 执行（**本 lane 禁 adb / 装包 / kvset**），并按 `CLAUDE.md:26`「一次重启抓完所有问题」把 Step 2 全部观测合并进同一包（`docs/_r49_rc_probe_verdict.md:385`）。
5. 真机开关态需 `op=kvget` 只读回读确认（R3 §3.4 的出厂默认 vs 取证态口径差异）`[需真机]`。

[DONE] R7

---

## 结论速览（4~6 行概述底稿 · 与对话回复一致）
1. **6 条 `& -9` 的 WHERE 无 `createTime` / 无时间窗**：只有 `userName="<好友>"` + `sourceType in (64 个全含 bit3 的值，不含 0/2)` + `(snsId != 0) AND (snsId > A) [AND (snsId < B)]`（`SnsSqlRewrite.kt:10-13`、`docs/_r42_bit_paths.md:192-198`）。
2. **生死假设要翻转**：「服务端不下发 ⇒ 本地无写 ⇒ before 空转 ⇒ R-C 证伪」的因果链断裂——清位写每进主页必发、WHERE 无时间窗，且清位语义本就是「服务端未返回、本地有 bit3 → 清 bit3」（`docs/_r42_bit_paths.md:79`），钩子不空转。
3. **R-C 当前判 B 档「不空转但未证明可行」**：⛔ 不是「可实现」——`snsId` 区间/白名单是否行级覆盖 262,154 条三天外行证据不足，且「拦住写」≠「界面显示」（首屏由网络响应重建、翻页还有三门槛）。
4. **仍需 1 次装包**：0 装包的日志区间 × `op=sql` join 只给「区间相交」弱线索（V4-11），判不了行级命中；建议先跑一次只读 join 校准判据，再把那 1 次装包的观测重点改成「逐条 WHERE 展开 → `hitS` 行级命中 → 拦住后翻页 `older3dShown`」。
5. 三档边界最易漏 **③复位**（库位是磁盘状态、无回滚、冷启 ≠ 下拉刷新、改回全部可见引入重复源）；全程好友以**昵称A / 昵称B** 指代，不裸报 wxid。

---

## 子步进度
- [DONE] 骨架
- [DONE] R1（6 条 SQL 原文表 + 4 问逐答）
- [DONE] R2（命中判定 + 所缺证据清单）
- [DONE] R3（触发机会裁决 + 双开关 + 口径差异）
- [DONE] R4（生死重判 = B 档）
- [DONE] R5（V2 保留 / V5 措辞修正 + 阶段二观测重点）
- [DONE] R6（0 装包 9 条漏洞 + 值得先跑的明确建议）
- [DONE] R7（最小路径 + 判据表 + 复位档 + 用户配合）

[DONE] ALL
