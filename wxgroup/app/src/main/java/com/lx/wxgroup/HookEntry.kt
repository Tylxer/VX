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

        // ---- M1 探针：定位通讯录 Fragment / Adapter / 联系人字段 ----
        // UiProbe 靠纯反射，不依赖 DexKit，是当前的主力；
        // DexProbe 用 DexKit 在 dex 层搜候选，失败也不影响 UiProbe。
        runCatching {
            UiProbe.install(lpparam)
        }.onFailure { LogX.e("UiProbe install failed", it) }

        // ---- M2-B 探针：通讯录条目长按链路 ----
        runCatching {
            MenuProbe.install(lpparam)
        }.onFailure { LogX.e("MenuProbe install failed", it) }

        // ---- M3：会话列表顶部注入「我的分组」条 ----
        runCatching {
            GroupBar.install(lpparam)
        }.onFailure { LogX.e("GroupBar install failed", it) }

        // ---- M3 探针：会话列表控件形态 + 单聊 Activity 类名 ----
        runCatching {
            ChatListProbe.install(lpparam)
        }.onFailure { LogX.e("ChatListProbe install failed", it) }

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

        // ---- M2-C 入口：广播开分组管理页（模块没有可启动的 Activity）----
        runCatching {
            EntryReceiver.register(context)
        }.onFailure { LogX.e("EntryReceiver register failed", it) }

        val hostApk = apkPaths.firstOrNull() ?: pkgInfo.applicationInfo?.sourceDir.orEmpty()
        if (hostApk.isEmpty()) {
            LogX.w("no host apk path, DexProbe skipped")
        } else {
            runCatching { DexProbe.runAsync(context, hostApk) }
                .onFailure { LogX.e("DexProbe start failed", it) }
        }

        LogX.i("M0 boot completed. M1 probe armed - now open WeChat Contacts tab.")
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
