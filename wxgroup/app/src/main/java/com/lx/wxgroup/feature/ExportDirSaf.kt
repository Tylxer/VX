package com.lx.wxgroup.feature

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lx.wxgroup.core.ActivityResultRelay
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import java.io.File

/**
 * 朋友圈导出目录的 SAF 授权入口（需求 6.4「路径授权（v6.3 预留）」）。
 *
 * ## 干什么
 * 用系统文件选择器（`ACTION_OPEN_DOCUMENT_TREE`）让用户挑一个文件夹，
 * 授权后把导出根目录指过去，写到**用户自己看得见**的位置。
 *
 * ## ⛔ 为什么不做成「整个导出层改走 DocumentFile」
 * 现有导出是 `File` 直写（文本 + 图片拷贝 + 按文件名去重），其中
 * `scanExportedIds` 要**列目录**做幂等去重。换成 `DocumentFile` 后每次列目录都是
 * 跨进程 IPC，几千条动态的规模下性能不可接受，而且要重写整个 IO 层。
 * 而 M7 已真机验证：默认路径 `/storage/emulated/0/Documents/WXGroup/moments`
 * **微信进程可直接写、用户可直接看到**（`docs/_r19_M4M6M7_真机验证.md`），
 * 也就是说「写到用户可见位置」这个目标**已经达成**，缺的只是「让用户改到别处」的入口。
 *
 * ⇒ 本模块的职责边界：**挑目录 + 拿权限 + 换成可用路径 + 验证可写**。
 *   实际写入仍然走已验证的 `File` 层。
 *
 * ## ⛔ 硬约束：解析出的路径必须回验，绝不「猜完就写」
 * `ACTION_OPEN_DOCUMENT_TREE` 给的是 `content://` Uri，**没有标准 API 能拿到文件路径**。
 * 这里按系统文档提供器的约定解析 docId（见 [docIdToPath]），
 * 但解析结果**必须**再过 `exists && isDirectory && 真的能建文件` 三道回验，
 * 任何一道不过就拒绝并明确告诉用户，绝不静默落到一个错路径上。
 */
object ExportDirSaf {

    private const val TAG = "EXPSAF"

    /**
     * 把系统文档提供器的 tree docId 解析成文件路径。
     *
     * 约定形态：`primary:Documents/WXGroup` ⇒ `/storage/emulated/0/Documents/WXGroup`。
     *
     * ⛔ **只认 `primary:`**（内置主存储）。外置 SD 卡的 docId 是卷标（如 `ABCD-1234:`），
     * 没有可靠的路径映射规则 —— 与其猜一个错路径，不如返回 null 走「不支持」提示。
     *
     * @param docId `Uri.lastPathSegment`，形如 `primary:Documents/WXGroup` 或 `primary:`
     * @return 解析出的绝对路径；无法解析时返回 **null**（调用方负责提示，不要兜底成默认值）
     */
    fun docIdToPath(docId: String?): String? {
        if (docId.isNullOrBlank()) return null
        // 有些厂商会带 "/" 结尾或多段，统一只认第一段冒号前的卷标
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val rel = docId.substring(colon + 1)
        if (!volume.equals("primary", ignoreCase = true)) return null
        val base = "/storage/emulated/0"
        if (rel.isBlank()) return base
        return "$base/${rel.trim('/')}"
    }

    /**
     * 发起目录选择。
     *
     * ⚠️ 必须能拿到宿主 Activity（微信在前台），否则 [ActivityResultRelay.launch] 返回 false。
     *
     * @param onDone 给用户的一句话结果（主线程），用于 toast
     */
    fun launch(ctx: Context, onDone: (String) -> Unit) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // 持久授权：拿到后跨进程重启仍可写，否则下次冷启就失效
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        val ok = ActivityResultRelay.launch(
            ActivityResultRelay.REQ_DIR,
            intent
        ) { _, resultCode, uri ->
            onDone(handleResult(ctx, uri, resultCode))
        }
        if (!ok) onDone("拿不到当前界面，请先切回微信再试")
    }

    /**
     * 处理选择结果：取持久权限 → 解析路径 → **回验可写** → 落配置。
     *
     * @return 给用户的一句话
     */
    private fun handleResult(ctx: Context, uri: Uri?, resultCode: Int): String {
        if (uri == null) {
            LogX.i("$TAG cancelled result=$resultCode")
            return "已取消"
        }
        // 持久授权：没有它，下次冷启后权限就没了（本模块实际写文件用 File 层，
        // 但权限留着，将来若改走 DocumentFile 才有得用）
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure {
            LogX.w("$TAG takePersistable failed: ${it.javaClass.simpleName}")
        }
        runCatching {
            ctx.contentResolver.persistedUriPermissions.size
        }.onSuccess { LogX.i("$TAG persisted=$it") }

        val docId = uri.lastPathSegment
        val path = docIdToPath(docId)
        if (path == null) {
            LogX.w("$TAG unsupported docId=$docId (authority=${uri.authority})")
            return "这个目录不受支持（只支持手机内置存储），请选「内部存储」下的文件夹"
        }

        // ⛔ 三道回验，缺一不可
        val dir = File(path)
        if (!dir.exists()) return "目录不存在：$path"
        if (!dir.isDirectory) return "选中的不是文件夹：$path"

        val probe = runCatching {
            val t = File(dir, ".wxgroup_write_probe")
            if (!t.createNewFile()) return@runCatching false
            t.delete()
            true
        }.getOrElse {
            LogX.w("$TAG probe failed: ${it.javaClass.simpleName}")
            false
        }
        if (!probe) return "目录不可写：$path"

        Kv.putString(Keys.MOMENTS_EXPORT_PATH, path)
        // 立刻生效，不等 CFG_TTL_MS 自然过期（用户刚改完就想看到新路径）
        runCatching { MomentsExport.refresh() }
        LogX.i("$TAG export dir => $path")
        return "已设为导出目录：$path"
    }
}
