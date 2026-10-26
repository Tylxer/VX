package com.lx.wxgroup.core

import android.os.Handler
import android.os.Looper
import com.lx.wxgroup.data.Friend
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.WxidKind
import com.lx.wxgroup.feature.AntiRevoke
import com.lx.wxgroup.feature.GroupNameLogic
import com.lx.wxgroup.feature.PickerTabLogic
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 群聊联系人数据源：把 `rcontact` 里的群聊补进 `friend_cache`。
 *
 * ## 为什么需要它（R24 根因）
 *
 * `friend_cache`（`GroupDb.T_FRIEND`）此前**两个写入点都不写群聊**：
 *
 * 1. [ContactSync.doSync]（`:186`）遍历的是**通讯录 tab 的 adapter 对象图** ——
 *    微信通讯录 tab 里只有好友和公众号，群聊在「聊天」tab 的会话列表里，
 *    结构上就扫不到；
 * 2. [ContactSqlSource.doSupplement]（`:277`）的 SQL 是
 *    `username like '%@openim'`（`SUPPLEMENT_LIKE`），**只补企业微信好友**。
 *
 * ⇒ `friend_cache` 里 `wxid_type=2`（`CHATROOM`）恒为 0，
 * 于是 K3 选择器的「群聊」段永远是空的。这不是 UI 的 bug，是**数据源缺口**。
 *
 * ## 读法：与 [GroupNameSource] 同一套，不另造第二条 SQL 口径
 *
 * [GroupNameSource] 已经证明 rcontact 里群聊齐全、读得出来，且它的
 * `loadBlocking` 把「0 行」当**失败**而不是「成功但没群」（`:195-200`），
 * 说明真机上群聊数确实大于 0。
 *
 * ⚠️ **不用 `where username like '%@chatroom'`**：LIKE 的 `%` 语义在微信自带的
 * SQLCipher 分支上没验证过（[GroupNameSource]:212-218 已定案）。这里同样
 * **全表扫 + 按后缀过滤**，代价是同一张表一次性扫（后台线程），换来不依赖未验证行为。
 *
 * ## 群名取值：复用 [GroupNameLogic.pick]
 *
 * 真机取证（2026-10-18）：群聊 `conRemark` 常为空，群名在 `nickname`；
 * 用户手动备注过群之后 `conRemark` 有值，语义与好友一致（备注 > 昵称）。
 * 这套规则在 [GroupNameLogic] 里已配单测，**这里直接调用，不复制规则**。
 *
 * ## 只在必要时跑
 *
 * 全表扫 rcontact 在好友多的号上是万行级，不能每次同步都跑。判据：
 * - 库里的群聊行数为 0（首次 / 从没补成功过）→ 必须跑；
 * - 距上次成功超过 [FULL_INTERVAL_MS] → 跑（群会新增，昵称会改）；
 * - 否则跳过。
 */
object ChatroomSource {

    private const val TAG = "CHATROOM"

    private const val TABLE_CONTACT = "rcontact"

    /**
     * 全表扫的强制刷新间隔。
     *
     * 定 6 小时：群的新增/改名的时效要求远低于好友（选择器里的群名晚一点更新
     * 不影响正确性），而万行全表扫的代价不小，顺带也避免频繁占用后台线程。
     */
    private const val FULL_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /** 防御：rcontact 行数上限。正常号 1~2 万行，超这个数说明读错了表 */
    private const val READ_MAX_ROWS = 60_000

    private const val PREFS = "wxgroup_chatroom"
    private const val KEY_LAST_FULL = "last_full_sync"

    @Volatile
    private var appContext: android.content.Context? = null

    private val loading = AtomicBoolean(false)
    private val runs = AtomicLong(0)
    private val failures = AtomicLong(0)

    /**
     * 群聊补跑完成的监听者。**弱引用**：调用方必须自己强引用住回调（否则会被 GC），
     * 页面被回收后这里自动失效，不泄漏。回调在主线程执行，参数是本次写入条数。
     */
    private val roomListeners = CopyOnWriteArrayList<WeakReference<(Int) -> Unit>>()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var lastResult: String? = null

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    /**
     * 是否需要跑一次。主线程安全（只读计数与 SharedPreferences）。
     *
     * 刻意**不把「对象图扫到的群」当判据**：那个数字依赖用户是否打开了通讯录，
     * 而群聊的权威来源就是 rcontact，直接问库最准。
     */
    fun shouldRun(db: GroupDb?): Boolean {
        if (db == null) return false
        val cached = runCatching { db.allChatrooms().size }.getOrDefault(0)
        val last = prefs()?.getLong(KEY_LAST_FULL, 0L) ?: 0L
        return PickerTabLogic.needsFullSync(cached, last, System.currentTimeMillis(), FULL_INTERVAL_MS)
    }

