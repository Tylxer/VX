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
 * - 在 `updateWithOnConflict` 的 **before** 里把 `sourceType` 写回「旧值补齐 bit1+bit3」的
 *   保底值（bits 读不到时降级为摘键），清位不落地，行留在库里、两个可见位都在 →
 *   时间线与好友主页都照常显示（R42 bit3，见 `docs/_r42_bit3_impl.md`）；
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
 * ⚠️ **R42 self-exclude 例外**：上面的「一律拦」只对**写时拦截**（`updateWithOnConflict`
 * 清 `sourceType`）生效；`readBits` 能读回该行的 `userName`，所以**自己发的动态可以排除**——
 * 作者 == 当前登录 wxid 时**彻底放行**（不保位、不加前缀），让微信的写原样落地、动态真被删掉。
 * 判据在 [applyBlock] 开头（`isSelfAuthor(bits?.userName)`），`bits == null` 或 wxid 取不到时
 * **照常拦截**（绝不因拿不到作者放行）。物理 DELETE 钩子仍只计数、不拦（本就没作者信息可判）。
 *
 * ## 拦在 SQLite 层而不是 hook 某个 `plugin.sns.*`
 * `SnsInfo` 表名、字段名都是未混淆的，比混淆的 `a1.b2` 抗版本。
 *
 * ## R42 方案 B（写时拦截补不上的那一半）
 * 写时拦截管的是 `updateWithOnConflict`，但清 bit3 还有一条**裸 SQL** 通道：
 * `UPDATE SnsInfo SET sourceType = sourceType & -9 WHERE ...`（进好友主页就执行，一次 6 条）。
 * 位置在 [onSnsSql]：把 `& -9` 改成恒等掩码 `& -1` 后**照常执行**（命中集合与 `changes()` 都不变），
 * 判定/改写是纯函数 [SnsSqlRewrite]，`& -3` 只识别不拦。
 */
object SnsGuard {

    private const val TABLE = "SnsInfo"

    /** 朋友圈是**独立的库**，不是主消息库 `EnMicroMsg.db`（防撤回那边踩过「多个 db」的坑） */
    private const val DB_MARK = "SnsMicroMsg.db"

    private const val CFG_TTL_MS = 3000L

    /**
     * R42 bit3 · A/B 反向对照**调试覆盖**键（off 语义）。
     *
     * ⚠️ 这是 A/B 对照用的调试覆盖，**正式开关仍是 [Keys.ANTIREVOKE_SNS]**。
     * 关掉它只停「写回补位」、把行为降级回纯摘键，A（写时拦截）与 B（`&-9`→`-1`）两层
     * **完全不动** —— 反向对照的自变量必须唯一；用主开关关会一次停掉三层，证明不了
     * 「主页出现」是补位带来的。
     *
     * `Kv.getBool` 没有默认值参数、未设置即 false ⇒ `!false = true` ⇒ **补位默认开启**。
     * ⚠️ 键字面量收口在 [Keys.SNS_ALBUM_FIX_OFF]（原先是本文件私有字面量），但仍**不登记
     * PrefSpec**：`PrefsVisibilityTest` 只遍历 `Prefs.ALL`，未登记的键不受约束；
     * 沿用 `antirevoke.` 前缀便于 `op=kvset` 直接改。`kvset` 探针结尾已调 [refresh]，热切换生效。
     */
    private const val ALBUM_FIX_OFF_KEY = Keys.SNS_ALBUM_FIX_OFF

    private data class Config(
        /** R42 路线甲：`updateWithOnConflict` **before** 里摘掉 `sourceType`（写时拦截）。 */
        val blockEnabled: Boolean,
        /**
         * R42 方案 B：`execSQL` 里 `sourceType & -9` 改写为 no-op（清 bit3 的裸 SQL）。
         *
         * ⚠️ 必须是 `blockEnabled && 未开调试覆盖`：SQL 改写是写时拦截的**补位**，
         * 写时拦截关掉时位型根本到不了 `10`，单独开它没有意义（见 [Keys.ANTIREVOKE_SNS_SQLBLOCK_OFF]）。
         */
        val sqlBlockEnabled: Boolean,
        /**
         * R42 bit3：拦截成功那次把 `sourceType` 写回「旧值补齐两个可见位」的保底值。
         *
         * ⚠️ 这是 **A/B 对照专用的调试覆盖**，正式开关仍是 [Keys.ANTIREVOKE_SNS]。
         * 关掉它只停「写回」、把行为降级回纯摘键，A（写时拦截）与 B（`&-9`→`-1`）两层
         * **完全不动** —— 反向对照的自变量必须唯一，用主开关关会一次停掉三层。
         * 键名：`antirevoke.sns_album_fix_off`（off 语义，未设置 = false = 补位开启）。
         */
        val albumFixEnabled: Boolean,
    )

