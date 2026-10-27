package com.lx.wxgroup.feature

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// 纯逻辑都在 SnsTagLogic 里（零 Android 依赖，可跑 JVM 单测），这里只是别名
private typealias ItemInfo = SnsTagLogic.Info

/**
 * 朋友圈动态「[已删除]」标签（需求 6.2 第三项）。
 *
 * ## 落点是怎么找到的（四轮真机取证，全部实测，不是猜的）
 *
 * **① tag 那条路在新版时间线上是死的。** 新版时间线是 `ImproveSnsTimelineUI`，
 * 整棵 View 树上**只有一个** `com.tencent.mm.plugin.sns.` 前缀的 tag：
 * `MaskImageView` 上的 `qh`（里面只有 `sns_table_256863` 这种表名，**没有**动态 id）。
 * 老版本 `g2.a → TimeLineObject.Id` 那条链在这里**根本不存在**；
 * 而 `View.setTag` 又是全应用热路径 —— 这条路整体放弃。
 *
 * **② 自研列表其实是标准 RecyclerView。** 实测继承链：
 * ```
 * com.tencent.mm.plugin.sns.ui.improve.view.ImproveRecyclerView
 *   → com.tencent.mm.view.recyclerview.WxRecyclerView
 *   → com.tencent.mm.view.x2c.X2CRecyclerView
 *   → androidx.recyclerview.widget.RecyclerView
 * ```
 * ⚠️ 但微信自带的是**重新打包过**的 androidx：`getChildAdapterPosition(View)` 报
 * `NoSuchMethodException`，ViewHolder 里的位置字段也全是 -1
 * —— 「child → position → data」这条现成的路走不通。
 *
 * **③ 能走通的是 adapter 的绑定回调。** `WxRecyclerAdapter` 是**没有混淆的稳定类名**；
 * `adapter.getData()`（实测）返回 `List`，元素字段 `d` 的值对象 `toString()` 直接给出
 * `ImproveInfo(name=贸易商陈健峰, snsID='15033729556253184615', rowid=256862, …)`。
 * → **onBindViewHolder 之后：holder.itemView ↔ snsID**。
 *
 * **④ `position` 与 `getData()` 的下标**存在错位可能（列表第一项是「封面头」，
 * 而 `getData()[0]` 是第一条真实动态）。所以**绝不能**直接信 `pos`：
 * 先用 item 子树里的作者文字与 `ImproveInfo.name` 对账，对不上就在 ±2 窗口里找，
 * 还找不到就**不打标**（打错标签比不打标签糟糕得多）。
 */
object SnsTag {

    /** 我们加的标记 View 的 tag，用来识别/摘除 */
    private const val MARK = "wxgroup.sns.deleted.tag"

    /** 微信的 RecyclerView 适配器基类：**没有混淆**，可以直接按名字找 */
    private const val ADAPTER_CLASS = "com.tencent.mm.view.recyclerview.WxRecyclerAdapter"

    /** 微信自研列表基类：用来抓 setAdapter 拿到「具体 adapter 类」 */
    private const val WX_RV_CLASS = "com.tencent.mm.view.recyclerview.WxRecyclerView"

    /** 只给朋友圈的 adapter 挂钩，别把微信其它 RecyclerView 全挂上 */
    private const val SNS_ADAPTER_PREFIX = "com.tencent.mm.plugin.sns."

    private const val SET_TTL_MS = 5000L
    private const val SWEEP_MS = 2000L
    private const val CFG_TTL_MS = 30000L

    /** 日志限流：热路径里 LogX 是主线程同步文件 IO，只能留前几条 */
    private const val LOG_MAX = 5

    @Volatile private var enabled = false
    @Volatile private var cfgAt = 0L
    @Volatile private var delSet: Set<Long> = emptySet()
    @Volatile private var setAt = 0L
    @Volatile private var firstLoad = false

    private val setLoading = AtomicBoolean(false)
    private val sweepScheduled = AtomicBoolean(false)

    @Volatile private var dbWarned = false

