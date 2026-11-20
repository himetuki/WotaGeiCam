package com.wotagei.cam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Y70 桥测：请求档位与实际落定槽位可以不一致。
 *
 * 设备只有前置可开时，用户请求后置会一路回退到前置槽；站点分析的 D13 真值必须按
 * **实际槽位**（[resolveLensSlot] 的结果）判断前置，否则「前置禁开」失效。
 */
class LensFallbackTest {

    private fun slot(type: LensType, id: String, physicalId: String? = null): LensSlot = LensSlot(
        type = type,
        logicId = id,
        physicalId = physicalId,
        eqFocal = 24f,
        ability = CameraAbility(
            hardwareLevel = 0,
            sensorOrientation = 90,
            activeArray = Rect(0, 0, 1, 1),
            jpegSizes = emptyList(),
            highSpeedSizes = emptyList(),
            videoSizes = emptyList(),
            fpsRanges = emptyList(),
            highSpeedFps = emptyMap(),
            iso = RangeI.CLOSED,
            exposureNs = RangeI.CLOSED,
            evRange = RangeI.CLOSED,
            evStep = 0f,
            afModes = setOf(0),
            awbModes = emptySet(),
            flashAvailable = false,
            maxRegionsAf = 0,
            maxRegionsAe = 0,
            minFocusDistanceDiopter = 0f,
            zoomRatioRange = null,
            maxDigitalZoom = 1f,
            oisAvailable = false,
            eisAvailable = false,
            highSpeedCapable = false,
            availableSurfaceFormats = intArrayOf()
        )
    )

    @Test
    fun `只有前置可用时请求后置实际回退前置`() {
        val front = slot(LensType.FRONT, "1")
        val resolved = resolveLensSlot(listOf(front), chosenKey = null, requested = LensType.WIDE)
        assertEquals("请求后置回退到前置槽", front, resolved)
        assertEquals(
            "UI 的 D13 真值按实际槽位判断：frontLens 必须为 true",
            true,
            resolved?.type == LensType.FRONT
        )
    }

    @Test
    fun `请求档位存在时优先于广角兜底`() {
        val tele = slot(LensType.TELEPHOTO, "2")
        val resolved = resolveLensSlot(
            listOf(slot(LensType.WIDE, "0"), tele),
            chosenKey = null,
            requested = LensType.TELEPHOTO
        )
        assertEquals("同档位请求不能被广角兜底抢走", tele, resolved)
    }

    @Test
    fun `请求档位缺失时先退广角而不是清单首颗`() {
        val wide = slot(LensType.WIDE, "0")
        val resolved = resolveLensSlot(
            listOf(slot(LensType.TELEPHOTO, "2"), wide),
            chosenKey = null,
            requested = LensType.SUPER_WIDE
        )
        assertEquals("没有超广角时退到广角档", wide, resolved)
    }

    @Test
    fun `显式槽位键优先于请求档位`() {
        val physical = slot(LensType.WIDE, "0", physicalId = "5")
        val resolved = resolveLensSlot(
            listOf(slot(LensType.WIDE, "0"), physical),
            chosenKey = "0#5",
            requested = LensType.WIDE
        )
        assertEquals("同档位多颗时必须按 key 认槽", physical, resolved)
    }

    @Test
    fun `空表返回null`() {
        assertNull(resolveLensSlot(emptyList(), chosenKey = "0", requested = LensType.WIDE))
    }
}
