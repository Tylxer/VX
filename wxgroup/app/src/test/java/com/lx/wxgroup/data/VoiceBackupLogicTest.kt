package com.lx.wxgroup.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VoiceBackupLogic] 的判据回归测试。
 *
 * ## 样本来源（全真机，不编）
 *
 * 数组 `SAMPLES` 的 21 条 `(imgPath, 桶1, 桶2)` 来自 8.0.48 真机
 * `label sub=find` 递归扫 `voice2/` 拿到的**真实路径**（2026-10-25 R21c）。
 *
 * ## 为什么 MD5 必须做「已知答案向量」校验
 *
 * [VoiceBackupLogic.md5Hex] 是手写的 RFC 1321 实现 —— 手写哈希最容易在
 * 「填充长度 / 长度字段端序 / 常量表抄错」上翻车，而且**翻车时不会报错**，
 * 只会让所有桶算错 ⇒ 备份全部落到错误目录 ⇒ 功能静默失效。
 * 所以除了业务样本，还钉了 RFC 1321 附录 A.5 的官方测试向量。
 */
class VoiceBackupLogicTest {

    /**
     * 真机样本：imgPath → 实际所在的两级桶。
     *
     * ⚠️ 唯一未收录的是 `542132102426d122a6a55b101`（27 位）—— 它算出来对不上，
     * 而 26 位规则能覆盖全部正常样本，所以把它当「非法输入」样本单独测。
     */
    private val SAMPLES = listOf(
        Triple("50095010242638d35faef1f106", "01", "65"),
        Triple("3619341025268a9e016c7d4104", "96", "ce"),
        Triple("05143110252654b6378e583103", "b0", "f7"),
        Triple("59142610252654b6378236f102", "9d", "73"),
        Triple("25142510252654b6378b53d102", "25", "b2"),
        Triple("39142410252654b63780102100", "43", "64"),
        Triple("11141910252654b6378ff0b104", "d6", "2b"),
        Triple("10141910252654b6378fcfc102", "0c", "a5"),
        Triple("10141910252654b6378fcab105", "8f", "5b"),
        Triple("09141910252654b6378f918100", "fe", "c1"),
        Triple("581030102526d122a6a1103105", "30", "05"),
        Triple("581030102526cf1a78610f6106", "b6", "9c"),
        Triple("581030102526cf1a78610bc104", "e9", "b7"),
        Triple("581030102526cf1a78610a9106", "71", "12"),
        Triple("581030102526d122a6a1047106", "dd", "c8"),
        Triple("55022810252654b6378b833106", "aa", "be"),
        Triple("56022410252654b63781504104", "be", "ef"),
        Triple("252134102426d122a6a1a2d102", "86", "64"),
        Triple("462131102426d122a6aad10105", "36", "6d"),
        Triple("342130102426d122a6a911f105", "57", "c6"),
        // 被撤回的那条 —— 实证它的桶是 01/70（目录 mtime 与撤回时刻吻合且为空）
        Triple("4323041025263e6e1a32793103", "01", "70"),
    )

    // ------------------------------------------------------------ MD5 自身

