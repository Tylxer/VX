package com.lx.wxgroup.feature

/**
 * 「已删动态」标记的**纯判据**（R30 §6.1），与 Xposed / 微信类完全隔离。
 *
 * ## 为什么单独一个文件
 * 与 [SnsTimelineMergeLogic] 同一套理由：主实现里挂着 `XC_MethodHook` 字段，
 * JVM 单测一加载那个 object 就会因缺 Xposed 运行时而**整个测试进程崩掉**
 * （exit 1，不是断言失败）。判据必须能在纯 JVM 下跑 ⇒ 拆出来。
 *
 * ## ⛔ 唯一会出真 bug 的地方 = 幂等
 * 时间行是 RecyclerView 里**复用**的 `TextView`。追加标记可能被重复执行：
 * 1. **hook 重入** —— 我们自己 `setText` 会再次触发挂在 `setText` 上的钩子；
 * 2. **复用残留** —— 若实现成「只在命中时追加」，滚过之后普通动态会带着上一条的标记。
 *
 * 两种都会让界面出现 `[删除已拦截] [删除已拦截]` 或「普通动态被标成已删」，
 * 而且**只在滚动后才出现**，真机上极难复现 ⇒ 必须在 [appendTag] 里靠幂等挡死。
 */
object SnsDeletedTagLogic {

    /**
     * 标记文案。用户 2026-10-27 拍板：**灰色小字**，与微信自己的时间戳同色同字号，
     * 直接拼在时间文案后面（不额外加 View）。
     */
    const val MARK = "[删除已拦截]"

    /**
     * 台账候选 → 可打标记的 snsId 集合。
     *
     * - 过滤 `snsId = 0`：无效项（`oh3/p.isInValid()` 判 die），本就不该出现在界面上；
     * - 去重：台账 `sns_visible_src` 里同一 snsId 可能有多行。
     *
     * @param ids 台账候选（`GroupDb.listSrcFixedIds()` 的返回，已按 `last_seen DESC` 排序）
     */
    fun taggedIds(ids: List<Long>): Set<Long> {
        val out = HashSet<Long>(ids.size)
        for (id in ids) {
            if (id == 0L) continue
            out.add(id)
        }
        return out
    }

    /**
     * 这条动态要不要打标记。
     *
     * ⛔ `snsId = 0` 恒 false —— 与 [SnsTimelineMergeLogic.pickMissing] 保持同一口径，
     * 避免「注入了无效项 + 还给它打了标记」这种双重错误。
     */
    fun isTagged(snsId: Long, tagged: Set<Long>): Boolean =
        snsId != 0L && snsId in tagged

    /**
     * 在时间文案后面追加标记。
     *
     * | 输入 | 输出 |
     * |---|---|
     * | `null` / `""` / 全空白 | `[删除已拦截]`（**不产生前导空格**） |
     * | `"10分钟前"` | `"10分钟前 [删除已拦截]"` |
     * | `"昨天   "`（尾部空格） | `"昨天 [删除已拦截]"`（不出现双空格） |
     * | `"10分钟前 [删除已拦截]"` | **原样返回**（幂等） |
     *
     * ⚠️ 返回 `String` 而不是原地改 `Spannable`：时间戳本身不带 span，
     * 转成 String 不会丢样式；而保留原 `CharSequence` 反而有被上层复用改写的风险。
     */
    fun appendTag(original: CharSequence?): String {
        val s = original?.toString()?.trimEnd() ?: ""
        if (s.isEmpty()) return MARK
        // 幂等闸：已含标记就原样返回。挡 hook 重入与 ViewHolder 复用后的重复追加
        if (s.contains(MARK)) return s
        return "$s $MARK"
    }
}
