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
    const val CHAT_SHOW_UNREAD = "chat.show_unread"
    const val CHAT_DISABLE_PAT = "chat.disable_pat"
    const val CHAT_MUTE_AT_ENABLED = "chat.mute_at_enabled"
    const val CHAT_MUTE_AT_ALLOW = "chat.mute_at_allow"
    const val CHAT_MUTE_AT_EXCLUDE = "chat.mute_at_exclude"
    const val CHAT_DND_ENABLED = "chat.dnd_enabled"
    const val CHAT_DND_START = "chat.dnd_start"
    const val CHAT_DND_END = "chat.dnd_end"
    const val CHAT_AUTO_DND_NEW_GROUP = "chat.auto_dnd_new_group"

    // —— 防撤回域（6 项，v6.3 定：全部默认关闭）——
    const val ANTIREVOKE_MSG = "antirevoke.msg"
    const val ANTIREVOKE_SNS = "antirevoke.sns"
    const val ANTIREVOKE_SNS_DELETED_TAG = "antirevoke.sns_deleted_tag"
    const val ANTIREVOKE_COMMENT = "antirevoke.comment"
    const val ANTIREVOKE_COMMENT_DELETED_TAG = "antirevoke.comment_deleted_tag"
    const val ANTIREVOKE_SHOW_HINT = "antirevoke.show_hint"

    // —— 红包域 ——
    const val REDPACKET_AUTO_OPEN = "redpacket.auto_open"

    // —— 朋友圈域 ——
    const val MOMENTS_AUTO_REFRESH = "moments.auto_refresh"
    const val MOMENTS_AUTO_LOAD = "moments.auto_load"
    const val MOMENTS_REFRESH_INTERVAL_H = "moments.refresh_interval_h"
    const val MOMENTS_AUTO_EXPORT = "moments.auto_export"
    const val MOMENTS_EXPORT_PATH = "moments.export_path"
    const val MOMENTS_EXPORT_LIST = "moments.export_list"

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
        PrefSpec(Keys.CHAT_SHOW_UNREAD, PrefKind.SWITCH, "true", "聊天", "显示未读"),
        PrefSpec(Keys.CHAT_DISABLE_PAT, PrefKind.SWITCH, "false", "聊天", "关闭拍一拍"),
        PrefSpec(Keys.CHAT_MUTE_AT_ENABLED, PrefKind.SWITCH, "false", "聊天", "开启防止被@"),
        PrefSpec(Keys.CHAT_MUTE_AT_ALLOW, PrefKind.ENTER, "[]", "聊天", "设置允许名单"),
        PrefSpec(Keys.CHAT_MUTE_AT_EXCLUDE, PrefKind.ENTER, "[]", "聊天", "设置排除名单"),
        PrefSpec(Keys.CHAT_DND_ENABLED, PrefKind.SWITCH, "false", "聊天", "免打扰"),
        // 起止相等 = 不生效，不是全天（决策 #7）
        PrefSpec(Keys.CHAT_DND_START, PrefKind.TIME, "23:00", "聊天", "开始时间"),
        PrefSpec(Keys.CHAT_DND_END, PrefKind.TIME, "07:00", "聊天", "结束时间"),
        PrefSpec(Keys.CHAT_AUTO_DND_NEW_GROUP, PrefKind.SWITCH, "false", "聊天", "新加群自动免打扰"),

        // 防撤回域：默认全关，由用户自己开（决策 #2）
        PrefSpec(Keys.ANTIREVOKE_MSG, PrefKind.SWITCH, "false", "防撤回", "防止消息撤回"),
        PrefSpec(Keys.ANTIREVOKE_SNS, PrefKind.SWITCH, "false", "防撤回", "防止朋友圈动态撤回"),
        PrefSpec(Keys.ANTIREVOKE_SNS_DELETED_TAG, PrefKind.SWITCH, "false", "防撤回", "显示动态[已删除]标签"),
        PrefSpec(Keys.ANTIREVOKE_COMMENT, PrefKind.SWITCH, "false", "防撤回", "防止评论撤回"),
        PrefSpec(Keys.ANTIREVOKE_COMMENT_DELETED_TAG, PrefKind.SWITCH, "false", "防撤回", "显示评论[已删除]标签"),
        PrefSpec(Keys.ANTIREVOKE_SHOW_HINT, PrefKind.SWITCH, "false", "防撤回", "显示撤回提示"),

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
