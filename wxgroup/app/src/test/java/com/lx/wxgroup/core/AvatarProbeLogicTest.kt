package com.lx.wxgroup.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AvatarProbeLogic] 纯判据回归测试（R42）。
 *
 * 钉死「头像反射探针裁决」两件事：
 * ① firstFailure 在「全成功 / 首步失败 / 中间失败 / 末步失败」下返回正确的首个失败步骤。
 * ② verdict 输出 "OK" / "FAIL at <step>"，与 firstFailure 一致。
 * 这些判据零 Android 依赖，JVM 直接跑。
 */
class AvatarProbeLogicTest {

    private fun allOk(): Map<AvatarProbeLogic.Step, String?> =
        AvatarProbeLogic.Step.entries.associateWith { null }

    @Test
    fun allStepsOkHasNoFailure() {
        assertNull(AvatarProbeLogic.firstFailure(allOk()))
        assertEquals("OK", AvatarProbeLogic.verdict(allOk()))
    }

    @Test
    fun firstStepFailureIsReported() {
        val m = allOk() + (AvatarProbeLogic.Step.FOR_NAME to "ClassNotFoundException: x")
        assertEquals(AvatarProbeLogic.Step.FOR_NAME, AvatarProbeLogic.firstFailure(m))
        assertEquals("FAIL at FOR_NAME", AvatarProbeLogic.verdict(m))
    }

    @Test
    fun middleStepFailureIsReported() {
        val m = allOk() + (AvatarProbeLogic.Step.INVOKE_Z8 to "InvocationTargetException: boom")
        assertEquals(AvatarProbeLogic.Step.INVOKE_Z8, AvatarProbeLogic.firstFailure(m))
        assertEquals("FAIL at INVOKE_Z8", AvatarProbeLogic.verdict(m))
    }

    @Test
    fun lastStepFailureIsReported() {
        val m = allOk() + (AvatarProbeLogic.Step.GET_J to "NoSuchMethodException: j")
        assertEquals(AvatarProbeLogic.Step.GET_J, AvatarProbeLogic.firstFailure(m))
        assertEquals("FAIL at GET_J", AvatarProbeLogic.verdict(m))
    }

    @Test
    fun earliestFailureWins() {
        // 两个步骤同时失败 → 报最早那个
        val m = allOk() +
            (AvatarProbeLogic.Step.GET_B8 to "err1") +
            (AvatarProbeLogic.Step.GET_J to "err2")
        assertEquals(AvatarProbeLogic.Step.GET_B8, AvatarProbeLogic.firstFailure(m))
        assertEquals("FAIL at GET_B8", AvatarProbeLogic.verdict(m))
    }

    @Test
    fun emptyMapIsTreatedAsNoFailure() {
        // 探针从未记录任何步骤（生产里不会发生，但纯函数必须确定）：无失败 → OK
        assertNull(AvatarProbeLogic.firstFailure(emptyMap()))
        assertEquals("OK", AvatarProbeLogic.verdict(emptyMap()))
    }

    // ---------------------------------------------------------------- 一行诊断

    @Test
    fun diagnoseAllOkIsOneLineWithEveryStep() {
        val d = AvatarProbeLogic.diagnose("Lcom/tencent/mm/modelavatar/a1;", allOk())
        assertEquals(
            "cls=Lcom/tencent/mm/modelavatar/a1; FOR_NAME=ok GET_B8=ok INVOKE_B8=ok " +
                "GET_Z8=ok INVOKE_Z8=ok GET_I=ok GET_J=ok -> OK",
            d,
        )
    }

    @Test
    fun diagnoseReportsFailingStepAndExceptionClass() {
        // 模拟 GET_I 失败（其后的 GET_J 未执行到，缺 key）
        val m: Map<AvatarProbeLogic.Step, String?> = mapOf(
            AvatarProbeLogic.Step.FOR_NAME to null,
            AvatarProbeLogic.Step.GET_B8 to null,
            AvatarProbeLogic.Step.INVOKE_B8 to null,
            AvatarProbeLogic.Step.GET_Z8 to null,
            AvatarProbeLogic.Step.INVOKE_Z8 to null,
            AvatarProbeLogic.Step.GET_I to "NoSuchMethodException",
        )
        val d = AvatarProbeLogic.diagnose("Lcom/tencent/mm/modelavatar/a1;", m)
        // 失败步骤带异常类名，其后未执行到的步骤标 ?，裁决 FAIL at GET_I
        assertEquals(
            "cls=Lcom/tencent/mm/modelavatar/a1; FOR_NAME=ok GET_B8=ok INVOKE_B8=ok " +
                "GET_Z8=ok INVOKE_Z8=ok GET_I=NoSuchMethodException GET_J=? -> FAIL at GET_I",
            d,
        )
    }

    @Test
    fun diagnoseMarksUnreachedStepsAsUnknown() {
        // 第 1 步就失败 → 其余 6 步都是 ?
        val m = mapOf(AvatarProbeLogic.Step.FOR_NAME to "ClassNotFoundException")
        val d = AvatarProbeLogic.diagnose("Lcom/tencent/mm/modelavatar/a1;", m)
        assertEquals(
            "cls=Lcom/tencent/mm/modelavatar/a1; FOR_NAME=ClassNotFoundException " +
                "GET_B8=? INVOKE_B8=? GET_Z8=? INVOKE_Z8=? GET_I=? GET_J=? -> FAIL at FOR_NAME",
            d,
        )
    }
}
