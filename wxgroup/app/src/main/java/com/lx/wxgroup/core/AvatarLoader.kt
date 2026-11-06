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

    /** 宿主 ClassLoader：缺了它 [resolve] 里的 `Class.forName` 会落到模块自己的 CL，真机必抛 CNFE */
    @Volatile private var hostCl: ClassLoader? = null

    /** 模块初始化时由 HookEntry 注入宿主 `lpparam.classLoader` */
    fun attach(cl: ClassLoader) { hostCl = cl }

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
            val resolved = resolve()
            if (resolved == null) dead = true
            storage = resolved
            return storage
        }
    }

    /**
     * 首次反射解析：逐步执行并记录每步成败（零副作用地打一行日志）。
     *
     * - 成功 → `LogX.i` 一行：`AvatarLoader 反射链路 OK cls=… FOR_NAME=ok … -> OK`
     * - 失败 → `LogX.w` 一行：`AvatarLoader 初始化失败… cls=… <失败步骤>=<异常类名> … -> FAIL at <步骤>`
     * - 返回 null 由 [storage] 置 [dead] 停用，日志因此只打一次（`adb logcat -s WXGroup` 可看）。
     */
    private fun resolve(): Storage? {
        val errors = LinkedHashMap<AvatarProbeLogic.Step, String?>()

        fun <T> step(s: AvatarProbeLogic.Step, block: () -> T?): T? = try {
            block().also { errors[s] = null }
        } catch (t: Throwable) {
            errors[s] = t.javaClass.simpleName
            null
        }

        val a1c = step(AvatarProbeLogic.Step.FOR_NAME) {
            val cl = hostCl
            if (cl != null) Class.forName(CLS_SUBCORE, false, cl) else Class.forName(CLS_SUBCORE)
        } ?: return fail(errors)
        val b8 = step(AvatarProbeLogic.Step.GET_B8) { a1c.getMethod("B8") }
            ?: return fail(errors)
        val a1 = step(AvatarProbeLogic.Step.INVOKE_B8) {
            b8.invoke(null) ?: throw IllegalStateException("B8() returned null")
        } ?: return fail(errors)
        val z8 = step(AvatarProbeLogic.Step.GET_Z8) { a1c.getMethod("z8") }
            ?: return fail(errors)
        val x = step(AvatarProbeLogic.Step.INVOKE_Z8) {
            z8.invoke(a1) ?: throw IllegalStateException("z8() returned null")
        } ?: return fail(errors)
        val xc = x.javaClass
        val i = step(AvatarProbeLogic.Step.GET_I) { xc.getMethod("i", String::class.java) }
            ?: return fail(errors)
        val j = step(AvatarProbeLogic.Step.GET_J) { xc.getMethod("j", String::class.java) }
            ?: return fail(errors)

        LogX.i("AvatarLoader 反射链路 OK " + AvatarProbeLogic.diagnose(CLS_SUBCORE, errors))
        return Storage(x, i, j)
    }

    private fun fail(errors: Map<AvatarProbeLogic.Step, String?>): Storage? {
        LogX.w("AvatarLoader 初始化失败（头像退回首字兜底） " + AvatarProbeLogic.diagnose(CLS_SUBCORE, errors) +
            " cl=" + (hostCl?.javaClass?.simpleName ?: "NULL(未attach)"))
        return null
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

/**
 * 头像反射的纯判据（零 Android 依赖 → JVM 可直接单测）。
 *
 * [AvatarLoader.resolve] 反射微信头像存储时，把每一步结果记成「步骤 → 异常类名（null=成功）」，
 * 本对象只负责从这份结果里裁决「第一个失败在哪一步」并拼一行诊断，纯函数、无副作用。
 */
object AvatarProbeLogic {

    /** 反射步骤，顺序即 [AvatarLoader.resolve] 的执行顺序。 */
    enum class Step { FOR_NAME, GET_B8, INVOKE_B8, GET_Z8, INVOKE_Z8, GET_I, GET_J }

    /** 返回第一个失败的步骤（error 非 null）；全成功返回 null。 */
    fun firstFailure(errors: Map<Step, String?>): Step? =
        Step.entries.firstOrNull { errors[it] != null }

    /** 裁决串：全成功 `"OK"`，否则 `"FAIL at <step>"`。 */
    fun verdict(errors: Map<Step, String?>): String =
        firstFailure(errors)?.let { "FAIL at ${it.name}" } ?: "OK"

    /**
     * 一行诊断：目标类名 + 每步成败（`ok` / 异常类名 / `?`=未执行到）+ 裁决。
     * 供 `LogX.i` / `LogX.w` 单行输出，`adb logcat -s WXGroup` 直接可见。
     */
    fun diagnose(targetCls: String, errors: Map<Step, String?>): String {
        val steps = Step.entries.joinToString(" ") { s ->
            val e = errors[s]
            when {
                !errors.containsKey(s) -> "${s.name}=?"
                e != null -> "${s.name}=$e"
                else -> "${s.name}=ok"
            }
        }
        return "cls=$targetCls $steps -> ${verdict(errors)}"
    }
}
