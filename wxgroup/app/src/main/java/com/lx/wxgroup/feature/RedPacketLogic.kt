package com.lx.wxgroup.feature

/**
 * 「自动点击拆红包」的**纯判据**（零 Android 依赖 → 可直接跑 JVM 单测）。
 *
 * 需求边界：**不是**自动点开红包消息，而是用户手动点开红包、领取界面弹出时，
 * 自动点「拆红包 / 领取 / 开」按钮，省掉最后那一次点击。
 *
 * ## 为什么单独抽出来
 * 这些判据要靠真机取证（真发一个 0.01 的红包）才能定下来，改错一次就要重上一轮真机。
 * 抽出来之后改判据先跑单测，真机只做最后一公里验证。
 *
 * ## 总原则：误点比不点更糟
 * 点错按钮（点到「关闭」「再发一个」「查看领取详情」）比什么都不点糟糕得多 ——
 * 前者会让用户莫名其妙地丢掉一个红包 / 跳到发红包页。所以这里的每一条判据都是
 * **白名单优先 + 宁可漏点**：
 *  - 文案判定走**全等匹配**（[OPEN_TEXTS]），不做模糊包含；
 *  - 长度超过 [MAX_BTN_LEN] 的一律不算按钮（那是句子，不是按钮）；
 *  - 页面上下文不确定时（[shouldClick]）不点。
 *
 * ⚠️ 本文件**禁止 import 任何 android.* / xposed.* **，否则单测加载不了。
 */
object RedPacketLogic {

    /** 判定结果 */
    enum class Kind { OPEN, DENY, IGNORE }

    /**
     * **待取证**：按钮文案白名单（全等匹配，[classify] 专用）。
     *
     * ⚠️ 真机取证前先别信这张表。8.0.48 领取界面上的真实文案要靠
     * `probe(sub=tree)` 打出来的 View 树确认，确认后再往这里加；
     * 拿不准的文案**宁可不加**（漏点只是没效果，误点是事故）。
     *
     * 收录依据：微信红包领取界面历史上出现过的按钮文案（简繁两种写法都在）。
     */
    private val OPEN_TEXTS: Set<String> = setOf(
        "开", "開",                 // 封面页那个大圆按钮（微信历史上用繁体「開」）
        "拆红包", "拆紅包",
        "领取", "領取",
        "打开红包", "打開紅包",
    )

    /**
     * 黑名单（**子串**匹配，[classify] 在白名单全等未命中后才看这里）。
     *
     * 这些都是领取界面上**一定不能点**的东西，宁可让黑名单长一点：
     *  - 「关闭 / 关闭红包」—— 点了红包界面直接没了
     *  - 「查看领取详情 / 领取详情」—— 跳详情页
     *  - 「再发一个 / 继续发红包」—— 跳到**发**红包页（要花钱）
     *  - 「零钱 / 提现 / 充值 / 明细 / 存入零钱」—— 跳支付相关页
     */
    private val DENY_TOKENS: List<String> = listOf(
        "关闭", "關閉", "关閉", "关闭红包", "關閉紅包",
        "查看", "领取详情", "領取詳情", "详情", "詳情",
        "再发", "再發", "发红包", "發紅包", "继续", "繼續",
        "退回", "退款", "零钱", "零錢", "提现", "提現", "充值",
        "明细", "明細", "存入", "到账", "到賬", "支付", "微信支付",
        "留言", "表情", "取消", "知道了", "完成", "返回", "分享",
        "记录", "記錄", "帮助", "幫助", "客服", "投诉", "投訴",
        "已领", "已領", "开通", "開啟", "开启", "展开", "展開",
    )

    /** 按钮文案长度上限：超过这个长度的一定是句子，不是按钮 */
    private const val MAX_BTN_LEN = 8

    /** 页面上下文关键词（命中任一 = 这屏确实是红包相关界面）*/
    private val PAGE_TOKENS: List<String> = listOf(
        "红包", "紅包",
        "恭喜发财", "恭喜發財",
        "拼手气", "拼手氣",
        "领取详情", "領取詳情",
        "已存入零钱", "已存入零錢", "存入零钱", "存入零錢",
    )

    /**
     * 兜底：**极简封面页**。红包封面往往只有「发红包人 / 祝福语 / 开」三五个文本，
     * 一个「红包」字样都未必有。整屏文本极少时放宽上下文要求。
     * ⚠️ 只在**已经命中拆红包按钮**的前提下才会走到这里（见 [shouldClick]）。
     */
    private const val SPARSE_PAGE_MAX_TEXTS = 8

