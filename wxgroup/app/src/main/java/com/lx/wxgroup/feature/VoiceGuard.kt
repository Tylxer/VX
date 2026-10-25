package com.lx.wxgroup.feature

import android.os.SystemClock
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.data.VoiceGuardLogic
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 防撤回语音后的「播放失败」修复 —— 拦 voiceinfo 的 DELETE（需求 6.2 的补丁）。
 *
 * ## 根因（主会话真机定案，见 `docs/_r21c_voice_root.md`）
 * 微信撤回语音时**先** `DELETE FROM voiceinfo WHERE FileName IN (SELECT imgPath FROM message ...)`
 * 删掉语音下载记录，**再** `UPDATE message` 改撤回形态。[AntiRevoke] 只拦了第二步，
 * 第一步的 DELETE 没拦 ⇒ `message` 行还在（气泡在），但 `voiceinfo` 行没了 ⇒ 播放器按
 * `imgPath` 去 `voiceinfo` 拿 `Status/TotalLen/NetOffset` 查不到 ⇒ 点播放没反应。
 *
 * ## 策略：方案 A（伪装成功，不真执行）
 * 命中判据时 `param.result = 1`（同 [AntiRevoke] 拦 update 的思路，最省）。
 * 微信以为删了、实际没删。风险是微信可能重试 —— 用 [blockedLog] 节流日志即可
 * （DELETE 本身幂等，重试删不存在的行无害）。
 *
 * ## ⛔ 为什么不能无条件拦
 * voiceinfo 也用于微信正常语音清理（删过期语音、空间不足 GC）。无条件拦会让 GC 失效、
 * 存储只增不减。所以判据在 [VoiceGuardLogic.shouldBlock]（纯函数，有 JVM 单测），
 * 这里只负责「把最近 N 秒内刚 record 过的语音」聚成受保护集合喂进去。
 *
 * ## 受保护集合怎么来（时间窗）
 * - `rescued` 表里 `orig_type=34`（语音）且 `at` 在最近 [WINDOW_MS] 内的 msgId；
 * - 这些 msgId 对应 `message.imgPath`（24 位 hex，FileName 匹配用）。
 * ⚠️ `GroupDb.Rescued` 没暴露 `at`/`imgPath`，所以这里直读 `rescued` 表的 `msg_id/at`
 * （模块自己的 AOSP 库）+ 反查 message 表拿 `imgPath`。这是对 GroupDb 内部 schema 的
 * 轻耦合，若将来 GroupDb 补了公开 API，本类可换过去。
 */
object VoiceGuard {

    private const val TABLE = "voiceinfo"
    private const val TYPE_VOICE = 34

    /**
     * 时间窗：只拦「最近 [WINDOW_MS] 内刚 record 过」的语音，避免误拦历史清理。
     *
     * 取值 60s = [AntiRevoke.BLOCK_TTL_MS]，理由见 `docs/_r21c_voice_fix.md` §1。
     */
    private const val WINDOW_MS = 60_000L

    /** 受保护集合的缓存刷新间隔（DELETE 低频，缓存几秒既能抗重试风暴又不会太旧） */
    private const val CACHE_TTL_MS = 3_000L

    /** 伪装返回值：微信预期 delete 返回删掉的行数，返回 1 比 0 更像成功 */
    private const val FAKE_ROWS = 1

    @Volatile
    private var enabled = false

    @Volatile
    private var cfgAt = 0L

    @Volatile
    private var cacheAt = 0L

    @Volatile
    private var cachedImgs: Set<String> = emptySet()

    /** 节流日志：同一 key N 秒内只打一条（重试风暴会反复命中同一条 delete） */
    private val blockedLog = ConcurrentHashMap<String, Long>()
    private const val LOG_TTL_MS = 60_000L

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cls = runCatching {
            XposedHelpers.findClass("com.tencent.wcdb.database.SQLiteDatabase", lpparam.classLoader)
        }.getOrNull()
        if (cls == null) {
            LogX.w("VG: wcdb SQLiteDatabase not found, voice guard disabled")
            return
        }
        runCatching { refresh() }
            .onFailure { LogX.w("VG refresh failed: ${it.javaClass.simpleName}") }

