package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SnsRestoreLogic] 的判据回归测试。
 *
 * ## 为什么必须有这个
 * 反插是**往微信的库里写数据**，判据错了不是"功能不生效"，是**把微信的 SnsInfo 写坏**
 * （多一行 ID 对不上的、可见位写反的），而写坏了在界面上未必立刻看得出来。
 * 真机一轮 ≈2 分钟且日志会被微信自己的 DB 日志冲掉，所以判据必须在 JVM 里锁住。
 *
 * ## 数据来源
 * 列清单来自 `SnsGuard.readRow`（真机逐列 getColumnIndex 读出来又归档的，实证）；
 * snsId / localFlag 取自真机抓到的真实值（docs/取证结论.md 朋友圈章节：
 * 删动态时 localFlag 落成 2，补可见位后是 18；snsId 是有符号 Long）。
 */
class SnsRestoreLogicTest {

    // ------------------------------------------------------------------ 真实样本

    /** 真机 `pragma table_info(SnsInfo)` 抓到的全部 18 列（2026-10-18 `snsg sub=cols`） */
    private fun realColumns(): List<String> =
        SnsRestoreLogic.columns() + SnsRestoreLogic.UNARCHIVED

    /** 真机归档里的一条（id 是实测值，attrBuf 长度落在实测区间 235B~1069B 内） */
    private fun realRow(
        snsId: Long = -3447438253133393222L,
        localFlag: Int = 2,
    ): SnsRestoreLogic.ArchiveRow = SnsRestoreLogic.ArchiveRow(
        snsId = snsId,
        userName = "wxid_real_0f1a2b3c",
        createTime = 1760500000L,
        type = 1,
        sourceType = 1,
        localFlag = localFlag,
        subType = 0,
        serverExtFlag = 0,
        head = "http://wx.qlogo.cn/mmhead/realhead/0",
        stringSeq = "1379*****",
        content = ByteArray(512) { it.toByte() },
        attrBuf = ByteArray(235) { it.toByte() },
        postBuf = null,
    )

    // ------------------------------------------------------------------ 列白名单

    @Test
    fun knownColumnsAreTheThirteenArchivedOnes() {
        assertEquals(13, SnsRestoreLogic.KNOWN.size)
        assertEquals(13, SnsRestoreLogic.columns().size)
        // 归档行有值的字段，一个不多一个不少
        assertEquals(
            listOf(
                "snsId", "userName", "createTime", "type", "sourceType", "localFlag",
                "subType", "serverExtFlag", "head", "stringSeq", "content", "attrBuf", "postBuf",
            ),
            SnsRestoreLogic.columns(),
        )
    }

    @Test
    fun columnWhitelistRejectsGuessedNames() {
        for (c in SnsRestoreLogic.columns()) assertTrue(SnsRestoreLogic.isKnown(c))
        assertFalse(SnsRestoreLogic.isKnown("snsID")) // 评论表叫 snsID，动态表不叫
        assertFalse(SnsRestoreLogic.isKnown("localflag")) // 大小写敏感，别想当然
        assertFalse(SnsRestoreLogic.isKnown(""))
    }

    /** SnsInfo 实测 18 列，我们只写 13 列 → 剩下 5 列名已知、由 SQLite 填默认/NULL */
    @Test
    fun unknownColumnCountMatchesForensics() {
        // 13 + 5 = 18：两张名单加起来必须正好等于真机列数，多一列少一列都说明 schema 变了
        assertEquals(
            SnsRestoreLogic.TOTAL_COLUMNS,
            SnsRestoreLogic.KNOWN.size + SnsRestoreLogic.UNARCHIVED.size,
        )
        assertEquals(
            SnsRestoreLogic.TOTAL_COLUMNS,
            realColumns().size,
        )

        assertEquals(18, SnsRestoreLogic.TOTAL_COLUMNS)
        assertEquals(5, SnsRestoreLogic.unknownColumnCount(SnsRestoreLogic.TOTAL_COLUMNS))
        assertEquals(0, SnsRestoreLogic.unknownColumnCount(13))
        // 表里列数比白名单还少（schema 变了）不能算出负数
        assertEquals(0, SnsRestoreLogic.unknownColumnCount(9))
    }

    /** 真机 schema 必须与白名单对得上：少一列就写不进去，必须停下来 */
    @Test
    fun missingColumnsDetectsFatalSchemaDrift() {
        val actual = SnsRestoreLogic.columns() + listOf("colA", "colB", "colC", "colD", "colE")
        assertEquals(emptyList<String>(), SnsRestoreLogic.missingColumns(actual))
        assertEquals(
            listOf("MISSING attrBuf"),
            SnsRestoreLogic.missingColumns(actual - "attrBuf"),
        )
    }

