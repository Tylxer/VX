package com.lx.wxgroup.feature

import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.Collections
import java.util.HashSet

/**
 * 禁用双击头像触发拍一拍。
 *
 * ## 方向（需求定死）
 * docs/需求功能清单.md:152「禁用双击头像触发拍一拍」——主方向是**阻止自己发出**：
 * 聊天页双击对方头像不触发拍一拍。备选方向是阻断拍一拍消息的收发
 * （`shouldBlockIncoming`，判据在 [PatLogic]），二选一由真机取证结果定。
 *
 * ## 取证状态（2026-10，lane/pat）
 * `docs/取证结论.md` **没有拍一拍相关锚点**：8.0.48/2588 GP 的拍一拍触发类、
 * 方法和参数个数尚未定位。下面三个常量故意留空/-1，**不能用猜测的混淆名
 * 误拦普通头像点击**；填入真机锚点后 install 会反射校验。
 *
 * 取证入口（零猜测锚点，随时可跑）：
 * ```
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
 *   --es op quiet --es sub pat --es sub2 dbltap    # 开启：hook GestureDetector#onTouchEvent 打栈
 * # 去聊天页双击一次对方头像，然后：
 * python tools/dev.py q 'PATDBG'     # 看 dbltap-listener= 与栈里的微信真实类名/方法名
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
 *   --es op quiet --es sub pat --es sub2 dbltapoff # 关闭（卸载 hook）
 *
 * ⚠️ action 是 PROBE（不是 QUIET，模块没有这个 action，照旧注释敲会静默无输出）；
 * 入口统一走 `op=quiet`：`--es sub pat` 是**路由**（选中本功能），
 * `--es sub2` 才是本功能的子命令（dbltap/dbltapoff/anchor/status）。
 * 两个参数不能混 —— 早先只用一个 sub 时，sub2 这些子命令根本传不进来。
 * ```
 * 拿到真实类名/方法名后回填下面三个常量即可生效。
 */
object PatBlock {

    private const val PAT_TRIGGER_CLASS_8048 = "" // 待取证
    private const val PAT_TRIGGER_METHOD_8048 = "" // 待取证
    private const val PAT_TRIGGER_ARG_COUNT_8048 = -1 // 待取证

    @Volatile
    private var installed = false

    @Volatile
    private var hookedMethods = 0

    private val blocked = java.util.concurrent.atomic.AtomicLong(0)

    /** install 时保存的宿主 classLoader，供 [probe] 的取证探针反射用 */
    @Volatile
    private var hostLoader: ClassLoader? = null

    /** dbltap 栈 dump 已打条数（限流见 [PatLogic.stackDumpDue]） */
    private val stackDumps = java.util.concurrent.atomic.AtomicInteger(0)

    /** dbltap 探针正在打点（= `sub2=dbltap` 的真实开关状态，status 展示的就是它） */
    @Volatile
    private var dbltapLogging = false

    /** 成功挂载时的卸载句柄；null = 当前没挂着 */
    @Volatile
    private var dbltapHook: XC_MethodHook.Unhook? = null

    /** hook 时缓存好的 `GestureDetector.mDoubleTapListener` 字段（逐帧反射太烫） */
    @Volatile
    private var dbltapListenerField: java.lang.reflect.Field? = null

