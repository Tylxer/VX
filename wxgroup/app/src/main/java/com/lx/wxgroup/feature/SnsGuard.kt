package com.lx.wxgroup.feature

import android.content.ContentValues
import android.os.SystemClock
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 朋友圈动态防删除（需求 6.2 第二项：防止朋友圈动态撤回）。
 *
 * ## 锚点是真机实测抓出来的（不是猜的）
 *
 * 删一条朋友圈动态，微信做的是**物理 DELETE 整行**（这点跟消息撤回完全不同 ——
 * 消息撤回是 `update` 把行改写成撤回态，可以拦也可以还原；这里是直接把行抹掉）：
 * ```
 * SQLiteDatabase.delete   thread=main
 *   db    = /data/user/0/com.tencent.mm/MicroMsg/<md5>/SnsMicroMsg.db
 *   table = SnsInfo   whereClause = "snsId=?"   whereArgs = [-3447438253133393222]
 * ```
 * 顺带抓到的**评论删除**（本轮不做，留档）：
 * ```
 * execSQL "delete from SnsComment where snsID = -3442401033659805004 and commentSvrID = 1 ..."
 *   thread=[D]#worker
 * ```
 *
 * ## 所以策略是「拦 + 归档」，不是「拦 + 改写」
 * - 拦住 `DELETE`，行留在 `SnsInfo` 里 → 朋友圈界面上它照常显示；
 * - 同时把整行（含 `content` / `attrBuf` / `postBuf` 三个 BLOB）**复制一份进我们自己的库**。
 *   因为拦得住这一次，拦不住微信后续的清理或重装 —— 归档才是内容真正的保险，
 *   也是 6.4「自动导出朋友圈动态」的数据源。
 *
 * ## ⚠️ 作用域的现实情况（跟消息撤回不一样，别套用）
 * 消息撤回能靠 `isSend` / 文案区分「自己撤回 / 他人撤回」，然后只拦他人的。
 * 朋友圈**没有这个对称关系**：微信本地只在你删自己动态、或做本地清理时才 DELETE，
 * 「别人删了他自己的动态」在本地通常不产生 DELETE（只是下次刷新服务端不返回了）。
 * 所以这里**不区分作者**，一律拦 —— 否则开关永远不生效。
 * 说白了它的实际效果是「不让微信把朋友圈动态从本地库里抹掉」，偏留档 / 防误删。
 *
 * ## 拦在 SQLite 层而不是 hook 某个 `plugin.sns.*`
 * `SnsInfo` 表名、字段名都是未混淆的，比混淆的 `a1.b2` 抗版本。
 */
object SnsGuard {

    private const val TABLE = "SnsInfo"

    /** 朋友圈是**独立的库**，不是主消息库 `EnMicroMsg.db`（防撤回那边踩过「多个 db」的坑） */
    private const val DB_MARK = "SnsMicroMsg.db"

    private const val CFG_TTL_MS = 3000L

    private data class Config(
        val enabled: Boolean,
        val debugLog: Boolean,
    )

    @Volatile
    private var config = Config(false, false)

    @Volatile
    private var cfgAt = 0L

    /**
     * 「这次删除是我们自己发的」标记。
     * 目前只有诊断用的 `snsdel` 会走，真删必须绕过拦截，否则永远删不掉。
     */
    internal val bypass = ThreadLocal<Boolean>()

    /** 朋友圈库实例，诊断（snsdump）要用；只在命中删/execSQL 时顺手记下 */
    @Volatile
    private var snsDb: Any? = null

    private val blocked = AtomicInteger(0)
    private val archived = AtomicInteger(0)

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cls = runCatching {
            XposedHelpers.findClass(
                "com.tencent.wcdb.database.SQLiteDatabase",
                lpparam.classLoader,
            )
        }.getOrNull()
        if (cls == null) {
            LogX.w("SNS: wcdb SQLiteDatabase not found, sns guard disabled")
            return
        }
        runCatching { refresh() }
            .onFailure { LogX.w("SNS refresh failed: ${it.javaClass.simpleName}") }

