package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R15「朋友圈图片走 SNS 磁盘缓存直读」的**纯字符串判据**回归测试。
 *
 * 这一批判据写错的后果都是「真机跑一轮才发现」：
 * - `fliterPre` 差一位 ⇒ 所有文件名对不上，`imgCopied=0`
 * - 候选优先级反了 ⇒ 明明有原图却只导了 40KB 缩略图
 * - 边界规则漏了 ⇒ `snst_150` 把 `snst_1503…`（另一条动态的图）当成自己的
 *
 * 依据：`docs/_r15_sns_cache_plan.md` 的 Q1（fliterPre 反汇编）/ Q2b（索引直扫）/ Q3（三级前缀）。
 */
class SnsCacheMatchTest {

    // ------------------------------------------------------------ fliterPre

    @Test
    fun fliterPreSkipsLeadingZerosOnly() {
        // 首位非 '0' ⇒ 原样返回（真机 20 位形态）
        assertEquals("15038644089917813396", MomentsLogic.fliterPre("15038644089917813396"))
        // 前导 0 ⇒ 只跳前导 0，其余原样保留
        assertEquals("15038873180398883448", MomentsLogic.fliterPre("0015038873180398883448"))
        // 跳前导 0 后，中间的 0 保留
        assertEquals("1500", MomentsLogic.fliterPre("001500"))
    }

    @Test
    fun fliterPreKeepsLeadingNonZero() {
        assertEquals("12345", MomentsLogic.fliterPre("12345"))
        // 只跳 '0'，其他字符原样保留（mediaId 长度有 8 种，别假设全是数字）
        assertEquals("12a0b", MomentsLogic.fliterPre("12a0b"))
    }

    @Test
    fun fliterPreEmptyAndAllZeroCases() {
        assertEquals("", MomentsLogic.fliterPre(""))
        assertEquals("", MomentsLogic.fliterPre("0"))
        assertEquals("", MomentsLogic.fliterPre("00"))
        // 全 0 ⇒ 跳完到结尾 ⇒ 空
        assertEquals("", MomentsLogic.fliterPre("00000000"))
    }

    @Test
    fun fliterPreOnlySkipsZeroNotOtherChars() {
        // 首位非 '0' 的短串不再恒为空（i 从 0 起，不是 2）
        assertEquals("12", MomentsLogic.fliterPre("12"))
        assertEquals("10", MomentsLogic.fliterPre("10"))
        // 前导 0 后的字母及其后的 0 都保留
        assertEquals("a0b", MomentsLogic.fliterPre("00a0b"))
    }

    @Test
    fun fliterPreSkipsMultipleLeadingZeros() {
        assertEquals("123", MomentsLogic.fliterPre("00123"))
        assertEquals("3", MomentsLogic.fliterPre("0003"))
    }

    // ------------------------------------------------------------ snsNameCandidates

    @Test
    fun candidatesTierOrderIsBigThenMidThenThumb() {
        // 带前导 0 的 mediaId：fliterPre(原值) != 原值 ⇒ 双候选各 3 前缀共 6 项
        val c = MomentsLogic.snsNameCandidates("0015038873180398883448")
        // 6 项：三级前缀 × 两个键（fliterPre 结果 + mediaId 原值）
        assertEquals(6, c.size)
        assertEquals(
            listOf(
                "snsb_15038873180398883448", "snsb_0015038873180398883448",
                "snsu_15038873180398883448", "snsu_0015038873180398883448",
                "snst_15038873180398883448", "snst_0015038873180398883448",
            ),
            c,
        )
    }

    @Test
    fun candidatesFallBackToMediaIdWhenFliterPreIsEmpty() {
        // "000" 的 fliterPre 为空 ⇒ 只剩 3 项，且都不能命中真机文件（边界规则会挡）
        val c = MomentsLogic.snsNameCandidates("000")
        assertEquals(listOf("snsb_", "snsu_", "snst_"), c)
    }

    @Test
    fun candidatesOfEmptyMediaIdIsEmpty() {
        assertTrue(MomentsLogic.snsNameCandidates("").isEmpty())
    }

    @Test
    fun candidatesPreferOriginalOverThumbnail() {
        // 前缀优先于键：第一个候选必须是 snsb_（原图），不是 snst_
        val c = MomentsLogic.snsNameCandidates("0015038873180398883448")
        assertEquals("snsb_15038873180398883448", c.first())
        assertTrue(c.first().startsWith("snsb_"))
    }

    // ------------------------------------------------------------ matchSnsFileName

