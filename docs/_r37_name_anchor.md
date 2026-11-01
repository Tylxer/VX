# R37 — 朋友圈「已删动态」灰标：作者文本结构性锚定（堵短正文误命中）

> 实现 lane（Pro 档）。独占文件：
> - `app/src/main/java/com/lx/wxgroup/feature/SnsDeletedTag.kt`
> - `app/src/main/java/com/lx/wxgroup/feature/SnsDeletedTagLogic.kt`
> - `app/src/test/java/com/lx/wxgroup/feature/SnsDeletedTagLogicTest.kt`

---

## §1 现状分析：洞在哪

### 1.1 对账链路（改之前的形态）

「已删动态」灰标走的是 `RecyclerView.Adapter.bindViewHolder`（全应用公共入口，无 try/catch），
所以「这一格到底装的是哪条动态」不能靠猜下标，必须靠 **View ↔ 数据对账**自证：

```
SnsDeletedTag.reconcileAt(adapter, pos, itemView)          // SnsDeletedTag.kt:804
  ├─ visibleTexts(itemView)                     // :804 先序 DFS，收子树里所有「非空且可见」的 TextView 文本
  ├─ pickWindow(pos, size) { snsIdOf(cand) != null && matchName(nameOf(cand), texts) }   // Logic:198
  └─ 命中数 != 1 ⇒ 返回 null（这一格不打标）
```

校验闸 `SnsDeletedTagLogic.matchName`（Logic:156）是**双向 contains + 长度上界**：

```kotlin
val maxLen = maxOf(n.length + 8, NAME_TEXT_MAX /*16*/)
visibleTexts.any { t -> t.length in 2..maxLen && (t.contains(n) || (t.length >= 3 && n.contains(t))) }
```

### 1.2 洞：长度上界只挡住 >16 字的长正文

`visibleTexts` **不区分角色**：正文、转发来源、点赞文案、作者昵称全在同一个 `List<String>` 里。
长度上界是这个缺陷的唯一补偿，而它的兜底值 `NAME_TEXT_MAX = 16` 恰好把上界抬到 16 ⇒
**真正被挡掉的只有 >16 字的正文，9~16 字的短正文照样进语料并命中**。

实测（本轮之前，测试里已按现状锁住）：

```
matchName("李四", ["今天和李四一起吃饭"])  == true     // 10 字
matchName("张三", ["转发 张三 的动态"])    == true     // 9 字
```

### 1.3 失效形态（本功能**最危险**的一条）

1. 这一格的正确候选因**备注名**恒不命中 —— 界面显示「李总」，`ImproveInfo.name` 仍是「李四」，
   双向 contains 都救不了 ⇒ 正确候选永远出局；
2. 而窗口内的**邻居**候选（比如王贝拉那条动态的数据）名字恰好出现在这格 itemView 的
   **正文**里，且正文只有 10 字 ⇒ 邻居成为窗口内**唯一命中**；
3. ⇒ `reconcileAt` 返回邻居的下标 ⇒ 这一格绑上**邻居的 snsId** ⇒
   邻居有被删动态时，**这条动态被打上错误标签**。

用户看得见（错标记），比「打不上标记」糟得多 ⇒ 优先级高于漏标。

### 1.4 为什么「把 16 降到 8」不是正解

- 昵称本身可以很长（「来自深圳的陈健峰」8 字、数据侧 `name` 上限 32 字），降到 8 会把
  **正常昵称**也挡掉 ⇒ 从「打错」变成「大面积打不上」（另一种静默失效）；
- 而且它仍是**长度猜测**：正文完全可以只有 8 字（「李四真帅」）⇒ 洞还在，只是变小。

⇒ 正解是**换判据维度**：不用「文本有多长」猜它是昵称还是正文，用**控件的类名**直接认。

### 1.5 真机取证（`docs/派发审计台账.md:1460`）

depth=9 的完整 View 树 dump 里，item 子树中 TextView 的角色是**按类名分开**的：

