package com.lx.wxgroup.data

/**
 * 「群聊有没有名字」的**纯判据 + 稳定排序**（零 Android / 零 Xposed 依赖 → 可直接跑 JVM 单测）。
 *
 * 存在的原因：群聊 Tab 里大量群 `remark` / `nickname` 都是空串，微信源表按
 * `remark, nickname` 排序时空串排最前 ⇒ 一进 Tab 满屏数字群号。**显示逻辑不动**
 * （显示群号是正确兜底），只把「无名群」沉到列表末尾。
 *
 * ⚠️ 本文件**禁止 import 任何 android.* / xposed.* / 本包内带 Android 依赖的类**
 *    （连 [Friend] 也不行：Friend.kt 的默认参数引用了 GroupDb.WxidType，会拖进 Android 依赖）。
 *    因此这里用泛型 + 取字段的 lambda，不依赖任何数据类。
 */
object ChatroomSortLogic {

    /** 排序键：有名的一组（排前） */
    private const val GROUP_NAMED = 0

    /** 排序键：无名的一组（排后） */
    private const val GROUP_UNNAMED = 1

    /**
     * 群到底有没有「名字」。
     *
     * 规则：`remark` 非空白 → 有名；`remark` 空白但 `nickname` 非空白 → 有名；
     * 两者皆空**或全是空白字符** → 无名。
     *
     * ⚠️ 必须 `isNotBlank()`（内部 trim 后判断），`"   "` 这种纯空格算无名 —— 真机源表里
     * 确实存在这种只有空格的值，用 `isNotEmpty()` 判会把它们当成有名、继续顶在最前。
     */
    fun hasName(remark: String, nickname: String): Boolean =
        remark.isNotBlank() || nickname.isNotBlank()

    /** 0 = 有名，1 = 无名。越小越靠前。 */
    fun sortKey(remark: String, nickname: String): Int =
        if (hasName(remark, nickname)) GROUP_NAMED else GROUP_UNNAMED

    /**
     * 稳定排序：有名的在前、无名的在后，**两组内部各自保持输入顺序**。
     *
     * `sortedWith(compareBy { ... })` 底层是 Arrays.sort(Object[])（TimSort），是稳定排序；
     * 比较键只有 0/1 两个值，所以「组内顺序」= 入参顺序，不会打乱数据库那层
     * `remark, nickname` 已经排好的顺序。
     *
     * ⛔ 不要换成 `sortedByDescending` / 手写快排等不稳定实现，也不要改
     * [GroupDb.queryFriends] 里共享的 ORDER BY（好友列表、findByDisplayName 都在用）。
     */
    fun <T> sortNamedFirst(
        items: List<T>,
        remarkOf: (T) -> String,
        nicknameOf: (T) -> String,
    ): List<T> = items.sortedWith(compareBy { sortKey(remarkOf(it), nicknameOf(it)) })
}
