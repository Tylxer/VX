package com.lx.wxgroup.feature

/**
 * 「原生转发」（缺口 G1）的**纯判据层** —— 零 Android 依赖，可跑 JVM 单测。
 *
 * 与 [ForwardLogic] 的分工：[ForwardLogic] 管「菜单归类 / 目标展开 / 走原生还是降级」，
 * 本类管「**原生这条路怎么走**」的全部常量与判据（子类型号、ForwardInfo 字段取值、
 * 失败阶段、失败后回落到哪）。反射执行留在 [ForceForward]。
 *
 * ## 取证依据
 *
 * `docs/_r29_g1_forward_forensics.md`（G1）+ `docs/_r29_g2_msg_object.md` / `docs/_r16_g2_msgsnap.md`（G2）。
 * 每处常量的档位写在各自的 KDoc 上：①=字面证据，②=强推测（附「错了会怎样」）。
 *
 * ## 为什么这些常量必须被单测锁住
 *
 * 它们全是「**错了只有在真机上才看得出来**」的那类判据，而且错法很难查：
 * - 子类型号写错 ⇒ 消息照发，只是没有转发标记（对端看不出区别，用户以为功能好了）；
 * - ForwardInfo 字段填错 ⇒ 同上，或者干脆走到 `t81/g` 的 default 分支发个空壳；
 * - 失败阶段判错 ⇒ 该永久回落的没回落（每条都白试一次原生），或该逐条重试的整个批次都放弃。
 *
 * ## 档位声明（本文件里唯一的 ② 档）
 *
 * - [ForwardInfo.fromImgId] 恒 -1：**没有①档的 CDN imgId 获取路径**（不在 h9 字段表里，
 *   G1 §2.1 只坐实了它有这个语义）。⇒ 图片转发会重传，与现在的降级重发一致；
 *   转发标记由 `q1.h=13` + [ForwardInfo.msgId] 承担。
 * - [ForwardInfo.svrId] 填 `field_msgSvrId` 的十进制字符串：字段 `e` 是 `String` 而
 *   `field_msgSvrId` 是 `J`（long），**格式是推断的**。错了最多服务端忽略它
 *   （`t81/g.l` 的 h==4 分支只读 `m.b`，不读 `m.e`）。
 */
object ForwardNativeLogic {

    // ------------------------------------------------------------------ q1.h 子类型

    /**
     * `hl0/q1.h`（发送子类型，int，**无 setter，直写字段**）的取值。
     *
     * ① `docs/_r29_g1_forward_forensics.md` §2.4 值表（`t81/g.l` + `qf4/o4.O8/Q8` + `MsgRetransmitUI.z6` 三处交叉坐实）。
     */
    const val SUBTYPE_RESEND = 2            // 重发/引用
    const val SUBTYPE_TEXT_FORWARD = 4      // 文本转发（吃 m.b = forwardMsgId）
    const val SUBTYPE_TEXT_PLAIN = 5        // 普通文本（吃 q1.g Object）
    const val SUBTYPE_IMAGE_FORWARD = 13    // 图片转发
    const val SUBTYPE_MULTI_IMAGE = 19      // 多图转发

    /** 不支持原生转发时的「不设 h」值（0 = `q1.<init>` 未初始化的默认值，文本会走 default 空 `q0`） */
    const val SUBTYPE_NONE = 0

    /** 原生链路能处理的子类型。NONE = 这条消息别走原生 */
    enum class SubType { TEXT_FORWARD, IMAGE_FORWARD, MULTI_IMAGE, NONE }

    /**
     * 消息种类 → 子类型。
     *
     * ⚠️ 只放行 **TEXT 与 IMAGE**：其余（视频/语音/文件/名片/位置/链接）G1 §7 明确排除
     * （视频要 `modelimage/o3.j()`+`h=20`、普通文件要 `qf4/r5`+CDN 上传，都没取证）
     * ⇒ 让它们走原生只会撞壁，不如直接降级。**不许为了「覆盖全」放宽这里。**
     */
    fun subTypeOf(kind: ForwardLogic.Kind, multiImage: Boolean = false): SubType = when (kind) {
        ForwardLogic.Kind.TEXT -> SubType.TEXT_FORWARD
        ForwardLogic.Kind.IMAGE -> if (multiImage) SubType.MULTI_IMAGE else SubType.IMAGE_FORWARD
        else -> SubType.NONE
    }

