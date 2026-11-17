package com.lx.wxgroup.feature

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 只读 trace 钩子 —— 回答「谁在 ChattingUI 返回时调 `startActivity(LauncherUI, CLEAR_TOP)`」。
 *
 * 背景（真机已实测）：分组页 `ConvBoxServiceConversationUI → ChattingUI` 里按返回，
 * 直接落回 `LauncherUI`（主列表），中间的 ConvBox 被一起清掉；原生 `ChatroomContactUI → ChattingUI`
 * 返回时上一页保留。logcat 决定性差异是：我方路径 back 的同一时刻出现了
 * `START u0 {flg=0x4000000 cmp=com.tencent.mm/.ui.LauncherUI}`（`FLAG_ACTIVITY_CLEAR_TOP`），
 * 而这条跳转**不走 Java 层 `Activity.finish()`**，所以 finish 钩子抓不到。
 *
 * 本钩子观察「谁调了 Activity 的 startActivity/startActivityForResult 去打开 LauncherUI 或 ChattingUI，
 * 以及它带没带 CLEAR_TOP」，用于取证返回时那次 `startActivity(LauncherUI, CLEAR_TOP)` 的调用方、
 * 以及两条路径启动 ChattingUI 时 extras 的差异（核心对比项是 `KEY_HOME_PAGE_CLS`）。
 * 挂点选 `android.app.Activity`（boot 框架类，无需宿主 classLoader）：
 *
 *  - `hookAllMethods("startActivity")` 会把 `startActivity(Intent)` / `startActivity(Intent, Bundle)`
 *    以及（若存在）其它重载全部挂上；
 *  - `hookAllMethods("startActivityForResult")` 同理挂上
 *    `(Intent, int)` / `(Intent, int, Bundle)` 重载 —— 微信可能走这条而非 `startActivity`。
 *    注意 Android 内部 `startActivity(Intent)` 会转发到 `startActivityForResult(Intent, -1, …)`，
 *    所以一次逻辑跳转可能命中多个重载，多行日志正好把「包装链」也暴露出来。
 *
 * ⛔ 全程只读：绝不 `param.setResult(...)`、不改 intent 的 flags、不改任何参数。
 * ⛔ 性能：先判 `thisObject` 是 `com.tencent.mm` 的 Activity，再判 intent 命中 LauncherUI/ChattingUI，
 *    只有命中才取调用栈；其余 intent 在第二道判据就早退，不取栈、不刷日志。
 */
object StartActivityTrace {

    private const val TAG = "GCONV_SA"
    private const val HOST = "com.tencent.mm"

    /** 命中判据：component 类名 / intent.toURI() 里含任一即可（返回那次是 LauncherUI，启动时是 ChattingUI）。 */
    private val MARKS = listOf("LauncherUI", "ChattingUI")

    /** 核心对比项：返回时被写成 `ChattingUI`，正常应为 `LauncherUI`。 */
    private const val KEY_HOME_PAGE_CLS = "KEY_HOME_PAGE_CLS"

    /** 节流表：同一调用栈字符串 300ms 内只打一行。 */
    private val lastTrace = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Volatile
    private var installed = false

