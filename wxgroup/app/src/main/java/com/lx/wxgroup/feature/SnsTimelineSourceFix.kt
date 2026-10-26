package com.lx.wxgroup.feature

import android.content.ContentValues
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/**
 * 朋友圈「时间线来源位」修复（R27-3 · srcfix）—— **手动触发版**。
 *
 * ## 修的是什么
 * 好友删动态时微信**不删行**，只跑
 * `UPDATE SnsInfo SET sourceType = sourceType & -3 WHERE (sourceType & 2 != 0)`：
 * `-3 = ~2` ⇒ 只清 bit1，行 / `content` / `localFlag` 全不动。
 * 而时间线查询是 `where (sourceType & 2 != 0)` ⇒ 这条动态从此不显示。
 * 修复 = 把 bit1 补回去（`sourceType | 2`）。
 *
 * 判据（谁能补）全在 [SnsTimelineSrcLogic]（纯函数、JVM 单测锁住），这里只做 I/O：
 * 扫表 → 记台账 → 补位 → 写回滚值。设计见 `docs/_r27_srcfix_design.md`。
 *
 * ## 三条硬约束（都是结构性的，不靠"小心"）
 * 1. **只认台账**：`src=4`（自己私密）/ `src=0`（从来没可见过）的行走不进台账
 *    （快照只扫 `& 2 != 0`），补位逻辑物理上够不着它们。
 * 2. **绝不 INSERT**：补位是 `UPDATE ... WHERE snsId=?`，行没了就是 `rows=0`，
 *    记一笔跳过 —— 不从归档造行。
 * 3. **不动 SnsGuard 的 delete/execSQL 拦截**：这边只借它的库句柄
 *    （[SnsGuard.snsDbHandle]，仍是 null 安全的），写库走自己那份 `update` 反射。
 *
 * ## ⛔ 本轮范围
 * 只有**探针手动触发**（`op=snsg sub=srcstat|srcfix`）。自动触发（同步响应后对账）
 * **没做**，接口留在 [autoReconcile]。
 */
object SnsTimelineSourceFix {

    /** 微信朋友圈动态表（与 [SnsGuard] 同一个，真机实证） */
    private const val TABLE = "SnsInfo"

    /**
     * 台账扫描上限（`last_seen DESC` ⇒ 最近还看得见的排在前面）。
     *
     * ⚠️ 台账超过这个数时，最老的记录**进不了本轮窗口**（补位够不着它们）。
     * 这是有意的取舍：一行就是一次点查，无上限 = 广播线程跑几十秒。
     * 现实规模远小于 5000（每轮快照只登记「当前可见」的几百到几千条）。
     */
    private const val LEDGER_SCAN = 5000

    // ------------------------------------------------------------------ 探针：只读统计

    /**
     * `op=snsg sub=srcstat` —— **只读**，一行都不写。
     *
     * 回答三个数：
     * - `ledger`：台账有多少行
     * - `ever`：其中「曾经 `& 2 != 0`」的行（= 补位候选池大小）
     * - `cand`：**现在**库里 `& 2 == 0`、且台账有记录的候选数（= 下一轮能补的行数）
     *
     * 排查用：`cand=0` + `ever>0` ⇒ 没有行被清（或都已补好）；`ever=0` ⇒ 快照还没跑到过
     * 目标行（先跑 `sub=srcfix` 或等一轮刷新）。
     */
    fun stat(): List<String> {
        val gdb = GroupDb.current() ?: return listOf("SNS srcstat no module db")
        val db = SnsGuard.snsDbHandle()
            ?: return listOf("SNS srcstat no sns db yet (先让微信刷一次朋友圈再看)")
        val m = rawQueryMethod(db)
            ?: return listOf("SNS srcstat no rawQuery on ${db.javaClass.name}")

        var ever = 0
        var cand = 0
        var priv = 0
        var gone = 0
        var err = 0
        val ledger = gdb.listVisibleSrc(LEDGER_SCAN)
        for (e in ledger) {
            if (!SnsTimelineSrcLogic.everVisible(e.sourceType)) continue
            ever++
            val p = probe(db, m, e.snsId)
            when {
                p == null -> err++
                !p.exist -> gone++
                SnsTimelineSrcLogic.shouldFix(p.src, true) -> cand++
                SnsTimelineSrcLogic.isPrivate(p.src) -> priv++
            }
        }
        return listOf(
            "SNS srcstat ledger=${gdb.countVisibleSrc()} ever=$ever cand=$cand " +
                "priv=$priv gone=$gone err=$err held=${gdb.countSrcFixed()} switch=${enabled()}"
        )
    }

