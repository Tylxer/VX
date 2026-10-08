package com.lx.wxgroup.hook

import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.TextView
import com.lx.wxgroup.feature.MainPage
import com.lx.wxgroup.ui.Panel
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * S3：微信主界面右上角「+」下拉菜单底部追加一行「分组模块」。
 *
 * ## 实测到的结构（8.0.48 / 2588，谷歌版）
 * 点击「+」（`WeImageView`, id = `plus_icon`）后弹出的是一个 PopupWindow，
 * 里面就是一个普通 **ListView**，4 个菜单项：发起群聊 / 添加朋友 / 扫一扫 / 收付款。
 *
 * 单项结构（复用它就能自动跟微信样式一致，不用自己画）：
 * ```
 * LinearLayout id/n9y  clickable=true   ← 点击挂在**这一层**，不是 ListView.onItemClick
 *   ImageView id/hrx                    ← 图标
 *   LinearLayout
 *     LinearLayout
 *       TextView id/pkc                 ← 菜单文字
 *     ImageView id/das                  ← 分隔线
 * ```
 *
 * ## 为什么不 hook 菜单的创建类
 * 那些类名全是混淆的（而且不同版本会变）。这里改成**按视图特征认人**：
 * 拿到 adapter 后先试渲染第 0 行，看它里面有没有 `id/pkc` 这个 TextView。
 * 有就认，没有就不动。这样只要微信不改菜单布局，版本升级也不会失效。
 *
 * ## 为什么是包装 adapter 而不是 addView
 * addView 到 ListView 外面，菜单容器高度是固定的，多出来的一行会被截掉；
 * 包装 adapter 让 ListView 自己多算一项，高度、分隔线、滚动全都自动正确。
 */
object PlusMenu {

    private const val LABEL = "分组模块"

    /** 菜单文字那个 TextView 的资源名 */
    private const val ID_ROW_TEXT = "pkc"
    /** 菜单图标那个 ImageView 的资源名 */
    private const val ID_ROW_ICON = "hrx"

