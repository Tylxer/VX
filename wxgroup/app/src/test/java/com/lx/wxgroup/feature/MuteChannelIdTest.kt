package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B 静音通道常量与 importance 的 JVM 回归（零 Android 依赖）。
 *
 * 用例设计对准根因重大反转结论：
 * - 旧 id `wxgroup_silent` / `wxgroup_silent_v2` 已以 IMPORTANCE_HIGH 落地且被 user-lock，新 id 必须 ≠ 两者；
 * - importance 必须为 LOW(=2)：保留通知栏、静音、不弹横幅；绝不能是 HIGH(=4，会响) 或 MIN(=1，更隐蔽)；
 * - isMute / isLowImportance 必须严格相等，禁止 contains/startsWith 或区间判定。
 */
class MuteChannelIdTest {

    @Test
    fun newValueDiffersFromLegacyLockedId() {
        assertNotEquals("wxgroup_silent", MuteChannelId.VALUE)
    }

    @Test
    fun isMuteMatchesTheExactNewId() {
        assertEquals("wxgroup_silent_low", MuteChannelId.VALUE)
        assertTrue(MuteChannelId.isMute(MuteChannelId.VALUE))
        assertTrue(MuteChannelId.isMute("wxgroup_silent_low"))
    }

    @Test
    fun legacyIdsDoNotMatch() {
        assertFalse(MuteChannelId.isMute("wxgroup_silent"))
        assertFalse(MuteChannelId.isMute("wxgroup_silent_v2"))
    }

    @Test
    fun nullAndEmptyDoNotMatch() {
        assertFalse(MuteChannelId.isMute(null))
        assertFalse(MuteChannelId.isMute(""))
    }

    @Test
    fun suffixDoesNotMatchBySubstring() {
        assertFalse(MuteChannelId.isMute("wxgroup_silent_low_extra"))
    }

    @Test
    fun importanceLevelIsLow() {
        assertEquals(2, MuteChannelId.IMPORTANCE_LEVEL)
    }

    @Test
    fun importanceLevelIsNotHigh() {
        assertNotEquals(4, MuteChannelId.IMPORTANCE_LEVEL)
    }

    @Test
    fun importanceLevelIsNotMin() {
        assertNotEquals(1, MuteChannelId.IMPORTANCE_LEVEL)
    }

    @Test
    fun isLowImportanceUsesStrictEquality() {
        assertTrue(MuteChannelId.isLowImportance(2))
        assertFalse(MuteChannelId.isLowImportance(4))
        assertFalse(MuteChannelId.isLowImportance(0))
    }
}
