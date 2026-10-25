package com.lx.wxgroup.data

/**
 * 「已阻止」提示行的**内容摘要纯判据**（零 Android / 零 Xposed 依赖 → 可直接跑 JVM 单测）。
 *
 * 存在的意义：用户报障「防撤回的显示撤回提示增加撤回内容，便于识别那一条被撤回」。
 * 原来 [com.lx.wxgroup.feature.RevokeRescue] 插的提示行只有 `"昵称" 尝试撤回一条消息（已阻止）`，
 * 不含被撤回消息的内容。本类负责把 `origType` + `origContent` 折成一句可读摘要。
 *
 * 判据是靠真机取证 + 微信 message 表惯例值（type 1/3/34/43/47/48/49…）试出来的，
 * 改错一处就要重上一轮真机，所以抽成纯函数先跑单测。
 *
 * ⚠️ 本文件**禁止 import 任何 android.* / xposed.* / 本包内带 Android 依赖的类**，
 *    否则 JVM 单测加载不了（同 ContactFilterLogic / SnsTagLogic 的约束）。
 *    也刻意**不反向依赖 feature/ForwardLogic**（data 层不能依赖 feature 层），
 *    消息类型常量在本类内自持一份（重复 4 个 Int 常量，换取层级干净）。
 */
object HintTextLogic {

    /** 撤回文案公共后缀：从 revokeContent 里抠「谁」靠它 */
    const val SUFFIX_REVOKE = "撤回了一条消息"

    /** 文本摘要上限（UTF-16 码元）：超过截断 + 省略号 */
    const val MAX_TEXT = 30

    // ------------------------------------------------------------------ 消息类型（message.type 惯例值）

    const val TYPE_TEXT = 1
    const val TYPE_IMAGE = 3
    const val TYPE_VOICE = 34
    const val TYPE_CARD = 42
    const val TYPE_VIDEO = 43
    const val TYPE_EMOJI = 47
    const val TYPE_LOCATION = 48
    const val TYPE_APP = 49
    const val TYPE_SYSTEM = 10000

    // appmsg（type=49）子类型，取自 XML 里的 `<type>`
    private const val APP_VIDEO = 4
    private const val APP_URL = 5
    private const val APP_FILE = 6
    private const val APP_EMOJI = 8
    private const val APP_CHAT_HISTORY = 19

    // ------------------------------------------------------------------ 提取正则

    /** 抠 appmsg 的 `<type>`（8.0.48 见过带空格 / 前面有 XML 头两种写法） */
    private val APP_TYPE_RE = Regex("""<\s*type\s*>\s*(-?\d+)\s*<\s*/\s*type\s*>""")

