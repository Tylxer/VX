# R42 · bit3 写回实现记录（bitfix）

> 实现者：**bitfix**（改代码并落盘，不只给方案）。
> 范围严格限定：`SnsGuard.kt` / `SnsBitLogic.kt` / `SnsBlockProbe.kt` / `SnsBitLogicTest.kt` / 本文件。
> ⛔ 禁止且未做：adb、装包、commit、改其它功能、改 docs 之外任何非目标文件。
> 父 turn 可能随时被打断 ⇒ 每完成一个子步立即写盘并留 `[DONE-N]`，可从最近一个标记续跑。

## 0. 命题（抄自 team-lead，真机已定案，不重新推理）

朋友圈防撤回是「写时拦截」：`SnsGuard.snsBlockGuard` 挂在 `updateWithOnConflict` 的 before/after；
微信想把 `SnsInfo.sourceType` 写 0 或纯降位时，`applyBlock()` 把 `sourceType` 键从
`ContentValues` 里 `remove` 掉 ⇒ UPDATE 不再碰这一列 ⇒ 库里旧值保持不变。

- 位语义：`bit1=2` 时间线可见；`bit3=8`「被好友主页相册列表拉取过」的记账位。
- 相册查询：`sourceType IN (8,264,10,266,12,268,...)` —— **集合里每个值都含 bit3**。
- **缺口**：删除前用户从没进过该好友主页 ⇒ 那条动态从未被相册拉过 ⇒ 库里 `src=2`
  （只有 bit1）。拦下写库后 src 停在 2 ⇒ 时间线还在，但主页相册查询不命中 ⇒ 整条动态在主页消失。
- **已拍板修法**：在拦截成功的那一次写入里**不再只是摘键，而是把 `sourceType` 写回去**，
  值取「库里旧值补上 bit1 与 bit3」（典型 `2 → 10`）。
  ⛔ 不允许：事后补位 / 额外 UPDATE / 注入表 / 定时扫描（R42 已物理删除，见
  `docs/conclusions/moments.md` 顶部订正）。零额外 IO、无竞态是本方案的全部价值。

## 1. 公式推导（写在 [SnsBitLogic.keepAliveSource] 的 KDoc 里，代码注释同源）

记 `old` = 写库**前**库里的 `sourceType`（`SnsBlockProbe.Bits.src`，可能为 null），
`want` = 微信这次想写入的值（写 0 路径 `newValue==null` ⇒ want=0；降位路径 `newValue` = 2/8…），
`VISIBLE = BIT_TIMELINE or BIT_ALBUM = 2 or 8 = 10`。

### 1.1 写回值公式

```
keep(old) = old or VISIBLE        （= old | 10）
```

三条「为什么不是更简单的写法」：

1. **不是 `old or 8`（只补 bit3）**：`old=8`（只在相册、时间线已不可见）时 `8|8=8`，
   bit1 仍缺 ⇒ 时间线照样丢。拦截的语义是「保住两个可见位」，所以 bit1 也要补 ⇒ `8|10=10`。
2. **不是常量 `10`**：`old` 可能带高位 —— `130=128|2`（折叠）、`264=8|256`、`266=8|256|2`、
   `478=...`。写死 10 会把这些位**抹掉**，等于替微信改了动态性质（折叠变普通、高位标记丢失），
   而且 `264 → 10` 反而把已经含 bit3 的高位值降成低位值。`old|10` 保留 old 的全部位，只补齐缺的。
3. **`want` 绝不参与公式**：`want` 是微信要清位的值，采用它的任何一位都等于替微信清位。
   写回值完全由库里旧值决定。

### 1.2 两个必须返回 null（= 降级为「只摘键」，与 R42 现状逐字一致）的边界

1. **`old == null`（bits 读不到：无 snsId / 无句柄 / 无此行）**：没有旧值就没有可补的基，
   凭空造 `10` 等于**编造可见性**。⇒ 降级摘键，绝不凭空造位（硬约束 2）。
2. **`old & VISIBLE == 0`（「彻底不可见」）**：如 `0`（死来源 `isDeadSource()`）、
   `4`（私密）、`32`（广告）、`128`（折叠且无 bit1）。这些动态本来时间线和主页都查不到；
   写 `old|10` 会把它变成可见 = **把一条已经彻底不可见的动态复活**。⇒ 不写回，保持摘键，
   库里留原值，不制造可见性。

⚠️ 注意「彻底不可见」判的是**两个可见位都没有**，不是「没有 bit3」：
`old=2`（只有 bit1，正是缺口场景）`2&10=2≠0` ⇒ **必须**写回 ⇒ `2|10=10`；
`old=8`（只有 bit3）`8&10=8≠0` ⇒ 写回 `8|10=10`。只有两位皆无才不写回。

### 1.3 四种典型 src 的结果（team-lead 点名要覆盖）

| `old` | `old & 10` | `keep` | 说明 |
|---|---|---|---|
| `0` | 0 | **null（不写回）** | 死来源，不复活 |
| `2` | 2 | **`10`** | ⭐ 缺口场景：时间线在、补 bit3 让主页也在 |
| `8` | 8 | **`10`** | 只在相册，补回 bit1 让时间线也在 |
| `10` | 10 | **`10`** | 已可见，写回是幂等 no-op |
| `4` / `32` / `128` | 0 | null | 彻底不可见，不复活 |
| `130` / `264` | 2 / 8 | `138` / `266` | 保留高位（1 的关键） |
| `null` | — | null | bits 读不到，降级 |

### 1.4 幂等 / 不会越写越大（设计文档 §4⑤）

写回值 `keep` 一定含 `VISIBLE`，且是 `old` 的超集。微信重试删除再次命中时
`old' = keep`，`keep(old') = keep or VISIBLE = keep` ⇒ **值收敛**，不会累积、不会越写越大。

### 1.5 已知代价 + 高位 old 的闭包复核

「所有被拦下的动态都进相册」是用户拍板方案时接受的代价；「只补本来该在相册里的那些」
需要服务端返回集合或额外 IO，与「零额外 IO、无竞态」冲突，不做。

✅ **`old|10` 对带高位 old 是否落在相册 64 值枚举的风险，已由 team-lead 用真机分布关闭**
（交叉复核结论，**不是本轮实测**，原 §1.5 的「真机未验证」判定作废）：
- 相册 64 值枚举里 **`138` 在**；
- 全库 **262,374 行**的 **17 种真实 `sourceType`** 中，
  `2 / 8 / 10 / 130 / 136 / 138 / 22 / 6 / 86 / 70 / 12` 写回后**全部落在枚举内**；
  `0 / 128 / 20 / 84 / 4 / 68` 由 `old & 10 == 0` 边界走**降级只摘键**（不写回、不产生新值）。
⇒ 本轮不再把这条列为「未验证风险」（§6.6 相应删去）；但「真机上界面是否真的两处都可见」
仍需下一轮按 §6.5 验收。

## 2. after 判据：为什么**不需要**改造链路，却仍保留互斥自证维度

