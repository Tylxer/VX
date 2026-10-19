package com.lx.wxgroup.feature

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
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
 * ## 三条触发路线（互为兜底）
 *  **A1** `Application.ActivityLifecycleCallbacks.onActivityResumed`（**框架主动回调**，
 *    子类覆不覆写 onResume 都触发）。⚠️ 不能直接 hook `Activity.onResume`：
 *    微信的 Activity 普遍覆写它，hook 基类方法根本不触发（HostContext 里踩过）。
 *  **A2** 反射找候选 Activity 类再 hook `onResume`（[CANDIDATE_ACTIVITIES]，
 *    8.0.48 上 `LuckyMoneyBeforeDetailUI` / `LuckyMoneyDetailUI` 已实证存在，
 *    `LuckyMoneyReceiveUI` 不存在 → 静默跳过）。
 *  **B** 兜底 —— 用户点红包气泡那一下经过 `View.performClick`，以此当**额外触发信号**，
 *    延迟几拍后对**当前窗口 View 树**做一次文案扫描。
 *    ⚠️ `performClick` 只用于**触发扫描**，不再用于**点击**：它对微信的 PAG 按钮无效
 *    （返回 true 但红包不开），真正的点击见 [tap]。
 *    三条路线共用同一个扫描/点击引擎，且都能被 `probe(sub=fake)` 单独触发验证。
 *
 * ## 安全
 *  - 全局 try/catch，任何异常静默放弃，绝不崩微信；
 *  - **同一个「开」按钮最多派发 N 次触摸**（N = 设置页的 `REDPACKET_MAX_ATTEMPTS`，默认 3），
 *    外加全局最小点击间隔；配额在**跨页进入红包页**时清零 —— 见 [resend] 的注释；
 *  - 文案判定全在 [RedPacketLogic]，保守白名单，误点比不点更糟。
 *
 * ## 验收口径（2026-10-19 定，别再混淆）
 *  - `dispatched` = 触摸事件**派发出去了**（不等于拆开）
 *  - `opened`     = **真拆开了**（离开领取页且新页仍是红包类）← 唯一验收指标
 *  - `leftPage`   = 派发后离开了红包体系（返回/来电/误点关闭），**不算**拆开
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

    // ⚠️ 曾有一个 `CANDIDATE_BUTTON_CLASSES`（LuckyMoneyAutoScrollViewPager / WalletTextView），
    //    注释写着"仅用于 tree 里标出来"，但 `tree()` 从来没读过它 —— 已删除。
    //    真机的「开」实际是 **`android.widget.ImageButton` 360x360@540,1853**，
    //    由文案（`text`/`contentDescription` = "开"）命中，不靠类名。

    // ================================================================== 常量

    private const val CFG_TTL_MS = 30_000L
    private const val LOG_MAX = 6

    /**
     * 扫描触发后的**基准**延迟。
     *
     * 领取界面是异步起来的：resume 那一刻 decorView 里还没有「开」按钮（封面/金额还在拉网络），
     * 所以必须多拍几次。三拍按 [REDPACKET_DELAY_MS] 缩放：
     * 默认 800ms → 220 / 600 / 1100 附近；用户把「识别后的等待时长」调大，三拍同步后移。
     *
     * ⚠️ 2026-10-19 真机实测：只用 220/600/1100 三拍时，红包领界面 `found=0`（三次都没扫到），
     * 而人工再点 `dry` 立刻命中 `cd="开"` —— 说明**不是判据错，是扫得太早**。
     * 现在额外补一拍「长尾」延迟（见 [LONG_TAIL_MS]），网络慢的机型才等得到按钮。
     */
    private val scanDelays: LongArray
        get() {
            val base = runCatching { Kv.getString(Keys.REDPACKET_DELAY_MS).toLongOrNull() }
                .getOrNull()?.coerceIn(200L, 5_000L) ?: 800L
            return longArrayOf(
                base / 3,              // ≈ 266
                base * 3 / 4,          // ≈ 600
                base * 11 / 8,         // ≈ 1100
                base + LONG_TAIL_MS,   // ≈ 2800 —— 留给网络慢的那次
            )
        }

    /** 最长那一拍的额外等待（覆盖「开」按钮迟迟不出现的情况）*/
    private const val LONG_TAIL_MS = 2_000L

    /** 两次扫描之间的最小间隔（防抖：路线 A/B 会同时触发）*/
    private const val SCAN_MIN_GAP_MS = 200L

    /** 同一次触发里最多排几拍，别重复排（要比 scanDelays 的最大值大）*/
    private const val BURST_MS = 5_200L

    /** 没扫到按钮时补扫的间隔 */
    private const val EMPTY_RETRY_MS = 1_500L

    /** 最多补扫几次（防止在非红包页面上无限扫）*/
    private const val MAX_EMPTY_RETRIES = 4

    /** 两次真实点击之间的最小间隔 */
    private const val CLICK_MIN_GAP_MS = 900L

    /**
     * 触摸派发后**第一次**去看结果的时间。
     *
     * ⚠️ 不能只看一次（2026-10-19 真机实测翻车）：红包从「派发触摸」到真的跳到
     * `LuckyMoneyDetailUI` 实测用了 **1226ms**（`56.370 dispatched` → `57.596 BeforeDetail`）。
     * 原来只查一次 900ms，查的时候按钮还在 → 误判"空响" → `opened` 永远是 0
     * （**功能成功但指标假红**，验收会得反结论）。所以改成轮询，见 [VERIFY_TOTAL_MS]。
     */
    private const val VERIFY_MS = 900L

    /** 轮询间隔（首查之后）*/
    private const val VERIFY_STEP_MS = 400L

    /**
     * 轮询总时长。
     *
     * 覆盖"派发 → 转圈 → CGI 往返 → 跳详情页"整条链（实测 1.2s，网络差时更久）。
     * 超过这个时长还没动静才认定真的空响。**不是** 900ms —— 那个数会把慢的成功判死。
     */
    private const val VERIFY_TOTAL_MS = 4_000L

    /**
     * 同一个按钮**总共**最多派发几次触摸 = **「同一红包最多试图决策几次」**（`REDPACKET_MAX_ATTEMPTS`）。
     *
     * ⚠️ 这个配置从 v0.1 就摆进设置页（默认 3），但直到 2026-10-19 都**没有任何代码读它**
     * —— 是个空壳（`docs/取证结论.md` 缺陷 1 记的就是这类问题）。现在它真的生效了：
     * 设置页改小 → 少补发；改大 → 多给几次机会。下限 1（至少点一次），上限 4
     * （再多就不是"动画没播完"而是判据错了，多点只是徒增风控特征）。
     */
    private val maxAttempts: Int
        get() = runCatching { Kv.getString(Keys.REDPACKET_MAX_ATTEMPTS).toIntOrNull() }
            .getOrNull()?.coerceIn(1, 4) ?: 2

    /**
     * 验证发现"按钮还在"时，隔多久补发下一次触摸。
     *
     * ⚠️ **不能复用 [retryOnEmpty]**：那个走 [emptyRetries] 配额（"扫不到按钮"的计数），
     * 语义不同 —— 补发时按钮明明在，只是没点动；共用配额会让补发被"空扫次数已用完"提前掐掉。
     * 700ms 留给微信播完"按下"动画。
     */
    private const val RESEND_DELAY_MS = 700L

    private const val MAX_DEPTH = 22
    private const val MAX_NODES = 600

    // ================================================================== 状态

    @Volatile private var enabled = false
    @Volatile private var cfgAt = 0L

    @Volatile private var topRef: WeakReference<Activity>? = null
    @Volatile private var topName = ""

    @Volatile private var clRef: ClassLoader? = null
    @Volatile private var cbInstalled = false
    @Volatile private var resumeHooks = 0
    @Volatile private var clickHook = false

    @Volatile private var lastScanAt = 0L
    @Volatile private var lastBurstAt = 0L
    @Volatile private var lastClickAt = 0L

    /** 本次「红包页停留」里已经补扫了几次（resume 时归零）*/
    @Volatile private var emptyRetries = 0

    /** 自诊断计数 */
    @Volatile private var resumes = 0        // 任何 Activity 的 resume（路线 A 存活）
    @Volatile private var rpResumes = 0      // 类名像红包界面的 resume
    @Volatile private var taps = 0           // performClick 触发次数（路线 B 存活）
    @Volatile private var scans = 0
    @Volatile private var found = 0          // 扫到「拆红包」文案按钮
    @Volatile private var gateRejects = 0    // 扫到了但页面上下文不像红包界面 → 不点
    @Volatile private var ambiguous = 0      // 同屏多个不同文案的按钮 → 不点（防误点）
    @Volatile private var clicked = 0
    @Volatile private var opened = 0         // **真正拆开**（派发后界面离开了领取页）
    @Volatile private var leftPage = 0       // 派发后离开了红包体系（返回/来电/误点关闭）→ 不算拆开
    @Volatile private var resendBlocked = 0  // 同一个按钮重发上限用尽，放弃
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
     * 「开」按钮的身份键 → 已经派发过几次触摸。
     *
     * 键 = **按钮 View 的 identityHashCode + 页面类名**。
     *
     * ⚠️ 两层坑，都真机踩过，别退回去：
     *  1. **不能用全局 session 做键**：拆完一个红包会连跳
     *     `NotHookReceiveUI → BeforeDetailUI → DetailUI → NotHookReceiveUI`，
     *     每次 resume 都自增；只要期间没 resume（被节流吞掉），
     *     用户手动点开的**下一个**红包就命中「同一界面已点过」→ **静默不拆**。
     *  2. **光换 View 身份也不够**：领取页是常驻 Activity、只 inflate 一次，
     *     「开」是**同一个 View 对象** → 上一个红包配额用尽会把下一个锁死。
     *     所以配额在 `onResumed` 检测到「非红包页 → 红包页」时清零，见那里的注释。
     *
     * ⚠️ 用 `Collections.synchronizedMap(HashMap())` 而不是 ConcurrentHashMap：
     * 真机上 ConcurrentHashMap 的 null value 直接崩过（NPE）。
     */
    private val resend: MutableMap<Int, Int> = Collections.synchronizedMap(HashMap())

    /** 身份键：同一个按钮对象 + 同一个页面 = 同一次点击机会 */
    private fun dedupKey(target: View): Int {
        val h = System.identityHashCode(target)
        return h * 31 + topName.hashCode()
    }

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
        val prevTop = topName
        topRef = WeakReference(a)
        topName = a.javaClass.name
        val isRp = RedPacketLogic.isRedPacketClass(topName)
        if (isRp) {
            rpResumes++
            logOnce(logScans, "resume rp=$topName")
            // ⚠️ 红包页的 resume **必须绕过节流**（force=true）。
            // 真机实测（2026-10-19）：拆完一个红包会连着走
            // NotHookReceiveUI → BeforeDetailUI → DetailUI → NotHookReceiveUI，
            // 三次 resume 挤在 4~8 秒内，被 BURST_MS(5.2s) 吞掉之后，
            // **用户手动点开的下一个红包就再也不触发扫描了**（scans 卡住不涨）。
            // 红包页 resume 是"用户真的要看一个红包"的强信号，宁可多扫几次也不能漏。
            emptyRetries = 0
            // ⚠️ **从非红包页进入红包页** = 用户点了新红包（复核 B4）。
            // 微信的领取页 Activity 是常驻的、只 inflate 一次，「开」按钮是**同一个 View 对象**。
            // 上一个红包派发满 2 次仍未开（比如网络失败），`resend[key]` 会一直停在 2，
            // 导致 20s 内下一个红包命中"配额用尽"被**静默跳过** —— 这正是本轮要修的那个回归。
            // 只在"跨页进入"时清（NotHook→BeforeDetail 是红包页→红包页，链内补发不受影响）。
            val fromRp = RedPacketLogic.isRedPacketClass(prevTop)
            if (!fromRp && prevTop.isNotEmpty()) {
                synchronized(resend) { resend.clear() }
                LogX.i("REDPACKET new rp entry $prevTop -> $topName (resend reset)")
            }
        }
        scheduleScan("resume", force = isRp)
    }

    // ------------------------------------------------------------------ 路线 A2

    /**
     * 反射找候选 Activity 并 hook `onResume`；返回成功条数（找不到静默跳过）。
     *
     * ⚠️ **A2 只是 A1 的兜底，A1 成功时 A2 整条不生效**（2026-10-19 定）。
     * 候选 Activity 的 resume 本来就会被 `ActivityLifecycleCallbacks` 回调一次，
     * 两条同时生效 = 同一个 resume 被处理两次 → `resumes` 重复计数、
     * `scheduleScan` 排出双份扫描、`resend` 清零跑两次。
     * A1 已经真机验证可用（`hooks[cb=true]`），所以 A2 只在 A1 没注册上时才值班。
     */
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
                        // A1 已经覆盖「本 Activity 到了前台」这件事 → 这里直接让位
                        if (cbInstalled) return
                        runCatching {
                            val act = param.thisObject as? Activity ?: return@runCatching
                            // 复用 onResumed，别在钩子里重写一份：force / resend 清零
                            // 这些逻辑一旦抄两遍，迟早会走岔。
                            onResumed(act)
                        }
                    }
                })
            }.isSuccess
            if (ok) {
                n++
                LogX.i("REDPACKET hooked onResume(fallback): $name")
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

    /**
     * 排一轮扫描。
     *
     * @param force 跳过节流。**红包页 resume / 探针**必须传 true：
     *              拆完一个红包会连跳三四个红包 Activity，节流会把「用户手动点开的下一个红包」一起吞掉。
     */
    private fun scheduleScan(reason: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastBurstAt < BURST_MS) return // 已经在排这一轮了
        lastBurstAt = now
        if (!on() && reason != "probe") return
        for (d in scanDelays) {
            mainHandler.postDelayed({
                runCatching { scanOnce(force = false, allowClick = true, reason = reason) }
                    .onFailure {
                        if (logErr.get() < 3 && logErr.incrementAndGet() <= 3) {
                            LogX.w("REDPACKET scan err: ${it.javaClass.simpleName}")
                        }
                    }
            }, d)
        }
        lastNote = "$reason@$now${if (force) "(force)" else ""}"
    }

    /**
     * 没扫到按钮时补一轮。**只补有限次**（[MAX_EMPTY_RETRIES]），避免在非红包页面无限扫描。
     *
     * 为什么需要：领取界面是异步的，封面/金额/动画都要等网络。
     * 真机实测网络慢时，固定几拍延迟全部落空 → `found=0` → 用户干等，
     * 而人工再点一次 probe 立刻命中 —— 说明按钮是**晚到**，不是没有。
     */
    private fun retryOnEmpty(reason: String) {
        if (emptyRetries >= MAX_EMPTY_RETRIES) return
        if (!on()) return
        emptyRetries++
        val delay = EMPTY_RETRY_MS
        mainHandler.postDelayed({
            // 补扫时 reason 带后缀，方便在日志里区分「补扫」和「首扫」
            runCatching { scanOnce(force = false, allowClick = true, reason = "$reason/retry$emptyRetries") }
        }, delay)
    }

    // ================================================================== 扫描 + 点击

    /** 一个候选按钮 */
    private class Cand(val view: View, val text: String, val cls: String, val w: Int, val h: Int) {
        override fun toString(): String = "\"$text\" $cls ${w}x$h"
    }

    /**
     * 一次扫描的**结构化**结果。
     *
     * ⚠️ 为什么不能用 String 前缀判断（2026-10-19 复核揪出）：`scanOnce` 的返回有一堆
     * 完全不同的语义 —— `"skip(gap)"`/`"no activity"`/`"no decorView"` 表示**根本没扫**，
     * 而 `"no button"` 表示**扫了但没有**。原来 verify 只判 `startsWith("no button")`，
     * 其余一律当"按钮还在"→ 在"没扫成"时盲目补发第二次触摸；
     * 反过来又把"页面正在动画/还没 layout"的 `no button` 当成"按钮消失了"→ 假绿。
     *
     * 判据必须分成四类，[SKIPPED] / [NO_ACTIVITY] 既不算成功也不补发。
     */
    private enum class Scan {
        /** 命中「开」并（可能）派发了触摸 */
        HIT,
        /** 真的扫完了，没有任何「开」候选 */
        NO_BUTTON,
        /** 扫到了但被护栏拒绝（上下文不像红包页 / 同屏多文案分不清） */
        REJECTED,
        /** 这次压根没扫：节流、开关关、没有 Activity/decorView */
        SKIPPED,
    }

    private class ScanResult(val kind: Scan, val note: String, val texts: Int = 0) {
        override fun toString(): String = note
    }

    private fun scanResult(kind: Scan, note: String, texts: Int = 0) = ScanResult(kind, note, texts)

    /**
     * 扫当前窗口 View 树 → 命中「拆红包」按钮 → 点一次。
     * **必须在主线程调**。
     *
     * @param force 忽略开关（probe 用）
     * @param allowClick false = 只扫描不点（probe dry 用）
     */
    private fun scanOnce(
        force: Boolean,
        allowClick: Boolean,
        reason: String,
    ): ScanResult {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanAt < SCAN_MIN_GAP_MS) return scanResult(Scan.SKIPPED, "skip(gap)")
        lastScanAt = now
        if (!force && !on()) return scanResult(Scan.SKIPPED, "skip(off)")
        scans++

        val act = topRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
            ?: runCatching { HostContext.activity() }.getOrNull()
        if (act == null) return scanResult(Scan.SKIPPED, "no activity")
        val decor = runCatching { act.window?.decorView }.getOrNull()
            ?: return scanResult(Scan.SKIPPED, "no decorView")
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

        // ⚠️ 按钮还没渲染出来（网络慢/封面动画未结束）→ 补扫，别就这么放弃。
        //    probe / verify 两路不补扫：probe 是人工取证，verify 自己有轮询节奏，
        //    再叠一层 retryOnEmpty 会双重排程、把空扫配额提前耗光。
        if (cands.isEmpty()) {
            if (reason != "probe" && reason != "verify") retryOnEmpty(reason)
            return scanResult(Scan.NO_BUTTON, "no button (texts=${texts.size})", texts.size)
        }

        // ⚠️ 同屏出现**不同文案**的多个候选 = 分不清哪个是拆红包 → 一律不点。
        // 误点（点到「关闭」）比漏点糟糕得多。
        val distinct = cands.map { RedPacketLogic.normalize(it.text) }.distinct()
        if (distinct.size > 1) {
            ambiguous++
            lastText = cands.joinToString("|") { it.text }
            logOnce(logFound, "ambiguous skip: ${cands.joinToString(";")}")
            return scanResult(Scan.REJECTED, "ambiguous ${cands.size}: ${distinct.joinToString("|")}")
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
            return scanResult(Scan.REJECTED, "gate reject \"${target.text}\"")
        }
        if (!allowClick) return scanResult(Scan.HIT, "dry hit \"${target.text}\" $target")

        // ── 派发上限 ────────────────────────────────────────────────────────
        // 语义变了（2026-10-19）：以前"点过"就算完；现在"派发成功但界面没走"
        // 要允许补发，因为微信的"开"是 PAG 动画按钮，动画未结束就点会空响。
        // 上限 = 设置页的「同一红包最多试图决策几次」(REDPACKET_MAX_ATTEMPTS)，默认 3。
        // 只在**跨页进入红包页**时清零（见 onResumed），不按时钟滚动，
        // 否则卡住的页面会被"过一会儿再来几次"无限连点。
        val s = dedupKey(target.view)
        val sentSoFar = resend[s] ?: 0
        if (sentSoFar >= maxAttempts) {
            resendBlocked++
            logOnce(logFound, "resend exhausted key=$s sent=$sentSoFar")
            return scanResult(Scan.REJECTED, "dup key=$s exhausted")
        }
        if (now - lastClickAt < CLICK_MIN_GAP_MS) return scanResult(Scan.SKIPPED, "click gap")

        // ⚠️ **坐标必须取自扫描命中的那个「开」**（复核 B1）。
        //    旧代码上溯到 clickable 父容器再 performClick，那时对象身份只决定"调谁的 listener"，
        //    改成坐标派发后对象身份**就是**坐标：上溯到全屏容器 = 点在整屏中心，必然点空或误点。
        //    所以坐标一律用 target.view（真机取证：「开」正好 360x360@540,1853，中心 720,2033）。
        val clickAt = target.view

        val dispatched = tap(clickAt)

        // ⚠️ **派发成功 ≠ 拆开**。计 clicked 只表示"事件送进去了"，
        //    真正的成功要等 [scheduleVerify] 看到界面进入红包详情页才计 opened++。
        if (!dispatched) {
            // 几何失败（找不到根 / 尺寸为 0 / 越界 / 页面在后台）是**确定性失败**：
            // 同一个 View 再试一百次也一样 → 直接记满配额，别无限重试。
            resend[s] = maxAttempts
            logOnce(logFound, "tap dispatch failed \"${target.text}\" ${target.cls} " +
                "(loc=${describeLoc(clickAt)})")
            return scanResult(Scan.HIT, "tap failed \"${target.text}\"")
        }

        resend[s] = sentSoFar + 1
        lastClickAt = now
        clicked++
        if (resend.size > 64) synchronized(resend) { resend.clear() }
        logOnce(logFound, "dispatched \"${target.text}\" ${target.cls} " +
            "sent=${sentSoFar + 1} loc=${describeLoc(clickAt)}")

        // 排一次结果验证（不阻塞主线程）
        scheduleVerify(s, sentSoFar + 1, reason)
        return scanResult(Scan.HIT, "dispatched \"${target.text}\" sent=${sentSoFar + 1}")
    }

    /** 日志用：把 View 的屏幕位置/尺寸打出来，真机上能直接和 uiautomator 坐标对照 */
    private fun describeLoc(v: View): String = runCatching {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        "${v.width}x${v.height}@${loc[0]},${loc[1]}"
    }.getOrDefault("?")

    /**
     * 派发触摸后**轮询**红包到底拆开了没，直到 [VERIFY_TOTAL_MS] 超时。
     *
     * ⚠️ 为什么必须轮询（2026-10-19 真机日志实锤）：
     * ```
     * 02:45:56.370  dispatched "开" ImageButton loc=360x360@540,1853
     * 02:45:57.274  resend ... reason=dry hit "开"     ← 900ms 时按钮还在（还在转圈等 CGI）
     * 02:45:57.596  resume rp=LuckyMoneyBeforeDetailUI ← 1226ms 才真的跳页
     * 02:45:57.772  resume rp=LuckyMoneyDetailUI
     * ```
     * 只查一次 900ms → 必然在"还没跳"的那一刻取样 → `opened` 永远 0。
     * 红包**确实拆开了**（用户肉眼确认），是**指标假红**。这是最坏的一种 bug：
     * 功能对、验收得出反结论，会让人去改本来正确的代码。
     *
     * ⚠️ 判据不能是"页面变了"（复核 B2 揪出的假绿）：`topName` 对任何 Activity 的
     * resume 都会更新，所以用户按返回、来电弹窗、误点关闭、点到广告页，都会被算成"拆开了"。
     * 必须**两条同时成立**：离开原页面 **且** 新页面仍是红包类（`isRedPacketClass`）。
     *
     * 同一 Activity 内的成功（按钮消失）单独判：见 `Scan.NO_BUTTON` 分支，
     * 且额外要求 `texts > 0`（页面确实渲染出了东西，不是白屏/还没 layout）。
     */
    private fun scheduleVerify(key: Int, sent: Int, reason: String) {
        val from = topName
        val at = SystemClock.elapsedRealtime()
        scheduleVerifyStep(key, sent, reason, from, at, attempt = 0)
    }

    private fun scheduleVerifyStep(
        key: Int, sent: Int, reason: String, from: String, at: Long, attempt: Int,
    ) {
        // 首查 900ms，之后每 400ms 一次，直到 4s 总预算用完
        val delay = if (attempt == 0) VERIFY_MS else VERIFY_STEP_MS
        mainHandler.postDelayed({
            runCatching {
                val nowTop = topName
                val ms = SystemClock.elapsedRealtime() - at
                val elapsed = ms

                // ── 判据 1：页面变了 ────────────────────────────────────────
                if (nowTop != from) {
                    if (RedPacketLogic.isRedPacketClass(nowTop)) {
                        opened++
                        resend.remove(key)
                        LogX.i("REDPACKET opened (to detail) sent=$sent $from -> $nowTop after ${ms}ms")
                    } else {
                        // 离开了红包体系（返回/来电/误点关闭/跳广告）
                        leftPage++
                        resend.remove(key)
                        LogX.w("REDPACKET left rp page, NOT opened sent=$sent $from -> $nowTop after ${ms}ms")
                    }
                    return@runCatching
                }

                // ── 判据 2：同一 Activity 内按钮消失了 ─────────────────────
                val r = scanOnce(force = true, allowClick = false, reason = "verify")
                when (r.kind) {
                    Scan.NO_BUTTON -> if (r.texts > 0) {
                        opened++
                        resend.remove(key)
                        LogX.i("REDPACKET opened (button gone) sent=$sent top=$nowTop texts=${r.texts} after ${ms}ms")
                        return@runCatching
                    } else {
                        // texts=0：白屏/还没 layout，不能当成功，也不能当失败 → 继续轮询
                        LogX.w("REDPACKET verify blank page (texts=0) sent=$sent top=$nowTop")
                    }
                    // 压根没扫成（节流/没有 Activity）→ 继续轮询，别误判
                    Scan.SKIPPED -> LogX.w("REDPACKET verify skipped: ${r.note} sent=$sent")
                    // 按钮还在 —— 但可能只是"还在等 CGI"，不能立刻判死
                    Scan.HIT, Scan.REJECTED ->
                        LogX.i("REDPACKET verify still there sent=$sent after ${ms}ms (${r.note})")
                }

                // ── 没结论：预算内继续轮询，超时才认输 ──────────────────────
                if (elapsed + VERIFY_STEP_MS <= VERIFY_TOTAL_MS) {
                    scheduleVerifyStep(key, sent, reason, from, at, attempt + 1)
                    return@runCatching
                }

                // ── 超时：确实空响了 → 补发一次 ────────────────────────────
                if (sent < maxAttempts) {
                    LogX.w("REDPACKET resend sent=$sent top=$nowTop (timeout ${ms}ms)")
                    mainHandler.postDelayed({
                        // 补发前再确认一次：开关没被关、还停在原页面（避免在新页面误点）
                        runCatching {
                            if (!on() || topName != from) return@runCatching
                            scanOnce(force = false, allowClick = true, reason = "resend")
                        }
                    }, RESEND_DELAY_MS)
                } else {
                    LogX.w("REDPACKET click no effect sent=$sent top=$nowTop (timeout ${ms}ms)")
                }
            }.onFailure {
                LogX.w("REDPACKET verify err: ${it.javaClass.simpleName}")
            }
        }, delay)
    }

    /**
     * 点「开」。
     *
     * ⚠️ **必须用真实触摸事件**（2026-10-19 真机定案）。
     *
     * 原来先试 `v.performClick()`，它返回 true（说明挂了 OnClickListener 并被调用了），
     * 但红包**没有打开** —— 界面上 `found=6 clicked=1` 而 focus 一直停在
     * `LuckyMoneyNotHookReceiveUI`。对照实验：同一界面 `adb shell input tap 720 2033`
     * **立刻**跳到 `LuckyMoneyDetailUI`。
     * 原因：微信这个「开」是 PAG 动画按钮，真正的开启逻辑挂在**触摸/手势**链上
     * （不是单纯 OnClickListener），performClick 只触发了无障碍回调。
     *
     * 所以现在：把 View 的屏幕坐标算出来，向**根 View** 派发一对 DOWN/UP 触摸，
     * 走完整的 `dispatchTouchEvent` 链路 —— 跟手指点一样。
     *
     * 三条实现细节（都是 2026-10-19 复核补的）：
     *  - UP 必须**真的延迟** 60ms（`postDelayed`）。同一轮里连着 `dispatch` 两次，
     *    两次事件的真实间隔是 0 —— PAG 依赖"按下后至少渲染一帧"，会当成长按/空响。
     *  - DOWN 抛异常时必须补一个 `ACTION_CANCEL`，否则 ViewGroup 的
     *    `mFirstTouchTarget`/`mPressed` 留在按下态，**下一次真实点击会被吃掉**。
     *  - 检查 `dispatchTouchEvent` 的返回值：false = 没人消费（坐标落在不可点区域），
     *    这时不必发 UP，也不能记成"派发成功"去误导排查。
     */
    private fun tap(v: View): Boolean {
        // 0) 窗口/页面在后台就不派发：否则会在用户看不到的时候把红包拆了
        runCatching {
            val act = topRef?.get()
            if (act != null && (!act.hasWindowFocus() || act.isFinishing)) return false
        }

        // 1) 算 View 在屏幕上的中心点
        val loc = IntArray(2)
        runCatching { v.getLocationOnScreen(loc) }.getOrElse { return false }
        val w = v.width
        val h = v.height
        if (w <= 0 || h <= 0) return false
        val sx = loc[0] + w / 2f
        val sy = loc[1] + h / 2f

        // 2) 找到根 View：事件必须派给窗口中真正的根，不能派给按钮自己
        //    （派给按钮会绕过它的父级手势拦截，PAG 那种"按下去才播动画"的会失效）
        var root: View = v
        var guard = 0
        while (guard++ < 32) {
            val p = root.parent
            if (p is View) root = p else break
        }
        if (root === v) return false

        // 3) 根 View 的坐标系原点不一定在屏幕 (0,0)（Dialog/Popup/分屏会偏移），要换算
        val rloc = IntArray(2)
        runCatching { root.getLocationOnScreen(rloc) }.getOrElse { return false }
        val lx = sx - rloc[0]
        val ly = sy - rloc[1]
        if (lx < 0 || ly < 0 || lx >= root.width || ly >= root.height) return false

        // 4) DOWN 立即派发；UP 延迟 60ms 真异步派发
        val t = SystemClock.uptimeMillis()
        val down = runCatching {
            MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, lx, ly, 0).also {
                it.setSource(InputDevice.SOURCE_TOUCHSCREEN)
            }
        }.getOrNull() ?: return false

        val consumed = runCatching { root.dispatchTouchEvent(down) }.getOrDefault(false)
        if (!consumed) {
            // 没人消费 → 坐标不在任何可点区域，发 UP 也没意义
            runCatching { down.recycle() }
            LogX.w("REDPACKET tap: nobody consumed DOWN at $lx,$ly (root=${root.javaClass.simpleName})")
            return false
        }

        mainHandler.postDelayed({
            runCatching {
                val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, lx, ly, 0)
                up.setSource(InputDevice.SOURCE_TOUCHSCREEN)
                try {
                    root.dispatchTouchEvent(up)
                } finally {
                    up.recycle()
                }
            }.onFailure {
                // UP 派发失败 → 补一个 CANCEL，别让控件卡在按下态（会吃掉下一次点击）
                runCatching {
                    val c = MotionEvent.obtain(t, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_CANCEL, lx, ly, 0)
                    try {
                        root.dispatchTouchEvent(c)
                    } finally {
                        c.recycle()
                    }
                }
            }
            runCatching { down.recycle() }
        }, 60)
        return true
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
            "fake" -> onMain { "REDPACKET fake " + scanOnce(true, true, "probe").note + "\n" + tree(60) }
            "fake dry", "dry" -> onMain { "REDPACKET dry " + scanOnce(true, false, "probe").note }
            "reset" -> {
                synchronized(resend) { resend.clear() }
                clicked = 0; opened = 0; leftPage = 0; resendBlocked = 0
                found = 0; scans = 0; gateRejects = 0; ambiguous = 0
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
        sb.append("found=$found gateReject=$gateRejects ambiguous=$ambiguous ")
        // ⚠️ dispatched 是"触摸派发成功"，opened 才是"真的拆开了"。验收只看 opened。
        sb.append("dispatched=$clicked opened=$opened leftPage=$leftPage resendBlocked=$resendBlocked ")
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
