package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.ui.bottomAbsoluteYOffsetPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底栏绝对 y 的手势避让（Y61）。
 *
 * 渲染层把卡片下缘钳到「带高 − 外距 − 手势区」之上；这里的 px 数字模拟带手势条的设备
 * （手势区 48px + 底栏外距 6px）。真机是否重叠仍需设备验证，纯算术只保证渲染上限。
 */
class BottomBarGestureAvoidTest {

    private val band = 800
    private val card = 100
    private val avoid = 54   // 6dp 外距 + 48px 手势区

    @Test
    fun `拖到带底的绝对y被抬到手势区之上`() {
        val clamped = bottomAbsoluteYOffsetPx(yPx = 700, cardHeightPx = card, bandHeightPx = band, bottomAvoidPx = avoid)
        assertEquals(800 - 100 - 54, clamped)
        // 卡片下缘 = 646 + 100 = 746，正好落在避让带顶，不伸手势区
        assertEquals(band - avoid, clamped + card)
        // 旧形态 700 + 100 = 800 会整条压进手势区
        assertTrue("旧形态下缘越进避让带——这正是 Y61", 700 + card > band - avoid)
    }

    @Test
    fun `正常高度不被改动`() {
        assertEquals(300, bottomAbsoluteYOffsetPx(300, card, band, avoid))
        assertEquals(0, bottomAbsoluteYOffsetPx(0, card, band, avoid))
    }

    @Test
    fun `首帧卡片高未量到时也有上界`() {
        // cardH=0：先保证 y 本身不越避让带顶，第二帧卡片高到了再收敛
        assertEquals(band - avoid, bottomAbsoluteYOffsetPx(9999, 0, band, avoid))
    }

    @Test
    fun `避让量超过带高时钳到0`() {
        assertEquals(0, bottomAbsoluteYOffsetPx(500, 100, 120, 54))
        assertEquals(0, bottomAbsoluteYOffsetPx(0, 0, 100, 200))
    }

    @Test
    fun `HudZoneBox 的绝对底栏分支走同一算式且实测卡片高`() {
        val flat = KotlinSourceScan.flatten(
            KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayer.kt"))
        )
        assertTrue(
            "bottomYOnly 的 offset 必须经 bottomAbsoluteYOffsetPx，不许裸 px(placement.yDp)",
            flat.contains("yPx = px(placement.yDp), cardHeightPx = cardHpx")
        )
        assertTrue(
            "卡片高必须与卡片宽同一处实测",
            flat.contains("cardHpx = s.height")
        )
        assertTrue(
            "避让量必须与默认底栏分支同源（BottomBarOuterPadV + systemGestures.bottom）",
            flat.contains("bottomOuterPadPx + bottomGesturePx")
        )
    }
}