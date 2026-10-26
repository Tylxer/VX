package com.lx.wxgroup.data

/**
 * 语音文件备份 / 恢复的**纯判据**（零 Android 依赖，有 JVM 单测）。
 *
 * ## 为什么需要这个类（真机实锤，2026-10-25 R21c）
 *
 * 撤回一条语音时微信做**三件事**，前两件拦住后播放**仍然失败**：
 *
 * 1. `UPDATE message SET type=... WHERE msgId=?` —— [com.lx.wxgroup.feature.AntiRevoke] 拦住 ✅
 * 2. `DELETE FROM voiceinfo WHERE FileName= ?` —— [com.lx.wxgroup.feature.VoiceGuard] 拦住 ✅
 * 3. **`File.delete()` 删掉磁盘上的音频文件** —— ❌ 不经过 SQLite，hook 完全看不到
 *
 * 第 3 步的实证（不是推测）：
 * - 全树递归扫 `voice2/` 的 20827 个目录，对照组 `msg_50095010242638d35faef1f106.amr`
 *   命中 1 个；被撤回那条 `4323041025263e6e1a32793103` **命中 0 个**。
 * - 被撤那条的桶目录 `voice2/01/70/` 存在但**空**，mtime = 撤回时刻 +4ms。
 *
 * ⇒ 只拦 SQL 不够。`voiceinfo` 行留着、文件没了，播放器照样打不开。
 *
 * ## 语音文件的路径规则（21 个真机样本验证，20 命中）
 *
 * ```
 * voice2/<md5(imgPath)[0..2]>/<md5(imgPath)[2..4]>/msg_<imgPath>.amr
 * ```
 *
 * 唯一未命中的那条 imgPath 是 27 位（多一位），属抄录异常；其余 20 条全部吻合，
 * 且「被撤回那条」用本规则算出的 `01/70` 正好指向那个 mtime 吻合的空目录。
 *
 * ⚠️ 分桶用 **md5**，不是 imgPath 自身的字符切分 —— 两者毫无关系。
 *    实测反例：`0514311025...`（自身以 `05` 开头）落在 `b0/f7`。
 *
 * ## 为什么必须算路径而不是全树扫
 *
 * 全树扫 `voice2/` 实测约 **1.5 秒**（40967 节点 / 20827 目录）。而备份必须发生在
 * 「撤回 handle 分派」到「`vj0.p` 真正删文件」之间 —— 隔着一次 `executor.submit`
 * 的线程切换，窗口只有几十毫秒。在主线程扫 1.5 秒会把微信卡住，且扫完文件早没了。
 * 所以只能 O(1) 算路径。
 */
object VoiceBackupLogic {

    /** 微信语音消息的 type */
    const val TYPE_VOICE = 34

    /**
     * 还原用的文件扩展名。
     *
     * 真机实测：文件全是 `msg_<imgPath>.amr`（微信给 silk 套了 amr 外壳）。
     * 若将来发现 `.silk`，[backupFileName] 保留原名，[restoreNeeds] 也不会误判。
     */
    const val VOICE_EXT = ".amr"

    /**
     * 该消息类型是否需要备份语音文件。
     *
     * 只有语音（[TYPE_VOICE]）有 `voice2/` 下的独立音频文件。文本 / 图片 / 视频
     * 走别的存储，不归本类管。
     */
    fun needsBackup(origType: Int): Boolean = origType == TYPE_VOICE

    /**
     * imgPath 的合法性判据。
     *
     * 真机样本全是 **26 位小写 hex**。长度或字符集不对就别去碰文件系统 ——
     * 脏数据拼出的路径可能指向别的东西，宁可不备份。
     */
    fun isValidImgPath(imgPath: String?): Boolean {
        if (imgPath == null || imgPath.length != 26) return false
        return imgPath.all { it in '0'..'9' || it in 'a'..'f' }
    }

