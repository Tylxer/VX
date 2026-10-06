package com.lx.wxgroup.probe

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.core.VersionGate
import com.lx.wxgroup.util.LogX
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * M3 探针：只关心一件事 —— 聊天（会话）列表到底长什么样，以及单聊页面怎么拉起来。
 *
 * 跟 M1 的 UiProbe 分工不同：UiProbe 是「广撒网看所有页面」，
 * 这里只回答两个具体问题，答案直接决定 GroupBar 的注入方式：
 *
 * 1. 会话列表是 ListView 还是 RecyclerView？
 *    - ListView  -> 可以 addHeaderView，最干净
 *    - RecyclerView -> 只能往它的父容器里插，或者给列表加 padding 后覆盖
 *    顺便把父容器的类名和子 View 顺序打出来，判断能不能安全插入。
 *
 * 2. 单聊 Activity 的类名和 Intent extra 是什么？
 *    模块没有自己的 Activity，点成员要跳聊天就必须显式启动宿主的 Activity。
 *    类名从 PackageInfo.activities 里筛，不猜。
 *
 * 一次性探测：命中一次就停，绝不常驻刷日志。
 */
object ChatListProbe {

    private const val MAX_TREE_LINES = 140
    private const val MAX_FRAGMENTS = 8
    private const val DELAY_MS = 2500L

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val seen = Collections.synchronizedSet(mutableSetOf<String>())
    private val fragCount = AtomicInteger(0)
    private val activitiesDone = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lines = 0

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookFragments(lpparam)
        probeChatActivity(lpparam)
    }

    private fun hookFragments(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (cls in listOf("androidx.fragment.app.Fragment", "android.support.v4.app.Fragment")) {
            val ok = runCatching {
                XposedHelpers.findAndHookMethod(
                    cls, lpparam.classLoader, "performResume",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            runCatching {
                                val frag = param.thisObject ?: return
                                val name = frag.javaClass.name
                                if (!seen.add(name)) return
                                if (fragCount.incrementAndGet() > MAX_FRAGMENTS) return
                                handler.postDelayed({
                                    runCatching {
                                        val view = fragView(frag) ?: return@postDelayed
                                        lines = 0
                                        LogX.i("CLP ==== FRAG $name ====")
                                        dumpTree(view, 0)
                                    }
                                }, DELAY_MS)
                            }
                        }
                    }
                )
            }.isSuccess
            if (ok) {
                LogX.i("CLP: hooked $cls.performResume")
                return
            }
        }
        LogX.w("CLP: no Fragment class hooked")
    }

    private fun fragView(frag: Any): View? {
        runCatching {
            val v = frag.javaClass.getMethod("getView").invoke(frag)
            if (v is View) return v
        }
        var cls: Class<*>? = frag.javaClass
        var guard = 0
        while (cls != null && cls != java.lang.Object::class.java && guard++ < 6) {
            runCatching {
                val f = cls!!.getDeclaredField("mView")
                f.isAccessible = true
                val v = f.get(frag)
                if (v is View) return v
            }
            cls = cls.superclass
        }
        return null
    }

    private fun dumpTree(v: View, depth: Int) {
        if (lines >= MAX_TREE_LINES || depth > 12) return
        val cn = v.javaClass.name
        val id = runCatching { v.resources.getResourceName(v.id) }.getOrNull()
            ?: "0x${Integer.toHexString(v.id)}"
        val childs = (v as? ViewGroup)?.childCount ?: 0
        val isList = v is AbsListView || cn.contains("RecyclerView")
        val pad = "  ".repeat(depth)
        LogX.i("CLP$pad$cn id=$id ${v.width}x${v.height} childs=$childs${if (isList) " <<LIST" else ""}")
        lines++

        if (isList) dumpListInfo(v, depth)

        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                runCatching {
                    val child = v.getChildAt(i)
                    if (child != null) dumpTree(child, depth + 1)
                }.onFailure { LogX.i("CLP${pad}  child[$i] <err:${it.javaClass.simpleName}>") }
            }
        }
    }

    /** 列表自身的 adapter/getCount，以及「能不能往它上面插东西」所需的父容器信息 */
    private fun dumpListInfo(v: View, depth: Int) {
        val pad = "  ".repeat(depth)
        val adapter = runCatching { v.javaClass.getMethod("getAdapter").invoke(v) }.getOrNull()
        val aName = adapter?.javaClass?.name ?: "null"
        val aCount = adapter?.let {
            runCatching { it.javaClass.getMethod("getItemCount").invoke(it) }.getOrNull()
                ?: runCatching { it.javaClass.getMethod("getCount").invoke(it) }.getOrNull()
        }
        val selfCount = runCatching {
            v.javaClass.getMethod("getCount").invoke(v)
        }.getOrNull()
        val headerCount = runCatching {
            v.javaClass.getMethod("getHeaderViewsCount").invoke(v)
        }.getOrNull()
        LogX.i("CLP${pad}  adapter=$aName adapterCount=$aCount selfCount=$selfCount headers=$headerCount")

        val parent = v.parent as? View
        if (parent == null) {
            LogX.i("CLP${pad}  parent=null")
            return
        }
        LogX.i("CLP${pad}  parent=${parent.javaClass.name} ${parent.width}x${parent.height}")
        if (parent is ViewGroup) {
            for (i in 0 until parent.childCount) {
                val c = parent.getChildAt(i) ?: continue
                LogX.i("CLP${pad}  parent.child[$i]=${c.javaClass.name} ${c.width}x${c.height}")
            }
        }
    }

    /** 从 manifest 里把聊天相关的 Activity 类名捞出来，避免猜包名 */
    private fun probeChatActivity(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val ctx = com.lx.wxgroup.core.HostContext.app() ?: return
            if (!activitiesDone.compareAndSet(false, true)) return
            val pm = ctx.packageManager
            val flags = PackageManager.GET_ACTIVITIES
            @Suppress("DEPRECATION")
            val pi = pm.getPackageInfo(VersionGate.HOST_PACKAGE, flags)
            val acts = pi.activities ?: emptyArray()
            LogX.i("CLP activities total=${acts.size}")
            val hit = acts.mapNotNull { it.name }
                .filter { it.contains("hat", true) || it.contains("onversation", true) || it.contains("auncher", true) }
            hit.take(25).forEach { LogX.i("CLP ACT $it") }
            LogX.i("CLP chat-like activities=${hit.size}")
        }.onFailure { LogX.w("CLP probeChatActivity failed: ${it.javaClass.simpleName}") }
    }
}
