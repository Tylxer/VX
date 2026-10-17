package com.lx.wxgroup.feature

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.HashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** 被 @ 时仅切到静音通知通道，消息、@ 标记、通知栏和角标均不改。 */
object MuteAt {

    @Volatile
    private var registered = false

    private val matched = AtomicLong(0)

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            registered = QuietNotificationBridge.install(lpparam)
            if (registered) QuietNotificationBridge.enableMuteAt()
            LogX.i("QUIET AT install registered=$registered")
        }.onFailure {
            registered = false
            LogX.i("QUIET AT install failed: ${it.javaClass.simpleName}")
        }
    }

    internal fun shouldMute(notification: Notification, talker: String?): Boolean {
        return runCatching {
            if (!registered || !Kv.getBool(Keys.CHAT_MUTE_AT_ENABLED)) return false
            if (!QuietLogic.groupInScope(
                    talker,
                    Kv.getList(Keys.CHAT_MUTE_AT_ALLOW),
                    Kv.getList(Keys.CHAT_MUTE_AT_EXCLUDE),
                )
            ) return false
            val text = notificationText(notification)
            if (!QuietLogic.isAtMe(text, QuietLogic.aliases(Kv.getString(Keys.CHAT_AT_ALIASES)))) {
                return false
            }
            matched.incrementAndGet()
            true
        }.getOrDefault(false)
    }

    private fun notificationText(notification: Notification): String = buildString {
        @Suppress("DEPRECATION")
        notification.tickerText?.let { append(it).append(' ') }
        notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.let { append(it).append(' ') }
        notification.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { append(it) }
    }

    fun probe(sub: String): String = runCatching {
        when (sub.lowercase()) {
            "status" -> "registered=$registered enabled=${Kv.getBool(Keys.CHAT_MUTE_AT_ENABLED)} matched=${matched.get()} bridge=${QuietNotificationBridge.status()}"
            "scope" -> "allow=${Kv.getList(Keys.CHAT_MUTE_AT_ALLOW)} exclude=${Kv.getList(Keys.CHAT_MUTE_AT_EXCLUDE)}"
            "aliases" -> "aliases=${QuietLogic.aliases(Kv.getString(Keys.CHAT_AT_ALIASES))}"
            "map" -> QuietNotificationBridge.mapStatus()
            else -> "subs=status,scope,aliases,map"
        }
    }.getOrElse { "error=${it.javaClass.simpleName}" }
}

/**
 * MuteAt 与 DndWindow 共用的唯一通知拦截器。两项分别 install 也只会 hook 一次，避免一条
 * Notification 被重复改写。会话来源使用 8.0.48 已实证的 NotificationItem(d=id,h=talker)。
 */
internal object QuietNotificationBridge {

    private const val ITEM_CLASS = "com.tencent.mm.booter.notification.NotificationItem"
    private const val MUTE_CHANNEL_ID = "wxgroup_silent"
    private const val MUTE_CHANNEL_NAME = "静音通知（分组模块）"
    private const val MUTE_WINDOW_MS = 1200L
    private const val LOG_THROTTLE_MS = 1000L
    private const val MAX_TRACKED = 256

    private val installLock = Any()

    /** ConcurrentHashMap 禁止 null；这里必须允许“已见 id、但 talker 解析失败”的三态。 */
    private val talkerById: MutableMap<Int, String?> =
        Collections.synchronizedMap(HashMap<Int, String?>())

    private val muted = AtomicLong(0)
    private val lastHotLogAt = AtomicLong(0)

    @Volatile
    private var installed = false

    @Volatile
    private var itemHooked = false

    @Volatile
    private var muteAtEnabled = false

    @Volatile
    private var dndEnabled = false

    @Volatile
    private var muteUntilMs = 0L

