package com.lx.wxgroup.data

/**
 * 全模块配置项的**唯一定义处**（K4）。
 *
 * 设计要点：
 * 1. 加一个新开关只需要在 `Prefs.ALL` 里加一行 —— K2 渲染器据此自动画出来、
 *    K5 备份引擎据此自动导出导入，都不用改代码。
 * 2. 命名规范 `<域>.<项>`，全小写 + 下划线。
 * 3. 值一律存字符串：开关 "true"/"false"、数值 "3"、时间 "23:00"、名单 JSON 数组。
 *    存字符串是为了让备份文件人类可读、adb 查库一眼能看懂。
 * 4. 数值范围在 spec 里定死，越界一律**钳制到最近边界**，不弹错误（决策 #6）。
 */

/** 设置项控件的种类，K2 渲染器据此决定画什么 */
enum class PrefKind {
    SWITCH,  // 开关
    NUMBER,  // 数值（带 min/max/unit）
    TIME,    // 时间 HH:mm，精确到分钟（决策 #6）
    TEXT,    // 多行文本
    PATH,    // 路径，点进去选目录
    ENTER    // 点进去进下一级页（名单等）
}

/** 一个设置项的完整定义 */
data class PrefSpec(
    val key: String,
    val kind: PrefKind,
    val def: String,
    val domain: String,
    val title: String,
    val min: Int? = null,
    val max: Int? = null,
    val unit: String? = null
)

/** key 常量。业务代码只引用这里，不写字面量 */
object Keys {
    // —— 聊天域 ——
    const val CHAT_GROUP_ROW_ENABLED = "chat.group_row_enabled"

    /**
     * 长按普通会话时，往**微信自己的菜单**里加「加入分组 / 标记已读」两项。
     *
     * 注入点是微信自己那个菜单 PopupWindow（详见
     * [com.lx.wxgroup.feature.ConvMenuInject]）：微信原生的
     * 标为未读 / 置顶 / 不显示该聊天 / 删除该聊天**全部保留**，我们只是追加在末尾。
     * 关掉则完全不干预长按，交回微信原生行为。
     */
    const val CHAT_CONV_MENU_ENABLED = "chat.conv_menu_enabled"

    /**
     * 诊断开关：开着时每次会话长按会把**菜单的全部内容** dump 到
     * `wxgroup_dbg.txt`（含每项的 groupId / itemId / 标题 / 可见性）。
     *
     * 只在模块加载时读一次，改完要重启微信。取证完记得关掉。
     */
    const val CHAT_MENU_DIAG = "chat.menu_diag"

    /**
     * K7 是否包装会话列表的 `OnItemLongClickListener`。
     *
     * ⚠️ **真机已验证它就是元凶**：包装会介入微信 `conversation.z2` 的调用回环
     * （z2 内部会回头调 `list.getOnItemLongClickListener()`，拿到的就是我们这一层），
     * 微信自己的 4 项菜单（标为未读 / 置顶 / 不显示 / 删除）因此**不再弹出**；
     * 把开关置 false 后菜单立刻恢复。→ 默认 false，长按链路零干预。
     *
     * 分组行（A5）不受影响：它有自己的 `View.OnLongClickListener` 且 return true，
     * 本来就不经过 ListView 的 OnItemLongClickListener。
     */
    const val CHAT_CONV_LONGCLICK_REMAP = "chat.conv_longclick_remap"

    const val CHAT_SHOW_UNREAD = "chat.show_unread"
    const val CHAT_DISABLE_PAT = "chat.disable_pat"
    const val CHAT_MUTE_AT_ENABLED = "chat.mute_at_enabled"
    const val CHAT_MUTE_AT_ALLOW = "chat.mute_at_allow"
    const val CHAT_MUTE_AT_EXCLUDE = "chat.mute_at_exclude"

    /**
     * 自己的昵称 / 群昵称，用于判断「这条消息是不是 @了我」。
     *
     * 微信把 @ 渲染进通知正文（如 `verity: @V 3`），要认出 `@V` 里的 V 是不是自己，
     * 必须知道自己的昵称。模块目前没有读微信自身昵称的锚点（S8 里「自身 wxid」未定位），
     * 所以这一项由用户填。多个用逗号分隔（各群群昵称不同时可以都写上）。
     * **留空时只认 `@所有人`** —— 宁可漏判也不把 @别人 的消息静音。
     */
    const val CHAT_AT_ALIASES = "chat.at_aliases"
    const val CHAT_DND_ENABLED = "chat.dnd_enabled"
    const val CHAT_DND_START = "chat.dnd_start"
    const val CHAT_DND_END = "chat.dnd_end"
    const val CHAT_AUTO_DND_NEW_GROUP = "chat.auto_dnd_new_group"

    // —— 防撤回域（6 项，v6.3 定：全部默认关闭；+ 2 个辅助开关）——
    const val ANTIREVOKE_MSG = "antirevoke.msg"
    const val ANTIREVOKE_SNS = "antirevoke.sns"
    const val ANTIREVOKE_SNS_DELETED_TAG = "antirevoke.sns_deleted_tag"
    const val ANTIREVOKE_COMMENT = "antirevoke.comment"
    const val ANTIREVOKE_COMMENT_DELETED_TAG = "antirevoke.comment_deleted_tag"
    const val ANTIREVOKE_SHOW_HINT = "antirevoke.show_hint"

