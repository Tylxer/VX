package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SnsDeletedTagLogic] 的回归测试（R30 §6.1「时间行追加 `[删除已拦截]`」）。
 *
 * ## 为什么必须有
 * 标记是追加在一个**复用**的 `TextView` 上的。判据错了会出两类界面故障：
 * - **重复**（hook 重入 / 再次 bind 同一项）⇒ `[删除已拦截] [删除已拦截]`；
 * - **串味**（复用残留）⇒ 普通动态被标成已删，看起来像「防删除功能乱标」。
 * 两者都只在**滚动之后**出现，真机复现代价高 ⇒ 在 JVM 里先锁死。
 *
 * ## R35-6B 补强
 * 本轮补的是**边界**：`snsIdOf` 的形状闸门、`isTagged` 的 0/负数、`appendTag` 的幂等、
 * `parseSnsId` 的符号与超长、`brief` 的截断与 UTF-16 长度、`[isTimeLike]` 的整串匹配收紧。
 * 每条断言都带中文 message，失败时不用翻源码就知道破的是哪条语义。
 */
class SnsDeletedTagLogicTest {

    // ---------------------------------------------------------------- 文案追加

    @Test
    fun appendsMarkAfterNormalText() {
        assertEquals("10分钟前 [删除已拦截]", SnsDeletedTagLogic.appendTag("10分钟前"))
    }

    @Test
    fun appendsMarkAfterDayText() {
        assertEquals("昨天 [删除已拦截]", SnsDeletedTagLogic.appendTag("昨天"))
    }

    /** 微信自己的文案尾部可能带空格（不同版本/不同语言）⇒ 不能拼出双空格 */
    @Test
    fun trimsTrailingSpaceBeforeAppending() {
        assertEquals("昨天 [删除已拦截]", SnsDeletedTagLogic.appendTag("昨天   "))
    }

    /** 空文案：只留标记，且**不能有前导空格**（否则 TextView 里会看到缩进） */
    @Test
    fun emptyBecomesMarkOnly() {
        assertEquals(SnsDeletedTagLogic.MARK, SnsDeletedTagLogic.appendTag(""))
        assertEquals(SnsDeletedTagLogic.MARK, SnsDeletedTagLogic.appendTag("   "))
        assertEquals(SnsDeletedTagLogic.MARK, SnsDeletedTagLogic.appendTag(null))
    }

    @Test
    fun nullHasNoLeadingSpace() {
        assertFalse(SnsDeletedTagLogic.appendTag(null).startsWith(" "))
    }

    // ------------------------------------------------------------------- 幂等

    /** ⛔ 防 hook 重入：第二次追加必须原样返回 */
    @Test
    fun isIdempotentWhenAlreadyTagged() {
        assertEquals(
            "10分钟前 [删除已拦截]",
            SnsDeletedTagLogic.appendTag("10分钟前 [删除已拦截]"),
        )
    }

    /** 连调两次 == 调一次（把「先追加再被重入」这个真实序列走一遍） */
    @Test
    fun appendTwiceEqualsAppendOnce() {
        val once = SnsDeletedTagLogic.appendTag("3分钟前")
        val twice = SnsDeletedTagLogic.appendTag(once)
        assertEquals(once, twice)
        assertEquals(1, Regex(Regex.escape(SnsDeletedTagLogic.MARK)).findAll(twice).count())
    }

    /** 用户内容里恰好含同样文字也不该被当成"已经标过"以外的特殊处理 —— 幂等优先 */
    @Test
    fun taggedInputWithTrailingSpaceStillIdempotent() {
        assertEquals(
            "昨天 [删除已拦截]",
            SnsDeletedTagLogic.appendTag("昨天 [删除已拦截]   "),
        )
    }

    // --------------------------------------------------------------- snsId 集合

    @Test
    fun taggedIdsDropsZeroAndDuplicates() {
        val set = SnsDeletedTagLogic.taggedIds(listOf(0L, 11L, 11L, 22L, 0L))
        assertEquals(setOf(11L, 22L), set)
    }

    @Test
    fun taggedIdsOnEmptyInput() {
        assertTrue(SnsDeletedTagLogic.taggedIds(emptyList()).isEmpty())
    }

    /** `snsId=0` 是无效项，永远不标（与 `pickMissing` 同口径） */
    @Test
    fun zeroSnsIdIsNeverTagged() {
        assertFalse(SnsDeletedTagLogic.isTagged(0L, setOf(0L, 11L)))
    }

    @Test
    fun isTaggedBySetMembership() {
        val tagged = SnsDeletedTagLogic.taggedIds(listOf(11L))
        assertTrue(SnsDeletedTagLogic.isTagged(11L, tagged))
        assertFalse(SnsDeletedTagLogic.isTagged(12L, tagged))
        assertFalse(SnsDeletedTagLogic.isTagged(11L, emptySet()))
    }

    // =============================================================== isTagged 边界

    /**
     * `0` 表示「未知」，不是合法 id —— 即使集合里显式塞了 0 也不能命中。
     * 放宽这条 ⇒ 取不到 id 的动态会被统统打上灰标（**打错位置**）。
     */
    @Test
    fun `id 为 0：即使集合里含 0 也不命中`() {
        assertFalse("0 表示未知，不是合法 id", SnsDeletedTagLogic.isTagged(0L, setOf(0L)))
        assertFalse("0 表示未知，不是合法 id", SnsDeletedTagLogic.isTagged(0L, setOf(0L, -1L, 11L)))
        assertFalse("0 表示未知，不是合法 id", SnsDeletedTagLogic.isTagged(0L, emptySet()))
    }

    /**
     * 负数与 0 **不同待遇**：归档/DB 里的位模式是有符号 Long，高位为 1 就是负数，
     * 那是**合法** id（见 `SnsTagLogicTest.signedAndUnsignedAreSameBits`）。
     */
    @Test
    fun `负数 id 是合法值：命中集合就算标记`() {
        val neg = java.lang.Long.parseUnsignedLong("15033764437568787118")
        assertTrue("负数只是有符号解释，仍是合法 id", SnsDeletedTagLogic.isTagged(neg, setOf(neg)))
        assertTrue("负数必须在 Long.MIN 侧也能命中", SnsDeletedTagLogic.isTagged(-1L, setOf(-1L, 11L)))
        assertFalse("负数不在集合里就不命中", SnsDeletedTagLogic.isTagged(-1L, setOf(11L)))
    }

    @Test
    fun `空集合：任何 id 都不命中`() {
        assertFalse(SnsDeletedTagLogic.isTagged(11L, emptySet()))
        assertFalse(SnsDeletedTagLogic.isTagged(-11L, emptySet()))
        assertFalse(SnsDeletedTagLogic.isTagged(0L, emptySet()))
    }

    // =============================================================== snsIdOf 闸门

    /** 真实样本（8.0.48 抓的），里面同时有 `rowid` / `createTime` 等其它数字字段 */
    private val REAL_INFO = "ImproveInfo(name=贸易商陈健峰, snsID='15033729556253184615', " +
        "ad:false, time:29分钟前 rowid=256862, createTime=1792160219"

    @Test
    fun `真实样本：取到的是 snsID 字段本身`() {
        val id = SnsDeletedTagLogic.snsIdOf(REAL_INFO)
        assertNotNull("真实 ImproveInfo 样本必须能取到 snsID", id)
        assertEquals(
            "取到的必须是 snsID 字段的位模式（无符号写法）",
            "15033729556253184615",
            java.lang.Long.toUnsignedString(id!!),
        )
    }

    @Test
    fun `15 位下边界：可取`() {
        assertEquals(
            "15 位是 ID_RE 的下限，必须能取",
            123456789012345L,
            SnsDeletedTagLogic.snsIdOf("snsID='123456789012345'"),
        )
    }

    @Test
    fun `20 位上边界：可取`() {
        val id = SnsDeletedTagLogic.snsIdOf("snsID='12345678901234567890'")
        assertNotNull("20 位是 ID_RE 的上限，必须能取", id)
        assertEquals(
            "20 位无符号值不能溢出",
            "12345678901234567890",
            java.lang.Long.toUnsignedString(id!!),
        )
    }

    @Test
    fun `14 位：太短，闸门必须挡住`() {
        assertNull(
            "14 位不到 15 位下限，取它会把别的字段误当 snsID",
            SnsDeletedTagLogic.snsIdOf("snsID='12345678901234'"),
        )
    }

    @Test
    fun `没有 snsID 前缀的长数字：不取`() {
        assertNull(
            "定位靠字面量 snsID=，光有 20 位数字不算",
            SnsDeletedTagLogic.snsIdOf("Foo(id=12345678901234567890, rowid=123456789012345678)"),
        )
    }