    /** 子类型 → 要写进 `q1.h` 的 int。[SubType.NONE] 返回 [SUBTYPE_NONE]（= 别设、别发） */
    fun subTypeValue(sub: SubType): Int = when (sub) {
        SubType.TEXT_FORWARD -> SUBTYPE_TEXT_FORWARD
        SubType.IMAGE_FORWARD -> SUBTYPE_IMAGE_FORWARD
        SubType.MULTI_IMAGE -> SUBTYPE_MULTI_IMAGE
        SubType.NONE -> SUBTYPE_NONE
    }

    /**
     * 是否值得为这条消息试原生。
     *
     * 与 [subTypeOf] 的区别：这里只看种类（**不查原料**）。原料（正文 / 本地文件）在发送前
     * 才检查 —— 计划层按种类挑路、发送层按原料兜底，这样「种类支持但这次没拿到原料」
     * 能落成 [FailStage.MATERIAL] 并回落，而不是在计划层就静默 SKIP。
     */
    fun supportsNative(kind: ForwardLogic.Kind): Boolean = subTypeOf(kind) != SubType.NONE

    // ------------------------------------------------------------------ ForwardInfo（hl0/m）

    /** `m.a` 的 `<init>` 默认值：没有源图 CDN imgId（① G1 §2.1） */
    const val NO_IMG_ID = -1L

    /** `m.b` 的 `<init>` 默认值：没拿到源 msgId */
    const val NO_MSG_ID = 0L

    /** `m.c` 的 `<init>` 默认值：空串（不是 null） */
    const val EMPTY_SNS_LOCAL_ID = ""

    /**
     * `hl0/m`（ForwardInfo）六个字段的取值规格 —— 与微信字段一一对应，**顺序即字段名 a..f**。
     *
     * ① 字段语义由 `hl0/m.toString` 明文钉死（G1 §2.1）。默认值全部对齐 `m.<init>`，
     * 这样「拿不到就填默认」=「和微信自己 new 一个空的 m 一样」，不会产生半截状态。
     */
    data class ForwardInfo(
        /** `m.a` long：forwardFromImgId（源图 CDN imgId）。**恒 [NO_IMG_ID]**，见类注释②档 */
        val fromImgId: Long = NO_IMG_ID,
        /** `m.b` long：forwardMsgId（源消息 msgId，`h9.getMsgId()`） */
        val msgId: Long = NO_MSG_ID,
        /** `m.c` String：forwardSnsLocalId（朋友圈本地 id）。本模块不转朋友圈 ⇒ 恒空串 */
        val snsLocalId: String = EMPTY_SNS_LOCAL_ID,
        /** `m.d` String：forwardMsgUuid。无①档获取路径 ⇒ 恒 null（= `<init>` 默认） */
        val uuid: String? = null,
        /** `m.e` String：forwardMsgSvrId。`field_msgSvrId` 的十进制字符串，拿不到为 null */
        val svrId: String? = null,
        /** `m.f` String：talkerUsername（源会话 talker） */
        val talker: String = "",
    )

    /**
     * [ForwardLogic.Snapshot] → ForwardInfo 六字段。
     *
     * 缺省纪律（**拿不到就退回 `<init>` 默认值，不猜、不填 0 之外的占位**）：
     * - msgId 拿不到 → [NO_MSG_ID]（0，与 `m.<init>` 一致）；
     * - msgSvrId ≤ 0 → null；
     * - talker 空 → 空串（微信侧 `m.f` 默认就是 null，写空串与 null 都只是「没源会话」）。
     */
    fun forwardInfoOf(snap: ForwardLogic.Snapshot): ForwardInfo = ForwardInfo(
        fromImgId = NO_IMG_ID,
        msgId = snap.msgId,
        snsLocalId = EMPTY_SNS_LOCAL_ID,
        uuid = null,
        svrId = svrIdStringOf(snap.msgSvrId),
        talker = snap.sourceChat,
    )