    /**
     * 后台入口：满足 [shouldRun] 才真正跑。可被同步链路反复调用（自带节流）。
     *
     * ⚠️ 起线程而不是阻塞调用方：调用点在 [ContactSync.doSync] 的收尾，
     * 那里已经在后台线程，但全表扫要几百毫秒，不该把同步链路的收尾拖住。
     */
    fun runIfNeeded() {
        runCatching {
            if (!loading.compareAndSet(false, true)) return@runCatching
            val started = runCatching {
                Thread({
                    try {
                        // force=false：让 syncBlocking 内部**再判一次** shouldRun。
                        // 外层那次判断与真正执行之间隔着线程启动，期间可能已被别的路径
                        // 补过群聊了。显式传 false 既消掉「force 语义与 runIfNeeded 矛盾」
                        // 的歧义（R24 复核 S5），也避免重复全表扫。
                        //
                        // F1：命中 / 跳过各打一条可区分日志（只在「决定跑不跑」这一下打，
                        // 不在每个 Tab 切换上打）。
                        if (shouldRun(GroupDb.current())) {
                            LogX.i("$TAG 命中补跑：群聊缓存缺失或过期，起全表扫")
                            syncBlocking(force = false)
                        } else {
                            LogX.i("$TAG 跳过补跑：群聊缓存新鲜，无需全表扫")
                        }
                    } finally {
                        loading.set(false)
                    }
                }, "wxg-chatroom").apply { isDaemon = true }.start()
            }
            if (started.isFailure) loading.set(false)
        }.onFailure { LogX.w("$TAG runIfNeeded failed: ${cause(it)}") }
    }

    /**
     * 注册「群聊补跑完成」回调（弱引用）。回调在主线程执行，参数是本次写入条数（>0 表示
     * 有新群聊落库、页面应当刷新）。调用方**必须自己强引用住回调**，否则会被 GC 回收。
     *
     * 供 [com.lx.wxgroup.feature.PickerPage] 在打开时注册、刷新 Tab 用。
     */
    fun addRoomListener(l: (Int) -> Unit) {
        roomListeners.add(WeakReference(l))
    }

    private fun notifyRooms(written: Int) {
        val alive = roomListeners.mapNotNull { it.get() }
        if (alive.isEmpty()) return
        mainHandler.post {
            alive.forEach {
                runCatching { it(written) }
                    .onFailure { e -> LogX.w("$TAG listener failed: ${cause(e)}") }
            }
        }
    }

    /**
     * 同步执行一次（只能从后台线程调，探针的 `io {}` 也走这里）。
     *
     * @param force true 时无视 [shouldRun] 直接扫
     * @return 写入的群聊条数；失败返回 -1
     */
    fun syncBlocking(force: Boolean = true): Int = runCatching {
        val db = GroupDb.current() ?: run {
            lastError = "db not ready"
            LogX.w("$TAG db not ready, skip")
            return@runCatching -1
        }
        if (!force && !shouldRun(db)) {
            lastResult = "skipped (fresh)"
            return@runCatching 0
        }
        val wxDb = AntiRevoke.wxDb() ?: run {
            lastError = "wx db not ready"
            LogX.w("$TAG wx db not ready, skip")
            return@runCatching -1
        }
        val method = rawQueryMethod(wxDb) ?: run {
            fail("rawQuery not found on ${wxDb.javaClass.name}")
            return@runCatching -1
        }

        runs.incrementAndGet()
        val t0 = android.os.SystemClock.uptimeMillis()
        val rooms = readChatrooms(wxDb, method)
        if (rooms == null) {
            fail("query rcontact failed")
            return@runCatching -1
        }
        val cost = android.os.SystemClock.uptimeMillis() - t0

        if (rooms.isEmpty()) {
            // 0 行不能当成功：与 GroupNameSource 同判据（否则 ready 会关掉重试、
            // 群聊 Tab 在整个进程生命周期里静默为空）
            fail("0 chatroom rows")
            return@runCatching -1
        }

        val before = db.countFriends()
        val written = db.upsertFriends(rooms)
        val after = db.countFriends()
        val counts = db.friendTypeCounts()
        lastError = null
        lastResult = "rooms=${rooms.size} upsert=$written cost=${cost}ms"
        markFullSync()
        LogX.i(
            "$TAG sync rooms=${rooms.size} upsert=$written cost=${cost}ms " +
                "db $before -> $after types=" +
                counts.entries.joinToString { "${typeName(it.key)}=${it.value}" }
        )
        // F1：有新群聊落库时通知已打开的页面刷新（失败/0 行/无变化都不通知）
        if (written > 0) notifyRooms(written)
        written
    }.onFailure { fail(cause(it)) }.getOrDefault(-1)

