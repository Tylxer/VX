# R42 · 灰标错位常数可信度重构（N1 + N6 合并治理）

> 档位=Pro 依据=纯判据设计 + 调用点取证 + 单测/变异验证
> 作者：stagfix（缺陷修复子代理）
> 日期：2026-11-07
> 关联：`docs/_r42_deltag_false_positive.md`（N1/N6 登记于其 [DONE-8] 末节）、
> `docs/_r42_deltag_fp_review2.md`（B1 修复的独立复核）

## 硬边界（自束，逐条对照）

- 唯一允许改的源码：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsDeletedTag.kt`、
  `SnsDeletedTagLogic.kt`、`wxgroup/app/src/test/java/com/lx/wxgroup/feature/SnsDeletedTagLogicTest.kt`。
- ⛔ 禁区（bit3 已真机验收，碰了就是回归）：`SnsGuard.kt`、`SnsBitLogic.kt`、`SnsBlockProbe.kt`、
  `Keys.kt`、`HookEntry.kt`、`EntryReceiver.kt`、`res/values/ids.xml`。
- ⛔ 不装包、不 adb、不真机、不 commit、不 push（仓库有 post-commit 自动推送钩子）。
- 唯一允许新建的文档：本文件 `docs/_r42_n1n6_impl.md`（分步落盘，每节 `[DONE-n]`）。

---

## [DONE-1] 现状只读取证（谁在调用 offsets / anchorOffset / shouldResweep）

### 1.1 三个符号的全部调用点（grep 全文，无遗漏）

**`offsets`（`SnsDeletedTag.kt` 私有字段，`Collections.synchronizedMap(HashMap<String, Int>)`）**：

| 位置 | 操作 | 语义 |
|---|---|---|
| `SnsDeletedTag.kt:510` | 声明 | `offsets: MutableMap<String, Int>`，key=`adapter.javaClass.name` |
| `SnsDeletedTag.kt:534` | 读 | `val learned = offsets[key]` |
| `SnsDeletedTag.kt:551` | 写 | `if (d != 0) offsets[key] = d else offsets.remove(key)`（0 被丢弃） |
| `SnsDeletedTag.kt:556` | 读 | `shouldResweep(learned, offsets[key])` |

**`anchorOffset(passing: List<Int>, learned: Int?): Int?`（`SnsDeletedTagLogic.kt:198`）**：

| 位置 | 说明 |
|---|---|
| `SnsDeletedTag.kt:543` | 唯一生产调用点（`resolve()` 内） |
| `SnsDeletedTagLogicTest.kt:417-463` | 单测（10 处断言） |
| `SnsDeletedTagLogicTest.kt:559`、`:590`、`:593`、`:597` | 串联场景单测 |

**`shouldResweep(before: Int?, after: Int?): Boolean`（`SnsDeletedTagLogic.kt:217`）**：

| 位置 | 说明 |
|---|---|
| `SnsDeletedTag.kt:556` | 唯一生产调用点（`resolve()` 内） |
| `SnsDeletedTagLogicTest.kt:475-491` | 单测 |

**结论**：三个符号只被 `SnsDeletedTag.resolve()` 消费，没有其它文件（`SnsGuard`/`SnsBitLogic`/
`SnsBlockProbe`/`Keys`/`HookEntry`/`EntryReceiver`）引用。改动面严格收敛在允许的 3 个文件内，
不会触碰 bit3 已验收的禁区。

### 1.2 `resweep()` 的调用点（确认改动不扩大重入面）

`SnsDeletedTag.kt:829`（`resolve` 校准成功后触发）、`:886`（`setStyle`）、`:991`（`purge`）。
本次改动不新增 `resweep()` 调用点，只改「何时触发」的纯判据 `shouldResweep`。

### 1.3 两个缺陷的代码级病根（对着行号复述，不重新推）

- **N6**（`SnsDeletedTag.kt:551`）：`if (d != 0) offsets[key] = d else offsets.remove(key)`。
  O=0 时 `d==0` ⇒ 走 `remove` ⇒ `offsets[key]` 恒 null ⇒ `shouldResweep(null, null)=false` ⇒
  resweep 永不触发 ⇒ O=0 设备冷启首屏「同一好友连续两条」永久 HIDE（连真被删那条也不标），滚动也救不回。
- **N1**（`SnsDeletedTag.kt:510` key + `SnsDeletedTagLogic.kt:198-201` 分支 1）：key 只有类名；
  头部构成变化使 O 从 -1 漂到 -2 时，旧值 -1 仍 `in passing`（相邻恰好同作者）⇒ 分支 1 沿用 -1 ⇒ 新错标。

### 1.4 一个关键前置事实（决定方案走向）

`offsets` 是进程内内存字段，每次 LSPosed 加载（=微信重启）即清空。所以 N1 的「过期常数」发生在
**同一进程内头部结构变化**（换封面/出现横幅/封面消失等触发时间线重建）时，不是跨重启残留。
头部结构只在 adapter/itemView 侧可见（`getData()` 不含头部项），因此「头部签名」必须来自 itemView 侧
观测，且必然跨多次绑定累积 —— 这是本文 [DONE-2] 的核心推演前提。

---

## [DONE-2] 方案设计 + 4 个必答问题 + 被推翻的候选方向

### 2.0 核心结论（一句话）

> 把 `offsets` 的值从裸 `Int` 升级为 `Learned(offset, votes, headerSig)`；可信度显式建模为
> 「**一次无歧义采样（passing.size==1）+ 与头部签名一致（offset == -headerSig）**」两个独立信号；
> 每次使用前用 `reconcile(stored, headerSig)` 校验头部签名是否仍一致（N1 立即失效）；`0` 是合法值
> 且照常驱动 `shouldResweep`（N6）。`anchorOffset` 分支 1 改为「仅当 learned 已通过 reconcile
> （=头部签名一致）才沿用」。

### 2.1 数据结构与纯判据（全部落在 `SnsDeletedTagLogic.kt`，可 JVM 单测）

```kotlin
/** 一次绑定的错位常数状态。headerSig = 采样当时的头部占位签名（头部项数 H，O = -H）。 */
class Learned(val offset: Int, val votes: Int, val headerSig: Int)

