package com.lx.wxgroup.feature

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * R30 §6.1「被删动态在发布时间后面追加灰色小字 `[删除已拦截]`」的 UI 落点 + R35 重挂载后的**自持挂载点**。
 *
 * ## 为什么 R35 要自己挂钩子（docs/_r35_sns_tag.md ①）
 * R30 把链路搭在 [SnsTag] 的 `onBindViewHolder` 上。dex 取证证明这一版里：
 * - `WxRecyclerAdapter` **自己没声明** `onBindViewHolder`，`Class.getMethods()` 取到的是
 *   继承来的两条：`w1.onBindViewHolder(c3,I)` 与 `ki4/i0.onBindViewHolder(c3,I,List)`；
 * - 真机 `hooks=3`（两条 + setAdapter 一条）与之一致；
 * - 其中 **2 参那条是死钩**：`ki4/i0.onBindViewHolder(3参)` 体内只 `invoke-virtual k0 / i0 / l0`，
 *   没有任何一处调到 2 参版。
 *
 * 真正干活的在更下面一层（classes8.dex 反汇编）：
 * ```
 * ki4/i0.onBindViewHolder(3参)
 *    ├ 0x0026 invoke-virtual ki4/i0->k0  → WxRecyclerAdapter.b1(m0,I,ki4/r)
 *    ├ 0x0051 invoke-virtual ki4/i0->i0  → WxRecyclerAdapter.a1(m0,I,ki4/r)
 *    └ 0x005b invoke-virtual ki4/i0->l0  → WxRecyclerAdapter.l0(c3,I)
 *                                            0x0002 invoke-virtual WxRecyclerAdapter->c1(m0,I)
 * n0(c3,I,List) → 0x0002 invoke-virtual WxRecyclerAdapter->d1(m0,I,List)
 * ```
 * 而 `c1` / `d1` 体内**把数据直接写进 holder**：
 * ```
 * 0x001b iput-object …, v9, Lki4/m0;->E:Ljava/lang/Object;    ← 数据 item
 * 0x0061 iget-object v9, Landroidx/recyclerview/widget/c3;->d:Landroid/view/View;  ← itemView
 * ```
 * ⇒ 挂 `c1/d1/a1/b1/l0/n0/k0` 之后，`args[0]`（holder）里**同时**有数据对象和 itemView，
 *    **不再需要「pos ↔ getData() 下标」对账**，也就不会再错位打标。
 *
 * ## ⛔ 不许 hook `oh3/p.J0` / `ns.a`
 * 取证证明 `oh3/p.toString()` 复用了 `J0`（getTimeString），hook 它会污染 snsID 解析。
 * 这里只做纯 UI 层追加，不碰模型/格式化层。
 *
 * ## 与 [SnsTag] 的关系
 * [SnsTag] 仍会调 [apply]（它自己的 `onBindViewHolder` 链路），本类**保持那个契约不变**；
 * 新增的自持链路只是**并行多一条**更可靠的入口。两条都调 [apply]，而 [apply] 是幂等的。
 */
object SnsDeletedTag {

    /** 微信的 RecyclerView 适配器基类：**没有混淆**，可以直接按名字找 */
    private const val ADAPTER_CLASS = "com.tencent.mm.view.recyclerview.WxRecyclerAdapter"

    /** 微信自研列表基类：用来抓 setAdapter 拿到「具体 adapter 类」 */
    private const val WX_RV_CLASS = "com.tencent.mm.view.recyclerview.WxRecyclerView"

    /** 只给朋友圈的 adapter 挂钩，别把微信其它 RecyclerView 全挂上 */
    private const val SNS_PREFIX = "com.tencent.mm.plugin.sns."

    private const val SWEEP_MS = 2000L
    private const val SET_TTL_MS = 5000L
    private const val CFG_TTL_MS = 30000L

    /** 日志限流：热路径里 LogX 是主线程同步文件 IO，只能留前几条 */
    private const val LOG_MAX = 5

    @Volatile private var enabled = false
    @Volatile private var cfgAt = 0L
    @Volatile private var delSet: Set<Long> = emptySet()
    @Volatile private var setAt = 0L
    @Volatile private var firstLoad = false

    private val setLoading = AtomicBoolean(false)
    private val sweepScheduled = AtomicBoolean(false)
    private val installed = AtomicBoolean(false)

    @Volatile private var dbWarned = false

    /**
     * 取不到时间控件时的告警计数（用于诊断「字段/方法名漂移」）。
     *
     * ## ⚠️ R37-1 起改为**按 Binding 去重**
     * 同一个 View 会被 2 秒 sweep 反复重判（这是故意的，用来扛 `realTimeUpdate` 把纯时间原文写回），
     * 所以一处持续失败能在几十秒内把这个数刷到几十 ⇒ 原实现的总数完全回答不了「几条动态画不上」
     * （真机 `missedTime=43` 实际只来自 1 个错绑的 header）。
     * 去重挂在 Binding 上而不是「View 一辈子只计一次」：View 被复用给另一条动态时 Binding 是
     * 全新对象 ⇒ 新内容仍然会计一次，不会把真实故障也一起抹掉。
     */
    @Volatile private var missedTime = 0

    /**
     * [missedTime] 里「判定命中、却找不到时间控件」的那部分 —— **真故障**：该画的没画上。
     *
     * 为什么必须和 [missedTimeRestore] 分开：这两种失败的修法完全不同。这一路涨说明
     * 「清单/判定是对的，但 View 结构漂了」，下一轮要去看 `getPublishTimeText()` 的方法名。
     */
    @Volatile private var missedTimeHit = 0

    /**
     * [missedTime] 里「未命中、只是尝试还原」的那部分 —— **探索失败，不是故障**。
     *
     * 隔开它的理由：反过来，某一格不打标时代码照样会去找时间控件准备剥掉可能残留的标记，
     * 找不到是**正常结果**（这格的宿主多半本来就是封面/广告卡这类没有时间行的容器）。
     * 混在一个总数里，它会把 [missedTimeHit] 那几个真正该修的样本整个淹没。
     */
    @Volatile private var missedTimeRestore = 0

    /**
     * 最近一次「View ↔ 数据」对账的**细粒度**失败原因（诊断专用）。
     *
     * 为什么单开一个字段：[lastMiss] 会被 `nodata(...)` 这种粗粒度原因覆盖，而
     * 「作者文本还没渲染出来」与「同作者相邻动态导致歧义」两种失败的**修法完全不同**
     * （前者要延后重试，后者是无解的歧义），真机却只看得到一条 lastMiss ⇒ 盖掉就等于失明。
     */
    @Volatile private var lastRecon = ""

    /** 自诊断：静默失效全靠这几个数定位（binds>0 才说明钩子活着） */
    @Volatile private var binds = 0
    @Volatile private var snsBinds = 0
    @Volatile private var resolved = 0
    /** 判定命中的 paint 次数（**不是**画上了几条，见 [paint] 的 KDoc） */
    @Volatile private var hits = 0
    /** 真的把标记写进时间 TextView 的次数 —— 真机验收的唯一判据是它 >0 */
    @Volatile private var painted = 0
    @Volatile private var missed = 0
    @Volatile private var lastMiss = ""
    @Volatile private var hooks = 0

    private val miscLogged = AtomicInteger(0)
    private val errLogged = AtomicInteger(0)

    /** 每个 key 自己有几条日志额度（[logKeyOnce]） */
    private const val PER_KEY_LOG_MAX = 2

    /**
     * key → 已打出条数。[logKeyOnce] 的分桶账本。
     *
     * ⚠️ 只存 String/Int：**不许**把 View 或 holder 塞进来当 key（进程级 Map ⇒ 条目永不回收）。
     */
    private val perKeyLogged = Collections.synchronizedMap(HashMap<String, Int>())

    /**
     * **按 key 分桶**的限流日志。
     *
     * ## 为什么要分桶（R37-1）
     * [logOnce] 是进程级 5 条：第一次进朋友圈就被 holder pick / tv 成功 / tree dump 抢光了，
     * 而 `miscLogged` 从来不在 Activity 切换时重置 ⇒ 同一个用户**第二次再进朋友圈**时，
     * 换了一批 View 实例却**一条成功或失败日志都看不到**（R37 里正是这样被误导成
     * 「只有 1 次成功、其余全失败」）。⇒ 按 key（一般取 host 类名）分桶，每桶独立额度，
     * 第二次进 Activity 的结果仍然可见。
     */
    private fun logKeyOnce(key: String, msg: String, warn: Boolean = false) {
        val n = perKeyLogged[key] ?: 0
        if (n >= PER_KEY_LOG_MAX) return
        perKeyLogged[key] = n + 1
        if (warn) LogX.w(msg) else LogX.i(msg)
    }

    /** 供 [SnsTag.stat] 读的「没打上标」信号：>0 说明反射取不到时间控件（字段/方法名漂移）。 */
    internal fun missedTimeCount(): Int = missedTime

    /** 限流日志：LogX 是主线程同步文件 IO，热路径上只能留前几条 */
    private fun logOnce(msg: String) {
        if (miscLogged.get() < LOG_MAX && miscLogged.incrementAndGet() <= LOG_MAX) LogX.i(msg)
    }

    /**
     * 限流**告警**，与 [logOnce] 分开计额度。
     *
     * 为什么要分开：holder/pick、tv host 那些一次性 dump 会把 [LOG_MAX] 条额度很快吃光，
     * 真出了异常反而一条告警都看不到 —— 而异常正是最该看见的（静默失效排查全靠它）。
     */
    private fun warnOnce(msg: String) {
        if (errLogged.get() < LOG_MAX && errLogged.incrementAndGet() <= LOG_MAX) LogX.w(msg)
    }

    /**
     * 一个 itemView 当前的绑定关系。
     *
     * ## 为什么从 `View -> Long` 扩成 `View -> Binding`（R35-1c）
     * 光有 snsId 定位不到时间 TextView（真机 `hits=0 missedTime=1`）。定位还需要两条信息：
     * - `tvId` —— 解析成功后记住时间 TextView 的**资源 id**，之后 [sweep] 每 2s 重判
     *   时直接 `findViewById`，不必每次遍历子树；
     * - `hint` —— 这条动态的发布时间原文（`ImproveInfo(…, time:44分钟前 …)`），
     *   用来在子树里按内容认出时间行（dex 已证时间行原文 == `J0()`，见 Logic 的 KDoc）；
     * - `pos` —— 绑定时的 RecyclerView position。**复用失效**就靠它：View 被 RecyclerView
     *   复用给另一条动态时 `bindViewHolder` 会带着**新的 pos** 进来，而 `bound` 的 key
     *   还是同一个 View ⇒ 只要 `pos` 变了就说明这个 View 换内容了，旧 id/hint 一律作废
     *   （[invalidateIfReused]）。选它而不是「存一份上次 paint 的文案基线」，是因为
     *   bind 那一刻时间行的文案常常还是空的（见 [timeTextOf] 的 KDoc：渲染是 suspend，
     *   `afterHookedMethod` 时还没填），基线对不上反而会误判。
     *   ⛔ 也**不能**存 View / holder / data（见下面那条强引用禁令）。
     *
     * ⚠️ **不存 holder / data 本体**：[bound] 是 `WeakHashMap`，value 若强引用 key
     * 指向的对象（holder 强引用 itemView == key），条目将永远无法回收。
     * ⇒ 同理，`pos` 是个 `Int`，不是「把上一条动态的证据留下来」的强引用，安全。
     */
    private class Binding(val id: Long) {
        @Volatile var tvId: Int = View.NO_ID
        @Volatile var hint: String = ""
        /** 绑定时的 position；`-1` = 未知（[apply] 那条老链路不传 pos，不参与复用判定） */
        @Volatile var pos: Int = -1
        /**
         * 这一格是否已经计过一次「取不到时间控件」（[missedTime] 系列的去重开关）。
         *
         * ⚠️ 去重挂在 Binding 而不是「View 一辈子只计一次」：View 被复用给另一条动态时 Binding
         *    是全新对象 ⇒ 新的那条动态仍然会计一次，不会把真实故障也一起抹掉；同一格反复 sweep
         *    则只计一次。
         */
        @Volatile var missCounted = false
    }

