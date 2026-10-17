package com.lx.wxgroup.feature

import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

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
 * adb shell am broadcast -a com.lx.wxgroup.action.QUIET -p com.tencent.mm \
 *   --es op pat --es sub dbltap      # 开启：hook SDK 公开的 onDoubleTap 打栈
 * # 去聊天页双击一次对方头像，然后：
 * python tools/dev.py q 'PATDBG'     # 看栈里的微信真实类名/方法名
 * adb shell am broadcast ... --es sub dbltapoff   # 关闭
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

    /** dbltap 探针是否挂着 */
    @Volatile
    private var dbltapHooked = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            hostLoader = lpparam.classLoader
            if (PAT_TRIGGER_CLASS_8048.isBlank() || PAT_TRIGGER_METHOD_8048.isBlank()) {
                LogX.i("QUIET PAT skipped: 8.0.48 trigger anchor pending evidence (probe: op=pat sub=dbltap)")
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
     * 开启 / 关闭双击栈 dump 取证。
     *
     * hook 的是 **Android SDK 公开 API** `GestureDetifier$OnDoubleTapListener#onDoubleTap`
     * （不是猜微信私有类名）：拍一拍由双击头像触发，微信无论自绘还是用标准
     * GestureDetector，双击事件都会经过这个公开回调。在 before 里打当前调用栈，
     * 栈帧里就是微信真实的触发类名/方法名。
     *
     * ⚠️ 这是**取证工具**，不是功能：默认不挂，手动广播开启，限流
     * [PatLogic.MAX_STACK_DUMPS] 条，用完记得关（`sub=dbltapoff`）。
     */
    private fun setDbltap(on: Boolean): String = runCatching {
        if (on && !dbltapHooked) {
            val cls = runCatching {
                Class.forName(
                    "android.view.GestureDetector\$OnDoubleTapListener",
                    false,
                    hostLoader ?: PatBlock::class.java.classLoader
                )
            }.getOrNull() ?: return "dbltap hook failed: SDK interface not found"
            val m = runCatching { cls.getMethod("onDoubleTap", android.view.MotionEvent::class.java) }
                .getOrNull() ?: return "dbltap hook failed: onDoubleTap not found"
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (!PatLogic.stackDumpDue(stackDumps.get())) return
                        stackDumps.incrementAndGet()
                        LogX.i("PATDBG onDoubleTap listener=${param.thisObject?.javaClass?.name}")
                        val st = Thread.currentThread().stackTrace
                        for (i in st.indices) {
                            LogX.i("PATDBG   at $i ${st[i].className}#${st[i].methodName}")
                        }
                    }
                }
            })
            dbltapHooked = true
            return "dbltap hook installed, go double-tap an avatar now"
        }
        if (!on && dbltapHooked) {
            // Xposed 不支持卸载 hook：置位关闭只是停止打点（hook 本身随进程存活）
            dbltapHooked = false
            return "dbltap logging off (hook stays in process, no-op now)"
        }
        if (on) "dbltap already hooked (dumps=${stackDumps.get()})"
        else "dbltap already off"
    }.getOrElse { "dbltap error=${it.javaClass.simpleName}" }

    fun probe(sub: String): String = runCatching {
        when (sub.lowercase()) {
            "status" -> "installed=$installed hooks=$hookedMethods blocked=${blocked.get()} enabled=${Kv.getBool(Keys.CHAT_DISABLE_PAT)} dbltap=$dbltapHooked dumps=${stackDumps.get()}"
            "anchor" -> "class=${PAT_TRIGGER_CLASS_8048.ifBlank { "待取证" }} method=${PAT_TRIGGER_METHOD_8048.ifBlank { "待取证" }} argc=$PAT_TRIGGER_ARG_COUNT_8048 (evidence: op=pat sub=dbltap)"
            "dbltap" -> setDbltap(true)
            "dbltapoff" -> setDbltap(false)
            else -> "subs=status,anchor,dbltap,dbltapoff"
        }
    }.getOrElse { "error=${it.javaClass.simpleName}" }
}