    // ------------------------------------------------------------------ 探针：快照 + 补位

    /**
     * `op=snsg sub=srcfix` —— 执行一轮「快照 + 补位」。
     *
     * 明细最多 [SnsTimelineSrcLogic.MAX_DETAIL_LOGS] 行（微信日志是 5000 行环形缓冲，
     * 刷屏会把关键判据挤掉），超出的只计数；最后一行是聚合结果
     * `SNS srcstat snap=.. fixed=.. skip=..`。
     */
    fun run(): List<String> {
        if (!enabled()) {
            return listOf("SNS srcfix switch off (antirevoke.sns=false)：只报状态，不执行")
        }
        val gdb = GroupDb.current() ?: return listOf("SNS srcfix no module db")
        val db = SnsGuard.snsDbHandle()
            ?: return listOf("SNS srcfix no sns db yet (先让微信刷一次朋友圈再看)")
        val m = rawQueryMethod(db)
            ?: return listOf("SNS srcfix no rawQuery on ${db.javaClass.name}")

        val out = ArrayList<String>()

        // ── A. 快照步：把「现在可见」的行登记进台账（跨轮持久） ──
        val (snap, added, pages) = snapshot(db, m, gdb)

        // ── B. 补位步：台账说曾经可见 + 现在被清 bit1 ⇒ 补回 ──
        var fixed = 0
        var skip = 0
        var priv = 0
        var gone = 0
        var fail = 0
        var err = 0
        var detail = 0
        for (e in gdb.listVisibleSrc(LEDGER_SCAN)) {
            if (fixed >= SnsTimelineSrcLogic.MAX_FIX_ROWS) break
            if (!SnsTimelineSrcLogic.everVisible(e.sourceType)) continue
            val p = probe(db, m, e.snsId)
            if (p == null) {
                err++
                skip++
                continue
            }
            if (!p.exist) {
                // 行已被真正删掉：绝不 INSERT 回填，记一笔跳过
                gone++
                skip++
                continue
            }
            if (!SnsTimelineSrcLogic.shouldFix(p.src, true)) {
                skip++
                if (SnsTimelineSrcLogic.isPrivate(p.src)) priv++
                continue
            }
            val target = SnsTimelineSrcLogic.fixTarget(p.src)
            val rows = updateSourceType(db, e.snsId, target)
            if (rows <= 0) {
                fail++
                skip++
                if (detail++ < SnsTimelineSrcLogic.MAX_DETAIL_LOGS) {
                    out += "SNS srcfix snsId=${e.snsId} ${p.src} -> $target rows=$rows (未命中)"
                }
                continue
            }
            // 写成功才记回滚值 —— 写失败却记了，回滚时会把没补过的行 "还原" 成脏值
            gdb.markSrcFixed(e.snsId, p.src)
            fixed++
            if (detail++ < SnsTimelineSrcLogic.MAX_DETAIL_LOGS) {
                out += "SNS srcfix snsId=${e.snsId} ${p.src} -> $target rows=$rows"
            }
        }
        out += "SNS srcstat snap=$snap fixed=$fixed skip=$skip " +
            "priv=$priv gone=$gone fail=$fail err=$err new=$added pages=$pages " +
            "ledger=${gdb.countVisibleSrc()} held=${gdb.countSrcFixed()}"
        return out
    }