/** 错位常数与头部签名的一致性：O == -H。毒样本（O=-1 时采样到 0）在此被拒。 */
fun consistent(offset: Int, headerSig: Int): Boolean = offset == -headerSig

/** 当前头部结构（headerSig）下，stored 是否仍可信。头部变 ⇒ 签名变 ⇒ 返回 null（N1 立即失效）。 */
fun reconcile(stored: Learned?, headerSig: Int): Learned? =
    if (stored != null && stored.headerSig == headerSig) stored else null

/** 投票：只接受与头部签名一致的偏移；同偏移同签名累票（封顶 2），不同则重置为 1。 */
fun vote(stored: Learned?, offset: Int, headerSig: Int): Learned? {
    if (!consistent(offset, headerSig)) return stored   // 毒样本被拒，不落票
    if (stored == null || stored.offset != offset || stored.headerSig != headerSig)
        return Learned(offset, 1, headerSig)
    return Learned(offset, minOf(stored.votes + 1, 2), headerSig)
}

/** 一次绑定的完整校准决策（原子读-算-写的纯函数，resolve() 在锁内调用）。 */
class Calibration(val d: Int?, val newStored: Learned?, val resweep: Boolean)

fun calibrate(passing: List<Int>, stored: Learned?, headerSig: Int): Calibration {
    val learned = reconcile(stored, headerSig)
    val d = anchorOffset(passing, learned)
    if (d == null) return Calibration(null, learned, false)
    val after = if (passing.size == 1) vote(learned, d, headerSig) else learned
    return Calibration(d, after, shouldResweep(learned, after))
}

fun anchorOffset(passing: List<Int>, learned: Learned?): Int? {
    if (learned != null && learned.offset in passing) return learned.offset  // 收紧：learned 已是 reconcile 过的
    return passing.singleOrNull()
}