    /** 已 dump 过的 doubleTap listener 类名，用来去重（同一个类只打一次栈） */
    private val dumpedListeners = Collections.synchronizedSet(HashSet<String>())

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            hostLoader = lpparam.classLoader
            if (PAT_TRIGGER_CLASS_8048.isBlank() || PAT_TRIGGER_METHOD_8048.isBlank()) {
                LogX.i("QUIET PAT skipped: 8.0.48 trigger anchor pending evidence (probe: op=quiet sub=pat sub2=dbltap)")
                return
            }
            val triggerClass = runCatching {
                Class.forName(PAT_TRIGGER_CLASS_8048, false, lpparam.classLoader)
            }.getOrNull() ?: run {
                LogX.i("QUIET PAT skipped: trigger class not found")
                return
            }
            val methods = triggerClass.declaredMethods.filter { method ->
                method.name == PAT_TRIGGER_METHOD_8048 &&
                    (PAT_TRIGGER_ARG_COUNT_8048 < 0 ||
                        method.parameterTypes.size == PAT_TRIGGER_ARG_COUNT_8048)
            }
            if (methods.isEmpty()) {
                LogX.i("QUIET PAT skipped: trigger method not found")
                return
            }
            methods.forEach { method -> hook(method) }
            hookedMethods = methods.size
            installed = true
            LogX.i("QUIET PAT installed hooks=${methods.size}")
        }.onFailure {
            installed = false
            LogX.i("QUIET PAT install failed: ${it.javaClass.simpleName}")
        }
    }

    private fun hook(method: Method) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    // 判据统一走 PatLogic（JVM 单测覆盖），开关关着不拦
                    if (!PatLogic.shouldBlockTrigger(Kv.getString(Keys.CHAT_DISABLE_PAT))) return
                    blocked.incrementAndGet()
                    param.result = defaultValue(method.returnType)
                }
            }
        })
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    // ------------------------------------------------------------------ 取证探针（零猜测锚点）

    /**
     * `PATDBG` 栈 dump 的帧数上限：够盖住 View → GestureDetector → 微信业务类，
     * 再往下是 framework 的消息泵，没有取证价值。
     */
    private const val PAT_STACK_FRAMES = 30

    /**
     * [android.view.GestureDetector] 里装双击监听器的字段名（AOSP 原生命名，
     * 微信没改 framework）。反射拿到的对象**类型**就是微信处理双击的真实实现类
     * —— 本轮取证的核心产出。
     */
    private const val DBLTAP_LISTENER_FIELD = "mDoubleTapListener"

    /**
     * 开启 / 关闭双击栈 dump 取证。
     *
     * ## 为什么 hook `GestureDetector#onTouchEvent` 而不是 `OnDoubleTapListener#onDoubleTap`
     * `OnDoubleTapListener` 是**接口**，`onDoubleTap` 是没有实现体的抽象方法，
     * 而 Xposed 只能替换一个 method 的真实入口、需要它有实现体，
     * 所以 `hookMethod` 对接口方法必然抛 `IllegalArgumentException`
     * （真机实测 `sub2=dbltap` 返回 `QUIET dbltap error=IllegalArgumentException`）。
     * 改挂**具体类的具体非抽象方法** `GestureDetector#onTouchEvent(MotionEvent)`：
     * 所有触摸事件都从这里进 GestureDetector，双击也不例外。
     *
     * ## 回调里做两件事
     * 1. 反射读 `param.thisObject`（那个 GestureDetector 实例）的 [DBLTAP_LISTENER_FIELD]，
     *    打印它的**实际类名** —— 微信的双击监听器实现类就此现形，最有价值的一条；
     * 2. 打一次调用栈，栈里能看到微信侧双击分发的类与方法。
     *
     * ## 约束
     * - hook 的是 **Android framework 的类**，不是微信的类；抓到的微信类名只是
     *   **取证输出**，不据此回填任何硬编码实现；
     * - **取证工具不是功能**：默认不挂，只有手动广播才开；
     * - 严格限流：同一个 listener 类只打一次，总条数上限 [PatLogic.MAX_STACK_DUMPS]；
     * - 幂等挂载 + 尽量干净卸载，重复 `sub2=dbltap` 不会二次 hook 同一个 method。
     */
    private fun setDbltap(on: Boolean): String = runCatching {
        if (on) arm() else disarm()
    }.getOrElse { "dbltap error=${it.javaClass.simpleName}" }

    private fun arm(): String {
        // 幂等：句柄还在就说明同一个 method 已经挂过了，重复 dbltap 绝不二次 hook
        if (dbltapHook != null) {
            rearm()
            return "dbltap already hooked, re-armed (dumps reset)"
        }
        val cls = Class.forName(
            "android.view.GestureDetector",
            false,
            hostLoader ?: PatBlock::class.java.classLoader
        )
        val m = cls.getDeclaredMethod("onTouchEvent", android.view.MotionEvent::class.java)
        val fld = runCatching {
            cls.getDeclaredField(DBLTAP_LISTENER_FIELD).apply { isAccessible = true }
        }.getOrNull()
        dbltapHook = XposedBridge.hookMethod(m, dbltapCallback)
        dbltapListenerField = fld
        rearm()
        val fieldState = if (fld != null) "ok" else "MISSING(only stack available)"
        LogX.i("PATDBG dbltap probe installed: GestureDetector#onTouchEvent field=$DBLTAP_LISTENER_FIELD=$fieldState")
        return "dbltap hook installed GestureDetector#onTouchEvent field=$fieldState max=${PatLogic.MAX_STACK_DUMPS}, go double-tap an avatar now"
    }

    private fun disarm(): String {
        val dumps = stackDumps.get()
        dbltapLogging = false
        val unhook = dbltapHook ?: return "dbltap already off (dumps=$dumps)"
        val removed = runCatching { unhook.unhook() }.isSuccess
        if (removed) {
            dbltapHook = null
            dbltapListenerField = null
        }
        LogX.i("PATDBG dbltap probe removed=$removed listeners=${dumpedListeners.size} dumps=$dumps")
        return if (removed) "dbltap unhooked cleanly, dumps=$dumps listeners=${dumpedListeners.size}"
        else "dbltap logging off; unhook unsupported so callback muted to no-op, dumps=$dumps"
    }

    /** 打开打点并把计数、去重表归零（重新取证一轮；不会触发二次 hook） */
    private fun rearm() {
        stackDumps.set(0)
        runCatching { dumpedListeners.clear() }
        dbltapLogging = true
    }

    private val dbltapCallback = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!dbltapLogging) return
            // 预算用完就停 —— 必须赶在任何反射之前返回，否则每次触摸事件都白跑一趟
            if (!PatLogic.stackDumpDue(stackDumps.get())) return
            runCatching {
                val detector = param.thisObject ?: return
                val listener = dbltapListenerField?.get(detector) ?: return
                val cls = listener.javaClass.name
                val alreadySeen = !dumpedListeners.add(cls)
                if (!PatLogic.shouldDumpListener(alreadySeen, stackDumps.get())) return
                stackDumps.incrementAndGet()
                LogX.i("PATDBG dbltap-listener=$cls")
                val st = Thread.currentThread().stackTrace
                val limit = kotlin.math.min(st.size, PAT_STACK_FRAMES)
                for (i in 0 until limit) {
                    val f = st[i]
                    if (f.className.startsWith("com.lx.wxgroup") ||
                        f.className.startsWith("de.robv.android.xposed")
                    ) continue
                    LogX.i("PATDBG   #$i ${f.className}#${f.methodName}")
                }
                if (!PatLogic.stackDumpDue(stackDumps.get())) {
                    LogX.i("PATDBG dbltap dumps reached max=${PatLogic.MAX_STACK_DUMPS}, stopped (re-arm: sub2=dbltapoff then sub2=dbltap)")
                }
            }
        }
    }

    fun probe(sub: String): String = runCatching {
        when (sub.lowercase()) {
            "status" -> "installed=$installed hooks=$hookedMethods blocked=${blocked.get()} enabled=${Kv.getBool(Keys.CHAT_DISABLE_PAT)} dbltap=$dbltapLogging dumps=${stackDumps.get()}/${PatLogic.MAX_STACK_DUMPS} listeners=${dumpedListeners.size}"
            "anchor" -> "class=${PAT_TRIGGER_CLASS_8048.ifBlank { "待取证" }} method=${PAT_TRIGGER_METHOD_8048.ifBlank { "待取证" }} argc=$PAT_TRIGGER_ARG_COUNT_8048 (evidence: op=quiet sub=pat sub2=dbltap)"
            "dbltap" -> setDbltap(true)
            "dbltapoff" -> setDbltap(false)
            else -> "subs=status,anchor,dbltap,dbltapoff"
        }
    }.getOrElse { "error=${it.javaClass.simpleName}" }
}
