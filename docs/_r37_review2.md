# R37 装包前交叉复核（Pro 档 lane 2：灰标作者行锚定 + K7 裁决探针）

> 复核对象：`git diff` 全量 7 文件 / +825 −123（含测试与文档）
> 复核方式：逐文件读 diff 原文 + 读被调用方的当前实现（Kv / GroupDb / ConvReader / ScrollDock 挂载点 / rebuild 线程模型）+ 走查 `_mut.py` M1~M5
> ⛔ 未跑 gradle / 未跑 adb / 未改任何源码
> 以下所有行号均为**改动后**的行号

---

## 1. 结论

**`GO-WITH-CONDITIONS`**

一句话理由：**判据链本身写得符合硬约束（认不出 ⇒ 不打标、`pickWindow`/`snsIdOf` 一行未动、探针默认关且没碰判据），但 `collectVisTexts` 让「空文本也占 20 条预算」把作者行的可达范围**严格变小**了，而其 `noauthor(cls=[...])` 诊断**无法区分「类名漂了」与「采样被 20 截断」** ⇒ 装包前必须先把这两个副作用堵掉（P0-1 / P0-2），否则这轮最贵的失效形态（Painted=1 → 0，且按文档给的修法还可能把 `AUTHOR_VIEW_CLS` 改成错误的类名 ⇒ 打错标签）会现场重演。

条件：

| # | 装包前必须处理 | 代价 |
|---|---|---|
| C1 | P0-1（20 条预算被空文本挤占） | 1~3 行 |
| C2 | P0-2（`noauthor` 诊断缺 seen 计数，会把人引导到错误修法） | 1 行日志字段 |
| C3 | 真机前 3 分钟按 §5 三条判据取数，**没过就停手**（别把 12 次探测预算烧光） | — |

---

## 2. P0 清单

### P0-1 `collectVisTexts` 的空文本开始占用 20 条预算 ⇒ 作者行可能被采样窗口**截断**，功能从「能用」变「一条标签都不打」

- **文件:行**：`app/src/main/java/com/lx/wxgroup/feature/SnsDeletedTag.kt:847-864`（`collectVisTexts`）、判据落点 `SnsDeletedTag.kt:805-813`（`rows==0 ⇒ return null`）+ `SnsDeletedTagLogic.kt:255`
- **现状**（diff 原文，改动后半段）：
  ```kotlin
  if (v is TextView) {
      if (v.visibility == View.VISIBLE) {
          val t = runCatching { v.text?.toString() }.getOrNull()?.trim().orEmpty()   // ← 空也进
          out.add(SnsDeletedTagLogic.VisText(t, classChain(v.javaClass)))            // ← 空也占预算
      }
  }
  ```
  入口的闸门是 `if (out.size >= max || d > 8) return`（第 850 行，`max` 默认 **20**）。
  改动**前**：`out` 里只有**非空**文本 ⇒ 作者行只要在「第 20 个**非空** TextView」之前就一定被采到。
  改动**后**：`out` 里是所有**可见** TextView（含空） ⇒ 作者行必须在「第 20 个**可见** TextView」之前才被采到。
  设 `E` = 作者行之前的可见**非空** TextView 数、`V` = 作者行之前的可见 TextView 总数，`E ≤ V` ⇒ **新判据严格更紧**，且在 `V ≥ 20 > E` 时直接从「采得到」变「采不到」。item 子树本身很密：`docs/_r35_sns_tag.md:873` 的真机 dump `SNS_DELTAG tree depth=4 host=FrameLayout paths=37 [...]`（深度 ≤4 就 37 条叶子路径），且 `docs/派发审计台账.md:1460` 的取证原文是 `ImproveContentView > ImproveTextView("")` ×3（空）+ 正文 + 作者名 `AsyncTextView("王新功")` —— **作者名排在正文之后**。带点赞/评论昵称的富条目越过 20 个可见 TextView 是完全可能的，而静态 diff 无法证否。
