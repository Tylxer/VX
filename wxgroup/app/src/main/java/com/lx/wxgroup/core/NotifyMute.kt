package com.lx.wxgroup.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.feature.LabelSource
import com.lx.wxgroup.feature.ListResolver
import com.lx.wxgroup.util.LogX
import java.util.Calendar
import java.util.Locale

/**
 * K9 通知静音过滤器（三合一）。
 *
 * 一个拦截点同时判定三件事，顺序不可调换：
 *   1. 是微信来电        → 放行（照常响铃振动）
 *   2. 免打扰时段开启且在时段内 → 静音
 *   3. 防止被@开启、这条@了我、且群在作用域 → 静音
 *   4. 其余一律不动
 *
 * ## 为什么挂在这两个点（8.0.48 真机实测，六轮探针的结论）
 *
 * - 通知**只在主进程**发出（`com.tencent.mm`，不是 `:push`），所以装在 bootstrap 里即可，
 *   DB 直接可用，不用跨进程取配置。
 * - 微信发通知的构造链是
 *   `booter.notification.NotificationItem.a ← a.a ← w.a/w.b ← q.d/q.m ← p.handleMessage`。
 *   **`NotificationItem` 里才有会话 username**：字段 `h` = talker（`wxid_xxx` 或
 *   `xxx@chatroom`），字段 `d` = 通知 id（与 `notify()` 的 id 完全对应），
 *   字段 `i` = msgId，`p` = 未读数。
 *   通知的 extras 里**没有** chatroom id，`contentIntent` 的 Intent 又反射取不到，
 *   所以「这条消息来自哪个群」只能靠这里。
 * - 提示音和震动**不在 Notification 里**：实测 `sound=null vibrate=null defaults=0`，
 *   声音在 channel 上也是 `mSound=null`，只有 `mVibrationEnabled=true` + `[300,200,300,200]`。
 *   → 也就是说**震动是系统按 channel 播的**，改 channel 就能掐掉，
 *   在 Notification 对象上改 sound/vibrate 字段一点用都没有。
 *   提示音来源一直没定位到（六轮探针没抓到任何音频调用，微信前台也不响），
 *   但**换到静音通道后实测声音同样没了** —— 所以不必再单独找播放入口。
 *
 * ## 静音怎么落地
 *
 * 主手段：**把通知的 channelId 换成我们预建的一条静音通道**
 * （同样的 IMPORTANCE_HIGH，照样进通知栏、照样弹 heads-up、角标不变，
 *   但 `setSound(null)` + `enableVibration(false)`，系统于是不响不振）。
 * 需求要的正是「消息照常收到、通知栏保持原样，只是不响不振」。
 *
 * 辅手段：命中静音时**开一个 1.2 秒的静音窗口**，窗口内把 `SystemVibrator.vibrate`
 * 直接吞掉（`param.result = null`）。这是给「微信自己调 Vibrator」留的保险 ——
 * 微信前台时本来就不响不振，所以这个窗口只会在后台收消息时开，
 * 不会误伤用户在微信里的触摸反馈。
 *
 * ## 已知边界
 *
 * - **微信来电放行（[isIncomingCall]）没有真机验证过**：判据取自真机抓到的
 *   VOIP 通知特征（category=call / channel 以 voip_ 开头 / fullScreenIntent 非空），
 *   但验证当晚没条件打微信电话。真机跑一次来电确认前，别把它当成已验证。
 * - 提示音这里**实测是掐得掉的**：换到静音通道后用户反馈「没震、没响」，
 *   所以此前「声音是微信自己播、管不了」的推测不成立（见下）。
 *
 * ## 静音实测（0.2.4，微信 8.0.48，设备 467cf5bc）
 *
 * | 场景 | 结果 |
 * |---|---|
 * | 免打扰时段内来消息（群 + 单聊共 6 条） | 全部 `K9 DND muted`，用户反馈不响不振、通知栏正常 |
 * | 关掉免打扰再发一条 | 恢复响 + 震（对照成立，证明静音是 K9 干的） |
 * | 群里 @昵称 | `K9 AT muted ... talker=xxx@chatroom`，不响不振 |
 * | 群里普通消息（没@） | 无 K9 记录，正常响 + 震（没误伤） |
 * - 「@我」的判定依赖昵称比对，昵称从 `chat.at_aliases` 读；
 *   没配昵称时只认 `@所有人` / `@all`（宁可漏判，也不该把 @别人 的消息静音）。
 */