    /**
     * 一轮快照 + 补位，只写日志、不返回内容 —— **留给自动触发**（同步响应后 / 导出轮 / 冷启进朋友圈）。
     *
     * ⛔ 本轮**没有接线**：设计 §3 的多触发点（`x2.onGYNetEnd` 常驻轻钩等）还没做，
     * 这里先给出唯一正确的调用形态（后台线程 + 幂等 + 全程 runCatching），
     * 将来接线时**只加调用、不改本文件**。
     */
    fun autoReconcile() {
        runCatching { for (l in run()) LogX.i(l) }
            .onFailure { LogX.w("SNS srcfix auto failed: ${it.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------------ 方案 B：清位后立刻补回

    /**
     * 微信刚跑完「批量清 bit1」那条 UPDATE 之后**立刻**补回去（由 `SnsGuard` 的
     * `execSQL` after 钩子调用）。
     *
     * ## 为什么必须有这一步
     * 真机实测（R27-4）：一次性补位会被**下一轮 sync 立刻抹掉** ——
     * `updateFilterTimeLine` 每轮都先把所有可见行清位、再由 classifytimeline 把
     * 「服务器仍然返回的」补回来；被删的那条不在响应里，于是停在 0。
     * 所以补位必须**每次都跑**，而且必须跑在清位**之后**（before 钩子里跑会被同一条 SQL 清掉）。
     *
     * ## 为什么不复用 [run]
     * [run] 走 `listVisibleSrc` → 逐行 [probe] 点查 → 逐行 `update`。
     * 清位之后**所有**台账行都变成候选（3000+ 行），逐行反射写库会把同步线程拖慢好几秒。
     * 这里改为**分块一条 UPDATE**：每块 500 个 id，SQL 自己带 `& 2 == 0` / `& 4 == 0` 两个条件，
     * 只改真正需要的行，总语句数由 id 数决定（3000 行 = 7 条）。
     *
     * ## 安全性
     * - 只认**台账**里的 id（`src=4` / `src=0` 的行走不进台账，见 [SnsTimelineSrcLogic.admit]）
     * - SQL 再带 `(sourceType & 4) = 0`，私密位在就不改（第二道保险）
     * - 只 `UPDATE`，不 `INSERT`：行没了就是改不到，绝不造行
     * - 重入保护：钩子触发的写库不会再触发本函数
     *
     * @return 实际补回的行数；**-1 = 上一轮还没跑完**（本次跳过）
     */
    fun reFixAfterClear(db: Any): Int {
        if (!enabled()) return 0
        if (!fixing.compareAndSet(false, true)) return -1
        try {
            val gdb = GroupDb.current() ?: return 0
            val ids = gdb.listVisibleSrc(LEDGER_SCAN)
                .filter { SnsTimelineSrcLogic.everVisible(it.sourceType) }
                .map { it.snsId }
            if (ids.isEmpty()) return 0
            val exec = execSqlMethod(db) ?: return 0
            val q = rawQueryMethod(db)
            var fixed = 0
            var chunks = 0
            for (part in ids.chunked(CLEAR_FIX_CHUNK)) {
                val inClause = part.joinToString(",")
                // 先数「这一块里真正需要补」的行数：execSQL 返回 void，不给行数，
                // 少了这一步日志只能报「发了几条 SQL」，证明不了补到东西没有。
                val cand = q?.let {
                    val sql = "select count(*) from $TABLE where snsId in ($inClause) " +
                        "and (sourceType & 2) = 0 and (sourceType & 4) = 0"
                    count(it, db, sql)
                } ?: -1
                if (cand == 0) {
                    chunks++
                    continue
                }
                val sql = "update $TABLE set sourceType = sourceType | 2 " +
                    "where snsId in ($inClause) and (sourceType & 2) = 0 and (sourceType & 4) = 0"
                runCatching { exec.invoke(db, sql) }
                    .onFailure {
                        LogX.w(
                            "SNS srcguard re-fix failed chunk=$chunks " +
                                "${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(120)}"
                        )
                    }
                chunks++
                if (cand > 0) fixed += cand
            }
            return fixed
        } finally {
            fixing.set(false)
        }
    }

    /** 重入保护：补位自己写的 SQL 会再走一遍 `execSQL` 钩子 */
    private val fixing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 单条 UPDATE 里塞多少个 snsId（SQLite 变量/语句长度都够，500 是经验值） */
    private const val CLEAR_FIX_CHUNK = 500

    /**
     * 采样打印：`SnsInfo` + `sourceType` 的 UPDATE 有，但形态没被 [SnsTimelineSrcLogic.isSourceClearSql]
     * 认出来 —— 说明微信换了写法，补位会静默失效。只打前 3 条，别刷屏。
     */
    private val sampled = java.util.concurrent.atomic.AtomicInteger(0)

    fun noteUnmatchedSourceWrite(sql: String) {
        if (sampled.get() >= 3) return
        if (sampled.incrementAndGet() <= 3) {
            LogX.w("SNS srcguard unmatched sourceType sql (adopt me): ${sql.take(200)}")
        }
    }

    /** 一行 count 查询（拿不到就 -1，别把「没查成」伪装成 0） */
    private fun count(m: Method, db: Any, sql: String): Int {
        val args: Any =
            if (m.parameterTypes[1] == Array<String>::class.java) emptyArray<String>()
            else emptyArray<Any>()
        val cur = runCatching { m.invoke(db, sql, args) }.getOrNull() ?: return -1
        return try {
            if (XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean != true) -1
            else cLong(cur, 0).toInt()
        } finally {
            runCatching { XposedHelpers.callMethod(cur, "close") }
        }
    }

    private fun execSqlMethod(db: Any): Method? = runCatching {
        db.javaClass.methods.firstOrNull {
            it.name == "execSQL" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java
        }
    }.getOrNull()

    // ------------------------------------------------------------------ 探针：回滚

    /**
     * `op=snsg sub=srcfix --es sub2 rollback` —— 按台账 `fixed_orig` 反向还原。
     *
     * 只还原「补过位且还没回滚」的行（`fixed_orig IS NOT NULL`）：
     * - 有原值 ⇒ 写回原值（[SnsTimelineSrcLogic.rollbackTarget]）
     * - 原值为脏（自带 bit1）⇒ 退化成「只清 bit1」，与微信自己的 `& -3` 等价
     *
     * ⚠️ 回滚**不碰** `content` / `localFlag` / 行数，全程只有一条 UPDATE（`sourceType`）。
     * ⚠️ 关开关（[Keys.ANTIREVOKE_SNS] = false）**不拦**回滚：停止保护 != 禁止收拾现场。
     */
    fun rollback(): List<String> {
        val gdb = GroupDb.current() ?: return listOf("SNS srcroll no module db")
        val db = SnsGuard.snsDbHandle()
            ?: return listOf("SNS srcroll no sns db yet (先让微信刷一次朋友圈再看)")
        val m = rawQueryMethod(db)
            ?: return listOf("SNS srcroll no rawQuery on ${db.javaClass.name}")

        val out = ArrayList<String>()
        var done = 0
        var skip = 0
        var gone = 0
        var fail = 0
        var detail = 0
        for (e in gdb.listVisibleSrc(LEDGER_SCAN)) {
            val orig = e.fixedOrig ?: continue
            if (done >= SnsTimelineSrcLogic.MAX_FIX_ROWS) break
            val p = probe(db, m, e.snsId)
            if (p == null) {
                fail++
                skip++
                continue
            }
            if (!p.exist) {
                gone++
                skip++
                continue
            }
            val target = SnsTimelineSrcLogic.rollbackTarget(p.src, orig)
            if (target == p.src) {
                // 已经是原值（微信自己又清过了）⇒ 视为已还原，清掉台账标记
                gdb.clearSrcFixed(e.snsId)
                skip++
                continue
            }
            val rows = updateSourceType(db, e.snsId, target)
            if (rows <= 0) {
                fail++
                skip++
                continue
            }
            gdb.clearSrcFixed(e.snsId)
            done++
            if (detail++ < SnsTimelineSrcLogic.MAX_DETAIL_LOGS) {
                out += "SNS srcroll snsId=${e.snsId} ${p.src} -> $target rows=$rows"
            }
        }
        out += "SNS srcstat rolled=$done skip=$skip gone=$gone fail=$fail " +
            "held=${gdb.countSrcFixed()}"
        return out
    }

    // ------------------------------------------------------------------ 快照步

    /**
     * 键集分页扫 `SnsInfo where (sourceType & 2 != 0)`，逐页 upsert 进台账。
     *
     * 为什么按 `(createTime, snsId)` 复合游标：只按 `createTime <` 会在页边界
     * 丢掉同一秒的行（与 `GroupDb.listSnsArchiveBefore` / `SnsGuard.listCurrentSns` 同一条理由）。
     *
     * ⚠️ 页数有上限（[SnsTimelineSrcLogic.MAX_SNAPSHOT_PAGES]）：首次跑就几十万行的话
     * 不能把整表拖出来；台账是**增量**的，这一轮没扫到的下一轮还能补上。
     *
     * @return `(扫到的行数, 新增台账行数, 实际扫了几页)`
     */
    private fun snapshot(db: Any, m: Method, gdb: GroupDb): Triple<Int, Int, Int> {
        var cursorTime = Long.MAX_VALUE
        var cursorId = Long.MAX_VALUE
        var scanned = 0
        var added = 0
        var pages = 0
        while (pages < SnsTimelineSrcLogic.MAX_SNAPSHOT_PAGES) {
            val page = pageVisible(db, m, cursorTime, cursorId) ?: break
            if (page.isEmpty()) break
            pages++
            val rows = ArrayList<Pair<Long, Int>>(page.size)
            for (r in page) {
                if (!SnsTimelineSrcLogic.admit(r.snsId, r.src)) continue
                rows += r.snsId to r.src
            }
            if (rows.isNotEmpty()) {
                scanned += rows.size
                added += gdb.upsertVisibleSrc(rows)
            }
            val last = page.last()
            cursorTime = last.createTime
            cursorId = last.snsId
            if (page.size < SnsTimelineSrcLogic.PAGE_SIZE) break
        }
        return Triple(scanned, added, pages)
    }

    /** 快照用的一页（只取 3 列，不碰 content/attrBuf/postBuf 三个 BLOB） */
    private class Snap(val snsId: Long, val src: Int, val createTime: Long)

    private fun pageVisible(db: Any, m: Method, createTime: Long, snsId: Long): List<Snap>? {
        val sql = "select snsId,sourceType,createTime from $TABLE " +
            "where (sourceType & 2 != 0) " +
            "and (createTime < ? or (createTime = ? and snsId < ?)) " +
            "order by createTime desc, snsId desc limit ${SnsTimelineSrcLogic.PAGE_SIZE}"
        val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
            arrayOf(createTime.toString(), createTime.toString(), snsId.toString())
        } else {
            arrayOf<Any>(createTime.toString(), createTime.toString(), snsId.toString())
        }
        val cur = runCatching { m.invoke(db, sql, args) }
            .onFailure {
                LogX.w(
                    "SNS srcfix snapshot query failed ${it.javaClass.simpleName}: " +
                        "${(it.cause ?: it).message?.take(80)}"
                )
            }
            .getOrNull() ?: return null
        val out = ArrayList<Snap>(SnsTimelineSrcLogic.PAGE_SIZE)
        try {
            while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                val id = cLong(cur, 0)
                // snsId=0 = 这一列没读出来（真机值可能为负，所以只能判 0）
                if (id == 0L) continue
                out += Snap(id, cLong(cur, 1).toInt(), cLong(cur, 2))
            }
        } finally {
            runCatching { XposedHelpers.callMethod(cur, "close") }
        }
        return out
    }

    // ------------------------------------------------------------------ 单行读 / 写

    /**
     * 台账里某一行在微信库里的**当前**来源位。
     *
     * @return null = 查不动（反射/游标失败，**不是**"行不在"）；
     *         `exist=false` 才是行真的没了（真删，补不动）
     */
    private class Probe(val exist: Boolean, val src: Int)

    private fun probe(db: Any, m: Method, snsId: Long): Probe? {
        val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
            arrayOf(snsId.toString())
        } else {
            arrayOf<Any>(snsId.toString())
        }
        val cur = runCatching { m.invoke(db, "select sourceType from $TABLE where snsId=?", args) }
            .onFailure {
                LogX.w(
                    "SNS srcfix probe failed snsId=$snsId ${it.javaClass.simpleName}: " +
                        "${(it.cause ?: it).message?.take(80)}"
                )
            }
            .getOrNull() ?: return null
        try {
            if (XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean != true) {
                return Probe(false, 0)
            }
            return Probe(true, cLong(cur, 0).toInt())
        } finally {
            runCatching { XposedHelpers.callMethod(cur, "close") }
        }
    }

