# R42 方案 A 实现报告：朋友圈防撤回只拦两个 update 降位点

> 范围：`SnsGuard` 的 `updateWithOnConflict` 写时拦截。
> 依据：`docs/_r42_bit_paths.md`（只读参考，本次未改动）与 2026-11-06 用户定稿的方案 A。
> 本次**未装包、未 adb、未 commit**；结论里的「预期」与「已实测」严格分开标注。

---

## 1. 做了什么（一句话）

`sourceType` 的两条真实「消失路径」都不写 0，所以只拦「写 0」必然漏：

| 路径 | 位变化 | 谁在写 | 旧实现 | 现在 |
|---|---|---|---|---|
| 时间线消失 | `10 → 8`（清 bit1） | `updateWithOnConflict` | 放行（非 0） | **拦** |
| 主页相册消失 | `10 → 2`（清 bit3） | `updateWithOnConflict` | 放行（非 0） | **拦** |
| 彻底清零 | `2 → 0` / `8 → 0` | `updateWithOnConflict` | 拦 | 拦（逐字保留） |
| 加位 | `0→2` / `2→10` / `0→8` / `2→130` | `updateWithOnConflict` | 放行 | 放行（必须） |
| 清 bit3（裸 SQL） | `sourceType & -9` | `execSQL` | 只计数？从未识别 | **只识别、只计数、绝不拦** |

`& -9` 为什么不拦：见 §5。

---

## 2. 判据（纯函数，可单测）

`wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsBitLogic.kt`

```kotlin
fun decide(old: Int?, new: Int): Action = when {
    new == 0 -> BLOCK                                              // 写 0：不查旧值也拦
    old == null -> ALLOW                                           // 旧值不可知 ⇒ 不动
    isPureDowngrade(old, new) &&
        (loses(old, new, BIT_TIMELINE) || loses(old, new, BIT_ALBUM)) -> BLOCK
    else -> ALLOW
}
```

- `BIT_TIMELINE = 2`、`BIT_ALBUM = 8`；
- `isPureDowngrade(old,new) = (new and old.inv()) == 0`，即**新值是旧值的子集**（只删位、不添位）；
- `loses(a,b,bit) = a 有且 b 没有`。

### 2.1 为什么必须吃旧值

`2` 既可能是 `10→2`（降位，要拦），也可能是 `0→2`（加位，必须放行）——**新值完全相同**，
唯一区别是旧值。`8` 同理（`10→8` vs `0→8`）。

### 2.2 为什么加「纯降位」这个前提（比用户枚举略窄）

用户枚举的是四组纯降位。按位直接判会把**混合改写**也算成降位，最典型的是 `8→2`：
它丢 bit3，但同时加回 bit1。拦掉它等于把动态按在「只在相册、时间线仍不可见」，
与「保住可见性」的目标正好相反。因此规则收紧为：**只有不添任何新位时才拦**。

于是：

- 拦：`10→8`、`10→2`、`2→0`、`8→0`、`264→256`、`138→130`、`136→128`、`11→8`；
- 放行：`0→2`、`2→10`、`0→8`、`2→130`、`8→2`、`2→8`、`10→130`、`10→6`、`130→2`（只丢折叠位）。

> ⚠️ 未实测部分：`264→256` 等高位组合是**按同一规则推导**的覆盖，不是真机观察到的写入。
> 真机上它们会不会发生、发生了该不该拦，需要新包上机后看 `drop` 计数与事件行。

### 2.3 快速排除（热路径零 IO）

`needsOld(new)`：新值同时含 bit1+bit3（如 `10`）⇒ 一定没丢位，**连旧值都不查**。
只有缺某一位时才点查一次 `select sourceType,... where snsId=?`（主键点查）。
真实频次量级：一次进好友主页几十次写（`_r42_bit_paths.md` §3），不是消息表那种热路径。

---

## 3. 代码改动

| 文件 | 改动 | 关键行 |
|---|---|---|
| `feature/SnsBitLogic.kt`（新增） | 位判据 + `& -3` / `& -9` 形态识别，纯 JVM（无 Android/Xposed import） | `decide` L62、`needsOld` L53、`sqlClear` L108 |
| `feature/SnsGuard.kt` | `onSnsUpdateBefore` 分流：非 0 走降位判定；抽出 `applyBlock` 让「写 0」与「降位」共用同一套摘键/回滚逻辑 | `onSnsUpdateBefore` L275、`onSnsUpdateDrop` L339、`applyBlock` L370 |
| `feature/SnsGuard.kt` | execSQL 只剩「识别 + 计数」；删除内联正则，改调 `SnsBitLogic` | `onExecSql` L506 |
| `feature/SnsBlockProbe.kt` | 新增降位计数、`sqlClear9` 独立计数、事件行加 `want=` | `record` L254、`noteSql` L422、`stat` L543 |
| `app/src/test/.../SnsBitLogicTest.kt`（新增） | 20 个用例 | — |

