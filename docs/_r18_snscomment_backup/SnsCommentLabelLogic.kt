package com.lx.wxgroup.feature

/**
 * 朋友圈评论 `[已删除]` 标签的**归属判定**纯逻辑（零 Android 依赖 → 可直接跑 JVM 单测）。
 *
 * 存在理由：`SnsCommentTag` 的标签是插进 itemView 子树的普通 View，而 itemView 会被
 * RecyclerView 复用。只靠「这个容器里有没有我的标签」判断幂等，复用后必然把
 * 上一条动态的标签当成这一条的（见 `_cache/a1_onbind_review.md` 的 5 条 P0）。
 * 所以判断必须回答「这个标签属于谁、还该不该留」，而不是「有没有标签」。
 *
 * ## 为什么必须区分 [Scope] 和 svrId 未知
 *
 * `onBind` 有两条打标路径，打出来的标签**层级不同、可辨识度也不同**：
 *
 * - **CARD**：`forceSet` 探针分支把标签直接加在 `itemView` 根上（整卡级）。视觉上等于
 *   "整条动态被删"，层级最高，任何行级标签都会被它盖住。仅探针走。
 * - **ROW**：评论行级。又分两种：
 *   - `svrId != null` —— 精确路径，能对上 `commentSvrID`；
 *   - `svrId == null` —— 降级路径（靠归档正文 ↔ 界面文案对账命中），**拿不到 svrId**。
 *
 * ⚠️ **8.0.48 上生产只走 ROW + svrId==null**（降级路径）：T3 已修正为
 * `SnsCommentCollapseLayout`，它没有承载评论 proto 的字段，精确路径尚未接通
 * （详见 `SnsCommentTag` 文件头「复核」段与 `docs/取证结论.md` ⑦）。
 * 但两条路径的摘/留规则不同，精确路径**保留** —— 将来改挂绑定方法接通后直接复用。
 *
 * 三种标签的摘/留规则**互不相同**，混在一起判就会出错：
 * - CARD 与 ROW 层级不同、互相看不见 → 必须摘掉换成同一 scope 的；
 * - 旧的模糊 ROW 被新的精确 ROW 取代（否则一条评论叠两个标签）；
 * - 旧的精确 ROW 遇到新的模糊 ROW 也要摘（旧的可能已随 itemView 复用而错位）。
 *
 * ⚠️ 本文件**禁止 import 任何 android.* / androidx.* **，否则单测加载不了。
 */
object SnsCommentLabelLogic {

    /** 标签挂在哪一层。两者层级不同、互相不可见。 */
    enum class Scope {
        /** 整卡级：挂在 `itemView` 根上（仅 forceSet 探针分支） */
        CARD,

        /** 评论行级：挂在评论行容器 / `tv.parent` 上 */
        ROW,
    }

    /**
     * 一个标签的归属信息。
     *
     * @param snsId   这条标签是给哪条动态打的 —— 复用后唯一可靠的区分维度
     * @param scope   挂在哪一层；CARD 的 [svrId] / [renderKey] 恒为 null
     * @param svrId   具体到哪条评论（**DB 主键维度**）；仅 [Scope.ROW] 有效，
     *                null = 行级但具体评论未知
     * @param renderKey 具体到哪条评论（**渲染维度**，形如 `"createTime:talker:type"`）。
     *                真机实测 UI 层不携带 commentSvrID（见 `docs/取证结论.md` ⑩ 段），
     *                所以渲染侧只能拿这个键来认「是不是同一条评论」。
     *                **非 null 时优先于 [svrId] 参与判重**；两个都为 null = 模糊行级标签。
     * @param isForce true = 探针强制标签，与正常判据打出来的标签不混用
     */
    data class LabelOwner(
        val snsId: Long,
        val scope: Scope,
        val svrId: Long? = null,
        val isForce: Boolean = false,
        val renderKey: String? = null,
    )

    /**
     * 把「这条标签指向哪条评论」归一成一个可比较的标识。
     *
     * ⚠️ **`renderKey` 优先**：渲染侧真机只能拿到 `(createTime,talker,type)`，
     * 拿不到 `commentSvrID`。若一个 owner 两者都有（理论上不会），以渲染键为准 ——
     * 因为标签是在渲染侧打的，判重也必须用渲染侧能看见的维度，否则每次 bind 都会
     * 「认不出自己上一帧打的标签」→ 摘了再打 → 每帧闪动。
     *
     * @return null = 模糊行级标签（不知道该指向哪条具体评论）
     */
    private fun commentIdent(o: LabelOwner): String? {
        o.renderKey?.let { return "r:$it" }
        o.svrId?.let { return "s:$it" }
        return null
    }

