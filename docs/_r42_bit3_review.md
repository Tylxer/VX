# R42 bit3 装包前第二视角复核（bitreview）

> 只读复核。⛔ 未改任何源码/配置/文档；⛔ 未跑 gradle；⛔ 未装包/adb/commit。
> 唯一写入文件：本文件。

## 复核范围 + 基线

**裁决目标**：这批改动马上 `adb install` 到真机（LSPosed 模块，微信 8.0.48/2588 GP）。
本复核不确认「代码存在」，而是逐条对照源码，验证逻辑、边界、回滚路径、反向对照的单变量性，
给出「能不能装包」的三选一总裁决。

**项目根**（ROOT）：`c:\Users\lx\WorkBuddy\2026-09-29-20-23-21`
**路径缩写**（下文 `文件:行号` 均相对 ROOT）：
- `SnsGuard.kt` = `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsGuard.kt`
- `SnsBlockProbe.kt` = `.../feature/SnsBlockProbe.kt`
- `SnsBitLogic.kt` = `.../feature/SnsBitLogic.kt`
- `SnsDeletedTag.kt` / `SnsDeletedTagLogic.kt` = `.../feature/`
- `SnsSqlRewrite.kt` = `.../feature/SnsSqlRewrite.kt`
- `SnsBitLogicTest.kt` = `wxgroup/app/src/test/java/com/lx/wxgroup/feature/SnsBitLogicTest.kt`
- `Keys.kt` / `Kv.kt` / `GroupDb.kt` = `wxgroup/app/src/main/java/com/lx/wxgroup/data/`
- `PrefsVisibilityTest.kt` = `wxgroup/app/src/test/java/com/lx/wxgroup/data/PrefsVisibilityTest.kt`
- `EntryReceiver.kt` = `wxgroup/app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt`

**基线（team-lead 给的全部必要事实，复核时作为判据）**：
- `SnsInfo.sourceType`：bit1(2)=时间线可见；bit3(8)=好友主页相册记账位。
- 主页相册查询是闭集枚举 `sourceType IN (...64 个值...)` 全含 bit3，**不是** `&8` 位测试。
- 待修缺口：删前没进过主页 ⇒ old=2（只 bit1）；旧写时拦截只「摘键」⇒ 库停 2 ⇒ 时间线在、主页整条消失。
- 本轮修复：拦下的那次 UPDATE 把 `ContentValues["sourceType"]` 写回 `keep = old | 10`（典型 `2→10`），零额外 IO、无竞态。
- 三层拦截：甲=`updateWithOnConflict before` 摘键/写回；A=非 0 纯降位判定；B=`execSQL` 里 `& -9`→`-1`（no-op）。
- 新增反向对照开关 `antirevoke.sns_album_fix_off`（off 语义，未设置=false=补位开）。关掉它只停「写回」、降级回纯摘键，A/B 两层完全不动 ⇒ 自变量唯一。
- `keepAliveSource(old)` 两个 null 边界：`old==null`（bits 读不到）降级；`old&10==0`（彻底不可见 0/4/32/128/20/84/68）降级，避免复活。`want` 绝不参与公式。
- `afterOk/afterFail` 是判断成败唯一依据，语义必须是「回查处是否取到 bits」：`bits==null ⇒ afterFail++/e.after="?"`；`bits!=null ⇒ afterOk++/e.after=bits.src`。四个 `shape*` 计数器必须正交独立、绝不覆盖这两个。

**回归红线**：方案 B 的 `&-9→-1` 改写不被碰坏；灰标 `isTimelineLoss(old,new)` 只盯 bit1、吃 `want`；
`blockEnabled = ANTIREVOKE_SNS || ANTIREVOKE_SNS_BLOCK` 语义保持。

**已知两处小遗漏（team-lead 已点名，需判断是否阻塞）**：`install()` 日志无 `albumFix=`；
key 字面量写成 `SnsGuard.ALBUM_FIX_OFF_KEY` 而非进 `Keys.kt`（违背 K4「业务代码只引用 Keys」）。

[DONE-0]

## §1 `SnsGuard.kt` — Config 接线 / 独立性 / applyBlock / 可观测性

**1.1 Config 三参数接线**
- `Config` 三参数声明完整：`SnsGuard.kt:82-101`（`blockEnabled:84` / `sqlBlockEnabled:91` / `albumFixEnabled:100`）。
- `refresh()` `SnsGuard.kt:174-181` 用**命名参数**传齐三个：`blockEnabled=block(175)`、`sqlBlockEnabled=block && !Kv.getBool(SQLBLOCK_OFF)(177)`、`albumFixEnabled=!Kv.getBool(ALBUM_FIX_OFF_KEY)(180)`。无漏参。
- 默认值 `Config(false,false,true)` 在 `SnsGuard.kt:104`；`install()` 在 `SnsGuard.kt:126` 先 `runCatching{refresh()}` 再挂钩子 ⇒ 默认仅存在于类初始化瞬间、马上被 `refresh()` 覆盖，不会残留。默认值第三参 `true` 与 off 语义（未设置=补位开）一致。
- 全文件仅两处 `Config(`（`:104`、`:174`）均为 3 参 ⇒ **编译期无漏参**。✅ 结论：接线完整、一致。