### 3.1 拦截动作本身没变

`applyBlock` 仍是：`ContentValues.remove("sourceType")` → 若 values 变空则**放回微信原本要写的值**
（写 0 路径放 `0`，降位路径放 `2`/`8`）→ 记账 → after 钩子回查一次。
唯一新增的分支是「放回值取 `newValue` 而不是硬编码 0」——否则降位路径会替微信改库。

### 3.2 `& -9` 的处理（代码级边界）

`onExecSql` 里只有这一处与 SQL 有关：

```kotlin
val kind = SnsBitLogic.sqlClear(sql)
SnsBlockProbe.noteSql(sql, isSourceWrite = ..., clearBit1 = kind.clearsBit1, clearBit3 = kind.clearsBit3, own = ...)
```

- ⛔ 不改 `param.args`、不设 `param.result`、不跳过执行、不改 WHERE；
- `noteSql` 没有返回值，调用方拿到它也无从「按结果拦」；
- 新增 `sqlClear9` 计数 + 独立采样（`BLOCK w2 sql-clear9(-9 未拦) <SQL>`，去重、单独 60 条配额、
  落盘 1500 字符），因此 `& -9` 的完整 WHERE（含 `IN (8,264,10,...)`）能被原样看到。

---

## 4. 观测通道（新包上机后怎么验）

`op=snsg sub=blockstat`（5 行计数 + 最近 10 个事件，≤16 行）：

```
BLOCK stat hit=.. blocked=.. nonZero=.. skipOff=.. last=..
BLOCK skip empty=.. noApi=.. noId=.. parseFail=.. afterOk=.. afterFail=..
BLOCK drop seen=.. blocked=.. off=.. allow=.. unknown=.. empty=.. noApi=..
BLOCK upd seen=.. zero=.. nosrc=.. | ins seen=.. zero=.. nz=.. nosrc=..
BLOCK w2 delSns=.. delRow=.. sqlSns=.. sqlSrc=.. sqlClear=.. sqlClear9=.. sqlOwn=.. sqlUnmatched=..
BLOCK ev id=.. at=.. before=10 want=8 keys=.. C=.. A=.. blk=1 after=10 lf=.. sef=.. ct=..
```

判读表：

| 想验证 | 看什么 | 期望 |
|---|---|---|
| `10→8` 被拦 | `drop blocked` 增长；事件 `before=10 want=8 after=10` | 时间线位保住 |
| `10→2` 被拦 | `drop blocked` 增长；事件 `before=10 want=2 after=10` | 相册位保住（位型停 10） |
| 加位没被误拦 | `drop allow` 增长、`drop blocked` 不涨 | 新动态正常进相册 |
| `& -9` 是否真的执行、是否命中 10 | `sqlClear9` 增长 + `BLOCK w2 sql-clear9(-9 未拦)` 原文 | 这是**唯一**能验证推论的数据 |
| id 抠不出导致漏判 | `drop unknown` | 应为 0；非 0 说明 where 形态变了 |

---

## 5. 为什么暂不拦 `& -9`

**这是用户 2026-11-06 20:29 的决定（方案 A），不是技术做不到。**

1. **单独拦它没有收益。** 实测两次删除（图片 19:42、视频 20:02）里，清 bit3 的主通道是
   `update` 写 `2`（`sqlSrc` 当时为 0，那条裸 SQL 一次都没执行）。只拦 `& -9` 属于拦一条
   「当时根本没跑」的通道。
2. **它的 WHERE 枚举里含 `10`。** `_r42_bit_paths.md` §3.2 抓到的条件是
   `userName=? AND sourceType IN (8,264,10,266,12,268,...)`，而 `10` 正是我们拦住 `10→8`
   之后**位型会停在的值**。
3. ⇒ **已知推论（⚠️ 未实测）**：拦了写 `8` 后，删除场景的位型停在 `10`；此时若 `& -9` 执行，
   它会把 `10` 削成 `2`（`10 & ~8 = 2`），主页相册仍然丢。构造不出这个场景是因为旧包拦不住
   `10→8`，所以这条推论只能等新包验证。
4. 先只做两个 update 点，是为了把**变更面收在一条已实测过的通道上**（`updateWithOnConflict`
   的 before 钩子已在 R42 甲验证过），避免同时改 execSQL 参数带来不可控风险。

### 5.1 因此本次预期效果的边界（写死，别过度承诺）

| 场景 | 时间线 | 好友主页 |
|---|---|---|
| 进主页对账（写 `2`） | ✅ 保住 | ✅ 保住（写 2 被拦，位型停 `10`） |
| 好友删除（写 `8` + `& -9`） | ✅ 保住 | ⚠️ **可能仍丢**（`& -9` 把 `10` 削成 `2`，本次不拦） |

