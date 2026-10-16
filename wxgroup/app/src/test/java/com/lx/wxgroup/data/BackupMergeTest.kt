package com.lx.wxgroup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份导入**合并规则**的回归测试（需求 6.7 / 决策 #10 / v6.4）。
 *
 * 只测 [BackupMerge] —— 它刻意不依赖任何 Android API（没有 Context / Uri / SQLite），
 * 所以能在 JVM 上跑。这些规则是「换手机 / 升级模块 / 拿到别人的旧文件」才走到的分支，
 * 写错了在手机上很难复现，但用户会直接丢数据。
 *
 * 覆盖四类：
 * 1. **同名群合并**：按名字匹配（id 换机后对不上），文件里出现两个同名组也并进同一组；
 * 2. **冲突消解**：成员归属冲突以现有为准（v6.4），不搬移、不重复计入；
 * 3. **空库 / 脏数据**：空库全新建；坏 JSON / 缺字段 / 类型不符到不了 plan；
 * 4. **计数对账**：plan 的每个计数都有明确语义，且按 plan 执行后
 *    「成员独占」不变量保持、二次导入零新增（幂等）—— 这条同时锁住
 *    [BackupIO.restore] 的执行层必须照 plan 做，不能另写一套。
 *
 * [simulate] 是执行层的**内存镜像**：真机落库走 GroupDb，单测里走它，
 * 两边语义必须一致（plan 是规则的唯一书面定义）。
 */
class BackupMergeTest {

    /** 单测里当"已登记配置 key"用（对应真机的 Prefs.ALL） */
    private val knownKeys = setOf(
        "chat.dnd_enabled",
        "moments.refresh_interval_h",
        "broadcast.interval_s",
    )

    // ------------------------------------------------------------ 构造辅助

    private fun member(wxid: String, name: String = wxid, type: Int = 0) =
        MemberSnap(wxid, name, type)

    private fun group(
        name: String,
        vararg members: MemberSnap,
        color: Int = 0,
        pinned: Boolean = false,
    ) = GroupSnap(name = name, color = color, sortOrder = 0, pinned = pinned, members = members.toList())

    private fun snap(
        vararg groups: GroupSnap,
        schema: Int = BackupFormat.SCHEMA,
        settings: Map<String, String> = emptyMap(),
    ) = Snapshot(
        schema = schema,
        exportedAt = 0L,
        groups = groups.toList(),
        settings = settings,
    )

    private fun state(
        groupNames: Set<String> = emptySet(),
        membersOfGroup: Map<String, Set<String>> = emptyMap(),
        owned: Set<String> = emptySet(),
        keys: Set<String> = knownKeys,
    ) = BackupMerge.CurrentState(
        groupNames = groupNames,
        membersOfGroup = membersOfGroup,
        owned = owned,
        knownKeys = keys,
    )

    // ------------------------------------------------------------ 同名群合并

    @Test
    fun 同名组合并不新建只补缺的成员() {
        val s = snap(group("家人", member("wxid_a"), member("wxid_b")), group("同事", member("wxid_c")))
        val cur = state(
            groupNames = setOf("家人"),
            membersOfGroup = mapOf("家人" to setOf("wxid_a")),
            owned = setOf("wxid_a"),
        )

        val plan = BackupMerge.plan(s, cur, replace = false)

        val jia = plan.groups.first()
        assertFalse("库里有同名组就不新建", jia.create)
        assertEquals("已在组里的不重复加", 1, jia.already)
        assertEquals(listOf("wxid_b"), jia.add.map { it.wxid })
        assertEquals("没有冲突", 0, jia.skipped.size)

        val tong = plan.groups[1]
        assertTrue("新名字才新建", tong.create)
        assertEquals(listOf("wxid_c"), tong.add.map { it.wxid })

        assertEquals(1, plan.groupsCreated)
        assertEquals(2, plan.membersAdded)
        assertEquals(1, plan.membersAlready)
    }

    @Test
    fun 文件里两个同名组合并进同一组() {
        // 手工编辑过的文件可能出现两个同名组：分组名有 UNIQUE 约束，
        // 第二个必须并进第一个（而不是再"新建"一个必然失败的同名组）
        val s = snap(group("家人", member("wxid_a")), group("家人", member("wxid_b")))

        val plan = BackupMerge.plan(s, state(), replace = false)

        assertEquals("同名组只算一次新建", 1, plan.groupsCreated)
        assertTrue(plan.groups[0].create)
        assertFalse("第二个同名组并进去", plan.groups[1].create)
        assertEquals(listOf("wxid_b"), plan.groups[1].add.map { it.wxid })
        assertEquals(2, plan.membersAdded)
        assertEquals(0, plan.membersSkipped)
    }

