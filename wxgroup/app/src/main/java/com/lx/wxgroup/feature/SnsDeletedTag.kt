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

    /** 取不到时间控件时的告警计数（不去重，用于诊断「字段/方法名漂移」） */
    @Volatile private var missedTime = 0

    /** 自诊断：静默失效全靠这几个数定位（binds>0 才说明钩子活着） */
    @Volatile private var binds = 0
    @Volatile private var snsBinds = 0
    @Volatile private var resolved = 0
    @Volatile private var hits = 0
    @Volatile private var missed = 0
    @Volatile private var lastMiss = ""
    @Volatile private var hooks = 0

    private val miscLogged = AtomicInteger(0)

    /** 供 [SnsTag.stat] 读的「没打上标」信号：>0 说明反射取不到时间控件（字段/方法名漂移）。 */
    internal fun missedTimeCount(): Int = missedTime

    /** 限流日志：LogX 是主线程同步文件 IO，热路径上只能留前几条 */
    private fun logOnce(msg: String) {
        if (miscLogged.get() < LOG_MAX && miscLogged.incrementAndGet() <= LOG_MAX) LogX.i(msg)
    }

    /**
     * 一个 itemView 当前的绑定关系。
     *
     * ## 为什么从 `View -> Long` 扩成 `View -> Binding`（R35-1c）
     * 光有 snsId 定位不到时间 TextView（真机 `hits=0 missedTime=1`）。定位还需要两条信息：
     * - `tvId` —— 解析成功后记住时间 TextView 的**资源 id**，之后 [sweep] 每 2s 重判
     *   时直接 `findViewById`，不必每次遍历子树；
     * - `hint` —— 这条动态的发布时间原文（`ImproveInfo(…, time:44分钟前 …)`），
     *   用来在子树里按内容认出时间行（dex 已证时间行原文 == `J0()`，见 Logic 的 KDoc）。
     *
     * ⚠️ **不存 holder / data 本体**：[bound] 是 `WeakHashMap`，value 若强引用 key
     * 指向的对象（holder 强引用 itemView == key），条目将永远无法回收。
     */
    private class Binding(val id: Long) {
        @Volatile var tvId: Int = View.NO_ID
        @Volatile var hint: String = ""
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
                val key = "${m.name}(${m.parameterTypes.joinToString { p -> p.name }})"
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

    private val bindHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            binds++
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
            val data = dataOf(holder, adapter, pos)
            val id = SnsDeletedTagLogic.snsIdOf(data)
            if (id == null) {
                missed++
                lastMiss = "nodata(${holder.javaClass.simpleName})"
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
            val b = if (prev != null && prev.id == id) prev else {
                Binding(id).also {
                    it.hint = timeHintOf(data)
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
     */
    private fun retryResolve(holder: Any, adapter: Any?, pos: Int, view: View) {
        if (retryLeft <= 0) return
        retryLeft--
        mainHandler.post { runCatching { retryPaint(holder, adapter, pos, view) } }
    }

    private fun retryPaint(holder: Any, adapter: Any?, pos: Int, view: View) {
        if (!on()) return
        val d = dataOf(holder, adapter, pos) ?: return
        val i = SnsDeletedTagLogic.snsIdOf(d) ?: return
        val prev = bound[view]
        val b = if (prev != null && prev.id == i) prev else {
            Binding(i).also { it.hint = timeHintOf(d); bound[view] = it }
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
     * - `@` = `adapter.data[pos]`（与 holder 字段无关的独立来源）
     * - `z` = 调 holder 的无参 getter `z()`；其余单个名字 = 读 holder 的同名字段
     * - `f:xxx` = 读上一步结果的字段 `xxx`
     * - `m:xxx` = 调上一步结果的无参方法 `xxx`
     */
    private val dataPathCache = Collections.synchronizedMap(HashMap<String, String>())

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
     * @param adapter 被 hook 方法的 `thisObject`（adapter），用来走 `@` 这条独立路径。
     * @param pos     绑定下标（`c1/d1/a1/b1` 的形参[1]，恒为 int）。
     */
    private fun dataOf(holder: Any, adapter: Any?, pos: Int): Any? {
        val key = holder.javaClass.name
        bumpSeen(key)
        val cached = dataPathCache[key]
        if (cached != null) {
            val v = walkPath(holder, adapter, pos, cached)
            if (v != null) return v
        }
        if (!takeProbe(key)) {
            logDataFail(key)
            return null
        }
        val found = probePath(holder, adapter, pos)
        if (found == null) {
            logDataFail(key)
            return null
        }
        dataPathCache[key] = found
        LogX.i("SNS_DELTAG path ${holder.javaClass.simpleName} -> $found")
        return walkPath(holder, adapter, pos, found)
    }

    /** 全量探测：按优先级试所有候选，返回第一条能解析出 snsId 的路径 */
    private fun probePath(holder: Any, adapter: Any?, pos: Int): String? {
        dumpFields(holder, "holder")
        for (h in arrayOf("@", "z", "E", "D")) {
            val v = stepOf(holder, adapter, pos, h) ?: continue
            dumpFields(v, "item($h)")
            if (SnsDeletedTagLogic.snsIdOf(v) != null) return h
            val sub = unwrap(v) ?: continue
            if (SnsDeletedTagLogic.snsIdOf(sub.second) != null) return "$h;${sub.first}"
        }
        return null
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

    /** 单个 step：`@` = adapter.data[pos]，`z` = 无参 getter，其余 = 字段名 */
    private fun stepOf(holder: Any, adapter: Any?, pos: Int, step: String): Any? = when {
        step == "@" -> adapterItem(adapter, pos)
        step == "z" -> runCatching { XposedHelpers.callMethod(holder, "z") }.getOrNull()
        else -> runCatching { XposedHelpers.getObjectField(holder, step) }.getOrNull()
    }

    /**
     * 与 `m0.E` **完全无关**的独立来源：`WxRecyclerAdapter->data` 的第 pos 项。
     *
     * dex：`WxRecyclerAdapter.c1(m0,I)` 体内就是
     * `iget-object … WxRecyclerAdapter->data : Ljava/util/ArrayList;` + `ArrayList.get(I)`，
     * 这正是微信自己取 item 的方式，不受「`E` 被 `a1/b1` 临时写成 bind-info」影响。
     * 字段名 `data` **没被混淆**，比 `E`/`z` 稳。
     */
    private fun adapterItem(adapter: Any?, pos: Int): Any? {
        val a = adapter ?: return null
        if (pos < 0) return null
        val list = runCatching {
            XposedHelpers.getObjectField(a, "data") as? java.util.List<*>
        }.getOrNull() ?: return null
        if (pos >= list.size) return null
        return runCatching { list[pos] }.getOrNull()
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
            val b = if (prev != null && prev.id == snsId) prev else {
                Binding(snsId).also { bound[itemView] = it }
            }
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
        paint(itemView, own, deleted())
    }

    /** 判定 + 计数 + 落笔；[tagged] 命中时 hits++ */
    private fun paint(host: View, b: Binding, tagged: Set<Long>) {
        if (SnsDeletedTagLogic.isTagged(b.id, tagged)) hits++
        dumpTree(host)
        val tv = timeTextOf(host, b)
        if (tv == null) {
            val n = ++missedTime
            // 限流：这是主线程热路径，LogX 同步写盘，只能留前几条
            if (n <= 5) {
                LogX.w("SNS_DELTAG: no time TextView snsId=${b.id} host=${host.javaClass.name} hint='${b.hint}'")
            }
            return
        }
        val cur = tv.text?.toString() ?: ""
        val base = strip(cur)
        val want = if (SnsDeletedTagLogic.isTagged(b.id, tagged)) {
            SnsDeletedTagLogic.appendTag(base)
        } else {
            base
        }
        // 幂等：text 一致就不 setText，避免无谓重绘（也顺带挡掉「setText 又触发重入」）
        if (want != cur) {
            tv.text = want
        }
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
     * ②③④ 任一成功就把资源 id 记回 [Binding.tvId]，之后不再遍历。
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
        val tv = resolveTime(host, b?.hint.orEmpty())
        if (tv != null && b != null && tv.id != View.NO_ID) b.tvId = tv.id
        return tv
    }

    private fun resolveTime(host: View, hint: String): TextView? {
        walkForGetter(host, 0)?.let { return it }
        if (hint.isNotEmpty()) {
            val want = hint
            walkForText(host, 0) { it == want }?.let { return it }
        }
        return walkForText(host, 0) { SnsDeletedTagLogic.isTimeLike(it) }
    }

    private fun walkForGetter(v: View, depth: Int): TextView? {
        val m = publishTimeMethod(v.javaClass)
        if (m != null) {
            val t = runCatching { m.invoke(v) as? TextView }.getOrNull()
            if (t != null) {
                logOnce("SNS_DELTAG tv host=${v.javaClass.simpleName} " +
                    "cls=${t.javaClass.simpleName} id=${Integer.toHexString(t.id)} depth=$depth")
                return t
            }
            logOnce("SNS_DELTAG: getPublishTimeText() returned null on ${v.javaClass.name}")
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
     */
    private fun sweep() {
        val snap = snapshot()
        if (snap.isEmpty()) return
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

    /** 已救下（= 被删过）的动态清单 */
    private fun deleted(): Set<Long> {
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
     * ④ `hits>0` —— 清单/符号对吗
     * ⑤ `missedTime>0` —— hits 有值但界面上标记没出现时看这个：>0 = 反射取不到时间控件
     */
    fun stat(): String = "SNS_DELTAG stat on=${on()} deleted=${delSet.size} " +
        "bound=${synchronized(bound) { bound.size }} hooks=$hooks " +
        "binds=$binds sns=$snsBinds resolved=$resolved missed=$missed " +
        "hits=$hits missedTime=$missedTime tv=$tvCached paths=${dataPathCache.size} lastMiss=$lastMiss"

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
