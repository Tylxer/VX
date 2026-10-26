package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R28：朋友圈「广告位」判据的回归测试。
 *
 * ## 为什么值得单独立一个测试
 * 旧结论（`docs/conclusions/moments.md`）写的是 `bit7(128)` = 广告，**是错的**。
 * R28 静态取证订正：广告位是 `bit5(32)`，依据 `SnsInfo.isAd()` 的方法体字节码
 * （`const/16 v2,#int 32` → `isSourceExist(32)`），且有 `AdSnsInfo.convertToSnsInfo()`
 * 显式 `addSourceFlag(2)` + `addSourceFlag(32)` 等多条独立证据互证。
 *
 * ⛔ 128 的真实含义是「折叠 / 长文本需要点『全文』」。实测出现过的 `sourceType=130`
 * = 128|2 是**正常的折叠动态** —— 按旧结论过滤会把它整组误杀，而且误杀是静默的
 * （用户只会觉得「少导了几条」）。
 *
 * ⇒ 下面两条是**反向失效**的守门测试，改坏了必须红：
 *   [folded_dynamic_is_not_ad]（130 不是广告）、[folded_bit_alone_is_not_ad]（128 不是广告）。
 */
class MomentsAdFlagTest {

    @Test
    fun flag_constants_match_forensics() {
        assertEquals(2, MomentsLogic.SOURCE_FLAG_TIMELINE)
        assertEquals(4, MomentsLogic.SOURCE_FLAG_PRIVATE)
        assertEquals("广告位必须是 bit5(32)：SnsInfo.isAd() 判的就是它", 32, MomentsLogic.SOURCE_FLAG_AD)
        assertEquals("128 是折叠位，不是广告位", 128, MomentsLogic.SOURCE_FLAG_FOLDED)
    }

    @Test
    fun ad_bit_alone_is_ad() {
        assertTrue(MomentsLogic.isAd(32))
    }

    @Test
    fun ad_plus_timeline_is_ad() {
        // 广告进入普通时间线时落库为 2|32 = 34（AdSnsInfo.convertToSnsInfo 的字面证据）
        assertTrue(MomentsLogic.isAd(34))
        assertEquals(34, MomentsLogic.SOURCE_FLAG_TIMELINE or MomentsLogic.SOURCE_FLAG_AD)
    }

    @Test
    fun ad_plus_folded_plus_private_is_ad() {
        assertTrue(MomentsLogic.isAd(128 or 32 or 4))
    }

    @Test
    fun folded_dynamic_is_not_ad() {
        // ⛔ 反向失效守门：130 = 128|2 是正常折叠动态。按旧结论（128=广告）会被误杀。
        assertFalse("sourceType=130 是折叠动态，绝不能判成广告", MomentsLogic.isAd(130))
    }

    @Test
    fun folded_bit_alone_is_not_ad() {
        assertFalse(MomentsLogic.isAd(128))
    }

    @Test
    fun normal_and_other_observed_values_are_not_ad() {
        // 真机实测出现过的全部非广告取值：0(死来源) / 2(可见) / 6(可见|私密) / 8 / 10(8|2)
        for (v in intArrayOf(0, 2, 4, 6, 8, 10, 128, 130)) {
            assertFalse("sourceType=$v 不应判成广告", MomentsLogic.isAd(v))
        }
    }

    @Test
    fun ad_flag_does_not_leak_into_others() {
        // 位运算写错（比如用了 == 而不是 and）时，34 会被判 false，下面这条会红
        assertTrue(MomentsLogic.isAd(32 or 64))
        assertTrue(MomentsLogic.isAd(0xFF or 32))
        assertFalse(MomentsLogic.isAd(0xFF and 32.inv()))
    }
}
