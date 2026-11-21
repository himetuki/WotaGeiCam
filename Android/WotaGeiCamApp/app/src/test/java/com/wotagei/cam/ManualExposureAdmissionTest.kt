package com.wotagei.cam

import com.wotagei.cam.core.AeMode
import com.wotagei.cam.core.CameraAbility
import com.wotagei.cam.core.ParamState
import com.wotagei.cam.core.RangeI
import com.wotagei.cam.core.Rect
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.manualShutterRoom
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y04 · 设备最短曝光大于一帧周期时的**手动曝光准入**（行为 + 接线守卫）。
 *
 * ## 缺陷
 * `manualExposurePossible()` 只看「设备给不给 ISO/曝光范围」，不看当前帧率。设备最短曝光
 * 20ms、用户在 60fps（帧周期 16.67ms）进手动档时：`clampShutterNs` 先把快门压到 16.67ms、
 * 再被设备下限抬回 20ms，曝光纠偏又压回 16.67ms —— 最终下发落在设备范围之外的请求。
 * 参数层虽然把快门域置成空（`enabled=false`，档位梯整排不可选），但 AE 档位仍能进 MANUAL。
 *
 * ## 修法
 * 档位准入求「曝光范围 ∩ 一帧周期」交集：`manualShutterRoom`（复用 `shutterCeilingNs` 的域算式）
 * → `CameraAbility.manualExposurePossibleAt(fps)` → 三处同源消费：
 * ① `WotaParams.applyModeGates`（换镜头/换档位回弹 AUTO，Diag 可归因）；
 * ② `WotaParams.setFps`（换帧率同一拍重判）；
 * ③ `AeModeRow` 的 MANUAL 可选项（灰显 = 进不去）+ ShutterPill 的原因提示。
 */
class ManualExposureAdmissionTest {

    private fun params() = WotaParams(CoroutineScope(Dispatchers.Unconfined))

