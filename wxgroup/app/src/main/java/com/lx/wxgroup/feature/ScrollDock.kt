package com.lx.wxgroup.feature

import android.widget.AbsListView
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 会话列表「顶部停靠位」守卫 —— 修「下拉再上拉之后页面显示异常」。
 *
 * ## 现象
 * 筛选态下手指往下拉，列表内容整体下移（`child0.top` 从 -2713 跑到 -745 甚至 0），
 * 再往上拉**拉不回来**：那个 3046px 的顶部 header 整块糊在屏幕上，会话行被顶到屏幕下半部分。
 *
 * ## 根因（真机 hook `AbsListView.trackMotionScroll` 逐帧实测）
 * AOSP 的滚动守门逻辑只有两道闸：
 * ```
 * cannotScrollDown = firstPos == 0 && firstTop >= padTop && dY >= 0
 * cannotScrollUp   = firstPos + n == mItemCount && lastBottom <= end && dY <= 0
 * ```
 * 微信把「列表在顶部」做成 `child0.top == -2713`（header 只露最后 333px），
 * 于是 `firstTop` 远小于 `padTop` → `cannotScrollDown` 恒为 false → **下拉毫不受限**，
 * 手指能把内容一路推到 `firstTop == 0`（header 全露，顺带弹出微信的小程序面板）。
 *
 * 推下去之后能不能拉回来，全看 `cannotScrollUp`。v0.2.2 为了屏蔽
 * `correctTooHigh` 在列表末尾补过一行「把缺口填满」的透明行，它让 `lastBottom`
 * 恒等于 `end`、且 footer 永远挂不上（`firstPos + n` 恒差 1），
 * 于是 `cannotScrollUp` 恒为 **false** → 上拉一路滚到底，
 * 屏幕上只剩那行透明填充 View + 微信 footer —— 另一种「页面空白」。
 *
 * ## 为什么不改 AOSP 的判据
 * `cannotScrollUp` 是 `trackMotionScroll` 里的局部变量，hook 改不动；
 * 让 `lastBottom` 恒大于 `end`（尾行多 1px）能拆掉这道闸，但那样 `cannotScrollUp`
 * 就**永远不成立**，列表会被一路推到内容全跑出屏幕 —— 更糟。
 *
 * ## 修法：三件套（本类负责 1 和 3，2 由 [ConvFilter] 去掉填充行完成）
 *  1. **吞掉 `correctTooHigh`**：内容不足一屏时不再整块下移（下移会把 header 糊一屏）；
 *  2. **去掉尾部填充行**：内容不足一屏时 `firstPos + n == mItemCount` 且
 *     `lastBottom <= end` 成立 → `cannotScrollUp` 为真 → 上拉被自然禁掉；
 *  3. **下拉事后钳制**：每次滚动结束（`trackMotionScroll` 返回后）检查 `child0.top`，
 *     一旦比停靠位更靠下就整体 `offsetChildrenTopAndBottom` 拉回去。
 *     于是「下拉」变成「已经在顶部、拉不动」，内容根本没跑偏，
 *     也就不需要依赖 `cannotScrollUp` 把它拉回来。
 *
 * 副作用（可接受，甚至算是顺带修好的）：**筛选态下**微信原生「下拉露出 header +
 * 弹小程序面板」不再触发，手感等同于「列表已经在顶部」。不筛选态不受任何影响
 * （见 [guardPin]）。
 */
object ScrollDock {

    /** offset 回写期间挡掉重入（保险，正常情况下不会递归进来） */
    private var inFix = false

    /** 反射调用一次失败就永久停用 pin，宁可不修也不能每帧抛异常崩微信 */
    private var pinBroken = false

    /** 两条日志各自限量，别把 wxgroup_dbg.txt 刷爆 */
    private var pinLogged = 0
    private var blockLogged = 0

    /** 小于这个位移就不管，避免 1~2px 抖动把日志和布局搅乱 */
    private const val MIN_OVER = 4

    /**
     * 停靠位的安全阀：正常停靠是「header 大部分藏在上面」，`child0.top` 应该是
     * 一个明显的负数。万一锚点抓成了几十 px（比如装包装时列表恰好被滚过），
     * 钳制会把列表永久钉在错的地方 —— 宁可不启用。
     */
    private const val MIN_DOCK = -200

    /**
     * 「下拉钳制」要不要生效 —— **只在筛选态**。
     *
     * ⚠️ 这是个刻意的收敛：不筛选时列表是 2957 项，内容远不止一屏
     * （实测最后一个 child bottom=3182 > 3104，溢出），而且那时候
     * `firstVisiblePosition` 同样是 0（header 3046px 要滚 2713px 才会变成 1）
     * —— 光看 firstVisiblePosition 区分不了两种形态。
     * 「下拉露出顶部栏 + 弹小程序面板」是微信**原生行为**，不能一起干掉。
     *
     * 只有我们筛出少量会话、导致内容不足一屏时，AOSP 那套边界判断才不成立，
     * 才需要我们兜底。
     */
    private fun guardPin(list: AbsListView): Boolean {
        if (!ConvFilter.wraps(list)) return false
        return ConvFilter.currentGroupId() > 0L
    }

