# R42 · 朋友圈「已删除」灰标误报（新发未删动态被挂标）—— 根因判定与修复

> 档位=Pro 依据=判据设计 + Hook 绑定链路 + View 复用推理
> 作者：tagfix（根因判定 + 修复实现 lane）
> 日期：2026-11-07
> 关联：`docs/_r42_deltag_report.md` §「真机误报：新动态被挂『已删除』灰标（2026-11-07 01:58，待修）」

## 硬边界（自束，逐条对照）

- 唯一允许改的源码：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsDeletedTag.kt`、
  `SnsDeletedTagLogic.kt`、`wxgroup/app/src/test/java/com/lx/wxgroup/feature/SnsDeletedTagLogicTest.kt`
  （+ 必要的新 test 文件）。
- ⛔ 不碰 `SnsGuard.kt` / `SnsBlockProbe.kt` / `SnsBitLogic.kt` / `GroupDb.kt` / `Keys.kt` /
  `EntryReceiver.kt` / `res/values/ids.xml`；不碰 `antirevoke.sns_album_fix_off`（反向对照用户明确暂不做）。
- ⛔ 不装包、不 adb、不真机、不 commit、不改任何已有 `docs/_r42_bit3_*.md`。
- 唯一允许新建的文档：本文件 `docs/_r42_deltag_false_positive.md`（分步落盘，每节留 `[DONE-n]`）。

---

## [DONE-1] 取证事实 + 复核范围

### 用户能看见的事实（判据一律对着这些写，不对着内部计数器）

1. bit3 补位装机并正向验收 PASS 之后，用户让同一好友 **verity** 又**新发一条动态**（这条没被删除），
   结果这条新动态上出现了「该动态已被作者删除」的灰色小字 —— 这是误报，必须修。
2. UI dump（`wxgroup/_cache/ui_fp.xml`，`ImproveSnsTimelineUI`，按 bounds 的 y 排序）。
   ⚠️ 这是**原始 XML 一手取证**（不是从报告表格转述）：本轮用 PowerShell 逐 `<node>` 解析，
   共 **73 个 node**，逐条比对 `text=` / `content-desc=` / `bounds=`，下表 y 值与原始 `bounds`
   逐行吻合（PowerShell 控制台把中文显示成 GBK 乱码，但 y 坐标与字节位置是准的）：

   | y（原始 bounds） | text | 判定 |
   |---|---|---|
   | 665（`[251,665][1379,819]`） | `verity` | 第 1 条动态作者 |
   | 1659（`[251,1659][429,1717]`） | `1分钟前` | 刚新发、**未被删除** |
   | 1742（`[259,1742][1440,1800]`） | `该动态已被作者删除` | ❌ 误报 |
   | 1838（`[251,1838][1379,1992]`） | `verity` | 第 2 条动态作者 |
   | 2832（`[251,2832][429,2890]`） | `8分钟前` | 01:48 那条真·被删动态 |
   | 2915（`[259,2915][1440,2973]`） | `该动态已被作者删除` | ✅ 正确 |
   | 3011（`[251,3011][1379,3140]`） | `万华化学—李思远pc` | 第 3 条（另一好友，非同名） |

   三条从原始 XML 读出来的**额外硬事实**：
   - 两条灰标的 `bounds` 左边界都是 **x=259**，与 `MARK_LEFT_DP=72`（`SnsDeletedTag.kt:96`，
     真机 251px 起点）及 `_r42_deltag_report.md:306` 记录的 `[259,2349][1440,2407]` 同款
     ⇒ 两条灰字都是**本模块 `ensureMark` 主动 `addView` 插进去的**，不是微信自带文字。
   - 每条灰标都紧跟在自己那条动态的时间行之后（1659→1742、2832→2915，上距约 25px），
     即**两个不同 itemView 各自被插了一行**，且两条动态**同时在屏**（665/1659/1742 与
     1838/2832/2915 分属两块可见区域），不是「同一个 View 被先后复用」。
   - 两条动态各带一个数字文本 `1`（`[190,752][346,910]`）/`2`（`[190,1925][346,2083]`），
     内容不同 ⇒ 是两条**不同**动态，不是同一数据重复渲染。

   ⇒ **两条相邻动态同属 verity**，一真一假都挂灰标；屏上还有一条**非同名**动态可用于错位校准。

   **⛔ 这份 dump 是「修复前对照基线」**：修复后同一屏应满足 —— y=1742 那条灰标**消失**、
   y=2915 那条灰标**保留**、上下滚动后不新增/不串位。真机复核时按这三个 y 区间逐条比对。

### 主会话已取的真机事实（不重新猜）

- `SNSDEL stat style=line rows=5` **没有新增**，`recent` 首条仍是 01:51:01 真·被删动态
  （无符号 `15049056316837204499` / 有符号 `-3397687756872347117`）
  ⇒ 台账没记错，**新动态不在 `sns_deleted` 表里**。
- `BLOCK` 事件自 `01:54:41` 之后**零新增** ⇒ 新动态没触发任何拦截写库，
  `SnsDeletedTag.onBlocked`（`SnsDeletedTag.kt:216`）没被调用 ⇒ 记录层 100% 清白。
- `SNSDEL stat` 从 `01:53:21` 的 `match=1 hits=1 binds=19 sns=15 resolved=11 missed=4`
  涨到 `01:58:33` 的 **`match=7 hits=7 binds=39 sns=35 resolved=27 missed=8`**
  ⇒ 同一个台账 id 被判成了更多次命中并**插了灰标**（`match`/`hits` 语义见 `SnsDeletedTag.kt:176-179`、
  `:630`、`:669`）。
- `SNSDEL miss#5/#6 pos=0/10 texts=[轻触更换封面|换封面|V]`、
  `lastMiss=pos=0/10 texts=[换封面|正在加载...]`
  ⇒ **`pos=0` 是顶部封面项**、解析不到（已知正常态）。这是「position 与 dataList 下标错位」的
  直接取证，不是推理。
