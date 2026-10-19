package com.lx.wxgroup.feature

import com.lx.wxgroup.feature.SnsCommentLabelLogic.LabelOwner
import com.lx.wxgroup.feature.SnsCommentLabelLogic.ReconcilePlan
import com.lx.wxgroup.feature.SnsCommentLabelLogic.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SnsCommentLabelLogic] 的归属判定回归测试。
 *
 * 这几条判据挡的是 `_cache/a1_onbind_review.md` 里的 5 条 P0：
 * 标签插在会被 RecyclerView 复用的 itemView 子树里，一旦判据放宽，
 * 就会出现「上一条动态的标签留在这一条上」「两条评论互相判死、标签闪一下就没了」
 * 「位置被锁死、既不该摘也不补」。
 *
 * 纯 JVM 测试：被测 object 零 Android 依赖，不走 Robolectric。
 */
class SnsCommentLabelLogicTest {

    private fun card(snsId: Long, force: Boolean = false) =
        LabelOwner(snsId = snsId, scope = Scope.CARD, svrId = null, isForce = force)

    private fun row(snsId: Long, svrId: Long? = null, force: Boolean = false) =
        LabelOwner(snsId = snsId, scope = Scope.ROW, svrId = svrId, isForce = force)

    private fun reconcile(owners: List<LabelOwner?>, desired: List<LabelOwner>): ReconcilePlan =
        SnsCommentLabelLogic.reconcile(owners, desired)

    // ----------------------------------------------------- A. shouldRemove 6 条判据

    @Test
    fun shouldRemoveNullOwner() {
        // P0：没有归属信息的老标签无法证明属于当前动态，必须摘掉重打
        assertTrue(SnsCommentLabelLogic.shouldRemove(null, row(1L, 5L)))
        assertTrue(SnsCommentLabelLogic.shouldRemove(null, card(1L)))
    }

    @Test
    fun shouldRemoveDifferentSnsId() {
        // P0 #1（复用误标）：snsId 不同是复用后唯一无歧义的「不是我的」证据
        assertTrue(SnsCommentLabelLogic.shouldRemove(row(200L, 5L), row(100L, 5L)))
    }

    @Test
    fun shouldRemoveDifferentIsForce() {
        // P0：探针强制标签与正常判据标签不混用，isForce 不同即摘
        assertTrue(SnsCommentLabelLogic.shouldRemove(row(100L, 5L, force = false), row(100L, 5L, force = true)))
        assertTrue(SnsCommentLabelLogic.shouldRemove(card(100L, force = true), card(100L, force = false)))
    }

    @Test
    fun shouldRemoveDifferentScope() {
        // P0 #3：CARD 挂 itemView 根、ROW 挂评论行，层级不同、互相看不见，双向都要摘
        assertTrue(SnsCommentLabelLogic.shouldRemove(card(100L), row(100L, null)))
        assertTrue(SnsCommentLabelLogic.shouldRemove(row(100L, null), card(100L)))
    }

    @Test
    fun shouldKeepSameCardLabel() {
        // P0：同为 CARD、同 snsId 即同一个标签（CARD 的 svrId 恒 null），留着不重复打
        assertFalse(SnsCommentLabelLogic.shouldRemove(card(100L), card(100L)))
    }

    @Test
    fun shouldRemoveRowBranchMatrix() {
        val currentKnown = row(100L, 11L)

        // 双方 svrId 都未知：就是本次该留的降级标签
        assertFalse(SnsCommentLabelLogic.shouldRemove(row(100L, null), row(100L, null)))
        // 同一个已知评论：幂等
        assertFalse(SnsCommentLabelLogic.shouldRemove(row(100L, 11L), currentKnown))
        // P0 #2：旧的精确标签可能已随 itemView 复用错位，本次只拿到模糊标签时摘掉
        assertTrue(SnsCommentLabelLogic.shouldRemove(row(100L, 11L), row(100L, null)))
        // 旧的模糊标签无法证明不是这一条，本次是精确标签时摘掉，避免一评论两标
        assertTrue(SnsCommentLabelLogic.shouldRemove(row(100L, null), currentKnown))
        // 同一条动态的两条不同已删评论：各自都该留（一条动态可有多条已删评论）
        assertFalse(SnsCommentLabelLogic.shouldRemove(row(100L, 11L), row(100L, 12L)))
    }

