package com.lx.wxgroup.feature

import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.HashMap

/**
 * 新加群自动打开微信自带「消息免打扰」，自己创建的群除外。
 *
 * 四个判定点对应需求 6.1 的第四条：
 *   1. 新群      —— 只在进群那一刻设一次，设置后写入 auto_dnd_done；
 *   2. 自建群    —— 群主是自己则不设，且**不记台账**（用户后面自己改不影响）；
 *   3. 存量群    —— 开启开关之前就在的群，只在它出现新进群事件时才管；
 *   4. 手动关掉后 —— 已记台账的群不再重复开启（[GroupDb.isDndHandled]）。
 *
 * ⚠️ 待取证：8.0.48/2588 GP 的「进群事件」回调和「设置免打扰」的方法尚未定位，
 * 下面两个常量不能用猜测的混淆名填（会误伤普通点击 / 写错库）。锚点确定后填入即可。
 *
 * 退路：进群事件不一定存在稳定 hook 点，定位不到会漏设。为此保留开关分支
 * [SWEEP_FALLBACK_ENABLED]，改为定期扫描已识别群列表补设（对每个群走同一套
 * 判定 + 台账）。与实时分支共用 [applyOne]，语义一致。
 */
object AutoDndGroup {

    private const val JOIN_EVENT_CLASS_8048 = "" // 待取证：进群事件所在类
    private const val JOIN_EVENT_METHOD_8048 = "" // 待取证：进群回调方法名

    /** 进群事件定位不到时，改为扫描补设。锚点确定后建议关掉，避免无谓开销。 */
    private const val SWEEP_FALLBACK_ENABLED = true

    private const val SWEEP_INTERVAL_MS = 5 * 60 * 1000L

    @Volatile
    private var installed = false

    @Volatile
    private var joinHooked = false

    @Volatile
    private var lastSweepAt = 0L

    private val applied = Collections.synchronizedMap(HashMap<String, Boolean>())

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            joinHooked = installJoinHook(lpparam)
            if (joinHooked) {
                installed = true
            } else if (SWEEP_FALLBACK_ENABLED) {
                installed = true
                LogX.i("QUIET ADND fallback=sweep only (join hook pending evidence)")
            } else {
                LogX.i("QUIET ADND skipped: join anchor pending evidence")
                return
            }
            LogX.i("QUIET ADND installed joinHooked=$joinHooked sweep=$SWEEP_FALLBACK_ENABLED")
        }.onFailure {
            installed = false
            LogX.i("QUIET ADND install failed: ${it.javaClass.simpleName}")
        }
    }

    private fun installJoinHook(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        if (JOIN_EVENT_CLASS_8048.isBlank() || JOIN_EVENT_METHOD_8048.isBlank()) return false
        val eventClass = runCatching {
            Class.forName(JOIN_EVENT_CLASS_8048, false, lpparam.classLoader)
        }.getOrNull() ?: return false
        val methods = eventClass.declaredMethods.filter { it.name == JOIN_EVENT_METHOD_8048 }
        if (methods.isEmpty()) return false
        LogX.i("QUIET ADND join anchor present but handler pending evidence")
        return false
    }

    /** 主入口：判定 + 设置 + 记账；失败静默放弃（绝不崩微信）。 */
    fun applyOne(chatroomId: String, ownerWxid: String?, selfWxid: String?): Boolean {
        return runCatching {
            if (!installed || !Kv.getBool(Keys.CHAT_AUTO_DND_NEW_GROUP)) return false
            if (chatroomId.isBlank() || !chatroomId.endsWith("@chatroom")) return false
            if (alreadyHandled(chatroomId)) return false
            val self = selfWxid?.trim().orEmpty()
            val owner = ownerWxid?.trim().orEmpty()
            if (self.isNotBlank() && self == owner) {
                LogX.i("QUIET ADND skip own group $chatroomId")
                return false
            }
            if (!setMute(chatroomId)) return false
            GroupDb.current()?.markDndHandled(chatroomId)
            applied[chatroomId] = true
            LogX.i("QUIET ADND muted $chatroomId")
            true
        }.getOrDefault(false)
    }

    /** 扫描退路：定时对当前已知群跑一遍 [applyOne]，命中新群就补设。 */
    fun sweepIfDue(nowMs: Long = System.currentTimeMillis(), chatrooms: List<String>) {
        runCatching {
            if (!installed || !SWEEP_FALLBACK_ENABLED) return
            if (nowMs - lastSweepAt < SWEEP_INTERVAL_MS) return
            lastSweepAt = nowMs
            for (room in chatrooms) applyOne(room, null, null)
        }.onFailure { LogX.i("QUIET ADND sweep failed: ${it.javaClass.simpleName}") }
    }

    private fun alreadyHandled(chatroomId: String): Boolean =
        applied[chatroomId] == true || GroupDb.current()?.isDndHandled(chatroomId) == true

    /**
     * 打开微信自带的消息免打扰。
     *
     * 待取证：真实写库口径（会话表免打扰位 / 群设置接口）未确认，这里保留单一写入点，
     * 拿到锚点后只改本方法，不扩散到别处。未取证前返回 false（宁可不设，也不写错库）。
     */
    private fun setMute(chatroomId: String): Boolean {
        if (chatroomId.isBlank()) return false
        return false
    }

    fun probe(sub: String): String = runCatching {
        when (sub.lowercase()) {
            "status" -> "installed=$installed joinHooked=$joinHooked sweep=$SWEEP_FALLBACK_ENABLED enabled=${Kv.getBool(Keys.CHAT_AUTO_DND_NEW_GROUP)} applied=${applied.size}"
            "anchor" -> "join=${JOIN_EVENT_CLASS_8048.ifBlank { "待取证" }}#${JOIN_EVENT_METHOD_8048.ifBlank { "待取证" }} setter=待取证"
            "applied" -> "applied=${applied.keys.toList()}"
            else -> "subs=status,anchor,applied"
        }
    }.getOrElse { "error=${it.javaClass.simpleName}" }
}
