package com.wotagei.cam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 能力协商的出厂默认镜头（2026-10-26 用户指令「能力协商，而非能力假设」）纯函数用例。
 *
 * 回归题面（marble 真机）：档位按焦距归档会把小底定焦头排进「广角」档，出厂默认开出的
 * 「广角」最大只有 1600x1200，用户由此只能选 720p。[pickCapabilityDefault] 不认标签，
 * 只比后摄里最大视频档的像素数。
 */
class LensCapabilityDefaultTest {

    private fun slot(type: LensType, id: String, maxPx: Long): LensSlot = LensSlot(
        type = type,
        logicId = id,
        physicalId = null,
        eqFocal = 24f,
        ability = abilityOf(maxPx)
    )

    private fun abilityOf(maxPx: Long): CameraAbility = CameraAbility(
        hardwareLevel = 0,
        sensorOrientation = 90,
        activeArray = Rect(0, 0, 1, 1),
        jpegSizes = emptyList(),
        highSpeedSizes = emptyList(),
        videoSizes = if (maxPx > 0L) listOf(Size(maxPx.toInt().coerceAtLeast(1), 1)) else emptyList(),
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

    @Test
    fun `后摄里最大视频档者胜_前置一律排除`() {
        val picked = pickCapabilityDefault(
            listOf(
                slot(LensType.FRONT, "front", 7_600_000),
                slot(LensType.WIDE, "main", 64_000_000),
                slot(LensType.TELEPHOTO, "tele", 16_000_000)
            )
        )
        assertEquals("main", picked?.logicId)
    }

    @Test
    fun `归档错位时小底广角不会抢走默认`() {
        // marble 形态：2MP 定焦头被归进「广角」，真主摄挂在别的档位名下
        val picked = pickCapabilityDefault(
            listOf(
                slot(LensType.WIDE, "macro-ish", 1_920_000),
                slot(LensType.SUPER_TELEPHOTO, "real-main", 64_000_000)
            )
        )
        assertEquals("real-main", picked?.logicId)
    }

    @Test
    fun `并列时取清单里靠前的一颗`() {
        val picked = pickCapabilityDefault(
            listOf(slot(LensType.WIDE, "a", 64_000_000), slot(LensType.TELEPHOTO, "b", 64_000_000))
        )
        assertEquals("a", picked?.logicId)
    }

    @Test
    fun `只有前置时报不出可开默认`() {
        assertNull(pickCapabilityDefault(listOf(slot(LensType.FRONT, "front", 7_600_000))))
    }

    @Test
    fun `全表报不出视频档时返回null`() {
        assertNull(
            pickCapabilityDefault(
                listOf(slot(LensType.WIDE, "a", 0), slot(LensType.TELEPHOTO, "b", 0))
            )
        )
    }

    @Test
    fun `空表返回null`() {
        assertNull(pickCapabilityDefault(emptyList()))
    }
}
