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
import android.view.ViewGroup
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.core.MomentsSyncTrigger
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
 * - **定时刷新**（R25 起改为**纯后台**）：周期性投递微信自己的 `NetSceneSnsSync`
 *   （见 [MomentsSyncTrigger]），让微信去服务端拉一次动态。
 *   目的是「在对方删除前留存」——删动态在本地是物理 DELETE（见 [SnsGuard]），
 *   本地库里有这一行，删除才会被拦下并归档；拉得越勤，漏删窗口越小。
 *
 *   ⚠️ 旧实现是 `startActivity` 把时间线拉到前台：既要求用户先手动进过一次朋友圈
 *   （否则拿不到 Activity 类），又会抢走用户正在用的界面。R25 起不再碰 UI。
 * - **自动加载**：用户滚到时间线底部时，替他把列表再往下滚一段（真机实证：
 *   时间线是 ImproveRecyclerView，微信剥了公有的 fling(int,int)，改用
 *   smoothScrollBy/scrollBy，见 [triggerLoadMore]），让微信自己的
 *   「滑到底加载更多」继续工作，把更多历史动态拉进本地库。
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
 * 2. 滚到底是否稳定触发微信自己的「加载更多」。自观测信号：onBind 里每次看到
 *    `getData().size` 变大会打 `MOMENT autoload grow`——它就是硬判据。
 *    （2026-10-18 真机：grow 0->10 / 10->20 已实证；smoothScrollBy 触发链复验中）
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

    /**
     * 「拉更多」的滚动距离（px，不是速度）。smoothScrollBy(dx,dy) / scrollBy 吃的是像素距离。
     * 2400px ≈ 一屏，足够滚到列表底部触发微信自己的加载更多。
     */
    private const val SCROLL_DY = 2400

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

    /**
     * 时间线 Activity 的**具体类**（用户进过一次朋友圈后在 lifecycle 回调里捕获）。
     *
     * ⚠️ R25 起它**只服务于自动滚动加载**（判断"用户正在看朋友圈"这个会话边界），
     * 不再是定时刷新的前提 —— 定时刷新已改走 [MomentsSyncTrigger] 的后台 NetScene，
     * 用户从来没打开过朋友圈也照样刷新。
     */
    @Volatile
    private var timelineCls: Class<out Activity>? = null

    /** 用户当前是否正在朋友圈界面（用于重置自动加载的会话计数） */
    @Volatile
    private var timelineActive = false

    @Volatile
    private var unknownWarned = false

    /** rv 捕获成功只打一行（每次进朋友圈都会重建，一行足够 */
    @Volatile
    private var rvLogged = false

    @Volatile
    private var lifecycleWarned = false

    private val hookedMethods: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** 已自愈补挂过钩子的具体 adapter 类（onBind 首次见到就补挂，防 setAdapter 没赶上） */
    private val hookedAdapters: MutableSet<String> = Collections.synchronizedSet(HashSet())

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
    /** R25：后台同步投递成功的次数（原 launches，语义已变） */
    private val refreshes = java.util.concurrent.atomic.AtomicLong(0)
    private val binds = java.util.concurrent.atomic.AtomicLong(0)
    private val snsBinds = java.util.concurrent.atomic.AtomicLong(0)
    private val loads = java.util.concurrent.atomic.AtomicLong(0)
    private val grows = java.util.concurrent.atomic.AtomicLong(0)
    private val loadLogged = java.util.concurrent.atomic.AtomicInteger(0)
    private val growLogged = java.util.concurrent.atomic.AtomicInteger(0)
    private val warnLogged = java.util.concurrent.atomic.AtomicInteger(0)
    /** create 捕获兜底命中的日志限流（最多 3 行） */
    private val fallbackLogged = java.util.concurrent.atomic.AtomicInteger(0)
    /** create 钩子见过 sns adapter 但 parent 不是 RV 的诊断计数（最多 5 行） */
    private val createSeen = java.util.concurrent.atomic.AtomicInteger(0)

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching { refresh() }
            .onFailure { LogX.w("$TAG refresh failed: ${it.javaClass.simpleName}") }

        // R25：定时刷新改走后台 NetScene —— 必须在 install 里备好句柄，
        // 否则微信开始干活之后才想起来拿 ClassLoader 会错过时机。
        val syncOk = MomentsSyncTrigger.install(lpparam.classLoader)
        if (!syncOk) LogX.w("$TAG: background sync handles unresolved (见 op=msync sub=stat)")

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
            "$TAG installed hooks=$hooked bgSync=$syncOk " +
                "refresh=${config.autoRefresh} " +
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
        // R25：两个开关**任一**开启都要跑后台同步。
        //
        // 原来只有 autoRefresh 一条路，autoLoad 单独开着时整条后台链路是死的
        // —— 而用户视角里「定时刷新」和「自动加载」都是「模块帮我把朋友圈拉进来」，
        //    看不出也不该关心底下分了两个开关。既然拉新的能力已经后台化，
        //    让 autoLoad 也能触发它才对得上「两个功能都在后台定时执行」。
        //
        // ⚠️ 两者共享同一个 `lastRefreshAt` 节拍（同一份 interval），不会叠加成双倍频率：
        //    autoLoad 在这里**不额外多跑一次**，它只是让「什么情况下允许跑」放宽。
        //    autoLoad 真正独立的部分仍在原有分支：用户打开朋友圈时帮他继续往下滚。
        if (!c.autoRefresh && !c.autoLoad) return
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
     * 到点刷新 —— **后台投递 NetSceneSnsSync，不拉任何界面**。
     *
     * ## R25 改造（这是本轮的核心行为变更）
     *
     * 旧实现：`startActivity` 把 [TIMELINE_ACT] 拉到前台。三个副作用：
     * 1. [timelineCls] 只能靠 lifecycle 捕获 ⇒ 用户**没手动进过朋友圈就永远不刷新**；
     * 2. 一到点就把朋友圈顶到前台，**抢走用户正在用的界面**；
     * 3. `FLAG_ACTIVITY_NEW_TASK` 起 Activity 属于「系统看不懂意图」的操作，
     *    失败一次就得整体 self-disable。
     *
     * 新实现：走 [MomentsSyncTrigger] 直接投递微信自己的同步 NetScene
     * （`x2(256)` → `modelbase/p1.f()`）。全程无 UI、不需要用户进过朋友圈、
     * 也不需要知道 Activity 类名。
     *
     * ⚠️ **Rolling back 的判据**：如果某次微信升级让反射链失效，
     * 日志会长这样 —— `MSYNC FAIL NoSuchMethodException: y70.k1.n()`，
     * 自动轮随即停住（不会刷屏），`op=msync sub=stat` 可见。
     * 是否退化回「拉 Activity」由人工决策，不要自动退化：
     * 抢前台的代价远大于「这一轮没刷成」。
     *
     * @param force 手动探针（`op=moments sub=refresh`）传 true：
     *   绕过 [MomentsSyncTrigger] 的 disabled 闸门进行诊断。
     *   ⚠️ force 只绕过闸门，失败仍会写回 disabled —— 自动轮依然被拦。
     */
    private fun doRefresh(now: Long, force: Boolean = false): Boolean {
        val r = MomentsSyncTrigger.syncNow(force = force)
        val okr = MomentsSyncTrigger.syncOk(r)
        if (okr) {
            refreshes.incrementAndGet()
            Kv.putString(Keys.MOMENTS_LAST_REFRESH_AT, now.toString())
            lastRefreshAt = now
            lastRefreshAtRead = now
            LogX.i("$TAG refresh bg-sent $r")
            // R25：同步刚投递成功 = 最可能有新动态的时刻，把导出那一轮提前，
            // 别让用户等到下一个 10 分钟的 exportDue 窗口。内部自带节流与开关判断。
            runCatching { MomentsExport.pokeExport() }
                .onFailure { LogX.w("$TAG poke export failed: ${it.javaClass.simpleName}") }
        } else {
            LogX.w("$TAG refresh bg-send failed: $r")
        }
        return okr
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
                    if (warnLogged.get() < 3 && warnLogged.incrementAndGet() <= 3) {
                        LogX.w("$TAG bind err: ${t.javaClass.simpleName}")
                    }
                }
        }
    }

    /**
     * rv 只能从 [onCreateViewHolder] 抓：onBind 时 itemView **还没 attach**
     * （真机实证 `p=null`，RecyclerView 先 bind 后 layout），从 item 往父链上找
     * rv 永远失败 —— 曾导致 load 探针恒 "no rv"、grow/fling 整条链路断掉。
     * 这里 args[0] 就是 RecyclerView 本体，且 onCreate 必先于 onBind 发生。
     */
    private val createHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching {
                // ⚠️ 钩子挂基类，全微信所有列表的 create 都会进来 —— 与 onBind:400
                // 同样只认朋友圈的 adapter，否则聊天列表的 RV 会抢走 lastRv，
                // load 探针甩在错误的列表上（真机「探针说成功但没反应」误判）。
                val adapter = param.thisObject ?: return
                if (!adapter.javaClass.name.startsWith(SNS_ADAPTER_PREFIX)) return
                val parent = param.args?.getOrNull(0) as? View ?: return
                if (!isRv(parent)) {
                    // 诊断：sns adapter 的 parent 不是 RV（自愈没生效时靠它定位）
                    if (createSeen.get() < 5) {
                        createSeen.incrementAndGet()
                        LogX.w("$TAG create seen adapter=${adapter.javaClass.name} " +
                            "parent=${parent.javaClass.name} isRv=false")
                    }
                    return
                }
                lastRv = WeakReference(parent)
                if (!rvLogged) {
                    rvLogged = true
                    LogX.i("$TAG rv captured cls=${parent.javaClass.name}")
                }
            }
        }
    }

    private fun onBind(param: XC_MethodHook.MethodHookParam) {
        val adapter = param.thisObject ?: return
        // 钩子挂基类，微信里所有 WxRecyclerAdapter 子类都会进来 → 只认朋友圈的
        if (!adapter.javaClass.name.startsWith(SNS_ADAPTER_PREFIX)) return
        snsBinds.incrementAndGet()

        // 自愈：setAdapter 钩子可能没赶上（时间线 Activity 被系统恢复时，
        // setAdapter 早于本钩子安装就调用完了 → 具体类的 onCreateViewHolder
        // 从来没挂上，rv 捕获断掉——真机 2026-10-18 实证 snsBinds=36 但零 create）。
        // 这里首次见到某具体类就当场补挂（hookedMethods 去重，不会重复挂钩）。
        if (hookedAdapters.add(adapter.javaClass.name)) {
            runCatching { hookAdapterClass(adapter.javaClass) }
                .onFailure { LogX.w("$TAG self-heal hook failed: ${it.javaClass.simpleName}") }
        }

        if (!cfg().autoLoad) return

        val holder = param.args?.getOrNull(0) ?: return
        val pos = param.args?.getOrNull(1) as? Int ?: return
        val view = holderView(holder) ?: return
        val direct = rvOf(view)
        // item 未 attach 时从 create 捕获的 rv 兜底（真机闭环：create 先于 bind）
        val rv = direct ?: lastRv?.get() ?: return
        if (direct == null && fallbackLogged.get() < 3 && fallbackLogged.incrementAndGet() <= 3) {
            LogX.i("$TAG bind fallback rv=${rv.javaClass.simpleName} (create-captured)")
        }
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
        // ⚠️ loads 计的是「尝试」不是「成功」：触发失败时也 ++（失败后退避 1.5s 不重试）。
        // 复判：loads>0 但无 "autoload <how>" 日志 = 触发失败了。
        loads.incrementAndGet()
        val how = triggerLoadMore(rv) ?: return
        if (loadLogged.get() < LOG_MAX && loadLogged.incrementAndGet() <= LOG_MAX) {
            LogX.i("$TAG autoload $how pos=$pos/$size session=$sessionCount")
        }
    }

    /**
     * 触发「拉更多」：把列表往下甩一段。返回成功用的方法名，全失败返回 null。
     *
     * ⚠️ 真机实证（2026-10-18）：时间线是 ImproveRecyclerView（extends
     * WxRecyclerView extends androidx RecyclerView），微信把公有的
     * fling(int,int) 剥了 —— callMethod(rv,"fling",0,vy) 恒 NoSuchMethodError。
     * 反射枚举（`op=moments sub=rvmethods`）确认可用的是：
     * - `smoothScrollBy(int,int)`（动画滚，onScrolled 连续回调，最接近 fling 手感）
     * - `scrollBy(int,int)`（WxRecyclerView 自己的同步滚）
     */
    private fun triggerLoadMore(rv: View): String? {
        return runCatching {
            XposedHelpers.callMethod(rv, "smoothScrollBy", 0, SCROLL_DY)
            "smoothScrollBy"
        }.getOrElse {
            runCatching {
                XposedHelpers.callMethod(rv, "scrollBy", 0, SCROLL_DY)
                "scrollBy"
            }.getOrElse { t2 ->
                if (warnLogged.get() < 3 && warnLogged.incrementAndGet() <= 3) {
                    LogX.w("$TAG load-more trigger failed on ${rv.javaClass.name}: " +
                        "${t2.javaClass.simpleName}")
                }
                null
            }
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

    /** 给一个 adapter 类挂上它所有 onBindViewHolder(?, int) / onCreateViewHolder(?, int) 的具体重载；返回新挂条数 */
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
        // onCreateViewHolder(ViewGroup, int)：parent 就是 rv，见 [createHook]
        val ccands = ms.filter {
            it.name == "onCreateViewHolder" && !Modifier.isAbstract(it.modifiers) &&
                it.parameterTypes.size == 2 &&
                ViewGroup::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        for (m in (ccands.filter { !it.isBridge }.ifEmpty { ccands })) {
            val key = "${m.declaringClass.name}#${m.name}(${m.parameterTypes.joinToString { p -> p.name }})"
            if (!hookedMethods.add(key)) continue
            if (runCatching { XposedBridge.hookMethod(m, createHook) }.isSuccess) n++
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
            // R25：refreshes = 后台 NetScene 投递成功次数（原来叫 launches，统计的是拉 Activity）。
            // ⚠️ 投递成功 ≠ 服务端回来新数据：朋友圈本来没新内容时这一样会 OK。
            //   判断"到底拉没拉到东西"要看 SnsMicroMsg.db 有没有新行，别只看计数长没长。
            "refreshes=${refreshes.get()} bgSync=[${MomentsSyncTrigger.stat()}] " +
            "timeline=${timelineCls?.simpleName ?: "-"} active=$timelineActive " +
            // ⚠️ timeline 仍在这里，但它现在**只是自动滚动加载用的辅助信息**，
            // 不再是定时刷新的前提 —— 用户没进过朋友圈也照样刷新。
            "binds=${binds.get()} snsBinds=${snsBinds.get()} loads=${loads.get()} " +
            "grows=${grows.get()} lastSize=$lastSize " +
            "rv=${lastRv?.get()?.javaClass?.simpleName ?: "-"}"
    }.getOrElse { "MOMENT stat error=${it.javaClass.simpleName}" }

    /**
     * 探针入口（`op=moments`）。
     *
     * - `stat` 状态；`env` 当前充电/WiFi；`act` 时间线类捕获情况
     * - `refresh` 立刻触发一次刷新（绕过调度，真机验证用）
     * - `load` 对最近一个时间线列表立刻甩一次底（真机验证用）
     * - `rvmethods` 反射枚举 rv 类层级上的滚动/fling API（ImproveRecyclerView
     *   没有 androidx 的 fling(int,int)，真机 2026-10-18 实证 NoSuchMethodError；
     *   用枚举结果决定「拉更多」的正确调法，不猜）
     */
    fun probe(sub: String): String = runCatching {
        when (sub.lowercase()) {
            "stat" -> stat()
            "env" -> "MOMENT env charging=${charging()} wifi=${wifi()} ctx=${HostContext.app() != null}"
            // ⚠️ R25 起这里不再是「没手动进过朋友圈就刷不了」，只是说明
            //    "用户正在看朋友圈时才会起作用的自动滚动加载"目前没被激活过。
            "act" -> "MOMENT act timeline=${timelineCls?.name ?: "(未捕获；不影响后台刷新)"} " +
                "active=$timelineActive bgSyncReady=${MomentsSyncTrigger.ready()}"
            "refresh" -> {
                val done = doRefresh(System.currentTimeMillis(), force = true)
                "MOMENT refresh forced ok=$done " +
                    "refreshes=${refreshes.get()} bgSync=[${MomentsSyncTrigger.stat()}]"
            }
            "load" -> forceLoad()
            "rvmethods" -> rvMethods()
            else -> "MOMENT subs=stat|env|act|refresh|load|rvmethods"
        }
    }.getOrElse { "MOMENT probe error=${it.javaClass.simpleName}" }

    /**
     * 枚举 lastRv 类层级（含父类，最多 6 层）上名字含 scroll/fling/smooth/velocity
     * 的方法，逐个打日志（去重、限量 60 行）。真机上跑一次看结果，再定触发 API。
     */
    private fun rvMethods(): String {
        val rv = lastRv?.get() ?: return "MOMENT rvm no rv (先进朋友圈滚一下)"
        val seen = HashSet<String>()
        var c: Class<*>? = rv.javaClass
        var up = 0
        var matched = 0
        var n = 0
        while (c != null && up < 6) {
            for (m in runCatching { c.declaredMethods }.getOrNull().orEmpty()) {
                val name = m.name.lowercase()
                if (!name.contains("scroll") && !name.contains("fling") &&
                    !name.contains("smooth") && !name.contains("velocity")
                ) continue
                val sig = "${m.name}(${m.parameterTypes.joinToString { p -> p.simpleName }})"
                val key = "${c.name}#$sig"
                if (!seen.add(key)) continue
                matched++
                if (n < 60) {
                    n++
                    LogX.i("MOMENT rvm $key")
                }
            }
            c = c.superclass
            up++
        }
        return "MOMENT rvm done cls=${rv.javaClass.name} matched=$matched logged=$n"
    }

    /** 滚动触发必须在主线程（探针可能跑在广播的后台线程上） */
    private fun forceLoad(): String {
        val rv = lastRv?.get()
            ?: return "MOMENT load no rv (先进朋友圈滚一下，让钩子看见列表)"
        mainHandler.post {
            val how = triggerLoadMore(rv)
            if (how == null) LogX.w("$TAG load trigger failed (见 rvmethods 枚举)")
        }
        return "MOMENT load trigger posted rv=${rv.javaClass.simpleName}"
    }
}