object NotifyMute {

    // ---------------------------------------------------------------- 常量

    private const val ITEM_CLASS = "com.tencent.mm.booter.notification.NotificationItem"

    /** 预建的静音通道。名字用户看得见（系统设置 → 通知类别里会出现） */
    private const val MUTE_CHANNEL_ID = "wxgroup_silent"
    private const val MUTE_CHANNEL_NAME = "静音通知（分组模块）"

    /** 静音窗口时长：覆盖通知发出后微信补的那一次自行震动 */
    private const val MUTE_WINDOW_MS = 1200L

    /** 日志节流：相同结论 1 秒内只打一次，避免群消息刷爆日志 */
    private const val LOG_THROTTLE_MS = 1000L

    private const val MAX_TRACKED = 256

    // ---------------------------------------------------------------- 状态

    /** 通知 id → 会话 username。发通知的线程和构造 Notification 的线程不一定同一个，加锁 */
    private val lock = Any()
    private val talkerById = object : LinkedHashMap<Int, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean =
            size > MAX_TRACKED
    }

    @Volatile
    private var muteUntilMs = 0L

    private var lastLogMsg = ""
    private var lastLogMs = 0L

    private var installed = false

    // ---------------------------------------------------------------- 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!installItemHook(lpparam)) return
        installNotifyHook(lpparam)
        installVibratorHook(lpparam)
        installed = true
        LogX.i("K9 installed (NotifyMute)")
    }

    /**
     * 从 NotificationItem 里解出「通知 id + 会话 username」。
     *
     * **两级降级，顺序不能反**：
     * ① 先解析 `toString()` —— 实测它长这样
     *    `id: 1740451091,msgId: 7061729564838265855,userName: wxid_xlrbelf6hv0w22,unreadCount: 6`
     *    这是微信自己留的调试输出，属于**功能性格式**，比混淆字段名稳得多
     *    （换版本重排混淆时字段名会变，这种输出格式一般不会）。
     * ② 再退到字段名 —— 8.0.48 实测 `h` = talker、`d` = id。
     *
     * 两级都拿不到就返回 null：这时不是崩，是不认得这个会话，
     * 交给 [inScope] 按「不在作用域」处理（宁可漏判）。
     */
    private fun parse(self: Any): Item? {
        val s = runCatching { self.toString() }.getOrNull()
        if (s != null) {
            val m = ITEM_RE.find(s)
            if (m != null) {
                val id = m.groupValues[1].toIntOrNull()
                val talker = m.groupValues[3]
                if (id != null && talker.isNotBlank()) return Item(id, talker)
            }
        }
        val talker = runCatching { XposedHelpers.getObjectField(self, "h") as? String }.getOrNull()
        val raw = runCatching { XposedHelpers.getObjectField(self, "d") }.getOrNull()
        val id = when (raw) {
            is Int -> raw
            is Long -> raw.toInt()
            else -> null
        }
        return if (!talker.isNullOrBlank() && id != null) Item(id, talker) else null
    }

    private data class Item(val id: Int, val talker: String)

    /** 对应 `NotificationItem.toString()` 的输出格式 */
    private val ITEM_RE =
        Regex("""id:\s*(-?\d+),\s*msgId:\s*(\d+),\s*userName:\s*(\S+?),\s*unreadCount:\s*(\d+)""")

    /**
     * 记下「通知 id → 会话 username」。
     *
     * 拿不到 NotificationItem 就整个 K9 不装：没有 talker 就判不了「群在不在名单里」，
     * 硬装只会做出一个「要么全静音要么全不静音」的残废功能。
     */
    private fun installItemHook(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        val cls = runCatching { XposedHelpers.findClass(ITEM_CLASS, lpparam.classLoader) }
            .onFailure { LogX.w("K9 disabled: NotificationItem n/a ($it)") }
            .getOrNull() ?: return false

        return runCatching {
            XposedBridge.hookAllMethods(cls, "a", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val self = param.thisObject ?: return
                        val item = parse(self) ?: return
                        synchronized(lock) { talkerById[item.id] = item.talker }
                    }.onFailure { LogX.e("K9 item hook failed", it) }
                }
            })
            true
        }.onFailure { LogX.e("K9 NotificationItem hook failed", it) }.getOrDefault(false)
    }

    private fun installNotifyHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 微信用的是三参重载 `notify(String tag, int id, Notification)`（tag 恒为 null），
        // 两参的一并挂上做兜底 —— 换版本改了调用方式也不至于整个失效。
        val overloads = listOf(
            arrayOf<Class<*>>(
                String::class.java, Int::class.javaPrimitiveType!!, Notification::class.java
            ),
            arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Notification::class.java),
        )
        for (args in overloads) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    "android.app.NotificationManager",
                    lpparam.classLoader,
                    "notify",
                    *args,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            runCatching { onNotify(param.args) }
                                .onFailure { LogX.e("K9 notify failed", it) }
                        }
                    }
                )
            }.onFailure { LogX.w("K9 notify(${args.size}) hook failed: ${it.javaClass.simpleName}") }
        }
    }

    /** 静音窗口内吞掉震动。只吞震动，不动音频 —— 动音频可能让播放器状态错乱 */
    private fun installVibratorHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cls = runCatching { XposedHelpers.findClass("android.os.SystemVibrator", lpparam.classLoader) }
            .getOrNull() ?: return
        runCatching {
            XposedBridge.hookAllMethods(cls, "vibrate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (System.currentTimeMillis() >= muteUntilMs) return
                    param.result = null
                    log("K9 swallow vibrate in mute window")
                }
            })
        }.onFailure { LogX.w("K9 vibrator hook failed: ${it.javaClass.simpleName}") }
    }

    // ---------------------------------------------------------------- 判定

    private fun onNotify(args: Array<Any?>) {
        if (!installed) return
        val n = args.firstOrNull { it is Notification } as? Notification ?: return
        val id = args.firstOrNull { it is Int } as? Int ?: return

        // —— 第一优先级：微信来电，无条件放行 ——
        // 实测三条判据任意一条都够：category=call / channel 以 voip_ 开头 /
        // fullScreenIntent 非空。全判一遍是为了换版本后还能兜住。
        if (isIncomingCall(n)) {
            log("K9 CALL pass-through (channel=${channelOf(n)})")
            return
        }

        val talker = synchronized(lock) { talkerById[id] }

        if (Kv.getBool(Keys.CHAT_DND_ENABLED) && inQuietHours()) {
            mute(n, id, "DND", talker)
            return
        }

        if (Kv.getBool(Keys.CHAT_MUTE_AT_ENABLED) && isAtMe(n) && inScope(talker)) {
            mute(n, id, "AT", talker)
        }
    }

    /** 微信来电：视频/语音通话邀请，必须照常响铃 */
    private fun isIncomingCall(n: Notification): Boolean {
        if (n.category == Notification.CATEGORY_CALL) return true
        if (channelOf(n)?.startsWith("voip_") == true) return true
        return runCatching {
            val f = Notification::class.java.getDeclaredField("fullScreenIntent")
            f.isAccessible = true
            f.get(n) != null
        }.getOrDefault(false)
    }

    /** 免打扰时段。**跨天**（23:00 → 次日 07:00）；**起止相等 = 不生效**（时长 0） */
    private fun inQuietHours(): Boolean {
        val s = minutesOf(Kv.getString(Keys.CHAT_DND_START)) ?: return false
        val e = minutesOf(Kv.getString(Keys.CHAT_DND_END)) ?: return false
        if (s == e) return false
        val now = Calendar.getInstance(Locale.getDefault()).let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }
        return if (s < e) now in s until e else now >= s || now < e
    }

    private fun minutesOf(hhmm: String): Int? {
        val parts = hhmm.split(":")
        if (parts.size != 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /**
     * 作用域：**排除名单优先**（命中直接不生效），再看允许名单。
     * 允许名单为空 = 对所有会话生效。talker 拿不到时按不在作用域处理（宁可漏判）。
     *
     * ## R24：支持 `g:` / `t:` 前缀
     *
     * K3 选择器现在四个 Tab 全开，名单里可能存 `g:3`（分组）或 `t:12`（标签）。
     * 直接拿 talker 去比 `g:3` 永不命中 ⇒ 用户勾了分组会**静默失效**。
     * 所以先把名单经 [ListResolver.expand] 展开成具体 wxid 再比。
     *
     * ⚠️ 「允许名单为空」只认**配置为空**，不认「展开后为空」：
     * 用户把允许名单设成一个空分组时，正确行为是「谁都不在范围内」；
     * 若退回成「允许所有人」，免打扰会把**全部**通知静音 —— 这是方向相反的失效。
     * 该三态语义由 [ListResolver.inScope] 统一实现，别在这里另写一份判空逻辑。
     */
    private fun inScope(talker: String?): Boolean = ListResolver.inScope(
        talker = talker,
        allowRaw = Kv.getList(Keys.CHAT_MUTE_AT_ALLOW),
        excludeRaw = Kv.getList(Keys.CHAT_MUTE_AT_EXCLUDE),
        groups = scopeBuckets().groups,
        labels = scopeBuckets().labels,
    )

    // ---------------------------------------------------- 作用域用的分组/标签快照

    /**
     * 作用域展开用的 buckets 缓存。
     *
     * 为什么必须缓存：`inScope` 跑在**通知回调**里（`onNotify` 的 hook 帧上），
     * 每条微信通知都会走一次。分组有几十个、成员上千时，每次通知都查
     * `group_member` 表会让通知延迟可见 —— 而免打扰是「能容忍配置晚 3 秒生效，
     * 不能容忍通知卡顿」的功能。
     *
     * 缓存策略照抄项目里已有的做法（`MomentsExport.Config` 的 `cfgAt` + TTL）：
     * - TTL 30 秒：用户刚改完分组就收到消息，最多 30 秒后才按新成员判定；
     * - 用户改名单 / 成员时 [invalidateScope] 主动失效，所以正常路径是立即生效的。
     *
     * ⚠️ 快照只在**首次需要时**才查库。若用户从没用过分组/标签（名单里全是裸 wxid），
     * [needBuckets] 直接返回 false，一次库都不查。
     */
    private data class ScopeBuckets(
        val groups: Map<String, List<String>> = emptyMap(),
        val labels: Map<String, List<String>> = emptyMap(),
    )

    private val scopeLock = Any()
    private var scopeCache: ScopeBuckets = ScopeBuckets()
    private var scopeAt = 0L

    private const val SCOPE_TTL_MS = 30_000L

    private fun scopeBuckets(): ScopeBuckets {
        val allow = Kv.getList(Keys.CHAT_MUTE_AT_ALLOW)
        val exclude = Kv.getList(Keys.CHAT_MUTE_AT_EXCLUDE)
        // 名单里一个前缀都没有 ⇒ 展开必然等于原样，不用查库也不用缓存
        if (!needBuckets(allow) && !needBuckets(exclude)) return ScopeBuckets()
        synchronized(scopeLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (scopeAt != 0L && now - scopeAt < SCOPE_TTL_MS) return scopeCache
            val fresh = loadBuckets()
            scopeCache = fresh
            scopeAt = now
            return fresh
        }
    }

    /** 名单里有没有 `g:` / `t:` 条目 —— 没有就不值得为它查库 */
    private fun needBuckets(list: List<String>): Boolean = list.any {
        val s = it.trim()
        s.startsWith(ListResolver.PREFIX_GROUP) || s.startsWith(ListResolver.PREFIX_LABEL)
    }

    private fun loadBuckets(): ScopeBuckets = runCatching {
        val db = com.lx.wxgroup.data.GroupDb.current() ?: return@runCatching ScopeBuckets()
        val g = LinkedHashMap<String, List<String>>()
        for (group in db.listGroups()) {
            runCatching { g[group.id.toString()] = db.memberWxids(group.id).toList() }
        }
        val l = runCatching { LabelSource.membersByLabelId() }.getOrDefault(emptyMap())
        ScopeBuckets(groups = g, labels = l)
    }.onFailure { LogX.w("K9 scope buckets failed: ${it.javaClass.simpleName}") }
        .getOrDefault(ScopeBuckets())

    /**
     * 名单 / 分组成员变了就调它。
     *
     * 两件事，缺一不可：
     * 1. **立刻失效**（`scopeAt = 0`）—— 否则改完配置要等 30 秒 TTL 才生效，
     *    验收时会看到「名单改了但行为没变」的假阴性；
     * 2. **后台预热** —— 若只失效不预热，下一次 @ 通知会在 **hook 帧上**同步跑
     *    `listGroups()` + 逐组 `memberWxids()`（几十次 SQLite 查询），把通知卡住
     *    （R24 复核 S4）。在此处提前把快照建好，通知路径就只读内存。
     *
     * 预热失败不影响正确性：`scopeBuckets()` 发现 `scopeAt == 0` 会自己补一次同步加载。
     */
    fun invalidateScope() {
        synchronized(scopeLock) { scopeAt = 0L }
        runCatching {
            Thread({
                runCatching {
                    val fresh = loadBuckets()
                    synchronized(scopeLock) {
                        scopeCache = fresh
                        scopeAt = android.os.SystemClock.elapsedRealtime()
                    }
                }.onFailure { LogX.w("K9 scope warm-up failed: ${it.javaClass.simpleName}") }
            }, "wxg-scope-warm").apply { isDaemon = true }.start()
        }.onFailure { LogX.w("K9 scope warm-up thread failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 这条消息是不是 @了我。
     *
     * 微信把 @ 渲染进通知正文，形如 `verity: @V 345`、`@所有人`。
     * 判断「@V 里的 V 是不是我」必须知道自己的昵称 —— 模块目前拿不到自身昵称的锚点，
     * 所以走配置项 `chat.at_aliases`（可填多个，含各群不同的群昵称）。
     * **没配就只认 @所有人**，@某个具体人的一律不静音：误静音比漏静音难受得多。
     */
    private fun isAtMe(n: Notification): Boolean {
        val text = buildString {
            @Suppress("DEPRECATION")
            n.tickerText?.let { append(it).append(' ') }
            n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.let { append(it) }
            n.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { append(it) }
        }
        if (text.isBlank()) return false
        if (text.contains("@所有人") || text.contains("@all")) return true
        for (alias in aliases()) {
            if (alias.isNotBlank() && containsAt(text, alias)) return true
        }
        return false
    }

    /**
     * 文本里有没有 `@alias`。
     *
     * **不能只做 `contains("@$alias")`** —— 昵称是 `V` 这种短名时，
     * `@Verity` / `@VIP` 都会被算成 @我，把不该静音的消息静音了。
     * 所以要求 @alias 后面紧跟的不是名字字符（字母、数字、下划线、短横线）。
     */
    private fun containsAt(text: String, alias: String): Boolean {
        val token = "@$alias"
        var i = text.indexOf(token)
        while (i >= 0) {
            val after = i + token.length
            if (after >= text.length || !isNameChar(text[after])) return true
            i = text.indexOf(token, i + 1)
        }
        return false
    }

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-'

    /**
     * 我的昵称列表。
     *
     * `chat.at_aliases` 是**纯文本**配置项（UI 里就是一个输入框，用户填
     * `verity` 或 `verity, 老V`），**不能**用 [Kv.getList] 读 —— 那个是按 JSON 数组
     * 解析的，填裸文本会解析失败返回空列表，昵称就永远不生效（踩过）。
     * 这里按逗号/顿号/空白切分，跟用户填东西的习惯对齐。
     */
    private fun aliases(): List<String> =
        Kv.getString(Keys.CHAT_AT_ALIASES)
            .split(",", "，", "、", " ", "\n", "\t")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    // ---------------------------------------------------------------- 执行

    private fun mute(n: Notification, id: Int, reason: String, talker: String?) {
        val ctx = HostContext.app()
        if (ctx == null || !ensureMuteChannel(ctx)) {
            log("K9 $reason MUTE-FAILED (no channel) id=$id talker=$talker")
            return
        }
        if (!setChannelId(n)) {
            log("K9 $reason MUTE-FAILED (cannot rewrite channel) id=$id")
            return
        }
        // 辅手段：给微信可能自行补的那一次震动留个窗口
        muteUntilMs = System.currentTimeMillis() + MUTE_WINDOW_MS
        log("K9 $reason muted id=$id talker=$talker channel=${channelOf(n)}")
    }

    /**
     * 建一条自己的静音通道。
     *
     * `IMPORTANCE_HIGH` 是刻意的：微信原通道 `message_channel_new_id` 也是 4（HIGH），
     * 保持一致才能让「通知栏 / 角标 / heads-up 保持原样」，只是不响不振。
     * 建过就不再建（`createNotificationChannel` 重复调用是幂等的，但省一次 IPC）。
     */
    private fun ensureMuteChannel(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            if (runCatching { nm.getNotificationChannel(MUTE_CHANNEL_ID) }.getOrNull() == null) {
                val ch = NotificationChannel(
                    MUTE_CHANNEL_ID, MUTE_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
                )
                ch.setSound(null, null)
                ch.enableVibration(false)
                ch.vibrationPattern = null
                nm.createNotificationChannel(ch)
                LogX.i("K9 mute channel created")
            }
            true
        }.onFailure { LogX.e("K9 create mute channel failed", it) }.getOrDefault(false)
    }

    /**
     * 改 Notification 的 channelId。
     *
     * `mChannelId` 字段在不同 API level 上可见性不一致，所以先直取，
     * 失败再按「当前值等于 channelId 的那个 String 字段」兜底找 ——
     * 这时拿不到就放弃，不能硬改一个找错的字段。
     */
    private fun setChannelId(n: Notification): Boolean {
        runCatching {
            val f = Notification::class.java.getDeclaredField("mChannelId")
            f.isAccessible = true
            f.set(n, MUTE_CHANNEL_ID)
            return true
        }
        val cur = channelOf(n)
        if (cur.isNullOrBlank()) return false
        return runCatching {
            for (f in Notification::class.java.declaredFields) {
                if (f.type != String::class.java) continue
                f.isAccessible = true
                if (f.get(n) == cur) {
                    f.set(n, MUTE_CHANNEL_ID)
                    return true
                }
            }
            false
        }.getOrDefault(false)
    }

    private fun channelOf(n: Notification): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { n.channelId }.getOrNull()
        } else {
            null
        }

    /** 日志节流 + 落盘，避免群消息刷爆 */
    private fun log(msg: String) {
        val now = System.currentTimeMillis()
        if (msg == lastLogMsg && now - lastLogMs < LOG_THROTTLE_MS) return
        lastLogMsg = msg
        lastLogMs = now
        LogX.i(msg)
    }
}
