package com.lx.wxgroup.feature

/**
 * 「防止被@」（chat.mute_at_*）的**纯判据**：零 Android / Xposed 依赖，
 * 字符串进、布尔出，配 JVM 单测（MuteAtLogicTest）—— 判据错了改完跑 16 秒
 * 单测就行，不用重上真机（五阶段流程阶段 1）。
 *
 * hook 侧（[MuteAt]）负责把微信里的真实取值取来再传进来：
 * - 通知正文：tickerText + EXTRA_TEXT + EXTRA_BIG_TEXT 拼成的文本
 *   （微信把 @ 渲染进通知正文，形如 `verity: @V 3`，见 docs/取证结论.md「通知静音」节）；
 * - talker：NotificationItem 的 `h` 字段（形如 `1234567890@chatroom`）；
 * - 名单 / 别名：Kv.getList（JSON 数组）/ Kv.getString（裸文本）。
 *
 * ## 一、名单格式（chat.mute_at_allow / chat.mute_at_exclude）
 *
 * JSON 数组，每个元素二选一：
 * - **群 wxid**：完整会话 id，形如 `1234567890@chatroom`，与 talker 精确相等
 *   （**必须带 `@chatroom` 后缀**；不带 `@` 的元素一律按群名匹配）；
 * - **群名**：微信里显示的群名，与群显示名精确相等。
 *
 * 两种格式混写没有优先级之分，任一等价即命中；元素前后空白忽略，空元素忽略。
 *
 * ⚠️ **群名匹配已实现但未接线（待取证）**：通知链路上目前只能拿到 talker
 * （取证结论「通知静音」节：extras 里没有 chatroom id，contentIntent 反射取不到），
 * 拿不到群显示名，所以 [MuteAt] 现在传 groupName=null，群名元素暂时永远不命中。
 * 纯判据先按双格式写好测好；等拿到「talker → 群名」锚点（候选：通知 EXTRA_TITLE
 * 是否即群名，需真机确认，可用 `op=quiet sub=extras` dump 最近一条通知的 extras）
 * 后只需在 hook 侧一行接线。
 *
 * ## 二、优先级（写死，调换顺序 = 改语义）
 * 1. talker 缺失 / 空白 / 不是 `@chatroom` 结尾 → **不生效**。单聊没有
 *    「@所有人」，且误静音单聊比漏静音难受（与 Keys.CHAT_AT_ALIASES 同款决策）；
 * 2. **排除名单优先**：命中排除名单 → 不生效，哪怕同时写在允许名单里；
 * 3. 允许名单为空 → 对所有群生效；非空 → 只对名单内的群生效。
 *
 * ## 三、「@的是不是我」（chat.at_aliases）
 * - `@所有人` 恒命中（微信中文客户端的渲染）；
 * - `@all` 命中且**大小写不敏感**（`@ALL` / `@All` 都认，英文客户端渲染）；
 * - `@别名` 命中且**大小写敏感**：别名是用户填的精确身份，`@v` 不命中别名 `V`
 *   （宁可漏判，也不把 @别人 的消息静音 —— Keys.CHAT_AT_ALIASES 注释里的决策）；
 * - 别名留空 → 只剩前两条，@任何具体人都不静音；
 * - **防短名误伤**（取证结论「通知静音」节实证）：`@alias` 后不能紧跟
 *   ASCII 字母/数字/下划线/短横线，否则别名 `V` 会把 `@Verity` / `@VIP` /
 *   `@V_2` / `@V-test` 全算成 @我。**CJK 字符不算名字字符** —— 中文正文紧贴
 *   中文昵称是常态（`@老王在吗` 必须命中，`@所有人请查看` 必须命中）。
 */
object MuteAtLogic {

    /** 群会话 id 后缀。单聊（`wxid_xxx` 等）没有 @所有人，一律不生效 */
    const val CHATROOM_SUFFIX = "@chatroom"

    /**
     * 解析别名配置（TEXT 项，**裸文本不是 JSON** —— 用 Kv.getList 读会得到空列表，
     * 这是取证结论里点名的坑）。
     *
     * 分隔符：半角逗号、全角逗号、顿号、任意空白（含换行）。前后空白去掉、
     * 空段丢弃、重复项去重。别名开头的 `@` 会被剥掉：用户照着 `@V` 填是常态，
     * 不剥的话匹配 token 变成 `@@V`，功能静默失效。
     */
    fun aliases(raw: String): List<String> = raw
        .split(ALIAS_SPLIT)
        .map { it.trim().removePrefix("@").trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    /**
     * 文本里有没有 `@alias`：`@` 后紧跟完整别名，且别名结束后**不是名字字符**。
     *
     * 名字字符 = ASCII 字母/数字/下划线/短横线（CJK 不算，见类注释第三节）。
     * [ignoreCase] 只给 `@all` 这类微信固定关键字用；用户别名恒大小写敏感。
     */
    fun containsAtAlias(text: String, alias: String, ignoreCase: Boolean = false): Boolean {
        val clean = alias.trim().removePrefix("@").trim()
        if (text.isEmpty() || clean.isEmpty()) return false
        val token = "@$clean"
        var index = text.indexOf(token, ignoreCase = ignoreCase)
        while (index >= 0) {
            val after = index + token.length
            if (after == text.length || !isNameChar(text[after])) return true
            index = text.indexOf(token, index + 1, ignoreCase)
        }
        return false
    }

    /**
     * 这条消息是不是 @了我。
     *
     * [aliases] 留空时只认 `@所有人` / `@all` —— 宁可漏判也不把 @别人 的消息静音。
     */
    fun isAtMe(text: String, aliases: Collection<String>): Boolean {
        if (text.isEmpty()) return false
        if (containsAtAlias(text, "所有人")) return true
        if (containsAtAlias(text, "all", ignoreCase = true)) return true
        return aliases.any { containsAtAlias(text, it) }
    }

    /**
     * 这个群在不在「防止被@」的作用域。优先级见类注释第二节。
     *
     * [groupName] 是群显示名（拿不到就传 null）：群名元素只在它非空时参与匹配。
     */
    fun groupInScope(
        talker: String?,
        groupName: String?,
        allow: Collection<String>,
        exclude: Collection<String>,
    ): Boolean {
        val room = talker?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (!room.endsWith(CHATROOM_SUFFIX)) return false
        if (hit(room, groupName, normalize(exclude))) return false
        val allowed = normalize(allow)
        return allowed.isEmpty() || hit(room, groupName, allowed)
    }

    private fun hit(room: String, groupName: String?, entries: Set<String>): Boolean {
        if (entries.isEmpty()) return false
        if (room in entries) return true
        val name = groupName?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return name in entries
    }

    private fun normalize(entries: Collection<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (entry in entries) {
            val trimmed = entry.trim()
            if (trimmed.isNotEmpty()) out.add(trimmed)
        }
        return out
    }

    private fun isNameChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

    private val ALIAS_SPLIT = Regex("""[,，、\s]+""")
}
