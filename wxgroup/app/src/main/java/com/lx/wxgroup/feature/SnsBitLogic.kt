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

    /**
     * 两个可见位合起来的掩码（= 10）。写回公式与 after 形态判据共用，避免散落的魔数。
     * ⚠️ 写成字面量 10 而不是 `BIT_TIMELINE or BIT_ALBUM`：`const val` 只接受编译期常量，
     *    `Int.or` 是函数调用不能进 const。`2` 与 `8` 位不重叠 ⇒ 10 == 2|8 成立
     *    （[SnsBitLogicTest.visible_mask_is_the_union_of_the_two_bits] 断言锁住）。
     */
    const val VISIBLE_MASK = 10

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

    // ------------------------------------------------------------------ 写回值（R42 bit3 缺口）

    /**
     * 拦截成功那一次写入里，应该**写回** `ContentValues` 的 `sourceType` 值。
     *
     * 返回 `null` = 不写回（调用方降级为「只摘键」，与 R42 现状逐字一致）。
     *
     * ## 公式
     * `keep = old or VISIBLE_MASK`（= `old | 10`），即「库里旧值补上 bit1 与 bit3」。
     * 完整推导见 `docs/_r42_bit3_impl.md` §1，这里只留与代码同处一屏的三条要点。
     *
     * ## 为什么不是更省事的写法
     * 1. ⛔ 不是 `old or 8`（只补 bit3）：`old=8`（只在相册、时间线已不可见）时 `8|8=8`
     *    仍缺 bit1 ⇒ 时间线照样丢。拦截要保住**两个**可见位，故 bit1 也要补 ⇒ `8|10=10`。
     * 2. ⛔ 不是常量 `10`：`old` 可能带高位（`130=128|2` 折叠、`264=8|256`、`266=8|256|2`…），
     *    写死 10 会把这些位抹掉，等于替微信改了动态性质；`old|10` 保留 old 全部位、只补齐缺的。
     * 3. ⛔ `want`（微信想写的值）**绝不参与**公式：它是清位值，采用它的任何一位都等于替微信清位。
     *
     * ## 两个返回 null 的边界
     * 1. `old == null`：bits 读不到（无 id / 无句柄 / 无此行）⇒ 没有可补的基，凭空造 `10`
     *    等于**编造可见性** ⇒ 降级摘键，绝不凭空造位（硬约束 2）。
     * 2. `old & VISIBLE_MASK == 0`：「彻底不可见」——`0`（死来源 `isDeadSource()`）、`4`（私密）、
     *    `32`（广告）、`128` 等，本来时间线与主页都查不到；写 `old|10` 会把它变成可见 = **复活**。
     *    ⇒ 不写回，保持摘键、库里留原值。
     *    ⚠️ 判的是「两个可见位都没有」，**不是**「没有 bit3」：`old=2`（本轮缺口场景）
     *    `2&10=2≠0` ⇒ 必须写回 ⇒ `10`；`old=8` ⇒ `8&10=8≠0` ⇒ 写回 `10`。
     *
     * ## 幂等（不会越写越大）
     * `keep` 一定含 `VISIBLE_MASK` 且是 `old` 的超集；微信重试删除再次命中时 `old'=keep`，
     * `keepAliveSource(keep) = keep | VISIBLE_MASK = keep` ⇒ 值收敛。
     *
     * @param old 写库**前**库里的 `sourceType`；`null` = 查不到
     * @return 写回值；`null` = 降级只摘键
     */
    fun keepAliveSource(old: Int?): Int? {
        if (old == null) return null                  // 边界 1：bits 读不到 ⇒ 降级
        if ((old and VISIBLE_MASK) == 0) return null  // 边界 2：不复活彻底不可见
        return old or VISIBLE_MASK
    }

    // ------------------------------------------------------------------ after 形态（写回后互斥判据）

    /**
     * 拦截后 after 钩子对 ContentValues 形态的**互斥**分类（见 impl 文档 §2）。
     *
     * ⚠️ 这是 R42 bit3 新增的**额外自证维度**：`SnsBlockProbe` 用四个**新**计数器独立表达，
     * ⛔ **不影响 `afterOk`/`afterFail`**（那两个严格保持 HEAD 旧语义，只由回查
     * `bits != null` 决定）。两个维度正交、互不覆盖。
     */
    enum class AfterShape {
        /** 拦住了·摘键：ContentValues 里读不到 `sourceType`（降级 / 不复活路径） */
        BLOCKED_BY_REMOVE,

        /** 拦住了·写回：ContentValues 里 `sourceType` == 我们写回的保底值 */
        BLOCKED_BY_REWRITE,

        /** 没拦住：ContentValues 里 `sourceType` 仍是微信原本要写的 `want` */
        NOT_BLOCKED,

        /** 形态异常（既不是 want 也不是 keep）：必须单独告警，不许静默归到任一边 */
        UNKNOWN,
    }

    /**
     * after 半场的**互斥形态判据**（R42 bit3 新增的额外自证维度）。
     *
     * ⚠️ **前提更正**：写回值**不会**破坏原有 after 校验链路 —— `pending` 是 ThreadLocal
     *    事件对象，`hasPending()`→`finishAfterWrite{readBits}` 与 ContentValues 里有没有
     *    `sourceType` 键完全无关；`isBlockedValues`/`blockedCvs` 是零调用点死代码、
     *    `onSnsUpdateAfter` 是空函数。本判据不是「修好被写回弄坏的判据」，而是给
     *    「这次到底摘键还是写回」一个**互斥自证**，并订正旧日志「(键已摘)」的假话。
     *
     * @param want 微信原本想写入的值（写 0 路径 = 0；降位路径 = 2/8…）
     * @param keep 本次写回值；`null` = 没走写回路径（降级 / 不复活 / albumFix 调试开关关）
     * @param observed after 时从 ContentValues 实际读到的 `sourceType`；`null` = 键读不到
     *
     * 四类两两互斥（证明见 `docs/_r42_bit3_impl.md` §2）：
     * - `observed == null` 与其余三类（都要非 null）天然互斥；
     * - 第 2 类显式要求 `keep != want`，排除与第 3 类（`observed==want`）重叠的情形；
     * - 第 4 类是前三者的补集。
     *
     * ⇒ 「拦住了」={1,2}、「没拦住」={3}、{4} 单独告警：**不误报**（记了 BLOCKED 却没改动
     * ContentValues 会落 `NOT_BLOCKED`，被 `shapeNotBlocked` 计数 + MISMATCH 告警抓出）、
     * **不漏报**（`UNKNOWN` 不静默吞）。⛔ 全程不碰 `afterOk`/`afterFail`。
     */
    fun afterShape(want: Int, keep: Int?, observed: Int?): AfterShape = when {
        observed == null -> AfterShape.BLOCKED_BY_REMOVE
        keep != null && keep != want && observed == keep -> AfterShape.BLOCKED_BY_REWRITE
        observed == want -> AfterShape.NOT_BLOCKED
        else -> AfterShape.UNKNOWN
    }

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
