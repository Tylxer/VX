package com.lx.wxgroup.feature

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * R42 · 路线甲实测：朋友圈「写时拦截 `sourceType`」的**事件账本 + 通道计数 + 后台采样器**。
 *
 * ## 要回答的三个问题（见 `docs/_r42_block_impl.md`）
 * - **W1**：摘掉 `sourceType` 后微信会不会重新校正回去、多久 ⇒ [trace] 每 30 秒点查一次；
 * - **W2**：清位是否**只有** `updateWithOnConflict` 一条通道 ⇒ update / insert / delete /
 *   execSQL 四路各自独立计数（⛔ 不合成一个总数，合成后就看不出是哪条通道）；
 * - **W3**：`ContentValues` 是整行 upsert 还是只带改动列 ⇒ [Event.keys] + hasContent/hasAttr。
 *
 * ## 设计约束
 * - **纯内存**：不建表、不写盘。进程死了账本就没了 —— 实测窗口内够用，且不会污染用户库。
 * - **不碰主链路**：所有入口都由调用方 `runCatching` 包住；本对象内部再包一层，
 *   任何异常只 `LogX.w`，绝不向微信的写库路径抛。
 * - **日志限流**：`LogX` 是 5000 行环形，`updateWithOnConflict` 是微信**所有表**的热路径，
 *   放开打会把关键判据挤掉。所以：
 *   - 同一 `snsId` 的 `BLOCK hit` **5 秒内只记一次**（纯节流，不丢计数）；
 *   - `BLOCK stat` 汇总最多 **60 秒一条**；
 *   - execSQL 原文采样**去重 + 上限 20 条**。
 *   ⛔ 计数不去重 —— 去重只作用于「重复打同一行日志」，四个计数器始终各自累加。
 */
object SnsBlockProbe {

    private const val TABLE = "SnsInfo"

    /** 内存账本保留最近多少个事件（判据要能看见「最近发生了什么」，不是只看见最后一秒） */
    private const val RING = 50

    /** `sub=blockstat` 最多打最近几条事件（配合 4 行计数，总行数 ≤ 16） */
    private const val EV_SHOW = 10

    /** 同一 snsId 的拦截日志最小间隔（纯节流） */
    private const val HIT_LOG_GAP_MS = 5_000L

    /** 汇总日志最小间隔 */
    private const val STAT_GAP_MS = 60_000L

    /** execSQL 原文采样上限（去重后）。R42 取证：需存下 `& -9` 等未识别模板的完整 WHERE，20 → 60 */
    private const val SQL_SAMPLE_MAX = 60

    /** 未识别 SQL 的落盘长度。R42 取证：200 会在 `IN (...)` 列表中间截断，看不到尾部条件 */
    private const val SQL_LOG_CHARS = 1500

    /** 采样器间隔 / 上限（30s × 60 = 30 分钟） */
    private const val TRACE_EVERY_MS = 30_000L
    private const val TRACE_MAX = 60

    // ------------------------------------------------------------------ 计数
    // ⛔ 每一项都单独报，不合成「总数」：合成之后就分不清「没命中」和「命中了但被跳过」。

    /** `sourceType=0` 且 table=SnsInfo 的次数（**不论开关、不论后来是否真拦**） */
    private val hit = AtomicInteger()

    /** 真的从 ContentValues 里摘掉 `sourceType` 的次数 */
    private val blocked = AtomicInteger()

    /** `sourceType != 0` 的写（正常行写 2 / 10；这类**不是**清位） */
    private val nonZero = AtomicInteger()

    /** 命中但开关关闭（关/开对照实验靠它） */
    private val skipOff = AtomicInteger()

    /** 摘完 values 变空 ⇒ 把原值放回、放弃拦截（宁可不拦也不能让 update 抛异常） */
    private val skipEmpty = AtomicInteger()

    /** ContentValues 上找不到 `remove` 方法（wcdb 形态变化） */
    private val skipNoApi = AtomicInteger()

    /** 命中但抠不出 snsId（照样拦，只是事件账本里 id=0） */
    private val skipNoId = AtomicInteger()

    /** `sourceType` 取到了但类型不认识 */
    private val parseFail = AtomicInteger()

    /** 这次 update 的 ContentValues 里根本没有 `sourceType`（绝大多数正常写） */
    private val updNoSrcKey = AtomicInteger()

    // W2：UPDATE 通道的取值分布（与上面的 hit 有重叠，这里只服务「通道是否唯一」的论证）
    private val updSeen = AtomicInteger()
    private val updZero = AtomicInteger()

    // R42 方案 A：`sourceType != 0` 的**降位**（`10→8` 清 bit1 / `10→2` 清 bit3）。
    // ⛔ 与上面的 hit（写 0）分开计：合成一个数就看不出「新加的两条判据到底命中没有」。
    private val dropSeen = AtomicInteger()     // 进入降位判定（含最终放行的）
    private val dropBlocked = AtomicInteger()  // 真的摘了键
    private val dropOff = AtomicInteger()      // 命中但开关关闭
    private val dropAllowed = AtomicInteger()  // 查了旧值，证明是加位/原地 ⇒ 放行
    private val dropUnknown = AtomicInteger()  // 抠不出 id / 查不到旧值 ⇒ 无法判定，放行
    private val dropEmpty = AtomicInteger()    // 摘完 values 空 ⇒ 放回
    private val dropNoApi = AtomicInteger()    // 没有 remove 方法

