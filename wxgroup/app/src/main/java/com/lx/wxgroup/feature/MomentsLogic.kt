package com.lx.wxgroup.feature

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 需求 6.4「朋友圈」——定时刷新 / 自动加载 / 自动导出的**纯逻辑**（零 Android 依赖）。
 *
 * 抽出来的理由跟 [SnsTagLogic] 一样：目录名格式、名单命中、下次刷新时刻这些判据
 * 写错了要**上真机跑一轮**（开微信 → 进朋友圈 → 等定时器 → adb pull 看目录）才能发现，
 * 一次就是十几分钟。放在这里可以先跑 JVM 单测锁住。
 *
 * ⚠️ 本文件**禁止 import 任何 android.* / xposed.* / 项目内其它类**，否则单测加载不了。
 *
 * ## 目录结构（需求定死）
 * ```
 * <导出路径>/昵称(wxid)/yyyy-MM-dd-HHmm/
 *      -3447438253133393222.txt    ← text 文本
 *      <原图>                       ← 图片（命名规则已取证 R15，见 MomentsExport.COPY_IMAGES）
 * ```
 * 昵称在前是为了**人读得懂**，括号里带 wxid 是为了**唯一**（昵称会重名、会改）。
 */
object MomentsLogic {

    // ------------------------------------------------------------------ 常量

    /** 日期文件夹格式（需求定死：`yyyy-MM-dd-HHmm`，例 `2026-06-10-0516`） */
    const val DAY_PATTERN = "yyyy-MM-dd-HHmm"

    const val TEXT_EXT = ".txt"

    /** 文本文件名前缀：纯 snsId，便于回查归档库（与 `sns_archive.sns_id` 同一位模式） */
    const val UNKNOWN_USER = "unknown"

    /** 导出前的可用空间下限：低于它就判定「空间不足」弹窗，不写一字节 */
    const val MIN_FREE_BYTES = 32L * 1024 * 1024

    /**
     * 刷新间隔的合法范围（与 `Prefs.ALL` 里 `moments.refresh_interval_h` 的 min/max 一致）。
     * 这里再钳一次是因为 Kv 读出来的是字符串，可能被人手改成 0 或 999。
     */
    const val MIN_INTERVAL_H = 1
    const val MAX_INTERVAL_H = 24

    /** 到期但环境不满足（没充电 / 没 WiFi）时，隔多久再判一次 */
    const val RETRY_MS = 10 * 60_000L

    // ------------------------------------------------------------------ 自动加载判据

    /**
     * 「距列表末尾还有几条」之内算贴底（onBind 的 [pos] 与 [size] 都来自
     * `WxRecyclerAdapter.onBindViewHolder` + `getData()`，错位也不影响这个判定：
     * 错位一两位仍在窗口内）。
     */
    const val LOAD_NEAR_END = 3

    /** 同一轮浏览里最多自动触发多少次「拉更多」（防失控滚到底） */
    const val LOAD_MAX_PER_SESSION = 30

    /** 两次自动触发之间的最小间隔（毫秒） */
    const val LOAD_MIN_INTERVAL_MS = 1_500L

    /** 导出两轮之间的最小间隔（毫秒） */
    const val EXPORT_MIN_INTERVAL_MS = 10 * 60_000L

    private val DAY_FMT = DateTimeFormatter.ofPattern(DAY_PATTERN, Locale.US)
    private val FULL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    // ------------------------------------------------------------------ 目录 / 文件名

    /**
     * `yyyy-MM-dd-HHmm`。
     *
     * ⚠️ 传 [zone] 而不是默认 systemDefault 是为了单测可复现（CI 机器的时区未必是 Asia/Shanghai）。
     */
    fun dayDir(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        runCatching {
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).format(DAY_FMT)
        }.getOrDefault("0000-00-00-0000")

