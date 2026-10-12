package com.lx.wxgroup.entry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.lx.wxgroup.core.HostContext
import java.io.File
import com.lx.wxgroup.data.BackupIO
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.feature.ConvFilter
import com.lx.wxgroup.feature.GroupBar
import com.lx.wxgroup.feature.GroupDetailPage
import com.lx.wxgroup.feature.GroupManager
import com.lx.wxgroup.feature.MainPage
import com.lx.wxgroup.ui.Panel
import com.lx.wxgroup.util.LogX

/**
 * 广播入口：不发广播就开管理页，模块本身没有可启动的 Activity。
 *
 * 触发方式（验证脚本用的就是这个）：
 *   adb shell am broadcast -a com.lx.wxgroup.action.OPEN_GROUPS -p com.tencent.mm
 *   adb shell am broadcast -a com.lx.wxgroup.action.OPEN_GROUPS -p com.tencent.mm --el group_id 1
 *
 * 三个坑：
 * 1. **必须动态注册**（registerReceiver），Manifest 静态注册在 Android 8+ 收不到隐式广播。
 * 2. **Android 13+ 必须用 RECEIVER_EXPORTED 注册**，否则默认不导出，
 *    adb shell（uid 2000）发过来的广播会被 AMS 直接丢掉，表现为「发了没反应」。
 *    这里写的是字面量 2 而不是 Context.RECEIVER_EXPORTED —— 那个常量 API 33 才有，
 *    在旧系统上读它会抛 NoSuchFieldError。
 * 3. **微信在后台时没有前台 Activity**，这时候弹 Dialog 会 BadTokenException。
 *    所以先把微信拉回前台，延迟 1.5s 再弹。
 */
object EntryReceiver {

    const val ACTION = "com.lx.wxgroup.action.OPEN_GROUPS"
    const val EXTRA_GROUP_ID = "group_id"

    /** M3：强制重画聊天页顶部的分组条（新建/删除分组后不想等自动刷新时用） */
    const val ACTION_BAR = "com.lx.wxgroup.action.BAR"

    /** M3：切换分组显示模式。--es mode bar|list（横条 <-> 聊天列表内分组行） */
    const val ACTION_MODE = "com.lx.wxgroup.action.MODE"
    const val EXTRA_MODE = "mode"

    /** M4-C：只看某个分组的会话（--el group_id N，0 = 显示全部） */
    const val ACTION_FILTER = "com.lx.wxgroup.action.FILTER"

    /**
     * 打开模块主界面（六行）。锚点 S3（微信「+」菜单）定位前，这是唯一的入口：
     *   adb shell am broadcast -a com.lx.wxgroup.action.PANEL -p com.tencent.mm
     * S3 打通后由菜单直接调 `Panel.open(MainPage)`，这个广播入口随之删除。
     */
    const val ACTION_PANEL = "com.lx.wxgroup.action.PANEL"

    /** 定位探针。--es op convtime  → 打印会话对象的全部字段，用于找会话时间字段（S10） */
    const val ACTION_PROBE = "com.lx.wxgroup.action.PROBE"

    /** M5：备份与恢复。--es op export|list|restore [--es file 绝对路径] [--ez replace true] */
    const val ACTION_BACKUP = "com.lx.wxgroup.action.BACKUP"
    const val EXTRA_OP = "op"
    const val EXTRA_FILE = "file"
    const val EXTRA_REPLACE = "replace"

    /** Context.RECEIVER_EXPORTED 的值，写成字面量是为了兼容低版本（见注释 2） */
    private const val FLAG_EXPORTED = 2

    private const val RELAUNCH_DELAY_MS = 1500L

