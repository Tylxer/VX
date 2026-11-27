package com.lx.wxgroup.feature

/** 防@静音通道 id（B：换新 id 绕开被 user-locked 的旧通道，并把 importance 降到 LOW）。
 * 单一真源：hook 侧与单测侧都引用这里，避免字符串漂移与 importance 写死。 */
object MuteChannelId {
    const val VALUE = "wxgroup_silent_low"
    const val NAME = "静音通知（分组模块·低重要性）"
    /** 对应 NotificationManager.IMPORTANCE_LOW(=2)：保留通知栏显示、静音、不弹横幅。
     * 不用 IMPORTANCE_HIGH(=4，会响) 也不用 IMPORTANCE_MIN(=1，通知更隐蔽)。
     * 单一真源：hook 侧建通道必须用这个值，禁止写死数字。 */
    const val IMPORTANCE_LEVEL = 2
    /** 严格相等判定 */
    fun isLowImportance(level: Int): Boolean = level == IMPORTANCE_LEVEL
    /** 严格相等判定（禁止 contains/startsWith，防子串误命中） */
    fun isMute(id: String?): Boolean = id == VALUE
}
