# R27-3 · srcfix 实现记录（第一步：手动触发版）

> 目标：先在真机上**证明补位有效**。自动触发（同步响应后对账）本轮**不做**，只留接口。
> 设计依据：`docs/_r27_srcfix_design.md`（已定案：时间线可见性由 `SnsInfo.sourceType` 的 bit1(2) 决定）。
> 包名 `com.lx.wxgroup`，微信 8.0.48。

---

## 1. 改了什么（文件 / 位置）

| 文件 | 改动 | 要点 |
|---|---|---|
| `feature/SnsTimelineSrcLogic.kt`（**新**） | 全部 | 纯判据，零 Android 依赖，JVM 单测直接打：`admit` / `shouldFix` / `fixTarget` / `rollbackTarget` / `everVisible` + 三个规模常量 |
| `feature/SnsTimelineSourceFix.kt`（**新**） | 全部 | I/O 层：`stat()` 只读统计、`run()` 快照+补位、`rollback()` 反向还原、`autoReconcile()` 给将来自动触发用（本轮未接线）；`snapshot`（键集分页）、`probe`（单行点查）、`updateSourceType`（反射写库） |
| `feature/SnsGuard.kt` | 386 行，**+7 行** | 唯一改动：`internal fun snsDbHandle(): Any? = snsDb` 只读暴露库句柄。⛔ delete / execSQL 拦截逻辑**一行未动**，也没加任何新钩子 |
| `data/GroupDb.kt` | `DB_VERSION 8→9`（41 行）、`T_SRC` 常量（51）、`onCreate`（103）、`onUpgrade`（169）、`createSrcTable`（249）、台账 API（525–660） | 新增 `sns_visible_src` 表；老库走 `if (oldVersion < 9) createSrcTable(db)`（纯新增表 + `CREATE IF NOT EXISTS`，**没有 ALTER 老表**，不会让老用户升级崩溃） |
| `entry/EntryReceiver.kt` | 350（读 `sub2`）、443–456（`srcstat` / `srcfix` 分支） | 照 `stat|dump|one|ls` 的写法接线；`sub2=rollback` 走回滚 |
| `test/.../feature/SnsTimelineSrcLogicTest.kt`（**新**） | 全部 | 15 个用例 |

### 台账表 `sns_visible_src`（模块自己的 `wxgroup.db`，不写微信库）

| 列 | 语义 |
|---|---|
| `sns_id` INTEGER PRIMARY KEY | 微信 `SnsInfo.snsId`（**可能为负**，别用 >0 判） |
| `source_type` INTEGER | **最近一次**亲眼见到它 `& 2 != 0` 时的值（不是当前值，当前值每次现查微信库） |
| `first_seen` / `last_seen` INTEGER | 首次 / 最近一次见到可见的毫秒时间戳（`first_seen` 不覆盖） |
| `fixed_orig` INTEGER NULL | 最近一次补位**之前**的原值（回滚依据）；null = 没补过 / 已回滚 |

### 与设计的对应

- 快照步 = 设计 §1.3 A：分页扫 `where (sourceType & 2 != 0)`，单页 100，最多 30 页；
  **没有**复用 `SnsGuard.listCurrentSns` —— 它 R26 起**故意不带任何 sourceType 过滤**
  （`docs/_r26_bit16_rootcause.md`），拿它当快照源会把所有不可见的行都当"见过"，判据直接失效。
  复用的是它的**键集分页写法**（`createTime, snsId` 复合游标，避免页边界丢同秒行）。
- 补位步 = 设计 §1.3 B + §4.1 双保险：台账「曾经可见」+ 当前 `& 2 == 0` + 当前 `& 4 == 0`。
- 写库 = 仿 `SnsGuard.updateFlag`：`ContentValues` + `update` 4 参反射，whereArgs 按
  `parameterTypes[3]` 分流 `Array<String>` / `Array<Object>`（写死一种会静默失效）。
- ⛔ **不做 INSERT 回填**：行没了就是 `probe.exist=false` → 计 `gone` 跳过。
- 日志限流：明细最多 50 行（`MAX_DETAIL_LOGS`），其余只计数，最后一行是聚合行。

---

## 2. 探针怎么用

```
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
    --es op snsg --es sub srcstat

adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
    --es op snsg --es sub srcfix

adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
    --es op snsg --es sub srcfix --es sub2 rollback
```

（子命令参数沿用 `op=quiet` 的既有约定 `sub2`，`sub` 只做路由。）

日志行（`wxgroup_dbg.txt`）：

```
SNS srcstat ledger=812 ever=790 cand=3 priv=2 gone=41 err=0 held=1 switch=true
SNS srcfix snsId=-3405809214430834153 0 -> 2 rows=1
SNS srcstat snap=812 fixed=1 skip=789 priv=2 gone=41 fail=0 err=0 new=812 pages=9 ledger=812 held=1
SNS srcroll snsId=-3405809214430834153 2 -> 0 rows=1
SNS srcstat rolled=1 skip=0 gone=0 fail=0 held=0
```

字段含义：
- `ledger` 台账总行数 / `ever` 其中「曾经 `& 2 != 0`」的行 / `cand` **现在**能补的候选数
- `priv` 候选里因**私密位在**被跳过的（正常应为 0；非 0 说明有行被微信改成了私密）
- `gone` 行已被真删（补不动、也不会 INSERT） / `fail` 写库失败 / `err` 点查读不出来
- `held` 台账里「补过位、还没回滚」的行数（= 下一轮 `rollback` 会处理的行数）
- `snap` 本轮快照扫到的可见行数 / `new` 新增台账行数 / `pages` 扫了几页

