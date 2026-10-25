package com.lx.wxgroup.feature

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.HintBadgeLogic
import com.lx.wxgroup.data.HintTextLogic
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 聊天页「防撤回·角标」（需求 6.2，R21d 方案 1）。
 *
 * 方案：**不往微信 message 表插任何行**，改为在**原文那条消息的气泡**上打一个小角标
 * `已阻止撤回 · <摘要>`（如 `已阻止撤回 · 语音 30秒`）。角标文案由
 * [HintTextLogic.badgeText] 出（格式已由 HintTextLogicTest 锁死）。
 *
 * ## 链路（全部 DEX 坐实，见 docs/_r21d_badge_chain.md）
 *
 * 聊天列表的 RecyclerView.Adapter 基类是 `com.tencent.mm.pluginsdk.ui.tools.q3`
 * （继承重打包的 androidx `w1` = RecyclerView.Adapter）。它把 ListView 风格的
 * `t3`（getCount/getItem/getView）桥接成 RecyclerView，header/footer 用两个
 * `SparseArray`（字段 d/e）占位。
 *
 * - **挂 q3 的 onBindViewHolder（2 参 + 3 参都挂）**：q3 全 dex 只有 q0 一个子类，
 *   而 q0 **没有**覆写 onBindViewHolder ⇒ 活钩。
 * - **pos 坐标系**：`onBindViewHolder(holder,pos)` 的 pos 是 RecyclerView 坐标（含 header
 *   偏移）。拿消息必须调 **`adapter.getItem(pos)`**（=q0.getItem），它内部已做
 *   `t3.getItem(pos - headerCount)` 换算，且 header/footer 位置返回 null。
 *   ⛔ 绝不能直接 `t3.getItem(pos)` —— 有 header 时差 headerCount 位，角标打到邻居消息上。
 * - **holder → itemView**：holder 是 `androidx.recyclerview.widget.c3`（RecyclerView.ViewHolder
 *   重打包），其 itemView 字段被 R8 改名为 `d`。这里**不写死字段名**，照 SnsTag 的
 *   `itemViewOf` 逐层反射 View 字段 + 排除 RecyclerView + 面积最大兜底。
 * - **msgId/type**：`h9.getMsgId()` / `h9.getType()` 未混淆（声明在 `k74/f0`，
 *   `getMethod` 沿继承链找）。
 *
 * ## 自证判据（防「pos 错位」这条最糟路径）
 * - [HintBadgeLogic.typeOk]：低 16 位类型闸门（吞掉 0x10000000 高位标志；伪装态
 *   type=10000/10002 与原文 type 不等 ⇒ 抑制，因为没有「原文气泡」可贴）。
 * - [HintBadgeLogic.contentOk]：仅文本消息，item 子树可见文字必须含 origContent 前 12 字。
 * - 非文本消息靠「msgId 全局唯一 + 字节码坐实的 getItem 换算 + typeOk」。
 */
object HintBadge {

    /** 我们加的标记 View 的 tag，用来识别/摘除 */
    private const val MARK = "wxgroup.badge.hint"

    /** 聊天列表 RecyclerView.Adapter 基类（q0 不覆写 onBindViewHolder ⇒ 活钩） */
    private const val ADAPTER_CLASS = "com.tencent.mm.pluginsdk.ui.tools.q3"

    /** setAdapter 兜底抓「具体 adapter 类」用的列表类（未混淆 setAdapter） */
    private const val CHAT_RV_CLASS = "com.tencent.mm.pluginsdk.ui.tools.ChattingRecyclerView"

    /** 只给聊天列表的 adapter 挂钩 */
    private const val ADAPTER_PREFIX = "com.tencent.mm.pluginsdk.ui.tools."

    private const val SET_TTL_MS = 5000L
    private const val SWEEP_MS = 2000L
    private const val CFG_TTL_MS = 30000L

    /** 日志限流：热路径里 LogX 是主线程同步文件 IO，只能留前几条 */
    private const val LOG_MAX = 5

    @Volatile private var enabled = false
    @Volatile private var cfgAt = 0L

    /** 被救消息台账缓存：msgId → (origType, origContent)。绝不每次 bind 查库 */
    @Volatile private var hitMap: Map<Long, Hit> = emptyMap()
    @Volatile private var setAt = 0L
    @Volatile private var firstLoad = false

