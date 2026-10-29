# R35 结构性复核：SnsDeletedTag / SnsDeletedTagLogic

- 复核对象：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsDeletedTag.kt`（1098 行）、`SnsDeletedTagLogic.kt`（173 行）、`HookEntry.kt` 接线
- 目标环境：微信 8.0.48 / 2588 GP 版，模块包 `com.lx.wxgroup`
- 本轮为**纯静态只读**复核（不碰 adb / 不跑 assembleDebug / 不改 `app/src/main`）
- 判定口径：P0 = 静默失效 / 崩微信 / 标记打错位置；P1 = 有明确风险但当前不会出事；P2 = 代码质量；OK = 查过没问题

---

## 1. `bound: WeakHashMap<View, Binding>` 的泄漏与正确性

### 1.1 值 `Binding` 里有没有强引用 key（`View`）的路径？

**没有。严重度 = OK。**

`Binding` 的全部字段（SnsDeletedTag.kt:126-129）：

```kotlin
private class Binding(val id: Long) {
    @Volatile var tvId: Int = View.NO_ID   // 127
    @Volatile var hint: String = ""        // 128
}
```

只有 `Long` / `Int` / `String` 三个标量，**没有任何指向 `View` / `ViewHolder` / 数据对象的引用**
（类注释 123-124 已经把这条约束写明，实现也确实遵守了）。

逐条排查其它可能把 key 钉住的引用：

| 候选引用点 | 是否钉住 key | 依据 |
|---|---|---|
| `Binding` 字段（126-129） | 否 | 全是标量 |
| `snapshot()`（980-990）返回的 `List<Pair<View,Binding>>` | 短暂 | 局部变量，`sweep`（970-976）/ `dump`（1066）用完即弃 |
| `viewFieldCache`（369）/ `dataPathCache`（432）/ `timeMethodCache`（751） | 否 | 存的是 `Field` / `String` / `Method`，键是类名不是实例 |
| `retryResolve` 的 lambda（351） | 是 | ⚠️ 但它捕获的是 `holder: Any`（微信数据侧对象），**不是** `bound` 的 key；且只 `post` 一拍，不累积 |
| `sweeper`（938-945） | 否 | object 单例，不持有 View |

**结论**：`WeakHashMap` 的 value 侧不存在回到 key 的强引用路径 ⇒ 条目可正常随 View 被 GC 回收。
`WeakHashMap` 用 key 的 `hashCode`/`equals`，`android.view.View` 未覆写这两者 ⇒ 走身份语义，符合预期。

---

### 1.2 `synchronized(bound)` 与 `WeakHashMap` 混用是否覆盖所有读写点？

**锁的监视器是同一个，但读写点覆盖不全。严重度 = P2（当前不出事，结构上有隐患）。**

**（a）混用本身是对的** —— 这点必须先说清，否则会误判：
`bound` = `Collections.synchronizedMap(WeakHashMap<View, Binding>())`（132-133）。
JDK `Collections.SynchronizedMap` 的默认 `mutex = this`（即包装器对象本身），而 `bound` 引用的正是这个包装器
⇒ `synchronized(bound) { ... }`（297/949/972/982/1052/1058）与 `bound[...]` 内部加的锁**是同一把监视器**，混用不会互相穿透。

**（b）已加锁的读写点**：
297（读）、949（isEmpty）、972（remove）、982-988（遍历）、1052（size）、1058（values.count）。

**（c）漏锁的读写点**（逐条列，都是热路径）：

| 行号 | 操作 | 所在路径 |
|---|---|---|
| 302 | `val prev = bound[view]` 读 | `bindHook` |
| 306 | `bound[view] = it` 写 | `bindHook` |
| 358 | `val prev = bound[view]` 读 | `retryPaint` |
| 360 | `bound[view] = it` 写 | `retryPaint` |
| 667 | `val prev = bound[itemView]` 读 | `apply` |
| 669 | `bound[itemView] = it` 写 | `apply` |
| 678 | `val own = bound[itemView]` 读 | `apply` |
| 714 | `timeTextOf(host, bound[host])` 读 | `restore` |

**（d）为什么当前不会出事**：
上述 8 处的调用方全部在**主线程**：
`bindHook` 是 RecyclerView 绑定回调（主线程）、`retryPaint` 经 `mainHandler.post`（351，主线程）、
`apply` 只被 `SnsTag`（SnsTag.kt:271/292/573，其 bindHook 与 sweeper 都在主线程）调用、`restore` 同理。
唯一的后台线程 `sns-deltag-ids`（1010-1020）只写 `delSet` / `setAt`，**从不碰 `bound`**。
⇒ 实际是单线程访问，不触发并发问题。

**（e）为什么仍要记为 P2**：
`WeakHashMap` 的 `get`/`put`/`size` 内部都会调 `expungeStaleEntries()` 并**改写 `table` 数组与 `size`**，
在无锁并发下确实会丢更新 / 在极端情况下形成引用链环（历史上 JDK 上真实存在的失效形态）。
现在靠「所有访问点恰好都在主线程」这一**未被任何机制保证**的事实兜着 ——
一旦以后把 `stat()`/`dump()`（1051/1061，这两个**已经**加了锁）以外的新入口接到广播线程
（参照 `EntryReceiver.kt:602-606` 直接调 `SnsTag.stat()`），或哪天 `deleted()` 的后台线程顺手去清 `bound`，就会中招。

**建议**：把 302/306/358/360/667/669/678/714 统一收进 `synchronized(bound)`，或干脆提供
`private fun get(v: View) / put(v: View, b: Binding)` 两个带锁访问器，杜绝漏锁。

---

### 1.3 `snapshot()` 返回的是快照还是活引用？调用方有没有在锁外遍历？

**是快照；调用方确实在锁外遍历，但这是安全的。严重度 = OK。**

`snapshot()`（980-990）：在 `synchronized(bound)` 内 new 一个 `ArrayList<Pair<View,Binding>>`（981），
逐条 `out += k to v`（986），**并且滤掉了 `e.key == null`（984）**——这一句很关键：
`WeakHashMap` 迭代时 referent 可能已被回收而条目尚未 expunge，`e.key` 会是 null，不过滤就会在
`k to v` 之后的 `v.isAttachedToWindow`（971）/ `paint`（975）上 NPE。
（对比 `SnsTag.snapshot()`，SnsTag.kt:578-587 只滤了 key 没滤 value，因为它的 value 是非空 `Long`。）

调用方遍历位置：
- `sweep()`（970-976）—— 锁外遍历 `snap`，期间 `bound` 可被修改（例如 972 的 `remove`）⇒ **不会** `ConcurrentModificationException`，因为遍历的是副本。
- `dump()`（1066-1073）—— 同上。

副作用：`snap` 在遍历期间对 View 是**强引用**（`Pair<View, Binding>`），把已 detach 的 View 多钉住几毫秒；
`sweep` 很短（见 §5 定量估算 ≈ 0.1 ms/次），无实际影响。

---

**§1 小结**：1.1 OK / 1.2 P2 / 1.3 OK。

---

## 2. `sweep()` 的线程模型

### 2.1 跑在哪个线程？

**主线程。**：`sweeper`（938-945）由 `ensureSweep()` 用 `mainHandler.postDelayed(sweeper, SWEEP_MS)`（951）投递，
而 `mainHandler` = `Handler(Looper.getMainLooper())`（135）⇒ 整个 `sweep()`（966-977）连同它调用的
`on()` / `deleted()` / `paint()` / `timeTextOf()` 全在**主线程**执行。

这是设计上的取舍（`paint` 必须动 View，只能在主线程），本身没问题；问题在 §2.2 的「里面做了什么」。

---

### 2.2 里面有没有 `LogX`（同步文件 IO）或 DB 读？

**两样都有。严重度 = P2（都被限流，量级很小）。**

`LogX` 确实是同步文件 IO（取证）：`LogX.file()`（util/LogX.kt:91）是
`java.io.File(path).appendText(...)` —— 每次调用 = 一次 open/write/close，在调用线程上同步完成，
且整段在 `synchronized(lock)`（util/LogX.kt:68）里，还会 `SimpleDateFormat` + `new Date()`。单次约 0.3~3 ms。

`sweep` 一跳里的 IO/DB 清单：

| 行号 | 动作 | 类型 | 频率 |
|---|---|---|---|
| 969 | `on()` → `Kv.getBool(Keys.ANTIREVOKE_SNS)` → `Kv.getString` → `db().getPref`（Kv.kt:24-31） | **SQLite 读** | 每 30 s 一次（`CFG_TTL_MS=30000`，74/323） |
| 969 | `deleted()` → 首屏那一次 `loadIds()` → `db.snsTagIds()`（GroupDb.kt:537-547） | **SQLite 读** | 仅首次（`firstLoad`，1001-1007），详见 §3.1 |
| 975 → 689 | `paint` → `dumpTree` → `LogX.i`（913） | 同步文件 IO | 每个根 View 类**一次**（`treeDumped`，907-909） |
| 975 → 694-696 | `missedTime` 告警 | 同步文件 IO | 全局前 5 条 |
| — | `dumpFields`（618-641） | 同步文件 IO | 每（角色×类）一次，不在 sweep 内，在 `probePath` 内（486/489） |

稳态下一次 sweep 的开销 ≈ 「N 次 `Method.invoke` + 文本比对」（见 §5 定量 ≈ 0.1~1 ms），**没有** IO、没有 DB。

---

### 2.3 `sweepScheduled` 的置位/清位是否成对？异常路径会不会永久不再调度？

**成对，且异常路径也能恢复。严重度 = OK。**

```kotlin
private val sweeper = object : Runnable {
    override fun run() {
        sweepScheduled.set(false)                                        // 940 清位
        runCatching { sweep() }                                          // 941-942
            .onFailure { LogX.w("SNS_DELTAG sweep err: ...") }           // 942
        ensureSweep()                                                    // 943 重新置位
    }
}
```

- `runCatching` 包的是 `Throwable`（Kotlin 的 `runCatching` 连 `Error` 也吞）⇒ 任何异常都不会跳过 943。
- `ensureSweep()`（948-953）在 `bound` 非空时 CAS 置位并重新 `postDelayed` ⇒ **异常不会导致永久静默**。
- 唯一的「停」是 949 `bound.isEmpty()` 时不排下一拍；这不是泄漏 —— 下次 bind 成功会在 309 重新 arm，
  且 `bound` 空时本来就无物可画。
- ⚠️ 一个相邻风险：`ensureSweep()` **只在** `bindHook`（309）和 `retryPaint`（363）里被调用，
  `apply()`（660-684）里**没有**（见 §7）。若本类钩子全挂不上、`bound` 恒空，sweep 永不启动 ——
  那是 §6 的 install 失败分支，不是 sweep 自身的缺陷。

**额外的两个小瑕疵（P2）**：
- 940 的清位在 `run()` **开头**而不是结尾 ⇒ `sweep()` 执行期间若有第二线程 `ensureSweep()` 成功，
  会出现两个 sweeper 并存。当前单线程，且每个 `run` 只排一拍（不会指数增长），无害。
- 没有「进程/页面前台」判定：只要 `bound` 非空就一直 2 s 一跳。退到后台时 itemView 通常仍
  `isAttachedToWindow`（Activity 未销毁）⇒ 后台也继续跑。成本 ≈ 0.1~1 ms / 2 s，可忽略，但确实不停。

---

### 2.4 有没有引用泄漏（Runnable 持有 View 不放）？

**没有。严重度 = OK。**

- `sweeper`（938-945）是 object 单例，闭包里只有 `LogX.w(...)` 与 `ensureSweep()`，**不捕获任何 View**。
- View 的唯一临时强引用是 `snapshot()` 产出的 `snap`（967），在 `sweep()` 返回后即释放（§1.3）。
- `bound` 本身是弱键（§1.1）， detach 的 View 在 971-973 被显式 `remove`，剩下的交给 GC + WeakHashMap。
- `retryResolve`（348-352）的 lambda 捕获了 `holder`（不是 key），且只 `post` 一拍、有 `RETRY_MAX=12` 预算（333/349）。

---

**§2 小结**：2.1 OK / 2.2 P2 / 2.3 OK / 2.4 OK。
需要留意的只有「`on()` 里 30 s 一次的主线程 SQLite 读」和「后台不停跳」，都是量级问题不是正确性问题。

---

## 3. `deleted()` / `loadIds()` 的缓存

### 3.1 首屏同步查库发生在哪个线程？

**主线程。严重度 = P2（是主线程 DB 读，但数据量小，不会 ANR）。**

`deleted()`（995-1023）的四个调用点全部在主线程：

| 调用点 | 线程 | 说明 |
|---|---|---|
| 310（`bindHook` → `paint(view, b, deleted())`） | 主线程 | RecyclerView 绑定回调 |
| 364（`retryPaint`） | 主线程 | `mainHandler.post`（351） |
| 969（`sweep`） | 主线程 | `mainHandler.postDelayed`（951），见 §2.1 |
| 683（`apply`） | 主线程 | 只被 `SnsTag` 的主线程路径调用 |

第一次进入时 `firstLoad=false`（83/1001）⇒ 走 1002-1006 的**同步**分支：

```kotlin
if (!firstLoad) {
    val s = loadIds()                                    // 1002 ← 同步 SQLite 查询
    if (GroupDb.current() != null) firstLoad = true      // 1003
    delSet = s; setAt = now                              // 1004-1005
    return s
}
```

`loadIds()`（1025-1039）→ `db.snsTagIds()`（GroupDb.kt:537-547）= `query(T_SNS, ["sns_id"], …)`，
**无 LIMIT 全表扫**，但只取一个 INTEGER 列，且 `T_SNS` 仅装「`SnsGuard` 拦下的物理 DELETE 归档」，
量级是几十行 ⇒ 单次约 1~5 ms。**当前不会 ANR**；若 `T_SNS` 将来涨到万行级，这条就升 P1。

两处值得记的细节：
- 1003 的 `if (GroupDb.current() != null)` 是对的：库还没开时不把首屏机会用掉，之后每次都重试。
  库未开时 `loadIds()` 在 1027-1033 直接返回旧 `delSet`，几乎零成本 ⇒ 重试不痛。
- ⚠️ 另一个被忽略的主线程 DB 读：`on()`（322-328）→ `Kv.getBool` → `Kv.getString` → `db().getPref`
  （Kv.kt:24-31，也是 SQLite）。有 30 s TTL（`CFG_TTL_MS`，74/323），且 `stat()`（1051）也会调 `on()`。
  频率低，但性质上仍是「主线程查库」。

---

### 3.2 `firstLoad` / `setAt` / `delSet` 三个 `@Volatile` 分三次写，读方看到的是一致组合吗？

**一致。不会出现「`delSet` 已换新但 `setAt` 还是旧值」导致 TTL 判错。严重度 = OK。**

两条写路径的写序都是 **先 `delSet` 后 `setAt`**：

- 首屏同步路径：1003（`firstLoad`）→ 1004（`delSet`）→ 1005（`setAt`）
- 后台刷新路径：1013（`delSet = loadIds()`）→ 1014（`setAt = SystemClock.elapsedRealtime()`）

读方在 997：`if (setAt != 0L && now - setAt < SET_TTL_MS) return delSet`。
`delSet`（81）与 `setAt`（82）都是 `@Volatile` ⇒ 对 `setAt` 的 volatile 写会把它之前的写（含 `delSet`）全部释放，
对 `setAt` 的 volatile 读会拿到那次释放的全部内容
⇒ **「看到新 `setAt` 却看到旧 `delSet`」在 JMM 下不可能发生**。

反向组合（「`delSet` 已换新但 `setAt` 还是旧值」）**会**发生（后台线程写完 1013、还没写 1014 时读者进来），
但后果是无害的：
- 读者在 997 看到旧 `setAt`（看起来 TTL 已过期）⇒ 落到 1009 的 CAS；
- CAS 失败（后台正在刷）⇒ 1022 返回 `delSet`，此时读到的是**新的**（甚至更好）；
- 最坏只是下一拍多试一次 CAS，不会有「拿新清单却按旧 TTL 判断」的错误。

`firstLoad`（83）与另两个没有顺序依赖（它只决定走同步还是后台分支，读它的是同一个线程）⇒ 无影响。

---

### 3.3 `setLoading.compareAndSet` 失败就直接返回旧 `delSet` —— 有没有「永远刷不上」的路径？

**有，而且是结构性的：`.start()` 在 `try/finally` 之外。严重度 = P1。**

```kotlin
if (setLoading.compareAndSet(false, true)) {        // 1009  ← 置位
    Thread {                                        // 1010
        try {                                       // 1011
            runCatching {                           // 1012
                delSet = loadIds()                  // 1013
                setAt = SystemClock.elapsedRealtime()   // 1014
            }
        } finally {
            setLoading.set(false)                   // 1018  ← 复位，**只在线程体内**
        }
    }.apply { isDaemon = true; name = "sns-deltag-ids" }.start()   // 1020  ← 在 try 之外
}
return delSet                                       // 1022
```

**路径 A（永久静默）**：`Thread.start()`（1020）本身抛异常 —— 典型是
`OutOfMemoryError: unable to create new native thread`（微信进程线程数本来就多）、或线程创建被系统拒绝。
此时 lambda 体**一次都没执行**，`finally`（1016-1019）永远不会跑 ⇒ `setLoading` **永久为 true**
⇒ 之后每一次 `deleted()` 都在 1009 CAS 失败、直接返回旧 `delSet` ⇒ **已删清单永久不再刷新**。
注释（1017）写的「漏复位的话标志永久卡住」正是这个场景，但代码只防住了「线程体内异常」这一半，
**没防住「线程根本没起来」这一半**。

**路径 B（长时陈旧）**：线程起来了，但 `loadIds()` 在 SQLite 上被阻塞
（主线程正持有写锁做批量插入 / 微信自己的 `SnsInfo` 写库）⇒ `setLoading` 长时间为 true，
期间所有刷新请求被吞，标记停在旧清单上。阻塞多久就陈旧多久，没有超时/打断机制。

**修法（两行）**：把 1010-1020 整体包进 `try`，失败即复位 ——

```kotlin
if (setLoading.compareAndSet(false, true)) {
    try { Thread { ... }.apply{...}.start() }
    catch (t: Throwable) { setLoading.set(false); LogX.w(...) }
}
```

⚠️ **同一缺陷在 `SnsTag.deleted()` 里一模一样地存在**（SnsTag.kt:616-628，同样是
`compareAndSet` → `Thread{ try/finally }.start()`），要修就一起修。

---

### 3.4 后台线程写 `delSet` 后，界面靠什么刷新？只靠 2 s sweep 会不会扫不到？

**只靠 2 s sweep；当前不会扫不到，但覆盖是靠「两条链互为兜底」凑出来的，不是本类自己保证的。严重度 = OK（附带两条 P2 备注）。**

刷新链路：`sweep()` 每跳都重新调 `deleted()`（969）⇒ 后台写完后 ≤ 2 s 被读到 ⇒ `paint` 重新判定。
（另有一条：`SnsTag.sweep()`（SnsTag.kt:561-575）每 2 s 调 `SnsDeletedTag.apply(v, id, tagged)`，
它内部走的是**本类**的 `deleted()`（683）⇒ 也能触发刷新。）

本类 sweep 的启动条件是 `bound` 非空（949），而 `ensureSweep()` 只在两处被调：

- `bindHook`（309）—— 本类自己解析成功一条就 arm；
- `retryPaint`（363）—— 兜底重试成功时也 arm。

⚠️ **盲区（P2）**：`apply()`（660-684）**不调 `ensureSweep()`**。
所以「本类钩子一条都没解析成功（`bound` 恒空）+ 只有 `SnsTag` 老链路在画」这种组合下，
本类 sweep 永不启动。此时刷新其实仍被 `SnsTag.sweep` 兜住（它每 2 s 调一次 `apply` → 本类 `deleted()`），
所以**当前不会漏**；但这属于「靠别人」，一旦 §7 里讨论的「摘掉老链路」落地，
就必须给 `apply()` 补上 `ensureSweep()`，否则删掉一条动态后标记要等下一次 bind 才出现。

⚠️ **第二条（P2）**：本类**没有 `invalidateIds()`**。`SnsTag` 有（SnsTag.kt:596-598）且被广播调用
（`EntryReceiver.kt:523`，用于 `op=snsg sub=srcfix sub2=clean` 之类外部改库后立刻失效）。
本类清库后只能等 5 s TTL（`SET_TTL_MS`，73）⇒ 「清完还挂着标记」最多看 5 s。

---

**§3 小结**：3.1 P2 / 3.2 OK / 3.3 **P1**（`.start()` 在 try 外 ⇒ 存在永久卡死的路径）/ 3.4 OK（+2 条 P2 备注）。

---

## 4. `paint()` 的判定与副作用

### 4.1 `hits++` 在 `timeTextOf()` 之前 —— 这个计数会不会让人误判成「画上了」？

**会。`hits` 是「判定命中的 paint 调用次数」，不是「画上了几条」。严重度 = P1（诊断口径缺陷）。**

```kotlin
private fun paint(host: View, b: Binding, tagged: Set<Long>) {
    if (SnsDeletedTagLogic.isTagged(b.id, tagged)) hits++   // 688  ← 判定就计数
    dumpTree(host)                                          // 689
    val tv = timeTextOf(host, b)                            // 690  ← 取不到就 return，但 hits 已经加了
    if (tv == null) { ...; return }                         // 691-698
    ...
}
```

两个独立的失真来源：

1. **判定 ≠ 落笔**（688 vs 690）：`tv == null` 时 692 `missedTime++` 并 return，
   标记根本没画上，但 `hits` 已经 +1。二者必须**联看**才有意义。
2. **每次 paint 都加，不是每条 item 加一次**：`sweep` 每 2 s 对所有 bound 的 View 重画一次（975），
   而 `paint` 无「已画过就不再计」的闸 ⇒ 一条被删动态挂着不动，1 分钟就是 `hits += 30`。
   ⇒ `hits` 既不是「命中条数」也不是「画上条数」，是**累加的 paint 次数**。

真机验收用「`hits 0→1` ⇒ 界面出现标记」其实是**站不住的判据**，只是在本轮恰好成立
（同时 `missedTime=0` 且 `SNS_DELTAG tv …` 日志出现，docs/_r35_sns_tag.md:891/1316）。
本仓库已经因为「计数器恒为 0 / 口径错」栽过一次（docs/_r35_sns_tag.md:1309「坑 2：`tv=` 是坏判据」），
`hits` 是同一类坑的第二个实例。

**我认为正确的判据**（三选一，从省事到严谨）：

- **最省**：把 688 的 `hits++` 挪到 `tv != null` 之后（690 之后），并改名 `painted`。
  这样 `painted>0` 才等价于「至少有一次真的把文本写进了一个找得到的 TextView」。
- **更准**：只在 **707 的 `want != cur` 真正触发 `setText`** 时 +1（即「这次 paint 真的改变了界面」），
  再单独留一个 `alreadyPainted` 计数给「已含 MARK 无需改写」的情况。
- **最直接（推荐，与界面一致）**：别看计数器，看**界面文本** ——
  `SnsDeletedTag.dump()`（1061-1075）已经能打出每个 item 的 `hit=`；再加一列「时间行当前文本」
  （`tv.text`），让「判据 == 用户肉眼看到的东西」。这是 docs 里 R34 那条
  「对着用户能看见的事实写，别对着内部计数器」的直接应用。

---

### 4.2 `strip()` + `appendTag()` 对 `TextView.text` 的读写会不会触发 `TextWatcher` / 引起 `realTimeUpdate` 递归？

**不会递归。严重度 = P2（有幂等闸，但缺重入守卫）。**

- **不存在「hook 重入」**：`SnsDeletedTagLogic` 的 KDoc（SnsDeletedTagLogic.kt:13）担心的是
  「我们自己 `setText` 会再次触发挂在 `setText` 上的钩子」。但本模块**没有 hook `setText`**
  （全文件只有 `XposedBridge.hookMethod` 出现在 195 与 239，分别挂 bind 形状方法与 `setAdapter`）
  ⇒ 这个担心在当前实现里不成立。
- **`TextWatcher` 理论上会被触发**：`tv.text = want`（708）会走 `TextView.setText`，
  微信若在该时间 TextView 上注册了 `TextWatcher`，`afterTextChanged` 会被回调。
  即便如此也不会无限递归 —— 因为 707 有 `if (want != cur)` 幂等闸：第二次进来时 `cur` 已经等于 `want`，
  不会再 `setText` ⇒ 递归深度恒为 1。
- ⚠️ **闸失效的唯一情形**：若微信的 `TextWatcher` 在回调里**又改写了文本**（这正是
  `ImproveTimelineItemMeasure.d()` / realTimeUpdate 的行为，KDoc:961-962 已记），
  则每次 paint 时 `want != cur` 都成立 ⇒ 每次都 `setText` ⇒ 与 realTimeUpdate **交替改写**，
  表现为「标记闪一下就没/忽有忽无」，靠 2 s sweep 补回来。当前真机没报这个现象
  （说明时间行上大概率没有 TextWatcher，或 realTimeUpdate 只重写特定类型的文案）。
- ⚠️ **缺重入守卫**：`SnsTag` 有 `depth` `ThreadLocal` + `runCatching` 双重保护
  （SnsTag.kt:111/240-252），本类的 `bindHook`（267-316）**两者都没有**（详见 §6.3）。
  这是 §6 里最该补的一条。

---

### 4.3 同一 `View` 被连续 `paint` 两次，会不会叠成 `[删除已拦截] [删除已拦截]`？

**不会。三重保险，且能自愈历史残留。严重度 = OK。**

```kotlin
val cur  = tv.text?.toString() ?: ""      // 699
val base = strip(cur)                     // 700  ①剥
val want = if (isTagged(...)) SnsDeletedTagLogic.appendTag(base) else base   // 701-705  ②幂等
if (want != cur) { tv.text = want }       // 707-708  ③同值不写
```

- ① `strip()`（1091-1098）是 `appendTag` 的逆操作：整段等于 MARK 时返回 `""`，
  结尾是 `" $MARK"` 时砍掉 `MARK.length + 1` ⇒ 去掉尾缀**并**去掉那个空格。
- ② `appendTag`（SnsDeletedTagLogic.kt:166-172）先 `trimEnd()` 再判 `if (s.contains(MARK)) return s`
  ⇒ 已经含标记就原样返回，**不会重复追加**。
- ③ `want != cur` ⇒ 文本没变化就不 `setText`，也顺带挡掉无谓重绘。

**自愈性验证**（把各种历史脏状态代入）：

| 当前文本 `cur` | `strip` → `base` | `appendTag(base)` → `want` | 结果 |
|---|---|---|---|
| `10分钟前` | `10分钟前` | `10分钟前 [删除已拦截]` | 正常追加 |
| `10分钟前 [删除已拦截]` | `10分钟前` | `10分钟前 [删除已拦截]` | 幂等，`want == cur`，不写 |
| `10分钟前 [删除已拦截] [删除已拦截]` | `10分钟前 [删除已拦截]` | 原样（`contains` 命中） | **自动收敛成单个** |
| `[删除已拦截]`（整段） | `""` | `[删除已拦截]` | 不产生前导空格 |
| `10分钟前[删除已拦截]`（无空格，别处拼的） | 不变（`endsWith(" $MARK")` 不成立） | 原样（`contains` 命中） | 不叠加，也不补空格 ⇒ 无害 |

**唯一可挑的边角（P2，极低概率）**：`appendTag` 的幂等闸用的是 `contains(MARK)` 而不是 `endsWith`。
若某条动态的时间文案里**天然包含** `[删除已拦截]` 这 8 个字（用户自己发的），
则 `appendTag` 认为「已打标」而**不追加** ⇒ 该条漏标。改 `endsWith` 即可，但收益极小。

---

**§4 小结**：4.1 **P1**（`hits` 不能证「画上了」，真机验收判据站不住）/ 4.2 P2 / 4.3 OK。

---

## 5. `timeTextOf()` 的反射成本与误伤

### 5.1 四级解析各自的触发频率 + 主线程成本定量估算

**先给结论：热路径不掉帧。严重度 = OK（定量见下）。
但「①缓存资源 id」这一级在本机型上是**死代码**（见 5.1.0），所以每帧/每跳都在跑 ②。**

#### 5.1.0 ⚠️ 第 ① 级（`tvId` 缓存）实际永不命中 —— 真机有据

```kotlin
val id = b?.tvId ?: View.NO_ID                                  // 783
if (id != View.NO_ID) { ... }                                    // 784  ← 永不进入
if (tv != null && b != null && tv.id != View.NO_ID) b.tvId = tv.id  // 790  ← 永不成立
```

真机日志（docs/_r35_sns_tag.md:1316）：

```
SNS_DELTAG tv host=f3 cls=ImproveTextView id=ffffffff depth=0
```

`id=ffffffff` = `View.NO_ID` ⇒ 时间控件是**代码 new 出来的 `ImproveTextView`，没有资源 id**
（KDoc:657-658 自己也写了「时间控件是代码 new 出来的」）⇒ 790 的条件恒 false ⇒ `tvId` 恒为 `NO_ID`
⇒ ① 级永不命中，**每次 `paint` 都重走 ②（失败时再走 ③④）**。

连带后果：`tvCached`（1057-1058）恒为 0 ⇒ `stat()` 里的 `tv=` 是**坏判据**
（docs/_r35_sns_tag.md:1309-1318「坑 2」已独立得出同一结论）。这不是正确性问题，但会让下一轮排障走弯路。
**建议**：要么给时间控件补一个 `setId`（风险高，不推荐），要么把 ① 的实现换成「缓存 View 实例本身
（`WeakReference<TextView>`）而不是资源 id」，要么直接删掉 `tvId` 字段和 `tv=` 指标。→ **P2**。

#### 5.1.1 各级触发频率与单次成本

| 级别 | 代码 | 触发条件 | 单次成本（[估算]） |
|---|---|---|---|
| ① `findViewById(tvId)` | 784-788 | **永不**（5.1.0） | — |
| ② `getPublishTimeText()` | 795 → 803-822 | 每次 paint 都先试 | **命中路径**：1 次 `synchronized` HashMap 查（858-860）+ 1 次 `Method.invoke`（806）≈ **2~6 µs** |
| ③ 文本 == `hint` 精确比对 | 796-799 | ② 未命中 且 `hint` 非空 | 全子树 DFS：~30~60 节点 × (HashMap 查 + `getChildAt`)，命中的 TextView 上 `text.toString()` ≈ **30~60 µs** |
| ④ `isTimeLike` 兜底 | 800 | ②③ 都未命中 | 再全子树 DFS 一遍 + 每个 TextView 两次正则（`REL_RE`/`DATE_RE`，SnsDeletedTagLogic.kt:134-137）≈ **60~150 µs** |

**反射调用次数（热路径、缓存预热之后）**：

- ② 命中（真机 `depth=0`，即 item 根类 `f3` 的父类链里有 `getPublishTimeText`）：
  每 paint **1 次 `Method.invoke`**，**0 次 `getDeclaredMethods`**。
- `getDeclaredMethods` 只在某 View 类**首次出现**时跑（856-880，最多 `SUPER_MAX=16` 层 × 1 次），
  结果进 `timeMethodCache`（751）永久复用 ⇒ 一次性。
- 另有 `dataOf`（462-482）的 path 缓存命中后：1 次 `XposedHelpers.getObjectField(a,"data")`（566）
  + 数据对象 `toString()` ×2（`snsIdOf` 86 + `timeHintOf` 129）+ 2 次正则（36 / 122）≈ **10~30 µs / bind**。

#### 5.1.2 每帧 / 每跳估算

| 场景 | 计算 | 占 16.7 ms 帧预算 |
|---|---|---|
| 滚动时新 bind（② 命中） | 1~2 item × (2~6 µs + 10~30 µs) ≈ **15~70 µs/帧** | **0.1%~0.4%** |
| 滚动时新 bind（② 未命中，如 header/广告位） | 1~2 item × (100~200 µs) ≈ **0.1~0.4 ms/帧** | **0.6%~2.4%** |
| sweep 每 2 s（N = `bound.size`） | N=30 × (2~6 µs + 文本比对) ≈ **0.1~0.3 ms/跳** | 单帧 <2% |
| 一次性：`dumpTree`（907-915） | 每根 View 类 1 次：≤120 路径 + `brief(…,3000)` + `LogX` 同步写 ~3 KB ≈ **0.5~2 ms** | 一次性抖动 |
| 一次性：`dumpFields`（618-641） | 每（角色×类）1 次 × 约 5 次（holder + 各候选 item）：2 行 × 最多 40 字段 × 80 字符 ≈ **1~4 ms/次** | 首次进朋友圈共 ~5~15 ms |

**结论**：稳态热路径只有「1 次缓存方法查找 + 1 次反射 invoke + 两次短字符串操作」，
量级是**微秒**，不会掉帧。一次性 dump 有毫秒级抖动但只发生一次，且都被 `treeDumped`/`dumped` 去重（909/620）。

⚠️ 唯一需要盯的量：`WALK_MAX=12`（734）× `WALK_KIDS=16`（737）在 ② 未命中时是
「最多 12 层 × 16 子节点」的 DFS —— 最坏情况是**指数级**的节点数（16^12），
实际被真实 View 树规模（~50 节点）限制住，但**没有节点总数上限**（`collectPaths` 有 `out.size >= 120` 的闸（918），
`walkForGetter`/`walkForText` **没有**）。若将来遇到九宫格 + 长评论的宽深树，会成为热点。→ **P2**。

---

### 5.2 第 ④ 级 `isTimeLike` 兜底误命中的概率、后果与缓存

#### 5.2.1 当前为什么不出事

④ 只在 ②③ 都失败时执行（800）。真机 ② 在 `depth=0` 就命中（item 根类 `f3` 的父类链里有
`getPublishTimeText`）⇒ **对正常动态 item，④ 基本不跑**。这是当前没出事的唯一原因。

#### 5.2.2 误命中反例（`walkForText` 是**先序 DFS**，825-838，返回**第一个**匹配 ⇒ 布局里靠前的控件优先）

`isTimeLike` 的判据是「长度 ≤ 20 且命中 `REL_RE` 或 `DATE_RE`」（SnsDeletedTagLogic.kt:147-151）。
朋友圈 item 里能通过这关的非时间控件：

| 控件 | 文本示例 | 命中哪条 |
|---|---|---|
| **视频时长徽章** | `00:15` / `12:30` | `DATE_RE` = `\d{1,2}:\d{2}`（137） |
| 正文/评论预览（≤20 字） | `昨天我去看了…`、`刚刚到家` | `REL_RE` = `刚刚|\d+\s*(秒\|分钟\|分\|小时\|天\|周\|个月)前\|昨天\|前天`（134） |
| 位置/商品/活动卡片 | `10月29日` | `DATE_RE` = `\d{1,2}\s*月\s*\d{1,2}\s*日` |
| 直播/倒计时 | `03:20` | `DATE_RE` |

`DATE_RE` 的 `\d{1,2}:\d{2}` 是最松的一条 —— **任何 `mm:ss` 形式的时长/倒计时都会命中**。
视频动态在朋友圈里非常普遍 ⇒ 一旦 ② 失效，④ 大概率把标打到**视频时长徽章**上。

#### 5.2.3 后果

把 `[删除已拦截]` 追加到时长徽章/正文上，而真正的时间行没标 ⇒ 用户在时间行看不到标记、
却在别处看到一段灰字 —— 正是「**标记打错位置**」这一最危险的失效形态。

#### 5.2.4 「一旦命中就永久缓存」的路径存在吗？缓存错了怎么办？

- **不存在永久缓存**：因为 `tvId` 恒为 `NO_ID`（5.1.0），790 永远不写 ⇒ **没有任何「记住这次选了谁」的路径**。
  这一点反而是好事：不会把一次偶然的误判固化。
- **但也不会自愈**：正因为没有缓存，每次 paint 都会**确定性地重新犯同一个错**（同样的树、同样的先序
  ⇒ 同样的错误 TextView）。表现为「错了就一直错」，而不是「错一次」。
- **唯一的自愈线路是 787**（`findViewById(tvId)` 返回 null 时清 `tvId`）—— 而这条也永不触发（① 永不进入）
  ⇒ **当前没有任何自愈路径**。

**建议**（按代价排序，任选其一）：
1. 给 ④ 加**形状校验**：只接受类名含 `ImproveTextView`/`PLSysTextView`/`TextView` 且**父布局与时间行同级**
   的候选；或要求候选文本与 ③ 的 `hint` 在「去掉空白后」相等（把 ③ 的精确匹配放宽成 trim 后相等，
   放在 ④ 之前），这样 ④ 只在 `hint` 为空时才兜底。
2. 把 `DATE_RE` 的 `\d{1,2}:\d{2}` 收紧成 `(?<![0-9])\d{1,2}:\d{2}(?![0-9])` 且要求整串长度 ≤ 8
   （时间行不会是 `00:15` 这种只有时分的形态吗？会 —— 但那只在「今天」的动态上出现，可接受）。
3. ④ 命中时打一条**限流日志**（现在完全静默），让「打错位置」在日志里可查。

**严重度 = P1**：当前被 ② 挡住不出事；但 ② 依赖 `getPublishTimeText` 这个方法名（868-870），
一旦 R8 改名（KDoc:761-764 已经记过一次 `lh3/x1 → xh3/o2` 的漂移），② 失效、④ 立刻接棒，
**失效后的形态恰好是最坏的「打错位置」**。

---

### 5.3 `publishTimeMethod` 的负缓存：类还没初始化完会不会永久误判？

**不会。严重度 = OK。**

```kotlin
private fun publishTimeMethod(c: Class<*>): Method? {
    val key = c.name
    synchronized(timeMethodCache) { if (timeMethodCache.containsKey(key)) return timeMethodCache[key] }  // 858-860
    ...
    found = runCatching { cc.declaredMethods.firstOrNull { it.name == "getPublishTimeText" && ... } }.getOrNull()  // 867-872
    ...
    synchronized(timeMethodCache) { timeMethodCache[key] = found }   // 878
}
```

- `Class.getDeclaredMethods()` 会**触发类的加载与链接**（如果还没加载），而方法表在链接完成后就**固定不变**；
  `<clinit>` 是否已执行**不影响** `declaredMethods` 的返回 ⇒ 不存在「初始化一半导致方法看不到」的时序窗口。
- 缓存键是**起始类全名**（857），扫描的是它连同最多 `SUPER_MAX=16` 层父类（864-876）⇒ 同一个类
  无论何时第一次被问，得到的答案都一样 ⇒ **负缓存不会因时序误判**。
- 真正的负缓存风险是**符号漂移**：`getPublishTimeText` 被 R8 改名、或返回类型不再是 `TextView`
  （868-870 同时校验了名字、0 参、返回类型可赋值给 `TextView`）⇒ 该类永久记 `null`。
  但这**不是静默的**：`tv` 取不到会走 ③④，若再取不到就 692 `missedTime++` 并打前 5 条告警日志
  ⇒ `missedTime` 就是这个失效形态的**专门信号**（KDoc:105-106 也是这么设计的）。⇒ **OK**。

两个小瑕疵（**P2**）：
- 861-877 的扫描在 `synchronized` **块外** ⇒ 两个线程可能同时对同一个类做一次全链扫描（幂等，只是浪费）；
  当前只有主线程访问，无影响。
- `timeMethodCache`（751）/ `viewFieldCache`（369）/ `dataPathCache`（432）**永不清空**，
  且 `timeMethodCache` 强引用 `Method` ⇒ 间接钉住 `Class` / `ClassLoader`。微信单 ClassLoader，
  规模也小，实际无害；但如果以后要支持热重载就得清。

---

**§5 小结**：5.1 OK（+①级死代码 P2、DFS 无节点上限 P2）/ 5.2 **P1**（④ 误命中视频时长徽章等，② 一旦漂移就是「打错位置」）/ 5.3 OK（+2 条 P2）。

---

## 6. `hookBinds` 的 shapes 匹配

### 6.1 `hooks=10` 到底挂在哪 10 个地方（从代码 + dex 逐个推导，非猜测）

#### 6.1.1 计数口径

`hooks` **只**在 `ensureInstalled()`（150-165）里被赋值一次：

```kotlin
var n = hookBinds(base)      // 159
n += hookSetAdapter(cl)      // 160
hooks = n                    // 161
```

⇒ `hooks=10` = 「install 时形状匹配的 bind 方法数」+「`setAdapter` 那 1 条」。
之后 `setAdapter` 回调里补挂的条数（244）**不回写 `hooks`**（见 6.3）。

#### 6.1.2 `hookBinds` 扫了哪几层

```kotlin
var cur: Class<*>? = c; var up = 0
while (cur != null && up < 4) {                                     // 185
    if (name.startsWith("java.") || name.startsWith("android.") ||
        name.startsWith("kotlin.") || name.startsWith("dalvik.")) break   // 187-189
    for (m in cur.declaredMethods) { ... }                           // 190
    cur = cur.superclass; up++                                       // 197-198
}
```

`base = com.tencent.mm.view.recyclerview.WxRecyclerAdapter`（64/153）。继承链（dex 实测，
`python _r35_sns_dex.py meth …`，与 docs/_r35_sns_tag.md:46-52 一致）：

| up | 类 | dex | 该层形状匹配数 |
|---|---|---|---|
| 0 | `com.tencent.mm.view.recyclerview.WxRecyclerAdapter` | classes8 | **8** |
| 1 | `Lki4/i0;` | classes | 0（全部被 `hookedSigs` 去重，见 6.1.4） |
| 2 | `com.tencent.mm.ui.recyclerview.SynchronizedAdapter` | classes7 | 0（只有 `onBindViewHolder`，被 205 显式排除） |
| 3 | `Landroidx/recyclerview/widget/w1;`（= RecyclerView.Adapter 本体） | classes12 | **1** |

⚠️ 注意 187 的 break 判定：`"androidx.recyclerview.widget.w1".startsWith("android.")` 是 **false**
（第 7 个字符是 `x` 不是 `.`）⇒ 第 3 层**不会**被跳过，`w1` 确实被扫到了。这一条很隐蔽。

#### 6.1.3 逐个列出这 9 条 bind 钩子

`matchesBindShape`（204-211）的条件：名字 ≠ `onBindViewHolder`（205）、非 static/abstract（206）、
形参 2~3 个（208）、形参[1] == `int`（209）、形参[0] 是 ViewHolder（210 → 214-223：6 层内类名以
`androidx.recyclerview.widget.` 开头）。

**WxRecyclerAdapter（8 条）**：

| # | 方法 | 形参 | 为什么匹配 |
|---|---|---|---|
| 1 | `a1` | `(Lki4/m0; I; Lki4/r;)` | `m0 → l0 → androidx…c3` ✔，p[1]=int ✔ |
| 2 | `b1` | `(Lki4/m0; I; Lki4/r;)` | 同上 |
| 3 | `c1` | `(Lki4/m0; I)` | 同上（dex 证据：`c1` 体内 `iput … m0.E`，docs:138） |
| 4 | `d1` | `(Lki4/m0; I; List)` | 同上 |
| 5 | `i0` | `(Landroidx/…/c3; I; Lki4/r;)` | p[0] 就是 `c3` 本身 ✔ |
| 6 | `k0` | `(Landroidx/…/c3; I; Lki4/r;)` | 同上 |
| 7 | `l0` | `(Landroidx/…/c3; I)` | 同上 |
| 8 | `n0` | `(Landroidx/…/c3; I; List)` | 同上 |

同 dex 里**被正确排除**的邻近方法（逐条核对过 `WxRecyclerAdapter` 的全部 42 个声明方法）：
`I0(c3;Z)`（p[1] 是 boolean）、`K0(c3)`（1 参）、`g1(View;Lki4/c;I)`（p[0] 是 View）、
`e1(ViewGroup;I)`/`o0(ViewGroup;I;Lki4/r;)`/`t0`/`x0`（p[0] 是 ViewGroup）、
`p1(m0)`/`m1(RecyclerView)`（1 参）、`f1(WxRecyclerAdapter)`（static + 1 参）、
`onBindViewHolder` 不存在于本类。

**androidx `w1`（1 条）**：

| # | 方法 | 形参 | 备注 |
|---|---|---|---|
| 9 | `bindViewHolder` | `(Landroidx/…/c3; I)` | ⚠️ **框架层公共入口** —— 全应用所有 `RecyclerView.Adapter` 都会经过它 |

**第 10 条**：`hookSetAdapter`（229-250）→ `WxRecyclerView.setAdapter`（253-263 找，最多 6 层）。

**8 + 1 + 1 = 10**，与真机 `SNS_DELTAG installed hooks=10`（docs/_r35_sns_tag.md:633/908/1165）**完全对上**。

#### 6.1.4 为什么是 10 而不是文档预期的 8（顺带纠正一处文档错误）

- `hookedSigs` 的 key 是 `"${m.name}(${形参})"`（192），**不含 `declaringClass`**。
  `ki4/i0` 也声明了 `i0/k0/l0/n0`，但 up=0 时 `WxRecyclerAdapter` 已经把这四个 key 占住 ⇒ 193 去重跳过。
- 文档 docs/_r35_sns_tag.md:171 写「`i0` 分支（`WxRecyclerAdapter` 未覆写）覆盖不到」—— **与 dex 不符**，
  `WxRecyclerAdapter` 明确声明了 `i0(Landroidx/…/c3; I; Lki4/r;)V`（`_r35_sns_dex.py meth view/recyclerview/WxRecyclerAdapter` 可见）。
- 文档 §S3（docs:336-337）预期 `+7` / `hooks=8` ⇒ 少算了两处：上面这条 `i0`，以及
  **`w1.bindViewHolder`**（第 9 条）。多出来的是**覆盖变全**，不是坏事，但文档结论要改，否则下一轮
  会把 `hooks=10` 当异常。

---

### 6.2 形状匹配会不会误挂到非朋友圈 adapter？`SNS_PREFIX` 在哪一层，够不够？

**不会误「打标」，但会误「进入」—— 前缀过滤在回调层。严重度 = P2。**

- **钩子确实挂在了共享基类上**：`WxRecyclerAdapter` 是微信所有 RecyclerView adapter 的基类，
  而第 9 条甚至挂在 `androidx` 的 `RecyclerView.Adapter.bindViewHolder` 上
  ⇒ **全微信（乃至全进程）所有 RecyclerView 都会进入 `bindHook`**（268 的 `binds++` 因此统计的是全量）。
- **过滤点**：
  - 安装层：`hookSetAdapter` 的回调里，`a.javaClass.name.startsWith(SNS_PREFIX)`（243）才 `hookBinds`；
  - 回调层：`bindHook` 里 `adapter.javaClass.name.startsWith(SNS_PREFIX)`（272）才 `snsBinds++` 并继续。
  - `SNS_PREFIX = "com.tencent.mm.plugin.sns."`（70）。
- **够不够**：
  - 防「打错标」——**够**。`param.thisObject` 是 adapter 实例，取其类名的前缀，与 docs:107-108 的
    dex 事实（朋友圈 adapter = `com.tencent.mm.plugin.sns.ui.improve.component.u1`，前缀不被误杀）一致。
  - 防「白跑」——**不够窄**。所有 `com.tencent.mm.plugin.sns.*` 的 adapter（相册网格、评论列表、
    图片选择器、SNS 设置页……）都会完整跑一遍 `dataOf`（462-482）⇒ 首次进入时触发
    `probePath`（485-495）的全量字段反射探测 + `dumpFields`（618-641）的两行日志，
    每个 holder 类最多 `PROBE_MAX=12` 次（420/572-576）⇒ 每个这类页面一次 ~5~15 ms 的抖动。
    ⇒ **P2**（功能正确，只是付了不该付的代价）。
- **形状本身够不够紧**：`p[0] 是 ViewHolder 且 p[1] 是 int` 这个形状**不唯一**。
  本版 dex 上恰好只有 bind 类方法满足（已逐个核对，见 6.1.3），但将来若出现
  `onViewAttachedToWindow(holder, pos)` / `xxx(holder, position)` 这类非渲染方法，
  就会被误挂 ⇒ 在错误时机读 holder 数据。⇒ **P2**。

---

### 6.3 重复挂载保护？`install` 调两次会怎样？

**`install` 调两次是安全的；但有三处结构性缺口。**

#### 6.3.1 `install` 幂等 —— **OK**

```kotlin
private fun ensureInstalled(cl: ClassLoader): Int {
    if (installed.get()) return 0                       // 151
    val base = runCatching { findClass(ADAPTER_CLASS, cl) }.getOrNull()
    if (base == null) { LogX.w(...); return 0 }         // 153-157
    if (!installed.compareAndSet(false, true)) return 0 // 158
    ...
}
```

`installed`（87）是 `AtomicBoolean` + CAS ⇒ `install()`（145-147）调第二次直接返回 0，不会重复挂。
153 的「先确认能找到基类再占位」也是对的（找不到类时不浪费一次性安装机会）。
另 `hookedSigs`（167）提供签名级去重（193），`setAdapter` 回调里对同一 adapter 类重复触发
`hookBinds` 也不会重复挂。**OK**。

#### 6.3.2 ⚠️ `hookedSigs` 去重键不含 `declaringClass` ⇒ 「子类覆写」这个病根**没有真正修掉** —— **P1**

```kotlin
val key = "${m.name}(${m.parameterTypes.joinToString { p -> p.name }})"   // 192
if (!hookedSigs.add(key)) continue                                        // 193
```

install 时 `WxRecyclerAdapter` 已经把 8 个签名注册进 `hookedSigs`。
之后 `setAdapter` 钩子给**具体** adapter 类补挂时（244 → `hookBinds(a.javaClass)`），
若该子类**覆写**了 `c1/d1/a1/b1/i0/k0/l0/n0`，其 key 与基类的完全相同 ⇒ 193 直接 `continue`
⇒ **子类覆写版根本挂不上**。

而 KDoc:225-227 明确写着「子类一旦覆写 `c1/d1/…`，只挂基类就会**静默失效**（这正是 R35 的病根形态）
→ 抓 `setAdapter` 拿到具体 adapter 类再补挂」—— **这个补救措施被 192 的去重键废掉了**。

对比 `SnsTag.hookedMethods`（SnsTag.kt:160）：

```kotlin
val key = "${m.declaringClass.name}#${m.name}(${...})"   // ← 带了 declaringClass
```

⇒ 本类是**退化**的。当前不出事，只因为真机的 `u1` 没覆写这些方法（docs:107「`u1` 只声明
`<init>` 与 `g(Lju2/n0;)V`」）。修法：把 192 的 key 加上 `m.declaringClass.name`。

#### 6.3.3 ⚠️ `bindHook` 的 `afterHookedMethod` 整体没有 try/catch —— **P1（本轮最该补的一条）**

`bindHook`（267-316）的整个方法体**没有任何 `runCatching`**，也没有 `SnsTag` 那种
`depth` `ThreadLocal` 重入守卫（对比 SnsTag.kt:111/240-252：既有 `runCatching { onBind(param) }`
又有 `finally { depth }`）。

而第 9 条钩子挂在 `androidx.recyclerview.widget.w1.bindViewHolder` 上 —— 这是**全应用所有
RecyclerView 的公共入口**，任何从这里逃逸的 Throwable 都会顺着 RecyclerView 的布局流程冒泡
⇒ **微信崩溃**。

当前为什么没崩：`bindHook` 体内的高风险点各自有 `runCatching` 包着（195/245/351/663/785/806/837 等），
`dataOf`/`itemViewOf`/`timeHintOf` 内部也逐字段 `runCatching`（392/513/522/551/630/893-894）。
但**边界之外**仍有未覆盖的：`binds++`（269）、`param.thisObject`（271）、
`adapter.javaClass.name.startsWith`（272）、`bound[view]`（302/306，WeakHashMap 的 hashCode）、
`Binding(id)`（304）、`ensureSweep()`（309，内含 `synchronized`）、
`paint` 里的 `tv.text = want`（708）、`LogX.i("SNS_DELTAG stat ${stat()}")`（313，内含 `on()` → SQLite）。
这些都是「通常不抛」，但**没有任何一层兜底**。

**建议**（一行）：照抄 SnsTag 的写法，把 268-315 整体包进 `runCatching { … }.onFailure { LogX.w(...) }`，
并加一个 `depth` ThreadLocal 防真递归。成本极低，收益是把「模块异常」和「微信崩溃」彻底隔离。

#### 6.3.4 ⚠️ `hooks` 不反映补挂结果 —— **P2**

`hooks` 只在 161 赋值；`setAdapter` 回调里后续补挂的条数（244）**没有回写**
⇒ `stat()`（1053）里的 `hooks=10` 永远是 install 时的数字，无法用来判断「具体 adapter 补挂了没」。
另外 `installed` 一旦为 true 就不再重试：若 `hookBinds` 返回 0（形状全不匹配），
`hooks=0` 只打一条 warn（163），之后**进程内永久不再尝试** —— 版本漂移时就是「永久静默失效」。

---

**§6 小结**：6.1 OK（10 条已逐个落到 dex，含一处文档纠错）/ 6.2 P2 / 6.3.1 OK、6.3.2 **P1**、6.3.3 **P1**、6.3.4 P2。

---

## 7. 与 `SnsTag.kt` 老链路的耦合

先明确两条链的**调用时序**（推导自 dex 的 `ki4/i0.onBindViewHolder` 体内
`0x0026 k0 → b1`、`0x0051 i0 → a1`、`0x005b l0 → c1`，docs/_r35_sns_tag.md:70-76）：

```
一次 bind 内：
  ① a1/b1/c1 … 的 after-hook   → 本类 bindHook（267）       ← 本类先跑
  ② ki4/i0.onBindViewHolder 返回 → SnsTag.bindHook（SnsTag.kt:255）→ apply → 本类 apply（673）
  ③ w1.bindViewHolder 返回      → 本类 bindHook（第 9 条钩子）  ← 本类最后再跑一次
