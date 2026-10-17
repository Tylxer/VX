package com.lx.wxgroup.feature

/**
 * 需求 6.1「关闭拍一拍」的**纯判据**（零 Android 依赖，可 JVM 单测）。
 *
 * 功能语义（docs/需求功能清单.md:152「禁用双击头像触发拍一拍」）：
 * 开关开启后**阻止自己发出拍一拍**（聊天页双击对方头像不触发）。
 * 备选方向是阻断拍一拍消息的收发，方向二选一由真机取证结果定，
 * 见 [com.lx.wxgroup.feature.PatBlock] 类注释。
 *
 * ⚠️ 本文件**禁止 import 任何 android.* / xposed.* / 项目内其它类**，否则单测加载不了。
 *
 * ## 取证状态（2026-10，lane/pat）
 * `docs/取证结论.md` **没有拍一拍相关锚点**：8.0.48/2588 GP 的触发类、方法、
 * 参数个数均未定位。因此：
 * - hook 主体（拦截双击触发）保持「待取证」，见 PatBlock 顶部常量；
 * - 本文件只交付**不依赖微信私有 API** 的判据：开关语义、系统消息文本形态、
 *   以及真机反射枚举取证时的候选筛选；
 * - 真机取证入口：广播 `op=quiet sub=pat sub2=dbltap`（hook Android framework 的具体方法
 *   `GestureDetector#onTouchEvent`，反射读出 `mDoubleTapListener` 的真实实现类并 dump
 *   调用栈，零猜测锚点），拿到真实类名/方法名后回填 PatBlock 常量即可生效。
 */
object PatLogic {

    // ------------------------------------------------------------------ 开关判据

    /**
     * `chat.disable_pat` 的取值判据。
     *
     * Kv 里一切值都是字符串（见 [com.lx.wxgroup.data.Keys] 设计要点 3），
     * 开关正常写 "true"/"false"；这里同时兼容 "1"（与
     * [com.lx.wxgroup.data.Kv.getBool] 的旧写法兼容保持一致）。
     * 读不到 / 脏值一律按**关**处理（宁可漏拦也不误拦——误拦会让双击头像彻底失灵）。
     */
    fun enabled(raw: String?): Boolean = when (raw?.trim()?.lowercase()) {
        "true", "1" -> true
        else -> false
    }

    // ------------------------------------------------------------------ 拍一拍消息文本判据

    /**
     * 拍一拍系统消息的核心文案。
     *
     * 依据：需求原文「对方收到『XXX 拍了拍 XXX』的系统消息」
     * （docs/需求功能清单.md:152；微信 7.0.14 起该文案稳定）。
     * 已知形态（含自定义尾巴）：
     * - `"A" 拍了拍 "B"`
     * - `"A" 拍了拍 "B" 的肩膀`（用户自定义后缀）
     * - `"A" 拍了拍自己`
     */
    const val PAT_KEYWORD = "拍了拍"

    /**
     * 拍一拍系统消息文本的**长度上限**（字符）。
     *
     * 真实文案最长也就「"A" 拍了拍 "B" 的肩膀」这个量级；设上限是为了防止
     * 把转发的长文 / 聊天记录导出这类含「拍了拍」字样的大段文本误判成拍一拍。
     */
    const val PAT_TEXT_MAX_LEN = 200

    /**
     * 这段文本是不是拍一拍系统消息。
     *
     * ⚠️ 这是**文本形态判据，不是拦截判据**：用户手动打一句「我拍了拍你」
     * 也会命中本函数。拦不拦由 [shouldBlockIncoming] 结合「系统消息」上下文决定，
     * 不能只凭文本就拦（见其注释）。
     */
    fun isPatText(text: String?): Boolean {
        val s = text?.trim() ?: return false
        if (s.isEmpty() || s.length > PAT_TEXT_MAX_LEN) return false
        return s.contains(PAT_KEYWORD)
    }

