package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [strip] 纯函数的回归测试（R30 §6.1「时间行追加 `[删除已拦截]`」的还原侧）。
 *
 * `strip` 是 [SnsDeletedTagLogic.appendTag] 的逆操作，二者必须满足**往返不变**：
 * `strip(appendTag(x)) == x`。这是「被删 item 的 View 复用到正常动态后能否还原」的
 * 唯一防线 —— 判据错了，普通动态会带着上一条的标记，只在滚动后出现，真机极难复现。
 *
 * ⚠️ 本文件只测顶层 `internal fun strip`（编译成 `SnsDeletedTagKt` 门面），
 * **不 import `SnsDeletedTag` 对象**：对象里挂着 `XposedHelpers`，JVM 一加载就崩。
 */
class SnsDeletedTagTest {

    // ---------------------------------------------------------------- 基础剥离

    @Test
    fun returnsUnchangedWhenNoMark() {
        assertEquals("10分钟前", strip("10分钟前"))
        assertEquals("昨天", strip("昨天"))
    }

    @Test
    fun stripsSuffixMark() {
        assertEquals("10分钟前", strip("10分钟前 [删除已拦截]"))
        assertEquals("昨天", strip("昨天 [删除已拦截]"))
    }

    /** `appendTag("")` 产出整段就是标记（无前导空格），strip 必须还原成空串 */
    @Test
    fun stripsWholeMarkToEmpty() {
        assertEquals("", strip(SnsDeletedTagLogic.MARK))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", strip(""))
    }

    /**
     * strip 只认「恰好以 `" [删除已拦截]"` 结尾」；标记后再跟空格不算（appendTag 会
     * trimEnd，正常流不会产出这种形态，这里只是把契约钉死）。
     */
    @Test
    fun trailingSpaceAfterMarkIsNotStripped() {
        assertEquals("昨天 [删除已拦截]  ", strip("昨天 [删除已拦截]  "))
    }

    // ---------------------------------------------------------------- 往返不变

    /** 正常文案：追加再剥 == 原文 */
    @Test
    fun roundTripNormal() {
        assertEquals("3分钟前", strip(SnsDeletedTagLogic.appendTag("3分钟前")))
    }

    /** 空文案：appendTag("") == MARK，strip 再还原成空串 */
    @Test
    fun roundTripEmpty() {
        assertEquals("", strip(SnsDeletedTagLogic.appendTag("")))
    }

    /** 尾部带空格：appendTag 会 trim，往返后空格被吃掉是预期行为 */
    @Test
    fun roundTripTrailingSpaceIsTrimmed() {
        assertEquals("昨天", strip(SnsDeletedTagLogic.appendTag("昨天   ")))
    }
}