fun shouldResweep(before: Learned?, after: Learned?): Boolean = before == null && after != null
```

### 2.2 必答问题 1：O=0 且「同一好友连续两条」冷启首屏，两个 item 会怎样？多久标上？

数据模型（O=0 ⇒ 无封面头，`headerSig=0`）：`getData()` = `[A(新,未删), B(被删), C(异作者), D(异作者)]`，
A/B 同作者相邻。

| 时刻 | pos | passing | headerSig | 结果 |
|---|---|---|---|---|
| 首绑 | 0=A | `[0,1]` 歧义 | 0 | learned=null ⇒ `anchorOffset` 返回 null ⇒ HIDE（pending，**不误标**） |
| 首绑 | 1=B | `[0,-1]` 歧义 | 0 | 同上 HIDE（pending） |
| 首绑 | 2=C 异作者 | `[0]` 唯一 | 0 | `vote(null,0,0)`=Learned(0,1,0)；`shouldResweep(null,已用)`=true ⇒ **resweep 立即触发** |
| resweep | 0=A | `[0,1]` | 0 | learned(0) 在 passing ⇒ 锚 0 ⇒ A ⇒ **HIDE（不误标）** |
| resweep | 1=B | `[0,-1]` | 0 | learned(0) 在 passing ⇒ 锚 0 ⇒ B ⇒ **SHOW（真被删标上）** |

**回答**：第一个 item（新发 A）最终 HIDE（无标）；第二个 item（真被删 B）**在第一个异作者 item C
绑定的那一刻**（pos=2）就通过 resweep 补标成功。也就是说，O=0 设备上「滚动出至少一个异作者动态」即触发，
不需要第二个异作者（`shouldResweep` 在首票即可用时就触发，而不是攒满 2 票）。

### 2.3 必答问题 2：O 从 -1 漂到 -2 时，旧常数为什么不会被子沿用？

漂移后头部项数 H:1→2 ⇒ `headerSig` 从 1 变 2。任何一次绑定先走 `reconcile(stored, headerSig=2)`，
旧值 `Learned(-1, *, headerSig=1)` 的 `headerSig=1 != 2` ⇒ reconcile 返回 null ⇒ `anchorOffset`
的分支 1 拿不到旧值 ⇒ 同名相邻歧义窗口（`passing=[-2,-1]`）落到 `singleOrNull()` ⇒ 返回 null ⇒
保守 HIDE（不沿用、不误标）。等一个新的、偏移与 headerSig=2 一致的无歧义采样（`passing=[-2]` 唯一）
落票后，才以 `Learned(-2,1,2)` 重新校准。

**关键**：旧常数失效靠的是「头部签名变了」这一**独立观测**，而不是「投票数不够」。所以即便漂移后
第一个窗口就是同名歧义窗口（没有无歧义采样可投票），旧 -1 也已经在 `reconcile` 一步被丢弃。

### 2.4 必答问题 3：投票数在 View 复用时会不会积累出错误共识？怎么防？

不会。防三层：

1. **票只来自「无歧义窗口」**（`passing.size == 1`）。同名相邻/复用串味的窗口（`passing` 有 2+ 个候选）
   根本不落票。
2. **票必须与头部签名一致**（`consistent(offset, headerSig)`）。一个 View 被复用给不同作者时，
   `textsOf` 取的是「绑定后」的新文本，`nameOk` 用新文本对新数据做护栏；若复用残留导致文本串味，
   只会让 `passing` 变空或歧义，**不会产生一个与头部签名一致的「错误偏移唯一采样」**。
3. **票是「同偏移同签名」才累加、否则重置**。即便某个错误采样侥幸落了一票，下一次正确采样会
   `offset != offset` 走重置分支，把错误票清零。且票数封顶 2，不可能无限累加。

补充：真正决定「可信」的是 `reconcile`（头部签名一致），票数只是重确认计数，不是信任的唯一来源。

### 2.5 必答问题 4：`offsets` 是 synchronizedMap，读-算-写是否有 check-then-act 竞态？

**有**。原代码 `learned = offsets[key]` → 算 d → `offsets[key] = d` 是典型 check-then-act：
synchronizedMap 只保证单次 get/put 原子，不保证「读→算→写」整段原子。生产里 `resolve()` 既被 UI 线程
（onBind）调用，也被探针线程（`setStyle`/`purge` → `resweep` → `resolve`）调用，两线程可并发。

**修法**：把整段读-算-写收敛进 `synchronized(offsets) { ... }`（在 `resolve()` 内，见 [DONE-3]），
`calibrate` 是纯函数、在锁内调用，`newStored` 在锁内写回。这样：
- 读 `offsets[key]`、算 `calibrate`、写 `offsets[key]=newStored / remove(key)` 三段在同一把锁内，无竞态；
- 锁粒度极小（一次 map 查 + 几个纯函数 + 一次 put），UI 线程热路径上基本无竞争（实际几乎只有 UI 线程写）；
- `resweep()` 的触发放在锁**外**（`if (cal.resweep) resweep()`），避免在锁内做 View 遍历（重入/长时间持锁）。

`firstDataPos`（头部签名观测字段）标 `@Volatile`，读/写是单字原子操作；并发下最坏是「瞬时的错误签名
⇒ 保守不信任」，不会产生错误标注。

### 2.6 被推翻的候选方向及反证

- **方向 A 原版「达到 2 票才可信」——推翻**。2026-11-07 真机冷启首屏只有**一个**异作者动态
  （`万华化学—李思远pc`，见 `_r42_deltag_false_positive.md` 的 dump），即只有一次无歧义采样。
  若要求 2 票，冷启首屏 learned 永远不到「可信」⇒ `shouldResweep` 不触发 ⇒ 真被删那条首屏标不上 ⇒
  **直接回归 bit3 已验收的 B1 行为（红线「冷启首屏 learned=-1 校准成功」）**。故否决「2 票」；
  改用「1 票 + 头部签名一致」双信号，信息量等价于「两个独立观测」，且不回归 B1。
- **方向 B（按 adapter 实例 `WeakHashMap<adapter, Learned>` 隔离）——部分采纳、未采用为主键**。
  头部签名本身已经承载了「头部结构」维度；按实例隔离解决不了「同一实例内部头部结构变化」（N1 恰好是
  同一 adapter 内部变化），故只在 key 用类名 + 值里带 headerSig，实例隔离是多余的。
- **方向 C（彻底不用历史常数，改结构反推）——推翻**。itemView 里拿不到 snsId/pos 的结构性对应
  （这正是为什么需要 `getData()` + 作者名对账），「反推」唯一可行的结构信号就是「头部项数」，
  而头部项数只能靠跨绑定累积观测（见 [DONE-1] 1.4），无法单绑定反推；它不比 A 更简单，且引入
  新的观测脆弱性。故不采用。

### 2.7 「头部签名用什么维度最稳」的正面回答

用 **`firstDataPos` = 已观测到的最小「数据项」adapter position = 头部项数 H**（`O = -H`）。

为什么它最稳（且 `list.size` 是错的）：
- `list.size` 会随「新发一条动态」变化（新动态插入 `getData()[0]`，size+1），但头部项数 H 不变 ——
  所以 `list.size` 作为签名会把「新发动态」误判成「头部变化」，冲掉有效校准（正是
  `_r42_deltag_false_positive.md` A5 记录的放大器）。
- `firstDataPos` 只随「头部项数」变化：封面头存在 ⇒ 第一个数据项在 pos=1（H=1）；无封面 ⇒ pos=0（H=0）；
  新增一个头部 ⇒ pos 后移（H+1）。新发动态只平移数据、不动第一个数据项的 pos ⇒ `firstDataPos` 不变。
- 观测方式：`observeHeader(pos, isData=passing.isNotEmpty())`，取单调最小 pos；当「一个 pos ≤ 已观测
  firstDataPos 的位置变成头部（passing 为空）」时，说明头部变多，重置重测。见 [DONE-3]。

> 诚实边界：`firstDataPos` 依赖「封面头产生空 passing、首条数据产生非空 passing」这一真机事实
> （`miss#5/#6 pos=0 texts=[轻触更换封面|…]` 已取证）。若某头部项文本恰好撞上作者名、或某条数据
> 作者名未渲染，签名可能瞬时误判，最坏结果是「签名不一致 ⇒ 保守不信任（漏标）」，不会错标。

