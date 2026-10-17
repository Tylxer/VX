package com.lx.wxgroup.feature

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 朋友圈**评论**防撤回（需求 6.2：「防止朋友圈评论撤回」）。
 *
 * ## 锚点（真机实测，不是猜的）
 * ```
 * execSQL "delete from SnsComment where snsID = -3442401033659805004 and commentSvrID = 1"
 *   thread = [D]#worker      db = MicroMsg/<md5>/SnsMicroMsg.db
 * ```
 * 跟动态侧（[SnsGuard]）一样是**物理 DELETE 整行**，所以套路相同：拦 + 归档。
 *
 * ## ⚠️ 跟动态侧的关键差别：可见性不在本行
 * 动态：行在 `SnsInfo` 里，拦住 DELETE + 把 `localFlag` 的可见位（`or 16`）置回去就行。
 * 评论：行在 `SnsComment` 里，但**界面读的可见列表在 `SnsInfo.attrBuf`**（protobuf）。
 * 实测一次删操作的 SQL 顺序是 `update SnsInfo → replace SnsInfo → delete SnsComment`，
 * 所以拦住 DELETE 之后：
 * - 若微信**没**重写 attrBuf → 行还在，UI 照常显示（最理想）；
 * - 若微信**已经**重写了 → 行还在、UI 上没了。内容没丢（我们归档了），但看不见。
 *
 * 到底是哪种 **还没实证** → 本文件默认**不写 attrBuf**（保守降级），
 * 实证手段是 `probe("traceon")`：开 60 秒窗口挂 `update` 钩子，记录
 * 「写之前 / 写之后」的 attrBuf 长度与头部 hex，日志里直接出结论。
 * 证据到手、把 `AUTO_RESTORE_ATTR` 打开后，才会在拦截点把快照写回去。
 */
object SnsCommentGuard {

    // ==================================================================
    // ⚠️ 待取证区 —— 常量为空串 = 还没真机取证到。
    //    install 时反射查找；找不到（或还是空串）就**静默跳过并打日志**，绝不影响微信。
    //    取证到之后把名字填进来即可，不需要改任何逻辑。
    // ==================================================================

    /** T1（待取证）：删评论的 Java 层入口（DAO/Storage 类 + 方法名），用于拿业务侧上下文 */
    private const val PENDING_DELETE_DAO_CLASS = ""
    private const val PENDING_DELETE_DAO_METHOD = ""

    /**
     * T2（待取证 → 由 probe("traceon") 实证）：删评论时微信有没有重写 `SnsInfo.attrBuf`。
     * 实证前 `[AUTO_RESTORE_ATTR]` 保持 false → 只拦 DELETE + 归档，不碰 attrBuf。
     */
    private const val AUTO_RESTORE_ATTR = false

    // ------------------------------------------------------------------ 常量

    private const val WCDB_DB = "com.tencent.wcdb.database.SQLiteDatabase"
    private const val DB_MARK = "SnsMicroMsg.db"
    private const val TABLE_INFO = "SnsInfo"
    private const val COL_ATTR = "attrBuf"

    private const val CFG_TTL_MS = 3000L
    private const val SNAP_TTL_MS = 30_000L
    private const val TRACE_WINDOW_MS = 60_000L
    private const val ATTR_SAMPLE_MAX = 20
    private const val TRACE_LOG_MAX = 20
    private const val LOG_MAX = 5

    private data class Config(val enabled: Boolean)

    @Volatile private var config = Config(false)
    @Volatile private var cfgAt = 0L

    /** 「这次删除是我们自己发的」标记（诊断用，真删必须绕过拦截） */
    internal val bypass = ThreadLocal<Boolean>()

    /** 朋友圈库实例（只在命中删/execSQL 时顺手记下，诊断用） */
    @Volatile private var snsDb: Any? = null

    @Volatile private var wcdbCls: Class<*>? = null
    @Volatile private var pathWarned = false
    @Volatile private var ctxWarned = false

    private val seen = AtomicInteger(0)
    private val blocked = AtomicInteger(0)
    private val archived = AtomicInteger(0)
    private val bulkSeen = AtomicInteger(0)
    private val actRestore = AtomicInteger(0)
    private val actKeep = AtomicInteger(0)
    private val actSkip = AtomicInteger(0)
    private val miscLogged = AtomicInteger(0)

    /**
     * attrBuf 快照：`snsId -> 写之前的内容`。
     *
     * ⚠️ 用 synchronizedMap + 非空 value（[Snap]），**不用 ConcurrentHashMap** ——
     *    CHM 不允许 null value，真机崩过一次。负结果一律靠 containsKey 表达。
     */
    private val attrSnap: MutableMap<Long, Snap> = Collections.synchronizedMap(HashMap())

