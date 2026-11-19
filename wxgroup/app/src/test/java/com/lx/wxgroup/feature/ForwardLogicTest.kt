package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 需求 6.6「强制转发」纯逻辑层（[ForwardLogic]）的单测。
 *
 * 数据来源纪律（对应 CLAUDE.md 阶段1「测试数据用真机真实样本」，详见 docs/开发回路速查.md §六）：
 * 1. **菜单词表**：CONV 词表真机取证（docs/取证结论.md「会话长按菜单」：
 *    `标为未读 / 取消置顶 / 不显示该聊天 / 删除该聊天` 四项）；
 *    **CHAT 词表待 op=forward dump 验证**（当前是常见长按项组合推断，未取证）。
 * 2. **appmsg XML 样本覆盖 8.0.48 实测见过的两种写法**
 *    （见 [ForwardLogic.appMsgTypeOf] 注释）：`<type>` 带空格、`<appmsg>` 前有 XML 头。
 *
 * 重点防护 [ForwardLogic] 类注释点名的**最贵误判**：把「转发」挂到会话列表菜单上。
 */
class ForwardLogicTest {

    // ------------------------------------------------------------ kindOf

    @Test
    fun kindOfMapsHighConfidenceTypes() {
        assertEquals(ForwardLogic.Kind.TEXT, ForwardLogic.kindOf(ForwardLogic.MSG_TYPE_TEXT))
        assertEquals(ForwardLogic.Kind.IMAGE, ForwardLogic.kindOf(ForwardLogic.MSG_TYPE_IMAGE))
        assertEquals(ForwardLogic.Kind.VOICE, ForwardLogic.kindOf(ForwardLogic.MSG_TYPE_VOICE))
        assertEquals(ForwardLogic.Kind.VIDEO, ForwardLogic.kindOf(ForwardLogic.MSG_TYPE_VIDEO))
    }

    @Test
    fun kindOfMapsMediumConfidenceTypes() {
        assertEquals(ForwardLogic.Kind.CARD, ForwardLogic.kindOf(ForwardLogic.MSG_TYPE_CARD))
        assertEquals(ForwardLogic.Kind.LOCATION, ForwardLogic.kindOf(ForwardLogic.MSG_TYPE_LOCATION))
    }

    @Test
    fun kindOfAppMsgUsesSubType() {
        assertEquals(
            ForwardLogic.Kind.FILE,
            ForwardLogic.kindOf(ForwardLogic.MSG_TYPE_APP, ForwardLogic.APP_TYPE_FILE)
        )
        assertEquals(
            ForwardLogic.Kind.LINK,
            ForwardLogic.kindOf(ForwardLogic.MSG_TYPE_APP, ForwardLogic.APP_TYPE_URL)
        )
        assertEquals(
            ForwardLogic.Kind.VIDEO,
            ForwardLogic.kindOf(ForwardLogic.MSG_TYPE_APP, ForwardLogic.APP_TYPE_VIDEO)
        )
        assertEquals(
            ForwardLogic.Kind.IMAGE,
            ForwardLogic.kindOf(ForwardLogic.MSG_TYPE_APP, ForwardLogic.APP_TYPE_EMOJI)
        )
        // 合并转发的聊天记录按文本兜底（能救多少是多少）
        assertEquals(
            ForwardLogic.Kind.TEXT,
            ForwardLogic.kindOf(ForwardLogic.MSG_TYPE_APP, ForwardLogic.APP_TYPE_CHAT_HISTORY)
        )
    }

    @Test
    fun kindOfAppMsgWithoutSubTypeIsUnknown() {
        // 默认 appType=0：拿不到子类型时不猜
        assertEquals(ForwardLogic.Kind.UNKNOWN, ForwardLogic.kindOf(ForwardLogic.MSG_TYPE_APP))
        assertEquals(
            ForwardLogic.Kind.UNKNOWN,
            ForwardLogic.kindOf(ForwardLogic.MSG_TYPE_APP, 999)
        )
    }

    @Test
    fun kindOfUnknownAndSystemTypesAreUnknown() {
        assertEquals(ForwardLogic.Kind.UNKNOWN, ForwardLogic.kindOf(0))
        assertEquals(ForwardLogic.Kind.UNKNOWN, ForwardLogic.kindOf(47))
        // 系统提示（含撤回提示）不转发
        assertEquals(
            ForwardLogic.Kind.UNKNOWN,
            ForwardLogic.kindOf(ForwardLogic.MSG_TYPE_SYSTEM)
        )
    }

