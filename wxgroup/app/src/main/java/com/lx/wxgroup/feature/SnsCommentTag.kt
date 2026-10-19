package com.lx.wxgroup.feature

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 朋友圈**评论**的 `[已删除]` 标签（需求 6.2：「显示评论 [已删除] 标签」）。
 *
 * ## 数据源：不是微信库，是我们自己的归档
 * 评论被删时 [SnsCommentGuard] 拦下 DELETE 并把整行写进 `sns_comment_archive`，
 * 这里读的就是那份表（`snsId -> {commentSvrID…}`）。
 * ⚠️ 推论：防撤回开关没开 → 表里没数据 → 标签不会打。两个都开才生效（跟 [SnsTag] 同理）。
 *
 * ## ✅ 两条取值路径（T3/T4 于 2026-10-19 指令级取证定案）
 *
 * - **精确路径（真机主路径）**：`ImproveCommentView` 自带字段 `o: Lq54/q95`
 *   （`setupCommentLayout` 每帧 `iput` 覆盖），其 `.r` 就是 `commentSvrID`。
 *   我们**不 hook 任何方法**，直接在 `onBindViewHolder` 之后从 View 子树里反射读 ⇒
 *   跟归档里的 `svrIds` **精确比对，不依赖正文**。
 * - **降级路径**（精确路径未解析成功时的兜底，仍在）：归档里存着删之前的 `curActionBuf`，
 *   抽出正文候选跟界面 TextView 文案对账（[SnsCommentLogic.textHit]），命中就在那条评论行上打标。
 *
 * ## 时间线渲染链（improve 链，已指令级实证）
 * ```
 * lh3.x1.b(...)                              ← item 绑定（1168 条指令的巨型方法）
 *   └─ ImproveInteractionLayout.setupLayout()
 *        └─ setupCommentLayout(oh3/p)        ← 逐条评论循环
 *             [209] LinkedList.get(I) → q54/q95    ← 评论 proto
 *             [229] q95.r:J                        ← commentSvrID
 *             [268] iput ImproveCommentView.o:q95  ← ⭐ 写进评论 View 自己的字段
 *             [338] ImproveCommentView.l()         ← loadContent
 * ```
 * ⇒ 因为 `setupCommentLayout` 跑在 `onBindViewHolder` **内部**，我们挂
 * `afterHookedMethod` 读到的必然是本次 bind 的新值（不存在跨帧陈旧）。
 *
 * ⚠️ **不要再回去挂 `rf3.a.b(View, q95)`**：那条路在 improve 时间线上**一次都不被调用**
 * （真机实测 `binds=746 bindIds=0`）。`TimelineCommentView.b` 的唯一调用者是
 * `TimelineCommentView.a(ms,int)`，而后者方法体只处理点赞布局，注释区根本不走。
 *
 * ⚠️ [`COMMENT_SVR_ID_FIELD`] = `r` 已验证（`q95.r` 有 31 处业务读取，`q95.q` 只有 2 处）。
 *
 * 要验证「容器 / 落点」本身没问题，用 `probe("mark")`：给指定 snsId 的动态
 * 强制打一个标签，肉眼确认位置对不对。`probe("pending")` 能查精确路径当前状态。
 */
object SnsCommentTag {

    // ==================================================================
    // ✅ T4 的字段名已定案（2026-10-19）
    // ✅ T3/T4 improve 链已接通（从 View 直读），见下方 IMPROVE_COMMENT_VIEW_CLASS
    // ==================================================================

    /**
     * **旧版时间线 / 评论详情页**的单条评论容器（`RelativeLayout`）。
     *
     * `SnsCommentCollapseLayout` 持两个正文 TextView（`d` 折叠态 / `e` 展开态 / `f` 其他），
     * 由 `TimelineCommentView.b(...)` 逐条创建。
     *
     * ⚠️ 真机日志（2026-10-19 19:12）实测字段表：
     * `d/e/f(TextView) g/h(CharSequence) i/j/n(int)` —— **有 `h` 但没有 `q95`**。
     *
     * ⚠️ **improve 时间线（真机实际使用的 UI）不用这个类**：那条链的评论行是
     * [IMPROVE_COMMENT_VIEW_CLASS]。本常量只用于 [resolvePending] 的兜底判断，
     * **不能**拿去当 `rowsOf` 的过滤条件（那样在 improve 链上恒返回空）。
     */
    private const val COMMENT_ROW_CLASS =
        "com.tencent.mm.plugin.sns.ui.widget.SnsCommentCollapseLayout"

    /**
     * 旧版链的字段链兜底：`SnsCommentCollapseLayout.h` 如果哪天真的变成评论 proto，
     * 这里就能自动接上。8.0.48 上它是 `CharSequence`（正文）⇒ 必然失败、如实记日志。
     */
    private const val ROW_COMMENT_FIELD = "h"

    /**
     * ⛔ **已废弃（2026-10-19 21:32 真机推翻）**：UI 层评论 proto 的 `commentSvrID` 字段名。
     *
     * 曾经以为它是 `"r"`，还据此写了 `svrIdOf()` 精确路径。真机 `probe("ids")` dump 全部字段后
     * 证明：**`q:J` 与 `r:J` 恒为 0（6/6 条真实评论）**，微信根本没把 commentSvrID 带到 UI 层
     * （DB 里是有的，只是没往上带）。见 `docs/取证结论.md` ⑩ 段。
     *
     * ⇒ 改走微信自己 `isExistComment` 的判重维度：`talker + createTime + type`。
     * 本常量保留只为让下轮 grep 能直接撞见这段结论，**不要再拿它去读字段**。
     */
    @Deprecated("UI 层不带 commentSvrID，恒为 0；改用三元组")
    private const val COMMENT_SVR_ID_FIELD = "r"

    /**
     * ✅ 渲染侧匹配键（真机实测字段语义，见 `docs/取证结论.md` ⑩ 段）：
     * - `talker` = proto 的 `d: String`（实测 `wxid_jsmu3n3ucorv22`）
     * - `createTime` = proto 的 `i: Int`（实测 `1792410895` = 2026-10-19 11:54:55 UTC，秒级）
     * - `type` = proto 的 `g: Int`（实测 `2`；1=评论 / 2=回复）
     *
     * ⚠️ 这三个名字是**混淆后的短名**，跟着微信版本会变 ⇒ 解析必须带类型校验
     * （`i` 必须是 `int`、`d` 必须是 `String`），对不上就走降级，绝不硬读错字段。
     */
    private const val COMMENT_TALKER_FIELD = "d"
    private const val COMMENT_TIME_FIELD = "i"
    private const val COMMENT_TYPE_FIELD = "g"

    // ==================================================================
    // ✅ T3/T4 improve 链的真答案（2026-10-19 指令级取证定案）
    // ==================================================================

    /**
     * improve 时间线**单条评论**的 View 类名 —— 它自己就持有评论 proto。
     *
     * ## 决定性证据（`ImproveInteractionLayout.setupCommentLayout` 指令级解码）
     * ```
     * [ 205] invoke  oh3.p->getCommentList()          ← 本条动态的评论 proto 链表
     * [ 209] invoke  java.util.LinkedList->get(I)     ← 第 i 条评论 proto
     * [ 218] check-cast <Lq54/q95;>                   ← 确认元素类型是评论 proto
     * [ 220] iget     q54.q95.j:I                     ← 拼 key 用
     * [ 229] iget-wide q54.q95.r:J                    ← ⭐ commentSvrID
     * [ 268] iput-object ImproveCommentView.o:q54.q95 ← ⭐ 写进 View 自己的字段！
     * [ 338] invoke  ImproveCommentView->l()          ← loadContent（消费 o）
     * ```
     * `ImproveCommentView` 的字段表：`o: Lq54/q95;`（其余 `p/q` 是 CharSequence/String）。
     * 而 `ImproveCommentView` 的父类链是
     * `ImproveCommentView → ImproveTranslateLayout → X2CLinearLayout`，全程没覆写 `o`。
     *
     * ## 为什么这比「挂方法记映射」好得多
     * - **不用 hook 任何方法** ⇒ 没有「钩子挂错 / 没被调用」这类静默失效（上一版
     *   `rf3.a.b` 就是这么失效的：真机 `binds=746 bindIds=0`，那条路 improve 链根本不走）；
     * - **不存在跨帧陈旧**：`o` 是 View 自己的字段，`setupCommentLayout` 每帧都会 `iput` 覆盖，
     *   读到的永远是这次 bind 对应的那条评论；
     * - **不需要 `WeakHashMap`** ⇒ 顺带消掉「弱键被 RecyclerView 池强引用、只增不减」的泄漏。
     *
     * ## ⚠️ 时序：必须先 `onBindViewHolder` 再读
     * `setupCommentLayout` 的调用链是
     * `ImproveInteractionLayout.setupLayout() → setupCommentLayout()`
     * ← `lh3.x1.q() ∈ lh3.x1.b()`（item 绑定，1168 条指令的巨型方法尾部）。
     * 也就是说 `setupCommentLayout` 跑在**绑定过程内部**，而 `onBindViewHolder`
     * 的 `afterHookedMethod` 跑在**绑定结束之后** ⇒ 我们的钩子读到的必然是刚写好的新值。
     */
    private const val IMPROVE_COMMENT_VIEW_CLASS =
        "com.tencent.mm.plugin.sns.ui.improve.view.ImproveCommentView"

    /** 上面那个类里持有评论 proto 的字段名（已指令级实证） */
    private const val IMPROVE_COMMENT_PROTO_FIELD = "o"

    /**
     * T4 备用：万一 `q95` 将来长出 getter，这些方法名按顺序尝试。
     *
     * ⚠️ 保留了但**不要指望它**：`q95` 是 lite protobuf，8.0.48 上**没有任何 getter**
     * （字段全 public 直读）。这个列表只在「将来某版本改成标准 protobuf」时才有意义。
     */
    private val COMMENT_ID_GETTERS = listOf(
        "getCommentSvrID", "getCommentSvrId", "getSvrID", "getSvrId",
    )

    // ------------------------------------------------------------------ 常量

    /** 微信 RecyclerView 适配器基类：**未混淆**（与 SnsTag 同源，真机已验证） */
    private const val ADAPTER_CLASS = "com.tencent.mm.view.recyclerview.WxRecyclerAdapter"

    /** 微信自研列表基类：抓 setAdapter 拿「具体 adapter 类」 */
    private const val WX_RV_CLASS = "com.tencent.mm.view.recyclerview.WxRecyclerView"

