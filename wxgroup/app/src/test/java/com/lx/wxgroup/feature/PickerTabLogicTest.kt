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

/**
 * R46 · [PickerTabLogic.selectedFirst] 的判据回归。
 *
 * 需求：所有选人组件（设置自动导出名单 / 允许·排除名单 / 群发 / 转发目标）里
 * 已选中的人排在前面。这里锁死三件容易改错的事：
 * ① 是**稳定分区**，两组内部相对顺序不能乱（换成 sortedBy* 会打乱）；
 * ② 结果不写回数据源、不缓存 ⇒ 换一组 selected 重算要跟着变（复位边界）；
 * ③ 判定是「id 在 selected 里」，反转即回归。
 *
 * 变异验证逐组结论（真跑单测后的红条数）见 `docs/_r44_chain.md` R46 节与派发台账。
 */
class PickerSelectedFirstTest {

    private data class P(val id: String, val name: String)

    private val base = listOf(
        P("a", "A"), P("b", "B"), P("c", "C"), P("d", "D"), P("e", "E")
    )

    private fun ids(xs: List<P>) = xs.map { it.id }

    private fun order(sel: Set<String>) = ids(PickerTabLogic.selectedFirst(base, sel) { it.id })

    /** 没勾选任何人 ⇒ 整表顺序逐字不变 */
    @Test
    fun noSelectedKeepsOriginalOrder() {
        assertEquals(listOf("a", "b", "c", "d", "e"), order(emptySet()))
    }

    /** 部分已选 ⇒ 已选整体在前，且**两组内部**保持原相对顺序 */
    @Test
    fun pickedFirstAndBothGroupsStable() {
        assertEquals(listOf("b", "d", "a", "c", "e"), order(setOf("b", "d")))
        assertEquals(listOf("c", "e", "a", "b", "d"), order(setOf("e", "c")))
    }

    /** 全选 ⇒ 顺序不变（不能因为「排过序」就重排组内） */
    @Test
    fun allSelectedKeepsOriginalOrder() {
        assertEquals(listOf("a", "b", "c", "d", "e"), order(base.map { it.id }.toSet()))
    }

    /** selected 里含列表不存在的 id ⇒ 不产生幽灵行、不影响顺序 */
    @Test
    fun unknownIdsIgnored() {
        assertEquals(listOf("c", "a", "b", "d", "e"), order(setOf("zzz", "c")))
    }

    /** 重复 id 的条目必须一起稳定保留，不能去重 */
    @Test
    fun duplicateIdsStayStable() {
        val dup = listOf(P("a", "A1"), P("a", "A2"), P("b", "B"))
        val got = PickerTabLogic.selectedFirst(dup, setOf("a")) { it.id }
        assertEquals(listOf("A1", "A2", "B"), got.map { it.name })
    }

    /** 幂等：对结果再跑一次，结果不变（防止每次 render 逐轮漂移） */
    @Test
    fun idempotent() {
        val once = PickerTabLogic.selectedFirst(base, setOf("b", "d")) { it.id }
        val twice = PickerTabLogic.selectedFirst(once, setOf("b", "d")) { it.id }
        assertEquals(ids(once), ids(twice))
    }

    /** ⭐ 复位边界：反选 / 清空 / 换一组已选后，重算必须按**最新** selected 排，不能沿用旧顺序 */
    @Test
    fun resetsWhenSelectionChanges() {
        assertEquals(listOf("b", "d", "a", "c", "e"), order(setOf("b", "d")))
        assertEquals(listOf("a", "b", "c", "d", "e"), order(emptySet()))   // 清空
        assertEquals(listOf("a", "e", "b", "c", "d"), order(setOf("a", "e"))) // 换成另一组
    }

    /** 空列表 ⇒ 空列表，不崩 */
    @Test
    fun emptyListReturnsEmpty() {
        assertTrue(PickerTabLogic.selectedFirst(emptyList<P>(), setOf("a")) { it.id }.isEmpty())
    }

    /**
     * R46 第三入口（BatchPicker 批量添加成员）：预选的是「已是本群成员」的人，
     * idOf 用 wxid，visible 由 filter 重建。
     *
     * ⛔ 用例形状是故意设计的（见 `docs/_r46_review_batchpicker.md` 问题5）：
     * ① **未选项必须排在原列表前面**（d 在 b 前），否则「根本不分区 / 恒返回原序」
     *    也能全绿 —— 初版把已选放成前缀，是等价变异假验证；
     * ② 两组内部的 id 都**非升序**（已选 b,c 夹在未选 d,a 之间；未选组 d→a 降序），
     *    才能抓到「组内按 key 排序」这类破坏稳定分区的实现；
     * ③ 预选集合插入顺序 `c,b` 与列表顺序 `b,c` 相反 ⇒ 抓「按 selected 迭代顺序输出」；
     * ④ 复位断言的期望与首次期望**不同**，否则零区分力。
     */
    @Test
    fun batchPickerPreselectedMembersFirstAfterFilter() {
        val matched = listOf(
            P("wxid_d", "的"),  // 未选，却排在前面
            P("wxid_b", "波"),  // 已选
            P("wxid_a", "阿"),  // 未选
            P("wxid_c", "次")   // 已选
        )
        val preselected = linkedSetOf("wxid_c", "wxid_b") // 插入顺序与列表顺序相反
        val out = PickerTabLogic.selectedFirst(matched, preselected) { it.id }
        // 已选按列表原序 [b,c] 在前，未选按列表原序 [d,a] 在后
        assertEquals(listOf("wxid_b", "wxid_c", "wxid_d", "wxid_a"), ids(out))
        // 反选 / 清空选择后，下一次 filter 必须复位成命中集原序（与上面期望不同）
        assertEquals(
            listOf("wxid_d", "wxid_b", "wxid_a", "wxid_c"),
            ids(PickerTabLogic.selectedFirst(matched, emptySet()) { it.id })
        )
    }
}
