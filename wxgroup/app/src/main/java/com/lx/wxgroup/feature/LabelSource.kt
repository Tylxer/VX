package com.lx.wxgroup.feature

import com.lx.wxgroup.util.LogX
import java.util.Collections

/**
 * 微信标签数据源（K3 选择器「数据源＝微信标签」要用）。
 *
 * # ⚠️ 状态：**待取证**，本对象只提供「装载 + 查询」的壳
 *
 * 已知的锚点只有一半（`core/Anchors848.kt`）：
 * - ✅ 联系人实体 `com.tencent.mm.storage.h4` 上有 `field_contactLabelIds`
 *   → 能读出「这个人身上的标签 id 串」
 * - ❌ **id → 标签名**的映射从哪来，没有任何实机证据：
 *   微信库里到底有没有标签表、表名叫什么、是不是跟 `rcontact` 在同一个库，全是未知。
 *
 * 项目铁律是不许把猜出来的表名/类名当实现，所以**这里不写死任何取数代码**。
 * 数据由取证确认后的来源调 [ingestLabels] / [ingestUserLabels] 灌进来；
 * 在此之前 [labelsOf] 恒返回空列表、[labelName] 恒返回 null。
 *
 * 取证步骤见 `docs/标签数据源取证方案.md` + `probe/LabelHuntProbe.kt`（`op=label`）。
 */
object LabelSource {

    private const val TAG = "LABEL"

    private val index = LabelLogic.Index()

    /** userName(wxid) → 原始 id 串。同样待取证：现在是空的 */
    private val userIds: MutableMap<String, String> =
        Collections.synchronizedMap(HashMap<String, String>())

    /** id → 标签名；null = 未知 id（或已知但名为空，用 [hasLabel] 区分） */
    fun labelName(id: String): String? = index.nameOf(id)

    fun hasLabel(id: String): Boolean = index.has(id)

    /**
     * 这个人身上的标签名列表。
     *
     * ⚠️ **待取证**：在拿到 `userName → 标签 id 串` 的取数路径之前恒为空。
     * 空列表的语义是「不知道」，不是「这个人没有标签」—— 调用方（K3 选择器）
     * 在 [ready] 为 false 时应当**不显示微信标签段**，而不是显示一个空的段。
     */
    fun labelsOf(userName: String): List<String> {
        val raw = userIds[userName] ?: return emptyList()
        return LabelLogic.namesOf(raw, index)
    }

    /** 原始 id 串（诊断用：看清没解析前微信给的是什么） */
    fun rawIdsOf(userName: String): String? = userIds[userName]

    /** 标签词典是否已灌过（空 = 还没取证/还没装载） */
    fun ready(): Boolean = index.size() > 0

    // ---------------------------------------------------------------- 装载

    /** 灌标签词典（id → 名）。@return 实际写入条数 */
    fun ingestLabels(src: Map<String, String?>): Int {
        if (src.isEmpty()) return 0
        index.putAll(src)
        LogX.i("$TAG ingest labels n=${src.size} total=${index.size()}")
        return src.size
    }

    /** 灌「某人身上的标签 id 串」。空串表示该联系人没有标签（要记下来，跟「没扫到」区分） */
    fun ingestUserLabels(userName: String, rawIds: String) {
        if (userName.isBlank()) return
        userIds[userName] = rawIds
    }

    fun clear() {
        index.clear()
        synchronized(userIds) { userIds.clear() }
    }

    fun stat(): String =
        "$TAG stat labels=${index.size()} users=${synchronized(userIds) { userIds.size }} " +
            "ready=${ready()}"
}
