package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「防止被@」纯判据（MuteAtLogic）的 JVM 回归。
 *
 * 测试数据风格：通知正文样本取自 docs/取证结论.md「通知静音」节实证的格式
 * （`verity: @V 3`——微信把 @ 渲染进通知正文）；其余按判据边界构造。
 */
class MuteAtLogicTest {

    private val room = "1234567890@chatroom"

    // ---------------------------------------------------------- 别名解析

    @Test
    fun parsesMultipleAliasesWithMixedSeparators() {
        assertEquals(listOf("V", "老王", "Alice"), MuteAtLogic.aliases("V，老王、 Alice\nV"))
    }

    @Test
    fun trimsSurroundingSpacesAndDropsEmptySegments() {
        assertEquals(listOf("V", "老王"), MuteAtLogic.aliases("  V  ,  老王  ,, "))
        assertEquals(emptyList<String>(), MuteAtLogic.aliases("   "))
        assertEquals(emptyList<String>(), MuteAtLogic.aliases(",，、"))
    }

    @Test
    fun stripsLeadingAtSoUsersCanTypeItNaturally() {
        assertEquals(listOf("V", "老王"), MuteAtLogic.aliases("@V, @老王"))
        assertEquals(emptyList<String>(), MuteAtLogic.aliases("@"))
    }

    // ---------------------------------------------------------- @我识别

    @Test
    fun broadcastMentionsAlwaysMatch() {
        assertTrue(MuteAtLogic.isAtMe("@所有人 请查看", emptyList()))
        // 中文正文紧贴中文关键字，无空格也必须命中（CJK 不算名字字符）
        assertTrue(MuteAtLogic.isAtMe("@所有人请查看", emptyList()))
        assertTrue(MuteAtLogic.isAtMe("@all 请查看", emptyList()))
    }

    @Test
    fun emptyAliasesOnlyMatchBroadcastMentions() {
        assertTrue(MuteAtLogic.isAtMe("@所有人 请查看", emptyList()))
        assertFalse(MuteAtLogic.isAtMe("verity: @V 3", emptyList()))
        assertFalse(MuteAtLogic.isAtMe("张三: @老王 在吗", emptyList()))
    }

    @Test
    fun aliasMentionMatchesAnyOfMultipleAliases() {
        assertTrue(MuteAtLogic.isAtMe("verity: @V 3", listOf("V")))
        assertTrue(MuteAtLogic.isAtMe("张三: @老王 在吗", listOf("V", "老王")))
        assertTrue(MuteAtLogic.isAtMe("Bob: @Alice hi", listOf("Alice", "Bob")))
    }

    @Test
    fun otherPersonsMentionsNeverMatch() {
        // @别人：整个昵称对不上
        assertFalse(MuteAtLogic.isAtMe("张三: @小李 在吗", listOf("V", "老王")))
        // 短名误伤防护：@V 不得命中 @Verity / @VIP / @V_2 / @V-test
        assertFalse(MuteAtLogic.containsAtAlias("张三: @Verity 收到", "V"))
        assertFalse(MuteAtLogic.containsAtAlias("张三: @VIP 收到", "V"))
        assertFalse(MuteAtLogic.containsAtAlias("张三: @V_2 收到", "V"))
        assertFalse(MuteAtLogic.containsAtAlias("张三: @V-test 收到", "V"))
        assertFalse(MuteAtLogic.containsAtAlias("张三: @V3 收到", "V"))
        // 同一行里先出现 @VIP 再出现 @V，后者仍应命中
        assertTrue(MuteAtLogic.containsAtAlias("先 @VIP 后 @V。", "V"))
        assertTrue(MuteAtLogic.containsAtAlias("张三: @V 收到", "V"))
        assertTrue(MuteAtLogic.containsAtAlias("@V", "V"))
        // 中文昵称紧贴中文正文必须命中（旧实现用 isLetterOrDigit 把 CJK 当名字字符，会漏）
        assertTrue(MuteAtLogic.containsAtAlias("@老王在吗", "老王"))
    }

