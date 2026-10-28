package com.lx.wxgroup.data

/**
 * 「撤回图片/视频」文件保护判据的**纯逻辑**（零 Android / 零 Xposed → 可直接跑 JVM 单测）。
 *
 * 背景见 [com.lx.wxgroup.feature.VoiceFileGuard]：撤回图片时微信同样走
 * `File.delete(image2/<桶>/<md5>[hd])`，行还在、文件没了 ⇒ 点开必然失败。
 *
 * 本类只回答两件事：
 * 1. 一个 `imgPath` 能不能被当作保护 token（[hexToken]）
 * 2. 一个待删除的路径是否命中保护池（[mediaHit]）
 *
 * ## ⛔ 为什么 token 下限必须是 32 而不是 16（R31h 复核发现）
 *
 * 旧实现下限 16。`hexToken` 取的是**最长连续 hex 段**，而 hex 字符集包含 `0-9` ⇒
 * 一个 16 位以上的**纯数字串**（时间戳 `1793015486`、序号 `202510280001`）会被当成
 * 合法 token。图片文件名里普遍带时间戳/序号，`contains` 一命中就把**无关文件**也拦了
 * ⇒ 微信正常清理被堵、存储只增不减。这条比「漏保护一张图」严重得多。
 *
 * 真机实测（R31g）：图片 `imgPath` = `THUMBNAIL_DIRPATH://th_<32 位 md5>`，
 * 最长 hex 段恒为 32 位。所以下限放到 32 —— 既不会误收数字串，也仍兼容
 * `xxx://th_<md5>` / `/image2/ab/cd/<md5>` / 纯 `<md5>` 三种形态。
 */
object MediaGuardLogic {

    /** 媒体 token 最短长度：md5 定长 32。低于此值一律不收（见类注释） */
    const val MIN_TOKEN = 32

    /** 媒体文件所在目录片段（含两侧分隔符，防 `myimage2` 这类误命中） */
    private val MEDIA_DIRS = arrayOf("/image2/", "/video2/", "/video/")

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /**
     * 从任意形态的 imgPath 里取出最长的十六进制段（统一**小写**返回）。
     *
     * 返回 null 的情形：整串没有连续 hex 段，或最长段短于 [minLen]。
     * 大小写都认（真机是小写，防大写变体）—— 必须小写归一，否则
     * `protectedMedia` 的 key 与文件名小写 md5 对不上，静默漏保护。
     */
    fun hexToken(raw: String?, minLen: Int = MIN_TOKEN): String? {
        val s = raw?.trim().orEmpty()
        if (s.length < minLen) return null
        var best = ""
        var i = 0
        while (i < s.length) {
            if (isHex(s[i])) {
                var j = i
                while (j < s.length && isHex(s[j])) j++
                if (j - i > best.length) best = s.substring(i, j)
                i = j
            } else {
                i++
            }
        }
        return if (best.length >= minLen) best.lowercase() else null
    }

    /** 路径是否落在图片/视频目录下 */
    fun inMediaDir(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        for (d in MEDIA_DIRS) if (path.indexOf(d) >= 0) return true
        return false
    }

    /** 保护池里是否还有未过期的项（供调用方把 `hasMedia` 快判复位） */
    fun anyAlive(pool: Map<String, Long>, now: Long): Boolean {
        for (v in pool.values) if (v >= now) return true
        return false
    }

    /**
     * 该路径是不是某个「刚被撤回的图片/视频」的文件。
     *
     * 判据（全部满足）：① 路径在图片/视频目录下；② 文件名含某个未过期 token。
     * 命中返回该 token，否则 null。
     *
     * ⚠️ `contains` 而不是 `==`/`startsWith`：微信同一张图有多个文件
     * （`<md5>` 压缩图、`<md5>hd` 原图、`th_<md5>` 缩略图、`<md5>_tmp`），
     * 全部都要保护。而 md5 定长 32 位，`contains` 不会串到别的图上。
     */
    fun mediaHit(path: String?, pool: Map<String, Long>, now: Long): String? {
        if (!inMediaDir(path)) return null
        val p = path!!
        val slash = p.lastIndexOf('/')
        val name = if (slash >= 0) p.substring(slash + 1) else p
        for ((token, until) in pool) {
            if (until < now) continue
            if (name.contains(token)) return token
        }
        return null
    }
}