| 角色 | 类名 | 备注 |
|---|---|---|
| 作者昵称 | `AsyncTextView` | dump 里 `AsyncTextView("王新功")` |
| 正文 / 时间行 | `ImproveTextView` | dump 里 `ImproveTextView("")` ×3（空）+ 正文 |

⇒ 用类名把「作者文本」从「子树所有可见文本」里分离出来，比对账语料只留作者文本，
比长度猜测强一个维度。

---

## §2 方案

### 2.1 选定方案：类名限定 + 纯函数下沉

在 `SnsDeletedTagLogic`（纯 JVM、可单测）里新增三个纯函数，View 侧只做**哑采集**：

| 新增 | 作用 |
|---|---|
| `data class VisText(text, classNames)` | 一个可见 TextView 的采样：文本 + **类及其父类链的简单类名** |
| `isAuthorRowClass(classNames): Boolean` | 类名闸门：链里任一层简单类名 == `AsyncTextView` |
| `authorCorpusOf(items): AuthorCorpus` | 从采样列表里挑出**作者行**文本，产出 `AuthorCorpus(texts, rows, classes)` |
| `matchName(candName, corpus)` | 对账闸门改成吃 `AuthorCorpus`（不再是「子树全文」） |
| `pickByAuthor(pos, size, corpus, nameAt, idAt)` | `reconcileAt` 的纯判据部分（窗口挑选 + 对账） |

View 侧：`visibleTexts`（返回 `List<String>`）→ `collectVisTexts`（返回 `List<VisText>`），
遍历时**不再判断角色**，只做「可见 + 类名链采样」，角色判定全在 Logic 里 ⇒ 可纯 JVM 测。

`AuthorCorpus` 三个字段的分工（这是「不许静默全废」那条的落点）：

- `rows` —— 类名命中作者行的 TextView **个数**（含文本为空的）。`0` ⇒ 一个作者行都没认出。
- `texts` —— 这些作者行里的**非空**文本。空 ⇒ 认出了作者行但还没渲染出字。
- `classes` —— 本次见到的所有 TextView 简单类名（去重），**仅诊断用**，进 `lastRecon`。

于是两种「打不上」的原因被彻底分开：

| 现象 | `rows` | `texts` | 含义 | 处置 |
|---|---|---|---|---|
| `lastRecon=noauthor(cls=[...])` | 0 | — | **类名漂了**（或被 R8 改名） | 改类名常量；`noAuthor` 计数会涨 |
| `lastRecon=noname(...)` | >0 | 空/对不上 | 渲染还没完成或备注名不一致 | 延后一拍重试 / 认栽 |

### 2.2 被否决的备选

| 备选 | 否决理由 |
|---|---|
| **把 `NAME_TEXT_MAX` 从 16 降到 8~10** | 仍是长度猜测；且会把「来自深圳的陈健峰」这类正常长昵称一并挡掉 ⇒ 打错变成大面积打不上 |
| **按资源 id 定位昵称行** | 时间行的 id 在本项目里是靠「命中一次后记住」拿到的；昵称行没有等价的 getter 可依（`getPublishTimeText()` 只对时间行存在），只能硬编 id ⇒ 比类名更容易随版本漂且完全不可诊断 |
| **认不出作者行就退回「子树全文 + 长度上界」** | ⛔ 任务书明令禁止：等于把洞原样留着（9~16 字短正文照样命中）。降级必须是**不打标** |
| **认不出作者行就当通过** | ⛔ 同左，而且更糟：窗口内所有候选都会通过 ⇒ `hits≥2` ⇒ 全是歧义，或者刚好只剩一个候选时直接错绑 |
| **给昵称行加 `isAuthorRowClass` 之外的第二判据（如「文本不含标点」）** | 又回到内容猜测；且短正文同样可以不含标点 ⇒ 收益为零、复杂度翻倍 |
| **保留 `matchName(String?, List<String>)` 旧签名另开一个新函数** | 旧签名就是「全文语料」的入口，留着必然会被以后的调用方误用 ⇒ 直接改签名，让类型本身说明语料已经过角色限定 |

