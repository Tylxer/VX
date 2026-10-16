package com.lx.wxgroup.feature

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.lx.wxgroup.core.HostContext
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
import java.lang.ref.WeakReference
import java.util.Collections

/**
 * 需求 6.4「朋友圈」——**定时刷新** + **自动加载**的 hook 侧。
 *
 * ## 两部分各自解决什么
 * - **定时刷新**：周期性把朋友圈时间线拉到前台，让微信真的去服务端拉一次动态。
 *   目的是「在对方删除前留存」——删动态在本地是物理 DELETE（见 [SnsGuard]），
 *   本地库里有这一行，删除才会被拦下并归档；拉得越勤，漏删窗口越小。
 * - **自动加载**：用户滚到时间线底部时，替他把列表再甩到底（[android.widget.fling]
 *   用的是 RecyclerView 自己的公有 API），让微信自己的「滑到底加载更多」继续工作，
 *   把更多历史动态拉进本地库。
 *
 * ## 锚点（全部来自 docs/取证结论.md「朋友圈」节，没有一个是猜的）
 * - adapter：`com.tencent.mm.view.recyclerview.WxRecyclerAdapter`（**类名未混淆**），
 *   `onBindViewHolder(holder, pos)` + `adapter.getData()`（元素 toString 含
 *   `ImproveInfo(name=…, snsID='无符号十进制')`）。子类覆写会让基类钩子变死钩，
 *   所以跟 [SnsTag] 一样补挂 `WxRecyclerView.setAdapter` 抓具体类。
 * - 时间线 Activity：**简单类名** `ImproveSnsTimelineUI`（取证结论已实证）。
 *   全限定名**不猜**——用 ActivityLifecycleCallbacks 在用户真正进过一次朋友圈后
 *   按简单类名捕获，之后才允许自动拉起。
 *
 * ## ⚠️ 待取证（详见报告「取证缺口」）
 * 1. 显式 Intent 重新拉起时间线 Activity，是否**真的触发一次服务端拉取**（还是只
 *   走本地缓存 onResume）。探针：`op=moments sub=refresh` 后看 `MOMENT autoload grow`
 *    与 SnsMicroMsg.db 的写入。
 * 2. 底部 fling 是否稳定触发微信自己的「加载更多」。自观测信号：onBind 里每次看到
 *    `getData().size` 变大会打 `MOMENT autoload grow`——它就是硬判据。
 */
object MomentsAuto {

    private const val TAG = "MOMENT"

    /** 微信 RecyclerView 适配器基类：未混淆，可直接按名字找 */
    private const val ADAPTER_CLASS = "com.tencent.mm.view.recyclerview.WxRecyclerAdapter"

    /** 微信自研列表基类：用来抓 setAdapter 拿到「具体 adapter 类」 */
    private const val WX_RV_CLASS = "com.tencent.mm.view.recyclerview.WxRecyclerView"

    /** 只给朋友圈的 adapter 挂钩（钩子挂基类，全微信列表都会进来） */
    private const val SNS_ADAPTER_PREFIX = "com.tencent.mm.plugin.sns."

    /** 时间线 Activity 的简单类名（已实证）。全限定名运行时捕获，不猜 */
    private const val TIMELINE_ACT = "ImproveSnsTimelineUI"

    private const val CFG_TTL_MS = 3_000L

    /** 例行判定间隔；首次判定提前一点，便于真机观察 */
    private const val TICK_MS = 60_000L
    private const val FIRST_TICK_MS = 20_000L

    /** 多久没有新绑定算「新一轮浏览」（自动加载计数随之归零） */
    private const val SESSION_IDLE_MS = 10_000L

    /** 向下甩的力度（px/s）。RecyclerView.fling 的公有参数 */
    private const val FLING_VY = 8000

    /** 日志限流：LogX 是主线程同步文件 IO，热路径只能留前几条 */
    private const val LOG_MAX = 5

    private data class Config(
        val autoRefresh: Boolean,
        val autoLoad: Boolean,
        val intervalH: Int,
    )

    @Volatile
    private var config = Config(false, false, 3)

    @Volatile
    private var cfgAt = 0L

    // ------------------------------------------------------------------ 运行状态

    /** 上次刷新时刻（epoch millis，Kv 持久化；0 = 从没刷过 → 立刻刷） */
    @Volatile
    private var lastRefreshAt = 0L

    @Volatile
    private var lastRefreshAtRead = 0L

    private val tickScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 时间线 Activity 的**具体类**（用户进过一次朋友圈后捕获） */
    @Volatile
    private var timelineCls: Class<out Activity>? = null

    @Volatile
    private var timelineActive = false

