package com.lx.wxgroup.feature

import android.view.View
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 临时诊断：抓「下拉快捷面板容器（id=a32）」在筛选 OFF→ON 后 3 秒内被谁改变。
 *
 * 背景：OFF→ON 时面板会自动弹出（4/4 复现），ON→OFF / ON→ON / 置顶展开（内容下移 779px）
 * 都不触发，纯零位移 tap 也触发 —— 排除触摸路径，必是程序化改动。这里在 toggle 后的
 * 3 秒窗口内监控 a32 的 setTranslationY / setLayoutParams / offsetTopAndBottom，
 * 打印调用栈，定位微信的面板控制类，再做针对性抑制。
 *
 * 用完即删。全 View 热路径 hook 仅诊断期可接受。
 */
object PanelDiag {
    @Volatile private var until = 0L
    private var a32Id = 0

    /** 抑制窗口：toggle 后 600ms 内拦下面板展开的 setTranslationY 推送 */
    @Volatile var suppress = false
        private set

    fun arm() {
        until = System.currentTimeMillis() + 600
        suppress = true
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        // v13 主修复：面板展开动作在 AbsListView.invokeOnItemScrollListener →
        // ConversationListView.onScroll 里同步执行（v6 栈实锤）。抑制窗口内
        // 直接跳过滚动回调的分发，微信就"看不到"我们造成的布局变化，面板不会开。
        runCatching {
            XposedBridge.hookAllMethods(
                android.widget.AbsListView::class.java, "invokeOnItemScrollListener",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!suppress || System.currentTimeMillis() > until) return
                        param.result = null
                        LogX.i("DIAG suppressed invokeOnItemScrollListener")
                    }
                },
            )
        }.onFailure { LogX.w("DIAG hook invoke failed: ${it.javaClass.simpleName}") }

        // v11 修复钩子：面板展开 = 微信 onScroll 把 ActionBar 等 setTranslationY(1271)。
        // 抑制窗口内拦下大位移的 setTranslationY，面板就展开不了；
        // 配合 GroupBar.reanchorPanel 把面板 item 滚回关闭位，窗口结束后一切如常。
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.view.View", lpparam.classLoader, "setTranslationY",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!suppress || System.currentTimeMillis() > until) return
                        val to = (param.args[0] as? Float) ?: return
                        val v = param.thisObject as? View ?: return
                        if (kotlin.math.abs(to - v.translationY) <= 150f) return
                        param.result = null  // 拦下：跳过原始调用，视图不动
                        LogX.i("DIAG blocked setTranslationY $to on ${v.javaClass.simpleName}")
                    }
                },
            )
        }.onFailure { LogX.w("DIAG hook suppress failed: ${it.javaClass.simpleName}") }

        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.view.View", lpparam.classLoader, "setTranslationY",
                Float::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) = hit(param, "setTranslationY")
                },
            )
        }.onFailure { LogX.w("DIAG hook setTranslationY failed: ${it.javaClass.simpleName}") }

        runCatching {
            XposedBridge.hookAllMethods(
                View::class.java, "offsetTopAndBottom",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) = hit(param, "offsetTopAndBottom")
                },
            )
        }.onFailure { LogX.w("DIAG hook offsetTopAndBottom failed: ${it.javaClass.simpleName}") }

        runCatching {
            XposedBridge.hookAllMethods(
                View::class.java, "setLayoutParams",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) = hit(param, "setLayoutParams")
                },
            )
        }.onFailure { LogX.w("DIAG hook setLayoutParams failed: ${it.javaClass.simpleName}") }

        // 面板高度 131↔1920 很可能走 layout() 或直接改 lp.height + requestLayout
        runCatching {
            XposedBridge.hookAllMethods(
                View::class.java, "layout",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) = hit(param, "layout")
                },
            )
        }.onFailure { LogX.w("DIAG hook layout failed: ${it.javaClass.simpleName}") }

        runCatching {
            XposedBridge.hookAllMethods(
                View::class.java, "setVisibility",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) = hit(param, "setVisibility")
                },
            )
        }.onFailure { LogX.w("DIAG hook setVisibility failed: ${it.javaClass.simpleName}") }

        // 面板 = ListView 滚动露出 a32（关闭时仅露底部 131px，打开 = 滚到 position 0）。
        // 这里抓「谁滚的」：所有 AbsListView/ListView 滚动入口都打点（仅诊断窗口内）。
        runCatching {
            XposedBridge.hookAllMethods(
                android.widget.AbsListView::class.java, "setSelectionFromTop",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) = hitList(param, "setSelectionFromTop")
                },
            )
        }.onFailure { LogX.w("DIAG hook setSelectionFromTop failed: ${it.javaClass.simpleName}") }

        runCatching {
            XposedBridge.hookAllMethods(
                android.widget.AbsListView::class.java, "setSelection",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) = hitList(param, "setSelection")
                },
            )
        }.onFailure { LogX.w("DIAG hook setSelection failed: ${it.javaClass.simpleName}") }

        runCatching {
            XposedBridge.hookAllMethods(
                android.widget.AbsListView::class.java, "setSelectionAfterHeaderView",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) = hitList(param, "setSelectionAfterHeaderView")
                },
            )
        }.onFailure { LogX.w("DIAG hook setSelectionAfterHeaderView failed: ${it.javaClass.simpleName}") }

        runCatching {
            XposedBridge.hookAllMethods(
                android.widget.AbsListView::class.java, "smoothScrollToPosition",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) = hitList(param, "smoothScrollToPosition")
                },
            )
        }.onFailure { LogX.w("DIAG hook smoothScrollToPosition failed: ${it.javaClass.simpleName}") }

        runCatching {
            XposedBridge.hookAllMethods(
                android.widget.AbsListView::class.java, "scrollListBy",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) = hitList(param, "scrollListBy")
                },
            )
        }.onFailure { LogX.w("DIAG hook scrollListBy failed: ${it.javaClass.simpleName}") }

        runCatching {
            XposedBridge.hookAllMethods(
                android.widget.AbsListView::class.java, "notifyDataSetChanged",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) = hitList(param, "list-notifyDataSetChanged")
                },
            )
        }.onFailure { LogX.w("DIAG hook list-notify failed: ${it.javaClass.simpleName}") }

        LogX.i("DIAG installed v13 (block-onScroll)")
    }

    private fun hit(param: XC_MethodHook.MethodHookParam, what: String) {
        if (System.currentTimeMillis() > until) return
        val v = param.thisObject as? View ?: return
        if (a32Id == 0) {
            a32Id = runCatching { v.resources.getIdentifier("a32", "id", "com.tencent.mm") }.getOrDefault(0)
        }
        val isA32 = a32Id != 0 && v.id == a32Id
        // 大位移放行：面板整体下移 ~1271px，可能是根视图的 translationY/offset ——不限 id
        val big = when (what) {
            "setTranslationY" -> {
                val to = (param.args[0] as? Float) ?: return
                kotlin.math.abs(to - v.translationY) > 150f
            }
            "offsetTopAndBottom" -> {
                val dy = (param.args[0] as? Int) ?: return
                kotlin.math.abs(dy) > 150
            }
            "layout" -> {
                val bottom = (param.args.getOrNull(3) as? Int) ?: return
                val top = (param.args.getOrNull(1) as? Int) ?: return
                (bottom - top) > 1000 && isA32 || (v.height > 0 && kotlin.math.abs((bottom - top) - v.height) > 300)
            }
            else -> isA32
        }
        if (!isA32 && !big) return
        val stack = Throwable().stackTrace.take(25).joinToString("\n    ") { it.toString() }
        LogX.w(
            "DIAG $what v=${v.javaClass.simpleName}#${Integer.toHexString(v.id)} " +
                "args=${param.args.joinToString()} h=${v.height}\n    $stack"
        )
    }

    /** AbsListView 滚动入口打点：只要会话列表在诊断窗口内被滚动就打出完整栈 */
    private fun hitList(param: XC_MethodHook.MethodHookParam, what: String) {
        if (System.currentTimeMillis() > until) return
        val lv = param.thisObject as? android.widget.AbsListView ?: return
        if (lv !== GroupBar.conversationList()) return
        val stack = Throwable().stackTrace.take(25).joinToString("\n    ") { it.toString() }
        LogX.w(
            "DIAG-LV $what args=${param.args.joinToString()} " +
                "firstPos=${lv.firstVisiblePosition} child0top=${lv.getChildAt(0)?.top}\n    $stack"
        )
    }
}
