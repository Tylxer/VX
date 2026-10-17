package com.lx.wxgroup.feature

import android.content.ContentValues
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 防撤回（需求 6.2 的第一项：防止消息撤回）。
 *
 * ## 锚点是怎么定下来的（8.0.48 真机实测，不是猜的）
 *
 * 撤回**不是删消息**，是把原消息改写成撤回状态。真机抓到两种写库形态：
 *
 * | 谁撤回 | 写库 | type | content | isSend |
 * |--------|------|------|---------|--------|
 * | 他人   | 一次 update | `0x10000000 or 10000` | `"昵称" 撤回了一条消息` | 0 |
 * | 自己   | 第 1 次 update | `0x10000000 or 10002` | **原文保留**（留着给「重新编辑」） | 被置 0 |
 * | 自己   | 第 2 次 update | `0x10000000 or 10000` | `你撤回了一条消息` | 0 |
 *
 * 实证（`ardump` 打出来的真实行）：
 * ```
 * msgId=19139997 isSend=1 type=1          content=123                ← 没被撤（正常）
 * msgId=19139996 isSend=1 type=1          content=123                ← 撤回被我们拦死，原样保留
 * msgId=19139995 isSend=0 type=268445458  content=123                ← 自己撤回第 1 步（10002，原文还在）
 * msgId=19139993 isSend=0 type=268445456  content=你撤回了一条消息     ← 自己撤回第 2 步（10000）
 * msgId=19139986 isSend=0 type=268445456  content="verity" 撤回了一条消息 ← 他人撤回
 * ```
 *
 * 调用栈（自己撤回 / 他人撤回走同一段）：
 * ```
 * com.tencent.mm.storage.j9.va ← vj0.s.i ← vj0.s.b9 ← …messenger.foundation.u0 ← plugin.zero.y0.d
 * ```
 *
 * ## ⚠️ 为什么不能用 isSend 一刀切（踩过的坑，改过一次判据）
 * 撤回会把 `isSend` 一起改成 0，**自己发的消息撤回后 isSend 也是 0**。
 * 早先一律用 `isSend` 判「是不是自己撤回」，结果自己撤回被判成他人撤回、被误拦。
 *
 * 真正可靠的时机只有 **10002 那一步**：此刻行还是原值（实测自己发的消息在那里读到 1）。
 * 到 10000 那一步时 isSend 已经是 0，只能靠**文案**：
 * - 自己撤回 → `你撤回了一条消息`
 * - 他人撤回 → `"昵称" 撤回了一条消息`
 *
 * 所以判据是：**10002 看 isSend，10000 看文案**。两者都判不出来时一律保守放行。
 *
 * ## 为什么拦在 SQLite 层而不是 hook `j9.va` / `vj0.s.b9`
 * 那两个是混淆名，换版本就失效；而**表名 message / 字段名 type / content / digest /
 * msgId / isSend / talker 都是未混淆的**，写库语义也稳定。拦在底层换版本更抗造。
 * 代价是拿不到内存里的消息对象（所以要反查 isSend / talker）。
 *
 * ## 已知代价：拦截会引发微信重试风暴
 * 拦掉一次撤回后，微信会在 8 个 DB 线程上**反复重试同一条 update**
 * （实测同一 msgId 7.5 分钟内被拦 1966 次）。消息本身是留住了，但：
 * - 每次重试都要走一遍判定，查库 + 写日志会很贵；
 * - 我们的日志有行数上限，刷爆之后别的诊断日志全丢。
 *
 * 所以做了 [blocked] 记忆：**同一 msgId 首次完整判定并记入之后，后续重试走静默路径**
 * —— 只拦、不查库、不打日志，每 500 次补一条汇总。
 *
 * ## 热路径开销
 * `updateWithOnConflict` 每秒会被调用很多次，所以判断顺序是：
 * 表名 → ContentValues → type 低位 → 文案 → **最后才读配置**。
 * 也就是说只有长得像撤回的那几次写入才会去查配置库。
 *
 * ## 边界
 * - 只作用于**他人撤回**；自己撤回完全走微信原生流程（需求 6.2 的作用域条款）。
 * - 判不出来时**保守放行**（宁可漏拦，也不能把用户自己的撤回搞坏）。
 * - 「显示撤回提示」的隐藏（含历史提示）是渲染层的事，不在这里，见 Keys.ANTIREVOKE_SHOW_HINT。
 */
object AntiRevoke {

    private const val TABLE_MESSAGE = "message"
    private const val TABLE_CONV = "rconversation"

    /** 撤回文案的公共后缀：认「这次写入是不是撤回」靠它 */
    private const val HINT = "撤回了一条消息"

    /** 自己撤回时的文案前缀：认「是不是自己撤回」靠它（他人撤回是 `"昵称" 撤回了一条消息`） */
    private const val SELF_HINT = "你撤回了一条消息"

    /**
     * 撤回的两种写库形态（真机都抓到过，只认一种会漏）：
     * - [SYS_TYPE_HINT] = 10000：把原消息**改写成撤回提示**（content 变成撤回文案）。
     * - [SYS_TYPE_REVOKED] = 10002：**保留原消息内容**，只打「已撤回」标记
     *   （自己撤回的第 1 步，原文留着给「重新编辑」）。
     *
     * ⚠️ 早期只判 10000，结果第二种形态连「撤回形状」都识别不到，功能等于半个失灵。
     * type 的高位 0x10000000 是微信自己加的标志，比较低 16 位即可。
     */
    private const val SYS_TYPE_HINT = 10000
    private const val SYS_TYPE_REVOKED = 10002

