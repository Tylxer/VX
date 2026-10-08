package com.lx.wxgroup.probe

import android.widget.HeaderViewListAdapter
import com.lx.wxgroup.core.ConvReader
import com.lx.wxgroup.feature.GroupBar
import java.lang.reflect.Field
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * S10 探针：找出「会话的最后消息时间」存在哪个字段。
 *
 * 旧项目 GroupBar 显示时间用的是 `membersOf().maxOf { it.addedAt }`（成员加入模块的
 * 时间），那是**语义错误** —— 会话列表要按微信自己的会话时间排序，必须拿到真实字段。
 *
 * 会话对象是 `com.tencent.mm.storage.r4`（8.0.48 实测），但字段全是混淆的，
 * 所以这里把整个对象的字段都打出来，按「值像不像时间戳」来认：
 * - [MS] 毫秒级时间戳（1.7e12 量级）
 * - [S]  秒级时间戳（1.7e9 量级）
 *
 * 用法（模块跑起来、微信停在会话列表时）：
 *   adb shell am broadcast -a com.lx.wxgroup.action.PROBE \
 *       --es op convtime -p com.tencent.mm
 * 然后 logcat -s WXGroup 看输出。
 */
object ConvTimeProbe {

    private const val MS_MIN = 1_000_000_000_000L   // 2001-09-09
    private const val MS_MAX = 2_000_000_000_000L   // 2033-05-18
    private const val S_MIN = 1_000_000_000L        // 2001-09-09
    private const val S_MAX = 2_000_000_000L        // 2033-05-18

    fun dump(sample: Int = 3): List<String> {
        val out = mutableListOf<String>()
        val list = GroupBar.conversationList()
        if (list == null) {
            out.add("会话列表还没就绪（先手动进一次微信聊天页）")
            return out
        }
        val hvl = runCatching { list.adapter as? HeaderViewListAdapter }.getOrNull()
        if (hvl == null) {
            out.add("adapter=${list.adapter?.javaClass?.name} 不是 HeaderViewListAdapter")
            return out
        }
        val inner = runCatching { hvl.wrappedAdapter }.getOrNull()
        if (inner == null) {
            out.add("拿不到内层 adapter")
            return out
        }
        out.add("innerAdapter=${inner.javaClass.name} count=${inner.count}")
        val n = minOf(sample, inner.count)
        for (i in 0 until n) {
            val item = runCatching { inner.getItem(i) }.getOrNull()
            if (item == null) {
                out.add("item[$i] = null")
                continue
            }
            out.add("=== item[$i] class=${item.javaClass.name}")
            // 顺带验证 ConvReader —— 这是 S10 最终要用的读取方式
            out.add("   >> " + ConvReader.probe(item))
            dumpObject(item, out)
        }
        return out
    }

    private fun dumpObject(obj: Any, out: MutableList<String>) {
        var cls: Class<*>? = obj.javaClass
        var depth = 0
        while (cls != null && depth < 3) {
            for (f in cls.declaredFields) {
                if (f.isSynthetic) continue
                val line = runCatching {
                    f.isAccessible = true
                    format(f, obj)
                }.getOrNull() ?: continue
                out.add("   ${cls.simpleName}.${f.name} = $line")
            }
            cls = cls.superclass
            depth++
        }
    }

    private fun format(f: Field, obj: Any): String {
        val v = f.get(obj)
        val type = f.type
        return when {
            type == Long::class.javaPrimitiveType -> {
                val l = v as Long
                when {
                    l in MS_MIN..MS_MAX -> "$l  [MS] ${fmt(l)}"
                    l in S_MIN..S_MAX -> "$l  [S] ${fmt(l * 1000)}"
                    else -> l.toString()
                }
            }
            type == Int::class.javaPrimitiveType -> {
                val i = v as Int
                val l = i.toLong()
                when {
                    l in S_MIN..S_MAX -> "$i  [S?] ${fmt(l * 1000)}"
                    else -> i.toString()
                }
            }
            v == null -> "null"
            type == String::class.java -> {
                val s = v as String
                if (s.length > 60) "\"${s.take(57)}...\"" else "\"$s\""
            }
            type == Boolean::class.javaPrimitiveType -> v.toString()
            else -> "<${type.simpleName}>"
        }
    }

    private fun fmt(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
}
