package com.lx.wxgroup.feature

import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX

/**
 * 「显示撤回提示」的隐藏侧实现（需求 6.2 最后一项：`Keys.ANTIREVOKE_SHOW_HINT`）。
 *
 * ## 语义（容易搞反，写死在这里）
 * - `antirevoke.show_hint = false`（**默认**）→ **隐藏**他人撤回产生的提示条，含历史提示
 * - `antirevoke.show_hint = true`            → 显示（本模块不介入）
 *
 * 需求原文：「关闭时，连历史已存在的撤回提示一并隐藏，不只是拦新的；只对他人撤回产生的提示生效」。
 * 所以本类是**读取侧**过滤，跟 [AntiRevoke]（写入侧拦截，让撤回根本不发生）是两件事：
 *
 * | 场景 | 谁负责 |
 * |------|--------|
 * | 防撤回开着 → 撤回被拦，压根不产生提示 | [AntiRevoke] |
 * | 防撤回关着 → 提示正常产生，但要**不显示** | 本类 |
 * | 装模块之前就躺在库里的历史提示 | 本类 |
 *
 * ## 为什么只能在读取侧做（渲染层走不通，实测过）
 * 聊天页的消息文本**不走 `TextView.setText`** —— 用探针 hook 过
 * `android.widget.TextView.setText`，进会话只抓到拍一拍气泡（`AvatarPatTipImageView`），
 * 撤回提示一次都没出现。微信用的是自绘文本控件，渲染层没有落点。
 *
 * 读取侧的真机取证（探针 hook `rawQueryWithFactory`，8.0.48）：
 * ```
 * SELECT * FROM ( SELECT * FROM message WHERE talker= 'wxid_xxx'
 *                 ORDER BY createTime DESC LIMIT 16) ORDER BY createTime ASC   ← 聊天页分页
 * select * from message where talker= 'wxid_xxx' order by createTime DESC limit 1
 * SELECT * FROM message WHERE msgId=?
 * SELECT COUNT(msgId) FROM message WHERE talker= 'wxid_xxx' AND createTime >= 0 ...
 * ```
 * ⚠️ **微信只用 `rawQueryWithFactory`，`rawQuery` 一次都没调到** —— 只 hook `rawQuery` 会全落空。
 *
 * ## ⚠️ 钩子回调里读配置会无限递归（复核揪出来的，差点必现 ANR）
 * `Kv.getBool` 要查模块自己的库，而 AOSP 的 `SQLiteDatabase.query` 内部会转调
 * `queryWithFactory → rawQueryWithFactory` —— 正好是我们 hook 的方法。
 * 而我们也 hook 了 `android.database.sqlite.SQLiteDatabase`，于是：
 * `config() → refresh() → Kv.getBool → query → 本钩子 → config() → …`
 * 展开两条子树（DEBUG_LOG + SHOW_HINT）直到栈溢出，DB 线程长卡。
 * 两道保险都留着：[inHook] 重入守卫 + [refresh] 先打时间戳再读库。
 *
 * SQL 改写本身在 [SqlHideCond]（纯字符串、无依赖，有 JVM 单测），本类只管装钩。
 */
object AntiRevokeHint {

    /**
     * 模块自己发的诊断 SQL 前缀（`AntiRevoke.dumpRecent` / `queryMeta` 用）。
     *
     * 默认 hide=true 时，我们自己查库也会被自己的钩子改写 —— 结果就是
     * `ardump` 看不到任何他人撤回行，取证工具自己瞎了。加这个前缀即可绕过
     * （`isMessageSelect` 认的是 `select` 开头，加了前缀自然不匹配）。
     */
    internal const val BYPASS = "/*w*/"

    // ------------------------------------------------------------------ 配置

    private data class Config(
        /** 是否隐藏撤回提示（= `!show_hint`） */
        val hide: Boolean,
        /** 调试日志（Keys.DEBUG_LOG）：开着才把改写后的 SQL 写进日志 */
        val debugLog: Boolean,
    )

    @Volatile
    private var config = Config(false, false)

    @Volatile
    private var cfgAt = 0L

    /** 改写日志节流：聊天页每次进出都会查，逐条打会刷爆日志（LogX 有 5000 行上限） */
    @Volatile
    private var lastLogAt = 0L

