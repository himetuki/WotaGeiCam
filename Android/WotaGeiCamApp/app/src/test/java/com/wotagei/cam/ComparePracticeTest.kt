package com.wotagei.cam

import com.wotagei.cam.player.ComparePractice
import com.wotagei.cam.player.PracticeReturn
import com.wotagei.cam.player.practiceReturnPlan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对比页练习闭环的桥：armed 门 + pendingRightId 记账 + 参考片 takeLeftId 一次性取走 +
 * 发起页身份与回程计划（Y51）。
 */
class ComparePracticeTest {

    @After
    fun reset() {
        // disarm 清整条意图（armed / 右尾巴 / 发起者 / 参考片）
        ComparePractice.disarm()
        assertNull(ComparePractice.takeLeftId())
        assertNull(ComparePractice.takeOrigin())
    }

    @Test
    fun `deliver 未armed拒绝且不记账`() {
        assertFalse(ComparePractice.deliver(7L))
        assertNull(ComparePractice.pendingRightId)
    }

    @Test
    fun `deliver armed时记账并消费armed`() {
        ComparePractice.armed = true
        assertTrue(ComparePractice.deliver(42L))
        assertEquals(42L, ComparePractice.pendingRightId)
        // 消费即关闸：第二次投递是另一条不属练习动线的停止
        assertFalse(ComparePractice.deliver(43L))
        assertEquals(42L, ComparePractice.pendingRightId)
    }

    @Test
    fun `armed未录成时对比页侧手动自愈清态`() {
        // 对比页 ON_RESUME 的自愈顺序：先清标志、丢弃尾巴，不会把旧尾巴错配到新片
        ComparePractice.armed = true
        ComparePractice.pendingRightId = null
        ComparePractice.armed = false
        assertFalse(ComparePractice.deliver(9L))
        assertNull(ComparePractice.pendingRightId)
    }

    @Test
    fun `primeWith武装意图并带参考片`() {
        ComparePractice.primeWith(77L)
        assertTrue(ComparePractice.armed)
        assertEquals(77L, ComparePractice.takeLeftId())
        // 一次性取走：录制页之后无论如何重组都不会被旧意图再拉回分屏
        assertNull(ComparePractice.takeLeftId())
        // 意图仍在（等录完 deliver）；重置后归零
        assertTrue(ComparePractice.armed)
    }

    @Test
    fun `takeLeftId 未发起时返回null`() {
        assertNull(ComparePractice.takeLeftId())
        assertFalse(ComparePractice.armed)
    }

    @Test
    fun `primeWith 记发起页身份且只结算一次`() {
        ComparePractice.primeWith(77L, "entry-compare")
        assertEquals("entry-compare", ComparePractice.takeOrigin())
        // 回程只结算一次：第二次拿到 null，不会把同一条意图算进下一条回程
        assertNull(ComparePractice.takeOrigin())
        // 发起者被取走不影响原意图本身（参考片/armed 仍在）
        assertEquals(77L, ComparePractice.takeLeftId())
        assertTrue(ComparePractice.armed)
    }

    @Test
    fun `不传发起者时身份为 null`() {
        ComparePractice.primeWith(5L)
        assertNull(ComparePractice.takeOrigin())
        assertTrue(ComparePractice.armed)
    }

    @Test
    fun `自愈 disarm 连发起者身份一起清`() {
        ComparePractice.primeWith(9L, "entry-compare")
        ComparePractice.pendingRightId = 42L
        ComparePractice.disarm()
        assertFalse(ComparePractice.armed)
        assertNull(ComparePractice.pendingRightId)
        assertNull(ComparePractice.takeOrigin())
        assertNull(ComparePractice.takeLeftId())
    }

    @Test
    fun `回程计划：对比页在栈上定向弹回`() {
        assertEquals(
            PracticeReturn.TO_COMPARE,
            practiceReturnPlan(compareInStack = true, originAlive = true, canPopOne = true)
        )
        // 对比页在栈上时是否有下层页不影响定向弹回
        assertEquals(
            PracticeReturn.TO_COMPARE,
            practiceReturnPlan(compareInStack = true, originAlive = true, canPopOne = false)
        )
    }

    @Test
    fun `回程计划：对比页缺席且还有下层页才裸弹`() {
        assertEquals(
            PracticeReturn.POP_ONE,
            practiceReturnPlan(compareInStack = false, originAlive = true, canPopOne = true)
        )
        // 已是根栈唯一页：裸弹会清空返回栈（黑屏），宁可不动
        assertEquals(
            PracticeReturn.STAY,
            practiceReturnPlan(compareInStack = false, originAlive = true, canPopOne = false)
        )
    }

    @Test
    fun `回程计划：过期发起者一律不动作`() {
        assertEquals(
            PracticeReturn.STAY,
            practiceReturnPlan(compareInStack = true, originAlive = false, canPopOne = true)
        )
        assertEquals(
            PracticeReturn.STAY,
            practiceReturnPlan(compareInStack = false, originAlive = false, canPopOne = true)
        )
        assertEquals(
            PracticeReturn.STAY,
            practiceReturnPlan(compareInStack = false, originAlive = false, canPopOne = false)
        )
    }
}