    fun stat(): String = runCatching {
        val cached = runCatching { GroupDb.current()?.allChatrooms()?.size ?: -1 }.getOrDefault(-1)
        val last = prefs()?.getLong(KEY_LAST_FULL, 0L) ?: 0L
        val age = if (last == 0L) "never" else {
            val min = (System.currentTimeMillis() - last) / 60_000
            "${min}min ago"
        }
        val counts = runCatching { GroupDb.current()?.friendTypeCounts() }.getOrNull()
            ?.entries?.joinToString { "${typeName(it.key)}=${it.value}" } ?: "-"
        "$TAG stat cached=$cached loading=${loading.get()} runs=${runs.get()} " +
            "fail=${failures.get()} lastSync=$age lastResult=${lastResult ?: "-"} " +
            "lastError=${lastError ?: "-"} types=$counts"
    }.onFailure { LogX.w("$TAG stat failed: ${cause(it)}") }.getOrDefault("$TAG stat unavailable")

    /**
     * 诊断探针：打印 rcontact 的实际列清单 + 若干群行的原始字段值。
     *
     * 存在的理由：真机实测 841 个群**全部**显示成纯数字群号（`12345678@chatroom`），
     * 说明 `nickname` / `conRemark` 读出来是空。可能原因有三，只看 `sampleText`
     * 分不清：① 列名不是 `nickname`；② 列名对但微信 8.0.48 把群名存到别处；
     * ③ 这些群本来就无名。必须先看**列清单**和**该行的原始值**才能判定。
     */
    fun schemaText(): String = runCatching {
        val wxDb = AntiRevoke.wxDb() ?: return@runCatching "$TAG schema: wx db not ready"
        val method = rawQueryMethod(wxDb) ?: return@runCatching "$TAG schema: no rawQuery"
        val sb = StringBuilder("$TAG schema")
        val cur = raw(wxDb, method, "select * from $TABLE_CONTACT limit 200") ?: run {
            return@runCatching "$TAG schema: query failed"
        }
        try {
            val cols = runCatching { XposedHelpers.callMethod(cur, "getColumnNames") as? Array<*> }
                .getOrNull()?.map { it?.toString().orEmpty() }.orEmpty()
            sb.append("\n$TAG   cols(${cols.size})=").append(cols.joinToString(","))
            var shown = 0
            while (shown < 3 && moveNext(cur)) {
                val wxid = stringAt(cur, cols.indexOf("username"))?.trim().orEmpty()
                if (!wxid.endsWith(GroupNameLogic.CHATROOM_SUFFIX)) continue
                shown++
                sb.append("\n$TAG   ROW $wxid")
                for (c in cols) {
                    val v = stringAt(cur, cols.indexOf(c))
                    if (!v.isNullOrBlank()) sb.append(" | $c=${v.take(40)}")
                }
                // ⭐ 关键对照：同一条行，同一批列名，两种取下标方式。
                // readChatrooms 走的是 getColumnIndex（生产路径），这里走 getColumnNames。
                // 两者不一致 ⇒ 生产路径读到 -1 ⇒ nickname 恒空 ⇒ 群名丢。
                sb.append("\n$TAG   IDX viaNames: nickname=${cols.indexOf("nickname")}" +
                    " conRemark=${cols.indexOf("conRemark")}" +
                    " alias=${cols.indexOf("alias")}")
                sb.append("\n$TAG   IDX viaMethod: nickname=${columnIndexOf(cur, "nickname")}" +
                    " conRemark=${columnIndexOf(cur, "conRemark")}" +
                    " alias=${columnIndexOf(cur, "alias")}")
                sb.append("\n$TAG   VAL viaMethod: nickname=${stringAt(cur, columnIndexOf(cur, "nickname"))}" +
                    " conRemark=${stringAt(cur, columnIndexOf(cur, "conRemark"))}")
            }
            sb.append("\n$TAG   showed=$shown")
        } finally {
            close(cur)
        }
        sb.toString()
    }.onFailure { LogX.w("$TAG schema failed: ${cause(it)}") }.getOrDefault("$TAG schema unavailable")

