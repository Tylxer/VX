package com.lx.wxgroup.feature

import android.os.SystemClock
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.data.MediaDeleteLogic
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 候选 B：独立的「撤回图片/视频媒体表 DELETE 拦截」钩子（R51）。
 *
 * ## 补的缺口
 * `VoiceFileGuard.kt:251-252,306` 已全进程拦 `File.delete`（图片/视频/语音共用），
 * 但 wcdb `delete` 判据只认 `voiceinfo`（`VoiceGuard.kt:173-174`），
 * `ImgInfo2`/`videoinfo2`/`WxFileIndex2` 的删除**零拦截**
 * （`docs/_r49_revokeimg_forensics.md:132,139`、`docs/_r49_revokeimg_reference.md:100-101`）。
 * 外部决定性实验（掘金 coder-pig）：只拦 `File.delete` 时图片打不开，拦住媒体表 delete 后能打开。
 *
 * ## ⛔ 与现网链路互斥（靠表名白名单，不是靠注册顺序）
 * `MediaDeleteLogic.TARGET_TABLES` = `{imginfo2, videoinfo2, wxfileindex2}`
 * （`MediaDeleteLogic.kt:64`，**不含 voiceinfo**）⇒ 与 `VoiceGuard.deleteGuard`（只认 voiceinfo）、
 * `SnsGuard.sqlGuard/deleteGuard`（朋友圈独立库 SnsInfo/SnsComment）天然互不重叠。
 * 本 lane **不改** `VoiceGuard` / `SnsGuard` / `SnsSqlRewrite` / `VoiceFileGuard` / `R42` 任何语义。
 *
 * ## ⛔ 保守性红线（承自 `VoiceGuardLogic.kt:23-26,145-157`）
 * 媒体表同时服务微信正常 GC。无条件拦 delete 会让存储只增不减，比「图片打不开」严重得多。
 * 判据**全部**下移给 `data/MediaDeleteLogic` 的纯函数（本文件不重写任何判据）：
 * 只认单列精确 `=`、键列命中、抽得出合法 token、命中已保护集合、且在撤回期窗口；
 * `whereClause==null` 删全表 / `<` `>` `LIKE` `IS NULL` / `IN (SELECT …)` / 键列未取证一律放行。
 * **任何不确定一律放行**。
 *
 * ## ⛔ 热路径纪律（R52 Pro lane A 补探针后）
 * `execSQL` 是热路径：`if (!enabled)` 分支只留一条 60s 节流的 `disabled` 观测日志
 * （区分「钩子没被调用」与「开关关」）；其余放行分支按「table+reason」60s 节流 + 封顶打观测日志
 * （`observe()`），⛔ 都不查库 / 不反射 / 不文件 IO / 不打高频日志；`LogX` 另有 1s dedup +
 * 5000 行环形覆盖。`Kv.getBool` 只在 `install()` / `invalidate()`（非 before）里调用。
 *
 * ## token 集合来源（`[推断]`，⛔ 不写微信库、不查库）
 * 磁盘现状：`VoiceFileGuard.protectedMedia`（`:109`）是 **private**，`stat()` 只返回 size
 * （`:233-238`），无公开 token 集合只读接口；`RevokeRescue` 也无公开池。
 * 且本 lane ⛔ 严禁改 `VoiceFileGuard.kt`、`RevokeRescue.kt` 只准加 1 行无参 `invalidate()`。
 * ⇒ 取任务给的 fallback「用 `RevokeRescue` 已记录的 token 集合」：`install()` 期**一次性反射**
 * 拿 `protectedMedia` 的 Map 引用（非热路径、只读），`invalidate()` 时把其中**存活 token 的 key**
 * 快照进本对象进程内缓存。反射失败 ⇒ 快照恒空 ⇒ `shouldBlock` 恒 false ⇒ **只记账、永不拦**（安全降级）。
 *
 * ## `[需真机]`
 * - `execSQL` 返回 `void`（`docs/_r49_revokeimg_reference.md:215`），跳过方法体用
 *   `param.result = null`；该写法在 LSPosed 对 void 方法是否确实跳过、以及微信是否重试删除，未取证。
 * - 微信撤回删媒体表究竟走 `delete()` / `execSQL` / 预编译语句，未取证（`_r49_revokeimg_reference.md:90`）。
 * - `WxFileIndex2` 键列未取证（`MediaDeleteLogic.kt:89-94`）⇒ 该表基本走 UNKNOWN 放行。
 */
object MediaTableGuard {

    /** 消息前缀 TAG：`LogX` 的 logcat TAG 固定 `WXGroup`（`LogX.kt:19`），项目惯例靠消息前缀供 `dev.py q` 过滤 */
    private const val TAG = "MTG"

