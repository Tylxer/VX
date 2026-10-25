package com.lx.wxgroup.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HintBadgeLogic] 的判据回归测试。
 *
 * 两个判据改错一处就要重上一轮真机（≈2 分钟 + 排查），所以先在这里锁住：
 * - [HintBadgeLogic.typeOk]：类型闸门（低 16 位，吞掉 0x10000000 高位标志）。
 * - [HintBadgeLogic.contentOk]：文本对账（item 子树文字必须含内容前缀）。
 *
 * 高位标志值 0x10000000|10000 = 268445456、0x10000000|10002 = 268445458，
 * 与 AntiRevoke.kt 注释里的 `0x10000000 or 10000` 一致。
 */
class HintBadgeLogicTest {

    // ---------------------------------------------------------------- typeOk

    @Test
    fun typeOkPlainText() {
        // 普通文本：h9.getType() == origType == 1
        assertTrue(HintBadgeLogic.typeOk(1, 1))
        assertTrue(HintBadgeLogic.typeOk(34, 34))   // 语音
        assertTrue(HintBadgeLogic.typeOk(3, 3))     // 图片
    }

    @Test
    fun typeOkIgnoresHighFlag() {
        // 微信自己加的 0x10000000 高位标志不参与比对（低 16 位一致即可）
        assertTrue(HintBadgeLogic.typeOk(0x10000000 or 1, 1))
        assertTrue(HintBadgeLogic.typeOk(0x10000000 or 34, 34))
    }

    @Test
    fun typeOkRejectsMaskedRevoke() {
        // 「恢复默认显示」伪装态：h9 低 16 位 = 10000/10002，与原文 type 不等 ⇒ 抑制角标
        assertFalse(HintBadgeLogic.typeOk(0x10000000 or 10000, 1))
        assertFalse(HintBadgeLogic.typeOk(0x10000000 or 10002, 34))
        assertFalse(HintBadgeLogic.typeOk(10000, 1))
        assertFalse(HintBadgeLogic.typeOk(10002, 3))
    }

    @Test
    fun typeOkWithFlaggedOrigType() {
        // 台账里的 origType 可能自带高位标志（team-lead 实证 805306417 = 0x30000000|49）
        // 低 16 位比对让它与未加标志的 49 视为同型
        assertTrue(HintBadgeLogic.typeOk(805306417, 805306417))
        assertTrue(HintBadgeLogic.typeOk(49, 805306417))
        assertTrue(HintBadgeLogic.typeOk(805306417, 49))
    }

    // ---------------------------------------------------------------- contentOk

    @Test
    fun contentOkExactMatch() {
        assertTrue(HintBadgeLogic.contentOk("你好", listOf("你好")))
    }

    @Test
    fun contentOkBubbleHasSurroundingText() {
        // 群聊气泡里内容 TextView 独立于昵称/时间，probe 命中内容那条即可
        assertTrue(HintBadgeLogic.contentOk("你好", listOf("张三", "你好", "14:30")))
    }

    @Test
    fun contentOkLongContentTruncatedBubble() {
        // 长文本气泡被截断，但保留了前 12 字符前缀 → 仍能对上
        val long = "这是一条特别长的消息内容超过了十二个字符".repeat(4)
        val truncatedBubble = long.take(40)
        assertTrue(HintBadgeLogic.contentOk(long, listOf(truncatedBubble)))
    }

    @Test
    fun contentOkFlattensWhitespace() {
        // 台账内容有换行，气泡渲染成单行
        assertTrue(HintBadgeLogic.contentOk("第一行\n第二行", listOf("第一行 第二行")))
    }

    @Test
    fun contentOkIsStrict() {
        assertFalse(HintBadgeLogic.contentOk(null, listOf("你好")))          // 拿不到内容
        assertFalse(HintBadgeLogic.contentOk("", listOf("你好")))            // 空内容
        assertFalse(HintBadgeLogic.contentOk("   ", listOf("你好")))         // 空白内容
        assertFalse(HintBadgeLogic.contentOk("1", listOf("1")))              // 1 字太短
        assertFalse(HintBadgeLogic.contentOk("你好", emptyList()))            // 读不到文字
        assertFalse(HintBadgeLogic.contentOk("你好", listOf("再见")))          // 对不上
    }

    @Test
    fun contentOkShortContentNeedsLengthTwoOrMore() {
        // 两字是下限：一字内容无法可靠对账
        assertTrue(HintBadgeLogic.contentOk("好的", listOf("好的")))
        assertFalse(HintBadgeLogic.contentOk("好", listOf("好的")))
    }

