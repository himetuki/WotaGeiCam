package com.wotagei.cam.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y57 单测：跨中点翻转只改本段手势的逻辑档，手势不中断；取消路径清空全部拖动残余。
 * 状态机就是 WotaSwitch 指针手势实际使用的那一枚（SwitchGestureState）。
 */
class SwitchGestureStateTest {

    @Test
    fun `跨中点翻转后手势继续接受移动且落位用最新逻辑档`() {
        val s = SwitchGestureState()
        s.begin(initialChecked = false)
        assertEquals("过中点立刻翻转", true, s.move(at = 60f, grab = 0f, travelPx = 100f, velocity = 120f))
        assertTrue("逻辑档必须已翻转", s.checked)
        assertEquals("翻转后拖动位置继续更新", 60f, s.dragX!!, 0f)

        assertNull("已翻转，继续拖不许重复回调", s.move(at = 80f, grab = 0f, travelPx = 100f, velocity = 100f))
        assertEquals("手势未中断：位置继续跟随", 80f, s.dragX!!, 0f)
        assertEquals("速度也继续更新", 100f, s.dragVelocity, 0f)

        assertEquals("拖回中点另一侧立即反向翻转", false, s.move(at = 40f, grab = 0f, travelPx = 100f, velocity = -50f))
        assertFalse(s.checked)

        val release = s.finish(travelPx = 100f, currentX = 40f, springVelocity = 0f) as SwitchRelease.Drag
        assertEquals("从离手位置落位", 40f, release.at, 0f)
        assertEquals("目标取本段逻辑档（false ⇒ 0）", 0f, release.target, 0f)
        assertEquals("速度继承离手速度", -50f, release.initialVelocity, 0f)
        assertNull("正常收尾不需要取消回位", s.endGesture(100f))
        assertFalse("收尾必须复位按压态", s.pressing)
        assertNull("收尾必须清空拖动位置", s.dragX)
    }

    @Test
    fun `取消路径清空拖动状态并把拇指落回逻辑档`() {
        val s = SwitchGestureState()
        s.begin(initialChecked = false)
        assertEquals(true, s.move(at = 70f, grab = 0f, travelPx = 100f, velocity = 200f))
        assertTrue(s.checked)

        val cancel = requireNotNull(s.endGesture(100f))
        assertEquals("从当前位置回位", 70f, cancel.at, 0f)
        assertEquals("回位目标取本段逻辑档（true ⇒ travel）", 100f, cancel.target, 0f)
        assertFalse("取消必须清按压态", s.pressing)
        assertNull("取消必须清 dragX（下一次手势从零起跑）", s.dragX)
        assertEquals("取消必须清速度", 0f, s.dragVelocity, 0f)
    }

    @Test
    fun `没移动过的松手按点击翻转一次`() {
        val s = SwitchGestureState()
        s.begin(initialChecked = false)
        val click = s.finish(travelPx = 100f, currentX = 0f, springVelocity = 0f) as SwitchRelease.Click
        assertEquals("点击翻转一次", true, click.checked)
        assertTrue(s.checked)
        assertNull("点击已正常收尾，不再回位", s.endGesture(100f))
    }
}