    @Test
    fun `snsID=0 与 snsID=-1：都不是合法 id`() {
        assertNull("snsID=0 表示未知，位数也不够", SnsDeletedTagLogic.snsIdOf("snsID=0"))
        assertNull("snsID=-1 位数不够", SnsDeletedTagLogic.snsIdOf("snsID=-1"))
        assertNull("snsID= 后面没有数字", SnsDeletedTagLogic.snsIdOf("snsID="))
    }

    /**
     * 误收反例：一个串里同时有别的**长数字**字段（`rowid` / `createTime` / 别的 id）时，
     * 实现靠的是字面量 `snsID=`，不是「找最长的数字串」⇒ 不会抓错。
     */
    @Test
    fun `别的长数字字段不干扰：只认 snsID 字面前缀`() {
        val s = "Foo(rowid=123456789012345678, createTime=1792160219, snsID='15043160246930444808')"
        val id = SnsDeletedTagLogic.snsIdOf(s)
        assertNotNull("snsID= 存在就该取到", id)
        assertEquals(
            "rowid 也是 18 位数字，但不能被当成 snsID",
            "15043160246930444808",
            java.lang.Long.toUnsignedString(id!!),
        )
    }

    @Test
    fun `snsID 后面紧跟非数字：不取`() {
        assertNull(
            "引号后直接是非数字 ⇒ 形状不符",
            SnsDeletedTagLogic.snsIdOf("snsID='x15043160246930444808'"),
        )
    }

    @Test
    fun `20 位全 9：超出无符号 Long 上界，解析失败返回 null`() {
        assertNull(
            "99999999999999999999 > 2^64-1，parseUnsignedLong 会抛，必须吞掉而不是返回错值",
            SnsDeletedTagLogic.snsIdOf("snsID='99999999999999999999'"),
        )
    }

    @Test
    fun `null 与空串：不取`() {
        assertNull(SnsDeletedTagLogic.snsIdOf(null))
        assertNull(SnsDeletedTagLogic.snsIdOf(""))
        assertNull(SnsDeletedTagLogic.snsIdOf("   "))
    }

    /** `toString()` 抛异常的对象不能把打标链路带崩（实现里是 `runCatching`） */
    @Test
    fun `toString 抛异常：吞掉并返回 null`() {
        assertNull("toString 抛异常必须被 runCatching 吞掉", SnsDeletedTagLogic.snsIdOf(Boom()))
    }

    private class Boom {
        override fun toString(): String = throw RuntimeException("boom")
    }

    // -------------------------------------------------- snsIdOf 已发现的疑似缺陷

    /**
     * ⚠️ 疑似缺陷 #1（R35-6B 发现，**不改实现**，留给 SnsDeletedTag 那条 lane 定夺）
     *
     * - 输入：`snsID='123456789012345678901'`（21 位）
     * - 期望：`null`（21 位不可能是 snsID —— 无符号 Long 最多 20 位）
     * - 实际：`12345678901234567890`（正则 `\d{15,20}` 贪婪吃掉前 20 位，`find` 无锚定）
     * - 疑似：`SnsDeletedTagLogic.kt:36` 的 `ID_RE` 缺右侧边界（可加 `(?!\d)` 或 `'?` 收尾）
     * - 危害：拿到一个**看着合法但其实是错的** id ⇒ 打错标签，正是本类 KDoc 要防的形态。
     */
    @Test
    fun `21 位超长 id 应判非法`() {
        assertNull(
            "21 位超过无符号 Long 上限，不能截断后当成合法 id",
            SnsDeletedTagLogic.snsIdOf("snsID='123456789012345678901'"),
        )
    }

    /**
     * ⚠️ 疑似缺陷 #2（与 #1 同源）
     *
     * - 输入：`snsID='1504316024693044480a'`（数字串后面挂了字母）
     * - 期望：`null`（形状校验要求纯数字）
     * - 实际：`1504316024693044480`（正则只取前 19 位数字，尾部的 `a` 被忽略）
     * - 疑似：同 `SnsDeletedTagLogic.kt:36` 的 `ID_RE` 缺右侧边界。
     */
    @Test
    fun `数字串后跟字母应判非法`() {
        assertNull(
            "尾部有非数字 ⇒ 形状校验不通过",
            SnsDeletedTagLogic.snsIdOf("snsID='1504316024693044480a'"),
        )
    }

    /**
     * ⚠️ 疑似缺陷 #3（前缀子串命中）
     *
     * - 输入：`Foo(oldsnsID='111111111111111111', snsID='15043160246930444808')`
     * - 期望：`15043160246930444808`
     * - 实际：`111111111111111111` —— `oldsnsID` 里**含**子串 `snsID`，`find` 取最左 ⇒ 抓到前一个
     * - 疑似：同 `SnsDeletedTagLogic.kt:36`，`ID_RE` 缺左侧词边界（可加 `(?<![A-Za-z])`）
     * - 说明：当前微信的 toString 里没有 `…snsID` 这种字段，**不是现网问题**，
     *   只是正则的隐含假设没被锁住，版本漂移后可能踩到。
     */
    @Test
    fun `字段名尾部匹配 snsID 时不能误收`() {
        val s = "Foo(oldsnsID='111111111111111111', snsID='15043160246930444808')"
        assertEquals(
            "oldsnsID 里的 snsID 子串不应被当成 snsID 字段",
            "15043160246930444808",
            java.lang.Long.toUnsignedString(SnsDeletedTagLogic.snsIdOf(s)!!),
        )
    }

    // =============================================================== parseSnsId

    @Test
    fun `空串与纯空格：都判非法`() {
        assertNull("空串没有数字", SnsDeletedTagLogic.parseSnsId(""))
        assertNull("纯空格不是数字", SnsDeletedTagLogic.parseSnsId("   "))
        assertNull("首尾空格包着数字也要 trim 前先判非法", SnsDeletedTagLogic.parseSnsId(" 123 "))
    }

    @Test
    fun `负号：有符号 15 位可解析`() {
        assertEquals(
            "有符号写法要能吃下（DB/归档是有符号）",
            -123456789012345L,
            SnsDeletedTagLogic.parseSnsId("-123456789012345"),
        )
        assertNull("单独一个负号没有数字", SnsDeletedTagLogic.parseSnsId("-"))
        assertNull("正号写法不吃（与 ID_RE 口径一致）", SnsDeletedTagLogic.parseSnsId("+123456789012345"))
    }

    @Test
    fun `无符号 20 位：解析成同一个位模式`() {
        val v = SnsDeletedTagLogic.parseSnsId("15033764437568787118")
        assertNotNull("20 位无符号值在 2^64-1 以内，可解析", v)
        assertEquals(
            "与有符号写法必须同 bits（否则标签永远打不上）",
            SnsDeletedTagLogic.parseSnsId("-3412979636140764498"),
            v,
        )
        assertEquals("15033764437568787118", java.lang.Long.toUnsignedString(v!!))
    }

    @Test
    fun `超长 21 位：parseSnsId 自己会挡住`() {
        assertNull("超过 20 位直接判非法", SnsDeletedTagLogic.parseSnsId("123456789012345678901"))
        assertNull("20 位全 9 超 2^64-1，解析抛异常 ⇒ null", SnsDeletedTagLogic.parseSnsId("99999999999999999999"))
    }

    @Test
    fun `前导 0：位数以内按数值解析`() {
        assertEquals(
            "前导 0 不影响数值",
            150431602469304448L,
            SnsDeletedTagLogic.parseSnsId("0150431602469304448"),
        )
        assertNull(
            "前导 0 把长度顶到 21 位 ⇒ 判非法",
            SnsDeletedTagLogic.parseSnsId("015043160246930444808"),
        )
    }

    @Test
    fun `混进非数字字符：判非法`() {
        assertNull(SnsDeletedTagLogic.parseSnsId("123abc4567890123"))
        assertNull(SnsDeletedTagLogic.parseSnsId("sns_table_256863"))
        assertNull(SnsDeletedTagLogic.parseSnsId("15043160246930444808L"))
    }

    // =============================================================== appendTag

    @Test
    fun `连调两次等于调一次（换一种输入复验）`() {
        val once = SnsDeletedTagLogic.appendTag("昨天")
        val twice = SnsDeletedTagLogic.appendTag(once)
        assertEquals("重入不能叠出第二个标记", once, twice)
        assertEquals("昨天 [删除已拦截]", twice)
    }

