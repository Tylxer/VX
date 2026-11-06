# R42 方案 B：拦住 `execSQL` 的 `sourceType & -9`

> 2026-11-06。前置结论（方案 A 真机验证，21:18）见 `docs/_r42_bit_paths.md` §8，本报告**不重新取证**。
> 代码 + 单测 + 变异完成后**已装包真机验收通过**（§7，21:52~22:03）：`&-9` 8 条全拦、
> `blkFail=0`、目标动态 `src` 稳在 `10`、**时间线与好友主页都可见**。

## 0. 一句话

把微信那条清 bit3 的裸 SQL `UPDATE SnsInfo SET sourceType = sourceType & -9 ...` 的**值表达式**
改成恒等掩码 `& -1`（`x & -1 == x`）后**照常执行** —— 命中集合、`changes()`、后续分页与微信原本的行为完全一致，
只是位型不再被削掉。识别/改写是纯函数 `SnsSqlRewrite`，`& -3` 只识别不拦，其余 `execSQL` 一律原样透传。

---

## 1. 三种实现的选型（4.1）

| 维度 | ① 改写值表达式 ⭐ 选中 | ② WHERE 追加 `1=0` | ③ 跳过执行 `result=null` |
|---|---|---|---|
| 微信是否认为成功 | ✅ `execSQL` 是 `void`，只要不抛异常就是成功 | ✅ 同 | ✅ 同 |
| `affected rows` / `changes()` | **= 命中行数，与原语句逐字一致** | `0` | 保留**上一条语句**的陈旧值 |
| 命中集合 | 不变（WHERE 一个字节没动） | 变空 | 不变，但根本没执行 |
| 对后续分页/重试 | **零影响**：区间游标、退出条件、`changes()` 全同 | 若微信用 `changes()` 推进游标 ⇒ 可能空转/重试风暴 | 同 ②，且陈旧值比 0 更难排查 |
| 实现复杂度 | 低（一条正则 + SET 区切分） | 中（尾随 `;`、`ORDER BY`、括号都要处理，拼错就是语法错误） | 最低 |
| 误伤风险 | 低（只换一个常量，括号/引号/长度不变） | 中（要改 WHERE 结构；拼错 ⇒ 微信抛 `SQLiteException`） | 中（同一事务里"写后读"会读到旧值） |
| 额外 IO | 与原语句**完全相同**（本来就要写这些行） | 省掉写 | 省掉写 |

**结论：选 ①，并且用「常量 `-9` → `-1`」而不是「整段表达式换成 `sourceType`」。**

理由按重要性排序：

1. **只有 ① 让 `execSQL` 的外部可观测面完全不变**。三条语句在 Java 侧都"成功"（`void` + 不抛异常），
   所以判据只能落在 `changes()` 与命中集合上。真机这条 SQL 是按 `snsId` **区间分页**的
   （`snsId > A`，有时还有 `snsId < B`）；一旦微信哪天用 `changes()` 或"受影响行数"来推进游标，
   ②③ 会把游标卡在同一个区间上无限重试，而 ① 与原始语句的行数**逐行相同**，从根上没有这个风险。
2. **③ 的 `changes()` 是"上一条语句的陈旧值"**，比 0 更坏：它不是"没有信息"，而是**错误信息**。
3. **② 要动 WHERE，是三种里唯一会改变"命中什么"的做法**。真机 SQL 的 WHERE 有 64 值 `IN` 列表 +
   多个括号条件，在末尾追加 `AND 1=0` 需要先判断语句是否已经以 `;` / `LIMIT` 结尾 —— 每一步都可能把
   一条能跑的 SQL 变成语法错误（微信直接抛异常，用户侧是朋友圈闪退）。收益（省 IO）远小于风险。
4. **不改整段表达式**：`(sourceType & (-9))` 这类带括号的写法，换成 `sourceType` 会留下多余右括号
   ⇒ 语法错误。只替换常量则 `(sourceType & (-1))` 天然合法。单测 `nested_parentheses_keep_their_shape`
   与 `rewritten_sql_is_still_a_legal_statement`（括号/引号配平、单语句、幂等）钉死这一点。

`-1` 的正确性：SQLite 整数是 64 位补码，`x & -1 == x` 对任意整数成立（单测 `noop_mask_is_identity`
用 `0/2/8/10/130/264/-1/Int.MIN/Int.MAX` 验，同时反向验证 `10 & -9 == 2`，证明这不是"本来就相等"的空测试）。