    @Volatile
    private var wrapped = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.widget.ListView", cl, "setAdapter",
                ListAdapter::class.java, adapterHook,
            )
            LogX.i("S3: hooked ListView.setAdapter")
        }.onFailure { LogX.e("S3: hook setAdapter failed", it) }
    }

    private val adapterHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                val lv = param.thisObject as? ListView ?: return
                val real = param.args[0] as? ListAdapter ?: return
                if (real is MenuWrapper) return
                if (!looksLikePlusMenu(lv, real)) return
                param.args[0] = MenuWrapper(real, LABEL)
                wrapped = true
                LogX.i("S3: plus menu wrapped, realCount=${real.count}")
            }.onFailure { LogX.w("S3: setAdapter hook error: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * 认人：把 adapter 的第 0 行渲染出来看看，是不是「+」菜单的样子。
     *
     * 调用 `getView(0, null, lv)` 是安全的（菜单 adapter 只是 inflate + 设文本），
     * 菜单最多十来项，这点开销可以忽略。
     */
    private fun looksLikePlusMenu(lv: ListView, real: ListAdapter): Boolean {
        val count = real.count
        if (count < 2 || count > 12) return false
        val ctx = lv.context ?: return false
        val idText = ctx.resources.getIdentifier(ID_ROW_TEXT, "id", ctx.packageName)
        if (idText == 0) return false
        val row = runCatching { real.getView(0, null, lv) }.getOrNull() ?: return false
        return findView(row, idText) != null
    }

    private fun findView(root: View, id: Int): View? {
        if (root.id == id) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val hit = findView(root.getChildAt(i), id)
                if (hit != null) return hit
            }
        }
        return null
    }

    /** 把复用来的菜单行改造成我们自己的那一项 */
    private fun decorate(row: View, label: String, ctx: android.content.Context) {
        val idText = ctx.resources.getIdentifier(ID_ROW_TEXT, "id", ctx.packageName)
        if (idText != 0) {
            (findView(row, idText) as? TextView)?.text = label
        }
        // 图标沿用微信的会误导（那是「发起群聊」的图标），直接留空占位，
        // 用 INVISIBLE 而不是 GONE —— GONE 会让整行的宽度塌掉，跟其他项对不齐
        val idIcon = ctx.resources.getIdentifier(ID_ROW_ICON, "id", ctx.packageName)
        if (idIcon != 0) {
            findView(row, idIcon)?.visibility = View.INVISIBLE
        }
        // 覆盖微信原本挂在这一行上的监听（否则点了会去「发起群聊」）
        row.setOnClickListener { onRowClick(row) }
    }

    private fun onRowClick(row: View) {
        runCatching { dismissHostPopup(row) }
            .onFailure { LogX.d("S3: popup dismiss failed: ${it.javaClass.simpleName}") }
        runCatching {
            Panel.open(MainPage)
            LogX.i("S3: panel opened from plus menu")
        }.onFailure { LogX.e("S3: open panel failed", it); }
    }

    /**
     * 关掉承载这个菜单的 PopupWindow。
     *
     * 拿不到 PopupWindow 实例（微信用的是它的子类，类名还是混淆的），但菜单行的
     * View 链顶端一定是 PopupWindow 的 decorView，而它作为内部类持有外部
     * PopupWindow 的引用。所以沿 View 链逐层扫字段，找**继承链上有
     * android.widget.PopupWindow** 的那个值 —— 只比对类名本身会漏掉子类。
     *
     * 找不到就退一步把菜单整棵 View 藏起来（INVISIBLE 而不是 GONE，避免影响布局），
     * 至少不会让菜单尴尬地留在面板背后。
     */
    private fun dismissHostPopup(row: View) {
        var cur: View? = row
        var top: View = row
        while (cur != null) {
            val hit = scanPopupWindow(cur)
            if (hit != null) {
                runCatching { hit.javaClass.getMethod("dismiss").invoke(hit) }
                    .onSuccess { LogX.d("S3: popup dismissed") }
                    .onFailure { LogX.w("S3: dismiss invoke failed: ${it.javaClass.simpleName}") }
                return
            }
            top = cur
            cur = cur.parent as? View
        }
        LogX.w("S3: popup not found (top=${top.javaClass.name}), hiding menu view")
        runCatching { top.visibility = View.INVISIBLE }
    }

    /** 扫一个 View 的所有字段（含父类字段），找 PopupWindow 实例 */
    private fun scanPopupWindow(v: View): Any? {
        var cls: Class<*>? = v.javaClass
        while (cls != null) {
            for (f in cls.declaredFields) {
                val o = runCatching {
                    f.isAccessible = true
                    f.get(v)
                }.getOrNull() ?: continue
                if (isPopupWindow(o)) return o
            }
            cls = cls.superclass
        }
        return null
    }

    private fun isPopupWindow(o: Any): Boolean {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            if (c.name == "android.widget.PopupWindow") return true
            c = c.superclass
        }
        return false
    }

    /**
     * 在原 adapter 末尾多加一项的包装器。
     *
     * 刻意让 `getItem()` 对最后一项返回 String（而不是 null）：微信的代码万一取到它，
     * 至少不会 NPE，而我们的点击监听是在最外层拦截的，正常根本走不到微信那边。
     */
    private class MenuWrapper(
        private val real: ListAdapter,
        private val label: String,
    ) : BaseAdapter() {

        private val extraIndex: Int get() = real.count

        override fun getCount(): Int = real.count + 1

        override fun getItem(position: Int): Any? =
            if (position >= extraIndex) label else runCatching { real.getItem(position) }.getOrNull()

        override fun getItemId(position: Int): Long =
            if (position >= extraIndex) -999L else real.getItemId(position)

        override fun getViewTypeCount(): Int = real.viewTypeCount + 1

        override fun getItemViewType(position: Int): Int =
            if (position >= extraIndex) real.viewTypeCount else real.getItemViewType(position)

        override fun hasStableIds(): Boolean = real.hasStableIds()

        override fun isEmpty(): Boolean = false

        override fun areAllItemsEnabled(): Boolean = real.areAllItemsEnabled()

        override fun isEnabled(position: Int): Boolean =
            if (position >= extraIndex) true else real.isEnabled(position)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (position < extraIndex) return real.getView(position, convertView, parent)
            val ctx = parent.context
            val row = runCatching { real.getView(0, null, parent) }.getOrNull()
            if (row == null) {
                // 兜底：极端情况下拿不到真实行，就自己画一个朴素的行，
                // 宁可样式差点也不能让菜单崩掉
                return TextView(ctx).apply {
                    text = label
                    textSize = 16f
                    setTextColor(0xFF333333.toInt())
                    val pad = (20 * ctx.resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                }
            }
            decorate(row, label, ctx)
            return row
        }
    }

    /** 供调试：是否至少成功包装过一次 */
    fun isWrapped(): Boolean = wrapped
}