    fun install(): List<String> {
        if (installed) return listOf("$TAG already installed")
        installed = true
        val out = mutableListOf<String>()

        val cb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) = logHit(param)
        }

        // `hookAllMethods` 会把该名字下的所有重载都挂上（startActivity: Intent / Intent,Bundle；
        // startActivityForResult: Intent,int / Intent,int,Bundle 等），n 即重载数量。
        runCatching {
            val set = XposedBridge.hookAllMethods(Activity::class.java, "startActivity", cb)
            out.add("$TAG hook android.app.Activity.startActivity n=${set?.size ?: 0}")
        }.onFailure {
            out.add("$TAG hook startActivity fail: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
        }

        runCatching {
            val set = XposedBridge.hookAllMethods(Activity::class.java, "startActivityForResult", cb)
            out.add("$TAG hook android.app.Activity.startActivityForResult n=${set?.size ?: 0}")
        }.onFailure {
            out.add("$TAG hook startActivityForResult fail: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
        }

        LogX.i("$TAG installed")
        return out
    }

    private fun logHit(param: XC_MethodHook.MethodHookParam) {
        runCatching {
            val self = param.thisObject ?: return@runCatching
            if (!self.javaClass.name.startsWith(HOST)) return@runCatching

            val intent = param.args.getOrNull(0) as? Intent ?: return@runCatching
            val component = runCatching { intent.component?.className }.getOrNull()
            val uri = runCatching { intent.toURI() }.getOrNull() ?: ""
            // 只有命中 LauncherUI / ChattingUI 才继续：其余 intent 在这一道就早退，不取栈、不刷日志
            val hit = component?.let { c -> MARKS.any { c.contains(it) } } == true ||
                MARKS.any { uri.contains(it) }
            if (!hit) return@runCatching

            val trace = callerTrace()
            val now = System.currentTimeMillis()
            val last = lastTrace.put(trace, now) ?: 0L
            if (now - last < 300L) return@runCatching

            val selfName = self.javaClass.name.substringAfterLast('.')
            val flags = runCatching { intent.flags }.getOrDefault(-1)
            val clearTop = (flags and Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0
            val home = homeClsOf(intent)
            val extras = runCatching { describeExtras(intent) }.getOrDefault(" extras={}")

            LogX.i(
                "$TAG hit m=${param.method.name} self=$selfName comp=$component " +
                    "flags=0x${flags.toString(16)} clearTop=$clearTop home=$home$extras $trace " +
                    "tid=${Thread.currentThread().name}"
            )
        }.onFailure {
            LogX.w("$TAG log fail: ${it.javaClass.simpleName}")
        }
    }

    /**
     * 单独取出 `KEY_HOME_PAGE_CLS` 的值（核心对比项）：类型按 Object 取再 toString 兜底。
     * 取不到 / 无 extras 时返回 `<null>`，不抛。
     */
    private fun homeClsOf(intent: Intent): String =
        runCatching {
            intent.extras?.get(KEY_HOME_PAGE_CLS)?.toString()
        }.getOrNull() ?: "<null>"

    /**
     * 遍历 extras 的所有 key，逐个 `Bundle.get(key)` 取值。值类型不确定
     * （String/Int/Long/Serializable/… 都可能），统一 `toString()` 兜底；单个 key 取值
     * 也包 runCatching，任何一个坏了都不影响其它 key 与整行日志。
     */
    private fun describeExtras(intent: Intent): String {
        val extras: Bundle = intent.extras ?: return " extras={}"
        val sb = StringBuilder(" extras={")
        runCatching {
            val keys = extras.keySet()
            var first = true
            for (k in keys) {
                if (!first) sb.append(", ")
                first = false
                val v = runCatching { extras.get(k) }.getOrNull()
                sb.append(k).append('=').append(v?.toString() ?: "<null>")
            }
        }
        sb.append('}')
        return sb.toString()
    }

    /**
     * 从当前线程栈取「调用方」帧：`dropWhile` 跳过我们自己的类、Xposed 桥、反射与 VM 帧，
     * 剩下第一帧就是被 hook 的 startActivity/startActivityForResult，往后就是它的调用方。
     */
    private fun callerTrace(): String =
        Thread.currentThread().stackTrace
            .dropWhile {
                it.className.startsWith("com.lx.wxgroup") ||
                    it.className.startsWith("de.robv.android.xposed") ||
                    it.className.startsWith("java.lang.reflect") ||
                    it.className.startsWith("java.lang.Thread") ||
                    it.className.startsWith("dalvik.system") ||
                    it.className.startsWith("java.lang.StackTraceElement")
            }
            .take(12)
            .joinToString("<-") { it.className.substringAfterLast('.') + "." + it.methodName }
}