加两条 2 s 周期的 sweep：SnsTag.sweep（SnsTag.kt:561）与本类 sweep（966），都在主线程。
```

---

### 7.1 两条链路同时 paint 同一个 View，会不会互相擦除？

#### 7.1.1 正常（不含探针）：**不会**。严重度 = OK

两条链的判据数据源**是同一个**：`SnsTag.deleted()` → `db.snsTagIds()`（SnsTag.kt:646），
本类 `deleted()` → `db.snsTagIds()`（1036）⇒ 同一张 `T_SNS`、同一批 id。
`SnsTag` 传给 `apply` 的是 `del + forceSet`（SnsTag.kt:673），`forceSet` 正常恒空（659）
⇒ 两边判定结果一致，而 `paint` 是幂等的（§4.3）⇒ 谁先谁后都一样，**不会互相擦除**。

#### 7.1.2 ⚠️ 会擦的场景 1：探针 `forceSet` —— **P1**

`snstag fake` / `mark` / `unmark`（EntryReceiver.kt:605-606）设的是 **SnsTag 的 `forceSet`**
（SnsTag.kt:659/681/689/704），而 **`SnsDeletedTag` 没有 `forceSet` 这个概念**：

- `SnsTag.sweep`（573）传 `del + forceSet` ⇒ 贴标；
- 本类 `sweep`（969）传 `deleted()`（**不含 forceSet**）⇒ 判定「未删」⇒ `strip` ⇒ **剥标**；
- 下一跳 `SnsTag.sweep` 又贴回来 ⇒ **2 秒周期的闪烁**，稳态取决于两条 sweep 的相位。

真机「`snstag fake` 后界面出现标记」与这个竞态**不矛盾**：抓拍时刻恰好落在「贴着」那一侧；
`unmark` 只是让 `SnsTag` 也不再贴 ⇒ 干净消失。
⇒ **真机结论「功能跑通」不能证明「没有擦除竞态」** —— 这两件事是正交的。

#### 7.1.3 ⚠️ 会擦的场景 2（更本质）：两条链解析出的 id 不一致 ⇒ 还会**污染**本类 `bound` —— **P1**

`apply()` 在 `snsId != 0` 时会**用调用方给的 id 覆写本类的 `bound`**：

```kotlin
if (snsId != 0L) {                                       // 666
    val prev = bound[itemView]                           // 667
    val b = if (prev != null && prev.id == snsId) prev else {
        Binding(snsId).also { bound[itemView] = it }     // 668-670  ← 覆写
    }
    paint(itemView, b, tagged)                           // 671
    return
}
```

而在上面的时序里，**`SnsTag` 那一步（②）跑在本类 `c1/d1` 之后、`bindViewHolder`（③）之前**
⇒ 每次 bind 的「最后写入者」是 `SnsTag`。于是：

- 若两边 id **一致** ⇒ `prev.id == snsId` ⇒ 复用本类的 `Binding`（保住 `tvId`/`hint`）⇒ 无害；
- 若两边 id **不一致** ⇒ 本类那条正确解析出来的 `Binding` 被 `SnsTag` 的 id **整体替换**（669），
  且新 `Binding` 的 `tvId = NO_ID`、`hint = ""`（`Binding` 只在 305/360 由本类自己填 `hint`）⇒
  **本类自己的 sweep 从此按 `SnsTag` 的 id 判定**，第 ③ 级解析（`hint` 精确比对）也永久失效。

`SnsTag` 的 id 来自 `resolve()`（「`getData()` 下标 ±2 窗口 + 作者名对账」，
docs/_r35_sns_tag.md:1358「偏移校准是**猜**的」），本类的 id 来自 holder/数据本体
⇒ **两者不一致是有可能的**，一旦发生就是「错标 / 漏标 / 标记打在邻居那条上」。

**可操作的判据**（一轮 adb 就能定性，本轮不做）：
同时打 `snstag dump`（SnsTag.kt:712，打印 `bound` 里每个 View 的 id）和本类 `dump()`（1061，
同样按 View 打印 `u=`），对同一个 `host=` 比对两边 id：
- 全等 ⇒ 当前没有污染（真机 `hits 0→1` 且落点正确，倾向于这一种）；
- 有不等 ⇒ 机制 7.1.3 正在生效，必须让 `apply()` 不再覆写本类 `bound`
  （例如：`snsId != 0` 时若 `bound[view]` 已有 id 且与传入 id 不同，**以本类为准**，只 `paint` 不写 `bound`）。

---

### 7.2 `apply(itemView, snsId=0, …)` 用本类 `bound` 兜底，什么时候会把上一个 item 的标记留在复用的 View 上？

**会。窗口 = 「View 已复用给新 item，但 `bound[view]` 还是旧 `Binding` 且本类这一拍没解析出新 id」。严重度 = P1。**

```kotlin
if (!on()) { restore(itemView); return }        // 674-677
val own = bound[itemView]                       // 678  ← 旧 Binding
if (own == null || own.id == 0L) { restore(itemView); return }   // 679-682
paint(itemView, own, deleted())                 // 683  ← 按**旧 id** 重新判定并画
```

触发条件（必须同时满足）：

1. View 被 RecyclerView 复用给另一条动态；
2. `bound[view]` 里还是**上一条**的 `Binding`（`bound` **没有任何复用失效机制**：
   只在解析成功时被替换（306/360/669），从不因 `onViewRecycled`/`pos` 变化而失效）；
3. 本类这一拍**没解析出新 id**（`bindHook` 的 `id == null` 分支 290-300，或本类钩子没挂上）；
4. 旧 id 命中已删清单。

⇒ 结果：**上一条动态的 `[删除已拦截]` 留在新的这条动态的 View 上**（串味）。

为什么当前没事 / 为什么窗口很短：
- 条件 3 通常不成立 —— 本类自己的钩子每次 bind 都跑（`resolved=40 missed=0`），同帧内就把 `bound` 换对了；
- 但 `retryResolve`（348-352）的存在恰恰说明「先跑的那几条 hook 拿不到数据」是常态
  （KDoc:337-342），而 `SnsTag` 的 `resolve` 在同样时机也会失败 ⇒ 两条链都传 0 ⇒ 都走兜底
  ⇒ **快速滚动时确实有「旧标记短暂串到新 item」的窗口**（≤1 帧 ~ 下次 2 s sweep）。

**这是 R35-1c 一次改动的已知代价**：KDoc:293-296 说明了为什么把「无条件 restore」改成
「只有 `bound[view] == null` 才 restore」（297）—— 无条件 restore 会把 `c1` 刚打上的标擦掉，
表现为「标记闪烁/没有」。改成有条件后换来的就是**复用残留窗口**。取舍本身合理，
但**缺配套的复用失效标记**：建议给 `Binding` 加一个 `pos`/`bindSeq` 字段，
或在 `bindHook` 里发现 `bound[view]` 的 id 与新解析出的 id 不同时先 `restore` 再画，
把「复用」变成一个显式可判的事件。

另外补一刀：`restore()`（713-718）在 714 用 `bound[host]` 作 hint 源，若 `bound[host] == null`
则 `b = null` ⇒ `hint` 为空 ⇒ 第 ③ 级失效，只剩 ②④；`restore` 只做剥标，选错控件也只是
「从一个本来就没有标记的 TextView 上剥了个不存在的标记」，**无害**。

---

### 7.3 摘掉老链路（`SnsTag` 不再调 `SnsDeletedTag.apply`）后，本类能不能独立工作？缺什么？

**核心链路能独立工作（OK），但要保持「可验证 + 可诊断」，必须补 5 项。**

**能独立的部分**（本类自持，不依赖 `SnsTag`）：
- 挂载点：10 条钩子（§6.1），真机 `resolved=40 missed=0`；
- 判定数据：自己的 `deleted()`/`loadIds()`（995-1039）；
- 定时重画：自己的 `sweep`（966-977）+ `ensureSweep`（948-953）；
- 落笔：`paint`（687-710）+ `Logic` 纯函数。
⇒ 即使 `HookEntry.kt:249-251` 的 `SnsTag.install` 整段删掉，本类照样能画。

**缺的 5 项（摘之前必须补）**：

| # | 缺什么 | 依据 | 不补的后果 |
|---|---|---|---|
| 1 | **外部注入集合（等价 `forceSet`）** | `forceSet` 在 `SnsTag`（659）；探针 `snstag fake/mark/unmark`（EntryReceiver.kt:605-606）只调 `SnsTag` | 摘掉后**真机验证手段全部失效**（没法不删动态就证明链路通） |
| 2 | **`apply()` 里没有 `ensureSweep()`** | 660-684 全程不 arm sweep；arm 只在 309/363 | 本类解析失败（`bound` 空）时 sweep 永不启动 ⇒ 「删掉一条动态后要等下一次 bind 才出现标记」（现在靠 `SnsTag.sweep` 兜着） |
| 3 | **`invalidateIds()`** | `SnsTag` 有（596）且被广播调用（EntryReceiver.kt:523）；本类没有 | 外部改库后「清完还挂着标记」最多 5 s（`SET_TTL_MS`，73） |
| 4 | **探针出口接的是 `SnsTag`** | EntryReceiver.kt:602-606 只接 `SnsTag.stat/dump/mark/unmark` | 本类的 `dump()`（1061-1075）目前是**死代码**；`stat()` 只在 313 内部打日志 |
| 5 | **`missedTimeCount()` 的消费方在 `SnsTag`** | 106 定义，SnsTag.kt:744 使用 | 摘掉后要改由本类 `stat()`（1053，已有 `missedTime=`）直接对外输出 |

**建议的摘法**：不要一步摘。先做「`SnsTag` 侧改成只做判定、把 `apply` 换成 `SnsDeletedTag.apply(view, id, tagged)` 的**只传不清**版本」
（即 7.1.3 的修法：不覆写本类 `bound`），跑一轮真机确认 `hits`/落点不变，再摘。

---

**§7 小结**：7.1.1 OK / 7.1.2 **P1** / 7.1.3 **P1**（`apply` 会用 `SnsTag` 的 id 覆写本类 `bound`，这是两条链唯一的双向耦合点）/ 7.2 **P1** / 7.3 OK（需补 5 项）。

---

## 8. `SnsDeletedTagLogic.kt` 的纯函数

> 下列正则/边界结论均用临时 `python -c` 模拟核对过（Java/Kotlin 的 `\d` 是 ASCII `[0-9]`，
> 模拟时用 `[0-9]` 保持一致），**未落任何文件到仓库**。

### 8.1 `snsIdOf` 的形状校验会不会误收别的数字字段？

#### 8.1.1 校验实际比 KDoc 描述更严 —— **先纠正口径**

KDoc（SnsDeletedTagLogic.kt:92-93）写「必须是**纯数字**（允许负号）」，容易让人以为
「任意 15~20 位数字都会被收」。实际判据是 `ID_RE`（36）：

```kotlin
private val ID_RE = Regex("""snsID\s*=\s*'?(-?\d{15,20})""")   // 36
```

**必须带 `snsID=`（或 `snsID = ` / `snsID='`）前缀** ⇒ `rowid`、`createTime`、时间戳、
手机号、评论 id 这类裸数字字段**全部被排除**。这一层比 KDoc 说的强得多，是本文件最有效的一道闸。
模拟验证：`rowid=12345 snsID=15033729556253184615` ⇒ 抓到 `15033729556253184615`（正确）；
`ImproveInfo(name=x, time:刚刚)`（无 `snsID=`）⇒ `null`（正确）。