    /** 没写的列 = [SnsRestoreLogic.UNARCHIVED] 那 5 列（数量变化是微信升级的信号） */
    @Test
    fun unknownColumnsAreTheFiveUnwrittenOnes() {
        assertEquals(SnsRestoreLogic.UNARCHIVED, SnsRestoreLogic.unknownColumns(realColumns()))

        val actual = SnsRestoreLogic.columns() + listOf("colA", "colB", "colC", "colD", "colE")
        assertEquals(
            listOf("colA", "colB", "colC", "colD", "colE"),
            SnsRestoreLogic.unknownColumns(actual),
        )
        assertEquals(emptyList<String>(), SnsRestoreLogic.unknownColumns(SnsRestoreLogic.columns()))
    }

    /**
     * 「INSERT 只给部分列」的硬风险：没写的列里只要有 NOT NULL 且无默认值的，
     * INSERT 必挂 —— 必须被算出来（宁可不做，也不能写一半进去）。
     *
     * 真机实测 18 列全可空（见 [realSchemaHasNoBlockingColumns]），这里是**护栏**：
     * 微信哪天加了 NOT NULL 列，这里先炸，别等真机上一堆 INSERT 失败才看出来。
     */
    @Test
    fun blockingColumnsFlagsNotNullWithoutDefault() {
        val schema = listOf(
            SnsRestoreLogic.ColInfo("snsId", notNull = true, hasDefault = false), // 已知列，会写
            SnsRestoreLogic.ColInfo("localFlag", notNull = true, hasDefault = false),
            SnsRestoreLogic.ColInfo("someNew1", notNull = true, hasDefault = false), // ⚠️
            SnsRestoreLogic.ColInfo("someNew2", notNull = true, hasDefault = true), // 有默认值，OK
            SnsRestoreLogic.ColInfo("someNew3", notNull = false, hasDefault = false), // 可空，OK
        )
        assertEquals(listOf("someNew1"), SnsRestoreLogic.blockingColumns(schema))
    }

    /**
     * 真机 18 列**没有一列是 NOT NULL** → `BLOCKING none`，INSERT 不会因为缺那 5 列而失败
     * （2026-10-18 `snsg sub=cols` 实测）。这条锁的是「反插落库安全」这个前提，
     * 哪天微信加了 NOT NULL 列，这条会先炸。
     */
    @Test
    fun realSchemaHasNoBlockingColumns() {
        val schema = realColumns().map { SnsRestoreLogic.ColInfo(it, notNull = false, hasDefault = false) }
        assertEquals(18, schema.size)
        assertEquals(emptyList<String>(), SnsRestoreLogic.blockingColumns(schema))
        assertEquals(emptyList<String>(), SnsRestoreLogic.missingColumns(realColumns()))
        assertEquals(emptyList<String>(), SnsRestoreLogic.alienColumns(realColumns()))
    }

    /**
     * 那 5 列的**真名**（真机 pragma 抓全，不再是「未知列」）。
     *
     * ⚠️ `pravited` 是微信自己的拼写错误，别写成 `privated`；
     *    `withTa` 和 `withTaHasOther` 是**两列**，别合并成一列。
     * ⚠️ 但**列名已知 ≠ 可以写入**：归档里没存这些值，它们绝不能进 [SnsRestoreLogic.KNOWN]。
     */
    @Test
    fun theOtherFiveColumnsAreNamedButDeliberatelyNotWritten() {
        assertEquals(
            listOf("localPrivate", "likeFlag", "pravited", "withTa", "withTaHasOther"),
            SnsRestoreLogic.UNARCHIVED,
        )
        for (c in SnsRestoreLogic.UNARCHIVED) {
            assertFalse("$c 不能进写入白名单", SnsRestoreLogic.isKnown(c))
            assertTrue(SnsRestoreLogic.isUnarchived(c))
        }
        // 写入白名单仍然是那 13 列，一个都没多
        assertEquals(13, SnsRestoreLogic.columns().size)
        assertEquals(13, SnsRestoreLogic.insertPuts(realRow()).size)
    }