    @Test
    fun coveredKindsAreTheEightNamedOnes() {
        assertTrue(ForwardLogic.isCovered(ForwardLogic.Kind.TEXT))
        assertTrue(ForwardLogic.isCovered(ForwardLogic.Kind.FILE))
        assertFalse(ForwardLogic.isCovered(ForwardLogic.Kind.UNKNOWN))
        assertEquals(8, ForwardLogic.COVERED.size)
        assertEquals("文件", ForwardLogic.labelOf(ForwardLogic.Kind.FILE))
        assertEquals("未知", ForwardLogic.labelOf(ForwardLogic.Kind.UNKNOWN))
    }

    // ------------------------------------------------------------ appMsgTypeOf

    @Test
    fun appMsgTypeOfParsesPlainTag() {
        val xml =
            "<msg><appmsg appid=\"\" sdkver=\"0\"><title>周报.pdf</title>" +
                "<type>6</type><showtype>0</showtype></appmsg></msg>"
        assertEquals(6, ForwardLogic.appMsgTypeOf(xml))
    }

    @Test
    fun appMsgTypeOfParsesSpacedTagSeenOnRealDevice() {
        // 8.0.48 实测见过 `<type>6</type>` 带空格的写法（两种都覆盖）
        assertEquals(6, ForwardLogic.appMsgTypeOf("<appmsg><type> 6 </type></appmsg>"))
        assertEquals(6, ForwardLogic.appMsgTypeOf("<appmsg>< type >6< / type ></appmsg>"))
    }

    @Test
    fun appMsgTypeOfParsesWithXmlHeader() {
        // 8.0.48 实测见过 `<appmsg>` 前面带 XML 头的写法
        val xml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><msg><appmsg sdkver=\"0\">" +
                "<title>腾讯新闻</title><type>5</type><url>https://example.com</url></appmsg></msg>"
        assertEquals(5, ForwardLogic.appMsgTypeOf(xml))
    }

    @Test
    fun appMsgTypeOfRealSamplesEndToEnd() {
        // 真实样本 → 子类型 → 消息种类，整条判据链锁在一起
        val fileXml =
            "<msg><appmsg appid=\"\" sdkver=\"0\"><title>周报.pdf</title><type>6</type></appmsg></msg>"
        val linkXml =
            "<?xml version=\"1.0\"?><msg><appmsg sdkver=\"0\"><type>5</type>" +
                "<url>https://example.com</url></appmsg></msg>"
        assertEquals(
            ForwardLogic.Kind.FILE,
            ForwardLogic.kindOf(49, ForwardLogic.appMsgTypeOf(fileXml))
        )
        assertEquals(
            ForwardLogic.Kind.LINK,
            ForwardLogic.kindOf(49, ForwardLogic.appMsgTypeOf(linkXml))
        )
    }

    @Test
    fun appMsgTypeOfDegradesQuietly() {
        assertEquals(0, ForwardLogic.appMsgTypeOf(null))
        assertEquals(0, ForwardLogic.appMsgTypeOf(""))
        assertEquals(0, ForwardLogic.appMsgTypeOf("   "))
        assertEquals(0, ForwardLogic.appMsgTypeOf("<appmsg><title>没有 type</title></appmsg>"))
        assertEquals(0, ForwardLogic.appMsgTypeOf("<appmsg><type>abc</type></appmsg>"))
        // 负数子类型原样返回（不猜是不是合法，交给 kindOf 判）
        assertEquals(-1, ForwardLogic.appMsgTypeOf("<appmsg><type>-1</type></appmsg>"))
    }

    // ------------------------------------------------------------ classifyMenu

    /** 真机取证到的会话列表长按菜单（docs/取证结论.md「会话长按菜单」） */
    private val realConvMenu = listOf("标为未读", "取消置顶", "不显示该聊天", "删除该聊天")

    @Test
    fun realConvMenuIsConvList() {
        assertEquals(
            ForwardLogic.MenuKind.CONV_LIST,
            ForwardLogic.classifyMenu(realConvMenu)
        )
    }

