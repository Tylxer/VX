package com.lx.wxgroup.feature

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.lx.wxgroup.data.Group
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.ui.PanelPage
import com.lx.wxgroup.ui.Rows
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX

/**
 * 分组管理页（K1 入栈版）：新建 / 重命名 / 删除 / ▲▼ 排序 / 清空成员。
 *
 * 之前它是自己弹一个 AlertDialog（`GroupManager`），从主界面进来后「返回」会把整个面板关掉，
 * 而且排序需求（v6.2 定的 ▲▼ 按钮）也没做。现在改成页面栈里的普通一页。
 *
 * ▲▼ 用的是「交换相邻两组的 sort_order」，不动 ListView 结构 —— 比拖拽好实现得多，
 * 也不会有长按拖拽那种跟列表滚动抢手势的问题。
 *
 * 成员管理仍然走 `MemberPage`（独立弹窗，暂未入栈）。
 */
class GroupManagerPage : PanelPage {

    override val title = "我的分组"

    override fun build(ctx: Context): View {
        val db = GroupDb.current()
        if (db == null) return Rows.note(ctx, "数据库还没准备好")

        val adapter = Adapter(ctx, db)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(Rows.note(ctx, "点击管理成员 · ▲▼ 调整顺序 · 长按重命名或删除"))

        // ⚠️ 同 PickerPage：apply 块内 this.adapter 赋值不可靠，显式落到局部变量再设
        val list = UiKit.listView(ctx, 0.40f)
        list.adapter = adapter
        // ListView 嵌在 Panel 的 ScrollView 里，不拦一下父容器会抢走竖向滑动
        list.setOnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        root.addView(list)

        list.setOnItemClickListener { _, _, position, _ ->
            val g = adapter.getItem(position)
            MemberPage.show(g.id, g.name)
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            actions(ctx, db, adapter.getItem(position)) {
                adapter.reload()
                // 分组/成员关系变了 ⇒ 让 K7 会话列表重算分组行。
                //
                // 为什么统一放在 onChanged 里，而不是在每个 when 分支各补一行：
                // 这个页面的 4 个操作（重命名 / 置顶 / 删除 / 以及挂在上面的成员增删）
                // **每一个都会改分组行该显示的内容**，漏补任何一个都是同一个 bug 复发。
                // 收口到 onChanged 一处，以后加新操作也不会漏。
                //
                // ⚠️ 必须是 refreshMembers() 而非 refresh()：删除分组会连带清掉
                // `group_member` 里的成员关系（见下方 confirm 文案），而 refresh()
                // 不重读 cachedMembers —— 筛选态下会拿旧成员集算，分组行成员数残留。
                ConvFilter.refreshMembers()
            }
            true
        }

        root.addView(
            Rows.primaryButton(ctx, "新建分组") {
                UiKit.input(ctx, "新建分组", "", "分组名称") { name ->
                    val id = db.createGroup(name)
                    if (id > 0) {
                        adapter.reload()
                        // 新分组 ⇒ 会话列表要插一行「没有会话的分组行」（ConvLayout 规则 4）
                        ConvFilter.refreshMembers()
                        UiKit.toast(ctx, "已创建：$name")
                        LogX.i("MGR create id=$id name=$name")
                    } else {
                        UiKit.toast(ctx, "创建失败（可能是重名）")
                    }
                }
            }
        )

        // 破坏性操作，放最后且必须二次确认
        root.addView(
            Rows.link(ctx, "清空全部分组成员", "分组保留，只移除成员关系") {
                UiKit.confirm(ctx, "清空成员", "将移除所有分组下的好友（分组保留），确认？") {
                    var n = 0
                    for (g in db.listGroups()) n += db.clearMembers(g.id)
                    // 成员关系变了 ⇒ 重读 cachedMembers 再重算（refresh() 不重读成员，筛选态下会无效）
                    ConvFilter.refreshMembers()
                    adapter.reload()
                    UiKit.toast(ctx, "已移除 $n 条成员关系")
                    LogX.i("MGR clear all members=$n")
                }
            }
        )
        return root
    }

