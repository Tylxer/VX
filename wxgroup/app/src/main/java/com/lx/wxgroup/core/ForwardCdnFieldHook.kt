package com.lx.wxgroup.core

import android.os.SystemClock
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * R86c 第4轮最终裁决落码（Pro）：转发图片清晰度。
 *
 * 唯一 hook 点 = 复用**已挂载**的 `ok0/m1.e(tj/k)Z` before（`install()` 已挂，不新增第二个 hook）。
 * `param.args[0]` = 上传 task `tj/k`。本 hook 只在命中"本次转发目标"时把 task.field_fullpath
 * 指向唯一的 45522B / 内容 MD5 前8 = cff4f5a5 的 `.jpg_hevc` 字节（清晰），其余逐字节不动。
 *
 * ## 隔离（R2=(a)，⛔ 绝不用 ThreadLocal）
 * FileSender 在 send 时按**目标会话 wxid** 登记 `pendingMap[wxid]`（跨线程 ConcurrentHashMap +
 * 单调时钟 TTL，条件 remove(key, value) 原子消费）；m1.e before 从 `param.args[0].field_talker`
 * 取同一 key 命中。FileSender 侧 `wxg-forward`、m1.e 侧 `[D]#worker` 分属不同线程，ThreadLocal 跨不过去。
 * 修正轮：成功后按 `(talker, 原图 basename)` **有界重登记**（≤[MAX_HITS] 次），保证同转发 host
 * 重传帧的"最后一次上传"也写 hevc；basename 不一致（并发不同图）fail-closed，不写不枚举。
 *
 * ## 字节来源（m1.e before 现场枚举）
 * 从 `task.field_fullpath` 正则取 `/MicroMsg/<32hex>/` 数据根，**优先原图同目录**找 `*.jpg_hevc`
 * （命中即停，不阻塞 [D]#worker），同目录 0 命中才走 `root+"image2/"` 两级分桶并设扫描上限。
 * 先做模糊态前置门禁（原文件 9554B/0ef205c1），再取目标态 45522B/cff4f5a5；
 * **命中数必须 ==1**，0 或 >1 一律不写（fail-closed）。
 * ⛔ 禁用 xml `.msg.img.$md5`（ca47f83e）作参照——它是源图 md5，与目标内容 md5 不同，用它必 0 命中。
 *
 * ## 只读红线
 * 除 inject 单字段写 `task.field_fullpath` 外，不写 fileId/aesKey/originPath、不写 r0/ImgInfo2/DB/param；
 * 探针链只读。开关 off 时回调首行 return（零反射/零 IO/零写）。
 */
object ForwardCdnFieldHook {

    private const val TAG = "FORWARD CDN_FIELD"

    /**
     * 交接 TTL（单调时钟 elapsedRealtime）：超过该时长未命中 m1.e 视为失效。
     * 第4轮原为 2000L，独立复核实证真机 queued→m1.e 迟到 6345ms/10071ms ⇒ 必超时零生效（BLOCKER）。
     * 修正为 15000L（> 观测最大 10.071s×1.5，同为同转发 host 重传两帧间隔 10.858s 留余量）；
     * 不用 30/60s 以免残留窗口过长。每次成功命中后续期，覆盖同转发的重传帧。
     */
    private const val TTL_MS = 15000L

    /**
     * 有界重试：同一 (talker, 原图 basename) 最多允许 MAX_HITS 次成功注入。
     * step1b 实证同转发两次 m1.e（间隔 20.9s、其间无新 queued），第二次为新 task 仍读 9554B 模糊字节。
     * 首帧消费成功后按同 basename 有界重登记，保证"最后一次上传"也写 hevc。
     * =3 ⇒ 首帧 + 2 次重试；超过上限即清除并停写（fail-closed），杜绝永久残留。
     */
    private const val MAX_HITS = 3

    /** 全树兜底扫描上限（文件数）；同目录优先命中时不触发。上界耗时见 impl note。 */
    private const val MAX_SCAN_FILES = 1024

    /** 目标态判据：唯一 45522B / 内容 MD5 前 8 = cff4f5a5（step1b 实证，硬编码，禁 xml md5 参照）。 */
    private const val REF_MD5_PRE = "cff4f5a5"
    private const val TARGET_LEN = 45522L