    /**
     * **schema 漂移**：出现既不在写入白名单、也不在那 5 列里的列 = 微信改了表
     * （加列 / 改名）。必须被认出来，不能当成「正常的未知列」混过去。
     */
    @Test
    fun alienColumnsFlagsRealSchemaDrift() {
        assertEquals(emptyList<String>(), SnsRestoreLogic.alienColumns(realColumns()))
        assertEquals(
            listOf("brandNewCol"),
            SnsRestoreLogic.alienColumns(realColumns() + "brandNewCol"),
        )
        // 「改名」的表现是：老列进了 missing、新列进了 alien，两条都得报。
        //
        // ⚠️ 改名测试必须挑 **KNOWN（写入白名单）里的列**：[SnsRestoreLogic.missingColumns]
        // 只看「要写的 13 列在真机里还在不在」—— 真正致命的是拿一个不存在的列名去 INSERT。
        // 而 `pravited` 属于 [SnsRestoreLogic.UNARCHIVED]（那 5 列我们压根不写），
        // 它消失**不该**报 MISSING（用 `pravited` 做改名断言会得到空列表，别写回这个坑）。
        val renamed = realColumns() - "attrBuf" + "attrBufNew"
        assertEquals(listOf("MISSING attrBuf"), SnsRestoreLogic.missingColumns(renamed))
        assertEquals(listOf("attrBufNew"), SnsRestoreLogic.alienColumns(renamed))

        // UNARCHIVED 列改名：只进 alien，不进 missing（它本来就不写，丢了也不致命）
        val renamedUnarchived = realColumns() - "pravited" + "privated"
        assertEquals(
            emptyList<String>(),
            SnsRestoreLogic.missingColumns(renamedUnarchived),
        )
        assertEquals(listOf("privated"), SnsRestoreLogic.alienColumns(renamedUnarchived))
    }

    // ------------------------------------------------------------------ 可见位

    @Test
    fun visibleBitRules() {
        assertFalse(SnsRestoreLogic.isVisible(2)) // 微信清位后
        assertFalse(SnsRestoreLogic.isVisible(0))
        assertTrue(SnsRestoreLogic.isVisible(16))
        assertTrue(SnsRestoreLogic.isVisible(18)) // 2|16，恢复目标
        assertTrue(SnsRestoreLogic.isVisible(2064)) // 白名单里带 2048 的
        assertTrue(SnsRestoreLogic.isVisible(2066))
    }

    @Test
    fun withVisibleIsIdempotentAndKeepsOtherBits() {
        assertEquals(18, SnsRestoreLogic.withVisible(2))
        assertEquals(18, SnsRestoreLogic.withVisible(18)) // 再或一次不变
        assertEquals(2064, SnsRestoreLogic.withVisible(2048))
        assertEquals(2066, SnsRestoreLogic.withVisible(2050)) // 2050|16
    }

    // ------------------------------------------------------------------ INSERT 映射

    @Test
    fun insertPutsCoverAllKnownColumnsInOrder() {
        val puts = SnsRestoreLogic.insertPuts(realRow())
        assertEquals(13, puts.size)
        assertEquals(SnsRestoreLogic.columns(), puts.map { it.col })
    }

    /** 归档快照的 localFlag 恒 2，插回去必须补可见位，否则界面上还是看不到 */
    @Test
    fun insertPutsRestoresVisibleBit() {
        val puts = SnsRestoreLogic.insertPuts(realRow(localFlag = 2))
        val flag = puts.first { it.col == "localFlag" }
        assertEquals(SnsRestoreLogic.Kind.INT, flag.kind)
        // 值必须是 Long：wcdb 的 INT 列绑 Int 会被截成 32 位（coerce 统一收敛）
        assertEquals(18L, flag.v)
    }

    @Test
    fun insertPutsKeepsTypesAndNulls() {
        val row = realRow().copy(userName = null, content = null)
        val puts = SnsRestoreLogic.insertPuts(row)
        val byCol = puts.associateBy { it.col }
        assertEquals(SnsRestoreLogic.Kind.TEXT, byCol["userName"]!!.kind)
        assertNull(byCol["userName"]!!.v)
        assertEquals(SnsRestoreLogic.Kind.BLOB, byCol["content"]!!.kind)
        assertNull(byCol["content"]!!.v)
        assertEquals(SnsRestoreLogic.Kind.INT, byCol["createTime"]!!.kind)
        assertEquals(1760500000L, byCol["createTime"]!!.v)
        // snsId 必须原样带过去（它是主键，位模式错了就永远对不上）
        assertEquals(-3447438253133393222L, byCol["snsId"]!!.v)
    }

    // ------------------------------------------------------------------ 幂等判定

    @Test
    fun classifyDecidesPerRowState() {
        assertEquals(SnsRestoreLogic.Action.INSERT, SnsRestoreLogic.classify(null))
        assertEquals(SnsRestoreLogic.Action.SET_FLAG, SnsRestoreLogic.classify(2))
        assertEquals(SnsRestoreLogic.Action.SET_FLAG, SnsRestoreLogic.classify(0))
        assertEquals(SnsRestoreLogic.Action.SKIP, SnsRestoreLogic.classify(16))
        assertEquals(SnsRestoreLogic.Action.SKIP, SnsRestoreLogic.classify(18))
        assertEquals(SnsRestoreLogic.Action.SKIP, SnsRestoreLogic.classify(2064))
    }

