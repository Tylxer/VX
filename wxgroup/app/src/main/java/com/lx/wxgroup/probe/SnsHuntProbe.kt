package com.lx.wxgroup.probe

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.util.LogX
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.lang.reflect.Method

/**
 * 6.2 朋友圈防撤回取证探针：搞清楚「朋友圈动态 / 评论」存在哪、删除时微信做了什么。
 *
 * ## 为什么必须取证
 * 朋友圈跟聊天消息不是一套东西：库可能不是主消息库（微信有多个 db，防撤回踩过
 * `no such table: message` 的坑），表名是 `SnsInfo` 这种拼写（大小写敏感匹配才准），
 * 删除既可能是 `delete from` 也可能是 `update` 某个标志位。猜一个方向就上真机，
 * 一次冷启动十几分钟，代价太高。
 *
 * 所以第一步只干三件事：
 * 1. **哪个库**：每次 SQL 调用都记下 `SQLiteDatabase.getPath()`（这是防撤回那次学到的
 *    最关键的一条 —— 只有知道 path，才知道该往哪个库里读/写）。
 * 2. **哪张表、什么 SQL**：写库记表名 + values，读库记完整 SQL（要拿 schema 和分页形态）。
 * 3. **schema**：`sub=tables` 列所有已见库的表名，`sub=sample` 打 `pragma table_info`。
 *
 * ## 用法
 * ```
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE --es op sns --es sub on  --ei sec 150 -p com.tencent.mm
 *   # 进朋友圈滚一滚 → 删一条自己的动态 → 删一条别人动态下的评论
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE --es op sns --es sub tables -p com.tencent.mm
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE --es op sns --es sub sample -p com.tencent.mm
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE --es op sns --es sub off -p com.tencent.mm
 * ```
 * 日志行前缀 `SNS`。
 *
 * ⚠️ 三条纪律（同 RevokeHuntProbe）：
 * 1. 窗口到期自动 flush + 卸载全部钩子，忘记 `off` 也不会把钩子留在宿主里。
 * 2. 钩子挂在写库热路径，**只在取证期间开**，取完证一定 `off`。
 * 3. 钩子内部全部 try/catch，出错只记一行、绝不向上抛。
 */
object SnsHuntProbe {

    private const val LOG = "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"

    private const val FRAMES = 24

    private const val MAX_LINES = 260

    /**
     * catch 模式的日志行数上限。
     *
     * ⚠️ 为什么必须比默认大得多：实测（2026-10-17）无 key 的 catch 窗口只有 52 秒就把
     * 260 行预算打满 —— 全是群消息 insert，真正的开关写入**根本没轮到**。
     * 带 catchKey 时正常，但无 key 的对照试验必须给足预算，否则又白跑一轮。
     */
    private const val MAX_LINES_CATCH = 1500

    private fun maxLines() = if (allWrites) MAX_LINES_CATCH else MAX_LINES

    private const val MAX_WINDOW_SEC = 600

    private var loader: ClassLoader? = null

    private val unhooks = Collections.synchronizedList(mutableListOf<XC_MethodHook.Unhook>())

    @Volatile
    private var until = 0L

    /**
     * `catch` 模式：无条件记录**全部写操作**，不过 sns 表名/关键字过滤。
     *
     * 为什么需要：默认过滤只看 `TABLE_HINTS`(sns*) 与 `KEYWORDS`(delete/snsid)，
     * 于是「群免打扰开关到底写了哪张表」这类取证会被**静默丢弃** ——
     * 表现为 dump 前后一个字段都没变，看着像"微信没写库"，实际是探针没记。
     */
    @Volatile
    private var allWrites = false

    /**
     * `catch` 的目标关键词过滤：非空时只记录 SQL/参数里含这个子串的写操作。
     *
     * 用例：`catch --es table 45396655401@chatroom` → 只抓「跟这个群有关」的写，
     * 用户在手机上做任何动作都能对上，不用卡时间窗口。
     * 为空时记录全部写操作（只适合 10~20 秒的极短窗）。
     */
    @Volatile
    private var catchKey: String? = null

    private val counts = ConcurrentHashMap<String, Int>()

    private val passed = AtomicInteger()

    private val printed = AtomicInteger()

    /**
     * 已见过的 db 实例。
     *
     * ⚠️ **必须弱引用**：强引用会把微信每一个 SQLiteDatabase 实例永久钉住（含它背后的
     * 连接池与 mmap），一个取证动作泄漏整个进程的 db。
     */
    private val dbs = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    /**
     * 防重入：探针自己 dump schema 时也会调 rawQuery，而 `pragma table_info(SnsInfo)`
     * 里就含 "sns"，会命中自己的钩子 → 日志自污染。
     */
    private val inProbe = ThreadLocal<Boolean>()

