# R21d F3+F4 修法实现记录（r21d-f3f4-fix）

> 状态：完成（三闸全绿 + 变异有语义已确认，详见④⑤；`[DONE ALL]`）

## 目标
- **F4**：`HintBadge.kt` talker 闸门从「两侧任一为空就放行」收紧为「当前 talker 取不到 / 台账 talker 空 ⇒ 抑制角标」（宁可漏画不误贴），堵住 22:59 真机坐实的 `curTk=<null> + ledTk=群号 + tkSkip=true` 错贴通道。
- **F3**：关 `show_hint=false` 后残留角标从屏幕消失。必须正面裁决 `bound=0` 现场下「光改 ensureSweep 无效」的矛盾，给出不依赖 `bound` 的清理路径。

## 权威锚点
- 根因裁决：`docs/_r21d_reuse_rootcause_verdict.md` §修法建议 F3/F4、§验证点 3/4。
- 真机铁证：`docs/派发审计台账.md` 22:59 判据 A 结果（`hits/added +2/+2`、`curTk=<null> ledTk=43809452760@chatroom tkSkip=true`）。
- 矛盾来源：`docs/派发审计台账.md` 23:0x 派发段（`refresh():270 → ensureSweep():595 return → sweep():607 snap.isEmpty return`，现场 `bound=0`）。
- 源码锚点：`HintBadge.kt`（`bound:142-144`、`refresh:263-271`、`onBind talker 闸门:367-385`、`ensureSweep:594-599`、`sweep:605-621`、`ensure:655-738`）、`HintBadgeLogic.kt`（纯判据）、`HintBadgeLogicTest.kt`。

## 红线（三档）
- **进入**：仅可改 `HintBadge.kt` + `HintBadgeLogic.kt` + 对应单测 + 唯一产物本文；不动 `GroupDb.kt`/`AntiRevoke.kt`/pos 换算/type 闸门/content 对账/增强 E 观测段(:1063+)/bindHook 包装层(:280-301)。
- **返回**：先建骨架 → 每子步 `[DONE]` → 真 `[DONE ALL]`；三闸（定向/全量/assembleDebug）+ 变异验证（先确认变异有语义）。
- **复位（绝对禁区）**：禁碰 adb/装机/设备/开关/发图/gradle 之外的设备操作；禁 git（commit/push/checkout）；禁写模型路由字样。

---

## ① F4 抑制条件裁决（含行号）

**现状**（改前 `HintBadge.kt:367-385`）：`if (!curTalker.isNullOrBlank() && rescued.talker.isNotBlank() && curTalker != rescued.talker)` 才抑制；两侧任一空 ⇒ 整段跳过核对 ⇒ 放行贴角标。22:59 现场 `curTk=<null>` 正是走这条放行。

**裁决**：抑制条件覆盖**两侧** —— `curTalker.isNullOrBlank() || rescued.talker.isBlank() || curTalker != rescued.talker` 即抑制；等价的放行条件是「两侧非空**且**相等」。抽取纯函数 `HintBadgeLogic.talkerOk(curTalker, ledTalker)` 落在 `HintBadgeLogic.kt`，`HintBadge.kt:367-385` 调用它。理由：
1. 真机坐实的触发点是**当前侧** `curTk=<null>`（`talkerOf:533-536` 群聊 `xi` 取不到），必须收紧当前侧，否则坐实通道不堵。
2. **台账侧也不能放**：`AntiRevoke.kt:710 talker ?: ""` + `GroupDb.kt:177 talker TEXT NOT NULL`（只禁 NULL 不禁空串）⇒ 台账侧可写空串；台账侧为空时闸门同样零核对信息，撞号仍会误贴。根因裁决 F4 原文即「台账 talker 为空 **或** 当前 talker 取不到时抑制角标」，只收紧一侧会留半边漏洞。
3. 两侧任一空 ⇒ 闸门没有任何可用于核对会话身份的信息 ⇒ 执行「宁可漏画不误贴」。
4. 无误伤：两侧非空且相等（正常被救消息）仍贴 —— 硬回归红线，由单测 `f4TalkerOkSuppressesMismatchAndAllowsEqual` 锁死，真机反向对照见⑥。

