package com.lx.wxgroup.feature

import com.lx.wxgroup.core.ConvReader
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 新加群自动免打扰（6.1 第四条：被拉进新群时自动把该群设为免打扰）。
 *
 * ## 取证依据（全部来自 docs/取证结论.md 已实证章节，不猜 API）
 *
 * 1. **「新群聊会话」从哪来** —— 会话列表结构是 8.0.48/2588 实证结论
 *    （ConvFilter 类注释 + ConvReader 实测记录）：
 *    `ConversationListView → HeaderViewListAdapter.mAdapter`，item 是
 *    `com.tencent.mm.storage.r4`，`field_username` 就是 wxid，群聊以 `@chatroom` 结尾。
 *    → 模块启动时 hook `ListView.setAdapter`（Android 公开 API，锚点与 ConvFilter 不同
 *    功能共用同一处），首次拿到会话列表 adapter 后后台枚举一次，得到**存量群快照**。
 * 2. **「出现了一个没见过的群聊」从哪来** —— 通知链路实证结论（docs/取证结论.md
 *    「通知静音」章节）：每条微信通知都构造 `NotificationItem`，字段 `h`=talker。
 *    QuietNotificationBridge 已 hook 该方法并暴露 talker 观察者 → 本类 registered
 *    observer 被动接收。
 * 3. **「置免打扰」怎么落** —— 同样取自「通知静音」章节实证：响铃/震动由系统按
 *    channel 播放，正解是把通知的 `mChannelId` 反射改写成自建静音通道
 *    （IMPORTANCE_HIGH + setSound(null) + enableVibration(false)），通知栏/角标原样。
 *    → 本类只做「登记」，静音改写完全复用 QuietNotificationBridge（与 DND 时段
 *    / 被@静音同一条通道），**不写微信任何数据、不调任何未实证的微信 API**。
 *
 * ## 降级与已知边界（宁可不做，不猜）
 *
 * - **「入群事件」无锚点**：取证结论里没有进群回调/事件的定位。按批准的退路实现为
 *   「检测到未见过的新群聊会话时置免打扰」：存量靠启动快照区分，快照未就绪前
 *   观察到的群一律按存量（宁漏设，也不把老群静音了）。
 * - **两层免打扰（2026-10-18 起）**：
 *   1. **真开关**：[applyOne] 调 [com.lx.wxgroup.core.RealMuteSetter.setMuted] 发
 *      oplog CGI，等价于用户在聊天信息页手动打开「消息免打扰」（真机双向验证通过）。
 *   2. **即时静音**：仍然走 QuietNotificationBridge 改通知通道 —— 因为 CGI 的本地
 *      sync 实测要**数十秒**，这段窗口里新群消息还是会响铃，靠静音通道顶住。
 *   两条并行，任一条失败都不会互相拖累（真开关失败只打日志，静音照旧）。
 * - **「自建群不设」暂不生效**：判定群主需要 chatroom owner 锚点（NotificationItem /
 *   r4 均无此字段），取证结论未覆盖 → 待取证后补（[applyOne] 的 owner 参数预留）。
 * - **手动关掉后不再自动打开**：靠 GroupDb.auto_dnd_done 台账（重启仍有效）。
 */
object AutoDndGroup {

    /** 锚点同 ConvFilter（docs/取证结论.md 会话列表结论）：微信会话列表 ListView。 */
    private const val CONV_LIST_CLASS = "com.tencent.mm.ui.conversation.ConversationListView"

    /** setAdapter 后等微信把 2900+ 会话填完再枚举，快照才准（毫秒）。 */
    private const val SNAPSHOT_DELAY_MS = 2500L

    /**
     * 会话列表刷新触发的新群扫描节流间隔（毫秒）。
     * 微信刷新会话列表很频繁（新消息、撤回、草稿…），必须节流 + 后台遍历。
     */
    private const val SCAN_THROTTLE_MS = 30_000L