**1.2 albumFixEnabled 独立性 + A/B 单变量（核心）**
- `albumFixEnabled = !Kv.getBool(ALBUM_FIX_OFF_KEY)`（`SnsGuard.kt:180`）——**完全不含 `block`**，⛔ 不是 `block && ...`。独立于 `blockEnabled`。✅
- 全文件 `albumFixEnabled` 的**读取点**只有两处：`applyBlock` 的 `SnsGuard.kt:473` 与 `stat()` 的 `SnsGuard.kt:1447`。已逐字核对 `onSnsSql`(`685-705`) 用的是 `cfg().sqlBlockEnabled`(`:694`)，`applySqlRewrite`(`713-728`) 只读 `sqlBlockEnabled` 的判定结果，`onExecSql`(`647-670`) 不涉及开关 ⇒ **关掉 albumFix 时 A(甲/`blockEnabled`) 与 B(`sqlBlockEnabled`) 一个字节都不变**。✅
- `cfg()` 3s TTL 缓存 `SnsGuard.kt:185-188`：`refresh()` 末尾 `cfgAt=elapsedRealtime()`(`:182`)，`kvset` 里主动 `SnsGuard.refresh()`（`EntryReceiver.kt:183`）⇒ `config` 立即刷新、无需等 TTL；TTL 到期后也是一次性重算三参、彼此一致，不会造成「albumFix 变了而 A/B 用旧值」的撕裂。✅
- 自变量唯一性：主开关开时 `block=true`、`sqlBlockEnabled=true&&!sqlBlockOff`、`albumFixEnabled=!albumFixOff`。`kvset antirevoke.sns_album_fix_off true` 只改 `albumFixEnabled` ⇒ `applyBlock:473` 的 `keep` 从 `keepAliveSource(...)` 变 `null` ⇒ 只摘键；A/B 不动。✅

**1.3 `applyBlock()` 逐条**
- 开关关闭 `OFF` 早退：`SnsGuard.kt:462-468` `if(!cfg().blockEnabled){ record(...Outcome.OFF...); return }`。在 `keep` 计算(`:473`)、`put`、 `remove` **之前**；`record()`（`SnsBlockProbe.kt:305-360`）只加内存计数/事件/日志，不碰 `values`、不写库 ⇒ **一个字节都不写**。✅
- `keep` 门控：`SnsGuard.kt:473` `var keep = if(cfg().albumFixEnabled) SnsBitLogic.keepAliveSource(bits?.src) else null`。调试开关关 ⇒ `keep=null` 直接降级。✅
- 写回路径 `SnsGuard.kt:483-494`：`put("sourceType",keep)`(`:485`) 后 `record(...,cv=values,keep=keep)`(`:487-490`) → `SnsDeletedTag.onBlocked(id,bits,want)`(`:492`) → `return`(`:493`)。**只 put 不 remove，且 return 不落到降级段**。
- ⇒ `left==0` 的 `SKIP_EMPTY`（`SnsGuard.kt:524-539`）在写回路径**确实不可达**：① 控制流在 `:493` 已 return；② 即便假设不 return，`put` 让 cv 恒有 `sourceType` 键、`size()!=0`。✅ 论证成立。
- 降级路径放回 **`want`**（微信原值）：`SnsGuard.kt:525-529`，`want = newValue ?: 0`(`:459`)，`put.invoke(values,"sourceType",want)`(`:529`)。⛔ 不是 `keep`。✅ 失败留告警不吞(`:531`)。
- `SnsDeletedTag.onBlocked(id,bits,want)` 参数仍是 `want`：写回分支 `:492`、降级 BLOCKED 分支 `:545` 两处一致。✅
- 写回值**完全由 `keepAliveSource(bits?.src)` 决定**，`newValue`/`want` 不参与公式（`:473`、`:485`）。✅
- ⚠️ 但 `keepAliveSource` 本身在 `old==null` 边界上有缺陷 —— 见 §3（这是本轮**唯一阻塞项**）。

**1.4 可观测性**
- `stat()` `SnsGuard.kt:1445-1447` 含 `albumFix=${cfg().albumFixEnabled}`，且 `op=snsg sub=stat` 会打它（`EntryReceiver.kt:349`）⇒ 反向对照开关读回可见。✅
- `install()` 日志 `SnsGuard.kt:167` 只带 `blockEnabled=`，**未带 `albumFix=`** —— 即 team-lead 点名的「已知小遗漏 ①」。仅日志口径、不影响功能（stat 可查），**非阻塞**。

**§1 结论**：接线/独立性/applyBlock 全部符合规格；唯一例外是 `install()` 日志缺 `albumFix=`（非阻塞），以及 `keepAliveSource` 的边界缺陷会经 `:473` 传导（见 §3，阻塞）。

[DONE-1]

## §2 `SnsBlockProbe.kt` — `finishAfterWrite()` 的成败判据 / 正交性 / 节流 / ThreadLocal

**2.1 afterOk/afterFail 只由 bits 决定（唯一依据，不可被覆盖）**
- `finishAfterWrite` `SnsBlockProbe.kt:385-441`。
- `val bits = runCatching{reader(e.snsId)}.getOrNull()`(`:390`) 是唯一输入。
- `if (bits == null) { afterFail++; e.after="?" }`(`:397-400`)；`else { afterOk++; e.after=bits.src.toString() }`(`:406-408`)。✅ 与基线逐字一致：增减条件只看 `bits`。
- `e.after` 赋值两处(`:399`/`:408`)正确；`e.afterShape=shape.name`(`:389`) 是**另一个字段**（`Event.after:183` vs `Event.afterShape:187`），互不覆盖。✅