    /** `field_msgSvrId`(J) → `m.e`(String)。非正数（没拿到）返回 null，不填 "0" 这种假值 */
    fun svrIdStringOf(msgSvrId: Long): String? = if (msgSvrId > 0L) msgSvrId.toString() else null

    /**
     * 这份 ForwardInfo 是否**真的带得动转发标记**。
     *
     * 判据：`m.b`(源 msgId) > 0 **或** `m.e`(源 svrId) 非空 —— 两个都没有时挂上去也是个空壳，
     * 微信 `t81/g.l` 的 h==4 分支会读 `m.b` 拿 0 ⇒ 与普通文本无差别。
     * 这时**仍然发**（降级也比不发好），但日志要能区分（见 [FailStage] 之外的 `marked` 标记）。
     */
    fun hasForwardMark(info: ForwardInfo): Boolean = info.msgId > 0L || !info.svrId.isNullOrBlank()

    // ------------------------------------------------------------------ 失败分阶段

    /**
     * 原生链路的失败阶段。日志按它分口径（`FORWARD native fail stage=...`），
     * 真机上「类没找到」和「找到了但没发出去」是两种完全不同的修法。
     */
    enum class FailStage {
        /** 没失败 */
        NONE,

        /** 类找不到（`hl0.r1` / `hl0.m` / `hl0.o1`）—— 版本变了或没 prepare */
        CLASS,

        /** 类在但方法/字段不在（混淆名变了）—— 版本变了 */
        METHOD,

        /** 造不出对象（`new m()` / `r1.b()` 返回 null / 枚举取不到） */
        CONSTRUCT,

        /** 原料不足（正文为空、图片文件不存在）—— 与链路无关，是这次长按没抓全 */
        MATERIAL,

        /** 真正发送阶段失败（`q1.a()` 返回 null、`l1.a()` 返回 false、抛异常） */
        SEND,
    }

    /** 一次原生尝试的结果 */
    data class NativeResult(
        val ok: Boolean,
        val stage: FailStage = FailStage.NONE,
    ) {
        companion object {
            fun fail(stage: FailStage): NativeResult = NativeResult(false, stage)
            fun ok(): NativeResult = NativeResult(true, FailStage.NONE)
        }
    }

    /**
     * 结构性失败 = 这条链路**在本批次剩下的目标上也不会变好**（类/方法/构造层的问题），
     * ⇒ 后面每个目标直接走降级，不再白试一次原生。
     *
     * [FailStage.SEND] 不是结构性失败：入队失败可能只是这一次的网络/状态问题，
     * 下一个目标照样值得试原生。
     */
    fun isStructural(stage: FailStage): Boolean = when (stage) {
        FailStage.CLASS, FailStage.METHOD, FailStage.CONSTRUCT -> true
        FailStage.NONE, FailStage.MATERIAL, FailStage.SEND -> false
    }

    /** 失败后 [ForwardLogic.NativeState] 怎么迁移（结构性失败 ⇒ 本批次判 REJECTED，不再试） */
    fun nativeStateAfter(
        stage: FailStage,
        current: ForwardLogic.NativeState,
    ): ForwardLogic.NativeState =
        if (isStructural(stage)) ForwardLogic.NativeState.REJECTED else current

    /**
     * 原生失败后回落到哪 —— 与 [ForwardLogic.planFor] 的降级段**同一套判据**
     * （有文本重发文本、有本地文件且能发就重发文件、都没有才 SKIP），
     * 只是去掉了 `AVAILABLE` 那一支。抽出来是为了让「回落」也能被单测锁住：
     * 真机上最贵的错是**原生失败后什么都不做**，用户只看到「失败 N」。
     */
    fun fallbackStrategy(snap: ForwardLogic.Snapshot): ForwardLogic.Strategy {
        if (snap.text.isNotBlank()) return ForwardLogic.Strategy.RESEND_TEXT
        if (snap.localPath.isNotBlank() && snap.fileExists && ForwardLogic.canResendFile(snap.kind)) {
            return ForwardLogic.Strategy.RESEND_FILE
        }
        return ForwardLogic.Strategy.SKIP
    }
}
