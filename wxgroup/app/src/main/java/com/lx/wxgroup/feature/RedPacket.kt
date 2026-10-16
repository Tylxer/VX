package com.lx.wxgroup.feature

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 需求 6.3：**自动点击拆红包**。
 *
 * ## 边界（别做歪）
 *  **不是**自动点开红包消息 —— 不 hook 消息接收、不在后台触发。
 *  用户手动点开红包、**领取界面弹出时**，自动点「拆红包 / 领取 / 开」按钮，
 *  只省掉最后那一次点击，延迟仍为零（不引入任何 sleep 等待）。开关默认关。
 *
 * ## 两条路线（可降级，不押注单一路线）
 *  **路线 A**：拿到「领取界面已弹到前台」这件事
 *    - A1 `Application.ActivityLifecycleCallbacks.onActivityResumed`（**框架主动回调**，
 *      子类覆不覆写 onResume 都触发）。⚠️ 不能直接 hook `Activity.onResume`：
 *      微信的 Activity 普遍覆写它，hook 基类方法根本不触发（HostContext 里踩过）。
 *    - A2 反射找候选 Activity 类再 hook `onResume`（[CANDIDATE_ACTIVITIES]，**待取证**，
 *      找不到就静默跳过）。
 *  **路线 B**：兜底 —— 用户点红包气泡那一下必然经过 `View.performClick`，
 *    以此为触发，延迟几拍后对**当前窗口 View 树**做一次文案扫描，命中即点。
 *    两条路线共用同一个扫描/点击引擎，且都能被 `probe(sub=fake)` 单独触发验证。
 *
 * ## 安全
 *  - 全局 try/catch，任何异常静默放弃，绝不崩微信；
 *  - **同一界面只点一次**（session 去重 + 全局最小间隔），防连点；
 *  - 文案判定全在 [RedPacketLogic]，保守白名单，误点比不点更糟。
 */
object RedPacket {

    // ================================================================== 待取证常量
    //
    // ⚠️ 下面这些类名**没有在 8.0.48 上取证过**，只是候选。install 时逐个反射查找，
    //    找不到就静默跳过并打一行日志；真实类名/文案靠 `probe(sub=tree)` 打 View 树取证。
    //    取证到真实值后再回来改这里；**不要**拿它当既成事实去推断其它 hook 点。

    /** 候选「领取界面」Activity（反射查找，找不到就跳过）。微信红包是 luckymoney 插件 */
    private val CANDIDATE_ACTIVITIES: List<String> = listOf(
        "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyReceiveUI",
        "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyBeforeDetailUI",
        "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI",
        "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewReceiveUI",
    )

    /** 候选按钮 View 类名（**待取证**，仅用于 tree 里标出来，不参与判定）*/
    private val CANDIDATE_BUTTON_CLASSES: List<String> = listOf(
        "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyAutoScrollViewPager",
        "com.tencent.mm.wallet_core.ui.WalletTextView",
    )

    // ================================================================== 常量

    private const val CFG_TTL_MS = 30_000L
    private const val LOG_MAX = 6

    /** 扫描触发后的三拍延迟：领取界面是异步起来的，第一拍往往还没 attach */
    private val SCAN_DELAYS = longArrayOf(220L, 600L, 1100L)

    /** 两次扫描之间的最小间隔（防抖：路线 A/B 会同时触发）*/
    private const val SCAN_MIN_GAP_MS = 400L

    /** 同一次触发里最多排三拍，别重复排 */
    private const val BURST_MS = 1600L

    /** 两次真实点击之间的最小间隔 */
    private const val CLICK_MIN_GAP_MS = 900L

    /** session 去重保留时长（同时也是「同一界面只点一次」的窗口）*/
    private const val DEDUP_TTL_MS = 20_000L

    private const val MAX_DEPTH = 22
    private const val MAX_NODES = 600

    // ================================================================== 状态

    @Volatile private var enabled = false
    @Volatile private var cfgAt = 0L

    @Volatile private var session = 0
    @Volatile private var topRef: WeakReference<Activity>? = null
    @Volatile private var topName = ""

    @Volatile private var clRef: ClassLoader? = null
    @Volatile private var cbInstalled = false
    @Volatile private var resumeHooks = 0
    @Volatile private var clickHook = false

    @Volatile private var lastScanAt = 0L
    @Volatile private var lastBurstAt = 0L
    @Volatile private var lastClickAt = 0L