#### 8.1.2 仍会误收的反例（按「会不会真的发生」排序）

| # | 反例 | 机制 | 现实概率 | 后果 |
|---|---|---|---|---|
| **A** | **字段里嵌了另一条动态**：`unwrap`（503-524）扫字段值的 `toString()`，若某字段是转发源 / 广告 `sourceInfo` / 评论携带的父动态，其 `toString()` 里的 `snsID=` 属于**另一条** | `probePath`（485-495）一旦 `snsIdOf(v)` 非 null 就把该 path **固化进 `dataPathCache`**（479） | **低** —— 因为候选顺序是 `@`（487）优先，而 `@` = `WxRecyclerAdapter.data[pos]`（562-570）= 当前 item 本体，它自己的 `toString()` 里 `snsID=` 就是自己的 id ⇒ `@` 几乎必然先命中，`unwrap` 根本跑不到（真机 `paths=1` 与之一致） | 若发生：**每条动态都读成邻居的 id** ⇒ 打错位置 |
| **B** | **超长数字串被截断**：`snsID=12345678901234567890123`（23 位）⇒ 贪婪 `\d{15,20}` 取前 20 位 `12345678901234567890` ⇒ `parseSnsId` length=20 通过 | 正则没有「后面不能再是数字」的边界（`(?![0-9])`） | **极低** —— 真实 snsId ≤ 20 位，不会出现 21+ 位连续数字紧跟 `snsID=` | 静默错 id |
| **C** | **15 位全零**：`snsID=000000000000000` ⇒ `parseSnsId` 返回 `0` | 96 不拦长度 15 的全零 | **极低** | 不误标（`isTagged(0,…)` 恒 false，63-64；`taggedIds` 也过滤 0，50-52），但会占住 `dataPathCache` ⇒ 该 holder 类**永久解析成 0 = 永不打标**（静默失效） |
| **D** | **15~20 位的其它业务 id 恰好被标成 `snsID=`** | 前缀约束 | **无**（没有这种字段） | — |