    @Test
    fun planSplitsInsertAndSetFlag() {
        val a = realRow(snsId = 1L)
        val b = realRow(snsId = 2L)
        val c = realRow(snsId = 3L)
        // 1 行没了 → INSERT；2 行在但被清位 → SET_FLAG；3 行在且可见 → 不进计划
        val steps = SnsRestoreLogic.plan(listOf(a, b, c), mapOf(2L to 2, 3L to 18))
        assertEquals(2, steps.size)
        assertEquals(SnsRestoreLogic.Action.INSERT, steps[0].action)
        assertEquals(1L, steps[0].snsId)
        assertEquals(SnsRestoreLogic.Action.SET_FLAG, steps[1].action)
        assertEquals(2L, steps[1].snsId)
        assertEquals(2, steps[1].fromFlag)
        assertEquals(18, steps[1].toFlag)
    }

    @Test
    fun planDeduplicatesAndClamps() {
        val rows = listOf(
            realRow(snsId = 1L), realRow(snsId = 1L), realRow(snsId = 2L), realRow(snsId = 3L),
        )
        assertEquals(2, SnsRestoreLogic.plan(rows, emptyMap(), limit = 2).size)
        // 广播 --ei n 外人可控，必须钳位
        assertEquals(1, SnsRestoreLogic.clampBatch(0))
        assertEquals(3, SnsRestoreLogic.clampBatch(3))
        assertEquals(50, SnsRestoreLogic.clampBatch(999999))
    }

    /**
     * ⚠️ 预检查询失败（UNKNOWN）的行绝不能进计划。
     *
     * 真机风险：查失败的 id 不在 live 里 → 看似「行没了」→ INSERT → 撞主键（snsId）
     * → CONFLICT_IGNORE 静默不写 → **那条动态永远恢复不了，日志还看不出错**。
     * 比"这次没恢复"更坏，因为它伪装成了"执行成功"。
     */
    @Test
    fun planSkipsUnknownPresenceRows() {
        val a = realRow(snsId = 1L) // 预查失败
        val b = realRow(snsId = 2L) // 预查成功：行真没了
        // 1 在 unknown 里、且不在 live 里 —— 若不特判就会被当成 INSERT
        val steps = SnsRestoreLogic.plan(listOf(a, b), emptyMap(), limit = 10, unknown = setOf(1L))
        assertEquals(1, steps.size)
        assertEquals(2L, steps[0].snsId)
        assertEquals(SnsRestoreLogic.Action.INSERT, steps[0].action)
    }

    /** UNKNOWN 的行**不占额度**：跳过后，后面的行仍能凑满 limit */
    @Test
    fun planUnknownDoesNotConsumeQuota() {
        val rows = (1L..5L).map { realRow(snsId = it) }
        val steps = SnsRestoreLogic.plan(rows, emptyMap(), limit = 2, unknown = setOf(1L, 2L, 3L))
        assertEquals(2, steps.size)
        assertEquals(listOf(4L, 5L), steps.map { it.snsId })
    }

    /** 全部预查失败 → 计划为空（不能瞎写） */
    @Test
    fun planAllUnknownYieldsNothing() {
        val rows = listOf(realRow(snsId = 1L), realRow(snsId = 2L))
        val steps = SnsRestoreLogic.plan(rows, emptyMap(), limit = 10, unknown = setOf(1L, 2L))
        assertTrue(steps.isEmpty())
    }

    /** 不传 unknown（默认空集）时行为与本改动前完全一致 —— 防回归 */
    @Test
    fun planWithoutUnknownKeepsOldBehaviour() {
        val rows = listOf(realRow(snsId = 1L))
        assertEquals(1, SnsRestoreLogic.plan(rows, emptyMap(), limit = 10).size)
    }

    /** 幂等：按计划改完库之后再算一次，必须**什么都不用做** */
    @Test
    fun planIsIdempotentAfterApply() {
        val rows = listOf(realRow(snsId = 1L), realRow(snsId = 2L))
        val live = mutableMapOf(2L to 2) // 1 已不在，2 被清位
        val first = SnsRestoreLogic.plan(rows, live)
        assertEquals(2, first.size)

        // 模拟执行
        for (s in first) {
            when (s.action) {
                SnsRestoreLogic.Action.INSERT -> live[s.snsId] = 18
                SnsRestoreLogic.Action.SET_FLAG -> live[s.snsId] = s.toFlag!!
                else -> Unit
            }
        }
        assertEquals(emptyList<SnsRestoreLogic.Step>(), SnsRestoreLogic.plan(rows, live))
    }