    /** 只给朋友圈的 adapter 挂钩 */
    private const val SNS_ADAPTER_PREFIX = "com.tencent.mm.plugin.sns."

    /**
     * 标签 View 上存的东西：**归属 + 文本**。
     *
     * 为什么不再是裸哨兵 `MARK`：itemView 会被 RecyclerView 复用，光凭「这个容器里有我的标签」
     * 无法回答「它属于哪条动态 / 哪条评论」，复用后必然把上一条动态的标签当成这一条的。
     * 存下归属，上层 [collectLabels] 才能把子树里的标签对上号交给 reconcile 决策。
     */
    private data class LabelTag(
        val owner: SnsCommentLabelLogic.LabelOwner,
        val text: String,
    )

    /** 扫标签树时的深度上限：异常布局（ViewGroup 嵌套异常深 / 成环）下别把线程跑死 */
    private const val LABEL_TREE_MAX_DEPTH = 32

    /** ⚠️ 标签文本的唯一来源，别再就地写字面量（拼装规则锁在 [SnsCommentLogic.labelText] 单测里） */
    private const val LABEL = SnsCommentLogic.LABEL_DELETED

    private const val SET_TTL_MS = 5000L
    private const val CFG_TTL_MS = 30000L
    private const val LOG_MAX = 5

    private const val TEXT_LIMIT = 500
    private const val TEXT_CACHE_MAX = 300

    @Volatile private var enabled = false
    @Volatile private var cfgAt = 0L
    @Volatile private var delMap: Map<Long, Set<String>> = emptyMap()
    @Volatile private var setAt = 0L

    private val loading = AtomicBoolean(false)
    private val added = AtomicInteger(0)
    /** 因复用/换动态而摘掉的标签数（旧实现没有这个动作，所以是新增计数） */
    private val removed = AtomicInteger(0)
    private val degradeHits = AtomicInteger(0)
    private val textHits = AtomicInteger(0)
    private val binds = AtomicInteger(0)
    private val miscLogged = AtomicInteger(0)

    /**
     * `snsId -> 该动态已删评论的正文候选`（每条评论一个 `List<String>`）。
     *
     * ⚠️ **value 允许为 null**（= 查过了，归档里没有这条动态的已删评论），
     * 所以必须用 `containsKey` 区分「没查过」和「查过是空的」——
     * **不能用 ConcurrentHashMap**（CHM 不允许 null value，真机崩过一次）。
     */
    private val textCache: MutableMap<Long, List<List<String>>?> =
        Collections.synchronizedMap(HashMap())

    /** 待补的 snsId（主线程只往里丢，后台线程排空） */
    private val wantTexts: MutableSet<Long> = Collections.synchronizedSet(HashSet())

    private val loadingTexts = AtomicBoolean(false)

    /** 探针用的强制集合：只对它打开，正常运行里恒为空 */
    @Volatile private var forceSet: Set<Long> = emptySet()

    /** 我们插进去的标签 View（弱引用，绝不钉住宿主 View） */
    private val tagged: MutableList<WeakReference<TextView>> =
        Collections.synchronizedList(mutableListOf())

