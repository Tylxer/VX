package com.lx.wxgroup.feature

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.data.PrefKind
import com.lx.wxgroup.data.Prefs
import com.lx.wxgroup.ui.Panel
import com.lx.wxgroup.ui.PanelPage
import com.lx.wxgroup.ui.Rows
import com.lx.wxgroup.ui.UiKit

/**
 * 模块主界面：六行入口（G2 / G3）。
 *
 * 顺序按需求定稿：**防撤回 / 聊天 / 红包 / 朋友圈 / 备份 / 群发**。
 * 每行副标题显示该域当前开了几项，不用点进去就知道状态。
 *
 * 入口本身（微信「+」菜单里那一行，锚点 S3）还没定位，
 * 现在靠 `adb shell am broadcast -a com.lx.wxgroup.action.PANEL -p com.tencent.mm` 唤起。
 * 等 S3 定位后就是一行 hook 调 `Panel.open(MainPage)`，本文件不用改。
 */
object MainPage : PanelPage {

    override val title = "分组模块"

    // 六行色块。挑的是微信自己也在用的色系，深色主题下不至于刺眼
    private val C_ANTI = 0xFFFA5151.toInt()
    private val C_CHAT = 0xFF07C160.toInt()
    private val C_RED = 0xFFFA9D3B.toInt()
    private val C_MMT = 0xFF576B95.toInt()
    private val C_BAK = 0xFF4B8FF7.toInt()
    private val C_BC = 0xFF8B5CF6.toInt()

    private data class Entry(
        val title: String,
        val color: Int,
        val subtitle: () -> String,
        val page: () -> PanelPage,
    )

    override fun build(ctx: Context): View {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(Rows.section(ctx, "功能"))
        for (e in entries()) {
            root.addView(Rows.action(ctx, e.title, e.subtitle(), e.color) { Panel.push(e.page()) })
        }
        root.addView(Rows.note(ctx, "点遮罩区域或按返回键关闭面板"))
        return root
    }

    private fun entries(): List<Entry> = listOf(
        Entry("防撤回", C_ANTI, { switchSummary("防撤回") }, { antirevokePage() }),
        Entry("聊天", C_CHAT, { switchSummary("聊天") }, { chatPage() }),
        Entry("红包", C_RED, { switchSummary("红包") }, { redpacketPage() }),
        Entry("朋友圈", C_MMT, { switchSummary("朋友圈") }, { momentsPage() }),
        Entry("备份", C_BAK, { "导出 / 导入 JSON" }, { BackupPanelPage() }),
        Entry("群发", C_BC, { "间隔 ${Kv.getInt(Keys.BROADCAST_INTERVAL_S)} 秒" }, { broadcastPage() }),
    )

    /** 「已开 n / m 项」，让用户不用点进去就知道这个域现在是开是关 */
    private fun switchSummary(domain: String): String {
        val switches = Prefs.byDomain(domain).filter { it.kind == PrefKind.SWITCH }
        if (switches.isEmpty()) return ""
        val on = switches.count { Kv.getBool(it.key) }
        return if (on == 0) "未开启" else "$on / ${switches.size} 项已开启"
    }

    // ------------------------------------------------------------ 各二级页

    fun antirevokePage(): PanelPage = SettingPage(
        title = "防撤回",
        domain = "防撤回",
        footnote = "只对他人撤回的消息生效，自己撤回的不受影响。" +
            "关闭「显示撤回提示」时，历史里已有的撤回提示也会一并隐藏。",
    )

    /**
     * 聊天域：顶部补一行「分组管理」（它不是配置项，进不了 `Prefs.ALL`）。
     *
     * 分组管理目前还是独立弹窗（旧项目 `GroupManager`）。等 K1 全量接入后
     * 会改造成 PanelPage 走页面栈，届时这里从 `show()` 改成 `Panel.push(...)`。
     */
    fun chatPage(): PanelPage = SettingPage(
        title = "聊天",
        domain = "聊天",
        head = listOf(
            SettingPage.Row("分组管理", { "${GroupDb.current()?.countGroups() ?: 0} 个分组" }) {
                GroupManager.show()
            }
        ),
        footnote = "免打扰的开始与结束时间相同时，视为不生效（不是全天）。时段内除微信电话外，提示音与震动都会被掐断。",
    )

    fun redpacketPage(): PanelPage = SettingPage(
        title = "红包",
        domain = "红包",
        footnote = "在红包领取界面自动点击「拆红包」，不改变消息接收链路。",
    )

    fun momentsPage(): PanelPage = SettingPage(
        title = "朋友圈",
        domain = "朋友圈",
        footnote = "导出目录为微信应用专属目录，模块可直接写入，无需额外存储权限。",
    )

    /**
     * 群发页。执行链路依赖底层发送接口（锚点 S9，未定位），
     * 所以「选择目标」和「开始群发」现在都是占位 —— 界面与配置先就位，
     * S9 打通后只需要把这两个 onClick 换成真实调用。
     */
    fun broadcastPage(): PanelPage = SettingPage(
        title = "群发",
        domain = "群发",
        head = listOf(
            SettingPage.Row("选择发送目标", { "模块分组 / 微信标签" }) {
                toast("目标选择器（K3）待接入")
            }
        ),
        tail = listOf(
            SettingPage.Row("开始群发", asButton = true) {
                toast("发送接口（S9）未定位，暂不能群发")
            }
        ),
        footnote = "全自动后台执行，失败重试 1 次后跳过，全部结束弹「完成 n/m」；连续失败 5 条自动中止。",
    )

    private fun toast(msg: String) {
        val c = HostContext.activity() ?: HostContext.app() ?: return
        UiKit.toast(c, msg)
    }
}
