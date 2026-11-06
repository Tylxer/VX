package com.lx.wxgroup.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R42 · 朋友圈「已删除」灰标纯判据的守门测试。
 *
 * 判据错的两个方向都是**静默**的：
 * - 该记的不记（丢时间线位却没落台账）⇒ 好友删了动态永远看不到灰标；
 * - 不该记的记了（自己删的 / 没丢时间线位）⇒ 正常动态被插「已删除」灰标。
 * 所以「是否丢 bit1」「是否排除自己」「样式解析」「缓存失效条件」「snsId 有无符号互转」
 * 全部纯函数 + 单测锁住。
 */
class SnsDeletedTagLogicTest {

    // ------------------------------------------------------------------ 样式解析

    @Test
    fun style_defaults_to_line_when_unset_or_blank() {
        // ⚠️ 回归：未设置 / 空 / 空白 ⇒ LINE（默认开），不是 OFF。
        // 否则装包后灰标永不显示（致命 bug，见 docs/_r42_deltag_report.md §复核修正）。
        assertEquals(SnsDeletedTagLogic.Style.LINE, SnsDeletedTagLogic.parseStyle(null))
        assertEquals(SnsDeletedTagLogic.Style.LINE, SnsDeletedTagLogic.parseStyle(""))
        assertEquals(SnsDeletedTagLogic.Style.LINE, SnsDeletedTagLogic.parseStyle("   "))
    }

    @Test
    fun style_true_is_line() {
        // SWITCH 库里存的是 true/false，true / 1（去空白、忽略大小写）⇒ LINE
        assertEquals(SnsDeletedTagLogic.Style.LINE, SnsDeletedTagLogic.parseStyle("true"))
        assertEquals(SnsDeletedTagLogic.Style.LINE, SnsDeletedTagLogic.parseStyle("TRUE"))
        assertEquals(SnsDeletedTagLogic.Style.LINE, SnsDeletedTagLogic.parseStyle(" true "))
        assertEquals(SnsDeletedTagLogic.Style.LINE, SnsDeletedTagLogic.parseStyle("1"))
    }

    @Test
    fun style_line_and_on_are_compat_line() {
        // 兼容：旧写法 line、探针 on，都等同开
        assertEquals(SnsDeletedTagLogic.Style.LINE, SnsDeletedTagLogic.parseStyle("line"))
        assertEquals(SnsDeletedTagLogic.Style.LINE, SnsDeletedTagLogic.parseStyle("LINE"))
        assertEquals(SnsDeletedTagLogic.Style.LINE, SnsDeletedTagLogic.parseStyle(" Line "))
        assertEquals(SnsDeletedTagLogic.Style.LINE, SnsDeletedTagLogic.parseStyle("on"))
        assertEquals(SnsDeletedTagLogic.Style.LINE, SnsDeletedTagLogic.parseStyle("ON"))
    }

    @Test
    fun style_false_and_off_are_off() {
        assertEquals(SnsDeletedTagLogic.Style.OFF, SnsDeletedTagLogic.parseStyle("false"))
        assertEquals(SnsDeletedTagLogic.Style.OFF, SnsDeletedTagLogic.parseStyle("FALSE"))
        assertEquals(SnsDeletedTagLogic.Style.OFF, SnsDeletedTagLogic.parseStyle("off"))
        assertEquals(SnsDeletedTagLogic.Style.OFF, SnsDeletedTagLogic.parseStyle("OFF"))
    }

    @Test
    fun style_anything_else_is_off() {
        assertEquals(SnsDeletedTagLogic.Style.OFF, SnsDeletedTagLogic.parseStyle("inline"))
        assertEquals(SnsDeletedTagLogic.Style.OFF, SnsDeletedTagLogic.parseStyle("offline"))
        assertEquals(SnsDeletedTagLogic.Style.OFF, SnsDeletedTagLogic.parseStyle("2"))
        assertEquals(SnsDeletedTagLogic.Style.OFF, SnsDeletedTagLogic.parseStyle("yes"))
    }

    // ------------------------------------------------------------------ 丢时间线位

    @Test
    fun timeline_loss_is_detected() {
        // 好友删除第一步：10 & ~2 = 8（丢 bit1）
        assertTrue(SnsDeletedTagLogic.isTimelineLoss(10, 8))
        // 写 0 也丢 bit1
        assertTrue(SnsDeletedTagLogic.isTimelineLoss(2, 0))
        assertTrue(SnsDeletedTagLogic.isTimelineLoss(10, 0))
    }

    @Test
    fun album_loss_only_is_not_timeline_loss() {
        // 10→2 丢的是 bit3（相册位），bit1 还在 ⇒ 时间线仍可见 ⇒ 不算删除
        assertFalse(SnsDeletedTagLogic.isTimelineLoss(10, 2))
    }

    @Test
    fun old_without_bit1_is_not_a_loss() {
        assertFalse(SnsDeletedTagLogic.isTimelineLoss(8, 2))   // 旧值 8 无 bit1
        assertFalse(SnsDeletedTagLogic.isTimelineLoss(0, 8))   // 加位
    }

    @Test
    fun unknown_old_or_gain_is_not_a_loss() {
        assertFalse(SnsDeletedTagLogic.isTimelineLoss(null, 8))
        assertFalse(SnsDeletedTagLogic.isTimelineLoss(null, 0))
        assertFalse(SnsDeletedTagLogic.isTimelineLoss(2, 10))   // 加 bit3
        assertFalse(SnsDeletedTagLogic.isTimelineLoss(130, 2))  // 只丢折叠位，bit1 还在
    }

