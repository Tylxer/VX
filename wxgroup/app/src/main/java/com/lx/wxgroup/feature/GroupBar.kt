package com.lx.wxgroup.feature

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.Group
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.data.PrefKeys
import com.lx.wxgroup.ui.AvatarCompose
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX
import java.util.Collections
import java.util.WeakHashMap

/**
 * M3：分组入口常驻聊天列表。
 *
 * 两种显示模式（kv 表 bar_mode，可随时切换）：
 *  - MODE_LIST（默认，新）：分组行作为**真·列表头**插在聊天列表里，
 *    位置 = 微信自己的 header（如「网页版文件传输助手已打开」通知条）之下、
 *    会话之上；点分组名进二级列表（GroupDetailPage）；滚动/裁剪/命中全原生。
 *  - MODE_BAR（旧）：ActionBar 下沿一条横向胶囊条，常驻不随列表滚动。
 *
 * list 模式为什么能做成真列表头：
 *  微信会话列表的 adapter 是 HeaderViewListAdapter（自带 14 个 header，
 *  通知条就是其中之一）。直接 addHeaderView 无效——adapter 定型之后再加
 *  不会进 adapter（实测 childIndex=-1，界面上不出现）。所以反射往
 *  HeaderViewListAdapter 的 mHeaderViewInfos 里追加我们的 FixedViewInfo：
 *  header 追加在所有既有 header（含通知条）之后，正好是「通知条之下、会话之上」。
 *
 *  原生 header 带来的简化：不需要 canvas 平移让位、不需要触摸重映射、
 *  不需要滚动量跟踪。全部补偿机制只服务于 bar 模式和 header 注入失败的
 *  回退路径，统一由 wantPadTop<=0 / groupsH<=0 控制开关（见各 hook）。
 *
 *  共用坑（bar 模式与回退路径还在用，别乱动）：
 *  - 分组 UI 叠在列表父容器（MainUIView）上时，LayoutParams 要照列表那份复制。
 *  - 微信每次 layout 会把列表 paddingTop / mListPadding 改回去，需 hook
 *    setPadding 拦截 + 反射补 mListPadding。
 *  - canvas.translate 只挪画面不挪布局，回退路径里还要 hook dispatchTouchEvent
 *    平移 y，且滚动采样必须走 trackMotionScroll（dispatchDraw 会漏帧）。
 */
object GroupBar {

    private const val FRAG_MAIN = "com.tencent.mm.ui.conversation.MainUI"
    private const val LIST_CLASS = "com.tencent.mm.ui.conversation.ConversationListView"
    private const val HVL_CLASS = "android.widget.HeaderViewListAdapter"

    /** 顶部横条模式：胶囊横排，常驻 ActionBar 下沿（旧） */
    const val MODE_BAR = "bar"

    /** 列表模式：分组行插进聊天列表顶部，随列表滚动（旧机制：真·header） */
    const val MODE_LIST = "list"

    /**
     * 不注入任何 UI —— 分组行由 `ConvFilter` 在 adapter 内部插入（A1/A2/A6）。
     *
     * 这里仍然**登记列表**（`bars[list]`），因为 `conversationList()` 是
     * `ConvMenu`（A15 长按加入分组）和几个探针发现会话列表的唯一途径，
     * 不能连它一起停掉。只是不再往列表里塞 header / 浮层，避免两套分组行并存。
     */
    const val MODE_NONE = "none"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** listView(弱引用) -> 已注入的 bar。WeakHashMap 避免持有宿主 View 造成泄漏 */
    private val bars = Collections.synchronizedMap(WeakHashMap<View, Bar>())

    /**
     * 我们曾经追加过的所有 FixedViewInfo（弱引用集合）。
     *
     * 从小程序/其它全屏页回来时，微信可能换 adapter 或重建列表，旧的 info
     * 会残留在新的 infos 里 —— 再挂一个就变成两份分组行（界面直接乱掉）。
     * 所以每次注入前先按这个集合把旧的清干净，保证任何时刻至多一个。
     */
    private val ourInfos = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    private class Bar(
        val list: ListView,
        val parent: ViewGroup,
        val root: LinearLayout,
        val lane: LinearLayout,
        val scroller: HorizontalScrollView?,
        val ctx: Context,
        val mode: String,
        /** 分组 UI 挂在哪个 y 上（= ActionBar 下沿）。nativeHeader 时不用 */
        var offset: Int = 0,
        /** 强制保持的列表 paddingTop / mListPadding.top（滚动裁剪边界）。0=禁用全部补偿 hook */
        var wantPadTop: Int = 0,
        /** bar 模式：条自身高度 */
        var barH: Int = 0,
        /** dispatchDraw 里画 child 前的位移量（bar=barH；回退 list=G） */
        var drawShift: Float = 0f,
        /** 触摸重映射：手势 DOWN 时锁定的 drawShift */
        var touchShift: Float = 0f,
        /** list 回退路径：分组行总高 */
        var groupsH: Int = 0,
        /** list 回退路径：已滚出量（像素，0=停在顶部）。锚点增量跟踪 */
        var scrollS: Int = 0,
        /** 滚动锚点：盯住一个会话行，它的 top 变化就是内容滚动量 */
        var anchor: View? = null,
        var anchorLastTop: Int = 0,
        /** 微信会话行的资源 id（cr0），锚点优先用它 */
        var convRowId: Int = 0,
        /** list 原生 header：追加进 HeaderViewListAdapter 的 FixedViewInfo */
        var headerInfo: Any? = null,
        /** list 原生 header：我们改过 infos 的那个 adapter（微信可能换新的，要重挂） */
        var headerAdapter: Any? = null,
    ) {
        /** dispatchTouchEvent hook 里替换进去的事件副本，方法返回后回收 */
        var remapped: MotionEvent? = null

        /** hook 首次命中的日志只打一次，避免刷屏 */
        var touchLogged = false

        /** 分组行当前是否真的以 header 身份在列表里 */
        val nativeHeader: Boolean get() = headerInfo != null

        /** 已被 rebuild 换掉的旧 bar：布局回调/settle 里看到它必须立即收手 */
        var dead: Boolean = false

        /** 上次扫描「置顶聊天折叠状态」的时间戳（节流用） */
        var foldScanMs: Long = 0L

        /** 周期自愈任务（startWatchdog 启动，防重复启动） */
        var watchdog: Runnable? = null

        /** 最近一次判定出的折叠态（null=没探测到，按展开处理） */
        var pinFolded: Boolean? = null

        /** 已真正执行过的折叠态（跟 pinFolded 分开，避免动画期间重复触发） */
        var pinFoldApplied: Boolean? = null

        /** 折叠/展开动画 */
        var foldAnim: android.animation.ValueAnimator? = null

        /**
         * 从真实会话行（cr0）采样来的行背景。
         *
         * 微信会话行自带一层浅色/渐变行背景，画在列表深色底之上；我们的行如果
         * 全透明就会露出列表底色（实测从小程序回来后整个分组区发黑、文字看不清）。
         * 采样原样贴上，深浅模式、动态背景通吃。
         */
        var rowBg: Drawable? = null

        /** rowBg 是否已经采样并套用到行上（防 refresh 循环） */
        var rowBgApplied: Boolean = false
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookFragmentResume(lpparam)
        hookVisibility(lpparam)
        hookSetPadding(lpparam)
        hookDispatchTouch(lpparam)
        hookTrackMotionScroll(lpparam)
        // PanelDiag（下拉面板诊断，hook 了 View.layout / setLayoutParams 等超热路径）
        // 是临时脚手架，已摘除 —— 它 600ms 窗口外虽然只做一次时间比较，但
        // 拦在所有 View 的 layout 上，冷启动时是白白的开销。
    }

    /** 当前已注入的会话列表（给会话长按绑定用） */
    fun conversationList(): ListView? = runCatching {
        bars.keys.firstOrNull() as? ListView
    }.getOrNull()

