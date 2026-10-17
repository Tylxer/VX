package com.lx.wxgroup.probe

import com.lx.wxgroup.feature.AntiRevoke
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.WeakHashMap

/**
 * K3「按微信标签选人」的**取证探针**（`op=label`）。
 *
 * # 为什么必须有它
 * 已知锚点只有一半：`com.tencent.mm.storage.h4.field_contactLabelIds` 能读出
 * 「这个联系人身上挂了哪些标签 id」，但 **id → 标签名** 的映射在哪，全项目没有任何实机证据。
 * 微信有多个库（已证：朋友圈在独立的 `SnsMicroMsg.db`）、表名可能是混淆外的拼写，
 * **猜一个表名上真机 = 一次冷启动十几分钟**，所以第一步只做枚举：哪个库、哪些表、列名、样本行。
 *
 * # ⚠️ 本文件全程「待取证」，不含任何硬编的表名/类名
 * 表名靠 `sqlite_master` 枚举 + 关键字过滤得到，拿到真实结果后再固化到
 * `core/Anchors848.kt` 和 `docs/取证结论.md`。
 *
 * # 用法（一条命令一个答案）
 * ```
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
 *      --es op label --es sub on --ei sec 60          # 开 60 秒收集微信库实例
 *   # 期间进微信通讯录 / 「标签」页滚一滚
 * adb shell ... --es op label --es sub dbs            # 见过哪些库（path）
 * adb shell ... --es op label --es sub tables         # 每个库有哪些表，命中 label 关键字的列出来
 * adb shell ... --es op label --es sub info  --es table <表名>   # 列名（pragma table_info）
 * adb shell ... --es op label --es sub rows  --es table <表名> [--ei n 5]  # 样本行
 * adb shell ... --es op label --es sub sql   --es sql "select contactLabelIds from rcontact limit 5"
 * adb shell ... --es op label --es sub off                       # 取完务必关掉（钩子在热路径上）
 * ```
 * 日志前缀 `LABEL`。
 */
object LabelHuntProbe {

    private const val TAG = "LABEL"

    private const val MAX_WINDOW_SEC = 180

    /** 一个库最多列多少表名（多了刷屏，看不清） */
    private const val MAX_NAMES = 80

    /**
     * 表名关键字。**故意放得宽**：宁可多列几张表让人眼看，也不要因为关键字没押中
     * 而得出「微信库里没有标签表」这种假结论。
     */
    private val TABLE_HINTS = listOf("label", "tag", "rcontact", "contact")

    private val SQL_CLASSES = listOf(
        "com.tencent.wcdb.database.SQLiteDatabase",
        "android.database.sqlite.SQLiteDatabase",
    )

    private var loader: ClassLoader? = null

    private val unhooks = Collections.synchronizedList(mutableListOf<XC_MethodHook.Unhook>())

    @Volatile
    private var until = 0L

    /**
     * 已见过的 db 实例。
     *
     * ⚠️ **必须弱引用**：强引用会把微信每一个 SQLiteDatabase 永久钉住（含连接池与 mmap），
     * 一次取证泄漏整个进程的 db。
     */
    private val dbs = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    /**
     * 窗口到期自动卸载钩子：取证用的钩子挂在 rawQuery 这种极热路径上，
     * 忘了 `sub=off` 会把它们永远留在宿主里（探针铁律：宁可少收，也不能忘了摘）。
     */
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private val expireTask = Runnable {
        uninstall()
        own("$TAG window expired: hooks removed")
    }

    fun prepare(lpparam: XC_LoadPackage.LoadPackageParam) {
        loader = lpparam.classLoader
    }

    // ------------------------------------------------------------------ 命令

    fun command(sub: String, sec: Int, table: String?, sql: String?, n: Int) {
        when (sub) {
            "on" -> {
                val ms = sec.coerceIn(10, MAX_WINDOW_SEC) * 1000L
                until = System.currentTimeMillis() + ms
                install()
                runCatching {
                    mainHandler.removeCallbacks(expireTask)
                    mainHandler.postDelayed(expireTask, ms)
                }.onFailure { own("$TAG on: no main looper, 窗口不会自动到期，务必手动 sub=off") }
                own("$TAG on: window=${ms}ms hooks=${unhooks.size} dbs=${dbs.size}")
            }

            "off" -> {
                uninstall()
                own("$TAG off: unhooked")
            }

            "stat" -> own(
                "$TAG stat hooks=${unhooks.size} dbs=${dbs.size} " +
                    "mainDb=${pathOf(AntiRevoke.wxDb())} " +
                    "windowLeft=${(until - System.currentTimeMillis()).coerceAtLeast(0)}ms"
            )

            /** 见过哪些库（path 是判断「该往哪个库读」的唯一依据） */
            "dbs" -> dumpDbs()

            /** 每个库有哪些表，命中关键字的列出来；没命中就把表名全列出来让人眼看 */
            "tables" -> dumpTables()

            "info" -> dumpInfo(table)

            "rows" -> dumpRows(table, n)

            /**
             * 任意只读 SQL。取证后期不想为了一条查询再打一次包时用。
             * ⚠️ 只放行 select / pragma —— 探针绝不写微信的库。
             */
            "sql" -> runSql(sql)

            else -> own("$TAG unknown sub=$sub (on|off|stat|dbs|tables|info|rows|sql)")
        }
    }