负面长度边界模拟结果（均与预期一致）：
`snsID=-15033729556253184615` ⇒ group 长度 **21** ⇒ `parseSnsId`（96）`raw.length > 20` ⇒ **null**（安全，不会截成 20 位）；
`snsID=-3403583826779106808`（20 字符）⇒ 走 `toLong()`（108）⇒ 有符号值，与 SQLite 位模式一致（正确）。

**严重度 = P2**（前缀约束已经很强；唯一有现实可能的是 A，而 A 被「`@` 优先」挡住）。

---

### 8.2 `isTagged` / `appendTag` / `parseSnsId` / `brief` 的边界 bug？

#### 8.2.1 `isTagged`（63-64）—— **OK**

```kotlin
fun isTagged(snsId: Long, tagged: Set<Long>): Boolean = snsId != 0L && snsId in tagged
```

- `0` ⇒ 恒 false ✓（KDoc:60-61 与 `SnsTagLogic` 同口径）；
- **负数** ⇒ 正常参与 `in` 比较。位模式一致性：`GroupDb.snsTagIds()` 用 `c.getLong(i)`（GroupDb.kt:543，
  **有符号**），`parseSnsId` 正数走 `parseUnsignedLong`、负数走 `toLong`（108）⇒ 两边都是同一个
  64 位模式的两种写法 ⇒ **匹配正确** ✓；