    @Test
    fun convMenuWithPinnedVariantIsStillConvList() {
        // 「置顶该聊天 / 取消置顶」随状态二选一，另一种三项组合也必须认得出
        assertEquals(
            ForwardLogic.MenuKind.CONV_LIST,
            ForwardLogic.classifyMenu(listOf("标记未读", "置顶该聊天", "不显示该聊天", "删除该聊天"))
        )
    }

    @Test
    fun realChatMsgMenuIsChatMsg() {
        // 微信 8.0.48 聊天页长按文本消息的常见菜单组合
        val chatMenu = listOf("复制", "转发", "收藏", "删除", "引用", "多选", "提醒")
        assertEquals(ForwardLogic.MenuKind.CHAT_MSG, ForwardLogic.classifyMenu(chatMenu))
        // 单消息菜单也可能长这样（无多选）
        assertEquals(
            ForwardLogic.MenuKind.CHAT_MSG,
            ForwardLogic.classifyMenu(listOf("复制", "转发", "收藏", "删除", "引用"))
        )
    }

    @Test
    fun convMenuNeverMistakenForChatEvenWithDeleteLikeWords() {
        // ★ 最贵误判防护：会话菜单里有「删除该聊天」，用 contains 匹配「删除」会把
        // 会话列表菜单误认成消息菜单 → 「转发」挂到会话列表上。严格相等下 chat=0。
        val items = listOf("删除该聊天", "不显示该聊天", "置顶该聊天", "标记未读")
        assertEquals(ForwardLogic.MenuKind.CONV_LIST, ForwardLogic.classifyMenu(items))
        // 反证：同样的词一个都不能算聊天词命中
        val chatHits = items.count { it in ForwardLogic.CHAT_MENU_WORDS }
        assertEquals(0, chatHits)
    }

    @Test
    fun explicitConvWinsOnTieToAvoidAmbiguousSlide() {
        // 规则 1：会话词命中 ≥2 且不少于聊天词 → 会话列表（明确的那套优先）
        val items = listOf("转发", "删除", "标记未读", "不显示该聊天")
        val conv = items.count { it in ForwardLogic.CONV_MENU_WORDS }
        val chat = items.count { it in ForwardLogic.CHAT_MENU_WORDS }
        assertEquals(2, conv)
        assertEquals(2, chat)
        assertEquals(ForwardLogic.MenuKind.CONV_LIST, ForwardLogic.classifyMenu(items))
    }

    @Test
    fun chatWinsWhenItHasStrictlyMoreHits() {
        // 会话词只有 1 个（不够 minHits）→ 回落到聊天菜单判定
        val items = listOf("复制", "转发", "收藏", "删除该聊天")
        assertEquals(ForwardLogic.MenuKind.CHAT_MSG, ForwardLogic.classifyMenu(items))
        // 会话词 2 个但聊天词 3 个 → 聊天赢（conv >= chat 不成立）
        val items2 = listOf("复制", "转发", "收藏", "标记未读", "不显示该聊天")
        assertEquals(ForwardLogic.MenuKind.CHAT_MSG, ForwardLogic.classifyMenu(items2))
    }

    @Test
    fun singleHitOrEmptyIsNone() {
        assertEquals(ForwardLogic.MenuKind.NONE, ForwardLogic.classifyMenu(emptyList()))
        assertEquals(ForwardLogic.MenuKind.NONE, ForwardLogic.classifyMenu(listOf("复制")))
        assertEquals(ForwardLogic.MenuKind.NONE, ForwardLogic.classifyMenu(listOf("转发", "其它")))
        // 认不出的浮层（输入法面板、表情面板等）静默放弃
        assertEquals(
            ForwardLogic.MenuKind.NONE,
            ForwardLogic.classifyMenu(listOf("发送", "表情", "语音输入"))
        )
    }

    @Test
    fun minHitsIsHonored() {
        val three = listOf("复制", "转发", "收藏")
        assertEquals(ForwardLogic.MenuKind.CHAT_MSG, ForwardLogic.classifyMenu(three, minHits = 3))
        assertEquals(ForwardLogic.MenuKind.NONE, ForwardLogic.classifyMenu(three, minHits = 4))
    }

