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
 * 待取证：8.0.48/2588 GP 的拍一拍触发类、方法和参数个数尚未定位。下面三个常量
 * 故意留空/-1，不能用猜测的混淆名误拦普通头像点击；填入真机锚点后 install 会反射校验。
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

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            if (PAT_TRIGGER_CLASS_8048.isBlank() || PAT_TRIGGER_METHOD_8048.isBlank()) {
                LogX.i("QUIET PAT skipped: 8.0.48 trigger anchor pending evidence")
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
                    if (!Kv.getBool(Keys.CHAT_DISABLE_PAT)) return
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

    fun probe(sub: String): String = runCatching {
        when (sub.lowercase()) {
            "status" -> "installed=$installed hooks=$hookedMethods blocked=${blocked.get()} enabled=${Kv.getBool(Keys.CHAT_DISABLE_PAT)}"
            "anchor" -> "class=${PAT_TRIGGER_CLASS_8048.ifBlank { "待取证" }} method=${PAT_TRIGGER_METHOD_8048.ifBlank { "待取证" }} argc=$PAT_TRIGGER_ARG_COUNT_8048"
            else -> "subs=status,anchor"
        }
    }.getOrElse { "error=${it.javaClass.simpleName}" }
}