- 时间线是 RecyclerView，灰标挂在 `WxRecyclerAdapter.onBindViewHolder` 的 **after** 钩子
  （`SnsDeletedTag.kt:351-369`）。

### 复核范围（读过的文件 / 行）

- `SnsDeletedTag.kt`（全文 918 行）：
  `onBind`（`:371-403`）、`itemViewOf`（`:414-455`）、`resolve`（`:486-520`）、`infoAt/infoOf`
  （`:522-563`）、`textsOf`（`:574-591`）、`applyMark`（`:626-635`）、`ensureMark`（`:637-675`）、
  `tailIndex`（`:685-692`）、`removeMark`（`:694-708`）、`findBodyInsertPoint`（`:714-723`）、
  `offsets` 缓存（`:479`、`:496-501`、`:514`）、`bound` 映射（`:168-169`、`:401`、`:783-792`）、
  `resweep`（`:772-781`）、`stat`（`:815-828`）。
- `SnsDeletedTagLogic.kt`（全文 244 行）：`nameOk`（`:151-158`）、`pickOffset`（`:171-176`）、
  `parseInfo`（`:118-124`）、`shouldRecord`（`:79-80`）、`isTimelineLoss`（`:57-58`）。
- `SnsDeletedTagLogicTest.kt`（全文 406 行，36 个 `@Test`）。
- 历史文档：`docs/_r42_deltag_report.md`（尤其 `:224-259` ambig2 → pickOffset 的改动、
  `:357-400` 本次误报现场）、`docs/conclusions/moments.md:50`（原始设计「±2 窗口必须唯一命中」
  并警告「同作者相邻动态会互相通过校验」）、`docs/_r37_name_anchor.md`（更早版本的作者文本
  结构性锚定，当前 R42 代码已回退为 `textsOf` 全文语料 + `nameOk`）。

---

## [DONE-2] 根因判定

### 结论（互斥判定，不是「看起来像」）

**唯一根因 = 怀疑方向 1：`resolve()` 相邻 snsId 串位。**
**怀疑方向 2（RecyclerView itemView 复用残留）不是本次成因**——负路径现有代码已在三处移除灰标，
且真机 `match==hits` 同步增长证明这 6 次是「主动判定命中并插标」，不是「残留的旧标」。

（两者是互斥关系：残留不会产生新的 `match`/`hits` 计数，且原始 UI dump 里两条灰标分属
**两个同时可见的不同 itemView** ⇒ 残留说解释不了，详见下文 B 段第 2/3 点。故不写成「两者都有」，
也不预设结论：是对着「源码插标路径 + 计数语义 + 原始 UI dump」三方交叉实证后判定的。）

### A. 方向 1 的完整因果链（逐环带文件:行号）

**A0 前置事实：pos 与 dataList 存在全局固定错位。**
`SnsDeletedTag.kt:49` KDoc 明写「position 与 getData() 下标可能错位（封面头）」；
`docs/conclusions/moments.md:50` 取证为 `offset=-1/-2`；
真机 `lastMiss=pos=0/10 texts=[轻触更换封面|换封面|V]` 直接证明 `pos=0` 是封面项、
`dataList`（`getData()`，`SnsDeletedTag.kt:464-477`）不含它 ⇒ 所有动态的
「自己那条」在 `dataList` 里对应某个**固定偏移 O**（不是 `d=0`）。

**A1 锚定判据失了区分力。**
`resolve()` 在 `order = intArrayOf(0, 1, -1, 2, -2)`（`:502`）窗口里，对每个 `d` 取
`infoAt(list, pos+d)`，用 `SnsDeletedTagLogic.nameOk(info.name, texts)` 做护栏（`:506-509`）。
而 `nameOk`（`SnsDeletedTagLogic.kt:151-158`）是**只认作者名**的双向 `contains`：
`texts.any { t.length>=2 && (t.contains(n) || (t.length>=3 && n.contains(t))) }`。
`texts` 来自 `textsOf(itemView)`（`:495`、`:574-591`）——子树全文，含昵称 `verity`。

**A2 同名相邻 ⇒ 两个偏移都通过护栏。**
两条相邻动态作者都是 `verity`。绑定**新发动态**的 itemView 时：
- `d = O`（正确错位）指向新发动态自己，`info.name=verity`，`nameOk` 通过；
- `d = 0`（错位后恰好指向相邻那条**真·被删动态**），`info.name` 同样是 `verity`，
  `nameOk` **也通过**（护栏只比作者名，两条动态名字相同，无法区分）。

**A3 `pickOffset` 优先取 `d=0` ⇒ 串位。**
`SnsDeletedTagLogic.pickOffset`（`:171-176`）「按传入顺序返回第一个 `ok=true` 的偏移」，
`resolve` 传入顺序 `0,1,-1,2,-2`（`:502-503`）⇒ **`d=0` 排在正确错位之前**，
返回 `byOffset[0]`（`:512`）＝相邻真·被删动态的 snsId。
这一步正是 `docs/_r42_deltag_report.md:243-250` 为修 `ambig2`（match 恒 0）把
「±2 窗口唯一命中才采用」改成「取第一个通过护栏」时引入的回归；
`moments.md:50` 当年就警告过「同作者相邻动态会互相通过校验」。

**A4 查表命中 ⇒ 误插灰标。**
`onBind` 把串来的 snsId 记为 `bound[view]` 并 `applyMark(view, info.snsId)`（`:400-402`）；
`applyMark`（`:626-635`）判据 `snsId in deleted()`（`:628`）⇒ 该 id 在 `sns_deleted` 表里
（正是真·被删那条）⇒ `shouldShow=true` ⇒ `match++` + `ensureMark(view)`（`:630-631`）
⇒ 新发动态上出现灰标。

