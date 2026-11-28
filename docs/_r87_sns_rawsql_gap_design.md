# R87 朋友圈 raw SQL 批量清位口子 — 缺口裁决与路线对照

> 状态：用户已按 D2 选 **R1（归档收口）**，设计阶段完成；**未改任何源码、未装机**。
> 依据：`docs/当前状态.md` §二 D 第 4 项；用户 2026-11-28 21:23 指令「先推 4，朋友圈 raw SQL 口子」。
> 派发：Pro / Flash 两次 402（首个 API 调用前零产出），已按 `docs/派发审计台账.md` 取③主会话自审并登记三件套。

---

## [DONE] S1 缺口裁决

### 1.1 已覆盖（源码实证 + 真机实证）

| 项 | 证据 | 结论 |
|---|---|---|
| 甲：`updateWithOnConflict` before 摘 `sourceType` | `SnsGuard.kt:163-165` 挂 before；`SnsGuard.kt:879-899` 写时拦截链；真机 `BLOCK stat hit=1 blocked=1 shape=rm:1` | ContentValues 写 `sourceType=0` 已被拦 |
| B：`execSQL` 的 `sourceType & -9` 裸 SQL | `SnsSqlRewrite.kt:65-69` 判 SET 段；`:77-85` 改写；`SnsGuard.kt:907-922` 写回 `param.args[0]`；`SnsGuard.kt:152` 挂 `execSQL` | 已知批量清位 SQL 已被拦 |
| WHERE / 命中集合 / `changes()` 保持 | `SnsSqlRewrite.kt:125-141` 顶层 `whereIndex` 只切 SET 前；`:83` 只替换常量 `-9→-1`；`SnsSqlRewriteTest.kt:51-57`「WHERE 逐字不变」 | 不是跳过执行、不是 WHERE 恒假，分页游标不受影响 |
| `sqlUnmatched` 观测口 | `SnsBlockProbe.kt:552-606`；`:601` 计数；`:602-604` 原文去重采样 + `BLOCK w2 sql-unmatched` 日志 | 「改 sourceType 但形态未识别」有唯一痕迹 |

**真机当前快照（2026-11-28 21:27，`op=snsg sub=blockstat`）**：
- `sqlClear9=2 sqlBlock9=2 blkOff=0 blkFail=0 sqlUnmatched=0 sqlClear=0`
- 21:18 抓到原文：`UPDATE SnsInfo SET sourceType = sourceType & -9 WHERE SnsInfo.userName="Captain Xu 的 wxid" AND (sourceType in (8,264,10,...))`
  ⇒ 这条**就是**已知口子，已被改写为 `& -1` 拦下，且 `sqlUnmatched=0`。

### 1.2 仍未覆盖（只剩这一种理论可能）

**未知的第三条 raw SQL 模板**：一条 `UPDATE SnsInfo SET sourceType = <新表达式>`，既不是 `-9`、也不是 `-3`、也不是我们自己 `| 2|10`，就会绕过 A/B 两层，只被 `sqlUnmatched` 记账 + 采样。当前**零样本**（`sqlUnmatched=0`，`_r42_bit_b_report.md:344-345` 同样记录本轮未再出现）。

### 1.3 已定案不做（不能再捡回来）

- `& -3` 改写：`docs/当前状态.md` §三明确「真机从未出现，无样本估副作用，`SnsSqlRewriteTest` 已固化原样返回」；测试 `:137-143` 钉死 `shouldBlock=false`、逐字返回。
- 改 WHERE 恒假 / 跳过执行：`SnsSqlRewrite.kt:16-21` 已论证 `changes()` 与分页游标风险，硬排除。

### 1.4 一句话缺口定义

> **已知 `&-9` 口子已闭合；剩余缺口不是「漏拦某条已知 SQL」，而是「若微信将来换一种未知 sourceType 写形态，我们只能事后从 `sqlUnmatched` 看见，不能预防」。**

⇒ `docs/当前状态.md` §二 D4 的「补这个口子 = 待办」表述**已过时**：它把「记台账并告警」（`_r42_block_result.md:116` 的要求）当成未做，而该能力已在 `SnsBlockProbe.noteSql` 落地。

---

## [DONE] S2 D2 路线对照表