    /**
     * **计划必须可复现**：同样的输入跑两次，得到**一模一样**的步骤序列。
     *
     * 为什么值得单独立一条：批量恢复是**写微信的库**，一旦中途失败要走倒序回滚，
     * 而回滚清单是按「已成功步骤」算的。计划里要是掺了时钟/随机数/HashMap 迭代序，
     * 重试一次就换一批行，那上一次失败留下的现场永远对不上，人工兜底也无从下手。
     * 这里用**不同的 Map 构造顺序**跑两遍 —— 输入集合一样，输出也必须一样
     * （[plan] 只遍历 `rows`、对 `live` 只做 `containsKey` 点查，天然不受 Map 顺序影响）。
     */
    @Test
    fun planIsReproducibleRegardlessOfMapInsertionOrder() {
        val rows = listOf(realRow(snsId = 1L), realRow(snsId = 2L), realRow(snsId = 3L))
        val liveA = linkedMapOf(2L to 2, 3L to 18) // 正序插
        val liveB = linkedMapOf(3L to 18, 2L to 2) // 倒序插
        val first = SnsRestoreLogic.plan(rows, liveA)
        val second = SnsRestoreLogic.plan(rows, liveB)
        assertEquals(first, second)
        assertEquals(listOf(1L, 2L), first.map { it.snsId })
    }

    /**
     * 批量额度只给**真要动手**的行：`SKIP`（行在且已可见）是「什么都不做」，
     * 不该占掉 `--ei n` 的额度 —— 否则归档里前面躺着 49 条已可见的，
     * 一条真正需要 INSERT 的都轮不上。
     */
    @Test
    fun planBudgetCountsOnlyActionableRows() {
        val rows = listOf(
            realRow(snsId = 1L), // 行在且可见 → SKIP
            realRow(snsId = 2L), // 行没了 → INSERT
            realRow(snsId = 3L), // 行没了 → INSERT
            realRow(snsId = 4L), // 行没了 → INSERT
            realRow(snsId = 5L), // localFlag=0 被清位 → SET_FLAG
        )
        val steps = SnsRestoreLogic.plan(rows, mapOf(1L to 18, 5L to 0), limit = 2)
        assertEquals(listOf(2L, 3L), steps.map { it.snsId }) // SKIP 不占额度
        assertEquals(SnsRestoreLogic.Action.INSERT, steps[0].action)

        // 第 5 条：0 也是「被清了可见位」，补成 16（白名单里的值）
        val one = SnsRestoreLogic.plan(rows, mapOf(1L to 18, 5L to 0), limit = 50)
        val set = one.first { it.snsId == 5L }
        assertEquals(SnsRestoreLogic.Action.SET_FLAG, set.action)
        assertEquals(0, set.fromFlag)
        assertEquals(16, set.toFlag)
    }

    /**
     * 批量顺序 = 归档传入顺序（`listSnsArchive` 是 createTime DESC，最新的先恢复），
     * 且**条数硬顶在 [SnsRestoreLogic.MAX_BATCH]** —— 广播 `--ei n` 是外人可控的，
     * 一条 INSERT 要把 content/attrBuf/postBuf 三个 BLOB 读出来又写进去。
     */
    @Test
    fun planKeepsArchiveOrderAndCapsAtMaxBatch() {
        assertEquals(50, SnsRestoreLogic.MAX_BATCH)
        val rows = (1L..60L).map { realRow(snsId = it) }
        val steps = SnsRestoreLogic.plan(rows, emptyMap(), limit = 999999)
        assertEquals(50, steps.size)
        assertEquals((1L..50L).toList(), steps.map { it.snsId }) // 保序，且不重排
        for (s in steps) assertEquals(SnsRestoreLogic.Action.INSERT, s.action)
    }

    // ------------------------------------------------------------------ 回滚

    @Test
    fun rollbackInvertsExactlyOneStep() {
        val ins = SnsRestoreLogic.Step(1L, SnsRestoreLogic.Action.INSERT, null, null)
        assertEquals(
            SnsRestoreLogic.Step(1L, SnsRestoreLogic.Action.ROLLBACK_DELETE, null, null),
            SnsRestoreLogic.rollbackOf(ins),
        )
        val set = SnsRestoreLogic.Step(2L, SnsRestoreLogic.Action.SET_FLAG, 2, 18)
        // 回滚 = 把 from/to 换个方向带回去，执行端写 fromFlag（2）
        assertEquals(SnsRestoreLogic.Action.ROLLBACK_FLAG, SnsRestoreLogic.rollbackOf(set)!!.action)
        assertEquals(2, SnsRestoreLogic.rollbackOf(set)!!.fromFlag)
        assertEquals(18, SnsRestoreLogic.rollbackOf(set)!!.toFlag)
        // SKIP 什么都没写，没有可回滚的
        assertNull(SnsRestoreLogic.rollbackOf(SnsRestoreLogic.Step(3L, SnsRestoreLogic.Action.SKIP, 18, null)))
    }