        val ok = mutableListOf<String>()
        // delete() 内部转调 deleteWithOnConflict（同 update→updateWithOnConflict），
        // 挂两个是为了兼容「删走 execSQL 之外的 delete 直调」；命中时在 delete 入口就
        // 置 result 跳过原方法，所以不会双重处理（详情见类注释）。
        runCatching { XposedBridge.hookAllMethods(cls, "deleteWithOnConflict", deleteGuard) }
            .onSuccess { if (it.isNotEmpty()) ok += "deleteWithOnConflict" }
            .onFailure { LogX.e("VG: hook deleteWithOnConflict failed", it) }
        runCatching { XposedBridge.hookAllMethods(cls, "delete", deleteGuard) }
            .onSuccess { if (it.isNotEmpty()) ok += "delete" }
            .onFailure { LogX.e("VG: hook delete failed", it) }
        if (ok.isEmpty()) {
            LogX.w("VG: no delete hook installed, voice guard disabled")
            return
        }
        LogX.i("VG installed hooks=${ok.joinToString(",")} enabled=$enabled")
    }

    fun refresh() {
        enabled = Kv.getBool(Keys.ANTIREVOKE_MSG)
        cfgAt = SystemClock.elapsedRealtime()
    }

    private fun cfg(): Boolean {
        if (SystemClock.elapsedRealtime() - cfgAt > 3_000) refresh()
        return enabled
    }

    private val deleteGuard = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // 拦截钩子绝不能把异常抛回宿主：失败就是「这次不拦」
            runCatching { onDelete(param) }
                .onFailure { LogX.w("VG delete hook err: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * `delete(String table, String whereClause, String[]|Object[] whereArgs)`，
     * 以及 `deleteWithOnConflict(...)`（多一个 int conflict 在末尾，位置 0/1/2 一致）。
     * wcdb 两个重载的第三个参数类型不同，按位置取、不按类型取。
     */
    private fun onDelete(param: XC_MethodHook.MethodHookParam) {
        val a = param.args ?: return
        if (a.isEmpty()) return
        // 最便宜的判断最先做：不是 voiceinfo 就放行，别去建受保护集合（要查库）
        val table = a[0] as? String ?: return
        if (!table.equals(TABLE, ignoreCase = true)) return
        if (!cfg()) return

        val whereClause = a.getOrNull(1) as? String
        val whereArgs: Array<String>? = (a.getOrNull(2) as? Array<*>)
            ?.map { it?.toString() ?: "" }
            ?.toTypedArray()

        val protected = protectedSet()
        if (!VoiceGuardLogic.shouldBlock(table, whereClause, whereArgs, protected)) return

        // 命中：方案 A —— 伪装成功、不真执行（跳过原 deleteWithOnConflict 方法体）
        param.result = FAKE_ROWS
        val key = (whereClause ?: "") + "|" + whereArgs?.joinToString(",")
        val now = SystemClock.elapsedRealtime()
        val last = blockedLog[key]
        if (last == null || now - last > LOG_TTL_MS) {
            blockedLog[key] = now
            LogX.i(
                "VG blocked voiceinfo delete " +
                    "where=${whereClause?.take(40)} args=${whereArgs?.joinToString(",")?.take(64)}"
            )
        }
    }

    // ------------------------------------------------------------ 受保护集合

    private fun protectedSet(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - cacheAt < CACHE_TTL_MS) return cachedImgs
        val set = buildProtectedSet()
        cachedImgs = set
        cacheAt = now
        return set
    }

    private fun buildProtectedSet(): Set<String> {
        val out = HashSet<String>()
        val msgIds = recentVoiceMsgIds()
        out.addAll(msgIds)
        out.addAll(imgPathsOf(msgIds))
        return out
    }

    /** 最近 [WINDOW_MS] 内 record 过的语音 msgId（直读模块自己的 rescued 表，AOSP SQLite） */
    private fun recentVoiceMsgIds(): List<String> {
        val gdb = GroupDb.current() ?: return emptyList()
        val db = runCatching { gdb.readableDatabase }.getOrNull() ?: return emptyList()
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        val out = ArrayList<String>()
        runCatching {
            db.query(
                "rescued", arrayOf("msg_id"), "orig_type=? AND at>?",
                arrayOf(TYPE_VOICE.toString(), cutoff.toString()),
                null, null, null
            ).use { c ->
                while (c.moveToNext()) out.add(c.getLong(0).toString())
            }
        }.onFailure { LogX.w("VG recentVoiceMsgIds failed: ${it.javaClass.simpleName}") }
        return out
    }

    /** 这些 msgId 在 message 表里的 imgPath（24 位 hex，FileName 匹配用）。查不到就空。 */
    private fun imgPathsOf(msgIds: List<String>): Set<String> {
        if (msgIds.isEmpty()) return emptySet()
        val db = AntiRevoke.wxDb() ?: return emptySet()
        val m = rawQueryMethod(db) ?: return emptySet()
        val placeholders = msgIds.joinToString(",") { "?" }
        val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
            msgIds.toTypedArray()
        } else {
            msgIds.map { it as Any }.toTypedArray()
        }
        val out = HashSet<String>()
        runCatching {
            val cur = m.invoke(
                db,
                AntiRevokeHint.BYPASS + "select imgPath from message where msgId in ($placeholders)",
                args
            ) ?: return@runCatching
            try {
                while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                    val p = XposedHelpers.callMethod(cur, "getString", 0) as? String
                    if (!p.isNullOrBlank()) out.add(p.lowercase())
                }
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
        }.onFailure { LogX.w("VG imgPathsOf failed: ${it.javaClass.simpleName}") }
        return out
    }

    // ------------------------------------------------------------------ 反射

    private var rawQueryRef: Method? = null

    private fun rawQueryMethod(db: Any): Method? {
        rawQueryRef?.let { return it }
        val m = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "rawQuery" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    (it.parameterTypes[1] == Array<String>::class.java ||
                        it.parameterTypes[1] == Array<Any>::class.java)
            }
        }.getOrNull()
        rawQueryRef = m
        return m
    }
}