**2.2 四个 shape 计数器正交独立**
- 声明四个独立 `AtomicInteger`：`shapeRemoved/Rewritten/NotBlocked/Unknown`（`SnsBlockProbe.kt:153-156`），与 `afterOk/afterFail`(`:151-152`) 并列、各自独立。
- 形态维度在 `SnsBlockProbe.kt:418-440` 的 `when(shape)` 里只 `increment` 对应 shape 计数，**没有任何一处写 `afterOk/afterFail`**。✅
- 回查维度(`:396-416`)只写 `afterOk/afterFail`，**没有任何一处写 shape 计数**。✅
- ⇒ 两维度物理隔离、互不覆盖。`stat()` 第二行(`:701-703`)同时打印 `afterOk/afterFail` 与 `shape=rm/rw/nb/un` 两套，佐证正交。✅

**2.3 e.after 赋值 / act 口径**
- 见 2.1。`act = if(e.keep!=null) "writeback" else "remove"`(`:394`) 订正了旧日志「(键已摘)」在写回路径下的假话，只作用于日志文本，不碰计数。✅

**2.4 allowLog 节流是否被多分支重复消耗**
- `val canLog = allowLog(afterLogAt, e.snsId)`(`:393`) —— **整函数只调用一次**，结果缓存进布尔 `canLog`。
- 复用该 `canLog` 的分支：readback 失败(`:401`)、readback 成功(`:410`)、MISMATCH(`:424`)、UNKNOWN(`:433`)。其中 readback 两分支互斥、shape 两分支互斥，最多 2 行日志，共用同一额度。
- `allowLog`(`SnsBlockProbe.kt:467-479`) 返回 true 时写 `map[snsId]=el`（消耗额度）。因只调用一次 ⇒ **不存在「多分支各自再调一次 allowLog 互相把对方挤掉」**。✅
- 另：`logBlockedThrottled` 用 `hitLogAt`(`:452`)，`finishAfterWrite` 用 `afterLogAt`(`:393`)，两张表互不干扰。✅ 结论：节流设计正确，无挤掉。

**2.5 pending ThreadLocal 的 set/remove 配对**
- `pending = ThreadLocal<Event?>()`(`SnsBlockProbe.kt:213`)。
- `set`：`record()` 中仅 `Outcome.BLOCKED && cv!=null` 时 `pending.set(e)`(`:346`)。
- `remove`：`finishAfterWrite` 开头 `pending.get() ?: return`(`:386`) 后 `pending.remove()`(`:387`)；`clearPending()`(`:447-449`) 在 `onSnsUpdateBefore` 第一行被调（`SnsGuard.kt:334`），清除上一轮残留。
- `hasPending()`(`:444`) 供 after 钩子守卫(`SnsGuard.kt:567`)。
- ⇒ 每个事件恰好 set 一次、在 after 消费一次并 remove；异常/提前退出由 `clearPending` 兜底。**配对完整、无泄漏**。✅
- 附带核对：写回路径 `record(...,cv=values,...)`(`SnsGuard.kt:487-490`) 传了非空 cv ⇒ `pending` 被 set ⇒ after 链路 `hasPending→finishAfterWrite→readBits` 仍跑，写回值**不会**让校验链路失效。✅

**§2 结论**：全部符合基线。`afterOk/afterFail` 语义未被污染、四个 shape 计数器正交、节流单次消耗、ThreadLocal 配对完整。**无阻塞项**。

[DONE-2]

## §3 `SnsBitLogic.kt` — keepAliveSource 公式/边界/幂等 + afterShape 互斥性 ⚠️ 原含 B1（已证伪，见文末修订节）

**3.1 `keepAliveSource(old)` 公式与 want 不参与**
- 实现 `SnsBitLogic.kt:122-126`：
  ```
  if (old == null) return VISIBLE_MASK          // ← :123 见 3.2，缺陷
  if ((old and VISIBLE_MASK) == 0) return null  // :124 边界 2
  return old or VISIBLE_MASK                    // :125 = old | 10
  ```
- 公式 `old | 10`（`VISIBLE_MASK=10` 见 `:43`）正确：保留 old 全部高位、只补 bit1+bit3。KDoc(`:99-104`) 逐条论证了「不是 `old|8`」「不是常量 10」「want 不参与」。函数签名只吃 `old`、根本拿不到 `want` ⇒ **want 绝不参与公式**。✅
- 幂等：`keep` 必含 `VISIBLE_MASK` 且是 `old` 超集 ⇒ `keep(keep)=keep|10=keep`（`:115-117`）。对**非 null 入参**成立。✅