    // ------------------------------------------------------------ B. needApply 4 条

    @Test
    fun needApplyFalseWhenIdentical() {
        // P0：完全相同的 (snsId, scope, svrId, isForce) 已存在 → 不重复打
        assertFalse(SnsCommentLabelLogic.needApply(listOf(row(100L, 11L)), row(100L, 11L)))
        assertFalse(SnsCommentLabelLogic.needApply(listOf(card(100L)), card(100L)))
    }

    @Test
    fun needApplyFalseWhenCardCoversRow() {
        // P0 #3：整卡级已经盖住整条动态，行级不再重复加（needApply 自身的覆盖面判据）
        assertFalse(SnsCommentLabelLogic.needApply(listOf(card(100L)), row(100L, 7L)))
        assertFalse(SnsCommentLabelLogic.needApply(listOf(card(100L)), row(100L, null)))
    }

    @Test
    fun needApplyTrueWhenSvrIdDiffers() {
        // P0：同动态、不同已删评论 → 各是各的标签，必须补
        assertTrue(SnsCommentLabelLogic.needApply(listOf(row(100L, 11L)), row(100L, 12L)))
        assertTrue(SnsCommentLabelLogic.needApply(listOf(row(100L, null)), row(100L, 12L)))
    }

    @Test
    fun needApplyTrueWhenExistingEmpty() {
        // P0：摘完之后没有任何幸存标签 → 必须补
        assertTrue(SnsCommentLabelLogic.needApply(emptyList(), row(100L, 11L)))
        assertTrue(SnsCommentLabelLogic.needApply(emptyList(), card(100L)))
    }

    // ------------------------------------------------------------ C. reconcile 决策

    @Test
    fun reconcileKeepsAllWhenDesiredHasMultiple() {
        // P0 #1 回归：desired 有 2 条时不能用 all 判，否则两条标签互相判死、全被摘掉
        val owners = listOf(row(100L, 11L), row(100L, 12L))
        val desired = listOf(row(100L, 11L), row(100L, 12L))
        val plan = reconcile(owners, desired)

        assertEquals(emptyList<Int>(), plan.removeIdx)
        assertEquals(emptyList<LabelOwner>(), plan.applyOwners)
    }

    @Test
    fun reconcileRemovesAllWhenDesiredEmpty() {
        // 反向对照核心：这条动态没有已删评论 → 旧标签全部摘掉，且不再补
        val plan = reconcile(listOf(row(100L, 11L), row(100L, 12L)), emptyList())

        assertEquals(listOf(0, 1), plan.removeIdx)
        assertEquals(emptyList<LabelOwner>(), plan.applyOwners)
        assertEquals("reuse-other", plan.reason)
    }

    @Test
    fun reconcileRemovesReusedLabelFromOtherSns() {
        // P0 #1（复用误标）：itemView 复用后留着上一条动态的标签 → 必须摘并补新的
        val plan = reconcile(listOf(row(100L, 11L)), listOf(row(200L, 33L)))

        assertEquals(listOf(0), plan.removeIdx)
        assertEquals(1, plan.applyOwners.size)
        assertEquals(200L, plan.applyOwners[0].snsId)
        assertEquals("fresh", plan.reason)
    }

    @Test
    fun reconcileKeepsCoexistingCommentsOfSameSns() {
        // P0 #2（位置锁死修正）：同一条动态的两条不同已删评论共存，是预期行为。
        // 旧标签 svrId=11 与 current svrId=12 同 snsId、都非 null 且不同 →
        // shouldRemove 返回 false（该留），因此 removeIdx 为空、只补新的那条。
        val plan = reconcile(listOf(row(100L, 11L)), listOf(row(100L, 12L)))

        assertEquals(emptyList<Int>(), plan.removeIdx)
        assertEquals(1, plan.applyOwners.size)
        assertEquals(12L, plan.applyOwners[0].svrId)
        assertEquals("fresh", plan.reason)
    }