    /** 自诊断计数 */
    @Volatile private var resumes = 0        // 任何 Activity 的 resume（路线 A 存活）
    @Volatile private var rpResumes = 0      // 类名像红包界面的 resume
    @Volatile private var taps = 0           // performClick 触发次数（路线 B 存活）
    @Volatile private var scans = 0
    @Volatile private var found = 0          // 扫到「拆红包」文案按钮
    @Volatile private var gateRejects = 0    // 扫到了但页面上下文不像红包界面 → 不点
    @Volatile private var ambiguous = 0      // 同屏多个不同文案的按钮 → 不点（防误点）
    @Volatile private var clicked = 0
    @Volatile private var lastText = ""
    @Volatile private var lastTarget = ""
    @Volatile private var lastNote = ""

    private val logScans = AtomicInteger(0)
    private val logFound = AtomicInteger(0)
    private val logErr = AtomicInteger(0)

    /** 日志限流：LogX 是主线程同步文件 IO，View 树扫描是热路径 */
    private fun logOnce(counter: AtomicInteger, msg: String) {
        if (counter.get() < LOG_MAX && counter.incrementAndGet() <= LOG_MAX) LogX.i("REDPACKET $msg")
    }

    /**
     * session → 已点时间戳。
     * ⚠️ 用 `Collections.synchronizedMap(HashMap())` 而不是 ConcurrentHashMap：
     * 真机上 ConcurrentHashMap 的 null value 直接崩过（NPE），这里靠 containsKey 判定。
     */
    private val dedup: MutableMap<Int, Long> = Collections.synchronizedMap(HashMap())

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // ================================================================== 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            clRef = lpparam.classLoader
            refresh()

            // 路线 A1：生命周期回调（唯一稳的「界面已到前台」信号）
            ensureCallbacks()
            // HostContext 的 app 可能在 install 之后才绑上 → 补几次重试
            for (d in longArrayOf(1500L, 4000L, 10_000L)) {
                mainHandler.postDelayed({ runCatching { ensureCallbacks() } }, d)
            }

            // 路线 A2：候选 Activity 的 onResume（待取证，找不到静默跳过）
            resumeHooks = hookCandidateResumes(lpparam.classLoader)

            // 路线 B：用户点红包气泡那一下必然经过 performClick
            clickHook = hookPerformClick(lpparam.classLoader)