### 2.0 前提更正（team-lead 交叉复核，本节以此为准）

`isBlockedValues(cv)` / `blockedCvs` 是**全项目零调用点的死代码**，`onSnsUpdateAfter()`
是空函数；旧 KDoc（`SnsGuard.kt:218`）与 `docs/_r42_block_impl.md` §2 偏差 2 描述的
「读不到 sourceType ⇒ 甲已生效」形态观察**在 HEAD 里并不存在**。真正的 after 校验链路是：

```
SnsGuard.onSnsUpdateBlockAfter() → SnsBlockProbe.hasPending() → finishAfterWrite { readBits(db, id) }
```

`pending` 是 **ThreadLocal 事件对象**，与 ContentValues 里有没有 `sourceType` 键**完全无关**
⇒ **把值写回 ContentValues 不会让 after 判据失效**，不需要「修好」任何被写回破坏的东西。

所以链路本身**不需要改造**。本轮保留 [DONE-2] 已落盘的 `AfterShape`，理由是它是**额外的互斥
自证维度**（见 2.1），并顺带订正两处过时口径（team-lead 必改）：
- `SnsGuard.kt:218` 的 KDoc → 改成上述事实（已落盘）；
- `finishAfterWrite` 旧日志的「(键已摘)」→ 写回路径下是假话，改成 `act=writeback|remove`。

### 2.1 保留的互斥形态判据（额外正交维度，不碰 afterOk/afterFail）

判据（纯函数 `SnsBitLogic.afterShape`，可由 JVM 单测锁住）：
输入 `want`（微信原值）、`keep`（本次写回值，null = 没走写回）、
`observed`（after 时从 `ContentValues` 实际读到的 `sourceType`，null = 键读不到）。

| 条件 | 结论 | 类别 |
|---|---|---|
| `observed == null` | `BLOCKED_BY_REMOVE` | **拦住了**（降级/不复活路径，键被摘掉） |
| `keep != null && keep != want && observed == keep` | `BLOCKED_BY_REWRITE` | **拦住了**（写回路径生效） |
| `observed == want` | `NOT_BLOCKED` | **没拦住**（ContentValues 仍是微信原值，我们没动） |
| 其余 | `UNKNOWN` | 形态异常，**单独告警**，不归入任一边 |

**互斥性证明**（四类两两不相交）：
- `observed == null` 与其余三类（都要求非 null）天然互斥；
- 第 2、3 类同时成立需 `observed == keep == want`，而第 2 类已显式要求 `keep != want` ⇒ 互斥；
- 第 4 类是前三者的补集 ⇒ 互斥。
- 「拦住了」= {1,2}，「没拦住」= {3}，{4} 显式告警 ⇒ 不误报（`NOT_BLOCKED` 由
  `shapeNotBlocked` 计数 + `BLOCK after MISMATCH` 告警抓出）、不漏报（`UNKNOWN` 不静默吞）。
- `finishAfterWrite` 只在 `pending` 存在（before 明确记了 `Outcome.BLOCKED`）时才跑，
  所以 `NOT_BLOCKED` 是**防御性守门**：若 applyBlock 记了 BLOCKED 却没真改 ContentValues，
  after 会读到 `want` 并判「没拦住」。
- ⛔ **两个维度正交**（交叉复核必改 1）：`afterOk`/`afterFail` 严格保持 HEAD 旧语义
  （只由 `bits != null` 决定，自增条件与位置不变）；形态分类走
  `shapeRemoved / shapeRewritten / shapeNotBlocked / shapeUnknown` 四个**新**计数器独立表达，
  谁都不覆盖谁。「回查成功」与「形态判定」是两个独立可读的判据。

## 3. 硬约束的落点（原 6 条 + 交叉复核追加 2 条，逐条在 §6.3 复核）

1. `cfg().blockEnabled == false` 一个字节不写：`applyBlock` 第一行即 `record(OFF); return`，
   keep 计算 / put / remove 全部在其后；`readBits` 是只读查询且旧代码在关开关时也做。
2. `bits == null` ⇒ `keepAliveSource(null) == null` ⇒ 走「只摘键」降级分支。
3. after 判据：**链路本身不需要改造**（`pending` 与 cv 有无键无关，见 §2.0）；
   订正 `SnsGuard.kt:218` 过时 KDoc 与 `finishAfterWrite` 旧日志「(键已摘)」；
   保留 `afterShape` 作额外互斥自证维度，四个新计数器与 `afterOk/afterFail` 正交；
   删掉零调用点的 `isBlockedValues`/`blockedCvs` 死代码。
4. SKIP_EMPTY：写回路径**不 remove**（只 put 覆盖）⇒ `size()==0` 不可能发生 ⇒ 该分支在
   写回路径**消失**；降级路径仍只摘键 ⇒ **仍需保留**（不是死代码），且放回失败要告警（不吞）。
5. 方案 B（`onSnsSql`/`applySqlRewrite`，`& -9 → -1`）一行未动；
   `SnsDeletedTag.onBlocked(id, bits, want)` 仍在 BLOCKED 分支调用、参数不变；
   `sqlBlockEnabled` / `blockEnabled` 的反向消融开关逻辑一行未动。
6. 新增纯判据单测 + 变异验证（见 [DONE-3] / [DONE-7]），全量单测必须通过。
7. **独立 off 语义调试开关**（team-lead 追加）：`Config.albumFixEnabled` +
   `antirevoke.sns_album_fix_off`（`Kv.getBool` 未设置即 false ⇒ 默认开启补位）；
   关掉只停补位、A/B 两层不动，供反向对照**单变量**实验（读数见 §6.5）。
8. **计数语义不许动**（team-lead 底线）：`hit / blocked / dropSeen / dropBlocked /
   afterOk / afterFail / sqlClear9 / sqlBlock9 / blkOff` 等全部计数器名字与自增条件保持原样；
   `Event.line()` 只增 `keep=` / `shape=` 字段、不删字段。

## 4. 子步进度（从最近的 [DONE-N] 续跑）

- **[DONE-1]** 公式推导、边界、after 互斥判据、六条约束落点 —— 本节 0~3 已写盘。
- **[DONE-2]** `SnsBitLogic.kt`：新增 `VISIBLE_MASK`（=10，KDoc 说明为何不用 `or` 表达式进 const）、
  `keepAliveSource(old: Int?): Int?`、`AfterShape` 枚举、`afterShape(want, keep, observed)`。
  全部是纯函数，零 Android/Xposed 依赖。
