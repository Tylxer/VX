package com.lx.wxgroup.feature

import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.Group
import com.lx.wxgroup.data.GroupConvPlan
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 分组条「数据层伪会话」的**写库层**：把 [com.lx.wxgroup.data.GroupDb] 的分组定义同步成
 * 微信主库 `rconversation` / `rcontact` 两表的 `wxgrp_` 行，由微信原生渲染分组行（R54 §10 定案）。
 *
 * 只负责「读现状 → 交给 [GroupConvPlan] 算差异 → 执行 SQL」，不碰视图、不碰刷新链路：
 * 纯写库后列表不刷新是已证实事实，冷启 / 调用方的 onDataChanged 才让它可见。
 *
 * 对外 [postSync] / [postClean] / [postStat] 把任务**串行投递到单一后台线程**
 * （线程名 `gconv-store`），调用方只 post、不等待、也不自己切线程；[sync] / [clean] /
 * [stat] 仍是同步实现，由该后台线程在内部直接调用。写库段包在 wcdb 事务里：单线程
 * 串行保证我方不与宿主并发，事务保证 `rconversation` / `rcontact` 两表写不留半行。
 * 本类不常驻轮询、不设定时器。
 *
 * # 为什么写库只能反射、且不能缓存 Method
 * [AntiRevoke.wxDb] 给的是 wcdb 的 `com.tencent.wcdb.database.SQLiteDatabase`（super 是
 * `SQLiteClosable`），**不是** AOSP 的 `android.database.sqlite.SQLiteDatabase`，强转恒 null
 * 会静默白跑一轮。`rawQuery` 又有 `(String,String[])` / `(String,Object[])` 两个重载，
 * `findMethodExact` 会静默找不到；主库(wcdb) 与模块库(AOSP) 是两套实现，跨库缓存的
 * `Method` 调另一个实例会抛 `IllegalArgumentException`。⇒ 一律 `Any` + 每次重扫方法表。
 *
 * # 为什么读路径失败必须打日志并抛出（不静默返回空 Map）
 * 探针的 `readAll` 吞异常，列名缺失只表现为 `rows=0 cols=0`。本层若照抄，「读失败」与
 * 「真的 0 行」都会让 plan 得出「全部该 Insert / 什么都没变」的**假结论**。故本层任一
 * 查询失败先打 `FAIL` 再抛，由 [sync] / [clean] / [stat] 的 `runCatching` 兜住原样返回，
 * 绝不把异常吞成空集合。
 *
 * # 安全边界
 * 所有 DELETE / UPDATE 只作用于 `wxgrp_` 前缀（条件来自 [GroupConvPlan.ownWhere] 或精确
 * `username='wxgrp_...'`）；SQL 里不出现裸 `>` / `&`（远端 sh 会当重定向/后台且不报错），
 * 不用 `like 'wxgrp_%'`（`_` 是单字符通配）。每行都带 `GCONVSTORE ` 前缀返回给调用方
 * 统一打日志，一行动一个事实。
 */
object GroupConvStore {

    private const val TAG = "GCONVSTORE"
    private const val CONV = "rconversation"
    private const val CONTACT = "rcontact"
    private const val BACKUP_NAME = "gconv_backup.txt"
    private val OWNERSHIP_STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // ------------------------------------------------------------------ 串行写库 + 节流

