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
     *
     * ## 为什么两侧都要边界断言（R35-6 单测挖出来的真 bug）
     *
     * 原来只有 `(-?\d{15,20})`，`find()` 既不要求左边界也不要求右边界，三种错法：
     *
     * | 输入 | 原来抓到 | 应为 |
     * |---|---|---|
     * | `snsID='123456789012345678901'`（21 位） | 前 20 位 | `null` |
     * | `snsID='1504316024693044480a'` | 前 19 位 | `null` |
     * | `Foo(oldsnsID='111…', snsID='1504…')` | `oldsnsID` 那个 | 后面那个 |
     *
     * 前两种会得到一个**看着合法、其实是错的** id ⇒ **打错标签**，比打不上糟得多
     * （正是本文件 `isTimeLike` / `snsIdOf` 反复写要防的形态）。第三种是隐含假设没锁住：
     * 8.0.48 的 `toString()` 里没有 `…snsID` 这类字段，但版本一漂就可能踩。
     *
     * ⇒ `(?<![\w])` 挡左侧（`oldsnsID` 的 `d` 是 `\w`），`(?![\w])` 挡右侧
     * （既挡"后面还有数字"，也挡"数字后面接字母"）。用 `\w` 而不是 `\d`：
     * 21 位那种要一路回溯到 15 位全都失败 ⇒ 整体不匹配，而不是"退而求其次取前 15 位"。
     */
    private val ID_RE = Regex("""(?<![\w])snsID\s*=\s*'?(-?\d{15,20})(?![\w])""")

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

    /**
     * 相对时间：`44分钟前` / `刚刚` / `3小时前` / `2天前` / `昨天` / `前天`。
     *
     * ⚠️ **整串**匹配（`^…$`）：只「包含」相对时间词就放行的话，正文
     * `昨天我去看了…` / `刚刚到家` 会跟着命中 ⇒ 灰标打到**正文**上。
     */
    private val REL_RE = Regex("""^(?:刚刚|\d+\s*(?:秒|分钟|分|小时|天|周|个月)前|昨天|前天)$""")

    /** 绝对时间（中文）：`10月29日` / `2026年10月29日` —— 同样要求**整串**匹配 */
    private val DATE_CN_RE = Regex("""^(?:\d{4}年)?\d{1,2}\s*月\s*\d{1,2}\s*日$""")

    /** 绝对时间（数字）：`2026-10-29` / `2026/10/29` —— 同样要求**整串**匹配 */
    private val DATE_NUM_RE = Regex("""^\d{4}[-/]\d{1,2}[-/]\d{1,2}$""")

    /**
     * 纯「时分」形态：视频时长徽章 `00:15` / `1:23`、直播倒计时 `03:20`。
     *
     * ⛔ 它跟「今天发的动态显示 `HH:MM`」在字面上**完全无法区分** ⇒ 一律否掉。
     * 取舍：漏掉「今天」那几条（顶多不打标）好过把灰标打到视频时长徽章上
     * （**打错位置**，是最危险的失效形态）。
     */
    private val DURATION_RE = Regex("""^\d{1,2}:\d{2}$""")

    /** ④ 级的长度闸门：朋友圈时间行不会比这更长，超了必是正文/昵称这类自由文本 */
    private const val TIME_LIKE_MAX = 20

    /**
     * 这个文案长得像不像「发布时间」。
     *
     * 这是定位时间 TextView 的**第四级兜底**（前三级是 `getPublishTimeText()`、
     * [timeHintOf] 的期望值精确匹配，以及已缓存的 `tvId`）。
     *
     * ## 为什么现在要求「整串匹配」而不是「包含即可」
     * 老判据是「长度 ≤ 20 且**包含**相对时间/日期样式」。`walkForText` 是先序 DFS，
     * 返回**第一个**匹配的 TextView ⇒ 视频动态的时长徽章（`00:15`）在布局里往往比时间行更靠前，
     * 而 `\d{1,2}:\d{2}` 这条最松的样式恰好能吃下它。当前不出事只是因为第二级
     * （`getPublishTimeText()`）在真机上 `depth=0` 就命中、④ 根本不跑；**一旦那个方法名
     * 被 R8 改掉**（本仓库已经记过一次 `lh3/x1 → xh3/o2` 的漂移），④ 立刻接棒，
     * 失效形态恰好是「标记打错位置」。⇒ 收紧成整串匹配 + 显式否掉纯时长形态。
     *
     * 判据（对着这四条写，别改回去）：
     * | 输入 | 结果 | 理由 |
     * |---|---|---|
     * | `00:15` / `1:23` | **false** | 视频时长徽章（与 `HH:MM` 无法区分，一律否） |
     * | `:30` / 以冒号开头 | **false** | 残串/分隔符，不是时间行 |
     * | `昨天` / `3小时前` | true | 整串命中相对时间 |
     * | `10月28日` / `2026年10月28日` | true | 整串命中日期 |
     * | `昨天我去看了…` / `刚刚到家` | **false** | 正文，只是「包含」时间词 |
     */
    fun isTimeLike(s: String?): Boolean {
        val t = s?.trim() ?: return false
        if (t.isEmpty() || t.length > TIME_LIKE_MAX) return false
        if (t[0] == ':') return false
        if (DURATION_RE.matches(t)) return false
        return REL_RE.matches(t) || DATE_CN_RE.matches(t) || DATE_NUM_RE.matches(t)
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
