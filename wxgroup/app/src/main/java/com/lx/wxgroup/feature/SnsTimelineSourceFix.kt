package com.lx.wxgroup.feature

import android.content.ContentValues
import android.os.SystemClock
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.concurrent.Executors

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
     * `op=snsg sub=srcfix sub2=album` —— 给「被删又被我们救回」的行**补相册位**（bit3）。
     *
     * ## 为什么需要单独一次
     * [run] 只在「当前 `& 2 == 0`」时动手（`shouldFix`），而 R30 之前补位写的是 `| 2`，
     * 所以**历史上被救回的行 bit1 已在、bit3 从来就没有** ⇒ 再跑多少轮 [run] 都不会碰它们。
     * 相册门槛（真机 SQL）是 `sourceType in (8,10,12,…,222,478)` 全部含 bit3 ⇒ 这些行
     * 在好友相册页永远不出现。要见到效果必须对存量补一次。
     *
     * ## 只碰 `fixed_orig IS NOT NULL` 的行（[GroupDb.listSrcFixedIds]）
     * 也就是「确实被微信清过位、我们补回来过」的那批 —— 正是 R30 §6.2 要救的对象。
     * 普通可见动态**一行都不动**（它们本来就在相册里）。
     */
    fun fixAlbum(limit: Int = 500): List<String> {
        val gdb = GroupDb.current() ?: return listOf("SNS albumfix no module db")
        val db = SnsGuard.snsDbHandle()
            ?: return listOf("SNS albumfix no sns db yet (先让微信刷一次朋友圈再看)")
        val m = rawQueryMethod(db)
            ?: return listOf("SNS albumfix no rawQuery on ${db.javaClass.name}")
        val ids = gdb.listSrcFixedIds(limit)
        if (ids.isEmpty()) {
            return listOf("SNS albumfix held=0 —— 台账里没有「被删又救回」的行，无需补")
        }
        var fixed = 0
        var already = 0
        var priv = 0
        var gone = 0
        var fail = 0
        val out = ArrayList<String>(8)
        for (id in ids) {
            val p = probe(db, m, id)
            if (p == null || !p.exist) {
                gone++
                continue
            }
            if (SnsTimelineSrcLogic.isPrivate(p.src)) {
                priv++
                continue
            }
            if (SnsTimelineSrcLogic.isAlbumVisible(p.src)) {
                // ⛔⛔⛔ 2026-10-28 事故回滚（用户报障「正常朋友圈也被插入已阻止删除」）：
                //    这里**绝对不能** markSrcFixed。
                //
                //    昨晚曾在此登记「已经带 bit8 的行」，理由是「手动 setsrc 设过的行得进窄台账，
                //    否则下次同步被打回 2 时补位轮不到它」。但这条链忽略了一个致命事实：
                //
                //    **`fixAlbum` 的输入是整个「曾经可见」台账（held=229），其中绝大多数是
                //    正常动态**（正常动态 sourceType 天生就是 10，天然"已经带 bit8"）。
                //    而 `fixed_orig` 同时是「已删除」的判据
                //    （[GroupDb.snsTagIds] = `T_SNS ∪ T_SRC WHERE fixed_orig IS NOT NULL`）。
                //
                //    ⇒ 一句话：**把「可见」写成了「已删除」**。真机数据铁证：
                //      `02:17 albumfix held=229 fixed=0 already=229` 之后
                //      `srcstat fixed=199`（清洗前一度 199/229 条把 fixed_orig 写成非 null），
                //      于是 SnsTag 把 229 条几乎全当已删，正常动态全被标 `[删除已拦截]`。
                //
                //    正确语义：`fixed_orig` 只由**真正改了库**的分支写（下面 updateSourceType
                //    成功那支），它天然是「补位前原值（缺 bit8）」。任何"已经是好的就别记"
                //    的补登记都会污染这个判据。
                already++
                continue
            }
            val target = p.src or SnsTimelineSrcLogic.ALBUM_BIT
            if (updateSourceType(db, id, target) > 0) {
                // 写成功才记账（记的是**补之前**的值，回滚要用）
                runCatching { gdb.markSrcFixed(id, p.src) }
                fixed++
                if (fixed <= SnsTimelineSrcLogic.MAX_DETAIL_LOGS) {
                    out += "SNS albumfix snsId=$id ${p.src} -> $target"
                }
            } else {
                fail++
            }
        }
        // 台账刚变，让写库瞬间补位用的缓存立刻作废（否则最多 60s 内认不出新登记的行）
        invalidateAlbumIds()
        out += "SNS albumfix held=${ids.size} fixed=$fixed already=$already " +
            "priv=$priv gone=$gone fail=$fail"
        return out
    }

    // ------------------------------------------- 相册位自动补位（R30 §6.2）

    /**
     * 窄台账缓存（`fixed_orig IS NOT NULL` 的 snsId）—— 供 [SnsGuard.onSnsUpdateAfter]
     * 在**写库瞬间**判断「这一行是不是我们救回来的」。
     *
     * ## 为什么必须有这个缓存
     * `updateWithOnConflict` 是**全表热路径**（微信所有表的 update 都经过），
     * 每次命中 `SnsInfo` 都去查一次模块库会把同步拖慢。这里 60s 一次、
     * 只取 id（十几条），成本可忽略。
     *
     * ## ⛔ 为什么必须是「窄台账」而不是「可见台账」
     * 真机 `SnsInfo` 里有 **17.6 万条正常的 `sourceType=2`**。若按「缺 bit8 就补」的
     * 广谱判据走这条路径，一晚上会误标几百条（2026-10-27 实际发生过 512 条事故）。
     * 窄台账只含「确实被微信清过位、我们补回来过」的行 ⇒ 结构上不可能误伤。
     */
    private const val ALBUM_IDS_TTL_MS = 60_000L

    @Volatile private var albumIdsAt = 0L
    @Volatile private var albumIds: Set<Long> = emptySet()

    /**
     * 这个 snsId 是不是「被删又被我们救回」的行（= 可以补 bit8 的唯一一支）。
     *
     * ⚠️ **不能写 `snsId <= 0` 判非法**：真机 snsId 有负值（服务端分配，合法）。
     * 只有 `0` 才是「没读到」的哨兵。
     */
    fun isAlbumTarget(snsId: Long): Boolean {
        if (snsId == 0L) return false
        val now = SystemClock.elapsedRealtime()
        if (now - albumIdsAt > ALBUM_IDS_TTL_MS) {
            // 查不到就沿用旧快照：宁可多补一次（幂等 UPDATE）也不要清空集合。
            runCatching { GroupDb.current()?.listSrcFixedIds(1000) }.getOrNull()
                ?.let { albumIds = it.toSet() }
            albumIdsAt = now
        }
        return snsId in albumIds
    }

    /** 台账变了（[fixAlbum] 写入 / 回滚清标记）时让缓存失效 */
    fun invalidateAlbumIds() {
        albumIdsAt = 0L
    }

    /**
     * `op=snsg sub=srcfix --es sub2 hold --es val "id1,id2,..."` —— 手动把若干行登记进窄台账。
     *
     * ## 为什么需要它（真实事故，不是预留接口）
     * `fixAlbum` 在 R30 早期版本里**补了位却没记账**（`markSrcFixed` 是后来才加的），
     * 于是 2026-10-28 01:54 补过的那 5 条（verity 的 4 条纯文字 + 1 条图片）永远进不了
     * 窄台账 ⇒ 「写库瞬间补位」认不出它们 ⇒ 用户每次进她主页都是「闪一下就没」。
     * 这个入口用来把这批**历史遗留**一次性补登记（新路径已自愈，只补旧账）。
     *
     * ⚠️ 只登记不写库：`sourceType` 由 [fixAlbum] / [SnsGuard] 去动，这里不碰。
     */
    fun hold(ids: List<Long>): List<String> {
        if (ids.isEmpty()) return listOf("SNS hold no ids (--es val \"id1,id2\")")
        val gdb = GroupDb.current() ?: return listOf("SNS hold no module db")
        val db = SnsGuard.snsDbHandle()
            ?: return listOf("SNS hold no sns db yet (先让微信刷一次朋友圈再看)")
        val m = rawQueryMethod(db) ?: return listOf("SNS hold no rawQuery")
        var ok = 0
        var gone = 0
        val out = ArrayList<String>(8)
        for (id in ids) {
            val p = probe(db, m, id)
            if (p == null || !p.exist) {
                gone++
                out += "SNS hold snsId=$id skipped (not found)"
                continue
            }
            // ⚠️ 记 **0** 而不是当前 `p.src`（2026-10-28 改）。
            //
            // `fixed_orig` 现在被两支同时读：① 补位目标表（`isAlbumTarget`）；
            // ② 「已删除」判据（`GroupDb.snsTagIds` 的 `fixed_orig IS NOT NULL`）。
            // 而 [GroupDb.cleanSrcFixedWithAlbumBit] 的清洗判据是 `(fixed_orig & 8) != 0`
            // —— 因为**合法补位**记的原值必然缺 bit8。若这里记 `p.src`（人工登记时通常
            // 已经是正常的 10，含 bit8），这条会被下一次清洗误判成"误登记"而清掉。
            //
            // 记 0 同时满足三个约束：不含 bit8（清洗不会误伤）、非 null（补位/打标都认）、
            // 且语义正确 —— `sourceType=0` 正是"被微信清过位"的原样，回滚时写回 0 也符合预期。
            runCatching { gdb.markSrcFixed(id, 0) }
            ok++
            out += "SNS hold snsId=$id src=${p.src} (登记为已删候选, fixed_orig=0)"
        }
        invalidateAlbumIds()
        out += "SNS hold n=${ids.size} held=$ok gone=$gone total=${gdb.countSrcFixed()}"
        return out
    }

    /**
     * ⭐ **写库瞬间**补相册位（R30 §6.2 治本）。
     *
     * ## 为什么必须在 `update` 的 after 里**同步**补，而不是事后跑 [fixAlbum]
     * 真机现象（用户报障）：「进好友朋友圈，那几条已删动态**闪一下马上就消失**」。
     * 链路是
     * `h3.N 收响应 → updateWithOnConflict 按服务端值整行 upsert（sourceType=2，bit8 丢）
     *  → 事件 → loadData 读库（WHERE sourceType in (...含8...)）`。
     * 我们原来只有 [installAlbumTrigger]（`h3.N` after + 15s 节流 + 后台线程池），
     * 补位**晚于**这次读库 ⇒ 首屏用旧内存数据能看见、刷新一读库就没了。
     *
     * 挂在写库的 after 里同步补，等于把「覆盖」和「补回」压进同一个数据库事务窗口，
     * 读库时**不可能**看到中间态。
     *
     * @return 实际写进去的值；0 = 没补（不需要 / 写失败）
     */
    fun patchAlbumBit(db: Any, snsId: Long, curSrc: Int): Int {
        val target = curSrc or SnsTimelineSrcLogic.ALBUM_BIT
        if (target == curSrc) return 0
        if (!enabled()) return 0
        // ⛔ 私密位（bit2=4）在就不动：那是用户自己设的「仅自己可见」，加相册位会把它
        //    推给好友看 —— 与 [SnsTimelineSrcLogic.isPrivate] 同一条边界。
        if (SnsTimelineSrcLogic.isPrivate(curSrc)) return 0
        val rows = updateSourceType(db, snsId, target)
        if (rows > 0) {
            val n = patchCount.incrementAndGet()
            if (n <= 8) LogX.i("SNS albumpatch snsId=$snsId $curSrc -> $target rows=$rows (#$n)")
            return target
        }
        return 0
    }

    /** 补位次数（探针可见：>0 说明「写库瞬间补位」这条新路径真的被走到了） */
    private val patchCount = java.util.concurrent.atomic.AtomicInteger(0)

    fun albumPatchStat(): String = "SNS albumpatch count=${patchCount.get()} targets=${albumIds.size}"

    private const val ALBUM_SCENE = "com.tencent.mm.plugin.sns.model.h3"
    private const val ALBUM_RESP = "q54.jc5"

    /** 朋友圈同步 NetScene（scene 256），R25 定案：`MomentsSyncTrigger.kt:77` */
    private const val SYNC_SCENE = "com.tencent.mm.plugin.sns.model.x2"

    /**
     * 翻页会连着打多次 `h3.N`，而补位是 N 条 × 2 次 SQL —— 必须节流。
     *
     * ⚠️ R30 从 15s 收到 5s：这道触发现在还兼着「把已含 bit8 的行登记进窄台账」
     * （见 [fixAlbum] 的 `already` 分支），节流太长会让用户第一次进好友主页时
     * 台账还没登记、写库瞬间补位认不出那几条 ⇒ 仍然「闪一下就没」。
     * 5s 内重复进同一页面会连续跑两轮，但每轮 `limit=64` 且绝大多数是 point query，
     * 实测成本可忽略。
     */
    private const val ALBUM_FIX_MIN_INTERVAL_MS = 5_000L

    @Volatile private var lastAlbumFixAt = 0L
    private val albumFixPool = Executors.newSingleThreadExecutor()

    /**
     * 相册位（bit3）自动补位的触发点。
     *
     * ## 为什么必须挂在 `h3.N` 的 **after**
     * 好友个人相册是**读库渲染**（`docs/_r30_lane2_album_page.md` §8/§9）：
     * `h3.N 收响应 → l5.o 写库 → 事件 → loadData 读库（WHERE sourceType in (…白名单…)）`。
     * 而微信每次同步都会按服务端值重写 `sourceType`，实测把补好的 `10`（`2|8`）
     * 打回 `2`（时间线位在、相册位丢）⇒ 好友相册里就看不到被删动态。
     * 补位必须落在「写库之后、读库之前」—— `h3.N` 的 after 正好是这个窗口。
     *
     * ⛔⛔ 补位集合必须是 [fixAlbum] 内部那个**窄台账**（`fixed_orig IS NOT NULL`，
     * 真机十几条）。真机 `SnsInfo` 里有 **17.6 万条正常的 `sourceType=2`** —— 微信
     * 本来就不让它们进个人相册。2026-10-27 曾把「缺 bit8 就补」接到广谱路径上，
     * 几百条正常动态一夜之间涌进好友相册、标记也跟着乱标。**扫全表 = 事故**，
     * 这条边界由 [fixAlbum] 只认窄台账从结构上保证，不靠"小心"。
     */
    fun installAlbumTrigger(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val scene = runCatching { XposedHelpers.findClass(ALBUM_SCENE, cl) }.getOrNull()
        val resp = runCatching { XposedHelpers.findClass(ALBUM_RESP, cl) }.getOrNull()
        if (scene == null || resp == null) {
            // ⛔ 类名漂移必须看得见：静默失败的话「相册看不到」会变成无头案
            LogX.w("SNS-ALBUMFIX class missing scene=$scene resp=$resp (disabled)")
            return
        }
        runCatching {
            XposedHelpers.findAndHookMethod(scene, "N", resp, String::class.java, albumGuard)
        }.onSuccess { LogX.i("SNS-ALBUMFIX hook h3.N installed") }
            .onFailure { LogX.e("SNS-ALBUMFIX hook h3.N failed", it) }
    }

    /**
     * ⭐ 第二个触发点：**朋友圈同步结束之后**补一次。
     *
     * 为什么必须有它：微信每次同步都会按服务端值重写 `sourceType`，而服务端已经把
     * 被删动态清了位 ⇒ 我们补好的 `10` 会被打回 `2`（真机实测，01:1x 复现）。
     * 只靠「进相册页时补」（`installAlbumTrigger`）是**马后炮**——那一轮同步早跑完了，
     * 而用户可能在同步之后、进页面之前根本不触发 `h3.N`。
     *
     * `x2.onGYNetEnd` 是朋友圈同步（scene 256）的统一回包（R25 定案，
     * 见 `MomentsSyncTrigger.kt:295` 的用法），挂在它 **after** = 覆盖发生之后，
     * 正好是补回的唯一正确时机。与 `installAlbumTrigger` 共用 15s 节流 + 同一后台线程池。
     */
    fun installAlbumSyncTrigger(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val scene = runCatching { XposedHelpers.findClass(SYNC_SCENE, cl) }.getOrNull()
        if (scene == null) {
            LogX.w("SNS-ALBUMFIX class missing $SYNC_SCENE (sync trigger disabled)")
            return
        }
        val m = runCatching {
            scene.declaredMethods.firstOrNull {
                it.name == "onGYNetEnd" && it.parameterTypes.size == 6
            }
        }.getOrNull()
        if (m == null) {
            LogX.w("SNS-ALBUMFIX $SYNC_SCENE.onGYNetEnd not found (sync trigger disabled)")
            return
        }
        runCatching { XposedBridge.hookMethod(m, albumGuard) }
            .onSuccess { LogX.i("SNS-ALBUMFIX hook $SYNC_SCENE.onGYNetEnd installed (同步覆盖后补回 bit8)") }
            .onFailure { LogX.e("SNS-ALBUMFIX hook $SYNC_SCENE.onGYNetEnd failed", it) }
    }

    private val albumGuard = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            // ⚠️ 这是主线程回调：补位是 N×2 次 SQL，绝不能在 UI 线程跑
            val now = SystemClock.elapsedRealtime()
            if (now - lastAlbumFixAt < ALBUM_FIX_MIN_INTERVAL_MS) return
            lastAlbumFixAt = now
            runCatching {
                albumFixPool.execute {
                    runCatching {
                        if (Kv.getBool(Keys.ANTIREVOKE_SNS)) {
                            for (l in fixAlbum(limit = 64)) LogX.i(l)
                            // ⭐ 顺手跑一次快照：新动态若在「发布 → 被删」之间没被记进台账
                            // （`sns_visible_src`），补位就永远轮不到它 —— 真机实测一条
                            // 当天发当天删的带图动态就是这样漏掉的（`new=0`）。
                            // 挂在同步之后 = 每次朋友圈刷新都补一次账，把窗口缩到一轮同步。
                            autoReconcile()
                        }
                    }.onFailure { LogX.w("SNS-ALBUMFIX run err: ${it.javaClass.simpleName}") }
                }
            }.onFailure { LogX.w("SNS-ALBUMFIX submit err: ${it.javaClass.simpleName}") }
        }
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
                // R30：置位值 = bit1(时间线) + bit3(相册)，见 SnsTimelineSrcLogic.FIX_BITS。
                // ⛔ 不能只写 `| 2` —— 那样时间线能看、好友相册页看不到（两套 WHERE 门槛不同）。
                val sql = "update $TABLE set sourceType = sourceType | ${SnsTimelineSrcLogic.FIX_BITS} " +
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

    /**
     * 只读诊断：把若干 snsId 在 `SnsInfo` 里的关键列一次打全（2026-10-28 为 §6.2 定位加）。
     *
     * ## 为什么必须看 `localFlag`
     * 此前所有关于「已删动态为什么不显示」的结论都盯着 `sourceType`（时间线位 bit1 /
     * 相册位 bit3）。但微信**还有一套独立的「这条动态已死」判据**：
     * `Ljj/ca.isDieItem()` = `(localFlag & 32) != 0 && snsId != 0`（bit5 = ItemDie）。
     * 如果被删动态的 `localFlag` bit5 被置上，那不管 `sourceType` 怎么补，
     * 页面 loader/adapter 都可能直接跳过它 —— 这正是「补了位还是不显示」的最可能解释。
     *
     * 输出 `dieBit=true` 就是这条线索成立。
     */
    fun dumpRows(ids: List<Long>): List<String> {
        if (ids.isEmpty()) return listOf("SNS dump no ids (--es val \"id1,id2\")")
        val db = SnsGuard.snsDbHandle() ?: return listOf("SNS dump no sns db yet")
        val m = rawQueryMethod(db) ?: return listOf("SNS dump no rawQuery")
        val out = ArrayList<String>(ids.size + 1)
        for (id in ids) {
            val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
                arrayOf(id.toString())
            } else {
                arrayOf<Any>(id.toString())
            }
            out += runCatching {
                // ⚠️ 用 `select *` 而不是写死列名：真机第一次跑 `select snsId,user_name,
                //    createTime,sourceType,localFlag` 直接 InvocationTargetException
                //    （SnsInfo 的列名与内存里的字段名不同构）。逐列按名字取最稳。
                val cur = m.invoke(db, "select * from $TABLE where snsId=? limit 1", args)
                    ?: return@runCatching "SNS dump snsId=$id query-null"
                try {
                    if (XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean != true) {
                        return@runCatching "SNS dump snsId=$id NOT-FOUND"
                    }
                    fun col(vararg names: String): Int {
                        for (n in names) {
                            val i = XposedHelpers.callMethod(cur, "getColumnIndex", n) as? Int ?: -1
                            if (i >= 0) return i
                        }
                        return -1
                    }
                    fun num(vararg names: String): Long {
                        val i = col(*names)
                        return if (i < 0) Long.MIN_VALUE else cLong(cur, i)
                    }
                    fun str(vararg names: String): String {
                        val i = col(*names)
                        if (i < 0) return "?"
                        return (XposedHelpers.callMethod(cur, "getString", i) as? String).orEmpty()
                    }
                    val user = str("user_name", "userName")
                    val ct = num("createTime")
                    val src = num("sourceType")
                    val lf = num("localFlag", "local_flag")
                    val priv = num("private", "privacy")
                    // ⭐ type 必须打：好友主页只渲染了一部分行，type 是最可能的差异位
                    //    （2=纯文字、1=单图、15=多图/长文），没有它无法做同构对比
                    val ty = num("type")
                    val ctStr = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                        .format(java.util.Date(ct * 1000L))
                    "SNS dump snsId=$id user=${user.take(24)} ct=$ct($ctStr) type=$ty " +
                        "src=$src localFlag=$lf dieBit=${(lf and 32L) != 0L} " +
                        "priv=$priv flags=${flagNames(lf)}"
                } finally {
                    runCatching { XposedHelpers.callMethod(cur, "close") }
                }
            }.getOrElse {
                val c = (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it
                "SNS dump snsId=$id err ${c.javaClass.simpleName}: ${c.message?.take(80)}"
            }
        }
        return out
    }

    /**
     * **R31c 决定性探针**：把某一行的**全部列**打出来（列名 + 值 + 长度）。
     *
     * 为什么要它：好友主页 SQL 明明返回了 5 条已删动态（`rank` 实测 #2~#6），
     * 页面却只渲染 1 条，而两者在 `type/sourceType/localFlag/createTime` 上看不出差别。
     * 剩下的差异只能藏在**别的列**，或 `attrBuf`（protobuf `SnsObject` 正文）里。
     * ⇒ 与其继续猜字段，不如把两行全列摆在一起对比（一条显示的 vs 一条不显示的）。
     */
    fun dumpAllCols(id: Long): List<String> {
        val db = SnsGuard.snsDbHandle() ?: return listOf("SNSCOL no sns db yet")
        val m = rawQueryMethod(db) ?: return listOf("SNSCOL no rawQuery")
        val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
            arrayOf(id.toString())
        } else {
            arrayOf<Any>(id.toString())
        }
        return runCatching {
            val cur = m.invoke(db, "select * from $TABLE where snsId=? limit 1", args)
                ?: return listOf("SNSCOL snsId=$id query-null")
            try {
                if (XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean != true) {
                    return listOf("SNSCOL snsId=$id NOT-FOUND")
                }
                val n = XposedHelpers.callMethod(cur, "getColumnCount") as? Int ?: 0
                val out = ArrayList<String>(n + 1)
                out += "SNSCOL snsId=$id cols=$n"
                for (i in 0 until n) {
                    val name = runCatching {
                        XposedHelpers.callMethod(cur, "getColumnName", i) as? String
                    }.getOrNull() ?: "#$i"
                    // 每列独立兜底：blob 列 getString 可能抛，不能让它带走整个 dump
                    val v = runCatching {
                        XposedHelpers.callMethod(cur, "getString", i) as? String
                    }.getOrNull()
                    val shown = when {
                        v == null -> "<null>"
                        v.length <= 70 -> v
                        else -> v.take(70) + "…(len=${v.length})"
                    }
                    out += "SNSCOL $name = $shown"
                }
                // ⛔ BLOB 列的 `getString` 会返回 null —— 那是**取值失败**，不等于「列是空的」。
                //    这里用 SQL 的 `length()` / `hex()` 绕开 Java 取值，才是真实字节数。
                for (c in arrayOf("content", "attrBuf", "postBuf")) {
                    val len = runCatching {
                        val c2 = m.invoke(db, "select length(`$c`) from $TABLE where snsId=?", args)
                        try {
                            if (XposedHelpers.callMethod(c2, "moveToFirst") as? Boolean == true) {
                                (XposedHelpers.callMethod(c2, "getString", 0) as? String).orEmpty()
                            } else "<no-row>"
                        } finally {
                            runCatching { XposedHelpers.callMethod(c2, "close") }
                        }
                    }.getOrElse { "<err ${it.javaClass.simpleName}>" }
                    out += "SNSCOL blob $c length=$len"
                }
                return out
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
        }.getOrElse {
            val c = (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it
            listOf("SNSCOL snsId=$id err ${c.javaClass.simpleName}: ${c.message?.take(100)}")
        }
    }

    /**
     * **R31c 决定性探针**：把 `sns.db` 里**每一张表**都拿这组 snsId 试一遍，
     * 输出「哪些表有这些行」。
     *
     * 为什么需要：`dumpAllCols` 证明「显示的」和「不显示的」两行在 `SnsInfo` 的 18 列里
     * 逐列相同，且 `attrBuf / content / postBuf` **都是 null** —— 也就是说正文根本不在
     * `SnsInfo`。公众号页能渲染的正文一定来自另一张表；若已删动态在那张表里缺行，
     * 就是「SQL 查得到、页面渲染不出」的机制级解释。
     *
     * ⚠️ 只读、只 `count(*)`，不扫内容；单表失败（没有 snsId 列）静默跳过。
     */
    fun scanTables(ids: List<Long>): List<String> {
        if (ids.isEmpty()) return listOf("SNSTBL no ids (--es val \"id1,id2\")")
        val db = SnsGuard.snsDbHandle() ?: return listOf("SNSTBL no sns db yet")
        val m = rawQueryMethod(db) ?: return listOf("SNSTBL no rawQuery")
        fun q1(sql: String): String? = runCatching {
            val cur = m.invoke(db, sql, null) ?: return@runCatching null
            try {
                if (XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean != true) return@runCatching null
                (XposedHelpers.callMethod(cur, "getString", 0) as? String)
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
        }.getOrNull()

        val out = ArrayList<String>(32)
        val tables = runCatching {
            val cur = m.invoke(db, "select name from sqlite_master where type='table'", null)
                ?: return@runCatching emptyList<String>()
            val list = ArrayList<String>(32)
            try {
                while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                    val n = (XposedHelpers.callMethod(cur, "getString", 0) as? String).orEmpty()
                    if (n.isNotEmpty() && !n.startsWith("sqlite_")) list.add(n)
                }
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
            list
        }.getOrDefault(emptyList())

        out += "SNSTBL tables=${tables.size} ids=${ids.size} (hit 位序 = 入参顺序)"
        for (t in tables) {
            var any = false
            val hit = StringBuilder()
            for (id in ids) {
                val c = q1("select count(*) from `$t` where snsId=$id")
                if (c == null) {
                    // 这张表没有 snsId 列 ⇒ 与本课题无关，整表跳过（不打噪音）
                    any = false
                    break
                }
                val n = c.toLongOrNull() ?: 0L
                if (n > 0) any = true
                hit.append(if (n > 0) "1" else "0")
            }
            if (any) out += "SNSTBL $t hit=$hit"
        }
        return out
    }

    /**
     * **R31c**：把 `sns.db` 全部表的结构打出来（表名 / 行数 / 列名）。
     *
     * 动机：`scanTables` 实测 16 张表里只有 `SnsInfo` 有 `snsId` 列，而 `SnsInfo` 的
     * `attrBuf / content / postBuf` **全为 null** —— 正文（protobuf `SnsObject`）
     * 必然在别处。要么在某张用别的 key 的表里，要么根本不在 SQLite（文件/内存缓存）。
     * 结构一摆出来就能分清，不用再猜。
     */
    fun schema(): List<String> {
        val db = SnsGuard.snsDbHandle() ?: return listOf("SNSSCH no sns db yet")
        val m = rawQueryMethod(db) ?: return listOf("SNSSCH no rawQuery")
        fun cursor(sql: String, per: (Any) -> Unit): Boolean = runCatching {
            val cur = m.invoke(db, sql, null) ?: return@runCatching false
            try {
                while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) per(cur)
                true
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
        }.getOrDefault(false)

        val tables = ArrayList<String>(32)
        cursor("select name from sqlite_master where type='table'") { cur ->
            val n = (XposedHelpers.callMethod(cur, "getString", 0) as? String).orEmpty()
            if (n.isNotEmpty() && !n.startsWith("sqlite_")) tables.add(n)
        }
        val out = ArrayList<String>(tables.size + 2)
        out += "SNSSCH tables=${tables.size}"
        for (t in tables) {
            var rows = "?"
            cursor("select count(*) from `$t`") { cur ->
                rows = (XposedHelpers.callMethod(cur, "getString", 0) as? String).orEmpty()
            }
            val cols = ArrayList<String>(12)
            val ok = cursor("pragma table_info(`$t`)") { cur ->
                // pragma table_info: cid, name, type, notnull, dflt_value, pk
                val name = (XposedHelpers.callMethod(cur, "getString", 1) as? String).orEmpty()
                val ty = (XposedHelpers.callMethod(cur, "getString", 2) as? String).orEmpty()
                if (name.isNotEmpty()) cols.add(if (ty.isEmpty()) name else "$name:$ty")
            }
            out += if (ok) "SNSSCH $t rows=$rows cols=${cols.joinToString(",")}"
            else "SNSSCH $t rows=$rows cols=<pragma failed>"
        }
        return out
    }

    /** `localFlag` 的位名（`Ljj/ca` 的实证位表），只用于把数字翻成人话 */
    private fun flagNames(lf: Long): String {
        val names = ArrayList<String>(4)
        if (lf and 2L != 0L) names += "ExtFlag"
        if (lf and 16L != 0L) names += "WaitPost"
        if (lf and 32L != 0L) names += "ItemDie"
        if (lf and 128L != 0L) names += "Exposures"
        if (lf and 256L != 0L) names += "LocalInvisible"
        if (lf and 512L != 0L) names += "PostHolding"
        if (lf and 1024L != 0L) names += "WsFold"
        if (lf and 4096L != 0L) names += "WsFeedType"
        return if (names.isEmpty()) "-" else names.joinToString("|")
    }

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
