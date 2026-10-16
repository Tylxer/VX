package com.lx.wxgroup.data

/**
 * 备份导入的**冲突合并与版本兼容**（纯逻辑）。
 *
 * ## 为什么单独拆出来
 *
 * 这几条规则是「换手机 / 升级模块 / 拿到别人的旧文件」才走到的分支，
 * 写错了在手机上很难复现，但用户会直接丢数据。所以它们必须能**在没有手机的时候验证** ——
 * 本文件**不碰任何 Android API**（没有 Context / Uri / SQLite / Kv），
 * 只用 [Snapshot] 和几个普通的 Set/Map，配 `app/src/test/.../BackupMergeTest.kt` 直接跑。
 *
 * 真正落库的是 [BackupIO.restore]；本文件产出的是**计划**（[Plan]），
 * 用于① 导入前的 dry-run 预览（adb/UI 都能看）② 单测锁住规则语义。
 * ⚠️ 两边语义必须一致：本文件是规则的**唯一书面定义**，
 *    改规则先改这里和它的单测，再对齐 [BackupIO.restore]。
 *
 * ## 规则（v6.3 / v6.4 定，不要放宽）
 *
 * 1. **成员归属冲突以现有为准**：库里已属于某分组的人，即使文件里把他放在另一个分组，
 *    **保持现在不动** —— 不搬移、不重复计入。只有**当前不属于任何分组**的人才会被加进
 *    文件里指定的分组。备份是「把缺的补回来」，不是「按文件重排」。
 * 2. **配置项只覆盖备份里存在的项**，没写的保持当前值。
 * 3. **置顶只在新建分组时应用**，合并模式不推翻现有置顶。
 * 4. **缺 `schema` → 视为 v0**：只恢复分组与成员，配置一律不动（由 [Snapshot.hasSettings] 判定）。
 * 5. **高于当前版本的备份**：尽力解析、忽略未知字段，**不拒绝**（Gson 本来就不认未声明字段，
 *    这里只需保证已知字段照常读出）。
 * 6. 分组按**名字**匹配，不按 id —— id 是自增主键，换机后不可能对上；
 *    分组名有 UNIQUE 约束，是天然的业务主键。
 */
object BackupMerge {

    /**
     * 导入前库的现状快照。调用方（[com.lx.wxgroup.feature.BackupFlow]）从 [GroupDb] 读出来传进来。
     *
     * @param groupNames 现有全部分组名
     * @param membersOfGroup 分组名 -> 该组现有成员 wxid
     * @param owned **当前已归属于任意分组**的 wxid 全集。成员独占（wxid 是主键），
     *              所以「一个人最多在一个组」；判断归属冲突只看这个集合。
     * @param knownKeys 当前版本已登记的配置 key（即 `Prefs.ALL` 的 key 集合）。
     *                  文件里没登记的 key 一律丢弃 —— 可能是更高版本的新配置，
     *                  写进来只会污染当前库。
     */
    data class CurrentState(
        val groupNames: Set<String> = emptySet(),
        val membersOfGroup: Map<String, Set<String>> = emptyMap(),
        val owned: Set<String> = emptySet(),
        val knownKeys: Set<String> = emptySet(),
    )

    /** 一个分组在本次导入里要做的动作 */
    data class GroupAction(
        val name: String,
        /** true = 库里还没有，要新建；false = 按名字合并进现有分组 */
        val create: Boolean,
        /** 仅在 [create] 为 true 时有意义：新建时是否按文件置顶 */
        val applyPinned: Boolean,
        /** 要新增进这个组的成员（已去重、已排除归属冲突） */
        val add: List<MemberSnap>,
        /** 已在别的分组、按「以现有为准」跳过的人。只用于给用户看，不做任何写入 */
        val skipped: List<String>,
        /** 已经就在这个组里的人。不写、不计入新增 */
        val already: Int,
    )

