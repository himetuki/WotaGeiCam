package com.wotagei.cam

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.wotagei.cam.ui.design.PillAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 就近胶囊浮层定位的单测（JVM）。
 *
 * 弹窗摆偏一格就是"压住被点的控件"或"跑出屏幕点不到"，这类问题真机上只能一张张肉眼看，
 * 所以先把纯几何锁住：贴边、贴角、上下都放不下、弹窗比可视区还大、横屏。
 */
class PillAnchorTest {

    private val area = IntSize(720, 1600)
    private val popup = IntSize(400, 300)
    private val gap = 8
    private val margin = 10

    private fun place(anchor: IntRect) = PillAnchor.place(anchor, popup, area, gap, margin)

    @Test
    fun `空间够时放在锚点上方并收回左边距`() {
        // 锚点靠左，居中后会顶出左边界 → 应被夹回 margin
        val at = place(IntRect(100, 1200, 200, 1260))
        assertEquals(1200 - gap - 300, at.y)
        assertEquals(margin, at.x)
    }

    @Test
    fun `居中放得下时就按锚点中心对齐`() {
        val at = place(IntRect(300, 1200, 380, 1260))
        assertEquals((300 + 380) / 2 - 200, at.x)
        assertEquals(892, at.y)
    }

    @Test
    fun `上方放不下就退到锚点下方`() {
        val at = place(IntRect(100, 100, 200, 160))
        assertEquals(160 + gap, at.y)
    }

    @Test
    fun `上下都放不下时贴可视区底边`() {
        val at = place(IntRect(100, 100, 200, 1300))
        assertEquals(area.height - margin - popup.height, at.y)
    }

    @Test
    fun `锚点贴左贴右都不越界`() {
        assertEquals(margin, place(IntRect(0, 1200, 60, 1260)).x)
        assertEquals(area.width - margin - popup.width, place(IntRect(660, 1200, 720, 1260)).x)
    }

    @Test
    fun `弹窗比可视区还宽时停在左边距不倒负`() {
        val at = PillAnchor.place(IntRect(0, 100, 10, 160), IntSize(5000, 300), area, gap, margin)
        assertEquals(margin, at.x)
        assertEquals(160 + gap, at.y)
    }

    @Test
    fun `横屏面积下任意锚点都落在合法矩形内`() {
        val wide = IntSize(1600, 720)
        val maxX = wide.width - margin - popup.width
        val maxY = wide.height - margin - popup.height
        var top = 0
        while (top < wide.height) {
            val at = PillAnchor.place(IntRect(200, top, 260, top + 48), popup, wide, gap, margin)
            assertTrue("x 越界: ${at.x}", at.x in margin..maxX)
            assertTrue("y 越界: ${at.y}", at.y in margin..maxY)
            top += 57
        }
    }
}