    /** 自动拉起失败过就整体禁用（多半是 Activity 不能直接启动，反复试只会反复撩前台） */
    @Volatile
    private var launchDisabled = false

    @Volatile
    private var unknownWarned = false

    @Volatile
    private var lifecycleWarned = false

    private val hookedMethods: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** adapter 类 → getData() 方法（负缓存用 containsKey 表达，CHM 不允许 null value） */
    private val dataMethodCache: MutableMap<String, Method?> =
        Collections.synchronizedMap(HashMap())

    /** holder 类 → itemView 字段 */
    private val holderViewCache: MutableMap<String, Field?> =
        Collections.synchronizedMap(HashMap())

    /** 最近一个时间线 RecyclerView（弱引用，绝不钉住宿主 View） */
    @Volatile
    private var lastRv: WeakReference<View>? = null

    // 自动加载状态
    @Volatile
    private var lastTriggerAt = 0L

    @Volatile
    private var lastBindAt = 0L

    @Volatile
    private var sessionCount = 0

    @Volatile
    private var lastSize = 0

    // 自诊断计数
    private val ticks = java.util.concurrent.atomic.AtomicLong(0)
    private val launches = java.util.concurrent.atomic.AtomicLong(0)
    private val binds = java.util.concurrent.atomic.AtomicLong(0)
    private val snsBinds = java.util.concurrent.atomic.AtomicLong(0)
    private val loads = java.util.concurrent.atomic.AtomicLong(0)
    private val grows = java.util.concurrent.atomic.AtomicLong(0)
    private val loadLogged = java.util.concurrent.atomic.AtomicInteger(0)
    private val growLogged = java.util.concurrent.atomic.AtomicInteger(0)
    private val flingLogged = java.util.concurrent.atomic.AtomicInteger(0)

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching { refresh() }
            .onFailure { LogX.w("$TAG refresh failed: ${it.javaClass.simpleName}") }

        var hooked = 0
        // ① 基类上声明的实现（子类不覆写时走这条）
        runCatching { XposedHelpers.findClass(ADAPTER_CLASS, lpparam.classLoader) }
            .onSuccess { hooked += hookAdapterClass(it) }
            .onFailure { LogX.w("$TAG: $ADAPTER_CLASS not found") }
        // ② 子类一旦覆写 onBindViewHolder，挂基类就是死钩 → 靠 setAdapter 补挂
        hooked += hookSetAdapter(lpparam.classLoader)

        registerLifecycle()
        ensureTick(FIRST_TICK_MS)