    @Volatile
    private var config = Config(false, false, true)

    @Volatile
    private var cfgAt = 0L

    /** 朋友圈库实例，扫当前 SnsInfo（导出）要用；只在命中 execSQL/rawQuery 时顺手记下 */
    @Volatile
    private var snsDb: Any? = null

    /**
     * 宿主（微信）ClassLoader，[install] 时记下。
     *
     * ⛔ 微信的类（如 `com.tencent.mm.protocal.protobuf.TimeLineObject`）**只能用宿主
     * ClassLoader** 加载，模块自己的 ClassLoader 加载不到（真机必抛 CNFE）。
     * 供 [SnsPrefixProbe] 反序列化 `content` BLOB 用。
     */
    @Volatile
    private var hostLoader: ClassLoader? = null

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 宿主 ClassLoader 必须最先进来记下：下面 findClass 失败早退时探针也还能用。
        hostLoader = lpparam.classLoader
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
        // 六个（R42 甲：update/insert 是写时拦截 + 通道计数；delete 是旁证计数；
        // R42 乙：execSQL 升级为**方案 B 的改写点**，只对 `SnsInfo` + `& -9` 生效）。
        // 非 SnsInfo 全部第一句零反射短路，query 一律不挂。
        if (ok.isEmpty()) {
            LogX.w("SNS: no hook installed, sns guard disabled")
            return
        }
        LogX.i(
            "SNS installed hooks=${ok.joinToString(",")} blockEnabled=${config.blockEnabled} " +
                "sqlBlock=${config.sqlBlockEnabled} albumFix=${config.albumFixEnabled}"
        )
    }

    fun refresh() {
        // R42 路线甲：写时拦截跟随主开关「防止朋友圈动态撤回」（Keys.ANTIREVOKE_SNS）。
        // Keys.ANTIREVOKE_SNS_BLOCK 作为调试覆盖：true 强制开启；false（默认）= 不覆盖、跟随主开关。
        val block = Kv.getBool(Keys.ANTIREVOKE_SNS) || Kv.getBool(Keys.ANTIREVOKE_SNS_BLOCK)
        config = Config(
            blockEnabled = block,
            // 方案 B：写时拦截开启时默认一起拦；调试键置 true 则只关这一层（A/B 对照用）
            sqlBlockEnabled = block && !Kv.getBool(Keys.ANTIREVOKE_SNS_SQLBLOCK_OFF),
            // R42 bit3：off 语义调试键，未设置即 false ⇒ 补位默认开启；
            // 只停补位、A/B 两层照旧（反向对照自变量唯一）
            albumFixEnabled = !Kv.getBool(ALBUM_FIX_OFF_KEY),
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
     * 甲（写时拦截）在 **before** 里把 `sourceType` 写回保底值或摘键，让清位不落地
     * （R42 bit3 起不再只是摘键，见 [applyBlock]）。
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
     * 两个回调挂在**同一个方法**上，互不调用。`snsUpdateGuard` 的 after 半场（补位）
     * 已随乙方案删除、是空函数；旧 KDoc 提到的 `SnsBlockProbe.isBlockedValues` /
     * `blockedCvs` **全项目零调用点**（死代码）。
     *
     * ⚠️ **真正的 after 校验链路是本钩子自己的 after 半场**：[onSnsUpdateBlockAfter]
     * → `SnsBlockProbe.hasPending()`（ThreadLocal 事件对象，与 ContentValues 里有没有
     * `sourceType` 键**完全无关**）→ `SnsBlockProbe.finishAfterWrite` 回查 `readBits`。
     * ⇒ 把 `sourceType` 写回 ContentValues **不会**让这条链路失效，不需要「修好」什么。
     *
     * R42 bit3 仍额外引入 [SnsBitLogic.afterShape] 互斥形态判据（after 里读 cv 现值分类
     * 「拦住了 / 没拦住 / 异常」），目的是**自证**与订正旧日志口径（旧日志写「(键已摘)」，
     * 写回路径下是假话）。⛔ `afterOk`/`afterFail` 严格保持 HEAD 旧语义（只由 `bits != null`
     * 决定），形态分类走四个独立新计数器，两个维度正交、互不覆盖。
     *
     * ## 两个拦截点（R42 方案 A）
     * 1. **写 0**（`2→0` / `8→0`）—— 旧行为，逐字保留，不查旧值也拦；
     * 2. **非 0 纯降位**（`10→8` 清 bit1、`10→2` 清 bit3）—— 新增。必须查旧值才能与
     *    `0→2` / `0→8` 这类**加位**区分；混合改写（删一位又添一位，如 `8→2`）一律放行，
     *    判据见 [SnsBitLogic.decide]。
     * ⚠️ **R42 self-exclude**：两个拦截点进 [applyBlock] 后都先判 `isSelfAuthor(bits?.userName)`，
     * 命中即彻底放行（`record(SELF)` + return，不保位、不加前缀）；bits/wxid 取不到照常拦截。
     *
     * ## 代价
     * ① 第一句零反射字符串比较（微信所有表的 update 都经过这里）；
     * ② 只有 `SnsInfo` + values 里有 `sourceType` 才反射读值；
     * ③ 写 0 ⇒ 点查一次库、记账、可能摘键；
     *    非 0 ⇒ 新值同时含 bit1+bit3（如 `10`）直接放行（零 IO），
     *    缺位时才点查一次旧值（真实频次：一次进主页几十次，不是热路径）。
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
        val putBlob: Method? =
            runCatching { c.getMethod("put", String::class.java, ByteArray::class.java) }
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
        // ③ 非 0：**先排除加位**，再按「旧值 vs 新值」判断是不是降位（R42 方案 A）
        if (st != 0) {
            SnsBlockProbe.noteUpdateNonZero()
            // 新值同时含 bit1+bit3（如 10）⇒ 一定没丢位，连旧值都不用查（热路径零 IO）
            if (!SnsBitLogic.needsOld(st)) return
            onSnsUpdateDrop(a, param.thisObject, values, api, st)
            return
        }

        // ---- ④ 命中：微信要把这一条的可见位清掉（写 0，旧实现逐字保留）----
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

        applyBlock(db, values, api, id, bits, keys, hasContent, hasAttr, newValue = null)
    }

    /**
     * 非 0 写入的降位判定（`10→8` 清 bit1、`10→2` 清 bit3）。
     *
     * ## 为什么这里一定要查旧值
     * `2` 既可能是 `10→2`（纯降位，要拦），也可能是 `0→2`（加位，必须放行）。
     * 只有库里旧值能区分 —— 见 [SnsBitLogic.decide]。
     *
     * ## 查不到旧值时为什么放行
     * 没有旧值就无法证明是降位；这条路径上「多拦」会直接把动态写不进相册，
     * 与「宁可少拦」的旧口径相比，这里的代价不对等，所以 `old == null` 一律放行并记账。
     */
    private fun onSnsUpdateDrop(
        a: Array<Any?>,
        db: Any?,
        values: Any,
        api: CvApi,
        st: Int,
    ) {
        val id = runCatching { snsIdOf(a) }.getOrDefault(0L)
        if (id == 0L) {
            // 抠不出 id ⇒ 查不到旧值 ⇒ 无法判定，放行（与「写 0」的保守方向相反，理由见上）
            SnsBlockProbe.noteDropUnknown()
            return
        }
        val bits = SnsBlockProbe.readBits(db, id)
        if (SnsBitLogic.decide(bits?.src, st) == SnsBitLogic.Action.ALLOW) {
            SnsBlockProbe.noteDropAllowed()
            return
        }
        val keys = runCatching { api.size?.invoke(values) as? Int ?: -1 }.getOrDefault(-1)
        val hasContent = runCatching { api.get?.invoke(values, "content") != null }
            .getOrDefault(false)
        val hasAttr = runCatching { api.get?.invoke(values, "attrBuf") != null }
            .getOrDefault(false)
        applyBlock(db, values, api, id, bits, keys, hasContent, hasAttr, newValue = st)
    }

    /**
     * 真正的拦截动作 —— 写 0 与非 0 降位**共用**这一份。
     *
     * ## R42 bit3：拦截不再只是「摘键」，而是把 `sourceType` 写回保底值
     * 只摘键时库里停在 `old`；若删除前从没进过好友主页，`old=2`（只有 bit1）⇒
     * 时间线在、但主页相册查询（`IN (8,264,10,...)` 全含 bit3）不命中 ⇒ 主页整条消失。
     * ⇒ 拦下的这一次直接 `put("sourceType", keep)`，`keep = old | (2|8)`，
     * 让这一列顺带补齐 bit3（典型 `2 → 10`）。**零额外 IO、无竞态**：值在这一次 UPDATE
     * 里落地，没有第二条 UPDATE、没有注入表、没有定时扫描。
     *
     * ## 两条路径
     * - **写回路径**（`keepAliveSource(bits?.src) != null`）：只 `put` 覆盖、**不 remove**。
     *   ContentValues 恒有 `sourceType` 键 ⇒ `size()==0` 不可能发生 ⇒ SKIP_EMPTY 分支
     *   在这条路径**消失**（不是死代码，是根本不可达）。
     * - **降级路径**（keep == null：`albumFixEnabled == false` / bits 读不到 / old 彻底不可见 /
     *   put 不可用或抛异常）：与 R42 现状逐字一致 —— 只 `remove`，并**保留** `left==0` 的
     *   SKIP_EMPTY（放回原值、不吞异常）。这条路径仍会摘空 cv，所以该分支必须留着。
     *
     * ## 硬约束
     * - 开关关闭（`cfg().blockEnabled == false`）在第一行就 `record(OFF); return`，
     *   keep 计算 / put / remove 全在其后 ⇒ **一个字节都不写**（约束 1）。
     * - 写回值完全由 [SnsBitLogic.keepAliveSource] 决定，`newValue` 绝不参与；
     *   `bits == null` ⇒ 返回 null ⇒ 降级只摘键（约束 2）。公式与边界见其 KDoc。
     * - A/B 反向对照：`cfg().albumFixEnabled == false` ⇒ keep 直接置 null ⇒ 只摘键，
     *   A 层（写时拦截）与 B 层（`&-9 → -1`）**完全不动**，自变量唯一（见 [ALBUM_FIX_OFF_KEY]）；
     *   `SNS stat` 会显示 `albumFix=<true|false>` 便于确认开关读回。
     * - 方案 B 的 `& -9 → -1` 一行未动。
     *
     * ## R42 prefix：删除拦截成功时把 `[已删除]` 写进同一次 update 的 content
     * 两个 BLOCKED 分支（写回 / 降级）都调 [attachPrefix]，把前缀 blob 与 sourceType
     * 一起塞进**同一个** ContentValues —— 微信这一次 update 全做完，没有第二条写。
     * ⚠️ 前缀附加**在保位逻辑之后**：保位（put/remove 及 SKIP_EMPTY 放回）已完成、
     * 结果已定，前缀失败（buildPrefixedBlob 返回 null / put 抛异常）只打限流日志、
     * 绝不 rethrow、**不影响删除拦截**。SKIP_EMPTY / SKIP_NO_API / OFF 分支不附加前缀
     * （那些分支本条根本没拦住，行照旧被清位，写前缀无意义）。
     *
     * ## R42 self-exclude：作者是自己 ⇒ 彻底放行（不保位、不加前缀）
     * 在 OFF 检查之后、keep 计算之前判断 `isSelfAuthor(bits?.userName)`：
     * - 命中 ⇒ `record(SELF); return`，**不 put keep、不 remove、不 attachPrefix**，
     *   微信这次 update（sourceType=0 / 降位）原样落地 ⇒ 动态真的被删掉；
     * - ⛔ `isSelfAuthor(null)` 恒 false（`selfWxid()` 取不到也恒 false）⇒
     *   **bits 读不到 / self wxid 取不到时照常走下面的保位/摘键**，绝不因拿不到作者而放行。
     * 放在 [applyBlock] 开头一处、覆盖两个调用点（写 0 与降位），理由：
     * 两处都已在调用前算好 `bits` 并传入，`bits?.userName` 是唯一的判据输入，无额外 IO；
     * 单一决策点避免两处各自早退的判据漂移。留痕走 [SnsBlockProbe.Outcome.SELF]（独立
     * 计数器 + 事件 + 限流日志），不污染 blocked / skipOff / afterOk / afterFail / shape*。
     *
     * @param db wcdb 的 SQLiteDatabase（[SnsPrefixProbe.buildPrefixedBlob] 读 content 用）
     * @param newValue 微信这次想写入的值；`null` = 写 0 路径（与旧实现逐字一致）
     */
    private fun applyBlock(
        db: Any?,
        values: Any,
        api: CvApi,
        id: Long,
        bits: SnsBlockProbe.Bits?,
        keys: Int,
        hasContent: Boolean,
        hasAttr: Boolean,
        newValue: Int?,
    ) {
        val want = newValue ?: 0

        // 约束 1：开关关闭 ⇒ 只记账，绝不写任何字节（keep 计算 / put / remove 全在其后）
        if (!cfg().blockEnabled) {
            SnsBlockProbe.record(
                id, bits, keys, hasContent, hasAttr, SnsBlockProbe.Outcome.OFF,
                newValue = newValue,
            )
            return
        }

        // R42 self-exclude：作者是自己 ⇒ 彻底放行（不保位、不加前缀），
        // 让微信这次 update（sourceType=0 / 降位）原样落地，动态真的被删掉。
        // ⛔ isSelfAuthor(null) 恒 false：bits 读不到（userName==null）或 self wxid 取不到时
        //    **不**排除、照常走下面的保位/摘键 —— 绝不能因为拿不到作者就放行（硬红线）。
        if (isSelfAuthor(bits?.userName)) {
            SnsBlockProbe.record(
                id, bits, keys, hasContent, hasAttr, SnsBlockProbe.Outcome.SELF,
                newValue = newValue,
            )
            return
        }

        // 写回值：纯判据。bits 读不到 / old 彻底不可见 ⇒ null（降级为只摘键）
        // ⚠️ A/B 反向对照：关掉 ALBUM_FIX_OFF_KEY ⇒ keep 直接置 null ⇒ 只摘键，
        //    A 层（写时拦截）与 B 层（&-9→-1）**完全不动**，自变量只有「补位」这一项。
        var keep = if (cfg().albumFixEnabled) SnsBitLogic.keepAliveSource(bits?.src) else null
        if (keep != null && api.putInt == null) {
            // wcdb 形态异常：没有 put(String,Integer) 就写不回 ⇒ 降级，绝不吞
            LogX.w(
                "BLOCK degrade: no ContentValues.put(String,Integer) on " +
                    "${values.javaClass.name}，写回降级为只摘键"
            )
            keep = null
        }

        if (keep != null) {
            // ⭐ 写回路径：一次 put 覆盖微信想写的值（不 remove ⇒ cv 不会变空）
            val putOk = runCatching { api.putInt!!.invoke(values, "sourceType", keep) }.isSuccess
            if (putOk) {
                SnsBlockProbe.record(
                    id, bits, keys, hasContent, hasAttr, SnsBlockProbe.Outcome.BLOCKED,
                    cv = values, newValue = newValue, keep = keep,
                )
                // R42 prefix：写回路径附加前缀（保位已完成，前缀失败不影响拦截）
                attachPrefix(db, values, api, id, bits?.userName)
                return
            }
            // ⛔ 不吞异常：put 抛了要能看见，并降级为只摘键（此时 cv 未被改动）
            LogX.w(
                "BLOCK degrade: put(sourceType,$keep) threw on " +
                    "${values.javaClass.name}，降级只摘键"
            )
        }

        // ---- 降级：只摘键（与 R42 现状逐字一致）----
        val remove = api.remove
        if (remove == null) {
            SnsBlockProbe.record(
                id, bits, keys, hasContent, hasAttr, SnsBlockProbe.Outcome.SKIP_NO_API,
                newValue = newValue,
            )
            LogX.w("BLOCK skip: no ContentValues.remove on ${values.javaClass.name}")
            return
        }
        val ok = runCatching { remove.invoke(values, "sourceType") }.isSuccess
        if (!ok) {
            SnsBlockProbe.record(
                id, bits, keys, hasContent, hasAttr, SnsBlockProbe.Outcome.SKIP_NO_API,
                newValue = newValue,
            )
            LogX.w("BLOCK skip: remove(sourceType) threw on ${values.javaClass.name}")
            return
        }
        // 摘完变空 ⇒ SQLite 的 update 会抛 IllegalArgumentException。宁可放回去不拦，也不能崩。
        // ⚠️ 这条分支**只在降级路径可达**：写回路径不 remove，cv 恒有 sourceType 键。
        val left = runCatching { api.size?.invoke(values) as? Int ?: -1 }.getOrDefault(-1)
        if (left == 0) {
            // 放回的必须是**微信原本要写的值**（want）：放错值等于替微信改库。
            // ⛔ 不许吞：放回失败会让 update 抛异常，必须留一行告警（约束 4）。
            val put = api.putInt
            val restored = put != null &&
                runCatching { put.invoke(values, "sourceType", want) }.isSuccess
            if (!restored) {
                LogX.w("BLOCK skip: empty values 且放回失败（put 不可用/抛异常），update 可能抛异常")
            }
            SnsBlockProbe.noteSkipEmpty(id, isDrop = newValue != null)
            SnsBlockProbe.record(
                id, bits, keys, hasContent, hasAttr, SnsBlockProbe.Outcome.SKIP_EMPTY,
                newValue = newValue,
            )
            return
        }
        SnsBlockProbe.record(
            id, bits, keys, hasContent, hasAttr, SnsBlockProbe.Outcome.BLOCKED,
            cv = values, newValue = newValue, keep = null,
        )
        // R42 prefix：降级路径附加前缀（保位已完成，前缀失败不影响拦截）
        attachPrefix(db, values, api, id, bits?.userName)
    }

    /** 前缀写入失败的告警只打一次（防系统性失败在删除路径刷屏） */
    private val prefixWarned = java.util.concurrent.atomic.AtomicBoolean(false)

    // ------------------------------------------------------------------ 当前登录 wxid（排除自己）

    /**
     * 当前登录 wxid 的候选（按可靠性排序，取第一个非空）。
     * 键名沿用旧 `SnsDeletedTag.selfWxid`（随灰标一起删除，此处为等价搬迁）。
     */
    private val SELF_CANDIDATES: List<Pair<String, String>> = listOf(
        "com.tencent.mm_preferences" to "login_weixin_username",
        "com.tencent.mm_preferences" to "login_user_name",
        "system_config_prefs" to "login_weixin_username",
        "com.tencent.mm" to "login_weixin_username",
    )

    /** 登录 wxid 缓存。**只缓存成功值**：取不到时下次再试（install 早期 Context 可能未就绪）。 */
    @Volatile
    private var selfWxidCache: String? = null

    @Volatile
    private var selfWxidWarned: Boolean = false

    private fun selfWxid(): String? {
        selfWxidCache?.let { return it }
        val ctx = com.lx.wxgroup.core.HostContext.app() ?: return null
        for ((file, key) in SELF_CANDIDATES) {
            val v = runCatching {
                ctx.getSharedPreferences(file, android.content.Context.MODE_PRIVATE)
                    .getString(key, null)
            }.getOrNull()
            if (!v.isNullOrBlank()) {
                selfWxidCache = v
                return v
            }
        }
        if (!selfWxidWarned) {
            selfWxidWarned = true
            LogX.w("PREFIX: self wxid unknown（不排除自己，照常加前缀）")
        }
        return null
    }

    /** 作者是不是自己。⛔ 取不到 self 时恒 false ⇒ **不排除**（与旧 `shouldRecord` 降级一致）。 */
    private fun isSelfAuthor(author: String?): Boolean {
        val me = selfWxid() ?: return false
        return author != null && author == me
    }

    /** 探针复用的同一入口（`selfWxid` 是 private，跨文件不可见）。 */
    internal fun selfWxidOrNull(): String? = selfWxid()

    /**
     * 探针入口：**逐候选**打印登录 wxid 的解析过程，不读写缓存。
     *
     * 为什么要把解析过程本身做成证据：`isSelfAuthor` 的判据是 `author == self`，而
     * `self` 取不到时**恒不排除**（降级）。若 4 个候选 prefs 全部读不到，排除自己就是
     * **静默失效** —— 代码看着对、日志不报错、行为却是「照常加前缀」。所以要能直接看到
     * 哪条命中、哪条 null。
     *
     * 输出不含 wxid 明文（只给长度），避免日志里留完整账号。
     */
    internal fun selfWxidProbe(): List<String> {
        val out = ArrayList<String>()
        val ctx = com.lx.wxgroup.core.HostContext.app()
        if (ctx == null) {
            out += "SELF ctx=null（微信进程未 attach）"
            return out
        }
        var hit: String? = null
        for ((file, key) in SELF_CANDIDATES) {
            val v = runCatching {
                ctx.getSharedPreferences(file, android.content.Context.MODE_PRIVATE)
                    .getString(key, null)
            }.onFailure {
                out += "SELF $file/$key throw ${it.javaClass.simpleName}"
            }.getOrNull()
            out += "SELF $file/$key = " + when {
                v == null -> "null"
                v.isBlank() -> "blank"
                else -> "ok(len=${v.length})"
            }
            if (hit == null && !v.isNullOrBlank()) hit = v
        }
        out += "SELF resolved=" + (
            hit?.let { "HIT len=${it.length}" }
                ?: "null ⇒ isSelfAuthor 恒 false，排除自己不生效"
            )
        out += "SELF cached=${if (selfWxidCache == null) "-" else "yes"}"
        return out
    }


    /**
     * R42 prefix：把 `[已删除]\n` 前缀塞进同一次 update 的 `content` 列。
     *
     * ## 为什么是 before 而不是 after
     * after 里改 values 已经晚了 —— `updateWithOnConflict` 的 SQL 早就执行完，
     * 再改 values 不会写进库。要「content 前缀与 sourceType 保位**一起**落地」，
     * 只能在这条钩子的 **before** 半场把 `content` 键塞进**同一个** ContentValues，
     * 让微信这一次 update 把两件事都做完（第二次写库 = 竞态 + 冷启/下拉刷新被覆写）。
     *
     * ## 硬约束
     * - 整段 `runCatching`，出错只打限流日志、绝不 rethrow —— 这是微信写库线程，抛异常 = 崩微信；
     * - `api.putBlob == null`（wcdb 没有 put(String,byte[])）直接跳过，不降级为别的手段；
     * - 幂等由 [SnsPrefixProbe.buildPrefixedBlob] 保证：ContentDesc 已带前缀 ⇒ 返回 null ⇒ 不写；
     * - 本函数只在两个 BLOCKED 分支调用（保位已成功），前缀失败**不影响删除拦截**。
     */
    private fun attachPrefix(db: Any?, values: Any, api: CvApi, id: Long, author: String?) {
        if (api.putBlob == null) return
        // 自己发的动态不加前缀：作者就是当前登录账号 ⇒ 跳过。
        // 语义沿用旧灰标 `shouldRecord` 的 `!isSelf(author, self)`（含「取不到 self 不排除」的降级）：
        // 用户自己删自己的动态是明确意图，不该留一条「已被作者删除」的提示。
        if (isSelfAuthor(author)) return
        val blob = SnsPrefixProbe.buildPrefixedBlob(db, id)
        if (blob == null) return
        runCatching { api.putBlob!!.invoke(values, "content", blob) }
            .onFailure {
                if (prefixWarned.compareAndSet(false, true)) {
                    LogX.w(
                        "PREFIX attach fail: put(content) threw on ${values.javaClass.name}: " +
                            "${it.javaClass.simpleName}（保位不受影响）"
                    )
                }
            }
    }

    /**
     * 拦截的 after 半场：读回 ContentValues 里 `sourceType` 的**现值**，交
     * [SnsBitLogic.afterShape] 互斥判定「拦住了 / 没拦住」，并回查库里值。
     *
     * ⚠️ **R42 bit3 写回改造**：以前 after 靠「ContentValues 里读不到 sourceType」判定甲生效；
     * 写回后 cv 里**有**该键（值 = keep），判据失效。现在把读到的值原样传给
     * [SnsBlockProbe.finishAfterWrite]，由纯函数分类（见 `docs/_r42_bit3_impl.md` §2）：
     * 写回路径读到 keep、降级（摘键）路径读不到键（null）、没拦住则读到微信原值 want。
     *
     * 放 after 是唯一正确位置：before 里读到的是写之前的旧值 / 我们刚 put 的值，
     * 证明不了「微信实际拿去执行的 ContentValues 是什么形态」。
     * 第一句仍是零反射的表名比较，非 `SnsInfo` 直接走。
     *
     * 前提：`param.args[1]` 与 before 是**同一个** ContentValues 对象，且 before 已验证过
     * `cvApiOf` / `get` 可用（否则根本走不到拦截），所以 after 里读不到键只可能是「被摘掉」。
     */
    private fun onSnsUpdateBlockAfter(param: XC_MethodHook.MethodHookParam) {
        val a = param.args ?: return
        if ((a.getOrNull(0) as? String) != TABLE) return
        if (!SnsBlockProbe.hasPending()) return
        val values = a.getOrNull(1)
        val api = if (values != null) cvApiOf(values) else null
        val raw = if (api != null) {
            runCatching { api.get?.invoke(values, "sourceType") }.getOrNull()
        } else {
            null
        }
        val observed = intOfAny(raw)
        SnsBlockProbe.finishAfterWrite(observed) { id -> SnsBlockProbe.readBits(param.thisObject, id) }
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
        // R42 W2：execSQL 是「清位第二条通道」的头号嫌疑（`sourceType & -3` / `& -9`）。
        // 只在 SQL 里出现 SnsInfo 时才多一次 lowercase（平时零开销）。
        // R42 方案 B：`& -9` 从「只识别」升级为「改写为 no-op 后执行」；
        //   ⛔ 其他任何形态（含 `& -3`）一律**原样透传**，连正则都不再跑一次。
        if (sql != null && sql.contains(TABLE)) {
            runCatching { onSnsSql(sql, param) }
                .onFailure { LogX.w("SNS sql count err: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * `execSQL` 命中 `SnsInfo` 之后的全部动作：**先决定改不改写，再记账**。
     *
     * ## 为什么改写放在记账之前
     * 账本里的 `sqlBlock9 / blkOff / blkFail` 是「实际发生了什么」，不是「本可以发生什么」。
     * 顺序反了就会出现「计数说拦了、SQL 其实原样执行」这种自欺。
     *
     * ## 方案 B 只在 `& -9` 上动手（理由见 [SnsSqlRewrite] 的类注释）
     * - `& -9`（清 bit3）：改写 `& -1`，语义 = 赋回原值，WHERE / 命中集合 / `changes()` 全不变；
     * - `& -3`（清 bit1）：**只识别 + 告警**。真机从未出现（`sqlClear=0`），没有样本能估副作用，
     *   而它与 `& -9` 可能共处同一条语句（`BOTH`），贸然改写等于在没有观测的前提下改微信的写库语义。
     * - 其余：不进 [SnsSqlRewrite]，只计数（零额外开销）。
     */
    private fun onSnsSql(sql: String, param: XC_MethodHook.MethodHookParam) {
        val kind = SnsBitLogic.sqlClear(sql)
        val block = when {
            // ① 连形态都不是（绝大多数 SnsInfo 上的 execSQL）—— 零动作
            !kind.clearsBit3 -> SnsBlockProbe.SqlBlock.NONE
            // ② 形态像、但清位不在 SET 里（WHERE 里的同名表达式 / 非 UPDATE）⇒ 不是可改写对象。
            //    ⚠️ 这一条与「记账」无关：sqlClear9 已经记过了，这里只决定动不动手。
            !SnsSqlRewrite.shouldBlock(sql) -> SnsBlockProbe.SqlBlock.NONE
            // ③ 该拦但开关关着（A/B 对照的 B 组）：原样执行，但要能被看见
            !cfg().sqlBlockEnabled -> SnsBlockProbe.SqlBlock.OFF
            else -> applySqlRewrite(param, sql)
        }
        SnsBlockProbe.noteSql(
            sql = sql,
            isSourceWrite = SnsBitLogic.looksLikeSourceWrite(sql),
            clearBit1 = kind.clearsBit1,
            clearBit3 = kind.clearsBit3,
            own = SnsBitLogic.isOwnFix(sql),
            block = block,
        )
    }

    /**
     * 把 `& -9` 换成 `& -1` 并写回 `param.args[0]`（before 钩子里改，SQL 尚未执行）。
     *
     * @return [SnsBlockProbe.SqlBlock.BLOCKED] = 真的改了；[SnsBlockProbe.SqlBlock.FAILED] = 没改动，
     *   SQL 将**原样执行**（= 本条没拦住），由账本的 `blkFail` 暴露，⛔ 不许静默。
     */
    private fun applySqlRewrite(
        param: XC_MethodHook.MethodHookParam,
        sql: String,
    ): SnsBlockProbe.SqlBlock {
        val out = SnsSqlRewrite.rewrite(sql)
        if (out == sql) {
            LogX.w("BLOCK fail: 判定为可拦但改写无变化，本条原样执行 ${sql.take(200)}")
            return SnsBlockProbe.SqlBlock.FAILED
        }
        runCatching { param.args[0] = out }
            .onFailure {
                LogX.w("BLOCK fail: 写回 SQL 参数失败 ${it.javaClass.simpleName}")
                return SnsBlockProbe.SqlBlock.FAILED
            }
        return SnsBlockProbe.SqlBlock.BLOCKED
    }

    // ------------------------------------------------------------------ execSQL 计数判据
    // R42：判据已抽到 [SnsBitLogic]（纯函数，可单测）；这里不再内联正则。

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

    /** 只读暴露宿主 ClassLoader，给 [SnsPrefixProbe] 反序列化 `content` BLOB 用 */
    internal fun hostLoaderHandle(): ClassLoader? = hostLoader

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
        "SNS stat blockEnabled=${cfg().blockEnabled} sqlBlock=${cfg().sqlBlockEnabled} " +
            "albumFix=${cfg().albumFixEnabled} snsDb=${snsDb != null}"
}