**3.2 ⛔ 阻塞项 B1：`old == null` 边界返回了 `VISIBLE_MASK` 而非 `null`**
- `SnsBitLogic.kt:123` 现状：`if (old == null) return VISIBLE_MASK   // MUTATED M4`。
- 这与**三处权威口径全部矛盾**：
  1. **基线要求**：「`old==null`（bits 读不到）降级」＝返回 `null`、不写回。
  2. **本文件自身 KDoc** `SnsBitLogic.kt:106-108`：「两个返回 null 的边界 / 1. `old==null`：……凭空造 `10` 等于**编造可见性** ⇒ 降级摘键，绝不凭空造位（硬约束 2）」；`:93`「返回 `null` = 不写回」。
  3. **调用方 KDoc** `SnsGuard.kt:439-440`：「`bits == null ⇒ 返回 null ⇒ 降级只摘键（约束 2）」；`SnsGuard.kt:430` 把「写回路径」定义为 `keepAliveSource(bits?.src) != null`。
  4. **单测** `SnsBitLogicTest.kt:268-271` `keep_alive_degrades_when_bits_unreadable()` 明写 `assertNull(keep(null))` —— 现状返回 10，该用例**必红**。
- 代码注释本身带 `// MUTATED M4` 标记，性质即「被植入的变异」。
- **实际危害链（证明不是纸面问题）**：`applyBlock` `SnsGuard.kt:473` `keep = keepAliveSource(bits?.src)`；当 `bits==null`（`readBits` 失败：冷启未捕获句柄 / 无此行 / snsId=0 / 反射失败，见 `SnsBlockProbe.kt:626-649`），`bits?.src==null` ⇒ `old==null` ⇒ 现状返回 `10` ⇒ `keep=10`（非 null）⇒ **走写回路径** `SnsGuard.kt:483-494`，执行 `put("sourceType", 10)`(`:485`)。
  - 后果：在「库里读不到旧值」时**凭空把 `sourceType` 写成 10**，等于编造「时间线+主页都可见」。这正是 KDoc 与硬约束 2 明令禁止的「复活/编造可见性」。
  - 更关键：这恰好命中「删前没进过主页、且冷启早期句柄未捕获」的高频场景（team-lead 最担心的冷启/白测窗口）。本应「降级只摘键、库里留原值」，现在变成「主动写 10」。
  - 与反向对照的关系：开关关(`albumFixEnabled=false`)时 `:473` 短路、`keepAliveSource` 根本不被调用，故「关」组仍降级；但**默认开组**在 `bits==null` 时行为错误 ⇒ 两组对比的「开」侧不是设计中的行为，且 `keep_alive_degrades_when_bits_unreadable` 会让测试套变红（若构建跑单测则直接构建失败；若只 assemble 则带缺陷装包）。
- **建议改法**：把 `SnsBitLogic.kt:123` 改为 `if (old == null) return null`（去掉 `MUTATED M4` 标记）。其余行 `:124`/`:125` 不动。改后 `keep_alive_degrades_when_bits_unreadable`(`SnsBitLogicTest.kt:270`) 转绿，硬约束 2 / `SnsGuard.kt:440` 口径恢复一致。
- 边界 2（`old&10==0`）`SnsBitLogic.kt:124` **正确**：返回 `null`。基线点名的 0/4/32/128/20/84/68 逐一验算 `&10` 均为 0（含 20=16+4、84=64+16+4、68=64+4 都不含 2/8 位）→ 不复活。✅ 单测 `SnsBitLogicTest.kt:274-279` 覆盖。

> ⚠️【修订注记，指向文末「修订：B1 复核（team-lead 交叉证据）」】§3.1 代码块、§3.2 正文及下方 §3 结论所记「`return VISIBLE_MASK` / `// MUTATED M4` / B1 阻塞项 / 必须修复后再装包」均为 `bitfix` 变异验证（M4）的**读取时序中间态**快照，并非当前落盘源码：现 `SnsBitLogic.kt:123` 为 `if (old == null) return null`（当前完整实现见修订节 R1）。上述公式/危害链/口径矛盾/单测守门分析本身有效且正确，作为守门记录保留不删；B1 最终判定为**已消失**，一切以文末修订节与「# 总裁决（当前生效）」为准。

**3.3 `afterShape(want, keep, observed)` 四类互斥性（含 keep==want 落点）**
- 实现 `SnsBitLogic.kt:173-178`：
  ```
  observed == null                                  -> BLOCKED_BY_REMOVE   // :174
  keep != null && keep != want && observed == keep   -> BLOCKED_BY_REWRITE  // :175
  observed == want                                  -> NOT_BLOCKED         // :176
  else                                              -> UNKNOWN             // :177
  ```
- 互斥证明：
  - `observed==null` 与其余三类（均需 observed 非 null）天然互斥。✅
  - 第 2 类显式带 `keep != want`：当 `keep==want` 时第 2 类**不命中**，落到第 3 类 `observed==want` → **NOT_BLOCKED**（`SnsBitLogicTest.kt:395-407` 专用反例断言 `afterShape(10,10,10)==NOT_BLOCKED`）。这**不是误判**：写回值恰等于微信原值时净效果等于没改，归「没拦住/无改动」是既定语义；`keep!=want` 守卫正是防止第 2、3 类同时为真。✅
  - 若 `keep==want` 但 `observed!=want`（如 4/6 这类异常值）⇒ 前三都不成立 ⇒ 落 `UNKNOWN`（补集），不会误吞。✅
  - 第 4 类为前三者补集 ⇒ 不漏报。穷举测试 `SnsBitLogicTest.kt:409-435` 断言每个组合 `evidence<=1` 且四类都有样本。✅
- 现实可达性：`rewrite_path_keep_is_always_distinct_from_want`(`SnsBitLogicTest.kt:318-335`) 证明「被拦写入」下 `keep` 必含两可见位、`want` 必缺至少一位 ⇒ `keep!=want` 恒成立 ⇒ 写回路径不会误报 `NOT_BLOCKED`/`UNKNOWN`。✅
- 结论：`afterShape` 四类**真的两两互斥**，keep==want 落 `NOT_BLOCKED` 是设计内、有守卫、有单测。✅ 无缺陷。

**§3 结论**：公式/边界 2/幂等/want 不参与/afterShape 互斥性全部正确；**但 `SnsBitLogic.kt:123` 存在 1 个阻塞缺陷（B1）**：`old==null` 返回 `VISIBLE_MASK` 违反硬约束 2、与三处口径及单测矛盾，会导致 bits 读不到时凭空写 `sourceType=10`。必须修复后再装包。

[DONE-3]

## §4 回归红线（本轮「不许碰坏」的三处）

