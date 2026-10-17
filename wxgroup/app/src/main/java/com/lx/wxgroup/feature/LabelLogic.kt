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
 * ## ⚠️ 分隔符是候选集，不是结论
 * [SEPARATORS] 里每个字符都会当分隔符。真机抓到真实值后要在这里收敛成单一常量，
 * 并给 [splitIds] 补一条对应单测（[SEPARATORS] 放宽的代价是把「含空格的 id」
 * 误切成两个，取证后可收窄）。
 *
 * ## 三态必须分开（这是最容易写错的地方）
 * 「id 不在表里」和「id 在表里但名字是空串」是两件事：
 * - 不在表里 → 名称未知，UI 上不该显示（显示 `null` 或空字符串都是 bug）
 * - 在表里且名字为空 → 已知但无名，要能跟上面区分开（否则会被当成未知 id 静默丢掉）
 * 所以 [Index] 用 `containsKey` 判「是否已知」，值允许 null。
 */
object LabelLogic {

    /**
     * 候选分隔符。**待取证**：真实值出来后收敛。
     * 空格/制表/换行也列进来，是为了兜住「微信把整串前后包了空白」的情况。
     */
    private const val SEPARATORS = ",;| \t\r\n"

    /**
     * 把原始 id 串切成 id 列表。
     *
     * - null / 全空白 → 空列表（不是 listOf("")：空串不是有效 id）
     * - 重复 id 去重，**保持首次出现顺序**（UI 上的标签顺序要稳定）
     * - 每一段遇到 [SEPARATORS] 里的字符就截断
     *
     * ⚠️ 关于 `1, 2,3` 那种「逗号后面带空格」：那**不是**靠下面 [add] 里的 `trim()`
     * 处理的 —— 空格本身就是 [SEPARATORS] 之一，遇到它就 flush 了，
     * 所以 [cur] 里永远进不来任何空白字符，`trim()` 实际上一次都不会生效。
     * 留着它只是当 [SEPARATORS] 将来收窄（比如收敛成只有 `,`）时的兜底，
     * 别把功劳记在它头上。
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

    /**
     * 把累积的 [cur] 收进结果集并清空。
     *
     * ⚠️ 这里的 `trim()` 目前是**兜底而非主力**：[SEPARATORS] 已含全部空白字符，
     * 空白根本进不了 [cur]。它只在 [SEPARATORS] 将来收窄（收敛成单个真实分隔符）
     * 之后才有意义 —— 到那时 `1, 2,3` 里的空格才需要靠它去掉。
     */
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