**A5 缓存失效放大。**
`offsets` 缓存的 key 是 `"${adapter类名}#${list.size}"`（`:496`）。新发一条动态使
`list.size` 变化（`miss` 日志里的 `/10`），旧 key 命中不到 ⇒ 缓存失效，
每一次绑定都重新走「`0` 优先」的完整窗口猜测（`:502-510`），把 `d=0` 的假命中反复放大。
这与真机 `binds 19→39 / resolved 11→27 / match,hits 1→7` 完全吻合：
**台账只有 1 条可见的被删 id，却被反复锚定到多个 itemView 上。**

### B. 方向 2 被排除的证据（对着代码 + 计数语义）

1. **负路径三处都已移除灰标**：
   - `onBind` 的 `info == null` 分支 `removeMark(view)`（`SnsDeletedTag.kt:397`）；
   - `applyMark` 的 `!shouldShow` 分支 `removeMark(view)`（`:633`）；
   - `ensureMark` 开头先 `removeMark(root)` 再插（`:647`）。
   `removeMark`（`:694-708`）递归整棵子树、按 `MARK_KEY/MARK_MAGIC` 摘除，与
   `ensureMark` 的 `tv.setTag(MARK_KEY, MARK_MAGIC)`（`:666`）严格配对。
2. **计数语义排除「残留」**：`match` 只在 `applyMark` 的 `shouldShow=true` 分支自增（`:630`），
   `hits` 只在 `ensureMark` 的 `addView` 成功后自增（`:669`）。
   若灰标只是「复用 View 上遗留、负路径没清掉」，则这次绑定必走 `shouldShow=false` 分支，
   **既不 `match++` 也不 `hits++`**，`stat()`（`:824-827`）里的 `match`/`hits` 不会涨。
   真机 `match=1→7`、`hits=1→7` 同步 +6 ⇒ 这 6 次是**主动判定命中并真的插了标**，
   是「判据算错」（A 段），不是「残留」。
3. **原始 UI dump 一手事实排除「一个 View 复用带旧标」**：`wxgroup/_cache/ui_fp.xml` 里
   `y=1742` 与 `y=2915` 两条灰标**同时在屏**，各自位于两条不同动态的时间行之后，
   两条动态正文分别是 `1` / `2`（不同数据）、左边界同为 x=259（都是本模块 `ensureMark` 插的）。
   「复用残留」的机制只能是「**一个** itemView 被复用、上一次的标没清掉，跟着到了新数据」，
   无法解释「**两个不同 itemView 同时各带一个模块插的标**」，更无法解释「新动态 snsId 不在台账
   （`rows=5` 未变 / `BLOCK` 零新增）却也带了标」。要让一条不在台账的动态带标，唯一路径是
   这次绑定 `snsId in deleted()` 判真 ⇒ `applyMark` 走 SHOW ⇒ `ensureMark` 主动 `addView`
   ⇒ 与 `match=hits` 同步 +6 完全吻合 ⇒ 只能是 A 段的 `resolve()` 串位。
   这条把「用户能看见的两条灰标」与「源码插标路径」直接对上，不靠内部计数器单独下结论。
4. 诚实边界：`onBind` 的 `view == null` 分支（`:384-388`）确实没有 `removeMark`，
   但此时 `itemViewOf` 返回 null、拿不到 View 本就无法操作，且真机无 `noitemview` 日志；
   该分支不是本次成因。不过「负路径必须清标」这一契约仍要用纯判据 + 单测锁死（见 [DONE-3] 修复 2），
   以满足验收「上下滚动 / ViewHolder 复用不串位、不残留」。

### 根因一句话

> `resolve()` 用「按 `0,1,-1,2,-2` 取第一个 `nameOk` 通过的偏移」代替「封面头导致的全局固定错位」；
> 同一好友 verity 两条相邻动态时作者名护栏失去区分力，`d=0` 假命中相邻的真·被删动态 snsId，
> 查表命中 ⇒ 新发未删动态被误挂灰标；View 复用残留不是本次成因。

---

## [DONE-3] 修复方案

### 方案总览（两条修复，各自纯判据化 + 单测锁死）

误报的必要条件只有一个：`applyMark` 里 `snsId in deleted()` 成立，而 `snsId` 是**串位**来的。
所以修复分两层，**都只改允许的 3 个文件**：

- **修复 1（治本，堵 A 段串位）**：把 `resolve()` 的锚定从「每条 item 各自按 `0,1,-1,2,-2`
  取第一个 `nameOk` 通过的偏移」改成「**沿用已校准的全局固定错位常数；未校准且窗口歧义时保守放弃**」。
  错位常数由「窗口里恰好一个候选通过护栏」的**非歧义绑定**校准（真机屏上「万华化学—李思远pc」
  那条非同名动态即校准源）。新增纯判据 `SnsDeletedTagLogic.anchorOffset(passing, learned)`。
- **修复 2（保验收 3，把契约锁死）**：新增纯判据 `SnsDeletedTagLogic.markDecision(...)`，
  把「一次绑定该 SHOW 还是 HIDE」收敛成**互斥两分支**，`applyMark` 对 `HIDE` **一律 `removeMark`**
  ⇒ 复用负路径（解析失败 / 未命中 / 样式关 / snsId=0）必然清掉遗留灰标，重复绑定同一条不重复插标。

### 修复 1：锚定判据（`SnsDeletedTagLogic.kt` 新增 + `SnsDeletedTag.kt:486-520` 改造）

新增纯函数（零 Android 依赖，可 JVM 单测）：

```kotlin
/**
 * 一次绑定的窗口锚定决策。
 * passing  = 按 0,1,-1,2,-2 顺序排列的、通过身份护栏（nameOk）的偏移列表；
 * learned  = 已校准的「封面头导致的全局固定错位常数」（null = 尚未校准）。
 * 返回这一格该用的偏移；null = 无法可靠锚定（保守，不打标）。
 *
 * 互斥三分支（顺序即优先级，不并列）：
 *  1. learned 非空且本次仍通过护栏 ⇒ 沿用 learned（同名相邻窗口里抵御 d=0 的假命中）；
 *  2. 否则 passing 恰好一个 ⇒ 采信它（非歧义，可拿来校准错位常数）；
 *  3. 否则（歧义且无 learned）⇒ null（漏标好过打错标）。
 */
fun anchorOffset(passing: List<Int>, learned: Int?): Int? {
    if (learned != null && learned in passing) return learned
    return passing.singleOrNull()
}
```

