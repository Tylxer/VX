# R27-5 · 方案 B：清位后立刻补回（SrcGuard re-fix）

> 目的：先用最小代价回答一个悬而未决的问题 —— **时间线到底读不读本地 `SnsInfo` 表**。
> 如果读，方案 B 就够用；如果不读，才需要上 Response-Merge（方案 A）。

## 1. 为什么需要这一步（R27-4 真机实测结论）

| 时点 | 库里 `src` | 界面 |
|---|---|---|
| 快照后（未删） | 2 | 显示 |
| 好友删后、未刷新 | 2 | 显示 |
| 下拉刷新后 | **0** | 消失 |
| `sub=srcfix` 补位 | 0 → **2** | — |
| 联网冷启进朋友圈 | 2 → **0**（被同步清回） | 消失 |
| **断网冷启进朋友圈** | **2** | ✅ 出现 |
| 恢复网络 + 刷新 | **0** | 消失 |

⇒ 补位本身有效（断网冷启就是证据），但**每轮 sync 都会重新清位**：
`updateFilterTimeLine` 先把所有权重行统一 `sourceType & -3`，再由 classifytimeline 把
「服务器仍然返回的」补回来；被删的那条不在响应里 ⇒ 停在 0。
一次性补位活不过一轮，必须**跟在清位后面每轮跑**。

## 2. 实现

### 2.1 判据（纯函数，JVM 单测）

`SnsTimelineSrcLogic`：

```kotlin
isSourceClearSql(sql)   // 认得微信那条：sourceType = sourceType & -3（容忍空格/大小写/括号）
looksLikeSourceWrite(sql) // 诊断：像在改 SnsInfo.sourceType 但没被认出来 ⇒ 采样打印真实 SQL
isOwnFixSql(sql)        // 自己写的 sourceType = sourceType | 2 ⇒ 采样时排除，避免自刷日志
```

⛔ **`isSourceClearSql` 必须拒绝我们自己的补位 SQL**，否则 after 钩子会被自己触发，
变成「补位 → 钩子 → 补位」的无限递归。单测 `rejectsOwnFixSql` 专钉这一条。

### 2.2 钩子（`SnsGuard.sqlGuard` 加 after 分支）

```
execSQL before → onExecSql（原有 DELETE 拦截，一行没动）
execSQL after  → onExecSqlAfter
                   ├─ isSourceClearSql(sql)? 否 → 采样诊断，返回
                   └─ 是 → SnsTimelineSourceFix.reFixAfterClear(db)
```

⚠️ **必须挂 after**：清位 SQL 在 before 里补位会被同一条 SQL 立刻清掉。

### 2.3 补位主体（`SnsTimelineSourceFix.reFixAfterClear`）

- 候选 = 台账里 `everVisible` 的 id（`src=4` / `src=0` 的行走不进台账 ⇒ 结构上够不着）
- **分块一条 UPDATE**（每块 500 个 id）：

```sql
update SnsInfo set sourceType = sourceType | 2
where snsId in (...) and (sourceType & 2) = 0 and (sourceType & 4) = 0
```

- 为什么不用现有的 `run()`：清位之后**所有**台账行都是候选（3000+），
  逐行 `probe` + 逐行 `update` 会把同步线程拖慢好几秒；3000 行改成分块只要 7 条语句。
- 每块先 `count(*)` 数出「真正需要补」的行数（`execSQL` 返回 void 不给行数，
  少了这步日志只能证明「发了几条 SQL」，证明不了补到东西）。
- 重入保护 `AtomicBoolean`：钩子触发的写库不会再进本函数。
- 只 `UPDATE` 不 `INSERT`：行被真删了就改不到，绝不造行。

### 2.4 日志

```
SNS srcguard re-fix after clear fixed=<N> cost=<T>ms        # 每轮 sync 一条
SNS srcguard re-fix failed chunk=<i> ...                    # 写库失败
SNS srcguard unmatched sourceType sql (adopt me): <SQL>      # 微信换写法，最多 3 条
```

## 3. 验证

### 3.1 单测（`SnsTimelineSrcLogicTest` 新增 4 条，总 **683 tests / 0 failed**）

| 用例 | 钉的是什么 |
|---|---|
| `detectsWechatClearSourceSql` | 三种写法（带空格 / 无空格 / 带括号）都要认 |
| `rejectsOwnFixSql` | 自家补位 SQL 绝不能被当成清位（递归风险） |
| `flagsUnmatchedSourceWriteForSampling` | 换写法时能采样到真实 SQL |
| `ownFixSqlIsRecognisedSoItDoesNotPolluteSampling` | 自家 SQL 不刷采样日志 |

**变异验证**（两处同时注入，各命中一个用例）：

| 变异 | 结果 |
|---|---|
| `CLEAR_SQL_RE` 的 `-3` → `-5` | `detectsWechatClearSourceSql` FAILED |
| `OWN_FIX_SQL_RE` 的 `\| 2` → `\| 3` | `ownFixSqlIsRecognisedSoItDoesNotPolluteSampling` FAILED |

还原后复跑全绿。

### 3.2 真机判据（等结果）

前提：verity 那条 22:13 的「6」已在台账里（`ledger=3011`），当前库里 `src=0`。

1. 装包 + 冷启两次
2. 进朋友圈 → 日志应出现 `SNS srcguard re-fix after clear fixed=<N> cost=<T>ms`
3. **下拉刷新一次**
   - 界面出现 verity「6」⇒ 时间线**读库**，方案 B 成功，不需要 Response-Merge
   - 界面仍没有，但库里 `src=2` ⇒ 时间线**纯用响应对象**，必须上方案 A
4. 反向对照：自己的私密动态（`src=4`）**不得**出现在时间线（隐私底线）
5. 回滚：`op=snsg sub=srcfix --es sub2 rollback`

## 4. 风险

- 每轮 sync 多 7 条 UPDATE + 7 条 count（实测耗时见日志 `cost=`）。若 `cost` 超过 ~50ms 需要优化（去掉 count / 只跑有候选的块）。
- 语义边界：台账里的行**永远**保持可见 ⇒ 「三天可见」「分组可见」之类由服务端收缩的内容也会留在本地时间线。
  这是「防删除」的必然代价，需要用户知情。
- 只覆盖 `execSQL` 形态；若微信改走 `update()`，`isSourceClearSql` 不会命中，
  但会打出 `unmatched sourceType sql (adopt me)` 采样日志，便于补钩子。