    /** 拦下 message 之后，同会话的摘要要在这么久之内一起拦掉 */
    private const val CONV_WINDOW_MS = 3000L

    /** 同一个 msgId 的「已拦过」记忆有效期；过期后重新走一次完整判定（顺带留一条日志） */
    private const val BLOCK_TTL_MS = 60_000L

    /** 伪装的返回值：原生预期就是更新 1 行，返回 1 比返回 0（0 行受影响）更像成功 */
    private const val FAKE_ROWS = 1

    // ------------------------------------------------------------------ 配置

    private data class Config(
        val enabled: Boolean,
        val alsoSelf: Boolean,
        /** 「显示撤回提示」：拦下之后要不要额外插一条我们自己生成的「已阻止」提示行 */
        val showHint: Boolean,
        /** 调试日志开关（Keys.DEBUG_LOG）：只有开着才把消息原文 / talker 写进日志 */
        val debugLog: Boolean,
    )

    @Volatile
    private var config = Config(false, false, false, false)

    @Volatile
    private var cfgAt = 0L

    /**
     * 「这条写入是我们自己发的」标记，供 [RevokeRescue] 用。
     *
     * 「恢复默认显示」要把消息写成 `type=10000` + 撤回文案 —— 跟撤回写入**一模一样**，
     * 不打个标记就会被本类当成真撤回拦下来（自己拦自己，功能永远看不见效果）。
     */
    internal val selfWrite = ThreadLocal<Boolean>()

    /** [lastSeenAt]：采样日志节流用的时间戳 */
    @Volatile
    private var lastSeenAt = 0L

    /** 形状日志节流：同一 msgId 最多 2 秒一条（风暴里同一条会被判上千次） */
    @Volatile
    private var shapeKey: String? = null

    @Volatile
    private var shapeAt = 0L

    /**
     * 插入探针的总开关：**每次 insert 都会经过这个钩子**，所以判断必须便宜到
     * 只剩一次 volatile 读。值跟着 DEBUG_LOG 走，关掉调试后探针等于不存在。
     */
    @Volatile
    private var probeOn = false

    fun refresh() {
        val debugLog = Kv.getBool(Keys.DEBUG_LOG)
        config = Config(
            enabled = Kv.getBool(Keys.ANTIREVOKE_MSG),
            alsoSelf = Kv.getBool(Keys.ANTIREVOKE_DEBUG_SELF),
            showHint = Kv.getBool(Keys.ANTIREVOKE_SHOW_HINT),
            debugLog = debugLog,
        )
        probeOn = debugLog
        cfgAt = SystemClock.elapsedRealtime()
    }

    private fun config(): Config {
        // 用 elapsedRealtime：系统时间被回拨时不会导致长期不刷新
        if (SystemClock.elapsedRealtime() - cfgAt > 3000) refresh()
        return config
    }

    // ------------------------------------------------------------ 待拦摘要队列

    private data class Pending(val talker: String, val text: String, val at: Long)

    private val pendingLock = Any()
    private val pending = ArrayList<Pending>(4)

    private fun addPending(p: Pending) {
        synchronized(pendingLock) {
            dropExpired(SystemClock.elapsedRealtime())
            // 重试风暴会让同一个 (talker, 文案) 被 add 上千次，去重后队列里始终只有一条
            val exists = pending.any { it.talker == p.talker && sameText(it.text, p.text) }
            if (!exists) pending.add(p)
        }
    }

    /** 命中即消费（从队列移除），避免同一次撤回的摘要被拦两次、也避免别的会话误拿 */
    private fun takePending(talker: String, text: String): Pending? {
        synchronized(pendingLock) {
            dropExpired(SystemClock.elapsedRealtime())
            val i = pending.indexOfFirst { it.talker == talker && sameText(it.text, text) }
            return if (i < 0) null else pending.removeAt(i)
        }
    }

    private fun dropExpired(now: Long) {
        pending.removeAll { now - it.at > CONV_WINDOW_MS }
    }

    /**
     * 文案比对：先做规范化（去首尾空白、折叠连续空白、剥掉最外层配对的引号），
     * 规范化后相等才算同一条。
     *
     * 兜底的 contains 只是防止微信把摘要格式微调（比如多一个前缀）时整条功能失效 ——
     * 那时宁可多拦一次摘要，也不要出现「消息还在、列表却显示已撤回」的不一致。
     */
    private fun sameText(a: String, b: String): Boolean {
        val x = canon(a)
        val y = canon(b)
        return x == y || x.contains(y) || y.contains(x)
    }

    private fun canon(s: String): String {
        var t = s.trim().replace(Regex("\\s+"), " ")
        // 剥掉最外层成对引号（微信的撤回文案形如 "昵称" 撤回了一条消息）
        if (t.length >= 2) {
            val f = t.first()
            val l = t.last()
            if ((f == '"' && l == '"') || (f == '\'' && l == '\'') || (f == '“' && l == '”')) {
                t = t.substring(1, t.length - 1).trim()
            }
        }
        return t
    }

    // ---------------------------------------------------- 已拦截记忆（抗重试风暴）

