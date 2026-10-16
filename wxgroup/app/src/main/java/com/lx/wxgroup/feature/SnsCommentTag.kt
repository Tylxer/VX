package com.lx.wxgroup.feature

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 朋友圈**评论**的 `[已删除]` 标签（需求 6.2：「显示评论 [已删除] 标签」）。
 *
 * ## 数据源：不是微信库，是我们自己的归档
 * 评论被删时 [SnsCommentGuard] 拦下 DELETE 并把整行写进 `sns_comment_archive`，
 * 这里读的就是那份表（`snsId -> {commentSvrID…}`）。
 * ⚠️ 推论：防撤回开关没开 → 表里没数据 → 标签不会打。两个都开才生效（跟 [SnsTag] 同理）。
 *
 * ## ⚠️ 落点还没取证，所以默认是「降级」状态
 * 已取证的部分：[ADAPTER_CLASS] / [WX_RV_CLASS] 是**未混淆的稳定类名**（[SnsTag] 四轮真机
 * 验证过），绑定回调里 `holder.itemView ↔ snsID` 是可靠的。
 *
 * 没取证的部分：**评论行 View ↔ commentSvrID 的映射**。朋友圈的评论是画在动态 item
 * 内部的子 View 上，不是独立列表项 —— 哪一行对应哪条 `commentSvrID`，没抓到之前不能猜。
 * 所以两个 `PENDING_*` 常量留空：install 时反射查不到就**静默跳过**，运行时只计数、
 * **不打标**。取证到之后把常量填上即可，逻辑一行不用改。
 *
 * 降级期要验证「容器 / 落点」本身没问题，用 `probe("mark")`：给指定 snsId 的动态
 * 强制打一个标签，肉眼确认位置对不对。
 */
object SnsCommentTag {

    // ==================================================================
    // ⚠️ 待取证区 —— 留空 = 未取证，install 反射查不到就跳过，运行期不打标只计数
    // ==================================================================

    /** T3（待取证）：评论行 View 的类名 */
    private const val PENDING_COMMENT_ROW_CLASS = ""

    /** T4（待取证）：从评论行取 `commentSvrID` 的方法名（无参，返回 long/Long/int/Integer） */
    private const val PENDING_COMMENT_ID_METHOD = ""

    // ------------------------------------------------------------------ 常量

    /** 微信 RecyclerView 适配器基类：**未混淆**（与 SnsTag 同源，真机已验证） */
    private const val ADAPTER_CLASS = "com.tencent.mm.view.recyclerview.WxRecyclerAdapter"

    /** 微信自研列表基类：抓 setAdapter 拿「具体 adapter 类」 */
    private const val WX_RV_CLASS = "com.tencent.mm.view.recyclerview.WxRecyclerView"

    /** 只给朋友圈的 adapter 挂钩 */
    private const val SNS_ADAPTER_PREFIX = "com.tencent.mm.plugin.sns."

    private const val MARK = "wxgroup.sns.comment.deleted.tag"
    private const val LABEL = "[已删除]"

    private const val SET_TTL_MS = 5000L
    private const val CFG_TTL_MS = 30000L
    private const val LOG_MAX = 5

    @Volatile private var enabled = false
    @Volatile private var cfgAt = 0L
    @Volatile private var delMap: Map<Long, Set<Long>> = emptyMap()
    @Volatile private var setAt = 0L

    private val loading = AtomicBoolean(false)
    private val added = AtomicInteger(0)
    private val degradeHits = AtomicInteger(0)
    private val binds = AtomicInteger(0)
    private val miscLogged = AtomicInteger(0)

    /** 探针用的强制集合：只对它打开，正常运行里恒为空 */
    @Volatile private var forceSet: Set<Long> = emptySet()

    /** 我们插进去的标签 View（弱引用，绝不钉住宿主 View） */
    private val tagged: MutableList<WeakReference<TextView>> =
        Collections.synchronizedList(mutableListOf())