    /**
     * 两级桶路径（相对 `voice2/` 的父目录），如 `01/70`。
     *
     * `md5` 必须是小写 hex（[md5Hex] 的返回值）。传入非法 imgPath 时返回 null。
     *
     * ⚠️ 不要把 `null` 当成「桶在根目录」—— 返回 null 的语义是「算不出来，别动文件」。
     */
    fun bucketOf(md5Hex: String?): String? {
        if (md5Hex == null || md5Hex.length < 4) return null
        val a = md5Hex.substring(0, 2)
        val b = md5Hex.substring(2, 4)
        if (!a.all { isHex(it) } || !b.all { isHex(it) }) return null
        return "$a/$b"
    }

    /** 语音文件名：`msg_<imgPath>.amr` */
    fun fileNameOf(imgPath: String): String = "msg_$imgPath$VOICE_EXT"

    /**
     * 从文件路径反解 imgPath —— 判据是「这条路径是不是微信的语音包」。
     *
     * 三个条件全满足才返回 imgPath，否则 null：
     * 1. 路径含 `/voice2/`（**带两侧分隔符**，防 `myvoice2`、`voice22` 误命中）
     * 2. 文件名 `msg_` 开头、`.amr` 结尾
     * 3. 中间那段是合法的 26 位 hex（[isValidImgPath]）
     *
     * 纯字符串操作，无 IO —— 给 `File.delete()` 这种高频 hook 用。
     * 典型输入：`/data/user/0/com.tencent.mm/MicroMsg/<hash>/voice2/01/70/msg_4323...103.amr`
     */
    fun imgPathOfVoiceFile(path: String?): String? {
        if (path == null) return null
        if (!path.contains("/voice2/")) return null
        val slash = path.lastIndexOf('/')
        if (slash < 0) return null
        val name = path.substring(slash + 1)
        if (!name.startsWith("msg_") || !name.endsWith(VOICE_EXT)) return null
        val img = name.substring(4, name.length - VOICE_EXT.length)
        return if (isValidImgPath(img)) img else null
    }

    /**
     * 备份文件名。
     *
     * 和原文件名**刻意保持一致**（`msg_<imgPath>.amr`）：还原时直接搬回去，
     * 不用再从 imgPath 反推原名；排查时也能一眼认出是哪条消息。
     */
    fun backupFileName(imgPath: String): String = fileNameOf(imgPath)

    /**
     * 是否该执行备份。
     *
     * 三个条件缺一不可：
     * 1. 是语音消息（[needsBackup]）
     * 2. imgPath 合法（[isValidImgPath]）
     * 3. 原文件此刻存在 —— 由调用方传入，`false` 就直接跳过（文件本来就没有，
     *    备份无从谈起；也避免为「未下载的语音」建空目录）
     */
    fun shouldBackup(origType: Int, imgPath: String?, sourceExists: Boolean): Boolean =
        needsBackup(origType) && isValidImgPath(imgPath) && sourceExists

    /**
     * 是否该执行还原。
     *
     * 只有在「备份存在」且「原位置缺失」时才搬回去 —— 原位置还在就绝不覆盖
     * （微信可能正在写这个文件，覆盖会写坏）。
     */
    fun shouldRestore(backupExists: Boolean, targetExists: Boolean): Boolean =
        backupExists && !targetExists

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f'

    /**
     * 小写 hex md5（纯 Kotlin 实现，不依赖 `java.security` —— 本类要能在 JVM 单测里
     * 直接跑，且判据零 Android 依赖）。
     *
     * 只用于算目录分桶，不是安全用途，所以不用考虑碰撞防护。
     */
    fun md5Hex(input: String): String {
        val msg = input.toByteArray(Charsets.UTF_8)
        val md5 = Md5()
        md5.update(msg, 0, msg.size)
        return md5.digestHex()
    }

    /**
     * 极简 MD5（RFC 1321）。
     *
     * 为什么自己写而不是用 `MessageDigest`：`MessageDigest.getInstance("MD5")` 在
     * JVM 单测里可用，但本类是纯判据类，**刻意不引入任何平台 API**（项目规则：
     * 判据类零 Android 依赖）。自己实现 ~60 行，配单测有已知答案向量校验。
     */
    private class Md5 {
        private var a0 = 0x67452301
        private var b0 = 0xefcdab89.toInt()
        private var c0 = 0x98badcfe.toInt()
        private var d0 = 0x10325476
        private val buf = ByteArray(64)
        private var bufLen = 0
        private var total = 0L