### 2.3 为什么类名链要比对「整条父类链」而不是只看自己的类名

微信会用 `AsyncTextView` 的**子类**承载不同位置的文本（混淆后子类名可能变成 `x.y.z`）。
只比自己的简单类名 ⇒ 子类一出现就全废；比整条链 ⇒ 只要祖先还在就继续生效。
代价是遍历时要多算一次父类链 ⇒ 按**完整类名**缓存（跟 `timeMethodCache` 同款手法）。


## §3 硬约束逐条落点

改动量（落地后的实际值）：`SnsDeletedTagLogic.kt` +157 −18（`VisSample`/`AuthorCorpus.seen` 等 4 个纯/Kotlin 结构 + `matchName` 换签名 + `authorCorpusOf` 带 `seen`）；`SnsDeletedTag.kt` +139 −18（`visibleTexts` → `collectVisTexts`（上限 48 + `seen` 计数）、`classChain` + 缓存、`missedNoAuthor` 计数与 stat 字段、`VIS_TEXT_MAX`）；`Keys.kt` +23、`ConvFilter.kt` +150、`ScrollDock.kt` +33；`SnsDeletedTagLogicTest.kt` +421 −87（938 条全量中的 128 条属于本链路）。

### 3.1 「作者文本」的结构性识别（主交付）

| 位置 | 内容 |
|---|---|
| `SnsDeletedTagLogic.kt:164` | `const val AUTHOR_VIEW_CLS = "AsyncTextView"` —— 真机 dump 取证的类名 |
| `SnsDeletedTagLogic.kt:219` | `isAuthorRowClass(classNames)` —— 类名闸门，比整条父类链，**严格相等** |
| `SnsDeletedTagLogic.kt:245` | `authorCorpusOf(items, seen)` —— 从采样里挑作者行，产出 `AuthorCorpus(texts, rows, classes, seen)` |
| `SnsDeletedTagLogic.kt:280` | `matchName(candName, corpus)` —— 入参从「子树全文」换成**作者行语料** |
| `SnsDeletedTagLogic.kt:304` | `pickByAuthor(...)` —— `reconcileAt` 的纯判据部分（新增，为了可测） |
| `SnsDeletedTag.kt:871` | `collectVisTexts` —— 哑采集（可见 TextView + 类名链），判定全在 Logic |
| `SnsDeletedTag.kt:913` | `classChain` + `classChainCache` —— 按完整类名缓存父类链 |

⇒ 短正文不再进语料的**机制**：正文是 `ImproveTextView`，`isAuthorRowClass` 判 false
⇒ `authorCorpusOf` 直接 `continue` ⇒ 它既不进 `texts` 也不进 `rows`
⇒ `matchName` 根本看不到它，与长度无关（9 字还是 200 字都一样）。

### 3.2 ⛔ 降级不许退回「无校验」

- `SnsDeletedTagLogic.kt:284` —— `if (corpus.rows == 0) return false`：
  **认不出作者行 ⇒ 判不过**（不是跳过校验、不是当通过、不是退回全文）。
- `SnsDeletedTagLogic.kt:245` `authorCorpusOf` —— `rows == 0` 时产出的 `texts` **恒为空**，
  不存在「认不出就把所有文本都当作者文本」的分支（代码里压根没有这条路径）。
- `SnsDeletedTag.kt:813` —— `reconcileAt` 在 `rows == 0` 时直接 `return null`
  ⇒ 这一格不打标（保守方向：漏标好过打错标）。

变异验证：把 :284 改成 `return true` ⇒ 2 条用例变红（见 §4 M2）。

### 3.3 ⛔ 但不许静默全废 —— 可诊断性