    // ------------------------------------------------------------ findMenuBoxCandidate
    // R73 根因：findMenuBox 旧口径只数「直接子 TextView」，ListView 的 item 是
    // LinearLayout 包 TextView ⇒ 命中恒 0。这些用例锁住「递归数子树」判据。
    // R73-L2b：判据再收紧为「命中数最多优先、同数时取最深」，锁住多行网格菜单选菜单体。

    private fun node(
        id: String,
        text: String? = null,
        container: Boolean = false,
        children: List<ForwardLogic.MenuNode> = emptyList(),
    ) = ForwardLogic.MenuNode(text = text, isContainer = container, children = children, id = id)

    /** 一个 ListView item：LinearLayout 包 TextView（真机 MH TREE-DIFF 的形状） */
    private fun item(id: String, word: String) = node(
        id, container = true, children = listOf(node("$id-tv", text = word))
    )

    @Test
    fun findMenuBoxCandidateFindsListViewWithNestedItemViews() {
        // 正例1：PopupWindow > FrameLayout > ListView > item(LinearLayout > TextView) xN
        val listView = node(
            "list", container = true,
            children = listOf(item("it0", "复制"), item("it1", "转发"), item("it2", "删除")),
        )
        val tree = node("popup", container = true, children = listOf(
            node("frame", container = true, children = listOf(listView))
        ))
        val cand = ForwardLogic.findMenuBoxCandidate(listOf(tree))
        assertNotNull(cand)
        // 最深优先：必须选 ListView，不能是更浅的 frame/popup（那样入口塞到列表外）
        assertEquals("list", cand!!.boxId)
        // 样本是子树 DFS 最后一个命中 TextView，样式来源就是真实菜单项那一行
        assertEquals("it2-tv", cand.sampleId)
    }

    @Test
    fun findMenuBoxCandidateFindsPlainMenuWithDirectTextChildren() {
        // 正例2（零回归）：原生普通菜单，容器直接挂 TextView（无 item 包裹）
        val menu = node(
            "menu", container = true,
            children = listOf(node("t0", text = "复制"), node("t1", text = "转发"), node("t2", text = "收藏")),
        )
        val cand = ForwardLogic.findMenuBoxCandidate(listOf(
            node("popup", container = true, children = listOf(menu))
        ))
        assertNotNull(cand)
        assertEquals("menu", cand!!.boxId)
        assertEquals("t2", cand.sampleId)
    }

    @Test
    fun findMenuBoxCandidateRejectsConvListMenu() {
        // 反例1：会话列表菜单词不在 CHAT_MENU_WORDS（严格相等）⇒ 命中 0 ⇒ 不注入（CONV_LIST）
        val conv = node(
            "conv", container = true,
            children = listOf(
                node("c0", text = "删除该聊天"),
                node("c1", text = "不显示该聊天"),
                node("c2", text = "标为未读"),
            ),
        )
        assertNull(ForwardLogic.findMenuBoxCandidate(listOf(conv)))
    }

    @Test
    fun findMenuBoxCandidateRejectsNonMenuOverlay() {
        // 反例2：非菜单浮层（无命中词）⇒ 找不到，静默放弃
        val panel = node(
            "panel", container = true,
            children = listOf(node("x0", text = "发送"), node("x1", text = "表情"), node("x2", text = "语音输入")),
        )
        assertNull(ForwardLogic.findMenuBoxCandidate(listOf(panel)))
    }

    @Test
    fun findMenuBoxCandidateNeedsAtLeastTwoHits() {
        // 变异防护：只有 1 个命中词的浮层不算菜单 box。
        // 若把判据放宽成 hits>=1，本用例（以及上面两个反例）应变红。
        val one = node("one", container = true, children = listOf(node("t0", text = "复制")))
        assertNull(ForwardLogic.findMenuBoxCandidate(listOf(one)))
        // 2 个命中则入选，锁住「≥2」这个下界本身
        val two = node("two", container = true, children = listOf(
            node("t0", text = "复制"), node("t1", text = "转发")
        ))
        assertEquals("two", ForwardLogic.findMenuBoxCandidate(listOf(two))!!.boxId)
    }

    // --- R73-L2b：命中数优先 / 同数取最深（多行网格菜单定位） ---