---

## 2. 实现

### 2.1 文件与函数（4.5 的纯函数要求）

| 文件 | 内容 | 依赖 |
|---|---|---|
| `feature/SnsSqlRewrite.kt` **新增** | `shouldBlock` / `rewrite` / `rangeKey` / `NOOP_MASK` | 只依赖 `SnsBitLogic` 之外的标准库（`Regex`），**不 import Android/Xposed** |
| `feature/SnsGuard.kt` | `onSnsSql()`（决策）+ `applySqlRewrite()`（写回 `param.args[0]`） | Xposed |
| `feature/SnsBlockProbe.kt` | `SqlBlock` 枚举 + `sqlBlock9/blkOff/blkFail/rng` 记账 | Android（`LogX`） |
| `data/Keys.kt` | `ANTIREVOKE_SNS_SQLBLOCK_OFF` + PrefSpec | — |
| `test/.../SnsSqlRewriteTest.kt` **新增** | 20 条用例 | JUnit4 |

两个纯函数的分工是**故意不同**的，报告里单独说明，因为它决定了"识别"与"动手"不会互相污染：

- `SnsBitLogic.sqlClear(sql)` = **记账层**：全文扫描，WHERE 里的同名表达式也算、`SELECT/DELETE` 也算。
  宁可多记一条，也不许漏掉没见过的模板（`sqlClear9` 就是靠它涨的）。
- `SnsSqlRewrite.shouldBlock(sql)` = **动作层**：必须是 `UPDATE` + 表名 `SnsInfo` + 清位出现在 **SET 子句**。
  因为这里要改 SQL，识别错的代价不对等。
- 所以「识别到 `sqlClear9` 但 `shouldBlock=false`」（如 WHERE 里的 `sourceType = sourceType & -9`）是**正常状态**，
  单测 `recognition_and_action_are_two_deliberately_different_layers` 把这条差异钉死。

### 2.2 范围与开关（4.2）

- **范围**：`SnsGuard.onExecSql` 里原有的 `sql.contains("SnsInfo")` 之后就分流，只有
  `SqlClear.clearsBit3 == true` **且** `SnsSqlRewrite.shouldBlock == true` 才会走到 `rewrite`。
  其他任何 `execSQL`：不解析、不改参数、不产生额外字符串（零改动、零额外开销）。
- **用户开关**：复用 `Keys.ANTIREVOKE_SNS`（`cfg().blockEnabled`）。关掉 ⇒ `sqlBlockEnabled=false`
  ⇒ 只走 `noteSql(OFF)`，`rewrite` 不被调用、`param.args` 不被写 ⇒ **行为逐字恢复**（见 §4）。
- **调试覆盖键**：`Keys.ANTIREVOKE_SNS_SQLBLOCK_OFF = "antirevoke.sns_sqlblock_off"`（PrefSpec SWITCH，默认 false，
  设置页「防撤回」域，标题「实验：不拦 SQL 清相册位（A/B 对照，测完关掉）」）。
  - `sqlBlockEnabled = blockEnabled && !Kv.getBool(本键)`。
  - 为什么不是照抄 `ANTIREVOKE_SNS_BLOCK` 的 `A || B` 形态：方案 B 的验收是**单变量对照**——主开关必须保持开着，
    只关 SQL 这一层，否则方案 A 的写时拦截会一起关掉，两次实验的自变量就不止一个。
  - 它只能**关**不能**开**：写时拦截关闭时位型根本到不了 `10`，单独开 SQL 改写没有意义。
  - 热切换：`op=probe kvset` 结尾已调 `SnsGuard.refresh()`（`EntryReceiver.kt:183`），改完立即生效，不用重启微信。
- **状态可见**：`op=snsg sub=stat` 输出增加 `sqlBlock=<true|false>`。

### 2.3 `& -3` 的处置（4.3）：**只记录 + 告警，不拦**

理由（不是"保守"，是三条具体判断）：

1. **零样本**：`sqlClear`（bit1）从上线至今恒为 0 —— 真机从未执行过 `& -3`。
   我们手上没有一条真实语句，无法回答"它是否同时改别的列 / 是否属于私密化流程"。
