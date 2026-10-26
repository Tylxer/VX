package com.lx.wxgroup.data

/**
 * 「防撤回·角标」（R21d 方案 1）的**纯判据**（零 Android / 零 Xposed 依赖 → 可跑 JVM 单测）。
 *
 * 角标文案由 [HintTextLogic.badgeText] 出（team-lead 同轮产出，格式已由 HintTextLogicTest 锁死），
 * 本类只管「这条消息到底是不是它」的两个对账判据：
 *
 * 1. [typeOk] —— 类型闸门：`h9.getType()` 与台账 `origType` 低 16 位一致。
 *    低 16 位是为了吞掉微信自己加的 `0x10000000` 高位标志（AntiRevoke 实证：撤回/伪装会写
 *    `0x10000000 or 10000/10002`）。不匹配 = 气泡处于「恢复默认显示」的伪装态，没有原文气泡可贴。
 * 2. [contentOk] —— 文本对账：仅对 `origType == TYPE_TEXT` 用，把台账 `origContent` 折平后取
 *    前 N 字符作 probe，必须在 item 子树的可见 TextView 文本里找到 `contains(probe)`。
 *
 * ⚠️ 本文件**禁止 import 任何 android.* / xposed.* / 本包内带 Android 依赖的类**，
 *    否则 JVM 单测加载不了（同 ContactFilterLogic / SnsTagLogic / HintTextLogic 的约束）。
 */
object HintBadgeLogic {

    /** 文本对账 probe 的字符数：截断的气泡至少会保留这个前缀，太长则气泡截断后对不上 */
    const val PROBE_LEN = 12

    /** 类型闸门掩码：只比低 16 位（吞掉 0x10000000 这类高位标志） */
    private const val TYPE_MASK = 0xFFFF

    /**
     * 「类型拿不到」的哨兵值。[HintBadge.typeOf] 反射失败时返回 null，
     * 调用方降级成这个值再传进来。
     *
     * ⚠️ 必须显式判它：`-1 and 0xFFFF == 0xFFFF`，若台账里恰好有 `orig_type = -1`（脏数据），
     * 两个哨兵会「配上」并贴出角标。类型不明时应该**抑制**，不是找一个能匹配的值。
     */
    const val TYPE_UNKNOWN = -1

    /**
     * 类型闸门。
     *
     * - 未伪装（正常显示原文）：`h9.getType()` 低 16 位 == `origType` 低 16 位。
     * - 伪装态（「恢复默认显示」开启）：`h9.getType()` 低 16 位 == 10000/10002，与 origType 不等。
     * - 任一侧是 [TYPE_UNKNOWN]（或负数）：直接 false。
     *
     * 只有第一种才有「原文气泡」可以贴角标，其余跳过（宁可 missed 也不在「撤回了一条消息」
     * 气泡上贴「已阻止撤回 · 原文」）。
     */
    fun typeOk(h9Type: Int, origType: Int): Boolean {
        if (h9Type < 0 || origType < 0) return false
        return (h9Type and TYPE_MASK) == (origType and TYPE_MASK)
    }

    /**
     * 文本对账。**严格**：拿不到内容 / 内容太短 / 读不到文字 → 一律 false（宁可 missed）。
     *
     * 只对文本消息调用；非文本（图片/语音/视频）没有可对账的可见文字，走 [typeOk] + msgId 唯一性。
     *
     * 折平（换行/制表符/连续空格 → 单空格）后取前 [PROBE_LEN] 字符作 probe，
     * 任一 item 文字 `contains(probe)` 即认为这条 item 确实在显示这条消息。
     *
     * ⚠️ **emoji 截断**：`take(12)` 按 UTF-16 码元切，可能正好切断一个代理对
     * （emoji 占 2 个码元）。半截代理与任何字符串都匹配不上 ⇒ 假阴性 ⇒ 角标漏画。
     * 所以切完要把末尾的孤立高位代理去掉（宁可 probe 短 1 位，也不能让它变废）。
     */
    fun contentOk(origContent: String?, texts: List<String>): Boolean {
        val flat = origContent?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (flat.length < 2) return false
        if (texts.isEmpty()) return false
        val probe = dropLoneHighSurrogate(if (flat.length > PROBE_LEN) flat.take(PROBE_LEN) else flat)
        if (probe.length < 2) return false
        return texts.any { it.length >= 2 && it.contains(probe) }
    }