    private class Blocked(
        val at: Long,
        val talker: String?,
        val pairText: String,
        /**
         * 首次判定的「是不是自己撤回」：`true` 自己 / `false` 他人 / `null` 判断不了。
         *
         * ⚠️ 必须是三态，不能压成 Boolean：判断不了（null）时之所以还会拦，
         * 只是因为当时 alsoSelf 开着；一旦 alsoSelf 关掉就得放行，压成 Boolean
         * 会把「判断不了」记成「他人撤回」，开关关了还照拦不误。
         */
        val self: Boolean?,
    ) {
        val hits = AtomicInteger(0)
    }

    private val blocked = ConcurrentHashMap<String, Blocked>()

    /**
     * 会话摘要的「已拦过」兜底：key = `talker|canon(digest)`，value = 上次拦的时刻。
     *
     * 为什么需要它：摘要是靠 [pending] 一次性配对消费的，万一某次摘要更新正好落在
     * 两次 addPending 的空档里就会漏拦，出现「消息还在、列表却显示已撤回」。
     * ⚠️ TTL 故意很短（10 秒）：万一 message 那边不再拦了（比如用户关了开关），
     * 摘要必须尽快跟着放行，否则会变成「消息已撤回、列表还显示旧摘要」的反向不一致。
     */
    private val convBlocked = ConcurrentHashMap<String, Long>()
    private const val CONV_BLOCK_TTL_MS = 10_000L

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        // 只挂 updateWithOnConflict：`update()` 内部就是转调它，挂一个就够，
        // 挂两个会让同一条撤回被处理两次。
        val cls = runCatching {
            XposedHelpers.findClass("com.tencent.wcdb.database.SQLiteDatabase", cl)
        }.getOrNull()
        if (cls == null) {
            LogX.w("AR: wcdb SQLiteDatabase not found, antirevoke disabled")
            return
        }
        // 读配置本身也不允许影响微信：失败就保持默认（全关）
        runCatching { refresh() }.onFailure { LogX.w("AR refresh failed: ${it.javaClass.simpleName}") }
        val set = runCatching {
            XposedBridge.hookAllMethods(cls, "updateWithOnConflict", revokeGuard)
        }.onFailure { LogX.e("AR: hook failed", it) }.getOrNull()
        if (set.isNullOrEmpty()) {
            LogX.w("AR: updateWithOnConflict not found, antirevoke disabled")
            return
        }
        // 只记录不拦截：用来确认「撤回会不会绕过 update 走 INSERT OR REPLACE 写库」。
        // 自己撤回的第 1 步（10002）有几次没被 update 钩子看到，这个探针就是为它准备的。
        runCatching { XposedBridge.hookAllMethods(cls, "insertWithOnConflict", insertProbe) }
            .onFailure { LogX.w("AR insert probe not installed: ${it.javaClass.simpleName}") }
        LogX.i("AR installed (antirevoke.msg=${config.enabled} alsoSelf=${config.alsoSelf})")
    }

    private val revokeGuard = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // 拦截钩子绝不能把异常抛回宿主：失败就是「这次不拦」
            runCatching { guard(param) }
                .onFailure { LogX.w("AR guard error: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * 只看不拦的插入探针：命中「message 表里 type 长成撤回样子的插入」就记一行。
     *
     * 存在的理由：有两条被撤回的消息（type=10002）在日志里**没有对应的 update**，
     * 怀疑是走了 `INSERT OR REPLACE`。在没证实之前不敢直接拦（type=10000 的正常系统
     * 消息也是 INSERT 进来的，误拦会弄坏系统提示），所以先只打日志。
     */
    private val insertProbe = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching { probeInsert(param) }
        }
    }

    private fun probeInsert(param: XC_MethodHook.MethodHookParam) {
        // 每次 insert 都会进这里，先做一次 volatile 读把开销压到最低
        if (!probeOn) return
        val a = param.args ?: return
        if (a.getOrNull(0) as? String != TABLE_MESSAGE) return
        val values = a.getOrNull(1) as? ContentValues ?: return
        val type = values.getAsInteger("type") ?: return
        val low = type and 0x0000FFFF
        if (low != SYS_TYPE_HINT && low != SYS_TYPE_REVOKED) return
        LogX.i(
            "AR insert-shape message type=$type low=$low " +
                "msgId=${values.getAsLong("msgId")} " +
                "content=${values.getAsString("content")?.take(24)}"
        )
    }

    // ------------------------------------------------------------------ 判定

    private fun guard(param: XC_MethodHook.MethodHookParam) {
        val a = param.args ?: return
        // 记住 DB 实例：调试用的 dumpRecent() 只能从这里拿到（模块没有别的方式拿到微信的库）。
        // ⚠️ 必须在判表名**之前**记 —— 发消息走的是 insert，只有会话摘要这类 update
        // 才会进这里，记录得太靠后就永远拿不到实例。
        // 只在实例变了的时候写：这是最热的一段路径，能省一次 volatile 写就省。
        if (lastDb !== param.thisObject) lastDb = param.thisObject
        // 「恢复默认显示」是持久开关，但微信重启后库里是原样 —— 等库真正可用时补写一次。
        // ⚠️ 这也是**界面上切开关**唯一的感知点：设置页走 `Kv.putBool` 没有任何回调，
        // 只有这里定时把库里的显示状态和开关对齐，否则开关关掉后伪装行永远不还原
        // （用户看到的就是「消息丢了」）。内部有 3 秒节流，热路径上开销可忽略。
        RevokeRescue.syncIfNeeded()
        val table = a.getOrNull(0) as? String ?: return
        // 先廉价判表名 —— 这个方法每秒都会被调用很多次
        if (table != TABLE_MESSAGE && table != TABLE_CONV) return
        val values = a.getOrNull(1) as? ContentValues ?: return
        // 记下「消息库」：后面的插入 / 伪装都要写到这个库上，用错库会 no such table
        if (table == TABLE_MESSAGE && msgDb !== param.thisObject) msgDb = param.thisObject

        // 采样：证明钩子真的被调用了（节流到 5 秒一条）。
        // 撤回没拦住时，这条是区分「钩子没跑」和「跑了但自己 return 了」的关键。
        // ⚠️ 别把节流调密：重试风暴里同一条撤回会被判上千次，日志有 5000 行上限，
        // 刷爆之后别的诊断日志全丢。
        if (table == TABLE_MESSAGE && probeOn) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSeenAt > 5000) {
                lastSeenAt = now
                LogX.i("AR seen message keys=${values.keySet().joinToString(",").take(60)}")
            }
        }

        if (table == TABLE_MESSAGE) {
            val type = values.getAsInteger("type") ?: return
            val low = type and 0x0000FFFF
            val content = values.getAsString("content")
            // 形态一：改写成撤回提示；形态二：保留原文只打撤回标记
            val isHint = low == SYS_TYPE_HINT && content != null && content.contains(HINT)
            val isMark = low == SYS_TYPE_REVOKED
            if (!isHint && !isMark) return

            // ⚠️ 到这一步才读配置：长成「撤回形状」的写入极少，
            // 否则每次写库都要查一次配置库
            val c = config()
            // 诊断：确认「判定到底走到哪一步」——没这行就只能靠猜
            // （撤回没拦住时，首先要区分的是「钩子没被调用」还是「调用了自己 return 了」）
            // ⚠️ 这条日志在静默路径**之前**，风暴里每次重试都会走到，必须节流
            // （1966 次重试就是靠它把日志刷到 5000 行上限的）
            if (c.debugLog) {
                val k = values.getAsLong("msgId")?.toString() ?: "?"
                val now = SystemClock.elapsedRealtime()
                if (k != shapeKey || now - shapeAt > 2000) {
                    shapeKey = k
                    shapeAt = now
                    LogX.i("AR shape message type=$type low=$low isHint=$isHint isMark=$isMark " +
                        "msgId=$k enabled=${c.enabled} " +
                        "alsoSelf=${c.alsoSelf} content=${content?.take(24)}")
                }
            }
            if (!c.enabled) return
            // 配对会话摘要用的文案：形态二的原 content 还是消息正文，跟摘要对不上，
            // 所以用固定短语去匹配摘要（摘要里一定是「…撤回了一条消息」）
            val pairText = if (isHint) content!! else HINT
            guardMessage(param, values, a, pairText, content, isHint, c)
        } else {
            val digest = values.getAsString("digest") ?: return
            if (!digest.contains(HINT)) return
            val c = config()
            if (c.debugLog) {
                LogX.i("AR shape conv enabled=${c.enabled} " +
                    "talker=${values.getAsString("username")?.take(12)} digest=${digest.take(24)}")
            }
            if (!c.enabled) return
            guardConversation(param, values, a, digest)
        }
    }

    /**
     * 判断这次撤回是不是「自己撤回」。
     *
     * - `true`  = 自己撤回 → 正常模式必须放行
     * - `false` = 他人撤回 → 拦
     * - `null`  = 判断不了 → 保守放行
     *
     * ⚠️ 两种形态的判据**不能互换**（见类注释里的表）：
     * 10002 那一步 `isSend` 还是原值，可信；10000 那一步 isSend 已被前面的步骤改成 0，
     * 只能看文案是「你撤回了一条消息」还是「"昵称" 撤回了一条消息」。
     */
    private fun isSelfRevoke(isHint: Boolean, content: String?, meta: Meta?): Boolean? {
        if (isHint) {
            // canon 一遍再比：剥掉可能的外层引号、折叠空白，免得格式微调就判错
            val c = canon(content ?: return null)
            if (c.isEmpty()) return null
            // 光秃秃的一句「撤回了一条消息」分不清是谁，交给保守放行
            if (c == HINT) return null
            return c.startsWith(SELF_HINT)
        }
        // 形态二：此刻 isSend 还是原始值（实测自己发的消息在这里读到 1）
        return when (meta?.isSend) {
            1 -> true
            0 -> false
            else -> null
        }
    }

    private fun guardMessage(
        param: XC_MethodHook.MethodHookParam,
        values: ContentValues,
        args: Array<Any?>,
        pairText: String,
        rawContent: String?,
        isHint: Boolean,
        c: Config,
    ) {
        // 「恢复默认显示」的伪装写入长得跟撤回一样，别把自己写的也拦了
        if (selfWrite.get() == true) return

        // values 里就带 msgId（实测），拿不到再退回 whereArgs —— 但要确认 where 确实是 msgId=?
        val msgId = values.getAsLong("msgId")?.toString() ?: msgIdFromWhere(args)
        val key = msgId ?: "?"

        // ── 静默路径 ──────────────────────────────────────────────────
        // 微信被拦之后会不停重试同一条 update（实测 1966 次 / 7.5 分钟）。
        // 首次已经完整判定过了，这里直接照搬结论：只拦、不查库、不打日志。
        val seen = blocked[key]
        if (seen != null && SystemClock.elapsedRealtime() - seen.at < BLOCK_TTL_MS) {
            val cc = config()
            if (!cc.enabled) return
            // 配置可能中途改过：比如关掉 alsoSelf 后，自己撤回（以及「判断不了」的那次）
            // 就不该再拦。`!= false` 是关键：只有明确判成「他人撤回」才无视 alsoSelf。
            if (seen.self != false && !cc.alsoSelf) return
            param.result = FAKE_ROWS
            if (!seen.talker.isNullOrBlank()) {
                // addPending 内部有去重，风暴里重复 add 不会让队列膨胀
                addPending(Pending(seen.talker, seen.pairText, SystemClock.elapsedRealtime()))
            }
            val n = seen.hits.incrementAndGet()
            if (n % 100 == 0) LogX.w("AR retry storm msgId=$key hits=$n")
            return
        }

        val meta = msgId?.let { queryMetaCached(param.thisObject, it) }
        val self = isSelfRevoke(isHint, rawContent, meta)
        // 诊断：把反查的**原始值**打出来。只看 self 的真假没法区分
        // 「查错了行」和「判据的语义和我以为的不一样」
        if (c.debugLog) {
            LogX.i("AR meta msgId=$msgId isSend=${meta?.isSend} " +
                "talker=${meta?.talker?.take(10)} self=$self hint=${rawContent?.take(16)}")
        }

        // 自己撤回（被撤的那条是自己发的）必须放行；判断不了时保守放行。
        // alsoSelf 是调试开关：开着连自己撤回也拦，用来在不借小号的情况下验证拦截生效。
        if (self == true && !c.alsoSelf) {
            LogX.i("AR pass self-revoke msgId=$msgId")
            return
        }
        if (self == null && !c.alsoSelf) {
            LogX.w("AR pass: cannot tell who revoked (msgId=$msgId)")
            return
        }

        // ⚠️ 顺序有讲究：**先拦下消息本体，再尽力去配对摘要**。
        // 早期版本把「拿不到 talker」当成硬失败直接 return，结果连消息本体都没拦 ——
        // talker 只是用来拦会话摘要的辅助信息，拿不到最多是摘要还显示撤回提示，
        // 绝不能因此放弃拦截（那才是这个功能的全部价值）。
        param.result = FAKE_ROWS

        val talker = meta?.talker
        if (!talker.isNullOrBlank()) {
            addPending(Pending(talker, pairText, SystemClock.elapsedRealtime()))
        } else {
            LogX.w("AR: talker unknown, conv digest may still show revoke hint (msgId=$msgId)")
        }

        // msgId 拿不到时别往记忆里塞：所有这类写入会共用同一个 "?" key，
        // 会让后面**别的**撤回被误判成「已判过」而跳过判定
        if (msgId != null) {
            blocked[key] = Blocked(SystemClock.elapsedRealtime(), talker, pairText, self)
        }
        if (blocked.size > 128) {
            val now = SystemClock.elapsedRealtime()
            blocked.entries.removeIf { now - it.value.at > BLOCK_TTL_MS }
        }

        // 登记台账（「恢复默认显示」要靠它把原文写回来），再按当前开关决定这条怎么显示。
        // ⚠️ 10002 形态的 content 是**原消息正文**、不是撤回文案，不能拿去当伪装文案
        //    （否则用户会看到「"xx" 撤回了一条消息」其实是自己刚发的正文，更莫名其妙）。
        // ⚠️ 台账没记成功就**不许伪装**：改写会覆盖 content，没有备份 = 消息原文永久丢失。
        // ⚠️ 真机实测：微信真撤回写的字段不止 type+content，还有 lvbuffer / isSend / reserved。
        //    只重放 type+content 那行会处于混合态，微信渲染成整页空白（2026-10-16 踩过）。
        //    所以这里把**完整 ContentValues**（除 msgId）快照进台账，伪装时原样重放。
        val revokeText = if (isHint) rawContent else null
        val extra = runCatching { CvCodec.encodeFrom(values, skip = setOf("msgId")) }
            .onFailure { LogX.w("AR snapshot failed: ${it.javaClass.simpleName}") }
            .getOrNull()
        msgId?.toLongOrNull()?.let { id ->
            runCatching {
                if (RevokeRescue.record(id, talker ?: "", revokeText, extra)) {
                    RevokeRescue.applyOne(id, revokeText, extra)
                    // 「显示撤回提示」：插一条我们自己生成的「已阻止」提示行。
                    // ⚠️ 必须判重 —— 拦下之后微信会重试上千次，不判重会插出上千条提示。
                    if (c.showHint && !talker.isNullOrBlank()) {
                        RevokeRescue.insertHintOnce(id, talker, revokeText)
                    }
                }
            }.onFailure { LogX.w("RRV hook failed: ${it.javaClass.simpleName}") }
        }

        LogX.i(
            "AR blocked revoke msgId=$msgId self=$self" +
                (if (self == true) " [debug alsoSelf]" else "") +
                if (c.debugLog) " content=${rawContent?.take(40)}" else ""
        )
    }

    /** 兜底：values 里没带 msgId 时，只有 where 严格是 `msgId=?` 才敢取 whereArgs[0] */
    private fun msgIdFromWhere(args: Array<Any?>): String? {
        val where = args.getOrNull(2) as? String ?: return null
        if (!where.equals("msgId=?", ignoreCase = true)) return null
        val wa = args.getOrNull(3) as? Array<*> ?: return null
        return wa.firstOrNull()?.toString()
    }

    private fun guardConversation(
        param: XC_MethodHook.MethodHookParam,
        values: ContentValues,
        args: Array<Any?>,
        digest: String,
    ) {
        // 空串也算没拿到，走 where 兜底
        val talker = values.getAsString("username")?.takeIf { it.isNotBlank() } ?: talkerFromWhere(args)
        if (talker.isNullOrBlank()) return
        val cbKey = talker + "|" + canon(digest)
        val now = SystemClock.elapsedRealtime()
        // 必须是「刚刚被我们拦掉的那次撤回」，按 talker + 文案匹配后消费
        if (takePending(talker, digest) == null) {
            // 兜底：pending 是一次性消费的，万一这次摘要落在两次 add 的空档里就会漏。
            // 只有最近真的拦过同 key 才继续拦（TTL 很短，见 convBlocked 的注释）。
            val last = convBlocked[cbKey]
            if (last == null || now - last > CONV_BLOCK_TTL_MS) {
                convBlocked.remove(cbKey)
                return
            }
        }
        // ⚠️ **只摘掉 digest 这一个键，update 照常执行** —— 早期版本是整条吞掉
        // （`param.result = FAKE_ROWS`），结果这次 update 里带的 unReadCount /
        // conversationTime / flag 一起被拦，表现为「会话列表不刷新、未读数消不掉」。
        // 摘掉 digest 后：摘要保留旧值（这才是目的），其余状态正常落库。
        values.remove("digest")
        convBlocked[cbKey] = now
        if (convBlocked.size > 64) {
            convBlocked.entries.removeIf { now - it.value > CONV_BLOCK_TTL_MS }
        }
        if (values.size() == 0) {
            // 摘完没东西可写了：SQLite 的 update 不接受空 ContentValues
            // （会抛 IllegalArgumentException），这时才退回「整条拦掉」的老做法
            param.result = FAKE_ROWS
        }
        LogX.i(
            "AR blocked conv digest" +
                if (config.debugLog) " talker=$talker" else ""
        )
    }

    /** 兜底：values 里没带 username 时，从 `where username=?` 的 whereArgs 里取 */
    private fun talkerFromWhere(args: Array<Any?>): String? {
        val where = args.getOrNull(2) as? String ?: return null
        if (!where.equals("username=?", ignoreCase = true)) return null
        val wa = args.getOrNull(3) as? Array<*> ?: return null
        return wa.firstOrNull()?.toString()
    }

    // ------------------------------------------------------------------ 反查

    private data class Meta(val isSend: Int?, val talker: String?)

    /**
     * 反查缓存：msgId → (查到的时刻, Meta)。
     *
     * ⚠️ 拦截撤回会让微信**反复重试同一条 update**（实测 5 秒内 360 次），
     * 每次都去 rawQuery 会把这个重试风暴放大成 360 次查询。同一个 msgId 缓存几秒即可。
     */
    private val metaCache = ConcurrentHashMap<String, Pair<Long, Meta>>()
    private const val META_TTL_MS = 5000L

    /** 最近见到的微信 DB 实例，供 [dumpRecent] 用（平时只有 hook 回调里拿得到） */
    @Volatile
    private var lastDb: Any? = null

    /**
     * **写过 message 表的那个库**（微信有多个 db，只有这个能查消息）。
     * 没它的话 `select ... from message` 会报 `no such table: message`。
     *
     * ⚠️ 会从微信的多个 DB 线程写，所以必须 volatile（普通字段可能让别的线程
     * 一直读到 null，诊断工具就永远卡在「no DB instance yet」）。
     */
    @Volatile
    private var msgDb: Any? = null

    /**
     * 微信的消息库实例。RevokeRescue 要写同一张表，从这儿拿（模块没有别的入口）。
     *
     * ⚠️ **只认 [msgDb]（`message` 表所在的那个库），绝不退到 [lastDb]**。
     * 微信不止一个数据库，`lastDb` 是「最近一次任意表 update 的库」，可能是某个业务库，
     * 在上面读写 message 会直接 `no such table: message`。
     *
     * ⚠️ 早期版本写成 `msgDb ?: lastDb`，后果是：模块刚起来时 `msgDb` 还是 null，
     * `ensureApplied` 拿着错误的库去写，`runCatching` 把异常一吞、标志位却已经置上
     * → 「恢复默认显示」在本次微信生命周期内彻底失效，而且日志里只有一行 warning。
     * 宁可返回 null 让调用方下一轮重试，也不要拿一个可能错的库硬写。
     */
    internal fun wxDb(): Any? = msgDb

    /** 消息库还没抓到时为 true（[AntiRevokeHint] 据此在读取侧顺手记一次） */
    internal fun needsMsgDb(): Boolean = msgDb == null

    internal fun noteMsgDb(db: Any) {
        if (msgDb !== db) msgDb = db
    }

    /**
     * 给**只读诊断探针**用的查询句柄：`消息库实例 + rawQuery 反射方法`。
     *
     * 存在的理由：模块里唯一能拿到微信消息库的地方就是这里的写库 hook
     * （`msgDb` 在 updateWithOnConflict 里捕获），别的探针没别的入口。
     * 与其让每个探针各自去试 `SQLiteDatabase` 的各种入口，不如共用这一条实证过的路。
     *
     * @return 任一缺失就返回 null（消息库还没抓到 / rawQuery 没找到），
     *         调用方据此打一行说明，**不要**退到别的东西上重试（早期版本退 [lastDb]
     *         的教训见 [wxDb] 的注释）。
     */
    internal fun msgQueryHandle(): Pair<Any, Method>? {
        val db = msgDb ?: return null
        val m = rawQueryMethod(db) ?: return null
        return db to m
    }

    @Volatile
    private var rawQueryMethod: Method? = null

    /**
     * 用 msgId 一次查出 `isSend` 和 `talker`。
     *
     * ⚠️ 这里是**在写库 hook 内部**做一次读：走的是 rawQuery，不会回到
     * updateWithOnConflict，所以不会递归；before 发生在本次 update 拿到写锁之前，
     * 同线程读不会自锁。查不到 / 抛异常一律返回 null（保守放行）。
     */
    private fun queryMetaCached(db: Any, msgId: String): Meta? {
        lastDb = db
        val now = SystemClock.elapsedRealtime()
        metaCache[msgId]?.let { (at, m) ->
            if (now - at < META_TTL_MS) return m
        }
        val m = queryMeta(db, msgId) ?: return null
        metaCache[msgId] = now to m
        // 防止长时间运行后无限增长：顺手清掉过期的
        if (metaCache.size > 64) {
            metaCache.entries.removeIf { now - it.value.first > META_TTL_MS }
        }
        return m
    }

    private fun queryMeta(db: Any, msgId: String): Meta? {
        val m = rawQueryMethod(db)
        if (m == null) {
            LogX.w("AR: rawQuery not found on ${db.javaClass.name}")
            return null
        }
        return runCatching {
            // ⚠️ WCDB 同时有 `rawQuery(String, String[])` 和 `rawQuery(String, Object[])`：
            // 参数数组的类型必须跟查到的那个方法的形参一致，否则 invoke 直接抛
            // IllegalArgumentException，表现就是「查不到」（早期就是栽在这里）。
            val bindArgs: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
                arrayOf(msgId)
            } else {
                arrayOf<Any>(msgId)
            }
            // ⚠️ [AntiRevokeHint.BYPASS] 前缀：模块自己的诊断查询必须绕过「隐藏撤回提示」
            // 的 SQL 改写，否则 ardump 看不到任何他人撤回行（取证工具自己瞎了）
            val cur = m.invoke(
                db,
                AntiRevokeHint.BYPASS + "select isSend,talker from $TABLE_MESSAGE where msgId=?",
                bindArgs
            )
            if (cur == null) {
                LogX.w("AR: rawQuery returned null cursor")
                return null
            }
            try {
                val moved = XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean ?: false
                if (!moved) {
                    LogX.w("AR: no row for msgId=$msgId (meta lookup miss)")
                    return null
                }
                // wcdb 的 Cursor 不是 android.database.Cursor（是它自己的接口），全程反射
                val isSend = XposedHelpers.callMethod(cur, "getInt", 0) as? Int
                val talker = XposedHelpers.callMethod(cur, "getString", 1) as? String
                Meta(isSend, talker)
            } finally {
                // 游标必须关：读失败也要关，否则泄漏。关失败至少留下一条日志
                runCatching { XposedHelpers.callMethod(cur, "close") }
                    .onFailure { LogX.w("AR cursor close failed: ${it.javaClass.simpleName}") }
            }
        }.onFailure { LogX.w("AR query meta failed: ${it.javaClass.simpleName}") }.getOrNull()
    }

    /**
     * 调试用：把最近几条消息的真实字段打出来。
     *
     * 存在的意义是**不触发撤回也能看清 isSend / type 的真实语义** ——
     * 「这条消息是不是自己发的」「撤回走的是哪种形态」全靠这些字段判断，
     * 而它们的取值跟预期对不上时，只能把真实数据打出来看（靠猜会浪费很多轮真机验证）。
     *
     * 用法：
     * `adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
     *      --es op ardump [--es sub all] [--ei n 12]`
     *
     * @param onlyRevoked true = 只看 type 低 16 位是 10000 / 10002 的记录（默认）
     */
    fun dumpRecent(limit: Int = 8, onlyRevoked: Boolean = true): List<String> {
        // ⚠️ 必须是 wxDb()（写过 message 表的那个库），不能用 lastDb ——
        // 微信有多个 db，在别的库上查 message 会 "no such table: message"
        val db = wxDb() ?: return listOf("no DB instance yet (先让微信写一次库)")
        val m = rawQueryMethod(db) ?: return listOf("rawQuery not found on ${db.javaClass.name}")
        return runCatching {
            val where = if (onlyRevoked) {
                // 只看撤回相关的记录：目的是看清「自己撤回」和「他人撤回」到底长什么样
                "where (type & 65535) in ($SYS_TYPE_HINT,$SYS_TYPE_REVOKED) "
            } else ""
            // ⚠️ [AntiRevokeHint.BYPASS]：dump 必须绕过「隐藏撤回提示」的改写，
            // 否则它要查的恰恰是被隐藏的那几行，等于自己把自己屏蔽了
            val sql = AntiRevokeHint.BYPASS +
                "select msgId,isSend,type,talker,content from $TABLE_MESSAGE " +
                where + "order by msgId desc limit $limit"
            val cur = m.invoke(db, sql, null) ?: return listOf("null cursor")
            val out = ArrayList<String>()
            try {
                var n = 0
                while (n < limit && XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                    val msgId = XposedHelpers.callMethod(cur, "getLong", 0)
                    val isSend = XposedHelpers.callMethod(cur, "getInt", 1)
                    val type = XposedHelpers.callMethod(cur, "getInt", 2)
                    val talker = XposedHelpers.callMethod(cur, "getString", 3) as? String
                    val content = XposedHelpers.callMethod(cur, "getString", 4) as? String
                    out.add("msgId=$msgId isSend=$isSend type=$type " +
                        "talker=${talker?.take(10)} content=${content?.take(28)}")
                    n++
                }
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
            if (out.isEmpty()) listOf("empty result") else out
        }.getOrElse { listOf("dump failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * 「隐藏撤回提示」的一致性自检（诊断用）：
     * `adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
     *      --es op archeck --es talker wxid_xxx [--ei n 58]`
     *
     * **为什么需要它**：改写日志能看到 SQL 被改了、也没熔断，但界面上提示还在 ——
     * 这时候必须区分两种根本不同的失败：
     * 1. **条件没命中任何行**（数据长得跟判断不一样，比如文案/type 跟实测对不上）
     *    → `total` 和 `visible` 两个数相等；
     * 2. **SQL 确实生效了，但界面走的是缓存 / 别的渲染路径**
     *    → 两个数不等，且下面列出的 hidden 行有内容。
     *
     * 直接拿微信自己的库、用真实的 SQL 跑一遍，比在手机上肉眼判断快得多。
     */
    fun checkHide(talker: String, limit: Int = 58): List<String> {
        // ⚠️ 同 dumpRecent：必须是 wxDb()，用 lastDb 会 "no such table: message"
        val db = wxDb() ?: return listOf("no DB instance yet (先让微信写一次库)")
        val m = rawQueryMethod(db) ?: return listOf("rawQuery not found on ${db.javaClass.name}")
        if (talker.isBlank()) return listOf("talker 不能为空")

        val sub = "(select * from message where talker=? order by createTime desc limit $limit)"
        val out = ArrayList<String>()
        out.add("talker=$talker limit=$limit argType=${m.parameterTypes[1].name}")
        // 对照：不带子查询的最简形式，用来区分「参数绑定不行」和「子查询不行」
        out.add("plain   = " + scalar(m, db, "select count(*) from message where talker=?", talker))
        out.add("total   = " + scalar(m, db, "select count(*) from $sub", talker))

        // 隐藏的对象是我们自己插入的「已阻止」提示行，按 msgId 精确排除
        // ⚠️ 用 != 0L：提示行 msgId 是负数（RevokeRescue 的专用区间），写 > 0 拿不到任何 id
        val ids = com.lx.wxgroup.data.GroupDb.current()?.listRescued()
            ?.mapNotNull { it.hintMsgId.takeIf { id -> id != 0L } }.orEmpty()
        if (ids.isEmpty()) {
            out.add("hint rows = (none) ← 还没插入过「已阻止」提示行，SQL 不会被改写")
            return out
        }
        out.add("hintIds  = ${ids.take(8).joinToString(",")}${if (ids.size > 8) "…" else ""}")
        out.add("visible  = " + scalar(
            m, db, "select count(*) from $sub where ${SqlHideCond.notIn(ids)}", talker
        ))
        val hidden = query(
            m, db,
            "select msgId,type,content from $sub where not (${SqlHideCond.notIn(ids)}) limit 5",
            talker
        )
        if (hidden.isEmpty()) out.add("hidden = (none) ← 条件没命中任何行")
        else hidden.forEach { out.add("hidden  $it") }
        return out
    }

    private fun bindArgs(m: Method, vararg v: String): Any =
        if (m.parameterTypes.size > 1 && m.parameterTypes[1] == Array<String>::class.java) {
            arrayOf(*v)
        } else {
            arrayOf<Any>(*v)
        }

    private fun scalar(m: Method, db: Any, sql: String, talker: String): String =
        runCatching {
            val cur = m.invoke(db, AntiRevokeHint.BYPASS + sql, bindArgs(m, talker))
                ?: return "null cursor"
            try {
                val ok = XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean ?: false
                if (!ok) "no row" else (XposedHelpers.callMethod(cur, "getLong", 0) as? Long)?.toString() ?: "?"
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
        }.getOrElse { "err ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(120)}" }

    private fun query(m: Method, db: Any, sql: String, talker: String): List<String> =
        runCatching {
            val cur = m.invoke(db, AntiRevokeHint.BYPASS + sql, bindArgs(m, talker))
                ?: return listOf("null cursor")
            val out = ArrayList<String>()
            try {
                while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                    val msgId = XposedHelpers.callMethod(cur, "getLong", 0)
                    val type = XposedHelpers.callMethod(cur, "getInt", 1)
                    val content = XposedHelpers.callMethod(cur, "getString", 2) as? String
                    out.add("msgId=$msgId type=$type content=${content?.take(30)}")
                }
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
            out
        }.getOrElse { listOf("err ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(120)}") }

    private fun rawQueryMethod(db: Any): Method? {
        rawQueryMethod?.let { return it }
        val m = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "rawQuery" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    // WCDB 两个重载都要认：String[]（兼容 AOSP）与 Object[]
                    (it.parameterTypes[1] == Array<String>::class.java ||
                        it.parameterTypes[1] == Array<Any>::class.java)
            }
        }.getOrNull()
        rawQueryMethod = m
        return m
    }
}