---

## [DONE-3] 代码落地（改了哪几处、每处为什么）

| 文件:位置 | 改动 | 为什么 |
|---|---|---|
| `SnsDeletedTagLogic.kt` 新增 `data class Learned` | `offset/votes/headerSig` 三字段 | 错位常数 + 可信度显式建模（替代裸 `Int`）。`data class` 使单测 `assertEquals` 按值比较。 |
| `SnsDeletedTagLogic.kt` 新增 `consistent(offset, headerSig)` | `offset == -headerSig` | 毒样本唯一闸门：O 必须等于 -H（头部项数）。 |
| `SnsDeletedTagLogic.kt` 新增 `reconcile(stored, headerSig)` | 签名不一致返回 null | N1 核心：头部构成变化 ⇒ 旧常数立即失效。 |
| `SnsDeletedTagLogic.kt` 新增 `vote(stored, offset, headerSig)` | 拒不一致 + 同票累加/异票重置 | 单次采样不再「定终身」，可被重置。 |
| `SnsDeletedTagLogic.kt` 新增 `Calibration` + `calibrate(...)` | 一次绑定的完整决策纯函数 | 供 `resolve()` 在锁内原子读-算-写，也供单测串联。 |
| `SnsDeletedTagLogic.kt` `anchorOffset` 签名 `Int?`→`Learned?` | 分支 1 用 `learned.offset in passing` | 收紧：learned 必须是 reconcile 过的（头部签名一致）才可沿用。 |
| `SnsDeletedTagLogic.kt` `shouldResweep` 签名 + 删 `after != 0` | `before==null && after!=null` | N6：`0` 合法值也触发重扫，不再当「未知」哨兵。 |
| `SnsDeletedTag.kt` `offsets` 值类型 `Int`→`Learned` | KDoc 重写 | 值里带 headerSig（头部维度）。 |
| `SnsDeletedTag.kt` 新增 `firstDataPos` + `observeHeader(...)` | 观测头部项数 H | 头部签名来源（itemView 侧累积观测，`list.size` 之外的最稳维度）。 |
| `SnsDeletedTag.kt` `resolve()` 重写 | 算 passing → observeHeader → `synchronized(offsets){ calibrate }` → `cal.resweep` 外触发 | ① 头部签名观测；② 修 check-then-act 竞态；③ 0 也触发 resweep。 |

**未触碰**：`SnsGuard.kt` / `SnsBitLogic.kt` / `SnsBlockProbe.kt` / `Keys.kt` / `HookEntry.kt` /
`EntryReceiver.kt` / `ids.xml`；`markDecision` 四条件、`pickOffset`、`nameOk`、`tailIndex`、
`MARK_KEY/MARK_MAGIC`、`resweep()` 遍历本体均未改。

---

## [DONE-4] 单测新增/修改清单 + 实际用例数与失败数

环境（严格按纪律）：PowerShell 工具，`$env:PATH='G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot\bin;C:\Windows\System32;C:\Windows'`、
`$env:JAVA_HOME='G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot'`，`gradlew.bat testDebugUnitTest --tests ... --rerun-tasks`。

真实输出行（读 `app/build/test-results/testDebugUnitTest/TEST-com.lx.wxgroup.feature.SnsDeletedTagLogicTest.xml`）：

