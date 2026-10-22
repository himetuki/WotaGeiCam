package com.wotagei.cam.record

import kotlin.math.abs
import kotlin.math.max

/**
 * 预校准用的单路 PCM 累加器（**纯 Kotlin**，JVM 直测）：
 * - 交织 PCM16 → 单声道 float（声道均值）；
 * - 以**两路共同的时间原点** `basePtsUs` 落位，PTS 空洞补零（两路 feeder 启动/读缓冲差异
 *   不再被"各自首包归零"吃掉）；
 * - 锁存是否见过非零样本（600ms 双静音早退判据）。
 *
 * 时间落位公式：`targetFrames = (pktPtsUs - basePtsUs) * sampleRate / 1_000_000`。
 * 包内容紧接在 target 之后写入；比目标短则补零，比目标长则顺延（不覆盖已有样本）。
 */
internal class PreflightMonoPcm(
    private val sampleRate: Int,
    channels: Int,
    private val basePtsUs: Long
) {
    private val ch = max(1, channels)
    private val frameBytes = ch * 2
    private var data = FloatArray(1 shl 14)
    private var size = 0
    private var peak = 0f

    val frameCount: Int get() = size

    fun append(pcm: ByteArray, ptsUs: Long) {
        val target = ((ptsUs - basePtsUs) * sampleRate / 1_000_000L).toInt().coerceAtLeast(0)
        if (target > size) {
            ensure(target - size)
            repeat(target - size) { data[size++] = 0f }
        }
        val frames = pcm.size / frameBytes
        if (frames <= 0) return
        ensure(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until ch) {
                val i = (f * ch + c) * 2
                var v = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
                if (v and 0x8000 != 0) v -= 0x10000
                sum += v
            }
            val sample = sum / (ch * 32768f)
            data[size++] = sample
            val a = abs(sample)
            if (a > peak) peak = a
        }
    }

    fun durationMs(): Long = size.toLong() * 1000L / max(1, sampleRate)

    fun isSilent(): Boolean = peak <= 1e-6f

    fun toArray(): FloatArray = data.copyOf(size)

    private fun ensure(n: Int) {
        if (size + n <= data.size) return
        var cap = data.size
        while (cap < size + n) cap *= 2
        data = data.copyOf(cap)
    }
}
