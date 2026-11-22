package com.lx.wxgroup.feature

import com.lx.wxgroup.data.MediaGuardLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R73 图片转发第二层缺口「URI → 真实大图路径」的**纯逻辑**单测（零 Android / 零 IO）。
 *
 * 被测对象：
 * - [ForwardLogic.imageBucket]：md5 → `image2` 分桶片段（真机 4 组样本，取证文档 §1.1）；
 * - [ForwardLogic.pickImageFile]：桶内按「含 md5、非 th_、图片扩展名、字节最大」挑大图（§4.3/§7.2）；
 * - [MediaGuardLogic.hexToken]：虚拟 URI → md5（复用现成纯函数，§1.3）；
 * - [ForwardLogic.planFor]：解析失败保留 URI ⇒ 安全 SKIP（不空发，§5.1 C）。
 *
 * 数据来源纪律：`docs/_r73_image_path_forensics.md` 真机 4 组样本 + 同桶文件大小实录；
 * 这些数据**不上手机就能锁住**选路口径。宿主侧 `ForceForward.resolveImagePath` 的 IO
 * （dataDir / listFiles / exists）只做存在性判定，留给装机验收（§6.2）。
 */
class ImagePathLogicTest {

    // 真机样本（取证文档 §1.1 / _r52_device_log_snapshot.md:30）。四条都必须恰好 32 位：
    // imageBucket/pickImageFile 对长度≠32 一律返回 null（不猜不截断），写错长度会让测试假红。
    private val md5A = "5a9a0b372dbc9b4465e776185700f1a7" // 前缀 5a9a → 桶 5a/9a（真机原串，末位 7）
    private val md5B = "1a7b9f5d918f9d80ce5c8065d3dcb5e7" // 前缀 1a7b → 桶 1a/7b（真机原串）
    private val md5C = "3fadbde02a26c4ab4034c3ab3a604e51" // 前缀 3fad → 桶 3f/ad（真机原串）
    private val md5D = "ecd4840123456789abcdef012345674a" // 前缀 ecd4 → 桶 ec/d4

    // ---------------------------------------------------------------- imageBucket

    @Test
    fun imageBucketMapsFourRealDeviceSamples() {
        // 【真机确定】4 组独立 md5 样本与目录前缀逐字吻合（取证文档 §1.1）
        assertEquals("5a/9a", ForwardLogic.imageBucket(md5A))
        assertEquals("1a/7b", ForwardLogic.imageBucket(md5B))
        assertEquals("3f/ad", ForwardLogic.imageBucket(md5C))
        assertEquals("ec/d4", ForwardLogic.imageBucket(md5D))
    }

    @Test
    fun imageBucketRejectsNonMd5() {
        // 长度 != MediaGuardLogic.MD5_LEN(32) 一律 null —— 不猜、不截断
        assertNull(ForwardLogic.imageBucket(""))
        assertNull(ForwardLogic.imageBucket(md5A.dropLast(1)))     // 31 位
        assertNull(ForwardLogic.imageBucket(md5A + "0"))           // 33 位
        assertNull(ForwardLogic.imageBucket("th_$md5A"))           // 35 位（带 th_ 前缀）
    }

    // ---------------------------------------------------------------- pickImageFile

    @Test
    fun pickImageFilePrefersLargestNonThumbBigImage() {
        // ① 同桶同时有缩略图(4KB) + 压缩图(40KB) + 大图(140KB) → 选字节最大且非 th_ 的大图
        val files = listOf(
            "th_$md5A" to 4_230L,
            "$md5A.png" to 40_843L,
            "$md5A.jpg" to 140_942L,
        )
        assertEquals("$md5A.jpg", ForwardLogic.pickImageFile(files, md5A))
    }

    @Test
    fun pickImageFileFallsBackToHdThumb() {
        // ② 只有 HD 缩略图（真机形态 th_<md5>hd / th_<md5>hd_tmp.jpg）→ 选 hd
        assertEquals(
            "th_${md5A}hd",
            ForwardLogic.pickImageFile(listOf("th_${md5A}hd" to 16_667L), md5A)
        )
        assertEquals(
            "th_${md5A}hd_tmp.jpg",
            ForwardLogic.pickImageFile(listOf("th_${md5A}hd_tmp.jpg" to 16_667L), md5A)
        )
    }

    @Test
    fun pickImageFileFallsBackToThumbAsLastResort() {
        // ③ 只有普通缩略图 → 兜底选 th_<md5>（能发出图但模糊，宿主侧应打 warn 留痕）
        assertEquals(
            "th_$md5A",
            ForwardLogic.pickImageFile(listOf("th_$md5A" to 4_230L), md5A)
        )
    }

    /**
     * R73 第三层落锤：36B 坏占位文件 `th_<md5>hd⌖` 必须被大小阈值滤掉。
     *
     * 真机现场：目标图 md5=216b1f7ccd... 的桶里没有大图、只有一个 36B 的坏 `hd⌖` 文件，
     * 旧版 tier-2 只按名字匹配就选中它 → 微信上传失败、对端收不到（本机提示发送失败）。
     *
     * 变异验证：删掉 pickImageFile 的 `valid = files.filter { bytes >= 1024 }` 这道过滤，
     * tier-2 会选中 36B 的 `th_<md5>hd⌖`（名字含 th_<md5>hd），本用例断言转红 ⇒ 证明判别力。
     */
    @Test
    fun pickImageFileRejectsCorruptTinyHdPlaceholder() {
        // ① 36B 坏 hd + 15KB 真缩略图 → 必须选真缩略图，绝不选坏 hd
        val withRealThumb = listOf(
            "th_${md5A}hd\u2316" to 36L,   // 坏占位：36 字节 + 尾部特殊字符 ⌖
            "th_$md5A" to 15_906L,          // 真缩略图 15KB
        )
        assertEquals("th_$md5A", ForwardLogic.pickImageFile(withRealThumb, md5A))

        // ② 36B 坏 hd + 真大图 → 选大图
        val withBig = listOf(
            "th_${md5A}hd\u2316" to 36L,
            "$md5A.jpg" to 253_478L,
        )
        assertEquals("$md5A.jpg", ForwardLogic.pickImageFile(withBig, md5A))

        // ③ 只有 36B 坏 hd（无其它有效文件）→ null，让调用方安全 SKIP（不发坏文件）
        assertNull(
            ForwardLogic.pickImageFile(listOf("th_${md5A}hd\u2316" to 36L), md5A)
        )
    }

