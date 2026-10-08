package com.lx.wxgroup.data

/**
 * K7 的布局计算：把微信的会话列表和我们的分组行合成一个行序列。
 *
 * ## 为什么单独抽出来（而且不碰任何 Android API）
 *
 * 「插在哪一行、点击位置怎么换算回去」这段逻辑写错了的表现是**点开错人**，
 * 而 hook 代码只能上真机才能验证。抽成纯 Kotlin 之后可以直接跑 JVM 单测，
 * 不用连手机也能确认算法是对的 —— 跟 `BackupCodec` 同样的套路。
 *
 * ## 三条硬规则
 *
 * 1. **绝不重排会话**。微信自己的顺序（置顶会话、免打扰、时间）很复杂，
 *    我们只往里**插入**分组行，会话之间的相对顺序必须跟微信原样一致。
 * 2. **分组行的位置 = 该组成员中最新一条会话的位置**（会话是时间降序，
 *    所以就是下标最小的那条），插在它前面。
 * 3. **没有任何会话的分组行排到末尾**，按 sort_order —— 这样它不会在时间轴上
 *    乱窜，但用户仍然能点进去看成员（没聊过天的人在二级页里照样显示）。
 *
 * 置顶（pinned）的分组行在最前面，同样按 sort_order。
 */
object ConvLayout {

    /** 一条会话。`timeMs` 只用于算分组行的先后，不参与会话排序（见规则 1） */
    data class ConvEntry(val wxid: String, val timeMs: Long = 0L)

    /** 分组行的显示信息 */
    data class GroupRowSpec(
        val id: Long,
        val name: String,
        val pinned: Boolean = false,
        val sortOrder: Int = 0,
    )

    /** 合成后的行 */
    sealed class ConvRow {
        /** 会话行：`index` 是它在**原始**会话列表里的下标 */
        data class Conv(val index: Int) : ConvRow()

        /** 分组行 */
        data class Group(val groupId: Long) : ConvRow()
    }

    /**
     * @param convs 微信原始会话列表，**必须保持微信自己的顺序**
     * @param groups 全部分组（已按 sort_order 排好）
     * @param members 分组 id -> 成员 wxid 集合（数据层保证一人只属于一组）
     * @param enabled false 时原样返回（模块开关关掉 = 零改动）
     */
    fun build(
        convs: List<ConvEntry>,
        groups: List<GroupRowSpec>,
        members: Map<Long, Set<String>>,
        enabled: Boolean = true,
    ): Layout {
        if (!enabled || groups.isEmpty()) return Layout(passthrough(convs))

        val pinned = groups.filter { it.pinned }.sortedBy { it.sortOrder }
        val normal = groups.filter { !it.pinned }.sortedBy { it.sortOrder }

        // 分组行插在「该组最新一条会话」之前；没有会话的组放到末尾
        val anchorOf = HashMap<Long, Int>()
        val tail = ArrayList<GroupRowSpec>()
        for (g in normal) {
            val idx = anchorIndex(convs, members[g.id].orEmpty())
            if (idx < 0) tail.add(g) else anchorOf[g.id] = idx
        }

        val rows = ArrayList<ConvRow>(convs.size + groups.size)
        for (g in pinned) rows.add(ConvRow.Group(g.id))
        for (i in convs.indices) {
            for (g in normal) {
                if (anchorOf[g.id] == i) rows.add(ConvRow.Group(g.id))
            }
            rows.add(ConvRow.Conv(i))
        }
        for (g in tail) rows.add(ConvRow.Group(g.id))
        return Layout(rows)
    }

    private fun passthrough(convs: List<ConvEntry>): List<ConvRow> =
        convs.indices.map { ConvRow.Conv(it) }

    /** 该组成员会话里下标最小的那个（会话按时间降序，所以就是最新一条）；没有则 -1 */
    private fun anchorIndex(convs: List<ConvEntry>, wxids: Set<String>): Int {
        if (wxids.isEmpty()) return -1
        var best = -1
        for (i in convs.indices) {
            if (convs[i].wxid in wxids) {
                best = i
                break
            }
        }
        return best
    }

    /**
     * 合成后的行序列 + 位置映射。
     *
     * 点击位置换算**必须**走这里：微信的点击监听 `com.tencent.mm.ui.conversation.o2`
     * 是用位置取会话的，插了分组行之后位置全变了，不换算就是「点第 3 条打开第 5 条」。
     */
    class Layout internal constructor(val rows: List<ConvRow>) {

        val size: Int get() = rows.size

        operator fun get(position: Int): ConvRow = rows[position]

        fun isGroupRow(position: Int): Boolean = rows.getOrNull(position) is ConvRow.Group

        /**
         * 该位置对应的**原始会话下标**；分组行返回 -1（调用方必须拦掉，不能转交微信）。
         */
        fun convIndexOf(position: Int): Int =
            (rows.getOrNull(position) as? ConvRow.Conv)?.index ?: -1

        /** 分组行的 id；不是分组行返回 -1 */
        fun groupIdAt(position: Int): Long =
            (rows.getOrNull(position) as? ConvRow.Group)?.groupId ?: -1L
    }
}