    /** 末尾若是孤立的高位代理（被 `take` 切断的 emoji 前半），去掉它 */
    private fun dropLoneHighSurrogate(s: String): String {
        if (s.isEmpty()) return s
        val last = s[s.length - 1]
        return if (Character.isHighSurrogate(last)) s.substring(0, s.length - 1) else s
    }

    // ---------------------------------------------------------------- 角标锚点（纯判据）

    /**
     * item 根容器里一个「候选子节点」的最小视图模型。
     *
     * 只带选锚点所需的信息，零 Android 依赖。由 [HintBadge] 从真 View 上投影：
     * `width/height` 是已 layout 的像素尺寸（≤0 表示「还没量完」），`x/y` 是相对**父节点**
     * 左上角的像素坐标，`isOurBadge` 标记我们自己加的角标（tag == MARK），
     * `children` 是它的直接子节点（只投影一层，够判据用）。
     */
    data class BadgeNode(
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int,
        val visible: Boolean,
        val isOurBadge: Boolean,
        val children: List<BadgeNode> = emptyList()
    )

    /**
     * 从 item 根的子节点里挑「消息行」的下标，角标就画在它**下方**。
     *
     * 规则（按优先级，命中即返回，绝不再往下钻）：
     *
     * 1. 跳过 `GONE` 和我们自己加的角标（角标不能把自己当锚点）。
     * 2. **最后一个「高」可见子节点** —— 消息行是 item 的主内容，纵向占大头。
     *    「高」= `height >= 0.5 * maxVisibleHeight`，容忍自研 VM 与外发场景里
     *    「时间戳槽被收起 / 复用成一条细线」等情形。
     * 3. 若没有任何高子节点（数据异常），退回最后一个可见的非角标子节点。
     * 4. 全空才返回 -1（调用方此时不贴角标）。
     *
     * ⚠️ 为什么是「最后一个高子节点」而不是「childCount-1」：真机实测 item 根是
     * `RelativeLayout`（`com.tencent.mm.ui.chatting.viewitems.xi`），消息行 `#bqh` 恰好是
     * 最后一个子节点，但「最后一个」这个位置是**未承诺的**——RecyclerView 复用 + 微信内部
     * 增删节点都可能改变它。用「最后一个**高**子节点」既覆盖当前结构，又不赌位置。
     */
    fun selectMessageRow(nodes: List<BadgeNode>): Int {
        var maxH = 0
        for (n in nodes) {
            if (n.visible && !n.isOurBadge && n.height > maxH) maxH = n.height
        }
        if (maxH <= 0) return -1
        val threshold = maxH / 2
        var lastHigh = -1
        var lastVisible = -1
        for (i in nodes.indices) {
            val n = nodes[i]
            if (!n.visible || n.isOurBadge) continue
            lastVisible = i
            if (n.height >= threshold) lastHigh = i
        }
        return if (lastHigh >= 0) lastHigh else lastVisible
    }

    /**
     * 角标相对 item 根的水平对齐：自己发的消息靠右（`alignParentRight`），对方发的靠左。
     *
     * 判断依据（从 View 结构推，不赌资源 id、不赌头像节点数）：微信 item 根 `xi` 的消息行
     * `#bqh` 内部是「头像 + 气泡」横排，**头像永远贴外沿**——对方消息头像在左、气泡靠中偏右；
     * 自己发的头像在右、气泡靠中偏左。因此头像（离消息行水平中线**最远**的那个子节点）在哪
     * 半边，角标就跟到哪半边。拿不到头像（无子节点）时按对方消息（靠左）保守处理。
     */
    fun alignToEnd(nodes: List<BadgeNode>): Boolean {
        val rowIdx = selectMessageRow(nodes)
        if (rowIdx < 0) return false
        val row = nodes[rowIdx]
        val rowCx = row.width / 2
        var avatar: BadgeNode? = null
        var avatarDist = -1
        for (c in row.children) {
            if (!c.visible || c.isOurBadge) continue
            if (c.width <= 0 || c.height <= 0) continue
            val d = Math.abs(c.x + c.width / 2 - rowCx)
            if (d > avatarDist) {
                avatarDist = d
                avatar = c
            }
        }
        val a = avatar ?: return false
        return a.x + a.width / 2 > rowCx
    }
}