    /** 长按某一行：添加成员 / 重命名 / 删除 / 置顶切换 */
    private fun actions(ctx: Context, db: GroupDb, group: Group, onChanged: () -> Unit) {
        val pinTitle = if (group.pinned) "取消置顶" else "置顶分组"
        val items = arrayOf("添加成员", "重命名", pinTitle, "删除分组")
        android.app.AlertDialog.Builder(ctx)
            .setTitle(group.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> BatchPicker.show(group.id, group.name)
                    1 -> UiKit.input(ctx, "重命名", group.name, "新名称") { name ->
                        if (db.renameGroup(group.id, name)) {
                            onChanged()
                            UiKit.toast(ctx, "已重命名")
                        } else {
                            UiKit.toast(ctx, "重命名失败（可能是重名）")
                        }
                    }
                    2 -> {
                        db.setGroupPinned(group.id, !group.pinned)
                        onChanged()
                        UiKit.toast(ctx, if (group.pinned) "已取消置顶" else "已置顶")
                    }
                    3 -> UiKit.confirm(
                        ctx,
                        "删除分组",
                        "删除「${group.name}」，其中的 ${db.countMembers(group.id)} 条成员关系会一并清除。"
                    ) {
                        if (db.deleteGroup(group.id)) {
                            onChanged()
                            UiKit.toast(ctx, "已删除")
                            LogX.i("MGR delete id=${group.id} name=${group.name}")
                        }
                    }
                }
            }
            .show()
    }

    private class Adapter(
        private val ctx: Context,
        private val db: GroupDb,
    ) : BaseAdapter() {

        private val items = ArrayList<Group>(db.listGroups())

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Group = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? GroupRow) ?: GroupRow(ctx)
            row.bind(getItem(position), db.countMembers(getItem(position).id), position, items.size) { up ->
                move(position, up)
            }
            return row
        }

        /** 数据变了就地重建内容，保留同一个 adapter 实例，滚动位置不丢 */
        fun reload() {
            items.clear()
            items.addAll(db.listGroups())
            notifyDataSetChanged()
        }

        private fun move(position: Int, up: Boolean) {
            val g = items.getOrNull(position) ?: return
            if (!db.moveGroup(g.id, up)) {
                UiKit.toast(ctx, if (up) "已经在最上面" else "已经在最下面")
                return
            }
            reload()
            // 排序动了 sort_order ⇒ 会话列表里分组行（尤其"没有会话的垫底行"）的位置要跟着变。
            // 分组表变更靠 configStamp 里的 groups.hashCode() 就能捕获，
            // 所以这里用 refresh() 就够，不需要 refreshMembers()（成员关系没动）。
            ConvFilter.refresh()
            LogX.d("MGR move id=${g.id} up=$up")
        }
    }

    private class GroupRow(ctx: Context) : LinearLayout(ctx) {
        private val name = TextView(ctx).apply { textSize = 16f }
        private val sub = TextView(ctx).apply {
            textSize = 11f
            setTextColor(UiKit.muted())
        }
        private val up = moveBtn(ctx, "▲")
        private val down = moveBtn(ctx, "▼")

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = UiKit.dp(ctx, 16)
            setPadding(p, UiKit.dp(ctx, 6), p, UiKit.dp(ctx, 6))

            val texts = LinearLayout(ctx).apply {
                orientation = VERTICAL
                addView(name)
                addView(sub)
            }
            addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(up)
            addView(down)
        }

        fun bind(g: Group, count: Int, position: Int, total: Int, onMove: (Boolean) -> Unit) {
            name.text = if (g.pinned) "置顶 · ${g.name}" else g.name
            sub.text = "$count 人"
            // 边界上的按钮置灰而不是隐藏：隐藏会让整行宽度跳动
            setEnabled(up, position > 0)
            setEnabled(down, position < total - 1)
            up.setOnClickListener { onMove(true) }
            down.setOnClickListener { onMove(false) }
        }

        private fun setEnabled(v: TextView, on: Boolean) {
            v.isEnabled = on
            v.alpha = if (on) 1f else 0.3f
        }

        private fun moveBtn(ctx: Context, glyph: String): TextView = TextView(ctx).apply {
            text = glyph
            textSize = 15f
            setTextColor(UiKit.muted())
            gravity = Gravity.CENTER
            val w = UiKit.dp(ctx, 34)
            setPadding(UiKit.dp(ctx, 6), UiKit.dp(ctx, 10), UiKit.dp(ctx, 6), UiKit.dp(ctx, 10))
            layoutParams = LayoutParams(w, LayoutParams.WRAP_CONTENT)
            // 关键：关掉 focusable，否则按钮吃掉焦点，ListView 的 onItemClick 不触发；
            // clickable 保持默认，setOnClickListener 会让它为 true
            isFocusable = false
            isFocusableInTouchMode = false
        }
    }
}
