package com.lx.wxgroup.feature

/**
 * 需求 6.6「强制转发」的**纯逻辑层** —— 零 Android 依赖，可跑 JVM 单测。
 *
 * 放进这里的东西遵循一个原则：**错了要上一轮真机才能发现的判据**，
 * 必须能在没连手机的时候被锁住。UI / 反射 / hook 全部留在 [ForceForward]。
 *
 * 两块内容：
 * 1. **菜单归类**：聊天页（长按消息）菜单 vs 会话列表菜单。**两者不是同一套**，
 *    判错了会把「转发」加错地方（已经多次踩坑的点）。
 * 2. **转发决策**：覆盖类型判定、目标去重展开、混合降级的策略选择。
 *
 * ⚠️ 关于 msgType 常量：微信的 `message.type` 是跨版本稳定的**惯例值**
 *    （不是混淆项，微信自己也要靠它区分消息种类），但本模块没有逐个在
 *    8.0.48 上对过账，所以按置信度分组标注。真核对一次即可永久锁定，
 *    核对走 `probe("menu")` + 日志里的 `FORWARD snap ...` 行。
 */
object ForwardLogic {

    // ------------------------------------------------------------------ 消息种类

    /** 需求点名要覆盖的 8 类 + 兜底的 UNKNOWN */
    enum class Kind {
        TEXT, IMAGE, VIDEO, VOICE, FILE, CARD, LOCATION, LINK, UNKNOWN,
    }

    // 高置信：文本/图片/语音/视频这四条几乎没变过版本
    const val MSG_TYPE_TEXT = 1
    const val MSG_TYPE_IMAGE = 3
    const val MSG_TYPE_VOICE = 34
    const val MSG_TYPE_VIDEO = 43

    // 中置信：名片/定位/特殊卡片（appmsg）
    const val MSG_TYPE_CARD = 42
    const val MSG_TYPE_LOCATION = 48
    const val MSG_TYPE_APP = 49

    /** 系统提示（含撤回提示，参数里带 0x10000000 标记位，见 AntiRevoke 的类注释） */
    const val MSG_TYPE_SYSTEM = 10000

    /** appmsg 子类型，取自 XML 里的 `<type>` */
    const val APP_TYPE_VIDEO = 4
    const val APP_TYPE_URL = 5
    const val APP_TYPE_FILE = 6
    const val APP_TYPE_EMOJI = 8
    /** 合并转发的聊天记录 —— 本身就是别人的转发堆出来的，降级时能救多少是多少 */
    const val APP_TYPE_CHAT_HISTORY = 19

    /** 需求点名覆盖的 8 类 */
    val COVERED: Set<Kind> =
        Kind.entries.toSet() - Kind.UNKNOWN

    fun isCovered(kind: Kind): Boolean = kind in COVERED

    fun labelOf(kind: Kind): String = when (kind) {
        Kind.TEXT -> "文本"
        Kind.IMAGE -> "图片"
        Kind.VIDEO -> "视频"
        Kind.VOICE -> "语音"
        Kind.FILE -> "文件"
        Kind.CARD -> "名片"
        Kind.LOCATION -> "位置"
        Kind.LINK -> "链接"
        Kind.UNKNOWN -> "未知"
    }

    /**
     * 判定消息种类。
     *
     * @param msgType 微信 `message.type` 原始值
     * @param appType msgType=49 时的 appmsg 子类型（用 [appMsgTypeOf] 从 XML 拿）
     */
    fun kindOf(msgType: Int, appType: Int = 0): Kind = when (msgType) {
        MSG_TYPE_TEXT -> Kind.TEXT
        MSG_TYPE_IMAGE -> Kind.IMAGE
        MSG_TYPE_VOICE -> Kind.VOICE
        MSG_TYPE_VIDEO -> Kind.VIDEO
        MSG_TYPE_CARD -> Kind.CARD
        MSG_TYPE_LOCATION -> Kind.LOCATION
        MSG_TYPE_APP -> when (appType) {
            APP_TYPE_FILE -> Kind.FILE
            APP_TYPE_URL -> Kind.LINK
            APP_TYPE_VIDEO -> Kind.VIDEO
            APP_TYPE_EMOJI -> Kind.IMAGE
            APP_TYPE_CHAT_HISTORY -> Kind.TEXT
            else -> Kind.UNKNOWN
        }
        else -> Kind.UNKNOWN
    }