    /**
     * 熔断：改写过的 SQL 一旦被 SQLite 判定为语法错误就永久停用本进程的改写。
     * 宁可功能失效，也不能让微信每次开聊天页都崩。
     *
     * ⚠️ 只认语法类错误（syntax error / no such column / misuse…）。
     * `database is locked` 这类环境异常不算，否则一次偶发就把功能废掉。
     */
    @Volatile
    private var disabled = false

    /**
     * 重入守卫：读配置会查库，查库又会进本钩子 —— 不挡住就是无限递归（见类注释）。
     */
    private val inHook = ThreadLocal<Boolean>()

    /** 记下本次改写后的 SQL，after 里比对，才能把异常算到自己头上 */
    private val rewritten = ThreadLocal<String>()

    fun refresh() {
        // ⚠️ 先打时间戳再读库：放在末尾的话，重入进来看到的还是「已过期」，
        // 于是再读一次库 → 再重入 → 栈爆。这一行是递归的第二道保险。
        cfgAt = SystemClock.elapsedRealtime()
        // ⚠️ 这里**不要**清 listAt：refresh() 每 3 秒就会被 config() 调一次，
        // 清了等于让提示行清单每 3 秒重读一遍库（微信 DB 线程上白白全表扫一次）。
        // 清单只在开关真正被外部改动时才失效，走 [invalidate]。
        config = Config(
            // ⚠️ 反着读：开关叫「显示撤回提示」，关掉才是隐藏
            // 隐藏的对象是**我们自己插入的**「已阻止」提示行，不是微信自带的撤回条
            hide = !Kv.getBool(Keys.ANTIREVOKE_SHOW_HINT),
            debugLog = Kv.getBool(Keys.DEBUG_LOG),
        )
    }

    /**
     * 开关被外部改过（adb `kvset` / 设置页）时调用：提示行清单可能有增删，要重读。
     *
     * ⚠️ 必须跟 [refresh] 分开：[refresh] 是 3 秒一次的热路径轮询，
     * 在里面清缓存会让微信的 DB 线程每 3 秒全表扫一次我们的台账。
     */
    fun invalidate() {
        refresh()
        listAt = 0L
    }

    @Volatile
    private var listAt = 0L

    @Volatile
    private var listCache: List<Long> = emptyList()

    /**
     * 我们插入过的「已阻止」提示行的 msgId。
     *
     * 「显示撤回提示」关掉时要连历史一起隐藏，所以按 msgId 精确排除
     * （不能按文案匹配 —— 微信自己也有「xx 尝试撤回上一条消息（已阻止）」这种文案）。
     */
    private fun hintIds(): List<Long> {
        val now = SystemClock.elapsedRealtime()
        val c = listCache
        if (now - listAt < 5000) return c
        // ⚠️ 只取最近的 [MAX_IDS] 条：`msgId NOT IN (...)` 是把整串 id 塞进 SQL 的，
        // 5000 条 ≈ 50KB，既拖慢解析也可能撞上别的限制。更早的提示行就不隐藏了
        // （数量极少，代价远小于让每条聊天查询都扛 50KB 的 SQL）。
        val list = runCatching {
            com.lx.wxgroup.data.GroupDb.current()?.listRescued(SqlHideCond.MAX_IDS)
                // ⚠️ 用 != 0L 判「有提示行」：提示行 msgId 走负数区间，写 > 0 会永远拿不到 id，
                // 表现为「显示撤回提示」关掉了但提示条一个都没隐藏
                ?.mapNotNull { it.hintMsgId.takeIf { id -> id != 0L } }
                ?: emptyList()
        }.getOrDefault(emptyList())
        listCache = list
        listAt = now
        return list
    }

    private fun config(): Config {
        val now = SystemClock.elapsedRealtime()
        if (now - cfgAt > 3000) refresh()
        return config
    }

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 装钩前先把配置读进来：cfgAt=0 时若 elapsedRealtime()<3000 是不会刷新的，
        // 而且下面那行日志会先触发一次读库（此时钩子已装，会白跑一趟改写判定）
        runCatching { refresh() }

