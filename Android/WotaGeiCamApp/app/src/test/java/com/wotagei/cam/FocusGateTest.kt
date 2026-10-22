package com.wotagei.cam

import android.hardware.camera2.CaptureRequest
import com.wotagei.cam.core.adjustableFocus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #44 的判据：定焦镜头（只有 AF_MODE_OFF、又没有手动屈光度域）不该出现在对焦入口上。
 * 纯函数单测，不需要真造一个 CameraAbility。
 */
class FocusGateTest {

    private val off = CaptureRequest.CONTROL_AF_MODE_OFF
    private val auto = CaptureRequest.CONTROL_AF_MODE_AUTO
    private val continuous = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO

    @Test
    fun fixedFocusLensHasNoAdjustableFocus() {
        assertFalse(setOf(off).adjustable(0f))
        assertFalse(emptySet<Int>().adjustable(0f))
    }

    @Test
    fun manualDiopterRangeMakesTheEntryUseful() {
        // 定焦但给了屈光度域（少数 HAL 这么报）：滑杆有意义，入口留着
        assertTrue(setOf(off).adjustable(2.5f))
    }

    @Test
    fun anyAutoStyleModeShowsTheEntry() {
        assertTrue(setOf(off, auto).adjustable(0f))
        assertTrue(setOf(continuous).adjustable(0f))
        assertTrue(setOf(auto, off).adjustable(-1f))
    }

    private fun Set<Int>.adjustable(diopter: Float): Boolean = adjustableFocus(this, diopter)
}