    /**
     * 按指定 wxid 精确查 rcontact，打印该行所有非空列。
     *
     * 用途：`sampleText` 里 `nickname=''` 的那批群，需要确认它们在**微信源表**里
     * 到底有没有名字 —— 有则是本模块读取漏了，没有则本来就无名（显示群号是正确的）。
     */
    fun rowText(vararg wxids: String): String = runCatching {
        val wxDb = AntiRevoke.wxDb() ?: return@runCatching "$TAG row: wx db not ready"
        val method = rawQueryMethod(wxDb) ?: return@runCatching "$TAG row: no rawQuery"
        val sb = StringBuilder("$TAG row probe n=${wxids.size}")
        for (w in wxids) {
            val cur = raw(wxDb, method, "select * from $TABLE_CONTACT where username='$w'")
            if (cur == null) {
                sb.append("\n$TAG   $w => query failed")
                continue
            }
            try {
                if (!moveNext(cur)) {
                    sb.append("\n$TAG   $w => NOT FOUND in rcontact")
                } else {
                    sb.append("\n$TAG   $w")
                    val cols = runCatching {
                        XposedHelpers.callMethod(cur, "getColumnNames") as? Array<*>
                    }.getOrNull()?.map { it?.toString().orEmpty() }.orEmpty()
                    for (c in cols) {
                        val v = runCatching { stringAt(cur, columnIndexOf(cur, c)) }.getOrNull()
                        if (!v.isNullOrBlank()) sb.append(" | $c=${v.take(40)}")
                    }
                }
            } finally {
                close(cur)
            }
        }
        sb.toString()
    }.onFailure { LogX.w("$TAG row probe failed: ${cause(it)}") }.getOrDefault("$TAG row unavailable")

    /** 前若干个群聊样本，核对群名取值（探针用） */
    fun sampleText(limit: Int = 8): String = runCatching {
        val db = GroupDb.current() ?: return@runCatching "$TAG sample: db not ready"
        val rooms = db.allChatrooms()
        if (rooms.isEmpty()) return@runCatching "$TAG sample empty (cached=0)"
        val n = limit.coerceIn(1, 50)
        val sb = StringBuilder("$TAG sample total=${rooms.size}")
        for (f in rooms.take(n)) {
            sb.append("\n$TAG   ${f.wxid} => ${f.displayName}")
            // 逐字段摊开：displayName 是「备注>昵称>别名>wxid」的结果，
            // 只看它分不清「读没读到」和「读到了但优先级选错」。
            sb.append(" [nick='${f.nickname}' rem='${f.remark}' alias='${f.alias}' type=${f.type}]")
        }
        // ⭐ 直查 friend_cache 的原始存储值，绕开 Friend 封装。
        // 用来区分两种可能：① 写入就丢了（DB 里全空）② 写入正常、读取映射丢。
        runCatching {
            val raw = db.debugRawRows(
                "select wxid,nickname,remark,alias,wxid_type from friend_cache " +
                    "where wxid_type=2 limit $n"
            )
            sb.append("\n$TAG rawRows=${raw.size}")
            raw.forEach { sb.append("\n$TAG   RAW $it") }
        }.onFailure { sb.append("\n$TAG rawRows failed: ${cause(it)}") }
        sb.toString()
    }.onFailure { LogX.w("$TAG sample failed: ${cause(it)}") }.getOrDefault("$TAG sample unavailable")

    // ---------------------------------------------------------------- 装载

