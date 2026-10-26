package com.lx.wxgroup.feature

import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.data.VoiceBackupLogic
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 拦「撤回语音时微信删磁盘音频文件」这一步。
 *
 * ## 为什么要有它（真机实锤，2026-10-25 R21c）
 *
 * 撤回语音时微信做三件事：
 * 1. `UPDATE message SET type=... WHERE msgId=?` —— [AntiRevoke] 拦住 ✅
 * 2. `DELETE FROM voiceinfo WHERE FileName= ?` —— [VoiceGuard] 拦住 ✅
 * 3. **`File.delete()` 删 `voice2/<桶>/msg_<imgPath>.amr`** —— 前两版都漏了 ❌
 *
 * 只拦前两步的真机现象：`voiceinfo` 行完好、`message` 行仍是 `type=34`，
 * **但点播放毫无反应**。播放要求「表里有记录 + 磁盘有文件」齐备。
 *
 * 实证（不是推测）：`voice2/` 全树递归扫 **20827 个目录**，对照组
 * `msg_50095010242638d35faef1f106.amr` 命中 1 个、被撤回那条
 * `4323041025263e6e1a32793103` **命中 0 个**；其桶目录 `voice2/01/70/`
 * 存在但为空，mtime = 撤回日志时刻 +4ms。
 *
 * ## 为什么 hook `File.delete()` 是可行的
 *
 * 微信删这个文件**不经过 WCDB**，但它终究要调 `java.io.File.delete()` /
 * `deleteOnExit()` 之类 —— 那是 Java 层调用，Xposed 拦得住（不像 native
 * `unlink()`）。`File` 是 bootclasspath 类，`hookAllMethods` 拿到的
 * `File.class` 是同一个类，hook 对全进程生效。
 *
 * ⚠️ **性能**：`File.delete()` 是全进程高频调用（微信到处在删临时文件、缓存）。
 *    所以 `beforeHookedMethod` 里只做**纯字符串判断**，绝不在里面查库 / 列目录：
 *      - 先看路径里有没有 `/voice2/`（`indexOf`，几纳秒）
 *      - 再看文件名是不是 `msg_<26hex>.amr`
 *    不满足就立刻返回，成本可忽略。
 *
 * ⚠️ **粒度**：只拦「文件名形如语音包名」的删除。微信删 `voice2` 下的
 *    其他东西（`.tmp`、索引、别的桶）一律放行 —— 我们只关心
 *    「刚被撤回的那条语音」，其余清理动作不能碰（否则存储只增不减）。
 */
object VoiceFileGuard {

    /** 命中判据用的路径片段（带两侧分隔符，防 `myvoice2` 这类误命中） */
    private const val VOICE_SEG = "/voice2/"

    /** 文件名前缀（微信语音包：`msg_<imgPath>.amr`） */
    private const val NAME_PREFIX = "msg_"

    /** 扩展名 */
    private const val EXT = ".amr"

    /**
     * 受保护的 imgPath（26 位 hex）→ 放行截止时刻（`elapsedRealtime`）。
     *
     * 由 [protect] 在撤回被拦下时写入；[shouldBlock] 命中后**不消费**（微信可能重试），
     * 靠 TTL 自然过期。TTL 取 [PROTECT_MS]。
     *
     * ⚠️ 为什么要 TTL：不能永久拦 —— 用户之后真的想清空语音、或微信做空间 GC 时，
     *    这些文件应当能被删掉。60 秒足够覆盖「撤回 → 用户点播放」这个窗口。
     */
    private val protected = ConcurrentHashMap<String, Long>()

    /**
     * 保护窗口。取值理由同 [VoiceGuard.WINDOW_MS]（60s）：撤回后用户通常在
     * 一分钟内会去点开那条语音看/听，再长就把微信的正常清理永久堵住了。
     */
    private const val PROTECT_MS = 60_000L

    /** 日志节流：同一文件反复被拦时不要刷屏 */
    private val logAt = ConcurrentHashMap<String, Long>()
    private const val LOG_TTL_MS = 60_000L

    /** 统计：本进程拦了多少次（探针用） */
    @Volatile
    var blockedCount = 0
        private set

    /**
     * 开关缓存。
     *
     * ⛔ 必须受「防撤回」总开关约束：`File.delete` 是全进程高频调用，
     *    如果在开关关掉时还拦，用户手动删除语音、微信做空间清理都会被我们挡住
     *    ⇒ 存储只增不减。这是比「播放失败」严重得多的问题。
     */
    @Volatile
    private var enabled = false