- 空 set ⇒ false ✓；`tagged` 为 `emptySet()`（969 开关关闭路径）⇒ 全 false ⇒ 全摘 ✓。

#### 8.2.2 `appendTag`（166-172）—— **OK（一条 P2 边角）**

| 输入 | 行为 | 结论 |
|---|---|---|
| `null` / `""` / `"   "` | `trimEnd()` → `""` → 返回 `MARK`（168），**无前导空格** | ✓ |
| `"昨天   "`（尾空格） | `trimEnd()` → 单空格拼接 | ✓ |
| `"10分钟前 [删除已拦截]"` | `contains(MARK)` ⇒ **原样返回**（170）| ✓ 幂等 |
| `"10分钟前[删除已拦截]"`（无空格） | 原样返回 | ✓ 不叠加 |
| 超长原文 | 直接拼，无截断 | 时间行本来就短，✓ |

⚠️ **唯一边角（P2）**：幂等闸用 `contains(MARK)` 而不是 `endsWith(MARK)`。
若某条动态的时间文案里**天然包含** `[删除已拦截]` 这 8 个字（用户自己发的内容），
`appendTag` 会误认为「已打标」而**不追加** ⇒ 该条漏标。概率极低，改成 `endsWith` 即可。

#### 8.2.3 `parseSnsId`（95-110）—— **OK（一条 P2 边角）**

