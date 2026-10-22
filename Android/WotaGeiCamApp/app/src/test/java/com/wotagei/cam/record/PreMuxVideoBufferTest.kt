package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 起录缓冲（muxer 启动前的已编码视频样本）纯逻辑（2026-10-17 第 2 笔 + 复审轮）。
 *
 * 每条断言对照一份**独立参考实现**（按到达顺序保留最后 K 枚 / 淘汰对齐关键帧），不复述
 * production 的内部字段；容量边界、淘汰顺序、关键帧对齐、乱序 PTS、段尾清空各有用例。
 *
 * 复审口径变更：补写**不按 PTS 排序**（编码器输出是访问单元顺序，B 帧/非单调 PTS 排序会
 * 乱解码顺序），PTS 单调兜底由 `writeSample` 的 monotonicPts 负责。
 */
class PreMuxVideoBufferTest {

    /** `MediaCodec.BUFFER_FLAG_KEY_FRAME` 字面值（测试不引 android.*） */
    private val keyFrame = 1

    private fun sample(ptsUs: Long, size: Int, flags: Int = keyFrame) =
        PreMuxVideoBuffer.Sample(ptsUs, flags, ByteArray(size) { it.toByte() })

    @Test
    fun `容量恰好等于上限时不淘汰`() {
        val b = PreMuxVideoBuffer(10L)
        assertEquals(0L, b.offer(sample(1, 4)))
        assertEquals(0L, b.offer(sample(2, 6)))
        assertEquals(0, b.dropped)
        assertEquals(2, b.size)
        assertEquals(10L, b.bytes)
        assertEquals(listOf(1L, 2L), b.drainArrivalOrder().map { it.ptsUs })
    }

    @Test
    fun `超上限即淘汰最旧并计数`() {
        val b = PreMuxVideoBuffer(10L)
        b.offer(sample(1, 4))
        b.offer(sample(2, 6))
        assertEquals("第 3 枚 4B 进来后必须恰淘汰最旧的 4B 那枚", 1L, b.offer(sample(3, 4)))
        assertEquals(1L, b.dropped)
        assertEquals(2, b.size)
        assertEquals(listOf(2L, 3L), b.drainArrivalOrder().map { it.ptsUs })
    }

    @Test
    fun `单枚样本本身超上限直接拒收`() {
        val b = PreMuxVideoBuffer(10L)
        assertFalse("超大样本必须能被调用方先判掉，不做复制", b.canAccept(11))
        assertTrue(b.canAccept(10))
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
        assertEquals(listOf(3L), b.drainArrivalOrder().map { it.ptsUs })
    }

    @Test
    fun `PTS 乱序输入按到达顺序补写_单调兜底交给 writeSample`() {
        val input = listOf(50L, 10L, 30L, 20L, 30L)
        val b = PreMuxVideoBuffer(1_024L)
        input.forEach { b.offer(sample(it, 1)) }
        val drained = b.drainArrivalOrder().map { it.ptsUs }
        // 排序会让访问单元顺序错乱（B 帧/PTS 非单调）；补写必须保持收样顺序
        assertEquals("补写顺序必须逐枚等于到达顺序", input, drained)

        // 与 writeSample 同一条兜底：同戳/倒退一律推进到 last+1，最终写出序列严格单调
        var last = -1L
        for (pts in drained) {
            val fix = monotonicPts(last, pts)
            assertTrue("补写序列必须严格单调：last=$last cur=$pts fix=${fix.ptsUs}", fix.ptsUs > last)
            last = fix.ptsUs
        }
    }

    @Test
    fun `无溢出补写后的样本集合与不丢样本的理想序列一致`() {
        val input = listOf(500L, 100L, 400L, 200L, 300L)
        val b = PreMuxVideoBuffer(1_024L)
        input.forEach { b.offer(sample(it, 1)) }
        assertEquals("无溢出不得丢样本且顺序不变", input, b.drainArrivalOrder().map { it.ptsUs })
        assertEquals("无溢出不得丢样本", 0L, b.dropped)
    }

