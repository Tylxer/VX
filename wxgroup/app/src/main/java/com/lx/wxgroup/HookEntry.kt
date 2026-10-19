package com.lx.wxgroup

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.core.ActivityResultRelay
import com.lx.wxgroup.core.ContactSync
import com.lx.wxgroup.core.DexKitLocator
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.core.TextSender
import com.lx.wxgroup.core.NotifyMute
import com.lx.wxgroup.core.VersionGate
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.entry.EntryReceiver
import com.lx.wxgroup.feature.ConvFilter
import com.lx.wxgroup.feature.AntiRevoke
import com.lx.wxgroup.feature.AntiRevokeHint
import com.lx.wxgroup.feature.ConvMenuInject
import com.lx.wxgroup.feature.ForceForward
import com.lx.wxgroup.feature.GroupBar
import com.lx.wxgroup.feature.ScrollDock
import com.lx.wxgroup.feature.SnsGuard
import com.lx.wxgroup.feature.SnsTag
import com.lx.wxgroup.feature.SnsCommentGuard
import com.lx.wxgroup.feature.SnsCommentTag
import com.lx.wxgroup.feature.MomentsAuto
import com.lx.wxgroup.feature.MomentsExport
import com.lx.wxgroup.feature.PatBlock
import com.lx.wxgroup.feature.MuteAt
import com.lx.wxgroup.feature.DndWindow
import com.lx.wxgroup.feature.AutoDndGroup
import com.lx.wxgroup.feature.RedPacket
import com.lx.wxgroup.hook.PlusMenu
import com.lx.wxgroup.probe.ChatListProbe
import com.lx.wxgroup.probe.ChatOpenProbe
import com.lx.wxgroup.probe.DexProbe
import com.lx.wxgroup.probe.LabelHuntProbe
import com.lx.wxgroup.probe.MenuHuntProbe
import com.lx.wxgroup.probe.MenuProbe
import com.lx.wxgroup.probe.MuteSetterProbe
import com.lx.wxgroup.probe.RevokeHuntProbe
import com.lx.wxgroup.probe.SendProbe
import com.lx.wxgroup.probe.SnsHuntProbe
import com.lx.wxgroup.probe.UiProbe
import com.lx.wxgroup.util.LogX