| 落点 | 内容 |
|---|---|
| `SnsDeletedTag.kt:151` | `@Volatile private var missedNoAuthor` + KDoc 写明**定性顺序是先 `seen` 后 `cls`** |
| `SnsDeletedTag.kt:814` | 只在 `rows == 0` 时自增（**不**在「作者行存在但文本为空」时增 ⇒ 两类原因不混） |
| `SnsDeletedTag.kt:815` | `lastRecon = "noauthor(pos=… seen=N cap=C cls=[AsyncTextView, ImproveTextView])"` —— **类名集合 + 本次遇到的可见 TextView 总数**一起打出来 |
| `SnsDeletedTag.kt:817` | `logKeyOnce("noauthor:${corpus.classes}", …, warn = true)` —— logcat 也能看到，按类名集合分桶 |
| `SnsDeletedTag.kt:1728` | `stat()` 新增 `noAuthor=$missedNoAuthor` |

#### ⛔⛔ `rows == 0` 有两种成因，`cls=` 只在其中一种下才有证据力（R37-2 复核 P0-2）

「一个作者行都没认出」不等于「类名漂了」—— 装包前交叉复核（`docs/_r37_review2.md` P0-2）
发现了第二种成因，而且两种成因的处置**方向相反**：

| 成因 | 日志特征 | 作者行走到了吗 | 正确处置 |
|---|---|---|---|
| **采样被截断** | `seen >= cap` | ❌ 没有（排在 View 侧上限之后，压根没被遍历到） | 抬 `VIS_TEXT_MAX`（现 48） |
| **类名漂移** | `seen < cap` 且 `cls=` 里没有作者类名 | ✅ 走到了，只是认不出 | 照抄 `cls=` 进 `AUTHOR_VIEW_CLS` |

只看 `cls=` 就把两种成因当成同一种的代价是**本轮唯一一条「打错标签」路径**：截断时 `cls=`
里是「先遇到的一批」类名，其中最大概率是正文/时间行的 `ImproveTextView` ⇒ 照抄进去 ⇒
`AUTHOR_VIEW_CLS` 变成正文控件 ⇒ 正文（含 9~16 字短正文）重新成为对账语料 ⇒
邻居候选成为唯一命中 ⇒ **把邻居的删除状态画到这条动态上**。所以 `seen` 不是可选的润色，
它是「这份 `cls=` 能不能信」的前置条件。

⇒ 真机分辨方法（顺序**不能**倒）：
1. `noAuthor=0` 而 `painted=0` ⇒ 判定链路没问题，去看清单 / 时间控件（`missedTime`）；
2. `noAuthor` 持续涨 ⇒ 先看 `seen` 与 `cap`：`seen >= cap` ⇒ 抬 `VIS_TEXT_MAX`，
   ⛔ **不许**在这时改 `AUTHOR_VIEW_CLS`；
3. 只有 `seen < cap` 且 `cls=` 里没有 `AsyncTextView` ⇒ 才是类名漂了，此时才把 `cls=`
   里 dump 出来的类名抄进 `AUTHOR_VIEW_CLS`。

### 3.4 ⛔ 空文本仍判 false（现有语义未动）

- `SnsDeletedTagLogic.kt:286` —— `if (corpus.texts.isEmpty()) return false`。
- 为了让它能区分于「没有作者行」，`collectVisTexts`（`SnsDeletedTag.kt:871`）**连空文本的
  TextView 也采样**，于是 `rows>0 且 texts 为空` 唯一地表示「作者行在、还没渲染出字」
  ⇒ 由 `retryResolve` 延后一拍重试（该机制本来就存在，未改动）。
- 现状语义与 R37-1 一致：空文本 = 判不过，**不是**跳过校验。

变异验证：把 :286 改成 `return true` ⇒ 2 条断言变红（见 §4 M4）。

### 3.5 ⛔ 不许放宽 `pickWindow` 的「唯一命中」/`snsIdOf` 边界断言

- `pickWindow`（`SnsDeletedTagLogic.kt:337`）**一行未改**；新增的 `pickByAuthor` 是它的
  调用方，语义完全继承（`n == 1` 才返回下标，否则 `-1`）。
