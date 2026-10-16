package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 需求 6.6「强制转发」纯逻辑层（[ForwardLogic]）的单测。
 *
 * 数据来源纪律（对应 docs/开发流程与原则.md 阶段1「测试数据用真机真实样本」）：
 * 1. **菜单词表全部取自真机取证**（docs/取证结论.md「会话长按菜单」）：
 *    会话列表菜单就是 `标为未读 / 取消置顶 / 不显示该聊天 / 删除该聊天` 四项；
 *    聊天消息菜单用微信 8.0.48 上常见的长按项组合。
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
        assertEquals("已发送 5 个目标 · 降级 2", ForwardLogic.summary(5, 2, 0, 0))
        assertEquals("已发送 5 个目标 · 跳过 1", ForwardLogic.summary(5, 0, 1, 0))
        assertEquals("已发送 5 个目标 · 失败 3", ForwardLogic.summary(5, 0, 0, 3))
        assertEquals(
            "已发送 5 个目标 · 降级 2 · 跳过 1 · 失败 3",
            ForwardLogic.summary(5, 2, 1, 3)
        )
        // 全零也给出确定的文案（不返回空串）
        assertEquals("已发送 0 个目标", ForwardLogic.summary(0, 0, 0, 0))
    }

    @Test
    fun degradedToastTextMatchesSpec() {
        // 需求 6.6 原文：「已作为普通消息发送，无转发标记」
        assertEquals("已作为普通消息发送，无转发标记", ForwardLogic.TOAST_DEGRADED)
    }
}
