package com.lx.wxgroup.feature

import android.view.View
import android.widget.TextView
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers

/**
 * R30 §6.1「被删动态在发布时间后面追加灰色小字 `[删除已拦截]`」的 UI 落点。
 *
 * ## 为什么独立于 [SnsTag]
 * [SnsTag] 原来的呈现是往 itemView 末尾 `addView` 一个绿色 `[已删除]` TextView；用户
 * 2026-10-27 拍板：同一条动态**不能同时出现两个标记**，统一改成「拼在时间文案后面」。
 * 于是这里复用 [SnsTag] 已经跑通的 hook 链路（`onBindViewHolder → itemView + snsId`），
 * 但呈现动作自己写 —— 只改时间 TextView 的 text，不再 addView。
 *
 * ## 时间控件怎么拿（docs/_r30_lane1_time_label.md 取证）
 * - item 根视图 `ImproveTimelineItemBuild`（lh3/l1）持有字段 `o:Landroid/widget/TextView`，
 *   公开 getter `getPublishTimeText()`；实际实例可能是其子类 `ImproveTimelineItemMeasure`
 *   （lh3/x1），getter/字段都在父类上，反射照样拿得到。
 * - ⚠️ `ImproveTimelineItemMeasure.d()`（realTimeUpdate）会**周期性**把纯原文案写回这个
 *   TextView（`0x001e` 与 `0x0059` 两处 setText）。所以单次追加会被覆盖 —— 这里不自己
 *   挂 `d()`（要在 `d()` 里反查这是哪个 item，风险高），而是靠 [SnsTag.sweep] 每 2s 重判
 *   兜底（方案 a）。
 *
 * ## ⛔ 不许 hook `oh3/p.J0` / `ns.a`
 * 取证证明 `oh3/p.toString()` 复用了 `J0`（getTimeString），hook 它会污染 [SnsTag] 的
 * snsID 解析（整个打标功能会坏）。这里只做纯 UI 层追加，不碰模型/格式化层。
 */
object SnsDeletedTag {

    /** 取不到时间控件时的告警计数（不去重，用于诊断「字段/方法名漂移」） */
    @Volatile private var missedTime = 0

    /** 供 [SnsTag.stat] 读的「没打上标」信号：>0 说明反射取不到时间控件（字段/方法名漂移）。 */
    internal fun missedTimeCount(): Int = missedTime

    /**
     * 把 [itemView] 的时间文案恢复到「该有」的状态：在 [tagged] 里就追加标记，
     * 不在就把标记剥掉、还原成纯原文。
     *
     * @param itemView [SnsTag] 解析出的 item 根视图。
     * @param snsId    这条动态的 id；`0` = 未知/无效，恒不标（与
     *                 [SnsDeletedTagLogic.isTagged] 同口径），只做还原。
     * @param tagged   已删动态 id 并集清单。
     *
     * 颜色/字号一律不动：时间控件是代码 new 出来的 `ImproveTextView`，改样式风险高；
     * 追加的文字自动继承当前 TextView 的灰色小字样式，无需也不应单独设色。
     */
    fun apply(itemView: View, snsId: Long, tagged: Set<Long>) {
        val tv = timeTextOf(itemView)
        if (tv == null) {
            val n = ++missedTime
            // 限流：这是主线程热路径，LogX 同步写盘，只能留前几条
            if (n <= 5) {
                LogX.w("SNS_DELTAG: no time TextView snsId=$snsId host=${itemView.javaClass.name}")
            }
            return
        }
        val cur = tv.text?.toString() ?: ""
        val base = strip(cur)
        val want = if (SnsDeletedTagLogic.isTagged(snsId, tagged)) {
            SnsDeletedTagLogic.appendTag(base)
        } else {
            base
        }
        // 幂等：text 一致就不 setText，避免无谓重绘（也顺带挡掉「setText 又触发重入」）
        if (want != cur) {
            tv.text = want
        }
    }

    /**
     * item 根视图 → 时间 TextView。
     *
     * 优先公开 getter `getPublishTimeText()`（方法名未混淆，比混淆字段名 `o` 稳）；
     * 拿不到再退回读字段 `o`。两者都可能随版本漂移，所以都留。
     */
    private fun timeTextOf(itemView: View): TextView? {
        return (runCatching {
            XposedHelpers.callMethod(itemView, "getPublishTimeText") as? TextView
        }.getOrNull())
            ?: (runCatching {
                XposedHelpers.getObjectField(itemView, "o") as? TextView
            }.getOrNull())
    }
}

/**
 * 剥掉末尾可能残留的 ` [删除已拦截]`，是 [SnsDeletedTagLogic.appendTag] 的**逆操作**。
 *
 * ## 为什么需要
 * 时间 TextView 是 RecyclerView **复用**的，且 `realTimeUpdate` 会周期性写回纯原文。
 * 追加前必须先剥一次，否则会叠成 `…[删除已拦截] [删除已拦截]`；被删 item 的 View
 * 复用到正常动态时也必须剥，否则标记串味。`appendTag` 是幂等的但它**不会去掉**标记，
 * 去标记只能靠这里。
 *
 * ## 为什么是纯函数 / 顶层 internal
 * 本文件的对象 [SnsDeletedTag] 挂着 `XposedHelpers` 等 Android/Xposed 依赖，JVM 单测
 * 一加载对象就崩（与 [SnsTagLogic] 同一理由）。顶层函数编译成独立的 `SnsDeletedTagKt`
 * 门面类，单测只加载它，不碰对象。
 */
internal fun strip(text: String): String {
    val mark = SnsDeletedTagLogic.MARK
    return when {
        text == mark -> ""                        // appendTag("") 的逆：整段就是标记
        text.endsWith(" $mark") -> text.substring(0, text.length - mark.length - 1)
        else -> text
    }
}