    /** 后台读归档库用；daemon 线程，绝不能挡住宿主进程退出 */
    private val bg: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "snscmt-archive").apply { isDaemon = true }
        }
    }

    private var pendingRowCls: Class<*>? = null
    private var pendingIdMethod: Method? = null

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        var hooked = 0

        // ① 基类上声明的实现（子类不覆写时走这条）—— 已取证
        runCatching { XposedHelpers.findClass(ADAPTER_CLASS, cl) }
            .onSuccess { hooked += hookAdapterClass(it) }
            .onFailure { LogX.w("SNSC_TAG: $ADAPTER_CLASS not found") }

        // ② 子类一旦覆写 onBindViewHolder，挂基类就是死钩（静默失效），
        //    靠 setAdapter 抓具体 adapter 类补挂
        hooked += hookSetAdapter(cl)

        // ③ 待取证点
        resolvePending(lpparam)

        if (hooked == 0) LogX.w("SNSC_TAG: no bind hook installed")
        runCatching { refresh() }
        LogX.i("SNSC_TAG installed enabled=$enabled hooks=$hooked " +
            "pending=${pendingIdMethod != null}")
    }

    private val hookedMethods: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** 给一个 adapter 类挂它所有 `onBindViewHolder(?, int)` 的具体重载；返回新挂条数 */
    private fun hookAdapterClass(c: Class<*>): Int {
        var n = 0
        val ms = runCatching { c.methods }.getOrNull() ?: return 0
        // 2 参 (holder,pos) 与 3 参 (holder,pos,payloads) 都要挂：
        // adapter 只覆写 3 参版时，只挂 2 参就是完全静默失效。
        val cands = ms.filter {
            it.name == "onBindViewHolder" && !Modifier.isAbstract(it.modifiers) &&
                it.parameterTypes.size >= 2 &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                (it.parameterTypes.size == 2 ||
                    (it.parameterTypes.size == 3 && it.parameterTypes[2] == List::class.java))
        }
        val solid = cands.filter { !it.isBridge }
        for (m in solid.ifEmpty { cands }) {
            val key = "${m.declaringClass.name}#${m.name}" +
                "(${m.parameterTypes.joinToString { p -> p.name }})"
            if (!hookedMethods.add(key)) continue
            if (runCatching { XposedBridge.hookMethod(m, bindHook) }.isSuccess) n++
        }
        if (n > 0) LogX.i("SNSC_TAG hooked adapter=${c.name} +$n")
        return n
    }

    private fun hookSetAdapter(cl: ClassLoader): Int {
        val rv = runCatching { XposedHelpers.findClass(WX_RV_CLASS, cl) }.getOrElse {
            LogX.w("SNSC_TAG: $WX_RV_CLASS not found, concrete-adapter hook skipped")
            return 0
        }
        val m = rv.methods.firstOrNull { it.name == "setAdapter" && it.parameterTypes.size == 1 }
            ?: runCatching {
                var c: Class<*>? = rv.superclass
                var up = 0
                var found: Method? = null
                while (c != null && up < 6 && found == null) {
                    found = c.methods.firstOrNull {
                        it.name == "setAdapter" && it.parameterTypes.size == 1
                    }
                    c = c.superclass
                    up++
                }
                found
            }.getOrNull()
        if (m == null) {
            LogX.w("SNSC_TAG: setAdapter not found, concrete-adapter hook skipped")
            return 0
        }
        return runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val a = param.args?.getOrNull(0) ?: return
                        if (!a.javaClass.name.startsWith(SNS_ADAPTER_PREFIX)) return
                        hookAdapterClass(a.javaClass)
                    }.onFailure { LogX.w("SNSC_TAG setAdapter: ${it.javaClass.simpleName}") }
                }
            })
            1
        }.getOrDefault(0)
    }

    /** 待取证点：常量为空就跳过并打日志；不为空就反射找，找不到同样跳过 */
    private fun resolvePending(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (PENDING_COMMENT_ROW_CLASS.isBlank() || PENDING_COMMENT_ID_METHOD.isBlank()) {
            LogX.i("SNSC_TAG: 评论行→commentSvrID 映射未取证 -> 降级（只计数不打标）")
            return
        }
        val c = runCatching { XposedHelpers.findClass(PENDING_COMMENT_ROW_CLASS, lpparam.classLoader) }
            .getOrNull()
        if (c == null) {
            LogX.w("SNSC_TAG: pending row class not found: $PENDING_COMMENT_ROW_CLASS")
            return
        }
        val m = runCatching {
            c.methods.firstOrNull { it.name == PENDING_COMMENT_ID_METHOD && it.parameterTypes.isEmpty() }
        }.getOrNull()
        if (m == null) {
            LogX.w("SNSC_TAG: pending id method not found: $PENDING_COMMENT_ID_METHOD")
            return
        }
        pendingRowCls = c
        pendingIdMethod = m
        LogX.i("SNSC_TAG: pending resolved ${c.name}#${m.name}")
    }

    private fun refresh() {
        enabled = Kv.getBool(Keys.ANTIREVOKE_COMMENT) && Kv.getBool(Keys.ANTIREVOKE_COMMENT_DELETED_TAG)
        cfgAt = SystemClock.elapsedRealtime()
        setAt = 0L
        forceSet = emptySet() // 验证用的强制集合只在探针里设，开关一变就清掉
    }

    // ------------------------------------------------------------------ 绑定回调

    private val bindHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching { onBind(param) }
                .onFailure { logOnce("SNSC_TAG bind err: ${it.javaClass.simpleName}") }
        }
    }

    private fun onBind(param: XC_MethodHook.MethodHookParam) {
        val a = param.args
        if (a == null || a.size < 2) return
        binds.incrementAndGet()
        if (SystemClock.elapsedRealtime() - cfgAt > CFG_TTL_MS) refresh()
        if (!enabled) return
        if (delMap.isEmpty() && forceSet.isEmpty()) {
            loadAsync() // ⚠️ 主线程不查库，丢后台
            return
        }
        val holder = a[0] ?: return
        val pos = a[1] as? Int ?: return
        val itemView = itemViewOf(holder) ?: return
        val snsId = snsIdOfItem(param.thisObject, pos, itemView) ?: return
        val svrIds = delMap[snsId]
        // 探针的强制模式：不看 commentSvrID，直接给整条动态打标签，用来验证落点
        if (forceSet.contains(snsId)) {
            applyLabel(itemView, LABEL)
            return
        }
        if (svrIds.isNullOrEmpty()) return
        val getter = pendingIdMethod
        if (getter == null || pendingRowCls == null) {
            // ⚠️ 未取证 → 降级：只计数不打标。打错标签比不打标签糟糕得多。
            degradeHits.incrementAndGet()
            logOnce("SNSC_TAG degrade: snsId=$snsId 有 ${svrIds.size} 条已删评论，" +
                "但评论行→commentSvrID 映射未取证 -> skip")
            return
        }
        for (row in rowsOf(itemView, pendingRowCls!!)) {
            val svr = runCatching {
                when (val v = getter.invoke(row)) {
                    is Long -> v
                    is Int -> v.toLong()
                    else -> null
                }
            }.getOrNull() ?: continue
            if (svrIds.contains(svr)) applyLabel(row, LABEL)
        }
    }

    /** ViewHolder 的 `itemView`（先按名字取，取不到再扫字段） */
    private fun itemViewOf(holder: Any): View? {
        runCatching { XposedHelpers.getObjectField(holder, "itemView") as? View }
            .getOrNull()?.let { return it }
        return runCatching {
            holder.javaClass.fields.firstOrNull {
                View::class.java.isAssignableFrom(it.type)
            }?.get(holder) as? View
        }.getOrNull()
    }

    /**
     * item ↔ snsID：`getData()[pos]` 的**下标可能对不齐**（列表第一项是封面头），
     * 所以在 ±2 窗口里找；找到多个不同 snsId 就放弃（宁可不打标）。
     */
    private fun snsIdOfItem(adapter: Any?, pos: Int, itemView: View): Long? {
        if (adapter == null) return null
        val list = runCatching { XposedHelpers.callMethod(adapter, "getData") as? List<*> }
            .getOrNull() ?: return null
        val texts = textsOf(itemView)
        val hits = HashSet<Long>()
        // ⚠️ 读不到任何文字就放弃：没法对账，打错标签比不打标签糟糕得多
        if (texts.isEmpty()) return null
        for (i in (pos - 2)..(pos + 2)) {
            val e = list.getOrNull(i) ?: continue
            val info = SnsTagLogic.parseInfo(e.toString()) ?: continue
            if (SnsTagLogic.nameOk(info.name, texts)) hits += info.snsId
        }
        return hits.singleOrNull()
    }

    private fun textsOf(root: View): List<String> {
        val out = mutableListOf<String>()
        val stack = ArrayDeque<View>()
        stack += root
        var budget = 60 // 兜底：别在异常深的树上跑太久
        while (stack.isNotEmpty() && budget-- > 0) {
            val v = stack.removeLast()
            if (v is TextView) v.text?.toString()?.let { if (it.isNotEmpty()) out += it }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) v.getChildAt(i)?.let { stack += it }
            }
        }
        return out
    }

    /** 取 itemView 子树里所有的「评论行」View */
    private fun rowsOf(root: View, cls: Class<*>): List<View> {
        val out = mutableListOf<View>()
        val stack = ArrayDeque<View>()
        stack += root
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            if (cls.isInstance(v)) out += v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) v.getChildAt(i)?.let { stack += it }
            }
        }
        return out
    }

    private fun isRecyclerView(v: View): Boolean {
        var c: Class<*>? = v.javaClass
        var up = 0
        while (c != null && up < 8) {
            if (c.name == "androidx.recyclerview.widget.RecyclerView") return true
            c = c.superclass
            up++
        }
        return false
    }

    private fun applyLabel(host: View, text: String) {
        if (host !is ViewGroup) return
        // ⚠️ 往 RecyclerView 里直接 addView 会绕开 LayoutManager 的 child 管理，
        //    实测会把微信搞崩 —— 宁可什么都不做
        //    （这里按**类名**判，不 import androidx：模块没有 androidx 依赖）
        if (isRecyclerView(host)) {
            logOnce("SNSC_TAG: refuse to label Rv, host=${host.javaClass.name}")
            return
        }
        for (i in 0 until host.childCount) {
            if (host.getChildAt(i)?.tag == MARK) return // 幂等
        }
        val tv = TextView(host.context).apply {
            this.text = text
            tag = MARK
            setTextColor(0xFFFF5252.toInt())
            textSize = 11f
        }
        runCatching {
            when (host) {
                is LinearLayout -> host.addView(tv, 0)
                is FrameLayout -> host.addView(tv, 0)
                else -> host.addView(tv, 0)
            }
        }.onFailure {
            logOnce("SNSC_TAG addView failed: ${it.javaClass.simpleName}")
            return
        }
        tagged += WeakReference(tv)
        added.incrementAndGet()
    }

    /** 摘掉我们加的所有标签（探针 / 关开关时用） */
    private fun clearLabels() {
        synchronized(tagged) {
            for (ref in tagged) {
                val v = ref.get() ?: continue
                runCatching { (v.parent as? ViewGroup)?.removeView(v) }
            }
            tagged.clear()
        }
    }

    // ------------------------------------------------------------------ 数据源

    /** ⚠️ 后台线程读归档库，主线程绝不查库 */
    private fun loadAsync() {
        if (!loading.compareAndSet(false, true)) return
        bg.execute {
            runCatching {
                val m = SnsCommentGuard.archivedIds()
                delMap = m
                setAt = SystemClock.elapsedRealtime()
                if (m.isNotEmpty()) {
                    logOnce("SNSC_TAG loaded posts=${m.size} comments=" +
                        "${m.values.sumOf { it.size }}")
                }
            }.onFailure {
                logOnce("SNSC_TAG load failed: ${it.javaClass.simpleName}")
            }
            loading.set(false)
        }
    }

    // ------------------------------------------------------------------ 诊断

    private fun logOnce(msg: String) {
        if (miscLogged.get() < LOG_MAX && miscLogged.incrementAndGet() <= LOG_MAX) LogX.i(msg)
    }

    /**
     * 探针。`sub`：`stat` / `list` / `mark <snsId>` / `unmark` / `pending` / `refresh`
     */
    fun probe(sub: String, id: Long = 0L): String {
        return when (sub.lowercase()) {
            "stat" -> "SNSC_TAG stat enabled=${Kv.getBool(Keys.ANTIREVOKE_COMMENT)}&&" +
                "${Kv.getBool(Keys.ANTIREVOKE_COMMENT_DELETED_TAG)} binds=${binds.get()} " +
                "added=${added.get()} degrade=${degradeHits.get()} " +
                "posts=${delMap.size} setAge=${(SystemClock.elapsedRealtime() - setAt) / 1000}s"

            "list" -> {
                if (delMap.isEmpty()) "SNSC_TAGLS (empty) 归档里没有已删评论"
                else buildString {
                    appendLine("SNSC_TAGLS posts=${delMap.size}")
                    delMap.entries.take(10).forEach { (k, v) ->
                        appendLine("SNSC_TAGLS snsId=$k svrIds=${v.sorted()}")
                    }
                    append("(最多显示 10 条)")
                }
            }

            "mark" -> {
                if (id == 0L) return "SNSC_TAG mark: need snsId"
                forceSet = setOf(id)
                "SNSC_TAG mark snsId=$id -> 打开朋友圈刷到这条动态，" +
                    "看标签落点；验证完用 probe(\"unmark\") 摘掉"
            }

            "unmark" -> {
                forceSet = emptySet()
                clearLabels()
                "SNSC_TAG unmark ok"
            }

            "pending" -> buildString {
                appendLine("SNSC_TAG 待取证清单：")
                appendLine("T3 评论行 View 类名：'$PENDING_COMMENT_ROW_CLASS'（空 = 未取证）")
                append("T4 评论行 → commentSvrID 的无参 getter：'" +
                    "$PENDING_COMMENT_ID_METHOD'（空 = 未取证）")
            }

            "refresh", "reload" -> {
                refresh()
                loadAsync()
                "SNSC_TAG refreshed"
            }

            else -> "SNSC_TAG probe: stat | list | mark <snsId> | unmark | pending | refresh"
        }
    }
}
