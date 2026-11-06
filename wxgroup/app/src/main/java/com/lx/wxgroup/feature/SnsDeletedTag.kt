package com.lx.wxgroup.feature

import android.content.Context
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.lx.wxgroup.R
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * R42 · 朋友圈动态「已删除」灰标（**line 样式**）。
 *
 * ## 背景：库里没有「已删除」现成信号
 * 三层写时拦截后，微信自己不知道删除失败（键被摘掉但返回值正常），所以 `localFlag` /
 * `sourceType` 都没有「这条被删了」的标记。⇒ 灰标必须**我们自己记**：
 * 在 A 层真摘键、且这次写丢了时间线位（bit1）的那一刻，把 `snsId` 落进模块自己的 `sns_deleted` 表。
 *
 * ## 职责分工（只做「记录 + 渲染」，不碰既有三层拦截）
 * - [onBlocked]：由 [SnsGuard.applyBlock] 在 `Outcome.BLOCKED` 后调用，决定要不要落台账；
 * - [install]：挂钩 `WxRecyclerAdapter.onBindViewHolder` + `getData()` 映射链（落点见 KDoc）；
 * - 渲染：绑定时按 snsId 查「已删清单」，命中则在 itemView 内已有 ViewGroup 里插一行灰字。
 *
 * ## 落点（沿用 SnsTag 旧版已验证的链，见 `docs/conclusions/moments.md` §4/§5）
 * - `WxRecyclerAdapter`（`com.tencent.mm.view.recyclerview.WxRecyclerAdapter`）类名未混淆；
 * - `onBindViewHolder(holder, pos)` + `adapter.getData()`（元素 `toString()` 含
 *   `ImproveInfo(name=…, snsID='无符号十进制')`）；
 * - 子类覆写会让基类钩子变死钩 ⇒ 补挂 `WxRecyclerView.setAdapter` 抓具体 adapter 类；
 * - `position` 与 `getData()` 下标可能错位（封面头）⇒ 用作者名在 ±2 窗口唯一对账（[resolve]）。
 *
 * ## ⛔ 渲染铁律（沿用 moments.md §5 的教训）
 * - `holder → itemView` **必须排除 RecyclerView**（holder 持有 rv 引用、面积最大会被选中）；
 * - 「插一行」走 itemView 内**已有的 ViewGroup**（LinearLayout），不在 RecyclerView 层面操作；
 * - 标记 View 要能被回收复用逻辑正确处理：bind 时先移除上一次加的标记（[removeMark]），
 *   避免复用残留串味。
 *
 * ## 样式只实现 line
 * 探针 `op=snsdel sub=style --es val on|off`（兼容旧写法 `line` = on）；`off` 是关闭对照，
 * `on` 是唯一实现样式（line）。持久化存布尔串 `"true"`/`"false"`（`PrefKind.SWITCH`）。
 */
object SnsDeletedTag {

    /** 微信 RecyclerView 适配器基类（未混淆） */
    private const val ADAPTER_CLASS = "com.tencent.mm.view.recyclerview.WxRecyclerAdapter"

    /** 微信自研列表基类：抓 setAdapter 拿「具体 adapter 类」 */
    private const val WX_RV_CLASS = "com.tencent.mm.view.recyclerview.WxRecyclerView"

    /** 只给朋友圈的 adapter 挂钩，别把微信其它 RecyclerView 全挂上 */
    private const val SNS_PREFIX = "com.tencent.mm.plugin.sns."

    /** 配置 TTL：style 每 30s 重读一次 Kv */
    private const val CFG_TTL_MS = 30_000L

    /** 已删清单缓存 TTL（gen 失效是主判据，TTL 只是兜底） */
    private const val SET_TTL_MS = 5_000L

    /** 灰标文案与视觉规格（line 唯一实现样式） */
    private const val MARK_TEXT = "该动态已被作者删除"

    /** 微信次级灰（#9B9B9B 附近）。⛔ 按主题动态取色未实现，先用固定值，真机可换。 */
    private const val MARK_COLOR = 0xFF9B9B9B.toInt()

    /** 标记字号：正文约 15sp ⇒ 标记 12sp（小 3sp，符合「小 1~2sp」的视觉量级） */
    private const val MARK_SP = 12f

    /** 标记与正文之间的间距（dp） */
    private const val MARK_TOP_DP = 5

    /**
     * 标记左缩进（dp）。
     *
     * 真机实测（1440×3200 / density 3.5）：item 内正文与昵称的左边界都在 x=251px，
     * 换算 251 / 3.5 ≈ 71.7dp。原先取 56dp 会落在 x≈196，比正文列左出 ~55px。
     */
    private const val MARK_LEFT_DP = 72

    /**
     * 标记 View 的识别 tag key。
     *
     * ⛔ 别换回 `View.generateViewId()`：它只产出 `1..0xFFFFFF`，而
     * `View.setTag(int, Object)` 对 `key >>> 24 < 2` 直接抛
     * `IllegalArgumentException("The key must be an application-specific resource id.")`。
     * 真机症状是 bind 抛异常、灰标一条都插不进去（2026-11-06 实测）。
     * 见 `res/values/ids.xml` 同一条提醒。
     */
    private val MARK_KEY: Int = R.id.wxgroup_sns_mark

    /** 标记 View 的识别 tag value（String，与微信自己的 tag 值不冲突） */
    private const val MARK_MAGIC = "wxgroup.sns.deleted.line"

    /** 日志限流：热路径里 LogX 是主线程同步文件 IO，只留前几条 */
    private const val LOG_MAX = 5

    /** 解析失败原因最多打几条（见 [missLogged]） */
    private const val MISS_LOG_MAX = 6

    /** 判定「item 根末尾那条 View 是分隔线」的高度上限（dp） */
    private const val MARK_DIVIDER_MAX_DP = 2

