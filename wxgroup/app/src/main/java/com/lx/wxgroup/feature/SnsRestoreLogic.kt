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
 *
 * `SnsInfo` 实测 **18 列**，剩下 5 列**列名已由真机 `pragma table_info` 抓全**
 * （[UNARCHIVED]：`localPrivate` / `likeFlag` / `pravited` / `withTa` / `withTaHasOther`），
 * 13 + 5 = [TOTAL_COLUMNS]。
 *
 * ⚠️ **列名已知 ≠ 可以写入**：这 5 列归档里**一行都没存**（[ArchiveRow] 没有对应字段），
 * 插回去只能编一个值 —— 那比留 NULL 危险得多。所以它们**仍然不进 [KNOWN]**，
 * 由 SQLite 填默认/NULL。
 *
 * ⚠️ **INSERT 不会因为缺这 5 列而失败**（2026-10-18 真机 `snsg sub=cols` 实测）：
 * 18 列 `notNull` **全为 false**，`BLOCKING none`。[blockingColumns] 保留着当护栏 ——
 * 预期恒为空，一旦非空就说明微信改了表结构，那时候必须停下来重新取证，
 * 而不是照着老白名单硬写。
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

    /** `SnsInfo` 实测总列数（pragma 抓到的）= [KNOWN] 13 + [UNARCHIVED] 5 */
    const val TOTAL_COLUMNS = 18

    /** 批量上限：广播 `--ei n` 外人可控，防止一次把整库 BLOB 全捞出来（对齐 MomentsLogic.MAX_PROBE_N） */
    const val MAX_BATCH = 50

    // ------------------------------------------------------------------ 冲突算法

    /** 写到**哪个库**：决定 `insertWithOnConflict` 的第 4 个参数取几 */
    enum class Target {
        /** 微信的 `SnsInfo`（别人的活数据，写错不可逆） */
        WX_SNSINFO,

        /** 模块自己的 `sns_archive`（我们的数据，写错可以清库重来） */
        OWN_ARCHIVE,
    }

    /**
     * AOSP / wcdb `SQLiteDatabase` 的冲突算法枚举（真机反射调用传的就是这个 int）。
     * 全表：`NONE=0 ROLLBACK=1 ABORT=2 FAIL=3 IGNORE=4 REPLACE=5`。
     *
     * ⚠️ 别写 **1**：那是 `CONFLICT_ROLLBACK`，撞约束时它会**回滚整条语句所在的事务** ——
     * 我们是在微信自己的 db 实例上干活，滚掉的可能是微信刚写进去的另一条数据。
     */
    const val CONFLICT_IGNORE = 4
    const val CONFLICT_REPLACE = 5

    /**
     * 按目标库挑冲突算法。
     *
     * ⚠️ **写微信的 `SnsInfo` 必须是 4（IGNORE）**，不是 5：真机 DDL 实证 `SnsInfo.snsId`
     * 上没有唯一约束，一旦微信哪天加上了，5（REPLACE）会**先把用户那条活行删掉再插我们的**，
     * 那是从"恢复"变成"覆盖用户数据"。4 撞约束只是静默不写，最坏是"这条没恢复成功"。
     *
     * ⚠️ **写自己的 `sns_archive` 这里也返回 4**，跟 `GroupDb.insertSnsArchive`
     * （`GroupDb.kt:388`，注释写着"绝不能用 CONFLICT_REPLACE"）保持一致：
     * 归档是**首记录为准**的快照 —— 同一个 snsId 第二次归档时，第一次那份（删之前的原始状态）
     * 才是唯一可信的，用 5 覆盖掉它就再也找不回来了。
     * 若确实需要在归档上做"以新换旧"（比如重归档刷新 attrBuf），那是 **UPDATE**，
     * 不是换冲突算法 —— 别把这个常量改成 5 来"解决"。
     */
    fun conflictFor(target: Target): Int = when (target) {
        Target.WX_SNSINFO -> CONFLICT_IGNORE
        Target.OWN_ARCHIVE -> CONFLICT_IGNORE
    }

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
     * 真机 `pragma table_info(SnsInfo)` 抓到的**另外 5 列**（2026-10-18 `snsg sub=cols` 实证）。
     *
     * ⚠️ 只登记名字，**不加入 [KNOWN]、不写入**：归档行 [ArchiveRow] 里没有这 5 个字段，
     * 写进去只能凭空造值。让 SQLite 填默认/NULL 是唯一诚实的选择。
     *
     * ⚠️ `pravited` 是**微信自己的拼写错误**（不是 `privated`）—— 照抄，别「顺手改对」，
     *    改了就跟真机对不上了（跟 `snsID`/`snsId` 两表不一致是同一类坑）。
     */
    val UNARCHIVED: List<String> = listOf(
        "localPrivate",
        "likeFlag",
        "pravited",
        "withTa",
        "withTaHasOther",
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

    /** 会写进 INSERT 的列（= [KNOWN]），**不含** [UNARCHIVED] */
    fun isKnown(col: String): Boolean = KNOWN.any { it.name == col }

    /** 名字已知、但归档没存所以**故意不写**的列 */
    fun isUnarchived(col: String): Boolean = UNARCHIVED.contains(col)

    /** 白名单里没有的列数（= 我们没写、靠默认值的列数）。只用于日志提示，不参与判定 */
    fun unknownColumnCount(actualColumnCount: Int): Int =
        (actualColumnCount - KNOWN.size).coerceAtLeast(0)

    /** `pragma table_info` 的一行 */
    data class ColInfo(val name: String, val notNull: Boolean, val hasDefault: Boolean)

    /**
     * **「INSERT 只给部分列」的硬风险计算**：表里有哪些列是 NOT NULL 且无默认值、
     * 而我们又没在 [KNOWN] 里（即写不进去）→ 这些列会让 INSERT 直接失败。
     *
     * 2026-10-18 真机 `snsg sub=cols` 实测：**18 列 `notNull` 全为 false**，
     * 所以现在**预期恒返回空**（`BLOCKING none`）。它留着是当护栏：
     * 微信哪天给 `SnsInfo` 加了 `NOT NULL` 列，这里会立刻点名，
     * 那时候必须先补归档字段再恢复 —— **补列名只能靠真机 pragma，不靠猜**。
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
     * 真机 schema 里**我们没写**的列（正常情况就是 [UNARCHIVED] 那 5 列）。
     *
     * 想只看「微信新加的、我们不认识的列」请用 [alienColumns] ——
     * 本函数会把 [UNARCHIVED] 一起返回，那 5 列是正常的，不算异常。
     */
    fun unknownColumns(actual: Collection<String>): List<String> =
        actual.filter { !isKnown(it) }

    /**
     * **schema 漂移**：真机里出现了既不在 [KNOWN] 也不在 [UNARCHIVED] 的列。
     *
     * 正常情况恒为空。非空 = 微信加了列（或改了名），这时候必须回头看
     * [blockingColumns]（新列有没有 NOT NULL）和 [missingColumns]（老列还在不在），
     * 而不是照着老白名单继续写。
     */
    fun alienColumns(actual: Collection<String>): List<String> =
        actual.filter { !isKnown(it) && !isUnarchived(it) }

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
        Put("snsId", Kind.INT, coerce(Kind.INT, row.snsId)),
        Put("userName", Kind.TEXT, coerce(Kind.TEXT, row.userName)),
        Put("createTime", Kind.INT, coerce(Kind.INT, row.createTime)),
        Put("type", Kind.INT, coerce(Kind.INT, row.type)),
        Put("sourceType", Kind.INT, coerce(Kind.INT, row.sourceType)),
        Put("localFlag", Kind.INT, coerce(Kind.INT, withVisible(row.localFlag))),
        Put("subType", Kind.INT, coerce(Kind.INT, row.subType)),
        Put("serverExtFlag", Kind.INT, coerce(Kind.INT, row.serverExtFlag)),
        Put("head", Kind.TEXT, coerce(Kind.TEXT, row.head)),
        Put("stringSeq", Kind.TEXT, coerce(Kind.TEXT, row.stringSeq)),
        Put("content", Kind.BLOB, coerce(Kind.BLOB, row.content)),
        Put("attrBuf", Kind.BLOB, coerce(Kind.BLOB, row.attrBuf)),
        Put("postBuf", Kind.BLOB, coerce(Kind.BLOB, row.postBuf)),
    )

    /**
     * `[Put]` → `execSQL` 的 bindArgs（纯类型转换，null 原样留 null）。
     *
     * 为什么需要它：INSERT 走的是 `insertWithOnConflict(table, nullCV, ContentValues, conflict)`，
     * 但在 wcdb 上这个方法有时选不上（见 `SnsGuard.insertMethod`），得退回
     * `execSQL(sql, bindArgs)` —— 那时候 BLOB 必须还是 `ByteArray`、
     * 时间戳必须是 `Long`（传 `Int` 会被 wcdb 存成 32 位，createTime 直接废掉）。
     */
    fun toBindArgs(puts: List<Put>): Array<Any?> = Array(puts.size) { i -> puts[i].v }

    /**
     * 单个值按 [Kind] 收敛类型：INT → Long、TEXT → String、BLOB → ByteArray；
     * 值本身是 null 就返回 null（SQLite 存 NULL，跟微信原行一致，不塞默认值）。
     *
     * @return 收敛后的值；类型对不上（比如 TEXT 列拿到 ByteArray）返回 **null** ——
     *         宁可这一列存 NULL，也不能把字节流当字符串写进微信的库。
     */
    fun coerce(kind: Kind, v: Any?): Any? {
        if (v == null) return null
        return when (kind) {
            Kind.INT -> when (v) {
                is Long -> v
                is Int -> v.toLong()
                is Short -> v.toLong()
                is Boolean -> if (v) 1L else 0L
                is String -> v.toLongOrNull()
                else -> null
            }
            Kind.TEXT -> when (v) {
                is String -> v
                is Long, is Int, is Short -> v.toString()
                else -> null
            }
            Kind.BLOB -> v as? ByteArray
        }
    }

    // ------------------------------------------------------------------ 危险行跳过

    /**
     * 这条归档行**能不能插回 `SnsInfo`**。
     *
     * @return null = 可以恢复；非 null = 拒绝原因（直接进探针日志，所以要能看懂）
     *
     * ⚠️ 取的是"宁可漏恢复，不可写坏微信活数据"：判据宁可严一点。
     * 一条被跳过的行在界面上只是"没回来"，而一条写坏的（空壳 / 主键为 0 / 时间错乱）
     * 会**永久躺在微信的 `SnsInfo` 里**，而且 `snsId` 上没有唯一约束、
     * 回滚删除又只认"确认不在库里"的 id —— 那时候想清理只能手工开库删。
     */
    fun rejectReason(row: ArchiveRow): String? = when {
        row.snsId == 0L ->
            "snsId=0（无主键，插进去无法定位也无法回滚）"
        row.createTime <= 0L ->
            "createTime=${row.createTime}（时间线按它排序，非正值会排到 1970 且可能被清理语句扫掉）"
        row.content == null && row.attrBuf == null ->
            "content/attrBuf 全空（没有正文的空洞行，界面上会出白块；postBuf 单独有值不算正文）"
        row.userName.isNullOrBlank() ->
            "userName 为空（这条动态归属不到作者，插回去时间线渲染不出来）"
        else -> null
    }

    /** [rejectReason] 的布尔版 */
    fun isRestorable(row: ArchiveRow): Boolean = rejectReason(row) == null

    /**
     * 把归档行分成「能恢复」和「被跳过（带原因）」两堆。
     *
     * 顺序保持传入顺序（= 归档的 createTime DESC），被跳过的也保序，
     * 这样探针日志里打出来的顺序跟归档列表一一对应，人工核对时不用重新排序。
     */
    fun partition(rows: List<ArchiveRow>): Pair<List<ArchiveRow>, List<Pair<Long, String>>> {
        val ok = ArrayList<ArchiveRow>(rows.size)
        val bad = ArrayList<Pair<Long, String>>()
        for (r in rows) {
            val why = rejectReason(r)
            if (why == null) ok += r else bad += r.snsId to why
        }
        return ok to bad
    }

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
     * - [rejectReason] 非空的**危险行直接跳过**（不占额度，见上）；
     * - 顺序 = 归档传入顺序（`listSnsArchive` 是 create_time DESC，最新的先恢复）；
     * - [limit] 走 [clampBatch] 钳位（广播 `--ei n` 外人可控）。
     *
     * @param live snsId → 当前 localFlag；**不含某个 id 即表示行已不在**
     * @param unknown 预检查询**失败**的 snsId（[Presence.UNKNOWN]）。
     *
     * ⚠️ **[unknown] 里的行一律不进计划**。原因：它不在 [live] 里，`classify(null)` 会判成
     * [Action.INSERT] —— 但"查失败"不等于"行没了"。真机上表现为：行其实还在（只是可见位被清），
     * 我们却整行 INSERT 回去 → 撞主键（主键是 snsId）→ IGNORE 静默不写 →
     * **那条动态永远恢复不了，日志还看不出错**（比报错更坏）。
     * 对 UNKNOWN 我们唯一安全的选择是跳过，让下一次重试时重新判定
     * （预查失败的通常是一次性抖动，下一批就正常了）。
     */
    fun plan(
        rows: List<ArchiveRow>,
        live: Map<Long, Int>,
        limit: Int = MAX_BATCH,
        unknown: Set<Long> = emptySet(),
    ): List<Step> {
        val out = ArrayList<Step>(rows.size)
        val seen = HashSet<Long>()
        var left = clampBatch(limit)
        for (r in rows) {
            if (left <= 0) break
            if (!seen.add(r.snsId)) continue
            // ⚠️ 危险/残缺行不进计划，也不占额度（判据见 rejectReason）
            if (!isRestorable(r)) continue
            // ⚠️ 预查失败的行同样跳过，且不占额度（见上方 [unknown] 的说明）
            if (r.snsId in unknown) continue
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
     * 一行在**写库之前**的存在性三态。
     *
     * ⚠️ 必须三态、不能压成 Boolean：把 [UNKNOWN] 当成「不存在」是**致命**的 ——
     * 「不存在」意味着后面的 INSERT 造出来的就是新行，回滚可以按主键删掉它；
     * 而 [UNKNOWN] 意味着**预检查询本身失败了**（rawQuery 抛异常 / 返回空游标），
     * 库里到底有没有这一行**不知道**，这时候插进去的行没法证明是新的。
     */
    enum class Presence { PRESENT, ABSENT, UNKNOWN }

    /**
     * 本次批量里**允许按 snsId 从 `SnsInfo` 回滚删除**的 id 集合。
     *
     * 准入条件：步骤是 [Action.INSERT]，且该 snsId 在批量开始前被查成 [Presence.ABSENT]
     * （**确认不在库里**）—— 只有这种行才肯定是咱们这次插进去的。
     *
     * ⚠️ 为什么卡这么死：**`SnsInfo.snsId` 没有唯一约束**（2026-10-18 真机 DDL 实证，
     * 见 [SnsGuard.restoreVisibility] 上方的注释）：建表语句是
     * `CREATE TABLE SnsInfo ( snsId LONG, ...)`，`snsId` 既不是 PRIMARY KEY 也没有 UNIQUE，
     * 表上 6 个索引的 `unique` **全为 0**。
     *
     * 两个直接后果，都已经在别处按这个事实处理了，别按「有唯一约束」的假设改回去：
     * 1. **`CONFLICT_IGNORE` 拦不住重复插入** —— 它只在撞到唯一约束时才退，这里根本
     *    没有约束可撞。所以「插之前先查存在性」是**唯一**的去重手段，不是多余保险。
     * 2. `delete from SnsInfo where snsId=?` 会**把用户本来就有的那一行一起删掉**
     *    （同一 snsId 可以并存多行）—— 那不是回滚，是直接删掉用户看得见的动态。
     * 所以清单外的 id 一律不许删：宁可在微信库里留一行脏数据等人清理，也不能删错。
     *
     * @param steps 本次批量的步骤（传计划 [plan] 或已执行的步骤集都一样：
     *              只有 INSERT 且预检为 [Presence.ABSENT] 的才会被选中）
     * @param pre 批量开始前逐条查出来的存在性；查都没查过的 id 按 [Presence.UNKNOWN] 处理
     */
    fun undoableInserts(steps: List<Step>, pre: Map<Long, Presence>): Set<Long> =
        steps.filter { it.action == Action.INSERT && pre[it.snsId] == Presence.ABSENT }
            .map { it.snsId }
            .toSet()

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
