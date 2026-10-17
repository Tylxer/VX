package com.lx.wxgroup.feature

import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 微信标签数据源（宿主微信 8.0.48，code=2588，gate=gp）。
 *
 * 真机已确认：标签词典位于主库 `ContactLabel(labelID,labelName)`，联系人标签串位于
 * `rcontact(username,contactLabelIds)`。本对象只通过 [AntiRevoke.wxDb] 持有的微信 WCDB
 * 句柄执行 `SELECT`，绝不写微信库。
 *
 * 标签词典只有约 45 行，第一次拿到主库时在后台一次性载入；同时扫描 `rcontact` 建立
 * `username → 原始 id 串` 与 `标签 id → username` 两份内存索引。之后 [labelName]、
 * [labelsOf] 和 K3 选择器都只读缓存，不在主线程查库。
 *
 * `contactLabelIds` 的真实分隔符仍待下一轮真机确认；当前统一交给
 * [LabelLogic.splitIds] 做兼容解析，探针 `sub=of` 会原样打印串以便收敛。
 */
object LabelSource {

    private const val TAG = "LABEL"
    private const val TABLE_LABEL = "ContactLabel"
    private const val TABLE_CONTACT = "rcontact"

    data class Label(val id: String, val name: String?, val memberCount: Int)

    private val index = LabelLogic.Index()

    /** username → 原始 id 串；空串也保留，以区分「联系人无标签」和「没有这条联系人」。 */
    private val userIds: MutableMap<String, String> =
        Collections.synchronizedMap(HashMap<String, String>())

    /** 标签 id → username；每次成功装载后整体替换，读侧永远只看到完整快照。 */
    @Volatile
    private var members: Map<String, List<String>> = emptyMap()

    @Volatile
    private var available = false

    @Volatile
    private var loadedDb: Any? = null

    private val loading = AtomicBoolean(false)
    private val loadLock = Any()
    private val loads = AtomicLong(0)
    private val loadFailures = AtomicLong(0)
    private val labelHits = AtomicLong(0)
    private val labelMisses = AtomicLong(0)
    private val userHits = AtomicLong(0)
    private val userMisses = AtomicLong(0)

    /**
     * 主线程安全的预热入口：只起 daemon 后台线程，不等待查询结束。
     * [AntiRevoke] 第一次拿到主库时会调用；若当时失败，任一读接口会再次触发。
     */
    fun warmUp() {
        runCatching {
            val db = AntiRevoke.wxDb()
            if (available && db != null && loadedDb === db) return@runCatching
            if (!loading.compareAndSet(false, true)) return@runCatching
            Thread({
                try {
                    runCatching { loadBlocking(force = false) }
                        .onFailure { LogX.w("$TAG warm-up failed: ${cause(it)}") }
                } finally {
                    loading.set(false)
                }
            }, "wxg-label-source").apply { isDaemon = true }.start()
        }.onFailure { LogX.w("$TAG warm-up start failed: ${cause(it)}") }
    }

    /** id → 标签名；null 可能是未知 id，也可能已知但名为空，用 [hasLabel] 区分。 */
    fun labelName(id: String): String? = runCatching {
        warmUp()
        if (index.has(id)) {
            labelHits.incrementAndGet()
            index.nameOf(id)
        } else {
            labelMisses.incrementAndGet()
            null
        }
    }.onFailure { LogX.w("$TAG labelName failed: ${cause(it)}") }.getOrNull()

    /** 三态判据：true 表示 id 已知，不要求名字非空。 */
    fun hasLabel(id: String): Boolean = runCatching {
        warmUp()
        index.has(id)
    }.onFailure { LogX.w("$TAG hasLabel failed: ${cause(it)}") }.getOrDefault(false)

    /** 这个联系人身上的已命名标签；未知 id 与已知空名均不展示。 */
    fun labelsOf(userName: String): List<String> = runCatching {
        warmUp()
        val raw = cachedRaw(userName) ?: return@runCatching emptyList()
        LabelLogic.namesOf(raw, index).filter { it.isNotBlank() }
    }.onFailure { LogX.w("$TAG labelsOf failed: ${cause(it)}") }.getOrDefault(emptyList())

    /** 原始 id 串（诊断用，不能先 trim / split；主代理靠它确认真实分隔符）。 */
    fun rawIdsOf(userName: String): String? = runCatching {
        warmUp()
        cachedRaw(userName)
    }.onFailure { LogX.w("$TAG rawIdsOf failed: ${cause(it)}") }.getOrNull()

    /**
     * K3 使用的标签快照。已知空名仍保留在结果中，UI 自己决定是否展示；按数字 id 排序。
     */
    fun allLabels(): List<Label> = runCatching {
        warmUp()
        val memberSnapshot = members
        index.snapshot().map { (id, name) ->
            Label(id, name, memberSnapshot[id]?.size ?: 0)
        }.sortedWith(compareBy<Label> { it.id.toLongOrNull() ?: Long.MAX_VALUE }.thenBy { it.id })
    }.onFailure { LogX.w("$TAG allLabels failed: ${cause(it)}") }.getOrDefault(emptyList())

    /** K3 / 转发执行侧使用的反向索引快照，key 是稳定的标签 id。 */
    fun membersByLabelId(): Map<String, List<String>> = runCatching {
        warmUp()
        members
    }.onFailure { LogX.w("$TAG members failed: ${cause(it)}") }.getOrDefault(emptyMap())