    /**
     * 「恢复默认显示」：开启期间，被防撤回救下的消息**显示成正常撤回**的样子
     * （看起来跟没装模块一样）；关掉后恢复显示原文。
     *
     * ⚠️ 它只是**显示层的伪装**，不是真的撤回：
     * - 防撤回**照常在拦**，消息原文一直躺在库里（存在 `rescued` 表）；
     * - 开关只改「看起来是什么样」，关掉就全回来，**不会丢内容**；
     * - 开启期间新拦下的也一样显示成撤回，关掉后同样恢复。
     */
    const val ANTIREVOKE_RESTORE_VIEW = "antirevoke.restore_view"

    /**
     * 调试开关：连**自己撤回**也一起拦。
     *
     * 正常逻辑只拦他人撤回（需求 6.2 的作用域条款），但「他人撤回」要有小号配合才能验，
     * 平时不方便。开着这个开关就能用自己的撤回验证拦截链路到底通不通。
     * ⚠️ 验证完记得关掉，否则你自己也撤不回消息了。
     */
    const val ANTIREVOKE_DEBUG_SELF = "antirevoke.debug_self"

    // —— 红包域 ——
    const val REDPACKET_AUTO_OPEN = "redpacket.auto_open"

    // —— 朋友圈域 ——
    const val MOMENTS_AUTO_REFRESH = "moments.auto_refresh"
    const val MOMENTS_AUTO_LOAD = "moments.auto_load"
    const val MOMENTS_REFRESH_INTERVAL_H = "moments.refresh_interval_h"
    const val MOMENTS_AUTO_EXPORT = "moments.auto_export"
    const val MOMENTS_EXPORT_PATH = "moments.export_path"
    const val MOMENTS_EXPORT_LIST = "moments.export_list"

    /**
     * 定时刷新 / 自动导出的**内部状态**（epoch millis 字符串，读不到按 0 = 从没做过）。
     *
     * ⚠️ **刻意不进 `Prefs.ALL`**：进了就会显示在设置页、还会被备份引擎带走，
     * 而它只是「上次什么时候刷过」的运行状态，换机重置没有意义。
     * Kv 对未登记的 key 也能正常读写（`defaultOf` 返回空串），只是不出现在 UI/备份里。
     */
    const val MOMENTS_LAST_REFRESH_AT = "moments.last_refresh_at"
    const val MOMENTS_LAST_EXPORT_AT = "moments.last_export_at"

    // —— 群发域 ——
    const val BROADCAST_INTERVAL_S = "broadcast.interval_s"
    const val BROADCAST_LAST_TEXT = "broadcast.last_text"
    const val BROADCAST_TARGETS = "broadcast.targets"

    // —— 调试 ——
    const val DEBUG_LOG = "debug.log"
}

/** 配置项的元数据总表。遍历它就能渲染出全部设置页 */
object Prefs {

    const val DEFAULT_EXPORT_PATH =
        "/storage/emulated/0/Android/data/com.tencent.mm/files/WechatXposed/moments"

