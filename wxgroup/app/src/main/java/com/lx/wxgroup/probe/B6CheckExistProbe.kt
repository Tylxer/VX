package com.lx.wxgroup.probe

import android.os.SystemClock
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * R86c · B5 干预实验探针（日志前缀 `B6X`）。
 *
 * 目标 = 验证「补齐三字段后自建 checkExist 秒传探测能否被启动」，这是**机制验证干预实验，不是产品功能**；
 * 不声称命中原图、不裁决 md5 身份。判据重定见 `docs/_r86c_b5_fix_note.md` §1/§2（原 impl note §1/§2 已撤回），本文件只落码不改判据。
 * B5 判据（见 `docs/_r86c_b5_fix_note.md` §2，已降级）= 同一会话 off/on 对照下，Proxy C2 第二次（真实回包）
 * 回调的 `field_exist_whencheck` 由 false 翻 true 且 `field_filemd5` 长度由 0 翻 >0（`logC2Shape` 已排除空壳），
 * 作为「三字段启动 checkExist 秒传探测」的机制信号；md5 前缀只记录、不裁决身份，**不声称命中原图**。
 * **不追肉眼翻转**：C2 只读、不调 j6 / 不发消息 / 不写宿主字段，与真实转发消息无耦合，肉眼翻转在 B5 不可能发生。
 *
 * ## 🔴 只读红线（最高优先级，改动本文件前先读）
 *
 * 1. Proxy 的 C2 回调**只做**：反射读 Ltj/f 的 `field_exist_whencheck`/`field_filemd5` + 全部 String
 *    字段名/长度、打 `args[3].javaClass.name`，然后返回 0。**绝不调 `b1.j6`、绝不调 `FileSender.send`、
 *    绝不写任何宿主字段、绝不改 m.a、绝不发消息**；`I`/`z` 方法只返回默认值、不打日志。
 * 2. arm 段构造 `tj/k`（含 B5 三字段）+ 下发 B8 是唯一允许的宿主调用（这就是验证目的本身）。
 *    `field_fullpath` 走独立方法 [resolveFullpath] 动态定位 / 安全回退，**不写死任何字节数或内容 MD5 常量**
 *    （45522/148929/cff4f5a5/484bc088/36/124 一律不进代码）。
 * 3. 全程 runCatching 单钩守护：类找不到 / 字段写失败单独 runCatching + WARN，不崩微信、不影响其它 hook。
 *    本探针**不 hook 任何方法**，install 只存 classLoader + 反射找类 + 记 status。
 * 4. 日志只打前缀/长度：fileId/aesKey/filemd5 只打长度 + 前 8 字符；普通 String `take(80)`、路径 `take(120)`。
 * 5. 默认静默：enabled=false 时回调第一行就 return（零反射）；仅广播 `op=b6step sub=on` 打开。
 * 6. 幂等 installed；单钩失败不阻断其它。
 *
 * ## 字段取值说明（取值裁决见 note §1）
 *
 * - `field_fullpath`=独立方法 [resolveFullpath] 的返回：主路径仅按「同根唯一 hevc 文件名 + 非空」定位
 *   （**不计算任何内容摘要、不做身份准入**），回退非空小占位（占位只作 hitCheck 启动令牌），全失败 fail-closed 不下发。
 *   与 q4b「字节内容摘要决定身份」的关系 =【UNKNOWN，见 fix note §1】，本探针不声称对齐原图字节。
 * - `field_enable_hitcheck`=true / `field_fake_bigfile_signature`=""：B5 补齐，对齐 A 态实测。
 * - `field_onlycheckexist`=false：R78 §Q2.4 + R82 Z.1 真机坐实 stock/我方恒 false。
 * - `field_mediaId`=`checkExist_$msgSvrId`：B6 原值来自 `n1.b("checkExist",msgId,"","")`（R82 H2）；
 *   本探针不额外发起该 host 调用，用非空确定串即可过 `ok0/m1.e` 的 mediaId 校验。
 * - `field_fileType`=2 / `field_priority`=0 / `field_svr_signature`=""：图片任务常规值，不参与命中判据。
 */
