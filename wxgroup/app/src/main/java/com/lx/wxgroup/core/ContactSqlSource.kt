package com.lx.wxgroup.core

import android.content.Context
import com.lx.wxgroup.data.ContactFilter
import com.lx.wxgroup.data.Friend
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.WxidKind
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * R21：rcontact 直读源 —— 把通讯录 UI 不渲染的「企业微信好友（@openim）」补进 friend_cache。
 *
 * ## 为什么需要它
 * [ContactScanner.scan] 从通讯录列表 adapter 的对象图 BFS 捞 `com.tencent.mm.storage.h4`，
 * 而 `@openim` 联系人**不在通讯录 UI 渲染**（微信把它们放在独立入口，不进 AddressLiveList），
 * 所以对象图里永远扫不到 ⇒ `friend_cache` 恒缺 ⇒ BatchPicker 搜「Lynsey」搜不到。
 * 这些联系人完整存在于微信主库 `EnMicroMsg.db` 的 `rcontact` 表里（真机：`@openim` 254 条）。
 *
 * ## 怎么拿库句柄（复用 SnsGuard 已跑通的通路）
 * hook `com.tencent.wcdb.database.SQLiteDatabase.rawQuery`，在看到「SQL 含 rcontact」且
 * `getPath()` 以 `EnMicroMsg.db` 结尾时，记下这个 db 实例（[mainDb]），**拿到即卸载钩子**。
 * 这是 SnsGuard 捕获 SnsMicroMsg.db 的同一套办法，已在真机验证可行。
 *
 * ## 为什么是「补充直读」而不是「全量替换」
 * rcontact 是微信的关系流水账，除了通讯录好友还装着群成员/已删好友/公众号/陌生人，
 * 全量替换要么按 type 硬筛（已证不可行），要么把上万条非好友灌进 friend_cache。
 * 所以只读 `username like '%@openim'` 这一窄片（254 条，全部是真好友），
 * 用 [GroupDb.upsertFriends] **合并**进现有 2257 条普通好友，谁也不覆盖谁。
 *
 * ## 触发时机（不依赖用户操作）
 * 微信启动时必查 rcontact（会话列表渲染要读联系人名），所以 [mainDb] 会在启动后几秒内
 * 自动捕获，捕获成功即后台补读一次。⚠️ 依赖一条硬前提：**微信自己先碰过 EnMicroMsg.db**
 * —— 若某个启动过程微信从头到尾都不查 rcontact（理论上不会），钩子就永远拿不到句柄，
 * 此时退化为现状（不影响已有 2257 条普通好友）。另在 [ContactSync.doSync] 里挂了一个
 * 兜底 [trySupplement]，用户打开通讯录时若句柄已捕获但还没补成功，会再试一次。
 */
object ContactSqlSource {

    /** 主消息库 EnMicroMsg.db —— rcontact 只在这里（其余 7 个库都 `no such table`） */
    private const val DB_MARK = "EnMicroMsg.db"

    /** 目标表。⚠️ 认库 + 认表双保险：光认路径不够，路径对了还得看见 rcontact 查询才动手 */
    private const val TABLE = "rcontact"

    /** 只补这一形态：企业微信好友（判据见 ContactFilter.needsRcontactSupplement） */
    private const val SUPPLEMENT_LIKE = "%@openim"

    /** 认输上限：这么多 rawQuery 里都没见过 rcontact 就卸载（防常驻） */
    private const val CAPTURE_MAX_CALLS = 20_000

    /** 单次读的行数上限（真机 254 条，留 10 倍余量兜异常膨胀） */
    private const val READ_MAX_ROWS = 5_000

    @Volatile
    private var mainDb: Any? = null

    @Volatile
    private var supplementDone = false

    @Volatile
    private var installed = false

    @Volatile
    private var gaveUp = false

    @Volatile
    private var pathWarned = false

    private val captureCalls = AtomicInteger(0)
    private val reading = AtomicBoolean(false)

    private val captureUnhooks =
        Collections.synchronizedList(ArrayList<XC_MethodHook.Unhook>())

    // ------------------------------------------------------------------ 安装

