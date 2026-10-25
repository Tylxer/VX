package com.lx.wxgroup.feature

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
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
 * 数据按段组织（好友 / 我的分组 / 微信标签），所以同一个页面既能选单一来源，
 * 也能做群发那种「混合选目标」。
 *
 * @param multi false = 单选（点中即确认返回），true = 多选（底部「确定」）
 */
class PickerPage(
    override val title: String,
    private val sections: List<Section>,
    private val initial: Collection<String> = emptyList(),
    private val multi: Boolean = true,
    private val hint: String? = null,
    private val onConfirm: (List<String>) -> Unit,
) : PanelPage {

    data class Item(val id: String, val title: String, val subtitle: String = "")

    data class Section(val name: String, val items: List<Item>)

    /** 选中集放实例字段：`Panel.refresh()` 会重建 View 但实例不变，选择状态不丢 */
    private val selected = LinkedHashSet(initial)

    private lateinit var counter: TextView
    private lateinit var adapter: Adapter
    private lateinit var okBtn: View

    override fun build(ctx: Context): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiKit.dp(ctx, 16)
            setPadding(p, UiKit.dp(ctx, 8), p, 0)
        }

        counter = UiKit.label(ctx, "", 13f, bold = true)
        root.addView(counter)

        // R21：AlertDialog 骨架清掉 ALT_FOCUSABLE_IM 后系统才接受 IME 请求，
        // 这里再补一发显式 showSoftInput，保证点击/聚焦即弹（见 UiKit 的 KDoc）
        val search = UiKit.bindSoftInput(EditText(ctx).apply {
            this.hint = "搜索"
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            textSize = 14f
        })
        root.addView(search)

        adapter = Adapter(ctx, sections, selected)
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
                adapter.filter(s?.toString().orEmpty())
                refreshCounter()
            }
        })

        root.addView(
            Rows.primaryButton(ctx, if (multi) "确定" else "取消") {
                if (multi) confirm() else Panel.pop()
            }.also { okBtn = it }
        )

        if (!hint.isNullOrBlank()) root.addView(Rows.note(ctx, hint))
        refreshCounter()
        LogX.i("PICKER open $title sections=${sections.size} preselect=${selected.size}")
        return root
    }

    private fun quickRow(ctx: Context, list: ListView): View {
        fun mk(text: String, act: (Int) -> Unit) = android.widget.Button(ctx).apply {
            this.text = text
            setOnClickListener {
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
        counter.text = "已选 ${selected.size} 项 · 可见 ${adapter.count}"
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
        private val sections: List<Section>,
        private val selected: Set<String>,
    ) : BaseAdapter() {

        private val visible = ArrayList<Row>()

        init {
            reset(sections)
        }

        private fun reset(list: List<Section>) {
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
                if (hit.isEmpty()) null else Section(s.name, hit)
            }
            reset(kept)
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
            gravity = android.view.Gravity.CENTER_VERTICAL
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
            sub.text = item.subtitle
        }
    }
}
