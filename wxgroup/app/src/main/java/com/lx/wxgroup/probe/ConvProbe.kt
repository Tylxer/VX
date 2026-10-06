package com.lx.wxgroup.probe

import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.HeaderViewListAdapter
import android.widget.ListPopupWindow
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.feature.GroupBar
import com.lx.wxgroup.util.LogX
import java.util.Collections
import java.util.WeakHashMap

/**
 * M4-A 定位探针（只打日志，不改行为）：为「聊天列表长按会话 → 加入分组」找两个锚点。
 *
 *  锚点 A：会话行 → wxid。HeaderViewListAdapter.getView 记住「行 View → 位置」，
 *          再把会话对象（com.tencent.mm.storage.r4）和行 tag（…conversation.n3）
 *          的字段/无参方法全量 dump，从中找长得像 wxid 的那个。
 *  锚点 B：长按弹出的会话菜单容器。候选 ListPopupWindow / PopupWindow / Dialog，
 *          命中后打印容器类 + 内容文字，决定往哪儿塞「加入分组」。
 *
 * 全部 runCatching 包裹，探针失败绝不影响微信。
 */
object ConvProbe {

    /** 行 View -> adapter 位置 */
    private val rowPos = Collections.synchronizedMap(WeakHashMap<View, Int>())

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val dumped = Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var adapterLogged = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookGetView(lpparam)
        hookLongPress(lpparam)
        hookMenuContainers(lpparam)
        LogX.i("ConvProbe: installed")
    }

    // ------------------------------------------------------------ 锚点 A

    private fun hookGetView(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.widget.HeaderViewListAdapter", lpparam.classLoader,
                "getView", Int::class.javaPrimitiveType, View::class.java, ViewGroup::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val list = GroupBar.conversationList() ?: return
                            if (param.thisObject !== list.adapter) return
                            val pos = param.args[0] as Int
                            val v = param.result as? View ?: return
                            rowPos[v] = pos
                            if (!adapterLogged) {
                                adapterLogged = true
                                LogX.i(
                                    "CONV adapter=${param.thisObject.javaClass.name} " +
                                        "count=${list.adapter.count}"
                                )
                            }
                            dumpOnce("item", adapterItem(param.thisObject, pos))
                        }
                    }
                }
            )
        }.onFailure { LogX.w("ConvProbe: hook getView failed: ${it.javaClass.simpleName}") }
    }

    private fun adapterItem(adapter: Any, pos: Int): Any? = runCatching {
        adapter.javaClass.getMethod("getItem", Int::class.javaPrimitiveType).invoke(adapter, pos)
    }.getOrNull()

    /** 全量 dump：声明字段（任意类型）+ 无参方法（任意返回类型） */
    private fun dumpOnce(label: String, obj: Any?) {
        if (obj == null) return
        val cls = obj.javaClass.name
        if (!dumped.add("$label:$cls")) return
        val sb = StringBuilder("CONV $label class=$cls\n")
        for (f in obj.javaClass.allFields()) {
            val v = runCatching { f.isAccessible = true; f.get(obj) }.getOrNull() ?: continue
            sb.append("    f ${f.name}:${f.type.simpleName}=${short(v)}\n")
        }
        for (m in runCatching { obj.javaClass.methods }.getOrDefault(emptyArray())) {
            if (m.parameterTypes.isNotEmpty()) continue
            val n = m.name
            if (n == "getClass" || n == "hashCode" || n == "toString") continue
            val v = runCatching { m.isAccessible = true; m.invoke(obj) }.getOrNull() ?: continue
            val s = short(v)
            if (s.isEmpty()) continue
            sb.append("    m $n():${m.returnType.simpleName}=$s\n")
        }
        LogX.i(sb.toString().trimEnd())
    }

    private fun Class<*>.allFields(): List<java.lang.reflect.Field> {
        val out = LinkedHashMap<String, java.lang.reflect.Field>()
        var c: Class<*>? = this
        var guard = 0
        while (c != null && guard++ < 4) {
            for (f in runCatching { c!!.declaredFields }.getOrDefault(emptyArray())) {
                out.putIfAbsent(f.name, f)
            }
            c = c.superclass
        }
        return out.values.toList()
    }

    private fun short(v: Any): String = runCatching {
        val s = when (v) {
            is String -> v
            is Number -> v.toString()
            is Boolean -> v.toString()
            else -> "${v.javaClass.simpleName}@${Integer.toHexString(v.hashCode())}"
        }
        s.take(90)
    }.getOrDefault("?")

    // ------------------------------------------------------------ 长按链路

    private fun hookLongPress(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val list = GroupBar.conversationList() ?: return
                    val v = param.thisObject as? View ?: return
                    if (v.rootView !== list.rootView) return
                    val row = cr0Ancestor(v) ?: return
                    val pos = rowPos[row] ?: return
                    LogX.i("CONVLP row=${row.javaClass.name} pos=$pos view=${v.javaClass.name}")
                    dumpOnce("tag", row.tag)
                    dumpOnce("item@$pos", adapterItem(list.adapter ?: return, pos))
                    // 菜单是异步弹出的（长按 → 震动 → 弹窗），延迟一点再枚举窗口
                    mainHandler.postDelayed({ dumpWindows("afterLP") }, 500)
                }
            }
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.view.View", lpparam.classLoader, "performLongClick", cb
            )
        }.onFailure { LogX.w("ConvProbe: hook performLongClick failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 枚举当前所有窗口（WindowManagerGlobal.mViews）。
     *
     * 会话长按菜单既不是 ListPopupWindow / PopupWindow / Dialog，也不是经
     * WindowManager 加进来的（前面几层 hook 全没命中），所以直接从窗口表反查：
     * 菜单一定是「文字很少的那一个非主窗口」。
     */
    private fun dumpWindows(label: String) {
        runCatching {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            val inst = cls.getMethod("getInstance").invoke(null)
            val f = cls.getDeclaredField("mViews")
            f.isAccessible = true
            val views = f.get(inst) as? List<*> ?: return@runCatching
            LogX.i("WIN $label count=${views.size}")
            views.forEachIndexed { i, v ->
                val view = v as? View ?: return@forEachIndexed
                val texts = ArrayList<String>()
                collectTexts(view) { if (texts.size < 12) texts.add(it) }
                LogX.i(
                    "WIN  [$i] ${view.javaClass.name} " +
                        "w=${view.width} h=${view.height} texts=$texts"
                )
            }
        }.onFailure { LogX.w("ConvProbe: dumpWindows failed: ${it.javaClass.simpleName}") }
    }

    private fun cr0Ancestor(v: View): View? {
        var cur: View? = v
        var guard = 0
        while (cur != null && guard++ < 8) {
            if (isCr0(cur)) return cur
            cur = cur.parent as? View
        }
        return null
    }

    private fun isCr0(v: View): Boolean = runCatching {
        val id = v.id
        if (id == 0) return false
        v.resources.getResourceName(id).endsWith(":id/cr0")
    }.getOrDefault(false)

    // ------------------------------------------------------------ 锚点 B

    private fun hookMenuContainers(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching { report(param) }
            }
        }
        val intT = Int::class.javaPrimitiveType
        hookOne(lpparam, "android.widget.ListPopupWindow", "show", cb)
        hookOne(lpparam, "android.widget.PopupWindow", "showAsDropDown", cb, View::class.java)
        hookOne(lpparam, "android.widget.PopupWindow", "showAsDropDown", cb,
            View::class.java, intT, intT)
        hookOne(lpparam, "android.widget.PopupWindow", "showAsDropDown", cb,
            View::class.java, intT, intT, intT)
        hookOne(lpparam, "android.widget.PopupWindow", "showAtLocation", cb,
            View::class.java, intT, intT, intT)
        hookOne(lpparam, "android.app.Dialog", "show", cb)

        // 兜底：不管是什么弹窗，最终都要经 WindowManager 把 view 加到窗口上。
        // Android 12+ 的 WindowManagerGlobal.addView 多一个 userId 参数（5 参版），
        // 两种签名都挂上，免得漏。
        hookOne(lpparam, "android.view.WindowManagerImpl", "addView", cb,
            View::class.java, ViewGroup.LayoutParams::class.java)
        hookOne(lpparam, "android.view.WindowManagerGlobal", "addView", cb,
            View::class.java, ViewGroup.LayoutParams::class.java,
            android.view.Display::class.java, android.view.Window::class.java)
        hookOne(lpparam, "android.view.WindowManagerGlobal", "addView", cb,
            View::class.java, ViewGroup.LayoutParams::class.java,
            android.view.Display::class.java, android.view.Window::class.java, intT)
    }

    private fun hookOne(
        lpparam: XC_LoadPackage.LoadPackageParam,
        cls: String,
        method: String,
        cb: XC_MethodHook,
        vararg params: Class<*>?,
    ) {
        runCatching {
            // 注意：findAndHookMethod 的约定是「参数类型..., 回调」——回调必须放最后。
            // 之前写成 (cls, loader, method, cb, *params) 时只有 0 参数的方法能挂上，
            // 带参数的一律 "not available"，兜底那两层因此白挂。
            XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, method, *params, cb)
            LogX.d("ConvProbe: hooked $cls.$method(${params.size})")
        }.onFailure {
            LogX.d("ConvProbe: $cls.$method(${params.size}) not available")
        }
    }

    private fun report(param: XC_MethodHook.MethodHookParam) {
        val host = param.thisObject
        val content: View = when (host) {
            is ListPopupWindow -> runCatching { host.listView }.getOrNull()
            is PopupWindow -> runCatching { host.contentView }.getOrNull()
            is Dialog -> runCatching { host.window?.decorView }.getOrNull()
            // WindowManager(Impl/Global).addView(view, ...) 的宿主是 WindowManager 自己，
            // 弹窗本体就在第一个参数里 —— 不取它的话这层兜底等于没挂
            else -> param.args.getOrNull(0) as? View
        } ?: return
        val texts = ArrayList<String>()
        collectTexts(content) { if (texts.size < 40) texts.add(it) }
        if (texts.isEmpty()) return
        // 走 WindowManager 兜底时主界面窗口也会命中（文字一大堆），只留「小菜单」形态的
        if (texts.size > 14) return
        val extra = if (content is ListView) {
            " adapter=" + runCatching { content.adapter?.javaClass?.name }.getOrDefault("?")
        } else ""
        LogX.i("MENU host=${host.javaClass.name} content=${content.javaClass.name}$extra texts=$texts")
    }

    private fun collectTexts(root: View, depth: Int = 0, out: (String) -> Unit) {
        if (depth > 8) return
        if (root is TextView) {
            val t = root.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) out(t)
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i)
                if (c != null) collectTexts(c, depth + 1, out)
            }
        }
    }
}