    /** 「降位判定抠不出 id」只告警一次，之后只计数（热路径不能刷日志） */
    private val dropUnknownWarned = AtomicBoolean(false)

    // W2：INSERT 通道
    private val insSeen = AtomicInteger()
    private val insZero = AtomicInteger()
    private val insNonZero = AtomicInteger()
    private val insNoKey = AtomicInteger()

    // W2：DELETE 通道
    private val delSns = AtomicInteger()
    private val delRow = AtomicInteger()

    // W2：execSQL 通道
    private val sqlSns = AtomicInteger()      // SQL 里含 SnsInfo
    private val sqlSrc = AtomicInteger()      // 且看起来在改 sourceType
    private val sqlClear = AtomicInteger()    // 且命中 `sourceType & -3` 形态（清 bit1）—— ⛔ 只识别不拦
    private val sqlClear9 = AtomicInteger()   // 且命中 `sourceType & -9` 形态（清 bit3）—— 方案 B 起可拦
    private val sqlOwn = AtomicInteger()      // 且是我们自己补位写的
    private val sqlUnmatched = AtomicInteger()// 且是改 sourceType 但没被上面两类认出来 ⭐ 有它才叫「抓出别的通道」

    // R42 方案 B：`& -9` 的**拦截**记账。⛔ 与 sqlClear9（识别数）分开：
    // 识别了但没拦（开关关 / 改写失败）必须能单独看见，否则「开关开了却没效果」查不出来。
    private val sqlBlock9 = AtomicInteger()     // 真的改写为 no-op 并放行执行
    private val sqlBlock9Off = AtomicInteger()  // 命中但开关关闭（原样执行）
    private val sqlBlock9Fail = AtomicInteger() // 命中、开关开启，但改写没产生变化 ⇒ 原样执行（= 没拦住，非 0 即故障）

    /** 同一 snsId 区间的被拦次数：>1 就是微信在对同一批行**重试**的证据 */
    private val block9Ranges = HashMap<String, Int>()

    @Volatile
    private var block9RangeMaxKey = "-"

    private val block9RangeMax = AtomicInteger()

    /** 区间表上限：分页区间是长尾的，不能无界增长（进程内只做判据，丢新 key 可接受） */
    private const val RANGE_MAX = 64

    // 拦截后 after 校验（R42 bit3 交叉复核后收敛）：
    // ⛔ afterOk / afterFail **严格保持 HEAD 旧语义** —— 只由 readBits 是否取到 bits 决定，
    //    一个自增条件都没动。它们是历史报告与反向对照里判断成败的唯一依据，不许被覆盖。
    // R42 bit3 新增的四个 shape 计数是与它**正交的额外维度**（ContentValues 形态互斥分类），
    //    独立表达，绝不碰上面两个计数器。见 [finishAfterWrite]。
    private val afterOk = AtomicInteger()
    private val afterFail = AtomicInteger()
    private val shapeRemoved = AtomicInteger()
    private val shapeRewritten = AtomicInteger()
    private val shapeNotBlocked = AtomicInteger()
    private val shapeUnknown = AtomicInteger()

    // R42 self-exclude：作者是自己 ⇒ 彻底放行（不保位、不加前缀）。
    // ⛔ 写 0 与降位**分开计**（合成一个数就分不清是哪条通道放行的）。
    // ⛔ 独立新计数器，绝不碰 blocked / skipOff / afterOk / afterFail / shape*。
    private val selfExcluded = AtomicInteger()     // 写 0 路径的自己删除
    private val selfExcludedDrop = AtomicInteger() // 降位路径的自己删除

    // ------------------------------------------------------------------ 事件账本

    /** 一次「微信要清位」的现场快照 */
    class Event internal constructor(
        val snsId: Long,
        /** 写库**前**库里的 sourceType（现查；`?` = 查不到） */
        val before: String,
        /** 微信这次**想写入**的值（`0` = 旧的「清位」路径；`2`/`8` = 新增的降位路径） */
        val want: String,
        /** [want] 的 Int 形态 —— [SnsBitLogic.afterShape] 的入参，避免 after 里再解析字符串 */
        val wantInt: Int,
        /** 本次写回 ContentValues 的保底值；`null` = 没走写回路径（降级 / 不复活） */
        val keep: Int?,
        /** ContentValues 的 key 数量 —— W3 的核心证据（2~5 个 ⇒ 只带改动列；十几二十个 ⇒ 整行 upsert） */
        val keys: Int,
        val hasContent: Boolean,
        val hasAttr: Boolean,
        val blocked: Boolean,
        val clock: String,
        val lf: String,
        val sef: String,
        val ct: String,
    ) {
        /** 写库**后**回查到的 sourceType（`?` = 没回查/查不到） */
        @Volatile
        var after: String = "?"

        /** after 半场 [SnsBitLogic.afterShape] 的互斥分类结果（`?` = 还没跑） */
        @Volatile
        var afterShape: String = "?"

        fun line(): String =
            "BLOCK ev id=$snsId at=$clock before=$before want=$want keep=${keep ?: "-"} keys=$keys " +
                "C=${if (hasContent) 1 else 0} A=${if (hasAttr) 1 else 0} " +
                "blk=${if (blocked) 1 else 0} after=$after shape=$afterShape lf=$lf sef=$sef ct=$ct"
    }

