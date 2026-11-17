package com.lx.wxgroup.probe

import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.feature.AntiRevoke
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 阶段0「伪会话写库」真机探针（`op=gconv`）。
 *
 * 唯一要回答的问题：**往微信主库 `EnMicroMsg.db` 的 `rconversation` 插一行我们自己造的会话，
 * 会话列表会不会显示、显示成什么名字、能不能置顶**。所有子命令都只为一件事服务 ——
 * 拿到「组名到底该写 `rcontact.nickname` 还是别处」的一手证据。
 *
 * # 为什么写库只能反射
 * 主库实例是 wcdb 的 `com.tencent.wcdb.database.SQLiteDatabase`（super 是 `SQLiteClosable`），
 * **不是** AOSP 的 `android.database.sqlite.SQLiteDatabase`。强转恒为 null（真机实测，
 * 编译期不报错、运行时静默失败，是最容易白跑一轮的坑），所以 `execSQL` / `rawQuery`
 * 一律走 `XposedHelpers.callMethod` 或直接扫方法表拿 `Method`。
 *
 * `rawQuery` 有两个重载（`String[]` 与 `Object[]`），`findMethodExact` 会静默找不到；
 * 且**绝不能跨库缓存 Method**（wcdb 与 AOSP 是两套实现，缓存后调另一个库的实例会抛
 * IllegalArgumentException）。调用次数很少，每次重新扫。这两条都是 LabelHuntProbe 踩过的。
 *
 * # 为什么 insert 要两段式
 * 这张表是微信自己的，列约束（NOT NULL / 默认值 / 唯一索引）我们没有权威清单，
 * 只能试：先只写 7 列（username / flag / conversationTime / msgType / status / chatmode / isSend），
 * 其余靠列默认值 —— 这条最接近微信自己写行的形状，成功概率最高。
 * 失败就退到显式写全部 20 列（值取自真机 dump 到的微X 伪会话行）。
 * **两次都失败时把 `SQLiteException` 的 message 原样打出来** —— 列名/约束报错本身就是
 * 本次取证要的答案之一，吞掉它等于白跑。
 *
 * # 安全边界
 * 所有写入/删除都只作用于 `username` 前缀 `wxgrp_` 的行（我方命名空间，**绝不用微X 的 `wx__`**）。
 * `clean` 的 delete 必须带 `substr(username,1,6)='wxgrp_'` 条件，并在前后各打一次
 * 「非本方前缀行数」做自证：这个数必须**一次都不变**，否则说明 SQL 写漏了条件。
 *
 * 本类**不自己打日志**：每行都带 `GCONV ` 前缀返回给 EntryReceiver 统一 `LogX.i`，
 * 避免探针和入口双写同一事实（`dev.py q 'GCONV'` 过滤出来一行动一个事实）。
 */
object GroupConvProbe {

    private const val TAG = "GCONV"

    /** 我方行前缀。**绝不使用微X 的 `wx__`** —— 那条命名空间不属于我们，撞上就是事故。 */
    private const val PREFIX = "wxgrp_"

    /** 微信的置顶位：`flag = 2^62 + conversationTime`，`2^62` 置位即置顶。 */
    private const val PIN_BASE = 4611686018427387904L

    /** 回滚备份（append，带时间戳）。留着是为了「写脏了能人工还原」。 */
    private const val BACKUP_NAME = "gconv_backup.txt"

    private const val CONV = "rconversation"
    private const val CONTACT = "rcontact"

    /** 真机 dump 到的 `rconversation` 全部 20 列（顺序即建表顺序，写全列时用）。 */
    private val CONV_ALL_COLS = listOf(
        "msgCount", "username", "unReadCount", "chatmode", "status", "isSend",
        "conversationTime", "content", "msgType", "customNotify", "showTips", "flag",
        "digest", "digestUser", "hasTrunc", "parentRef", "attrflag", "editingMsg",
        "atCount", "sightTime",
    )

    /** `wxgrp_` 的 SQL 条件（`substr` 不认 LIKE 的 `_` 通配，比 `like 'wxgrp_%'` 更准）。 */
    private val OWN_WHERE = "substr(username,1,6)='$PREFIX'"