    /**
     * 防「同一次逻辑写被记两遍」。
     *
     * 成因：`SQLiteDatabase.insert/update/delete/execSQL` 内部会 `compileStatement` 后调
     * `SQLiteStatement.executeInsert/executeUpdateDelete/execute` —— 库层与语句层**都会被挂到**。
     * 原来的 key 只含 sig，两层不同 → 去重表也去不掉，于是每条日志占 2 行（各带一行栈），
     * `MAX_LINES=260` 的预算直接减半，还容易被误读成"微信写了两遍"。
     *
     * 判据：**线程内深度计数**。库层 `beforeHookedMethod` +1、`afterHookedMethod` -1；
     * 语句层看到深度 > 0 就跳过（它是库层的子调用，同一次写不该记两遍）。
     * 用 ThreadLocal 是因为这两层调用必然同线程嵌套。
     */
    private val sqlWriteDepth = ThreadLocal.withInitial { 0 }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val expireTask = Runnable {
        runCatching {
            flushInternal()
            uninstall()
            allWrites = false
            catchKey = null
            own("SNS window expired: all hooks removed")
        }
    }

    private val NOISE = arrayOf(
        "de.robv.android.xposed",
        "com.lx.wxgroup",
        "java.lang.reflect",
        "dalvik.system",
        "android.os.Handler",
        "com.android.internal.os",
        "kotlin.coroutines",
        "kotlinx.coroutines",
    )

    /**
     * 朋友圈相关特征。用小写匹配 —— 表名 `SnsInfo` 大小写混写，SQL 里也可能是
     * `snsExtInfo3` 这种。⚠️ 只认 `sns`，别加太宽的词（比如 "comment"），
     * 否则微信主库里一堆表会被扫进来，日志刷爆。
     */
    private val TABLE_HINTS = listOf("sns", "sns_info", "snsinfo")

    /** 删除动作的特征：朋友圈删除在协议层多半是 op/del 之类的字段名 */
    private val KEYWORDS = listOf("delete", "snsid", "sns_id", "删除")

    private val SQL_CLASSES = listOf(
        "com.tencent.wcdb.database.SQLiteDatabase",
        "android.database.sqlite.SQLiteDatabase",
    )

    /**
     * ⚠️ `SQLiteStatement.executeInsert/executeUpdateDelete` 原本**故意不挂**（预编译语句调用时
     * 只有 handle，拿不到 SQL，是最热的执行路径）。但 2026-10-17 取「群免打扰开关落点」时
     * 发现：catch 模式开着、用户切了开关，日志里**一条相关写都没有** —— 全是消息/头像。
     * 最可能的解释就是这类「设置写入」走预编译路径。
     *
     * 解法：只挂**执行**入口（不挂 compileStatement —— 那是热路径），
     * 在回调里**反射读语句对象自带的 SQL 字段**（wcdb 的 `mSql` / AOSP 的 `mSql`）。
     * 拿不到字段时打 `(sql?)`，不猜。
     */
    private val SQL_METHODS = listOf(
        "update", "updateWithOnConflict",
        "delete",
        "insert", "insertWithOnConflict", "insertOrThrow",
        "replace", "replaceOrThrow",
        "execSQL",
        "rawQuery", "rawQueryWithFactory", "query",
    )

    /** 预编译语句的执行入口（SQL 从语句对象字段反射取） */
    private val STMT_CLASSES = listOf(
        "com.tencent.wcdb.database.SQLiteStatement",
        "android.database.sqlite.SQLiteStatement",
    )

    private val STMT_METHODS = listOf("executeUpdateDelete", "executeInsert", "execute")

    /** wcdb / AOSP 的 SQLiteProgram 里存 SQL 的字段名，按顺序试 */
    private val SQL_FIELDS = listOf("mSql", "mSqlStatement", "sql")

    /** 查询类只打 SQL 不打栈：极热路径，24 帧栈会把日志刷爆 */
    private val NO_STACK_SIGS = listOf("rawQuery", "rawQueryWithFactory", "query")

    fun prepare(lpparam: XC_LoadPackage.LoadPackageParam) {
        loader = lpparam.classLoader
    }

    // ------------------------------------------------------------------ 命令