```
<testsuite name="com.lx.wxgroup.feature.SnsDeletedTagLogicTest" tests="60" skipped="0" failures="0" errors="0" timestamp="2026-11-06T19:41:40" ...>
```

**60 例 / 0 失败 / 0 错误 / 0 跳过**（原 54 + 新增 6）。

### 修改的既有用例（说明改的理由）

| 用例 | 改动 | 理由 |
|---|---|---|
| `anchor_offset_*`（6 例） | `learned` 实参 `Int`→`Learned` | `anchorOffset` 签名从 `Int?` 变为 `Learned?`，语义不变。 |
| `should_resweep_triggers_on_first_nonzero_calibration` → 改名 `..._on_first_calibration` | 加 `null→0 触发`；删「非零」限定 | `0` 现在是合法值（N6），`null→0` 必须触发。 |
| `should_resweep_does_not_trigger_on_zero_or_non_first_calibration` → 改名 `..._on_null_or_non_first` | 删 `null→0 不触发` 断言 | 原断言锁的是「0 当未知哨兵」，现已废弃（N6 根因）。 |
| 两个 `scenario_*` | `learned` 用 `learned(...)` 构造；`scenario_cold_start` 改走 `calibrate` 串联 | 与新签名/`calibrate` 一致，锁「calibrate→resweep→SHOW」全链。 |

### 新增用例（6 例，覆盖 4 个硬要求）

| 用例 | 锁住的分支 / 要求 |
|---|---|
| `reconcile_returns_null_when_header_signature_changed` | 要求 2（头部签名变 ⇒ 旧常数失效） |
| `header_change_makes_ambiguous_window_conservative` | 要求 2 + 要求 5（头部变后歧义窗口保守 null） |
| `poison_single_zero_is_rejected_and_not_promoted_to_trusted` | 要求 4（毒样本 passing=[0] 不把 0 升为可信） |
| `single_sample_without_header_signal_is_not_trusted` | 要求 3（签名未知时单次采样不落票） |
| `vote_accumulates_same_offset_and_resets_on_change` | 票数累加/重置语义 |
| `scenario_o0_same_author_cold_start_recovers_deleted_mark` | 要求 1（O=0 同一好友连续两条，真被删最终标上） |

⛔ 两条守门用例 `mark_decision_hide_when_style_off`、`mark_decision_hide_when_not_resolved_clears_recycled_mark`
**一行未动**（仍 0 失败）。

### 全量回归（`testDebugUnitTest --rerun-tasks`，无 `--tests` 过滤）

`app/build/test-results/testDebugUnitTest/TEST-*.xml` 汇总：**41 套件 / 821 例 / 0 失败 / 0 错误 / 0 跳过**
（原 815 + 新增 6）。`SnsDeletedTagLogicTest` 单类 60 例全绿。

三文件 SHA256 基线（实现完成后、变异之前，供复核比对）：

| 文件 | SHA256 |
|---|---|
| `SnsDeletedTag.kt` | `b4203fb1dff74d3a09a0552dec8325c9c959369eafc762a9729c0bcdb9a63c14` |
| `SnsDeletedTagLogic.kt` | `09269e2bf16ed323920359a3a9b09b96345aa055f9d567a7d0a4af1eb94eea33` |
| `SnsDeletedTagLogicTest.kt` | `5f05ffb54d6d2ea572b4d8cd02c0736d0be63bb624c26e85b9454c235e5b3102` |

---

## [DONE-5] 变异验证结果（每组变异 → 哪条用例变红；等价变异 → 全绿）

脚本 `app/src/test/mut/mut_deltag.py`（已按新代码重写 MUTS，7 语义变异 + 1 等价变异），
从 `wxgroup/` 目录 `python app/src/test/mut/mut_deltag.py` 跑，真实输出：