    private val events = ArrayList<Event>(RING + 1)

    /**
     * 本次 update 里被我们拦下的事件（只用于让 after 钩子回查一次）。
     *
     * ⚠️ **R42 bit3 写回改造 / 前提更正**：旁边原来还有一份 `blockedCvs` 身份集合 +
     * `isBlockedValues(cv)`，配套旧 KDoc 里的「读不到 sourceType ⇒ 甲已生效」形态观察
     * （`docs/_r42_block_impl.md` §2 偏差 2）。它们**全项目零调用点**、`onSnsUpdateAfter()`
     * 也是空函数 ⇒ 死代码，本轮删除（不是因为它们被写回「弄坏」了）。
     *
     * ⚠️ **写回值不会让 after 校验链路失效**：[pending] 是 ThreadLocal 事件对象，
     * [hasPending] / [finishAfterWrite] 与 ContentValues 里有没有 `sourceType` 键**完全无关**。
     * 真正的 after 校验仍是 `finishAfterWrite` 回查 `readBits`，且 `afterOk`/`afterFail`
     * 严格保持 HEAD 旧语义（只由 `bits != null` 决定）。
     *
     * 额外新增 [SnsBitLogic.afterShape] 互斥形态判据（四个独立新计数器）用于**自证**与
     * 订正旧日志口径，与回查维度正交、互不覆盖。见 `docs/_r42_bit3_impl.md` §2。
     */
    private val pending = ThreadLocal<Event?>()

    private val lastHitMs = AtomicLong(0L)
    private val lastStatEl = AtomicLong(0L)
    private val hitLogAt = HashMap<Long, Long>()
    private val afterLogAt = HashMap<Long, Long>()

    // ------------------------------------------------------------------ 结果枚举

    /** 一次「sourceType=0 命中」的最终去向（⛔ 每一种都要能被单独报出来） */
    enum class Outcome {
        BLOCKED,     // 摘掉了
        OFF,         // 开关关闭，只记账
        SKIP_EMPTY,  // 摘完 values 空 ⇒ 放回、不拦
        SKIP_NO_API, // 没有 remove / put 方法
        SKIP_NO_ID,  // 抠不出 snsId（仍会拦，见 onBlocked）
        SELF,        // 作者是自己 ⇒ 彻底放行（不保位、不加前缀），让微信的写原样落地
    }

    /**
     * 一条 `& -9` 裸 SQL 的**实际去向**（R42 方案 B）。
     *
     * ⛔ 四个值必须分开报：「识别到了」与「真拦住了」是两件事，
     * 合成一个 bool 会让「开关开了但改写没生效」永远查不出来。
     */
    enum class SqlBlock {
        /** 不是 `& -9` 形态（绝大多数 execSQL） */
        NONE,

        /** 已改写为 no-op 并放行执行（`sourceType & -1`） */
        BLOCKED,

        /** 命中但开关关闭 ⇒ 原样执行（A/B 对照的那个 B） */
        OFF,

        /** 命中、开关开启，但改写没产生变化 ⇒ 原样执行。⚠️ 非 0 = 拦截失效，必须查 */
        FAILED,
    }

    // ------------------------------------------------------------------ 入口：UPDATE 命中

    /** 这次 update 的 ContentValues 里没有 `sourceType`（绝大多数正常写，只计数） */
    fun noteUpdateNoSrcKey() {
        updNoSrcKey.incrementAndGet()
        updSeen.incrementAndGet()
    }

    /** `sourceType != 0`：正常行写 2/10，也包含「微信把我们补的位打回 2」那种写 */
    fun noteUpdateNonZero() {
        nonZero.incrementAndGet()
        updSeen.incrementAndGet()
    }

    fun noteParseFail() {
        parseFail.incrementAndGet()
    }

    fun noteSkipNoApi() {
        skipNoApi.incrementAndGet()
    }

    /** 摘完 values 变空 ⇒ 放回原值、放弃拦截。[isDrop] 决定记到哪一套计数里 */
    fun noteSkipEmpty(snsId: Long, isDrop: Boolean) {
        if (isDrop) dropEmpty.incrementAndGet() else skipEmpty.incrementAndGet()
        LogX.w("BLOCK skip: empty values snsId=$snsId isDrop=$isDrop (sourceType 已放回，本条未拦)")
    }

    /** 降位判定时抠不出 snsId（⇒ 查不到旧值 ⇒ 放行）。首次打一行，之后只计数。 */
    fun noteDropUnknown() {
        dropSeen.incrementAndGet()
        dropUnknown.incrementAndGet()
        if (dropUnknownWarned.compareAndSet(false, true)) {
            LogX.w("BLOCK drop: 抠不出 snsId，无法判定旧值 ⇒ 本条放行（之后同类只计数）")
        }
    }