**计数裁决**：**复用 `talkerMismatch` 单一计数**，不新增第二个计数器；`lastMiss` 携带子原因（`talkerSkip(cur|led|both)` / `talker(cur!=led)`），限流日志（≤8 条）带 `id/cur/led/orig` 现场字段。理由：
- 「两侧非空但不等」与「任一侧取不到/空」是**同一 talker 闸门的两个互斥分支** ⇒ 互斥判据不并列，一个闸门一个计数器，避免两个计数互相稀释、`stat()` 语义分裂。
- 「对着用户可见事实，不只写内部计数器」：抑制真正落在 `bound.remove(itemView)` + `ensure(itemView,false,"")` + `return`（角标不贴/被摘，用户可见），日志携带可复核的现场字段；计数器只是自诊断。

## ② F3 `bound=0` 矛盾裁决（含实现行号）

**主会话前置矛盾经源码复核属实**：`refresh():270 !enabled → ensureSweep()`；`ensureSweep():595 if (bound.isEmpty()) return`；`sweep():606-607 snapshot() 只遍历 bound，snap 空 return`。现场 `bound=0` ⇒ sweep 的视野为空，且残留角标所在 view 早被 `:349` 清出 `bound`、`ensure(false)` 又在 `:664` 未 attach 静默失败没真摘 View。⇒ **光改 ensureSweep 的 return 条件仍是无效改动**，必须加「不依赖 bound 的按 MARK tag 清理路径」。

**裁决方案（不碰增强 E :1063+）**：
1. 新增独立登记 `badgedViews`（`WeakHashMap<View,Boolean>`，与 `bound` 解耦）于 `HintBadge.kt:154`。`ensure()` 在角标确实挂上时登记 host（`markBadged` 调用点 `:712` 复用路径 / `:757` 新建成功路径；定义 `:770`）。**关键**：闸门失败路径（`:349/:382/:390/:419` 等）只 `bound.remove`，**不清 `badgedViews`** ⇒ 残留角标的 view 仍在此登记中。
2. `refresh()`（`:281-287`）改为 `if (!enabled) { clearAllBadges(); ensureSweep() }`。`clearAllBadges()`（`:801`）用纯判据 `HintBadgeLogic.cleanupTargetsOnDisable(boundViews, badgedViews)` 取清理集 = `badgedViews` 全集（**刻意不按 bound 过滤**），对每个 view 递归 `stripBadges()`（`:780`）按 `MARK == tag` 摘掉子树内所有角标 TextView。
3. `stripBadges()` 用 `ViewGroup.removeViewAt` 直接改自身 child 列表，**不依赖 `isAttachedToWindow`**（detach 态也生效），故能摘掉 `:664` 遗留的残留。
4. **bound=0 现场为何真能清掉**：那枚角标是本进程某次 bind 贴的（22:59 证据 `hits/added +2/+2`），贴的瞬间 `ensure` 已把 host 登记进 `badgedViews`；之后即使被 `:349` 清出 `bound`，`badgedViews` 保留 ⇒ `clearAllBadges()` 仍拿到那个 view ⇒ 递归按 MARK 摘掉 ⇒ 残留角标从屏幕消失。清理源是独立登记，不是 bound / rvRef / ring。
5. 关后保持：`onBind:314` 在 `!on()` 时不再贴新角标，`apply` 的 post 重试 `:647` 也复查 `on()` ⇒ 清一次即保持，不会被排队重试贴回。
6. 无误伤：重新 `show_hint=true` 后，`bound` 中仍在的 view 由现存 sweeper（`:590` 自调度）在下一轮按 `on()=true` 重贴；不在 bound 的 view 由后续 rebind 重贴。故「关→开」角标能回来。

[DONE] ① F4 抑制条件裁决 + ② F3 bound=0 矛盾裁决
[DONE] F4 落码：HintBadgeLogic.talkerOk + HintBadge.kt:382-413 闸门收紧（复用 talkerMismatch + why 子原因 + 限流日志 + bound.remove + ensure(false) + return）
[DONE] F3 落码：badgedViews 独立登记 + clearAllBadges/stripBadges + refresh 关开关接入
[DONE] 新增 5 条单测（F4 3 条 + F3 2 条）

---

## ③ 改动清单（文件:行）