    fun command(sub: String, sec: Int, table: String? = null) {
        when (sub) {
            "on" -> {
                val ms = sec.coerceIn(10, MAX_WINDOW_SEC) * 1000L
                Thread {
                    install()
                    mainHandler.post {
                        until = System.currentTimeMillis() + ms
                        counts.clear()
                        passed.set(0)
                        printed.set(0)
                        // ⚠️ 必须显式清掉 catch 残留：否则先 `catch` 再 `on`，这个"默认"窗口
                        // 实际是 catch 语义，而日志只打 `SNS on: ...`，取证结论会被静默污染。
                        allWrites = false
                        catchKey = null
                        mainHandler.removeCallbacks(expireTask)
                        mainHandler.postDelayed(expireTask, ms)
                        own("SNS on: window=${ms}ms hooks=${unhooks.size}")
                    }
                }.start()
            }

            "off" -> {
                flushInternal()
                uninstall()
                own("SNS off: unhooked")
            }

            "flush" -> flushInternal()
            "clear" -> runCatching { File(LOG).delete() }
            "stat" -> own(
                "SNS stat hooks=${unhooks.size} kinds=${counts.size} dbs=${dbs.size} " +
                    "passed=${passed.get()} allWrites=$allWrites catchKey=${catchKey ?: "-"} " +
                    "windowLeft=${(until - System.currentTimeMillis()).coerceAtLeast(0)}ms"
            )
            // 列所有已见库的表名 —— 回答「朋友圈数据到底在哪张表」
            "tables" -> dumpTables()
            // 打含 sns 的表的列结构与样本行 —— 回答「字段叫什么」
            // --es table SnsInfo 只打指定表（表太多时按字母序会漏掉最关键的 SnsInfo）
            "sample" -> dumpSample(table)
            // 「snsId 有没有唯一约束」—— restore 上真机的硬门禁（pk=0 不等于没有唯一约束）
            "index" -> dumpIndexes(table)
            // 建表/建索引原始 DDL，人眼最不容易误读
            "ddl" -> dumpDdl(table)
            /**
             * 自检：wcdb 的语句类里 SQL 到底存在哪个字段。
             *
             * 为什么必需：`SQL_FIELDS`（mSql/mSqlStatement/sql）是**推测名**。若全错，
             * 语句层就永远拿到 `(sql?)` → 一条都不记，而人会误以为"微信确实没写库"。
             * 这条命令直接把真实字段名列出来，一次问清。
             */
            "stmtdiag" -> dumpStmtFields()
            // 无条件记录全部写操作（短窗用）：回答「某个未知表被谁写了什么」
            // 用法：sub=catch → 在手机上做一个动作 → 看日志里这条动作写了哪些表
            //
            // ⚠️ catch 自带 install + 开窗，**不需要先 sub=on**：
            // 单独置标志而不挂钩子的话，钩子压根没装上，日志永远是空的。
            "catch" -> {
                val ms = sec.coerceIn(10, MAX_WINDOW_SEC) * 1000L
                val key = table?.trim()?.takeIf { it.isNotEmpty() }
                Thread {
                    install()
                    mainHandler.post {
                        allWrites = true
                        catchKey = key
                        until = System.currentTimeMillis() + ms
                        counts.clear()
                        passed.set(0)
                        printed.set(0)
                        mainHandler.removeCallbacks(expireTask)
                        mainHandler.postDelayed(expireTask, ms)
                        own(
                            "SNS catch ON: window=${ms}ms hooks=${unhooks.size} " +
                                if (key != null) "只记含「$key」的写操作" else "全部写操作不过滤（短窗）"
                        )
                    }
                }.start()
            }
            "catchoff" -> {
                allWrites = false
                catchKey = null
                own(
                    "SNS catch OFF (钩子仍在，窗口剩 " +
                        "${(until - System.currentTimeMillis()).coerceAtLeast(0)}ms；要彻底停用发 sub=off)"
                )
            }
            else -> own("SNS unknown sub=$sub")
        }
    }

    // ------------------------------------------------------------------ 安装

    /**
     * 自检：把 wcdb / AOSP 语句类（及其父类链）的**全部字段**列出来。
     *
     * 回答的唯一问题：SQL 模板存在哪个字段名下？`SQL_FIELDS` 的候选名是猜的，
     * 猜错 → 语句层永远 `(sql?)` → 静默零记录 → 误判成"微信没写库"。
     * 顺带列出 declaredMethods，确认 `executeUpdateDelete` 等确实存在。
     */
    private fun dumpStmtFields() {
        val cl = loader
        if (cl == null) {
            own("SNS stmtdiag: no classLoader")
            return
        }
        for (name in STMT_CLASSES) {
            val cls = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull()
            if (cls == null) {
                own("SNS stmtdiag $name: 类不存在")
                continue
            }
            own("SNS stmtdiag === $name ===")
            var c: Class<*>? = cls
            var depth = 0
            while (c != null && c != Any::class.java && depth < 6) {
                val fs = runCatching {
                    c!!.declaredFields.joinToString(",") { "${it.name}:${it.type.simpleName}" }
                }.getOrDefault("(读取失败)")
                own("SNS   fields[${c.simpleName}] = ${fs.take(400)}")
                c = c.superclass
                depth++
            }
            val ms = runCatching {
                cls.declaredMethods.joinToString(",") { it.name }.let {
                    // 去重，方法多起来看着累
                    it.split(",").distinct().joinToString(",")
                }
            }.getOrDefault("(读取失败)")
            own("SNS   methods = ${ms.take(400)}")
        }
    }

