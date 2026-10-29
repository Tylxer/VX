package com.lx.wxgroup.feature

/**
 * 「已删动态」标记的**纯判据**（R30 §6.1 / R35 重挂载），与 Xposed / 微信类完全隔离。
 *
 * ## 为什么单独一个文件
 * 与 [SnsTagLogic] 同一套理由：主实现里挂着 `XC_MethodHook` 字段，
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
     * `ImproveInfo(name=…, snsID='15033729556253184615', …)` 里的 id。
     *
     * ⚠️ 这个 `toString()` 是**功能性格式**（R35 dex 取证：字面量 `ImproveInfo(name=`
     * 与 `, snsID='` 都还在 `classes3.dex` 的字符串池里），比猜混淆字段名稳得多
     * —— 字段 `snsID` 本身**已被 R8 改名**（全 dex 扫不到任何名为 `snsID` 的字段），
     * 但 toString 里硬编码的属性名不会跟着改。
     * ⚠️ `snsID` 允许负号 —— 有符号写法也要能吃下。
     */
    private val ID_RE = Regex("""snsID\s*=\s*'?(-?\d{15,20})""")

    /**
     * 台账候选 → 可打标记的 snsId 集合。
     *
     * - 过滤 `snsId = 0`：无效项（`oh3/p.isInValid()` 判 die），本就不该出现在界面上；
     * - 去重：台账 `sns_visible_src` 里同一 snsId 可能有多行。
     *
     * @param ids 已删判据候选。**必须是 `GroupDb.snsTagIds()`**（只含物理删除归档 `T_SNS`）；
     *   ⛔ 曾经传 `GroupDb.listSrcFixedIds()`（补位台账），那是「补过位的可见动态」，
     *   会把正常动态全部打上 `[删除已拦截]` —— R31h 真实事故。
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
     * ⛔ `snsId = 0` 恒 false —— 与 [SnsTagLogic] 保持同一口径，
     * 避免「注入了无效项 + 还给它打了标记」这种双重错误。
     */
    fun isTagged(snsId: Long, tagged: Set<Long>): Boolean =
        snsId != 0L && snsId in tagged

    /**
     * 日志用：把任意对象转字符串并截断。
     *
     * 一次性全量 dump 会把几十个字段的值拼进一行，不截断会刷爆日志（LogX 是主线程同步写盘）。
     */
    fun brief(s: String?, max: Int = 80): String {
        val t = s ?: "null"
        return if (t.length <= max) t else t.substring(0, max) + "..."
    }

    /**
     * 从任意对象的 `toString()` 里抓 snsId。
     *
     * R35 新挂载点（候选 A）拿到的 holder 字段 `E` 就是数据对象本体，
     * 直接 toString 即可 —— 不需要「pos ↔ getData() 下标」对账，也就不会错位打标。
     *
     * @return 有符号 Long（与 SQLite/归档里的位模式一致）；解析不出返回 null。
     */
    fun snsIdOf(obj: Any?): Long? {
        if (obj == null) return null
        val s = runCatching { obj.toString() }.getOrNull() ?: return null
        val raw = ID_RE.find(s)?.groupValues?.get(1) ?: return null
        return parseSnsId(raw)
    }

    /**
     * 形状校验：必须是**纯数字**（允许负号）。这是最后一道防线 —— 取错字段时几乎
     * 不可能同时满足这个形状，「打错标签」（比不打标签糟糕得多）就基本不会发生。
     */
    fun parseSnsId(raw: String): Long? {
        if (raw.isEmpty() || raw.length > 20) return null
        var i = 0
        if (raw[0] == '-') {
            i = 1
            if (raw.length == 1) return null
        }
        while (i < raw.length) {
            val ch = raw[i]
            if (ch < '0' || ch > '9') return null
            i++
        }
        return runCatching {
            if (raw[0] == '-') raw.toLong() else java.lang.Long.parseUnsignedLong(raw)
        }.getOrNull()
    }

    /**
     * `ImproveInfo(name=…, snsID='…', ad:false, time:44分钟前 rowid=…)` 里的**发布时间原文**。
     *
     * R35-1c dex 事实：`Llh3/x1;->d()`（realTimeUpdate）体内就是
     * `getPublishTimeText().setText(info.J0())`，而 `oh3/p.toString()` 里 `time:` 后面
     * 拼的正是 `J0()` ⇒ **屏幕上时间行当时的原文 == 这里取到的串**。
     *
     * 用途：在 itemView 子树里**按内容**认出时间 TextView（比猜混淆字段名/资源 id 稳）。
     * ⚠️ 只是「定位用的提示」，会随分钟数漂移；命中一次之后实现会记住资源 id，不再依赖它。
     */
    private val TIME_RE = Regex("""time:(\S+)""")

    /**
     * 从数据对象的 `toString()` 里取发布时间原文。取不到返回 `""`（调用方当「无提示」处理）。
     */
    fun timeHintOf(obj: Any?): String {
        if (obj == null) return ""
        val s = runCatching { obj.toString() }.getOrNull() ?: return ""
        return TIME_RE.find(s)?.groupValues?.get(1)?.trim().orEmpty()
    }

    /** 相对时间：`44分钟前` / `刚刚` / `3小时前` / `2天前` / `昨天` / `前天` */
    private val REL_RE = Regex("""(刚刚|\d+\s*(秒|分钟|分|小时|天|周|个月)前|昨天|前天)""")

    /** 绝对时间：`10月29日` / `2026-10-29` / `12:30` */
    private val DATE_RE = Regex("""(\d{1,2}\s*月\s*\d{1,2}\s*日|\d{4}[-/]\d{1,2}[-/]\d{1,2}|\d{1,2}:\d{2})""")

    /**
     * 这个文案长得像不像「发布时间」。
     *
     * 这是定位时间 TextView 的**第三级兜底**（前两级是 `getPublishTimeText()` 与
     * [timeHintOf] 的期望值精确匹配）。刻意收得很紧 —— 长度 > 20 直接否掉，
     * 且必须命中相对时间或日期时间样式；否则很容易把昵称/正文/评论数认成时间行，
     * 那就会把灰标打到错误的控件上（比不打标糟糕得多）。
     */
    fun isTimeLike(s: String?): Boolean {
        val t = s?.trim() ?: return false
        if (t.isEmpty() || t.length > 20) return false
        return REL_RE.containsMatchIn(t) || DATE_RE.containsMatchIn(t)
    }

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
