# R36 lane `r36-snstag`：`SnsTag.kt` 两处修复

任务来源：`docs/_r35_review_tag.md` §3.3 末尾（「同一缺陷在 `SnsTag.deleted()` 里一模一样地存在」）
与「修复记录（R35-6）」的 #3 判据里点名的 `SnsTag.kt:616-628`、#8 末尾的「⚠️ 未做的接线」。

改动文件：**只有** `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsTag.kt`（`EntryReceiver.kt` 未动 ——
探针出口 `snstag mark/unmark/fake` 本来就调 `SnsTag` 的公开方法，双写收在 `SnsTag` 内部即可，不需要新增/改名入口）。

结论标注约定：① = 代码字面（能指到行号）；② = 推理（标出假设）。

---

## 任务一（P1）：`SnsTag.deleted()` 的 `setLoading` 永久泄漏

### 改前（`SnsTag.kt:616-628`，改动前的行号）

```kotlin
if (setLoading.compareAndSet(false, true)) {
    Thread {
        try {
            runCatching {
                delSet = loadIds()
                setAt = SystemClock.elapsedRealtime()
            }
        } finally {
            setLoading.set(false)          // ← 只在**线程体内**
        }
    }.apply { isDaemon = true; name = "sns-tag-ids" }.start()   // ← 在 try 之外
}
return delSet
```

### 改后（`SnsTag.kt:630-663`）

```kotlin
if (setLoading.compareAndSet(false, true)) {
    try {
        Thread {
            try {
                runCatching {
                    delSet = loadIds()
                    setAt = SystemClock.elapsedRealtime()
                }
            } finally {
                setLoading.set(false)      // ← 内层：防「体内异常」
            }
        }.apply { isDaemon = true; name = "sns-tag-ids" }.start()
    } catch (t: Throwable) {
        setLoading.set(false)              // ← 外层：防「线程没起来」
        warnOnce("SNS_TAG: id refresh thread failed: ${t.javaClass.simpleName}")
    }
}
return delSet
```

### 为什么两层都要（缺一层都不行）

两层守的是**两个不重叠的失败窗口**，不是冗余：

| 层 | 防的失败 | 为什么只有它能防 |
|---|---|---|
| 外层 `catch (t: Throwable)`（656-661） | **线程根本没起来**：`Thread.start()` 本身抛（典型 `OutOfMemoryError: unable to create new native thread`，微信进程线程数本来就多；或 `IllegalThreadStateException`） | 此时 lambda 体**一次都没执行**，体内的 `finally` 永远轮不到跑 ⇒ 外层是**唯一**能复位的地方 |
| 内层 `finally`（651-655） | **线程起来了，但体内抛异常**（`loadIds()` 里的 SQLite 异常、被 interrupt、`Error`） | 那条线程的栈不是我们能 `catch` 的位置 —— `Thread` 的 `run()` 是它自己的栈帧，异常只会往它自己的 `finally` 走 |

① CAS 已经把 `setLoading` 置成 `true` 了（630），而上面两个窗口都在「已置位、未复位」区间内。
漏任何一层 ⇒ `setLoading` **永久为 true** ⇒ 之后每次 `deleted()` 都在 630 行 CAS 失败、直接 `return delSet` 旧值
⇒ **已删清单从此再也不刷新**，而且**完全静默**（界面表现只是「新删的没标」，没有任何日志指向这里）。

② 假设：`delSet` 的陈旧程度 = 清单最后一次成功刷新的内容，进程存活期间不再更新；
`deleted()` 里的 `SET_TTL_MS`（5000ms）早退分支（617）救不了它 —— 那个分支 `return delSet`，
而刷新分支在它后面（630）且被 CAS 挡住。

### 告警为什么另开一个额度

新增 `warnLogged`（104）+ `warnOnce`（118-120），不复用 `errLogged`：

- ① `errLogged` 被 bind 异常路径按「只留前 3 条」扣着（`SnsTag.kt:258` 的 `errLogged.get() < 3` 写法），
  再共用就只剩 2 条告警额度；
- ① `logOnce`（106-108）用的是 `miscLogged`，那是给 `dump` / 一次性信息用的，`LOG_MAX=5` 很快被吃光。
- 与 `SnsDeletedTag.warnOnce`（`SnsDeletedTag.kt:123-125`）同一做法、同一理由：异常是最该看见的日志。