    @Test
    fun `空串 纯空白 null：都只剩标记`() {
        assertEquals(SnsDeletedTagLogic.MARK, SnsDeletedTagLogic.appendTag(""))
        assertEquals(SnsDeletedTagLogic.MARK, SnsDeletedTagLogic.appendTag("   "))
        assertEquals(SnsDeletedTagLogic.MARK, SnsDeletedTagLogic.appendTag("\n\t "))
        assertEquals(SnsDeletedTagLogic.MARK, SnsDeletedTagLogic.appendTag(null))
    }

    @Test
    fun `原文就等于标记：原样返回，不再追加`() {
        assertEquals(
            "text 本身 == MARK 时不能拼成两个",
            SnsDeletedTagLogic.MARK,
            SnsDeletedTagLogic.appendTag(SnsDeletedTagLogic.MARK),
        )
    }

    /**
     * 幂等闸是 `contains` 而不是 `endsWith` ⇒ 标记在**中间**时同样原样返回。
     * 这不是理想语义（真遇到这种串标记位置会怪），但**重复追加更糟**，先把现状锁住。
     */
    @Test
    fun `标记出现在中间：也按已标记处理，不重复追加`() {
        assertEquals(
            "contains 判定 ⇒ 中间命中同样原样返回",
            "前缀 [删除已拦截] 后缀",
            SnsDeletedTagLogic.appendTag("前缀 [删除已拦截] 后缀"),
        )
    }

    /** `trimEnd` 只削尾部 ⇒ 前导空白会被**保留**（现状锁定，别误改成 trim） */
    @Test
    fun `前导空白保留 尾部空白削掉`() {
        assertEquals(
            "实现是 trimEnd，前导空格不该被吃掉",
            " [删除已拦截]",
            SnsDeletedTagLogic.appendTag(" [删除已拦截] "),
        )
        assertEquals(
            "换行也算尾部空白，要削掉",
            "昨天 [删除已拦截]",
            SnsDeletedTagLogic.appendTag("昨天\n"),
        )
    }

    @Test
    fun `普通文案：中间只隔一个空格`() {
        assertEquals("3小时前 [删除已拦截]", SnsDeletedTagLogic.appendTag("3小时前"))
        assertFalse(
            "不能出现双空格",
            SnsDeletedTagLogic.appendTag("3小时前   ").contains("  "),
        )
    }

    // =============================================================== brief

    @Test
    fun `超长：截到 max 并补省略号`() {
        val out = SnsDeletedTagLogic.brief("x".repeat(100))
        assertEquals("80 + 3 = 83", 83, out.length)
        assertTrue("必须以省略号结尾", out.endsWith("..."))
        assertEquals("保留的是前 80 个字符", "x".repeat(80) + "...", out)
    }

    @Test
    fun `长度正好等于 max：不截断`() {
        assertEquals("不超过就不动，也不该加省略号", "abc", SnsDeletedTagLogic.brief("abc", 3))
        assertEquals("abcd", SnsDeletedTagLogic.brief("abcd", 4))
        assertEquals("abc...", SnsDeletedTagLogic.brief("abcd", 3))
    }

    @Test
    fun `max 为 0：只剩省略号，但空串仍是空串`() {
        assertEquals("3 > 0 ⇒ 截到 0 个字符再加省略号", "...", SnsDeletedTagLogic.brief("abc", 0))
        assertEquals("空串长度 0 <= 0 ⇒ 走不截断分支", "", SnsDeletedTagLogic.brief("", 0))
    }

    @Test
    fun `空串与 null：空串原样 null 出 null 字样`() {
        assertEquals("空串不该变成省略号", "", SnsDeletedTagLogic.brief(""))
        assertEquals("null 走 `s ?: \"null\"` 分支", "null", SnsDeletedTagLogic.brief(null))
    }

    @Test
    fun `换行：短串原样保留，超长时可能截在换行之后`() {
        assertEquals("a\nb", SnsDeletedTagLogic.brief("a\nb"))
        val long = "第一行\n第二行" + "x".repeat(50)
        val out = SnsDeletedTagLogic.brief(long, 20)
        assertEquals(23, out.length)
        assertTrue("换行符不能被当成截断边界", out.contains("\n"))
    }

    /**
     * 中文：截断按 **UTF-16 code unit** 数，不是字点数，更不是显示宽度。
     * 10 个汉字 = 10 个 code unit ⇒ `max=10` 正好切 10 个**字**。
     */
    @Test
    fun `中文：按 UTF-16 长度截断`() {
        val src = "朋友圈动态内容展示".repeat(5) // 9 字 × 5 = 45 个 code unit
        val out = SnsDeletedTagLogic.brief(src, 10)
        assertEquals("10 个 code unit + 3 个省略号 = 13", 13, out.length)
        assertEquals("朋友圈动态内容展示朋...", out)
        assertEquals("整段没超过 max 时一个字都不该少", src, SnsDeletedTagLogic.brief(src, 45))
    }

    /**
     * Emoji 是**代理对**（2 个 code unit）。`max=3` 会切在第二个 emoji 的**中间**
     * （留下半个代理对）。这里只锁「不崩 + 长度对」，**不锁字形** ——
     * 假设「长度 == 显示宽度」是错的，但假设「截断不越界」是对的。
     */
    @Test
    fun `emoji：按代理对截断，只锁长度不锁字形`() {
        val face = "\uD83D\uDE00" // 1 个 emoji = 2 个 code unit
        val src = face.repeat(10) // 20 个 code unit
        assertEquals("先确认 emoji 是代理对，否则这条用例没意义", 2, face.length)
        val out = SnsDeletedTagLogic.brief(src, 3)
        assertEquals("截 3 个 unit（1 个完整 emoji + 半个高代理）+ 3 个省略号 = 6", 6, out.length)
        assertTrue("第一个 emoji 必须完整", out.startsWith(face))
        assertTrue(out.endsWith("..."))
    }

    // =============================================================== isTimeLike

    /**
     * 标准相对时间：**整串**命中才放行。这是收紧后的核心语义。
     */
    @Test
    fun `相对时间说法：整串命中才算时间行`() {
        for (s in listOf("刚刚", "10秒前", "10分钟前", "3小时前", "2天前", "3周前", "2个月前", "昨天", "前天")) {
            assertTrue("「$s」是标准相对时间文案，必须判为时间行", SnsDeletedTagLogic.isTimeLike(s))
        }
    }

    @Test
    fun `绝对日期：中文与数字两种写法都算`() {
        for (s in listOf("10月28日", "2026年10月28日", "2026-10-28", "2026/10/28")) {
            assertTrue("「$s」是绝对日期，必须判为时间行", SnsDeletedTagLogic.isTimeLike(s))
        }
    }

    /**
     * ⛔ 视频时长徽章。它跟「今天发的动态显示 `HH:MM`」字面完全无法区分 ⇒ 一律否掉。
     * 取舍：漏掉今天那几条（顶多不打标）好过把灰标打到时长徽章上（**打错位置**）。
     */
    @Test
    fun `视频时长徽章：一律不算时间行`() {
        for (s in listOf("00:15", "1:23", "12:34", "03:20")) {
            assertFalse(
                "「$s」是视频时长徽章，与今天的 HH:MM 无法区分，只能一律否掉",
                SnsDeletedTagLogic.isTimeLike(s),
            )
        }
    }

    @Test
    fun `以冒号开头的残串：不算`() {
        assertFalse(":30 是残串/分隔符，不是时间行", SnsDeletedTagLogic.isTimeLike(":30"))
        assertFalse(SnsDeletedTagLogic.isTimeLike("30:"))
    }

    @Test
    fun `空串 纯空白 null：都不算`() {
        assertFalse(SnsDeletedTagLogic.isTimeLike(""))
        assertFalse(SnsDeletedTagLogic.isTimeLike("   "))
        assertFalse(SnsDeletedTagLogic.isTimeLike("\n\t"))
        assertFalse(SnsDeletedTagLogic.isTimeLike(null))
    }

    /** 200 字正文：朋友圈时间行不可能这么长 ⇒ 长度闸门 + 整串匹配双保险 */
    @Test
    fun `超长正文：不算时间行`() {
        val body = ("昨天，我和朋友去看了一场电影，散场后又吃了夜宵，回家已经很晚了").repeat(4)
        assertTrue("用例本身要先够长，否则测不到闸门", body.length > 100)
        assertFalse("正文再怎么含时间词也不该当成时间行", SnsDeletedTagLogic.isTimeLike(body))
    }

