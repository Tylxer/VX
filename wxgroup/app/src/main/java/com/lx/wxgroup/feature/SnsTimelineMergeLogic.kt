package com.lx.wxgroup.feature

/**
 * 方案 A（Response-Merge）的**纯判据**，与 Xposed / 微信类完全隔离。
 *
 * ⚠️ 为什么必须单独一个文件：`SnsTimelineMerge` 里挂着 `XC_MethodHook` 字段，
 * JVM 单测一加载那个 object 就会因为缺 Xposed 运行时而**整个测试进程崩掉**
 * （exit 1，不是断言失败）。判据必须在纯 JVM 里可测 ⇒ 拆出来。
 * 与 `SnsTimelineSrcLogic` / `SnsTimelineSourceFix` 的分层是同一套做法。
 */
object SnsTimelineMergeLogic {

    /**
     * 从台账候选里挑出「这一轮响应里没有的」。
     *
     * @param ledger 台账候选（已按 `last_seen DESC` 排好，最近补过位的在前）
     * @param respIds 响应列表里已有的 snsId
     * @param limit 上限（防御台账变大）
     */
    fun pickMissing(ledger: List<Long>, respIds: Set<Long>, limit: Int): List<Long> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<Long>()
        val seen = HashSet<Long>()
        for (id in ledger) {
            // snsId=0 是无效项（`oh3/p.isInValid()` 判 die），绝不能注入
            if (id == 0L) continue
            if (id in respIds) continue
            // 台账同一 snsId 可能多行；重复注入会让时间线出现重影
            if (!seen.add(id)) continue
            out.add(id)
            if (out.size >= limit) break
        }
        return out
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
