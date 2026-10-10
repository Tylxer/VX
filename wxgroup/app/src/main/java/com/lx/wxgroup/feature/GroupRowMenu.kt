package com.lx.wxgroup.feature

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.core.MarkRead
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX

/**
 * A5：长按会话列表里的**分组行** → 五项菜单。
 *
 * 置顶（已置顶时文案变「取消置顶」）/ 重命名 / 管理成员 / 标记已读 / 删除分组。
 *
 * ## 为什么自己弹对话框，而不是注入微信的长按菜单
 *
 * 微信的会话长按菜单是个 PopupWindow，**没有任何可挂 hook 的入口**
 * （`ListPopupWindow.show` / `PopupWindow.showAsDropDown` / `showAtLocation` /
 * `Dialog.show` / `WindowManager.addView` 全都试过，一个都不命中，见 [ConvMenu]）。
 * 分组行本来就是我们自己 new 出来的 View，直接给它挂 `OnLongClickListener` 弹
 * 自己的对话框最省事，也不用担心打乱微信菜单的 position 索引。
 *
 * 长按回调**必须 return true**（在 [GroupRowView] 里）：否则事件会冒泡到
 * ListView 的 `OnItemLongClickListener`，微信把这个位置当会话处理，
 * 弹出的就是「删除该聊天」。
 */
object GroupRowMenu {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 从 View 的 context 里剥出能用的 Activity。
     *
     * 微信的 Activity 是 `ContextThemeWrapper`（AppCompatActivity），所以不能只
     * `as? Activity`，得沿 `baseContext` 往上剥几层。
     */
    private fun activityOf(ctx: Context): Activity? {
        var c: Context? = ctx
        var guard = 0
        while (c != null && guard++ < 6) {
            if (c is Activity) return c.takeIf { !it.isFinishing && !it.isDestroyed }
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        return HostContext.activity()
    }

    /**
     * @param viewCtx **分组行自己的 Context**（`View.getContext`）。
     *
     * 弹 Dialog 必须有 Activity 上下文。这里**不能**退回 `HostContext.activity()`：
     * 那个引用来自 `Activity.onResume` 的 hook，而微信的 LauncherUI **覆写了
     * onResume**，hook 基类根本不触发（真机实测：引用一直是 null，弹窗直接
     * `BadTokenException: token null is not valid`）。
     * 列表 View 的 context 就是创建它的 Activity，永远有效 —— 用它最稳。
     */
    fun show(viewCtx: Context, groupId: Long) {
        mainHandler.post {
            runCatching { showInternal(viewCtx, groupId) }
                .onFailure { LogX.e("GROWMENU failed group=$groupId", it) }
        }
    }

    private fun showInternal(viewCtx: Context, groupId: Long) {
        val ctx = activityOf(viewCtx) ?: run {
            LogX.w("GROWMENU: no usable activity context (ctx=${viewCtx.javaClass.name})")
            return
        }
        val db = GroupDb.current() ?: run {
            LogX.w("GROWMENU: db not ready")
            return
        }
        val g = db.listGroups().firstOrNull { it.id == groupId } ?: run {
            LogX.w("GROWMENU: group $groupId not found")
            return
        }

        LogX.i("GROWMENU open group=$groupId name=${g.name} pinned=${g.pinned}")

        val items = arrayOf(
            if (g.pinned) "取消置顶" else "置顶该分组",
            "重命名",
            "管理成员",
            "标记已读",
            "删除分组",
        )

        AlertDialog.Builder(ctx)
            .setTitle(g.name)
            .setItems(items) { d, which ->
                d.dismiss()
                // 名字在重命名之后就变了，回调里统一重新读一次
                when (which) {
                    0 -> {
                        db.setGroupPinned(g.id, !g.pinned)
                        UiKit.toast(ctx, if (g.pinned) "已取消置顶" else "已置顶")
                        ConvFilter.refresh()
                    }
                    1 -> UiKit.input(ctx, "重命名分组", g.name, "分组名") { name ->
                        db.renameGroup(g.id, name)
                        UiKit.toast(ctx, "已重命名")
                        ConvFilter.refresh()
                    }
                    2 -> MemberPage.show(g.id, g.name)
                    3 -> {
                        val n = MarkRead.clearGroup(g.id)
                        UiKit.toast(
                            ctx,
                            if (n > 0) "已把 $n 个会话标为已读（仅本次生效，重启微信会恢复）"
                            else "没有未读会话"
                        )
                    }
                    4 -> UiKit.confirm(
                        ctx,
                        "删除分组",
                        "删除「${g.name}」？只会取消归组，成员不会丢失。",
                    ) {
                        db.deleteGroup(g.id)
                        UiKit.toast(ctx, "已删除分组")
                        // 正在看这个分组的话顺手退回收纳态，否则会卡在空列表里
                        if (ConvFilter.currentGroupId() == g.id) ConvFilter.setGroup(0)
                        else ConvFilter.refresh()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
