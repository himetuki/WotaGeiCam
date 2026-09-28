package com.wotagei.cam.camera

import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.FpsPick
import com.wotagei.cam.core.RangeI
import com.wotagei.cam.core.Stabilize
import com.wotagei.cam.core.WotaTiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RequestApplier 纯函数单测（步骤 4 要求的三项：快门钳制 / fps 选范围 / kelvin→gains）。
 * 只测不吃 Android 类的纯计算，因此可直接在 JVM 跑，无需 Robolectric。
 */
class RequestApplierTest {

    private val ns = 1_000_000L

    // -------------------------------------------------- 快门双钳制

    @Test
    fun shutterNeverExceedsOneTenthSecond() {
        // 低帧率下 1/10s 先起效（帧周期 200ms > 100ms）
        val capped = RequestApplier.clampShutterNs(500 * ns, fps = 5, exposureMinNs = 1, exposureMaxNs = 1_000 * ns)
        assertEquals(WotaTiers.SHUTTER_CAP_NS, capped)
    }

    @Test
    fun shutterNeverExceedsFrameDuration() {
        // 1/24 帧周期 ≈ 41.67ms，比 1/10s 更短，应先到帧边界
        val v = RequestApplier.clampShutterNs(200 * ns, fps = 24, exposureMinNs = 1, exposureMaxNs = 1_000 * ns)
        assertEquals(1_000_000_000L / 24, v)
        assertTrue(v < WotaTiers.SHUTTER_CAP_NS)
    }

    @Test
    fun highFpsShrinksShutterCeiling() {
        val v = RequestApplier.clampShutterNs(20 * ns, fps = 240, exposureMinNs = 1, exposureMaxNs = 1_000 * ns)
        assertEquals(1_000_000_000L / 240, v)
    }

    @Test
    fun deviceExposureRangeIsHonored() {
        val v = RequestApplier.clampShutterNs(20 * ns, fps = 24, exposureMinNs = 30 * ns, exposureMaxNs = 1_000 * ns)
        assertEquals(30 * ns, v)   // 低于设备下界 → 抬到下界
    }

    @Test
    fun frameDurationMatchesFps() {
        assertEquals(40_000_000L, RequestApplier.frameDurationNs(25))
        assertEquals(41_666_666L, RequestApplier.frameDurationNs(24))
    }

    // -------------------------------------------------- fps 范围选择

    private val normalRanges = listOf(
        RangeI(10, 30),
        RangeI(7, 30),
        RangeI(24, 24),
        RangeI(30, 30)
    )

    @Test
    fun prefersExactFixedRange() {
        val pick = RequestApplier.pickFps(normalRanges, 24, highSpeed = false)
        assertNotNull(pick)
        assertTrue(pick!!.exact)
        assertEquals(24, pick.lo)
        assertEquals(24, pick.hi)
    }

    @Test
    fun fallsBackToCoveringRangeAndMarksApprox() {
        val pick = RequestApplier.pickFps(normalRanges, 25, highSpeed = false)
        assertNotNull(pick)
        assertFalse(pick!!.exact)
        // 没有 [25,25] 固定档 → 落进包含 25 的范围，上界取最大者
        assertTrue(pick.lo <= 25 && 25 <= pick.hi)
        assertEquals(30, pick.hi)
    }

    @Test
    fun returnsNullWhenFpsUnsupported() {
        assertNull(RequestApplier.pickFps(normalRanges, 120, highSpeed = false))
    }

    @Test
    fun highSpeedUsesFixedRangeDirectly() {
        val pick = RequestApplier.pickFps(emptyList(), 120, highSpeed = true)
        assertNotNull(pick)
        assertEquals(FpsPick(120, 120, true), pick)
    }

    // -------------------------------------------------- 色温 → gains

    @Test
    fun neutralPointAtSixThousandKelvin() {
        val rgb = RequestApplier.kelvinTintToRgb255(6000, 0)
        assertEquals(rgb[0], rgb[2], 1f)                 // R=B → 无偏色
        assertEquals(rgb[0], rgb[1], 1f)                 // 中间点三通道一致
    }

    @Test
    fun lowKelvinAddsBlueHighKelvinAddsRed() {
        val warm = RequestApplier.kelvinTintToRgb255(2000, 0)
        val cool = RequestApplier.kelvinTintToRgb255(10000, 0)
        assertTrue("2000K 应补蓝", warm[2] > warm[0])
        assertTrue("10000K 应补红", cool[0] > cool[2])
        assertTrue("色温轴单调", cool[0] > warm[0] && warm[2] > cool[2])
    }

    @Test
    fun tintAxisIsMonotonicAndBounded() {
        val green = RequestApplier.kelvinTintToRgb255(5500, -50)
        val magenta = RequestApplier.kelvinTintToRgb255(5500, 50)
        assertTrue(magenta[0] > green[0])
        assertTrue(magenta[2] > green[2])
        assertTrue("品红端压绿", green[1] > magenta[1])
        for (channel in magenta) assertTrue(channel in 0f..255f)
        for (channel in green) assertTrue(channel in 0f..255f)
    }

    @Test
    fun gainsMapIntoUnitRange() {
        assertEquals(1f, RequestApplier.gain255ToUnit(0f), 1e-6f)
        assertEquals(3f, RequestApplier.gain255ToUnit(255f), 1e-6f)
    }

    // -------------------------------------------------- 区域与裁切（/）

