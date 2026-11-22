package com.lx.wxgroup.core

import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * R80 最终裁决 (a)：图片转发上传前，把源坏图 xml 里的 CDN `fileId`/`aesKey` 补进上传 task，
 * 让微信服务端**秒传命中原图**，而不是上传本地 15KB 缩略图导致模糊。规格 R80 §2.3。
 *
 * ## 为什么只补字段就够了（fileId 是决定量）
 * 同一张坏图 216b 本地无大图。真机硬对照：stock 自带转发 `m1e task.fileId=305f...`（非空）
 * ⇒ 清晰；我方模块转发 `fileId=空` ⇒ 上传 15KB 缩略图 ⇒ 模糊。dex 证 fileId 唯一来源
 * = `j2.O` 从消息 xml 用 `g9.c` 解析 `$cdnbigimgurl`。**决定量是 CDN fileId，不是本地字节**
 * ⇒ 只需在上传 task 下发前把 `fileId`/`aesKey` 补进 task，无需落盘大图、无需改 ImgInfo2/DB。
 *
 * ## 只读红线
 * - [arm] **只用反射读 + 只读 map 取值**，不改 r0 / ImgInfo2 / 消息表；
 * - tj/k 的字段写入**只发生在 [inject]（m1.e before）**，别处绝不写宿主字段；
 * - 日志**只打长度**，绝不打完整 fileId/aesKey。
 *
 * ## 闸门（防污染普通图片发送）
 * `ok0/m1.e(tj/k)Z` 是全局图片上传下发点，所有图片发送都会经过。故 before 首行 `if (!enabled) return`，
 * 且必须有一次性的 pending 才写；无 pending / TTL 超时 / 发送失败 ⇒ 直接 return，绝不碰 task。
 * pending 由 [FileSender] 每目标发送前 [arm] 一次，注入后立即清（一个文件转发只补一次，防多目标串味）。
 */
object ForwardCdnFieldHook {

    private const val TAG = "FORWARD CDN_FIELD"

    /** 交接 TTL：超过该时长未命中 m1.e 视为失效（建议 ≤2s）。 */
    private const val TTL_MS = 2000L

    @Volatile
    private var installed = false

    /** 宿主 ClassLoader，由 [install] 保存，供 [arm] 反射找类。 */
    @Volatile
    private var loader: ClassLoader? = null

    /** 闸门：仅本次转发取到源 CDN 参数且 pending 有效期间为 true。 */
    @Volatile
    private var enabled = false

    /** 一次性交接数据：本次待补的 fileId/aesKey + arm 时间戳。 */
    private data class Pending(val fileId: String, val aesKey: String, val ts: Long)

    @Volatile
    private var pending: Pending? = null

    /**
     * hook `ok0/m1.e(tj.k)Z`（全局图片上传下发点）。单钩 guard + runCatching 全包，
     * 精确签名命中则 hookMethod，找不到 `tj.k` 或签名漂移则 hookAllMethods("e") 兜底 + 回调打 msig。
     */
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true
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
                    if (!enabled) return
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
     * m1.e before 注入：一次性消费 pending，命中则写 task 的 fileId/aesKey。
     * 进入闸门（enabled=false / pending 无 / TTL 超时）一律直接 return，绝不碰 task。
     */
    private fun inject(param: XC_MethodHook.MethodHookParam) {
        val p = pending ?: return
        if (System.currentTimeMillis() - p.ts > TTL_MS) {
            // TTL 超时：视为无效并清，绝不写 task。
            pending = null
            enabled = false
            LogX.w("$TAG arm expired reason=ttl")
            return
        }
        // 一次性消费：注入前即清，之后即便 m1.e 再被调用也不再补（防多目标串味）。
        pending = null
        enabled = false
        val task = param.args?.getOrNull(0) ?: return
        runCatching { XposedHelpers.setObjectField(task, "field_fileId", p.fileId) }
            .onFailure { LogX.w("$TAG set field_fileId failed: ${it.javaClass.simpleName}") }
        runCatching { XposedHelpers.setObjectField(task, "field_aesKey", p.aesKey) }
            .onFailure { LogX.w("$TAG set field_aesKey failed: ${it.javaClass.simpleName}") }
        // 只打长度，绝不打完整值。
        LogX.i("$TAG injected fileIdLen=${p.fileId.length} aesKeyLen=${p.aesKey.length}")
    }

    /**
     * 由 [FileSender] 每目标发送前调用：反射只读取源坏图的 CDN 参数，成功则装进 pending 并开闸门。
     *
     * 链路（真机已验证）：`d3.O8().a3(talker, msgSvrId)` → `r0.u` → 三参 `g9.c(u, "msg", "")`
     * → map 键 `.msg.img.$cdnbigimgurl` / `.msg.img.$cdnmidimgurl` / `.msg.img.$aeskey`。
     * 全程 runCatching；读不到 / 参数无效 ⇒ skip（清 pending、关闸门），安全降级。
     */
    fun arm(talker: String, msgSvrId: Long, imgLocalId: Long) {
        runCatching {
            if (talker.isBlank() || msgSvrId <= 0L || imgLocalId <= 0L) {
                skip("invalid args")
                return@runCatching
            }
            val cl = loader ?: run { skip("no classloader"); return@runCatching }
            // 1) d3.O8() → a3(talker, msgSvrId) → r0；只读。
            val d3cls = XposedHelpers.findClass("com.tencent.mm.modelimage.d3", cl)
            val o8 = XposedHelpers.callStaticMethod(d3cls, "O8")
            val r0 = XposedHelpers.callMethod(o8, "a3", talker, msgSvrId)
            val u = XposedHelpers.getObjectField(r0, "u") as? String
                ?: run { skip("r0.u null"); return@runCatching }
            // 2) 三参 g9.c(u, "msg", "") → Map；只读 map 取值。
            val g9cls = XposedHelpers.findClass("com.tencent.mm.sdk.platformtools.g9", cl)
            val map = XposedHelpers.callStaticMethod(g9cls, "c", u, "msg", "") as? Map<*, *>
                ?: run { skip("g9.c null"); return@runCatching }
            val fileId = (map[".msg.img.\$cdnbigimgurl"] as? String)?.takeIf { it.isNotBlank() }
                ?: (map[".msg.img.\$cdnmidimgurl"] as? String)?.takeIf { it.isNotBlank() }
            val aesKey = map[".msg.img.\$aeskey"] as? String
            if (fileId.isNullOrBlank()) {
                skip("no fileId")
                return@runCatching
            }
            // 3) 两个都拿到（fileId 非空）⇒ 存 pending、开闸门。
            pending = Pending(fileId, aesKey.orEmpty(), System.currentTimeMillis())
            enabled = true
            LogX.i("$TAG armed fileIdLen=${fileId.length}")
        }.onFailure {
            pending = null
            enabled = false
            LogX.w("$TAG arm failed: ${it.javaClass.simpleName}")
        }
    }

    /** FileSender 发送失败 / 异常时调：清 pending、关闸门。 */
    fun clear() {
        pending = null
        enabled = false
    }

    /** 统一 skip：清 pending、关闸门、只打原因（不打值）。 */
    private fun skip(reason: String) {
        pending = null
        enabled = false
        LogX.w("$TAG arm skip reason=$reason")
    }

    /** 签名漂移时打实际命中的 method sig（take 80），定位用。 */
    private fun sigOf(p: XC_MethodHook.MethodHookParam): String =
        runCatching { p.method.toString().takeLast(80) }.getOrDefault("NA")
}