    @Test
    fun findMenuBoxCandidatePicksMenuBodyForMultiRowGrid() {
        // 真机 8.0.48 长按菜单形状（本次 bug 现场）：多行横排网格，不是 ListView。
        //   PopupWindow > menu(菜单体) > [row1(5 项) | 分隔线 | row2(4 项)]
        // 旧「最深优先」选中 row1（命中5、depth 更深）⇒ 入口 addView 塞进第一行 ⇒ 视觉在顶部；
        // 新「命中数优先」应选覆盖全部 9 项的 menu ⇒ 入口落末尾。
        fun textRow(prefix: String, words: List<String>) = words.mapIndexed { i, w ->
            node("${prefix}c$i", text = w)
        }
        val row1 = node("row1", container = true, children = textRow(
            "r1", listOf("复制", "转发", "收藏", "删除", "多选")
        ))
        val row2 = node("row2", container = true, children = textRow(
            "r2", listOf("引用", "提醒", "翻译", "搜一搜")
        ))
        val menu = node("menu", container = true, children = listOf(
            row1, node("divider", text = "分隔线"), row2,
        ))
        val tree = node("popup", container = true, children = listOf(menu))

        val cand = ForwardLogic.findMenuBoxCandidate(listOf(tree))
        assertNotNull(cand)
        // 命中 9 > row1 的 5 ⇒ 选菜单体，不选最深的行容器
        assertEquals("menu", cand!!.boxId)
        // 样本 = 菜单体子树 DFS 最后一个命中项 = row2 最后一项「搜一搜」
        assertEquals("r2c3", cand.sampleId)
    }

    @Test
    fun findMenuBoxCandidateBreaksHitTieByDepth() {
        // 同命中数取最深：ListView 与上层外壳命中同一批 N=3 项，必须选 ListView。
        // 若同分取浅（外壳胜出），入口会被塞到列表之外 —— R73-L2b 明令禁止的回归。
        val listView = node("list", container = true, children = listOf(
            item("it0", "复制"), item("it1", "转发"), item("it2", "删除"),
        ))
        val shell = node("shell", container = true, children = listOf(listView))
        val tree = node("popup", container = true, children = listOf(shell))

        val cand = ForwardLogic.findMenuBoxCandidate(listOf(tree))
        assertNotNull(cand)
        assertEquals("list", cand!!.boxId)
        assertEquals("it2-tv", cand.sampleId)
    }

    @Test
    fun findMenuBoxCandidatePrefersMoreHitsOverDeeperContainer() {
        // 本次 bug 的直接回归锁：浅层大容器（命中5）必须赢过深层小容器（命中2），
        // 即便后者 depth 更大。旧「只比深度」会选 deep ⇒ 入口塞进小容器 ⇒ 位置错。
        val big = node("big", container = true, children = listOf(
            node("b0", text = "复制"), node("b1", text = "转发"), node("b2", text = "收藏"),
            node("b3", text = "删除"), node("b4", text = "多选"),
        ))
        val deep = node("deep", container = true, children = listOf(
            node("d0", text = "引用"), node("d1", text = "提醒"),
        ))
        val deepTree = node("outer", container = true, children = listOf(
            node("mid", container = true, children = listOf(deep))
        ))

        val cand = ForwardLogic.findMenuBoxCandidate(listOf(big, deepTree))
        assertNotNull(cand)
        assertEquals("big", cand!!.boxId)
    }

    // ------------------------------------------------------------ resolveTargets

    private val buckets = ForwardLogic.Buckets(
        groups = mapOf(
            "1" to listOf("wxid_a", "wxid_b"),
            "2" to listOf("wxid_b", "wxid_c"),
        ),
        labels = mapOf("好友" to listOf("wxid_d")),
    )

    @Test
    fun bareWxidPassesThrough() {
        assertEquals(listOf("wxid_x"), ForwardLogic.resolveTargets(listOf("wxid_x")))
    }

    @Test
    fun groupPrefixExpandsToMembers() {
        assertEquals(
            listOf("wxid_a", "wxid_b"),
            ForwardLogic.resolveTargets(listOf("g:1"), buckets)
        )
    }

    @Test
    fun labelPrefixExpandsToMembers() {
        assertEquals(
            listOf("wxid_d"),
            ForwardLogic.resolveTargets(listOf("t:好友"), buckets)
        )
    }

