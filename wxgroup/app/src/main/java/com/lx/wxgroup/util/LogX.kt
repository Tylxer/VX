package com.lx.wxgroup.util

import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * 模块日志。
 *
 * **logcat 不可靠**：实测同一进程里，某些调用点的 `Log.i` 压根进不了 logcat 缓冲区
 * （同 tag 同级别，另一些点却正常），排查时会把人带偏。所以所有日志**同时落盘**到
 * 微信自己的 external cache —— 那个目录 adb pull 读得到，且不需要 root。
 *
 * 落盘做了两层保护，防止某条热路径日志把 IO 打爆：
 *  - 相同内容 1 秒内只写一次（[DEDUP_MS]）
 *  - 总行数上限 [MAX_LINES]，超了**环形覆盖**（清空重来，不是停写 —— 见 [file] 里的说明）
 */
object LogX {

    private const val TAG = "WXGroup"

    private const val DEDUP_MS = 1000L
    private const val MAX_LINES = 5000

    /** 微信分区存储下 /sdcard 根目录写不了，external cache 可以 */
    private val DBG_PATHS = arrayOf(
        "/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt",
        "/storage/emulated/0/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt",
    )

    private val lock = Any()
    private var filePath: String? = null
    private var lines = 0
    private var lastMsg = ""
    private var lastMs = 0L

    fun d(msg: String) {
        runCatching { Log.d(TAG, msg) }
        bridgeLog("D $msg")
        file(msg)
    }

    fun i(msg: String) {
        runCatching { Log.i(TAG, msg) }
        bridgeLog("I $msg")
        file(msg)
    }

    fun w(msg: String) {
        runCatching { Log.w(TAG, msg) }
        bridgeLog("W $msg")
        file(msg)
    }

    fun e(msg: String, throwable: Throwable? = null) {
        runCatching { Log.e(TAG, msg, throwable) }
        bridgeLog("E $msg")
        file(msg)
        if (throwable != null) {
            bridgeLog(throwable)
            file("E   " + throwable.javaClass.name + ": " + throwable.message)
            throwable.stackTrace.take(8).forEach { file("E   at $it") }
        }
    }

    /** 显式落盘（不打 logcat），仅需要文件留痕的高频点用 */
    fun f(msg: String) = file(msg)

    private fun file(msg: String) {
        runCatching {
            synchronized(lock) {
                val now = System.currentTimeMillis()
                if (msg == lastMsg && now - lastMs < DEDUP_MS) return
                lastMsg = msg
                lastMs = now

                val path = filePath ?: resolvePath() ?: return
                val ts = now()

                // ⚠️ 写满后**不能停写**：曾经的做法是 `if (lines >= MAX_LINES) return`，
                // 表现是「广播发了但日志毫无反应」，会被误判成广播没投递 / 模块没加载
                // （这个坑踩过三次，每次都要花十几分钟重新排查）。改为环形覆盖：
                // 清空从头写，最新日志永远在文件里，并留一行轮转标记。
                if (lines >= MAX_LINES) {
                    java.io.File(path).writeText(
                        "$ts ---- log rotated: 已达 $MAX_LINES 行，清空重来（最新日志始终保留）----\n"
                    )
                    lines = 0
                }

                java.io.File(path).appendText("$ts ${Thread.currentThread().name} $msg\n")
                lines++
            }
        }
    }

    private fun now(): String = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        .format(java.util.Date())

    /**
     * 清空落盘日志并归零计数。
     *
     * 以前要让日志「干净」必须 `am force-stop` 两次再拉起微信（≈30 秒 + 模块重载不
     * 确定的风险）。现在一条广播就能清，测试节奏快很多。
     * ⚠️ 必须连 dedup 状态一起清，否则紧接着发的同内容日志会被 1 秒去重吞掉。
     *
     * @return 清掉的行数
     */
    fun reset(): Int {
        synchronized(lock) {
            val n = lines
            runCatching { filePath?.let { java.io.File(it).writeText("") } }
            lines = 0
            lastMsg = ""
            lastMs = 0L
            return n
        }
    }

    private fun resolvePath(): String? {
        for (p in DBG_PATHS) {
            val ok = runCatching {
                val f = java.io.File(p)
                f.parentFile?.mkdirs()
                f.appendText("")
                true
            }.getOrDefault(false)
            if (ok) {
                filePath = p
                return p
            }
        }
        return null
    }

    // 每个入口都要能吞掉异常：日志组件本身绝不能成为崩溃源
    private fun bridgeLog(msg: String) {
        runCatching { XposedBridge.log("$TAG/$msg") }
    }

    private fun bridgeLog(throwable: Throwable) {
        runCatching { XposedBridge.log(throwable) }
    }
}
