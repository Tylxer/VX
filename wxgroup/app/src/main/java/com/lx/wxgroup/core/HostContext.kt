package com.lx.wxgroup.core

import android.app.Activity
import android.content.Context
import java.lang.ref.WeakReference

/**
 * 宿主进程的 Context。
 *
 * 为什么不能直接用自己的模块 Context：hook 代码跑在微信进程（微信 uid），
 * 模块 APK 是另一个 uid，弹 Dialog 必须有宿主的 Activity 上下文（否则 BadTokenException）。
 */
object HostContext {

    @Volatile
    private var appRef: WeakReference<Context>? = null

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    /**
     * 绑定宿主 Application，并**注册 Activity 生命周期回调**记住当前前台 Activity。
     *
     * ⚠️ 光靠 UiProbe 里 hook `Activity.onResume` / `performResume` 是不够的：
     * 微信的 LauncherUI 覆写了 onResume，hook 基类方法对它根本不触发，
     * 真机上 `activity()` 恒为 null，所有弹窗都 `BadTokenException: token null
     * is not valid`（成员页 / 分组选择框 / 新加的分组行菜单都中招）。
     * `registerActivityLifecycleCallbacks` 是**框架主动回调**，跟子类覆不覆写
     * 没关系 —— 这是唯一稳的拿法。
     */
    fun setApp(context: Context) {
        val app = context.applicationContext
        appRef = WeakReference(app)
        runCatching {
            (app as? android.app.Application)?.registerActivityLifecycleCallbacks(
                object : android.app.Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(a: Activity) = setActivity(a)
                    override fun onActivityPaused(a: Activity) = Unit
                    override fun onActivityCreated(
                        a: Activity, b: android.os.Bundle?
                    ) = Unit
                    override fun onActivityStarted(a: Activity) = Unit
                    override fun onActivityStopped(a: Activity) = Unit
                    override fun onActivitySaveInstanceState(
                        a: Activity, b: android.os.Bundle
                    ) = Unit
                    override fun onActivityDestroyed(a: Activity) = Unit
                }
            )
        }.onFailure {
            // 注册失败不致命：调用方还能传 View 的 context 兜底
            com.lx.wxgroup.util.LogX.w(
                "HostContext: lifecycle callback n/a ${it.javaClass.simpleName}"
            )
        }
    }

    /** UiProbe 在 Activity.onResume 里调，永远只保留最近一个 */
    fun setActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun app(): Context? = appRef?.get()

    /** 优先用 Activity（Dialog 需要它），拿不到才退回 applicationContext */
    fun activity(): Activity? = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
}