    /**
     * 长度闸门（[TIME_LIKE_MAX = 20]）的**专属**守门用例：
     * 形状上完全像相对时间（`\d+分钟前`），只是数字长到离谱。
     * 只靠正则挡不住（正则对数字位数没有上限）⇒ 删掉长度闸门这条就会变红。
     */
    @Test
    fun `形状像相对时间但超 20 字：被长度闸门挡住`() {
        val s = "12345678901234567890分钟前"
        assertEquals("用例长度必须真的超过闸门", 23, s.length)
        assertFalse("时间行不会是 23 字，长度闸门必须挡住", SnsDeletedTagLogic.isTimeLike(s))
    }

    /**
     * `28`：纯数字，既不是时长形态（没冒号）也不是任何时间说法 ⇒ false。
     * 锁住它是为了挡住「放宽成『含数字就算』」这种退化。
     */
    @Test
    fun `纯数字 28：既不是时长也不是时间说法`() {
        assertFalse("28 只是个数字，没有任何时间语义", SnsDeletedTagLogic.isTimeLike("28"))
        assertFalse(SnsDeletedTagLogic.isTimeLike("2026"))
    }

    /**
     * 混合形态 `昨天 10:30` —— **本测试断言 false**，理由：
     * 1. 判据是**整串**匹配，`昨天 10:30` 既不是相对时间也不是日期样式 ⇒ 天然 false；
     * 2. 语义上也站得住：朋友圈时间行的展示是「昨天」「10月28日」「3小时前」这类**单一**说法，
     *    `昨天 10:30` 这种拼法出现在评论/详情页，不是列表页时间行；
     * 3. 取向一致 —— 认不出来就**不打标**（漏），也好过打到别的 View 上（**错**）。
     * ⚠️ 若将来确认列表页真的会显示这种拼法，应该改的是**这条用例 + 正则**，
     *    而不是把整串匹配退回去（退回「包含即可」会把 `昨天我去看了…` 这类正文全放进来）。
     */
    @Test
    fun `混合形态 昨天 10点30：按整串匹配判 false`() {
        assertFalse(
            "「昨天 10:30」不是整串时间说法；认不出来就不打标，好过打错位置",
            SnsDeletedTagLogic.isTimeLike("昨天 10:30"),
        )
    }

    /** 只是「包含」时间词的正文 —— 老判据（包含即可）会把灰标打到正文上 */
    @Test
    fun `正文中包含时间词：不算时间行`() {
        assertFalse("「刚刚到家」是正文", SnsDeletedTagLogic.isTimeLike("刚刚到家"))
        assertFalse("「昨天我去看了电影」是正文", SnsDeletedTagLogic.isTimeLike("昨天我去看了电影"))
        assertFalse("「3小时前发布的通知」是正文", SnsDeletedTagLogic.isTimeLike("3小时前发布的通知"))
    }

    @Test
    fun `首尾空白：trim 之后照常判定`() {
        assertTrue("时间行文案可能带首尾空格", SnsDeletedTagLogic.isTimeLike("  昨天  "))
        assertTrue(SnsDeletedTagLogic.isTimeLike(" 10月28日"))
        assertFalse("trim 之后是时长徽章", SnsDeletedTagLogic.isTimeLike(" 00:15 "))
        assertFalse("trim 之后是空串", SnsDeletedTagLogic.isTimeLike("   "))
    }

    // =========================================================== nameOf（R37-1C）

    /**
     * `nameOf` 提供的是「View ↔ 数据对账」唯一的锚。
     *
     * 为什么只能用作者名：时间文本在绑定回调那一刻通常**还是空串**（渲染是 suspend 的），
     * 拿它当锚等于没有锚（R37 根因里 `missedTime` 涨到 43 就是从这儿来的）。
     * 取不到名字时返回 `null`，调用方按「不可利用」处理 ⇒ 比返回一个错名字安全得多。
     */

    @Test
    fun `真实样本：取到的是 ImproveInfo 的 name 字段`() {
        assertEquals(
            "真实样本必须能取到作者名，否则整条对账链路没有锚",
            "贸易商陈健峰",
            SnsDeletedTagLogic.nameOf(REAL_INFO),
        )
    }

    @Test
    fun `null 与空串：都取不到`() {
        assertNull("null 对象直接返回 null", SnsDeletedTagLogic.nameOf(null))
        assertNull("空串里没有 name=", SnsDeletedTagLogic.nameOf(""))
        assertNull("纯空格里没有 name=", SnsDeletedTagLogic.nameOf("   "))
    }

    @Test
    fun `没有 name 字段的对象：取不到`() {
        assertNull(
            "只有 snsID / rowid 这类字段时不该硬凑一个名字出来",
            SnsDeletedTagLogic.nameOf("Foo(id=1, snsID='123456789012345678', rowid=256862)"),
        )
    }

    /**
     * ⛔ 前缀闸门：`nickname=` / `displayname=` 是**另一个字段**，同样以 `name=` 结尾。
     * 让它们抢先命中 ⇒ 拿去比对的名字不再代表 `ImproveInfo.name` ⇒ 对账判据失真；
     * 失真方向是随机的，可能一个标签都不打，也可能**相邻动态全过**。
     */
    @Test
    fun `nickname 字段不能被当成 name`() {
        assertNull(
            "nickname= 里的 name 前缀是字母，不该被当成独立 name 字段",
            SnsDeletedTagLogic.nameOf("Foo(nickname=李四, id=1)"),
        )
        assertNull(
            "displayname= 同理",
            SnsDeletedTagLogic.nameOf("Foo(displayname=王五, id=1)"),
        )
    }

    @Test
    fun `同时有 nickname 与 name：取真正的 name`() {
        assertEquals(
            "真正的 name 字段带前导空格，必须优先于 nickname",
            "赵六",
            SnsDeletedTagLogic.nameOf("Foo(nickname=李四, name=赵六, id=1)"),
        )
    }

    @Test
    fun `引号写法与尾随空格：取到并 trim`() {
        assertEquals(
            "name='李四' 的引号要被吃掉",
            "李四",
            SnsDeletedTagLogic.nameOf("ImproveInfo(name='李四', snsID='123456789012345678')"),
        )
        assertEquals(
            "值后面的空格要 trim 掉，否则跟界面文本比对时永远不相等",
            "李四",
            SnsDeletedTagLogic.nameOf("ImproveInfo(name=李四 , snsID='123456789012345678')"),
        )
        assertEquals(
            "等号两侧有空格也要能吃下",
            "李四",
            SnsDeletedTagLogic.nameOf("ImproveInfo(name = 李四, snsID='123456789012345678')"),
        )
    }

    @Test
    fun `name 后面直接是逗号：取不到，不能返回空串`() {
        assertNull(
            "空名字必须落到 null（不可利用），返回空串会被当成合法名字",
            SnsDeletedTagLogic.nameOf("ImproveInfo(name=, snsID='123456789012345678')"),
        )
    }

    /** 英文昵称同理 —— 前缀闸门按「前一个字符是不是字母」判，不是按值的内容判 */
    @Test
    fun `英文昵称：正常取到`() {
        assertEquals("John", SnsDeletedTagLogic.nameOf("ImproveInfo(name=John, snsID='123456789012345678')"))
    }

    /** 反射/toString 可能抛 ⇒ 必须吞掉，不能把打标链路带崩（实现里是 `runCatching`） */
    @Test
    fun `nameOf 遇到 toString 抛异常：吞掉并返回 null`() {
        assertNull("toString 抛异常必须被 runCatching 吞掉", SnsDeletedTagLogic.nameOf(Boom()))
    }

    /** 任意对象（没有 name 字段、且类型完全无关）都不能抛异常，只返回 null */
    @Test
    fun `任意对象：不抛异常，取不到就返回 null`() {
        assertNull(SnsDeletedTagLogic.nameOf(42))
        assertNull(SnsDeletedTagLogic.nameOf(listOf(1, 2, 3)))
        assertNull(SnsDeletedTagLogic.nameOf("plain text without any field"))
    }

    /** 名字上限 32 字：超长时截断（宁可截短也不能让正则吃掉后面的字段） */
    @Test
    fun `超长名字：被 32 字上限截断`() {
        val long = "x".repeat(40)
        val got = SnsDeletedTagLogic.nameOf("ImproveInfo(name=$long, snsID='123456789012345678')")
        assertNotNull("40 字名字应该能取到（截断后）", got)
        assertEquals("超过 32 字的部分必须被截掉", 32, got!!.length)
        assertEquals("截断保留的是前 32 个字", "x".repeat(32), got)
    }

