package com.lx.wxgroup.feature

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.lx.wxgroup.core.ChatroomSource
import com.lx.wxgroup.ui.Panel
import com.lx.wxgroup.ui.PanelPage
import com.lx.wxgroup.ui.Rows
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX

/**
 * K3：通用选择器。名单、群发目标、转发目标都走它，一处实现多处复用。
 *
 * 核心机制是从旧项目 `BatchPicker` 搬来的（那套已经在真机上验证过），
 * 三条经验**别破**：
 * 1. 必须 ListView + BaseAdapter 复用行 —— 两千多个好友一次性 addView 会卡死甚至 OOM。
 * 2. CheckBox 必须关掉 focusable / clickable，否则它吃掉触摸事件，
 *    `onItemClick` 永远不触发（经典「点了没反应」）。关掉后整行可点。
 * 3. 搜索只影响**可见项**，不动 `selected` —— 已勾选的人被过滤掉再清掉搜索框，
 *    勾选必须还在，否则用户以为数据丢了。
 *
 * ## R24：四 Tab（好友 / 群聊 / 我的分组 / 微信标签）
 *
 * 数据按 **Tab** 组织，每个 Tab 内可再分若干段（[Section]）。四个 Tab 互斥切换。
 *
 * ⚠️ **勾选状态是全局的，不属于任何 Tab**：`selected` 是扁平 `LinkedHashSet<String>`，
 * 切 Tab 只换可见行（`adapter.reset`），**绝不碰 `selected`**。用户原话要求
 * 「在一个 TAB 内的打勾后，切换到另一个 tab，被选的还是生效」——
 * 这条就靠**不触碰 selected** 实现，而不是靠任何合并逻辑。
 * 若在 [switchTo] 里加了 `selected.clear()`，就是直接违反需求。
 *
 * ⚠️ **单 Tab 自动退化**：`tabs.size == 1` 时不画 Tab 按钮行，行为与 R24 之前一致。
 * 调用方不必为「只有好友」的场景写分支。
 *
 * @param multi false = 单选（点中即确认返回），true = 多选（底部「确定」）
 */