- **失效形态**：`rows == 0` 恒定 ⇒ `reconcileAt` 在 `SnsDeletedTag.kt:805` 直接 `return null` ⇒ `probePath` 里 `@` 分支 `continue`（第 758 行）⇒ 退回 `z/E/D` 三条 holder 路径；`srcStillValid` 也因 `reconcileAt` 空而判失配（第 735 行）⇒ 缓存里的 `@` 路径被 remove、反复重探，`takeProbe` 的 12 次预算烧光后彻底静默。上一轮真机 `SNS_DELTAG path m0 -> @`（`docs/_r35_sns_tag.md:607/639/910/1167`）说明**生效路径就是 `@`** ⇒ 表现为 `painted` 从 1 掉到 0、`missed` 与 `noAuthor` 一路涨。**注意：`retryResolve` 帮不上忙** —— 帧与帧之间「可见 TextView 的个数」几乎不变，截断是永久性的，不是渲染竞态。
- **具体修法**（三选一，推荐 ①+③）：
  ```kotlin
  // ① 空/非作者行不再挤占作者行的预算：把采样上限抬到明显高于 item 子树规模
  -    private fun collectVisTexts(root: View, max: Int = 20): List<SnsDeletedTagLogic.VisText> {
  +    private fun collectVisTexts(root: View, max: Int = 48): List<SnsDeletedTagLogic.VisText> {
  ```
  ```kotlin
  // ②（更稳）非作者行允许被截断，作者行永不被截断 —— 语义上更贴设计意图
  //    在 collectVisTexts 里判 AuthorClass 命中与否分桶计数：
  //      hit -> 只要 hitBucket < 16 就 add；miss -> 只有 out.size < max 才 add
  //    代价：View 侧要 import SnsDeletedTagLogic.AUTHOR_VIEW_CLS（纯常量比较，不引入判据）
  ```
  ```kotlin
  // ③ 必须配套：把「总共遇到了多少可见 TextView」带出来，见 P0-2
  ```
  语义上的硬约束（认不出 ⇒ 不打标）**不用动**，动的是「认得出却因为没走遍子树而说没认出」。
  ⚠️ 这条 P0 的**触发条件依赖真机子树规模，静态 diff 无法判定一定会发生**，所以按 P0 处理但不断言已经坏了 —— 判据见 §5-①（滚两屏、一个 grep 就能定性）。
  📌 引用口径：`docs/_r35_sns_tag.md:607/639/910/1167` 是 **R35 时期**的真机日志，用来说明历史上生效路径是 `@`；本轮是否仍如此，以 §5-② 的 `SNS_DELTAG path` 为准。

### P0-2 `noauthor(cls=[...])` 无法区分「类名漂了」与「采集被截断」，而现有 KDoc 的修法会把人带向**打错标签**

- **文件:行**：`SnsDeletedTag.kt:806-811`（`lastRecon = "noauthor(pos=… cls=${corpus.classes})"`）、`SnsDeletedTagLogic.kt:216-227`（`authorCorpusOf` 只在 `continue` 之前记 `classes`，即**只有被采到的那 ≤20 条**的类名进 `classes`）、配套文档 `docs/_r37_name_anchor.md:165`/`104` 与 `SnsDeletedTag.kt:1668` 的 KDoc「把 `cls=` 里的类名抄进 `AUTHOR_VIEW_CLS` 即可」
- **现状**：一旦 P0-1 的截断发生，`cls=` 里**根本不会出现** `AsyncTextView`（它压根没被遍历到），此时它与「类名被 R8 改名」在日志上**完全同形**。
- **失效形态**：按 KDoc 的指引照抄 `cls=` 里的类名（大概率是 `ImproveTextView`，即正文/时间行）⇒ `AUTHOR_VIEW_CLS` 被改成正文控件 ⇒ **正文重新成为对账语料** ⇒ 本轮唯一要堵的洞原样回来，而且是 user-visible 的**打错标签**（9~16 字短正文里提到邻居名字 ⇒ 邻居候选成为唯一命中）。这是本轮唯一一条会导致「打错」而不是「漏打」的失效路径。
- **具体修法**：把「遇到了多少可见 TextView」一起打出来，让截断自己会说话；同时把「不要盲抄 cls」写进 KDoc。
  ```kotlin
  // SnsDeletedTagLogic.kt:184  AuthorCorpus 增加 seen（或复用 rows/classes 之外的第 4 个字段）
  // SnsDeletedTag.kt:850 处顺手累计：每走到一个 VISIBLE 的 TextView 就 seen++
  // SnsDeletedTag.kt:809 日志改成：
  lastRecon = "noauthor(pos=$pos/${list.size} seen=$seen cap=$max cls=${corpus.classes})"
  // 判据：seen >= cap ⇒ 是**采样截断**，不要动 AUTHOR_VIEW_CLS，先把 max 抬上去；
  //       seen <  cap 且 cls 里没有 AsyncTextView ⇒ 才是类名漂了，此时才能照抄 cls。
  ```