        val cl = lpparam.classLoader
        for (clsName in listOf(
            "com.tencent.wcdb.database.SQLiteDatabase",
            "android.database.sqlite.SQLiteDatabase",
        )) {
            val cls = runCatching { XposedHelpers.findClass(clsName, cl) }.getOrNull() ?: continue
            for (m in listOf("rawQueryWithFactory", "rawQuery")) {
                runCatching {
                    XposedBridge.hookAllMethods(cls, m, hook())
                }.onFailure {
                    LogX.w("ARH hook-fail $clsName.$m ${it.javaClass.simpleName}")
                }
            }
        }
        LogX.i("ARH installed (hide=${config().hide})")
    }

    /**
     * ⚠️ 惰性构造并缓存：JVM 单测里没有 Xposed 运行时，object 初始化阶段就
     * `new XC_MethodHook` 会让整个类初始化失败（ExceptionInInitializerError）。
     */
    @Volatile
    private var hookRef: XC_MethodHook? = null

    private fun hook(): XC_MethodHook {
        val h = hookRef
        if (h != null) return h
        val made = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (inHook.get() == true) return
                inHook.set(true)
                try {
                    // 顺手帮 RevokeRescue 抓「消息库」实例：微信一直在查 message 表，
                    // 从读取侧抓比等它写一次快得多（写库可能几十秒都不发生一次）。
                    // ⚠️ 抓到之后就不再做这步，热路径上没有额外开销。
                    if (AntiRevoke.needsMsgDb()) {
                        runCatching {
                            val aa = param.args
                            val s = aa?.firstOrNull { it is CharSequence }?.toString()
                            if (s != null && SqlHideCond.isMessageSelect(s)) {
                                AntiRevoke.noteMsgDb(param.thisObject)
                            }
                        }
                    }
                    // 改写只在 SQL 字符串上做，出错一律放弃改写、绝不影响宿主
                    runCatching { rewrite(param) }
                        .onFailure { runCatching { LogX.w("ARH err ${it.javaClass.simpleName}") } }
                } finally {
                    inHook.set(false)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val mark = rewritten.get() ?: return
                rewritten.remove()
                if (!param.hasThrowable()) return
                val th = param.throwable ?: return
                val msg = th.message ?: ""
                // 只认语法类错误；database is locked 这类不算，别把功能误废
                if (msg.contains("syntax error") || msg.contains("no such column") ||
                    msg.contains("misuse") || msg.contains("ambiguous column")
                ) {
                    disabled = true
                    LogX.e("ARH disabled: rewritten SQL rejected", th)
                }
            }
        }
        hookRef = made
        return made
    }

    private fun rewrite(param: XC_MethodHook.MethodHookParam) {
        if (disabled) return
        val c = config()
        if (!c.hide) return
        // 我们还没插过任何「已阻止」提示行就不用改 SQL —— 空转只是白烧 CPU
        val ids = hintIds()
        if (ids.isEmpty()) return
        val args = param.args ?: return
        for (i in 0 until args.size) {
            val old = args[i] as? CharSequence ?: continue
            val sql = old.toString()
            if (!SqlHideCond.isMessageSelect(sql)) continue
            val next = SqlHideCond.inject(sql, SqlHideCond.notIn(ids)) ?: continue
            args[i] = next
            rewritten.set(next)
            if (c.debugLog) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastLogAt > 2000) {
                    lastLogAt = now
                    LogX.i("ARH rewrite :: ${next.take(260)}")
                }
            }
            return
        }
    }
}