    /** 单一后台线程：sync/clean/stat 全部串行投递到这里，调用方只 post、不等待。 */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gconv-store").apply { isDaemon = true }
    }

    /**
     * 上次接受自动 sync 的时刻（[android.os.SystemClock.elapsedRealtime]）。
     * 初值 [Long.MIN_VALUE] 表示「从未同步」，保证首次必触发 —— 不能用 0L：开机 <60s
     * 时 `elapsedRealtime()-0` 小于窗口会把首次吞掉（复核 C）。判节流时把 MIN_VALUE
     * 当哨兵短路，避免 `now - MIN_VALUE` 的 Long 溢出把首次又吞掉。
     */
    @Volatile
    private var lastSyncMs = Long.MIN_VALUE

    private const val SYNC_THROTTLE_MS = 60_000L

    // ------------------------------------------------------------------ 对外 API

    /**
     * 投递一次自动同步（默认受 [SYNC_THROTTLE_MS] 节流）。
     *
     * 调用方只 post、不等待、不切线程；读现状 / 算计划 / 写库都在 [worker] 线程内串行
     * 执行，主线程不再有任何全表读或写库。`force = true` 绕过节流（手动 `op=gcs
     * sub=sync` 用，验收 SOP 依赖它立即灌库）。
     *
     * **接受任务时即推进 [lastSyncMs]**（不等成功）：失败只打日志、不回滚时间戳，否则
     * 250ms 去抖会在窗口内忙循环反复重试。⚠️ 写库一旦出现 `FAIL` 产生的半行**不会被
     * 下一次 sync 自愈**（[readExisting] 取两表并集、[GroupConvPlan.plan] 对已存在
     * username 只产 Update，repairSql/renameSql 都是 UPDATE、补不回缺失行），须 `op=gcs
     * sub=clean` 全删重建；事务回滚用于**阻止半行产生**（见 [inTransaction]）。
     */
    fun postSync(force: Boolean = false) {
        worker.execute {
            runCatching {
                val now = android.os.SystemClock.elapsedRealtime()
                // Long.MIN_VALUE 是「从未同步」哨兵，必须先短路：直接算 now - MIN_VALUE
                // 会因 Long 减法溢出成负数，反而把首次吞掉。
                val due = force || lastSyncMs == Long.MIN_VALUE ||
                    (now - lastSyncMs >= SYNC_THROTTLE_MS)
                if (due) {
                    lastSyncMs = now
                    val db = GroupDb.current()
                    val groups = db?.listGroups().orEmpty()
                    val lines = sync(groups) { id -> db?.memberWxids(id) ?: emptySet() }
                    for (l in lines) LogX.i(l)
                }
            }.onFailure {
                LogX.w("GCONVSTORE postSync fail: ${it.javaClass.simpleName}: ${msgOf(it)}")
            }
        }
    }

    /** 幂等清理，post 到同一个 [worker] 串行执行；不判节流、不判开关（回滚清理随时能跑）。 */
    fun postClean() {
        worker.execute {
            runCatching {
                for (l in clean()) LogX.i(l)
            }.onFailure {
                LogX.w("GCONVSTORE postClean fail: ${it.javaClass.simpleName}: ${msgOf(it)}")
            }
        }
    }

    /** 只读对账，post 到同一个 [worker] 串行执行；不判节流、不判开关。 */
    fun postStat() {
        worker.execute {
            runCatching {
                for (l in stat()) LogX.i(l)
            }.onFailure {
                LogX.w("GCONVSTORE postStat fail: ${it.javaClass.simpleName}: ${msgOf(it)}")
            }
        }
    }

    /**
     * 全量对账：读现状 → [GroupConvPlan.plan] → 执行，返回逐行日志（一行动一个事实）。
     *
     * 组内最新消息时间 `timeMs` 只能**全表读 3120 行进 Map 再按成员集合算**：[Group] 不带
     * 成员字段、成员增删不改 `groups` 快照，没法靠 hashCode 触发；一次 `rawQuery` 全读比对
     * 每个成员各查一次快得多。Map 里排除 `wxgrp_` / `wx__c_` 前缀，避免成员 wxid 撞上伪
     * 会话 username 时把 time 顶住不动（R54 §6.3）。
     *
     * # 空组 fallback 用 createdAt 而不是 0L（默认置顶）
     * 空组 / 成员都无会话时 `maxOrNull()` 为 null，旧 fallback `0L` 会让
     * `Desired.timeMs=0`、`flag=flagOf(0)=PIN_BASE`（整整 2^62）。这个 flag 虽在置顶区，
     * 但 `conversationTime=0` 让它在置顶区内部按时间降序排到最末，真机实测落进「折叠置顶
     * 聊天」区、肉眼看不见（R68 探针：新行 wxgrp_009e 排在微X 10 条 conversationTime>0
     * 的置顶行之后）。改用 [Group.createdAt]（建组毫秒时间戳，与 rconversation.
     * conversationTime 同量纲）：flag=PIN_BASE+createdAt > PIN_BASE，且 createdAt(~1.79e12)
     * 远大于微X 的 conversationTime=1，故空组排进置顶区前部，实现「新建分组默认置顶」。
     * 有成员的组仍取 max(成员会话时间)，行为逐字不变。createdAt=0 的脏数据退化回旧行为、
     * 不崩（且备份恢复走 createGroup 写当前时间，正常不会有 0）。一处改即覆盖 b1 execSQL
     * 与 b2 原生插入两条路径（两者都消费 [GroupConvPlan.Desired.timeMs]）。fallback 规则
     * 抽成纯函数 [GroupConvPlan.effectiveTimeMs] 供 JVM 单测直接钉死（见 GroupConvPlanTest）。
     */
    fun sync(groups: List<Group>, membersOf: (Long) -> Set<String>): List<String> {
        val out = ArrayList<String>()
        val db = AntiRevoke.wxDb() ?: return listOf("$TAG no db")
        return runCatching {
            say(out, "sync start db=${pathOf(db)} groups=${groups.size}")
            val times = readConvTimes(db, out)
            val desired = groups.map { g ->
                // 空组/成员无会话 ⇒ 用建组时间当 conversationTime，使 flag=PIN_BASE+createdAt
                // > PIN_BASE 排进置顶区前部（默认置顶），而非 time=0 掉进「折叠置顶聊天」。
                // effectiveTimeMs 是纯函数，单测直接钉死 fallback=createdAt（见 R68 变异验证）。
                val memberTimes = membersOf(g.id).mapNotNull { times[it] }
                val t = GroupConvPlan.effectiveTimeMs(memberTimes, g.createdAt)
                GroupConvPlan.Desired(g.id, g.name, t, g.sortOrder)
            }
            val existing = readExisting(db, out)
            val ops = GroupConvPlan.plan(desired, existing)
            say(out, "sync plan desired=${desired.size} existing=${existing.size} ops=${ops.size}")
            // b2：开关开着时 rconversation 改走宿主 s4.I（事务外发通知），事务内只写 rcontact + 预删除。
            val nativeInsert = NativeConvInsert.isEnabled()
            val deferredNative = ArrayList<GroupConvPlan.Desired>()
            // R70 A′：删组补发宿主删除通知（s4.b(5,s4,user)）让分组行即时消失。与 b2 同款
            // 分段：开关读一次 + 事务内收集成功删行的 username，事务提交后（事务外、同 worker）补发。
            val deleteNotify = HostConvDelete.isEnabled()
            val deferredNotify = ArrayList<String>()
            inTransaction(db, out) { failures ->
                for (op in ops) {
                    when (op) {
                        is GroupConvPlan.Op.Delete -> {
                            val u = op.username
                            val convFail = execLog(db, GroupConvPlan.deleteConvSql(u), "deleteConv", u, out)
                            convFail?.let { failures += it }
                            val contactFail = execLog(db, GroupConvPlan.deleteContactSql(u), "deleteContact", u, out)
                            contactFail?.let { failures += it }
                            // R70 A′：只在两表删行都成功时收集 u，供事务外补发通知。
                            // 判据：execLog 返回 Throwable? —— null = 成功，非 null = 失败（已计入 failures 触发回滚）。
                            // 删行失败不做通知，避免宿主重查后旧行还在、状态漂移。
                            if (deleteNotify && convFail == null && contactFail == null) deferredNotify += u
                        }
                        is GroupConvPlan.Op.Update -> {
                            val u = GroupConvPlan.usernameOf(op.d.groupId)
                            execLog(db, GroupConvPlan.repairSql(op.d), "repair", "$u reason=${op.reason}", out)?.let { failures += it }
                            execLog(db, GroupConvPlan.renameSql(op.d), "rename", u, out)?.let { failures += it }
                        }
                        is GroupConvPlan.Op.Insert -> {
                            val u = GroupConvPlan.usernameOf(op.d.groupId)
                            // username 有唯一索引（真机实测），同名旧行会让 insert 直接失败；
                            // 先删再插才幂等。复用带 ownWhere() 保护的 delete*Sql，绝不误删宿主行。
                            execLog(db, GroupConvPlan.deleteConvSql(u), "insertPreDelConv", u, out)?.let { failures += it }
                            execLog(db, GroupConvPlan.deleteContactSql(u), "insertPreDelContact", u, out)?.let { failures += it }
                            if (nativeInsert) {
                                // b2：rcontact 标题来源先写（事务内），rconversation 行留到事务外走 s4.I
                                //（s4.I 会同步发 b(2) 通知，绝不能放进事务 —— 通知在提交前发出行为不确定，§4.3）。
                                execLog(db, GroupConvPlan.insertContactSql(op.d), "insertContact", u, out)?.let { failures += it }
                                deferredNative += op.d
                            } else {
                                // 现状路径逐字保持原样：execSQL 直写 rconversation（冷启才可见）。
                                execLog(db, GroupConvPlan.insertConvSql(op.d), "insertConv", u, out)?.let { failures += it }
                                execLog(db, GroupConvPlan.insertContactSql(op.d), "insertContact", u, out)?.let { failures += it }
                            }
                        }
                    }
                }
            }
            // b2 段二（事务外、同 worker 线程、紧随事务之后）：s4.I 写 rconversation + 自动发
            // b(2) 通知。宿主 CL 由 NativeConvInsert.install 存；失败降级回 execSQL 直写
            //（回到现状，冷启可见），不吞失败日志。
            if (nativeInsert && deferredNative.isNotEmpty()) {
                for (d in deferredNative) {
                    val u = GroupConvPlan.usernameOf(d.groupId)
                    val rowid = NativeConvInsert.insertConv(u, d.timeMs, d.groupId)
                    if (rowid == null) {
                        say(out, "nativeInsert $u FAIL -> degrade execSQL")
                        execLog(db, GroupConvPlan.insertConvSql(d), "insertConv", u, out)
                    } else {
                        say(out, "nativeInsert $u OK rowid=$rowid")
                    }
                }
            }
            // R70 A′ 段二（事务外、同 worker 线程、紧随事务之后）：对成功删掉的行补发宿主
            // 删除通知 s4.b(5,s4,user)，触发宿主 p3.f 重查让分组行即时消失（不冷启）。仿 b2
            // 分段纪律：通知必须在事务提交后发，不能在提交前；失败只告警不影响删行主流程。
            if (deleteNotify) {
                for (u in deferredNotify) {
                    HostConvDelete.notifyDeleted(u)
                    say(out, "deleteNotify $u sent")
                }
            }
            val where = GroupConvPlan.ownWhere()
            val own = countOf(db, "select count(*) from $CONV where $where", out, "sync countOwn")
            val other = countOf(db, "select count(*) from $CONV where not ($where)", out, "sync countOther")
            val ownCt = countOf(db, "select count(*) from $CONTACT where $where", out, "sync countOwnContact")
            val otherCt = countOf(db, "select count(*) from $CONTACT where not ($where)", out, "sync countOtherContact")
            say(out, "sync done own=$own other=$other")
            say(out, "sync done contactOwn=$ownCt contactOther=$otherCt")
            out
        }.getOrElse { e ->
            say(out, "FATAL ${e.javaClass.simpleName}: ${msgOf(e)}")
            out
        }
    }

    /** 幂等清理：删两表所有 `wxgrp_` 行，删前备份、删后自证非本方行数不变。 */
    fun clean(): List<String> {
        val out = ArrayList<String>()
        val db = AntiRevoke.wxDb() ?: return listOf("$TAG no db")
        return runCatching {
            val where = GroupConvPlan.ownWhere()
            val convOtherBefore = countOf(db, "select count(*) from $CONV where not ($where)", out, "clean otherConvBefore")
            val ctOtherBefore = countOf(db, "select count(*) from $CONTACT where not ($where)", out, "clean otherContactBefore")
            val convOwn = countOf(db, "select count(*) from $CONV where $where", out, "clean ownConv")
            val ctOwn = countOf(db, "select count(*) from $CONTACT where $where", out, "clean ownContact")
            say(out, "clean 待删 convOwn=$convOwn contactOwn=$ctOwn")

            backupRows(db, CONV, out)
            backupRows(db, CONTACT, out)

            inTransaction(db, out) { failures ->
                execLog(db, "delete from $CONV where $where", "cleanDelConv", "*", out)?.let { failures += it }
                execLog(db, "delete from $CONTACT where $where", "cleanDelContact", "*", out)?.let { failures += it }
            }

            val convLeft = countOf(db, "select count(*) from $CONV where $where", out, "clean convLeft")
            val ctLeft = countOf(db, "select count(*) from $CONTACT where $where", out, "clean contactLeft")
            val convOtherAfter = countOf(db, "select count(*) from $CONV where not ($where)", out, "clean otherConvAfter")
            val ctOtherAfter = countOf(db, "select count(*) from $CONTACT where not ($where)", out, "clean otherContactAfter")
            say(out, "clean done convLeft=$convLeft contactLeft=$ctLeft expect 0/0 " +
                if (convLeft == 0L && ctLeft == 0L) "PASS" else "FAIL")
            val same = convOtherBefore == convOtherAfter && ctOtherBefore == ctOtherAfter
            say(out, "clean 非本方行 conv $convOtherBefore->$convOtherAfter " +
                "contact $ctOtherBefore->$ctOtherAfter " +
                if (same) "不变 PASS" else "**变了 FAIL**")
            out
        }.getOrElse { e ->
            say(out, "FATAL ${e.javaClass.simpleName}: ${msgOf(e)}")
            out
        }
    }

    /** 只读对账输出（不写库），供 `stat` 验收用。 */
    fun stat(): List<String> {
        val out = ArrayList<String>()
        val db = AntiRevoke.wxDb() ?: return listOf("$TAG no db")
        return runCatching {
            val where = GroupConvPlan.ownWhere()
            say(out, "stat db=${pathOf(db)}")
            val convTotal = countOf(db, "select count(*) from $CONV", out, "stat convTotal")
            val convOwn = countOf(db, "select count(*) from $CONV where $where", out, "stat convOwn")
            val ctTotal = countOf(db, "select count(*) from $CONTACT", out, "stat contactTotal")
            val ctOwn = countOf(db, "select count(*) from $CONTACT where $where", out, "stat contactOwn")
            say(out, "stat conv total=$convTotal own=$convOwn other=${diff(convTotal, convOwn)}")
            say(out, "stat contact total=$ctTotal own=$ctOwn other=${diff(ctTotal, ctOwn)}")
            val convRows = readAll(
                db,
                "select username,flag,conversationTime from $CONV where $where order by username",
                out, "stat ownConvRows"
            )
            for (r in convRows.second) {
                val line = r.joinToString(" | ")
                say(out, "  conv $line")
            }
            val ctRows = readAll(
                db,
                "select username,nickname from $CONTACT where $where order by username",
                out, "stat ownContactRows"
            )
            for (r in ctRows.second) {
                val line = r.joinToString(" | ")
                say(out, "  contact $line")
            }
            out
        }.getOrElse { e ->
            say(out, "FATAL ${e.javaClass.simpleName}: ${msgOf(e)}")
            out
        }
    }

    // ------------------------------------------------------------------ 读取现状

    /** 一次全读 `username,conversationTime` 进 Map；排除伪会话前缀，失败不静默。 */
    private fun readConvTimes(db: Any, out: MutableList<String>): Map<String, Long> {
        val rs = readAll(db, "select username,conversationTime from $CONV", out, "readConvTimes")
        val map = HashMap<String, Long>()
        for (r in rs.second) {
            val u = r.getOrNull(0) ?: continue
            // 按前缀排除，不用 isOurs：后者是「能反解出 groupId」的窄口径（GroupConvPlan.kt:37-42），
            // 会漏掉 wxgrp_t1 这类探测残留行。宽口径与 ownWhere() 一致，确保成员 wxid 撞上任一
            // 我方伪会话 username 时都不会把 time 顶住不动（R54 §6.3）。
            if (u.isEmpty() || u.startsWith(GroupConvPlan.PREFIX) || u.startsWith("wx__c_")) continue
            map[u] = r.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
        }
        say(out, "readConvTimes rows=${rs.second.size} kept=${map.size}")
        return map
    }

    /** 读两表我方行按 username 合并成 [GroupConvPlan.Existing]，缺的一侧补空。 */
    private fun readExisting(db: Any, out: MutableList<String>): List<GroupConvPlan.Existing> {
        val where = GroupConvPlan.ownWhere()
        val conv = readAll(db, "select username,flag,conversationTime from $CONV where $where", out, "readOwnConv")
        val ct = readAll(db, "select username,nickname from $CONTACT where $where", out, "readOwnContact")

        val convMap = HashMap<String, Pair<Long, Long>>()
        for (r in conv.second) {
            val u = r.getOrNull(0) ?: continue
            if (u.isEmpty()) continue
            convMap[u] = (r.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L) to
                (r.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L)
        }
        val ctMap = HashMap<String, String>()
        for (r in ct.second) {
            val u = r.getOrNull(0) ?: continue
            if (u.isEmpty()) continue
            ctMap[u] = r.getOrNull(1) ?: ""
        }

        val users = LinkedHashSet(convMap.keys)
        users.addAll(ctMap.keys)
        val merged = ArrayList<GroupConvPlan.Existing>(users.size)
        for (u in users) {
            val (flag, ctime) = convMap[u] ?: (0L to 0L)
            merged.add(GroupConvPlan.Existing(u, flag, ctime, ctMap[u] ?: ""))
        }
        say(out, "readExisting conv=${conv.second.size} contact=${ct.second.size} merged=${merged.size}")
        return merged
    }

    // ------------------------------------------------------------------ 备份

    /** 删前把我方整行 append 到 `filesDir/gconv_backup.txt`（带时间戳），写脏了能人工还原。 */
    private fun backupRows(db: Any, table: String, out: MutableList<String>) {
        val where = GroupConvPlan.ownWhere()
        val rs = runCatching { readAll(db, "select * from $table where $where", out, "backup $table") }
            .onFailure { say(out, "backup $table 读取失败 ${it.javaClass.simpleName}: ${msgOf(it)}（继续删除）") }
            .getOrNull() ?: return
        if (rs.second.isEmpty()) return
        val f = backupFile() ?: return
        val at = OWNERSHIP_STAMP.format(Date())
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText(buildString {
                for (r in rs.second) {
                    append("# ").append(at).append(" before-write db=").append(pathOf(db))
                        .append(" table=").append(table).append('\n')
                    append(rs.first.indices.joinToString("\t") { "${rs.first[it]}=${r[it]}" })
                    append('\n')
                }
            })
        }.onFailure { say(out, "backup $table 写文件失败 ${it.javaClass.simpleName}: ${msgOf(it)}") }
    }

    private fun backupFile(): File? {
        val dir = runCatching { HostContext.app()?.filesDir }.getOrNull()
            ?: File("/data/data/com.tencent.mm/files")
        return runCatching { File(dir, BACKUP_NAME) }.getOrNull()
    }

    // ------------------------------------------------------------------ 反射底层

    /**
     * 反射拿 `rawQuery(String, [])` 并查询。不缓存 Method：wcdb 两个重载会让
     * findMethodExact 静默找不到，跨库缓存调另一实例必抛 IllegalArgumentException。
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

    /** 反射调 `execSQL(String)`；找不到单参重载退回 XposedHelpers。 */
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

    /**
     * 反射找 wcdb 事务方法（一律 [Any] + 每次重扫方法表，⛔ 绝不缓存 Method —— 跨库缓存
     * 的 Method 调另一个实例会抛 IllegalArgumentException，见类 KDoc）。
     */
    private fun txMethod(db: Any, name: String): java.lang.reflect.Method? =
        runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            }
        }.getOrNull()

    /**
     * 把 `rconversation` + `rcontact` 两表写包进 wcdb 事务：begin → block →
     * setTransactionSuccessful →（finally）endTransaction。block 抛异常时不标记成功，
     * finally 仍 endTransaction ⇒ 整段回滚，不留半行。
     *
     * 反射拿不到这三个方法时**打 WARN 并降级为无事务直写**（绝不静默跳过）：单一后台
     * 线程串行仍保证我方不与宿主并发，只是少了失败回滚。
     */
    private fun inTransaction(db: Any, out: MutableList<String>, block: (MutableList<Throwable>) -> Unit) {
        val begin = txMethod(db, "beginTransaction")
        val success = txMethod(db, "setTransactionSuccessful")
        val end = txMethod(db, "endTransaction")
        // block 内每条 SQL 的失败都收集到这里；非空即整段回滚，绝不标记成功（半行不自愈）。
        val failures = ArrayList<Throwable>()
        if (begin == null || success == null || end == null) {
            say(out, "WARN 事务方法(begin/setTransactionSuccessful/end)反射失败，" +
                "降级为无事务直写（单线程串行仍保证我方无并发）")
            block(failures)
            if (failures.isNotEmpty()) {
                say(out, "WARN 无事务降级下 ${failures.size} 条 SQL 失败，半行可能残留，需 op=gcs sub=clean 全删重建")
                throw failures.first()
            }
            return
        }
        var begun = false
        try {
            begin.invoke(db)
            begun = true
            block(failures)
            if (failures.isNotEmpty()) {
                say(out, "FATAL 事务内 ${failures.size} 条 SQL 失败，整段回滚（跳过 setTransactionSuccessful）")
                throw failures.first()
            }
            success.invoke(db)
        } finally {
            // endTransaction 必须调到：成功则提交，block 抛异常则回滚。
            if (begun) {
                runCatching { end.invoke(db) }
                    .onFailure { say(out, "WARN endTransaction 失败 " +
                        "${it.javaClass.simpleName}: ${msgOf(it)}") }
            }
        }
    }

    /**
     * 每条 SQL 执行结果都打一行：成功 `op=... user=...`，失败带解包后的异常原文（不吞）。
     * 返回失败异常（成功为 null），供 [inTransaction] 收集后整段回滚 —— 半行不自愈。
     */
    private fun execLog(db: Any, sql: String, op: String, user: String, out: MutableList<String>): Throwable? {
        val e = runCatching { exec(db, sql) }.exceptionOrNull()
        if (e == null) {
            say(out, "op=$op user=$user OK")
            return null
        } else {
            say(out, "op=$op user=$user FAIL ${e.javaClass.simpleName}: ${msgOf(e)}")
            return e
        }
    }

    /** 拆掉 InvocationTargetException 这层壳 —— 真正的原因（SQLiteException）在 cause 里。 */
    private fun msgOf(t: Throwable): String {
        val c = (t as? InvocationTargetException)?.targetException ?: t.cause ?: t
        val m = c.message?.replace('\n', ' ')?.replace('\r', ' ')
        return if (m.isNullOrBlank()) c.javaClass.name else m
    }

    /**
     * 返回 `表头 + 全部行`。读失败打 FAIL 并抛（不静默返回空 Map，否则 plan 会得出假结论），
     * 由调用方 runCatching 兜住原样返回；Cursor 无论成败都关。
     */
    private fun readAll(
        db: Any,
        sql: String,
        out: MutableList<String>,
        tag: String,
    ): Pair<List<String>, List<List<String>>> {
        val cur = query(db, sql)
        if (cur == null) {
            val why = "$tag FAIL query=null (rawQuery 反射失败或 SQL prepare 失败)"
            say(out, why)
            throw IllegalStateException(why)
        }
        return runCatching {
            val cnt = XposedHelpers.callMethod(cur, "getColumnCount") as? Int ?: 0
            val head = (0 until cnt).map {
                XposedHelpers.callMethod(cur, "getColumnName", it) as? String ?: "c$it"
            }
            val rows = ArrayList<List<String>>()
            while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                rows.add((0 until cnt).map { i ->
                    runCatching { XposedHelpers.callMethod(cur, "getString", i) as? String }
                        .getOrNull() ?: ""
                })
            }
            head to rows
        }.also { close(cur) }.getOrElse { e ->
            val why = "$tag FAIL ${e.javaClass.simpleName}: ${msgOf(e)}"
            say(out, why)
            throw IllegalStateException(why, e)
        }
    }

    /** 单值（count(*) 之类）。 */
    private fun countOf(db: Any, sql: String, out: MutableList<String>, tag: String): Long {
        val rs = readAll(db, sql, out, tag)
        return rs.second.firstOrNull()?.firstOrNull()?.trim()?.toLongOrNull() ?: -1L
    }

    // ------------------------------------------------------------------ 小工具

    private fun close(cur: Any) {
        runCatching { XposedHelpers.callMethod(cur, "close") }
    }

    private fun pathOf(db: Any): String =
        runCatching { XposedHelpers.callMethod(db, "getPath") as? String ?: "-" }.getOrDefault("?")

    private fun diff(total: Long, part: Long): Long =
        if (total < 0 || part < 0) -1L else total - part

    private fun say(out: MutableList<String>, msg: String) {
        out.add("$TAG $msg")
    }
}
