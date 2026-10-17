package com.lx.wxgroup.feature

import java.util.Collections
import java.util.LinkedHashSet

/**
 * 微信标签（contactLabel）的**纯判据**。零 Android 依赖 → JVM 单测可直接跑。
 *
 * ## 现状：id→name 的取数链**待取证**
 * 已确认的锚点只有一半：`com.tencent.mm.storage.h4.field_contactLabelIds`
 * （`core/Anchors848.kt`，旧项目实机 dump 的字段名）能读出「这个联系人身上挂了哪些标签 id」，
 * 但**没有任何实证**说明 id 串的分隔符是什么、标签名（id → 名称）存在哪。
 * 所以本类只做「拿到原始串之后」的判定，取数部分见 `probe/LabelHuntProbe.kt`
 * 与 `docs/标签数据源取证方案.md`。
 *
 * ## ⚠️ 分隔符待真机确认，当前为兼容解析
 * [SEPARATORS] 里每个字符都会当分隔符。主代理下一轮会从 `rcontact.contactLabelIds`
 * 取真实原始串；确认后再收敛候选集，避免长期把合法 id 中的字符误当分隔符。
 *
 * ## 三态必须分开（这是最容易写错的地方）
 * 「id 不在表里」和「id 在表里但名字是空串」是两件事：
 * - 不在表里 → 名称未知，UI 上不该显示（显示 `null` 或空字符串都是 bug）
 * - 在表里且名字为空 → 已知但无名，要能跟上面区分开（否则会被当成未知 id 静默丢掉）
 * 所以 [Index] 用 `containsKey` 判「是否已知」，值允许 null。
 */
object LabelLogic {

    /**
     * 候选分隔符。**待真机确认，当前为兼容解析**。
     * 除常见半角符号外兼容中文顿号、全角逗号与空白。
     */
    private const val SEPARATORS = ",，;|、 \t\r\n"

    /**
     * 把原始 id 串切成 id 列表。
     *
     * - null / 全空白 → 空列表（不是 listOf("")：空串不是有效 id）
     * - 重复 id 去重，**保持首次出现顺序**（UI 上的标签顺序要稳定）
     * - 每段 trim：微信很可能写 `1, 2,3`
     */
    fun splitIds(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        val cur = StringBuilder()
        for (c in raw) {
            if (SEPARATORS.indexOf(c) >= 0) {
                add(cur, out)
            } else {
                cur.append(c)
            }
        }
        add(cur, out)
        return out.toList()
    }

    private fun add(cur: StringBuilder, out: LinkedHashSet<String>) {
        if (cur.isEmpty()) return
        val s = cur.toString().trim()
        if (s.isNotEmpty()) out.add(s)
        cur.setLength(0)
    }

    /**
     * 形状检查：不含分隔符且非空。
     *
     * ⚠️ 只用于**探针诊断**（判断抓到的串像不像 id 列表），
     * [splitIds] 不拿它过滤 —— 形状判错了会静默丢数据，比多收几个坏 id 糟得多。
     */
    fun isPlausibleId(id: String): Boolean =
        id.isNotEmpty() && id.all { SEPARATORS.indexOf(it) < 0 }

    /** 一条解析结果。`name == null` = id 在 [Index] 里查不到（未知标签） */
    data class Resolved(val id: String, val name: String?)

    /**
     * id → 名称的索引。
     *
     * ⚠️ **不能用 ConcurrentHashMap**：它不允许 null value，而「已知 id、空名字」
     * 必须能表达。用 `synchronizedMap(HashMap<String, String?>())` + `containsKey`。
     */
    class Index {

        private val map: MutableMap<String, String?> =
            Collections.synchronizedMap(HashMap<String, String?>())

        /** @param name null = 已知这个 id 但没有名字 */
        fun put(id: String, name: String?) {
            if (id.isEmpty()) return
            synchronized(map) { map[id] = name }
        }

        fun putAll(src: Map<String, String?>) {
            if (src.isEmpty()) return
            synchronized(map) { map.putAll(src) }
        }

        /** 用一次临界区原子替换整份索引，加载失败时旧快照不受影响。 */
        fun replaceAll(src: Map<String, String?>) {
            synchronized(map) {
                map.clear()
                map.putAll(src)
            }
        }

        /** 诊断 / UI 使用的稳定快照；调用方拿不到内部可变 map。 */
        fun snapshot(): Map<String, String?> = synchronized(map) { HashMap(map) }

        /** 这个 id 是否在索引里（**不关心**名字是否为空） */
        fun has(id: String): Boolean = synchronized(map) { map.containsKey(id) }

        /** 名字；null = 未知 id（也可能已知但名为空，用 [has] 区分） */
        fun nameOf(id: String): String? = synchronized(map) { map[id] }

        fun size(): Int = synchronized(map) { map.size }

        fun ids(): List<String> = synchronized(map) { map.keys.toList() }

        fun clear() = synchronized(map) { map.clear() }
    }

    /**
     * 原始 id 串 → 已知标签名列表（**丢掉未知 id**）。
     *
     * 丢而不是留占位符：调用方拿它是为了显示在联系人身上，
     * 一个 `null` / 「未知标签」占位比不显示更糟（用户会以为微信里真有这个标签）。
     */
    fun namesOf(raw: String?, index: Index): List<String> =
        resolve(raw, index).mapNotNull { it.name }

    /**
     * 原始 id 串 → 逐条解析结果（**保留未知 id**，name=null）。
     *
     * 给探针 / 诊断用：要能看出「哪些 id 没在索引里」，否则取数链断了没有任何痕迹。
     */
    fun resolve(raw: String?, index: Index): List<Resolved> =
        splitIds(raw).map { Resolved(it, index.nameOf(it)) }

    /** 这批 id 里有几个在索引里查不到（探针自检用） */
    fun unknownCount(raw: String?, index: Index): Int =
        resolve(raw, index).count { it.name == null }
}