    private fun hookFragmentResume(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (cls in listOf("androidx.fragment.app.Fragment", "android.support.v4.app.Fragment")) {
            val ok = runCatching {
                XposedHelpers.findAndHookMethod(
                    cls, lpparam.classLoader, "performResume",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching { onMainShown(param.thisObject) }
                        }
                    }
                )
            }.isSuccess
            if (ok) {
                LogX.i("BAR: hooked $cls.performResume")
                return
            }
        }
        LogX.w("BAR: no Fragment class hooked")
    }

    /** 切 Tab 不会重新 performResume，走的是可见性回调 */
    private fun hookVisibility(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (method in listOf("onHiddenChanged", "setUserVisibleHint")) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    "androidx.fragment.app.Fragment", lpparam.classLoader, method,
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching {
                                val v = param.args[0] as? Boolean ?: return
                                val shown = if (method == "onHiddenChanged") !v else v
                                if (!shown) return
                                onMainShown(param.thisObject)
                            }
                        }
                    }
                )
            }.onFailure { LogX.d("BAR: hook Fragment.$method failed") }
        }
    }

    /**
     * 拦截 ConversationListView 的 setPadding，强制把 top 替换成我们要的值。
     *
     * 微信在每次 layout 里都会主动调 setPadding 把列表顶回原位。
     * wantPadTop<=0（原生 header 模式）时不干预。
     */
    private fun hookSetPadding(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.view.View", lpparam.classLoader, "setPadding",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val bar = bars[view] ?: return
                        if (bar.wantPadTop <= 0) return
                        param.args[1] = bar.wantPadTop
                    }
                }
            )
            LogX.i("BAR: hooked View.setPadding")
        }.onFailure { LogX.w("BAR: hook setPadding failed: ${it.javaClass.simpleName}") }

        // 回退路径：画 child 前 canvas.translate(0, drawShift) 让会话内容从分组 UI 下沿开始画
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.view.ViewGroup", lpparam.classLoader, "dispatchDraw",
                android.graphics.Canvas::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val bar = bars[view] ?: return
                        if (bar.wantPadTop <= 0) return
                        if (bar.mode == MODE_LIST && bar.groupsH > 0) {
                            updateScroll(bar)
                            bar.lane.translationY = -bar.scrollS.toFloat()
                            // S>0 时行整体上移，root 底部露出一条真会话区域：
                            // 那块必须放行给列表（看到的本来就是会话）。
                            // S=0 时行铺满 root，root 兜底吃掉行间缝隙的触摸，
                            // 不然按在 1px 分隔线/行底缝上会漏给列表弹微信菜单。
                            bar.root.isClickable = bar.scrollS == 0
                        }
                        val canvas = param.args[0] as android.graphics.Canvas
                        canvas.save()
                        canvas.translate(0f, bar.drawShift)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val bar = bars[view] ?: return
                        if (bar.wantPadTop <= 0) return
                        (param.args[0] as android.graphics.Canvas).restore()
                    }
                }
            )
            LogX.i("BAR: hooked ViewGroup.dispatchDraw")
        }.onFailure { LogX.w("BAR: hook dispatchDraw failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 滚动增量的权威入口（仅回退路径用）。
     *
     * AbsListView.trackMotionScroll 每次滚动都会走（触摸拖动、惯性、smoothScrollBy
     * 全经过它），而 ListView 并非每一帧都调 dispatchDraw，只靠 dispatchDraw 采样
     * 会漏掉一部分位移，S 就跟实际滚动对不上（实测漂移过 170px）。
     */
    private fun hookTrackMotionScroll(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.widget.AbsListView", lpparam.classLoader, "trackMotionScroll",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val bar = bars[view] ?: return
                        if (bar.mode != MODE_LIST || bar.groupsH <= 0) return
                        updateScroll(bar)
                    }
                }
            )
            LogX.i("BAR: hooked AbsListView.trackMotionScroll")
        }.onFailure { LogX.w("BAR: hook trackMotionScroll failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 触摸重映射（仅回退路径用）。
     *
     * dispatchDraw 的 translate 只影响绘制不影响布局：会话画在 492，
     * 但触摸命中算的还是布局位置 333 —— 直接点会点错行。这里在事件进
     * ListView 前把 y 平移 -drawShift，让命中位置和绘制位置对齐。
     */
    private fun hookDispatchTouch(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.view.ViewGroup", lpparam.classLoader, "dispatchTouchEvent",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val bar = bars[view] ?: return
                        if (bar.wantPadTop <= 0) return
                        val ev = param.args[0] as? MotionEvent ?: return
                        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                            bar.touchShift = bar.drawShift
                            if (!bar.touchLogged) {
                                bar.touchLogged = true
                                LogX.i("BAR touch hook live shift=${bar.touchShift.toInt()}")
                            }
                        }
                        if (bar.touchShift == 0f) return
                        val copy = MotionEvent.obtain(ev)
                        copy.setLocation(ev.x, ev.y - bar.touchShift)
                        param.args[0] = copy
                        bar.remapped = copy
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val bar = bars[view] ?: return
                        bar.remapped?.recycle()
                        bar.remapped = null
                    }
                }
            )
            LogX.i("BAR: hooked ViewGroup.dispatchTouchEvent")
        }.onFailure { LogX.w("BAR: hook dispatchTouchEvent failed: ${it.javaClass.simpleName}") }
    }

    private fun onMainShown(fragment: Any?) {
        if (fragment == null) return
        if (fragment.javaClass.name != FRAG_MAIN) return
        // 会话列表是异步填充的，早了拿不到 ListView / ActionBar 还没布局完
        mainHandler.postDelayed({
            runCatching { attach(fragment) }.onFailure { LogX.w("BAR attach failed: ${it.javaClass.simpleName}") }
        }, 2500)
    }

    private fun attach(fragment: Any) {
        val view = fragmentView(fragment) ?: return
        val list = findListView(view) as? ListView ?: return

        val existing = bars[list]
        if (existing != null) {
            ensureAttached(existing)
            refresh(existing)
            return
        }

        val parent = list.parent as? ViewGroup
        if (parent == null) {
            LogX.w("BAR: conversation list has no parent, abort")
            return
        }
        val ctx = HostContext.activity() ?: list.context
        val db = GroupDb.current() ?: return

        // A1/A2/A6 落地后，分组行归 ConvFilter 管（adapter 内插行，能跟会话按时间混排），
        // 这里的 header 横条是旧机制 —— 两套并存会出现两份分组行，所以默认让位。
        // 关掉「启用分组行」开关则退回旧行为，等于留了一条可回退的路。
        val mode = if (runCatching { Kv.getBool(Keys.CHAT_GROUP_ROW_ENABLED) }.getOrDefault(true)) {
            MODE_NONE
        } else {
            db.getPref(PrefKeys.BAR_MODE, MODE_LIST)
        }
        val bar = build(ctx, db, list, parent, mode)

        // cr0 = 微信会话行的资源 id，行背景采样和滚动锚点都靠它（两种模式都要）
        bar.convRowId = runCatching {
            list.resources.getIdentifier("cr0", "id", "com.tencent.mm")
        }.getOrDefault(0)

        if (mode == MODE_NONE) {
            // 只登记、不注入：分组行由 ConvFilter 在 adapter 内插入。
            // 必须在这里 return —— 后面的 place/settle 是给「注入了 UI」的两种模式
            // 做布局补偿的，对本模式没有任何意义。
            bars[list] = bar
            LogX.i("BAR mode=none, ConvFilter owns group rows (list=${list.javaClass.simpleName})")
            return
        }
        if (mode == MODE_LIST && attachHeader(bar)) {
            // 原生 header：分组行就是列表自己的一部分，不需要任何补偿机制。
            // wantPadTop / groupsH 保持 0，全部 hook 自动短路。
            bars[list] = bar
            LogX.i("BAR inject mode=$mode NATIVE header list=${list.javaClass.simpleName}")
        } else {
            // 回退（bar 模式 / header 注入失败）：叠在列表父容器上 + 补偿机制
            val lp = layoutParamsOf(list)
            runCatching {
                val idx = parent.indexOfChild(list)
                parent.addView(bar.root, if (idx >= 0) idx + 1 else -1, lp)
            }.onFailure {
                LogX.w("BAR addView failed: ${it.javaClass.simpleName}, fallback to header")
                runCatching { list.addHeaderView(bar.root, null, false) }
            }
            bars[list] = bar

            bar.offset = anchorTop(ctx, list)
            LogX.i(
                "BAR inject mode=$mode OVERLAY list=${list.javaClass.simpleName} " +
                    "parent=${parent.javaClass.simpleName} offset=${bar.offset}"
            )
        }
        place(bar)
        settle(bar)
        startWatchdog(bar)
    }

    /**
     * 周期自愈：微信刷数据（新消息/adapter 重建）会把我们追加的 header infos 作废，
     * 分组栏随之消失。现有的自愈挂在列表 layout 回调上 —— 列表静止时永远不触发，
     * 实测分组栏能一消失就是几分钟。这里 3s 一次兜底 ensureAttached：
     * header 还在时只是两次字段比较（零成本），丢了才走重挂。
     */
    private fun startWatchdog(bar: Bar) {
        if (bar.watchdog != null) return
        val r = object : Runnable {
            override fun run() {
                if (bar.dead) return
                runCatching {
                    if (bar.list.isAttachedToWindow) ensureAttached(bar)
                }.onFailure { LogX.w("BAR watchdog failed: ${it.javaClass.simpleName}") }
                mainHandler.postDelayed(this, 3000L)
            }
        }
        bar.watchdog = r
        mainHandler.postDelayed(r, 3000L)
    }

    // ---------------------------------------------------------- 原生 header 注入

    /**
     * 把分组行（lane）追加成列表的真 header。
     *
     * 关键：微信的 adapter 已经是 HeaderViewListAdapter，直接 addHeaderView 无效
     * （adapter 定型后再加不进 adapter）。所以反射拿它的 mHeaderViewInfos，
     * 追加一个 FixedViewInfo（view=lane, isSelectable=false）再让列表重新布局。
     * FixedViewInfo 排在既有 header（含「网页版文件传输助手已打开」通知条）
     * 之后 —— 正好是「通知条之下、会话之上」。
     *
     * isSelectable=false 时 HVL.isEnabled(pos)=false，ListView 不会把我们的行
     * 当会话项做点击/长按，行上手势完全归我们自己的 GestureDetector。
     */
    private fun attachHeader(bar: Bar): Boolean {
        val adapter = bar.list.adapter ?: return false
        if (adapter.javaClass.name != HVL_CLASS) {
            LogX.w("BAR header: adapter=${adapter.javaClass.name} not HVL")
            return false
        }
        val ok = runCatching {
            val infos = XposedHelpers.getObjectField(adapter, "mHeaderViewInfos") as? ArrayList<Any>
                ?: return@runCatching false
            if (bar.headerInfo != null) {
                if (infos.contains(bar.headerInfo)) return@runCatching true
                bar.headerInfo = null
            }
            // 先清掉自己之前挂过的（换 adapter / 列表重建后的残留），
            // 否则会出现两份分组行
            val stale = infos.filter { it in ourInfos }
            if (stale.isNotEmpty()) {
                infos.removeAll(stale.toSet())
                ourInfos.removeAll(stale.toSet())
                LogX.w("BAR header cleaned stale=${stale.size}")
            }
            // FixedViewInfo 不能反射 new（部分 API 上没有可访问的构造器，
            // 实测 NoSuchMethodException）。让框架自己造：addHeaderView 会在
            // ListView.mHeaderViewInfos 里 new 一个，借出来改完字段再用。
            // 它此时对 adapter 无效（adapter 已定型），所以不会真的进列表。
            bar.list.addHeaderView(View(bar.ctx), null, false)
            val lvInfos = XposedHelpers.getObjectField(bar.list, "mHeaderViewInfos") as? ArrayList<Any>
            val info = lvInfos?.removeAt(lvInfos.size - 1)
                ?: return@runCatching false
            XposedHelpers.setObjectField(info, "view", bar.lane)
            XposedHelpers.setObjectField(info, "data", null)
            XposedHelpers.setBooleanField(info, "isSelectable", false)
            infos.add(info)
            ourInfos.add(info)
            bar.headerInfo = info
            bar.headerAdapter = adapter
            LogX.i("BAR header appended, infos=${infos.size}")
            true
        }.onFailure {
            LogX.w("BAR header attach failed: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
        if (!ok) return false
        // 原生 header：列表自己管滚动，overlay 补偿字段必须全部归零。
        // 否则 hookTrackMotionScroll / dispatchDraw 的「groupsH > 0」短路条件失效，
        // updateScroll 会把面板开合（LauncherUI 整体平移 ±1271px）等巨量位移灌进
        // scrollS（实测 anchor big delta -922），lane 被平移出屏、分组栏凭空消失。
        bar.groupsH = 0
        bar.wantPadTop = 0
        bar.drawShift = 0f
        bar.scrollS = 0
        bar.offset = 0
        bar.lane.translationY = 0f
        bar.list.requestLayout()
        return true
    }

    /** 从列表头里摘掉分组行（切模式时用） */
    private fun detachHeader(bar: Bar) {
        val info = bar.headerInfo ?: return
        runCatching {
            val adapter = bar.headerAdapter ?: bar.list.adapter
            val infos = adapter?.let {
                XposedHelpers.getObjectField(it, "mHeaderViewInfos") as? ArrayList<Any>
            }
            infos?.remove(info)
            bar.list.requestLayout()
        }.onFailure { LogX.w("BAR header detach failed: ${it.javaClass.simpleName}") }
        ourInfos.remove(info)
        bar.headerInfo = null
        bar.headerAdapter = null
    }

    /**
     * 确保分组行还在列表头里。微信可能重建 adapter（换 Tab、数据刷新），
     * 旧 infos 一起作废，行会从界面上消失——每次补校时查一遍，丢了就重挂。
     */
    private fun ensureAttached(bar: Bar) {
        if (bar.mode != MODE_LIST || bar.dead) return
        runCatching {
            val adapter = bar.list.adapter
            if (bar.nativeHeader) {
                val same = adapter === bar.headerAdapter
                val still = adapter != null && runCatching {
                    (XposedHelpers.getObjectField(adapter, "mHeaderViewInfos") as? ArrayList<Any>)
                        ?.contains(bar.headerInfo) == true
                }.getOrDefault(false)
                if (same && still) return
                // adapter 换了或 infos 被清了：先摘干净再重挂
                bar.headerInfo = null
                bar.headerAdapter = null
                LogX.i("BAR header lost (same=$same still=$still), re-attach")
            }
            if (!attachHeader(bar)) {
                // 原生注入失败（比如微信换了非 HVL adapter）：退回 overlay
                if (bar.root.parent == null) {
                    val lp = layoutParamsOf(bar.list)
                    bar.parent.addView(bar.root, bar.parent.indexOfChild(bar.list) + 1, lp)
                    bar.convRowId = runCatching {
                        bar.list.resources.getIdentifier("cr0", "id", "com.tencent.mm")
                    }.getOrDefault(0)
                    bar.offset = anchorTop(bar.ctx, bar.list)
                    place(bar)
                    LogX.w("BAR header re-attach failed, fallback to overlay")
                }
            }
        }.onFailure { LogX.w("BAR ensureAttached failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 分组行跟随微信「置顶聊天」的折叠状态一起折叠。
     *
     * 微信两态实测（都是列表里的 cr0 折叠条目，两者互斥出现）：
     *  - 展开态：置顶区底部有一行「折叠置顶聊天」（点了收起）
     *  - 折叠态：列表顶部有一行「N个置顶聊天」（点了展开）
     * 所以扫一遍列表里的文字就能判定，不需要碰微信的内部开关。
     * 探不到（比如没置顶会话）就按展开处理，宁可多显示也别把入口藏了。
     *
     * 只在状态翻转时改 visibility + requestLayout，HeaderViewListAdapter
     * 会把 GONE 的 header 按 0 高度处理，列表自己重新排 —— 原生收起。
     */
    private fun syncPinFold(bar: Bar) {
        if (bar.mode != MODE_LIST || bar.nativeHeader.not()) return
        // 节流 100ms：滚动时布局回调很密，逐帧扫整棵树没必要；100ms 的
        // 判定延迟肉眼无感，能保证「点折叠的同一瞬间分组行就跟着收」
        val now = System.currentTimeMillis()
        if (now - bar.foldScanMs < 100) return
        bar.foldScanMs = now

        var folded: Boolean? = null
        walkTexts(bar.list) { text ->
            if (text == "折叠置顶聊天") folded = false
            else if (text.length <= 12 && text.endsWith("个置顶聊天")) folded = true
        }
        bar.pinFolded = folded
        val wantFold = folded == true
        if (bar.pinFoldApplied != wantFold) {
            val first = bar.pinFoldApplied == null
            bar.pinFoldApplied = wantFold
            if (first) {
                // 首次判定直接落到目标态，别在启动瞬间放一段收起动画
                bar.lane.visibility = View.VISIBLE
                val lp = bar.lane.layoutParams
                if (lp != null) {
                    lp.height = if (wantFold) FOLDED_H else ViewGroup.LayoutParams.WRAP_CONTENT
                    bar.lane.layoutParams = lp
                }
                bar.lane.requestLayout()
            } else {
                applyFold(bar, wantFold)
            }
            LogX.i("BAR pinFold folded=$wantFold -> ${if (wantFold) "collapse" else "expand"} ${FOLD_MS}ms")
        }
    }

    /** 折叠动画时长，跟微信自己的折叠动画大致同步 */
    private const val FOLD_MS = 340L

    /**
     * 折叠收完后 lane 保留的高度。
     *
     * 不能是 0 也不能是 GONE —— ListView.setupChild 是按
     *   lp.height > 0 ? EXACTLY(lp.height) : UNSPECIFIED
     * 决定 header 测量方式的，0 和 GONE 都会退回 UNSPECIFIED，按自然高度
     * （1040px）预留位置，结果就是折叠后原地留一大块空白（实测 cr0 折叠前后
     * 都停在 1546）。留 1px 走 EXACTLY 分支，位置才真正被回收；这 1px 由
     * ViewGroup 默认的 clipChildren 裁掉，视觉上不存在。
     */
    private const val FOLDED_H = 1

    /**
     * 折叠/展开动画：逐帧改 lane 的 LayoutParams.height（ListView 跟着重排）。
     *
     * 直接切可见性会「跳」一下，跟置顶区的折叠动画对不上——要的是「一起折叠」，
     * 所以做高度动画，视觉上两块同时收。
     */
    private fun applyFold(bar: Bar, fold: Boolean) {
        bar.foldAnim?.cancel()
        val lane = bar.lane
        val full = naturalHeight(bar).coerceAtLeast(FOLDED_H)
        val from = lane.height.takeIf { it > 0 } ?: if (fold) full else FOLDED_H
        val to = if (fold) FOLDED_H else full
        lane.visibility = View.VISIBLE

        val anim = android.animation.ValueAnimator.ofInt(from, to).apply {
            duration = FOLD_MS
            addUpdateListener { a ->
                val lp = lane.layoutParams ?: return@addUpdateListener
                lp.height = (a.animatedValue as Int).coerceAtLeast(FOLDED_H)
                lane.layoutParams = lp
                lane.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    val lp = lane.layoutParams
                    if (lp != null) {
                        lp.height = if (fold) FOLDED_H else ViewGroup.LayoutParams.WRAP_CONTENT
                        lane.layoutParams = lp
                    }
                    lane.requestLayout()
                }
            })
        }
        bar.foldAnim = anim
        anim.start()
    }

    /** lane 的自然高度（折叠态下 height 为 0，得主动量一次） */
    private fun naturalHeight(bar: Bar): Int {
        val w = bar.list.width.takeIf { it > 0 } ?: bar.ctx.resources.displayMetrics.widthPixels
        return runCatching {
            bar.lane.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            bar.lane.measuredHeight
        }.getOrDefault(0)
    }

    /**
     * 原生 header 模式下，lane 高度变化必须**渐变**不能跳变。
     *
     * 筛选 OFF→ON 时 lane 顶部插入「✕ 显示全部会话」行（±260px），列表顶部的
     * 内容瞬间整体下移 —— 微信的下拉快捷面板把这个单帧 260px 的下移当成了
     * 下拉手势，面板会自己弹出来（实测 4/4 复现；而 ON→OFF 内容上移、
     * ON→ON 高度不变都从不触发 —— 只有「下移」触发）。
     * 把高度变化摊到 ~240ms（每帧 ~25px，量级低于拖动判定阈值），面板不再误触发。
     * 与 pinFold 的 applyFold 共用 foldAnim 字段互斥，避免两段动画打架。
     */
    private fun animateLaneHeight(bar: Bar) {
        val lane = bar.lane
        val h1 = lane.height.takeIf { it > 0 } ?: return  // 首次挂载没有旧高度，无所谓跳变
        val h2 = naturalHeight(bar).takeIf { it > 0 } ?: return
        if (h1 == h2) return
        // v8 修复（DIAG v6/v7 定论）：面板 item a32 恒高 1920px，关闭态列表滚到只露底部 131px；
        // lane 长高 delta 会在布局时把内容下推 delta → a32 露出 >阈值 → 微信 onScroll 自动播放
        // 1271px 面板展开动画。补偿必须发生在微信 onScroll 观察到之前 —— 所以**先滚后长**：
        // 先 scrollListBy(delta)（此刻 strip=131-delta，低于阈值，不会触发面板），
        // 再把 lane 长到新高度（布局后 strip 恰好回到 131）。lane 变短无需补偿（内容上移，
        // a32 露得更少，永远到不了阈值）。
        bar.foldAnim?.cancel()
        val lp = lane.layoutParams
        if (lp != null && lp.height != h2) {
            val delta = h2 - h1
            val list = bar.list
            if (delta > 0) {
                runCatching { if (list.firstVisiblePosition == 0) list.scrollListBy(delta) }
            }
            lp.height = h2
            lane.layoutParams = lp
            lane.requestLayout()
        }
    }

    /** 遍历视图树读所有 TextView 的文字（只看 6 层，列表行的子树足够浅） */
    private fun walkTexts(root: View, depth: Int = 0, onText: (String) -> Unit) {
        if (depth > 6) return
        if (root is TextView) {
            val t = root.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) onText(t)
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i)
                if (c != null && c.visibility == View.VISIBLE) walkTexts(c, depth + 1, onText)
            }
        }
    }

    /**
     * 从列表里第一个可见的会话行（cr0）采样行背景。
     *
     * 微信会话行自带浅色/渐变行背景，画在列表深色底之上；我们的分组行全透明
     * 会露出深色底（实测从小程序回来后整个分组区发黑）。采样原样贴上，
     * 深浅模式、动态背景通吃，不用自己猜颜色。
     */
    private fun sampleRowBg(bar: Bar) {
        if (bar.rowBg != null || bar.convRowId == 0) return
        for (i in 0 until bar.list.childCount) {
            val c = bar.list.getChildAt(i) ?: continue
            if (c.id != bar.convRowId || c.height <= 0) continue
            val d: Drawable = findFirstBg(c, 0) ?: continue
            val copy: Drawable = runCatching {
                d.constantState?.newDrawable(bar.ctx.resources) ?: d
            }.getOrDefault(d)
            bar.rowBg = copy
            LogX.i("BAR rowBg sampled: ${copy.javaClass.simpleName}")
            return
        }
    }

    /** 找视图树上第一层非空背景（cr0 根上没有就往里找一层） */
    private fun findFirstBg(v: View?, depth: Int): Drawable? {
        if (v == null || depth > 4) return null
        val own = v.background
        if (own != null) return own
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val hit = findFirstBg(v.getChildAt(i), depth + 1)
                if (hit != null) return hit
            }
        }
        return null
    }

    /** 切换显示模式（横条 <-> 列表行）。持久化 + 已注入的界面立即重建。 */
    fun setMode(mode: String) {
        if (mode != MODE_BAR && mode != MODE_LIST) return
        val db = GroupDb.current() ?: return
        db.setPref(PrefKeys.BAR_MODE, mode)
        LogX.i("BAR setMode=$mode")
        mainHandler.post {
            bars.values.toList().forEach { bar ->
                runCatching { rebuild(bar, mode) }
                    .onFailure { LogX.w("BAR rebuild failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    private fun rebuild(old: Bar, mode: String) {
        if (old.mode == mode) {
            refresh(old)
            return
        }
        // 先作废旧 bar：detachHeader 触发的 layout 会让旧布局回调跑起来，
        // 不拦住的话 ensureAttached 会立刻把刚摘掉的 header 又挂回去
        // （实测 infos 15→16，列表里出现两份分组行）。
        old.dead = true
        detachHeader(old)
        runCatching { (old.root.parent as? ViewGroup)?.removeView(old.root) }
        bars.remove(old.list)
        val db = GroupDb.current() ?: return
        val nb = build(old.ctx, db, old.list, old.parent, mode)
        bars[old.list] = nb
        if (mode == MODE_LIST && attachHeader(nb)) {
            LogX.i("BAR rebuild -> $mode NATIVE")
        } else {
            val lp = layoutParamsOf(old.list)
            runCatching {
                val idx = old.parent.indexOfChild(old.list)
                old.parent.addView(nb.root, if (idx >= 0) idx + 1 else -1, lp)
            }.onFailure { LogX.w("BAR rebuild addView failed: ${it.javaClass.simpleName}") }
            nb.convRowId = runCatching {
                old.list.resources.getIdentifier("cr0", "id", "com.tencent.mm")
            }.getOrDefault(0)
            nb.offset = old.offset
            LogX.i("BAR rebuild -> $mode OVERLAY")
        }
        place(nb)
        settle(nb)
    }

    /**
     * 分段补校：ActionBar 晚布局、会话异步填充、微信换 adapter，都可能让
     * 第一帧不齐。隔一段时间补几次，每次都是幂等操作。
     */
    private fun settle(bar: Bar) {
        for (t in intArrayOf(600, 1500, 3000, 5000, 8000)) {
            mainHandler.postDelayed({
                runCatching {
                    if (bar.dead) return@runCatching
                    ensureAttached(bar)
                    if (bar.mode == MODE_BAR || !bar.nativeHeader) reanchor(bar)
                    LogX.i(
                        "BAR settle t=$t mode=${bar.mode} native=${bar.nativeHeader} " +
                            "padTop=${bar.list.paddingTop} firstPos=${bar.list.firstVisiblePosition} " +
                            "offset=${bar.offset} groupsH=${bar.groupsH} " +
                            "drawShift=${bar.drawShift.toInt()} laneTrans=${bar.lane.translationY.toInt()}"
                    )
                }.onFailure { LogX.w("BAR settle failed: ${it.javaClass.simpleName}") }
            }, t.toLong())
        }
    }

    /** 分组 UI 该挂的 y：ActionBar 下沿。量不到就退回「状态栏 + 56dp」 */
    private fun anchorTop(ctx: Context, list: View): Int {
        val sb = statusBarHeight(ctx)
        val fallback = sb + UiKit.dp(ctx, 56)
        val decor = runCatching { HostContext.activity()?.window?.decorView }.getOrNull()
            ?: return fallback
        var best = 0
        val loc = IntArray(2)
        fun walk(v: View, depth: Int) {
            if (depth > 12) return
            val id = v.id
            if (id != View.NO_ID && v.visibility == View.VISIBLE && v.height > 0) {
                runCatching {
                    val name = v.resources.getResourceName(id).lowercase()
                    if (name.contains("actionbar") || name.contains("action_bar") || name.contains("toolbar")) {
                        v.getLocationOnScreen(loc)
                        val bottom = loc[1] + v.height
                        // 只认屏幕上半部分那一根，别把底部 TabBar 之类的算进来
                        if (bottom in (sb + 1)..(sb + 400) && bottom > best) best = bottom
                    }
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) v.getChildAt(i)?.let { walk(it, depth + 1) }
            }
        }
        runCatching { walk(decor, 0) }.onFailure { LogX.w("BAR anchor walk failed: ${it.javaClass.simpleName}") }
        return if (best > 0) best else fallback
    }

    private fun reanchor(bar: Bar) {
        val next = anchorTop(bar.ctx, bar.list)
        if (next != bar.offset) {
            bar.offset = next
            LogX.i("BAR reanchor offset=${bar.offset}")
        }
        place(bar)
    }

    // ---------------------------------------------------------------- 构建

    private fun build(ctx: Context, db: GroupDb, list: ListView, parent: ViewGroup, mode: String): Bar {
        val root: LinearLayout
        val lane: LinearLayout
        var scroller: HorizontalScrollView? = null
        if (mode == MODE_BAR) {
            root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                // 叠在会话列表上层，背景必须不透明，否则会透出下面滚动的会话
                background = UiKit.pill(UiKit.bgColor(ctx), radiusPx = 0f)
            }
            lane = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(UiKit.dp(ctx, 10), 0, UiKit.dp(ctx, 10), 0)
            }
            scroller = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
                // 高度写死在 44dp：单行胶囊本来就这个高，写死之后父容器测不测都在
                val laneLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, UiKit.dp(ctx, 44)
                )
                addView(lane, laneLp)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 44)
                )
            }
            root.addView(scroller)
            // 条子下沿一条细分隔线，跟下面的会话列表划清界限
            root.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(0x1A888888)
                setOnTouchListener { _, _ -> true }
            })
        } else {
            // 列表模式：root 是 lane 的容器。原生 header 时 root 不上屏（lane 直接
            // 进列表）；回退 overlay 时 root 钉在 ActionBar 下沿、高度=G，lane 在
            // 里面整体上移模拟「跟列表一起滚出去」，超出 root 顶边的部分被裁掉。
            // 背景透明：行滚出去后露出来的必须是会话内容。
            root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            lane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            root.addView(
                lane,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val bar = Bar(list, parent, root, lane, scroller, ctx, mode)
        // 微信随时可能重新布局列表。布局回调里顺带确认原生 header 还挂在
        // adapter 上：微信换 adapter / 刷数据会把我们追加的 infos 作废，
        // 分组行会从界面上消失（实测大行程滚动后就再也没回来），所以每次
        // 布局都要查一遍、丢了就重挂——这是持续自愈，不是只靠启动后那 8 秒。
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (bar.dead) return@addOnLayoutChangeListener
            if (bar.mode == MODE_LIST) {
                ensureAttached(bar)
                // 会话行背景是异步才有的（列表填充后），采到就套用一次
                if (!bar.rowBgApplied) {
                    sampleRowBg(bar)
                    if (bar.rowBg != null) {
                        bar.rowBgApplied = true
                        refresh(bar)
                    }
                }
                syncPinFold(bar)
            }
            // 会话过滤包装会被微信重建列表时丢掉，这里做低成本自愈（已装好时零成本返回）
            ConvFilter.ensure(list)
            place(bar)
        }
        refresh(bar)
        return bar
    }

    /**
     * 定位 + 让位（overlay 路径）。原生 header 模式下列表自己管一切，直接跳过。
     */
    private fun place(bar: Bar) {
        runCatching {
            if (bar.mode == MODE_LIST && bar.nativeHeader) return
            if (!bar.root.isAttachedToWindow) return
            if (bar.mode == MODE_LIST && bar.nativeHeader) return
            // 1. 钉在 ActionBar 下沿
            bar.root.translationY = bar.offset.toFloat()

            if (bar.mode == MODE_BAR) {
                val h = barHeight(bar)
                if (h <= 0) return
                bar.barH = h
                bar.drawShift = h.toFloat()
                // 裁剪边界 = 分组条下沿：内容画在条下面，滚动时裁在条外
                bar.wantPadTop = bar.offset + h
            } else {
                if (bar.groupsH <= 0) return
                bar.drawShift = bar.groupsH.toFloat()
                // 裁剪边界 = ActionBar 下沿：滚上去的内容藏进 ActionBar 后面
                bar.wantPadTop = bar.offset
                bar.lane.translationY = -bar.scrollS.toFloat()
            }

            val want = bar.wantPadTop
            if (bar.list.paddingTop != want) {
                bar.list.setPadding(bar.list.paddingLeft, want, bar.list.paddingRight, bar.list.paddingBottom)
            }
            setMListPaddingTop(bar.list, want)
            bar.list.clipToPadding = true
        }.onFailure { LogX.w("BAR place failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 跟踪列表已滚出量（像素）。仅回退路径使用。
     *
     * 不能用 getChildAt(0).top：第一个 child 是微信常驻 -2700px 的离屏 header。
     * 做锚点增量跟踪：盯住一个会话行（cr0），它每帧 top 的变化量就是内容
     * 这一帧滚动的像素量；锚点被回收换绑时丢掉跳变重新锚定，S 连续不丢。
     */
    private fun updateScroll(bar: Bar) {
        val list = bar.list

        // 绝对校准（自愈）：列表真的停在顶部（firstPos==0 且首个可见内容 child
        // 的 top == mListPadding.top）时 S 必为 0。增量是相对量，误差会累积，
        // S 一旦卡在 >0，分组行就永远回不到顶部。顶部是唯一能绝对判定滚动
        // 位置的状态，在这里强制归零。
        val padTop = bar.wantPadTop
        // firstVisiblePosition==0 是「真的停在顶部」的必要条件：
        // 只凭「某个 child 的 top 恰好等于 padTop」归零会误判——滚到中间时
        // 完全可能有任意一行的 top 正好等于 333，一归零分组行就会跳回原位。
        if (padTop > 0 && bar.groupsH > 0 && list.firstVisiblePosition == 0) {
            var visTop = -1
            for (i in 0 until list.childCount) {
                val c = list.getChildAt(i) ?: continue
                if (c.height > 0 && c.top >= padTop && (visTop < 0 || c.top < visTop)) visTop = c.top
            }
            if (visTop == padTop && bar.scrollS != 0) {
                LogX.i("BAR scroll reset S=${bar.scrollS} -> 0")
                bar.scrollS = 0
                bar.lane.translationY = 0f
                reanchorScroll(bar)
                return
            }
        }

        val a = bar.anchor
        if (a != null) {
            for (i in 0 until list.childCount) {
                if (list.getChildAt(i) === a) {
                    val delta = a.top - bar.anchorLastTop
                    // 锚点被 ListView 回收复用后会瞬移到另一个位置，此时的 delta 不是滚动量。
                    // 正常滚动一帧位移远小于一屏，超过一屏就判定为换绑，丢掉这次增量重新锚定。
                    if (kotlin.math.abs(delta) > list.height && list.height > 0) {
                        LogX.w("BAR anchor jump ${bar.anchorLastTop} -> ${a.top}, re-anchor (S=${bar.scrollS})")
                        reanchorScroll(bar)
                        return
                    }
                    if (delta != 0) {
                        // 漂移诊断：增量异常大（但没到换绑阈值）时留痕
                        if (kotlin.math.abs(delta) > 200) {
                            LogX.w("BAR anchor big delta $delta (${bar.anchorLastTop} -> ${a.top}) S=${bar.scrollS}")
                        }
                        bar.scrollS = (bar.scrollS - delta).coerceIn(0, bar.groupsH)
                        bar.anchorLastTop = a.top
                    }
                    return
                }
            }
        }
        reanchorScroll(bar)
    }

    /** 换滚动锚点：优先会话行，退而求其次第一个完整可见的 child。S 保持不变 */
    private fun reanchorScroll(bar: Bar) {
        val list = bar.list
        var na: View? = null
        if (bar.convRowId != 0) {
            for (i in 0 until list.childCount) {
                val c = list.getChildAt(i) ?: continue
                if (c.id == bar.convRowId && c.height > 0) {
                    na = c
                    break
                }
            }
        }
        if (na == null) {
            for (i in 0 until list.childCount) {
                val c = list.getChildAt(i) ?: continue
                if (c.height > 0 && c.top >= 0) {
                    na = c
                    break
                }
            }
        }
        bar.anchor = na
        bar.anchorLastTop = na?.top ?: 0
        if (na != null) {
            LogX.i("BAR re-anchor top=${na.top} convRow=${na.id == bar.convRowId} S=${bar.scrollS}")
        }
    }

    /**
     * 反射设置 AbsListView.mListPadding.top（滚动时的内容裁剪边界）。
     */
    private fun setMListPaddingTop(list: ListView, top: Int) {
        runCatching {
            var cls: Class<*>? = list.javaClass
            var field: java.lang.reflect.Field? = null
            var guard = 0
            while (cls != null && guard++ < 10) {
                field = runCatching { cls!!.getDeclaredField("mListPadding") }.getOrNull()
                if (field != null) break
                cls = cls.superclass
            }
            if (field == null) {
                LogX.w("BAR: mListPadding field not found")
                return
            }
            field.isAccessible = true
            val rect = field.get(list) as? android.graphics.Rect ?: return
            if (rect.top != top) {
                val old = rect.top
                rect.top = top
                list.requestLayout()
                LogX.i("BAR mListPadding.top $old -> $top")
            }
        }.onFailure { LogX.w("BAR setMListPadding failed: ${it.javaClass.simpleName}") }
    }

    /**
     * bar 模式分组条的实际高度。
     *
     * 父容器 MainUIView 是微信自定义的 ViewGroup，偶尔不测量我们这个 child，
     * 导致 height 一直是 0 —— 那样会话就让不了位。所以拿不到布局高度时主动 measure 一次。
     */
    private fun barHeight(bar: Bar): Int {
        val h = bar.root.height
        if (h > 0) return h
        val w = bar.list.width.takeIf { it > 0 } ?: bar.ctx.resources.displayMetrics.widthPixels
        runCatching {
            bar.root.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            return bar.root.measuredHeight
        }
        return 0
    }

    /**
     * 造一个父容器能接受的 LayoutParams。
     *
     * 直接 new ViewGroup.LayoutParams 在自定义 ViewGroup 里会被 checkLayoutParams 拒掉
     * （MainUIView 就是自定义的），所以照着列表自己那份的类型复制一个。
     */
    private fun layoutParamsOf(list: View): ViewGroup.LayoutParams {
        val width = ViewGroup.LayoutParams.MATCH_PARENT
        val height = ViewGroup.LayoutParams.WRAP_CONTENT
        runCatching {
            val src = list.layoutParams ?: return@runCatching
            val ctor = src.javaClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val made = ctor.newInstance(width, height)
            if (made is ViewGroup.LayoutParams) return made
        }
        return ViewGroup.LayoutParams(width, height)
    }

    /** 重画分组 UI（两种模式各自的 lane 内容） */
    private fun refresh(bar: Bar) {
        val db = GroupDb.current() ?: return
        val ctx = bar.ctx
        val groups = db.listGroups()

        bar.lane.removeAllViews()
        if (bar.mode == MODE_BAR) {
            if (groups.isEmpty()) {
                bar.lane.addView(chip(ctx, "还没有分组", false) { showMenu(bar) })
            } else {
                for (g in groups) {
                    bar.lane.addView(groupChip(ctx, db, bar, g))
                }
                bar.lane.addView(chip(ctx, "⋯", false) { showMenu(bar) })
            }
            LogX.i(
                "BAR refresh(bar) groups=${groups.size} scrollX=${bar.scroller?.scrollX} " +
                    "laneW=${bar.lane.width}"
            )
        } else {
            val filterId = ConvFilter.currentGroupId()
            if (groups.isEmpty()) {
                bar.lane.addView(hintRow(ctx, bar))
            } else {
                // 「退出筛选」行**常驻**（v9）：筛选 ON/OFF 之间 lane 高度不再变化 ——
                // 高度跳变会把面板 item 露出超阈值，微信 onScroll 会自动展开下拉面板
                // （DIAG v6 栈实锤）。OFF 态该行显示为置灰的「全部会话」，点击无操作。
                bar.lane.addView(clearFilterRow(ctx, bar, filterId > 0))
                bar.lane.addView(hairline(ctx))
                for (g in groups) {
                    bar.lane.addView(groupRow(ctx, bar, db, g))
                    bar.lane.addView(hairline(ctx))
                }
            }
            // 回退路径：分组行总高必须量准（drawShift / 触摸重映射都依赖它）
            if (!bar.nativeHeader) {
                val w = bar.list.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
                runCatching {
                    bar.lane.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    )
                }
                bar.groupsH = if (bar.lane.measuredHeight > 0) bar.lane.measuredHeight else 0
            }
            LogX.i(
                "BAR refresh(list) groups=${groups.size} native=${bar.nativeHeader} G=${bar.groupsH}"
            )
            // 行内容变了：原生 header 需要让列表重新测量。
            // 原生 header 下高度跳变必须平滑过渡（animateLaneHeight），直接跳变
            // 会触发微信下拉快捷面板（见该函数注释）。
            if (bar.nativeHeader) {
                animateLaneHeight(bar)
                bar.list.requestLayout()
            }
            // 面板锚点维护：OFF 态记录基线，ON 态把面板 item 滚回关闭位
            mainHandler.post { runCatching { reanchorPanel(bar.list) } }
        }
        // 高度/内容变了，位置和让位位移都要重算
        bar.root.post { place(bar) }
    }

    // ---------------------------------------------------------------- 列表模式 UI

    /**
     * 列表模式：一个分组 = 一条微信原生会话行。
     *
     * 尺寸全部对齐原生 cr0 行（实测 259px ≈ 72dp）：48dp 复合头像、
     * 17sp 标题、右侧 12sp 时间、分割线从头像右缘开始内缩。
     * 副标题按需求去掉，标题垂直居中。
     *
     * 原生 header 模式下行底色必须**透明**：微信自己的会话行也是透明的，
     * 聊天背景（动态渐变）从底下透出来 —— 不透明白底在深色聊天背景/深色
     * 模式下会变成一块突兀的色带（实测从小程序回来后整块发黑）。
     * 不透明底色只有 overlay 回退路径需要（盖住滚动瞬间的重叠）。
     */
    private fun groupRow(ctx: Context, bar: Bar, db: GroupDb, g: Group): View {
        val members = runCatching { db.membersOf(g.id) }.getOrDefault(emptyList())
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (bar.nativeHeader) {
                // 跟会话行同款背景（运行时采样），深浅模式/动态背景自动适配
                bar.rowBg?.let { background = it }
            } else {
                // 行本身必须不透明：往下滚的瞬间行和内容有一帧重叠，靠它盖住
                setBackgroundColor(UiKit.bgColor(ctx))
            }
            setPadding(UiKit.dp(ctx, 16), 0, UiKit.dp(ctx, 16), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 72)
            )
        }
        row.addView(AvatarCompose.build(ctx, members))
        val selected = ConvFilter.currentGroupId() == g.id
        row.addView(TextView(ctx).apply {
            text = g.name
            textSize = 17f
            // 选中态用品牌绿标出来，和上面那条「✕ 显示全部会话」一起构成筛选的可见状态
            setTextColor(if (selected) UiKit.brandGreen() else UiKit.fgColor(ctx))
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (selected) {
            row.addView(TextView(ctx).apply {
                text = "✓"
                textSize = 16f
                setTextColor(UiKit.brandGreen())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = UiKit.dp(ctx, 6) }
            })
        }
        row.addView(TextView(ctx).apply {
            text = timeLabel(db, g)
            textSize = 12f
            setTextColor(UiKit.muted())
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = UiKit.dp(ctx, 8) }
        })
        attachRowGestures(
            row, bar,
            // 单击 = 只看该组的会话（再点一次取消）；二级分组页在长按菜单的「查看成员」里
            onTap = { ConvFilter.toggle(g.id) },
            onLong = { rowMenu(ctx, bar, g) },
        )
        return row
    }

    /**
     * 「退出筛选」行 —— **常驻**（v9）。
     *
     * 筛选是持久化的（重启微信仍在筛选），所以必须有一条**显式**的退出入口 ——
     * 否则用户会以为会话被删了。
     *
     * v9 起无论筛选状态如何都保留此行：筛选 ON/OFF 切换时 lane 高度保持不变，
     * 否则高度跳变会让微信把面板 item 的短暂露出当成下拉手势，自动展开
     * 下拉快捷面板（实测 3/3 复现）。OFF 态显示为置灰指示，点击无操作。
     */
    private fun clearFilterRow(ctx: Context, bar: Bar, active: Boolean): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (bar.nativeHeader) {
                bar.rowBg?.let { background = it }
            } else {
                setBackgroundColor(UiKit.bgColor(ctx))
            }
            setPadding(UiKit.dp(ctx, 16), 0, UiKit.dp(ctx, 16), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 52)
            )
        }
        row.addView(TextView(ctx).apply {
            text = if (active) "✕ 显示全部会话" else "全部会话"
            textSize = 15f
            setTextColor(if (active) UiKit.brandGreen() else UiKit.muted())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        attachRowGestures(
            row, bar,
            onTap = { if (active) ConvFilter.setGroup(ConvFilter.NONE) },
            onLong = null,
        )
        return row
    }

    /**
     * 行右侧的时间（微信会话行的时间风格）：今天显示时刻、昨天显示「昨天」、
     * 一周内显示星期、更早显示日期。「分组最近更新」= 最后加人的时间，
     * 没有成员时退回分组创建时间。
     */
    private fun timeLabel(db: GroupDb, g: Group): String {
        val ts = runCatching {
            db.membersOf(g.id).maxOfOrNull { it.addedAt }
        }.getOrDefault(null) ?: g.createdAt
        if (ts <= 0L) return ""
        val cal = java.util.Calendar.getInstance()
        val now = cal.timeInMillis
        val dayStart = cal.apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val yesterdayStart = dayStart - 86_400_000L
        return when {
            ts >= dayStart -> {
                val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                f.format(java.util.Date(ts))
            }
            ts >= yesterdayStart -> "昨天"
            ts >= dayStart - 6 * 86_400_000L -> {
                val f = java.text.SimpleDateFormat("EEEE", java.util.Locale.CHINA)
                f.format(java.util.Date(ts))
            }
            else -> {
                val f = java.text.SimpleDateFormat("M/d", java.util.Locale.getDefault())
                f.format(java.util.Date(ts))
            }
        }
    }

    /** 列表模式空态：一条提示行，点它出菜单 */
    private fun hintRow(ctx: Context, bar: Bar): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (bar.nativeHeader) {
                bar.rowBg?.let { background = it }
            } else {
                setBackgroundColor(UiKit.bgColor(ctx))
            }
            setPadding(UiKit.dp(ctx, 14), 0, UiKit.dp(ctx, 14), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 52)
            )
        }
        row.addView(TextView(ctx).apply {
            text = "还没有分组，点这里新建"
            textSize = 15f
            setTextColor(UiKit.muted())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        attachRowGestures(row, bar, onTap = { rowMenu(ctx, bar, null) })
        return row
    }

    /**
     * 行间分隔线。微信原生会话的分割线不是通栏的，是从头像右缘开始内缩
     * （16 内边距 + 48 头像 + 12 间距 = 76dp），照抄才不像外来物。
     */
    private fun hairline(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { leftMargin = UiKit.dp(ctx, 76) }
        setBackgroundColor(0x0F000000)
        // 分隔线自己不吃事件的话会漏给下面的列表（长按缝隙=长按了真会话）
        setOnTouchListener { _, _ -> true }
    }

    /**
     * 分组行的手势三合一：
     *  - 单击 → 打开分组页（二级聊天列表）
     *  - 长按 → 菜单（新建 / 成员管理 / 管理 / 切模式）
     *  - 拖动 → 转发给会话列表。行是列表的 child，但它把 DOWN 消费掉了，
     *    ListView 收不到后续 MOVE，不转发的话拖行不会滚。确认是拖动（过了
     *    touch slop）后先补一个合成 DOWN，再把后续 MOVE/UP 原样喂给列表 ——
     *    拖行 = 拖列表，惯性滚动全是原生的。
     *    坐标不做手动换算：回退路径的 dispatchTouchEvent hook 会统一平移；
     *    原生 header 模式 drawShift=0，喂进去的就是原始坐标。
     */
    private fun attachRowGestures(row: View, bar: Bar, onTap: () -> Unit, onLong: (() -> Unit)? = null) {
        val gd = GestureDetector(row.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                onLong?.invoke()
            }
        })
        // 消费掉整段触摸，不做任何转发。
        //
        // 早前这里有一段 onScroll -> dispatchTouchEvent(list) 的「转发滚动」逻辑，
        // 想让用户能在分组行上滑动列表。但它把 tap 的手指轻微抖动也当成了滚动转发
        // 给 ListView，微信收到后会把点击判到自己的搜索 header 上、直接打开搜索页
        // （用户报的「点 G1 界面不正常」）。分组行总共就两三行，滚动列表用下面
        // 的会话区即可，没必要为了这点滚动冒误触搜索的险 —— 干脆只认 tap / 长按。
        row.setOnTouchListener { _, ev ->
            gd.onTouchEvent(ev)
            true
        }
    }

    /** 列表模式长按菜单；g == null 时是空态提示行点出来的 */
    private fun rowMenu(ctx: Context, bar: Bar, g: Group?) {
        val filtered = ConvFilter.currentGroupId() > 0
        val items = if (g != null) {
            // 单击已经是「只看该组会话」了，二级分组页挪到菜单里（用户要的入口别丢）
            if (filtered) {
                arrayOf("显示全部会话", "查看成员", "新建分组", "成员管理", "管理分组…", "切换为顶部横条")
            } else {
                arrayOf("查看成员", "新建分组", "成员管理", "管理分组…", "切换为顶部横条")
            }
        } else {
            if (filtered) {
                arrayOf("显示全部会话", "新建分组", "管理分组…", "切换为顶部横条")
            } else {
                arrayOf("新建分组", "管理分组…", "切换为顶部横条")
            }
        }
        android.app.AlertDialog.Builder(ctx)
            .setItems(items) { _, which ->
                when (items[which]) {
                    "显示全部会话" -> ConvFilter.setGroup(ConvFilter.NONE)
                    "查看成员" -> if (g != null) GroupDetailPage.open(ctx, g.id, g.name)
                    "新建分组" -> createGroup(ctx, bar)
                    "成员管理" -> if (g != null) MemberPage.show(g.id, g.name)
                    "管理分组…" -> GroupManager.show()
                    "切换为顶部横条" -> setMode(MODE_BAR)
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- 横条模式 UI（旧）

    /** bar 模式：一个分组 = 一个胶囊，点击打开分组详情页 */
    private fun groupChip(ctx: Context, db: GroupDb, bar: Bar, g: Group): TextView {
        val count = db.countMembers(g.id)
        val c = chip(ctx, "${g.name} $count", true) {
            GroupDetailPage.open(ctx, g.id, g.name)
        }
        c.setOnLongClickListener {
            MemberPage.show(g.id, g.name)
            true
        }
        return c
    }

    private fun chip(ctx: Context, text: String, brand: Boolean, onClick: () -> Unit): TextView {
        val pad = UiKit.dp(ctx, 10)
        return TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTextColor(if (brand) Color.WHITE else UiKit.fgColor(ctx))
            background = if (brand) {
                UiKit.pill(UiKit.brandGreen(), radiusPx = UiKit.dp(ctx, 14).toFloat())
            } else {
                UiKit.pill(UiKit.softBg(), radiusPx = UiKit.dp(ctx, 14).toFloat())
            }
            setPadding(pad, 0, pad, 0)
            gravity = Gravity.CENTER
            setSingleLine(true)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, UiKit.dp(ctx, 30)
            )
            lp.rightMargin = UiKit.dp(ctx, 8)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    // ---------------------------------------------------------------- 交互

    private fun createGroup(ctx: Context, bar: Bar) {
        UiKit.input(ctx, "新建分组", "", "分组名称") { name ->
            val db = GroupDb.current() ?: return@input
            val id = db.createGroup(name)
            LogX.i("BAR create id=$id name=$name")
            if (id > 0) refresh(bar) else UiKit.toast(ctx, "已存在同名分组")
        }
    }

    /** bar 模式「⋯」菜单：新建 / 管理的唯一常驻入口 */
    private fun showMenu(bar: Bar) {
        val ctx = bar.ctx
        // 横条模式没有「退出筛选」行，筛选态时在这里给一个入口，否则用户回不去
        val items = if (ConvFilter.currentGroupId() > 0) {
            arrayOf("显示全部会话", "新建分组", "管理分组…", "刷新", "切换为列表模式")
        } else {
            arrayOf("新建分组", "管理分组…", "刷新", "切换为列表模式")
        }
        android.app.AlertDialog.Builder(ctx)
            .setItems(items) { _, which ->
                when (items[which]) {
                    "显示全部会话" -> ConvFilter.setGroup(ConvFilter.NONE)
                    "新建分组" -> createGroup(ctx, bar)
                    "管理分组…" -> GroupManager.show()
                    "刷新" -> refresh(bar)
                    "切换为列表模式" -> setMode(MODE_LIST)
                }
            }
            .show()
    }

    /** 供外部（分组页关闭后）触发重画 */
    fun refreshAll() {
        PanelDiag.arm()
        mainHandler.post {
            runCatching {
                bars.values.toList().forEach { bar ->
                    refresh(bar)
                    bar.list?.let { reanchorPanel(it) }
                }
            }.onFailure { LogX.w("BAR refreshAll failed: ${it.javaClass.simpleName}") }
        }
    }

    /** 面板 item（a32）底边的「关闭态」基线屏幕 y；-1 = 尚未记录 */
    @Volatile private var panelBaseline = -1

    /**
     * v9：把下拉面板 item（a32）锚回关闭态位置。
     *
     * 筛选 OFF→ON 时数据集从 2944 行收缩到 2 行，ListView 重排会把滚动锚点
     * 重置到列表顶部，面板 item 露出超过阈值，微信 onScroll 随即自动展开整个
     * 下拉快捷面板（ActionBar 被 setTranslationY(1271) 推下）。这里在数据集
     * 变更之后把 a32 的底边滚回基线位置，微信的 onScroll 会随之把 UI 复位。
     *
     * 基线只在筛选 OFF（面板确定关闭）时记录。
     */
    fun reanchorPanel(list: ListView) {
        val a32Id = runCatching { list.resources.getIdentifier("a32", "id", "com.tencent.mm") }.getOrDefault(0)
        if (a32Id == 0) { LogX.w("BAR reanchor: no a32 id"); return }
        var a32: View? = null
        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i)
            if (c.id == a32Id) { a32 = c; break }
        }
        val a = a32
        if (a == null) {
            LogX.i("BAR reanchor: a32 not in children (childCount=${list.childCount} first=${list.firstVisiblePosition})")
            return
        }
        val bottom = a.bottom
        if (ConvFilter.currentGroupId() == 0L) {
            // 筛选 OFF：面板确定关闭，此刻的位置就是基线
            if (panelBaseline == -1 || panelBaseline != bottom) {
                LogX.i("BAR panel baseline=$panelBaseline -> $bottom")
                panelBaseline = bottom
            }
            return
        }
        if (panelBaseline == -1) { LogX.i("BAR reanchor: no baseline yet (bottom=$bottom)"); return }
        val dy = bottom - panelBaseline
        if (dy > 0) {
            runCatching { list.scrollListBy(dy) }
            LogX.i("BAR reanchor scroll=$dy (bottom=$bottom base=$panelBaseline)")
        }
    }

    // ---------------------------------------------------------------- 注入细节

    private fun fragmentView(fragment: Any): View? {
        runCatching {
            val v = fragment.javaClass.getMethod("getView").invoke(fragment)
            if (v is View) return v
        }
        var cls: Class<*>? = fragment.javaClass
        var guard = 0
        while (cls != null && cls != java.lang.Object::class.java && guard++ < 6) {
            runCatching {
                val f = cls!!.getDeclaredField("mView")
                f.isAccessible = true
                val v = f.get(fragment)
                if (v is View) return v
            }
            cls = cls.superclass
        }
        return null
    }

    private fun findListView(root: View): View? {
        var hit: View? = null
        var fallback: View? = null
        fun walk(v: View, depth: Int) {
            if (depth > 12) return
            val n = v.javaClass.name
            if (hit == null && n == LIST_CLASS) hit = v
            if (fallback == null && v is AbsListView) fallback = v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val c = v.getChildAt(i)
                    if (c != null) walk(c, depth + 1)
                }
            }
        }
        walk(root, 0)
        return hit ?: fallback
    }

    private fun statusBarHeight(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) runCatching { ctx.resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    }
}
