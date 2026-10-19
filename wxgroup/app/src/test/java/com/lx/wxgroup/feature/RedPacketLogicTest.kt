package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自动拆红包」按钮判据的纯判据单测（JVM，零 Android / Xposed）。
 *
 * 这一组用例守的是 [RedPacketLogic] 的总原则「**误点比不点更糟**」：
 *  - 白名单必须是**全等**，多一个字/少一个字都不能算命中；
 *  - 黑名单是**子串**，且**永远优先于白名单**（极端冲突时必须判 DENY = 不点）；
 *  - 长度/空输入/全角空白/零宽字符等归一化边界；
 *  - [RedPacketLogic.shouldClick] 的两道闸（按钮文案 + 页面上下文）缺一不可。
 */
class RedPacketLogicTest {

    private val noPage = emptyList<String>()
    private val redPacketPage = listOf("恭喜发财", "某某红包")

    // ---------------------------------------------------------- 文案：白名单（全等）

    @Test
    fun whitelistedTextsAreOpen() {
        // 简繁两种写法都要认（微信历史上都用过）
        for (t in listOf("开", "開", "拆红包", "拆紅包", "领取", "領取", "打开红包", "打開紅包")) {
            assertEquals("text=$t", RedPacketLogic.Kind.OPEN, RedPacketLogic.classify(t))
            assertTrue("text=$t", RedPacketLogic.isOpenText(t))
        }
    }

    /**
     * 白名单是**全等**不是包含：多一个字的文案一律不算「开」，避免把「拆红包记录」
     * 之类的东西当成按钮点了。
     */
    @Test
    fun whitelistIsExactNotSubstring() {
        assertEquals(RedPacketLogic.Kind.IGNORE, RedPacketLogic.classify("拆红包啦"))
        assertEquals(RedPacketLogic.Kind.IGNORE, RedPacketLogic.classify("打开红包哦"))
        // 少一个字也不算
        assertEquals(RedPacketLogic.Kind.IGNORE, RedPacketLogic.classify("拆红"))
    }

    // ---------------------------------------------------------- 归一化边界

    @Test
    fun whitespaceAndPunctuationAreNormalizedAway() {
        assertEquals("", RedPacketLogic.normalize(null))
        assertEquals("", RedPacketLogic.normalize("   "))
        assertEquals("开", RedPacketLogic.normalize("，开！"))
        assertEquals("开", RedPacketLogic.normalize("  开  "))
        // 按钮文案带标点/空白时仍应命中
        assertEquals(RedPacketLogic.Kind.OPEN, RedPacketLogic.classify("！拆红包。"))
        assertEquals(RedPacketLogic.Kind.OPEN, RedPacketLogic.classify(" 领 取 "))
    }

    /**
     * ⚠️ 回归：JVM 的 `\s` 只覆盖 ASCII 空白，**不包含**全角空格 U+3000。
     * 真机排版里的「开　」若只用 `\s` 是去不掉的，会漏判成 IGNORE。必须能命中。
     */
    @Test
    fun fullWidthAndZeroWidthSpacesAreStripped() {
        assertEquals("开", RedPacketLogic.normalize("\u3000开\u3000"))
        assertEquals(RedPacketLogic.Kind.OPEN, RedPacketLogic.classify("\u3000開\u3000"))
        // 不换行空格 / 零宽空格 / 零宽连字 / BOM
        assertEquals(RedPacketLogic.Kind.OPEN, RedPacketLogic.classify("\u00A0开"))
        assertEquals(RedPacketLogic.Kind.OPEN, RedPacketLogic.classify("\u200B拆红包"))
        assertEquals(RedPacketLogic.Kind.OPEN, RedPacketLogic.classify("\uFEFF领取"))
    }

    @Test
    fun emptyAndBlankInputIsIgnored() {
        for (t in listOf<String?>(null, "", "   ", "\u3000\u3000", "，。！", "\u200B")) {
            assertEquals("raw=$t", RedPacketLogic.Kind.IGNORE, RedPacketLogic.classify(t))
            assertFalse("raw=$t", RedPacketLogic.isOpenText(t))
        }
    }

    // ---------------------------------------------------------- 文案：黑名单（子串）

    @Test
    fun denyTokenSubstringIsDenied() {
        // 危险按钮都不是白名单文案，必须落到 DENY（而不是 IGNORE，便于排查）
        for (t in listOf("关闭", "查看领取详情", "再发一个", "继续发红包", "存入零钱", "提现", "取消", "知道了", "分享")) {
            assertEquals("text=$t", RedPacketLogic.Kind.DENY, RedPacketLogic.classify(t))
        }
    }

    /**
     * 黑名单**永远优先于白名单**。[classify] 里即便某个文案同时全等命中白名单又含黑名单
     * 子串，也必须判 DENY（不点）。
     *
     * 目前两张表没有交集，所以这条用例以**不变式**形式锁住：任何白名单文案都必须仍然判 OPEN。
     * 一旦以后有人往白名单里加了含黑名单子串的文案（例如「开启」），这条会变红，
     * 逼他先想清楚「误点比不点更糟」而不是直接放过。
     */
    @Test
    fun denyBeatsOpenOnConflict() {
        assertEquals(
            "含黑名单子串「开启」的文案必须是 DENY",
            RedPacketLogic.Kind.DENY,
            RedPacketLogic.classify("开启"),
        )
        for (t in RedPacketLogic.openTexts()) {
            assertEquals("whitelisted text=$t must not overlap deny tokens", RedPacketLogic.Kind.OPEN, RedPacketLogic.classify(t))
        }
    }