    private val OWNERSHIP_STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 只读 pragma 动词白名单（与 LabelHuntProbe 同一策略，那边是 private 所以在这里重写一份）。
     *
     * `pragma` 不等于只读：`user_version` / `journal_mode` 会改文件头并可能触发微信自己的
     * 迁移/降级，`optimize` 会写 `sqlite_stat1`，一律不放行（连「读」的形式也不放）。
     */
    private val READ_ONLY_PRAGMAS = setOf(
        "table_info", "table_list",
        "index_list", "index_info", "index_xinfo",
        "foreign_key_list", "foreign_key_check",
        "integrity_check", "quick_check",
        "database_list", "collation_list", "function_list", "compile_options",
        "page_count", "page_size", "freelist_count", "encoding",
    )

    // ------------------------------------------------------------------ 入口

    /**
     * @param sub `stat` / `trial` / `rename` / `clean` / `sql` / `views`
     * @param val `rename` 的新名字（`--es val`）
     * @param sql `sql` 子命令的只读查询（`--es sql`）
     * @param n 输出行数上限
     */
    fun run(sub: String, `val`: String?, sql: String?, n: Int): List<String> {
        val out = ArrayList<String>()
        // R55d：`views` 只是枚举当前认领的 FmUI 的 View 字段，**不需要主库句柄**
        // ⇒ 必须放在下面那条 `db == null` 闸门之前，否则页面刚开、库还没捕获时会误报无库。
        if (sub.trim().lowercase() == "views") return com.lx.wxgroup.feature.GconvPage.dumpViews()
        // R55d：`trace` 只挂「纯日志」钩子（不改返回值/不改状态），同样不需要库句柄。
        if (sub.trim().lowercase() == "trace") return com.lx.wxgroup.feature.GconvPage.traceInstall()
        // R55g：`finishtrace` 只挂「纯日志」finish 追踪钩子（不改返回值/不改状态），同样不需要库句柄。
        if (sub.trim().lowercase() == "finishtrace") return com.lx.wxgroup.feature.GconvPage.finishTraceInstall()
        // R55h：`satrace` 只挂「纯日志」startActivity 追踪钩子（不改返回值/不改状态），同样不需要库句柄。
        if (sub.trim().lowercase() == "satrace") return com.lx.wxgroup.feature.StartActivityTrace.install()
        // R59：数据层分组行单人头像现场对账（只读，NativeConvAvatar.dump 自己处理 db 为空的情况）。
        if (sub.trim().lowercase() == "ava") return com.lx.wxgroup.feature.NativeConvAvatar.dump()
        val db = AntiRevoke.wxDb()
        if (db == null) {
            say(out, "no DB instance —— 先让微信写一次库（收发一条消息），主库句柄才被捕获")
            return out
        }
        try {
            when (sub.trim().lowercase()) {
                "stat" -> stat(db, out)
                "trial" -> trial(db, n, out)
                "rename" -> rename(db, `val`, out)
                "clean" -> clean(db, n, out)
                "sql" -> runSql(db, sql, n, out)
                else -> say(
                    out,
                    "unknown sub=${sub.ifEmpty { "<empty>" }} (stat|trial|rename|clean|sql；" +
                        "trial/rename/clean 会真写主库)"
                )
            }
        } catch (e: Throwable) {
            // 兜底不能吞：写库探针最怕「什么都没说就结束了」
            say(out, "FATAL ${e.javaClass.simpleName}: ${msgOf(e)}")
        }
        return out
    }

    // ------------------------------------------------------------------ stat