---

## 任务二（P1）：探针 `forceSet` 双写接线（R35 #8 留给本 lane）

### 背景（①，`SnsDeletedTag.kt:1149-1179`）

R35 已经在 `SnsDeletedTag` 加了 `@Volatile forceSet` + `internal fun forceTag(ids)`（1168），
它的 `deleted()`（1175-1179）返回 `归档 ∪ forceSet`；但 `SnsTag` 侧的探针出口**没有调它** ⇒
`SnsTag.sweep` 贴、`SnsDeletedTag.sweep` 剥 ⇒ 2 秒周期闪烁（`_r35_review_tag.md` §7.1.2）。

### 做法

新增 `SnsTag` 内部唯一写入口 `setForce(ids)`（`SnsTag.kt:713-716`）：

```kotlin
private fun setForce(ids: Set<Long>) {
    forceSet = ids
    SnsDeletedTag.forceTag(ids)
}
```

四个写入点全部改走它。为什么不每处各写两行：① 漏一处就退化成原来的闪烁，收成一个函数才能保证
「写 A 必写 B」这件事只有一处能犯错的余地。

### `forceSet` 四个写入点逐一判断

| # | 位置 | 改前 | 结论 | 依据 |
|---|---|---|---|---|
| 1 | `refresh()`，`SnsTag.kt:233` | `forceSet = emptySet()` | ✅ **改**，走 `setForce(emptySet())` | ① 紧邻的注释字面就是「验证用的强制集合只能由探针设置，开关一变就清掉，绝不能让它在正常运行里残留」⇒ **是**「清空探针」语义。① `refresh()` 是 `SnsTag` 清探针的唯一路径（`on()` 的 30s TTL 会触发它），不同步清 ⇒ 开关一翻，本类停贴而 `SnsDeletedTag` 还留着旧 id 继续贴 ⇒ **单边闪烁** |
| 2 | `markVisible()`，`SnsTag.kt:739` | `forceSet = snap.mapTo(HashSet()) { it.second }` | ✅ **改** | ① 探针「贴上」出口（`EntryReceiver.kt:605`） |
| 3 | `unmark()`，`SnsTag.kt:749` | `forceSet = emptySet()` | ✅ **改** | ① 探针「撤掉」出口（`EntryReceiver.kt:606`）。① 原本的 `ensureSweep()` 保留 —— 那是本类的；`SnsDeletedTag` 那份由 `forceTag` 内部的 `ensureSweep()` 排，两边都要排一拍 |
| 4 | `fakeMark(raw)`，`SnsTag.kt:764` | `forceSet = setOf(id)` | ✅ **改** | ① 探针「按 id 精确模拟被删」出口（`EntryReceiver.kt:608-611`） |

⇒ **四处全部属于探针写入，没有第 5 处**（① 改动后全文件 `forceSet` 只剩 5 处引用：692 声明、
718 `isDeleted` 读、730 `apply` 读、745/773 读 `.size`；写入只剩 `setForce` 内部那一行）。

### 两处顺带确认的细节

1. **① `markVisible()` 里的 `cfgAt = SystemClock.elapsedRealtime()`（742）现在同时护住两份。**
   `SnsDeletedTag` 自己没有 `refresh()` 清 `forceSet`（它的 `on()`，`SnsDeletedTag.kt:408-414`，只重读开关、
   不动 `forceSet`）⇒ 那边清探针的**唯一**路径就是本类 `refresh()` ⇒ 延后 `cfgAt` 挡住 `on()` 的 TTL 触发，
   两份都保住 30 秒不被清掉。已把这层关系写进 740-741 的注释。
2. **① `SnsDeletedTag.forceTag` 是 `internal`（1168），同模块可直接调**，无需改可见性。

### 为什么**没有**用 `runCatching` 包 `SnsDeletedTag.forceTag`

逐字读 `forceTag`（1168-1172）后判断 —— 它体内只有三步，都不抛：

1. ① `forceSet = ids`（写 `@Volatile` 字段）；
2. ① `ensureSweep()`（`SnsDeletedTag.kt:1099-1104`）= `synchronized(bound) { bound.isEmpty() }`
   （`bound` = `Collections.synchronizedMap(WeakHashMap<View, Binding>())`，① `SnsDeletedTag.kt:179-180`）+
   `sweepScheduled.compareAndSet` + `mainHandler.postDelayed`；
