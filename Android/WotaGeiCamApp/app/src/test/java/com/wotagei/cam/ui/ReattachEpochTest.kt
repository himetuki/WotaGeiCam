package com.wotagei.cam.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y52 桥测：上屏重挂的纪元按 activeSink 持有，换 sink 后旧持有者不再被新请求推进；
 * 600ms 限频窗口也随持有者重建（换面本身不该被上一面的限频吞掉）。
 */
class ReattachEpochTest {

    @Test
    fun `换持有者后旧纪元不再被新请求推进`() {
        var now = 1_000L
        val old = ReattachEpoch { now }
        val fresh = ReattachEpoch { now }

        assertTrue("旧持有者首个请求推进", old.request())
        assertEquals(1, old.epoch)

        now = 1_100L
        assertFalse("600ms 内重复请求必须被限频", old.request())
        assertEquals("限频不推进纪元", 1, old.epoch)

        now = 2_000L
        assertTrue("新 sink 的持有者窗口重新起算，不被旧面限频吞掉", fresh.request())
        assertEquals(1, fresh.epoch)
        assertEquals("新持有者的请求不得改动旧纪元", 1, old.epoch)

        // 旧收集器的迟到请求最多只推进旧持有者；界面读的是新持有者
        assertTrue(old.request())
        assertEquals("旧持有者只在自己的纪元里变化", 2, old.epoch)
        assertEquals("新持有者不受旧请求影响", 1, fresh.epoch)
    }

    @Test
    fun `同一持有者内的限频窗口按最后推进时刻滚动`() {
        var now = 10_000L
        val epoch = ReattachEpoch { now }
        assertTrue(epoch.request())
        now = 10_599L
        assertFalse(epoch.request())
        now = 10_600L
        assertTrue("满 600ms 后允许下一次", epoch.request())
        assertEquals(2, epoch.epoch)
    }
}