    private const val WCDB_DB = "com.tencent.wcdb.database.SQLiteDatabase"

    /**
     * 撤回期窗口，与 `VoiceFileGuard.PROTECT_MEDIA_MS`（`VoiceFileGuard.kt:112`）一致。
     * `[推断]`：那边若改值需同步；`nowMs`/`tokenIssuedMs` 统一用 `SystemClock.elapsedRealtime()`
     * （`docs/_r51_media_delete_logic.md:400` 单位纪律，禁止混 10 位秒）。
     */
    private const val WINDOW_MS = 600_000L

    /** 账本事件明细封顶 + 日志节流 key 封顶（K7 perf 每 250ms 刷屏 ⇒ 必须限流/去重/封顶） */
    private const val MAX_EVENTS = 64
    private const val MAX_LOG_KEYS = 64
    private const val LOG_TTL_MS = 60_000L

    /** 命中 delete 时伪装受影响行数，跳过方法体（同 `VoiceGuard.kt:74,186`） */
    private const val FAKE_ROWS = 1

    /**
     * R52 乙（id 反查，去全表扫）：反查只在 `invalidate()`（`[GT]ColdPool`）跑，⛔ 绝不在 before 热路径。
     * 主路径 via=svr 单条 `select id, thumbImgPath from ImgInfo2 where msgSvrId=? limit 16`
     * （命中 `serverImgInfoIndex`）；兜底 via=recent 单条
     * `select id, thumbImgPath from ImgInfo2 order by id desc limit 500`（主键逆向扫描）。
     * ⛔ 已彻底废弃旧 R52 甲「对 84 万行 TEXT 逐行子串全表扫」的写法 —— 那是 97s 卡顿的根因。
     * `MAX_ROWS_PER_TOKEN` = svr 路径单次返回上限；`MAX_RECENT_SCAN` = 兜底逆向扫描行数上限。
     * ⛔ 不再设「时间预算」常量：单条 `rawQuery` 原子不可中断，预算检查只能卡在语句之间，
     *    对真正的慢语句毫无作用（曾据此误判「有预算保护」，实际仍全表扫了 97s）。
     *    真正的修复是「不再有全表扫」，靠的是索引而不是计数器。
     * ⛔ 反查日志**不节流**：低频、每条都是验收证据；热路径 `observe()` 的 60s 节流 / `MAX_LOG_KEYS` 原样不动。
     */
    private const val MAX_ROWS_PER_TOKEN = 16
    private const val MAX_RECENT_SCAN = 500

    @Volatile
    private var enabled = false

    /** 每发生一次 `record()` 自增；与 `VoiceGuard.gen`（`VoiceGuard.kt:94`）同款缓存版本号 */
    @Volatile
    private var gen = 0L

    /** 最近一次图片/视频撤回时刻（撤回期窗口起点，`elapsedRealtime()` 口径） */
    @Volatile
    private var lastIssuedMs = 0L

    /** `invalidate()` 时重建的「存活 token」只读快照；before 里只读它，不碰池 */
    @Volatile
    private var cachedTokens: Set<String> = emptySet()

    /**
     * R52 甲：`invalidate()` 时按 `cachedTokens` 的 md5 反查 `ImgInfo2.id` 得到的受保护行 id 快照，
     * 与 `cachedTokens` 同一 `gen` 重建（设计契约 `docs/_r52_fix_design.md:274`）。
     * 归一走 `MediaDeleteLogic.normalizeId`（同口径，⛔ 不自己重写，防 `idMatches` 静默失效）。
     * ⛔ 只在 `invalidate()`（`[GT]ColdPool`）里写，before 只读一次局部快照。
     * 反查 `rows=0` / 异常 / 句柄缺失 ⇒ 保守置空（放行）+ 必须留 MISS 日志，⛔ 不静默、⛔ 不凑假 id。
     */
    @Volatile
    private var cachedRowIds: Set<String> = emptySet()

    /** install 期一次性反射到的 `VoiceFileGuard.protectedMedia` 只读引用；null = 降级为只记账 */
    @Volatile
    private var mediaPoolRef: Map<String, Long>? = null

    // ------------------------------------------------------------------ 只读事件账本（进程内，不写微信库）

    private val seen = AtomicLong()      // 钩子被调次数（仅 enabled 时计）
    private val matched = AtomicLong()   // 命中目标表次数
    private val blocked = AtomicLong()   // 真拦次数
    private val skipped = AtomicLong()   // 命中目标表后按保守判据放行次数

