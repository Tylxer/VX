# R37 · K7 `readSnaps` 46ms 定位与「两段式」再评估（离线分析 · 只出方案不改码）

- 分析人：general-purpose-2（离线 lane，Pro 档）
- 时间：2026-10-31
- 输入事实：`docs/未完成项与计划.md` §2.5 + 2026-10-31 02:26 真机日志（设备 467cf5bc）
- 产出边界：**只分析 + 方案 + 单测清单**，本轮不动 `app/src/main` 任何源码、不 adb、不 commit
- 结论一句话：**§2.5 写的「count + 首末 wxid 两段式」在语义和量化上都不成立（最多省 50ms 里的 0.3~1ms，却会把 light 路径的分组行摘要一起冻住）；正解是「错峰（滚动降级 + 静止补做）+ 每项降本」，采样指纹只作兜底而非主判据。**

---

## 0. 事实与读法

真机三条（主会话，`com.tencent.mm/.ui.LauncherUI`，上下滚动 4 轮）：

```
02:25:38.499 K7 perf n=3006 snaps=46ms total=50ms path=light
02:25:54.408 K7 perf n=3006 snaps=52ms total=56ms path=light
02:25:58.176 K7 perf n=3006 snaps=62ms total=65ms path=light
02:25:38.500 K7 debounce count=3006 snaps=3006 layoutBefore=3007 layoutAfter=3007 changed=true
```

读法（`ConvFilter.kt:1093-1095` / `:1203-1207`）：

1. `snapsCost ≈ total`（46/50、52/56、62/65）⇒ **开销几乎全在 `readSnaps`**，后面的 `orderChanged` + `timeFingerprint` + `rebuildModels` 只占 4~6ms。
2. `path=light`（`:1122-1126`）⇒ 这次 rebuild **顺序没变**（`orderDirty == false`），是「收消息/时间变化」型。也就是说：**46ms 不是"为了检测重排"付的，是为了 `timeFingerprint` 和 `rebuildModels`（分组行摘要/未读）付的。**
3. 单价：`46ms / 3006 = 15.3µs/项`。
4. ⛔ **假设（时序推断，未证实）**：「这 46ms 打在滚动帧里 ⇒ 滚动掉帧」。三条日志只证明 rebuild 发生过，**没有**滚动帧时间戳、`trackMotionScroll` 时间戳或 gfxinfo jank 与之对齐。要坐实，见 §1.4 探针 P0。
5. 三条耗时 46→52→62 **没有下降趋势**（20s 内）⇒ 弱化「模块 dex 解释执行、JIT 热起来就好」的假设（H2c，见下）。

---

## 1. 瓶颈定位：46ms 花在哪

### 1.1 代码事实（确定的，非假设）

每次 rebuild 的必走段（`ConvFilter.kt:1059` → `:1098` → `:1129`）：

| 位置 | 做什么 | 是否 O(n) |
|---|---|---|
| `:1059` | `wrapped.count` | O(1) |
| `:1070-1071` | `db.listGroups()`（1 次 SQL） | O(G) |
| `:1079-1087` | `PinFoldLogic.configStamp`（拼串） | O(G) |
| **`:1093-1095`** | **`readSnaps(n)` ← 46ms** | **O(n)** |
| `:1098` | `ConvLayout.orderChanged`（`ConvLayout.kt:202-211`） | O(n) |
| `:1112` / `:1131` | `timeFingerprint`（`:1241-1245`，纯 Long 累加） | O(n) |
| `:1160-1165` | `db.memberWxids(g.id)` × G（full 路径才有） | O(G) SQL |
| `:1210-1221` → `:1449-1460` | `rebuildModels` → `buildModel`：**每个分组遍历全部 3006 条 snaps**，命中成员时再 `wrapped.getItem(i)` + `ConvReader.unread` 反射 | O(G×n) |

`readSnaps` 的单项成本（`ConvFilter.kt:1397-1410` + `ConvReader.kt:42-58`）：

```
per item =
  1 × runCatching { wrapped.getItem(i) }.getOrNull()      // 微信 dex 里的 p3.getItem
+ 1 × ConvReader.username(item)
        = ConcurrentHashMap<Class,Field>.get               // ConvReader.kt:44,:109-110
        + runCatching { f.get(conv) as? String }.getOrNull()
+ 1 × ConvReader.timeMs(item)
        = 又一次 CHM.get + runCatching { f.get(conv) }      // Long 装箱
+ 1 × new ConvLayout.ConvEntry(wxid, ms)                   // 分配
```

即每项 **3 次 `runCatching` + 2 次 CHM 查表 + 2 次 `Field.get` + 1 次 Long 装箱 + 1 次对象分配 + 1 次 ArrayList.add**。

### 1.2 数量级核算（这一步决定是否值得优化）

编译执行下的粗估（ART，`Field` 已缓存、`isAccessible=true`）：`Field.get` ≈ 50~200ns，CHM.get ≈ 20ns，小对象分配 ≈ 30~50ns，微信 `getItem` 若是纯数组索引 ≈ 30~80ns。
⇒ **理论单项目成本 ≈ 0.6~1.2µs ⇒ 全表 ≈ 2~4ms。**
实测 **15.3µs/项 = 理论的 13~25 倍**。这 10× 以上的缺口就是「46ms」的来源，只剩三个候选。

### 1.3 三个候选假设（⛔ 全部是假设，下面的探针才能裁决）