    /**
     * 现状锁定：正则要求 `name` 前面**有一个非字母字符** ⇒ 串首裸 `name=…`（前面没有字符）
     * 取不到。真机样本里 name 前面总有 `(` 或空格，所以不影响现网。
     * ⚠️ 别为了「修」这条把前缀删掉 —— 删了就会让 `nickname=` 抢先命中（见上一条用例）。
     */
    @Test
    fun `现状锁定：串首裸 name 无前导字符时取不到`() {
        assertNull(
            "裸 name= 前面没有字符，不满足前缀闸门（真机样本里 name 前总有 ( 或空格）",
            SnsDeletedTagLogic.nameOf("name=李四"),
        )
        assertEquals("前面有空格就正常了", "李四", SnsDeletedTagLogic.nameOf(" name=李四"))
    }

    // ============================================ 作者行锚定（R37-2）· isAuthorRowClass

    /**
     * R37-2 新增的一层判据：作者昵称 TextView 是**按类名**认出来的
     * （真机 depth=9 dump：作者名 `AsyncTextView`、正文与时间行 `ImproveTextView`），
     * 不再是「拿子树所有可见文本、靠长度猜哪个是昵称」。
     *
     * ## 为什么必须换判据
     * [SnsDeletedTagLogic.matchName] 的长度上界（`NAME_TEXT_MAX = 16`）只能挡住 **>16 字**
     * 的长正文，**9~16 字短正文照样命中**：`matchName("李四", ["今天和李四一起吃饭"])` 实测 true。
     * 于是「正文里提到邻居的名字」会让**邻居**候选通过校验；当正确候选因**备注名**恒不命中时，
     * 邻居就成了窗口内唯一命中 ⇒ 这一格绑上邻居的 snsId ⇒ **这条动态被打错标签**（用户看得见）。
     *
     * ## 为什么这里全是纯函数
     * View 侧（`collectVisTexts`）在真机上跑，JVM 里没有 Robolectric 可用 ⇒ 判据必须下沉到
     * 本文件：View 侧只做「可见 TextView + 类名链」的**哑采集**，角色判定全在这里。
     */

    private val AUTHOR_CHAIN = listOf("AsyncTextView", "TextView", "View")
    private val BODY_CHAIN = listOf("ImproveTextView", "TextView", "View")

    private fun vt(text: String, cls: List<String>) = SnsDeletedTagLogic.VisText(text, cls)

    /**
     * 「认出了作者行、文本也已渲染」的语料（正常情况）。
     *
     * `seen` 取 `texts.size` 表示「全采到了，没被截断」—— 与 View 侧
     * （`collectVisTexts` 采满 48 条就停）传上来的真值是同一套语义：
     * **`seen` 永远 ≥ [items] 的大小**，相等就是没截断。
     */
    private fun author(vararg texts: String) = SnsDeletedTagLogic.AuthorCorpus(
        texts.filter { it.isNotEmpty() }, texts.size, setOf("AsyncTextView"), texts.size,
    )

    /** 「一个作者行都没认出」的语料（类名漂移时的形态：`seen` 小于 View 侧上限） */
    private fun noAuthor() = SnsDeletedTagLogic.AuthorCorpus(
        emptyList(), 0, setOf("ImproveTextView"), 2,
    )

    /** 「一个作者行都没认出」的另一种成因：**采样被截断**（`seen >= cap`，作者行压根没走到） */
    private fun truncated(cap: Int = 48) = SnsDeletedTagLogic.AuthorCorpus(
        emptyList(), 0, setOf("ImproveTextView"), cap,
    )

    @Test
    fun `作者行类名：命中`() {
        assertTrue(
            "真机 dump 里作者名就是 AsyncTextView（正文是 ImproveTextView）",
            SnsDeletedTagLogic.isAuthorRowClass(AUTHOR_CHAIN),
        )
    }

    @Test
    fun `子类也算作者行：链上有这一层就命中`() {
        assertTrue(
            "微信常用子类承载不同位置的文本，子类名可能是混淆名 ⇒ 必须比整条父类链",
            SnsDeletedTagLogic.isAuthorRowClass(listOf("ki4.a", "AsyncTextView", "TextView")),
        )
    }

    @Test
    fun `正文类与普通 TextView：不命中`() {
        assertFalse(
            "ImproveTextView 是正文/时间行，不能进作者语料",
            SnsDeletedTagLogic.isAuthorRowClass(BODY_CHAIN),
        )
        assertFalse(SnsDeletedTagLogic.isAuthorRowClass(listOf("TextView", "View")))
        assertFalse("AppCompatTextView 也不是作者行", SnsDeletedTagLogic.isAuthorRowClass(listOf("AppCompatTextView", "TextView")))
    }

    @Test
    fun `空链：不命中`() {
        assertFalse("判不出就是判不出，不许退化成默认通过", SnsDeletedTagLogic.isAuthorRowClass(emptyList()))
    }

    /**
     * ⛔ 严格相等，不许放宽成 `contains` / 前缀匹配。
     * 放宽会把 `AsyncTextView2` / `XxxAsyncTextViewWrapper` 这类**另一个控件**也当成作者行
     * ⇒ 语料重新被污染 ⇒ 本轮修的洞又回来了。
     */
    @Test
    fun `相似但不同的类名：一律不命中（不许放宽成 contains）`() {
        for (n in listOf("AsyncTextView2", "AsyncTextViewWrapper", "asyncTextView", "AsyncText", "MyAsync")) {
            assertFalse(
                "「$n」不是作者行控件；放宽成 contains 会被误收 ⇒ 正文又混进语料",
                SnsDeletedTagLogic.isAuthorRowClass(listOf(n, "TextView")),
            )
        }
    }

    // ============================================== 作者行锚定（R37-2）· authorCorpusOf

    @Test
    fun `作者行与正文混排：语料里只剩作者行`() {
        val c = SnsDeletedTagLogic.authorCorpusOf(
            listOf(
                vt("10分钟前", BODY_CHAIN),
                vt("今天和李四一起吃饭", BODY_CHAIN),
                vt("王贝拉", AUTHOR_CHAIN),
            ),
        )
        assertEquals("正文与时间行都不是作者行 ⇒ 不得进语料", listOf("王贝拉"), c.texts)
        assertEquals(1, c.rows)
        assertEquals(
            "类名集合是诊断用的，两类都要记（漂了一眼看得出来）",
            setOf("ImproveTextView", "AsyncTextView"), c.classes,
        )
    }

    /**
     * ⭐ 区分「还没渲染」与「类名漂了」的关键：文本为空的作者行**仍然算一行**。
     * 少这一条，`rows` 与 `texts` 同时为 0，真机就只能看到一个笼统的「对账不过」，
     * 分不清该「等一拍重试」还是该「改类名常量」。
     */
    @Test
    fun `作者行文本为空：rows 仍为 1 但 texts 为空`() {
        val c = SnsDeletedTagLogic.authorCorpusOf(listOf(vt("", AUTHOR_CHAIN)))
        assertEquals("作者行控件在，只是还没填字 ⇒ rows 必须仍为 1", 1, c.rows)
        assertTrue("文本为空 ⇒ 不进语料（由 matchName 判不过）", c.texts.isEmpty())
    }

    /**
     * ⛔ 降级形态：一个作者行都没认出。语料**必须为空** —— 不许退回「子树全文 + 长度上界」，
     * 那等于把 9~16 字短正文又放回语料，本轮修的洞原样留着。
     */
    @Test
    fun `一个作者行都没有：rows 为 0 且语料为空，不许退回全文`() {
        val c = SnsDeletedTagLogic.authorCorpusOf(
            listOf(vt("李四", BODY_CHAIN), vt("10分钟前", BODY_CHAIN)),
        )
        assertEquals(0, c.rows)
        assertTrue("认不出作者行 ⇒ 语料为空（降级成全文就是把洞留着）", c.texts.isEmpty())
        assertEquals("类名照记：真机靠它看出该改成哪个类名", setOf("ImproveTextView"), c.classes)
    }

    @Test
    fun `多个作者行：都进语料`() {
        val c = SnsDeletedTagLogic.authorCorpusOf(listOf(vt("王贝拉", AUTHOR_CHAIN), vt("李四", AUTHOR_CHAIN)))
        assertEquals(2, c.rows)
        assertEquals(listOf("王贝拉", "李四"), c.texts)
    }

    @Test
    fun `空输入：rows 为 0 且类名集合为空`() {
        val c = SnsDeletedTagLogic.authorCorpusOf(emptyList())
        assertEquals(0, c.rows)
        assertTrue(c.texts.isEmpty())
        assertTrue(c.classes.isEmpty())
    }

    @Test
    fun `classes 只记自己那一层的类名且去重`() {
        val c = SnsDeletedTagLogic.authorCorpusOf(
            listOf(vt("a", AUTHOR_CHAIN), vt("b", AUTHOR_CHAIN), vt("c", BODY_CHAIN)),
        )
        assertEquals("同一个类名出现两次只记一次", setOf("AsyncTextView", "ImproveTextView"), c.classes)
    }