⇒ 本次交付的是「**进主页对账能保住主页；删除场景只保住时间线**」。
删除场景的主页是否保得住，取决于 §4 里 `sqlClear9` 的实测结果；若确认命中 `10`，
下一轮再讨论是否把 `& -9` 也纳入拦截（届时必须同时处理 `8→0` 的回滚语义）。

---

## 6. 验证

### 6.1 构建与单测

环境（Git Bash 会污染 `java.library.path`，测试进程会 `ClassNotFoundException` 且一个用例都不跑）：

```
JAVA_HOME = G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot
PATH      = G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot\bin;C:\Windows\System32;C:\Windows
```

```
.\gradlew.bat assembleDebug testDebugUnitTest --console=plain
BUILD SUCCESSFUL in 20s
```

- `app/build/outputs/apk/debug/app-debug.apk`：3,011,226 B，2026-11-06 20:45:54
- 单测汇总：**38 个 suite / 717 个用例 / 0 failures / 0 errors**
- 新增 `SnsBitLogicTest`：**20 个用例全绿**（`tests="20" failures="0"`）

### 6.2 变异验证（5 组，全部被杀）

| # | 变异 | 结果 |
|---|---|---|
| M1 | `loses()` 改成 `(new and bit) != 0`（永不判「丢位」） | ❌ 5 红：`other_pure_downgrades…`、`rewrite_with_same_value…`、`folded_bit_loss…`、`needs_old…`、`mixed_rewrites…` |
| M2 | 去掉「纯降位」前提（`isPureDowngrade` 恒 `true`） | ❌ 1 红：`mixed_rewrites_are_allowed`（`8→2` 等被误拦） |
| M3 | `new == 0 -> ALLOW` | ❌ 1 红：`write_zero_is_blocked_even_without_old_value` |
| M4 | `& -9` 正则写成 `-8` | ❌ 2 红：`sql_clear_bit3_is_recognized`、`sql_with_both_masks…` |
| M5 | `needsOld()` 恒 `false`（快速排除失效） | ❌ 1 红：`needs_old_only_when_a_watched_bit_is_missing` |

M1–M3 在**最终判据**上复跑；M4/M5 所在代码在判据收紧时未改动，沿用同版本的运行结果。
每组变异跑完都已还原，最终版本再跑一次全绿（§6.1）。

### 6.3 没做的验证（明确留白）

- 未装包、未 adb、未在真机上跑删除/进主页流程 ⇒ `10→8` / `10→2` 的**拦截效果**仍是
  「判据 + 单测 + 已有取证」的推论，不是本轮实测结论；
- `& -9` 是否命中 `10` 同上；
- 高位组合（`264→256` 等）未在真机观察过。

---

## 7. 已知取舍与风险

1. **`10→2` 同时是「好友删除」与「好友改私密/部分可见」的信号**，服务端完全相同；
   拦住它 ⇒ 两种情况你都继续看得见。用户已确认接受（`_r42_bit_paths.md` §6）。
2. **混合改写一律放行**（`8→2`、`10→130`、`10→6`）。代价是这些路径丢的位救不回来；
   收益是不会把动态按在「另一个位更差」的状态上。用户枚举里没有这些形态，因此不扩权。
3. **开关关闭时仍会为 `2`/`8` 写点查一次旧值**：开/关对照实验必须有同一套计数，
   否则「关的时候没看到」会被误读成「没发生」。频次是每次进主页几十次点查，可接受。
4. **批量 update（where 不是单值 snsId）不拦**：`snsIdOf` 只认「长度 1 且能 parseLong 的数组」，
   抠不出就放行并记 `drop unknown`（首次会打一行告警）。宁可漏拦也不猜 id 改库。
5. **写 0 路径不查旧值也拦**（旧行为）：库里查不到时仍拦 `sourceType=0`，
   这是与 `drop` 路径相反的保守方向，刻意保留 —— 写 0 是「彻底消失」，漏了无法挽回。
6. `SnsBlockProbe` 里既有的取证参数（`SQL_SAMPLE_MAX=60`、`SQL_LOG_CHARS=1500`）**原样保留**，
   本次只在其上叠加计数与 `want` 字段。

---

## 8. 文件清单

新增：

- `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsBitLogic.kt`
- `wxgroup/app/src/test/java/com/lx/wxgroup/feature/SnsBitLogicTest.kt`
- 本报告 `docs/_r42_bit_block_report.md`

修改：

- `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsGuard.kt`
- `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsBlockProbe.kt`

未改动：`docs/_r42_bit_paths.md`、任何既有 docs。
（`docs/派发审计台账.md` 在工作区里本来就是未提交改动，是团队既有内容，本次未碰。）

[ALL-DONE]