3. ① `mainHandler` 是 `by lazy { Handler(Looper.getMainLooper()) }`（`SnsDeletedTag.kt:182`），
   Kotlin `lazy` 默认 `SYNCHRONIZED`，`Looper.getMainLooper()` 在进程内恒非 null。

⇒ **不包 `runCatching`**。理由写在 `setForce` 的 KDoc 里：包了只会把「将来有人在 `forceTag` 里加了会抛的逻辑」
吞成静默；探针是人工敲的，真出事应该炸到控制台而不是悄悄降级。

### 线程约束（①/②）

`forceTag` 的 KDoc（1166-1167）写「必须在主线程调」。四个调用点的线程：
- ① `markVisible` / `unmark` / `fakeMark`：由 `EntryReceiver` 的 `snstag` 分支调（605/606/608-611），
  而 `BroadcastReceiver.onReceive` 跑在主线程（`EntryReceiver.kt:85` 的注释字面写了这点），
  且这三个分支**不在** `io { }`（89-95，那个才 `Thread{}`）里 ⇒ 主线程；
- ① `refresh()`：由 `on()` 的 TTL 驱动 / `install()`（137）/`EntryReceiver.kt:185` 调。
  `install()` 那次在 Xposed 的加载线程上 —— ② **假设**（这是本 lane 唯一一条 ②）：即使不在主线程也无害，
  因为 ① `ensureSweep()` 在 `bound` 为空时**立刻 return**（1100），不碰 `mainHandler`、
  不做任何 View 操作；而 `install()` 那一刻 `bound` 必然为空。

---

## 单测

**本 lane 无新增单测。理由（逐条）：**

1. ① `SnsTag` 是 `object`，`deleted()` 是 `private`，且体内硬依赖 `GroupDb.current()`、`SystemClock.elapsedRealtime()`、
   `Thread` —— JVM 单测里没有 Android 环境，跑不起来（本模块现有单测只有
   `SnsDeletedTagLogicTest.kt`，覆盖的是零 Android 依赖的 `SnsDeletedTagLogic`）；
2. ① 要把它变成可测的纯函数，必须新增 `feature/SnsTagLogic.kt` 并给生产代码注入
   「线程工厂 + 时钟 + 日志」三个接缝 —— 这超出了「照抄 R35 #3 写法」的改动范围，
   也会把「谁负责复位」这件事从一处挪到两处，反而更容易错；
3. ① 任务二的 `setForce` 是两行赋值，抽成纯函数没有可判定的行为差异；
4. 判据仍然落到真机：见 `_r35_review_tag.md` #3 的「判据」段（`setLoading` 回到 false、
   日志出现 `SNS_TAG: id refresh thread failed:`）与 #8 的判据（`SNS_DELTAG stat` 里 `deleted=` 多
   `forceSet.size`、同一条不再「贴上又被剥」）。

⇒ 未编造任何「已验证」结论；编译正确性靠静态自查（括号配对、符号可见性、`internal` 同模块），
**未跑 gradle / adb / 装包**（按 lane 约束交给主会话统一构建）。

---

## 风险

1. **低**：`SnsTag.install`（`HookEntry.kt:250`）早于 `SnsDeletedTag.install`（261），
   所以 `refresh()` → `setForce` → 首次触碰 `SnsDeletedTag` 的时间点被提前到 Xposed 加载期。
   ① 那一刻 `bound` 为空 ⇒ `ensureSweep()` 直接 return，无副作用；`mainHandler` 是 lazy、不会在那时创建。
2. **低**：`refresh()` 现在会无条件排一拍 `SnsDeletedTag` 的 sweep（原本只在 `!enabled` 时排本类的）。
   ① `SnsDeletedTag` 的 `sweeper`（1091-1094）本来就自我重排，`ensureSweep()` 在已排期时因
   `compareAndSet` 失败而 no-op ⇒ 不会叠加出多条 Runnable。
3. **无**：`EntryReceiver.kt` 未改 ⇒ 探针命令 `snstag mark/unmark/fake` 的用法与输出格式不变。

[DONE]