- **[DONE-3]** `SnsBitLogicTest.kt`：新增 **15** 条用例（写回公式 9 条 + after 互斥判据 6 条）。
  局部实测 `gradlew testDebugUnitTest --tests ...SnsBitLogicTest --rerun-tasks -q` exit=0，
  报告 XML：`tests="35" failures="0" errors="0"（原 20 + 新 15）。
- **[DONE-4]** `SnsBlockProbe.kt`：
  - 删除 `blockedCvs` / `rememberBlockedCv` / `isBlockedValues`（旧「读不到键 ⇒ 甲生效」身份集合，
    已无调用点、写回后语义失效 ⇒ 不留死代码）；
  - `Event` 增 `wantInt` / `keep` / `afterShape`，`line()` 增 `keep=` `shape=`；
  - `record()` 增 `keep: Int? = null` 参数，BLOCKED 分支不再 `rememberBlockedCv`；
  - `finishAfterWrite(observedSrc, reader)` 改用 [SnsBitLogic.afterShape] 互斥分类：
    {REMOVE, REWRITE}⇒`afterOk`、{NOT_BLOCKED, UNKNOWN}⇒`afterFail`+告警；
    新增 `shapeRemoved/Rewritten/NotBlocked/Unknown` 四个计数并进 `blockstat` 第 2 行。
- **[DONE-5]** `SnsGuard.kt`：
  - `applyBlock` 改双路径：**写回路径**只 `put` 覆盖（不 remove ⇒ 不会空 ⇒ SKIP_EMPTY 不可达）；
    **降级路径**（bits 读不到 / old 彻底不可见 / put 不可用或抛异常）与 R42 现状逐字一致
    （只 remove + 保留 SKIP_EMPTY + 放回失败告警）；
  - `onSnsUpdateBlockAfter` 读 cv 现值传 `finishAfterWrite(observed)`；
  - 类注释 / `snsUpdateGuard` / `snsBlockGuard` / `applyBlock` 的 KDoc 全部同步（不再说「只摘键」）；
  - 开关关闭仍在第一行 `record(OFF); return`；`SnsDeletedTag.onBlocked(id, bits, want)` 参数不变。
- **[DONE-6]** 全量实测：`assembleDebug -q` exit=0；
  `testDebugUnitTest --rerun-tasks -q` exit=0，**41 个测试类 / 800 用例 / 0 failures / 0 errors**
  （本轮新增 15 条）。用 XML `<testsuite>` 行正则统计，确认不是 0 用例假绿。
- **[DONE-7]** 变异验证 6/6 语义变异变红 + 1 个等价变异保持绿，还原 sha 一致、全量重跑全绿（§5）。
- **[DONE-8]** 最终报告（§6）已写盘并发给 team-lead。

## 4b. 交叉复核修订（team-lead 后续 5 条消息，**以最后一条为准**）

> 三条关键更正：① `isBlockedValues`/`blockedCvs` 是零调用点死代码，`onSnsUpdateAfter` 是空函数，
> 真正 after 链路是 `hasPending()`→`finishAfterWrite{readBits}`，与 cv 里有没有键**无关** ⇒
> **保留现有 AfterShape 实现不回退**；② `afterOk/afterFail` 必须还原 HEAD 旧语义；
> ③ 必须补独立 off 语义调试开关 `antirevoke.sns_album_fix_off`。

- **[DONE-9]** `SnsBlockProbe.finishAfterWrite`：`afterOk`/`afterFail` **还原 HEAD 旧语义**
  （只由 `bits != null` 决定，自增条件与位置不变）；shape 四分类改为
  `shapeRemoved/Rewritten/NotBlocked/Unknown` 四个**新**计数器的**正交独立维度**，
  绝不覆盖前者；旧日志「(键已摘)」订正为 `act=writeback|remove` + `shape=`；
  计数注释 / `pending` KDoc / 函数 KDoc 同步。
- **[DONE-10]** `SnsGuard.kt`：
  - ⚠️ 发现文件里已被并发预置了 `Config.albumFixEnabled` 字段 + KDoc + `Config(false,false,true)`，
    但 `refresh()` 没传该参数 ⇒ **当前编译不过**。保留预置内容并补齐接线（不删别人的工作）：
    新增 `ALBUM_FIX_OFF_KEY = "antirevoke.sns_album_fix_off"`（off 语义、`Kv.getBool` 未设置即
    false ⇒ 默认补位开启、不登记 PrefSpec、注释写明「正式开关仍是 `antirevoke.sns`」）；
    `refresh()` 补 `albumFixEnabled = !Kv.getBool(ALBUM_FIX_OFF_KEY)`；
    `applyBlock` 的 keep 改为 `if (cfg().albumFixEnabled) keepAliveSource(bits?.src) else null`；
    `stat()` 补 `albumFix=<true|false>` 便于真机确认开关读回；
  - `snsBlockGuard` 过时 KDoc（原 `SnsGuard.kt:218`）订正为 team-lead 的事实：
    `isBlockedValues`/`blockedCvs` 零调用点、`onSnsUpdateAfter` 空函数、真正链路是
    `hasPending()`→`finishAfterWrite{readBits}`、与 cv 有无键无关；
  - `applyBlock` KDoc 补 albumFix 降级条件与「A/B 自变量唯一」说明。
- **[DONE-11]** `SnsBitLogic.kt`：`AfterShape` 枚举与 `afterShape()` 的 KDoc 订正 ——
  写明「写回不会破坏 after 链路（`pending` 是 ThreadLocal）」、这是**额外自证维度**、
  ⛔ 全程不碰 `afterOk`/`afterFail`，`NOT_BLOCKED` 由 `shapeNotBlocked` + MISMATCH 告警抓出。
  **纯函数体一字未改**，公式/边界/互斥判据与单测结论不变。
- **[DONE-12]** 报告修订完成：§1.5 改成「已用真机分布关闭」（含 262,374 行 / 17 种值明细）、
  §2 重写为 2.0 前提更正 + 2.1 保留的互斥自证维度、§3 约束 3 订正 + 追加 7/8 两条、
  §5 补重跑结果、§6.2 补 albumFix/日志订正/KDoc、§6.3 重写约束 3 + 补 7/8 复核、
  §6.5 加两组反向对照读数 + 真机基线 + `op=sql` 用法、§6.6 移除已关闭风险 + 补开关未验证项、
  §6.7 加独立开关秒级回滚、§6.1 补「文件清单不变」说明。
- **[DONE-13]** 交叉复核修订后**整套重跑实测**：
  `assembleDebug -q` exit=0；`testDebugUnitTest --rerun-tasks -q` exit=0
  ⇒ **41 类 / 800 用例 / 0 failures / 0 errors**；
  变异验证 7 组读数与首轮**逐字一致**（M0 等价对照绿；M1~M6 分别 4/3/2/1/1/1 条变红）；
  还原 `sha_equal=True`、还原后全量 `rerun_exit=0 / 41 类 / 800 用例 / 0 failures`。
  最终报告已重新发给 team-lead。

> 环境坑实记：PowerShell 工具**拒绝 `cmd.exe`**（`Command blocked for security`），所以
> `cmd //c "gradlew.bat ..."` 那条不能照抄；改用原生
> `Push-Location <wxgroup>; & '.\gradlew.bat' <args>; Pop-Location`，
> 并先收窄 `$env:PATH`（JDK21 + System32 + Windows）。局部单测 exit=0 且用例数非 0，
> 确认不是「0 用例假绿」。后续全量/变异沿用这一套。