    @Test
    fun 文件同名组合并进库里有名的组() {
        val s = snap(group("家人", member("wxid_a")), group("家人", member("wxid_b")))
        val cur = state(groupNames = setOf("家人"), membersOfGroup = mapOf("家人" to setOf("wxid_a")), owned = setOf("wxid_a"))

        val plan = BackupMerge.plan(s, cur, replace = false)

        assertEquals(0, plan.groupsCreated)
        assertFalse(plan.groups[0].create)
        assertFalse(plan.groups[1].create)
        assertEquals(1, plan.membersAdded)
        assertEquals(1, plan.membersAlready)
    }

    @Test
    fun replace模式全部按文件重建() {
        val s = snap(group("家人", member("wxid_x")), group("同事", member("wxid_y")))
        val cur = state(
            groupNames = setOf("家人"),
            membersOfGroup = mapOf("家人" to setOf("wxid_a")),
            owned = setOf("wxid_a"),
        )

        val plan = BackupMerge.plan(s, cur, replace = true)

        assertTrue("覆盖模式下每个组都是新建", plan.groups.all { it.create })
        assertEquals(2, plan.groupsCreated)
        // wxid_a 不在文件里：先删光再重建，他自然消失，不算 skipped
        assertEquals(0, plan.membersSkipped)
        assertEquals(2, plan.membersAdded)
    }

    // ------------------------------------------------------------ 冲突消解

    @Test
    fun 归属冲突以现有为准不搬移() {
        // v6.4：文件把 wxid_b 放进 A 组，但库里他已经在 B 组 —— 保持不动
        val s = snap(group("A", member("wxid_a"), member("wxid_b")))
        val cur = state(
            groupNames = setOf("A", "B"),
            membersOfGroup = mapOf("A" to setOf("wxid_a"), "B" to setOf("wxid_b")),
            owned = setOf("wxid_a", "wxid_b"),
        )

        val plan = BackupMerge.plan(s, cur, replace = false)

        val a = plan.groups.first()
        assertEquals(1, a.already)
        assertEquals("b 已在 B 组，不搬进 A", listOf("wxid_b"), a.skipped)
        assertTrue(a.add.isEmpty())
        assertEquals(0, plan.membersAdded)
        assertEquals(1, plan.membersSkipped)
    }

    @Test
    fun 同一个人在文件两个组只认第一次() {
        // 旧备份（v0）允许一人多组；成员独占下只认第一次出现
        val s = snap(group("A", member("wxid_x")), group("B", member("wxid_x")))

        val plan = BackupMerge.plan(s, state(), replace = false)

        assertEquals(listOf("wxid_x"), plan.groups[0].add.map { it.wxid })
        assertEquals("第二次出现算冲突跳过", listOf("wxid_x"), plan.groups[1].skipped)
        assertTrue(plan.groups[1].add.isEmpty())
        assertEquals(1, plan.membersAdded)
        assertEquals(1, plan.membersSkipped)
    }

    @Test
    fun replace模式下文件内重复也只认第一次() {
        // 覆盖模式也受成员独占约束：删光之后按文件重建，同一个人仍只进第一个组
        val s = snap(group("A", member("wxid_x")), group("B", member("wxid_x")))
        val cur = state(owned = setOf("wxid_old"))

        val plan = BackupMerge.plan(s, cur, replace = true)

        assertEquals(1, plan.membersAdded)
        assertEquals(listOf("wxid_x"), plan.groups[1].skipped)
    }

    @Test
    fun 已在目标组的人不算冲突() {
        val s = snap(group("A", member("wxid_a"), member("wxid_b")))
        val cur = state(
            groupNames = setOf("A"),
            membersOfGroup = mapOf("A" to setOf("wxid_a", "wxid_b")),
            owned = setOf("wxid_a", "wxid_b"),
        )

        val plan = BackupMerge.plan(s, cur, replace = false)

        assertEquals(2, plan.membersAlready)
        assertEquals(0, plan.membersSkipped)
        assertEquals(0, plan.membersAdded)
    }

    // ------------------------------------------------------------ 置顶规则

    @Test
    fun 合并模式不推翻现有置顶() {
        val s = snap(group("家人", member("wxid_a"), pinned = true))
        val cur = state(groupNames = setOf("家人"), membersOfGroup = mapOf("家人" to emptySet()))

        val plan = BackupMerge.plan(s, cur, replace = false)

        assertFalse("已存在组的置顶状态不归文件管", plan.groups.first().applyPinned)
    }

