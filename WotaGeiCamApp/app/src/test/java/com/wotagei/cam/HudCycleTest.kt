package com.wotagei.cam

import com.wotagei.cam.ui.nextInCycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 参数胶囊点按循环的纯判据（鸿蒙化第 2 条）：候选表由调用方现取，这里只管"下一档"与回绕 */
class HudCycleTest {

    @Test
    fun walksForwardAndWraps() {
        val tiers = listOf(5_000_000, 10_000_000, 20_000_000)
        assertEquals(10_000_000, nextInCycle(tiers, 5_000_000))
        assertEquals(20_000_000, nextInCycle(tiers, 10_000_000))
        assertEquals(5_000_000, nextInCycle(tiers, 20_000_000))
    }

    @Test
    fun unknownCurrentGoesToFirst() {
        // 能力表换镜头后当前值可能已不在候选里：回到第一档比停在非法值安全
        assertEquals(24, nextInCycle(listOf(24, 25, 30), 60))
    }

    @Test
    fun emptyOptionsYieldNothing() {
        // 调用方拿到 null 就该给"本机不支持"提示，而不是假装改了
        assertNull(nextInCycle(emptyList<Int>(), 1))
    }

    @Test
    fun singleOptionCyclesOntoItself() {
        assertEquals(24, nextInCycle(listOf(24), 24))
    }
}
