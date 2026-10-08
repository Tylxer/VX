package com.lx.wxgroup.core

import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * S10：从微信的「会话」对象里读出 wxid 和最后消息时间。
 *
 * ## 实测（8.0.48 / 2588 谷歌版）
 * 会话对象 = `com.tencent.mm.storage.r4`，但这两个字段**不在 r4 自身，而在它的父类**
 * （实到类名 `y1`）。字段本身没混淆：
 * - `field_username`        → wxid，如 `wx__c_18c13d8bbf6`、群是 `......@chatroom`
 * - `field_conversationTime`→ **毫秒**时间戳，实测 1791454506000 = 2026-10-08 18:15:06，
 *   与会话列表上显示的时间一致
 *
 * ## 为什么不在 r4 自身找
 * 混淆后字段名可能整体挪到父类。这里按**名字沿类链向上找**，
 * 只要字段名不变就不用改代码；找不到就静默返回默认值，绝不让列表渲染崩掉。
 *
 * 旧项目 GroupBar 显示时间用的是「成员加入模块的时间」（`addedAt`），语义是错的，
 * 分组行要插在「该组成员最新一条会话」的位置，必须用这里的真实会话时间。
 */
object ConvReader {

    private const val F_USERNAME = "field_username"
    private const val F_TIME = "field_conversationTime"

    /** 类 → 字段，找到一次就缓存；找不到缓存 null，避免每次都做昂贵的反射遍历 */
    private val usernameCache = ConcurrentHashMap<Class<*>, Field?>()
    private val timeCache = ConcurrentHashMap<Class<*>, Field?>()

    fun username(conv: Any?): String {
        conv ?: return ""
        val f = field(conv.javaClass, F_USERNAME, usernameCache) ?: return ""
        return runCatching { f.get(conv) as? String }.getOrNull() ?: ""
    }

    /** 最后消息时间，毫秒；读不到返回 0（0 会被当成「没有会话」排到末尾） */
    fun timeMs(conv: Any?): Long {
        conv ?: return 0L
        val f = field(conv.javaClass, F_TIME, timeCache) ?: return 0L
        val v = runCatching { f.get(conv) }.getOrNull() ?: return 0L
        return when (v) {
            is Long -> v
            is Int -> v.toLong()
            else -> 0L
        }
    }

    private fun field(cls: Class<*>, name: String, cache: ConcurrentHashMap<Class<*>, Field?>): Field? {
        cache[cls]?.let { return it }
        var c: Class<*>? = cls
        var depth = 0
        while (c != null && depth < 5) {
            val f = runCatching { c!!.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                runCatching { f.isAccessible = true }
                cache[cls] = f
                return f
            }
            c = c.superclass
            depth++
        }
        cache[cls] = null
        return null
    }

    /** 调试用：这个会话对象能不能读出时间和 wxid */
    fun probe(conv: Any?): String {
        conv ?: return "null"
        return "class=${conv.javaClass.name} username=${username(conv)} timeMs=${timeMs(conv)}"
    }
}