    /** 自诊断：静默失效全靠这几个数定位（binds>0 才说明钩子活着） */
    @Volatile private var binds = 0      // 所有 adapter 的回调次数（钩子存活）
    @Volatile private var snsBinds = 0   // 朋友圈 adapter 的回调次数（限定是否生效）
    @Volatile private var hits = 0       // 命中「已删清单」的次数（清单/符号是否正确）
    @Volatile private var resolved = 0
    @Volatile private var missed = 0
    @Volatile private var lastMiss = ""

    private val miscLogged = AtomicInteger(0)
    private val errLogged = AtomicInteger(0)

    /** 限流日志：LogX 是主线程同步文件 IO，热路径上只能留前几条 */
    private fun logOnce(msg: String) {
        if (miscLogged.get() < LOG_MAX && miscLogged.incrementAndGet() <= LOG_MAX) LogX.i(msg)
    }

    /** 绑定深度守卫：只防真递归，不阻断「绑定里再绑一条」这种正常情况 */
    private val depth = ThreadLocal<Int>()

    /** item 根 View → 当前绑定的 snsId（弱引用，绝不钉住宿主 View） */
    private val bound: MutableMap<View, Long> =
        Collections.synchronizedMap(WeakHashMap<View, Long>())

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        var hooked = 0

        // ① 基类上声明的实现（子类**不覆写**时走这条）
        runCatching { XposedHelpers.findClass(ADAPTER_CLASS, cl) }
            .onSuccess { hooked += hookAdapterClass(it) }
            .onFailure { LogX.w("SNS_TAG: $ADAPTER_CLASS not found") }

        // ② ⚠️ 子类一旦覆写 onBindViewHolder，挂基类就是**死钩**（表现为静默失效，
        //    所以必须靠 setAdapter 抓到「具体 adapter 类」再补挂）
        hooked += hookSetAdapter(cl)