### 未发现会导致崩微信的问题（正面结论，附已核查范围）

- 挂点外层 `catch (t: Throwable)` 且不 rethrow（`SnsDeletedTag.kt:432-437`），`collectVisTexts` 外层还有 `runCatching { walk(root, 0) }`（`:863`）⇒ 采样/类名异常最多 miss，不会带崩微信。
- `classChain`（`:878-891`）无递归、双终止条件（`cur != null` + `up < 8` + 遇 `View` break），不会死循环/NPE。
- K7 探针的 `ConvReader.username(null)`（`core/ConvReader.kt:43`）/ `timeMs(null)`（`:50`）都有 `Elvis` 空安全，不会 NPE；`ArrayList<ConvEntry>(n)`/`getItem(n-1)` 有 `n <= 0` 早退（`:145`）。
- `ScrollDock.kt` 新增行不触碰 `pin()`/停靠判据（`:143-146`）。
- 逐行核过的改动行号区间：`SnsDeletedTagLogic.kt` 138-281、`SnsDeletedTag.kt` 129-146 / 783-891 / 1661-1684、`ConvFilter.kt` 98-188 / 1290-1310 / 1497-1517、`ScrollDock.kt` 62-146、`Keys.kt` 274-291 / 440-448、`SnsDeletedTagLogicTest.kt` 736-1046。

---

## 3. P1 清单

