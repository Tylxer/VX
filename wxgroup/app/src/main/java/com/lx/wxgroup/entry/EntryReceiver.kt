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
import com.lx.wxgroup.data.BackupFlow
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

    /** M5：备份与恢复。--es op export|list|restore|plan|selftest [--es file 绝对路径] [--ez replace true] */
    const val ACTION_BACKUP = "com.lx.wxgroup.action.BACKUP"
    const val EXTRA_OP = "op"
    const val EXTRA_FILE = "file"
    const val EXTRA_REPLACE = "replace"

    /** Context.RECEIVER_EXPORTED 的值，写成字面量是为了兼容低版本（见注释 2） */
    private const val FLAG_EXPORTED = 2

    private const val RELAUNCH_DELAY_MS = 1500L

    /**
     * 把「要读写微信消息库」的诊断动作丢到后台线程。
     *
     * ⚠️ BroadcastReceiver 的 `onReceive` 跑在**主线程**，而 ardump / arins / archeck /
     * ardel 都要去查/写微信几十万行的 `message` 表，同步干会把微信卡出 ANR ——
     * 而且这类 ANR 只在你手动敲 adb 命令时才会出现，极容易被当成「微信自己抽风」。
     */
    private fun io(block: () -> Unit) {
        Thread {
            runCatching(block).onFailure {
                LogX.w("PROBE io failed: ${it.javaClass.simpleName}")
            }
        }.start()
    }

    /**
     * 取一个 long 型附加参数。
     *
     * ⚠️ 不能用 `getLongExtra` 配 adb 的 `--ei`：存进去的是 Integer，
     * 而 getLongExtra 遇到非 Long 会**静默返回默认值 0**（ardel 踩过：「删单条」变「删全部」）。
     */
    private fun firstLong(intent: Intent, key: String): Long =
        when (val raw = intent.extras?.get(key)) {
            is Long -> raw
            is Int -> raw.toLong()
            is String -> raw.toLongOrNull() ?: 0L
            else -> 0L
        }

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
                    // 清空落盘日志并归零计数。测试前发一条即可，
                    // 不用再 force-stop 两次重启微信（省 30 秒，也避开模块重载的不确定性）。
                    "logclear" -> {
                        val n = com.lx.wxgroup.util.LogX.reset()
                        com.lx.wxgroup.util.LogX.i("PROBE logclear 已清 $n 行")
                    }
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
                        // 改完立刻重读，不用重启微信（防撤回的开关走这条）
                        runCatching { com.lx.wxgroup.feature.AntiRevoke.refresh() }
                        runCatching { com.lx.wxgroup.feature.SnsGuard.refresh() }
                        // 「显示动态[已删除]标签」也是开关，改完要让缓存立刻失效
                        runCatching { com.lx.wxgroup.feature.SnsTag.refresh() }
                        // 6.4 朋友圈三项：定时刷新/自动加载是 TTL 缓存，热刷立刻生效；
                        // 自动导出的根目录/名单同理
                        runCatching { com.lx.wxgroup.feature.MomentsAuto.refresh() }
                        runCatching { com.lx.wxgroup.feature.MomentsExport.refresh() }
                        // 「显示撤回提示」也是反着生效的开关（关=隐藏），同样要立刻重读。
                        // ⚠️ 用 invalidate 而不是 refresh：清单可能刚增删过提示行，缓存要失效
                        runCatching { com.lx.wxgroup.feature.AntiRevokeHint.invalidate() }
                        // 「恢复默认显示」要立刻把库里那些消息改写/还原一次（内部走后台线程）。
                        // ⚠️ 走 sync 而不是直接 applyView：sync 内部有「目标状态已对齐」判断，
                        // 重复调用不会把库反复改写两遍
                        if (key == com.lx.wxgroup.data.Keys.ANTIREVOKE_RESTORE_VIEW) {
                            runCatching {
                                com.lx.wxgroup.feature.RevokeRescue.sync(v == "true")
                            }
                        }
                    }
                    // A15 全量 hook 点取证（临时的，结论固化后随探针一起删除）：
                    // --es sub on|off|flush|tree|win|clear [--es sec 30]
                    "menuhunt" -> {
                        val sub = intent.getStringExtra("sub") ?: "on"
                        val sec = intent.getIntExtra("sec", 30)
                        com.lx.wxgroup.probe.MenuHuntProbe.command(sub, sec)
                    }
                    // S5① 防撤回取证：抓「撤回」的写库与提示渲染调用栈
                    // --es sub on|off|flush|stat|clear [--ei sec 90]
                    "revoke" -> {
                        val sub = intent.getStringExtra("sub") ?: "on"
                        val sec = intent.getIntExtra("sec", 90)
                        com.lx.wxgroup.probe.RevokeHuntProbe.command(sub, sec)
                    }
                    // 6.2 朋友圈防撤回取证：哪个库 / 哪张表 / 删除时写了什么
                    // --es sub on|off|flush|stat|tables|sample|clear [--ei sec 150]
                    "sns" -> {
                        val sub = intent.getStringExtra("sub") ?: "on"
                        val sec = intent.getIntExtra("sec", 150)
                        // ⚠️ tables / sample 要遍历所有库查 sqlite_master + pragma，
                        // 必须丢后台线程，否则广播主线程 ANR（防撤回那次踩过）
                        val t = intent.getStringExtra("table")
                        io { com.lx.wxgroup.probe.SnsHuntProbe.command(sub, sec, t) }
                    }
                    // 朋友圈动态防删除（SnsGuard）
                    // ⚠️ sub 是**唯一的**子命令参数，本分支不存在路由/子命令混用的问题
                    //    （同文件 op=quiet 那个是例外，见那里关于 sub2 的说明）
                    // --es sub ls = 归档清单；stat = 状态；dump = 微信库里还有几条
                    // --es sub del --es id <snsId> = 绕过拦截真删（**连归档一起删**，清理前先 one）
                    //    ⚠️ 批量恢复回滚半途失败时日志里的 `⚠️ UNDONE snsId=...`，就先用 one 看清再来
                    // 朋友圈界面 View 结构取证（为「[已删除]标签」做准备）
                    // --es sub tree = 完整树；tagged = 只打带 snsId 的节点
                    "snsv" -> {
                        val sub = intent.getStringExtra("sub") ?: "tree"
                        val n = intent.getIntExtra("n", 240)
                        io {
                            when (sub) {
                                "tagged" -> com.lx.wxgroup.probe.SnsViewProbe.tagged(n)
                                "ids" -> com.lx.wxgroup.probe.SnsViewProbe.ids(n)
                                "recycler" -> com.lx.wxgroup.probe.SnsViewProbe.recycler(n)
                                "tagdump" -> com.lx.wxgroup.probe.SnsViewProbe.tagDump(
                                    intent.getStringExtra("cls") ?: "", n
                                )
                                else -> com.lx.wxgroup.probe.SnsViewProbe.tree(n)
                            }
                        }
                    }
                    "snsg" -> {
                        val sub = intent.getStringExtra("sub") ?: "stat"
                        val n = intent.getIntExtra("n", 20)
                        io {
                            when (sub) {
                                "stat" -> LogX.i("SNSG " + com.lx.wxgroup.feature.SnsGuard.stat())
                                "ls" -> for (l in com.lx.wxgroup.feature.SnsGuard.listArchived(n)) {
                                    LogX.i(l)
                                }
                                "dump" -> for (l in com.lx.wxgroup.feature.SnsGuard.dump(n)) {
                                    LogX.i(l)
                                }
                                "one" -> {
                                    val id = firstLong(intent, "id")
                                    for (l in com.lx.wxgroup.feature.SnsGuard.one(id)) LogX.i(l)
                                }
                                "show" -> {
                                    val id = firstLong(intent, "id")
                                    LogX.i("SNSG show " + com.lx.wxgroup.feature.SnsGuard.show(id))
                                }
                                "del" -> {
                                    val id = firstLong(intent, "id")
                                    LogX.i("SNSG del " + com.lx.wxgroup.feature.SnsGuard.forceDelete(id))
                                }
                                // 评论表取证（列名 + 最近几行）
                                "cmts" -> for (l in com.lx.wxgroup.feature.SnsGuard.comments(n)) {
                                    LogX.i(l)
                                }
                                // 归档里每条的**当前** localFlag（dump 里的 flag 是快照，别当依据）
                                "flags" -> for (l in com.lx.wxgroup.feature.SnsGuard.flags()) {
                                    LogX.i("SNSG $l")
                                }
                                // 把归档里已不可见的动态批量恢复可见（修早期版本的历史遗留）
                                "fixall" -> LogX.i(
                                    "SNSG " + com.lx.wxgroup.feature.SnsGuard.restoreAll()
                                )
                                // 从归档反插回 SnsInfo（需求 6.4「从归档恢复」）：
                                // 幂等判定 —— 行在且可见=SKIP / 行在但被清位=补位 / 行没了=整行 INSERT。
                                // ⚠️ 这是写对方数据库，一次只恢复一条，先跑 --check 看清归档
                                "restore" -> {
                                    val id = firstLong(intent, "id")
                                    LogX.i(
                                        "SNSG restore " +
                                            com.lx.wxgroup.feature.SnsGuard.restore(id)
                                    )
                                }
                                // 批量恢复：--ei n <条数> --ez dry true(默认，只看计划)|false(真写)
                                // 失败即停 + 倒序回滚；幂等，可以重跑
                                "restoreall" -> for (l in com.lx.wxgroup.feature.SnsGuard
                                    .restoreBatch(n, intent.getBooleanExtra("dry", true))
                                ) LogX.i("SNSG $l")
                                // 取证：SnsInfo 列清单 + 「没写的列里有没有 NOT NULL 无默认值」
                                // （INSERT 只给部分列，这是唯一的硬风险，只读不写）
                                "cols" -> for (l in com.lx.wxgroup.feature.SnsGuard
                                    .snsInfoColumns()
                                ) LogX.i("SNSG $l")
                                // 模拟微信真实两步删除（update 清可见位 → delete 行），
                                // 走真实的拦截路径，用来验证「拦下 + 恢复可见 + 归档 + 贴标」
                                "simdel" -> {
                                    val id = firstLong(intent, "id")
                                    LogX.i("SNSG " + com.lx.wxgroup.feature.SnsGuard.simulateDelete(id))
                                }
                                else -> LogX.i("SNSG unknown sub=$sub")
                            }
                        }
                    }
                    // 朋友圈动态「[已删除]」标签（SnsTag）
                    // --es sub stat = 状态；hit = 当前界面上打过标的 snsId 清单
                    "snstag" -> {
                        val sub = intent.getStringExtra("sub") ?: "stat"
                        when (sub) {
                            "stat" -> LogX.i("SNSTAG " + com.lx.wxgroup.feature.SnsTag.stat())
                            "dump" -> LogX.i("SNSTAG\n" + com.lx.wxgroup.feature.SnsTag.dump())
                            // 强制给当前屏幕上解析出来的动态打标（验证整条链路通不通）
                            "mark" -> LogX.i("SNSTAG " + com.lx.wxgroup.feature.SnsTag.markVisible())
                            "unmark" -> LogX.i("SNSTAG " + com.lx.wxgroup.feature.SnsTag.unmark())
                            // --es id <dump 里的无符号 id> → 精确模拟「这条被删了」
                            "fake" -> LogX.i(
                                "SNSTAG " + com.lx.wxgroup.feature.SnsTag
                                    .fakeMark(intent.getStringExtra("id").orEmpty())
                            )
                            else -> LogX.i("SNSTAG unknown sub=$sub")
                        }
                    }
                    // ---- 6.2 防撤回：朋友圈**评论**防删除 + 评论 [已删除] 标签 ----
                    // ⚠️ 这两个都要读微信的 SnsMicroMsg.db → 走后台线程（onReceive 在主线程）
                    "snsc" -> {
                        val sub = intent.getStringExtra("sub") ?: "stat"
                        val id = intent.getLongExtra("id", 0L)
                        io {
                            LogX.i("SNSCG " + com.lx.wxgroup.feature.SnsCommentGuard.probe(sub, id))
                            LogX.i("SNSC " + com.lx.wxgroup.feature.SnsCommentTag.probe(sub, id))
                        }
                    }
                    // ---- 6.1 聊天：拍一拍 / 被@静音 / 免打扰时段 / 新加群自动免打扰 ----
                    // --es sub at|pat|dnd|grp     路由到哪个功能（默认 at）
                    //                            给个其它值（如 all）→ 四项各打一遍
                    // --es sub2 <该功能的子命令>   默认 status
                    //
                    // ⚠️ sub 与「功能自己的子命令」是两个参数，不能混：
                    // 早先只用 sub 时，sub=pat 会走进回退分支、并把 "pat" 当成
                    // 子命令传给 probe()，结果四项都只打印各自用法串；后来把实参
                    // 硬编码成 "status"，又让 dbltap / extras / window 这些子命令
                    // 永远传不进去（拍一拍取证卡死在这里）。现在 sub 只管路由，
                    // 子命令走独立的 sub2（与主工作区 775adda 的修法一致）。
                    //
                    //   --es op quiet --es sub pat --es sub2 dbltap   # 打开拍一拍取证
                    //   --es op quiet --es sub dnd --es sub2 window   # 查免打扰时段
                    //   --es op quiet --es sub at  --es sub2 extras   # 取证通知 extras
                    //
                    // 这四个都是纯内存状态，主线程直接打没问题
                    "quiet" -> {
                        val sub = intent.getStringExtra("sub") ?: "at"
                        val arg = intent.getStringExtra("sub2") ?: "status"
                        when (sub.lowercase()) {
                            "pat" -> LogX.i("QUIET " + com.lx.wxgroup.feature.PatBlock.probe(arg))
                            "at" -> LogX.i("QUIET " + com.lx.wxgroup.feature.MuteAt.probe(arg))
                            "dnd" -> LogX.i("QUIET " + com.lx.wxgroup.feature.DndWindow.probe(arg))
                            "grp" -> LogX.i("QUIET " + com.lx.wxgroup.feature.AutoDndGroup.probe(arg))
                            // 未知 sub：四项都过一遍（同样用 arg 当子命令）
                            else -> {
                                LogX.i("QUIET " + com.lx.wxgroup.feature.PatBlock.probe(arg))
                                LogX.i("QUIET " + com.lx.wxgroup.feature.MuteAt.probe(arg))
                                LogX.i("QUIET " + com.lx.wxgroup.feature.DndWindow.probe(arg))
                                LogX.i("QUIET " + com.lx.wxgroup.feature.AutoDndGroup.probe(arg))
                            }
                        }
                    }
                    // ---- 6.3 红包：领取界面自动点「拆红包」----
                    "redpacket" -> {
                        val sub = intent.getStringExtra("sub") ?: "stat"
                        LogX.i("REDPK " + com.lx.wxgroup.feature.RedPacket.probe(sub))
                    }
                    // ---- 6.4 朋友圈：定时刷新 / 自动加载 / 自动导出 ----
                    // --es sub stat|env|act|refresh|load|rvmethods|export|img|scan|path [--ei n 5]
                    // ⚠️ refresh/load 会撩微信前台或甩列表，img/scan 要读库 → 都走后台线程
                    "moments" -> {
                        val sub = intent.getStringExtra("sub") ?: "stat"
                        val n = intent.getIntExtra("n", 5)
                        io {
                            when (sub.lowercase()) {
                                "stat", "env", "act", "refresh", "load", "rvmethods" ->
                                    LogX.i(com.lx.wxgroup.feature.MomentsAuto.probe(sub))
                                else ->
                                    LogX.i(com.lx.wxgroup.feature.MomentsExport.probe(sub, n))
                            }
                        }
                    }
                    // ---- 6.6 强制转发（ForceForward）----
                    // --es sub stat = 状态与计数；on|off = 运行时开关（真机反向对照用，
                    // 不写库不进设置，需求 6.6 明确不加开关）；dump = 最近一次聊天消息
                    // 菜单文案清单（认人判据取证）；pick = 跳过长按直接打开选择器自检。
                    "forward" -> {
                        val sub = intent.getStringExtra("sub") ?: "stat"
                        LogX.i(
                            "FORWARD " + com.lx.wxgroup.feature.ForceForward.probe(sub)
                        )
                    }
                    // 防撤回调试：不打搅用户也能看清 message 表的 type / isSend 语义
                    // --es sub all = 不看撤回、直接列最近的普通消息；--ei n 12 = 条数
                    //
                    // ⚠️ 下面几个 op 都要读/写**微信的消息库**，而 BroadcastReceiver
                    // 跑在主线程 —— 直接在 onReceive 里干会卡住微信（ANR）。全部丢后台线程。
                    "ardump" -> {
                        val sub = intent.getStringExtra("sub") ?: ""
                        val n = intent.getIntExtra("n", 8)
                        io {
                            for (line in com.lx.wxgroup.feature.AntiRevoke.dumpRecent(
                                n, sub != "all"
                            )) LogX.i("ARDUMP $line")
                        }
                    }
                    // 手动往微信库插一条提示行，用来验证「显示撤回提示」的插入链路通不通
                    // --es talker <wxid> [--es text "测试提示"]
                    "arins" -> {
                        val t = intent.getStringExtra("talker") ?: ""
                        val x = intent.getStringExtra("text") ?: "测试提示"
                        io {
                            val id = com.lx.wxgroup.feature.RevokeRescue.insertHint(t, x)
                            LogX.i("ARINS msgId=$id talker=${t.take(12)} text=${x.take(24)}")
                        }
                    }
                    // 删掉我们自己插进去的提示行（**安全阀**）。
                    // ⚠️ 提示行是写进微信库的，卸载模块也不会消失，必须留一个手动清理的入口。
                    // 用法：--ei id -1 删单条；不带 id 则删全部（负 msgId 全是我们自己的行）
                    "ardel" -> {
                        // ⚠️ 不能用 getLongExtra 配 `--ei id N`：adb 的 --ei 存的是 Integer，
                        // 而 getLongExtra 遇到非 Long 会**静默返回默认值 0** ——
                        // 结果「删单条」变成「删全部」，白跑一轮还删错东西。
                        // 手动解析，三种类型都认。
                        val id = when (val raw = intent.extras?.get("id")) {
                            is Long -> raw
                            is Int -> raw.toLong()
                            is String -> raw.toLongOrNull() ?: 0L
                            else -> 0L
                        }
                        io { LogX.i("ARDEL " + com.lx.wxgroup.feature.RevokeRescue.deleteHints(id)) }
                    }
                    // 「隐藏撤回提示」自检：区分「条件没命中行」和「命中了但界面有缓存」
                    // --es talker <wxid> [--ei n 58]
                    "archeck" -> {
                        val t = intent.getStringExtra("talker") ?: ""
                        val n = intent.getIntExtra("n", 58)
                        io {
                            for (line in com.lx.wxgroup.feature.AntiRevoke.checkHide(t, n)) {
                                LogX.i("ARCHK $line")
                            }
                        }
                    }
                    // 查配置当前值。默认打印常用几个 key；
                    // --es key <k> 查单个（备份导入后断言"只覆盖了文件里有的项"用）
                    "kvget" -> {
                        val one = intent.getStringExtra("key")
                        val keys = if (!one.isNullOrBlank()) {
                            listOf(one)
                        } else {
                            listOf(
                                com.lx.wxgroup.data.Keys.CHAT_DND_ENABLED,
                                com.lx.wxgroup.data.Keys.CHAT_DND_START,
                                com.lx.wxgroup.data.Keys.CHAT_DND_END,
                                com.lx.wxgroup.data.Keys.CHAT_MUTE_AT_ENABLED,
                                com.lx.wxgroup.data.Keys.CHAT_AT_ALIASES,
                            )
                        }
                        for (k in keys) {
                            val known = com.lx.wxgroup.data.Prefs.spec(k) != null
                            LogX.i("PROBE kv $k = ${com.lx.wxgroup.data.Kv.getString(k)}${if (known) "" else " (未登记)"}")
                        }
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
                val out = BackupFlow.restore(context, db, snap, replace)
                LogX.i(
                    "ENTRY BACKUP restore ok path=$path replace=$replace schema=${snap.schema}" +
                        " plan=${out.plan.summary()}" +
                        " groupsAdded=${out.stats.groupsAdded} membersAdded=${out.stats.membersAdded}" +
                        " membersSkipped=${out.stats.membersSkipped}" +
                        " settingsApplied=${out.stats.settingsApplied}" +
                        " reconcile=${out.reconcileOk}" +
                        " safety=${out.safetyNet?.absolutePath}"
                )
                GroupBar.refreshAll()
            }
            /**
             * dry-run 预览：读文件、算计划、打日志，**一个字节都不写库**。
             * 真机回路用它断言"将发生什么"，不用真恢复一遍看结果。
             *   --es op plan --es file <绝对路径> [--ez replace true]
             */
            "plan" -> {
                val path = intent.getStringExtra(EXTRA_FILE)
                if (path.isNullOrBlank()) {
                    LogX.w("ENTRY BACKUP plan needs --es file")
                    return
                }
                val res = BackupIO.readDetailed(File(path))
                val snap = res.snapshot
                if (snap == null) {
                    LogX.w("ENTRY BACKUP plan unreadable: $path reason=${res.error}")
                    return
                }
                val replace = intent.getBooleanExtra(EXTRA_REPLACE, false)
                val plan = BackupFlow.preview(db, snap, replace)
                LogX.i("ENTRY BACKUP plan path=$path ${BackupFlow.previewText(plan)}")
                LogX.i("ENTRY BACKUP plan detail ${plan.summary()}")
                for (a in plan.groups) {
                    LogX.i(
                        "ENTRY BACKUP plan group ${a.name} create=${a.create}" +
                            " pinned=${a.applyPinned} add=${a.add.size}" +
                            " skipped=${a.skipped.size} already=${a.already}"
                    )
                }
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
                // 自己导出的文件再合并导回来，一条都不该新增 —— 幂等。
                // withSafetyNet=false：刚导出的那份就是现成的退路
                val out = BackupFlow.restore(ctx, db, snap, false, withSafetyNet = false)
                val idempotent = out.stats.groupsAdded == 0 && out.stats.membersAdded == 0
                LogX.i(
                    "ENTRY BACKUP selftest ${if (consistent && idempotent && out.reconcileOk) "PASS" else "FAIL"}" +
                        " file=${r.file.absolutePath} schema=${snap.schema}" +
                        " groups=${r.groups}/${snap.groupCount}" +
                        " members=${r.members}/${snap.memberCount}" +
                        " kv=${r.settings}/${snap.settings.size}" +
                        " reimport_added=${out.stats.groupsAdded}/${out.stats.membersAdded}" +
                        " membersSkipped=${out.stats.membersSkipped}" +
                        " settingsApplied=${out.stats.settingsApplied}" +
                        " reconcile=${out.reconcileOk}"
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
