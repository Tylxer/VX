package com.lx.wxgroup.core

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * 版本门控。宁可功能失效，也不能让微信闪退。
 *
 * 微信每次升级都可能重排混淆，本模块只在白名单版本内打钩子。
 * 版本不匹配时不打任何桩，并打一条 `LogX.w("DISABLED: <reason>")` 后退出
 * （见 HookEntry.kt 的 `Verdict.Reject` 分支）—— 便于排查，不是无声无息。
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
        // ⛔ 2026-10-30 删除：原 8.0.72 条目只给了 channel + versionName，
        // versionCode 取默认 0L、signatureSha256 取默认 null。而 check() 里两条校验分别是
        // `if (matched.versionCode != 0L && …)` 与 `if (matched.signatureSha256 != null && …)`，
        // 两个前置条件都为假 ⇒ 整段短路 ⇒ 该条目等价于「无条件 Ok」。
        // 这是 fail-open：微信升到 8.0.72 时闸门放行，但锚点仍是 Anchors848 里按 8.0.48
        // 硬编码的常量、DexKitLocator 的跨版本自动定位尚未接入 ⇒ 钩子全挂不上，
        // 最坏反射取到错对象导致微信闪退 —— 正与本文件开头「宁可功能失效也不能闪退」相反。
        // 现在版本不匹配会得到 Reject("version 8.0.72 not whitelisted, supported: 8.0.48")。
        //
        // 以后要支持 8.0.72：必须**连同锚点一起**加（先取证该版本的类名/方法/字段，
        // 再按版本分派，Anchors848 只覆盖 8.0.48），否则加回来就是重开这个 fail-open。
        // ⛔ 不要用「versionCode=0 表示不校验」这种隐式写法表达「临时放行」——
        // 这就是本次这个坑的成因。取证期需要临时放行：本地改、不入库，
        // 或显式加一个 allowIncomplete 字段并在日志里打 "gate ok (INCOMPLETE TARGET)"。
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