    // ------------------------------------------------------------------ 排除自己

    @Test
    fun self_is_detected() {
        assertTrue(SnsDeletedTagLogic.isSelf("wxid_a", "wxid_a"))
        assertFalse(SnsDeletedTagLogic.isSelf("wxid_a", "wxid_b"))
    }

    @Test
    fun unknown_self_or_author_is_never_self() {
        // 取不到 self ⇒ 不排除（降级）；作者未知也照记
        assertFalse(SnsDeletedTagLogic.isSelf("wxid_a", null))
        assertFalse(SnsDeletedTagLogic.isSelf(null, "wxid_a"))
        assertFalse(SnsDeletedTagLogic.isSelf(null, null))
        assertFalse(SnsDeletedTagLogic.isSelf("wxid_a", ""))
    }

    // ------------------------------------------------------------------ 落台账判据

    @Test
    fun record_when_timeline_lost_and_not_self() {
        assertTrue(SnsDeletedTagLogic.shouldRecord(10, 8, "wxid_b", "wxid_a"))
        assertTrue(SnsDeletedTagLogic.shouldRecord(2, 0, "wxid_b", "wxid_a"))
    }

    @Test
    fun do_not_record_self_deletion() {
        // 自己删自己的动态 ⇒ 不标
        assertFalse(SnsDeletedTagLogic.shouldRecord(10, 8, "wxid_a", "wxid_a"))
        assertFalse(SnsDeletedTagLogic.shouldRecord(2, 0, "wxid_a", "wxid_a"))
    }

    @Test
    fun unknown_self_degrades_to_record() {
        // ⛔ 取不到 self ⇒ 降级照记（但 stat 必须明示，这里只锁「照记」这个行为）
        assertTrue(SnsDeletedTagLogic.shouldRecord(10, 8, "wxid_b", null))
        assertTrue(SnsDeletedTagLogic.shouldRecord(10, 8, null, "wxid_a"))
    }

    @Test
    fun no_timeline_loss_never_records() {
        assertFalse(SnsDeletedTagLogic.shouldRecord(10, 2, "wxid_b", "wxid_a"))
        assertFalse(SnsDeletedTagLogic.shouldRecord(8, 2, "wxid_b", "wxid_a"))
        assertFalse(SnsDeletedTagLogic.shouldRecord(null, 8, "wxid_b", "wxid_a"))
    }

    // ------------------------------------------------------------------ 缓存失效条件

    @Test
    fun cache_usable_requires_nonempty_and_fresh_gen_and_ttl() {
        val ttl = 5000L
        // 正常命中
        assertTrue(SnsDeletedTagLogic.cacheUsable(1000, 500, 3, ttl, 7, 7))
        // 空集永不算命中
        assertFalse(SnsDeletedTagLogic.cacheUsable(1000, 500, 0, ttl, 7, 7))
        // gen 变了（构建后有新记录）⇒ 失效
        assertFalse(SnsDeletedTagLogic.cacheUsable(1000, 500, 3, ttl, 7, 8))
        // 过期 ⇒ 失效
        assertFalse(SnsDeletedTagLogic.cacheUsable(10000, 500, 3, ttl, 7, 7))
    }

    @Test
    fun stamp_after_build_empty_is_immediately_expired() {
        val ttl = 5000L
        assertEquals(1000L, SnsDeletedTagLogic.stampAfterBuild(1000, 3, ttl))
        assertEquals(1000 - ttl, SnsDeletedTagLogic.stampAfterBuild(1000, 0, ttl))
    }

    // ------------------------------------------------------------------ snsId 符号互转

    @Test
    fun parse_unsigned_sns_id_round_trips() {
        val raw = "15033729556253184615"
        val parsed = SnsDeletedTagLogic.parseSnsId(raw)
        assertEquals(java.lang.Long.parseUnsignedLong(raw), parsed)
        assertEquals(raw, java.lang.Long.toUnsignedString(parsed!!))
    }

    @Test
    fun parse_signed_sns_id_is_preserved() {
        val raw = "-3447438253133393222"
        assertEquals(raw.toLong(), SnsDeletedTagLogic.parseSnsId(raw))
    }

    @Test
    fun parse_sns_id_rejects_bad_shapes() {
        assertNull(SnsDeletedTagLogic.parseSnsId(""))
        assertNull(SnsDeletedTagLogic.parseSnsId("abc"))
        assertNull(SnsDeletedTagLogic.parseSnsId("123456789012345678901")) // 21 位超长
        assertNull(SnsDeletedTagLogic.parseSnsId("-"))            // 只有负号
        assertNull(SnsDeletedTagLogic.parseSnsId("sns_table_256863")) // 表名，不是 id
        // ⚠️ parseSnsId 只管形状（纯数字 + ≤20 位），**不管长度下限** ——
        //    15~20 位下限是 ID_RE（parseInfo 里）把的关，这里别指望它拦短串。
        assertEquals(12L, SnsDeletedTagLogic.parseSnsId("12"))
    }

    // ------------------------------------------------------------------ ImproveInfo 解析

    @Test
    fun parse_info_extracts_sns_id_and_name() {
        val info = SnsDeletedTagLogic.parseInfo(
            "ImproveInfo(name=贸易商陈健峰, snsID='15033729556253184615', rowid=256862)"
        )
        assertEquals(java.lang.Long.parseUnsignedLong("15033729556253184615"), info?.snsId)
        assertEquals("贸易商陈健峰", info?.name)
    }