    /** 假能力：只点菜 fps 表与曝光域，其余全空（与 ShutterRangeTest 同一手填惯例） */
    private fun ability(
        fpsRanges: List<RangeI> = listOf(RangeI(24, 24), RangeI(60, 60)),
        exposureNs: RangeI
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

    /** UI 选档的同一支口径（CameraPills onPick 的原样复刻，测试与生产同源） */
    private fun pickFor(p: WotaParams, a: CameraAbility, fps: Int): ParamState<Int> =
        a.decideFps(p.size.value, fps)?.pick
            ?.let { ParamState(fps, it.lo..it.hi, enabled = true, exact = it.exact) }
            ?: ParamState(fps, null, enabled = false, exact = false)

    // ------------------------------------------------------------------ ① 判据（纯计算）

    @Test
    fun `最短曝光长于一帧的手动组合没有解`() {
        val slowDevice = RangeI(20_000_000, 100_000_000)   // 最短 20ms
        // 前提：设备本身给手动曝光（旧判据为真——证明新判据不是重复旧判据）
        val a = ability(exposureNs = slowDevice)
        assertTrue(a.manualExposurePossible())
        // 24fps：帧周期 41.67ms ≥ 20ms ⇒ 有解
        assertTrue("24fps 下 20ms 最短曝光仍在帧周期内", a.manualExposurePossibleAt(24))
        // 60fps：帧周期 16.67ms < 20ms ⇒ 交集为空，禁入
        assertFalse("60fps 下 20ms 最短曝光超过一帧，手动档必须禁入", a.manualExposurePossibleAt(60))
        // 判据本体：交集的边界是闭区间——恰好等于帧周期算「有解」，多 1ns 就没解
        assertTrue(manualShutterRoom(RangeI(1_000_000_000 / 60, 100_000_000), 60))
        assertFalse(manualShutterRoom(RangeI(1_000_000_000 / 60 + 1, 100_000_000), 60))
        // 能力缺失（closed）与非法帧率一律无解（调用方另有灰显分支）
        assertFalse(manualShutterRoom(RangeI.CLOSED, 60))
        assertFalse(manualShutterRoom(slowDevice, 0))
    }

    // ------------------------------------------------------------------ ② 总线行为

    @Test
    fun `换到更快的档位后手动档必须回弹自动且快门域置灰`() {
        val p = params()
        // 曝光上限放宽到 100ms：这样 60fps 下域为空只会由「最短曝光 > 帧周期」造成
        val a = ability(exposureNs = RangeI(20_000_000, 100_000_000))
        p.applyAbility(a)
        p.aeMode.value = AeMode.MANUAL
        p.setFps(pickFor(p, a, 24))
        assertEquals("24fps 下有解，不许被弹掉", AeMode.MANUAL, p.aeMode.value)
        assertTrue("24fps 的 1/24 域必须可用", p.shutter.value.enabled)
        assertNotNull(p.shutter.value.range)

        // 换到 60fps：无解组合 ⇒ 回弹 AUTO，快门域同一拍置灰（不是等下次换镜头才收口）
        p.setFps(pickFor(p, a, 60))
        assertEquals("60fps 下 20ms 最短曝光无解，手动档必须回弹自动", AeMode.AUTO, p.aeMode.value)
        assertFalse("快门域必须同一拍置灰", p.shutter.value.enabled)
        assertEquals("置灰时域必须为空（1..lo 表达不了越界下限）", null, p.shutter.value.range)

        // 换回 24fps：域恢复，用户可再次进手动档（回弹不是永久闩锁）
        p.setFps(pickFor(p, a, 24))
        assertTrue("换回有解档位后快门域必须恢复", p.shutter.value.enabled)
        p.aeMode.value = AeMode.MANUAL
        p.applyAbility(a)
        assertEquals("applyAbility 只挡无解组合", AeMode.MANUAL, p.aeMode.value)
    }

    @Test
    fun `换镜头重新取能力时无解组合同样被挡`() {
        val p = params()
        // 前摄这类「最短曝光很长」的镜头：24fps 下也没有手动快门空间
        val narrow = ability(exposureNs = RangeI(50_000_000, 100_000_000))
        p.aeMode.value = AeMode.MANUAL
        p.applyAbility(narrow)
        assertEquals(AeMode.AUTO, p.aeMode.value)
        assertFalse(p.shutter.value.enabled)
    }

    // ------------------------------------------------------------------ ③ 接线守卫（UI 同源）

    private val pills by lazy { codeOnly(KotlinSourceScan.mainSourceText("ui/CameraPills.kt")) }

    /** MANUAL 准入与提示文案的接线问题；空 = 干净 */
    private fun admissionUiIssues(src: String): List<String> {
        val bad = ArrayList<String>()
        val ae = runCatching { bodyOf(src, "AeModeRow") }.getOrNull()
            ?: return listOf("定位不到 AeModeRow 的体——守卫失去输入，不许算通过")
        // [occurrences] 是**词边界**匹配：needle 若以 `(` 收尾，紧随其后的实参首字符（`f`）
        // 是标识符字符，调用永远不会被计数（`manualExposurePossibleAt(fps.value)` 这种单行
        // 调用会假红）。这里按标识符本身判，括号由后一条「仍读旧判据」反向排除。
        if (occurrences(ae, "manualExposurePossibleAt").isEmpty()) {
            bad.add("AeModeRow 的 MANUAL 准入没吃帧率（manualExposurePossibleAt）——60fps 下无解组合仍能进手动")
        }
        if (occurrences(ae, "manualExposurePossible(").isNotEmpty()) {
            bad.add("AeModeRow 仍直接读 manualExposurePossible()（与手册准入不同源）")
        }
        val shutter = runCatching { bodyOf(src, "ShutterPill") }.getOrNull()
            ?: return bad + "定位不到 ShutterPill 的体——守卫失去输入"
        if (occurrences(shutter, "cam_shutter_manual_no_room").isEmpty()) {
            bad.add("ShutterPill 没把「手动快门无解」的原因写给用户（cam_shutter_manual_no_room；自动回弹不许静默）")
        }
        if (occurrences(shutter, "!shutter.enabled").isEmpty()) {
            bad.add("ShutterPill 的提示没有挂在快门域置灰条件上")
        }
        return bad
    }

    /** 文案资源（与 PillInteractionGuardTest 同一手法：从主源码目录上溯找 res/values） */
    private fun stringsCamera(): File {
        var d: File? = KotlinSourceScan.mainSourceFile("ui/CameraScreen.kt").parentFile
        while (d != null) {
            val f = File(d, "res/values/strings_camera.xml")
            if (f.isFile) return f
            d = d.parentFile
        }
        throw AssertionError("找不到 res/values/strings_camera.xml（守卫失去输入）")
    }

    @Test
    fun `手动准入与无解提示接线在位`() {
        assertEquals(
            "手动准入接线缺件：\n${admissionUiIssues(pills).joinToString("\n")}",
            emptyList<String>(),
            admissionUiIssues(pills)
        )
        val strings = stringsCamera().readText(Charsets.UTF_8)
        assertTrue(
            "strings_camera.xml 缺 cam_shutter_manual_no_room",
            strings.contains("name=\"cam_shutter_manual_no_room\"")
        )
    }

    @Test
    fun `尺子自己能红_准入退回旧判据与提示被摘都必须报红`() {
        assertTrue("真身必须绿", admissionUiIssues(pills).isEmpty())
        val stale = pills.replace(
            "ability?.manualExposurePossibleAt(fps.value)",
            "ability?.manualExposurePossible()"
        )
        assertTrue(
            "MANUAL 准入退回旧判据必须红",
            admissionUiIssues(stale).any { it.contains("manualExposurePossibleAt") }
        )
        val silent = pills.replace("cam_shutter_manual_no_room", "cam_shutter_manual_note")
        assertTrue(
            "摘掉无解提示必须红（回弹静默）",
            admissionUiIssues(silent).any { it.contains("cam_shutter_manual_no_room") }
        )
    }
}
