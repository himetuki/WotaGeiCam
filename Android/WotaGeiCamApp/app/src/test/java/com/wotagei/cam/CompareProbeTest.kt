package com.wotagei.cam

import com.wotagei.cam.ui.CompareProbeState
import com.wotagei.cam.ui.compareProbeEnabledOf
import com.wotagei.cam.ui.compareProbeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对比播放黑屏探针的纯函数层（[compareProbeEnabledOf] "release 不可达"闸门 + [compareProbeText] 文本）。
 *
 * 两条闸门断言都不是恒等式：
 * - 删掉 `enabled &&`（探针在正式 release 里也能被 adb 点亮）→ [gateKeepsReleaseBlind] 红；
 * - 删掉 `&& raw`（变体开着就恒亮）→ [rawFalseStaysOff] 红。
 *
 * **测不到**的东西照实写清楚：`BuildConfig.COMPARE_PROBE` 在四变体各是什么值（构建侧证据）；
 * extra 有没有真被 MainActivity 收进 [com.wotagei.cam.ui.CompareProbe]；叠层画出来数字对不对
 * （只有真机能证——这正是探针存在的目的）。
 */
class CompareProbeTest {

    @Test
    fun gateKeepsReleaseBlind() {
        assertFalse("闸门关着就不许开探针", compareProbeEnabledOf(enabled = false, raw = true))
        assertTrue(compareProbeEnabledOf(enabled = true, raw = true))
    }

    @Test
    fun rawFalseStaysOff() {
        assertFalse("变体开着但没带 extra = 关闭态", compareProbeEnabledOf(enabled = true, raw = false))
    }

    @Test
    fun text_carriesAllProbeCells() {
        // 每格都在场（判读表见 ui/CompareProbe 头注）；期望值手写，不调被测实现反推
        val s = CompareProbeState(
            playerL = true, playerR = false,
            boundViewsL = 1, boundViewsR = 0,
            surfaceAvailL = true, surfaceAvailR = false,
            viewSizeL = 720 to 480, viewSizeR = 0 to 0,
            domBlackL = false, domBlackR = true,
            cmdBlackL = false, cmdBlackR = true,
            playingL = true, playingR = false,
            tL = 1234L, tR = 0L, off = -500L,
            t = 1234L, tMin = 500L, tMax = 9000L,
            stateL = 3, stateR = 1
        )
        val text = compareProbeText(s)
        listOf(
            "playerL=1", "playerR=0",
            "boundL=1", "boundR=0",
            "surfL=1", "surfR=0",
            "sizeL=720x480", "sizeR=0x0",
            "domBlackL=0", "domBlackR=1",
            "blackL=0", "blackR=1",
            "playingL=1", "playingR=0",
            "tL=1234", "tR=0", "off=-500",
            "T=1234", "Tmin=500", "Tmax=9000",
            "stateL=3", "stateR=1"
        ).forEach { cell ->
            assertTrue("探针文本缺格 $cell：$text", text.contains(cell))
        }
    }
}