- **H1（微信侧）**：`wrapped.getItem(i)`（微信 dex 里的 `com.tencent.mm.ui.conversation.p3.getItem`）不只是一句数组索引 —— 可能是懒 hydrate（首次取用时从 Cursor/DB 补齐字段）、分页加载检查、或链表式容器（`LinkedList.get(i)` 的 O(n) 随机访问 ⇒ 整体 O(n²)）。若成立，**单次 10µs+，我们这边怎么优化都没用**。
- **H2（我们侧）**：每项 3 次 `runCatching` 在 JVM 上把 inline class `Result` **装箱**（`runCatching<R>` 的 R 是类型参数，`Result<R>` 走装箱表示），叠加 Long 装箱、CHM 双查、`ConvEntry` 分配 ⇒ 每项 ~15k 次分配。再叠加**模块 dex 可能未 JIT 编译（解释执行）**，解释执行下每次调用开销放大 10~30×，可解释这个缺口。
- **H3（GC 放大）**：15k 次分配触发年轻代 GC，GC 停顿被计入这 46ms（微信本身内存压力就大）。
  - 反向证据：3 次耗时 46→52→62 是**上升**不是下降 ⇒ 与「JIT 冷启动（H2c）」不符；但**不足以排除** H2 的分配/装箱部分。

### 1.4 裁决探针（一条日志命令，一次装包搞定）

在 `readSnaps` 里加一次性分段计时（**受 `Kv` 调试开关控制，默认关，跑完就摘**），把四项分别量出来：

```kotlin
// 伪代码：ConvFilter.kt readSnaps 内，Kv.getBool(Keys.K7_SNAP_PROBE) 为 true 时执行一次
val n = wrapped.count
val t0 = uptime()
for (i in 0 until n) wrapped.getItem(i)                       // P1 只调 getItem，丢弃结果
val t1 = uptime()
val probe = runCatching { wrapped.getItem(0) }.getOrNull()
for (i in 0 until n) ConvReader.username(probe)               // P2 同一对象，cache 全热，纯反射+装箱
val t2 = uptime()
val tmp = ArrayList<ConvLayout.ConvEntry>(n)
for (i in 0 until n) tmp.add(ConvLayout.ConvEntry("wxid_$i", i.toLong()))  // P3 纯分配
val t3 = uptime()
val full = readSnaps(n)                                       // P4 完整版
val t4 = uptime()
val gc0 = Debug.getRuntimeStat("art.gc.gc-count")             // H3 判据
LogX.i("K7 snapprobe n=$n getItem=${t1-t0} refl=${t2-t1} alloc=${t3-t2} full=${t4-t3}" +
       " gcBefore=$gc0 gcAfter=${Debug.getRuntimeStat("art.gc.gc-count")}" +
       " gcTime=${Debug.getRuntimeStat("art.gc.gc-time")}")
```

判读表：

| 观测 | 结论 | 应对 |
|---|---|---|
| `getItem ≥ 0.7 × full` | **H1 成立**（微信侧） | 只能错峰（§2 L2/L3），L1 收益有限 |
| `refl` 单项 ≈ 10µs、`alloc` 小、`getItem` 小 | **H2 成立**（反射/装箱被放大） | L1 会有 3~5× 收益 |
| 四项之和 ≪ `full`，且 `gc-count` 有跳变 | **H3 成立**（GC） | L1 去分配即见效 |
| `refl` 单项 ≈ 0.1µs、`getItem` ≈ 15µs | H1（微信侧纯慢） | 错峰；并尝试「拿底层容器」候选（§2.4 备选 D） |

⛔ 补充：H1 下还可加一条**廉价的二次探针**判定是不是 `LinkedList`（O(n²)）：分别测 `getItem(0)` ×3006 与 `getItem(n-1)` ×3006、与顺序遍历 ×3006 的耗时。若「尾部/随机访问」明显慢于「顺序遍历」⇒ 容器是链表 ⇒ 换成**一次性反射拿到底层 `List` 再迭代**（可省掉 n 次虚拟调用 + 可能的 O(n²)）。这是候选 D，见 §2.4。

### 1.5 顺带确认：现有日志里能复用的两个数

- `K7 debounce` 行的 `count=3006` = `wrapped.count`（微信真实条数）⇒ **可复用**，O(1)，是唯一能免费拿到的微信侧信号。
- `layoutBefore/layoutAfter`（`:1284-1295`）：**不能当顺序判据**。它们是我们自己 `layout` 的行数；「条数不变的中段重排」恰恰是行数不变、顺序变了，两个数必然相等 ⇒ 复用它等于 100% 复现 K7 最初要修的 bug。❌ 否决。

---

## 2. 方案

### 2.0 先否掉 mandate：为什么「count + 首末 两段式」不成立

两条独立理由，任一条都足够：

1. **量化不成立**：`snaps=46 / total=50` ⇒ 两段式最多省掉的是「不读 snaps」以外的东西 —— 而 `orderChanged`（`ConvLayout.kt:202-211`）只是 3006 次 `String` 比较 + `isBlank()`（≈ 0.3~1ms，见 §1.1 表）。**收益 ≈ 0.6~2%，而它引入的漏检是 100% 的功能回归。**
2. **语义不成立**：若两段式 = 「指纹相同就不 `readSnaps`」，则 `fresh` 不存在 ⇒
   - `timeFingerprint(fresh)`（`:1112`）算不出来 ⇒ 「完全没变」与「仅摘要变」分不开 ⇒ 只能无条件 return false ⇒ **收新消息时分组行摘要/未读角标永远不更新**（等价于把 `summariesVersion` 那条修复整体作废）；
   - `rebuildModels`（`:1123`）吃的是 `snaps`，`buildModel`（`:1449-1460`）要遍历全部 3006 条找成员会话 ⇒ 同样冻住。
   
   **⇒ 46ms 不是「顺序检测」的成本，是「分组行摘要刷新」的成本。不读 snaps 就等于把分组行的时间/摘要/未读一起冻住 —— 这正是 R1 修的那类「永冻」，只是冻的是另一半。**

> 结论：**不做「不读 snaps 的两段式」。** 要做的是「**一样读，但别读在这一帧**」（§2 L2）+「**读得更快**」（§2 L1）。

### 2.1 推荐方案总览（三层，按序落地）

