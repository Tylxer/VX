package com.lx.wxgroup.feature

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.lx.wxgroup.util.LogX

/**
 * P0 纯取证探针：记录「独立同步轮次」的窗口账本。**纯内存，零 DB 写入**。
 *
 * ## 为什么只做这个对账
 * 好友删动态 = `UPDATE SnsInfo SET sourceType = sourceType & -3`，零 DELETE，且微信
 * 自己也会把 0 修回 2、8 修回 10（延迟 7~11 分钟）。所以行字段只能当「当前可见状态」，
 * 不能当「删除事件」。要判「这轮同步里某条真的被删」，唯一可对账的是：
 *   服务端本轮返回的 respIds，是否 ⊆ 本轮响应落库窗口内微信写库的 upsertIds。
 * 若某条动态「上轮还在 resp 里、这轮不在 resp 里、且这轮窗口内也没被写库」，
 * 才算「服务端把它下掉了」的候选证据。
 *
 * ## 判据纪律
 * - 日志不去重（去重会把第 2 条起吞掉，结论反掉）。
 * - 每个失败/异常分支都打日志，绝不允许静默失效。
 * - 互斥结果写成优先级分支，不写 AND 列表。
 * - [dump] 输出必须限行（12 轮 + 2 行汇总 = 最多 14 行），因为 LogX 是 5000 行环形。
 */
object SnsRoundLedger {

    private const val TAG = "WIN"

    /** 环形保留的最近轮次数 */
    private const val MAX_ROUNDS = 12

    /** 单轮 ids 集合最多保留个数（防内存；subset 用窗口内未截断的全量 respIds 算） */
    private const val MAX_IDS = 200

    /** upsert 收口窗口时长 */
    private const val UPSERT_WINDOW_MS = 3000L

    /** 同内容且间隔 < 该阈值视为心跳重复；≥ 该阈值视为用户真的又刷了一次（独立轮） */
    private const val DUP_GAP_MS = 3000L

    data class Round(
        val no: Int,
        val atMs: Long,
        val n: Int,
        val ctTop: Long,
        val ctBot: Long,
        val ids: Set<Long>,
        val same: Boolean,
        val upsertIds: MutableSet<Long> = HashSet(),
        val insertIds: MutableSet<Long> = HashSet(),
        var upsertClosed: Boolean = false,
        var subset: Boolean? = null,
    )

    private val lock = Any()

    private val rounds = ArrayDeque<Round>()

    private var roundNo = 0
    private var dupResp = 0L
    private var nonEmpty = 0

    /** 上一轮 respIds（判「完全重复响应」灌水用） */
    private var lastRespIds: Set<Long> = emptySet()

    /** 上一轮 onResponse 到达时刻（elapsedRealtime），配合 [DUP_GAP_MS] 判时间间隔 */
    private var lastRespAt = 0L

    // ---- upsert 窗口状态 ----
    private var windowOpen = false
    private var windowRoundNo = -1
    private var windowRespIds: Set<Long> = emptySet()
    private var windowUpsertIds: MutableSet<Long> = HashSet()
    private var windowInsertIds: MutableSet<Long> = HashSet()

    // ---- C1：相邻轮 ctTop 是否不变（验假设 B 的生死）----
    private var prevCtTop: Long? = null
    private var c1Unchanged = 0
    private var c1Comparable = 0

    private val worker: Handler by lazy {
        Handler(HandlerThread("sns-round-ledger").apply { start() }.looper)
    }