| # | 文件:行 | 现状 / 后果 | 修法 |
|---|---|---|---|
| P1-1 | `ScrollDock.kt:134-147`（hook `android.widget.AbsListView.trackMotionScroll`）+ `:72-81` | 时间戳挂在**基类 AbsListView** 上 ⇒ 微信进程里**任何**列表（聊天消息列表、通讯录、朋友圈、搜索结果）滚动都会刷新 `lastScrollMs` ⇒ `K7 perf ... scrolling=true` 可能是假阳性，而它本轮的唯一使命就是把「46ms 落在滚动帧里」变成可对账的事实 | 只在 `ConvFilter.wraps(list)` 为真时才打时间戳（`ConvFilter.kt:540` 的 `wraps` 是一次 `containsKey`，O(1)）：`val lv = param.thisObject as? AbsListView ?: return; if (ConvFilter.wraps(lv)) lastScrollMs = …; pin(lv)` |
| P1-2 | `ConvFilter.kt:143-186` + `:1137`（`rebuild` 在 `mainHandler.post/postDelayed` 里跑）+ `:1515` | 探针 5 段 × n=3006 全是**主线程**且**持有 `synchronized(lock)`**（`ConvFilter.kt:1023`），单次几百毫秒 ⇒ 即使 30s 节流也是肉眼可见的一次冻结；更糟的是 `tail` 段（`:165`）专门用于验证「容器是不是链表」，而若它真是链表，这段就是 O(n²)≈9×10⁶ 次 ⇒ **ANR 风险**，即「取证动作本身制造 ANR」 | ① `snapProbeDue()` 里加一条 `if (ScrollDock.scrolling()) return false`（丢到下一个静止窗口再取，数据已经有了）；② `runSnapProbe` 里把循环上限钳成 `minOf(n, 500)` 并如实把 `n` 打进日志（比例关系不失真） |
| P1-3 | `ConvFilter.kt:169-176` | `probeItem` 为 `null` 时 `ConvReader.username(null)` 立即返回 `""`（`core/ConvReader.kt:43`）、`timeMs(null)` 返回 `0L`（`:50`）⇒ `refl` 段退化成 ~0ms，看起来像「我们侧反射不要钱」⇒ H1/H2 裁决被误导。`refl` 段也是唯一没包 `try/catch` 的循环段（另外三段 `getItem` 都包了，`:149-167`） | `probeItem` 为 null 时跳过 `refl` 并在日志里打 `refl=?`；循环也补一层 try/catch，保持与其他段一致的失败语义 |
| P1-4 | `ConvFilter.kt:117-124` + `:1499-1515` | `Kv.getBool` → `getString` → `GroupDb.getPref` → **SQLite `query`**（`data/GroupDb.kt:1399-1405`，无内存缓存）⇒ 每次 `readSnaps` 都多一次查库，且它落在 `tSnaps..snapsCost` 之内（`:1187-1188`）却落在探针自己 `t0` 之外 ⇒ ① 默认关也付代价；② **把本轮要测的 46ms 基线污染了**（上一轮的 46ms 与这一轮的 `snaps=` 不再同源）；③ `K7 perf` 的 `total - snaps` 会莫名变大 | 先判节流窗口、命中窗口才查 Kv（开关关着时最多 30s 一次查库，而不是每次 rebuild 一次）：`val now = uptimeMillis(); if (now - snapProbeAt < SNAP_PROBE_INTERVAL_MS) return false; val on = …; snapProbeAt = now; return on` |
| P1-5 | `SnsDeletedTagLogic.kt:216-227` | 若 `AsyncTextView` 在同一 item 里还承载了**别的角色**（点赞列表昵称、评论昵称是最可能的几个），那些文本会一起进 `texts`，本轮想堵的洞会以另一种形态回来 | 暂不改实现（无证据显示该类还承载别的角色）；真机盯 `lastRecon=noname(... authors=N)`：正常富条目的 `authors` 应当很小（1~2）；若 `authors` 持续偏大 ⇒ 说明 `AsyncTextView` 在 item 里还有别的用途，届时再收紧为「只取首个 AsyncTextView」或加位置约束 |
| P1-6 | `app/src/test/.../SnsDeletedTagLogicTest.kt:736-1046` | 见 §4-6：M1~M5 都能变红，但**最高风险的那段代码 View 侧 `collectVisTexts` / `classChain` 零覆盖**（JVM 测不了 View），P0-1/P0-2 正好落在这个盲区 | 后续给这层加个可测 seam：把「遍历 + 采样上限」的策略抽成吃 `(View) -> List<View>` 的纯函数，或至少把 `seen`（遇到了多少可见 TextView）交给 Logic 侧算 ⇒ 把**容量/截断语义**下沉到 Logic，别留在 View 侧无人盯的地方 |
| P1-7 | `ConvFilter.kt:149-167` vs 真实 read 循环 `:1501-1506` | `seq` 段紧接着真实 read 循环跑同一个 `getItem(0..n-1)`，缓存预热状态与真实首发不同 ⇒ H1 的绝对值偏乐观（文档 §1.4 的比例比法仍成立，但读 `seq ≈ full` 所以要把它当成「热跑第二遍」，真实首帧更贵 | 日志里补一句 `warmth` 或在 KDoc 里写明「`seq` 是紧接着真实循环的第二遍，真实首帧只会更贵」 |

---

## 4. 逐条回应 7 个重点