    /**
     * ⚠️ `@Synchronized` 是必需的：`catch` 与 `on` 各自起裸线程调这里，快速连发广播会让
     * 两次 `install()` 交错 —— LSPosed 的钩子集合是追加语义，同一方法会被挂**两个**回调，
     * 于是每条日志都记两遍（看起来像"微信写了两次"）。
     */
    @Synchronized
    private fun install() {
        val cl = loader
        if (cl == null) {
            own("SNS install: no classLoader")
            return
        }
        uninstall()

        val missing = ArrayList<String>()
        for (c in SQL_CLASSES) {
            for (m in SQL_METHODS) all(c, m, missing)
        }
        // 预编译语句执行入口：SQL 从语句对象字段反射取（见 STMT_* 注释）
        for (c in STMT_CLASSES) {
            for (m in STMT_METHODS) allStmt(c, m, missing)
        }

        if (missing.isEmpty()) {
            own("SNS install done: ${unhooks.size} hooks, all candidate classes present")
        } else {
            own("SNS install done: ${unhooks.size} hooks, missing=${missing.joinToString(",")}")
        }
    }

    /**
     * 挂预编译语句的执行入口。与 [all] 的区别：这个回调要去**语句对象自己的字段**里
     * 抠 SQL（`param.thisObject` 就是 SQLiteStatement），所以单独走一条路。
     */
    private fun allStmt(clsName: String, method: String, missing: MutableList<String>): Boolean {
        val cl = loader ?: return false
        val cls = runCatching { XposedHelpers.findClass(clsName, cl) }.getOrNull()
        if (cls == null) {
            // ⚠️ 单独记一条：否则会出现「语句层压根没挂上，install 汇总却打印
            // all candidate classes present」的误导日志 —— 直接导致取证结论跑偏（以为没写库）。
            if (missing.size < 20) missing.add("STMT-CLS-MISS $clsName")
            return false
        }
        val set = runCatching {
            XposedBridge.hookAllMethods(cls, method, stmtCallback("$clsName.$method"))
                .toCollection(mutableListOf())
        }.onFailure {
            own("SNS  hook-fail $clsName.$method ${it.javaClass.simpleName}")
        }.getOrNull()
        if (set.isNullOrEmpty()) {
            if (missing.size < 20) missing.add("STMT `$clsName.$method` (类在但方法未 declared)")
            return false
        }
        synchronized(unhooks) { unhooks.addAll(set) }
        return true
    }

    private fun uninstall() {
        mainHandler.removeCallbacks(expireTask)
        synchronized(unhooks) {
            for (u in unhooks) runCatching { u.unhook() }
            unhooks.clear()
        }
        until = 0L
        counts.clear()
        passed.set(0)
        printed.set(0)
        // 关钩子时一并复位 catch 语义，避免「off 之后 allWrites 还留着」
        allWrites = false
        catchKey = null
        stopWriter()
    }

    private fun all(clsName: String, method: String, missing: MutableList<String>): Boolean {
        val cl = loader ?: return false
        val cls = runCatching { XposedHelpers.findClass(clsName, cl) }.getOrNull()
        if (cls == null) {
            if (missing.size < 20) missing.add("$clsName.$method(?)")
            return false
        }
        val set = runCatching {
            XposedBridge.hookAllMethods(cls, method, callback("$clsName.$method"))
                .toCollection(mutableListOf())
        }.onFailure {
            own("SNS  hook-fail $clsName.$method ${it.javaClass.simpleName}")
        }.getOrNull()
        if (set.isNullOrEmpty()) {
            missing.add("$clsName.$method")
            return false
        }
        synchronized(unhooks) { unhooks.addAll(set) }
        return true
    }

