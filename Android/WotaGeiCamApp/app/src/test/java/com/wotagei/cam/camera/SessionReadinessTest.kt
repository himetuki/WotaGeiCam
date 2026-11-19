package com.wotagei.cam.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R01/R02 纯判据：就绪必须同时满足配置 + repeating 接纳；录制窗口必须无条件关闭等号探测。 */
class SessionReadinessTest {

    @Test
    fun `onlyConfiguredAndSubmittedIsReady`() {
        assertFalse(CameraSessionReadiness().ready)
        assertFalse(CameraSessionReadiness(configured = true).ready)
        assertFalse(CameraSessionReadiness(repeatingSubmitted = true).ready)
        assertTrue(CameraSessionReadiness(configured = true, repeatingSubmitted = true).ready)
    }

    @Test
    fun `recordingWindowBlocksExposureProbeEvenWithoutDirectSurface`() {
        val base = ProbeInputs(deviceOpen = true, manualAe = true, correctionAuto = true, applicable = true)
        assertTrue(allowed(base))
        assertFalse("GPU 录制没有 extraSurface，仍必须被窗口挡住", allowed(base.copy(recordingWindow = true)))
        assertFalse("DIRECT 录制面在场必须挡住", allowed(base.copy(directSurfacePresent = true)))
        assertFalse("未打开设备不得探测", allowed(base.copy(deviceOpen = false)))
        assertFalse("自动 AE 不得探测", allowed(base.copy(manualAe = false)))
        assertFalse("纠偏关不得探测", allowed(base.copy(correctionAuto = false)))
        assertFalse("配置不适用不得探测", allowed(base.copy(applicable = false)))
        assertFalse("已闩锁不得重探", allowed(base.copy(alreadyLatched = true)))
    }

    private fun allowed(i: ProbeInputs): Boolean = exposureProbeAllowed(
        deviceOpen = i.deviceOpen,
        directSurfacePresent = i.directSurfacePresent,
        recordingWindow = i.recordingWindow,
        manualAe = i.manualAe,
        correctionAuto = i.correctionAuto,
        correctionApplicable = i.applicable,
        alreadyLatched = i.alreadyLatched
    )

    private data class ProbeInputs(
        val deviceOpen: Boolean = false,
        val directSurfacePresent: Boolean = false,
        val recordingWindow: Boolean = false,
        val manualAe: Boolean = false,
        val correctionAuto: Boolean = false,
        val applicable: Boolean = false,
        val alreadyLatched: Boolean = false
    )
}
