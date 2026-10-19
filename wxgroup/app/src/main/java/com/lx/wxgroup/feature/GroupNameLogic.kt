package com.lx.wxgroup.feature

/**
 * 群显示名的**纯判据**：零 Android / Xposed 依赖，字符串进、字符串/布尔出，
 * 配 JVM 单测（GroupNameLogicTest）—— 取值规则错了改完跑单测就行，不用重上真机。
 *
 * ## 取值规则（2026-10-18 真机取证，5 条群样本）
 * 群聊的 `conRemark` 全是空串，群名在 `nickname`：
 * ```
 * username=254407642@chatroom   conRemark=(空)  nickname=相亲相爱么么扎～
 * username=19501172869@chatroom conRemark=(空)  nickname=PM13片区  兼5号线
 * ```
 * 用户手动「备注」过群之后 `conRemark` 才有值，语义与好友一致（备注 > 昵称），
 * 所以 [pick] 的顺序和好友是同一条，不是特例。
 *
 * ## 为什么空白判定不能直接用 isBlank()
 * `Char.isWhitespace()` 对 `\u00A0`（NBSP）和零宽字符返回 **false**，而微信昵称里
 * 这两种字符很常见（从网页/输入法粘过来）。用 isBlank 判会把「看起来是空的」群名
 * 当成有效名字塞进匹配，导致名单里写 `GROUP` 却匹配到一个 NBSP。
 * 所以这里统一用 [isPadding]，全角空格 / NBSP / 零宽字符 / BOM 都算空。
 */
object GroupNameLogic {

    /** 群会话 id 后缀。直接复用 [MuteAtLogic.CHATROOM_SUFFIX]，避免两处各写一份字符串后漂移。 */
    const val CHATROOM_SUFFIX = MuteAtLogic.CHATROOM_SUFFIX

    /** 日志里退回显示 id 的宽松上限；超过才截尾，正常群号（9~11 位）原样显示 */
    private const val ID_MAX = 16

    /** 超长 id 截尾位数 */
    private const val ID_TAIL = 8

    /**
     * `isWhitespace()` 漏掉、但在微信群名里真实存在的「不可见」字符。
     * 零宽空格/零宽连接符不参与排版，肉眼不可见，当作空白处理才是用户预期。
     * 用 String 而非 CharArray 是为了直接复用 `indexOf(Char)`，不依赖数组的 contains 扩展。
     */
    private const val INVISIBLE =
        "\u00A0\u2007\u202F\u200B\u200C\u200D\u2060\uFEFF"

    /**
     * 群显示名：`conRemark` 非空优先，否则 `nickname`，两者都空返回 null。
     *
     * 返回值已去掉首尾空白（含 [INVISIBLE]），内部空白原样保留 —— 群名里的空格是
     * 名字的一部分（`PM13片区  兼5号线` 就是真名），压掉反而对不上用户填的配置。
     */
    fun pick(conRemark: String?, nickname: String?): String? {
        val remark = normalize(conRemark)
        if (remark.isNotEmpty()) return remark
        return normalize(nickname).takeIf { it.isNotEmpty() }
    }

    /**
     * 归一化：去掉首尾空白/不可见字符，纯空白归一为 `""`。
     *
     * 与 [MuteAtLogic] 内部对名单元素的口径**同向**（都只去首尾、不改大小写、不压内部
     * 空格），唯一放宽的是把 NBSP / 零宽 / 全角空格也算空白——那边用原生 `trim()`，
     * 不认这些字符。放宽只会让「看起来是空的元素」被正确丢掉，不会误命中。
     */
    fun normalize(name: String?): String {
        if (name == null || name.isEmpty()) return ""
        var start = 0
        var end = name.length
        while (start < end && isPadding(name[start])) start++
        while (end > start && isPadding(name[end - 1])) end--
        return name.substring(start, end)
    }

    /**
     * 这个群是否落在**允许名单**里。语义按「允许名单」定义，与
     * [MuteAtLogic.groupInScope] 的 allow 分支同口径：
     * - 候选集合为空（或全是空白元素）→ **命中**（= 不限群），不是不命中；
     * - 任一元素等于 chatroomId（精确，id 是机器生成的，不做大小写折叠）；
     * - 任一元素归一化后等于群名（**大小写不敏感**，见下方注释）。
     *
     * 群名比较做大小写折叠是因为用户是手抄配置的：库里叫 `GRS开TC交流群`，
     * 用户很可能写成 `grs开tc交流群`。中文不受影响，只放宽 ASCII。
     * ⚠️ 两点比 [MuteAtLogic.groupInScope] 宽：大小写折叠，以及把「只含 NBSP /
     * 零宽的条目」也算作空条目（那边 `trim()` 不认这些字符）。生产路径仍走
     * groupInScope，本函数服务于单测与将来的 UI 预校验，别直接替换。
     *
     * chatroomId 为空/空白时**先返回 false**（连空名单的「不限」也不套用）：
     * 此时根本不知道问的是哪个群，返回 true 会让调用方对未知会话放行。
     *
     * @param groupName 群显示名，未知传 null（此时只剩 chatroomId 匹配）
     */
    fun matches(chatroomId: String, groupName: String?, candidates: Collection<String>): Boolean {
        val room = chatroomId.trim()
        if (room.isEmpty()) return false
        val entries = candidates.mapNotNull { normalize(it).takeIf { text -> text.isNotEmpty() } }
        if (entries.isEmpty()) return true
        val name = normalize(groupName)
        for (entry in entries) {
            if (entry == room) return true
            if (name.isNotEmpty() && entry.equals(name, ignoreCase = true)) return true
        }
        return false
    }

    /**
     * 给日志 / UI 用的短名：有群名用群名，否则退回 chatroomId 的本地部分。
     *
     * ⚠️ 不能直接取 `chatroomId` 的后 8 位 —— 所有群 id 都以 `@chatroom` 结尾，
     * 后 8 位恒等于 `chatroom`，日志里会变成一排一模一样的字符串。
     * 所以先剥后缀；只有本地部分超长（>16）才截后 8 位，正常群号原样显示。
     */
    fun displayNameOf(chatroomId: String, name: String?): String {
        val n = normalize(name)
        if (n.isNotEmpty()) return n
        val room = chatroomId.trim()
        val local = room.removeSuffix(CHATROOM_SUFFIX)
        return if (local.length <= ID_MAX) local else local.takeLast(ID_TAIL)
    }

    private fun isPadding(c: Char): Boolean = c.isWhitespace() || INVISIBLE.indexOf(c) >= 0
}