    @Test
    fun 新建分组才应用文件置顶() {
        val s = snap(
            group("新组", member("wxid_a"), pinned = true),
            group("新组2", member("wxid_b"), pinned = false),
        )

        val plan = BackupMerge.plan(s, state(), replace = false)

        assertTrue(plan.groups[0].applyPinned)
        assertFalse(plan.groups[1].applyPinned)
    }

    @Test
    fun replace模式置顶全部按文件() {
        val s = snap(group("家人", member("wxid_a"), pinned = true))
        val cur = state(groupNames = setOf("家人"), membersOfGroup = mapOf("家人" to setOf("wxid_z")), owned = setOf("wxid_z"))

        val plan = BackupMerge.plan(s, cur, replace = true)

        assertTrue(plan.groups.first().applyPinned)
    }

    // ------------------------------------------------------------ 配置项

    @Test
    fun v0旧备份一个配置都不写() {
        // 缺 schema = v0：只恢复分组与成员，配置一律不动（决策 #10）
        val s = snap(
            group("家人", member("wxid_a")),
            schema = 0,
            settings = mapOf("chat.dnd_enabled" to "true"),
        )

        val plan = BackupMerge.plan(s, state(), replace = false)

        assertTrue(plan.settings.isEmpty())
        assertEquals(0, plan.settingsDropped)
        assertEquals("成员照常恢复", 1, plan.membersAdded)
    }

    @Test
    fun 配置只覆盖文件里出现的key() {
        val s = snap(settings = mapOf("chat.dnd_enabled" to "false"))

        val plan = BackupMerge.plan(s, state(), replace = false)

        assertEquals(mapOf("chat.dnd_enabled" to "false"), plan.settings)
        assertEquals(0, plan.settingsDropped)
    }

    @Test
    fun 未登记的key丢弃并计数() {
        // 可能是更高版本备份里的新配置，写进来只会污染当前库
        val s = snap(
            settings = mapOf(
                "chat.dnd_enabled" to "true",
                "future.new_key" to "x",
                "another.future" to "y",
            )
        )

        val plan = BackupMerge.plan(s, state(), replace = false)

        assertEquals(setOf("chat.dnd_enabled"), plan.settings.keys)
        assertEquals(2, plan.settingsDropped)
    }

    @Test
    fun 有schema但空配置段不写配置() {
        val s = snap(group("家人", member("wxid_a")), schema = BackupFormat.SCHEMA, settings = emptyMap())

        val plan = BackupMerge.plan(s, state(), replace = false)

        assertTrue(plan.settings.isEmpty())
        assertEquals(0, plan.settingsDropped)
    }

    @Test
    fun replace模式配置规则不变() {
        val s = snap(settings = mapOf("chat.dnd_enabled" to "true", "future.key" to "x"))

        val plan = BackupMerge.plan(s, state(), replace = true)

        assertEquals(setOf("chat.dnd_enabled"), plan.settings.keys)
        assertEquals(1, plan.settingsDropped)
    }

    // ------------------------------------------------------------ 空库 / 空文件

    @Test
    fun 空库合并全部新建() {
        val s = snap(
            group("家人", member("wxid_a"), member("wxid_b")),
            group("同事", member("wxid_c")),
        )

        val plan = BackupMerge.plan(s, state(), replace = false)

        assertEquals(2, plan.groupsCreated)
        assertEquals(3, plan.membersAdded)
        assertEquals(0, plan.membersSkipped)
        assertEquals(0, plan.membersAlready)
    }

    @Test
    fun 空文件产出全零计划() {
        val plan = BackupMerge.plan(snap(), state(), replace = false)

        assertEquals(0, plan.groupsCreated)
        assertEquals(0, plan.membersAdded)
        assertEquals(0, plan.membersSkipped)
        assertEquals(0, plan.membersAlready)
        assertTrue(plan.settings.isEmpty())
    }

    @Test
    fun accept判断有没有可恢复的内容() {
        // Snapshot 是 hydrate 之后的模型：groups 字段缺失的文件根本到不了这一层
        // （codec 层就报错了）。这里 accept 判的是"有没有内容可恢复"。
        assertFalse("空分组 + 空配置 = 没内容", BackupMerge.accept(snap()))
        assertTrue("有分组就有内容", BackupMerge.accept(snap(group("G"))))
        assertTrue("只有配置段也有内容", BackupMerge.accept(snap(settings = mapOf("chat.dnd_enabled" to "true"))))
        // 端到端：一个组都没有的用户导出的文件也合法，能一路走到 plan
        val res = BackupCodec.parse("""{"schema":1,"module":"wxgroup","kv":{},"groups":[]}""")
        assertNotNull("空分组文件合法", res.snapshot)
        val plan = BackupMerge.plan(res.snapshot!!, state(), replace = false)
        assertEquals(0, plan.groupsCreated)
        assertEquals(0, plan.membersAdded)
    }

