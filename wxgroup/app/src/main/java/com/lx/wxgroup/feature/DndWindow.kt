package com.lx.wxgroup.feature

import android.app.Notification
import android.os.Build
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** 免打扰时段；微信语音/视频来电始终放行。 */
object DndWindow {

    @Volatile
    private var registered = false

    private val muted = AtomicLong(0)
    private val callsPassed = AtomicLong(0)

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            registered = QuietNotificationBridge.install(lpparam)
            if (registered) QuietNotificationBridge.enableDnd()
            LogX.i("QUIET DND install registered=$registered")
        }.onFailure {
            registered = false
            LogX.i("QUIET DND install failed: ${it.javaClass.simpleName}")
        }
    }

    internal fun shouldMute(notification: Notification): Boolean {
        return runCatching {
            if (!registered || !Kv.getBool(Keys.CHAT_DND_ENABLED)) return false
            if (isIncomingCall(notification)) {
                callsPassed.incrementAndGet()
                return false
            }
            val now = Calendar.getInstance(Locale.getDefault()).let {
                it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
            }
            val active = QuietLogic.inDndWindow(
                now,
                Kv.getString(Keys.CHAT_DND_START),
                Kv.getString(Keys.CHAT_DND_END),
            )
            if (active) muted.incrementAndGet()
            active
        }.getOrDefault(false)
    }

    /** 任一特征命中即按微信来电放行。 */
    internal fun isIncomingCall(notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_CALL) return true
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { notification.channelId }.getOrNull()
        } else null
        if (channel?.startsWith("voip_") == true) return true
        return runCatching {
            val field = Notification::class.java.getDeclaredField("fullScreenIntent")
            field.isAccessible = true
            field.get(notification) != null
        }.getOrDefault(false)
    }

    /**
     * 探针用法（入口 `op=quiet`：`sub` 选中本功能，`sub2` 是本功能的子命令）：
     *   `--es op quiet --es sub dnd --es sub2 status|window|call`
     */
    fun probe(sub: String): String = runCatching {
        when (sub.lowercase()) {
            "status" -> "registered=$registered enabled=${Kv.getBool(Keys.CHAT_DND_ENABLED)} muted=${muted.get()} callsPassed=${callsPassed.get()}"
            "window" -> {
                val now = Calendar.getInstance(Locale.getDefault()).let {
                    it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
                }
                val start = Kv.getString(Keys.CHAT_DND_START)
                val end = Kv.getString(Keys.CHAT_DND_END)
                "start=$start end=$end now=$now active=${QuietLogic.inDndWindow(now, start, end)}"
            }
            "call" -> "callsPassed=${callsPassed.get()} criteria=category:call|channel:voip_*|fullScreenIntent"
            else -> "subs=status,window,call"
        }
    }.getOrElse { "error=${it.javaClass.simpleName}" }
}
