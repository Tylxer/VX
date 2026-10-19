package com.lx.wxgroup.feature

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import com.lx.wxgroup.core.ActivityResultRelay
import com.lx.wxgroup.data.BackupEntry
import com.lx.wxgroup.data.BackupFlow
import com.lx.wxgroup.data.BackupIO
import com.lx.wxgroup.data.BackupSafLogic
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

        // ---- 主路径：SAF（用户自己选位置与文件名）----
        // 需求 6.7 定的是「存放位置 = 系统文件选择器」。目录导出保留为兜底，
        // 因为 Android 11+ 用户在文件管理器里看不到 Android/data，兜底路径
        // 只适合 adb 调试。
        root.addView(
            Rows.primaryButton(ctx, "导出到…（选择位置）") {
                launchSafExport(ctx)
            }
        )
        root.addView(
            Rows.link(ctx, "从文件导入…", "从任意位置选一个备份 JSON，可与现有数据合并") {
                launchSafImport(ctx)
            }
        )

        root.addView(Rows.section(ctx, "兜底：导出到模块目录"))
        root.addView(
            Rows.note(ctx, "不需要授权，但 Android 11+ 的文件管理器可能看不到该目录；适合 adb 取出。")
        )
        root.addView(
            Rows.primaryButton(ctx, "导出到模块目录") {
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

    // ------------------------------------------------------------ SAF 两条路径

    /**
     * 导出：让用户自己选位置与文件名（需求 6.7 指定的主路径）。
     *
     * 为什么要挑 MIME 与 `CATEGORY_OPENABLE`：部分 ROM 的 DocumentsUI 在
     * `type` 为空时会退化成「只能选目录」，用户点完拿不到 Uri。
     */
    private fun launchSafExport(ctx: Context) {
        val db = GroupDb.current()
        if (db == null) {
            UiKit.toast(ctx, "数据库还没准备好")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(
                Intent.EXTRA_TITLE,
                BackupSafLogic.sanitizeFileName(BackupIO.suggestFileName())
            )
        }
        val ok = ActivityResultRelay.launch(BackupSafLogic.REQ_EXPORT, intent) { code, result, uri ->
            when (BackupSafLogic.decide(BackupSafLogic.REQ_EXPORT, result, uri != null)) {
                BackupSafLogic.Action.DO_EXPORT -> doSafExport(ctx, db, uri!!)
                BackupSafLogic.Action.CANCELLED ->
                    UiKit.toast(ctx, BackupSafLogic.message(BackupSafLogic.Action.CANCELLED))
                else -> {
                    UiKit.toast(ctx, BackupSafLogic.message(BackupSafLogic.Action.NO_URI))
                    LogX.w("BK saf export no uri result=$result")
                }
            }
        }
        if (!ok) UiKit.toast(ctx, "拿不到当前界面，请先切回微信再试")
    }

    /**
     * 写文件必须离开主线程。
     *
     * `onActivityResult` 回调跑在微信主线程，而 `exportToUri` 要序列化整个库
     * （几千条成员关系）再写流 —— 同步做会把微信卡出一次可感知的停顿，库大时
     * 甚至 ANR。这里甩一个一次性线程，写完再 post 回主线程弹 toast。
     */
    private fun doSafExport(ctx: Context, db: GroupDb, uri: Uri) {
        UiKit.toast(ctx, BackupSafLogic.message(BackupSafLogic.Action.DO_EXPORT))
        Thread {
            val r = runCatching { BackupIO.exportToUri(ctx, db, uri) }.getOrNull()
            val err = BackupIO.lastError
            UiKit.runOnMain {
                if (r == null) {
                    AlertDialog.Builder(ctx)
                        .setTitle("导出失败")
                        .setMessage(err ?: "未知原因")
                        .setPositiveButton("知道了", null)
                        .show()
                    LogX.w("BK saf export failed: $err")
                } else {
                    UiKit.toast(
                        ctx,
                        "已导出 ${r.groups} 组 / ${r.members} 人 / ${r.settings} 项配置"
                    )
                    LogX.i("BK saf export ok uri=$uri groups=${r.groups} members=${r.members}")
                }
            }
        }.start()
    }

    /** 导入：从任意位置选一个 JSON（需求 6.7「导入可从任意位置选」） */
    private fun launchSafImport(ctx: Context) {
        val db = GroupDb.current()
        if (db == null) {
            UiKit.toast(ctx, "数据库还没准备好")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // 不限定 MIME：微信自己的 provider 有时把 json 报成 text/plain 或
            // application/octet-stream，限定成 application/json 会让文件变灰不可选。
        }
        val ok = ActivityResultRelay.launch(BackupSafLogic.REQ_IMPORT, intent) { _, result, uri ->
            when (BackupSafLogic.decide(BackupSafLogic.REQ_IMPORT, result, uri != null)) {
                BackupSafLogic.Action.DO_IMPORT -> doSafImport(ctx, db, uri!!)
                BackupSafLogic.Action.CANCELLED ->
                    UiKit.toast(ctx, BackupSafLogic.message(BackupSafLogic.Action.CANCELLED))
                else -> {
                    UiKit.toast(ctx, BackupSafLogic.message(BackupSafLogic.Action.NO_URI))
                    LogX.w("BK saf import no uri result=$result")
                }
            }
        }
        if (!ok) UiKit.toast(ctx, "拿不到当前界面，请先切回微信再试")
    }

    private fun doSafImport(ctx: Context, db: GroupDb, uri: Uri) {
        UiKit.toast(ctx, BackupSafLogic.message(BackupSafLogic.Action.DO_IMPORT))
        Thread {
            val res = runCatching { BackupIO.readDetailedFromUri(ctx, uri) }
            val snap = res.getOrNull()?.snapshot
            UiKit.runOnMain {
                if (snap == null) {
                    val msg = res.getOrNull()?.error ?: BackupIO.lastError ?: "未知原因"
                    AlertDialog.Builder(ctx)
                        .setTitle("导入失败")
                        .setMessage(msg)
                        .setPositiveButton("知道了", null)
                        .show()
                    LogX.w("BK saf import unreadable: $msg uri=$uri")
                    return@runOnMain
                }
                // 复用与「列表里点某一份备份」完全相同的确认流程：
                // 这样合并/覆盖两种策略、dry-run 预览、安全快照全部自动继承，
                // 不会出现「从文件导入」这条路径少了二次确认的漏洞。
                //
                // ⚠️ 这里造的 File 是**假的**（content:// 不是文件路径），只为借
                //    BackupEntry 的现有类型走到 confirmRestore；下游只用到 `e.file.name`
                //    做标题显示，不会真去读它。
                //    但**不能**直接 `File(uri.toString())` —— 那 `.name` 会是
                //    `document/1234` 里的 `1234`，用户在确认框里看到一个数字，
                //    根本不知道自己选的是哪个文件。所以先过 displayNameOf 取可读名。
                val shown = BackupSafLogic.displayNameOf(uri.toString())
                LogX.i("BK saf import picked uri=$uri groups=${snap.groupCount}")
                confirmRestore(ctx, db, BackupEntry(java.io.File(shown), snap))
            }
        }.start()
    }

    private fun confirmRestore(ctx: Context, db: GroupDb, e: BackupEntry) {
        val s = e.snap
        // dry-run 预览：点按钮之前让用户看到会发生什么（只读，不写库）
        val mergePreview = runCatching { BackupFlow.previewText(BackupFlow.preview(db, s, false)) }
            .getOrElse { "（预览失败：${it.javaClass.simpleName}）" }
        AlertDialog.Builder(ctx)
            .setTitle("恢复这份备份？")
            .setMessage(
                "${e.file.name}\n" +
                    "导出于 ${BackupIO.stampHuman(s.exportedAt)}\n" +
                    "含 ${s.groupCount} 个分组 / ${s.memberCount} 条成员关系" +
                    if (s.settings.isNotEmpty()) " / ${s.settings.size} 项配置" else "" +
                    "\n\n$mergePreview"
            )
            .setNegativeButton("取消", null)
            .setNeutralButton("合并") { _, _ -> doRestore(ctx, db, e, false) }
            .setPositiveButton("覆盖") { _, _ ->
                // 覆盖会先删光现有分组，必须二次确认；确认框里给的是覆盖后的预览
                val replacePreview =
                    runCatching { BackupFlow.previewText(BackupFlow.preview(db, s, true)) }
                        .getOrElse { "（预览失败）" }
                UiKit.confirm(
                    ctx,
                    "覆盖恢复",
                    "会先删除现有全部分组，再按备份重建。确认？\n\n$replacePreview",
                ) {
                    doRestore(ctx, db, e, true)
                }
            }
            .show()
    }

    private fun doRestore(ctx: Context, db: GroupDb, e: BackupEntry, replace: Boolean) {
        // 对话框回调跑在主线程：restore 抛异常没有 runCatching 会直接打到微信宿主进程，
        // 必须自己兜住（与 EntryReceiver 对 handle 的包裹同一套路）
        val out = runCatching { BackupFlow.restore(ctx, db, e.snap, replace) }
            .getOrElse {
                LogX.w("BK restore failed: ${it.javaClass.simpleName}")
                UiKit.toast(ctx, "恢复失败：${it.javaClass.simpleName ?: "未知原因"}")
                return
            }
        UiKit.toast(ctx, out.summary())
        if (out.aborted) {
            // 安全快照没做成、恢复已中止：库没动过，不用刷新界面
            return
        }
        if (!out.reconcileOk) LogX.w("BK restore reconcile mismatch, file=${e.file.name}")
        LogX.i(
            "BK restored file=${e.file.name} replace=$replace stats=${out.stats} " +
                "reconcile=${out.reconcileOk} safety=${out.safetyNet?.absolutePath}"
        )
        // 分组变了，聊天页顶部的分组行要跟着重画
        GroupBar.refreshAll()
        Panel.refresh()
    }
}