| 层 | 动作 | 假设无关？ | 预期收益 | 风险 |
|---|---|---|---|---|
| **L1** | `readSnaps` 每项降本（去 `runCatching` 装箱、`Field.getLong` 去 Long 装箱、类缓存从 CHM 换成 2 槽快路径） | H2/H3 下有效，H1 下 ≈0 | H2 下 46ms → 10~15ms | 极低（行为等价） |
| **L2** | **错峰**：滚动中降级（只做 O(k) 采样指纹），滚动停止后**必做一次全量** | **任何假设下都有效** | 滚动帧内 46ms → ≤0.3ms | 分组行摘要滞后 ≤ 滚动时长 |
| **L3** | **采样指纹 + 强制巡检**（可选，仅在 H1 成立且 L2 不够时才要） | 任何 | 进一步降低全量频次 | 引入漏检窗口（有界，见 §3） |

**L2 是主菜**：它把 46ms 从「用户正在滚的那一帧」挪到「用户停下来的那一帧」。用户在滚的时候多掉 3 帧 = 可见卡顿；用户停住时多掉 3 帧 = 几乎无感（而且此时 notify 本来就会重绘整屏）。

### 2.2 L1：`readSnaps` 每项降本（diff 级）

目标：每项从「3×runCatching + 2×CHM + 2×Field.get + Long 装箱」降到「1 次 getItem + 2 次 Field 访问 + 1 次分配」。

```kotlin
// ConvFilter.kt，GroupedAdapter 内新增（不动 ConvLayout / ConvReader 的对外语义）

/** 类 → 字段的 2 槽快路径缓存（rebuild 只在主线程跑；影子后台读另加锁，见 §2.5） */
private var fUserCls: Class<*>? = null
private var fUser: java.lang.reflect.Field? = null
private var fTimeCls: Class<*>? = null
private var fTime: java.lang.reflect.Field? = null

private fun readSnapsFast(n: Int): List<ConvLayout.ConvEntry> {
    val out = ArrayList<ConvLayout.ConvEntry>(n)
    var i = 0
    try {
        while (i < n) {
            val item = wrapped.getItem(i)            // ① 不再包 runCatching
            if (item == null) {
                out.add(ConvLayout.ConvEntry("", unreadable = true))
                i++
                continue
            }
            val cls = item.javaClass
            var fu = fUser
            if (fu == null || fUserCls !== cls) {    // ② 引用比较代替 CHM 哈希查找
                fu = ConvReader.fieldOf(cls, "field_username"); fUser = fu; fUserCls = cls
            }
            var ft = fTime
            if (ft == null || fTimeCls !== cls) {
                ft = ConvReader.fieldOf(cls, "field_conversationTime"); fTime = ft; fTimeCls = cls
            }
            val wxid = if (fu != null) (fu.get(item) as? String).orEmpty() else ""   // ③ 无装箱
            val ms   = if (ft != null) runCatching { ft.getLong(item) }.getOrDefault(0L) else 0L // ④ 无 Long 装箱
            out.add(ConvLayout.ConvEntry(wxid, ms))
            i++
        }
        return out
    } catch (t: Throwable) {
        // 整循环任一项抛异常 ⇒ 退回逐项 runCatching 的老实现（保命，语义不变）
        LogX.w("K7 readSnapsFast fallback at i=$i: ${t.javaClass.simpleName}")
        return readSnaps(n)
    }
}

// ConvReader 新增一个 public 的字段出口（复用现有遍历逻辑，不改行为）：
fun fieldOf(cls: Class<*>, name: String): java.lang.reflect.Field? =
    field(cls, name, ConcurrentHashMap())   // 现成的私有 field() 加个 public 包装即可
```

要点（写给下一轮改的人）：

- **① 是最大头**：`runCatching` 在 JVM 上对 `Result<R>`（R 为类型参数）会装箱，每项 3 个临时对象。改成「整循环一个 try + 失败退回老实现」，错误语义**等价**（老实现是逐项吞异常，新实现是整批吞 —— 差别只在「某一项抛异常时后面的项还读不读」，退化路径已覆盖）。
- **④** `Field.getLong` 直接返回 primitive，省掉 Long 装箱。
- `ConvEntry` 的分配（3006 个）保留 —— 去掉它要改 `ConvLayout` 的 `List<ConvEntry>` 接口（有单测），**本轮不做**，列为 §6 遗留。
- 顺带 L1c（可选，省的是那 4~6ms 里的 G×n）：`rebuildModels` 里先建一张 `wxid -> 最新下标` 的 HashMap（O(n) 一次），`buildModel` 改为遍历「该组成员集合」（Σm ≪ n）而不是遍历全部 snaps。改前先量：日志里加 `models=${}ms`。

### 2.3 L2：错峰（滚动降级 + 静止补做）—— 主方案

**新增状态**（`GroupedAdapter`）：

```kotlin
/** 上次全量 readSnaps 的时间戳 */
private var lastFullMs = 0L
/** 有全量被滚动降级、尚未补做 */
private var fullDeferred = false
```

**`ScrollDock` 侧**（`ScrollDock.kt:114-127` 已有 `trackMotionScroll` 的 afterHooked），加 O(1) 的一行：

```kotlin
// afterHookedMethod 里，与 pin() 并列
ConvFilter.noteScroll()      // 内部：lastScrollUptimeMs = android.os.SystemClock.uptimeMillis()
```

`ConvFilter` 侧暴露：`fun scrolling(): Boolean = uptime() - lastScrollUptimeMs < 150L`。
（150ms：`trackMotionScroll` 是逐帧调的，惯性滑动期间每帧都调；手指按住不动时**不调** ⇒ 会被判成静止，见 §6 遗留风险 R2。）

**`rebuild()` 改造**（伪 diff，插入位置在 `:1093` 之前）：

