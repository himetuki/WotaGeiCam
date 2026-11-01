package com.wotagei.cam

import android.hardware.camera2.CaptureRequest
import com.wotagei.cam.camera.RequestApplier
import com.wotagei.cam.core.AeMode
import com.wotagei.cam.core.AfMode
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.FpsPick
import com.wotagei.cam.core.RangeI
import com.wotagei.cam.core.Rect as WotaRect
import com.wotagei.cam.core.Stabilize
import com.wotagei.cam.core.WbPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * exposurePlan 消费纠偏字段的集成守卫（2026-11-01 用户定版，docs/plan/31）。
 *
 * 钉三件事：①手动等号档**永不发等号值**（探测量程除外）；②纠偏只动 SENSOR_EXPOSURE_TIME——
 * AE 状态、帧周期、ISO 全部原样；③总线档位值原样透传（发送侧纠偏不改用户快门）。
 */
class ExposureCorrectionPlanTest {

    private fun state() = RequestApplier.State(
        activeArray = WotaRect(0, 0, 4000, 3000),
        isoMin = 100, isoMax = 16000,
        exposureMinNs = 1_000_000L, exposureMaxNs = 667_847_000L,
        evMinSteps = -4, evMaxSteps = 4,
        fpsRanges = listOf(RangeI(5, 30)),
        maxRegionsAf = 1, maxRegionsAe = 1,
        manualFocusMaxDiopter = 10f, zoomMin = 1f, zoomMax = 8f,
        useZoomRatio = false, flashAvailable = true, oisAvailable = true, eisAvailable = true,
        highSpeed = false, eisBlockedBySize = false
    )

    private fun snapshot(
        shutterNs: Long,
        fps: Int = 25,
        auto: Boolean = true,
        manualNs: Long = 0L,
        deltaNs: Long? = null,
        probeActive: Boolean = false
    ) = RequestApplier.Snapshot(
        aeMode = AeMode.MANUAL, iso = 800, shutterNs = shutterNs, evSteps = 0,
        fps = FpsPick(fps, fps, true), afMode = AfMode.CONTINUOUS_VIDEO, afModeOverride = null,
        focusDiopter = 0f, zoom = 1f, wbPreset = WbPreset.AUTO, kelvin = 5500, tint = 0,
        flashOverride = null, flash = Flash.OFF, stabilize = Stabilize.OFF,
        afRegions = null, aeRegions = null, afTrigger = null,
        correctionAuto = auto, correctionManualNs = manualNs, readoutDeltaNs = deltaNs,
        probeActive = probeActive, fpsTarget = fps
    )

    private val t24 = 41_666_666L
    private val t25 = 40_000_000L

    @Test
    fun equalTierAutoWithoutLatchSendsFivePercentFloor() {
        val plan = RequestApplier.exposurePlan(state(), snapshot(t25))
        assertEquals("1/25@25 默认自动：满窗 − 5% 下限 2ms = 38ms，永不发等号值", 38_000_000L, plan.exposureNs)
        assertEquals("帧周期 = 节奏真值，不许动", t25, plan.frameDurationNs)
        assertEquals(CaptureRequest.CONTROL_AE_MODE_OFF, plan.aeMode)
        assertNotNull(plan.sensitivity)
        assertEquals("AE_OFF 下补偿恒 0（纠偏不改变关 AE 的状态）", 0, plan.aeComp)
        assertFalse(plan.aeLock)
    }

    @Test
    fun equalTier24SendsFullWindowMinusFloor() {
        val plan = RequestApplier.exposurePlan(state(), snapshot(t24, fps = 24))
        assertEquals("1/24@24：41_666_666 − 2_083_333 = 39_583_333", 39_583_333L, plan.exposureNs)
        assertEquals(t24, plan.frameDurationNs)
    }

    @Test
    fun latchedDeltaRaisesMarginWithSafetyAndCap() {
        assertEquals(
            "Δ=6ms ⇒ 6+1=7ms 余量",
            33_000_000L,
            RequestApplier.exposurePlan(state(), snapshot(t25, deltaNs = 6_000_000L)).exposureNs
        )
        assertEquals(
            "Δ=30ms ⇒ 顶到 20% 上限 8ms ⇒ 32ms",
            32_000_000L,
            RequestApplier.exposurePlan(state(), snapshot(t25, deltaNs = 30_000_000L)).exposureNs
        )
    }

    @Test
    fun probeModeSendsEqualityAsTheRangefinder() {
        val plan = RequestApplier.exposurePlan(state(), snapshot(t25, probeActive = true))
        assertEquals("探测中发等号（这就是量 Δ 的量程）；只允许出现在预览态", t25, plan.exposureNs)
        assertEquals(t25, plan.frameDurationNs)
        assertEquals(CaptureRequest.CONTROL_AE_MODE_OFF, plan.aeMode)
    }

    @Test
    fun manualSliderReplacesAutoAndNegativeSaturatesAtFullWindow() {
        assertEquals(
            "关自动 + 手动 +2ms ⇒ 38ms",
            38_000_000L,
            RequestApplier.exposurePlan(state(), snapshot(t25, auto = false, manualNs = 2_000_000L)).exposureNs
        )
        assertEquals(
            "半毫步进：+0.5ms ⇒ 39.5ms",
            39_500_000L,
            RequestApplier.exposurePlan(state(), snapshot(t25, auto = false, manualNs = 500_000L)).exposureNs
        )
        assertEquals(
            "手动 0 = 用户明确要满窗（等号直发，与改版前行为一致；负值区 2026-11-01 裁决去掉）",
            t25,
            RequestApplier.exposurePlan(state(), snapshot(t25, auto = false, manualNs = 0L)).exposureNs
        )
    }

    @Test
    fun nonEqualPairIsUntouched() {
        val plan = RequestApplier.exposurePlan(state(), snapshot(t25, fps = 24))
        assertEquals(
            "1/25@24 本来就不满窗（读出有余量），纠偏不适用、曝光原样",
            t25,
            plan.exposureNs
        )
        assertEquals("帧周期按 24fps 档位", t24, plan.frameDurationNs)
    }

    @Test
    fun busTierValuePassesThroughUnchanged() {
        // 发送侧纠偏绝不改写用户快门：钳制仍按档位值（forceDeviceRange 对强制档豁免设备范围）
        val plan = RequestApplier.exposurePlan(state(), snapshot(t24, fps = 25))
        assertEquals("25fps+1/24 被钳到帧周期后再纠偏：40 − 2 = 38ms", 38_000_000L, plan.exposureNs)
        assertEquals(t25, plan.frameDurationNs)
    }
}