    @Test
    fun matchExactName() {
        val idx = setOf("snsb_38644089917813396", "snst_999")
        assertEquals(
            "snsb_38644089917813396",
            MomentsLogic.matchSnsFileName(idx, "snsb_38644089917813396"),
        )
    }

    @Test
    fun matchNameWithEncSuffix() {
        // 真机 sight_ 带 &enc 尾巴；图片理论上也可能带，兜住不吃亏
        val idx = setOf("snsb_38644089917813396&enc123")
        assertEquals(
            "snsb_38644089917813396&enc123",
            MomentsLogic.matchSnsFileName(idx, "snsb_38644089917813396"),
        )
    }

    @Test
    fun matchNameWithVersionSuffix() {
        val idx = setOf("snst_38644089917813396_V2")
        assertEquals(
            "snst_38644089917813396_V2",
            MomentsLogic.matchSnsFileName(idx, "snst_38644089917813396"),
        )
    }

    @Test
    fun doesNotMatchLongerNumber() {
        // 核心边界：`snst_150` 不能命中 `snst_1503…`（那是另一条动态的图）
        val idx = setOf("snst_15034444444444444444", "snst_1503")
        assertNull(MomentsLogic.matchSnsFileName(idx, "snst_150"))
    }

    @Test
    fun doesNotMatchWhenCandidateIsPrefixOfAnotherNumber() {
        val idx = setOf("snsb_3864408991781339677")
        assertNull(MomentsLogic.matchSnsFileName(idx, "snsb_38644089917813396"))
    }

    @Test
    fun emptyIndexReturnsNull() {
        assertNull(MomentsLogic.matchSnsFileName(emptySet(), "snsb_1"))
    }

    @Test
    fun emptyCandidateReturnsNull() {
        assertNull(MomentsLogic.matchSnsFileName(setOf("snsb_1"), ""))
    }

    @Test
    fun multipleTailsReturnLexicographicallySmallest() {
        // 确定性：多个同前缀的尾巴（&enc/_V2）时取字典序最小，跨设备结果一致
        val idx = setOf(
            "snsb_38644089917813396_V2",
            "snsb_38644089917813396&enc1",
            "snsb_38644089917813396",
        )
        // 精确命中优先，不受尾巴干扰
        assertEquals(
            "snsb_38644089917813396",
            MomentsLogic.matchSnsFileName(idx, "snsb_38644089917813396"),
        )
        val onlyTails = setOf(
            "snsb_38644089917813396_V2",
            "snsb_38644089917813396&enc1",
        )
        assertEquals(
            "snsb_38644089917813396&enc1",
            MomentsLogic.matchSnsFileName(onlyTails, "snsb_38644089917813396"),
        )
    }

    // ------------------------------------------------------------ 排除规则（白名单）

    @Test
    fun excludesBlurPlaceholder() {
        assertFalse(MomentsLogic.isSnsMediaName("snstblur_src_38644089917813396"))
    }

    @Test
    fun excludesAvatarCache() {
        assertFalse(MomentsLogic.isSnsMediaName("wxid_0123456789abcdef0123456789abcdef_1"))
    }

    @Test
    fun excludesVideo() {
        assertFalse(MomentsLogic.isSnsMediaName("sight_38644089917813396&enc1_V2"))
    }

    @Test
    fun acceptsThreeTiers() {
        assertTrue(MomentsLogic.isSnsMediaName("snsb_38644089917813396"))
        assertTrue(MomentsLogic.isSnsMediaName("snsu_38644089917813396"))
        assertTrue(MomentsLogic.isSnsMediaName("snst_38644089917813396"))
    }

    @Test
    fun excludesUnrelatedNames() {
        assertFalse(MomentsLogic.isSnsMediaName(""))
        assertFalse(MomentsLogic.isSnsMediaName("snsblurt_1"))
        assertFalse(MomentsLogic.isSnsMediaName("sightad_1"))
        assertFalse(MomentsLogic.isSnsMediaName("random.jpg"))
    }

    @Test
    fun excludedNamesNeverWinAgainstRealCandidate() {
        // 端到端：索引里同时有模糊图/头像/视频和真图，候选匹配必须落到真图上
        val idx = setOf(
            "snstblur_src_38644089917813396",
            "wxid_38644089917813396",
            "sight_38644089917813396&enc1",
            "snsu_38644089917813396",
        ).filter { MomentsLogic.isSnsMediaName(it) }.toSet()

        var hit: String? = null
        for (c in MomentsLogic.snsNameCandidates("0038644089917813396")) {
            val n = MomentsLogic.matchSnsFileName(idx, c)
            if (n != null) {
                hit = n
                break
            }
        }
        assertEquals("snsu_38644089917813396", hit)
    }
}
