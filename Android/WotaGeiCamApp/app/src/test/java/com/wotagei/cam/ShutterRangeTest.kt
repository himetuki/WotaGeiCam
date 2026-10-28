package com.wotagei.cam

import com.wotagei.cam.core.CameraAbility
import com.wotagei.cam.core.ParamState
import com.wotagei.cam.core.RangeI
import com.wotagei.cam.core.Rect
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.source.KotlinSourceScan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快门可选域随帧率档位收口（Issue #1 Bug3，docs/plan/28 §四）的行为单测 + 接线守卫。
 *
 * ## 缺陷回顾
 * 换帧率的写入点只写 `fps.value`，快门域只在 `applyAbility`（换镜头）里重算：
 * 60fps+1/240 → 切 24fps 后域上限仍是 1/60，滑杆刻度被过滤到只剩一枚——
 * 用户看到「快门滑条丢了」。反向（24→60）则是 UI 摆着 1/24、下发被 `clampShutterNs`
 * 压回 1/60 的假读数。
 *
 * ## 修法（28 §4.4 方案 A）
 * `WotaParams.setFps` 是换帧率**唯一写入口**：写值 + 按新档位立即重算曝光域。
 * 本文件两层：①行为（域随档位走、值钳制可见）；②源码接线（所有写入点必须走 setFps，
 * 每条尺配「尺子自己能红」的突变体）。
 */
class ShutterRangeTest {

    private fun params() = WotaParams(CoroutineScope(Dispatchers.Unconfined))