    /** 全量时间（写进 text 文本里给人看） */
    fun fullTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        runCatching {
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).format(FULL_FMT)
        }.getOrDefault("未知时间")

    /**
     * 目录名清洗：去掉文件系统不允许 / 跨系统不友好的字符。
     *
     * ⚠️ 微信昵称里真的会出现 `/` `:` `*` 这类字符（实测昵称带 `/` 会让 mkdirs 静默失败，
     * 表现为「导出开关开着但目录里什么都没有」），必须逐个替换而不是只去空格。
     */
    private val ILLEGAL = Regex("""[\\/:*?"<>|\r\n\t]""")
    private val SPACES = Regex("""\s+""")

    fun safeName(raw: String?, max: Int = 32): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        val cleaned = ILLEGAL.replace(s, "_").let { SPACES.replace(it, " ") }
            .trim().trim('.', ' ')
        // 全是下划线/空白（例如 "///"）→ 视为没有名字，让 userDir 落到 UNKNOWN_USER
        if (cleaned.isEmpty() || cleaned.all { it == '_' || it.isWhitespace() }) return ""
        return if (cleaned.length > max) cleaned.substring(0, max).trim('.', ' ') else cleaned
    }

    /** `昵称(wxid)`；缺哪个就用哪个，都没有才落到 [UNKNOWN_USER] */
    fun userDir(nick: String?, wxid: String?): String {
        val n = safeName(nick)
        val w = safeName(wxid, 64)
        return when {
            n.isNotEmpty() && w.isNotEmpty() -> "$n($w)"
            w.isNotEmpty() -> w
            n.isNotEmpty() -> n
            else -> UNKNOWN_USER
        }
    }

    /** 拼路径：自动吃掉多余 `/`，空段跳过。⚠️ 首段的前导 `/` 必须保留（否则变相对路径） */
    fun join(vararg parts: String?): String {
        val sb = StringBuilder()
        for (p in parts) {
            val s = p?.trim().orEmpty()
            if (s.isEmpty()) continue
            val seg = s.trim('/')
            if (seg.isEmpty()) continue
            if (sb.isEmpty()) {
                if (s.startsWith("/")) sb.append('/')
            } else if (!sb.endsWith("/")) sb.append('/')
            sb.append(seg)
        }
        return sb.toString()
    }

    /** 一条动态的完整导出目录：`<root>/昵称(wxid)/yyyy-MM-dd-HHmm` */
    fun exportDir(
        root: String,
        nick: String?,
        wxid: String?,
        millis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = join(root, userDir(nick, wxid), dayDir(millis, zone))

    fun textFileName(snsId: Long): String = "$snsId$TEXT_EXT"

    /**
     * 从已导出的文件名反解 snsId（用于「哪些导过了」的去重）。
     *
     * ⚠️ 必须吃下负号：真机的 snsId 是 `-3447438253133393222` 这种有符号 64 位值。
     */
    fun parseExportedId(fileName: String): Long? {
        if (!fileName.endsWith(TEXT_EXT)) return null
        val body = fileName.substring(0, fileName.length - TEXT_EXT.length)
        if (body.isEmpty() || body.length > 20) return null
        var i = 0
        if (body[0] == '-') {
            i = 1
            if (body.length == 1) return null
        }
        while (i < body.length) {
            val c = body[i]
            if (c < '0' || c > '9') return null
            i++
        }
        return runCatching { body.toLong() }.getOrNull()
    }

    /**
     * `SnsInfo.createTime` 是**秒**，但别盲乘 1000 —— 万一哪天存的是毫秒会错成 1970 年，
     * 目录名全落进 `1970-01-xx`，而且没有任何报错。按量级判一次。
     */
    fun millisOf(createTime: Long): Long = when {
        createTime <= 0L -> 0L
        createTime < 100_000_000_000L -> createTime * 1000L
        else -> createTime
    }

    // ------------------------------------------------------------------ BLOB → 文本

    /**
     * 从 `SnsInfo.content` BLOB 里抽**正文**。
     *
     * content 是 protobuf 序列化的 TimeLineObject，正文是里面一段连续的可读串，
     * 前后都是二进制。做法：按「可打印 ASCII / UTF-8 多字节」切出连续段，取最长的一段。
     *
     * ⚠️ 这是**尽力解析**，不是 protobuf 解包（解包要引入 proto 定义，且字段随版本变）。
     * 解不出来时返回空串 —— 导出仍然会落一个只有头信息的 txt，正文为空，
     * 但**内容本身一个字节没丢**（原始 BLOB 已经在 `sns_archive.content` 里）。
     */
    fun extractText(blob: ByteArray?, minLen: Int = 2): String {
        if (blob == null || blob.isEmpty()) return ""
        var best = ""
        var bestIsUrl = true
        val run = ArrayList<Byte>()
        fun flush() {
            if (run.isEmpty()) return
            val s = runCatching { String(run.toByteArray(), Charsets.UTF_8) }.getOrNull()?.trim()
            run.clear()
            if (s == null || s.length < minLen) return
            if (!hasContentChar(s)) return
            val isUrl = s.startsWith("http", ignoreCase = true)
            // URL 是媒体线索、不是人看的正文：**非 URL 一律优先**（不论长度），
            // 找不到非 URL 时才会退到 URL；同为非 URL 才比长度。
            val better = if (bestIsUrl != isUrl) !isUrl
            else s.length > best.length
            if (best.isEmpty() || better) {
                best = s
                bestIsUrl = isUrl
            }
        }
        for (b in blob) {
            val v = b.toInt() and 0xFF
            val keep = (v >= 0x20 && v < 0x7F) || v >= 0x80
            if (keep) run.add(b) else flush()
        }
        flush()
        return best
    }

    /** 至少含一个「有意义的字符」，否则会把纯标点/纯符号段当成正文 */
    private fun hasContentChar(s: String): Boolean {
        for (c in s) {
            if (c.isLetterOrDigit()) return true
            if (c.code in 0x4E00..0x9FFF) return true // CJK
        }
        return false
    }

    /** BLOB 里的 http(s) 链接（媒体线索：原图/视频的 CDN 地址） */
    private val URL_RE = Regex("""https?://[^\s\u0000-\u001f"'<>()\\]+""")

    fun extractUrls(blob: ByteArray?, limit: Int = 20): List<String> {
        if (blob == null || blob.isEmpty()) return emptyList()
        val s = runCatching { String(blob, Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (m in URL_RE.findAll(s)) {
            out.add(m.value.trimEnd(',', ';', '.'))
            if (out.size >= limit) break
        }
        return out.toList()
    }

    // ⛔ 已删于 R20（A8 收敛）：mediaKeys() / md5Hex() / MD5_RE / md5sOf() / imageCandidates() /
    //    pickExisting() —— 旧 md5 路线已三次真机证伪
    //    （R15 分桶 0.68% / R16 全盘 find 零结果 / R19 文件名是 20 位数字串不含 md5）。
    //    正解见 docs/_r19_a4_probe.md。证据见 docs/_r20_a8_del.md。
    //
    //    ---- 迁移保留：原 MD5_RE KDoc 里仍有价值的取证结论（勿再踩） ----
    //    · BLOB 里确实混着 32 位小写 hex（2026-10-18 实测 `6ac4b5b0d7424bc7ba…` 等），
    //      **但不要**认为「它们就是原图在磁盘缓存里的键」—— 已被否证。
    //    · 把 4 个这类 md5 拿到 `/sdcard` + `/data/user/0/com.tencent.mm` 全盘
    //      `find -name '<md5>*'`，**一个都搜不到**（R16）。
    //    · `ls -lt image/` 里最近写入的是两天前的 `reader_-255_<md5>.jpg` —— 那是公众号
    //      文章配图，不是朋友圈。
    //    · R15 真机确证缓存落在 `…/com.tencent.mm/cache/<hash32>/sns/<hex1>/<hex2>/`，
    //      但文件名是 `snsb_`/`snsu_`/`snst_` + mediaId 派生（R19 实测是 20 位数字串），
    //      **不含 md5** ⇒ 「md5 对不上」是**命名规则**问题，仍是必然。
    //    · 现行正解是索引直扫：[mediaIdsFromContent] → [snsNameCandidates] → [matchSnsFileName]。
    // ------------------------------------------------------------------ 图片原图留存

    /**
     * 头像 / 公众号图标不是原图，必须从图片导出里排除。
     *
     * 真机证据：`op=moments sub=img` 的 urls 里第一项常常是
     * `https://wx.qlogo.cn/finderhead/ver_1/...` —— 那是发动态人的头像，
     * 当成动态原图导出会把一堆头像混进用户目录。
     */
    fun isAvatarUrl(u: String): Boolean {
        val l = u.lowercase()
        return l.contains("qlogo.cn") ||
            l.contains("finderhead") ||
            l.contains("/avatar") ||
            l.contains("avatar/")
    }

    /** 原图的扩展名：微信缓存多数不带扩展名，缺省按 jpg 存 */
    fun extOf(path: String): String {
        val name = path.substringAfterLast('/').substringBefore('?')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return "jpg"
        val ext = name.substring(dot + 1).lowercase()
        // 只认图片扩展名，避免把 `md5.xxx` 这种怪名字的尾巴当扩展名
        return if (ext in IMAGE_EXTS) ext else "jpg"
    }

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")

    /** 导出图片的文件名：`snsId_序号.ext`（同一动态多张图靠序号区分） */
    fun imageFileName(snsId: Long, index: Int, ext: String): String {
        val e = ext.lowercase().takeIf { it in IMAGE_EXTS } ?: "jpg"
        return "img_${snsId}_${index}.$e"
    }

    // ------------------------------------------------- SNS 磁盘缓存文件名匹配（R15）

    /**
     * 朋友圈图片缓存的三级文件名前缀（`Lbf3/t0` 的 `getSnsBigName` / `getSnsUserName` /
     * `getSnsThumbName` 反汇编确证，见方案 Q3）。
     *
     * 真机样本：`snsb_<id>` 1.1~1.5MB（原图）、`snsu_<id>` 6~42KB（压缩）、
     * `snst_<id>` 20~70KB（缩略）。
     */
    private const val SNS_PREFIX_BIG = "snsb_"
    private const val SNS_PREFIX_MID = "snsu_"
    private const val SNS_PREFIX_THUMB = "snst_"

    /**
     * 必须排除的缓存文件名前缀（方案 Q2b「已确认的补充事实」3/4）：
     * - `snstblur_` 模糊占位图（语义不是内容）
     * - `wxid_` 头像缓存（不是朋友圈图片）
     * - `sight_` 视频（本轮只导图片，视频留后续）
     */
    private val SNS_EXCLUDE_PREFIXES = listOf("snstblur_", "wxid_", "sight_")

    /**
     * 复刻 `Lbf3/t0#s(String)`（fliterPre）—— 文件名里那段数字的真正来源。
     *
     * 反汇编语义：**从下标 0 开始**，跳过所有**前导的**连续 `'0'`，返回从第一个非 `'0'`
     * 处到结尾的子串。
     * 例：`"15038644089917813396"` → 首位非 `'0'`，**原样返回** `"15038644089917813396"`；
     * `"0015038873180398883448"` → 跳前导 0 → `"15038873180398883448"`。
     *
     * ⚠️ 真机 mediaId 长度实测有 20/22/23/24/25/26/27/28 共 8 种，**不要截断到定长**。
     *
     * 证据：DEX 反汇编 `Lbf3/t0;->s` 的 `[19] const/4 v3,#0`（2026-10-24 复核，
     * 见 docs/_r15_sns_cache_plan.md「重大订正」节）。
     */
    fun fliterPre(s: String): String {
        if (s.isEmpty()) return ""
        var i = 0
        while (i < s.length && s[i] == '0') i++
        return if (i >= s.length) "" else s.substring(i)
    }

    /**
     * 候选**文件名**列表（不含目录），按优先级排序：原图 > 压缩 > 缩略。
     *
     * 每个前缀配两种键（方案 Q2b）：`fliterPre(mediaId)` 优先，`mediaId` 原值兜底
     * （防 fliterPre 判断错）。`fliterPre` 为空时只留空键 ⇒ 三个前缀各一项（共 3 项），
     * 这种候选必然匹配不到真机文件（真机 `<前缀>` 后面一定是数字，被
     * [matchSnsFileName] 的边界规则拒掉），所以不会误命中。
     *
     * 最多 6 项：`[snsb_<pre>, snsb_<mid>, snsu_<pre>, snsu_<mid>, snst_<pre>, snst_<mid>]`
     */
    fun snsNameCandidates(mediaId: String): List<String> {
        if (mediaId.isEmpty()) return emptyList()
        val pre = fliterPre(mediaId)
        val keys = LinkedHashSet<String>(2)
        keys.add(pre)
        if (pre.isNotEmpty()) keys.add(mediaId)
        val prefixes = arrayOf(SNS_PREFIX_BIG, SNS_PREFIX_MID, SNS_PREFIX_THUMB)
        val out = ArrayList<String>(prefixes.size * keys.size)
        for (p in prefixes) for (k in keys) out.add(p + k)
        return out
    }

    /**
     * 文件名白名单：只认 `snsb_` / `snsu_` / `snst_`，并排除
     * `snstblur_` / `wxid_` / `sight_`。
     *
     * 这是**外层过滤**（建索引时用），不混进 [fliterPre] / [snsNameCandidates] /
     * [matchSnsFileName] 的字符串匹配里，三者保持纯字符串语义。
     */
    fun isSnsMediaName(name: String): Boolean {
        if (name.isEmpty()) return false
        for (x in SNS_EXCLUDE_PREFIXES) if (name.startsWith(x)) return false
        return name.startsWith(SNS_PREFIX_BIG) ||
            name.startsWith(SNS_PREFIX_MID) ||
            name.startsWith(SNS_PREFIX_THUMB)
    }

    /**
     * 在**文件名索引**里找 [cand] 对应的真实文件名（方案 Q2b 的 `lookup`）。
     *
     * 两级：
     * 1. 精确命中 → 直接返回
     * 2. 前缀兜底（处理 `&enc<n>` / `_V2` 这类尾巴），但要求 `cand` 之后的下一个字符
     *    **不是数字** —— 否则 `snst_150` 会把 `snst_1503...` 误判成同一条动态的图。
     *
     * 多个前缀候选时返回**字典序最小**的那个，保证跨设备/跨次运行结果确定（单测可断言）。
     *
     * @return 命中的文件名（不是路径；路径由调用方的 Map 查），没有则 null
     */
    fun matchSnsFileName(index: Set<String>, cand: String): String? {
        if (index.isEmpty() || cand.isEmpty()) return null
        if (index.contains(cand)) return cand
        var best: String? = null
        for (n in index) {
            // 必须比 cand 长：等长且不等的情况上面精确命中已排除
            if (n.length <= cand.length) continue
            if (!n.startsWith(cand)) continue
            val next = n[cand.length]
            if (next in '0'..'9') continue
            if (best == null || n < best) best = n
        }
        return best
    }

    // --------------------------------------------- protobuf 通用线格式（R15 · Q1 mediaId）

    /**
     * mediaId 链路的三个字段号（方案 Q1，DEX 已闭环）。
     *
     * ⚠️ **只用字段号，不用类名**：`TimeLineObject.ContentObj` / `i40.h` / `vm3.d` 这些
     * 混淆名每次微信升级都可能变（`q54/vm3` 换个包就没了），但**线上协议的字段号是稳定的**。
     * 所以这里按 protobuf 通用线格式手走字节，⛔ 绝不反射调 `vm3.parseFrom`。
     *
     * ## ⛔ R19 真机实测推翻了 R15 的字段号定案（2026-10-25）
     * R15 定的是 `content --6--> ContentObj --8--> 媒体对象 --1--> mediaId`。
     * 但真机 `op=snsg sub=mpath`（递归扫 protobuf、定位「≥18 位纯数字 ASCII 串」叶子）实测：
     * ```
     * MIDPATH snsId=... p=1(20),8>5>1(20),8>5>29(19),8>5>32(19)
     * ```
     * ⇒ 真正的 mediaId 路径是 **8 → 5 → 1**，不是 6 → 8 → 1。
     * 决定性交叉验证（同一批 content，两条路径各解一次去比对设备缓存文件名）：
     * - 顶层字段 1 的 20 位串：在 41 个真实缓存文件名里 **0 命中**；
     * - `8>5>1` 的 20 位串：**命中**（`15039549883662348966` 等 4 个精确命中
     *   `snst_<同名>.<...>` 缓存文件）。
     * 且 `ids=0` 的旧现象与「字段 6 内没有字段 8」完全吻合（`in6` 实测无 8）。
     *
     * **保留旧路径作为回退**：若新路径解空，再试一次 `6 → 8 → 1`。
     * 理由：不同版本/不同形态的动态（归档表 vs 当前表）结构可能有差异，
     * 回退的成本是几次字节扫描，收益是抗版本漂移。
     *
     * ```
     * content(BLOB) --8--> 容器 --5(repeated)--> 媒体对象 --1--> mediaId   ← 主路径（R19 实测）
     * content(BLOB) --6--> ContentObj --8(repeated)--> 媒体对象 --1--> mediaId  ← 回退（R15 旧定案）
     * ```
     */
    private const val PROTO_FIELD_CONTENT_OBJ = 8
    private const val PROTO_FIELD_MEDIA_LIST = 5
    private const val PROTO_FIELD_MEDIA_ID = 1
    private const val LEGACY_FIELD_CONTENT_OBJ = 6
    private const val LEGACY_FIELD_MEDIA_LIST = 8

    /**
     * 读一个 protobuf varint 的 **public 转发**（原实现仍为 private）。
     *
     * 为什么要有它：`MomentsExport` 的 `snsprobe` 探针要枚举 content 的**顶层字段号**
     * 来判断 `PROTO_FIELD_*` 是否定错，需要直接扫线格式，而不是取某个已知字段。
     * 只暴露读取能力，**不暴露任何字段语义常量**，业务路径不受影响。
     */
    fun readVarintPublic(b: ByteArray, start: Int): Pair<Long, Int>? = readVarint(b, start)

    /** 读一个 protobuf varint；返回 (值, 下一个下标)；越界 / 超 64 位返回 null */
    private fun readVarint(b: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var i = start
        while (i < b.size && shift < 64) {
            val x = b[i].toInt() and 0xFF
            value = value or ((x and 0x7F).toLong() shl shift)
            i++
            if (x and 0x80 == 0) return value to i
            shift += 7
        }
        return null
    }

    /**
     * 走 protobuf **通用线格式**，收集指定字段号（wiretype=2）出现的所有 length-delimited 段。
     *
     * 四种 wiretype 都按规范跳字节：0=varint、1=fixed64(+8)、2=length-delimited、
     * 5=fixed32(+4)；3/4=group 不支持（现网朋友圈不用，遇到就整段作废）。
     *
     * @return 各段原始字节；**结构断裂**（字段号 0 / varint 越界 / 长度越界 / 跳字节出界）
     *         时返回**空列表**，不抛异常、不返回半截结果（半截结果比空更危险：会让人以为解析成功）。
     *
     * ⚠️ 这不是「解析微信格式」：它只按 protobuf 规范跳字节，不解释任何字段语义。
     */
    fun protoSegments(b: ByteArray, field: Int): List<ByteArray> {
        if (field <= 0 || b.isEmpty()) return emptyList()
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < b.size) {
            val t = readVarint(b, i) ?: return emptyList()
            var pos = t.second
            val tag = t.first
            if (tag == 0L) return emptyList() // 字段号 0 非法
            val f = (tag ushr 3).toInt()
            when (tag and 0x07L) {
                0L -> pos = (readVarint(b, pos) ?: return emptyList()).second
                1L -> pos += 8                                    // fixed64
                2L -> {
                    val lv = readVarint(b, pos) ?: return emptyList()
                    pos = lv.second
                    val len = lv.first
                    if (len < 0 || len > (b.size - pos).toLong()) return emptyList()
                    val end = pos + len.toInt()
                    if (f == field) out.add(b.copyOfRange(pos, end))
                    pos = end
                }
                5L -> pos += 4                                    // fixed32
                else -> return emptyList()                        // 3/4 = group，不支持
            }
            if (pos > b.size) return emptyList()
            i = pos
        }
        return out
    }

    /**
     * 取指定字段号（wiretype=2）的**第一个** UTF-8 String。
     *
     * @return 没有该字段 / 段为空 / 结构断裂 ⇒ null。**不抛异常。**
     */
    fun protoString(b: ByteArray, field: Int): String? {
        val seg = protoSegments(b, field).firstOrNull() ?: return null
        if (seg.isEmpty()) return null
        return runCatching { String(seg, Charsets.UTF_8) }.getOrNull()
    }

    /**
     * 一步到位：`content` → 容器(8) → 媒体列表(5) → 每个元素的 d(1)。
     *
     * ⛔ **字段号于 2026-10-25（R19）由真机取证改过**：原为 `6 → 8 → 1`，
     * 实测 mediaId 实际在 `8 → 5 → 1`（证据见 [PROTO_FIELD_CONTENT_OBJ] 的 KDoc）。
     * 新路径解空时回退到旧的 `6 → 8 → 1`，抗版本漂移。
     *
     * **契约（V1 钉死）**：返回的是 `vm3.d` **原值**，一个字符都不加工
     * （不砍前缀、不截断、不加 `snsb_`）。下游 [snsNameCandidates] 会同时放
     * `fliterPre(原值)` 和 `原值` 两个候选键，因此「只砍一次」和「已砍过」两种形态都能命中。
     *
     * 顺序 = 字节顺序（= 动态里的图片顺序），slot 序号靠它。
     *
     * `content == null` / 空 / 结构断裂 / 两条路径都取不到 ⇒
     * 返回 `emptyList()`，**绝不抛异常**（上层有四态计数兜底）。
     */
    fun mediaIdsFromContent(content: ByteArray?): List<String> {
        if (content == null || content.isEmpty()) return emptyList()
        // 主路径（R19 真机实测）
        val main = idsByPath(content, PROTO_FIELD_CONTENT_OBJ, PROTO_FIELD_MEDIA_LIST)
        if (main.isNotEmpty()) return main
        // 回退（R15 旧定案）
        return idsByPath(content, LEGACY_FIELD_CONTENT_OBJ, LEGACY_FIELD_MEDIA_LIST)
    }

    /** 按「content → [objField] → [listField] → 1」取 mediaId；任一层取不到返回空表 */
    private fun idsByPath(content: ByteArray, objField: Int, listField: Int): List<String> {
        val obj = protoSegments(content, objField).firstOrNull() ?: return emptyList()
        if (obj.isEmpty()) return emptyList()
        val mediaList = protoSegments(obj, listField)
        if (mediaList.isEmpty()) return emptyList()
        val out = ArrayList<String>(mediaList.size)
        for (m in mediaList) {
            val id = protoString(m, PROTO_FIELD_MEDIA_ID) ?: continue
            if (id.isEmpty()) continue
            out.add(id)
        }
        return out
    }

    // ---------------------------------------------------------------- 原图下载（url 路线）

    /**
     * 媒体种类。**下载后以真实 Content-Type 为准覆盖这里的预判**，
     * 这里只决定「要不要下载」和「兜底扩展名」。
     */
    enum class MediaKind { IMAGE, VIDEO, UNKNOWN }

    /**
     * url 分类判据（**全部来自 2026-10-18 真机 `op=moments sub=imgurl` 的实际输出**，不是猜）：
     *
     * - `http://wxapp.tc.qq.com/<n>/<n>/stodownload?encfilekey=…&token=…` —— 视频 / 大图。
     *   ✅ **实测可下载**：设备上 curl 返回 HTTP 200、`video/mp4`、2826203 字节；
     *   ✅ 端到端已通：`op=moments sub=imgdl` 真机落盘 `vid_-3412979636140764498_0.mp4`。
     * - `http://shmmsns.qpic.cn/mmsns/<46位>/0` —— 朋友圈**图片**的 url 形态。
     *   ❌ **实测下不来（结论已定死，2026-10-18 23:4x）**：8 个样本（含当场新抓的）**全部 400**，
     *   加 `Referer: http://weixin.qq.com/`、加 iPhone UA、加 `?tp=jpg` **均无效**。
     *    ⇒ 图片 CDN 需要微信侧的鉴权，裸 HTTP 取不到。**别再调这些参数了**。
     * - 噪音：`mp.weixin.qq.com/s?…`（公众号文章正文链接，实测 302）、
     *   `dldir1.qq.com/…/internet_news_media_auth_icon_…`（腾讯资源图标，实测 404）
     *
     * ## 对 6.4「原图留存」的结论
     * 目前只有**视频 / stodownload 类**能存；**图片存不了**。图片要落地只剩两条路
     * （都需再取证，本轮没做）：① hook 微信自己的图片下载器拿带鉴权的请求；
     * ② 触发微信把图缓存到本地（用户点开大图）后走 md5 快速路径。
     */
    private val IMG_MARKS = listOf("mmsns", "qpic.cn")
    private val VIDEO_MARKS = listOf("stodownload")
    private val NOISE_MARKS = listOf("dldir1.qq.com", "mp.weixin.qq.com/s", "res.wx.qq.com")

    /** 正文里的文章链接 / 资源图标 —— 都不是动态原图 */
    fun isNoiseUrl(u: String): Boolean {
        val l = u.lowercase()
        return NOISE_MARKS.any { l.contains(it) }
    }

    /** 预判种类；头像素材与噪音都归 UNKNOWN（= 默认不下载） */
    fun kindOfUrl(u: String): MediaKind {
        if (isAvatarUrl(u) || isNoiseUrl(u)) return MediaKind.UNKNOWN
        val l = u.lowercase()
        if (VIDEO_MARKS.any { l.contains(it) }) return MediaKind.VIDEO
        if (IMG_MARKS.any { l.contains(it) }) return MediaKind.IMAGE
        return MediaKind.UNKNOWN
    }

    /**
     * 值得下载的媒体 url（已排头像 + 噪音，去重保序）。
     *
     * @param includeUnknown 认不出的域名也下载。**默认 false** —— 宁可漏，
     *                       也不能把一堆网页 HTML 当成图片存进用户目录。
     */
    fun mediaUrls(urls: List<String>, includeUnknown: Boolean = false): List<String> {
        val out = LinkedHashSet<String>()
        for (u in urls) {
            val k = kindOfUrl(u)
            if (k == MediaKind.UNKNOWN && !includeUnknown) continue
            out.add(u)
        }
        return out.toList()
    }

    private val VIDEO_EXTS = setOf("mp4", "mov", "m4v")
    private val CT_EXT = mapOf(
        "image/jpeg" to "jpg", "image/jpg" to "jpg", "image/png" to "png",
        "image/gif" to "gif", "image/webp" to "webp", "image/heic" to "heic",
        "video/mp4" to "mp4", "video/quicktime" to "mov", "video/x-m4v" to "m4v",
    )

    /** 从 Content-Type 取扩展名；认不出返回 `""`（调用方按 [MediaKind] 兜底） */
    fun extFromContentType(ct: String?): String {
        val v = ct?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return CT_EXT[v].orEmpty()
    }

    fun extOfKind(kind: MediaKind): String = if (kind == MediaKind.VIDEO) "mp4" else "jpg"

    /**
     * 落盘文件名。前缀按**最终扩展名**定（不按预判的 kind）——
     * 真机实测过 `stodownload` 返回的是 `video/mp4`，若按 kind=VIDEO 猜还好，
     * 但反过来「预判图片、实际视频」时叫 `img_xxx.mp4` 会误导，所以统一看 ext。
     */
    fun fileNameFor(snsId: Long, index: Int, kind: MediaKind, contentType: String?): String {
        val ext = extFromContentType(contentType).ifEmpty { extOfKind(kind) }
        val prefix = if (ext in VIDEO_EXTS) "vid" else "img"
        return "${prefix}_${snsId}_${index}.$ext"
    }

    /**
     * 图片缓存根目录候选（**2026-10-18 设备实测过目录结构，但命中率未知**）。
     *
     * 实测到的微信朋友圈相关目录（`adb shell ls` 直接看，不是猜）：
     * - `/sdcard/Android/data/com.tencent.mm/MicroMsg/<userHash>/sns/temp` —— **存在但是空**
     * - `/data/user/0/com.tencent.mm/MicroMsg/<userHash>/sns_aff` —— 存在
     * - `/sdcard/Android/data/com.tencent.mm/cache/<hash>/sns` —— 存在
     *
     * ⚠️ **重大结论（2026-10-18 第二轮设备实测，已定死）**：本轮拿真机上取到的 4 个 md5
     * （`6ac4b5b0…` / `9068346614…` / `59cdb4cf…` / `12fde739…`）
     * 在 `/sdcard` + 整个 `/data/user/0/com.tencent.mm` 下**全盘 find 都搜不到**；
     * 且 `image/` 目录最近写入的文件是 **2 天前**的 `reader_-255_<md5>.jpg`
     * （公众号文章配图，与朋友圈无关），近两天 `/sdcard` 下**没有任何图片写入**。
     *
     * ⇒ 判定：**朋友圈的图从未下载到本机**（微信按需拉取，没主动点开大图就只在内存里画缩略）。
     * 所以旧 md5 路线（已于 R20 删除的 `pickExisting`）全返回 null **是必然结果，不是 bug**。
     *
     * ## 对实现方向的结论
     * 「按 md5 找本地缓存」这条路**在未缓存时不成立**，继续调目录规则是白费。
     * 原图留存要改成**从 content 里的完整 CDN url 直接下载**
     * （真机日志已吐出可用地址：
     * `http://wxapp.tc.qq.com/251/20302/stodownload?enc…`、
     * `http://shmmsns.qpic.cn/mmsns/<hash>…`）。
     * 该方案需要联网 + 微信自己的 CDN 鉴权参数，**下一轮单独取证**，本轮不实现。
     *
     * 留着本函数的原因：若某天用户确实点开过大图（本地有缓存），走 md5 命中比下载快，
     * 作为**快速路径**保留；但它不能再被当作主路径。
     *
     * @param externalRoot `/sdcard/Android/data/com.tencent.mm`（可空）
     * @param userHash      MicroMsg 下的 32 位用户哈希目录名（可空）
     */
    fun imageRootCandidates(externalRoot: String?, userHash: String?): List<String> {
        val out = LinkedHashSet<String>()
        val ext = externalRoot?.trim()?.trimEnd('/').orEmpty()
        if (ext.isEmpty()) return emptyList()
        val uh = userHash?.trim()?.trimEnd('/').orEmpty()
        if (uh.isNotEmpty()) {
            // 实测存在的两个位置（`sns` 下有 `temp` 子目录，`temp` 本轮实测为空）
            out.add("$ext/MicroMsg/$uh/sns")
            out.add("$ext/MicroMsg/$uh/sns/temp")
        }
        // 实测存在的另一处：`cache/<hash>/sns`。hash 未知 → 由调用方在 cache 下自扫，
        // 这里只给一个不带 hash 的根，让 exists() 至少能证明「根不存在」
        out.add("$ext/cache")
        return out.toList()
    }

    // ------------------------------------------------------------------ text 文本渲染

    /** 渲染一条动态的 text 文本所需的最小字段集（[com.lx.wxgroup.data.GroupDb.SnsRow] 的投影） */
    data class Item(
        val snsId: Long,
        val wxid: String?,
        val nick: String?,
        val createTime: Long,
        val type: Int,
        val sourceType: Int,
        val localFlag: Int,
        val content: ByteArray?,
    )

    fun renderText(item: Item, zone: ZoneId = ZoneId.systemDefault()): String {
        val sb = StringBuilder()
        sb.append("snsId=").append(item.snsId).append('\n')
        val w = item.wxid?.trim().orEmpty()
        val n = item.nick?.trim().orEmpty()
        sb.append("作者=").append(
            when {
                n.isNotEmpty() && w.isNotEmpty() -> "$n($w)"
                w.isNotEmpty() -> w
                n.isNotEmpty() -> n
                else -> UNKNOWN_USER
            }
        ).append('\n')
        val ms = millisOf(item.createTime)
        sb.append("时间=").append(if (ms == 0L) "未知" else fullTime(ms, zone)).append('\n')
        sb.append("类型=").append(item.type)
            .append(" 来源=").append(item.sourceType)
            .append(" localFlag=").append(item.localFlag).append('\n')
        sb.append("---- 正文 ----\n")
        val text = extractText(item.content)
        sb.append(if (text.isEmpty()) "(正文未能解析，原始 BLOB 已存入 wxgroup.db 的 sns_archive.content)" else text)
            .append('\n')
        val urls = extractUrls(item.content)
        if (urls.isNotEmpty()) {
            sb.append("---- 媒体 ----\n")
            for (u in urls) sb.append(u).append('\n')
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ 导出名单

    /**
     * 导出名单命中判定。
     *
     * - **空名单 = 全部命中**（不设名单就导出所有已归档的；开关本身默认关，出不了事）
     * - 名单元素可能是 wxid，也可能是「昵称(wxid)」这种人读形态（K3 选择器存什么以接线为准，
     *   两种都认，避免接线换个存法就**静默全不导出**）
     * - wxid 与昵称都拿不到时返回 false：宁可漏，也不把「不知道是谁」的动态导出到错误目录
     */
    fun listHit(list: List<String>, wxid: String?, nick: String?): Boolean {
        if (list.isEmpty()) return true
        val w = wxid?.trim().orEmpty()
        val n = nick?.trim().orEmpty()
        if (w.isEmpty() && n.isEmpty()) return false
        for (raw in list) {
            val e = raw.trim()
            if (e.isEmpty()) continue
            if (w.isNotEmpty() && e.equals(w, ignoreCase = true)) return true
            if (n.isNotEmpty() && e.equals(n, ignoreCase = true)) return true
            val i = e.indexOf('(')
            if (i >= 0) {
                val namePart = e.substring(0, i).trim()
                val idPart = e.substring(i + 1).trimEnd(')').trim()
                if (w.isNotEmpty() && idPart.equals(w, ignoreCase = true)) return true
                if (n.isNotEmpty() && namePart.equals(n, ignoreCase = true)) return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------ 调度

    fun clampHours(h: Int): Int = h.coerceIn(MIN_INTERVAL_H, MAX_INTERVAL_H)

    /** 探针 `n` 参数的钳位上限：广播 `--ei n` 外人可控，img / snsg 对账只需几条 */
    const val MAX_PROBE_N = 50

    /**
     * 探针 `n` 的合法范围。直进 SQL LIMIT 会被 999999 这种值一次性把归档行
     * （含 content/attr_buf/post_buf 三个 BLOB）全捞出来，真机 OOM。
     */
    fun clampProbeN(n: Int): Int = n.coerceIn(1, MAX_PROBE_N)

    /**
     * 环境是否满足「充电中 / WiFi 下」。
     *
     * 需求写的是「充电中 / WiFi 下」，这里按**满足任一即算**处理：要求两个同时满足的话，
     * 白天不充电的用户永远刷不了，功能等于没有。
     */
    fun envOk(
        charging: Boolean,
        wifi: Boolean,
        requireCharging: Boolean = true,
        requireWifi: Boolean = true,
    ): Boolean {
        if (!requireCharging && !requireWifi) return true
        var ok = false
        if (requireCharging && charging) ok = true
        if (requireWifi && wifi) ok = true
        return ok
    }

    /** 下次到点时刻（不考虑环境，仅看间隔）；`lastAt=0` 表示从没刷过 */
    fun dueAt(lastAt: Long, intervalH: Int): Long =
        if (lastAt <= 0L) 0L else lastAt + clampHours(intervalH) * 3_600_000L

    /**
     * 下次判定的时刻。
     *
     * - 从没刷过（`lastAt<=0`）→ 立刻（返回 [now]）
     * - 没到点 → 返回到点时刻 [dueAt]
     * - 已到点 + 环境满足 → 返回 [now]（**现在就刷**）
     * - 已到点 + 环境不满足 → 返回 `now + retryMs`（过会儿再看，环境可能变好）
     *
     * 调用方只需要 `if (now >= nextAt) 干活`，判定逻辑全在这里，单测能全覆盖。
     */
    fun nextRefreshAt(
        lastAt: Long,
        intervalH: Int,
        charging: Boolean,
        wifi: Boolean,
        now: Long,
        requireCharging: Boolean = true,
        requireWifi: Boolean = true,
        retryMs: Long = RETRY_MS,
    ): Long {
        if (lastAt <= 0L) return now
        val due = dueAt(lastAt, intervalH)
        if (now < due) return due
        return if (envOk(charging, wifi, requireCharging, requireWifi)) now else now + retryMs
    }

    // ------------------------------------------------------------------ 自动加载 / 导出节奏

    /**
     * 这一次绑定要不要顺手「拉更多」。
     *
     * 判据拆出来单测的理由：贴底窗口、节流、每轮上限三个条件**任一写错**，
     * 表现分别是「永远不自动加载」「每帧都 fling 把列表甩到底」「一夜之间把全部历史拉完」——
     * 三种都只能上真机才发现，而这里 16 秒就能锁住。
     *
     * @param pos onBindViewHolder 的 position
     * @param size `adapter.getData().size`
     * @param lastTriggerAt 上次自动触发的时刻（elapsedRealtime；0 = 没触发过）
     * @param sessionCount 本轮浏览已触发次数
     */
    fun loadMoreDue(
        pos: Int,
        size: Int,
        lastTriggerAt: Long,
        now: Long,
        sessionCount: Int,
        maxPerSession: Int = LOAD_MAX_PER_SESSION,
        minIntervalMs: Long = LOAD_MIN_INTERVAL_MS,
    ): Boolean {
        if (size <= 0 || pos < 0) return false
        // 贴底：最后 LOAD_NEAR_END 条之内
        if (pos < size - LOAD_NEAR_END) return false
        if (sessionCount >= maxPerSession) return false
        if (lastTriggerAt > 0L && now - lastTriggerAt < minIntervalMs) return false
        return true
    }

    /**
     * 导出是否到点。
     *
     * `lastRunAt<=0`（含从没导出过）→ 立刻跑：用户打开自动导出开关后，
     * 第一轮 tick 就该把已归档的存量导出去，而不是干等一个间隔。
     */
    fun exportDue(lastRunAt: Long, now: Long, minIntervalMs: Long = EXPORT_MIN_INTERVAL_MS): Boolean =
        lastRunAt <= 0L || now - lastRunAt >= minIntervalMs
}