/**
 * 「隐藏撤回提示」的 SQL 改写。**纯字符串处理，不依赖 Android / Xposed**，
 * 因此能在 JVM 上直接跑单测（见 `AntiRevokeHintTest`）。
 *
 * ## 过滤条件（不删数据，开关关掉立刻恢复）
 * ```sql
 * NOT ((ifnull(type,0) & 65535) = 10000
 *       AND ifnull(content,'') LIKE     '%撤回了一条消息%'
 *       AND ifnull(content,'') NOT LIKE '你撤回了一条消息%')
 * ```
 * - `type & 65535 = 10000` = 系统消息（撤回提示就是这一类，高位的 0x10000000 是微信的标志位）
 * - 文案含「撤回了一条消息」才是撤回提示（群邀请、入群欢迎那些系统消息不受影响）
 * - **排除「你撤回了一条消息」**：自己撤回的提示照常显示（需求的作用域条款）
 * - `ifnull` 是必须的：SQLite 里 `NULL AND ...` 得 NULL，`NOT NULL` 还是 NULL，
 *   而 WHERE 里的 NULL 等于 false —— **type / content 为 NULL 的行会被整条误杀**。
 *
 * ## ⚠️ 注入位置必须按括号深度判断
 * 聊天页是 `SELECT * FROM ( ... ORDER BY ... LIMIT 16) ORDER BY ...` 的嵌套形式，
 * 朴素的 `indexOf("order by")` 会命中**内层**子句，条件插进子查询 →
 * 变成「先 LIMIT 16 再过滤」，撤回提示照样显示，而且分页语义被改坏。
 *
 * ## ⚠️ 子句关键字不能落在字符串字面量里（复核揪出来的）
 * 搜索聊天记录时会出现 `content LIKE '%limit%'`，朴素的正则扫描会把 `%limit%` 里的
 * `limit` 当成子句边界 → 条件被插进引号里。引号成对所以**不报语法错误、熔断也不触发**，
 * 只是这个查询静默变成「LIKE 一个巨型模式」→ 搜索永远 0 行。
 * 所以 [scan] 一并算出「是否在字符串里」，扫描时跳过。
 *
 * ## 已知代价
 * 内层 `LIMIT 16` 之后再过滤，一页会少显示几条（16 条里有 1 条提示就剩 15 条）。
 * 分页游标是 `createTime`，下一页接着走不会漏消息，只是首屏略短。
 */
object SqlHideCond {

    /**
     * 过滤条件。[MARK] 同时充当「已注入过」的指纹 —— 直接用条件里这段独有的片段，
     * 不再额外加注释型标记（少一种可能被 SQL 解析器挑剔的语法）。
     */
    /**
     * 注入片段的指纹（也用来判断「这条 SQL 已经改过」）。
     *
     * ⚠️ 用的是注释而不是 `ifnull(...)` 之类：条件内容会随开关语义变，
     * 指纹必须**跟语义无关**。SQLite 认 C 风格注释，`BYPASS` 已在真机上验证可用。
     */
    private const val MARK = "/*wr*/"

    /**
     * 早期版本这里写的是「隐藏微信自带的他人撤回提示条」（`type=10000` + 撤回文案），
     * 那是**做反了** —— 用户澄清后确认：微信自带的灰色条不该被我们动。
     *
     * 现在只用来隐藏**我们自己插入的**「已阻止」提示行：
     * 「显示撤回提示」关掉时，连以前插过的那些也要一起隐藏（需求原文的原话）。
     * 那些行的 msgId 存在 `rescued.hint_msg_id` 里，所以条件是 `msgId NOT IN (...)`。
     *
     * 保留这段注释是为了记住那个坑：**过滤的是"自己造的东西"，不是微信的东西**。
     *
     * ## 📌 R21d 起这段逻辑实际上不会再产出条件（历史兼容，**故意保留**）
     * 方案 1（角标）落地后不再往 `message` 表插行 ⇒ 新记录 `hintMsgId` 恒为 0
     * ⇒ [hintIds] 恒返回空 ⇒ 本条件不会被注入。但**不能删**：
     * 老版本插入过的那些负 msgId 行，在用户升级后可能还有几条没被启动自检清掉
     * （自检要先拿到库句柄，万一那个会话先被打开就漏了）。留着这一段，
     * 「关掉显示撤回提示」对这些历史行仍然有效。
     * ⚠️ 顺带注意：这些负 msgId 历史行**本身会让微信闪退**（`h9.convertFrom` 拒绝），
     * 所以真正的对策是 `RevokeRescue.selfCheckNegRows()` 把它们删掉，而不是隐藏。
     */
    // （这里曾经有一个 LEGACY_COND_HINT 常量，按 `type=10000` + 撤回文案过滤。
    //  已删除 —— 那条路是错的，教训记在上面那段注释里。）

    /** 只认 `from message` 这种真正的表名引用（避开 appbrandnotifymessage 之类） */
    private val FROM_MESSAGE = Regex("""\bfrom\s+message\b""", RegexOption.IGNORE_CASE)