    /**
     * 从 appmsg XML 里抠 `<type>`。
     *
     * ⚠️ 刻意**不用 XmlPullParser**（那是 Android 类，这里要保持零依赖）：
     * 微信这段 XML 是它自己拼的，`<type>` 只出现一次且紧贴节点，正则足够，
     * 而且 8.0.48 上见过 `<type>6</type>` 带空格、`<appmsg>` 前面有 XML 头两种写法。
     */
    private val APP_TYPE_RE = Regex("""<\s*type\s*>\s*(-?\d+)\s*<\s*/\s*type\s*>""")

    fun appMsgTypeOf(xml: String?): Int {
        if (xml.isNullOrBlank()) return 0
        val v = APP_TYPE_RE.find(xml)?.groupValues?.get(1) ?: return 0
        return v.toIntOrNull() ?: 0
    }

    // ------------------------------------------------------------------ 菜单归类

    /**
     * 聊天页**长按消息**菜单里必现的中文项。
     *
     * ⚠️ 全部走**严格相等**（不是 contains）：会话列表菜单里有「删除该聊天」「不显示该聊天」，
     * 用 contains 匹配「删除」会把会话列表菜单误认成消息菜单（这是最贵的一次误判：
     * 「转发」会挂到会话列表上，点了根本不知道要转什么）。
     */
    val CHAT_MENU_WORDS: Set<String> = setOf(
        "复制", "转发", "收藏", "删除", "撤回", "引用", "多选", "提醒",
        "稍后处理", "投诉", "删除消息", "转发到朋友", "发送给朋友",
        "查找聊天记录", "搜一搜", "翻译", "保存图片", "添加表情",
    )

    /**
     * 会话列表长按菜单（[ConvMenuInject] 已真机取证到的那 4 项）。
     * 命中它 ≠ 注入，只是给日志/探针一个「这次没动是因为这是会话菜单」的说法。
     */
    val CONV_MENU_WORDS: Set<String> = setOf(
        "标为未读", "标记未读", "不显示该聊天", "删除该聊天", "置顶该聊天", "取消置顶",
    )

    enum class MenuKind { CHAT_MSG, CONV_LIST, NONE }

    /**
     * 判别当前菜单窗口是「聊天页长按消息」还是「会话列表长按」。
     *
     * 规则（保持可预测）：
     * 1. 会话词命中 ≥2 且**不少于**聊天词命中 → 会话列表（明确的那套优先，避免歧义滑动）；
     * 2. 聊天词命中 ≥[minHits] → 聊天页消息菜单；
     * 3. 都不是 → NONE（静默放弃，绝不误伤）。
     */
    fun classifyMenu(items: List<String>, minHits: Int = 2): MenuKind {
        val conv = items.count { it in CONV_MENU_WORDS }
        val chat = items.count { it in CHAT_MENU_WORDS }
        if (conv >= minHits && conv >= chat) return MenuKind.CONV_LIST
        if (chat >= minHits) return MenuKind.CHAT_MSG
        return MenuKind.NONE
    }

    // ------------------------------------------------------------------ 目标去重

    /** 模块分组节点的 id 前缀（选的是分组，发送前要展开成人） */
    const val PREFIX_GROUP = "g:"

    /** 微信标签节点的 id 前缀 */
    const val PREFIX_LABEL = "t:"

    /** 分组 id → 成员 wxid；标签名 → 成员 wxid */
    data class Buckets(
        val groups: Map<String, List<String>> = emptyMap(),
        val labels: Map<String, List<String>> = emptyMap(),
    )