    /** 抠 `<title>…</title>` 文本 */
    private val TITLE_RE = Regex(
        """<\s*title\s*>(.*?)<\s*/\s*title\s*>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** 抠 `<refermsg>…</refermsg>` 块（引用回复的标志） */
    private val REFER_RE = Regex(
        """<\s*refermsg\s*>(.*?)<\s*/\s*refermsg\s*>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** 抠 `<content>…</content>` 文本（被引用的原文） */
    private val CONTENT_RE = Regex(
        """<\s*content\s*>(.*?)<\s*/\s*content\s*>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    // ------------------------------------------------------------------ 公开入口

    /**
     * 从微信原本要写的撤回文案里抠「谁」。
     *
     * 微信写的是 `"昵称" 撤回了一条消息`，`substringBefore` 后剩 `"昵称" `，
     * trim 后得带引号的昵称。**保留引号**：与现有提示行行为一致（自己拼 `("xx")` 反而
     * 拿不到群昵称）。抠不出来（null / 空串 / 只有光秃秃的「撤回了一条消息」）返回 null。
     */
    fun whoFrom(revokeContent: String?): String? {
        if (revokeContent.isNullOrBlank()) return null
        val who = revokeContent.substringBefore(SUFFIX_REVOKE).trim()
        return who.ifEmpty { null }
    }

    /**
     * 拼出最终提示文案。
     *
     * @param who 「谁」—— 必须是 [whoFrom] 抠出来的（可能带引号），空串不合法。
     * @param summary [summary] 的结果。空串 = 无内容可展示，只给基础句。
     * @return 形如 `"张三" 尝试撤回一条消息（已阻止）：你好` 或
     *         `"张三" 尝试撤回一条消息（已阻止）`（summary 为空时）。
     */
    fun buildHint(who: String, summary: String): String {
        val base = "$who 尝试撤回一条消息（已阻止）"
        return if (summary.isBlank()) base else "$base：$summary"
    }

    /** 角标固定前缀（R21d 方案 1） */
    const val BADGE_PREFIX = "已阻止撤回"

    /** 角标无摘要时的兜底（未知 type / 系统消息） */
    const val BADGE_ONLY = "已阻止撤回"

    /**
     * 「已阻止撤回」**角标**文案（R21d 方案 1）。
     *
     * 与 [buildHint] 的区别：角标贴在原文气泡上，**「是谁撤的」是冗余信息**
     * （群聊里气泡上方已经有群昵称了），所以不拼 who，只拼内容摘要：
     *
     * | 输入 | 输出 |
     * |---|---|
     * | `(34, "wxid_x:30000:0")` | `已阻止撤回 · 语音 30秒` |
     * | `(1, "你好")` | `已阻止撤回 · 你好` |
     * | `(3, null)` | `已阻止撤回 · 图片` |
     * | `(10000, null)`（系统消息） | `已阻止撤回` |
     *
     * ## ⚠️ 必须先剥掉摘要外层的方括号
     * [summary] 是给**独立提示行**设计的，非文本消息一律输出 `[图片]` / `[语音 30秒]`
     * —— 那是为了在一整句话里（`"张三" 尝试撤回一条消息（已阻止）：[语音 30秒]`）
     * 让占位符从正文里跳出来。
     *
     * 但**角标本身就是占位符**，再套一层方括号视觉上就像「系统没加载出来」，
     * 正是用户在选格式时淘汰第 1 种的理由（原话：方括号像系统占位符）。
     * ⇒ 这里把**成对的**外层方括号剥掉：`[语音 30秒]` → `语音 30秒`。
     *
     * ⚠️ 只剥「整串被一对 `[]` 包住」的情形，不动中间出现的方括号
     * （文本内容自己就可能是 `[图片]`，用户真发了这几个字 —— 那时必须原样保留吗？
     *  不，仍然剥：视觉收益大于「用户发的正文恰好是带括号占位符」这种巧合的损失）。
     *
     * ⚠️ 摘要为空时**不能**输出 `已阻止撤回 · `（末尾悬空的分隔符看着像渲染失败），
     * 直接退化成 [BADGE_ONLY]。
     */
    fun badgeText(origType: Int, origContent: String?): String {
        val s = stripOuterBrackets(summary(origType, origContent))
        return if (s.isBlank()) BADGE_ONLY else "$BADGE_PREFIX · $s"
    }

    /** `[语音 30秒]` → `语音 30秒`；不是整串被一对 `[]` 包住就原样返回 */
    private fun stripOuterBrackets(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && t.startsWith("[") && t.endsWith("]")) {
            val inner = t.substring(1, t.length - 1).trim()
            // ⚠️ 必须判「内层不再含括号」：`[引用 [图片]]` 这种只剥一层、
            //    剥完还是 `引用 [图片]`（中间那个是真内容，不能再动）
            if (inner.isNotEmpty()) return inner
        }
        return t
    }


    /**
     * 由 `origType` + `origContent` 折一句可读摘要。
     *
     * 返回「显示串」：有实际内容就返回内容（文本/时长/文件名/被引用原文）；
     * 只有类型可判就返回 `[标签]`；什么也拿不出（空文本、系统消息）返回 `""`。
     *
     * ⚠️ 未知 type 一律 `[消息]` 占位，**绝不抛异常**。
     */
    fun summary(origType: Int, origContent: String?): String = when (origType) {
        TYPE_TEXT -> textSummary(origContent)
        TYPE_IMAGE -> "[图片]"
        TYPE_VOICE -> voiceSummary(origContent)
        TYPE_CARD -> "[名片]"
        TYPE_VIDEO -> "[视频]"
        TYPE_EMOJI -> "[表情]"
        TYPE_LOCATION -> "[位置]"
        TYPE_APP -> appSummary(origContent)
        TYPE_SYSTEM -> ""
        else -> "[消息]"
    }

    // ------------------------------------------------------------------ 文本

    private fun textSummary(content: String?): String {
        if (content.isNullOrBlank()) return ""
        // 提示行是单行：换行 / 制表符 / 连续空格全折成单个空格
        val flat = content.replace(Regex("\\s+"), " ").trim()
        return truncate(flat)
    }

    // ------------------------------------------------------------------ 语音

    /**
     * 语音 content 是索引串 `sender:duration:flag...`（段数不固定），第 2 段是**毫秒**时长。
     * 单位论证见 docs/_r21c_hint_content.md §3：量级排除法 + 微信惯例。
     */
    private fun voiceSummary(content: String?): String {
        val sec = voiceSeconds(content)
        return if (sec == null) "[语音]" else "[语音 ${sec}秒]"
    }

    /** 解析语音时长（秒）；失败返回 null（调用方降级为不带时长的 `[语音]`） */
    private fun voiceSeconds(content: String?): Long? {
        val raw = content ?: return null
        if (raw.isBlank()) return null
        val parts = raw.split(':')
        // 第 1 段 = 发送者 wxid（可空，群聊里 `:12424:0` 这种开头就是空段），第 2 段 = 时长
        if (parts.size < 2) return null
        val durMs = parts[1].toLongOrNull() ?: return null
        if (durMs <= 0) return null
        // 毫秒 → 秒，四舍五入，最低 1 秒（30478 → 30，20785 → 21，12424 → 12）
        return ((durMs + 500) / 1000).coerceAtLeast(1L)
    }

    // ------------------------------------------------------------------ 应用消息（type=49，XML）

    private fun appSummary(xml: String?): String {
        val x = xml?.trim().orEmpty()
        if (x.isEmpty()) return "[消息]"

        // 引用回复（带 <refermsg>）：摘要优先取**被引用的原文**，这才是用户要识别的那条
        val refer = REFER_RE.find(x)
        if (refer != null) {
            val quoted = cleanXml(CONTENT_RE.find(refer.groupValues[1])?.groupValues?.get(1))
            return if (quoted.isEmpty()) "[引用]" else "[引用 ${truncate(quoted)}]"
        }

        val appType = APP_TYPE_RE.find(x)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return when (appType) {
            APP_FILE -> tagged("[文件", x)
            APP_URL -> tagged("[链接", x)
            APP_VIDEO -> "[视频]"
            APP_EMOJI -> "[表情]"
            APP_CHAT_HISTORY -> "[聊天记录]"
            else -> "[消息]"
        }
    }

    /** 由 `<title>` 拼 `[文件 x]` / `[链接 x]` 这类带标题的标签；没标题就退化成裸标签 */
    private fun tagged(label: String, xml: String): String {
        val title = cleanXml(TITLE_RE.find(xml)?.groupValues?.get(1))
        return if (title.isEmpty()) "$label]" else "$label ${truncate(title)}]"
    }

    // ------------------------------------------------------------------ 工具

    /** 剥掉残留标签 + 解常见实体 + 压成单行，作为摘要正文 */
    private fun cleanXml(s: String?): String {
        if (s.isNullOrBlank()) return ""
        var t = s.replace(Regex("<[^>]+>"), "")
        t = t.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun truncate(s: String): String =
        if (s.length <= MAX_TEXT) s else s.take(MAX_TEXT) + "…"
}