    /** 只读统计：我方行 / 置顶区 / 微X 置顶行，用来观察微信有没有把置顶位重置掉。 */
    private fun stat(db: Any, out: MutableList<String>) {
        say(out, "stat db=${pathOf(db)}")

        val convTotal = countOf(db, "select count(*) from $CONV")
        val convOwn = countOf(db, "select count(*) from $CONV where $OWN_WHERE")
        val ctTotal = countOf(db, "select count(*) from $CONTACT")
        val ctOwn = countOf(db, "select count(*) from $CONTACT where $OWN_WHERE")
        say(out, "stat conv total=$convTotal own=$convOwn other=${diff(convTotal, convOwn)}")
        say(out, "stat contact total=$ctTotal own=$ctOwn other=${diff(ctTotal, ctOwn)}")

        for (r in rowsText(
                db,
                "select username,flag,conversationTime,length(content) as clen from $CONV " +
                    "where $OWN_WHERE order by username",
                60
            )
        ) say(out, "  conv $r")
        for (r in rowsText(
                db,
                "select username,type,showHead,length(nickname) as nlen,nickname from $CONTACT " +
                    "where $OWN_WHERE order by username",
                60
            )
        ) say(out, "  contact $r")

        val pin = countOf(db, "select count(*) from $CONV where flag > $PIN_BASE")
        val pinWx = countOf(
            db, "select count(*) from $CONV where flag > $PIN_BASE and substr(username,1,6)='wx__c_'"
        )
        val pinOurs = countOf(db, "select count(*) from $CONV where flag > $PIN_BASE and $OWN_WHERE")
        say(out, "stat pinned(flag>$PIN_BASE) total=$pin wxC=$pinWx ours=$pinOurs")
    }

    // ------------------------------------------------------------------ trial

    /**
     * 写 2 条测试行，回答「组名写 `rcontact.nickname` 能不能显示」：
     * `wxgrp_t1` 带昵称 `测试组A`（实验组）、`wxgrp_t2` 昵称为空（对照组）。
     * 两条的 `rconversation` 行完全一致，唯一差别就是 `rcontact.nickname` —— 这样列表上
     * 显示「测试组A」还是 wxid/空串，结论就没有第二种解释。
     */
    private fun trial(db: Any, n: Int, out: MutableList<String>) {
        say(out, "trial db=${pathOf(db)}")

        // 第一步就把真实列名打出来：万一实测列与模板不一致，日志会直接暴露，不用再猜
        val convCols = columnsOf(db, CONV)
        val ctCols = columnsOf(db, CONTACT)
        say(out, "trial convCols n=${convCols.size} :: ${convCols.joinToString(",")}")
        say(
            out,
            "trial contactCols n=${ctCols.size} :: " +
                ctCols.joinToString(",") { it.first }
        )
        if (convCols.isEmpty() || ctCols.isEmpty()) {
            say(out, "trial 中止：pragma table_info 拿不到列（表名漂移？先用 sub=stat 看库）")
            return
        }

        val base = System.currentTimeMillis()
        val cases = listOf(
            "${PREFIX}t1" to "测试组A",
            "${PREFIX}t2" to "",
        )
        cases.forEachIndexed { i, (user, nick) ->
            val time = base + i
            // 先备份（若已存在同名行），再删我方旧行 —— 否则 username 唯一索引会让 insert 直接失败
            backupRows(db, CONV, user)
            backupRows(db, CONTACT, user)
            execTry(db, "delete from $CONV where username=${q(user)}")
                ?.let { say(out, "trial $user 清旧 conv 行失败: $it") }
            execTry(db, "delete from $CONTACT where username=${q(user)}")
                ?.let { say(out, "trial $user 清旧 contact 行失败: $it") }

            val convOk = insertConv(db, user, time, out)
            val ctOk = insertContact(db, user, nick, ctCols, out)
            say(
                out,
                "trial $user nickname=[$nick] ctime=$time conv=${if (convOk) "OK" else "FAIL"} " +
                    "contact=${if (ctOk) "OK" else "FAIL"}"
            )
        }

        // 回读一遍实际落库值：写成功 ≠ 写对了（列错位、默认值覆盖都能在这里看出来）
        say(out, "trial 回读（下面是库里真实存的值）")
        for (r in rowsText(db, "select * from $CONV where $OWN_WHERE order by username", n)) {
            say(out, "  conv $r")
        }
        for (r in rowsText(db, "select * from $CONTACT where $OWN_WHERE order by username", n)) {
            say(out, "  contact $r")
        }
        val convOwn = countOf(db, "select count(*) from $CONV where $OWN_WHERE")
        val ctOwn = countOf(db, "select count(*) from $CONTACT where $OWN_WHERE")
        say(
            out,
            "trial done convOwn=$convOwn contactOwn=$ctOwn " +
                "otherConv=${diff(countOf(db, "select count(*) from $CONV"), convOwn)} " +
                "（下一步：回微信看会话列表，再 sub=stat 看 flag 有没有被微信改写）"
        )
    }