```kotlin
val n = runCatching { wrapped.count }.getOrDefault(0)
// ... configStamp / groups / membersDirty / summariesDirty 照旧 ...

// ===== L2 新增：滚动中降级 =====
val mustFull = membersDirty || summariesDirty || configStamp != lastStamp ||
               snaps.isEmpty() || snaps.size != n          // 结构/配置级别的硬信号，立刻全量
val cheap = ConvSnapLogic.cheapStamp(wrapped, n)           // O(k)：count + k 个采样点的 wxid/timeMs
val cheapChanged = cheap != lastCheapStamp

if (!mustFull && !cheapChanged && ConvFilter.scrolling()) {
    // 滚动中 + 廉价指纹没变 ⇒ 这一帧不做 46ms 的全量读，排到静止后补做
    fullDeferred = true
    scheduleIdleFull()                                     // 见下
    probeCost(tRebuild, 0, n, "defer")                     // 新增 path=defer
    return@synchronized false                              // 这一轮不 notify（下一轮补做时会 notify）
}

// ===== 原有全量路径（readSnaps + orderChanged + light/full）原样保留 =====
lastCheapStamp = cheap
lastFullMs = uptime()
fullDeferred = false
val fresh = readSnapsFast(n)
// ... 后面全部不变 ...
```

`scheduleIdleFull()`（复用 `ConvFilter.mainHandler`，不新造）：

```kotlin
private fun scheduleIdleFull() {
    mainHandler.postDelayed({
        if (!fullDeferred) return@postDelayed
        if (ConvFilter.scrolling()) { scheduleIdleFull(); return@postDelayed }   // 还在滚 ⇒ 继续等
        fullDeferred = false
        val changed = runCatching { rebuild() }.getOrDefault(true)
        LogX.i("K7 idleFull changed=$changed snaps=${snaps.size} layout=${layout.size}")
        if (changed) runCatching { notifyDataSetChanged() }
    }, 200L)
}
```

三个不变量（**漏了任何一条都会变成新的永冻**，写进代码注释）：

1. `fullDeferred == true` 期间，任何一次 `rebuild()` 只要看到「非滚动」或 `mustFull`，就必须清标记并走全量 —— 即 **降级只在滚动帧内成立**。
2. `onPinFoldChanged`（`:1315`）与 `refresh()`（`:476`）走的是用户主动操作路径，**不参与降级**，永远全量。
3. `wxidNow`（`:1003`）/ `itemsOf`（`:1008`）已经是实时读，不依赖 snaps ⇒ 降级窗口里「长按加分组」「标记已读」不会加错人。**这是 L2 能成立的前提，改的时候不许把它改成读 snaps。**

### 2.4 L3：采样指纹（可选兜底）+ 纯逻辑类抽取

把判据抽成纯 Kotlin，才能写 JVM 单测（照 `PinFoldLogic` 的套路）：

```kotlin
object ConvSnapLogic {
    /** 采样点：首 3 + 末 3 + 中间等距 k 个（n 变化时下标按新 n 重算，去重后升序） */
    fun sampleIndices(n: Int, k: Int = 16): IntArray { ... }

    /** 廉价指纹：count + 每个采样点的 wxid + 该点 timeMs（O(k) 次 getItem） */
    class Stamp(val n: Int, val wxids: List<String>, val times: LongArray)

    fun cheapStampOf(get: (Int) -> Pair<String, Long>, n: Int, k: Int = 16): Stamp
    fun same(a: Stamp, b: Stamp): Boolean                 // 逐下标比，顺序敏感
    fun gapOf(n: Int, k: Int): Int                        // = ceil(n / (k-1))，漏检窗口的量化口径
}
```

- 采样点必须**按下标**取（不能按内容取），这是它能挡住「删中间 + 补末尾」的原因（见 §3.1）。
- `k=16`、n=3006 ⇒ gap ≈ 200 条；`k=32` ⇒ gap ≈ 97。**k 的取值应按 §1.4 探针实测的单次 getItem 成本定**：预算 0.3ms ⇒ `k ≈ 0.3ms / 15µs ≈ 20`。

**被否决的备选（逐条理由）：**

| 备选 | 结论 | 理由 |
|---|---|---|
| A. `count` + 首末 wxid（§2.5 原方案） | ❌ | §2.0：量化收益 ≈ 1%，且会冻住 models（语义回归） |
| B. 全表 wxid 的廉价摘要（XOR / 累加 hash，无对象分配） | ❌ 作主方案 | **仍是 O(n) 次 `getItem`** —— getItem 是 46ms 的主要嫌疑人（H1），摘要只能省掉 `ConvEntry` 分配与 String 比较（≈1~3ms）。只在 H2 成立时有意义，那时 L1 已经覆盖，不值得单开一套 |
| C. 全量比对挪子线程 | ⚠️ 备选（L4，最后手段） | 见 §2.5：微信 adapter 非线程安全 + torn read；且**收益有限** —— 数据一变（收消息）timeFp 就变，回主线程还是得全量读一次，省不掉 |
| D. 反射拿到微信 adapter 的底层 `List` 容器，绕开 n 次 `getItem` | ⚠️ 备选 | 只在 H1 成立 **且** 探针显示 getItem 慢于「顺序遍历容器」时才试。风险：混淆字段名、容器可能是 `LinkedList`、微信换版即失效；必须先做「只打日志不采用」的影子验证 |
| E. 复用 `layoutBefore/layoutAfter` | ❌ | §1.5：行数相等 ≠ 顺序相等 |
| F. 加大去抖 250ms → 600ms | ⚠️ 可选微调 | 与 L2 正交，最多把 46ms 的频次再降一半；代价是分组行摘要延迟变大 |

### 2.5 备选 C（子线程）的落地条件 —— 若要试，必须这样试

不能一步到位。分两步：

1. **影子模式（无副作用）**：起一个单线程池，后台跑 `readSnaps(n)`，**结果只打日志不采用**，与主线程上一次结果比对 `orderChanged` / 一致率。看三条：① 微信崩不崩（尤其 `getItem` 内部有没有碰 UI/Handler）；② 越界/异常率；③ 与主线程读的一致性。跑 30 分钟无异常才进第 2 步。
2. **采用模式**：后台只产出 `boolean orderChanged` 与 `long timeFp`；主线程**只在 true 时**再读一次全量并重建（后台结果**绝不用于渲染**，避免 torn read 直接进 layout 造成「点错人」）。后台读一律 `try/catch` 包住，异常 ⇒ 按「变了」处理（保守，最多多一次全量）。

