package com.lx.wxgroup.feature

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.lx.wxgroup.core.ChatroomSource
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.ui.Panel
import com.lx.wxgroup.ui.PanelPage
import com.lx.wxgroup.ui.Rows
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX

/**
 * 需求 6.6「强制转发」的 K3 目标选择器：**三 Tab 混合多选**。
 *
 * 为什么单独建一个而不复用 [PickerPage]：
 * - [PickerPage] 是「多段平铺在一个列表里」的形态，段标题占行、两千好友要一路滚过
 *   分组段和标签段才能到底；转发是**高频、目标量大**的操作，必须能按来源切页。
 * - [BatchPicker.show(groupId, groupName)] 是「给某个分组挑成员」的单一形态，
 *   签名和语义都跟「混选三种来源」对不上。
 * 所以这里新建，但**数据源与行样式一律复用现有的**（[GroupDb] / [LabelSource] /
 * [UiKit] / [PickerPage.Section]），[BatchPicker]、[GroupPicker]、[PickerPage]
 * 三处既有调用方一行都不动。
 *
 * 三条从 [PickerPage] / [BatchPicker] 搬来的硬经验（都踩过，别破）：
 * 1. 必须 ListView + BaseAdapter 复用行 —— 2245 个好友一次性 addView 会卡死甚至 OOM；
 * 2. CheckBox 必须关掉 focusable / clickable，否则它吃掉触摸事件，
 *    `onItemClick` 永不触发（经典「点了没反应」）；
 * 3. 搜索只改**可见行**，不动 [selected] —— 勾上的人被过滤掉再清掉搜索框，勾选还在。
 *
 * 选中集与当前 Tab 都是**实例字段**：`Panel.refresh()` 会重建 View 但实例不变，
 * 状态不丢（[PickerPage] 同款机制）。
 *
 * @param exclude 排除集（一般是当前会话自己，转发回原会话没意义）。展开结果里
 *                不会出现它们，列表里也直接不显示，省得勾了才发现没用。
 */
