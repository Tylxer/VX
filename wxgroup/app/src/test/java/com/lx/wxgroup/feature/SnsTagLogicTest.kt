package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SnsTagLogic] 的判据回归测试。
 *
 * 用例全部来自 **8.0.48 真机抓到的真实数据**（不是编的）。
 * 这些判据错了就要重上一轮真机（≈2 分钟 + 大量排查），所以必须先在这里锁住。
 */
class SnsTagLogicTest {

    // ---------------------------------------------------------------- snsId 符号

    /**
     * 真机一对：同一条动态的两种写法（DB/归档是有符号，ImproveInfo.toString 是无符号）。
     * 位模式必须完全一致，否则标签永远打不上（这是最容易静默出错的地方）。
     */
    @Test
    fun signedAndUnsignedAreSameBits() {
        val signed = SnsTagLogic.parseSnsId("-3412979636140764498")!!
        val unsigned = SnsTagLogic.parseSnsId("15033764437568787118")!!
        assertEquals(signed, unsigned)
        assertEquals("15033764437568787118", java.lang.Long.toUnsignedString(signed))
    }

    @Test
    fun rejectMalformedId() {
        assertNull(SnsTagLogic.parseSnsId(""))
        assertNull(SnsTagLogic.parseSnsId("-"))
        assertNull(SnsTagLogic.parseSnsId("123abc4567890123"))
        // ⚠️ parseSnsId 只管形状（纯数字），**不管长度下限** —— 15~20 位的下限是
        //    ID_RE 在正则里把的关。所以纯数字短串这里照样返回非空，别指望它拦。
        assertEquals(1792160219L, SnsTagLogic.parseSnsId("1792160219"))
        assertNull(SnsTagLogic.parseSnsId("15033764437568787118123")) // 超 20 位
        assertNull(SnsTagLogic.parseSnsId("sns_table_256863")) // 表名，不是 id
    }

    // ---------------------------------------------------------------- 解析 toString

    /** 真实样本：`qh3.a` 的字段 d 值对象 toString 的片段 */
    private val REAL = "ImproveInfo(name=贸易商陈健峰, snsID='15033729556253184615', " +
        "ad:false, time:29分钟前 rowid=256862, createTime=1792160219 localFla..."

    @Test
    fun parseRealInfo() {
        val info = SnsTagLogic.parseInfo(REAL)!!
        assertEquals("贸易商陈健峰", info.name)
        assertEquals("15033729556253184615", java.lang.Long.toUnsignedString(info.snsId))
    }

    @Test
    fun parseInfoRejectsNoId() {
        assertNull(SnsTagLogic.parseInfo(null))
        assertNull(SnsTagLogic.parseInfo(""))
        assertNull(SnsTagLogic.parseInfo("ImproveInfo(name=某人, time:1分钟前)"))
    }

    // ---------------------------------------------------------------- 名字对账

    @Test
    fun nameMatchesItemText() {
        assertTrue(SnsTagLogic.nameOk("贸易商陈健峰", listOf("贸易商陈健峰")))
        // 界面上作者名可能带前后缀
        assertTrue(SnsTagLogic.nameOk("陈健峰", listOf("贸易商陈健峰")))
    }

    @Test
    fun nameOkIsStrict() {
        assertFalse(SnsTagLogic.nameOk(null, listOf("某人")))          // 拿不到名字
        assertFalse(SnsTagLogic.nameOk("陈", listOf("贸易商陈健峰")))   // 1 字，太短
        assertFalse(SnsTagLogic.nameOk("张三", emptyList()))            // 读不到文字
        assertFalse(SnsTagLogic.nameOk("李四", listOf("贸易商陈健峰"))) // 对不上
    }

    /**
     * 反向包含必须要求文本够长：否则「一二」这种两字碎片会让
     * 任何含它的名字恒真 → 标签打到同作者的另一条动态上。
     */
    @Test
    fun reverseContainsNeedsLongText() {
        assertFalse(SnsTagLogic.nameOk("贸易商陈健峰", listOf("陈健")))
    }
}
