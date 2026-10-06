package com.lx.wxgroup.feature

import android.database.DataSetObserver
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.HeaderViewListAdapter
import android.widget.ListAdapter
import android.widget.ListView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.PrefKeys
import com.lx.wxgroup.util.LogX
import java.util.Collections
import java.util.WeakHashMap

/**
 * M4-C：按分组过滤聊天列表 —— 选中某个分组后，会话列表只显示该组的成员会话。
 *
 * ## 做法：在 HeaderViewListAdapter 里把内层 adapter 换成过滤包装
 *
 * 8.0.48 实测的列表结构（`probe/FilterProbe.kt` 打出来的）：
 *
 * ```
 * ConversationListView
 * └── HeaderViewListAdapter            headers=15（14 个微信自己的 + 我们的分组行） footers=1
 *     └── mAdapter = com.tencent.mm.ui.conversation.p3   ← 全部会话，2924 条
 *         └── getItem() = com.tencent.mm.storage.r4      ← field_username 就是 wxid
 * ```
 *
 * 所以只动 `HeaderViewListAdapter.mAdapter` 这一层：
 * 换成我们的 [FilterAdapter]（按 wxid 是否属于该分组转发 count/getItem/getView），
 * 微信自己的 adapter、数据源、header/footer 全都不动。
 *
 * ## 为什么必须重映射点击位置（这一步漏了就是灾难）
 *
 * 微信的点击监听是 `com.tencent.mm.ui.conversation.o2`，只实现 `onItemClick`，
 * 而且**握着 ConversationListView** —— 它必然是用位置去取会话的。过滤之后位置变了，
 * 不重映射的话「点第 3 条」会打开过滤前第 3 条那个聊天。
 * 所以把所有点击/长按监听包一层，把位置换算回**未过滤时的位置**再交给微信，
 * 这样它那边完全无感。
 *
 * ## 默认状态是「零改动」
 *
 * 没筛选时虽然是我们的 adapter 在转发，但 `real` 就是 `[0..n-1]`，
 * 转发结果与微信原样一致；只有在选中分组时才真的过滤。
 */
object ConvFilter {

    private const val LIST_CLASS = "com.tencent.mm.ui.conversation.ConversationListView"

    /** 没选中任何分组 */
    const val NONE = 0L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** listView -> 我们装进去的过滤包装 */
    private val wrappers = Collections.synchronizedMap(WeakHashMap<ListView, FilterAdapter>())

    /** 当前筛选的分组 id（0 = 不筛选）；-1 = 还没从库里读过 */
    @Volatile
    private var cachedId: Long = -1L

    @Volatile
    private var cachedMembers: Set<String> = emptySet()

    /** 上次核对成员表的时间（只有筛选态才会真的去查库） */
    @Volatile
    private var lastSyncMs = 0L