    /**
     * 「吞掉 correctTooHigh」要不要生效 —— 看**结构**，不看是不是筛选态。
     *
     * 只在「列表停在顶部、巨型 header 大部分藏在上面」（`firstVisiblePosition == 0`
     * 且 `child0.top < 0`）时吞。这时 `correctTooHigh` 的语义
     * （内容不足一屏 → 整块内容下移贴底）对微信是**错的**：下移会把 header 糊一屏。
     *
     * 为什么不用 [guardPin] 的筛选态判据：收纳态但会话很少的用户，内容同样不足一屏，
     * 一样会被下移 —— 那是同一个 bug，不能因为 `filterId == 0` 就放过。
     *
     * 为什么不会误伤：滚到列表底部时 `mFirstPosition > 0`、`firstVisiblePosition` 远大于 0，
     * 这里直接 return；那种情况下 `correctTooHigh` 做的是正常的「内容贴底」，理应保留。
     */
    private fun guardTooHigh(list: AbsListView): Boolean {
        if (!ConvFilter.wraps(list)) return false
        if (list.firstVisiblePosition != 0) return false
        return (list.getChildAt(0)?.top ?: 0) < 0
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        installPin(lpparam)
        installTooHighGuard(lpparam)
        LogX.i("ScrollDock installed")
    }

    private fun installPin(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.widget.AbsListView", lpparam.classLoader,
                "trackMotionScroll",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        pin(param.thisObject as? AbsListView ?: return)
                    }
                },
            )
        }.onFailure { LogX.e("ScrollDock pin hook failed", it) }
    }

    /**
     * 吞掉 `ListView.correctTooHigh`。
     *
     * 这件事原来是靠「在列表末尾补一行填充行」做的（见 [ConvFilter] 里
     * 「为什么不再需要尾部填充行」），那行会破坏上拉边界，所以改成直接拦方法：
     *
     * `correctTooHigh` 的语义是「内容不够一屏时把整块内容下移，让底部贴齐列表底部」。
     * 这个语义对普通列表是对的，但**对微信会话列表是错的** —— 微信靠
     * `child0.top == -2713` 把 3046px 的巨型 header 藏起来，一旦触发下移，
     * header 就整块糊到屏幕上（v0.2.2 之前「点分组行整页下移 2000px」就是它）。
     *
     * 只在「列表停在顶部 + header 藏在上面」的结构下吞（[guardTooHigh]），
     * 别的影响不到：滚到列表底部时它做的是正常的「内容贴底」，不拦。
     *
     * 对称的 `correctTooLow` 不用管：它的触发条件是 `topOffset = firstTop - padTop > 0`，
     * 而筛选态 `firstTop = -2713`，恒为负 → 不会动作（真机日志：调用 36 次，
     * 一次位移都没产生）。
     */
    private fun installTooHighGuard(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.widget.ListView", lpparam.classLoader,
                "correctTooHigh", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val list = param.thisObject as? AbsListView ?: return
                        if (!guardTooHigh(list)) return
                        // setResult 会置 returnEarly，原方法体整段跳过。
                        // correctTooHigh 返回 void，返回 null 安全（只有 primitive 返回类型才 NPE）。
                        param.result = null
                        if (blockLogged++ < 12) {
                            LogX.i(
                                "ScrollDock block correctTooHigh " +
                                    "(childCount=${param.args[0]}, firstTop=${list.getChildAt(0)?.top})"
                            )
                        }
                    }
                },
            )
        }.onFailure { LogX.e("ScrollDock correctTooHigh hook failed", it) }
    }

    /**
     * 把列表内容钳回停靠位。
     *
     * 只在「列表停在顶部」（`firstVisiblePosition == 0`）时动手 —— 正常往下翻会话时
     * `child0.top` 本来就是很大的负数，不会误伤。
     */
    private fun pin(list: AbsListView) {
        if (inFix || pinBroken) return
        if (!guardPin(list)) return
        val dock = ConvFilter.anchor() ?: return
        if (dock > MIN_DOCK) return
        if (list.firstVisiblePosition != 0) return
        val c0 = list.getChildAt(0) ?: return
        val before = c0.top
        val over = before - dock
        if (over < MIN_OVER) return

        inFix = true
        try {
            // ViewGroup.offsetChildrenTopAndBottom 是 @hide，SDK stub 里被剔除了
            // （直接调会编译不过），只能走反射。失败一次就永久停用 ——
            // 这个方法每帧都跑，抛出去会直接崩微信。
            runCatching { XposedHelpers.callMethod(list, "offsetChildrenTopAndBottom", -over) }
                .onFailure {
                    pinBroken = true
                    LogX.e("ScrollDock pin broken -> disabled", it)
                }
        } finally {
            inFix = false
        }
        // c0 是同一个 View 对象，offset 之后 top 已经是新值了
        if (pinLogged++ < 12) {
            LogX.i("ScrollDock pin ${-over}px: top $before -> ${c0.top} (dock=$dock)")
        }
    }
}