    /**
     * 把选择器选出的 id 展开成**去重后**的目标 wxid 列表（按选择顺序，保持顺序稳定）。
     *
     * 同一人被多个分组/标签同时选中 → 只发一次（需求明确要求）。
     *
     * @param picked   选择器返回的 id，可能是裸 wxid / `g:<id>` / `t:<name>`
     * @param buckets  分组与标签的成员表
     * @param exclude  排除集：一般是**当前会话自己**（转发回原会话没意义）
     */
    fun resolveTargets(
        picked: List<String>,
        buckets: Buckets = Buckets(),
        exclude: Set<String> = emptySet(),
    ): List<String> {
        val out = LinkedHashSet<String>()
        val dropped = LinkedHashSet<String>()
        for (raw in picked) {
            val id = raw.trim()
            if (id.isBlank()) continue
            when {
                id.startsWith(PREFIX_GROUP) -> {
                    val wxids = buckets.groups[id.removePrefix(PREFIX_GROUP)] ?: emptyList()
                    for (w in wxids) add(out, dropped, w, exclude)
                }
                id.startsWith(PREFIX_LABEL) -> {
                    val wxids = buckets.labels[id.removePrefix(PREFIX_LABEL)] ?: emptyList()
                    for (w in wxids) add(out, dropped, w, exclude)
                }
                else -> add(out, dropped, id, exclude)
            }
        }
        dropped.size // 空的分组/标签自然被吞掉，不需要额外分支
        return out.toList()
    }

    private fun add(out: LinkedHashSet<String>, dropped: LinkedHashSet<String>, wxid: String, exclude: Set<String>) {
        val w = wxid.trim()
        if (w.isBlank() || w in exclude) {
            if (w.isNotBlank()) dropped.add(w)
            return
        }
        out.add(w)
    }

    // ------------------------------------------------------------------ 转发决策

    /** 一次长按取到的消息快照。取不到字段时全是默认值 —— 由 [planFor] 决定是否放弃 */
    data class Snapshot(
        val kind: Kind = Kind.UNKNOWN,
        val text: String = "",
        /** 图片/视频/语音/文件的本地路径（微信自己的文件路径），降级重发要用 */
        val localPath: String = "",
        val fileExists: Boolean = false,
        /** 消息来的那个会话（转发回它自己没意义） */
        val sourceChat: String = "",
        /** 原生转发链路要用到的宿主对象（可能是任意类型，只在 hook 侧传递，不参与比较） */
        val raw: Any? = null,
    )

    enum class NativeState {
        /** 锚点未取证，直接进降级 */
        UNKNOWN,
        /** 锚点已定位且可用：先试原生（保留「转发」标记） */
        AVAILABLE,
        /** 原生链路这一次被拒 / 调不通：落到内容重发 */
        REJECTED,
    }

    enum class Strategy { NATIVE, RESEND_TEXT, RESEND_FILE, SKIP }

    data class Plan(val strategy: Strategy, val reason: String = "")

    /** 降级成功后的用户提示（文案写在逻辑层，便于单测锁住） */
    const val TOAST_DEGRADED = "已作为普通消息发送，无转发标记"