    // ---------------------------------------------------------- 长度上限

    @Test
    fun overlongTextIsIgnoredEvenIfItContainsDenyToken() {
        // 长度闸在文案判定之前：超过 8 个字的一律是句子不是按钮，连黑名单都不必看
        val longDeny = "关闭红包请点击这里"
        assertTrue(longDeny.length > 8)
        assertEquals(RedPacketLogic.Kind.IGNORE, RedPacketLogic.classify(longDeny))
        // 边界：正好 8 个字、又不是白名单的句子同样不点（全等要求的直接后果）
        assertEquals(RedPacketLogic.Kind.IGNORE, RedPacketLogic.classify("打开红包领取奖励"))
        // 边界：够短但含黑名单子串 → 正常走黑名单，判 DENY
        assertEquals(RedPacketLogic.Kind.DENY, RedPacketLogic.classify("查看领取详情啊"))
    }

    // ---------------------------------------------------------- 页面上下文 / 两道闸

    /**
     * ⚠️ 回归：`texts` 为空 = **采集不到上下文**（未知），不是「极简封面」。
     * 原实现 `size(0) <= 8` 会让空页面直接放行 —— 那是把「未知」当「确认」，
     * 与「误点比不点更糟」冲突。必须判 false。
     */
    @Test
    fun emptyPageContextIsUnknownNotSparse() {
        assertFalse(RedPacketLogic.looksLikeRedPacketPage(noPage))
        // 空页面即便按钮文案命中也不能点
        assertFalse(RedPacketLogic.shouldClick(noPage, "开"))
    }

    @Test
    fun pageContextByKeywordOrBySparseFallback() {
        assertTrue(RedPacketLogic.looksLikeRedPacketPage(listOf("废话一", "某某红包")))
        assertTrue(RedPacketLogic.looksLikeRedPacketPage(listOf("恭喜发财", "开")))
        // 整屏文本极少（≤8）时放宽：封面可能一个「红包」字样都没有
        assertTrue(RedPacketLogic.looksLikeRedPacketPage(listOf("张三", "新年好", "开")))
        // 文本够多且没有任何红包关键词 → 不是红包页
        val busy = (1..12).map { "普通文本$it" }
        assertFalse(RedPacketLogic.looksLikeRedPacketPage(busy))
    }

    @Test
    fun shouldClickNeedsBothGates() {
        // 两道闸都过 → 点
        assertTrue(RedPacketLogic.shouldClick(redPacketPage, "拆红包"))
        // 按钮文案没命中 → 不点
        assertFalse(RedPacketLogic.shouldClick(redPacketPage, "关闭"))
        assertFalse(RedPacketLogic.shouldClick(redPacketPage, null))
        assertFalse(RedPacketLogic.shouldClick(redPacketPage, "确定"))
        // 页面上下文不像红包页 → 不点（哪怕有个叫「开」的按钮）
        assertFalse(RedPacketLogic.shouldClick((1..12).map { "普通文本$it" }, "开"))
        assertFalse(RedPacketLogic.shouldClick(noPage, "开"))
    }

    // ---------------------------------------------------------- 节点尺寸

    @Test
    fun nodeOkRejectsHiddenDisabledAndTiny() {
        assertFalse(RedPacketLogic.nodeOk(clickable = true, enabled = false, visible = true, w = 200, h = 200))
        assertFalse(RedPacketLogic.nodeOk(clickable = true, enabled = true, visible = false, w = 200, h = 200))
        assertFalse(RedPacketLogic.nodeOk(clickable = true, enabled = true, visible = true, w = 10, h = 200))
        assertFalse(RedPacketLogic.nodeOk(clickable = true, enabled = true, visible = true, w = 200, h = 10))
        assertFalse(RedPacketLogic.nodeOk(clickable = true, enabled = true, visible = true, w = -1, h = -1))
        // 正好等于最小边长 → 通过
        assertTrue(RedPacketLogic.nodeOk(clickable = true, enabled = true, visible = true, w = 48, h = 48))
        // clickable 不作为通过条件（微信按钮常是不可点 TextView + 可点父容器）
        assertTrue(RedPacketLogic.nodeOk(clickable = false, enabled = true, visible = true, w = 100, h = 100))
    }

    // ---------------------------------------------------------- 类名

    @Test
    fun classNameMatchesOnlyMeaningfulSegments() {
        assertTrue(RedPacketLogic.isRedPacketClass("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyActivity"))
        assertTrue(RedPacketLogic.isRedPacketClass("RedPacketDetail"))
        assertTrue(RedPacketLogic.isRedPacketClass("lucky_money"))
        assertTrue(RedPacketLogic.isRedPacketClass("HongBaoActivity"))
        // ⚠️ 只认整段词：两字母 "hb" 在混淆类名里遍地都是，不能匹配
        assertFalse(RedPacketLogic.isRedPacketClass("com.tencent.mm.plugin.hb.a"))
        assertFalse(RedPacketLogic.isRedPacketClass("com.tencent.mm.ui.LauncherUI"))
        assertFalse(RedPacketLogic.isRedPacketClass(null))
        assertFalse(RedPacketLogic.isRedPacketClass(""))
    }
}