`resolve()` 改造要点（逐条对应 A 段每一环）：

| A 段环节 | 改法 |
|---|---|
| A0 固定错位 | `offsets` 的 key 由 `"类名#list.size"`（`SnsDeletedTag.kt:496`）改为**仅 adapter 类名**：错位是封面头常数，与列表长度无关；带 size 会让「新发一条动态」把校准值冲掉（A5 放大器）。 |
| A1 护栏 | `nameOk` **不动**：它仍是防「标到别人动态上」的护栏；只是不再拿它当「区分同一人两条动态」的依据（本就做不到）。 |
| A2/A3 串位 | 删掉「缓存先验直接 return」（`:497-501`）与 `pickOffset` 取第一个（`:510`）；改为收集 `passing` 后调 `anchorOffset(passing, offsets[key])`。同名相邻窗口在已校准时**优先沿用 learned**（正确错位），不再被 `d=0` 带偏。 |
| A5 校准 | 仅当 `passing.size == 1`（非歧义）时用该唯一偏移更新 `offsets[key]`（`d==0` 时不缓存，沿用原语义）；歧义且沿用 learned 成功时**不动**缓存。 |
| 放弃路径 | `anchorOffset` 返回 null ⇒ `lastMiss="ambig(pos=.. passing=.. learned=..)"`、返回 null ⇒ 走 `onBind` 负路径（HIDE ⇒ `removeMark`），**不插标**。 |

`pickOffset`（`SnsDeletedTagLogic.kt:171-176`）**保留不改**：它是「按优先级取第一个通过」的底层纯判据，
原有 4 条单测（`SnsDeletedTagLogicTest.kt:239-274`）全部保留、不回归。

### 修复 2：渲染决策判据（`SnsDeletedTagLogic.kt` 新增 + `SnsDeletedTag.kt:626-635` 改造）

```kotlin
enum class MarkAction { SHOW, HIDE }

/**
 * 一次绑定的渲染决策（互斥两分支，对着用户可见事实）：
 * 只有「样式开 + 解析出 snsId + snsId 非 0 + 命中已删清单」才 SHOW（插灰标）；
 * 其余所有情形一律 HIDE —— 调用方对 HIDE 必须 removeMark，清掉复用 View 上的残留灰标。
 */
fun markDecision(styleOn: Boolean, resolved: Boolean, snsId: Long, inDeleted: Boolean): MarkAction =
    if (styleOn && resolved && snsId != 0L && inDeleted) MarkAction.SHOW else MarkAction.HIDE
```

- `applyMark(view, snsId, resolved)` 改吃该判据：`SHOW` ⇒ `match++` + `ensureMark(view)`；
  `HIDE` ⇒ `removeMark(view)`。`resweep()`（`:772-781`）仍调 `applyMark`，默认 `resolved=true`。
- `onBind` 的 `info == null` 分支（`:390-399`）由「直接 `removeMark`」改为
  `applyMark(view, 0L, resolved = false)` ⇒ 经判据得 `HIDE` ⇒ `removeMark`，
  让「解析失败」这条负路径也走统一判据（判据的 `resolved` 参数在生产里有真实区分）。

### 三条用户可见验收如何被满足

| 验收（用户看得见） | 由哪条修复保证 |
|---|---|
| ① 新发的、没被删的动态**绝不**挂灰标 | 修复 1：新动态在已校准时锚到 `d=O`（自己那条，`notInDel`）⇒ `markDecision` 得 HIDE。即便错位常数尚未校准且窗口歧义，`anchorOffset` 返回 null ⇒ resolve 失败 ⇒ HIDE。**两条路径都不插标**。 |
| ② 真·被删动态**仍然**挂灰标（不回归 `match=hits`） | 修复 1：真被删动态锚到 `d=O` 得到自己的 snsId（在表里）⇒ SHOW。错位常数由屏上非同名动态（第 3 条万华）的非歧义绑定校准，与「列表长度」解耦，新发动态不会冲掉它。 |
| ③ 上下滚动 / 复用不串位、不残留、重复绑定不重复插标 | 修复 2：`HIDE` 一律 `removeMark`（递归整棵子树）；`ensureMark` 开头仍先 `removeMark` 再插（`SnsDeletedTag.kt:647`），同一 View 重复绑定只留一个标。修复 1 保证「同一被删 id 不会被锚到多个不同 itemView」，从源头消除串位。 |

### 为什么不破坏既有契约（逐条）

- **排除自己**：写侧 `shouldRecord`（`SnsDeletedTagLogic.kt:79-80`）、`isSelf`（`:66-67`）、
  `onBlocked`（`SnsDeletedTag.kt:216-234`）**一行未动**；本次只改读侧渲染锚定，不碰落台账判据。
- **bit1 口径**：`isTimelineLoss`（`:57-58`）只盯 `BIT_TIMELINE`、与相册位无关，**一行未动**。
- **分隔线位置**：`tailIndex(root)`（`:685-692`）把灰标插到末尾 1px `android.view.View` 之前、
  `MARK_LEFT_DP=72`（`:96`）、`bodyTextPath`（`:231-243`）**一行未动**；
  修复只改「哪个 snsId 该插标」，不改「插在哪里 / 怎么插」。
- **MARK_KEY / tag 契约**：`MARK_KEY = R.id.wxgroup_sns_mark`（`:107`）、`MARK_MAGIC`（`:110`）、
  `tv.setTag(MARK_KEY, MARK_MAGIC)`（`:666`）、`removeMark` 的 `getTag(MARK_KEY)==MARK_MAGIC`（`:700`）
  全部不动；⛔ 绝不用 `View.generateViewId()`。`res/values/ids.xml` 不改。
