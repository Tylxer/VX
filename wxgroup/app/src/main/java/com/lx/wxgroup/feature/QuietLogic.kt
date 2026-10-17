package com.lx.wxgroup.feature

/**
 * 免打扰时段（chat.dnd_*）的纯判据：零 Android / Xposed 依赖，JVM 单测覆盖
 * （QuietLogicTest）。「防止被@」的判据在同包 [MuteAtLogic]。
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

    private const val MINUTES_PER_DAY = 24 * 60
}
