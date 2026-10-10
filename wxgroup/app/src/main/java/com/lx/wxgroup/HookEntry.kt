package com.lx.wxgroup

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.core.ContactSync
import com.lx.wxgroup.core.DexKitLocator
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.core.VersionGate
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.entry.EntryReceiver
import com.lx.wxgroup.feature.ConvFilter
import com.lx.wxgroup.feature.ConvMenu
import com.lx.wxgroup.feature.GroupBar
import com.lx.wxgroup.hook.PlusMenu
import com.lx.wxgroup.probe.ChatListProbe
import com.lx.wxgroup.probe.ChatOpenProbe
import com.lx.wxgroup.probe.DexProbe
import com.lx.wxgroup.probe.MenuProbe
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

        ContactSync.init(context)
        HostContext.setApp(context)
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

        // ---- M4：长按会话 → 菜单里多一项「加入分组 / 移出分组」----
        // （M4-A 的定位探针 ConvProbe 已完成使命，锚点结论写进了 ConvMenu 注释，不再安装）
        runCatching {
            ConvMenu.install(lpparam)
        }.onFailure { LogX.e("ConvMenu install failed", it) }

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

        // ---- M2-C 入口：广播开分组管理页（模块没有可启动的 Activity）----
        runCatching {
            EntryReceiver.register(context)
        }.onFailure { LogX.e("EntryReceiver register failed", it) }

        // DexProbe 依赖 dexkit 的 libdexkit.so，本机一直加载不到（日志：
        // "libdexkit.so unavailable"），留着只是每次启动白跑一趟；锚点早已确定，摘除。
        LogX.i("boot completed. hooks: GroupBar/ConvMenu/ConvFilter/PlusMenu")
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