    // ------------------------------------------------------------ 脏数据

    @Test
    fun 坏JSON到不了plan() {
        // 文件损坏 → 解析层就拦住，一个字节都不写库
        val res = BackupCodec.parse("{ this is not json ]")
        assertNull(res.snapshot)
        assertNotNull(res.error)
        val res2 = BackupCodec.parse("")
        assertNull(res2.snapshot)
        assertNotNull(res2.error)
    }

    @Test
    fun 类型不符的字段整文件拒绝() {
        // color 写成了非数字字符串：Gson 抛错 → 解析层返回错误，不进 plan
        val bad = """{"schema":1,"module":"wxgroup","groups":[{"name":"家人","color":"abc","members":[]}]}"""
        val res = BackupCodec.parse(bad)
        assertNull("类型不符要整文件拒绝，不能洗成 0 蒙混过关", res.snapshot)
        assertNotNull(res.error)
    }

    @Test
    fun 缺字段的组按空组处理() {
        val json = """{"schema":1,"module":"wxgroup","groups":[{"name":"家人"}]}"""
        val snap = BackupCodec.parse(json).snapshot
        assertNotNull(snap)
        assertEquals(1, snap!!.groupCount)
        assertEquals(0, snap.memberCount)

        val plan = BackupMerge.plan(snap, state(), replace = false)
        assertTrue(plan.groups.first().create)
        assertTrue(plan.groups.first().add.isEmpty())
    }

    @Test
    fun 脏成员在解析层就被清洗() {
        // 空 wxid / 空名字的成员进不了 Snapshot，plan 看不到它们
        val json = """
            {"schema":1,"module":"wxgroup",
             "groups":[{"name":"有效组","members":[{"wxid":"  "},{"wxid":"wxid_y"},{}]}]}
        """.trimIndent()
        val snap = BackupCodec.parse(json).snapshot!!
        assertEquals(1, snap.groups[0].members.size)

        val plan = BackupMerge.plan(snap, state(), replace = false)
        assertEquals(listOf("wxid_y"), plan.groups[0].add.map { it.wxid })
    }

    @Test
    fun pinned非01按true() {
        val json = """{"schema":1,"module":"wxgroup","groups":[{"name":"G","pinned":5,"members":[]}]}"""
        val snap = BackupCodec.parse(json).snapshot!!
        assertTrue(snap.groups[0].pinned)
        assertTrue(BackupMerge.plan(snap, state(), replace = false).groups[0].applyPinned)
    }

    @Test
    fun 空名分组在解析层被丢弃() {
        val json = """
            {"schema":1,"module":"wxgroup",
             "groups":[{"name":"  ","members":[{"wxid":"wxid_x"}]},{"name":"有效","members":[]}]}
        """.trimIndent()
        val snap = BackupCodec.parse(json).snapshot!!
        assertEquals(1, snap.groupCount)
        assertEquals("有效", snap.groups[0].name)
    }

    // ------------------------------------------------------------ 计数对账

    @Test
    fun plan计数与分组明细一致() {
        val s = snap(
            group("A", member("a1"), member("a2")),
            group("B", member("b1")),
            group("C", member("c1")),
        )
        val cur = state(
            groupNames = setOf("A"),
            membersOfGroup = mapOf("A" to setOf("a1")),
            owned = setOf("a1", "b1"),
        )

        val plan = BackupMerge.plan(s, cur, replace = false)

        assertEquals(plan.groups.count { it.create }, plan.groupsCreated)
        assertEquals(plan.groups.sumOf { it.add.size }, plan.membersAdded)
        assertEquals(plan.groups.sumOf { it.skipped.size }, plan.membersSkipped)
        assertEquals(plan.groups.sumOf { it.already }, plan.membersAlready)
    }