    /** 回滚必须**倒序**：后写的先撤，且只撤真的改过库的（SKIP 不进 done） */
    @Test
    fun rollbackOrderIsReversedAndSkipsNoops() {
        val done = listOf(
            SnsRestoreLogic.Step(1L, SnsRestoreLogic.Action.INSERT, null, null),
            SnsRestoreLogic.Step(2L, SnsRestoreLogic.Action.SET_FLAG, 2, 18),
        )
        val back = SnsRestoreLogic.rollbackOrder(done)
        assertEquals(2, back.size)
        assertEquals(SnsRestoreLogic.Action.ROLLBACK_FLAG, back[0].action)
        assertEquals(2L, back[0].snsId)
        assertEquals(SnsRestoreLogic.Action.ROLLBACK_DELETE, back[1].action)
        assertEquals(1L, back[1].snsId)
        assertEquals(emptyList<SnsRestoreLogic.Step>(), SnsRestoreLogic.rollbackOrder(emptyList()))
    }

    // ------------------------------------------------------------------ 回滚准入（B2）

    /**
     * 回滚只能删「写库之前**确认不在** SnsInfo 里」的那些行。
     *
     * `SnsInfo.snsId` 有没有唯一约束**没有实证**；若它不是唯一键，`where snsId=?` 的 delete
     * 会把用户本来就有的那一行一起删掉。所以除了 ABSENT，其它三态一律不许删。
     */
    @Test
    fun undoableInsertsOnlyTheConfirmedAbsentOnes() {
        val pre = mapOf(
            1L to SnsRestoreLogic.Presence.ABSENT, // ✅ 确认不在 → 本次插的，可删
            2L to SnsRestoreLogic.Presence.PRESENT, // ❌ 用户本来就有，删了就是删用户数据
            3L to SnsRestoreLogic.Presence.UNKNOWN, // ❌ 预检查询失败，证明不了是新的
        )
        val steps = listOf(
            SnsRestoreLogic.Step(1L, SnsRestoreLogic.Action.INSERT, null, null),
            SnsRestoreLogic.Step(2L, SnsRestoreLogic.Action.INSERT, null, null),
            SnsRestoreLogic.Step(3L, SnsRestoreLogic.Action.INSERT, null, null),
            SnsRestoreLogic.Step(4L, SnsRestoreLogic.Action.INSERT, null, null), // 预检都没查过
        )
        assertEquals(setOf(1L), SnsRestoreLogic.undoableInserts(steps, pre))
    }

    /** 只改可见位的步骤不进删除清单 —— 它的逆操作是写回原值，碰不到行本身 */
    @Test
    fun undoableInsertsExcludesSetFlagSteps() {
        val pre = mapOf(1L to SnsRestoreLogic.Presence.ABSENT, 2L to SnsRestoreLogic.Presence.PRESENT)
        val steps = listOf(
            SnsRestoreLogic.Step(1L, SnsRestoreLogic.Action.INSERT, null, null),
            SnsRestoreLogic.Step(2L, SnsRestoreLogic.Action.SET_FLAG, 2, 18),
            SnsRestoreLogic.Step(3L, SnsRestoreLogic.Action.SKIP, 18, null),
        )
        assertEquals(setOf(1L), SnsRestoreLogic.undoableInserts(steps, pre))
        assertEquals(emptySet<Long>(), SnsRestoreLogic.undoableInserts(emptyList(), emptyMap()))
    }

    /**
     * 端到端：跑到一半失败时，**只有确认新插入的那些行**会被倒序回滚删除。
     *
     * 这里锁的是「plan → undoable → rollbackOrder」三者的组合语义：
     * 计划里第 3 条是预检失败(UNKNOWN)的 INSERT，它即便执行成功也不许被删。
     */
    @Test
    fun halfFailedBatchRollsBackOnlyNewRowsInReverseOrder() {
        val rows = listOf(realRow(snsId = 1L), realRow(snsId = 2L), realRow(snsId = 3L))
        // 1 确认没了；2 在但被清位；3 预检查询失败（UNKNOWN）
        val pre = mapOf(
            1L to SnsRestoreLogic.Presence.ABSENT,
            2L to SnsRestoreLogic.Presence.PRESENT,
            3L to SnsRestoreLogic.Presence.UNKNOWN,
        )
        // plan 只认「行在不在」，所以它看不出 1 和 3 的区别 —— 两者都是 INSERT
        val plan = SnsRestoreLogic.plan(rows, mapOf(2L to 2))
        assertEquals(3, plan.size)
        assertEquals(listOf(1L, 3L), plan.filter { it.action == SnsRestoreLogic.Action.INSERT }
            .map { it.snsId })
        assertEquals(SnsRestoreLogic.Action.SET_FLAG, plan[1].action)

        // 真正决定“回滚敢不敢删”的是三态：只有 1 能删
        val undoable = SnsRestoreLogic.undoableInserts(plan, pre)
        assertEquals(setOf(1L), undoable)

        val done = listOf(
            SnsRestoreLogic.Step(1L, SnsRestoreLogic.Action.INSERT, null, null),
            SnsRestoreLogic.Step(2L, SnsRestoreLogic.Action.SET_FLAG, 2, 18),
            SnsRestoreLogic.Step(3L, SnsRestoreLogic.Action.INSERT, null, null),
        )
        val back = SnsRestoreLogic.rollbackOrder(done)
        assertEquals(listOf(3L, 2L, 1L), back.map { it.snsId }) // 倒序：后写的先撤
        val deletable = back.filter { it.action == SnsRestoreLogic.Action.ROLLBACK_DELETE }
            .map { it.snsId }
            .filter { undoable.contains(it) }
        assertEquals(listOf(1L), deletable) // 3 查不出来时不许删
    }