1. **类名判定链路** —— `classChain`（`SnsDeletedTag.kt:878-891`）走的是**整条父类链**（自己→祖先，最多 8 层，`cur.superclass` 到 `null` 停，遇 `android.view.View` 提前 break），`isAuthorRowClass`（`SnsDeletedTagLogic.kt:196`）用**严格相等**匹配任一层的 `simpleName`；无死循环（双重终止条件）、无 NPE（`cur != null` 守卫）；`View`/`TextView`/`AppCompatTextView` 都不会被误判成作者行，也不会因出现在链里而被当成别的 ⇒ **OK**。
2. **`rows` 的语义** —— `authorCorpusOf`（`Logic.kt:222-225`）确实「命中作者类就算一行，`rows++` 无条件包含空文本」；`rows==0` 与 `rows>0 且 texts 空` 在 `reconcileAt`（`SnsDeletedTag.kt:805` / `:818-820`）被分成 `noauthor(cls=…)` 与 `noname(... authors=0)` 两条不同的 `lastRecon`，`matchName`（`:255`/`:257`）对应两道独立闸门 ⇒ **语义分开了，符合设计**；⚠️ 但 `rows==0` 有**第三种成因**（P0-1 的采样截断）是现有诊断分辨不出来的 ⇒ 见 P0-2。
3. **性能** —— 有缓存，`classChainCache`（`SnsDeletedTag.kt:876`）key = **完整类名**，值 ≤ 见过的 View 类数量（几十~几百，**有界**，`Collections.synchronizedMap`）；每次 bind 增量成本 = ≤20 次 synchronizedMap 查表 + ≤20 个 `VisText` 小对象 + 首次 per-class 最多 8 次 `superclass`（之后命中缓存）；相对改动前只多了「不再过滤非空」带来的 ~几个对象分配 ⇒ **不是新的卡顿源**，K7 的 46ms 与它无关（不在同一路径）。
4. **探针本身** —— 默认关成立：`snapProbeDue`（`ConvFilter.kt:117-124`）用 `runCatching{}.getOrDefault(false)`，且 `Kv.getBool` 一路回落到 `Prefs` 里新登记的 `"false"`（`Keys.kt:443-446`），DB 没开也返回默认 ⇒ **默认关是真的**；节流 30s 逻辑正确（`snapProbeAt` 初值 0 ⇒ 首次立即取一次，之后 30s）；**判据零污染**（`out` 在探针之前独立构造完毕，`runSnapProbe` 只计时只打日志，无复用局部变量、无提前 return，`ConvReader.username(null)`/`timeMs(null)` 都不会抛）；**性能不安全**：主线程 + 持锁 + 5×n 循环，链表容器候选下可能 ANR ⇒ 见 P1-2 / P1-3 / P1-4。
5. **`ScrollDock.kt` 的改动** —— 没有改到原有行为：唯一新增的一行 `lastScrollMs = uptimeMillis()`（`:144`）写在 `pin(param.thisObject as? AbsListView ?: return)` **之前**，`?: return` 的返回语义不变，`pin` 内部逻辑一行未动；是自己维护的标志位（不从微信侧读数）；`@Volatile`（`:71`）+ 单线程写入 ⇒ 跨线程可见性 OK；⚠️ 但它挂在**全局基类 AbsListView**（`:134-140`）⇒ 作用域是全微信所有列表，见 P1-1。
6. **测试是否覆盖判据** —— M1~M5 的五个 `old` 串在我核对的当前源文件里**都恰好出现一次**，`_mut.py` 能打得中；逐个走查：M1（去类名过滤）被 `作者行与正文混排：语料里只剩作者行` / `一个作者行都没有` / `短正文提到候选名字` 抓住；M2（`rows==0→true`）被 `一个作者行都没认出：判不过` + `认不出作者行：0 命中` 抓住；M3（去长度上界）被 `作者行文本超过 16 字：仍被长度上界挡掉`（17 字 > maxLen 16）抓住；M4（空文本→true）被 `作者行文本为空：判不过` / `作者行文本为空：0 命中` 抓住；M5（`contains`）被 `相似但不同的类名：一律不命中`（`AsyncTextView2`/`AsyncTextViewWrapper`/`asyncTextView`…）抓住 ⇒ **5/5 都会变红，没有「断言与实现对偶」的迹象**；但 View 侧采样（`collectVisTexts` 的 20/深度 8/`classChain`）**零覆盖**，而 P0-1/P0-2 正在那里面。
7. **编译期坑** —— 未发现：五个文件的全部新增 KDoc 逐行扫过，**无 `/*` 嵌套序列**（本次 grep `-P '(^\|[^*])/\*(?!\*)'` 在 5 个文件里零命中）；无未用 import（新增的 `Collections` 确在 `:876` 使用；`HashMap` 走 `kotlin.collections.HashMap` 默认别名，**不需要 import**，勿误补）；可见性全 `public`（`VisText`/`AuthorCorpus`/`AUTHOR_VIEW_CLS`/`isAuthorRowClass`/`authorCorpusOf`/`matchName`/`pickByAuthor`），旧 `matchName(String?, List<String>)` 调用点已全部消失（grep 仅剩注释与测试）；`runSnapProbe(wrapped, n, …)` 形参 `ListAdapter` 与 `wrapped`（`ConvFilter.kt:992`）一致；Kotlin 2.0.21 支持尾随逗号（`Keys.kt:443-446`）；`ScrollDock.scrolling()` 从 `ConvFilter` 同包可访问 ⇒ **无编译坑**。

