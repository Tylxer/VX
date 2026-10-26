package com.lx.wxgroup.feature

/**
 * 朋友圈「时间线来源位」修复（srcfix）的**纯判据**（零 Android 依赖，JVM 单测跑）。
 *
 * ## 背景（已定案，见 `docs/_r27_srcfix_design.md`）
 * 朋友圈时间线的查询条件是 `where (sourceType & 2 != 0)`。好友删动态时微信**不删行**，
 * 只执行 `UPDATE SnsInfo SET sourceType = sourceType & -3`（`-3 = ~2`，只清 bit1）——
 * 行、`content`、`localFlag` 全都不动，但这一行从此不进时间线。
 *
 * ⇒ 修复 = 把 bit1 补回去（`sourceType | 2`）。
 *
 * ## 唯一可信判据：「这行曾经 bit1=1」
 * 光看「现在是 0」绝不能补：`src=0`（死来源）和 `src=4`（自己发的私密）**本来就不是 0 的
 * 那种 0**，无差别补位会把私密动态推到时间线（隐私事故）。
 * 所以判据是一张**持久化台账**（[com.lx.wxgroup.data.GroupDb] 的 `sns_visible_src`）：
 * 只在「亲眼见过这行 `& 2 != 0`」时登记；补位只认台账里的 id。
 *
 * ## 结构性硬保证（不是「小心」）
 * - [admit] 只放行 `& 2 != 0` ⇒ `src=4` / `src=0` / `src=128` 的行走不进台账；
 * - [shouldFix] 再判一次「当前 `& 4 == 0`」，私密位在就跳过（第二道独立保险）；
 * - 两条独立成立，任何一条都能挡下私密动态。
 */
object SnsTimelineSrcLogic {

    /** 时间线可见位（bit1）。微信时间线查询就是 `sourceType & 2 != 0` */
    const val TIMELINE_BIT = 2

    /** 自己发的私密位（bit2，`src=4`）。**永远不补** */
    const val PRIVATE_BIT = 4

    /** 单页扫描行数（键集分页，与 `SnsGuard.listCurrentSns` 同量级） */
    const val PAGE_SIZE = 100

    /** 单轮快照最多扫多少页（**上限**，防止首次运行把整表拖出来） */
    const val MAX_SNAPSHOT_PAGES = 30

    /** 单轮最多补位多少行（点查 + 写库，后台线程也要有界） */
    const val MAX_FIX_ROWS = 500

    /**
     * 明细日志上限。
     *
     * ⚠️ 微信 logcat / [com.lx.wxgroup.util.LogX] 的落盘都是 **5000 行环形缓冲**，
     * 一轮补位几千行会把「关键判据」全挤掉。超出预算的只计数，由聚合行交代总量。
     */
    const val MAX_DETAIL_LOGS = 50

    // ------------------------------------------------------------------ SQL 形态识别（方案 B）

    /**
     * 微信「批量清时间线来源位」那条 UPDATE 的形态：
     * `UPDATE SnsInfo SET sourceType = sourceType & -3 WHERE (sourceType & 2 != 0) AND (snsId != 0)`
     *
     * ⚠️ 匹配必须**宽松到能吃掉空格/大小写/括号**，但**必须严格到拒绝我们自己的补位 SQL**
     * （`sourceType = sourceType | 2`）——否则 `execSQL` 的 after 钩子会被自己触发，
     * 变成「补位 → 触发钩子 → 再补位」的无限递归。
     */
    private val CLEAR_SQL_RE =
        Regex("""sourcetype\s*=\s*\(?\s*sourcetype\s*&\s*\(?\s*-3\b""")

    /** 这条 SQL 是不是「微信在清 bit1」 */
    fun isSourceClearSql(sql: String?): Boolean {
        if (sql.isNullOrBlank()) return false
        val s = sql.lowercase()
        if (!s.contains("snsinfo")) return false
        return CLEAR_SQL_RE.containsMatchIn(s)
    }

    /**
     * 诊断用：这条 SQL 看起来像「改 `SnsInfo.sourceType`」，但没被 [isSourceClearSql] 认出来。
     *
     * 微信换版本改写法时（比如把 `& -3` 改成 `& 4294967293` 或走参数化），
     * 补位会**静默失效**。有了这个判据就能把真实 SQL 打出来一次，而不是靠猜。
     */
    fun looksLikeSourceWrite(sql: String?): Boolean {
        if (sql.isNullOrBlank()) return false
        val s = sql.lowercase()
        return s.contains("snsinfo") && s.contains("sourcetype") &&
            (s.contains("update") || s.contains(" set "))
    }

    /** 我们自己补位、回滚写的 SQL（`sourceType = sourceType | 2`）—— 采样时要排除，否则日志被自己刷满 */
    private val OWN_FIX_SQL_RE =
        Regex("""sourcetype\s*=\s*\(?\s*sourcetype\s*\|\s*\(?\s*2\b""")

    fun isOwnFixSql(sql: String?): Boolean {
        if (sql.isNullOrBlank()) return false
        val s = sql.lowercase()
        if (!s.contains("snsinfo")) return false
        return OWN_FIX_SQL_RE.containsMatchIn(s)
    }

    /** 台账准入：这一行**现在**可以被登记为「曾经时间线可见」吗 */
    fun admit(snsId: Long, sourceType: Int): Boolean =
        snsId != 0L && isTimelineVisible(sourceType)

    /** 台账准入的判据本体（供单测直接打） */
    fun isTimelineVisible(sourceType: Int): Boolean = (sourceType and TIMELINE_BIT) != 0

    /** 这一行当前是否在主时间线里 */
    fun isTimelineVisibleNow(sourceType: Int): Boolean = isTimelineVisible(sourceType)

    /** 私密位是否在（`src=4` / `6` 等） */
    fun isPrivate(sourceType: Int): Boolean = (sourceType and PRIVATE_BIT) != 0

    /**
     * 补位判据（**两条硬约束的落地点**）。
     *
     * @param current 库里该行**当前**的 sourceType
     * @param everVisible 台账里是否有「曾经 `& 2 != 0`」的记录
     * @return true 才允许写 `sourceType | 2`
     */
    fun shouldFix(current: Int, everVisible: Boolean): Boolean =
        everVisible && !isTimelineVisible(current) && !isPrivate(current)

    /** 补位要写的目标值（幂等：已可见时等于原值，写不出变化） */
    fun fixTarget(current: Int): Int = current or TIMELINE_BIT

    /**
     * 回滚要写的目标值。
     *
     * 优先还原台账里记下的**补位前原值**（`fixed_orig`）；没有记录（老台账 / 手工清过）时
     * 退化为「只清 bit1」—— 与微信自己的 `& -3` 语义一致，绝不动其它位。
     *
     * ⚠️ `orig` 若自己带 bit1（理论上不该有，补位前必然是 0），说明台账被写脏了，
     * 此时**不信 orig**：照它写回去等于没回滚。
     */
    fun rollbackTarget(current: Int, orig: Int?): Int =
        if (orig != null && !isTimelineVisible(orig)) orig else current and TIMELINE_BIT.inv()

    /**
     * 台账里这条记录的「曾经可见」判定（快照写入时用，读台账时也用它复核）。
     *
     * 与 [isTimelineVisible] 同一个判据，但**语义分开**：万一将来准入规则要加
     * `& 128`（广告位）过滤，只有准入变、历史记录的解释不变。
     */
    fun everVisible(ledgerSourceType: Int): Boolean = isTimelineVisible(ledgerSourceType)
}