**能否接受检测延迟一拍**：能 —— 但只对「结构变化」容忍一拍；「分组行摘要」的更新仍在主线程补做时发生，与 L2 的滞后量级相同。

---

## 3. 漏检场景枚举（新方案会漏、现方案能抓）

### 3.0 先纠正一条前提

> 原话：「中间某条被删掉、末尾又补进一条新会话 ⇒ count 相同、首末 wxid 也相同 ⇒ 两段式判无变化」。

**这个形态不会漏检**（对「按下标取样的指纹」成立，无论 k=2 还是 k=16）：

```
old: [a0 a1 ... a_d ... a_{n-2} a_{n-1}]
new: [a0 a1 ... a_{d+1} ... a_{n-1} z]        // 删 a_d、末尾补 z
```
下标 `d` 之后所有项左移一位 ⇒ 任何 `下标 ≥ d` 的采样点读到的 wxid 都与上次不同；末尾采样点读到的是 `z ≠ a_{n-1}` ⇒ **必然判「变了」**。
（唯一例外是「补进来的正好等于原来的末项」，即同一会话重复出现，不可能。）

**真正的漏检判据**：设重排的**受影响下标区间** `[lo, hi]`（有插入/删除时取 min/max 变动下标），则

> **漏检 ⟺ `[lo, hi]` 区间内不含任何采样点**（且 count 不变、配置不变）。

### 3.1 逐条枚举

| # | 重排形态 | count | 采样(k=16, gap≈200) | 是否漏检 | 真机怎么造 |
|---|---|---|---|---|---|
| M1 | 新消息 ⇒ 会话置顶（移到第 0 位） | 不变 | `[lo,hi]` 含 0 | ✅ 抓到 | 让任意好友发一条消息（最常用） |
| M2 | 删除一条会话 | −1 | count 变 | ✅ 抓到 | 会话列表长按 → 删除该聊天 |
| M3 | 新建会话（首次给别人发消息） | +1 | count 变 | ✅ 抓到 | 通讯录里找个没聊过的人发一条 |
| M4 | 折叠/展开「置顶聊天」 | ±19 | count 变 | ✅ 抓到 | 点「折叠置顶聊天」/「N个置顶聊天」 |
| M5 | 删中间 + 末尾补一条（原「致命形态」） | 不变 | 尾采样点读到新项 | ✅ 抓到 | 滚到底触发微信加载更多历史会话，同时删掉中段一条 |
| M6 | 从一个 gap 内删一条、在**同一个 gap 内**插一条 | 不变 | 区间内无采样点 | ❌ **漏** | 见 3.2 造法 A |
| M7 | 同一个 gap 内两条会话**交换位置**（相邻/近邻 swap） | 不变 | 区间内无采样点 | ❌ **漏** | 见 3.2 造法 B |
| M8 | 跨 gap 的移动（例如第 250 位 → 第 900 位） | 不变 | 跨过 ≥1 个采样点 | ✅ 抓到 | 造法 B 的跨区版本 |
| M9 | 只改 `timeMs` 不重排（收消息但位置没动） | 不变 | 采样点 timeMs 变了 | ✅ 抓到（走全量 light） | 给「已经是最新会话」的人连发两条 |
| M10 | 只在**非采样点**上改 `timeMs` | 不变 | 采样点 timeMs 不变 | ❌ **漏（摘要滞后）** | 造法 C |
| M11 | 滚动中发生 M6/M7，且滚动在 200ms 内不停 | 不变 | 同 M6/M7 | ❌ 漏检窗口 = 滚动时长 | 造法 D |

**M6/M7/M10 的共同兜底**：`scheduleIdleFull` —— 一旦静止（或 `mustFull` 任一条件成立）就无条件全量一次 ⇒ **绝不永冻**，最坏是「滞后 ≤ 一次滚动时长（实测通常 < 1s）」。

### 3.2 真机验证步骤（可执行的）

前置：装带 L1+L2+L3 的包，开 `K7_SNAP_PROBE`（§1.4）与新增的 `K7 defer` / `K7 idleFull` 日志；`adb logcat -s WXGroup:I`（⛔ 本 lane 不执行 adb，交给下一轮/真机 lane）。

- **造法 A（M6，gap 内删+插）**
  1. 记录当前 `n`（日志 `K7 perf n=`）与 `gapOf(n,16)`；在会话列表里找**位置相邻、且在同一个 gap 内**的两条会话 X、Y（用 `K7 debounce` 行 + `wxidNow` 对账日志确定下标）。
  2. 长按 X → 删除该聊天；立刻（<250ms）在通讯录里给某人发一条消息让新会话插进来 —— 若两者下标都落在同一 gap，采样点不变。
  3. 判据：若日志出现 `K7 idleFull changed=true` 而**没有** `path=full/light` 的重排记录 ⇒ 说明采样漏了、靠巡检兜住了（记录为「漏检但已兜底」）。
- **造法 B（M7，gap 内 swap）**：真机上微信几乎不会自发做相邻 swap。用**注入**：写一个只在 debug 开关下生效的入口（设置页按钮或 `Kv` 开关触发），反射拿到 `wrapped` 的底层 `List`（与候选 D 同一条反射路径），`Collections.swap(list, i, i+1)` 后 `notifyDataSetChanged()`，其中 `i` 取两个采样点正中间（保证区间内无采样点）。
  4. 判据同上：应立即看不到变化（符合预期），但**停止滚动后** `K7 idleFull changed=true` 且列表位置修正 ⇒ 证明「有界滞后」成立而非永冻。
- **造法 C（M10）**：找一个**不在任何采样点上**的成员会话，让对方发消息，但不让它置顶（例如它本来就在最新位置附近、位置不变）⇒ 观察分组行摘要是否在 `idleFull` 后才更新。
- **造法 D（M11，滚动中的重排）**：一手持续匀速上下滑（>3s 不停），另一台设备/另一个号给本机发消息触发 notify ⇒ 日志应出现 `path=defer`（滚动帧内零全量读），停手后 200ms 内出现 `K7 idleFull`。
- **反向对照（必须做）**：把 `scrolling()` 强制返回 false（或用 `Kv` 开关关掉 L2）重跑造法 A/B ⇒ 应立即出现 `path=full` 且顺序被抓到 ⇒ 证明「漏检确实来自 L2/L3 而不是别处」。