    @Volatile
    private var style = SnsDeletedTagLogic.Style.OFF

    @Volatile
    private var cfgAt = 0L

    /** 已删清单（snsId 集合）+ gen 版本号失效（照抄 VoiceGuard 模式） */
    @Volatile
    private var delSet: Set<Long> = emptySet()

    @Volatile
    private var setAt = 0L

    @Volatile
    private var cachedGen = 0L

    private val gen = AtomicLong(0L)

    /** 当前登录 wxid（读 `com.tencent.mm_preferences` 的 `login_weixin_username`） */
    @Volatile
    private var self: String? = null

    /** self wxid 是从哪个 pref 文件/键取到的（`sub=who` 诊断用） */
    @Volatile
    private var selfSource: String = "?"

    private val miscLogged = AtomicBoolean(false)
    private val errLogged = AtomicBoolean(false)

    /** 限流：ensureMark 选错 View（非 ViewGroup）时只打一条 */
    private val markTargetLogged = AtomicBoolean(false)

    /** 限流：找不到正文 TextView、退回根末尾时只打一条 */
    private val markFallbackLogged = AtomicBoolean(false)

    /** 限流：addView 抛异常时只打一条 */
    private val markAddFailedLogged = AtomicBoolean(false)

    private fun logOnce(msg: String) {
        if (miscLogged.compareAndSet(false, true)) LogX.i(msg)
    }

    private fun warnOnce(msg: String) {
        if (errLogged.compareAndSet(false, true)) LogX.w(msg)
    }

    /**
     * item 根 View → 绑定状态（弱引用 key ⇒ 无泄漏）。
     *
     * B1（冷启首屏补标）起从 `WeakHashMap<View, Long>` 扩展为 `WeakHashMap<View, BoundEntry>`：
     * 解析成功时存 snsId（resolved=true）；解析失败（含冷启首屏同名相邻保守放弃）时存用于重扫的
     * (adapter, pos)（resolved=false）。校准成功后 [resweep] 据此重新 resolve 补标。
     */
    private class BoundEntry(
        /** 仅 resolved=false（pending）时非空，供 resweep 重新 resolve */
        val adapter: Any?,
        /** 仅 resolved=false（pending）时有效 */
        val pos: Int,
        /** 仅 resolved=true 时有效（snsId 恒非 0，见 parseSnsId 的 15~20 位下限） */
        val snsId: Long,
        val resolved: Boolean,
    )

    private val bound: MutableMap<View, BoundEntry> =
        Collections.synchronizedMap(WeakHashMap<View, BoundEntry>())

    /** resweep 防重入：resolve 校准成功会回调 resweep，而 resweep 内部又调 resolve ⇒ 防递归 */
    private var resweeping = false

    /** 自诊断计数（binds>0 才说明钩子活着） */
    @Volatile private var binds = 0
    @Volatile private var snsBinds = 0
    @Volatile private var resolved = 0
    @Volatile private var missed = 0
    /** 判定「应显示灰标」的次数（匹配命中，含没真正加上去的） */
    @Volatile private var match = 0
    /** 真正把标记 View 加到视图树上的次数（addView 成功才 +1） */
    @Volatile private var hits = 0
    @Volatile private var lastMiss = ""

    /**
     * 解析失败原因只留最近一条（`lastMiss`）→ 只能看到「最后那次为什么失败」。
     * 多打前几条不同原因，才分得清是「数据没就绪」还是「解析不出 snsId」。
     */
    private var missLogged = 0

    private val hookedMethods: MutableSet<String> = Collections.synchronizedSet(HashSet())

    // ------------------------------------------------------------------ 记录（由 SnsGuard 调用）