    /** 数据词典与联系人索引都成功加载后才为 true；否则 K3 不显示「微信标签」段。 */
    fun ready(): Boolean = runCatching {
        warmUp()
        available && index.size() > 0
    }.onFailure { LogX.w("$TAG ready failed: ${cause(it)}") }.getOrDefault(false)

    /**
     * 后台诊断入口：同步完成一次装载。只能从探针的 `io {}` 等后台线程调用。
     * @param force true 时即使已有可用快照也重新读库。
     */
    fun refreshBlocking(force: Boolean = false): Boolean = runCatching {
        loadBlocking(force)
    }.onFailure { LogX.w("$TAG refresh failed: ${cause(it)}") }.getOrDefault(false)

    fun clear() {
        runCatching {
            available = false
            loadedDb = null
            index.clear()
            synchronized(userIds) { userIds.clear() }
            members = emptyMap()
        }.onFailure { LogX.w("$TAG clear failed: ${cause(it)}") }
    }

    fun stat(): String = runCatching {
        val users = synchronized(userIds) { userIds.size }
        val links = members.values.sumOf { it.size }
        "$TAG stat ready=${available && index.size() > 0} loading=${loading.get()} " +
            "labels=${index.size()} users=$users links=$links loads=${loads.get()} " +
            "loadFail=${loadFailures.get()} labelHit=${labelHits.get()} " +
            "labelMiss=${labelMisses.get()} userHit=${userHits.get()} userMiss=${userMisses.get()}"
    }.onFailure { LogX.w("$TAG stat failed: ${cause(it)}") }.getOrDefault("$TAG stat unavailable")

    // ---------------------------------------------------------------- 装载

    private fun cachedRaw(userName: String): String? {
        if (userName.isBlank()) {
            userMisses.incrementAndGet()
            return null
        }
        synchronized(userIds) {
            return if (userIds.containsKey(userName)) {
                userHits.incrementAndGet()
                userIds[userName]
            } else {
                userMisses.incrementAndGet()
                null
            }
        }
    }

    private fun loadBlocking(force: Boolean): Boolean = synchronized(loadLock) {
        runCatching {
            val db = AntiRevoke.wxDb()
            if (db == null) {
                available = false
                return@runCatching false
            }
            if (!force && available && loadedDb === db) return@runCatching true

            loads.incrementAndGet()
            val method = rawQueryMethod(db)
            if (method == null) {
                available = false
                loadFailures.incrementAndGet()
                LogX.w("$TAG load: rawQuery not found on ${db.javaClass.name}")
                return@runCatching false
            }

            val labels = readLabels(db, method)
            val users = readUsers(db, method)
            if (labels == null || users == null || labels.isEmpty()) {
                available = false
                loadFailures.incrementAndGet()
                LogX.w(
                    "$TAG load unavailable labels=${labels?.size ?: -1} " +
                        "users=${users?.size ?: -1}"
                )
                return@runCatching false
            }

            val reverse = reverseIndex(users)
            index.replaceAll(labels)
            synchronized(userIds) {
                userIds.clear()
                userIds.putAll(users)
            }
            members = reverse
            loadedDb = db
            available = true
            LogX.i(
                "$TAG loaded labels=${labels.size} users=${users.size} " +
                    "taggedUsers=${reverse.values.flatten().toSet().size} links=${reverse.values.sumOf { it.size }}"
            )
            true
        }.onFailure {
            available = false
            loadFailures.incrementAndGet()
            LogX.w("$TAG load failed: ${cause(it)}")
        }.getOrDefault(false)
    }

    /** 全量词典加载；空白 labelName 规范化为 null，保留 containsKey 的三态语义。 */
    private fun readLabels(db: Any, method: Method): Map<String, String?>? {
        val cursor = raw(db, method, "select labelID,labelName from $TABLE_LABEL") ?: return null
        return runCatching {
            val out = LinkedHashMap<String, String?>()
            while (moveNext(cursor)) {
                val id = stringAt(cursor, 0)?.trim().orEmpty()
                if (id.isEmpty()) continue
                val name = stringAt(cursor, 1)?.trim()?.takeIf { it.isNotEmpty() }
                out[id] = name
            }
            out
        }.onFailure { LogX.w("$TAG read ContactLabel failed: ${cause(it)}") }
            .also { close(cursor) }
            .getOrNull()
    }

    /** 一次性扫描联系人标签串；包含空串，避免把「无标签」误报成 cache miss。 */
    private fun readUsers(db: Any, method: Method): Map<String, String>? {
        val cursor = raw(db, method, "select username,contactLabelIds from $TABLE_CONTACT") ?: return null
        return runCatching {
            val out = HashMap<String, String>()
            while (moveNext(cursor)) {
                val user = stringAt(cursor, 0)?.trim().orEmpty()
                if (user.isEmpty()) continue
                out[user] = stringAt(cursor, 1).orEmpty()
            }
            out
        }.onFailure { LogX.w("$TAG read rcontact failed: ${cause(it)}") }
            .also { close(cursor) }
            .getOrNull()
    }

    private fun reverseIndex(users: Map<String, String>): Map<String, List<String>> {
        val out = LinkedHashMap<String, LinkedHashSet<String>>()
        for ((user, raw) in users) {
            for (id in LabelLogic.splitIds(raw)) {
                out.getOrPut(id) { LinkedHashSet() }.add(user)
            }
        }
        return out.mapValues { it.value.toList() }
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
