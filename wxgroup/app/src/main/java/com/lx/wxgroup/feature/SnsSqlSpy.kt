package com.lx.wxgroup.feature

import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections

/**
 * R30 §6.2 取证：**旁听** wcdb `rawQuery`，把含 `SnsInfo` 的 SQL 原样打到日志。
 *
 * 为什么需要它：`_r30_lane2_album_page.md` §8 用静态反汇编推出相册谓词是
 * `sourceType in (64)`，但真机实测最新 50 行 `sourceType` ∈ {0,2,8,10,128,130}，
 * **一条 64 都没有** —— 若谓词真是 `in (64)`，相册页应当空白，与事实矛盾。
 * `22b`(`and-int/lit8`) 那一族正是反汇编器出过 bug 的地方，静态读数不可信。
 * ⇒ 与其再猜一次，不如让微信自己把 SQL 打出来。
 *
 * 安全设计：
 * - **默认关闭**（[on] = false）。关闭时每次 `rawQuery` 只多一次 volatile 读。
 * - 只在 SQL 含 `SnsInfo` 时记录，且**同类 SQL 只记一次**（去重）+ 总量封顶 [MAX] 行，
 *   防止把 5000 行环形日志冲掉。
 * - [LogX] 只写 logcat + 文件，不碰数据库 ⇒ 在 DB 钩子里打日志**不会递归**。
 */
object SnsSqlSpy {

    private const val MAX = 120

    /**
     * 我们自己发的 SQL —— **必须排除**。
     *
     * 实测（22:05）`sns-srcfix` 每轮会发 7 条 `select count(*) from SnsInfo where snsId in (…)`，
     * 参数各不相同 ⇒ 去重失效 ⇒ 几十秒就把 [MAX] 额度吃光，微信自己的取数 SQL 一条都进不来。
     */
    private val SKIP = arrayOf(
        "where snsId in (",
        "select count(*) from SnsInfo",
        "SELECT attrBuf FROM SnsInfo WHERE snsId=",
        "select snsId,userName,createTime,type,sourceType,localFlag,content from SnsInfo where (createTime",
        "select snsId,userName,createTime,type,sourceType,localFlag from SnsInfo order by createTime desc",
    )

    @Volatile
    private var installed = false

    @Volatile
    private var on = false

    private val seen: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    @Volatile
    private var logged = 0

    /**
     * 最近一条**微信自己**查某人朋友圈的 SQL 原文（`SnsInfo.userName=…` 那条）。
     *
     * 为什么留原文：要判断「已删动态被 `limit` 挤出窗口」只能**用它自己的语句**复现 ——
     * 手拼 WHERE 有几十个 sourceType / type 常量，抄错一个就得到相反结论。
     * 探针 [SnsGuard.rankSql] 只把这句的 `limit N` 放大再跑一遍。
     * ⚠️ 只存 SQL 那一个 String arg（不含 selectionArgs），可直接执行。
     */
    @Volatile
    var lastUserSql: String? = null
        private set

    /** `op=snsg sub=sqlspy sub2=on|off`。打开时清空去重集合，方便重新抓一轮。 */
    fun enable(v: Boolean): String {
        on = v
        if (v) {
            seen.clear()
            logged = 0
        }
        return "SNSSQL spy=$on logged=$logged/${MAX}"
    }

    fun state(): String = "SNSSQL spy=$on logged=$logged/${MAX} installed=$installed"

    fun install(cl: ClassLoader) {
        if (installed) return
        val cls = runCatching {
            XposedHelpers.findClass("com.tencent.wcdb.database.SQLiteDatabase", cl)
        }.getOrNull()
        if (cls == null) {
            LogX.w("SNSSQL wcdb SQLiteDatabase not found, spy disabled")
            return
        }
        // ⚠️ 实测（22:07）：只 hook `rawQuery` 抓不到微信自己的取数 SQL —— 撞见的全是本模块发的。
        // 微信的 SnsInfoStorage 走的是 `query()` → `rawQueryWithFactory` 这条链，
        // 所以要三个一起挂，并且**不假设 SQL 在 args[0]**（各重载位置不同）。
        var hooked = 0
        for (n in arrayOf("rawQuery", "rawQueryWithFactory", "query")) {
            val set = runCatching { XposedBridge.hookAllMethods(cls, n, hook) }.getOrNull()
            if (!set.isNullOrEmpty()) hooked += set.size
        }
        if (hooked == 0) {
            LogX.w("SNSSQL no rawQuery/query overload to hook, spy disabled")
            return
        }
        installed = true
        LogX.i("SNSSQL installed x$hooked (默认关闭，sub=sqlspy sub2=on 打开)")
    }

    private val hook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // ⚠️ 这一段对每次 DB 调用都会跑 —— 关着的时候必须只有这一行开销
            if (!on) return
            val args = param.args ?: return
            // 不假设 SQL 在 args[0]：`rawQuery(sql,…)` / `rawQueryWithFactory(factory,sql,…)` /
            // `query(table,columns,selection,…)` 位置各不相同，统一扫一遍。
            var hit = false
            var sqlArg: String? = null
            for (a in args) {
                if (a is String && a.contains("SnsInfo")) {
                    hit = true
                    sqlArg = a
                    break
                }
            }
            if (!hit) return
            // 微信查某人朋友圈的语句 —— 留原文给 [SnsGuard.rankSql] 复现用。
            // 判据用 `SnsInfo.userName=`（带表名限定）：本模块自己发的几句都是
            // `where userName=` / `order by createTime`，不会被误当成微信的语句。
            if (sqlArg != null) {
                val s = sqlArg
                if (s.contains("SnsInfo.userName=") && s.contains("limit", ignoreCase = true)) {
                    lastUserSql = s
                }
            }
            val sb = StringBuilder(160)
            for (a in args) {
                if (a is String) {
                    if (sb.isNotEmpty()) sb.append(" | ")
                    sb.append(a)
                }
            }
            val flat = sb.toString().replace('\n', ' ').replace('\r', ' ')
            for (s in SKIP) if (flat.contains(s)) return
            synchronized(SnsSqlSpy) {
                if (logged >= MAX) return
                if (!seen.add(flat)) return
                logged++
            }
            LogX.i("SNSSQL ${param.method.name} $flat")
        }
    }
}