    /** 模糊态前置门禁（只作门禁，不作目标参照）。 */
    private const val BLUR_LEN = 9554L
    private const val BLUR_MD5_PRE = "0ef205c1"

    @Volatile
    private var installed = false

    /** 宿主 ClassLoader，由 [install] 保存，供 [arm] 只读探针找类。 */
    @Volatile
    private var loader: ClassLoader? = null

    /** 独立开关（新 Key，默认 false；冷启按 Key 复位，广播运行时翻转不持久化）。 */
    @Volatile
    var hevcEnabled = false

    /** R86c Q4b 只读探针开关（默认关；不参与 pending/inject/TTL 闸门）。 */
    @Volatile
    var probeEnabled = false

    /**
     * 交接数据：ts = 最近一次成功注入的单调时钟（每次命中续期）；
     * basename = 首帧原图文件名（null 表示尚未注入过，仅 markTarget 登记）；
     * hits = 已成功注入次数（有界 [MAX_HITS]）。隔离 key 在 map 的 key 上，不在 value 里。
     */
    private data class Pending(val ts: Long, val basename: String?, val hits: Int)

    /** 跨线程目标标记：key = 目标会话 wxid（对应 task.field_talker）。 */
    private val pendingMap = ConcurrentHashMap<String, Pending>()

