package com.lx.wxgroup.feature

/**
 * 好友个人相册页「也显示被删动态」的**纯判据**（R30 Lane2），与 Xposed / 微信类完全隔离。
 *
 * ## 为什么单独一个文件
 * 与 [SnsTimelineMergeLogic] / [SnsDeletedTagLogic] 同一套理由：主实现里挂着
 * `XC_MethodHook` 字段，JVM 单测一加载那个 object 就会因缺 Xposed 运行时而
 * **整个测试进程崩掉**（exit 1，不是断言失败）。判据必须能在纯 JVM 下跑 ⇒ 拆出来。
 *
 * ## 与时间线版 [SnsTimelineMergeLogic] 的差异
 * - 相册响应类不是 `q54/zb5` 而是 `q54/jc5`，但主列表字段同叫 `f`、同是
 *   `LinkedList<SnsObject>`（dex 取证见 `docs/_r30_lane2_album_page.md` §2），
 *   所以 `pickForAlbum` 与 `pickMissing` 语义完全一致，只换名字。
 * - 相册页**天然只显示 owner 的动态**，注入前必须按 wxid 过滤，否则会把 A 的已删
 *   动态插进 B 的相册（串了非常显眼且难回滚）。[ownerMatches] 就是这道闸门。
 */
object SnsAlbumMergeLogic {

    /**
     * 从台账候选里挑出「这一轮响应里没有的」。
     *
     * 与时间线 [SnsTimelineMergeLogic.pickMissing] 同语义：
     * 排除 `snsId=0`（无效项）、排除响应已有的、去重、限条数。
     *
     * @param ledgerIds 台账候选（相册版来源是 `GroupDb.snsTagIds()` 的并集，
     *   本身是 `Set<Long>`，调用方转成 `List` 传入；顺序不影响最终渲染位，
     *   注入位置由 [insertIndexDesc] 按 CreateTime 独立决定）
     * @param respIds 响应列表里已有的 snsId
     * @param limit 上限（防御台账变大）
     */
    fun pickForAlbum(ledgerIds: List<Long>, respIds: Set<Long>, limit: Int): List<Long> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<Long>()
        val seen = HashSet<Long>()
        for (id in ledgerIds) {
            // snsId=0 是无效项（`oh3/p.isInValid()` 判 die），绝不能注入
            if (id == 0L) continue
            // 已在响应里的不注入，否则相册出现重复条目
            if (id in respIds) continue
            // 台账同一 snsId 可能多行（归档 ∪ src 两条路径的并集）；重复注入会重影
            if (!seen.add(id)) continue
            out.add(id)
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * 注入对象的作者是否就是当前相册的 owner。
     *
     * ⛔ 这是防串号的**核心闸门**：相册页只显示 owner 的动态，注入错了会把
     * A 的已删动态插进 B 的相册。判据必须最严：
     * - **大小写敏感**：wxid 大小写有语义，绝不能用 `equalsIgnoreCase` / `COLLATE NOCASE`；
     * - **两端 trim**：protobuf 字符串可能带杂散空白，不 trim 会把「wxid_abc」误判成
     *   与「 wxid_abc 」不同人而漏注入；
     * - **null / 空串一律 false**：绝不拿空值去匹配空值（那样会把「没有作者」的动态
     *   当成任何人的动态放行）。
     */
    fun ownerMatches(objUsername: String?, targetWxid: String?): Boolean {
        val u = objUsername?.trim()
        val t = targetWxid?.trim()
        if (u.isNullOrEmpty() || t.isNullOrEmpty()) return false
        return u == t
    }

    /**
     * 列表按 `CreateTime` **降序**（新的在前）时，`createTime` 应插入的下标。
     * 找不到比它更旧的就插到末尾；时间相等时插到同值之后（避免把原条目往后挤）。
     */
    fun insertIndexDesc(times: List<Int>, createTime: Int): Int {
        var i = 0
        // 用 `>=`：时间相等时插到同值**之后**，不把已有条目往后挤
        while (i < times.size && times[i] >= createTime) i++
        return i
    }
}
