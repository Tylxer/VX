package com.lx.wxgroup.data

import org.junit.Assert.assertEquals
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

    // ---------------------------------------------------------------- selectMessageRow / alignToEnd

    private fun node(
        width: Int, height: Int, x: Int, y: Int,
        visible: Boolean = true, isBadge: Boolean = false,
        children: List<HintBadgeLogic.BadgeNode> = emptyList()
    ) = HintBadgeLogic.BadgeNode(width, height, x, y, visible, isBadge, children)

    // 真机 8.0.48 语音 item（对方消息）根 `xi` 结构（uiautomator，1440×3200）：
    //   角标(433x65) → 时间戳槽 #bxh(106x120) → 消息行 #bqh(1440x188)
    //   消息行 #bqh 内部：头像 #bq2(187x187, 靠左 x=22) + 气泡区(601x144, x=209)
    private fun incomingAvatarRow(x: Int, bubbleW: Int) = node(
        1440, 188, 0, 206,
        children = listOf(
            node(187, 187, x, 0),            // 头像，贴外沿
            node(bubbleW, 144, 209, 0)       // 气泡区
        )
    )

    private val item1 = listOf(
        node(433, 65, 0, 0, isBadge = true),           // 我们加的角标
        node(106, 120, 667, 43),                        // 时间戳槽 #bxh
        incomingAvatarRow(x = 22, bubbleW = 601)        // 消息行 #bqh
    )

    private val item2 = listOf(
        incomingAvatarRow(x = 22, bubbleW = 288)        // 窄气泡，无时间戳槽
    )

    private val itemSelf = listOf(
        node(106, 120, 0, 0),                           // 时间戳槽
        node(1440, 188, 0, 206,
            children = listOf(
                node(601, 144, 601, 0),                 // 气泡区靠中偏左
                node(187, 187, 1231, 0)                 // 头像贴右外沿（x=1440-22-187）
            ))
    )

    @Test
    fun selectRowPicksLastTallVisibleChild() {
        // 消息行(188) 是最高子节点且是最后一个「高」节点 → 选中它，而不是角标(65)/时间戳槽(120)
        assertEquals(2, HintBadgeLogic.selectMessageRow(item1))
        assertEquals(0, HintBadgeLogic.selectMessageRow(item2))
    }

    @Test
    fun selectRowSkipsGoneAndOwnBadge() {
        // 角标自己不能当锚点；GONE 的节点跳过。这里只剩一个 GONE 的消息行 → 无候选 → -1
        val nodes = listOf(
            node(1440, 188, 0, 206, visible = false),
            node(433, 65, 0, 0, isBadge = true)
        )
        assertEquals(-1, HintBadgeLogic.selectMessageRow(nodes))
    }

    @Test
    fun selectRowEmptyGivesMinusOne() {
        assertEquals(-1, HintBadgeLogic.selectMessageRow(emptyList()))
    }

    @Test
    fun selectRowTimestampIsAlsoTallButMessageRowIsLastHigh() {
        // 阈值 = 188/2 = 94，时间戳槽 120 ≥ 94 也算「高」。但「取**最后一个**高节点」
        // 保证消息行(索引 2)胜出，而不是时间戳槽。这锁住「同名多高时按最后一个」的语义。
        assertEquals(2, HintBadgeLogic.selectMessageRow(item1))
    }

    @Test
    fun selectRowZeroHeightGivesMinusOne() {
        // 所有候选都没量完（height ≤ 0）→ 无锚点，调用方不贴角标
        assertEquals(-1, HintBadgeLogic.selectMessageRow(listOf(node(0, 0, 0, 0))))
    }

    @Test
    fun alignToEndFalseForIncoming() {
        // 对方消息：消息行内头像靠左（中心 115.5 < 中线 720）→ 角标靠左
        assertFalse(HintBadgeLogic.alignToEnd(item1))
        assertFalse(HintBadgeLogic.alignToEnd(item2))
    }

    @Test
    fun alignToEndTrueForSelf() {
        // 自己发的消息：消息行内头像靠右（中心 1324.5 > 中线 720）→ 角标靠右
        assertTrue(HintBadgeLogic.alignToEnd(itemSelf))
    }

    @Test
    fun alignToEndFallsBackToLeftWhenNoChildren() {
        // 消息行没有任何可见子节点（拿不到头像）→ 按对方消息靠左保守处理
        assertFalse(HintBadgeLogic.alignToEnd(listOf(node(1440, 188, 0, 206))))
    }

    // -------------------------------------- 复核 WARN 护栏（badge-review2 建议的补测）
    //
    // 这 5 条**不是**在测「正确行为」，而是在**把当前行为钉住**：
    // 一旦微信改了 item 内部节点顺序，或有人动了阈值公式，这些用例会立刻变红，
    // 提醒「角标锚点可能已经指错对象」——比等真机出鬼再排查便宜得多。

    @Test
    fun selectRowTimestampAfterMessageRowPicksTimestamp() {
        // ⚠️ 钉住一条**未承诺的顺序依赖**（复核判据 ③ 的 WARN，故意不修的那条）：
        //    当前真机结构是「角标 → 时间戳 #bxh(120) → 消息行 #bqh(188)」，消息行排在后
        //    才被选中（阈值 94，两者都过线，靠的是先后而非几何）。
        //    假如微信某版把时间戳挪到消息行**之后**，这里选中的就会变成时间戳：
        //    角标会贴到时间戳下方 —— 正是用户最初抱怨的「看着像给下一条消息打标」。
        //    ⇒ 本用例断言「选到时间戳」= 把该风险变成会响的警报，而不是假装它不存在。
        val timestampAfterRow = listOf(
            incomingAvatarRow(x = 22, bubbleW = 601),      // 消息行 #bqh(188)
            node(106, 120, 667, 43)                         // 时间戳槽 #bxh(120) 跑到后面
        )
        assertEquals(1, HintBadgeLogic.selectMessageRow(timestampAfterRow))
    }

    @Test
    fun selectRowOnlyTimestampNoMessageRow() {
        // 只有时间戳槽、没有消息行（系统提示 / tip 类 item）：
        // 阈值 = 120/2 = 60，时间戳 120 过线 ⇒ 仍返回它的下标（不会 -1）。
        // ⇒ 语义是「**矮子里拔高个**」：有可见子节点就一定给锚点，宁可位置不理想也不留空。
        //    这一类 item 本来就不该有角标（`typeOk` 在更外层已经拦掉），故只钉行为、不改判据。
        assertEquals(0, HintBadgeLogic.selectMessageRow(listOf(node(106, 120, 667, 43))))
    }

    @Test
    fun alignToEndCenteredSingleChildFallsBackLeft() {
        // 单个子节点**恰好居中**（中心 627+93 == rowCx 720）：恰好不满足 `> rowCx`
        // ⇒ 落到「靠左」。钉住边界是**严格大于**，不是 `>=`——
        // 否则居中元素会被判成「自己发的」，把角标甩到右边。
        val row = node(1440, 188, 0, 206, children = listOf(node(187, 187, 627, 0)))
        assertFalse(HintBadgeLogic.alignToEnd(listOf(row)))
    }

    @Test
    fun alignToEndRightAlignedSingleChild() {
        // 单个子节点贴右外沿（中心 1231+93 = 1324 > 720）⇒ 自己发的，靠右。
        val row = node(1440, 188, 0, 206, children = listOf(node(187, 187, 1231, 0)))
        assertTrue(HintBadgeLogic.alignToEnd(listOf(row)))
    }

    @Test
    fun alignToEndSingleFullWidthWrapperFallsBackLeft() {
        // 子节点是**占满整宽**的包裹层（x=0, w=1440 ⇒ 中心正好 720）：
        // 这种结构拿不到「头像贴外沿」这个特征（包裹层自己就是中线）
        // ⇒ 退化为靠左。钉住「满宽包裹层不会被误判成自己发的」。
        val row = node(1440, 188, 0, 206, children = listOf(node(1440, 188, 0, 0)))
        assertFalse(HintBadgeLogic.alignToEnd(listOf(row)))
    }
}
