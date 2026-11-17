package com.lx.wxgroup.feature

/**
 * 分组行 2×2 拼图头像的纯判据（零 Android 依赖 ⇒ 可跑 JVM 单测）。
 *
 * 只负责一件事：**从成员集合里挑出稳定的 4 个 wxid**。
 *
 * ## 为什么需要它（不是直接 `wx.take(4)`）
 * `ConvFilter` 手里的成员集合是 `Set<String>`，迭代顺序取决于底层实现；
 * 直接 `take(4)` 会让「同一分组」在不同 `rebuild()` 之后挑出不同的人，
 * 表现就是**头像每隔几秒换一组脸**。
 *
 * ## 规则
 * 1. 成员 `< 4` ⇒ 返回空列表，调用方回落单头像（不做 2/3 格留白拼图）；
 * 2. 成员 `>= 4` ⇒ 恰好返回 4 个：
 *    - **最后发消息的人排第一**（与分组行第二行「谁说了什么」对应）；
 *    - 其余按 wxid **升序**补齐，跳过已选的，保证同一成员集合永远同一结果。
 */
object AvatarGridLogic {

    /** 拼图固定 2×2 */
    const val CELLS = 4

    /**
     * @param wx 该分组的成员 wxid（`Set`，顺序不可信）
     * @param lastWxid 最新一条消息的发送者；空白 / 不在组内 ⇒ 不参与优先位
     * @return 恰好 [CELLS] 个互不重复的 wxid，或空列表（成员不足）
     */
    fun order(wx: Set<String>, lastWxid: String): List<String> {
        if (wx.size < CELLS) return emptyList()
        val out = ArrayList<String>(CELLS)
        if (lastWxid.isNotBlank() && lastWxid in wx) out.add(lastWxid)
        // sorted() 而不是遍历 Set：排序是「稳定输出」的唯一来源
        for (w in wx.sorted()) {
            if (out.size >= CELLS) break
            if (!out.contains(w)) out.add(w)
        }
        return out
    }
}