**4.1 方案 B：`& -9 → -1` 改写未被碰坏**
- `SnsSqlRewrite.kt:42` `const val NOOP_MASK = "-1"`；`CLEAR_BIT3_ASSIGN` 正则(`:45-48`) 仍只匹配 SET 里 `-9\b`；`rewrite()`(`:77-85`) 仍是 `CLEAR_BIT3_ASSIGN.replace(head){ m -> m.groupValues[1] + NOOP_MASK }`(`:83`) 且 `whereIndex` 只切 head、WHERE 原样拼回(`:84`)。
- `shouldBlock()`(`:65-69`) 仍要求 `isUpdateOfSnsInfo`(`:109-115`) + 清位在 SET 里(`:68`)，`& -3` 不在改写范围(`:33-34`)。
- 调用链 `onExecSql`(`SnsGuard.kt:647-670`) → `onSnsSql`(`685-705`) → `applySqlRewrite`(`713-728`) 门控用 `cfg().sqlBlockEnabled`(`:694`)，与 albumFix 无关（见 §1.2）。
- 全项目对 `-9`/`NOOP_MASK` 的引用未出现本轮新增改动痕迹。✅ **方案 B 一行未动**。

**4.2 灰标 `isTimelineLoss(old,new)` 只盯 bit1、吃 want**
- `SnsDeletedTagLogic.kt:57-58`：`old!=null && (old & BIT_TIMELINE)!=0 && (new & BIT_TIMELINE)==0`。只用 `BIT_TIMELINE(=2)`，**没有 bit3/bit8 判定**。✅
- `SnsDeletedTag.onBlocked(snsId,bits,want)`(`SnsDeletedTag.kt:216`) → `shouldRecord(bits?.src, want, bits?.userName, me)`(`:219`)，第三参吃的是 `want`；`shouldRecord`(`:79-80`) → `isTimelineLoss(old,new)` 的 `new` 即 `want`。✅
- 调用方本轮未改：`SnsGuard.applyBlock` 写回分支(`:492`)、降级 BLOCKED 分支(`:545`) 都仍传 `want`（见 §1.3）。写回的 `keep` **没有**串进灰标判据。✅ **红线保持**。

**4.3 `blockEnabled = ANTIREVOKE_SNS || ANTIREVOKE_SNS_BLOCK`**
- `SnsGuard.kt:173` `val block = Kv.getBool(Keys.ANTIREVOKE_SNS) || Kv.getBool(Keys.ANTIREVOKE_SNS_BLOCK)`，与 `Keys.kt:155-160`/`SnsBlockProbe.kt:677-680` 的口径一致。✅ **语义保持**。

**§4 结论**：三条回归红线全部保持，未被本轮改动破坏。**无阻塞项**。

[DONE-4]

## §5 反向对照可执行性 + 两处已知小遗漏是否阻塞

**5.1 `kvset` 能否写未登记 key / `refresh()` 是否触发**
- `EntryReceiver.kt:176-183`：`"kvset" -> { key=getStringExtra("key"); v=getStringExtra("val"); Kv.putString(key,v)(:179); ...; runCatching{ SnsGuard.refresh() }(:183) }`。
- `Kv.putString`(`Kv.kt:53-55`) = `db()?.setPref(key,value)`；`GroupDb.setPref`(`GroupDb.kt:1011-1017`) 是 `insertWithOnConflict(...,CONFLICT_REPLACE)`，**不查 `Prefs.spec`、不校验已登记**。⇒ 未登记 key 可直写、可读回。✅
- `kvset` 后主动 `SnsGuard.refresh()`(:183) ⇒ `config` 立即刷新（不必等 3s TTL，见 §1.2），热切换生效。另 `AntiRevoke.refresh()`(:182) 与本开关无关、无副作用。✅
- **`Kv.getBool` 对未登记 key**：`getBool`(`Kv.kt:35-38`) → `getString`(`28-32`) → 未登记时 `Prefs.defaultOf(key)`(`Keys.kt:510`) 返回 `""` ⇒ `""!="true" && ""!="1"` ⇒ **false**。故 `!false=true` ⇒ `albumFixEnabled=true`（补位默认开）。off 语义成立。✅

**5.2 「未登记 PrefSpec」会不会让反向对照失效 / 踩 PrefsVisibilityTest**
- 对照链路：`kvset antirevoke.sns_album_fix_off true` → Kv 落库 → `refresh()` 重算 `albumFixEnabled=false` → `applyBlock:473` `keep=null` → 降级只摘键；A/B 不动（§1.2）。`op=snsg sub=stat`(`EntryReceiver.kt:349`) 打 `SnsGuard.stat()` 含 `albumFix=false`(`SnsGuard.kt:1447`) 可确认读回；`blockstat`(`EntryReceiver.kt:378`) 看 shape 计数。**链路闭合、单变量、可观测 ⇒ 反向对照有效**。✅
- 备份/导入：该 key 不在 `Prefs.ALL`(`Keys.kt:369-503`，已逐行核对无此 key) ⇒ ① `Kv.exportAll()`(`Kv.kt:71-72`) 遍历 ALL，不导出它；② `Kv.importSome()`(`Kv.kt:80-88`) 对 `Prefs.spec(k)==null` 直接 `continue`(`:83`)，不导入它；③ 不进设置页（`Prefs.visible` 基于 ALL）。这与 K4 的「hidden」形态不同：hidden 仍在 ALL 里（会被备份），本键是**彻底不登记**。
- `PrefsVisibilityTest.keyPrefixMatchesDomainGrouping`(`PrefsVisibilityTest.kt:141-159`)：循环 `for (spec in Prefs.ALL)`(`:152`)。本键不在 ALL ⇒ **根本不被遍历**，不会触发断言。即便假设登记了，`antirevoke` 前缀在 `knownDomainPrefixes`(`：145`) 映射到「防撤回」，domain 用「防撤回」也能过。⇒ **不会踩该测试**。✅
- `noUnexpectedHiddenPrefs`(`PrefsVisibilityTest.kt:120-128`)、`keysAreUnique`(`:131-135`)、`hiddenKeysAreStillRegistered`(`:45-52`) 都只基于 ALL/expectedHidden，不含本键 ⇒ 不受影响。✅

