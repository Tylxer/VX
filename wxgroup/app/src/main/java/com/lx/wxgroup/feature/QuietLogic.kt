package com.lx.wxgroup.feature

/** 不依赖 Android/Xposed 的静音判定，便于纯 JVM 回归。 */
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

    fun aliases(raw: String): List<String> = raw
        .split(Regex("[,，、\\s]+"))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

    /** @alias 后不能紧跟字母、数字、下划线或短横线，避免 @V 命中 @Verity。 */
    fun containsAtAlias(text: String, alias: String): Boolean {
        val clean = alias.trim()
        if (text.isEmpty() || clean.isEmpty()) return false
        val token = "@$clean"
        var index = text.indexOf(token)
        while (index >= 0) {
            val after = index + token.length
            if (after == text.length || !isNameChar(text[after])) return true
            index = text.indexOf(token, index + 1)
        }
        return false
    }

    fun isAtMe(text: String, aliases: Collection<String>): Boolean {
        if (containsAtAlias(text, "所有人") || containsAtAlias(text, "all")) return true
        return aliases.any { containsAtAlias(text, it) }
    }

    /** 排除名单优先；允许名单为空表示所有群。非群会话和未知会话均不生效。 */
    fun groupInScope(
        talker: String?,
        allow: Collection<String>,
        exclude: Collection<String>,
    ): Boolean {
        if (talker.isNullOrBlank() || !talker.endsWith(CHATROOM_SUFFIX)) return false
        if (talker in exclude) return false
        return allow.isEmpty() || talker in allow
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

    private fun isNameChar(char: Char): Boolean =
        char.isLetterOrDigit() || char == '_' || char == '-'

    private const val MINUTES_PER_DAY = 24 * 60
    private const val CHATROOM_SUFFIX = "@chatroom"
}
