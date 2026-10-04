package com.wotagei.cam

import com.wotagei.cam.player.ComparePractice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对比页练习闭环的桥对象（armed 门 + pendingRightId 记账 + 重复投递拒绝） */
class ComparePracticeTest {

    @After
    fun reset() {
        ComparePractice.armed = false
        ComparePractice.pendingRightId = null
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
}