    /** 追踪窗口内的「写之前」临时值，afterHookedMethod 里配成对 */
    private val pendingOld: MutableMap<Long, ByteArray> = Collections.synchronizedMap(HashMap())

    private val traceLog: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private data class Snap(val buf: ByteArray, val at: Long)

    private data class Pending(val what: String, val cls: String, val method: String)

    private val pendingPoints = listOf(
        Pending("删评论 DAO 入口", PENDING_DELETE_DAO_CLASS, PENDING_DELETE_DAO_METHOD),
    )

    @Volatile private var traceUntil = 0L
    @Volatile private var traceOn = false

    private val traceUnhooks: MutableList<XC_MethodHook.Unhook> =
        Collections.synchronizedList(mutableListOf())

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cls = runCatching { XposedHelpers.findClass(WCDB_DB, lpparam.classLoader) }
            .getOrNull()
        if (cls == null) {
            LogX.w("SNSC: wcdb SQLiteDatabase not found, comment guard disabled")
            return
        }
        wcdbCls = cls
        runCatching { refresh() }
            .onFailure { LogX.w("SNSC refresh failed: ${it.javaClass.simpleName}") }

        val ok = mutableListOf<String>()
        // ① 已取证形态：execSQL("delete from SnsComment where snsID=? and commentSvrID=?")
        runCatching { XposedBridge.hookAllMethods(cls, "execSQL", execSqlGuard) }
            .onSuccess { if (it.isNotEmpty()) ok += "execSQL" }
            .onFailure { LogX.e("SNSC: hook execSQL failed", it) }
        // ② 兜底形态：delete(table, whereClause, whereArgs) —— 方法存在但未实测到微信走它，
        //    挂上零成本（先做字符串匹配再反射），真走这条也不会漏拦。
        runCatching { XposedBridge.hookAllMethods(cls, "delete", deleteGuard) }
            .onSuccess { if (it.isNotEmpty()) ok += "delete" }
            .onFailure { LogX.e("SNSC: hook delete failed", it) }
        if (ok.isEmpty()) {
            LogX.w("SNSC: no hook installed, comment guard disabled")
            return
        }
        installPending(lpparam)
        LogX.i("SNSC installed hooks=${ok.joinToString(",")} enabled=${config.enabled}")
    }

    /**
     * 待取证 hook 点：反射查得到就挂（只记录证据，不做任何拦截），查不到就跳过。
     * 这样取证代码可以一直留在分支里，取证到了也不用改结构。
     */
    private fun installPending(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (p in pendingPoints) {
            if (p.cls.isBlank() || p.method.isBlank()) {
                LogX.i("SNSC pending[${p.what}] 未取证 -> skip")
                continue
            }
            val c = runCatching { XposedHelpers.findClass(p.cls, lpparam.classLoader) }
                .getOrNull()
            if (c == null) {
                LogX.w("SNSC pending[${p.what}] class not found: ${p.cls}")
                continue
            }
            val m = runCatching {
                c.declaredMethods.firstOrNull { it.name == p.method }
            }.getOrNull()
            if (m == null) {
                LogX.w("SNSC pending[${p.what}] method not found: ${p.method}")
                continue
            }
            runCatching { XposedBridge.hookMethod(m, EvidenceHook(p)) }
                .onSuccess { LogX.i("SNSC pending[${p.what}] hooked ${p.cls}#${p.method}") }
                .onFailure { LogX.e("SNSC pending[${p.what}] hook failed", it) }
        }
    }

    /** 待取证点的通用证据记录器：只打日志，不改任何行为 */
    private class EvidenceHook(private val p: Pending) : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                val a = param.args
                val desc = a?.joinToString(",") { it?.javaClass?.simpleName ?: "null" } ?: ""
                LogX.i("SNSC evidence[${p.what}] args=($desc)")
            }.onFailure { /* 取证用的钩子，失败就放弃 */ }
        }
    }

    fun refresh() {
        config = Config(enabled = Kv.getBool(Keys.ANTIREVOKE_COMMENT))
        cfgAt = SystemClock.elapsedRealtime()
    }

    private fun cfg(): Config {
        if (SystemClock.elapsedRealtime() - cfgAt > CFG_TTL_MS) refresh()
        return config
    }

    // ------------------------------------------------------------------ 钩子

    private val execSqlGuard = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                val a = param.args
                if (!a.isNullOrEmpty()) {
                    // execSQL(String) / execSQL(String, Object[])
                    handle(
                        param,
                        SnsCommentLogic.matchDeleteSql(a[0] as? String, a.getOrNull(1) as? Array<*>?),
                        true,
                    )
                }
            }.onFailure { LogX.w("SNSC execSQL hook err: ${it.javaClass.simpleName}") }
        }
    }

    private val deleteGuard = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                val a = param.args
                if (!a.isNullOrEmpty()) {
                    handle(
                        param,
                        SnsCommentLogic.matchDeleteCall(
                            a[0] as? String,
                            a.getOrNull(1) as? String,
                            a.getOrNull(2) as? Array<*>?,
                        ),
                        false,
                    )
                }
            }.onFailure { LogX.w("SNSC delete hook err: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * @param fromExecSql true = execSQL 形态（返回 void，置 null 跳过原方法体）；
     *                    false = delete 形态（返回 int，置 1 让微信以为删掉了）
     */
    private fun handle(param: XC_MethodHook.MethodHookParam, id: SnsCommentLogic.Id?, fromExecSql: Boolean) {
        if (id == null) return
        if (bypass.get() == true) return
        val db = param.thisObject ?: return
        // ⚠️ 顺序有讲究：先做纯字符串匹配，再反射取路径 —— 反射只能出现在低频分支上
        if (!isSnsDb(db)) return
        seen.incrementAndGet()
        val c = cfg()
        if (!c.enabled) {
            logOnce("SNSC delete seen (off) ${SnsCommentLogic.key(id)}")
            return
        }
        // ⚠️ 快照必须**同步**取：只在拦截之前这一刻能确定行还在。
        // 放到后台读会有漏档窗口（库被关 / 进程被杀 / 微信另起清理抢先删），
        // 而漏档是永久的。这是主键点查，不是扫表。
        val row = runCatching { readRow(db, id) }.getOrNull()
        param.result = if (fromExecSql) null else 1
        blocked.incrementAndGet()
        val v = tryRestoreAttr(db, id)
        when (v.act) {
            SnsCommentLogic.Act.RESTORE -> actRestore.incrementAndGet()
            SnsCommentLogic.Act.KEEP -> actKeep.incrementAndGet()
            SnsCommentLogic.Act.SKIP -> actSkip.incrementAndGet()
        }
        logOnce("SNSC blocked ${SnsCommentLogic.key(id)} snap=${row != null} act=${v.act} ${v.reason}")
        saveAsync(row, v)
    }

    // ------------------------------------------------------------------ attrBuf

    /**
     * 拦下删评论之后，判断要不要把 `SnsInfo.attrBuf` 写回快照。
     *
     * ⚠️ 只在 [AUTO_RESTORE_ATTR] 打开时真写 —— 那是「删评论时微信会重写 attrBuf」
     * 被实证之后的事。没实证之前一律 SKIP：写错 attrBuf 比看不见评论严重得多
     * （会把整条动态的可见评论列表搞坏）。
     */
    private fun tryRestoreAttr(db: Any, id: SnsCommentLogic.Id): SnsCommentLogic.Verdict {
        val snap = attrSnap[id.snsId]
        if (snap == null) return SnsCommentLogic.Verdict(SnsCommentLogic.Act.SKIP, "no snapshot", 0, 0)
        if (SystemClock.elapsedRealtime() - snap.at > SNAP_TTL_MS) {
            attrSnap.remove(id.snsId)
            return SnsCommentLogic.Verdict(SnsCommentLogic.Act.SKIP, "snapshot stale", snap.buf.size, 0)
        }
        val cur = runCatching { readAttrBuf(db, id.snsId) }.getOrNull()
            ?: return SnsCommentLogic.Verdict(SnsCommentLogic.Act.SKIP, "read current failed", snap.buf.size, 0)
        val v = SnsCommentLogic.planAttr(snap.buf, cur)
        if (v.act == SnsCommentLogic.Act.RESTORE && AUTO_RESTORE_ATTR) {
            writeAttrBuf(db, id.snsId, snap.buf)
        }
        return v
    }

    /** 追踪窗口的钩子：记录 SnsInfo 写前/写后的 attrBuf 差量 —— 取证结论 #4 的实证手段 */
    private val attrTraceHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching { traceAttr(param, true) }
                .onFailure { logOnce("SNSC trace before err: ${it.javaClass.simpleName}") }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching { traceAttr(param, false) }
                .onFailure { logOnce("SNSC trace after err: ${it.javaClass.simpleName}") }
        }
    }

    private fun traceAttr(param: XC_MethodHook.MethodHookParam, before: Boolean) {
        if (!traceOn || SystemClock.elapsedRealtime() > traceUntil) {
            scheduleTraceOff()
            return
        }
        val a = param.args ?: return
        if (a.isEmpty()) return
        // ⚠️ 热路径：update 是全库写路径，先做一次字符串比较再谈别的
        if (!(a[0] as? String).equals(TABLE_INFO, ignoreCase = true)) return
        if (attrSamples.get() >= ATTR_SAMPLE_MAX) return
        val db = param.thisObject ?: return
        if (!isSnsDb(db)) return
        val snsId = SnsCommentLogic.snsIdOf(a.getOrNull(2) as? String, a.getOrNull(3) as? Array<*>?)
            ?: return
        val buf = runCatching { readAttrBuf(db, snsId) }.getOrNull()
        if (before) {
            if (buf != null) pendingOld[snsId] = buf
            return
        }
        val old = pendingOld.remove(snsId)
        attrSamples.incrementAndGet()
        val v = SnsCommentLogic.planAttr(old, buf)
        val line = "SNSC trace snsId=$snsId old=${old?.size ?: -1}B new=${buf?.size ?: -1}B " +
            "act=${v.act} (${v.reason}) head=${SnsCommentLogic.hex(old)} -> ${SnsCommentLogic.hex(buf)}"
        synchronized(traceLog) {
            if (traceLog.size >= TRACE_LOG_MAX) traceLog.removeAt(0)
            traceLog += line
        }
        LogX.i(line)
        // 命中 RESTORE 就把这份快照留下，等删评论那条 execSQL 到了直接用
        if (v.act == SnsCommentLogic.Act.RESTORE && old != null) {
            attrSnap[snsId] = Snap(old, SystemClock.elapsedRealtime())
            evictSnapshots()
        }
    }

    private val attrSamples = AtomicInteger(0)

    private fun evictSnapshots() {
        if (attrSnap.size <= SNAP_MAX) return
        synchronized(attrSnap) {
            while (attrSnap.size > SNAP_MAX) {
                val oldest = attrSnap.entries.minByOrNull { it.value.at }?.key ?: break
                attrSnap.remove(oldest)
            }
        }
    }

    private const val SNAP_MAX = 32

    /** 追踪窗口的开关（默认全程不挂 update —— 那是全库热路径） */
    private fun startTrace(ms: Long = TRACE_WINDOW_MS) {
        val cls = wcdbCls
        if (cls == null) {
            LogX.w("SNSC trace: wcdb class unavailable")
            return
        }
        if (traceOn) {
            traceUntil = SystemClock.elapsedRealtime() + ms
            return
        }
        var n = 0
        for (name in arrayOf("update", "updateWithOnConflict")) {
            runCatching { XposedBridge.hookAllMethods(cls, name, attrTraceHook) }
                .onSuccess { hs -> hs.forEach { traceUnhooks += it }; n += hs.size }
                .onFailure { t -> LogX.e("SNSC trace hook $name failed", t) }
        }
        traceOn = true
        traceUntil = SystemClock.elapsedRealtime() + ms
        attrSamples.set(0)
        LogX.i("SNSC trace ON ${ms / 1000}s hooks=$n")
        mainHandler.postDelayed({ stopTrace() }, ms)
    }

    private fun stopTrace() {
        if (!traceOn) return
        traceOn = false
        traceUntil = 0L
        synchronized(traceUnhooks) {
            traceUnhooks.forEach { runCatching { it.unhook() } }
            traceUnhooks.clear()
        }
        LogX.i("SNSC trace OFF samples=${attrSamples.get()}")
    }

    private fun scheduleTraceOff() {
        if (traceOn) mainHandler.post { stopTrace() }
    }

    // ------------------------------------------------------------------ 读微信库

    /**
     * 是不是朋友圈库。
     *
     * ⚠️ 只在**已经匹配到表名**之后才调。放进 query/update 的无条件分支里
     * 就等于给微信每次写库都加一次反射，那是不可接受的。
     */
    private fun isSnsDb(db: Any): Boolean {
        val p = runCatching { XposedHelpers.callMethod(db, "getPath") as? String }
            .onFailure {
                if (!pathWarned) {
                    pathWarned = true
                    LogX.w("SNSC getPath failed on ${db.javaClass.name}: ${it.javaClass.simpleName}")
                }
            }
            .getOrNull()
        if (p == null) {
            if (!pathWarned) {
                pathWarned = true
                LogX.w("SNSC getPath returned null on ${db.javaClass.name}")
            }
            return false
        }
        return if (p.endsWith(DB_MARK)) {
            snsDb = db
            true
        } else {
            false
        }
    }

    /**
     * ⚠️ rawQuery 的 Method **绝不能跨库缓存**：AOSP 的 Method 拿去调 wcdb 实例会抛
     * `IllegalArgumentException: Expected receiver of type android.database.sqlite.SQLiteDatabase`。
     * 每次按 `db.javaClass` 现找（SnsGuard 探针阶段踩过，静默失败白跑一轮）。
     */
    private fun rawQueryMethod(db: Any): Method? =
        runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "rawQuery" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    (it.parameterTypes[1] == Array<String>::class.java ||
                        it.parameterTypes[1] == Array<Any>::class.java)
            }
        }.getOrNull()

    private fun queryRow(db: Any, sql: String, argsIn: Array<String>, body: (Any) -> Unit): Boolean {
        val m = rawQueryMethod(db) ?: return false
        val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
            argsIn
        } else {
            Array<Any>(argsIn.size) { argsIn[it] }
        }
        val cur = runCatching { m.invoke(db, sql, args) }
            .onFailure {
                logOnce("SNSC read err ${it.javaClass.simpleName}: " +
                    "${(it.cause ?: it).message?.take(80)}")
            }
            .getOrNull() ?: return false
        return try {
            if (XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean != true) return false
            body(cur)
            true
        } finally {
            runCatching { XposedHelpers.callMethod(cur, "close") }
        }
    }

    /** 评论整行快照（16 列，按列名取，不按 select 下标取） */
    internal data class Row(
        val snsId: Long,
        val parentId: Long,
        val isRead: Int,
        val createTime: Long,
        val talker: String?,
        val type: Int,
        val isSend: Int,
        val curActionBuf: ByteArray?,
        val refActionBuf: ByteArray?,
        val svrId: Long,
        val clientId: String?,
        val commentflag: Int,
        val isSilence: Int,
        val springWishFlag: Int,
        val isReminding: Int,
        val msgRelevanceType: Int,
    )

    private fun readRow(db: Any, id: SnsCommentLogic.Id): Row? {
        var row: Row? = null
        val ok = queryRow(
            db,
            "select * from ${SnsCommentLogic.TABLE} where ${SnsCommentLogic.COL_SNS_ID}=? " +
                "and ${SnsCommentLogic.COL_SVR_ID}=?",
            arrayOf(id.snsId.toString(), id.svrId.toString()),
        ) { cur ->
            fun col(name: String): Int =
                runCatching { XposedHelpers.callMethod(cur, "getColumnIndex", name) as? Int ?: -1 }
                    .getOrDefault(-1)
            fun longOf(name: String): Long {
                val i = col(name)
                if (i < 0) return 0L
                return runCatching { XposedHelpers.callMethod(cur, "getLong", i) as? Long ?: 0L }
                    .getOrDefault(0L)
            }
            fun intOf(name: String): Int = longOf(name).toInt()
            fun textOf(name: String): String? {
                val i = col(name)
                if (i < 0) return null
                return runCatching { XposedHelpers.callMethod(cur, "getString", i) as? String }
                    .getOrNull()
            }
            fun blobOf(name: String): ByteArray? {
                val i = col(name)
                if (i < 0) return null
                return runCatching { XposedHelpers.callMethod(cur, "getBlob", i) as? ByteArray }
                    .getOrNull()
            }
            row = Row(
                snsId = longOf("snsID"),
                parentId = longOf("parentID"),
                isRead = intOf("isRead"),
                createTime = longOf("createTime"),
                talker = textOf("talker"),
                type = intOf("type"),
                isSend = intOf("isSend"),
                curActionBuf = blobOf("curActionBuf"),
                refActionBuf = blobOf("refActionBuf"),
                svrId = longOf("commentSvrID"),
                clientId = textOf("clientId"),
                commentflag = intOf("commentflag"),
                isSilence = intOf("isSilence"),
                springWishFlag = intOf("springWishFlag"),
                isReminding = intOf("isReminding"),
                msgRelevanceType = intOf("msgRelevanceType"),
            )
        }
        if (!ok) return null
        // ⚠️ row 是在 lambda 里赋值的 var，必须转成本地 val 才能用（否则 smart cast 不过）
        val r = row ?: return null
        // ⚠️ 拿到手的 id 对不上就拒绝归档：残档会占着主键，
        //    之后真想归档这条也写不进去了（首条为准 + CONFLICT_IGNORE）。
        if (r.snsId != id.snsId || r.svrId != id.svrId) {
            logOnce("SNSC read mismatch want=${SnsCommentLogic.key(id)} " +
                "got=${r.snsId}:${r.svrId} -> refuse")
            return null
        }
        return r
    }

    /**
     * [SnsCommentLogic.classify] 要的「正文」。
     *
     * ⚠️ `curActionBuf` 抽不出可读片段时交回 **null（= 未知）**，只有**确认为空**时才交回 `""` ——
     * 这两者对 [SnsCommentLogic.classify] 的含义完全不同（ALIVE vs EMPTY）。
     * 抽出来的候选里取最长的那个当正文近似值（探针只用来分辨空/非空，够用）。
     */
    private fun contentOf(row: Row?): String? {
        if (row == null) return null
        val buf = row.curActionBuf
        if (buf == null || buf.isEmpty()) return ""
        return SnsCommentLogic.textCandidates(buf).maxByOrNull { it.length }
    }

    /** 只取 attrBuf 一列 —— 评论拦截点上唯一需要的东西，别 select * 拖三个 BLOB */
    private fun readAttrBuf(db: Any, snsId: Long): ByteArray? {
        var out: ByteArray? = null
        queryRow(db, "select $COL_ATTR from $TABLE_INFO where snsId=?", arrayOf(snsId.toString())) { cur ->
            val i = runCatching { XposedHelpers.callMethod(cur, "getColumnIndex", COL_ATTR) as? Int ?: -1 }
                .getOrDefault(-1)
            if (i >= 0) {
                out = runCatching { XposedHelpers.callMethod(cur, "getBlob", i) as? ByteArray }
                    .getOrNull()
            }
        }
        return out
    }

    private fun updateMethod(db: Any): Method? =
        runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "update" &&
                    it.parameterTypes.size == 4 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == ContentValues::class.java
            }
        }.getOrNull()

    private fun writeAttrBuf(db: Any, snsId: Long, buf: ByteArray): Boolean {
        val m = updateMethod(db) ?: return false
        return runCatching {
            val cv = ContentValues().apply { put(COL_ATTR, buf) }
            m.invoke(db, TABLE_INFO, cv, "snsId=?", arrayOf(snsId.toString()))
            true
        }.onFailure {
            logOnce("SNSC write attrBuf failed: ${it.javaClass.simpleName}: " +
                "${(it.cause ?: it).message?.take(80)}")
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ 归档

    @Volatile private var archive: CommentArchive? = null

    private fun archive(): CommentArchive? {
        archive?.let { return it }
        val ctx = HostContext.app()
        if (ctx == null) {
            if (!ctxWarned) {
                ctxWarned = true
                LogX.w("SNSC: host context not ready, archive deferred")
            }
            return null
        }
        return synchronized(this) {
            archive ?: runCatching { CommentArchive(ctx.applicationContext) }
                .onFailure { LogX.e("SNSC: open archive db failed", it) }
                .getOrNull()
                ?.also { archive = it }
        }
    }

    /**
     * 归档写库全部丢后台线程（BLOB 的序列化不该占着微信的 worker）。
     *
     * ⚠️ **必须整个包 runCatching**：后台线程里逃逸出去的异常没人接，
     * 会走默认的 UncaughtExceptionHandler —— 那是要把微信杀掉的。
     */
    internal fun saveAsync(row: Row?, v: SnsCommentLogic.Verdict) {
        if (row == null) {
            logOnce("SNSC archive skipped: no snapshot")
            return
        }
        val db = archive()
        if (db == null) {
            logOnce("SNSC archive skipped: db unavailable")
            return
        }
        Thread {
            runCatching {
                val ok = db.put(row, v)
                if (ok) archived.incrementAndGet()
                else logOnce("SNSC archive ignored (already archived) ${row.snsId}:${row.svrId}")
            }.onFailure {
                logOnce("SNSC archive failed: ${it.javaClass.simpleName}: " +
                    "${(it.cause ?: it).message?.take(80)}")
            }
        }.start()
    }

    /** 标签侧的数据源：`snsId -> 被拦下来的 commentSvrID 集合` */
    internal fun archivedIds(limit: Int = 500): Map<Long, Set<Long>> =
        runCatching { archive()?.ids(limit) }.getOrNull() ?: emptyMap()

    /**
     * 标签侧的数据源：`snsId -> 每条已删评论的正文候选`（每条评论一个 `List<String>`）。
     *
     * 用在「评论行 View ↔ commentSvrID 映射」还没取证时的**降级定位**：
     * 归档里存的是删之前的 `curActionBuf`，抽出来的正文候选可以跟界面上的 TextView
     * 文案对账（[SnsCommentLogic.textHit]），从而找到那条评论画在哪个 View 上。
     * 全程只用 Android 标准 API，不碰任何未取证的微信类名/字段名。
     */
    internal fun archivedTexts(limit: Int = 500): Map<Long, List<List<String>>> =
        runCatching { archive()?.texts(limit) }.getOrNull() ?: emptyMap()

    // ------------------------------------------------------------------ 诊断

    private fun logOnce(msg: String) {
        if (miscLogged.get() < LOG_MAX && miscLogged.incrementAndGet() <= LOG_MAX) LogX.i(msg)
    }

    /**
     * 探针。`sub`：`stat` / `list` / `form`（形态取证）/ `snap`(带 id) / `traceon` /
     * `traceoff` / `pending` / `refresh`
     */
    fun probe(sub: String, id: Long = 0L): String {
        return when (sub.lowercase()) {
            "stat" -> "SNSC stat enabled=${cfg().enabled} " +
                "seen=${seen.get()} bulk=${bulkSeen.get()} blocked=${blocked.get()} " +
                "archived=${archived.get()} attr[restore=${actRestore.get()} " +
                "keep=${actKeep.get()} skip=${actSkip.get()}] " +
                "trace=${if (traceOn) "on" else "off"} db=${snsDb != null}"

            "list" -> {
                val rows = runCatching { archive()?.list(10) }.getOrNull().orEmpty()
                if (rows.isEmpty()) {
                    "SNSCLS (empty) 还没有拦下过任何评论删除"
                } else {
                    buildString {
                        appendLine("SNSCLS archived=${archived.get()}")
                        rows.forEach { appendLine(it) }
                        append("(最多显示 10 条)")
                    }
                }
            }

            "snap" -> {
                if (id == 0L) return "SNSC snap: need snsId"
                val s = attrSnap[id]
                if (s == null) "SNSC snap snsId=$id (none) —— 没有快照，attrBuf 不会写回"
                else "SNSC snap snsId=$id size=${s.buf.size}B age=" +
                    "${(SystemClock.elapsedRealtime() - s.at) / 1000}s head=${SnsCommentLogic.hex(s.buf)}"
            }

            /**
             * ⚠️ **取证探针**（取证结论「朋友圈」#9 的待办）：删评论之后本地库里那条记录
             * 到底是「整行消失」「内容清空」还是「置了某个 flag」。
             *
             * 做法：归档里每条 `(snsID, commentSvrID)` 是**删之前**的快照，拿它去**当前**
             * SnsComment 里点查就是**删之后**的状态 —— 两者对比就是答案。
             * 一次跑完定形态，判据（[SnsCommentLogic.classify]）一行不用改。
             */
            "form" -> {
                val db = snsDb
                if (db == null) {
                    "SNSCFORM 还没抓到 SnsMicroMsg.db 句柄 —— 先去朋友圈刷一刷 / 让它" +
                        "删一条评论，拦截点会顺手记下库实例"
                } else {
                    val ids = runCatching { archive()?.ids(50) }.getOrNull().orEmpty()
                    if (ids.isEmpty()) {
                        "SNSCFORM (empty) 归档里没有已删评论 —— 先开「防撤回-评论」开关，" +
                            "让微信真删一条评论，再跑这条"
                    } else buildString {
                        val tally = HashMap<SnsCommentLogic.DelForm, Int>()
                        var shown = 0
                        for ((snsId, svrs) in ids) {
                            for (svr in svrs) {
                                val row = runCatching {
                                    readRow(db, SnsCommentLogic.Id(snsId, svr))
                                }.getOrNull()
                                val form = SnsCommentLogic.classify(
                                    SnsCommentLogic.CommentState(
                                        present = row != null,
                                        content = contentOf(row),
                                        flag = row?.commentflag,
                                    )
                                )
                                tally[form] = (tally[form] ?: 0) + 1
                                if (shown++ < 5) {
                                    appendLine(
                                        "SNSCFORM ${SnsCommentLogic.key(snsId, svr)} form=$form " +
                                            "present=${row != null} " +
                                            "buf=${row?.curActionBuf?.size ?: -1}B " +
                                            "flag=${row?.commentflag} " +
                                            "label=${SnsCommentLogic.labelText(form) ?: "(不打标)"}"
                                    )
                                }
                            }
                        }
                        append("SNSCFORM total=${tally.values.sum()} " + tally.entries
                            .sortedBy { it.key.name }
                            .joinToString(" ") { "${it.key}=${it.value}" })
                    }
                }
            }

            "traceon" -> {
                startTrace()
                "SNSC trace ON ${TRACE_WINDOW_MS / 1000}s —— 现在去删一条评论，" +
                    "看 SNSC trace 日志里 old/new 是否不同"
            }

            "traceoff" -> {
                stopTrace()
                "SNSC trace OFF"
            }

            "pending" -> buildString {
                appendLine("SNSC 待取证清单：")
                appendLine("T1 删评论 DAO 入口：class='$PENDING_DELETE_DAO_CLASS' " +
                    "method='$PENDING_DELETE_DAO_METHOD'（空 = 未取证）")
                appendLine("T2 删评论时是否重写 SnsInfo.attrBuf：用 probe(\"traceon\") 实证，" +
                    "看 old/new 字节数是否不同")
                appendLine("T2b 删评论后本地库里那条记录变成哪种形态（整行消失/内容清空/置 flag）：" +
                    "用 probe(\"form\") 实证，看 form= 的分布")
                append("T3 评论行 View 与 commentSvrID 的映射：见 SnsCommentTag.probe(\"pending\")")
            }

            "refresh", "reload" -> {
                refresh()
                "SNSC refreshed enabled=${config.enabled}"
            }

            else -> "SNSC probe: stat | list | snap <snsId> | traceon | traceoff | pending | refresh"
        }
    }
}

/**
 * 评论归档库。
 *
 * ⚠️ 为什么是**独立 db 文件**而不是加进 `wxgroup.db`：接线约束不允许改 `GroupDb.kt`，
 * 而 `GroupDb` 里没有评论表。所以用宿主的 Context 单独开一份
 * `/data/data/com.tencent.mm/databases/sns_comment_archive.db` —— 跟 `wxgroup.db`
 * 同目录同 uid，adb 一样能 pull 出来看。
 *
 * 表名、列名沿用 `SnsComment` 的**实测 16 列**，方便直接跟微信库对账。
 */
private class CommentArchive(ctx: Context) :
    SQLiteOpenHelper(ctx, "sns_comment_archive.db", null, VERSION) {

    companion object {
        private const val VERSION = 1
        private const val T = "sns_comment_archive"
        private const val AT = "archived_at"
        private const val ACT = "guard_act"

        private fun ddl(): String = buildString {
            append("CREATE TABLE IF NOT EXISTS $T (")
            append(
                SnsCommentLogic.SCHEMA.joinToString(",") { c ->
                    "`${c.name}` " + when (c.kind) {
                        SnsCommentLogic.Kind.INT -> "INTEGER"
                        SnsCommentLogic.Kind.TEXT -> "TEXT"
                        SnsCommentLogic.Kind.BLOB -> "BLOB"
                    }
                }
            )
            append(",$AT INTEGER NOT NULL")
            append(",$ACT TEXT")
            append(",PRIMARY KEY(`snsID`,`commentSvrID`))")
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(ddl())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 是首版；将来加列走 ALTER TABLE 增量迁移，不要 drop 重建（归档是唯一副本）
    }

    /**
     * 写一条。**首条为准**：已经有就不覆盖 —— 覆盖会把「删之前那一版」冲掉，
     * 而删之前的才是我们要留的原文。
     */
    fun put(r: SnsCommentGuard.Row, v: SnsCommentLogic.Verdict): Boolean {
        val cv = ContentValues().apply {
            put("snsID", r.snsId)
            put("parentID", r.parentId)
            put("isRead", r.isRead)
            put("createTime", r.createTime)
            put("talker", r.talker)
            put("type", r.type)
            put("isSend", r.isSend)
            put("curActionBuf", r.curActionBuf)
            put("refActionBuf", r.refActionBuf)
            put("commentSvrID", r.svrId)
            put("clientId", r.clientId)
            put("commentflag", r.commentflag)
            put("isSilence", r.isSilence)
            put("springWishFlag", r.springWishFlag)
            put("isReminding", r.isReminding)
            put("msgRelevanceType", r.msgRelevanceType)
            put(AT, System.currentTimeMillis())
            put(ACT, v.act.name + ":" + v.reason)
        }
        return writableDatabase.insertWithOnConflict(
            T, null, cv, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    /** 标签数据源：限定列 + ORDER BY + LIMIT，绝不无 ORDER BY 的裸 LIMIT */
    fun ids(limit: Int): Map<Long, Set<Long>> {
        val out = HashMap<Long, MutableSet<Long>>()
        readableDatabase.query(
            T, arrayOf("snsID", "commentSvrID"), null, null, null, null,
            "$AT DESC", limit.toString()
        ).use { c ->
            val i1 = c.getColumnIndexOrThrow("snsID")
            val i2 = c.getColumnIndexOrThrow("commentSvrID")
            while (c.moveToNext()) {
                out.getOrPut(c.getLong(i1)) { HashSet() } += c.getLong(i2)
            }
        }
        return out
    }

    /**
     * 降级定位用的数据源：`snsId -> 每条已删评论的正文候选`。
     * 抽不出正文的行直接丢（没东西可对账，留着只会让命中判据变松）。
     */
    fun texts(limit: Int): Map<Long, List<List<String>>> {
        val out = HashMap<Long, MutableList<List<String>>>()
        readableDatabase.query(
            T, arrayOf("snsID", "curActionBuf"), null, null, null, null,
            "$AT DESC", limit.toString()
        ).use { c ->
            val i1 = c.getColumnIndexOrThrow("snsID")
            val i2 = c.getColumnIndexOrThrow("curActionBuf")
            while (c.moveToNext()) {
                val cands = SnsCommentLogic.textCandidates(c.getBlob(i2))
                if (cands.isEmpty()) continue
                out.getOrPut(c.getLong(i1)) { ArrayList() } += cands
            }
        }
        return out
    }

    fun list(limit: Int): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.query(
            T, arrayOf("snsID", "commentSvrID", "talker", "createTime", "curActionBuf", ACT),
            null, null, null, null, "$AT DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out += "SNSCLS snsId=${c.getLong(0)} svrId=${c.getLong(1)} " +
                    "talker=${c.getString(2)?.take(12)} t=${c.getLong(3)} " +
                    "buf=${c.getBlob(4)?.size ?: 0}B at=${c.getLong(5)}"
            }
        }
        return out
    }
}