---

## 4. 单测用例清单（纯 JVM）

**前提**：把采样判据抽成纯类 `ConvSnapLogic`（`data/` 或 `feature/`，零 Android 依赖），照 `PinFoldLogic` + `PinFoldLogicTest` 的既有套路。新增文件：`app/src/test/java/com/lx/wxgroup/feature/ConvSnapLogicTest.kt`。

`ConvSnapLogic` 需要暴露的方法：`sampleIndices(n,k)` / `cheapStampOf(get,n,k)` / `same(a,b)` / `gapOf(n,k)` / `affectedRange(old,new)`（后者只为单测服务，用来把 §3.0 的判据写成断言）。

### 4.1 `sampleIndices`
1. `n=0` ⇒ 空数组（不许越界、不许抛）
2. `n=1` ⇒ `[0]`（去重后唯一）
3. `n=2` ⇒ `[0,1]`
4. `n < k`（如 n=5,k=16）⇒ 全覆盖 `[0..4]`，无重复、升序
5. `n=3006,k=16` ⇒ 首元素 0、末元素 3005、长度 ≤ k、严格升序、无重复
6. `k=0/1` ⇒ 退化成「只取首」或空（定义清楚并写进 KDoc）
7. 幂等：同 `(n,k)` 两次调用结果**逐字节相同**（防止引入随机/哈希序）
8. 边界：`n=3006,k=32` 时 gap 单调不增

### 4.2 `cheapStampOf` / `same`（顺序敏感性 —— 最重要的一组）
9. 完全相同序列 ⇒ `same == true`
10. **首尾相同的中段 swap**（`[a b c d e] → [a b d c e]`，采样只取首尾）⇒ `same == false`（这条是老「count+首末」方案的失败用例，必须留下）
11. 同上但 swap 恰好落在两个相邻采样点之间、且 swap 后的区间内无采样点 ⇒ `same == true`（**刻意为之的漏检，写进注释**，证明我们对漏检边界有明确预期）
12. **删中间 + 末尾补新**（§3.0 的 M5）⇒ `same == false`（这是纠正前提的关键用例）
13. count +1 / −1 ⇒ `same == false`
14. 仅 timeMs 变（采样点上）⇒ `same == false`；仅 timeMs 变（非采样点上）⇒ `same == true`
15. 空 wxid（读不到对象）出现在采样点 ⇒ 保守判 `false`（对齐 `ConvLayout.orderChanged` 对空串返 true 的既有约定）
16. `get` 回调抛异常 ⇒ 不崩，退化为「变了」

### 4.3 `affectedRange`（把 §3.0 判据形式化）
17. 纯 swap(i,j) ⇒ 返回 `[i,j]`
18. 删除 d + 末尾插入 ⇒ 返回 `[d, n-1]`（**必须覆盖到末尾采样点** ⇒ 断言「区间内存在采样点」为真）
19. 无变化 ⇒ 返回空
20. 组合断言：对随机生成的 200 组「长度 ≤ gap 的局部置换」，`affectedRange ∩ sampleIndices == ∅` 的比例应等于「采样漏检率」，写成**定量的漏检率基线**（用来在 k 取值变化时做回归）

### 4.4 `ConvLayoutTest` 补充（现有文件，只加不改）
21. `orderChanged` 对「条数相同、中间 swap」返 true（**已有？若已有跳过**；若无必须补 —— 它是 R1 那条修复的判据底座）
22. `orderChanged` 对「仅 timeMs 变」返 false（保证 light 路径不会被误判成 full）
23. 空 wxid 出现 ⇒ 返 true（既有约定，加一条覆盖）

### 4.5 变异验证思路（≥2 组，验证「测试真的能抓到 bug」）

- **变异 1（顺序敏感性）**：把 `same()` 从「逐下标比较」改成「`wxids.toSet() == other.wxids.toSet()`」（顺序不敏感）。
  期望：用例 10（中段 swap）**必须失败**。若不失败 ⇒ 测试无效。
- **变异 2（末尾覆盖）**：把 `sampleIndices` 的末点从 `n-1` 改成 `n-2`。
  期望：用例 12（删中间 + 末尾补新）**必须失败**（因为末尾采样点不再覆盖新项）。若不失败 ⇒ 说明用例没真正覆盖 §3.0 的形态。
- **变异 3（巡检兜底）**：把 L2 的 `scheduleIdleFull` 删掉（降级后不补做）。
  期望：造法 D 的真机脚本与「滞后有界」断言失败 ⇒ 对应到单测层：把 `mustFull || !scrolling ⇒ 清 fullDeferred` 改掉，用例「降级后静止 ⇒ fullDeferred 必被清空」应失败。
- **变异 4（可选）**：把 `k` 默认从 16 改成 2。期望：用例 11 仍通过（本来就漏），但 §4.3 用例 20 的「漏检率基线」显著变差 ⇒ 用数字证明 k 的价值。

---

## 5. 真机判据（两条都要，缺一条不算完）

### 5.1 性能下去了

1. **主判据**：重复原始操作（主界面上下滚 4 轮，≥20s）：
   - `K7 perf` 命中数 **0**；或命中行的 `snaps` ≤ 8ms。
   - 日志里出现 `path=defer`（滚动帧内零全量读），且**每次 defer 之后 200ms 内必有一条 `K7 idleFull`**（证明补做没丢）。
2. **辅助**：`adb shell dumpsys gfxinfo com.tencent.mm framestats` 前后对比 jank 数（与未改包基线比，应有下降）；若做了 §1.4 探针，`K7 snapprobe` 的 `full` 值应显著低于 46ms（L1 生效的证据）。
3. **反向对照**：用 `Kv` 开关关掉 L2（`scrolling()` 恒 false）再滚一轮 ⇒ `K7 perf` 应重新命中 ⇒ 证明减少的耗时确实来自 L2 而不是「这次碰巧没触发」。

