package com.lx.wxgroup.probe

import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.feature.AntiRevoke
import com.lx.wxgroup.feature.LabelLogic
import com.lx.wxgroup.feature.LabelSource
import com.lx.wxgroup.feature.MuteHuntLogic
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.xmlpull.v1.XmlPullParser as XP
import java.util.Collections
import java.util.WeakHashMap

/**
 * K3「按微信标签选人」的诊断探针（`op=label`）。
 *
 * 真机已确认主库包含 `ContactLabel(labelID,labelName)` 与
 * `rcontact(username,contactLabelIds)`；日常验证优先使用 `names` / `of` / `stat`，
 * 原有枚举与只读 SQL 子命令保留作版本漂移诊断。
 *
 * `contactLabelIds` 的真实分隔符仍待真机确认，`sub=of` 会同时输出原始串、解析 id
 * 与标签名，不能只看解析结果后反推分隔符。
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
 *     # 只放行 select / explain / 只读 pragma，见 isReadOnly
 * adb shell ... --es op label --es sub names          # 全部 id→name 映射
 * adb shell ... --es op label --es sub of --es user <wxid> # 某联系人标签名 + 原始 id 串
 * adb shell ... --es op label --es sub stat           # ready / 索引 / cache hit
 * adb shell ... --es op label --es sub off                       # 取完务必关掉（钩子在热路径上）
 * ```
 * 日志前缀 `LABEL`。
 *
 * # 「群消息免打扰」开关锚点取证（同一个 op，另一组 sub）
 *
 * `AutoDndGroup` 现在是降级实现（只改写到静音通道，没打开微信自己的开关），
 * 缺的是「开关存在哪」的实证。用**开关对照法**：同一群开着 before/after 两份整行快照 diff。
 * 纯判据在 `feature/MuteHuntLogic.kt`。日志前缀 `MUTE`。
 * ```
 * adb shell ... --es op label --es sub mutetables            # 候选表清单（表名/列名命中 + 行数）
 * adb shell ... --es op label --es sub mute1 --es user 49543624181@chatroom
 *   # ⚠️ 然后**在手机上手动打开**这个群的「消息免打扰」（微信→群→右上角→消息免打扰）
 * adb shell ... --es op label --es sub mute2 --es user 49543624181@chatroom   # 自动出 DIFF
 * adb shell ... --es op label --es sub mutediff      # 重看一次 DIFF（不用重跑 mute1/mute2）
 * adb shell ... --es op label --es sub muteclear     # 清掉两份快照
 * adb shell ... --es op label --es sub muteprefs --es user 49543624181@chatroom
 *   # 旁路线：宿主 shared_prefs 下的 xml 里有没有这个群 / 开关类 key
 * ```
 * ⚠️ `mutetables` / `mute1` / `mute2` 会遍历所有库查 sqlite_master + pragma + count，
 * 必须走后台线程（EntryReceiver 里 `op=label` 已经包了 `io{}`）。
 */
object LabelHuntProbe {

    private const val TAG = "LABEL"

    /** 免打扰锚点取证的输出前缀（写成两行 logcat grep 能分开看） */
    private const val MTAG = "MUTE"

    private const val MAX_WINDOW_SEC = 180

    /**
     * 表名白名单：拼进 SQL 前必须过这一关。
     * 表名来自广播参数（可被任意 app 伪造），不校验就等于把 SQL 注入面敞开。
     */
    private val SAFE_IDENT = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")

    /** 快照 diff 最多收集多少条差异（全量写文件，日志只打前 60 行） */
    private const val MAX_DIFF_LINES = 3000

    /**
     * 快照文件的输出目录。
     *
     * ⚠️ **不能放 `/sdcard/Android/data/com.tencent.mm/`** —— Android 11+ 下这个目录
     * 对 adb `pull` 是 Permission denied（实测踩到）。当前日志文件能 pull 是因为
     * adb 恰好对该包有 legacy 访问，但新建文件不行。
     * `/sdcard/Download` 实测**可写且可 pull**，用它。
     */
    private val OUT_DIR = java.io.File("/sdcard/Download/wxgroup_snap")

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
     * 「群消息免打扰」开关对照法的两份整行快照（见类注释）。
     *
     * A = 开关前（`sub=mute1`），B = 开关后（`sub=mute2`）。两份快照必须针对**同一个目标群**，
     * 目标不一致时 [dumpDiff] 直接拒绝出结果（错配的 diff 比没有 diff 更误导）。
     */
    @Volatile
    private var snapA: MuteHuntLogic.Snap? = null

    @Volatile
    private var snapB: MuteHuntLogic.Snap? = null

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