    @Test
    fun `溢出保留的是到达顺序的最后 K 枚_与参考实现一致`() {
        val sizes = listOf(3, 3, 3, 3, 3)
        val pts = listOf(1L, 2L, 3L, 4L, 5L)
        val b = PreMuxVideoBuffer(8L) // 只能装 2 枚（6B）；第 3~5 枚进来逐枚淘汰最旧
        sizes.indices.forEach { b.offer(sample(pts[it], sizes[it])) }
        // 独立参考：容量 8B、每枚 3B ⇒ 最终只保留最后 2 枚（到达顺序）
        val expected = pts.takeLast(2)
        assertEquals(expected, b.drainArrivalOrder().map { it.ptsUs })
        assertEquals(3L, b.dropped)
    }

    @Test
    fun `溢出淘汰对齐关键帧_保留段不以P帧开头`() {
        val b = PreMuxVideoBuffer(10L)
        b.offer(sample(1, 2, keyFrame))
        b.offer(sample(2, 2, 0))
        b.offer(sample(3, 2, 0))
        b.offer(sample(4, 2, 0))
        b.offer(sample(5, 2, 0))
        // 第 6 枚关键帧进来后超 2B：先按字节淘汰旧 K1，再对齐关键帧丢掉 P2..P5
        assertEquals(5L, b.offer(sample(6, 2, keyFrame)))
        assertEquals(5L, b.dropped)
        assertEquals(listOf(6L), b.drainArrivalOrder().map { it.ptsUs })
    }

    @Test
    fun `溢出后一枚关键帧都不剩时整体清空并计数`() {
        val b = PreMuxVideoBuffer(10L)
        (1L..5L).forEach { b.offer(sample(it, 2, 0)) }
        // 全是 P 帧：字节淘汰 1 枚后仍以 P 打头且没有关键帧可对齐 ⇒ 全部丢弃
        assertEquals(6L, b.offer(sample(6, 2, 0)))
        assertEquals(6L, b.dropped)
        assertEquals(0, b.size)
        assertEquals(0L, b.bytes)
        assertEquals(emptyList<Long>(), b.drainArrivalOrder().map { it.ptsUs })
    }

    @Test
    fun `clear 返回剩余枚数并清空账`() {
        val b = PreMuxVideoBuffer(1_024L)
        b.offer(sample(1, 5))
        b.offer(sample(2, 5))
        assertEquals(2, b.clear())
        assertEquals(0, b.size)
        assertEquals(0L, b.bytes)
        assertEquals(emptyList<Long>(), b.drainArrivalOrder().map { it.ptsUs })
    }

    /**
     * 接线守卫：纯类再正确，没接进 `!muxStarted` 分支也等于没修。
     * 钉"生产源码真的建缓冲、先判容量再复制、真的在 muxer 启动后按到达顺序补写"四个锚点。
     */
    @Test
    fun `起录缓冲必须真的接在录制链上`() {
        val raw = mainSourceText("record/CodecRecorder.kt")
        val src = codeOnly(raw)
        assertTrue("未创建起录缓冲", src.contains("PreMuxVideoBuffer(PRE_MUX_BUFFER_MAX_BYTES)"))
        val acceptAt = src.indexOf("canAccept(bi.size)")
        val copyAt = src.indexOf("ByteArray(bi.size)")
        assertTrue("必须先用 canAccept 判断再复制（否则超大样本仍会瞬时分配）", acceptAt >= 0 && acceptAt < copyAt)
        assertTrue("muxer 未启动分支必须 offer 复制样本", src.contains("preMuxBuffer?.offer("))
        assertTrue("muxer 启动后必须按到达顺序补写", src.contains("flushPreMuxSamples(mx, videoTrack)"))
        assertTrue("补写不得按 PTS 排序", src.contains("drainArrivalOrder()") && !src.contains("sortBy"))
        assertTrue("段结束必须释放缓冲", raw.contains("起录缓冲随段释放"))
        assertEquals("字节上限必须固定为 32MiB（依据见常量 KDoc）", 32L * 1024 * 1024, CodecRecorder.PRE_MUX_BUFFER_MAX_BYTES)
    }
}
