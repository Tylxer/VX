package com.lx.wxgroup.feature

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.lx.wxgroup.core.ChatOpener
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.GroupMember
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX

/**
 * M3 独立分组页：一个分组的成员都在这里。
 *
 * 为什么是「独立页」而不是在会话列表里内联展开：
 * 内联展开要做 ListView 嵌套 ListView，手势冲突 + 复用失效，成员一多就卡。
 * 独立页是普通全屏 Dialog，几百人也是一条 ListView 平铺，性能可控。
 *
 * 交互（用户拍板）：
 *  - 点成员 -> 直接打开和他的单聊
 *  - 长按成员 -> 移出本组 / 加入其他分组
 */
object GroupDetailPage {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun open(context: Context?, groupId: Long, groupName: String) {
        mainHandler.post {
            runCatching { openInternal(context, groupId, groupName) }
                .onFailure { LogX.e("DETAIL open failed", it) }
        }
    }

    private fun openInternal(context: Context?, groupId: Long, groupName: String) {
        val ctx = HostContext.activity() ?: context ?: HostContext.app()
        if (ctx == null) {
            LogX.w("DETAIL: no host context")
            return
        }
        val db = GroupDb.current() ?: run {
            LogX.w("DETAIL: db not ready")
            return
        }
        val all = db.membersOf(groupId).toMutableList()
        LogX.i("DETAIL open group=$groupId name=$groupName members=${all.size}")

        val pad = UiKit.dp(ctx, 14)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        // ---- 顶栏 ----
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, UiKit.dp(ctx, 10), pad, UiKit.dp(ctx, 10))
        }
        val close = TextView(ctx).apply {
            text = "✕"
            textSize = 17f
            setTextColor(UiKit.fgColor(ctx))
            setPadding(0, 0, UiKit.dp(ctx, 14), 0)
        }
        val title = TextView(ctx).apply {
            text = "$groupName · ${all.size} 人"
            textSize = 16f
            setTextColor(UiKit.fgColor(ctx))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val manage = TextView(ctx).apply {
            text = "管理"
            textSize = 14f
            setTextColor(UiKit.brandGreen())
            setPadding(UiKit.dp(ctx, 12), 0, 0, 0)
        }
        bar.addView(close)
        bar.addView(title)
        bar.addView(manage)
        root.addView(bar)

        // ---- 搜索 ----
        val search = EditText(ctx).apply {
            hint = "搜索成员"
            textSize = 14f
            setTextColor(UiKit.fgColor(ctx))
            setHintTextColor(UiKit.muted())
            setPadding(pad, UiKit.dp(ctx, 8), pad, UiKit.dp(ctx, 8))
            background = UiKit.pill(UiKit.softBg(), radiusPx = UiKit.dp(ctx, 8).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = pad
                rightMargin = pad
                bottomMargin = UiKit.dp(ctx, 8)
            }
        }
        root.addView(search)

        // ---- 成员列表 ----
        val adapter = MemberAdapter(ctx, all)
        val list = ListView(ctx).apply {
            this.adapter = adapter
            dividerHeight = UiKit.dp(ctx, 1)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            // 行内控件会抢焦点，必须让 ListView 自己接管
            isFocusable = true
        }
        root.addView(list)

        // ---- 底部 ----
        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, UiKit.dp(ctx, 8), pad, UiKit.dp(ctx, 10))
        }
        val addBtn = button(ctx, "+ 添加成员", UiKit.brandGreen()) {
            BatchPicker.show(groupId, groupName)
        }
        val sendBtn = button(ctx, "群发消息", UiKit.softBg(), darkText = true) {
            UiKit.toast(ctx, "群发还没做（M3 之后）")
        }
        bottom.addView(addBtn)
        bottom.addView(sendBtn)
        root.addView(bottom)

        val dialog = Dialog(ctx).apply {
            setContentView(root)
            setOnDismissListener { GroupBar.refreshAll() }
        }
        close.setOnClickListener { dialog.dismiss() }
        manage.setOnClickListener { MemberPage.show(groupId, groupName) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString().orEmpty())
            }
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val m = adapter.getItem(position)
            if (!ChatOpener.open(ctx, m.wxid)) {
                UiKit.toast(ctx, "打不开聊天页：${m.wxid}")
            }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val m = adapter.getItem(position)
            memberMenu(ctx, db, groupId, m, adapter)
            true
        }
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private fun memberMenu(
        ctx: Context,
        db: GroupDb,
        groupId: Long,
        member: GroupMember,
        adapter: MemberAdapter,
    ) {
        val name = member.displayName?.takeIf { it.isNotBlank() } ?: member.wxid
        AlertDialog.Builder(ctx)
            .setTitle(name)
            .setItems(arrayOf("打开聊天", "移出本组", "加入其他分组")) { _, which ->
                when (which) {
                    0 -> if (!ChatOpener.open(ctx, member.wxid)) {
                        UiKit.toast(ctx, "打不开聊天页：$name")
                    }
                    1 -> {
                        val n = db.removeMember(groupId, member.wxid)
                        LogX.i("DETAIL remove group=$groupId wxid=${member.wxid} rows=$n")
                        adapter.remove(member)
                    }
                    2 -> {
                        val friend = db.findFriend(member.wxid)
                        if (friend == null) {
                            UiKit.toast(ctx, "好友缓存里没有他，先去通讯录刷一次")
                        } else {
                            GroupPicker.show(friend)
                        }
                    }
                }
            }
            .show()
    }

    private fun button(
        ctx: Context,
        text: String,
        fill: Int,
        darkText: Boolean = false,
        onClick: () -> Unit,
    ): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        setTextColor(if (darkText) UiKit.fgColor(ctx) else Color.WHITE)
        gravity = Gravity.CENTER
        background = UiKit.pill(fill, radiusPx = UiKit.dp(ctx, 8).toFloat())
        setPadding(UiKit.dp(ctx, 10), UiKit.dp(ctx, 10), UiKit.dp(ctx, 10), UiKit.dp(ctx, 10))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = UiKit.dp(ctx, 8)
        }
        setOnClickListener { onClick() }
    }

    private class MemberAdapter(
        private val ctx: Context,
        private val source: MutableList<GroupMember>,
    ) : BaseAdapter() {

        private var shown: List<GroupMember> = source.toList()

        fun filter(keyword: String) {
            shown = if (keyword.isBlank()) {
                source.toList()
            } else {
                val k = keyword.trim()
                source.filter {
                    (it.displayName?.contains(k, true) == true) || it.wxid.contains(k, true)
                }
            }
            notifyDataSetChanged()
        }

        fun remove(member: GroupMember) {
            source.removeAll { it.wxid == member.wxid }
            shown = shown.filter { it.wxid != member.wxid }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): GroupMember = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val m = getItem(position)
            val row = (convertView as? LinearLayout) ?: newRow()
            row.findViewById<TextView>(1).apply {
                text = m.displayName?.takeIf { it.isNotBlank() } ?: m.wxid
            }
            row.findViewById<TextView>(2).apply {
                text = m.wxid
            }
            return row
        }

        private fun newRow(): LinearLayout {
            val pad = UiKit.dp(ctx, 14)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, UiKit.dp(ctx, 10), pad, UiKit.dp(ctx, 10))
            }
            val badge = TextView(ctx).apply {
                id = 1
                textSize = 15f
                setTextColor(UiKit.fgColor(ctx))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sub = TextView(ctx).apply {
                id = 2
                textSize = 11f
                setTextColor(UiKit.muted())
            }
            row.addView(badge)
            row.addView(sub)
            return row
        }
    }
}