## 5. [DONE-7] 变异验证结果

方法：PowerShell inline 脚本，每个变异都从**同一份原始源码**独立做字符串替换 →
`gradlew testDebugUnitTest --tests ...SnsBitLogicTest --rerun-tasks -q` → 读 XML 的
`failures/errors` → 还原。**不新建任何脚本文件**（落在允许列表之外），全程临时改/还原。

| 变异 | 改动 | 语义（为什么**非等价**） | 结果 |
|---|---|---|---|
| **M0** | 只把一条 KDoc 注释加几个字 | **等价变异（对照组）**：代码一字未动 | **GREEN** `exit=0 failures=0` ✅ 证明「变红」不是随便改都会发生，脚本能区分等价变异 |
| M1 | `old or VISIBLE_MASK` → `old or BIT_ALBUM`（只补 8） | `old=8` 时漏补 bit1 ⇒ 时间线仍丢；`264→264` 高位型也错 | **RED** `exit=1 failures=4` |
| M2 | 同上 → `VISIBLE_MASK`（常量 10） | 抹掉 `130/264` 的高位 ⇒ 替微信改了动态性质 | **RED** `exit=1 failures=3` |
| M3 | 删掉 `if ((old and VISIBLE_MASK) == 0) return null` | 把 `0/4/32/128` 这类彻底不可见动态复活 | **RED** `exit=1 failures=2` |
| M4 | `if (old == null) return VISIBLE_MASK` | bits 读不到时凭空造位（硬约束 2 破） | **RED** `exit=1 failures=1` |
| M5 | `afterShape` 第 2 类去掉 `keep != want` | 互斥性破：`keep==want` 会同时算「拦住」和「没拦住」 | **RED** `exit=1 failures=1` |
| M6 | `observed == null -> NOT_BLOCKED` | 降级摘键路径被全部误判为没拦住（漏报） | **RED** `exit=1 failures=1` |

- **6/6 语义变异变红，1/1 等价变异保持绿**。每个语义变异都对应一个真实错误方向
  （漏补位 / 抹高位 / 复活 / 编造 / 互斥破 / 漏报），不是等价改写凑数。
- 还原后 `sha256` 与当次原始一致，重跑局部单测 `exit=0`；
  再跑**全量** `testDebugUnitTest --rerun-tasks -q` ⇒ `41 类 / 800 用例 / 0 failures / 0 errors`。
- **[DONE-13] 交叉复核修订后整套重跑**：`assembleDebug -q` exit=0、全量 `800/0`；
  7 组变异读数**逐字一致**（M0 绿；M1~M6 分别 4/3/2/1/1/1 条变红）；
  还原 `sha_equal=True`、还原后全量 `rerun_exit=0 / 41 类 / 800 用例 / 0 failures / 0 errors`。
  [DONE-11] 只订正 KDoc、纯函数体一字未改，故公式与互斥判据结论不变。

## 6. [DONE-8] 最终报告

### 6.1 改动文件清单（**仅 5 个，全部在允许列表内**）

| # | 文件 | 性质 |
|---|---|---|
| 1 | `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsBitLogic.kt` | 纯判据 |
| 2 | `wxgroup/app/src/test/java/com/lx/wxgroup/feature/SnsBitLogicTest.kt` | 单测 |
| 3 | `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsBlockProbe.kt` | 账本 / after 形态判据 |
| 4 | `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsGuard.kt` | `applyBlock` / after 钩子 / KDoc |
| 5 | `docs/_r42_bit3_impl.md` | 本实现报告 |

⛔ **本轮未改动**：`SnsDeletedTag.kt` / `SnsDeletedTagLogic.kt` / `SnsSqlRewrite.kt` / `SnsSql.kt` /
`Keys.kt` / `EntryReceiver.kt` / 任何 gradle 配置 / 任何资源文件 / 其它 docs / 任何数据库 schema。
⚠️ `git status --short` 里还能看到 `HookEntry.kt` / `AvatarLoader.kt` / `GroupDb.kt` /
`Keys.kt` / `EntryReceiver.kt` / `SnsViewProbe.kt` / `docs/_r42_bit_paths.md` 等 `M` 条目与
`docs/_r42_bit3_design.md` / `docs/_r42_bit3_verify.md` / `_mut*.py` 等 `??` 条目 ——
这些是**之前轮次或其他 teammate 的既有状态**，本轮一个字节都没碰（用工具调用记录核对：
本轮仅对上述 5 个文件执行过 Write/Edit）。
⚠️ 交叉复核修订（[DONE-9]~[DONE-11]）仍只动这 5 个文件里的 3 个
（`SnsGuard` / `SnsBlockProbe` / `SnsBitLogic`），文件清单不变；`Keys.kt` 未加常量
（调试键用 `SnsGuard` 内的 private const）、`EntryReceiver.kt` 未改。
⛔ **未做**：adb、装包、commit、真机、改其它功能。

### 6.2 diff 要点（关键代码位置）

**`SnsBitLogic.kt`**
- `VISIBLE_MASK = 10`（KDoc 说明为何 const 里不写 `or` 表达式，单测断言 `== 2|8`）。
- `keepAliveSource(old: Int?): Int?` ≈ `:115`：公式 `old or VISIBLE_MASK`，两条 null 边界
  （`old==null`、`old & VISIBLE_MASK == 0`），KDoc 写全 1.1~1.4 的推导与幂等性。
- `AfterShape` 枚举 + `afterShape(want, keep, observed)` ≈ `:145`：四分互斥判据。

**`SnsBitLogicTest.kt`**：+15 条（`keep_alive_*` 9 条 / `after_shape_*` 6 条），含
`keep_alive_typical_four_values`、`keep_alive_does_not_resurrect_fully_invisible_values`、
`keep_alive_preserves_high_bits`、`after_shape_keep_equals_want_is_not_rewritten`、
`after_shape_is_mutually_exclusive_and_total`。局部 35 条全绿。

**`SnsBlockProbe.kt`**
- 删 `blockedCvs` / `rememberBlockedCv` / `isBlockedValues`（旧「读不到键 ⇒ 甲生效」死代码）。
- `Event` 加 `wantInt` / `keep` / `afterShape`；`line()` 加 `keep=` `shape=`（真机可见）。
- `record()` 加 `keep: Int? = null`；BLOCKED 分支只 `pending.set(e)`。
- `finishAfterWrite(observedSrc: Int?, reader)` ≈ `:372`：**两个正交维度**——
  ① `afterOk`/`afterFail` 严格 HEAD 旧语义（只由 `bits != null` 决定，自增条件/位置不变）；
  ② `afterShape` 四分法走 `shapeRemoved/Rewritten/NotBlocked/Unknown` 四个**新**计数器
  （`blockstat` 第 2 行 `shape=rm:.. rw:.. nb:.. un:..`），`NOT_BLOCKED`/`UNKNOWN` 额外告警；
  旧日志「(键已摘)」订正为 `act=writeback|remove` + `shape=`，`Event.line()` 的 `shape=` 保留。