        if (hooked == 0) {
            LogX.w("SNS_TAG: no onBindViewHolder hook installed, tag disabled")
        }
        runCatching { refresh() }
        LogX.i("SNS_TAG installed enabled=$enabled hooks=$hooked")
    }

    private val hookedMethods: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** 给一个 adapter 类挂上它所有 `onBindViewHolder(?, int)` 的具体重载；返回新挂的条数 */
    private fun hookAdapterClass(c: Class<*>): Int {
        var n = 0
        val ms = runCatching { c.methods }.getOrNull() ?: return 0
        // 同一轮里优先非桥接方法：桥接 + 实方法都挂着会让一次绑定回调两次
        // 2 参 (holder, pos) 和 3 参 (holder, pos, payloads) 都要挂：
        // adapter 一旦只覆写 3 参版且内部不回调 2 参版，只挂 2 参就是**完全静默失效**。
        // （两边都触发时 ensure 幂等，只是计数翻倍，无害）
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
        if (n > 0) LogX.i("SNS_TAG hooked adapter=${c.name} +$n")
        return n
    }

    private fun hookSetAdapter(cl: ClassLoader): Int {
        val rv = runCatching { XposedHelpers.findClass(WX_RV_CLASS, cl) }.getOrElse {
            LogX.w("SNS_TAG: $WX_RV_CLASS not found, concrete-adapter hook skipped")
            return 0
        }
        val m = rv.methods.firstOrNull { it.name == "setAdapter" && it.parameterTypes.size == 1 }
            ?: runCatching {
                // WxRecyclerView 可能自己没声明，往父类找
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
            LogX.w("SNS_TAG: setAdapter not found, concrete-adapter hook skipped")
            return 0
        }
        return runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val a = param.args?.getOrNull(0) ?: return
                        if (!a.javaClass.name.startsWith(SNS_ADAPTER_PREFIX)) return
                        hookAdapterClass(a.javaClass)
                    }.onFailure { LogX.w("SNS_TAG setAdapter hook: ${it.javaClass.simpleName}") }
                }
            })
            1
        }.getOrDefault(0)
    }

    /**
     * 开关语义。
     *
     * ⭐ R30 拍板（2026-10-27 02:41）：**灰色小字 + 同一个开关**，复用 [Keys.ANTIREVOKE_SNS]，
     * 不新增 Key。所以这里只看防删除开关，**不再要求** `antirevoke.sns_deleted_tag`。
     * （那个 Key 保留在设置里不删，避免设置项突然消失；但已不参与判定。）
     */
    fun refresh() {
        val guard = Kv.getBool(Keys.ANTIREVOKE_SNS)
        enabled = guard
        cfgAt = SystemClock.elapsedRealtime()
        setAt = 0L // 顺手让「已删清单」也重读
        // 验证用的强制集合只能由探针设置，开关一变就清掉，绝不能让它在正常运行里残留
        forceSet = emptySet()
        // ⚠️ 不在这里清 bound：清掉的话「关掉再打开」要等下一次 bind 才回来。
        // sweep 会按当前开关状态重判（关=空清单=全摘），2 秒内自动收敛。
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
                        // ⚠️ 两处都限流：LogX 是主线程同步文件 IO，
                        // 每次 bind 都写盘的话滚动时等于给每帧加一次磁盘 IO
                        if (errLogged.get() < 3 && errLogged.incrementAndGet() <= 3) {
                            LogX.w("SNS_TAG err: ${t.javaClass.simpleName}")
                            LogX.e("SNS_TAG stack", t)
                        }
                    }
            } finally {
                if (d == 0) depth.remove() else depth.set(d)
            }
        }
    }

    private fun onBind(param: XC_MethodHook.MethodHookParam) {
        val adapter = param.thisObject ?: return

        // ① 钩子挂在基类上，微信里**所有** WxRecyclerAdapter 子类都会进这里
        //    （聊天列表 / 通讯录 / 发现页……）。不限定的话全微信列表都在跑反射解析，
        //    滚动必然掉帧。→ 只认朋友圈的 adapter。
        if (!adapter.javaClass.name.startsWith(SNS_ADAPTER_PREFIX)) return

        // ② 开关闸门必须在这里：否则关掉开关后只要滚动就照样打标，
        //    2 秒后才被 sweep 摘掉 → 「贴上→摘掉」反复闪。
        val holder = param.args?.getOrNull(0) ?: return
        val pos = param.args?.getOrNull(1) as? Int ?: return
        snsBinds++
        if (!on()) {
            // 开关关着时不再解析，但要记得把可能残留的时间标记剥掉
            // （snsId 未知 → 传 0L ⇒ isTagged 恒 false ⇒ 只还原）
            runCatching { itemViewOf(holder)?.let { SnsDeletedTag.apply(it, 0L, emptySet()) } }
            return
        }

        val itemView = itemViewOf(holder)
        if (itemView == null) {
            // 诊断盲区：原本这里直接 return，missed 不计数，
            // 「binds>0 但 resolved=0 missed=0」根本看不出是 holder 取不到 View
            missed++
            lastMiss = "noitemview(${holder.javaClass.simpleName})"
            return
        }

        // 解析不出「这是哪条动态」时，必须**顺手清掉这个 View 上的旧标记**：
        // item 是复用的，留着旧绑定会让标记跟着错位到别的动态上。
        // ⚠️ 不能靠 realTimeUpdate 自愈——它只重写「几分钟前」这类实时文案，
        //   老动态的「昨天 / 3月5日」根本不会被重写，残留标记会永久串味。
        val info = resolve(adapter, pos, itemView)
        if (info == null) {
            missed++
            bound.remove(itemView)
            runCatching { SnsDeletedTag.apply(itemView, 0L, emptySet()) }
            return
        }
        resolved++
        bound[itemView] = info.snsId
        ensureSweep()
        apply(itemView, info.snsId)
    }

    // ------------------------------------------------------------------ holder → itemView

    private val itemViewCache = Collections.synchronizedMap(HashMap<String, Field?>())

    /**
     * holder → item 根 View。
     *
     * ⚠️ **必须排除 RecyclerView 本身**。实测踩过：ViewHolder 里普遍持有指向列表的
     * rv 引用（`c3.x` 之类），而「面积最大」的兜底策略必然选中它 →
     * 所有 holder 都解析到同一个 RecyclerView → 标签被加到列表容器上，
     * item 上一个都没有，且不报错（`bound=1 / resolved=14` 就是这个症状）。
     * ⚠️ 也绝不能「取第一个 View 字段」：那通常是缓存的子 View，不是根 View。
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
            LogX.w("SNS_TAG: no View field on holder ${holder.javaClass.name}")
        } else {
            // 首次为这类 holder 选字段时留一份候选明细：选错字段是静默失效的头号原因
            logOnce("SNS_TAG holder=${holder.javaClass.simpleName} pick=${best.javaClass.simpleName}" +
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

    // ------------------------------------------------------------------ position → item

    private val dataMethodCache = Collections.synchronizedMap(HashMap<String, Method?>())

    /** adapter → 数据列表。只认 `getData()`，**不做「任意返回 List 的无参方法」兜底**
     *  （那会挑到 getHeaders/getSnapshots 之类，id 全错）*/
    private fun dataList(adapter: Any): List<*>? {
        val key = adapter.javaClass.name
        if (!dataMethodCache.containsKey(key)) {
            val m = adapter.javaClass.methods.firstOrNull {
                it.name == "getData" && it.parameterTypes.isEmpty() &&
                    List::class.java.isAssignableFrom(it.returnType)
            }
            if (m == null) {
                LogX.w("SNS_TAG: ${adapter.javaClass.name} has no getData()")
            }
            m?.isAccessible = true
            dataMethodCache[key] = m
        }
        val m = dataMethodCache[key] ?: return null
        return runCatching { m.invoke(adapter) as? List<*> }.getOrNull()
    }

    /** adapter 类 → 已校准的下标偏移（0 = 与 position 对齐） */
    private val offsets = Collections.synchronizedMap(HashMap<String, Int>())

    /**
     * pos → 数据元素。**先对账再采用**：item 子树里的作者文字必须能对上
     * `ImproveInfo.name`，否则在 ±2 窗口里找；找不到就返回 null（宁可不打标）。
     */
    private fun resolve(adapter: Any, pos: Int, itemView: View): ItemInfo? {
        val list = dataList(adapter) ?: run {
            lastMiss = "nodata(${adapter.javaClass.simpleName})"
            return null
        }
        if (list.isEmpty()) {
            lastMiss = "empty($pos)"
            return null
        }
        val texts = textsOf(itemView)
        // ⚠️ key 带 list.size 指纹：同一个 adapter 类在「时间线 / 个人相册」两种头部
        // 结构下复用会串味，下拉刷新后整体偏移也会变。size 一变就重新校准。
        val key = "${adapter.javaClass.name}#${list.size}"
        val off = offsets[key]

        // ① 已校准过才走这条捷径。首次必须过校准窗口，
        //    否则「第一个候选碰巧通过校验」会把错误偏移固化下来。
        if (off != null) {
            infoAt(list, pos + off)?.let { if (nameOk(it, texts)) return it }
            offsets.remove(key) // 校准失效（列表变了），重新来过
        }

        // ② ±2 窗口里找。**必须唯一命中**：nameOk 是双向包含，同一作者的相邻动态
        //    互相都能通过校验 → 取第一个命中会让标签打到同作者另一条动态上。
        val hits = ArrayList<Pair<Int, ItemInfo>>()
        for (d in intArrayOf(0, 1, -1, 2, -2)) {
            val info = infoAt(list, pos + d) ?: continue
            if (!nameOk(info, texts)) continue
            hits += d to info
        }
        if (hits.size == 1) {
            val (d, info) = hits[0]
            offsets[key] = d
            logOnce("SNS_TAG offset=$d (pos=$pos/${list.size})")
            return info
        }
        lastMiss = if (hits.isEmpty()) {
            "pos=$pos/${list.size} texts=[${texts.take(3).joinToString("|") { it.take(10) }}]"
        } else {
            "ambig${hits.size}(pos=$pos texts=[${texts.take(2).joinToString("|") { it.take(8) }}])"
        }
        return null
    }

    private fun infoAt(list: List<*>, p: Int): ItemInfo? {
        if (p < 0 || p >= list.size) return null
        val item = list[p] ?: return null
        return infoOf(item) ?: run {
            lastMiss = "parsefail(${item.javaClass.simpleName})"
            null
        }
    }

    /**
     * 作者名字对账。**严格**：拿不到名字 / 读不到文字 / 名字太短 → 一律不采信。
     * 打错标签（把没删的动态标成已删除）比不打标签糟糕得多，宁可让 missed 涨。
     */
    private fun nameOk(info: ItemInfo, texts: List<String>): Boolean =
        SnsTagLogic.nameOk(info.name, texts)

    /**
     * item 子树里的可见文字（跳过我们自己加的标签），用来跟 ImproveInfo.name 对账。
     * ⚠️ 上限放宽到 20 条 / 深度 8：作者名 TextView 常常埋在内容区之后，
     * 取太少会让 texts 里根本没有 name → nameOk 恒 false → 静默一个标签都不打。
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

    // ------------------------------------------------------------------ 解析 ImproveInfo


    /**
     * `ImproveInfo(name=贸易商陈健峰, snsID='15033729556253184615', …)`。
     * 这个 `toString()` 是**功能性格式**（名字/时间/rowid 都在里面，是界面上要用的），
     * 比猜混淆字段名稳。⚠️ `snsID` 允许负号 —— 有符号写法也要能吃下。
     */
    /** item 类名 → 承载 ImproveInfo 的字段（null 字段 = item 自身就是） */
    private class Path(val field: Field?)

    private val pathCache = Collections.synchronizedMap(HashMap<String, Path?>())

    private fun infoOf(item: Any): ItemInfo? {
        val key = item.javaClass.name
        if (pathCache.containsKey(key)) {
            val p = pathCache[key] ?: return null
            val target = if (p.field == null) item
            else runCatching { p.field.get(item) }.getOrNull() ?: return null
            return parseInfo(runCatching { target.toString() }.getOrNull())
        }
        parseInfo(runCatching { item.toString() }.getOrNull())?.let {
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
                val info = parseInfo(runCatching { v.toString() }.getOrNull()) ?: continue
                pathCache[key] = Path(f)
                return info
            }
            c = c.superclass
            up++
        }
        // ⚠️ **必须落负缓存**：不落的话每个 item、每次 bind 都要重跑一遍上面的
        // 「4 层超类 × 全字段反射 get」，再乘上 resolve 的 5 个候选 →
        // 单次 bind 上千次 Field.get，滚动必然掉帧甚至 ANR。
        // 不设「对象图里找 15~20 位数字串」的兜底：那种 id 拿不到 name，
        // 一定过不了 nameOk（打错标签比不打标签糟），纯属白烧 CPU。
        pathCache[key] = null
        return null
    }

    private fun parseInfo(s: String?): ItemInfo? = SnsTagLogic.parseInfo(s)

    private fun isPlain(v: Any): Boolean {
        if (v is String || v is Number || v is Boolean || v is Char || v is ByteArray) return true
        if (v is Iterable<*> || v is Map<*, *>) return true
        val n = v.javaClass.name
        return n.startsWith("java.") || n.startsWith("android.") || n.startsWith("kotlin.") ||
            n.startsWith("dalvik.")
    }

    /**
     * 形状校验：snsId 必须是**纯数字**（允许负号，兼容有符号写法）。
     * 这是最后一道防线 —— 取错字段时几乎不可能同时满足这个形状，
     * 「打错标签」（比不打标签糟糕得多）就基本不会发生。
     */
    private fun parseSnsId(raw: String): Long? = SnsTagLogic.parseSnsId(raw)

    // ------------------------------------------------------------------ 全量重判

    private val sweeper = object : Runnable {
        override fun run() {
            sweepScheduled.set(false)
            runCatching { sweep() }
                .onFailure { LogX.w("SNS_TAG sweep err: ${it.javaClass.simpleName}") }
            ensureSweep()
        }
    }

    /** 有活才排下一拍：不在朋友圈界面时 [bound] 是空的，不空转 */
    private fun ensureSweep() {
        if (synchronized(bound) { bound.isEmpty() }) return
        if (sweepScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(sweeper, SWEEP_MS)
        }
    }

    /**
     * 对每个绑定过的 itemView 重判。
     * ⚠️ 真相只认「当前绑定关系」[bound]；开关关着时按**空清单**处理（= 全摘），
     * 这样「关掉再打开」也能自己贴回来，不必等下一次绑定。
     */
    private fun sweep() {
        val snap = snapshot()
        if (snap.isEmpty()) return
        val del = if (on()) deleted() else emptySet()
        // 呈现改为时间文案追加（不再 ensure 加绿色 TextView）。开关关着时按空清单处理
        // = 全还原。forceSet 是验证探针，正常恒空：Set.plus 空集合原样返回 del，零分配。
        val tagged = if (on()) del + forceSet else emptySet()
        for ((v, id) in snap) {
            if (!v.isAttachedToWindow) {
                synchronized(bound) { bound.remove(v) }
                continue
            }
            SnsDeletedTag.apply(v, id, tagged)
        }
    }

    /** WeakHashMap 的 key 在迭代时可能是 null（referent 被回收）→ 必须滤掉，否则 NPE */
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

    /**
     * 让「已删清单」缓存失效，下一拍重读。
     *
     * 存在的理由：台账被**外部**改动时（`op=snsg sub=srcfix sub2=clean` 清洗误登记的
     * `fixed_orig`、`setsrc` 定向改值）本类毫不知情，只靠 5 秒 TTL 兜底会让用户
     * 「清完还挂着标记」看几秒。探针里显式调一次，验收判据才能立刻变。
     */
    fun invalidateIds() {
        setAt = 0L
    }

    /** 已救下（= 被删过）的动态清单 */
    private fun deleted(): Set<Long> {
        val now = SystemClock.elapsedRealtime()
        if (setAt != 0L && now - setAt < SET_TTL_MS) return delSet

        // 首屏必须同步拿一次，否则进朋友圈第一屏一个标签都没有。
        // ⚠️ 只查 sns_id 一列（GroupDb.snsTagIds），绝不读 content/attrBuf/postBuf
        // 三个 BLOB —— 那会把十几 MB 读进主线程，滚动时必然掉帧。
        if (!firstLoad) {
            val s = loadIds()
            if (GroupDb.current() != null) firstLoad = true // 库还没开就别把首屏机会用掉
            delSet = s
            setAt = now
            return s
        }
        // 之后一律后台刷新：主线程绝不查库
        if (setLoading.compareAndSet(false, true)) {
            Thread {
                try {
                    runCatching {
                        delSet = loadIds()
                        setAt = SystemClock.elapsedRealtime()
                    }
                } finally {
                    // ⚠️ 必须在 finally 里复位：被中断 / Error 路径下漏复位的话，
                    // 标志永久卡住，之后再也不会刷新清单（标签停在旧数据上）。
                    setLoading.set(false)
                }
            }.apply { isDaemon = true; name = "sns-tag-ids" }.start()
        }
        return delSet
    }

    private fun loadIds(): Set<Long> {
        val db = GroupDb.current()
        if (db == null) {
            if (!dbWarned) {
                dbWarned = true
                LogX.w("SNS_TAG: GroupDb not open, no labels")
            }
            return delSet
        }
        // ⚠️ 数据源换成并集 snsTagIds()（实测 12 条 vs 原 snsArchivedIds 8 条）：
        // 微信删动态有两条**互斥**路径（物理 DELETE 归档 vs UPDATE sourceType=0 补位），
        // 两集合交集为 ∅，只查任一边都会漏标。详见 GroupDb.snsTagIds 的 KDoc。
        return runCatching { db.snsTagIds() }
            .onFailure { LogX.w("SNS_TAG ids failed: ${it.javaClass.simpleName}") }
            .getOrDefault(delSet)
    }

    // ------------------------------------------------------------------ 打标

    /**
     * 临时强制打标集合 —— **真机验证专用**。
     * 归档里的 5 条已删动态是本人的、不一定出现在朋友圈时间线上，靠它们验证不了；
     * 有了这个就能把「当前屏幕上解析出来的动态」直接标上，一次性证明
     * 「解析 → 判定 → 打标」整条链路是通的，不必真去删一条动态。
     */
    @Volatile private var forceSet: Set<Long> = emptySet()

    private fun isDeleted(id: Long, del: Set<Long>): Boolean = id in del || id in forceSet

    /**
     * 呈现：不再往 itemView 末尾 addView 绿色 `[已删除]` TextView（用户拍板：同一条
     * 动态不能同时出现两个标记），统一改成在时间文案后面追加灰色 `[删除已拦截]`。
     * 具体动作在 [SnsDeletedTag.apply]；这里只负责判定 + 命中计数。
     */
    private fun apply(host: View, id: Long) {
        val del = deleted()
        if (isDeleted(id, del)) hits++
        // del + forceSet：验证探针设的 forceSet 也要走时间标记。正常运行时 forceSet
        // 恒空，Set.plus 空集合原样返回 del，零分配。
        SnsDeletedTag.apply(host, id, del + forceSet)
    }

    // ------------------------------------------------------------------ 真机验证入口

    /** 把当前屏幕上解析出来的动态全部强制打标（验证用）。必须在主线程调。 */
    fun markVisible(): String {
        val snap = snapshot()
        forceSet = snap.mapTo(HashSet()) { it.second }
        cfgAt = SystemClock.elapsedRealtime() // 别让 on() 立刻 refresh 把 forceSet 清掉
        // 探针必须和被测对象走同一条路：apply()（时间标记），否则验的不是新呈现
        for ((v, id) in snap) runCatching { apply(v, id) }
        return "SNS_TAG markVisible n=${forceSet.size}"
    }

    fun unmark(): String {
        forceSet = emptySet()
        ensureSweep()
        return "SNS_TAG unmark"
    }

    /**
     * 按 id 精确模拟「这条被删了」（验证用）。传入 `dump` 里看到的无符号 id。
     *
     * 这一步能一次性验证两件事：
     * ① 「已删清单 → 判定 → 打标」全链路（用的是真实的 id，走真实的 delSet 比较路径）
     * ② **offset 是否对齐** —— 标签必须落在**那条**动态上。若错位到邻居上，
     *    说明 position ↔ getData() 下标校准有问题（这是最危险的失效模式）。
     */
    fun fakeMark(raw: String): String {
        val id = parseSnsId(raw.trim()) ?: return "SNS_TAG fake bad id=$raw"
        forceSet = setOf(id)
        setAt = 0L
        cfgAt = SystemClock.elapsedRealtime()
        for ((v, vId) in snapshot()) runCatching { apply(v, vId) }
        return "SNS_TAG fake u=$raw s=$id"
    }

    /** 明细：已删清单 + 当前屏幕上每个 item 解析出的 id（无符号/有符号两种写法都打） */
    fun dump(): String {
        val sb = StringBuilder("SNS_TAG dump force=${forceSet.size}\n")
        sb.append("  deleted(${delSet.size}): ")
            .append(delSet.take(8).joinToString(",") { java.lang.Long.toUnsignedString(it) })
            .append("\n")
        val snap = snapshot()
        sb.append("  bound(${snap.size}):\n")
        for ((v, id) in snap) {
            sb.append("    u=").append(java.lang.Long.toUnsignedString(id))
                .append(" hit=").append(isDeleted(id, delSet))
                .append(" txt=").append(textsOf(v, 2).joinToString("|") { it.take(12) })
                .append(" host=").append(v.javaClass.name)
                .append(" LL=").append(v is LinearLayout)
                .append(" FL=").append(v is FrameLayout)
                .append("\n")
        }
        return sb.toString()
    }

    /**
     * 自诊断。看数顺序：
     * ① `binds>0` —— 钩子活着吗（0 = 完全没挂上）
     * ② `snsBinds>0` —— 朋友圈限定生效吗（binds 大而 snsBinds=0 = 类名前缀写错）
     * ③ `resolved≈snsBinds` —— 解析通吗（差得多就看 lastMiss）
     * ④ `hits>0` —— 清单/符号对吗（解析全通但 hits=0 = 归档里的 id 与解析出的 id 位模式对不上）
     * ⑤ `missedTime>0` —— hits 有值但界面上标记没出现时，看这个：>0 说明反射取不到
     *    时间控件（getPublishTimeText()/字段 o 漂移），不是判定错
     */
    fun stat(): String = "SNS_TAG stat enabled=${on()} " +
        "guard=${Kv.getBool(Keys.ANTIREVOKE_SNS)} tag=${Kv.getBool(Keys.ANTIREVOKE_SNS_DELETED_TAG)} " +
        "deleted=${delSet.size} bound=${synchronized(bound) { bound.size }} " +
        "binds=$binds sns=$snsBinds resolved=$resolved " +
        "missed=$missed hits=$hits missedTime=${SnsDeletedTag.missedTimeCount()} lastMiss=$lastMiss"
}