class ForwardPicker(
    override val title: String = "转发到多个",
    private val exclude: Set<String> = emptySet(),
    private val hint: String = HINT_DEFAULT,
    private val onConfirm: (Result) -> Unit,
) : PanelPage {

    /**
     * 确认结果。
     *
     * @param wxids  **已经展开 + 去重 + 排除**的目标 wxid（需求「同一人被多个分组/
     *               标签选中时只发一次」在这里落地，调用方拿去直接发即可）
     * @param picked 用户勾中的**节点**数（分组/标签算一个节点），只用于日志与提示，
     *               节点数 ≠ 人数
     */
    data class Result(val wxids: List<String>, val picked: Int)

    private data class TabData(val name: String, val sections: List<PickerPage.Section>)

    /** 一行：要么是段标题（item==null），要么是可选条目 */
    private class Row(val header: String?, val item: PickerPage.Item?)

    private val selected = LinkedHashSet<String>()

    private var tabIndex = 0

    /** 防「确定」被连点两次导致同一批目标发两遍 */
    private var confirmed = false

    /** 首次 build 时装载，refresh 复用（重建 View 不重查库） */
    private var tabs: List<TabData> = emptyList()
    private var adapters: List<TabAdapter> = emptyList()
    private var tabBtns: List<TextView> = emptyList()

    /** 当前搜索词，切 Tab 时要带到新页上 */
    private var keyword = ""

    private lateinit var counter: TextView
    private lateinit var searchBox: EditText
    private lateinit var list: ListView
    private lateinit var okBtn: TextView

    override fun build(ctx: Context): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiKit.dp(ctx, 16)
            setPadding(p, UiKit.dp(ctx, 8), p, 0)
        }

        counter = UiKit.label(ctx, "", 13f, bold = true)
        root.addView(counter)

        if (tabs.isEmpty()) {
            tabs = loadTabs()
            adapters = tabs.map { TabAdapter(ctx, it.sections, selected) }
            LogX.i("FWDPICK load tabs=${tabs.size} names=${tabs.joinToString(",") { it.name }}")
        }
        if (tabs.isEmpty()) {
            root.addView(Rows.note(ctx, EMPTY_HINT))
            LogX.w("FWDPICK no tab available")
            return root
        }
        if (tabIndex >= tabs.size) tabIndex = 0

        // R21：见 UiKit 中该助手的 KDoc
        searchBox = UiKit.bindSoftInput(EditText(ctx).apply {
            this.hint = "搜索昵称 / 备注 / 群名"
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            textSize = 14f
            setText(keyword)
            setSelection(keyword.length)
        })
        root.addView(searchBox)

        if (tabs.size > 1) {
            root.addView(tabRow(ctx))
            paintTabs(ctx)
        }

        root.addView(quickRow(ctx))

        // ⚠️ 不能用 `.apply { this.adapter = ... }`：apply 块里同名字段会抢走赋值，
        // ListView.setAdapter 从未被调用（真机实锤 list.adapter==null、零行渲染）
        list = UiKit.listView(ctx, LIST_RATIO)
        list.adapter = adapters[tabIndex]
        // ListView 嵌在 Panel 的 ScrollView 里，不拦一下父容器会抢走竖向滑动
        list.setOnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        list.setOnItemClickListener { _, _, position, _ -> toggle(position) }
        root.addView(list)

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                keyword = s?.toString().orEmpty()
                adapters[tabIndex].filter(keyword)
                refreshCounter()
            }
        })

        root.addView(buttonRow(ctx))
        if (hint.isNotBlank()) root.addView(Rows.note(ctx, hint))
        refreshCounter()
        return root
    }

    // ---------------------------------------------------------------- 三个 Tab

    /**
     * 装载三个 Tab 的数据。
     *
     * 空 Tab 直接不出现（宁可少一页，不给用户一个点进去是空白的页）—— 与
     * [PickerPage]「空段连标题一起隐藏」和 `LabelSource.ready()` 的门禁一致。
     */
    private fun loadTabs(): List<TabData> {
        val out = ArrayList<TabData>(3)
        val db = GroupDb.current()
        if (db == null) {
            LogX.w("FWDPICK db not ready")
            return out
        }
        // F1 补齐（R36）：本页自己实现 [loadTabs]，不走 [PickerTabs.all]，
        // 是唯一没接群聊补跑的选择器入口。缺了这一行，清缓存 / 新装后第一次转发时
        // 模块表里可能一条群聊都没有 ⇒ 群聊段整段不出现（空段连标题一起隐藏），
        // 用户会以为「转发不能选群」。
        // runIfNeeded() 立即返回（起 daemon 线程 + CAS 节流），不阻塞 UI；
        // 与 PickerTabs.all() 里那次共用同一把节流锁，重复调用不会重复全表扫。
        // ⚠️ 未做的那一半：补跑完成后的**原地刷新**（要把新出现的群聊段画出来）
        // 需要持有已构建的 View 引用并处理「页已退出」，属需真机验证的改动，留待后轮。
        // ⇒ 本次的效果是「打开转发选择器即触发补跑，本次或下次打开可见」，
        //    不再是「除非手动进通讯录滚一遍，否则永远没有」。
        ChatroomSource.runIfNeeded()

        // Tab1 好友·群聊：一次全量查询后按 wxid_type 拆两段（好友在前、群聊在后）
        val all = runCatching { db.allFriends() }.getOrDefault(emptyList())
        val friends = all.filter { it.type == GroupDb.WxidType.FRIEND && it.wxid !in exclude }
        val rooms = all.filter { it.type == GroupDb.WxidType.CHATROOM && it.wxid !in exclude }
        val contact = ArrayList<PickerPage.Section>(2)
        if (friends.isNotEmpty()) {
            contact.add(
                PickerPage.Section("好友", friends.map { PickerPage.Item(it.wxid, it.displayName, it.wxid) })
            )
        }
        if (rooms.isNotEmpty()) {
            contact.add(
                PickerPage.Section("群聊", rooms.map { PickerPage.Item(it.wxid, it.displayName, "群聊") })
            )
        }
        if (contact.isNotEmpty()) out.add(TabData(TAB_CONTACT, contact))

        // Tab2 模块分组：选整个分组 = 发送前展开成成员（前缀 [ForwardLogic.PREFIX_GROUP]）
        val groups = runCatching { db.listGroups() }.getOrDefault(emptyList())
        if (groups.isNotEmpty()) {
            out.add(
                TabData(
                    TAB_GROUP,
                    listOf(
                        PickerPage.Section(
                            "模块分组",
                            groups.map {
                                PickerPage.Item(
                                    id = "${ForwardLogic.PREFIX_GROUP}${it.id}",
                                    title = it.name,
                                    subtitle = "分组 · ${runCatching { db.countMembers(it.id) }.getOrDefault(0)} 人",
                                )
                            }
                        )
                    )
                )
            )
        }

        // Tab3 微信标签：数据来自 [LabelSource] 的只读缓存（ContactLabel + rcontact）。
        // ⚠️ `rcontact.contactLabelIds` 的分隔符已真机取证为**英文逗号**（单个标签是裸
        // 数字，如 `43,46`），解析**一律走 [LabelLogic.splitIds]**：这里不自己 split，
        // 也不改 LabelSource / LabelLogic 的解析规则，避免两处规则漂移。
        if (LabelSource.ready()) {
            val labels = LabelSource.allLabels()
                .filter { !it.name.isNullOrBlank() }
                .map {
                    PickerPage.Item(
                        id = "${ForwardLogic.PREFIX_LABEL}${it.id}",
                        title = it.name!!,
                        subtitle = "微信标签 · ${it.memberCount} 人",
                    )
                }
            if (labels.isNotEmpty()) out.add(TabData(TAB_LABEL, listOf(PickerPage.Section("微信标签", labels))))
        }
        return out
    }

    private fun tabRow(ctx: Context): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val btns = ArrayList<TextView>(tabs.size)
        for (i in tabs.indices) {
            val tv = TextView(ctx).apply {
                text = tabs[i].name
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, UiKit.dp(ctx, 8), 0, UiKit.dp(ctx, 8))
                setOnClickListener { switchTo(i) }
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(UiKit.dp(ctx, 2), 0, UiKit.dp(ctx, 2), 0)
            row.addView(tv, lp)
            btns.add(tv)
        }
        tabBtns = btns
        return row
    }

    private fun paintTabs(ctx: Context) {
        val r = UiKit.dp(ctx, 4).toFloat()
        for ((i, tv) in tabBtns.withIndex()) {
            if (i == tabIndex) {
                tv.setTextColor(0xFFFFFFFF.toInt())
                tv.background = UiKit.pill(UiKit.brandGreen(), radiusPx = r)
            } else {
                tv.setTextColor(UiKit.fgColor(ctx))
                tv.background = UiKit.pill(UiKit.softBg(), radiusPx = r)
            }
        }
    }

    private fun switchTo(index: Int) {
        if (index == tabIndex || index !in tabs.indices) return
        tabIndex = index
        // 搜索词跟着切过去：用户多半是在找同一批人
        adapters[index].filter(keyword)
        list.adapter = adapters[index]
        paintTabs(list.context)
        refreshCounter()
        LogX.d("FWDPICK tab -> ${tabs[index].name}")
    }

    // ---------------------------------------------------------------- 交互

    private fun toggle(position: Int) {
        val item = adapters[tabIndex].itemAt(position) ?: return
        if (!selected.add(item.id)) selected.remove(item.id)
        adapters[tabIndex].notifyDataSetChanged()
        refreshCounter()
    }

    private fun quickRow(ctx: Context): View {
        fun mk(text: String, act: (PickerPage.Item) -> Unit) = android.widget.Button(ctx).apply {
            this.text = text
            setOnClickListener {
                val a = adapters[tabIndex]
                for (i in 0 until a.count) a.itemAt(i)?.let(act)
                a.notifyDataSetChanged()
                refreshCounter()
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            // 只动「当前可见项」：搜索状态下全选不该把看不见的两千人也勾上
            addView(
                mk("全选可见") { selected.add(it.id) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                mk("清空") { selected.clear() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
    }

    private fun buttonRow(ctx: Context): View {
        val r = UiKit.dp(ctx, 6).toFloat()
        okBtn = TextView(ctx).apply {
            text = "确定"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, UiKit.dp(ctx, 12), 0, UiKit.dp(ctx, 12))
            background = UiKit.pill(UiKit.brandGreen(), radiusPx = r)
            setOnClickListener { confirm(ctx) }
        }
        val cancel = TextView(ctx).apply {
            text = "取消"
            textSize = 16f
            setTextColor(UiKit.fgColor(ctx))
            gravity = Gravity.CENTER
            setPadding(0, UiKit.dp(ctx, 12), 0, UiKit.dp(ctx, 12))
            background = UiKit.pill(UiKit.softBg(), radiusPx = r)
            setOnClickListener { Panel.pop() }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(0, UiKit.dp(ctx, 8), UiKit.dp(ctx, 4), UiKit.dp(ctx, 8))
            addView(okBtn, lp)
            val lp2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp2.setMargins(UiKit.dp(ctx, 4), UiKit.dp(ctx, 8), 0, UiKit.dp(ctx, 8))
            addView(cancel, lp2)
        }
    }

    private fun refreshCounter() {
        if (!::counter.isInitialized) return
        val a = adapters.getOrNull(tabIndex)
        counter.text = "已选 ${selected.size} 项 · 本页可见 ${a?.count ?: 0}"
        if (::okBtn.isInitialized) {
            okBtn.text = if (selected.isEmpty()) "确定" else "确定（${selected.size}）"
        }
    }

    /**
     * 确定：展开 → 去重 → 排除 → 回调。
     *
     * 去重这一步就在这里做完（[ForwardLogic.resolveTargets]），调用方拿到的
     * [Result.wxids] 保证无重复、不含 [exclude]。
     */
    private fun confirm(ctx: Context) {
        if (confirmed) return
        val picked = selected.toList()
        if (picked.isEmpty()) {
            UiKit.toast(ctx, "还没选转发目标")
            return
        }
        val wxids = ForwardLogic.resolveTargets(picked, buckets(), exclude)
        if (wxids.isEmpty()) {
            LogX.w("FWDPICK resolve empty picked=${picked.size} exclude=${exclude.size}")
            UiKit.toast(ctx, "展开后没有可转发的目标（分组/标签为空或全部被排除）")
            return
        }
        if (PENDING_MAX_TARGETS > 0 && wxids.size > PENDING_MAX_TARGETS) {
            // 取证到真实上限之前这条分支不会触发
            UiKit.toast(ctx, "一次最多转发 $PENDING_MAX_TARGETS 个目标，当前 ${wxids.size} 个")
            return
        }
        confirmed = true
        LogX.i(
            "FWDPICK confirm picked=${picked.size} targets=${wxids.size} " +
                "exclude=${exclude.size} ids=${picked.take(8).joinToString("|")}"
        )
        Panel.pop()
        onConfirm(Result(wxids, picked.size))
    }

    /** 分组 id → 成员 wxid；标签 id → 成员 wxid（只读缓存，不写任何库） */
    private fun buckets(): ForwardLogic.Buckets {
        val db = GroupDb.current()
        val groups = HashMap<String, List<String>>()
        if (db != null) {
            for (g in runCatching { db.listGroups() }.getOrDefault(emptyList())) {
                val members = runCatching { db.memberWxids(g.id) }.getOrDefault(emptySet())
                if (members.isNotEmpty()) groups[g.id.toString()] = members.toList()
            }
        }
        return ForwardLogic.Buckets(
            groups = groups,
            labels = runCatching { LabelSource.membersByLabelId() }.getOrDefault(emptyMap()),
        )
    }

    // ---------------------------------------------------------------- 列表

    private class TabAdapter(
        private val ctx: Context,
        private val sections: List<PickerPage.Section>,
        private val selected: Set<String>,
    ) : BaseAdapter() {

        private val visible = ArrayList<Row>()

        init {
            reset(sections)
        }

        private fun reset(list: List<PickerPage.Section>) {
            visible.clear()
            for (s in list) {
                if (s.items.isEmpty()) continue
                visible.add(Row(s.name, null))
                for (it in s.items) visible.add(Row(null, it))
            }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = visible.size
        override fun getItem(position: Int): Row = visible[position]
        override fun getItemId(position: Int): Long = position.toLong()

        /** 段标题行返回 null（不可点） */
        fun itemAt(position: Int): PickerPage.Item? = visible.getOrNull(position)?.item

        override fun getItemViewType(position: Int): Int =
            if (visible[position].item == null) 0 else 1

        override fun getViewTypeCount(): Int = 2

        override fun isEnabled(position: Int): Boolean = visible[position].item != null

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val r = visible[position]
            val item = r.item
            if (item == null) {
                return UiKit.label(ctx, r.header.orEmpty(), 12f, gray = true).apply {
                    setPadding(
                        UiKit.dp(ctx, 4), UiKit.dp(ctx, 6),
                        UiKit.dp(ctx, 4), UiKit.dp(ctx, 2)
                    )
                }
            }
            val v = convertView as? CheckRow ?: CheckRow(ctx)
            v.bind(item, item.id in selected)
            return v
        }

        /** 过滤只改可见行；空段连标题一起隐藏。selected 完全不受影响 */
        fun filter(keyword: String) {
            val kw = keyword.trim()
            if (kw.isEmpty()) {
                reset(sections)
                return
            }
            val lower = kw.lowercase()
            val kept = sections.mapNotNull { s ->
                val hit = s.items.filter {
                    it.title.lowercase().contains(lower) || it.subtitle.lowercase().contains(lower)
                }
                if (hit.isEmpty()) null else PickerPage.Section(s.name, hit)
            }
            reset(kept)
        }
    }

    private class CheckRow(ctx: Context) : LinearLayout(ctx) {
        private val box = CheckBox(ctx).apply {
            // 关掉焦点与点击，让触摸冒泡到 ListView —— 否则整行点不动
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
        }
        private val name = TextView(ctx).apply {
            textSize = 15f
            setTextColor(UiKit.fgColor(ctx))
            setSingleLine(true)
        }
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

        fun bind(item: PickerPage.Item, checked: Boolean) {
            box.isChecked = checked
            name.text = item.title
            sub.text = item.subtitle
        }
    }

    private companion object {
        const val TAB_CONTACT = "好友·群聊"
        const val TAB_GROUP = "模块分组"
        const val TAB_LABEL = "微信标签"

        /** 列表高度占屏比（真机 heightPixels=2943 → ≈1236px，Panel 内容区 0.62 装得下） */
        const val LIST_RATIO = 0.42f

        const val HINT_DEFAULT =
            "三个 Tab 可混选：好友·群聊 / 模块分组 / 微信标签。分组与标签会展开成具体的人，" +
                "同一人只发一次；当前会话自动排除。"

        const val EMPTY_HINT =
            "还没有可选的目标：好友缓存为空（先去通讯录滚一遍）、没有模块分组、微信标签也没读到。"

        /**
         * **待取证**：一次批量转发允许的最大目标数。
         *
         * 微信自己的原生转发 UI 有「最多选 9 个会话」的限制，但那是**它自己 UI 层的
         * 限制**；模块走的是「对每个目标分别调一次」的批量链路，是否继承这个上限、
         * 以及原生入口一次能吞几条，**没有取证**。
         * 0 = 暂不限（当前不会拦任何人）；取证到真实上限后改成常量，[confirm] 里的
         * 拦截分支自然生效。**严禁猜微信内部限制**。
         */
        const val PENDING_MAX_TARGETS = 0
    }
}