    @Test
    fun pickImageFileReturnsNullWhenNoCandidate() {
        // ④ 空列表 / 文件名不含 md5 → null（解析失败，保留 URI 让 planFor 安全 SKIP）
        assertNull(ForwardLogic.pickImageFile(emptyList(), md5A))
        val other = "a".repeat(32)
        assertNull(
            ForwardLogic.pickImageFile(
                listOf("$other.jpg" to 50_000L, "th_$other" to 4_000L),
                md5A,
            )
        )
    }

    @Test
    fun pickImageFileIgnoresOtherMessagesInSameBucket() {
        // ⑤ 同桶别人消息（前 2 位相同、后 30 位不同）不得误选：
        //    别人的大图字节还更大，若 contains 口径写错（如只比前缀）就会被挑中。
        val otherMd5 = "5a" + "b".repeat(30)
        val files = listOf(
            "$otherMd5.jpg" to 999_999L, // 别人的大图（更大，但不能选）
            "th_$md5A" to 4_230L,        // 目标图唯一的文件 → 应选中它
        )
        assertEquals("th_$md5A", ForwardLogic.pickImageFile(files, md5A))
    }

    /**
     * ⛔ 变异验证（mutation test）：锁住第 1 档的「非 th_ 前缀」条件。
     *
     * 手工验证步骤：
     * 1. 临时删掉 `ForwardLogic.pickImageFile` 里第 1 档的 `!name.startsWith("th_") &&`；
     * 2. 跑本用例 —— `maxByOrNull` 会挑到字节最大的 `th_<md5>.jpg`(999999)，
     *    断言期望 `$md5A.jpg` 必然失败 ⇒ 证明本用例对该条件有判别力；
     * 3. 恢复条件后转绿。
     *
     * 真机上 th_ 文件普遍只有 3~8KB（取证 §4.1），这里故意造一个「超大 th_」放大判别力：
     * 只要选择逻辑把 th_ 也纳入大图候选，就会误选缩略图 ⇒ 对端收到模糊图（§4.2 事故现场）。
     */
    @Test
    fun pickImageFileMutationGuardRejectsThumbPrefix() {
        val files = listOf(
            "th_$md5A.jpg" to 999_999L, // 变异触发器：比真大图还大的 th_ 文件
            "$md5A.jpg" to 140_942L,    // 真·大图
        )
        assertEquals("$md5A.jpg", ForwardLogic.pickImageFile(files, md5A))
    }

    // ---------------------------------------------------------------- hexToken（URI → md5）

    @Test
    fun hexTokenExtractsMd5FromThumbnailUri() {
        // 【真机确定】imgPath 恒为 THUMBNAIL_DIRPATH://th_<32位md5>（取证文档 §1.3）
        assertEquals(md5A, MediaGuardLogic.hexToken("THUMBNAIL_DIRPATH://th_$md5A"))
    }

    @Test
    fun hexTokenNormalizesUppercase() {
        // 大写变体必须归一为小写（文件名是小写 md5，否则 contains 永不命中）
        assertEquals(md5A, MediaGuardLogic.hexToken("THUMBNAIL_DIRPATH://th_${md5A.uppercase()}"))
    }

    @Test
    fun hexTokenPrefersMd5SegmentOverLongerSha1() {
        // 串里混进 40 位 sha1 时仍取 32 位 md5 段（R34 复核：不能取「最长段」）
        val sha1 = "c".repeat(40)
        assertEquals(md5A, MediaGuardLogic.hexToken("THUMBNAIL_DIRPATH://th_${md5A}_$sha1"))
        assertEquals(md5A, MediaGuardLogic.hexToken("${sha1}_$md5A"))
    }

    // ---------------------------------------------------------------- planFor（解析失败安全 SKIP）

    @Test
    fun planForImageWithExistingFileResendsFile() {
        // 解析成功 → fileExists=true、原生不可用 ⇒ 降级重发文件（真实大图）
        val snap = ForwardLogic.Snapshot(
            kind = ForwardLogic.Kind.IMAGE,
            localPath = "/data/user/0/com.tencent.mm/MicroMsg/hash/image2/5a/9a/$md5A.jpg",
            fileExists = true,
        )
        assertEquals(
            ForwardLogic.Strategy.RESEND_FILE,
            ForwardLogic.planFor(snap, ForwardLogic.NativeState.UNKNOWN).strategy
        )
    }

    @Test
    fun planForImageWithoutExistingFileSkips() {
        // 解析失败 → localPath 仍是虚拟 URI、fileExists=false ⇒ SKIP（不空发、不崩宿主）
        val snap = ForwardLogic.Snapshot(
            kind = ForwardLogic.Kind.IMAGE,
            localPath = "THUMBNAIL_DIRPATH://th_$md5A",
            fileExists = false,
        )
        assertEquals(
            ForwardLogic.Strategy.SKIP,
            ForwardLogic.planFor(snap, ForwardLogic.NativeState.UNKNOWN).strategy
        )
    }
}