- `snsIdOf`（`SnsDeletedTagLogic.kt:102`）/ `ID_RE`（:54）**一行未改**。
- 单测侧：`pickWindow` 原有 11 条用例全保留（含「两个命中 ⇒ -1」「pos 越界跳过」），
  另加 `pickByAuthor` 的「两个候选都命中 ⇒ -1」复验。

### 3.6 ⛔ Kotlin 坑：KDoc 里不许出现 `/*`

- 本轮所有新增 KDoc 均未出现 `/*` 序列；类名一律写**裸简单类名**（`AsyncTextView`）或
  「com.tencent.mm.ui.widget 包下的 AsyncTextView」这种中文表述，不拼可能连出 `/*` 的路径。
- `assembleDebug` 已过（KDoc 里若真有 `/*` 会直接编译失败，本仓库踩过一次）。

---

全量：`./gradlew.bat cleanTestDebugUnitTest testDebugUnitTest --rerun-tasks`
⇒ **938 条 / 0 失败**（基线 934 + 本轮新增 4 条 `seen` 相关）。其中
`SnsDeletedTagLogicTest` 128 条 / 0 失败。

> ⚠️ **环境坑（本轮实测，会浪费你 4 分钟）**：本会话 `PATH` 里混进了带空格和**残缺引号**的
> 条目（形如 `"G;F:\Program Files\WorkBuddy\resources\vendor\PortableGit\Program Files\...`）。
> AGP 会把整套 `PATH` 拼进测试 worker 的 `-Djava.library.path=…`，残缺引号把 worker 的 JVM
> 命令行切断 ⇒ `找不到或无法加载主类 Files\WorkBuddy\…`（**不是测试失败，是 JVM 没起来**，
> 日志里表现为 `Process 'Gradle Test Executor N' finished with non-zero exit value 1`）。
> 修法：跑之前把 PATH 压干净再跑（`--stop` 一次让 daemon 也带上干净环境）：
> ```
> $env:PATH='C:\Windows\system32;C:\Windows;C:\Windows\System32\Wbem'
> $env:JAVA_HOME='G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot'
> .\gradlew.bat cleanTestDebugUnitTest testDebugUnitTest --rerun-tasks
> ```
> 另外 `--rerun-tasks` **必须带**：`gradle.properties` 里 `org.gradle.caching=true`，
> 不带会被判 FROM-CACHE（看着绿，其实一次没跑）。

### 4.1 本轮用例分布

| 组 | 条数 | 覆盖的硬约束 |
|---|---|---|
| `isAuthorRowClass` | 4 | 类名链 / 严格相等 / 空链 / `View`·`TextView`·`AppCompatTextView` 不污染 |
| `authorCorpusOf` | 6 | 只留作者行 / 空文本仍算一行 / 降级不退回全文 / 多作者行 / 空输入 / `classes` 去重 |
| **`seen` 组（复核后新增）** | 4 | `seen` 由 View 侧带出不丢、与 `rows` 互不干扰、截断形态、漂移形态（见下表） |
| `matchName` | ~12 | 三道闸门（类名 / 空文本 / 长度上界）+ 双向包含 + 3 字反向闸门 |
| `pickByAuthor` | 8 | 窗口 + 唯一命中 + `idAt`；**含本轮要堵的那条「邻居靠正文抢走这一格」** 及它的反证 |

`seen` 组逐条（`rows == 0` 的两种成因就是靠这组锁死的）：

| 用例 | 断言 |
|---|---|
| `seen 由 View 侧带出：不传时等于采样条数，传了就用真值` | 默认 = `items.size`（没截断）；传 137 ⇒ `seen == 137`，且 `texts`/`rows` 不受影响 |
| `seen 与 rows 互不干扰` | `rows=1` 时 `seen` 照样是 48 ⇒ 两个字段各记各的 |
| `采样被截断：rows 为 0 且 seen 达到上限` | `rows=0`、`seen>=48`、且 `classes` 里**没有**作者类名 ⇒ `cls=` 不可用 |
| `类名漂移：rows 为 0 且 seen 远小于上限` | `rows=0` 但 `seen < 48` ⇒ 这才是能照抄 `cls=` 的形态 |