    /** 查了旧值，证明是加位/原地重写 ⇒ 放行（这是「加位必须放行」的正面证据） */
    fun noteDropAllowed() {
        dropSeen.incrementAndGet()
        dropAllowed.incrementAndGet()
    }

    /**
     * 记录一次命中的现场。
     *
     * @param cv 本次被拦的 ContentValues（只有 [Outcome.BLOCKED] 时非空，用于给 after 配对；
     *           ⚠️ 身份集合已随写回改造删除，这里只借它判「非空」来 set [pending]）
     * @param newValue 微信想写入的值；`null` = 写 0 路径（旧语义），`!= 0` = 降位路径
     * @param keep 本次写回 ContentValues 的保底值；`null` = 没走写回路径（降级 / 不复活）。
     *             ⚠️ 由 [SnsGuard.applyBlock] 用 [SnsBitLogic.keepAliveSource] 算出后传入，
     *             after 的 [SnsBitLogic.afterShape] 靠它区分「写回生效」与「没拦住」
     * @return 记进账本的事件
     */
    fun record(
        snsId: Long,
        bits: Bits?,
        keys: Int,
        hasContent: Boolean,
        hasAttr: Boolean,
        outcome: Outcome,
        cv: Any? = null,
        newValue: Int? = null,
        keep: Int? = null,
    ): Event {
        val isDrop = newValue != null && newValue != 0
        if (isDrop) {
            // updSeen 已在 noteUpdateNonZero() 里加过，这里不重复
            dropSeen.incrementAndGet()
        } else {
            hit.incrementAndGet()
            updSeen.incrementAndGet()
            updZero.incrementAndGet()
        }
        val nowMs = System.currentTimeMillis()
        lastHitMs.set(nowMs)
        val e = Event(
            snsId = snsId,
            before = bits?.src?.toString() ?: "?",
            want = (newValue ?: 0).toString(),
            wantInt = newValue ?: 0,
            keep = keep,
            keys = keys,
            hasContent = hasContent,
            hasAttr = hasAttr,
            blocked = outcome == Outcome.BLOCKED,
            clock = hms(nowMs),
            lf = bits?.lf?.toString() ?: "?",
            sef = bits?.sef?.toString() ?: "?",
            ct = bits?.createTime?.toString() ?: "?",
        )
        when (outcome) {
            Outcome.BLOCKED -> {
                if (isDrop) dropBlocked.incrementAndGet() else blocked.incrementAndGet()
                if (snsId == 0L) skipNoId.incrementAndGet()
                if (cv != null) pending.set(e)
                logBlockedThrottled(e, isDrop)
            }
            Outcome.OFF -> if (isDrop) dropOff.incrementAndGet() else skipOff.incrementAndGet()
            Outcome.SKIP_EMPTY -> { /* 计数已在 noteSkipEmpty 里加过 */ }
            Outcome.SKIP_NO_API -> if (isDrop) dropNoApi.incrementAndGet() else skipNoApi.incrementAndGet()
            Outcome.SKIP_NO_ID -> if (isDrop) dropUnknown.incrementAndGet() else skipNoId.incrementAndGet()
            Outcome.SELF -> {
                if (isDrop) selfExcludedDrop.incrementAndGet() else selfExcluded.incrementAndGet()
                logSelfExcludedThrottled(e, isDrop)
            }
        }
        synchronized(events) {
            events.add(e)
            while (events.size > RING) events.removeAt(0)
        }
        maybeStat()
        return e
    }

