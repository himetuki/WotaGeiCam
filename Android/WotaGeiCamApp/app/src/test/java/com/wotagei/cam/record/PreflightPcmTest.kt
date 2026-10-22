package com.wotagei.cam.record

import com.wotagei.cam.core.AudioAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 预校准 PCM 累加器（纯类）行为：
 * - 共同 t0 落位：PTS 空洞补零、早于 t0 的包不带负前导；
 * - 48k 双声道 PCM16 → 单声道 → 重采样 8k；
 * - 非零锁存（600ms 双静音早退判据的数据基础）。
 */
class PreflightPcmTest {

    private fun stereoPcm16(frames: Int, left: Short, right: Short): ByteArray {
        val out = ByteArray(frames * 4)
        for (f in 0 until frames) {
            val i = f * 4
            out[i] = (left.toInt() and 0xFF).toByte()
            out[i + 1] = ((left.toInt() shr 8) and 0xFF).toByte()
            out[i + 2] = (right.toInt() and 0xFF).toByte()
            out[i + 3] = ((right.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun monoPcm16(frames: Int, value: Short): ByteArray {
        val out = ByteArray(frames * 2)
        for (f in 0 until frames) {
            out[f * 2] = (value.toInt() and 0xFF).toByte()
            out[f * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `48k双声道转8k单声道_常量电平不漂`() {
        val pcm = PreflightMonoPcm(sampleRate = 48_000, channels = 2, basePtsUs = 0L)
        pcm.append(stereoPcm16(4_800, left = 16_384, right = 16_384), ptsUs = 0L)
        assertEquals(4_800, pcm.frameCount)
        val mono = pcm.toArray()
        val out = AudioAlign.resample(mono, 48_000, 8_000, 8)
        assertEquals(800, out.size)
        out.forEach { assertEquals(0.5f, it, 1e-3f) }
    }

    @Test
    fun `PTS空洞补零_共同t0保留前导`() {
        val base = 1_000_000L
        val pcm = PreflightMonoPcm(sampleRate = 48_000, channels = 2, basePtsUs = base)
        pcm.append(stereoPcm16(10, 16_384, 16_384), ptsUs = base)
        // 20ms 后第二包：48k 下应在第 960 帧开始，10..959 必须是补的静音
        pcm.append(stereoPcm16(10, 16_384, 16_384), ptsUs = base + 20_000L)
        val samples = pcm.toArray()
        assertEquals(970, samples.size)
        for (i in 10 until 960) assertEquals("空洞 $i 必须补零", 0f, samples[i], 1e-6f)
        assertTrue("第二包必须落在 960", abs(samples[960] - 0.5f) < 1e-3f)
    }

    @Test
    fun `早于共同t0的包不带负前导`() {
        val base = 5_000_000L
        val pcm = PreflightMonoPcm(sampleRate = 48_000, channels = 2, basePtsUs = base)
        pcm.append(stereoPcm16(10, 1_000, 1_000), ptsUs = base - 100_000L)
        assertEquals("负 PTS 包从 0 开始，不许有负下标/前导", 10, pcm.frameCount)
        assertFalse(pcm.isSilent())
    }

    @Test
    fun `非零检测_全零静音与任一非零`() {
        val pcm = PreflightMonoPcm(sampleRate = 8_000, channels = 1, basePtsUs = 0L)
        pcm.append(ByteArray(1_600), ptsUs = 0L)
        assertTrue("全零必须是静音", pcm.isSilent())
        pcm.append(monoPcm16(1, 1_000), ptsUs = 200_000L)
        assertFalse("见过非零后不再是静音", pcm.isSilent())
    }
}