        LogX.i(
            "$TAG installed hooks=$hooked refresh=${config.autoRefresh} " +
                "load=${config.autoLoad} interval=${config.intervalH}h"
        )
    }

    fun refresh() {
        config = Config(
            autoRefresh = Kv.getBool(Keys.MOMENTS_AUTO_REFRESH),
            autoLoad = Kv.getBool(Keys.MOMENTS_AUTO_LOAD),
            intervalH = MomentsLogic.clampHours(Kv.getInt(Keys.MOMENTS_REFRESH_INTERVAL_H)),
        )
        cfgAt = SystemClock.elapsedRealtime()
        lastRefreshAtRead = 0L // 让 lastRefreshAt 立刻重读
    }

    private fun cfg(): Config {
        if (SystemClock.elapsedRealtime() - cfgAt > CFG_TTL_MS) refresh()
        return config
    }

    // ------------------------------------------------------------------ 环境判定（Android API 现拿）

    /** 充电中（BatteryManager.isCharging，API 23+；取不到再退 sticky 广播） */
    private fun charging(): Boolean {
        val ctx = HostContext.app() ?: return false
        val bm = runCatching {
            ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        }.getOrNull()
        if (bm != null) return runCatching { bm.isCharging }.getOrDefault(false)
        val i = runCatching {
            ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return false
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return st == BatteryManager.BATTERY_STATUS_CHARGING ||
            st == BatteryManager.BATTERY_STATUS_FULL
    }

    /** WiFi 下（跑在微信进程里，直接用微信自己的 ConnectivityManager） */
    private fun wifi(): Boolean {
        val ctx = HostContext.app() ?: return false
        val cm = runCatching {
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull() ?: return false
        return runCatching {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ 定时调度

    private val ticker = object : Runnable {
        override fun run() {
            tickScheduled.set(false)
            runCatching { onTick() }
                .onFailure { LogX.w("$TAG tick err: ${it.javaClass.simpleName}") }
            ensureTick(TICK_MS)
        }
    }

    private fun ensureTick(delay: Long) {
        if (tickScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(ticker, delay)
        }
    }

    private fun onTick() {
        ticks.incrementAndGet()
        val c = cfg()
        if (!c.autoRefresh) return
        val now = System.currentTimeMillis()
        // Kv 读一次就够（60s 一拍），但开关刚被 kvset 改过时要立刻看到
        if (now - lastRefreshAtRead > CFG_TTL_MS) {
            lastRefreshAt = Kv.getString(Keys.MOMENTS_LAST_REFRESH_AT).toLongOrNull() ?: 0L
            lastRefreshAtRead = now
        }
        val next = MomentsLogic.nextRefreshAt(lastRefreshAt, c.intervalH, charging(), wifi(), now)
        if (now < next) return
        doRefresh(now)
    }

    /**
     * 到点刷新：把时间线 Activity 显式拉起来。
     *
     * ⚠️ 全限定名是运行时捕获的（[timelineCls]），这里不拼字符串猜包名。
     * ⚠️ 整个包 runCatching：startActivity 抛出来会把微信搞崩。
     *
     * @param force 手动探针（`op=moments sub=refresh`）传 true：绕过 [launchDisabled]，
     *   否则自动拉起失败过一次之后，手动诊断入口也被自己挡死，真机上没法再验证。
     *   ⚠️ force 只绕过闸门，不重置 launchDisabled——自动轮依然被禁着，防止反复撩前台。
     */
    private fun doRefresh(now: Long, force: Boolean = false) {
        val ctx = HostContext.app()
        val cls = timelineCls
        if (ctx == null || cls == null) {
            if (!unknownWarned) {
                unknownWarned = true
                LogX.w("$TAG refresh due but timeline class unknown (先手动进一次朋友圈)")
            }
            return
        }
        if (launchDisabled && !force) return
        val ok = runCatching {
            val i = Intent().setClassName(ctx, cls.name)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            ctx.startActivity(i)
            true
        }.getOrElse {
            LogX.w("$TAG launch err: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
            false
        }
        if (!ok) {
            launchDisabled = true
            LogX.w("$TAG auto-launch disabled (手动入口: op=moments sub=refresh)")
            return
        }
        launches.incrementAndGet()
        Kv.putString(Keys.MOMENTS_LAST_REFRESH_AT, now.toString())
        lastRefreshAt = now
        lastRefreshAtRead = now
        LogX.i(
            "$TAG refresh launched act=${cls.simpleName} active=$timelineActive " +
                "env(chg=${charging()},wifi=${wifi()})"
        )
    }

    // ------------------------------------------------------------------ 时间线 Activity 捕获

    /**
     * 用框架主动回调（registerActivityLifecycleCallbacks）记住时间线 Activity。
     *
     * ⚠️ 不 hook Activity.onResume：微信自己的 Activity 普遍覆写它，挂基类是死钩
     * （HostContext 里踩过，真机上 activity() 恒 null）。生命周期回调跟覆不覆写无关。
     */
    private fun registerLifecycle() {
        val app = HostContext.app()
        if (app == null) {
            if (!lifecycleWarned) {
                lifecycleWarned = true
                LogX.w("$TAG: no host app ctx, timeline capture skipped")
            }
            return
        }
        runCatching {
            (app as? Application)?.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(a: Activity) {
                        if (a.javaClass.simpleName == TIMELINE_ACT) {
                            @Suppress("UNCHECKED_CAST")
                            timelineCls = a.javaClass as Class<out Activity>
                            timelineActive = true
                            unknownWarned = false // 又能刷了
                            // 进朋友圈 = 新一轮浏览，自动加载计数归零
                            sessionCount = 0
                        }
                    }

                    override fun onActivityPaused(a: Activity) {
                        if (a.javaClass.simpleName == TIMELINE_ACT) timelineActive = false
                    }

                    override fun onActivityCreated(a: Activity, b: android.os.Bundle?) = Unit
                    override fun onActivityStarted(a: Activity) = Unit
                    override fun onActivityStopped(a: Activity) = Unit
                    override fun onActivitySaveInstanceState(a: Activity, out: android.os.Bundle) = Unit
                    override fun onActivityDestroyed(a: Activity) = Unit
                }
            )
        }.onFailure {
            LogX.w("$TAG lifecycle register failed: ${it.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ adapter 钩子（自动加载）

    private val bindHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            // binds 在开关判断之前自增 —— 钩子是不是死的，全看它
            binds.incrementAndGet()
            runCatching { onBind(param) }
                .onFailure { t ->
                    if (flingLogged.get() < 3 && flingLogged.incrementAndGet() <= 3) {
                        LogX.w("$TAG bind err: ${t.javaClass.simpleName}")
                    }
                }
        }
    }

    private fun onBind(param: XC_MethodHook.MethodHookParam) {
        val adapter = param.thisObject ?: return
        // 钩子挂基类，微信里所有 WxRecyclerAdapter 子类都会进来 → 只认朋友圈的
        if (!adapter.javaClass.name.startsWith(SNS_ADAPTER_PREFIX)) return
        snsBinds.incrementAndGet()
        if (!cfg().autoLoad) return

        val holder = param.args?.getOrNull(0) ?: return
        val pos = param.args?.getOrNull(1) as? Int ?: return
        val view = holderView(holder) ?: return
        val rv = rvOf(view) ?: return
        val list = dataList(adapter) ?: return
        val size = list.size

        // 自观测：size 变大会打 grow 日志 —— 自动加载到底有没有真的拉到更多，看它
        if (size > lastSize) {
            grows.incrementAndGet()
            if (growLogged.get() < LOG_MAX && growLogged.incrementAndGet() <= LOG_MAX) {
                LogX.i("$TAG autoload grow $lastSize -> $size")
            }
        }
        if (size > 0) lastSize = size

        val now = SystemClock.elapsedRealtime()
        if (now - lastBindAt > SESSION_IDLE_MS) sessionCount = 0
        lastBindAt = now
        lastRv = WeakReference(rv)

        if (!MomentsLogic.loadMoreDue(pos, size, lastTriggerAt, now, sessionCount)) return
        lastTriggerAt = now
        sessionCount++
        loads.incrementAndGet()
        val flung = runCatching {
            XposedHelpers.callMethod(rv, "fling", 0, FLING_VY)
            true
        }.getOrElse {
            if (flingLogged.get() < 3 && flingLogged.incrementAndGet() <= 3) {
                LogX.w("$TAG fling failed on ${rv.javaClass.name}: ${it.javaClass.simpleName}")
            }
            false
        }
        if (!flung) return
        if (loadLogged.get() < LOG_MAX && loadLogged.incrementAndGet() <= LOG_MAX) {
            LogX.i("$TAG autoload fling pos=$pos/$size session=$sessionCount")
        }
    }

    /** holder → item 根 View。先试标准字段名 itemView，再退「面积最大的非 RV 容器」 */
    private fun holderView(holder: Any): View? {
        val key = holder.javaClass.name
        if (holderViewCache.containsKey(key)) {
            val f = holderViewCache[key] ?: return null
            return runCatching { f.get(holder) as? View }.getOrNull()
        }
        // ① ViewHolder.itemView 是 public final，重打包的 androidx 也保留这个字段名
        val direct = runCatching { holder.javaClass.getField("itemView") }.getOrNull()
            ?: runCatching { holder.javaClass.getDeclaredField("itemView") }.getOrNull()
        if (direct != null) {
            runCatching { direct.isAccessible = true }
            holderViewCache[key] = direct
            return runCatching { direct.get(holder) as? View }.getOrNull()
        }
        // ② 兜底（SnsTag 同款策略）：字段里面积最大的 ViewGroup，且必须排除 RecyclerView
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
        val best = cands.filter { it.second is android.view.ViewGroup }
            .maxByOrNull { it.second.width.toLong() * it.second.height }
            ?: cands.firstOrNull()
        holderViewCache[key] = best?.first
        if (best == null) {
            LogX.w("$TAG: no View field on holder ${holder.javaClass.name}")
        }
        return best?.second
    }

    private fun isRv(v: View): Boolean = v.javaClass.name.contains("RecyclerView")

    /** item 根 View → 所在的 RecyclerView（父链向上找，item 的 parent 就是列表） */
    private fun rvOf(v: View?): View? {
        var p = v?.parent
        var up = 0
        while (p is View && up < 6) {
            if (isRv(p)) return p
            p = p.parent
            up++
        }
        return null
    }

    /** adapter → 数据列表。只认 getData()，不做「任意返回 List 的无参方法」兜底 */
    private fun dataList(adapter: Any): List<*>? {
        val key = adapter.javaClass.name
        if (!dataMethodCache.containsKey(key)) {
            val m = adapter.javaClass.methods.firstOrNull {
                it.name == "getData" && it.parameterTypes.isEmpty() &&
                    List::class.java.isAssignableFrom(it.returnType)
            }
            m?.isAccessible = true
            dataMethodCache[key] = m
        }
        val m = dataMethodCache[key] ?: return null
        return runCatching { m.invoke(adapter) as? List<*> }.getOrNull()
    }

    /** 给一个 adapter 类挂上它所有 onBindViewHolder(?, int) 的具体重载；返回新挂条数 */
    private fun hookAdapterClass(c: Class<*>): Int {
        var n = 0
        val ms = runCatching { c.methods }.getOrNull() ?: return 0
        val cands = ms.filter {
            it.name == "onBindViewHolder" && !Modifier.isAbstract(it.modifiers) &&
                it.parameterTypes.size >= 2 &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                (it.parameterTypes.size == 2 ||
                    (it.parameterTypes.size == 3 && it.parameterTypes[2] == List::class.java))
        }
        val solid = cands.filter { !it.isBridge }
        for (m in (solid.ifEmpty { cands })) {
            val key = "${m.declaringClass.name}#${m.name}(${m.parameterTypes.joinToString { p -> p.name }})"
            if (!hookedMethods.add(key)) continue
            if (runCatching { XposedBridge.hookMethod(m, bindHook) }.isSuccess) n++
        }
        return n
    }

    private fun hookSetAdapter(cl: ClassLoader): Int {
        val rv = runCatching { XposedHelpers.findClass(WX_RV_CLASS, cl) }.getOrElse {
            LogX.w("$TAG: $WX_RV_CLASS not found, concrete-adapter hook skipped")
            return 0
        }
        val m = rv.methods.firstOrNull { it.name == "setAdapter" && it.parameterTypes.size == 1 }
            ?: runCatching {
                var c: Class<*>? = rv.superclass
                var up = 0
                var found: Method? = null
                while (c != null && up < 6 && found == null) {
                    found = c.methods.firstOrNull {
                        it.name == "setAdapter" && it.parameterTypes.size == 1
                    }
                    c = c.superclass
                    up++
                }
                found
            }.getOrNull()
        if (m == null) {
            LogX.w("$TAG: setAdapter not found, concrete-adapter hook skipped")
            return 0
        }
        return runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val a = param.args?.getOrNull(0) ?: return
                        if (!a.javaClass.name.startsWith(SNS_ADAPTER_PREFIX)) return
                        hookAdapterClass(a.javaClass)
                    }.onFailure {
                        LogX.w("$TAG setAdapter hook: ${it.javaClass.simpleName}")
                    }
                }
            })
            1
        }.getOrDefault(0)
    }

    // ------------------------------------------------------------------ 诊断

    fun stat(): String = runCatching {
        val c = cfg()
        "MOMENT stat refresh=${c.autoRefresh} load=${c.autoLoad} interval=${c.intervalH}h " +
            "lastAt=$lastRefreshAt env(chg=${charging()},wifi=${wifi()}) ticks=${ticks.get()} " +
            "launches=${launches.get()} launchDisabled=$launchDisabled " +
            "timeline=${timelineCls?.simpleName ?: "-"} active=$timelineActive " +
            "binds=${binds.get()} snsBinds=${snsBinds.get()} loads=${loads.get()} " +
            "grows=${grows.get()} lastSize=$lastSize"
    }.getOrElse { "MOMENT stat error=${it.javaClass.simpleName}" }

    /**
     * 探针入口（`op=moments`）。
     *
     * - `stat` 状态；`env` 当前充电/WiFi；`act` 时间线类捕获情况
     * - `refresh` 立刻触发一次刷新（绕过调度，真机验证用）
     * - `load` 对最近一个时间线列表立刻甩一次底（真机验证用）
     */
    fun probe(sub: String): String = runCatching {
        when (sub.lowercase()) {
            "stat" -> stat()
            "env" -> "MOMENT env charging=${charging()} wifi=${wifi()} ctx=${HostContext.app() != null}"
            "act" -> "MOMENT act timeline=${timelineCls?.name ?: "(未捕获，先手动进一次朋友圈)"} active=$timelineActive"
            "refresh" -> {
                doRefresh(System.currentTimeMillis(), force = true)
                "MOMENT refresh forced launches=${launches.get()} disabled=$launchDisabled"
            }
            "load" -> forceLoad()
            else -> "MOMENT subs=stat|env|act|refresh|load"
        }
    }.getOrElse { "MOMENT probe error=${it.javaClass.simpleName}" }

    /** fling 必须在主线程（探针可能跑在广播的后台线程上） */
    private fun forceLoad(): String {
        val rv = lastRv?.get()
            ?: return "MOMENT load no rv (先进朋友圈滚一下，让钩子看见列表)"
        mainHandler.post {
            runCatching { XposedHelpers.callMethod(rv, "fling", 0, FLING_VY) }
                .onFailure { LogX.w("$TAG load fling failed: ${it.javaClass.simpleName}") }
        }
        return "MOMENT load fling posted rv=${rv.javaClass.simpleName}"
    }
}
