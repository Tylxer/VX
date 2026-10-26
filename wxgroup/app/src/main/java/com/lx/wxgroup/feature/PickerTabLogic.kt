package com.lx.wxgroup.feature

/**
 * 选择器四 Tab 的**纯判据**（零 Android 依赖 → 可直接跑 JVM 单测）。
 *
 * 存在的意义：F1 修复里的两个决策点 —— 「要不要补跑群聊全表扫」和「群聊补完、
 * 页面刷新时落在哪个 Tab」—— 都是靠真机行为推出来的判据，改错一次就要重上一轮真机
 * （全表扫 ≈2.4s + 大量排查）。抽成纯函数后，改判据先跑单测，真机只做最后一公里。
 *
 * ⚠️ 本文件**禁止 import 任何 android.* / xposed.* **，否则单测加载不了。
 * （与 [SnsTagLogic] 同一约定。）
 */
object PickerTabLogic {

    /**
     * 是否需要补跑一次群聊全表扫。
     *
     * 判据与 [com.lx.wxgroup.core.ChatroomSource.shouldRun] 逐字一致，这里只做纯化：
     * - 库里群聊行数为 0（首次 / 从没补成功过）→ 必须跑；
     * - 距上次成功超过 [intervalMs] → 跑（群会新增、昵称会改）；
     * - 否则跳过。
     *
     * 边界：恰好等于 [intervalMs] 时**不跑**（用 `>` 不用 `>=`，避免临界抖动反复扫）。
     */
    fun needsFullSync(cachedCount: Int, lastSyncMs: Long, nowMs: Long, intervalMs: Long): Boolean {
        if (cachedCount == 0) return true
        return nowMs - lastSyncMs > intervalMs
    }

    /**
     * 刷新时如何合并新旧 Tab：新 Tab 名称为准（群聊只会新增、不会消失），返回刷新后的
     * 当前 Tab 下标。若新旧名称**完全一致**返回 `null`（调用方跳过刷新，避免无谓重建与日志）。
     *
     * 「合并」的语义只有两个动作：① 列表整体换成新的；② 当前 Tab 按名字保留。
     * `selected`（已勾选）是页面级字段，本函数**不涉及**也不触碰 —— 那是
     * [PickerPage] 的结构性不变量（见其类注释）。
     */
    fun planTabRefresh(
        oldNames: List<String>,
        newNames: List<String>,
        currentName: String?,
    ): Int? {
        if (oldNames == newNames) return null
        return resolveCurrentIndex(currentName, newNames)
    }

    /**
     * 当前 Tab 名失效时选哪个：按名字在新列表里找；找到返回其下标，找不到（含名字为
     * null、新列表为空）回退到**第一个**（下标 0）。
     */
    fun resolveCurrentIndex(currentName: String?, newNames: List<String>): Int {
        val i = newNames.indexOf(currentName)
        return if (i >= 0) i else 0
    }
}
