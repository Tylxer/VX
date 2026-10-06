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
import com.lx.wxgroup.data.Group
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX

/**
 * M2-C 分组管理页：新建 / 重命名 / 删除 / 查看成员。
 *
 * 打开方式（两条，互为备份）：
 * 1. 长按通讯录好友 → 分组选择框里的「管理分组」按钮（真实用户路径）
 * 2. `adb shell am broadcast -a com.lx.wxgroup.action.OPEN_GROUPS -p com.tencent.mm`
 *    （EntryReceiver，验证与调试用，也是以后做通知栏入口的基础）
 *
 * 仍然全程纯代码构造 View + 宿主 Activity 上下文，原因同 GroupPicker。
 */
object GroupManager {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun show() {
        mainHandler.post {
            runCatching { showInternal() }
                .onFailure { LogX.e("GroupManager failed", it) }
        }
    }

    private fun showInternal() {
        val ctx = HostContext.activity() ?: HostContext.app()
        if (ctx == null) {
            LogX.w("GroupManager: no host context")
            return
        }
        val db = GroupDb.current() ?: run {
            LogX.w("GroupManager: db not ready")
            return
        }
        val groups = db.listGroups().toMutableList()
        LogX.i("MGR open groups=${groups.size}")

        val adapter = GroupAdapter(ctx, groups, db)
        val list = UiKit.listView(ctx, 0.42f).apply { this.adapter = adapter }

        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(headerRow(ctx))
        root.addView(list)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("我的分组")
            .setView(root)
            .setNegativeButton("关闭", null)
            .setNeutralButton("清空成员", null)
            .setPositiveButton("新建分组", null)
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            val g = adapter.getItem(position)
            dialog.dismiss()
            MemberPage.show(g.id, g.name)
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            actions(ctx, db, adapter.getItem(position)) { adapter.reload() }
            true
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                UiKit.input(ctx, "新建分组", "", "分组名称") { name ->
                    val id = db.createGroup(name)
                    if (id > 0) {
                        adapter.reload()
                        UiKit.toast(ctx, "已创建：$name")
                        LogX.i("MGR create id=$id name=$name")
                    } else {
                        UiKit.toast(ctx, "创建失败（可能是重名）")
                        LogX.w("MGR create failed name=$name")
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                // 「清空成员」清空的是当前所有分组的成员关系，分组本身保留。
                // 这是个破坏性操作，必须二次确认。
                UiKit.confirm(ctx, "清空成员", "将移除所有分组下的好友（分组保留），确认？") {
                    var n = 0
                    for (g in db.listGroups()) n += db.clearMembers(g.id)
                    adapter.reload()
                    UiKit.toast(ctx, "已移除 $n 条成员关系")
                    LogX.i("MGR clear all members=$n")
                }
            }
        }
        dialog.show()
        UiKit.widen(dialog, ctx)
    }

    /** 顶部一行：左边是操作提示，右边是 M5 备份与恢复的入口 */
    private fun headerRow(ctx: Context): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(UiKit.label(ctx, "点击管理成员 · 长按重命名/删除", 12f, gray = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(ctx).apply {
            text = "备份与恢复"
            textSize = 13f
            setTextColor(UiKit.brandGreen())
            val h = UiKit.dp(ctx, 10)
            setPadding(UiKit.dp(ctx, 12), h, UiKit.dp(ctx, 12), h)
            setOnClickListener { BackupPage.show() }
        })
        return row
    }

    /** 长按某一行：重命名 / 删除 / 添加成员 */
    private fun actions(ctx: Context, db: GroupDb, group: Group, onChanged: () -> Unit) {
        val items = arrayOf("添加成员", "重命名", "删除分组")
        AlertDialog.Builder(ctx)
            .setTitle(group.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> BatchPicker.show(group.id, group.name)
                    1 -> UiKit.input(ctx, "重命名", group.name, "新名称") { name ->
                        if (db.renameGroup(group.id, name)) {
                            onChanged()
                            UiKit.toast(ctx, "已重命名")
                            LogX.i("MGR rename id=${group.id} -> $name")
                        } else {
                            UiKit.toast(ctx, "重命名失败（可能是重名）")
                        }
                    }
                    2 -> UiKit.confirm(
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

    private class GroupAdapter(
        private val ctx: Context,
        private val items: MutableList<Group>,
        private val db: GroupDb,
    ) : BaseAdapter() {

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Group = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? GroupRow) ?: GroupRow(ctx)
            row.bind(getItem(position), db.countMembers(getItem(position).id))
            return row
        }

        /** 数据变了就地重建内容，但保留同一个 adapter 实例，ListView 的滚动位置不会丢 */
        fun reload() {
            val fresh = db.listGroups()
            items.clear()
            items.addAll(fresh)
            notifyDataSetChanged()
            LogX.d("MGR reload groups=${items.size}")
        }
    }

    private class GroupRow(ctx: Context) : LinearLayout(ctx) {
        private val name = TextView(ctx).apply { textSize = 16f }
        private val sub = TextView(ctx).apply {
            textSize = 11f
            setTextColor(0xFF888888.toInt())
        }

        init {
            orientation = VERTICAL
            val p = UiKit.dp(ctx, 16)
            setPadding(p, UiKit.dp(ctx, 10), p, UiKit.dp(ctx, 10))
            addView(name)
            addView(sub)
        }

        fun bind(group: Group, count: Int) {
            name.text = group.name
            sub.text = "$count 人 · #${group.id}"
        }
    }
}