    /** 事件明细去重：key -> 最后记录时刻；封顶 MAX_EVENTS，超出不再新增 key（只留累计计数） */
    private val events = ConcurrentHashMap<String, Long>()
    /** 日志节流：key -> 最后打日志时刻；封顶 MAX_LOG_KEYS（只服务 block/err/cb-* 4 个固定 key） */
    private val logAt = ConcurrentHashMap<String, Long>()
    /** 观测日志节流：key -> 最后打日志时刻；⛔ 与 logAt 分离，避免热路径 (table,reason) 高基数 key
     *  挤占上面 4 个固定 key 的 64 槽位，导致命中 blocked 时那行证据反而打不出来 */
    private val obsLogAt = ConcurrentHashMap<String, Long>()

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cls = runCatching {
            XposedHelpers.findClass(WCDB_DB, lpparam.classLoader)
        }.getOrNull()
        if (cls == null) {
            LogX.w("$TAG: wcdb SQLiteDatabase not found, media table guard disabled")
            return
        }
        runCatching { refresh() }
            .onFailure { LogX.w("$TAG refresh failed: ${it.javaClass.simpleName}") }
        resolvePool()

        val ok = mutableListOf<String>()
        // ⛔ wcdb 无 deleteWithOnConflict（死代码，`docs/_r49_revokeimg_reference.md:69-86`），不挂。
        runCatching { XposedBridge.hookAllMethods(cls, "delete", deleteGuard) }
            .onSuccess { if (it.isNotEmpty()) ok += "delete" }
            .onFailure { LogX.e("$TAG: hook delete failed", it) }
        runCatching { XposedBridge.hookAllMethods(cls, "execSQL", execSqlGuard) }
            .onSuccess { if (it.isNotEmpty()) ok += "execSQL" }
            .onFailure { LogX.e("$TAG: hook execSQL failed", it) }
        if (ok.isEmpty()) {
            LogX.w("$TAG: no delete/execSQL hook installed, media table guard disabled")
            return
        }
        // CLAUDE.md:75-76 —— installed 日志必须新增并可用 `dev.py q 'MTG'` 验挂
        LogX.i(
            "$TAG installed hooks=${ok.joinToString(",")} enabled=$enabled " +
                "pool=${if (mediaPoolRef != null) "ok" else "reflect-failed"} " +
                "switch=${Keys.ANTIREVOKE_MEDIA_DELETE}"
        )
    }

    /** 读一次开关。⛔ `Kv.getBool` 会查模块自有库，只在 install()/invalidate() 调，绝不在 before 里调。 */
    fun refresh() {
        enabled = Kv.getBool(Keys.ANTIREVOKE_MEDIA_DELETE)
    }

    /**
     * 与 `VoiceGuard.invalidate()`（`VoiceGuard.kt:103-105`）同款：清缓存 / `gen` 自增。
     * 额外重建存活 token 快照、记窗口起点、热更新开关（均非 before 热路径）。
     * ⛔ 必须由 `RevokeRescue.record()` 图片/视频分支调用（`docs/_r49_revokeimg_reference.md:211`），
     * 且必须在 `VoiceFileGuard.protectMedia(orig.imgPath)` 之后（池里已有 token 才快照得到）。
     *
     * R52 乙：同一份 token 快照里反查 `ImgInfo2.id` 写 `cachedRowIds`（`lookupRowIds()`）。
     * ⛔ 反查是同步 `rawQuery`，只放在这里（`RevokeRescue.record()` 的 `[GT]ColdPool` 线程），
     *    ⛔ 绝不在 `onBefore`（`[D]#worker`）热路径查库（`AntiRevoke.kt:863`）。
     * ⛔ 排在 `refresh()` 之前：删除真机仅隔 10~25ms，反查越早发布越赶得上（`cost` 打点验收）。
     *
     * @param msgId 本次被拦下的消息 `message.msgId`。主路径用它经 `message.msgSvrId` 关联
     *              `ImgInfo2.msgSvrId`（走 `serverImgInfoIndex`），绕开旧 R52 甲的全表扫。
     *              默认 0 仅为不破坏其它潜在调用点；`<= 0` 时跳过主路径直接走 recent 兜底。
     */
    fun invalidate(msgId: Long = 0L) {
        gen++
        lastIssuedMs = SystemClock.elapsedRealtime()
        val tokens = snapshotLiveTokens()
        cachedTokens = tokens
        cachedRowIds = lookupRowIds(tokens, msgId)
        runCatching { refresh() }
    }

    /**
     * 反查探针（供 `op=mtg sub=lookup [--es val <msgId>]` 广播读取）。
     *
     * 存在理由：`via=svr` 主路径在正常撤回时**必然命中**，`via=recent` 兜底只有在
     * `msgId <= 0` 或 svr 路径失败（无 svrId / 查不到 / 校验不过）时才走得到，
     * 正常撤回无法主动触发 ⇒ 兜底的真实耗时一直是 `[需真机]`。
     * 本方法用**当前保护池**实跑一次 `lookupRowIds`，传 `msgId=0` 即强制走兜底。
     *
     * ⛔ 只读 + 只观测：token 现取 `snapshotLiveTokens()`（不依赖 `invalidate()` 时序），
     *    **不改任何运行时状态**（`cachedTokens` / `cachedRowIds` / `gen` / `lastIssuedMs` 全不动），
     *    也不写账本，不影响拦截判定。供调参（`MAX_RECENT_SCAN`）与兜底验收用。
     */
    fun probeLookup(msgId: Long, probeToken: String? = null): String {
        val t0 = SystemClock.elapsedRealtime()
        // ⭐ 可指定 token：冷启后保护池为空（`tokens=0`），此时用池跑不出「命中」，
        //    只能测到「扫了没命中」。传一个已知真实 md5 才能同时测出命中性 + 真实耗时。
        val tokens = if (!probeToken.isNullOrBlank()) {
            setOf(probeToken.trim().lowercase())
        } else {
            snapshotLiveTokens()
        }
        val ids = lookupRowIds(tokens, msgId)
        val cost = SystemClock.elapsedRealtime() - t0
        return "$TAG probe-lookup tokens=${tokens.size} (${
            if (probeToken.isNullOrBlank()) "pool" else "arg"
        }) msgId=$msgId " +
            "path=${if (msgId > 0) "svr-then-recent" else "recent-forced"} " +
            "hit=${ids.size} ids=$ids totalCost=$cost"
    }

    /** 只读事件账本导出（供 `op=mtg sub=stat` 广播读取；前缀恒为 `$TAG stat`，方便 `dev.py q 'MTG'` 一把抓）。 */
    fun stat(): String {
        val s = seen.get()
        val m = matched.get()
        return "$TAG stat enabled=$enabled gen=$gen seen=$s matched=$m " +
            "blocked=${blocked.get()} skipped=${skipped.get()} " +
            "verdict=${MediaDeleteLogic.verdict(s, m)} " +
            "pool=${if (mediaPoolRef != null) "ok" else "reflect-failed"} " +
            "tokens=${cachedTokens.size} events=${events.size} " +
            "switch=${Keys.ANTIREVOKE_MEDIA_DELETE}"
    }

    /**
     * 只清「只读账本」，供 `op=mtg sub=reset` 正反对照归零（一次装包内即可复位，无需冷启）。
     * ⛔ 运行时状态一律不动：`enabled` / `cachedTokens` / `cachedRowIds` / `gen` / `lastIssuedMs` / `mediaPoolRef`
     *    是 hook 判定依据，不是账本；保留它们才能在复位后打印的 stat() 里核对「归零没动运行时」。
     * ⛔ 与「冷启整体清零」语义不同：冷启是进程死亡 → 整个 object 重建（连运行时状态一起没），
     *    本方法只归零账本，hook 不卸、开关不变、token 快照不变。
     */
    fun resetLedger() {
        seen.set(0)
        matched.set(0)
        blocked.set(0)
        skipped.set(0)
        events.clear()
        logAt.clear()
        obsLogAt.clear()
    }

    // ------------------------------------------------------------------ 钩子

    private val deleteGuard = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // ⛔ 双保险：回调绝不能把异常抛回宿主（会阻断后续 callback、破坏现网 R42/语音拦截）
            try {
                onBefore(param, execSql = false)
            } catch (t: Throwable) {
                runCatching { throttledLog("cb-del", "$TAG delete cb err: ${t.javaClass.simpleName}") }
            }
        }
    }

    private val execSqlGuard = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                onBefore(param, execSql = true)
            } catch (t: Throwable) {
                runCatching { throttledLog("cb-sql", "$TAG execSQL cb err: ${t.javaClass.simpleName}") }
            }
        }
    }

    /** 一次 delete / execSQL 的只读解析结果（⛔ 不改 SQL 原文、不改 param.args） */
    private class Parsed(
        val table: String,
        val where: String?,
        val args: Array<out Any?>?,
    )

    /**
     * before 主体：**整个逻辑包在 try/catch 里**，任何异常都吞掉（这次不拦）。
     * 顺序 = 最便宜判断最先做（`VoiceGuard.kt:172`、`docs/_r49_revokeimg_reference.md:214` 同款）。
     */
    private fun onBefore(param: XC_MethodHook.MethodHookParam, execSql: Boolean) {
        try {
            // ① 开关关：只留一条限流痕（区分「钩子没被调用」与「开关关」），⛔ 不做 SQL 解析 / 不改 param
            if (!enabled) { observe(execSql, null, "disabled"); return }

            seen.incrementAndGet()
            val a = param.args
            if (a == null) { observe(execSql, null, "args-null"); return }

            // ② 取参（只读）；解析失败 / 非 delete 语句一律放行
            val p = if (execSql) fromExecSql(a) else fromDelete(a)
            if (p == null) {
                observe(execSql, null, if (execSql) "sql-not-delete" else "delete-unparsed")
                return
            }

            // ③ 最便宜判据先行：非白名单表（含 voiceinfo / null）交给既有 VoiceGuard
            if (!MediaDeleteLogic.isTargetTable(p.table)) {
                observe(execSql, p, "non-target")
                return
            }
            matched.incrementAndGet()
            noteEvent(execSql, p)

            // ④ 保护池快照与反查 id 快照**都为空** / 判据不通过 ⇒ 放行（⛔ 任何不确定一律放行）
            //    ⛔ 与 lane A `shouldBlock` 的 `protectedTokens.isEmpty() && protectedRowIds.isEmpty()`
            //       双空集闸门对齐；⛔ 不许只留「tokens 空就 return」，那会把 id 通道静默挡掉（C7 / M2）。
            val rowIds = cachedRowIds          // 进入判据前一次性局部快照，避免并发变化
            val tokens = cachedTokens
            if (tokens.isEmpty() && rowIds.isEmpty()) {
                skipped.incrementAndGet()
                observe(execSql, p, "tokens-empty")
                return
            }
            val nowMs = SystemClock.elapsedRealtime()
            if (!MediaDeleteLogic.shouldBlock(
                    p.table, p.where, p.args, tokens, nowMs, lastIssuedMs, WINDOW_MS,
                    protectedRowIds = rowIds      // ← 本轮唯一新增（lane A 契约的命名参数）
                )
            ) {
                skipped.incrementAndGet()
                observe(execSql, p, "shouldBlock-false")
                return
            }

            // ⑤ 命中：只做「跳过删除」这一件事；不改 ContentValues / SQL 原文 / param.args / DB 返回值
            if (execSql) {
                // ⛔ `execSQL` 返回 void，`param.result = 1` 伪装行数的语义不适用。
                //    置 null ⇒ Xposed returnEarly=true ⇒ 跳过方法体。`[需真机]` 验证该写法确实跳过且微信不重试。
                param.result = null
            } else {
                // wcdb delete() 纯 Java，入口置 result 即跳过整个方法体（`VoiceGuard.kt:125-132,186`）
                param.result = FAKE_ROWS
            }
            blocked.incrementAndGet()
            throttledLog(
                "block",
                "$TAG blocked ${if (execSql) "execSQL" else "delete"} " +
                    "table=${p.table.take(24)} where=${p.where?.take(80)} reason=blocked gen=$gen"
            )
        } catch (t: Throwable) {
            // ⛔ 绝不外泄：回调抛异常会阻断同方法上的后续 callback（R42 写时拦截 / 语音拦截）
            runCatching { throttledLog("err", "$TAG before err: ${t.javaClass.simpleName}") }
        }
    }

    /** `delete(String table, String whereClause, String[]|Object[] whereArgs)`，按位置取不按类型取。 */
    private fun fromDelete(a: Array<Any?>): Parsed? {
        if (a.isEmpty()) return null
        val table = a[0] as? String ?: return null
        val where = a.getOrNull(1) as? String
        val args = a.getOrNull(2) as? Array<*>
        return Parsed(table, where, args)
    }

    /**
     * `execSQL(String sql)` / `execSQL(String sql, Object[] bindArgs)`（`SnsGuard.kt:840-842`）的
     * **只读**适配：只认 `DELETE FROM <单表> WHERE <where>` 最简形态，把裸 SQL 拆成
     * `MediaDeleteLogic` 需要的 `(table, whereClause, whereArgs)` 三参。
     * ⛔ 不生成/拼接任何 SQL、不改 `param.args`；解析不出 / 删全表 / 多语句一律放行（不确定即放行）。
     * ⛔ where 里的 `IN (SELECT…)` / `LIKE` / 比较符 / 多条件由 `MediaDeleteLogic.UNSAFE` 判放行。
     */
    private fun fromExecSql(a: Array<Any?>): Parsed? {
        val sql = a.getOrNull(0) as? String ?: return null
        val s = sql.trimStart()
        // 最便宜：非 delete 语句（SELECT/UPDATE/INSERT…）直接放行，不做任何后续解析
        if (!s.regionMatches(0, "delete", 0, 6, ignoreCase = true)) return null
        if (s.length <= 6 || !s[6].isWhitespace()) return null   // 防 `deleteX` 误命中
        val rest = s.substring(6).trimStart()
        if (!rest.regionMatches(0, "from", 0, 4, ignoreCase = true)) return null
        val afterFrom = rest.substring(4).trimStart()
        if (afterFrom.isEmpty()) return null
        val tableEnd = afterFrom.indexOfFirst { it.isWhitespace() || it == ';' || it == '(' }
        if (tableEnd <= 0) return null
        val table = afterFrom.substring(0, tableEnd)
        val tail = afterFrom.substring(tableEnd).trimStart()
        // 无 where = 删全表 ⇒ 保守性红线，放行
        if (!tail.regionMatches(0, "where", 0, 5, ignoreCase = true)) return null
        val where = tail.substring(5).trim()
        if (where.isEmpty()) return null
        if (where.indexOf(';') >= 0) return null                 // 多语句放行
        val args = a.getOrNull(1) as? Array<*>
        return Parsed(table, where, args)
    }

    // ------------------------------------------------------------------ token 池（只读）

    /**
     * install 期一次性反射拿 `VoiceFileGuard.protectedMedia`（`:109`）的 Map 引用。
     * ⛔ 非 before 热路径；失败则 `mediaPoolRef=null` ⇒ 快照恒空 ⇒ 永不拦（安全降级）。
     */
    private fun resolvePool() {
        mediaPoolRef = runCatching {
            val f = VoiceFileGuard::class.java.getDeclaredField("protectedMedia")
            f.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val pool = f.get(VoiceFileGuard) as? Map<String, Long>
            pool
        }.getOrNull()
        if (mediaPoolRef == null) {
            LogX.w("$TAG: VoiceFileGuard.protectedMedia reflect failed -> token pool empty, guard will NOT block (stat-only)")
        }
    }

    /** `invalidate()` 时重建：只收「未过期」的 token key，统一小写（与 `extractTokens` 口径一致）。 */
    private fun snapshotLiveTokens(): Set<String> {
        val pool = mediaPoolRef ?: return emptySet()
        val now = SystemClock.elapsedRealtime()
        val out = HashSet<String>()
        runCatching {
            for ((k, v) in pool) {
                if (v >= now) out.add(k.lowercase())
            }
        }
        return out
    }

    // ------------------------------------------------------------------ id 反查（R52 乙，⛔ 仅 invalidate() 调用）

    /**
     * 按保护池里的 md5 反查 `ImgInfo2.id`，返回受保护行 id 集合（十进制数字串）。
     *
     * ## 两段式（⛔ 旧 R52 甲「逐 token 子串全表扫」已废 —— 84 万行 TEXT 扫 97s 的根因）
     * 1. **主路径 via=svr**（`msgId > 0`）：`message.msgId → message.msgSvrId`，
     *    再 `ImgInfo2 where msgSvrId=? limit 16`（命中 `serverImgInfoIndex`）。
     *    ⭐ 命中后仍按 `thumbImgPath` 是否含保护池 token 做**应用层校验** ——
     *    防 svrId 串号 / 多图共用 svrId（`message.msgSvrId` 与 `ImgInfo2.msgSvrId` 未必一一对应）。
     * 2. **兜底 via=recent**（主路径未拿到，或 `msgId <= 0`）：`order by id desc limit 500`
     *    主键逆向扫描（毫秒级），应用层按 token 做 `contains` 匹配。
     *
     * ## 反查口径（决定性证据，`docs/_r52_revokeimg_rootcause.md`）
     * - ⛔ `origImgMD5` 是**原图** md5，≠ 保护池里的缩略图 md5 ⇒ 不能当 key。
     * - ⛔ `ImgInfo2.msglocalid` 实测恒 NULL ⇒ 旧的 `where msglocalid=?` 口径必 `row=0`（口径错，非行被删）。
     * - ⛔ `ImgInfo2.id` ≠ `message.msgId`（样本 `id=876171` vs `msgId=19649033`），别混。
     * - `thumbImgPath = THUMBNAIL_DIRPATH://th_<32位缩略图md5>`，正是保护池 token 所在。
     *
     * ## ⛔ 只读 + 隔离
     * `rawQuery` + `AntiRevokeHint.BYPASS`（防自家 SQL 改写），零写操作 / 零建表 / 零建索引。
     * 句柄 `AntiRevoke.msgQueryHandle()`（`AntiRevoke.kt:851-855`）不可用 ⇒ 直接 MISS，不退 `lastDb`、不重试。
     * 每个查询各自 try/catch + Cursor `finally close`，⛔ 一个失败不能拖垮另一个。
     * `rows=0` / 异常 / 句柄缺失 ⇒ 返回空集（保守放行），**每个分支都留一行 MISS 日志**（不静默）。
     * ⭐ `cost` 打点：与 `MTG delete` 时间戳对比，证明反查赶得上 10~25ms 的跨线程竞态（T2）。
     */
    private fun lookupRowIds(tokens: Set<String>, msgId: Long): Set<String> {
        val t0 = SystemClock.elapsedRealtime()
        if (tokens.isEmpty()) {
            LogX.i("$TAG id-lookup MISS reason=tokens-empty rows=0 cost=0")
            return emptySet()
        }
        val handle = AntiRevoke.msgQueryHandle()
        if (handle == null) {
            LogX.i("$TAG id-lookup MISS reason=handle-null rows=0 cost=${SystemClock.elapsedRealtime() - t0}")
            return emptySet()
        }
        val (db, m) = handle
        // ⛔ 此处不设 `queried` / `capped` 计数器：新实现对每个 token 只发 1~2 条按索引的 SQL，
        //    既不会「逐个 token 查完为止」，也没有封顶截断的语义。
        //    旧版把 `capped` 写死 false、`queried` 直接等于 token 数，日志像是「真的统计过」，
        //    实际两个字段恒为常数 ⇒ 已删除，避免后续判读时被假字段误导（复核 P2-1）。
        // ⚠️ WCDB 两个 rawQuery 重载的第 2 形参类型不同（AntiRevoke.kt:889-893、
        //    RevokeRescue.kt:172-176）：String[] 传 arrayOf(v)，Object[] 传 arrayOf<Any>(v)；
        //    传错直接 IllegalArgumentException，表现就是「查不到」。原样复用那套分支。
        fun bindArgs(v: String): Any =
            if (m.parameterTypes[1] == Array<String>::class.java) arrayOf(v) else arrayOf<Any>(v)

        // ① 主路径 via=svr：msgId → message.msgSvrId → ImgInfo2(serverImgInfoIndex)
        if (msgId > 0) {
            var svr = 0L
            var svrReason: String? = null
            try {
                val args = bindArgs(msgId.toString())
                val sql = AntiRevokeHint.BYPASS + "select msgSvrId from message where msgId=? limit 1"
                val cur = m.invoke(db, sql, args)
                if (cur != null) {
                    try {
                        if (XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean == true) {
                            svr = XposedHelpers.callMethod(cur, "getLong", 0) as? Long ?: 0L
                        }
                    } finally {
                        runCatching { XposedHelpers.callMethod(cur, "close") }
                    }
                }
            } catch (t: Throwable) {
                svrReason = t.javaClass.simpleName
            }
            if (svr <= 0) {
                LogX.i(
                    "$TAG id-lookup via=svr MISS msgId=$msgId svr=$svr " +
                        "reason=${svrReason ?: "svr<=0"} cost=${SystemClock.elapsedRealtime() - t0}"
                )
            } else {
                val out = HashSet<String>()
                var rowReason: String? = null
                try {
                    val args = bindArgs(svr.toString())
                    val sql = AntiRevokeHint.BYPASS +
                        "select id, thumbImgPath from ImgInfo2 where msgSvrId=? limit $MAX_ROWS_PER_TOKEN"
                    val cur = m.invoke(db, sql, args)
                    if (cur != null) {
                        try {
                            var move = XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean ?: false
                            while (move && out.size < MAX_ROWS_PER_TOKEN) {
                                // ⛔ 归一直接复用 lane A 纯函数，与 extractRowIds / idMatches 完全同口径。
                                val id = MediaDeleteLogic.normalizeId(XposedHelpers.callMethod(cur, "getLong", 0))
                                val thumb = XposedHelpers.callMethod(cur, "getString", 1) as? String
                                if (id != null && thumbMatchesToken(thumb, tokens)) out.add(id)
                                move = XposedHelpers.callMethod(cur, "moveToNext") as? Boolean ?: false
                            }
                        } finally {
                            runCatching { XposedHelpers.callMethod(cur, "close") }
                        }
                    }
                } catch (t: Throwable) {
                    rowReason = t.javaClass.simpleName
                }
                val cost = SystemClock.elapsedRealtime() - t0
                if (out.isNotEmpty()) {
                    LogX.i("$TAG id-lookup via=svr msgId=$msgId svr=$svr rows=${out.size} ids=$out cost=$cost")
                    return out
                }
                LogX.i(
                    "$TAG id-lookup via=svr MISS msgId=$msgId svr=$svr " +
                        "reason=${rowReason ?: "no-match"} cost=$cost"
                )
            }
        }

        // ② 兜底 via=recent：主键逆向扫描最近 N 行，应用层按 token 匹配（毫秒级）。
        val out = HashSet<String>()
        var scanned = 0
        try {
            val sql = AntiRevokeHint.BYPASS +
                "select id, thumbImgPath from ImgInfo2 order by id desc limit $MAX_RECENT_SCAN"
            val cur = m.invoke(db, sql, null)
            if (cur != null) {
                try {
                    var move = XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean ?: false
                    while (move && scanned < MAX_RECENT_SCAN) {
                        scanned++
                        val id = MediaDeleteLogic.normalizeId(XposedHelpers.callMethod(cur, "getLong", 0))
                        val thumb = XposedHelpers.callMethod(cur, "getString", 1) as? String
                        if (id != null && thumbMatchesToken(thumb, tokens)) out.add(id)
                        move = XposedHelpers.callMethod(cur, "moveToNext") as? Boolean ?: false
                    }
                } finally {
                    runCatching { XposedHelpers.callMethod(cur, "close") }
                }
            }
        } catch (t: Throwable) {
            // ⛔ 兜底查询异常 ⇒ out 空，走下面 MISS via=recent 日志，不拖垮主流程
        }
        val cost = SystemClock.elapsedRealtime() - t0
        if (out.isNotEmpty()) {
            LogX.i("$TAG id-lookup via=recent scanned=$scanned rows=${out.size} ids=$out cost=$cost")
            return out
        }
        LogX.i("$TAG id-lookup MISS via=recent scanned=$scanned rows=0 cost=$cost")
        LogX.i("$TAG id-lookup MISS total rows=0 tokens=${tokens.size} cost=$cost")
        return emptySet()
    }

    /** `thumbImgPath`（如 `THUMBNAIL_DIRPATH://th_<32位md5>`）是否含保护池里某个 token（统一小写，防大小写/前缀差异）。 */
    private fun thumbMatchesToken(thumb: String?, tokens: Set<String>): Boolean {
        if (thumb == null) return false
        val t = thumb.lowercase()
        return tokens.any { t.contains(it) }
    }

    // ------------------------------------------------------------------ 账本 / 日志限流

    /** 事件明细去重 + 封顶（只在命中目标表后调用，媒体表 delete 低频）。 */
    private fun noteEvent(execSql: Boolean, p: Parsed) {
        val key = (if (execSql) "x|" else "d|") +
            p.table.lowercase() + "|" + (p.where?.take(32) ?: "-")
        val now = SystemClock.elapsedRealtime()
        val prev = events[key]
        if (prev != null) {
            events[key] = now
            return
        }
        if (events.size >= MAX_EVENTS) return      // 封顶：不再新增 key，累计计数仍在
        events[key] = now
    }

    /** 60s 按 key 节流 + MAX_LOG_KEYS 封顶；LogX 另有 1s dedup + 5000 行环形覆盖。
     *  ⛔ 签名与行为保持原样（仍只服务 logAt 的 block/err/cb-* 4 个固定 key），只是委托给共用节流。 */
    private fun throttledLog(key: String, msg: String) {
        emitThrottled(logAt, key) { msg }
    }

    /**
     * 全量记账观测（R52 Pro lane A）：**只加日志，⛔ 不改任何拦截语义**。
     * 每一条进入钩子的 delete/execSQL 的每个放行分支都调一次，按「table+reason」组合节流：
     * key = "obs|reason|table"，复用 `LOG_TTL_MS`(60s) + `MAX_LOG_KEYS`(64) + `LogX.i`。
     * 每条至少带：execSql(delete/execSQL)、table(截 24)、where(截 80)、放行原因(reason)、gen。
     * ⛔ 用**独立** `obsLogAt`：`logAt` 只服务 4 个固定 key，若共用同一 map，热路径非目标表的
     *    (table,reason) 高基数 key 会先占满 64 槽位，命中 blocked 时那行证据反而打不出来。
     * p==null = 尚未解析出表名/where（!enabled / 参数为空 / 解析失败），用 "-" 占位。
     * 消息体只在**通过节流后**才拼（lazyMsg），热路径不每条都分配长字符串。
     */
    private fun observe(execSql: Boolean, p: Parsed?, reason: String) {
        val table = p?.table?.take(24) ?: "-"
        val where = p?.where?.take(80) ?: "-"
        emitThrottled(obsLogAt, "obs|$reason|$table") {
            "$TAG ${if (execSql) "execSQL" else "delete"} " +
                "table=$table where=$where reason=$reason gen=$gen"
        }
    }

    /** 共用节流：各自 map 内按 key 60s 一次 + MAX_LOG_KEYS 封顶；通过节流才拼消息并打日志。 */
    private fun emitThrottled(map: ConcurrentHashMap<String, Long>, key: String, lazyMsg: () -> String) {
        val now = SystemClock.elapsedRealtime()
        val last = map[key]
        if (last != null && now - last < LOG_TTL_MS) return
        if (last == null && map.size >= MAX_LOG_KEYS) return
        map[key] = now
        LogX.i(lazyMsg())
    }
}