    /**
     * 应当被抹掉的「空白」。**不含** `\s` 默认覆盖不到的那些：
     * JVM 的 `\s` 只有 ASCII 空白，真机多语言排版里的全角空格 U+3000、
     * 不换行空格 U+00A0、零宽空格 U+200B 都不在其中（`"开　".trim()` 也不会动它们），
     * 只靠 `\s` 会漏掉带这些空格的按钮文案。
     */
    private val WS = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200B\\u202F\\u205F\\u3000\\uFEFF]+")

    /**
     * 归一化：去空白 + 去首尾标点。
     * 真机上按钮文案可能带空格（多语言排版）或全角标点，直接全等会漏。
     */
    fun normalize(raw: String?): String {
        if (raw == null) return ""
        val s = raw.replace(WS, "")
        var a = 0
        var b = s.length
        while (a < b && isPunct(s[a])) a++
        while (b > a && isPunct(s[b - 1])) b--
        return if (a == 0 && b == s.length) s else s.substring(a, b)
    }

    private fun isPunct(ch: Char): Boolean = ch in "!！。.,，、;；:：?？~～'\"“”‘’「」『』()（）[]【】"

    /**
     * 文案判定：**白名单全等命中、且未命中黑名单子串**才算 [Kind.OPEN]；
     * 黑名单子串命中判 [Kind.DENY]；其余 [Kind.IGNORE]。
     *
     * 判据顺序是刻意的：
     *  1. 先过长度闸（超长的句子连黑名单都不必看）；
     *  2. 再算黑名单子串 `denied`，**黑名单优先于白名单**——真出现某个文案既全等命中
     *     [OPEN_TEXTS]、又含任一 [DENY_TOKENS] 子串的极端情况（配置被改坏 / 微信换了文案），
     *     必须判 [Kind.DENY]（不点）而不是 [Kind.OPEN]（点）。总原则「误点比不点更糟」
     *     在这一条上不打折扣；
     *  3. 只有「命中白名单 **且** 未被黑名单否决」才判 [Kind.OPEN]。
     *
     * 单字的「开」不会被「开通 / 开启」这类黑名单词误伤（`"开".contains("开通")` 为 false），
     * 所以黑名单优先不会把白名单里的「开」吃掉。
     */
    fun classify(raw: String?): Kind {
        val s = normalize(raw)
        if (s.isEmpty() || s.length > MAX_BTN_LEN) return Kind.IGNORE
        val denied = DENY_TOKENS.any { s.contains(it) }
        if (s in OPEN_TEXTS && !denied) return Kind.OPEN
        return if (denied) Kind.DENY else Kind.IGNORE
    }

    /** 是不是「拆红包」按钮文案 */
    fun isOpenText(raw: String?): Boolean = classify(raw) == Kind.OPEN

    /**
     * 节点判定：这个 View 长得像不像一个能点的按钮。
     *
     * @param clickable `View.isClickable()`。⚠️ **不要求**为 true —— 微信的按钮常常是
     *   「不可点的 TextView + 可点的父容器」，真正要点的目标由调用方上溯父级决定。
     * @param minPx 最小边长（px）。默认 48：封面那个「开」是大圆按钮（≈100dp），
     *   而页面上边角的小字不可能小于这个值还刚好叫「开」。保守起见宁可漏。
     */
    fun nodeOk(
        clickable: Boolean,
        enabled: Boolean,
        visible: Boolean,
        w: Int,
        h: Int,
        minPx: Int = 48,
    ): Boolean {
        if (!enabled || !visible) return false
        if (w < minPx || h < minPx) return false
        // clickable 只用于打日志/排序，不作为通过条件；但隐藏的 View 一律不点
        return true
    }

    /**
     * 最终决策：这屏到底点不点。
     *
     * 两道闸，缺一不可：
     *  ① 命中了拆红包按钮文案（[button] 非 null 且 [isOpenText]）；
     *  ② 页面上下文确实像红包界面（[looksLikeRedPacketPage]）—— 防止在某个毫不相干的
     *     界面上碰巧有个叫「开」的按钮就被点了。
     */
    fun shouldClick(pageTexts: List<String>, button: String?): Boolean {
        if (button == null || !isOpenText(button)) return false
        return looksLikeRedPacketPage(pageTexts)
    }

    /**
     * 页面上下文判定。命中任一关键词 = 红包界面；整屏文本极简时也放行（见 [SPARSE_PAGE_MAX_TEXTS]）。
     *
     * ⚠️ **空列表一律判 false**：`texts` 为空只可能是调用方没采到文本（真机上红包封面至少
     * 有「发红包人 / 祝福语 / 按钮」三五个文本），此时的「什么都没看到」是**上下文未知**而非
     * 「极简封面」。原实现让 `texts.size == 0 <= 8` 命中极简分支返回 true —— 那正相反，
     * 会把「未知」当成「确认」，与「误点比不点更糟」冲突。
     */
    fun looksLikeRedPacketPage(texts: List<String>): Boolean {
        if (texts.isEmpty()) return false
        if (texts.any { t -> PAGE_TOKENS.any { t.contains(it) } }) return true
        return texts.size <= SPARSE_PAGE_MAX_TEXTS
    }

    /**
     * 类名判定：这个 Activity 是不是红包相关界面。
     * ⚠️ **待取证**：只做「类名里带关键词」这种稳的判定，绝不写死混淆类名。
     * 微信红包是 `luckymoney` 插件，类名里带这个段基本就是它。
     */
    fun isRedPacketClass(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val n = name.lowercase()
        // ⚠️ 只认意义明确的整段词，**不认** "hb" 这种两字母片段 ——
        // 混淆类名里两字母段遍地都是，匹配上去会命中一堆无关界面。
        return n.contains("luckymoney") || n.contains("lucky_money") ||
            n.contains("redpacket") || n.contains("red_packet") ||
            n.contains("hongbao")
    }

    /** 给 probe 用：当前认哪些文案（取证时对着真机 View 树核对）*/
    fun openTexts(): Set<String> = OPEN_TEXTS
}
