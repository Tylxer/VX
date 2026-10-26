package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PickerTabLogic] 的判据回归测试（F1 修复）。
 *
 * 两条判据一旦改错，就要重上一轮真机（全表扫 ≈2.4s + 大量排查），所以先锁在这里：
 * ① 要不要补跑群聊全表扫；② 补跑完页面刷新时落在哪个 Tab。
 */
class PickerTabLogicTest {

    // ---------------------------------------------------------------- 是否补跑

    /** 缓存为 0（首次 / 从没补成功过）→ 无论时间戳如何都补跑 */
    @Test
    fun cachedZeroAlwaysRuns() {
        assertTrue(PickerTabLogic.needsFullSync(0, lastSyncMs = 0L, nowMs = 0L, intervalMs = 1L))
        assertTrue(PickerTabLogic.needsFullSync(0, lastSyncMs = Long.MAX_VALUE, nowMs = 0L, intervalMs = 1L))
    }

    /** 有缓存但从未做过全量同步（lastSync=0）→ 视为过期，补跑 */
    @Test
    fun neverFullSyncedRuns() {
        assertTrue(
            PickerTabLogic.needsFullSync(
                cachedCount = 3,
                lastSyncMs = 0L,
                nowMs = 1_700_000_000_000L,
                intervalMs = 6 * 60 * 60 * 1000L,
            )
        )
    }

    /** 有缓存且新鲜 → 跳过 */
    @Test
    fun freshCacheSkips() {
        assertFalse(
            PickerTabLogic.needsFullSync(
                cachedCount = 3,
                lastSyncMs = 1_000L,
                nowMs = 2_000L,
                intervalMs = 6 * 60 * 60 * 1000L,
            )
        )
    }

    /** 有缓存且过期 → 补跑 */
    @Test
    fun staleCacheRuns() {
        assertTrue(
            PickerTabLogic.needsFullSync(
                cachedCount = 3,
                lastSyncMs = 1_000L,
                nowMs = 1_000L + 6 * 60 * 60 * 1000L + 1L,
                intervalMs = 6 * 60 * 60 * 1000L,
            )
        )
    }

    /** 边界：恰好等于 interval 时不跑（用 `>` 不用 `>=`，避免临界抖动反复扫） */
    @Test
    fun exactlyAtIntervalSkips() {
        assertFalse(
            PickerTabLogic.needsFullSync(
                cachedCount = 3,
                lastSyncMs = 1_000L,
                nowMs = 1_000L + 6 * 60 * 60 * 1000L,
                intervalMs = 6 * 60 * 60 * 1000L,
            )
        )
    }

    // ------------------------------------------------------------ 刷新时落点

    private val BEFORE = listOf("好友", "我的分组", "微信标签")
    private val AFTER = listOf("好友", "群聊", "我的分组", "微信标签")

    /** 新旧名称变了 → 需要刷新，且返回新的当前 Tab 下标 */
    @Test
    fun planReturnsIndexWhenChanged() {
        assertEquals(0, PickerTabLogic.planTabRefresh(BEFORE, AFTER, "好友"))
        assertEquals(2, PickerTabLogic.planTabRefresh(BEFORE, AFTER, "我的分组"))
        assertEquals(3, PickerTabLogic.planTabRefresh(BEFORE, AFTER, "微信标签"))
    }

    /** 新旧名称完全一致 → 返回 null（调用方跳过刷新，避免无谓重建/日志） */
    @Test
    fun planReturnsNullWhenUnchanged() {
        assertNull(PickerTabLogic.planTabRefresh(AFTER, AFTER, "好友"))
    }

    /** 当前 Tab 名仍存在 → 保留在原 Tab（群聊插到第 1 位，不改变其它 Tab 的落点） */
    @Test
    fun currentTabKeptByName() {
        assertEquals(1, PickerTabLogic.resolveCurrentIndex("群聊", AFTER))
        assertEquals(0, PickerTabLogic.resolveCurrentIndex("好友", AFTER))
        assertEquals(3, PickerTabLogic.resolveCurrentIndex("微信标签", AFTER))
    }

    /** 当前 Tab 名已失效 → 回第一个（下标 0） */
    @Test
    fun missingCurrentFallsBackToFirst() {
        assertEquals(0, PickerTabLogic.resolveCurrentIndex("不存在的Tab", AFTER))
        assertEquals(0, PickerTabLogic.resolveCurrentIndex(null, AFTER))
    }

    /** 新列表为空 → 回 0（调用方另有 new.isEmpty 防御，这里只保证不越界语义） */
    @Test
    fun emptyNewListFallsBackToZero() {
        assertEquals(0, PickerTabLogic.resolveCurrentIndex("好友", emptyList()))
    }
}