    /**
     * 复用失效：这个 View 现在装的还是上一条动态吗？（[Binding.pos]）
     *
     * ## 为什么必须显式判，而不是「解析成功就覆盖」
     * `bound` 只在解析成功时被替换；一旦这一拍**解析不出** id（`a1/b1` 拿到的还是 bind-info，
     * 或整条链路没挂上），`bound[view]` 里躺着的仍是**上一条**动态的 Binding。
     * 而 [apply] 的 `snsId=0` 兜底正是拿这个旧 Binding 去 `paint` 的 ⇒ 上一条的
     * `[删除已拦截]` 会留在新的这条动态上（串味，比不打标糟得多）。
     * ⇒ 把「复用」变成显式事件：pos 一变就先把旧标记剥掉、再让这一拍重新解析。
     *
     * ⚠️ `pos < 0`（老链路 [apply] 建的 Binding，或取不到形参）不参与判定 ——
     *    那种 Binding 本来就没有可比对的位置，误判成复用会把刚贴上的标又擦掉。
     */
    private fun invalidateIfReused(view: View, pos: Int) {
        if (pos < 0) return
        val old = synchronized(bound) { bound[view] } ?: return
        if (old.pos < 0 || old.pos == pos) return
        // ⚠️ 顺序不能反：`restore()` 内部用 `bound[host]` 的 hint 定位时间行（第 ③ 级解析
        //    的唯一线索），先从 map 里摘掉就拿不到 hint 了。
        runCatching { restore(view) }
        synchronized(bound) { bound.remove(view) }
    }

    /** item 根 View → 当前绑定关系（弱引用，绝不钉住宿主 View） */
    private val bound: MutableMap<View, Binding> =
        Collections.synchronizedMap(WeakHashMap<View, Binding>())

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // ------------------------------------------------------------------ 安装