    private fun callback(sig: String) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // ⚠️ 记 db 实例要**无条件**做（哪怕窗口没开也要记，否则 sub=tables 没数据）：
            // sub=tables 是取证的主要手段之一，它不依赖窗口。
            runCatching { param.thisObject?.let { synchronized(dbs) { dbs.add(it) } } }
            // 库层调用可能内部再走语句层 → 深度 +1，让语句层跳过（避免同一次写记两遍）
            runCatching { sqlWriteDepth.set(sqlWriteDepth.get() + 1) }
            runCatching {
                if (System.currentTimeMillis() > until) return
                if (inProbe.get() == true) return // 我们自己 dump schema 的查询，别记
                recordSql(sig, param)
            }.onFailure {
                runCatching { own("SNS  cb-error $sig ${it.javaClass.simpleName}") }
            }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            // 必须配对减回去：异常路径也要走到这里（Xposed 保证 after 会被调）
            runCatching { sqlWriteDepth.set((sqlWriteDepth.get() - 1).coerceAtLeast(0)) }
        }
    }

    /**
     * 预编译语句执行回调：`param.thisObject` 是 SQLiteStatement，
     * SQL 从它自己的字段（[SQL_FIELDS]）里抠。
     *
     * 绑定参数不在 `param.args` 上（bind 是更早的调用），所以只能看到 SQL 模板：
     * 对「哪张表被写了」这个取证目标来说够用 —— 表名/列名都在模板里。
     */
    private fun stmtCallback(sig: String) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                if (System.currentTimeMillis() > until) return
                if (inProbe.get() == true) return
                // ⚠️ 深度 > 0 表示这次执行是**库层调用的子步骤**（insert/update/delete 内部
                // compileStatement→execute），库层那条已经记过了。不跳过就会同一次写记两遍。
                if (sqlWriteDepth.get() > 0) return
                val sql = sqlOf(param.thisObject)
                val head = sql ?: "(sql?)"
                val lower = head.lowercase()

                // ⚠️ catch 模式必须**真的收窄**：早期版本写成 `hit || (allWrites && sql!=null)`，
                // 结果 catchKey 被 OR 吸收、pass 恒真 → 记下全进程所有预编译语句，
                // 与「只抓这个群」的预期正好相反。收窄逻辑必须和 recordSql 保持一致。
                val pass = if (allWrites) {
                    val k = catchKey
                    sql != null && (k == null || head.contains(k, ignoreCase = true))
                } else {
                    TABLE_HINTS.any { lower.contains(it) } || KEYWORDS.any { lower.contains(it) }
                }
                if (!pass) {
                    passed.incrementAndGet()
                    return
                }
                val key = "$sig STMT ${head.take(if (allWrites) 160 else 100)}"
                val n = counts.merge(key, 1) { a, b -> a + b } ?: 1
                if (n == 1 && printed.get() < maxLines()) {
                    printed.incrementAndGet()
                    own("SNS $sig [STMT] thread=${Thread.currentThread().name} :: $head")
                    own("SNS   at " + frames().joinToString(" <- "))
                }
            }.onFailure {
                runCatching { own("SNS  stmt-error $sig ${it.javaClass.simpleName}") }
            }
        }
    }

    /**
     * 反射从 SQLiteStatement / SQLiteProgram 里抠 SQL 模板。
     *
     * ⚠️ 按 Class 缓存解析结果：语句执行是热路径，catch 窗口可开 600 秒且覆盖全进程所有
     * 预编译语句；`getObjectField` 命不中时会**每次抛 NoSuchFieldError**（XposedHelpers 无负缓存），
     * 三次试探就是三次异常构造。缓存只存 `Class → Field?`（null 表示"这个类里都没有"）。
     *
     * ⛔ **必须用 synchronizedMap(HashMap)，不能用 ConcurrentHashMap**：后者**不允许 null value**，
     * 而"这个类里没有 SQL 字段"正是最常见的负结果 —— 存 null 会在 `putVal` 处 NPE，
     * 且发生在每帧都要走的库调用路径上（项目里已踩过一次，真机每帧崩）。
     */
    private val sqlFieldCache =
        Collections.synchronizedMap(HashMap<Class<*>, java.lang.reflect.Field?>())

    private fun sqlOf(stmt: Any?): String? {
        if (stmt == null) return null
        val cls = stmt.javaClass
        val field = synchronized(sqlFieldCache) {
            if (!sqlFieldCache.containsKey(cls)) {
                // ⚠️ 必须沿**父类链**找：真机实证（`sub=stmtdiag`）显示 `mSql` 挂在
                // `SQLiteProgram` 上，而 `SQLiteStatement` 本类字段为空。
                // 只用 getDeclaredField 会永远拿不到 SQL ← 语句层静默零记录。
                var c: Class<*>? = cls
                var found: java.lang.reflect.Field? = null
                var depth = 0
                while (c != null && c != Any::class.java && depth < 6) {
                    for (name in SQL_FIELDS) {
                        val f = runCatching { c!!.getDeclaredField(name) }.getOrNull()
                        if (f != null) {
                            f.isAccessible = true
                            found = f
                            break
                        }
                    }
                    if (found != null) break
                    c = c.superclass
                    depth++
                }
                sqlFieldCache[cls] = found
            }
            sqlFieldCache[cls]
        } ?: return null
        val v = runCatching { field.get(stmt) }.getOrNull()
        return (v as? String)?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------ 记录

    /**
     * 把 SQL 参数摊平成可搜索文本。
     *
     * ⚠️ 存在的唯一理由：`args` 里常混着 `String[]`/`Object[]`（whereArgs / bindArgs），
     * 它们 `toString()` 出来是 `[Ljava.lang.String;@1a2b` —— 内容全丢。
     * 而「群 wxid 作为绑定参数传入」恰恰是最常见的写法，不摊平就等于放弃 catchKey 过滤。
     * 数组/集合递归展开，深度只到 2 层（SQL 参数不会更深），防环。
     */
    private fun flat(v: Any?, depth: Int = 0): String {
        if (v == null) return "null"
        if (depth >= 2) return v.toString().take(40)
        return when (v) {
            is Array<*> -> v.joinToString(",") { flat(it, depth + 1) }.take(300)
            is Iterable<*> -> v.joinToString(",") { flat(it, depth + 1) }.take(300)
            else -> v.toString().take(200)
        }
    }

    private fun recordSql(sig: String, param: XC_MethodHook.MethodHookParam) {
        val args = param.args
        val head = args?.firstOrNull { it is CharSequence }?.toString() ?: ""
        val headLower = head.lowercase()
        val tableHit = TABLE_HINTS.any { headLower.contains(it) }
        var kw = KEYWORDS.firstOrNull { headLower.contains(it) }
        val isQuery = NO_STACK_SIGS.any { sig.contains(it) }

        // ⚠️ `catch` 模式：**记录写操作**。
        //
        // 为什么需要它：默认过滤只看 TABLE_HINTS(sns*) 与 KEYWORDS(delete/snsid)，
        // 「某个未知表被写入」这类取证（例：群免打扰开关到底写哪儿）会被**静默丢弃**
        // —— 于是 `sub=on` 之后一个字段都没变，看起来像"微信没写库"，其实是探针没记。
        //
        // 只放行**写**操作（query 一律跳过）：微信查询极热，全记必刷爆。
        // catchKey 非空时再叠一层「SQL 或参数里必须含该子串」（例如目标群 wxid）。
        var catchHit = false
        if (allWrites && !isQuery) {
            val k = catchKey
            if (k == null) {
                catchHit = true
            } else {
                // 参数在 args[1..] 里，先粗判 SQL 头，再判参数（wxid 通常作绑定参数传入）
                val sb = StringBuilder(head)
                if (args != null) {
                    for (i in 1 until args.size) {
                        val v = args[i] ?: continue
                        // ⚠️ 数组必须摊开：`whereArgs`/`bindArgs` 是 String[]/Object[]，
                        // 直接 toString() 只得到 `[Ljava.lang.String;@1a2b`，内容全丢。
                        // 偏偏「群 id 走绑定参数」是最常见形态（update(t,'x=?',[roomid])），
                        // 不摊开就会把 catchKey 明明命中的写操作静默丢弃。
                        sb.append(' ').append(flat(v))
                        if (sb.length > 1200) break
                    }
                }
                catchHit = sb.contains(k, ignoreCase = true)
            }
        }
        if (catchHit) {
            // 落到下面的记录逻辑
        } else if (!tableHit && kw == null) {
            passed.incrementAndGet()
            return
        }

        val tail = StringBuilder()
        if (args != null) {
            for (i in 1 until args.size) {
                val v = args[i] ?: continue
                val s = when (v) {
                    is Array<*> -> v.joinToString(",") { it?.toString()?.take(30) ?: "null" }
                    else -> v.toString().take(200)
                }
                tail.append(s).append(' ')
                if (tail.length > 300) break
            }
        }
        val tailStr = tail.toString()
        if (kw == null) kw = KEYWORDS.firstOrNull { tailStr.lowercase().contains(it) }

        val headMax = if (isQuery) 500 else if (allWrites) 260 else 80
        val dbPath = pathOf(param.thisObject)
        val detail = "db=${dbPath.take(60)} | " + head.take(headMax) + " | " + tailStr.take(240)

        // ⚠️ 取栈很贵（Throwable().stackTrace），只在「第一次见到这个调用点」时才取；
        // 写库是热路径，无条件取栈会明显拖慢微信。
        // catch 模式下调宽 key 长度：不同表的前 60 字符可能撞在一起（update xxx set 之后才有表名差异）
        val keyLen = if (allWrites) 110 else 60
        val key = "$sig [${if (kw != null) "KW" else "TBL"}] ${detail.take(keyLen)}"
        val n = counts.merge(key, 1) { a, b -> a + b } ?: 1
        if (n == 1 && printed.get() < maxLines()) {
            printed.incrementAndGet()
            val tag = if (kw != null) "[KW:$kw]" else "[TBL]"
            own("SNS $sig $tag thread=${Thread.currentThread().name} :: $detail")
            if (!isQuery) own("SNS   at " + frames().joinToString(" <- "))
        }
    }

    // ------------------------------------------------------------------ schema

    private fun dumpTables() {
        val snapshot = synchronized(dbs) { dbs.toList() }
        if (snapshot.isEmpty()) {
            own("SNS tables: no db seen yet (先进朋友圈滚一滚，或先 sub=on 开一会儿)")
            return
        }
        for (db in snapshot) {
            val p = pathOf(db)
            if (!isOpen(db)) {
                own("SNS db=$p (closed, skipped)")
                continue
            }
            val names = column(db, "select name from sqlite_master where type='table'")
            if (names.isEmpty()) {
                own("SNS db=$p (sqlite_master 查不到表 —— 库打不开或没权限)")
                continue
            }
            val sns = names.filter { it.lowercase().contains("sns") }
            own("SNS db=$p tables=${names.size} sns=${sns.joinToString(",").take(200)}")
            if (sns.isEmpty()) {
                // 也顺手列一下看起来像朋友圈/评论的表，防止微信改了命名
                val alt = names.filter {
                    val l = it.lowercase()
                    l.contains("moment") || l.contains("timeline") || l.contains("comment")
                }
                if (alt.isNotEmpty()) own("SNS   alt=${alt.joinToString(",").take(200)}")
            }
        }
    }

    private fun dumpSample(only: String?) {
        val snapshot = synchronized(dbs) { dbs.toList() }
        for (db in snapshot) {
            val p = pathOf(db)
            if (!isOpen(db)) continue
            val names = column(db, "select name from sqlite_master where type='table'")
            val sns = if (only.isNullOrBlank()) {
                // SnsInfo 是最关键的一张，按字母序 take 会被别的 sns 表挤掉 —— 提到最前
                names.filter { it.lowercase().contains("sns") }
                    .sortedByDescending { it.lowercase().contains("info") }
                    .take(6)
            } else {
                names.filter { it.equals(only, ignoreCase = true) }
            }
            for (t in sns) {
                val info = runCatching { rows(db, "pragma table_info($t)") }.getOrDefault(emptyList())
                own("SNS schema ${t.take(40)} db=$p cols=" + info.size)
                for (r in info.take(60)) own("SNS   col ${r.take(120)}")
                val one = runCatching { rows(db, "select * from $t limit 1") }.getOrDefault(emptyList())
                for (r in one) own("SNS   row ${r.take(600)}")
            }
        }
    }

    /**
     * 「snsId 到底有没有唯一约束」—— restore 能不能上真机的**硬门禁**。
     *
     * ⚠️ 为什么 `sample` 不够：`pragma table_info` 的 `pk` 列**只反映 PRIMARY KEY 声明**，
     * 看不见 `CREATE UNIQUE INDEX`。真机实测 SnsInfo 18 列**全 pk=0**，但那不代表没有唯一约束
     * —— SQLite 的唯一约束常靠独立索引实现。所以必须查 index_list + index_info 才能定案。
     *
     * 判定：存在覆盖 snsId 的 unique 索引 → `CONFLICT_IGNORE` 有效（撞唯一约束就退）；
     * 都没有 → IGNORE 形同虚设，restore 可能在已有行旁再插一条一样的，**整条路别上真机**。
     */
    private fun dumpIndexes(only: String?) {
        val t = safeIdent(only) ?: "SnsInfo"
        val snapshot = synchronized(dbs) { dbs.toList() }
        for (db in snapshot) {
            if (!isOpen(db)) continue
            val list = runCatching { rows(db, "pragma index_list($t)") }.getOrDefault(emptyList())
            if (list.isEmpty()) continue // 这个库里没有这张表
            val p = pathOf(db)
            own("SNS index_list $t db=$p n=" + list.size + " (unique=1 才是唯一索引)")
            for (r in list) own("SNS   idx ${r.take(200)}")
            // 每个索引覆盖哪些列 —— 唯一索引若不覆盖 snsId，对 INSERT 去重没有帮助
            for (name in columnNamed(db, "pragma index_list($t)", "name")) {
                val safe = safeIdent(name) ?: continue
                val info = runCatching { rows(db, "pragma index_info($safe)") }.getOrDefault(emptyList())
                if (info.isEmpty()) continue
                val cols = columnNamed(db, "pragma index_info($safe)", "name").joinToString(",")
                own("SNS   info $safe cols=$cols")
                for (r in info) own("SNS     ${r.take(160)}")
            }
        }
    }

    /**
     * 建表 / 建索引的原始 DDL —— 人眼最不容易误读的证据（有没有 PRIMARY KEY、UNIQUE 一眼可见）。
     * `select type, name, sql from sqlite_master where tbl_name=?`
     */
    private fun dumpDdl(only: String?) {
        val t = safeIdent(only) ?: "SnsInfo"
        val snapshot = synchronized(dbs) { dbs.toList() }
        for (db in snapshot) {
            if (!isOpen(db)) continue
            val r = runCatching {
                rows(db, "select type, name, sql from sqlite_master where tbl_name='$t'")
            }.getOrDefault(emptyList())
            if (r.isEmpty()) continue
            own("SNS ddl $t db=${pathOf(db)} n=" + r.size)
            for (line in r) own("SNS   ddl ${line.take(700)}")
        }
    }

    /** 取结果集里指定列的一列值（index_list 的 name / unique，index_info 的 name 等） */
    private fun columnNamed(db: Any, sql: String, col: String): List<String> {
        val cur = raw(db, sql) ?: return emptyList()
        return runCatching {
            val i = XposedHelpers.callMethod(cur, "getColumnIndex", col) as? Int ?: -1
            val out = mutableListOf<String>()
            if (i >= 0) {
                while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                    out.add(XposedHelpers.callMethod(cur, "getString", i) as? String ?: "")
                }
            }
            out
        }.also { close(cur) }.getOrDefault(emptyList())
    }

    /** 表名/索引名来自广播参数，拼进 SQL 前先卡白名单，别把 SQL 拼坏 */
    private fun safeIdent(s: String?): String? {
        val t = s?.trim() ?: return null
        if (t.isEmpty() || t.length > 64) return null
        if (!t.all { it.isLetterOrDigit() || it == '_' }) return null
        return t
    }

    /** 单行单列查询 */
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

    /** 多行多列：一行拼成 `k=v | k=v` —— 直接看字段名和值，不用猜 */
    private fun rows(db: Any, sql: String): List<String> {
        val cur = raw(db, sql) ?: return emptyList()
        return runCatching {
            val cnt = XposedHelpers.callMethod(cur, "getColumnCount") as? Int ?: 0
            val out = mutableListOf<String>()
            var n = 0
            while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true && n < 60) {
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
     * ⚠️ **不能用 `findMethodExact`**：wcdb 的重载不止 `(String, String[])` 一种，
     * 精确匹配会找不到（第一版就是这么静默失败的 —— `tables=0` 却没有任何报错）。
     * 改成遍历方法名挑第一个「首参 String、次参 String[] 或 Object[]」的，
     * 这是防撤回那边真机验证过的写法。
     *
     * ⚠️⚠️ **绝不能跨库缓存 Method**：微信同时有 wcdb 和 AOSP 两套 SQLiteDatabase，
     * 缓存了 AOSP 的 rawQuery 再拿去调 wcdb 实例会抛
     * `IllegalArgumentException: Expected receiver of type android.database.sqlite.SQLiteDatabase,
     * but got com.tencent.wcdb.database.SQLiteDatabase`（真机踩到过，表现为所有库都查不出表）。
     * dump 操作调用次数很少，每次重新找，不缓存。
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
            own("SNS raw: no rawQuery(String,[]) on ${db.javaClass.name}")
            return null
        }
        inProbe.set(true)
        return try {
            runCatching { m.invoke(db, sql, null) }
                .onFailure { own("SNS raw err ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}") }
                .getOrNull()
        } finally {
            inProbe.set(false)
        }
    }

    /** 库已关闭就没法查，先判一次，省得每次都去猜「为什么返回空」 */
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

    private fun frames(): List<String> {
        val out = mutableListOf<String>()
        for (st in Throwable().stackTrace) {
            if (out.size >= FRAMES) break
            val s = st.toString()
            if (NOISE.any { s.contains(it) }) continue
            out.add(s)
        }
        return out
    }

    private fun flushInternal() {
        val entries = counts.entries.sortedByDescending { it.value }
        own("SNS FLUSH kinds=${entries.size} passed=${passed.get()} " +
            "windowLeft=${(until - System.currentTimeMillis()).coerceAtLeast(0)}ms")
        for (e in entries.take(60)) own("SNS   ${e.value}x ${e.key}")
    }

    // ------------------------------------------------------------------ 落盘

    private val queue = ConcurrentLinkedQueue<String>()

    @Volatile
    private var writer: Thread? = null

    private fun own(msg: String) {
        runCatching {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            queue.add("$ts SNS $msg")
        }
        startWriter()
    }

    @Synchronized
    private fun startWriter() {
        val t = writer
        if (t != null && t.isAlive) return
        val nt = Thread({
            while (!Thread.currentThread().isInterrupted) {
                runCatching {
                    Thread.sleep(400)
                    drain()
                }.onFailure { return@Thread }
            }
        }, "sns-log").apply { isDaemon = true }
        writer = nt
        nt.start()
    }

    private fun stopWriter() {
        repeat(3) { drain() }
        writer?.let { runCatching { it.interrupt() } }
        writer = null
    }

    private fun drain() {
        if (queue.isEmpty()) return
        val sb = StringBuilder()
        while (sb.length < 16384) {
            val s = queue.poll() ?: break
            sb.append(s).append('\n')
        }
        runCatching {
            val f = File(LOG)
            f.parentFile?.mkdirs()
            f.appendText(sb.toString())
        }.onFailure {
            runCatching { LogX.w("SNS write failed: ${it.javaClass.simpleName}") }
            // 兜底再走 logcat：文件真写不进去时至少留点痕迹，否则会误判「探针没命中」
            runCatching { android.util.Log.e("WXGroupSNS", "write failed: ${it.javaClass.simpleName}") }
        }
    }
}