    private val setLoading = AtomicBoolean(false)
    private val sweepScheduled = AtomicBoolean(false)

    @Volatile private var dbWarned = false
    @Volatile private var lpWarned = false
    @Volatile private var hostWarned = false

    /** 自诊断：静默失效全靠这几个数定位（binds>0 才说明钩子活着） */
    @Volatile private var binds = 0      // 所有 adapter 的回调次数（钩子存活）
    @Volatile private var chatBinds = 0  // 前缀限定后（聊天列表 adapter）的回调次数
    @Volatile private var resolved = 0   // getItem→msgId 成功
    @Volatile private var hits = 0       // msgId 命中 rescued 集（含 fake）
    @Volatile private var missed = 0     // 解析/取 View 失败
    @Volatile private var verifyFail = 0 // 文本对账失败（真被救但内容对不上）
    @Volatile private var masked = 0     // 类型闸门抑制（restore_view 伪装态）
    @Volatile private var lastMiss = ""

    private val added = AtomicInteger(0)
    private val logged = AtomicInteger(0)
    private val miscLogged = AtomicInteger(0)
    private val errLogged = AtomicInteger(0)

    private class Hit(val origType: Int, val origContent: String?)

    private fun logOnce(msg: String) {
        if (miscLogged.get() < LOG_MAX && miscLogged.incrementAndGet() <= LOG_MAX) LogX.i(msg)
    }

    /** 绑定深度守卫：只防真递归，不阻断「绑定里再绑一条」这种正常情况 */
    private val depth = ThreadLocal<Int>()

    /** item 根 View → 当前绑定的 msgId（弱引用，绝不钉住宿主 View） */
    private val bound: MutableMap<View, Long> =
        Collections.synchronizedMap(WeakHashMap<View, Long>())

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        var hooked = 0

        // ① 基类上声明的实现（q0 不覆写 ⇒ 活钩）
        runCatching { XposedHelpers.findClass(ADAPTER_CLASS, cl) }
            .onSuccess { hooked += hookAdapterClass(it) }
            .onFailure { LogX.w("HINTBADGE: $ADAPTER_CLASS not found") }

        // ② 兜底：setAdapter 抓具体 adapter 类再补挂（当前版本 q0 无覆写 ⇒ 预期无新增）
        hooked += hookSetAdapter(cl)

