package com.lx.wxgroup.probe

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.util.LogX
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.collections.LinkedHashMap

/**
 * A15 全量 hook 点探针：把「微信会话长按菜单到底从哪冒出来」的所有可能入口再扫一遍。
 *
 * ## 为什么还要再扫一次
 *
 * 上一轮的结论是「六轮探针没找到挂载点」，但那轮有三个**覆盖盲区**：
 *  - 弹窗类 hook 挂的多半是**接口/抽象类**（`android.view.WindowManager` 是接口，
 *    Xposed hook 接口方法**不会生效**，必须挂实现类 `WindowManagerImpl` /
 *    `WindowManagerGlobal`）；
 *  - 只覆盖了「窗口级」入口，**没挂 `ViewGroup.addView`** —— 如果菜单是作为子 View
 *    直接挂进 DecorView 的（ActionMode 工具栏就是这么干的），窗口级 hook 抓不到；
 *  - 没考虑「菜单 View 一直存在、只是长按后 `setVisibility(VISIBLE)`」，
 *    也没挂 **ActionMode（上下文操作栏）** 这条微信极可能走的路。
 *
 * ## 三条互补的取证手段
 *
 * 1. **全量 hook**：装 40+ 个候选入口，命中即记签名 + 调用栈（同调用点只记一次，计数累加）。
 * 2. **View 树 diff**（不依赖任何 hook）：长按瞬间拍一次全窗口 View 基线快照，
 *    窗口到期自动再拍一次，差集就是「菜单刚多出来的 View」—— 直接拿到容器类名。
 *    ⚠️ 基线在**长按那一刻**重拍（见 [arm]），否则 diff 会混进长按前几十秒的无关新增。
 * 3. **窗口枚举**：确认 `WindowManagerGlobal.mViews` 到底几个（上一轮恒为 1）。
 *
 * ## 用法（装一次 APK 可反复开关，不用重装）
 *
 * ```
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE --es op menuhunt --es sub on  --es sec 30 -p com.tencent.mm
 *   # 真手长按会话，等微信菜单弹出
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE --es op menuhunt --es sub tree -p com.tencent.mm
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE --es op menuhunt --es sub off  -p com.tencent.mm
 * adb pull /sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt
 * ```
 *
 * 长按也会**自动**开一个 8 秒窗口（挂 `AbsListView.performLongPress`），
 * 所以不手工 `on` 也能用：直接长按 → 8 秒内发 `sub=tree`；窗口到期会自动 flush。
 *
 * ⚠️ 探针会拖慢宿主（hook 了 `ViewGroup.addView` / `View.setVisibility` 这类热方法），
 * **只在取证期间装**；`off` 之后全部 unhook 并停掉写日志线程，日常完全不安装。
 */
object MenuHuntProbe {

    private const val AUTO_WINDOW_MS = 8_000L
    private const val MAX_TREE_LINES = 400
    private const val MAX_DEPTH = 16
    private const val FRAMES = 6

    /** 日志文件（和 LogX 同一个），绕过 LogX 的 1 秒去重与 5000 行上限 */
    private const val LOG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"

    private var loader: ClassLoader? = null

    private val unhooks = Collections.synchronizedList(mutableListOf<XC_MethodHook.Unhook>())

    @Volatile
    private var until = 0L

    /** key = 签名 + 调用点（前 3 帧），value = 命中次数 */
    private val counts = ConcurrentHashMap<String, Int>()

    @Volatile
    private var snapBefore: Map<String, Int>? = null

    @Volatile
    private var snapAfter: Map<String, Int>? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 到期自动拍 after 快照并 flush —— 不然菜单消失了才拍，diff 恒为 0 */
    private val flushTask = Runnable {
        runCatching {
            snapAfter = snapshot()
            flushInternal()
        }
    }

    private val NOISE = arrayOf(
        "de.robv.android.xposed",
        "com.lx.wxgroup",
        "java.lang.reflect",
        "dalvik.system",
        "android.os.Handler",
    )

    /** 只保存 classLoader，不打任何钩子 —— 平时零开销 */
    fun prepare(lpparam: XC_LoadPackage.LoadPackageParam) {
        loader = lpparam.classLoader
    }

    // ------------------------------------------------------------------ 命令

    fun command(sub: String, sec: Int) {
        when (sub) {
            "on" -> {
                val ms = sec.coerceIn(5, 120) * 1000L
                // 装钩要在后台线程：40 次 findClass + hook 热方法会触发 ART 去优化，
                // 放在广播主线程有 ANR 风险
                Thread {
                    install()
                    mainHandler.post {
                        open(ms)
                        own("MH on: window=${ms}ms hooks=${unhooks.size}")
                        dumpWindows()
                    }
                }.start()
            }
            "off" -> {
                flushInternal()
                uninstall()
                own("MH off: unhooked, state cleared")
            }
            "flush" -> flushInternal()
            "tree" -> {
                dumpTree()
                // 菜单正可见时顺手更新 after 快照，后续 flush 的 diff 才准
                snapAfter = snapshot()
            }
            "win" -> dumpWindows()
            "snap" -> {
                snapAfter = snapshot()
                own("MH snap taken size=${snapAfter?.size}")
            }
            "clear" -> runCatching { File(LOG).delete() }
            else -> own("MH unknown sub=$sub")
        }
    }