### 5.2 没漏检（这条更要紧）

1. **重排能被抓到**：M1/M4/M5（§3.1）各做一次 ⇒ 日志必须出现 `path=full` 或 `path=light` 且 `orderDirty` 生效（新增一行 `K7 order changed=true` 日志即可，现在的 `K7 perf path=` 已经能间接区分）。
2. **降级不丢活**：造法 D（滚动中收消息）⇒ `path=defer` → `K7 idleFull changed=true` → 停止滚动后列表位置与分组行摘要**在 200ms 内**对齐（人眼：停手后分组行的时间/未读立刻是对的）。
3. **点不错人**：滚动中随机点第 3/5/8 条会话 ⇒ 打开的聊天对象与点击位置一致（`wxidAt` / `wxidNow` 对账日志已有，加一行打印即可）。这是「没漏检」的最终用户可感判据。
4. **不永冻**：造法 B（注入 gap 内 swap）执行后**不重启不切页**，仅靠静置 ⇒ 列表必须在一次 `idleFull` 内自行修正。若 5s 内未修正 ⇒ 判失败。
5. **摘要不滞后**：收一条群消息（该群在某分组内）⇒ 分组行的最后消息/时间在 ≤1 次 `idleFull` 内更新。若长期不动 ⇒ 说明 L2 的补做链路断了。

---

## 6. 遗留风险

- **R1｜H1 成立（getItem 就是慢）**：L1 收益趋零，全量 46ms 只能靠 L2 挪到静止帧。若静止帧的 46ms 仍不可接受 ⇒ 上 L3（采样）或 L4（影子后台读），但两者都以「有界滞后」为代价。**先跑 §1.4 探针再决定，不要盲改。**
- **R2｜静止判定不准**：`trackMotionScroll` 只在真正位移时调；「手指按住不动」「缓慢拖动中的停顿」会被判成静止 ⇒ 46ms 可能打在触摸暂停那一帧。缓解：把 idle 判据改成「无 `trackMotionScroll` ≥ 200ms **且** 无未抬起的 `MotionEvent`」（需再加一个 `dispatchTouchEvent` 时间戳，成本 O(1)）。未验证前按 150ms 保守值。
- **R3｜降级窗口内的陈旧 snaps**：`wxidOf`（`:992`）是诊断用途，降级期间可能是旧值 —— 已有 `wxidNow` 覆盖所有「一步都不能错」的入口（长按加分组、菜单注入）；**新增代码不得再引入基于 `wxidOf` 的写入路径**。
- **R4｜`rebuildModels` 的 G×n**：L1c（建 wxid→下标 HashMap）若要改，需同时改 `buildModel` 的「未读求和」语义（现在靠遍历 snaps 累加），注意别把「同一会话被两个组共有」的脏数据行为改坏。
- **R5｜采样 k 的选择**：k 越大越不漏、越慢；k 的取值应由实测 `getItem` 单价定（预算 0.3ms）。换微信版本/换机型后单价可能变，**k 应可配置**（`Kv`，默认 16）。
- **R6｜`ConvEntry` 分配未消除**（3006 个/次）：改成并行 `Array<String>` + `LongArray` 要动 `ConvLayout` 的对外接口（有单测），本轮不做。若 L1 后仍 >16ms，这是下一个杠杆。
- **R7｜时序结论未证实**：「这 46ms 导致滚动掉帧」目前仍是推断（§0.4）。改完若 `K7 perf` 不再命中但用户仍反馈滚动卡顿 ⇒ 卡顿另有来源（很可能是 `getView` 或微信自身），**不要继续在 readSnaps 上堆优化**。
- **R8｜本轮未改任何源码**：以上 diff 均为方案级，需下一轮在 `ConvFilter.kt` / `ScrollDock.kt` / 新增 `ConvSnapLogic.kt` 上落地；落地时与另一条 lane（`feature/SnsDeletedTag*.kt`）**分开装包**，否则 `K7 perf` 的归因会被污染。

## 7. 探针已落地（2026-11-01）

§1.4 设计的裁决探针已按 R37-2 装包前交叉复核（`docs/_r37_review2.md` P1-1~P1-4）修完后落地。
**只加计时与日志，没碰任何判据，`readSnaps` 的产出与探针无关。**

### 7.1 改了哪些文件的哪些地方（落地后行号）

| 文件:行 | 改动 |
|---|---|
| `data/Keys.kt:292` | 新增 `const val DEBUG_K7_SNAP_PROBE = "debug.k7_snap_probe"` + KDoc（默认关、取完证记得关回） |
| `data/Keys.kt:446` | `Prefs.ALL` 登记这条 `PrefSpec`（SWITCH，默认 `"false"`）⇒ 设置页可见、`Prefs.defaultOf` 有值 |
| `feature/ConvFilter.kt:110` | `SNAP_PROBE_INTERVAL_MS = 30_000L`（节流窗口） |
| `feature/ConvFilter.kt:122` | `SNAP_PROBE_LIMIT = 500`（每段循环上限，`n` 仍打进日志） |
| `feature/ConvFilter.kt:140` | `snapProbeDue()`：**先判节流窗口 → 再判 `ScrollDock.scrolling()` → 命中窗口才读 `Kv`** |
| `feature/ConvFilter.kt:174` | `runSnapProbe()`：五段均跑 `lim = minOf(n, 500)`，`refl` 段在 `probeItem == null` 时跳过并打 `refl=?`，日志带 `n/lim/scale` |
| `feature/ConvFilter.kt:1544/1557` | `readSnaps()` 里 `val probe = snapProbeDue()` + `t0`（`full` 只量真实读循环）+ 末尾 `if (probe) runSnapProbe(...)` |
| `feature/ConvFilter.kt:1343` | `probeCost()` 的 `K7 perf` 追加第 6 个字段 `scrolling=` |
| `feature/ScrollDock.kt:153` | `trackMotionScroll` 的钩子里，时间戳**只在 `ConvFilter.wraps(lv)` 为真时才打**（否则会话列表以外的 List 滚动会制造 `scrolling=true` 假阳性） |
| `feature/ScrollDock.kt:75/83` | `lastScrollMs`（`@Volatile`）+ `scrolling(gapMs = 150)` |

