package com.lx.wxgroup.feature

/**
 * R42 · `SnsInfo.sourceType` 位判据（**纯函数**，不 import 任何 Android / Xposed 类）。
 *
 * 抽出来的唯一目的：这条判据写错的方向是「静默失效」（该拦的放行了，或者把加位也拦了
 * 导致动态进不了相册），两者都要等换机实测才发现。放在纯 JVM 里才能用单测守住。
 *
 * ## 位语义（`docs/_r42_bit_paths.md` §1）
 * - `bit1(2)` = 朋友圈**时间线**可见；
 * - `bit3(8)` = 该动态**当前在好友主页相册列表里**。
 *
 * ## 为什么判据必须吃「旧值」
 * 只看新值分不清同一个 `2` 是哪种动作：
 * - `10 → 2`（清 bit3，进主页对账）⇒ **必须拦**；
 * - `0 → 2`（加 bit1，新动态可见）⇒ **必须放行**。
 * 两者的新值都是 `2`，唯一的区别就是旧值。同理 `10 → 8` 与 `0 → 8`。
 *
 * ## 判定规则
 * 1. `new == 0` ⇒ 一律拦（`2→0` / `8→0`，与旧实现逐字一致）；
 * 2. `old == null`（查不到/id 抠不出）⇒ 放行（证明不了是降位，宁可不动）；
 * 3. **纯降位**（新值是旧值的子集，即这次写不引入任何新位）且丢掉 bit1 或 bit3 ⇒ 拦；
 * 4. 其余一律放行（加位、原地重写、**混合改写**）。
 *
 * ⚠️ 规则 3 加了「纯降位」这个前提，不是多余的保守：`8→2` 会丢 bit3 但**同时加回
 * bit1**，属于混合改写 —— 拦掉它等于把动态按在「只在相册、时间线仍不可见」上，
 * 与「保住可见性」的目标正好相反。混合改写不在用户枚举里，一律不动。
 */
object SnsBitLogic {

    /** 时间线可见位。清它 = 朋友圈时间线翻不到。 */
    const val BIT_TIMELINE = 2

    /** 好友主页相册位。清它 = 主页相册查不到（`WHERE sourceType IN (8,264,10,...)` 全含它）。 */
    const val BIT_ALBUM = 8

    enum class Action {
        /** 放行：加位 / 原地重写 / 旧值不可知 */
        ALLOW,

        /** 拦：这次写会摘掉一个可见位 */
        BLOCK,
    }

    /**
     * 这次写**是否可能**摘掉可见位 —— 用于在查库之前做一次零 IO 的快速排除。
     *
     * `new` 同时含 bit1 + bit3（如 `10`）时一定没有丢位，可以直接放行；
     * 只有缺了某一位才需要读旧值来确认「是旧值本来就没有（加位/原地）还是被摘掉」。
     *
     * @return true = 需要查旧值；false = 不看旧值也能判定为放行
     */
    fun needsOld(new: Int): Boolean =
        new != 0 && ((new and BIT_TIMELINE) == 0 || (new and BIT_ALBUM) == 0)

    /**
     * 核心判据：这次 `updateWithOnConflict` 对 `sourceType` 的写入该不该拦。
     *
     * @param old 写库**前**库里的 `sourceType`；`null` = 查不到（无 id / 无句柄 / 无此行）
     * @param new `ContentValues` 里要写入的值
     */
    fun decide(old: Int?, new: Int): Action = when {
        // ① 写 0：旧实现原样保留 —— 这一条**不依赖**旧值，查不到也拦（宁多拦不漏拦）
        new == 0 -> Action.BLOCK
        // ② 旧值不可知：证明不了是降位，放行（不能靠猜去改微信的写库参数）
        old == null -> Action.ALLOW
        // ③ 纯降位（新值不引入任何新位）且丢掉某个可见位 ⇒ 拦
        isPureDowngrade(old, new) &&
            (loses(old, new, BIT_TIMELINE) || loses(old, new, BIT_ALBUM)) -> Action.BLOCK
        // ④ 加位 / 原地重写 / 混合改写
        else -> Action.ALLOW
    }

    /** 新值是旧值的子集 = 这次写只删位、不添位 */
    private fun isPureDowngrade(old: Int, new: Int): Boolean = (new and old.inv()) == 0

    private fun loses(old: Int, new: Int, bit: Int): Boolean =
        (old and bit) != 0 && (new and bit) == 0

    // ------------------------------------------------------------------ execSQL 形态

    /** `sourceType = sourceType & -3`（`-3` = `~2`）⇒ 清 bit1，时间线丢失 */
    private val CLEAR_BIT1_RE =
        Regex("""sourcetype\s*=\s*\(?\s*sourcetype\s*&\s*\(?\s*-3\b""")

    /** `sourceType = sourceType & -9`（`-9` = `~8`）⇒ 清 bit3，好友主页相册丢失 */
    private val CLEAR_BIT3_RE =
        Regex("""sourcetype\s*=\s*\(?\s*sourcetype\s*&\s*\(?\s*-9\b""")

    /** 我们自己补位/回滚写的 SQL（`sourceType = sourceType | 10`）—— 采样时要排除 */
    private val OWN_FIX_RE =
        Regex("""sourcetype\s*=\s*\(?\s*sourcetype\s*\|\s*\(?\s*(?:10|2)\b""")

    /**
     * 一条 SQL 的清位形态。⛔ 只做**识别**，不参与任何「跳过执行/改参数/改 WHERE」的决定。
     *
     * `BOTH` 不是凑数：真机上出现过同一条 UPDATE 里两处 `&` 的模板，合成一个 bool
     * 会让「`-9` 到底有没有执行」这件事重新变成不可分辨。
     */
    enum class SqlClear(val clearsBit1: Boolean, val clearsBit3: Boolean) {
        NONE(false, false),
        TIMELINE(true, false),
        ALBUM(false, true),
        BOTH(true, true),
    }

    /** @return 该 SQL 是否含 `sourceType` 的清位写法；非 `SnsInfo` 一律 [SqlClear.NONE] */
    fun sqlClear(sql: String?): SqlClear {
        if (sql.isNullOrBlank()) return SqlClear.NONE
        val s = sql.lowercase()
        if (!s.contains("snsinfo")) return SqlClear.NONE
        val b1 = CLEAR_BIT1_RE.containsMatchIn(s)
        val b3 = CLEAR_BIT3_RE.containsMatchIn(s)
        return when {
            b1 && b3 -> SqlClear.BOTH
            b1 -> SqlClear.TIMELINE
            b3 -> SqlClear.ALBUM
            else -> SqlClear.NONE
        }
    }

    /** 这条 SQL 看起来像「改 `SnsInfo.sourceType`」，但没被 [sqlClear] 认出来 */
    fun looksLikeSourceWrite(sql: String?): Boolean {
        if (sql.isNullOrBlank()) return false
        val s = sql.lowercase()
        return s.contains("snsinfo") && s.contains("sourcetype") &&
            (s.contains("update") || s.contains(" set "))
    }

    /** 我们自己补位/回滚写的 SQL（`sourceType = sourceType | 10`）—— 采样时要排除 */
    fun isOwnFix(sql: String?): Boolean {
        if (sql.isNullOrBlank()) return false
        val s = sql.lowercase()
        if (!s.contains("snsinfo")) return false
        return OWN_FIX_RE.containsMatchIn(s)
    }
}