    /**
     * 由 [SnsTimelineMerge] 在收集完 respIds 后调用（空响应已在调用方闸掉）。
     *
     * 独立轮次判定：内容不同一定是新轮；内容相同但间隔 ≥ [DUP_GAP_MS] 也算新轮
     * （用户真的又刷了一次）。只有「同内容且间隔 < [DUP_GAP_MS]」才是心跳重复。
     * 这样「X 连续 ≥2 轮缺席」才能被记到（否则相同的连续刷新会被 dup 判掉，双轮缺席永远凑不齐）。
     */
    fun onResponse(ids: Set<Long>, times: List<Long>) {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            val gap = now - lastRespAt
            val same = ids == lastRespIds
            if (ids.isEmpty() || (same && gap < DUP_GAP_MS)) {
                dupResp++
                return
            }
            lastRespAt = now
            // 先关上一轮还开着的 upsert 窗口，再开新窗口
            closeWindowLocked()

            lastRespIds = ids
            roundNo++
            nonEmpty++
            val r = Round(
                no = roundNo,
                atMs = now,
                n = ids.size,
                ctTop = times.maxOrNull() ?: 0L,
                ctBot = times.minOrNull() ?: 0L,
                ids = if (ids.size > MAX_IDS) ids.take(MAX_IDS).toSet() else ids,
                same = same,
            )
            rounds.addLast(r)
            if (rounds.size > MAX_ROUNDS) rounds.removeFirst()

            prevCtTop?.let { p ->
                c1Comparable++
                if (r.ctTop == p) c1Unchanged++
            }
            prevCtTop = r.ctTop

            LogX.i(
                "$TAG new round=${r.no} n=${r.n} gap=${gap}ms same=$same " +
                    "ctTop=${r.ctTop} ctBot=${r.ctBot}"
            )

            windowOpen = true
            windowRoundNo = r.no
            windowRespIds = ids
            windowUpsertIds = HashSet()
            windowInsertIds = HashSet()
            val no = r.no
            worker.postDelayed({ onWindowExpire(no) }, UPSERT_WINDOW_MS)
        }
    }

    /**
     * 由 [SnsGuard.onSnsUpdateAfter] 调用，把微信写库的 snsId 加进「当前开着的
     * upsert 窗口」所属轮次。窗口没开/已关则忽略。
     */
    fun onUpsert(snsId: Long) {
        if (snsId == 0L) return
        synchronized(lock) {
            if (!windowOpen) return
            windowUpsertIds.add(snsId)
            findRound(windowRoundNo)?.upsertIds?.add(snsId)
        }
    }

    /**
     * 由 [SnsGuard] 的 INSERT 采样钩子调用（新动态首次落库走 INSERT，不走
     * `updateWithOnConflict`）。语义同 [onUpsert]，纯内存 HashSet.add、零 IO。
     */
    fun onInsert(snsId: Long) {
        if (snsId == 0L) return
        synchronized(lock) {
            if (!windowOpen) return
            windowInsertIds.add(snsId)
            findRound(windowRoundNo)?.insertIds?.add(snsId)
        }
    }

    private fun onWindowExpire(no: Int) {
        synchronized(lock) {
            if (!windowOpen || windowRoundNo != no) return
            closeWindowLocked()
        }
    }

    /** 关掉当前 upsert 窗口并打 P0-1 核心判据 `WIN gate ...`（须在 lock 内调用） */
    private fun closeWindowLocked() {
        if (!windowOpen) return
        windowOpen = false
        // R41：判据改并集 —— 新动态首次落库走 INSERT（不在 upsertIds），
        // 只比 upsertIds 会把「本轮有新内容」误判成证伪。
        val subset = windowRespIds.all { it in windowUpsertIds || it in windowInsertIds }
        val miss = (windowRespIds - windowUpsertIds - windowInsertIds).take(5).joinToString(",")
        val r = findRound(windowRoundNo)
        if (r == null) {
            // ⛔ 绝不允许静默：轮次被 12 轮环挤出时也必须留痕（极端高频才可能触发）
            LogX.w(
                "$TAG gate round=$windowRoundNo evicted respN=${windowRespIds.size} " +
                    "upsertN=${windowUpsertIds.size} insertN=${windowInsertIds.size} " +
                    "subset=$subset miss=$miss"
            )
            return
        }
        r.upsertClosed = true
        r.subset = subset
        LogX.i(
            "$TAG gate round=${r.no} respN=${windowRespIds.size} " +
                "upsertN=${windowUpsertIds.size} insertN=${windowInsertIds.size} " +
                "subset=$subset miss=$miss"
        )
        // R41 子步 3：每轮记录「这轮 id 现在还剩多少可见位」。查询走 worker 线程，
        // 不阻塞、不重试；失败在 SnsGuard 内部打 `WIN src skip: <原因>`。
        val no = r.no
        val ids = windowRespIds
        worker.post {
            runCatching { SnsGuard.probeVisibleBits(no, ids) }
                .onFailure { LogX.w("$TAG src probe err: ${it.javaClass.simpleName}") }
        }
    }

    private fun findRound(no: Int): Round? = rounds.lastOrNull { it.no == no }

    /** 该 id 是否曾出现在任一保留轮次的 ids 里（P0-2 翻页覆盖率用）。 */
    fun seenAny(id: Long): Boolean = synchronized(lock) { rounds.any { id in it.ids } }

    /** 给 `sub=win` 用：按时间正序输出最近 n 轮 + 2 行汇总。 */
    fun dump(n: Int = 12): List<String> {
        val out = ArrayList<String>()
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            val shown = rounds.toList().takeLast(n)
            for (r in shown) {
                val age = (now - r.atMs) / 1000
                val subset = if (!r.upsertClosed) {
                    "pending"
                } else if (r.subset == true) {
                    "true"
                } else {
                    "false"
                }
                out += "$TAG r=${r.no} age=${age}s n=${r.n} ctTop=${r.ctTop} " +
                    "ctBot=${r.ctBot} upsertN=${r.upsertIds.size} ins=${r.insertIds.size} " +
                    "subset=$subset same=${r.same} trunc=${r.n > r.ids.size} " +
                    "ids=${r.ids.take(8).joinToString(",")}"
            }
            out += "$TAG summary rounds=$roundNo dup=$dupResp nonEmpty=$nonEmpty"
            val pct = if (c1Comparable > 0) c1Unchanged * 100 / c1Comparable else 0
            out += "$TAG c1 ctTopUnchanged=$c1Unchanged/$c1Comparable 可满足率=$pct%"
        }
        return out
    }

    /** 清空账本。 */
    fun reset() {
        synchronized(lock) {
            rounds.clear()
            roundNo = 0
            dupResp = 0
            nonEmpty = 0
            lastRespIds = emptySet()
            lastRespAt = 0L
            windowOpen = false
            windowRoundNo = -1
            windowRespIds = emptySet()
            windowUpsertIds = HashSet()
            windowInsertIds = HashSet()
            prevCtTop = null
            c1Unchanged = 0
            c1Comparable = 0
        }
    }
}