    /**
     * 给一条消息挑策略：能走原生就走原生，被拒才降级。
     *
     * 降级的可用性与消息种类无关，只看**手上有没有可重发的原料**：
     * - 有文本（文本消息本身的 content，或链接/名片的标题）→ 重发文本；
     * - 有本地文件（图片/视频/语音/文件）→ 重发文件；
     * - 都没有 → SKIP（静默放弃，比发出去一个空消息好）。
     */
    fun planFor(snap: Snapshot, nativeState: NativeState): Plan {
        if (!isCovered(snap.kind)) return Plan(Strategy.SKIP, "不支持的消息类型：${labelOf(snap.kind)}")
        if (nativeState == NativeState.AVAILABLE) return Plan(Strategy.NATIVE)
        val hasText = snap.text.isNotBlank()
        val hasFile = snap.localPath.isNotBlank() && snap.fileExists
        if (hasText) return Plan(Strategy.RESEND_TEXT, "原生转发不可用，降级重发文本")
        if (hasFile) {
            // 有文件但种类发不出去（如普通文件/语音）→ 提前 SKIP 并说清原因，
            // 否则会走到 resendFile 里逐目标失败，toast 只剩「失败 N」不说为什么。
            if (!canResendFile(snap.kind)) {
                return Plan(
                    Strategy.SKIP,
                    "原生转发不可用；${labelOf(snap.kind)}的本地文件暂无发送链路"
                )
            }
            return Plan(Strategy.RESEND_FILE, "原生转发不可用，降级重发文件")
        }
        return Plan(Strategy.SKIP, "原生转发不可用且取不到可重发的内容")
    }

    /**
     * 哪些消息种类**本地文件**能发出去（降级重发的原料判据）。
     *
     * ## 为什么要单独一个纯函数
     *
     * 这是**只能在真机上才发现错**的那类判据（发不出去 = 静默失败），所以必须能在
     * JVM 单测里锁住。`ForceForward.resendFile` 只是按这里的结论去调 sender。
     *
     * ## 为什么只有 IMAGE（视频曾经判 true，已收回）
     *
     * 2026-10-20 静态取证（`docs/取证结论.md`「K8-file」段）最初以为
     * IMAGE（`qf4/o4.O8`）与 VIDEO（`o4.c9`）都能走这条 builder 链。
     * 复核时逐条读 `t81/g` 发现**视频走的是另一条路**：
     *
     * - `r1.a(o1.VIDEO)` 造出的是普通 `q1`，`q1.a()` 对 VIDEO 分支调 `t81/g.i(q1)`；
     * - `t81/g.i` **第一句**就是 `instance-of q1, com/tencent/mm/modelimage/o3`
     *   （`8bbca6`），不是 `o3` 子类就直接 `return-object v0`（`8bbd72`），
     *   拿到的 `k1.a` 恒为 null；
     * - 于是 `l1.a()` 里 `Objects.requireNonNull` 抛 NPE（`71039c`），
     *   被 `FileSender.send` 最外层 `runCatching` 吞掉 ⇒ **恒 false，只留一行 NPE**。
     *
     * 正解是 `modelimage/o3.j()` 工厂 + `o3.E/F/K/H` 字段 + `q1.h=20`，
     * 这套**尚未取证完整**。放它进来就是「判 true 但发不出去」的静默失败，
     * 比直接判 false 差得多（用户会以为发出去了）。⇒ 先判 false，等独立取证再放宽。
     *
     * 普通文件（appmsg type=6）与语音（type=34）同样没有已证实的非 UI 链路，
     * `hl0/o1` 的 APPMSG 常量在 classes7 全文零引用。
     *
     * ⚠️ **不许为了「都支持」把这里放宽** —— 放宽 = 对着未证实的反射调用赌微信不崩。
     * 要支持视频，先补 `o3.j()` 那条链的取证；要支持普通文件，先补
     * `pluginsdk/model/app/u0` + `WXFileObject` 的取证。
     */
    fun canResendFile(kind: Kind): Boolean = when (kind) {
        Kind.IMAGE -> true
        Kind.TEXT, Kind.VIDEO, Kind.VOICE, Kind.FILE, Kind.CARD, Kind.LOCATION, Kind.LINK,
        Kind.UNKNOWN -> false
    }

    /** 汇总 toast（逻辑层定文案），防止 UI 侧各自造句子 */
    fun summary(sent: Int, degraded: Int, skipped: Int, failed: Int): String {
        val sb = StringBuilder("已发送 $sent 个目标")
        if (degraded > 0) sb.append(" · 降级 $degraded")
        if (skipped > 0) sb.append(" · 跳过 $skipped")
        if (failed > 0) sb.append(" · 失败 $failed")
        return sb.toString()
    }
}