    @Test
    fun parse_info_handles_signed_id() {
        val info = SnsDeletedTagLogic.parseInfo("ImproveInfo(name=x, snsID='-3447438253133393222')")
        assertEquals("-3447438253133393222".toLong(), info?.snsId)
    }

    @Test
    fun parse_info_returns_null_without_sns_id() {
        assertNull(SnsDeletedTagLogic.parseInfo("ImproveInfo(name=x, rowid=1)"))
        assertNull(SnsDeletedTagLogic.parseInfo(null))
        assertNull(SnsDeletedTagLogic.parseInfo("random text"))
    }

    // ------------------------------------------------------------------ 作者名对账

    @Test
    fun name_ok_matches_author_text() {
        assertTrue(SnsDeletedTagLogic.nameOk("陈健峰", listOf("陈健峰")))
        assertTrue(SnsDeletedTagLogic.nameOk("陈健峰", listOf("今天和陈健峰一起吃饭")))
        // 反向包含（文本 ≥3 字才成立）：数据名更长、界面上只显示核心名
        assertTrue(SnsDeletedTagLogic.nameOk("贸易商陈健峰", listOf("陈健峰")))
    }

    @Test
    fun name_ok_rejects_too_short_or_mismatch() {
        assertFalse(SnsDeletedTagLogic.nameOk("陈健峰", listOf("健峰")))       // 文本 2 字，不反向
        assertFalse(SnsDeletedTagLogic.nameOk("贸易商陈健峰", listOf("陈健"))) // 文本 2 字，不反向
        assertFalse(SnsDeletedTagLogic.nameOk("陈健峰", listOf("王小明")))
        assertFalse(SnsDeletedTagLogic.nameOk(null, listOf("陈健峰")))
        assertFalse(SnsDeletedTagLogic.nameOk("李", listOf("李")))            // 名字 <2 字不采信
        assertFalse(SnsDeletedTagLogic.nameOk("陈健峰", emptyList()))
    }

    // ------------------------------------------------------------------ 偏移对账（pickOffset）

    @Test
    fun pick_offset_all_false_returns_null() {
        assertNull(SnsDeletedTagLogic.pickOffset(listOf(0 to false, 1 to false, -1 to false)))
        assertNull(SnsDeletedTagLogic.pickOffset(emptyList()))
    }

    @Test
    fun pick_offset_returns_first_true() {
        // 按传入顺序取第一个通过护栏的偏移
        assertEquals(1, SnsDeletedTagLogic.pickOffset(listOf(0 to false, 1 to true, -1 to true)))
        assertEquals(-1, SnsDeletedTagLogic.pickOffset(listOf(0 to false, 1 to false, -1 to true)))
    }

    @Test
    fun pick_offset_prefers_zero_over_one() {
        // ⛔ 回归：同一个人两条动态时 0 与 1 都通过护栏，必须选 0（直接对应），
        // 不能因为候选不唯一就报 ambig 放弃 —— 这正是真机 match 恒为 0 的根因。
        assertEquals(0, SnsDeletedTagLogic.pickOffset(listOf(0 to true, 1 to true)))
    }

    @Test
    fun pick_offset_respects_input_order() {
        // ⛔ 回归：窗口顺序 0,1,-1,2,-2 不能被排序破坏：-1 必须在 2 之前
        assertEquals(
            -1,
            SnsDeletedTagLogic.pickOffset(
                listOf(0 to false, 1 to false, -1 to true, 2 to true, -2 to false)
            ),
        )
        assertEquals(
            2,
            SnsDeletedTagLogic.pickOffset(
                listOf(0 to false, 1 to false, -1 to false, 2 to true, -2 to false)
            ),
        )
    }

    // ------------------------------------------------------------------ itemView 候选筛选（兜底链）

    private fun cand(
        androidx: Boolean = false,
        rvParent: Boolean = false,
        vg: Boolean = false,
        area: Long = 0L,
    ) = SnsDeletedTagLogic.ItemViewCandidate(androidx, rvParent, vg, area)

    @Test
    fun pick_item_view_empty_returns_minus_one() {
        assertEquals(-1, SnsDeletedTagLogic.pickItemViewIndex(emptyList()))
    }

    @Test
    fun pick_item_view_prefers_androidx_over_everything() {
        val cands = listOf(
            cand(vg = true, area = 9999),
            cand(androidx = true, vg = false),
        )
        // 即使其它候选面积更大，也优先 androidx 声明的 itemView 字段
        assertEquals(1, SnsDeletedTagLogic.pickItemViewIndex(cands))
    }

    @Test
    fun pick_item_view_prefers_rv_parent_next() {
        val cands = listOf(
            cand(vg = true, area = 9999),
            cand(rvParent = true, vg = false),
        )
        assertEquals(1, SnsDeletedTagLogic.pickItemViewIndex(cands))
    }

    @Test
    fun pick_item_view_prefers_largest_viewgroup() {
        val cands = listOf(
            cand(vg = true, area = 10),
            cand(vg = true, area = 100),
            cand(vg = true, area = 50),
        )
        assertEquals(1, SnsDeletedTagLogic.pickItemViewIndex(cands))
    }

    @Test
    fun pick_item_view_prefers_viewgroup_over_non_viewgroup() {
        // ⛔ 回归：即使 ViewGroup 面积是 0（未 layout），也绝不能选 ImageView 之类非容器 View
        val cands = listOf(
            cand(vg = false, area = 100),
            cand(vg = true, area = 0),
            cand(vg = false, area = 9999),
        )
        assertEquals(1, SnsDeletedTagLogic.pickItemViewIndex(cands))
    }

