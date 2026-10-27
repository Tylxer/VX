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
    val unit: String? = null,
    /**
     * **登记在表里，但不进设置页**（默认 false = 正常渲染）。
     *
     * 这个标记只影响 UI 一处：`Prefs.visible(domain)` 会把它滤掉，
     * 而 `Prefs.ALL` / `spec()` / `defaultOf()` / `Kv.exportAll()` / `BackupFlow.knownKeys`
     * **全部照旧包含它** —— 所以「已备份的配置不丢」和 `kvget/kvset` 探针都不受影响。
     *
     * 什么时候该用：某项**还没有消费点**（现在改它不会有任何效果），
     * 但**已经可能存在于用户的备份文件里**，删定义会让旧备份导入时被
     * `BackupMerge.knownKeys` 当未知 key 丢掉。这种「保留定义 + 不渲染」是
     * 唯一正确的空壳形态 —— 既不骗用户，也不丢数据。
     *
     * ⚠️ 反例：功能**将来要做**、现在只是没做完 ⇒ 那不该用 hidden，
     * 该把 `Prefs.ALL` 那一行直接删掉，等做完了再加回来。
     */
    val hidden: Boolean = false
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
     * 收到图片消息时**自动把原图下载下来**（不用手点「查看原图」）。
     *
     * 判定「是否已有原图」用 `ImgInfo.t == 1`（微信自己的 `AutoGetBigImgLogic` 用的
     * 就是这一个字段），已有时直接跳过 —— 不重复建下载任务。
     */
    const val CHAT_AUTO_HD_IMG = "chat.auto_hd_img"

    /** 自动加载原图的**允许名单**（空 = 所有会话都生效） */
    const val CHAT_AUTO_HD_ALLOW = "chat.auto_hd_allow"

    /** 自动加载原图的**排除名单**（排除优先于允许） */
    const val CHAT_AUTO_HD_EXCLUDE = "chat.auto_hd_exclude"

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

    /**
     * 6.3-A：领取界面已经弹出之后，自动点一下「拆红包 / 開 / 领取」按钮。
     *
     * 这是 **UI 层**的东西（判据在 `feature/RedPacketLogic`），默认关。
     *
     * ⚠️ **红包域只有这一个开关 + 三个数值/名单项**（2026-10-19 收尾定）。
     * ⛔ 2026-10-20 用户决定：消息层「自动抢红包」永久不做，勿恢复（LuckyLogic/LuckyProbe 已删除）。
     * 曾有一整套「消息层决策引擎」（6 个键 + `LuckyLogic` + `LuckyProbe` + 496 行单测），已整块删除：
     * `decide()` 在生产代码里**零调用点**，是"看起来能自动抢红包、实际什么都不做"的空壳。
     * 需求 6.3 只剩 UI 层：用户手动点开红包后自动点「开」，由 `feature/RedPacketLogic.kt` 实现。
     */
    const val REDPACKET_AUTO_OPEN = "redpacket.auto_open"

    /**
     * 领取界面出现后的**基准**等待时长（ms）。
     *
     * `RedPacket.scanDelays` 按它缩放成四拍扫描（base/3、base*3/4、base*11/8、base+2000）。
     * **下限 200ms**：0ms 秒抢是风控最容易识别的特征，也是用户最容易填出来的手误值。
     */
    const val REDPACKET_DELAY_MS = "redpacket.delay_ms"

    /**
     * 同一个红包最多试图点几次（= 最多派发几次触摸）。
     *
     * `RedPacket.maxAttempts` 读它，钳制在 1~4。默认 3：第一次空响后还给补发机会，
     * 但不会无限连点。
     */
    const val REDPACKET_MAX_ATTEMPTS = "redpacket.max_attempts"

    /**
     * 参与范围（TEXT，**裸文本不是 JSON**，逗号/顿号/空白分隔）。
     * 元素可以是 talker（`wxid_xxx` / `xxx@chatroom`）也可以是群显示名。
     * **留空 = 全部会话**。
     *
     * ⚠️ **当前无消费点**：UI 层（`RedPacket`）只在"用户已经手动点开红包"之后才动手，
     * 那时没有会话筛选的余地 —— 用户点开的红包就是要拆的红包。
     * 这两个键曾留给「消息层」用，但消息层已删除（见 [REDPACKET_AUTO_OPEN] 的注释）。
     *
     * 既然没有消费点，就不能在设置页画输入框（那是「说能做、实际不做」的空壳）。
     * 但它**又必须留在 `Prefs.ALL` 里**：用户旧备份文件里可能带着这两个 key，
     * 删掉定义会让 `BackupFlow.knownKeys` 把它们当未知 key 丢弃。
     * ⇒ 唯一正确的处置是 `hidden = true`：**保留定义、不渲染**。
     */
    const val REDPACKET_ALLOW = "redpacket.allow"

    /** 排除范围（格式同 [REDPACKET_ALLOW]）。⚠️ 同样**无消费点**、`hidden = true`，原因见上 */
    const val REDPACKET_EXCLUDE = "redpacket.exclude"

    // —— 朋友圈域 ——
    const val MOMENTS_AUTO_REFRESH = "moments.auto_refresh"
    const val MOMENTS_AUTO_LOAD = "moments.auto_load"
    const val MOMENTS_REFRESH_INTERVAL_H = "moments.refresh_interval_h"
    const val MOMENTS_AUTO_EXPORT = "moments.auto_export"
    const val MOMENTS_EXPORT_PATH = "moments.export_path"
    const val MOMENTS_EXPORT_LIST = "moments.export_list"

    /**
     * R28：导出时**排除广告**（`sourceType` 的 `bit5(32)`）。
     *
     * ⛔ 判据是位 **32**（`SnsInfo.isAd()`），**不是** 128 ——
     * 128 是「折叠/全文」位，拿它过滤会误杀正常动态。见 `MomentsLogic.SOURCE_FLAG_AD`。
     */
    const val MOMENTS_EXCLUDE_AD = "moments.exclude_ad"

    /**
     * 导出动态时**同时拷图片**（R26 起由编译期常量 `MomentsExport.COPY_IMAGES` 改成配置键）。
     *
     * ⚠️ 名字有讲究：拷的是**微信本地 SNS 缓存**里的图（`cache/<hash32>/sns/…`），
     * 不是从 CDN 下。所以它的能力边界是「只抓得到本机缓存里还在的图」——
     * 已删动态、冷启前的图都取不回。UI 文案必须写清楚，别让用户当图片备份用。
     */
    const val MOMENTS_COPY_IMAGES = "moments.copy_images"

    // ⛔ 2026-10-27 已删：`sns.cdn_img` / `sns.cdn_rewrite_path` 两个键。
    //    随整条朋友圈图片 CDN 通路（`SnsCdnImage` 等 4 个源文件）一并物理删除，
    //    朋友圈图片已改道「SNS 缓存索引直扫」（`MomentsExport.buildSnsIndex`）。
    //    ⛔ 勿按 git 历史恢复：CDN 通路实测从不触发（判据见下方原注释）。

    /**
     * 定时刷新 / 自动导出的**内部状态**（epoch millis 字符串，读不到按 0 = 从没做过）。
     *
     * ⚠️ **刻意不进 `Prefs.ALL`**：进了就会显示在设置页、还会被备份引擎带走，
     * 而它只是「上次什么时候刷过」的运行状态，换机重置没有意义。
     * Kv 对未登记的 key 也能正常读写（`defaultOf` 返回空串），只是不出现在 UI/备份里。
     */
    const val MOMENTS_LAST_REFRESH_AT = "moments.last_refresh_at"
    const val MOMENTS_LAST_EXPORT_AT = "moments.last_export_at"

    /**
     * 图片**回填轮的低水位游标**（格式 `src:createTime:snsId`，见 `MomentsLogic.parseFillCursor`）。
     *
     * 语义：本轮扫到哪儿，下轮从那儿继续往更旧扫，扫到底回绕到最新 —— 让回填真轮转，
     * 而不是每轮都把配额花在最新那 200 条上（R26 复核 §8(b)）。
     *
     * ⚠️ 同 [MOMENTS_LAST_EXPORT_AT]：**刻意不进 `Prefs.ALL`** —— 纯运行状态，
     * 换机/清空更有意义，也不该被备份带走。
     */
    const val MOMENTS_FILL_CURSOR = "moments.fill_cursor"

    // —— 群发域 ——
    const val BROADCAST_INTERVAL_S = "broadcast.interval_s"
    const val BROADCAST_LAST_TEXT = "broadcast.last_text"
    const val BROADCAST_TARGETS = "broadcast.targets"

    // —— 调试 ——
    const val DEBUG_LOG = "debug.log"
}