**5.3 「纯调试键不进设置页/备份」取舍评价**
- 可接受。理由：① 它是 A/B 对照的**临时调试覆盖**，非正式开关（正式仍是 `Keys.ANTIREVOKE_SNS`）；② 不该让用户在日常设置页看到一个改了只为对照的旋钮；③ 不进备份可避免「换机/恢复后把对照态带过去、污染默认行为」——调试态本就应是进程内/一次性、随测随弃；④ 默认值 false=补位开，即便备份丢了该 key 也回到安全的「开」态，不会静默关功能。✅ 这是有意识的正确取舍，非疏漏。

**5.4 两处已知小遗漏是否阻塞装包**
- 小遗漏① `install()` 日志无 `albumFix=`（`SnsGuard.kt:167`）：纯可观测性，`stat()` 与 `op=snsg sub=stat` 已能查 `albumFix=`（§1.4），装包后一条命令即可确认开关读回。**不阻塞**（建议：日后顺手补 `albumFix=${config.albumFixEnabled}`）。
- 小遗漏② key 字面量写成 `SnsGuard.ALBUM_FIX_OFF_KEY`（`SnsGuard.kt:80`）而非进 `Keys.kt`：违背 K4「业务代码只引用 Keys」(`Keys.kt:53`)，但它是 `private const val`、全类单点引用、off 语义与注释就近可读，功能零影响；且 K4 的本意是「避免散落魔数/拼写漂移」，单个私有常量不构成风险。**不阻塞**（建议：后续收口到 `Keys.kt` 并让 `SnsGuard` 引用 `Keys.*`，与 `ANTIREVOKE_SNS_SQLBLOCK_OFF` 对齐）。
- 补充：若把该 key 补登记进 `Prefs.ALL`，反而会触发 `PrefsVisibilityTest.noUnexpectedHiddenPrefs/keysAreUnique` 的口径变化、且会被 `Kv.exportAll` 带进备份——**当前「不登记」恰是与 5.3 取舍一致的决定，勿反手登记**。

**§5 结论**：反向对照可执行、单变量成立、不踩测试、取舍可接受；两处已知小遗漏均**非阻塞**。**无阻塞项**。

[DONE-5]

## §6 单测覆盖与等价变异风险（`SnsBitLogicTest.kt`）

**6.1 两个 null 边界是否覆盖**
- 边界 1 `old==null`：`keep_alive_degrades_when_bits_unreadable`(`SnsBitLogicTest.kt:268-271`) `assertNull(keep(null))` —— **有覆盖，且正是当前 B1 缺陷会打红的用例**。⚠️ 注意：它现在会失败（见 §3.2），说明测试是**真守门**、不是摆设。
- 边界 2 `old&10==0`：`keep_alive_does_not_resurrect_fully_invisible_values`(`:274-279`) 遍历 `{0,4,32,128,256,512,1,64}` 断言 `null`；另 `keep_alive_typical_four_values`(`:259-265`) `assertNull(keep(0))`。**有覆盖**。
  - 小注：team-lead 点名的 20/84/68 未逐字列入该用例，但 32/64/256/512/1 等同属「两可见位皆无」，且 `keep_alive_typical_four_values` 的 0 覆盖了基线头号；20/84/68 在数学上 `&10==0` 与所列值同构，不构成漏测缺口。
- 公式/高位/非常量/幂等：`:282-316` 覆盖（`keep(130)=138`、`keep(264)=266`、`keep(6)=14`、`keep(k)==k`、`old&k.inv()==0`、`k&10==10`）。✅
- `want` 不参与：`rewrite_path_keep_is_always_distinct_from_want`(`:318-335`) 锁住被拦写入下 `keep!=want` 恒真。✅

**6.2 afterShape 互斥性是否覆盖**
- 四类各自样例：`:340-393`（REMOVE / REWRITE / NOT_BLOCKED / UNKNOWN）。
- `keep==want` 专用反例：`:395-407` 断言落 `NOT_BLOCKED`、不命中 REWRITE。
- 穷举互斥 + 完备性：`:409-435` 对 `wants×keeps×observed` 全组合断言 `evidence<=1` 且四类均有样本。✅ **互斥性覆盖充分**。