/**
 * 模块唯一入口，assets/xposed_init 里声明的就是这个类。
 *
 * M0 目标：装上后不崩、能把宿主 APK 布局和版本信息打进日志。
 * 在 M1 拿到第一批锚点之前，这里不打任何业务钩子。
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != VersionGate.HOST_PACKAGE) return
        if (!lpparam.isFirstApplication) return

        val processName = lpparam.processName

        if (!VersionGate.isMainProcess(processName)) {
            LogX.d("skip process=$processName (not main)")
            return
        }

        LogX.i("package loaded, process=$processName")

        // hook android.app.Application.attach：它比任何 Application.onCreate 都早，
        // 且一定能拿到宿主的 Context —— 后续所有资源都依赖这个 Context
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val context = param.thisObject as Context
                            bootstrap(context, lpparam)
                        }.onFailure { LogX.e("bootstrap failed", it) }
                    }
                }
            )
        }.onFailure { LogX.e("hook Application.attach failed", it) }
    }

    private fun bootstrap(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkgInfo = hostPackageInfo(context)
            ?: run { LogX.w("cannot read host package info, abort"); return }

        val version = VersionGate.Version(
            versionName = pkgInfo.versionName ?: "unknown",
            versionCode = versionCodeOf(pkgInfo),
        )
        val signature = VersionGate.hostSignatureSha256(context)

        LogX.i("host version=${version.versionName} code=${version.versionCode}")
        LogX.i("host signature=$signature")

        when (val verdict = VersionGate.check(version, signature)) {
            is VersionGate.Verdict.Ok -> LogX.i("gate ok: ${verdict.target.channel.label}")
            is VersionGate.Verdict.Reject -> {
                LogX.w("DISABLED: ${verdict.reason}")
                return
            }
        }

        // 取证探针：只留 classLoader，不打任何钩子（平时零开销）。
        // 需要取证时发广播 op=menuhunt / op=revoke sub=on 才临时装钩，off 会全部 unhook。
        MenuHuntProbe.prepare(lpparam)
        RevokeHuntProbe.prepare(lpparam)
        SnsHuntProbe.prepare(lpparam)
        LabelHuntProbe.prepare(lpparam)
        MuteSetterProbe.prepare(lpparam)
        SendProbe.prepare(lpparam)
        TextSender.prepare(lpparam)

        ContactSync.init(context)
        HostContext.setApp(context)

        // ---- 6.7 备份：SAF 结果中继 ----
        // 模块 APK 没有自己的 Activity，SAF 必须借宿主 Activity 发起、再截
        // onActivityResult。挂的是 android.app.Activity 的框架方法（不受混淆影响）。
        runCatching { ActivityResultRelay.install(lpparam.classLoader) }
            .onFailure { LogX.w("ActivityResultRelay install failed: ${it.javaClass.simpleName}") }

        val db = GroupDb.open(context)
        LogX.i("db ready, groups=${db.countGroups()} friends=${db.countFriends()}")

        // M0 只打印宿主 APK 布局，顺带自检 APP 的 split apk 是否齐全
        val apkPaths = DexKitLocator(lpparam).apkPaths()
        apkPaths.forEachIndexed { index, path -> LogX.i("host apk[$index]=$path") }

        // ---- 定位阶段的探针已全部摘除 ----
        // UiProbe / MenuProbe / ChatListProbe 当初用来定位 Fragment / Adapter / 字段，
        // 锚点结论已经写进各自的业务类注释里，探针本身对成品零贡献；
        // 而且它们 hook 了 Fragment.performResume / onHiddenChanged / setUserVisibleHint
        // 这些**tab 切换热路径**（ChatListProbe 每次还要枚举上千个 Activity 类），
        // 会明显拖慢宿主页面切换 —— 一律不再安装。

        // ---- M3：会话列表顶部注入「我的分组」条 ----
        runCatching {
            GroupBar.install(lpparam)
        }.onFailure { LogX.e("GroupBar install failed", it) }

        // ---- A15：长按会话 → 往**微信原生菜单**里加「加入分组 / 标记已读」----
        // hook `View.createContextMenu`：微信自己的菜单就是标准 AOSP ContextMenu，
        // 在它 add 完自己的项之后我们再 add 两项即可（详见 ConvMenuInject 的类注释）。
        // 长按事件本身完全不干预，微信的删除/置顶/免打扰全部保留。
        runCatching {
            // 诊断开关只在加载时读一次（改完要重启微信），关着时不装诊断钩、不 dump
            ConvMenuInject.diag = Kv.getBool(Keys.CHAT_MENU_DIAG)
            ConvMenuInject.install(lpparam)
        }.onFailure { LogX.e("ConvMenuInject install failed", it) }

        // ---- 6.6 强制转发：聊天页长按消息菜单追加「转发到多个（模块）」----
        // 注入点与 ConvMenuInject 同一套（PopupWindow show after），但必须先过
        // ForwardLogic.classifyMenu 确认是 CHAT_MSG 才注入（挂错到会话列表是最贵误判）。
        // 需求 6.6：不加开关、默认常开；发送链路（原生转发锚点 / K8 降级发送）未取证前
        // 两条分支恒记 failed 并落日志，严禁猜微信内部 API。详见 feature/ForceForward.kt。
        runCatching {
            ForceForward.install(lpparam)
        }.onFailure { LogX.e("ForceForward install failed", it) }

        // ---- M4-C 探针：会话列表 adapter 结构 + 点击监听（定位已完成，不再安装）----
        // （结论：内层是 com.tencent.mm.ui.conversation.p3，点击监听 o2 靠位置取会话）

        // ---- M4-C：按分组过滤会话列表 ----
        runCatching {
            ConvFilter.install(lpparam)
        }.onFailure { LogX.e("ConvFilter install failed", it) }

        // ---- M4-C 探针：会话页打开时打出 Chat_User（验证「点第 N 行开第 N 行」）----
        runCatching {
            ChatOpenProbe.install(lpparam)
        }.onFailure { LogX.e("ChatOpenProbe install failed", it) }

        // ---- S3：主界面右上角「+」菜单底部加一行「分组模块」 ----
        runCatching {
            PlusMenu.install(lpparam)
        }.onFailure { LogX.e("PlusMenu install failed", it) }

        // ---- K7 补：会话列表顶部停靠位守卫（下拉不再把内容推下去推不回来）----
        runCatching {
            ScrollDock.install(lpparam)
        }.onFailure { LogX.e("ScrollDock install failed", it) }

        // ---- 防撤回（6.2）：拦住「把原消息改写成撤回提示」的那次写库 ----
        // 锚点是真机取证抓到的：updateWithOnConflict("message", type=0x10000000|10000,
        // content="…撤回了一条消息")，详见 feature/AntiRevoke.kt 的类注释。
        runCatching {
            AntiRevoke.install(lpparam)
        }.onFailure { LogX.e("AntiRevoke install failed", it) }

        // ---- 防撤回·隐藏撤回提示（6.2 最后一项）：读取侧把他人撤回提示过滤掉 ----
        // 与上面是两件事：上面让撤回不发生，这里让**已经躺在库里的**提示不显示
        // （防撤回关着时、以及装模块之前的历史提示）。落点是 rawQueryWithFactory。
        runCatching {
            AntiRevokeHint.install(lpparam)
        }.onFailure { LogX.e("AntiRevokeHint install failed", it) }

        // ---- 防撤回·朋友圈动态防删除（6.2 第二项）：拦住 DELETE FROM SnsInfo 并归档整行 ----
        // ⚠️ 朋友圈是**独立的库** SnsMicroMsg.db，不是 EnMicroMsg.db。
        // 删动态是物理 DELETE 整行（不是改标记），所以策略是「拦 + 归档」而不是「拦 + 改写」。
        runCatching {
            SnsGuard.install(lpparam)
        }.onFailure { LogX.e("SnsGuard install failed", it) }

        // ---- 防撤回·朋友圈动态「[已删除]」标签（6.2 第三项）----
        // 落点：新版时间线 ImproveSnsTimelineUI → WxRecyclerAdapter.onBindViewHolder（**类名未混淆**）
        // + adapter.getData() 的 ImproveInfo(name=…, snsID='无符号十进制')。
        // ⚠️ 老版那条 View.setTag → TimeLineObject.Id 链在新版**不存在**（四轮真机取证结论）。
        // 只往「被我们救下来的动态」上打标，清单来自 sns_archive。
        runCatching {
            SnsTag.install(lpparam)
        }.onFailure { LogX.e("SnsTag install failed", it) }

        // ---- K9：通知静音过滤器（防止被@ + 免打扰时段 + 微信来电放行）----
        // ⚠️ 2026-10-17 停用：静音域已统一到 QuietNotificationBridge（MuteAt /
        // DndWindow / AutoDndGroup 共用一条通知改写通道），判据在 MuteAtLogic /
        // QuietLogic（有 JVM 单测覆盖）。K9 与新链路挂**同三个锚点**且判据分叉
        // （K9 的 isNameChar 把 CJK 当名字字符 → `@老王在吗` 判不出；`@all` 无边界
        // 检查 → `@allow 请查看` 被误判为 @all 静音），双轨并存会让真机结果不可归因。
        // 来电放行新链路也有（DndWindow.isIncomingCall + callsPassed），不会丢功能。
        //
        // 类与其取证注释全部保留（VOIP 通知特征等结论在 core/NotifyMute.kt），
        // 要回退就把下面两行注回来。
        // runCatching {
        //     NotifyMute.install(lpparam)
        // }.onFailure { LogX.e("NotifyMute install failed", it) }

        // ---- 6.1 聊天：关闭拍一拍 ----
        runCatching {
            PatBlock.install(lpparam)
        }.onFailure { LogX.e("PatBlock install failed", it) }

        // ---- 6.1 聊天：被 @ 静音（复用 NotifyMute 的静音通道，排除名单优先）----
        runCatching {
            MuteAt.install(lpparam)
        }.onFailure { LogX.e("MuteAt install failed", it) }

        // ---- 6.1 聊天：免打扰时段（除微信电话外掐断提示音与震动，支持跨天）----
        runCatching {
            DndWindow.install(lpparam)
        }.onFailure { LogX.e("DndWindow install failed", it) }

        // ---- 6.1 聊天：新加群自动免打扰（除自己创建的群）----
        runCatching {
            AutoDndGroup.install(lpparam)
        }.onFailure { LogX.e("AutoDndGroup install failed", it) }

        // ---- 6.3 红包：领取界面弹出时自动点「拆红包」（默认关）----
        runCatching {
            RedPacket.install(lpparam)
        }.onFailure { LogX.e("RedPacket install failed", it) }

        // ---- 6.2 防撤回：朋友圈**评论**防删除 + 评论 [已删除] 标签 ----
        // ⚠️ 与动态不同：删评论前微信可能已重写 SnsInfo.attrBuf（评论可见列表的载体），
        // 所以除了拦 delete SnsComment 还要做 attrBuf 差量检测与还原。细节见
        // feature/SnsCommentGuard.kt 类注释；attrBuf 是否被重写**尚未实证**，靠 probe 取证。
        // ⛔ 2026-10-20 用户决定：评论域（评论防删除 + [已删除]标签）功能已停止，不再安装 hook。
        // 实现完整保留在 feature/SnsCommentGuard.kt / feature/SnsCommentTag.kt / feature/SnsCommentLogic.kt（冻结，不删）。
        // 恢复时：取消下面这段注释即可（连同 SnsCommentTag.install 一起）。
        // runCatching {
        //     SnsCommentGuard.install(lpparam)
        // }.onFailure { LogX.e("SnsCommentGuard install failed", it) }

        // ---- 6.2 防撤回：朋友圈评论「[已删除]」标签 ----
        // ⛔ 2026-10-20 同上：评论域功能已停止，不再安装 hook。
        // runCatching {
        //     SnsCommentTag.install(lpparam)
        // }.onFailure { LogX.e("SnsCommentTag install failed", it) }

        // ---- 6.4 朋友圈：定时刷新 + 自动加载（默认关；刷新要充电/WiFi 任一满足）----
        // 刷新 = 到点时把时间线 Activity 显式拉起来（类名运行时捕获，不猜包名）；
        // 自动加载 = 用户滚到底时替他把列表再甩一下（RecyclerView.fling 公有 API）。
        runCatching {
            MomentsAuto.install(lpparam)
        }.onFailure { LogX.e("MomentsAuto install failed", it) }

        // ---- 6.4 朋友圈：自动导出（sns_archive → 文本文件；图片规则未取证，只留探针）----
        // 没有任何 hook：后台 5 分钟一拍，导出间隔 10 分钟，单航班后台线程。
        runCatching {
            MomentsExport.install(lpparam)
        }.onFailure { LogX.e("MomentsExport install failed", it) }

        // ---- M2-C 入口：广播开分组管理页（模块没有可启动的 Activity）----
        runCatching {
            EntryReceiver.register(context)
        }.onFailure { LogX.e("EntryReceiver register failed", it) }

        // DexProbe 依赖 dexkit 的 libdexkit.so，本机一直加载不到（日志：
        // "libdexkit.so unavailable"），留着只是每次启动白跑一趟；锚点早已确定，摘除。
        LogX.i("boot completed. hooks: GroupBar/ConvFilter/PlusMenu")
    }

    private fun hostPackageInfo(context: Context): PackageInfo? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(VersionGate.HOST_PACKAGE, 0)
    }.onFailure { LogX.e("getPackageInfo failed", it) }.getOrNull()

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
}
