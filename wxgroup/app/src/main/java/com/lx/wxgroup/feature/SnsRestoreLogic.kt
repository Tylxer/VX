package com.lx.wxgroup.feature

/**
 * 「从归档恢复」（`sns_archive` → 微信 `SnsInfo`）的**纯判据**（零 Android 依赖，JVM 单测跑）。
 *
 * ## 干什么
 * 朋友圈防删除把被删动态整行存进模块自己的 `sns_archive`。反插就是把这一行
 * 原样写回微信的 `SnsInfo`，让动态重新出现在时间线里。这里只负责**判据**：
 * 写哪些列、这条要不要写、批量怎么排序、失败怎么回滚。真正落库在
 * [SnsGuard] 里（那里才有 wcdb 的 db 实例，且必须包 runCatching）。
 *
 * ## 列名的证据边界（铁律：不猜微信 API）
 * 下面 [KNOWN] 的 13 列**全部有实证**：`SnsGuard.readRow` 就是靠这些列名
 * `getColumnIndex` 逐列读出来再归档的，真机跑通过（docs/取证结论.md 朋友圈章节）。
 * 而 `SnsInfo` 实测 **18 列** —— 剩下 5 列**叫什么、什么约束，一律不知道**，
 * 记在 [TOTAL_COLUMNS] 里只做「还有 5 列没写」这个风险提示，**不编列名**。
 * 这 5 列若有 NOT NULL 且无默认值的，INSERT 会失败 —— 由 [blockingColumns]
 * 在拿到 `pragma table_info` 之后算出来，靠真机报错定位，不靠猜。
 *
 * ## 幂等
 * 同一条跑第二次必须什么也不做：行在且可见 → [Action.SKIP]。
 * 批量计划 [plan] 完全由「归档行 + 当前 localFlag」算出，不含时钟/随机数，
 * 所以**同样的输入一定得到同样的计划**（单测锁住）。
 */
object SnsRestoreLogic {

    /** 微信朋友圈动态表（真机实证，docs/取证结论.md 朋友圈第 1 条） */
    const val TABLE = "SnsInfo"

    /**
     * 时间线可见位（bit4）。
     * 微信查询是白名单枚举 `localFlag in (16,2064,18,2066,24,…)`，每个值都有 bit4。
     */
    const val VISIBLE_BIT = 16

    /** `SnsInfo` 实测总列数（pragma 抓到的），比 [KNOWN] 多出的部分是**待取证**列 */
    const val TOTAL_COLUMNS = 18

    /** 批量上限：广播 `--ei n` 外人可控，防止一次把整库 BLOB 全捞出来（对齐 MomentsLogic.MAX_PROBE_N） */
    const val MAX_BATCH = 50

    enum class Kind { INT, TEXT, BLOB }

    data class Col(val name: String, val kind: Kind)

    /**
     * 实证已知的 13 列，顺序即 [insertPuts] 的写入顺序。
     *
     * ⚠️ 这里的每一列都必须是 `SnsGuard.readRow` 里出现过的 —— 归档行只有这 13 个字段，
     * 写别的列就是凭空造数据（宁可让那 5 列走默认值，也不能瞎填）。
     */
    val KNOWN: List<Col> = listOf(
        Col("snsId", Kind.INT),
        Col("userName", Kind.TEXT),
        Col("createTime", Kind.INT),
        Col("type", Kind.INT),
        Col("sourceType", Kind.INT),
        Col("localFlag", Kind.INT),
        Col("subType", Kind.INT),
        Col("serverExtFlag", Kind.INT),
        Col("head", Kind.TEXT),
        Col("stringSeq", Kind.TEXT),
        Col("content", Kind.BLOB),
        Col("attrBuf", Kind.BLOB),
        Col("postBuf", Kind.BLOB),
    )

    /**
     * 归档行的**纯逻辑镜像**（对应 `GroupDb.SnsRow`，那边带 Android 依赖进不了单测）。
     *
     * 文本与 BLOB 允许 null：归档时列可能读不到（游标取空），插回去就是 SQLite NULL，
     * 跟微信原行一致 —— 强塞非空默认值反而会把数据改坏。
     */
    data class ArchiveRow(
        val snsId: Long,
        val userName: String?,
        val createTime: Long,
        val type: Int,
        val sourceType: Int,
        val localFlag: Int,
        val subType: Int,
        val serverExtFlag: Int,
        val head: String?,
        val stringSeq: String?,
        val content: ByteArray?,
        val attrBuf: ByteArray?,
        val postBuf: ByteArray?,
    )