    @Test
    fun pick_item_view_last_resort_any_view_when_no_viewgroup() {
        // 一个 ViewGroup 都没有时，最后兜底退到任意 View（第 0 个）
        val cands = listOf(
            cand(vg = false, area = 10),
            cand(vg = false, area = 20),
        )
        assertEquals(0, SnsDeletedTagLogic.pickItemViewIndex(cands))
    }

    // ------------------------------------------------------------------ 正文 TextView 路径

    @Test
    fun body_text_path_matches_real_dump_structure() {
        // 对应真机 uiautomator dump：
        //   LinearLayout(root)
        //     TextView "verity"                     ← 昵称（第 0 个子节点，裸 TextView）
        //     RelativeLayout                          ← 内容容器
        //       FrameLayout
        //         LinearLayout
        //           ImageView
        //           ViewGroup                          ← 内容区
        //             TextView "3"                     ← 正文
        //             LinearLayout
        //           View
        val root = SnsDeletedTagLogic.SnsNode(null, listOf(
            SnsDeletedTagLogic.SnsNode("verity"),
            SnsDeletedTagLogic.SnsNode(null, listOf(
                SnsDeletedTagLogic.SnsNode(null, listOf(
                    SnsDeletedTagLogic.SnsNode(null, listOf(
                        SnsDeletedTagLogic.SnsNode(null),
                        SnsDeletedTagLogic.SnsNode(null, listOf(
                            SnsDeletedTagLogic.SnsNode("3"),
                            SnsDeletedTagLogic.SnsNode(null),
                        )),
                        SnsDeletedTagLogic.SnsNode(null),
                    )),
                )),
            )),
        ))
        // 正文 "3" 的路径：root.child1(RelativeLayout).child0(FrameLayout).child0(LinearLayout).child1(ViewGroup).child0("3")
        assertEquals(listOf(1, 0, 0, 1, 0), SnsDeletedTagLogic.bodyTextPath(root))
    }

    @Test
    fun body_text_path_null_when_no_container() {
        // 根没有任何 ViewGroup 子节点 ⇒ null（退回加到根末尾）
        val root = SnsDeletedTagLogic.SnsNode(null, listOf(
            SnsDeletedTagLogic.SnsNode("verity"),
        ))
        assertNull(SnsDeletedTagLogic.bodyTextPath(root))
    }

    @Test
    fun body_text_path_skips_blank_text_and_finds_next() {
        // 内容容器里第一个文本是空白，应跳到下一个非空文本
        val root = SnsDeletedTagLogic.SnsNode(null, listOf(
            SnsDeletedTagLogic.SnsNode("verity"),
            SnsDeletedTagLogic.SnsNode(null, listOf(
                SnsDeletedTagLogic.SnsNode("   "),
                SnsDeletedTagLogic.SnsNode("正文内容"),
            )),
        ))
        assertEquals(listOf(1, 1), SnsDeletedTagLogic.bodyTextPath(root))
    }

    @Test
    fun body_text_path_finds_text_directly_under_container() {
        // 内容容器是根的第 0 个 ViewGroup，正文直接在里面
        val root = SnsDeletedTagLogic.SnsNode(null, listOf(
            SnsDeletedTagLogic.SnsNode(null, listOf(
                SnsDeletedTagLogic.SnsNode("正文"),
            )),
        ))
        assertEquals(listOf(0, 0), SnsDeletedTagLogic.bodyTextPath(root))
    }

    // ------------------------------------------------------------------ 错位常数可信度（N1+N6 合并治理）

    /** 测试助手：构造一个已校准的 Learned；headerSig 默认取 -offset（= 头部项数 H）。 */
    private fun learned(offset: Int, votes: Int = 1, headerSig: Int = -offset) =
        SnsDeletedTagLogic.Learned(offset, votes, headerSig)

    // ------------------------------------------------------------------ 窗口锚定（anchorOffset）· 相邻同名误报修复

    /**
     * 相邻同名 + 未校准 ⇒ 保守放弃。
     * 用户可见：屏上全是同一人的动态、还没法确定固定错位时，宁可不打标，也不能把
     * 「已删除」灰标挂到新发未删的那条上。旧 pickOffset 会返回 0（串位），这里必须 null。
     */
    @Test
    fun anchor_offset_adjacent_same_name_without_learned_is_conservative_null() {
        // d=0 与 d=-1 都通过护栏（两条 verity 相邻），learned 还没校准
        assertNull(SnsDeletedTagLogic.anchorOffset(listOf(0, -1), null))
        assertNull(SnsDeletedTagLogic.anchorOffset(listOf(0, 1), null))
        assertNull(SnsDeletedTagLogic.anchorOffset(listOf(0, 1, -1), null))
    }

    /**
     * 相邻同名 + 已校准 ⇒ 沿用 learned，抵御 d=0 假命中（核心断言）。
     * 用户可见：新发动态（错位 O=-1 才是自己）不会被锚到 d=0 那条相邻「已删除」动态上。
     */
    @Test
    fun anchor_offset_adjacent_same_name_with_learned_prefers_learned_over_zero() {
        assertEquals(-1, SnsDeletedTagLogic.anchorOffset(listOf(0, -1), learned(-1)))
        assertEquals(-2, SnsDeletedTagLogic.anchorOffset(listOf(0, 1, -1, -2), learned(-2)))
        // learned=0 也是合法「无错位」结论：0 在 passing 里就该沿用，不能被当 null 丢弃（N6）
        assertEquals(0, SnsDeletedTagLogic.anchorOffset(listOf(0, -1), learned(0)))
    }

