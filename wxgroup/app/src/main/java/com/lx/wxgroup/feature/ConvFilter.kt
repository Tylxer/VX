package com.lx.wxgroup.feature

import android.database.DataSetObserver
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.HeaderViewListAdapter
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.TextView
import com.lx.wxgroup.core.ConvReader
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.ConvLayout
import com.lx.wxgroup.data.Group
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.data.PrefKeys
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.WeakHashMap

/**
 * K7：会话列表 Adapter 包装器（**按 A1–A13 重写**）。
 *
 * ## 干什么
 *
 * 微信会话列表的实测结构（8.0.48，`probe/` 打出来的）：
 *
 * ```
 * ConversationListView
 * └── HeaderViewListAdapter            headers=15 footers=1
 *     └── mAdapter = com.tencent.mm.ui.conversation.p3   ← 全部会话（2900+ 条）
 *         └── getItem() = com.tencent.mm.storage.r4      ← field_username 就是 wxid
 * ```
 *
 * 我们**只替换 `HeaderViewListAdapter.mAdapter` 这一层**，微信自己的 adapter、
 * 数据源、header/footer 全都不动（A9：只在 Adapter 层过滤，不动微信数据源）。
 *
 * ## 两种形态
 *
 * - **收纳态**（默认）：已归组的会话从列表消失，由分组行代表它出现（A6）。
 *   分组行占据「该组成员最新一条会话」的位置，跟会话一起按时间混排（A1/A2）。
 * - **筛选态**（点进某个分组）：只显示该组成员会话，**没有「返回全部」行** ——
 *   退出走系统返回键（[hookBackExit]），跟微信自己的返回行为一致。
 *
 * 两种形态的行序列都由 [ConvLayout] 算（纯 Kotlin，有单测），这里只负责渲染和转发。
 *
 * ## 三条实机踩出来的规矩（别改回去）
 *
 * 1. **observer 必须挪到包装 adapter 上**。ListView 的数据观察者原本挂在微信的
 *    adapter 上，不挪过来的话我们 `notifyDataSetChanged` 时列表根本不刷新
 *    （`mItemCount` 不更新）。
 * 2. **分组行的点击走 `View.OnClickListener`**。实测微信会话行是每行自己挂监听的，
 *    `onItemClick` hook 一次都没触发过。分组行照做最稳 —— 但下面对
 *    `onItemClick` 也包了一层重映射，两条路都堵住。
 * 3. **`getView` 绝不返回 null**。返回 null 那一行就是一块空白，
 *    筛到空分组时用户会被彻底卡死（真机实测踩到，最难查的一个）。
 *
 * ## 拿会话字段的规矩
 *
 * **不能用 `Class.forName("com.tencent.mm.storage.r4")`** —— 走的是模块自己的
 * ClassLoader，看不到微信的类（实测返回 null，过滤会静默退化成「全部显示」）。
 * 一律通过 [ConvReader]，它从**真实 item 的运行时类**上反射。
 */
object ConvFilter {

    /** 没选中任何分组 = 收纳态 */
    const val NONE = 0L

    private const val LIST_CLASS = "com.tencent.mm.ui.conversation.ConversationListView"

    /** 重建去抖：微信一有新消息就 notify，2900 条全量重读会卡 */
    private const val REBUILD_DEBOUNCE_MS = 250L

    /**
     * R22 性能探针的报警阈值（毫秒）。
     *
     * R1 修复后 `readSnaps(n)` 从「条数变化才走的冷路径」变成「**每次** rebuild 都走
     * 的热路径」，2900 项逐条 `wrapped.getItem(i)` + 反射读字段。`getItem` 的实现体
     * 在微信 dex 里，静态判不出成本 ⇒ 用这个探针在真机上量。
     *
     * 判据：单次 `snaps` 或整次 rebuild 超过 8ms（≈ 半帧的一半）就打日志。
     * 若真机日志里反复出现，说明该退化成「先比 count + 首末 wxid，不符才全量读」
     * 的两段式（代价：中部局部重排可能漏检）。
     */
    private const val PROBE_WARN_MS = 8L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** R31：扫「置顶聊天」折叠态的节流间隔（布局回调可能密集，100ms 够用且看不出来） */
    private const val PIN_FOLD_SCAN_MS = 100L

    /**
     * R34 P2：认定「行数突变」的最小差值（条）。
     *
     * 折叠/展开「置顶聊天」是 ±19（= 置顶会话数，§9.3 实测），远大于这个门限；
     * 收新消息最多 ±1（新建一条会话），**不命中**，仍走 250ms 去抖。
     *
     * 为什么用门限而不是「只要 count 变了就即时重建」：收新消息是高频事件，
     * 把它从 250ms 去抖变成即时全量重算，等于把 `readSnaps` 压到每条消息一次。
     * 门限 2 把「高频小幅」与「低频大幅」分开 —— 只有折叠/展开这种一次性 ±19
     * 才值得即时对齐。
     */
    private const val COUNT_JUMP = 2

