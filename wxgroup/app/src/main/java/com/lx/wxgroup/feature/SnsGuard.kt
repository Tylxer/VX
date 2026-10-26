package com.lx.wxgroup.feature

import android.content.ContentValues
import android.os.Handler
import android.os.HandlerThread
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

    /**
     * 两次「update 后补位」之间的最小间隔。
     *
     * 实测一轮首屏同步会连写多条 `sourceType=0`（4 条、间隔 10ms），而
     * [SnsTimelineSourceFix.reFixAfterClear] 扫的是整张台账 ⇒ 补一次就把这一轮
     * 全部覆盖，重复触发纯属浪费（每次 7 块 count + 7 块 UPDATE SQL）。
     */
    private const val UPDATE_FIX_QUIET_MS = 350L
    private const val UPDATE_FIX_MAX_WAIT_MS = 2000L

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
        // ✅ R27：微信清 `sourceType` 的真实通道是 `update("SnsInfo", {...sourceType=0})`，
        //   不是 `execSQL`。不挂这条，[onExecSqlAfter] 永远是空挂
        //   （详见 `docs/_r27_clear_api_forensics.md`）。
        runCatching { XposedBridge.hookAllMethods(cls, "updateWithOnConflict", snsUpdateGuard) }
            .onSuccess { if (it.isNotEmpty()) ok += "updateWithOnConflict" }
            .onFailure { LogX.e("SNS: hook updateWithOnConflict failed", it) }
        // ✅ R27 方案 A（Response-Merge）：联网时时间线吃响应对象、不读库，
        //   只补 DB 的 sourceType 不够，还得把本地行注入回 `c3.N` 的响应列表
        //   （见 `docs/_r27_clear_api_forensics.md` §11.4 的联网/断网对照实验）。
        runCatching { SnsTimelineMerge.install(lpparam) }
            .onFailure { LogX.e("SNS: merge install failed", it) }
        // ✅ R20：**句柄捕获钩子**（拿到即自我卸载，见 [installCapture]）。
        //   它不是常驻钩子，跟下面「常驻的两个」不是一回事，别按同一把尺子衡量。
        runCatching { installCapture(cls) }
            .onFailure { LogX.w("SNS: capture hook failed: ${it.javaClass.simpleName}") }
        // ⚠️ **常驻**的只有 delete / execSQL / updateWithOnConflict 三个。insert / query 一律不挂：
        // 那是微信所有表（message / rcontact …）的写路径，挂上去等于给每次写库都加一层开销，
        // 而我们要的信息（原 localFlag）用 `| VISIBLE_BIT` 兜底已经够了。
        //
        // ⛔ updateWithOnConflict 是 2026-10-26 R27 才加的**唯一例外**：真机取证证明
        //    清 `sourceType` 走的是它、而不是 execSQL（见 [snsUpdateGuard] 的三道闸）。
        //    不加这一条，方案 B 的补位永远等不到触发。
        //
        // ⛔ 唯一例外是上面的 [installCapture]（`rawQuery`）：需求 6.4 的导出要扫
        // **当前** SnsInfo（防撤回 = 在动态还看得见时留图，见 [listCurrentSns]），
        // 而句柄只在「删」的路径上才会被记下来 —— 定时刷新朋友圈只会 INSERT/REPLACE，
        // 永远触发不到 DELETE ⇒ 句柄恒为 null ⇒ 导出恒 0 行。
        // 所以必须补一条捕获路径，且**拿到就卸载**（把常驻开销压成 0）。
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

        /**
         * ⚠️ **必须挂在 after**：微信清 `sourceType` 的 bit1 是
         * `UPDATE SnsInfo SET sourceType = sourceType & -3 ...`，在 before 里补位会被
         * 同一条 SQL 立刻清掉（顺序反过来）。
         */
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching { onExecSqlAfter(param) }
                .onFailure { LogX.w("SNS execSQL after hook err: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * `execSQL` 执行**之后**：如果这条 SQL 是微信在「批量清时间线来源位」，
     * 立刻把台账里「曾经可见」的行补回去（方案 B，见 `docs/_r27_srcfix_design.md`）。
     *
     * 为什么不能省：真机实测清位每轮 sync 都跑一次（`updateFilterTimeLine`），
     * 一次性补位活不过一轮。补位必须跟在清位后面走。
     *
     * 代价：这条 SQL 只在朋友圈同步时出现，字符串匹配在 `& -3` 那步就短路返回，
     * 平时零成本（不做反射、不碰库）。
     */
    private fun onExecSqlAfter(param: XC_MethodHook.MethodHookParam) {
        val sql = param.args?.firstOrNull() as? String ?: return
        if (!SnsTimelineSrcLogic.isSourceClearSql(sql)) {
            // 微信换写法时这里会打出真实 SQL —— 否则补位静默失效，只能靠猜。
            // ⚠️ 必须排除我们自己补位/回滚写的 SQL，否则每次 sync 都被自己刷一条噪音。
            if (SnsTimelineSrcLogic.looksLikeSourceWrite(sql) &&
                !SnsTimelineSrcLogic.isOwnFixSql(sql)
            ) {
                SnsTimelineSourceFix.noteUnmatchedSourceWrite(sql)
            }
            return
        }
        if (bypass.get() == true) return
        val db = param.thisObject ?: return
        val t0 = android.os.SystemClock.elapsedRealtime()
        val fixed = SnsTimelineSourceFix.reFixAfterClear(db)
        val cost = android.os.SystemClock.elapsedRealtime() - t0
        // fixed=-1 = 上一轮还在跑（重入保护），不算异常，但要让排查的人看见
        LogX.i("SNS srcguard re-fix after clear fixed=$fixed cost=${cost}ms")
    }


    /**
     * 微信清时间线可见位（`sourceType` bit1）的**真实通道**。
     *
     * ⚠️ 2026-10-26 R27 真机取证（`docs/_r27_clear_api_forensics.md`）：微信落库是
     * 「服务器对象整行 upsert」，清位表现为
     * `update("SnsInfo", ContentValues{... sourceType=0 ...})` → `updateWithOnConflict`，
     * **每轮首屏同步都会跑**（实测 4 条、间隔 10ms），`execSQL` 一次都不出现。
     * ⇒ 原来挂在 `execSQL` 上的 [onExecSqlAfter] 一直是空挂，必须补这一条。
     *
     * 挂 `updateWithOnConflict` 而不是 `update`：后者内部必走前者，挂前者不会漏。
     *
     * 代价控制三闸：`updateWithOnConflict` 是**全表**热路径（微信所有表的 update 都经过），
     * ① 第一句就是零反射的字符串比较，非 `SnsInfo` 立刻返回；
     * ② 命中后看 values 里 `sourceType` 是否为 0（正常行写 2/10，只有清位写 0）；
     * ③ 命中后**不立刻补**，而是等这一批写完（静默 350ms）在 [FixWorker] 里统一补一次
     * —— 立刻补会被同批后面的写 0 覆盖（真机实测一轮清 3 条只补回 1 条）。
     */
    private val snsUpdateGuard = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching { onSnsUpdateAfter(param) }
                .onFailure { LogX.w("SNS update hook err: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * 最近一次「写 0」命中的时刻（`elapsedRealtime`）。
     * 补位不在这里立刻跑，而是等这一批写完（静默 [UPDATE_FIX_QUIET_MS]）后统一跑，
     * 否则先补的那条会被同批后续的写 0 覆盖（2026-10-26 00:18 真机实测：
     * 一轮写 3 条，只补回 1 条，另两条被节流挡掉后保持 0）。
     */
    private val lastUpdateZeroAt = java.util.concurrent.atomic.AtomicLong(0L)

    /** 是否已排了一个待执行的补位任务（合并同一批连写） */
    private val fixScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 补位专用单线程：`object` 的初始化是线程安全的，懒加载且全局唯一 */
    private object FixWorker {
        val handler: Handler = Handler(HandlerThread("sns-srcfix").apply { start() }.looper)
    }

    /**
     * 读 `ContentValues.sourceType`，返回 `null` 表示取不到。
     *
     * ⚠️ 必须走反射、不能 `as? android.content.ContentValues`：微信用的是 wcdb 自己
     * copy 的一份 `com.tencent.wcdb.ContentValues`（不是 AOSP 那个类的子类）。
     * 直接强转在真机上会恒为 null ⇒ 整个钩子静默失效，而日志上什么异常都没有。
     * Method 缓存按 class 存，避免每条 update 都 getMethod。
     */
    private fun sourceTypeOf(values: Any): Int? {
        val c = values.javaClass
        if (cvCls !== c) {
            cvGetInteger = runCatching { c.getMethod("getAsInteger", String::class.java) }.getOrNull()
            cvCls = c
        }
        val m = cvGetInteger ?: return null
        return runCatching { m.invoke(values, "sourceType") as? Int }.getOrNull()
    }

    @Volatile
    private var cvCls: Class<*>? = null

    @Volatile
    private var cvGetInteger: Method? = null

    /** 「取不到 sourceType」的告警只打一次，避免刷屏 */
    private val cvWarned = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun onSnsUpdateAfter(param: XC_MethodHook.MethodHookParam) {
        val a = param.args ?: return
        if (a.size < 2) return
        // ① 零反射短路：微信所有表的 update 都走这里，这句必须是第一个判断
        if ((a[0] as? String) != "SnsInfo") return
        // ② 只有「服务器认为这条不可见」才需要补；正常行微信写的是 2 或 10
        val values = a[1] ?: return
        val st = sourceTypeOf(values)
        if (st == null) {
            // ⛔ 绝不允许静默失效：取不到值说明 ContentValues 形态变了，
            // 补位会从此永久不触发，而功能表面上"正常"（只是不生效）。
            if (cvWarned.compareAndSet(false, true)) {
                LogX.w("SNS update hook: no sourceType on ${values.javaClass.name} (re-fix disabled)")
            }
            return
        }
        if (st != 0) return
        if (bypass.get() == true) return
        // ③ 不立刻补：只记时刻 + 排一个「静默后统一补」的任务。
        //    立刻补会被同一批里后面的写 0 覆盖（真机实测 fixed=1 而实际有 3 条被清）。
        lastUpdateZeroAt.set(SystemClock.elapsedRealtime())
        if (!fixScheduled.compareAndSet(false, true)) return
        val db = param.thisObject ?: run {
            fixScheduled.set(false)
            return
        }
        // ⛔ 排程失败必须回滚单航班标记：否则 fixScheduled 永久为 true，
        //    补位从此彻底静默失效（正是本轮要防的失效模式）。
        runCatching {
            FixWorker.handler.postDelayed({ runDeferredFix(db, 0L) }, UPDATE_FIX_QUIET_MS)
        }.onFailure {
            fixScheduled.set(false)
            LogX.w("SNS srcguard schedule failed: ${it.javaClass.simpleName}")
        }
    }

    /**
     * 等这一批写 0 静默 [UPDATE_FIX_QUIET_MS] 后，跑一次 [SnsTimelineSourceFix.reFixAfterClear]。
     *
     * 三层保护：
     * 1. **静默判定**：每 [UPDATE_FIX_QUIET_MS] 复查一次 `lastUpdateZeroAt`，还在写就继续等，
     *    最长等 [UPDATE_FIX_MAX_WAIT_MS]（防止有持续写入把补位无限推迟）。
     * 2. **补位后自检**：补位期间若又有写 0（`lastUpdateZeroAt` 晚于补位开始时刻），再排一轮。
     * 3. **单航班**：`fixScheduled` 保证同一时刻只有一个补位任务在跑/在排队。
     */
    private fun runDeferredFix(db: Any, waited: Long) {
        val quiet = SystemClock.elapsedRealtime() - lastUpdateZeroAt.get()
        if (quiet < UPDATE_FIX_QUIET_MS && waited < UPDATE_FIX_MAX_WAIT_MS) {
            FixWorker.handler.postDelayed(
                { runDeferredFix(db, waited + UPDATE_FIX_QUIET_MS) },
                UPDATE_FIX_QUIET_MS
            )
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        val fixed = runCatching { SnsTimelineSourceFix.reFixAfterClear(db) }
            .onFailure { LogX.e("SNS srcguard re-fix failed", it) }
            .getOrDefault(0)
        val cost = SystemClock.elapsedRealtime() - startedAt
        LogX.i("SNS srcguard re-fix after burst fixed=$fixed waited=${waited}ms cost=${cost}ms")
        fixScheduled.set(false)
        // 补位期间又发生了写 0 ⇒ 再补一轮（真机上一批可能跨 100ms 以上）
        if (lastUpdateZeroAt.get() > startedAt && fixScheduled.compareAndSet(false, true)) {
            FixWorker.handler.postDelayed({ runDeferredFix(db, 0L) }, UPDATE_FIX_QUIET_MS)
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
        // ✅ R20：顺手捕获**朋友圈库句柄**（理由见 [installCapture]）。
        //    ⛔ 必须放在 `id == null` 早退**之前**：定时刷新朋友圈是 INSERT/REPLACE，
        //    匹配不到删除 → 原来根本走不到 [isSnsDb]，句柄永远拿不到。
        //    代价只在「SQL 里含 SnsInfo」时才付（一次 getPath 反射），不是每次 execSQL。
        if (snsDb == null && sql?.contains(TABLE) == true) {
            val db0 = param.thisObject
            if (db0 != null && isSnsDb(db0)) {
                LogX.i("SNS sns db captured via execSQL (导出扫当前 SnsInfo 已可用)")
                unhookCapture()
            }
        }
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
     * @return 实际写进去的 localFlag；0 = 没写（已可见 / 方法没找到 / 写失败 / 没命中行）
     */
    private fun restoreVisibility(db: Any, snsId: Long, curFlag: Int): Int {
        if (curFlag and VISIBLE_BIT != 0) return 0 // 本来就可见，不用动
        val target = curFlag or VISIBLE_BIT
        // ⚠️ 只有真的改动到行才算数：行没了的时候 update 返回 0 行，
        // 以前这里照样返回 target，日志看上去是「补可见位成功」，其实一条都没改 ——
        // 「拦了删除但界面还是不显示」这种问题查起来全靠这条日志，不能说谎。
        val n = updateFlag(db, snsId, target)
        if (n <= 0) return 0
        LogX.i("SNS visibility restored snsId=$snsId $curFlag -> $target rows=$n")
        return target
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
     * bit4(16)：**只用于「拦下删除后把行补回可见」**（[restoreVisibility]），
     * ⛔ **不再用于查询筛选** —— 理由见 [`listCurrentSns`] 的 KDoc 与
     * `docs/_r26_bit16_rootcause.md`。
     *
     * 8.0.48 实测：新落库的动态是 `localFlag=2`、**不含** bit16，而它们在朋友圈界面里正常显示。
     * 旧结论「时间线白名单里每个值都置 bit4 ⇒ bit4 = 可见位」把「删除会清这一位」
     * 偷换成了「可见必须置这一位」，第一步从未在 8.0.48 上验证过。
     */
    private const val VISIBLE_BIT = 16

    // ------------------------------------------------------------------ 句柄捕获（R20）

    /**
     * 拿到句柄之前**唯一**多出来的钩子：`rawQuery`。
     *
     * ## 为什么必须有它
     * [snsDb] 原来只在「DELETE 命中 SnsInfo」时被记下来（[isSnsDb]）。而需求 6.4 的
     * 导出要扫**当前** SnsInfo（防撤回语义：动态还看得见的时候就把图留存下来，
     * 见 [listCurrentSns]），定时刷新朋友圈走的是 INSERT/REPLACE —— **不产生 DELETE**。
     * 于是句柄恒为 null，导出恒 0 行，且日志里只会看到「归档也没几条」，
     * 根本看不出真因是「句柄没拿到」。
     *
     * ## 为什么可以接受它的开销
     * - **拿到就卸载**（[unhookCapture]）：之后是零钩子，不是「常驻一层开销」；
     * - 卸载前每次调用只做「`snsDb != null` 判空 + `sql.contains("SnsInfo")`」，
     *   没有任何反射；
     * - [CAPTURE_MAX_CALLS] 兜底：迟迟碰不到 SnsInfo（用户从不打开朋友圈）也认输卸载，
     *   绝不做常驻钩子。
     */
    private val captureUnhooks =
        java.util.Collections.synchronizedList(ArrayList<XC_MethodHook.Unhook>())

    @Volatile
    private var captureGaveUp = false

    private val captureCalls = AtomicInteger(0)

    /** 认输上限：这么多 rawQuery 里都没见过 `SnsInfo` 就卸载（防常驻） */
    private const val CAPTURE_MAX_CALLS = 20_000

    private fun installCapture(cls: Class<*>) {
        if (snsDb != null) return
        val set = runCatching { XposedBridge.hookAllMethods(cls, "rawQuery", captureHook) }
            .onFailure { LogX.w("SNS: hook rawQuery failed: ${it.javaClass.simpleName}") }
            .getOrNull()
        if (set.isNullOrEmpty()) {
            LogX.w("SNS: no rawQuery overload to hook, sns db capture disabled")
            return
        }
        synchronized(captureUnhooks) { captureUnhooks.addAll(set) }
        LogX.i("SNS capture hook installed rawQuery x${set.size} (拿到句柄即卸载)")
    }

    private val captureHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                // ① 已经拿到 / 已经认输 → 卸掉自己（这几行就是卸载前的全部开销）
                if (snsDb != null || captureGaveUp) {
                    unhookCapture()
                    return
                }
                if (captureCalls.incrementAndGet() > CAPTURE_MAX_CALLS) {
                    captureGaveUp = true
                    unhookCapture()
                    LogX.w("SNS capture gave up after $CAPTURE_MAX_CALLS rawQuery (扫当前 SnsInfo 不可用)")
                    return
                }
                val sql = param.args?.getOrNull(0) as? String ?: return
                if (!sql.contains(TABLE)) return
                val db = param.thisObject ?: return
                // isSnsDb 命中时会把句柄记进 [snsDb]（顺带校验路径）
                if (isSnsDb(db)) {
                    LogX.i("SNS sns db captured via rawQuery (导出扫当前 SnsInfo 已可用)")
                    unhookCapture()
                }
            }.onFailure {
                // 捕获是**附加收益**，绝不能因为它把微信的查询搞挂
                LogX.w("SNS capture err: ${it.javaClass.simpleName}")
            }
        }
    }

    private fun unhookCapture() {
        val list = synchronized(captureUnhooks) {
            if (captureUnhooks.isEmpty()) return
            ArrayList(captureUnhooks).also { captureUnhooks.clear() }
        }
        for (u in list) runCatching { u.unhook() }
    }

    /** 微信库句柄是否已捕获（=「扫当前 SnsInfo」这条路现在能不能走） */
    fun snsReady(): Boolean = snsDb != null

    /**
     * 只读暴露微信朋友圈库句柄，给 [SnsTimelineSourceFix]（R27 srcfix）复用。
     *
     * ⚠️ 仍然只在 `execSQL` / `rawQuery` 捕获钩子里被赋值（见 [installCapture]），
     *  **本轮没有新增任何常驻钩子**。调用方必须容忍 null（= 这次启动微信还没碰过朋友圈库）。
     */
    internal fun snsDbHandle(): Any? = snsDb

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

    // ------------------------------------------------------------------ 当前 SnsInfo（R20：导出数据源）

    /**
     * **需求 6.4 自动导出的数据源（R20 改路由）**：读**当前** `SnsInfo` 里**可见**的动态，
     * 键集分页一次一页。
     *
     * ## ⚠️ 语义：这是「防撤回」，不是「删除后抢救」
     * 用户定案（2026-10-25）：本导出是**搭配定时刷新朋友圈**用的 —— 在动态**还看得见**
     * 的时候就把它的图留存下来（**预防性留存**），而不是等它被删了再从 `sns_archive`
     * 抢救。而微信的 sns 磁盘缓存 `…/cache/<hash32>/sns/<hex1>/<hex2>/snst_xxx`
     * 里放的正是**当前可见**动态的图：R19 实测归档那 7 个 mediaId 在 72 个缓存文件里
     * **0 命中** —— 扫归档时 `imgCopied` 恒为 0 是数据源选错了，不是取图链路坏了。
     *
     * ## 可见性过滤 —— ⛔ R26 起**故意不加**
     * 旧实现按 `(localFlag & 16) != 0` 筛，依据是「时间线白名单 `localFlag in (16,2064,18,…)`
     * 里每个值都置了 bit4」。2026-10-26 真机证伪：
     * - 最新 50 行 `localFlag` 分布 `{2:45, 4098:2, 5122:3}` —— **含 bit16 的行数为 0**；
     * - 而同一时刻朋友圈界面正常显示「8 分钟前」的新动态；
     * - 导出因此**永远只扫到 7 条旧动态**（`flag=50`=32|16|2，最新一条 2026-10-16），
     *   新动态一条都进不来 —— 图片导出也就无从验证（图只在「新动态进主循环」或
     *   「旧动态的图还在缓存」时才拷得到，两头都不成立）。
     *
     * ⇒ 现在**只按键集分页取，不按 flag 筛**。取舍理由：
     * 这是防撤回的主数据源，**漏采 = 动态被删后永久找不回来**，比多带脏数据严重一个量级；
     * 而多导出的 txt 是加法、可逆（`renderText` 会打出 `来源=sourceType`，事后可筛）。
     * 广告/推荐位的 `sourceType` 映射尚未定死（实测出现过 0/2/6/8/10/130），
     * **不做黑名单**：定不死就黑名单，只会再次误杀真动态。
     * 详见 `docs/_r26_bit16_rootcause.md`。
     *
     * ⚠️ [VISIBLE_BIT] 仍然保留给「拦下删除后补回可见位」（[restoreVisibility]），
     * 那条路径的语义是「微信自己清过这一位，我们照原样补回去」，与查询筛选无关。
     *
     * ## ⚠️ 返回的是**半行**，只取导出需要的 7 列
     * `attrBuf` / `postBuf` / `head` / `stringSeq` / `subType` / `serverExtFlag` **不读**
     * （**显式**填 0/null —— `SnsRow` 对这几个字段没有默认值，不填编译不过，见 `liveRows`）。
     * 朋友圈 `content` 动辄几十 KB，一页 100 行再搭两个 BLOB 就是几百 MB，
     * 后台线程 OOM 会把微信带走（[GroupDb.listSnsArchiveBefore] 那边的同一条理由）。
     *
     * ⛔ **不要拿这里的行去归档**（`GroupDb.archiveSns`）：那是用一个残缺快照
     * 覆盖掉 [readRow] 存下来的完整快照，而 archiveSns 是「首条为准」，覆盖不回来。
     *
     * @param createTime 上一页最后一行的 `createTime`；首轮传 `Long.MAX_VALUE`
     * @param snsId 上一页最后一行的 `snsId`；首轮传 `Long.MAX_VALUE`。
     *              ⚠️ 复合游标：只按 `createTime <` 会在页边界丢掉同一秒的行
     * @return 一页行（`createTime desc, snsId desc`）；
     *         **null = 拿不到微信库句柄**（[snsDb] 还没捕获到 —— 这次启动微信还没碰过
     *         朋友圈库）。这不是错误，调用方必须**优雅降级**（退回扫归档），别弹窗。
     */
    fun listCurrentSns(createTime: Long, snsId: Long, limit: Int): List<GroupDb.SnsRow>? {
        val db = snsDb ?: return null
        val m = rawQueryMethod(db) ?: return null
        // ⚠️ limit 来自调用方常量，仍钳一次：拼进 SQL 的值必须自己守住
        val lim = limit.coerceIn(1, 200)
        // ⚠️ select 列表**固定 7 列**，下面按下标读 —— 下标即契约。
        //    列名（schema）对不上会让 SQLite 直接报错（被 runCatching 兜住并**告警**），
        //    不会像 `select *` 那样静默取错列。
        val sql = "select snsId,userName,createTime,type,sourceType,localFlag,content " +
            "from $TABLE where (createTime < ? or (createTime = ? and snsId < ?)) " +
            "order by createTime desc, snsId desc limit $lim"
        val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
            arrayOf(createTime.toString(), createTime.toString(), snsId.toString())
        } else {
            arrayOf<Any>(createTime.toString(), createTime.toString(), snsId.toString())
        }
        return liveRows(db, m, sql, args)
    }

    private fun liveRows(db: Any, m: Method, sql: String, args: Any): List<GroupDb.SnsRow> {
        val out = ArrayList<GroupDb.SnsRow>()
        val cur = runCatching { m.invoke(db, sql, args) }
            .onFailure {
                // ⛔ 不许静默吞：查不动 = 导出恒 0 行且看不出原因，必须留一行
                LogX.w(
                    "SNS live query failed ${it.javaClass.simpleName}: " +
                        "${(it.cause ?: it).message?.take(80)}"
                )
            }
            .getOrNull() ?: return out
        try {
            var guard = 0
            while (guard++ < 5_000 &&
                XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true
            ) {
                val id = cLong(cur, 0)
                // snsId 是 Long 且**可能为负**（真机 -3447438253133393222）；
                // 0 = 这一列没读出来，别把一堆 0 塞进去（会让去重把它们全并成一条）
                if (id == 0L) continue
                out += GroupDb.SnsRow(
                    snsId = id,
                    userName = cText(cur, 1),
                    createTime = cLong(cur, 2),
                    type = cLong(cur, 3).toInt(),
                    sourceType = cLong(cur, 4).toInt(),
                    localFlag = cLong(cur, 5).toInt(),
                    // ⚠️ 五个「没查的列」必须**显式**填零值：`SnsRow` 对这五个字段
                    //    没有默认值（只有 `at` 有）。原先 KDoc 写「留默认值」是错的 ——
                    //    真实后果是编译报 `No value passed for parameter 'subType'` 等 **6 条**，
                    //    R20 收口时由 `compileDebugKotlin` 抓出。填 0/null 与「留默认值」
                    //    在语义上等价：下面的读法本来就不碰它们（只吃 7 列）。
                    subType = 0,
                    serverExtFlag = 0,
                    head = null,
                    stringSeq = null,
                    content = cBlob(cur, 6),
                    attrBuf = null,
                    postBuf = null,
                )
            }
        } finally {
            runCatching { XposedHelpers.callMethod(cur, "close") }
        }
        return out
    }

    /**
     * 读一列 Long。
     *
     * ⛔ R20b（复核 §2 #1，报告判为「整条链路**唯一**能把读失败伪装成 0 行、且零日志的点」）：
     * 原来是一行 `runCatching{...}.getOrDefault(0L)`，**完全静默**。
     * 后果链：`getLong` 反射失败 → 返回 0 → `liveRows` 里 `if (id == 0L) continue` 全部跳过
     * → 单页恒空 → 上层当「这一页扫完了」切源 → 摘要 `rows=0 written=0`。
     * 现场看到的症状是**「没有动态」**，实际是**「读不出来」**，两者排查方向相反。
     *
     * ⇒ 失败时留一行日志（`CLOG`）。**只打日志、不改返回值**：`getLong` 失败就返回 0
     * 是本函数既定的契约（`id == 0` 在上层已有明确语义 = 这一列没读出来），
     * 本次只补可观测性，不动行为 —— 避免把「补日志」变成「改语义」。
     */
    private fun cLong(cur: Any, i: Int): Long =
        runCatching { XposedHelpers.callMethod(cur, "getLong", i) as? Long ?: 0L }
            .onFailure {
                LogX.w(
                    "CLOG getLong[$i] failed ${it.javaClass.simpleName}: " +
                        "${(it.cause ?: it).message?.take(60)}"
                )
            }
            .getOrDefault(0L)

    /**
     * 读一列 String / ByteArray。同 [cLong] 的 R20b 补丁理由：
     * 失败静默变 null ⇒ 上层看到「这个字段是空的」，而真相是「反射没读到」。
     * `content` 走 [cBlob]，它空掉会让 `mediaIdsFromContent` 恒 0 ⇒ 图片永远导不出，
     * 且日志上完全看不出原因。⇒ 失败留一行 `CLOG`，返回值契约不变。
     */
    private fun cText(cur: Any, i: Int): String? =
        runCatching { XposedHelpers.callMethod(cur, "getString", i) as? String }
            .onFailure {
                LogX.w(
                    "CLOG getString[$i] failed ${it.javaClass.simpleName}: " +
                        "${(it.cause ?: it).message?.take(60)}"
                )
            }
            .getOrNull()

    private fun cBlob(cur: Any, i: Int): ByteArray? =
        runCatching { XposedHelpers.callMethod(cur, "getBlob", i) as? ByteArray }
            .onFailure {
                LogX.w(
                    "CLOG getBlob[$i] failed ${it.javaClass.simpleName}: " +
                        "${(it.cause ?: it).message?.take(60)}"
                )
            }
            .getOrNull()

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
     * **A4 取证探针（R19）**：扫**当前** `SnsInfo`（不是归档表！）的 `content`，
     * 抽出里面所有「连续 ASCII 数字 ≥15 位」的串作为 mediaId 候选。
     *
     * ## 为什么必须扫当前表而不是归档表
     * `MomentsExport.probeSnsCache()` 扫的是 `GroupDb.listSnsArchive()` =
     * **防撤回归档表**（历史**已被删除**的动态），而 sns 磁盘缓存里放的却是
     * **当前朋友圈可见动态**的图 —— 两者**根本不重叠**。实测：归档的 7 个 mediaId
     * 候选在设备 72 个缓存文件名里 **0 命中**。
     * ⇒ 要验证「mediaId ↔ 缓存文件名」的对应关系，样本源必须换成当前 `SnsInfo`。
     *
     * ## 为什么用「抽数字串」而不是按 protobuf 字段号解析
     * R15 定案的层级是 `content → 字段6 → 字段8 → 字段1`，但真机实测
     * **字段 6 内部根本没有字段 8**（`in6=[1:5,2:5,3:2,4:2,5:2,6:2,7:0,9:0,10:0,11:0]`）
     * ⇒ 按字段号取恒空（`ids=0`）。在字段号未知的情况下，**直接从字节里抽数字串**
     * 是唯一不依赖字段号假设的办法：缓存文件名 `snst_15039549883688563348` 的
     * 后缀就是 20 位数字串，若 content 里存在同样的串，抽出来就能和文件名对上。
     *
     * ⚠️ 日志单行有 **295 字符硬上限**（实测），候选最多打 3 个、每个截 24 位。
     */
    fun probeMediaIds(limit: Int = 5): List<String> {
        val lim = MomentsLogic.clampProbeN(limit)
        val db = snsDb ?: return listOf("no sns db yet (先让微信刷一次朋友圈，或删一条动态)")
        val m = rawQueryMethod(db) ?: return listOf("no rawQuery on ${db.javaClass.name}")
        return runCatching {
            val args: Any? = null
            val out = mutableListOf<String>()
            val cur = m.invoke(
                db,
                "select snsId,content from $TABLE order by createTime desc limit $lim",
                args,
            ) ?: return listOf("null cursor")
            try {
                var n = 0
                var withCand = 0
                while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                    val id = XposedHelpers.callMethod(cur, "getLong", 0) as? Long ?: 0
                    val blob = runCatching {
                        XposedHelpers.callMethod(cur, "getBlob", 1) as? ByteArray
                    }.getOrNull()
                    val cands = if (blob == null) emptyList() else digitRuns(blob, 15)
                    if (cands.isNotEmpty()) withCand++
                    out += "SNSMID snsId=$id len=${blob?.size ?: -1} " +
                        "c=${if (cands.isEmpty()) "-" else cands.take(3).joinToString(",") { it.take(24) }}"
                    n++
                }
                out.add(0, "SNSMID rows=$n withCand=$withCand (limit=$lim)")
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
            out
        }.getOrElse { listOf("err ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}") }
    }

    /**
     * **A4 取证探针（R19 第二步）**：递归解析 `content` 的 protobuf，
     * 找出所有「值是 ≥18 位纯数字 ASCII 串」的叶子，打印它的**字段路径**。
     *
     * ## 为什么需要它
     * [probeMediaIds] 已证明 mediaId **确实在 content 里**（19 个候选里 4 个
     * 精确命中设备缓存文件名 `snst_<20位数字>`），但「从字节里抽数字串」只是取证手段，
     * 不能作为正式实现 —— 正式实现必须按**字段号**解析。
     * R15 定案的 `content → 6 → 8 → 1` 在这台设备上不成立（字段 6 内没有 8），
     * 所以真正的路径未知，用它来定位。
     *
     * ## 输出形态
     * `MIDPATH snsId=... p=1(20),6>3(20)` = 顶层字段 1 和「字段6→字段3」里各有 20 位数字串。
     */
    fun probeMediaPaths(limit: Int = 5): List<String> {
        val lim = MomentsLogic.clampProbeN(limit)
        val db = snsDb ?: return listOf("no sns db yet")
        val m = rawQueryMethod(db) ?: return listOf("no rawQuery on ${db.javaClass.name}")
        return runCatching {
            val cur = m.invoke(
                db,
                "select snsId,content from $TABLE order by createTime desc limit $lim",
                null,
            ) ?: return listOf("null cursor")
            try {
                val out = mutableListOf<String>()
                var n = 0
                while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                    val id = XposedHelpers.callMethod(cur, "getLong", 0) as? Long ?: 0
                    val blob = runCatching {
                        XposedHelpers.callMethod(cur, "getBlob", 1) as? ByteArray
                    }.getOrNull()
                    val paths = LinkedHashSet<String>()
                    if (blob != null) scanDigitPaths(blob, "", paths, 0)
                    out += "MIDPATH snsId=$id len=${blob?.size ?: -1} " +
                        "p=${if (paths.isEmpty()) "-" else paths.take(5).joinToString(",")}"
                    n++
                }
                out.add(0, "MIDPATH rows=$n (limit=$lim)")
                out
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
        }.getOrElse { listOf("err ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}") }
    }

    /**
     * **A4 端到端验收探针（R19）**：扫**当前** SnsInfo → 用 `MomentsLogic.mediaIdsFromContent`
     * 解 mediaId → 与磁盘缓存文件名对账，报 `hit`。
     *
     * ## 这是 A4 的正式判据（`hit>0` 才算通过）
     * `op=moments sub=snsprobe` 报 `hit=0` 有两个叠加原因，本探针两个都绕开：
     * ① 它扫**归档表**（历史被删动态），缓存里是**当前**动态的图 ⇒ 样本根本不重叠；
     * ② 它用的字段号是旧的 `6>8>1` ⇒ `ids=0`。
     * 本探针扫当前表 + 用 R19 实测修正后的 `8>5>1`。
     *
     * ⚠️ 输出受 295 字符/行限制，命中文件名最多打 2 个、每个截 28 字符。
     */
    fun probeHit(limit: Int = 5): List<String> {
        val lim = MomentsLogic.clampProbeN(limit)
        val db = snsDb ?: return listOf("no sns db yet")
        val m = rawQueryMethod(db) ?: return listOf("no rawQuery on ${db.javaClass.name}")
        val cache = scanCacheNames()
        return runCatching {
            val cur = m.invoke(
                db,
                "select snsId,content from $TABLE order by createTime desc limit $lim",
                null,
            ) ?: return listOf("null cursor")
            try {
                val out = mutableListOf<String>("SNSHIT cacheFiles=${cache.size}")
                var n = 0
                var totalIds = 0
                var totalHit = 0
                var rowsHit = 0
                while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                    val id = XposedHelpers.callMethod(cur, "getLong", 0) as? Long ?: 0
                    val blob = runCatching {
                        XposedHelpers.callMethod(cur, "getBlob", 1) as? ByteArray
                    }.getOrNull()
                    val ids = MomentsLogic.mediaIdsFromContent(blob)
                    val hits = LinkedHashSet<String>()
                    for (mid in ids) {
                        for (nm in MomentsLogic.snsNameCandidates(mid)) {
                            val f = cache[nm]
                            if (f != null) { hits.add(f); break }
                        }
                    }
                    totalIds += ids.size
                    totalHit += hits.size
                    if (hits.isNotEmpty()) rowsHit++
                    out += "SNSHIT snsId=$id ids=${ids.size} hit=${hits.size} " +
                        "n=${if (hits.isEmpty()) "-" else hits.take(2).joinToString(",") { it.take(28) }}"
                    n++
                }
                out += "SNSHIT sum rows=$n rowsHit=$rowsHit ids=$totalIds hit=$totalHit"
                out
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
        }.getOrElse { listOf("err ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}") }
    }

    /**
     * 扫 sns 磁盘缓存建 `Map<文件名,文件名>`。
     * 路径 `/sdcard/Android/data/com.tencent.mm/cache/<hash32>/sns/<hex1>/<hex2>/<name>`。
     *
     * ⛔ **不做任何分桶/截断推算**：直接遍历两级目录建索引（R15 已定案：分桶公式
     * 真机只中 0.68%，必须索引直扫）。
     */
    private fun scanCacheNames(): Map<String, String> {
        val out = HashMap<String, String>()
        val root = java.io.File("/sdcard/Android/data/com.tencent.mm/cache")
        val dirs = root.listFiles() ?: return out
        for (d in dirs) {
            val sns = java.io.File(d, "sns")
            if (!sns.isDirectory) continue
            val h1s = sns.listFiles() ?: continue
            for (h1 in h1s) {
                val h2s = h1.listFiles() ?: continue
                for (h2 in h2s) {
                    val fs = h2.listFiles() ?: continue
                    for (f in fs) if (f.isFile) out[f.name] = f.name
                }
            }
        }
        return out
    }

    /**
     * 递归扫 protobuf：把「值 = 长度 ≥18 的纯数字 ASCII 串」的叶子路径记进 [out]。
     * 非数字串的 wiretype=2 字段当作子消息继续下钻（限深 5、路径数上限 8，防爆）。
     */
    private fun scanDigitPaths(b: ByteArray, path: String, out: LinkedHashSet<String>, depth: Int) {
        if (depth > 5 || out.size >= 8) return
        var i = 0
        var guard = 0
        while (i < b.size && guard++ < 600) {
            val tag = MomentsLogic.readVarintPublic(b, i) ?: return
            i = tag.second
            val field = (tag.first.toInt() ushr 3)
            val wt = tag.first.toInt() and 7
            if (field <= 0) return
            when (wt) {
                0 -> {
                    val v = MomentsLogic.readVarintPublic(b, i) ?: return
                    i = v.second
                }
                1 -> i += 8
                5 -> i += 4
                2 -> {
                    val l = MomentsLogic.readVarintPublic(b, i) ?: return
                    var j = l.second
                    val len = l.first.toInt()
                    if (len < 0 || j + len > b.size) return
                    val sub = b.copyOfRange(j, j + len)
                    if (sub.size >= 18 && sub.all { it.toInt() and 0xFF in 0x30..0x39 }) {
                        out.add("$path$field(${sub.size})")
                    } else {
                        scanDigitPaths(sub, "$path$field>", out, depth + 1)
                    }
                    i = j + len
                }
                else -> return
            }
        }
    }

    /** 从字节里抽出所有长度 ≥[minLen] 的连续 ASCII 数字串（去重、保序） */
    private fun digitRuns(b: ByteArray, minLen: Int): List<String> {
        val out = LinkedHashSet<String>()
        var s = -1
        for (i in b.indices) {
            val d = b[i].toInt() and 0xFF
            if (d in 0x30..0x39) {
                if (s < 0) s = i
            } else {
                if (s >= 0 && i - s >= minLen) out.add(String(b, s, i - s, Charsets.US_ASCII))
                s = -1
            }
        }
        if (s >= 0 && b.size - s >= minLen) out.add(String(b, s, b.size - s, Charsets.US_ASCII))
        return out.toList()
    }

    /**
     * 评论表取证：列名 + 最近几行的全部列值。
     *
     * ## ✅ 去留已裁定（2026-10-25 用户拍板：**保留**）
     * 评论域的**功能代码**（`SnsCommentGuard` / `SnsCommentTag` / `SnsCommentLogic` /
     * `SnsCommentLabelLogic` + 121 个单测）已于 2026-10-24 物理删除（N7），
     * 但**本探针保留**，理由三条：
     * ① 它是 `SnsGuard` 的方法，`SnsGuard` 是**动态防撤回**的主体，与评论域不同源；
     * ② 它是**评论表唯一的取证入口**（`EntryReceiver` 的 `op=sns --es sub cmts` 调它），
     *    删了就没有办法再看评论表结构；
     * ③ 这里的 `SnsComment` 是**数据库表名**（`select * from SnsComment`），
     *    不是被删的那些类名 —— 两者只是同名。
     *
     * ⛔ 别因为「评论域已删」就顺手把这个也删掉：那是**同名误伤**，
     *    见 `docs/_r18_n7_deletion.md` 的「哪些 SnsComment 不能删」段。
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
     * 插后复查：同一个 snsId 现在有几行。
     *
     * ⚠️ 为什么要这个：真机 DDL 实证 `SnsInfo` 上 **snsId 既不是 PRIMARY KEY 也没有 UNIQUE**
     * （`CREATE TABLE SnsInfo ( snsId LONG, ...)`，6 个索引 `unique` 全为 0），
     * 所以 `CONFLICT_IGNORE` **拦不住重复插入** —— 它只在撞到唯一约束时才退。
     * 主防线是「写之前 [readFlag] 查成 ABSENT 才插」，这里数一遍是为了兜住 TOCTOU
     * （查与插之间微信自己插了一条同 snsId）。
     */
    private fun countRows(db: Any, snsId: Long): Int? {
        val m = rawQueryMethod(db) ?: return null
        return runCatching {
            val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
                arrayOf(snsId.toString())
            } else {
                arrayOf<Any>(snsId.toString())
            }
            val c = m.invoke(db, "select count(*) from $TABLE where snsId=?", args)
                ?: return@runCatching null
            try {
                if (XposedHelpers.callMethod(c, "moveToFirst") as? Boolean != true) {
                    null
                } else {
                    (XposedHelpers.callMethod(c, "getLong", 0) as? Long ?: 0L).toInt()
                }
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

    /**
     * 从归档**反插回**微信的 `SnsInfo`（需求 6.4「从归档恢复」，诊断入口，不自动执行）。
     *
     * 三种入口状态由 [SnsRestoreLogic.classify] 判定（纯逻辑，已单测锁住）：
     * - 行在且已可见 → [SnsRestoreLogic.Action.SKIP]，**什么都不写**（幂等的关键）；
     * - 行在但可见位被清了 → 只 [writeFlag]，不动内容；
     * - 行**没了**（拦之前就删了 / 早期版本没拦到）→ 整行 INSERT 回去，并补可见位。
     *
     * ⚠️ INSERT 只提供 [SnsRestoreLogic.KNOWN] 那 13 列（见 [readRow]），`SnsInfo` 共 18 列，
     * 剩下 5 列（`localPrivate`/`likeFlag`/`pravited`/`withTa`/`withTaHasOther`）
     * 归档里没存，**故意不写**，由 SQLite 填默认/NULL。
     * 2026-10-18 真机 `snsg sub=cols` 实测 18 列 `notNull` 全为 false（BLOCKING none），
     * 所以「缺列导致 INSERT 失败」这条已经排除；万一这里还是报错，
     * 异常消息会原样带回来，那就是微信改了表结构，靠 [snsInfoColumns] 重新取证，不猜。
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
                // ⚠️ 真机实证：SnsInfo 上 snsId **没有唯一约束**（6 个索引 unique 全 0），
                // 所以 CONFLICT_IGNORE 这道保险是失效的 —— 重复插入不会报错也不会被拦。
                // 主防线是「写之前查到 ABSENT 才插」，这里再数一遍兜住 TOCTOU。
                val n = countRows(db, snsId)
                if (n != null && n > 1) {
                    LogX.w("SNS ⚠️ 插重了 snsId=$snsId count=$n（表上无唯一约束，IGNORE 拦不住）")
                }
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
        // ⚠️ 必须把 UNKNOWN 的 id 传进去：它们不在 live 里，plan 会误判成「行没了」→ INSERT
        //    → 撞主键 → IGNORE 静默不写，那条动态永远恢复不了（详见 SnsRestoreLogic.plan 的 KDoc）
        val unknownIds = pre.filterValues { it == SnsRestoreLogic.Presence.UNKNOWN }.keys
        val plan = SnsRestoreLogic.plan(rows.map { it.toLogic() }, live, n, unknownIds)
        if (unknown > 0) {
            LogX.w("SNS restore: $unknown/${rows.size} 行预检查询失败(UNKNOWN)，已跳过不写，" +
                "下一批预查恢复后会自动重试")
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
     * 取证：`pragma table_info(SnsInfo)`。只读，不写任何东西。
     *
     * 回答三个问题，每个都直接决定反插敢不敢上真机：
     * - 我们写的 13 列在真机上**还在不在**（[SnsRestoreLogic.missingColumns]）；
     * - 我们**没写**的列里有没有 NOT NULL 且无默认值的（[SnsRestoreLogic.blockingColumns]，
     *   有则 INSERT 必挂）。2026-10-18 实测 18 列 `notNull` **全为 false** → `BLOCKING none`，
     *   这条路已经**排除**，这里留着是当护栏（微信哪天加列会立刻点名）；
     * - 有没有**我们不认识的新列**（[SnsRestoreLogic.alienColumns]）= 微信改了表结构。
     *
     * ⚠️ 那 5 列「已知但不写入」的（`localPrivate`/`likeFlag`/`pravited`/`withTa`/
     * `withTaHasOther`）在输出里标 `known-unwritten`，**不是异常** —— 归档里没存这些值，
     * 写进去等于编数据，由 SQLite 填默认/NULL。只有不在名单上的新列才标 ⚠️。
     */
    fun snsInfoColumns(): List<String> {
        val db = snsDb ?: return listOf("no sns db yet")
        val info = tableInfo(db) ?: return listOf("no pragma result")
        val out = ArrayList<String>(info.size + 5)
        val names = info.map { it.name }
        for (c in info) {
            val tag = when {
                SnsRestoreLogic.isKnown(c.name) -> ""
                SnsRestoreLogic.isUnarchived(c.name) -> "  <- known-unwritten(归档没存,不写)"
                else -> "  <- ⚠️ NEW(名单外的新列,微信改表了?)"
            }
            out += "${c.name} notNull=${c.notNull} default=${if (c.hasDefault) "yes" else "-"}$tag"
        }
        val missing = SnsRestoreLogic.missingColumns(names)
        if (missing.isNotEmpty()) out += "⚠️ 白名单里有列在真机不存在: $missing"
        out += "COLS total=${info.size} known=${SnsRestoreLogic.KNOWN.size} " +
            "unwritten=${SnsRestoreLogic.unknownColumns(names)}"
        val alien = SnsRestoreLogic.alienColumns(names)
        if (alien.isNotEmpty()) out += "⚠️ ALIEN $alien (既不在写入白名单也不在已知未写列 -> 回去重新取证)"
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
     *
     * ## `execSQL` 退路（2026-10-19 R6 补）
     * wcdb 上 `insertMethod` 可能**一个都选不上**（见它的日志），旧代码此时直接 `return null`
     * → [restoreBatch] 判 `ok=false` → 整批中止并回滚。也就是说「wcdb 少一个方法」这一个原因
     * 就能让「从归档恢复」整体不可用 —— 而 `SnsSql.insertSql` 早就写好了却**零调用点**（死代码）。
     *
     * 现在：`insertWithOnConflict`/`insert` 都拿不到、或拿到了但 invoke 失败/返回 null 时，
     * 退一次 `execSQL(sql, bindArgs)`。
     *
     * ⚠️ **`execSQL` 必须先二阶确认**：它返回 void，`INSERT OR IGNORE` 撞主键时**静默不写**
     * 且不抛异常。只看"没抛异常"会把没插进去的行算进 `inserted`，
     * 用户看到「恢复了 n 条」但朋友圈里啥都没有 —— 比直接失败更难查。所以写完必须回查一次。
     *
     * @return rowid；null/-1 = 失败（**库不会被写坏**，SQLite 单条 INSERT 是原子的）
     */
    private fun insertRow(db: Any, row: SnsRestoreLogic.ArchiveRow): Long? {
        val m = insertMethod(db)
        if (m != null) {
            val args: Array<Any?> = if (m.name == "insertWithOnConflict") {
                arrayOf(TABLE, null, contentValuesOf(row), CONFLICT_IGNORE)
            } else {
                arrayOf(TABLE, null, contentValuesOf(row))
            }
            val ret = runCatching { m.invoke(db, *args) as? Long }
                .onFailure {
                    LogX.w("SNS insert via ${m.name} threw snsId=${row.snsId}: " +
                        "${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(120)}")
                }
                .getOrNull()
            if (ret != null) {
                LogX.i("SNS insert via ${m.name} snsId=${row.snsId} rowid=$ret")
                return ret
            }
            // 走到这里：方法拿到了但没写成功 —— 退路值得一试（不要直接判死）
            LogX.w("SNS insert via ${m.name} returned $ret, trying execSQL fallback snsId=${row.snsId}")
        } else {
            LogX.w("SNS insertMethod NONE on ${db.javaClass.name}, trying execSQL fallback snsId=${row.snsId}")
        }

        val ok = insertViaExecSql(db, row)
        if (!ok) return null
        // ⚠️ execSQL 没有 rowid 返回（**也不能**再查一次 rowid 冒充：那行可能是别人早就有的，
        //    因为 OR IGNORE 撞主键时不会写）。回查只用来判"行在不在"，成功就返回一个恒 >= 0 的值。
        return 0L
    }

    /**
     * `execSQL` 退路的实现。成功 = **SQL 不抛异常** 且 **回查确认该行存在**。
     *
     * ⚠️ 实参数组的静态类型必须跟着形参走：`arrayOf(...)` 编出来是 `Object[]`，
     * 形参若是 `String[]` 就会 `IllegalArgumentException` —— 跟 [updateFlag] 里
     * 那个「补可见位静默失效」的坑是同一个，但这里多了更阴的一层：
     * 异常被 `runCatching` 吞掉后如果不再回查，就会伪装成"成功"。
     */
    private fun insertViaExecSql(db: Any, row: SnsRestoreLogic.ArchiveRow): Boolean {
        val puts = SnsRestoreLogic.insertPuts(row)
        val sql = SnsSql.insertSql(TABLE, puts.map { it.col }, CONFLICT_IGNORE)
        if (sql.isEmpty()) {
            LogX.w("SNS execSQL fallback: empty sql snsId=${row.snsId}")
            return false
        }
        val binds = SnsRestoreLogic.toBindArgs(puts)
        val m = execSqlMethod(db)
        if (m == null) {
            LogX.w("SNS execSQL not found on ${db.javaClass.name} snsId=${row.snsId}")
            return false
        }
        val threw = runCatching {
            val args: Any = if (m.parameterTypes.size == 1) {
                sql
            } else if (m.parameterTypes[1] == Array<String>::class.java) {
                // 形参是 String[]：bindArgs 只能是字符串（BLOB 传进来会 ClassCastException）
                Array(binds.size) { i -> binds[i]?.toString() ?: "" }
            } else {
                binds
            }
            if (m.parameterTypes.size == 1) m.invoke(db, sql) else m.invoke(db, sql, args)
        }.onFailure {
            LogX.w("SNS execSQL fallback threw snsId=${row.snsId}: " +
                "${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(120)}")
        }.isFailure
        if (threw) return false

        // ── 二阶确认：`INSERT OR IGNORE` 撞主键时静默不写，必须回查 ──
        // 注意这里只能证明"行在"，不能证明"是我们写的"。所以调用方（restoreBatch）
        // 才必须在写之前用 Presence 三态区分 ABSENT / PRESENT —— 那一层已经做了。
        val present = runCatching { readFlag(db, row.snsId).presence != SnsRestoreLogic.Presence.ABSENT }
            .getOrDefault(false)
        if (!present) {
            LogX.w("SNS execSQL fallback: row STILL ABSENT after insert snsId=${row.snsId}" +
                " (OR IGNORE 静默没写？)")
            return false
        }
        LogX.i("SNS insert via execSQL fallback snsId=${row.snsId} (verified present)")
        return true
    }

    /** `execSQL` 的几种形态，优先 2 参（带 bindArgs）/ 1 参 */
    private fun execSqlMethod(db: Any): Method? = runCatching {
        val ms = db.javaClass.methods.filter { it.name == "execSQL" }
        ms.firstOrNull { it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java }
            ?: ms.firstOrNull { it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java }
    }.getOrNull()


    /**
     * `update SnsInfo set localFlag=? where snsId=?` —— 所有改可见位的路径都走这里。
     *
     * ⚠️ **whereArgs 的静态类型必须跟着签名走**：wcdb 跟 AOSP 一样存在
     * `update(..., String[])` 与 `update(..., Object[])` 两个重载，而 [updateMethod] 是
     * `firstOrNull` 抓的，命中的是哪一个**不确定**。以前这里写死传 `Array<String>`，
     * 一旦命中 `Object[]` 那个重载，反射抛 `IllegalArgumentException`、被 runCatching 吞掉，
     * 表现为「补可见位静默失效」—— 库没动、日志只有一行警告，正是
     * 「删了之后拦住了但朋友圈里看不到」最难查的那种。
     * 跟 [readFlag] / [undoInsert] 保持同一套分流写法：按 `parameterTypes[3]` 决定数组类型。
     *
     * @return 受影响行数；**-1 = 失败**（异常已记日志），0 = 没命中行
     */
    private fun updateFlag(db: Any, snsId: Long, flag: Int): Int {
        val m = updateMethod(db) ?: return -1
        return runCatching {
            val args: Any = if (m.parameterTypes[3] == Array<String>::class.java) {
                arrayOf(snsId.toString())
            } else {
                arrayOf<Any>(snsId.toString())
            }
            val cv = ContentValues().apply { put("localFlag", flag) }
            m.invoke(db, TABLE, cv, "snsId=?", args) as? Int ?: -1
        }.onFailure {
            LogX.w("SNS update flag failed snsId=$snsId flag=$flag: " +
                "${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(120)}")
        }.getOrDefault(-1)
    }

    /** 显式写 localFlag（SET_FLAG / 回滚都用它；跟 [restoreVisibility] 的区别是不跳过"已可见"） */
    private fun writeFlag(db: Any, snsId: Long, flag: Int): Boolean = updateFlag(db, snsId, flag) > 0

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
     *
     * ── 合并须知（跨 lane 改同一个坑，别把两份都留下）────────────────────────
     * 本文件**只有这一个**冲突算法常量，它只服务 [insertRow]（写**微信的 SnsInfo**）。
     * main 与其它 lane 的旧版本里，同一个位置叫 **`CONFLICT_REPLACE = 5`**，
     * 它也是同一个 `insertRow` 的实参（旧版 `restore()` 里内联写的），**用途相同、值是错的**。
     * 合并时应当**用这里的 `4` 顶掉那个 `5`**，而不是两个常量并存：
     *   - 留 `5` → 写微信 SnsInfo 变成 REPLACE，覆盖用户的活行（最坏结局）；
     *   - 留 `1` → ROLLBACK，撞主键回滚整批；
     *   - 两个都留 → 那个 `5` 会变成没人调用的死常量，谁下次顺手接上就是线上事故。
     * 写**自己归档表**（`sns_archive`）的那条路径跟这里无关，它在
     * `GroupDb.insertSnsArchive` 里用框架常量 `SQLiteDatabase.CONFLICT_IGNORE`
     * （首条为准，**故意**不去覆盖），不要把它和这里的值串味。
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
        // ⚠️ 这里**不要**再判 `cur is String`：上面的 lambda 里三个 `return "..."` 都是非局部返回
        // （`runCatching` 是 inline，`return` 直接返出 `restore()`），`getOrElse` 又兜住异常，
        // 所以 `cur` 到这儿 100% 是 Int。旧代码留着 `if (cur is String) return cur` ——
        // 编译器报 "Check for instance is always false"，纯死代码，已删。
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
        // 走 [updateFlag]（跟微信清可见位的写法完全一致），不在这里另起一套反射：
        // 另起一套就会漏掉 whereArgs 的重载分流，模拟器自己挂了会让人误判成「拦截没生效」
        val step1 = updateFlag(db, snsId, 2)
        val dm = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "delete" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java
            }
        }.getOrNull() ?: return "flag=$step1 no delete method"
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