    /**
     * 在 [SnsTag.install] 之后调一次即可（HookEntry.kt）。
     *
     * ⚠️ 就算没人调它也不会永久失效：[apply] 被 [SnsTag] 第一次调到时会用
     * `itemView.javaClass.classLoader` **懒装**一次（[ensureInstalled]）。
     */
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        ensureInstalled(lpparam.classLoader)
    }

    /** 幂等。@return 实际新挂上的条数 */
    private fun ensureInstalled(cl: ClassLoader): Int {
        if (installed.get()) return 0
        // ⚠️ 先确认能找到基类再占位：找不到就别把「一次性安装机会」用掉
        val base = runCatching { XposedHelpers.findClass(ADAPTER_CLASS, cl) }.getOrNull()
        if (base == null) {
            LogX.w("SNS_DELTAG: $ADAPTER_CLASS not found on $cl")
            return 0
        }
        if (!installed.compareAndSet(false, true)) return 0
        var n = hookBinds(base)
        n += hookSetAdapter(cl)
        hooks = n
        LogX.i("SNS_DELTAG installed hooks=$n")
        if (n == 0) LogX.w("SNS_DELTAG: no bind hook installed, grey tag disabled")
        return n
    }

    private val hookedSigs = Collections.synchronizedSet(HashSet<String>())

    /**
     * 给一个 adapter 类挂上它（含 4 层父类）**自己声明的**绑定方法。
     *
     * ## 形状匹配而不是硬编码名字
     * `c1/d1/a1/b1/l0/n0/k0` 都是 R8 出来的混淆名，版本一变就飘。所以按**形状**选：
     * `形参[0]` 是 RecyclerView.ViewHolder 子类（`androidx.recyclerview.widget` 包下的子类，
     * 实测 `ki4/m0 → ki4/l0 → androidx/recyclerview/widget/c3`）、`形参[1]` 是 int。
     * 这样既覆盖候选 A（`c1/d1/a1/b1`）也覆盖候选 B（`l0/n0/k0`）。
     *
     * ⛔ 显式排除 `onBindViewHolder` —— dex 已证其 2 参版是死钩，挂上去只会让
     * 「hooks>0 但界面没反应」更难查。
     */
    private fun hookBinds(c: Class<*>): Int {
        var n = 0
        var cur: Class<*>? = c
        var up = 0
        while (cur != null && up < 4) {
            val name = cur.name
            if (name.startsWith("java.") || name.startsWith("android.") ||
                name.startsWith("kotlin.") || name.startsWith("dalvik.")
            ) break
            for (m in cur.declaredMethods) {
                if (!matchesBindShape(m)) continue
                // ⚠️ 去重键必须带 declaringClass：子类覆写 `c1/d1/a1/b1/…` 时方法名与形参
                //    与基类的**完全相同**，只按「名字(形参)」去重会把子类覆写版当成「已挂过」
                //    静默跳过 ⇒ 真正被执行的那一层反倒没挂上（R35 首轮「挂载点选错层」的病根
                //    就是这么来的，本文件 KDoc:225-227 的补挂措施会被这个键废掉）。
                //    幂等性不受影响：install() 两次依旧靠 ensureInstalled 的 installed CAS 挡住，
                //    即使再进这里，同一「类#方法(形参)」也只会挂一次。
                val key = "${m.declaringClass.name}#${m.name}(${m.parameterTypes.joinToString { p -> p.name }})"
                if (!hookedSigs.add(key)) continue
                m.isAccessible = true
                if (runCatching { XposedBridge.hookMethod(m, bindHook) }.isSuccess) n++
            }
            cur = cur.superclass
            up++
        }
        if (n > 0) LogX.i("SNS_DELTAG hooked adapter=${c.name} +$n")
        return n
    }

    private fun matchesBindShape(m: Method): Boolean {
        if (m.name == "onBindViewHolder") return false
        if (Modifier.isStatic(m.modifiers) || Modifier.isAbstract(m.modifiers)) return false
        val p = m.parameterTypes
        if (p.size !in 2..3) return false
        if (p[1] != Int::class.javaPrimitiveType) return false
        return isViewHolder(p[0])
    }

    /** 形参[0] 是不是 RecyclerView.ViewHolder（本版被 R8 改名成 `androidx/recyclerview/widget/c3`） */
    private fun isViewHolder(t: Class<*>): Boolean {
        var c: Class<*>? = t
        var up = 0
        while (c != null && up < 6) {
            if (c.name.startsWith("androidx.recyclerview.widget.")) return true
            c = c.superclass
            up++
        }
        return false
    }

    /**
     * 子类一旦覆写 `c1/d1/…`，只挂基类就会**静默失效**（这正是 R35 的病根形态）。
     * → 抓 `setAdapter` 拿到具体 adapter 类再补挂。
     */
    private fun hookSetAdapter(cl: ClassLoader): Int {
        val rv = runCatching { XposedHelpers.findClass(WX_RV_CLASS, cl) }.getOrElse {
            LogX.w("SNS_DELTAG: $WX_RV_CLASS not found, concrete-adapter hook skipped")
            return 0
        }
        val m = findSetAdapter(rv) ?: run {
            LogX.w("SNS_DELTAG: setAdapter not found, concrete-adapter hook skipped")
            return 0
        }
        return runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val a = param.args?.getOrNull(0) ?: return
                        if (!a.javaClass.name.startsWith(SNS_PREFIX)) return
                        hookBinds(a.javaClass)
                    }.onFailure { LogX.w("SNS_DELTAG setAdapter hook: ${it.javaClass.simpleName}") }
                }
            })
            1
        }.getOrDefault(0)
    }

    /** WxRecyclerView 自己没声明就往父类找（最多 6 层） */
    private fun findSetAdapter(rv: Class<*>): Method? {
        var c: Class<*>? = rv
        var up = 0
        while (c != null && up < 6) {
            val f = c.declaredMethods.firstOrNull { it.name == "setAdapter" && it.parameterTypes.size == 1 }
            if (f != null) return f.apply { isAccessible = true }
            c = c.superclass
            up++
        }
        return null
    }

    // ------------------------------------------------------------------ 绑定钩子

    /**
     * ⚠️ 这个回调的**最外层必须兜住 Throwable，且绝不 rethrow**。
     *
     * 为什么这么要紧：9 条 bind 钩子里有一条挂在
     * `androidx.recyclerview.widget.RecyclerView.Adapter.bindViewHolder` 上 —— 那是
     * **全应用所有 RecyclerView 的公共入口**，从这里逃逸的异常会顺着 RecyclerView 的
     * 布局流程往上冒泡 ⇒ **微信崩溃**（不是「标记没了」这种软失效）。模块异常必须和宿主隔离。
     *
     * 兜的是「边界」：体内的高风险点（反射、findViewById、setText）各自有 runCatching，
     * 但 `bound[view]`（WeakHashMap 的 hashCode）、`ensureSweep()` 里的 synchronized、
     * 末尾 `stat()` 里的 `on()` → SQLite 读，这三层**没有任何一层兜底**，平时不抛、
     * 一旦抛就是崩微信。
     *
     * ⚠️ 只兜异常、不打乱诊断：`missed++` / `lastMiss=` 这些计数都写在 [onBind] 体内、
     * 且先于可能抛出的点执行，所以「解析失败」的诊断不会因为加了这个 catch 被吞掉。
     * （同文件里另一个 `afterHookedMethod` —— `hookSetAdapter` 那条 —— 从一开始就自带
     *   runCatching，不需要再包。）
     */
    private val bindHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            // 钩子是不是死的，全看它 —— 必须在 try 之前自增，否则异常路径下
            // 「一次都没进来」和「进来就崩」会混成同一个数，没法定位。
            binds++
            try {
                onBind(param)
            } catch (t: Throwable) {
                // 吞掉 + 限流告警：绝不 rethrow（rethrow = 崩微信）
                warnOnce("SNS_DELTAG bind err: ${t.javaClass.simpleName}")
            }
        }
    }

    /** [bindHook] 的本体。分出来是为了让 try/catch 只包最外一层、内部逻辑仍然平铺。 */
    private fun onBind(param: XC_MethodHook.MethodHookParam) {
        // ① 只认朋友圈的 adapter（钩子挂在基类上，全微信的列表都会进这里）
        val adapter = param.thisObject ?: return
        if (!adapter.javaClass.name.startsWith(SNS_PREFIX)) return
        snsBinds++
        val holder = param.args?.getOrNull(0) ?: return

        // ② 开关关着时只还原、不解析
        if (!on()) {
            runCatching { itemViewOf(holder)?.let { restore(it) } }
            return
        }
        val view = itemViewOf(holder)
        if (view == null) {
            missed++
            lastMiss = "noitemview(${holder.javaClass.simpleName})"
            return
        }
        val pos = param.args?.getOrNull(1) as? Int ?: -1
        // ③ 复用失效：这个 View 若已经装了另一条动态，旧 Binding 必须先作废（见 Binding.pos）
        invalidateIfReused(view, pos)
        // ④ itemView 必须带进去：没有它，`adapter.data[pos]` 那条路径只能「能解析出 snsId 就采用」
        //    ⇒ 封面 header 会被错绑成第一条动态（R37 真机 `host=FrameLayout hint='25分钟前'`）。
        lastRecon = ""
        val data = dataOf(holder, adapter, pos, view)
        val id = SnsDeletedTagLogic.snsIdOf(data)
        if (id == null) {
            missed++
            // 优先用 [reconcileAt] 记下的细粒度原因：知道是「作者文本没渲染完」还是
            // 「同作者相邻动态歧义」，才知道该延后重试还是干脆认栽。
            lastMiss = lastRecon.ifEmpty { "nodata(${holder.javaClass.simpleName})" }
            // ⚠️ 不在每次解析失败时都 erase：一次 bind 会触发多条 hook，其中只有
            //    部分（`c1/d1`）拿得到 item（`a1/b1` 写进 `E` 的是 bind-info）。
            //    无条件 restore 会把刚由 `c1` 打上的标记又擦掉 —— 表现为「标记闪烁/没有」。
            //    ⇒ 只有这个 view 从来没解析成功过时才还原（那时确实该把复用残留剥掉）。
            if (synchronized(bound) { bound[view] } == null) restore(view)
            retryResolve(holder, adapter, pos, view)
            return
        }
        resolved++
        val prev = bound[view]
        val b = if (prev != null && prev.id == id && prev.pos == pos) prev else {
            Binding(id).also {
                it.hint = timeHintOf(data)
                it.pos = pos
                bound[view] = it
            }
        }
        ensureSweep()
        paint(view, b, deleted())
        // 前几条 + 每 20 条打一次自诊断，真机靠 grep SNS_DELTAG 看
        if (resolved <= 3 || resolved % 20 == 0) {
            LogX.i("SNS_DELTAG stat ${stat()}")
        }
    }

    /**
     * 开关语义（与 [SnsTag.refresh] 同口径）：**只看防删除开关** [Keys.ANTIREVOKE_SNS]。
     * R30 拍板：灰色小字 + 同一个开关，不新增 Key。
     */
    private fun on(): Boolean {
        if (SystemClock.elapsedRealtime() - cfgAt > CFG_TTL_MS) {
            enabled = Kv.getBool(Keys.ANTIREVOKE_SNS)
            cfgAt = SystemClock.elapsedRealtime()
        }
        return enabled
    }

    // ------------------------------------------------------------------ bind 竞态兜底

    /** 延迟一拍重试的总预算：只在最开始几条里试，扣完就不再排队（防快速滚动时堆一堆 Runnables） */
    private const val RETRY_MAX = 12

    @Volatile private var retryLeft = RETRY_MAX

    /**
     * 「延迟一拍重试」兜底（真机 `missed=2 lastMiss=nodata(m0)`）。
     *
     * 一次 bind 会触发多条 hook（`ki4/i0.onBindViewHolder` 体内依次 `k0→b1`、`i0→a1`、
     * `l0→c1`），先跑的那几条可能早于数据真正落位 ⇒ `dataOf` 三个候选这一拍全空。
     * 排到主线程的下一拍再判一次即可 —— 那时 `c1` 已经把真 item 写好了。
     *
     * ⚠️ 只补**一拍**，不做多帧轮询（滚动时堆 Runnables 会掉帧）。
     * ⚠️ 拿到的是 holder **当前**的数据：就算这两帧之间 ViewHolder 被复用了，
     *    `holder → itemView` 的对应关系不变，画上去的仍是这一格此刻的正确内容。
     *
     * ## R37-1：它现在还兼着「等作者文本渲染出来」的职责
     * [reconcileAt] 的作者名对账在 TextView 还没填字时**明确判不过**（不是跳过校验）。
     * 那一拍的合理处置就是排到下一拍重试 —— 昵称/头像在 bind 早期就 `setText`，
     * 通常一拍之后可见文本就够了。⇒ 看到 `lastMiss=noname(...)` 且随后 `resolved` 跟上，
     * 属正常；持续 `noname` 才说明 `ImproveInfo.name` 没有出现在子树里。
     */
    private fun retryResolve(holder: Any, adapter: Any?, pos: Int, view: View) {
        if (retryLeft <= 0) return
        retryLeft--
        mainHandler.post { runCatching { retryPaint(holder, adapter, pos, view) } }
    }

    private fun retryPaint(holder: Any, adapter: Any?, pos: Int, view: View) {
        if (!on()) return
        lastRecon = ""
        val d = dataOf(holder, adapter, pos, view) ?: return
        val i = SnsDeletedTagLogic.snsIdOf(d) ?: return
        val prev = bound[view]
        val b = if (prev != null && prev.id == i && prev.pos == pos) prev else {
            // pos 也要写进去：否则下一拍 [invalidateIfReused] 认不出这个 Binding 是哪一格
            Binding(i).also { it.hint = timeHintOf(d); it.pos = pos; bound[view] = it }
        }
        resolved++
        ensureSweep()
        paint(view, b, deleted())
    }

    // ------------------------------------------------------------------ holder → itemView

    private val viewFieldCache = Collections.synchronizedMap(HashMap<String, Field?>())

    /**
     * holder → item 根 View。
     *
     * ⚠️ **必须排除 RecyclerView 本身**：holder 上普遍持有指向列表的 rv 引用
     * （实测 `ki4/m0->F : RecyclerView`），选到它 ⇒ 标签加到列表容器上，item 上一个都没有。
     * ✅ 优先选**声明在 `androidx.recyclerview.widget.*`（ViewHolder 本体）上的 View 字段**
     *    —— 实测字段名是 `d`，反汇编里紧跟 `const-string 'itemView'`，最可靠。
     */
    private fun itemViewOf(holder: Any): View? {
        val key = holder.javaClass.name
        if (viewFieldCache.containsKey(key)) {
            val f = viewFieldCache[key] ?: return null
            return runCatching { f.get(holder) as? View }.getOrNull()
        }
        val cands = ArrayList<Pair<Field, View>>()
        var c: Class<*>? = holder.javaClass
        var up = 0
        while (c != null && up < 6) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (!View::class.java.isAssignableFrom(f.type)) continue
                val v = runCatching { f.isAccessible = true; f.get(holder) as? View }.getOrNull()
                if (v != null && !isRv(v)) cands += f to v
            }
            c = c.superclass
            up++
        }
        val best = cands.firstOrNull { it.first.declaringClass.name.startsWith("androidx.recyclerview.widget.") }?.second
            ?: cands.firstOrNull { it.second.parent?.javaClass?.name.orEmpty().contains("RecyclerView") }?.second
            ?: cands.filter { it.second is ViewGroup }
                .maxByOrNull { it.second.width.toLong() * it.second.height }?.second
            ?: cands.firstOrNull()?.second
        viewFieldCache[key] = cands.firstOrNull { it.second === best }?.first
        if (best == null) {
            logOnce("SNS_DELTAG: no View field on holder ${holder.javaClass.name}")
        } else {
            logOnce("SNS_DELTAG holder=${holder.javaClass.simpleName} pick=${best.javaClass.simpleName}" +
                " cands=[" + cands.take(6).joinToString(";") {
                    "${it.first.declaringClass.simpleName}.${it.first.name}:${it.second.javaClass.simpleName}"
                } + "]")
        }
        return best
    }

    private fun isRv(v: View): Boolean = v.javaClass.name.contains("RecyclerView")

    // ------------------------------------------------------------------ holder → 数据对象

    /** 探测预算：每个 holder 类最多全量探测这么多次，扣完就认命（否则每次 bind 都要反射扫字段） */
    private const val PROBE_MAX = 12

    /** 一层展开时最多看多少个字段 */
    private const val UNWRAP_MAX = 24

    /**
     * path step 语法（step 之间用 `;` 连）：
     * - `@` = `adapter.data[pos]`；`@-1` / `@2` = `adapter.data[pos-1]` / `[pos+2]`
     *   —— **带偏移的 `@` 只有在通过 View↔数据对账之后才会被生成**，见 [reconcileAt]
     * - `z` = 调 holder 的无参 getter `z()`；其余单个名字 = 读 holder 的同名字段
     * - `f:xxx` = 读上一步结果的字段 `xxx`
     * - `m:xxx` = 调上一步结果的无参方法 `xxx`
     */

    /**
     * 一条**经过验证**的数据来源描述（[dataPathCache] 的 value）。
     *
     * ## R37-1 为什么不能只存一条路径字符串
     * 原来就是 `holder 类名 -> "路径串"`，而且**没有任何失效条件**。于是「某一格侥幸通过」
     * 会把取数方式固化给**同 holder 类的后续所有 View**。真机后果：封面 header 上那次 `@`
     * 被缓存下来，之后无论这一格是 header 还是普通动态，都按同一个偏移去 `adapter.data` 里取
     * ⇒ header 与第一条动态长期错绑（指纹 `host=FrameLayout hint='25分钟前'`）。
     * ⇒ value 必须带**证据**：用哪个偏移（编码在 path 里）+ 当时对账命中的作者名；
     *    下次命中缓存时用同样的证据**重新对账**（[srcStillValid]），失配立刻作废重探。
     *
     * ⛔ 这里**不许**存 View / holder / 数据对象本体：这是个进程级长寿命 Map，value 一旦
     *    强引用 itemView（holder 必然强引用自己的 itemView）⇒ RecyclerView 条目永不回收。
     *    ⇒ 只存 String / Int 这类可重放的**描述**，真正的数据每次都重新从 live 对象上取。
     *
     * ⚠️ 保持纯数据类（不引用外层 object 的成员）：嵌套类访问外层单例的成员要走合成访问器，
     *    编译能不能过取决于 Kotlin 版本，没必要为省一个函数冒这个风险。
     *
     * @property path step 路径串（语法见上面的 KDoc）
     * @property name 对账命中候选的作者名；`""` = 这条路径不依赖 position（holder 自带的数据）
     */
    private class DataSrc(val path: String, val name: String)

    /**
     * holder 类 × adapter 类 → 已验证的数据来源。
     *
     * ⚠️ key 里带上 adapter 类：同一个 holder 类会被不同 adapter（时间线 / 个人相册 / 搜索结果）
     *    复用，各自 `data` 的排列与 header 结构并不相同 ⇒ 只用 holder 类名做 key，会把在一个界面
     *    上校准出来的偏移搬到另一个界面上用。
     */
    private val dataPathCache = Collections.synchronizedMap(HashMap<String, DataSrc>())

    private val probeLeft = Collections.synchronizedMap(HashMap<String, Int>())

    /** 「全失败还是首条失败」靠它区分：每个 holder 类一组 seen/fail */
    private class Tally {
        var seen = 0
        var fail = 0
    }

    private val dataTally = Collections.synchronizedMap(HashMap<String, Tally>())

    /** 一次性 dump 去重键：角色 + 类名 */
    private val dumped = Collections.synchronizedSet(HashSet<String>())

    /**
     * holder → 数据对象。
     *
     * ## R35-1b 修的病根：负缓存把「瞬时读错」固化成「永久读不到」
     * 一次 bind 会触发**多条** hook（`ki4/i0.onBindViewHolder` 体内依次
     * `k0`→`b1`、`i0`→`a1`、`l0`→`c1`）。dex 里只有 `c1/d1` 把真 item 写进 `m0.E`
     * （`E = (Lki4/c) data.get(pos)`），先跑的 `a1/b1` 写进去的是第 3 形参
     * `Lki4/r;`（bind-info，`toString()` 是默认的 `ki4.r@xxx`）。
     * 旧实现在第一次失败后就给该类打上**永久**负缓存，于是后面 `c1` 把 `E` 写对了
     * 也永远读不到 ⇒ 真机 100% `no snsId on holder`。这里是那个 bug 的修法：
     * ① 负缓存改成**有预算的探测**；② 候选里加一条不依赖 `E` 的独立来源（`@`）。
     *
     * @param adapter  被 hook 方法的 `thisObject`（adapter），用来走 `@` 这条独立路径。
     * @param pos      绑定下标（`c1/d1/a1/b1` 的形参[1]，恒为 int）。
     * @param itemView holder 当前对应的 item 根 View —— **`@` 路径的对账要用它**，没有它
     *                  就退化成「只要 `data[pos]` 能解析出 snsId 就采用」的 R37 错绑。
     */
    private fun dataOf(holder: Any, adapter: Any?, pos: Int, itemView: View): Any? {
        val key = cacheKey(holder, adapter)
        bumpSeen(key)
        val src = dataPathCache[key]
        if (src != null) {
            val v = walkPath(holder, adapter, pos, src.path)
            if (v != null) {
                // 缓存只能说明「上次这么取过并且那次对账通过」，证明不了这一格现在装的还是
                // 那条动态 ⇒ 必须复查。少这一句，一次侥幸通过就变成永久事实（见 [DataSrc]）。
                if (srcStillValid(src, itemView, adapter, pos)) return v
                dataPathCache.remove(key)
            }
        }
        if (!takeProbe(key)) {
            logDataFail(key)
            return null
        }
        val found = probePath(holder, adapter, pos, itemView)
        if (found == null) {
            logDataFail(key)
            // 走到这里 = 没有任何候选通过 View↔数据对账 ⇒ **绝不**回落去取未经校验的数据：
            // 「猜一个 id 也比一个都画不上好」这个想法在本项目已经付过一次学费
            // （见 Binding 上面那条 R31h 事故的注释）。
            return null
        }
        val s = DataSrc(found.first, found.second)
        dataPathCache[key] = s
        LogX.i("SNS_DELTAG path ${holder.javaClass.simpleName} -> ${s.path} " +
            "name='${SnsDeletedTagLogic.brief(s.name, 12)}'")
        return walkPath(holder, adapter, pos, s.path)
    }

    /**
     * [dataPathCache] 的 key：holder 类 × adapter 类（为什么必须带 adapter 类见 [dataPathCache]）。
     */
    private fun cacheKey(holder: Any, adapter: Any?): String =
        "${holder.javaClass.name}|${adapter?.javaClass?.name ?: "-"}"

    /**
     * 这次命中缓存时，缓存里那份**已验证关联**还成立吗。
     *
     * 为什么必须每次复查：下拉刷新、插入/移除头部、时间线与个人相册混排都会改变整体偏移，
     * 而 holder 类名不变。只把 `list.size` 塞进 key 也挡不住 —— header 与动态混排时 size
     * 完全可能一模一样（所以这里不赌指纹，直接把当时的对账证据重算一遍）。
     *
     * ⚠️ 只复核**依赖 position 的**那类路径（`@…`）：holder 自带的数据（`E`/`z`/`D`…）是
     *    同一个 holder 自己的字段，而 holder 与 itemView 天生一一对应，它不可能变成
     *    「隔壁那一格的数据」，也没有偏移可言。
     */
    private fun srcStillValid(src: DataSrc, itemView: View, adapter: Any?, pos: Int): Boolean {
        if (!src.path.startsWith("@")) return true
        val pick = reconcileAt(adapter, pos, itemView) ?: return false
        // ⛔ 只取**首个 step** 解偏移：path 可能是两步（`@-1;f:xxx`），整串喂给 atOffset
        //    会解出 0，于是每次命中缓存都判失配 ⇒ remove→全量重探→又存同一条，
        //    12 次探测预算烧光后这一屏**永远不打标**（静默失效，只有一条被节流的日志）。
        return pick.first == atOffset(src.path.substringBefore(";")) && pick.second == src.name
    }

    /**
     * 全量探测：按优先级试所有候选，返回第一条**通过 View↔数据对账**的路径 + 对账证据。
     *
     * ⛔ `@`（`adapter.data[pos]`）不是「能解析出 snsId 就采用」：它是**唯一**依赖 position 的
     *    来源，而 RecyclerView 的 position 在有 header / 封面 / 广告卡时会整体偏移 ⇒ 必须先对账。
     *
     * @return `(路径, 命中候选的作者名)`；holder 自带的路径作者名留空（无需对账）。
     */
    private fun probePath(holder: Any, adapter: Any?, pos: Int, itemView: View): Pair<String, String>? {
        dumpFields(holder, "holder")
        for (h in arrayOf("@", "z", "E", "D")) {
            if (h == "@") {
                // ⛔ R37-1 的病根就在这条分支：原来只要 `adapter.data[pos]` 能解析出 snsId
                //   就返回 `@`，于是任何装着这条数据的 View（封面 header 也算）都被当成
                //   「这条动态」。header 没时间控件时只表现为漏画；同一偏移一旦落到普通动态，
                //   就会把邻居的删除状态画到错误的动态上。⇒ 先过 [reconcileAt] 的位置对账。
                val pick = reconcileAt(adapter, pos, itemView) ?: continue
                val cand = adapterItem(adapter, pos + pick.first) ?: continue
                dumpFields(cand, "item(@${pick.first})")
                if (SnsDeletedTagLogic.snsIdOf(cand) != null) return "@${pick.first}" to pick.second
                val sub = unwrap(cand) ?: continue
                if (SnsDeletedTagLogic.snsIdOf(sub.second) != null)
                    return "@${pick.first};${sub.first}" to pick.second
                continue
            }
            val v = stepOf(holder, adapter, pos, h) ?: continue
            dumpFields(v, "item($h)")
            if (SnsDeletedTagLogic.snsIdOf(v) != null) return h to ""
            val sub = unwrap(v) ?: continue
            if (SnsDeletedTagLogic.snsIdOf(sub.second) != null) return "$h;${sub.first}" to ""
        }
        return null
    }

    /**
     * `adapter.data` 这条独立来源的**位置对账**（三条原则，与 `SnsTag.resolve` 同口径）。
     *
     * ① **精确位置优先**：先试 `data[pos]`；② **±2 窗口**：对不上就在
     * `pos, pos+1, pos-1, pos+2, pos-2` 里找作者名能对上 itemView **可见**文本的候选；
     * ③ **唯一命中**：窗口里通过的个数 ≠ 1 ⇒ 返回 null（偏移猜错比不打标糟得多）。
     *
     * ⚠️ 作者文本为空串时 [SnsDeletedTagLogic.matchName] 判 **false**，这里因此返回 null
     *    —— **不是**跳过校验。渲染是 suspend 的（时间/昵称都可能一拍之后才填上），
     *     caller 侧有「延后一拍重试」（[retryResolve]）兜底，等文本填上后再判一次。
     *
     * @return `(已校准偏移, 命中候选的作者名)`；null = 没有唯一可信候选
     *         （细粒度原因写进 [lastRecon]，别让 caller 用粗的 `nodata` 盖掉）。
     */
    private fun reconcileAt(adapter: Any?, pos: Int, itemView: View): Pair<Int, String>? {
        val list = dataListOf(adapter) ?: return null
        if (pos < 0 || list.isEmpty()) return null
        val texts = visibleTexts(itemView)
        val pick = SnsDeletedTagLogic.pickWindow(pos, list.size) { i ->
            val cand = list[i] ?: return@pickWindow false
            SnsDeletedTagLogic.snsIdOf(cand) != null &&
                SnsDeletedTagLogic.matchName(SnsDeletedTagLogic.nameOf(cand), texts)
        }
        val idx = pick.index
        // 诊断必须区分「0 命中」与「歧义」：前者多半是渲染还没完成（延后重试即可），
        // 后者是同作者相邻动态导致的无解歧义（只能不打标）。混成一条就没法排障。
        lastRecon = if (pick.hits == 0) {
            "noname(pos=$pos/${list.size} texts=${texts.size})"
        } else if (idx < 0) {
            "ambig${pick.hits}(pos=$pos/${list.size})"
        } else {
            ""
        }
        if (idx < 0) return null
        val cand = list[idx] ?: return null
        return (idx - pos) to SnsDeletedTagLogic.nameOf(cand).orEmpty()
    }

    /**
     * itemView 子树里的**可见非空**文本（给作者名对账用）。
     *
     * ⚠️ 上限 20 条 / 深度 8：作者名 TextView 常常埋在内容区之后，取太少 ⇒ 名字压根不在列表里
     *    ⇒ 对账恒不过 ⇒ 静默地一个标签都不打（比报错难查得多）。
     * ⚠️ 只要 `visibility != VISIBLE` 就跳过：GONE 的子树常常留着复用前的旧文本，
     *    拿它去对账会对上**上一条动态**的作者 —— 正是本轮要防的串味。
     *    （不用 `isShown`：绑定期 View 可能还没 attach，`isShown` 恒 false，会把正常绑定误判掉。）
     */
    private fun visibleTexts(root: View, max: Int = 20): List<String> {
        val out = ArrayList<String>(max)
        fun walk(v: View, d: Int) {
            if (out.size >= max || d > 8) return
            if (v is TextView) {
                val t = runCatching { v.text?.toString() }.getOrNull()?.trim()
                if (!t.isNullOrEmpty() && v.visibility == View.VISIBLE) out.add(t)
            }
            if (v is ViewGroup) {
                val n = minOf(v.childCount, WALK_KIDS)
                for (i in 0 until n) {
                    val c = runCatching { v.getChildAt(i) }.getOrNull() ?: continue
                    walk(c, d + 1)
                }
            }
        }
        runCatching { walk(root, 0) }
        return out
    }

    /**
     * 一层展开：先扫实例字段（无副作用），再退到白名单无参 getter。
     *
     * ⛔ 只接受 [SnsDeletedTagLogic.snsIdOf] 判得过的值 —— 形状校验（15~20 位纯数字 +
     * `snsID=` 前缀）是防「打错标签」的最后一道闸，宁可打不上也不能打错。
     */
    private fun unwrap(v: Any): Pair<String, Any>? {
        var c: Class<*>? = v.javaClass
        var up = 0
        var n = 0
        while (c != null && up < 3 && n < UNWRAP_MAX) {
            for (f in c.declaredFields) {
                if (n >= UNWRAP_MAX) break
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.type.isPrimitive) continue
                if (View::class.java.isAssignableFrom(f.type)) continue
                val x = runCatching { f.isAccessible = true; f.get(v) }.getOrNull() ?: continue
                n++
                if (SnsDeletedTagLogic.snsIdOf(x) != null) return "f:${f.name}" to x
            }
            c = c.superclass
            up++
        }
        // 白名单 getter：`qh3/a.k()` → `oh3/p`（ImproveSnsInfo）。名字是混淆名，放最后兜底。
        for (name in arrayOf("k")) {
            val x = runCatching { XposedHelpers.callMethod(v, name) }.getOrNull() ?: continue
            if (SnsDeletedTagLogic.snsIdOf(x) != null) return "m:$name" to x
        }
        return null
    }

    private fun walkPath(holder: Any, adapter: Any?, pos: Int, path: String): Any? {
        val parts = path.split(";")
        var cur: Any? = stepOf(holder, adapter, pos, parts[0])
        var i = 1
        while (i < parts.size) {
            val from = cur ?: return null
            val s = parts[i]
            cur = when {
                s.startsWith("f:") ->
                    runCatching { XposedHelpers.getObjectField(from, s.substring(2)) }.getOrNull()
                s.startsWith("m:") ->
                    runCatching { XposedHelpers.callMethod(from, s.substring(2)) }.getOrNull()
                else -> null
            }
            i++
        }
        return cur
    }

    /**
     * 单个 step：`@<off>` = `adapter.data[pos+off]`（`@`/`@0` = 原位），`z` = 无参 getter，其余 = 字段名。
     *
     * ⚠️ 偏移是这个 step 的一部分，不是别处记的派生量：这么写之后「这条数据来源用的哪个偏移」
     * 就只能在**路径本身**里体现，缓存/日志/重放三者不可能各说一套（见 [DataSrc]）。
     */
    private fun stepOf(holder: Any, adapter: Any?, pos: Int, step: String): Any? {
        if (step.startsWith("@")) return adapterItem(adapter, pos + atOffset(step))
        if (step == "z") return runCatching { XposedHelpers.callMethod(holder, "z") }.getOrNull()
        return runCatching { XposedHelpers.getObjectField(holder, step) }.getOrNull()
    }

    /** `@` step 里带的偏移：`@`/`@0` = 0，`@-1` = -1，`@2` = +2。解析不出来一律按原位。 */
    private fun atOffset(step: String): Int = step.substring(1).toIntOrNull() ?: 0

    /** adapter → 数据列表。字段 `data` **没被混淆**（见 [adapterItem] 的 dex 取证）。 */
    private fun dataListOf(adapter: Any?): java.util.List<*>? {
        val a = adapter ?: return null
        return runCatching {
            XposedHelpers.getObjectField(a, "data") as? java.util.List<*>
        }.getOrNull()
    }

    /**
     * 与 `m0.E` **完全无关**的独立来源：`WxRecyclerAdapter->data` 的第 index 项。
     *
     * dex：`WxRecyclerAdapter.c1(m0,I)` 体内就是
     * `iget-object … WxRecyclerAdapter->data : Ljava/util/ArrayList;` + `ArrayList.get(I)`，
     * 这正是微信自己取 item 的方式，不受「`E` 被 `a1/b1` 临时写成 bind-info」影响。
     * 字段名 `data` **没被混淆**，比 `E`/`z` 稳。
     *
     * @param index **绝对**下标（调用方负责先把已校准的偏移加上去），不是 position。
     */
    private fun adapterItem(adapter: Any?, index: Int): Any? {
        val a = adapter ?: return null
        if (index < 0) return null
        val list = dataListOf(a) ?: return null
        if (index >= list.size) return null
        return runCatching { list[index] }.getOrNull()
    }

    private fun takeProbe(key: String): Boolean {
        val left = (probeLeft[key] ?: PROBE_MAX) - 1
        probeLeft[key] = left
        return left >= 0
    }

    private fun bumpSeen(key: String) {
        synchronized(dataTally) { tallyOf(key).seen++ }
    }

    /**
     * 失败日志：**每类带累计计数 + 节流**。
     *
     * 形如 `no snsId on holder ki4.m0 (fail=3/seen=5)`：既能看出「全失败还是首条失败」，
     * 又不会像以前那样被 [logOnce] 的全局 5 条上限吞掉后面的样本。
     */
    private fun logDataFail(key: String) {
        var log = false
        var f = 0
        var s = 0
        synchronized(dataTally) {
            val t = tallyOf(key)
            t.fail++
            f = t.fail
            s = t.seen
            log = f <= 3 || f == 5 || f == 10 || f % 50 == 0
        }
        if (log) LogX.w("SNS_DELTAG: no snsId on holder $key (fail=$f/seen=$s)")
    }

    private fun tallyOf(key: String): Tally =
        dataTally[key] ?: Tally().also { dataTally[key] = it }

    /**
     * 一次性全量 dump：每个（角色 × 类名）只打一次。
     *
     * 真机 `grep "SNS_DELTAG fields"` 能一次拿到字段名/类型/真值，不用再反复猜。
     * 输出两行：
     * ```
     * SNS_DELTAG fields cls=<类名> tag=<角色> names=[...] types=[...]
     * SNS_DELTAG vals tag=<角色> vals=[...]
     * ```
     *
     * ⚠️ 值一律用 [SnsDeletedTagLogic.brief] 截断到 80 字符、最多 40 个字段：
     * LogX 是主线程同步写盘，一次刷爆会拖垮滚动。
     */
    private fun dumpFields(obj: Any, tag: String) {
        val k = "$tag|${obj.javaClass.name}"
        if (!dumped.add(k)) return
        val names = ArrayList<String>()
        val types = ArrayList<String>()
        val vals = ArrayList<String>()
        var c: Class<*>? = obj.javaClass
        var up = 0
        while (c != null && up < 4 && names.size < 40) {
            for (f in c.declaredFields) {
                if (names.size >= 40) break
                if (Modifier.isStatic(f.modifiers)) continue
                val v = runCatching { f.isAccessible = true; f.get(obj) }.getOrNull()
                names += f.name
                types += f.type.simpleName
                vals += SnsDeletedTagLogic.brief(runCatching { v.toString() }.getOrDefault("null"))
            }
            c = c.superclass
            up++
        }
        LogX.i("SNS_DELTAG fields cls=${obj.javaClass.name} tag=$tag " +
            "names=[${names.joinToString(",")}] types=[${types.joinToString(",")}]")
        LogX.i("SNS_DELTAG vals tag=$tag vals=[${vals.joinToString(" | ")}]")
    }

    // ------------------------------------------------------------------ 呈现

    /**
     * 把 [itemView] 的时间文案恢复到「该有」的状态：在 [tagged] 里就追加标记，
     * 不在就把标记剥掉、还原成纯原文。
     *
     * @param itemView item 根视图。
     * @param snsId    这条动态的 id；`0` = 未知/无效。
     * @param tagged   已删动态 id 并集清单。
     *
     * ⚠️ `snsId == 0` 时不再无条件还原：[SnsTag] 解析失败会传 0 进来，那会把本类自己
     * 打上的标记又擦掉（两条链路打架）。这时改用本类 [bound] 里自己解析出的 id
     * —— 我们自己解析成功过就用我们的，两边都没解析出来才还原。
     *
     * ## ⛔ 为什么「本类解析出的 id」优先级高于入参 `snsId`（别把这个 if 顺手简化掉）
     * 两条链的 id 来源不同：本类取自 holder / 数据本体（R35 dex 取证，见本文件顶部 KDoc），
     * [SnsTag] 取自「`getData()` 下标 ±2 的窗口 + 作者名对账」（`_r35_sns_tag.md:1358`
     * 自己写着「偏移校准是**猜**的」）。而时序上 [SnsTag] 那一步跑在本类 `c1/d1` 之后、
     * `bindViewHolder` 之前 ⇒ 每次 bind 的「最后写入者」都是 [SnsTag]。
     * 于是旧实现（无条件 `Binding(snsId)`）会用那条**猜出来的** id 整体替换本类的
     * `Binding`，连 `tvId` / `hint` 一起丢掉 ⇒ 本类自己的 sweep 从此按错误的 id 判定，
     * 第 ③ 级解析（`hint` 精确比对）也永久失效。⇒ 本类有非 0 id 时**只 paint 不写 bound**
     * （差一条限流日志，真机 grep `id clash` 就能定性两条链谁对）。
     *
     * ## R37-1：「本类还没有」时也不再无条件采纳外部 id
     * 上面那条规则守住了「不要覆盖」，却没守住「不要新增」：本类没有 Binding 时，
     * 任何拿进来的非 0 id 都会被登记成一条动态并排进 2 秒 sweep。封面 header 这类
     * **不是动态**的容器一旦被登记，就每 2 秒重试一次注定失败的「找时间控件」
     * ⇒ `missedTime` 被单个 View 刷到 43、`bound` 里躺着的却不是真动态。
     * ⇒ 本类没有 Binding 时，先要求这个 View **结构性像一条动态**（[hasTimeControl]），
     *    不通过就当场放弃，连 `bound` 都不写。
     *
     * ## ⛔ 为什么**渲染**仍然用入参 `tagged`，不用本类 `deleted()`
     * 入参是 [SnsTag] 的 `del + forceSet`（SnsTag.kt:673），`snstag fake / mark` 探针
     * 设的正是**它那份** `forceSet` —— 真机「不删动态就能验渲染」走的是这条路径。
     * 换成 `deleted()` 的话探针期间本类判定恒 false ⇒ `paint` 走 `strip` ⇒ **探针完全失效**。
     * 判定用谁的 id、渲染用谁的清单，这两件事在这里是**故意**不一致的。
     *
     * 颜色/字号一律不动：时间控件是代码 new 出来的 `ImproveTextView`，改样式风险高；
     * 追加的文字自动继承当前 TextView 的灰色小字样式，无需也不应单独设色。
     */
    fun apply(itemView: View, snsId: Long, tagged: Set<Long>) {
        // 懒装：没人调 install 时，靠宿主 View 反查 classLoader 也能把新挂载点装上
        if (!installed.get()) {
            runCatching { itemView.javaClass.classLoader?.let { ensureInstalled(it) } }
        }

        if (snsId != 0L) {
            val prev = bound[itemView]
            if (prev != null && prev.id != 0L) {
                if (prev.id != snsId) {
                    // 两条链对同一格给出不同 id：解析必有一边是错的，真机靠这条定性是谁错
                    warnOnce("SNS_DELTAG: id clash own=" +
                        java.lang.Long.toUnsignedString(prev.id) + " ext=" +
                        java.lang.Long.toUnsignedString(snsId))
                }
                // ⚠️ 渲染必须用入参 tagged：那是 SnsTag 的 del + forceSet，`snstag fake` 靠它验渲染
                paint(itemView, prev, tagged)
                return
            }
            // ⛔ R37-1：外部 id 不再是「无条件写一份 Binding」。
            //   原来只要拿到非 0 id 就 `Binding(snsId)` 写进 bound，于是连封面 header 这种
            //   **根本不是动态**的 View 也被当成一条动态登记下来，并且被 2 秒 sweep 反复重判
            //   —— 它上面根本没有时间控件，每一次重判都必然 miss（真机 `missedTime=43`
            //   就这样只由 1 个 View 刷出来）。而真正被 fake 的那条动态反而没有 Binding 可用。
            //   ⇒ 先过结构性闸门 [hasTimeControl]：连时间控件都没有的宿主，这辈子也画不上标记，
            //   登记它只会污染 bound 和 miss 计数，不如当场放弃。
            if (!hasTimeControl(itemView)) {
                lastMiss = "nodynamic(${itemView.javaClass.simpleName})"
                logKeyOnce("nodynamic:${itemView.javaClass.name}",
                    "SNS_DELTAG: skip non-timeline host=${itemView.javaClass.name}", warn = true)
                return
            }
            val b = Binding(snsId).also { bound[itemView] = it }
            // 必须注册 sweep：`ImproveTimelineItemMeasure.d()`（realTimeUpdate）会周期性地把
            // 纯时间原文写回控件（本文件 sweep 的 KDoc 已记），只画一次会被微信自己冲掉；
            // 补上这一层之后，被 forced 的那条动态才能在 30 秒 force TTL 内稳定看到标记。
            ensureSweep()
            // ⚠️ 渲染必须用入参 tagged：那是 SnsTag 的 del + forceSet，`snstag fake` 靠它验渲染
            paint(itemView, b, tagged)
            return
        }
        if (!on()) {
            restore(itemView)
            return
        }
        val own = bound[itemView]
        if (own == null || own.id == 0L) {
            restore(itemView)
            return
        }
        // 兜底用的是本类自己的 Binding：它已经在 bind 时按 pos 做过复用失效（[invalidateIfReused]），
        // 所以这里拿到的 id 一定属于「这个 View 此刻装的那条动态」，不会串到上一条。
        paint(itemView, own, deleted())
    }

    /**
     * 判定 + 计数 + 落笔。
     *
     * ⚠️ 两个计数不是一回事，别合并：
     * - `hits` —— **判定**命中的 paint 次数（在取时间控件**之前**就加了，取不到也算）；
     * - `painted` —— 真的把标记写进 TextView 的次数（只有末尾 `tv.text = want` 那句
     *   真的执行了才加，「已经带着标记、无需改写」不算新的一次）。
     * 分开的理由：`hits>0` 曾经被当成「界面上画上了」的真机判据，而取不到时间控件时
     * `hits` 照样涨（真机 `hits=0→1` 但界面上什么都没有就是这样误判的）。
     * ⇒ 验收只看 `painted`（见 [stat] 的「看数顺序」）。`hits` 保留是因为文档与
     * 真机验收脚本已经用了这个名字，且它仍然回答「清单/符号对不对」这另一个问题。
     */
    private fun paint(host: View, b: Binding, tagged: Set<Long>) {
        val hit = SnsDeletedTagLogic.isTagged(b.id, tagged)
        if (hit) hits++
        dumpTree(host)
        val tv = timeTextOf(host, b)
        if (tv == null) {
            noteMissTime(host, b, hit)
            return
        }
        val cur = tv.text?.toString() ?: ""
        val base = strip(cur)
        val want = if (hit) {
            SnsDeletedTagLogic.appendTag(base)
        } else {
            base
        }
        // 幂等：text 一致就不 setText，避免无谓重绘（也顺带挡掉「setText 又触发重入」）
        if (want != cur) {
            tv.text = want
            // 只有这里才叫「画上了」：上面那一句才是真正改了界面的地方
            if (hit) painted++
        }
    }

    /**
     * 「取不到时间控件」的计数 + 告警（R37-1 重写）。
     *
     * ## 为什么拆成 [missedTimeHit] / [missedTimeRestore] 两路
     * 原来共用 `missedTime` 一个总数，而 `paint()` 对 `hit=false` 的格子也会去找时间控件
     * （为了剥掉可能残留的标记）⇒ 封面 header 那种没有时间行的容器每 2 秒 sweep 一次就加一，
     * 一个 View 就能把总数刷到 43，完全没法反推「几条动态画不上」。
     * ⇒ 命中却画不上（= 真故障，看 View 结构漂移）与非命中时的还原探索（= 正常结果）彻底分开。
     *
     * ## 为什么按 Binding（一格的一次绑定）去重
     * 同一个 View 被 2 秒 sweep 反复重判是**设计如此**（扛 realTimeUpdate 写回），不加去重
     * 的计数永远在涨，看不出「是 1 个 View 在反复 miss 还是 43 条动态都没画上」。
     * ⚠️ 去重挂在 Binding 而不是「这个 View 一辈子只计一次」：View 被复用给另一条动态时
     *    Binding 是全新对象 ⇒ 新内容照会计一次，不会把真实故障也一起抹掉。
     *
     * ## 为什么日志按 host 类名分桶
     * 原来是 `n <= 5` 的全局额度：第一个 View 就把 5 条吃光，之后再有**别的结构**的 View
     * 失败，一行都看不到 ⇒ 排障的人只能看到「最先失败的那个」，且第二次进朋友圈依然如此。
     */
    private fun noteMissTime(host: View, b: Binding, hit: Boolean) {
        if (b.missCounted) return
        b.missCounted = true
        missedTime++
        if (hit) missedTimeHit++ else missedTimeRestore++
        logKeyOnce("notv:${host.javaClass.name}",
            "SNS_DELTAG: no time TextView ${if (hit) "HIT" else "restore"} " +
                "snsId=${java.lang.Long.toUnsignedString(b.id)} " +
                "host=${host.javaClass.name} hint='${b.hint}'", warn = true)
    }

    /** 只做还原（剥掉可能残留的标记），不判定 */
    private fun restore(host: View) {
        val tv = timeTextOf(host, bound[host]) ?: return
        val cur = tv.text?.toString() ?: return
        val base = strip(cur)
        if (base != cur) tv.text = base
    }

    // ------------------------------------------------------------------ itemView → 时间 TextView

    /**
     * 子树遍历深度上限（含根）。
     *
     * ⛔ R35-1d 真机修正：原来给 6 是**不够的**。23:41 那包在真机上把 View 树 dump 出来，
     * 动态 item 的实际结构是
     * `lh3.i3 > RelativeLayout > LinearLayout > ConstraintLayout > ImproveContentView > …`
     * —— 到 `ImproveContentView` 就已经第 5 层，时间行还在它下面，`WALK_MAX=6` 扫不到，
     * 表现为 `no time TextView … hint='25分钟前'`（hint 都拿到了，就是搜不到控件）。
     * 给到 12：实测最深的一条到 9 层，留 3 层余量。
     * ⚠️ 代价可控：成功一次就把 tvId 记进 [bound]，之后走 O(1) findViewById；
     *    只有「一直失败」的根才会每次重扫，而那种根本来就没几个。
     */
    private const val WALK_MAX = 12

    /** 每个节点最多看几个孩子（防九宫格那种宽树把主线程拖住） */
    private const val WALK_KIDS = 16

    /** View 树 dump 的深度（只打这么多层，够定位）—— 跟着 [WALK_MAX] 抬高，否则 dump 出来看不到时间行 */
    private const val TREE_DEPTH = 9

    /**
     * 找 `getPublishTimeText()` 时最多往上走几层父类。
     *
     * dex 实测真机 item 根 `Llh3/g2;` 到声明该方法的 `Llh3/l1;` 有 **9** 层，
     * 给 16 留足余量（微信每加一层中间类都不会失效）。
     */
    private const val SUPER_MAX = 16

    /** 类名 → 该类（含整条父类链）声明的 `getPublishTimeText()`，null = 没有（负缓存也存） */
    private val timeMethodCache = Collections.synchronizedMap(HashMap<String, Method?>())

    /** View 树 dump 去重键：根 View 类名 */
    private val treeDumped = Collections.synchronizedSet(HashSet<String>())

    /**
     * item 根视图 → 时间 TextView（R35-1c 重写）。
     *
     * ## 为什么原来的两步（`getPublishTimeText()` + 字段 `o`）必然失败
     * 那两个都是**方法/字段在 item 根 View 身上**的假设。dex 事实（classes3/classes4）：
     * - `getPublishTimeText()` 全 dex 只有两个类声明：`Llh3/l1;`
     *   （`…improve.item.ImproveTimelineItemBuild`，extends LinearLayout，
     *   返回 `android.widget.TextView`，体内 `iget-object l1->o`）与 `Lxh3/o2;`
     *   （`…ui.item.improve.TimelineItemBuild`，返回 `PLSysTextView`，体内 `iget o2->p`）；
     * - holder `Lki4/m0;` **没有**这个方法（它只有 `<init>/B/C/F/G/H/I/J/K/z`，
     *   其中 `F(I) = itemView.findViewById(I)`）；
     * - 而真机传进来的 host 是**裸 `android.widget.FrameLayout`**，`itemViewOf` 没选错 ——
     *   `c3.d` 就是微信自己认定的 itemView（`m0.F(I)` 反汇编第一句就是 `iget c3->d`）。
     *
     * ⇒ 病根不是「该传 holder」，是「时间控件**不在根 View 上**」。改成遍历子树。
     *
     * ## 四级解析（从稳到险）
     * ① 已缓存的资源 id → `host.findViewById(tvId)`（O(1)，sweep 走这条）；
     * ② 子树里任一节点（含整条父类链）声明了 `getPublishTimeText()` → 调它；
     * ③ 子树里文本 == [Binding.hint]（数据对象 `time:` 原文）的 TextView；
     * ④ 子树里文本长得像发布时间的 TextView（[SnsDeletedTagLogic.isTimeLike]，最险，最后才试）。
     *
     * ②③ 成功就把资源 id 记回 [Binding.tvId]，之后不再遍历；
     * ⛔ **④ 成功也不记**（[TIME_LEVEL_FALLBACK]）—— 它是猜的，缓存了就把误判固化。
     *
     * @param b 绑定关系，可为 null（[SnsTag] 那条老链路只给 View 和 id）。
     */
    private fun timeTextOf(host: View, b: Binding?): TextView? {
        val id = b?.tvId ?: View.NO_ID
        if (id != View.NO_ID) {
            val t = runCatching { host.findViewById<View>(id) as? TextView }.getOrNull()
            if (t != null) return t
            if (b != null) b.tvId = View.NO_ID   // 复用后 id 失效，退回全量解析
        }
        val hit = resolveTime(host, b?.hint.orEmpty()) ?: return null
        // ⛔ ④ 级（「长得像时间」的兜底）**绝不写进 tvId** —— 见 [resolveTime] 的 KDoc：
        //    误命中的后果是把灰标打到错误的控件上，一旦缓存就从「错一次」变成「永远错」。
        //    只让它在这一轮生效，下一轮重新判，至少有机会自愈。
        if (hit.second >= TIME_LEVEL_FALLBACK) {
            warnOnce("SNS_DELTAG: tv by level-4 fallback cls=${hit.first.javaClass.simpleName} " +
                "txt='${SnsDeletedTagLogic.brief(runCatching { hit.first.text?.toString() }.getOrNull(), 12)}'")
        } else if (b != null && hit.first.id != View.NO_ID) {
            b.tvId = hit.first.id
        }
        return hit.first
    }

    /**
     * 这个 View 是不是**真动态**的 item 根（结构性闸门）。
     *
     * 判据：子树（含整条父类链）里有没有声明 `getPublishTimeText()` —— 与 [timeTextOf] ② 级
     * 同源，差别是这里**不调用** getter，只看类有没有这个方法（结果按类名负缓存，几乎零成本）。
     *
     * ## 为什么用它当「是不是动态」的闸门
     * R37-1 要的是「forced id 必须落到真实动态上」。外部那条老链只会给一个 id 和一个 View，
     * 没有 pos / adapter 可供对账，唯一能当场验的就是**结构**：真动态根（`u3/g2/i3/…` 那一族）
     * 的父类链到第 9 层就是声明 `getPublishTimeText()` 的 `Llh3/l1`；封面 header 是裸
     * `FrameLayout`、广告卡/分割线也没有 ⇒ 一律拦下。
     * 而且这个判据同时保证了「拦下的 host 本来也画不上」：没有时间控件 ⇒ 不可能打到界面上。
     *
     * ⛔ 别在这儿放宽 ④ 级 `isTimeLike` 兜底去迁就空文本：那会把视频时长徽章 `00:15`
     *    当成时间行 ⇒ 标记打到错误的控件上（比不打标糟得多）。空文本由 [timeTextOf] ② 级
     *    照常处理 —— getter 返回的是对象，文本是不是空不影响它。
     */
    private fun hasTimeControl(host: View): Boolean = walkForTimeHolder(host, 0) != null

    private fun walkForTimeHolder(v: View, depth: Int): View? {
        if (publishTimeMethod(v.javaClass) != null) return v
        if (depth >= WALK_MAX || v !is ViewGroup) return null
        val n = minOf(v.childCount, WALK_KIDS)
        for (i in 0 until n) {
            val c = runCatching { v.getChildAt(i) }.getOrNull() ?: continue
            val t = walkForTimeHolder(c, depth + 1)
            if (t != null) return t
        }
        return null
    }

    /** ④ 级的级别号：[timeTextOf] 用它决定是否允许把结果缓存进 [Binding.tvId] */
    private const val TIME_LEVEL_FALLBACK = 4

    /**
     * ②③④ 三级解析。
     *
     * @return `(时间 TextView, 命中级别)`；级别见 [timeTextOf] 的 KDoc（②=2，③=3，④=4）。
     *
     * ⚠️ **④ 级只在本轮生效，禁止缓存**：它判定的是「这段文案长得像发布时间」，
     * 本质是猜。第二级（`getPublishTimeText()`）哪天被 R8 改名，④ 就变成主力，
     * 而那时它可能选中视频时长徽章 / 正文 / 卡片日期 ⇒ **标记打错位置**。
     * 不缓存 ⇒ 每次都重新判，② 修好了就能立刻自愈；缓存 ⇒ 一次误判固化成永久错误。
     */
    private fun resolveTime(host: View, hint: String): Pair<TextView, Int>? {
        walkForGetter(host, 0)?.let { return it to 2 }
        if (hint.isNotEmpty()) {
            val want = hint
            walkForText(host, 0) { it == want }?.let { return it to 3 }
        }
        walkForText(host, 0) { SnsDeletedTagLogic.isTimeLike(it) }?.let { return it to 4 }
        return null
    }

    private fun walkForGetter(v: View, depth: Int): TextView? {
        val m = publishTimeMethod(v.javaClass)
        if (m != null) {
            val t = runCatching { m.invoke(v) as? TextView }.getOrNull()
            if (t != null) {
                // 按 host 类名分桶而不是用全局 [logOnce]：否则第二次进朋友圈时，同类 View 的成功
                // 日志会静默消失，看上去就像「成功只有一次」（R37 就是这样被误导的）。
                logKeyOnce("tv:${v.javaClass.name}", "SNS_DELTAG tv host=${v.javaClass.simpleName} " +
                    "cls=${t.javaClass.simpleName} id=${Integer.toHexString(t.id)} depth=$depth")
                return t
            }
            logKeyOnce("tvnull:${v.javaClass.name}",
                "SNS_DELTAG: getPublishTimeText() returned null on ${v.javaClass.name}", warn = true)
        }
        if (depth >= WALK_MAX || v !is ViewGroup) return null
        val n = minOf(v.childCount, WALK_KIDS)
        for (i in 0 until n) {
            val c = v.getChildAt(i) ?: continue
            val t = walkForGetter(c, depth + 1)
            if (t != null) return t
        }
        return null
    }

    /** 注意：比对的文本要先 [strip] —— 上一轮已经追加过标记的行也要能认出来（幂等） */
    private fun walkForText(v: View, depth: Int, ok: (String) -> Boolean): TextView? {
        if (v is TextView) {
            val raw = runCatching { v.text?.toString() }.getOrNull().orEmpty()
            if (ok(strip(raw))) return v
        }
        if (depth >= WALK_MAX || v !is ViewGroup) return null
        val n = minOf(v.childCount, WALK_KIDS)
        for (i in 0 until n) {
            val c = v.getChildAt(i) ?: continue
            val t = walkForText(c, depth + 1, ok)
            if (t != null) return t
        }
        return null
    }

    /**
     * 类（含**整条**父类链）声明的 `getPublishTimeText()`；没有就缓存 null，避免每帧反射扫方法表。
     *
     * ## ⛔ R35-1d：这里原来写的是「含 4 层父类」，那是真机 `tv=0` 的直接原因
     * dex（classes3.dex）实测真机 item 根的继承链：
     * ```
     * Llh3/g2; → Llh3/j0; → Llh3/z1; → Lmh3/i1; → Llh3/o1; → Llh3/b2;
     *          → Llh3/l0; → Llh3/s1; → Llh3/x1; → Llh3/l1; → LinearLayout
     * ```
     * 而 `getPublishTimeText()` **只声明在 `Llh3/l1;` 上、且没有任何子类覆写**
     * （`g2/j0/z1/i1/o1/b2/l0/s1/x1` 的 declaredMethods 已逐个列过，无一同名）。
     * ⇒ 从 `g2` 往上要跳 **9** 层才看得到，`while (up < 4)` 恒返回 null。
     * ⇒ ②级永远不触发，只剩 ③（按内容比对）和 ④（时间样式兜底）；
     *    而 bind 那一刻时间 TextView 的文案还是空的（渲染是 `j0.g(...)` 那个 suspend，
     *    `afterHookedMethod` 时还没填）⇒ ③④ 也不可能中 ⇒ `tv=0`。
     */
    private fun publishTimeMethod(c: Class<*>): Method? {
        val key = c.name
        synchronized(timeMethodCache) {
            if (timeMethodCache.containsKey(key)) return timeMethodCache[key]
        }
        var found: Method? = null
        var cur: Class<*>? = c
        var up = 0
        while (up < SUPER_MAX) {
            val cc = cur ?: break
            if (cc.name == "java.lang.Object") break
            found = runCatching {
                cc.declaredMethods.firstOrNull {
                    it.name == "getPublishTimeText" && it.parameterTypes.isEmpty() &&
                        TextView::class.java.isAssignableFrom(it.returnType)
                }
            }.getOrNull()
            if (found != null) break
            cur = cc.superclass
            up++
        }
        if (found != null) found.isAccessible = true
        synchronized(timeMethodCache) { timeMethodCache[key] = found }
        return found
    }

    /**
     * 数据对象 → 发布时间原文（给 [Binding.hint] 用）。
     *
     * ① `toString()` 里的 `time:`（[SnsDeletedTagLogic.timeHintOf]，一次调用）；
     * ② 兜底直接调 `qh3/a.k()` → `oh3/p.J0()`（dex：realTimeUpdate 就是这么拿的）。
     * ⚠️ 只是**读**，不 hook —— 本类 KDoc 顶上那条「不许 hook `J0`」的禁令仍然成立。
     */
    private fun timeHintOf(data: Any?): String {
        val a = data ?: return ""
        val fromStr = SnsDeletedTagLogic.timeHintOf(a)
        if (fromStr.isNotEmpty()) return fromStr
        val p = runCatching { XposedHelpers.callMethod(a, "k") }.getOrNull() ?: return ""
        return runCatching { XposedHelpers.callMethod(p, "J0") as? String }.getOrNull().orEmpty()
    }

    /**
     * 一次性 View 树 dump（每个根 View 类只打一次）：万一四级解析都取不到，
     * 下一轮可以直接看树改，不用再猜。
     *
     * 形如：
     * ```
     * SNS_DELTAG tree depth=4 host=FrameLayout paths=37 [FrameLayout > LinearLayout > TextView("44分钟前") | …]
     * ```
     * 每个节点只打**类名**（TextView 额外打前 12 字文本），路径用 ` > ` 连，最多 30 条。
     */
    private fun dumpTree(host: View) {
        val key = host.javaClass.name
        if (!treeDumped.add(key)) return
        val paths = ArrayList<String>()
        collectPaths(host, "", 0, paths)
        val shown = paths.take(60).joinToString(" | ")
        LogX.i("SNS_DELTAG tree depth=$TREE_DEPTH host=${host.javaClass.simpleName} " +
            "paths=${paths.size} [${SnsDeletedTagLogic.brief(shown, 3000)}]")
    }

    private fun collectPaths(v: View, prefix: String, depth: Int, out: ArrayList<String>) {
        if (depth > TREE_DEPTH || out.size >= 120) return
        val label = if (v is TextView) {
            val t = SnsDeletedTagLogic.brief(runCatching { v.text?.toString() }.getOrNull(), 12)
            v.javaClass.simpleName + "(\"" + t + "\")"
        } else {
            v.javaClass.simpleName
        }
        val path = if (prefix.isEmpty()) label else "$prefix > $label"
        if (depth > 0) out.add(path)
        if (v is ViewGroup) {
            val n = minOf(v.childCount, WALK_KIDS)
            for (i in 0 until n) {
                val c = v.getChildAt(i) ?: continue
                collectPaths(c, path, depth + 1, out)
            }
        }
    }

    // ------------------------------------------------------------------ 全量重判

    private val sweeper = object : Runnable {
        override fun run() {
            sweepScheduled.set(false)
            runCatching { sweep() }
                .onFailure { LogX.w("SNS_DELTAG sweep err: ${it.javaClass.simpleName}") }
            ensureSweep()
        }
    }

    /** 有活才排下一拍：不在朋友圈界面时 [bound] 是空的，不空转 */
    private fun ensureSweep() {
        if (synchronized(bound) { bound.isEmpty() }) return
        if (sweepScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(sweeper, SWEEP_MS)
        }
    }

    /**
     * 对每个绑定过的 itemView 重判。
     *
     * 真相只认「当前绑定关系」[bound]；开关关着时按**空清单**处理（= 全摘），
     * 这样「关掉再打开」也能自己贴回来。
     *
     * ⚠️ 必须自己扫一遍：`ImproveTimelineItemMeasure.d()`（realTimeUpdate）会周期性把
     * 纯原文案写回时间 TextView（`SnsDeletedTag` 的 R30 KDoc 已记），不补就会被冲掉。
     * 而且 [SnsTag] 的 sweep 以它自己的 `bound` 为准 —— 它解析不出来时 `bound` 为空、
     * sweep 根本不排，本类的标记就再也没人补。
     *
     * ⚠️ 判定必须与 `SnsTag.sweep` 同口径，否则两条 2 s sweep 会互相贴/剥（闪烁）：
     * 那边传的是 `del + forceSet`，这边 [deleted()] 也已经把 [forceSet] 并进去了。
     */
    private fun sweep() {
        val snap = snapshot()
        if (snap.isEmpty()) return
        // deleted() = 物理删除归档 ∪ forceSet（探针），与 SnsTag.sweep 同口径
        val tagged = if (on()) deleted() else emptySet()
        for ((v, b) in snap) {
            if (!v.isAttachedToWindow) {
                synchronized(bound) { bound.remove(v) }
                continue
            }
            paint(v, b, tagged)
        }
    }

    /** WeakHashMap 的 key 在迭代时可能是 null（referent 被回收）→ 必须滤掉，否则 NPE */
    private fun snapshot(): List<Pair<View, Binding>> {
        val out = ArrayList<Pair<View, Binding>>()
        synchronized(bound) {
            for (e in bound.entries) {
                val k = e.key ?: continue
                val v = e.value ?: continue
                out += k to v
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 已删清单

    /**
     * 临时强制打标集合 —— **真机验证专用**（与 `SnsTag` 的 `forceSet` 同一用途）。
     *
     * ## 为什么本类也得有一份，光有 `SnsTag` 那份不够
     * `snstag fake / mark` 这类探针设的是 **`SnsTag` 的** `forceSet`：
     * - `SnsTag.sweep` 传 `del + forceSet` ⇒ 贴标；
     * - 本类 [sweep] 传 [deleted()]（**原来不含 forceSet**）⇒ 判定「未删」⇒ `strip` ⇒ **剥标**；
     * - 下一跳 `SnsTag.sweep` 又贴回来 ⇒ **两条 2 秒 sweep 互相贴/剥**，界面上就是闪烁。
     *
     * ⇒ [deleted()] 返回 `delSet + forceSet`，两边的判定就一致了，谁先谁后都一样。
     * ⚠️ 探针要验本类这条链时，设完 `SnsTag.forceSet` **也要** [forceTag] 一次
     * （`SnsTag.kt` / `EntryReceiver.kt` 不归本文件改，那里的接线留给那一条 lane）。
     */
    @Volatile private var forceSet: Set<Long> = emptySet()

    /**
     * 探针入口：临时把这几条当成「已删」（真机验证专用，理由见 [forceSet] 的 KDoc）。
     * 传空集合 = 取消。必须在主线程调。
     */
    internal fun forceTag(ids: Set<Long>) {
        forceSet = ids
        // 立刻排一拍 sweep：否则要等下一次 bind / 最多 2 s 才看得见变化
        ensureSweep()
    }

    /** 已删判据 = 物理删除归档 ∪ 探针强制集合。正常运行时 [forceSet] 恒空 ⇒ 零分配。 */
    private fun deleted(): Set<Long> {
        val s = archived()
        val fs = forceSet
        return if (fs.isEmpty()) s else s + fs
    }

    /** 已救下（= 被删过）的动态清单（**不含** [forceSet]，仅供 [deleted] 内部用） */
    private fun archived(): Set<Long> {
        val now = SystemClock.elapsedRealtime()
        if (setAt != 0L && now - setAt < SET_TTL_MS) return delSet

        // 首屏必须同步拿一次，否则进朋友圈第一屏一个标签都没有。
        // ⚠️ 只查 sns_id 一列（GroupDb.snsTagIds），绝不读 content/attrBuf/postBuf 三个 BLOB。
        if (!firstLoad) {
            val s = loadIds()
            if (GroupDb.current() != null) firstLoad = true // 库还没开就别把首屏机会用掉
            delSet = s
            setAt = now
            return s
        }
        // 之后一律后台刷新：主线程绝不查库
        if (setLoading.compareAndSet(false, true)) {
            // ⚠️ `Thread{...}.start()` 本身也必须包在 try/catch 里：CAS 已经把 setLoading
            //    置成 true 了，而 `start()` 是**唯一**会在这个窗口内抛的地方
            //    （OOM / 线程数打满 / `IllegalThreadStateException`）。
            //    漏了这层 ⇒ 标志永久 true ⇒ 已删清单从此再也不刷新，而且**完全静默**
            //    （界面上只是「新删的没有标」，没有任何日志指向这里）。
            //    ⇒ catch 里必须把标志复位；线程体内的 finally 照旧保留（防的是体内异常）。
            try {
                Thread {
                    try {
                        runCatching {
                            delSet = loadIds()
                            setAt = SystemClock.elapsedRealtime()
                        }
                    } finally {
                        // ⚠️ 必须在 finally 里复位：漏复位的话标志永久卡住，之后再也不刷新
                        setLoading.set(false)
                    }
                }.apply { isDaemon = true; name = "sns-deltag-ids" }.start()
            } catch (t: Throwable) {
                setLoading.set(false)
                warnOnce("SNS_DELTAG: id refresh thread failed: ${t.javaClass.simpleName}")
            }
        }
        return delSet
    }

    private fun loadIds(): Set<Long> {
        val db = GroupDb.current()
        if (db == null) {
            if (!dbWarned) {
                dbWarned = true
                LogX.w("SNS_DELTAG: GroupDb not open, no labels")
            }
            return delSet
        }
        // ⛔ 数据源 = [GroupDb.snsTagIds]，**只认物理删除归档 T_SNS**。
        //    用补位台账 `fixed_orig` 当判据 ⇒ 正常动态全被插上灰标（R31h 事故）。
        return runCatching { db.snsTagIds() }
            .onFailure { LogX.w("SNS_DELTAG ids failed: ${it.javaClass.simpleName}") }
            .getOrDefault(delSet)
    }

    // ------------------------------------------------------------------ 自诊断

    /**
     * 自诊断。看数顺序：
     * ① `binds>0` —— 钩子活着吗（0 = 完全没挂上）
     * ② `snsBinds>0` —— 朋友圈限定生效吗（binds 大而 snsBinds=0 = 类名前缀写错）
     * ③ `resolved≈snsBinds` —— 解析通吗（差得多就看 lastMiss）
     * ④ `painted>0` —— **这才算画上了**（判定命中 + 真的 setText 了）
     * ⑤ `hits>0 但 painted=0` —— 清单/符号是对的，但没落到界面上 ⇒ 立刻看 `missedTime`
     * ⑥ `missedTime>0` —— 反射取不到时间控件（字段/方法名漂移）。
     *    R37-1 起这个数**按 Binding 去重**（同一个 View 反复 sweep 只计一次），并拆成后面
     *    三个附加字段。`missedTime` 这个名字与位置都保留（真机验收文档已引用），只加不删：
     *    - `mtHit` —— **命中却找不到时间控件**（真故障：该画的没画上，最该盯的就是它）；
     *    - `mtRestore` —— 未命中、仅尝试还原时找不到控件（多为封面/广告卡，属正常探索失败）；
     *    - `lastRecon` —— 最近一次 View↔数据对账的细粒度原因（`noname` = 作者文本还没渲染
     *      出来、等下一拍重试即可；`ambig<N>` = 同作者相邻动态导致歧义，只能不打标）。
     *
     * ⚠️ ④ 为什么改成 `painted`：`hits` 在取时间控件**之前**就自增了（`paint` 第一行），
     * 取不到控件时它照样涨 ⇒ 「`hits` 从 0 变 1 ⇒ 界面上出现了标记」这个判据站不住，
     * R35 真机上就是这样误判过一次（同时 `missedTime=1`）。判据必须对着我能看见的事实：
     * 只有 `tv.text = want` 真的执行了，界面上才可能有标记。
     */
    fun stat(): String = "SNS_DELTAG stat on=${on()} deleted=${delSet.size} " +
        "bound=${synchronized(bound) { bound.size }} hooks=$hooks " +
        "binds=$binds sns=$snsBinds resolved=$resolved missed=$missed " +
        "hits=$hits painted=$painted missedTime=$missedTime tv=$tvCached " +
        "paths=${dataPathCache.size} lastMiss=$lastMiss " +
        "mtHit=$missedTimeHit mtRestore=$missedTimeRestore lastRecon=$lastRecon"

    /** 已记住时间 TextView 资源 id 的 item 数：`0` 且 `missedTime>0` ⇒ 三级解析全挂 */
    private val tvCached: Int
        get() = synchronized(bound) { bound.values.count { it.tvId != View.NO_ID } }

    /** 明细：当前屏幕上每个 item 解析出的 id（无符号写法，与 `snstag dump` 同口径） */
    fun dump(): String {
        val sb = StringBuilder("SNS_DELTAG dump\n")
            .append("  deleted(${delSet.size}): ")
            .append(delSet.take(8).joinToString(",") { java.lang.Long.toUnsignedString(it) })
            .append("\n")
        for ((v, b) in snapshot()) {
            sb.append("    u=").append(java.lang.Long.toUnsignedString(b.id))
                .append(" hit=").append(SnsDeletedTagLogic.isTagged(b.id, delSet))
                .append(" tvId=").append(if (b.tvId == View.NO_ID) "-" else Integer.toHexString(b.tvId))
                .append(" hint=").append(b.hint)
                .append(" host=").append(v.javaClass.name)
                .append("\n")
        }
        return sb.toString()
    }
}

/**
 * 剥掉末尾可能残留的 ` [删除已拦截]`，是 [SnsDeletedTagLogic.appendTag] 的**逆操作**。
 *
 * ## 为什么需要
 * 时间 TextView 是 RecyclerView **复用**的，且 `realTimeUpdate` 会周期性写回纯原文。
 * 追加前必须先剥一次，否则会叠成 `…[删除已拦截] [删除已拦截]`；被删 item 的 View
 * 复用到正常动态时也必须剥，否则标记串味。`appendTag` 是幂等的但它**不会去掉**标记，
 * 去标记只能靠这里。
 *
 * ## 为什么是纯函数 / 顶层 internal
 * 本文件的对象 [SnsDeletedTag] 挂着 `XposedHelpers` 等 Android/Xposed 依赖，JVM 单测
 * 一加载对象就崩。顶层函数编译成独立的 `SnsDeletedTagKt` 门面类，单测只加载它，不碰对象。
 */
internal fun strip(text: String): String {
    val mark = SnsDeletedTagLogic.MARK
    return when {
        text == mark -> ""                        // appendTag("") 的逆：整段就是标记
        text.endsWith(" $mark") -> text.substring(0, text.length - mark.length - 1)
        else -> text
    }
}