    /**
     * 一轮最多允许出现几个「新群」，超过就**只登记不设免打扰**。
     *
     * ⚠️ 这条是防最坏情况的保险丝：冷启动 seed 依赖「首次枚举拿到了全部老群」，
     * 而会话列表可能被本项目自己的分组筛选裁剪、被「折叠的群聊」隐藏、或还没加载完，
     * 一旦 seed 不全，下一轮扫描会把几百个老群当成新群**一次性全部免打扰**
     * （用户侧不可逆）。正常入群一轮最多一两个，>3 一定是基线出了问题。
     */
    private const val MAX_FRESH_PER_ROUND = 3

    /**
     * 会话 adapter 的候选类名 —— **只当加速 hint，不是识别的必要条件**。
     *
     * dex 实证（8.0.48 / 2588，`_cache/dex/classes4.dex`）：
     * `com.tencent.mm.ui.conversation.p3` → `com.tencent.mm.ui.i3` → `android.widget.BaseAdapter`，
     * 且 `MainUI.o : ConversationListView` / `MainUI.v : p3` ⇒ 会话列表就是这一对。
     * ⚠️ `p3` 是混淆名，升版必变。所以它匹配失败不代表失效 ——
     * 真正的判定是 [isConversationAdapter] 的内容判定（够长 + 能读出群 username）。
     */
    private val CONV_ADAPTER_CLASS_HINTS = listOf(
        "com.tencent.mm.ui.conversation.p3"
    )

    /** 内容判定的下限：会话列表动辄上千项，普通弹窗/选择器到不了这个量级。 */
    private const val MIN_CONV_COUNT = 20

    /** 内容判定最多探几项（够判定就行，别每次刷新都全量读）。 */
    private const val CONV_PROBE_SAMPLES = 12

    /**
     * 真免打扰 CGI 发出后多久复查一次本地状态（毫秒）。
     * 实测 sync 延迟数十秒，取 60s 留足余量；未生效则补发一次。
     */
    private const val REALMUTE_VERIFY_DELAY_MS = 60_000L

    @Volatile
    private var installed = false

    /**
     * 存量群基线 —— **持久化**在 `GroupDb.group_seen` 表。
     *
     * ⚠️ 原来它只在内存里、每次微信重启用当前会话列表重新快照，导致：用户被拉进的
     * 新群在下一次启动时已经躺在列表里 → 被判成存量群 → **功能重启即失效**
     * （2026-10-18 真机：`joined=0` 却 `baseline=731`，新群永远不动）。
     * 改成跨重启累加：只有**从未见过**的群才算新群。
     */
    private val baseline: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    /**
     * 冷启动是否已完成（= 基线里已经有历史数据）。
     * 首次安装时基线为空，此时要把当前所有群一次性灌进基线并**全部跳过处理** ——
     * 否则会把几百个老群全当成新群给免打扰掉。
     */
    @Volatile
    private var baselineReady = false

    /**
     * 会话列表 adapter 引用：用于「列表刷新时扫描新群」（不依赖通知的触发源）。
     * ⚠️ 用弱引用：强引用会一直拖住旧的 ListView / Activity，会话页重建后泄漏。
     */
    @Volatile
    private var convAdapterRef: java.lang.ref.WeakReference<android.widget.ListAdapter>? = null

    private val convAdapter: android.widget.ListAdapter?
        get() = convAdapterRef?.get()

    /** 扫描节流时间戳（[SCAN_THROTTLE_MS]） */
    @Volatile
    private var lastScanMs = 0L

    /** convAdapter 为 null 的告警只打一次（避免刷屏） */
    private val convAdapterWarned = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * `ListView.setAdapter` 这条路是否命中过会话列表。
     *
     * 命中过 ⇒ 启动快照那条路是好的；没命中 ⇒ 由 [hookAdapterChanged] 首次识别到
     * 会话 adapter 时补做一次启动快照（谁先到谁做，且只做一次）。
     */
    private val anchorMatched = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 运行时学到的会话 adapter 类名（命中一次后走快路径，省掉内容判定） */
    @Volatile
    private var learnedConvAdapterClass: String? = null

    /** 识别失败的 adapter 类名（每个类只 warn 一次，避免刷屏） */
    private val rejectedAdapterClasses = Collections.synchronizedSet(LinkedHashSet<String>())

