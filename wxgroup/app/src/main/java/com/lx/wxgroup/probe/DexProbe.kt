package com.lx.wxgroup.probe

import android.content.Context
import android.os.Build
import com.lx.wxgroup.util.LogX
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.matchers.ClassMatcher

/**
 * DexKit 探测：在 dex 层面（不加载类，不会 OOM）找出所有 RecyclerView.Adapter 子类，
 * 为后续「用特征锁定锚点」打底。
 *
 * 注意：DexKit 的 libdexkit.so 打在**模块 APK** 里，而代码跑在微信进程，
 * 宿主的 nativeLibraryDir 里没有这个 so，System.loadLibrary 会失败。
 * 所以这里手动从模块 APK 内加载（"apk路径!/lib/abi/libdexkit.so"）。
 * 万一加载失败也只影响本探测，UiProbe 那套反射方案完全不依赖 DexKit。
 */
object DexProbe {

    private const val MODULE_PACKAGE = "com.lx.wxgroup"

    fun runAsync(context: Context, hostApkPath: String) {
        Thread {
            runCatching { run(context, hostApkPath) }
                .onFailure { LogX.e("DexProbe: failed", it) }
        }.apply { name = "wxg-dex" }.start()
    }

    private fun run(context: Context, hostApkPath: String) {
        if (!loadNative(context)) {
            LogX.w("DexProbe: libdexkit.so unavailable, skip (UiProbe still works)")
            return
        }

        val bridge = DexKitBridge.create(hostApkPath)
        if (bridge == null) {
            LogX.w("DexProbe: bridge is null for $hostApkPath")
            return
        }

        bridge.use { dex ->
            LogX.i("DexProbe: bridge opened for $hostApkPath")
            val adapters = dex.findClass(
                FindClass.create().matcher(
                    ClassMatcher.create().apply {
                        superClass = "androidx.recyclerview.widget.RecyclerView\$Adapter"
                    }
                )
            )
            LogX.i("DexProbe: RecyclerView.Adapter subclasses = ${adapters.size}")
            var i = 0
            for (c in adapters) {
                if (i++ >= 40) break
                LogX.i("DexProbe:   ADPT ${c.name} (methods=${c.methodCount}, fields=${c.fieldCount})")
            }

            val contact = dex.findClass(
                FindClass.create().matcher(
                    ClassMatcher.create().apply { usingStrings = listOf("wxid_") }
                )
            )
            LogX.i("DexProbe: classes using \"wxid_\" = ${contact.size}")
            var j = 0
            for (c in contact) {
                if (j++ >= 20) break
                LogX.i("DexProbe:   WXID ${c.name}")
            }
        }
    }

    private fun loadNative(context: Context): Boolean {
        runCatching {
            System.loadLibrary("dexkit")
            LogX.i("DexProbe: System.loadLibrary ok")
            return true
        }

        // 宿主进程查不到模块包（Android 11+ 包可见性限制，createPackageContext 直接 NameNotFound），
        // 只能从模块自己的 ClassLoader 反推 APK 路径。
        val moduleApk = moduleApkPath()
        if (moduleApk.isNullOrEmpty()) {
            LogX.w("DexProbe: cannot resolve module apk path")
            return false
        }
        LogX.i("DexProbe: module apk = $moduleApk")

        for (abi in Build.SUPPORTED_ABIS) {
            val path = "$moduleApk!/lib/$abi/libdexkit.so"
            val ok = runCatching { System.load(path) }.isSuccess
            if (ok) {
                LogX.i("DexProbe: loaded $path")
                return true
            }
        }
        LogX.w("DexProbe: no loadable libdexkit.so under $moduleApk")
        return false
    }

    private fun moduleApkPath(): String? {
        var cl: ClassLoader? = DexProbe::class.java.classLoader
        while (cl != null) {
            val p = runCatching { apkFromClassLoader(cl!!) }.getOrNull()
            if (!p.isNullOrEmpty()) return p
            cl = cl.parent
        }
        return null
    }

    private fun apkFromClassLoader(cl: ClassLoader): String? {
        val pathList = findField(cl.javaClass, "pathList")?.get(cl) ?: return null
        val elements = findField(pathList.javaClass, "dexElements")?.get(pathList) as? Array<*> ?: return null
        for (e in elements) {
            e ?: continue
            for (fname in listOf("dexFile", "path", "zip", "zipFile")) {
                val f = findField(e.javaClass, fname) ?: continue
                val v = runCatching { f.get(e) }.getOrNull() ?: continue
                val p = when (v) {
                    is String -> v
                    else -> runCatching { v.javaClass.getMethod("getName").invoke(v) as? String }.getOrNull()
                }
                if (!p.isNullOrEmpty() && p.endsWith(".apk") && p.contains(MODULE_PACKAGE)) return p
            }
        }
        return null
    }

    private fun findField(cls: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = cls
        while (c != null) {
            runCatching {
                val f = c!!.getDeclaredField(name)
                f.isAccessible = true
                return f
            }
            c = c.superclass
        }
        return null
    }
}
