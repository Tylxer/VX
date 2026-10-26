package com.lx.wxgroup.feature

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.data.PrefKind
import com.lx.wxgroup.data.PrefSpec
import com.lx.wxgroup.data.Prefs
import com.lx.wxgroup.ui.Panel
import com.lx.wxgroup.ui.PanelPage
import com.lx.wxgroup.ui.Rows
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX

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
        // visible()：只统计用户真正能看到的项，否则「1 / 3 项已开启」里的分母
        // 会把 hidden 项算进来，跟二级页实际显示的行数对不上。
        val switches = Prefs.visible(domain).filter { it.kind == PrefKind.SWITCH }
        if (switches.isEmpty()) return ""
        val on = switches.count { Kv.getBool(it.key) }
        return if (on == 0) "未开启" else "$on / ${switches.size} 项已开启"
    }

    // ------------------------------------------------------------ 名单选择

    /**
     * 所有 `ENTER` 类配置项（名单、群发目标）的点击入口 —— 都推同一个 [PickerPage]。
     *
     * **R24 起统一四 Tab**：好友 / 群聊 / 我的分组 / 微信标签，对所有名单场景开放
     * （用户明确要求）。四 Tab 的构建只有一份实现 —— [PickerTabs.all]，
     * 本方法只负责取初始值、写回配置。
     *
     * `mixed` 已不再决定「给几段」（四 Tab 恒定），只保留给 hint 文案：
     * 群发目标的 hint 要说明「会展开成具体的人并去重」，普通名单不需要。
     */
    private fun onEnterSpec(spec: PrefSpec): Boolean {
        val db = GroupDb.current()
        if (db == null) {
            toast("数据库还没准备好")
            return true
        }
        val mixed = spec.key == Keys.BROADCAST_TARGETS
        val tabs = PickerTabs.all(
            db = db,
            groups = PickerTabs.allowGroupTab(),
            labels = PickerTabs.allowGroupTab(),
        )
        if (tabs.isEmpty()) {
            toast("好友缓存为空，先去通讯录滚一遍再回来")
            return true
        }

        Panel.push(
            PickerPage(
                title = spec.title,
                tabs = tabs,
                initial = Kv.getList(spec.key),
                hint = if (mixed) "分组、微信标签与好友可混选，发送时会展开成具体的人并去重" else null,
                // F1：群聊补跑完成后原地刷新，把「群聊」Tab 补出来（不丢勾选/当前 Tab/搜索词）
                refreshTabs = {
                    PickerTabs.all(
                        db = db,
                        groups = PickerTabs.allowGroupTab(),
                        labels = PickerTabs.allowGroupTab(),
                    )
                },
            ) { ids ->
                Kv.putList(spec.key, ids)
                // R24：免打扰名单可能含 g:/t:，作用域判定带 30 秒缓存 → 改完立刻失效，
                // 否则验收时会看到「名单改了但静音行为晚 30 秒才变」的假阴性
                runCatching { com.lx.wxgroup.core.NotifyMute.invalidateScope() }
                LogX.i("PREF ${spec.key} = ${ids.size} 项")
            }
        )
        return true
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
     * 分组管理目前还是独立弹窗（旧项目 `GroupManager`）。等它改造成 PanelPage 后
     * 这里从 `show()` 换成 `Panel.push(...)`。
     */
    fun chatPage(): PanelPage = SettingPage(
        title = "聊天",
        domain = "聊天",
        head = listOf(
            SettingPage.Row("分组管理", { "${GroupDb.current()?.countGroups() ?: 0} 个分组" }) {
                Panel.push(GroupManagerPage())
            }
        ),
        onEnter = { onEnterSpec(it) },
        footnote = "免打扰的开始与结束时间相同时，视为不生效（不是全天）。时段内除微信电话外，提示音与震动都会被掐断。",
    )

    fun redpacketPage(): PanelPage = SettingPage(
        title = "红包",
        domain = "红包",
        footnote = "「自动点击拆红包」只在领取界面已弹出后替你点那一下按钮，" +
            "不改变消息接收链路、不在后台触发。决定权仍在你：你点开哪个红包，就只拆哪个。",
    )

    fun momentsPage(): PanelPage = SettingPage(
        title = "朋友圈",
        domain = "朋友圈",
        onEnter = { onEnterSpec(it) },
        footnote = "导出目录为微信应用专属目录，模块可直接写入，无需额外存储权限。",
    )

    /**
     * 群发页（需求 6.5）。
     *
     * 交给 [MassSendPage]（单例，任务状态活过面板重建 —— 群发是全程后台的），
     * 不再用 `SettingPage` 占位：目标 / 内容 / 间隔三项配置在那一页里照样走 K2 渲染器。
     *
     * ✅ **S9 已接通并真机验证**（2026-10-18）：`MassSendRunner.send` 走
     * `core/TextSender.send` 真发。只有模块未 prepare（链路缺失）时才返回 NO_CHANNEL，
     * 那时任务照常走完全部目标，最后由弹窗如实告知「完成 0/N · 未发送 N」
     * 并导出目标清单。**不假装发送成功。**
     */
    fun broadcastPage(): PanelPage = MassSendPage

    private fun toast(msg: String) {
        val c = HostContext.activity() ?: HostContext.app() ?: return
        UiKit.toast(c, msg)
    }
}