    @Test
    fun reconcileIsIdempotent() {
        // P0：完全一致时既不摘也不补，避免每次 bind 都重建标签导致闪烁
        val plan = reconcile(listOf(row(100L, 11L)), listOf(row(100L, 11L)))

        assertEquals(emptyList<Int>(), plan.removeIdx)
        assertEquals(emptyList<LabelOwner>(), plan.applyOwners)
        assertEquals("same", plan.reason)
    }

    @Test
    fun reconcileSwapsCardAndRowOnce() {
        // P0 #3：CARD 与 ROW 层级不同、互相看不见 → 同一时刻只能留一层
        // ① 空树 + 想要整卡级 → 直接补一条 CARD
        val add = reconcile(emptyList(), listOf(card(1L)))
        assertEquals(emptyList<Int>(), add.removeIdx)
        assertEquals(1, add.applyOwners.size)
        assertEquals(Scope.CARD, add.applyOwners[0].scope)

        // ② 树里已有 CARD、本次只要 ROW：按 shouldRemove 的 scope 判据（第 4 条），
        //    旧 CARD 会被判该摘；摘完 survivors 为空，需要的 ROW 必须补上 ——
        //    结果是把整卡级换成了行级，而不是「CARD 盖住 ROW、什么都不做」。
        //    「CARD 盖 ROW」的短路只存在于 needApply（见下一个断言与 B 组），
        //    reconcile 里不会因为层级高低而跳过替换 —— 此处锁的就是这个真实语义。
        val swap = reconcile(listOf(card(1L)), listOf(row(1L, 7L)))
        assertEquals(listOf(0), swap.removeIdx)
        assertEquals(1, swap.applyOwners.size)
        assertEquals(Scope.ROW, swap.applyOwners[0].scope)

        // ③ CARD 存活时才会真的「盖住」行级
        assertFalse(SnsCommentLabelLogic.needApply(listOf(card(1L)), row(1L, 7L)))
    }

    @Test
    fun reconcileRemoveIdxIsAscending() {
        // 调用方按降序 removeView 才安全，所以逻辑层必须给出升序下标
        val plan = reconcile(
            listOf(row(100L, 1L), row(200L, 2L), null),
            emptyList(),
        )

        assertEquals(listOf(0, 1, 2), plan.removeIdx)
    }

    // -------------------------------------------------------------- D. reason 取值

    @Test
    fun reasonIsSame() {
        // 日志判读：无摘无补 → same
        assertEquals("same", reconcile(listOf(card(100L)), listOf(card(100L))).reason)
    }

    @Test
    fun reasonIsFresh() {
        // 日志判读：有新增 → fresh
        assertEquals("fresh", reconcile(emptyList(), listOf(row(100L, 11L))).reason)
    }

    @Test
    fun reasonIsReuseOther() {
        // 日志判读：只摘不补 → reuse-other（itemView 被别的动态用过）
        assertEquals("reuse-other", reconcile(listOf(row(100L, 11L)), emptyList()).reason)
    }

    @Test
    fun reasonIsForceDup() {
        // 日志判读：无摘无补且 desired 全是探针强制标签 → force-dup
        val plan = reconcile(listOf(card(100L, force = true)), listOf(card(100L, force = true)))
        assertEquals(emptyList<Int>(), plan.removeIdx)
        assertEquals(emptyList<LabelOwner>(), plan.applyOwners)
        assertEquals("force-dup", plan.reason)
    }

    // ------------------------------------------- G. 渲染键（renderKey）判据
    //
    // 背景：真机实测 UI 层**不携带 commentSvrID**（`q54/q95` 的 q/r 两个 long 恒 0），
    // 所以渲染侧只能用 `(createTime, talker, type)` 认评论。
    // 这一组锁的是「用渲染键代替 svrId 后，原有 5 条 P0 判据一条都不能松」。