    val ALL: List<PrefSpec> = listOf(
        // 聊天域
        PrefSpec(Keys.CHAT_GROUP_ROW_ENABLED, PrefKind.SWITCH, "true", "聊天", "启用分组行"),
        PrefSpec(
            Keys.CHAT_CONV_MENU_ENABLED, PrefKind.SWITCH, "true", "聊天",
            "长按会话菜单加「加入分组」（追加在微信菜单末尾，不顶掉原生项）"
        ),
        PrefSpec(
            Keys.CHAT_MENU_DIAG, PrefKind.SWITCH, "false", "聊天",
            "调试：dump 会话长按菜单内容到 wxgroup_dbg.txt（重启微信生效）"
        ),
        PrefSpec(
            Keys.CHAT_CONV_LONGCLICK_REMAP, PrefKind.SWITCH, "false", "聊天",
            "包装会话列表长按监听（开着会让微信原生会话菜单消失，别开）"
        ),
        PrefSpec(Keys.CHAT_SHOW_UNREAD, PrefKind.SWITCH, "true", "聊天", "显示未读"),
        PrefSpec(Keys.CHAT_DISABLE_PAT, PrefKind.SWITCH, "false", "聊天", "关闭拍一拍"),
        PrefSpec(Keys.CHAT_MUTE_AT_ENABLED, PrefKind.SWITCH, "false", "聊天", "开启防止被@"),
        PrefSpec(Keys.CHAT_MUTE_AT_ALLOW, PrefKind.ENTER, "[]", "聊天", "设置允许名单"),
        PrefSpec(Keys.CHAT_MUTE_AT_EXCLUDE, PrefKind.ENTER, "[]", "聊天", "设置排除名单"),
        // 判断「@我」用。留空则只认 @所有人（见 Keys.CHAT_AT_ALIASES 的注释）
        PrefSpec(Keys.CHAT_AT_ALIASES, PrefKind.TEXT, "", "聊天", "我的昵称（判断@我）"),
        PrefSpec(Keys.CHAT_DND_ENABLED, PrefKind.SWITCH, "false", "聊天", "免打扰"),
        // 起止相等 = 不生效，不是全天（决策 #7）
        PrefSpec(Keys.CHAT_DND_START, PrefKind.TIME, "23:00", "聊天", "开始时间"),
        PrefSpec(Keys.CHAT_DND_END, PrefKind.TIME, "07:00", "聊天", "结束时间"),
        PrefSpec(Keys.CHAT_AUTO_DND_NEW_GROUP, PrefKind.SWITCH, "false", "聊天", "新加群自动免打扰"),

        // 防撤回域：默认全关，由用户自己开（决策 #2）
        PrefSpec(Keys.ANTIREVOKE_MSG, PrefKind.SWITCH, "false", "防撤回", "防止消息撤回"),
        PrefSpec(Keys.ANTIREVOKE_SNS, PrefKind.SWITCH, "false", "防撤回", "防止朋友圈动态撤回"),
        PrefSpec(
            Keys.ANTIREVOKE_SNS_DELETED_TAG, PrefKind.SWITCH, "false", "防撤回",
            "显示动态[已删除]标签（需同时开启「防止朋友圈动态撤回」）"
        ),
        PrefSpec(Keys.ANTIREVOKE_COMMENT, PrefKind.SWITCH, "false", "防撤回", "防止评论撤回"),
        PrefSpec(Keys.ANTIREVOKE_COMMENT_DELETED_TAG, PrefKind.SWITCH, "false", "防撤回", "显示评论[已删除]标签"),
        PrefSpec(Keys.ANTIREVOKE_SHOW_HINT, PrefKind.SWITCH, "false", "防撤回", "显示撤回提示"),
        PrefSpec(Keys.ANTIREVOKE_RESTORE_VIEW, PrefKind.SWITCH, "false", "防撤回", "恢复默认显示"),
        PrefSpec(
            Keys.ANTIREVOKE_DEBUG_SELF, PrefKind.SWITCH, "false", "防撤回",
            "调试：连自己撤回也拦（验证拦截是否生效用，测完关掉）"
        ),

        // 红包域
        PrefSpec(Keys.REDPACKET_AUTO_OPEN, PrefKind.SWITCH, "false", "红包", "自动点击拆红包"),

        // 朋友圈域
        PrefSpec(Keys.MOMENTS_AUTO_REFRESH, PrefKind.SWITCH, "false", "朋友圈", "定时刷新朋友圈"),
        PrefSpec(Keys.MOMENTS_AUTO_LOAD, PrefKind.SWITCH, "true", "朋友圈", "自动加载朋友圈动态"),
        PrefSpec(
            Keys.MOMENTS_REFRESH_INTERVAL_H, PrefKind.NUMBER, "3", "朋友圈", "刷新时间间隔",
            min = 1, max = 24, unit = "小时"
        ),
        PrefSpec(Keys.MOMENTS_AUTO_EXPORT, PrefKind.SWITCH, "false", "朋友圈", "自动导出朋友圈动态"),
        PrefSpec(Keys.MOMENTS_EXPORT_PATH, PrefKind.PATH, DEFAULT_EXPORT_PATH, "朋友圈", "路径"),
        PrefSpec(Keys.MOMENTS_EXPORT_LIST, PrefKind.ENTER, "[]", "朋友圈", "设置自动导出名单"),

        // 群发域
        PrefSpec(
            Keys.BROADCAST_INTERVAL_S, PrefKind.NUMBER, "30", "群发", "发送间隔",
            min = 1, max = 600, unit = "秒"
        ),
        PrefSpec(Keys.BROADCAST_LAST_TEXT, PrefKind.TEXT, "", "群发", "消息内容"),
        PrefSpec(Keys.BROADCAST_TARGETS, PrefKind.ENTER, "[]", "群发", "选择发送目标"),

        // 调试
        PrefSpec(Keys.DEBUG_LOG, PrefKind.SWITCH, "false", "调试", "调试日志")
    )

    private val byKey: Map<String, PrefSpec> = ALL.associateBy { it.key }

    fun spec(key: String): PrefSpec? = byKey[key]

    /** 取默认值；未登记过的 key 返回空串，不抛异常 */
    fun defaultOf(key: String): String = byKey[key]?.def ?: ""

    /** 按域取配置，用于渲染某个二级页 */
    fun byDomain(domain: String): List<PrefSpec> = ALL.filter { it.domain == domain }

    /** 数值钳制到 [min, max]；不是数值型或没有范围则原样返回 */
    fun clamp(key: String, value: Int): Int {
        val s = byKey[key] ?: return value
        val lo = s.min
        val hi = s.max
        return when {
            lo != null && hi != null -> value.coerceIn(lo, hi)
            lo != null -> maxOf(value, lo)
            hi != null -> minOf(value, hi)
            else -> value
        }
    }
}