2. **改写的代价不对称**：`& -9` 有 6 条/次主页的真机样本，能证明只改常量不改变命中集合；
   `& -3` 连形态都只有正则层面的推测，改它等于在无观测前提下改微信写库语义。
3. **有更便宜的兜底**：真出现时 `sqlClear > 0` 立刻可见（现在一直是 0，任何非 0 都是新事实），
   且原文采样会留下完整 SQL ⇒ 下一轮拿样本再决定，不需要现在赌。
4. **不两头都做的落地方式**：`shouldBlock` 只认 `-9`；`BOTH` 情况下只改 `-9` 那一处、`-3` 原样保留
   （单测 `both_kinds_rewrite_only_bit3` / `both_kinds_with_bit3_only_in_where_is_not_rewritten`）。
   ⚠️ 代价必须写明：**若 `& -3` 出现，时间线位仍会丢**（`sqlClear>0` 会说"它来了"，但拦不住）。

### 2.4 观测与记账（4.4）

`op=snsg sub=blockstat` 的 W2 行改为：

```
BLOCK w2 delSns=.. delRow=.. sqlSns=.. sqlSrc=.. sqlClear=.. sqlClear9=<识别数>
        sqlBlock9=<已拦> blkOff=<开关关> blkFail=<改写失败>
        rng=<区间种类数> max=<同一区间最大被拦次数>x@<区间key> sqlOwn=.. sqlUnmatched=..
```

| 字段 | 含义 | 判读 |
|---|---|---|
| `sqlClear9` | 识别到 `& -9` 形态的次数（含未拦） | 与 `sqlBlock9+blkOff+blkFail` 的差 = 「识别到但不动手」（WHERE-only 等） |
| `sqlBlock9` | **真的改写并放行执行**的条数 | 进一次主页 +6 左右为正常 |
| `blkOff` | 命中但开关关（A/B 的 B 组） | 关开关后应等于 `sqlClear9` |
| `blkFail` | 命中、开关开，但改写无变化 ⇒ **原样执行 = 没拦住** | **必须恒为 0**；非 0 是故障 |
| `rng` / `max` | 按 `snsId` 区间去重后的「被拦次数」 | `max > 6` 或持续上涨 ⇒ 微信在对同一批行**重试** |

日志行同步改口径（旧行是 `sql-clear9(-9 未拦)`，已作废）：

```
BLOCK w2 sql-clear9(已拦 -9→-1) rng=-3397825059229724135..*  UPDATE SnsInfo SET sourceType = sourceType & -9 ...
BLOCK w2 sql-clear9(未拦·开关关) rng=...   ...
BLOCK w2 sql-clear9(未拦·改写失败) rng=...  ...
BLOCK w2 sql-clear9(未拦·未到拦截线) rng=... ...
```

- 采样仍去重 + 上限 60 条，且存的**永远是微信原本要执行的原文**（不是改写后的）—— 判据要靠原文。
- ⛔ **没有 `sqlBlock9Rows`**：`execSQL` 返回 `void`，拿行数只能额外发一条 `select count(*)`，
  那是给热路径加一次同步查询换一个**估算**，不值得。改用 `rng/max` 做重试判据（更贴近要回答的问题）。

---

## 3. 副作用风险清单（每条都给真机判据）

⚠️ 方案 A 已证伪「`& -9` 只在删除流程出现」：**进一次好友主页就执行 6 条**（`_r42_bit_paths.md` §8.2）。
下面按"每次进主页都会发生"来算。

