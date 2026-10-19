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
import java.util.concurrent.atomic.AtomicLong

/**
 * 朋友圈**评论**防撤回（需求 6.2：「防止朋友圈评论撤回」）。
 *
 * ## 锚点（真机实测 + 2026-10-19 静态取证，都不是猜的）
 * ```
 * 真机: execSQL "delete from SnsComment where snsID = -3442401033659805004 and commentSvrID = 1"
 *       thread = [D]#worker      db = MicroMsg/<md5>/SnsMicroMsg.db
 * 静态: Lcom/tencent/mm/plugin/sns/storage/s1;->Q0(JJI)Z   ← 唯一的删除入口（实例方法）
 *         ↑ 唯一调用点 m2.onGYNetEnd（NetSceneSnsObjectOp，同时管十几种 op）
 * ```
 * 跟动态侧（[SnsGuard]）一样是**物理 DELETE 整行**，所以套路相同：拦 + 归档。
 *
 * ## 两层分工（这是本类的核心结构，别合并）
 *
 * | 层 | 位置 | 职责 | 能不能真拦住 |
 * |---|---|---|---|
 * | 语义层 | `s1.Q0(JJI)Z` | 拿到拆好的 `(snsID, commentSvrID, type)` | ❌ 不拦，只记 |
 * | 拦截层 | `wcdb SQLiteDatabase.execSQL` | 匹配 SQL、置 `result=null` 跳过方法体 | ✅ 唯一能真拦的 |
 *
 * 为什么不把拦截放在 `Q0`：它返回 `boolean`，被 `m2.onGYNetEnd` 当「删成功了吗」使用 ——
 * 置 true 会变成「行没删但界面照样刷新」的半死状态，置 false 会走失败分支。
 * 所以 `Q0` 只当**语义来源 + 兜底归档**，真正的拦截仍在 `execSQL`。
 *
 * ## ⚠️ 跟动态侧的关键差别：可见性不在本行
 * 动态：行在 `SnsInfo` 里，拦住 DELETE + 把 `localFlag` 的可见位（`or 16`）置回去就行。
 * 评论：行在 `SnsComment` 里，界面读的可见列表在 `SnsInfo.attrBuf`（protobuf）。
 *
 * ✅ **2026-10-19 静态取证已确认单条评论删除路径不碰 `attrBuf`** ——
 * `SnsInfo.setAttrBuf` 全 APK 11 个调用点里，`x2#O`（`processSnsDelAction`）只在
 * **整条动态被删**时才写；删单条评论走的是 `Q0` 里的 `delete from SnsComment`，
 * 与 `attrBuf` 无关。⇒ 行拦住了，UI 就还能显示。
 *
 * 但 `attrBuf` 那套代码（快照 / 差量 / 写回）**保留**：删动态路径仍可能需要它，
 * 且 `AUTO_RESTORE_ATTR` 保持 false（只在有实证时才允许写）。复核手段仍是
 * `probe("traceon")`（60 秒窗口挂 `update`，记 old/new 长度与头部 hex）。
 */
object SnsCommentGuard {

    // ==================================================================
    // ✅ T1 已静态取证定案（2026-10-19，见 `docs/取证结论.md` §「朋友圈评论」⑤/⑤-a）
    //    —— 下面的常量不再是「待填」，别再改回空串。
    // ==================================================================

    /**
     * T1：删评论的入口 —— `com.tencent.mm.plugin.sns.storage.s1.Q0(long, long, int): boolean`
     *
     * 语义：`(snsID, commentSvrID, type)` 三个结构化参数，内部拼
     * `delete from SnsComment where snsID=<J> and commentSvrID=<J> and type in (...)`，
     * 再交给 `s1.d`（`Lz84/z;`）的 `j(table, sql)` 执行。
     *
     * ## ⚠️ 两层分工，别合并
     * - **`execSQL` 层（已有）= 真正的拦截**：返回 void，置 `param.result = null` 即跳过方法体，
     *   SQL 压根不执行。这是唯一能「不让它删」的位置。
     * - **`Q0` 层（本类新增）= 拿语义**：参数已经是拆好的 long/long/int，
     *   不用解析 SQL 字符串、不会因拼接格式变化而失效。
     *
     * 为什么不在 `Q0` 上置 `param.result = true` 当拦截：`Q0` 返回 boolean，
     * 置 true 只会让 `m2.onGYNetEnd` 以为删成功，它随后仍会更新缓存/触发刷新 ——
     * 行没删但界面照样变，属于**半死状态**，比不拦更糟。
     */
    private const val DELETE_DAO_CLASS = "com.tencent.mm.plugin.sns.storage.s1"
    private const val DELETE_DAO_METHOD = "Q0"