开关：复用 `Keys.ANTIREVOKE_SNS`（`antirevoke.sns`）。关掉时 `srcfix` 只回一行
`SNS srcfix switch off (antirevoke.sns=false)`，**不执行**；`rollback` **不**受开关限制（停保护 ≠ 禁止收拾现场）。

---

## 3. 单测与变异验证

命令：`python tools/gradle_run.py testDebugUnitTest`

| 阶段 | 结果 |
|---|---|
| 基线（改动前） | 664 tests / 0 failed |
| 本轮实现后 | **679 tests / 0 failed**（新增 15 个：`SnsTimelineSrcLogicTest`） |

变异验证（每次都改坏 → 跑 → 确认变红 → 改回 → 再跑全绿）：

| # | 变异 | 期望变红的用例 | 实测 |
|---|---|---|---|
| 1 | `shouldFix` 去掉 `&& !isPrivate(current)`（= 去掉第二道保险） | `neverFixesPrivateEvenIfLedgerSaysVisible` | ✅ 679 tests completed, **1 failed** |
| 2 | `shouldFix` 去掉 `everVisible &&`（= 不看台账，见 0 就补） | `fixesOnlyWhenLedgerSaysEverVisible` | ✅ 679 tests completed, **1 failed** |
| 3 | `rollbackTarget` 去掉「orig 自带 bit1 就失效」的守卫 | `rollbackIgnoresDirtyOriginalThatStillHasTimelineBit` | ✅ 679 tests completed, **1 failed** |

恢复后：`BUILD SUCCESSFUL`，679 / 0 failed。

判据覆盖点：
- `src=4` 绝不补（连"台账说有"也不补）——**单测 1 直接锁死**
- `src=0` 且台账无记录绝不补；`src=0` 且台账有记录才补
- `src=2 / 130` 已可见 ⇒ 不重复写（幂等）
- `130 → 128`（广告位被清时间线位）⇒ 补回 130，回滚回 **128**（不是 0）
- `fixTarget` 只 `| 2`，其它位（128）不丢

---

## 4. 真机验证步骤（判据对着"用户看得见的界面"写）

前置：`antirevoke.sns = true`；已至少**成功打开过一次朋友圈**（否则
`SnsGuard.snsDbHandle()` 还是 null，探针只会回 `no sns db yet`——
这是句柄捕获机制的正常状态，不是失败）。

现成样本（21:20 干净对照实验留下的）：

| 样本 | snsId | 当前库里 src | 用途 |
|---|---|---|---|
| 好友 verity 被删的公开动态「5」 | `-3405809214430834153` | 0 | **回放样本**：补位后应重新出现在时间线 |
| 自己的私密动态 | `-3405840164296256840` | 4 | **反向对照 1**：绝不能补 |
| verity 更早的 src=0 旧动态 | `-3405824697470283239` | 0（从未可见） | **反向对照 2**：绝不能补 |

| 步 | 操作 | 期望（界面 + 库 + 日志） |
|---|---|---|
| 0 | 先 `sub=srcfix` 跑一轮快照（**这步必须在「5」还看得见时做过**，否则台账里没有它） | `sub=srcstat` 的 `ever` 里含 `-3405809214430834153`；库里值不变 |
| 1 | `sub=srcfix`（若「5」此刻仍是 src=2，会走快照、`fixed=0`；先让好友删掉它，或直接看第 2 步） | — |
| 2 | 好友删掉「5」后（或已验证它变成 0）再 `sub=srcfix` | 日志出现 `SNS srcfix snsId=-3405809214430834153 0 -> 2 rows=1` + 聚合行 `fixed>=1`；`snsg sub=one --el id ...` 显示 `src=2` |
| 3 | **下拉刷新朋友圈** | 界面：verity 的「5」**重新出现**（主判据） |
| 4 | 连续下拉刷新 3 次 | 每次刷新后「5」**都在**。⚠️ 本轮**没有自动触发**，如果微信在这 3 次刷新中把它再清掉，需要**每次刷新后手动 `sub=srcfix`**；「刷新后自己就没了」是预期现象（§3.2 的稳定性设计属于自动触发阶段） |
| 5 | 杀微信重进朋友圈 | 重进后「5」仍在（DB 位已修）；若不在，再跑一次 `sub=srcfix` 后下拉 |
| 6 | 反向 1：看自己的私密动态（src=4） | 主时间线**仍不显示**；`srcstat` 的 `priv` 计数里也不会把它算成候选 |
| 7 | 反向 2：verity 早期 src=0 旧动态 | **仍不显示**；`snsg sub=one` 仍是 `src=0`（台账从来没有它） |
| 8 | 回滚：`sub=srcfix --es sub2 rollback` | 日志 `SNS srcroll snsId=... 2 -> 0 rows=1`；`held=0`；下拉刷新后「5」**消失** |

失败定位：
- `srcstat cand=0` 但界面确实少了一条 ⇒ 台账里没这条（快照没在它可见时跑到）⇒ 检查第 0 步；
- `srcfix` 报 `rows=0` ⇒ 行已真删（`sub=one` 显示 GONE），或反射写失败（看 `SNS update sourceType failed`）；
- `no sns db yet` ⇒ 还没打开过朋友圈，句柄未捕获。

---

## 5. 本轮边界（明确未做）

- **自动触发未接线**：`SnsTimelineSourceFix.autoReconcile()` 已按「后台线程 + 幂等 + 全程 runCatching」
  写好，将来在同步响应后 / 导出轮 / 冷启进朋友圈处**加一行调用**即可，不用改本文件。
- 没有新增任何常驻钩子；`execSQL` 上那条约 `UPDATE SnsInfo SET sourceType` 的"看见清位"分支也没加
  （设计 §2.6 里它是可选的加速器，属于自动触发阶段）。
- 台账扫描窗口 5000（`last_seen DESC`）：超过上限的最老记录本轮补不到，这是有意的开销取舍。