    /**
     * 装句柄捕获钩子。由 [ContactSync.init] 在 bootstrap 里调一次。
     *
     * ⚠️ 用 `context.classLoader` 找 wcdb 的 SQLiteDatabase：等价于宿主 PathClassLoader
     * （Application 的 classLoader 就是它），不用额外传 lpparam。
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val cls = runCatching {
            XposedHelpers.findClass("com.tencent.wcdb.database.SQLiteDatabase", context.classLoader)
        }.getOrNull()
        if (cls == null) {
            LogX.w("CSQL: wcdb SQLiteDatabase not found, rcontact supplement disabled")
            return
        }
        if (mainDb != null) return
        val set = runCatching { XposedBridge.hookAllMethods(cls, "rawQuery", captureHook) }
            .onFailure { LogX.w("CSQL: hook rawQuery failed: ${it.javaClass.simpleName}") }
            .getOrNull()
        if (set.isNullOrEmpty()) {
            LogX.w("CSQL: no rawQuery overload to hook, rcontact supplement disabled")
            return
        }
        synchronized(captureUnhooks) { captureUnhooks.addAll(set) }
        LogX.i("CSQL capture hook installed rawQuery x${set.size} (拿到 EnMicroMsg.db 即卸载)")
    }

    private val captureHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                // 已经拿到 / 已经认输 → 卸掉自己（这几行就是卸载前的全部开销）
                if (mainDb != null || gaveUp) {
                    unhookCapture()
                    return
                }
                if (captureCalls.incrementAndGet() > CAPTURE_MAX_CALLS) {
                    gaveUp = true
                    unhookCapture()
                    LogX.w("CSQL capture gave up after $CAPTURE_MAX_CALLS rawQuery (rcontact 直读不可用)")
                    return
                }
                val sql = param.args?.getOrNull(0) as? String ?: return
                if (!sql.contains(TABLE)) return
                val db = param.thisObject ?: return
                if (isMainDb(db)) {
                    LogX.i("CSQL EnMicroMsg.db captured via rawQuery (rcontact 直读已可用)")
                    unhookCapture()
                    scheduleSupplement()
                }
            }.onFailure {
                // 捕获是附加收益，绝不能因为它把微信的查询搞挂
                LogX.w("CSQL capture err: ${it.javaClass.simpleName}")
            }
        }
    }

    /**
     * 是不是主消息库。⚠️ 只在「SQL 已含 rcontact」之后才调（getPath 是反射，不能每次 rawQuery 都付）。
     * 命中时把句柄记进 [mainDb]（顺带用路径二次校验）。
     */
    private fun isMainDb(db: Any): Boolean {
        val p = runCatching { XposedHelpers.callMethod(db, "getPath") as? String }
            .onFailure {
                if (!pathWarned) {
                    pathWarned = true
                    LogX.w("CSQL getPath failed on ${db.javaClass.name}: ${it.javaClass.simpleName}")
                }
            }
            .getOrNull()
        if (p == null) {
            if (!pathWarned) {
                pathWarned = true
                LogX.w("CSQL getPath returned null on ${db.javaClass.name}")
            }
            return false
        }
        return if (p.endsWith(DB_MARK)) {
            mainDb = db
            true
        } else {
            false
        }
    }

    private fun unhookCapture() {
        val list = synchronized(captureUnhooks) {
            if (captureUnhooks.isEmpty()) return
            ArrayList(captureUnhooks).also { captureUnhooks.clear() }
        }
        for (u in list) runCatching { u.unhook() }
    }

    // ------------------------------------------------------------------ 补读

    /** 句柄是否已捕获（= rcontact 直读这条路现在能不能走） */
    fun ready(): Boolean = mainDb != null

    /**
     * 兜底触发：句柄已捕获但还没补成功时，再试一次。
     * 由 [ContactSync.doSync] 在用户打开通讯录时调用（低频，不会刷屏）。
     */
    fun trySupplement() {
        if (mainDb == null || supplementDone) return
        scheduleSupplement()
    }

    private fun scheduleSupplement() {
        if (supplementDone) return
        if (!reading.compareAndSet(false, true)) return // 已有一轮在读
        Thread({
            runCatching { doSupplement() }
                .onFailure { LogX.e("CSQL supplement failed", it) }
            reading.set(false)
        }, "wxgroup-csql").start()
    }