    /**
     * 拦截的 after 半场：**回查库里值（HEAD 旧语义）** + **互斥形态判定（新增正交维度）**。
     *
     * ## 两个正交维度，谁都不覆盖谁（交叉复核底线）
     * 1. **回查维度**：`bits == null` ⇒ `afterFail++` / `e.after="?"`；`bits != null` ⇒
     *    `afterOk++` / `e.after=bits.src`。**与 HEAD 逐字一致**，自增条件与位置一个都没动。
     * 2. **形态维度**：纯函数 [SnsBitLogic.afterShape] 把 ContentValues 现值分四类，
     *    用 `shapeRemoved / shapeRewritten / shapeNotBlocked / shapeUnknown` 四个**新**计数器
     *    独立表达，⛔ 绝不碰 `afterOk/afterFail`。
     *
     * ## 为什么写回值**不会**让 after 校验链路失效（team-lead 前提更正）
     * `pending` 是 ThreadLocal 事件对象，[hasPending] / 本函数与 ContentValues 里有没有
     * `sourceType` 键**完全无关**；`onSnsUpdateAfter` 是空函数、`isBlockedValues`/`blockedCvs`
     * 全项目零调用点（死代码）。所以这里不需要「修好」任何被写回破坏的东西 ——
     * 旧 KDoc（`SnsGuard.kt:218`）和旧日志「(键已摘)」是**过时口径**，本轮一并订正：
     * 写回路径日志报 `act=writeback`（降级路径 `act=remove`），并带 `shape=` 自证分类。
     *
     * 四类两两互斥（证明见 `docs/_r42_bit3_impl.md` §2）：`observed==null` 与其余天然互斥；
     * 第 2 类显式要求 `keep != want`，排除与第 3 类重叠；第 4 类是补集 ⇒ 不误报、不漏报。
     * `NOT_BLOCKED` 是**防御性守门**：before 记了 BLOCKED 却没真改 ContentValues 时命中，单列告警。
     *
     * @param observedSrc after 时从 ContentValues 读到的 `sourceType`；`null` = 键读不到
     */
    fun finishAfterWrite(observedSrc: Int?, reader: (Long) -> Bits?) {
        val e = pending.get() ?: return
        pending.remove()
        val shape = SnsBitLogic.afterShape(e.wantInt, e.keep, observedSrc)
        e.afterShape = shape.name
        val bits = runCatching { reader(e.snsId) }.getOrNull()
        // 一次事件只消耗一次节流额度：回查日志 / readback 告警 / 形态日志共用，
        // 避免互相把对方挤掉（沿用 HEAD 的 5s/id 节流，只作用于日志、不作用于计数）
        val canLog = allowLog(afterLogAt, e.snsId)
        val act = if (e.keep != null) "writeback" else "remove"

        // ---- ① 回查维度：afterOk/afterFail 严格保持 HEAD 旧语义（只由 bits 决定）----
        if (bits == null) {
            afterFail.incrementAndGet()
            e.after = "?"
            // ⚠️ 节流：snsId=0 的行必然查不到，重试风暴下不能每轮刷一行
            if (canLog) {
                LogX.w(
                    "BLOCK after snsId=${e.snsId} shape=$shape act=$act readback failed (src=?)"
                )
            }
        } else {
            afterOk.incrementAndGet()
            e.after = bits.src.toString()
            // 旧日志写「(键已摘)」—— 写回路径下这是假话，改成按真实动作报 act=
            if (canLog) {
                LogX.i(
                    "BLOCK after snsId=${e.snsId} before=${e.before} after=${bits.src} " +
                        "keep=${e.keep ?: "-"} act=$act shape=$shape (写回保位 / 摘键降级)"
                )
            }
        }

        // ---- ② 形态维度：四个新计数器独立表达，绝不碰 afterOk/afterFail ----
        when (shape) {
            SnsBitLogic.AfterShape.BLOCKED_BY_REMOVE -> shapeRemoved.incrementAndGet()
            SnsBitLogic.AfterShape.BLOCKED_BY_REWRITE -> shapeRewritten.incrementAndGet()
            SnsBitLogic.AfterShape.NOT_BLOCKED -> {
                shapeNotBlocked.incrementAndGet()
                if (canLog) {
                    LogX.w(
                        "BLOCK after MISMATCH snsId=${e.snsId} shape=$shape 没拦住：want=${e.want} " +
                            "keep=${e.keep ?: "-"} observed=$observedSrc（ContentValues 仍是微信原值）"
                    )
                }
            }
            SnsBitLogic.AfterShape.UNKNOWN -> {
                shapeUnknown.incrementAndGet()
                if (canLog) {
                    LogX.w(
                        "BLOCK after UNKNOWN snsId=${e.snsId} shape=$shape want=${e.want} " +
                            "keep=${e.keep ?: "-"} observed=$observedSrc（形态异常，未归到任一边）"
                    )
                }
            }
        }
    }

    /** 当前线程是否有待回查的拦截事件（after 钩子用它决定要不要查库，热路径第一句） */
    fun hasPending(): Boolean = pending.get() != null

    /** 丢弃待回查标记（异常/提前退出时避免污染下一次调用） */
    fun clearPending() {
        pending.remove()
    }

    private fun logBlockedThrottled(e: Event, isDrop: Boolean) {
        if (!allowLog(hitLogAt, e.snsId)) return
        // 写 0 与降位分开打标签：一份日志里混着两种动作时，靠 tag 就能分开数
        val tag = if (isDrop) "drop" else "hit"
        LogX.i(
            "BLOCK $tag snsId=${e.snsId} before=${e.before} want=${e.want} keys=${e.keys} " +
                "hasContent=${e.hasContent} hasAttr=${e.hasAttr} at=${e.clock}"
        )
    }

    /** 自己删除的动态：作者 == self ⇒ 彻底放行。日志只做留痕，计数已由 [Outcome.SELF] 分支累加 */
    private fun logSelfExcludedThrottled(e: Event, isDrop: Boolean) {
        if (!allowLog(hitLogAt, e.snsId)) return
        val tag = if (isDrop) "drop" else "hit"
        LogX.i(
            "BLOCK self $tag snsId=${e.snsId} before=${e.before} want=${e.want} " +
                "（自己删除，彻底放行：不保位、不加前缀）"
        )
    }

    /**
     * 同一 snsId 的同类日志 5 秒内只放行一次。
     *
     * ⚠️ 只是**日志节流**，计数和事件账本照旧每条都记 —— 微信对同一条 update 失败重试
     * 可以到几百次（防撤回那边实测 5 秒 360 次），不节流会把 5000 行环形日志冲干净。
     */
    private fun allowLog(map: HashMap<Long, Long>, snsId: Long): Boolean {
        val el = SystemClock.elapsedRealtime()
        val last = synchronized(map) { map[snsId] }
        if (last != null && el - last < HIT_LOG_GAP_MS) return false
        synchronized(map) {
            map[snsId] = el
            if (map.size > 128) {
                val it = map.entries.iterator()
                while (it.hasNext()) if (el - it.next().value >= HIT_LOG_GAP_MS) it.remove()
            }
        }
        return true
    }