    fun register(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                runCatching { handle(c, intent) }
                    .onFailure { LogX.e("ENTRY handle failed", it) }
            }
        }
        val filter = IntentFilter(ACTION)
        filter.addAction(ACTION_BAR)
        filter.addAction(ACTION_MODE)
        filter.addAction(ACTION_FILTER)
        filter.addAction(ACTION_BACKUP)
        filter.addAction(ACTION_PROBE)
        filter.addAction(ACTION_PANEL)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, FLAG_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            LogX.i("ENTRY registered $ACTION")
        }.onFailure { LogX.e("ENTRY register failed", it) }
    }

    private fun handle(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_BAR -> {
                LogX.i("ENTRY BAR refresh")
                GroupBar.refreshAll()
                return
            }
            ACTION_MODE -> {
                val mode = intent.getStringExtra(EXTRA_MODE) ?: return
                LogX.i("ENTRY MODE -> $mode")
                GroupBar.setMode(mode)
                return
            }
            ACTION_FILTER -> {
                val id = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
                LogX.i("ENTRY FILTER -> $id")
                ConvFilter.setGroup(id)
                return
            }
            ACTION_BACKUP -> {
                val op = intent.getStringExtra(EXTRA_OP) ?: return
                backup(context, op, intent)
                return
            }
            ACTION_PROBE -> {
                val op = intent.getStringExtra(EXTRA_OP) ?: return
                when (op) {
                    "convtime" -> {
                        for (line in com.lx.wxgroup.probe.ConvTimeProbe.dump()) {
                            LogX.i("PROBE $line")
                        }
                    }
                    // K9 验证期临时入口：设备里没有 sqlite3，改配置只能从模块内部写 Kv。
                    // 验证完随本分支一起删除。
                    "kvset" -> {
                        val key = intent.getStringExtra("key") ?: return
                        val v = intent.getStringExtra("val") ?: ""
                        com.lx.wxgroup.data.Kv.putString(key, v)
                        LogX.i("PROBE kvset $key=$v -> now=${com.lx.wxgroup.data.Kv.getString(key)}")
                    }
                    // A15 全量 hook 点取证（临时的，结论固化后随探针一起删除）：
                    // --es sub on|off|flush|tree|win|clear [--es sec 30]
                    "menuhunt" -> {
                        val sub = intent.getStringExtra("sub") ?: "on"
                        val sec = intent.getIntExtra("sec", 30)
                        com.lx.wxgroup.probe.MenuHuntProbe.command(sub, sec)
                    }
                    "kvget" -> {
                        for (k in listOf(
                            com.lx.wxgroup.data.Keys.CHAT_DND_ENABLED,
                            com.lx.wxgroup.data.Keys.CHAT_DND_START,
                            com.lx.wxgroup.data.Keys.CHAT_DND_END,
                            com.lx.wxgroup.data.Keys.CHAT_MUTE_AT_ENABLED,
                            com.lx.wxgroup.data.Keys.CHAT_AT_ALIASES,
                        )) LogX.i("PROBE kv $k = ${com.lx.wxgroup.data.Kv.getString(k)}")
                    }
                    // A13 探针 convtop / A7 探针 marktest 已用完，随 ConvTopProbe 一起删除：
                    // 结论已固化在 ConvLayout 与 MarkRead 的注释里。
                    // 滚动探针 listgeom/dragarm（ListGeomProbe + DragProbe）也已用完删除，
                    // 结论固化为 ScrollDock 与 ConvFilter「为什么不再需要尾部填充行」的注释。
                    else -> LogX.w("ENTRY PROBE unknown op=$op")
                }
                return
            }
            ACTION_PANEL -> {
                LogX.i("ENTRY PANEL open")
                withForeground(context) { Panel.open(MainPage) }
                return
            }
            ACTION -> Unit
            else -> return
        }
        val groupId = intent.getLongExtra(EXTRA_GROUP_ID, -1L)
        LogX.i("ENTRY received group=$groupId")
        withForeground(context) { open(groupId) }
    }

    /**
     * 微信在后台时没有前台 Activity，这时候弹 Dialog 会 BadTokenException，
     * 所以先把微信拉回前台，等 1.5s 再执行。
     */
    private fun withForeground(context: Context, block: () -> Unit) {
        if (HostContext.activity() != null) {
            block()
            return
        }
        LogX.i("ENTRY no foreground activity, bringing WeChat to front")
        runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        }.onFailure { LogX.w("ENTRY relaunch failed: ${it.javaClass.simpleName}") }
        Handler(Looper.getMainLooper()).postDelayed({ block() }, RELAUNCH_DELAY_MS)
    }

    /**
     * M5 备份恢复的命令行入口。
     *
     * 验证脚本靠它跑（UI 上点导出拿不到绝对路径），以后要做定时自动备份也得走这里。
     */
    private fun backup(context: Context, op: String, intent: Intent) {
        val db = GroupDb.current()
        if (db == null) {
            LogX.w("ENTRY BACKUP db not ready")
            return
        }
        when (op) {
            "export" -> {
                val ctx = HostContext.app() ?: context
                val r = BackupIO.export(ctx, db)
                if (r == null) {
                    LogX.w("ENTRY BACKUP export failed: ${BackupIO.lastError}")
                } else {
                    LogX.i(
                        "ENTRY BACKUP export ok path=${r.file.absolutePath}" +
                            " groups=${r.groups} members=${r.members} settings=${r.settings}"
                    )
                }
            }
            "list" -> {
                val ctx = HostContext.app() ?: context
                val all = BackupIO.scan(ctx)
                LogX.i("ENTRY BACKUP list count=${all.size}")
                for (e in all) {
                    LogX.i(
                        "ENTRY BACKUP item ${e.file.absolutePath}" +
                            " groups=${e.snap.groupCount} members=${e.snap.memberCount}" +
                            " at=${e.snap.exportedAt}"
                    )
                }
            }
            "restore" -> {
                val path = intent.getStringExtra(EXTRA_FILE)
                if (path.isNullOrBlank()) {
                    LogX.w("ENTRY BACKUP restore needs --es file")
                    return
                }
                val res = BackupIO.readDetailed(File(path))
                val snap = res.snapshot
                if (snap == null) {
                    LogX.w("ENTRY BACKUP unreadable: $path reason=${res.error}")
                    return
                }
                val replace = intent.getBooleanExtra(EXTRA_REPLACE, false)
                val st = BackupIO.restore(db, snap, replace)
                LogX.i(
                    "ENTRY BACKUP restore ok path=$path replace=$replace schema=${snap.schema}" +
                        " groupsAdded=${st.groupsAdded} membersAdded=${st.membersAdded}" +
                        " membersSkipped=${st.membersSkipped}" +
                        " settingsApplied=${st.settingsApplied}"
                )
                GroupBar.refreshAll()
            }
            /**
             * 自检：导出 → 读回 → 比对 → 再合并导入一次。
             *
             * 一条 adb 就能验证整条备份链路（含配置段与幂等性），
             * 不用在 UI 上点来点去再 adb pull 文件看内容。
             */
            "selftest" -> {
                val ctx = HostContext.app() ?: context
                val r = BackupIO.export(ctx, db)
                if (r == null) {
                    LogX.w("ENTRY BACKUP selftest export failed: ${BackupIO.lastError}")
                    return
                }
                val res = BackupIO.readDetailed(r.file)
                val snap = res.snapshot
                if (snap == null) {
                    LogX.w("ENTRY BACKUP selftest read back failed: ${res.error}")
                    return
                }
                val consistent = snap.groupCount == r.groups &&
                    snap.memberCount == r.members &&
                    snap.settings.size == r.settings
                // 自己导出的文件再合并导回来，一条都不该新增 —— 幂等
                val st = BackupIO.restore(db, snap, false)
                val idempotent = st.groupsAdded == 0 && st.membersAdded == 0
                LogX.i(
                    "ENTRY BACKUP selftest ${if (consistent && idempotent) "PASS" else "FAIL"}" +
                        " file=${r.file.absolutePath} schema=${snap.schema}" +
                        " groups=${r.groups}/${snap.groupCount}" +
                        " members=${r.members}/${snap.memberCount}" +
                        " kv=${r.settings}/${snap.settings.size}" +
                        " reimport_added=${st.groupsAdded}/${st.membersAdded}" +
                        " membersSkipped=${st.membersSkipped}" +
                        " settingsApplied=${st.settingsApplied}"
                )
            }
            else -> LogX.w("ENTRY BACKUP unknown op=$op")
        }
    }

    private fun open(groupId: Long) {
        if (groupId <= 0) {
            GroupManager.show()
            return
        }
        val db = GroupDb.current()
        val name = db?.listGroups()?.firstOrNull { it.id == groupId }?.name ?: "分组#$groupId"
        // M3：带 group_id 打开的是独立分组页（成员列表），不再是 M2-C 的成员管理页
        GroupDetailPage.open(HostContext.activity(), groupId, name)
    }
}