    /**
     * 非同名候选（窗口里恰好一个通过护栏）⇒ 采信它。这是错位常数的校准源，
     * 对应真机屏上第 3 条「万华化学—李思远pc」那条非同名动态。
     */
    @Test
    fun anchor_offset_single_passing_is_accepted_as_calibration() {
        assertEquals(-1, SnsDeletedTagLogic.anchorOffset(listOf(-1), null))
        assertEquals(2, SnsDeletedTagLogic.anchorOffset(listOf(2), null))
        assertEquals(0, SnsDeletedTagLogic.anchorOffset(listOf(0), null))
        // 已校准 + 恰好一个通过：仍返回那一个（与 learned 一致时等价于沿用）
        assertEquals(-1, SnsDeletedTagLogic.anchorOffset(listOf(-1), learned(-1)))
    }

    @Test
    fun anchor_offset_empty_passing_is_null() {
        assertNull(SnsDeletedTagLogic.anchorOffset(emptyList(), null))
        assertNull(SnsDeletedTagLogic.anchorOffset(emptyList(), learned(-1)))
    }

    /** learned 已失效（不在本次 passing）且窗口非歧义 ⇒ 采信唯一通过、可重新校准。 */
    @Test
    fun anchor_offset_stale_learned_falls_back_to_single() {
        assertEquals(1, SnsDeletedTagLogic.anchorOffset(listOf(1), learned(-1)))
    }

    /** learned 已失效 + 窗口歧义 ⇒ 仍保守 null（不能因为 learned 存在就乱猜第一个）。 */
    @Test
    fun anchor_offset_stale_learned_with_ambiguous_is_null() {
        assertNull(SnsDeletedTagLogic.anchorOffset(listOf(0, 1), learned(-1)))
        assertNull(SnsDeletedTagLogic.anchorOffset(listOf(0, 1, 2), learned(-2)))
    }

    // ------------------------------------------------------------------ 可信度显式建模（reconcile / consistent / vote）

    /**
     * 头部构成变化（headerSig 变了）⇒ reconcile 返回 null ⇒ 旧常数立即失效（N1 核心）。
     * 用户可见：O 从 -1 漂到 -2（封面项数 1→2）后，旧的 -1 不再被沿用，宁可不标也不错标。
     */
    @Test
    fun reconcile_returns_null_when_header_signature_changed() {
        // 旧常数：offset=-1 采自 headerSig=1（一个封面头）
        val stale = learned(-1, votes = 2, headerSig = 1)
        // 头部变多（headerSig=2）⇒ 旧常数失效
        assertNull(SnsDeletedTagLogic.reconcile(stale, 2))
        // 头部未变 ⇒ 仍可信
        assertEquals(stale, SnsDeletedTagLogic.reconcile(stale, 1))
        // 没有旧常数 ⇒ 恒 null
        assertNull(SnsDeletedTagLogic.reconcile(null, 1))
    }

    /** 头部变化后的歧义窗口不得沿用旧常数：reconcile→null 后 anchorOffset 保守返回 null。 */
    @Test
    fun header_change_makes_ambiguous_window_conservative() {
        // O 漂到 -2 后，同名相邻窗口 passing=[-2,-1]；旧 learned 已被 reconcile 判失效
        val reconciled = SnsDeletedTagLogic.reconcile(learned(-1, votes = 2, headerSig = 1), 2)
        assertNull(reconciled)
        // 无可信常数 + 歧义 ⇒ 保守 null（不沿用旧 -1，不误标）
        assertNull(SnsDeletedTagLogic.anchorOffset(listOf(-2, -1), reconciled))
    }

    /** 毒样本：O=-1（headerSig=1）时采样到 offset=0 ⇒ 与头部签名不一致 ⇒ vote 拒绝，0 不被信任。 */
    @Test
    fun poison_single_zero_is_rejected_and_not_promoted_to_trusted() {
        val stored = learned(-1, votes = 2, headerSig = 1)
        // 0 != -headerSig(= -1) ⇒ consistent=false ⇒ vote 返回原 stored，0 不落票
        assertEquals(stored, SnsDeletedTagLogic.vote(stored, 0, 1))
        // 0 从未成为可信值；同名相邻窗口下仍沿用 -1（而不是被 0 带偏）
        assertEquals(-1, SnsDeletedTagLogic.anchorOffset(listOf(0, -1), stored))
    }

    /** 单次采样不得可信：签名未知（headerSig=-1）时任何偏移都过不了 consistent 闸门。 */
    @Test
    fun single_sample_without_header_signal_is_not_trusted() {
        // headerSig=-1（未知）：consistent(offset, -1) 恒 false（offset 不可能是 1）
        assertFalse(SnsDeletedTagLogic.consistent(-1, -1))
        assertFalse(SnsDeletedTagLogic.consistent(0, -1))
        // 未知签名下投票被拒，返回原值（null）
        assertNull(SnsDeletedTagLogic.vote(null, -1, -1))
    }

    /** vote：同偏移同签名累票（封顶 2），偏移不同则重置为 1。 */
    @Test
    fun vote_accumulates_same_offset_and_resets_on_change() {
        assertEquals(learned(-1, 1, 1), SnsDeletedTagLogic.vote(null, -1, 1))
        assertEquals(learned(-1, 2, 1), SnsDeletedTagLogic.vote(learned(-1, 1, 1), -1, 1))
        // 封顶 2
        assertEquals(learned(-1, 2, 1), SnsDeletedTagLogic.vote(learned(-1, 2, 1), -1, 1))
        // 偏移漂移 ⇒ 重置为 1 票
        assertEquals(learned(-2, 1, 2), SnsDeletedTagLogic.vote(learned(-1, 2, 1), -2, 2))
    }

