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

    fun setApp(context: Context) {
        appRef = WeakReference(context.applicationContext)
    }

    /** UiProbe 在 Activity.onResume 里调，永远只保留最近一个 */
    fun setActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun app(): Context? = appRef?.get()

    /** 优先用 Activity（Dialog 需要它），拿不到才退回 applicationContext */
    fun activity(): Activity? = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
}
