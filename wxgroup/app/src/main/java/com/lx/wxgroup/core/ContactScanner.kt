package com.lx.wxgroup.core

import android.view.View
import com.lx.wxgroup.data.Friend
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * 从任意根对象出发，广度优先遍历对象图，把所有联系人实体捞出来。
 *
 * 为什么不硬编码 adapter.data -> item.d 这条路径：
 * 通讯录的数据链路是 adapter -> AddressLiveList -> 分组列表 -> 联系人列表，
 * 中间任何一层改名都会让硬编码失效。BFS 不关心路径，只认实体类型，
 * 换版本时只要 Anchors848.CONTACT_CLASS 还准，这里就不用改。
 *
 * 四条保护：
 * 1. IdentityHashMap 去重 —— 对象图里有大量共享引用和环，不去重会死循环
 * 2. MAX_NODES / MAX_DEPTH / DEADLINE_MS 三重限 —— 兜住意外的大图
 * 3. View 不展开 —— 一个 View 上百个字段，展开会把节点预算吃光
 * 4. 集合先快照再遍历 —— 宿主的集合随时被主线程改写，直接遍历会抛
 *    ConcurrentModificationException（实测撞过，会让整个扫描静默中断）
 */
object ContactScanner {

    // 规模必须收敛：对象图里有 mParent 这种字段，一松就会顺着 RecyclerView 爬到
    // Activity / 全局缓存，几万个节点扫不完。通讯录数据实际只有 4 层深。
    private const val MAX_NODES = 20_000
    private const val MAX_DEPTH = 6
    private const val MAX_ITEMS_PER_COLLECTION = 5_000
    private const val MAX_FRIENDS = 10_000
    private const val DEADLINE_MS = 15_000L

    private val allFieldsCache = ConcurrentHashMap<Class<*>, List<Field>>()

    /** @return 按 wxid 去重后的联系人列表 */
    fun scan(root: Any): List<Friend> {
        val deadline = System.currentTimeMillis() + DEADLINE_MS
        val found = LinkedHashMap<String, Friend>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val queue = ArrayDeque<Pair<Any, Int>>()

        visited.add(root)
        queue.addLast(root to 0)

        var nodes = 0
        while (queue.isNotEmpty() && nodes < MAX_NODES && found.size < MAX_FRIENDS) {
            // 兜底：宁可少收几个，也不能把宿主进程卡住
            if (nodes % 500 == 0 && System.currentTimeMillis() > deadline) break
            val (obj, depth) = queue.removeFirst()
            nodes++
            if (depth > MAX_DEPTH) continue

            // 命中联系人就收下，不再往里展开（省掉大量无用节点）
            val friend = runCatching { ContactReader.read(obj) }.getOrNull()
            if (friend != null) {
                found.putIfAbsent(friend.wxid, friend)
                continue
            }
            if (depth < MAX_DEPTH) expand(obj, depth, queue, visited)
        }
        return found.values.toList()
    }

    /**
     * 从单个 ViewHolder 里挖出它绑定的那个联系人。
     *
     * 预算刻意比 scan() 小一个量级：绑定路径上会调几千次（2245 个好友滚一遍），
     * 而一个 ViewHolder 的引用图非常浅，实际只需要几十个节点。
     */
    fun firstContact(root: Any): Friend? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val queue = ArrayDeque<Pair<Any, Int>>()
        visited.add(root)
        queue.addLast(root to 0)
        var nodes = 0
        while (queue.isNotEmpty() && nodes < 3_000) {
            val (obj, depth) = queue.removeFirst()
            nodes++
            if (depth > 4) continue
            runCatching { ContactReader.read(obj) }.getOrNull()?.let { return it }
            if (depth < 4) expand(obj, depth, queue, visited)
        }
        return null
    }

    private fun expand(
        obj: Any,
        depth: Int,
        queue: ArrayDeque<Pair<Any, Int>>,
        visited: MutableSet<Any>,
    ) {
        for (f in instanceFieldsOf(obj.javaClass)) {
            val v = runCatching { f.get(obj) }.getOrNull() ?: continue
            when (v) {
                is String, is Number, is Boolean, is Char -> Unit // 叶子，不用展开
                is View -> Unit                                   // 展开会爆
                is Iterable<*> -> feed(v, depth, queue, visited)
                is Map<*, *> -> feed(v.values, depth, queue, visited)
                is Array<*> -> feed(v.asList(), depth, queue, visited)
                else -> {
                    val cn = v.javaClass.name
                    if (cn.startsWith("java.") || cn.startsWith("kotlin.") ||
                        cn.startsWith("android.") || cn.startsWith("androidx.")
                    ) continue
                    enqueue(v, depth + 1, queue, visited)
                }
            }
        }
    }

    /** 集合可能正在被主线程改写，先做快照再遍历，避免 ConcurrentModificationException 打断整个扫描 */
    private fun feed(
        src: Iterable<*>,
        depth: Int,
        queue: ArrayDeque<Pair<Any, Int>>,
        visited: MutableSet<Any>,
    ) {
        val snapshot = runCatching {
            val out = ArrayList<Any?>(minOf(MAX_ITEMS_PER_COLLECTION, 512))
            var i = 0
            for (e in src) {
                if (i++ >= MAX_ITEMS_PER_COLLECTION) break
                out.add(e)
            }
            out
        }.getOrNull() ?: return
        for (e in snapshot) {
            if (e != null) enqueue(e, depth + 1, queue, visited)
        }
    }

    private fun enqueue(
        obj: Any,
        depth: Int,
        queue: ArrayDeque<Pair<Any, Int>>,
        visited: MutableSet<Any>,
    ) {
        if (queue.size > MAX_NODES) return
        if (!visited.add(obj)) return
        queue.addLast(obj to depth)
    }

    private fun instanceFieldsOf(cls: Class<*>): List<Field> = allFieldsCache.getOrPut(cls) {
        val out = ArrayList<Field>(32)
        var c: Class<*>? = cls
        var guard = 0
        while (c != null && c != java.lang.Object::class.java && guard++ < 4) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                runCatching { f.isAccessible = true }
                out.add(f)
            }
            c = c.superclass
        }
        out
    }
}