    private fun maybeStat() {
        val now = SystemClock.elapsedRealtime()
        val last = lastStatEl.get()
        if (last != 0L && now - last < STAT_GAP_MS) return
        if (!lastStatEl.compareAndSet(last, now)) return
        LogX.i(
            "BLOCK stat hit=${hit.get()} blocked=${blocked.get()} " +
                "nonZero=${nonZero.get()} " +
                "drop=${dropSeen.get()}/${dropBlocked.get()} " +
                "last=${hms(System.currentTimeMillis())}"
        )
    }

    // ------------------------------------------------------------------ 入口：其它三个通道（W2）

    /** INSERT：新动态首次落库。清位理论上不走这里，这里再证一次 */
    fun noteInsert(hasSourceType: Boolean, src: Int?) {
        insSeen.incrementAndGet()
        if (!hasSourceType) {
            insNoKey.incrementAndGet()
            return
        }
        when {
            src == null -> insNoKey.incrementAndGet()
            src == 0 -> insZero.incrementAndGet()
            else -> insNonZero.incrementAndGet()
        }
    }

    /** DELETE：历史上我们认为朋友圈删除「先清位、再删行」，这里再证一次零 DELETE */
    fun noteDelete(where: String?, snsId: Long?) {
        delSns.incrementAndGet()
        if (snsId != null) delRow.incrementAndGet()
        if (delSns.get() <= 5) {
            LogX.i("BLOCK w2 delete snsId=${snsId ?: 0} where=${where?.take(200) ?: "null"}")
        }
    }

    /**
     * execSQL：清位的**另一条通道**（方案 B 起，`& -9` 在这里被**改写为 no-op**）。
     *
     * ⛔ 本函数仍然只记账：不碰 SQL、不影响执行、不决定拦不拦。
     * 「改不改写」是 [SnsGuard] 在调用本函数**之前**做完的（改写要改 `param.args`），
     * 这里只接收结果 [block] 并分类计数 —— 这样账本和实际行为不会各说一套。
     *
     * @param clearBit1 命中 `sourceType = sourceType & -3` 形态（清时间线位）—— ⛔ 只识别不拦
     * @param clearBit3 命中 `sourceType = sourceType & -9` 形态（清相册位）—— 方案 B 的拦截对象
     * @param own 我们自己补位/回滚写的（`| 2` / `| 10`），要单独计，否则会把噪音当微信行为
     * @param block 这条 SQL 的实际去向（见 [SqlBlock]）
     */
    fun noteSql(
        sql: String,
        isSourceWrite: Boolean,
        clearBit1: Boolean,
        clearBit3: Boolean,
        own: Boolean,
        block: SqlBlock = SqlBlock.NONE,
    ) {
        sqlSns.incrementAndGet()
        if (!isSourceWrite) return
        sqlSrc.incrementAndGet()
        // 两种清位各自独立计数：一条 SQL 同时含 `& -3` 和 `& -9` 时两个都加（不互斥）
        if (clearBit1) sqlClear.incrementAndGet()
        if (clearBit3) {
            sqlClear9.incrementAndGet()
            val tag = when (block) {
                SqlBlock.BLOCKED -> {
                    sqlBlock9.incrementAndGet()
                    noteBlock9Range(SnsSqlRewrite.rangeKey(sql))
                    "已拦 -9→${SnsSqlRewrite.NOOP_MASK}"
                }
                SqlBlock.OFF -> {
                    sqlBlock9Off.incrementAndGet()
                    "未拦·开关关"
                }
                SqlBlock.FAILED -> {
                    sqlBlock9Fail.incrementAndGet()
                    "未拦·改写失败"
                }
                SqlBlock.NONE -> "未拦·未到拦截线"
            }
            // 原文仍是**微信原本要执行的**那条 —— 判据要靠它，不能存改写后的
            if (sampleSql(sql, sqlSamples9)) {
                val rng = SnsSqlRewrite.rangeKey(sql) ?: "?"
                LogX.w("BLOCK w2 sql-clear9($tag) rng=$rng ${sql.take(SQL_LOG_CHARS)}")
            }
        }
        if (clearBit1 || clearBit3) return
        if (own) {
            sqlOwn.incrementAndGet()
        } else {
            sqlUnmatched.incrementAndGet()
            if (sampleSql(sql, sqlSamples)) {
                LogX.w("BLOCK w2 sql-unmatched ${sql.take(SQL_LOG_CHARS)}")
            }
        }
    }

    /** 同一 snsId 区间的被拦次数；>1 说明微信在对同一批行重试（不是我们改坏了，就是它本来就这么干） */
    private fun noteBlock9Range(key: String?) {
        val k = key ?: "?"
        var n = 0
        synchronized(block9Ranges) {
            if (block9Ranges.containsKey(k) || block9Ranges.size < RANGE_MAX) {
                n = (block9Ranges[k] ?: 0) + 1
                block9Ranges[k] = n
            }
        }
        if (n > block9RangeMax.get()) {
            block9RangeMax.set(n)
            block9RangeMaxKey = k
        }
    }