class PickerPage(
    override val title: String,
    private var tabs: List<Tab>,
    private val initial: Collection<String> = emptyList(),
    private val multi: Boolean = true,
    private val hint: String? = null,
    private val refreshTabs: (() -> List<Tab>)? = null,
    private val onConfirm: (List<String>) -> Unit,
) : PanelPage {

    /**
     * @param id 选中后写入配置的稳定标识：裸 wxid / `g:<分组id>` / `t:<标签id>`
     * @param subtitle 副标题；为空时**隐藏但保留行高**（见 [ItemRow.bind]）
     */
    data class Item(val id: String, val title: String, val subtitle: String = "")

    data class Section(val name: String, val items: List<Item>)

    /**
     * 一个 Tab。`sections` 为空表示该 Tab 没有任何内容 —— 调用方应**不传**这种 Tab
     * （宁可少一个 Tab，不给用户一个点进去是空白的页），与「空段连标题一起隐藏」同口径。
     */
    data class Tab(val name: String, val sections: List<Section>) {
        val count: Int get() = sections.sumOf { it.items.size }
    }

    /** 选中集放实例字段：`Panel.refresh()` 会重建 View 但实例不变，选择状态不丢 */
    private val selected = LinkedHashSet(initial)

    private var currentTab = 0

    private lateinit var counter: TextView
    private lateinit var adapter: Adapter
    private lateinit var okBtn: View
    private val tabBtns = ArrayList<TextView>()

    /** 当前搜索关键字；切 Tab 后重放，避免「列表全展开但搜索框有字」的状态错位 */
    private var keyword = ""

    // ---- F1：群聊补跑完成后的原地刷新所需引用（不整页重建，见 onRoomsReady）

    private var rootView: ViewGroup? = null
    private var tabRowView: View? = null
    private var roomListenerBound = false

    /** 强引用住回调：页面活着回调就活着；ChatroomSource 侧是弱引用，页面回收后自动失效 */
    private val roomListener: (Int) -> Unit = { written ->
        if (written > 0) onRoomsReady()
    }

    override fun build(ctx: Context): View {
        bindRoomListener()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiKit.dp(ctx, 16)
            setPadding(p, UiKit.dp(ctx, 8), p, 0)
        }
        rootView = root
        tabBtns.clear()
        tabRowView = null

        counter = UiKit.label(ctx, "", 13f, bold = true)
        root.addView(counter)

        // R24：只在真有多个 Tab 时才画按钮行（单 Tab 退化成原行为）
        if (tabs.size > 1) {
            val row = tabRow(ctx)
            tabRowView = row
            root.addView(row)
        }

        // R21：AlertDialog 骨架清掉 ALT_FOCUSABLE_IM 后系统才接受 IME 请求，
        // 这里再补一发显式 showSoftInput，保证点击/聚焦即弹（见 UiKit 的 KDoc）
        val search = UiKit.bindSoftInput(EditText(ctx).apply {
            this.hint = "搜索"
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            textSize = 14f
        })
        root.addView(search)

        adapter = Adapter(ctx, tabs[currentTab].sections, selected)
        // ⚠️ 不能用 `.apply { this.adapter = adapter }`：apply 块里 this.adapter 会被解析到
        // PickerPage 的同名字段上（自赋值），ListView 的 setAdapter 从未被调用——
        // 真机 2026-10-18 实锤 list.adapter==null、零行渲染。必须先落到局部变量再赋值。
        val list = UiKit.listView(ctx, 0.42f)
        list.adapter = adapter

        if (multi) {
            root.addView(quickRow(ctx, list))
        }
        root.addView(list)

        // ListView 嵌在 Panel 的 ScrollView 里，不拦一下父容器会抢走竖向滑动
        list.setOnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }

        list.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.itemAt(position) ?: return@setOnItemClickListener
            if (!multi) {
                selected.clear()
                selected.add(item.id)
                confirm()
                return@setOnItemClickListener
            }
            if (!selected.add(item.id)) selected.remove(item.id)
            adapter.notifyDataSetChanged()
            refreshCounter()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                keyword = s?.toString().orEmpty()
                adapter.filter(keyword)
                refreshCounter()
            }
        })

        root.addView(
            Rows.primaryButton(ctx, if (multi) "确定" else "取消") {
                if (multi) confirm() else Panel.pop()
            }.also { okBtn = it }
        )

        if (!hint.isNullOrBlank()) root.addView(Rows.note(ctx, hint))
        if (tabBtns.isNotEmpty()) paintTabs(ctx)
        refreshCounter()
        LogX.i(
            "PICKER open $title tabs=${tabs.size} " +
                tabs.joinToString("/") { "${it.name}:${it.count}" } +
                " preselect=${selected.size}"
        )
        return root
    }

    // ------------------------------------------------------------------ Tab 行

    /**
     * Tab 按钮行。
     *
     * 用 TextView + `UiKit.pill` 而不是 RadioGroup / TabLayout：模块 UI 全手工搭
     * （不打包 xml、不引 support 库），「一排等宽、点中变色」用 LinearLayout 权重
     * 足够。视觉与 [ForwardPicker.paintTabs] **刻意保持一致**（品牌绿 / softBg），
     * 免得同一个 App 里两套 Tab 长得不一样。
     */
    private fun tabRow(ctx: Context): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, UiKit.dp(ctx, 4), 0, UiKit.dp(ctx, 8))
            for ((i, t) in tabs.withIndex()) {
                val tv = TextView(ctx).apply {
                    text = "${t.name} ${t.count}"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, UiKit.dp(ctx, 7), 0, UiKit.dp(ctx, 7))
                    setOnClickListener { switchTo(i) }
                }
                tabBtns += tv
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                val gap = UiKit.dp(ctx, 2)
                lp.setMargins(gap, 0, gap, 0)
                addView(tv, lp)
            }
        }
    }

    /** 重绘 Tab 选中态：选中 = 品牌绿底白字，未选中 = 10% 灰底正文色 */
    private fun paintTabs(ctx: Context) {
        val r = UiKit.dp(ctx, 4).toFloat()
        for ((i, tv) in tabBtns.withIndex()) {
            if (i == currentTab) {
                tv.setTextColor(0xFFFFFFFF.toInt())
                tv.background = UiKit.pill(UiKit.brandGreen(), radiusPx = r)
            } else {
                tv.setTextColor(UiKit.fgColor(ctx))
                tv.background = UiKit.pill(UiKit.softBg(), radiusPx = r)
            }
        }
    }

    /**
     * 切 Tab。
     *
     * ⛔ **绝不碰 `selected`** —— 这是「跨 Tab 勾选存活」的唯一实现点，
     * 见类注释。要改这里，先回去读那条需求。
     */
    private fun switchTo(i: Int) {
        if (i !in tabs.indices || i == currentTab) return
        currentTab = i
        adapter.reset(tabs[i].sections)
        // 切 Tab 后重放搜索词：否则列表全展开而搜索框还写着关键字，状态自相矛盾
        if (keyword.isNotBlank()) adapter.filter(keyword)
        paintTabs(tabBtns.first().context)
        refreshCounter()
        LogX.i(
            "PICKER tab $title -> ${tabs[i].name} " +
                "visible=${adapter.entryCount()} selected=${selected.size}"
        )
    }

    // ------------------------------------------------------------ F1：原地刷新

    /** 打开时注册一次群聊补跑完成回调（幂等；无 refreshTabs 提供器则不注册） */
    private fun bindRoomListener() {
        if (refreshTabs == null || roomListenerBound) return
        roomListenerBound = true
        ChatroomSource.addRoomListener(roomListener)
    }

    /**
     * 群聊补跑完成后（主线程回调）：原地重建 Tab，**不整页刷新**。
     *
     * 刻意不用 [Panel.refresh]：整页重建会丢掉搜索框焦点与文本，还要额外做
     * 「关掉的旧页别刷到别的页」的身份校验。原地刷新天然不碰搜索框，且对已 detach
     * 的旧页无害。
     *
     * 三个「不能丢」：`selected` 全程不碰；`currentTab` 按名字保留（[PickerTabLogic]）；
     * `keyword` 不动、刷新后重放过滤。
     */
    private fun onRoomsReady() {
        val provider = refreshTabs ?: return
        val new = runCatching { provider() }.getOrNull() ?: return
        if (new.isEmpty()) return // 防御：不把页面刷成空白
        val oldNames = tabs.map { it.name }
        val newNames = new.map { it.name }
        val currentName = tabs.getOrNull(currentTab)?.name
        val nextIndex = PickerTabLogic.planTabRefresh(oldNames, newNames, currentName) ?: return
        val ctx = rootView?.context ?: return
        currentTab = nextIndex
        tabs = new
        applyTabList(ctx)
        LogX.i(
            "PICKER rooms ready $title tabs=${newNames.joinToString("/")} " +
                "current=${tabs.getOrNull(currentTab)?.name} selected=${selected.size}"
        )
    }

    /** 按当前 [tabs] 重建 Tab 按钮行 + 当前 Tab 数据（不重建搜索框） */
    private fun applyTabList(ctx: Context) {
        tabRowView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        tabBtns.clear()
        tabRowView = null
        if (tabs.size > 1) {
            val row = tabRow(ctx)
            tabRowView = row
            rootView?.addView(row, 1) // 插回 counter 之后、搜索框之前
            paintTabs(ctx)
        }
        adapter.reset(tabs[currentTab].sections)
        if (keyword.isNotBlank()) adapter.filter(keyword)
        refreshCounter()
    }

    // ------------------------------------------------------------------ 快捷行

    private fun quickRow(ctx: Context, list: ListView): View {
        fun mk(text: String, act: (Int) -> Unit) = Button(ctx).apply {
            this.text = text
            setOnClickListener {
                // 只遍历当前可见行 ⇒ 天然只作用于当前 Tab，符合「可见」的字面语义
                for (i in 0 until adapter.count) {
                    adapter.itemAt(i)?.let { act(i) }
                }
                adapter.notifyDataSetChanged()
                refreshCounter()
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                mk("全选可见") { i -> adapter.itemAt(i)?.let { selected.add(it.id) } },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                mk("反选可见") { i ->
                    adapter.itemAt(i)?.let {
                        if (!selected.add(it.id)) selected.remove(it.id)
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                mk("清空") { selected.clear() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
    }

    private fun confirm() {
        val ids = selected.toList()
        LogX.i("PICKER confirm $title n=${ids.size}")
        onConfirm(ids)
        Panel.pop()
    }

    private fun refreshCounter() {
        if (!::counter.isInitialized) return
        // 已选 = 全局（跨 Tab 累计）；可见 = 当前 Tab 的**条目数**
        // ⚠️ 用 entryCount() 而不是 getCount()：后者含每个段一个标题行，会每段多算 1
        counter.text = "已选 ${selected.size} 项 · 可见 ${adapter.entryCount()}"
        if (multi && ::okBtn.isInitialized) {
            (okBtn as? TextView)?.text = if (selected.isEmpty()) "确定" else "确定（${selected.size}）"
        }
    }

    // ------------------------------------------------------------------ 列表

    private sealed class Row {
        data class Header(val text: String) : Row()
        data class Entry(val item: Item) : Row()
    }

    private class Adapter(
        private val ctx: Context,
        initialSections: List<Section>,
        private val selected: Set<String>,
    ) : BaseAdapter() {

        /**
         * **当前 Tab 的完整数据源**（不过滤）。只有切 Tab 时由 [reset] 改写。
         *
         * ⚠️ 两个必须分开的概念（R24 复核 B1 + 其衍生坑）：
         * - `source` = 当前 Tab 的全量段，**过滤不改变它**；
         * - `visible` = 实际渲染的行，随时可被 [filter] 重算。
         *
         * 若把两者混成一个字段（之前就是），会出现两个方向的错：
         * ① 构造时固定 ⇒ 切到非首 Tab 后一搜索，列表按**首 Tab**的 sections 重算，
         *    Tab 高亮「群聊」而列表是好友（B1）；
         * ② 让 `filter` 把结果写回该字段 ⇒ 清空搜索框时「全量」变成「上次的过滤结果」，
         *    用户再也回不到完整列表。
         */
        private var source: List<Section> = initialSections

        private val visible = ArrayList<Row>()

        init {
            reset(initialSections)
        }

        /**
         * 换数据源（切 Tab）。**只应由 Tab 切换调用**。
         *
         * 换完立刻按当前关键字重算可见行：否则切到新 Tab 时会短暂/长期显示未过滤的全量，
         * 与搜索框里的关键字自相矛盾。
         */
        fun reset(list: List<Section>) {
            source = list
            render(list)
        }

        private fun render(list: List<Section>) {
            visible.clear()
            for (s in list) {
                if (s.items.isEmpty()) continue
                visible.add(Row.Header(s.name))
                for (it in s.items) visible.add(Row.Entry(it))
            }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = visible.size
        override fun getItem(position: Int): Row = visible[position]
        override fun getItemId(position: Int): Long = position.toLong()

        /** 只返回真正的条目行；段标题返回 null */
        fun itemAt(position: Int): Item? = (visible.getOrNull(position) as? Row.Entry)?.item

        /**
         * 真正的**条目数**（不含段标题）。
         *
         * `getCount()` 含每个段一个 `Row.Header`，拿它当「可见 N」会每段多算 1
         * （R24 复核 S2）。计数器用这个。
         */
        fun entryCount(): Int = visible.count { it is Row.Entry }

        override fun getItemViewType(position: Int): Int =
            if (visible[position] is Row.Header) 0 else 1

        override fun getViewTypeCount(): Int = 2

        override fun isEnabled(position: Int): Boolean = visible[position] is Row.Entry

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            return when (val r = visible[position]) {
                is Row.Header -> UiKit.label(ctx, r.text, 12f, gray = true).apply {
                    setPadding(UiKit.dp(ctx, 4), UiKit.dp(ctx, 6), UiKit.dp(ctx, 4), UiKit.dp(ctx, 2))
                }
                is Row.Entry -> {
                    val v = convertView as? ItemRow ?: ItemRow(ctx)
                    v.bind(r.item, selected.contains(r.item.id))
                    v
                }
            }
        }

        /**
         * 过滤只改**可见行**，不动 [source]、更不动 `selected`。
         *
         * ⚠️ 必须走 [render] 而不是 [reset]：`reset` 会改写 `source`，
         * 那样清空搜索框时「全量」就变成了「上次的过滤结果」，用户回不到完整列表。
         */
        fun filter(keyword: String) {
            val kw = keyword.trim()
            if (kw.isEmpty()) {
                render(source)
                return
            }
            val lower = kw.lowercase()
            val kept = source.mapNotNull { s ->
                val hit = s.items.filter {
                    it.title.lowercase().contains(lower) || it.subtitle.lowercase().contains(lower)
                }
                if (hit.isEmpty()) null else Section(s.name, hit)
            }
            render(kept)
        }
    }

    private class ItemRow(ctx: Context) : LinearLayout(ctx) {
        private val box = CheckBox(ctx).apply {
            // 关掉焦点与点击，让触摸冒泡到 ListView —— 否则整行点不动
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
        }
        private val name = TextView(ctx).apply { textSize = 15f }
        private val sub = TextView(ctx).apply {
            textSize = 11f
            setTextColor(UiKit.muted())
            setSingleLine(true)
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = UiKit.dp(ctx, 12)
            setPadding(p, UiKit.dp(ctx, 6), p, UiKit.dp(ctx, 6))
            addView(box)
            addView(
                LinearLayout(ctx).apply {
                    orientation = VERTICAL
                    addView(name)
                    addView(sub)
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            )
        }

        fun bind(item: Item, checked: Boolean) {
            box.isChecked = checked
            name.text = item.title
            // 副标题为空时用 INVISIBLE 而非 GONE：**保留行高**。
            // 否则好友行比群聊行矮，滚动时高度跳动（群聊 Tab 里尤其明显）
            sub.text = item.subtitle
            sub.visibility = if (item.subtitle.isBlank()) INVISIBLE else VISIBLE
        }
    }
}