| # | 风险 | 为什么会发生 | **真机怎么验证它发生了** |
|---|---|---|---|
| R1 | **相册列表停止收缩**（必然） | bit3 不再被清 ⇒ 「服务端本次没返回」这条信号被丢弃 | 进同一好友主页前后各查一次：`select sourceType,count(*) from SnsInfo where userName='<wxid>' group by 1`。方案 A 时 `10` 会 325→300；开关 B 后 `10` **不再下降**、`2` 不再增加 |
| R2 | **越权/过期可见范围扩大**（R1 的另一面，产品风险） | 对方已删/已设为私密的动态仍留在相册 | 用一条"对方明确已删除"的动态做对照：开 B 后主页仍能看到 ⇒ 成立。或看主页条目数在开/关两组里是否相同（相同 = 没有收缩） |
| R3 | **同一批行被反复重试** | 若微信以 `changes()`/「还有没有 bit3 行」当退出条件，值不变会让它反复扫同一区间 | `blockstat` 的 `max=<n>x@<key>`：单次进主页 `sqlBlock9` 增量 ≈6 且 `max ≤ 6` = 正常；增量 20+ 或同一 key 的 `max` 持续上涨 = 重试成立。配合 logcat 时间戳看进主页耗时是否变长 |
| R4 | **`changes()` 语义漂移** | 理论上不存在（值改写 ⇒ 行数同原语句），但要证实 | 对比开关两组：`sqlBlock9` 增量应与"进主页次数 × 6"线性；若与页面内容/滚动深度相关 ⇒ 说明分母变了，另查 |
| R5 | **写放大 / 卡顿** | 改写后 UPDATE 仍然写这些行（值与原来相同） | 与关开关组对比：`SnsMicroMsg.db-wal` 大小、进主页时 `dumpsys gfxinfo` 掉帧数。预期**零差异**（执行路径没变），有差异说明命中的行集变了 |
| R6 | **改到非目标 SQL**（误伤） | 正则过宽 | `blkFail` 必须为 0；`sqlOwn`/`sqlUnmatched` 不应因本轮而变化；`sqlBlock9` 只在真正 `& -9` 时增长。若 `sqlUnmatched` 出现新原文 ⇒ 有第 4 种写 sourceType 的形态 |
| R7 | **SQL 语法被改坏** | 括号/引号/长度被破坏 | `blkFail=0` + logcat 无 `SQLiteException`/`wcdb` 异常 + 朋友圈主页不闪退。单测已断言括号/引号配平、单语句、幂等 |
| R8 | **`& -3` 出现但被放过**（已知缺口，4.3 的代价） | 本轮决定不拦 | `blockstat` 的 `sqlClear>0`（当前恒 0）即"它来了"；若同时 `sqlClear9>0` 则是 `BOTH`，届时时间线仍会丢 |
| R9 | **绑定参数形态漏拦** | 若某版本写成 `sourceType = sourceType & ?` + bindArgs，两套正则（都要求字面量 `-9`）都不匹配 | 判据：位型在 `& -9` 执行后**仍然掉了**，但 `sqlClear9` 不涨 ⇒ 存在没识别到的形态，去翻 `sql-unmatched` 原文 |

---

## 4. 回滚

**运行时回滚（推荐，秒级）**：任选其一

```bash
# A. 用户开关：整体关掉「防止朋友圈动态撤回」⇒ 方案 A + B 一起停
python tools/dev.py probe kvset --key antirevoke.sns --val false
# B. 只停方案 B（方案 A 继续跑，用于 A/B 对照）
python tools/dev.py probe kvset --key antirevoke.sns_sqlblock_off --val true
# 也可以在设置页「防撤回」里拨对应开关
```

回滚后的行为**是否逐字恢复**：是。`onSnsSql` 在 `sqlBlockEnabled=false` 时只调用
`SnsBlockProbe.noteSql(..., block = OFF)`，不调用 `SnsSqlRewrite`、不写 `param.args[0]`
⇒ 传给微信的 SQL 字符串对象**就是原来那个**，指针与内容都没动。唯一差异是账本里多了一行 `blkOff` 计数
（这正是 A/B 对照要看的数字）。`op=snsg sub=stat` 会显示 `sqlBlock=false` 以便确认。

**代码回滚**：删除 `SnsSqlRewrite.kt` + `SnsSqlRewriteTest.kt`；`SnsGuard.onSnsSql` 还原为「只调
`noteSql(sql, looksLikeSourceWrite, clearsBit1, clearsBit3, isOwnFix)`」；`Config` 去掉 `sqlBlockEnabled`；
`SnsBlockProbe` 去掉 `SqlBlock` 与 4 个字段；`Keys` 去掉 `ANTIREVOKE_SNS_SQLBLOCK_OFF` 与 PrefSpec 那一行。
⛔ 不需要动数据库 schema（本轮没有建表/加列，全部是内存计数）。

---

## 5. 单测与变异（4.5）

### 5.1 结果

| 项 | 命令 | 结果 |
|---|---|---|
| 编译 | `gradlew assembleDebug` | ✅ BUILD SUCCESSFUL（`app/build/outputs/apk/debug/app-debug.apk`，≈2.87 MB） |
| 全量单测 | `gradlew cleanTestDebugUnitTest testDebugUnitTest --rerun-tasks` | ✅ **737 tests, 0 failures, 0 errors**（39 个测试类） |
| 新增用例 | `--tests SnsSqlRewriteTest` | ✅ 20/20 |