    @Test
    fun keywordAllIsCaseInsensitiveButAliasesAreCaseSensitive() {
        assertTrue(MuteAtLogic.containsAtAlias("@ALL 请查看", "all", ignoreCase = true))
        assertTrue(MuteAtLogic.containsAtAlias("@All 请查看", "all", ignoreCase = true))
        assertTrue(MuteAtLogic.isAtMe("@ALL 请查看", emptyList()))
        assertFalse(MuteAtLogic.containsAtAlias("@allow 请查看", "all", ignoreCase = true))
        // 用户别名大小写敏感：宁可漏判，也不把 @别人 静音
        assertFalse(MuteAtLogic.containsAtAlias("@v 在吗", "V"))
        assertFalse(MuteAtLogic.isAtMe("@v 在吗", listOf("V")))
    }

    @Test
    fun emptyTextAndEmptyAliasNeverMatch() {
        assertFalse(MuteAtLogic.isAtMe("", listOf("V")))
        assertFalse(MuteAtLogic.containsAtAlias("@V 3", ""))
        assertFalse(MuteAtLogic.containsAtAlias("@V 3", "  "))
        assertFalse(MuteAtLogic.isAtMe("普通消息，没有@", listOf("V")))
    }

    // ---------------------------------------------------------- 作用域（名单）

    @Test
    fun emptyAllowMeansAllGroups() {
        assertTrue(MuteAtLogic.groupInScope(room, null, emptyList(), emptyList()))
    }

    @Test
    fun nonEmptyAllowRestrictsToItsRooms() {
        assertTrue(MuteAtLogic.groupInScope(room, null, listOf(room), emptyList()))
        assertFalse(MuteAtLogic.groupInScope(room, null, listOf("other@chatroom"), emptyList()))
    }

    @Test
    fun exclusionWinsEvenWhenAlsoAllowed() {
        assertFalse(MuteAtLogic.groupInScope(room, null, listOf(room), listOf(room)))
        assertFalse(MuteAtLogic.groupInScope(room, null, emptyList(), listOf(room)))
        // 排除名单里只有别的群时不影响本群
        assertTrue(MuteAtLogic.groupInScope(room, null, emptyList(), listOf("other@chatroom")))
    }

    @Test
    fun neverForDirectChatsOrMissingTalker() {
        assertFalse(MuteAtLogic.groupInScope("wxid_someone", null, emptyList(), emptyList()))
        assertFalse(MuteAtLogic.groupInScope(null, null, emptyList(), emptyList()))
        assertFalse(MuteAtLogic.groupInScope("   ", null, emptyList(), emptyList()))
    }

    @Test
    fun wxidEntriesNeedChatroomSuffixAndTolerateSpaces() {
        assertTrue(MuteAtLogic.groupInScope(room, null, listOf("  $room  "), emptyList()))
        // 不带 @chatroom 后缀的一律按群名匹配，群名未知（null）→ 不命中
        assertFalse(MuteAtLogic.groupInScope(room, null, listOf("1234567890"), emptyList()))
    }

    @Test
    fun groupNameEntriesMatchOnlyWhenNameKnown() {
        assertTrue(MuteAtLogic.groupInScope(room, "家庭群", listOf("家庭群"), emptyList()))
        assertTrue(MuteAtLogic.groupInScope(room, "  家庭群  ", listOf("家庭群"), emptyList()))
        assertFalse(MuteAtLogic.groupInScope(room, null, listOf("家庭群"), emptyList()))
        assertFalse(MuteAtLogic.groupInScope(room, "公司群", listOf("家庭群"), emptyList()))
        assertFalse(MuteAtLogic.groupInScope(room, "家庭群", listOf("家庭群"), listOf("家庭群")))
    }

    @Test
    fun blankListEntriesAreIgnoredSoListStaysEffectivelyEmpty() {
        // 全是空白的允许名单 = 空名单 = 对所有群生效
        assertTrue(MuteAtLogic.groupInScope(room, null, listOf("", "   "), emptyList()))
        // 全是空白的排除名单 = 谁都没排除
        assertTrue(MuteAtLogic.groupInScope(room, null, emptyList(), listOf(" ")))
    }
}