**`SnsGuard.kt`**
- `applyBlock` ≈ `:418`：新增**写回路径**（`keepAliveSource(bits?.src)` 非 null ⇒ 只
  `put(values,"sourceType",keep)`、**不 remove**）与**降级路径**（keep==null ⇒ 与 R42 现状
  逐字一致的 remove + SKIP_EMPTY）。`SnsDeletedTag.onBlocked(id, bits, want)` 两处调用、参数不变。
- `onSnsUpdateBlockAfter` ≈ `:520`：从 `param.args[1]` 读 `sourceType` 现值传
  `finishAfterWrite(observed)`。
- `ALBUM_FIX_OFF_KEY = "antirevoke.sns_album_fix_off"` + `Config.albumFixEnabled` +
  `refresh()` off 语义读键（`!Kv.getBool(...)`，默认开启补位）+
  `applyBlock` 按 `cfg().albumFixEnabled` 决定 keep +
  `stat()` 显示 `albumFix=<true|false>`（交叉复核必改 2）。
  ⚠️ `Config` 字段/KDoc/`Config(false,false,true)` 是**并发预置的半成品**（`refresh()` 没传参、
  当时编译不过），本轮保留并补齐接线，未删别人的工作。
- 类注释 / `snsUpdateGuard` / `snsBlockGuard` / `applyBlock` 四处 KDoc 同步：
  删「只摘键」与「读不到键 ⇒ 甲生效」过时口径，写明真正 after 链路与 A/B 自变量唯一。

### 6.3 六条硬约束逐条复核（怎么满足）

1. **开关关闭一个字节不写**：`applyBlock` 在 `val want = newValue ?: 0`（纯局部变量）之后
   第一行就是 `if (!cfg().blockEnabled) { record(OFF); return }`；`keepAliveSource` / `put` /
   `remove` / `size` 全部在其后 ⇒ OFF 路径**不触碰 ContentValues、不写库**。
   `onSnsUpdateBefore` 里的 `readBits` 是只读 `rawQuery`，且旧代码在关开关时同样执行 ⇒ 无回归。
2. **`bits == null` 降级**：`keepAliveSource(null) == null` ⇒ `applyBlock` 走降级只摘键分支，
   与 R42 现状逐字一致；绝不凭空造位。单测 `keep_alive_degrades_when_bits_unreadable` 锁住。
3. **after 判据（前提更正后）**：链路本身**不需要改造** —— `pending` 是 ThreadLocal 事件对象，
   `hasPending()`→`finishAfterWrite{readBits}` 与 cv 有无 `sourceType` 键完全无关，写回不会让它
   失效（§2.0）。本轮实际做的是：
   - 订正 `SnsGuard.kt:218` 过时 KDoc 与 `finishAfterWrite` 旧日志「(键已摘)」→
     `act=writeback|remove` + `shape=`（**日志不说假话**）；
   - 保留 `afterShape` 作**额外互斥自证维度**（四类互斥证明见 §2.1）：
     「拦住了」={BLOCKED_BY_REMOVE, BLOCKED_BY_REWRITE}、「没拦住」={NOT_BLOCKED}、
     {UNKNOWN} 单列告警 ⇒ 不误报、不漏报；`NOT_BLOCKED` 是「记了 BLOCKED 却没真改 cv」的
     防御性守门；
   - ⛔ **`afterOk`/`afterFail` 严格 HEAD 旧语义**（只由 `bits != null` 决定，自增条件与位置
     不变），形态分类走四个**新**计数器，两维度正交、谁都不覆盖谁（交叉复核必改 1）。
4. **SKIP_EMPTY 分支**：写回路径只 `put` 覆盖、不 `remove` ⇒ cv 恒有 `sourceType` 键 ⇒
   `size()==0` **不可能发生** ⇒ 该分支在这条路径**消失**（不是死代码，是不可达）；
   降级路径仍只摘键 ⇒ 分支**仍需保留**。放回失败新增
   `BLOCK skip: empty values 且放回失败…` 告警，⛔ 不吞异常。
5. **三者不回归**：
   - 方案 B：`onSnsSql` / `applySqlRewrite` / `SnsSqlRewrite` / `cfg().sqlBlockEnabled` **一行未动**；
   - 灰标：`SnsDeletedTag.onBlocked(id, bits, want)` 仍在两个 BLOCKED 分支调用、`want =
     newValue ?: 0` 与旧实现一致；`SnsDeletedTagLogic.shouldRecord` 只看 `bits.src` 与 `want`，
     与是否写回无关 ⇒ 行为不变；
   - 反向消融：`applyBlock` 第一行 OFF 分支 + `refresh()` 的开关逻辑未动 ⇒ 关开关后仍只记账。
6. **单测 + 变异**：新增 15 条纯判据用例；6 个语义变异全变红、1 个等价变异保持绿；
   全量 800 用例 0 失败（详见 §5 / [DONE-6] / [DONE-13]）。
7. **独立调试开关**（追加硬约束，反向对照自变量唯一）：`Config.albumFixEnabled` +
   `antirevoke.sns_album_fix_off`（off 语义，`Kv.getBool` 未设置即 false ⇒ 默认开启补位，
   不登记 PrefSpec；代码注释写明「这是 A/B 对照用的调试覆盖，正式开关仍是 `antirevoke.sns`」）。
   `applyBlock` 里 `if (cfg().albumFixEnabled) keepAliveSource(bits?.src) else null` ⇒
   关掉只停补位、A 层（写时拦截）与 B 层（`&-9→-1`）**完全不动**；
   `stat()` 显示 `albumFix=<true|false>` 供回读确认；`kvset` 已调 `refresh()` ⇒ 热切换生效，
   无需改 `EntryReceiver`。两组验收读数见 §6.5。
8. **计数语义不动**（team-lead 底线）：`hit / blocked / dropSeen / dropBlocked / afterOk /
   afterFail / sqlClear9 / sqlBlock9 / blkOff` 全部计数器名字与自增条件保持原样；只**新增**
   四个 shape 计数与 `Event.line()` 的 `keep=` / `shape=` 字段（不删字段）。它们仍是下一轮
   真机判断成败的唯一依据。

### 6.4 环境偏差（如实记录）

team-lead 给的 `cmd //c "gradlew.bat …"` 在本会话的 PowerShell 工具里被安全策略拒绝
（`Command blocked for security: cmd.exe cannot be used from the PowerShell tool`）。
改用等价的原生 PowerShell：`$env:PATH` 收窄为 `JDK21\bin;C:\Windows\System32;C:\Windows`，
`Push-Location <wxgroup>; & '.\gradlew.bat' <args>; Pop-Location`。
用例数从 XML `<testsuite>` 行正则统计确认非 0，避免「0 用例假绿」。

### 6.5 真机验证判据（**应该看到什么**，本轮未实测）

#### 6.5.0 取样基线（team-lead 已取样，同一好友 `wxid_xlrbelf6hv0w22` / 昵称 verity）