    // ------------------------------------------------------------------ 列白名单

    fun columns(): List<String> = KNOWN.map { it.name }

    fun isKnown(col: String): Boolean = KNOWN.any { it.name == col }

    /** 白名单里没有的列数（= 我们没写、靠默认值的列数）。只用于日志提示，不参与判定 */
    fun unknownColumnCount(actualColumnCount: Int): Int =
        (actualColumnCount - KNOWN.size).coerceAtLeast(0)

    /** `pragma table_info` 的一行 */
    data class ColInfo(val name: String, val notNull: Boolean, val hasDefault: Boolean)

    /**
     * **「INSERT 只给部分列」的硬风险计算**：表里有哪些列是 NOT NULL 且无默认值、
     * 而我们又没在 [KNOWN] 里（即写不进去）→ 这些列会让 INSERT 直接失败。
     *
     * 这是 docs/取证结论.md 6.4「仍待取证」第 4 条点的坑。拿到 `pragma table_info`
     * 之后调它，返回非空就说明这条 INSERT 打底会挂，得先把列名补齐 ——
     * **补列名只能靠真机 pragma，不靠猜**，所以这里只负责把风险**算出来并暴露**。
     */
    fun blockingColumns(schema: List<ColInfo>): List<String> =
        schema.filter { it.notNull && !it.hasDefault && !isKnown(it.name) }.map { it.name }

    /**
     * **要写的列在真机 schema 里不存在**（致命漂移）：列名改了 / 表结构变了。
     * 非空就必须**立刻停手** —— 拿不存在的列名去 INSERT 是直接抛异常的。
     */
    fun missingColumns(actual: Collection<String>): List<String> =
        KNOWN.filter { !actual.contains(it.name) }.map { "MISSING ${it.name}" }

    /**
     * 真机 schema 里**我们没写**的列（正常情况就是那 5 列待取证的）。
     * 数量突然变多 = 微信加了列，回去看 [blockingColumns] 有没有 NOT NULL 的。
     */
    fun unknownColumns(actual: Collection<String>): List<String> =
        actual.filter { !isKnown(it) }

    // ------------------------------------------------------------------ 可见位

    fun isVisible(flag: Int): Boolean = flag and VISIBLE_BIT != 0

    /** 归档快照是微信清位后的值（恒 2），插回去/改回去必须把可见位补上 */
    fun withVisible(flag: Int): Int = flag or VISIBLE_BIT

    // ------------------------------------------------------------------ INSERT 参数映射

    data class Put(val col: String, val kind: Kind, val v: Any?)

    /**
     * 归档行 → INSERT 的一列一值。
     *
     * 唯一一处"改写"：[ArchiveRow.localFlag] 或上 [VISIBLE_BIT]。
     * 归档快照是微信删动态**第一步**（清可见位）之后读到的，恒为 2，
     * 原样插回去时间线照样查不出来 —— 这就是「恢复了但界面上没有」的坑。
     */
    fun insertPuts(row: ArchiveRow): List<Put> = listOf(
        Put("snsId", Kind.INT, row.snsId),
        Put("userName", Kind.TEXT, row.userName),
        Put("createTime", Kind.INT, row.createTime),
        Put("type", Kind.INT, row.type),
        Put("sourceType", Kind.INT, row.sourceType),
        Put("localFlag", Kind.INT, withVisible(row.localFlag)),
        Put("subType", Kind.INT, row.subType),
        Put("serverExtFlag", Kind.INT, row.serverExtFlag),
        Put("head", Kind.TEXT, row.head),
        Put("stringSeq", Kind.TEXT, row.stringSeq),
        Put("content", Kind.BLOB, row.content),
        Put("attrBuf", Kind.BLOB, row.attrBuf),
        Put("postBuf", Kind.BLOB, row.postBuf),
    )

    // ------------------------------------------------------------------ 幂等判定

    enum class Action {
        /** 行在且已经可见：什么都不做（幂等的关键） */
        SKIP,

        /** 行在但可见位被清了：只 update localFlag（不动内容，最安全） */
        SET_FLAG,

        /** 行已经没了：整行 INSERT 回去 */
        INSERT,

        /** 回滚：把刚 INSERT 进去的行**删掉**（恢复原状） */
        ROLLBACK_DELETE,