逐边界核对：

| 输入 | 行号 | 结果 |
|---|---|---|
| `""` | 96 | `null` ✓ |
| 长度 > 20（如 21 位） | 96 | `null` ✓ |
| `"-"` | 99-100 | `null` ✓ |
| 含非数字（空格/`+`/字母） | 102-104 | `null` ✓ |
| `"0"` | — | 返回 `0`（合法；`isTagged` 会挡住）✓ |
| `"18446744073709551615"`（20 位，`2^64-1`） | 108 `parseUnsignedLong` | 成功 ✓ |
| `"18446744073709551616"`（> `2^64-1`） | 107-109 `runCatching` | 抛 ⇒ `null` ✓ |
| `"-9223372036854775808"`（`Long.MIN`） | 108 `toLong()` | 成功 ✓ |
| `"-9223372036854775809"` | 108 | 抛 ⇒ `null` ✓ |

⚠️ **P2 边角**：负号路径用 `raw.toLong()`，会接受前导零（`-0000000000000000001` ⇒ `-1`）。
语义上是「畸形但可解析」，与 8.1.2-C 同类，概率极低。

#### 8.2.4 `brief`（71-74）—— **OK（一条 P2 边角）**

```kotlin
fun brief(s: String?, max: Int = 80): String {
    val t = s ?: "null"
    return if (t.length <= max) t else t.substring(0, max) + "..."
}
```