    // ------------------------------------------------------------------ 安装

    private fun install() {
        if (loader == null) {
            own("MH install: no classLoader")
            return
        }
        uninstall()

        // ---- A. 窗口级弹窗 ----
        all("android.app.Dialog", "show")
        all("android.app.Dialog", "setContentView")
        all("android.widget.PopupWindow", "showAsDropDown")
        all("android.widget.PopupWindow", "showAtLocation")
        all("android.widget.PopupWindow", "setContentView")
        all("android.widget.PopupWindow", "update")
        all("android.widget.ListPopupWindow", "show")
        // 上一轮可能挂的是接口 android.view.WindowManager（hook 接口不生效），这轮挂实现类
        all("android.view.WindowManagerGlobal", "addView")
        all("android.view.WindowManagerGlobal", "updateViewLayout")
        all("android.view.WindowManagerImpl", "addView")
        all("android.view.ViewRootImpl", "setView")
        all("android.app.Activity", "addContentView")
        all("android.app.Activity", "showDialog")
        all("com.android.internal.policy.PhoneWindow", "addContentView")
        all("com.android.internal.policy.PhoneWindow", "setContentView")

        // ---- B. View 层级（上一轮的盲区：菜单作为子 View 挂进 DecorView）----
        all("android.view.ViewGroup", "addView")
        all("android.view.ViewGroup", "addViewInLayout")
        all("android.view.ViewGroup", "attachViewToParent")
        all("android.view.View", "setVisibility")
        all("android.animation.ObjectAnimator", "start")

        // ---- C. context menu / 菜单框架 ----
        all("android.widget.AbsListView", "showContextMenuForChild")
        all("android.view.View", "showContextMenu")
        all("android.view.View", "createContextMenu")
        all("android.app.Activity", "openContextMenu")
        all("android.app.Activity", "registerForContextMenu")
        all("android.app.Activity", "onCreateContextMenu")
        all("android.app.Activity", "onContextItemSelected")
        all("android.view.MenuInflater", "inflate")
        all("com.android.internal.view.menu.MenuBuilder", "add")
        all("androidx.appcompat.view.menu.MenuBuilder", "add")
        all("androidx.appcompat.view.menu.MenuPopupHelper", "show")
        all("androidx.appcompat.view.menu.MenuPopupHelper", "tryShow")
        all("androidx.appcompat.view.menu.MenuDialogHelper", "show")
        all("androidx.appcompat.widget.PopupMenu", "show")
        all("android.widget.PopupMenu", "show")
        all("androidx.appcompat.widget.ListPopupWindow", "show")
        all("androidx.appcompat.app.AlertDialog", "show")
        all("com.google.android.material.bottomsheet.BottomSheetDialog", "show")

        // ---- C2. ActionMode（上下文操作栏）：长按列表项的另一条经典路，
        //          它的工具栏正是通过 addView 挂进 DecorView 的 ----
        all("android.view.View", "startActionMode")
        all("android.app.Activity", "startActionMode")
        all("androidx.appcompat.app.AppCompatActivity", "startSupportActionMode")
        all("androidx.appcompat.view.ActionMode", "invalidate")

        // ---- D. 长按源头（命中即自动开窗口 / 续期）----
        all("android.widget.AbsListView", "performLongPress", autoOpen = true)
        all("android.widget.AbsListView", "performItemLongClick", autoOpen = true)
        all("android.view.View", "performLongClick", autoOpen = true)

        // ---- E. 布局 ----
        all("android.view.LayoutInflater", "inflate")

        own("MH install done: ${unhooks.size} hooks")
    }

    private fun uninstall() {
        synchronized(unhooks) {
            for (u in unhooks) runCatching { u.unhook() }
            unhooks.clear()
        }
        mainHandler.removeCallbacks(flushTask)
        counts.clear()
        snapBefore = null
        snapAfter = null
        until = 0L
        stopWriter()
    }