⚠️ 环境坑（复现过）：必须用 **PowerShell** + 收窄 PATH（`<jdk>/bin;C:\Windows\System32;C:\Windows`）；
Git Bash 的 PATH 会被 AGP 拼进 `-Djava.library.path`，导致 worker JVM 起不来、**一条用例都不跑**却显示成功。

### 5.2 用例覆盖（对应 4.5 清单）

标准 `& -9` 形态（真机原文，含 64 值 `IN` 列表 + `snsId` 区间）／`& -3` 形态／`BOTH`（两种排布）／
大小写混写 + 多空格 + 换行／单双引号 userName／`snsId` 区间（有无上界）／非 `SnsInfo` 表／非 `sourceType` 列／
`sourceType | 10` 加位写／`SELECT` 里同名表达式／`-90`/`-93`/`- 9` 近邻／null+blank／
改写后语义合法（括号+引号配平、单语句、幂等、`x & -1 == x`）。

### 5.3 变异验证（6 组，6/6 变红）

脚本 `wxgroup/_mut_b.py`（改源码 → 只跑 `SnsSqlRewriteTest` → 还原 → 比对 sha256）。

| 变异 | 变红的用例 |
|---|---|
| M1 `rewrite` 不切 WHERE（整条 SQL 都替换） | `where_only_bit3_expression_is_not_a_source_write` |
| M2 `shouldBlock` 不切 SET（WHERE 里的同名表达式也当可拦） | `both_kinds_with_bit3_only_in_where_is_not_rewritten`、`recognition_and_action_are_two_deliberately_different_layers`、`where_only_bit3_expression_is_not_a_source_write` |
| M3 掩码写成 `-3`（把时间线位也削掉） | 8 条（`noop_mask_is_identity`、`real_sql_is_blocked_and_rewritten_to_noop` …） |
| M4 去掉「必须是 UPDATE」前提 | `other_tables_and_other_columns_are_untouched` |
| M5 去掉 `-9\b` 词边界 | `near_miss_constants_do_not_false_positive` |
| M6 不识别引号 | `quoted_where_before_the_assignment_does_not_disable_rewrite` |

M6 第一轮是**绿的**（0 条变红），暴露了用例缺口：原来的引号用例只验证"改写范围正确"，
而引号误判在那种排布下输出恰好不变。补了反方向用例（引号里的 `where` 落在赋值**之前** ⇒
误判会导致"SET 里没有清位"⇒ 静默漏拦 fail-open）之后才变红。这条记在这里，因为它说明
「变异绿 = 用例真漏」，不是脚本问题。还原后 sha256 一致，重跑全量 737 条全绿。

---

## 6. 本轮未验证（⛔ 不许当成已成立）

1. ~~**任何真机行为**：本轮未装包、未 adb。~~ ⇒ **已在 §7 完成真机验收**：改写后 `10` 确实
   停在 `10`、时间线与主页都可见、微信对同一区间重试 2~4 次全被拦。
2. **`& -9` 命中多少行**：`execSQL` 无返回值，本轮没有加计数查询 ⇒ `sqlBlock9Rows` 不存在（§2.4）。
3. **`changes()` 是否被微信用到**：选型时按"最坏情况"（会用）取保守方案，但没有证据表明它用。
4. **`& -3` 的真实形态**：从未出现，连一条样本都没有。
5. **其他同义形态**：`~8`、`& -8`、`& (-9)` 之外的花样、绑定参数 `?` 形态 —— 只在正则覆盖范围内讨论，真机未证。
6. **并发**：多个线程同时进主页时，`param.args[0]` 的改写是 Xposed 标准用法，但没有并发压测证据。
7. **滚动分页**：一次进主页 6 条是「首屏」，向下滚动时区间是否继续推进（`rng` 种类数会涨）未观测。
8. **`& -3` 若出现时的副作用**：本轮明确不拦，因此也没有评估"拦了会怎样"。

## 7. 真机验收结果（2026-11-06 21:52 ~ 22:03，PASS）