### 7.2 为什么还要加这三道闸（复核 P1-1 / P1-2 / P1-3 / P1-4）

- **P1-1**：钩子类是 `android.widget.AbsListView`（基类），聊天消息列表/通讯录/朋友圈/搜索结果都会进来
  ⇒ 不加 `wraps(lv)` 门，`scrolling=true` 就是别的列表制造的假阳性，而它的唯一使命是
  「这次 46ms 是不是打在**会话列表**的滚动帧里」。
- **P1-2**：`rebuild()` 在 `mainHandler` 上跑（主线程）且整个 `rebuild` 持 `synchronized(lock)`
  ⇒ 探针自身的几百毫秒会结结实实压在一帧上；更糟的是 `tail` 段专门验证「容器是不是链表」，
  它真是链表时这一段的真实成本是 O(n²)≈9×10⁶ 次 ⇒ **取证动作自己 ANR**。
  对策：滚动时不取（丢到静止窗口）+ 每段钳到 500。
- **P1-3**：`ConvReader.username(null)` 会秒返回空串 ⇒ `getItem(0)` 取不到时 `refl` 会退化成 ~0ms
  这种**假数据** ⇒ 跳过并打 `refl=?`，别拿它给 H2 下结论。
- **P1-4**：`Kv.getBool` → `GroupDb.getPref` 是一次 **SQLite `query`**（无内存缓存）。原写法把它
  放在每次 `readSnaps` 的开头 ⇒ 开关关着也每次 rebuild 查一次库，且这笔开销落在 `tSnaps..snapsCost`
  之内 ⇒ **污染了本轮唯一要测的 46ms 基线**（上一轮的 46ms 与这一轮口径不再同源）。
  现在：**窗口没到就 return，开关关着时最多 30s 一次查库**。

### 7.3 开关

`debug.k7_snap_probe`，**默认 `false`**，改完不用重启微信：

```
python tools/dev.py probe kvset --key debug.k7_snap_probe --val true
```

### 7.4 装包后主会话要跑的完整序列

> 先跑完灰标那两条判据（`docs/_r37_name_anchor.md` §5-①②）再动探针 —— 探针会往主线程里塞
> 几百毫秒，别在还没确认灰标没事的时候把场面搞乱。

```bash
# ① 灰标先过（别跳过）
adb logcat -d | grep "SNS_DELTAG stat"        # noAuthor=0 且 lastRecon 不是 noauthor(...)
adb logcat -d | grep "SNS_DELTAG path"        # 仍是 m0 -> @

# ② 开探针
python tools/dev.py probe kvset --key debug.k7_snap_probe --val true

# ③ 静止取第一份样本（手指离开屏幕 ≥1s，静置 ≥35s）
adb logcat -c
adb logcat -d | grep "K7 snapprobe"           # 期望看到 scrolling=false
adb logcat -d | grep "K7 perf"                # 顺带看看基线 46ms 现在是多少

# ④ 滚动取样：会话列表上下滚 2 屏 → 停手 → 再静置等到下一个 30s 窗口
adb logcat -d | grep "K7 perf"                # scrolling=true / false 两种都要有
adb logcat -d | grep "K7 snapprobe"           # 至少再拿 1~2 条（其中有静止期的一条）

# ⑤ 判读（见 7.5），然后把开关关回去
python tools/dev.py probe kvset --key debug.k7_snap_probe --val false
```

### 7.5 判读表（⛔ 注意 `lim` 缩放）

日志形如
`K7 snapprobe n=3006 lim=500 scale=6.012 full=46ms seq=7ms head=6ms tail=8ms refl=1ms alloc=0ms gc=1234->1235 scrolling=false`

**五段只跑了 500 次，而 `full` 是真实 3006 次** ⇒ 拿它们跟 `full` 比大小之前先乘 `scale`
（= `n / lim`，日志里已给出）。段与段之间的**比例**（裁决真正用的东西）不受缩放影响。

| 现象 | 结论 | 动作 |
|---|---|---|
| `tail ≫ seq`（如 >3 倍） | **候选 D**：容器是链表 / O(n²) 随机访问 | ⛔ **立即把开关设回 false 再继续滚**（下一次探针会按比例变贵，可能 ANR）；这条本身就是 H1 的答案 ⇒ 别再做 L1 的「每项降本」，去做 L2 错峰 / L3 采样 |
| `seq × scale ≈ full` | **H1**：微信 `getItem` 就是慢 | 往 L2（错峰）走；L1 收益有限 |
| `refl × scale` 单项 ≈10µs / 次 | **H2**：我们侧反射+装箱被放大 | `ConvReader` 的字段缓存 / 装箱；做 L1a/L1b |
| 五项之和 × scale ≪ full，且 `gc` 计数跳变 | **H3**：GC 主导 | 先减分配（`ConvEntry` 3006 个/次 ⇒ §6 R6），再谈别的 |
| `refl=?` | `getItem(0)` 拿不到对象 ⇒ 这一段无证据力 | 先修取数，别用假数据给 H2 下结论 |
| `scrolling=true` 的那条 `K7 perf` | 46ms 确实打在滚动帧里 ⇒ R7 的推断成为事实 | 这是 §0.4 那笔账的对账点 |
| `scrolling=false` 的 `K7 perf` | 只是一次静止期的顿，**不构成掉帧证据** | 别把它算进「掉帧」证据里 |
| `K7 perf snaps=` 与 `K7 snapprobe full=` 的差值 | ≈ 一次 SQLite 查库开销（P1-4 之后**只会在探针命中的那一次**出现） | 每 30s 偶发几次属正常；若**每次** rebuild 都差 ⇒ P1-4 没生效，回去看 `snapProbeDue()` 的顺序 |

---

[DONE]