### 4.2 变异验证（`_mut.py`，6 组全红）

`_mut.py` 已同步修好三处（原来在这台机器上跑不起来）：不再整份覆盖 `PATH`/省略
`COMSPEC`、`gradlew.bat` 去掉 `./`、`shell=False` 且**带 `--rerun-tasks`**（否则同样被 build
cache 骗绿）；`try/finally` 还原 + 结尾 `sha256` 自证源码还原（本轮已验证：`943d43c7da6f`）。

| # | 变异 | 变红 | 红了哪几条 |
|---|---|---|---|
| M1 | 去掉类名限定（`authorCorpusOf` 不过滤作者行） | ✅ 5 | 混排 / 一个作者行都没有 / **短正文提到候选名字** / 转发来源 / seen 带出 |
| M2 | 降级改「认不出就通过」（`rows==0 → true`） | ✅ 4 | 认不出判不过 / 认不出 0 命中 / 超长正文 / 转发来源 |
| M3 | 去掉长度上界 | ✅ 1 | 作者行文本超过 16 字仍被挡掉（唯一守这条的用例，别删） |
| M4 | 空文本改通过 | ✅ 2 | 空文本判不过 / 空文本 0 命中 |
| M5 | 类名闸门放宽成 `contains` | ✅ 1 | 相似但不同的类名一律不命中 |
| **M6（新增）** | `seen` 退回 `items.size`（不看 View 侧真值） | ✅ 2 | seen 带出 / seen 与 rows 互不干扰 |

⇒ 6/6 全红。`rows==0 ⇒ 不打标`、`pickWindow` 唯一命中、`snsIdOf` 边界三条硬约束都有对口
用例，不存在「断言照着实现抄」的同构现象。

### 4.3 覆盖范围之外的部分（诚实口径）

View 侧 `collectVisTexts`（采样上限 / 深度 8 / `classChain`）**仍然没有单测** —— JVM 里没有
Robolectric，`View`/`Class` 链拿不到真实 Android 运行时。这就是 §6 第 1 条遗留风险的由来，
也解释了为什么本轮两条 P0 都出自这段：**零覆盖的那层，正是复核唯一没能静态判定的地方。**

---

## §5 真机验收判据

> 前两条**先于一切其它操作**执行（包括不要先去趟 K7 探针）。理由：`rows == 0` 一旦成立，
> `probePath` 会把 `@` 分支 `continue` 掉、继续往下试 `z/E/D`，而每次失败都要消耗一次
> `takeProbe` 预算（12 次），烧光之后连日志都静了 —— 那时再想取证就得冷启重来。

**① 作者行认没认出来（`adb logcat -d | grep "SNS_DELTAG stat"`）**

- ✅ `noAuthor=0`（或 ≤2 且不再增长），`lastRecon=` 为空或 `noname(...)`。
- ⛔ `noAuthor` 持续涨 且 `lastRecon=noauthor(pos=p/n seen=N cap=C cls=[...])`：
  **立刻停手，不要再滚**，然后按下表定性 ——