    /**
     * `rconversation` 两段式 insert：先 7 列靠默认值，失败再显式写全部列。
     *
     * 为什么是这个顺序：微信自己写行的形状最接近最小列集，兼容性最好；显式全列是兜底，
     * 一旦它也失败，报错信息（NOT NULL / no such column / constraint）就是本次取证的产出。
     */
    private fun insertConv(db: Any, user: String, time: Long, out: MutableList<String>): Boolean {
        val flag = PIN_BASE + time
        val minimal = "insert into $CONV(username,flag,conversationTime,msgType,status,chatmode,isSend) " +
            "values(${q(user)},$flag,$time,1,3,1,1)"
        execTry(db, minimal)?.let { err ->
            say(out, "insert conv $user stage=minimal 失败: $err")
            val vals = mapOf(
                "msgCount" to "0", "username" to q(user), "unReadCount" to "0",
                "chatmode" to "1", "status" to "3", "isSend" to "1",
                "conversationTime" to time.toString(), "content" to "''", "msgType" to "1",
                "customNotify" to "''", "showTips" to "0", "flag" to flag.toString(),
                "digest" to "''", "digestUser" to "''", "hasTrunc" to "0",
                "parentRef" to "''", "attrflag" to "0", "editingMsg" to "''",
                "atCount" to "0", "sightTime" to "0",
            )
            val full = "insert into $CONV(${CONV_ALL_COLS.joinToString(",")}) values(" +
                CONV_ALL_COLS.joinToString(",") { vals[it] ?: "''" } + ")"
            execTry(db, full)?.let { err2 ->
                say(out, "insert conv $user stage=full 也失败: $err2")
                return false
            }
            say(out, "insert conv $user OK stage=full")
            return true
        }
        say(out, "insert conv $user OK stage=minimal")
        return true
    }

    /**
     * `rcontact` 两段式 insert。列清单来自运行时 pragma（这张表的列比会话表多得多，
     * 硬编码一份必然随版本漂移），值取真机 dump 到的那套「t2 形状」：除
     * `username/type/showHead/nickname` 外全空或 0。
     */
    private fun insertContact(
        db: Any,
        user: String,
        nick: String,
        cols: List<Pair<String, String>>,
        out: MutableList<String>,
    ): Boolean {
        val names = cols.map { it.first }
        val lit = linkedMapOf(
            "username" to q(user),
            "type" to "0",
            "showHead" to "87",
            "nickname" to q(nick),
        )
        // 文本列：空串；数值列：0。写空串而不是省略，是为了让「显式全列」这一档真的是全列
        for (c in listOf(
            "conRemark", "alias", "pyInitial", "quanPin", "conRemarkPYFull",
            "conRemarkPYShort", "encryptUsername",
        )) lit[c] = "''"
        for (c in listOf(
            "createTime", "deleteFlag", "chatroomFlag", "weiboFlag",
            "verifyFlag", "usernameFlag", "uiType",
        )) lit[c] = "0"

        // ⚠️ nickname 必须进 minimal：t1/t2 的唯一自变量就是它。
        // 少了这一列，一旦 minimal 成功，两条对照行会变成一模一样 ⇒ trial 零判别力。
        val minimalCols = listOf("username", "type", "showHead", "nickname").filter { it in names }
        val minimal = "insert into $CONTACT(${minimalCols.joinToString(",")}) values(" +
            minimalCols.joinToString(",") { lit[it] ?: "''" } + ")"
        execTry(db, minimal)?.let { err ->
            say(out, "insert contact $user stage=minimal 失败: $err")
            // 显式全列：表里真实存在、且不是 BLOB 的列都带上（BLOB 省略，交默认值）
            val all = cols.filter { (n, t) ->
                n in names && !t.uppercase().contains("BLOB")
            }.map { it.first }
            val full = "insert into $CONTACT(${all.joinToString(",")}) values(" +
                all.joinToString(",") { c -> lit[c] ?: defaultLiteral(cols.first { it.first == c }.second) } +
                ")"
            execTry(db, full)?.let { err2 ->
                say(out, "insert contact $user stage=full 也失败: $err2")
                return false
            }
            say(out, "insert contact $user OK stage=full")
            return true
        }
        say(out, "insert contact $user OK stage=minimal")
        return true
    }