    // ------------------------------------------------ seen：`rows == 0` 的两种成因靠它分

    /**
     * ⭐ `rows == 0` 有两个成因，真机上的处置完全不同：
     *   - **类名漂了**：`seen < cap`，作者行走到了、只是认不出 ⇒ 照抄 `cls=` 改类名常量；
     *   - **采样被截断**：`seen >= cap`，作者行排在 View 侧上限之后、压根没被遍历到
     *     ⇒ 抬 `VIS_TEXT_MAX`，⛔ **不许**照抄 `cls=` —— 截断时 `cls=` 里是先遇到的一批类名
     *     （大概率是正文的 `ImproveTextView`），抄进 `AUTHOR_VIEW_CLS` ⇒ 正文重新成为语料
     *     ⇒ 9~16 字短正文提到邻居名字 ⇒ **打错标签**（比不打标糟得多）。
     *
     * ⇒ `seen` 必须能从 View 侧一路传到 `AuthorCorpus`，中间不许被默认值吞掉。
     */
    @Test
    fun `seen 由 View 侧带出：不传时等于采样条数，传了就用真值`() {
        val items = listOf(vt("李四", BODY_CHAIN), vt("王贝拉", AUTHOR_CHAIN))
        // 不传 ⇒ 退化成「没截断」的语义
        assertEquals("默认 seen = 采样条数（表示全采到了）", 2, SnsDeletedTagLogic.authorCorpusOf(items).seen)
        // View 侧传真值 ⇒ 必须原样带出，哪怕远大于 items.size（那是被截断的形态）
        assertEquals(
            "View 侧采满 48 条但子树里有 137 个可见 TextView ⇒ seen 必须是 137",
            137, SnsDeletedTagLogic.authorCorpusOf(items, 137).seen,
        )
        assertEquals(
            "语料本身与 seen 无关：seen 只是诊断字段",
            listOf("王贝拉"), SnsDeletedTagLogic.authorCorpusOf(items, 137).texts,
        )
        assertEquals(1, SnsDeletedTagLogic.authorCorpusOf(items, 137).rows)
    }

    @Test
    fun `seen 与 rows 互不干扰：认出作者行时 rows 照常被 seen 之外的值带着`() {
        val c = SnsDeletedTagLogic.authorCorpusOf(listOf(vt("王贝拉", AUTHOR_CHAIN)), 48)
        assertEquals(1, c.rows)
        assertEquals("rows>0 时 seen 照样是 48 ⇒ 两个字段各记各的", 48, c.seen)
    }

    /** 截断形态的语料：`rows=0` + `seen >= cap` —— 这时 `cls=` 里的类名**没有证据力** */
    @Test
    fun `采样被截断：rows 为 0 且 seen 达到上限`() {
        val c = truncated()
        assertEquals(0, c.rows)
        assertTrue(c.seen >= 48)
        assertFalse(
            "截断时 cls 里只有先遇到的一批类名（正文类）⇒ 不许当成作者类名去改常量",
            c.classes.any { it == SnsDeletedTagLogic.AUTHOR_VIEW_CLS },
        )
    }

    /** 反例：同样是 `rows=0`，但 `seen` 远小于上限 ⇒ 这才是「类名漂了」的形态 */
    @Test
    fun `类名漂移：rows 为 0 且 seen 远小于上限`() {
        val c = noAuthor()
        assertEquals(0, c.rows)
        assertTrue("seen 没打满 ⇒ 作者行走到了，只是认不出 ⇒ 这时才能照抄 cls", c.seen < 48)
    }

    // ================================================== matchName（R37-1C / R37-2 重写）

    /**
     * `matchName` 是「候选数据 ↔ itemView」的**唯一**校验闸门。
     *
     * R37-2 起它的入参不再是「子树所有可见文本」，而是**按类名限定的作者行语料**
     * （[SnsDeletedTagLogic.AuthorCorpus]）：正文/转发来源/点赞文案根本不会传进来
     * ⇒ 「正文里提到邻居名字」这条打错标签的路径被**从语料层面**堵死，
     * 而不是靠长度上界去猜（上界挡不住 9~16 字的短正文）。
     */

    /** ⭐ 本轮要堵的洞：**正文**里提到候选名字 —— 它压根不该出现在语料里 */
    @Test
    fun `短正文提到候选名字：正文不进语料，判不过`() {
        val items = listOf(vt("今天和李四一起吃饭", BODY_CHAIN), vt("李总", AUTHOR_CHAIN))
        val c = SnsDeletedTagLogic.authorCorpusOf(items)
        assertFalse(
            "10 字正文提到「李四」不再命中（R37-1 的长度上界挡不住它，靠类名限定挡）",
            SnsDeletedTagLogic.matchName("李四", c),
        )
        assertTrue("作者行「李总」本身要照常命中", SnsDeletedTagLogic.matchName("李总", c))
    }

    /**
     * 反证（说明上一条为什么值钱）：只要正文混进语料，邻居候选立刻成为命中项。
     * 「去掉类名限定」这个变异会让上一组断言变红，就是因为语料会退化成这个样子。
     */
    @Test
    fun `反证：正文一旦混进语料就会命中（这就是要防的形态）`() {
        val bad = SnsDeletedTagLogic.AuthorCorpus(
            listOf("贝拉", "今天和李四一起吃饭"), 2, setOf("AsyncTextView"), 2,
        )
        assertTrue(
            "正文混进语料 ⇒ 「李四」靠正文命中 ⇒ 这一格会被绑上邻居的 snsId ⇒ 打错标签",
            SnsDeletedTagLogic.matchName("李四", bad),
        )
        assertFalse(
            "而干净的语料（只有作者行「贝拉」）里它不命中",
            SnsDeletedTagLogic.matchName("李四", author("贝拉")),
        )
    }

    @Test
    fun `超长正文提到候选名字：同样判不过`() {
        val body = "今天和李四一起吃饭，聊了很久关于孩子上学的事情"
        assertEquals("用例自身校验：正文必须够长", 23, body.length)
        val c = SnsDeletedTagLogic.authorCorpusOf(listOf(vt(body, BODY_CHAIN)))
        assertFalse("23 字正文也不是作者行", SnsDeletedTagLogic.matchName("李四", c))
    }

    @Test
    fun `转发来源提到候选名字：同样判不过`() {
        val c = SnsDeletedTagLogic.authorCorpusOf(listOf(vt("转发 张三 的动态", BODY_CHAIN)))
        assertFalse("转发来源行不是作者行", SnsDeletedTagLogic.matchName("张三", c))
    }

    @Test
    fun `正常昵称：精确命中`() {
        assertTrue("昵称行就是名字本身", SnsDeletedTagLogic.matchName("李四", author("李四")))
        assertTrue("昵称后面跟了别的文案也能命中", SnsDeletedTagLogic.matchName("李四", author("李四 昨天")))
        assertTrue("昵称前面加了别的内容也能命中", SnsDeletedTagLogic.matchName("李四", author("小明 李四")))
    }

    /**
     * 长度上界**只挡长昵称以外的极端文本，不挡正常长昵称**：
     * `n.length + 8` 对 2~3 字的名字太紧，靠 `NAME_TEXT_MAX = 16` 兜底 ⇒ 8 字前缀仍能命中。
     */
    @Test
    fun `昵称带前后缀：仍要命中`() {
        assertTrue(
            "「来自深圳的陈健峰」只有 8 字，界面前缀不该把昵称挡掉",
            SnsDeletedTagLogic.matchName("陈健峰", author("来自深圳的陈健峰")),
        )
        assertTrue(
            "数据名比界面名长一截（ImproveInfo.name=贸易商陈健峰）时也要命中",
            SnsDeletedTagLogic.matchName("贸易商陈健峰", author("来自深圳的贸易商陈健峰")),
        )
    }

    /** 兜底常量的专属守门用例：13 > `n.length + 8`（= 11）但 ≤ 16 ⇒ 靠 16 兜底命中 */
    @Test
    fun `文本比 name 加 8 更长但不超过 16：靠 NAME_TEXT_MAX 兜底命中`() {
        val t = "来自广东深圳南山区的陈健峰"
        assertEquals("用例自身校验：必须真的超出 n+8 但不超过 16", 13, t.length)
        assertTrue("兜底值 16 就是为了让这种长昵称仍能命中", SnsDeletedTagLogic.matchName("陈健峰", author(t)))
    }