        val ok = mutableListOf<String>()
        runCatching { XposedBridge.hookAllMethods(cls, "delete", deleteGuard) }
            .onSuccess { if (it.isNotEmpty()) ok += "delete" }
            .onFailure { LogX.e("SNS: hook delete failed", it) }
        runCatching { XposedBridge.hookAllMethods(cls, "execSQL", sqlGuard) }
            .onSuccess { if (it.isNotEmpty()) ok += "execSQL" }
            .onFailure { LogX.e("SNS: hook execSQL failed", it) }
        // ⚠️ 只挂 delete 和 execSQL 两个。update / insert / query 一律不挂：
        // 那是微信所有表（message / rcontact …）的写路径，挂上去等于给每次写库都加一层开销，
        // 而我们要的信息（原 localFlag）用 `| VISIBLE_BIT` 兜底已经够了。
        if (ok.isEmpty()) {
            LogX.w("SNS: no hook installed, sns guard disabled")
            return
        }
        LogX.i("SNS installed hooks=${ok.joinToString(",")} enabled=${config.enabled}")
    }

    fun refresh() {
        config = Config(
            enabled = Kv.getBool(Keys.ANTIREVOKE_SNS),
            debugLog = Kv.getBool(Keys.DEBUG_LOG),
        )
        cfgAt = SystemClock.elapsedRealtime()
    }

    private fun cfg(): Config {
        if (SystemClock.elapsedRealtime() - cfgAt > CFG_TTL_MS) refresh()
        return config
    }

    // ------------------------------------------------------------------ 钩子

    private val deleteGuard = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching { onDelete(param) }
                .onFailure { LogX.w("SNS delete hook err: ${it.javaClass.simpleName}") }
        }
    }

    private val sqlGuard = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching { onExecSql(param) }
                .onFailure { LogX.w("SNS execSQL hook err: ${it.javaClass.simpleName}") }
        }
    }


    /**
     * `delete(String table, String whereClause, String[]|Object[] whereArgs)`
     * （wcdb 两个重载的第三个参数类型不同，按位置取，不按类型取）
     */
    private fun onDelete(param: XC_MethodHook.MethodHookParam) {
        val a = param.args ?: return
        if (a.isEmpty()) return
        val table = a[0] as? String
        val id = SnsSql.matchDeleteCall(table, a.getOrNull(1) as? String, a.getOrNull(2) as? Array<*>?)
        // 不是「删 SnsInfo 的某一行」就直接走（含 `createTime < ?` 这类清理），别去反射拿路径
        if (id == null) return
        val c = cfg()
        if (bypass.get() == true) return
        val db = param.thisObject ?: return
        if (!isSnsDb(db)) return
        if (!c.enabled) {
            LogX.i("SNS delete seen (off) snsId=$id")
            return
        }
        // ⚠️ 快照必须**同步**取：只在拦截之前这一刻能确定行还在。
        // 放到后台读会有漏档窗口（库被关 / 进程被杀 / 微信另起一条清理抢先删掉），
        // 而漏档是永久的，比多花一两毫秒严重得多。这只是一次主键点查，不是扫表。
        val row = runCatching { readRow(db, id) }.getOrNull()
        // 拦下来：返回 1 让微信以为删掉了（不这么做微信可能重试或报错）
        param.result = 1
        blocked.incrementAndGet()
        // ⚠️ 光拦 DELETE 不够：微信删动态是**两步** —— 先把 localFlag 的可见位清掉、
        // 再删行。我们只拦了第二步，行虽然还在，但时间线查询要求 localFlag 在白名单里，
        // 结果就是「库里有、界面没有」。所以拦完必须把可见位写回去。
        val flag = restoreVisibility(db, id, row?.localFlag ?: 0)
        LogX.i("SNS blocked delete snsId=$id snap=${row != null} flag=$flag total=${blocked.get()}")
        saveAsync(row)
    }

    /** `execSQL(String sql)` / `execSQL(String sql, Object[] bindArgs)` */
    private fun onExecSql(param: XC_MethodHook.MethodHookParam) {
        val a = param.args ?: return
        if (a.isEmpty()) return
        val sql = a[0] as? String
        // ⚠️ 参数化 SQL（`where snsId=?`）的 id 在第二个参数里，必须一起传进去，
        // 否则 execSQL 形态对参数化写法**完全失效**（第一版就漏了）
        val id = SnsSql.matchDeleteSql(sql, a.getOrNull(1) as? Array<*>?)
        if (id == null) return
        val c = cfg()
        if (bypass.get() == true) return
        val db = param.thisObject ?: return
        if (!isSnsDb(db)) return
        if (!c.enabled) {
            LogX.i("SNS delete seen (off) snsId=$id")
            return
        }
        val row = runCatching { readRow(db, id) }.getOrNull()
        // execSQL 返回 void：置 null 即 returnEarly，跳过原方法体
        param.result = null
        blocked.incrementAndGet()
        val flag = restoreVisibility(db, id, row?.localFlag ?: 0)
        LogX.i("SNS blocked delete-sql snsId=$id snap=${row != null} flag=$flag total=${blocked.get()}")
        saveAsync(row)
    }

    /**
     * 把可见位写回去。
     *
     * 时间线的查询是 `select *,rowid from SnsInfo where (localFlag in (16,2064,18,2066,…))`
     * —— 白名单里每个值都置了 bit4（16）。微信删动态时把这一位清掉（实测落成 2），
     * 所以我们把它置回去即可。**优先用记住的原值**（能保住 2048 之类其它位），
     * 记不住就退 `原值 | 16`（2 → 18，也在白名单里）。
     *
     * 用 `原值 | 16`（2 → 18）而不是去查「原来是多少」：查原值得挂 update/insert 钩子，
     * 那是微信**所有表**的写路径（message / rcontact …），为这点信息给每次写库加开销不值当。
     *
     * @return 实际写进去的 localFlag；0 = 没写
     */
    private fun restoreVisibility(db: Any, snsId: Long, curFlag: Int): Int {
        if (curFlag and VISIBLE_BIT != 0) return 0 // 本来就可见，不用动
        val target = curFlag or VISIBLE_BIT
        val m = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "update" &&
                    it.parameterTypes.size == 4 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == ContentValues::class.java
            }
        }.getOrNull() ?: return 0
        return runCatching {
            val cv = ContentValues().apply { put("localFlag", target) }
            m.invoke(db, TABLE, cv, "snsId=?", arrayOf(snsId.toString()))
            LogX.i("SNS visibility restored snsId=$snsId $curFlag -> $target")
            target
        }.onFailure {
            LogX.w("SNS restore flag failed: ${it.javaClass.simpleName}: " +
                "${(it.cause ?: it).message?.take(80)}")
        }.getOrDefault(0)
    }

    /**
     * 是不是朋友圈库。
     *
     * ⚠️ 只在**已经匹配到表名**之后才调（delete/execSQL 频次都很低），
     * 绝不能放在 query/update 钩子里 —— 那会每次都反射，拖慢微信。
     */
    private fun isSnsDb(db: Any): Boolean {
        val p = runCatching { XposedHelpers.callMethod(db, "getPath") as? String }
            .onFailure {
                // 拿不到路径 = 整条链路静默失效，必须留一次告警，不然查不到原因
                if (!pathWarned) {
                    pathWarned = true
                    LogX.w("SNS getPath failed on ${db.javaClass.name}: ${it.javaClass.simpleName}")
                }
            }
            .getOrNull()
        if (p == null) {
            if (!pathWarned) {
                pathWarned = true
                LogX.w("SNS getPath returned null on ${db.javaClass.name}")
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

    @Volatile
    private var pathWarned = false

    /**
     * 时间线查询 `localFlag in (16,2064,18,2066,24,2072,…)` 里**每个值都置了 bit4(16)**。
     * 微信删动态时先清掉这一位（实测落成 2），拦完 DELETE 必须置回去，否则界面不显示。
     */
    private const val VISIBLE_BIT = 16

    // ------------------------------------------------------------------ 归档

    /**
     * 把快照写进模块库（丢后台线程：三个 BLOB 的序列化和写库不该占着主线程）。
     *
     * ⚠️ **必须整个包 runCatching**：后台线程里逃逸出去的异常没人接，
     * 会走默认的 UncaughtExceptionHandler —— 那是要把微信杀掉的。
     */
    private fun saveAsync(row: GroupDb.SnsRow?) {
        if (row == null) {
            LogX.w("SNS archive skipped: no snapshot")
            return
        }
        Thread {
            runCatching {
                val ok = GroupDb.current()?.archiveSns(row) ?: false
                if (ok) archived.incrementAndGet()
                LogX.i("SNS archived snsId=${row.snsId} ok=$ok")
            }.onFailure {
                LogX.w("SNS archive failed: ${it.javaClass.simpleName}: " +
                    "${(it.cause ?: it).message?.take(80)}")
            }
        }.start()
    }

    /**
     * 读整行。
     *
     * ⚠️ 用 `select *` + **按列名取**，不要按 select 列表的下标取：
     * 只要有一列名对不上（`withTaHasOther` / `serverExtFlag` / `pravited` 这些都可能），
     * rawQuery 直接抛异常、被外层吞掉，结果是**归档永久静默失败**，日志里只有一行警告。
     *
     * ⚠️ `snsId` / `createTime` 必须按 **Long** 读：真机的 snsId 是
     * `-3447438253133393222` 这种 64 位值，先 `toInt()` 会截断成 32 位 ——
     * 归档主键全错、去重对不上、清理删不掉，而且截断后还会撞主键，
     * 让第二条归档被 CONFLICT_IGNORE 静默吞掉。
     *
     * ⚠️ rawQuery 的 Method **绝不能跨库缓存**：AOSP 的 Method 拿去调 wcdb 实例会抛
     * `IllegalArgumentException: Expected receiver of type android.database.sqlite.SQLiteDatabase`。
     * 所以每次都按 `db.javaClass` 现找（探针阶段踩过，静默失败白跑一轮）。
     */
    private fun readRow(db: Any, snsId: Long): GroupDb.SnsRow? {
        val m = rawQueryMethod(db) ?: return null
        val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
            arrayOf(snsId.toString())
        } else {
            arrayOf<Any>(snsId.toString())
        }
        val cur = runCatching { m.invoke(db, "select * from $TABLE where snsId=?", args) }
            .onFailure {
                LogX.w("SNS read err ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}")
            }
            .getOrNull() ?: return null
        return try {
            if (XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean != true) return null
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
            val gotId = longOf("snsId")
            // ⚠️ 拿到手的 id 对不上就**拒绝归档**，别写残档进去：
            // 残档会占着主键，之后真想归档这条也写不进去了（首条为准 + CONFLICT_IGNORE）。
            // 对不上只有两种可能：列名变了（schema 变了）或游标读错了，两种情况都得留证重查。
            if (gotId != snsId) {
                LogX.w("SNS read mismatch: want=$snsId got=$gotId -> refuse archive")
                return null
            }
            GroupDb.SnsRow(
                snsId = gotId,
                localFlag = intOf("localFlag"),
                createTime = longOf("createTime"),
                type = intOf("type"),
                sourceType = intOf("sourceType"),
                subType = intOf("subType"),
                serverExtFlag = intOf("serverExtFlag"),
                userName = textOf("userName"),
                head = textOf("head"),
                stringSeq = textOf("stringSeq"),
                content = blobOf("content"),
                attrBuf = blobOf("attrBuf"),
                postBuf = blobOf("postBuf"),
            )
        } finally {
            runCatching { XposedHelpers.callMethod(cur, "close") }
        }
    }

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

    // ------------------------------------------------------------------ 诊断

    /** 归档清单（读我们自己的库，不碰微信库） */
    fun listArchived(limit: Int = 20): List<String> {
        val list = runCatching { GroupDb.current()?.listSnsArchive(limit) }
            .getOrNull().orEmpty()
        if (list.isEmpty()) return listOf("(empty) 还没有归档过任何动态")
        return listOf("SNSLS archived=${list.size}") + list.map { r ->
            "SNSLS snsId=${r.snsId} user=${r.userName?.take(12)} t=${r.createTime} " +
                "type=${r.type} src=${r.sourceType} " +
                "content=${r.content?.size ?: 0}B attr=${r.attrBuf?.size ?: 0}B " +
                "post=${r.postBuf?.size ?: 0}B"
        }
    }

    /**
     * 精确查一条：拦下来之后这行到底还在不在、`localFlag` 是多少。
     *
     * 用来区分两种情况：
     * - 行还在 → 微信只是**内存里/UI 上**把它摘掉了，重新进朋友圈或下拉刷新就该回来；
     * - 行没了 → 微信还有别的删除路径（execSQL 之外的），我们没拦住。
     */
    fun one(snsId: Long): List<String> {
        val db = snsDb ?: return listOf("no sns db yet")
        val m = rawQueryMethod(db) ?: return listOf("no rawQuery on ${db.javaClass.name}")
        return runCatching {
            val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
                arrayOf(snsId.toString())
            } else {
                arrayOf<Any>(snsId.toString())
            }
            val cur = m.invoke(
                db,
                "select snsId,userName,createTime,type,sourceType,localFlag," +
                    "length(ifnull(content,x'')),length(ifnull(attrBuf,x''))," +
                    "length(ifnull(postBuf,x'')) from $TABLE where snsId=?",
                args,
            ) ?: return listOf("null cursor")
            try {
                if (XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean != true) {
                    return listOf("SNS1 snsId=$snsId GONE (库里已经没有这一行)")
                }
                fun l(i: Int): Long =
                    runCatching { XposedHelpers.callMethod(cur, "getLong", i) as? Long ?: 0L }
                        .getOrDefault(0L)
                fun s(i: Int): String? =
                    runCatching { XposedHelpers.callMethod(cur, "getString", i) as? String }
                        .getOrNull()
                listOf(
                    "SNS1 snsId=${l(0)} EXISTS user=${s(1)?.take(12)} t=${l(2)} " +
                        "type=${l(3)} src=${l(4)} localFlag=${l(5)} " +
                        "content=${l(6)}B attr=${l(7)}B post=${l(8)}B"
                )
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
        }.getOrElse {
            listOf("err ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}")
        }
    }

    /** 微信库里现在还有多少条 SnsInfo（验证「拦下来后行还在」） */
    fun dump(limit: Int = 5): List<String> {
        val db = snsDb ?: return listOf("no sns db yet (先删一条动态，或让微信刷一次朋友圈)")
        val m = rawQueryMethod(db) ?: return listOf("no rawQuery on ${db.javaClass.name}")
        return runCatching {
            // 这个查询没有占位参数，两个重载都直接给 null
            val args: Any? = null
            val out = mutableListOf<String>()
            val cur = m.invoke(
                db,
                "select snsId,userName,createTime,type,sourceType,localFlag from $TABLE " +
                    "order by createTime desc limit $limit",
                args,
            ) ?: return listOf("null cursor")
            try {
                var n = 0
                while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                    val id = XposedHelpers.callMethod(cur, "getLong", 0) as? Long ?: 0
                    val u = XposedHelpers.callMethod(cur, "getString", 1) as? String
                    val t = XposedHelpers.callMethod(cur, "getLong", 2) as? Long ?: 0
                    val ty = XposedHelpers.callMethod(cur, "getLong", 3) as? Long ?: 0
                    val src = XposedHelpers.callMethod(cur, "getLong", 4) as? Long ?: 0
                    val fl = XposedHelpers.callMethod(cur, "getLong", 5) as? Long ?: 0
                    out += "SNSDMP snsId=$id user=${u?.take(12)} t=$t type=$ty src=$src flag=$fl"
                    n++
                }
                out.add(0, "SNSDMP rows=$n (limit=$limit)")
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
            out
        }.getOrElse { listOf("err ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}") }
    }

    /** 只读：查某条当前的 localFlag（不修改） */
    private fun queryFlag(db: Any, snsId: Long): Int? {
        val m = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "rawQuery" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java
            }
        }.getOrNull() ?: return null
        return runCatching {
            val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
                arrayOf(snsId.toString())
            } else {
                arrayOf<Any>(snsId.toString())
            }
            val c = m.invoke(db, "select localFlag from $TABLE where snsId=?", args) ?: return null
            try {
                if (XposedHelpers.callMethod(c, "moveToFirst") as? Boolean != true) return null
                (XposedHelpers.callMethod(c, "getLong", 0) as? Long ?: 0L).toInt()
            } finally {
                runCatching { XposedHelpers.callMethod(c, "close") }
            }
        }.getOrNull()
    }

    /**
     * 诊断：列出归档里每条的**当前** localFlag。
     * ⚠️ 不要拿 [dump] 里的 flag 当依据 —— 那是**归档快照**，恒为微信清位后的值（2），
     * 不代表现在库里是多少。
     */
    fun flags(): List<String> {
        val ids = runCatching { GroupDb.current()?.snsArchivedIds() }.getOrNull()
            ?: return listOf("no wxgroup db")
        val db = snsDb ?: return listOf("no sns db yet")
        val out = ArrayList<String>(ids.size + 1)
        var hidden = 0
        for (id in ids) {
            val cur = queryFlag(db, id)
            if (cur == null) {
                out += "snsId=$id MISSING(row gone)"
                continue
            }
            if (cur and VISIBLE_BIT == 0) hidden++
            out += "snsId=$id localFlag=$cur visible=${cur and VISIBLE_BIT != 0}"
        }
        out.add(0, "SNSFLAGS total=${ids.size} hidden=$hidden")
        return out
    }

    /**
     * 批量把归档里**已不可见**的动态恢复可见（修历史遗留）。
     *
     * 为什么需要：早期版本的拦截没把可见位写回去（或写回失败），那些行虽然还在库里，
     * 但时间线查询要求 localFlag 含 bit4 → 界面上根本看不到 → 「防删除」形同没生效
     * （用户反馈的「以前删除的没有回来」就是这个）。这里一次性补齐。
     */
    fun restoreAll(): String {
        val ids = runCatching { GroupDb.current()?.snsArchivedIds() }.getOrNull()
            ?: return "no wxgroup db"
        val db = snsDb ?: return "no sns db yet"
        var fixed = 0
        var visible = 0
        var missing = 0
        for (id in ids) {
            val cur = queryFlag(db, id)
            if (cur == null) {
                missing++
                continue
            }
            if (cur and VISIBLE_BIT != 0) {
                visible++
                continue
            }
            if (restoreVisibility(db, id, cur) != 0) fixed++
        }
        return "restoreAll total=${ids.size} visible=$visible fixed=$fixed missing=$missing"
    }

    /** 手动把某条恢复成可见（补拦：修复之前拦下来的那些 localFlag 已经被清掉了） */
    fun show(snsId: Long): String {
        val db = snsDb ?: return "no sns db yet"
        val m = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "rawQuery" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java
            }
        }.getOrNull() ?: return "no rawQuery"
        val cur = runCatching {
            val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
                arrayOf(snsId.toString())
            } else {
                arrayOf<Any>(snsId.toString())
            }
            val c = m.invoke(db, "select localFlag from $TABLE where snsId=?", args)
                ?: return "null cursor"
            try {
                if (XposedHelpers.callMethod(c, "moveToFirst") as? Boolean != true) return "row gone"
                (XposedHelpers.callMethod(c, "getLong", 0) as? Long ?: 0L).toInt()
            } finally {
                runCatching { XposedHelpers.callMethod(c, "close") }
            }
        }.getOrElse { return "err ${it.javaClass.simpleName}" }
        if (cur is String) return cur
        val curFlag = cur as Int
        val now = restoreVisibility(db, snsId, curFlag)
        return "snsId=$snsId localFlag $curFlag -> $now"
    }

    /** 诊断用真删：绕过拦截，同时把归档也清掉 */
    fun forceDelete(snsId: Long): String {
        val db = snsDb ?: return "no sns db yet"
        val m = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "delete" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java
            }
        }.getOrNull() ?: return "no delete method"
        return runCatching {
            val args: Any = if (m.parameterTypes[2] == Array<String>::class.java) {
                arrayOf(snsId.toString())
            } else {
                arrayOf<Any>(snsId.toString())
            }
            bypass.set(true)
            val n = try {
                m.invoke(db, TABLE, "snsId=?", args)
            } finally {
                // ⚠️ 用 remove 而不是 set(false)：ThreadLocal 会一直挂在线程上，
                // 微信的 DB 线程是复用的一直不回收，残留的 false 虽然不影响判断，
                // 但会在内存里白留一份（DB 线程池常驻）
                bypass.remove()
            }
            runCatching { GroupDb.current()?.deleteSnsArchive(snsId) }
            "deleted=$n snsId=$snsId"
        }.getOrElse { "err ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}" }
    }

    /**
     * **模拟微信真实的两步删除**（诊断用，不复用 [forceDelete] 的 bypass）：
     * ① `update SnsInfo set localFlag=2` 清掉可见位（微信删动态第一步）
     * ② `delete from SnsInfo where snsId=?`（第二步，**必须**走到 onDelete 拦截里）
     *
     * 这样能一次性验证三件事：
     * - 拦截是否生效（行还在）
     * - `restoreVisibility` 在 delete 钩子内部调用是否真的落库（这是最容易静默失效的：
     *   在 delete 的 before 里同步调 update，wcdb 连接状态可能异常）
     * - 归档 + SnsTag 的 delSet 刷新 + sweep 自动贴标
     */
    fun simulateDelete(snsId: Long): String {
        val db = snsDb ?: return "no sns db yet"
        val um = db.javaClass.methods.firstOrNull {
            it.name == "update" && it.parameterTypes.size == 4 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == ContentValues::class.java
        } ?: return "no update method"
        val step1 = runCatching {
            um.invoke(
                db, TABLE, ContentValues().apply { put("localFlag", 2) },
                "snsId=?", arrayOf(snsId.toString())
            )
        }.getOrElse { "err:${it.javaClass.simpleName}" }
        val dm = db.javaClass.methods.firstOrNull {
            it.name == "delete" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java
        } ?: return "flag=$step1 no delete method"
        val args: Any = if (dm.parameterTypes[2] == Array<String>::class.java) {
            arrayOf(snsId.toString())
        } else {
            arrayOf<Any>(snsId.toString())
        }
        val step2 = runCatching { dm.invoke(db, TABLE, "snsId=?", args) }
            .getOrElse { "err:${it.javaClass.simpleName}" }
        return "simdel snsId=$snsId clearFlag=$step1 deleteRet=$step2"
    }

    fun stat(): String =
        "SNS stat enabled=${cfg().enabled} blocked=${blocked.get()} archived=${archived.get()} " +
            "snsDb=${snsDb != null}"
}
