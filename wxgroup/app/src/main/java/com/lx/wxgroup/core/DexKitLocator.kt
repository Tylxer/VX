package com.lx.wxgroup.core

import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.util.LogX

/**
 * DexKit 定位器骨架（M0 阶段不实际调用 DexKit）。
 *
 * 设计要点：
 * 1. GP 版微信是多 APK（App Bundle），代码大头在 split apk 里，
 *    只用 appInfo.sourceDir 会漏掉绝大部分 dex —— 这是最容易踩的坑。
 * 2. 扫描开销大，必须放到子线程，结果必须缓存，不能每次冷启动都扫。
 * 3. 任何一个锚点定位失败都不能抛异常，只能标记为「该能力不可用」。
 */
class DexKitLocator(private val lpparam: XC_LoadPackage.LoadPackageParam) {

    /**
     * base apk + 所有 split apk。M1 用 DexKit 时，优先尝试
     * DexKitBridge.create(lpparam.classLoader, true)，用 cookie 方式覆盖所有已加载的 dex；
     * 只有当这条路走不通时，才退回到用这里的路径列表逐个 create。
     */
    fun apkPaths(): List<String> {
        val info = lpparam.appInfo
        val paths = ArrayList<String>(8)
        val base = info.sourceDir
        if (!base.isNullOrEmpty()) paths.add(base)
        info.splitSourceDirs?.filterNotNull()?.forEach { paths.add(it) }
        return paths
    }

    /**
     * M0：只把宿主 APK 布局打印出来，供你确认拉取是否完整。
     * M1：在这里接入 DexKitBridge，返回 Anchor -> 序列化后的 DexMethodDescriptor 映射，
     *     并把结果落到磁盘缓存，下次启动直接反序列化。
     */
    fun locate(): Map<Anchor, String> {
        val paths = apkPaths()
        LogX.i("host apk count=${paths.size}")
        paths.forEachIndexed { index, path -> LogX.i("  [$index] $path") }
        LogX.i("M0: DexKit not wired yet, skip locating.")
        return emptyMap()
    }

    companion object {
        /** M1 接入后，缓存文件放宿主 filesDir 下，跟随 VersionGate 的版本 key 失效 */
        const val CACHE_FILE = "anchors.json"
    }
}
