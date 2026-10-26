package com.lx.wxgroup.core

import android.graphics.Bitmap
import android.util.LruCache
import android.widget.ImageView
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX
import java.lang.reflect.Method
import java.util.concurrent.Executors

/**
 * 头像加载器（R29-2）：反射微信 `AvatarStorage` 取 Bitmap。
 *
 * 取证结论见 `docs/_r29_avatar_entry.md`：
 * - 服务类 `SubCoreAvatar` = `com.tencent.mm.modelavatar.a1`，单例 getter `B8()`
 * - 存储类 `AvatarStorage` = `com.tencent.mm.modelavatar.x`，由 `a1.z8()` 懒初始化返回
 * - 主线程只允许 `x.i(wxid)`（内存 LruCache 查表）；`x.j(wxid)` 是 1080 磁盘解码，**严禁进主线程**
 * - ⛔ 微信返回的 Bitmap 是其共享 LruCache 引用，**绝不 recycle**（回收会导致微信自己崩）
 *
 * 优先级：模块 LruCache → 微信 `x.i` → 后台 `x.j` → 首字兜底（调用方负责）。
 * 纯判据抽在 [AvatarLoadLogic]（零 Android 依赖，见 `AvatarLoadLogicTest`）。
 */
object AvatarLoader {

    private const val CLS_SUBCORE = "com.tencent.mm.modelavatar.a1"

    /** 模块侧二级缓存上限：按字节计（KB），8MB —— 兜住少量 1080 头像或大量小图 */
    private const val CACHE_KB = 8 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    /** 磁盘解码专用单线程池（`x.j` 是磁盘 IO + 1080 decode，绝不能进主线程） */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wxgroup-avatar").apply { isDaemon = true }
    }

    /** 反射解析结果：只解析一次；失败后 [dead] 置位，不再重试、日志只 warn 一次 */
    private class Storage(val x: Any, val i: Method, val j: Method)

    private val lock = Any()
    @Volatile private var storage: Storage? = null
    @Volatile private var dead = false

    private fun storage(): Storage? {
        if (dead) return null
        storage?.let { return it }
        synchronized(lock) {
            if (dead) return null
            storage?.let { return it }
            storage = try {
                val a1c = Class.forName(CLS_SUBCORE)
                val a1 = a1c.getMethod("B8").invoke(null)
                val x = a1c.getMethod("z8").invoke(a1)
                val xc = x.javaClass
                Storage(
                    x = x,
                    i = xc.getMethod("i", String::class.java),
                    j = xc.getMethod("j", String::class.java),
                )
            } catch (t: Throwable) {
                dead = true
                LogX.w("AvatarLoader 初始化失败（头像退回首字兜底）: ${t.javaClass.simpleName}")
                null
            }
            return storage
        }
    }

    /** 主线程内存查表：模块 LruCache → 微信 `x.i`。未命中返回 null。 */
    fun getCached(wxid: String): Bitmap? {
        if (wxid.isBlank()) return null
        cache.get(wxid)?.let {
            if (it.isRecycled) cache.remove(wxid) else return it
        }
        val s = storage() ?: return null
        val bm = runCatching { s.i.invoke(s.x, wxid) as? Bitmap }.getOrNull() ?: return null
        if (bm.isRecycled) return null
        cache.put(wxid, bm)
        return bm
    }

    /**
     * 绑定到 [ImageView]：内存 → 磁盘异步 → 兜底。
     * - [onLoaded] 只在「真头像可用且 tag 校验通过」时回调（调用方据此隐藏首字层）。
     * - 内存 miss 时立刻返回，首字兜底保持可见，异步回来再覆盖。
     */
    fun bind(imageView: ImageView, wxid: String, onLoaded: () -> Unit) {
        if (wxid.isBlank()) return
        imageView.tag = wxid // 复用校验锚：异步回调里比对，防止列表行复用错位

        val hit = getCached(wxid)
        if (AvatarLoadLogic.decide(hit != null) == AvatarLoadLogic.Action.SET_IMAGE) {
            imageView.setImageBitmap(hit)
            onLoaded()
            return
        }
        loadDiskAsync(imageView, wxid, onLoaded)
    }

    private fun loadDiskAsync(imageView: ImageView, wxid: String, onLoaded: () -> Unit) {
        val s = storage() ?: return
        io.execute {
            val bm = runCatching { s.j.invoke(s.x, wxid) as? Bitmap }.getOrNull()
            UiKit.runOnMain {
                val action = AvatarLoadLogic.afterDisk(
                    tagMatches = AvatarLoadLogic.tagMatches(imageView.tag, wxid),
                    bitmapOk = bm != null && !bm.isRecycled,
                )
                if (action == AvatarLoadLogic.Action.SET_IMAGE) {
                    cache.put(wxid, bm!!)
                    imageView.setImageBitmap(bm)
                    onLoaded()
                }
                // FALLBACK：行已复用 / 拿不到图 → 保持首字兜底，静默
            }
        }
    }
}

/**
 * 头像加载的纯判据（零 Android 依赖 → 可直接跑 JVM 单测）。
 *
 * 状态机：内存（模块缓存 / 微信缓存）→ 磁盘异步 → 兜底。
 */
object AvatarLoadLogic {

    enum class Action { SET_IMAGE, LOAD_DISK_ASYNC, FALLBACK }

    /** 内存查表后：命中就设图，否则转磁盘异步 */
    fun decide(memoryHit: Boolean): Action =
        if (memoryHit) Action.SET_IMAGE else Action.LOAD_DISK_ASYNC

    /** 磁盘加载回来后的裁决：行是否已被复用 / 图是否可用 */
    fun afterDisk(tagMatches: Boolean, bitmapOk: Boolean): Action = when {
        !tagMatches -> Action.FALLBACK
        bitmapOk -> Action.SET_IMAGE
        else -> Action.FALLBACK
    }

    /** 列表行复用错位校验：view 当前绑定的 wxid 是否还是请求时的那个 */
    fun tagMatches(viewTag: Any?, wxid: String): Boolean = viewTag == wxid
}
