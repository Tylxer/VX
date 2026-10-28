package com.lx.wxgroup.feature

/**
 * 名单类配置的**统一展开判据**（零 Android 依赖 → 可直接跑 JVM 单测）。
 *
 * ## 为什么要有这个文件
 *
 * K3 选择器从 R24 起允许选「我的分组」`g:<id>` 和「微信标签」`t:<id>`，
 * 于是所有吃名单的地方都必须能把这两类 id 展开成**具体的人**。
 * 此前只有群发（[MassSendLogic.resolveTargets]）会展开，免打扰名单
 * （`core/NotifyMute.inScope`）与朋友圈导出名单（[MomentsLogic.listHit]）
 * 都是**字面比对** —— 名单里存 `g:3` 时，拿 talker 去比 `g:3` 永远不命中，
 * 表现为**静默失效**，比不显示这些 Tab 更糟。
 *
 * 三条链路各写一份展开逻辑必然漂移（前缀含义、去重口径、空值处理），
 * 所以收敛到这里。⚠️ **群发那条不要改用它**：`MassSendLogic.resolveTargets`
 * 还要记录每个人「被哪些选中项带进来」的来源，本对象只产出 id 集合，
 * 强行合并会丢掉对账能力。两者共享的只有**前缀常量语义**，此处以注释对齐。
 *
 * ## 核心难点：「空」有两种，含义相反
 *
 * | 配置 | 含义 | 判定 |
 * |---|---|---|
 * | `[]`（没配） | **不限制**，对所有会话 / 全部内容生效 | [Resolved.unrestricted] |
 * | `["g:3"]` 但分组 3 没有成员 | **谁都不命中**，等于「限制到一个空集」 | [Resolved.impossible] |
 *
 * ⚠️ 如果不区分这两者，用户把名单设成一个**空分组**，就会从「只对这一个组生效」
 * 悄悄变成「对所有会话生效」—— 免打扰会静音全部通知、朋友圈会导出全部内容，
 * 这是**方向相反**的失效，必须显式建模。
 */
object ListResolver {

    /**
     * 模块分组节点 id 前缀。
     *
     * ⚠️ 与 [MassSendLogic.PREFIX_GROUP] / [ForwardLogic.PREFIX_GROUP] **必须同值**。
     * 不直接引用那两个常量，是为了让本对象保持零依赖（可单测）；
     * 若哪天要改前缀，三处都要改 —— 单测 `prefix_matches_senders` 会拦住漂移。
     */
    const val PREFIX_GROUP = "g:"

    /** 微信标签节点 id 前缀；同 [MassSendLogic.PREFIX_LABEL]，同值理由同上 */
    const val PREFIX_LABEL = "t:"

    /**
     * 一次解析的结果。用「原始配置 + 展开后的 id 集合」两个字段显式承载三态语义，
     * 而不是让调用方自己判空 —— 判空是上次出错的根源。
     */
    data class Resolved(
        /** 配置里原样的条目（已 trim、已去空），用于判断「到底配没配」 */
        val raw: List<String> = emptyList(),
        /** 展开后的具体 wxid；顺序按配置顺序稳定输出，已去重 */
        val ids: Set<String> = emptySet(),
    ) {
        /** 配置为空 = 不限制 */
        val unrestricted: Boolean get() = raw.isEmpty()

        /** 配了、但一个 id 都展开不出来 = 谁都不命中（**不是**不限制） */
        val impossible: Boolean get() = raw.isNotEmpty() && ids.isEmpty()

        /** 有没有配了东西但展开失败（诊断用：说明分组被删了 / 标签没同步） */
        fun unresolvedKeys(): List<String> =
            raw.filter { it.startsWith(PREFIX_GROUP) || it.startsWith(PREFIX_LABEL) }
    }