    private fun all(clsName: String, method: String, autoOpen: Boolean = false) {
        val cl = loader ?: return
        val cls = runCatching { XposedHelpers.findClass(clsName, cl) }.getOrNull()
        if (cls == null) {
            own("MH  no-class  $clsName.$method")
            return
        }
        // ⚠️ 是 XposedBridge.hookAllMethods（不是 XposedHelpers 上的同名方法）：
        // api-82 只有 XposedBridge 提供它。它只扫 cls 自己声明的方法，不递归子类。
        val set = runCatching {
            XposedBridge.hookAllMethods(cls, method, callback("$clsName.$method", autoOpen))
                .toCollection(mutableListOf())
        }.onFailure {
            own("MH  hook-fail $clsName.$method ${it.javaClass.simpleName}")
        }.getOrNull()
        if (set.isNullOrEmpty()) {
            own("MH  no-method $clsName.$method")
            return
        }
        synchronized(unhooks) { unhooks.addAll(set) }
    }

    private fun callback(sig: String, autoOpen: Boolean) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                if (autoOpen) arm(sig)
                if (System.currentTimeMillis() > until) return
                record(sig, param)
            }
        }
    }

    // ------------------------------------------------------------------ 记录

    /**
     * 长按等源头一出现就开窗口（并**重拍基线快照**）。
     *
     * 两个关键点：
     *  - 已开着长窗口时也要**续期**，否则窗口末尾的长按会把菜单构建的后半段丢在窗口外；
     *  - 每次都重拍 [snapBefore]，diff 才只包含「长按之后新增的 View」。
     */
    private fun arm(sig: String) {
        val now = System.currentTimeMillis()
        val left = until - now
        if (left < AUTO_WINDOW_MS) until = now + AUTO_WINDOW_MS
        // 长按会沿 View 链触发好几次（performLongClick 向上传递），快照很贵，
        // 500ms 内只拍一次
        if (now - lastSnapAt > 500) {
            snapBefore = snapshot()
            lastSnapAt = now
        }
        mainHandler.removeCallbacks(flushTask)
        // 长按后 8 秒就出结论（菜单还在），而不是干等手工窗口到期
        mainHandler.postDelayed(flushTask, minOf(until - now, AUTO_WINDOW_MS))
        if (left < AUTO_WINDOW_MS) {
            own("MH ARM by $sig -> window ${until - now}ms, baseline re-snapped")
        }
    }

    @Volatile
    private var lastSnapAt = 0L

    private fun open(ms: Long) {
        val now = System.currentTimeMillis()
        until = now + ms
        counts.clear()
        snapAfter = null
        snapBefore = snapshot()
        mainHandler.removeCallbacks(flushTask)
        mainHandler.postDelayed(flushTask, ms)
    }

    private fun record(sig: String, param: XC_MethodHook.MethodHookParam) {
        val frames = frames()
        val key = "$sig <- " + frames.take(3).joinToString(" <- ")
        val n = counts.merge(key, 1) { a, b -> a + b } ?: 1
        // 同一调用点只打一次，后续只累加 —— 否则 addView/setVisibility 会把日志冲爆
        if (n == 1) {
            own("MH HIT $sig args=[${args(param)}] at " + frames.joinToString(" <- "))
        }
    }

    private fun frames(): List<String> {
        val out = mutableListOf<String>()
        for (st in Throwable().stackTrace) {
            if (out.size >= FRAMES) break
            val s = st.toString()
            if (NOISE.any { s.contains(it) }) continue
            out.add(s)
        }
        return out
    }

    private fun args(param: XC_MethodHook.MethodHookParam): String {
        val a = param.args ?: return ""
        return a.joinToString(", ") { v ->
            when (v) {
                null -> "null"
                is View -> "${v.javaClass.name}#${idHex(v)}"
                else -> v.toString().take(60)
            }
        }
    }

    private fun idHex(v: View): String {
        val id = v.id
        return if (id == View.NO_ID) "-" else "0x" + Integer.toHexString(id)
    }

    private fun flushInternal() {
        val entries = counts.entries.sortedByDescending { it.value }
        val left = (until - System.currentTimeMillis()).coerceAtLeast(0)
        own("MH FLUSH windowLeft=${left}ms hitKinds=${entries.size}")
        for (e in entries.take(60)) own("MH   ${e.value}x ${e.key}")

        val before = snapBefore
        val after = snapAfter ?: runCatching { snapshot() }.getOrNull()
        if (before != null && after != null) {
            val added = LinkedHashMap<String, Int>()
            for ((k, n) in after) {
                val d = n - (before[k] ?: 0)
                if (d > 0) added[k] = d
            }
            own("MH TREE-DIFF before=${before.size} after=${after.size} added=${added.size}")
            for ((k, n) in added.entries.sortedByDescending { it.value }.take(40)) {
                own("MH   +$n $k")
            }
        } else {
            own("MH TREE-DIFF skipped before=${before != null} after=${after != null}")
        }
    }

    // ------------------------------------------------------------------ 快照 / dump

    /** 所有 Window 的根 View（能覆盖「菜单是独立窗口」这种情况） */
    private fun roots(): List<View> {
        val out = mutableListOf<View>()
        runCatching {
            val cl = loader
            val wmg = if (cl != null) {
                XposedHelpers.findClass("android.view.WindowManagerGlobal", cl)
            } else {
                Class.forName("android.view.WindowManagerGlobal")
            }
            val inst = XposedHelpers.callStaticMethod(wmg, "getInstance")
            val views = XposedHelpers.getObjectField(inst, "mViews")
            // mViews 是 ArrayList<View>；不能当成 Collection<View> 直接 addAll（泛型擦除后
            // 强转整个容器会炸），逐个取出来判类型最稳
            (views as? Iterable<*>)?.forEach { (it as? View)?.let { v -> out.add(v) } }
        }.onFailure { own("MH  roots failed: ${it.javaClass.simpleName}: ${it.message}") }
        if (out.isEmpty()) {
            // 隐藏 API 被拒时（Android 16 上 mViews 未必拿得到）的兜底：
            // 至少把模块自己记下的前台 Activity 的 DecorView 拿出来
            val dec = runCatching { HostContext.activity()?.window?.decorView }.getOrNull()
            if (dec != null) {
                out.add(dec)
                own("MH  roots fallback: foreground decorView only")
            } else {
                own("MH  roots EMPTY and no foreground activity")
            }
        }
        return out
    }

    private fun snapshot(): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (root in roots()) collect(root, 0, out)
        return out
    }

    private fun collect(v: View, depth: Int, out: MutableMap<String, Int>) {
        if (depth > MAX_DEPTH) return
        val key = "${v.javaClass.name}#${idHex(v)}"
        out[key] = (out[key] ?: 0) + 1
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                runCatching { collect(v.getChildAt(i), depth + 1, out) }
            }
        }
    }

    private fun dumpWindows() {
        val rs = roots()
        own("MH WINDOWS count=${rs.size}")
        rs.forEachIndexed { i, v ->
            own("MH   win[$i] ${v.javaClass.name} ${v.width}x${v.height} vis=${v.visibility}")
        }
    }

    private fun dumpTree() {
        val rs = roots()
        own("MH TREE roots=${rs.size}")
        val lines = mutableListOf<String>()
        rs.forEachIndexed { i, v ->
            lines.add("MH  win[$i] ${v.javaClass.name}")
            walk(v, 1, lines)
        }
        for (l in lines.take(MAX_TREE_LINES)) own(l)
        if (lines.size > MAX_TREE_LINES) own("MH  ... ${lines.size - MAX_TREE_LINES} more")
    }

    private fun walk(v: View, depth: Int, out: MutableList<String>) {
        if (depth > MAX_DEPTH || out.size >= MAX_TREE_LINES) return
        val sb = StringBuilder()
        repeat(depth) { sb.append("  ") }
        sb.append(v.javaClass.name).append('#').append(idHex(v))
        sb.append(' ').append(v.width).append('x').append(v.height)
        sb.append(" vis=").append(v.visibility)
        val text = (v as? TextView)?.text?.toString()
        if (!text.isNullOrEmpty()) sb.append(" txt=").append(text.take(30))
        out.add("MH  $sb")
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                runCatching { walk(v.getChildAt(i), depth + 1, out) }
            }
        }
    }

    // ------------------------------------------------------------------ 落盘

    /**
     * 日志走后台线程批量写：取证窗口内命中上百个调用点，若每次都同步
     * `appendText`（开/写/关一次文件），主线程会被拖出 ANR。
     */
    private val queue = ConcurrentLinkedQueue<String>()

    @Volatile
    private var writer: Thread? = null

    private fun own(msg: String) {
        runCatching {
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            queue.add("$ts MH $msg")
        }
        startWriter()
    }

    private fun startWriter() {
        val t = writer
        if (t != null && t.isAlive) return
        val nt = Thread({
            while (!Thread.currentThread().isInterrupted) {
                runCatching {
                    Thread.sleep(400)
                    drain()
                }.onFailure { return@Thread }
            }
        }, "mh-log").apply { isDaemon = true }
        writer = nt
        nt.start()
    }

    private fun stopWriter() {
        drain()
        writer?.let { runCatching { it.interrupt() } }
        writer = null
    }

    private fun drain() {
        if (queue.isEmpty()) return
        val sb = StringBuilder()
        while (sb.length < 16384) {
            val s = queue.poll() ?: break
            sb.append(s).append('\n')
        }
        runCatching {
            val f = File(LOG)
            f.parentFile?.mkdirs()
            f.appendText(sb.toString())
        }.onFailure {
            // 文件写不进去时至少落 logcat，否则会误以为「探针没命中」
            runCatching { android.util.Log.e("WXGroupMH", "write failed: ${it.javaClass.simpleName}") }
            runCatching { LogX.w("MH write failed: ${it.javaClass.simpleName}") }
        }
    }
}
