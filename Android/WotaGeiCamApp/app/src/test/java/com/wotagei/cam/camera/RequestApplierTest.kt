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
    fun highSpeedOnlyUsesDeclaredRanges() {
        // 2026-10-26 修正：高速档**只在机器声明过的区间里选**，绝不自己造 [f,f]。
        // 旧实现 return FpsPick(target, target, true) ⇒ 50/60 首次被路由进高速会话后发出
        // 机器没声明过的 [50,50] ⇒ createHighSpeedRequestList 拒收 ⇒ 掉进无退路的降档分支。
        val hs = listOf(
            RangeI(30, 120), RangeI(60, 120), RangeI(120, 120),
            RangeI(30, 240), RangeI(60, 240), RangeI(240, 240)
        )
        assertEquals("120 有原生固定档 ⇒ 精确", FpsPick(120, 120, true), RequestApplier.pickFps(hs, 120, true))
        assertEquals("60 取 lo==60 里上界最小的 [60,120]", FpsPick(60, 120, false), RequestApplier.pickFps(hs, 60, true))
        assertEquals("50 退到覆盖它的 [30,120]", FpsPick(30, 120, false), RequestApplier.pickFps(hs, 50, true))
        assertNull("表里没有覆盖它的区间 ⇒ null（交给失败路径降级，不硬发）",
            RequestApplier.pickFps(listOf(RangeI(120, 120), RangeI(240, 240)), 60, true))
        assertNull("空表 ⇒ null", RequestApplier.pickFps(emptyList(), 120, true))
        // 普通档口径不变（固定优先 / 其次"包含且上界最大"）
        val normal = listOf(RangeI(24, 24), RangeI(30, 30), RangeI(5, 30))
        assertEquals(FpsPick(24, 24, true), RequestApplier.pickFps(normal, 24, false))
        assertFalse(RequestApplier.pickFps(normal, 25, false)!!.exact)
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

    // -------------------------------------------------- 帧周期/快门基准 = 目标档位（2026-10-26）

    /** 与 EvCrashGuardTest 同型的最小 State/Snapshot 便捷构造 */
    private fun stateHs(): RequestApplier.State = RequestApplier.State(
        activeArray = com.wotagei.cam.core.Rect(0, 0, 4000, 3000),
        isoMin = 100, isoMax = 16000,
        exposureMinNs = 1_000_000L, exposureMaxNs = 667_847_000L,
        evMinSteps = -4, evMaxSteps = 4,
        fpsRanges = listOf(RangeI(30, 120), RangeI(60, 120), RangeI(120, 120)),
        maxRegionsAf = 1, maxRegionsAe = 1,
        manualFocusMaxDiopter = 10f, zoomMin = 1f, zoomMax = 8f,
        useZoomRatio = false, flashAvailable = true, oisAvailable = true, eisAvailable = true,
        highSpeed = true, eisBlockedBySize = false
    )

    private fun snapHs(target: Int) = RequestApplier.Snapshot(
        aeMode = com.wotagei.cam.core.AeMode.MANUAL, iso = 800, shutterNs = 20_000_000L, evSteps = 0,
        fps = FpsPick(30, 120, false), afMode = com.wotagei.cam.core.AfMode.CONTINUOUS_VIDEO,
        afModeOverride = null, focusDiopter = 0f, zoom = 1f,
        wbPreset = com.wotagei.cam.core.WbPreset.AUTO, kelvin = 5500, tint = 0,
        flashOverride = null, flash = Flash.OFF, stabilize = Stabilize.OFF,
        afRegions = null, aeRegions = null, afTrigger = null,
        fpsTarget = target
    )

    @Test
    fun framePeriodBasisIsTargetTierNotRangeLowerBound() {
        // vivo 50 → [30,120]：lo=30 若作基准会放行 33.3ms 帧周期（>50fps 的 20ms）＝死档；
        // 基准必须是目标 50。曝光 20ms 与 1/50 同域 ⇒ 不被钳
        val plan = RequestApplier.exposurePlan(stateHs(), snapHs(target = 50))
        assertEquals(1_000_000_000L / 50, plan.frameDurationNs)
        assertEquals("曝光 20ms < 1/50 帧周期 ⇒ 原样", 20_000_000L, plan.exposureNs)
    }

    @Test
    fun shutterLadderCeilingFollowsTargetTierOnVariableHighSpeedRange() {
        // 1/30（33.3ms）在 50fps 档必须被钳回 1/50（20ms）——这是 UI 梯与引擎共同的基准
        val clamped = RequestApplier.clampShutterNs(33 * ns + 333_333L, fps = 50, exposureMinNs = 1, exposureMaxNs = 1_000 * ns)
        assertEquals(1_000_000_000L / 50, clamped)
    }

    @Test
    fun exactNormalTierKeepsLegacyBehavior() {
        // 回归锚：r10 的原案 [24,30] 选 24（lo==目标）⇒ 41.67ms 帧周期，1/24 长曝光保留
        val s = RequestApplier.State(
            activeArray = com.wotagei.cam.core.Rect(0, 0, 4000, 3000),
            isoMin = 100, isoMax = 16000,
            exposureMinNs = 1_000_000L, exposureMaxNs = 667_847_000L,
            evMinSteps = -4, evMaxSteps = 4,
            fpsRanges = listOf(RangeI(24, 30)),
            maxRegionsAf = 1, maxRegionsAe = 1,
            manualFocusMaxDiopter = 10f, zoomMin = 1f, zoomMax = 8f,
            useZoomRatio = false, flashAvailable = true, oisAvailable = true, eisAvailable = true,
            highSpeed = false, eisBlockedBySize = false
        )
        val snap = RequestApplier.Snapshot(
            aeMode = com.wotagei.cam.core.AeMode.MANUAL, iso = 800,
            shutterNs = 1_000_000_000L / 24, evSteps = 0,
            fps = FpsPick(24, 30, false), afMode = com.wotagei.cam.core.AfMode.CONTINUOUS_VIDEO,
            afModeOverride = null, focusDiopter = 0f, zoom = 1f,
            wbPreset = com.wotagei.cam.core.WbPreset.AUTO, kelvin = 5500, tint = 0,
            flashOverride = null, flash = Flash.OFF, stabilize = Stabilize.OFF,
            afRegions = null, aeRegions = null, afTrigger = null,
            fpsTarget = 24
        )
        val plan = RequestApplier.exposurePlan(s, snap)
        assertEquals("1/24 强制档在 [24,30] 上保留（r10 意图不变）", 1_000_000_000L / 24, plan.exposureNs)
        assertEquals(1_000_000_000L / 24, plan.frameDurationNs)
    }
}
