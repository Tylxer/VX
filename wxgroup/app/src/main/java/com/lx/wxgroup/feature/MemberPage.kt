package com.lx.wxgroup.feature

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.GroupMember
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX

/**
 * 分组成员页：看某个组里有哪些人、把人移出去、跳到批量勾选页加人。
 *
 * 显示名直接读 group_member.display_name（加人时快照下来的），
 * 不回 friend_cache 关联 —— 万一好友缓存还没同步也有东西可看。
 */
object MemberPage {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun show(groupId: Long, groupName: String) {
        mainHandler.post {
            runCatching { showInternal(groupId, groupName) }
                .onFailure { LogX.e("MemberPage failed", it) }
        }
    }

    private fun showInternal(groupId: Long, groupName: String) {
        val ctx = HostContext.activity() ?: HostContext.app()
        if (ctx == null) {
            LogX.w("MemberPage: no host context")
            return
        }
        val db = GroupDb.current() ?: run {
            LogX.w("MemberPage: db not ready")
            return
        }

        val members = db.membersOf(groupId).toMutableList()
        LogX.i("MEM open group=$groupId(${db.countMembers(groupId)}) name=$groupName")

        val adapter = MemberAdapter(ctx, members, db, groupId)
        // ⚠️ 同 PickerPage：apply 块内 this.adapter 赋值不可靠，显式落到局部变量再设
        val list = UiKit.listView(ctx, 0.45f)
        list.adapter = adapter

        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(UiKit.label(ctx, "点击某一行 = 把该好友移出本组", 12f, gray = true))
        root.addView(list)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("$groupName（${members.size} 人）")
            .setView(root)
            .setNegativeButton("关闭", null)
            .setNeutralButton("清空本组", null)
            .setPositiveButton("批量添加", null)
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            val m = adapter.getItem(position)
            db.removeMember(groupId, m.wxid)
            adapter.reload()
            dialog.setTitle("$groupName（${db.countMembers(groupId)} 人）")
            UiKit.toast(ctx, "已移出：${m.displayName ?: m.wxid}")
            LogX.i("MEM remove group=$groupId wxid=${m.wxid}")
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                BatchPicker.show(groupId, groupName)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                UiKit.confirm(ctx, "清空本组", "移除「$groupName」下的全部成员？") {
                    val n = db.clearMembers(groupId)
                    adapter.reload()
                    dialog.setTitle("$groupName（${db.countMembers(groupId)} 人）")
                    UiKit.toast(ctx, "已移除 $n 人")
                    LogX.i("MEM clear group=$groupId n=$n")
                }
            }
        }
        dialog.show()
        UiKit.widen(dialog, ctx)
    }

    private class MemberAdapter(
        private val ctx: Context,
        private val items: MutableList<GroupMember>,
        private val db: GroupDb,
        private val groupId: Long,
    ) : BaseAdapter() {

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): GroupMember = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? MemberRow) ?: MemberRow(ctx)
            row.bind(getItem(position))
            return row
        }

        fun reload() {
            val fresh = db.membersOf(groupId)
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName ?: it.wxid })
            items.clear()
            items.addAll(fresh)
            notifyDataSetChanged()
        }
    }

    private class MemberRow(ctx: Context) : LinearLayout(ctx) {
        private val name = TextView(ctx).apply { textSize = 15f }
        private val sub = TextView(ctx).apply {
            textSize = 11f
            setTextColor(0xFF888888.toInt())
        }

        init {
            orientation = VERTICAL
            val p = UiKit.dp(ctx, 16)
            setPadding(p, UiKit.dp(ctx, 8), p, UiKit.dp(ctx, 8))
            addView(name)
            addView(sub)
        }

        fun bind(member: GroupMember) {
            name.text = member.displayName?.takeIf { it.isNotBlank() } ?: member.wxid
            sub.text = member.wxid
        }
    }
}