    // ------------------------------------------------------------------ 汇总

    @Test
    fun tallyAddsUp() {
        val t = SnsRestoreLogic.Tally(total = 3, inserted = 1) +
            SnsRestoreLogic.Tally(total = 2, unhidden = 1, failed = 1, rolledBack = 1)
        assertEquals(5, t.total)
        assertEquals(1, t.inserted)
        assertEquals(1, t.unhidden)
        assertEquals(1, t.failed)
        assertEquals(1, t.rolledBack)
        assertEquals(0, t.skipped)
    }

    @Test
    fun summariesAreStable() {
        val steps = listOf(
            SnsRestoreLogic.Step(1L, SnsRestoreLogic.Action.INSERT, null, null),
            SnsRestoreLogic.Step(2L, SnsRestoreLogic.Action.SET_FLAG, 2, 18),
        )
        assertEquals("plan total=2 insert=1 setFlag=1", SnsRestoreLogic.planSummary(steps))
        assertEquals("plan total=0 insert=0 setFlag=0", SnsRestoreLogic.planSummary(emptyList()))
        assertEquals(
            "total=2 inserted=1 unhidden=1 skipped=0 failed=0 rolledBack=0",
            SnsRestoreLogic.summary(SnsRestoreLogic.Tally(total = 2, inserted = 1, unhidden = 1)),
        )
    }

    // ------------------------------------------------------------------ 冲突算法

    /**
     * 写微信的库取 **4（CONFLICT_IGNORE）**，绝不能是 1（ROLLBACK，会回滚微信自己的事务）。
     *
     * 为什么单独锁：这个 int 是反射传给 wcdb 的，写错了不报错 —— `SnsInfo` 上
     * `snsId` 没有唯一约束，撞不出冲突，错了平时看不出来，等哪天微信加了约束就变成
     * 「覆盖用户活行」（5）或「回滚掉微信刚写的数据」（1）。
     */
    @Test
    fun conflictAlgorithmIsIgnoreForBothTargets() {
        assertEquals(4, SnsRestoreLogic.CONFLICT_IGNORE)
        assertEquals(5, SnsRestoreLogic.CONFLICT_REPLACE)
        assertEquals(4, SnsRestoreLogic.conflictFor(SnsRestoreLogic.Target.WX_SNSINFO))
        // 归档是「首记录为准」的快照（GroupDb.insertSnsArchive 用 IGNORE），REPLACE 会覆盖掉原始快照
        assertEquals(4, SnsRestoreLogic.conflictFor(SnsRestoreLogic.Target.OWN_ARCHIVE))
        for (t in SnsRestoreLogic.Target.values()) {
            assertTrue(SnsRestoreLogic.conflictFor(t) != 1)
            assertTrue(SnsRestoreLogic.conflictFor(t) != 0)
        }
    }

    @Test
    fun insertSqlCarriesTheConflictKeyword() {
        val sql = SnsSql.insertSql(
            SnsRestoreLogic.TABLE, SnsRestoreLogic.columns(), SnsRestoreLogic.CONFLICT_IGNORE,
        )
        assertTrue(sql.startsWith("INSERT OR IGNORE INTO SnsInfo ("))
        assertTrue(sql.endsWith(") VALUES (${Array(13) { "?" }.joinToString(",")})"))
        // 13 列 → 13 个占位符，一个不多一个不少（少一个就是拿 null 顶到错列上）
        assertEquals(13, sql.count { it == '?' })

        val rep = SnsSql.insertSql(SnsRestoreLogic.TABLE, listOf("snsId"), 5)
        assertEquals("INSERT OR REPLACE INTO SnsInfo (snsId) VALUES (?)", rep)
        // 空列清单不生成半截 SQL
        assertEquals("", SnsSql.insertSql(SnsRestoreLogic.TABLE, emptyList(), 4))
    }

    // ------------------------------------------------------------------ 危险行跳过

