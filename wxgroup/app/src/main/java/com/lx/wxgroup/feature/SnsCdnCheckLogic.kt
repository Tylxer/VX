package com.lx.wxgroup.feature

/**
 * 朋友圈图片 CDN 的纯判断逻辑（零 Android 依赖 → 可直接跑 JVM 单测）。
 *
 * 只做纯判断，不碰文件系统：字节由调用方读好传入，路径也由调用方给。
 * 本文件禁止 import 任何 android.* / xposed.*，否则 JVM 单测加载不了。
 */
object SnsCdnCheck {

    /** 文件头魔数 → 图片类型。返回小写短名：jpeg/png/gif/bmp/webp/heic/unknown。 */
    fun magicKind(head: ByteArray): String {
        // jpeg：FF D8 FF
        if (head.size >= 3 &&
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
        ) return "jpeg"

        // png：89 50 4E 47 0D 0A 1A 0A
        if (head.size >= 8 &&
            head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() &&
            head[4] == 0x0D.toByte() && head[5] == 0x0A.toByte() && head[6] == 0x1A.toByte() && head[7] == 0x0A.toByte()
        ) return "png"

        // gif：47 49 46 38（GIF8）
        if (head.size >= 4 &&
            head[0] == 0x47.toByte() && head[1] == 0x49.toByte() && head[2] == 0x46.toByte() && head[3] == 0x38.toByte()
        ) return "gif"

        // bmp：42 4D（BM）
        if (head.size >= 2 &&
            head[0] == 0x42.toByte() && head[1] == 0x4D.toByte()
        ) return "bmp"

        // webp：52 49 46 46（RIFF）且 offset 8 起 4 字节 = 57 45 42 50（WEBP）
        if (head.size >= 12 &&
            head[0] == 0x52.toByte() && head[1] == 0x49.toByte() && head[2] == 0x46.toByte() && head[3] == 0x46.toByte() &&
            head[8] == 0x57.toByte() && head[9] == 0x45.toByte() && head[10] == 0x42.toByte() && head[11] == 0x50.toByte()
        ) return "webp"

        // heic：offset 4 起 = 66 74 79 70（ftyp）且 offset 8 起 ∈ {heic, mif1, heix}
        if (head.size >= 12 &&
            head[4] == 0x66.toByte() && head[5] == 0x74.toByte() && head[6] == 0x79.toByte() && head[7] == 0x70.toByte() &&
            (
                (head[8] == 0x68.toByte() && head[9] == 0x65.toByte() && head[10] == 0x69.toByte() && head[11] == 0x63.toByte()) ||
                    (head[8] == 0x6D.toByte() && head[9] == 0x69.toByte() && head[10] == 0x66.toByte() && head[11] == 0x31.toByte()) ||
                    (head[8] == 0x68.toByte() && head[9] == 0x65.toByte() && head[10] == 0x69.toByte() && head[11] == 0x78.toByte())
            )
        ) return "heic"

        return "unknown"
    }

    /**
     * I-4 判据：这堆字节是不是明文图片（不是密文）。
     * 现口径 = magicKind(head) != "unknown"。
     */
    fun looksPlainImage(head: ByteArray): Boolean = magicKind(head) != "unknown"

    /**
     * I-2 判据：这条落盘路径是否有资格作为证据。
     * 返回 null = 有资格；非 null = 拒绝原因（短英文串，直接进日志）。
     *
     * ⚠️ **入参既可能是目录、也可能是完整文件路径**（2026-10-20 R11 复核 S-3）：
     * `SnsCdnImage.collect()` 传的是 `C2CDownloadResult.picCachePath`，那是**文件全路径**；
     * 而工具脚本 `tools/_r9_cdn.py` 传的是**目录**。两种都要能正确判。
     * ⇒ 第 2 条「父目录」必须**同时**接受「路径本身就是 cache 目录」和「路径的父目录是 cache」，
     * 否则对文件路径永远不成立，那道防线形同虚设。
     *
     * 拒绝顺序即优先级：
     *  1. 空串 / null
     *  2. cache 根目录（本身是 / 或其父目录是）—— 整个缓存目录，会把历史图算进来
     *  3. 自己落点前缀 .../cache/r9cdn、.../cache/r9i3
     *  4. 外部可读导出目录 .../files/WechatXposed/moments/_imgdl（我们写的，循环论证）
     *  5. 不含 /sns 也不含 /image
     */
    fun dirReject(path: String?): String? {
        if (path.isNullOrEmpty()) return "empty"

        val p = path.trimEnd('/')
        val parent = p.substringBeforeLast('/', "")

        if (p == CACHE_ROOT || parent == CACHE_ROOT) return "parent-cache"

        if (p.startsWith("$CACHE_ROOT/r9cdn") || p.startsWith("$CACHE_ROOT/r9i3")) {
            return "self-export"
        }

        if (p.contains("WechatXposed/moments/_imgdl")) {
            return "self-export"
        }

        if (!p.contains("/sns") && !p.contains("/image")) {
            return "not-sns-dir"
        }

        return null
    }

    /** 微信缓存根目录（§4.5：它下面躺满历史缓存，绝不能整目录当证据） */
    private const val CACHE_ROOT = "/data/data/com.tencent.mm/cache"
}