/** 配置项的元数据总表。遍历它就能渲染出全部设置页 */
object Prefs {

    /**
     * 朋友圈导出默认目录 = **公共目录**，用户能用系统文件管理器直接打开。
     *
     * ⛔ 旧值 `Android/data/com.tencent.mm/files/WechatXposed/moments` 已废弃：
     *   Android 11+ 起该目录对第三方应用（含系统文件管理器）不可见，
     *   用户根本进不去 ⇒ 导出等于白导。2026-10-26 真机 M7 定案换成
     *   `Documents/WXGroup/moments`（与备份 `Documents/WXGroup` 同根，用户只找一处）。
     *
     * 已验证可写：设备 Android 16 / 微信 targetSdk=34 下
     * `MOMENT path ... writable=true`，因为 `/sdcard/Documents` 的 owner 就是微信 uid，
     * **不需要 SAF 授权**。见 `docs/_r19_M4M6M7_真机验证.md`。
     *
     * ⚠️ 旧值仍保留在 [LEGACY_EXPORT_PATH] 用于迁移已有配置，别删。
     */
    const val DEFAULT_EXPORT_PATH =
        "/storage/emulated/0/Documents/WXGroup/moments"

    /**
     * 旧默认值。只在**迁移**时用：老用户的 Kv 里已经持久化了这个字符串，
     * 光改默认值对他们无效（`getString` 读到的是库里的旧值）。
     * 见 `MomentsLogic.isLegacyExportPath`。
     */
    const val LEGACY_EXPORT_PATH =
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
        PrefSpec(
            Keys.CHAT_AUTO_HD_IMG, PrefKind.SWITCH, "false", "聊天",
            "自动加载原图（收到图片就后台下原图，不用手点「查看原图」）"
        ),
        PrefSpec(Keys.CHAT_AUTO_HD_ALLOW, PrefKind.ENTER, "[]", "聊天", "原图：设置允许名单"),
        PrefSpec(Keys.CHAT_AUTO_HD_EXCLUDE, PrefKind.ENTER, "[]", "聊天", "原图：设置排除名单"),
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
        // 📌 R21d 起语义变更：原来是「插入一条独立的提示行」，现在是「在原文气泡上画角标」。
        // 原因见 feature/RevokeRescue.kt 的 insertHint KDoc（负数 msgId 让微信闪退 + 提示行错位）。
        // ⚠️ key 名不改（改名会让老用户的选择丢失），只改展示文案。
        PrefSpec(Keys.ANTIREVOKE_SHOW_HINT, PrefKind.SWITCH, "false", "防撤回", "在原文上标「已阻止撤回」"),
        PrefSpec(Keys.ANTIREVOKE_RESTORE_VIEW, PrefKind.SWITCH, "false", "防撤回", "恢复默认显示"),
        PrefSpec(
            Keys.ANTIREVOKE_DEBUG_SELF, PrefKind.SWITCH, "false", "防撤回",
            "调试：连自己撤回也拦（验证拦截是否生效用，测完关掉）"
        ),

