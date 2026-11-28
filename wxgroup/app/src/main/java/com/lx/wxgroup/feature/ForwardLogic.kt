package com.lx.wxgroup.feature

import com.lx.wxgroup.data.Friend
import com.lx.wxgroup.data.MediaGuardLogic

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

    // ------------------------------------------------------------------ 菜单容器定位

    /**
     * View 树的**纯数据**抽象（零 Android 依赖，可在 JVM 单测里造假树）。
     *
     * [ForceForward] 把真实的 `View/ViewGroup` 转成这棵树：`isContainer` 对应 ViewGroup
     * （可作为注入容器），`text` 取 TextView 文本，`id` 回映射到真实 View（纯函数不解释它）。
     */
    data class MenuNode(
        val text: String? = null,
        val isContainer: Boolean = false,
        val children: List<MenuNode> = emptyList(),
        val id: Any? = null,
    )

    /** [findMenuBoxCandidate] 的结果：[boxId] 是容器节点 id，[sampleId] 是子树里最后一个命中文本节点 id。 */
    data class MenuBoxCandidate(val boxId: Any?, val sampleId: Any?)

    /**
     * 从 View 树（抽象成 [MenuNode]）里找「装菜单项的容器」+「样式样本」，供注入用。
     *
     * 与 [classifyMenu] 用同一套 [CHAT_MENU_WORDS] **严格相等**口径 —— 这是修 R73 的根：
     * 旧实现只数「直接子 TextView」，而 PopupWindow+ListView 的 item 是 `LinearLayout` 包
     * `TextView`，直接子命中恒 0 ⇒ 明明 classifyMenu 判成 CHAT_MSG 却找不到 box。这里改成
     * **递归数子树里所有命中 TextView**，与 classifyMenu 的 collectTexts 口径对齐。
     *
     * 策略：**先比子树命中数（多者胜），命中数相同再比深度（深者胜）**，候选仍需
     * 子树命中 ≥[minHits]（后序遍历，深度只做同分裁决）。两条理由：
     * (a) 真机 8.0.48 的长按菜单是**多行横排网格**（菜单体 > row1(5 项) + 分隔线 +
     *     row2(4 项)），不是 ListView。若按旧的「最深优先」会选中第一行的行内容器
     *     （命中 5、depth 更深），把入口 addView 塞进第一行 ⇒ 视觉出现在菜单顶部
     *     （用户反馈「位置不对」）。命中数优先才能选中覆盖全部 9 项的**菜单体**，入口落到末尾。
     * (b) 命中数相同时仍取最深，是为了不把更浅的 PopupWindow 外壳当 box —— 那会把
     *     入口塞到列表外：ListView 场景里 ListView 与上层父容器子树命中数相同（同一批
     *     item），但 ListView 更深 ⇒ 选 ListView（注入走 addFooterView）；若同分取浅，
     *     外壳胜出就会把入口放到列表之外。
     *
     * 样本取子树 DFS 顺序**最后一个**命中文本节点，保证样式来源是真实菜单项那一行。
     *
     * @return 找到返回 [MenuBoxCandidate]，否则 null。
     */
    fun findMenuBoxCandidate(
        roots: List<MenuNode>,
        words: Set<String> = CHAT_MENU_WORDS,
        minHits: Int = 2,
    ): MenuBoxCandidate? {
        // 子树内命中的文本节点 id（DFS 顺序）
        fun hits(node: MenuNode): List<Any?> {
            val out = ArrayList<Any?>()
            val t = node.text?.trim()
            if (!t.isNullOrEmpty() && t in words) out.add(node.id)
            for (c in node.children) out.addAll(hits(c))
            return out
        }

        var bestBox: Any? = null
        var bestSample: Any? = null
        var bestHits = -1
        var bestDepth = -1

        // 后序：先比命中数、再比深度；只有命中数相同才让更深者覆盖
        fun walk(node: MenuNode, depth: Int) {
            if (!node.isContainer) return
            for (c in node.children) walk(c, depth + 1)
            val hs = hits(node)
            if (hs.size < minHits) return
            if (hs.size > bestHits || (hs.size == bestHits && depth > bestDepth)) {
                bestBox = node.id
                bestSample = hs.last()
                bestHits = hs.size
                bestDepth = depth
            }
        }
        for (r in roots) walk(r, 0)
        return if (bestDepth >= 0) MenuBoxCandidate(bestBox, bestSample) else null
    }

    // ------------------------------------------------------------------ 选人器好友白名单

    /** 转发选人器「好友」段白名单：文件传输助手在微信库里是 UNKNOWN=9，不是真好友，但要能选 */
    const val PICKER_FILE_HELPER_WXID = "filehelper"
    const val PICKER_FILE_HELPER_NAME = "文件传输助手"

    /**
     * 转发选人器「好友」段的数据源判据：真好友 + 白名单里的文件传输助手。
     *
     * 文件传输助手在 rcontact 里 type=UNKNOWN(9)、昵称常为空，`isRealFriend` 恒 false，
     * 旧的「只看 FRIEND」过滤会把它整个漏掉。这里显式放行它，其余规则不变：
     * - `exclude`（一般是当前会话）仍排除；
     * - 其它 UNKNOWN 系统号（如 weixin）、CHATROOM、公众号仍不进好友段；
     * - 保持 [all] 原顺序；去重由调用方/数据库负责，这里不写库、不新增状态。
     */
    fun pickerFriends(all: List<Friend>, exclude: Set<String>): List<Friend> =
        all.filter { f ->
            f.wxid !in exclude && (f.isRealFriend || f.wxid == PICKER_FILE_HELPER_WXID)
        }

    /** 选人器「好友」段显示名：文件传输助手昵称常为空，固定显示中文名，其它行走正常优先级。 */
    fun pickerDisplayName(f: Friend): String =
        if (f.wxid == PICKER_FILE_HELPER_WXID) PICKER_FILE_HELPER_NAME else f.displayName

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

    // ------------------------------------------------------------ 图片路径解析（R73 第二层缺口）
    //
    // 以下两个函数是「URI → 真实大图路径」的**纯逻辑层**：零 Android / 零 IO / 零 Xposed，
    // 可被 JVM 单测锁住。宿主侧 `ForceForward.resolveImagePath` 只负责 listFiles + exists，
    // 把 (文件名, 字节数) 喂进 [pickImageFile]，选择口径全在这里（取证见
    // docs/_r73_image_path_forensics.md §1.1/§4.3/§7.2）。

    /**
     * 把 32 位 md5 映射成微信 `image2` 的分桶片段 `xx/yy`（前 2 位 / 第 3~4 位）。
     *
     * 【真机确定】4 组独立样本逐字吻合（`docs/_r73_image_path_forensics.md` §1.1），
     * 与宿主 `b1.C4/z3` 原生解析语义同源。长度不是 [MediaGuardLogic.MD5_LEN] 一律返回 null，
     * 让调用方告警/放弃 —— 不猜、不截断。
     *
     * 长度常量复用 [MediaGuardLogic.MD5_LEN]（data 包纯 Kotlin、无 Android 依赖，
     * 不破坏 JVM 单测），与 `hexToken` 的取 token 口径保持单一来源。
     */
    fun imageBucket(md5: String): String? =
        if (md5.length == MediaGuardLogic.MD5_LEN)
            md5.substring(0, 2) + "/" + md5.substring(2, 4)
        else null

    /**
     * 无效图片文件的最小字节阈值。
     *
     * R73 第三层落锤（真机）：微信缓存桶里可能残留**无效占位文件**——目标图 `th_<md5>hd⌖`
     * 只有 36 字节（文件名尾部带特殊字符），旧版 tier-2 只按名字匹配、不看大小，把它当 HD
     * 缩略图发给上传器 ⇒ `ok=true`（入队）但 NetScene 上传失败、对端收不到（本机提示发送失败）。
     * 真机有效文件：坏占位 36B / 真缩略图 3~8KB / HD 缩略图 ~16KB / 大图 40~194KB。
     * 阈值取 1KB：远低于最小真缩略图、远高于 36B 坏文件。
     */
    private const val MIN_VALID_IMAGE_BYTES = 1_024L

    /**
     * 从桶目录枚举出的 `(文件名, 字节数)` 列表里挑出**最适合转发**的图片文件名。
     *
     * 优先级（【真机确定】，见 `docs/_r73_image_path_forensics.md` §4.3/§7.2）：
     * 0. 先按 [MIN_VALID_IMAGE_BYTES] 滤掉无效占位文件（36B 坏 `th_<md5>hd`，第三层真机落锤）；
     * 1. 文件名 `contains(md5)` 且**不以 `th_` 开头**且扩展名 ∈ {.jpg,.png,.jpeg,.webp}
     *    → 取字节数最大者（大图 40~194KB，首选，对端拿到清晰图）；
     * 2. 无 1 → 取 `th_<md5>hd` / `th_<md5>hd_tmp.jpg`（HD 缩略图，次选）；
     * 3. 无 2 → 取 `th_<md5>`（缩略图兜底，宿主侧应打 warn 留痕）；
     * 4. 都没有 → null（解析失败，调用方保留原 URI 让 [planFor] 安全 SKIP）。
     *
     * ⛔ 用 `contains(md5)` 而不是精确相等：微信同一张图有 `<md5>.jpg`/`<md5>.png`/
     *    `th_<md5>`/`th_<md5>hd` 等多个文件，都要能认出；但 md5 定长 32 位，`contains`
     *    不会串到同桶别人消息（前 2 位相同、后 30 位不同）的文件上。
     * ⛔ 第 1 档必须排除 `th_` 前缀：否则 `maxByOrNull` 会挑到缩略图 ⇒ 对端收到模糊图
     *    （§4.2 事故）。单测里专门有一个「超大 th_ 文件」用例锁住这个判别力。
     *
     * @param files 桶目录 `listFiles()` 映射出的 (文件名, 字节数)；宿主只负责枚举，选择口径全在这里
     * @param md5   [MediaGuardLogic.hexToken] 取出的 32 位小写 md5
     * @return 相对该桶目录的文件名，全不命中返回 null
     */
    fun pickImageFile(files: List<Pair<String, Long>>, md5: String): String? {
        if (md5.length != MediaGuardLogic.MD5_LEN) return null
        val imageExt = arrayOf(".jpg", ".png", ".jpeg", ".webp")
        // 无效文件（36B 坏占位等）任何一档都不能选：按大小先滤一道，再走优先级。
        val valid = files.filter { (_, bytes) -> bytes >= MIN_VALID_IMAGE_BYTES }

        // 1) 大图：含 md5、非 th_ 前缀、图片扩展名，取字节最大者
        val big = valid
            .filter { (name, _) ->
                !name.startsWith("th_") &&
                    imageExt.any { name.endsWith(it, ignoreCase = true) } &&
                    name.contains(md5)
            }
            .maxByOrNull { it.second }
        if (big != null) return big.first

        // 2) HD 缩略图：th_<md5>hd / th_<md5>hd_tmp.jpg
        val hd = valid.firstOrNull { (name, _) -> name.contains("th_${md5}hd") }
        if (hd != null) return hd.first

        // 3) 缩略图兜底：th_<md5>
        val thumb = valid.firstOrNull { (name, _) ->
            name.startsWith("th_") && name.contains(md5)
        }
        return thumb?.first
    }

    /** 一次长按取到的消息快照。取不到字段时全是默认值 —— 由 [planFor] 决定是否放弃 */
    data class Snapshot(
        val kind: Kind = Kind.UNKNOWN,
        val text: String = "",
        /** 图片/视频/语音/文件的本地路径（微信自己的文件路径），降级重发要用 */
        val localPath: String = "",
        val fileExists: Boolean = false,
        /** 消息来的那个会话（只用于诊断/原生链；2026-11-28 起不再作选人器排除集） */
        val sourceChat: String = "",
        /** 原生转发链路要用到的宿主对象（可能是任意类型，只在 hook 侧传递，不参与比较） */
        val raw: Any? = null,
        /**
         * 源消息 msgId（`h9.getMsgId()`，① G2 §4）。**0 = 没拿到**
         * —— 原生转发靠它填 `hl0/m.b`(forwardMsgId)，拿不到就退回微信自己的默认值 0。
         */
        val msgId: Long = 0L,
        /**
         * 源消息服务端 id（`h9.field_msgSvrId`，`J`，① `docs/_r16_g2_msgsnap.md` §3.2）。
         * **0 = 没拿到**。用于填 `hl0/m.e`(forwardMsgSvrId)。
         */
        val msgSvrId: Long = 0L,
        /**
         * R73 决定性修复（Q1-R）：源图 imgLocalId，写进 `hl0/m.a`（forwardFromImgId）。
         *
         * 微信自带转发把它设为源消息的 ImgInfo2 localId(**>0**) ⇒ j2 走 ImgInfo2/CDN
         * 服务端引用拿清晰大图；我方旧值恒 -1 ⇒ j2 读本地 ori 缩略图 ⇒ 模糊。
         * **-1 = 取不到，安全降级回现有本地文件重传（不改任何已闭环三层）。**
         * 该值是源消息属性、对所有目标相同 ⇒ 逐目标各建 m/q1/j2，多目标天然保留。
         */
        val imgLocalId: Long = -1L,
        /**
         * R73 转发上下文（原生 MsgRetransmitUI.k6 填入、我方旧版缺失）：m.b / q1.r / q1.j 的值来源。
         * 三项默认值都取「取不到 ⇒ 其它 lane 回退现状」的安全值。该值是源消息属性、
         * 对所有目标相同 ⇒ 逐目标各建 m/q1/j2，多目标天然保留。
         */
        val srcMsgId: Long = 0L,          // → 写进 hl0/m.b（源消息 msgId，>0 才写；原生 = MsgRetransmitUI.f）
        val srcTalkerPrefix: String = "", // → 写进 hl0/q1.r（转发源前缀，形如 "wxid_xxx:"，带尾冒号；原生 = k6 入参）
        val srcBigImgPath: String = "",   // → 写进 hl0/q1.j（源图大图路径，非 th_ 前缀；原生 = MsgRetransmitUI.g）
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
        // ⚠️ 原生可用 ≠ 这条消息能走原生：原生链路目前只接通 TEXT / IMAGE
        //    （视频要 modelimage/o3、普通文件要 qf4/r5，都没取证，见 ForwardNativeLogic.subTypeOf）。
        //    不加这个判据，名片/位置/链接会被送进原生链撞壁 —— 那比直接降级还慢，
        //    而且失败原因会写成 METHOD 之类，看起来像「锚点失效」，误导排查方向。
        if (nativeState == NativeState.AVAILABLE && ForwardNativeLogic.supportsNative(snap.kind)) {
            return Plan(Strategy.NATIVE)
        }
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
        // R73：用户明确不需要转发标记，降级重发是常态通路，不再在 toast 展示「降级 N」。
        // degraded 参数保留（计数与内部统计仍用），仅从展示文案中移除。
        if (skipped > 0) sb.append(" · 跳过 $skipped")
        if (failed > 0) sb.append(" · 失败 $failed")
        return sb.toString()
    }
}