```
=== M1 reconcile 去掉头部签名校验（旧常数永不失效 ⇒ N1 复活）
    SnsDeletedTagLogicTest > header_change_makes_ambiguous_window_conservative FAILED
    SnsDeletedTagLogicTest > reconcile_returns_null_when_header_signature_changed FAILED
    60 tests completed, 2 failed

=== M2 consistent 符号反转（0 与 headerSig 不再互斥 ⇒ 毒样本 0 可落票）
    SnsDeletedTagLogicTest > scenario_cold_start_resweep_recovers_real_deleted_mark FAILED
    SnsDeletedTagLogicTest > single_sample_without_header_signal_is_not_trusted FAILED
    SnsDeletedTagLogicTest > vote_accumulates_same_offset_and_resets_on_change FAILED
    60 tests completed, 3 failed

=== M3 vote 不累票（同偏移票数不 +1 ⇒ 重确认计数失效）
    SnsDeletedTagLogicTest > vote_accumulates_same_offset_and_resets_on_change FAILED
    60 tests completed, 1 failed

=== M4 anchorOffset 分支 1 无脑沿用（learned 不在 passing 也沿用 ⇒ 过期常数乱锚）
    SnsDeletedTagLogicTest > anchor_offset_empty_passing_is_null FAILED
    SnsDeletedTagLogicTest > anchor_offset_stale_learned_falls_back_to_single FAILED
    SnsDeletedTagLogicTest > anchor_offset_stale_learned_with_ambiguous_is_null FAILED
    60 tests completed, 3 failed

=== M5 anchorOffset 保守退回 firstOrNull（歧义窗口不再保守 null ⇒ 相邻同名串位）
    ... 5 failed（含 anchor_offset_adjacent_same_name_without_learned、scenario_o0 等）

=== M6 shouldResweep 重新加 after!=0（0 不再触发 ⇒ N6 复活）
    SnsDeletedTagLogicTest > scenario_o0_same_author_cold_start_recovers_deleted_mark FAILED
    SnsDeletedTagLogicTest > should_resweep_triggers_on_first_calibration FAILED
    60 tests completed, 2 failed

=== M7 calibrate 永不投票（无歧义窗口不落票 ⇒ resweep 永不触发）
    SnsDeletedTagLogicTest > scenario_cold_start_resweep_recovers_real_deleted_mark FAILED
    SnsDeletedTagLogicTest > scenario_o0_same_author_cold_start_recovers_deleted_mark FAILED
    60 tests completed, 2 failed

=== EQ shouldResweep De Morgan 等价改写（必须全绿）
    BUILD SUCCESSFUL（0 条变红！）
    ✓ 等价变异保持全绿

语义变异组数 7 / 变红组数 7
等价变异 EQ：全绿 ✓
源码已还原：sha256 09269e2bf16e
```

结论：**7/7 语义变异全部变红、且每条分支都有对口用例**（M2 还顺带变红了 scenario_cold_start /
vote_accumulates，说明 consistent 是「票」和「冷启校准」的共同闸门）；**EQ 等价变异保持全绿**，
证明「变红」不是「随便改都会红」的假验证。源码还原后 sha 与基线一致（`09269e2bf16e`）。

---

## [DONE-6] 回归自检（逐条对照红线）+ 遗留风险

### 红线逐条对照

| 红线（已验真机行为） | 静态对照结论 |
|---|---|
| 冷启首屏 `learned=-1` 校准成功，新发（未删）动态**无**灰标 | ✅ `calibrate(listOf(-1), null, 1)` ⇒ `Learned(-1,1,1)` 首票即可信 ⇒ resweep；新发格 `anchorOffset([0,-1], learned(-1))=-1` ⇒ 锚到自己 ⇒ `markDecision` HIDE。锁在 `scenario_adjacent_same_name_only_real_deleted_post_is_marked` + `scenario_cold_start_resweep_recovers_real_deleted_mark`。 |
| 滚动后只有真被删那条带灰标（两条同作者无标、被删有标、他人无标） | ✅ 同一 learned 下，新发格 HIDE / 被删格 SHOW / 异作者格按其自身 snsId 判，与 `markDecision` 四条件一致。`markDecision` **一行未动**。 |
| `match == hits`（命中数与已渲染数相等） | ✅ `applyMark`/`ensureMark`/`removeMark` 计数链路未动；本次只改「哪个 snsId 该插标」，不改「插/摘」计数。 |
| `miss` 只出现在顶部封面项（`passing=[]`） | ✅ 封面 `passing=[]` ⇒ `calibrate` 返回 `d=null` ⇒ `lastMiss="ambig(... headerSig=...)"` 仍走 miss 负路径；`firstDataPos` 只在封面后第一条数据时才被观测为 1。 |
| 滚回顶部再滚回来，真被删那条的灰标**始终在**（无「先消失再补回」的可见闪烁）；同屏只有同一好友连续多条且全部歧义也不丢标 | ✅ 头部重绑不复位签名（复位判据 `pos == firstDataPos` 非 `<=`，复位后 `pos + 1` 非 `-1`）；`newStored==null` 只「不更新」不 remove ⇒ `offsets` 条目只增改不删除，签名回到旧值时旧常数经 `reconcile` 立即重新有效。锁在 `header_rebind_does_not_reset_signature` + `calibration_survives_signature_flap`。 |

### 遗留风险（如实记录，非阻塞）

1. **`firstDataPos` 观测依赖真机结构事实**（封面空 passing / 首条数据非空 passing），无真机无法验证
   观测序列；若失败则退化为「保守不标」（漏标，不误标）。
2. **`consistent` 的单一头部块假设**（O=-H）未覆盖「非连续头部/混合结构」；届时闸门误拒合法票，
   同样退化为保守不标。
3. **毒样本的单次 `passing=[0]` 仍会经 `singleOrNull()` 被本格采信一次**（`anchorOffset([0], learned)`
   返回 0）。这是旧 `singleOrNull` 语义的既有行为（与改前一致），不落票、不扩散；但严格说这一格
   可能瞬时错锚，属「已知单格风险」，非本次引入。

