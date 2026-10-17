package com.wotagei.cam.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧账纯函数与"计划→行为"桥（2026-10-17）。
 *
 * 每个数字都必须真的落在 [frameLedgerLine] 里：这条测试直接给一座完整计划，再对成文逐字段
 * 断言——内联在 `Log.i` 字符串里时，删掉任一字段测试仍会全绿，正是本仓反复吃过的假绿。
 */
class FrameLedgerTest {

    private fun report(
        camera: Long = 300L,
        callbacks: Long = 297L,
        consumed: Long = 250L,
        swaps: Long = 240L,
        swapFails: Long = 1L,
        mux: Long = 239L,
        buffered: Long = 5L,
        preMuxDropped: Long = 2L,
        arc: Long = 7L,
        lostMs: Long = 1_234L,
        lostFrames: Long = 37L,
        durationMs: Long = 10_000L,
        gpu: Boolean = true
    ) = FrameLedgerReport(
        partIndex = 3,
        gpu = gpu,
        durationMs = durationMs,
        cameraFrames = camera,
        glCallbacks = callbacks,
        glConsumed = consumed,
        encoderSwaps = swaps,
        swapFails = swapFails,
        muxSamples = mux,
        preMuxBuffered = buffered,
        preMuxDropped = preMuxDropped,
        arcDrops = arc,
        rotateLostMs = lostMs,
        rotateFramesUpper = lostFrames
    )

    // ---- ① 口径 ----

    @Test
    fun `合帧数等于回调数减消费数_且不为负`() {
        assertEquals(47L, frameMerged(297L, 250L))
        assertEquals(0L, frameMerged(250L, 250L))
        assertEquals("快照非事务时消费可能短暂领先回调，按 0 夹紧", 0L, frameMerged(10L, 12L))
    }

    @Test
    fun `逐级差值按四级口径计算且各自非负`() {
        val d = frameStageDrops(report())
        assertEquals(3L, d.cameraToGl)   // 300 - 297
        assertEquals(10L, d.glToSwap)    // 250 - 240
        assertEquals(1L, d.swapToMux)    // 240 - 239
    }

    @Test
    fun `厘帧率按时长换算_时长非正报 0`() {
        assertEquals(3_000L, frameCentiFps(300L, 10_000L))
        assertEquals(2_390L, frameCentiFps(239L, 10_000L))
        assertEquals(0L, frameCentiFps(300L, 0L))
        assertEquals(0L, frameCentiFps(0L, 10_000L))
        assertEquals("2.39", frameCentiText(239L))
        assertEquals("24.00", frameCentiText(2_400L))
        assertEquals("0.05", frameCentiText(5L))
    }

    // ---- ② 计划→行为桥：一行里每个字段都能被读回来 ----

    @Test
    fun `段帧账一行包含全部计划数字与逐级差`() {
        val line = frameLedgerLine(report())
        val must = listOf(
            "part=3",
            "路径=GPU",
            "墙钟=10000ms",
            "相机到达=300",
            "GL回调=297",
            "GL消费=250",
            "GL合帧=47",
            "编码swap=240",
            "swap失败=1",
            "muxer视频=239",
            "起录补写=5",
            "起录丢弃=2",
            "有意抽帧=7",
            "换段丢=1234ms/37帧(上界)",
            "逐级差=相机→GL 3/GL→swap 10/swap→mux 1",
            "实测相机=30.00fps",
            "成片=23.90fps"
        )
        must.forEach { assertTrue("帧账行缺少 `$it`：$line", line.contains(it)) }
    }

    @Test
    fun `DIRECT 路显式标注 GL 字段只描述预览链`() {
        val line = frameLedgerLine(report(gpu = false))
        assertTrue(line.contains("路径=DIRECT(GL仅预览)"))
    }

    @Test
    fun `快照短暂不单调时逐级差不出现负数`() {
        val d = frameStageDrops(report(camera = 100L, callbacks = 120L, consumed = 90L, swaps = 130L, mux = 140L))
        assertEquals(0L, d.cameraToGl)
        assertEquals(0L, d.glToSwap)
        assertEquals(0L, d.swapToMux)
    }

    // ---- ③ 会话段清单摘要（与 .drops.json 的 segments 同源） ----

    @Test
    fun `会话段清单按前缀和给出 first 与逐段丢失`() {
        val s = frameSessionSummary(listOf(72, 24, 25), listOf(0L, 1_200L, 300L))
        assertTrue(s.contains("段数=3"))
        assertTrue(s.contains("[段0 first=0 count=72 lost=0ms]"))
        assertTrue(s.contains("[段1 first=72 count=24 lost=1200ms]"))
        assertTrue(s.contains("[段2 first=96 count=25 lost=300ms]"))
    }

    @Test
    fun `空段表不产摘要_丢失表短于段表按 0 处理`() {
        assertEquals("", frameSessionSummary(emptyList(), emptyList()))
        val s = frameSessionSummary(listOf(10), emptyList())
        assertTrue(s.contains("lost=0ms"))
    }
}
