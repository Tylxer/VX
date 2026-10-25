package com.lx.wxgroup.core

import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.feature.ConvFilter
import com.lx.wxgroup.util.LogX

/**
 * A7：把一组会话「标记已读」。
 *
 * ## 现状：清的是**内存里的会话对象**
 *
 * 微信的会话对象是 `com.tencent.mm.storage.r4`（未读数在它的父类字段
 * `field_unReadCount` 上，见 [ConvReader]）。列表每一行的角标都直接读这个对象，
 * 所以把它置 0 再让微信重绘，未读角标立刻消失 —— **当次进程内是真生效的**。
 *
 * ⚠️ **没有落库**：微信重启后会从自己的 DB 把未读数读回来，角标会复活。
 * 要真正落库得调微信自己的会话写库接口（混淆类名，随版本变），
 * 定位到之前就先只做内存这一层，并在 UI 上如实提示。
 *
 * ## 为什么不去硬猜微信的写库方法
 *
 * 试过一条更「偷懒」的路：反射枚举 `com.tencent.mm.storage.*` 里签名像
 * `void f(String)` 的方法挨个试。问题是命中一堆同名混淆方法，
 * 试错调用会写到不该写的表（副作用不可逆），宁可不做。
 * 真要落库就老老实实用 DexKit 按字符串特征定位（见 [Anchor] 的 M1 计划）。
 */
object MarkRead {

    /** 清掉某个分组下全部成员会话的未读，返回被清掉的条数 */
    fun clearGroup(groupId: Long): Int {
        val db = GroupDb.current() ?: run {
            LogX.w("MARK group=$groupId: db not ready")
            return 0
        }
        val wx = runCatching { db.memberWxids(groupId) }.getOrDefault(emptySet())
        return clear(wx)
    }

    /**
     * 清掉这些 wxid 对应会话的未读。
     *
     * 只处理**当前列表里存在**的会话：微信的会话对象必须从列表 adapter 里取，
     * 没有会话（没聊过天）的人本来也没未读，跳过即可。
     */
    fun clear(wxids: Set<String>): Int {
        if (wxids.isEmpty()) return 0
        var n = 0
        for (conv in ConvFilter.convItemsOf(wxids)) {
            if (ConvReader.unread(conv) <= 0) continue
            if (zeroUnread(conv)) {
                n++
                LogX.i("MARK read username=${ConvReader.username(conv)}")
            }
        }
        LogX.i("MARK cleared=$n of ${wxids.size}")
        // ⚠️ 必须是 refreshSummaries()，不能是 refresh()：
        // 标记已读只把会话对象上的未读清 0，**不动 conversationTime**。而列表重建的
        // 轻量路径靠 timeFingerprint（只看 timeMs）判「摘要要不要重算」⇒ 用 refresh()
        // 会被判成「完全没变」直接短路，分组行的未读角标一直挂着，直到这个组来下一条消息。
        // refreshSummaries() 递增 summariesVersion 专门破这个短路。
        if (n > 0) ConvFilter.refreshSummaries()
        return n
    }

    /**
     * 把会话对象上的未读字段置 0。
     *
     * 字段名用 [ConvReader.unreadFieldName] —— 运行时从候选里实际命中的那一个，
     * 没命中就返回 false：宁可什么都不做，也不能瞎改一个名字像的字段。
     */
    private fun zeroUnread(conv: Any): Boolean {
        val name = ConvReader.unreadFieldName(conv) ?: run {
            LogX.w("MARK: unread field not found on ${conv.javaClass.name}")
            return false
        }
        return runCatching {
            val f = conv.javaClass.findFieldUp(name) ?: return false
            f.isAccessible = true
            when (f.type) {
                Int::class.javaPrimitiveType, Int::class.java -> f.setInt(conv, 0)
                Long::class.javaPrimitiveType, Long::class.java -> f.setLong(conv, 0L)
                Short::class.javaPrimitiveType, Short::class.java -> f.setShort(conv, 0)
                else -> return false
            }
            true
        }.onFailure { LogX.w("MARK set failed: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
    }

    /** 沿继承链找字段（[ConvReader] 的 `field()` 是私有的，这里自己走一遍） */
    private fun Class<*>.findFieldUp(name: String): java.lang.reflect.Field? {
        var c: Class<*>? = this
        var depth = 0
        while (c != null && depth < 5) {
            val f = runCatching { c!!.getDeclaredField(name) }.getOrNull()
            if (f != null) return f
            c = c.superclass
            depth++
        }
        return null
    }
}