        // 红包域。⚠️ 只有 UI 层 4 项（消息层引擎已删除，见 REDPACKET_AUTO_OPEN 的注释）
        PrefSpec(Keys.REDPACKET_AUTO_OPEN, PrefKind.SWITCH, "false", "红包", "自动点击拆红包"),
        // ⚠️ 200 / 10000 与 RedPacket.scanDelays 的钳制范围一致，刻意不引 feature 包过来
        //    —— data 是 feature 的下层。真正在兜底的是 RedPacket 里的 coerceIn。
        PrefSpec(
            Keys.REDPACKET_DELAY_MS, PrefKind.NUMBER, "800", "红包", "识别后的等待时长",
            min = 200, max = 10_000, unit = "毫秒"
        ),
        PrefSpec(
            Keys.REDPACKET_MAX_ATTEMPTS, PrefKind.NUMBER, "3", "红包", "同一红包最多试图点击几次",
            min = 1, max = 4, unit = "次"
        ),
        PrefSpec(
            Keys.REDPACKET_ALLOW, PrefKind.TEXT, "", "红包",
            "参与范围（逗号分隔 wxid / 群名，留空=全部；当前无消费点）",
            hidden = true
        ),
        PrefSpec(
            Keys.REDPACKET_EXCLUDE, PrefKind.TEXT, "", "红包",
            "排除范围（逗号分隔，优先于参与范围；当前无消费点）",
            hidden = true
        ),

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
        // R28 广告黑名单。默认**开**：广告不是「好友的动态」，导出它属于噪音。
        PrefSpec(
            Keys.MOMENTS_EXCLUDE_AD, PrefKind.SWITCH, "true", "朋友圈",
            "排除广告（按 sourceType 的广告位判定）"
        ),
        // R26：图片导出。默认关（真机只验到「链路通」，见 MomentsExport 的 KDoc）。
        // ⚠️ 摘要必须写明「只导缓存里已有的图」——否则用户会以为能当图片备份用。
        PrefSpec(
            Keys.MOMENTS_COPY_IMAGES, PrefKind.SWITCH, "false", "朋友圈",
            "导出图片（仅微信本地缓存里已有的图；历史/已删动态的图取不回）"
        ),
        // ⛔ 2026-10-27 已删：这里原本登记着 `sns.cdn_img` / `sns.cdn_rewrite_path` 两项
        //    （hidden = true）。随整条 CDN 通路一并物理删除，判死判据保留备查：
        //      - sns.cdn_img：真机定案（docs/取证结论.md ⑫.9，2026-10-24）两条 ——
        //        ① 出参 picCachePath 字段**存在但恒为空**（hitPicE=7 / hitNoField=0）⇒ 拿不到落盘路径；
        //        ② 批量下载请求 `ok0.x0#n` **一次都没被调用**（hitN=0）⇒ 通路根本没发生。
        //      - sns.cdn_rewrite_path：零消费点（只在 refreshCfg() 被写入，全类无读取），从未实现的预留。
        //    ⚠️ 删除定义会让旧备份里的这两个 key 被 BackupMerge 当「未登记 key」丢弃
        //       （`BackupMerge.kt:165`，并计入 settingsDropped 提示）。已确认这是**容错丢弃、不拒绝导入**，
        //        且这两个开关在通路删除后无论真假都无任何效果 ⇒ 丢弃零损失。
        //    ⛔ 勿按 git 历史恢复。

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

    /**
     * 按域取配置，用于渲染某个二级页。
     *
     * ⚠️ **这个函数不过滤 `hidden`**（它答的是「这个域登记了哪些 key」，
     * 备份/统计口径要的是全集）。**渲染一律用 [visible]**。
     */
    fun byDomain(domain: String): List<PrefSpec> = ALL.filter { it.domain == domain }

    /**
     * 按域取**要画出来**的配置 —— 渲染器唯一该用的入口。
     *
     * 比 [byDomain] 多滤掉 `hidden = true` 的项（定义还在、备份还在、探针还在，
     * 只是不给用户看一个改了也没用的输入框）。理由见 [PrefSpec.hidden]。
     */
    fun visible(domain: String): List<PrefSpec> = byDomain(domain).filter { !it.hidden }

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