    /** 成员表核对节流间隔 */
    private const val SYNC_MS = 1500L

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookSetAdapter(lpparam)
        hookListeners(lpparam)
    }

    // ------------------------------------------------------------ 对外接口

    /** 当前筛选的分组 id（0 = 全部） */
    fun currentGroupId(): Long {
        if (cachedId < 0) refreshCache()
        return cachedId.coerceAtLeast(0)
    }

    /** 切换筛选：再点同一个分组 = 取消筛选 */
    fun toggle(id: Long) {
        val cur = currentGroupId()
        setGroup(if (cur == id) NONE else id)
    }

    fun setGroup(id: Long) {
        val db = GroupDb.current() ?: return
        val target = id.coerceAtLeast(0)
        PanelDiag.arm()  // 诊断窗口：toggle 后 3 秒内监听滚动入口
        db.setPref(PrefKeys.FILTER_GROUP, target.toString())
        cachedId = target
        cachedMembers = if (target > 0) {
            runCatching { db.memberWxids(target) }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        LogX.i("FILTER setGroup id=$target members=${cachedMembers.size}")
        mainHandler.post {
            val list = GroupBar.conversationList()
            if (list != null) {
                apply(list)
                // 过滤后回到顶部，让用户看到结果的第一条。
                // 已在顶部时跳过：setSelection(0) 是一次多余的强制布局，
                // 而且紧跟着的 lane 高度动画期间再叠加布局扰动没有好处。
                runCatching { if (list.firstVisiblePosition > 0) list.setSelection(0) }
            }
            GroupBar.refreshAll()
            // v9：数据集收缩会让 ListView 锚回顶部、露出面板 item，微信会自动展开
            // 下拉面板 —— 把面板 item 滚回关闭态基线位置。
            if (list != null) {
                mainHandler.post { runCatching { GroupBar.reanchorPanel(list) } }
            }
        }
    }

    /**
     * 低成本自愈 + 成员表核对：被 GroupBar 的布局回调频繁调用，
     * 所以「已装好且没什么要做的」这条路必须零成本。
     *
     * 真的需要装（或重装）时再 post 出去，避免在 layout 里改 adapter 造成重入。
     */
    fun ensure(list: ListView) {
        if (!isInstalled(list)) {
            mainHandler.post { runCatching { apply(list) } }
            return
        }
        // 已装好：顺手核对一次成员表（节流 1.5s）。这样在分组页/批量页里增删成员后，
        // 回到聊天列表就能看到过滤结果跟着变 —— 否则各写入点都得记得通知我们，
        // 漏一个就是「改了成员但列表没变」的幽灵问题。
        if (currentGroupId() <= 0) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSyncMs < SYNC_MS) return
        lastSyncMs = now
        mainHandler.post {
            runCatching {
                val db = GroupDb.current() ?: return@runCatching
                val id = currentGroupId()
                if (id <= 0) return@runCatching
                val fresh = db.memberWxids(id)
                if (fresh != cachedMembers) {
                    LogX.i("FILTER members changed ${cachedMembers.size} -> ${fresh.size}")
                    cachedMembers = fresh
                    wrappers[list]?.update(id, fresh)
                }
            }
        }
    }

    /** 建会话列表时也要装一遍（列表被微信重建后，包装会丢） */
    fun apply(list: ListView) {
        val db = GroupDb.current() ?: return
        if (cachedId < 0) refreshCache()
        var id = cachedId
        var members = cachedMembers

        if (id > 0) {
            val exists = runCatching { db.listGroups().any { it.id == id } }.getOrDefault(false)
            if (!exists) {
                // 分组被删了：清掉筛选，别把列表过滤成空的
                LogX.w("FILTER group $id gone, clear filter")
                db.setPref(PrefKeys.FILTER_GROUP, NONE.toString())
                cachedId = NONE
                cachedMembers = emptySet()
                id = NONE
            } else {
                val fresh = loadMembers(id)
                if (fresh == null) {
                    // 读成员失败：宁可不过滤，也绝不能把会话列表清空
                    LogX.w("FILTER member read failed, fall back to no-filter")
                    id = NONE
                } else {
                    members = fresh
                    cachedMembers = fresh
                }
            }
        }

        val fa = install(list) ?: return
        // 注意：这里**空集合要照传** —— 空分组的正确结果是「0 条会话」，
        // 传 emptySet 才会真的过滤成空；只有读库失败才会走上面的 no-filter 分支
        fa.update(id, if (id > 0) members else emptySet())
    }

    /** 读成员表；失败返回 null（调用方据此退化成「不筛选」而不是「全隐藏」） */
    private fun loadMembers(id: Long): Set<String>? =
        runCatching { GroupDb.current()?.memberWxids(id) }.getOrNull()

    // ------------------------------------------------------------ 安装

    private fun isInstalled(list: ListView): Boolean {
        val hvl = runCatching { list.adapter }.getOrNull() as? HeaderViewListAdapter ?: return false
        val inner = runCatching { hvl.wrappedAdapter }.getOrNull() ?: return false
        return wrappers[list] === inner
    }

    private fun install(list: ListView): FilterAdapter? {
        val hvl = runCatching { list.adapter }.getOrNull() as? HeaderViewListAdapter
        if (hvl == null) {
            // 不是 HVL 说明列表结构和预期不同：换成我们自己的 adapter 会把微信的
            // header 全弄丢，宁可不做
            LogX.w("FILTER: adapter=${list.adapter?.javaClass?.name} not HeaderViewListAdapter, skip")
            return null
        }
        val inner = runCatching { hvl.wrappedAdapter }.getOrNull() ?: return null
        if (inner is FilterAdapter) {
            wrappers[list] = inner
            return inner
        }

        val fa = FilterAdapter(inner)
        val ok = runCatching { XposedHelpers.setObjectField(hvl, "mAdapter", fa) }.isSuccess
        if (!ok) {
            LogX.w("FILTER: cannot replace HeaderViewListAdapter.mAdapter")
            return null
        }

        // ListView 的数据观察者原本挂在微信的 adapter 上，必须挪到我们身上，
        // 否则我们 notifyDataSetChanged 时列表不会刷新（mItemCount 不更新）
        runCatching {
            val observer = XposedHelpers.getObjectField(list, "mDataSetObserver") as? DataSetObserver
            if (observer != null) {
                inner.unregisterDataSetObserver(observer)
                fa.registerDataSetObserver(observer)
                LogX.i("FILTER: observer moved to FilterAdapter")
            }
        }.onFailure { LogX.w("FILTER: move observer failed ${it.javaClass.simpleName}") }

        // 微信自己刷会话（新消息/置顶变化）时，要能透到我们这层
        runCatching {
            inner.registerDataSetObserver(object : DataSetObserver() {
                override fun onChanged() {
                    runCatching { fa.rebuild(); fa.notifyDataSetChanged() }
                }

                override fun onInvalidated() {
                    runCatching { fa.notifyDataSetInvalidated() }
                }
            })
        }.onFailure { LogX.w("FILTER: forward observer failed ${it.javaClass.simpleName}") }

        wrappers[list] = fa
        wrapListeners(list)
        LogX.i("FILTER installed inner=${inner.javaClass.name} count=${inner.count}")
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
        LogX.i("FILTER cache id=$cachedId members=${cachedMembers.size}")
    }

    // ------------------------------------------------------------ 位置重映射

    /** 过滤后的位置 -> 未过滤时的位置。没有过滤时原样返回。 */
    private fun realPosition(list: ListView, position: Int): Int {
        val fa = wrappers[list] ?: return position
        val hvl = runCatching { list.adapter }.getOrNull() as? HeaderViewListAdapter ?: return position
        val headers = runCatching { hvl.headersCount }.getOrDefault(0)
        val idx = position - headers
        val realIdx = fa.realIndex(idx) ?: return position
        return headers + realIdx
    }

    private class ClickRemap(
        private val list: ListView,
        private val orig: AdapterView.OnItemClickListener,
    ) : AdapterView.OnItemClickListener {
        override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            val real = ConvFilter.realPosition(list, position)
            // 这条日志是排查「点 A 开 B」的唯一线索（能看出微信报的位置和我们的换算）
            LogX.d("FILTER click pos=$position -> $real")
            orig.onItemClick(parent, view, real, id)
        }
    }

    private class LongClickRemap(
        private val list: ListView,
        private val orig: AdapterView.OnItemLongClickListener,
    ) : AdapterView.OnItemLongClickListener {
        override fun onItemLongClick(
            parent: AdapterView<*>?, view: View?, position: Int, id: Long,
        ): Boolean {
            val real = ConvFilter.realPosition(list, position)
            LogX.d("FILTER longClick pos=$position -> $real")
            return orig.onItemLongClick(parent, view, real, id)
        }
    }

    /** 把微信已经设上的点击/长按监听包一层（未过滤时是恒等变换） */
    private fun wrapListeners(list: ListView) {
        runCatching {
            val cur = XposedHelpers.getObjectField(list, "mOnItemClickListener")
            if (cur is AdapterView.OnItemClickListener && cur !is ClickRemap) {
                XposedHelpers.setObjectField(list, "mOnItemClickListener", ClickRemap(list, cur))
                LogX.i("FILTER: click listener remapped (${cur.javaClass.name})")
            }
        }.onFailure { LogX.d("FILTER: wrap click failed ${it.javaClass.simpleName}") }

        runCatching {
            val cur = XposedHelpers.getObjectField(list, "mOnItemLongClickListener")
            if (cur is AdapterView.OnItemLongClickListener && cur !is LongClickRemap) {
                XposedHelpers.setObjectField(list, "mOnItemLongClickListener", LongClickRemap(list, cur))
                LogX.i("FILTER: longClick listener remapped (${cur.javaClass.name})")
            }
        }.onFailure { LogX.d("FILTER: wrap longClick failed ${it.javaClass.simpleName}") }
    }

    /** 微信随时可能重设监听 / 重建列表，两个入口都补一次 */
    private fun hookListeners(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cb = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val list = param.thisObject as? ListView ?: return
                if (list.javaClass.name != LIST_CLASS) return
                mainHandler.post { runCatching { wrapListeners(list) } }
            }
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.widget.AdapterView", lpparam.classLoader,
                "setOnItemClickListener", AdapterView.OnItemClickListener::class.java, cb
            )
        }.onFailure { LogX.d("FILTER: hook setOnItemClickListener failed") }
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.widget.AdapterView", lpparam.classLoader,
                "setOnItemLongClickListener", AdapterView.OnItemLongClickListener::class.java, cb
            )
        }.onFailure { LogX.d("FILTER: hook setOnItemLongClickListener failed") }
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
                            runCatching { apply(list) }
                            runCatching { wrapListeners(list) }
                        }
                    }
                }
            )
            LogX.i("FILTER: hooked ListView.setAdapter")
        }.onFailure { LogX.w("FILTER: hook setAdapter failed: ${it.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------ 过滤 adapter

    /**
     * 把微信的会话 adapter 包一层，只转发命中的那几条。
     *
     * `real` 是「过滤后第 i 项 -> 原始第几项」的映射表；没筛选时是 0..n-1 的恒等表，
     * 所以默认状态下这一层的输出与微信原样完全一致。
     */
    private class FilterAdapter(private val wrapped: ListAdapter) : BaseAdapter() {

        private val real = ArrayList<Int>()

        @Volatile
        private var filterId: Long = NONE

        @Volatile
        private var members: Set<String> = emptySet()

        /** 会话对象类 -> 取 wxid 的字段（拿不到就是 null，那一类条目会被当成不匹配） */
        private val fieldCache = HashMap<Class<*>, java.lang.reflect.Field?>()

        val size: Int get() = synchronized(real) { real.size }

        fun realIndex(index: Int): Int? = synchronized(real) {
            if (index < 0 || index >= real.size) null else real[index]
        }

        fun update(id: Long, memberSet: Set<String>) {
            filterId = id
            members = memberSet
            rebuild()
            runCatching { notifyDataSetChanged() }
            // head 记的是「过滤后前几项在微信 adapter 里的原始索引」，
            // 排查位置重映射时靠它对照
            val head = synchronized(real) { real.take(4) }
            LogX.i(
                "FILTER update id=$id members=${memberSet.size} " +
                    "shown=${size}/${wrapped.count} head=$head"
            )
        }

        /**
         * 会话对象的 wxid。
         *
         * **不能用 `Class.forName("com.tencent.mm.storage.r4")`** —— 那走的是模块自己的
         * 类加载器，根本看不到微信的类（实测返回 null，过滤静默退化成「全部显示」）。
         * 正确做法是拿一个真实的 item 对象，从它的运行时类上找字段。
         */
        private fun usernameOf(item: Any?): String? {
            if (item == null) return null
            val cls = item.javaClass
            val f = synchronized(fieldCache) {
                if (fieldCache.containsKey(cls)) {
                    fieldCache[cls]
                } else {
                    resolveField(cls).also { fieldCache[cls] = it }
                }
            } ?: return null
            return runCatching { f.get(item) as? String }.getOrNull()
        }

        /** 优先 field_username（实测锚点），退化到任何名字里带 username 的 String 字段 */
        private fun resolveField(cls: Class<*>): java.lang.reflect.Field? {
            var c: Class<*>? = cls
            var guard = 0
            while (c != null && c != java.lang.Object::class.java && guard++ < 4) {
                val fields = runCatching { c!!.declaredFields }.getOrDefault(emptyArray())
                fields.firstOrNull { it.name == "field_username" && it.type == String::class.java }
                    ?.let { return it.apply { isAccessible = true } }
                fields.firstOrNull {
                    it.type == String::class.java &&
                        it.name.contains("username", ignoreCase = true)
                }?.let { return it.apply { isAccessible = true } }
                c = c.superclass
            }
            return null
        }

        fun rebuild() {
            val n = runCatching { wrapped.count }.getOrDefault(0)
            val out = ArrayList<Int>(if (filterId > 0) 64 else n)
            if (filterId <= 0) {
                // 没有筛选：恒等表
                for (i in 0 until n) out.add(i)
            } else {
                // 有筛选：**空分组就该是 0 条**，不能退化成「全部显示」
                // （那会让用户以为筛选没生效；实测踩过这个坑）
                for (i in 0 until n) {
                    val item = runCatching { wrapped.getItem(i) }.getOrNull() ?: continue
                    val u = usernameOf(item) ?: continue
                    if (members.contains(u)) out.add(i)
                }
            }
            synchronized(real) {
                real.clear()
                real.addAll(out)
            }
        }

        override fun getCount(): Int = size

        override fun getItem(position: Int): Any? {
            val r = realIndex(position) ?: return null
            return runCatching { wrapped.getItem(r) }.getOrNull()
        }

        override fun getItemId(position: Int): Long {
            val r = realIndex(position) ?: return 0L
            return runCatching { wrapped.getItemId(r) }.getOrDefault(0L)
        }

        override fun hasStableIds(): Boolean = runCatching { wrapped.hasStableIds() }.getOrDefault(false)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View? {
            val r = realIndex(position) ?: return null
            return runCatching { wrapped.getView(r, convertView, parent) }.getOrNull()
        }

        override fun getItemViewType(position: Int): Int {
            val r = realIndex(position) ?: return 0
            return runCatching { wrapped.getItemViewType(r) }.getOrDefault(0)
        }

        override fun getViewTypeCount(): Int =
            runCatching { wrapped.viewTypeCount }.getOrDefault(1).coerceAtLeast(1)

        override fun isEmpty(): Boolean = size == 0

        override fun areAllItemsEnabled(): Boolean =
            runCatching { wrapped.areAllItemsEnabled() }.getOrDefault(true)

        override fun isEnabled(position: Int): Boolean {
            val r = realIndex(position) ?: return false
            return runCatching { wrapped.isEnabled(r) }.getOrDefault(true)
        }
    }
}