    /** 已登记/已记账的群（含从 GroupDb 台账恢复的），内存态去重用 */
    private val handled: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    /** 本次进程内新登记过的群数（observer 可能在多线程回调，必须原子） */
    private val joined = AtomicInteger(0)

    /** 宿主 classLoader：真 setter 反射要用（[applyOne]） */
    @Volatile
    private var hostLoader: ClassLoader? = null

    /** 真 setter 必须主线程发 CGI（微信 CGI 链路依赖主线程上下文） */
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            hostLoader = lpparam.classLoader
            val db = GroupDb.current()
            // 重启恢复：台账只用于「不重复处理」，**不再重挂静音通道**。
            // ⚠️ 原来这里会把台账里的群重新 markAutoDndTalker，后果是用户在微信里手动
            // 关掉免打扰后，一重启又被我们的静音通道盖住 → 表现为「关不掉」。
            // 免打扰的持久性改由真开关（服务端 CGI）保证 —— 那才是用户自己认可的开关。
            db?.listDndHandled()?.forEach { handled.add(it) }
            // 恢复跨重启的存量基线（新群判定的依据）
            val seen = db?.listSeenGroups().orEmpty()
            baseline.addAll(seen)
            baselineReady = seen.isNotEmpty()
            QuietNotificationBridge.addTalkerObserver(::onTalkerObserved)
            hookConversationList(lpparam)
            hookAdapterChanged(lpparam)
            installed = true
            // pid 是「进程有没有真的换过」的硬判据：冷启后 pid 必变。
            // 2026-10-29 曾据此区分「模块没重载」和「重载了但 hook 没命中」。
            LogX.i(
                "QUIET ADND installed pid=${android.os.Process.myPid()} " +
                    "restored=${handled.size} seen=${seen.size} " +
                    "coldStartDone=$baselineReady enabled=${Kv.getBool(Keys.CHAT_AUTO_DND_NEW_GROUP)}"
            )
        }.onFailure {
            installed = false
            LogX.i("QUIET ADND install failed: ${it.javaClass.simpleName}")
        }
    }

    /**
     * 会话列表首次拿到 adapter 时，后台枚举一次存量群。
     *
     * 两个触发源（谁先到谁做，见 [anchorMatched]）：
     * `ListView.setAdapter` 命中会话列表，或 [hookAdapterChanged] 首次识别出会话 adapter。
     *
     * 枚举按 Android 公开 API（ListAdapter.getCount/getItem）+ ConvReader（实证字段）。
     * header/footer 行的 getItem 返回 null → ConvReader.username("") 安全跳过。
     */
    private fun hookConversationList(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.widget.ListView",
                lpparam.classLoader,
                "setAdapter",
                android.widget.ListAdapter::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = param.thisObject as? android.widget.ListView ?: return
                        if (list.javaClass.name != CONV_LIST_CLASS) return
                        // 先出声再取值：区分「回调没来」和「回调来了但 list.adapter 是 null」
                        // （2026-10-29 那次就是这条分支全程无声，导致误判成"类名变了"）
                        LogX.i(
                            "QUIET ADND anchor setAdapter hit list=${System.identityHashCode(list)} " +
                                "adapterCls=${runCatching { list.adapter?.javaClass?.name }.getOrNull()}"
                        )
                        val adapter = runCatching { list.adapter }.getOrNull()
                        if (adapter == null) {
                            LogX.w("QUIET ADND anchor setAdapter: list.adapter=null —— 锚点在但取不到 adapter")
                            return
                        }
                        convAdapterRef = java.lang.ref.WeakReference(adapter) // 只供 probe 观察用
                        // 标记「启动快照这条路是通的」，notify 那边就不必再补一次
                        anchorMatched.set(true)
                        LogX.i(
                            "QUIET ADND anchor setAdapter ok cls=${adapter.javaClass.name} " +
                                "count=${runCatching { adapter.count }.getOrDefault(-1)}"
                        )
                        // ⚠️ 不能因为 baselineReady 就跳过：基线的意义已经从「本次启动快照」
                        // 变成「跨重启累加的已知集合」，每次启动都要拿当前列表跟它 diff，
                        // 才能发现"上次启动之后才加进来的群"。
                        // HeaderViewListAdapter 的 count 含 header/footer，getItem 越界返回 null，均可安全跳过
                        Thread({
                            runCatching { snapshotBaseline(adapter) }
                                .onFailure {
                                    LogX.i("QUIET ADND snapshot failed: ${it.javaClass.simpleName}")
                                }
                        }, "wxgroup-adnd-snap").start()
                    }
                }
            )
            LogX.i("QUIET ADND hooked ListView.setAdapter (baseline 快照)")
        }.onFailure { LogX.i("QUIET ADND hook setAdapter failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 启动时拿当前会话列表跟**持久化的已知集合** diff。
     *
     * - 首次运行（基线为空）：把所有现存群一次性灌进基线并全部跳过 —— 否则几百个
     *   老群会被当成新群全免打扰掉。
     * - 之后：只处理「从未见过」的群。
     *
     * ⚠️ 一个群都没枚举到时**不**置就绪，让判定继续走「存量群」（宁漏设也不错设老群）。
     */
    private fun snapshotBaseline(adapter: android.widget.ListAdapter) {
        Thread.sleep(SNAPSHOT_DELAY_MS) // 等微信把数据填完再快照
        val groups = runCatching { enumerateGroups(adapter) }.getOrNull()
        if (groups.isNullOrEmpty()) {
            LogX.i("QUIET ADND baseline NOT ready (groups=0) — 保持存量假设")
            return
        }
        if (!baselineReady) {
            synchronized(baseline) {
                if (baselineReady) return
                baseline.addAll(groups)
                baselineReady = true
            }
            persistSeen(groups)
            LogX.i("QUIET ADND cold start: seeded ${groups.size} groups (首次运行，全部按存量处理)")
            // 落盘校验：库没打开/写入失败时这里会暴露（否则每次启动都重新冷启动，功能永不生效）
            Thread({
                Thread.sleep(2000)
                val n = GroupDb.current()?.seenCount() ?: -1
                if (n < groups.size) {
                    LogX.w("QUIET ADND cold start 落盘异常: seenCount=$n < seeded=${groups.size}")
                }
            }, "wxgroup-adnd-verify").start()
            return
        }
        diffAndApply(groups, "startup")
    }

    /**
     * hook 会话列表刷新：被拉进新群时**不一定有通知**（已知边界），但会话列表一定会变。
     * 这条触发源不依赖通知，是「入群即发现」的关键。
     *
     * 节流 [SCAN_THROTTLE_MS] + 后台线程遍历，避免高频刷新把主线程拖死。
     *
     * ## 🔴 2026-10-29 R35-4 重写：不再用 convAdapter 弱引用
     *
     * 原实现用冷启时 `ListView.setAdapter` 抓到的弱引用当数据源，抓不到就**永久静默**
     * （`lastScanMs` 在判 null 之前就更新了，且 warn 只打一次）—— 真机上表现为
     * 「新群完全不会自动免打扰，日志一行异常都没有」。
     *
     * dex 实证（8.0.48/2588 `_cache/dex/classes4.dex`）：
     * 会话 adapter `com.tencent.mm.ui.conversation.p3` **覆写了** `notifyDataSetChanged()`，
     * 但方法体里第一条就是 `invoke-super -> android.widget.BaseAdapter.notifyDataSetChanged()`。
     * ⇒ 本 hook 依然为会话刷新命中，且 **`param.thisObject` 就是活的会话 adapter**。
     * 直接用它，就不必经过 View / Activity / 弱引用，Fragment 重建也不会丢。
     */
    private fun hookAdapterChanged(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.widget.BaseAdapter",
                lpparam.classLoader,
                "notifyDataSetChanged",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!baselineReady) return
                        // ⛔ 这里是全局 hook：整个微信上千个 BaseAdapter 都会过这条路。
                        // 只有确认它是会话列表 adapter 才继续（先看 thisObject，不再看别的锚点）。
                        val ad = param.thisObject as? android.widget.ListAdapter
                        if (ad == null) {
                            if (!convAdapterWarned.getAndSet(true)) {
                                LogX.w(
                                    "QUIET ADND scan: thisObject 不是 ListAdapter " +
                                        "(${param.thisObject?.javaClass?.name}) —— 锚点结构变了"
                                )
                            }
                            return
                        }
                        if (!isConversationAdapter(ad)) return
                        // setAdapter 那条路没命中过 ⇒ 启动快照还没做过，这里补一次（只做一次）
                        if (!anchorMatched.getAndSet(true)) {
                            Thread({
                                runCatching { snapshotBaseline(ad) }
                                    .onFailure {
                                        LogX.i("QUIET ADND snapshot failed: ${it.javaClass.simpleName}")
                                    }
                            }, "wxgroup-adnd-snap").start()
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastScanMs < SCAN_THROTTLE_MS) return
                        lastScanMs = now
                        Thread({
                            runCatching {
                                val groups = enumerateGroups(ad)
                                if (groups.isEmpty()) {
                                    // 会话列表里一个群都没有 ⇒ 八成是 ConvReader 读不到 item 了，必须出声
                                    LogX.w(
                                        "QUIET ADND scan: convs=0 adapter=${ad.javaClass.simpleName} " +
                                            "count=${runCatching { ad.count }.getOrDefault(-1)}"
                                    )
                                    return@runCatching
                                }
                                val fresh = groups.count { it !in baseline && it !in handled }
                                LogX.i(
                                    "QUIET ADND scan ok adapter=${ad.javaClass.simpleName} " +
                                        "convs=${groups.size} new=$fresh"
                                )
                                // 保持与原实现一致：非空就交给 diffAndApply（它负责补登记 seen）
                                diffAndApply(groups, "scan")
                            }.onFailure {
                                LogX.i("QUIET ADND scan failed: ${it.javaClass.simpleName}")
                            }
                        }, "wxgroup-adnd-scan").start()
                    }
                }
            )
            LogX.i("QUIET ADND hooked BaseAdapter.notifyDataSetChanged (新群扫描)")
        }.onFailure {
            LogX.i("QUIET ADND hook notifyDataSetChanged failed: ${it.javaClass.simpleName}")
        }
    }

    /**
     * 判定 [ad] 是不是会话列表 adapter。三步，**类名不是必要条件**：
     *
     * 1. 运行时学到的类名（最快，命中一次之后都走这条）；
     * 2. dex 实证的候选名单 [CONV_ADAPTER_CLASS_HINTS]（混淆名，可能失效，只是加速）；
     * 3. **内容判定**：项数 >= [MIN_CONV_COUNT] 且采样里能读出 `@chatroom`
     *    （走 ConvReader 实证字段 `field_username`，与 [enumerateGroups] 同一口径）。
     *
     * 三步都不中 ⇒ warn（带真类名 + 项数，每个类名只吵一次）。
     * ⚠️ 这条 warn 是**以后最重要的取证入口**：微信换了 adapter 类名/结构时，
     * 真机日志会直接告诉我们它现在叫什么，不会再出现「静默失效」。
     */
    private fun isConversationAdapter(ad: android.widget.ListAdapter): Boolean {
        val cls = ad.javaClass.name
        // 本项目自己的 adapter（ConvFilter.GroupedAdapter 等）不参与识别：
        // 它语义上只是「可见会话」的快照，筛选态下会把列表裁到只有几项，不是可靠数据源。
        if (cls.startsWith("com.lx.wxgroup")) return false
        if (cls == learnedConvAdapterClass) return true
        if (rejectedAdapterClasses.contains(cls)) return false // 已判定过，别每次刷新都重探
        if (CONV_ADAPTER_CLASS_HINTS.contains(cls)) {
            learnedConvAdapterClass = cls
            return true
        }
        val n = runCatching { ad.count }.getOrDefault(0)
        if (n < MIN_CONV_COUNT) return false
        val hit = runCatching {
            val limit = if (n < CONV_PROBE_SAMPLES) n else CONV_PROBE_SAMPLES
            for (i in 0 until limit) {
                val item = ad.getItem(i) ?: continue
                if (QuietLogic.isGroupTalker(ConvReader.username(item))) return@runCatching true
            }
            false
        }.getOrDefault(false)
        if (hit) {
            learnedConvAdapterClass = cls
            return true
        }
        if (rejectedAdapterClasses.add(cls)) {
            LogX.w(
                "QUIET ADND scan: 未识别 adapter cls=${cls.take(64)} count=$n " +
                    "（若它确实是会话列表，请把类名补进 CONV_ADAPTER_CLASS_HINTS）"
            )
        }
        return false
    }

    /** 枚举 adapter 里所有群聊 username（header/footer 的 getItem 返回 null，安全跳过） */
    private fun enumerateGroups(adapter: android.widget.ListAdapter): Set<String> {
        val count = runCatching { adapter.count }.getOrDefault(0)
        val found = LinkedHashSet<String>()
        for (i in 0 until count) {
            val item = runCatching { adapter.getItem(i) }.getOrNull() ?: continue
            val username = ConvReader.username(item)
            if (QuietLogic.isGroupTalker(username)) found.add(username)
        }
        return found
    }

    /** 与已知集合 diff：新群逐个处理，然后把所有群登记为"见过" */
    private fun diffAndApply(groups: Set<String>, tag: String) {
        // 开关关闭期间**不登记**：否则这段时间进的群被永久标成"见过"，
        // 用户之后打开开关也不会补设，表现为静默失效。
        if (!Kv.getBool(Keys.CHAT_AUTO_DND_NEW_GROUP)) return

        val fresh = groups.filter { it !in baseline && it !in handled }
        val toPersist = groups.filter { it !in baseline }
        if (toPersist.isNotEmpty()) {
            synchronized(baseline) { baseline.addAll(toPersist) }
            persistSeen(toPersist)
        }
        if (fresh.isEmpty()) return
        if (fresh.size > MAX_FRESH_PER_ROUND) {
            // 保险丝：一轮冒出这么多新群，只可能是基线不全（筛选态/折叠群/没加载完），
            // 宁可漏设也绝不批量误设 —— 误设免打扰对用户是不可逆的骚扰
            LogX.w(
                "QUIET ADND [$tag] 疑似基线不全：一轮出现 ${fresh.size} 个新群 " +
                    "(>$MAX_FRESH_PER_ROUND)，只登记 seen、不设 DND -> ${fresh.take(3)}"
            )
            return
        }
        LogX.i("QUIET ADND [$tag] new groups=${fresh.size} -> ${fresh.map { it.take(20) }}")
        for (id in fresh) applyOne(id, ownerWxid = null)
    }

    private fun persistSeen(ids: Collection<String>) {
        val db = GroupDb.current()
        if (db == null) {
            // db 为 null 时 markSeen 不抛异常（?. 直接返回 null），runCatching 抓不到，
            // 必须显式告警 —— 否则基线没落盘、每次启动都重新冷启动，功能永久不生效
            LogX.w("QUIET ADND persistSeen: no db — 基线没落盘，重启后会重新冷启动")
            return
        }
        Thread({
            runCatching { db.markSeen(ids) }
                .onFailure { LogX.i("QUIET ADND persistSeen failed: ${it.javaClass.simpleName}") }
        }, "wxgroup-adnd-seen").start()
    }

    /**
     * 被动观察：每条带 talker 的通知过一遍（bridge 回调，可能高频 → 只做内存判定）。
     * 命中新群才落库/登记；写库丢后台线程，绝不卡通知路径。
     */
    private fun onTalkerObserved(talker: String) {
        if (!installed) return
        val verdict = runCatching {
            QuietLogic.classifyGroup(talker, baseline, handled, baselineReady)
        }.getOrDefault(QuietLogic.GroupDndVerdict.EXISTING)
        // 排查"新群没自动开"时先看这条：能定位是没触发、还是判成 EXISTING
        LogX.d("QUIET ADND observe ${talker.take(24)} -> $verdict")
        if (verdict != QuietLogic.GroupDndVerdict.NEW) return
        applyOne(talker, ownerWxid = null)
    }

    /**
     * 判定 + 登记 + 记账；失败静默放弃（绝不崩微信）。
     *
     * @param ownerWxid 群主 wxid。**当前无锚点可取，调用方恒传 null**；
     *   取证到 chatroom owner 锚点后按「群主是自己不设」补上（需求 6.1 第四条）。
     *   自身 wxid 同样未定位（取证结论 S8），两侧都无数据时该判定整体跳过。
     */
    private fun applyOne(chatroomId: String, ownerWxid: String?): Boolean {
        return runCatching {
            if (!Kv.getBool(Keys.CHAT_AUTO_DND_NEW_GROUP)) return@runCatching false
            if (!QuietLogic.isGroupTalker(chatroomId)) return@runCatching false
            // 自建群不设 —— 暂不可用：ownerWxid / 自身 wxid 均无锚点（见 KDoc）
            val selfWxid: String? = null
            if (!ownerWxid.isNullOrBlank() && !selfWxid.isNullOrBlank() && ownerWxid == selfWxid) {
                LogX.i("QUIET ADND skip own group $chatroomId")
                return@runCatching false
            }
            // ⚠️ 用 add() 的返回值当原子锁：新群涌入时 observer 可能多线程回调同一群，
            //    check-then-act 会让两条都过判定 → 重复发 CGI、joined 重复计数
            if (!handled.add(chatroomId)) {
                LogX.d("QUIET ADND dup $chatroomId (already handled)")
                return@runCatching false
            }
            QuietNotificationBridge.markAutoDndTalker(chatroomId)
            joined.incrementAndGet()
            // 记账落后台线程：通知路径上不能做磁盘 IO
            Thread({
                runCatching { GroupDb.current()?.markDndHandled(chatroomId) }
                    .onFailure { LogX.i("QUIET ADND ledger failed: ${it.javaClass.simpleName}") }
            }, "wxgroup-adnd-ledger").start()
            LogX.i("QUIET ADND muted ${chatroomId.take(24)} (silent channel)")

            // 真·免打扰开关：等价于用户手动打开微信自己的「消息免打扰」
            // （RealMuteSetter 发 oplog CGI，2026-10-18 真机双向验证）
            applyRealMute(chatroomId)
            true
        }.onFailure { LogX.i("QUIET ADND apply failed: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
    }

    /**
     * 真·免打扰开关：调 `RealMuteSetter` 发 oplog CGI，把微信自己的开关打开。
     *
     * - **必须主线程**：后台线程调 `b()` 不报错但 CGI 很可能不投递（02:26 那轮踩过）。
     * - **失败只降级**：反射链路断了/微信升级导致混淆名失效时，静音通道（[applyOne]
     *   前半段）已经生效，这里失败不影响用户侧"不响铃"，只是 UI 开关没变。
     * - **sync 有延迟**：CGI 成功后本地状态数十秒才更新，别在这里复查判成败。
     * - **不读状态直接发**：功能侧只做 mute=true，直接 `forceStateInt = 0` 跳过读状态。
     *   理由（复核）："已是目标状态就 skip"这条门完全建立在 `S` 反相的单次标定上，
     *   一旦某个群上 `S` 语义不成立，表现就是"日志正常、ok=true、什么都没发生" ——
     *   真机上最难发现的失效模式。CGI 幂等，无条件发比"靠读状态拦"稳。
     *   顺带避免在主线程做 `contactOf`（读联系人 DB）的 IO/ANR 风险。
     */
    private fun applyRealMute(chatroomId: String) {
        val cl = hostLoader
        if (cl == null) {
            LogX.w("QUIET ADND realmute skip: no classLoader")
            return
        }
        runCatching {
            mainHandler.post {
                val r = runCatching {
                    // forceStateInt=0 —— 0 = 开启免打扰（真机标定，别改）
                    com.lx.wxgroup.core.RealMuteSetter.setMuted(cl, chatroomId, true, 0)
                }.getOrElse {
                    LogX.w("QUIET ADND realmute threw: ${it.javaClass.simpleName}")
                    return@post
                }
                LogX.i(
                    "QUIET ADND realmute ok=${r.ok} sent=${r.sent} stateInt=${r.stateInt} " +
                        "factory=${r.factoryClass} ${r.detail}"
                )
                if (!r.sent) {
                    // 没真发出去：静音通道仍生效，但微信开关没变，必须留痕
                    LogX.w("QUIET ADND realmute NOT sent for ${chatroomId.take(24)} — 只有静音通道")
                    return@post
                }
                // 延迟复查：b() 返回 void，"没抛异常"≠"服务端真生效"。
                // 这是唯一能发现"发了但没生效"的手段，失败再补发一次。
                mainHandler.postDelayed({
                    val muted = runCatching {
                        com.lx.wxgroup.core.RealMuteSetter.isMuted(cl, chatroomId)
                    }.getOrNull()
                    if (muted == true) {
                        LogX.i("QUIET ADND realmute verified ${chatroomId.take(24)} muted=true")
                    } else {
                        LogX.w("QUIET ADND realmute UNVERIFIED ${chatroomId.take(24)} muted=$muted — retry once")
                        runCatching {
                            com.lx.wxgroup.core.RealMuteSetter.setMuted(cl, chatroomId, true, 0)
                        }.onFailure {
                            LogX.w("QUIET ADND realmute retry threw: ${it.javaClass.simpleName}")
                        }
                    }
                }, REALMUTE_VERIFY_DELAY_MS)
            }
        }.onFailure { LogX.w("QUIET ADND realmute post failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 探针用法（入口 `op=quiet`：`sub` 选中本功能，`sub2` 是本功能的子命令）：
     *   `--es op quiet --es sub grp --es sub2 status|anchor|applied`
     */
    fun probe(sub: String, intentVal: String? = null): String = runCatching {
        when (sub.lowercase()) {
            "status" ->
                "installed=$installed enabled=${Kv.getBool(Keys.CHAT_AUTO_DND_NEW_GROUP)} " +
                    "baselineReady=$baselineReady baseline=${baseline.size} handled=${handled.size} joined=$joined"
            "anchor" ->
                "scan=BaseAdapter.notifyDataSetChanged#thisObject(learned=$learnedConvAdapterClass) " +
                    "convList=$CONV_LIST_CLASS(ListView.setAdapter 仅作启动快照,matched=$anchorMatched) " +
                    "observe=NotificationItem.h(bridge observer) " +
                    "setter=QuietNotificationBridge(mChannelId rewrite) " +
                    "joinEvent=无锚点-降级为新群会话检测 owner=无锚点-自建群判定暂缺"
            "applied" -> "handled=${handled.toList()}"
            "seen" -> "seenCount=${baseline.size} coldStartDone=$baselineReady " +
                "convAdapter=${convAdapter != null} anchorMatched=$anchorMatched " +
                "learned=$learnedConvAdapterClass"
            /**
             * 手动对指定群执行「新群自动免打扰」全流程（含真开关 CGI）。
             * 两种用途：① 验证链路通不通；② 补救 —— 首次安装时该群已被吸进
             * 存量基线（cold start），之后再也不会被自动处理，只能手动补。
             *   `--es op quiet --es sub grp --es sub2 force --es val <chatroomId>`
             */
            "force" -> forceApply(
                intentVal ?: ""
            )
            else -> "subs=status,anchor,applied,seen,force(--es val <chatroomId>)"
        }
    }.getOrElse { "error=${it.javaClass.simpleName}" }

    /**
     * 手动执行（[probe] 的 `force` 子命令）。绕过新群判定，直接走 [applyOne]，
     * 连真开关 CGI 一起发 —— 用于验证链路 / 补救已被吸进存量基线的群。
     */
    private fun forceApply(chatroomId: String): String {
        if (chatroomId.isBlank()) return "force: need --es val <chatroomId>"
        if (!QuietLogic.isGroupTalker(chatroomId)) return "force: not a chatroom id"
        // handled 里已有会被引擎的去重锁挡掉，这里先摘掉再执行
        handled.remove(chatroomId)
        val ok = applyOne(chatroomId, ownerWxid = null)
        return if (ok) {
            "force OK $chatroomId (看后续 QUIET ADND realmute 日志)"
        } else {
            "force FAILED $chatroomId — 检查：功能开关是否打开 / id 是否为群 / 日志里有无 reject"
        }
    }
}
