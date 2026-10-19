package com.lx.wxgroup.feature

import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 群显示名数据源（宿主微信 8.0.48）。
 *
 * 2026-10-18 真机取证：`EnMicroMsg.db` 的 `rcontact` 表里，群聊的 `conRemark` 是
 * **空串**，群名在 `nickname`（5 条样本一致，见 [GroupNameLogic] 类注释）。
 * 本对象只通过 [AntiRevoke.wxDb] 持有的微信 WCDB 句柄执行 `SELECT`，绝不写微信库。
 *
 * 为什么不用 `chatroomFlag` 判群：那列确实存在（INTEGER），但本轮没取到取值，
 * 用未知语义的列判群等于猜。`username` 以 `@chatroom` 结尾是本轮 5 条样本一致的
 * 事实，且与全项目（[MuteAtLogic.CHATROOM_SUFFIX]）同一个口径。
 *
 * 分层与 [LabelSource] 一致：
 * - 装载体：后台 daemon 线程一次性全表扫描，`username → 群名` 灌进内存；
 * - 读侧 [nameOf]：**只读缓存**，不查库、不加锁等待、不阻塞调用线程 ——
 *   它会被通知链路（`MuteAt.shouldMute`）每一帧调用，任何一次查库都是主线程卡顿；
 * - 未就绪返回 `null`，调用方 [MuteAtLogic.groupInScope] 已能处理（退回 talker-only 匹配）。
 *
 * ⚠️ 缓存只存**非空值**，且装载完成后整体替换成一个不可变 Map：`ConcurrentHashMap`
 * 不允许 null value（本项目真机踩过 `NPE at putVal`），而「允许 null 的同步 map」会
 * 让主线程读缓存时跟后台装载抢同一把锁。三态语义由 [GroupNameLogic.pick] 在装载时
 * 收敛成「有名字 / 无名字」，读侧只需要「有 / 没有」。
 */
object GroupNameSource {

    private const val TAG = "GROUPNAME"
    private const val TABLE_CONTACT = "rcontact"

    /**
     * 失败后的重试间隔。装载失败时 `warmUp()` 会被通知链路反复调用，
     * 没有退避的话每条通知都会新建一个线程、再失败一次（真机上是几百个短命线程）。
     */
    private const val RETRY_BACKOFF_MS = 30_000L

    /**
     * username → 群名快照。装载完成后**整体替换**，读侧永远只看到完整快照。
     *
     * 用不可变快照而不是 `Collections.synchronizedMap`：通知链路每一帧都要读，
     * 加锁等于把主线程和被后台装载线程放到同一把锁上。也正因为只有非空值，
     * 才可以安全用 Map 而不是「允许 null 的同步 map」—— `ConcurrentHashMap`
     * 不允许 null value（本项目真机踩过 `NPE at putVal`）。
     */
    @Volatile
    private var names: Map<String, String> = emptyMap()

    @Volatile
    private var ready = false

    /** 已装载成功的库实例；换库（微信重建 WCDB）时据此重新装载。 */
    @Volatile
    private var loadedDb: Any? = null

    @Volatile
    private var queryMethod: Method? = null

    /** [queryMethod] 来自哪个类；换库且实现类不同（微信切 DB 实现）时必须重新找，否则永久失效。 */
    @Volatile
    private var queryClass: Class<*>? = null

    @Volatile
    private var nextAttemptAt = 0L

    @Volatile
    private var lastError: String? = null

    private val loading = AtomicBoolean(false)
    private val loadLock = Any()
    private val loads = AtomicLong(0)
    private val loadFailures = AtomicLong(0)

