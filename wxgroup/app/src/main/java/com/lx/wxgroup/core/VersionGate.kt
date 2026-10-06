package com.lx.wxgroup.core

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * 版本门控。宁可功能失效，也不能让微信闪退。
 *
 * 微信每次升级都可能重排混淆，本模块只在白名单版本内打钩子。
 * 版本不匹配时静默退出，一行桩都不打。
 */
object VersionGate {

    const val HOST_PACKAGE = "com.tencent.mm"

    /** 微信有多个进程（:push / :tools / :appbrand 等），只在主进程干活 */
    const val HOST_MAIN_PROCESS = "com.tencent.mm"

    enum class Channel(val label: String) {
        GOOGLE_PLAY("gp"),
        MAINLAND("cn"),
    }

    data class Target(
        val channel: Channel,
        val versionName: String,
        /** 填 0 表示不校验 versionCode。确认实机值后建议补上 */
        val versionCode: Long = 0L,
        /** 填 null 表示不校验签名。建议跑起来后填上真实签名 */
        val signatureSha256: String? = null,
    )

    data class Version(val versionName: String, val versionCode: Long)

    sealed class Verdict {
        data class Ok(val target: Target, val actual: Version) : Verdict()
        data class Reject(val reason: String) : Verdict()
    }

    /**
     * 白名单。要支持多个版本就在这里加条目，
     * 每个条目对应一套独立的 DexKit 特征，别指望一套特征吃遍所有版本。
     */
    val whitelist: List<Target> = listOf(
        // 实机读到的版本（device 467cf5bc）：versionName=8.0.48, versionCode=2588
        // installerPackageName=null，渠道无法自动判定 —— 确认是国内版就把 channel 改成 MAINLAND
        Target(
            Channel.GOOGLE_PLAY,
            "8.0.48",
            versionCode = 2588L,
            signatureSha256 = "0fe4ff85c215918396dadc7cd8ce6963339af33d37751a56e54c7206b63a3c7c",
        ),
        // 原定目标版本，实机尚未安装，先留着
        Target(Channel.GOOGLE_PLAY, "8.0.72"),
    )

    fun isMainProcess(processName: String?): Boolean = processName == HOST_MAIN_PROCESS

    fun check(version: Version, currentSignature: String? = null): Verdict {
        val matched = whitelist.firstOrNull { it.versionName == version.versionName }
            ?: return Verdict.Reject(
                "version ${version.versionName} not whitelisted, " +
                    "supported: ${whitelist.joinToString { it.versionName }}"
            )

        if (matched.versionCode != 0L && matched.versionCode != version.versionCode) {
            return Verdict.Reject(
                "versionCode mismatch: actual=${version.versionCode}, expected=${matched.versionCode}"
            )
        }

        if (matched.signatureSha256 != null && currentSignature != matched.signatureSha256) {
            return Verdict.Reject("signature mismatch: $currentSignature")
        }

        return Verdict.Ok(matched, version)
    }

    /** 缓存 key：版本一变，DexKit 的搜索结果全部作废 */
    fun cacheKey(version: Version): String = "${version.versionName}_${version.versionCode}"

    /**
     * 取出宿主签名指纹。M0 阶段先把它打进日志，
     * 你拿到真实值后再填回 whitelist，避免别人拿篡改包给你喂假数据。
     */
    fun hostSignatureSha256(context: Context): String? = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(HOST_PACKAGE, PackageManager.GET_SIGNATURES)
        val signature = info.signatures?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
    }.getOrNull()
}