        if (hooked == 0) {
            LogX.w("HINTBADGE: no onBindViewHolder hook installed, badge disabled")
        }
        runCatching { refresh() }
        LogX.i("HINTBADGE installed enabled=$enabled hooks=$hooked")
    }

    private val hookedMethods: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** 给一个 adapter 类挂上它所有 `onBindViewHolder(?, int)` 的具体重载；返回新挂的条数 */
    private fun hookAdapterClass(c: Class<*>): Int {
        var n = 0
        val ms = runCatching { c.methods }.getOrNull() ?: return 0
        // 同一轮里优先非桥接方法：桥接 + 实方法都挂着会让一次绑定回调两次。
        // 2 参 (holder, pos) 和 3 参 (holder, pos, payloads) 都要挂。
        val cands = ms.filter {
            it.name == "onBindViewHolder" && !Modifier.isAbstract(it.modifiers) &&
                it.parameterTypes.size >= 2 &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                (it.parameterTypes.size == 2 ||
                    (it.parameterTypes.size == 3 && it.parameterTypes[2] == List::class.java))
        }
        val solid = cands.filter { !it.isBridge }
        for (m in (solid.ifEmpty { cands })) {
            val key = "${m.declaringClass.name}#${m.name}(${m.parameterTypes.joinToString { p -> p.name }})"
            if (!hookedMethods.add(key)) continue
            if (runCatching { XposedBridge.hookMethod(m, bindHook) }.isSuccess) n++
        }
        if (n > 0) LogX.i("HINTBADGE hooked adapter=${c.name} +$n")
        return n
    }

    private fun hookSetAdapter(cl: ClassLoader): Int {
        val rv = runCatching { XposedHelpers.findClass(CHAT_RV_CLASS, cl) }.getOrElse {
            LogX.w("HINTBADGE: $CHAT_RV_CLASS not found, concrete-adapter hook skipped")
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
            LogX.w("HINTBADGE: setAdapter not found, concrete-adapter hook skipped")
            return 0
        }
        return runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val a = param.args?.getOrNull(0) ?: return
                        if (!a.javaClass.name.startsWith(ADAPTER_PREFIX)) return
                        hookAdapterClass(a.javaClass)
                    }.onFailure { LogX.w("HINTBADGE setAdapter hook: ${it.javaClass.simpleName}") }
                }
            })
            1
        }.getOrDefault(0)
    }

    /**
     * 开关语义：角标的数据源是 `rescued` 台账，而台账只由「防撤回」写入。
     * 「显示撤回提示」是这次角标方案替换掉的旧提示行开关。两个都开才生效。
     */
    fun refresh() {
        val msg = Kv.getBool(Keys.ANTIREVOKE_MSG)
        val hint = Kv.getBool(Keys.ANTIREVOKE_SHOW_HINT)
        enabled = msg && hint
        cfgAt = SystemClock.elapsedRealtime()
        setAt = 0L // 顺手让台账也重读
        forceSet = emptySet()
        if (!enabled) ensureSweep()
    }

    private fun on(): Boolean {
        if (SystemClock.elapsedRealtime() - cfgAt > CFG_TTL_MS) refresh()
        return enabled
    }

    // ------------------------------------------------------------------ 绑定钩子

    private val bindHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            // binds 在开关判断之前自增 —— 钩子是不是死的，全看它
            binds++
            val d = depth.get() ?: 0
            if (d >= 4) return // 只防真递归
            depth.set(d + 1)
            try {
                runCatching { onBind(param) }
                    .onFailure { t ->
                        if (errLogged.get() < 3 && errLogged.incrementAndGet() <= 3) {
                            LogX.w("HINTBADGE err: ${t.javaClass.simpleName}")
                            LogX.e("HINTBADGE stack", t)
                        }
                    }
            } finally {
                if (d == 0) depth.remove() else depth.set(d)
            }
        }
    }

    private fun onBind(param: XC_MethodHook.MethodHookParam) {
        val adapter = param.thisObject ?: return

        // ① 前缀限定：虽然 q3 本身已经够窄，防御未来子类
        if (!adapter.javaClass.name.startsWith(ADAPTER_PREFIX)) return

        // ② 开关闸门必须在这里：否则关掉开关后只要滚动就照样打标，
        //    2 秒后才被 sweep 摘掉 → 「贴上→摘掉」反复闪。
        val holder = param.args?.getOrNull(0) ?: return
        val pos = param.args?.getOrNull(1) as? Int ?: return
        chatBinds++
        if (!on()) {
            runCatching { itemViewOf(holder)?.let { ensure(it, false, "") } }
            return
        }

        val itemView = itemViewOf(holder)
        if (itemView == null) {
            missed++
            lastMiss = "noitemview(${holder.javaClass.simpleName})"
            return
        }

        // ③ 拿消息：必须调 adapter.getItem(pos)（内部已减 headerCount），不是 t3.getItem(pos)
        val h9 = getItem(adapter, pos)
        if (h9 == null) {
            // header/footer 位置：正常跳过（不是失败），但顺手摘掉旧角标
            bound.remove(itemView)
            runCatching { ensure(itemView, false, "") }
            return
        }
        val msgId = msgIdOf(h9)
        if (msgId <= 0) {
            missed++
            lastMiss = "nomsgid(${h9.javaClass.simpleName})"
            bound.remove(itemView)
            runCatching { ensure(itemView, false, "") }
            return
        }
        val h9Type = typeOf(h9) ?: -1
        resolved++

        val rescued = rescued()[msgId]
        val forced = msgId in forceSet
        if (!forced && rescued == null) {
            // 不是被救消息：摘掉可能残留的旧角标（item 复用）
            bound.remove(itemView)
            runCatching { ensure(itemView, false, "") }
            return
        }
        hits++ // 命中 rescued（或 fake），在闸门之前自增（诊断用）

        if (rescued != null) {
            // 类型闸门：伪装态（restore_view）没有「原文气泡」，抑制角标
            if (!HintBadgeLogic.typeOk(h9Type, rescued.origType)) {
                masked++
                lastMiss = "masked(type=$h9Type orig=${rescued.origType})"
                bound.remove(itemView)
                runCatching { ensure(itemView, false, "") }
                return
            }
            // 文本对账：只对文本消息，宁可 missed 不打错
            if (rescued.origType == HintTextLogic.TYPE_TEXT) {
                val texts = textsOf(itemView)
                if (!HintBadgeLogic.contentOk(rescued.origContent, texts)) {
                    verifyFail++
                    lastMiss = "verify(texts=[${texts.take(3).joinToString("|") { it.take(10) }}])"
                    bound.remove(itemView)
                    runCatching { ensure(itemView, false, "") }
                    return
                }
            }
        }

        // ⚠️ 只有通过全部闸门才登记 bound：sweep 只看 bound，不重复走闸门，
        //    若闸门失败仍登记，sweep 会在 2 秒后把被抑制的角标重新贴回来。
        bound[itemView] = msgId
        ensureSweep()
        if (rescued != null) {
            apply(itemView, HintTextLogic.badgeText(rescued.origType, rescued.origContent))
        } else {
            // fake 探针：跳过对账，直接打（只用于验证 pos 链路）
            apply(itemView, HintTextLogic.BADGE_ONLY)
        }
    }

    // ------------------------------------------------------------------ holder → itemView

    private val itemViewCache = Collections.synchronizedMap(HashMap<String, Field?>())

    /**
     * holder → item 根 View。⚠️ 必须排除 RecyclerView 本身（SnsTag 实测踩过：
     * ViewHolder 里持有指向列表的 rv 引用，「面积最大」兜底必然选中它 → 所有 holder 都解析到
     * 同一个 RecyclerView）。也绝不能「取第一个 View 字段」。
     */
    private fun itemViewOf(holder: Any): View? {
        val key = holder.javaClass.name
        if (itemViewCache.containsKey(key)) {
            val f = itemViewCache[key] ?: return null
            return runCatching { f.get(holder) as? View }.getOrNull()
        }
        val cands = ArrayList<Pair<Field, View>>()
        var c: Class<*>? = holder.javaClass
        var up = 0
        while (c != null && up < 6) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (!View::class.java.isAssignableFrom(f.type)) continue
                val v = runCatching { f.isAccessible = true; f.get(holder) as? View }.getOrNull()
                if (v != null && !isRv(v)) cands += f to v
            }
            c = c.superclass
            up++
        }
        val best = cands.firstOrNull { it.second is ViewGroup && isRvChild(it.second) }?.second
            ?: cands.filter { it.second is ViewGroup }
                .maxByOrNull { it.second.width.toLong() * it.second.height }?.second
            ?: cands.firstOrNull { it.second is ViewGroup }?.second
            ?: cands.firstOrNull()?.second
        itemViewCache[key] = cands.firstOrNull { it.second === best }?.first
        if (best == null) {
            LogX.w("HINTBADGE: no View field on holder ${holder.javaClass.name}")
        } else {
            logOnce("HINTBADGE holder=${holder.javaClass.simpleName} pick=${best.javaClass.simpleName}" +
                " cands=[" + cands.take(6).joinToString(";") {
                    "${it.first.name}:${it.second.javaClass.simpleName}" +
                        "(${it.second.width}x${it.second.height})" +
                        "p=${it.second.parent?.javaClass?.simpleName}"
                } + "]")
        }
        return best
    }

    private fun isRv(v: View): Boolean = v.javaClass.name.contains("RecyclerView")

    private fun isRvChild(v: View): Boolean =
        v.parent?.javaClass?.name.orEmpty().contains("RecyclerView")

    // ------------------------------------------------------------------ adapter → h9 → msgId

    private val getItemCache = Collections.synchronizedMap(HashMap<String, Method?>())

    /**
     * adapter → `getItem(pos)`。⚠️ **必须调 adapter 自己的 getItem**（=q0.getItem），
     * 它内部做 `t3.getItem(pos - headerCount)` 换算；直接调 t3.getItem 会错 headerCount 位。
     * header/footer 位置返回 null。
     */
    private fun getItem(adapter: Any, pos: Int): Any? {
        val key = adapter.javaClass.name
        if (!getItemCache.containsKey(key)) {
            val m = adapter.javaClass.methods.firstOrNull {
                it.name == "getItem" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (m == null) LogX.w("HINTBADGE: ${adapter.javaClass.name} has no getItem(int)")
            m?.isAccessible = true
            getItemCache[key] = m
        }
        val m = getItemCache[key] ?: return null
        return runCatching { m.invoke(adapter, pos) }.getOrNull()
    }

    /** `h9.getMsgId()`（未混淆，声明在 k74/f0，getMethod 沿链找） */
    private fun msgIdOf(h9: Any): Long {
        val m = runCatching { h9.javaClass.getMethod("getMsgId") }.getOrNull() ?: return 0L
        return runCatching { m.invoke(h9) as? Long }.getOrNull() ?: 0L
    }

    /** `h9.getType()`（未混淆） */
    private fun typeOf(h9: Any): Int? {
        val m = runCatching { h9.javaClass.getMethod("getType") }.getOrNull() ?: return null
        return runCatching { m.invoke(h9) as? Int }.getOrNull()
    }

    // ------------------------------------------------------------------ 台账缓存

    /** 被救消息台账（内存缓存，TTL + 首屏同步 + 后台刷新，绝不每次 bind 查库） */
    private fun rescued(): Map<Long, Hit> {
        val now = SystemClock.elapsedRealtime()
        if (setAt != 0L && now - setAt < SET_TTL_MS) return hitMap
        // 首屏必须同步拿一次，否则进聊天页第一屏一个角标都没有
        if (!firstLoad) {
            val m = loadRescued()
            if (GroupDb.current() != null) firstLoad = true // 库还没开就别把首屏机会用掉
            hitMap = m
            setAt = now
            return m
        }
        // 之后一律后台刷新：主线程绝不查库
        if (setLoading.compareAndSet(false, true)) {
            Thread {
                try {
                    runCatching {
                        hitMap = loadRescued()
                        setAt = SystemClock.elapsedRealtime()
                    }
                } finally {
                    setLoading.set(false)
                }
            }.apply { isDaemon = true; name = "badge-rescued" }.start()
        }
        return hitMap
    }

    private fun loadRescued(): Map<Long, Hit> {
        val db = GroupDb.current()
        if (db == null) {
            if (!dbWarned) {
                dbWarned = true
                LogX.w("HINTBADGE: GroupDb not open, no badges")
            }
            return hitMap
        }
        return runCatching {
            db.listRescued().associate { it.msgId to Hit(it.origType, it.origContent) }
        }.onFailure { LogX.w("HINTBADGE listRescued failed: ${it.javaClass.simpleName}") }
            .getOrDefault(hitMap)
    }

    // ------------------------------------------------------------------ 全量重判

    private val sweeper = object : Runnable {
        override fun run() {
            sweepScheduled.set(false)
            runCatching { sweep() }
                .onFailure { LogX.w("HINTBADGE sweep err: ${it.javaClass.simpleName}") }
            ensureSweep()
        }
    }

    private fun ensureSweep() {
        if (synchronized(bound) { bound.isEmpty() }) return
        if (sweepScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(sweeper, SWEEP_MS)
        }
    }

    /**
     * 对每个绑定过的 itemView 重判。真相只认 [bound]；开关关着时按「空清单」处理（= 全摘），
     * 这样「关掉再打开」也能自己贴回来。
     */
    private fun sweep() {
        val snap = snapshot()
        if (snap.isEmpty()) return
        val map = if (on()) rescued() else emptyMap()
        for ((v, id) in snap) {
            if (!v.isAttachedToWindow) {
                synchronized(bound) { bound.remove(v) }
                continue
            }
            val hit = map[id]
            val forced = on() && id in forceSet
            val show = forced || hit != null
            val text = hit?.let { HintTextLogic.badgeText(it.origType, it.origContent) }
                ?: HintTextLogic.BADGE_ONLY
            ensure(v, show, text)
        }
    }

    private fun snapshot(): List<Pair<View, Long>> {
        val out = ArrayList<Pair<View, Long>>()
        synchronized(bound) {
            for (e in bound.entries) {
                val k = e.key ?: continue
                out += k to e.value
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 打标

    /**
     * 临时强制打标集合 —— **真机验证专用**（证明 pos 没对齐错位）。
     * 用法：长按消息 `op=forward sub=msgsnap` 拿 msgId → `op=hintbadge sub=fake --el id <msgId>`
     * → 滚屏 rebind → `op=hintbadge sub=dump` 看角标落点。
     */
    @Volatile private var forceSet: Set<Long> = emptySet()

    private fun apply(host: View, text: String) {
        if (!ensure(host, true, text)) {
            // ⚠️ 复核 F1：重试闭包必须**重新查一次开关**。原来无条件重放 ensure(host,true,...)，
            // 若在这条 post 排队期间开关被关掉，会逆着开关把角标贴回去。
            host.post { runCatching { if (on()) ensure(host, true, text) } }
        }
    }

    /**
     * 幂等：状态一致就什么都不做；文本变化（item 复用到另一条被救消息）时原地 setText。
     * @return true = 已按目标状态处理过；false = 因为还没 attach / host 不是容器等原因没动
     */
    private fun ensure(host: View, show: Boolean, text: String): Boolean {
        val target = host as? ViewGroup
        if (target == null) {
            if (!hostWarned) {
                hostWarned = true
                LogX.w("HINTBADGE: host not ViewGroup: ${host.javaClass.name}")
            }
            return false
        }
        if (!target.isAttachedToWindow) return false
        // ⚠️ 防御：往 RecyclerView 里直接 addView 会绕开 LayoutManager 的 child 管理，实测会崩
        if (isRv(target)) {
            logOnce("HINTBADGE: refuse to badge Rv, host=${target.javaClass.name}")
            return true
        }
        var existing: TextView? = null
        for (i in target.childCount - 1 downTo 0) {
            val c = runCatching { target.getChildAt(i) }.getOrNull() ?: continue
            if (MARK == c.tag) { // ⚠️ 用 equals 不用 ===：不依赖常量池驻留
                if (show) existing = c as? TextView else target.removeViewAt(i)
            }
        }
        if (!show) return true
        if (existing != null) {
            // item 复用到另一条被救消息：原地更新文案，不重复建 View
            if (existing.text?.toString() != text) existing.text = text
            return true
        }
        val tv = TextView(target.context).apply {
            this.text = text
            setTextColor(Color.parseColor("#E64340"))
            textSize = 12f
            tag = MARK
            id = View.generateViewId()
            isClickable = false // 别吃掉 item 的点击
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(UiKit.dp(target.context, 6), 0, 0, UiKit.dp(target.context, 2))
        }
        // 📌 位置：改到内容**下方**（R21d 用户反馈「压在正文顶部不好读」）。
        // ⚠️ 但「末尾」的含义随容器方向变：垂直 LinearLayout = 下方（正确）；
        //    横向 LinearLayout = **右端**，不是下方。而微信消息行正是「头像 + 内容」横排，
        //    直接 addView(childCount) 会把角标甩到右边，所以先找纵向子容器当落点。
        val dest = bottomSlot(target)
        val lp = labelParams(dest)
        runCatching {
            when {
                dest is LinearLayout -> dest.addView(tv, dest.childCount, lp)
                lp == null -> dest.addView(tv)
                else -> dest.addView(tv, lp)
            }
        }.onFailure { e ->
            logOnce("HINTBADGE addView failed: ${e.javaClass.simpleName}")
            runCatching { target.addView(tv) }
        }
        val n = added.incrementAndGet()
        if (logged.get() < LOG_MAX && logged.incrementAndGet() <= LOG_MAX) {
            LogX.i("HINTBADGE badge added #$n host=${target.javaClass.name} text=$text")
        }
        return true
    }

    /**
     * 给角标找「视觉上的下方」落点。
     *
     * ⚠️ 这不是「取最后一个子 View」那么简单：微信消息 item 是
     * **横向 LinearLayout（头像在左、内容在右）**，往里 append 会跑到右边。
     * 所以规则按优先级来：
     *
     * 1. **横向**容器 ⇒ 不是「下方」语义，先从右往左找纵向子容器钻进去。
     * 2. **纵向**容器 ⇒ 末尾就是下方，直接返回；但若最后一个 ViewGroup 子项也是纵向
     *    （`竖[名字, 气泡]` 这种），钻进去贴着气泡画，比退到外层更精确 ——
     *    否则角标会落在气泡**外面**，中间夹着时间戳就对不上号了。
     * 3. 非 LinearLayout（FrameLayout / Constraint）⇒ 直接返回，靠 gravity/约束定位。
     *
     * 判据里的 `vertical` 读的是 `LinearLayout.getOrientation()`（public，未混淆），
     * **不**去反射 `mOrientation` 字段（R8 会改名）。取不到方向时按「横向」保守处理 ——
     * 宁可回退到旧行为，也不要把角标甩到正文右边。
     */
    private fun bottomSlot(target: ViewGroup, depth: Int = 0): ViewGroup {
        if (target !is LinearLayout) return target
        if (depth >= 4) return target
        val vertical = runCatching {
            target.orientation == LinearLayout.VERTICAL
        }.getOrElse { false }

        if (!vertical) {
            // 横排：往里找**占位够宽**的 ViewGroup 子项钻进去。
            // ⚠️ 不能「随便挑最后一个 ViewGroup」—— 消息行末尾常挂状态图标 / 红包图标 /
            //    按钮行这类窄元素（宽 20~60dp），钻进去角标会跑到那小图标的正下方，
            //    看着像给图标打标。用「宽度 ≥ 本容器 50%」当闸门：内容区（气泡那列）
            //    通常占大头，窄装饰件天然落选。
            // ⚠️ 宽度为 0 表示还没 layout 完，此时**不能**用它当判据（会全部落选而退回
            //    横向容器末尾 = 正文右边，正是要避免的）。这种情况一律当「未知」放行。
            var fallback: ViewGroup? = null
            for (i in target.childCount - 1 downTo 0) {
                val c = runCatching { target.getChildAt(i) }.getOrNull() as? ViewGroup ?: continue
                if (MARK == c.tag || c.visibility == View.GONE) continue
                if (c.width <= 0 || target.width <= 0) {
                    // 未 layout：记住它，但如果后面找到量过的就用后面的
                    if (fallback == null) fallback = c
                    continue
                }
                if (c.width >= target.width * 0.5) return bottomSlot(c, depth + 1)
            }
            return fallback?.let { bottomSlot(it, depth + 1) } ?: target
        }

        // 竖排：末尾 = 下方。但末尾若是纵向子容器，钻进去贴得更准
        for (i in target.childCount - 1 downTo 0) {
            val c = runCatching { target.getChildAt(i) }.getOrNull() as? ViewGroup ?: continue
            if (MARK == c.tag || c.visibility == View.GONE) continue
            val cVert = c is LinearLayout &&
                runCatching { c.orientation == LinearLayout.VERTICAL }.getOrElse { false }
            return if (cVert) bottomSlot(c, depth + 1) else target
        }
        return target
    }

    private fun labelParams(target: ViewGroup): ViewGroup.LayoutParams? {
        val ctx = target.context
        val px = UiKit.dp(ctx, 12)
        val top = UiKit.dp(ctx, 4)
        when (target) {
            // ⚠️ 角标现在落在**内容下方**，所以要设下边距而不是上边距；
            //    否则紧贴下一条消息，看着像给下一条打标（正是这次要修的问题）。
            is LinearLayout -> return LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val vertical = runCatching { target.orientation == LinearLayout.VERTICAL }
                    .getOrElse { true }
                if (vertical) {
                    leftMargin = px
                    bottomMargin = top
                } else {
                    // 横向兜底：右侧对齐、与内容居中对齐
                    rightMargin = px
                }
            }
            // ⚠️ FrameLayout 是叠放容器：子 View 位置靠 gravity 定，跟「谁先谁后」无关。
            //    改成 BOTTOM（原来 TOP）—— 它在内容之上覆盖，压底部最不容易挡正文。
            is FrameLayout -> return FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.END or android.view.Gravity.BOTTOM
            ).apply { rightMargin = px; bottomMargin = top }
        }
        if (target.javaClass.name.contains("ConstraintLayout")) {
            runCatching {
                val cls = Class.forName(
                    "androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams",
                    false, target.javaClass.classLoader
                )
                val w = ViewGroup.LayoutParams.WRAP_CONTENT
                val lp = cls.getConstructor(
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                ).newInstance(w, w) as ViewGroup.LayoutParams
                // 角标不再挂 topToTop（顶部），改成**自己贴父底部**
                runCatching { XposedHelpers.setIntField(lp, "bottomToBottom", 0) }
                XposedHelpers.setIntField(lp, "endToEnd", 0)
                XposedHelpers.setIntField(lp, "leftMargin", px)
                XposedHelpers.setIntField(lp, "bottomMargin", top)
                return lp
            }.onFailure { logOnce("HINTBADGE: constraint LP failed: ${it.javaClass.simpleName}") }
        }
        if (!lpWarned) {
            lpWarned = true
            LogX.w("HINTBADGE: no explicit LP for ${target.javaClass.name}, use default")
        }
        return null
    }

    /**
     * item 子树里的可见文字（跳过我们自己加的角标），用来做文本对账。
     * ⚠️ 上限放宽到 20 条 / 深度 8（同 SnsTag：正文 TextView 常埋在内容区之后）。
     */
    private fun textsOf(root: View, max: Int = 20): List<String> {
        val out = ArrayList<String>(max)
        fun walk(v: View, d: Int) {
            if (out.size >= max || d > 8) return
            if (MARK == v.tag) return
            if (v is TextView) {
                val t = v.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) out.add(t)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    walk(runCatching { v.getChildAt(i) }.getOrNull() ?: continue, d + 1)
                }
            }
        }
        walk(root, 0)
        return out
    }

    // ------------------------------------------------------------------ 真机验证入口

    /** 按 msgId 精确模拟「这条被救下」（验证用）。传入无符号或有符号 msgId 均可。 */
    fun fake(raw: String): String {
        val id = raw.trim().toLongOrNull()
            ?: runCatching { java.lang.Long.parseUnsignedLong(raw.trim()) }.getOrNull()
            ?: return "HINTBADGE fake bad id=$raw"
        forceSet = setOf(id)
        setAt = 0L
        cfgAt = SystemClock.elapsedRealtime()
        for ((v, vId) in snapshot()) {
            val hit = rescued()[vId]
            runCatching {
                ensure(v, vId == id || hit != null,
                    hit?.let { HintTextLogic.badgeText(it.origType, it.origContent) }
                        ?: HintTextLogic.BADGE_ONLY)
            }
        }
        return "HINTBADGE fake u=$raw s=$id"
    }

    fun unfake(): String {
        forceSet = emptySet()
        ensureSweep()
        return "HINTBADGE unfake"
    }

    /** 明细：当前屏幕上每个 item 解析出的 msgId（无符号/有符号都打）+ 可见文字 + 落点 */
    fun dump(): String {
        val sb = StringBuilder("HINTBADGE dump force=${forceSet.size} rescued=${hitMap.size}\n")
        val snap = snapshot()
        sb.append("  bound(${snap.size}):\n")
        for ((v, id) in snap) {
            val hit = hitMap[id]
            sb.append("    u=").append(java.lang.Long.toUnsignedString(id))
                .append(" hit=").append(hit != null || id in forceSet)
                .append(" badge=").append(hit?.let { HintTextLogic.badgeText(it.origType, it.origContent) }
                    ?: if (id in forceSet) HintTextLogic.BADGE_ONLY else "-")
                .append(" txt=").append(textsOf(v, 3).joinToString("|") { it.take(12) })
                .append(" host=").append(v.javaClass.name)
                .append(" LL=").append(v is LinearLayout)
                .append(" FL=").append(v is FrameLayout)
                .append("\n")
        }
        return sb.toString()
    }

    /**
     * 自诊断。看数顺序：
     * ① `binds>0` —— 钩子活着吗
     * ② `chat>0` —— 前缀限定生效吗
     * ③ `resolved≈chat` —— getItem→msgId 通吗（差得多看 lastMiss）
     * ④ `hits>0` —— msgId 命中 rescued 集吗
     * ⑤ `added>0` —— 真的贴上去了吗
     * ⑥ `masked`/`verifyFail` —— 抑制原因（伪装态 / 文本对账）
     */
    fun stat(): String = "HINTBADGE stat enabled=${on()} " +
        "msg=${Kv.getBool(Keys.ANTIREVOKE_MSG)} hint=${Kv.getBool(Keys.ANTIREVOKE_SHOW_HINT)} " +
        "rescued=${hitMap.size} bound=${synchronized(bound) { bound.size }} " +
        "added=${added.get()} binds=$binds chat=$chatBinds resolved=$resolved " +
        "hits=$hits missed=$missed verifyFail=$verifyFail masked=$masked lastMiss=$lastMiss"
}
