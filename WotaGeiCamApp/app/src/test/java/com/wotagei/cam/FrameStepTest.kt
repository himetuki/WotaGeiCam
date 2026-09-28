package com.wotagei.cam

import com.wotagei.cam.player.FrameStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧步进帧号算术单测（JVM，不需设备）。
 *
 * 核心性质是「连点单调」——这类缺陷只在非整数帧率（29.97/59.94）上出现，
 * 而本机录的成片恰好是 25/50，真机点验根本碰不到，所以必须在纯函数层面锁死。
 */
class FrameStepTest {

    private fun tapLoop(start: Long, frameMs: Double, fwd: Boolean, times: Int, dur: Long = 0L): List<Long> {
        val out = ArrayList<Long>(times)
        var pending: Long? = null
        repeat(times) {
            val target = FrameStep.next(pending, if (pending == null) start else out.last(), frameMs, fwd, dur)
            out += target
            pending = target
        }
        return out
    }

    @Test
    fun `整数帧率下逐帧推进`() {
        val taps = tapLoop(start = 0L, frameMs = 40.0, fwd = true, times = 5)
        assertEquals(listOf(40L, 80L, 120L, 160L, 200L), taps)
    }

    @Test
    fun `29_97fps 连点前进不卡在同一帧`() {
        // 旧实现：base 用 floor 反查帧号，帧首 33.37ms 被存成整毫秒 33 后又会退回第 0 帧，
        // 于是第二次起每次都算出 33ms —— 前进键永久卡死
        val taps = tapLoop(start = 0L, frameMs = 1000.0 / 29.97, fwd = true, times = 8)
        assertEquals(8, taps.distinct().size)
        for (i in 1 until taps.size) {
            assertTrue("第 $i 次点击没有前进：${taps[i - 1]} -> ${taps[i]}", taps[i] > taps[i - 1])
        }
        assertEquals(listOf(33L, 67L, 100L, 133L), taps.take(4))
    }

    @Test
    fun `59_94fps 连点前进同样单调`() {
        val taps = tapLoop(start = 0L, frameMs = 1000.0 / 59.94, fwd = true, times = 10)
        assertEquals(10, taps.distinct().size)
        for (i in 1 until taps.size) assertTrue(taps[i] > taps[i - 1])
    }

    @Test
    fun `连点后退逐帧回退且不越过零`() {
        val taps = tapLoop(start = 200L, frameMs = 40.0, fwd = false, times = 8)
        assertEquals(listOf(160L, 120L, 80L, 40L, 0L, 0L, 0L, 0L), taps)
    }

    @Test
    fun `前进到片尾被时长钳住`() {
        assertEquals(100L, FrameStep.next(pendingMs = 80L, positionMs = 80L, frameMs = 40.0, fwd = true, durationMs = 100L))
        // 时长未知（<=0）时不做上限钳制，交给播放器自己裁
        assertEquals(120L, FrameStep.next(null, 80L, 40.0, true, 0L))
    }

    @Test
    fun `seek未落地时以上次目标为基准`() {
        // 位置还停在 0，但上一次已发出 33ms 的目标：再点一次必须往前，不能被旧位置吞掉
        assertEquals(67L, FrameStep.next(33L, 0L, 1000.0 / 29.97, true, 0L))
    }

    @Test
    fun `前进一回退一回到原帧`() {
        listOf(40.0, 1000.0 / 29.97, 1000.0 / 59.94).forEach { frameMs ->
            // 起点必须落在帧栅格上：非整数帧率下「帧内的任意毫秒」本来就不成在回不去的帧上
            val start = (3 * frameMs).toLong()
            val fwd = FrameStep.next(null, start, frameMs, true, 0L)
            val back = FrameStep.next(fwd, start, frameMs, false, 0L)
            assertTrue("帧长 $frameMs 下前进没有往前走：$start -> $fwd", fwd > start)
            assertEquals("帧长 $frameMs 下来回点没有回到原位", start, back)
        }
    }

    @Test
    fun `帧长非法时保持原位不炸`() {
        assertEquals(50L, FrameStep.next(null, 50L, 0.0, true, 0L))
        assertEquals(50L, FrameStep.next(50L, 10L, -1.0, false, 0L))
    }
}
