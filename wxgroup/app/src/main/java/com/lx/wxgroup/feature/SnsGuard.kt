package com.lx.wxgroup.feature

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
 * ## 所以策略是「写时拦截」（R42 路线甲），不再做「拦 + 归档」
 * - 微信清时间线可见位（`sourceType` bit1 置 0）的**真实通道**是
 *   `update("SnsInfo", {sourceType=0})` → `updateWithOnConflict`，**不是** `DELETE`；
 * - 在 `updateWithOnConflict` 的 **before** 里把 `sourceType` 这个键摘掉，清位不落地，
 *   行留在库里、`sourceType` 保持旧值 → 朋友圈界面上它照常显示；
 * - 同一行 `sourceType` 永远不会落成 0，因此一切以「库里出现 0」为触发条件的
 *   事后补位/归档恢复子系统都随乙方案删除，不再存在。
 * - 6.4「自动导出朋友圈动态」改为**预防性留存**：动态还看得见时就把图留存下来（扫当前表）。
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

    private data class Config(
        /** R42 路线甲：`updateWithOnConflict` **before** 里摘掉 `sourceType`（写时拦截）。 */
        val blockEnabled: Boolean,
    )

    @Volatile
    private var config = Config(false)

    @Volatile
    private var cfgAt = 0L

    /** 朋友圈库实例，扫当前 SnsInfo（导出）要用；只在命中 execSQL/rawQuery 时顺手记下 */
    @Volatile
    private var snsDb: Any? = null

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
        // ✅ R42 路线甲实测：**同一方法的 before 钩子**（再挂一个回调，Xposed 允许多个）。
        //    ⛔ 与上面那条 after 钩子互不影响：上面那条只读 values 做补位排程，这条只摘键。
        runCatching { XposedBridge.hookAllMethods(cls, "updateWithOnConflict", snsBlockGuard) }
            .onSuccess { if (it.isNotEmpty()) ok += "updateWithOnConflict.before" }
            .onFailure { LogX.e("SNS: hook updateWithOnConflict before failed", it) }
        // ✅ R41：新动态首次落库走 INSERT（不走 updateWithOnConflict），账本采样
        //   必须补插入路径，否则「本轮有新内容」时 subset 恒 false（见 [snsInsertGuard]）。
        runCatching { XposedBridge.hookAllMethods(cls, "insertWithOnConflict", snsInsertGuard) }
            .onSuccess { if (it.isNotEmpty()) ok += "insertWithOnConflict" }
            .onFailure { LogX.e("SNS: hook insertWithOnConflict failed", it) }
        runCatching { XposedBridge.hookAllMethods(cls, "insert", snsInsertGuard) }
            .onSuccess { if (it.isNotEmpty()) ok += "insert" }
            .onFailure { LogX.e("SNS: hook insert failed", it) }
        // ✅ R20：**句柄捕获钩子**（拿到即自我卸载，见 [installCapture]）。
        //   它不是常驻钩子，跟下面「常驻的」不是一回事，别按同一把尺子衡量。
        runCatching { installCapture(cls) }
            .onFailure { LogX.w("SNS: capture hook failed: ${it.javaClass.simpleName}") }
        // ⚠️ **常驻**的只有 delete / execSQL / updateWithOnConflict / insert(WithOnConflict)
        // 六个（R42 甲：delete/execSQL 是「是否有裸 SQL 绕过」的旁证计数，update/insert 是
        // 写时拦截 + 通道计数）。非 SnsInfo 全部第一句零反射短路，query 一律不挂。
        if (ok.isEmpty()) {
            LogX.w("SNS: no hook installed, sns guard disabled")
            return
        }
        LogX.i("SNS installed hooks=${ok.joinToString(",")} blockEnabled=${config.blockEnabled}")
    }

    fun refresh() {
        // R42 路线甲：写时拦截跟随主开关「防止朋友圈动态撤回」（Keys.ANTIREVOKE_SNS）。
        // Keys.ANTIREVOKE_SNS_BLOCK 作为调试覆盖：true 强制开启；false（默认）= 不覆盖、跟随主开关。
        config = Config(
            blockEnabled = Kv.getBool(Keys.ANTIREVOKE_SNS) ||
                Kv.getBool(Keys.ANTIREVOKE_SNS_BLOCK),
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
    }

    /**
     * `updateWithOnConflict` 的 after 半场（R42：补位随乙方案删除，此钩子仅保留注册）。
     *
     * 微信清时间线可见位（`sourceType` bit1）的**真实通道**是
     * `update("SnsInfo", ContentValues{... sourceType=0 ...})` → `updateWithOnConflict`。
     * 甲（写时拦截）在 **before** 里把 `sourceType` 这个键摘掉，让清位不落地。
     */
    private val snsUpdateGuard = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching { onSnsUpdateAfter(param) }
                .onFailure { LogX.w("SNS update hook err: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * R42 W2：INSERT 通道计数（新动态首次落库走 `insertWithOnConflict`/`insert`）。
     *
     * 代价控制：第一句零反射字符串比较，非 `SnsInfo` 立刻返回。
     * afterHook 里从 **ContentValues** 取 sourceType 计数（只记账、不写库、不改 values）。
     */
    private val snsInsertGuard = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching { onSnsInsertAfter(param) }
                .onFailure { LogX.w("SNS insert hook err: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * R42 路线甲实测：**写时拦截** `SnsInfo.sourceType`（before）。
     *
     * ## 为什么 before 而不是 after
     * after 里改 values 已经晚了 —— SQL 早就执行完了，位已经清掉。要「让清位不落地」，
     * 只能在 SQL 执行**之前**把这个键从参数里拿掉（WechatMagician 2017 的做法，
     * 见 `docs/_r42_wechatmagician.md`）。同一行 `sourceType` 保持旧值，微信的
     * `sourceType & 2 != 0` 判据继续成立 ⇒ 时间线还能翻到。
     *
     * ## 与 [snsUpdateGuard]（after）的关系
     * 两个回调挂在**同一个方法**上，互不调用：
     * - after 钩子（补位）已随乙方案删除，现在只剩「读不到 sourceType 时是否被我们摘键」的形态观察；
     * - before 钩子把 `sourceType` 摘掉 ⇒ after 钩子读不到值 ⇒ 按 [SnsBlockProbe.isBlockedValues]
     *   判定为「甲已生效」而不报形态告警。
     *
     * ## 代价
     * ① 第一句零反射字符串比较（微信所有表的 update 都经过这里）；
     * ② 只有 `SnsInfo` + values 里有 `sourceType` 才反射读值；
     * ③ 只有 `== 0`（真正的清位）才点查一次库、记账、可能摘键。
     * ④ 开关关闭时**照样记账**（只计数、不摘键）—— 开/关对照实验必须有同一套计数，
     *    否则「关的时候没看到」会被误读成「没发生」。
     */
    private val snsBlockGuard = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching { onSnsUpdateBefore(param) }
                .onFailure { LogX.w("SNS block hook err: ${it.javaClass.simpleName}") }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching { onSnsUpdateBlockAfter(param) }
                .onFailure { LogX.w("SNS block after err: ${it.javaClass.simpleName}") }
        }
    }

    /** `ContentValues` 的反射面（wcdb 那份不是 AOSP 类的子类，⛔ 不能强转） */
    private class CvApi(c: Class<*>) {
        val get: Method? = runCatching { c.getMethod("get", String::class.java) }.getOrNull()
        val remove: Method? = runCatching { c.getMethod("remove", String::class.java) }.getOrNull()
        val size: Method? = runCatching { c.getMethod("size") }.getOrNull()
        val putInt: Method? =
            runCatching { c.getMethod("put", String::class.java, Int::class.javaObjectType) }
                .getOrNull()
                ?: runCatching { c.getMethod("put", String::class.java, Any::class.java) }.getOrNull()
    }

    @Volatile
    private var cvApiCls: Class<*>? = null

    @Volatile
    private var cvApiCached: CvApi? = null

    /** 按 class 缓存；非 ContentValues 返回 null（= 不是我们要的重载） */
    private fun cvApiOf(values: Any): CvApi? {
        val c = values.javaClass
        if (!c.name.contains("ContentValues")) return null
        if (cvApiCls === c) return cvApiCached
        val api = CvApi(c)
        cvApiCls = c
        cvApiCached = api
        return api
    }

    /** Integer / Long / Short / String 都认（微信不同字段类型不一样，不能赌） */
    private fun intOfAny(v: Any?): Int? = when (v) {
        null -> null
        is Int -> v
        is Long -> v.toInt()
        is Short -> v.toInt()
        is Byte -> v.toInt()
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> v.toString().trim().toIntOrNull()
    }

    /** 「sourceType 类型不认识」的告警只打一次 */
    private val blockParseWarned = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun onSnsUpdateBefore(param: XC_MethodHook.MethodHookParam) {
        val a = param.args ?: return
        if (a.size < 2) return
        // ① 零反射短路：微信所有表的 update 都走这里
        if ((a[0] as? String) != TABLE) return
        // ② 上一轮若没跑到 after（方法抛异常/提前返回），残留标记会污染下一次调用 —— 先清
        SnsBlockProbe.clearPending()
        val values = a[1] ?: return
        val api = cvApiOf(values) ?: return
        val raw = runCatching { api.get?.invoke(values, "sourceType") }.getOrNull()
        // ② 这次写根本没碰 sourceType（绝大多数）—— 只计数，不打日志
        if (raw == null) {
            SnsBlockProbe.noteUpdateNoSrcKey()
            return
        }
        val st = intOfAny(raw)
        if (st == null) {
            // ⛔ 不许静默：类型变了 ⇒ 拦截会永久失效，而功能表面上「开着」
            SnsBlockProbe.noteParseFail()
            if (blockParseWarned.compareAndSet(false, true)) {
                LogX.w(
                    "BLOCK skip: sourceType unparsable " +
                        "type=${raw.javaClass.name} value=${raw.toString().take(24)}（拦截不可用）"
                )
            }
            return
        }
        // ③ 非 0：正常行写 2/10（也包含微信把我们补的位打回 2），不是清位
        if (st != 0) {
            SnsBlockProbe.noteUpdateNonZero()
            return
        }

        // ---- ④ 命中：微信要把这一条的可见位清掉 ----
        val id = runCatching { snsIdOf(a) }.getOrDefault(0L)
        val db = param.thisObject
        val bits = if (id != 0L) SnsBlockProbe.readBits(db, id) else null
        val keys = runCatching { api.size?.invoke(values) as? Int ?: -1 }.getOrDefault(-1)
        val hasContent = runCatching { api.get?.invoke(values, "content") != null }
            .getOrDefault(false)
        val hasAttr = runCatching { api.get?.invoke(values, "attrBuf") != null }
            .getOrDefault(false)
        if (id == 0L) {
            // 抠不出 id 不代表不能拦（键在不在才是拦截的全部依据），但要让人看见
            LogX.w("BLOCK warn: sourceType=0 但 whereArgs 里抠不出 snsId keys=$keys（本条照拦）")
        }

        if (!cfg().blockEnabled) {
            SnsBlockProbe.record(id, bits, keys, hasContent, hasAttr, SnsBlockProbe.Outcome.OFF)
            return
        }

        val remove = api.remove
        if (remove == null) {
            SnsBlockProbe.record(
                id, bits, keys, hasContent, hasAttr, SnsBlockProbe.Outcome.SKIP_NO_API
            )
            LogX.w("BLOCK skip: no ContentValues.remove on ${values.javaClass.name}")
            return
        }
        val ok = runCatching { remove.invoke(values, "sourceType") }.isSuccess
        if (!ok) {
            SnsBlockProbe.record(
                id, bits, keys, hasContent, hasAttr, SnsBlockProbe.Outcome.SKIP_NO_API
            )
            LogX.w("BLOCK skip: remove(sourceType) threw on ${values.javaClass.name}")
            return
        }
        // ⑤ 摘完变空 ⇒ SQLite 的 update 会抛 IllegalArgumentException。宁可放回去不拦，也不能崩。
        val left = runCatching { api.size?.invoke(values) as? Int ?: -1 }.getOrDefault(-1)
        if (left == 0) {
            runCatching { api.putInt?.invoke(values, "sourceType", 0) }
            SnsBlockProbe.noteSkipEmpty(id)
            SnsBlockProbe.record(
                id, bits, keys, hasContent, hasAttr, SnsBlockProbe.Outcome.SKIP_EMPTY
            )
            return
        }
        SnsBlockProbe.record(
            id, bits, keys, hasContent, hasAttr, SnsBlockProbe.Outcome.BLOCKED, values,
        )
    }

    /**
     * 拦截的 after 半场：**立刻回查**这条的 sourceType，证明「键摘掉后库里没被改成 0」。
     *
     * 放 after 是唯一的正确位置：before 里查到的还是写之前的旧值。
     * 第一句仍是零反射的表名比较，非 `SnsInfo` 直接走。
     */
    private fun onSnsUpdateBlockAfter(param: XC_MethodHook.MethodHookParam) {
        val a = param.args ?: return
        if ((a.getOrNull(0) as? String) != TABLE) return
        if (!SnsBlockProbe.hasPending()) return
        SnsBlockProbe.finishAfterWrite { id -> SnsBlockProbe.readBits(param.thisObject, id) }
    }

    /**
     * 从 `updateWithOnConflict` 的参数里抠 snsId。
     *
     * 签名是 `(table, values, whereClause, whereArgs, conflictAlgorithm)`，但 wcdb 有多个
     * 重载，所以不赌下标：**只认「某个参数是长度 1 的数组、且唯一元素能 parseLong」**。
     * 抠不出返回 0（= 哨兵，调用方直接放弃补位，绝不去猜一个 id 来改库）。
     */
    private fun snsIdOf(a: Array<Any?>): Long {
        for (i in 2 until a.size) {
            val arr = a[i] as? Array<*> ?: continue
            if (arr.size != 1) continue
            val s = arr[0]?.toString() ?: continue
            val v = s.toLongOrNull() ?: continue
            if (v != 0L) return v
        }
        return 0L
    }

    private fun onSnsInsertAfter(param: XC_MethodHook.MethodHookParam) {
        val a = param.args ?: return
        if (a.size < 2) return
        // ① 零反射短路：微信所有表的 insert 都走这里，非 SnsInfo 立刻返回
        if ((a[0] as? String) != "SnsInfo") return
        // ② values 参数按「类名含 ContentValues」定位，不赌下标（wcdb 有多组重载）
        val values = a.firstOrNull { it != null && it.javaClass.name.contains("ContentValues") }
            ?: return
        // R42 W2：INSERT 通道的 sourceType 分布（清位理论上不走 INSERT，这里再证一次）
        runCatching { countInsertSource(values) }
            .onFailure { LogX.w("SNS insert count err: ${it.javaClass.simpleName}") }
    }

    /**
     * R42 W2：INSERT 路径上 `sourceType` 的取值（只计数，不写库、不改 values）。
     *
     * 新动态首次落库走 INSERT；「清位」如果也走 INSERT，那拦截 before 就漏了一条通道，
     * 所以这里必须有一份独立数字。
     */
    private fun countInsertSource(values: Any) {
        val api = cvApiOf(values) ?: return
        val raw = runCatching { api.get?.invoke(values, "sourceType") }.getOrNull()
        SnsBlockProbe.noteInsert(raw != null, intOfAny(raw))
    }

    private fun onSnsUpdateAfter(param: XC_MethodHook.MethodHookParam) {
        // R42 路线甲：写时拦截在 before 里完成，补位已随乙方案删除。
        // after 半场仅保留注册（甲依赖的 6 钩子之一），不再做任何事。
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
        // R42 W2：DELETE 通道独立计数（历史上认为「零 DELETE」，这里再证一次）。
        // ⚠️ 必须在 `id == null` 早退之前 —— 早期结论错就错在只看「单行删除」。
        // R42 路线甲：物理 DELETE 拦截与归档随乙方案删除，此钩子只保留通道计数（甲的旁证）。
        if (table == TABLE) {
            runCatching { SnsBlockProbe.noteDelete(a.getOrNull(1) as? String, id) }
                .onFailure { LogX.w("SNS delete count err: ${it.javaClass.simpleName}") }
        }
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
        // R42 W2：execSQL 是「清位第二条通道」的头号嫌疑（旧模板 `sourceType & -3`）。
        // 只在 SQL 里出现 SnsInfo 时才多一次 lowercase（平时零开销）。
        if (sql != null && sql.contains(TABLE)) {
            runCatching {
                SnsBlockProbe.noteSql(
                    sql,
                    isSourceWrite = looksLikeSourceWrite(sql),
                    clear = isSourceClearSql(sql),
                    own = isOwnFixSql(sql),
                )
            }.onFailure { LogX.w("SNS sql count err: ${it.javaClass.simpleName}") }
        }
    }

    // ------------------------------------------------------------------ execSQL 计数判据（随 SnsTimelineSrcLogic 删除内联到这里）

    /** 微信「批量清时间线来源位」那条 UPDATE 的形态：`sourceType = sourceType & -3` */
    private val CLEAR_SQL_RE =
        Regex("""sourcetype\s*=\s*\(?\s*sourcetype\s*&\s*\(?\s*-3\b""")

    /** 这条 SQL 是不是「微信在清 bit1」 */
    private fun isSourceClearSql(sql: String?): Boolean {
        if (sql.isNullOrBlank()) return false
        val s = sql.lowercase()
        if (!s.contains("snsinfo")) return false
        return CLEAR_SQL_RE.containsMatchIn(s)
    }

    /** 这条 SQL 看起来像「改 `SnsInfo.sourceType`」，但没被 [isSourceClearSql] 认出来 */
    private fun looksLikeSourceWrite(sql: String?): Boolean {
        if (sql.isNullOrBlank()) return false
        val s = sql.lowercase()
        return s.contains("snsinfo") && s.contains("sourcetype") &&
            (s.contains("update") || s.contains(" set "))
    }

    /** 我们自己补位/回滚写的 SQL（`sourceType = sourceType | 10`）—— 采样时要排除 */
    private val OWN_FIX_SQL_RE =
        Regex("""sourcetype\s*=\s*\(?\s*sourcetype\s*\|\s*\(?\s*(?:10|2)\b""")

    private fun isOwnFixSql(sql: String?): Boolean {
        if (sql.isNullOrBlank()) return false
        val s = sql.lowercase()
        if (!s.contains("snsinfo")) return false
        return OWN_FIX_SQL_RE.containsMatchIn(s)
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

    // ------------------------------------------------------------------ 句柄捕获（R20）

    /**
     * 拿到句柄之前**唯一**多出来的钩子：`rawQuery`。
     *
     * ## 为什么必须有它
     * [snsDb] 原来只在「DELETE 命中 SnsInfo」时被记下来（[isSnsDb]）。而需求 6.4 的
     * 导出要扫**当前** SnsInfo（防撤回语义：动态还看得见的时候就把图留存下来，
     * 见 [listCurrentSns]），定时刷新朋友圈走的是 INSERT/REPLACE —— **不产生 DELETE**。
     * 于是句柄恒为 null，导出恒 0 行，且日志里只会看到「扫不到当前 SnsInfo」，
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
     * 只读暴露微信朋友圈库句柄，给 [SnsBlockProbe]（甲的 readBits 取证）复用。
     *
     * ⚠️ 仍然只在 `execSQL` / `rawQuery` 捕获钩子里被赋值（见 [installCapture]），
     *  **本轮没有新增任何常驻钩子**。调用方必须容忍 null（= 这次启动微信还没碰过朋友圈库）。
     */
    internal fun snsDbHandle(): Any? = snsDb

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
     * 的时候就把它的图留存下来（**预防性留存**）。微信的 sns 磁盘缓存
     * `…/cache/<hash32>/sns/<hex1>/<hex2>/snst_xxx` 里放的正是**当前可见**动态的图。
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
     * ## ⚠️ 返回的是**半行**，只取导出需要的 7 列
     * `attrBuf` / `postBuf` / `head` / `stringSeq` / `subType` / `serverExtFlag` **不读**
     * （**显式**填 0/null —— `SnsRow` 对这几个字段没有默认值，不填编译不过，见 `liveRows`）。
     * 朋友圈 `content` 动辄几十 KB，一页 100 行再搭两个 BLOB 就是几百 MB，
     * 后台线程 OOM 会把微信带走。
     *
     * @param createTime 上一页最后一行的 `createTime`；首轮传 `Long.MAX_VALUE`
     * @param snsId 上一页最后一行的 `snsId`；首轮传 `Long.MAX_VALUE`。
     *              ⚠️ 复合游标：只按 `createTime <` 会在页边界丢掉同一秒的行
     * @return 一页行（`createTime desc, snsId desc`）；
     *         **null = 拿不到微信库句柄**（[snsDb] 还没捕获到 —— 这次启动微信还没碰过
     *         朋友圈库）。这不是错误，调用方必须**优雅降级**（本页导出为空，别弹窗）。
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


    /** 允许**带参数**的 pragma（列举型，纯读） */
    private val PRAGMA_WITH_ARG = setOf(
        "table_info", "table_xinfo", "table_list", "table_exists",
        "database_list", "foreign_key_list", "index_list", "index_info", "index_xinfo",
        "collation_list", "function_list", "module_list", "pragma_list", "compile_options",
    )

    /** 只允许**无参数**形式的 pragma（读当前值）。带参数多半是写，例如 `journal_mode(WAL)` */
    private val PRAGMA_NO_ARG = setOf(
        "page_size", "page_count", "freelist_count", "encoding", "schema_version",
        "user_version", "journal_mode", "auto_vacuum", "foreign_keys", "integrity_check",
    )

    /**
     * `pragma` 是否只读。**注意分两类**：
     * - `pragma journal_mode` = 读；`pragma journal_mode(WAL)` = **写**。同名前缀不同语义，
     *   只按名字白名单放行会漏掉后者 ⇒ 必须看有没有括号。
     * - `pragma foo=bar` 是赋值，一律拒。
     */
    private fun pragmaReadOnly(low: String): Boolean {
        val body = low.removePrefix("pragma").trim()
        if (body.contains('=')) return false
        return if (body.contains('(')) {
            body.substringBefore('(').trim() in PRAGMA_WITH_ARG
        } else {
            body in PRAGMA_NO_ARG
        }
    }

    /**
     * **通用只读 SQL 探针（R31d）**：`op=sql --es q "select …"`，查 `sns.db`。
     *
     * ## 为什么要有它
     * 6.2 排查时为 4 个取证维度（窗口排名 / 逐列对比 / 表清单 / 表结构）写了 4 个专用探针，
     * 结果**装了 4 次包、重启了 4 次微信**，每次还要用户重新进页面配合。
     * 这 4 件事本质都是「跑一条 SQL 看结果」——专用探针是把通用能力拆碎了重复付款。
     * ⇒ 通用入口一次投入，之后取证**零编译、零重启**。
     *
     * ## 安全（R31e 收口，第二视角审核发现三个口子）
     * 白名单只放 **`select`**，另加**只读 pragma 白名单**。三条理由：
     * 1. ⛔ `with` 必须去掉：`WITH x AS (SELECT 1) DELETE FROM SnsInfo` 以 `with` 开头，
     *    原判据放行；而 `rawQuery` **不校验语句类型**（不像 `execSQL` 有反向限制），
     *    `SQLiteCursor` 填窗口时会真的 step 它 ⇒ 等于任意写库。同理 `WITH…UPDATE/INSERT`。
     * 2. ⛔ `pragma` 不能整体放行：`pragma user_version=9` / `journal_mode=` / `auto_vacuum=`
     *    都改库头或持久状态。只放列举型（`table_info`/`table_list`/`database_list`/
     *    `foreign_key_list`/`index_list`/`index_info`），取值型 `pragma foo` 也放（无副作用）。
     * 3. 内部 `;` 一律拒：`rawQuery` 只编译第一条、尾部静默丢弃 —— 它现在无害，
     *    但「静默丢弃」本身是判据陷阱，fail-closed 更省心。
     *
     * ⚠️ 这个接收器是 **exported** 的（Manifest `RECEIVER_EXPORTED`），设备上任何 app
     * 都能发这条广播 ⇒ 上面不仅是「防自己手滑」，同时是**防第三方借模块写微信库的安全边界**。
     * 改判据时按安全边界对待，别当普通参数校验。
     */
    fun runReadOnlySql(sql: String, maxRows: Int = 60): List<String> {
        val s = sql.trim().trimEnd(';')
        val low = s.lowercase()
        val okPrefix = low.startsWith("select") || (low.startsWith("pragma") && pragmaReadOnly(low))
        if (!okPrefix) {
            return listOf(
                "SQLX 拒绝：只允许 select / 只读 pragma（收到 `${s.take(32)}`）"
            )
        }
        if (s.contains(';')) {
            return listOf("SQLX 拒绝：不允许多语句（内部含 `;`）")
        }
        val db = snsDb ?: return listOf("SQLX no sns db yet（先让微信刷一次朋友圈）")
        val m = rawQueryMethod(db) ?: return listOf("SQLX no rawQuery on ${db.javaClass.name}")
        val cap = MomentsLogic.clampProbeN(maxRows)
        return runCatching {
            val cur = m.invoke(db, s, null) ?: return listOf("SQLX null cursor")
            try {
                val nc = XposedHelpers.callMethod(cur, "getColumnCount") as? Int ?: 0
                val names = (0 until nc).map { i ->
                    (runCatching {
                        XposedHelpers.callMethod(cur, "getColumnName", i) as? String
                    }.getOrNull()) ?: "#$i"
                }
                val out = ArrayList<String>(16)
                out += "SQLX cols=${names.joinToString(",")}"
                var n = 0
                while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                    if (n >= cap) break
                    val sb = StringBuilder(64)
                    for (i in 0 until nc) {
                        if (i > 0) sb.append(" | ")
                        sb.append(cellOf(cur, i))
                    }
                    out += "SQLX r${n + 1} $sb"
                    n++
                }
                out += "SQLX rows=$n"
                out
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
        }.getOrElse {
            val c = (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it
            listOf("SQLX err ${c.javaClass.simpleName}: ${c.message?.take(120)}")
        }
    }

    /**
     * 取一格的值。BLOB 用 `getType` 识别后只报字节数。
     *
     * ⛔ `Cursor.getString()` 对 BLOB 列返回 **null** —— 那是**取值失败**，不是「列是空的」。
     * 本轮就因此误判过「attrBuf 全为 null ⇒ 正文永久丢失」，实际两条都是 174 字节。
     */
    private fun cellOf(cur: Any, i: Int): String {
        val ty = runCatching { XposedHelpers.callMethod(cur, "getType", i) as? Int }.getOrNull()
        if (ty == 4) { // FIELD_TYPE_BLOB
            val b = runCatching {
                XposedHelpers.callMethod(cur, "getBlob", i) as? ByteArray
            }.getOrNull()
            return "<blob ${b?.size ?: -1}B>"
        }
        return runCatching {
            XposedHelpers.callMethod(cur, "getString", i) as? String
        }.getOrNull() ?: "<null>"
    }


    /**
     * **A4 取证探针（R19）**：扫**当前** `SnsInfo`（不是归档表！）的 `content`，
     * 抽出里面所有「连续 ASCII 数字 ≥15 位」的串作为 mediaId 候选。
     *
     * ## 为什么必须扫当前表而不是归档表
     * 旧版 `snsprobe` 探针扫的是**归档表**（历史**已被删除**的动态），而 sns 磁盘缓存里
     * 放的却是**当前朋友圈可见动态**的图 —— 两者**根本不重叠**。实测：归档的 7 个 mediaId
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
     * 旧版 `snsprobe` 报 `hit=0` 有两个叠加原因，本探针两个都绕开：
     * ① 它扫的是**历史被删动态**，缓存里是**当前**动态的图 ⇒ 样本根本不重叠；
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

    fun stat(): String =
        "SNS stat blockEnabled=${cfg().blockEnabled} snsDb=${snsDb != null}"
}