| `seen` vs `cap` | 定性 | 动作 |
|---|---|---|
| `seen >= cap`（如 `seen=48 cap=48`） | **采样被截断**：作者行排在采样窗口之后 | 抬 `SnsDeletedTag.VIS_TEXT_MAX`；⛔ **不许**改 `AUTHOR_VIEW_CLS` |
| `seen < cap` **且 `cls` 只含基类**（`TextView` / `View`）| **这个条目本来就没有作者行**（封面/特殊卡片/提示条）—— R37-6 真机实测：`noauthor(pos=0/20 seen=4 cap=48 cls=[TextView])` | ⛔ **不许**动 `AUTHOR_VIEW_CLS`：抄基类会让**全应用所有 TextView** 都成为作者行 ⇒ 打错标签。不打标就是正确行为 |
| `seen < cap` 且 `cls=` 没出现 `AsyncTextView`，**且不是只含基类** | **类名漂了** | 把 `cls=` 里的类名抄进 `AUTHOR_VIEW_CLS` |
| `cls=` 里**有** `AsyncTextView` | 不该发生（`rows` 必 >0） | 去查 `SNS_DELTAG bind err`（`SnsDeletedTag.kt:441`）有没有吐异常 |

  补充取证：`adb logcat -d | grep "SNS_DELTAG tree"`（`SnsDeletedTag.kt:1520` 的一次性
  树 dump，`depth=4`、最多 40 条路径）—— 数 `AsyncTextView` 在树里的深度和它是第几个可见
  TextView，用来定 `VIS_TEXT_MAX` 该抬到多少。

**② 功能没退化（`adb logcat -d | grep "SNS_DELTAG path"` + `stat`）**

- ✅ 仍能打印 `SNS_DELTAG path m0 -> @`（单步 `@0`/`@-1`，**同一条不超过 1 次**），
  `resolved` 持续增长、`painted≥1`、`mtHit=0`。
- ⛔ `path` 从 `@` 变成 `E`/`z`/`D`（`@` 分支被 `rows == 0` 打掉），或同一条 `@-1;f:xxx`
  重复打印 ⇒ **停手**，回到 ① 定性后再决定要不要回滚这一处。

**③ 打标没打错（这条必须在界面上数，不能只看计数器）**

- 挑一条**正文里提到别人名字**的动态：本轮的期望行为是 **看不到任何多余标记**；标记只能出现在**已被物理删除**的那条动态的时间行后面。
- 出现「标记画在邻居那条动态上」⇒ 立即回滚到改动前的 `authorCorpusOf`（那时是全文语料 + 长度上界），并按 §3.3 的表重判 `AUTHOR_VIEW_CLS` 是不是被抄错了类名。

---

## §6 遗留风险

| # | 风险 | 现状 | 触发条件 / 判据 |
|---|---|---|---|
| R1 | **View 侧零覆盖**：`collectVisTexts` 的采样上限（48）/ 深度 8 / `classChain` 没有 JVM 单测，本轮两条 P0 都出在这层 | 未解决（JVM 里无 Robolectric，结构上不好测） | 真机见 §5-①：一出现 `noauthor(...)` 就说明这段还没被管住；建议后续把「遍历 + 上限」抽成吃 `(View)->List<View>` 的纯函数下沉到 Logic |
| R2 | `VIS_TEXT_MAX = 48` 是**按 R35 那份 dump（深度 ≤4 就 37 条叶子路径）估的**，没有本轮的实时证据 | 接受（它是「往上抬」的方向，抬多了只是多走几个节点；抬少了才是灾难） | 真机 `seen=48 cap=48` 就是它在报警 ⇒ 继续抬，并把新值写回这里 |
| R3 | `AsyncTextView` 若在 item 里还承载**别的角色**（点赞/评论昵称最可能），那些文本仍会进 `texts` | 无证据表明有 | 真机盯 `lastRecon=noname(... authors=N)`：正常富条目 `authors` 应很小（1~2）；持续偏大 ⇒ 加「只取首个 AsyncTextView」或位置约束 |
| R4 | `reconcileAt` 失败时 `@` 路径被 `continue` 掉 ⇒ 静默退回 holder 路径（`z/E/D`），后者**不做位置对账** | 既有行为（holder 自带数据，按 design 是与 View 一一对应的） | `SNS_DELTAG path` 打出的是哪条就走哪条，日志里直接可见；若长期只剩 `E`/`z`/`D`，要回头评估这个兜底的取向 |
| R5 | 上一轮 `painted=1` 的成功样本是 **R35 时期**的日志（`docs/_r35_sns_tag.md` 系列），本版现在是否仍走 `@` 路径**没有重验过** | 待 §5-② 回答 | §5-② 的 `path` 日志 |

---

[DONE]