- **缓存 / gen / 诊断**：`deleted()`、`cacheUsable`、`stampAfterBuild`、`gen`、`stat()` 字段语义不动；
  仅新增 `lastMiss` 的 `ambig(...)` 文本，便于真机区分「保守放弃」与「解析不出 snsId」。

### 诚实边界（不吹）

- 错位常数需一次「非歧义窗口」绑定来校准。若用户一进页面、可见范围内**全是同一人的动态**
  （无非同名校准源），则 `learned` 为空 + 窗口歧义 ⇒ `anchorOffset` 返回 null ⇒ **保守不打标**
  （连真被删也不标）。这是「漏标好过打错标」（`SnsDeletedTagLogic.kt:149` 既定纪律）的预期降级，
  滚到有其它作者的一屏即自动校准。⛔ 修正（第二轮 B1）：即便屏上有第 3 条非同名动态，校准也只发生在
  它**绑定时**的那一刻（按 position 排在最后）——**校准发生前的所有同名相邻绑定都会保守放弃**
  （含真·被删那条），需靠校准后的 `resweep` 补标（见 [DONE-8]）。
- 判据吃的是 `nameOk`（作者名）与 `textsOf` 全文语料；`docs/_r37_name_anchor.md` 记录过更严的
  「作者行类名限定」方案，当前 R42 代码并未采用。本轮**不扩大范围**去改语料角色判定
  （那是另一个独立风险，且会触及 `collectVisTexts` 等 View 侧零覆盖代码），只在现有护栏之上
  用「全局错位常数」补齐同名区分力。

---

## [DONE-4] 实现（只改允许的 3 个文件）

| 文件:行号 | 改动 |
|---|---|
| `SnsDeletedTagLogic.kt:198-201` | 新增 `anchorOffset(passing, learned): Int?`，互斥三分支（沿用 learned / 唯一通过 / 歧义保守 null）。 |
| `SnsDeletedTagLogic.kt:203-210` | 新增枚举 `MarkAction { SHOW, HIDE }`。 |
| `SnsDeletedTagLogic.kt:220-221` | 新增 `markDecision(styleOn, resolved, snsId, inDeleted): MarkAction`，互斥两分支。 |
| `SnsDeletedTagLogic.kt:162-170` | 仅改 `pickOffset` 的 KDoc：注明生产锚定已改用 `anchorOffset`、本函数不再被 `resolve()` 调用；**函数体与其 4 条单测一行未动**。 |
| `SnsDeletedTag.kt:480-488` | `offsets` 声明的 KDoc 重写：key 语义从「上次成功非零偏移」改为「已校准的全局错位常数」。 |
| `SnsDeletedTag.kt:501-533` | `resolve()` 重写：`key = adapter.javaClass.name`（去掉 `#list.size`）、删缓存先验直接 return 与 `pickOffset`，改收集 `passing` 后交 `anchorOffset(passing, offsets[key])`；仅 `passing.size==1` 时校准缓存；`anchorOffset` 返回 null ⇒ `lastMiss="ambig(...)"` + 返回 null（负路径）。 |
| `SnsDeletedTag.kt:390-403` | `onBind` 的 `info==null` 分支由「直接 `removeMark(view)`」改为 `applyMark(view, 0L, resolved=false)` ⇒ 经统一判据得 HIDE ⇒ `removeMark`。 |
| `SnsDeletedTag.kt:644-658` | `applyMark` 增 `resolved: Boolean = true` 参数并改吃 `markDecision`；SHOW ⇒ `match++`+`ensureMark`，HIDE ⇒ `removeMark`。`resweep()`（`:802`）仍调它，默认 `resolved=true`。 |

`SnsGuard.kt` / `SnsBlockProbe.kt` / `SnsBitLogic.kt` / `GroupDb.kt` / `Keys.kt` /
`EntryReceiver.kt` / `res/values/ids.xml` **均未触碰**；`tailIndex` / `bodyTextPath` /
`MARK_KEY` / `MARK_MAGIC` / `shouldRecord` / `isTimelineLoss` / `isSelf` **均未改动**。

---

## [DONE-5] 单测（`SnsDeletedTagLogicTest.kt` 新增 12 例，39 → 51）

新增段位于 `SnsDeletedTagLogicTest.kt:407` 起，全部为纯 JVM 判据、零 Android 依赖。
按 team-lead 指定的三个场景 + 串联场景分类：

| 组 | 用例 | 锁住的分支 / 用户可见事实 |
|---|---|---|
| **相邻同名** | `anchor_offset_adjacent_same_name_without_learned_is_conservative_null` | `passing=[0,-1]`、`learned=null` ⇒ null（旧 pickOffset 会返回 0 ⇒ 串位）；锁分支 3。 |
| 相邻同名 | `anchor_offset_adjacent_same_name_with_learned_prefers_learned_over_zero` | `passing=[0,-1]`、`learned=-1` ⇒ -1（**核心修复**：不被 d=0 带偏）；含 `learned=0` 合法沿用；锁分支 1。 |
| **非同名候选** | `anchor_offset_single_passing_is_accepted_as_calibration` | 窗口恰好一个通过（非同名区）⇒ 采信、可校准；锁分支 2。 |
| 边界 | `anchor_offset_empty_passing_is_null` | 空 passing 任何 learned 都 ⇒ null。 |
| 边界 | `anchor_offset_stale_learned_falls_back_to_single` / `..._with_ambiguous_is_null` | learned 失效时的非歧义 / 歧义两条路径。 |
| **复用负路径** | `mark_decision_hide_when_not_resolved_clears_recycled_mark` | 解析失败 ⇒ HIDE（即使 inDeleted 误传 true 也 HIDE，互斥）。 |
| 复用负路径 | `mark_decision_hide_for_new_post_not_in_deleted` | 新发未删（不在表）⇒ HIDE（验收①）。 |
| 复用负路径 | `mark_decision_hide_when_style_off` / `..._hide_when_snsid_zero` | 样式关 / snsId=0 两条负路径 ⇒ HIDE。 |
| 不回归 | `mark_decision_show_only_for_resolved_deleted_id` | 四条件全成立 ⇒ SHOW（验收②）。 |
| 串联 | `scenario_adjacent_same_name_only_real_deleted_post_is_marked` | `anchorOffset` 与 `markDecision` 串联：同一 learned 下，新发格 HIDE、真被删格 SHOW，同时守验收①②。 |

