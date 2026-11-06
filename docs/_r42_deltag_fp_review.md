# R42 · 灰标误报修复 —— 装包前第二视角独立复核

> 档位=Pro 依据=装包前交叉复核（判据设计 + Hook 链路 + 回归红线）
> 复核员：tagreview（独立第二视角，未参与实现）
> 日期：2026-11-07
> 性质：**只读复核**。未改任何源码 / 测试 / 配置，未装包、未 adb、未 commit，唯一写入本文件。
> 被复核对象：`docs/_r42_deltag_false_positive.md` [DONE-1]~[DONE-7] 的实现与自报结论、3 个改动源码文件、构建产物。
> 裁决口径：每条结论带 `文件:行号`；禁止「看起来没问题」。

---

## [DONE-1] 复核范围 + 基线

### 实际读过的文件 / 行（不是转述报告）

| 类别 | 文件 | 范围 |
|---|---|---|
| 改动源码 | `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsDeletedTag.kt` | 全文 942 行 |
| 改动源码 | `.../feature/SnsDeletedTagLogic.kt` | 全文 289 行 |
| 改动单测 | `.../test/java/com/lx/wxgroup/feature/SnsDeletedTagLogicTest.kt` | 全文 547 行，51 个 `@Test` |
| 一手证据 | `wxgroup/_cache/ui_fp.xml` | 原始 XML，逐 node 核对 `bounds` / `text` / `resource-id` |
| 改前依据 | `docs/_r42_deltag_report.md` | `:218-262`（ambig2→pickOffset 改动）、`:352-400`（误报现场） |
| 实现报告 | `docs/_r42_deltag_false_positive.md` | 全文 |
| 受保护 | `res/values/ids.xml`、`SnsBitLogic.kt`、`SnsGuard.kt:470-560`、`SnsBlockProbe.kt`、`GroupDb.kt`、`Keys.kt`、`entry/EntryReceiver.kt` | mtime + sha + 关键行 |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk`、`app/build/test-results/testDebugUnitTest/TEST-*.xml` | 时间戳 + 用例数 |

### 基线（独立复算，非转述）

| 项 | 实测 | 与实现报告比对 |
|---|---|---|
| 3 个改动文件 SHA256 | `f927d922…d9` / `705bcf46…fe` / `f68e830a…92` | 与 `[DONE-6]:384-386` 声明的还原基线**逐字一致** ⇒ 变异还原后状态可信，本复核读的就是最终版源码 |
| 3 文件 mtime | `02:10:16` / `02:26:21` / `02:11:27` | 均早于 APK |
| APK | `2026-11-07 02:27:00` / 2,898,530 bytes | 晚于源码最晚 `02:26:21` ⇒ APK 含最终源码 |
| 独立全量单测 | `41 XML / 812 例 / 0 失败 / 0 错误 / 0 跳过`，本类 51 例 | 与报告一致，详见 [DONE-6] |
| 主源码 class 新鲜度 | `SnsDeletedTag.class` / `SnsDeletedTagLogic.class` = `02:36:56`（本次运行） | ⇒ 本次 `--rerun-tasks` 确实重编译了 main，非空跑单测 |

**受保护文件 mtime（全部早于本轮 ~02:00 起，⇒ 本轮未触碰）**：

| 文件 | mtime |
|---|---|
| `SnsGuard.kt` | `2026-11-07 01:10:25` |
| `SnsBlockProbe.kt` | `2026-11-07 01:08:07` |
| `SnsBitLogic.kt` | `2026-11-07 01:17:45` |
| `GroupDb.kt` | `2026-11-06 22:34:42` |
| `Keys.kt` | `2026-11-06 23:07:22` |
| `entry/EntryReceiver.kt` | `2026-11-07 00:19:29` |
| `res/values/ids.xml` | `2026-11-06 23:57:58` |

> `git status` 显示 `SnsGuard/SnsBlockProbe/SnsBitLogic/GroupDb/Keys/EntryReceiver` 为 `M` —— 那是**bit3 轮**相对 HEAD 的既有改动，mtime 全在本轮之前，与本轮无关。
> `ALBUM_FIX_OFF_KEY = "antirevoke.sns_album_fix_off"` 仍在 `SnsGuard.kt:80/180`，3 个改动文件内无该 key 引用。

### 复核边界（诚实声明）

3 个改动文件在 git 中均为 **`??` 未跟踪**、无历史 commit 可 `diff` ⇒ **拿不到字面「改前 `resolve()`」副本**。本复核的「改前」依据 `docs/_r42_deltag_report.md:241-250` 的改法说明 + 现仍保留的 `SnsDeletedTagLogic.pickOffset`（`SnsDeletedTagLogic.kt:171-176`）重建，并在 [DONE-2] 用原始 UI dump 反证其自洽性。这是本次复核唯一无法做字面 diff 的部分。

---

## [DONE-2] 必查项 1 · 根因链是否成立

**结论：成立。「封面头全局固定错位 O + 同名相邻 ⇒ d=0 假命中」能完整解释误报与 `match/hits 1→7`；「View 复用残留不是成因」论证站得住。未发现另有其它根因。**

### 改前 `resolve()`（重建，依据 `docs/_r42_deltag_report.md:241-250`）

窗口 `order = [0,1,-1,2,-2]`，对每个 `d` 取 `infoAt(list, pos+d)` 过 `SnsDeletedTagLogic.nameOk`（`SnsDeletedTagLogic.kt:151-158`，只认作者名的双向 `contains`）；`pickOffset`（`:171-176`）按传入顺序返回**第一个** `ok=true`；`offsets` key 含 `list.size`，缓存命中先验直接 return、仍过一次 `nameOk` 护栏。

### 用原始 UI dump 反证「错位 O≠0」（本复核新增的交叉验证，实现报告只用了 miss 日志推断）

`wxgroup/_cache/ui_fp.xml`：RecyclerView `com.tencent.mm:id/hzd` 视口 `[0,0][1440,3104]`，4 个直接子项按 adapter position 递增：

| pos | bounds | 内容 |
|---|---|---|
| 0 | `[0,0][1440,657]` | 封面（`V` / 换封面 / 头像） |
| 1 | `[0,657][1440,1830]` | verity / `1分钟前` / 灰标 `[259,1742][1440,1800]` / 分隔线 `[0,1829][1440,1830]` |
| 2 | `[0,1830][1440,3003]` | verity / `8分钟前` / 灰标 `[259,2915][1440,2973]` / 分隔线 `[0,3002][1440,3003]` |
| 3 | `[0,3003][1440,3104]` | `万华化学—李思远pc` `[251,3011][1379,3140]`（**部分可见**） |

两条灰标都是 `resource-id=""` 的裸 TextView、左边界 `x=259`（与 `MARK_LEFT_DP=72`，`SnsDeletedTag.kt:96` 同款）⇒ 均是本模块 `ensureMark` 主动 `addView` 插的。

**关键反证**：若 `dataList`（`getData()`，`SnsDeletedTag.kt:465-477`）**含**封面项，则 O=0，新动态 pos1 的 `d=0` 就是它自己 ⇒ `pickOffset` 取 0 ⇒ snsId 不在台账 ⇒ **不会产生误报**。真机确实误报 ⇒ **O 必不为 0**；又 `miss` 日志 `pos=0/10 texts=[轻触更换封面|换封面|V]` 证明 pos0 是封面且 `dataList` 不含它 ⇒ **O = -1**（view pos P ↔ `dataList[P-1]`）。这条不靠 A0 的「文档写了错位」，是从「误报存在」反推出来的。

### 逐格推演（O=-1，改前）

- **新动态 pos1**：`d=0`→`dataList[1]`=真·被删 verity（护栏过）、`d=-1`→`dataList[0]`=新 verity（护栏过）、`d=1`→`dataList[2]`=万华（不过）、`d=-2` 越界 ⇒ `passing=[0,-1]` ⇒ `pickOffset` 取 **0** ⇒ 拿到**相邻被删动态**的 snsId ⇒ 在 `sns_deleted` 表 ⇒ `applyMark` SHOW（`SnsDeletedTag.kt:403`→`applyMark`）⇒ **y=1742 误报**。✅
- **被删动态 pos2**：`d=-1`→自己（过）、`d=-2`→新 verity（过）、`d=0`→万华（不过）⇒ `passing=[-1,-2]` ⇒ `pickOffset` 取 **-1** ⇒ 自己 ⇒ SHOW ⇒ **y=2915 正确**。✅

两格各挂一标、同时在屏、正文分别 `1`/`2` —— 与 dump 逐条吻合。「同名相邻 ⇒ `d=0` 假命中」解释力完整。

### `match/hits 1→7` 的解释

旧 `offsets` key 含 `list.size`（`docs/_r42_deltag_report.md:247`），新发一条使 size 变化 ⇒ 缓存失效 ⇒ 每次绑定都重新「`0` 优先」猜偏移；`binds 19→39`（+20 次重绑）里凡锚到那条唯一被删 id 的都 SHOW ⇒ `match`（`SnsDeletedTag.kt:653`，仅 SHOW 分支自增）与 `hits`（`:692`，仅 `addView` 成功自增）**同步 +6**。A5「放大器」成立。台账 `rows=5` 未变、`BLOCK` 零新增 ⇒ 记录层清白，与渲染层串位不矛盾。

### 「View 复用残留不是成因」—— 站得住（三条独立佐证）

1. **计数语义**：`match` 只在 `applyMark` 的 `MarkAction.SHOW` 分支自增（`:652-655`）、`hits` 只在 `addView` 成功自增（`:688-692`）。残留若成立，该次绑定必走 HIDE，**两者都不涨**；实测 +6/+6 ⇒ 6 次都是主动判定命中并真插了标。
2. **两个不同 itemView**：dump 里 y=1742 与 y=2915 两条灰标分属 `[0,657][1440,1830]` / `[0,1830][1440,3003]` 两块**同时可见**的不同卡片；「复用残留」的机制只能是**一个** View 被重绑、旧标没清，解释不了两个 View 同时各带一个模块插的标。
3. **新动态 snsId 不在台账**：一条不在 `sns_deleted` 的动态要带灰标，唯一路径是这次绑定 `snsId in deleted()` 判真（`SnsDeletedTag.kt:645`）⇒ SHOW ⇒ `ensureMark` 主动 `addView`；这正需要 `resolve()` 串位提供被删 id。
4. 负路径确实都在清标：`onBind` 的 `info==null` → `applyMark(view,0L,resolved=false)` → HIDE → `removeMark`（`:390-399`、`:656`）；`applyMark` 的 HIDE → `removeMark`（`:656`）；`ensureMark` 开头 `removeMark(root)`（`:670`）。实现报告 B 段 `:149-173` 的「三处 removeMark」在**改后**代码中成立。

**唯一诚实缺口（非阻塞）**：`onBind` 的 `view == null` 分支（`:384-388`）没有 `removeMark`。此时 `itemViewOf` 返回 null、拿不到 View 句柄，且真机无 `noitemview` 日志 ⇒ 非本次成因；但「负路径必清标」契约有这一个例外，实现报告 `:170-173` 已自认。

---

## [DONE-3] 必查项 2（⭐重点）· 保守降级会不会造成可观测回退

**结论：会漏标。实现方「本次屏有第三条非同名动态，不落边界」的说法不成立。这是必须先修的阻塞项。**

### 冷启首屏逐格推演（`learned` 从哪来、两条是不是都返回 null）

`offsets` 是 `object SnsDeletedTag` 的实例字段 `Collections.synchronizedMap(HashMap<String,Int>())`（`SnsDeletedTag.kt:488`），**进程冷启即为空**。LSPosed 模块装包后必须重启 / 强停微信才能加载新代码 ⇒ **冷启是默认测试条件，`learned = offsets[key]`（`:512`）必为 null**。

首次布局按 adapter position **递增**绑定（`onBindViewHolder` 是 `after` 钩子，`:351-369`；`LinearLayoutManager` 初布局自锚点 pos0 向下 `layoutChunk`）：

| 次序 | pos | 窗口 `passing`（推导同 [DONE-2]） | `anchorOffset(passing, learned=null)` | 结果 |
|---|---|---|---|---|
| 1 | 0 封面 | `texts=[轻触更换封面\|换封面\|V]`，5 个候选作者名全不匹配 ⇒ `[]` | `singleOrNull()` on empty ⇒ **null**（`SnsDeletedTagLogic.kt:198-201`） | `resolve` 返回 null（`:521-525`）→ HIDE。**封面项无法校准**（与真机 `miss pos=0` 吻合） |
| 2 | 1 新 verity | `[0, -1]` | 分支 3：歧义且无 learned ⇒ **null** | `applyMark(view,0L,resolved=false)` → HIDE → `removeMark`（`:398`）。新动态**不挂标**（正确） |
| 3 | 2 被删 verity | `[-1, -2]` | 分支 3 ⇒ **null** | HIDE → **真·被删动态的 y=2915 灰标不插 / 被清掉** ❌ |
| 4 | 3 万华化学 | `[-1]`（`texts` 只与同名那条对上） | 分支 2 `singleOrNull()` ⇒ **-1** | `passing.size==1` 且 `d!=0` ⇒ `offsets[key] = -1`（`:528-530`）**校准在这一条才发生** |

⇒ **前两条在 `learned` 为空时全部返回 null，8 分钟前那条真·被删动态的灰标会消失。**

### 为什么「屏上有第三条非同名动态」救不了首屏（实现方说法错在哪）

实现方把「屏上存在校准源」等同于「校准先于前两条发生」，二者**不等价**：

1. **校准是逐条绑定时发生的**（`:528-530` 在 `resolve()` 内、每次绑定末尾），不是 `install()` 时预扫描。`install()` 只调 `selfWxid()` + `refresh()`（`:287-288`），没有预校准。
2. 第三条在可见顺序里排**最后**，且初布局按 position 递增 ⇒ pos3 的绑定**必然晚于** pos1/pos2；它写入 `offsets` 时，前两条已经渲染成 HIDE。
3. **校准后没有任何自动重绘**：`resweep()` 全局只有 2 个调用点 —— `setStyle`（`:833`）与 `purge`（`:938`），已 grep 确认；`resolve()` 的校准分支（`:528-530`）不触发任何 `resweep` / 重绑。首轮 4 次绑定**全是 HIDE、零 `addView` 成功** ⇒ 视图树无变化 ⇒ 不会引发第二次 layout / binding pass。
4. 被判 HIDE 的 view 还被 `bound.remove(view)`（`:396`）移出了 `bound` 映射，而 `resweep()`（`:795-804`）只对 `bound` 里的 `(view, 旧snsId)` 做 `applyMark`、**不会重新 `resolve()`** ⇒ 即便后续有人调 `resweep()` 也补不回这两条。

⇒ 灰标要等**用户滚动**使 pos1/pos2 重新绑定才恢复（那时 `learned=-1` 且 `learned in passing` ⇒ 分支 1 沿用 -1 ⇒ 新动态仍 HIDE、被删动态 SHOW 恢复）。屏上有第三条只保证「滚一次能恢复」，**不保证首屏不漏标**。

### 严重性判定：**阻塞（必须先修 / 或必须先明确改测试规程并经 team-lead 确认）**

1. 直接违反实现方自己写的验收②「真·被删动态**仍然**挂灰标」（`docs/_r42_deltag_false_positive.md:418`）与 `[DONE-1]` 定义的基线对照「**y=2915 灰标保留**」（`:53-54`）—— 首屏观察即失败。
2. LSPosed 装包必重启微信 ⇒ 冷启 + `offsets` 空是**默认条件**，不是罕见边界。
3. 用户视角「首屏两条灰标全没了」= 整个功能回归，极易被判为装包失败，触发又一轮十几分钟真机白测（本次复核的初衷）。
4. **该风险未被实现方评估**：其「诚实边界」（`:277-280`）只覆盖「可见范围全是同一人、无非同名校准源」一种情形，并据此断言「本次屏不落在该边界」；完全没考虑「有非同名校准源、但它排在前两条之后才绑定」这一更常见的时序。报告 [DONE-7]:429 甚至把 `lastMiss` 出现 `ambig(...)` 直接写成「预期降级、不是新故障」，会掩盖首屏漏标。

**这是「漏标好过打错标」纪律用错了地方**：该纪律适用于「无法判定时宁可不打」（本例 pos1 新动态确实该不打），但 pos2 是**已有确定性结论**（它在台账里、且旧版本能正确标）的动态，把它一起降级掉，不是保守，是回归。

### 不改架构的最小缓解建议（三选一，推荐 A）

- **A（推荐，约 15 行，只在 `SnsDeletedTag.kt` 内，3 文件边界不变）**：把 `bound`（`:168-169`）的 value 从 `Long` 扩成轻量结构持有 `(adapterRef, pos, snsId)`（或另加 `pending: MutableMap<View, Pair<Any,Int>>`）；`onBind` 的 `info==null` 分支**不再** `bound.remove(view)`（`:396`），改为登记待复判；`resolve()` 校准成功处（`:528-530`，`d!=0` 且 `learned` 由空变值）**立即调一次 `resweep()`**，令 `resweep` 对 pending view 用其 `adapter/pos` **重新 `resolve()`** 后再 `applyMark`。
  - 效果：pos3 绑定完成校准的同一帧就把 pos2 补标、pos1 保持无标 ⇒ 首屏即满足验收①②，无需滚动。
  - 不动 `anchorOffset` / `markDecision` / `offsets` 键语义 / `removeMark` / 渲染链路；顺带把「校准 ⇒ 复判」做成可测的一步。
- **B（零代码，改测试规程）**：明确「装包后先上下滚动一次再取 UI dump 对照」，并把首屏漏标写进文档当预期。缺点：弱化了「y=2915 首屏保留」这条基线、可能掩盖真问题，用户仍会看到首屏闪失；**若选 B 必须由 team-lead 明确接受，不能默认**。
- **C（不推荐单独用）**：`install()` 时从 `Kv` 持久化上次校准的 O 以跨进程存活。风险是把别的页面 / 结构变化后的旧 O 带进新进程，与 [DONE-4] 的过期常数风险叠加。

---

## [DONE-4] 必查项 3 · `learned` 沿用是否安全

**结论：不安全。`learned in passing` 只证明「该偏移仍指向某个同名作者」，不证明「它是本格的正确偏移」；在同名相邻窗口里可能沿用过期常数，造成**新的错标**（比漏标更糟）。但触发条件较窄，判为「非阻塞但必须登记的高风险」。**

### 缓存形状

`offsets = Collections.synchronizedMap(HashMap<String, Int>())`（`SnsDeletedTag.kt:488`），key 仅 `adapter.javaClass.name`（`:511`），**全进程单值**。`learned = offsets[key]`（`:512`）；`anchorOffset` 分支 1 是 `if (learned != null && learned in passing) return learned`（`SnsDeletedTagLogic.kt:199`）。

### 护栏为什么不够（构造反例）

`passing` 的元素是「**通过 `nameOk` 作者名护栏**的偏移」（`:516-520`），而 `nameOk` 只比作者名（`SnsDeletedTagLogic.kt:151-158`）。在两条相邻同名动态的窗口里，`d=O`（自己）与 `d=O±1`（相邻同名）**都指向同名作者** ⇒ 都进 `passing`。于是：

设曾校准 `learned = -1`。若**头部项数量变化**（下拉刷新临时头、封面折叠/移除）或**另一个 SNS 页面复用同一 adapter 类但无封面头** ⇒ 真实 O 变为 0。此时某条 verity 动态窗口 `passing=[0,-1]`（`d=0` 是自己、`d=-1` 是相邻同名）：

- `learned = -1`，`-1 in passing` **成立** ⇒ 分支 1 返回 **-1**（`:199`）；
- `byOffset[-1]` = 相邻那条（若是真·被删动态）⇒ `markDecision(..., inDeleted=true)` ⇒ **SHOW ⇒ 新错标**；
- 且 `passing.size == 2` ⇒ `:528` 的 `if (passing.size == 1)` 不成立 ⇒ **不重新校准、错值持续**（`:526-530`）。

⇒ 「沿用过期常数导致新的错标」路径**确实存在**，且恰好命中本修复要解决的「同名相邻」场景。

### 能自愈的场景（公平地给出护栏有效的一面）

非同名动态窗口 `passing=[O]` 单元素：过期 `learned` 不在 `passing` ⇒ 落到分支 2 `singleOrNull()` = O（正确），且 `passing.size==1` ⇒ `:528-530` 重新写缓存（`d!=0`）或 `offsets.remove(key)`（`d==0`）⇒ **自愈**。所以风险集中在同名相邻窗口，不是全局必现。

### 本修复是放大还是收敛

旧 `offsets` key 含 `list.size`（`docs/_r42_deltag_report.md:247`）：虽是 A5「放大器」的元凶（新发一条就冲掉校准），却也顺带提供了「不同列表长度 / 不同页面」的**天然隔离**。新代码去掉 `list.size`（`:511`、KDoc `:480-487`）后，一个 adapter 类**全进程只认一个 O**：隔离消失、learned 更持久、跨页面串用成为可能。⇒ **去 size 修放大器本身是对的取舍，但确实放大了「跨上下文沿用过期 O」这一侧的风险**，实现报告的 KDoc 只论证了「与列表长度无关」，未论证「与页面 / 头部结构无关」。

### 同源风险：校准正确性全押在弱护栏上

`offsets` 的唯一写入条件是 `passing.size == 1`（`:528`），而 `passing` 完全由 `nameOk`（双向 `contains` + `textsOf` 全文语料，`:587-604`）决定。实现报告 `:281-284` 自己承认 `docs/_r37_name_anchor.md` 的更严「作者行类名限定」方案**当前未采用**。若某条动态正文里恰好出现另一个作者名、使窗口里「恰好只有一个通过」的其实是**错误**偏移，就会校准出错误 O，再经分支 1 全局传播 ⇒ 与上面同类的系统性错标。非本次必现，但与过期 O 同源，建议一并登记。

### 建议（非阻塞，下一轮）

1. 给分支 1 加二次确认，或把 `offsets` key 从「仅 adapter 类名」扩成「adapter 类名 + 头部项数量 / 页面标识」，让不同结构不共用常数；
2. 至少在 `stat()` 里把当前 `learned` 值打出来，真机一眼能看出 O 是否漂移；
3. 补一条「`learned` 过期 + 同名相邻」纯判据单测，明确当前行为是「沿用」还是「重校」，别让风险停在文档里。

---

## [DONE-5] 必查项 4 · HIDE 路径安全性 ／ 必查项 5 · 回归红线

### 项 4：HIDE 路径 —— 结论：安全

**`removeMark(view)` 绝不误删微信原生子 View。** `SnsDeletedTag.kt:717-731`：先 `view as? ViewGroup ?: return`，递归整棵子树，移除条件**严格**是

```
runCatching { c.getTag(MARK_KEY) }.getOrNull() == MARK_MAGIC    // :723
```

`MARK_KEY = R.id.wxgroup_sns_mark`（`:107`）、`MARK_MAGIC = "wxgroup.sns.deleted.line"`（`:110`）；模块**只**在 `ensureMark` 里对自己 new 的 `TextView` 执行 `tv.setTag(MARK_KEY, MARK_MAGIC)`（`:689`）。微信原生 View 从不带这个 key ⇒ `getTag` 返回 null ⇒ 不移除。`getChildAt` / `getTag` / `removeViewAt` 全包 `runCatching`（`:722-724`），异常不外溢到 `bindHook`。命中标记的分支 `continue`（`:725`）不再下钻，逻辑干净。✅

**`applyMark(view, 0L, resolved=false)` 清标不会误删「本应显示」的灰标。** 解析失败意味着**这次绑定拿不到 snsId**；而该 View 正在被重绑到新数据，其上任何旧标按定义都是「复用残留」（`textsOf` 也会跳过带 `MARK_KEY` tag 的 View，`:592`）。`markDecision(styleOn, resolved=false, 0L, ...)` ⇒ 恒 HIDE（`SnsDeletedTagLogic.kt:220-221`）⇒ `removeMark`（`:656`）。不存在「应显示却解析成功」的反例。唯一会「本应显示却没显示」的是 [DONE-3] 的 `anchorOffset` 返回 null —— 那是**锚定判据**把真被删动态判成歧义，属第 2 项漏标，不是 `applyMark` 误删。✅

**`ensureMark` 先 `removeMark` 再插 ⇒ 重复绑定不累积。** SHOW 路径：`applyMark`（`:652-655`）→ `ensureMark` 开头 `removeMark(root)`（`:670`）→ 只 `addView` 一个 tv（`:690`）→ `hits++`（`:692`）；HIDE 路径：`applyMark` 直接 `removeMark(view)`（`:656`）。`tv.setTag` 与 `parent.addView` 放进**同一个** `runCatching`（`:688-691`），setTag 失败则整条不插、不会留下「无主」标记（KDoc `:685-687` 已说明）。✅

唯一缺口同 [DONE-2]：`onBind` 的 `view==null` 分支（`:384-388`）无 `removeMark`，但无 View 句柄、真机无日志，非成因。

### 项 5：回归红线 —— 逐条核对，全过

| 红线 | 证据 | 结论 |
|---|---|---|
| `MARK_KEY` 是合法资源 id，⛔ 非 `View.generateViewId()` | `SnsDeletedTag.kt:107` = `R.id.wxgroup_sns_mark`；`res/values/ids.xml:16` `<item name="wxgroup_sns_mark" type="id"/>` ⇒ `0x7f` 段，满足 `View.setTag(int,Object)` 的 `key>>>24 >= 2`（`ids.xml:6-14` 注释）。全局 grep：`View.generateViewId()` 仅出现在 `HintBadge.kt:662/825/894`（那里是 `setId`，不同契约）与 `SnsDeletedTag.kt:101` / `ids.xml:6` 的**警示注释**，生产代码未用 | ✅ |
| `tailIndex()` 仍跳过 item 根末尾 1px 分隔线 | `SnsDeletedTag.kt:708-715`：末子为 `android.view.View` 且 `height <= dp(MARK_DIVIDER_MAX_DP)`（`MARK_DIVIDER_MAX_DP=2`，`:119`）⇒ 返回 `childCount-1`；dump 里确有 `[0,1829][1440,1830]` / `[0,3002][1440,3003]` 两条 1px `android.view.View` | ✅ |
| `MARK_LEFT_DP = 72` | `SnsDeletedTag.kt:96`；dump 两条灰标 `x=259` 与 251px 起点同款 | ✅ |
| `isTimelineLoss` 只盯 bit1 | `SnsDeletedTagLogic.kt:57-58`：`(old and BIT_TIMELINE)!=0 && (new and BIT_TIMELINE)==0`；`BIT_TIMELINE=2`（`SnsBitLogic.kt:32`），bit3=`BIT_ALBUM=8`（`:35`）不参与；`album_loss_only_is_not_timeline_loss` 等单测锁住 | ✅ |
| `shouldRecord` 仍排除自己 | `SnsDeletedTagLogic.kt:79-80` = `isTimelineLoss(...) && !isSelf(author,self)`；`isSelf`（`:66-67`）要求 `self` 非空且 `author==self` | ✅ |
| `SnsDeletedTag.onBlocked` 记录链路 + 后台单线程 writer 未改 | `SnsDeletedTag.kt:216-234`：判定在调用线程（纯函数 + self 缓存），`writer.execute{ recordSnsDeleted → gen.incrementAndGet() → LogX }`；`writer` = `Executors.newSingleThreadExecutor` daemon `wxgroup-snsdel`（`:199-201`）；KDoc `:212-215` 明确「gen 自增必须在写库成功之后、同一后台任务里」。调用方 `SnsGuard.kt:492`（写回 BLOCKED）/`:545`（摘键 BLOCKED）参数 `(id,bits,want)` 逐字一致，`SnsGuard.kt` mtime `01:10:25` 早于本轮 | ✅ |
| bit3 三文件 + 其余受保护文件未被本轮改动 | `stat -c '%y'` 全部早于本轮（见 [DONE-1] 表）：`SnsGuard 01:10:25` / `SnsBlockProbe 01:08:07` / `SnsBitLogic 01:17:45` / `GroupDb 11-06 22:34` / `Keys 11-06 23:07` / `EntryReceiver 00:19` / `ids.xml 11-06 23:57`；`ALBUM_FIX_OFF_KEY` 仍在 `SnsGuard.kt:80/180` | ✅ |

> 说明：bit3 文档（`_r42_bit3_impl.md:252`、`_r42_bit3_review.md:310`）只记「还原后 sha 与原始一致」，**未登记受保护文件的 SHA 基线**，故本项以「mtime 全部早于本轮」+「关键行逐字核对」为准，无法做 sha 逐字比对 —— 这是只读条件下的最强可得证据。

---

## [DONE-6] 必查项 6 · 测试真实性（独立跑，不信报告）

### 独立运行记录

按纪律用 **PowerShell 工具**（⛔ 未从 Bash 调 `cmd.exe`），每次收窄环境：

```
$env:PATH='G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot\bin;C:\Windows\System32;C:\Windows'
$env:JAVA_HOME='G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot'
Push-Location '...\wxgroup'; & '.\gradlew.bat' testDebugUnitTest --rerun-tasks -q --console=plain; Pop-Location
```

结果：`GRADLE_EXIT=0`，18s。

读 `app/build/test-results/testDebugUnitTest/TEST-*.xml`（用 `[System.IO.File]::ReadAllText(path, UTF8)` 绕过 PowerShell 5.1 默认 GBK 导致 `[xml]` 解析失败；第一次因编码问题只统计到 801，UTF-8 重算才是真值）：

| 项 | 实测 |
|---|---|
| XML 文件数 | **41**（41 个全部解析成功） |
| TESTS / FAILURES / ERRORS / SKIPPED | **812 / 0 / 0 / 0** |
| 含 `<failure>` / `<error>` 节点的文件 | **无** |
| 全部 XML `timestamp` | `2026-11-06T18:37:00` / `:01`（= 本地 02:37，即本次运行，非 FROM-CACHE 旧结果） |
| `TEST-...SnsDeletedTagLogicTest.xml` | `tests=51 failures=0 errors=0` |
| 主源码 class 新鲜度 | `SnsDeletedTag.class` / `SnsDeletedTagLogic.class` = `02:36:56`（本次）⇒ `--rerun-tasks` 连 main 一起重编译，非「0 用例假绿」 |

⇒ 与实现报告 `[DONE-6]:351-355` 的「41 类 / 812 例 / 0 失败」一致，**独立复核通过**。

### 新增 12 例的覆盖核对（`SnsDeletedTagLogicTest.kt:407-546`）

| team-lead 指定场景 | 对应用例 | 覆盖 |
|---|---|---|
| 相邻同名 | `:414-420` `...without_learned_is_conservative_null`、`:426-432` `...with_learned_prefers_learned_over_zero`、`:527-546` `scenario_...only_real_deleted_post_is_marked` | ✅ |
| 复用负路径 | `:490-500` `...hide_when_not_resolved_clears_recycled_mark`、`:478-484` `hide_for_new_post_not_in_deleted`、`:502-508` `hide_when_style_off`、`:510-517` `hide_when_snsid_zero` | ✅ |
| 非同名候选 | `:438-445` `anchor_offset_single_passing_is_accepted_as_calibration`（KDoc 明写对应第 3 条万华） | ✅ |
| `anchorOffset` 三分支 | 分支1 `learned in passing`：`:426-432`（含 `learned=0` 合法沿用，`:431`）、`:443-444`；分支2 `singleOrNull`：`:438-445`、`:454-457`；分支3 歧义 null：`:414-420`、`:447-451`（空 passing）、`:459-464`（stale+歧义） | ✅ 全覆盖 |
| `markDecision` 互斥 | 正例 `:469-475`；四个条件各有一条对口负例（`:478` / `:490` / `:502` / `:510`） | ✅ |

12 例确为纯 JVM 判据、零 Android 依赖，期望值从用户可见事实推导（如 `anchorOffset([0,-1], -1) == -1` 来自「新动态该锚到自己」，不是照抄 `singleOrNull()`），既有 39 例（含 `pickOffset` 4 条 `:239-274`）保留未改。

**⚠️ 覆盖缺口（重要，直接影响第 2 项裁决）**：12 例中**没有一条**覆盖「冷启首屏、`learned=null`、同名相邻、真·被删动态仍须打标」。相反 `:414-420` 把「该场景返回 null ⇒ 不打标」**写成期望断言**，`:527-546` 的串联场景又**预设 `learned=-1` 已校准**。即：测试**锁住的是「保守降级」这个设计选择，而不是验收②「真被删仍挂标」**。测试全绿不能证明 [DONE-3] 不是回归 —— 这正是「必须独立做源码推演、不能只看测试」的理由。

### 变异验证 8 条 + 等价变异评估（`docs/_r42_deltag_false_positive.md:363-376`）

| # | 变异 | 语义 | 有效性 |
|---|---|---|---|
| M1 | 注释掉 `learned in passing` 分支 1 | 丢「沿用已校准常数」 | ✅ 对口 2 例红 |
| M2 | `singleOrNull()` → `firstOrNull()` | **退回旧 pickOffset「猜第一个」**，正是本次误报根因 | ✅ 最关键，对口 2 例红 |
| M3 | 分支 2 恒 null | 丢「唯一通过采信 / 校准源」 | ✅ 对口 2 例红 |
| M4~M7 | 逐一去掉 `markDecision` 的 `inDeleted` / `resolved` / `styleOn` / `snsId!=0L` | 四个 SHOW 必要条件各自成为独立判据 | ✅ 各对口 1~2 例红 |
| M8 | SHOW 分支恒 HIDE | 丢「真被删仍挂标」 | ✅ 对口 2 例红 |
| EQ | `singleOrNull()` → `if(size==1) passing[0] else null` | **语义完全等价** | ✅ 全绿，证明「变红不是随便改动都会发生」 |

⇒ 8 条各有独立语义、且非「改一处全红」的同构现象；EQ 有效。独立补验：把分支 1 改成 `learned != null`（丢掉 `in passing`）会被 `:454-457` / `:459-464` 抓红 ⇒ 护栏的「in passing」也有用例守着，不是只测了主路径。

**结论**：测试真实性成立、用例数与结果可信、变异体系有效；但**测试覆盖的边界 ≠ 真机首屏边界**（见缺口），故不能替代 [DONE-3] 的裁决。

---

## [DONE-7] 总裁决（三选一）

# ⛔ 有阻塞项（1 项）—— 当前不建议直接装包

## 阻塞项（逐条列全 + 建议改法）

**B1 [阻塞] 冷启首屏真·被删动态灰标消失（[DONE-3]）**

- 现象：`offsets`（`SnsDeletedTag.kt:488`）冷启为空 ⇒ `learned=null`（`:512`）；首屏 pos1/pos2 两条同名相邻动态 `passing` 分别 `[0,-1]` / `[-1,-2]` 且无 learned ⇒ `anchorOffset` 分支 3 返回 null（`SnsDeletedTagLogic.kt:198-201`）⇒ `resolve` 返回 null（`:521-525`）⇒ HIDE ⇒ **y=2915 灰标消失**。
- 校准只在 pos3（万华化学，`passing=[-1]`）那条绑定时发生（`:528-530`），**晚于**前两条；`resweep()` 仅在 `setStyle`(`:833`) / `purge`(`:938`) 调用，校准后无自动重绘；HIDE 的 view 还被 `bound.remove`（`:396`）移出，`resweep` 也不会重解析 ⇒ 必须滚动才恢复。
- 违反验收②（`_r42_deltag_false_positive.md:418`）与 `[DONE-1]` 基线「y=2915 灰标保留」(`:53-54`)；冷启是 LSPosed 装包的默认条件；用户视角 = 整功能回归，极易触发又一轮十几分钟白测。
- 实现方「本次屏有第三条非同名动态，不落边界」**不成立**：把「屏上有校准源」误等同于「校准先于前两条绑定发生」。
- **建议改法（推荐 [DONE-3]-A）**：`bound` 扩为持有 `(adapterRef,pos,snsId)`（或加 `pending` 集合）→ `onBind` 的 `info==null` 分支不再 `bound.remove(view)` → `resolve()` 校准成功（`d!=0` 且 learned 由空变值）后立即 `resweep()`，对 pending view 用 `adapter/pos` **重新 `resolve()`** 再 `applyMark`。约 15 行、只在 `SnsDeletedTag.kt` 内，不动 `anchorOffset` / `markDecision` / `offsets` 键语义 / 渲染链路，3 文件边界不变。备选取舍：[DONE-3]-B（改测试规程，需 team-lead 明确接受首屏漏标）、-C（Kv 持久化 O，不推荐单独用）。

## 非阻塞项（列全，不阻止装包，但须登记）

| # | 项 | 位置 | 建议 |
|---|---|---|---|
| N1 | `learned in passing` 不能防过期 O；同名相邻窗口可沿用过期常数 ⇒ **新错标**（比漏标更糟）；去 `list.size` 后跨页面隔离消失 | [DONE-4]；`SnsDeletedTag.kt:511`、`:528-530`；`SnsDeletedTagLogic.kt:199` | 下轮给 key 加头部项数/页面维度，或分支 1 加二次确认；`stat()` 打出 learned 值 |
| N2 | 校准正确性全押在弱护栏 `nameOk`（双向 contains + 全文语料），错误 O 会经分支 1 全局传播 | `SnsDeletedTagLogic.kt:151-158`；`_r42_deltag_false_positive.md:281-284` 自认 `_r37_name_anchor.md` 更严方案未采用 | 与 N1 同源，一并登记 |
| N3 | `onBind` 的 `view==null` 分支无 `removeMark`（负路径契约唯一例外） | `SnsDeletedTag.kt:384-388`；实现报告 `:170-173` 已自认 | 无 View 句柄、真机无日志，非成因；可顺手补一次 `itemViewOf` 兜底 |
| N4 | 12 例把「首屏保守降级」固化为期望，**未覆盖验收②首屏** | [DONE-6]；`SnsDeletedTagLogicTest.kt:414-420` | 补一条「冷启首屏同名相邻」用例（配合 B1 的 A 方案把 pending 复判做成可测纯函数） |
| N5 | 3 文件 git `??` 无基线可字面 diff；改前 `resolve()` 系重建 | [DONE-1] | 已在 [DONE-2] 用 UI dump 反证 O≠0 交叉验证自洽；建议下轮把 3 文件纳入版本管理 |

## 正面结论（避免只报坏消息，这些是「修掉 B1 后即可装包」的依据）

1. 改动**严格限定 3 个文件**；三文件 SHA 与实现报告声明的还原基线逐字一致（`f927d922…`/`705bcf46…`/`f68e830a…`），变异还原自证可信。
2. **回归红线全过**：`MARK_KEY` 合法资源 id、非 `generateViewId()`；`tailIndex` 跳过 1px 分隔线；`MARK_LEFT_DP=72`；`isTimelineLoss` 只盯 bit1；`shouldRecord` 仍排除自己；`onBlocked` 记录链路 + daemon 单线程 writer + `gen` 自增时序未改；bit3 三文件及其余受保护文件 mtime 全在本轮之前。
3. **HIDE 路径安全**：`removeMark` 严格按 `MARK_KEY/MARK_MAGIC` 配对、绝不误删原生 View；`applyMark(...,resolved=false)` 清的是复用残留；`ensureMark` 先 removeMark 再插、setTag 与 addView 同事务 ⇒ 重复绑定不累积。
4. **根因链成立**：「封面头固定错位 O=-1 + 同名相邻 ⇒ `d=0` 假命中」完整解释误报与 `match/hits 1→7`；「View 复用残留不是成因」三条佐证扎实；未发现另有其它根因。
5. **测试真实性成立**：独立 `--rerun-tasks` 得 `41 类 / 812 例 / 0 失败 / 0 错误 / 0 跳过`、本类 51 例、时间戳为本次、main 同步重编译；12 例覆盖三场景 + `anchorOffset` 三分支 + `markDecision` 四条件；8 语义变异逐分支变红、1 等价变异全绿。
6. 产物 `app-debug.apk`（`02:27:00`）晚于源码最晚（`02:26:21`）。

## 放行条件

按 B1 的 A 方案修掉（或 team-lead 书面确认接受 B 方案 / 首屏漏标），**并补一条对应单测**后即可装包。真机复核时按修复前基线 `wxgroup/_cache/ui_fp.xml`：**首屏直接取 dump**（⛔ 不要先滚动，否则掩盖 B1），要求 **y=1742 灰标消失、y=2915 灰标保留**，再上下滚动确认不新增/不串位；`stat()` 里 `lastMiss` 出现 `ambig(...)` 属预期、`learned` 值应稳定。N1~N5 不阻塞本轮装包，但 N1/N2 建议在下轮一起处理。

---

## [补充-A] 对 team-lead 补充聚焦 / 实现方 5 点自标的逐条回应（追做实证，**总裁决不变**）

> 本节为收到两条补充消息后追做的核查；[DONE-7] 的「⛔ 有阻塞项（1 项），当前不建议直接装包」**维持不变**。

### A1 · team-lead：链 2「View 复用残留排除」→ 已采纳，不再重复论证

[DONE-2] 已独立复核并采纳三条佐证（计数语义 `match` 仅 SHOW 分支 `:653` / `hits` 仅 `addView` 成功 `:692`；两个不同 itemView 同时在屏；新动态 snsId 不在台账），并额外补了一条实现报告未写的**反证**：若 `dataList` 含封面（O=0），新动态 `d=0` 就是它自己 ⇒ `pickOffset` 取 0 ⇒ 不在台账 ⇒ **不会产生误报**；真机误报存在 ⇒ 逻辑上要求 O≠0。结论一致。

### A2 · tagfix 点 2：「仅类名」key 的假设是否成立（`moments.md:50` + `pos=0/10`）

- 历史取证 `docs/conclusions/moments.md:50`（2026-10-16 真机）明写「`position` 与 `getData()` 下标**错位（封面头，offset=-1/-2）**」并警告「**同作者相邻动态会互相通过校验**」⇒ 「封面头造成非零固定错位」有真机依据；本轮 [DONE-2] 用 `ui_fp.xml` 反推本屏 **O=-1**，落在该区间。真机 `pos=0/10 texts=[轻触更换封面|换封面|V]` 证明 pos0 是封面且 `dataList` 不含它 ⇒ 本屏 O=-1 成立。
- ⚠️ 但两件事必须分清：
  1. **「与列表长度无关」成立，去 `list.size` 是对的**：下拉刷新在 `dataList` 头部插入 k 条时，content item 的 `dataList` 索引 +k、其 adapter `pos` 也同步 +k（封面恒在 pos0）⇒ `dataIndex = pos-1` 不变，O=-1 保持。这正说明「新发一条本不该冲掉校准」，旧 key 含 size 才是 A5 放大器。
  2. **「与页面 / 头部结构无关」未被证明**：`moments.md:50` 自己记的是 **-1/-2 两个值**，说明 O 历史上就**不是**被证明为单一全局常数；本轮 KDoc（`SnsDeletedTag.kt:480-487`）只论证了「与列表长度无关」，未论证「与头部项数量 / 页面无关」。⇒ 与 [DONE-4] N1 一致：去 size 后跨页面隔离消失，头部结构变化时 `learned in passing` 可能沿用过期 O ⇒ 新错标。维持「非阻塞·高风险」，建议下轮给 key 加头部项数 / 页面维度。

### A3 · tagfix 点 3：「`styleOn` / `resolved` 只有单测覆盖、无生产路径」—— **需更正**

- `resolved=false`：确实只有 `onBind` 的 `info==null` 分支传（`:398`）；但在本修复下 **ambig 导致的解析失败恰恰是冷启首屏的高频路径**（[DONE-3]）⇒ 这条生产路径不仅存在，还是本次阻塞项的直接表现。
- `styleOn=false`：**不是「恒 true / 只有单测」**。`onBind` 的 `!on()` 闸门（`:379-382`）确实让绑定路径不走 `applyMark`；但 `setStyle("off")`（`:825-835`）→ `refresh()`（`style=OFF`，`:614-617`）→ `resweep()`（`:833`）→ `applyMark(v,id)`（`:802`）→ `markDecision(styleOn = style==LINE = false, ...)`（`:647`）⇒ **HIDE → `removeMark`，是真实生产路径**；`purge()`（`:938`）同理。这恰好实现了「关开关应立即摘掉已插灰标」这一用户可见行为，`mark_decision_hide_when_style_off`（`SnsDeletedTagLogicTest.kt:502-508`）守的是真实生产语义，不是纯测试摆设。
  - 该更正**不改变裁决**（行为正确、无缺陷），但避免下轮误删这条用例 / 误判 `markDecision` 的两个条件「死代码」。

### A4 · tagfix 点 4：三文件 sha

[DONE-1] 已独立复算 `f927d922…d9` / `705bcf46…fe` / `f68e830a…92`，与 `_r42_deltag_false_positive.md:384-386` 基线逐字一致；我跑完 `--rerun-tasks` 之后又复算一次仍一致（报告末「只读边界自证」）⇒ 变异还原自证成立，当前源码即最终版，无残留变异态。

### A5 · tagfix 点 5 / team-lead y 基线：已纳入裁决

[DONE-1] 已记录本轮未装包 / 未 adb / 未真机 / 未 commit（我本人复核亦未做，仅跑 gradle 单测）；[DONE-7] 已把 **y=1742 应消失 / y=2915 应保留** 作为 B1 的直接判据（违反验收②与 `_r42_deltag_false_positive.md:53-54` 基线），并写明「真机复核**首屏直接取 dump、不要先滚动**（否则掩盖 B1），再滚动确认不串位」。

### A6 · team-lead 第 2 项：一句话明确回答

**会消失，且是必须先修的阻塞项（B1），不是可接受的降级。** 装包后一进页面、未滚动时：`offsets`（`:488`）冷启为空 ⇒ `learned=null`（`:512`）；pos1 / pos2 两条同名相邻动态 `passing` 分别 `[0,-1]` / `[-1,-2]` ⇒ 歧义且无 learned ⇒ `anchorOffset` 分支 3 返回 null（`SnsDeletedTagLogic.kt:198-201`）⇒ HIDE ⇒ **y=2915 灰标消失**。校准只在排最后的 pos3 万华化学绑定时发生（`:528-530`），且 `resweep()` 不在校准后触发（全工程仅 `:833` / `:938`）、HIDE 的 view 又被 `bound.remove`（`:396`）移出、`resweep` 只重放旧 snsId 不重新 `resolve` ⇒ **必须滚动才恢复**。这是「把已有确定性结论（在台账里、旧版能正确标）的动态一起降级」，属回归，不是「漏标好过打错标」。最小缓解见 [DONE-3]-A（pending 复判 + 校准后立即 `resweep` 重 `resolve`，约 15 行、仅 `SnsDeletedTag.kt`、不动判据 / `offsets` 键语义 / 渲染链路）。

---

## [补充-B] 实现方追加的「`d==0` 语义残留」—— 核实、代价、更稳的落地（**总裁决仍不变**）

> 来源：tagfix 认可 B1 后补的一条「:529 `d==0 → offsets.remove(key)` 是旧语义残留」。
> 结论：**缺口成立**（我 [DONE-3]-A 的触发条件确有 O≠0 隐含假设），但「把 :529 改成无条件 `offsets[key]=d`（含 0）」**不是无代价**，不宜单边采纳。

### B1 · 缺口核实：成立

`SnsDeletedTag.kt:529` 现为 `if (d != 0) offsets[key] = d else offsets.remove(key)`（KDoc `:526` 明写「d==0 不必缓存（沿用原语义）」）；`anchorOffset` 分支 1 是 `learned != null && learned in passing`（`SnsDeletedTagLogic.kt:199`）。设 **O=0**（无封面头 / `dataList` 含封面的设备或页面，view pos P ↔ `dataList[P]`）：

| 格 | `passing` | `learned=null` | `learned=0` |
|---|---|---|---|
| pos1 新 verity | `[0,1]`（`d=0` 自己、`d=1` 相邻同名） | 分支 3 `singleOrNull()` ⇒ null ⇒ HIDE | `0 in [0,1]` ⇒ 分支 1 返回 0 ⇒ 锚到自己（notInDel）⇒ HIDE ✅ |
| pos2 被删 verity | `[0,-1]` | null ⇒ HIDE ⇒ **真被删漏标** | `0 in [0,-1]` ⇒ 返回 0 ⇒ 锚到自己（inDel）⇒ SHOW ✅ |

⇒ O=0 时 **`learned=0` 是必需的**；而 :529 的 else 分支使 0 **永不被缓存** ⇒ `learned` 恒 null ⇒ 所有同名相邻窗口永远降级。且我 [DONE-3]-A 原文触发条件写的是「`d!=0` 且 learned 由空变值」，O=0 时既不缓存也不触发 `resweep` ⇒ 首屏漏标依旧。**tagfix 的补充成立，我方案 A 确有 O≠0 隐含假设，应修正。**

### B2 · 但对「当前真机这屏」总裁决不变

[DONE-2] 已用原始 UI dump 反证：若 O=0 则新动态 `d=0` 就是自己、不会产生误报 ⇒ 误报存在**逻辑上要求 O≠0**，本屏 O=-1。于是 pos3 校准 `d=-1 != 0` ⇒ :529 走 `offsets[key] = -1` ⇒ `learned` 由 null→-1 ⇒ 方案 A 触发条件成立、会 `resweep`。⇒ **B1 仍是唯一阻塞项，方案 A 对本屏有效；[DONE-7] 三选一裁决不变。** 该缺口属「O=0 的一般情形」，且是**本轮之前就存在**的既有行为（`d==0 → remove` 非本轮新增），不构成新的装包阻塞，登记为 **N6（非阻塞）**。

### B3 · ⚠️ 「改成无条件缓存含 0」的两条代价（本复核新增，反向验证）

1. **放大 N1**：缓存 0 后 `learned` 几乎恒非 null ⇒ 分支 1（`learned in passing`）更常被调用 ⇒ [DONE-4] 的「过期常数跨页面沿用」风险面扩大；`learned=0` 与 `learned=-1` 串页面时，同名相邻窗口 `passing=[0,-1]` 两者都在 ⇒ 分支 1 会「成功沿用」过期值 ⇒ **新错标**。
2. **更直接：O=-1 下也可能把 0 误当 O 缓存**。构造：view pos P ↔ `dataList[P-1]`（自己）；若「自己」那条没通过 `nameOk`（昵称读不到 / 特殊昵称 / `texts` 无该名），而 `d=0` 指向的**相邻下一条同名**通过了 ⇒ `passing=[0]`、`size==1`、`d=0`。
   - **当前代码（措辞已按 B7 精确化）**：⚠️ `anchorOffset([0], null)` 与 `anchorOffset([0], -1)` **都返回 0**（分支 1 要求 `learned in passing`，`-1 ∉ [0]` ⇒ 落 `singleOrNull()`=0）⇒ `resolve` 会返回 `byOffset[0]`（相邻那条）⇒ 若相邻是真被删动态，**本次就已经错标**（`nameOk` 护栏固有弱点，**非本轮引入**，见 B7 / N2）。`else offsets.remove(key)` 真正防的是：① **不把错误的 0 缓存下来扩散到后续所有绑定**；② 清掉先前正确的 -1 回到保守 null。它**不防本次错标**。
   - **改成 `offsets[key]=0`** ⇒ 缓存错误 O（真实 -1）⇒ 后续 `passing=[0,-1]` 时 `0 in passing` ⇒ 分支 1 **沿用**错误 O ⇒ 由「一次性错标」放大成 **持续性错标**；再叠加代价 1（`learned` 几乎恒非 null ⇒ 放大 N1）⇒ 单边采纳确实更糟。
   - ⇒ 结论修正：当前那行 `remove` 防的是**扩散与放大**，不是本次；本报告初稿「保守降级（安全）」表述不精确，已更正。

⇒ 结论：**不要简单删掉 `else offsets.remove(key)`**。修 O=0 退化须连带加一致性约束（例如仅当 `d` 对应的 `info.name` 与 `texts` 为**正向包含**才缓存 0，或连续两次非歧义绑定得到同一 `d` 才落缓存），并与 N1 的 key 维度一起做。

### B4 · 更稳的落地（对 [DONE-3]-A 的修正稿）

1. **触发条件与 `d` 解耦，且不依赖「缓存了 0」**：用 before/after 比较
   `val before = offsets[key]` → 校准 → `if (before == null && offsets[key] != null) 触发 resweep()`。
   - 对当前屏（O=-1）：before=null → after=-1 ⇒ 触发 ✅。
   - **天然自带一次性**：`resweep` 内对 pending view 重新 `resolve()` 时 `before` 已非 null ⇒ 不会再次触发 ⇒ **不需要全局防重入标志**（tagfix 提的「一次性触发 + 防重入」用此写法即可满足，比加标志更省）。`applyMark` 不写 `offsets`，无递归。
   - :529 保持现状 ⇒ 不引入 B3 的两条代价；O=0 退化登记 N6、下轮与 N1 一起改。
2. **明确 `resweep` 的分工**（采纳 tagfix 第二点）：只对 **pending（曾 `info==null`）** view 用 `(adapter,pos)` 重新 `resolve()` + `applyMark`；已成功绑定 `resolved=true` 的仍按原逻辑用旧 snsId 重放 `applyMark(v, id)`，不扩大改动面。
3. **验收**（采纳 tagfix 第三点，与 [DONE-7] 一致）：真机加「冷启后**不滚动**直接看首屏」，y=1742 消失 / y=2915 保留；方案 A 是跨绑定时序，纯 JVM 判据单测盖不住，必须真机补这一条。

### B5 · 汇总（供裁决稿）

- 阻塞项仍 **1 个（B1）**；非阻塞由 N1~N5 扩为 **N1~N6**（N6 = O=0 时 `learned=0` 不可缓存导致功能退化，本轮之前既有、当前真机 O=-1 不触发）。
- 裁决稿若采纳方案 A，请把「校准成功」的触发条件按 B4-1 写（与 `d` 是否 0 解耦、before/after 自带一次性），**不要**仅按 tagfix 原话把 :529 改成无条件缓存含 0 —— 那会修了漏标、可能引入错标（B3）。

### B6 · 措辞澄清（避免「保留发现」被误读成「按此实施」）

tagfix 请求「B1 裁决稿里把『校准成功必须含 `d==0`』一并保留」。本复核的界定：

- **该保留的是「发现」**：`:529` 的 `d==0 → offsets.remove(key)` 是旧语义残留，导致 O=0 时 `learned=0` 永不被缓存、同名相邻窗口全部降级 ⇒ 已登记为 **N6（非阻塞，既有行为）**，并在 B1/B2 完整推演。**这条必须在裁决稿里留痕，不能丢。**
- **不该原样写成实施结论**：「校准成功必须含 `d==0`」若被读成「把 :529 改成无条件 `offsets[key] = d`（含 0）」，会引入 B3 的两条代价（放大 N1；O=-1 下把 0 误当 O 缓存 ⇒ 新错标），且当前真机 O=-1 根本不触发。⇒ **不是本轮放行前的修复动作。**
- **建议裁决稿措辞**：
  > 「实现方补充的 `d==0` 语义残留经复核成立：O=0 时 `learned=0` 不可缓存会导致功能退化，属本轮之前既有行为、当前真机（O=-1）不触发，登记为 N6 非阻塞，下轮与 N1（key 加头部项数/页面维度）一并处理。**本轮不放行前修改 `:529`**；方案 A 的 resweep 触发条件改为与 `d` 解耦的 before/after 写法（[补充-B]-B4-1），对当前屏有效且不引入错标风险。」

⇒ 「保留发现」= 登记 N6 + 留推演；「按此实施」= 本轮不做。两者分开写，裁决稿才不会自相矛盾。

### B7 · 实现方第二次反向核实：B3 措辞精确化 + 方案 A 的「重复触发」核实（**总裁决仍不变**）

> tagfix 核实 B3 两条代价成立、撤回「:529 无条件缓存含 0」单边建议、采纳 B4，并补了一条独立核实细节。本复核逐条验证后**采纳并更正本报告初稿措辞**。

**B7-1 · 更正 B3 初稿的「保守降级（安全）」**（tagfix 的核实成立）

独立验算 `SnsDeletedTagLogic.kt:198-201`：

| 调用 | 分支 1 | 返回 |
|---|---|---|
| `anchorOffset([0], null)` | `learned != null` 为 false ⇒ 跳过 | `singleOrNull()` = **0** |
| `anchorOffset([0], -1)` | `-1 in [0]` 为 false ⇒ 跳过 | `singleOrNull()` = **0** |

⇒ 两种情况下 `resolve` 都返回 `byOffset[0]`（**相邻那条**），`onBind` 会 `resolved++` + `bound[view] = 相邻snsId` + `applyMark` ⇒ 若相邻是真被删动态，**当前代码本次就已经 SHOW 错标**。这不是「返回 null ⇒ HIDE 的保守降级」，也不是 tagfix 补充引入的，而是 `nameOk` 护栏的**固有弱点**（与 `docs/_r37_name_anchor.md` 记的「备注名读不到」同类）。
- `:529` 的 `else offsets.remove(key)` 真正防的是**扩散**：不把错误的 0 缓存、并清掉先前正确的 -1 回 null，使错误 O 不被后续绑定经分支 1 沿用。防的是「一次性错标 → 持续性错标」的放大，**不是防本次**。
- 本报告 B3 初稿写「清缓存回 null ⇒ 保守降级（**安全**）」不精确，已改（见 B3 第 2 条）。**该更正不改变结论**：仍是不该单边改 :529，但理由从「防本次错标」修正为「防扩散与放大」。

**B7-2 · 对 N2 的补强（同源，非新增阻塞）**

B7-1 揭示的「本次错标」是 **N2（锚定/校准全押在弱护栏 `nameOk`）** 的一个具体表现：`passing=[0]` 时无论 `learned` 为何，`anchorOffset` 都会采信唯一通过护栏的那个偏移，而该偏移可能是相邻同名动态。属既有行为、非本轮引入 ⇒ 并入 N2 留痕，不改阻塞项数。

**B7-3 · 核实 tagfix 的「重复触发」小提醒：成立，可接受**

方案 A 落地后，若 `:529` 的 `d==0 → remove` 使 `offsets` 回 null：
- 该次 `before`（进入 `resolve` 时的 `learned`）非 null、`after` = null ⇒ `before == null && after != null` **不成立** ⇒ 不触发 `resweep`（不会误触发）。
- 之后某次 `d!=0` 的非歧义绑定把 `offsets[key]` 从 null 写回 -1 ⇒ `before == null && after != null` ⇒ **再次触发** `resweep`。
- 该重复**非递归**（`resweep` 内重新 `resolve()` 时 `before` 已非 null，不再触发；`applyMark` 不写 `offsets`）、每次都是对 pending view 的有效补标 ⇒ 可接受开销，不必额外加抑制。**tagfix 判断成立，采纳。**

**B7-4 · 补一条落地期可观察现象（避免被误判为新故障）**

`remove` 使 `offsets` 回 null 后、到「下次 `d!=0` 校准」之间的绑定，`learned=null` ⇒ 同名相邻窗口会再次走分支 3 ⇒ **B1 类漏标短暂重现**；方案 A 的 pending 机制会在下次校准触发的 `resweep` 中补标。⇒ 装包后若观察到「灰标偶发闪失后自动恢复」，属预期，不是新缺陷。建议写进真机验收说明。

**B7-5 · 结论**

总裁决、阻塞项数（**1 个 B1**）、非阻塞 N1~N6、812 例独立复核结论**全部不变**；[补充-B]-B3 措辞更正、B4 落地稿不变。tagfix 已撤回单边建议并采纳 B4，双方对裁决稿措辞一致。








