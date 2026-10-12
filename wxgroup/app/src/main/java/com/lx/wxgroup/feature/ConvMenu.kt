package com.lx.wxgroup.feature

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.core.MarkRead
import com.lx.wxgroup.data.Friend
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.WxidKind
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX

/**
 * A15：长按会话列表里的**普通会话** → 菜单（加入分组 / 标记已读）。
 *
 * ## 为什么自己弹对话框，而不是往微信的长按菜单里塞一行
 *
 * 早期这里走的是「注入微信菜单」，真机上**一次都没成功**，六轮探针的结论：
 *
 *  - 微信的会话菜单是个 PopupWindow，`ListPopupWindow.show` / `PopupWindow.showAsDropDown`
 *    / `showAtLocation` / `Dialog.show` / `WindowManager.addView` 全挂了 hook，一个都不命中。
 *  - 长按后枚举 `WindowManagerGlobal.mViews` 永远只有 1 个窗口（主 DecorView），
 *    即**菜单根本没弹**，无从注入。
 *  - 进一步实测（2026-10-12）：微信的 `OnItemLongClickListener`（z2）返回的是 **false**，
 *    菜单本该由 AOSP `AbsListView.performLongPress` 里的
 *    `if (!handled) showContextMenuForChild(...)` 兜底弹出；但把 listener 的返回值
 *    原样透传之后窗口数依然是 1 —— 说明微信压根没注册 context menu，
 *    这条兜底对它也不成立。
 *
 * 结论：微信 8.0.48 的会话菜单不走任何我们能挂的公开入口，注入是死路。
 * 改成自己弹 AlertDialog，和长按**分组行**时的 [GroupRowMenu] 一致 ——
 * 那条路已在真机上验证可用。
 *
 * ## 代价
 *
 * 长按会话时菜单变成我们的（原生的「删除该聊天 / 置顶」会看不到）。
 * 所以加了 [Keys.CHAT_CONV_MENU_ENABLED] 开关，关掉即恢复微信原生长按。
 *
 * ## 弹窗上下文
 *
 * 必须**从 View 的 context 剥 Activity**（见 [activityOf]）。用 Application context
 * 弹 Dialog 会 `BadTokenException: token null is not valid`。
 */
object ConvMenu {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * @param viewCtx **会话行自己的 Context**（`View.getContext`），不是 Application。
     * @param wxid 会话 username（`wxid_xxx` / `xxx@chatroom`）
     * @param name 展示名，取不到时传 wxid 也行
     */
    fun show(viewCtx: Context, wxid: String, name: String) {
        if (!Kv.getBool(Keys.CHAT_CONV_MENU_ENABLED)) return
        mainHandler.post {
            runCatching { showInternal(viewCtx, wxid, name) }
                .onFailure { LogX.e("ConvMenu failed wxid=$wxid", it) }
        }
    }

    private fun showInternal(viewCtx: Context, wxid: String, name: String) {
        val ctx = activityOf(viewCtx) ?: run {
            LogX.w("ConvMenu: no usable activity context (ctx=${viewCtx.javaClass.name})")
            return
        }
        val db = GroupDb.current() ?: run {
            LogX.w("ConvMenu: db not ready")
            return
        }
        val joined = runCatching { db.groupsOf(wxid) }.getOrDefault(emptyList())
        val label = if (joined.isEmpty()) "加入分组" else "分组管理（已加入 ${joined.size} 个）"

        LogX.i("ConvMenu open wxid=$wxid name=$name joined=${joined.size}")

        AlertDialog.Builder(ctx)
            .setTitle(name)
            .setItems(arrayOf(label, "标记已读")) { d, which ->
                d.dismiss()
                when (which) {
                    0 -> {
                        val friend = db.findFriend(wxid) ?: Friend(
                            wxid = wxid,
                            nickname = name,
                            type = WxidKind.typeOf(wxid),
                        )
                        // GroupPicker 是带勾选态的通用选择器，加入/移出都走它
                        GroupPicker.show(friend)
                    }
                    1 -> {
                        val n = MarkRead.clear(setOf(wxid))
                        UiKit.toast(
                            ctx,
                            if (n > 0) "已标为已读（仅本次生效，重启微信会恢复）" else "没有未读消息"
                        )
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 从 View 的 context 里剥出能用的 Activity。
     *
     * 微信的 Activity 是 `ContextThemeWrapper`（AppCompatActivity），不能只 `as? Activity`，
     * 得沿 `baseContext` 往上剥几层。
     */
    fun activityOf(ctx: Context): Activity? {
        var c: Context? = ctx
        var guard = 0
        while (c != null && guard++ < 6) {
            if (c is Activity) return c.takeIf { !it.isFinishing && !it.isDestroyed }
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        return HostContext.activity()
    }

    /**
     * 从行 View 上取会话标题。
     *
     * 踩过的坑：**会话标题不是 TextView**。微信的会话名是自绘 View（实测 id `l7a`），
     * 拿不到 `.text`；行里真正的 TextView 只有时间、以及**复用残留** —— 按「找 TextView」
     * 的思路只会抓到残留文本（实测抓到过「折叠置顶聊天」，那是另一个折叠行留在 tag
     * 上的旧值）。所以改读**无障碍文本**（与 uiautomator dump 同源），自绘 View 的可访问
     * 文本就是这么暴露出来的。
     */
    fun titleOf(row: View?): String {
        if (row == null) return ""
        var hit = ""
        fun walk(v: View, depth: Int) {
            if (depth > 14 || hit.isNotEmpty()) return
            val t = a11yText(v)
            if (t.isNotEmpty() && !isChromeText(t)) hit = t
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) v.getChildAt(i)?.let { walk(it, depth + 1) }
            }
        }
        runCatching { walk(row, 0) }
        return hit
    }

    /** 取一个 View 对外暴露的可访问文本（等价于 uiautomator dump 里看到的 text） */
    private fun a11yText(v: View): String = runCatching {
        val info = android.view.accessibility.AccessibilityNodeInfo.obtain()
        v.onInitializeAccessibilityNodeInfo(info)
        val t = (info.text ?: info.contentDescription)?.toString()?.trim().orEmpty()
        info.recycle()
        if (t == "null") "" else t
    }.getOrDefault("")

    /** 微信自己的行文案（不是人名），不能拿当会话名 */
    private fun isChromeText(t: String): Boolean =
        t.contains("置顶聊天") || t.contains("个置顶") || t.contains("该聊天")
}