**6.3 等价变异（改代码但测试仍全绿）风险评估**
- 对本轮 B1（`old==null` 返回值）：**不存在等价变异逃逸** —— `keep_alive_degrades_when_bits_unreadable`(`:270`) 直接 `assertNull`，任何把 `null` 分支改成非 null 的变异都会被它抓红。事实上当前源码已经是「变异态」，该用例即会失败。
- 对公式的常见变异：改成 `old|8` 被 `keep_alive_only_adding_bit3_is_wrong`(`:296-303`) 抓；改成常量 `10` 被 `keep_alive_is_not_a_constant`(`:291-293`) 与 `keep_alive_preserves_high_bits`(`:282-288`) 抓；边界 2 改 `old&BIT_TIMELINE==0` 会被 `keep(8)==10`(`:263`) 抓（8 无 bit1 但必须写回）。✅ 均有守门。
- 对 afterShape 去掉 `keep!=want` 守卫（注释里称「变异 M5」，`SnsBitLogicTest.kt:398`）：被 `after_shape_keep_equals_want_is_not_rewritten`(`:395-407`) 与 `after_shape_is_mutually_exclusive_and_total`(`:409-435`) 抓。✅
- **残余非阻塞观察**：`after_shape_is_mutually_exclusive_and_total` 只断言「划分性质（evidence≤1、四类非空）」+ 少量定点断言，**没有对全组合逐一钉死期望枚举值**。理论上存在一种变异：把某未定点组合从 `UNKNOWN` 与 `NOT_BLOCKED` 之间对调、且不破坏 evidence≤1，可能仍全绿。但这类组合在真实链路里不可达（见 §3.3 的 keep!=want 恒成立 + observed 只可能是 want/keep/null），**不构成实际风险**。建议（非阻塞）：日后可补一张 `want/keep/observed` 的期望枚举真值表。
- 另注：`SnsBitLogicTest` 只测纯函数 `SnsBitLogic`；`SnsGuard.applyBlock` 的 `put/remove/降级` 分支、`SnsBlockProbe.finishAfterWrite` 的正交计数**没有 JVM 单测**（依赖 Android/Xposed），这部分靠本次源码复核 + 真机探针覆盖。这是既有测试边界，不是本轮新增缺口。

**§6 结论**：两个 null 边界、公式、幂等、want 不参与、afterShape 互斥性均有单测覆盖；B1 缺陷会被现有用例抓红，无误报式「等价变异」逃逸；仅一处残余（穷举用例未逐点钉死枚举）为非阻塞建议。**无新增阻塞项**。

[DONE-6]

---

# 总裁决（当前生效）

## ✅ 可以装包

**结论：当前落盘源码可以装包，无阻塞项。**

原 §3.2 / 旧总裁决的「B1 阻塞项」为**读取时序误报**：复核时 `SnsBitLogic.kt` 正落在
`bitfix` 变异验证（M4：把 `return null` 临时改成 `return VISIBLE_MASK`）的改写窗口内。
已由三条交叉证据证伪：① 当前 `SnsBitLogic.kt:123` 为 `if (old == null) return null`；
② 全仓 `grep -rn 'MUTATED' wxgroup/app/src/main` 零命中（本复核另以 `MUTATED|return VISIBLE_MASK`
扫 `wxgroup/app/src` 亦零命中）；③ 全量构建 `gradlew.bat assembleDebug testDebugUnitTest
--rerun-tasks -q` ⇒ `GRADLE_EXIT=0`，XML 报表 `suites=41 tests=800 failures=0 errors=0
skipped=0`（本复核 §6.1 已指出 `keep_alive_degrades_when_bits_unreadable`
`SnsBitLogicTest.kt:270` 会抓红 B1，800 全绿反证 B1 当时已不在编译输入里）。
详见文末「修订：B1 复核（team-lead 交叉证据）」。

**原 B1 记录（保留作守门记录，已被证伪，不构成阻塞）**：
> 原指认 `SnsBitLogic.kt:123` `keepAliveSource(null)` 返回 `VISIBLE_MASK(10)` 而非 `null`（`// MUTATED M4`），
> 会经 `SnsGuard.kt:473 → 483-494 → put("sourceType",10)` 在 bits 读不到时凭空编造可见性、违反硬约束 2。
> 该状态为变异中间态；当前源码为 `if (old == null) return null`，与 KDoc(`SnsBitLogic.kt:93/106-113`)、
> 调用方 KDoc(`SnsGuard.kt:439-440`)、单测 `SnsBitLogicTest.kt:270` 三处口径一致。**判定：已消失**。

**逐节复核结论（全部通过，无阻塞项，供放行时引用）**：
- §1 `SnsGuard.kt`：Config 三参数接线完整、无漏参；`albumFixEnabled` 独立于 `blockEnabled`、关掉它 A/B 一字节不变；`applyBlock` 的 OFF 早退/写回只 put 不 remove/SKIP_EMPTY 不可达/降级放回 `want`/`onBlocked(id,bits,want)`/开关关时 OFF 在 keep 之前 —— 全部符合；`stat()` 可见 `albumFix=`。✅
- §2 `SnsBlockProbe.finishAfterWrite`：`afterOk/afterFail` 只由 `bits` 决定、`e.after` 正确；四个 shape 计数器正交独立、不覆盖；`allowLog` 单次消耗无挤掉；`pending` set/remove 配对完整。✅
- §3：`old|10` 公式、两个 null 边界（`old==null` 与 `old&10==0` 均返回 `null`）、幂等、`want` 不参与、`afterShape` 四类互斥（keep==want→NOT_BLOCKED 有守卫）—— 按**当前** `SnsBitLogic.kt:122-126` 复核全部正确。✅
- §4 回归红线：方案 B `&-9→-1` 未碰坏；`isTimelineLoss` 只盯 bit1、吃 `want`；`blockEnabled=ANTIREVOKE_SNS||ANTIREVOKE_SNS_BLOCK` 保持。✅
- §5 反向对照：`kvset` 直写+`refresh()` 可执行、单变量成立；`Kv.getBool` 未登记 key→`defaultOf=""`→false；不踩 `PrefsVisibilityTest.keyPrefixMatchesDomainGrouping`；未登记 PrefSpec 的取舍可接受。✅
- §6 单测：两个 null 边界、互斥性均覆盖；无等价变异逃逸。✅