    // ------------------------------------------------------------------ 重扫判据（shouldResweep）· B1 冷启首屏补标 + N6 O=0

    /**
     * 本次绑定把错位常数**首次**从 null 变成可用值 ⇒ 触发重扫（0 也触发，N6 修复）。
     * 这正是冷启首屏：pos3 非同名动态校准出 learned=-1 的那一刻，必须 resweep 把
     * 之前被保守放弃（HIDE、pending）的真·被删动态补上灰标。
     */
    @Test
    fun should_resweep_triggers_on_first_calibration() {
        assertTrue(SnsDeletedTagLogic.shouldResweep(null, learned(-1)))   // null→-1 触发
        assertTrue(SnsDeletedTagLogic.shouldResweep(null, learned(1)))    // null→1（正错位）也触发
        assertTrue(SnsDeletedTagLogic.shouldResweep(null, learned(0)))    // null→0 触发（N6：0 合法）
    }

    /**
     * 其余过渡一律不触发：
     * - null→null 不触发（校准失败 / 移除，什么都没变）；
     * - -1→-1 不触发（早已校准，非「首次」）；
     * - -1→null 不触发（校准值被移除，状态退化而非前进）。
     */
    @Test
    fun should_resweep_does_not_trigger_on_null_or_non_first_calibration() {
        assertFalse(SnsDeletedTagLogic.shouldResweep(null, null))
        assertFalse(SnsDeletedTagLogic.shouldResweep(learned(-1), learned(-1)))
        assertFalse(SnsDeletedTagLogic.shouldResweep(learned(-1), null))
    }

    // ------------------------------------------------------------------ N6 场景：O=0 设备同一好友连续两条冷启首屏

    /**
     * O=0（无封面头）时，同一好友连续两条（一条新发未删、一条真被删）冷启首屏：
     * 两个 item 首绑同名歧义 ⇒ 保守 HIDE（pending）；第一个异作者 item 校准出 learned=0
     * （1 票即可信，头部签名 0 校验通过）⇒ resweep 触发 ⇒ 真被删那条补标、新发那条不误标。
     *
     * 旧代码 `d==0` 不缓存 ⇒ resweep 永不触发 ⇒ 两条永久 HIDE（连真被删也不标），本条锁住修复。
     */
    @Test
    fun scenario_o0_same_author_cold_start_recovers_deleted_mark() {
        val deletedId = 222L
        val deletedSet = setOf(deletedId)

        // 冷启首绑：pos0=A（新发）、pos1=B（被删），同名相邻窗口歧义，learned 未知
        assertNull(SnsDeletedTagLogic.anchorOffset(listOf(0, 1), null))   // A：d=0→A, d=1→B 都过
        assertNull(SnsDeletedTagLogic.anchorOffset(listOf(0, -1), null))  // B：d=0→B, d=-1→A 都过

        // 第一个异作者 C（pos2）：窗口唯一 d=0 ⇒ 校准 learned=0（headerSig=0 一致）
        val cal = SnsDeletedTagLogic.calibrate(listOf(0), null, 0)
        assertEquals(0, cal.d)
        assertEquals(learned(0, 1, 0), cal.newStored)
        assertTrue(cal.resweep)   // 首票即触发 resweep（N6 核心：0 也要触发）

        // resweep 重扫 A：learned(0) 在 passing=[0,1] ⇒ 锚 0 ⇒ A（新发，不在表）⇒ HIDE
        val dA = SnsDeletedTagLogic.anchorOffset(listOf(0, 1), cal.newStored)
        assertEquals(0, dA)
        assertEquals(
            SnsDeletedTagLogic.MarkAction.HIDE,
            SnsDeletedTagLogic.markDecision(true, true, 111L, 111L in deletedSet),
        )
        // resweep 重扫 B：learned(0) 在 passing=[0,-1] ⇒ 锚 0 ⇒ B（真被删）⇒ SHOW
        val dB = SnsDeletedTagLogic.anchorOffset(listOf(0, -1), cal.newStored)
        assertEquals(0, dB)
        assertEquals(
            SnsDeletedTagLogic.MarkAction.SHOW,
            SnsDeletedTagLogic.markDecision(true, true, deletedId, deletedId in deletedSet),
        )
    }

    // ------------------------------------------------------------------ 渲染决策（markDecision）· 复用负路径

    /** 真·被删动态：四个条件全成立 ⇒ SHOW（不回归 match=hits 成果）。 */
    @Test
    fun mark_decision_show_only_for_resolved_deleted_id() {
        assertEquals(
            SnsDeletedTagLogic.MarkAction.SHOW,
            SnsDeletedTagLogic.markDecision(true, true, 123L, true),
        )
    }

    /** 新发未删动态：解析成功但不在已删清单 ⇒ HIDE（验收①：绝不挂灰标）。 */
    @Test
    fun mark_decision_hide_for_new_post_not_in_deleted() {
        assertEquals(
            SnsDeletedTagLogic.MarkAction.HIDE,
            SnsDeletedTagLogic.markDecision(true, true, 999L, false),
        )
    }

    /**
     * 复用负路径：解析失败（resolve 返回 null / 封面项）⇒ HIDE ⇒ 调用方 removeMark
     * 摘掉复用 View 上遗留的灰标。即使 inDeleted 误传 true 也必须 HIDE（互斥，不与 SHOW 并列）。
     */
    @Test
    fun mark_decision_hide_when_not_resolved_clears_recycled_mark() {
        assertEquals(
            SnsDeletedTagLogic.MarkAction.HIDE,
            SnsDeletedTagLogic.markDecision(true, false, 123L, true),
        )
        assertEquals(
            SnsDeletedTagLogic.MarkAction.HIDE,
            SnsDeletedTagLogic.markDecision(true, false, 0L, false),
        )
    }