    private fun doSupplement() {
        val db = mainDb ?: return
        val m = rawQueryMethod(db) ?: run {
            LogX.w("CSQL no rawQuery on ${db.javaClass.name}")
            return
        }
        // 参数化：值里的 % 由 SQLite 按 LIKE 通配符解释，行为等价于内联 '%@openim'
        val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
            arrayOf(SUPPLEMENT_LIKE)
        } else {
            arrayOf<Any>(SUPPLEMENT_LIKE)
        }
        // ⚠️ 用 select * + 按列名取（同 SnsGuard.readRow 先例）：rcontact 列名跨版本偶有增减，
        // 固定列清单（如 select username,nickname,…）里只要有一列名对不上，整条 rawQuery 直接抛
        // 异常 → 补读静默失败。select * 永远不会因列名失败，读不到的单列走 getColumnIndex=-1
        // 优雅降级为空，不影响 username 这个主键列。
        val sql = "select * from $TABLE where username like ?"
        val cur = runCatching { m.invoke(db, sql, args) }
            .onFailure {
                LogX.w(
                    "CSQL rcontact query failed ${it.javaClass.simpleName}: " +
                        "${(it.cause ?: it).message?.take(80)}"
                )
            }
            .getOrNull() ?: return

        val friends = ArrayList<Friend>(256)
        try {
            // username 是主键列，schema 对不上时 getColumnIndex 返回 -1 → 明确告警并放弃，
            // 而不是把「读不到」伪装成「没有 @openim」
            if (columnIndexOf(cur, "username") < 0) {
                LogX.w("CSQL rcontact schema mismatch: no 'username' column, refuse to read")
                return
            }
            var guard = 0
            while (guard++ < READ_MAX_ROWS &&
                XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true
            ) {
                val wxid = cText(cur, "username") ?: continue
                if (wxid.isBlank()) continue
                // 把真实行喂给纯判据（type/verifyFlag/deleteFlag/chatroomFlag 会读出来但被
                // 判据显式忽略，见 ContactFilter.shouldSupplementFromRcontact 的 KDoc）
                val row = ContactFilter.RcontactRow(
                    username = wxid,
                    nickname = cText(cur, "nickname") ?: "",
                    conRemark = cText(cur, "conRemark") ?: "",
                    type = cLong(cur, "type").toInt(),
                    verifyFlag = cLong(cur, "verifyFlag").toInt(),
                    deleteFlag = cLong(cur, "deleteFlag").toInt(),
                    chatroomFlag = cLong(cur, "chatroomFlag").toInt(),
                )
                // 双保险：SQL 已 like '%@openim'，这里再按纯判据拦一次，防止取错列混进脏数据
                if (!ContactFilter.shouldSupplementFromRcontact(row)) continue
                friends += Friend(
                    wxid = row.username,
                    nickname = row.nickname,
                    remark = row.conRemark,
                    alias = cText(cur, "alias") ?: "",
                    type = WxidKind.typeOf(row.username),
                )
            }
        } finally {
            runCatching { XposedHelpers.callMethod(cur, "close") }
        }

        if (friends.isEmpty()) {
            LogX.w("CSQL supplement: 0 @openim rows (rcontact 里没有企业微信好友)")
            supplementDone = true
            return
        }
        val db0 = GroupDb.current()
        if (db0 == null) {
            LogX.w("CSQL supplement: GroupDb not ready, keep supplementDone=false for retry")
            return
        }
        val before = db0.countFriends()
        val written = db0.upsertFriends(friends)
        val after = db0.countFriends()
        supplementDone = true
        LogX.i(
            "CSQL supplement: read=${friends.size} upsert=$written " +
                "db $before -> $after types=${db0.friendTypeCounts().entries.joinToString { "${it.key}=${it.value}" }}"
        )
    }

    // ------------------------------------------------------------------ 反射

    /**
     * rawQuery(String, String[]|Object[]) 的 Method。
     * ⚠️ 同 SnsGuard：AOSP 的 Method 拿去调 wcdb 实例会抛 IllegalArgumentException，
     * 所以每次按 `db.javaClass` 现找，不跨库缓存（这里每次进程只调一次，无性能顾虑）。
     */
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

    private fun columnIndexOf(cur: Any, name: String): Int =
        runCatching { XposedHelpers.callMethod(cur, "getColumnIndex", name) as? Int ?: -1 }
            .getOrDefault(-1)

    private fun cLong(cur: Any, name: String): Long {
        val i = columnIndexOf(cur, name)
        if (i < 0) return 0L
        return runCatching { XposedHelpers.callMethod(cur, "getLong", i) as? Long ?: 0L }
            .getOrDefault(0L)
    }

    private fun cText(cur: Any, name: String): String? {
        val i = columnIndexOf(cur, name)
        if (i < 0) return null
        return runCatching { XposedHelpers.callMethod(cur, "getString", i) as? String }
            .getOrNull()
    }
}