    // ------------------------------------------------------------------ rename

    /**
     * 只改 `rcontact.nickname`，用来判断「改名要不要重启微信才生效」：
     * 改完不重启、回列表看，名字变了 → 走的是实时读库；没变 → 有内存缓存，得重启或触发生效。
     */
    private fun rename(db: Any, value: String?, out: MutableList<String>) {
        val v = value?.trim()
        if (v.isNullOrEmpty()) {
            say(out, "rename: --es val <新名字> 不能为空")
            return
        }
        val user = "$PREFIX" + "t1"
        // 写库前必须备份：单独跑一次 rename 也要能把旧名字还原回来（复核 P1）
        backupRows(db, CONTACT, user)
        execTry(db, "update $CONTACT set nickname=${q(v)} where username=${q(user)}")?.let {
            say(out, "rename $user 失败: $it")
            return
        }
        say(out, "rename $user nickname=[$v] OK（t2 保持空，作对照）")
        for (r in rowsText(
                db,
                "select username,nickname,length(nickname) as nlen from $CONTACT where $OWN_WHERE " +
                    "order by username",
                10
            )
        ) say(out, "  contact $r")
    }

    // ------------------------------------------------------------------ clean

    /**
     * 删掉所有 `wxgrp_` 行（两张表）。删前备份，删后**必须**是 0 行；
     * 同时打「非本方前缀行数」，前后一致才说明没碰到别人的数据。
     */
    private fun clean(db: Any, n: Int, out: MutableList<String>) {
        val convOtherBefore = countOf(db, "select count(*) from $CONV where not ($OWN_WHERE)")
        val ctOtherBefore = countOf(db, "select count(*) from $CONTACT where not ($OWN_WHERE)")
        val convOwn = countOf(db, "select count(*) from $CONV where $OWN_WHERE")
        val ctOwn = countOf(db, "select count(*) from $CONTACT where $OWN_WHERE")
        say(out, "clean 待删 convOwn=$convOwn contactOwn=$ctOwn")
        for (r in rowsText(db, "select * from $CONV where $OWN_WHERE", n)) say(out, "  del conv $r")
        for (r in rowsText(db, "select * from $CONTACT where $OWN_WHERE", n)) say(out, "  del contact $r")

        backupRows(db, CONV, null)
        backupRows(db, CONTACT, null)

        execTry(db, "delete from $CONV where $OWN_WHERE")?.let { say(out, "clean conv 失败: $it") }
        execTry(db, "delete from $CONTACT where $OWN_WHERE")?.let { say(out, "clean contact 失败: $it") }

        val convLeft = countOf(db, "select count(*) from $CONV where $OWN_WHERE")
        val ctLeft = countOf(db, "select count(*) from $CONTACT where $OWN_WHERE")
        val convOtherAfter = countOf(db, "select count(*) from $CONV where not ($OWN_WHERE)")
        val ctOtherAfter = countOf(db, "select count(*) from $CONTACT where not ($OWN_WHERE)")
        say(
            out,
            "clean done convLeft=$convLeft contactLeft=$ctLeft " +
                "expect 0/0 ${if (convLeft == 0L && ctLeft == 0L) "PASS" else "FAIL"}"
        )
        say(
            out,
            "clean 非本方行 conv $convOtherBefore->$convOtherAfter " +
                "contact $ctOtherBefore->$ctOtherAfter " +
                if (convOtherBefore == convOtherAfter && ctOtherBefore == ctOtherAfter) "不变 PASS" else "**变了 FAIL**"
        )
    }

    // ------------------------------------------------------------------ sql

    /** 这条 SQL 是不是只读的（`sub=sql` 的唯一关卡，策略同 LabelHuntProbe）。 */
    private fun isReadOnly(sql: String): Boolean {
        val l = sql.lowercase()
        if (l.startsWith("select") || l.startsWith("explain")) {
            // load_extension 会在微信进程里加载任意 so
            if (l.contains("load_extension")) return false
            // 「rawQuery 只 prepare 首条语句」是 AOSP 的行为，wcdb 侧没验过 ⇒
            // 分号后还有内容一律拒，别赌实现（末尾单个分号合法）。
            return l.substringAfter(';', "").trim().isEmpty()
        }
        if (!l.startsWith("pragma")) return false
        val rest = l.removePrefix("pragma").trimStart()
        val verb = rest.takeWhile { it.isLetter() || it == '_' }
        if (verb !in READ_ONLY_PRAGMAS) return false
        // `page_size=4096` 这种赋值形式照样是写
        return !rest.contains("=")
    }

