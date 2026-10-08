package com.lx.wxgroup.feature

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import com.lx.wxgroup.data.BackupEntry
import com.lx.wxgroup.data.BackupIO
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.ui.Panel
import com.lx.wxgroup.ui.PanelPage
import com.lx.wxgroup.ui.Rows
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX

/**
 * 备份与恢复页（主界面第五行）。
 *
 * 跟旧 `BackupPage` 的区别：原来它是自己弹一个 AlertDialog，从主界面进来后
 * 「返回」会直接把整个面板关掉；现在它是页面栈里的普通一页，返回只回到主界面。
 *
 * 备份范围 = 分组设置 + 模块配置，**不含**好友昵称缓存（可在微信进程内重建）。
 * 合并导入时已在其他分组的人不会被搬动（v6.4）。
 */
class BackupPanelPage : PanelPage {

    override val title = "备份与恢复"

    override fun build(ctx: Context): View {
        val db = GroupDb.current()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        if (db == null) {
            root.addView(Rows.note(ctx, "数据库还没准备好，请稍后再试"))
            return root
        }

        val groups = db.listGroups()
        var memberTotal = 0
        for (g in groups) memberTotal += db.countMembers(g.id)
        val cfgCount = Kv.exportAll().size

        root.addView(Rows.section(ctx, "当前数据"))
        root.addView(
            Rows.note(ctx, "${groups.size} 个分组 · $memberTotal 条成员关系 · $cfgCount 项配置")
        )
        root.addView(
            Rows.primaryButton(ctx, "导出备份") {
                val r = BackupIO.export(ctx, db)
                if (r == null) {
                    // 决策 #11：失败要让用户明确看到原因
                    AlertDialog.Builder(ctx)
                        .setTitle("导出失败")
                        .setMessage(BackupIO.lastError ?: "未知原因")
                        .setPositiveButton("知道了", null)
                        .show()
                    LogX.w("BK export failed: ${BackupIO.lastError}")
                } else {
                    UiKit.toast(ctx, "已导出 ${r.groups} 组 / ${r.members} 人 / ${r.settings} 项配置")
                    LogX.i("BK exported ${r.file.absolutePath}")
                    // 列表里要立刻出现刚生成的那份
                    Panel.refresh()
                }
            }
        )

        val backups = BackupIO.scan(ctx)
        root.addView(Rows.section(ctx, "备份文件"))
        if (backups.isEmpty()) {
            root.addView(Rows.note(ctx, "还没有备份文件，先点上面的「导出备份」"))
        } else {
            for (e in backups) {
                root.addView(
                    Rows.link(ctx, e.file.name, summarize(e)) { confirmRestore(ctx, db, e) }
                )
            }
        }

        val hint = BackupIO.exportDirHint()
        root.addView(
            Rows.note(ctx, if (hint.isBlank()) "点「导出备份」生成 JSON 文件" else "备份目录：$hint")
        )
        return root
    }

    private fun summarize(e: BackupEntry): String {
        val s = e.snap
        val cfg = if (s.settings.isEmpty()) "" else " · ${s.settings.size} 项配置"
        return "${BackupIO.stampHuman(s.exportedAt)} · ${s.groupCount} 组 / ${s.memberCount} 人$cfg"
    }

    private fun confirmRestore(ctx: Context, db: GroupDb, e: BackupEntry) {
        val s = e.snap
        AlertDialog.Builder(ctx)
            .setTitle("恢复这份备份？")
            .setMessage(
                "${e.file.name}\n" +
                    "导出于 ${BackupIO.stampHuman(s.exportedAt)}\n" +
                    "含 ${s.groupCount} 个分组 / ${s.memberCount} 条成员关系" +
                    if (s.settings.isNotEmpty()) " / ${s.settings.size} 项配置" else ""
            )
            .setNegativeButton("取消", null)
            .setNeutralButton("合并") { _, _ -> doRestore(ctx, db, e, false) }
            .setPositiveButton("覆盖") { _, _ ->
                // 覆盖会先删光现有分组，必须二次确认
                UiKit.confirm(ctx, "覆盖恢复", "会先删除现有全部分组，再按备份重建。确认？") {
                    doRestore(ctx, db, e, true)
                }
            }
            .show()
    }

    private fun doRestore(ctx: Context, db: GroupDb, e: BackupEntry, replace: Boolean) {
        val st = BackupIO.restore(db, e.snap, replace)
        val msg = buildString {
            append("已恢复：新增 ${st.groupsAdded} 组 / ${st.membersAdded} 人")
            if (st.membersSkipped > 0) append("，${st.membersSkipped} 人因已在其他分组跳过")
            if (st.settingsApplied > 0) append("，${st.settingsApplied} 项配置已覆盖")
        }
        UiKit.toast(ctx, msg)
        LogX.i("BK restored file=${e.file.name} replace=$replace stats=$st")
        // 分组变了，聊天页顶部的分组行要跟着重画
        GroupBar.refreshAll()
        Panel.refresh()
    }
}