    /**
     * 收到的消息要不要拦（阻断收发方向）。
     *
     * 三个条件全满足才拦：
     * 1. 开关开着（[enabled]）；
     * 2. 这条消息是**系统消息**——由调用方从微信消息对象的类型字段判定后传入，
     *    PatLogic 不猜微信的 type 常量（`docs/取证结论.md` 无锚点，禁止硬编）；
     * 3. 文本命中 [isPatText]。
     *
     * 为什么要系统消息上下文：普通用户打「拍了拍」三个字是正常聊天内容，
     * 只凭文本拦截会把真人的话也吞掉。宁可漏拦（系统消息判定失败时放过），
     * 不误伤正常聊天。
     *
     * @param enabledRaw `chat.disable_pat` 的 Kv 原始值（字符串）
     * @param sysMsg 调用方判定的「是否系统消息」
     * @param text 消息文本
     */
    fun shouldBlockIncoming(enabledRaw: String?, sysMsg: Boolean, text: String?): Boolean =
        enabled(enabledRaw) && sysMsg && isPatText(text)

    /**
     * 自己触发的拍一拍要不要拦（阻止发出方向，需求主方向）。
     *
     * 触发侧的 hook 落点就是「双击头像触发拍一拍」的那次调用本身，命中即拦，
     * 因此判据只有开关一项——锚点（类/方法）见
     * [com.lx.wxgroup.feature.PatBlock] 顶部常量，**待取证**。
     */
    fun shouldBlockTrigger(enabledRaw: String?): Boolean = enabled(enabledRaw)

    // ------------------------------------------------------------------ 真机取证：候选筛选

    /**
     * 类名候选关键词（**不是锚点，只是启发式筛子**）。
     *
     * 从运行时枚举到的类名里按这些子串筛候选，缩小人工确认范围。
     * 全部来自公开常识而非微信私有命名：
     * - `pat`：拍一拍的英文惯用缩写；
     * - `pai` / `paipai`：拼音（微信混淆名里拼音极常见）；
     * - `doubletap`：双击手势的英文命名（Android SDK 方法名 onDoubleTap 同源）。
     */
    val CLASS_KEYWORDS: List<String> = listOf("pat", "pai", "doubletap")

    /** 方法名候选关键词，理由同 [CLASS_KEYWORDS]。 */
    val METHOD_KEYWORDS: List<String> = listOf("pat", "pai", "doubletap", "头像", "avatar", "head")

    /**
     * 从枚举到的类名里筛拍一拍相关候选（供真机 dump 后缩范围）。
     *
     * 纯函数：入参是字符串列表（来自反射枚举 / 日志），出参是命中的子列表，
     * 不改动入参、不发反射、不碰 Android。**命中的只是候选，必须人工过目确认**
     * 才能回填 PatBlock 锚点——混淆名里 `pat` 也可能是毫不相干的类。
     */
    fun classCandidates(names: List<String>): List<String> {
        if (names.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (n in names) {
            val lower = n.lowercase()
            if (CLASS_KEYWORDS.any { lower.contains(it) }) out.add(n)
        }
        return out
    }

    /** 同 [classCandidates]，用于方法名。 */
    fun methodCandidates(names: List<String>): List<String> {
        if (names.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (n in names) {
            val lower = n.lowercase()
            if (METHOD_KEYWORDS.any { lower.contains(it) }) out.add(n)
        }
        return out
    }

    // ------------------------------------------------------------------ 取证探针限流

    /**
     * `op=quiet sub=pat sub2=dbltap` 栈 dump 的**条数上限**。
     *
     * 双击一次会触发多次 onDoubleTap 回调（不同 View 的 listener 层层上报），
     * 不限流会把 LogX 环形缓冲冲爆、把真机日志搅乱。50 条足够覆盖一轮双击。
     */
    const val MAX_STACK_DUMPS = 50

    /** 还能不能再打一条栈（纯判据，探针侧调用；[count] 是已打条数）。 */
    fun stackDumpDue(count: Int, max: Int = MAX_STACK_DUMPS): Boolean = count < max

    /**
     * 探针「这一帧要不要 dump」的判据（纯函数）。
     *
     * 探针挂在 `GestureDetector#onTouchEvent` 上，**每一个触摸事件都会进来**
     * （滑动列表时一秒几十次），所以只认两类：
     * - [alreadySeen]=false：这是个**新的** doubleTap listener 实现类，它的类名和栈值得记一笔；
     *   同一个类的重复事件静默跳过（同一页面里反复双击不会刷屏）；
     * - 且预算 [count] 还没到 [max]。
     *
     * @param alreadySeen 该 listener 类名是否已在去重表里
     */
    fun shouldDumpListener(alreadySeen: Boolean, count: Int, max: Int = MAX_STACK_DUMPS): Boolean =
        !alreadySeen && stackDumpDue(count, max)
}