        fun update(data: ByteArray, off: Int, len: Int) {
            total += len
            var i = off
            val end = off + len
            while (i < end) {
                val take = minOf(64 - bufLen, end - i)
                System.arraycopy(data, i, buf, bufLen, take)
                bufLen += take
                i += take
                if (bufLen == 64) {
                    processBlock(buf, 0)
                    bufLen = 0
                }
            }
        }

        private fun pad() {
            val bitLen = total * 8
            // 补 0x80，再补 0，最后写 8 字节长度（小端），使总长 ≡ 0 (mod 64)。
            // ⚠️ 必须基于 `total % 64` 算，不能写 `(total + 1) % 64` ——
            //    后者在 total 是 64 的整数倍时算出 55，补完只有 63 字节，整块错位。
            //    （R21c 首版就踩了：'' / 'a'×64 这些整块样本全红。）
            // r < 56 ⇒ 补到 56；r >= 56 ⇒ 补到下一个块的 56（即补 120 - r）
            val r = (total % 64).toInt()
            val padLen = if (r < 56) 56 - r else 120 - r
            val padByte = ByteArray(padLen)
            padByte[0] = 0x80.toByte()
            update(padByte, 0, padByte.size)
            val lenBytes = ByteArray(8)
            var v = bitLen
            for (i in 0 until 8) {
                lenBytes[i] = (v and 0xff).toByte()
                v = v ushr 8
            }
            update(lenBytes, 0, 8)
        }

        fun digestHex(): String {
            pad()
            val out = StringBuilder(32)
            for (w in intArrayOf(a0, b0, c0, d0)) {
                for (i in 0 until 4) {
                    val b = (w ushr (8 * i)) and 0xff
                    out.append(HEX[b ushr 4]).append(HEX[b and 0x0f])
                }
            }
            return out.toString()
        }

        private fun processBlock(block: ByteArray, off: Int) {
            val m = IntArray(16)
            for (i in 0 until 16) {
                val j = off + i * 4
                m[i] = (block[j].toInt() and 0xff) or
                    ((block[j + 1].toInt() and 0xff) shl 8) or
                    ((block[j + 2].toInt() and 0xff) shl 16) or
                    ((block[j + 3].toInt() and 0xff) shl 24)
            }
            var a = a0
            var b = b0
            var c = c0
            var d = d0
            for (i in 0 until 64) {
                var f: Int
                var g: Int
                when (i) {
                    in 0..15 -> { f = (b and c) or (b.inv() and d); g = i }
                    in 16..31 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    in 32..47 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                val tmp = d
                d = c
                c = b
                val x = a + f + K[i] + m[g]
                b += Integer.rotateLeft(x, S[i])
                a = tmp
            }
            a0 += a
            b0 += b
            c0 += c
            d0 += d
        }

        companion object {
            private val HEX = "0123456789abcdef".toCharArray()

            /** 每轮的左移位数（RFC 1321 §3.4） */
            private val S = intArrayOf(
                7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
                5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
                4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
                6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
            )

            /** K[i] = floor(2^32 × |sin(i+1)|)，此处用已有实现的标准常量 */
            private val K = intArrayOf(
                0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db, 0xc1bdceee.toInt(),
                0xf57c0faf.toInt(), 0x4787c62a, 0xa8304613.toInt(), 0xfd469501.toInt(),
                0x698098d8, 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
                0x6b901122, 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821,
                0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51, 0xe9b6c7aa.toInt(),
                0xd62f105d.toInt(), 0x02441453, 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
                0x21e1cde6, 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed,
                0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9, 0x8d2a4c8a.toInt(),
                0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122, 0xfde5380c.toInt(),
                0xa4beea44.toInt(), 0x4bdecfa9, 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
                0x289b7ec6, 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05,
                0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8, 0xc4ac5665.toInt(),
                0xf4292244.toInt(), 0x432aff97, 0xab9423a7.toInt(), 0xfc93a039.toInt(),
                0x655b59c3, 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
                0x6fa87e4f, 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1,
                0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb, 0xeb86d391.toInt(),
            )
        }
    }
}