---

## 5. 装包后真机必须验的 3 条判据

> 前两条**先于一切其它操作**（包括不要先去趟 K7 探针）执行 —— 一旦 `_mut.py` 没覆盖的那个 View 侧判据不成立，继续滚动只会烧掉 `takeProbe` 的 12 次探测预算（烧完就永久静默，`docs/_r37_preflight2.md:592/664` 已经在这上面付过学费）。

**① 【灰标】类名/可达性：滚两屏，`grep 'SNS_DELTAG stat'`，只看两个数**
```
adb logcat -d | grep "SNS_DELTAG stat"
```
- ✅ 通过：`noAuthor=0`（或 ≤2 且不再增长），且 `lastRecon=` 为空或 `noname(...)`。
- ⛔ 不通过：`noAuthor` 持续涨 且 `lastRecon=noauthor(pos=p/n cls=[...])`。
  → **立刻停手，不要再滚**。然后二选一定性（这正是 P0-2 要求补 `seen=` 的原因；没有 `seen=` 时按下述替代做法）：
  - 若 `cls=` 里**有** `AsyncTextView`：这在本实现里不该发生（`rows` 必 >0）⇒ 说明这不是 `noauthor` 的正常形态，去查 `SNS_DELTAG bind err`（`SnsDeletedTag.kt:436`）有没有吐异常；
  - 若 `cls=` 里**没有** `AsyncTextView`：**禁止照抄 `cls` 里的类名去改 `AUTHOR_VIEW_CLS`**（那是 P0-2，会把正文变成语料 ⇒ 打错标签）。先用现成能力取一眼子树规模定性：`adb logcat -d | grep 'SNS_DELTAG tree'`（`SnsDeletedTag.kt:1476` 的一次性树 dump，`TREE_DEPTH=4`、最多 40 条路径），数一下 `AsyncTextView` 在树里的**深度与序号**：排在第 20 个可见 TextView 之后 ⇒ 是 **P0-1 的采样截断**（抬 `max` / 给作者行分桶），否则才是类名漂移（改常量）。

**② 【灰标】功能没退化依然成立：`grep 'SNS_DELTAG path'` + `stat` 里的 `resolved/painted/mtHit`**
- ✅ 通过：仍能打印 `SNS_DELTAG path m0 -> @`（单步 `@0`/`@-1`，**同一条不超过 1 次**），`resolved` 持续增长、`painted≥1`、`mtHit=0`。
- ⛔ 不通过：`path` 从 `@` 变成 `E`/`z`/`D`（`@` 分支被 `rows==0` 打掉了），或同一条 `@-1;f:xxx` 重复打印 ⇒ **停手**，按 ① 定性后再决定要不要回滚 `SnsDeletedTag.kt` 这两处。

**③ 【K7 探针】取证方式本身不要把自己弄出 ANR**
- 开法：`python tools/dev.py probe kvset --key debug.k7_snap_probe --val true`（不用重启微信）。
- **先静止**（手指离开屏幕 ≥1s）等一次 `K7 snapprobe n=... full=... seq=... head=... tail=... refl=... alloc=... gc=... scrolling=false`，而不是滚动中取（`scrolling=true` 的那条同样要一条，但别拿它当第一份样本）。
- ⛔ 若出现 `tail` 显著 ≫ `seq`（容器是链表 / O(n²) 候选 D）：**立即把开关设回 `false` 再继续滚**，否则下一次探针就可能 ANR；同时这本身就是 H1/候选 D 的答案，`refl`/`alloc` 不必再取。
- 附带对账：`K7 perf … snaps=` 与同一次 `K7 snapprobe … full=` 的差值 ≈ P1-4 里那次多出来的 SQLite 查库开销；若差值明显不为 0，这一轮的 `snaps=` 与上一轮的 46ms **不可直接比**（口径已被改过）。

---

[DONE]
