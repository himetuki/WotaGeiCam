package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 起录缓冲（muxer 启动前的已编码视频样本）纯逻辑（2026-10-17 第 2 笔）。
 *
 * 每条断言对照一份**独立参考实现**（按到达顺序保留最后 K 枚 / 对 PTS 排序），不复述 production
 * 的内部字段；容量边界、淘汰顺序、乱序 PTS、段尾清空各有用例。
 */
class PreMuxVideoBufferTest {

    private fun sample(ptsUs: Long, size: Int, flags: Int = 0) =
        PreMuxVideoBuffer.Sample(ptsUs, flags, ByteArray(size) { it.toByte() })

    @Test
    fun `容量恰好等于上限时不淘汰`() {
        val b = PreMuxVideoBuffer(10L)
        assertEquals(0L, b.offer(sample(1, 4)))
        assertEquals(0L, b.offer(sample(2, 6)))
        assertEquals(0, b.dropped)
        assertEquals(2, b.size)
        assertEquals(10L, b.bytes)
        assertEquals(listOf(1L, 2L), b.drainPtsOrdered().map { it.ptsUs })
    }

    @Test
    fun `超上限即淘汰最旧并计数`() {
        val b = PreMuxVideoBuffer(10L)
        b.offer(sample(1, 4))
        b.offer(sample(2, 6))
        assertEquals("第 3 枚 4B 进来后必须恰淘汰最旧的 4B 那枚", 1L, b.offer(sample(3, 4)))
        assertEquals(1L, b.dropped)
        assertEquals(2, b.size)
        assertEquals(listOf(2L, 3L), b.drainPtsOrdered().map { it.ptsUs })
    }

    @Test
    fun `单枚样本本身超上限直接拒收`() {
        val b = PreMuxVideoBuffer(10L)
        assertEquals(1L, b.offer(sample(1, 11)))
        assertEquals(1L, b.dropped)
        assertEquals(0, b.size)
        assertEquals(0L, b.bytes)
    }

    @Test
    fun `一次溢出可能连续淘汰多枚`() {
        val b = PreMuxVideoBuffer(10L)
        b.offer(sample(1, 4))
        b.offer(sample(2, 4))
        assertEquals(2L, b.offer(sample(3, 10))) // 18 - 10 = 8 字节需要淘汰前两枚
        assertEquals(2L, b.dropped)
        assertEquals(listOf(3L), b.drainPtsOrdered().map { it.ptsUs })
    }

    @Test
    fun `PTS 乱序输入补写时按升序且逐枚单调推进`() {
        val b = PreMuxVideoBuffer(1_024L)
        listOf(50L, 10L, 30L, 20L, 30L).forEach { b.offer(sample(it, 1)) }
        val drained = b.drainPtsOrdered().map { it.ptsUs }
        assertEquals(listOf(10L, 20L, 30L, 30L, 50L), drained)

        // 与 writeSample 同一条兜底：同戳/倒退一律推进到 last+1，最终写出序列严格单调
        var last = -1L
        for (pts in drained) {
            val fix = monotonicPts(last, pts)
            assertTrue("补写序列必须严格单调：last=$last cur=$pts fix=${fix.ptsUs}", fix.ptsUs > last)
            last = fix.ptsUs
        }
    }

    @Test
    fun `无溢出补写后的 PTS 与不丢样本的理想序列一致`() {
        val input = listOf(500L, 100L, 400L, 200L, 300L)
        val b = PreMuxVideoBuffer(1_024L)
        input.forEach { b.offer(sample(it, 1)) }
        assertEquals("补写序列与理想序列必须只差顺序，值集合逐枚一致", input.sorted(), b.drainPtsOrdered().map { it.ptsUs })
        assertEquals("无溢出不得丢样本", 0L, b.dropped)
    }

    @Test
    fun `溢出保留的是到达顺序的最后 K 枚_与参考实现一致`() {
        val sizes = listOf(3, 3, 3, 3, 3)
        val pts = listOf(1L, 2L, 3L, 4L, 5L)
        val b = PreMuxVideoBuffer(8L) // 只能装 2 枚（6B）；第 3~5 枚进来逐枚淘汰最旧
        sizes.indices.forEach { b.offer(sample(pts[it], sizes[it])) }
        // 独立参考：容量 8B、每枚 3B ⇒ 最终只保留最后 2 枚（到达顺序）
        val expected = pts.takeLast(2).sorted()
        assertEquals(expected, b.drainPtsOrdered().map { it.ptsUs })
        assertEquals(3L, b.dropped)
    }

    @Test
    fun `clear 返回剩余枚数并清空账`() {
        val b = PreMuxVideoBuffer(1_024L)
        b.offer(sample(1, 5))
        b.offer(sample(2, 5))
        assertEquals(2, b.clear())
        assertEquals(0, b.size)
        assertEquals(0L, b.bytes)
        assertEquals(emptyList<Long>(), b.drainPtsOrdered().map { it.ptsUs })
    }

    /**
     * 接线守卫：纯类再正确，没接进 `!muxStarted` 分支也等于没修。
     * 只钉"生产源码真的建缓冲、真的 offer、真的在 muxer 启动后补写"三个锚点。
     */
    @Test
    fun `起录缓冲必须真的接在录制链上`() {
        val raw = mainSourceText("record/CodecRecorder.kt")
        val src = codeOnly(raw)
        assertTrue("未创建起录缓冲", src.contains("PreMuxVideoBuffer(PRE_MUX_BUFFER_MAX_BYTES)"))
        assertTrue("muxer 未启动分支必须 offer 复制样本", src.contains("preMuxBuffer?.offer("))
        assertTrue("muxer 启动后必须补写", src.contains("flushPreMuxSamples(mx, videoTrack)"))
        assertTrue("段结束必须释放缓冲", raw.contains("起录缓冲随段释放"))
        assertEquals("字节上限必须固定为 32MiB（依据见常量 KDoc）", 32L * 1024 * 1024, CodecRecorder.PRE_MUX_BUFFER_MAX_BYTES)
    }
}