    @Test
    fun 按plan执行后成员独占不变量保持() {
        // 模拟执行层照 plan 落库：每个 wxid 只能出现在一个组里
        val s = snap(
            group("家人", member("wxid_a"), member("wxid_b"), member("wxid_c")),
            group("同事", member("wxid_c"), member("wxid_d")),
            group("家人", member("wxid_e")),
        )
        val db = FakeDb().apply {
            addGroup("家人", "wxid_a")
            addGroup("球友", "wxid_b")
        }
        val before = db.snapshotState()

        val plan = BackupMerge.plan(s, before, replace = false)
        db.apply(plan)

        // 库里原有的球友组不动，b 也没被搬进家人组
        assertEquals(setOf("wxid_b"), db.groups["球友"])
        assertEquals(setOf("wxid_a", "wxid_c", "wxid_e"), db.groups["家人"])
        assertEquals(setOf("wxid_d"), db.groups["同事"])
        // 不变量：没有任何 wxid 出现在两个组里
        val all = db.groups.values.flatten()
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun replace执行后库内容与文件一致() {
        val s = snap(
            group("家人", member("wxid_a"), member("wxid_b")),
            group("同事", member("wxid_c")),
        )
        val db = FakeDb().apply {
            addGroup("旧组", "wxid_old")
            addGroup("家人", "wxid_z")
        }

        val plan = BackupMerge.plan(s, db.snapshotState(), replace = true)
        db.apply(plan)

        assertEquals(setOf("家人", "同事"), db.groups.keys)
        assertEquals(setOf("wxid_a", "wxid_b"), db.groups["家人"])
        assertEquals(setOf("wxid_c"), db.groups["同事"])
    }

    @Test
    fun 二次导入零新增() {
        // 幂等：自己导出的文件再合并导回来，一条都不该新增（selftest 的判据）
        val s = snap(
            group("家人", member("wxid_a"), member("wxid_b")),
            group("同事", member("wxid_c")),
            settings = mapOf("chat.dnd_enabled" to "true"),
        )
        val db = FakeDb().apply {
            addGroup("家人", "wxid_a")
            addGroup("球友", "wxid_q")
        }

        val first = BackupMerge.plan(s, db.snapshotState(), replace = false)
        db.apply(first)
        val second = BackupMerge.plan(s, db.snapshotState(), replace = false)

        assertEquals(0, second.groupsCreated)
        assertEquals(0, second.membersAdded)
        assertEquals(0, second.membersSkipped)
        assertTrue(second.settings.isEmpty() || second.settings == first.settings)
    }

    @Test
    fun 对账函数能数出执行偏差() {
        // 执行层（GroupDb）返回的实际新增数必须和 plan 一致；
        // 不一致 = 落库出了意外（并发、磁盘满、UNIQUE 冲突），必须能在日志里看出来
        val s = snap(group("A", member("a1"), member("a2")), group("B", member("b1")))
        val plan = BackupMerge.plan(s, state(), replace = false)

        assertTrue(BackupMerge.reconcile(plan, createdGroups = 2, addedMembers = 3))
        assertFalse("少建一个组要对账失败", BackupMerge.reconcile(plan, createdGroups = 1, addedMembers = 3))
        assertFalse("少加一个人要对账失败", BackupMerge.reconcile(plan, createdGroups = 2, addedMembers = 2))
        assertFalse("多加人也要失败", BackupMerge.reconcile(plan, createdGroups = 2, addedMembers = 4))
    }

    @Test
    fun 版本兼容说明覆盖三种schema() {
        assertEquals(true, BackupMerge.compatNote(snap(schema = 0)).contains("v0"))
        assertEquals(true, BackupMerge.compatNote(snap(schema = BackupFormat.SCHEMA)).contains("一致"))
        assertEquals(true, BackupMerge.compatNote(snap(schema = 99)).contains("高于当前"))
    }

    // ------------------------------------------------------------ 执行层内存镜像

    /**
     * 分组库的内存镜像：真机上这些操作走 [GroupDb]（SQLite），
     * 单测里用同语义的 Map 模拟 —— [BackupMerge.plan] 是规则的唯一定义，
     * 执行层照它做就行，不需要第二套判断。
     */
    private inner class FakeDb {
        val groups = LinkedHashMap<String, MutableSet<String>>()

        fun addGroup(name: String, vararg wxids: String) {
            groups.getOrPut(name) { mutableSetOf() }.addAll(wxids.toList())
        }

        fun snapshotState() = BackupMerge.CurrentState(
            groupNames = groups.keys.toSet(),
            membersOfGroup = groups.mapValues { it.value.toSet() },
            owned = groups.values.flatten().toSet(),
            knownKeys = knownKeys,
        )

        /** 模拟执行层：覆盖先删光，再按 plan 建组/加人（置顶不影响成员集合，这里不追踪） */
        fun apply(plan: BackupMerge.Plan) {
            if (plan.replace) groups.clear()
            for (a in plan.groups) {
                if (a.create) {
                    check(groups[a.name] == null) { "plan 说新建，但库里已有同名组：${a.name}" }
                    groups[a.name] = mutableSetOf()
                }
                groups.getValue(a.name).addAll(a.add.map { it.wxid })
            }
        }
    }
}