---

## [DONE-7] 给复核方的 3 个「最可能被推翻的点」

1. **信任门槛「1 票 + 头部签名一致」是否充分**。我**推翻**了任务候选方向 A 的「2 票才可信」：
   2026-11-07 真机冷启首屏只有 1 个异作者动态（万华），2 票会让 `learned=-1` 永远不到可信 ⇒
   resweep 不触发 ⇒ 真被删那条首屏标不上 ⇒ **回归红线「冷启首屏 learned=-1 校准成功」**。故改用
   「1 次无歧义采样 + 头部签名一致」两个独立信号。若复核方坚持「单次采样不得可信」必须字面满足
   「2 票」，则需先回答「冷启首屏只有 1 个异作者时如何凑第 2 票」——本方案认为那无法在不动红线
   的前提下成立。

2. **头部签名维度 `firstDataPos`（=头部项数 H=-O）是否真比 `list.size` 稳**。我的论证：`list.size`
   随新发动态变化（新动态插入 getData()[0]）而 H 不变；H 只在头部项数变化时才变。但 `firstDataPos`
   是**跨绑定累积观测**、依赖「封面空 passing / 首条数据非空 passing」，若观测被某次异常绑定污染
   （如头部文本撞作者名），会误判 H。这是无真机下最需要真机复核的一环。

3. **`consistent(offset, headerSig) ≡ offset == -headerSig` 的严格性**。它把「毒样本 0」与「合法 0」
   的分界完全压在「H 观测正确」上：O=0 设备上 H 必须被观测为 0 才能落票。若 O=0 设备上 `firstDataPos`
   因封面残留文本被误判为 1，则 `consistent(0, 1)=false` ⇒ 0 落不了票 ⇒ N6 仍退化。这是一个
   「修复依赖同一观测」的耦合点，建议真机在 O=0 设备上单独验证 `firstDataPos` 序列。

---

## [DONE-9] 调用侧 B1/B2 回归修复（头部重绑删校准 + firstDataPos 全局单例漏隔离）

> 触发：主会话逐行复核 [DONE-6] 时发现两个**回归级阻塞**，上一轮红线自检漏了它们。本节修掉并补红线。

### 9.1 B1 成因（头部项每次重绑都把校准删掉）

`observeHeader` 复位条件原写 `pos <= firstDataPos`，且复位后置 `-1`。真机 H=1、`firstDataPos=1`、
封面项在 pos=0。逐事件推演：

1. 冷启：pos=0 封面（`passing=[]`）→ `firstDataPos=-1`，`firstDataPos >= 0` 不成立 → 不复位；
   pos=1 数据 → `firstDataPos=1`；pos=3 异作者 `passing=[-1]` → `vote` 得 `Learned(-1,1,1)` → resweep 补标。
2. **用户滚回顶部 / 刷新时间线** ⇒ pos=0 封面重绑 ⇒ `passing=[]`、`0 <= 1` 成立 ⇒ `firstDataPos=-1`。
3. `calibrate([], Learned(-1,1,1), -1)` → `reconcile` 因 `1 != -1` 返回 null → `Calibration(null, null, false)`。
4. 调用侧 `if (c.newStored == null) offsets.remove(key)` ⇒ **校准条目被物理删除**。
5. 紧随其后的同名相邻两条重新变歧义（`passing=[0,-1]`、`stored=null`）⇒ `d=null` ⇒ `markDecision` 走 HIDE
   ⇒ `removeMark` ⇒ **灰标从屏幕上消失**。
6. 只有等异作者那条重绑投票后 resweep 才补回；同屏若只有该好友连续多条且全部歧义，就一直不回来。

改前代码在头部绑定上不删校准（只在 `passing.size==1 && d==0` 时 remove），所以这是本轮**新引入的回归**，
且肉眼可见（闪烁）。

### 9.2 B2 成因（firstDataPos 全局单例、offsets 按类分键）

`firstDataPos` 无 key。`install` 挂在通用 `WxRecyclerAdapter.onBindViewHolder` 上，聊天列表等其它 adapter
也会进来；它们的 `getData()` 不含 SnsInfo ⇒ `passing` 恒空 ⇒ 被当成「头部」⇒ 能把时间线签名改坏 ⇒
`consistent(-1, 2)=false` ⇒ 正确采样全被 `vote` 拒掉 ⇒ 整个会话永久漏标。`offsets` 当初按类名分键正是
为了隔离这个，签名维度漏了同一考虑。

### 9.3 修法（三处，全部落在允许文件）