    fun command(
        sub: String,
        sec: Int,
        table: String?,
        sql: String?,
        n: Int,
        user: String?,
    ) {
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

            "stat" -> {
                LabelSource.refreshBlocking()
                own(
                    "$TAG probe hooks=${unhooks.size} dbs=${dbs.size} " +
                        "mainDb=${pathOf(AntiRevoke.wxDb())} " +
                        "windowLeft=${(until - System.currentTimeMillis()).coerceAtLeast(0)}ms"
                )
                own(LabelSource.stat())
            }

            "names" -> dumpNames()

            "of" -> dumpOf(user)

            /** 见过哪些库（path 是判断「该往哪个库读」的唯一依据） */
            "dbs" -> dumpDbs()

            /** 每个库有哪些表，命中关键字的列出来；没命中就把表名全列出来让人眼看 */
            "tables" -> dumpTables()

            "info" -> dumpInfo(table)

            "rows" -> dumpRows(table, n)

            /** 候选表清单（`mutetables`；`--es table` 逗号分隔可强制指定几张表） */
            "mutetables" -> dumpMuteTables(table)

            /** 开关对照法：dump 目标群在所有候选表里的整行（开关**前**） */
            "mute1" -> {
                dumpMuteSnapshot(user, table, phase = 1)
                own("$MTAG 下一步：去微信里手动打开这个群的「消息免打扰」，回来再 sub=mute2")
            }

            /** 开关对照法：再 dump 一次（开关**后**），并立刻与 mute1 逐字段 diff */
            "mute2" -> {
                if (snapA == null) {
                    own("$MTAG mute2: 没有 A 快照，先 sub=mute1 --es user <群wxid>")
                    return
                }
                dumpMuteSnapshot(user, table, phase = 2)
                dumpDiff()
            }

            /** 重看一次 DIFF（不用重跑 dump） */
            "mutediff" -> dumpDiff()

            /** 丢掉两份快照，换一个群重做 */
            "muteclear" -> {
                snapA = null
                snapB = null
                own("$MTAG cleared")
            }

            /** 旁路线：shared_prefs 里有没有这个群 / 开关类 key */
            "muteprefs" -> dumpPrefs(user)

            /**
             * 快照法（**不依赖 hook、不依赖时间窗口**）：
             * `snapshot --es table rconversation --es file snap_a`
             * 用户切开关后再存 snap_b，`diffsnap` 对比。
             * 专治「mute1→手动→mute2 对不齐时间」这种白跑。
             */
            "snapshot" -> dumpSnapshot(table, user)

            /** 对比两份快照：`--es table snap_a --es user snap_b` */
            "diffsnap" -> diffSnapshot(table, user)

            /**
             * 任意只读 SQL。取证后期不想为了一条查询再打一次包时用。
             * ⚠️ 只放行 select / explain / 只读 pragma（判据见 [isReadOnly]）—— 探针绝不写微信的库。
             */
            "sql" -> runSql(sql)

            else -> own(
                "$TAG unknown sub=$sub " +
                    "(names|of|stat|on|off|dbs|tables|info|rows|sql|" +
                    "mutetables|mute1|mute2|mutediff|muteclear|muteprefs)"
            )
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

    private fun dumpNames() {
        if (!LabelSource.refreshBlocking()) {
            own("$TAG names unavailable: ${LabelSource.stat()}")
            return
        }
        val labels = LabelSource.allLabels()
        own("$TAG names n=${labels.size}")
        for (label in labels) {
            val name = label.name ?: "<empty>"
            own("$TAG   id=${label.id} name=$name members=${label.memberCount}")
        }
    }

    private fun dumpOf(user: String?) {
        val wxid = user?.trim().orEmpty()
        if (wxid.isEmpty()) {
            own("$TAG of: --es user <wxid> 不能为空")
            return
        }
        if (!LabelSource.refreshBlocking()) {
            own("$TAG of user=$wxid unavailable: ${LabelSource.stat()}")
            return
        }
        val raw = LabelSource.rawIdsOf(wxid)
        val ids = LabelLogic.splitIds(raw)
        val names = LabelSource.labelsOf(wxid)
        val resolved = ids.joinToString(",") { id ->
            when {
                !LabelSource.hasLabel(id) -> "$id=<unknown>"
                LabelSource.labelName(id).isNullOrBlank() -> "$id=<empty>"
                else -> "$id=${LabelSource.labelName(id)}"
            }
        }
        own(
            "$TAG of user=$wxid raw=${raw?.let { "[$it]" } ?: "<missing>"} " +
                "ids=${ids.joinToString(",")} names=${names.joinToString(",")} resolved=$resolved"
        )
    }

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

    /**
     * **只读 pragma 动词白名单**。
     *
     * ⚠️ `pragma` 不等于只读 —— 这些动词会改微信的库文件或文件头，
     * 一律**不进**白名单（连读的形式也不放行，少一层判断就少一个出错的地方）：
     * `user_version`（改文件头，可能触发微信自己的迁移/降级，最危险）、
     * `journal_mode`（持久化进文件头）、`optimize`（写 `sqlite_stat1`，没有还会建表）、
     * `incremental_vacuum`、`wal_checkpoint`、`writable_schema`、`application_id`、
     * `schema_version`、`auto_vacuum`、`foreign_keys`。
     *
     * 白名单里剩下的这些**即使被人写成赋值形式也改不动库**，配合
     * [isReadOnly] 里的 `=` 检查构成两层防线。
     */
    private val READ_ONLY_PRAGMAS = setOf(
        "table_info", "table_list",
        "index_list", "index_info", "index_xinfo",
        "foreign_key_list", "foreign_key_check",
        "integrity_check", "quick_check",
        "database_list", "collation_list", "function_list", "compile_options",
        "page_count", "page_size", "freelist_count", "encoding",
    )

    /**
     * 这条 SQL 是不是只读的（`sub=sql` 的唯一关卡）。
     *
     * 三个放行口，各自都有理由：
     * - `select` / `explain`：`explain` 只编译出字节码并列出，**不执行**；
     *   两个都挡掉 `load_extension`（SQLite 的加载原生代码，命中就是在微信进程里执行任意 so）。
     * - `pragma`：**只看动词**，动词不在 [READ_ONLY_PRAGMAS] 里就拒；
     *   动词对了还要再查一次后面有没有 `=` —— `page_size=4096` 这种赋值形式照样是写。
     *
     * 多语句**不用担心**：`rawQuery` 内部是 `sqlite3_prepare_v2`，只编译第一条语句，
     * 分号后面的字节根本不会被 prepare，所以 `select 1; delete from x` 只会跑 `select 1`。
     */
    private fun isReadOnly(sql: String): Boolean {
        val l = sql.lowercase()
        if (l.startsWith("select") || l.startsWith("explain")) {
            return !l.contains("load_extension")
        }
        if (!l.startsWith("pragma")) return false
        val rest = l.removePrefix("pragma").trimStart()
        val verb = rest.takeWhile { it.isLetter() || it == '_' }
        if (verb !in READ_ONLY_PRAGMAS) return false
        return !rest.contains("=")
    }

    private fun runSql(sql: String?) {
        val s = sql?.trim()
        if (s.isNullOrEmpty()) {
            own("$TAG sql: --es sql 不能为空")
            return
        }
        // ⚠️ 探针只做只读：写微信的库是不可逆的
        if (!isReadOnly(s)) {
            own("$TAG sql refused: 只放行 select / explain / 只读 pragma（如 pragma table_info(表名)）")
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

    // ------------------------------------------------------- 免打扰锚点取证（MUTE）

    /** 免打扰 dump 时 `rowid` 的别名列（有它才能稳定对齐两次快照的同一行） */
    private const val LOCATOR_COL = "__rid"

    /** 每个库最多看多少张表（微信主库表数在几百量级，够用） */
    private const val MAX_TABLES_PER_DB = 400

    /**
     * 行数超过这个值就跳过 `like '%wxid%'` 兜底。
     *
     * 精确匹配走不到索引时是全表扫（几十列的 `or`），大表上一次就可能几十秒 ——
     * 兜底只在「表不大且精确没命中」时做，避免一次 `mute1` 拖成分钟级。
     */
    private const val FUZZY_ROW_CAP = 20000L

    private const val MAX_PREF_FILES = 300
    private const val MAX_PREF_FILE_BYTES = 4L * 1024 * 1024
    private const val MAX_PREF_LINES = 200

    /**
     * 候选表清单。
     *
     * 两路筛查都跑：**表名**命中很重要，但微信的表名混淆后很难看得出作用；
     * **列名**命中（`columnHits`）这路更实在 —— 不管表叫什么，只要它有「像开关」的列就列出来。
     * ⚠️ 一个都没命中时必须把全部表名打出来：否则会误判成「微信库里没有群设置表」。
     */
    private fun dumpMuteTables(only: String?) {
        val snapshot = allDbs()
        if (snapshot.isEmpty()) {
            own("$MTAG mutetables: no db instance (先 sub=on 开窗口，或主库还没打开)")
            return
        }
        val forced = MuteHuntLogic.parseTables(only)
        for (db in snapshot) {
            if (!isOpen(db)) continue
            val p = pathOf(db)
            val names = column(db, "select name from sqlite_master where type='table' order by name")
            if (names.isEmpty()) {
                own("$MTAG db=$p (sqlite_master 空 / 查不到)")
                continue
            }
            var cand = 0
            var colhit = 0
            for (n in names.take(MAX_TABLES_PER_DB)) {
                if (cand + colhit >= MAX_NAMES) break
                val isForced = forced.any { it.equals(n, ignoreCase = true) }
                val by = MuteHuntLogic.tableHit(n)
                val cols = colNames(db, n)
                val hits = MuteHuntLogic.columnHits(cols)
                if (forced.isNotEmpty() && !isForced) continue
                if (!isForced && by == null && hits.isEmpty()) continue
                val rows = rowCount(db, n)
                if (isForced || by != null) {
                    cand++
                    own(
                        "$MTAG   cand=$n by=${if (isForced) "forced" else by} rows=$rows " +
                            "cols=${cols.size} colhits=${hits.joinToString("|")}"
                    )
                } else {
                    colhit++
                    own("$MTAG   colhit=$n rows=$rows cols=${cols.size} hits=${hits.joinToString("|")}")
                }
            }
            if (cand + colhit == 0) {
                own("$MTAG db=$p tables=${names.size} hit=0 all=${names.take(MAX_NAMES).joinToString(",")}")
            } else {
                own(
                    "$MTAG db=$p tables=${names.size} cand=$cand colhit=$colhit" +
                        if (cand + colhit >= MAX_NAMES) " ⚠️ 被 $MAX_NAMES 上限截断，输出不全（可用 --es table 指定单独看）" else ""
                )
            }
        }
    }

    /**
     * 开关对照法的一次快照：把目标群在**所有候选表**里的**整行全列** dump 进内存。
     *
     * 刻意 dump 整行而不是「关心的那几列」—— diff 的价值全在看到**意外**的变化，
     * 只挑列等于先假设了答案。
     */
    private fun dumpMuteSnapshot(user: String?, only: String?, phase: Int) {
        val target = user?.trim().orEmpty()
        if (target.isEmpty()) {
            own("$MTAG: --es user <群wxid> 不能为空（形如 49543624181@chatroom）")
            return
        }
        val snapshot = allDbs()
        if (snapshot.isEmpty()) {
            own("$MTAG: no db instance (先 sub=on 开窗口)")
            return
        }
        val forced = MuteHuntLogic.parseTables(only)
        val tables = ArrayList<MuteHuntLogic.TableSnap>()
        var dbs = 0
        var skipped = 0
        for (db in snapshot) {
            if (!isOpen(db)) continue
            val p = pathOf(db)
            dbs++
            for (n in column(db, "select name from sqlite_master where type='table' order by name")
                .take(MAX_TABLES_PER_DB)
            ) {
                val isForced = forced.any { it.equals(n, ignoreCase = true) }
                if (forced.isNotEmpty() && !isForced) continue
                if (forced.isEmpty() && MuteHuntLogic.tableHit(n) == null) continue
                val cols = colNames(db, n)
                if (cols.isEmpty()) {
                    skipped++
                    continue
                }
                val raw = matchingRows(db, n, cols, target)
                if (raw.isEmpty()) continue
                val rows = raw.mapIndexed { idx, cells ->
                    val loc = cells.firstOrNull { it.first == LOCATOR_COL }?.second ?: "#$idx"
                    MuteHuntLogic.Row(
                        loc,
                        cells.filter { it.first != LOCATOR_COL }.map { MuteHuntLogic.Cell(it.first, it.second) }
                    )
                }
                tables += MuteHuntLogic.TableSnap(p, n, rows)
                own("$MTAG   dump=$n rows=${rows.size} cols=${cols.size}")
            }
        }
        val snap = MuteHuntLogic.Snap(target, System.currentTimeMillis(), tables)
        if (phase == 1) snapA = snap else snapB = snap
        own(
            "$MTAG phase=$phase target=$target dbs=$dbs tablesWithRows=${tables.size} " +
                "rows=${snap.rowCount()} skippedCols=$skipped at=${snap.atMs}"
        )
        if (tables.isEmpty()) {
            own("$MTAG   ⚠️ 一张表都没 dump 到行：target 打错了？或群设置不在名字像的表里 → sub=mutetables 换 --es table 强制指定")
        }
    }

    /** 输出 A→B 的字段级差分。**这就是要找锚点的地方**。 */
    private fun dumpDiff() {
        val a = snapA
        val b = snapB
        if (a == null || b == null) {
            own("$MTAG mutediff: 需要两份快照（A=${if (a == null) "缺" else "有"} B=${if (b == null) "缺" else "有"}）")
            return
        }
        if (a.target != b.target) {
            own("$MTAG mutediff: 两次快照目标不同 A=${a.target} B=${b.target} → sub=muteclear 后重跑")
            return
        }
        val d = MuteHuntLogic.diff(a, b)
        own(
            "$MTAG DIFF target=${a.target} gapMs=${b.atMs - a.atMs} tables=${d.tablesCompared} " +
                "rows=${d.rowsCompared} cells=${d.cellsCompared} " +
                "changes=${d.changes.size} valueChanges=${d.valueChanges().size}"
        )
        if (d.changes.isEmpty()) {
            own("$MTAG   ⚠️ 零变化：开关没真的切换，或落点不在这些表里 → sub=mutetables 加表，走 muteprefs 旁路线")
            return
        }
        for (c in d.changes.take(60)) {
            if (c.kind == MuteHuntLogic.Kind.VALUE) {
                own(
                    "$MTAG   CHG ${dbShort(c.dbPath)}#$c.table row=${c.locator} " +
                        "col=${c.col}: [${show(c.before)}] -> [${show(c.after)}]"
                )
            } else {
                own("$MTAG   ${c.kind} ${dbShort(c.dbPath)}#$c.table row=${c.locator} col=${c.col ?: "-"}")
            }
        }
    }

    /**
     * 旁路线：宿主的 `shared_prefs` 目录下那些 xml 里有没有这个群 / 「开关类」key。
     *
     * ⚠️ 写作时别在注释里打出 `星号斜杠` 那种「路径通配符」形状 —— Kotlin 的块注释是**可嵌套**的，
     * 一个不小心就把整个文件后半段吃进注释里（本文件已经踩过一次）。
     *
     * 微信很多 UI 级开关是 SP 持久化而不是进 SQLite。同一个 app 进程内读自己的
     * `dataDir` 不需要 root，纯只读。
     * ⚠️ 微信另有 MMKV（二进制），本探针读不了它的值，只提示目录存在 —— 那条路要另做全盘字节扫描。
     */
    private fun dumpPrefs(user: String?) {
        val target = user?.trim().orEmpty()
        val root = dataDir()
        if (root == null) {
            own("$MTAG muteprefs: 拿不到宿主 dataDir")
            return
        }
        val dir = java.io.File(root, "shared_prefs")
        val files = if (dir.isDirectory) {
            (dir.listFiles()?.toList() ?: emptyList()).sortedBy { it.name }
        } else {
            emptyList()
        }
        own("$MTAG muteprefs root=$root files=${files.size}${if (target.isEmpty()) "" else " target=$target"}")
        if (files.isEmpty()) return
        var printed = 0
        for (f in files.take(MAX_PREF_FILES)) {
            if (f.length() > MAX_PREF_FILE_BYTES) {
                own("$MTAG   skip ${f.name} (${f.length()}B)")
                continue
            }
            val lines = runCatching { prefHits(f, target) }.getOrDefault(emptyList())
            if (lines.isEmpty()) continue
            own("$MTAG   file=${f.name} hits=${lines.size}")
            for (l in lines.take(20)) {
                if (printed >= MAX_PREF_LINES) break
                printed++
                own("$MTAG     $l")
            }
        }
        val mmkv = java.io.File(root, "mmkv")
        if (mmkv.isDirectory) {
            own(
                "$MTAG   ⚠️ 发现 mmkv 目录（二进制，本探针看不了里面的值）" +
                    "files=${mmkv.listFiles()?.size ?: 0} → 需要另做字节扫描探针"
            )
        }
    }

    /** 从一个 SP XML 里挑出「名字/值含目标群」或「名字像开关」的条目 */
    private fun prefHits(f: java.io.File, target: String): List<String> {
        val entries = ArrayList<Pair<String, String>>()
        val parser = android.util.Xml.newPullParser()
        java.io.FileInputStream(f).use { parser.setInput(it, null) }
        var pending: String? = null
        val text = StringBuilder()
        var ev = parser.eventType
        while (ev != XP.END_DOCUMENT && entries.size < 4000) {
            when (ev) {
                XP.START_TAG -> {
                    val k = parser.getAttributeValue(null, "name")
                    if (k != null) {
                        val v = parser.getAttributeValue(null, "value")
                        if (v != null) {
                            entries += k to v
                            pending = null
                        } else {
                            pending = k
                        }
                        text.setLength(0)
                    }
                }
                XP.TEXT -> text.append(parser.text)
                XP.END_TAG -> {
                    val k = pending
                    if (k != null) {
                        entries += k to text.toString()
                        pending = null
                    }
                }
            }
            ev = parser.next()
        }
        val lowTarget = target.lowercase()
        return entries.filter { (k, v) ->
            if (target.isNotEmpty() && (k.lowercase().contains(lowTarget) || v.lowercase().contains(lowTarget))) {
                true
            } else {
                val lk = k.lowercase()
                MuteHuntLogic.COLUMN_HINTS.any { lk.contains(it) } ||
                    MuteHuntLogic.STRONG_TABLE_HINTS.any { lk.contains(it) }
            }
        }.take(60).map { (k, v) -> "$k = ${show(v)}" }
    }

    /** 宿主的 dataDir：优先 HostContext，退化时用主库路径往上剪到 `com.tencent.mm` */
    private fun dataDir(): String? {
        val direct = runCatching { HostContext.app()?.dataDir?.absolutePath }.getOrNull()
        if (!direct.isNullOrEmpty()) return direct
        val p = pathOf(AntiRevoke.wxDb())
        val i = p.lastIndexOf("/MicroMsg/")
        return if (i > 0) p.substring(0, i) else null
    }

    /** 一张表里跟目标群有关的行：精确 →（必要时）去 rowid 重试 →（必要时）like 兜底 */
    private fun matchingRows(
        db: Any,
        table: String,
        cols: List<String>,
        target: String,
    ): List<List<Pair<String, String>>> {
        val withRid = MuteHuntLogic.selectSql(
            table, cols, target, fuzzy = false,
            includeRowid = true, limit = MuteHuntLogic.MAX_ROWS_PER_TABLE
        )
        var out = if (withRid == null) emptyList() else runCatching { snapRows(db, withRid) }.getOrDefault(emptyList())
        if (out.isNotEmpty()) return out
        // WITHOUT ROWID 的表取 rowid 会直接报错 —— 换个没 rowid 的同款查询再试
        val noRid = MuteHuntLogic.selectSql(
            table, cols, target, fuzzy = false,
            includeRowid = false, limit = MuteHuntLogic.MAX_ROWS_PER_TABLE
        )
        if (noRid != null) {
            out = runCatching { snapRows(db, noRid) }.getOrDefault(emptyList())
            if (out.isNotEmpty()) return out
            // 列值可能是序列化串，wxid 被包在里面。大表跳过（怕一次 dump 拖成分钟级）
            if (rowCount(db, table) <= FUZZY_ROW_CAP) {
                val fuzzy = MuteHuntLogic.selectSql(
                    table, cols, target, fuzzy = true,
                    includeRowid = false, limit = MuteHuntLogic.MAX_ROWS_PER_TABLE
                )
                if (fuzzy != null) {
                    out = runCatching { snapRows(db, fuzzy) }.getOrDefault(emptyList())
                }
            }
        }
        return out
    }

    /** 多行多列读成 `List<Row>`（每格取的是**原值视图**，BLOB 转 hex —— blob 里才是微信最爱塞设置的地方） */
    private fun snapRows(db: Any, sql: String): List<List<Pair<String, String>>> {
        val cur = raw(db, sql) ?: return emptyList()
        return runCatching {
            val cnt = XposedHelpers.callMethod(cur, "getColumnCount") as? Int ?: 0
            val out = mutableListOf<List<Pair<String, String>>>()
            while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                val row = ArrayList<Pair<String, String>>(cnt)
                for (i in 0 until cnt) {
                    val name = XposedHelpers.callMethod(cur, "getColumnName", i) as? String ?: "c$i"
                    row += name to cellView(cur, i)
                }
                out += row
                if (out.size >= MuteHuntLogic.MAX_ROWS_PER_TABLE) break
            }
            out
        }.also { close(cur) }.getOrDefault(emptyList())
    }

    /** 一个格子的值视图（NULL / TEXT / BLOB 都走得通） */
    private fun cellView(cur: Any, i: Int): String {
        val type = runCatching { XposedHelpers.callMethod(cur, "getType", i) as? Int }.getOrNull()
        if (type == 4) { // Cursor.FIELD_TYPE_BLOB
            val b = runCatching { XposedHelpers.callMethod(cur, "getBlob", i) as? ByteArray }.getOrNull()
            if (b != null) return "BLOB[" + MuteHuntLogic.compact(hexOf(b)) + "]"
        }
        val s = runCatching { XposedHelpers.callMethod(cur, "getString", i) as? String }.getOrNull()
        return MuteHuntLogic.compact(s ?: "<null>")
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** 前 1024 字节 + 后 256 字节的 hex，中间那截不展开（一个 proto 动辄几十 KB，全打出来只会刷屏） */
    private fun hexOf(b: ByteArray): String {
        if (b.isEmpty()) return "len=0"
        val head = StringBuilder()
        for (i in 0 until b.size.coerceAtMost(1024)) {
            head.append(HEX[(b[i].toInt() and 0xFF) shr 4]).append(HEX[b[i].toInt() and 0x0F])
        }
        if (b.size <= 1024) return "len=${b.size}:$head"
        val tail = StringBuilder()
        for (i in (b.size - 256).coerceAtLeast(1024) until b.size) {
            tail.append(HEX[(b[i].toInt() and 0xFF) shr 4]).append(HEX[b[i].toInt() and 0x0F])
        }
        return "len=${b.size}:$head...$tail"
    }

    private fun colNames(db: Any, table: String): List<String> {
        val cur = runCatching {
            raw(db, "pragma table_info(${MuteHuntLogic.quoteIdent(table)})")
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<String>()
        return runCatching {
            while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                val name = XposedHelpers.callMethod(cur, "getString", 1) as? String
                if (!name.isNullOrEmpty()) out += name
            }
            out
        }.also { close(cur) }.getOrDefault(out)
    }

    private fun rowCount(db: Any, table: String): Long = runCatching {
        column(db, "select count(*) from ${MuteHuntLogic.quoteIdent(table)}")
            .firstOrNull()?.trim()?.toLongOrNull() ?: -1L
    }.getOrDefault(-1L)

    /** 日志里只留库路径的后两级（`.../EnMicroMsg.db`），每行省几十字符 */
    private fun dbShort(path: String): String =
        runCatching { path.split('/').takeLast(2).joinToString("/") }.getOrDefault(path)

    /** 值太长时砍成「头 + 长度 + 尾」，保证变化点在 logcat 单行里看得见 */
    private fun show(v: String?): String {
        val s = v ?: "-"
        return if (s.length <= 240) s else s.take(160) + "…(len=" + s.length + ")…" + s.takeLast(60)
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

    /**
     * 防重入：探针自己 dump 时也会调 rawQuery，会被自己的钩子记下 → 日志自污染。
     * （SnsHuntProbe 里也有一个同名机制，两处各自独立，别混用）
     */
    private val inProbe = ThreadLocal.withInitial { false }

    /**
     * 把一张表的**全部行全列**导出到设备文件，供离线 diff。
     *
     * 为什么需要它：`mute1 → 手动切开关 → mute2` 那条链要求用户操作与窗口时间对齐，
     * 前面几轮都因为对不齐而白跑。这个命令改成「先落一份全量快照到文件」，
     * 用户什么时候切都行 —— 事后 `mutesnap2` 再落一份，两份文件直接 diff，
     * **不依赖 hook、不依赖时间窗口**。
     *
     * ⚠️ 走 `ANDROID_DATA/clqj4q30` 这种外部可写目录（app 私有目录 adb pull 不到），
     * 具体落到 `files-dir/snapshot`。行数可能上万，只在用户主动触发时跑。
     */
    private fun dumpSnapshot(table: String?, file: String?) {
        val t = table?.trim()
        if (t.isNullOrEmpty()) {
            own("$TAG snapshot: --es table 不能为空")
            return
        }
        if (!SAFE_IDENT.matches(t)) {
            own("$TAG snapshot: 表名不合法 $t")
            return
        }
        val db = dbWith(t)
        if (db == null) {
            own("$TAG snapshot: 找不到含表 $t 的库（先进一次相关页面让库被打开）")
            return
        }
        inProbe.set(true)
        try {
            val cur = raw(db, "select * from $t") ?: run {
                own("$TAG snapshot: rawQuery 失败 $t")
                return
            }
            val cnt = XposedHelpers.callMethod(cur, "getColumnCount") as? Int ?: 0
            val cols = ArrayList<String>(cnt)
            for (i in 0 until cnt) {
                cols.add(
                    runCatching { XposedHelpers.callMethod(cur, "getColumnName", i) as? String }
                        .getOrNull() ?: "c$i"
                )
            }
            val sb = StringBuilder()
            sb.append("#").append(t).append('\t').append(cols.joinToString("\t")).append('\n')
            var n = 0
            while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                n++
                sb.append(n).append('\t')
                for (i in 0 until cnt) {
                    // getString 返回 Any?（反射调用），必须显式转 String 才能 replace
                    val v = runCatching { XposedHelpers.callMethod(cur, "getString", i) as? String }
                        .getOrNull()
                    sb.append(v?.replace('\t', ' ')?.replace('\n', ' ') ?: "")
                    if (i < cnt - 1) sb.append('\t')
                }
                sb.append('\n')
            }
            close(cur)
            val name = file?.trim()?.takeIf { it.isNotEmpty() } ?: "snap_$t"
            val out = java.io.File(OUT_DIR, "$name.tsv")
            out.parentFile?.mkdirs()
            out.writeText(sb.toString())
            own("$TAG snapshot ok table=$t rows=$n cols=${cnt} file=${out.absolutePath}")
        } catch (e: Throwable) {
            own("$TAG snapshot err ${e.javaClass.simpleName}: ${(e.cause ?: e).message?.take(100)}")
        } finally {
            inProbe.set(false)
        }
    }

    /**
     * 对比两份快照，逐行逐列列出差异。
     *
     * ⚠️ 只按第 1 列（rowid）对齐；行号会因新增/删除而漂移，所以**先按 rowid 建索引**，
     * 同时输出「只在 A」「只在 B」两个集合，避免漂移造成的假差异。
     */
    private fun diffSnapshot(a: String?, b: String?) {
        val fa = java.io.File(OUT_DIR, "${a?.trim() ?: "snap_a"}.tsv")
        val fb = java.io.File(OUT_DIR, "${b?.trim() ?: "snap_b"}.tsv")
        if (!fa.exists() || !fb.exists()) {
            own("$TAG diff: 缺少快照文件 A=${fa.exists()} B=${fb.exists()} (dir=${OUT_DIR.absolutePath})")
            return
        }
        val A = readTsv(fa)
        val B = readTsv(fb)
        val head = A.first
        val rowsA = A.second.associateBy { it.firstOrNull() ?: "" }
        val rowsB = B.second.associateBy { it.firstOrNull() ?: "" }
        var chg = 0
        var onlyA = 0
        var onlyB = 0
        val lines = ArrayList<String>()
        for ((k, va) in rowsA) {
            val vb = rowsB[k]
            if (vb == null) {
                onlyA++
                if (lines.size < MAX_DIFF_LINES) lines.add("ONLY-A row=$k")
                continue
            }
            if (va.size == vb.size) {
                for (i in va.indices) {
                    if (va[i] != vb[i]) {
                        chg++
                        if (lines.size < MAX_DIFF_LINES) {
                            val cn = head.getOrNull(i) ?: "$i"
                            val av = va[i].take(50)
                            val bv = vb[i].take(50)
                            lines.add("CHG row=$k col=$cn 「$av」→「$bv」")
                        }
                    }
                }
            }
        }
        for (k in rowsB.keys) if (k !in rowsA) {
            onlyB++
            if (lines.size < MAX_DIFF_LINES) lines.add("ONLY-B row=$k")
        }
        own("$TAG diff A=${A.second.size}行 B=${B.second.size}行 changes=$chg onlyA=$onlyA onlyB=$onlyB")
        // 日志里只打前 60 行防刷屏，**全量差异写文件**（差异可能几百条，截断会漏掉关键列）
        val shown = minOf(lines.size, 60)
        for (i in 0 until shown) own("$TAG   ${lines[i]}")
        if (lines.size > shown) own("$TAG   ... 另有 ${lines.size - shown} 条，见 diff 文件")
        // ⚠️ 差异行数可能远超日志行预算（60 行上限只保证不刷屏，但会截断真实差异）
        // → 全量差异另存文件，供 adb pull 后离线细看
        runCatching {
            val out = java.io.File(OUT_DIR, "diff_${a?.trim() ?: "a"}_${b?.trim() ?: "b"}.txt")
            out.parentFile?.mkdirs()
            out.writeText(
                "A=${A.second.size} B=${B.second.size} changes=$chg onlyA=$onlyA onlyB=$onlyB\n" +
                    lines.joinToString("\n")
            )
            own("$TAG diff file=${out.absolutePath} (含全部 ${lines.size} 条，日志里只打了前 60)")
        }
    }

    private fun readTsv(f: java.io.File): Pair<List<String>, List<List<String>>> {
        val all = f.readLines()
        if (all.isEmpty()) return emptyList<String>() to emptyList()
        val head = all[0].split('\t')
        val rows = all.drop(1).filter { it.isNotBlank() }.map { it.split('\t') }
        return head to rows
    }

    private fun own(msg: String) = runCatching { LogX.i(msg) }
}