    @Test
    fun samePersonInMultipleBucketsSentOnce() {
        // g:1 与 g:2 都含 wxid_b → 只出现一次
        assertEquals(
            listOf("wxid_a", "wxid_b", "wxid_c"),
            ForwardLogic.resolveTargets(listOf("g:1", "g:2"), buckets)
        )
        // 分组 + 裸 wxid 重复勾选也去重
        assertEquals(
            listOf("wxid_a", "wxid_b"),
            ForwardLogic.resolveTargets(listOf("g:1", "wxid_a"), buckets)
        )
    }

    @Test
    fun excludeDropsListedTargets() {
        // 典型场景：排除当前会话（转发回原会话没意义）
        assertEquals(
            listOf("wxid_b", "wxid_c"),
            ForwardLogic.resolveTargets(listOf("g:1", "g:2"), buckets, exclude = setOf("wxid_a"))
        )
        assertEquals(
            listOf("wxid_b"),
            ForwardLogic.resolveTargets(listOf("g:1"), buckets, exclude = setOf("wxid_a"))
        )
    }

    @Test
    fun emptyOrMissingBucketsAreSwallowed() {
        assertTrue(ForwardLogic.resolveTargets(listOf("g:404"), buckets).isEmpty())
        assertTrue(ForwardLogic.resolveTargets(listOf("t:不存在的标签"), buckets).isEmpty())
        assertTrue(ForwardLogic.resolveTargets(emptyList(), buckets).isEmpty())
    }

    @Test
    fun selectionOrderIsStable() {
        assertEquals(
            listOf("z", "a", "m"),
            ForwardLogic.resolveTargets(listOf("z", "a", "m"))
        )
        // 混合选择保持勾选顺序：分组 → 单个好友 → 标签
        assertEquals(
            listOf("wxid_a", "wxid_b", "wxid_z", "wxid_d"),
            ForwardLogic.resolveTargets(listOf("g:1", "wxid_z", "t:好友"), buckets)
        )
    }

    @Test
    fun blankAndWhitespaceEntriesAreSkipped() {
        assertEquals(
            listOf("wxid_a"),
            ForwardLogic.resolveTargets(listOf("", "  ", "wxid_a"), buckets)
        )
        // id 两端空白先 trim 再判前缀
        assertEquals(
            listOf("wxid_a", "wxid_b"),
            ForwardLogic.resolveTargets(listOf(" g:1 "), buckets)
        )
    }

    // ------------------------------------------------------------ planFor

    private fun snap(
        kind: ForwardLogic.Kind,
        text: String = "",
        localPath: String = "",
        fileExists: Boolean = false,
    ) = ForwardLogic.Snapshot(kind = kind, text = text, localPath = localPath, fileExists = fileExists)

    @Test
    fun nativeWhenAnchorAvailable() {
        // 原生可用时不问原料，直接走原生（保留转发标记）
        val plan = ForwardLogic.planFor(
            snap(ForwardLogic.Kind.TEXT),
            ForwardLogic.NativeState.AVAILABLE
        )
        assertEquals(ForwardLogic.Strategy.NATIVE, plan.strategy)
        // 连图片这种「没有文本原料」的也能原生
        assertEquals(
            ForwardLogic.Strategy.NATIVE,
            ForwardLogic.planFor(
                snap(ForwardLogic.Kind.IMAGE),
                ForwardLogic.NativeState.AVAILABLE
            ).strategy
        )
    }

    @Test
    fun degradeToResendTextWhenTextAvailable() {
        val plan = ForwardLogic.planFor(
            snap(ForwardLogic.Kind.TEXT, text = "今晚八点开会"),
            ForwardLogic.NativeState.UNKNOWN
        )
        assertEquals(ForwardLogic.Strategy.RESEND_TEXT, plan.strategy)
        assertTrue(plan.reason.isNotBlank())
        // 被拒与未取证一样走降级
        assertEquals(
            ForwardLogic.Strategy.RESEND_TEXT,
            ForwardLogic.planFor(
                snap(ForwardLogic.Kind.TEXT, text = "hi"),
                ForwardLogic.NativeState.REJECTED
            ).strategy
        )
    }

