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
        // ⚠️ limit 来自广播 --ei n（外人可控），直进 listSnsArchive 的 SQL LIMIT 会把
        // content/attr_buf/post_buf 三个 BLOB 全捞进内存（真机 OOM）。入口钳位，
        // 一并覆盖将来新增的调用方（与 MomentsExport.probe img 分支同一防护命题）。
        val lim = MomentsLogic.clampProbeN(limit)
        val list = runCatching { GroupDb.current()?.listSnsArchive(lim) }
            .getOrNull().orEmpty()
        if (list.isEmpty()) return listOf("(empty) 还没有归档过任何动态")
        return listOf("SNSLS archived=${list.size} limit=$lim") + list.map { r ->
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
        // ⚠️ limit 来自广播 --ei n，拼进 SQL 字符串，入口钳位防全表拖库
        val lim = MomentsLogic.clampProbeN(limit)
        val db = snsDb ?: return listOf("no sns db yet (先删一条动态，或让微信刷一次朋友圈)")
        val m = rawQueryMethod(db) ?: return listOf("no rawQuery on ${db.javaClass.name}")
        return runCatching {
            // 这个查询没有占位参数，两个重载都直接给 null
            val args: Any? = null
            val out = mutableListOf<String>()
            val cur = m.invoke(
                db,
                "select snsId,userName,createTime,type,sourceType,localFlag from $TABLE " +
                    "order by createTime desc limit $lim",
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
                out.add(0, "SNSDMP rows=$n (limit=$lim)")
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
            out
        }.getOrElse { listOf("err ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}") }
    }

    /**
     * 评论表取证：列名 + 最近几行的全部列值。
     *
     * 为什么先看这个：评论的**可见列表**存在 `SnsInfo.attrBuf`（protobuf），
     * 而 `SnsComment` 是另一份明细。做「评论[已删除]标签」前必须先确认：
     * 界面渲染到底认哪一份 —— 只认 attrBuf 的话，保住 `SnsComment` 行是不够的。
     */
    fun comments(limit: Int = 3): List<String> {
        val db = snsDb ?: return listOf("no sns db yet")
        val m = rawQueryMethod(db) ?: return listOf("no rawQuery")
        val out = ArrayList<String>()
        runCatching {
            val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
                emptyArray<String>()
            } else {
                emptyArray<Any>()
            }
            val c = m.invoke(
                db, "select * from SnsComment order by rowid desc limit $limit", args
            ) ?: run { out += "CMT null cursor"; return@runCatching }
            try {
                val names = (XposedHelpers.callMethod(c, "getColumnNames") as? Array<*>)
                    ?.map { it?.toString().orEmpty() } ?: emptyList()
                out += "CMT cols=${names.joinToString("|")}"
                var n = 0
                while (XposedHelpers.callMethod(c, "moveToNext") as? Boolean == true) {
                    val sb = StringBuilder("CMT#$n ")
                    for (i in names.indices) {
                        // BLOB 列 getString 会抛，逐列吞异常；长值截断，别把日志撑爆
                        val v = runCatching { XposedHelpers.callMethod(c, "getString", i) as? String }
                            .getOrNull()
                        sb.append(names[i]).append('=')
                            .append(v?.take(60)?.replace('\n', ' ') ?: "<blob/null>")
                            .append("  ")
                    }
                    out += sb.toString()
                    n++
                }
                out.add(0, "CMT rows=$n (limit=$limit)")
            } finally {
                runCatching { XposedHelpers.callMethod(c, "close") }
            }
        }.onFailure {
            out += "CMT err ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}"
        }
        return out
    }

    /**
     * 一行在微信库里的**当前**状态：`localFlag` + 存在性三态。
     *
     * 必须是三态：`Int?` 压不出「查不出来」和「确实没有这一行」的区别，
     * 而回滚能不能按 snsId 删行，完全建立在这个区别上（见 [undoInsert]）。
     */
    private class FlagState(val flag: Int?, val presence: SnsRestoreLogic.Presence)

    /**
     * 只读：查某条当前的 localFlag（不修改）。
     *
     * ⚠️ **null 有两种含义**（行没了 / 查不出来），需要区分时请用 [readFlag]，别拿这里当依据。
     */
    private fun queryFlag(db: Any, snsId: Long): Int? = readFlag(db, snsId).flag

    /**
     * 查一行当前的 `localFlag`，并**明确区分「确认没有这一行」和「查不出来」**。
     *
     * ⚠️ 这个区分以前不存在（两种情况都返回 null），而它是批量回滚能不能做的前提：
     * 「查不出来」时后面接一个 INSERT，插出来的行没法证明是新行，回滚就不能按 snsId 删它
     * —— 否则可能连用户本来就有的那条一起删掉（详见 [SnsRestoreLogic.undoableInserts]）。
     */
    private fun readFlag(db: Any, snsId: Long): FlagState {
        val m = rawQueryMethod(db)
            ?: return FlagState(null, SnsRestoreLogic.Presence.UNKNOWN)
        return runCatching {
            val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
                arrayOf(snsId.toString())
            } else {
                arrayOf<Any>(snsId.toString())
            }
            val c = m.invoke(db, "select localFlag from $TABLE where snsId=?", args)
                ?: return@runCatching FlagState(null, SnsRestoreLogic.Presence.UNKNOWN)
            try {
                if (XposedHelpers.callMethod(c, "moveToFirst") as? Boolean != true) {
                    FlagState(null, SnsRestoreLogic.Presence.ABSENT)
                } else {
                    FlagState(
                        (XposedHelpers.callMethod(c, "getLong", 0) as? Long ?: 0L).toInt(),
                        SnsRestoreLogic.Presence.PRESENT,
                    )
                }
            } finally {
                runCatching { XposedHelpers.callMethod(c, "close") }
            }
        }.getOrElse {
            FlagState(null, SnsRestoreLogic.Presence.UNKNOWN)
        }
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

    /**
     * 从归档**反插回**微信的 `SnsInfo`（需求 6.4「从归档恢复」，诊断入口，不自动执行）。
     *
     * 三种入口状态由 [SnsRestoreLogic.classify] 判定（纯逻辑，已单测锁住）：
     * - 行在且已可见 → [SnsRestoreLogic.Action.SKIP]，**什么都不写**（幂等的关键）；
     * - 行在但可见位被清了 → 只 [writeFlag]，不动内容；
     * - 行**没了**（拦之前就删了 / 早期版本没拦到）→ 整行 INSERT 回去，并补可见位。
     *
     * ⚠️ INSERT 只提供**已知列**（[SnsRestoreLogic.KNOWN]，见 [readRow]），`SnsInfo` 共 18 列，
     * 剩下 5 列靠微信建表时的默认值。若有 NOT NULL 且无默认值的列，这里会失败并把
     * 异常消息带回来 —— 那就是下一步要补的列，靠 [snsInfoColumns] 取证，不猜。
     */
    fun restore(snsId: Long): String {
        val db = snsDb ?: return "no sns db yet (先进一次朋友圈，或删一条动态)"
        val row = runCatching { GroupDb.current()?.snsArchived(snsId) }.getOrNull()
            ?: return "no archive for snsId=$snsId"
        val cur = queryFlag(db, snsId)
        return when (val a = SnsRestoreLogic.classify(cur)) {
            SnsRestoreLogic.Action.SKIP ->
                "snsId=$snsId already in SnsInfo localFlag=$cur (visible already, no write)"
            SnsRestoreLogic.Action.SET_FLAG -> {
                val t = SnsRestoreLogic.withVisible(cur ?: 0)
                if (writeFlag(db, snsId, t)) "snsId=$snsId unhidden localFlag $cur -> $t"
                else "snsId=$snsId set flag FAILED (没写坏，库没动)"
            }
            SnsRestoreLogic.Action.INSERT -> {
                val r = runCatching { insertRow(db, row.toLogic()) }.getOrElse {
                    return "restore err ${it.javaClass.simpleName}: " +
                        "${(it.cause ?: it).message?.take(120)}"
                }
                if (r == null || r < 0) return "restore failed ret=$r (可能撞主键/约束)"
                val after = queryFlag(db, snsId)
                "restored snsId=$snsId rowid=$r localFlag=${row.localFlag}" +
                    "->${row.localFlag or VISIBLE_BIT} now=$after"
            }
            else -> "snsId=$snsId unexpected action=$a"
        }
    }

    /**
     * **批量**从归档恢复（幂等 + 失败即停 + 倒序回滚）。
     *
     * 幂等靠 [SnsRestoreLogic.plan]：同一批跑第二次计划是空的（行在且可见 → 跳过），
     * 所以可以放心重跑。
     *
     * 失败策略：**一步失败就停**，然后把这一步之前**已成功**的步骤按倒序撤回
     * （[SnsRestoreLogic.rollbackOrder]）。理由：写微信的库宁可一个字不写，
     * 也不能留下一半 —— 半恢复的 SnsInfo 比不恢复更难查（界面上会出现"有内容但状态不对"的行）。
     *
     * ⚠️ **回滚只撤「本次确认新插入」的行**：写库之前逐条记录该行的存在性三态
     * （[readFlag] → [SnsRestoreLogic.Presence]），只有查成 `ABSENT` 的 snsId 才准按主键删。
     * 查不出来的（`UNKNOWN`）宁可在微信库里留一行，**也不会**去删 ——
     * `SnsInfo.snsId` 有没有唯一约束未取证，瞎删可能连带删掉用户自己的那条动态。
     * 回滚失败的会收进失败清单打进日志和返回值，供人工兜底。
     * ⚠️ 人工清理别直接 `snsg sub=del` —— 它连归档那一行一起删（[forceDelete]），
     *    先用 `snsg sub=one <id>` 看清楚这一行的来源再说。
     *
     * @param dry true = 只打印计划不写库（探针默认，先看清楚再动手）
     */
    fun restoreBatch(limit: Int = SnsRestoreLogic.MAX_BATCH, dry: Boolean = true): List<String> {
        val out = ArrayList<String>()
        val db = snsDb
            ?: return listOf("no sns db yet (先进一次朋友圈，或删一条动态)")
        val n = SnsRestoreLogic.clampBatch(limit)
        // ⚠️ 这里会读出 content/attrBuf/postBuf 三个 BLOB，n 必须钳过（广播 --ei n 外人可控）
        val rows = runCatching { GroupDb.current()?.listSnsArchive(n) }.getOrNull()
            ?: return listOf("no wxgroup db")
        if (rows.isEmpty()) return listOf("archive empty")

        // ⚠️ 存在性三态必须**在写库之前**逐条查清楚：只有「确认不在」的那些 snsId，
        //    后面失败时才允许按主键回滚删除（判据见 SnsRestoreLogic.undoableInserts）。
        //    写完之后再查就晚了 —— 那时候所有行都在，分不清谁是我们插的。
        val live = HashMap<Long, Int>()
        val pre = HashMap<Long, SnsRestoreLogic.Presence>()
        var gone = 0
        var unknown = 0
        for (r in rows) {
            val st = readFlag(db, r.snsId)
            pre[r.snsId] = st.presence
            when (st.presence) {
                SnsRestoreLogic.Presence.PRESENT -> live[r.snsId] = st.flag ?: 0
                SnsRestoreLogic.Presence.ABSENT -> gone++
                SnsRestoreLogic.Presence.UNKNOWN -> unknown++
            }
        }
        val plan = SnsRestoreLogic.plan(rows.map { it.toLogic() }, live, n)
        if (unknown > 0) {
            LogX.w("SNS restore: $unknown/${rows.size} 行预检查询失败(UNKNOWN)，" +
                "这批里的 INSERT 回滚时不会被删除，只能靠人工核对")
        }
        val skipped = (rows.size - gone - unknown - plan.size).coerceAtLeast(0)
        // ⚠️ 能回滚删除的白名单，必须在写之前算好（见 restoreBatch 的 KDoc）
        val undoable = SnsRestoreLogic.undoableInserts(plan, pre)
        out += "snsRestore archive=${rows.size} gone=$gone unknown=$unknown visible=$skipped " +
            SnsRestoreLogic.planSummary(plan)
        if (dry) {
            for (s in plan) {
                out += "  ${s.snsId} ${s.action}" +
                    if (s.fromFlag != null) " ${s.fromFlag} -> ${s.toFlag}" else ""
            }
            out += "(dry-run; 加 --ez dry false 才真的写库)"
            return out
        }

        var tally = SnsRestoreLogic.Tally(total = plan.size, skipped = skipped)
        val done = ArrayList<SnsRestoreLogic.Step>(plan.size)
        var err = ""
        for (s in plan) {
            val ok = runCatching {
                when (s.action) {
                    SnsRestoreLogic.Action.INSERT -> {
                        val row = runCatching { GroupDb.current()?.snsArchived(s.snsId) }
                            .getOrNull()?.toLogic() ?: return@runCatching false
                        (insertRow(db, row) ?: -1L) >= 0
                    }
                    SnsRestoreLogic.Action.SET_FLAG -> writeFlag(db, s.snsId, s.toFlag ?: VISIBLE_BIT)
                    else -> false
                }
            }.getOrElse { e ->
                err = "${e.javaClass.simpleName}: ${(e.cause ?: e).message?.take(80)}"
                false
            }
            if (!ok) {
                if (err.isEmpty()) err = "write returned false (${s.action})"
                break
            }
            done += s
            tally = if (s.action == SnsRestoreLogic.Action.INSERT) {
                tally.copy(inserted = tally.inserted + 1)
            } else {
                tally.copy(unhidden = tally.unhidden + 1)
            }
            out += "ok ${s.snsId} ${s.action}"
        }
        if (done.size < plan.size) {
            // rollbackOrder 已经是倒序：后写的先撤
            val back = SnsRestoreLogic.rollbackOrder(done)
            var rb = 0
            val undone = ArrayList<Long>(back.size)
            for (b in back) {
                val ok = runCatching { rollbackStep(db, b, undoable) }
                    .onFailure {
                        LogX.w("SNS undo threw ${b.action} snsId=${b.snsId}: " +
                            "${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}")
                    }
                    .getOrDefault(false)
                if (ok) rb++ else undone += b.snsId
            }
            tally = tally.copy(failed = 1, rolledBack = rb)
            out += "FAILED $err -> rolled back $rb/${back.size}"
            LogX.w("SNS restore batch failed: $err")
            // ⚠️ 回滚失败**绝不能吞**：没撤回去的行此刻正躺在微信的 SnsInfo 里，
            // 对用户就是「朋友圈里出现一半莫名恢复出来的动态」。必须把清单打出来，
            // 让人还能拿着这批 snsId 去核对。
            if (undone.isNotEmpty()) {
                val ids = undone.joinToString(",")
                out += "⚠️ UNDONE ${undone.size}/${back.size} snsId=$ids " +
                    "(仍在 SnsInfo 里，需人工清理)"
                LogX.w("SNS restore rollback incomplete, left in SnsInfo: $ids")
            }
        }
        out += "snsRestore " + SnsRestoreLogic.summary(tally)
        LogX.i("SNS ${out.last()}")
        return out
    }

    /**
     * 撤一个**已成功执行**的步骤。
     *
     * @param undoable 本次允许按 snsId 从 `SnsInfo` 删除的清单（[SnsRestoreLogic.undoableInserts]），
     *                 只约束 [SnsRestoreLogic.Action.ROLLBACK_DELETE]；SET_FLAG 的逆操作写回
     *                 原值，怎么都不会删行，不受它限制。
     */
    private fun rollbackStep(
        db: Any,
        b: SnsRestoreLogic.Step,
        undoable: Set<Long>,
    ): Boolean = when (b.action) {
        SnsRestoreLogic.Action.ROLLBACK_DELETE -> undoInsert(db, b.snsId, undoable)
        // 回滚 SET_FLAG = 写回原值
        SnsRestoreLogic.Action.ROLLBACK_FLAG -> writeFlag(db, b.snsId, b.fromFlag ?: 0)
        else -> false
    }

    /**
     * 取证：`pragma table_info(SnsInfo)` —— 只为回答一个问题：
     * **我们没写的那 5 列里有没有 NOT NULL 且无默认值的**（有则 INSERT 必挂）。
     *
     * 这是 docs/取证结论.md 6.4「仍待取证」第 4 条。只读，不写任何东西。
     */
    fun snsInfoColumns(): List<String> {
        val db = snsDb ?: return listOf("no sns db yet")
        val info = tableInfo(db) ?: return listOf("no pragma result")
        val out = ArrayList<String>(info.size + 4)
        val names = info.map { it.name }
        for (c in info) {
            out += "${c.name} notNull=${c.notNull} default=${if (c.hasDefault) "yes" else "-"}" +
                if (SnsRestoreLogic.isKnown(c.name)) "" else "  <- 待取证(不在写入白名单)"
        }
        val missing = SnsRestoreLogic.missingColumns(names)
        if (missing.isNotEmpty()) out += "⚠️ 白名单里有列在真机不存在: $missing"
        out += "COLS total=${info.size} known=${SnsRestoreLogic.KNOWN.size} " +
            "unknown=${SnsRestoreLogic.unknownColumns(names)}"
        val blocking = SnsRestoreLogic.blockingColumns(info)
        out += if (blocking.isEmpty()) {
            "BLOCKING none (未写入的列都可空或有默认值 → INSERT 不会因为缺列失败)"
        } else {
            "⚠️ BLOCKING $blocking (NOT NULL 且无默认值且不写 → INSERT 必挂，需补列)"
        }
        return out
    }

    /** `pragma table_info($TABLE)` 的原始行（只读；失败返回 null，不抛） */
    private fun tableInfo(db: Any): List<SnsRestoreLogic.ColInfo>? {
        val m = rawQueryMethod(db) ?: return null
        return runCatching {
            val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
                arrayOf<String>()
            } else {
                arrayOf<Any>()
            }
            val c = m.invoke(db, "pragma table_info($TABLE)", args) ?: return null
            val out = ArrayList<SnsRestoreLogic.ColInfo>()
            try {
                if (XposedHelpers.callMethod(c, "moveToFirst") as? Boolean != true) return emptyList()
                do {
                    val name = runCatching {
                        XposedHelpers.callMethod(c, "getString", 1) as? String
                    }.getOrNull() ?: continue
                    val notNull = runCatching {
                        (XposedHelpers.callMethod(c, "getLong", 3) as? Long ?: 0L) != 0L
                    }.getOrDefault(false)
                    val def = runCatching {
                        XposedHelpers.callMethod(c, "getString", 4) as? String
                    }.getOrNull()
                    out += SnsRestoreLogic.ColInfo(name, notNull, def != null)
                } while (XposedHelpers.callMethod(c, "moveToNext") as? Boolean == true)
            } finally {
                runCatching { XposedHelpers.callMethod(c, "close") }
            }
            out
        }.onFailure {
            LogX.w("SNS pragma failed: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 写微信库（全部 runCatching 保护）

    /** 归档行 → 纯逻辑行（[SnsRestoreLogic] 零 Android 依赖，进得了对单测） */
    private fun GroupDb.SnsRow.toLogic(): SnsRestoreLogic.ArchiveRow = SnsRestoreLogic.ArchiveRow(
        snsId = snsId,
        userName = userName,
        createTime = createTime,
        type = type,
        sourceType = sourceType,
        localFlag = localFlag,
        subType = subType,
        serverExtFlag = serverExtFlag,
        head = head,
        stringSeq = stringSeq,
        content = content,
        attrBuf = attrBuf,
        postBuf = postBuf,
    )

    /**
     * 归档行 → ContentValues。**列名与类型的唯一出处是 [SnsRestoreLogic.insertPuts]**，
     * 这里只做机械搬运 —— 以后补列改纯逻辑类 + 单测，不用碰这里。
     */
    private fun contentValuesOf(row: SnsRestoreLogic.ArchiveRow): ContentValues =
        ContentValues().apply {
            for (p in SnsRestoreLogic.insertPuts(row)) {
                when (p.kind) {
                    SnsRestoreLogic.Kind.INT -> when (val v = p.v) {
                        null -> putNull(p.col)
                        is Long -> put(p.col, v)
                        is Int -> put(p.col, v)
                        else -> put(p.col, (v as Number).toLong())
                    }
                    SnsRestoreLogic.Kind.TEXT ->
                        if (p.v == null) putNull(p.col) else put(p.col, p.v as String)
                    SnsRestoreLogic.Kind.BLOB ->
                        if (p.v == null) putNull(p.col) else put(p.col, p.v as ByteArray)
                }
            }
        }

    /**
     * 整行 INSERT 回 `SnsInfo`。
     *
     * ⚠️ **冲突算法必须带进去**：只有 4 参的
     * `insertWithOnConflict(table, nullColumnHack, values, conflictAlgorithm)` 收这个参数
     * （[insertMethod] 已改成优先它），传的是 [CONFLICT_IGNORE]（=4）。
     * @return rowid；null/-1 = 失败（**库不会被写坏**，SQLite 单条 INSERT 是原子的）
     */
    private fun insertRow(db: Any, row: SnsRestoreLogic.ArchiveRow): Long? {
        val m = insertMethod(db) ?: return null
        val args: Array<Any?> = if (m.name == "insertWithOnConflict") {
            arrayOf(TABLE, null, contentValuesOf(row), CONFLICT_IGNORE)
        } else {
            arrayOf(TABLE, null, contentValuesOf(row))
        }
        return runCatching { m.invoke(db, *args) as? Long }
            .onFailure {
                LogX.w("SNS insert failed snsId=${row.snsId}: ${it.javaClass.simpleName}: " +
                    "${(it.cause ?: it).message?.take(120)}")
            }
            .getOrNull()
    }

    /** 显式写 localFlag（SET_FLAG / 回滚都用它；跟 [restoreVisibility] 的区别是不跳过"已可见"） */
    private fun writeFlag(db: Any, snsId: Long, flag: Int): Boolean {
        val m = updateMethod(db) ?: return false
        return runCatching {
            val cv = ContentValues().apply { put("localFlag", flag) }
            val n = m.invoke(db, TABLE, cv, "snsId=?", arrayOf(snsId.toString()))
            (n as? Int ?: 0) > 0
        }.onFailure {
            LogX.w("SNS write flag failed snsId=$snsId: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    /**
     * **真正的 undo**：把本次 INSERT 进去的那一行从微信的 `SnsInfo` 里删掉
     * （`delete from SnsInfo where snsId=?` —— 表名/列名来自 [readRow] 的真机实证，不猜）。
     *
     * ⚠️ **准入条件：snsId 必须在 [undoable] 里。** 那是批量开始前逐条查过的
     * 「**确认不在 SnsInfo 里**」的清单（[SnsRestoreLogic.undoableInserts]）。为什么要卡：
     * `SnsInfo.snsId` **有没有唯一约束没有实证**（docs/取证结论.md 里没这一条）。
     * 若它不是唯一键，同一个 snsId 可以并存多行，而按 `snsId=?` 删会
     * **把用户本来就有的那一行一起删掉** —— 那不是回滚，是直接删掉用户看得见的动态，
     * 比恢复失败严重得多。清单外的 id 一律不删，留着等人清理。
     *
     * ⚠️ 必须 [bypass]：我们自己的 delete 钩子会把对 `SnsInfo` 的删除拦下来，
     *    不 bypass 的话回滚**静默失效** —— 没删掉还返回 0 行改动，看上去像成功了。
     *
     * ⚠️ 全程 runCatching：这里已经在失败处理路径上，一旦抛出去就把整批回滚打断，
     *    剩下的行再也撤不回来，所以异常在这儿收住、转成 false。
     *
     * @return true = 确认已删除；false = **没删**（调用方必须收进失败清单并告警，
     *         不能当成"已经回滚干净了"）
     */
    private fun undoInsert(db: Any, snsId: Long, undoable: Set<Long>): Boolean {
        if (!undoable.contains(snsId)) {
            LogX.w("SNS undo refused snsId=$snsId: 不在「本次确认新插入」清单里，" +
                "删它可能连带删掉用户自己的动态 -> 保留现场等人工处理")
            return false
        }
        val m = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "delete" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java
            }
        }.getOrNull() ?: return false
        return runCatching {
            val args: Any = if (m.parameterTypes[2] == Array<String>::class.java) {
                arrayOf(snsId.toString())
            } else {
                arrayOf<Any>(snsId.toString())
            }
            bypass.set(true)
            try {
                (m.invoke(db, TABLE, "snsId=?", args) as? Int ?: 0) > 0
            } finally {
                bypass.remove()
            }
        }.onFailure {
            LogX.w("SNS undo delete failed snsId=$snsId: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    private fun updateMethod(db: Any): Method? = runCatching {
        db.javaClass.methods.firstOrNull {
            it.name == "update" && it.parameterTypes.size == 4 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == ContentValues::class.java
        }
    }.getOrNull()

    /**
     * 冲突算法常量：**CONFLICT_IGNORE = 4**。
     *
     * ⚠️ **4 = CONFLICT_IGNORE**，对照组是同仓库已上过真机的 `RevokeRescue.kt:52`
     * （`private const val CONFLICT_IGNORE = 4`）。全表：
     * `0=NONE 1=ROLLBACK 2=ABORT 3=FAIL 4=IGNORE 5=REPLACE`
     * （AOSP `android.database.sqlite.SQLiteDatabase` 的定义，wcdb 同值）。
     *
     * ⚠️ 这里早先写成 **1**，那是 **CONFLICT_ROLLBACK**：撞主键时它会**回滚整条语句所在的
     * 整个事务**，在批量恢复里表现为「第 N 条撞主键 → 前 N-1 条已经成功的 INSERT 一起被撤销」，
     * 而外层只看 rowid/-1，日志里完全看不出来被回滚过。
     *
     * ⚠️ 为什么不能用 REPLACE(5)：INSERT 只在预检查出「行没了」时才走，
     * 而那个判定有可能因为**查询失败**而落空（见 [readFlag] 的 [SnsRestoreLogic.Presence]），
     * 那种情况下行其实还在，REPLACE 会把 13 列覆盖成归档值、另外 5 列打成默认值
     * —— **把活行写坏**。IGNORE 撞主键就返回 -1，什么都不动，宁可报失败也不冒险。
     */
    private const val CONFLICT_IGNORE = 4

    /**
     * 找 INSERT 方法：**必须优先带冲突算法参数的 4 参版**
     * `insertWithOnConflict(table, nullColumnHack, values, conflictAlgorithm)`。
     *
     * ⚠️ 顺序反了之前踩过坑：早先**先找 3 参 `insert`**，而它在 AOSP/wcdb 上一定有，
     * 于是 4 参版永远选不上 —— [CONFLICT_IGNORE] 这个参数等于白写，
     * 冲突时走的是 `insert` 默认的 ABORT/ROLLBACK 行为。**只能 4 参优先。**
     *
     * 3 参 `insert` **只在 4 参版不存在时**（极老的 wcdb）兜底，且兜底要留一行警告：
     * 那条路径传不了冲突算法，撞主键会抛异常，只能靠 [insertRow] 外层的 runCatching 兜住。
     */
    private fun insertMethod(db: Any): Method? {
        val conflict = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "insertWithOnConflict" && it.parameterTypes.size == 4 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == ContentValues::class.java
            }
        }.getOrNull()
        if (conflict != null) return conflict
        val plain = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "insert" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == ContentValues::class.java
            }
        }.getOrNull()
        LogX.w("SNS insertWithOnConflict not found on ${db.javaClass.name}" +
            " -> fallback insert (冲突算法传不进去，撞主键会抛异常)")
        return plain
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