    /**
     * 新加群自动免打扰（AutoDndGroup）置过静音的群。
     *
     * 与 DND 时段共用同一条通知改写通道（锚点见类注释），只是判定条件不同：
     * 这里是「这个群被登记过」而不是「当前在时段内」。
     */
    private val autoDndTalkers: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    /** talker 观察者：每条通知构造时回调（AutoDndGroup 靠它发现新群聊会话）。 */
    private val talkerObservers: MutableList<(String) -> Unit> = CopyOnWriteArrayList()

    fun install(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        if (installed) return true
        synchronized(installLock) {
            if (installed) return true
            return runCatching {
                itemHooked = installItemHook(lpparam)
                val notifyHooked = installNotifyHook()
                if (!notifyHooked) {
                    LogX.i("QUIET bridge disabled: NotificationManager.notify unavailable")
                    return false
                }
                installVibratorHook(lpparam)
                installed = true
                LogX.i("QUIET bridge installed itemHooked=$itemHooked")
                true
            }.onFailure {
                LogX.i("QUIET bridge install failed: ${it.javaClass.simpleName}")
            }.getOrDefault(false)
        }
    }

    fun enableMuteAt() {
        muteAtEnabled = true
    }

    fun enableDnd() {
        dndEnabled = true
    }

    /** 登记「这个群要走自动免打扰静音」（AutoDndGroup 判定为新群后调用）。 */
    fun markAutoDndTalker(talker: String) {
        if (talker.isNotBlank()) autoDndTalkers.add(talker)
    }

    fun isAutoDndTalker(talker: String?): Boolean =
        !talker.isNullOrBlank() && autoDndTalkers.contains(talker)

    /** 注册 talker 观察者（只回调非空 talker；用于被动发现群聊会话）。 */
    fun addTalkerObserver(observer: (String) -> Unit) {
        talkerObservers.add(observer)
    }

    private fun fireTalkerObservers(talker: String) {
        if (talker.isBlank()) return
        for (observer in talkerObservers) {
            runCatching { observer(talker) }
        }
    }

