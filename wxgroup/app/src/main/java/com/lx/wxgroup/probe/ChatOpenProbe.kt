package com.lx.wxgroup.probe

import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.util.LogX
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 会话页探针：谁被打开了。
 *
 * 为什么需要它：聊天页的标题在微信里是**自绘 View**，uiautomator dump 里根本看不到
 * （实测打开 `wx__c_...` 的单聊，dump 里只有返回按钮的未读数和一个提示条）。
 * 而「按组过滤会话」最危险的失败方式就是**点 A 开 B**（过滤改了位置空间，
 * 微信自己按位置取会话，少做一步位置重映射就会点错），必须能严格验证。
 *
 * 两个实测结论（8.0.48）：
 *  1. 聊天页是 **Fragment**（`com.tencent.mm.ui.chatting.ChattingUIFragment`，挂在 LauncherUI 上），
 *     不是 `ChattingUI` 这个 Activity —— 挂 Activity 的 onCreate 一次都不会触发。
 *  2. 会话 Intent（带 `Chat_User`）挂在 Fragment 内部的对象上（实测在 3~4 层深），
 *     所以只能按对象图找。
 */
object ChatOpenProbe {

    private const val FRAG_CHAT = "com.tencent.mm.ui.chatting.ChattingUIFragment"
    private const val EXTRA_CHAT_USER = "Chat_User"

    private val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 挂基类 performResume 再按类名过滤：具体类不一定自己声明了 onResume
        runCatching {
            XposedHelpers.findAndHookMethod(
                "androidx.fragment.app.Fragment", lpparam.classLoader, "performResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val frag = param.thisObject ?: return
                        if (frag.javaClass.name != FRAG_CHAT) return
                        runCatching { LogX.i("CHATOPEN user=${chatUserOf(frag)}") }
                        // 第一次进来把候选路径全打出来（只看一次，别刷屏）
                        runCatching { dumpCandidates(frag) }
                    }
                }
            )
            LogX.i("ChatOpenProbe: hooked Fragment.performResume (filter=$FRAG_CHAT)")
        }.onFailure { LogX.w("ChatOpenProbe: hook failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 诊断：会话页的对象图里哪些地方藏着 wxid / 会话相关字符串。
     *
     * 微信把聊天页做成 Fragment，会话 Intent 挂在内部对象上而不是 Activity 上，
     * 所以只能把候选路径打出来一次，看清楚到底该读哪里。
     */
    private fun dumpCandidates(root: Any) {
        if (!seen.add(root.javaClass)) return
        val sb = StringBuilder("CHATOPEN cand:")
        var budget = 900

        fun walk(o: Any?, path: String, depth: Int) {
            if (o == null || depth > 6 || budget-- <= 0 || sb.length > 900) return
            if (o is String) {
                if (looksLikeWxid(o) || o.length in 4..40 && path.contains("user", true)) {
                    sb.append(" $path='${o.take(24)}'")
                }
                return
            }
            if (o is Intent) {
                val u = runCatching { o.getStringExtra(EXTRA_CHAT_USER) }.getOrNull()
                sb.append(" $path(Intent)=${u}")
            }
            if (o is Number || o is Boolean) return
            if (o.javaClass.name.startsWith("java.")) return

            var c: Class<*>? = o.javaClass
            var guard = 0
            while (c != null && c != Any::class.java && guard++ < 2) {
                for (f in runCatching { c!!.declaredFields }.getOrDefault(emptyArray())) {
                    if (f.type.isPrimitive) continue
                    val v = runCatching {
                        f.isAccessible = true
                        f.get(o)
                    }.getOrNull() ?: continue
                    walk(v, "$path.${f.name}", depth + 1)
                }
                c = c.superclass
            }
        }
        walk(root, "F", 0)
        LogX.i(sb.toString())
    }

    private fun looksLikeWxid(s: String): Boolean =
        s.length in 4..40 &&
            (s.endsWith("@chatroom") || s.startsWith("wxid_") || s.startsWith("gh_") ||
                (s.contains('_') && s.none { it.isWhitespace() || it.code > 127 }))

    /** 在 Fragment 的对象图里找那个 Intent 的 `Chat_User` */
    private fun chatUserOf(root: Any): String {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var budget = 500

        fun walk(o: Any?, depth: Int): String? {
            if (o == null || depth > 5 || budget-- <= 0) return null
            if (!visited.add(o)) return null
            if (o is Intent) {
                val u = runCatching { o.getStringExtra(EXTRA_CHAT_USER) }.getOrNull()
                if (!u.isNullOrBlank()) return u
            }
            if (o is String || o is Number || o is Boolean) return null
            // 只走微信自己的对象，别爬进框架/集合里
            if (o.javaClass.name.startsWith("java.")) return null

            var c: Class<*>? = o.javaClass
            var guard = 0
            while (c != null && c != Any::class.java && guard++ < 3) {
                for (f in runCatching { c!!.declaredFields }.getOrDefault(emptyArray())) {
                    if (f.type.isPrimitive) continue
                    val v = runCatching {
                        f.isAccessible = true
                        f.get(o)
                    }.getOrNull() ?: continue
                    walk(v, depth + 1)?.let { return it }
                }
                c = c.superclass
            }
            return null
        }
        return walk(root, 0) ?: "?"
    }
}