    /**
     * 落台账的后台单线程 executor（daemon，命名 `wxgroup-snsdel`）。
     *
     * ⚠️ [onBlocked] 是被微信自己的写库线程（`updateWithOnConflict` 的 before 钩子）同步调用的，
     * 磁盘 IO 必须挪出那条线程 —— 所以判定在调用线程做完（纯函数 + self 有缓存），
     * 只把「写库 + gen 自增 + 日志」post 到这里。
     */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wxgroup-snsdel").apply { isDaemon = true }
    }

    /**
     * 写时拦截真摘键后，由 [SnsGuard.applyBlock] 调用。
     *
     * 判据（纯函数 [SnsDeletedTagLogic.shouldRecord]）：
     * 旧值含 bit1、这次写不含 bit1、且作者不是自己 ⇒ 落 `sns_deleted`（同一 snsId 只保留一条）。
     *
     * ⚠️ 取不到 self wxid 时 `shouldRecord` 会**照记不误**（降级），但 [stat] 里会明示
     * `self=? (未知，未排除自己)` —— 宁可明说失效，不让用户以为排除了。
     *
     * ⚠️ 判定在调用线程（避免异步读 `bits` 的时序），但 `recordSnsDeleted` + `gen.incrementAndGet()`
     * + 日志整段 post 到 [writer] 后台线程：`gen` 自增必须发生在写库成功之后、同一条后台任务里，
     * 不能挪到前面，否则缓存版本号会领先于真实落库。
     */
    fun onBlocked(snsId: Long, bits: SnsBlockProbe.Bits?, want: Int) {
        if (snsId == 0L) return
        val (me, src) = selfWxid()
        if (!SnsDeletedTagLogic.shouldRecord(bits?.src, want, bits?.userName, me)) return
        writer.execute {
            val db = GroupDb.current()
            if (db == null) {
                warnOnce("SNSDEL: GroupDb not open, cannot record snsId=$snsId")
                return@execute
            }
            if (db.recordSnsDeleted(snsId)) {
                gen.incrementAndGet()
                LogX.i(
                    "SNSDEL record snsId=$snsId author=${bits?.userName ?: "?"} " +
                        "want=$want src=${bits?.src ?: "?"} self=${me ?: "?"} src=$src"
                )
            }
        }
    }

    // ------------------------------------------------------------------ self wxid

    /**
     * 读当前登录 wxid 的候选（按可靠性排序，取第一个非空）。
     *
     * ⚠️ 主路径是标准 Android API（`getSharedPreferences`），不碰任何未取证的微信类名。
     * 全部取不到 ⇒ 返回 `(null, "?")`，调用方**必须**在 `stat` 里明示，绝不静默。
     */
    private val SELF_CANDIDATES: List<Pair<String, String>> = listOf(
        "com.tencent.mm_preferences" to "login_weixin_username",
        "com.tencent.mm_preferences" to "login_user_name",
        "system_config_prefs" to "login_weixin_username",
        "com.tencent.mm" to "login_weixin_username",
    )

    private fun selfWxid(): Pair<String?, String> {
        self?.let { return it to selfSource }
        val ctx = HostContext.app()
        if (ctx == null) {
            selfSource = "no-context"
            return null to selfSource
        }
        for ((file, key) in SELF_CANDIDATES) {
            val v = runCatching {
                ctx.getSharedPreferences(file, Context.MODE_PRIVATE).getString(key, null)
            }.getOrNull()
            if (!v.isNullOrBlank()) {
                self = v
                selfSource = "$file/$key"
                return v to selfSource
            }
        }
        selfSource = "?"
        LogX.w("SNSDEL: self wxid unknown (未排除自己) —— 探针 sub=who 可诊断")
        return null to selfSource
    }

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        var hooked = 0
        runCatching { XposedHelpers.findClass(ADAPTER_CLASS, cl) }
            .onSuccess { hooked += hookAdapterClass(it) }
            .onFailure { LogX.w("SNSDEL: $ADAPTER_CLASS not found") }
        hooked += hookSetAdapter(cl)
        if (hooked == 0) LogX.w("SNSDEL: no onBindViewHolder hook installed, tag disabled")
        // 预热 self wxid：`getSharedPreferences(...).getString(...)` 首次调用会同步加载整个 xml，
        // 若不在这里先读一次，这笔 IO 会落在用户某次删除动态的瞬间 —— 而那次正处在微信自己的
        // 写库线程（`updateWithOnConflict` 的 before 钩子栈）上。install 跑在模块加载线程，
        // 在这里做一次 SharedPreferences 读是可接受的（与 `GroupDb.open()` 同一时机等级）。
        selfWxid()
        runCatching { refresh() }
        LogX.i("SNSDEL installed hooks=$hooked style=${style.name.lowercase()}")
    }

    /** 给一个 adapter 类挂它所有 `onBindViewHolder(?, int)` 具体重载；返回新挂条数 */
    private fun hookAdapterClass(c: Class<*>): Int {
        var n = 0
        val ms = runCatching { c.methods }.getOrNull() ?: return 0
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
        if (n > 0) LogX.i("SNSDEL hooked adapter=${c.name} +$n")
        return n
    }

    /** 子类一旦覆写 onBindViewHolder，挂基类就是死钩 ⇒ 抓 setAdapter 补挂具体类 */
    private fun hookSetAdapter(cl: ClassLoader): Int {
        val rv = runCatching { XposedHelpers.findClass(WX_RV_CLASS, cl) }.getOrElse {
            LogX.w("SNSDEL: $WX_RV_CLASS not found, concrete-adapter hook skipped")
            return 0
        }
        val m = findSetAdapter(rv) ?: run {
            LogX.w("SNSDEL: setAdapter not found, concrete-adapter hook skipped")
            return 0
        }
        return runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val a = param.args?.getOrNull(0) ?: return
                        if (!a.javaClass.name.startsWith(SNS_PREFIX)) return
                        hookAdapterClass(a.javaClass)
                    }.onFailure { LogX.w("SNSDEL setAdapter hook: ${it.javaClass.simpleName}") }
                }
            })
            1
        }.getOrDefault(0)
    }

    private fun findSetAdapter(rv: Class<*>): Method? {
        var c: Class<*>? = rv
        var up = 0
        while (c != null && up < 6) {
            val f = c.declaredMethods.firstOrNull { it.name == "setAdapter" && it.parameterTypes.size == 1 }
            if (f != null) return f.apply { isAccessible = true }
            c = c.superclass
            up++
        }
        return null
    }

    // ------------------------------------------------------------------ 绑定钩子

    private val bindHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            // binds 在开关判断之前自增 —— 钩子是不是死的，全看它
            binds++
            try {
                onBind(param)
            } catch (t: Throwable) {
                // 吞掉 + 限流告警：绝不 rethrow（rethrow = 崩微信）
                // ⚠️ 只报类名不够 —— 2026-11-06 就是靠「IllegalArgumentException」这一句
                //    反推了半小时才定位到 setTag 的 key 契约。带上**首个栈帧**直接给出出事的行。
                val top = t.stackTrace.firstOrNull()
                warnOnce(
                    "SNSDEL bind err: ${t.javaClass.simpleName}" +
                        (top?.let { " @ ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "") +
                        " msg=${t.message?.take(80)}"
                )
            }
        }
    }

    private fun onBind(param: XC_MethodHook.MethodHookParam) {
        val adapter = param.thisObject ?: return
        // ① 钩子挂在基类上，全微信列表都进这里 → 只认朋友圈 adapter
        if (!adapter.javaClass.name.startsWith(SNS_PREFIX)) return
        val holder = param.args?.getOrNull(0) ?: return
        val pos = param.args?.getOrNull(1) as? Int ?: return
        snsBinds++
        // ② 开关闸门在最前面：关着时只清残留标记，不解析
        if (!on()) {
            runCatching { itemViewOf(holder)?.let { removeMark(it) } }
            return
        }
        val view = itemViewOf(holder)
        if (view == null) {
            missed++
            lastMiss = "noitemview(${holder.javaClass.simpleName})"
            return
        }
        val info = resolve(adapter, pos, view)
        if (info == null) {
            missed++
            if (missLogged < MISS_LOG_MAX) {
                missLogged++
                LogX.i("SNSDEL miss#$missLogged $lastMiss")
            }
            // B1：不再 bound.remove(view)。解析失败（含冷启首屏尚未校准、同名相邻保守放弃）时
            // 把该 view 记为 pending（带 adapter 与 pos），等错位常数校准成功后 resweep 重新 resolve 补标。
            // 覆盖写 bound：同一 view 若之前成功绑定过，旧 snsId 已随本次绑定失效。
            bound[view] = BoundEntry(adapter, pos, 0L, resolved = false)
            // 解析失败也是复用负路径：走统一判据得 HIDE，必然 removeMark 清掉复用 View 上的残留灰标。
            applyMark(view, 0L, resolved = false)
            return
        }
        resolved++
        bound[view] = BoundEntry(adapter, pos, info.snsId, resolved = true)
        applyMark(view, info.snsId)
    }

    // ------------------------------------------------------------------ holder → itemView

    private val viewFieldCache = Collections.synchronizedMap(HashMap<String, Field?>())

    /**
     * holder → item 根 View。⛔ **必须排除 RecyclerView 本身**（holder 持有 rv 引用、
     * 面积最大会被选中 ⇒ 所有 holder 指向同一 View、标记加到列表容器上）。
     * 优先选声明在 `androidx.recyclerview.widget.*`（ViewHolder 本体）上的 View 字段。
     */
    private fun itemViewOf(holder: Any): View? {
        val key = holder.javaClass.name
        if (viewFieldCache.containsKey(key)) {
            val f = viewFieldCache[key] ?: return null
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
        val descriptors = cands.map { (f, v) ->
            SnsDeletedTagLogic.ItemViewCandidate(
                declaredInAndroidX = f.declaringClass.name.startsWith("androidx.recyclerview.widget."),
                parentContainsRecyclerView = v.parent?.javaClass?.name.orEmpty().contains("RecyclerView"),
                isViewGroup = v is ViewGroup,
                area = v.width.toLong() * v.height,
            )
        }
        val pickIdx = SnsDeletedTagLogic.pickItemViewIndex(descriptors)
        val best = if (pickIdx >= 0) cands[pickIdx].second else null
        viewFieldCache[key] = cands.firstOrNull { it.second === best }?.first
        if (best == null) {
            logOnce("SNSDEL: no View field on holder ${holder.javaClass.name}")
        } else {
            logOnce(
                "SNSDEL holder=${holder.javaClass.simpleName} pick=${best.javaClass.simpleName}" +
                    " cands=[" + cands.take(6).joinToString(";") {
                        "${it.first.name}:${it.second.javaClass.simpleName}"
                    } + "]"
            )
        }
        return best
    }

    private fun isRv(v: View): Boolean = v.javaClass.name.contains("RecyclerView")

    // ------------------------------------------------------------------ position → item

    private val dataMethodCache = Collections.synchronizedMap(HashMap<String, Method?>())

    /** adapter → 数据列表。只认 `getData()`，不做「任意返回 List 的无参方法」兜底。 */
    private fun dataList(adapter: Any): List<*>? {
        val key = adapter.javaClass.name
        if (!dataMethodCache.containsKey(key)) {
            val m = adapter.javaClass.methods.firstOrNull {
                it.name == "getData" && it.parameterTypes.isEmpty() &&
                    List::class.java.isAssignableFrom(it.returnType)
            }
            if (m == null) LogX.w("SNSDEL: ${adapter.javaClass.name} has no getData()")
            m?.isAccessible = true
            dataMethodCache[key] = m
        }
        val m = dataMethodCache[key] ?: return null
        return runCatching { m.invoke(adapter) as? List<*> }.getOrNull()
    }

    /**
     * adapter 类 → 已校准的「封面头导致的全局固定错位常数」及其可信度。
     *
     * ⛔ key 只按 adapter 类、**不含 list.size**：错位是封面头占位造成的常数，与列表长度无关。
     * 旧版用 `"类名#list.size"`，新发一条动态使 size 变化就把校准值冲掉，逼得每次绑定都重新
     * 「0 优先」猜偏移，正是「相邻同名串位」误报的放大器（见 docs/_r42_deltag_false_positive.md A5）。
     *
     * 值从裸 `Int` 升级为 [SnsDeletedTagLogic.Learned]：`offset` 是错位常数（**0 是合法值**，N6），
     * `headerSig` 是采样时的头部签名（头部项数 H），`votes` 是无歧义采样票数。头部构成变化 ⇒
     * `headerSig` 变化 ⇒ [SnsDeletedTagLogic.reconcile] 判失效 ⇒ 旧常数不复用（N1）。
     *
     * ⛔ **该 map 里的条目只增改、不删除**：`newStored == null` 只表示「本次不更新」，绝不 remove。
     * 陈旧条目永不误用 —— [SnsDeletedTagLogic.reconcile] 已用 `headerSig` 把关（签名不符即拒）；
     * 签名回到旧值时期旧常数本来就重新有效。删除只会制造「滚回顶部再滚回来」时校准被物理清除、
     * 灰标先消失再补回的可见闪烁（B1）。
     *
     * ⛔ 该 map 的「读-算-写」必须整段在 `synchronized(offsets)` 内（见 [resolve]），
     * 否则 `Collections.synchronizedMap` 只保证单次 get/put 原子、不保证 check-then-act 原子。
     * [firstDataPos] 与 `offsets` 用**同一把** `synchronized(offsets)` 锁保护。
     */
    private val offsets = Collections.synchronizedMap(HashMap<String, SnsDeletedTagLogic.Learned>())

    /**
     * adapter 类 → 头部占位签名观测值（首个数据项 adapter position = 头部项数 H = -O）。
     * `-1` = 尚未观测到任何数据项（签名未知，此时任何偏移都不被采信）。
     *
     * ⛔ **必须按 adapter 类分键**（与 [offsets] 同 key、同一把 `synchronized(offsets)` 锁保护）：
     * [install] 挂在通用 `WxRecyclerAdapter.onBindViewHolder` 上，聊天列表等其它 adapter 也会进来；
     * 它们的 `getData()` 不含 SnsInfo ⇒ `passing` 恒空 ⇒ 被当成「头部」，若签名是全局单例就会被
     * 改坏 ⇒ `consistent(-1, 2)=false` ⇒ 正确采样全被 [SnsDeletedTagLogic.vote] 拒掉 ⇒ 整个会话
     * 永久漏标（B2）。分键后各 adapter 的签名互不污染。
     */
    private val firstDataPos = HashMap<String, Int>()

    /**
     * pos → 数据元素。封面头让 pos 与 getData() 下标存在【全局固定错位】O。
     *
     * 旧逻辑按 `0,1,-1,2,-2` 取第一个通过 [SnsDeletedTagLogic.nameOk] 的偏移；但 nameOk 只认
     * 作者名，同一好友连续两条动态时 `d=0`（错位后恰指向相邻那条）与 `d=O`（本条自己）都通过
     * ⇒ 新发未删动态被锚到相邻「已删除」动态的 snsId ⇒ 误挂灰标（2026-11-07 真机）。
     *
     * 新逻辑：收集窗口里通过护栏的偏移，交 [SnsDeletedTagLogic.calibrate] 决策 ——
     * 已校准且头部签名一致时**沿用错位常数**（抵御 `d=0` 假命中）；头部签名变化（N1）或未校准
     * 且窗口歧义时**保守返回 null**（不打标）。nameOk 仍是防「标到别人动态上」的护栏。
     */
    private fun resolve(adapter: Any, pos: Int, itemView: View): SnsDeletedTagLogic.Info? {
        val list = dataList(adapter) ?: run {
            lastMiss = "nodata(${adapter.javaClass.simpleName})"
            return null
        }
        if (list.isEmpty()) {
            lastMiss = "empty($pos)"
            return null
        }
        val texts = textsOf(itemView)
        val key = adapter.javaClass.name
        val order = intArrayOf(0, 1, -1, 2, -2)
        val passing = ArrayList<Int>(order.size)
        val byOffset = HashMap<Int, SnsDeletedTagLogic.Info>(order.size)
        for (d in order) {
            val info = infoAt(list, pos + d) ?: continue
            byOffset[d] = info
            if (SnsDeletedTagLogic.nameOk(info.name, texts)) passing += d
        }
        // ⛔ 原子「读-算-写」：offsets 是 synchronizedMap，但 check-then-act 必须整段锁住，
        // 否则 UI 线程与探针线程（setStyle/purge → resweep → resolve）并发会丢失更新/读脏值。
        // 头部签名观测（firstDataPos，按 adapter key 分键）与 offsets 同一把锁保护（B2）。
        var headerSig = -1
        val cal = synchronized(offsets) {
            headerSig = SnsDeletedTagLogic.observeHeader(
                firstDataPos[key] ?: -1, pos, passing.isNotEmpty(),
            )
            firstDataPos[key] = headerSig
            val stored = offsets[key]
            val c = SnsDeletedTagLogic.calibrate(passing, stored, headerSig)
            // ⛔ newStored==null 只许「不更新」，不许 remove：offsets 条目只增改、不删除（见 KDoc）。
            //    失效靠 reconcile 的 headerSig 把关（签名不符即拒），删除只会制造灰标闪烁（B1）。
            if (c.newStored != null) offsets[key] = c.newStored
            c
        }
        if (cal.d == null) {
            lastMiss = "ambig(pos=$pos/${list.size} passing=$passing headerSig=$headerSig " +
                "texts=[${texts.take(3).joinToString("|") { it.take(10) }}]"
            return null
        }
        // B1：错位常数首次可用 ⇒ 本轮之前被保守放弃（HIDE、pending）的同屏 item 有救，重扫补标。
        // 触发与 d 解耦、由 cal.resweep 判据决定（0 也触发，N6）。防重入由 resweep 内 resweeping 布尔兜底。
        if (cal.resweep) {
            resweep()
        }
        logOnce("SNSDEL offset=${cal.d} (pos=$pos/${list.size} headerSig=$headerSig passing=$passing)")
        return byOffset[cal.d]
    }

    private fun infoAt(list: List<*>, p: Int): SnsDeletedTagLogic.Info? {
        if (p < 0 || p >= list.size) return null
        val item = list[p] ?: return null
        return infoOf(item) ?: run {
            lastMiss = "parsefail(${item.javaClass.simpleName})"
            null
        }
    }

    private class Path(val field: Field?)

    private val pathCache = Collections.synchronizedMap(HashMap<String, Path?>())

    private fun infoOf(item: Any): SnsDeletedTagLogic.Info? {
        val key = item.javaClass.name
        if (pathCache.containsKey(key)) {
            val p = pathCache[key] ?: return null
            val target = if (p.field == null) item
            else runCatching { p.field.get(item) }.getOrNull() ?: return null
            return SnsDeletedTagLogic.parseInfo(runCatching { target.toString() }.getOrNull())
        }
        SnsDeletedTagLogic.parseInfo(runCatching { item.toString() }.getOrNull())?.let {
            pathCache[key] = Path(null)
            return it
        }
        var c: Class<*>? = item.javaClass
        var up = 0
        while (c != null && up < 4) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
                val v = runCatching { f.isAccessible = true; f.get(item) }.getOrNull() ?: continue
                if (isPlain(v)) continue
                val info = SnsDeletedTagLogic.parseInfo(runCatching { v.toString() }.getOrNull()) ?: continue
                pathCache[key] = Path(f)
                return info
            }
            c = c.superclass
            up++
        }
        pathCache[key] = null
        return null
    }

    private fun isPlain(v: Any): Boolean {
        if (v is String || v is Number || v is Boolean || v is Char || v is ByteArray) return true
        if (v is Iterable<*> || v is Map<*, *>) return true
        val n = v.javaClass.name
        return n.startsWith("java.") || n.startsWith("android.") || n.startsWith("kotlin.") ||
            n.startsWith("dalvik.")
    }

    /** item 子树里的可见非空文字（跳过我们自己加的标记），用来跟 ImproveInfo.name 对账。 */
    private fun textsOf(root: View, max: Int = 20): List<String> {
        val out = ArrayList<String>(max)
        fun walk(v: View, d: Int) {
            if (out.size >= max || d > 8) return
            if (v is TextView) {
                if (runCatching { v.getTag(MARK_KEY) }.getOrNull() == MARK_MAGIC) return
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

    // ------------------------------------------------------------------ 渲染

    /** 开关语义：只看 style（line=开，off=关）。 */
    private fun on(): Boolean {
        if (SystemClock.elapsedRealtime() - cfgAt > CFG_TTL_MS) refresh()
        return style == SnsDeletedTagLogic.Style.LINE
    }

    fun refresh() {
        style = SnsDeletedTagLogic.parseStyle(Kv.getString(Keys.SNSDEL_STYLE))
        cfgAt = SystemClock.elapsedRealtime()
    }

    private fun deleted(): Set<Long> {
        val now = SystemClock.elapsedRealtime()
        val g = gen.get()
        if (SnsDeletedTagLogic.cacheUsable(now, setAt, delSet.size, SET_TTL_MS, cachedGen, g)) {
            return delSet
        }
        val s = loadIds()
        delSet = s
        cachedGen = g
        setAt = SnsDeletedTagLogic.stampAfterBuild(now, s.size, SET_TTL_MS)
        return s
    }

    private fun loadIds(): Set<Long> {
        val db = GroupDb.current() ?: return delSet
        return runCatching { db.snsDeletedIds() }
            .onFailure { LogX.w("SNSDEL ids failed: ${it.javaClass.simpleName}") }
            .getOrDefault(delSet)
    }

    /**
     * 按 [SnsDeletedTagLogic.markDecision] 的统一判据渲染：SHOW 插标，HIDE 必清标。
     * `resolved=false`（解析失败）时也走这里 ⇒ HIDE ⇒ removeMark，保证复用负路径摘掉遗留灰标；
     * SHOW 走 ensureMark（其开头先 removeMark 再插）⇒ 重复绑定同一条不重复插标。
     */
    private fun applyMark(view: View, snsId: Long, resolved: Boolean = true) {
        val inDeleted = snsId != 0L && snsId in deleted()
        when (SnsDeletedTagLogic.markDecision(
            styleOn = style == SnsDeletedTagLogic.Style.LINE,
            resolved = resolved,
            snsId = snsId,
            inDeleted = inDeleted,
        )) {
            SnsDeletedTagLogic.MarkAction.SHOW -> {
                match++
                ensureMark(view)
            }
            SnsDeletedTagLogic.MarkAction.HIDE -> removeMark(view)
        }
    }

    private fun ensureMark(view: View) {
        val root = view as? ViewGroup ?: run {
            // ⛔ 以前这里静默 return，hits 已在 applyMark 里加过 ⇒ 计数看着正常、实际零生效。
            //    现在限流打一条（含选错 View 的类型），下次一眼看出「又选错 View」。
            if (markTargetLogged.compareAndSet(false, true)) {
                LogX.w("SNSDEL: ensureMark got ${view.javaClass.simpleName} (not ViewGroup), mark NOT added")
            }
            return
        }
        // 复用清理：先移除上一次加的标记（标记现在插在嵌套的内容容器里，要递归找）
        removeMark(root)
        val tv = TextView(root.context)
        tv.text = MARK_TEXT
        tv.setTextColor(MARK_COLOR)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, MARK_SP)
        val point = findBodyInsertPoint(root)
        val (parent, idx) = if (point != null) {
            point
        } else {
            // 找不到正文 TextView ⇒ 退回加到根末尾（保持旧行为），限流说明走了兜底
            if (markFallbackLogged.compareAndSet(false, true)) {
                LogX.w("SNSDEL: body text not found in ${root.javaClass.simpleName}, fallback to root end")
            }
            root to tailIndex(root)
        }
        // hits 只统计「真正 addView 成功」；addView 抛异常则不计，match 与 hits 的差量会暴露它。
        // ⚠️ setTag 也放进同一个 runCatching：它若失败（key 非法等），View 就既插不进也别想被
        //    removeMark 找到 ⇒ 重绑时越积越多。宁可整条不插，也不要插一条「无主」的。
        runCatching {
            tv.setTag(MARK_KEY, MARK_MAGIC)
            parent.addView(tv, idx, markLayoutParams(parent))
        }
            .onSuccess { hits++ }
            .onFailure {
                if (markAddFailedLogged.compareAndSet(false, true)) {
                    LogX.w("SNSDEL: addView failed on ${parent.javaClass.simpleName}: ${it.javaClass.simpleName}")
                }
            }
    }

    /**
     * item 根末尾的插入下标：**跳过末尾那条 1px 分隔线**。
     *
     * 真机结构（微信 8.0.48，`op=snsv sub=tree` 实测）里 item 根的子节点顺序是
     * `[ImproveItemHeader, 内容 RelativeLayout, View(1440×1 分隔线)]`。
     * 直接按 `childCount` 插 ⇒ 标记落在分隔线**之后**（卡片外），离下一条动态只剩 8px，
     * 视觉上像是挂在下一头上（2026-11-06 截图确认）。插到分隔线之前才留在本条卡片内。
     */
    private fun tailIndex(root: ViewGroup): Int {
        if (root.childCount == 0) return 0
        val last = runCatching { root.getChildAt(root.childCount - 1) }.getOrNull()
            ?: return root.childCount
        val isDivider = last.javaClass.name == "android.view.View" &&
            runCatching { last.height }.getOrDefault(0) <= dp(MARK_DIVIDER_MAX_DP.toFloat())
        return if (isDivider) root.childCount - 1 else root.childCount
    }

    private fun removeMark(view: View) {
        val root = view as? ViewGroup ?: return
        // ⛔ 标记插在正文父容器里（嵌套），不能只扫 root 的直接子节点，递归整棵子树
        fun walk(v: ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                val c = runCatching { v.getChildAt(i) }.getOrNull() ?: continue
                if (runCatching { c.getTag(MARK_KEY) }.getOrNull() == MARK_MAGIC) {
                    runCatching { v.removeViewAt(i) }
                    continue
                }
                if (c is ViewGroup) walk(c)
            }
        }
        walk(root)
    }

    /**
     * 在 itemView 子树里找「正文 TextView」的插入点：返回 (父容器, 插入下标)，找不到 null。
     * 筛选规则抽在 [SnsDeletedTagLogic.bodyTextPath]（纯函数，可单测）。
     */
    private fun findBodyInsertPoint(root: ViewGroup): Pair<ViewGroup, Int>? {
        val path = SnsDeletedTagLogic.bodyTextPath(toSnsNode(root)) ?: return null
        var parent: ViewGroup = root
        // 路径最后一位是正文文本节点在父容器里的下标；前面的位逐层下钻到父容器
        for (i in 0 until path.size - 1) {
            val next = runCatching { parent.getChildAt(path[i]) }.getOrNull() as? ViewGroup ?: return null
            parent = next
        }
        return parent to (path.last() + 1)
    }

    /** 把真实 View 树映射成抽象树（结构与真实树逐下标一致，供 [SnsDeletedTagLogic.bodyTextPath] 用） */
    private fun toSnsNode(v: View): SnsDeletedTagLogic.SnsNode {
        if (v is TextView) {
            return SnsDeletedTagLogic.SnsNode(v.text?.toString() ?: "")
        }
        if (v is ViewGroup) {
            val kids = ArrayList<SnsDeletedTagLogic.SnsNode>(v.childCount)
            for (i in 0 until v.childCount) {
                val c = runCatching { v.getChildAt(i) }.getOrNull()
                kids += if (c == null) SnsDeletedTagLogic.SnsNode(null) else toSnsNode(c)
            }
            return SnsDeletedTagLogic.SnsNode(null, kids)
        }
        return SnsDeletedTagLogic.SnsNode(null)
    }

    @Volatile
    private var density = 0f

    private fun dp(v: Float): Int {
        val d = if (density > 0f) density else {
            density = HostContext.app()?.resources?.displayMetrics?.density ?: 3f
            density
        }
        return (v * d + 0.5f).toInt()
    }

    private fun markLayoutParams(parent: ViewGroup): ViewGroup.LayoutParams {
        val left = dp(MARK_LEFT_DP.toFloat())
        val top = dp(MARK_TOP_DP.toFloat())
        return when (parent) {
            is LinearLayout -> LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = left; topMargin = top }
            is FrameLayout -> FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = left; topMargin = top }
            is RelativeLayout -> RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(left, top, 0, 0) }
            else -> ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /**
     * 遍历当前绑定/待补标的 itemView 重绘（改样式/清台账/校准错位常数后立刻生效，不必等下一次滚动）。
     *
     * B1：除「已成功绑定（resolved=true）的 view 用旧 snsId 重放 applyMark」外，还要重扫
     * pending（resolved=false）——用保存的 (adapter, pos) 重新 resolve；成功则 applyMark + 写 bound，
     * 失败则保持 HIDE。校准成功后借此把冷启首屏被保守放弃的真·被删动态补上灰标。
     */
    private fun resweep() {
        if (resweeping) return
        resweeping = true
        try {
            val snap = snapshot()
            for ((v, entry) in snap) {
                if (!v.isAttachedToWindow) {
                    synchronized(bound) { bound.remove(v) }
                    continue
                }
                if (entry.resolved) {
                    // 已成功绑定：仍用旧 snsId 重放，不重新 resolve，别扩大改动面
                    runCatching { applyMark(v, entry.snsId) }
                    continue
                }
                val adapter = entry.adapter ?: continue
                val info = runCatching { resolve(adapter, entry.pos, v) }.getOrNull()
                if (info == null) {
                    // 仍无法锚定：保持 HIDE（残留已在上次 applyMark 清过，这里再确保一次）
                    runCatching { applyMark(v, 0L, resolved = false) }
                    continue
                }
                bound[v] = BoundEntry(adapter, entry.pos, info.snsId, resolved = true)
                runCatching { applyMark(v, info.snsId) }
            }
        } finally {
            resweeping = false
        }
    }

    private fun snapshot(): List<Pair<View, BoundEntry>> {
        val out = ArrayList<Pair<View, BoundEntry>>()
        synchronized(bound) {
            for (e in bound.entries) {
                val k = e.key ?: continue
                out += k to e.value
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 探针

    /**
     * `op=snsdel sub=style --es val on|off`（兼容 `line` = on）。
     *
     * ⚠️ 持久化写**布尔串** `"true"`/`"false"`：`Keys.SNSDEL_STYLE` 是 `PrefKind.SWITCH`，
     * 库里必须存 `"true"`/`"false"`，否则设置页开关（`Kv.getBool`）会把 `"line"` 当 false。
     */
    fun setStyle(v: String?): String {
        val s = SnsDeletedTagLogic.parseStyle(v)
        val persisted = when (s) {
            SnsDeletedTagLogic.Style.LINE -> "true"
            SnsDeletedTagLogic.Style.OFF -> "false"
        }
        Kv.putString(Keys.SNSDEL_STYLE, persisted)
        refresh()
        resweep()
        return "SNSDEL style=${s.name.lowercase()} readback=${Kv.getString(Keys.SNSDEL_STYLE)}"
    }

    /** `op=snsdel sub=stat`：表内条数 + 最近 5 条 + 当前样式 + self 状态 */
    fun stat(): String {
        val (me, src) = selfWxid()
        val db = GroupDb.current()
        val n = db?.countSnsDeleted() ?: -1
        val recent = db?.recentSnsDeleted(5).orEmpty()
        val recentText = recent.joinToString(",") { (id, at) ->
            "${java.lang.Long.toUnsignedString(id)}@$at"
        }
        val selfText = if (me == null) "? (未知，未排除自己)" else me
        return "SNSDEL stat style=${style.name.lowercase()} rows=$n " +
            "self=$selfText src=$src recent=[$recentText] " +
            "bound=${synchronized(bound) { bound.size }} binds=$binds sns=$snsBinds " +
            "resolved=$resolved missed=$missed match=$match hits=$hits lastMiss=$lastMiss"
    }

    /** `op=snsdel sub=who`：self 来源诊断（装包后一条命令确认取没取到） */
    fun who(): String {
        val (me, src) = selfWxid()
        return "SNSDEL who self=${me ?: "?"} src=$src"
    }

    /**
     * `op=snsdel sub=home`：**好友主页相册**（`SnsUserUI`）的列表取证。
     *
     * 为什么单独一条：那页用的是 `ListView`（`MMOverScrollView` 里），
     * 根本走不到 `WxRecyclerAdapter.bindViewHolder` ⇒ 主时间线的钩子在那页**一次都不触发**
     * （真机实测 `binds` 在进出该页时纹丝不动）。要挂新钩子，先得知道 adapter 的真名与
     * `getItem()` 返回什么（有没有 snsId）。
     */
    fun home(): List<String> {
        val out = ArrayList<String>(12)
        val act = runCatching { HostContext.activity() }.getOrNull()
            ?: return listOf("SNSHOME no activity")
        out += "SNSHOME act=${act.javaClass.name}"
        val dec = runCatching { act.window?.decorView }.getOrNull()
            ?: return out + "SNSHOME no decorView"
        val lv = findListView(dec) ?: return out + "SNSHOME no ListView"
        val ad = runCatching { lv.adapter }.getOrNull()
        out += "SNSHOME lv=${lv.javaClass.name} adapter=${ad?.javaClass?.name} count=${ad?.count}"
        // HeaderViewListAdapter 只是壳，真正干活的是被包装的那个 adapter（挂钩子要挂它）
        val wrapped = runCatching {
            val f = ad!!.javaClass.getDeclaredField("mAdapter")
            f.isAccessible = true
            f.get(ad)
        }.getOrNull()
        out += "SNSHOME wrapped=${wrapped?.javaClass?.name}"
        if (ad != null) {
            var c: Class<*>? = ad.javaClass
            var up = 0
            while (c != null && up < 4) {
                out += "SNSHOME   ^ ${c.name}"
                c = c.superclass
                up++
            }
            for (m in ad.javaClass.methods) {
                if (m.name != "getView" && m.name != "getItem" && m.name != "getItemId") continue
                out += "SNSHOME   m ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})" +
                    " -> ${m.returnType.simpleName}"
            }
            val n = runCatching { ad.count }.getOrDefault(0)
            for (i in 0 until minOf(3, n)) {
                val item = runCatching { ad.getItem(i) }.getOrNull()
                out += "SNSHOME item[$i]=${item?.javaClass?.name} :: " +
                    (item?.toString() ?: "null").take(220)
                if (item == null) continue
                // 每行的 snsId 怎么取（字段优先，其次同名字段/方法）
                val f = item.javaClass.declaredFields.firstOrNull {
                    it.name.equals("field_snsId", true) || it.name.contains("snsId", true)
                }
                if (f != null) {
                    runCatching { f.isAccessible = true }
                    out += "SNSHOME   snsId f=${f.name}(${f.type.simpleName})=" +
                        runCatching { f.get(item) }.getOrNull()
                } else {
                    val ms = item.javaClass.methods
                        .filter { it.name.contains("snsId", true) && it.parameterTypes.isEmpty() }
                        .joinToString(",") { it.name }
                    out += "SNSHOME   snsId methods=[$ms]"
                }
            }
        }
        return out
    }

    private fun findListView(v: View): android.widget.ListView? {
        if (v is android.widget.ListView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = runCatching { v.getChildAt(i) }.getOrNull() ?: continue
                findListView(c)?.let { return it }
            }
        }
        return null
    }

    /** `op=snsdel sub=purge`：清空台账（便于重测），并让当前屏幕把标记摘掉 */
    fun purge(): String {
        val db = GroupDb.current() ?: return "SNSDEL purge no-db"
        val n = db.purgeSnsDeleted()
        gen.incrementAndGet()
        resweep()
        return "SNSDEL purge removed=$n"
    }
}