**环境**：微信 8.0.48 / 设备 `467cf5bc` / 模块 0.2.8，`lastUpdateTime=21:52:31`，
双次冷启后 pid=381。三开关：`antirevoke.sns=true`、`antirevoke.sns_block=false`、
`antirevoke.sns_sqlblock_off=false` ⇒ `blockEnabled=true` 且 `sqlBlockEnabled=true`。

**被测动态**：`snsId=-3397803690084199920`、`type=1`、`createTime=1793973491`（21:58:11）、
发送者 `wxid_xlrbelf6hv0w22`。

### 7.1 四段时序（每段都有独立判据）

| 时刻 | 用户动作 | 微信想做什么 | 结果 |
|---|---|---|---|
| 21:58:11 | verity 发动态 | insert 落库 `sourceType=2` | `ins seen=22 nz=22`，`upd seen=9 drop allow=9`（加位全放行） |
| 22:00:26 | 我进她主页 | ContentValues 写 `2→10`（bit3 上位） | `TRACE src=10`，`blocked=0`（**加位没被误拦**） |
| 22:01:49 | verity 删除 | `10→8`（`& ~2`，砍时间线） | `blk=1 after=10`（**bit1 保住**）⇒ 时间线还在 |
| 22:03:10/12 | 我再进主页 | `10→2`×2（`& ~8`，砍相册位） + `&-9` SQL×8 | `blk=1 after=10`，`sqlBlock9=8` ⇒ 主页还在 |

### 7.2 关键日志原文

删除时（方案 A 的判据，`want=8`）：

```
BLOCK drop snsId=-3397803690084199920 before=10 want=8 keys=18 hasContent=true hasAttr=true at=22:01:49
BLOCK after snsId=-3397803690084199920 before=10 after=10 (键已摘)
```

进主页时（方案 B 的目标，`want=2` + `&-9`）：

```
BLOCK ev id=-3397803690084199920 at=22:03:10 before=10 want=2 keys=18 C=1 A=1 blk=1 after=10 lf=2 sef=1
BLOCK ev id=-3397803690084199920 at=22:03:12 before=10 want=2 keys=18 C=1 A=1 blk=1 after=10 lf=2 sef=1

BLOCK w2 sql-clear9(已拦 -9→-1) rng=-3397803690084199920..*
UPDATE SnsInfo SET sourceType = sourceType & -9
WHERE SnsInfo.userName="wxid_xlrbelf6hv0w22"
  AND (sourceType in (8,264,10,266,12,268,...)) AND (snsId != 0) AND (snsId > -3397803690084199920)
```

### 7.3 三个必看数字（全部达标）

| 判据 | 要求 | 实测 |
|---|---|---|
| `sqlBlock9` 随进主页增长 | 线性增长 | `4`（第一次进主页）→ `8`（第二次），`sqlClear9` 与它**恒等** |
| `blkFail` | 恒 0 | **0**（8 条改写全成功） |
| 目标动态 `src` | 停在 `10` | `21:59:56` 起每 30s 采样，至 `22:03:26` 全程 `src=10 lf=2 sef=1` |

### 7.4 未误伤的证据

- `drop seen=26 blocked=3 allow=23` —— 26 次降位判定只拦了 3 次（全部是目标动态的
  `want=8`×1 + `want=2`×2），其余 23 次加位/原地重写**一律放行**。
- `afterOk=3 afterFail=0` —— 三次拦截的事后回查全部读到 `10`。
- `delSns=1 delRow=1` —— 全程**零条 `DELETE`**，再次印证微信删朋友圈不删行、只改位。

### 7.5 一个必须记下的边界

`rng=3 max=4x@?` —— 有 3 种区间形态，其中「只有上界」的分页
（`snsId < -3405064359876226540`）**解析不出 key**（记成 `?`），但**拦截本身照常生效**
（计入 `sqlBlock9`）。⇒ `rangeKey` 只影响记账粒度，不影响正确性；若将来要用区间做
「同一批行被重试几次」的判据，需要补无上界/无下界两种形态的 key。

### 7.6 复现命令

```bash
ADB="/c/Users/lx/AppData/Local/Android/Sdk/platform-tools/adb.exe"
# 账本（5 行计数 + 最近 10 条 event）
"$ADB" shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op snsg --es sub blockstat
# 点查看位（每 30s，最多 30 分钟；id=0 停）
"$ADB" shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op snsg --es sub trace --el id <snsId>
# 注意：SQL/字符串参数必须再包一层单引号，否则被 adb shell 二次解析拆成单词
"$ADB" shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op sql \
  --es q "'select snsId,sourceType,type,createTime from SnsInfo order by createTime desc limit 6'"
```