    // ------------------------------------------- 复核 _r21d_badge_review.md §5 补的缺口

    @Test
    fun typeOkUnknownTypeIsSuppressed() {
        // typeOf 反射失败 ⇒ 传 TYPE_UNKNOWN(-1)。类型不明应该**抑制**，不能找一个能匹配的值。
        // ⚠️ 若没有这条守卫，-1 and 0xFFFF == 0xFFFF，台账里恰好有 orig_type=-1 就会误配。
        assertFalse(HintBadgeLogic.typeOk(HintBadgeLogic.TYPE_UNKNOWN, HintBadgeLogic.TYPE_UNKNOWN))
        assertFalse(HintBadgeLogic.typeOk(HintBadgeLogic.TYPE_UNKNOWN, 1))
        assertFalse(HintBadgeLogic.typeOk(1, HintBadgeLogic.TYPE_UNKNOWN))
        // 任意负数都按「不明」处理，不只是恰好 -1
        assertFalse(HintBadgeLogic.typeOk(-2, -2))
        assertFalse(HintBadgeLogic.typeOk(-1, 0xFFFF))
    }

    @Test
    fun typeOkMaskedSystemTypeStillMatchesItself() {
        // 台账被污染成系统态（orig_type=10000）：两个都 10000 时闸门放行是**对的**
        // （类型确实一致），真正的拦截在 contentOk / rescued 命中上。
        // 这里锁住的是「不要为这种情况额外加特判」，避免以后有人误加。
        assertTrue(HintBadgeLogic.typeOk(10000, 10000))
        assertTrue(HintBadgeLogic.typeOk(10002, 10002))
        // 但「原文是文本、库里是系统态」必须拦（伪装态）
        assertFalse(HintBadgeLogic.typeOk(1, 10000))
        assertFalse(HintBadgeLogic.typeOk(10000, 1))
    }

    @Test
    fun contentOkEmojiProbeNotSplitMidSurrogate() {
        // 😀 占 2 个 UTF-16 码元。11 个汉字 + 1 个 emoji ⇒ take(12) 正好**切断代理对**。
        // 半截代理是非法字符，与任何字符串都匹配不上 ⇒ 本该命中的消息被判 false（角标漏画）。
        val content = "一二三四五六七八九十十😀abc"

        // ① 气泡完整显示（含 emoji）—— 加不加修复都能命中，不是判别点
        assertTrue(HintBadgeLogic.contentOk(content, listOf(content)))

        // ② 真正的判别点：微信把 emoji 渲染成**图片表情**，气泡文字里没有 emoji 字符，
        //    只剩那 11 个汉字。此时 probe 若带半截代理就永远匹配不上。
        val bubbleEmojiAsImage = "一二三四五六七八九十十"
        assertTrue("probe 切在 emoji 中间时必须退化成合法前缀", 
            HintBadgeLogic.contentOk(content, listOf(bubbleEmojiAsImage)))

        // ③ 反向对照：未修版本的行为（probe 带孤立高位代理）在这条气泡上必然失败。
        //    这条断言证明 ② 的收益是真的，不是「加不加都一样」。
        val naive = content.take(HintBadgeLogic.PROBE_LEN)
        assertTrue("前置：take(12) 确实切断了代理对", Character.isHighSurrogate(naive.last()))
        assertFalse("未修的 probe 匹配不上这条气泡", bubbleEmojiAsImage.contains(naive))
    }

    @Test
    fun contentOkAtMentionMayMissOnPurpose() {
        // 群聊 @ 提及：台账存 wxid（@wxid_abc），气泡渲染成昵称（@张三）⇒ 对不上。
        // 这是**已知且接受**的假阴性：宁可漏画，也不能放松到「随便对上就贴」。
        assertFalse(HintBadgeLogic.contentOk("@wxid_abc 你好", listOf("@张三 你好")))
        // 但正文部分落在 probe 窗口里时仍能命中（probe 只取前 12 字符）
        assertTrue(HintBadgeLogic.contentOk("你好这是一条正文", listOf("你好这是一条正文")))
    }

    @Test
    fun contentOkProbeDegeneratesToFalseNotTrue() {
        // 极端：内容只有 2 个字且第二个是 emoji 前半 —— 去掉代理后只剩 1 个字符，
        // 低于下限 ⇒ 必须 false（不能退化成「1 个字符也算命中」）
        assertFalse(HintBadgeLogic.contentOk("好\uD83D", listOf("好")))
    }
}