    /**
     * 主线程安全的预热：只起 daemon 线程，不等查询结束。
     *
     * 没有库实例时**不起线程**（`msgDb` 在微信第一次写库前恒为 null），
     * 否则模块刚注入那段时间会白起一堆线程、全部空转失败。
     */
    fun warmUp() {
        runCatching {
            val db = AntiRevoke.wxDb()
            if (db == null) return@runCatching
            if (ready && loadedDb === db) return@runCatching
            if (System.currentTimeMillis() < nextAttemptAt) return@runCatching
            if (!loading.compareAndSet(false, true)) return@runCatching
            val started = runCatching {
                Thread({
                    try {
                        runCatching { loadBlocking() }
                            .onFailure { LogX.w("$TAG warm-up failed: ${cause(it)}") }
                    } finally {
                        loading.set(false)
                    }
                }, "wxg-group-name").apply { isDaemon = true }.start()
            }
            // 线程起不来（极端情况下 Thread.start 抛 OOM）时必须把门闩放回去，
            // 否则 loading 永久为 true，本次微信生命周期内再也不会重试。
            if (started.isFailure) {
                loading.set(false)
                val why = started.exceptionOrNull()?.javaClass?.simpleName
                LogX.w("$TAG warm-up thread failed: $why")
            }
        }.onFailure { LogX.w("$TAG warm-up start failed: ${cause(it)}") }
    }

    /**
     * chatroomId → 群显示名，未知返回 null。
     *
     * 读侧不查库：缓存未就绪、或这个群没名字（真机上存在 `nickname` 也空的群）、
     * 或传入的不是群 id，都返回 null。
     */
    fun nameOf(chatroomId: String): String? = runCatching {
        val room = chatroomId.trim()
        if (!room.endsWith(GroupNameLogic.CHATROOM_SUFFIX)) return@runCatching null
        warmUp()
        names[room]
    }.onFailure { LogX.w("$TAG nameOf failed: ${cause(it)}") }.getOrNull()

    /** 后台诊断入口：同步完成一次装载，只能从探针的 `io {}` 等后台线程调用。 */
    fun refreshBlocking(force: Boolean = false): Boolean = runCatching {
        loadBlocking(force)
    }.onFailure { LogX.w("$TAG refresh failed: ${cause(it)}") }.getOrDefault(false)

    fun clear() {
        runCatching {
            ready = false
            loadedDb = null
            lastError = null
            nextAttemptAt = 0L
            names = emptyMap()
        }.onFailure { LogX.w("$TAG clear failed: ${cause(it)}") }
    }

    /** 一行状态串，探针直接打印。 */
    fun stat(): String = runCatching {
        val count = names.size
        "$TAG stat ready=$ready loading=${loading.get()} count=$count " +
            "loads=${loads.get()} loadFail=${loadFailures.get()} " +
            "lastError=${lastError ?: "-"}"
    }.onFailure { LogX.w("$TAG stat failed: ${cause(it)}") }.getOrDefault("$TAG stat unavailable")

    /**
     * 打若干个群名样本（探针 `sub=at sub2=grplist` 用）。
     *
     * 存在的意义：本轮「群聊 conRemark 为空、群名在 nickname」是靠 5 条样本得出的，
     * 而**用户手动备注过群**之后 conRemark 到底存什么，本轮没有样本。
     * 这个子命令让人能在真机上挑一个自己备注过的群，看 [GroupNameLogic.pick] 取出来的
     * 是不是自己认的那个名字 —— 取错了说明备注的语义与好友不同，取值顺序要调。
     */
    fun sampleText(limit: Int = 8): String = runCatching {
        val n = limit.coerceIn(1, 50)
        val snap = names
        if (snap.isEmpty()) return@runCatching "$TAG sample empty (ready=$ready lastError=${lastError ?: "-"})"
        val sb = StringBuilder("$TAG sample total=${snap.size}")
        var i = 0
        for ((k, v) in snap) {
            if (i++ >= n) break
            sb.append("\n$TAG   $k => $v")
        }
        sb.toString()
    }.onFailure { LogX.w("$TAG sample failed: ${cause(it)}") }.getOrDefault("$TAG sample unavailable")

    // ---------------------------------------------------------------- 装载

