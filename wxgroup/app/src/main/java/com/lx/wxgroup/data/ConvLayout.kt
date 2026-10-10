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
 * ## 四条硬规则
 *
 * 1. **绝不重排会话**。微信自己的顺序（置顶会话、免打扰、时间）很复杂，
 *    我们只做两件事：把已归组的会话**抽掉**、在原位**补上**分组行。
 *    剩下的会话之间的相对顺序必须跟微信原样一致。
 * 2. **A6 收纳语义：已归组的会话从主列表消失**，由分组行代表它出现。
 *    分组整组只占**一行**，不会因为组里有 20 个人就占 20 行。
 * 3. **A2 分组行的位置 = 该组成员中最新一条会话的位置**（会话是时间降序，
 *    所以就是下标最小的那条）。分组行**取代**那条会话，而不是插在它前面 ——
 *    这样列表长度才不会膨胀，时间轴也才对得上。
 * 4. **没有任何会话的分组行排到末尾**，按 sort_order —— 这样它不会在时间轴上
 *    乱窜，但用户仍然能点进去看成员（没聊过天的人在二级页里照样显示）。
 *
 * **A13 置顶**：pinned 的分组行排在所有行最前面，按 sort_order。
 * 微信原生置顶区块的判定字段还没定位到（见 `docs` 的 S1 遗留项），
 * 所以暂时退化为「列表最顶部」，行为上等价且不会错。
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

        // 空白 wxid 不是合法成员：不过滤的话它会把一条空会话认成某组的成员，
        // 归属表和锚点全被污染（实测踩到）。
        val clean = HashMap<Long, Set<String>>()
        for ((gid, wxids) in members) {
            val s = HashSet<String>()
            for (w in wxids) if (w.isNotBlank()) s.add(w)
            clean[gid] = s
        }

        val pinned = groups.filter { it.pinned }.sortedBy { it.sortOrder }
        val normal = groups.filter { !it.pinned }.sortedBy { it.sortOrder }

        // wxid -> 所属分组。
        // 按分组顺序（而不是 HashMap 的迭代顺序）构建，保证结果**确定**：
        // 数据层保证一人一组；万一脏数据一人多组，sortOrder 靠前的组优先，
        // 且每条会话只会产生一个归属 —— 绝不出现两个组抢同一条会话。
        val ownerOf = HashMap<String, Long>()
        for (g in pinned + normal) {
            for (w in clean[g.id].orEmpty()) ownerOf.putIfAbsent(w, g.id)
        }

        // 分组行占据「该组最新一条会话」的位置；位置被别的组占走就顺延到下一条。
        //
        // 为什么要「顺延」而不是直接放弃：分组行是用户进入该分组的**唯一入口**，
        // 少插一行 = 那个分组在会话列表里彻底消失（用户只能去设置页找）。
        // 脏数据里两人同组时，后一个组必须退到它自己下一条成员会话上，不能消失。
        val anchorOf = HashMap<Long, Int>()
        val tail = ArrayList<GroupRowSpec>()
        val taken = HashSet<Int>()
        for (g in normal) {
            var idx = -1
            for (i in convs.indices) {
                if (i in taken) continue
                if (ownerOf[convs[i].wxid] != g.id) continue
                idx = i
                break
            }
            if (idx < 0) tail.add(g) else {
                anchorOf[g.id] = idx
                taken.add(idx)
            }
        }

        val rows = ArrayList<ConvRow>(convs.size + groups.size)

        // A13：置顶的分组行在最前（pinned 组的成员会话在下面统一被隐藏）
        for (g in pinned) rows.add(ConvRow.Group(g.id))

        for (i in convs.indices) {
            val owner = ownerOf[convs[i].wxid]
            when {
                // 没归组：原样保留（规则 1）
                owner == null -> rows.add(ConvRow.Conv(i))

                // 该组最新一条会话：用分组行取代它（规则 2 + 3）
                anchorOf[owner] == i -> rows.add(ConvRow.Group(owner))

                // 同组的其余会话：收纳隐藏（A6）
                else -> Unit
            }
        }

        // 规则 4：整组没会话的分组行垫在最后，按 sort_order
        for (g in tail) rows.add(ConvRow.Group(g.id))

        return Layout(rows)
    }

    /**
     * 筛选态（点进某个分组之后的二级列表）：只显示该组成员会话。
     *
     * **没有「返回全部」行** —— 退出筛选走系统返回键（见 [com.lx.wxgroup.feature.ConvFilter]
     * 的返回拦截），跟微信自己的返回行为一致，不再往列表里塞导航行。
     *
     * **空分组必须过滤成 0 条**，一行都不留 ——
     * 不能退化成「全部显示」，否则用户会以为筛选根本没生效（旧项目实机踩过）。
     * 空分组时界面是一片空白，出口是返回键。
     */
    fun filtered(convs: List<ConvEntry>, members: Set<String>): Layout {
        val rows = ArrayList<ConvRow>(convs.size)
        for (i in convs.indices) {
            val w = convs[i].wxid
            if (w.isNotBlank() && w in members) rows.add(ConvRow.Conv(i))
        }
        return Layout(rows)
    }

    private fun passthrough(convs: List<ConvEntry>): List<ConvRow> =
        convs.indices.map { ConvRow.Conv(it) }

    /**
     * 合成后的行序列 + 位置映射。
     *
     * 点击位置换算**必须**走这里：微信的点击监听 `com.tencent.mm.ui.conversation.o2`
     * 是用位置取会话的，收纳之后位置全变了，不换算就是「点第 3 条打开第 5 条」。
     */
    class Layout internal constructor(val rows: List<ConvRow>) {

        val size: Int get() = rows.size

        operator fun get(position: Int): ConvRow? = rows.getOrNull(position)

        fun isGroupRow(position: Int): Boolean = rows.getOrNull(position) is ConvRow.Group

        /**
         * 该位置对应的**原始会话下标**；分组行返回 -1（调用方必须拦掉，不能转交微信）。
         */
        fun convIndexOf(position: Int): Int =
            (rows.getOrNull(position) as? ConvRow.Conv)?.index ?: -1

        /**
         * 分组行的 id；不是分组行返回 [NOT_A_GROUP]。
         *
         * **不能拿 -1 当哨兵**：分组 id 本身可能就是 -1（历史脏数据），
         * 而且旧实现用 `>= 0` 判断，把负 id 的行误判成会话行，
         * 结果分组行永远画不出来，筛到空分组时用户被卡死在空白列表上。
         */
        fun groupIdAt(position: Int): Long =
            (rows.getOrNull(position) as? ConvRow.Group)?.groupId ?: NOT_A_GROUP

        /** 该位置是不是分组行（避免调用方自己写 `groupIdAt(x) >= 0` 这种判断） */
        fun isGroup(position: Int): Boolean = isGroupRow(position)

        companion object {
            const val NOT_A_GROUP = Long.MIN_VALUE
        }
    }
}
