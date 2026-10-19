package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群显示名纯判据（GroupNameLogic）的 JVM 回归。
 *
 * 取值样本取自 2026-10-18 真机 `rcontact` 实证（5 条群样本 conRemark 全空、
 * 群名在 nickname），空白类样本按微信昵称里真实存在的「不可见字符」构造。
 */
class GroupNameLogicTest {

    private val room = "254407642@chatroom"

    // ---------------------------------------------------------- 取值优先级

    @Test
    fun conRemarkWinsOverNickname() {
        // 用户手动备注过群 → 备注就是用户认知里的群名（与好友同一条规则）
        assertEquals("家庭群", GroupNameLogic.pick("家庭群", "相亲相爱么么扎～"))
    }

    @Test
    fun blankConRemarkFallsBackToNickname() {
        // 真机 5 条样本的形态：conRemark 是空串，群名只在 nickname
        assertEquals("相亲相爱么么扎～", GroupNameLogic.pick("", "相亲相爱么么扎～"))
        assertEquals("PM13片区  兼5号线", GroupNameLogic.pick(null, "PM13片区  兼5号线"))
        assertEquals("凯丰一号线", GroupNameLogic.pick("   ", "凯丰一号线"))
        // 全角空格 / NBSP / 零宽字符肉眼不可见，必须也当空处理，否则名单永远对不上
        assertEquals("GRS开TC交流群", GroupNameLogic.pick("\u3000", "GRS开TC交流群"))
        assertEquals("8人3鸽群", GroupNameLogic.pick("\u00A0", "8人3鸽群"))
        assertEquals("PM13片区", GroupNameLogic.pick("\u200B\uFEFF", "PM13片区"))
    }

    @Test
    fun bothBlankYieldsNull() {
        assertNull(GroupNameLogic.pick(null, null))
        assertNull(GroupNameLogic.pick("", ""))
        assertNull(GroupNameLogic.pick("   ", "\u3000\u00A0"))
        assertNull(GroupNameLogic.pick("\u200B", null))
    }

    @Test
    fun pickTrimsEndsButKeepsInnerSpaces() {
        // 群名里的连续空格是真名的一部分，压掉就跟用户填的配置对不上了
        assertEquals("PM13片区  兼5号线", GroupNameLogic.pick(null, "  PM13片区  兼5号线\u3000"))
    }

    @Test
    fun normalizeTreatsInvisibleCharsAsBlank() {
        assertEquals("", GroupNameLogic.normalize("\u00A0\u3000\u200B\uFEFF \t\n"))
        assertEquals("abc", GroupNameLogic.normalize("\u00A0abc\u200B"))
        assertEquals("", GroupNameLogic.normalize(null))
    }

    // ---------------------------------------------------------- 名单匹配

    @Test
    fun matchesByChatroomIdEvenWhenNameUnknown() {
        assertTrue(GroupNameLogic.matches(room, null, listOf(room)))
        assertTrue(GroupNameLogic.matches(room, null, listOf("  $room  ")))
        assertFalse(GroupNameLogic.matches(room, null, listOf("other@chatroom")))
    }

    @Test
    fun matchesByGroupNameWhenKnown() {
        assertTrue(GroupNameLogic.matches(room, "家庭群", listOf("家庭群")))
        assertTrue(GroupNameLogic.matches(room, "家庭群", listOf("  家庭群  ")))
        // 群名未知时只剩 id 匹配，群名元素不该命中（这是冷启动首条通知的降级形态）
        assertFalse(GroupNameLogic.matches(room, null, listOf("家庭群")))
        assertFalse(GroupNameLogic.matches(room, "公司群", listOf("家庭群")))
    }