    private fun loadBlocking(force: Boolean = false): Boolean = synchronized(loadLock) {
        runCatching {
            val db = AntiRevoke.wxDb()
            if (db == null) {
                ready = false
                return@runCatching false
            }
            if (!force && ready && loadedDb === db) return@runCatching true

            loads.incrementAndGet()
            val method = queryMethod?.takeIf { queryClass == db.javaClass }
                ?: rawQueryMethod(db)?.also {
                    queryMethod = it
                    queryClass = db.javaClass
                }
            if (method == null) {
                fail("rawQuery not found on ${db.javaClass.name}")
                return@runCatching false
            }

            val loaded = readGroups(db, method)
            if (loaded == null) {
                fail("query rcontact failed")
                return@runCatching false
            }
            if (loaded.isEmpty()) {
                // 0 行说明表结构/后缀（数据层事实）与本轮取证不一致，不能当成「成功但没群」，
                // 否则 ready=true 会永久关掉重试，群名匹配在整个微信生命周期内静默失效。
                fail("0 chatroom rows")
                return@runCatching false
            }

            names = loaded
            loadedDb = db
            ready = true
            lastError = null
            nextAttemptAt = 0L
            LogX.i("$TAG loaded groups=${loaded.size}")
            true
        }.onFailure { fail(cause(it)) }.getOrDefault(false)
    }

    /**
     * 全表扫一遍 `rcontact` 再按 `@chatroom` 后缀过滤。
     *
     * 不用 `where username like '%@chatroom'`：LIKE 的 `%` 语义在微信自带的
     * SQLCipher 分支上没验证过，而全表扫一遍的代价与 [LabelSource] 已经做过的事同级
     * （同一张表、一次性、后台线程），换来的是不依赖未验证的 SQL 行为。
     */
    private fun readGroups(db: Any, method: Method): Map<String, String>? {
        val cursor = raw(
            db,
            method,
            "select username,nickname,conRemark from $TABLE_CONTACT",
        ) ?: return null
        return runCatching {
            val out = HashMap<String, String>()
            var scanned = 0
            while (moveNext(cursor)) {
                scanned++
                val user = stringAt(cursor, 0)?.trim().orEmpty()
                if (!user.endsWith(GroupNameLogic.CHATROOM_SUFFIX)) continue
                // pick() 已经把「空备注 → 退昵称 → 都空返回 null」收敛好，这里不再判空
                val name = GroupNameLogic.pick(stringAt(cursor, 2), stringAt(cursor, 1))
                if (name != null) out[user] = name
            }
            LogX.i("$TAG scanned rows=$scanned")
            out
        }.onFailure { LogX.w("$TAG read rcontact failed: ${cause(it)}") }
            .also { close(cursor) }
            .getOrNull()
    }

    private fun fail(reason: String) {
        ready = false
        loadFailures.incrementAndGet()
        lastError = reason
        nextAttemptAt = System.currentTimeMillis() + RETRY_BACKOFF_MS
        LogX.w("$TAG load failed: $reason (retry in ${RETRY_BACKOFF_MS / 1000}s)")
    }

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

    private fun moveNext(cursor: Any): Boolean = runCatching {
        XposedHelpers.callMethod(cursor, "moveToNext") as? Boolean ?: false
    }.onFailure { LogX.w("$TAG cursor move failed: ${cause(it)}") }.getOrDefault(false)

    private fun stringAt(cursor: Any, column: Int): String? = runCatching {
        XposedHelpers.callMethod(cursor, "getString", column) as? String
    }.onFailure { LogX.w("$TAG cursor get[$column] failed: ${cause(it)}") }.getOrNull()

    private fun close(cursor: Any) {
        runCatching { XposedHelpers.callMethod(cursor, "close") }
            .onFailure { LogX.w("$TAG cursor close failed: ${cause(it)}") }
    }

    private fun cause(t: Throwable): String {
        val e = t.cause ?: t
        return "${e.javaClass.simpleName}: ${e.message?.take(100)}"
    }
}