| 路线 | 核心动作 | 代码量 | 额外写库 | 竞态 / `changes()` 风险 | 失效场景 | 验证成本 |
|---|---|---|---|---|---|---|
| **R1 归档收口（推荐）** | 不改代码；把 `当前状态.md` §二 D4 移到已闭环，写明 B 层 + `sqlUnmatched` 已覆盖，`&-3` 已定案不做 | 0 行源码；2 份文档 | 无 | 无（零行为变化） | 将来出现未知模板时仍只能靠 `sqlUnmatched` 看见，不能自动拦 | 0；已有 21:27 真机计数佐证 |
| **R2 只强化观测** | 在 `SnsBlockProbe.noteSql` 的 `sqlUnmatched` 分支提升日志可见性 / 扩采样上限，并在 `stat()` 输出最近模板摘要 | ~1 个功能文件，<40 行 + 单测 | 无 | 无（只读计数，不碰 SQL） | 仍不拦未知模板，只是更早、更清楚看见 | 低：单测 + 一次装机看 `blockstat` |
| **R3 通用改写未知 sourceType 写** | 对 `UPDATE SnsInfo SET sourceType=...` 的未知表达式一律换成 no-op / 原值 | ≥2 个功能文件，≥100 行 + 新判据 + 变异 | 无 | **高**：可能把微信正常初始化 `sourceType=2/10` 也变成 no-op，破坏正常写库；误伤不可即时发现 | 新模板正常业务写被吞；`changes()` 仍相同但语义错 | 高：必须拿到新样本 + 正反对照 + 全量回归 |
| R4 补 `& -3` 改写 | 把 `-3` 也改 `-1` | — | — | — | 已定案不做，无样本 | —（排除） |

---

## [DONE] S3 唯一推荐

**推荐 R1（归档收口）。** 理由：

1. `_r42_block_result.md:116` 当年要求的「execSQL 里识别 `SnsInfo` + `sourceType` 的 UPDATE，记台账并告警」**已经实现**于 `SnsBlockProbe.noteSql`；当前 `sqlUnmatched=0` 且 21:18 真 `&-9` 被拦，证明链路活着。
2. R2 的边际收益很低：现有 `sqlUnmatched` 已有原文去重采样（上限由 `SnsBlockProbe.kt:54` 从 20 提到 60）和 `BLOCK w2 sql-unmatched` 日志；没有新样本时扩采样只是放大空计数。
3. R3 违反 D4「控制优先于观测」的反面：它不是控制已知事件，而是**猜测未知事件并替微信改库**，风险不对等；且与已定案「`&-3` 不改写」的保守口径冲突。
4. 若用户坚持要 R2，最小动作只动 `SnsBlockProbe.kt`，任何开关默认关、关掉逐字透传，且**不动** `SnsSqlRewrite` / `SnsGuard.onSnsSql` / `updateWithOnConflict` A/B 两层。

**红线（三档）**
- 进入：只读上述文件；不改 `app/src/main`；不碰 `updateWithOnConflict` / A/B 两层。
- 返回：只交付本设计文档 + 路线表；落码前必须用户选路线。
- 复位：任何新增开关默认关；关掉后 SQL 逐字透传；WHERE / 命中集合 / `changes()` 一项都不动。

---

## [DONE] S4 判据与变异（仅当改选 R2/R3 时启用）

R1 无代码改动，无需单测/变异。

若 R2：
- 单测：`noteSql` 对 `isSourceWrite && !clearBit1 && !clearBit3 && !own` 仍计 `sqlUnmatched+1`；模板摘要只在采样命中时出现；开关关时计数不变。
- 变异：①去掉 `own` 分支 ⇒ 自己补位 SQL 被误记（红）；②把采样条件取反 ⇒ 日志噪音（红）；③把 `sqlUnmatched` 计数挪到 `isSourceWrite` 判断前 ⇒ 所有 SnsInfo SQL 都计（红）。

若 R3（不推荐）：
- 必须先补「正常 `sourceType=2/10` 写入必须逐字透传」的反向守门，再谈未知模板；否则任何变异测试都是假绿。

## [DONE] S5 三档红线自检

- [x] 进入边界：只读源码与 `_r42_*` 文档，未改 `app/src/main`，未碰 adb 写操作
- [x] 返回边界：只写本设计文档，未改 `当前状态.md` / 台账 / memory
- [x] 复位边界：任何后续方案均以「关掉逐字透传 SQL」为硬条件
- [x] 派发合规：Pro 402 → Flash 402 → 三件套登记 → 主会话自审，未静默豁免

[DONE ALL]

## [DONE] S6 用户拍板与收口

- 2026-11-28 21:3x 用户按 D2 路线表选 **R1（归档收口）**，不选 R2 观测强化与 R3 通用改写。
- 执行动作：更新 `docs/当前状态.md` 顶部 R87 条目 + §一已闭环 R87 行 + §二 D4 划线归档；本设计文档状态同步。
- 不做的动作：不改 `app/src/main`、不改单测、不装机、不新增开关；`&-3` 维持「只识别不改写」的定案。
- 派发合规：Pro/Flash 两次 402 → 三件套登记 → 主会话自审，已在 `docs/派发审计台账.md` 留痕。
