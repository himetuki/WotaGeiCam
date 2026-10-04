package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频 EOS 判定 / 修复路音频时间戳基的**源码结构守卫**（逻辑自检批次 J）。
 *
 * 两处都依赖真机 AudioRecord/MediaCodec，JVM 行为测试造不出来，按本循环守卫口径锁函数体：
 * - feedAudioInput 的 EOS 判定只许看 pts<0：哨兵 data 恰 4 字节与「双声道 2 帧合法短读」同形，
 *   长度支路（01028b9 加 pts<0 时留在 OR 里的旧判定）会把真 PCM 误判成 EOS 提前掐断音轨；
 * - drainAudioUpTo 的音频基必须锚**视频首帧**（decodePtsBase，两轨 sampleTime 同一条容器时间轴）：
 *   按音轨自身首样本归一，音轨晚启动的源整条音轨前移 = 修复成片音画错位；视频起点之前的音频
 *   要有丢弃支防负 PTS。
 */
class AudioFeedEosGuardTest {

    private fun maskedMain(rel: String): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel))

    @Test
    fun `EOS判定只看pts负不看包长`() {
        val body = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(maskedMain("record/CodecRecorder.kt"), "feedAudioInput")
        )
        assertTrue(
            "feedAudioInput 的 EOS 判定必须走 pts<0（真实包 pts 恒为 nanoTime µs、恒正，哨兵恒 -1）",
            body.contains("pkt.ptsUs < 0")
        )
        assertFalse(
            "feedAudioInput 不得再按包长判 EOS（AudioFeeder.EOS_SIZE 支路会把 4 字节合法短读" +
                "误判成 EOS 提前掐断音轨——01028b9 遗留的半截修复）",
            body.contains("EOS_SIZE")
        )
    }

    @Test
    fun `修复路音频时间戳基锚视频首帧`() {
        val body = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(maskedMain("record/ArcRepairRunner.kt"), "drainAudioUpTo")
        )
        assertTrue(
            "drainAudioUpTo 的音频基必须取视频首帧（decodePtsBase，两轨同一容器时间轴）；" +
                "按音轨自身首样本归一会把晚启动的音轨整体前移，修复成片音画错位",
            body.contains("decodePtsBase")
        )
        assertTrue(
            "视频起点之前的音频（音轨早启动的源）必须有丢弃支，防负 PTS / 0 点重播",
            body.contains("norm < 0L")
        )
    }
}
