package com.lx.wxgroup.feature

import java.security.MessageDigest
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
 *      <原图>                       ← 图片（待取证，见 MomentsExport.COPY_IMAGES）
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

    /**
     * 图片候选文件的匹配线索（**待取证**用）。
     *
     * 微信磁盘上的朋友圈缓存文件命名规则**还没取证**（是 url 的 md5？还是 snsId+序号？不知道）。
     * 这里把**所有可能用来对上的串**都吐出来，由 `MomentsExport.probe("img")`
     * 拿去跟真实文件名对账，对上哪种就把那条规则写进导出逻辑。
     */
    fun mediaKeys(snsId: Long, urls: List<String>): Set<String> {
        val out = LinkedHashSet<String>()
        out.add(snsId.toString())
        out.add(java.lang.Long.toUnsignedString(snsId))
        for (u in urls) {
            val tail = u.substringAfterLast('/').substringBefore('?')
            if (tail.isNotBlank()) out.add(tail)
            out.add(md5Hex(u))
        }
        return out
    }

    fun md5Hex(s: String): String = runCatching {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(((b.toInt() and 0xFF) + 0x100).toString(16).substring(1))
        sb.toString()
    }.getOrDefault("")

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