    /**
     * ⛔ 长度上界仍在（纵深防御）：作者行文本 17 字且含名字 ⇒ 判不过。
     * 万一将来有人把别的角色的文本混进语料，这条仍能挡住长文本 ⇒ 不至于一次性全崩。
     * 「去掉长度上界」这个变异会让本条变红。
     */
    @Test
    fun `作者行文本超过 16 字：仍被长度上界挡掉`() {
        val t = "来自广东深圳南山区的陈健峰的好友们"
        assertEquals("用例自身校验：必须真的超过上界 16", 17, t.length)
        assertTrue("用例自身校验：必须真的含这个名字", t.contains("陈健峰"))
        assertFalse(
            "长度上界是类名限定之外的第二道闸，不许拆",
            SnsDeletedTagLogic.matchName("陈健峰", author(t)),
        )
        assertTrue(
            "反例：16 字以内（上界之内）仍要命中，否则上界就变成另一种静默失效",
            SnsDeletedTagLogic.matchName("陈健峰", author("来自广东深圳南山区的陈健峰的好友")),
        )
    }

    /**
     * ⛔ 降级闸门：**认不出作者行 ⇒ 判不过**（不许退化成「认不出就当通过」）。
     * 「把 `rows == 0` 改成返回 true」这个变异会让本条变红。
     */
    @Test
    fun `一个作者行都没认出：判不过，不许退化成通过`() {
        assertFalse(
            "类名漂了时宁可这一格不打标；退化成通过 ⇒ 窗口内所有候选乱绑",
            SnsDeletedTagLogic.matchName("李四", noAuthor()),
        )
        assertFalse(
            "名字再怎么像也不能补救语料缺失",
            SnsDeletedTagLogic.matchName("王贝拉", noAuthor()),
        )
    }

    /**
     * ⛔ 空文本闸门：作者行控件在、但文本还没渲染出来 ⇒ **判不过**，由调用方延后一拍重试。
     * 「把空文本改成通过」这个变异会让本条变红。
     */
    @Test
    fun `作者行文本为空：判不过（不是跳过校验）`() {
        assertFalse(
            "绑定当拍作者行可能一个字都没有；退化成通过会整体绕开校验 ⇒ 又是 R37 的错绑",
            SnsDeletedTagLogic.matchName("李四", author("")),
        )
        assertFalse(
            "多个作者行全空同理",
            SnsDeletedTagLogic.matchName("李四", author("", "")),
        )
    }

    @Test
    fun `null 名字与单字名字：都不采信`() {
        assertFalse("null 名字不可利用", SnsDeletedTagLogic.matchName(null, author("李四")))
        assertFalse("单字名字不采信", SnsDeletedTagLogic.matchName("李", author("李四")))
        assertFalse("空名字不采信", SnsDeletedTagLogic.matchName("", author("李四")))
    }

    /** 语料里只有 1 个字也不采信：太短的碎片会让**任何**候选都能通过 */
    @Test
    fun `单字作者行文本：不采信`() {
        assertFalse(
            "1 字碎片不能参与对账（去掉 t.length >= 2 闸门这条就会变红）",
            SnsDeletedTagLogic.matchName("李四", author("李")),
        )
    }

    /** 作者行有多条时，有一条命中即可 */
    @Test
    fun `多条作者行文本：有一条命中即可`() {
        assertTrue(SnsDeletedTagLogic.matchName("李四", author("李四", "王贝拉")))
        assertTrue("顺序无关", SnsDeletedTagLogic.matchName("李四", author("王贝拉", "李四")))
    }

    /** 反向包含：界面显示的是**截断/短写**的名字，数据名更长 */
    @Test
    fun `反向包含：文本够长时可用`() {
        assertTrue(
            "界面被 ellipsize 截短成「陈健峰」时也要能对上",
            SnsDeletedTagLogic.matchName("贸易商陈健峰", author("陈健峰")),
        )
    }

    /**
     * ⛔ 反向判定的 3 字闸门：`t.length >= 3 && n.contains(t)`。
     * 去掉闸门 ⇒ 一两个字的碎片能让**任何**候选通过 ⇒ 等于没有校验。
     */
    @Test
    fun `反向包含：文本只有 2 字时不采信`() {
        assertFalse(
            "「健峰」是「陈健峰」的子串，但 2 字碎片不能当证据（3 字闸门）",
            SnsDeletedTagLogic.matchName("陈健峰", author("健峰")),
        )
        assertFalse(
            "「李四」是「张三李四」的子串，同理不能采信",
            SnsDeletedTagLogic.matchName("张三李四", author("李四")),
        )
        assertTrue(
            "反例：3 字就应当放行，否则反向判定形同虚设",
            SnsDeletedTagLogic.matchName("贸易商陈健峰", author("陈健峰")),
        )
    }

    /** 备注名场景：界面显示「李总」而数据名是「李四」⇒ 对不上，宁可不打标 */
    @Test
    fun `备注名与数据名不一致：不命中`() {
        assertFalse(
            "对不上就不打标（漏标好过把邻居的删除状态画到这条上）",
            SnsDeletedTagLogic.matchName("李四", author("李总")),
        )
        assertFalse(
            "界面昵称中间插字（李总四）也不是子串 ⇒ 双向包含都救不了，同样不打标",
            SnsDeletedTagLogic.matchName("李四", author("李总四")),
        )
    }

    // ================================================ pickByAuthor（R37-2 新增纯判据）

    /**
     * `pickByAuthor` 是 `SnsDeletedTag.reconcileAt` 的纯判据部分（窗口挑选 + 对账）。
     * 抽出来就是为了让「邻居候选抢走这一格」这种**真机才看得到**的失效能在 JVM 里锁死。
     */

    /**
     * ⭐ 本轮真正要堵的失效形态（任务书点名的那条）：
     * 这一格显示的是王贝拉的**备注名**「贝拉」（数据名「王贝拉」对不上），
     * 而它的正文里提到了邻居「李四」（10 字 —— 长度上界挡不住）。
     * ⇒ 作者行限定之后：两个候选都判不过 ⇒ 这一格**不打标**（正确处置）；
     *   旧行为（语料含正文）下「李四」会成为唯一命中 ⇒ **打错标签**。
     */
    @Test
    fun `邻居靠正文抢走这一格：作者行限定后判不过`() {
        val names = listOf("王贝拉", "李四")
        val pick = SnsDeletedTagLogic.pickByAuthor(0, names.size, author("贝拉"), { names[it] }, { true })
        assertEquals("作者行只有「贝拉」⇒ 两个候选都对不上 ⇒ 这一格不打标", -1, pick.index)
        assertEquals(0, pick.hits)
    }

    /** 反证：语料里一旦混入正文，邻居立刻成为唯一命中 ⇒ 这一格绑上邻居的 snsId */
    @Test
    fun `反证：正文混进语料时邻居成为唯一命中（打错标签的成因）`() {
        val names = listOf("王贝拉", "李四")
        val bad = SnsDeletedTagLogic.AuthorCorpus(
            listOf("贝拉", "今天和李四一起吃饭"), 2, setOf("AsyncTextView"), 2,
        )
        val pick = SnsDeletedTagLogic.pickByAuthor(0, names.size, bad, { names[it] }, { true })
        assertEquals("「李四」靠正文命中 ⇒ 这一格会被绑成邻居那条动态", 1, pick.index)
        assertEquals(1, pick.hits)
    }

    @Test
    fun `唯一命中：返回绝对下标`() {
        val names = listOf("王贝拉", "李四", "赵六")
        val pick = SnsDeletedTagLogic.pickByAuthor(0, names.size, author("李四"), { names[it] }, { true })
        assertEquals(1, pick.index)
        assertEquals(1, pick.hits)
    }

    @Test
    fun `作者名必须对上：只认作者行里的那条`() {
        val names = listOf("王贝拉", "李四", "赵六")
        val pick = SnsDeletedTagLogic.pickByAuthor(0, names.size, author("赵六"), { names[it] }, { true })
        assertEquals("唯一命中的是 pos+2 那条", 2, pick.index)
        assertEquals(1, pick.hits)
    }

    /** ⛔ 唯一命中语义没被放宽：两个候选都能对上 ⇒ -1（宁可不打标） */
    @Test
    fun `两个候选都命中：-1 而不是取第一个`() {
        val names = listOf("李四", "李四", "赵六")
        val pick = SnsDeletedTagLogic.pickByAuthor(0, names.size, author("李四"), { names[it] }, { true })
        assertEquals("同作者相邻动态 ⇒ 歧义 ⇒ 不打标（R31h 事故同款）", -1, pick.index)
        assertEquals(2, pick.hits)
    }