    /** 原文采样：去重 + 上限 [SQL_SAMPLE_MAX] 条。返回 true = 这条是新的、值得打一行 */
    private fun sampleSql(sql: String, into: MutableList<String>): Boolean = synchronized(into) {
        if (into.size >= SQL_SAMPLE_MAX) return false
        if (into.contains(sql)) return false
        into.add(sql)
        true
    }

    private val sqlSamples = ArrayList<String>()

    /** `& -9` 的原文样本单独存：不能被 `sql-unmatched` 的 60 条配额挤掉 */
    private val sqlSamples9 = ArrayList<String>()

    // ------------------------------------------------------------------ 读回查

    /** 一行的可见位快照。`userName` 曾供灰标「排除自己」用，灰标已删、现无消费点，
     *  但保留（多读一列零成本），避免改动 [readBits] 触及 bit3 计数/after 校验路径。 */
    class Bits(
        val src: Int,
        val lf: Int,
        val sef: Int,
        val createTime: Long,
        val userName: String?,
    )

    /**
     * 点查一条的 `sourceType / localFlag / serverExtFlag / createTime`。
     *
     * ⚠️ `rawQuery` 的 Method **按 `db.javaClass` 现找、不跨库缓存** —— AOSP 的 Method
     * 拿去调 wcdb 实例会抛 `IllegalArgumentException`（R27 踩过，静默失败）。
     * 失败一律返回 null，不抛。
     */
    fun readBits(db: Any?, snsId: Long): Bits? {
        if (db == null || snsId == 0L) return null
        val m = rawQueryMethod(db) ?: run {
            warnOnce("BLOCK read: no rawQuery on ${db.javaClass.name}")
            return null
        }
        val sql = "select sourceType,localFlag,serverExtFlag,createTime,userName from $TABLE where snsId=$snsId"
        val cur = runCatching { m.invoke(db, sql, null) }.getOrNull() ?: return null
        return try {
            if (XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean != true) return null
            Bits(
                src = longAt(cur, 0).toInt(),
                lf = longAt(cur, 1).toInt(),
                sef = longAt(cur, 2).toInt(),
                createTime = longAt(cur, 3),
                userName = textAt(cur, 4),
            )
        } catch (t: Throwable) {
            warnOnce("BLOCK read err: ${t.javaClass.simpleName}")
            null
        } finally {
            runCatching { XposedHelpers.callMethod(cur, "close") }
        }
    }

    private fun longAt(cur: Any, i: Int): Long =
        runCatching { XposedHelpers.callMethod(cur, "getLong", i) as? Long ?: 0L }.getOrDefault(0L)

    private fun textAt(cur: Any, i: Int): String? =
        runCatching { XposedHelpers.callMethod(cur, "getString", i) as? String }.getOrNull()

    private fun rawQueryMethod(db: Any): Method? = runCatching {
        db.javaClass.methods.firstOrNull {
            it.name == "rawQuery" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                (it.parameterTypes[1] == Array<String>::class.java ||
                    it.parameterTypes[1] == Array<Any>::class.java)
        }
    }.getOrNull()

    private val warned = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun warnOnce(msg: String) {
        if (warned.size < 8 && warned.add(msg)) LogX.w(msg)
    }

    // ------------------------------------------------------------------ 开关

    /**
     * `op=snsg sub=block --es sub2 on|off`
     *
     * R42 路线甲的**调试覆盖开关**：主开关 [Keys.ANTIREVOKE_SNS] 负责**写时拦截**，
     * 本开关在未显式设置时跟随主开关（`SnsGuard.blockEnabled =
     * Kv.getBool(ANTIREVOKE_SNS) || Kv.getBool(ANTIREVOKE_SNS_BLOCK)`）。
     */
    fun switch(arg: String?): String {
        val a = arg?.trim()?.lowercase()
        if (a != "on" && a != "off") {
            return "BLOCK switch bad arg='${arg.orEmpty()}' (want on|off) " +
                "now=${Kv.getBool(Keys.ANTIREVOKE_SNS_BLOCK)}"
        }
        Kv.putBool(Keys.ANTIREVOKE_SNS_BLOCK, a == "on")
        SnsGuard.refresh()
        val now = Kv.getBool(Keys.ANTIREVOKE_SNS_BLOCK)
        return "BLOCK switch ${if (now) "on" else "off"} readback=$now"
    }

    // ------------------------------------------------------------------ 统计输出