| snsId | 删除前 `sourceType` | 角色 |
|---|---|---|
| `-3397762123896778223` | `10`（删前进过主页，有 bit3） | 对照组，两组都应保持 10 |
| `-3397763077853081064` | `2`（删前没进主页，无 bit3） | ⭐ **缺口目标** |

只读查库探针（`op=sql`，SQL **只包一层双引号**，别再包单引号 —— 否则被 SQLX 白名单拒）：

```bash
am broadcast -a com.lx.wxgroup.action.PROBE --es op sql \
  --es q "select snsId,sourceType,type,createTime from SnsInfo where snsId in (-3397763077853081064,-3397762123896778223)"
```

#### 6.5.1 开补位（默认，`albumFix=true`）

1. `SNS stat` 回读 `blockEnabled=true sqlBlock=true albumFix=true`。
2. 事件行：`BLOCK ev … before=2 want=0 keep=10 blk=1 shape=BLOCKED_BY_REWRITE after=10`
   （`keep=10` 与 `shape=BLOCKED_BY_REWRITE` 是本轮新增字段）。
3. **`BLOCK after ... before=2 after=10 ... act=writeback shape=BLOCKED_BY_REWRITE`**，
   且 **`afterOk` 递增、`afterFail` 不涨**（`afterOk`/`afterFail` 保持 HEAD 旧语义 = 回查是否成功；
   日志不再写「(键已摘)」这句假话）。
4. `blockstat` 的 `shape` 段：`rw:` 增长、`nb:` 与 `un:` **恒 0**。
5. 库里 `src 2 → 10`（`op=sql` / `TRACE` 采样）——**修复缺口的核心证据**。
6. 界面：**时间线在、好友主页相册也在**（旧行为只有时间线在、主页整条消失）。

#### 6.5.2 关补位（反向对照，`albumFix=false`，**A/B 保留**）—— 两组读数必须互斥

```bash
op=kvset --es key antirevoke.sns_album_fix_off --es val true
op=kvget --es key antirevoke.sns_album_fix_off    # 回读复核
```

- `SNS stat` 回读 `blockEnabled=true sqlBlock=true albumFix=false`
  （**A 层写时拦截与 B 层 `&-9→-1` 仍开着**，自变量只有「补位」这一项）。
- 事件行 `keep=-`（无写回）、`shape=BLOCKED_BY_REMOVE`、`after=2`。
- **`BLOCK after ... before=2 after=2 ... act=remove`**，好友主页相册**查不到**。
- `shape` 段 `rm:` 增长、`rw:` 不增；`afterOk` 仍递增（回查成功，与形态无关）。
- 对照组 `-3397762123896778223`（本来 `src=10`）两组里都保持 `10` ⇒ 不回归。
- ⭐ 与 6.5.1 **互斥**：关 ⇒ `before=2 after=2` / 主页不出现；开 ⇒ `before=2 after=10` /
  主页出现。两组的 `after` 与「主页出现」必须一一对应，不能同时成立。

#### 6.5.3 主开关反向消融（另一维度，A/B 一起停）

关 `antirevoke.sns=false` 后重进主页 ⇒ `& -9` 与 ContentValues 写 2 都不再被拦 ⇒
`src 10 → 2`、**主页消失**（与 R42 报告 §8 消融口径一致）。⚠️ 这一组自变量是「三层全停」，
与 6.5.2 的「只停补位」不是同一个实验，别混用判据。

#### 6.5.4 未回归

`blkFail=0`（方案 B 正常）、`sqlBlock9` 随进主页增长、`blkOff` 在主开关关时增长；
灰标只在「丢 bit1」时记台账；`hit / blocked / dropSeen / dropBlocked / afterOk / afterFail`
计数口径与历史报告一致（本轮未改自增条件）。

### 6.6 ⛔ 没能验证的部分（不谎称跑过真机）

1. **本轮完全未装包、未 adb、未真机**。上面 6.5 全部是「应该看到」的判据，不是实测结果；
   `src 2→10` 是否真的落地、界面是否真的两处都可见、独立开关两组读数是否互斥，都需下一轮真机。
2. ✅ **已从本清单移除**：`old|10` 对带高位 old 是否落在相册 64 值枚举 —— team-lead 已用
   全库 **262,374 行 / 17 种真实 `sourceType`** 分布关闭（见 §1.5），不再是未验证风险。
3. wcdb 那份 `ContentValues` 的 `put(String, Integer)` 确切签名/可用性未真机验证；代码里有
   `api.putInt == null` 与 put 抛异常两条降级兜底，但这两条路径本身没实机跑过。
4. after 里 `intOfAny(raw)` 把「键被摘掉」与「键存在但值 parse 失败」都归为 null ⇒
   `BLOCKED_BY_REMOVE`；前提（before 已验证过同一 cv 的 `get` 与值类型）写进了 KDoc，但未压测。
5. 微信重试删除是否反复触发写回：公式幂等已由单测
   `keep_alive_is_idempotent_and_only_adds_bits` 锁住（`keepAliveSource(keep)==keep`），
   但真实重试频次 / 是否存在其它补偿写库通道未观测。
6. 「所有被拦下的动态都进相册列表」的产品代价是否可接受，需真机 + 用户确认。
7. 并发（多线程同时进主页）、`SnsDeletedTag` 灰标与方案 B 的联动，只做了静态复核，
   未做真机回归。
8. `albumFixEnabled` 调试键的 `op=kvset` 热切换（`refresh()` 3s TTL）与 `SNS stat` 的
   `albumFix=` 回读未真机复核；代码路径与「未登记 PrefSpec 不影响 `PrefsVisibilityTest`」
   只做了静态核对（该测试只遍历 `Prefs.ALL`）。

### 6.7 回滚路径（若真机发现主页重复/错序）

- **首选：只关补位（单变量回滚，A/B 保留）**：
  `op=kvset --es key antirevoke.sns_album_fix_off --es val true` ⇒ `albumFixEnabled=false`
  ⇒ `applyBlock` 的 keep 直接置 null ⇒ 只摘键，行为逐字回到 R42 现状，
  A（写时拦截）与 B（`&-9→-1`）照旧。这正是 §6.5.2 反向对照的操作，**不需要改代码**。
- **整体回滚**：关主开关 `antirevoke.sns=false` ⇒ `applyBlock` 第一行 OFF 分支生效，
  `keepAliveSource` 不执行、不写回，只记账（A/B 一起停）。
- **代码回滚**：`SnsGuard.applyBlock` 删掉写回路径（保留降级分支即可，等价于旧实现）；
  `Config.albumFixEnabled` / `ALBUM_FIX_OFF_KEY` / `stat()` 的 `albumFix=` 可一并删除；
  `SnsBitLogic.keepAliveSource` 可保留但无调用点；`SnsBlockProbe.finishAfterWrite` 若要完全
  回到旧口径，删掉 `afterShape` 形态维度（四个 shape 计数 + `Event.line()` 的 `keep=`/`shape=`）
  即可 —— ⚠️ `afterOk`/`afterFail` 本来就是 HEAD 旧语义，回滚时**不需要动**。

