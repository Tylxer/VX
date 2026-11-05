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

    /** execSQL 原文采样上限（去重后） */
    private const val SQL_SAMPLE_MAX = 20

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
    private val sqlClear = AtomicInteger()    // 且命中 `sourceType & -3` 形态
    private val sqlOwn = AtomicInteger()      // 且是我们自己补位写的
    private val sqlUnmatched = AtomicInteger()// 且是改 sourceType 但没被上面两类认出来 ⭐ 有它才叫「抓出别的通道」

    // 拦截后立刻回查（证明摘键真的生效：写完之后库里还是原值）
    private val afterOk = AtomicInteger()
    private val afterFail = AtomicInteger()

    // ------------------------------------------------------------------ 事件账本

    /** 一次「微信要清位」的现场快照 */
    class Event internal constructor(
        val snsId: Long,
        /** 写库**前**库里的 sourceType（现查；`?` = 查不到） */
        val before: String,
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

        fun line(): String =
            "BLOCK ev id=$snsId at=$clock before=$before keys=$keys " +
                "C=${if (hasContent) 1 else 0} A=${if (hasAttr) 1 else 0} " +
                "blk=${if (blocked) 1 else 0} after=$after lf=$lf sef=$sef ct=$ct"
    }

    private val events = ArrayList<Event>(RING + 1)

    /** 本次 update 里被我们摘了键的 ContentValues（只用于让 after 钩子回查一次） */
    private val pending = ThreadLocal<Event?>()

    /**
     * 最近被我们摘过键的 ContentValues 身份。
     *
     * 用途：`SnsGuard.onSnsUpdateAfter` 里 `sourceTypeOf(values) == null` 原本要打一条
     * 「形态变了、补位停用」的告警 —— 拦截模式下这句是**误报**（键是我们自己摘的）。
     * 用身份比对把它排除掉，比按时间窗猜更准。
     */
    private val blockedCvs = ArrayList<Any>(8)

    private fun rememberBlockedCv(cv: Any) {
        synchronized(blockedCvs) {
            blockedCvs.add(cv)
            while (blockedCvs.size > 8) blockedCvs.removeAt(0)
        }
    }

    /** 这个 ContentValues 是不是刚被本钩子摘掉过 `sourceType` */
    fun isBlockedValues(cv: Any): Boolean = synchronized(blockedCvs) {
        blockedCvs.any { it === cv }
    }

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

    fun noteSkipEmpty(snsId: Long) {
        skipEmpty.incrementAndGet()
        LogX.w("BLOCK skip: empty values snsId=$snsId (sourceType 已放回，本条未拦)")
    }

    /**
     * 记录一次 `sourceType=0` 的命中。
     *
     * @param cv 被摘键的 ContentValues（只有 [Outcome.BLOCKED] 时非空，用于事后回查配对）
     * @return 记进账本的事件；`null` = 参数不成立（不会发生，防御用）
     */
    fun record(
        snsId: Long,
        bits: Bits?,
        keys: Int,
        hasContent: Boolean,
        hasAttr: Boolean,
        outcome: Outcome,
        cv: Any? = null,
    ): Event {
        hit.incrementAndGet()
        updSeen.incrementAndGet()
        updZero.incrementAndGet()
        val nowMs = System.currentTimeMillis()
        lastHitMs.set(nowMs)
        val e = Event(
            snsId = snsId,
            before = bits?.src?.toString() ?: "?",
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
                blocked.incrementAndGet()
                if (snsId == 0L) skipNoId.incrementAndGet()
                if (cv != null) {
                    rememberBlockedCv(cv)
                    pending.set(e)
                }
                logHitThrottled(e)
            }
            Outcome.OFF -> skipOff.incrementAndGet()
            Outcome.SKIP_EMPTY -> { /* 计数已在 noteSkipEmpty 里加过 */ }
            Outcome.SKIP_NO_API -> { }
            Outcome.SKIP_NO_ID -> skipNoId.incrementAndGet()
        }
        synchronized(events) {
            events.add(e)
            while (events.size > RING) events.removeAt(0)
        }
        maybeStat()
        return e
    }

    /**
     * 拦截的 after 钩子：立刻回查这条的 `sourceType`，证明「键摘掉后库里没被改成 0」。
     *
     * ⚠️ 必须在 after 里做，不能放 before：before 里查到的还是这次写之前的旧值，
     * 证明不了「清位没落地」。
     */
    fun finishAfterWrite(reader: (Long) -> Bits?) {
        val e = pending.get() ?: return
        pending.remove()
        val bits = runCatching { reader(e.snsId) }.getOrNull()
        if (bits == null) {
            afterFail.incrementAndGet()
            e.after = "?"
            // ⚠️ 同样要节流：snsId=0 的行必然查不到，重试风暴下不能每轮刷一行
            if (allowLog(afterLogAt, e.snsId)) {
                LogX.w("BLOCK after snsId=${e.snsId} readback failed (src=?)")
            }
        } else {
            afterOk.incrementAndGet()
            e.after = bits.src.toString()
            if (allowLog(afterLogAt, e.snsId)) {
                LogX.i("BLOCK after snsId=${e.snsId} before=${e.before} after=${bits.src} (键已摘)")
            }
        }
    }

    /** 当前线程是否有待回查的拦截事件（after 钩子用它决定要不要查库，热路径第一句） */
    fun hasPending(): Boolean = pending.get() != null

    /** 丢弃待回查标记（异常/提前退出时避免污染下一次调用） */
    fun clearPending() {
        pending.remove()
    }

    private fun logHitThrottled(e: Event) {
        if (!allowLog(hitLogAt, e.snsId)) return
        LogX.i(
            "BLOCK hit snsId=${e.snsId} before=${e.before} keys=${e.keys} " +
                "hasContent=${e.hasContent} hasAttr=${e.hasAttr} at=${e.clock}"
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
                "nonZero=${nonZero.get()} last=${hms(System.currentTimeMillis())}"
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
     * execSQL：清位的**另一条候选通道**。
     *
     * @param clear 命中 `sourceType = sourceType & -3` 形态
     * @param own 我们自己补位/回滚写的（`| 2` / `| 10`），要单独计，否则会把噪音当微信行为
     */
    fun noteSql(sql: String, isSourceWrite: Boolean, clear: Boolean, own: Boolean) {
        sqlSns.incrementAndGet()
        if (!isSourceWrite) return
        sqlSrc.incrementAndGet()
        when {
            clear -> sqlClear.incrementAndGet()
            own -> sqlOwn.incrementAndGet()
            else -> {
                sqlUnmatched.incrementAndGet()
                if (sampleSql(sql)) {
                    LogX.w("BLOCK w2 sql-unmatched ${sql.take(200)}")
                }
            }
        }
    }

    /** 原文采样：去重 + 上限 20 条。返回 true = 这条是新的、值得打一行 */
    private fun sampleSql(sql: String): Boolean = synchronized(sqlSamples) {
        if (sqlSamples.size >= SQL_SAMPLE_MAX) return false
        if (sqlSamples.contains(sql)) return false
        sqlSamples.add(sql)
        true
    }

    private val sqlSamples = ArrayList<String>()

    // ------------------------------------------------------------------ 读回查

    /** 一行的可见位快照 */
    class Bits(val src: Int, val lf: Int, val sef: Int, val createTime: Long)

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
        val sql = "select sourceType,localFlag,serverExtFlag,createTime from $TABLE where snsId=$snsId"
        val cur = runCatching { m.invoke(db, sql, null) }.getOrNull() ?: return null
        return try {
            if (XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean != true) return null
            Bits(
                src = longAt(cur, 0).toInt(),
                lf = longAt(cur, 1).toInt(),
                sef = longAt(cur, 2).toInt(),
                createTime = longAt(cur, 3),
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
     * 独立于「防朋友圈动态撤回」主开关：主开关负责**事后补位**（[SnsGuard] 现有行为，
     * 本轮一个字都不改），本开关负责**写时拦截**。两个都开时拦截先生效 ⇒ 没有位被清，
     * 补位自然空转（`blockstat` 里 `blocked` 增长、`SNS srcguard` 不再出现）。
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

    /** `op=snsg sub=blockstat`：4 行计数 + 最近 10 个事件，**总行数 ≤ 16** */
    fun stat(): List<String> {
        val out = ArrayList<String>(16)
        out += "BLOCK stat hit=${hit.get()} blocked=${blocked.get()} nonZero=${nonZero.get()} " +
            "skipOff=${skipOff.get()} last=${lastHitText()}"
        out += "BLOCK skip empty=${skipEmpty.get()} noApi=${skipNoApi.get()} noId=${skipNoId.get()} " +
            "parseFail=${parseFail.get()} afterOk=${afterOk.get()} afterFail=${afterFail.get()}"
        out += "BLOCK upd seen=${updSeen.get()} zero=${updZero.get()} nosrc=${updNoSrcKey.get()} | " +
            "ins seen=${insSeen.get()} zero=${insZero.get()} nz=${insNonZero.get()} nosrc=${insNoKey.get()}"
        out += "BLOCK w2 delSns=${delSns.get()} delRow=${delRow.get()} " +
            "sqlSns=${sqlSns.get()} sqlSrc=${sqlSrc.get()} sqlClear=${sqlClear.get()} " +
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