    @Test
    fun degradeToResendFileWhenLocalFileExists() {
        val plan = ForwardLogic.planFor(
            snap(ForwardLogic.Kind.IMAGE, localPath = "/storage/.../jpg", fileExists = true),
            ForwardLogic.NativeState.UNKNOWN
        )
        assertEquals(ForwardLogic.Strategy.RESEND_FILE, plan.strategy)
    }

    // ---------------------------------------------------------- 文件重发能力（A 档）

    @Test
    fun canResendFileOnlyForImage() {
        // 2026-10-20 静态取证：只有 IMAGE(qf4/o4.O8) 有已证实的完整链路。
        // VIDEO 曾判 true，复核时发现 r1.a(o1.VIDEO) 造的是普通 q1，而 t81/g.i 第一句
        // 就 instance-of modelimage/o3、不是就直接 return ⇒ l1.a 恒 null ⇒ 恒静默失败。
        // 放宽这里 = 对着未证实的反射调用赌微信不崩，所以逐个种类锁死。
        assertEquals(true, ForwardLogic.canResendFile(ForwardLogic.Kind.IMAGE))
        for (k in listOf(
            ForwardLogic.Kind.TEXT,
            ForwardLogic.Kind.VIDEO,   // ← 视频没链路，必须在这里面
            ForwardLogic.Kind.VOICE,
            ForwardLogic.Kind.FILE,
            ForwardLogic.Kind.CARD,
            ForwardLogic.Kind.LOCATION,
            ForwardLogic.Kind.LINK,
            ForwardLogic.Kind.UNKNOWN,
        )) {
            assertEquals("kind=$k 不该被判为可重发文件", false, ForwardLogic.canResendFile(k))
        }
    }

    @Test
    fun plainFileWithLocalPathSkipsInsteadOfFailingPerTarget() {
        // 普通文件有本地路径、但没有可用的文件发送链路 ⇒ 应提前 SKIP 并说清原因。
        // 若这里退化成 RESEND_FILE，真机上会「逐目标失败」，toast 只剩「失败 N」不说为什么。
        val plan = ForwardLogic.planFor(
            snap(
                ForwardLogic.Kind.FILE,
                text = "",
                localPath = "/storage/.../pdf",
                fileExists = true
            ),
            ForwardLogic.NativeState.UNKNOWN
        )
        assertEquals(ForwardLogic.Strategy.SKIP, plan.strategy)
        assertTrue("reason 要说明是链路缺失：${plan.reason}", plan.reason.contains("暂无发送链路"))
    }

    @Test
    fun voiceWithLocalPathAlsoSkips() {
        // 语音（type=34）同样没有已证实的非 UI 发送链路
        val plan = ForwardLogic.planFor(
            snap(
                ForwardLogic.Kind.VOICE,
                localPath = "/storage/.../amr",
                fileExists = true
            ),
            ForwardLogic.NativeState.UNKNOWN
        )
        assertEquals(ForwardLogic.Strategy.SKIP, plan.strategy)
    }

    @Test
    fun videoWithLocalPathSkipsInsteadOfFailingPerTarget() {
        // 视频有本地文件但**没有**可用的发送链路（r1.a(VIDEO) 造不出 o3，见 canResendFile 注释）
        // ⇒ 应提前 SKIP 并说清原因，而不是逐目标失败（toast 只剩「失败 N」不说为什么）。
        val plan = ForwardLogic.planFor(
            snap(ForwardLogic.Kind.VIDEO, localPath = "/storage/.../mp4", fileExists = true),
            ForwardLogic.NativeState.UNKNOWN
        )
        assertEquals(ForwardLogic.Strategy.SKIP, plan.strategy)
        assertTrue("reason 要说明是链路缺失：${plan.reason}", plan.reason.contains("暂无发送链路"))
    }

    @Test
    fun imageWithoutExistingFileStillFallsThroughToSkip() {
        // fileExists=false 时不应走 RESEND_FILE（sender 里也挡，但计划层就该挡住）
        val plan = ForwardLogic.planFor(
            snap(ForwardLogic.Kind.IMAGE, localPath = "/storage/.../jpg", fileExists = false),
            ForwardLogic.NativeState.UNKNOWN
        )
        assertEquals(ForwardLogic.Strategy.SKIP, plan.strategy)
    }