object B6CheckExistProbe {

    // ---- 判据常量（单测锁拼写，防探针读错字段白跑）----
    /** DEX 描述符 Ltj/i; 对应的 Java 类名——前导 L 是描述符标记不是包名，故为 tj.i（非 ltj.i）。 */
    private const val LISTENER_CLS = "tj.i"

    private val C2_READ_FIELDS = listOf("field_exist_whencheck", "field_filemd5")

    /** field_fullpath 主路径枚举上限（文件数），防扫描失控；命中数必须 ==1，否则走回退。 */
    private const val MAX_SCAN_FILES = 1024

    /** 主路径扫描时间预算（毫秒）：elapsedRealtime 截止即放弃主路径走回退，避免 worker 线程秒级全树扫描。 */
    private const val SCAN_TIME_BUDGET_MS = 250L

    /** 数据根正则：从 u/map 的绝对路径里截 `/…/MicroMsg/<32hex>/`（不硬编码 hash / 不硬编码字节值）。 */
    private val RE_DATA_ROOT = Regex("(.*/MicroMsg/[0-9a-fA-F]{32}/)")

    /** tj/k 写入字段全集（dex 实证 + B5 补三字段）；B5 起含 field_fullpath。 */
    private val TASK_FIELDS = listOf(
        "d", "f", "field_mediaId", "field_fileId", "field_aesKey", "field_filemd5",
        "field_talker", "field_fileType", "field_priority", "field_svr_signature",
        "field_onlycheckexist", "field_trysafecdn",
        "field_fullpath", "field_enable_hitcheck", "field_fake_bigfile_signature"
    )

    /** 源 xml g9.c map 三键（ForwardCdnFieldHook.arm 只取前两个，这里多取 $md5 填 task.filemd5）。 */
    private val MAP_KEYS = listOf(".msg.img.\$cdnbigimgurl", ".msg.img.\$aeskey", ".msg.img.\$md5")

    /** xml 原文正则回退（map 缺键时用；与 StockCdnProbe SCD2 g9c 同款）。
     *  R86b 真机：坏图 map 与 xml 均无 cdnbigimgurl，但有 cdnthumburl="305f0201..."（194B，
     *  = 态① stock 转发的 fileId）——故 fileId 回退链按 big→mid→thumb 依次尝试。
     *  这只是"取哪个候选值下发 checkExist"的诊断，裁决仍看 C2 回包 exist/filemd5。 */
    private val RE_FILEID_CANDIDATES = listOf(
        "big" to Regex("cdnbigimgurl=\"([^\"]*)\""),
        "mid" to Regex("cdnmidimgurl=\"([^\"]*)\""),
        "thumb" to Regex("cdnthumburl=\"([^\"]*)\"")
    )
    private const val RE_AES = "aeskey=\"([^\"]*)\""
    private const val RE_MD5 = "md5=\"([^\"]*)\""

    /** Ltj/i 接口三方法（dex 实证 shape 锁）：C2(String,int,Ltj/e,Ltj/f,Z)I / I(String,[B)[B / z(String,ByteArrayOutputStream)V。 */
    private val LISTENER_METHODS = listOf("C2", "I", "z")

    /** 只有 enabled 时 C2 回调才反射读字段、打日志；广播可即时翻转。 */
    @Volatile
    var enabled = false

    /** 幂等：install 只跑一次。 */
    @Volatile
    private var installed = false

    @Volatile
    private var classLoader: ClassLoader? = null

    /** status() 的 classes= 回显：按规格只算 d3/g9/tj.k/mq.k 四个类。 */
    @Volatile
    private var classesFound = false

    private var d3Cls: Class<*>? = null
    private var g9Cls: Class<*>? = null
    private var kCls: Class<*>? = null
    private var mqkCls: Class<*>? = null

