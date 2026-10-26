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
 * ## 根因（反汇编定案，见 `docs/_r21c_voice_sqlform.md` §1/§2）
 * 微信撤回语音时**先** `UPDATE message SET type=...,content=... WHERE msgId=?`（`j9.va`）
 * 改撤回形态，**再排程**（`executor.submit`，异步）`vj0.p` → `onPreDelMessage` →
 * `em0.v0.f`(deleteRecordFile) → `em0.e1.f`(VoiceStorage) →
 * `SQLiteDatabase.delete("voiceinfo", "FileName= ?", [imgPath])` 删下载记录。
 * [AntiRevoke] 只拦了第一步 ⇒ `message` 行还在（气泡在），但 `voiceinfo` 行被删 ⇒ 播放器按
 * `imgPath` 去 `voiceinfo` 拿 `Status/TotalLen/NetOffset` 查不到 ⇒ 点播放没反应。
 *
 * ⚠️ 两个**已证伪**的历史误判（别再写回去）：
 * 1. ~~撤回删 voiceinfo 走子查询 `FileName IN (SELECT imgPath ...)`~~ —— 那条子查询属于
 *    `MsgInfoStorageLogic.deleteMsgByTalkerSync`（`vj0.a9.j`），只被「删会话/清空记录」的
 *    34 个 UI 入口调用，**与撤回无任何调用关系**。撤回走的是**单行参数化 `FileName= ?`**。
 * 2. ~~DELETE 在 UPDATE 之前~~ —— 反汇编地址单调递增：`[532] j9.va`(UPDATE) 之后 `[576-579]`
 *    才 `new vj0.p` + `executor.submit`。⇒ `AntiRevoke.record()` 已完成，`rescued` 行必已存在。
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
 * - 这些 msgId 对应 `message.imgPath`（**26 位 hex**，FileName 匹配用）。
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

    /**
     * 受保护集合的缓存 TTL。
     *
     * ⚠️ 缓存能存在的前提是 [invalidate] 挂了 [RevokeRescue.record]（gen 版本号）——
     * **删掉那个失效通知，这个 TTL 就会变成漏拦窗口**。当前保留它的理由是
     * 微信可能对同一条 DELETE 重试，重复查库没必要；真机若发现缓存引发的漏拦，
     * 直接把本值改 0（等价于每次重查）即可，判据见 [VoiceGuardLogic.cacheUsable]。
     */
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

    /** 缓存构建时的记录版本号（见 [invalidate] 与 VoiceGuardLogic.cacheUsable 的 KDoc） */
    @Volatile
    private var cachedGen = 0L

    /** 每发生一次 `record()` 自增。缓存构建时快照它，之后不等即失效。 */
    @Volatile
    private var gen = 0L

    /**
     * 通知「台账里多了一条被救语音」⇒ 让受保护集合的缓存立即失效。
     *
     * ⛔ **必须由 [RevokeRescue.record] 调用**，否则 3 秒内连续撤回两条时，
     * 第二条的 DELETE 会命中第一条建下的缓存（不含 B）⇒ 放行 ⇒ B 播放失败。
     * 详见 [VoiceGuardLogic.cacheUsable] 的 KDoc。
     */
    fun invalidate() {
        gen++
    }

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
        // ⚠️ wcdb 的 `SQLiteDatabase` **没有** `deleteWithOnConflict`（与 AOSP 不同，
        //    已用 `tools/_r21c_verify_sql.py` 核方法表）。写库相关方法只有：
        //    delete / deleteDatabase / insert / insertOrThrow / insertWithOnConflict /
        //    update / updateWithOnConflict / execSQL×3 / rawQuery×2。所以下面那个 hook 返回空集、不会生效，
        //    日志只会是 `VG installed hooks=delete` —— **这是正常的，不是 hook 没装全**。
        //    保留它的唯一价值是兼容「若某版本 wcdb 补了这个方法」；delete() 本体是纯 Java
        //    （拼 'DELETE FROM <t> WHERE <w>' + SQLiteStatement.executeUpdateDelete()），
        //    param.result 在入口置位即可跳过方法体，所以真正生效的一定是 delete。
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

        // ⛔ R21c：**拦 SQL 只是第一步**。微信随后还会用 `File.delete()` 删掉
        //    `voice2/<md5桶>/msg_<imgPath>.amr` —— 那条路径不过 SQLite，本 hook 看不到。
        //
        // ⚠️ 这里这次 restore **只是「免费早触发」，不是兜底**（别把它当保障）：
        //    已定案的顺序是 voiceinfo DELETE **在前**、File.delete(磁盘) **在后**，
        //    所以本 hook 命中那一刻文件**还在** ⇒ shouldRestore(true, true) 恒 false
        //    ⇒ 直接 no-op。真正的兜底是 `VoiceBackup.scheduleRestore`
        //    （record() 里排的 +1s 幂等检查，删除链跑完之后才跑）。
        // ⚠️ 本项目**没有播放路径的 hook**，所以不存在「用户点播放前补一次」这种兜底。
        runCatching { VoiceBackup.restore(whereArgs?.firstOrNull()) }
            .onFailure { LogX.w("VG restore on block failed: ${it.javaClass.simpleName}") }

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
        val g = gen
        // 三个条件全成立才用缓存（见 VoiceGuardLogic.cacheUsable）：
        //   ① 非空  ② 构建后无新 record（gen 未变）  ③ 未过期
        // ⛔ 少了 ② 会漏拦「3 秒内连撤两条」的第二条。
        if (VoiceGuardLogic.cacheUsable(now, cacheAt, cachedImgs.size, CACHE_TTL_MS, cachedGen, g)) {
            return cachedImgs
        }
        val set = buildProtectedSet()
        cachedImgs = set
        cachedGen = g
        // 空集时把 cacheAt 记成「已过期」，下次必然重新查库
        cacheAt = VoiceGuardLogic.stampAfterBuild(now, set.size, CACHE_TTL_MS)
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

    /** 这些 msgId 在 message 表里的 imgPath（**26 位 hex**，FileName 匹配用）。查不到就空。 */
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