判据期望值全部从「用户能看见的事实」推导，**不是照着实现抄**（例如 `anchorOffset([0,-1], -1)` 期望
`-1` 来自「新动态该锚到自己那条」，而非 `singleOrNull()` 的返回值）。既有 39 例（含 `pickOffset`
4 例）全部保留未改，新增 12 例 ⇒ 共 **51 例**（实测 `TEST-com.lx.wxgroup.feature.SnsDeletedTagLogicTest.xml` `tests="51"`）。

---

## [DONE-6] 构建 / 单测 / 变异验证

### 环境（严格按纪律）

- JDK：`G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot`（`Test-Path java.exe` = True）。
- ⚠️ Git Bash 的 PATH 会污染 `java.library.path` 导致「0 用例假绿」⇒ **全程用 PowerShell 工具**，
  且每次调用开头收窄：
  `$env:PATH='G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot\bin;C:\Windows\System32;C:\Windows'`、
  `$env:JAVA_HOME='G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot'`。
- 姿势：`Push-Location '…/wxgroup'; & '.\gradlew.bat' assembleDebug testDebugUnitTest --rerun-tasks …; Pop-Location`。
  ⛔ 不从 Bash 调 `cmd.exe`。`--rerun-tasks` 必带（`gradle.properties` 有 `org.gradle.caching=true`，
  不带会被 FROM-CACHE 骗绿）。
- Kotlin compile daemon 连不上时 Gradle 自动 `Using fallback strategy: Compile without Kotlin daemon`，
  编译照常成功（非致命，日志里的 `e: java.rmi.NoSuchObjectException` 不是测试失败）。

### 构建 / 全量单测（还原后重跑，`--rerun-tasks`）

| 项 | 结果 |
|---|---|
| `assembleDebug` | BUILD SUCCESSFUL，`GRADLE_EXIT=0` |
| `testDebugUnitTest` | **41 个 XML / 812 例 / 0 失败 / 0 错误 / 0 跳过** |
| `SnsDeletedTagLogicTest` | **51 例 / 0 失败** |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk`，2,898,530 bytes |
| 用例数非 0 核验 | 直接读 `app/build/test-results/testDebugUnitTest/TEST-*.xml` 的 `<testsuite tests=…>` 汇总；`build/` 被 .gitignore 忽略，Glob 看不到 ⇒ 用 PowerShell `Get-ChildItem` 读。 |
| 确实重跑的旁证 | 两次全量 XML 的 `timestamp` 从 `18:17:04` 推进到 `18:27:03`（机器本地时钟与「今天 11-07」有时差，但时间在推进）。 |

### 变异验证（8 个有语义变异 + 1 个等价变异对照）

每个变异都改 `SnsDeletedTagLogic.kt` 一处、删 `test-results` 后跑
`testDebugUnitTest --tests com.lx.wxgroup.feature.SnsDeletedTagLogicTest`，从 XML 读 `failures` 与
`<failure>` 用例名；`try/finally` 保证每次必还原。

| # | 改坏哪条分支 | 变红用例（实测方法名） | 数 |
|---|---|---|---|
| M1 | `anchorOffset` 分支 1「learned 优先」（注释掉该行） | `anchor_offset_adjacent_same_name_with_learned_prefers_learned_over_zero`、`scenario_adjacent_same_name_only_real_deleted_post_is_marked` | 2 |
| M2 | `anchorOffset` 分支 3「歧义保守」（`singleOrNull()`→`firstOrNull()`，即退回旧 pickOffset 猜第一个） | `anchor_offset_adjacent_same_name_without_learned_is_conservative_null`、`anchor_offset_stale_learned_with_ambiguous_is_null` | 2 |
| M3 | `anchorOffset` 分支 2「唯一通过采信」（→恒 `null`） | `anchor_offset_single_passing_is_accepted_as_calibration`、`anchor_offset_stale_learned_falls_back_to_single` | 2 |
| M4 | `markDecision` 去掉 `inDeleted` | `mark_decision_hide_for_new_post_not_in_deleted`、`scenario_adjacent_same_name_only_real_deleted_post_is_marked` | 2 |
| M5 | `markDecision` 去掉 `resolved` | `mark_decision_hide_when_not_resolved_clears_recycled_mark` | 1 |
| M6 | `markDecision` 去掉 `styleOn` | `mark_decision_hide_when_style_off` | 1 |
| M7 | `markDecision` 去掉 `snsId != 0L` | `mark_decision_hide_when_snsid_zero` | 1 |
| M8 | `markDecision` SHOW 分支改恒 HIDE | `mark_decision_show_only_for_resolved_deleted_id`、`scenario_adjacent_same_name_only_real_deleted_post_is_marked` | 2 |
| **EQ（等价变异对照）** | `return passing.singleOrNull()` → `return if (passing.size == 1) passing[0] else null`（语义完全等价） | **0 条变红（全绿）** | 0 |

⇒ 8/8 有语义变异全部变红，且每条分支都有**对口**用例（没有「随便改一处就全红」的同构现象）；
等价变异 EQ 保持全绿 ⇒ 证明「变红不是随便改动都会发生」，判据确实在守分支。

### 还原自证（「还原后确认文件 sha 一致」）

三个允许改动文件的 SHA256 基线（实现完成、变异之前）：

| 文件 | SHA256 |
|---|---|
| `SnsDeletedTag.kt` | `F927D922060706D6D89B8E176171BD3EE620982F78D946BB1AD28B50D7924ED9` |
| `SnsDeletedTagLogic.kt` | `705BCF460A557DC9BF26B4419E8B4756364033E01A1BC90046095075ABD192FE` |
| `SnsDeletedTagLogicTest.kt` | `F68E830A648D908750C6DBCF0123309E3560D4A133C4755B0B157F0886080692` |

- 变异脚本结束 `FINAL_SHA=705BCF46…`，`MATCH_BASE=True`；
- 脚本外再独立复算三文件 sha，**与基线逐字一致**；
- 之后才跑最终全量 `--rerun-tasks`（上面的 812 例）。

> 过程留痕：第一次变异脚本用了 `$ErrorActionPreference='Stop'` + `2>$null`，PowerShell 5.1 把
> gradlew 的 native stderr 抛成 `NativeCommandError` 中止脚本、未还原，文件一度停在 M1+M2 变异态。
> 已用 sha 对比发现并**手动还原**（`if (learned != null && learned in passing) return learned` +
> `return passing.singleOrNull()`），确认 sha 回到基线后改用 `try/finally` + 不设 Stop 的健壮脚本重跑。
> 这段如实记录，说明「还原自证」不是走过场。

---

## [DONE-7] 总裁决

**根因**：`resolve()` 用「按 `0,1,-1,2,-2` 取第一个 `nameOk` 通过的偏移」做 pos→dataList 锚定
（`SnsDeletedTag.kt:501-533` 改前、`SnsDeletedTagLogic.kt:171-176` `pickOffset`）；封面头导致
pos/dataList 存在全局固定错位，同一好友 verity 两条相邻动态时作者名护栏 `nameOk` 失去区分力，
`d=0` 假命中相邻真·被删动态的 snsId ⇒ 查表命中 ⇒ 新发未删动态被误挂灰标。
`offsets` key 含 `list.size`（新发动态使其失效）放大了该串位。**View 复用残留不是本次成因**
（负路径三处 `removeMark`，且真机 `match==hits` 同步 1→7 证明是主动插标而非残留）。

**修复**：① `anchorOffset(passing, learned)` 沿用全局错位常数、歧义且未校准时保守放弃；
`offsets` key 去掉 `list.size`。② `markDecision` 把渲染收敛为互斥 SHOW/HIDE 两分支，
HIDE 一律 `removeMark`（锁死复用负路径清标）。`nameOk` / `shouldRecord` / `isTimelineLoss` /
`tailIndex` / `MARK_KEY` / `ids.xml` 一行未动。

**三条用户可见验收**（静态判据 + 单测，真机待装包复核）：

| 验收 | 结论 |
|---|---|
| ① 新发未删动态绝不挂灰标 | ✅ 已校准时锚到自己（notInDel）⇒ HIDE；未校准且歧义 ⇒ 保守 null ⇒ HIDE。两条路径都不插标。 |
| ② 真被删动态仍挂灰标 | ✅ 锚到自己（inDel）⇒ SHOW；错位常数由非同名区校准、与列表长度解耦，不被新发动态冲掉。 |
| ③ 滚动/复用不串位、不残留、不重复插标 | ✅ HIDE 必 `removeMark`；SHOW 的 `ensureMark` 先 `removeMark` 再插；`anchorOffset` 杜绝同一被删 id 锚到多个 itemView。 |

**构建/变异**：`assembleDebug` + `testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL；
812 例 0 失败（本类 51 例）；8 个语义变异逐分支变红、1 个等价变异全绿；还原 sha 与基线一致。