    // ------------------------------------------------------------------ 安装（不 hook 任何方法）

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true
        val cl = lpparam.classLoader
        classLoader = cl
        d3Cls = findCls("com.tencent.mm.modelimage.d3", cl)
        g9Cls = findCls("com.tencent.mm.sdk.platformtools.g9", cl)
        kCls = findCls("tj.k", cl)
        mqkCls = findCls("mq.k", cl)
        // Ltj/i; 的 Java 名是 tj.i（自建 Proxy 需要）；classesFound 按规格只算上面四个类。
        findCls(LISTENER_CLS, cl)
        classesFound = d3Cls != null && g9Cls != null && kCls != null && mqkCls != null
        LogX.i("B6X STATUS enabled=$enabled installed=$installed classes=$classesFound")
    }

    // ------------------------------------------------------------------ 开关 / 状态

    fun turn(on: Boolean) {
        enabled = on
        LogX.i("B6X enabled=$on")
    }

    /**
     * 转发当下用 snap 真实参数自动 arm（设计 §5.3 接入点 = FileSender.send 前）。
     * 自动 arm 用 srcTalkerPrefix（非空源 talker，去尾冒号）作 a3/Q2 读源 xml 的 talker；
     * 目标 wxid 作 taskTalker → task.field_talker（toUser）；msgId 供 Q2 回退（<=0 时回退 msgSvrId）。
     * 手工 b6step 通道仍可用默认参（talker/svrid 两入参，零改动兼容）。
     * enabled=false（默认）时零动作 ⇒ 反向对照只差 b6step 开关这一个自变量。
     */
    fun armFromForward(talker: String, msgSvrId: Long, taskTalker: String = talker, msgId: Long = 0L) {
        if (!enabled) return
        if (talker.isBlank() || msgSvrId <= 0L) {
            LogX.w("B6X armFromForward skip invalid talker/svrId")
            return
        }
        arm(talker, msgSvrId, taskTalker, msgId)
    }

    fun status(): List<String> =
        listOf("B6X STATUS enabled=$enabled installed=$installed classes=$classesFound")

    // ------------------------------------------------------------------ arm（唯一允许的宿主调用段）

    /**
     * 只读取源 xml 参数 → 构造含 B5 三字段（含 field_fullpath）的 checkExist task → 自建 Ltj/i Proxy → 下发 B8/m1.e。
     * talker = 源消息存储会话（a3/Q2 读源 xml 的键，来自 srcTalkerPrefix.trimEnd(':')）；
     * taskTalker = 目标会话 wxid（写 task.field_talker → toUser，stock B6「目标 wxid 作 toUser」语义）；
     * msgId = 源消息本地 id，供 Q2 回退入参（<=0L 回退 msgSvrId 兼容手工通道）。三者职责不可混用。
     * 全程 runCatching；任何一步失败只 WARN，不发消息、不写宿主字段。
     */
    fun arm(talker: String, msgSvrId: Long, taskTalker: String = talker, msgId: Long = 0L) {
        runCatching {
            if (!enabled) {
                LogX.w("B6X arm skip reason=disabled")
                return@runCatching
            }
            if (talker.isBlank() || msgSvrId <= 0L) {
                LogX.w("B6X arm skip reason=invalid_args")
                return@runCatching
            }
            val cl = classLoader ?: run {
                LogX.w("B6X arm skip reason=no_classloader")
                return@runCatching
            }
            val d3 = d3Cls ?: error("d3 missing")
            val g9 = g9Cls ?: error("g9 missing")
            val k = kCls ?: error("tj.k missing")
            val iCls = findCls(LISTENER_CLS, cl) ?: error("$LISTENER_CLS missing")

            // 1) d3.O8() → r0.u → g9.c(u,"msg","") → map；只读。
            //    a3(talker,msgSvrId) 首选（msgSvrId=服务端 19 位 id）；回退 Q2(talker,msgId)（msgId=本地 id，
            //    ForceForward.imgLocalIdOf 真机验证过该入口）。msgId<=0L（手工通道）时 Q2 回退 msgSvrId。
            //    talker 只用于 a3/Q2 读源 xml；taskTalker（目标 wxid）只用于 field_talker，绝不混用。
            val o8 = XposedHelpers.callStaticMethod(d3, "O8")
            var u: String? = null
            var map: Map<*, *>? = null
            var xmlVia = "NA"
            for (m in arrayOf("a3", "Q2")) {
                val idForMethod = if (m == "a3") msgSvrId else (if (msgId > 0L) msgId else msgSvrId)
                val r0r = runCatching { XposedHelpers.callMethod(o8, m, talker, idForMethod) }
                val r0 = r0r.getOrNull()
                if (r0 == null) {
                    LogX.w("B6X arm try $m r0=FAIL ${r0r.exceptionOrNull()?.javaClass?.simpleName}")
                    continue
                }
                val uTry = runCatching { XposedHelpers.getObjectField(r0, "u") as? String }.getOrNull()
                if (uTry.isNullOrBlank()) {
                    LogX.w("B6X arm try $m u=blank")
                    continue
                }
                val mapR = runCatching {
                    XposedHelpers.callStaticMethod(g9, "c", uTry, "msg", "") as? Map<*, *>
                }
                val mapTry = mapR.getOrNull()
                LogX.i(
                    "B6X arm try $m uLen=${uTry.length} " +
                        "uPre=${uTry.take(300).replace('\n', '|').replace('\r', ' ')} " +
                        "map=${mapTry?.size ?: "null"} mapErr=${mapR.exceptionOrNull()?.javaClass?.simpleName ?: "-"} " +
                        "keys=${mapTry?.keys?.take(20)}"
                )
                // 只打 xml 属性键名+值长度（不打值），一次看清 img 节点有哪些 CDN 身份键
                val attrSig = Regex("([A-Za-z0-9_]+)=\"([^\"]*)\"")
                    .findAll(uTry).map { "${it.groupValues[1]}:${it.groupValues[2].length}" }
                    .take(24).joinToString(",")
                LogX.i("B6X arm attrs $m $attrSig")
                if (mapTry == null) continue
                u = uTry
                map = mapTry
                xmlVia = m
                break
            }
            if (u == null || map == null) {
                LogX.w("B6X arm skip reason=no_xml via=$xmlVia")
                return@runCatching
            }
            // 3) 取 fileId/aesKey/md5：g9.c map 键优先；缺键时按 big→mid→thumb 依次试 xml 正则。
            //    R86b 真机：坏图 map 与 xml 均无 cdnbigimgurl，但 cdnthumburl="305f0201..."（194B，
            //    与态① stock fileId 逐字同）⇒ 候选链覆盖它。裁决仍看 C2 回包，不预设哪个是原图。
            val (keyFileId, keyAes, keyMd5) = MAP_KEYS
            var fileId: String? = (map[keyFileId] as? String)?.takeIf { it.isNotBlank() }
            var idVia = "map"
            if (fileId == null) {
                for ((tag, rx) in RE_FILEID_CANDIDATES) {
                    val v = rx.find(u)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                    if (v != null) {
                        fileId = v
                        idVia = "regex:$tag"
                        break
                    }
                }
            }
            if (fileId == null) {
                LogX.w("B6X arm skip reason=no_fileId via=$xmlVia"); return@runCatching
            }
            // 强制校验 fileId 来源：map / regex:big 才是 big 锚点；regex:mid / regex:thumb 是降级锚点，
            // 明确标注「非原图锚点」，只作诊断下发，绝不按 big 原图身份使用（BLOCKER-2 处置）。
            if (idVia != "map" && idVia != "regex:big") {
                LogX.w("B6X arm idVia=$idVia 非原图锚点（不当 big 用）via=$xmlVia")
            }
            val aesKey = (map[keyAes] as? String)?.takeIf { it.isNotBlank() }
                ?: Regex(RE_AES).find(u)?.groupValues?.getOrNull(1).orEmpty()
            val xmlMd5 = (map[keyMd5] as? String)?.takeIf { it.isNotBlank() }
                ?: Regex(RE_MD5).find(u)?.groupValues?.getOrNull(1).orEmpty()
            LogX.i(
                "B6X arm xml via=$xmlVia idVia=$idVia fidLen=${fileId.length} " +
                    "fidPre=${fileId.take(8)} aesLen=${aesKey.length} " +
                    "md5Len=${xmlMd5.length} md5Pre=${xmlMd5.take(8)} uLen=${u.length}"
            )

            // 2) 构造 tj/k：逐字段写，单独 runCatching；B5 补三字段（含 field_fullpath）。
            //    field_fullpath 全失败 → fail-closed 不下发（不写半个 task）。
            val fullpath = resolveFullpath(u, map)
            if (fullpath == null) {
                // fail-closed 同时校验 fileId 来源身份：idVia 必须随不下发日志落盘，便于回放是否 mid 污染。
                LogX.w("B6X arm skip reason=no_fullpath (fail-closed) idVia=$idVia")
                return@runCatching
            }
            val task = XposedHelpers.newInstance(k)
            put(task, "d", "task_MsgRetransmitUI_2")
            put(task, "field_mediaId", "checkExist_$msgSvrId")
            put(task, "field_fileId", fileId) // 命门：305f 锚点，绝不清空
            put(task, "field_aesKey", aesKey)
            put(task, "field_filemd5", xmlMd5)
            put(task, "field_talker", taskTalker)
            put(task, "field_fileType", 2)
            put(task, "field_priority", 0)
            put(task, "field_svr_signature", "")
            put(task, "field_onlycheckexist", false)
            put(task, "field_trysafecdn", false)
            put(task, "field_fullpath", fullpath)
            put(task, "field_enable_hitcheck", true)
            put(task, "field_fake_bigfile_signature", "")

            // 3) 自建 Ltj/i Proxy：接口 shape 漂移只 WARN 不失败。
            val declared = runCatching { iCls.methods.map { it.name }.toSet() }.getOrDefault(emptySet())
            if (!declared.containsAll(LISTENER_METHODS)) {
                LogX.w("B6X arm $LISTENER_CLS shape drift expected=$LISTENER_METHODS actual=$declared")
            }
            var hookOk = false
            val (existField, md5Field) = C2_READ_FIELDS
            val handler = InvocationHandler { _, method, args ->
                if (!enabled) return@InvocationHandler defaultOf(method.returnType)
                runCatching {
                    when (method.name) {
                        "C2" -> {
                            // 只读反射 Ltj/f（args[3]）：exist + md5 前缀/长度，然后返回。
                            val f = args?.getOrNull(3)
                            // 只读自检：args[3] 实际类名 + Ltj/f 全部 String 字段名/长度，
                            // 彻底排除「读错对象 / 空壳」（b6step【基本排除】→【完全排除】）。
                            logC2Shape(f)
                            val exist = runCatching {
                                XposedHelpers.getObjectField(f, existField)?.toString() ?: "null"
                            }.getOrDefault("NA")
                            val md5 = runCatching { XposedHelpers.getObjectField(f, md5Field) as? String }
                                .getOrDefault(null)
                            val md5Pre = md5?.take(8) ?: "NA"
                            val md5Len = md5?.length ?: -1
                            LogX.i("B6X C2 exist=$exist md5Pre=$md5Pre md5Len=$md5Len")
                            0
                        }
                        "I" -> null
                        "z" -> null
                        else -> null
                    }
                }.getOrDefault(defaultOf(method.returnType))
            }
            val listener = Proxy.newProxyInstance(cl, arrayOf(iCls), handler)
            put(task, "f", listener)
            hookOk = true

            // 4) 下发 checkExist（会真的产生一次 checkExist 网络请求：无 DB 写、无消息发送）。
            val via = dispatch(task)
            if (via == null) {
                LogX.w("B6X arm dispatch both paths FAIL")
                return@runCatching
            }
            // 5) 只打长度 + 前 8 位（本行按规格只打长度/布尔，值前缀见 C2 行）。
            LogX.i(
                "B6X arm sent fidLen=${fileId.length} aesLen=${aesKey.length} md5Len=${xmlMd5.length} existHook=$hookOk " +
                    "fpathLen=${fullpath.length} fpathTail=${fullpath.takeLast(12)} via=$via fields=${TASK_FIELDS.size}"
            )
        }.onFailure {
            LogX.w("B6X arm failed: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
        }
    }

    /**
     * 返回下发路径名，或 null（两条都失败，由 arm 打 WARN）。
     * 路 A：mq/k.B8 —— mq/k 实例按 §1.1 第4条取 `d94/n0.c(nq/e0.class)`（cross-package public）。
     * 路 B：ok0/s2.e9() → ok0/m1 → m1.e(task)（B8 是纯 delegate，等价）。
     */
    private fun dispatch(task: Any): String? {
        val cl = classLoader ?: return null
        val a = runCatching {
            val n0 = findCls("d94.n0", cl) ?: error("d94.n0 missing")
            val e0 = findCls("nq.e0", cl) ?: error("nq.e0 missing")
            val inst = XposedHelpers.callStaticMethod(n0, "c", e0) ?: error("mq/k instance null")
            XposedHelpers.callMethod(inst, "B8", task)
        }
        if (a.isSuccess) return "B8"
        LogX.w("B6X arm B8 FAIL ${a.exceptionOrNull()?.javaClass?.simpleName}")

        val b = runCatching {
            val s2 = findCls("ok0.s2", cl) ?: error("ok0.s2 missing")
            val m1 = XposedHelpers.callStaticMethod(s2, "e9") ?: error("m1 null")
            XposedHelpers.callMethod(m1, "e", task)
        }
        if (b.isSuccess) return "m1.e"
        LogX.w("B6X arm m1.e FAIL ${b.exceptionOrNull()?.javaClass?.simpleName}")
        return null
    }

    // ------------------------------------------------------------------ 内部工具

    /**
     * B5 `field_fullpath` 填值裁决的落码（见 note §1；独立方法便于单测与替换）。
     *
     * 主路径：从 u/map（d3.O8→r0.u→g9.c 已取到，不新增 hook）动态定位数据根 image2/ 子目录下唯一的 hevc 文件，
     * 只按「唯一候选 + 非空」准入，**不计算内容摘要、不写死任何字节数 / 摘要指纹常量**（换图不失效）；
     * 限定 image2/ 子目录 + elapsedRealtime 时间预算，超时即放弃主路径（防 worker 线程秒级全树扫描）。
     * 回退：构造一个非空小占位文件（无 magic / 64KB / 分桶要求），只作 hitCheck 非空字节入口启动令牌。
     * 旧稿「身份靠 fileId(305f) 锚点 / 两链分叉」=【推断·高，见 fix note §1】，与 q4b 字节决定论的冲突 =【UNKNOWN】；
     * 本探针**不据占位或 fileId 裁决身份**。全失败返回 null ⇒ arm fail-closed 不下发。
     */
    private fun resolveFullpath(u: String, map: Map<*, *>?): String? {
        // 主路径：从 map/u 的绝对路径里截数据根，枚举唯一 hevc 候选（命中数 ==1 才用）。
        var root: String? = null
        for (v in (map?.values ?: emptyList())) {
            val r = (v as? String)?.let { RE_DATA_ROOT.find(it)?.groupValues?.getOrNull(1) }
            if (r != null) { root = r; break }
        }
        if (root == null) root = RE_DATA_ROOT.find(u)?.groupValues?.getOrNull(1)
        if (root != null) {
            // 性能（复核 §③ 第4条）：只扫 image2/ 子目录 + elapsedRealtime 时间预算。
            // 超时即放弃主路径：半途扫描得到的"唯一命中"不可信，必须 fail-closed 走回退。
            var timedOut = false
            val deadline = SystemClock.elapsedRealtime() + SCAN_TIME_BUDGET_MS
            val hits = runCatching {
                File(root, "image2").walkTopDown().maxDepth(6)
                    .filter {
                        if (SystemClock.elapsedRealtime() >= deadline) {
                            timedOut = true
                            return@filter false
                        }
                        it.isFile && it.name.endsWith(".jpg_hevc") && it.length() > 0L
                    }
                    .take(MAX_SCAN_FILES + 1).toList()
            }.getOrDefault(emptyList())
            if (!timedOut && hits.size == 1) return hits[0].absolutePath
        }
        // 安全回退：非空小占位文件，只作 hitCheck 非空字节入口启动令牌；
        // 身份判定不属本探针（md5 前缀只记录不裁决，旧稿的 fileId 锚点身份假设已撤回，见 fix note §1）。
        return runCatching {
            val f = File.createTempFile("b6x_", ".jpg_hevc")
            if (!f.exists() || f.length() == 0L) f.writeBytes(byteArrayOf(0x42, 0x36, 0x58))
            f.absolutePath
        }.onFailure { LogX.w("B6X arm placeholder FAIL ${it.javaClass.simpleName}") }.getOrNull()
    }

    /** C2 只读自检：打 args[3] 实际类名 + Ltj/f 全部 String 字段名/长度（只名字/长度，不打值）。 */
    private fun logC2Shape(f: Any?) {
        val cls = f?.javaClass?.name ?: "null"
        val fields = runCatching {
            f?.javaClass?.declaredFields
                ?.filter { it.type == String::class.java }
                ?.joinToString(",") { field ->
                    field.isAccessible = true
                    val len = (field.get(f) as? String)?.length ?: -1
                    "${field.name}:$len"
                } ?: "NA"
        }.getOrDefault("ERR")
        LogX.i("B6X C2 shape cls=$cls strFields=$fields")
    }

    /** 单个字段写入；失败单独 WARN，不阻断其它字段。三种 setter 分开，便于类型安全。 */
    private fun put(obj: Any?, name: String, value: Any?) {
        runCatching { XposedHelpers.setObjectField(obj, name, value) }
            .onFailure { LogX.w("B6X arm set $name FAIL ${it.javaClass.simpleName}") }
    }

    private fun put(obj: Any?, name: String, value: Int) {
        runCatching { XposedHelpers.setIntField(obj, name, value) }
            .onFailure { LogX.w("B6X arm set $name FAIL ${it.javaClass.simpleName}") }
    }

    private fun put(obj: Any?, name: String, value: Boolean) {
        runCatching { XposedHelpers.setBooleanField(obj, name, value) }
            .onFailure { LogX.w("B6X arm set $name FAIL ${it.javaClass.simpleName}") }
    }

    /** 只读找类；失败返回 null（单独 WARN），不崩。 */
    private fun findCls(name: String, cl: ClassLoader): Class<*>? =
        runCatching { XposedHelpers.findClass(name, cl) }
            .onFailure { LogX.w("B6X class $name FAIL ${it.javaClass.simpleName}") }
            .getOrNull()

    /** InvocationHandler 的默认值：C2 返回 int 0，I(byte[])/z(void) 返回 null。 */
    private fun defaultOf(returnType: Class<*>): Any? = when (returnType) {
        java.lang.Integer.TYPE -> 0
        java.lang.Boolean.TYPE -> false
        java.lang.Long.TYPE -> 0L
        else -> null
    }
}
