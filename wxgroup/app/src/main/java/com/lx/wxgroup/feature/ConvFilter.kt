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

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

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
        hookItemClick(lpparam)
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
     * 点击走 [hookItemClick]，长按仍走 listener 包装（长按没有"位置会被二次解释"
     * 的问题，包一层就够）。
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

    /**
     * 点击重映射：**直接改 `performItemClick` 的 position 参数**。
     *
     * 为什么不包装 OnItemClickListener（旧做法，已废弃）：微信的
     * ConversationListView 自己覆写了 setter/getter，我们 getter 读回来的未必
     * 是自己包的那层，于是每 set 一次就套一层；套两层时一次点击会触发两次
     * `onItemClick`，外层把重映射后的 18 传给内层，内层又拿 18 当显示位置重算
     * 成 -1 —— 表现就是「点会话行完全没反应」。
     *
     * 改在 `performItemClick` 上做，链路上只有我们自己一次、且不改动微信的
     * listener 对象，从根上消掉嵌套。用 `hookAllMethods` 是为了连子类
     * （ConversationListView）自己的覆写也一起抓。
     */
    private fun hookItemClick(lpparam: XC_LoadPackage.LoadPackageParam) {
        // ⚠️ 必须挂满整条继承链：`XposedBridge.hookAllMethods(cls, ...)` 只扫
        // **cls 自己声明**的方法，不会递归子类。而 ListView 覆写了
        // `performItemClick`，微信的 ConversationListView 很可能又覆写了一层 ——
        // 只挂 AdapterView 的话一次都不会触发（实测：日志里 "hooked
        // AdapterView.performItemClick" 有，点击却毫无动静）。
        val cb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val list = param.thisObject as? ListView ?: return
                if (list.javaClass.name != LIST_CLASS) return
                val pos = param.args.getOrNull(1) as? Int ?: return

                val gid = groupIdAt(list, pos)
                if (gid != ConvLayout.Layout.NOT_A_GROUP) {
                    LogX.i("K7 performClick group row id=$gid pos=$pos")
                    setGroup(gid)
                    param.result = true          // 自己吃掉，别让微信看到分组行
                    return
                }

                val real = realPosition(list, pos)
                val headers = headerCount(list)
                // 把 wxid 一起打出来：「点第 3 条打开第 5 条」是这套代码最难查的故障，
                // 有 wxid 才能一眼看出换算对没对。
                LogX.i(
                    "K7 performClick conv pos=$pos headers=$headers real=$real " +
                        "wxid=${wrappers[list]?.wxidOf(real - headers)} " +
                        "size=${wrappers[list]?.size}"
                )
                if (real < 0) {
                    param.result = true
                    return
                }
                param.args[1] = real
            }
        }

        for (cls in listOf("android.widget.AdapterView", "android.widget.ListView", LIST_CLASS)) {
            runCatching {
                XposedBridge.hookAllMethods(
                    XposedHelpers.findClass(cls, lpparam.classLoader), "performItemClick", cb
                )
                LogX.i("K7: hooked $cls.performItemClick")
            }.onFailure { LogX.d("K7: hook $cls.performItemClick n/a: ${it.javaClass.simpleName}") }
        }
    }

    /** 长按 listener 包装（点击不在这里做，见 [hookItemClick]） */
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
        // ⚠️ 必须实时取（wxidNow），不能用快照 wxidOf —— 快照只在会话条数变化时重算，
        // 收消息重排后条数不变，快照是旧顺序，会把「加入分组」加到别人头上。
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
                    runCatching { fa.rebuild(); fa.notifyDataSetChanged() }
                }

                override fun onInvalidated() {
                    runCatching { fa.notifyDataSetInvalidated() }
                }
            })
        }.onFailure { LogX.w("K7: forward observer failed ${it.javaClass.simpleName}") }

        wrappers[list] = fa
        runCatching { wrapListeners(list) }
        rememberAnchor(list)
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
     * 点击**不走这里**：点击的位置会被微信二次解释（`getItemAtPosition`），
     * 包装 listener 在真机上会因微信覆写 getter 而嵌套，导致一次点击两次回调、
     * 位置被重算成 -1（点会话没反应）。点击统一由 [hookItemClick] 在
     * `performItemClick` 上改参数，链路上只有一次。
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

        private val lock = Any()

        /** 上次 rebuild 的输入指纹；输入没变就直接复用，避免微信连续 notify 时反复全量重算 */
        private var lastStamp = ""

        val size: Int get() = layout.size

        /** 原始会话下标 -> wxid（诊断用：核对点击有没有点错人） */
        fun wxidOf(convIndex: Int): String = snaps.getOrNull(convIndex)?.wxid ?: ""

        /**
         * 原始会话下标 -> wxid，**实时**读微信 adapter。
         *
         * ⚠️ 需要「现在这一行的真实 wxid」时必须用这个，不能用 [wxidOf]：
         * [snaps] 只在**会话条数变化**时才重算（[rebuild] 里 `snaps.size != n`），
         * 而收新消息导致的置顶/重排**条数不变** —— 快照会一直停在旧顺序上，
         * 长按加分组就会加到别人头上。这里单次 `getItem` 读一次会话对象，
         * 主线程开销可忽略。
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

        fun rebuild() = synchronized(lock) {
            val n = runCatching { wrapped.count }.getOrDefault(0)
            if (snaps.size != n) snaps = readSnaps(n)

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
            val stamp = "$n|$enabled|$filterId|$showUnread|${cachedMembers.hashCode()}|${groups.hashCode()}"
            if (snaps.isNotEmpty() && stamp == lastStamp) return@synchronized
            lastStamp = stamp

            layout = when {
                db == null || !enabled -> {
                    // 开关关掉 / DB 没就绪 = 零改动，一条会话都不能少
                    ConvLayout.build(snaps, emptyList(), emptyMap(), enabled = false)
                }
                filterId > 0 -> {
                    ConvLayout.filtered(snaps, cachedMembers)
                }
                else -> {
                    if (groups.isEmpty()) {
                        ConvLayout.build(snaps, emptyList(), emptyMap(), enabled = false)
                    } else {
                        val specs = groups.map {
                            ConvLayout.GroupRowSpec(it.id, it.name, it.pinned, it.sortOrder)
                        }
                        val members = LinkedHashMap<Long, Set<String>>()
                        val newModels = HashMap<Long, GroupRowView.Model>()
                        for (g in groups) {
                            val wx = runCatching { db.memberWxids(g.id) }.getOrDefault(emptySet())
                            members[g.id] = wx
                            newModels[g.id] = buildModel(db, g, wx, showUnread)
                        }
                        models = newModels
                        ConvLayout.build(snaps, specs, members, enabled = true)
                    }
                }
            }
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
                    out.add(ConvLayout.ConvEntry(""))
                    continue
                }
                out.add(ConvLayout.ConvEntry(ConvReader.username(item), ConvReader.timeMs(item)))
            }
            return out
        }

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
            var msg = ""

            for (i in snaps.indices) {
                if (snaps[i].wxid !in wx) continue
                if (timeMs == 0L) {
                    // snaps 是时间降序：第一个命中的就是该组最新一条
                    timeMs = snaps[i].timeMs
                    val item = runCatching { wrapped.getItem(i) }.getOrNull()
                    msg = ConvReader.digest(item)
                    val w = snaps[i].wxid
                    sender = runCatching { db.findFriend(w)?.displayName }.getOrNull() ?: w
                }
                unread += ConvReader.unread(runCatching { wrapped.getItem(i) }.getOrNull())
            }

            // A4-c：整组没消息 → 第二行显示前 3 个成员名
            val names = if (timeMs == 0L && wx.isNotEmpty()) {
                wx.take(3).map { w ->
                    runCatching { db.findFriend(w)?.displayName }.getOrNull() ?: w
                }
            } else {
                emptyList()
            }

            return GroupRowView.Model(
                id = g.id,
                name = g.name,
                pinned = g.pinned,
                memberCount = wx.size,
                lastSender = sender,
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
                return GroupRowView.build(
                    ctx,
                    modelOf(gid),
                    onTap = { runCatching { setGroup(gid) } },
                    // ctx 就是创建列表的那个 Activity（View.getContext 必是 Activity），
                    // 直接传下去 —— 比 HostContext.activity() 可靠得多（见 [GroupRowMenu]）
                    onLongTap = { GroupRowMenu.show(ctx, gid) },
                )
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