| 位置 | 改动 |
|---|---|
| `SnsDeletedTagLogic.kt` 新增 `observeHeader(prev, pos, isData): Int` | 观测逻辑下沉成纯函数：数据项取单调最小；头部项**只在 `pos == prev` 时前移 `pos + 1`**，`pos < prev`（含封面 pos=0）一律保持 `prev` 不变。**`pos=0` 是「从来就是头部」的位置，绝不触发复位**；复位后正向已知值（`pos+1`）不置 `-1` 失明。 |
| `SnsDeletedTag.kt` `firstDataPos` `Int` → `HashMap<String, Int>` | 按 adapter 类名分键，与 `offsets` 同一把 `synchronized(offsets)` 锁保护；`resolve()` 里观测/写回/校准整段锁内完成。 |
| `SnsDeletedTag.kt` 调用侧 `newStored==null` | 只许**不更新**、不许 `remove`。失效靠 `reconcile` 的 `headerSig` 把关（签名不符即拒），签名回到旧值时期旧常数本来就重新有效；删除只会制造闪烁。不变式写进 `offsets` KDoc：**条目只增改、不删除**。 |

`Calibration.newStored` KDoc 同步改为「null = 本次不更新，保持现状」。

### 9.4 新增单测（5 例）与对应抓取

| 用例 | 断言 | 锁住的回归 |
|---|---|---|
| `header_rebind_does_not_reset_signature` | `observeHeader(1,0,false)=1`、`observeHeader(2,0,false)=2` | B1 主用例：封面重绑不复位；第二条断言专抓 M8（`==`→`<=` 会把 2 前移成 1） |
| `header_growth_shifts_signature` | `observeHeader(1,1,false)=2` | 原先首个数据位变成头部 ⇒ 前移 pos+1；专抓 M9（`pos+1`→`-1`） |
| `data_bind_keeps_monotonic_min` | `observeHeader(2,3,true)=2`、`observeHeader(3,1,true)=1`、`observeHeader(-1,1,true)=1` | 数据项单调最小 |
| `calibration_survives_signature_flap` | 串 `Learned(-1,1,1)` → 头部重绑(`calibrate([],stored,-1)`) → 再绑(`calibrate([0,-1],stored,1)`) ⇒ 最终 `anchorOffset` 仍 -1、新发 HIDE/真被删 SHOW；末尾反证「删条目 ⇒ d=null 漏标」 | B1：校准条目存活、签名 flap 后恢复 |
| `header_signature_isolated_per_adapter_key` | keyA 朋友圈（签名 1）与 keyB 聊天列表（签名恒 -1）各自独立演进，A 的 `consistent(-1,1)=true` | B2：不同 adapter key 签名互不污染 |

### 9.5 变异结果（真实输出）

`mut_deltag.py` 补两组 M8/M9，语义变异 9 组 + 等价变异 1 组：

```
=== M8 observeHeader 复位判据 == 改回 <=（封面重绑被误判成头部变多 ⇒ 签名前移）
    SnsDeletedTagLogicTest > header_rebind_does_not_reset_signature FAILED
    65 tests completed, 1 failed
=== M9 observeHeader 前移 pos+1 改回 -1（头部变多后退化失明 ⇒ 签名未知）
    SnsDeletedTagLogicTest > header_growth_shifts_signature FAILED
    65 tests completed, 1 failed
=== EQ shouldResweep De Morgan 等价改写（必须全绿）
    BUILD SUCCESSFUL（0 条变红！）
    ✓ 等价变异保持全绿
语义变异组数 9 / 变红组数 9
等价变异 EQ：全绿 ✓
源码已还原：sha256 fdd60d065a9f
```

M1~M7 全部仍变红（其中 M1 现额外抓到 `calibration_survives_signature_flap`，证明该串联用例与
`reconcile` 语义一致）。**9/9 语义变异变红、EQ 全绿**，源码还原后 sha 与基线一致。

### 9.6 全量回归

```
<testsuite name="com.lx.wxgroup.feature.SnsDeletedTagLogicTest" tests="65" skipped="0" failures="0" errors="0" timestamp="2026-11-06T20:02:08" ...>
```

全量 `testDebugUnitTest --rerun-tasks`：**41 套件 / 826 例 / 0 失败 / 0 错误 / 0 跳过**
（原 821 + 新增 5）。`SnsDeletedTagLogicTest` 单类 65 例全绿。

三文件 SHA256（实现完成后）：

| 文件 | SHA256 |
|---|---|
| `SnsDeletedTag.kt` | `7a911f31a231aecb6cb73e188ddbae52a9554024b01716d8b9038819a91275f7` |
| `SnsDeletedTagLogic.kt` | `fdd60d065a9fae65abcf669cb78fe3e199db4842986a9d4f6c851289fb56e6ba` |
| `SnsDeletedTagLogicTest.kt` | `57745227d7a8cb2b69d6823f1a1e5bc1938dd221da68a553a211ac8ad78c2152` |

### 9.7 红线补一条（已并入 [DONE-6] 红线表）

> **滚回顶部再滚回来，真被删那条的灰标必须始终在**（不允许「先消失再补回」的可见闪烁）；
> 同屏若只有同一好友的连续多条且全部歧义，**也不允许**因此丢标。

[ALL-DONE-2]