    /** `idAt` 也必须过：解析不出 snsId 的候选不算命中 */
    @Test
    fun `解析不出 snsId 的候选不算命中`() {
        val names = listOf("李四", "李四", "李四")
        val withId = SnsDeletedTagLogic.pickByAuthor(
            0, names.size, author("李四"), { names[it] }, { i -> i == 2 },
        )
        assertEquals("只有下标 2 同时满足「有 snsId 且作者名对上」", 2, withId.index)
        assertEquals(1, withId.hits)
        val noId = SnsDeletedTagLogic.pickByAuthor(
            0, names.size, author("李四"), { names[it] }, { false },
        )
        assertEquals("作者名全对得上、但一个都解析不出 snsId ⇒ 不打标", -1, noId.index)
        assertEquals(0, noId.hits)
    }

    /** 认不出作者行 ⇒ 窗口内 0 命中（这一格不打标，且**不**退回全文语料） */
    @Test
    fun `认不出作者行：0 命中`() {
        val names = listOf("李四", "王贝拉")
        val pick = SnsDeletedTagLogic.pickByAuthor(0, names.size, noAuthor(), { names[it] }, { true })
        assertEquals(-1, pick.index)
        assertEquals(0, pick.hits)
    }

    /** 作者行文本为空（还没渲染）⇒ 0 命中，等调用方延后一拍重试 */
    @Test
    fun `作者行文本为空：0 命中`() {
        val names = listOf("李四", "王贝拉")
        val pick = SnsDeletedTagLogic.pickByAuthor(0, names.size, author(""), { names[it] }, { true })
        assertEquals(-1, pick.index)
        assertEquals(0, pick.hits)
    }

    /** 名字取不到（null）的候选不算命中 */
    @Test
    fun `候选取不到名字：不算命中`() {
        val pick = SnsDeletedTagLogic.pickByAuthor(0, 3, author("李四"), { null }, { true })
        assertEquals(-1, pick.index)
        assertEquals(0, pick.hits)
    }

    /** 空列表：不访问任何下标 */
    @Test
    fun `size 为 0：0 命中`() {
        val pick = SnsDeletedTagLogic.pickByAuthor(0, 0, author("李四"), { "x" }, { true })
        assertEquals(-1, pick.index)
        assertEquals(0, pick.hits)
    }

    // ======================================================== pickWindow（R37-1C）

    /** 记录 `ok` 被问到的下标，用来验证窗口顺序与越界跳过 */
    private fun probe(pos: Int, size: Int, ok: (Int) -> Boolean): Pair<List<Int>, SnsDeletedTagLogic.WindowPick> {
        val seen = ArrayList<Int>()
        val pick = SnsDeletedTagLogic.pickWindow(pos, size) { i -> seen.add(i); ok(i) }
        return seen to pick
    }

    /**
     * ⭐ 本文件最该守住的一行：**唯一命中**。
     * `matchName` 是双向包含 ⇒ 同一作者的相邻动态互相都能通过校验；
     * 「取第一个命中」会把邻居的 snsId 用在这一格上 ⇒ 把相邻条目的删除状态画到错误的动态上
     * （R31h 事故同款）。宁可 `hits != 1` 返回 `-1` 让这一格不打标。
     */
    @Test
    fun `窗口内两个命中：返回 -1 而不是第一个命中`() {
        val (_, pick) = probe(3, 10) { it == 4 || it == 2 }
        assertEquals("两个命中 ⇒ 这一格不打标（取第一个命中就是 R31h 事故成因）", -1, pick.index)
        assertEquals("命中数要如实报出来，供诊断用", 2, pick.hits)
    }

    /** 顺序无关的复验：命中落在 `pos+1` 与 `pos-2`（窗口里一前一后） */
    @Test
    fun `命中在 pos+1 与 pos-2：仍要判多命中`() {
        val (seen, pick) = probe(5, 10) { it == 6 || it == 3 }
        assertEquals("窗口顺序必须是 pos, pos+1, pos-1, pos+2, pos-2", listOf(5, 6, 4, 7, 3), seen)
        assertEquals("命中数与顺序无关，仍是 2", 2, pick.hits)
        assertEquals("多命中 ⇒ -1，不能返回先问到的 pos+1", -1, pick.index)
    }

    @Test
    fun `窗口内三个命中：也是 -1`() {
        val (_, pick) = probe(4, 10) { it == 3 || it == 4 || it == 5 }
        assertEquals(-1, pick.index)
        assertEquals(3, pick.hits)
    }

    @Test
    fun `唯一命中在 pos+1：返回该绝对下标`() {
        val (_, pick) = probe(3, 10) { it == 4 }
        assertEquals("唯一命中要返回**绝对**下标", 4, pick.index)
        assertEquals(1, pick.hits)
    }

    @Test
    fun `唯一命中在 pos-2：返回该绝对下标`() {
        val (_, pick) = probe(5, 10) { it == 3 }
        assertEquals("唯一命中在窗口末位也要正确返回", 3, pick.index)
        assertEquals(1, pick.hits)
    }

    @Test
    fun `唯一命中就在 pos 自己：直接返回 pos`() {
        val (_, pick) = probe(5, 10) { it == 5 }
        assertEquals(5, pick.index)
        assertEquals(1, pick.hits)
    }

    @Test
    fun `零命中：-1 与 hits 0`() {
        val (_, pick) = probe(5, 10) { false }
        assertEquals("没有候选通过 ⇒ 不打标", -1, pick.index)
        assertEquals(0, pick.hits)
    }

    /** `pos = 0`：负偏移下标必须跳过，不能越界访问 */
    @Test
    fun `pos 为 0：只访问 0 1 2`() {
        val (seen, pick) = probe(0, 5) { false }
        assertEquals("pos-1 / pos-2 是负数，必须跳过", listOf(0, 1, 2), seen)
        assertEquals(-1, pick.index)
        assertEquals(0, pick.hits)
    }

    /** `pos = size - 1`：`pos+1` / `pos+2` 必须跳过，不能抛异常 */
    @Test
    fun `pos 为最后一个：pos+1 与 pos+2 必须跳过`() {
        val (seen, pick) = probe(4, 5) { false }
        assertEquals("越界下标不能被问到（否则 ok 里取 data[i] 会抛）", listOf(4, 3, 2), seen)
        assertEquals(-1, pick.index)
        assertEquals(0, pick.hits)
    }

    @Test
    fun `size 为 0：一个下标都不访问`() {
        val (seen, pick) = probe(0, 0) { true }
        assertTrue("空列表不该访问任何下标", seen.isEmpty())
        assertEquals(-1, pick.index)
        assertEquals(0, pick.hits)
    }

    @Test
    fun `size 为 1 且命中：返回 0`() {
        val (seen, pick) = probe(0, 1) { true }
        assertEquals(listOf(0), seen)
        assertEquals(0, pick.index)
        assertEquals(1, pick.hits)
    }

    @Test
    fun `size 为 1 且 pos 越界：不命中`() {
        val (seen, pick) = probe(7, 1) { true }
        assertTrue("pos 超出 size ⇒ 窗口全越界，一个都不问", seen.isEmpty())
        assertEquals(-1, pick.index)
        assertEquals(0, pick.hits)
    }

    /** 负 pos（header 偏移算歪时出现）：负下标跳过，仍能命中 0 */
    @Test
    fun `pos 为负：负下标跳过，仍能命中 0`() {
        val (seen, pick) = probe(-1, 5) { it == 0 }
        assertEquals("只有 0 和 1 在界内", listOf(0, 1), seen)
        assertEquals(0, pick.index)
        assertEquals(1, pick.hits)
    }

    @Test
    fun `pos 为负且更极端：窗口整体落在界外`() {
        val (seen, pick) = probe(-5, 3) { true }
        assertTrue("pos=-5 时 pos+2=-3 仍在界外", seen.isEmpty())
        assertEquals(-1, pick.index)
        assertEquals(0, pick.hits)
    }

    @Test
    fun `窗口整体命中 5 个：hits 5 且 index 为 -1`() {
        val (seen, pick) = probe(2, 5) { true }
        assertEquals("size=5 时窗口覆盖全部下标", listOf(2, 3, 1, 4, 0), seen)
        assertEquals(-1, pick.index)
        assertEquals(5, pick.hits)
    }

    /** `ok` 不许有副作用：窗口内每个下标都会被调用**一次**（不重复、不遗漏） */
    @Test
    fun `ok 对每个在界下标只调用一次`() {
        val (seen, pick) = probe(3, 10) { false }
        assertEquals("窗口内下标互不相同，每个只能问一次", seen.toSet().size, seen.size)
        assertEquals(5, seen.size)
        assertEquals(-1, pick.index)
    }
}
