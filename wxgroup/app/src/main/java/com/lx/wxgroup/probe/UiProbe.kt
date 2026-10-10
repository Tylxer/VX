package com.lx.wxgroup.probe

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.core.Anchors848
import com.lx.wxgroup.core.ContactListRef
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.core.ContactSync
import com.lx.wxgroup.util.LogX
import java.lang.reflect.Modifier
import java.util.Collections

/**
 * M1 主力探针：不猜类名，靠运行时反射把「当前页面到底用了哪个 Adapter、数据对象长什么样」摊到日志里。
 *
 * 为什么这么做：GP 版微信没有可抄的 hook 点，混淆名（h1.a 之类）每个版本都变。
 * 与其猜，不如在 Fragment 可见时把它 View 树里的 RecyclerView 抓出来，
 * 反射它的 Adapter 和数据集合，把字段名 + 值全打出来，让数据自己说话。
 *
 * 全部包了 try/catch：探针绝不能成为崩溃源。
 */
object UiProbe {

    private const val MAX_FRAGMENT_DUMP = 10
    // 锚点已经定位完了，字段 dump 现在纯粹是噪音，压低行数免得把 logcat 缓冲冲爆
    private const val MAX_FIELD_LINES = 60
    private const val DELAY_MS = 1800L

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private val seenActivities = Collections.synchronizedSet(mutableSetOf<String>())
    private val seenFragments = Collections.synchronizedSet(mutableSetOf<String>())
    private val seenAdapters = Collections.synchronizedSet(mutableSetOf<String>())
    private val perFragment = Collections.synchronizedMap(mutableMapOf<String, Int>())
    private val firstContactDone = java.util.concurrent.atomic.AtomicBoolean(false)
    private var dumpCount = 0

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookActivities(lpparam)
        hookFragments(lpparam)
        hookVisibility(lpparam)
        hookSetAdapter(lpparam)
    }

    /**
     * 切 Tab 不会重新 performResume，但会走 onHiddenChanged / setUserVisibleHint。
     * 通讯录页就是在这一刻才真正加载数据的。
     */
    private fun hookVisibility(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (method in listOf("onHiddenChanged", "setUserVisibleHint")) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    "androidx.fragment.app.Fragment", lpparam.classLoader, method,
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching {
                                val visible = param.args[0] as? Boolean ?: return
                                // onHiddenChanged(hidden=false) / setUserVisibleHint(true) 都表示可见
                                val shown = if (method == "onHiddenChanged") !visible else visible
                                if (!shown) return
                                val name = param.thisObject.javaClass.name
                                if (dumpTimes(name) > 2) return
                                // 数据异步加载，稍等一下再抓
                                mainHandler.postDelayed({
                                    runCatching { dumpFragment(param.thisObject, "$name (visible)") }
                                }, 1200)
                            }
                        }
                    }
                )
                LogX.i("UiProbe: hooked Fragment.$method")
            }.onFailure { LogX.d("UiProbe: hook Fragment.$method failed") }
        }
    }

    /**
     * 抓 adapter 被设置的那一刻 —— 这是数据真正绑定上 RecyclerView 的瞬间。
     */
    private fun hookSetAdapter(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "androidx.recyclerview.widget.RecyclerView",
                lpparam.classLoader,
                "setAdapter",
                "androidx.recyclerview.widget.RecyclerView\$Adapter",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val rv = param.thisObject as? View ?: return
                            val adapter = param.args[0] ?: return
                            val aName = adapter.javaClass.name
                            if (!seenAdapters.add(aName)) return
                            LogX.i("SETADAPTER view=${rv.javaClass.simpleName} adapter=$aName")
                            mainHandler.postDelayed({
                                runCatching { dumpList("SETADAPTER/$aName", rv) }
                            }, 1500)
                        }
                    }
                }
            )
            LogX.i("UiProbe: RecyclerView.setAdapter hooked")
        }.onFailure { LogX.w("UiProbe: hook setAdapter failed: ${it.javaClass.simpleName}") }
    }

    private fun dumpTimes(name: String): Int {
        val n = (perFragment[name] ?: 0) + 1
        perFragment[name] = n
        return n
    }

    private fun hookActivities(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cb = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val act = param.thisObject as? android.app.Activity ?: return
                    // M2-B：弹分组选择框需要宿主的 Activity 上下文
                    HostContext.setActivity(act)
                    val name = act.javaClass.name
                    if (seenActivities.add(name)) LogX.i("ACT $name")
                }
            }
        }
        // ⚠️ 只挂 `onResume` 是不够的：hook 基类方法对**覆写了它的子类不生效**
        // （微信的 LauncherUI 就是），真机上 `HostContext.activity()` 一直是 null，
        // 弹窗直接 BadTokenException。`performResume` 由 Instrumentation 调用、
        // 子类一般不覆写，用它兜底才能稳稳拿到当前 Activity。
        // 用 hookAllMethods：这个方法各版本签名不一样（Android 12+ 多一个参数）。
        for (m in arrayOf("onResume", "performResume")) {
            runCatching {
                XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.app.Activity", lpparam.classLoader), m, cb
                )
                LogX.i("UiProbe: Activity.$m hooked")
            }.onFailure { LogX.w("UiProbe: hook Activity.$m failed: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * Fragment 用 performResume 而不是 onResume：
     * 子类常常覆写 onResume，hook 父类 onResume 会被跳过；performResume 是 FragmentManager 调的，子类一般不覆写。
     */
    private fun hookFragments(lpparam: XC_LoadPackage.LoadPackageParam) {
        val candidates = listOf("androidx.fragment.app.Fragment", "android.support.v4.app.Fragment")
        var hooked = false
        for (cls in candidates) {
            for (method in listOf("performResume", "onResume")) {
                val ok = runCatching {
                    XposedHelpers.findAndHookMethod(
                        cls, lpparam.classLoader, method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                runCatching { onFragmentResume(param.thisObject) }
                            }
                        }
                    )
                }.isSuccess
                if (ok) {
                    LogX.i("UiProbe: hooked $cls.$method")
                    hooked = true
                    break
                }
            }
            if (hooked) break
        }
        if (!hooked) LogX.w("UiProbe: no Fragment class hooked (androidx/support both missing?)")
    }

    private fun onFragmentResume(fragment: Any) {
        val name = fragment.javaClass.name
        if (!seenFragments.add(name)) return
        LogX.i("FRAG $name")

        if (dumpCount >= MAX_FRAGMENT_DUMP) return
        dumpCount++

        // 通讯录这类页面是异步加载数据的：resume 那一刻 WxRecyclerView 的 adapter 还是 null。
        // 所以先立刻试一次，拿不到就 1.8s 后再试。
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        runCatching {
            if (dumpFragment(fragment, name)) done.set(true)
        }
        mainHandler.postDelayed({
            if (!done.get()) runCatching { dumpFragment(fragment, name) }
        }, DELAY_MS)
    }

    /** @return true 表示抓到了非空 adapter */
    private fun dumpFragment(fragment: Any, name: String): Boolean {
        val view = fragmentView(fragment)
        if (view == null) {
            LogX.i("FRAG $name : no view")
            return false
        }
        val lists = mutableListOf<View>()
        collectListViews(view, lists, 0)
        LogX.i("FRAG $name : listViews=${lists.size}")
        var got = false
        lists.take(3).forEachIndexed { i, v -> if (dumpList("$name#$i", v)) got = true }
        return got
    }

    private fun fragmentView(fragment: Any): View? {
        runCatching {
            val m = fragment.javaClass.getMethod("getView")
            val v = m.invoke(fragment)
            if (v is View) return v
        }
        // 某些版本 getView 拿不到，退回到 mView 字段
        var cls: Class<*>? = fragment.javaClass
        while (cls != null && cls != java.lang.Object::class.java) {
            runCatching {
                val f = cls!!.getDeclaredField("mView")
                f.isAccessible = true
                val v = f.get(fragment)
                if (v is View) return v
            }
            cls = cls.superclass
        }
        return null
    }

    private fun collectListViews(v: View, out: MutableList<View>, depth: Int) {
        if (depth > 12 || out.size > 20) return
        val n = v.javaClass.name
        if (n.contains("RecyclerView") || n.contains("ListView") || n.contains("ViewPager")) {
            out.add(v)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                runCatching {
                    val child = v.getChildAt(i)
                    if (child != null) collectListViews(child, out, depth + 1)
                }
            }
        }
    }

    /** @return true 表示抓到了非空 adapter */
    private fun dumpList(tag: String, listView: View): Boolean {
        val adapter = runCatching { listView.javaClass.getMethod("getAdapter").invoke(listView) }
            .onFailure { LogX.i("LIST[$tag] getAdapter failed: ${it.javaClass.simpleName}") }
            .getOrNull()
        if (adapter == null) {
            LogX.i("LIST[$tag] ${listView.javaClass.simpleName} adapter=null")
            return false
        }
        val aName = adapter.javaClass.name
        val count = runCatching {
            adapter.javaClass.getMethod("getItemCount").invoke(adapter)
        }.getOrNull() ?: runCatching {
            adapter.javaClass.getMethod("getCount").invoke(adapter)
        }.getOrNull()
        LogX.i("LIST[$tag] view=${listView.javaClass.simpleName} adapter=$aName count=$count")

        // M2-A：认出通讯录 Adapter 就触发一次好友同步（内部按实例去重 + 限次）
        if (aName == Anchors848.CONTACT_ADAPTER) {
            // M2-B 长按菜单要靠这个引用判断「长按发生在通讯录里」
            ContactListRef.set(listView, adapter)
            runCatching { ContactSync.onContactList(listView, adapter) }
                .onFailure { LogX.e("SYNC trigger failed", it) }
        }

        // Adapter 里通常有一个 List 字段存数据；找到它并 dump 第一个元素
        // 通讯录的数据链路较深：adapter -> LiveData -> 分组列表 -> 联系人列表 -> Contact 字段
        dumpFields("LIST[$tag]", adapter, depth = 0, maxDepth = 5, lines = Counter())
        return true
    }

    private class Counter(var n: Int = 0)

    /**
     * 8.0.48 实测：通讯录条目的 contact 字段类型是 com.tencent.mm.storage.h4。
     * 换版本这个值会变，届时改这里（或用 DexKit 按特征重新定位）。
     */
    private fun isContactClass(cn: String): Boolean = cn == Anchors848.CONTACT_CLASS

    /** 把 Contact 的字段和无参 getter 全打出来 —— wxid / 昵称 / 备注 就藏在这里 */
    private fun dumpContact(tag: String, obj: Any) {
        val fields = stringFields(obj)
        val full = firstContactDone.compareAndSet(false, true)
        LogX.i("CONTACT class=${obj.javaClass.name}${if (full) " (full dump)" else ""}")

        if (full) {
            // 第一次全量打印（含空值），用来确认备注字段到底叫什么
            var n = 0
            for ((k, v) in fields) {
                if (n++ >= 60) break
                LogX.i("CONTACT.F $k=${if (v.isEmpty()) "<empty>" else v}")
            }
            var m = 0
            for (method in obj.javaClass.methods) {
                if (m >= 40) break
                if (method.parameterCount != 0) continue
                if (method.name in setOf("getClass", "toString", "hashCode")) continue
                val r = runCatching { method.invoke(obj) }.getOrNull() ?: continue
                if (r is String && r.isNotEmpty()) {
                    LogX.i("CONTACT.M ${method.name}()=$r")
                    m++
                }
            }
        }

        val wxid = fields["field_username"].takeIf { !it.isNullOrEmpty() }
            ?: runCatching {
                obj.javaClass.getMethod("getPrimaryKeyValue").invoke(obj) as? String
            }.getOrNull() ?: "?"
        val nick = fields["field_nickname"] ?: ""
        val alias = fields["field_alias"] ?: ""
        val remark = fields.entries
            .firstOrNull { it.key.contains("remark", true) && it.value.isNotEmpty() }?.value ?: ""
        LogX.i("CONTACT row wxid=$wxid | nick=$nick | remark=$remark | alias=$alias")
    }

    /** 收集对象及其父类里所有 String 字段的 名 -> 值 */
    private fun stringFields(obj: Any): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        var c: Class<*>? = obj.javaClass
        var guard = 0
        while (c != null && c != java.lang.Object::class.java && guard++ < 5) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.type != String::class.java) continue
                f.isAccessible = true
                val v = runCatching { f.get(obj) as? String }.getOrNull() ?: continue
                out.putIfAbsent(f.name, v)
            }
            c = c.superclass
        }
        return out
    }

    private fun dumpFields(tag: String, obj: Any, depth: Int, maxDepth: Int, lines: Counter) {
        if (depth > maxDepth || lines.n >= MAX_FIELD_LINES) return
        var cls: Class<*>? = obj.javaClass
        var level = 0
        while (cls != null && cls != java.lang.Object::class.java && level < 3) {
            for (f in cls.declaredFields) {
                if (lines.n >= MAX_FIELD_LINES) return
                if (Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val v = runCatching { f.get(obj) }.getOrNull() ?: continue
                when (v) {
                    is String -> {
                        LogX.i("$tag ${f.name}(String)=${v.take(100)}")
                        lines.n++
                    }
                    is Int, is Long, is Boolean, is Float, is Double -> {
                        LogX.i("$tag ${f.name}(${v.javaClass.simpleName})=$v")
                        lines.n++
                    }
                    is List<*> -> {
                        LogX.i("$tag ${f.name}(List)=${v.size}")
                        lines.n++
                        // 通讯录第一项通常是字母索引头，真实联系人藏在后面，所以取前 3 个
                        v.filterNotNull().take(3).forEachIndexed { idx, item ->
                            dumpFields("$tag/${f.name}[$idx]", item, depth + 1, maxDepth, lines)
                        }
                    }
                    else -> {
                        // 联系人对象本身是自定义类型，必须递归进去才看得到 wxid / 昵称 / 备注。
                        // 但 View 一类的对象字段上百个，会把日志刷爆，只记类名不递归。
                        val cn = v.javaClass.name
                        LogX.i("$tag ${f.name}(obj)=$cn")
                        lines.n++
                        // 命中 Contact 类就全量展开，不受递归深度限制
                        if (isContactClass(cn)) {
                            dumpContact(tag, v)
                            return
                        }
                        val worthIt = v !is View && depth < maxDepth &&
                            !cn.startsWith("java.") && !cn.startsWith("kotlin.") &&
                            !(cn.startsWith("android.") && !cn.startsWith("androidx."))
                        if (worthIt) {
                            dumpFields("$tag.${f.name}", v, depth + 1, maxDepth, lines)
                        }
                    }
                }
            }
            cls = cls.superclass
            level++
        }
    }
}
