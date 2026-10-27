package com.wotagei.cam

import com.wotagei.cam.core.popupBelowRight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 站位线浮层落点（2026-10-27 三轮）：贴锚点右下、右缘不越保留区、不越窗口。
 * 这三条都是用户点名要求过的（先"别漂画面中间"，再"别压右 Dock"），所以钉成纯算术单测。
 */
class PopupPlacementTest {

    @Test
    fun `右缘与锚点对齐_纵向落在锚点下方一个间隙`() {
        val (x, y) = popupBelowRight(
            rightLimitPx = 1400,
            anchorBottomPx = 94,
            popupWidthPx = 480,
            popupHeightPx = 220,
            windowWidthPx = 1600,
            windowHeightPx = 720,
            gapPx = 16,
            marginPx = 16
        )
        assertEquals("右缘贴着锚点右缘", 1400, x + 480)
        assertEquals("落在锚点下方 = 下缘 + 间隙", 110, y)
    }

    @Test
    fun `右 Dock 左缘作为上限时浮层整体左移`() {
        // 右 Dock 比圆钮更靠左压过来时（保留区左缘 1300 < 圆钮右缘 1400），以保留区为准
        val (x, _) = popupBelowRight(
            rightLimitPx = 1300,
            anchorBottomPx = 94,
            popupWidthPx = 480,
            popupHeightPx = 220,
            windowWidthPx = 1600,
            windowHeightPx = 720,
            gapPx = 16,
            marginPx = 16
        )
        assertEquals("右缘让到保留区左缘", 1300, x + 480)
    }

    @Test
    fun `不越左缘与下缘`() {
        val (x, y) = popupBelowRight(
            rightLimitPx = 300,
            anchorBottomPx = 700,
            popupWidthPx = 480,
            popupHeightPx = 300,
            windowWidthPx = 1600,
            windowHeightPx = 720,
            gapPx = 16,
            marginPx = 16
        )
        assertEquals("空间不足时先保左留白", 16, x)
        assertEquals("下缘钳在窗口内", 720 - 300 - 16, y)
        assertTrue(y + 300 <= 720)
    }

    @Test
    fun `负留白钳成零_不产生非法区间`() {
        val (x, y) = popupBelowRight(
            rightLimitPx = 500,
            anchorBottomPx = 100,
            popupWidthPx = 200,
            popupHeightPx = 100,
            windowWidthPx = 1600,
            windowHeightPx = 720,
            gapPx = 8,
            marginPx = -20
        )
        assertEquals(300, x)
        assertEquals(108, y)
    }
}