**是否可装包**：产物 `app-debug.apk` 已生成、构建通过，**从构建角度可装包**；但按硬边界
⛔ **本轮未装包 / 未 adb / 未真机**，三条验收均为静态判据与单测层面的结论，**必须下一轮真机复核**
（按修复前基线 `wxgroup/_cache/ui_fp.xml` 逐条比对：**y=1742 灰标消失、y=2915 灰标保留**、
上下滚动不新增/不串位；⛔ 修正（第二轮 B1）：`op=snsdel sub=stat` 里 `lastMiss` 出现 `ambig(...)`
**首屏出现需重点复核**——冷启首屏同名相邻窗口保守放弃后，必须靠校准后的 `resweep` 补标，否则即 B1 回归）。

---

## [DONE-8] B1 修复：冷启首屏真·被删动态灰标消失（第二轮）

> 档位=Pro 依据=跨绑定时序 + Hook 链路 + 并发防重入
> 作者：tagfix2（第二轮实现）
> 日期：2026-11-07

### B1 推演（为什么首屏两条灰标全没）

上一轮 `anchorOffset` 修复本身正确、**不推翻**，但它留下一个时序缺口：`offsets`（`SnsDeletedTag.kt:510`）
是 object 实例字段，**冷启为空**（LSPosed 装包必重启微信 ⇒ 冷启是默认测试条件）⇒
`learned = offsets[key]`（`SnsDeletedTag.kt:534`）= null。

首屏按 position 递增绑定：
- pos0 封面项：`passing=[]`，无法校准；
- pos1 新动态（verity）：`passing=[0,-1]`，同名相邻 ⇒ 歧义；
- pos2 真·被删动态（verity）：`passing=[-1,-2]`，同样歧义；
- 两者都走 `anchorOffset` 分支 3 返回 null（`SnsDeletedTagLogic.kt:198-201`）⇒ `resolve` 返回 null ⇒ HIDE
  ⇒ **连 pos2 那条真被删的灰标也消失**；
- 校准只在 pos3（「万华化学—李思远pc」，`passing=[-1]`）**绑定时**发生（`SnsDeletedTag.kt:550-552`），
  按 position 排在最后 ⇒ 必然晚于前两条；
- 校准后**不会重绘**：`resweep()` 全工程只有 `setStyle` / `purge` 两个调用点；首轮全 HIDE、零 `addView`
  ⇒ 无第二次 layout；被 HIDE 的 view 还被 `bound.remove(view)`（改前 `:396`）移出，`resweep` 只重放 bound 里的
  旧 snsId、不重新 resolve ⇒ 必须用户滚动才恢复。