    private fun installItemHook(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        val itemClass = runCatching {
            XposedHelpers.findClass(ITEM_CLASS, lpparam.classLoader)
        }.getOrNull() ?: run {
            LogX.i("QUIET item hook skipped: NotificationItem not found")
            return false
        }
        val methods = itemClass.declaredMethods.filter {
            it.name == "a" && it.parameterTypes.size == 1
        }
        if (methods.isEmpty()) {
            LogX.i("QUIET item hook skipped: instance a(nargs=1) not found")
            return false
        }
        var hooked = 0
        methods.forEach { method ->
            val ok = runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val self = param.thisObject ?: return
                            remember(parseItem(self))
                        }
                    }
                })
            }.isSuccess
            if (ok) hooked++
        }
        return hooked > 0
    }

    private fun installNotifyHook(): Boolean {
        return runCatching {
            XposedBridge.hookAllMethods(
                NotificationManager::class.java,
                "notify",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching { onNotify(param.args) }
                            .onFailure { hotLog("notify failed ${it.javaClass.simpleName}") }
                    }
                },
            ).isNotEmpty()
        }.getOrDefault(false)
    }

    private fun installVibratorHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val vibratorClass = runCatching {
            XposedHelpers.findClass("android.os.SystemVibrator", lpparam.classLoader)
        }.getOrNull() ?: return
        runCatching {
            XposedBridge.hookAllMethods(vibratorClass, "vibrate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (System.currentTimeMillis() >= muteUntilMs) return
                        param.result = null
                    }
                }
            })
        }
    }

    private fun onNotify(args: Array<Any?>) {
        if (!installed) return
        val notification = args.firstOrNull { it is Notification } as? Notification ?: return
        val id = when {
            args.size >= 3 && args[1] is Int -> args[1] as Int
            args.isNotEmpty() && args[0] is Int -> args[0] as Int
            else -> return
        }

        if (dndEnabled && DndWindow.shouldMute(notification)) {
            mute(notification, id, "DND", talkerOf(id))
            return
        }

        val talker = talkerOf(id)
        if (Kv.getBool(Keys.CHAT_AUTO_DND_NEW_GROUP) && isAutoDndTalker(talker)) {
            mute(notification, id, "GRP", talker)
            return
        }
        if (muteAtEnabled && MuteAt.shouldMute(notification, talker)) {
            mute(notification, id, "AT", talker)
        }
    }

    private fun mute(notification: Notification, id: Int, reason: String, talker: String?) {
        val context = HostContext.app() ?: return
        if (!ensureMuteChannel(context) || !rewriteChannel(notification)) {
            hotLog("$reason mute failed id=$id")
            return
        }
        muteUntilMs = System.currentTimeMillis() + MUTE_WINDOW_MS
        muted.incrementAndGet()
        hotLog("$reason muted id=$id talker=$talker")
    }

    private fun ensureMuteChannel(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            if (manager.getNotificationChannel(MUTE_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    MUTE_CHANNEL_ID,
                    MUTE_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    vibrationPattern = null
                }
                manager.createNotificationChannel(channel)
                LogX.i("QUIET silent channel created")
            }
            true
        }.getOrDefault(false)
    }

    private fun rewriteChannel(notification: Notification): Boolean {
        runCatching {
            val field = Notification::class.java.getDeclaredField("mChannelId")
            field.isAccessible = true
            field.set(notification, MUTE_CHANNEL_ID)
            return true
        }
        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { notification.channelId }.getOrNull()
        } else null
        if (current.isNullOrBlank()) return false
        return runCatching {
            for (field in Notification::class.java.declaredFields) {
                if (field.type != String::class.java) continue
                field.isAccessible = true
                if (field.get(notification) == current) {
                    field.set(notification, MUTE_CHANNEL_ID)
                    return true
                }
            }
            false
        }.getOrDefault(false)
    }

    private data class Item(val id: Int, val talker: String?)

    private fun parseItem(self: Any): Item? {
        val text = runCatching { self.toString() }.getOrNull()
        if (text != null) {
            val match = ITEM_RE.find(text)
            if (match != null) {
                val id = match.groupValues[1].toIntOrNull()
                if (id != null) return Item(id, match.groupValues[2].takeIf(String::isNotBlank))
            }
        }
        val rawId = runCatching { XposedHelpers.getObjectField(self, "d") }.getOrNull()
        val id = when (rawId) {
            is Int -> rawId
            is Long -> rawId.toInt()
            else -> return null
        }
        val talker = runCatching {
            XposedHelpers.getObjectField(self, "h") as? String
        }.getOrNull()?.takeIf(String::isNotBlank)
        return Item(id, talker)
    }

    private fun remember(item: Item?) {
        if (item == null) return
        synchronized(talkerById) {
            if (talkerById.size >= MAX_TRACKED && !talkerById.containsKey(item.id)) {
                talkerById.clear()
            }
            talkerById[item.id] = item.talker
        }
        // 被动观察：每个新通知都过一遍（talker 解析失败就跳过，AutoDndGroup 侧幂等去重）
        val talker = item.talker
        if (!talker.isNullOrBlank()) fireTalkerObservers(talker)
    }

    private fun talkerOf(id: Int): String? = synchronized(talkerById) {
        val value = talkerById[id]
        if (value.isNullOrBlank()) null else value
    }

    private fun hotLog(message: String) {
        val now = System.currentTimeMillis()
        while (true) {
            val previous = lastHotLogAt.get()
            if (now - previous < LOG_THROTTLE_MS) return
            if (lastHotLogAt.compareAndSet(previous, now)) break
        }
        LogX.i("QUIET $message")
    }

    fun status(): String =
        "installed=$installed item=$itemHooked at=$muteAtEnabled dnd=$dndEnabled muted=${muted.get()} autoDnd=${autoDndTalkers.size}"

    fun mapStatus(): String = synchronized(talkerById) {
        "tracked=${talkerById.size} known=${talkerById.values.count { it != null }}"
    }

    private val ITEM_RE = Regex(
        """id:\s*(-?\d+),.*?userName:\s*([^,\s]+)""",
    )
}
