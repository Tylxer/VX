package com.lx.wxgroup.core

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.lx.wxgroup.data.Friend
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.util.LogX
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 长按通讯录条目时，反查出这一条对应的好友（wxid）。
 *
 * ⚠️ 微信把 androidx 重打包改造过，标准套路全部失效（8.0.48 实测）：
 *   - androidx.recyclerview.widget.RecyclerView$Adapter 类 **不存在**，hook 不了 bindViewHolder
 *   - WxRecyclerView 上没有 getChildAdapterPosition / getChildViewHolder / scrollToPosition
 *   - adapter 的 ViewHolder（ki4.m0）里没有数据，位置访问器 i()/j()/k() 一律返回 -1
 *
 * 所以这里按「先准后稳」两级来：
 *   1. 准：adapter 上形如 X(View):ViewHolder 的方法（8.0.48 是 Z0）拿到 Holder，BFS 挖 Contact
 *   2. 稳：读条目里 TextView 的文本（就是用户看到的名字），回自己的 friend_cache 反查
 * 第 2 级只依赖显示名 + 自己的库，完全不碰微信内部结构，是最终兜底。
 *
 * 方法名不硬编码，一律按签名特征现找，换版本只要特征还在就还能用。
 */
object ContactBinder {

    /** itemView -> 好友。null 表示确实没挖到（负缓存，避免重复扫描） */
    private val cache = Collections.synchronizedMap(WeakHashMap<View, Friend?>())

    private val vhMethodCache = ConcurrentHashMap<Class<*>, Method?>()

    /** 长按命中的通常是条目内部的子 View，这里负责爬到条目根布局并反查 */
    fun resolve(view: View): Friend? {
        var cur: View = view
        var depth = 0
        while (depth++ < 12) {
            if (cache.containsKey(cur)) return cache[cur]
            val p = cur.parent
            if (p !is View) break
            cur = p
        }
        val rv = ContactListRef.listView
        val item = if (rv != null) directChildOf(rv, view) ?: view else view
        if (cache.containsKey(item)) return cache[item]
        return resolveItem(item)
    }

    fun resolveItem(item: View): Friend? {
        val adapter = ContactListRef.adapter
        if (adapter != null) {
            val holder = viewHolderOf(adapter, item)
            if (holder != null) {
                val friend = ContactScanner.firstContact(holder)
                if (friend != null) {
                    cache[item] = friend
                    return friend
                }
            }
        }
        // 兜底：按条目上显示的名字回库里查
        val friend = byDisplayName(item)
        cache[item] = friend
        return friend
    }

    private fun byDisplayName(item: View): Friend? {
        val db = GroupDb.current() ?: return null
        val texts = textsOf(item)
        val hits = LinkedHashMap<String, Friend>()
        for (t in texts) {
            if (t.length < 2) continue // 跳过字母索引头（A/B/C…）
            for (f in db.findByDisplayName(t)) hits.putIfAbsent(f.wxid, f)
        }
        return when {
            hits.isEmpty() -> null
            hits.size == 1 -> hits.values.first()
            else -> {
                // 重名是真的存在（2245 个好友里 28 组），绝不猜，宁可不做
                LogX.w("AMBIGUOUS texts=$texts candidates=${hits.values.map { it.wxid }}")
                null
            }
        }
    }

    /** 收集条目里所有 TextView 的文本 */
    private fun textsOf(view: View): List<String> {
        val out = ArrayList<String>(6)
        collectTexts(view, out, 0)
        return out
    }

    private fun collectTexts(v: View, out: MutableList<String>, depth: Int) {
        if (depth > 6 || out.size >= 6) return
        if (v is TextView) {
            val t = v.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) out.add(t)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                v.getChildAt(i)?.let { collectTexts(it, out, depth + 1) }
            }
        }
    }

    /**
     * 找 adapter 上形如 `X(View): ViewHolder` 的方法。
     * 特征：单参、参数是 View、返回非 void 且非基本类型（ViewHolder 必定是对象）。
     */
    private fun viewHolderOf(adapter: Any, item: View): Any? {
        val cls = adapter.javaClass
        val method = vhMethodCache.getOrPut(cls) {
            cls.methods.firstOrNull { m ->
                m.parameterCount == 1 &&
                    View::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                    m.returnType != Void.TYPE &&
                    !m.returnType.isPrimitive
            }
        } ?: return null
        return runCatching { method.invoke(adapter, item) }.getOrNull()
    }

    private fun directChildOf(rv: View, v: View): View? {
        var cur: View = v
        var depth = 0
        while (depth++ < 12) {
            val p = cur.parent
            if (p === rv) return cur
            if (p !is View) return null
            cur = p
        }
        return null
    }

    fun cachedCount(): Int = cache.size

    /** 调试用：把找到的方法签名打出来，确认没认错 */
    fun describe(): String {
        val adapter = ContactListRef.adapter ?: return "adapter not ready"
        val m = vhMethodCache[adapter.javaClass]
        return "${adapter.javaClass.name}.${m?.name ?: "?"}(View):${m?.returnType?.simpleName ?: "?"}"
    }
}