    /**
     * 全表扫 rcontact，按 `@chatroom` 后缀过滤。
     *
     * 用 `select *` + 按列名取（同 [ContactSqlSource] 的先例）：rcontact 列名跨版本
     * 偶有增减，固定列清单里只要一列名对不上，整条 rawQuery 直接抛 ⇒ 补读静默失败。
     * `select *` 永不因列名失败，读不到的单列降级为空。
     */
    private fun readChatrooms(db: Any, method: Method): List<Friend>? {
        val cursor = raw(db, method, "select * from $TABLE_CONTACT") ?: return null
        return runCatching {
            // username 是主键列，取不到说明 schema 对不上 → 明确失败，别伪装成「没群」
            val iUser = columnIndexOf(cursor, "username")
            if (iUser < 0) {
                LogX.w("$TAG rcontact schema mismatch: no 'username' column, refuse to read")
                return@runCatching null
            }
            val iNick = columnIndexOf(cursor, "nickname")
            val iRemark = columnIndexOf(cursor, "conRemark")
            val iAlias = columnIndexOf(cursor, "alias")

            val out = ArrayList<Friend>(256)
            var scanned = 0
            while (scanned < READ_MAX_ROWS && moveNext(cursor)) {
                scanned++
                val wxid = stringAt(cursor, iUser)?.trim().orEmpty()
                if (wxid.isEmpty()) continue
                if (!wxid.endsWith(GroupNameLogic.CHATROOM_SUFFIX)) continue
                val nickname = if (iNick >= 0) stringAt(cursor, iNick) else null
                val remark = if (iRemark >= 0) stringAt(cursor, iRemark) else null
                // ⚠️ 群名可能两个都空（真机存在这种群）。此时仍然**保留这一行**：
                // 选择器里显示可由 GroupNameLogic.displayNameOf 兜底成群号，
                // 而丢掉它等于「用户看不到这个群、也就永远选不到」。
                out += Friend(
                    wxid = wxid,
                    nickname = GroupNameLogic.normalize(nickname),
                    remark = GroupNameLogic.normalize(remark),
                    alias = if (iAlias >= 0) stringAt(cursor, iAlias).orEmpty() else "",
                    type = WxidKind.typeOf(wxid),
                )
            }
            LogX.i("$TAG scanned rows=$scanned chatrooms=${out.size}")
            out
        }.onFailure { LogX.w("$TAG read rcontact failed: ${cause(it)}") }
            .also { close(cursor) }
            .getOrNull()
    }

    // ---------------------------------------------------------------- 工具

    private fun prefs() = runCatching {
        appContext?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    }.getOrNull()

    private fun markFullSync() {
        runCatching {
            // commit 而非 apply：微信进程随时可能被杀，异步落盘可能来不及
            prefs()?.edit()?.putLong(KEY_LAST_FULL, System.currentTimeMillis())?.commit()
        }.onFailure { LogX.w("$TAG markFullSync failed: ${cause(it).take(80)}") }
    }

    private fun fail(reason: String) {
        failures.incrementAndGet()
        lastError = reason
        LogX.w("$TAG sync failed: $reason")
    }

    private fun typeName(type: Int): String = when (type) {
        GroupDb.WxidType.FRIEND -> "friend"
        GroupDb.WxidType.OFFICIAL_ACCOUNT -> "gh"
        GroupDb.WxidType.CHATROOM -> "chatroom"
        else -> "other"
    }

    /**
     * `rawQuery(String, String[]|Object[])` 的 Method。
     * ⚠️ 同 [AntiRevoke] / [GroupNameSource]：AOSP 的 Method 拿去调 wcdb 实例会抛
     * `IllegalArgumentException`，所以每次按 `db.javaClass` 现找，不跨库缓存。
     */
    private fun rawQueryMethod(db: Any): Method? = runCatching {
        db.javaClass.methods.firstOrNull {
            it.name == "rawQuery" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                (it.parameterTypes[1] == Array<String>::class.java ||
                    it.parameterTypes[1] == Array<Any>::class.java)
        }
    }.onFailure { LogX.w("$TAG rawQuery lookup failed: ${cause(it)}") }.getOrNull()

    private fun raw(db: Any, method: Method, sql: String): Any? = runCatching {
        method.invoke(db, sql, null)
    }.onFailure { LogX.w("$TAG query failed: ${cause(it)}") }.getOrNull()

    private fun columnIndexOf(cur: Any, name: String): Int =
        runCatching { XposedHelpers.callMethod(cur, "getColumnIndex", name) as? Int ?: -1 }
            .getOrDefault(-1)

    private fun moveNext(cur: Any): Boolean = runCatching {
        XposedHelpers.callMethod(cur, "moveToNext") as? Boolean ?: false
    }.onFailure { LogX.w("$TAG cursor move failed: ${cause(it)}") }.getOrDefault(false)

    private fun stringAt(cur: Any, column: Int): String? {
        if (column < 0) return null
        return runCatching { XposedHelpers.callMethod(cur, "getString", column) as? String }
            .onFailure { LogX.w("$TAG cursor get[$column] failed: ${cause(it)}") }
            .getOrNull()
    }

    private fun close(cursor: Any) {
        runCatching { XposedHelpers.callMethod(cursor, "close") }
            .onFailure { LogX.w("$TAG cursor close failed: ${cause(it)}") }
    }

    private fun cause(t: Throwable): String {
        val e = t.cause ?: t
        return "${e.javaClass.simpleName}: ${e.message?.take(100)}"
    }
}