    @Test
    fun mark_decision_hide_when_style_off() {
        assertEquals(
            SnsDeletedTagLogic.MarkAction.HIDE,
            SnsDeletedTagLogic.markDecision(false, true, 123L, true),
        )
    }

    @Test
    fun mark_decision_hide_when_snsid_zero() {
        // snsId=0 是「没解析出 id」的哨兵，即便命中位误传 true 也不许插标
        assertEquals(
            SnsDeletedTagLogic.MarkAction.HIDE,
            SnsDeletedTagLogic.markDecision(true, true, 0L, true),
        )
    }

    /**
     * 串联场景（对着用户可见事实）：verity 两条相邻动态，一条新发未删、一条真被删，
     * 错位常数 O=-1 已由非同名区校准。两格窗口都是同名歧义 passing=[0,-1]：
     *  - 两格都因 learned=-1 锚到「自己那条」；
     *  - 新发那格 snsId 不在清单 ⇒ HIDE（不挂标）；
     *  - 真被删那格 snsId 在清单 ⇒ SHOW（仍挂标）。
     * 这把 anchorOffset 与 markDecision 串起来，同时守住验收①②。
     */
    @Test
    fun scenario_adjacent_same_name_only_real_deleted_post_is_marked() {
        val passing = listOf(0, -1)           // d=0 与 d=-1 都通过（同名相邻）
        val learned = learned(-1)              // 由非同名动态校准出的固定错位（headerSig=1 一致）
        val d = SnsDeletedTagLogic.anchorOffset(passing, learned)
        assertEquals(-1, d)                    // 两格都锚到自己那条，不是 d=0 的相邻条

        // d=-1 时：新发动态格锚到 NEW（不在清单），真被删格锚到 DEL（在清单）
        val newPostId = 111L
        val deletedId = 222L
        val deletedSet = setOf(deletedId)
        assertEquals(
            SnsDeletedTagLogic.MarkAction.HIDE,
            SnsDeletedTagLogic.markDecision(true, true, newPostId, newPostId in deletedSet),
        )
        assertEquals(
            SnsDeletedTagLogic.MarkAction.SHOW,
            SnsDeletedTagLogic.markDecision(true, true, deletedId, deletedId in deletedSet),
        )
    }

    /**
     * 冷启首屏时序（B1）：offsets 冷启为空 ⇒ learned=null，同名相邻窗口保守返回 null（漏标），
     * 但校准成功后 resweep 用保存的 (adapter, pos) 重新 resolve，pos2 被重新判定为 SHOW（补标）。
     *
     * 用纯函数模拟 pending 重扫决策（calibrate + anchorOffset + markDecision）：
     *  - 校准前：pos2 窗口 passing=[-1,-2]、learned=null ⇒ 同名歧义 ⇒ null ⇒ HIDE（漏标，不是误标）；
     *  - pos3 非同名：passing=[-1] 唯一 ⇒ 采信并校准 learned=-1（headerSig=1 一致）；
     *  - 校准触发 resweep：pos2 用 (adapter,pos) 重新 resolve ⇒ learned=-1 in passing ⇒ d=-1 ⇒ 锚到自己；
     *  - 真被删 snsId 在清单 ⇒ SHOW（补标成功）。
     */
    @Test
    fun scenario_cold_start_resweep_recovers_real_deleted_mark() {
        // 校准前：pos2 同名相邻窗口，learned=null ⇒ 保守 null（漏标好过打错标）
        val passingPos2 = listOf(-1, -2)
        assertNull(SnsDeletedTagLogic.anchorOffset(passingPos2, null))

        // pos3 非同名：窗口唯一通过 ⇒ 采信并校准错位常数（headerSig=1 与 offset=-1 一致）
        val cal = SnsDeletedTagLogic.calibrate(listOf(-1), null, 1)
        assertEquals(-1, cal.d)
        assertEquals(learned(-1, 1, 1), cal.newStored)
        assertTrue(cal.resweep)

        // 校准成功后 resweep 重扫 pos2：learned=-1 在 passing 里 ⇒ 沿用 -1（锚到自己）
        val d = SnsDeletedTagLogic.anchorOffset(passingPos2, cal.newStored)
        assertEquals(-1, d)

        // d=-1 锚到 pos2 自己 ⇒ 真被删 snsId ⇒ SHOW（补标成功）
        val deletedId = 222L
        val deletedSet = setOf(deletedId)
        assertEquals(
            SnsDeletedTagLogic.MarkAction.SHOW,
            SnsDeletedTagLogic.markDecision(true, true, deletedId, deletedId in deletedSet),
        )
    }

    // ------------------------------------------------------------------ 头部签名观测（observeHeader）· B1/B2 回归

    /**
     * B1 主用例：滚回顶部 / 刷新时间线重绑封面（pos=0 < prev=1）**不得**复位签名。
     * 真机 H=1、firstDataPos=1，封面项在 pos=0 —— `pos=0` 是「从来就是头部」的位置，
     * 旧 `<=` 写法会触发复位 ⇒ 签名退化为 -1 ⇒ 校准被误判失效。这里必须仍返回 1。
     *
     * 附带 H=2 的封面重绑（pos=0 < prev=2）：若复位判据是 `<=`，会错误把签名从 2 前移到 1，
     * 该断言正是抓 M8（`==`→`<=`）的闸门。
     */
    @Test
    fun header_rebind_does_not_reset_signature() {
        assertEquals(1, SnsDeletedTagLogic.observeHeader(1, 0, isData = false))
        assertEquals(2, SnsDeletedTagLogic.observeHeader(2, 0, isData = false))
    }