        /** 回滚：把刚改过的 localFlag 写回原值 */
        ROLLBACK_FLAG,
    }

    /**
     * 一个待执行步骤。
     *
     * @param fromFlag 改库**之前**的值（SET_FLAG / ROLLBACK_FLAG 用；INSERT 时恒 null）
     * @param toFlag 要写成的值（SET_FLAG / ROLLBACK_FLAG 用；INSERT 时恒 null）
     */
    data class Step(val snsId: Long, val action: Action, val fromFlag: Int?, val toFlag: Int?)

    /**
     * 单条幂等判定。
     *
     * @param liveFlag 微信库里**当前**的 localFlag；null = 行已经不在了
     */
    fun classify(liveFlag: Int?): Action = when {
        liveFlag == null -> Action.INSERT
        isVisible(liveFlag) -> Action.SKIP
        else -> Action.SET_FLAG
    }

    fun clampBatch(n: Int): Int = n.coerceIn(1, MAX_BATCH)

    /**
     * 批量计划。
     *
     * - 归档行**按 snsId 去重**（同一个 id 重复出现会写两次，第二次撞主键）；
     * - 顺序 = 归档传入顺序（`listSnsArchive` 是 create_time DESC，最新的先恢复）；
     * - [limit] 走 [clampBatch] 钳位（广播 `--ei n` 外人可控）。
     *
     * @param live snsId → 当前 localFlag；**不含某个 id 即表示行已不在**
     */
    fun plan(rows: List<ArchiveRow>, live: Map<Long, Int>, limit: Int = MAX_BATCH): List<Step> {
        val out = ArrayList<Step>(rows.size)
        val seen = HashSet<Long>()
        var left = clampBatch(limit)
        for (r in rows) {
            if (left <= 0) break
            if (!seen.add(r.snsId)) continue
            val cur: Int? = if (live.containsKey(r.snsId)) live[r.snsId] else null
            val a = classify(cur)
            if (a == Action.SKIP) continue
            left--
            out += when (a) {
                Action.INSERT -> Step(r.snsId, Action.INSERT, null, null)
                Action.SET_FLAG -> Step(r.snsId, Action.SET_FLAG, cur, withVisible(cur ?: 0))
                else -> error("plan: unexpected action $a")
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 回滚

    /**
     * 一个**已成功执行**的步骤对应的回滚步骤。
     *
     * - INSERT 成功 → 把插进去的行删掉（否则微信库里多一行我们自己造的数据）；
     * - SET_FLAG 成功 → 把 localFlag 写回 [Step.fromFlag]；
     * - SKIP 什么都没写 → 没有可回滚的。
     */
    fun rollbackOf(step: Step): Step? = when (step.action) {
        Action.INSERT -> Step(step.snsId, Action.ROLLBACK_DELETE, null, null)
        Action.SET_FLAG -> Step(step.snsId, Action.ROLLBACK_FLAG, step.fromFlag, step.toFlag)
        else -> null
    }

    /**
     * 回滚执行顺序：**倒序**（后写的先撤），且只撤真的改过库的步骤。
     *
     * 为什么不回滚到「批量开始前」：那要整表快照，成本大且更容易写坏微信库。
     * 倒序撤已成功步骤是等效的（每一步的逆操作都精确已知）。
     */
    fun rollbackOrder(done: List<Step>): List<Step> =
        done.asReversed().mapNotNull { rollbackOf(it) }

    // ------------------------------------------------------------------ 汇总

    data class Tally(
        val total: Int = 0,
        val inserted: Int = 0,
        val unhidden: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val rolledBack: Int = 0,
    ) {
        operator fun plus(o: Tally): Tally = Tally(
            total + o.total,
            inserted + o.inserted,
            unhidden + o.unhidden,
            skipped + o.skipped,
            failed + o.failed,
            rolledBack + o.rolledBack,
        )
    }

    /** 干跑（dry-run）摘要：只说打算干什么，不落库 */
    fun planSummary(steps: List<Step>): String {
        var ins = 0
        var set = 0
        for (s in steps) when (s.action) {
            Action.INSERT -> ins++
            Action.SET_FLAG -> set++
            else -> Unit
        }
        return "plan total=${steps.size} insert=$ins setFlag=$set"
    }

    fun summary(t: Tally): String =
        "total=${t.total} inserted=${t.inserted} unhidden=${t.unhidden} " +
            "skipped=${t.skipped} failed=${t.failed} rolledBack=${t.rolledBack}"
}