## 8. 反向消融（2026-11-06 22:10，已完成 —— B 层是必要条件）

只关 B 层、其余不动（`antirevoke.sns_sqlblock_off=true`，`SNS stat` 回读
`blockEnabled=true sqlBlock=false`），对同一条动态再进一次主页。

**结果：主页消失，`src 10 → 2`。**

```
TRACE 22:10:26  src=2                              ← bit3 被削
sqlClear9=10（+2）  sqlBlock9=8（不变）  blkOff=2   ← 2 条 &-9 命中、开关关、原样执行
BLOCK ev at=22:10:23 before=2 want=0 blk=1 after=2  ← 削成 2 后微信想清零，被甲拦住
```

三条判据互相印证，缺一不可：`sqlClear9` 增长证明**命中了**、`sqlBlock9` 不动证明**没拦**、
`blkOff` 增长证明**开关确实是关的**（不是“写了没生效”）。

### 8.1 ⭐ 这条实验还推翻了一个隐含假设

这次 `10→2` 前后 `drop blocked` **仍是 3、没有增加** ⇒ 削 bit3 的那一刀**完全没走
ContentValues**，A 层看不见它。

⚠️ 所以别再认为「A 拦了 `want=2` 就够了」。两条通道都会清 bit3：
- 进主页早期：ContentValues 写 `want=2`（A 层拦，22:03 实测 2 次）
- 进主页分页：`&-9` 裸 SQL（B 层拦，22:10 实测 2 条真的削掉了）

**教训与 R21c 同构**：`拦 SQL ≠ 拦住删除` 的反面 —— **拦 ContentValues ≠ 拦住清位**。

### 8.2 三层各自的不可替代性

| 层 | 拦的是什么 | 关掉后会发生什么 | 证据 |
|---|---|---|---|
| 甲 | `sourceType==0` | 降到 `0`，`isDeadSource()` 成立，彻底消失 | 22:10:23 `want=0` 被拦 |
| A | `10→8` / `10→2` 的 ContentValues | 时间线被砍（bit1）或 bit3 被削 | 22:01:49 `want=8`、22:03 `want=2` |
| B | `&-9` 裸 SQL | bit3 被削 → 主页消失 | 22:10 关掉后 `10→2` |

## 9. 尚未做的（本轮明确跳过）

1. **`& -9` 命中行数**：`execSQL` 无返回值，仍无 `sqlBlock9Rows`。
2. **`changes()` 是否被微信用到**：仍未取证（选了保守方案，不影响正确性）。
3. **`& -3` 真实形态**：全程 `sqlClear=0`，一条样本都没出现。
4. **滚动分页**：只观测到首屏，向下滚动时区间是否继续推进未测。
5. **冷启动后是否回算**：没让微信在拦截后重启再进主页（`10` 是库里值，理论上不会回算，
   但没证据）。
6. **另一类批量清位（`& -9` 之外的 raw SQL）**：`sqlUnmatched=0` 本轮没再出现，
   但「按好友批量清位的 raw SQL 绕过甲」这个已知口子仍未处理。

## 10. 本轮改动文件

| 文件 | 变更 |
|---|---|
| `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsSqlRewrite.kt` | 新增（纯函数：判定/改写/区间） |
| `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsGuard.kt` | `Config.sqlBlockEnabled`、`onSnsSql`、`applySqlRewrite`、`stat` 增字段 |
| `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsBlockProbe.kt` | `SqlBlock`、4 个计数 + 区间表、`noteSql` 新签名、日志/stat 口径 |
| `wxgroup/app/src/main/java/com/lx/wxgroup/data/Keys.kt` | `ANTIREVOKE_SNS_SQLBLOCK_OFF` + PrefSpec |
| `wxgroup/app/src/test/java/com/lx/wxgroup/feature/SnsSqlRewriteTest.kt` | 新增 20 条用例 |
| `wxgroup/_mut_b.py` | 新增（变异脚本，未提交） |

⛔ 未改动：数据库 schema、`docs/_r42_bit_paths.md` 及其它既有文档、方案 A 的 `SnsBitLogic.decide` 判据。

[ALL-DONE]