    @Test
    fun rejectReasonCatchesDangerousRows() {
        // 真机归档的正常行：可恢复
        assertNull(SnsRestoreLogic.rejectReason(realRow()))

        assertTrue("snsId" in SnsRestoreLogic.rejectReason(realRow(snsId = 0L))!!)
        assertTrue(
            "createTime" in SnsRestoreLogic.rejectReason(
                realRow().copy(createTime = 0L),
            )!!
        )
        // 正文两个 BLOB 全空 → 空洞行
        assertTrue(
            "content" in SnsRestoreLogic.rejectReason(
                realRow().copy(content = null, attrBuf = null, postBuf = null),
            )!!
        )
        // 只有 postBuf 不算正文
        assertNull(SnsRestoreLogic.rejectReason(realRow().copy(content = null, attrBuf = ByteArray(4))))
        assertTrue("userName" in SnsRestoreLogic.rejectReason(realRow().copy(userName = "  "))!!)
        assertTrue("userName" in SnsRestoreLogic.rejectReason(realRow().copy(userName = null))!!)
    }

    /** 危险行不进计划，也**不占** `--ei n` 的额度 */
    @Test
    fun planSkipsDangerousRowsWithoutSpendingBudget() {
        val rows = listOf(
            realRow(snsId = 1L), // 正常 → INSERT
            realRow(snsId = 0L), // 无主键 → 跳过
            realRow(snsId = 2L).copy(createTime = -1L), // 时间非法 → 跳过
            realRow(snsId = 3L), // 正常 → INSERT
        )
        val steps = SnsRestoreLogic.plan(rows, emptyMap(), limit = 2)
        assertEquals(listOf(1L, 3L), steps.map { it.snsId })
        for (s in steps) assertEquals(SnsRestoreLogic.Action.INSERT, s.action)
    }

    @Test
    fun partitionKeepsOrderAndReasons() {
        val rows = listOf(
            realRow(snsId = 1L),
            realRow(snsId = 2L).copy(content = null, attrBuf = null),
            realRow(snsId = 3L),
        )
        val (ok, bad) = SnsRestoreLogic.partition(rows)
        assertEquals(listOf(1L, 3L), ok.map { it.snsId })
        assertEquals(1, bad.size)
        assertEquals(2L, bad[0].first)
        assertTrue("content" in bad[0].second)
        assertEquals(SnsRestoreLogic.partition(rows), SnsRestoreLogic.partition(rows)) // 纯函数
    }

    // ------------------------------------------------------------------ 类型转换

    @Test
    fun coerceConvergesTypesAndDropsMismatches() {
        // 时间戳必须收敛成 Long（Int 会被存成 32 位，createTime 直接废）
        assertEquals(1760500000L, SnsRestoreLogic.coerce(SnsRestoreLogic.Kind.INT, 1760500000))
        assertEquals(-3447438253133393222L, SnsRestoreLogic.coerce(SnsRestoreLogic.Kind.INT, -3447438253133393222L))
        assertEquals(18L, SnsRestoreLogic.coerce(SnsRestoreLogic.Kind.INT, 18.toShort()))
        assertEquals(1L, SnsRestoreLogic.coerce(SnsRestoreLogic.Kind.INT, true))
        // 数字当文本：拼回去而不是丢
        assertEquals("1379", SnsRestoreLogic.coerce(SnsRestoreLogic.Kind.TEXT, 1379))
        // 类型对不上 → null，绝不能把字节流当字符串写进微信的库
        assertNull(SnsRestoreLogic.coerce(SnsRestoreLogic.Kind.TEXT, ByteArray(4)))
        assertNull(SnsRestoreLogic.coerce(SnsRestoreLogic.Kind.BLOB, "not-blob"))
        assertNull(SnsRestoreLogic.coerce(SnsRestoreLogic.Kind.INT, "abc"))
        // null 一律原样（SQLite 存 NULL，跟微信原行一致，不塞默认值）
        for (k in SnsRestoreLogic.Kind.values()) assertNull(SnsRestoreLogic.coerce(k, null))
    }

    @Test
    fun bindArgsKeepOrderAndNulls() {
        val row = realRow().copy(stringSeq = null, postBuf = null)
        val puts = SnsRestoreLogic.insertPuts(row)
        val args = SnsRestoreLogic.toBindArgs(puts)
        assertEquals(13, args.size)
        assertEquals(row.snsId, args[0])
        assertEquals(row.createTime, args[2]) // createTime（第 3 列），必须是 Long 不能是 Int
        assertEquals(withVisibleFlag(row.localFlag).toLong(), args[5]) // localFlag 补过可见位
        assertNull(args[9]) // stringSeq
        assertNull(args[12]) // postBuf
        assertTrue(args[10] is ByteArray) // content
        assertTrue(args[11] is ByteArray) // attrBuf
    }

    private fun withVisibleFlag(flag: Int): Int = flag or SnsRestoreLogic.VISIBLE_BIT
}