    /** `s1.d` 的字段名：`Lz84/z;` 包装器，`s()` 取出真正的 `SQLiteDatabase` */
    private const val DAO_DB_FIELD = "d"

    /**
     * T2（未取证）：删评论时微信有没有重写 `SnsInfo.attrBuf`。
     * 静态取证已确认**单条评论删除路径不碰 attrBuf**（`x2#O` 的 processSnsDelAction
     * 只在整条动态被删时写），但冗余起见仍保守：`[AUTO_RESTORE_ATTR]` 保持 false。
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
    /** [logDao] 的限流窗口：静默这么久之后，日志额度重新开一轮 */
    private const val DAO_LOG_WINDOW_MS = 60_000L

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

    /** `s1.Q0` 层命中数（语义来源层；与 execSQL 层的 [seen] 分开计数，便于判断谁在生效） */
    private val daoSeen = AtomicInteger(0)
    private val actRestore = AtomicInteger(0)
    private val actKeep = AtomicInteger(0)
    private val actSkip = AtomicInteger(0)
    private val miscLogged = AtomicInteger(0)

    /** 见 [logDao]：语义层单独的日志额度，不与拦截层抢 */
    private val daoLogged = AtomicInteger(0)

    /** [logDao] 窗口起点（上次成功输出的 `elapsedRealtime`，0 = 从没打过） */
    private val daoLoggedAt = AtomicLong(0L)

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
        val dao = installDao(lpparam)
        LogX.i("SNSC installed hooks=${ok.joinToString(",")} " +
            "dao=$dao enabled=${config.enabled}")
    }

    /**
     * T1（已取证）：挂 `s1.Q0(long snsID, long commentSvrID, int type)` 拿**结构化语义**。
     *
     * ## 为什么需要它（`execSQL` 层已经能拦了，为什么还挂这一层）
     *
     * `execSQL` 层只能看到**拼好的 SQL 字符串**，要靠正则反解出 `(snsID, commentSvrID)`：
     * - 拼接格式一变（多一个空格、换成 `?` 占位、换成 `delete(...)` API）就静默失效；
     * - 反解失败时**没有第二道保险**，表现为「拦不到 → 评论真的被删了」。
     *
     * `Q0` 的参数本来就是拆好的 `long/long/int`，**零解析成本、零格式依赖**。
     * 所以这里把它当**语义来源**与**快照兜底**：即使 `execSQL` 层因格式变化没匹配上，
     * 归档里也已经有删之前的行内容了（但评论本身仍会被真删掉）。
     *
     * ## ⚠️ 本层**不拦截**（KDoc 曾误写为「兼作兜底拦截」，已订正）
     *
     * `Q0` 返回 `boolean`，`m2.onGYNetEnd` 拿它当「删成功了吗」。
     * ⚠️ 这里**不置 `param.result`** —— 置 true 只是骗 `m2`，行没删但界面照样刷新（半死状态）；
     * 置 false 又会让 `m2` 走失败分支（可能重试 / 报错）。
     * 于是本层的唯一动作是**记下这次删除意图 + 归档快照，然后放行**，
     * 一次都不 `increment blocked`。「不让它删」**完全**由 `execSQL` 层
     * （返回 void，置 null 即跳过方法体）和 `delete` 层（返回 int，置 1）负责。
     *
     * ## ⚠️ 未判死的疑点：Q0 这条删路径到底走不走 `execSQL`
     *
     * 上面「execSQL 层会拦」这个前提本身**没有判据**：`Q0` 内部拿 db 走的是
     * `s1.d` → `z84/z.s()`，再往下是否落到 `execSQL(String)` / `delete(...)` 是未知的。
     * 若它走的是另一条执行通道，那 execSQL 层就**根本盖不到 Q0 这条路径**，
     * 评论会被真删掉 —— 而且因为本层不拦，日志上也没任何异常迹象。
     *
     * 判据（**不重装，只看日志**）：`probe("stat")` 里若出现 `dao > 0 && blocked == 0`，
     * 说明语义层有活动、拦截层一次都没命中 ⇒ Q0 不走 execSQL。
     * 告警行以 `[DAO_NO_BLOCK]` 打头，见 `probe` 的 `stat` 分支。
     *
     * @return 挂上的方法数（0 = 没挂上，走 execSQL 单层）
     */
    private fun installDao(lpparam: XC_LoadPackage.LoadPackageParam): Int {
        val c = runCatching { XposedHelpers.findClass(DELETE_DAO_CLASS, lpparam.classLoader) }
            .getOrNull()
        if (c == null) {
            LogX.w("SNSC dao: class not found: $DELETE_DAO_CLASS -> 单靠 execSQL 层")
            return 0
        }
        // ⚠️ 按**参数签名**找，不按名字找：Q0 是 short 名，别的版本里同名不同义的风险不为零
        val m = runCatching {
            c.declaredMethods.firstOrNull {
                it.name == DELETE_DAO_METHOD &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == Long::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Long::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType
            }
        }.getOrNull()
        if (m == null) {
            LogX.w("SNSC dao: method not found: " +
                "$DELETE_DAO_CLASS#$DELETE_DAO_METHOD(JJI)Z -> 单靠 execSQL 层")
            return 0
        }
        return runCatching {
            XposedBridge.hookMethod(m, daoGuard)
            LogX.i("SNSC dao hooked ${DELETE_DAO_CLASS}#$DELETE_DAO_METHOD(JJI)Z")
            1
        }.onFailure {
            LogX.e("SNSC dao: hook failed, 单靠 execSQL 层", it)
        }.getOrDefault(0)
    }

    /**
     * `s1.Q0(snsID, commentSvrID, type)` 的钩子。
     *
     * ⚠️ 这里**绝不改 `param.result`**，也不抛异常 —— 见 [installDao] 的说明。
     * 唯一动作是把 `(snsID, commentSvrID)` 记进日志 + 触发一次归档快照。
     */
    private val daoGuard = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                val a = param.args
                // ⚠️ 以下一律用 `return@runCatching`（**局部返回**）而不是裸 `return`：
                //    裸 return 在 lambda 里是**非局部返回**，会直接从
                //    `beforeHookedMethod` 整个函数弹出去。本例因为 runCatching 是该函数
                //    最后一句、后面没有别的逻辑，两种写法行为恰好一致；
                //    但一旦有人在 `}.onFailure {}` 之后再加代码，裸 return 就会把那段
                //    （含收尾逻辑）静默跳过。显式局部返回把这个坑填掉，语义不变。
                if (a == null || a.size < 3) return@runCatching
                val snsId = a[0] as? Long ?: return@runCatching
                val svrId = a[1] as? Long ?: return@runCatching
                val type = a[2] as? Int ?: return@runCatching
                // ⚠️ type 三个值（1/2/8/16）语义尚未逐值确认，**一律不筛**：
                //    commentSvrID 已经唯一定位一条评论，多筛一次只会漏拦。
                val id = SnsCommentLogic.Id(snsId, svrId)
                daoSeen.incrementAndGet()
                if (bypass.get() == true) return@runCatching
                val c = cfg()
                // ⚠️ 关开关时**不打日志、不消耗额度**（见 [logDao]）：否则静默期会被关开关
                //    期间的删除吃光，等真开开关想看 dao 命中时就只剩静音了。
                //    关开关期间的活跃度看 probe("stat") 的 dao= 计数（不消耗额度）。
                if (!c.enabled) return@runCatching
                // 归档快照：db 走 thisObject.d → z84/z.s()
                val db = daoDb(param.thisObject)
                val row = if (db != null) runCatching { readRow(db, id) }.getOrNull() else null
                // ⚠️ 这里**不拦**（execSQL 层会拦），只归档。所以不 increment blocked
                logDao("SNSC dao ${SnsCommentLogic.key(id)} type=$type snap=${row != null} " +
                    "db=${db != null} txn=${if (db != null) inTx(db) else null} " +
                    "(语义来源，拦截在 execSQL 层)")
                // 归档：`overwriteAct=false` —— 只负责留住「删之前的行内容」。
                // ⚠️ 这里先到，写进去的 act=SKIP:dao-snapshot 只是占位诊断值；
                //    随后 verdict 层（execSQL / delete）再写同一主键时，
                //    行内容被 IGNORE 保住不变，而 guard_act 会被 UPDATE 覆盖成真实 verdict
                //    （见 [CommentArchive.put]）。这样既留住最原始那份快照，又不丢 verdict。
                if (row != null) saveAsync(row, SnsCommentLogic.Verdict(
                    SnsCommentLogic.Act.SKIP, "dao-snapshot", 0, 0,
                ))
            }.onFailure {
                // ⚠️ 这个钩子挂在微信的删除主路径上，异常必须吞掉：
                //    逃逸出去会把微信搞崩，而它只是「多拿一份语义」的辅助层
                logDao("SNSC dao hook err: ${it.javaClass.simpleName}")
            }
        }
    }

    /**
     * 从 `s1` 实例取真正的 `SQLiteDatabase`：
     * ```
     * s1.d            : Lz84/z;                      ← 包装器
     *   .s()          : com.tencent.wcdb.database.SQLiteDatabase
     * ```
     * 任一步失败返回 null（调用方按「读不到行」处理，不影响拦截）。
     */
    private fun daoDb(dao: Any?): Any? {
        if (dao == null) return null
        val wrapper = runCatching { XposedHelpers.getObjectField(dao, DAO_DB_FIELD) }
            .getOrNull() ?: return null
        return runCatching { XposedHelpers.callMethod(wrapper, "s") }.getOrNull()
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
                    val sql = a[0] as? String
                    val binds = a.getOrNull(1) as? Array<*>?
                    val id = SnsCommentLogic.matchDeleteSql(sql, binds)
                    if (id != null) {
                        // 取证期诊断：把**原始 SQL 与绑定值**打出来。
                        // 只有它能区分「id 解析错」与「这一行确实查不到」——
                        // 现象是 `blocked ... snap=false`（拦到了但读不到快照），
                        // 而日志里既无 read err 也无 mismatch，只能靠原文判断。
                        // ⚠️ 只在匹配成功（= 真要删一条评论，低频）时打，不污染热路径。
                        LogX.i("SNSC delsql id=${SnsCommentLogic.key(id)} " +
                            "binds=${binds?.joinToString(",") { it?.toString() ?: "null" }} " +
                            "sql=${sql?.take(200)}")
                    }
                    handle(param, id, true)
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
        logOnce("SNSC blocked ${SnsCommentLogic.key(id)} snap=${row != null} " +
            "txn=${inTx(db)} act=${v.act} ${v.reason}")
        // ⚠️ overwriteAct=true：行内容保留 dao 层那份快照（首条为准），
        //    但 guard_act 必须被真实 verdict 覆盖，否则归档里恒为 SKIP:dao-snapshot。
        saveAsync(row, v, overwriteAct = true)
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

    /**
     * 当前连接是不是在事务里 —— 用来区分 `snap=false` 的两种成因：
     *
     * - `txn=true`：**撞上了 wcdb 的写事务**（我们是在 `beforeHookedMethod` 里同步读，
     *   跟微信自己的写操作同一个连接），读不到只是暂时的，不是漏档；
     * - `txn=false`：这行**真的不在库里**（我们解析出的 id 不对 / 或已经被删过）。
     *
     * ⚠️ 反射拿 `inTransaction()`：db 实例是 wcdb 的 `SQLiteDatabase`，编译期没有这个类型。
     *    取不到就回 `null`，日志里显示 `txn=?`。
     */
    private fun inTx(db: Any): Boolean? =
        runCatching { XposedHelpers.callMethod(db, "inTransaction") as? Boolean }.getOrNull()

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
                    "${(it.cause ?: it).message?.take(80)} txn=${inTx(db)}")
            }
            .getOrNull() ?: return false
        return try {
            if (XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean != true) {
                // ⚠️ 这里**必须**留诊断：`moveToFirst=false` 有两种完全不同的原因，
                //    而它们导出的结论相反 ——
                //    ① 这行本来就不存在（我们解析出的 id 是错的 / 已经被删过）
                //    ② 游标是空的 / 反射调用本身失败（反射签名不对）
                //    ③ **撞上了 wcdb 的写事务**（本层是在 beforeHookedMethod 里同步读）
                //    排查现场（2026-10-19）就是卡在这：`blocked snap=false` 但日志里
                //    read err / mismatch 都没有，无法区分。
                //    ⇒ 所以下面顺带打出 `txn=`（见 [inTx]）：
                //      txn=true 是「撞锁」，属于暂时读不到，不是漏档；
                //      txn=false 才是「这行真不在库里」。
                logOnce("SNSC read miss sql=${sql.take(120)} args=${argsIn.joinToString(",")} " +
                    "cols=${runCatching { XposedHelpers.callMethod(cur, "getColumnCount") }.getOrNull()} " +
                    "txn=${inTx(db)}")
                return false
            }
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
    /**
     * @param overwriteAct 传给 [CommentArchive.put]：verdict 层（[handle]）传 true，
     *                     让真实 verdict 覆盖 dao 快照层预先写下的 `guard_act`。
     */
    internal fun saveAsync(row: Row?, v: SnsCommentLogic.Verdict, overwriteAct: Boolean = false) {
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
                val isNew = db.put(row, v, overwriteAct)
                if (isNew) archived.incrementAndGet()
                // ⚠️ 不说 "ignored"：行内容本来就该保留（首条为准），
                //    overwriteAct=true 时 guard_act 已经回写成真实 verdict 了。
                else logOnce("SNSC archive: row already present (content kept) " +
                    "${row.snsId}:${row.svrId}")
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
     * 标签侧的数据源（**主路径**）：`snsId -> 已删评论的渲染键集合`。
     *
     * 渲染键 = `"createTime:talker:type"`（见 [SnsCommentLogic.RenderKey]）。
     * 这是唯一能在**当前微信版本**上打通 UI 的键 —— UI 层不带 commentSvrID。
     */
    internal fun archivedRenderKeys(limit: Int = 500): Map<Long, Set<String>> =
        runCatching { archive()?.renderKeys(limit) }.getOrNull() ?: emptyMap()

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
     * `Q0` 语义层的独立日志额度。
     *
     * ⚠️ **不能跟 [logOnce] 共用计数器**：那一份只有 [LOG_MAX]（5）条额度，
     * 被 `blocked` / `read miss` 这些**拦截层关键日志**占满后，`dao` 层就一句都打不出来 ——
     * 而真机验收恰恰要看「dao 有没有命中」。所以两层各留一份，互不挤占。
     *
     * ⚠️ **额度是**按时间窗重置**的，不是「打满 5 条就永久静音」**：
     * 距上次成功输出超过 [DAO_LOG_WINDOW_MS]（60s）就把计数归零，重新给
     * [LOG_MAX] 条。否则真机跑一轮之后，下一轮取证什么都看不到。
     *
     * ⚠️ **关开关的分支不进这个门**（`daoGuard` 里 `!enabled` 直接返回）：
     * 关开关时每条删除都要打一次，几分钟就能把额度吃光，
     * 等真把开关打开想看 `dao` 命中时已经静音了。关开关期间的活跃度看
     * `probe("stat")` 的 `dao=` 计数即可，那个不消耗任何额度。
     */
    private fun logDao(msg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - daoLoggedAt.get() >= DAO_LOG_WINDOW_MS) daoLogged.set(0)
        if (daoLogged.get() < LOG_MAX && daoLogged.incrementAndGet() <= LOG_MAX) {
            LogX.i(msg)
            daoLoggedAt.set(now)
        }
    }

    /**
     * 探针。`sub`：`stat` / `list` / `form`（形态取证）/ `snap`(带 id) / `traceon` /
     * `traceoff` / `pending` / `refresh`
     */
    fun probe(sub: String, id: Long = 0L): String {
        return when (sub.lowercase()) {
            "stat" -> {
                val d = daoSeen.get()
                val b = blocked.get()
                // ⚠️ 一次性判死「Q0 到底走不走 execSQL」（见 [installDao] 的疑点段）：
                //    dao 层有活动（d > 0）但 execSQL 层一次都没拦到（b == 0）
                //    ⇒ Q0 的删除根本不过 execSQL，评论会被真删。
                //    反过来 blocked > 0 即证明这条路径被 execSQL 层覆盖住了。
                //    整行以 [DAO_NO_BLOCK] 打头，方便不装机时直接 grep 日志。
                val warn = if (d > 0 && b == 0) {
                    // ⚠️ 开关关着时本来就不拦（blocked 必然为 0），先提示开开关，
                    //    免得把「开关没开」误读成「Q0 不走 execSQL」。
                    if (cfg().enabled) {
                        " *** [DAO_NO_BLOCK] dao=$d>0 但 blocked=0 —— dao 层有活动而 " +
                            "execSQL 层一次没拦到 ⇒ Q0 可能不走 execSQL，评论会被真删！" +
                            "请核对 execSQL/delete 两层是否覆盖了这条路径 ***"
                    } else {
                        " *** [DAO_NO_BLOCK] dao=$d>0 但 blocked=0（开关当前是**关**的，" +
                            "不拦属正常）—— 要判 Q0 走不走 execSQL，请先开开关再删一条重测 ***"
                    }
                } else {
                    ""
                }
                "SNSC stat enabled=${cfg().enabled} " +
                    "seen=${seen.get()} dao=$d bulk=${bulkSeen.get()} " +
                    "blocked=$b " +
                    "archived=${archived.get()} attr[restore=${actRestore.get()} " +
                    "keep=${actKeep.get()} skip=${actSkip.get()}] " +
                    "trace=${if (traceOn) "on" else "off"} db=${snsDb != null}" + warn
            }

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
                appendLine("SNSC 取证状态（T1/T2 已于 2026-10-19 静态取证定案）：")
                appendLine("T1 删评论入口：$DELETE_DAO_CLASS#$DELETE_DAO_METHOD(JJI)Z  ← 已挂，" +
                    "走 $DAO_DB_FIELD → z84/z.s() 取 db")
                appendLine("   分层：execSQL 层**拦**（seen/blocked 计数），Q0 层**拿语义**（dao 计数）")
                append("T2 attrBuf 是否被重写：静态结论=单条评论不碰 attrBuf；" +
                    "仍可用 probe(\"traceon\") 复核，form 分布用 probe(\"form\")")
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
     * 写一条。**行内容「首条为准」**：INSERT 用 [SQLiteDatabase.CONFLICT_IGNORE]，
     * 已经有就不覆盖 —— 覆盖会把「删之前那一版」冲掉，而删之前的才是我们要留的原文。
     *
     * @param overwriteAct true = 行已存在时，再用 `UPDATE ... SET $ACT=?`
     *        **只覆盖 [ACT] 这一列**（行内容一个字节都不动）。
     *        判据：dao 快照层先写 `act=SKIP:dao-snapshot`，随后 verdict 层
     *        （[SnsCommentGuard.handle]，execSQL / delete 两条形态）再写同一主键；
     *        若只靠 IGNORE，归档里 `guard_act` 会**恒为 dao 层的值，真实 verdict 永久丢失**。
     *        用 UPDATE 而不是 REPLACE / delete+insert：后两者会把整行内容一起冲掉。
     *        幂等：同一主键重复 UPDATE 同一个 act，结果恒定。
     * @return true = **新插入**了一行（调用方据此 +1 归档计数）；
     *         false = 行早就在了（内容保留，[ACT] 可能已回写）
     */
    fun put(
        r: SnsCommentGuard.Row,
        v: SnsCommentLogic.Verdict,
        overwriteAct: Boolean = false,
    ): Boolean {
        val act = v.act.name + ":" + v.reason
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
            put(ACT, act)
        }
        val inserted = writableDatabase.insertWithOnConflict(
            T, null, cv, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
        if (inserted || !overwriteAct) return inserted
        val cvAct = ContentValues()
        cvAct.put(ACT, act)
        writableDatabase.update(
            T, cvAct, "`snsID`=? AND `commentSvrID`=?",
            arrayOf(r.snsId.toString(), r.svrId.toString()),
        )
        return false
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
     * 标签数据源（**渲染侧主路径**）：`snsId -> 该动态下已删评论的渲染键集合`。
     *
     * ⚠️ 为什么不用 `commentSvrID` 做 key：真机实测 UI 层评论 proto 不带它
     * （`q54/q95` 的两个 long 字段恒 0，见 `docs/取证结论.md` ⑩ 段）。
     * 改用微信自己 `isExistComment` 的判重维度 `(createTime, talker, type)`。
     *
     * 抽不出 talker 的行**直接丢** —— 没 talker 就无法无歧义定位，留着只会让命中判据变松
     * （宁可漏标，不可误标：误标会把没被删的评论标成 `[已删除]`，用户会立刻发现）。
     */
    fun renderKeys(limit: Int): Map<Long, Set<String>> {
        val out = HashMap<Long, MutableSet<String>>()
        readableDatabase.query(
            T, arrayOf("snsID", "createTime", "talker", "type"), null, null, null, null,
            "$AT DESC", limit.toString()
        ).use { c ->
            val i1 = c.getColumnIndexOrThrow("snsID")
            val i2 = c.getColumnIndexOrThrow("createTime")
            val i3 = c.getColumnIndexOrThrow("talker")
            val i4 = c.getColumnIndexOrThrow("type")
            while (c.moveToNext()) {
                val k = SnsCommentLogic.renderKey(c.getLong(i2), c.getString(i3), c.getInt(i4))
                    ?: continue
                out.getOrPut(c.getLong(i1)) { HashSet() } += SnsCommentLogic.keyOf(k)
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