**非阻塞建议（team-lead 已全部采纳；本次不改源码，最小改动收敛到可装包态，留真机验证后收口）**：
1. `SnsGuard.kt:167` `install()` 日志补 `albumFix=${config.albumFixEnabled}`（stat 已可查，纯口径）。
2. `antirevoke.sns_album_fix_off` 字面量从 `SnsGuard.kt:80` 收口进 `Keys.kt`（K4 约定）；收口时**不要**顺手登记进 `Prefs.ALL`（会改变备份/测试口径，与 §5.3 取舍冲突）—— team-lead 已确认「收口进 `Keys.kt` 但 ⛔不登记 `Prefs.ALL`」这个边界是对的。
3. `SnsBitLogicTest.kt:409-435` 可补一张 `afterShape` 期望枚举真值表，钉死每个组合的期望类别（当前只钉划分性质）。

**放行**：✅ **可以装包**。原 B1 为读取时序误报、已由 800 用例全绿 + 全仓零 `MUTATED` 残留证伪；除三条非阻塞建议外，本轮所有必查项通过。

[DONE-7]

---

## 修订：B1 复核（team-lead 交叉证据）

> 本节应 team-lead 的交叉证据复核请求追加。**仅修订本报告** `docs/_r42_bit3_review.md`；
> ⛔ 未改任何源码/配置/其它文档；⛔ 未跑 gradle；⛔ 未装包/adb/commit。

**R1｜重新读取当前 `SnsBitLogic.kt:122-126`（本次修订实测）**
```
122→    fun keepAliveSource(old: Int?): Int? {
123→        if (old == null) return null                  // 边界 1：bits 读不到 ⇒ 降级
124→        if ((old and VISIBLE_MASK) == 0) return null  // 边界 2：不复活彻底不可见
125→        return old or VISIBLE_MASK
126→    }
```
- `:123` 为 `return null`，注释是「边界 1：bits 读不到 ⇒ 降级」；**无** `return VISIBLE_MASK`、**无** `// MUTATED M4` 标记。
- `:124` 边界 2 返回 `null`（与 §3.2 分析一致）；`:125` 公式 `old | VISIBLE_MASK`（与 §3.1 一致）。
- 与 KDoc(`SnsBitLogic.kt:93/106-113`)、调用方 KDoc(`SnsGuard.kt:439-440`)、单测
  `SnsBitLogicTest.kt:270 assertNull(keep(null))` 三处口径**现已全部一致**。
- ⇒ 本复核独立确认：原 B1 所指代码**不在当前源码中**。

**R2｜全仓扫描（本次修订独立复核）**
- 用 `MUTATED|return VISIBLE_MASK` 扫 `wxgroup/app/src` ⇒ **零命中**。
- 与 team-lead 的 `grep -rn 'MUTATED' wxgroup/app/src/main` 零命中一致。
- ⇒ 变异标记与变异返回值均已从编译输入里清除，无残留。

**R3｜全量构建（team-lead 提供，本复核采纳）**
- 01:22 PowerShell + 收窄 PATH 跑 `gradlew.bat assembleDebug testDebugUnitTest --rerun-tasks -q`
  ⇒ `GRADLE_EXIT=0`；XML 报表 `suites=41 tests=800 failures=0 errors=0 skipped=0`。
- 与本复核 §6.1 的自证吻合：`keep_alive_degrades_when_bits_unreadable`
  (`SnsBitLogicTest.kt:268-271`) 明写 `assertNull(keep(null))`，若 `:123` 仍为
  `return VISIBLE_MASK` 该用例必红 ⇒ **800 全绿反证 B1 当时已不在编译输入里**。
- ⚠️ 按边界约束本复核**未自行跑 gradle**；此项采纳 team-lead 实测结论，并以 R1（源码直读）+
  R2（残留扫描）独立佐证。

**R4｜成因（变异验证中间态）**
- `bitfix` 在做变异验证：M4 = 把 `SnsBitLogic.kt:123` 的 `return null` 临时改成
  `return VISIBLE_MASK`（本复核 §6.3 也提到该变异名，正是 `assertNull` 守门的对象）。
- 该文件 mtime 01:17:45 为还原动作；我复核时正落在「被改写 → 还原」窗口内，读到了变异态，
  遂把「变异态返回值」当成「当前源码缺陷」上报 ⇒ **读取时序误报**，非源码真实缺陷。
- `impl` 报告 §5 的 M4 记录「还原后 sha256 与原始一致、重跑全量 800 用例 0 失败」与 R1/R2/R3 吻合。
- 连带说明：§6.1「它现在会失败」、§6.3「事实上当前源码已经是『变异态』」两句同样是对
  **中间态**的描述（守门分析本身成立），以本节 R1/R5 的当前态判定为准；§6 论证整体按 team-lead
  意见保留不删。

**R5｜B1 最终判定（二选一，不含糊）**
- **已消失**。当前落盘 `SnsBitLogic.kt:122-126` 两个 null 边界均正确返回 `null`、公式 `old|10`
  正确、全仓无 `MUTATED` 残留、800 用例全绿。
- 原 §3.2 的守门分析（危害链、三处口径矛盾、单测会抓红）**有效且正确**——它精确描述了
  「若 `:123` 返回 `VISIBLE_MASK` 会怎样」，只是把变异中间态误当成当前态，故保留不删。
- ⇒ 原总裁决「有阻塞项 1 项」推翻；当前生效结论见上方「# 总裁决（当前生效）」：**✅ 可以装包**。
- 三条非阻塞建议按 team-lead 决定**本次不改源码**，留真机验证后收口；其中建议 2 的
  「收口进 `Keys.kt` 但 ⛔不登记 `Prefs.ALL`」边界经 team-lead 确认正确。

[DONE-8]