    @Test
    fun mixedCandidatesHitEitherWay() {
        val candidates = listOf("家庭群", "other@chatroom", room, "公司群")
        assertTrue(GroupNameLogic.matches(room, null, candidates))
        assertTrue(GroupNameLogic.matches(room, "家庭群", candidates))
        // 同一个群名靠群名命中（id 不在里面）
        assertTrue(GroupNameLogic.matches(room, "公司群", candidates))
        // 反向：列表里既没有这个 id、也没有这个群名 → 不命中
        assertFalse(GroupNameLogic.matches(room, "陌生群", listOf("家庭群", "other@chatroom")))
    }

    @Test
    fun caseAndSpaceDifferencesStillMatch() {
        // 用户手抄配置时大小写/首尾空格随意，比对必须宽容
        assertTrue(GroupNameLogic.matches(room, "GRS开TC交流群", listOf("grs开tc交流群")))
        assertTrue(GroupNameLogic.matches(room, "grs开TC交流群", listOf("  GRS开TC交流群 ")))
        assertTrue(GroupNameLogic.matches(room, "  PM13片区  兼5号线 ", listOf("PM13片区  兼5号线")))
    }

    @Test
    fun emptyCandidatesMeansNoRestrictionJustLikeGroupInScope() {
        // 与 MuteAtLogic.groupInScope 的 allow 语义同口径：空名单 = 不限群 = 命中
        assertTrue(GroupNameLogic.matches(room, null, emptyList()))
        assertTrue(GroupNameLogic.matches(room, "家庭群", emptyList()))
        assertTrue(GroupNameLogic.matches(room, null, listOf("", "   ", "\u3000")))
        // 交叉锁定：同输入下 groupInScope 的 allow 分支必须同为 true，否则两处语义漂移
        assertTrue(MuteAtLogic.groupInScope(room, null, emptyList(), emptyList()))
        assertTrue(MuteAtLogic.groupInScope(room, null, listOf("", "   "), emptyList()))
    }

    @Test
    fun missingChatroomIdNeverMatches() {
        assertFalse(GroupNameLogic.matches("", "家庭群", listOf("家庭群")))
        assertFalse(GroupNameLogic.matches("   ", null, emptyList()))
    }

    @Test
    fun handlesFullWidthBracketsAndEmojiInGroupName() {
        val name = "【开黑】5排🎮（周末）"
        assertEquals(name, GroupNameLogic.pick(null, name))
        assertTrue(GroupNameLogic.matches(room, name, listOf(name)))
        assertFalse(GroupNameLogic.matches(room, name, listOf("【开黑】5排🎮（工作日）")))
        // 代理对（emoji）不能被 trim 逻辑切坏
        assertEquals("🎮", GroupNameLogic.pick(null, "🎮"))
    }

    @Test
    fun groupNameContainingVisibleSpacesIsPreservedExactly() {
        // 名单里少写一个空格就不该命中：内部空格是名字的一部分，不做归一化折叠
        assertFalse(GroupNameLogic.matches(room, "PM13片区  兼5号线", listOf("PM13片区 兼5号线")))
    }

    // ---------------------------------------------------------- 日志短名

    @Test
    fun displayNameUsesNameWhenPresent() {
        assertEquals("相亲相爱么么扎～", GroupNameLogic.displayNameOf(room, "相亲相爱么么扎～"))
        assertEquals("相亲相爱么么扎～", GroupNameLogic.displayNameOf(room, "  相亲相爱么么扎～ "))
    }

    @Test
    fun displayNameFallsBackToIdWithoutSuffix() {
        // 直接取后 8 位会恒等于 "chatroom"（所有群 id 同尾巴），必须先剥后缀
        assertEquals("254407642", GroupNameLogic.displayNameOf(room, null))
        assertEquals("254407642", GroupNameLogic.displayNameOf(room, "\u3000"))
        assertEquals("19501172869", GroupNameLogic.displayNameOf("19501172869@chatroom", ""))
    }

    @Test
    fun displayNameTruncatesOnlyOverlongLocalPart() {
        val longRoom = "12345678901234567890@chatroom"
        assertEquals("34567890", GroupNameLogic.displayNameOf(longRoom, null))
    }
}