    /** 假能力：fps 表与曝光域由用例点菜，其余全空（VideoSizeAspectTest 手填惯例） */
    private fun ability(
        fpsRanges: List<RangeI> = listOf(RangeI(24, 24), RangeI(60, 60)),
        exposureNs: RangeI = RangeI(1_000, 1_000_000_000)
    ) = CameraAbility(
        hardwareLevel = 0,
        sensorOrientation = 0,
        activeArray = Rect(0, 0, 0, 0),
        jpegSizes = emptyList(),
        highSpeedSizes = emptyList(),
        videoSizes = emptyList(),
        fpsRanges = fpsRanges,
        highSpeedFps = emptyMap(),
        iso = RangeI(50, 6400),
        exposureNs = exposureNs,
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

    /** UI 选档的同一支口径（CameraPills 两处 onPick 的原样复刻，保持测试与生产同源） */
    private fun pickFor(p: WotaParams, a: CameraAbility, fps: Int): ParamState<Int> {
        val pick = a.decideFps(p.size.value, fps)?.pick
        return pick
            ?.let { ParamState(fps, it.lo..it.hi, enabled = true, exact = it.exact) }
            ?: ParamState(fps, null, enabled = false, exact = false)
    }

    // -------------------------------------------------- ① 行为

    @Test
    fun `60切24后快门域扩到慢档_滑杆刻度回到梯内`() {
        val p = params()
        val a = ability()
        p.applyAbility(a)
        p.setFps(pickFor(p, a, 60))
        assertEquals(16_666_666L, p.shutter.value.range!!.endInclusive)

        // 用户在 60fps 下选 1/240（域内合法）
        p.shutter.value = p.shutter.value.copy(value = 4_166_667L)
        // 切回 24fps：域上限必须立即扩到 1/24（旧缺陷停在这 1/60）
        p.setFps(pickFor(p, a, 24))
        assertEquals(41_666_666L, p.shutter.value.range!!.endInclusive)
        assertEquals(4_166_667L, p.shutter.value.value)
        // 1/24、1/30 都落在新域内——滑杆刻度不再被过滤到只剩 1/60
        assertTrue(p.shutter.value.range!!.contains(41_666_666L))
        assertTrue(p.shutter.value.range!!.contains(33_333_333L))
        assertTrue("换档后快门必须仍可用", p.shutter.value.enabled)
    }

    @Test
    fun `24切60后快门域收窄且当前值被钳到帧周期`() {
        val p = params()
        val a = ability()
        p.applyAbility(a)
        p.setFps(pickFor(p, a, 24))
        // 24fps 下选 1/24（域内合法）
        p.shutter.value = p.shutter.value.copy(value = 41_666_666L)
        // 切到 60fps：域收窄到 1/60，当前值同一拍被钳（UI 可见，不能留下假读数）
        p.setFps(pickFor(p, a, 60))
        assertEquals(16_666_666L, p.shutter.value.range!!.endInclusive)
        assertEquals(16_666_666L, p.shutter.value.value)
    }

    @Test
    fun `帧率档不可用时快门域仍按目标档位完整给出`() {
        val p = params()
        val a = ability(fpsRanges = listOf(RangeI(24, 24)))
        p.applyAbility(a)
        // 设备没有 60fps：档位灰显（enabled=false），但快门域必须仍按 60 的帧周期算——
        // 快门不因帧率不可用而失去可选域（滑杆可见性由 editable 另管）
        p.setFps(ParamState(60, null, enabled = false, exact = false))
        assertEquals(16_666_666L, p.shutter.value.range!!.endInclusive)
        assertFalse(p.fps.value.enabled)
    }

    @Test
    fun `换镜头重挡后exact按目标档重查且域与新档一致`() {
        val p = params()
        val a = ability()
        p.applyAbility(a)
        p.setFps(pickFor(p, a, 60))
        assertTrue(p.fps.value.exact)
        // 重挡（换镜头/重开等一切经 applyAbility 的路径）：域必须落在当前档位上（回归）
        p.applyAbility(a)
        assertEquals(60, p.fps.value.value)
        assertEquals(16_666_666L, p.shutter.value.range!!.endInclusive)
        assertTrue(p.fps.value.exact)
    }

    // -------------------------------------------------- ② 源码接线守卫

    /** 尺：函数体内不允许 `params.fps.value =` 直写（读与 `==` 比较不匹配这根针） */
    private fun noDirectFpsWrite(body: String): Boolean =
        KotlinSourceScan.occurrences(body, "params.fps.value =").isEmpty()

    @Test
    fun `引擎退档回写与设置页恢复都走setFps单一写入者`() {
        // writeBackFps（引擎自动降档也是用户可见的档位变化）
        val engine = KotlinSourceScan.codeOnly(
            KotlinSourceScan.mainSourceText("camera/Camera2Engine.kt")
        )
        val wb = KotlinSourceScan.bodyOf(engine, "writeBackFps")
        assertTrue("writeBackFps 必须调 params.setFps（否则退档后快门域不重算）", wb.contains("params.setFps("))
        assertTrue(noDirectFpsWrite(wb))

        // 设置页默认档与冷启恢复（lastAbility 为空时行为与直写等价，但必须留单一入口）
        val settings = KotlinSourceScan.codeOnly(
            KotlinSourceScan.mainSourceText("ui/SettingsScreen.kt")
        )
        assertTrue(
            "applyDefaults 必须调 params.setFps",
            KotlinSourceScan.bodyOf(settings, "applyDefaults").contains("params.setFps(")
        )

        val persist = KotlinSourceScan.codeOnly(
            KotlinSourceScan.mainSourceText("ui/ParamsPersist.kt")
        )
        assertTrue(
            "restore 必须调 params.setFps",
            KotlinSourceScan.bodyOf(persist, "restore").contains("params.setFps(")
        )

        // 尺要能红：把调用改成直写后断言必须翻红
        val mutant = wb.replace("params.setFps(", "params.fps.value = ")
        assertFalse("尺子自己必须能红（突变体上翻红）", noDirectFpsWrite(mutant))
    }

    @Test
    fun `全仓换帧率写入点不允许绕过setFps`() {
        val sources = KotlinSourceScan.walkMainSources()
        val offenders = sources.filter { (path, text) ->
            val code = KotlinSourceScan.codeOnly(text)
            if (path == "core/WotaParams.kt") {
                // WotaParams 内部：只有 setFps 与 applyAbility 落档两处合法写 fps.value
                val setFpsBody = KotlinSourceScan.regionOf(code, "setFps")
                val applyBody = KotlinSourceScan.regionOf(code, "applyAbility")
                val comparisons = KotlinSourceScan.occurrences(code, "fps.value ==").toSet()
                KotlinSourceScan.occurrences(code, "fps.value =").any { at ->
                    at !in comparisons &&
                        at !in setFpsBody.start until setFpsBody.end &&
                        at !in applyBody.start until applyBody.end
                }
            } else {
                // 任意接收者名的直写（params./bus./p. 都算）：`==` 比较不算写，扣掉再判
                KotlinSourceScan.occurrences(code, ".fps.value =").size >
                    KotlinSourceScan.occurrences(code, ".fps.value ==").size
            }
        }.keys
        assertTrue("换帧率存在绕过 setFps 的直写：$offenders", offenders.isEmpty())
    }
}