    /** 后台读归档库用；daemon 线程，绝不能挡住宿主进程退出 */
    private val bg: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "snscmt-archive").apply { isDaemon = true }
        }
    }

    private var pendingRowCls: Class<*>? = null

    /**
     * 精确路径的解析结果：`ImproveCommentView` 类 + 它持有 proto 的字段 `o` + proto 的 `r` 字段。
     *
     * 三者**必须同时拿到**才启用精确路径；任一为 null ⇒ [idOf] 保持 null ⇒ 走降级（正文对账）。
     *
     * ## 为什么不用 `WeakHashMap<View, Long>` 缓存
     * 上一版确实这么做过（hook `rf3.a.b` 记映射），有明确的坑：
     * RecyclerView 的回收池**强引用** itemView 子树 ⇒ 弱键等于强键，条目只增不减（慢性泄漏）。
     * 而这里改成**每帧现读**：`setupCommentLayout` 每帧都会 `iput` 覆盖 `o`，
     * 所以我们读到的永远是新值，缓存既没必要也有害（会拿到上一次 bind 的旧 id）。
     */
    @Volatile private var improveViewCls: Class<*>? = null
    @Volatile private var improveProtoField: java.lang.reflect.Field? = null

    /** 渲染键三元组的反射句柄（见 [COMMENT_TALKER_FIELD] 的说明） */
    @Volatile private var protoTalkerField: java.lang.reflect.Field? = null
    @Volatile private var protoTimeField: java.lang.reflect.Field? = null
    @Volatile private var protoTypeField: java.lang.reflect.Field? = null

    /** 精确路径命中计数（从 View 上成功读出渲染键的次数），验收看它涨不涨 */
    private val preciseIds = AtomicInteger(0)

    // ── 漏斗计数器：定位「binds 涨但 ids 不涨」到底丢在哪一环 ──
    // 只看 binds/ids 两个数永远分不清是「没进精确路径」还是「进了但读不出」，
    // 所以每一环各记一个数，一次 probe 就能定位。
    /** bind 有 itemView 且 enabled 的次数 */
    private val fItem = AtomicInteger(0)
    /** 拿到非 null snsId 的次数 */
    private val fSns = AtomicInteger(0)
    /** 归档库里有该 snsId 的已删评论（真正进精确路径）的次数 */
    private val fDel = AtomicInteger(0)
    /** rowsOf 找到 ≥1 个落点 View（ImproveCommentView）的次数 */
    private val fRows = AtomicInteger(0)
    /** 找到的行里至少有一个读出了 id */
    private val fGot = AtomicInteger(0)
    /**
     * `(createTime, talker)` 对得上归档、但三元组整体不等（即 `type` 字段错位）的次数。
     *
     * 为什么要这个：`type` 是拿 UI 的 `g` 跟 DB 的 `type` 列比。
     * `docs/取证结论.md` 里 DB 侧见过 `type in (8,16)` 的删除分支，而 UI 侧只验过 `g=2` ——
     * **两者是否同语义从未逐条对齐过**。若错位，全量 key 失配 → 全漏标且**零报错**，
     * 靠"打不上标"根本分不清是「没删评论」还是「键错位」。
     * 有这个计数就能一眼判定：涨 = 键语义错位，不涨 = 语义一致（纯漏标另查）。
     */
    private val fTypeMiss = AtomicInteger(0)
    /**
     * 成功读出**可用于比对**的三元组的行数（`fTypeMiss` 的分母）。
     *
     * ⚠️ 没有这个数，`typeMiss=0` 就是**假阴性**：分不清「type 语义完全一致」
     * 与「一行都没读出 key，压根没比过」（复核 2026-10-19 P1）。
     * 判读口径：`keyOk>0 && typeMiss==0` = 语义一致；`keyOk==0` = 无数据，别下结论。
     */
    private val fKeyOk = AtomicInteger(0)

    /**
     * 最近读到的 snsId（环形，最多 16 个，新的在前）。
     *
     * 为什么要它：验标签必须先知道「屏幕上这条动态的 snsId」才能造测试数据，
     * 而 `logBind` 只有 5 条额度、进程一启动就被吃光 ⇒ 只能靠 probe 现场读。
     */
    private val recentSns = java.util.concurrent.ConcurrentLinkedDeque<Long>()

    /**
     * 最后一次 bind 的 itemView（弱引用）。
     *
     * 只为 `probe("types")` 服务：当「precise=true 但 ids 不涨」时，需要直接看树上有没有
     * `ImproveCommentView`，否则永远分不清「钩子没跑」和「树上就没这个类」。
     * ⚠️ 必须弱引用：itemView 被 RecyclerView 池持有，强引用会让整个 item 泄漏。
     */
    @Volatile private var lastItemView: java.lang.ref.WeakReference<View>? = null

    /**
     * 绑定层**独立**的日志额度。
     *
     * ⚠️ 不能共用 [logOnce] 的 [LOG_MAX]（5 条）：那份额度是给 `reconcile` /
     * `degrade` 等**验收关键日志**的，共用一个计数器会互相挤掉。
     */
    private val bindLogged = AtomicInteger(0)

    private fun logBind(msg: String) {
        // 原写法 `bindLogged.get() < LOG_MAX && incrementAndGet() <= LOG_MAX` 的前半是冗余的：
        // 计数器单调增（只在 :721 被 set(0) 重置），后半 `<= LOG_MAX` 已经等价地表达了额度，
        // 去掉前半**不改变任何一条日志的打出与否**（复核 2026-10-20 #12）。
        if (bindLogged.incrementAndGet() <= LOG_MAX) LogX.i(msg)
    }

    /**
     * 从「评论行 View」取出**渲染键**的取值器；null = 该行读不出可用键（未知）。
     *
     * ## 演进史（三次真机推翻，别再走回头路）
     * 1. ~~无参 getter 方法~~ → 证伪：ViewHolder 一个方法都没有。
     * 2. ~~`ViewHolder.h.r` 两级字段直读当 commentSvrID~~ → 证伪（2026-10-19 21:32 真机）：
     *    `q54/q95` 的 `q:J` / `r:J` **恒为 0（6/6 条真实评论）**，微信没把 commentSvrID
     *    带到 UI 层（DB 里有，只是不带上来）。
     * 3. ✅ **当前方案**：读 `talker(d) / createTime(i) / type(g)` 三元组，
     *    与微信自己 `s1.q1` 的 `isExistComment` 判重维度一致。
     *
     * 详见 `docs/取证结论.md` ⑩ 段。返回值形如 `"1792410895:wxid_xxx:2"`。
     */
    private var idOf: ((Any) -> String?)? = null

    // ------------------------------------------------------------------ 安装

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        var hooked = 0

        // ① 基类上声明的实现（子类不覆写时走这条）—— 已取证
        runCatching { XposedHelpers.findClass(ADAPTER_CLASS, cl) }
            .onSuccess { hooked += hookAdapterClass(it) }
            .onFailure { LogX.w("SNSC_TAG: $ADAPTER_CLASS not found") }

        // ② 子类一旦覆写 onBindViewHolder，挂基类就是死钩（静默失效），
        //    靠 setAdapter 抓具体 adapter 类补挂
        hooked += hookSetAdapter(cl)

        // ③ ✅ 精确路径（**真机主路径**）：ImproveCommentView.o(Lq54/q95).r —— 从 View 直接读 id
        val preciseOk = resolveImproveProto(cl)

        // ④ 字段链兜底：只在 ③ 没成时尝试（防的是「将来某版本把 proto 挂回评论行 View」）。
        //    ⚠️ 顺序不能反：③ 覆盖 ④，反过来会让低可靠度的字段链盖掉可靠路径。
        if (idOf == null) resolvePending(lpparam)

        if (hooked == 0) LogX.w("SNSC_TAG: no bind hook installed")
        runCatching { refresh() }
        LogX.i("SNSC_TAG installed enabled=$enabled hooks=$hooked " +
            "precise=${idOf != null} improve=$preciseOk")
    }

    /**
     * 解析 improve 时间线「从评论 View 直接读出 commentSvrID」所需的三个反射句柄。
     *
     * ## 取值链（全部指令级实证，见 [IMPROVE_COMMENT_VIEW_CLASS] 的说明）
     * ```
     * itemView 子树里的 ImproveCommentView 实例
     *   └─ .o : Lq54/q95;      ← 评论 proto（setupCommentLayout 每帧 iput 覆盖）
     *        └─ .r : J         ← commentSvrID
     * ```
     *
     * ⚠️ **必须挂 `afterHookedMethod`**：`setupCommentLayout`（写 `o`）跑在
     * `onBindViewHolder` 内部，而我们在 `afterHookedMethod` 里读 ⇒ 必然是本次 bind 的新值。
     * 若哪天微信改成异步填 `o`，[idOf] 会返回 null 而**不是**错值 —— 降级路径兜住，不会误标。
     *
     * @return 1 = 三个句柄都拿到，精确路径启用；0 = 放弃（走降级，功能不中断）
     */
    private fun resolveImproveProto(cl: ClassLoader): Int {
        val vc = runCatching { XposedHelpers.findClass(IMPROVE_COMMENT_VIEW_CLASS, cl) }
            .getOrNull()
        if (vc == null) {
            LogX.w("SNSC_TAG: $IMPROVE_COMMENT_VIEW_CLASS not found -> 精确路径不可用，走降级")
            return 0
        }
        // 字段在类自身声明（父类是 ImproveTranslateLayout / X2CLinearLayout，都没有 o），
        // 但为抗「将来上移到父类」，这里沿父类链找。
        val pf = runCatching {
            var c: Class<*>? = vc
            var up = 0
            var f: java.lang.reflect.Field? = null
            while (c != null && up < 5 && f == null) {
                f = c.declaredFields.firstOrNull { it.name == IMPROVE_COMMENT_PROTO_FIELD }
                c = c.superclass
                up++
            }
            f
        }.getOrNull()
        if (pf == null) {
            LogX.w("SNSC_TAG: $IMPROVE_COMMENT_VIEW_CLASS#$IMPROVE_COMMENT_PROTO_FIELD 不存在 " +
                "-> 走降级")
            return 0
        }
        runCatching { pf.isAccessible = true }

        // ⚠️ **不再解析 commentSvrID 字段**：真机实测 proto 的 `q`/`r` 两个 long 恒为 0，
        //    微信没把 commentSvrID 带到 UI 层（见 docs/取证结论.md ⑩ 段）。
        //    改解析微信自己判重用的三元组字段：talker(d) / createTime(i) / type(g)。
        val pType = pf.type
        fun fieldOf(name: String, want: Class<*>): java.lang.reflect.Field? {
            val f = runCatching { pType.getDeclaredField(name) }.getOrNull() ?: return null
            if (f.type != want) return null
            runCatching { f.isAccessible = true }
            return f
        }
        val talkerF = fieldOf(COMMENT_TALKER_FIELD, String::class.java)
        val timeF = fieldOf(COMMENT_TIME_FIELD, Int::class.javaPrimitiveType!!)
        val typeF = fieldOf(COMMENT_TYPE_FIELD, Int::class.javaPrimitiveType!!)
        if (talkerF == null || timeF == null || typeF == null) {
            LogX.w(
                "SNSC_TAG: proto 三元组字段缺失 ${pType.name} " +
                    "talker=$COMMENT_TALKER_FIELD(${talkerF != null}) " +
                    "time=$COMMENT_TIME_FIELD(${timeF != null}) " +
                    "type=$COMMENT_TYPE_FIELD(${typeF != null}) -> 走降级"
            )
            return 0
        }

        improveViewCls = vc
        improveProtoField = pf
        protoTalkerField = talkerF
        protoTimeField = timeF
        protoTypeField = typeF

        // ⚠️ 落点容器**必须**是 `ImproveCommentView` 本身，不能沿用 `COMMENT_ROW_CLASS`！
        //    improve 链上每条评论就是一个 `ImproveCommentView`（就是被 iput 的那个 View），
        //    而 `SnsCommentCollapseLayout` 属于**旧版时间线 / 评论详情页**那条链 ——
        //    在 improve 时间线上 `rowsOf(itemView, SnsCommentCollapseLayout)` 恒为空，
        //    于是 precise 恒为 0、精确路径永久"解析成功但一条都打不上"。
        //    （这正是上一版真机 `binds=746 bindIds=0` 之后的第二个坑，静态看不出来。）
        pendingRowCls = vc

        idOf = { row -> renderKeyOf(row) }
        LogX.i(
            "SNSC_TAG: 精确路径=View 直读三元组 ${vc.simpleName}.${pf.name}" +
                "(${pType.simpleName}) → " +
                "$COMMENT_TALKER_FIELD/$COMMENT_TIME_FIELD/$COMMENT_TYPE_FIELD " +
                "(无 commentSvrID：UI 层不带)"
        )
        return 1
    }

    /**
     * 从一个 View 实例读出它的**渲染键**（`createTime:talker:type`）。
     *
     * ⛔ 上一版读的是 `proto.r`（以为是 commentSvrID），真机证明恒 0 —— 已废弃，
     * 详见 [COMMENT_SVR_ID_FIELD] 的注释与 `docs/取证结论.md` ⑩ 段。
     *
     * 三种情况必须严格区分（混成一种就会误标）：
     * - 不是 `ImproveCommentView` → `null`（这个 View 根本不是评论行）
     * - 是，但 `o == null`（还没绑 / 已被回收） → `null`（**未知**）
     * - 是，`o != null`，但 talker 为空 / createTime<=0 → `null`（**读到了但不可用**，等同未知）
     *
     * ⇒ 只有「确实读到了可用的三元组」才返回非 null。
     * 返回值**永不为空串**，调用方不必再判空。
     */
    private fun renderKeyOf(row: Any): String? {
        val vc = improveViewCls ?: return null
        if (!vc.isInstance(row)) return null
        val pf = improveProtoField ?: return null
        val tf = protoTalkerField ?: return null
        val cf = protoTimeField ?: return null
        val yf = protoTypeField ?: return null
        val proto = runCatching { pf.get(row) }.getOrNull() ?: return null
        val talker = runCatching { tf.get(proto) as? String }.getOrNull()
        val time = runCatching { cf.getInt(proto) }.getOrNull() ?: return null
        val type = runCatching { yf.getInt(proto) }.getOrNull() ?: return null
        val k = SnsCommentLogic.renderKey(time.toLong(), talker, type) ?: return null
        val key = SnsCommentLogic.keyOf(k)
        val n = preciseIds.incrementAndGet()
        if (n <= 3) logBind("SNSC_TAG precise hit#$n ${row.javaClass.simpleName} key=$key")
        return key
    }

    private val hookedMethods: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** 给一个 adapter 类挂它所有 `onBindViewHolder(?, int)` 的具体重载；返回新挂条数 */
    private fun hookAdapterClass(c: Class<*>): Int {
        var n = 0
        val ms = runCatching { c.methods }.getOrNull() ?: return 0
        // 2 参 (holder,pos) 与 3 参 (holder,pos,payloads) 都要挂：
        // adapter 只覆写 3 参版时，只挂 2 参就是完全静默失效。
        val cands = ms.filter {
            it.name == "onBindViewHolder" && !Modifier.isAbstract(it.modifiers) &&
                it.parameterTypes.size >= 2 &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                (it.parameterTypes.size == 2 ||
                    (it.parameterTypes.size == 3 && it.parameterTypes[2] == List::class.java))
        }
        val solid = cands.filter { !it.isBridge }
        for (m in solid.ifEmpty { cands }) {
            val key = "${m.declaringClass.name}#${m.name}" +
                "(${m.parameterTypes.joinToString { p -> p.name }})"
            if (!hookedMethods.add(key)) continue
            if (runCatching { XposedBridge.hookMethod(m, bindHook) }.isSuccess) n++
        }
        if (n > 0) LogX.i("SNSC_TAG hooked adapter=${c.name} +$n")
        return n
    }

    private fun hookSetAdapter(cl: ClassLoader): Int {
        val rv = runCatching { XposedHelpers.findClass(WX_RV_CLASS, cl) }.getOrElse {
            LogX.w("SNSC_TAG: $WX_RV_CLASS not found, concrete-adapter hook skipped")
            return 0
        }
        val m = rv.methods.firstOrNull { it.name == "setAdapter" && it.parameterTypes.size == 1 }
            ?: runCatching {
                var c: Class<*>? = rv.superclass
                var up = 0
                var found: Method? = null
                while (c != null && up < 6 && found == null) {
                    found = c.methods.firstOrNull {
                        it.name == "setAdapter" && it.parameterTypes.size == 1
                    }
                    c = c.superclass
                    up++
                }
                found
            }.getOrNull()
        if (m == null) {
            LogX.w("SNSC_TAG: setAdapter not found, concrete-adapter hook skipped")
            return 0
        }
        return runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val a = param.args?.getOrNull(0) ?: return
                        if (!a.javaClass.name.startsWith(SNS_ADAPTER_PREFIX)) return
                        hookAdapterClass(a.javaClass)
                    }.onFailure { LogX.w("SNSC_TAG setAdapter: ${it.javaClass.simpleName}") }
                }
            })
            1
        }.getOrDefault(0)
    }

    /**
     * 「从 View 自己身上取 commentSvrID」的**兜底**解析器。
     *
     * ⚠️ **8.0.48 上这条路必然失败**（真机日志 2026-10-19 19:12 实证）：
     * `SNSC_TAG: 字段 java.lang.CharSequence#r 不存在 -> 降级` ——
     * `SnsCommentCollapseLayout` 确实有 `h` 字段，但类型是 `CharSequence`（正文），
     * 拿它当 `q95` 去读 `r` 自然失败。
     *
     * ⇒ 真正的取值入口是 [hookCommentBind]（挂 `rf3.a.b(View, q95)`）。
     * 本函数保留的意义：万一将来某版本把评论 proto 挂回 View 的字段上，
     * 这里能自动接上；而它**在 [hookCommentBind] 之前**执行，
     * 所以成功与否都不会覆盖绑定层设的 [idOf]。
     *
     * ⚠️ 任一步拿不到就回落降级路径（正文对账），绝不让异常冒出去。
     */
    private fun resolvePending(lpparam: XC_LoadPackage.LoadPackageParam) {
        val c = runCatching { XposedHelpers.findClass(COMMENT_ROW_CLASS, lpparam.classLoader) }
            .getOrNull()
        if (c == null) {
            LogX.w("SNSC_TAG: comment row class not found: $COMMENT_ROW_CLASS -> 用绑定层")
            return
        }

        // ① 先试 getter（现在没有，仅兜未来）
        val getter = COMMENT_ID_GETTERS.asSequence()
            .mapNotNull { n ->
                runCatching { c.methods.firstOrNull { it.name == n && it.parameterTypes.isEmpty() } }
                    .getOrNull()
            }
            .firstOrNull()
        if (getter != null) {
            // ⚠️ 旧链 getter 返回的是**数值 id**，不是三元组 ⇒ 无法构成渲染键。
            //    ⛔ 绝不能写成 `idOf = { null }`：那会让 install() 认为「精确路径已接通」
            //       从而跳过后续兜底，且 onBind 会进入精确分支 → `rowsOf(CollapseLayout)`
            //       在 improve 链上恒空 → 走「没有评论行」的 return，**降级正文对账被完全跳过**
            //       ⇒ 功能直接归零（复核 2026-10-19 P1）。
            //    正确做法：什么都不设，如实记日志后返回，让调用方继续走别的路径。
            LogX.i(
                "SNSC_TAG: View 侧 getter ${c.name}#${getter.name} 是数值 id（非三元组）" +
                    "-> 不可用于渲染键，继续走 improve 链"
            )
            return
        }

        // ② 字段链：**必须确认 h 的类型真的是评论 proto 才采用**
        //    真机实测 h 是 CharSequence（正文），此时 protoCls 也是 CharSequence，
        //    而 CharSequence 上不会有 `r` 字段 ⇒ 下面 f2 == null 会拦住。
        //    但 `r` 这个词太短，万一将来某个类恰好有 r 字段就会误判，
        //    所以额外要求「第一级字段类型不是 CharSequence/String」这道硬闸。
        val f1 = runCatching { c.getDeclaredField(ROW_COMMENT_FIELD) }.getOrNull()
        if (f1 == null) {
            LogX.i("SNSC_TAG: ${c.simpleName} 无字段 $ROW_COMMENT_FIELD -> 用 improve 链")
            return
        }
        if (CharSequence::class.java.isAssignableFrom(f1.type)) {
            // 8.0.48 的真机落点：h 是正文，不是 proto
            LogX.i("SNSC_TAG: ${c.simpleName}.$ROW_COMMENT_FIELD 是 " +
                "${f1.type.simpleName}（正文，不是评论 proto）-> 用 improve 链")
            return
        }
        runCatching { f1.isAccessible = true }
        val protoCls = f1.type
        fun pf(name: String, want: Class<*>): java.lang.reflect.Field? {
            val f = runCatching { protoCls.getDeclaredField(name) }.getOrNull() ?: return null
            if (f.type != want) return null
            runCatching { f.isAccessible = true }
            return f
        }
        val fTalker = pf(COMMENT_TALKER_FIELD, String::class.java)
        val fTime = pf(COMMENT_TIME_FIELD, Int::class.javaPrimitiveType!!)
        val fType = pf(COMMENT_TYPE_FIELD, Int::class.javaPrimitiveType!!)
        if (fTalker == null || fTime == null || fType == null) {
            // 8.0.48 的 improve 时间线本来就走 ③（ImproveCommentView.o），这里是兜底，
            // 解析不到三元组就如实放弃 -> 走降级正文对账。
            LogX.i("SNSC_TAG: 字段 ${protoCls.simpleName} 无 talker/time/type 三元组 " +
                "-> 用 improve 链（ImproveCommentView.o）")
            return
        }

        pendingRowCls = c
        idOf = { row ->
            // ⚠️ RecyclerView 复用期 h 可能为 null → 返回 null 交给降级，不抛异常
            val proto = runCatching { f1.get(row) }.getOrNull()
            if (proto == null) null
            else runCatching {
                val k = SnsCommentLogic.renderKey(
                    fTime.getInt(proto).toLong(),
                    fTalker.get(proto) as? String,
                    fType.getInt(proto),
                )
                k?.let { SnsCommentLogic.keyOf(it) }
            }.getOrNull()
        }
        LogX.i(
            "SNSC_TAG: 精确路径=字段链 ${c.simpleName}.${ROW_COMMENT_FIELD}" +
                "(${protoCls.simpleName}).[$COMMENT_TALKER_FIELD/$COMMENT_TIME_FIELD/$COMMENT_TYPE_FIELD]"
        )
    }

    private fun refresh() {
        enabled = Kv.getBool(Keys.ANTIREVOKE_COMMENT) && Kv.getBool(Keys.ANTIREVOKE_COMMENT_DELETED_TAG)
        cfgAt = SystemClock.elapsedRealtime()
        setAt = 0L
        forceSet = emptySet() // 验证用的强制集合只在探针里设，开关一变就清掉
        synchronized(textCache) { textCache.clear() }
        synchronized(wantTexts) { wantTexts.clear() }
        // ⚠️ 这里**没有**需要清的 id 缓存：精确路径是每帧从 View 的 `o` 字段现读，
        //    不存任何跨帧状态（上一版用 WeakHashMap 存过，被 RecyclerView 池钉住只增不减）。
    }

    // ------------------------------------------------------------------ 绑定回调

    private val bindHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching { onBind(param) }
                .onFailure { logOnce("SNSC_TAG bind err: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * 一次 bind 的完整流程：**先算出想打哪些标签 → 一律先恢复成干净状态（先清后打）→ 再补**。
     *
     * ## 为什么必须「先清后打」（这版改动的核心）
     *
     * 旧实现是「找到命中点就直接 `applyLabel`，没有命中点就 `return`」，**全程没有清理旧标签的步骤**
     * （`clearLabels()` 原本只在 `probe("unmark")` 里被调用一次）。itemView 被 RecyclerView 复用后：
     *
     * - 旧标签还挂在子树上，新的一条动态明明没有已删评论，也会带着上一条的 `[已删除]` 显示；
     * - 反向更糟：`applyLabel` 原先的幂等是「容器里已存在 MARK 就直接 return」，
     *   复用后命中旧标签 → 直接 return → **该新打的位置永远打不上，位置被锁死**。
     *
     * 所以现在所有早退分支（没有 delMap / 取不到 snsId / 没有已删评论 / 文案没对上）
     * **都不能直接 return**，必须先跑一次 [reconcileAndApply]（`desired` 传空表即为「全部摘掉」）。
     *
     * 归属判定全部委托给 [SnsCommentLabelLogic.reconcile]（有 21 个 JVM 单测），
     * 本方法只负责「收集实况 + 收集期望 + 执行摘/补」三件体力活。
     */
    private fun onBind(param: XC_MethodHook.MethodHookParam) {
        val a = param.args
        if (a == null || a.size < 2) return
        binds.incrementAndGet()
        if (SystemClock.elapsedRealtime() - cfgAt > CFG_TTL_MS) refresh()

        // ⚠️ itemView 必须在 `enabled` 检查**之前**拿到：没有它连「清理旧标签」都做不到。
        //    这也是旧实现最大的结构性缺陷 —— 各种早退都直接 return，旧标签永久残留。
        val holder = a[0] ?: return
        val pos = a[1] as? Int ?: return
        val itemView = itemViewOf(holder) ?: return
        fItem.incrementAndGet()
        // 一次性诊断（只打前 3 次）：`types` 探针显示 itemView 竟然是整个
        // `ImproveRecyclerView`（150 个子 View、4 组 header/content/footer），
        // 说明 `onBindViewHolder` 的 holder 可能是外层容器的 ViewHolder，
        // 而不是单条动态。这里把 holder / itemView 的真实身份打出来定案。
        if (fItem.get() <= 3) {
            logBind("SNSC_TAG holder#${fItem.get()} ${holder.javaClass.name} " +
                "itemView=${itemView.javaClass.name} " +
                "childCount=${(itemView as? ViewGroup)?.childCount ?: -1} " +
                "isImproveRv=${itemView.javaClass.name.contains("ImproveRecyclerView")}")
        }
        bindLogged.set(0) // 让后面的 snsHit 日志不被前面的额度挤掉
        // 只给 `probe("types"/"rv"/"kids"/"ids")` 诊断用。
        // ⚠️ **必须只记朋友圈那条列表**：`onBindViewHolder` 同时被多任务面板
        //    （`OverScrollMultiTaskRecyclerView`）等一堆无关 adapter 调用，
        //    记「最后一次」会随机落到它们身上，探针就打出一个完全无关的树 ——
        //    真机实测过：`probe("ids")` 打出 `item=OverScrollMultiTaskRecyclerView rows=0`，
        //    看起来像「精确路径坏了」，其实只是看错了对象。
        if (itemView.javaClass.name.contains("ImproveRecyclerView")) {
            if (itemView !== lastItemView?.get()) lastItemView = java.lang.ref.WeakReference(itemView)
        }

        // 关开关后不能一走了之：屏上/回收池里的 itemView 还挂着我们的标签，
        // 复用后会把 `[已删除]` 显示到无关的动态上。所以关开关时**至少清一次**。
        if (!enabled) {
            if (collectLabels(itemView).isNotEmpty()) {
                reconcileAndApply(itemView, -1L, emptyList(), quiet = true)
            }
            return
        }

        val snsId = snsIdOfItem(param.thisObject, pos, itemView)

        // 取不到 snsId（`snsIdOfItem` 在 ±2 窗口里找不到唯一值时会返回 null，属于正常边界情况）。
        // 无法判断归属时**只摘「确定不属于这条动态」的标签**，不能盲清：
        // 盲清会把当前这条动态的正确标签一起摘掉，下一轮 bind 又重新打上 →
        // 每帧摘一次打一次的闪动。所以走严格模式（只摘 snsId 明确不同的）。
        if (snsId == null) {
            dropForeignLabels(itemView, null)
            return
        }
        fSns.incrementAndGet()
        // 环形采样：把最近读到的 snsId 留下来给 probe 看。
        // ⚠️ 不能只靠 logBind —— 那份额度只有 5 条，进程一启动就被 probe 日志吃光了，
        //    结果就是「明明拿到了 snsId 却一条日志都看不见」，白白多跑几轮真机。
        recentSns.addFirst(snsId)
        while (recentSns.size > 16) recentSns.pollLast()
        // 一次性记录：把屏幕上真实出现的 snsId 打出来，供「造测试数据」用。
        // 没有这行就只能靠猜 id，而 probe("mark") 需要精确 snsId 才能验证落点。
        if (fSns.get() <= 8) logBind("SNSC_TAG snsHit#${fSns.get()} snsId=$snsId")

        // ── 期望集合：本次 bind 应该出现哪些标签（0~N 个）──
        val desired = ArrayList<SnsCommentLabelLogic.LabelOwner>(2)

        if (forceSet.contains(snsId)) {
            // 探针的强制模式：不看 commentSvrID，直接给整条动态打标签，用来验证落点
            desired += SnsCommentLabelLogic.LabelOwner(
                snsId, SnsCommentLabelLogic.Scope.CARD, null, true,
            )
        } else {
            val svrIds = delMap[snsId]
            when {
                // 库里还没加载完：只清不打，别让旧标签赖在屏上
                delMap.isEmpty() -> loadAsync() // ⚠️ 主线程不查库，丢后台

                svrIds.isNullOrEmpty() -> Unit // 这条动态没有已删评论 → desired 留空 = 只摘不补

                else -> {
                    fDel.incrementAndGet()
                    // svrIds 非空 ⇒ 库里确实有「这条动态的已删评论」，但归档会随时间增长
                    // （别人删评论是随时发生的）。所以按 SET_TTL_MS 定期重载，
                    // 否则 delMap 一旦非空就再也不刷新 → 新删的评论本次进程内永远打不上标签。
                    if (SystemClock.elapsedRealtime() - setAt > SET_TTL_MS) loadAsync()

                    val getId = idOf
                    if (getId != null) {
                        // ① 精确路径：评论 View → commentSvrID（improve 链上是 View 自带字段）
                        val rows = rowsOf(itemView, pendingRowCls ?: return)
                        // ⚠️ rows 为空 = **这条动态当前没有评论行**（未展开 / 被折叠 / 屏外）。
                        //    这是**精确信息**（确定不该有行级标签），绝不能当成「读不出 ID」
                        //    去跑正文对账 —— 那条路会在正文容器上误打标（复核 P1 #1）。
                        if (rows.isEmpty()) {
                            reconcileAndApply(itemView, snsId, emptyList())
                            return
                        }
                        fRows.incrementAndGet()
                        var precise = 0
                        for (row in rows) {
                            // null = 读不到可用的三元组（未绑定 / talker 空 / time<=0）→ 跳过，
                            // 后面按 precise==0 判定为「未知」。
                            val key = getId(row) ?: continue
                            precise++
                            fKeyOk.incrementAndGet()
                            // ⚠️ 每个读出的 key **都**跑一次错位检测，不能只放在 else 里：
                            //    一条动态可能「精确命中 1 条 + type 错位 1 条」，
                            //    只统计未命中的会让 typeMiss 假阴性（复核 2026-10-19 P1）。
                            if (SnsCommentLogic.typeOnlyMismatch(key, svrIds)) fTypeMiss.incrementAndGet()
                            if (svrIds.contains(key)) {
                                desired += SnsCommentLabelLogic.LabelOwner(
                                    snsId, SnsCommentLabelLogic.Scope.ROW, null, false, key,
                                )
                            }
                        }
                        if (precise > 0) fGot.incrementAndGet()
                        // ⛔ **不能再回落降级**（复核 2026-10-19 P1）：
                        //    降级路径 `rowOwnersByText` 是拿归档正文跟**整棵 itemView 子树**的
                        //    全部 TextView 对账，且对每一处命中都 addView（不是只打"读不出 key 的那一行"）。
                        //    候选来自 `textCandidates` 的宽松切片 + `textHit` 允许包含匹配 ⇒
                        //    「哈哈哈哈哈」「好的好的」这类短评很容易命中**同一卡片里没被删的**评论，
                        //    直接误标。改造前 null 只在"路径完全不可用"时出现，触发面小；
                        //    现在"读到但不可用"也合并成 null，触发面被放大。
                        //    ⇒ 精确路径已接通、行也在、但 key 全读不出 = **未知**。
                        //      按铁律「宁可漏标不可误标」：只摘不补，等下一帧 bind 再试。
                        if (precise == 0) {
                            reconcileAndApply(itemView, snsId, emptyList())
                            return
                        }
                    } else {
                        // ② 降级路径：评论行 → commentSvrID 未解析成功，改用「归档正文 ↔ 界面文案」对账定位。
                        //    只用 Android 标准 API（遍历 TextView），不碰任何未取证的微信类名/字段名。
                        val cands = textsFor(snsId)
                        if (cands == null) {
                            // 缓存还没填好：**先清旧标签再 return**，等下一轮 bind 自会再来
                            reconcileAndApply(itemView, snsId, emptyList())
                            return
                        }
                        desired += rowOwnersByText(itemView, snsId, cands)
                        if (desired.isEmpty()) {
                            degradeHits.incrementAndGet()
                            logOnce("SNSC_TAG degrade: snsId=$snsId 有 ${svrIds.size} 条已删评论，" +
                                "精确路径不可用且正文对账没命中 -> skip")
                        }
                    }
                }
            }
        }
        reconcileAndApply(itemView, snsId, desired)
    }

    /**
     * 执行一次「先摘后补」。**正常 bind 路径的唯一出口** —— 早退分支也必须走这里（传 `desired=[]`）。
     *
     * @param snsId   本次 bind 的动态 id，只用于日志
     * @param desired 本次该出现的标签；空表 = 这条动态不该有任何标签（全部摘掉）
     * @param quiet   true = 不写 reconcile 日志（关开关时的清理用，避免刷屏）
     */
    private fun reconcileAndApply(
        itemView: View,
        snsId: Long,
        desired: List<SnsCommentLabelLogic.LabelOwner>,
        quiet: Boolean = false,
    ) {
        // ① 收集实况：当前子树里还挂着哪些我们的标签（顺序即下标，摘的时候要用）
        val found = collectLabels(itemView)
        if (found.isEmpty() && desired.isEmpty()) return // 干净且无需打 → 最快的常见路径
        val owners = found.map { it.second.owner }

        // ② 决策（纯逻辑，可单测）
        val plan = SnsCommentLabelLogic.reconcile(owners, desired)

        // ③ 先摘：必须**降序** removeView，否则前面的下标会随着删除前移，摘错对象
        for (i in plan.removeIdx.sortedDescending()) {
            val tv = found.getOrNull(i)?.first ?: continue
            // `removeView` 返回 void，无法回报是否真的摘掉；这个判断只保证「parent 存在才尝试摘」，
            // 避免 tv.parent 已被复用链清空时把没摘成功的算进计数。
            if (runCatching { (tv.parent as? ViewGroup)?.removeView(tv) != null }.getOrDefault(false)) {
                removed.incrementAndGet()
            }
        }

        // ④ 再补：`applyTarget` 负责把 LabelOwner 映射回树上的落点
        for (owner in plan.applyOwners) {
            applyTarget(itemView, owner)
        }

        if (!quiet && (plan.removeIdx.isNotEmpty() || plan.applyOwners.isNotEmpty())) {
            logOnce("SNSC_TAG reconcile snsId=$snsId removed=${plan.removeIdx.size} " +
                "applied=${plan.applyOwners.size} reason=${plan.reason}")
        }
    }

    /**
     * 严格清理：只摘**能证明不属于当前动态**的标签。
     *
     * 用于 `snsId` 取不到的场景。为什么不能直接 `reconcileAndApply(itemView, -1, emptyList())`
     * 全清：那样会把「当前这条动态本来正确的标签」也摘掉，而下一轮 bind 又重新打上 ——
     * `snsIdOfItem` 在 ±2 窗口有歧义时可能连续几帧都返回 null，就会变成每帧摘了又打、标签闪烁。
     *
     * 判定：标签上记的 `snsId` 与 [currentSnsId] 不同就是外来标签。
     *
     * @param currentSnsId 当前动态 id；**null = 未知**，此时什么都不摘 ——
     *                     连这条动态是谁都不知道，就无法证明任何标签属于别的动态，
     *                     盲摘会导致「摘了下一轮又打上」的闪烁。
     *                     （注意：[LabelTag] 的 owner 是非空类型，「没记归属的老标签」在本代码里不存在）
     */
    private fun dropForeignLabels(itemView: View, currentSnsId: Long?) {
        val found = collectLabels(itemView)
        if (found.isEmpty()) return
        var n = 0
        for ((tv, tag) in found) {
            val foreign = if (currentSnsId == null) false else tag.owner.snsId != currentSnsId
            if (!foreign) continue
            if (runCatching { (tv.parent as? ViewGroup)?.removeView(tv) != null }.getOrDefault(false)) n++
        }
        if (n > 0) {
            removed.addAndGet(n)
            logOnce("SNSC_TAG drop foreign n=$n curSnsId=${currentSnsId ?: "unknown"}")
        }
    }

    /**
     * 把一条 [SnsCommentLabelLogic.LabelOwner] 映射到树上的实际落点并打标。
     *
     * ⚠️ **CARD 与 ROW 的落点规则不同，不能混用**：
     * - CARD → 直接挂在 `itemView` 根（整卡级，仅 forceSet 探针会走到）；
     * - ROW + svrId 已知 → 用精确路径的 `idOf` 重新定位那一行；
     * - ROW + svrId 未知（**生产环境实际只有这条**）→ 无法反查具体哪一行，
     *   只能靠正文对账重新命中。这也是为什么 `desired` 里降级路径的 owner 不带 svrId：
     *   对账阶段已经知道落点了，但 reconcile 是纯逻辑、不持有 View，所以这里重算一次。
     */
    /**
     * `probe("mark")` 的落点：找一个**能安全 addView** 的容器。
     *
     * improve 链上 `itemView` 是 `ImproveRecyclerView`（整个列表），
     * [applyLabel] 拒绝往 RecyclerView 里 addView（会绕开 LayoutManager 崩溃，真机实锤）。
     * 所以这里往下找第一层不是 RecyclerView 的 ViewGroup ——
     * 在 improve 链上就是单条动态卡片（`lh3.f3` / `lh3.g2` 那类）。
     *
     * 找不到就退回原 itemView（让 [applyLabel] 自己去拒绝并记日志，行为可观测）。
     */
    private fun cardHostOf(root: View): View {
        if (!isRecyclerView(root)) return root
        if (root !is ViewGroup) return root
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i) ?: continue
            if (!isRecyclerView(c)) return c
        }
        return root
    }

    private fun applyTarget(itemView: View, owner: SnsCommentLabelLogic.LabelOwner) {
        when {
            owner.scope == SnsCommentLabelLogic.Scope.CARD -> {
                // ⚠️ CARD 的落点不能直接给 itemView：improve 链上 itemView **就是列表根**
                //    `ImproveRecyclerView`，而 [applyLabel] 有「拒绝往 RecyclerView 里 addView」
                //    的硬保护（真机实测会崩）⇒ 直接挂等于 100% 被拒，探针 mark 永远看不出效果。
                //    这里换成「第一个不是 RecyclerView 的容器子 View」，即单条动态卡片本身。
                applyLabel(cardHostOf(itemView), LABEL, owner)
            }

            owner.renderKey != null -> {
                val getId = idOf ?: return
                val rowCls = pendingRowCls ?: return
                val want = owner.renderKey
                for (row in rowsOf(itemView, rowCls)) {
                    if (getId(row) == want) {
                        applyLabel(row, LABEL, owner)
                        return
                    }
                }
            }

            owner.svrId != null -> {
                // ⛔ **已废弃分支，保留仅为编译期兼容**（复核 2026-10-19 P1）。
                //    三处 LabelOwner 构造（CARD / 精确 ROW / 降级 ROW）的 svrId 全传 null，
                //    生产路径永远走不到这里；而且 `getId` 返回的是 `"time:talker:type"`，
                //    与 `svrId.toString()`（纯数字）永不相等 ⇒ 即便进来也是静默无操作。
                //    真正的渲染定位在下面的 `else` 分支（正文对账）。
                val cands = textsFor(owner.snsId) ?: return
                val hosts = textHostsFor(itemView, cands)
                for (h in hosts) applyLabel(h, LABEL, owner)
            }

            else -> {
                // ROW + svrId 未知：按正文对账重新定位（与 rowOwnersByText 的判据完全一致，
                // 否则会出现「算出了 desired 却打不上去」的静默空洞）
                val cands = textsFor(owner.snsId) ?: return
                val hosts = textHostsFor(itemView, cands)
                for (h in hosts) applyLabel(h, LABEL, owner)
            }
        }
    }

    /**
     * 降级定位：拿归档里「删之前」的评论正文候选，跟 itemView 子树里的 TextView 文案对账，
     * 判断本次 bind **该不该出现一个行级标签**。
     *
     * ⚠️ 只认 [SnsCommentLogic.textHit] 判定的命中（严格：短碎片、完全对不上都不算），
     * 宁可 missed 涨也不能误标。
     *
     * ## ⚠️ 命中 N 处时只能返回 **1 个** owner，不能返回 N 个
     *
     * 这是复核抓出来的一个 P0（原实现返回 N 个、标签被打成 N² 个）：
     * 降级路径的 owner 恒为 `(snsId, ROW, svrId=null, false)`，N 个命中**彼此完全相同**。
     * 而 [SnsCommentLabelLogic.reconcile] 的幂等只拿 `desired` 跟「摘完剩下的旧标签」比，
     * **不跟 `desired` 内部比**，所以 N 个相同 owner 会全部进入 `applyOwners`；
     * 偏偏 [applyTarget] 的降级分支本身又会对 `textHostsFor` 的全部命中落点各打一个 ——
     * 两层叠加就是 N×N。真实数据里一条动态多条已删评论是常态，必须挡住。
     *
     * 正确语义：**「这条动态该有一个行级标签」**，落点由 [applyTarget] 一次铺满。
     */
    private fun rowOwnersByText(
        itemView: View,
        snsId: Long,
        cands: List<List<String>>,
    ): List<SnsCommentLabelLogic.LabelOwner> {
        val hits = textHostsFor(itemView, cands)
        if (hits.isEmpty()) return emptyList()
        textHits.addAndGet(hits.size)
        // 只回 1 个（见上面的 N² 说明）；实际落点由 applyTarget → textHostsFor 重算。
        return listOf(
            SnsCommentLabelLogic.LabelOwner(
                snsId, SnsCommentLabelLogic.Scope.ROW, null, false,
            ),
        )
    }

    /** 正文对账的实际匹配逻辑：返回去重后的落点容器（按子树出现顺序） */
    private fun textHostsFor(itemView: View, cands: List<List<String>>): List<ViewGroup> {
        val out = ArrayList<ViewGroup>(2)
        for (tv in textViewsOf(itemView)) {
            // 我们已经加的标签自身不能再参与对账（它的 `[已删除]` 文案会跟着进候选）
            if (tv.tag is LabelTag) continue
            val t = tv.text?.toString() ?: continue
            if (t.contains(LABEL)) continue // 已经打过了（正文里自带该字样，别再对账）
            if (cands.none { SnsCommentLogic.textHit(it, listOf(t)) }) continue
            val host = tv.parent as? ViewGroup ?: continue
            if (out.none { it === host }) out += host // 同一容器只算一次
        }
        return out
    }

    /** 后台线程填充 [textCache]；主线程绝不查库 */
    private fun textsFor(snsId: Long): List<List<String>>? {
        synchronized(textCache) {
            if (textCache.containsKey(snsId)) return textCache[snsId]
        }
        if (wantTexts.add(snsId)) loadTextsAsync()
        return null
    }

    private fun loadTextsAsync() {
        if (!loadingTexts.compareAndSet(false, true)) return
        bg.execute {
            runCatching {
                val all = SnsCommentGuard.archivedTexts(TEXT_LIMIT)
                val ids = synchronized(wantTexts) {
                    wantTexts.toList().also { wantTexts.clear() }
                }
                synchronized(textCache) {
                    if (textCache.size > TEXT_CACHE_MAX) textCache.clear()
                    // ⚠️ all[id] 为 null = 「查过了，这条动态没有可定位的已删评论」，
                    //    照样存进去，靠 containsKey 记住「查过了」，避免反复回库
                    for (id in ids) textCache[id] = all[id]
                }
            }.onFailure {
                logOnce("SNSC_TAG load texts failed: ${it.javaClass.simpleName}")
            }
            loadingTexts.set(false)
        }
    }

    /** ViewHolder 的 `itemView`（先按名字取，取不到再扫字段） */
    private fun itemViewOf(holder: Any): View? {
        runCatching { XposedHelpers.getObjectField(holder, "itemView") as? View }
            .getOrNull()?.let { return it }
        return runCatching {
            holder.javaClass.fields.firstOrNull {
                View::class.java.isAssignableFrom(it.type)
            }?.get(holder) as? View
        }.getOrNull()
    }

    /**
     * item ↔ snsID：`getData()[pos]` 的**下标可能对不齐**（列表第一项是封面头），
     * 所以在 ±2 窗口里找；找到多个不同 snsId 就放弃（宁可不打标）。
     */
    private fun snsIdOfItem(adapter: Any?, pos: Int, itemView: View): Long? {
        if (adapter == null) return null
        val list = runCatching { XposedHelpers.callMethod(adapter, "getData") as? List<*> }
            .getOrNull() ?: return null
        val texts = textsOf(itemView)
        val hits = HashSet<Long>()
        // ⚠️ 读不到任何文字就放弃：没法对账，打错标签比不打标签糟糕得多
        if (texts.isEmpty()) return null
        for (i in (pos - 2)..(pos + 2)) {
            val e = list.getOrNull(i) ?: continue
            val info = SnsTagLogic.parseInfo(e.toString()) ?: continue
            if (SnsTagLogic.nameOk(info.name, texts)) hits += info.snsId
        }
        return hits.singleOrNull()
    }

    private fun textsOf(root: View): List<String> {
        val out = mutableListOf<String>()
        val stack = ArrayDeque<View>()
        stack += root
        var budget = 60 // 兜底：别在异常深的树上跑太久
        while (stack.isNotEmpty() && budget-- > 0) {
            val v = stack.removeLast()
            if (v is TextView) v.text?.toString()?.let { if (it.isNotEmpty()) out += it }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) v.getChildAt(i)?.let { stack += it }
            }
        }
        return out
    }

    /** itemView 子树里所有的 TextView（降级对账用；带预算，别在异常深的树上跑太久） */
    private fun textViewsOf(root: View): List<TextView> {
        val out = mutableListOf<TextView>()
        val stack = ArrayDeque<View>()
        stack += root
        var budget = 60
        while (stack.isNotEmpty() && budget-- > 0) {
            val v = stack.removeLast()
            if (v is TextView) out += v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) v.getChildAt(i)?.let { stack += it }
            }
        }
        return out
    }

    /** 取 itemView 子树里所有的「评论行」View */
    private fun rowsOf(root: View, cls: Class<*>): List<View> {
        val out = mutableListOf<View>()
        val stack = ArrayDeque<View>()
        stack += root
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            if (cls.isInstance(v)) out += v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) v.getChildAt(i)?.let { stack += it }
            }
        }
        return out
    }

    private fun isRecyclerView(v: View): Boolean {
        var c: Class<*>? = v.javaClass
        var up = 0
        while (c != null && up < 8) {
            if (c.name == "androidx.recyclerview.widget.RecyclerView") return true
            c = c.superclass
            up++
        }
        return false
    }

    private fun applyLabel(
        host: View,
        text: String,
        owner: SnsCommentLabelLogic.LabelOwner,
    ) {
        if (host !is ViewGroup) return
        // ⚠️ 往 RecyclerView 里直接 addView 会绕开 LayoutManager 的 child 管理，
        //    实测会把微信搞崩 —— 宁可什么都不做
        //    （这里按**类名**判，不 import androidx：模块没有 androidx 依赖）
        if (isRecyclerView(host)) {
            logOnce("SNSC_TAG: refuse to label Rv, host=${host.javaClass.name}")
            return
        }
        // 幂等判断已移除：光看「容器里有没有我的标签」在复用下是错的（会把上一条动态的
        // 标签当成这一条的）。该不该补、该不该摘统一由上层 reconcile 按归属决策。
        val tv = TextView(host.context).apply {
            this.text = text
            // 存归属而不是裸哨兵：复用后要能回答「这个标签属于哪条动态/评论」
            tag = LabelTag(owner, text)
            setTextColor(0xFFFF5252.toInt())
            textSize = 11f
        }
        runCatching {
            when (host) {
                is LinearLayout -> host.addView(tv, 0)
                is FrameLayout -> host.addView(tv, 0)
                else -> host.addView(tv, 0)
            }
        }.onFailure {
            logOnce("SNSC_TAG addView failed: ${it.javaClass.simpleName}")
            return
        }
        tagged += WeakReference(tv)
        added.incrementAndGet()
    }

    /**
     * 扫 itemView 子树里我们自己加的全部标签，按出现顺序返回它们的归属。
     * 顺序即下标 —— 上层要用这个下标去 removeView。
     *
     * 为什么不用 [tagged]：那张表是全局的，回答不了「这棵子树里现在有哪些标签」；
     * reconcile 必须基于**当前子树**的实况决策。
     */
    private fun collectLabels(root: View): List<Pair<TextView, LabelTag>> {
        val out = mutableListOf<Pair<TextView, LabelTag>>()
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        while (stack.isNotEmpty()) {
            val (v, depth) = stack.removeLast()
            val t = v.tag
            if (v is TextView && t is LabelTag) {
                // 我们自己加的 TextView 是叶子，不再往下走（也没必要）
                out += v to t
            }
            if (v is ViewGroup && depth < LABEL_TREE_MAX_DEPTH) {
                for (i in 0 until v.childCount) v.getChildAt(i)?.let { stack += it to depth + 1 }
            }
        }
        return out
    }

    /**
     * 摘掉我们加的所有标签（探针 / 关开关时用）。
     *
     * 顺手剔除已死条目：引用链是 `RecyclerView 缓存池 → itemView(强) → 子树 → 标签(强)`，
     * 只要宿主还在，WeakReference 就不会失效，光靠 GC 清不掉，列表只增不减。
     */
    private fun clearLabels() {
        synchronized(tagged) {
            val it = tagged.iterator()
            while (it.hasNext()) {
                val v = it.next().get()
                if (v == null) {
                    it.remove()
                    continue
                }
                runCatching { (v.parent as? ViewGroup)?.removeView(v) }
            }
            tagged.clear()
        }
    }

    // ------------------------------------------------------------------ 数据源

    /** ⚠️ 后台线程读归档库，主线程绝不查库 */
    private fun loadAsync() {
        if (!loading.compareAndSet(false, true)) return
        bg.execute {
            runCatching {
                // ⚠️ 用渲染键（createTime:talker:type）而不是 commentSvrID ——
                //    UI 层不带 svrID，用 svrID 做 key 会永远匹配不上（真机实测恒 0）。
                val m = SnsCommentGuard.archivedRenderKeys()
                delMap = m
                setAt = SystemClock.elapsedRealtime()
                if (m.isNotEmpty()) {
                    logOnce("SNSC_TAG loaded posts=${m.size} comments=" +
                        "${m.values.sumOf { it.size }}")
                }
            }.onFailure {
                logOnce("SNSC_TAG load failed: ${it.javaClass.simpleName}")
            }
            loading.set(false)
        }
    }

    /**
     * 诊断：dump 这一行 proto 的全部 long/int/String 字段。
     *
     * 就是靠它定案「`qJ=0 rJ=0` ⇒ UI 层不带 commentSvrID」的（`docs/取证结论.md` ⑩ 段）。
     * **留着**：将来微信改版本导致三元组字段名变了，用它一次就能重新认出
     * 「哪个 String 是 talker、哪个 int 是 createTime」，不用再走一遍静态反编译。
     */
    private fun protoDump(row: Any): String {
        val pf = improveProtoField ?: return "noField"
        val vc = improveViewCls
        if (vc != null && !vc.isInstance(row)) return "notThisType"
        val proto = runCatching { pf.get(row) }.getOrNull() ?: return "protoNull"
        // 把 q95 上所有 long/int/String 字段都 dump 出来。
        // 真机实测（2026-10-19 21:28）：`qJ=0 rJ=0` —— **q95 里根本没有 commentSvrID**，
        // ⑧/⑨ 段「r = commentSvrID」的假设被彻底推翻。
        // 同时 `iI=1792410895` → 2026-10-19 11:54:55 UTC = 评论创建时间。
        // ⇒ 改走微信自己的判重键：snsID + createTime + talker（见 `s1.q1` 的 isExistComment）。
        // 这里一并 dump String 字段，用来认出哪个是 talker。
        return runCatching {
            val sb = StringBuilder()
            var c: Class<*>? = proto.javaClass
            var up = 0
            while (c != null && up < 3) {
                for (f in c.declaredFields) {
                    val t = f.type
                    when {
                        t == Long::class.javaPrimitiveType ->
                            runCatching { f.isAccessible = true; sb.append("${f.name}J=").append(f.getLong(proto)).append(" ") }
                        t == Int::class.javaPrimitiveType ->
                            runCatching { f.isAccessible = true; sb.append("${f.name}I=").append(f.getInt(proto)).append(" ") }
                        t == String::class.java ->
                            runCatching {
                                f.isAccessible = true
                                val s = f.get(proto) as? String ?: ""
                                sb.append("${f.name}S='").append(s.take(28)).append("' ")
                            }
                    }
                }
                c = c.superclass
                up++
            }
            sb.toString().trim()
        }.getOrElse { "err" }
    }

    // ------------------------------------------------------------------ 诊断

    private fun logOnce(msg: String) {
        if (miscLogged.get() < LOG_MAX && miscLogged.incrementAndGet() <= LOG_MAX) LogX.i(msg)
    }

    /**
     * 探针。`sub`：`stat` / `list` / `mark <snsId>` / `unmark` / `pending` / `refresh`
     *
     * ⚠️ 关于 `svr`：**本函数体内没有任何地方读它**，它只是广播入参的透传
     * （`EntryReceiver` 按位置传进来，签名保留以免改到调用方）。
     * 原先「`svr == 0` ⇒ 未绑定、直接 return」的语义判定已随 renderKey 改造移除 ——
     * UI 层 proto 不带 commentSvrID（真机 `qJ=0 rJ=0`），匹配键改成
     * `createTime:talker:type` 后不再存在「按 svr 判零值」的分支。
     */
    fun probe(sub: String, id: Long = 0L, svr: Long = 1L, seedKey: String = ""): String {
        return when (sub.lowercase()) {
            "stat" -> "SNSC_TAG stat enabled=${Kv.getBool(Keys.ANTIREVOKE_COMMENT)}&&" +
                "${Kv.getBool(Keys.ANTIREVOKE_COMMENT_DELETED_TAG)} binds=${binds.get()} " +
                "added=${added.get()} removed=${removed.get()} textHits=${textHits.get()} " +
                "degrade=${degradeHits.get()} posts=${delMap.size} " +
                "precise=${idOf != null} ids=${preciseIds.get()} " +
                "tcache=${textCache.size} setAge=${(SystemClock.elapsedRealtime() - setAt) / 1000}s" +
                // 漏斗：item → snsId → 库里确有已删评论 → 找到评论行 → 读出 id
                // 哪一环掉到 0 就是卡点，不用再猜
                "\nSNSC_FUNNEL item=${fItem.get()} sns=${fSns.get()} del=${fDel.get()} " +
                "rows=${fRows.get()} got=${fGot.get()} " +
                // keyOk = 分母（实际比对过的行数）；typeMiss>0 ⇒ 键语义错位（不是没删）
                // 判读：keyOk>0 && typeMiss==0 = type 语义一致；keyOk==0 = 无数据，别下结论
                "keyOk=${fKeyOk.get()} typeMiss=${fTypeMiss.get()}" +
                // 最近读到的 snsId：造测试数据（probe seed）靠它，别再靠猜
                "\nSNSC_FUNNEL recentSns=" + recentSns.take(8).joinToString(",")

            "list" -> {
                if (delMap.isEmpty()) "SNSC_TAGLS (empty) 归档里没有已删评论"
                else buildString {
                    appendLine("SNSC_TAGLS posts=${delMap.size}")
                    delMap.entries.take(10).forEach { (k, v) ->
                        appendLine("SNSC_TAGLS snsId=$k keys=${v.sorted()}")
                    }
                    append("(最多显示 10 条)")
                }
            }

            "mark" -> {
                if (id == 0L) return "SNSC_TAG mark: need snsId"
                forceSet = setOf(id)
                "SNSC_TAG mark snsId=$id -> 打开朋友圈刷到这条动态，" +
                    "看标签落点；验证完用 probe(\"unmark\") 摘掉"
            }

            "unmark" -> {
                forceSet = emptySet()
                clearLabels()
                "SNSC_TAG unmark ok"
            }

            // 降级对账用的正文候选：确认归档里到底抽没抽出可读正文
            "texts" -> buildString {
                val snap = synchronized(textCache) { textCache.entries.take(5) }
                if (snap.isEmpty()) {
                    append("SNSC_TAGTEXT (empty) 缓存还没填 —— 先让朋友圈刷到有已删评论的动态")
                } else {
                    appendLine("SNSC_TAGTEXT tcache=${textCache.size} 显示前 5 条：")
                    for ((k, v) in snap) {
                        appendLine("SNSC_TAGTEXT snsId=$k comments=${v?.size ?: 0} " +
                            "first=${v?.firstOrNull()?.take(3)}")
                    }
                    append(("(null = 查过了但归档里没有可定位的正文)"))
                }
            }

            "pending" -> buildString {
                appendLine("SNSC_TAG 精确路径状态（improve 链，指令级取证 2026-10-19）：")
                appendLine("T3 评论 View：$IMPROVE_COMMENT_VIEW_CLASS")
                appendLine("   它自带字段 $IMPROVE_COMMENT_PROTO_FIELD: Lq54/q95（setupCommentLayout 每帧 iput）")
                appendLine("T4 取值：q95 的三元组 " +
                    "$COMMENT_TALKER_FIELD(String) / $COMMENT_TIME_FIELD(int) / $COMMENT_TYPE_FIELD(int)")
                appendLine("⛔ 不用 commentSvrID：UI 层不带（q/r 两个 long 真机恒 0，见 docs ⑩ 段）")
                appendLine("✅ 从 View 直接读，不 hook 任何方法 ⇒ 无「钩子没被调用」的静默失效")
                appendLine("落点容器：${pendingRowCls?.name ?: "(未解析)"}")
                append("   当前 precise=${idOf != null} ids=${preciseIds.get()}" +
                    (if (idOf == null) " → 未解析成功，全量走降级（正文对账）"
                    else " → 走精确路径（ids 随朋友圈滚动增长即证明生效）"))
            }

            // 诊断：从最后那个 itemView（= ImproveRecyclerView）反查它**真正的 adapter 类名**。
            //
            // 为什么要这个探针：`types` 已证实我们 hook 的 `WxRecyclerAdapter.onBindViewHolder`
            // 收到的 itemView 是整个 ImproveRecyclerView，holder 是 `ki4.m0` ——
            // 那是「把 RecyclerView 塞进容器」的那层，**不是单条动态的绑定**。
            // improve 时间线的 item 绑定走的是 `lh3.x1`（LinearLayout 子类，自带
            // getHolder/setHolder/setData/setSnsPosition），根本不经过 WxRecyclerAdapter。
            // ⇒ 必须拿到真实 adapter 的类名，才能改挂到正确的方法上。
            "rv" -> {
                val snap = lastItemView?.get()
                if (snap == null) "SNSC_TAGRV (no itemView yet)"
                else buildString {
                    appendLine("SNSC_TAGRV itemView=${snap.javaClass.name}")
                    var rv: View? = snap
                    if (rv !is ViewGroup) rv = null
                    // 从 itemView 往上/往下找真正的 RecyclerView 实例
                    var found: Any? = null
                    var cur: View? = snap
                    var up = 0
                    while (cur != null && up < 8 && found == null) {
                        if (cur.javaClass.name.contains("RecyclerView")) found = cur
                        cur = (cur.parent as? View)
                        up++
                    }
                    if (found == null) { append("SNSC_TAGRV 没找到 RecyclerView"); return@buildString }
                    appendLine("SNSC_TAGRV rv=${(found as View).javaClass.name}")
                    val ad = runCatching {
                        found.javaClass.getMethod("getAdapter").invoke(found)
                    }.getOrNull()
                    if (ad == null) { append("SNSC_TAGRV getAdapter=null"); return@buildString }
                    appendLine("SNSC_TAGRV adapter=${ad.javaClass.name}")
                    appendLine("SNSC_TAGRV adapterSuper=${ad.javaClass.superclass?.name}")
                    // 反射列出 adapter 类层次里所有 onBindViewHolder 重载 ——
                    // 这是唯一可靠的判据：`u1`/`ju2.s0` 不覆写它就说明挂基类是死钩，
                    // 必须改挂 `g`（Kotlin 的 onUpdateAdapter）
                    var ac: Class<*>? = ad.javaClass
                    var lvl = 0
                    while (ac != null && lvl < 6) {
                        val ms = ac.declaredMethods.filter { it.name == "onBindViewHolder" }
                        if (ms.isNotEmpty()) {
                            appendLine("SNSC_TAGRV ${ac.simpleName}.onBindViewHolder x${ms.size}: " +
                                ms.joinToString(" | ") { m ->
                                    "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")"
                                })
                        }
                        val gs = ac.declaredMethods.filter { it.name == "g" }
                        if (gs.isNotEmpty()) {
                            appendLine("SNSC_TAGRV ${ac.simpleName}.g x${gs.size}: " +
                                gs.joinToString(" | ") { m ->
                                    "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")"
                                })
                        }
                        ac = ac.superclass
                        lvl++
                    }
                    val n = runCatching {
                        (ad.javaClass.getMethod("getItemCount").invoke(ad) as? Int) ?: -1
                    }.getOrDefault(-1)
                    append("SNSC_TAGRV itemCount=$n")
                }
            }

            // 诊断：从 ImproveRecyclerView 的 **子 View** 里找单条动态容器（lh3.x1）。
            //
            // `types` 已证实 itemView 子树里 improve=0（没有 ImproveCommentView），
            // `rv` 已证实 adapter=u1 itemCount=32。现在只剩一个问题：
            // 那 32 条动态的 View 到底在哪里？improve 的 item 容器是 `lh3.x1`
            // （LinearLayout 子类，自带 getHolder():ki4.m0 / getData():qh3.a / getCommentView()）。
            "kids" -> {
                val snap = lastItemView?.get()
                if (snap == null) "SNSC_TAGKIDS (no itemView yet)"
                else buildString {
                    appendLine("SNSC_TAGKIDS itemView=${snap.javaClass.simpleName} " +
                        "childCount=${(snap as? ViewGroup)?.childCount ?: -1}")
                    // 从 ImproveRecyclerView 往下走 3 层，把每一层出现过的类名打出来
                    var lvl = listOf<View>(snap)
                    for (d in 0..3) {
                        val next = ArrayList<View>()
                        val names = LinkedHashMap<String, Int>()
                        for (v in lvl) {
                            names[v.javaClass.simpleName] = (names[v.javaClass.simpleName] ?: 0) + 1
                            if (v is ViewGroup) {
                                for (i in 0 until v.childCount) v.getChildAt(i)?.let { next += it }
                            }
                        }
                        appendLine("SNSC_TAGKIDS L$d n=${lvl.size} ${names.entries.joinToString(" ") { "${it.key}x${it.value}" }}")
                        lvl = next
                        if (lvl.isEmpty()) break
                    }
                    append("SNSC_TAGKIDS done")
                }
            }

            // 诊断：把**最后一次 bind 的 itemView 子树**里出现的类名/数量打出来。
            // 用途：当「precise=true 但 ids 不涨」时，直接看树上到底有没有 ImproveCommentView，
            // 而不是靠猜（这类问题只看计数器永远分不清「钩子没跑」还是「树上没有目标 View」）。
            "types" -> {
                val snap = lastItemView?.get()
                if (snap == null) "SNSC_TAGTYPES (no itemView yet) 还没发生过 bind"
                else {
                    val counts = LinkedHashMap<String, Int>()
                    val stack = ArrayDeque<View>()
                    stack += snap
                    var total = 0
                    while (stack.isNotEmpty() && total < 4000) {
                        val v = stack.removeLast()
                        total++
                        counts[v.javaClass.name] = (counts[v.javaClass.name] ?: 0) + 1
                        if (v is ViewGroup) {
                            for (i in 0 until v.childCount) v.getChildAt(i)?.let { stack += it }
                        }
                    }
                    buildString {
                        appendLine("SNSC_TAGTYPES total=$total item=${snap.javaClass.simpleName} " +
                            "classes=${counts.size}")
                        counts.entries.sortedByDescending { it.value }.take(14).forEach { (k, v) ->
                            appendLine("SNSC_TAGTYPES $v x ${k.substringAfterLast('.')}")
                        }
                        append("SNSC_TAGTYPES improve=${counts.keys.count { it.contains("ImproveCommentView") }} " +
                            "collapse=${counts.keys.count { it.contains("SnsCommentCollapseLayout") }}")
                    }
                }
            }

            "refresh", "reload" -> {
                refresh()
                loadAsync()
                "SNSC_TAG refreshed"
            }

            // 验证用：往**内存 delMap** 里强行塞一条「当前屏幕上真实存在的动态」的已删评论。
            //
            // 为什么必须有这个：归档库里只有历史 seed 数据（snsId 不在当前时间线上），
            // 所以 `SNSC_FUNNEL del` 恒 0 ⇒ 精确路径那段代码**从来没被执行过**。
            // 没有真删评论的事件就没法自然产生数据，而人工删评论又会污染真实归档。
            // ⇒ 只在内存里注一条，不动数据库，`probe("refresh")` 即恢复。
            //
            // ⚠️ 传的必须是**屏幕上真实读到的渲染键**（用 `probe("ids")` 抄一行 `key=`），
            //    形如 `1792410895:wxid_jsmu3n3ucorv22:2`。传任意值只能验证
            //    「精确路径有没有被执行」，打不上属于预期。
            "seed" -> {
                if (id == 0L) {
                    return "SNSC_TAG seed: need snsId (--el id <snsId>) 和 " +
                        "--es key <createTime:talker:type>（用 probe(\"ids\") 抄真实值）"
                }
                if (seedKey.isBlank()) {
                    return "SNSC_TAG seed: 缺渲染键。先 probe(\"ids\") 抄一行 key=xxx，" +
                        "再 seed --el id <snsId> --es key <那个 key>"
                }
                val cur = delMap.toMutableMap()
                cur[id] = (cur[id] ?: emptySet()) + seedKey
                delMap = cur
                setAt = SystemClock.elapsedRealtime()
                "SNSC_TAG seed 已注入（仅内存）snsId=$id key=$seedKey " +
                    "→ 滚到这条动态看 ids 是否上涨；probe(\"refresh\") 恢复"
            }

            // 验证用：把屏幕上**当前真实读到的渲染键**打出来。
            // 精确路径是否真的在跑，看这个有没有值，而不是看 ids 计数器。
            "ids" -> {
                val snap = lastItemView?.get()
                if (snap == null) "SNSC_TAGIDS (no itemView yet)"
                else {
                    val get = idOf
                    val rowCls = pendingRowCls
                    if (get == null || rowCls == null) "SNSC_TAGIDS 精确路径未解析"
                    else {
                        val rows = rowsOf(snap, rowCls)
                        // ⚠️ child / attached 是区分「扫到的是空容器」与「树上真没评论行」的唯一判据
                        // （2026-10-20 R8-L5 复核建议，为下次真机一次判死而加）：
                        //   child=0            ⇒ 扫根是个空容器 ⇒ 扫描对象选错（非「本无评论」）
                        //   attached=false     ⇒ 该实例已离屏 / 已销毁
                        //   child>0 && rows=0  ⇒ 树非空但没匹配 rowCls 的行 ⇒ 类型或结构问题
                        val child = (snap as? ViewGroup)?.childCount ?: -1
                        val attached = runCatching { snap.isAttachedToWindow }.getOrDefault(false)
                        buildString {
                            appendLine("SNSC_TAGIDS item=${snap.javaClass.simpleName} " +
                                "rowCls=${rowCls.simpleName} rows=${rows.size} " +
                                "child=$child attached=$attached")
                            rows.take(12).forEachIndexed { i, r ->
                                val v = runCatching { get(r) }.getOrNull()
                                appendLine("SNSC_TAGIDS #$i ${r.javaClass.simpleName} " +
                                    "key=${v ?: "null"}")
                                // 字段 dump 只打第一行：改版本重认字段名时看一行就够，
                                // 全打会把日志刷爆（每行 20+ 字段）
                                if (i == 0) appendLine("SNSC_TAGIDS   proto=${protoDump(r)}")
                            }
                            append("SNSC_TAGIDS preciseIds=${preciseIds.get()}")
                        }
                    }
                }
            }

            else -> "SNSC_TAG probe: stat | list | texts | types | rv | kids | ids | " +
                "seed <snsId> <svrId> | mark <snsId> | unmark | pending | refresh"
        }
    }
}