| 文件 | 行 | 改动 |
|---|---|---|
| `HintBadge.kt` | `:154-155` | 新增 `badgedViews`（WeakHashMap 独立登记，与 bound 解耦） |
| `HintBadge.kt` | `:281-287` | `refresh()` 在 `!enabled` 时先 `clearAllBadges()` 再 `ensureSweep()`（F3 接入点） |
| `HintBadge.kt` | `:382-413` | talker 闸门收紧：调用 `HintBadgeLogic.talkerOk`，复用 `talkerMismatch` + `why` 子原因 + `lastMiss` + 限流日志 + `bound.remove` + `ensure(false)` + `return`（F4） |
| `HintBadge.kt` | `:712` / `:757` | `ensure()` 两条成功路径登记 `markBadged(host)` |
| `HintBadge.kt` | `:770-771` / `:780-793` / `:801-808` | 新增 `markBadged` / `stripBadges`（递归按 MARK 摘）/ `clearAllBadges`（F3 清理执行器） |
| `HintBadgeLogic.kt` | `:89-119` | 新增纯判据 `talkerOk`（F4）、`cleanupTargetsOnDisable`（F3） |
| `HintBadgeLogicTest.kt` | `:332-386` | 新增 5 条回归单测 |

⛔ 未改：`GroupDb.kt` / `AntiRevoke.kt` / `pos` 换算 / `typeOk` / `contentOk` / 增强 E 段（`observeBind:1139` / `ring` / `rvRef` / `dump:1246` / `stat:1308`，内容逐字未变，仅因上方插入而整体后移）/ `bindHook` 包装层（`:280-301`）。

## ④ 三闸结果（`wxgroup/` 下，JDK21 + Gradle 8.9，`--offline`）

| 闸 | 命令 | 结果 |
|---|---|---|
| 定向单测 | `bash tools/_junit.sh com.lx.wxgroup.data.HintBadgeLogicTest` | **OK (36 tests)**（改前 31 + 新增 5） |
| 全量单测 | `bash tools/_junit.sh` | **OK (1220 tests)**（历史 1215 + 5） |
| 编译 | `python tools/gradle_run.py assembleDebug` | **EXIT=0 / BUILD SUCCESSFUL**（未跑 `build`） |

（先 `compileDebugUnitTestKotlin` EXIT=0 保证 `_junit.sh` 跑的是新编译产物，避免读到旧 class。）

## ⑤ 变异验证（先确认变异有语义）

**语义确认**：两处变异都打在**真正决定结果的那层**——生产代码直接调用的纯判据（`HintBadgeLogic.talkerOk` / `cleanupTargetsOnDisable`），不是被下游校验挡住的等价变异；若 0 条变红即为假验证，本次两处均 2 条变红。

| 变异 | 改法 | 落盘确认 | 定向单测结果 | 还原 |
|---|---|---|---|---|
| **M1 · F4 反向放行** | `talkerOk` 改回旧逻辑 `cur.isNullOrBlank() \|\| led.isBlank() \|\| cur==led` | grep `[MUTATION F4-LEGACY]` 命中 | **2 failures（红）**：`f4TalkerOkSuppressesWhenCurrentTalkerUnavailable`、`f4TalkerOkSuppressesWhenLedgerTalkerBlank` —— 即 `curTk=null` + 台账命中时旧逻辑放行（贴错） | cp 还原 + grep 无 MUTATION → 36 OK |
| **M2 · F3 去掉清理** | `cleanupTargetsOnDisable` 改为 `= boundViews`（旧缺陷：清集只认 bound） | grep `[MUTATION F3-BOUND]` 命中 | **2 failures（红）**：`f3CleanupTargetsIgnoreEmptyBound` = `expected:<[residual-item-view]> but was:<[]>`（**正是 bound=0 现场清不掉**）、`f3CleanupTargetsKeepAllBadgedWhenBoundHasOtherViews` | cp 还原 + grep 无 MUTATION → 36 OK |

- **F4 正向**：`talkerOk(null, "43809452760@chatroom") = false` ⇒ 闸门 `if (!talkerOk(...))` 命中 ⇒ `bound.remove + ensure(false) + return`，角标不贴；反向恢复放行即变红 —— 满足「改前贴错/改后不贴/反向又贴错」。
- **F3 正向**：`bound` 空时清集仍为 `badgedViews` 全集 ⇒ 残留 view 被 `stripBadges` 递归摘掉；反向只认 bound 即变红 —— 满足「改前不消失/改后消失/反向复现」。
- **变异纪律**：变异脚本用绝对路径 python / cp，且变异后先 `grep` 确认落盘再编译，避免「文件没改成功但测试照跑全绿」的伪验证。