    // ------------------------------------------------------------------ 安装

    private fun install() {
        val cl = loader
        if (cl == null) {
            own("$TAG install: no classLoader")
            return
        }
        uninstall()
        for (c in SQL_CLASSES) {
            val cls = runCatching { XposedHelpers.findClass(c, cl) }.getOrNull() ?: continue
            for (m in listOf("rawQuery", "rawQueryWithFactory")) {
                runCatching {
                    XposedBridge.hookAllMethods(cls, m, catcher())
                        .toCollection(mutableListOf())
                }.onFailure {
                    own("$TAG hook-fail $c.$m ${it.javaClass.simpleName}")
                }.getOrNull()?.let { synchronized(unhooks) { unhooks.addAll(it) } }
            }
        }
    }

    private fun uninstall() {
        runCatching { mainHandler.removeCallbacks(expireTask) }
        synchronized(unhooks) {
            for (u in unhooks) runCatching { u.unhook() }
            unhooks.clear()
        }
        until = 0L
    }

    private fun catcher() = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // ⚠️ 记 db 实例**无条件**做：sub=dbs / tables 不依赖窗口，
            // 但只有装了钩子才收集得到实例。
            runCatching { param.thisObject?.let { synchronized(dbs) { dbs.add(it) } } }
        }
    }

    // ------------------------------------------------------------------ 输出

    private fun dumpDbs() {
        val snapshot = allDbs()
        if (snapshot.isEmpty()) {
            own("$TAG dbs: none — 先 sub=on 开窗口并在微信里滚一滚通讯录")
            return
        }
        for (db in snapshot) {
            own("$TAG db=${pathOf(db)} open=${isOpen(db)} cls=${db.javaClass.name}")
        }
    }

    private fun dumpTables() {
        val snapshot = allDbs()
        if (snapshot.isEmpty()) {
            own("$TAG tables: no db seen (先 sub=on 开窗口，或直接进一次聊天页拿主库)")
            return
        }
        for (db in snapshot) {
            val p = pathOf(db)
            if (!isOpen(db)) {
                own("$TAG db=$p (closed, skipped)")
                continue
            }
            val names = column(db, "select name from sqlite_master where type='table'")
            if (names.isEmpty()) {
                own("$TAG db=$p (sqlite_master 空 / 查不到 —— 库打不开或实例已失效)")
                continue
            }
            val hits = names.filter { n -> TABLE_HINTS.any { n.lowercase().contains(it) } }
            if (hits.isEmpty()) {
                // 关键字没押中时**必须**把表名列出来：否则会误判成「没有标签表」
                own("$TAG db=$p tables=${names.size} hit=0 all=${names.take(MAX_NAMES).joinToString(",")}")
            } else {
                own("$TAG db=$p tables=${names.size} hit=${hits.size} :: ${hits.joinToString(",")}")
                for (h in hits.take(12)) {
                    val cnt = column(db, "select count(*) from $h").firstOrNull()
                    own("$TAG   table=$h rows=$cnt")
                }
            }
        }
    }

    private fun dumpInfo(table: String?) {
        val t = table?.trim()
        if (t.isNullOrEmpty()) {
            own("$TAG info: --es table 不能为空")
            return
        }
        val db = dbWith(t) ?: return
        val info = runCatching { rows(db, "pragma table_info($t)") }.getOrDefault(emptyList())
        if (info.isEmpty()) {
            own("$TAG info $t: pragma 返回空（表名不对？用 sub=tables 看真实表名）")
            return
        }
        own("$TAG info $t db=${pathOf(db)} cols=${info.size}")
        for (r in info.take(60)) own("$TAG   col $r")
    }

    private fun dumpRows(table: String?, n: Int) {
        val t = table?.trim()
        if (t.isNullOrEmpty()) {
            own("$TAG rows: --es table 不能为空")
            return
        }
        val limit = n.coerceIn(1, 30)
        val db = dbWith(t) ?: return
        val out = runCatching { rows(db, "select * from $t limit $limit") }.getOrDefault(emptyList())
        if (out.isEmpty()) {
            own("$TAG rows $t: 0 行（表存在但为空，或读失败）")
            return
        }
        own("$TAG rows $t db=${pathOf(db)} n=${out.size}")
        for (r in out) own("$TAG   row $r")
    }

    private fun runSql(sql: String?) {
        val s = sql?.trim()
        if (s.isNullOrEmpty()) {
            own("$TAG sql: --es sql 不能为空")
            return
        }
        val l = s.lowercase()
        // ⚠️ 探针只做只读：写微信的库是不可逆的
        if (!l.startsWith("select") && !l.startsWith("pragma") && !l.startsWith("explain")) {
            own("$TAG sql refused: 只放行 select / pragma / explain")
            return
        }
        val snapshot = allDbs()
        if (snapshot.isEmpty()) {
            own("$TAG sql: no db instance (先 sub=on 开窗口)")
            return
        }
        for (db in snapshot) {
            if (!isOpen(db)) continue
            val out = runCatching { rows(db, s) }.getOrDefault(emptyList())
            own("$TAG sql db=${pathOf(db)} rows=${out.size}")
            for (r in out.take(20)) own("$TAG   $r")
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 所有可用库实例：探针抓到的 + 防撤回那边一直在用的主库（`message` 所在的那个）。
     *
     * ⚠️ 主库是免费且**常驻**的（读取侧 hook 一直在抓），所以大多数时候
     * 不用 `sub=on` 也能直接 `sub=tables`。
     */
    private fun allDbs(): List<Any> {
        val out = ArrayList<Any>()
        val main = AntiRevoke.wxDb()
        if (main != null) out.add(main)
        synchronized(dbs) {
            for (d in dbs) if (d !== main) out.add(d)
        }
        return out
    }

    /** 找到含这张表的库：微信有多个库，在错误的库上查就是 `no such table` */
    private fun dbWith(table: String): Any? {
        for (db in allDbs()) {
            if (!isOpen(db)) continue
            val names = column(db, "select name from sqlite_master where type='table'")
            if (names.any { it.equals(table, ignoreCase = true) }) return db
        }
        own("$TAG no db contains table=$table (用 sub=tables 看真实表名，注意大小写)")
        return null
    }

    /** 单行单列 */
    private fun column(db: Any, sql: String): List<String> {
        val cur = raw(db, sql) ?: return emptyList()
        return runCatching {
            val out = mutableListOf<String>()
            while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                out.add(XposedHelpers.callMethod(cur, "getString", 0) as? String ?: "")
            }
            out
        }.also { close(cur) }.getOrDefault(emptyList())
    }

    /** 多行多列：一行拼成 `k=v | k=v` —— 直接看清字段名和值，不用猜 */
    private fun rows(db: Any, sql: String): List<String> {
        val cur = raw(db, sql) ?: return emptyList()
        return runCatching {
            val cnt = XposedHelpers.callMethod(cur, "getColumnCount") as? Int ?: 0
            val out = mutableListOf<String>()
            var n = 0
            while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true && n < 40) {
                n++
                val sb = StringBuilder()
                for (i in 0 until cnt) {
                    val name = XposedHelpers.callMethod(cur, "getColumnName", i) as? String ?: "c$i"
                    val v = runCatching { XposedHelpers.callMethod(cur, "getString", i) }.getOrNull()
                    if (sb.isNotEmpty()) sb.append(" | ")
                    sb.append(name).append('=').append((v as? String)?.take(60) ?: "-")
                }
                out.add(sb.toString())
            }
            out
        }.also { close(cur) }.getOrDefault(emptyList())
    }

    /**
     * 反射调 rawQuery。
     *
     * ⚠️ **不能用 `findMethodExact`**：wcdb 有 `rawQuery(String,String[])` 与
     * `rawQuery(String,Object[])` 两个重载，精确匹配会静默找不到。
     * ⚠️ **绝不能跨库缓存 Method**：wcdb 与 AOSP 是两套 SQLiteDatabase，
     * 缓存 AOSP 的 Method 去调 wcdb 实例会抛 IllegalArgumentException
     * （真机踩到过，表现为所有库都查不出表）。调用次数很少，每次重新找。
     */
    private fun raw(db: Any, sql: String): Any? {
        val m = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "rawQuery" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    (it.parameterTypes[1] == Array<String>::class.java ||
                        it.parameterTypes[1] == Array<Any>::class.java)
            }
        }.getOrNull()
        if (m == null) {
            own("$TAG raw: no rawQuery(String,[]) on ${db.javaClass.name}")
            return null
        }
        return runCatching { m.invoke(db, sql, null) }
            .onFailure {
                own("$TAG raw err ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}")
            }
            .getOrNull()
    }

    private fun isOpen(db: Any): Boolean =
        runCatching { XposedHelpers.callMethod(db, "isOpen") as? Boolean }.getOrDefault(true) ?: true

    private fun close(cur: Any) {
        runCatching { XposedHelpers.callMethod(cur, "close") }
    }

    private fun pathOf(db: Any?): String {
        if (db == null) return "-"
        return runCatching { XposedHelpers.callMethod(db, "getPath") as? String ?: "-" }
            .getOrDefault("?")
    }

    private fun own(msg: String) = runCatching { LogX.i(msg) }
}