    @Volatile
    private var cfgAt = 0L

    private fun on(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - cfgAt > 3_000) refresh()
        return enabled
    }

    fun refresh() {
        enabled = Kv.getBool(Keys.ANTIREVOKE_MSG)
        cfgAt = SystemClock.elapsedRealtime()
    }

    /**
     * 把某个 imgPath 加入保护名单。
     *
     * @param imgPath 26 位 hex；非法值直接忽略（判据同 [VoiceBackupLogic.isValidImgPath]）
     */
    fun protect(imgPath: String?) {
        if (!VoiceBackupLogic.isValidImgPath(imgPath)) return
        val now = SystemClock.elapsedRealtime()
        protected[imgPath!!] = now + PROTECT_MS
        // 顺手清掉过期的，防止长时间运行后表无限长
        if (protected.size > 256) {
            protected.entries.removeIf { it.value < now }
        }
        LogX.i("VFG protect img=$imgPath ttl=${PROTECT_MS}ms pool=${protected.size}")
    }

    /**
     * 这个路径是否该拦。
     *
     * 判据（全部满足才拦）：
     * 1. 路径含 `/voice2/`
     * 2. 名字形如 `msg_<26hex>.amr`
     * 3. 那个 26hex 在保护名单里且未过期
     *
     * 纯字符串 + 一次 map 查，无 IO —— 可以放在 `File.delete` 这种高频路径上。
     */
    fun shouldBlock(path: String?, now: Long): Boolean {
        val img = VoiceBackupLogic.imgPathOfVoiceFile(path) ?: return false
        val until = protected[img] ?: return false
        if (until < now) {
            protected.remove(img)
            return false
        }
        return true
    }

    /** 探针用：保护名单概况 */
    fun stat(): String {
        val now = SystemClock.elapsedRealtime()
        val live = protected.entries.count { it.value >= now }
        return "stat: enabled=${on()} blocked=$blockedCount " +
            "protected(pool=${protected.size} live=$live)"
    }

    /** 安装 hook。**必须在微信进程起来时调用一次**（[HookEntry] 已挂）。 */
    fun install() {
        val cls = runCatching { XposedHelpers.findClass("java.io.File", null) }.getOrNull()
        if (cls == null) {
            LogX.w("VFG: java.io.File not found, voice file guard disabled")
            return
        }
        val ok = mutableListOf<String>()
        // 起来先读一次开关，别等第一次 File.delete 才读（那次读在 hook 里，慢）
        runCatching { refresh() }
        // File.delete() / deleteOnExit() 都不带参；两个都挂上，覆盖微信可能走的任一路径。
        for (m in listOf("delete", "deleteOnExit")) {
            runCatching { XposedBridge.hookAllMethods(cls, m, deleteGuard) }
                .onSuccess { if (it.isNotEmpty()) ok += m }
                .onFailure { LogX.w("VFG hook $m failed: ${it.javaClass.simpleName}") }
        }
        if (ok.isEmpty()) {
            LogX.w("VFG: no hook installed, voice file guard disabled")
            return
        }
        LogX.i("VFG installed hooks=${ok.joinToString(",")}")
    }

    private val deleteGuard = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // ⚠️ 高频路径：先做最便宜的判断。`thisObject` 就是那个 File 实例，
            //    取路径用反射调 toString() 太贵 ⇒ 直接拿 field。但 File.path 是 private，
            //    所以退一步：只在「文件名可能像语音包」时才取路径。
            val self = param.thisObject as? File ?: return
            // File.getName() 是纯字段拼接，比 getAbsolutePath 便宜得多
            val name = runCatching { self.name }.getOrNull() ?: return
            if (!name.startsWith(NAME_PREFIX) || !name.endsWith(EXT)) return

            val path = runCatching { self.path }.getOrNull() ?: return
            val now = SystemClock.elapsedRealtime()
            if (!shouldBlock(path, now)) return
            // ⛔ 开关关着就绝不拦（见 enabled 的 KDoc）
            if (!on()) return

            // 命中：伪装成功、不真删
            param.result = true
            blockedCount++
            val img = name.substring(NAME_PREFIX.length, name.length - EXT.length)
            val last = logAt[img]
            if (last == null || now - last > LOG_TTL_MS) {
                logAt[img] = now
                LogX.i("VFG blocked file delete img=$img path=${path.takeLast(24)}")
            }
        }
    }

    /** `SystemClock` 的薄封装，避免本文件到处都是 import */
    private object SystemClock {
        fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()
    }
}