## ⑥ 无误伤对照

**单测层（已落）**：
- `f4TalkerOkSuppressesMismatchAndAllowsEqual`：两侧非空且相等（`wxid_A`/`wxid_A`、群号/群号）=> `true`，正常被救消息仍贴；两侧非空但不等 => `false`。这条在 M1/M2 变异下**仍绿**，证明修法只收紧空侧、不误伤正常侧。
- `f3CleanupTargetsKeepAllBadgedWhenBoundHasOtherViews`：bound 非空也不缩减清集，正常角标照样被清理/重贴路径覆盖。
- 既有 31 条（typeOk / contentOk / 锚点 /  emoji / 换行）全绿，零回归。

**宿主 View 行为（JVM 无法合理单测，给真机反向对照步骤；本 lane 未执行，交主会话装包后验证）**：
- ⚠️ F4「角标贴/不贴」与 F3「屏幕上角标消失」依赖 Android `ViewGroup` / Xposed hook，JVM 单测无法覆盖 `ensure()`/`stripBadges()` 的真实 View 操作；纯判据单测只锁决策层。以下为真机步骤替代（**本 lane 禁碰设备/开关/装包，未执行**）：
  1. **F4 正对照**：装包确认日志 `HINTBADGE installed` → 进 22:59 同群滚到错贴位 → `op=hintbadge sub=dump` 应不再出现 `tkSkip=true` 的贴出角标；`op=hintbadge sub=stat` 应 `talkerMismatch>0`、`lastMiss=talker(cur ...)`；肉眼该 cell 不再有「已阻止撤回 · 白也中单针还可以」。**反对照**：若临时把闸门恢复放行，同位置应再次贴错（变红）。
  2. **F3 正对照**：先贴一枚角标（对非台账 msgId 用 `op=hintbadge sub=fake --el id <id>`，或正常被救消息）→ 滚动使该 view 被 `:349` 清出 `bound`（`stat` 见 `bound=0`）→ `kvset antirevoke.show_hint false` + `kvset antirevoke.msg true` 并**回读** → 肉眼角标消失、`stat enabled=false`。**反对照**：临时去掉 `clearAllBadges()` 调用，同步骤角标不消失（变红）。
  3. **关→开恢复**：再 `kvset antirevoke.show_hint true`，滚动/rebind 后正常被救消息角标须重现（无误伤）。

## ⑦ 诚实边界

- **未装机、未做真机 A/B**：本 lane 严格未碰 adb/设备/开关/发图/装包/git 写操作；F4/F3 的**用户可见效果只在纯判据层由单测 + 变异锁定**，真机最后一公里须由主会话装包前交叉复核 + 用户确认后执行（见⑥）。
- **F3 清理的完备性依赖本进程登记**：`badgedViews` 只登记本进程 `ensure()` 真正挂上的角标；若角标来自更早进程（冷启已销毁，不构成残留）或未来新增的贴标路径绕过 `ensure()`，则不在登记内。当前贴标唯一入口 `apply → ensure`，覆盖完整。此路径未依赖 rvRef/ring/bound（增强 E 观测段逐字未改）。
- **F4「宁可漏画」的代价**：台账号空串或群聊 `talkerOf` 取不到时，本条被救消息角标会被抑制（漏画）—— 这是设计取舍（误贴更糟），非缺陷；两侧非空且相等时不受影响。
- **`talkerMismatch` 语义扩展**：该计数现在同时含「两侧不等」与「任一侧取不到/空」两种抑制；分支靠 `lastMiss`/日志 `why=` 区分（`cur`/`led`/`both`/`diff`）。历史现场 `talkerMismatch=0` 的旧口径不再等同于「闸门未触发」，解读须看 `why`。
- **计数 `added`/`bound` 未改语义**：`added` 仍只在真挂上时自增；`bound` 仍只认通过全部闸门的 view；F3 新增的 `badgedViews` 与二者独立，不改既有自诊断读数。

[DONE] ③ 改动清单 + ④ 三闸 + ⑤ 变异验证 + ⑥ 无误伤对照 + ⑦ 诚实边界
[DONE ALL]