    /**
     * 一个已存在的标签该不该摘掉。
     *
     * 判定顺序是有意的：先认「是不是同一条动态」，再认「是不是同一种标签」，
     * 最后才认具体评论 —— 因为复用后 snsId 不同是**唯一无歧义**的「不是我的」证据，
     * 而 svrId 缺失（老标签没记归属 / 降级路径打出来的）必须当成不可信处理，宁可摘掉重打。
     *
     * @param owner   标签上记下的归属；null = 没有归属信息的老标签
     * @param current 本次 bind 想打的标签（之一）
     */
    fun shouldRemove(owner: LabelOwner?, current: LabelOwner): Boolean {
        if (owner == null) return true                    // 无法证明属于当前动态
        if (owner.snsId != current.snsId) return true     // 别的动态的，复用后必须摘
        if (owner.isForce != current.isForce) return true // 探针标签与正常标签不混用
        // 层级不同：CARD 挂在 itemView 根、ROW 挂在评论行，互相看不见也不互相取代，
        // 留着就是「整卡一个标 + 某行再来一个」，必须摘掉换成同一 scope。
        if (owner.scope != current.scope) return true
        // CARD：svrId/renderKey 恒 null，同 snsId 同 isForce 就是同一个标签
        if (current.scope == Scope.CARD) return false
        // 以下均为 ROW
        val a = commentIdent(owner)
        val b = commentIdent(current)
        if (a == b) return false                          // 含双方都 null：就是本次该留的
        // 本次是模糊标签（不知道具体哪条）：旧的精确标签可能已随复用错位，摘掉
        if (b == null) return true
        // 本次是精确标签、旧的模糊：模糊标签无法证明不是这一条，摘掉避免一评论两标
        if (a == null) return true
        // 同一条动态、两条不同的已知评论 —— 各自都该留着（一条动态可有多条已删评论）
        return false
    }

    /**
     * 摘完之后还要不要新建一个标签。
     *
     * @param existing **当前 itemView 子树里已存在、且**摘完之后**仍保留的标签**
     *                 （调用方先剔除 `removeIdx` 指向的元素，否则会出现
     *                 「既该摘、又算已存在」→ 既不摘也不补 的死锁）
     * @param current  本次想打的标签
     */
    fun needApply(existing: List<LabelOwner>, current: LabelOwner): Boolean {
        for (o in existing) {
            if (o.snsId != current.snsId) continue
            if (o.isForce != current.isForce) continue
            if (o.scope == current.scope && commentIdent(o) == commentIdent(current)) return false // 幂等
            // 整卡级已经盖住整条动态，行级不再重复加
            if (o.scope == Scope.CARD && current.scope == Scope.ROW) return false
        }
        return true
    }

    /**
     * 一次 bind 的总决策：先摘哪些、再补哪些。
     *
     * @param removeIdx   需要摘掉的标签在 [owners] 里的下标（**降序 removeView 才安全**）
     * @param applyOwners **摘完之后**仍需新建的标签，0~N 个（一次 bind 可能给多条评论打标）
     * @param reason      只进日志：`same` / `force-dup` / `reuse-other` / `fresh`
     */
    data class ReconcilePlan(
        val removeIdx: List<Int>,
        val applyOwners: List<LabelOwner>,
        val reason: String,
    )

    /**
     * 把 [shouldRemove] 与 [needApply] 串成一次决策。
     *
     * 幂等判断**必须基于摘剩下的集合**：否则一个本次注定要被摘掉的旧标签
     * 会同时满足「该摘」和「已存在」，结果既不摘也不补 —— 正是审查报告里
     * 「位置被锁死」的那条路径。
     *
     * @param owners  当前 itemView 子树里扫到的全部标签归属（顺序即下标，null 表示老标签）
     * @param desired 本次 bind 想打出来的全部标签（精确路径逐条评论 / 降级路径逐条命中文案，
     *                可能是空表 —— 例如这条动态没有已删评论，此时只摘不补）
     */
    fun reconcile(owners: List<LabelOwner?>, desired: List<LabelOwner>): ReconcilePlan {
        val remove = ArrayList<Int>(owners.size)
        val survivors = ArrayList<LabelOwner>(owners.size)
        for (i in owners.indices) {
            val o = owners[i]
            // ⚠️ 保留条件是「**存在**某条 desired 认为它该留」（any + 非），
            //    不是「**全部** desired 都认为它该留」（all）。
            //    写成 all 的后果是：只要本次想打 2 条评论的标签，任何标签都会因为
            //    对不上另一条而被判该摘 → 界面上标签反复闪一下就没了（曾写错一次）。
            //    desired 为空（这条动态没有已删评论）时 any 恒 false → 全部摘掉，
            //    这正是反向对照要的行为。
            if (desired.any { !shouldRemove(o, it) }) {
                if (o != null) survivors.add(o)
            } else {
                remove.add(i)
            }
        }
        val apply = ArrayList<LabelOwner>(desired.size)
        for (d in desired) {
            if (needApply(survivors, d)) apply.add(d)
        }
        val reason = when {
            apply.isNotEmpty() -> "fresh"
            remove.isNotEmpty() -> "reuse-other"
            desired.isNotEmpty() && desired.all { it.isForce } -> "force-dup"
            else -> "same"
        }
        return ReconcilePlan(remove, apply, reason)
    }
}
