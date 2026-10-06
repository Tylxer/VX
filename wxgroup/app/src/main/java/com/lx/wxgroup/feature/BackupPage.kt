package com.lx.wxgroup.feature

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.BackupEntry
import com.lx.wxgroup.data.BackupIO
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX

/**
 * M5 备份与恢复页：导出 JSON / 从备份恢复（合并或覆盖）。
 *
 * 入口在分组管理页顶部。覆盖恢复是破坏性的，必须走二次确认 ——
 * 分组是用户一条条加出来的，手滑一下全没了比没做这个功能更糟。
 *
 * 样式与 GroupManager 保持一致：纯代码构造 View + 宿主 Activity 上下文。
 */
object BackupPage {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun show() {
        mainHandler.post {
            runCatching { showInternal() }
                .onFailure { LogX.e("BackupPage failed", it) }
        }
    }

    private fun showInternal() {
        val ctx = HostContext.activity() ?: HostContext.app()
        if (ctx == null) {
            LogX.w("BackupPage: no host context")
            return
        }
        val db = GroupDb.current() ?: run {
            LogX.w("BackupPage: db not ready")
            return
        }
        val backups = BackupIO.scan(ctx)
        LogX.i("BK open backups=${backups.size}")

        val groups = db.listGroups()
        var memberTotal = 0
        for (g in groups) memberTotal += db.countMembers(g.id)

        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(UiKit.label(ctx, "当前 ${groups.size} 个分组 · $memberTotal 条成员关系", 12f, gray = true))
        if (backups.isEmpty()) {
            root.addView(UiKit.label(ctx, "（还没有备份文件，先点「导出备份」）", 12f, gray = true))
        }
        val list = UiKit.listView(ctx, 0.38f).apply { adapter = Adapter(ctx, backups) }
        root.addView(list)
        val hint = BackupIO.exportDirHint()
        root.addView(
            UiKit.label(
                ctx,
                if (hint.isBlank()) "点「导出备份」生成 JSON 文件" else "备份目录：$hint",
                11f, gray = true,
            )
        )

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("备份与恢复")
            .setView(root)
            .setNegativeButton("关闭", null)
            .setNeutralButton("导出备份", null)
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            val e = backups.getOrNull(position) ?: return@setOnItemClickListener
            restoreDialog(ctx, db, e) { dialog.dismiss(); show() }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val r = BackupIO.export(ctx, db)
                if (r == null) {
                    UiKit.toast(ctx, "导出失败，详见日志")
                } else {
                    UiKit.toast(ctx, "已导出 ${r.groups} 组 / ${r.members} 人")
                    LogX.i("BK exported ${r.file.absolutePath}")
                    // 重新打开本页，列表里立刻能看到刚生成的那份
                    dialog.dismiss()
                    show()
                }
            }
        }
        dialog.show()
        UiKit.widen(dialog, ctx)
    }

    private fun restoreDialog(ctx: Context, db: GroupDb, e: BackupEntry, onDone: () -> Unit) {
        val s = e.snap
        AlertDialog.Builder(ctx)
            .setTitle("恢复这份备份？")
            .setMessage(
                "${e.file.name}\n" +
                    "导出于 ${BackupIO.stampHuman(s.exportedAt)}\n" +
                    "含 ${s.groupCount} 个分组 / ${s.memberCount} 条成员关系"
            )
            .setNegativeButton("取消", null)
            .setNeutralButton("合并") { _, _ -> doRestore(ctx, db, e, false, onDone) }
            .setPositiveButton("覆盖") { _, _ ->
                UiKit.confirm(ctx, "覆盖恢复", "会先删除现有全部分组，再按备份重建。确认？") {
                    doRestore(ctx, db, e, true, onDone)
                }
            }
            .show()
    }

    private fun doRestore(
        ctx: Context,
        db: GroupDb,
        e: BackupEntry,
        replace: Boolean,
        onDone: () -> Unit,
    ) {
        val st = BackupIO.restore(db, e.snap, replace)
        UiKit.toast(ctx, "已恢复：新增 ${st.groupsAdded} 组 / ${st.membersAdded} 人")
        LogX.i("BK restored file=${e.file.name} replace=$replace stats=$st")
        // 分组数据变了，聊天页顶部的分组行要跟着重画
        GroupBar.refreshAll()
        onDone()
    }

    private class Adapter(
        private val ctx: Context,
        private val items: List<BackupEntry>,
    ) : BaseAdapter() {

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): BackupEntry = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? Row) ?: Row(ctx)
            row.bind(getItem(position))
            return row
        }
    }

    private class Row(ctx: Context) : LinearLayout(ctx) {
        private val name = TextView(ctx).apply {
            textSize = 14f
            setTextColor(UiKit.fgColor(ctx))
            setSingleLine(true)
        }
        private val sub = TextView(ctx).apply {
            textSize = 11f
            setTextColor(UiKit.muted())
            setSingleLine(true)
        }

        init {
            orientation = VERTICAL
            val p = UiKit.dp(ctx, 14)
            setPadding(p, UiKit.dp(ctx, 8), p, UiKit.dp(ctx, 8))
            addView(name)
            addView(sub)
        }

        fun bind(e: BackupEntry) {
            name.text = e.file.name
            sub.text =
                "${BackupIO.stampHuman(e.snap.exportedAt)} · ${e.snap.groupCount} 组 / ${e.snap.memberCount} 人"
        }
    }
}
