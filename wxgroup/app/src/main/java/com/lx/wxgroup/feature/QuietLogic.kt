package com.lx.wxgroup.feature

/**
 * 静音域的纯判据（免打扰时段 chat.dnd_* + 新加群自动免打扰）：
 * 零 Android / Xposed 依赖，JVM 单测覆盖（QuietLogicTest）。
 *
 * 「防止被@」（chat.mute_at_*）的判据在同包 [MuteAtLogic]，两边不互相依赖，
 * 只有 `@chatroom` 这个后缀是各自私有的同值常量 —— 改一处不必担心另一处。
 */
object QuietLogic {

    fun minuteOf(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.size != 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /** 起点包含、终点不包含；跨天有效；起止相等表示零时长。 */
    fun inDndWindow(nowMinute: Int, start: String, end: String): Boolean {
        if (nowMinute !in 0 until MINUTES_PER_DAY) return false
        val startMinute = minuteOf(start) ?: return false
        val endMinute = minuteOf(end) ?: return false
        if (startMinute == endMinute) return false
        return if (startMinute < endMinute) {
            nowMinute in startMinute until endMinute
        } else {
            nowMinute >= startMinute || nowMinute < endMinute
        }
    }

    /** 群聊 talker 一律 `@chatroom` 结尾（8.0.48 实证，见 ConvReader / docs/取证结论.md）。 */
    fun isGroupTalker(talker: String?): Boolean =
        !talker.isNullOrBlank() && talker.endsWith(CHATROOM_SUFFIX)

    /**
     * 新加群自动免打扰的群聊分类结果。
     *
     * - NOT_GROUP 不是群聊（不管）
     * - HANDLED   台账里已有这个群（设过免打扰，不重复设）
     * - EXISTING  存量群：启动快照里就有，或快照还没就绪（宁漏设也不错设老群）
     * - NEW       快照之后才出现的群聊 = 新群 → 设免打扰
     */
    enum class GroupDndVerdict { NOT_GROUP, NEW, EXISTING, HANDLED }

    /**
     * 新群判定（纯函数，见 [GroupDndVerdict]）。
     *
     * @param baseline      启动时枚举会话列表得到的存量群快照
     * @param handled       已记账（设过免打扰 / 手动登记过）的群
     * @param baselineReady 快照是否就绪；false 时一律按存量处理
     */
    fun classifyGroup(
        talker: String?,
        baseline: Set<String>,
        handled: Set<String>,
        baselineReady: Boolean,
    ): GroupDndVerdict {
        if (!isGroupTalker(talker)) return GroupDndVerdict.NOT_GROUP
        if (talker in handled) return GroupDndVerdict.HANDLED
        if (!baselineReady || talker in baseline) return GroupDndVerdict.EXISTING
        return GroupDndVerdict.NEW
    }

    private const val MINUTES_PER_DAY = 24 * 60
    private const val CHATROOM_SUFFIX = "@chatroom"
}