- `null` ⇒ `"null"` ✓；短串 ⇒ 原样 ✓；长串 ⇒ 截断 + `"..."` ✓。
- ⚠️ `max < 0` ⇒ `substring(0, max)` 抛 `StringIndexOutOfBoundsException`。
  三个调用点传的都是**常量正数**：633（默认 80）、920（`12`）、914（`3000`）⇒ **当前打不到**。
  `max = 0` ⇒ 返回 `"..."`（长度 3 > 0，无害）。⇒ **P2**（加一句 `max.coerceAtLeast(0)` 即可）。

#### 8.2.5 附带两条 —— **P2**

- **`taggedIds`（48-55）在生产代码里没有调用点**（只有 `SnsDeletedTagLogicTest.kt:83/89/100` 用到）。
  生产侧 `deleted()` 直接用 `db.snsTagIds()` 返回的 `Set<Long>`（1036），`0` 已被
  `isTagged`（63）挡住 ⇒ 逻辑不缺，但这个函数是**死代码**，且它的 KDoc（44-46）里那条
  「⛔ 绝不能传 `listSrcFixedIds()`」的警告因此**不会在调用点被看到**（警告挂在死代码上）。
  建议：要么在 `loadIds()` 里真的用它包一层（让 R31h 事故不再重演），要么把那段警告挪到 `loadIds`。
- **`timeHintOf`（127-131）用 `time:(\S+)` 取第一个匹配**：若数据对象 `toString()` 里
  `time:` 出现在**更早的嵌套对象**中，会取到错的 hint ⇒ 第 ③ 级（`walkForText` 精确比对，796-799）
  失配 ⇒ 退化到第 ④ 级。后果是**降级不是错标**，且 ② 命中时 ③ 根本不跑 ⇒ 影响很小。

---

**§8 小结**：8.1 P2 / 8.2.1 OK / 8.2.2 OK（+1 条 P2）/ 8.2.3 OK（+1 条 P2）/ 8.2.4 OK（+1 条 P2）/ 8.2.5 P2。
**没有找到会导致「打错标签」的纯函数 bug** —— 形状校验（`snsID=` 前缀 + 15~20 位）确实如 KDoc 所说
是最后一道有效闸门。

---

## 附：本轮 P0/P1 清单（按建议修复顺序）

| # | 严重度 | 位置 | 一句话 |
|---|---|---|---|
| 1 | **P1** | SnsDeletedTag.kt:267-316 | `bindHook` 的 `afterHookedMethod` 无 try/catch，且第 9 条钩子挂在 `androidx` 的 `RecyclerView.Adapter.bindViewHolder`（全应用公共入口）⇒ 任何逃逸 Throwable = 崩微信 |
| 2 | **P1** | SnsDeletedTag.kt:192 | `hookedSigs` 去重键不含 `declaringClass` ⇒ 子类覆写 `c1/d1/...` 时补挂被静默跳过，R35 病根没真正修掉 |
| 3 | **P1** | SnsDeletedTag.kt:1009-1020 | `Thread{...}.start()` 在 `try/finally` 之外 ⇒ 线程创建失败时 `setLoading` 永久卡死 ⇒ 已删清单永久不刷新（SnsTag.kt:616-628 同缺陷） |
| 4 | **P1** | SnsDeletedTag.kt:667-670 | `apply()` 会用 `SnsTag` 的 id 覆写本类 `bound` ⇒ 老链路解析错 id 时会污染本类判定 |
| 5 | **P1** | SnsDeletedTag.kt:800 / Logic:147-151 | 第 ④ 级 `isTimeLike` 会误命中视频时长徽章 `00:15` / 正文「昨天…」；② 一旦因符号漂移失效，④ 接棒 ⇒ 标记打错位置 |
| 6 | **P1** | SnsDeletedTag.kt:678-683 | `snsId=0` 兜底用旧 `Binding` 重画；`bound` 无复用失效机制 ⇒ 复用时可能把上一条的标记留在新的 item 上 |
| 7 | **P1** | SnsDeletedTag.kt:688 | `hits` 是「判定命中的 paint 次数」不是「画上了几条」⇒ 真机验收判据站不住 |
| 8 | **P1** | SnsDeletedTag.kt:969 / SnsTag.kt:567 | `SnsTag` 的 `forceSet` 本类没有 ⇒ 探针期间两条 sweep 每 2 s 互相贴/剥 ⇒ 闪烁 |

**没有发现 P0**（没有任何一条在当前真机状态下会导致功能静默失效 / 崩微信 / 标打错位置）。

[DONE]

---

## 修复记录（R35-6）

改动只落在两个文件：`feature/SnsDeletedTag.kt`、`feature/SnsDeletedTagLogic.kt`。
行号以改动**后**的文件为准。所有原有诊断日志与计数（`binds/sns/resolved/missed/hits/missedTime/lastMiss/paths/tv`）一律保留，只加不删。

### #1 `bindHook` 的 `afterHookedMethod` 无 try/catch（崩微信风险）

- **改了什么**：原来的整个回调体抽成 `private fun onBind(param)`（353），
  `afterHookedMethod`（339-349）只剩三件事：`binds++`（**在 try 之外**，保证「钩子活着吗」这个
  计数不受异常影响）→ `try { onBind(param) } catch (t: Throwable) { warnOnce(...) }`（345）。
  catch 只记一条限流告警，**绝不 rethrow**。
- **改在哪个行号**：`SnsDeletedTag.kt:320-350`（KDoc 说明为什么必须兜）+ 353-397（`onBind` 本体）。
  另新增 `warnOnce()`（117-125）与 `errLogged` 计数（104）—— 与 `logOnce` 分开计额度，
  否则 holder/pick 那些一次性 dump 会把 5 条额度吃光，异常告警反而一条看不见。
- **同一文件其它 hook 回调**：只有 `hookSetAdapter` 里的 `afterHookedMethod`（约 295），
  它从一开始就自带 `runCatching { … }.onFailure { … }`，不需要再包；`sweeper`（1088）同样已包。
- **判据**：
  ① 静态 —— `bindHook` 的 `afterHookedMethod` 里不存在任何向 `XC_MethodHook` 之外抛出的路径
  （`catch (t: Throwable)` 是最外层）；
  ② 计数不受影响 —— 人为让 `onBind` 中途抛异常（例如 `paint` 里 `tv.text` 抛），
  `binds` 仍 +1、`missed++` / `lastMiss=` 仍生效（这两句在抛点之前），日志出现
  `SNS_DELTAG bind err: <异常类名>` 而**微信不崩**；
  ③ 真机 —— `grep "SNS_DELTAG bind err"` 为空 = 没触发过；`binds` 仍随滚动增长。

### #2 `hookedSigs` 去重键不含 `declaringClass`

- **改了什么**：键从 `"${m.name}(形参)"` 改成
  `"${m.declaringClass.name}#${m.name}(形参)"`（245），与 `SnsTag.hookedMethods`（SnsTag.kt:160）同口径。
- **改在哪个行号**：`SnsDeletedTag.kt:237-245`（含为什么必须带 `declaringClass` 的注释）。
- **幂等性没坏**：`install()` 两次仍由 `ensureInstalled` 的 `installed` CAS（205）挡住，第二次直接 return 0；
  即使绕过它再进 `hookBinds`，同一个「类#方法(形参)」在 `hookedSigs` 里也只会 add 成功一次 ⇒ 不会挂两遍。
- **判据**：`SNS_DELTAG installed hooks=N` 的 N 不因 `install()` 被调用两次而翻倍；
  子类覆写 `c1(m0,I)` 的具体 adapter 类被 `setAdapter` 抓到时，日志出现
  `SNS_DELTAG hooked adapter=<子类> +1`（改之前这条恒为 0 / 不出现）。

### #3 `Thread{...}.start()` 在 `try/finally` 之外

- **改了什么**：`Thread { … }.apply{…}.start()` 整体包进 `try/catch (t: Throwable)`（1198-1217），
  catch 里 **必须** `setLoading.set(false)` + 一条 `warnOnce`；线程体内的
  `finally { setLoading.set(false) }` 原样保留（它防的是体内异常，两层不是一回事）。
- **改在哪个行号**：`SnsDeletedTag.kt:1194-1217`。
- **判据**：把 `start()` 换成必抛的实现（或在单测里 `mock` 线程创建失败）⇒
  `setLoading` 立即回到 `false`、日志出现 `SNS_DELTAG: id refresh thread failed: …`，
  下一拍 `deleted()` 能再次进刷新分支（否则 `delSet` 永远停在旧值、`setAt` 不再更新）。
  ⚠️ `SnsTag.kt:616-628` 有同一缺陷，**不在本 lane 范围内，未改**。

### #4 `apply()` 会用 `SnsTag` 的 id 覆写本类 `bound`

- **改了什么**：`snsId != 0` 分支改成「本类已有非 0 id 就用本类的，不覆盖」（769-784）：
  只有 `bound[view]` 为空时才采纳外部 id 建 `Binding`。两边 id 不一致时打一条限流告警
  `SNS_DELTAG: id clash own=… ext=…`（776-781），真机可据此定性哪条链解析错了。
  **渲染仍然用入参 `tagged`**（782-783）—— 那是 `SnsTag` 的 `del + forceSet`。
- **改在哪个行号**：`SnsDeletedTag.kt:763-798`（含两段 KDoc：为什么不覆盖 / 为什么渲染不改）。
- **判据**：
  ① `snstag fake` 之后界面出现标记（说明入参 `tagged` 这条渲染路径还在，没被 `deleted()` 换掉）；
  ② 同时打 `snstag dump` 与本类 `dump()`，同一个 `host=` 上两边 id 不等时，
  日志出现 `id clash` 且**本类的 id 不变**（改之前会被覆写成 ext 那个）。

### #5 第 ④ 级 `isTimeLike` 误命中

- **改了什么**：
  1. `SnsDeletedTagLogic.kt:133-157` —— 三条正则改成**整串匹配**
     （`REL_RE` / `DATE_CN_RE` / `DATE_NUM_RE`），新增 `DURATION_RE`（`^\d{1,2}:\d{2}$`）
     显式否掉视频时长徽章，新增长度闸门常量 `TIME_LIKE_MAX = 20`；
  2. `isTimeLike`（182-188）：排除以 `:` 开头的串、排除纯时长形态、长度 > 20 直接否；
  3. `SnsDeletedTag.kt:944-951` —— `resolveTime` 返回 `(TextView, 命中级别)`，
     `timeTextOf`（917-928）**只在级别 ≤3 时写 `tvId`**，④ 级只在本轮生效并打一条限流日志
     `SNS_DELTAG: tv by level-4 fallback`。
- **改在哪个行号**：`SnsDeletedTagLogic.kt:133-188`、`SnsDeletedTag.kt:902-951`。
- **判据**（纯函数，可直接跑）：
  `00:15` / `1:23` / `12:30` / `:30` / `昨天我去看了…` / `刚刚到家` ⇒ **false**；
  `昨天` / `3小时前` / `10月28日` / `2026年10月28日` / `44分钟前` / `刚刚` ⇒ **true**。
  （本轮用 `python -c` 按 Java 正则语义模拟跑过这 12 条，全对。）
  ④ 不缓存：命中一次后 `bound` 里该 item 的 `tvId` 仍为 `-`（`dump()` 里看 `tvId=`）。

