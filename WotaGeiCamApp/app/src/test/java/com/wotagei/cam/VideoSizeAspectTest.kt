package com.wotagei.cam

import com.wotagei.cam.core.CameraAbility
import com.wotagei.cam.core.RangeI
import com.wotagei.cam.core.Rect
import com.wotagei.cam.core.Size
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.core.aspectOf
import com.wotagei.cam.core.buildVideoSizeTable
import com.wotagei.cam.core.screenAspectOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「原相机全屏分辨率」档（10-01 用户指令）的**机型无关**实现：不写死任何机型尺寸，
 * 而是由 [screenAspectOf] 归一的屏幕比例 × 设备能力表在 [buildVideoSizeTable] 运行时推导。
 *
 * 10-01 用户纠正：本项目面向所有新安卓设备，禁止按连接的测试机写死参数（曾把 4160x1872
 * 这一档写进候选表，已撤）。这里钉住推导行为本身：有同比档就进表、没有就不造幻影档、
 * 屏幕没量到就跳过；示例尺寸只是测试数据，断言对任何机型的尺寸组合都成立。
 *
 * 比例标签（"20:9" 这类）是通用登记（[aspectOf] 的常用比例表 + WotaTiers.ASPECTS），
 * 不是机型参数——保留比例数学断言。
 */
class VideoSizeAspectTest {

    /** 手填假能力表（全纯数据、零 android 依赖），只关心 videoSizes */
    private fun abilityWith(vararg sizes: Size) = CameraAbility(
        hardwareLevel = 0,
        sensorOrientation = 0,
        activeArray = Rect(0, 0, 0, 0),
        jpegSizes = emptyList(),
        highSpeedSizes = emptyList(),
        videoSizes = sizes.toList(),
        fpsRanges = emptyList(),
        highSpeedFps = emptyMap(),
        iso = RangeI.CLOSED,
        exposureNs = RangeI.CLOSED,
        evRange = RangeI.CLOSED,
        evStep = 0f,
        afModes = emptySet(),
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
        availableSurfaceFormats = IntArray(0)
    )

    @Test
    fun screenAspectOfIsOrientationAgnostic() {
        // 竖横屏必须归一到同一个比值：录像档天生长边在前，拿无向比才对得上
        assertEquals(20.0 / 9.0, screenAspectOf(2160, 972), 1e-9)
        assertEquals(20.0 / 9.0, screenAspectOf(972, 2160), 1e-9)
        assertEquals(0.0, screenAspectOf(0, 1080), 1e-9)
        assertEquals(0.0, screenAspectOf(-1, 1080), 1e-9)
    }

    @Test
    fun fullscreenTierDerivesFromScreenAspectAndDeviceTable() {
        // 设备能力表有与屏幕同比的最大档 ⇒ 推导进表，且比例标签通用现算
        val ability = abilityWith(
            Size(4160, 3120), Size(4160, 1872), Size(1920, 1080), Size(1280, 720)
        )
        val table = buildVideoSizeTable(ability, screenAspect = 20.0 / 9.0)
        val option = table.optionOf(Size(4160, 1872))
        assertNotNull("屏幕同比的最大档必须推导进表", option)
        assertTrue("设备表命中时必须 supported", option?.supported == true)
        assertEquals("20:9", option?.aspect)
        assertEquals("全屏档与需求档同级排序", true, option?.inRequirement)
    }

    @Test
    fun fullscreenTierNotDerivedWhenDeviceHasNoMatchingAspect() {
        // 设备能力表里没有任何屏幕同比的档 ⇒ **不造幻影档**（推出来的 unsupported 幻影没有意义）
        val ability = abilityWith(Size(4032, 3024), Size(1920, 1080)) // 全是 4:3 / 16:9
        val table = buildVideoSizeTable(ability, screenAspect = 20.0 / 9.0)
        val has20to9 = table.options.any { it.aspect == "20:9" }
        assertFalse("设备没有同比档时不许凭空进表", has20to9)
    }

    @Test
    fun fullscreenTierSkippedWhenScreenNotMeasured() {
        // 屏幕还没量到（screenAspect=0/取消）= 推导跳过：老行为原样。
        // ⚠ 断言钉的是"推导档"（inRequirement=true 那一族）而不是"任何同比档"——
        // 设备最高档仍会走既有的 extras 机制（EXTRA_TOP_COUNT）进表，那是改前就有的行为
        val ability = abilityWith(Size(4160, 1872), Size(1920, 1080))
        val table = buildVideoSizeTable(ability, screenAspect = 0.0)
        val table2 = buildVideoSizeTable(ability, screenAspect = null)
        assertFalse(
            "screenAspect=0 时不许推导出全屏档",
            table.options.any { it.inRequirement && it.aspect == "20:9" }
        )
        assertFalse(
            "screenAspect=null 时不许推导出全屏档",
            table2.options.any { it.inRequirement && it.aspect == "20:9" }
        )
    }

    @Test
    fun fullscreenTierNeverDuplicatesARequirementTier() {
        // 屏幕是 16:9 且最大 16:9 档已在需求表里求交过 ⇒ 不许出两行同尺寸
        val ability = abilityWith(Size(7680, 4320), Size(1920, 1080), Size(1280, 720))
        val table = buildVideoSizeTable(ability, screenAspect = 16.0 / 9.0)
        val same = table.options.filter { it.size == Size(7680, 4320) }
        assertEquals("同一尺寸只许出现一次", 1, same.size)
    }

    @Test
    fun aspectRegistriesContain20to9() {
        // 比例标签是通用登记（非机型参数）；4170/1876 是纯数学示例：
        // ratio 2.2226 落 20:9 的 ±0.02 内，但 gcd 约分是 2085:938——
        // 常用比例表不登记的话这一条算不出 "20:9"
        assertTrue(WotaTiers.ASPECTS.contains("20:9"))
        assertEquals("20:9", aspectOf(4170, 1876))
        assertEquals("20:9", aspectOf(4160, 1872))
    }
}