## 7. 交叉复核后最终状态（一眼看清）

| 项 | 状态 |
|---|---|
| 公式 `keep = old or 10` + 两条 null 边界 | ✅ 保留，纯函数体未改；15 条用例 + 6 个语义变异锁住 |
| after 链路 | ✅ **未改造**（`pending` 与 cv 有无键无关）；只订正过时 KDoc 与旧日志「(键已摘)」 |
| `afterOk` / `afterFail` | ✅ **严格 HEAD 旧语义**（只由 `bits != null` 决定，自增条件/位置不变） |
| 形态四分类 | ✅ 四个**新**计数器正交独立表达，`Event.line()` 只增字段不删 |
| 计数语义（hit/blocked/drop*/sql*/blkOff…） | ✅ 名字与自增条件**全部未动** |
| 独立调试开关 | ✅ `Config.albumFixEnabled` + `antirevoke.sns_album_fix_off`（off 语义、默认开启）、`applyBlock` 判定、`SNS stat` 显示 `albumFix=` |
| 写回 / 降级双路径、关开关零写入、SKIP_EMPTY | ✅ 保留 |
| 方案 B / 灰标 / 反向消融 | ✅ 未回归（静态复核，真机待验） |
| 全量构建 + 单测 + 变异 | ✅ `assembleDebug` exit=0、800/0、6 语义变异全红 + 1 等价对照绿、还原 sha 一致 |
| 闭包风险（高位 old 是否在相册枚举） | ✅ team-lead 已用真机分布关闭（§1.5），从「未验证」清单移除 |
| 真机 | ⛔ **本轮未跑**，判据见 §6.5（含独立开关两组互斥读数 + 真机基线） |

[ALL-DONE]

---

## 8. 收口声明（源码静止，装包前最终口径）

> 本节按 team-lead「收口确认」要求追加：⛔ 不再改源码、不再跑构建，只确认源码静止并补此标记。
> 自本节起源码进入**静止**状态，等待装包 / 真机验收（真机由主会话按
> `docs/_r42_bit3_verify.md` 执行，bitfix 不碰 adb）。
> ⚠️ **口径优先级（收口确认指定）**：涉及**真机验收判据 / 回滚路径 / 计数命名**，以
> `docs/_r42_bit3_verify.md` 与 `docs/_r42_bit3_design.md` 为准；本文件 §6.5 第 5 条、
> §6.6 第 2 条、§6.7 回滚路径是**修订前旧口径**，仅作实现留痕、不作装包验收依据
> （具体差异见 8.3 / 8.5 / 8.6）。

### 8.1 albumFix 独立调试开关：4 处接线的实际落地形态（逐行核过）

| # | 位置 | 现状 |
|---|---|---|
| 1 | `SnsGuard.kt:80` | `private const val ALBUM_FIX_OFF_KEY = "antirevoke.sns_album_fix_off"`（off 语义，沿用 `antirevoke.` 前缀） |
| 2 | `SnsGuard.kt:100` | `Config` 第三参数 `val albumFixEnabled: Boolean` |
| 3 | `SnsGuard.kt:174-181` | `refresh()`: `albumFixEnabled = !Kv.getBool(ALBUM_FIX_OFF_KEY)` —— **独立**于 `blockEnabled` / `sqlBlockEnabled`，⛔ **没有**挂 `block &&`；`Kv.getBool` 未设置即 false ⇒ 补位**默认开启** |
| 4 | `SnsGuard.kt:473` | `applyBlock`: `var keep = if (cfg().albumFixEnabled) SnsBitLogic.keepAliveSource(bits?.src) else null` ⇒ 关掉只停写回，A（写时拦截）与 B（`&-9→-1`）两层完全不动 |
| 5 | `SnsGuard.kt:1445-1447` | `stat()`: `albumFix=${cfg().albumFixEnabled}` ⇒ `op=snsg --es sub stat` 可回读（`verify.md §2 ②b` 的验收入口） |

- 两个 BLOCKED 分支仍都传 `cv = values`、`SnsDeletedTag.onBlocked(id, bits, want)` 参数不变；
  `kvset` 探针已调 `SnsGuard.refresh()` ⇒ 热切换生效，`EntryReceiver.kt` 未改。
- ✅ **2026-11-07 04:2x 已补**（原先留到真机验证后收口的三条非阻塞建议之一）：
  `install()` 日志现在带全四项，与 `stat()` 口径一致 ——
  `SNS installed hooks=... blockEnabled=... sqlBlock=... albumFix=...`。
  动机：`install()` 是「新包是否加载」的第一判据，只有 `blockEnabled=` 时无法从这一行
  确认补位与 SQL 层读回值，每次都要再发一次 `op=snsg sub=stat`。
  ⚠️ 旧文档/脚本里按 `SNS installed hooks=... blockEnabled=...` 精确匹配的 grep 仍能命中
  （新增字段在其后），但**逐字回显**的老日志（如 `_r42_verify.md §1`）属于历史记录，不追改。

### 8.2 刻意不登记 PrefSpec（最终取舍，勿反手登记）

`Keys.kt` **本轮未改**（`git status` 里的 `M Keys.kt` 属既有轮次，bitfix 一个字节没碰）；
调试键是 `SnsGuard.kt` 内 private const，**没有** `Keys.ANTIREVOKE_SNS_ALBUM_FIX_OFF`、
**没有** `Prefs.ALL` 的 `PrefSpec(...)` 登记。
- 理由：`PrefsVisibilityTest.keyPrefixMatchesDomainGrouping` 只遍历 `Prefs.ALL`，
  未登记的键不受域分组约束；登记反而引入不必要的可见性面。
- 口径演变：team-lead 早先一条消息曾要求「`Keys.kt` 增常量 + `Prefs.ALL` 登记」，
  但**最后一条「收口确认」明确认可并采纳「刻意不登记 PrefSpec」的取舍**（独立复核也判定正确、
  且明确警告不要反手登记）⇒ 以收口确认为准。
- ✅ `verify.md §6` 代码回滚清单**已由 team-lead 订正**：原先误列的
  `Keys.ANTIREVOKE_SNS_ALBUM_FIX_OFF（含 Prefs.ALL 登记）` 已删除，现按 8.1 的实际形态
  撤 `SnsGuard.ALBUM_FIX_OFF_KEY` + `Config.albumFixEnabled` 三参接线，并明确标注
  「⛔ 别去 `Keys.kt` / `Prefs.ALL` 找它」。四份文档的回滚口径现已一致。

### 8.3 反向对照：自变量唯一，只用独立开关

- 本轮补位方案的反向对照**只用** `antirevoke.sns_album_fix_off`（`op=kvset --es val true`，
  写完必须 `kvget` 回读），关掉后 `albumFix=false` 而 `blockEnabled/sqlBlock` 仍 `true`。
  两组互斥读数：开 ⇒ `before=2 after=10` / `act=writeback` / `shape=BLOCKED_BY_REWRITE` /
  主页出现；关 ⇒ `before=2 after=2` / `act=remove` / `shape=BLOCKED_BY_REMOVE` / 主页不出现
  （详见 `verify.md §3 / §4`）。