    /** `op=snsg sub=blockstat`：5 行计数 + 最近 10 个事件，**总行数 ≤ 16** */
    fun stat(): List<String> {
        val out = ArrayList<String>(16)
        out += "BLOCK stat hit=${hit.get()} blocked=${blocked.get()} nonZero=${nonZero.get()} " +
            "skipOff=${skipOff.get()} last=${lastHitText()}"
        out += "BLOCK skip empty=${skipEmpty.get()} noApi=${skipNoApi.get()} noId=${skipNoId.get()} " +
            "parseFail=${parseFail.get()} afterOk=${afterOk.get()} afterFail=${afterFail.get()} " +
            "shape=rm:${shapeRemoved.get()} rw:${shapeRewritten.get()} " +
            "nb:${shapeNotBlocked.get()} un:${shapeUnknown.get()}"
        out += "BLOCK drop seen=${dropSeen.get()} blocked=${dropBlocked.get()} " +
            "off=${dropOff.get()} allow=${dropAllowed.get()} unknown=${dropUnknown.get()} " +
            "empty=${dropEmpty.get()} noApi=${dropNoApi.get()}"
        out += "BLOCK self zero=${selfExcluded.get()} drop=${selfExcludedDrop.get()}"
        out += "BLOCK upd seen=${updSeen.get()} zero=${updZero.get()} nosrc=${updNoSrcKey.get()} | " +
            "ins seen=${insSeen.get()} zero=${insZero.get()} nz=${insNonZero.get()} nosrc=${insNoKey.get()}"
        val rngSize = synchronized(block9Ranges) { block9Ranges.size }
        out += "BLOCK w2 delSns=${delSns.get()} delRow=${delRow.get()} " +
            "sqlSns=${sqlSns.get()} sqlSrc=${sqlSrc.get()} sqlClear=${sqlClear.get()} " +
            "sqlClear9=${sqlClear9.get()} sqlBlock9=${sqlBlock9.get()} " +
            "blkOff=${sqlBlock9Off.get()} blkFail=${sqlBlock9Fail.get()} " +
            "rng=$rngSize max=${block9RangeMax.get()}x@$block9RangeMaxKey " +
            "sqlOwn=${sqlOwn.get()} sqlUnmatched=${sqlUnmatched.get()}"
        val snapshot = synchronized(events) { ArrayList(events) }
        val from = (snapshot.size - EV_SHOW).coerceAtLeast(0)
        for (i in from until snapshot.size) out += snapshot[i].line()
        return out.take(16)
    }

    private fun lastHitText(): String {
        val ms = lastHitMs.get()
        return if (ms == 0L) "-" else hms(ms)
    }

    // ------------------------------------------------------------------ 后台采样器（W1）

    @Volatile
    private var traceId = 0L

    @Volatile
    private var traceHandler: Handler? = null

    @Volatile
    private var traceThread: HandlerThread? = null

    private val traceTicks = AtomicInteger(0)

    /**
     * `op=snsg sub=trace --el id <snsId>`：每 30 秒点查一次这条的
     * `sourceType / localFlag / serverExtFlag`，最多 60 次（30 分钟）。
     *
     * 这是 **W1 的唯一判据**：拦截后如果微信有校正机制，一定会在这 30 分钟里出现
     * `src` 从「原值」变成 0/2/10 的某一跳 —— 那就是校正发生的时间点。
     *
     * `id = 0` 停止；同一 id 只允许一个采样器，再次调用直接替换。
     * ⚠️ 只在内存里跑，进程死了就没了（可接受）。
     */
    fun trace(id: Long): List<String> {
        val had = stopTrace()
        if (id == 0L) {
            return listOf(if (had) "TRACE stopped" else "TRACE stopped (no sampler was running)")
        }
        val t = HandlerThread("sns-trace").apply { start() }
        val h = Handler(t.looper)
        traceThread = t
        traceHandler = h
        traceId = id
        traceTicks.set(0)
        h.post { traceTick(id) }
        return listOf("TRACE started id=$id every=${TRACE_EVERY_MS / 1000}s max=$TRACE_MAX")
    }

    private fun traceTick(id: Long) {
        runCatching {
            if (traceId != id) return
            val n = traceTicks.incrementAndGet()
            val clock = hms(System.currentTimeMillis())
            val bits = readBits(SnsGuard.snsDbHandle(), id)
            if (bits == null) {
                LogX.i("TRACE $clock id=$id src=? lf=? sef=? (no db / no row)")
            } else {
                LogX.i("TRACE $clock id=$id src=${bits.src} lf=${bits.lf} sef=${bits.sef}")
            }
            if (n >= TRACE_MAX) {
                LogX.i("TRACE done id=$id n=$n (30min 采样结束)")
                stopTrace()
                return
            }
            // stopTrace() 会把 handler 置空 ⇒ 这里再取一次，停掉后不会再排下一轮
            traceHandler?.postDelayed({ traceTick(id) }, TRACE_EVERY_MS)
        }.onFailure {
            LogX.w("TRACE err ${it.javaClass.simpleName}: ${it.message?.take(60)}")
            // 出错也别静默停：下一轮照常排（除进程死了）
            traceHandler?.postDelayed({ traceTick(id) }, TRACE_EVERY_MS)
        }
    }

    /** @return true = 真的停掉了一个在跑的采样器 */
    fun stopTrace(): Boolean {
        val t = traceThread ?: return false
        traceId = 0L
        traceThread = null
        traceHandler?.removeCallbacksAndMessages(null)
        traceHandler = null
        runCatching { t.quitSafely() }
            .onFailure { LogX.w("TRACE quit err ${it.javaClass.simpleName}") }
        return true
    }

    /** `HH:mm:ss` —— 每次调用新建 formatter（调用点都是低频路径，别为它加锁） */
    private fun hms(ms: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
}