    /**
     * 展开名单：`g:` → 分组成员，`t:` → 标签成员，裸 wxid 原样保留。
     *
     * 与 [MassSendLogic.resolveTargets] 保持同样的宽容度：
     * - 前缀指向的分组 / 标签**查不到**（被删了、标签还没同步）→ 静默吞掉，不报错；
     *   对应关系由 [Resolved.impossible] / [Resolved.unresolvedKeys] 让调用方判断。
     * - 空白条目直接丢弃（`Kv.getList` 已滤一遍，这里再兜一层，防手工写坏的配置）。
     * - 同一人被分组 + 直接选中同时命中 → **只出现一次**。
     *
     * @param groups 分组 id → 成员 wxid；取不到就传空 map（此时 `g:` 条目全被吞）
     * @param labels 标签 id → 成员 wxid；同上
     */
    fun expand(
        picked: Collection<String>,
        groups: Map<String, List<String>> = emptyMap(),
        labels: Map<String, List<String>> = emptyMap(),
    ): Resolved {
        val raw = ArrayList<String>(picked.size)
        val ids = LinkedHashSet<String>()
        for (item in picked) {
            val id = item.trim()
            if (id.isEmpty()) continue
            raw += id
            when {
                id.startsWith(PREFIX_GROUP) ->
                    groups[id.removePrefix(PREFIX_GROUP)].orEmpty().forEach { add(ids, it) }
                id.startsWith(PREFIX_LABEL) ->
                    labels[id.removePrefix(PREFIX_LABEL)].orEmpty().forEach { add(ids, it) }
                // 裸 wxid：直接选的好友 / 群聊 / 公众号，原样进集合
                else -> add(ids, id)
            }
        }
        return Resolved(raw = raw, ids = ids)
    }

    /**
     * 作用域判定 —— 「谁在范围里」。直接复刻 `NotifyMute.inScope` 的既有口径：
     *
     * 1. **排除优先**：命中排除集 → 立刻不在范围内；
     * 2. **允许名单为空 = 对所有会话生效**；
     * 3. 允许名单非空 → 必须在里面。
     *
     * ⚠️ 第 2 条只认「配置为空」，**不认「展开后为空」**。用户把允许名单设成一个
     * 空分组时，正确行为是「谁都不在范围内」而不是「所有人都允许」——
     * 后者会把「只想对某个群生效」变成「对全部会话生效」。
     *
     * ## ⛔ 调用方必读：`talker == null` 时**不能直接信这个返回值**（R34 复核 P2②）
     *
     * 本函数对空 talker 一律 `return false`（宁可漏判，与 NotifyMute 同口径）。
     * 这在**允许名单模式**下是对的；但在**纯排除模式**（`allowRaw` 为空、`excludeRaw` 非空）下
     * 会把语义整个反过来 —— 用户配的是「排除这几个、其余都下」，而「查不到 talker」这个
     * **取数失败**会被当成「命中排除」⇒ 变成「谁都不下」，且日志里只剩 skipScope 在涨。
     *
     * ⇒ **这个判断不能下沉到本函数**（下沉就要改 NotifyMute 的既有口径）。
     *   调用方自己在调 [inScope] 之前分一下模式：
     *   `talker.isNullOrBlank() && allowRaw.isEmpty()` ⇒ **放行**（纯排除模式的语义是默认生效），
     *   并打 warn 表明这是降级；其余情况照旧交给 [inScope]。
     *   参考实现见 `AutoHdImg.fire` 的名单分支。
     */
    fun inScope(
        talker: String?,
        allowRaw: List<String>,
        excludeRaw: List<String>,
        groups: Map<String, List<String>> = emptyMap(),
        labels: Map<String, List<String>> = emptyMap(),
    ): Boolean {
        // talker 拿不到时按不在作用域处理（宁可漏判 —— 与 NotifyMute 的既有注释一致）
        val who = talker?.trim().orEmpty()
        if (who.isEmpty()) return false

        if (who in expand(excludeRaw, groups, labels).ids) return false
        val allow = expand(allowRaw, groups, labels)
        if (allow.unrestricted) return true
        return who in allow.ids
    }

    private fun add(out: MutableSet<String>, wxid: String) {
        val w = wxid.trim()
        if (w.isNotEmpty()) out += w
    }
}