    @Test
    fun textWinsOverFileWhenBothAvailable() {
        // 有文本优先重发文本（文件路径此时用不上）
        val plan = ForwardLogic.planFor(
            snap(
                ForwardLogic.Kind.FILE,
                text = "周报.pdf",
                localPath = "/storage/.../pdf",
                fileExists = true
            ),
            ForwardLogic.NativeState.REJECTED
        )
        assertEquals(ForwardLogic.Strategy.RESEND_TEXT, plan.strategy)
    }

    @Test
    fun skipUncoveredKindEvenIfNativeAvailable() {
        // 系统提示等不覆盖的类型：原生可用也不转发
        assertEquals(
            ForwardLogic.Strategy.SKIP,
            ForwardLogic.planFor(
                snap(ForwardLogic.Kind.UNKNOWN, text = "你撤回了一条消息"),
                ForwardLogic.NativeState.AVAILABLE
            ).strategy
        )
    }

    @Test
    fun skipUncoveredKindWithNativeUnknownEvenIfTextPresent() {
        // ★ 真机长按路径的形状：snapshotNow 的 kind 恒 UNKNOWN（G2）、nativeState()
        // 恒 UNKNOWN → planFor 首行 isCovered(UNKNOWN)=false 恒 SKIP，failed 恒 0。
        // execute() 的 toast 后缀条件因此必须看 sent==0，不能看 failed>0。
        // 有文本原料也救不回来（首行就拦掉）—— 防止将来有人把 isCovered 挪到
        // 原料判断之后，「未知类型也硬发」。
        val plan = ForwardLogic.planFor(
            ForwardLogic.Snapshot(kind = ForwardLogic.Kind.UNKNOWN, text = "x"),
            ForwardLogic.NativeState.UNKNOWN
        )
        assertEquals(ForwardLogic.Strategy.SKIP, plan.strategy)
        assertTrue(plan.reason.contains("不支持"))
    }

    @Test
    fun skipWhenNoResendMaterial() {
        // 取不到原料：静默放弃，比发一个空消息好
        val plan = ForwardLogic.planFor(
            snap(ForwardLogic.Kind.IMAGE, localPath = "/gone.jpg", fileExists = false),
            ForwardLogic.NativeState.UNKNOWN
        )
        assertEquals(ForwardLogic.Strategy.SKIP, plan.strategy)
        assertTrue(plan.reason.isNotBlank())
    }

    @Test
    fun chatHistoryKindIsCoveredAndDegradesAsText() {
        // 合并转发的聊天记录（appType=19）按文本兜底
        assertEquals(
            ForwardLogic.Kind.TEXT,
            ForwardLogic.kindOf(49, ForwardLogic.APP_TYPE_CHAT_HISTORY)
        )
        val plan = ForwardLogic.planFor(
            snap(ForwardLogic.Kind.TEXT, text = "合并转发的聊天记录"),
            ForwardLogic.NativeState.REJECTED
        )
        assertEquals(ForwardLogic.Strategy.RESEND_TEXT, plan.strategy)
    }

    // ------------------------------------------------------------ summary

    @Test
    fun summaryTextIsStable() {
        assertEquals("已发送 3 个目标", ForwardLogic.summary(3, 0, 0, 0))
        // R73：degraded 计数保留但不展示「降级 N」（用户不需要转发标记）
        assertEquals("已发送 5 个目标", ForwardLogic.summary(5, 2, 0, 0))
        assertEquals("已发送 5 个目标 · 跳过 1", ForwardLogic.summary(5, 0, 1, 0))
        assertEquals("已发送 5 个目标 · 失败 3", ForwardLogic.summary(5, 0, 0, 3))
        assertEquals(
            "已发送 5 个目标 · 跳过 1 · 失败 3",
            ForwardLogic.summary(5, 2, 1, 3)
        )
        // 全零也给出确定的文案（不返回空串）
        assertEquals("已发送 0 个目标", ForwardLogic.summary(0, 0, 0, 0))
    }

    @Test
    fun degradedCountNoLongerShownInToast() {
        // R73：用户明确不需要转发标记，toast 不再带降级字样（成功场景纯噪音）
        val s = ForwardLogic.summary(5, 3, 0, 0)
        assertFalse("toast 不得含「降级」", "降级" in s)
        assertFalse("toast 不得含无转发标记提示", "转发标记" in s)
        assertEquals("已发送 5 个目标", s)
    }
}
