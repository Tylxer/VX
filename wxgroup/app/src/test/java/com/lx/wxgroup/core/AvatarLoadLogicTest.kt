package com.lx.wxgroup.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AvatarLoadLogic] 的纯判据回归测试（R29-2）。
 *
 * 钉死三件事：
 * ① 取图优先级状态机：内存命中 → 设图；内存 miss → 磁盘异步；磁盘回来按「tag 是否还命中 /
 *    图是否可用」裁决设图还是兜底。
 * ② tag 复用校验：列表行复用时 `view.tag` 与请求 wxid 不一致必须判兜底（否则头像错位到别人头上）。
 * ③ 这些判据零 Android 依赖，JVM 直接跑（Bitmap 相关不强求）。
 */
class AvatarLoadLogicTest {

    // ---------------------------------------------------------------- 优先级

    @Test
    fun memoryHitGoesStraightToSetImage() {
        assertEquals(
            AvatarLoadLogic.Action.SET_IMAGE,
            AvatarLoadLogic.decide(memoryHit = true),
        )
    }

    @Test
    fun memoryMissGoesToDiskAsync() {
        assertEquals(
            AvatarLoadLogic.Action.LOAD_DISK_ASYNC,
            AvatarLoadLogic.decide(memoryHit = false),
        )
    }

    @Test
    fun fullPriorityChainEndsInFallbackWhenDiskEmpty() {
        // 状态机走一遍：内存 miss → 磁盘 async；磁盘回来 tag 命中但图空 → 兜底
        assertEquals(
            AvatarLoadLogic.Action.LOAD_DISK_ASYNC,
            AvatarLoadLogic.decide(memoryHit = false),
        )
        assertEquals(
            AvatarLoadLogic.Action.FALLBACK,
            AvatarLoadLogic.afterDisk(tagMatches = true, bitmapOk = false),
        )
    }

    @Test
    fun fullPriorityChainEndsInSetImageWhenDiskHits() {
        assertEquals(
            AvatarLoadLogic.Action.SET_IMAGE,
            AvatarLoadLogic.afterDisk(tagMatches = true, bitmapOk = true),
        )
    }

    // ---------------------------------------------------------------- 复用校验

    @Test
    fun recycledRowMustFallbackEvenIfBitmapOk() {
        // 行已经被复用成别人的 wxid，哪怕后台图都解出来了也不能设 —— 这是列表错位的经典 bug
        assertEquals(
            AvatarLoadLogic.Action.FALLBACK,
            AvatarLoadLogic.afterDisk(tagMatches = false, bitmapOk = true),
        )
    }

    @Test
    fun tagMatchesSameWxid() {
        assertTrue(AvatarLoadLogic.tagMatches("wxid_a", "wxid_a"))
    }

    @Test
    fun tagMatchesDifferentWxid() {
        assertFalse(AvatarLoadLogic.tagMatches("wxid_a", "wxid_b"))
    }

    @Test
    fun tagMatchesNullTagAlwaysFalse() {
        // 还没 setTag / tag 被清掉：一律判不命中，走兜底
        assertFalse(AvatarLoadLogic.tagMatches(null, "wxid_a"))
        assertFalse(AvatarLoadLogic.tagMatches(null, ""))
    }

    @Test
    fun tagMatchesNeverConfusesBlankWithRealWxid() {
        assertFalse(AvatarLoadLogic.tagMatches("", "wxid_a"))
        assertFalse(AvatarLoadLogic.tagMatches("wxid_a", ""))
    }

    // ---------------------------------------------------------------- 边界

    @Test
    fun afterDiskEveryCombinationIsDeterministic() {
        // tag 不命中 → 一律 FALLBACK，与图是否可用无关
        assertEquals(AvatarLoadLogic.Action.FALLBACK, AvatarLoadLogic.afterDisk(false, false))
        assertEquals(AvatarLoadLogic.Action.FALLBACK, AvatarLoadLogic.afterDisk(false, true))
        // tag 命中 → 由图可用性决定
        assertEquals(AvatarLoadLogic.Action.SET_IMAGE, AvatarLoadLogic.afterDisk(true, true))
        assertEquals(AvatarLoadLogic.Action.FALLBACK, AvatarLoadLogic.afterDisk(true, false))
    }
}