- ⛔ **不要**用主开关 `antirevoke.sns` 做本轮反向对照：一关就把 A（摘键）和 B（`&-9→-1`）
  一起停掉，自变量不唯一，证明不了「主页出现」是补位带来的。
  本文件 §6.5.3「主开关反向消融」是 R42 **既有方案的另一维度**实验，不是 bit3 的反向对照判据。
- ⭐ 重做方案 B 反向消融（`docs/_r42_bit_b_report.md §8` 的
  `sqlClear9↑ + sqlBlock9 不变 + blkOff↑ + src 10→2`）之前**必须先停写回**
  （`antirevoke.sns_album_fix_off=true`，见 `verify.md §5`）：否则微信 `& -9` 把 `10` 削成 `2`
  后，紧随的 `want=0` 仍被甲拦下又写回成 `10`，界面「主页没消失」会被误判成
  「B 层不必要 / 写回自愈了缺口」。

### 8.4 闭包风险：已关闭（非未验证）

`old|10` 对带高位 old 是否落在相册 64 值枚举 —— team-lead 已用真机数据关闭
（`verify.md §5`、本文件 §1.5）：64 值枚举里 `138` 在；全库 262,374 行 / 17 种真实
`sourceType` 中 `2/8/10/130/136/138/22/6/86/70/12` 写回后全在枚举内，
`0/128/20/84/4/68` 由 `old & 10 == 0` 走降级不写回。本轮不再列为「未验证风险」。

### 8.5 回滚优先级与「数据消退」顺序（以 `verify.md §6` 为准，§6.7 作废）

- **止血（秒级，优先）**：`antirevoke.sns_album_fix_off=true` ⇒ 只摘键、A/B 不动，单变量回滚。
  主开关 `antirevoke.sns=false` 是兜底（一次停三层，不适合单变量对照）。
- ⚠️ **数据不能只靠开关回滚**：写回成 `10` 的行会长期留在相册枚举里。要让它自然消退，
  顺序必须是 **先停写回 → 再让微信自己的 `& -9` 执行 → 等微信对账收缩**；
  反过来（先关 B、写回还开着）等于「清了又补」，白做。模块不提供带外人工修库。
- 代码回滚按 8.1 实际形态撤，勿按 8.2 末尾所指的旧清单去 `Keys.kt` / `Prefs.ALL`。

### 8.6 计数/日志命名：验收取证以 `shape=` 为准（与 bitchk 对齐，语义一一对应）

`docs/_r42_bit3_design.md §3.3` 用的是**建议命名** `wbOk / wbRemoveOk / wbLost / wbMismatch`、
日志 `(写回)/(摘键·未补位)/(回查失败)`、`BLOCK ev ... wb=`；
bitfix 落地的是 `blockstat` 第 2 行 `shape=rm:.. rw:.. nb:.. un:..`、
`shape=BLOCKED_BY_REWRITE|BLOCKED_BY_REMOVE|NOT_BLOCKED|UNKNOWN`、`keep= / act= / shape=`。
映射（语义一一对应，不影响结论）：

| 落地计数 / 形态 | design.md 建议名 | 含义 |
|---|---|---|
| `rw` / `BLOCKED_BY_REWRITE` | `wbOk` | R 拦住并补位（写回生效） |
| `rm` / `BLOCKED_BY_REMOVE` | `wbRemoveOk` | S 摘键降级 |
| `nb` / `NOT_BLOCKED` | `wbLost` | F 没拦住 |
| `un` / `UNKNOWN` | `wbMismatch` | F 形态异常 |

⛔ **验收者若按 design.md 的 `wb*` 计数名去 `blockstat` 取证会查空**，请以
`shape=rm/rw/nb/un` 为准（正向 `rw:` 增长、`nb:/un:` 恒 0；反向 `rm:` 增长、`rw:` 不增）。
✅ **已裁决并落地**：team-lead 授权 bitchk 在 `design.md:368` 补了
「命名对照表（⛔ 取证前必读）」——8 行映射 + 明确「真机取证一律以 `verify.md` 与
`blockstat` 实际字段为准，左列是设计期命名、不是日志里能 grep 到的字符串」，
bitchk `[DONE-6]` 完成；本节 8.6 表格与之一致。
其余一致部分（albumFix off 语义、`SNS stat` 回读、关它只停补位 A/B 照旧、`nb:/un:` 恒 0、
两组互斥读数、基线 `-3397763077853081064`(src 2) / `-3397762123896778223`(src 10)、
`op=sql` 只包一层双引号、`Config.albumFixEnabled` 回滚、`old|10` 且 `bits==null` 或
`old&10==0` 降级、`afterOk/afterFail` 仍是 HEAD 旧语义只记回查成败）经与 bitchk 对齐，无冲突。

### 8.7 源码静止确认（收口实测）

- `SnsBitLogic.kt:123` = `if (old == null) return null`（M4 变异已还原）；
  `wxgroup/app/src` 全量 `MUTATED|return VISIBLE_MASK` **零命中**。
  全仓 `MUTATED` 命中仅落在 `docs/_r42_bit3_review.md`（bitreview 的历史复核/误报复盘文字），
  **不是源码变异残留**。
- `SnsBlockProbe.kt`：`afterFail.incrementAndGet()` 唯一在 `:398`、
  `afterOk.incrementAndGet()` 唯一在 `:407`（HEAD 旧语义，只由 `bits != null` 决定）；
  四个 shape 计数在 `:420+` 正交独立，不触碰前两者。
- `git status --short`：本轮允许改动的 5 个文件
  （`SnsGuard.kt` / `SnsBitLogic.kt` / `SnsBlockProbe.kt` / `SnsBitLogicTest.kt` /
  `docs/_r42_bit3_impl.md`）均在列；其余 `M` / `??` 条目为既有轮次或其他 teammate 状态，
  bitfix 未碰。
- **在跑进程**：`Get-Process` 过滤 `java|gradle|python|kotlin|adb` ⇒ **零命中**，
  ⛔ 当前**没有**任何 gradle 构建 / 变异脚本 / adb 在跑。
- **源码静止**：自本节起不再改源码、不再跑构建、不装包、不 adb、不 commit，等待装包阶段。

### 8.8 如实记录：纠偏消息与 transcript

team-lead 的一条纠偏消息**未注入本 agent 的 transcript**；`Config.albumFixEnabled` 那套接线是
bitfix **读源码现状自行补齐**的（发现 `refresh()` 缺第三参数、编译不过 ⇒ 保留并发预置的
`Config` 字段 / KDoc / `Config(false,false,true)`，补齐 `ALBUM_FIX_OFF_KEY` / `refresh()` /
`applyBlock` 门控 / `stat()`，未删别人的工作），不是照那条消息逐字执行。特此说明便于复核来源。

[DONE-ALBUMFIX]