    private fun rrow(snsId: Long, key: String?, force: Boolean = false) =
        LabelOwner(snsId = snsId, scope = Scope.ROW, svrId = null, isForce = force, renderKey = key)

    @Test
    fun renderKeySameIsKept() {
        // P0：同一个渲染键 = 同一帧重复 bind → 必须留着，否则每帧摘了再打会闪动
        val plan = reconcile(
            listOf(rrow(100L, "1792:a:2")),
            listOf(rrow(100L, "1792:a:2")),
        )
        assertEquals(emptyList<Int>(), plan.removeIdx)
        assertEquals(emptyList<LabelOwner>(), plan.applyOwners)
    }

    @Test
    fun renderKeyDifferentIsKeptToo() {
        // 一条动态可以有多条已删评论 → 两个不同的渲染键各自保留，不互相摘
        val plan = reconcile(
            listOf(rrow(100L, "1792:a:2")),
            listOf(rrow(100L, "1793:b:1")),
        )
        assertEquals(emptyList<Int>(), plan.removeIdx)
        assertEquals(1, plan.applyOwners.size)
    }

    @Test
    fun renderKeyUnknownRemovesKnown() {
        // 本次是模糊标签（不知道具体哪条）→ 旧的精确标签可能已错位，必须摘
        val plan = reconcile(
            listOf(rrow(100L, "1792:a:2")),
            listOf(rrow(100L, null)),
        )
        assertEquals(listOf(0), plan.removeIdx)
    }

    @Test
    fun renderKeyKnownRemovesUnknown() {
        // 本次是精确标签、旧的模糊 → 模糊标签无法证明不是这一条，摘掉避免一评论两标
        val plan = reconcile(
            listOf(rrow(100L, null)),
            listOf(rrow(100L, "1792:a:2")),
        )
        assertEquals(listOf(0), plan.removeIdx)
    }

    @Test
    fun renderKeyDoesNotRescueOtherPost() {
        // snsId 不同 = 复用 → 渲染键再像也必须摘（snsId 是唯一无歧义证据）
        val plan = reconcile(
            listOf(rrow(100L, "1792:a:2")),
            listOf(rrow(200L, "1792:a:2")),
        )
        assertEquals(listOf(0), plan.removeIdx)
    }

    @Test
    fun renderKeyBeatsSvrIdWhenBothPresent() {
        // renderKey 优先：svrId 相同但渲染键不同 → 判为不同评论（渲染侧只信渲染键）
        val old = LabelOwner(100L, Scope.ROW, svrId = 11L, renderKey = "1792:a:2")
        val cur = LabelOwner(100L, Scope.ROW, svrId = 11L, renderKey = "1793:b:1")
        val plan = reconcile(listOf(old), listOf(cur))
        assertEquals(emptyList<Int>(), plan.removeIdx)
        assertEquals(1, plan.applyOwners.size)
        assertEquals("1793:b:1", plan.applyOwners[0].renderKey)
    }

    @Test
    fun renderKeyIdempotentWithinOneBind() {
        // 幂等：同一次 bind 里两个期望集元素完全相同 → 只打一次
        val plan = reconcile(
            listOf(rrow(100L, "1792:a:2")),
            listOf(rrow(100L, "1792:a:2"), rrow(100L, "1792:a:2")),
        )
        assertEquals(emptyList<LabelOwner>(), plan.applyOwners)
    }

    @Test
    fun renderKeyMixedWithSvrIdAreDistinct() {
        // 老 owner 只有 svrId、新 owner 只有 renderKey → 不同标识符前缀，判为不同（宁可重打）
        val old = LabelOwner(100L, Scope.ROW, svrId = 11L, renderKey = null)
        val cur = LabelOwner(100L, Scope.ROW, svrId = null, renderKey = "1792:a:2")
        val plan = reconcile(listOf(old), listOf(cur))
        assertEquals(emptyList<Int>(), plan.removeIdx)
        assertEquals(1, plan.applyOwners.size)
    }
}