    /**
     * 原先的首个数据位（pos=1）现在成了头部 ⇒ 头部变多 ⇒ 前移成 pos+1=2（正向已知值，
     * 不置 -1 失明）。锁住 `pos == prev`（而非 `<=`）与 `pos + 1`（而非 -1）两处。
     */
    @Test
    fun header_growth_shifts_signature() {
        assertEquals(2, SnsDeletedTagLogic.observeHeader(1, 1, isData = false))
    }

    /** 数据项绑定取单调最小：prev 已知时取 min，prev 未知（-1）时首绑即 pos。 */
    @Test
    fun data_bind_keeps_monotonic_min() {
        assertEquals(2, SnsDeletedTagLogic.observeHeader(2, 3, isData = true))
        assertEquals(1, SnsDeletedTagLogic.observeHeader(3, 1, isData = true))
        // 未知（-1）首绑数据项 ⇒ 直接采信 pos
        assertEquals(1, SnsDeletedTagLogic.observeHeader(-1, 1, isData = true))
    }

    /**
     * B1：头部重绑（签名短暂未知 -1）后，校准条目必须「存活」，签名回到旧值 1 时旧常数重新有效。
     *
     * 串：Learned(-1,1,1) → 头部重绑（calibrate([], stored, -1)）→ 再绑 pos=1/2（签名 1）。
     * 头部重绑时 reconcile 判签名不符 ⇒ newStored=null；调用侧此时**只许不更新、不许 remove**，
     * 于是再绑时 stored 仍是 Learned(-1,1,1) ⇒ anchorOffset 拿到 -1，两个同名相邻格恢复
     * 「新发 HIDE / 真被删 SHOW」。反证在末尾：若调用侧把 newStored==null 当删除，再绑时
     * stored==null + passing=[0,-1] 同名歧义 ⇒ d==null ⇒ 灰标消失。
     */
    @Test
    fun calibration_survives_signature_flap() {
        // 初始已校准
        var stored: SnsDeletedTagLogic.Learned? = learned(-1, 1, 1)

        // —— 头部重绑：签名未知（-1），reconcile 判签名不符 ⇒ 本次不采信、newStored=null
        val flap = SnsDeletedTagLogic.calibrate(emptyList(), stored, -1)
        assertNull(flap.d)
        assertNull(flap.newStored)
        // 模拟 resolve 写回：newStored==null ⇒ 不更新（保留旧条目），这就是「不删」契约
        if (flap.newStored != null) stored = flap.newStored

        // —— 再绑 pos=1/2：签名回到 1 ⇒ 旧常数重新有效 ⇒ 锚 -1
        val rebind = SnsDeletedTagLogic.calibrate(listOf(0, -1), stored, 1)
        assertEquals(-1, rebind.d)
        assertEquals(learned(-1, 1, 1), rebind.newStored)

        // 两个同名相邻格恢复：新发（未删）HIDE、真被删 SHOW
        val deletedSet = setOf(222L)
        val d = SnsDeletedTagLogic.anchorOffset(listOf(0, -1), rebind.newStored)
        assertEquals(-1, d)
        assertEquals(
            SnsDeletedTagLogic.MarkAction.HIDE,
            SnsDeletedTagLogic.markDecision(true, true, 111L, 111L in deletedSet),
        )
        assertEquals(
            SnsDeletedTagLogic.MarkAction.SHOW,
            SnsDeletedTagLogic.markDecision(true, true, 222L, 222L in deletedSet),
        )

        // 反证（锁「删条目」写法）：若上面把条目删了，再绑时 stored==null ⇒ 同名歧义窗口无法锚定 ⇒ 漏标
        val deleted = SnsDeletedTagLogic.calibrate(listOf(0, -1), null, 1)
        assertNull(deleted.d)
    }

    /**
     * B2 隔离：两个不同 adapter key 的签名互不影响。
     * 纯函数层面各 key 各测一遍：keyA=朋友圈（封面 pos=0、数据 pos=1 ⇒ 签名 1）；
     * keyB=聊天列表（getData() 不含 SnsInfo ⇒ passing 恒空 ⇒ 全是头部 ⇒ 签名恒 -1）。
     * B 的「恒空头部」不得污染 A：A 的签名 1 仍能通过 consistent(-1, 1) 校验。
     */
    @Test
    fun header_signature_isolated_per_adapter_key() {
        // keyA：封面（头部，未知）→ 首条数据（pos=1）⇒ 签名 1
        var a = SnsDeletedTagLogic.observeHeader(-1, 0, isData = false)
        assertEquals(-1, a)
        a = SnsDeletedTagLogic.observeHeader(a, 1, isData = true)
        assertEquals(1, a)

        // keyB：全是头部（聊天列表），签名恒 -1，且独立演进
        var b = SnsDeletedTagLogic.observeHeader(-1, 0, isData = false)
        assertEquals(-1, b)
        b = SnsDeletedTagLogic.observeHeader(b, 1, isData = false)
        assertEquals(-1, b)

        // 隔离断言：A 的签名 1 仍有效（若签名是全局单例，B 每次绑定会把 A 改坏 ⇒ 正确采样被 vote 拒）
        assertTrue(SnsDeletedTagLogic.consistent(-1, a))
    }
}
