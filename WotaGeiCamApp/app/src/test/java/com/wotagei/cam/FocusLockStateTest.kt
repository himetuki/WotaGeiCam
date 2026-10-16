package com.wotagei.cam

import com.wotagei.cam.core.AfMode
import com.wotagei.cam.core.CameraAbility
import com.wotagei.cam.core.RangeI
import com.wotagei.cam.core.Rect
import com.wotagei.cam.core.TapPoint
import com.wotagei.cam.core.WotaParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 对焦锁定（用户反馈第 2 条）的**清锁规则**行为单测（Q4 的机器判据）：
 * 「会话级重建保持、相机整套重开清除」里"整套重开"的唯一落点是 [WotaParams.applyAbility]
 * ——换镜头/切渲染模式/离开取景页再回/权限重开都经它。
 *
 * 桥是真身：测试直接驱动 `applyAbility`（运行时唯一清锁出口），不摸私有分支；
 * 假能力表构造惯例照 [VideoSizeAspectTest]。
 */
class FocusLockStateTest {

    private fun params() = WotaParams(CoroutineScope(Dispatchers.Unconfined))

    /** 空能力假件：afModes 由用例点菜，其余全空（与 ParamsPersistSessionTest 同一支惯例） */
    private fun abilityWithAf(vararg cam2Modes: Int) = CameraAbility(
        hardwareLevel = 0,
        sensorOrientation = 0,
        activeArray = Rect(0, 0, 0, 0),
        jpegSizes = emptyList(),
        highSpeedSizes = emptyList(),
        videoSizes = emptyList(),
        fpsRanges = emptyList(),
        highSpeedFps = emptyMap(),
        iso = RangeI.CLOSED,
        exposureNs = RangeI.CLOSED,
        evRange = RangeI.CLOSED,
        evStep = 0f,
        afModes = cam2Modes.toSet(),
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
    fun `换挡清锁_锁态与锚点一起归零`() {
        val p = params()
        p.focusLocked.value = true
        p.focusLockPoint.value = TapPoint(0.25f, 0.75f)
        p.applyAbility(abilityWithAf(AfMode.CONTINUOUS_VIDEO.cam2Mode))
        assertFalse("applyAbility（相机整套重开）必须解锁（Q4 清锁唯一落点）", p.focusLocked.value)
        assertNull("锚点随锁一起失效（activeArray 换了，旧坐标语义不再成立）", p.focusLockPoint.value)
    }

    @Test
    fun `未锁定时不触碰_残留锚点原样保留`() {
        val p = params()
        p.focusLocked.value = false
        // 解锁后的残留锚点（胶囊解除/长按解锁都不清它——没有读取方，下一次上锁由 UI 整份覆盖）
        p.focusLockPoint.value = TapPoint(0.5f, 0.5f)
        p.applyAbility(abilityWithAf(AfMode.CONTINUOUS_VIDEO.cam2Mode))
        assertFalse("未锁定态不会被换挡意外上锁", p.focusLocked.value)
        assertEquals(
            "未锁定时清锁逻辑不许触碰锚点（条件式清理的语义钉子）",
            TapPoint(0.5f, 0.5f),
            p.focusLockPoint.value
        )
    }

    @Test
    fun `焦点框旧态在换挡时照旧清理_与锁清理同点`() {
        // 既有行为回归：focusPoint 的清理口径不变（锁清理与它同一行族）
        val p = params()
        p.focusPoint.value = TapPoint(0.1f, 0.2f)
        p.focusLocked.value = true
        p.focusLockPoint.value = TapPoint(0.3f, 0.4f)
        p.applyAbility(abilityWithAf(AfMode.CONTINUOUS_VIDEO.cam2Mode))
        assertNull("focusPoint 清理口径不变", p.focusPoint.value)
        assertFalse(p.focusLocked.value)
        assertNull(p.focusLockPoint.value)
    }
}