    /**
     * `update SnsInfo set sourceType=? where snsId=?` —— 补位 / 回滚唯一的写库点。
     *
     * ⚠️ **whereArgs 的静态类型必须跟着签名走**：wcdb 同时存在
     * `update(..., String[])` 与 `update(..., Object[])`，而 [updateMethod] 是
     * `firstOrNull` 抓的，命中哪个不确定。写死 `Array<String>` 会踩
     * `IllegalArgumentException` → 被 runCatching 吞掉 → 表现成「补位静默失效」
     * （库没动，日志只有一行警告）。与 `SnsGuard.updateFlag` 同一套分流写法。
     *
     * @return 受影响行数；**-1 = 失败**（已记日志），0 = 没命中行（行没了或值未变）
     */
    private fun updateSourceType(db: Any, snsId: Long, target: Int): Int {
        val m = updateMethod(db) ?: return -1
        return runCatching {
            val args: Any = if (m.parameterTypes[3] == Array<String>::class.java) {
                arrayOf(snsId.toString())
            } else {
                arrayOf<Any>(snsId.toString())
            }
            val cv = ContentValues().apply { put("sourceType", target) }
            m.invoke(db, TABLE, cv, "snsId=?", args) as? Int ?: -1
        }.onFailure {
            LogX.w(
                "SNS update sourceType failed snsId=$snsId target=$target: " +
                    "${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(120)}"
            )
        }.getOrDefault(-1)
    }

    private fun updateMethod(db: Any): Method? = runCatching {
        db.javaClass.methods.firstOrNull {
            it.name == "update" && it.parameterTypes.size == 4 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == ContentValues::class.java
        }
    }.getOrNull()

    private fun rawQueryMethod(db: Any): Method? = runCatching {
        db.javaClass.methods.firstOrNull {
            it.name == "rawQuery" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                (it.parameterTypes[1] == Array<String>::class.java ||
                    it.parameterTypes[1] == Array<Any>::class.java)
        }
    }.getOrNull()

    /** 读一列 Long；失败记一行日志（别把"读不出来"伪装成 0） */
    private fun cLong(cur: Any, i: Int): Long =
        runCatching { XposedHelpers.callMethod(cur, "getLong", i) as? Long ?: 0L }
            .onFailure {
                LogX.w(
                    "SNS srcfix getLong[$i] failed ${it.javaClass.simpleName}: " +
                        "${(it.cause ?: it).message?.take(60)}"
                )
            }
            .getOrDefault(0L)

    /** 开关复用「防止朋友圈动态撤回」那一个，**不新增配置键** */
    private fun enabled(): Boolean = runCatching { Kv.getBool(Keys.ANTIREVOKE_SNS) }
        .getOrDefault(false)
}