    /**
     * hook `ok0/m1.e(tj/k)Z`（全局图片上传下发点）。单钩 guard + runCatching 全包；
     * 精确签名命中则 hookMethod，找不到 `tj.k` 或签名漂移则 hookAllMethods("e") 兜底 + 回调打 msig。
     */
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true
        // 冷启读一次默认值；运行时广播翻转不持久化（off ⇒ 回调首行 return）。
        hevcEnabled = runCatching { Kv.getBool(Keys.FORWARD_TASK_FULLPATH_HEVC) }.getOrDefault(false)
        val cl = lpparam.classLoader
        runCatching {
            loader = cl
            val m1cls = XposedHelpers.findClass("ok0.m1", cl)
            val kcls = runCatching { XposedHelpers.findClass("tj.k", cl) }.getOrNull()
            val exact = if (kcls != null) m1cls.declaredMethods.firstOrNull { m ->
                m.name == "e" &&
                    m.parameterTypes.map { it.name } == listOf("tj.k") &&
                    m.returnType == java.lang.Boolean.TYPE
            } else null
            val fallback = exact == null

            val cb = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!hevcEnabled) return
                    runCatching {
                        if (fallback) LogX.i("$TAG m1e msig=${sigOf(param)}")
                        inject(param)
                    }.onFailure { LogX.w("$TAG inject err: ${it.javaClass.simpleName}") }
                }
                // after 不写任何东西：本 hook 只在 before 补字段。
            }
            if (exact != null) {
                XposedBridge.hookMethod(exact, cb)
                LogX.i("$TAG hook ok0.m1.e exact")
            } else {
                val set = XposedBridge.hookAllMethods(m1cls, "e", cb)
                if (set.isEmpty()) error("ok0.m1.e not found (all overloads)")
                LogX.i("$TAG hook ok0.m1.e fallbackAll count=${set.size}")
            }
        }.onFailure { LogX.w("$TAG install failed: ${it.javaClass.simpleName}") }
    }

    /**
     * FileSender.send() 在 arm 之前调：只登记目标 key（wxid = **目标会话**，对应 task.field_talker）。
     * off 时首行 return（零反射/零 IO/零写）；不解析路径、不读 r0/ImgInfo2、不调宿主业务方法。
     */
    fun markTarget(wxid: String) {
        if (!hevcEnabled) return
        if (wxid.isBlank()) return
        // 单调时钟起算；basename=null 表示首帧未到，hits=0。
        pendingMap[wxid] = Pending(SystemClock.elapsedRealtime(), null, 0)
    }

    /**
     * m1.e before 注入：按 `task.field_talker` 从 pendingMap keyed 命中，单调时钟 TTL 判活，
     * 条件原子消费（`remove(key, value)`），字节准入命中数==1 才写 `task.field_fullpath`。
     *
     * ## 有界重试（Q2.8-2 修正）
     * 第4轮首帧命中即消费 ⇒ 同转发 host 重传帧无 pending ⇒ 最后一次上传仍模糊。
     * 本轮改为：消费成功后，若该帧原图 basename 与首帧一致、且未超过 [MAX_HITS]，
     * 则**写入成功后**按同 basename 重登记（ts 续期、hits+1），保证重传帧同样被注入。
     * 防串味三闸：① key 仍是目标会话 talker（跨会话不命中）；② basename 不一致（同 talker 并发
     * 不同图）直接 fail-closed  return，不枚举不写不消费；③ 每次注入都重过 9554B/0ef205c1 门禁
     * 与 45522B/cff4f5a5 唯一判据，重试不放宽。超过 [MAX_HITS] 即清除停写，杜绝永久残留。
     *
     * 任一进入条件不满足立即 return：**绝不写 task 任何字段、绝不 remove 他人条目**。
     */
    private fun inject(param: XC_MethodHook.MethodHookParam) {
        val task = param.args?.getOrNull(0) ?: return
        val field_talker = runCatching {
            XposedHelpers.getObjectField(task, "field_talker") as? String
        }.getOrNull() ?: return
        if (field_talker.isBlank()) return
        val p = pendingMap[field_talker] ?: return
        if (SystemClock.elapsedRealtime() - p.ts > TTL_MS) {
            // TTL 超时：只条件 remove 自己这条，绝不写 task、不误删他人条目。
            pendingMap.remove(field_talker, p)
            LogX.w("$TAG arm expired reason=ttl talker=${field_talker.take(24)}")
            return
        }
        // 字节准入唯一来源 = m1.e before 的 param.args[0].field_fullpath。
        val fullpath = runCatching {
            XposedHelpers.getObjectField(task, "field_fullpath") as? String
        }.getOrNull() ?: return
        // 防串味②：同一 (talker, basename) 才视为同转发的重传帧；不同图一律 fail-closed。
        val bn = File(fullpath).name
        if (p.basename != null && p.basename != bn) {
            LogX.w("$TAG skip reason=basename_mismatch talker=${field_talker.take(24)}")
            return
        }
        // 有界：超过上限即清除停写，避免残留 + 无关上传反复触发昂贵枚举。
        if (p.hits >= MAX_HITS) {
            pendingMap.remove(field_talker, p)
            LogX.w("$TAG stop reason=retry_limit talker=${field_talker.take(24)}")
            return
        }
        // 条件原子消费：并发竞争失败（已被别人 remove/覆盖）者直接 return，不写、不动他人条目。
        if (!pendingMap.remove(field_talker, p)) return
        // 每次注入（含重试帧）都重过字节门禁，绝不因是重试而放宽。
        val target = findTargetHevc(fullpath) ?: return
        runCatching { XposedHelpers.setObjectField(task, "field_fullpath", target) }
            .onFailure { LogX.w("$TAG set field_fullpath failed: ${it.javaClass.simpleName}") }
        // 日志只打 take(24)/takeLast(16)，绝不打完整值。
        LogX.i("$TAG injected talker=${field_talker.take(24)} tail=${target.takeLast(16)} hits=${p.hits + 1}")
        // 写入成功后按同 basename 有界重登记：保证同转发 host 重传的"最后一次上传"也写 hevc。
        if (p.hits + 1 < MAX_HITS) {
            pendingMap[field_talker] = Pending(SystemClock.elapsedRealtime(), bn, p.hits + 1)
        }
    }

    /**
     * 字节准入（全程只读）：
     * 1) 模糊态门禁：本 task 原 field_fullpath 文件须存在 + 9554B + 内容 MD5 前8 = 0ef205c1；
     * 2) 从 field_fullpath 正则取 `/MicroMsg/<32hex>/` 数据根（不是 j2.R 的 param.result）；
     * 3) **优先原图同目录分桶**：同内容 `.jpg_hevc` 通常就落在原图旁边，扫完本目录即停
     *    （O(单目录文件数)，不触发全树，避免阻塞 [D]#worker）；同目录 0 命中才走
     *    root/image2 两级分桶兜底，且设 [MAX_SCAN_FILES] 上限（最坏耗时上界见 impl note）。
     * 全程保持"命中数必须 ==1"的 fail-closed 判据（0 或 >1 一律不写），不因优化削弱。
     */
    private fun findTargetHevc(fullpath: String): String? = runCatching {
        val origin = File(fullpath)
        if (!origin.exists() || origin.length() != BLUR_LEN) return@runCatching null
        if (md5PrefixOfFile(origin) != BLUR_MD5_PRE) return@runCatching null
        val rootHit = Regex("/MicroMsg/[0-9a-fA-F]{32}/").find(fullpath) ?: return@runCatching null
        val root = fullpath.substring(0, rootHit.range.last + 1)

        val hits = mutableListOf<String>()
        val parentAbs = origin.parentFile?.absolutePath
        // ① 原图同目录优先：最常见命中点；扫完整目录仍保持"唯一命中才写"。
        origin.parentFile?.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".jpg_hevc") && admitTarget(f)) hits.add(f.absolutePath)
        }
        // ② 同目录 0 命中才兜底全树：root/image2 两级分桶，带 MAX_SCAN_FILES 上限 + 歧义早停。
        if (hits.isEmpty()) {
            var scanned = 0
            outer@ for (d1 in (File(root, "image2").listFiles() ?: emptyArray())) {
                if (!d1.isDirectory) continue
                for (d2 in (d1.listFiles() ?: emptyArray())) {
                    if (!d2.isDirectory) continue
                    if (d2.absolutePath == parentAbs) continue // 同目录已扫过，跳过避免重复
                    for (f in (d2.listFiles() ?: emptyArray())) {
                        if (scanned >= MAX_SCAN_FILES) break@outer
                        scanned++
                        if (f.isFile && f.name.endsWith(".jpg_hevc") && admitTarget(f)) {
                            hits.add(f.absolutePath)
                            if (hits.size > 1) break@outer // 已判歧义，早停省时
                        }
                    }
                }
            }
        }
        // 命中数必须唯一：0 或 >1 一律不写（fail-closed 判据不因优化削弱）。
        if (hits.size == 1) hits.first() else null
    }.onFailure {
        LogX.w("$TAG findTargetHevc err: ${it.javaClass.simpleName}")
    }.getOrNull()

    /** 目标态准入：存在 + 45522B + 内容 MD5 前 8 == cff4f5a5（禁 P3AdmitGate / xml md5 参照）。 */
    private fun admitTarget(f: File): Boolean {
        if (!f.exists() || f.length() != TARGET_LEN) return false
        return md5PrefixOfFile(f) == REF_MD5_PRE
    }

    /** 文件字节 MD5 前 4 字节 → 8 个 hex（与 SCD fmd5pre 同口径）。 */
    private fun md5PrefixOfFile(f: File): String? = runCatching {
        val digest = MessageDigest.getInstance("MD5")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }.take(8)
    }.getOrNull()

    /** 字符串（参照 md5）取前 8 hex；非 hex 返回 null（只读探针链保留）。 */
    private fun md5PrefixOf(s: String): String? = runCatching {
        val t = s.take(8).lowercase()
        if (t.length == 8 && t.all { it in '0'..'9' || it in 'a'..'f' }) t else null
    }.getOrNull()

    /** FileSender 失败路径：只清指定 wxid 的 key（不清全表、不动他人条目）。 */
    fun clear(wxid: String) {
        pendingMap.remove(wxid)
    }

    /** 独立开关广播翻转（不写库、不持久化）。 */
    fun turn(on: Boolean) {
        hevcEnabled = on
        LogX.i("HEVC enabled=$on")
    }

    fun status(): List<String> = listOf(
        "HEVC enabled=$hevcEnabled pending=${pendingMap.size} key=${Keys.FORWARD_TASK_FULLPATH_HEVC}"
    )

    /**
     * 保留 arm 签名供 FileSender / 探针用：**只做只读探针**，绝不解析路径、登记 pending、写宿主字段。
     */
    fun arm(talker: String, msgSvrId: Long, imgLocalId: Long) {
        runCatching {
            if (talker.isBlank() || msgSvrId <= 0L || imgLocalId <= 0L) return@runCatching
            val cl = loader ?: return@runCatching
            val d3cls = XposedHelpers.findClass("com.tencent.mm.modelimage.d3", cl)
            val o8 = XposedHelpers.callStaticMethod(d3cls, "O8")
            if (probeEnabled) probeR86c(talker, msgSvrId, imgLocalId, o8)
            val r0 = XposedHelpers.callMethod(o8, "a3", talker, msgSvrId)
            // 只读 a3（探针取证链）：结果不用于写侧解析/pending。
            LogX.d("$TAG arm probe r0=${r0?.javaClass?.simpleName ?: "null"}")
        }.onFailure { LogX.w("$TAG arm failed: ${it.javaClass.simpleName}") }
    }

    /** R86c Q4b 只读探针开关（默认关，冷启静默）。 */
    fun turnProbe(on: Boolean) {
        probeEnabled = on
        LogX.i("R86C R0 enabled=$on")
    }

    /**
     * R86c Q4b P-R0 + P-R0ID 只读取证（前缀 R86C）。原样保留：
     * 只读 a3/o2/z3，不写 param.args/宿主字段、不发消息、不建 db 记录；字段与路径各自独立 runCatching。
     */
    private fun probeR86c(talker: String, msgSvrId: Long, imgLocalId: Long, o8: Any) {
        runCatching {
            // ---- P-R0ID：a3 的 r0 与 o2 的 r0 是否同一对象/同一行 ----
            val r0a3 = runCatching { XposedHelpers.callMethod(o8, "a3", talker, msgSvrId) }.getOrNull()
            val r0o2 = runCatching { XposedHelpers.callMethod(o8, "o2", imgLocalId) }.getOrNull()
            LogX.i("R86C R0ID a3id=${r0a3?.let(::idh) ?: "NA"} " +
                "o2id=${r0o2?.let(::idh) ?: "NA"} sameObj=${r0a3 != null && r0a3 === r0o2}")
            LogX.i("R86C R0ID a3Fields=${r0Fields(r0a3)} o2Fields=${r0Fields(r0o2)}")

            if (r0o2 == null) {
                LogX.w("R86C R0 skip reason=no_o2_r0")
                return@runCatching
            }
            val hits = mutableListOf<String>()
            // 四路 z3：第4参 boolean 默认 false（j2.O 现场为 trysafecdn flag，影响路径与否 UNKNOWN）。
            fun one(f: String, b: Boolean, retry: Boolean) {
                val raw = runCatching { XposedHelpers.getObjectField(r0o2, f) as? String }.getOrNull()
                val path = runCatching {
                    XposedHelpers.callMethod(o8, "z3", raw, "", "", b) as? String
                }.getOrNull()
                val len = runCatching {
                    if (path.isNullOrEmpty()) -1L else java.io.File(path).length()
                }.getOrDefault(-1L)
                val pre = runCatching {
                    if (path.isNullOrEmpty()) "NA" else {
                        val file = java.io.File(path)
                        if (!file.isFile || file.length() > 32L * 1024 * 1024) "NA"
                        else md5PrefixOfFile(file) ?: "ERR"
                    }
                }.getOrDefault("ERR")
                if (len == 45522L && pre == "cff4f5a5") hits.add("${if (retry) "true:" else ""}$f")
                LogX.i("R86C R0${if (retry) "retryTrue" else ""} field=$f " +
                    "raw=${raw?.take(80) ?: "NA"} tail=${path?.takeLast(16) ?: "NA"} " +
                    "len=$len md5pre=$pre")
            }
            listOf("e", "f", "g", "j").forEach { one(it, false, false) }
            LogX.i("R86C R0 verdict hitFields=${hits.joinToString(",").ifEmpty { "none" }}")
            // 第4参 UNKNOWN：四路 false 全不命中时，只读重试一遍 true（不改判据）。
            if (hits.isEmpty()) {
                listOf("e", "f", "g", "j").forEach { one(it, true, true) }
                LogX.i("R86C R0 verdict retryTrue hitFields=${hits.joinToString(",").ifEmpty { "none" }}")
            }
        }.onFailure { LogX.w("R86C R0 err: ${it.javaClass.simpleName}") }
    }

    /** 只读打 r0 的 e/f/g/j/l（各 take(80)），供 P-R0ID 逐字段比对。 */
    private fun r0Fields(r0: Any?): String {
        if (r0 == null) return "null"
        return listOf("e", "f", "g", "j", "l").joinToString(",") { f ->
            val v = runCatching { XposedHelpers.getObjectField(r0, f) as? String }.getOrNull()
            "$f=${v?.take(80) ?: "NA"}"
        }
    }

    private fun idh(o: Any): Int = System.identityHashCode(o)

    /** 签名漂移时打实际命中的 method sig（take 80），定位用。 */
    private fun sigOf(p: XC_MethodHook.MethodHookParam): String =
        runCatching { p.method.toString().takeLast(80) }.getOrDefault("NA")
}