### #6 `snsId=0` 兜底用旧 `Binding` 重画（复用残留）

- **改了什么**：选**方案 (a) 的变体 —— 记 position**（`Binding.pos`，152）：
  新增 `invalidateIfReused(view, pos)`（155-176），在 `onBind` 解析**之前**先判：
  旧 Binding 的 `pos >= 0` 且与本次 `pos` 不同 ⇒ 这个 View 已经装了另一条动态 ⇒
  先 `restore(view)` 剥掉旧标记、再从 `bound` 摘掉旧 Binding（`restore` 必须在 remove 之前，
  它要用旧 `hint` 定位时间行）。`onBind`（392）、`retryPaint`（447）都会写 `pos`。
- **为什么选它而不是另两个**：
  (b) 时间戳 —— 阈值定多大都不对（静态停留的 item 会被误判成过期，反而把标擦掉）；
  (c) 文案基线 —— bind 那一刻时间行的文案常常还是空的（渲染是 suspend），基线对不上会误判。
  `pos` 是 RecyclerView 自己给的复用证据，零猜测、零额外引用。
  ⛔ 全程没有往 `bound` 里存任何 View / holder / data 的强引用（`pos` 只是个 `Int`）。
- **改在哪个行号**：`SnsDeletedTag.kt:127-176`（`Binding.pos` + `invalidateIfReused`）、
  371-373（调用点）、392 / 447（写 pos）、795-797（`snsId=0` 兜底的注释）。
- **判据**：快速滚动后停下来，任一「未删」动态上不出现 `[删除已拦截]`；
  已删那条仍在（`painted` 不回落）。`dump()` 里每个 `host=` 的 `u=` 与屏幕上实际那条一致
  （改之前复用窗口内会看到上一条的 id）。

### #7 `hits` 不是「画上了几条」

- **改了什么**：拆成两个计数 —— `hits`（判定命中，**语义不变、不删**，100 附近）
  与新增 `painted`（101，只有末尾 `tv.text = want` 真的执行了才 ++，836）。
  `stat()`（1254-1259）两个都打；KDoc 的「看数顺序」改成 `painted>0` 才算画上了。
- **改在哪个行号**：`SnsDeletedTag.kt:98-101`（字段）、800-837（`paint`）、1239-1259（`stat`）。
- **判据**：取不到时间控件时（`missedTime` 上涨的那次）`hits` 涨而 `painted` **不涨**；
  只有 `SNS_DELTAG tv …` 日志出现、且 `painted>0` 时界面上才真的能看到标记。
  ⇒ 真机验收判据从「`hits 0→1`」改成「`painted>0`」。

### #8 本类没有 `forceSet`（探针期间两条 sweep 互相贴/剥）

- **改了什么**：新增 `@Volatile private var forceSet: Set<Long> = emptySet()`（1162），
  `deleted()`（1175-1179）返回 `归档 ∪ forceSet`（原来的缓存逻辑平移到 `archived()`，1182-1221，
  行为不变）；`sweep()`（1120-1127）因此与 `SnsTag.sweep` 的 `del + forceSet` 同口径。
  新增探针出口 `internal fun forceTag(ids: Set<Long>)`（1168）：设完立刻 `ensureSweep()`。
- **改在哪个行号**：`SnsDeletedTag.kt:1149-1179`、`1120-1127`。
- **判据**：探针期间 `SNS_DELTAG stat` 里的 `deleted=` 比 `SnsTag` 的 `deleted(` 大
  `forceSet.size`（非空时）；同一条动态在 2 s 周期内不再「贴上又被剥」
  （改之前的表现是 `hits` 每 2 s 涨一次而界面闪烁）。
- ⚠️ **未做的接线**：`SnsTag.kt` / `EntryReceiver.kt` 不归本 lane，
  探针（`snstag fake / mark / unmark`）在设 `SnsTag.forceSet` 的同时需要**再调一次**
  `SnsDeletedTag.forceTag(同集合)`，两边的 sweep 才彻底一致（KDoc 里已写明，留给那一条 lane）。

## 单测补强（R35-6B）

- **范围**：只改了 `wxgroup/app/src/test/java/com/lx/wxgroup/feature/SnsDeletedTagLogicTest.kt`
  （105 行 → 598 行）。`app/src/main` **一处未动**（动手前后 `git diff --stat` 一致，
  且 `SnsDeletedTagLogic.kt` 与动手前备份 `diff` 逐字节相同）。
- **结果**：`SnsDeletedTagLogicTest` **59 条，全绿，3 条 `@Ignore`**（下面「疑似缺陷」）。
  跑法见文末「怎么跑」。

### 覆盖

| 函数 | 覆盖点 |
|---|---|
| `snsIdOf` | 真实样本 / 15 位下界 / 20 位上界 / 14 位 / 无 `snsID=` 前缀 / `snsID=0`、`snsID=-1` / 引号后非数字 / 20 位全 9 溢出 / null / `toString()` 抛异常 |
| `isTagged` | `id=0` 恒 false（**即使集合含 0**）/ 负数命中（负数是有符号合法值，与 0 不同待遇）/ 空集 / 命中 / 不命中 |
| `appendTag` | 连调两次 == 一次 / 空串·纯空白·`null` → 只剩 MARK / 原文 == MARK / 标记在中间（现状 `contains` 语义）/ `trimEnd` 而非 `trim`（前导空白保留）|
| `parseSnsId` | 空串 / 纯空格 / 正负号 / 单独 `-` / 无符号 20 位（与有符号同 bits）/ 21 位 / 前导 0（19 位可、21 位否）/ 混非数字 |
| `brief` | 超长截断（80+3）/ 恰好等于 max 不截断 / `max=0` / 换行 / 空串 / `null` / 中文（UTF-16 长度）/ emoji（**按代理对截断，只锁长度不锁字形**）|
| `isTimeLike` | 9 种相对时间 / 4 种绝对日期 / `00:15`·`1:23`·`12:34`·`03:20` / 空·空白·`null` / 200 字正文 / 长度闸门专属用例 / `28` / `昨天 10:30` / 只含时间词的正文 / 首尾空白 |

- ⚠️ 任务书里写的是 `appendTag(text, mark)`，**当前实现是单参** `appendTag(CharSequence?)`，
  没有 `mark` 参数 ⇒ 按现在的实现写的，没造一个不存在的方法。
- `昨天 10:30` 断言的是 **false**，理由写在用例 KDoc 里：整串匹配天然排除它；
  且取向是「认不出来就不打标（漏）好过打错位置（错）」。
  若将来确认列表页真会显示这种拼法，该改的是**这条用例 + 正则**，不是退回「包含即可」。

### 变异验证（4 组，其中 2 组是等价变异）

| # | 变异 | 红 | 失败在 |
|---|---|---|---|
| M1 | `TIME_LIKE_MAX` 20 → 200（长度闸门失效） | **1** | `形状像相对时间但超 20 字：被长度闸门挡住`（`12345678901234567890分钟前`，23 字） |
| M2 | 从 `REL_RE` 白名单删掉 `刚刚` | **1** | `相对时间说法：整串命中才算时间行` |
| M3 | 只删掉 `DURATION_RE` 的排除 | **0** | ⚠️ **等价变异**，见下 |
| M3′ | 删掉 `DURATION_RE` 排除 **且** 给 `REL_RE` 加上 `\d{1,2}:\d{2}`（模拟「今天发的动态显示 HH:MM」这个真实需求） | **2** | `视频时长徽章：一律不算时间行`、`首尾空白：trim 之后照常判定` |
| M4 | 去掉 `REL_RE` 的 `^…$` 锚点 | **0** | ⚠️ **等价变异**，见下 |
| M4′ | 去锚点 **且** `matches()` → `containsMatchIn()`（= 退回老判据「包含即可」） | **2** | `正文中包含时间词：不算时间行`、`混合形态 昨天 10点30：按整串匹配判 false` |

**为什么 M3 / M4 单独改是等价变异**（不是测试没写够，是判据结构决定的）：

- **M3**：`REL_RE` / `DATE_CN_RE` / `DATE_NUM_RE` 三条**都是整串语义**，字面上没有任何一条能吃下
  `^\d{1,2}:\d{2}$`。⇒ 在**当前**判据下 `DURATION_RE` 那一行是不可达分支（纯防御性代码）。
  它的价值只在「将来有人把 `HH:MM` 加进白名单」时才兑现 —— M3′ 就是把这个前提显式做出来，
  此时删掉它 `00:15` 立刻变 true ⇒ 2 条红。**保留它是对的，但要知道它现在不生效**。
- **M4**：Kotlin 的 `Regex.matches()` **本身就要求整串匹配**，
  模式里的 `^…$` 是冗余的 ⇒ 只删锚点行为不变。真正退回「包含即可」要连 `matches()` 一起换
  （M4′），那条才是本轮收紧真正要挡的回归。

### 发现的疑似缺陷（⛔ 没改实现，只留 `@Ignore` 用例）

三条都指向 `SnsDeletedTagLogic.kt:36` 的 `ID_RE = Regex("""snsID\s*=\s*'?(-?\d{15,20})""")`，
**缺左右两侧边界**：

| # | 输入 | 期望 | 实际 | 说明 |
|---|---|---|---|---|
| 1 | `snsID='123456789012345678901'`（21 位） | `null` | `12345678901234567890` | `\d{15,20}` 贪婪吃掉**前 20 位**，`find` 无右侧边界 |
| 2 | `snsID='1504316024693044480a'` | `null` | `1504316024693044480` | 数字串后面的字母被忽略，取前 19 位 |
| 3 | `Foo(oldsnsID='111111111111111111', snsID='15043160246930444808')` | `15043160246930444808` | `111111111111111111` | `oldsnsID` 里**含**子串 `snsID`，`find` 取最左 ⇒ 抓到前一个字段 |

- #1/#2 的危害：拿到一个**看着合法、其实是错的** id ⇒ 打错标签，正是本类 KDoc 里写要防的形态。
  修法建议（交给 `SnsDeletedTag.kt` 那条 lane）：`ID_RE` 改成
  `"""snsID\s*=\s*'?(-?\d{15,20})(?!\d)'?"""` 之类，左侧再加 `(?<![A-Za-z])`。
- #3 只是**隐含假设没被锁住**（当前 8.0.48 的 toString 里没有 `…snsID` 这种字段，不是现网问题），
  版本漂移后可能踩到。
- 对应的 3 条用例已写好并标 `@Ignore`，修完删掉 `@Ignore` 即可，不用重写断言。

### 怎么跑（⚠️ 环境坑）

```bash
cd /c/Users/lx/WorkBuddy/2026-09-29-20-23-21/wxgroup
PATH="/c/WINDOWS/system32:/c/WINDOWS" \
  JAVA_HOME="G:/Program Files/Microsoft/jdk-21.0.7.6-hotspot" \
  ./gradlew.bat testDebugUnitTest --tests "*SnsDeletedTagLogicTest" --offline
```

- ⛔ **必须收窄 `PATH`**。本机 shell 的 `PATH` 是**坏的**（含 `F;` 这类残缺片段和游离引号，
  例如 `"G;F:/Program Files\WorkBuddy\resources\vendor\PortableGit\Program Files\Microsoft\..."`），
  Gradle 会把它原样塞进 `-Djava.library.path=`，java 启动器按空格一切，
  主类名就变成了 `Files\Microsoft\jdk-21.0.7.6-hotspot\bin;G:/Program`
  ⇒ 报 `找不到或无法加载主类` / `Process 'Gradle Test Executor N' finished with non-zero exit value 1`。
  **这不是测试失败，是测试进程压根没起来**，看 `--info` 里 `Starting process 'Gradle Test Executor 1'. Command:` 能直接看到。
  用上面那个干净 `PATH` 就正常。
- 增量编译不重编测试类的坑仍在：改完测试若报「0 tests」，先删
  `app/build/tmp/kotlin-classes/debugUnitTest` 再跑。