    @Test
    fun meteringBoxCenteredAndClamped() {
        val center = RequestApplier.meteringBox(4032, 3024, 0.5f, 0.5f)
        assertEquals(1016, center[0])
        assertEquals(center[0] + 1000, center[2])
        val leftEdge = RequestApplier.meteringBox(4032, 3024, 0f, 0f)
        assertEquals(0, leftEdge[0])
        assertEquals(0, leftEdge[1])
        val rightEdge = RequestApplier.meteringBox(4032, 3024, 1f, 1f)
        assertEquals(4032, rightEdge[2])
        assertEquals(3024, rightEdge[3])
    }

    @Test
    fun fullFrameBoxCoversActiveArray() {
        val full = RequestApplier.fullFrameBox(4032, 3024)
        assertEquals(0, full[0])
        assertEquals(4032, full[2])
        assertEquals(3024, full[3])
    }

    @Test
    fun cropBoxAtOneTimesIsFullArray() {
        val box = RequestApplier.cropBox(4000, 3000, zoom = 1f, zoomMin = 1f, zoomMax = 8f)
        assertEquals(0, box[0])
        assertEquals(0, box[1])
        assertEquals(4000, box[2])
        assertEquals(3000, box[3])
    }

    @Test
    fun cropBoxHalvesEachEdgeAtTwoTimes() {
        val box = RequestApplier.cropBox(4000, 3000, zoom = 2f, zoomMin = 1f, zoomMax = 8f)
        assertEquals(1000, box[0])
        assertEquals(750, box[1])
        assertEquals(3000, box[2])
        assertEquals(2250, box[3])
    }

    @Test
    fun cropBoxClampsBelowOneAndAboveMax() {
        val low = RequestApplier.cropBox(4000, 3000, zoom = 0.4f, zoomMin = 1f, zoomMax = 8f)
        assertEquals(4000, low[2])
        val high = RequestApplier.cropBox(4000, 3000, zoom = 99f, zoomMin = 1f, zoomMax = 8f)
        assertTrue(high[2] <= 4000 && high[3] <= 3000)
    }

    // -------------------------------------------------- 闪光语义（按官方映射）

    @Test
    fun flashUsesOfficialAeModeSemantics() {
        assertEquals(
            RequestApplier.FlashAe.ON_ALWAYS_FLASH,
            RequestApplier.flashPlan(Flash.ON, flashAvailable = true, manualAe = false).ae
        )
        assertEquals(
            RequestApplier.FlashAe.ON_AUTO_FLASH,
            RequestApplier.flashPlan(Flash.AUTO, flashAvailable = true, manualAe = false).ae
        )
        val torch = RequestApplier.flashPlan(Flash.TORCH, flashAvailable = true, manualAe = false)
        assertEquals(RequestApplier.FlashAe.ON, torch.ae)
        assertTrue(torch.torch)
        assertFalse(RequestApplier.flashPlan(Flash.OFF, flashAvailable = true, manualAe = false).torch)
    }

    @Test
    fun flashIgnoredWithoutCapabilityOrManualAe() {
        val noFlash = RequestApplier.flashPlan(Flash.ON, flashAvailable = false, manualAe = false)
        assertEquals(RequestApplier.FlashAe.ON, noFlash.ae)
        assertFalse(noFlash.torch)
        val manual = RequestApplier.flashPlan(Flash.ON, flashAvailable = true, manualAe = true)
        assertEquals(RequestApplier.FlashAe.ON, manual.ae)   // 手动曝光下不能用 AE 驱动的闪光模式
    }

    // -------------------------------------------------- 防抖三门控

    @Test
    fun stabilizeNeverForcesUnsupportedCapability() {
        val plan = RequestApplier.stabilizePlan(
            Stabilize.OIS_EIS, oisAvailable = false, eisAvailable = true,
            eisBlockedBySize = false, highSpeed = false
        )
        assertNull("没给 OIS 能力就不能强设", plan.oisOn)
        assertEquals(true, plan.eisOn)                         // EIS 有能力且被选中 → 写 ON
    }

    @Test
    fun stabilizeKeepsOisWhenOnlyEisMissing() {
        val plan = RequestApplier.stabilizePlan(
            Stabilize.OIS_EIS, oisAvailable = true, eisAvailable = false,
            eisBlockedBySize = false, highSpeed = false
        )
        assertEquals(true, plan.oisOn)
        assertNull("没给 EIS 能力就不写该键", plan.eisOn)
    }

    @Test
    fun stabilizeDisablesEisAtFourK() {
        val plan = RequestApplier.stabilizePlan(
            Stabilize.EIS, oisAvailable = true, eisAvailable = true,
            eisBlockedBySize = true, highSpeed = false
        )
        assertNull(plan.eisOn)
        assertEquals(false, plan.oisOn)
    }

    @Test
    fun stabilizeSkippedEntirelyInHighSpeedSession() {
        val plan = RequestApplier.stabilizePlan(
            Stabilize.OIS_EIS, oisAvailable = true, eisAvailable = true,
            eisBlockedBySize = false, highSpeed = true
        )
        assertNull(plan.oisOn)
        assertNull(plan.eisOn)
    }

    @Test
    fun stabilizeOffWritesExplicitOff() {
        val plan = RequestApplier.stabilizePlan(
            Stabilize.OFF, oisAvailable = true, eisAvailable = true,
            eisBlockedBySize = false, highSpeed = false
        )
        assertEquals(false, plan.oisOn)
        assertEquals(false, plan.eisOn)
    }
}