            LogX.i("REDPACKET installed enabled=$enabled " +
                "cb=$cbInstalled resumeHooks=$resumeHooks clickHook=$clickHook")
        }.onFailure { LogX.w("REDPACKET install failed: ${it.javaClass.simpleName}") }
    }

    /** 开关语义：单开关，默认关 */
    fun refresh() {
        enabled = runCatching { Kv.getBool(Keys.REDPACKET_AUTO_OPEN) }.getOrDefault(false)
        cfgAt = SystemClock.elapsedRealtime()
    }

    private fun on(): Boolean {
        if (SystemClock.elapsedRealtime() - cfgAt > CFG_TTL_MS) refresh()
        return enabled
    }

    // ------------------------------------------------------------------ 路线 A1

    private fun ensureCallbacks() {
        if (cbInstalled) return
        val app = runCatching { HostContext.app() }.getOrNull() as? Application ?: return
        runCatching {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                // ⚠️ 不能写成 `= runCatching{...}` 表达式体：ActivityLifecycleCallbacks
                //    的回调返回 Unit，runCatching 返回 Result → 返回类型不兼容编译不过
                override fun onActivityResumed(a: Activity) { runCatching { onResumed(a) } }
                override fun onActivityPaused(a: Activity) = Unit
                override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
                override fun onActivityStarted(a: Activity) = Unit
                override fun onActivityStopped(a: Activity) = Unit
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
                override fun onActivityDestroyed(a: Activity) = Unit
            })
            cbInstalled = true
            LogX.i("REDPACKET lifecycle callbacks registered")
        }.onFailure {
            LogX.w("REDPACKET lifecycle cb failed: ${it.javaClass.simpleName}")
        }
    }

    private fun onResumed(a: Activity) {
        resumes++
        topRef = WeakReference(a)
        topName = a.javaClass.name
        session++
        if (RedPacketLogic.isRedPacketClass(topName)) {
            rpResumes++
            logOnce(logScans, "resume rp=$topName")
        }
        scheduleScan("resume")
    }

    // ------------------------------------------------------------------ 路线 A2

    /** 反射找候选 Activity 并 hook onResume；返回成功条数（找不到静默跳过）*/
    private fun hookCandidateResumes(cl: ClassLoader): Int {
        var n = 0
        for (name in CANDIDATE_ACTIVITIES) {
            val c = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull()
            if (c == null) {
                LogX.i("REDPACKET candidate not found: $name")
                continue
            }
            val ok = runCatching {
                XposedHelpers.findAndHookMethod(c, "onResume", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val act = param.thisObject as? Activity ?: return@runCatching
                            topRef = WeakReference(act)
                            topName = act.javaClass.name
                            session++
                            rpResumes++
                            scheduleScan("onResume")
                        }
                    }
                })
            }.isSuccess
            if (ok) {
                n++
                LogX.i("REDPACKET hooked onResume: $name")
            }
        }
        return n
    }

    // ------------------------------------------------------------------ 路线 B

    /**
     * hook `View.performClick`。
     *
     * ⚠️ 这是**热路径**（微信里所有点击都过）。所以这里只做「排一次扫描」这一件事，
     * 不解析 View、不读文本 —— 一次 volatile 读 + 一次 CAS 就完事，扫描本身还带
     * [SCAN_MIN_GAP_MS] 防抖。真正干活的是 [scanOnce]。
     */
    private fun hookPerformClick(cl: ClassLoader): Boolean {
        return runCatching {
            XposedHelpers.findAndHookMethod(
                XposedHelpers.findClass("android.view.View", cl),
                "performClick",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            taps++
                            if (!on()) return@runCatching
                            scheduleScan("tap")
                        }
                    }
                }
            )
            true
        }.onFailure { LogX.w("REDPACKET performClick hook failed: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
    }

    // ------------------------------------------------------------------ 调度

    private fun scheduleScan(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBurstAt < BURST_MS) return // 已经在排这一轮了
        lastBurstAt = now
        if (!on() && reason != "probe") return
        for (d in SCAN_DELAYS) {
            mainHandler.postDelayed({
                runCatching { scanOnce(force = false, allowClick = true, reason = reason) }
                    .onFailure {
                        if (logErr.get() < 3 && logErr.incrementAndGet() <= 3) {
                            LogX.w("REDPACKET scan err: ${it.javaClass.simpleName}")
                        }
                    }
            }, d)
        }
        lastNote = "$reason@$now"
    }

    // ================================================================== 扫描 + 点击

    /** 一个候选按钮 */
    private class Cand(val view: View, val text: String, val cls: String, val w: Int, val h: Int) {
        override fun toString(): String = "\"$text\" $cls ${w}x$h"
    }

    /**
     * 扫当前窗口 View 树 → 命中「拆红包」按钮 → 点一次。
     * **必须在主线程调**。
     *
     * @param force 忽略开关（probe 用）
     * @param allowClick false = 只扫描不点（probe dry 用）
     */
    private fun scanOnce(force: Boolean, allowClick: Boolean, reason: String): String {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanAt < SCAN_MIN_GAP_MS) return "skip(gap)"
        lastScanAt = now
        if (!force && !on()) return "skip(off)"
        scans++

        val act = topRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
            ?: runCatching { HostContext.activity() }.getOrNull()
        if (act == null) return "no activity"
        val decor = runCatching { act.window?.decorView }.getOrNull()
        if (decor == null) return "no decorView"

        val texts = ArrayList<String>()
        val cands = ArrayList<Cand>()
        var nodes = 0

        fun walk(v: View, d: Int) {
            if (d > MAX_DEPTH || nodes >= MAX_NODES) return
            nodes++
            val visible = runCatching { v.isShown }.getOrDefault(false)
            val enabled = runCatching { v.isEnabled }.getOrDefault(true)
            val w = runCatching { v.width }.getOrDefault(0)
            val h = runCatching { v.height }.getOrDefault(0)
            val clickable = runCatching { v.isClickable }.getOrDefault(false)

            if (v is TextView) {
                val t = runCatching { v.text?.toString() }.getOrNull()
                if (!t.isNullOrBlank()) {
                    if (texts.size < 40) texts.add(t)
                    if (RedPacketLogic.isOpenText(t) &&
                        RedPacketLogic.nodeOk(clickable, enabled, visible, w, h)
                    ) {
                        cands += Cand(v, t, v.javaClass.name, w, h)
                    }
                }
            } else {
                // 自定义按钮（无 TextView 文本）走 contentDescription —— 那是无障碍文案，
                // 不是我们编的；拿不到就算了，绝不猜。
                val cd = runCatching { v.contentDescription?.toString() }.getOrNull()
                if (!cd.isNullOrBlank() &&
                    RedPacketLogic.isOpenText(cd) &&
                    RedPacketLogic.nodeOk(clickable, enabled, visible, w, h)
                ) {
                    cands += Cand(v, cd, v.javaClass.name, w, h)
                }
            }

            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val c = runCatching { v.getChildAt(i) }.getOrNull() ?: continue
                    walk(c, d + 1)
                }
            }
        }
        walk(decor, 0)

        if (cands.isEmpty()) return "no button (texts=${texts.size})"

        // ⚠️ 同屏出现**不同文案**的多个候选 = 分不清哪个是拆红包 → 一律不点。
        // 误点（点到「关闭」）比漏点糟糕得多。
        val distinct = cands.map { RedPacketLogic.normalize(it.text) }.distinct()
        if (distinct.size > 1) {
            ambiguous++
            lastText = cands.joinToString("|") { it.text }
            logOnce(logFound, "ambiguous skip: ${cands.joinToString(";")}")
            return "ambiguous ${cands.size}: ${distinct.joinToString("|")}"
        }

        found++
        val target = cands.firstOrNull { runCatching { it.view.isClickable }.getOrDefault(false) }
            ?: cands.first()
        lastText = target.text
        lastTarget = target.cls

        if (!RedPacketLogic.shouldClick(texts, target.text)) {
            gateRejects++
            logOnce(logFound, "gate reject txt=\"${target.text}\" page=[${
                texts.take(6).joinToString("|") { it.take(12) }}]")
            return "gate reject \"${target.text}\""
        }
        if (!allowClick) return "dry hit \"${target.text}\" $target"

        // 同一界面只点一次
        val s = session
        val t0 = dedup[s]
        if (t0 != null && now - t0 < DEDUP_TTL_MS) return "dup session=$s"
        if (now - lastClickAt < CLICK_MIN_GAP_MS) return "click gap"

        val real = clickTarget(target.view)
        val ok = tap(real)
        dedup[s] = now
        if (dedup.size > 64) synchronized(dedup) { dedup.clear() }
        if (ok) {
            clicked++
            lastClickAt = now
            logOnce(logFound, "clicked \"${target.text}\" ${target.cls} -> ${real.javaClass.name}")
        } else {
            logOnce(logFound, "click failed \"${target.text}\" ${target.cls}")
        }
        return if (ok) "clicked \"${target.text}\"" else "click failed \"${target.text}\""
    }

    /**
     * 真正要点下去的那个 View。
     * 微信的按钮经常是「不可点的 TextView + 可点的父容器」，只点 TextView 会毫无反应。
     * ⚠️ 最多上溯 3 层，且父级**自己不能有别的文案**（有就说明它是个更大的容器，
     * 点它不知道会触发什么）。
     */
    private fun clickTarget(v: View): View {
        if (runCatching { v.isClickable }.getOrDefault(true)) return v
        var p = v.parent
        var up = 0
        while (p is View && up < 3) {
            val own = (p as? TextView)?.let { runCatching { it.text?.toString() }.getOrNull() }
            val blank = own.isNullOrBlank()
            val sameKind = !blank && RedPacketLogic.isOpenText(own)
            if (p.isClickable && (blank || sameKind)) return p
            p = p.parent
            up++
        }
        return v
    }

    /**
     * 点。先 `performClick()`（走 OnClickListener，最准）；返回 false 说明这个 View
     * 上没挂 listener，再用一次坐标触摸兜底（直接 dispatch 给这个 View，不会误派给别的）。
     */
    private fun tap(v: View): Boolean {
        if (runCatching { v.performClick() }.getOrDefault(false)) return true
        return runCatching {
            val x = v.width / 2f
            val y = v.height / 2f
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(t, t + 10, MotionEvent.ACTION_UP, x, y, 0)
            try {
                v.dispatchTouchEvent(down)
                v.dispatchTouchEvent(up)
            } finally {
                down.recycle()
                up.recycle()
            }
            true
        }.getOrDefault(false)
    }

    // ================================================================== probe

    /**
     * 真机取证入口。
     * - `stat` 开关 + 命中次数 + 钩子存活（resumes/taps 为 0 说明钩子没挂上）
     * - `tree` 当前界面 View 树 / 节点文案 dump（**取证真实类名与按钮文案**就靠它）
     * - `fake` 强制模拟点一次（忽略开关）；`fake dry` 只扫不点
     * - `reset` 清去重与计数
     */
    fun probe(sub: String): String = runCatching {
        when (sub.trim().lowercase()) {
            "stat" -> stat()
            "tree" -> onMain { tree() }
            "fake" -> onMain { "REDPACKET fake " + scanOnce(true, true, "probe") + "\n" + tree(60) }
            "fake dry", "dry" -> onMain { "REDPACKET dry " + scanOnce(true, false, "probe") }
            "reset" -> {
                synchronized(dedup) { dedup.clear() }
                clicked = 0; found = 0; scans = 0; gateRejects = 0; ambiguous = 0
                logScans.set(0); logFound.set(0); logErr.set(0)
                "REDPACKET reset"
            }
            else -> "REDPACKET probe: stat | tree | fake | fake dry | reset"
        }
    }.getOrDefault("REDPACKET probe err: $sub")

    private fun stat(): String {
        val sb = StringBuilder("REDPACKET stat enabled=${on()} ")
        sb.append("switch=${runCatching { Kv.getBool(Keys.REDPACKET_AUTO_OPEN) }.getOrDefault(false)} ")
        sb.append("hooks[cb=$cbInstalled resume=$resumeHooks click=$clickHook] ")
        sb.append("resumes=$resumes rp=$rpResumes taps=$taps scans=$scans ")
        sb.append("found=$found gateReject=$gateRejects ambiguous=$ambiguous clicked=$clicked ")
        sb.append("top=$topName lastTxt=\"$lastText\" lastCls=$lastTarget note=$lastNote")
        sb.append("\nREDPACKET openTexts=${RedPacketLogic.openTexts().joinToString("|")}")
        // 候选类名到底存不存在：取证第一步
        val cl = clRef
        if (cl != null) {
            sb.append("\nREDPACKET candidates:")
            for (n in CANDIDATE_ACTIVITIES) {
                val ok = runCatching { XposedHelpers.findClass(n, cl) }.isSuccess
                sb.append("\n  ").append(if (ok) "FOUND " else "  -   ").append(n)
            }
        }
        return sb.toString()
    }

    /**
     * View 树 dump。**取证真实类名与按钮文案**就靠它：
     * 真发一个 0.01 的红包 → 界面停在「开」那一步 → 打这个 → 从 log 里抄真实类名/文案。
     */
    private fun tree(maxLines: Int = 220): String {
        val act = runCatching { HostContext.activity() }.getOrNull()
            ?: topRef?.get()
        if (act == null) return "REDPACKET tree: no activity"
        val decor = runCatching { act.window?.decorView }.getOrNull()
            ?: return "REDPACKET tree: no decorView"
        val sb = StringBuilder("REDPACKET tree act=${act.javaClass.name}")
        sb.append(" rpClass=${RedPacketLogic.isRedPacketClass(act.javaClass.name)}\n")
        var lines = 0
        var nodes = 0
        fun walk(v: View, d: Int) {
            if (lines >= maxLines || d > MAX_DEPTH || nodes >= MAX_NODES) return
            nodes++
            val pad = "  ".repeat(d)
            val w = runCatching { v.width }.getOrDefault(0)
            val h = runCatching { v.height }.getOrDefault(0)
            val vis = runCatching { v.isShown }.getOrDefault(false)
            val clk = runCatching { v.isClickable }.getOrDefault(false)
            val txt = runCatching { (v as? TextView)?.text?.toString() }.getOrNull()
            val cd = runCatching { v.contentDescription?.toString() }.getOrNull()
            val kind = RedPacketLogic.classify(txt ?: cd)
            sb.append("REDPACKET $pad${v.javaClass.name} ${w}x$h")
            sb.append(if (vis) " shown" else " hidden").append(if (clk) " click" else "")
            if (!txt.isNullOrBlank()) sb.append(" txt=\"${txt.take(24)}\"")
            if (!cd.isNullOrBlank()) sb.append(" cd=\"${cd.take(24)}\"")
            if (kind != RedPacketLogic.Kind.IGNORE) sb.append(" <<$kind>>")
            sb.append('\n')
            lines++
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val c = runCatching { v.getChildAt(i) }.getOrNull() ?: continue
                    walk(c, d + 1)
                }
            }
        }
        walk(decor, 0)
        // 顺带把候选按钮类在这一屏里有没有列出来（取证用）
        val names = HashSet<String>()
        fun collect(v: View, d: Int) {
            if (d > MAX_DEPTH) return
            names.add(v.javaClass.name)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    runCatching { v.getChildAt(i) }.getOrNull()?.let { collect(it, d + 1) }
                }
            }
        }
        collect(decor, 0)
        sb.append("REDPACKET tree classes(${names.size}):\n")
        for (n in names.filter { it.contains("lucky", true) || it.contains("wallet", true) }.take(20)) {
            sb.append("  $n\n")
        }
        return sb.toString()
    }

    /** 在指定行里跑一段主线程代码（probe 可能从广播线程进来）*/
    private fun onMain(block: () -> String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var res = "(timeout)"
        val latch = CountDownLatch(1)
        mainHandler.post {
            res = runCatching { block() }.getOrDefault("(err)")
            latch.countDown()
        }
        runCatching { latch.await(2500, TimeUnit.MILLISECONDS) }
        return res
    }
}
