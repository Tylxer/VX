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
    fun currentGroupId(): Long {
        if (cachedId < 0) refreshCache()
        return cachedId.coerceAtLeast(0)
    }

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

    /** 重建前抓锚点：列表停在顶部（第一个 item 就是 header）时记下当前 top */
    private fun rememberAnchor(list: ListView) {
        runCatching {
            if (list.firstVisiblePosition != 0) return
            val c0 = list.getChildAt(0) ?: return
            if (c0.top < 0) headerAnchor = c0.top
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
     * 用 [headerAnchor]；还没抓到就退回 0（只在冷启动第一帧可能发生）。
     */
    private fun restoreAnchor(list: ListView) {
        val anchor = headerAnchor ?: 0
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
        // 我们的包装多了「分组行 / 尾部填充行」两个类型 → 池子数量对不上，
        // 会把尾部那行的高 View 当成会话行的 convertView 递进来（真机实测）→
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
            // 分组行：自己消费掉，不转交微信（A5/A8 长按菜单以后挂这里）
            if (groupIdAt(list, position) != ConvLayout.Layout.NOT_A_GROUP) return true
            val real = realPosition(list, position)
            if (real < 0) return true
            return runCatching { orig.onItemLongClick(parent, view, real, id) }.getOrDefault(true)
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

        /** 尾部占几行（恒 1，常量只是为了让 [getCount] 读起来自解释） */
        private val tailRows = 1

        /**
         * 视图类型编号。
         *
         * 分组行和尾部行**必须各占一个类型**：ListView 的类型复用的是「同类型才能
         * 回收给同类型」，串类型会把分组行的内容复用到会话行上（真机踩过）。
         */
        private val groupType: Int
            get() = runCatching { wrapped.viewTypeCount }.getOrDefault(1).coerceAtLeast(1)

        private val tailType: Int get() = groupType + 1

        fun rebuild() = synchronized(lock) {
            val n = runCatching { wrapped.count }.getOrDefault(0)
            if (snaps.size != n) snaps = readSnaps(n)

            val db = GroupDb.current()
            val enabled = runCatching { Kv.getBool(Keys.CHAT_GROUP_ROW_ENABLED) }.getOrDefault(true)
            val filterId = currentGroupId()

            val stamp = "$n|$enabled|$filterId|${cachedMembers.hashCode()}"
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
                    val groups = runCatching { db.listGroups() }.getOrDefault(emptyList())
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
                            newModels[g.id] = buildModel(db, g, wx)
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
        private fun buildModel(db: GroupDb, g: Group, wx: Set<String>): GroupRowView.Model {
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
        // 真机日志实锤：`K7 click conv pos=16 headers=14 real=18`
        // 之后微信按 18 取数据，取到 null → 聊天页压根不打开。

        override fun getCount(): Int =
            if (layout.size == 0) 0 else layout.size + tailRows

        override fun getItem(position: Int): Any? =
            runCatching { wrapped.getItem(position) }.getOrNull()

        override fun getItemId(position: Int): Long =
            runCatching { wrapped.getItemId(position) }.getOrDefault(-1L)

        override fun hasStableIds(): Boolean =
            runCatching { wrapped.hasStableIds() }.getOrDefault(false)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            if (isTail(position)) return buildTail(parent)

            val ctx = parent?.context ?: HostContext.app()

            val gid = layout.groupIdAt(position)
            if (gid != ConvLayout.Layout.NOT_A_GROUP) {
                if (ctx == null) return placeholder(parent)
                return GroupRowView.build(ctx, modelOf(gid)) {
                    runCatching { setGroup(gid) }
                }
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

        // -------------------------------------------------- 尾部填充行
        //
        // ★ 「点分组行 → 整页下移 2000px + 小程序面板被顶出来」的**真正的解** ★
        //
        // 读 AOSP `ListView.correctTooHigh(childCount)`（sources/android-35）：
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
        // 这个状态下 **`childCount == mItemCount`、`firstTop(-2713) < 0`、
        // `lastBottom(≈1110) < end(3104)`** —— 三个条件全中，于是被下移 ≈1994px。
        // `fillSpecific()` 里也调 `correctTooHigh`，所以**任何 `setSelectionFromTop`
        // 复位都会被再次下移**（实测：notify 前复位 → 布局后 c0top 从 -2713 变 -662）。
        //
        // 唯一出路是让 `bottomOffset <= 0`：在最后补一行「把缺口填满」的透明行。
        //  - 内容本来够长时它高度算出来是 0，退化成一个 0 高 View，无影响；
        //  - 内容不够时它正好补到列表底部，`bottomOffset == 0`，下移逻辑不成立。
        //
        // 高度是**现算的**而不是写死：`fillDown` 是顺序挂 child 的，轮到本行时
        // 前面所有真实行都已量好放好，直接读最后一个 child 的 bottom 就知道差多少。

        private fun isTail(position: Int): Boolean = position >= layout.size

        /**
         * 还差多少像素才顶到列表底部。差 0 就返回 0（内容够长，本行不产生任何高度）。
         */
        private fun tailHeight(list: ListView?): Int {
            if (list == null) return 0
            val n = list.childCount
            if (n <= 0) return 0
            val lastBottom = list.getChildAt(n - 1).bottom
            val end = list.height - list.paddingBottom
            return (end - lastBottom).coerceAtLeast(0)
        }

        private fun buildTail(parent: ViewGroup?): View {
            val ctx = parent?.context ?: HostContext.app()
            val v = View(ctx)
            v.isClickable = false
            v.isFocusable = false
            v.layoutParams = AbsListView.LayoutParams(
                AbsListView.LayoutParams.MATCH_PARENT,
                tailHeight(parent as? ListView),
            )
            return v
        }

        override fun getItemViewType(position: Int): Int {
            if (isTail(position)) return tailType
            if (layout.isGroupRow(position)) return groupType
            val r = layout.convIndexOf(position)
            if (r < 0) return 0
            return runCatching { wrapped.getItemViewType(r) }.getOrDefault(0)
        }

        override fun getViewTypeCount(): Int = groupType + 2

        override fun isEmpty(): Boolean = layout.size == 0

        override fun areAllItemsEnabled(): Boolean = false

        override fun isEnabled(position: Int): Boolean {
            if (isTail(position)) return false
            if (layout.isGroupRow(position)) return true
            val r = layout.convIndexOf(position)
            if (r < 0) return false
            return runCatching { wrapped.isEnabled(r) }.getOrDefault(true)
        }
    }
}