    /** 导入计划。调用方照它执行即可，本对象不产生任何副作用 */
    data class Plan(
        val schema: Int,
        val replace: Boolean,
        val groups: List<GroupAction>,
        /** 要写入的配置项（已按 [CurrentState.knownKeys] 过滤、已按 v0 规则清空） */
        val settings: Map<String, String>,
        /** 文件里有、但被 key 白名单挡掉的配置项数（诊断用） */
        val settingsDropped: Int,
    ) {
        val groupsCreated: Int get() = groups.count { it.create }
        val membersAdded: Int get() = groups.sumOf { it.add.size }
        /** 归属冲突被跳过的人数（v6.4：「以现有为准」） */
        val membersSkipped: Int get() = groups.sumOf { it.skipped.size }
        val membersAlready: Int get() = groups.sumOf { it.already }

        /** 给用户看的一行摘要 */
        fun summary(): String =
            "schema=$schema replace=$replace 新建组=$groupsCreated 加人=$membersAdded " +
                "冲突跳过=$membersSkipped 已存在=$membersAlready 配置=${
                    settings.size
                }(丢弃$settingsDropped)"
    }

    /**
     * 生成导入计划。
     *
     * @param replace false = 合并（默认）；true = 覆盖 —— 先删光所有分组再按文件重建，
     *                此时「现有归属」已不存在，全部按文件重排，置顶也全部按文件应用。
     */
    fun plan(snap: Snapshot, cur: CurrentState, replace: Boolean = false): Plan {
        // 覆盖模式：分组全删 → 现状等价于空库
        val base = if (replace) CurrentState(knownKeys = cur.knownKeys) else cur
        // ⚠️ 循环里新加的人也要并进 owned：旧备份（v0）允许一人多组，
        //    同一个人可能在文件里出现两次，**只认第一次**（否则第二次会把他搬到另一组）。
        val owned = base.owned.toMutableSet()
        val actions = ArrayList<GroupAction>(snap.groups.size)

        for (gs in snap.groups) {
            val name = gs.name
            val existing = !replace && name in base.groupNames
            val here = if (replace) emptySet<String>() else base.membersOfGroup[name].orEmpty()

            val add = ArrayList<MemberSnap>()
            val skipped = ArrayList<String>()
            var already = 0
            for (m in gs.members) {
                when {
                    m.wxid in here -> already++
                    m.wxid in owned -> skipped.add(m.wxid) // 已在别的组 → 以现有为准，不搬
                    else -> {
                        add.add(m)
                        owned.add(m.wxid)
                    }
                }
            }
            actions += GroupAction(
                name = name,
                create = !existing,
                // 置顶只在新建时应用：合并语义下不该推翻用户现有的置顶状态
                applyPinned = !existing && gs.pinned,
                add = add,
                skipped = skipped,
                already = already,
            )
        }

        val (settings, dropped) = pickSettings(snap, base.knownKeys)
        return Plan(
            schema = snap.schema,
            replace = replace,
            groups = actions,
            settings = settings,
            settingsDropped = dropped,
        )
    }

    /**
     * 挑选要写入的配置项。
     *
     * 三条：
     * - v0（缺 `schema`）或文件没带配置段 → **一个都不写**（[Snapshot.hasSettings] 已判版本）；
     * - 只覆盖**文件里出现**的 key，没写的保持当前值 —— 所以这里不做任何「补默认值」；
     * - 未登记的 key 丢弃（可能来自更高版本的备份）。
     */
    fun pickSettings(snap: Snapshot, knownKeys: Set<String>): Pair<Map<String, String>, Int> {
        if (!snap.hasSettings) return emptyMap<String, String>() to 0
        val out = LinkedHashMap<String, String>()
        var dropped = 0
        for ((k, v) in snap.settings) {
            if (k !in knownKeys) {
                dropped++
                continue
            }
            out[k] = v
        }
        return out to dropped
    }

    /**
     * 版本兼容说明（写进日志，出问题时一眼能看出是哪个文件）。
     *
     * - 缺 schema → v0：只恢复分组与成员，配置不动；
     * - 高于当前版本 → 尽力解析、忽略未知字段，不拒绝。
     */
    fun compatNote(snap: Snapshot): String = when {
        snap.schema <= 0 ->
            "v0 旧备份（无 schema）：只恢复分组与成员，配置保持当前值"
        snap.schema > BackupFormat.SCHEMA ->
            "备份 schema=${snap.schema} 高于当前 ${BackupFormat.SCHEMA}：" +
                "尽力解析、忽略未知字段，不拒绝"
        snap.schema == BackupFormat.SCHEMA ->
            "schema=${snap.schema} 与当前一致"
        else ->
            "schema=${snap.schema} 低于当前 ${BackupFormat.SCHEMA}：按已知字段恢复"
    }

    /** 这份快照到底是不是「本模块的文件」（不是的话调用方应当中止，一个字节都不写） */
    fun accept(snap: Snapshot): Boolean = snap.groups.isNotEmpty() || snap.hasSettings
}