    /** 只读取证通道：留一个口子，后续取证不用每次重新装包。 */
    private fun runSql(db: Any, sql: String?, n: Int, out: MutableList<String>) {
        val s = sql?.trim()
        if (s.isNullOrEmpty()) {
            say(out, "sql: --es sql 不能为空")
            return
        }
        if (!isReadOnly(s)) {
            say(out, "sql refused: 只放行 select / explain / 只读 pragma")
            return
        }
        val rs = readAll(db, s, n)
        say(out, "sql db=${pathOf(db)} rows=${rs.second.size} cols=${rs.first.size}")
        for (r in rs.second.take(n)) {
            say(out, "  " + rs.first.indices.joinToString(" | ") { "${rs.first[it]}=${show(r[it])}" })
        }
    }

    // ------------------------------------------------------------------ 备份

    /**
     * 把 [user] 为 null 时的**全部我方行**、否则单条同名行，整行 dump 到
     * `filesDir/gconv_backup.txt`（append，带时间戳）。
     *
     * 存在意义只有一个：写脏了能人工还原。所以必须**在 delete/insert 之前**调用。
     */
    private fun backupRows(db: Any, table: String, user: String?) {
        val where = if (user == null) OWN_WHERE else "username=${q(user)}"
        val rs = readAll(db, "select * from $table where $where", 200)
        if (rs.second.isEmpty()) return
        val f = backupFile() ?: return
        val at = OWNERSHIP_STAMP.format(Date())
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText(
                buildString {
                    for (r in rs.second) {
                        append("# ").append(at).append(" before-write db=").append(pathOf(db))
                            .append(" table=").append(table).append('\n')
                        append(rs.first.indices.joinToString("\t") { "${rs.first[it]}=${r[it]}" })
                        append('\n')
                    }
                }
            )
        }
    }

    /** 备份文件：优先宿主 filesDir，退化到写死的宿主私有目录（模块就在宿主进程里，可写）。 */
    private fun backupFile(): File? {
        val dir = runCatching { HostContext.app()?.filesDir }.getOrNull()
            ?: File("/data/data/com.tencent.mm/files")
        return runCatching { File(dir, BACKUP_NAME) }.getOrNull()
    }

    // ------------------------------------------------------------------ 反射底层

    /**
     * 反射拿 `rawQuery(String, [])` 并查询。
     *
     * ⚠️ 不用 `findMethodExact`：wcdb 有 `rawQuery(String,String[])` 与 `rawQuery(String,Object[])`
     * 两个重载，精确匹配会静默找不到。⚠️ 不缓存 Method（跨库缓存必崩，见类注释）。
     */
    private fun query(db: Any, sql: String): Any? {
        val m = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "rawQuery" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    (it.parameterTypes[1] == Array<String>::class.java ||
                        it.parameterTypes[1] == Array<Any>::class.java)
            }
        }.getOrNull() ?: return null
        return runCatching { m.invoke(db, sql, null) }.getOrNull()
    }

    /** 反射调 `execSQL(String)`；找不到单参重载时退回 XposedHelpers。 */
    private fun exec(db: Any, sql: String) {
        val m = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "execSQL" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java
            }
        }.getOrNull()
        if (m != null) {
            m.invoke(db, sql)
            return
        }
        XposedHelpers.callMethod(db, "execSQL", sql)
    }

    /** 执行一条写语句；成功返回 null，失败返回 `异常类: message`（**原样，不吞**）。 */
    private fun execTry(db: Any, sql: String): String? {
        val e = runCatching { exec(db, sql) }.exceptionOrNull() ?: return null
        return "${e.javaClass.simpleName}: ${msgOf(e)}"
    }

    /** 拆掉 InvocationTargetException 这层壳 —— 真正的原因（SQLiteException）在 cause 里。 */
    private fun msgOf(t: Throwable): String {
        val c = (t as? InvocationTargetException)?.targetException ?: t.cause ?: t
        val m = c.message?.replace('\n', ' ')?.replace('\r', ' ')
        return if (m.isNullOrBlank()) c.javaClass.name else m
    }

    // ------------------------------------------------------------------ 读取

    /** 返回 `表头 + 全部行（每格一个字符串）`，行数上限 [max]。 */
    private fun readAll(
        db: Any,
        sql: String,
        max: Int,
    ): Pair<List<String>, List<List<String>>> {
        val cur = query(db, sql) ?: return emptyList<String>() to emptyList()
        return runCatching {
            val cnt = XposedHelpers.callMethod(cur, "getColumnCount") as? Int ?: 0
            val head = (0 until cnt).map {
                XposedHelpers.callMethod(cur, "getColumnName", it) as? String ?: "c$it"
            }
            val rows = ArrayList<List<String>>()
            while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true && rows.size < max) {
                rows.add(
                    (0 until cnt).map { i ->
                        runCatching { XposedHelpers.callMethod(cur, "getString", i) as? String }
                            .getOrNull() ?: ""
                    }
                )
            }
            head to rows
        }.also { close(cur) }.getOrDefault(emptyList<String>() to emptyList())
    }

    /**
     * 一行拼成 `k=v | k=v`。BLOB 列被 `getString` 读成 null，这里统一显示 `<null>` ——
     * 本次取证关心的是会话表/联系人表的标量列，不去展开二进制。
     */
    private fun rowsText(db: Any, sql: String, max: Int): List<String> {
        val rs = readAll(db, sql, max.coerceIn(1, 200))
        return rs.second.map { r ->
            rs.first.indices.joinToString(" | ") { "${rs.first[it]}=${show(r[it])}" }
        }
    }

    /** 单值（count(*) 之类）。 */
    private fun countOf(db: Any, sql: String): Long {
        val rs = readAll(db, sql, 1)
        return rs.second.firstOrNull()?.firstOrNull()?.trim()?.toLongOrNull() ?: -1L
    }

    /** `pragma table_info` → `(列名, 声明类型)`，顺序即建表顺序。 */
    private fun columnsOf(db: Any, table: String): List<Pair<String, String>> {
        val rs = readAll(db, "pragma table_info($table)", 200)
        return rs.second.mapNotNull { r ->
            val name = r.getOrNull(1)?.trim()
            if (name.isNullOrEmpty()) null else name to (r.getOrNull(2) ?: "")
        }
    }

    /** 按声明类型给一个「无意义但合法」的默认字面量（全列 insert 兜底用）。 */
    private fun defaultLiteral(declaredType: String): String {
        val t = declaredType.uppercase()
        return when {
            t.contains("INT") || t.contains("REAL") || t.contains("NUM") ||
                t.contains("BOOL") || t.contains("DOUB") || t.contains("FLOA") -> "0"
            else -> "''"
        }
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * SQL 字符串字面量转义。
     *
     * username 是我方常量，但 `rename` 的 nickname 来自 `--es val` ——
     * `O'Brien` 这种输入不转义会直接把 SQL 拆断（`'` → `''` 是 SQLite 唯一的转义方式）。
     */
    private fun q(s: String): String = "'" + s.replace("'", "''") + "'"

    private fun close(cur: Any) {
        runCatching { XposedHelpers.callMethod(cur, "close") }
    }

    private fun pathOf(db: Any): String =
        runCatching { XposedHelpers.callMethod(db, "getPath") as? String ?: "-" }.getOrDefault("?")

    /** 值太长时砍头留尾，保证在 logcat 单行里看得见（nickname/content 可能是长串）。 */
    private fun show(v: String?): String {
        val s = v ?: "<null>"
        return if (s.length <= 120) s else s.take(90) + "…(len=${s.length})…" + s.takeLast(20)
    }

    private fun diff(total: Long, part: Long): Long =
        if (total < 0 || part < 0) -1L else total - part

    private fun say(out: MutableList<String>, msg: String) {
        // 一行动一个事实：前缀只在这里加一次，EntryReceiver 原样 LogX.i
        out.add("$TAG $msg")
    }
}