⇒ 用户可见：装包后一进朋友圈，首屏两条灰标全没了（其中 pos2 是本该正确显示的），属功能回归。

### B4 落地（已对齐，不新增后台线程）

| 文件:行号 | 改动 |
|---|---|
| `SnsDeletedTag.kt:167-188` | `bound` 由 `WeakHashMap<View, Long>` 扩展为 `WeakHashMap<View, BoundEntry>`（值保存 `adapter`/`pos`/`snsId`/`resolved`）；新增 `resweeping` 布尔。 |
| `SnsDeletedTag.kt:409-421` | `onBind` 的 `info==null` 分支**不再 `bound.remove(view)`**，改 `bound[view]=BoundEntry(adapter,pos,0L,resolved=false)`（记为 pending），仍 `applyMark(view,0L,resolved=false)` 清残留。 |
| `SnsDeletedTag.kt:423-425` | 成功路径 `bound[view]=BoundEntry(adapter,pos,info.snsId,resolved=true)`。 |
| `SnsDeletedTag.kt:543-558` | `resolve()` 在校准块（`:550-552`）之后加 `if (shouldResweep(learned, offsets[key])) resweep()`——**触发条件与 `d` 解耦**，靠 before/after 状态判定；⛔ `:551` 的 `if (d != 0) offsets[key] = d else offsets.remove(key)` 语义**一字未动**。 |
| `SnsDeletedTag.kt:822-856` | `resweep()` 重写：resolved=true 的 view 仍用旧 snsId 重放 `applyMark`（不重新 resolve）；resolved=false（pending）用保存的 `(adapter,pos)` 重新 `resolve`，成功则写 bound + `applyMark(snsId)`，失败保持 HIDE；保留 `!isAttachedToWindow` 清理。 |
| `SnsDeletedTag.kt:829-831,854-856` | `resweeping` 布尔 + `try/finally` 防重入（`resolve` 校准成功会回调 `resweep`，而 `resweep` 又调 `resolve`；`offsets.remove` 后另一条 pending 再校准成功会再触发）。 |
| `SnsDeletedTagLogic.kt:203-217` | 新增纯判据 `shouldResweep(before, after)`：`before == null && after != null && after != 0`（只有「首次从 null 校准成非零值」才触发；`after != 0` 刻意挡住「把 0 当错位常数缓存扩散成新错标」）。 |

### 单测（`SnsDeletedTagLogicTest.kt` 新增 3 例，51 → 54）

| 用例（文件:行号） | 锁住的分支 / 用户可见事实 |
|---|---|
| `should_resweep_triggers_on_first_nonzero_calibration`（`:474`） | `null→-1` 触发、`null→1` 触发（首次非零校准必须 resweep 补标）。 |
| `should_resweep_does_not_trigger_on_zero_or_non_first_calibration`（`:487`） | `null→0` / `null→null` / `-1→-1` / `-1→null` 均不触发（0 不缓存 ⇒ after 仍 null；已校准/退化不触发，避免无谓重绘与递归）。 |
| `scenario_cold_start_resweep_recovers_real_deleted_mark`（`:587`） | 冷启首屏时序：pos2 同名相邻 `learned=null` ⇒ 保守 null；pos3 非同名校准 `learned=-1` 后，用 `(adapter,pos)` 重新 `resolve` pos2 ⇒ `d=-1` ⇒ 锚到自己 ⇒ 真被删 snsId ⇒ **SHOW（补标成功）**。 |

⛔ 既有两条守门用例 `mark_decision_hide_when_style_off`、`mark_decision_hide_when_not_resolved_clears_recycled_mark`
**未删未改**（仍是 0 失败）。

### 构建 / 变异验证（PowerShell 收窄 PATH + JDK 21，`--rerun-tasks`）

| 项 | 结果 |
|---|---|
| `assembleDebug testDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL，`GRADLE_EXIT=0` |
| 全量单测 | **41 个 XML / 815 例 / 0 失败 / 0 错误 / 0 跳过**（读 XML `<testsuite tests=…>` 汇总，UTF-8 显式解码防中文用例名破坏解析） |
| `SnsDeletedTagLogicTest` | **54 例 / 0 失败**（51 + 3） |
| 变异 M1（去掉 `before==null`） | 1 失败（`should_resweep_does_not_trigger…`）变红 |
| 变异 M2（去掉 `after!=null`） | 1 失败变红 |
| 变异 M3（去掉 `after!=0`） | 1 失败变红（正是「null→0 不触发」分支） |
| 变异 M4（pending 决策：`anchorOffset` 分支 1 `learned in passing` 短路） | **3 失败**变红，含 `scenario_cold_start_resweep_recovers_real_deleted_mark`（pending 重扫决策被守） |
| 等价变异 EQ（`a&&b&&c` → De Morgan `!(¬a‖¬b‖¬c)`） | 0 失败（全绿） |
| 还原自证 | 变异后 `SnsDeletedTagLogic.kt` sha `E6F4C064…` 与基线逐字一致；三文件 sha 与基线一致后重跑全量 815 例 0 失败 |

### 下轮项（登记，本轮不动）

- **N1**：`anchorOffset` 分支 1「`learned in passing` 沿用」可能沿用**过期**错位常数（例如列表结构变化后
  常数已不匹配但恰好仍通过同名护栏）⇒ 潜在新错标。当前无真机证据触发，暂缓。
- **N6**：O=0（无封面头错位）时 `learned=0` 不可缓存（`d==0` 被 `offsets.remove` 移除）⇒ 每屏都要重猜、
  功能退化。本轮刻意不动 `:550`（防「把 0 当错位常数缓存后扩散成新错标」）。

**未做的未验证项**：按硬边界 ⛔ 本轮**未装包 / 未 adb / 未真机 / 未 commit**；B1 修复的最终生效
（冷启首屏 pos2 灰标保留）仍是静态判据 + 单测层面的结论，须下一轮真机装包按
`wxgroup/_cache/ui_fp.xml` 基线复核（y=2915 灰标保留、y=1742 灰标消失）。