    /**
     * 遍历视图树读所有 TextView 的文字（只看 8 层，会话行的子树足够浅）。
     *
     * R31 只用于判「折叠置顶聊天」状态，不参与任何渲染。
     * ⚠️ 微信那两行文案来自资源（`%d个置顶聊天` / `折叠置顶聊天`），8.0.48 已实查确认。
     */
    private fun walkTexts(root: View, depth: Int = 0, onText: (String) -> Unit) {
        if (depth > 8) return
        if (root is TextView) {
            val t = root.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) onText(t)
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i) ?: continue
                if (c.visibility == View.VISIBLE) walkTexts(c, depth + 1, onText)
            }
        }
    }

    /** listView -> 我们装进去的包装 */
    private val wrappers = Collections.synchronizedMap(WeakHashMap<ListView, GroupedAdapter>())

    /**
     * 微信会话列表「停在顶部」时 `getChildAt(0).top` 的值（真机实测 **-2713**）。
     *
     * 为什么会有这个值：微信把整条顶部栏（标题 + 搜索 + 加号）做成会话列表的
     * **第一个 header**，高度 ≈ 3046px。列表停在顶部时，这个 header 只有末尾
     * 333px 露在屏幕上（`-2713 + 3046 = 333`），会话行从 333 往下排。
     * 也就是说「列表在顶部」≠ `child0.top == 0`，而是 `child0.top == -2713`。
     *
     * ⚠️ 这个数**不能硬编码**（换微信版本/换机型就变），所以在列表正常停在顶部时
     * 抓一次存下来，之后重建列表都用它还原（见 [restoreAnchor]）。
     */
    @Volatile
    private var headerAnchor: Int? = null

    /** 当前筛选的分组 id（0 = 收纳态）；-1 = 还没从库里读过 */
    @Volatile
    private var cachedId: Long = -1L

    @Volatile
    private var cachedMembers: Set<String> = emptySet()

    /**
     * 成员关系版本号：每次「加/删成员」递增一次（见 [refreshMembers]）。
     *
     * 收纳态下成员变化不落在 [cachedMembers]（收纳态 cachedId==0，cachedMembers 恒为空），
     * 而是落在每个 [GroupedAdapter] 自己的 [GroupedAdapter.memberSets] 里 —— 而 memberSets
     * 不在配置指纹（configStamp）里。用这个单调版本号让每个 wrapper 的下一次 [GroupedAdapter.rebuild]
     * 强制全量重读 memberSets，避免被「指纹相同」短路。筛选态也顺带受益。
     */
    @Volatile
    private var membersVersion = 0

    /**
     * 摘要版本号：每次「未读数/摘要变了但 timeMs 没变」时递增一次（见 [refreshSummaries]）。
     *
     * 为什么单独需要一个：轻量路径用 [GroupedAdapter.timeFingerprint] 判「摘要要不要重算」，
     * 而那个指纹只滚 `timeMs`（`readSnaps` 的产出里没有 unread）。**标记已读**恰好是
     * 「unread 变 0、timeMs 不动」—— 指纹不变 ⇒ 直接 return ⇒ 分组行未读角标残留到下一条消息。
     * 用这个单调版本号让那一次 rebuild 跳过「完全没变」的短路。
     */
    @Volatile
    private var summariesVersion = 0

    private var rebuildPending = false

    /**
     * 重入守卫（**没有它主线程会死循环，界面全黑**）。
     *
     * [wrapListeners] 自己会把包好的 listener set 回去，而 setter 被我们 hook 了 ——
     * 于是 afterHookedMethod 里又调 [wrapListeners]，又 set，又触发…… 真机 ANR 栈实锤：
     *
     *     ConvFilter.wrapListeners(ConvFilter.kt:164)
     *       ← ConversationListView.setOnItemClickListener ← hook
     *     ConvFilter.wrapListeners(ConvFilter.kt:170)
     *       ← ConversationListView.setOnItemLongClickListener ← hook
     *     ConvFilter.wrapListeners(ConvFilter.kt:164) ← …无限
     *
     * 结果主线程 utm 烧到 77s，LauncherUI 永远走不到 relayoutWindow，
     * 窗口 mDrawState=NO_SURFACE / mShownAlpha=0.0 —— 表现就是「解锁了但全黑」。
     *
     * 用 ThreadLocal 而不是普通字段：hook 可能被非主线程调用，绝不能互相干扰。
     */
    private val wrapping = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    /**
     * 长按回调的**重入标记**（区别于 wrapping：那是防 set 时套娃，这是防调用时套娃）。
     * 详见 [LongClickRemap] 里的说明 —— 缺了它长按任意会话都会栈溢出崩掉微信。
     */
    private val inLongClick = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    // ------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookSetAdapter(lpparam)
        hookListeners(lpparam)
        hookBackExit(lpparam)
    }

    // ------------------------------------------------- 系统返回退出筛选态
    //
    // 筛选态不再往列表里插「返回全部会话」行，退出改走**微信自己的返回**：
    // 先退出分组页，再按一次才走微信原本的逻辑（把微信挪到后台）。
    //
    // ⚠️ **挂 `onBackPressed` 是错的**（真机实测踩过）：微信在更早的
    // `dispatchKeyEvent` 里就已经把自己挪到后台了，`onBackPressed` 只是**之后**才
    // 被顺带调一次。实测栈：
    //
    //     ViewRootImpl → DecorView.dispatchKeyEvent → AppCompat
    //       → LauncherUI.dispatchKeyEvent
    //       → com.tencent.mm.ui.HomeUI.r()          ← 微信主界面的返回处理
    //       → LauncherUI.moveTaskToBack()           ← 这里微信就已经退到后台了
    //
    // 所以必须在 **ACTION_DOWN 的 dispatchKeyEvent** 上拦，并且直接 `return true`
    // 把这次按键吃掉 —— 微信根本看不到返回键，也就不会挪自己。
    //
    // ⚠️ **必须判定当前是不是在会话列表页**：微信的聊天页是挂在 LauncherUI 上的
    // Fragment（`com.tencent.mm.ui.chatting.ChattingUIFragment`），不是独立 Activity。
    // 不判定的话，在聊天页按返回会被我们拦掉 —— 用户回不到会话列表。
    // 判据是「会话列表 View 当前可见」（见 [conversationListVisible]）。

    private const val LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI"

    /** 同一按键可能被继承链上多层 hook 命中，去重用 */
    @Volatile
    private var backExitAt = 0L

    private fun hookBackExit(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val act = param.thisObject as? android.app.Activity ?: return
                if (act.javaClass.name != LAUNCHER_UI) return

                val ev = param.args.getOrNull(0) as? KeyEvent ?: return
                if (ev.keyCode != KeyEvent.KEYCODE_BACK) return
                // 只吃按下的那一次；长按/抬起放过，别把别的交互一起吞了
                if (ev.action != KeyEvent.ACTION_DOWN || ev.repeatCount != 0) return

                val now = android.os.SystemClock.uptimeMillis()
                if (now - backExitAt < 400L) return      // 同一按键，已被上面那层处理过
                if (currentGroupId() == NONE) return     // 收纳态：放行给微信
                if (!conversationListVisible()) return   // 不在会话列表（在聊天页等）：放行
                backExitAt = now

                LogX.i("K7 back: exit filter ${currentGroupId()}")
                setGroup(NONE)
                param.result = true                      // 吃掉这次按键
            }
        }
        hookUpChain(lpparam, "dispatchKeyEvent", cb)
    }

    /**
     * 沿「LauncherUI → … → Activity」整条继承链逐层挂同名方法。
     *
     * `XposedBridge.hookAllMethods(cls, ...)` **只扫 cls 自己声明**的方法，不递归子类，
     * 也不包含父类 —— 只挂 `LauncherUI` + `android.app.Activity` 会漏掉中间那些覆写层。
     */
    private fun hookUpChain(
        lpparam: XC_LoadPackage.LoadPackageParam,
        method: String,
        cb: XC_MethodHook,
    ) {
        var c: Class<*>? = runCatching { XposedHelpers.findClass(LAUNCHER_UI, lpparam.classLoader) }
            .getOrNull()
        var guard = 0
        while (c != null && guard++ < 15) {
            val n = runCatching { XposedBridge.hookAllMethods(c, method, cb) }
                .getOrNull()?.size ?: 0
            if (n > 0) LogX.i("K7: hooked ${c.name}.$method x$n")
            if (c.name == "android.app.Activity") break
            c = c.superclass
        }
    }

    /**
     * 会话列表当前是否可见。
     *
     * 用「装了我们包装的 ListView 里有没有一个是显示中的」来判定：
     * 聊天页盖上来之后，微信会把会话列表整块隐藏（或 detach），`isShown` 就是 false。
     * 取不到任何可见列表时**保守放行** —— 宁可让用户多按一次，也不能拦掉正常的返回。
     */
    private fun conversationListVisible(): Boolean {
        // 快照：wrappers 是 WeakHashMap，别的线程可能在改，别直接迭代
        for (list in ArrayList(wrappers.keys)) {
            val shown = runCatching { list.isShown && list.windowVisibility == View.VISIBLE }
                .getOrDefault(false)
            if (shown) return true
        }
        return false
    }

    private fun hookSetAdapter(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.widget.ListView", lpparam.classLoader, "setAdapter",
                ListAdapter::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = param.thisObject as? ListView ?: return
                        if (list.javaClass.name != LIST_CLASS) return
                        // 新的 HVL 里包的是新的 adapter，旧的包装作废
                        wrappers.remove(list)
                        mainHandler.post {
                            runCatching { ensure(list) }
                                .onFailure { LogX.w("K7 ensure failed: ${it.javaClass.simpleName}") }
                        }
                    }
                }
            )
            LogX.i("K7: hooked ListView.setAdapter")
        }.onFailure { LogX.w("K7: hook setAdapter failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 长按链路：在 `setOnItemLongClickListener` 上包一层，做位置重映射。
     *
     * ⚠️ 点击**不走这里**，也不走任何 hook —— 真机实证：会话行的点击由微信自己
     * 在 `getView` 时绑到行上（每行自己的 `OnClickListener`），`performItemClick`
     * **一次都不会被调到**。详见 [LongClickRemap] 的注释。
     */
    private fun hookListeners(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.widget.AdapterView", lpparam.classLoader,
                "setOnItemLongClickListener", AdapterView.OnItemLongClickListener::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = param.thisObject as? ListView ?: return
                        if (list.javaClass.name != LIST_CLASS) return
                        runCatching { wrapListeners(list) }
                    }
                }
            )
        }.onFailure { LogX.d("K7: hook setOnItemLongClickListener failed") }
    }

    /** 长按 listener 包装（点击由微信自己的行 OnClickListener 扛，见 [LongClickRemap]） */
    private fun wrapListeners(list: ListView) {
        // 我们自己 set 回去的那一次也会进 hook —— 必须挡住，否则无限递归（见 wrapping 注释）
        if (wrapping.get() == true) return
        wrapping.set(true)
        try {
            runCatching {
                // ⚠️ 对照实验开关（默认开）。关掉 = 完全不碰微信的长按链路。
                //
                // 为什么要它：真机发现「模块启用后微信自己的 4 项会话菜单不再弹出」，
                // 而 dump 证明微信那 4 项**不走 MenuBuilder.add**，所以不是被我们顶掉，
                // 而是被包装层挡住了 —— z2 内部会回头调 `getOnItemLongClickListener()`
                // （拿到的是我们的 wrapper），我们重入时返回 false，微信据此不弹菜单。
                // 关掉这一层就能验证：微信菜单是否恢复。
                if (!Kv.getBool(Keys.CHAT_CONV_LONGCLICK_REMAP)) {
                    // ⚠️ 光「不再包一层」不够：已经装上去的 wrapper 还在链上，
                    // 开关就形同没关（会得出「包装不是元凶」的错误结论）。
                    // 这里主动把它剥回微信的原始 listener。
                    val cur = list.onItemLongClickListener
                    if (cur is LongClickRemap) {
                        list.onItemLongClickListener = unwrapLongClick(cur)
                        LogX.i("K7 unwrap long (remap switch off)")
                    } else {
                        LogX.i("K7 wrap long SKIPPED (remap switch off, no wrapper)")
                    }
                    return
                }
                val long = unwrapLongClick(list.onItemLongClickListener)
                if (long != null) {
                    list.onItemLongClickListener = LongClickRemap(list, long)
                    LogX.i("K7 wrap long inner=${long.javaClass.name}")
                }
            }.onFailure { LogX.w("K7 wrap long failed ${it.javaClass.simpleName}") }
        } finally {
            wrapping.set(false)
        }
    }

    /**
     * 剥到最里层的原始 listener。
     *
     * 微信的 ConversationListView 自己覆写了这组 setter/getter（ANR 栈里能看到
     * `ConversationListView.setOnItemClickListener`），我们 getter 读回来的未必
     * 是自己上一次包的那层 —— 旧代码靠 `is ClickRemap` 判幂等，真机上失效，
     * 于是每 set 一次就套一层。这里统一剥到最里层再套，嵌套深度恒为 1。
     */
    private fun unwrapLongClick(
        l: AdapterView.OnItemLongClickListener?
    ): AdapterView.OnItemLongClickListener? {
        var cur = l
        var guard = 0
        while (cur is LongClickRemap && guard++ < 16) cur = cur.orig
        return cur
    }

    // ------------------------------------------------------------ 对外接口

    /** 当前筛选的分组 id（0 = 收纳态） */
    /**
     * A15 注入版用：ListView 的**原始 position**（含 header、含我们自己插的分组行）
     * → 该行的会话 wxid。
     *
     * 非会话行（header / 分组行 / 不是我们包装过的列表）一律返回 null ——
     * 注入菜单时靠这个把「长按的不是会话」的情况挡在外面，避免拿错人。
     */
    fun wxidAt(list: ListView, rawPosition: Int): String? {
        val fa = wrappers[list] ?: return null
        if (groupIdAt(list, rawPosition) != ConvLayout.Layout.NOT_A_GROUP) return null
        val real = realPosition(list, rawPosition)
        if (real < 0) return null
        // ⚠️ 必须实时取（wxidNow），不能用快照 wxidOf —— 快照虽然每次 rebuild 都会重读，
        // 但 rebuild 去抖的窗口里可能是旧顺序，会把「加入分组」加到别人头上。
        return fa.wxidNow(real - headerCount(list)).takeIf { it.isNotBlank() }
    }

    fun currentGroupId(): Long {
        if (cachedId < 0) refreshCache()
        return cachedId.coerceAtLeast(0)
    }

    /**
     * 列表「停在顶部」时 `child0.top` 的值（[headerAnchor]），null = 还没抓到。
     * [ScrollDock] 用它当「内容不允许再往下」的上限。
     */
    fun anchor(): Int? = headerAnchor

    /** 这个列表是不是已经装了我们的包装 adapter（[ScrollDock] 只在包装生效时干活） */
    fun wraps(v: View?): Boolean = v != null && wrappers.containsKey(v)

    /** 切换筛选：再点同一个分组 = 回到收纳态 */
    fun toggle(id: Long) {
        val cur = currentGroupId()
        setGroup(if (cur == id) NONE else id)
    }

    /**
     * 进入某个分组的筛选态（[NONE] 回到收纳态）。
     *
     * 注意：这里是**写库 + 通知重绘**，不直接操作 View —— 微信自己的 UI 线程
     * 才是列表的唯一所有者，跨线程碰 View 必崩。
     */
    fun setGroup(id: Long) {
        val db = GroupDb.current() ?: return
        val target = id.coerceAtLeast(0)
        db.setPref(PrefKeys.FILTER_GROUP, target.toString())
        cachedId = target
        cachedMembers = if (target > 0) {
            runCatching { db.memberWxids(target) }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        LogX.i("K7 setGroup id=$target members=${cachedMembers.size}")
        refresh()
    }

    /** 分组/成员数据变了（增删改分组、加删成员）之后调一次，让列表重算 */
    fun refresh() {
        mainHandler.post {
            if (rebuildPending) return@post
            rebuildPending = true
            mainHandler.postDelayed({
                rebuildPending = false
                eachWrapper { fa, list ->
                    rememberAnchor(list)
                    fa.rebuild()
                    // 复位放在 notify **之前**：此刻还没有「数据变化」，
                    // 微信不会把这次置位当成下拉手势。（放在 notify 之后 —— 无论是
                    // 同批还是延后 —— 都会把小程序面板整个展开，真机实测。）
                    //
                    // 注意还原的是**原锚点**而不是 0：见 [headerAnchor] 的注释，
                    // 拉到 0 会把微信的超高 header 顶回来，反而制造出一次"下拉"。
                    restoreAnchor(list)
                    fa.notifyDataSetChanged()
                }
            }, REBUILD_DEBOUNCE_MS)
        }
    }

    /**
     * 分组/成员关系变了（加删成员、改成员、恢复备份等）之后调一次，让列表重算。
     *
     * 与 [refresh] 的区别：成员增删**不改变**会话条数/顺序/开关/分组表，会撞上
     * [GroupedAdapter.rebuild] 的「配置指纹相同 → 短路」。这里分两件事补上：
     *
     * 1. 筛选态：重读 [cachedMembers]（当前筛选分组的成员集），让 configStamp 里的
     *    `cachedMembers.hashCode()` 变化，自然触发全量重建。
     * 2. 收纳态：递增 [membersVersion]，让每个 wrapper 的下一次 rebuild 强制重读
     *    各自的 [GroupedAdapter.memberSets]（成员集不在 configStamp 里，只能靠版本号破短路）。
     *
     * **方案 X（只重读 cachedMembers，不动 cachedId）**：cachedId 由 [setGroup] 独占管理
     * （写库 + 写缓存），不该被一个「数据变了」的通知改掉。若走 [refreshCache] 全量重读，
     * 会在 [setGroup] 写 `FILTER_GROUP` 的竞态窗口内读到旧值并覆盖新 cachedId（丢失更新）。
     * 这里 `currentGroupId()` 只在从未初始化（cachedId < 0）时懒读一次，属正常懒初始化，
     * 之后不再从库重读。
     */
    fun refreshMembers() {
        val id = currentGroupId()
        val db = GroupDb.current()
        cachedMembers = if (id > 0 && db != null) {
            runCatching { db.memberWxids(id) }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        membersVersion++
        // R24：分组/标签成员变了，免打扰的「作用域」快照也得跟着失效，
        // 否则名单里存 g:/t: 时，改完成员要等 30 秒（SCOPE_TTL_MS）才按新成员判定。
        // 全部 10 个成员写库入口都收敛到本方法，所以在这里一处就够。
        runCatching { com.lx.wxgroup.core.NotifyMute.invalidateScope() }
        refresh()
    }

    /**
     * 分组行的**摘要内容**变了，但会话顺序和成员关系都没变时调一次。
     *
     * 目前唯一的调用方是 [com.lx.wxgroup.core.MarkRead]：「标记已读」把会话对象上的
     * 未读字段清 0，**不动 conversationTime**。而轻量路径的判据
     * [GroupedAdapter.timeFingerprint] 只看 timeMs ⇒ 会被判成「完全没变」直接短路，
     * 分组行的未读角标就一直挂着，直到这个组来下一条消息。
     *
     * 与 [refreshMembers] 分开是因为语义不同：那个会重读 `cachedMembers`（成员关系变了），
     * 这个只让摘要重算（成员关系没变）。合在一起也不会错，但会让「标记已读」多查一次库。
     */
    fun refreshSummaries() {
        summariesVersion++
        refresh()
    }

    /**
     * A7：取当前会话列表里属于这些 wxid 的**微信会话对象**。
     *
     * 标记已读只能改微信自己的对象，模块造不出来，必须从 adapter 里拿。
     * 没聊过天的人没有会话对象，自然也不在结果里（他们本来也没有未读）。
     */
    fun convItemsOf(wxids: Set<String>): List<Any> {
        if (wxids.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Any>()
        eachWrapper { fa, _ ->
            for (conv in fa.itemsOf(wxids)) {
                val w = ConvReader.username(conv)
                if (w.isBlank() || !seen.add(w)) continue
                out.add(conv)
            }
        }
        return out
    }

    /** 确保这个列表已经装上包装（幂等） */
    fun ensure(list: ListView) {
        if (wrappers[list] != null) return
        runCatching { install(list) }
            .onFailure { LogX.w("K7 install failed: ${it.javaClass.simpleName}") }
    }

    private fun eachWrapper(block: (GroupedAdapter, ListView) -> Unit) {
        val snapshot = ArrayList(wrappers.entries)
        for ((list, fa) in snapshot) {
            runCatching { block(fa, list) }
                .onFailure { LogX.w("K7 rebuild failed: ${it.javaClass.simpleName}") }
        }
    }

    // ------------------------------------------------- 「停在顶部」的锚点
    //
    // ★ 这是「点分组行跳小程序面板 / 整页下移」的真凶修复 ★
    //
    // 对照实验（真机，G1 分别 252 人 / 2 人）：
    //
    //   252 人：筛选后 253 行 > 一屏 → child0.top=-2713 → 干净
    //   2   人：筛选后 18 项 == 一屏 → child0.top=-662  → 整页被 setTranslationY(2051)
    //
    // -2713 是「列表停在顶部」的正确值（= -(header 高度 3046 - 露出 333)）。
    // 而 -662 是我们自己用 `setSelectionFromTop(0, 0)` 把 header 硬顶回 y=0 造成的：
    // 列表等于从「已滚动 2713」退回「已滚动 662」，微信把这 2051 的跳变当成下拉手势。
    //
    // 所以：**重建前后保持 child0.top 不变**，微信看不到任何滚动变化，也就不会弹面板。

    /**
     * 重建前抓锚点：列表停在顶部（第一个 item 就是 header）时记下当前 top。
     *
     * ⚠️ **只认「藏得最到位」的那一次**（取更负的值），不能无条件覆盖：
     * `firstVisiblePosition == 0` 时 `child0.top` 的可动区间是 `[dock, 0]`
     * （dock ≈ -2713）。用户往下拉露出 header 之后停在 -1500 也是
     * `firstVisiblePosition == 0`，此时如果 [refresh] 跑一次，锚点就会被污染成
     * -1500，之后 [restoreAnchor] 和 [ScrollDock] 会把列表**永久钉在 header 露出
     * 1862px 的状态**。取 min 之后：污染值进不来；反过来就算先被污染了，
     * 下一次真正停在 -2713 时还能自愈。
     */
    private fun rememberAnchor(list: ListView) {
        runCatching {
            if (list.firstVisiblePosition != 0) return
            val c0 = list.getChildAt(0) ?: return
            if (c0.top >= 0) return
            val cur = headerAnchor
            if (cur == null || c0.top < cur) {
                headerAnchor = c0.top
                LogX.i("K7 anchor ${cur ?: "(first)"} -> ${c0.top}")
            }
        }
    }

    /**
     * 让列表重建后仍停在原来的位置。
     *
     * 用 `setSelectionFromTop(0, anchor)`：把 item 0（= 微信的顶级 header）钉在
     * `top = anchor` 上，`fillDown` 自然把会话行接着排在 `anchor + headerHeight`。
     * 等价于「什么都不滚」，而不是硬拉回 0。
     *
     * 列表当前不在顶部（用户滚下去了）时回到顶部即可 —— 此时也需要 anchor，
     * 用 [headerAnchor]。
     *
     * ⚠️ 锚点还没抓到时**什么都不做**，不要退回 0：`setSelectionFromTop(0, 0)`
     * 会把微信那个 3046px 的 header 整个顶出来，等于制造一次假下拉。
     */
    private fun restoreAnchor(list: ListView) {
        val anchor = headerAnchor ?: return
        runCatching { list.setSelectionFromTop(0, anchor) }
            .onFailure { LogX.d("K7: restoreAnchor n/a ${it.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------ 装包装

    private fun install(list: ListView): GroupedAdapter? {
        val hvl = runCatching { list.adapter }.getOrNull() as? HeaderViewListAdapter
        if (hvl == null) {
            // 不是 HVL 说明列表结构和预期不同：硬换会把微信的 header 全弄丢，宁可不做
            LogX.w("K7: adapter=${list.adapter?.javaClass?.name} not HeaderViewListAdapter, skip")
            return null
        }
        val inner = runCatching { hvl.wrappedAdapter }.getOrNull() ?: return null
        if (inner is GroupedAdapter) {
            wrappers[list] = inner
            return inner
        }

        val fa = GroupedAdapter(list, inner)
        val ok = runCatching { XposedHelpers.setObjectField(hvl, "mAdapter", fa) }.isSuccess
        if (!ok) {
            LogX.w("K7: cannot replace HeaderViewListAdapter.mAdapter")
            return null
        }

        // ListView 的 RecycleBin 是按「换 adapter 之前」的 viewTypeCount 建的回收池。
        // 我们的包装多了一个「分组行」类型 → 池子数量对不上，
        // 会把分组行当成会话行的 convertView 递进来（真机实测）→
        // 微信强转 convertView 直接抛，会话行整片空白。换完 adapter 立刻把
        // 池子按新的类型数量重建一次。
        runCatching {
            val recycler = XposedHelpers.getObjectField(list, "mRecycler")
            XposedHelpers.callMethod(recycler, "setViewTypeCount", hvl.viewTypeCount)
            LogX.i("K7: recycler viewTypeCount=${hvl.viewTypeCount}")
        }.onFailure { LogX.d("K7: reset recycler viewTypeCount n/a: ${it.javaClass.simpleName}") }

        // 规矩 1：ListAdapter 的观察者原本挂在微信的 adapter 上，必须挪到我们身上
        runCatching {
            val observer = XposedHelpers.getObjectField(list, "mDataSetObserver") as? DataSetObserver
            if (observer != null) {
                inner.unregisterDataSetObserver(observer)
                fa.registerDataSetObserver(observer)
                LogX.i("K7: observer moved to GroupedAdapter")
            }
        }.onFailure { LogX.w("K7: move observer failed ${it.javaClass.simpleName}") }

        // 微信自己刷新会话（新消息/置顶变化）时，要能透到我们这层
        runCatching {
            inner.registerDataSetObserver(object : DataSetObserver() {
                override fun onChanged() {
                    // R34 取证 + P2：微信数据已变的这一刻，`inner.count` 与我们的 `snaps`
                    // 是否还同步 / 是不是「行数突变」。
                    runCatching {
                        val nc = inner.count
                        if (nc != fa.snapSize) {
                            LogX.i("K7 innerChanged count=$nc snaps=${fa.snapSize} layout=${fa.size}")
                        }
                        // ★ R34 P2：行数突变（折叠/展开 ±19）时【即时】对齐，不等 250ms。
                        //
                        // 为什么必须即时：ListView 的 DataSetObserver 已被我们挪到本包装
                        // adapter 上（规矩 1），微信自己那次 notify 【不会】让列表重排 ——
                        // 屏幕上的行数只在「我们 notify」时才变。折叠时若等到 250ms 去抖
                        // 才把 19 行去掉，用户看到的就是「点了之后停住，0.25s 后整屏往
                        // 上滑一大截」。即时对齐把这段位移拉回微信改数据的同一帧。
                        //
                        // ⚠️ 这里【不】判折叠方向：方向仍归 scanPinFold 的扫文字（R31/R32
                        // 已真机验证），本处只负责「行数别撒谎」。
                        if (nc > 0 && kotlin.math.abs(nc - fa.snapSize) >= COUNT_JUMP) {
                            LogX.i("K7 innerJump count=$nc snaps=${fa.snapSize}")
                            runCatching { fa.alignNow() }
                        }
                    }
                    // 去抖重建：微信收消息时会高频 notify，直接 rebuild 会把
                    // 2900 条的 readSnaps + 分组行摘要全压在主线程上。
                    // 走 onDataChanged（轻量去抖，不碰滚动锚点）。
                    runCatching { fa.onDataChanged() }
                }

                override fun onInvalidated() {
                    runCatching { fa.notifyDataSetInvalidated() }
                }
            })
        }.onFailure { LogX.w("K7: forward observer failed ${it.javaClass.simpleName}") }

        wrappers[list] = fa
        runCatching { wrapListeners(list) }
        rememberAnchor(list)

        // R31：「置顶聊天」折叠态靠**扫已布局文字**判（原因见 [GroupedAdapter.pinFoldedByView]）。
        // 状态翻转时触发一次轻量重建，置顶分组行就跟着微信一起收放。
        // 只扫文字不改 UI —— 判不出（null）时保持上一次结论，首次判不出就按展开处理。
        runCatching {
            // 扫一次「折叠置顶聊天 / N个置顶聊天」文字并推进状态。
            fun scanPinFold() {
                fa.pinScanMs = android.os.SystemClock.uptimeMillis()
                var folded: Boolean? = null
                walkTexts(list) { t ->
                    if (t == "折叠置顶聊天") folded = false
                    else if (t.length <= 12 && t.endsWith("个置顶聊天")) folded = true
                }
                if (folded != null && folded != fa.pinFoldedByView) {
                    fa.pinFoldedByView = folded
                    LogX.i("K7 pinFold(view) folded=$folded -> immediate")
                    runCatching { fa.onPinFoldChanged(folded) }
                }
            }
            list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val now = android.os.SystemClock.uptimeMillis()
                if (now - fa.pinScanMs < PIN_FOLD_SCAN_MS) {
                    // ⚠️ 节流窗口内**排一次补扫，而不是丢掉**（R32 真机踩到）：
                    // 冷启时微信先渲染展开态、100ms 内再切成折叠态，被丢的那次正是最终态，
                    // 内部状态就永久停在展开（折叠态下分组行插到置顶区上面）。
                    // 补扫放在窗口末尾，同一窗口只排一个。
                    if (!fa.pinScanQueued) {
                        fa.pinScanQueued = true
                        mainHandler.postDelayed({
                            fa.pinScanQueued = false
                            runCatching { scanPinFold() }
                        }, PIN_FOLD_SCAN_MS)
                    }
                    return@addOnLayoutChangeListener
                }
                scanPinFold()
            }
        }.onFailure { LogX.w("K7 pinFold listener failed: ${it.javaClass.simpleName}") }

        fa.rebuild()
        restoreAnchor(list)
        fa.notifyDataSetChanged()
        LogX.i("K7 installed inner=${inner.javaClass.name} rows=${fa.size}")
        return fa
    }

    private fun refreshCache() {
        val db = GroupDb.current() ?: return
        val id = db.getPref(PrefKeys.FILTER_GROUP, NONE.toString()).toLongOrNull() ?: NONE
        cachedId = id.coerceAtLeast(0)
        cachedMembers = if (cachedId > 0) {
            runCatching { db.memberWxids(cachedId) }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
    }

    // ------------------------------------------------------------ 位置重映射

    /** 列表位置（含 header 偏移）-> 微信原始位置；分组行返回 -1 */
    private fun realPosition(list: ListView, position: Int): Int {
        val fa = wrappers[list] ?: return position
        val headers = headerCount(list)
        val real = fa.layout.convIndexOf(position - headers)
        if (real < 0) return -1
        return headers + real
    }

    /** 这个位置是不是分组行；是的话返回分组 id，否则 [NOT_A_GROUP] */
    private fun groupIdAt(list: ListView, position: Int): Long {
        val fa = wrappers[list] ?: return ConvLayout.Layout.NOT_A_GROUP
        return fa.layout.groupIdAt(position - headerCount(list))
    }

    private fun headerCount(list: ListView): Int {
        val hvl = runCatching { list.adapter }.getOrNull() as? HeaderViewListAdapter ?: return 0
        return runCatching { hvl.headersCount }.getOrDefault(0)
    }

    /**
     * 长按 listener 包装。
     *
     * ⚠️ **点击不在这里做，也不在任何 hook 里做** —— 真机实证（`_dbg_pull.txt`）：
     * 会话行的点击由微信自己在 `getView` 时绑到行上，走的是每行自己的
     * `OnClickListener`，**`performItemClick` 一次都不会被调到**。
     *
     * 所以这里只包长按。曾经在 `AdapterView` / `ListView` / `ConversationListView`
     * 三层的 `performItemClick` 上都挂过点击重映射，真机日志里
     * `K7 performClick conv` **零命中** —— 是纯死代码，已删除（2026-10-25）。
     *
     * 由此推出一条重要结论：**改位置映射（`realPosition`）治不了「点错人」**。
     * 点击位置由微信自己按它当前的列表算，我们能控制的只有 `layout` 本身 ——
     * 必须保证 `layout` 不漏重建，行内容与会话的对应关系才不会错位。
     */
    private class LongClickRemap(
        private val list: ListView,
        /** 拆包用（见 [unwrapLongClick]） */
        val orig: AdapterView.OnItemLongClickListener,
    ) : AdapterView.OnItemLongClickListener {
        override fun onItemLongClick(
            parent: AdapterView<*>?, view: View?, position: Int, id: Long
        ): Boolean {
            // ⚠️ 重入守卫（没有它微信必崩）。
            //
            // 微信的 `com.tencent.mm.ui.conversation.z2`（它的长按监听实现）在
            // onItemLongClick **内部**会回头调 `list.getOnItemLongClickListener()`
            // —— 拿到的正是我们这一层。于是 wrapper → z2 → wrapper → z2 无限套娃，
            // 主线程栈溢出，微信直接崩（真机 crash 栈里 500+ 帧全是这两行交替）。
            //
            // `unwrapLongClick` 只解决「set 的时候别套多层」，解决不了这种
            // **调用期**的回环：只要我们把 z2 包在里面，z2 一回调就必然再进来。
            // 所以这里按线程记一次「我已经在处理了」，重入不再转发（否则就是一个
            // wrapper→z2→wrapper 的死循环，主线程栈溢出崩微信）。
            //
            // ⚠️ 重入必须返回 **false**（未消费）。
            // 早期这里返回 true，真机实测的后果是**微信的会话长按菜单再也不弹了**
            // （z2 拿到 true 就认为「外层已经处理了」，直接跳过自己弹菜单的逻辑）。
            // 返回 false 才让 z2 继续走它自己的菜单流程。
            if (inLongClick.get() == true) {
                LogX.i("K7 longclick REENTRY pos=$position -> false")
                return false
            }
            inLongClick.set(true)
            return try {
                val headers = headerCount(list)
                val gid = groupIdAt(list, position)
                val real = realPosition(list, position)
                // 长按链路的每一步都要留痕：菜单弹不出来时，光看现象分不清是
                // 「分组行自己吃了」「位置换算成 -1 被吞了」还是「微信压根没弹」。
                LogX.i(
                    "K7 longclick conv pos=$position headers=$headers gid=$gid real=$real " +
                        "wxid=${wrappers[list]?.wxidOf(real - headers)} size=${wrappers[list]?.size}"
                )
                // 分组行：自己消费掉，不转交微信（A5 菜单由 GroupRowView 的长按自己弹）
                if (gid != ConvLayout.Layout.NOT_A_GROUP) {
                    LogX.i("K7 longclick group row gid=$gid -> consumed")
                    return true
                }
                if (real < 0) {
                    // 位置换算不出来（header 区 / 越界）：原样转交微信，不干预，
                    // 也不自作主张消费 —— 拿错误 position 交给微信会对错的会话弹菜单。
                    LogX.w("K7 longclick real<0 (pos=$position headers=$headers) -> passthrough")
                    return runCatching { orig.onItemLongClick(parent, view, position, id) }
                        .getOrDefault(false)
                }
                // A15 注入版：这里**不弹任何菜单**，一律原样转交微信。
                // 我们的项是在 `View.createContextMenu` 里塞进**微信原生菜单**的
                // （见 [ConvMenuInject]），长按链路保持完全透明 —— 微信原生的
                // 「删除 / 置顶 / 免打扰」全部保留。
                //
                // ⚠️ 必须**原样透传**微信的返回值（z2 返回 false），绝不能兜成 true：
                // 真机实测微信的会话菜单正是靠 AOSP `AbsListView.performLongPress` 里的
                // `if (!handled) showContextMenuForChild(...)` 这条兜底弹出来的，
                // 我们一旦返回 true，兜底不执行，菜单就不弹了。
                val r = runCatching { orig.onItemLongClick(parent, view, real, id) }
                    .onFailure { LogX.w("K7 longclick orig threw: ${it?.javaClass?.simpleName}") }
                    .getOrDefault(false)
                LogX.i("K7 longclick orig returned=$r -> passthrough")
                return r
            } finally {
                inLongClick.set(false)
            }
        }
    }

    // ------------------------------------------------------------ 包装 adapter

    /**
     * 包住微信的会话 adapter，按 [ConvLayout] 算出来的行序列转发。
     *
     * 没任何分组时行序列就是 `0..n-1` 的恒等表，输出与微信原样一致 ——
     * 也就是说**默认状态下这一层是零改动**的。
     */
    private class GroupedAdapter(
        private val list: ListView,
        private val wrapped: ListAdapter,
    ) : BaseAdapter() {

        /** 合成后的行序列（会话行 or 分组行） */
        @Volatile
        var layout: ConvLayout.Layout = ConvLayout.Layout(emptyList())
            private set

        /** 会话快照：wxid + 时间。**下标必须与微信 adapter 一一对应**，否则点错人 */
        @Volatile
        private var snaps: List<ConvLayout.ConvEntry> = emptyList()

        /** 分组 id -> 分组行显示内容（rebuild 时算好，getView 直接用） */
        @Volatile
        private var models: Map<Long, GroupRowView.Model> = emptyMap()

        /**
         * 收纳态最近一次完整重建时读到的成员集合（分组 id -> 成员 wxid）。
         *
         * 「仅 timeMs/unread 变」的轻量路径里 models 重算要复用它，避免每帧都
         * `db.memberWxids(g.id)` 查库（收纳态分组多时是 N 次 SQL 查询）。
         *
         * 成员集合在**完整重建**时刷新。成员增删改必须走 [ConvFilter.refreshMembers]
         * 而非 [ConvFilter.refresh]：refresh 不重读 cachedMembers（筛选态），且成员变化
         * 不落在 configStamp 的任何一项（收纳态）—— 只有 refreshMembers 递增
         * [ConvFilter.membersVersion] 才能破掉「配置指纹相同」的短路，让下一次 rebuild
         * 强制重读这里的 memberSets。
         */
        @Volatile
        private var memberSets: Map<Long, Set<String>> = emptyMap()

        private val lock = Any()

        /** 上次 rebuild 的输入指纹；输入没变就直接复用，避免微信连续 notify 时反复全量重算 */
        private var lastStamp = ""

        /**
         * R31：「置顶聊天」折叠态 —— 由**扫已布局文字**得来（null = 还没扫到过）。
         *
         * 为什么需要 sweep 而不是只看数据：折叠行究竟是不是 adapter item 目前无法静态
         * 定论（可能是微信自己的 header，那就不进 `wrapped.getItem`）。扫文字是唯一
         * 「照用户能看见的事实」判的办法，数据层判据只作为它扫不到时的兜底。
         */
        @Volatile
        var pinFoldedByView: Boolean? = null

        /** 折叠态扫描的节流时间戳（布局回调可能密集） */
        var pinScanMs = 0L

        /**
         * 节流窗口内是否已经排了一次「补扫」（R32）。
         *
         * 节流窗口里**不能直接丢掉**扫描：冷启时微信会先渲染展开态、100ms 内再切成折叠态，
         * 被丢的那次正是最终态 ⇒ 内部状态永久停在展开，折叠态下分组行会被插到置顶区上面。
         */
        var pinScanQueued = false

        /** 上次真正传给 ConvLayout.build 的折叠态：只在变化时打一条日志，避免刷屏 */
        var pinApplied: Boolean? = null

        /** 上次 rebuild 的快照时间指纹：区分「仅 timeMs 变」与「完全没变」 */
        private var lastTimeFp = 0L

        /** 上次 rebuild 时消费到的成员版本号（见 [membersVersion]）；初始 -1 保证首次重建也强制 */
        private var lastMembersVersion = -1

        /** 上次 rebuild 时消费到的摘要版本号（见 [summariesVersion]） */
        private var lastSummariesVersion = 0

        /**
         * 上次完整重建（收纳态、有分组）时的分组 spec，供折叠快路径复用。
         *
         * R32：折叠翻转只重算 layout，不该重读分组表。这个缓存保证 [lastSpecs] 与
         * [memberSets] 来自同一次完整重建，不会出现「specs 有、memberSets 无」的错位。
         * **空列表 = 无分组行可收放**（开关关 / 筛选态 / 无分组），折叠快路径据此直接
         * return —— 这几种情况下 layout 里根本没有分组行，折叠态不影响渲染。
         */
        @Volatile
        private var lastSpecs: List<ConvLayout.GroupRowSpec> = emptyList()

        /** observer 去抖标记（主线程读写，配合 mainHandler.post 串行） */
        private var observerRebuildQueued = false

        val size: Int get() = layout.size

        /**
         * R34 取证用：当前快照条数（它才是 `wrapped.count` 的镜像，
         * [size] 是收纳后的行数，两者天然不等）。
         * 只读、无副作用，不参与任何判据。
         */
        val snapSize: Int get() = snaps.size

        /** 原始会话下标 -> wxid（诊断用：核对点击有没有点错人） */
        fun wxidOf(convIndex: Int): String = snaps.getOrNull(convIndex)?.wxid ?: ""

        /**
         * 原始会话下标 -> wxid，**实时**读微信 adapter。
         *
         * ⚠️ 需要「现在这一行的真实 wxid」时必须用这个，不能用 [wxidOf]：
         * [snaps] 现在虽然每次 rebuild 都会重读（见 [rebuild]），但 rebuild 是
         * **去抖**的 —— 收消息到重算之间有至多 [REBUILD_DEBOUNCE_MS] 的窗口，
         * 期间快照可能是旧顺序，「长按加分组」这种一步都不能错的场景仍要实时取。
         * 这里单次 `getItem` 读一次会话对象，主线程开销可忽略。
         */
        fun wxidNow(convIndex: Int): String = runCatching {
            ConvReader.username(wrapped.getItem(convIndex))
        }.getOrDefault("")

        /** A7 标记已读用：这些 wxid 在列表里对应的微信会话对象 */
        fun itemsOf(wxids: Set<String>): List<Any> {
            if (wxids.isEmpty()) return emptyList()
            val out = ArrayList<Any>(wxids.size)
            // ⚠️ 实时遍历，不能按 [snaps] 的下标取：快照顺序可能已过期（收消息重排后
            // 条数不变），按旧下标 getItem 会拿到**别人**的会话对象，标记已读就标错了。
            val n = wrapped.count
            for (i in 0 until n) {
                val item = runCatching { wrapped.getItem(i) }.getOrNull() ?: continue
                if (ConvReader.username(item) !in wxids) continue
                out.add(item)
            }
            return out
        }

        /**
         * 视图类型编号。
         *
         * 分组行**必须单独占一个类型**：ListView 的类型复用的是「同类型才能
         * 回收给同类型」，串类型会把分组行的内容复用到会话行上（真机踩过）。
         */
        private val groupType: Int
            get() = runCatching { wrapped.viewTypeCount }.getOrDefault(1).coerceAtLeast(1)

        /**
         * @return **true = 有东西变了，调用方需要 `notifyDataSetChanged()`**；
         *         false = 输入指纹与时间指纹都没变，一行都没动，不必 notify。
         *
         * R34（P1）：`notifyDataSetChanged()` 会强制 ListView 重排 + 整屏 `getView` 重绑，
         * 收新消息时它每 250ms 一次全打在已停住的列表上 —— 那既是无谓的主线程开销
         * （「顿」），也是「我们多出来的一次位移」的载体。这里把「有没有真变」报出去，
         * 让 [onDataChanged] 只在真变时才 notify。
         *
         * ⚠️ false 只由「完全没变」那一条分支返回（配置指纹相同 + 顺序没变 + 时间指纹
         * 相同 + 摘要版本没变 + 成员版本没变），语义上就是「这次 rebuild 什么都没改」，
         * 漏刷新的风险为零。其余两条分支（light / full）都返回 true。
         */
        fun rebuild(): Boolean = synchronized(lock) {
            // ★ R22 性能探针：把 readSnaps 与整次 rebuild 的耗时量出来。
            //
            // 为什么必须量：R1 修复把「只在条数变化时读快照」改成了「**每次** rebuild
            // 都 readSnaps(n)」，而 readSnaps 对 2900 项逐条 `wrapped.getItem(i)` +
            // 两次反射读字段。旧代码里这条路径是冷路径（条数变化才走），现在是
            // **每 250ms 最多一次**的主线程热路径。getItem 的实现体在微信 dex 里，
            // 静态判不出成本 —— 只能用真机数据裁决。
            //
            // 判据：单次 readSnaps > 8ms 就该退化成「先比 count + 首末 wxid，
            // 不符才全量读」的两段式（代价是中部局部重排可能漏检）。
            //
            // ⚠️ 只在超阈值时打日志：rebuild 每秒最多 4 次，每次都打会把日志刷爆
            //（日志环形缓冲 5000 行，刷爆会把真正有用的取证日志挤掉）。
            val tRebuild = android.os.SystemClock.uptimeMillis()
            val n = runCatching { wrapped.count }.getOrDefault(0)
            val db = GroupDb.current()
            val enabled = runCatching { Kv.getBool(Keys.CHAT_GROUP_ROW_ENABLED) }.getOrDefault(true)
            // 未读角标开关：整个 rebuild 只读这一次（每行读会把滚动变成查库）
            val showUnread = runCatching { Kv.getBool(Keys.CHAT_SHOW_UNREAD) }.getOrDefault(true)
            val filterId = currentGroupId()

            // ⚠️ 指纹**必须包含分组本身**：置顶 / 重命名 / 删分组改的是分组表，
            // 会话数和成员集都没变 —— 少了这一项，改完置顶列表纹丝不动
            // （真机实测：DB 里 pinned 已经是 1，分组行还留在原位）。
            // Group 是 data class，hashCode 覆盖 id/name/pinned/sortOrder，够用了。
            val groups = if (db == null) emptyList()
            else runCatching { db.listGroups() }.getOrDefault(emptyList())

            // ⚠️ 指纹**必须带上 showUnread**：只改这个开关时会话数/分组都没变，
            // 少了它会被下面的「指纹相同直接返回」短路掉，表现为改了开关列表纹丝不动。
            // 这 6 项是「配置指纹」，不含会话顺序/时间（那些用 orderChanged + timeFingerprint 单独判）。
            // ⚠️ 折叠态**必须**进指纹（R31 真机踩过）：折叠/展开时会话顺序、条数、分组全没变，
            // 少了它就会被下面的「指纹相同直接返回」短路掉，日志明明打了
            // `pinFold(view) folded=true -> rebuild`，置顶分组行却纹丝不动。
            val configStamp = PinFoldLogic.configStamp(
                n = n,
                enabled = enabled,
                filterId = filterId,
                showUnread = showUnread,
                membersHash = cachedMembers.hashCode(),
                groupsHash = groups.hashCode(),
                pinFolded = pinFoldedByView,
            )

            // ★ R1 修复：每次都读最新快照。旧代码只在「条数变化」时才读
            // （`if (snaps.size != n) snaps = readSnaps(n)`），但收新消息导致
            // **重排而条数不变**时快照就永久冻结 —— layout 冻结 → 点错人。
            // 这里先读新快照，再判断「顺序有没有变」决定要不要重算 layout。
            val tSnaps = android.os.SystemClock.uptimeMillis()
            val fresh = readSnaps(n)
            val snapsCost = android.os.SystemClock.uptimeMillis() - tSnaps

            // 顺序变了（wxid 序列变了 / 条数变了）⇒ layout 必须重算。
            val orderDirty = fresh.size != snaps.size || ConvLayout.orderChanged(snaps, fresh)

            // 成员关系版本变了（refreshMembers 触发）⇒ 收纳态 memberSets 得重读，
            // 走全量重建破掉「配置指纹相同」的短路。
            val membersDirty = membersVersion != lastMembersVersion

            // 摘要版本变了（refreshSummaries 触发，目前只有「标记已读」用）⇒ 跳过下面的
            // 「时间指纹相同就 return」，强制重算一次 models。标记已读只改 unread、
            // 不改 timeMs，光靠 timeFingerprint 判不出来。
            val summariesDirty = summariesVersion != lastSummariesVersion

            if (!membersDirty && !orderDirty && configStamp == lastStamp && snaps.isNotEmpty()) {
                // 配置和顺序都没变。剩下唯一的可能变化是 timeMs/unread（分组行摘要级），
                // 用时间指纹区分「摘要变了」与「完全没变」。
                val fp = timeFingerprint(fresh)
                if (fp == lastTimeFp && !summariesDirty) {
                    probeCost(tRebuild, snapsCost, n, "short-circuit")
                    return@synchronized false      // R34 P1：真的什么都没变 ⇒ 不必 notify
                }
                snaps = fresh
                lastTimeFp = fp
                lastSummariesVersion = summariesVersion
                // layout 复用，只重算分组行摘要（models）。成员集合复用上一次的
                // （见 [memberSets] 注释：成员变化都走完整重建，这里不查库）。
                if (filterId == NONE && db != null && enabled) {
                    rebuildModels(db, groups, memberSets, showUnread)
                }
                probeCost(tRebuild, snapsCost, n, "light")
                return@synchronized true
            }

            snaps = fresh
            lastStamp = configStamp
            lastTimeFp = timeFingerprint(fresh)
            lastMembersVersion = membersVersion
            lastSummariesVersion = summariesVersion

            layout = when {
                db == null || !enabled -> {
                    // 开关关掉 / DB 没就绪 = 零改动，一条会话都不能少
                    memberSets = emptyMap()
                    models = emptyMap()
                    lastSpecs = emptyList()
                    ConvLayout.build(snaps, emptyList(), emptyMap(), enabled = false)
                }
                filterId > 0 -> {
                    memberSets = emptyMap()
                    models = emptyMap()
                    lastSpecs = emptyList()
                    ConvLayout.filtered(snaps, cachedMembers)
                }
                else -> {
                    if (groups.isEmpty()) {
                        memberSets = emptyMap()
                        models = emptyMap()
                        lastSpecs = emptyList()
                        ConvLayout.build(snaps, emptyList(), emptyMap(), enabled = false)
                    } else {
                        val specs = groups.map {
                            ConvLayout.GroupRowSpec(it.id, it.name, it.pinned, it.sortOrder)
                        }
                        lastSpecs = specs
                        val members = LinkedHashMap<Long, Set<String>>()
                        for (g in groups) {
                            members[g.id] = runCatching { db.memberWxids(g.id) }.getOrDefault(emptySet())
                        }
                        memberSets = members
                        rebuildModels(db, groups, members, showUnread)
                        // R31：置顶分组行要跟着微信「折叠置顶聊天」一起收放。
                        // 判据优先用「扫已布局文字」（最接近用户看到的事实），
                        // 扫不到（还没布局过 / 微信换了文案）再退回数据层判据。
                        val pf = pinFoldedByView ?: PinFoldLogic.detectPinFolded(snaps)
                        if (pf != pinApplied) {
                            pinApplied = pf
                            LogX.i("K7 pinFold apply folded=$pf src=${if (pinFoldedByView != null) "view" else "data"}")
                        }
                        ConvLayout.build(
                            snaps, specs, members, enabled = true,
                            pinFolded = pf,
                        )
                    }
                }
            }
            probeCost(tRebuild, snapsCost, n, "full")
            // R34 取证：把全量重建里 readSnaps 与「其余（ConvLayout.build + rebuildModels
            // 的 N 次 SQL + nameOf 查表）」拆开量。`total - snapsCost` 才是 build 的真实成本，
            // 用来裁决展开 51–55ms 里那 45ms 到底是不是 ConvLayout 的 O(n)（候选 ④b）。
            LogX.i(
                "K7 full rebuild n=$n snaps=${snapsCost}ms" +
                    " total=${android.os.SystemClock.uptimeMillis() - tRebuild}ms" +
                    " layout=${layout.size}"
            )
            true
        }

        /**
         * R22 性能探针：只在超阈值时打日志。
         *
         * 阈值取 8ms（≈ 半帧 @60Hz 的一半）。低于它的都算健康，不打日志 ——
         * rebuild 每秒最多 4 次，每次都打会把 5000 行环形日志刷爆，把真正有用的
         * 取证日志挤掉。高于它才是「需要退化成两段式」的信号。
         *
         * `path` 说明这次走的是哪条分支（short-circuit / light / full），
         * 拿真机日志时能一眼看出耗时花在哪。
         */
        private fun probeCost(tStart: Long, snapsCost: Long, n: Int, path: String) {
            val total = android.os.SystemClock.uptimeMillis() - tStart
            if (total < PROBE_WARN_MS && snapsCost < PROBE_WARN_MS) return
            LogX.i("K7 perf n=$n snaps=${snapsCost}ms total=${total}ms path=$path")
        }

        /** 收纳态：用给定成员集合重算每个分组行的显示内容（摘要/时间/未读）。 */
        private fun rebuildModels(
            db: GroupDb,
            groups: List<Group>,
            members: Map<Long, Set<String>>,
            showUnread: Boolean,
        ) {
            val newModels = HashMap<Long, GroupRowView.Model>(groups.size)
            for (g in groups) {
                newModels[g.id] = buildModel(db, g, members[g.id].orEmpty(), showUnread)
            }
            models = newModels
        }

        /**
         * 快照的时间指纹：只累加 timeMs，用来区分「仅摘要变了」和「完全没变」。
         *
         * 不参与 [ConvLayout.orderChanged]（那是判 wxid 顺序，决定 layout 要不要重算），
         * 这里只回答「分组行该显示的时间/未读有没有变」。纯 Long 运算，2900 项一遍过，
         * 不分配对象。
         *
         * ⚠️ **这个指纹有盲区，不是万能的**：`unread` / `digest` 不在 [readSnaps] 的产出里，
         * 只能拿 timeMs 当代理。
         * - 「收新消息」⇒ 微信 bump `conversationTime` ⇒ 覆盖得到 ✅
         * - 「**标记已读**」⇒ unread 清 0 但 **timeMs 不动** ⇒ 指纹不变 ⇒ 被判成"完全没变"，
         *   分组行未读角标会一直挂着。**这不是边缘场景，是高频日常操作。**
         *   所以 [ConvFilter.refreshSummaries] 专门维护 [summariesVersion] 来破这个短路 ——
         *   任何「只改 unread 不改时间」的入口都必须走那条，别指望这个指纹。
         *
         * 没有直接把 unread 并进指纹：那要给 [readSnaps] 的每个 entry 加字段，等于把
         * 2900 次 `ConvReader.unread` 反射读压进每 250ms 一次的主线程路径，不划算。
         */
        private fun timeFingerprint(snaps: List<ConvLayout.ConvEntry>): Long {
            var h = 1125899906842597L
            for (e in snaps) h = h * 31 + e.timeMs
            return h
        }

        /**
         * R34 P2：微信行数突变时的**即时对齐**（由 `inner` 的 observer 同步调用）。
         *
         * 与 [onDataChanged] 的区别只是「不等 250ms」，本体仍复用 [rebuild] 的全部
         * 分支逻辑（开关关 / 筛选态 / 无分组 / 折叠方向），不另写一套 —— 这样不会
         * 出现「passthrough 与 filtered 走错分支」这类新错误。
         *
         * 代价：真机实测一次全量 `rebuild()` 只要 9ms（`K7 perf ... total=9ms path=full`），
         * 远低于一帧，可以放在微信 notify 的同一帧里做。
         *
         * ⚠️ 不设 `observerRebuildQueued`、也不取消 [onDataChanged] 已排下的那次：
         * 那次是 models / memberSets 的兜底刷新，必须保留。它会在 250ms 后再算一遍
         * 同样的行数（[rebuild] 的 `changed` 由 P1 决定要不要 notify），不会带来
         * 第二次行数变化 —— 因为 [snaps] 到这里已经对齐了。
         */
        fun alignNow() {
            val changed = runCatching { rebuild() }.getOrDefault(false)
            LogX.i("K7 alignNow snaps=${snaps.size} layout=${layout.size} changed=$changed")
            if (changed) runCatching { notifyDataSetChanged() }
        }

        /**
         * 微信数据变了（收消息 / 置顶变化）时的去抖重建。
         *
         * 为什么**不走 [ConvFilter.refresh]**：refresh 里 `rememberAnchor` +
         * `restoreAnchor` 会用 `setSelectionFromTop(0, anchor)` 把列表滚回顶部，
         * 那是给「用户主动操作（切分组/标记已读）」用的；收消息时用户可能正滚在
         * 列表中段，滚回顶部会打断阅读。这里只去抖 + 重算 + notify，不碰滚动位置。
         * 去抖复用一个 [ConvFilter.mainHandler] 和 [ConvFilter.REBUILD_DEBOUNCE_MS]，
         * 不新造 Handler。
         */
        fun onDataChanged() {
            mainHandler.post {
                if (observerRebuildQueued) return@post
                observerRebuildQueued = true
                mainHandler.postDelayed({
                    observerRebuildQueued = false
                    val b = layout.size
                    // R34 P1：rebuild 报「没变」就不再 notify。
                    // `getOrDefault(true)` 是保险：rebuild 万一抛了，退回原来的无条件刷新，
                    // 宁可多刷一次也不能漏刷。
                    val changed = runCatching { rebuild() }.getOrDefault(true)
                    // R34 取证：这 250ms 去抖的那一次重建有没有【改行数】。
                    // `layoutBefore != layoutAfter` ⇒ 折叠/展开过程中行数第二次变化
                    // ⇒ 那一次 notify 会带来一段额外的位移（「上滑」的候选来源）。
                    LogX.i(
                        "K7 debounce count=${runCatching { wrapped.count }.getOrDefault(-1)}" +
                            " snaps=${snaps.size} layoutBefore=$b layoutAfter=${layout.size} changed=$changed"
                    )
                    if (changed) runCatching { notifyDataSetChanged() }
                }, REBUILD_DEBOUNCE_MS)
            }
        }

        /**
         * 折叠态翻转的**即时路径**（R32）。
         *
         * 为什么不能走 [onDataChanged]：那条有 250ms 去抖（为「收新消息」的高频
         * notify 设计），而折叠/展开是用户主动操作，延迟 250ms 表现为「慢半拍 +
         * 微信折叠动画结束后分组行才二次跳动」。
         *
         * 为什么不能走 [ConvFilter.refresh]：refresh 会 [ConvFilter.rememberAnchor] +
         * [ConvFilter.restoreAnchor]（`setSelectionFromTop` 把列表钉回顶部），折叠时
         * 列表本就停在顶部（折叠入口在最上），不该再碰滚动位置 ——
         * `setSelectionFromTop(0,0)` 会把微信 3046px header 顶出来制造假下拉
         * （历史坑）。这里只重算 layout + notify，不碰任何滚动锚点，用户无论滚在
         * 哪里都不会被弹回顶部。
         */
        fun onPinFoldChanged(folded: Boolean) {
            mainHandler.post {
                // R34 取证：折叠前后 `child0.top` 的位移量。
                //  · 位移 ≈ 19 × 行高 ⇒ 行数变化造成的（主假设）
                //  · 位移是非行高整数倍 ⇒ ScrollDock / correctTooHigh 的整块平移（候选 ④a）
                // notify 只请求重排，不同步生效，所以 60ms 后再读一次「落定值」。
                // 纯日志，无副作用。
                val top0 = {
                    runCatching {
                        "top=${list.getChildAt(0)?.top} first=${list.firstVisiblePosition}"
                    }.getOrDefault("top=n/a")
                }
                val t0 = top0()
                runCatching { rebuildForPinFold(folded) }
                runCatching { notifyDataSetChanged() }
                LogX.i("K7 pinFold notify folded=$folded before[$t0] after[${top0()}]")
                mainHandler.postDelayed({
                    LogX.i("K7 pinFold settled folded=$folded ${top0()}")
                }, 60L)
            }
        }

        /**
         * 折叠快路径的重建本体（只重算 layout，不重读快照 / 不查库 / 不重算 models）。
         *
         * [PinFoldLogic.foldAction] 判三种动作：
         *  - [PinFoldLogic.FoldAction.NOOP]：没翻转，直接返回（布局回调可能重复报同方向）。
         *  - [PinFoldLogic.FoldAction.RELAYOUT_REUSE]：count 未变，快照复用，只换 layout
         *    （O(n) 纯计算，无反射、无 SQL）。
         *  - [PinFoldLogic.FoldAction.RELAYOUT_REREAD]：count 变了，折叠入口是真 item，
         *    快照下标整体偏移，重读一次快照（仍避开 rebuildModels 的 SQL 与 nameOf 查表）。
         *
         * ⚠️ 这里**不更新 [lastStamp]**：下一次 [rebuild]（收消息等触发）会用新的
         * [pinFoldedByView] 算出新指纹，与旧 lastStamp 不同 ⇒ 走一次全量重建，把快照 /
         * models / memberSets 全部刷新到折叠后的真实状态。折叠是低频操作，这一次额外的
         * 全量重建代价可忽略，换来的是「折叠后数据绝不残留旧状态」。
         */
        private fun rebuildForPinFold(detected: Boolean) = synchronized(lock) {
            val currentCount = runCatching { wrapped.count }.getOrDefault(snaps.size)
            val action = PinFoldLogic.foldAction(pinApplied, detected, snaps.size, currentCount)
            if (action == PinFoldLogic.FoldAction.NOOP) return@synchronized

            // ⚠️ **顺序要紧：先判「有没有分组行可收放」，再决定要不要重读快照。**
            // 反过来写（先 `snaps = readSnaps(...)` 再判空返回）会留下 `snaps` 已换、
            // `layout` 未换的错位窗口 —— `layout` 的下标还指着旧快照，而 `wrapped`
            // 已经变了，表现为「点 A 进的是 B」。
            //
            // 无分组行可收放（开关关 / 筛选态 / 无分组）时，折叠态不影响渲染；但底层
            // 微信列表**真的变了**（折叠入口是真实 item 时 count 会变 ⇒ 下标整体偏移），
            // 这种情况下必须补一次全量重建。这条分支里本来就没有分组行，不存在
            // 「分组行慢半拍」的观感问题，正确性优先。
            if (lastSpecs.isEmpty()) {
                pinApplied = detected
                if (action == PinFoldLogic.FoldAction.RELAYOUT_REREAD) {
                    lastStamp = ""   // 破掉指纹短路，保证下面这次 rebuild 必走全量
                    rebuild()
                }
                return@synchronized
            }

            if (action == PinFoldLogic.FoldAction.RELAYOUT_REREAD) {
                snaps = readSnaps(currentCount)
            }
            layout = ConvLayout.build(
                snaps, lastSpecs, memberSets, enabled = true, pinFolded = detected,
            )
            pinApplied = detected
            // R34 取证：`snaps.size != count` ⇒ 这次判定【抢跑】了（微信还没把 count 换过来），
            // 行数这一轮没变，真正的行数变化会落到 250ms 后的 `K7 debounce` 上 ⇒ 两段式。
            LogX.i(
                "K7 pinFold fast folded=$detected action=$action count=$currentCount" +
                    " snaps=${snaps.size} layout=${layout.size}"
            )
        }

        /**
         * 读全部会话的 wxid + 时间。
         *
         * **下标必须对齐**：微信 adapter 的第 i 项必须落在 `snaps[i]`，
         * 拿不到会话对象也要放一个空 entry 占位 —— 用 `continue` 跳过会让后面
         * 所有下标左移，表现就是「点谁都开错人」。
         */
        private fun readSnaps(n: Int): List<ConvLayout.ConvEntry> {
            val out = ArrayList<ConvLayout.ConvEntry>(n)
            for (i in 0 until n) {
                val item = runCatching { wrapped.getItem(i) }.getOrNull()
                if (item == null) {
                    // R31：标记 unreadable，跟「拿到对象但读不到 username」区分开 ——
                    // 后者才是「这一项不是会话」（如折叠置顶聊天那行），折叠判据要用它。
                    out.add(ConvLayout.ConvEntry("", unreadable = true))
                    continue
                }
                out.add(ConvLayout.ConvEntry(ConvReader.username(item), ConvReader.timeMs(item)))
            }
            return out
        }

        /**
         * 按 wxid 取「能给人看的名字」，取不到就回落成 wxid 本身。
         *
         * 三级回落（顺序不能反）：
         * 1. `findFriend` —— `friend_cache` 是通讯录**实时快照**，改备注立刻生效；
         * 2. `findMember` —— `group_member.display_name` 是**加人那一刻**的快照，
         *    覆盖「已归组但不在通讯录」的人（群内未加好友、已删好友）；
         * 3. 裸 wxid。
         *
         * **为什么必须先 friend 后 member**：反过来的话，用户改了备注，分组行
         * 会一直显示加人时的旧备注，直到把这个人移出再重新加回来。
         *
         * **为什么不传 groupId 用 membersOf**：`membersOf` 要整组几十~几百行只为
         * 取一个名字，而 [buildModel] 是**按会话循环**调用的 ⇒ N+1 放大。
         * `findMember` 是主键单行命中，成本可忽略。
         *
         * ⚠️ `?.displayName` 之后还要判空串：`display_name` 是可空 TEXT 列，
         * 写入时来源本身也可能是空，只判 null 会渲染出「名字是空白」的行。
         */
        private fun nameOf(db: GroupDb, wxid: String): String = runCatching {
            db.findFriend(wxid)?.displayName?.takeIf { it.isNotBlank() }
                ?: db.findMember(wxid)?.displayName?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: wxid

        /** A4 + A7：算出这个分组行该显示什么 */
        private fun buildModel(
            db: GroupDb,
            g: Group,
            wx: Set<String>,
            showUnread: Boolean
        ): GroupRowView.Model {
            var timeMs = 0L
            var unread = 0
            var sender = ""
            var senderWxid = ""
            var msg = ""

            for (i in snaps.indices) {
                if (snaps[i].wxid !in wx) continue
                if (timeMs == 0L) {
                    // snaps 是时间降序：第一个命中的就是该组最新一条
                    timeMs = snaps[i].timeMs
                    val item = runCatching { wrapped.getItem(i) }.getOrNull()
                    msg = ConvReader.digest(item)
                    sender = nameOf(db, snaps[i].wxid)
                    senderWxid = snaps[i].wxid
                }
                unread += ConvReader.unread(runCatching { wrapped.getItem(i) }.getOrNull())
            }

            // A4-c：整组没消息 → 第二行显示前 3 个成员名
            val names = if (timeMs == 0L && wx.isNotEmpty()) {
                wx.take(3).map { w -> nameOf(db, w) }
            } else {
                emptyList()
            }

            return GroupRowView.Model(
                id = g.id,
                name = g.name,
                pinned = g.pinned,
                memberCount = wx.size,
                lastSender = sender,
                lastSenderWxid = senderWxid,
                lastMsg = msg,
                timeMs = timeMs,
                unread = unread,
                showUnread = showUnread,
                memberNames = names,
            )
        }

        private fun modelOf(gid: Long): GroupRowView.Model =
            models[gid] ?: GroupRowView.Model(id = gid, name = "分组")

        // ---- Adapter 转发 ----
        //
        // 这里有个**必须分清的双语义**，混了就是「点了没反应 / 点谁都开错人」：
        //
        //  - 显示位置（layout 下标）：`getCount` / `getView` 用。列表第 i 行是
        //    分组行还是哪条会话，由 layout 决定。
        //  - 数据位置（微信原始下标）：`getItem` / `getItemId` 用。
        //
        // 为什么后者必须按原始下标：我们把点击转交微信时，给的是
        // 「原始下标 + header 数」的 ListView position；微信回头会用
        // `getItemAtPosition(p)` 去取会话，那条路最终打到本类的
        // `getItem(p - headersCount)`。**所以这里的入参是原始下标**，
        // 若再拿它去查 layout（筛选后的序列），取到的就是另一条会话。
        // 真机日志实锤（当时的行数，尾部填充行还没删）：
        // `K7 click conv pos=16 headers=14 real=18`
        // 之后微信按 18 取数据，取到 null → 聊天页压根不打开。

        /**
         * ⚠️ 这里**不能再多挂一行**（v0.2.2 之前挂过一行「尾部填充行」，已移除）。
         *
         * 那行是为了骗过 `ListView.correctTooHigh`（详见文件末尾「为什么不再需要
         * 尾部填充行」），但它带来一个更糟的副作用：把内容底部撑到列表底部之后，
         * `AbsListView.trackMotionScroll` 的
         * `cannotScrollUp = firstPos + n == mItemCount && lastBottom <= end`
         * 就再也成立不了（footer 永远挂不上、lastBottom 恒等于 end），
         * 于是**上拉一路到底**，屏幕上只剩那行透明的填充 View + 微信的 footer，
         * 表现为「页面空白」。现在改成 hook 吞掉 `correctTooHigh`，见 [ScrollDock]。
         */
        override fun getCount(): Int = layout.size

        override fun getItem(position: Int): Any? =
            runCatching { wrapped.getItem(position) }.getOrNull()

        override fun getItemId(position: Int): Long =
            runCatching { wrapped.getItemId(position) }.getOrDefault(-1L)

        override fun hasStableIds(): Boolean =
            runCatching { wrapped.hasStableIds() }.getOrDefault(false)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val ctx = parent?.context ?: HostContext.app()

            val gid = layout.groupIdAt(position)
            if (gid != ConvLayout.Layout.NOT_A_GROUP) {
                if (ctx == null) return placeholder(parent)
                // R32：分组行**复用 convertView**（`create` 只建一次骨架，之后每次只 `bind`）。
                //
                // 旧写法每次都 `GroupRowView.build(...)` 新建整棵树，而 `notifyDataSetChanged()`
                // 会重建所有可见行 ⇒ 折叠/展开置顶时分组行重测量、头像重载、LayoutParams 与
                // listener 重新分配，视觉上「闪一下、跳一下」，那一帧也变长。
                //
                // ⚠️ 必须先过 `isGroupRow`：ListView 的回收池按 viewType 分池，但换 adapter /
                // 改类型数量之后旧池子仍可能串类型把**会话行**递进来；`bind` 对非本类骨架会
                // 静默返回，那一行就渲染成空白（规矩 3 的另一面）。
                val reusable = convertView?.takeIf { GroupRowView.isGroupRow(it) }
                val row = reusable ?: GroupRowView.create(ctx)
                GroupRowView.bind(
                    row,
                    modelOf(gid),
                    onTap = { runCatching { setGroup(gid) } },
                    // ctx 就是创建列表的那个 Activity（View.getContext 必是 Activity），
                    // 直接传下去 —— 比 HostContext.activity() 可靠得多（见 [GroupRowMenu]）
                    onLongTap = { GroupRowMenu.show(ctx, gid) },
                )
                return row
            }

            val r = layout.convIndexOf(position)
            if (r < 0 || ctx == null) return placeholder(parent)

            // convertView 的**类型必须和本行的类型一致**才能交给微信复用。
            // ListView 的 RecycleBin 池子是按「换 adapter 之前」的 viewTypeCount 建的，
            // 池子数量对不上时会串类型 —— 微信的 getView 会把自己的类型强转
            // convertView，直接抛异常，这一行就渲染成空白（真机实测踩到）。
            // 对不上就当没有 convertView，让它自己新建。
            val cv = convertView?.takeIf { viewTypeOf(it) == getItemViewType(position) }

            // 规矩 3：绝不返回 null。微信的 getView 万一抛了，给个占位行也比空白强
            return runCatching { wrapped.getView(r, cv, parent) }
                .getOrNull() ?: placeholder(parent)
        }

        private fun placeholder(parent: ViewGroup?): View {
            val ctx = parent?.context
            return if (ctx != null) View(ctx) else View(HostContext.app())
        }

        /**
         * 读 `AbsListView.LayoutParams.viewType`。
         *
         * 这个字段是**包内可见**（`int viewType;`），模块和 framework 不同包，
         * 编译期拿不到，只能反射。
         */
        private fun viewTypeOf(v: View): Int {
            val lp = v.layoutParams as? AbsListView.LayoutParams ?: return Int.MIN_VALUE
            return runCatching { XposedHelpers.getIntField(lp, "viewType") }
                .getOrDefault(Int.MIN_VALUE)
        }

        // -------------------------------------------------- 为什么不再需要尾部填充行
        //
        // v0.2.2 为了屏蔽 `ListView.correctTooHigh` 曾在列表末尾补一行「把缺口填满」
        // 的透明行。**那行已经删掉了**，原因是它破坏了滚动边界：
        //
        // AOSP `ListView.correctTooHigh(childCount)`（sources/android-35）：
        //
        //     int lastPosition = mFirstPosition + childCount - 1;
        //     if (lastPosition == mItemCount - 1 && childCount > 0) {
        //         int bottomOffset = end - lastChild.getBottom();      // end = 列表高 - padBottom
        //         int firstTop   = firstChild.getTop();
        //         if (bottomOffset > 0 && (mFirstPosition > 0 || firstTop < mListPadding.top)) {
        //             if (mFirstPosition == 0)
        //                 bottomOffset = Math.min(bottomOffset, mListPadding.top - firstTop);
        //             offsetChildrenTopAndBottom(bottomOffset);        // ← 整块内容下移
        //         }
        //     }
        //
        // 微信把顶部栏做成会话列表 3046px 的巨型 header，列表靠停在
        // `child0.top = -2713` 才显示正常（header 只露最后 333px，会话行从 333 开始）。
        // 内容不足一屏时 `firstTop(-2713) < padTop` 且 `bottomOffset > 0`，
        // 条件全中 → 整块内容被下移 ≈1994px（「点分组行整页下移」的元凶）。
        //
        // 填充行能让 `bottomOffset` 恒为 0，把上面这段彻底屏蔽 —— 但它同时让
        // `lastBottom` 恒等于 `end`，于是
        // `cannotScrollUp = firstPos + n == mItemCount && lastBottom <= end`
        // 再也成立不了（footer 挂不上，`firstPos + n` 永远差 1）→ **上拉一路到底**，
        // 屏幕上只剩那行透明填充 View + 微信 footer，表现就是「页面空白」。
        //
        // 现在的做法（三件套，缺一不可，见 [ScrollDock]）：
        //  1. 直接 hook 吞掉 `correctTooHigh`（真机验证过这个 hook 点确实会被调到）；
        //  2. 不再补行 → 内容不足一屏时 `firstPos + n == mItemCount` 且
        //     `lastBottom <= end` 成立 → `cannotScrollUp` 为真 → 上拉被自然禁掉；
        //  3. `cannotScrollDown` 因为 `firstTop(-2713) < padTop` 永远为假，
        //     下拉不受限 → 由 [ScrollDock] 事后钳制回停靠位。

        override fun getItemViewType(position: Int): Int {
            if (layout.isGroupRow(position)) return groupType
            val r = layout.convIndexOf(position)
            if (r < 0) return 0
            return runCatching { wrapped.getItemViewType(r) }.getOrDefault(0)
        }

        override fun getViewTypeCount(): Int = groupType + 1

        override fun isEmpty(): Boolean = layout.size == 0

        override fun areAllItemsEnabled(): Boolean = false

        override fun isEnabled(position: Int): Boolean {
            if (layout.isGroupRow(position)) return true
            val r = layout.convIndexOf(position)
            if (r < 0) return false
            return runCatching { wrapped.isEnabled(r) }.getOrDefault(true)
        }
    }
}