    /** 决定插在哪之前的子句关键字 */
    private val CLAUSE = Regex("""\b(where|order\s+by|group\s+by|limit|union)\b""", RegexOption.IGNORE_CASE)

    /**
     * `FROM ( SELECT …` 这种子查询的投影。
     *
     * ⚠️ 只有当内层投影是 `*` / `别名.*` 时，外层 WHERE 才引用得到 `type` / `content`；
     * 内层若是 `SELECT msgId FROM message`，外层写 type 会直接 `no such column`。
     * 判不了就放弃改写 —— 这条查询不隐藏提示，也比让微信开聊天页就崩好。
     * ⚠️ 必须**遍历所有**匹配：嵌套的 `FROM (SELECT * FROM (SELECT msgId …))`
     * 只有查到里面那层才拦得住。
     */
    private val SUB_SELECT =
        Regex("""\bfrom\s*\(\s*select\s+(?:distinct\s+)?(\S+)""", RegexOption.IGNORE_CASE)

    /**
     * 精确点查豁免：`WHERE msgId=?` / `WHERE msgSvrId=?`。
     *
     * 引用原文、转发、通知跳转都靠这种点查取行，把它们过滤掉会让那些入口取不到消息
     * （显示空白或跳转失败）。隐藏撤回提示只该影响**列表渲染**，不该影响按主键取行。
     */
    private val POINT_QUERY =
        Regex("""\bwhere\s+msg(id|svrid)\s*=\s*\?""", RegexOption.IGNORE_CASE)

    /**
     * 带 JOIN 的查询直接放弃改写。
     *
     * ⚠️ 两张表都有 `msgId` 时，外层追加 `msgId NOT IN (...)` 会报
     * `ambiguous column name: msgId` —— 而这个错误在 [AntiRevokeHint] 的熔断名单里，
     * 结果是**整个「隐藏提示条」被永久停用**（一次查询就废掉一个功能，代价太大）。
     * 宁可这条查询不隐藏提示，也不要触发熔断。
     */
    private val JOIN = Regex("""\bjoin\b""", RegexOption.IGNORE_CASE)

    /** 超长 SQL 不碰：不是聊天页的正常查询，扫描成本也不划算 */
    private const val MAX_SQL = 4096

    /** 参与 `msgId NOT IN (...)` 的提示行上限：SQL 不能无限长（见 [AntiRevokeHint.hintIds]） */
    internal const val MAX_IDS = 200

    /**
     * 生成「排除这些 msgId」的条件片段。
     *
     * [MARK] 同时充当「已注入过」的指纹。
     */
    fun notIn(ids: List<Long>): String =
        // ⚠️ 空列表必须收口成恒真：`msgId NOT IN ()` 是 SQLite 语法错误，
        // 一旦出现在改写里会直接触发熔断，把整个「隐藏提示条」永久废掉。
        if (ids.isEmpty()) "$MARK 1=1"
        else "$MARK msgId NOT IN (${ids.joinToString(",")})"

    /** 廉价前置筛选：只对「读 message 表的 select」做昂贵的扫描 */
    fun isMessageSelect(sql: String): Boolean {
        if (sql.length > MAX_SQL) return false
        if (sql.contains(MARK)) return false // 已经注入过
        if (POINT_QUERY.containsMatchIn(sql)) return false // 按主键取行，别动
        if (JOIN.containsMatchIn(sql)) return false // 多表查询可能列名歧义，会触发熔断
        val s = sql.trimStart()
        // 模块自己的诊断 SQL 带 [BYPASS] 前缀，走到这里就不会以 select 开头了
        if (!s.startsWith("select", ignoreCase = true)) return false
        if (!s.contains("message", ignoreCase = true)) return false
        return FROM_MESSAGE.containsMatchIn(s)
    }

