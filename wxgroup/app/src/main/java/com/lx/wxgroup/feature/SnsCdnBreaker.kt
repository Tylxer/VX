package com.lx.wxgroup.feature

/**
 * 熔断器状态机（纯内存、纯逻辑）。
 *
 * 零 Android 依赖：本文件禁止 import 任何 android.* / xposed.*，否则 JVM 单测加载不了。
 * 时间一律由调用方以 `now: Long` 传入，内部不取系统时间、不做 IO、不打日志。
 *
 * @param limit  连续失败多少次触发熔断（默认 8）
 * @param coolMs 熔断冷却时长，超过即自动复位（默认 600_000 = 10 分钟）
 */
class SnsCdnBreaker(private val limit: Int = 8, private val coolMs: Long = 600_000L) {

    /** 当前连续失败计数（触发熔断瞬间清零，下次熔断重新累计）。 */
    private var streakCount = 0

    /** 是否处于熔断中（原始值，不含冷却判定）。 */
    private var tripped = false

    /** 熔断触发时刻（仅 tripped 时有意义）。 */
    private var tripAt = 0L

    /** 记一次失败。返回 true 表示「**本次刚好触发**熔断」（调用方据此决定是否打一条告警）。 */
    fun hit(now: Long): Boolean {
        if (tripped) {
            if (now - tripAt > coolMs) {
                resetTripped()
            } else {
                // 熔断期间 streak 钳在 limit，防止无限增长
                if (streakCount < limit) streakCount++
                // ⚠️ 这里必须 return false，不能 return true（2026-10-20 R11 复核 S-1）：
                // 本函数的契约是「true = 本次刚好触发」。熔断期间每次失败都返回 true 的话，
                // 调用方 `if (breaker.hit(now)) LogX.w(...)` 会**每张失败的图各打一条日志**
                // ⇒ 日志洪水（LogX 是同步文件 IO，写到满 5000 行还会环形覆盖掉有用信息）。
                // 当前接线恰好被 enabled() 先短路所以没暴露，但那是隐式约定，不牢靠。
                return false
            }
        }
        streakCount++
        if (streakCount >= limit) {
            tripped = true
            tripAt = now
            streakCount = 0
            return true
        }
        return false
    }

    /** 记一次成功，连续计数清零。 */
    fun ok() {
        streakCount = 0
    }

    /** 是否处于熔断中。内部先做冷却判定：now - tripAt > coolMs ⇒ 自动复位并返回 false。 */
    fun blocked(now: Long): Boolean {
        if (tripped && now - tripAt > coolMs) {
            resetTripped()
        }
        return tripped
    }

    /** 手动复位（探针 sub=reset / 用户重开开关时调用）。 */
    fun reset() {
        resetTripped()
    }

    /** 当前连续失败计数（只读，供 stat 输出）。 */
    fun streak(): Int = streakCount

    /** 是否熔断中（不做冷却判定的原始值，供 stat 输出区分「冷却中」与「已复位」）。 */
    fun rawTripped(): Boolean = tripped

    private fun resetTripped() {
        tripped = false
        tripAt = 0L
        streakCount = 0
    }
}