    @Test
    fun md5MatchesRfc1321Vectors() {
        // RFC 1321 附录 A.5 的官方测试向量
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", VoiceBackupLogic.md5Hex(""))
        assertEquals("0cc175b9c0f1b6a831c399e269772661", VoiceBackupLogic.md5Hex("a"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", VoiceBackupLogic.md5Hex("abc"))
        assertEquals(
            "f96b697d7cb7938d525a2f31aaf161d0",
            VoiceBackupLogic.md5Hex("message digest")
        )
        assertEquals(
            "c3fcd3d76192e4007dfb496cca67e13b",
            VoiceBackupLogic.md5Hex("abcdefghijklmnopqrstuvwxyz")
        )
        // 长输入专打「跨 64 字节分块 + 填充边界」
        assertEquals(
            "d174ab98d277d9f5a5611c2c9f419d9f",
            VoiceBackupLogic.md5Hex(
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            )
        )
        assertEquals(
            "57edf4a22be3c955ac49da2e2107b67a",
            VoiceBackupLogic.md5Hex(
                "1234567890123456789012345678901234567890" +
                    "1234567890123456789012345678901234567890"
            )
        )
    }

    @Test
    fun md5KnownImgPathVector() {
        // 真机验过的具体值：与 Python hashlib.md5 逐字一致
        assertEquals(
            "0170c47132944e9ed1cfd6a881c3df44",
            VoiceBackupLogic.md5Hex("4323041025263e6e1a32793103")
        )
    }

    @Test
    fun md5HandlesExactly64ByteBoundary() {
        // 64 字节整块：填充要新开一块，最容易写错的一档
        assertEquals("014842d480b571495a4a0363793f7367", VoiceBackupLogic.md5Hex("a".repeat(64)))
        // 55 字节：长度字段刚好还能塞进当前块
        assertEquals("ef1772b6dff9a122358552954ad0df65", VoiceBackupLogic.md5Hex("a".repeat(55)))
        // 56 字节：必须新开一块放长度字段（分界另一侧）
        assertEquals("3b0c8ac703f828b04c6c197006d17218", VoiceBackupLogic.md5Hex("a".repeat(56)))
        // 63 / 65 字节：夹着块边界的两档
        assertEquals("b06521f39153d618550606be297466d5", VoiceBackupLogic.md5Hex("a".repeat(63)))
        assertEquals("c743a45e0d2e6a95cb859adae0248435", VoiceBackupLogic.md5Hex("a".repeat(65)))
    }

    // ------------------------------------------------------ 分桶（真机样本）

    @Test
    fun allRealSamplesMapToActualBucket() {
        var bad = 0
        for ((img, b1, b2) in SAMPLES) {
            val md5 = VoiceBackupLogic.md5Hex(img)
            val bucket = VoiceBackupLogic.bucketOf(md5)
            val expect = "$b1/$b2"
            if (bucket != expect) {
                bad++
                println("MISMATCH imgPath=$img md5=$md5 算出=$bucket 实际=$expect")
            }
            assertEquals("imgPath=$img 的桶", expect, bucket)
        }
        assertEquals("真机样本必须全中", 0, bad)
    }

    @Test
    fun bucketIsNotDerivedFromImgPathItself() {
        // ⛔ 反例护栏：分桶用 md5，不是 imgPath 自身字符切分。
        //    `0514311025...` 以 "05" 开头，但真机落在 b0/f7。
        val img = "05143110252654b6378e583103"
        assertEquals("05", img.substring(0, 2))
        val bucket = VoiceBackupLogic.bucketOf(VoiceBackupLogic.md5Hex(img))
        assertEquals("b0/f7", bucket)
        assertFalse("不能等于 imgPath 自身前缀", bucket == "05/14")
    }

    @Test
    fun bucketRejectsBadMd5() {
        assertNull(VoiceBackupLogic.bucketOf(null))
        assertNull(VoiceBackupLogic.bucketOf(""))
        assertNull(VoiceBackupLogic.bucketOf("abc"))       // 不足 4 位
        assertNull(VoiceBackupLogic.bucketOf("ZZZZ"))      // 非 hex
    }

    // ---------------------------------------------------------- 名字与判据

    @Test
    fun fileNameMatchesRealDeviceNaming() {
        // 真机实际文件名：msg_50095010242638d35faef1f106.amr
        assertEquals(
            "msg_50095010242638d35faef1f106.amr",
            VoiceBackupLogic.fileNameOf("50095010242638d35faef1f106")
        )
        // 备份名与原文件同名，还原时直接搬回去
        assertEquals(
            VoiceBackupLogic.fileNameOf("3619341025268a9e016c7d4104"),
            VoiceBackupLogic.backupFileName("3619341025268a9e016c7d4104")
        )
    }

    @Test
    fun onlyVoiceTypeNeedsBackup() {
        assertTrue(VoiceBackupLogic.needsBackup(34))
        // 文本 / 图片 / 视频 / 撤回形态都不归本类管
        assertFalse(VoiceBackupLogic.needsBackup(1))
        assertFalse(VoiceBackupLogic.needsBackup(3))
        assertFalse(VoiceBackupLogic.needsBackup(43))
        assertFalse(VoiceBackupLogic.needsBackup(10000))
        assertFalse(VoiceBackupLogic.needsBackup(268445456))
    }

    @Test
    fun imgPathValidation() {
        assertTrue(VoiceBackupLogic.isValidImgPath("4323041025263e6e1a32793103"))
        assertFalse("null 不行", VoiceBackupLogic.isValidImgPath(null))
        assertFalse("空串不行", VoiceBackupLogic.isValidImgPath(""))
        assertFalse("25 位不行", VoiceBackupLogic.isValidImgPath("4323041025263e6e1a3279310"))
        // ⛔ 真机上出现过 27 位样本，必须判非法
        assertFalse("27 位不行", VoiceBackupLogic.isValidImgPath("542132102426d122a6a55b101"))
        assertFalse("大写不行", VoiceBackupLogic.isValidImgPath("4323041025263E6E1A32793103"))
        assertFalse("含非 hex 不行", VoiceBackupLogic.isValidImgPath("4323041025263e6e1a3279310z"))
    }

    @Test
    fun shouldBackupRequiresAllThree() {
        val good = "4323041025263e6e1a32793103"
        assertTrue(VoiceBackupLogic.shouldBackup(34, good, true))
        // 不是语音
        assertFalse(VoiceBackupLogic.shouldBackup(1, good, true))
        // imgPath 不合法
        assertFalse(VoiceBackupLogic.shouldBackup(34, null, true))
        assertFalse(VoiceBackupLogic.shouldBackup(34, "bad", true))
        // 原文件不存在（未下载的语音）—— 没东西可备
        assertFalse(VoiceBackupLogic.shouldBackup(34, good, false))
    }

    @Test
    fun shouldRestoreOnlyWhenTargetMissing() {
        // 备份在 + 原位置缺 ⇒ 还原
        assertTrue(VoiceBackupLogic.shouldRestore(backupExists = true, targetExists = false))
        // 备份在 + 原位置也在 ⇒ **绝不覆盖**（微信可能正在写）
        assertFalse(VoiceBackupLogic.shouldRestore(backupExists = true, targetExists = true))
        // 没备份 ⇒ 无事可做
        assertFalse(VoiceBackupLogic.shouldRestore(backupExists = false, targetExists = false))
        assertFalse(VoiceBackupLogic.shouldRestore(backupExists = false, targetExists = true))
    }

    // ------------------------------------------- 路径反解（File.delete hook 的闸门）

    /**
     * ⛔ 这组是本轮**风险最高**的判据：`File.delete()` 是全进程高频调用
     * （微信到处删缓存）。误判一次就会拦住正常的文件清理，存储只增不减。
     * 所以「不该拦」的样本比「该拦」的更重要。
     */
    @Test
    fun imgPathParsedFromRealDevicePath() {
        // 真机上真真实实存在过的路径
        assertEquals(
            "50095010242638d35faef1f106",
            VoiceBackupLogic.imgPathOfVoiceFile(
                "/data/user/0/com.tencent.mm/MicroMsg/89476c7fc82a9f977bc8a901d68b7d37" +
                    "/voice2/01/65/msg_50095010242638d35faef1f106.amr"
            )
        )
        assertEquals(
            "4323041025263e6e1a32793103",
            VoiceBackupLogic.imgPathOfVoiceFile(
                "/data/user/0/com.tencent.mm/MicroMsg/89476c7fc82a9f977bc8a901d68b7d37" +
                    "/voice2/01/70/msg_4323041025263e6e1a32793103.amr"
            )
        )
    }

    @Test
    fun imgPathRejectsNonVoiceFiles() {
        // ⛔ 以下每一条都必须返回 null —— 返回非 null 就是误拦微信的正常清理
        val cases = listOf(
            null,
            "",
            // 不在 voice2 下（微信删缓存的重灾区：tmp / image2 / cache）
            "/data/user/0/com.tencent.mm/cache/msg_50095010242638d35faef1f106.amr",
            "/data/user/0/com.tencent.mm/MicroMsg/x/image2/01/65/msg_50095010242638d35faef1f106.amr",
            // 相似但不同的目录名：⛔ `/voice2x/`、`/myvoice2/` 不能被 `contains("voice2")` 蒙中
            "/data/user/0/com.tencent.mm/MicroMsg/x/voice2x/01/65/msg_50095010242638d35faef1f106.amr",
            "/data/user/0/com.tencent.mm/MicroMsg/x/myvoice2/01/65/msg_50095010242638d35faef1f106.amr",
            // 在 voice2 下但不是语音包名
            "/data/user/0/com.tencent.mm/MicroMsg/x/voice2/01/65/index.tmp",
            "/data/user/0/com.tencent.mm/MicroMsg/x/voice2/01/65/msg_50095010242638d35faef1f106.silk",
            "/data/user/0/com.tencent.mm/MicroMsg/x/voice2/01/65/50095010242638d35faef1f106.amr",
            // 名字对但 hex 不合法（25 位 / 27 位 / 含 g）
            "/x/voice2/01/65/msg_50095010242638d35faef1f10.amr",
            "/x/voice2/01/65/msg_50095010242638d35faef1f1066.amr",
            "/x/voice2/01/65/msg_50095010242638d35faef1f10g.amr",
            // 只有文件名没有路径（无 /voice2/ 分隔）
            "msg_50095010242638d35faef1f106.amr"
        )
        for (c in cases) {
            assertNull("不该命中：$c", VoiceBackupLogic.imgPathOfVoiceFile(c))
        }
    }

    @Test
    fun voiceSegNeedsBothSlashes() {
        // `/voice2/` 必须带两侧斜杠：这是防 `/voice22/` `/myvoice2/` 的关键
        assertNull(VoiceBackupLogic.imgPathOfVoiceFile("/a/voice22/msg_4323041025263e6e1a32793103.amr"))
        assertNull(VoiceBackupLogic.imgPathOfVoiceFile("/a/nvoice2/msg_4323041025263e6e1a32793103.amr"))
        // 正确形态
        assertEquals(
            "4323041025263e6e1a32793103",
            VoiceBackupLogic.imgPathOfVoiceFile("/a/voice2/msg_4323041025263e6e1a32793103.amr")
        )
    }

    @Test
    fun imgPathRejectsTmpSuffix() {
        // 复核 Q2 补：`.amr.tmp` 是微信写文件时的临时名（先写 .tmp 再 rename）。
        // ⛔ 若把 `.amr.tmp` 当成语音文件放行，撤回收尾时那个 rename 前的清理会被误拦，
        //    而且算出来的 imgPath 也是错的 —— 严格 endsWith(".amr") 才能挡住。
        val cases = listOf(
            "/x/voice2/01/65/msg_50095010242638d35faef1f106.amr.tmp",
            "/x/voice2/01/65/msg_50095010242638d35faef1f106.tmp",
            "/x/voice2/01/65/msg_50095010242638d35faef1f106.amr.bak",
            "/x/voice2/01/65/msg_50095010242638d35faef1f106.am",
            "/x/voice2/01/65/msg_50095010242638d35faef1f106.AMR" // 后缀大小写敏感
        )
        for (c in cases) {
            assertNull("不该命中：$c", VoiceBackupLogic.imgPathOfVoiceFile(c))
        }
    }

    @Test
    fun bucketSegmentsDoNotAffectParsing() {
        // 复核 Q2 补：桶目录本身长什么样**不影响**解析 —— 解析只看「最后一段文件名」。
        // 理由：桶是 md5 前 4 位的小写 hex，但万一微信某版本改用大写/别的口径，
        // 我们仍应能认出文件（拦删除靠的是文件名里的 imgPath，不是桶）。
        val expected = "4323041025263e6e1a32793103"
        val paths = listOf(
            "/x/voice2/01/70/msg_$expected.amr",   // 常规小写
            "/x/voice2/01/70/msg_$expected.amr",   // 同上（占位，保持列表对齐）
            "/x/voice2/AB/CD/msg_$expected.amr",   // 大写桶
            "/x/voice2/ab/cd/msg_$expected.amr",   // 小写桶
            "/x/voice2/msg_$expected.amr",         // 桶段缺失
            "/x/voice2/a/b/c/d/msg_$expected.amr"  // 深层嵌套
        )
        for (p in paths) {
            assertEquals("解析应只看文件名：$p", expected, VoiceBackupLogic.imgPathOfVoiceFile(p))
        }
    }
}
