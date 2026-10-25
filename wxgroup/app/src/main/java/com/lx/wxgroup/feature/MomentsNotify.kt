package com.lx.wxgroup.feature

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX

/**
 * 朋友圈自动导出失败的「弹窗告知」接线：拿上下文 → 主线程弹 [UiKit.confirm]。
 *
 * 为什么单独一个文件：弹窗 + 上下文获取是**有 Android 依赖**的接线，跟
 * [MomentsNotifyLogic] 的纯判据（失败分类 / 弹不弹 / 去重 latch）分开，判据才能跑 JVM 单测。
 *
 * ## 后台线程弹 Dialog 的三个分支（决策 #11 已接受「后台周期任务可能拉起前台」的风险）
 * 1. **有 Activity**：`HostContext.activity()` 非空（用户正在用微信）→ **过主线程**弹
 *    （调用方在导出线程上，不能碰 UI）。
 * 2. **无 Activity**：按 `EntryReceiver.withForeground` 的路子拉起微信（NEW_TASK），
 *    `postDelayed(RELAUNCH_DELAY_MS)` 后**再次校验** activity；仍为 null 则放弃 + 记日志。
 * 3. 拉起本身失败 / 再次校验失败：只记日志，**不无限重试**。
 */
object MomentsNotify {

    private const val TAG = "MOMENT"

    private const val RELAUNCH_DELAY_MS = 1500L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 弹一次失败提示。
     *
     * ## 返回值 = **是否受理**（不是「用户看到没有」）
     * - 有 Activity：转主线程弹（结果经 [onOutcome] 异步回传），返回 `true`。
     * - 无 Activity：拉起 + 1500ms 后重校验，**当场返回 `true`**（已受理，异步交付），
     *   1500ms 后的成败同样经 [onOutcome] 回传。
     * - 拿不到 app context / 拉起抛异常：`onOutcome(false)` + 返回 `false`（没受理）。
     *
     * ## 交付结果为什么必须回传（修 F2）
     * 调用方只在 `onOutcome(true)`（**真的显示出去了**）时才 `Latch.markFired`。
     * 旧实现里 `show()` 的 `runCatching` 会把 `BadTokenException`（`isFinishing` 竞态 /
     * token 失效）连同 F1 的线程异常一起吞掉，而 `notify()` 无条件返回 `true`
     * ⇒ **latch 被白占，用户没看到窗却再也不弹**。
     * 异步分支更明显：`postDelayed` 后立即 `return true`，1.5s 后拿不到 Activity 只打一行日志，
     * 没有任何回路告知调用方 ⇒ 旧注释自述的「异步放弃属环境问题，下轮重试」在当时根本不可能发生。
     *
     * ## ⛔ 两处必须过主线程（修 F1/P0）
     * 调用方 `MomentsExport.runAsync()` 起的是 **daemon、从未 `Looper.prepare()`** 的导出线程，
     * 而 `UiKit.confirm` 是裸 `AlertDialog.Builder(ctx)...show()`（`UiKit.kt:213-220`），内部没有主线程跳转
     * ⇒ 在导出线程上直接弹必抛 `Can't create handler inside thread ... that has not called Looper.prepare()`
     * （或后续 `CalledFromWrongThreadException`）。反讽的是「微信在后台」那条异步分支因为走了
     * `mainHandler` 反而能弹出来。
     * ⇒ 有 Activity 的**主路径**也必须 `UiKit.runOnMain { ... }`（同仓库正确写法见 `BackupPanelPage.kt:167`）。
     *
     * ## 后台线程弹 Dialog 的三个分支（决策 #11 已接受「后台周期任务可能拉起前台」的风险）
     * 1. **有 Activity**：`HostContext.activity()` 非空（用户正在用微信）→ 转主线程弹。
     * 2. **无 Activity**：按 `EntryReceiver.withForeground` 的路子拉起微信（NEW_TASK），
     *    `postDelayed(RELAUNCH_DELAY_MS)` 后**再次校验** activity；仍为 null 则放弃 + [onOutcome]`(false)`。
     * 3. 拉起本身失败 / 再次校验失败：记日志 + [onOutcome]`(false)`，**不无限重试**。
     *
     * ⚠️ 用户在弹窗上点「取消」仍算**已送达**（`show()` 返回 true）——只弹一次的设计意图不变。
     *
     * @param onOutcome 送达结果回调，参数 `delivered` = 弹窗真的显示出去了没有。
     *   极端情况（`UiKit.runOnMain` 的 `post` 自己失败）回调不会被调用；
     *   调用方的语义是「没收到 true 就不占 latch」⇒ 下轮重试，安全。
     */
    fun notify(
        msg: MomentsNotifyLogic.Message,
        onOutcome: (delivered: Boolean) -> Unit,
    ): Boolean {
        val act = HostContext.activity()
        if (act != null) {
            // ⛔ 必须在主线程弹：当前线程是导出线程，没有 Looper（复现见本函数文档 F1）
            UiKit.runOnMain { onOutcome(show(act, msg)) }
            return true
        }

        val ctx = HostContext.app() ?: run {
            LogX.w("$TAG export notify: no app context, skip popup")
            onOutcome(false)
            return false
        }
        LogX.i("$TAG export notify: no foreground activity, bringing WeChat to front")
        val launched = runCatching {
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(launch)
            true
        }.getOrElse {
            LogX.w("$TAG export notify relaunch failed: ${it.javaClass.simpleName}")
            false
        }
        if (!launched) {
            onOutcome(false)
            return false
        }

        // 拉起后必须再次校验：仍拿不到 Activity（用户拒绝 / 微信没起来）就放弃，
        // 不允许无限重试。放弃 = **没送达** ⇒ 回传 false，让调用方下轮再来一次。
        mainHandler.postDelayed({
            val a = HostContext.activity()
            if (a == null) {
                LogX.w("$TAG export notify: still no activity after relaunch, giving up")
                onOutcome(false)
            } else {
                onOutcome(show(a, msg))
            }
        }, RELAUNCH_DELAY_MS)
        // 已受理：已经决定要弹且在推进（拉起 + 延迟校验），不该让调用方重置 latch
        return true
    }

    // ⛔ 原「单参兼容重载」已于 R19 接线完成后删除（2026-10-25）。
    //    它不具备「未送达不占 latch」的能力（没有回调可达），留着就是一条
    //    绕过 F2 修复的后门 —— 任何人图省事调它都会让「用户没看到窗却再也不弹」复现。

    /**
     * 弹窗落地。**@return 弹窗真的显示出去了没有**（`true` = 用户眼前确实出现了对话框）。
     *
     * ⚠️ 这里是**唯一**捕异常的地方。`UiKit.runOnMain` 内部自带 `runCatching` 且不回传结果，
     * 若在外层再包一层 `runCatching`，异常被吞两次、结果永远取不到 ⇒ **别双层吞**。
     */
    private fun show(act: android.app.Activity, msg: MomentsNotifyLogic.Message): Boolean {
        if (act.isFinishing || act.isDestroyed) {
            LogX.w("$TAG export notify show skipped: activity is finishing")
            return false
        }
        return runCatching {
            UiKit.confirm(act, msg.title, msg.body) {}
            true
        }.getOrElse {
            LogX.w("$TAG export notify show failed: ${it.javaClass.simpleName}")
            false
        }
    }
}