    /**
     * 在最外层追加/新建过滤条件，返回改写后的 SQL；改不了返回 null（调用方当没事发生）。
     *
     * 只做字符串改写，不碰数据库、不执行 SQL，所以没有递归和锁的风险。
     */
    fun inject(raw: String, cond: String): String? {
        // 尾部分号要摘掉再插，否则 `...; AND (...)` 是语法错误
        val trimmed = raw.trimEnd()
        val semi = trimmed.endsWith(";")
        val body = if (semi) trimmed.substring(0, trimmed.length - 1) else trimmed
        if (body.isBlank()) return null

        // 子查询投影必须能看到 type/content，否则外层 WHERE 会 "no such column"
        for (m in SUB_SELECT.findAll(body)) {
            val proj = m.groupValues[1]
            if (proj != "*" && !proj.endsWith(".*")) return null
        }

        val sc = scan(body)
        var whereAt = -1
        var whereKwEnd = -1
        var endAt = body.length
        for (m in CLAUSE.findAll(body)) {
            val pos = m.range.first
            if (sc.depth[pos] != 0) continue // 子查询里的子句，不是最外层
            if (sc.inStr[pos]) continue // 字符串字面量里的关键字不是子句
            if (m.value.trimStart().startsWith("where", ignoreCase = true)) {
                if (whereAt < 0) {
                    whereAt = pos
                    whereKwEnd = m.range.last + 1
                }
            } else if (pos < endAt) {
                endAt = pos
            }
        }

        val tail = body.substring(endAt).trimStart()
        val semiTail = if (semi) ";" else ""

        // 已有 WHERE：默认直接 AND 追加
        if (whereAt >= 0 && whereAt < endAt) {
            val orig = body.substring(whereKwEnd, endAt).trim()
            // ⚠️ OR 优先级陷阱：`where a=1 or b=2` 直接 AND 追加会变成
            // `a=1 OR (b=2 AND cond)` —— 提示条照样显示，而且**不报语法错误、
            // 不触发熔断**，是最难查的一类静默失效。所以原 WHERE 里只要最外层有 OR，
            // 就先把整段原条件括号化再 AND。
            // （没有 OR 时不加括号，保持输出跟原来一致，避免无谓地改坏别的查询）
            return if (hasTopLevelOr(orig)) {
                val head = body.substring(0, whereAt)
                buildString {
                    append(head).append("WHERE (").append(orig)
                    append(") AND (").append(cond).append(')')
                    if (tail.isNotEmpty()) append(' ').append(tail)
                    append(semiTail)
                }
            } else {
                val head = body.substring(0, endAt).trimEnd()
                buildString {
                    append(head).append(" AND (").append(cond).append(')')
                    if (tail.isNotEmpty()) append(' ').append(tail)
                    append(semiTail)
                }
            }
        }

        val head = body.substring(0, endAt).trimEnd()
        return buildString {
            append(head).append(" WHERE (").append(cond).append(')')
            if (tail.isNotEmpty()) append(' ').append(tail)
            append(semiTail)
        }
    }

    private val OR_KW = Regex("""\bor\b""", RegexOption.IGNORE_CASE)

    /** 这段 WHERE 的最外层有没有裸 `OR`（有的话 AND 追加前必须整段括号化） */
    private fun hasTopLevelOr(s: String): Boolean {
        val sc = scan(s)
        for (m in OR_KW.findAll(s)) {
            val p = m.range.first
            if (sc.depth[p] == 0 && !sc.inStr[p]) return true
        }
        return false
    }

    private class Scan(val depth: IntArray, val inStr: BooleanArray)

    /**
     * 每个字符所在位置的括号深度（0 = 最外层）与「是否在字符串字面量里」。
     *
     * ⚠️ 引号里的括号不计入深度 —— `talker='a(b'` 这种字面量会把后面所有子句
     * 全算成「子查询」，于是找不到最外层位置，条件插错层。
     * ⚠️ 单引号是字符串、双引号是标识符（SQLite 两种都认），都要识别。
     */
    private fun scan(s: String): Scan {
        val depth = IntArray(s.length)
        val inStr = BooleanArray(s.length)
        var d = 0
        var quote = 0.toChar()
        for (i in s.indices) {
            val c = s[i]
            when {
                quote != 0.toChar() -> {
                    inStr[i] = true
                    depth[i] = d
                    if (c == quote) quote = 0.toChar()
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    inStr[i] = true
                    depth[i] = d
                }
                c == '(' -> {
                    d++
                    depth[i] = d
                }
                c == ')' -> {
                    depth[i] = d
                    if (d > 0) d--
                }
                else -> depth[i] = d
            }
        }
        return Scan(depth, inStr)
    }
}
