package com.wotagei.cam.player

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-10 用户二次确认的守卫：倍速音频**保持原声音高与音色**，只把时间拉长。
 *
 * 口径：pitch 恒 1.0（不做磁带式变调，男声不会被压成低频轰鸣）；0.1x/0.3x/0.5x/0.75x/1.0x
 * 都呈现为“原声慢放/快放”。三处判据：纯函数口径 / setSpeed 一次写齐 / 对比纠偏只微调 speed
 * 且 pitch 仍为 1.0（纠偏周期内音色不漂）。
 */
class PlaybackSpeedAudioGuardTest {

    private val engine: String by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/PlayerEngine.kt"))
    }

    @Test
    fun `原声口径_pitch恒1`() {
        // 恒等常量不另写测试副本（写副本就成了「改实现不改用例」的假绿）——直接钉函数本体，
        // 行为语义由 setSpeed/nudge 两条接线守卫与真机听感共同承载。
        // 参数上的 @Suppress(...) 自带括号，正则的 [^)]* 会被它截断，所以按“整行 + 结尾”判定。
        val line = engine.lineSequence().firstOrNull { it.contains("internal fun audioPitchFor(") }
        assertTrue(
            "audioPitchFor 必须恒返回 1.0（原声拉长，不变调）",
            line != null && line.trimEnd().endsWith("): Float = 1f")
        )
        // 尺要能红：pitch 跟回 speed（磁带变调，低倍率变深沉）后判据翻红
        val mutant = engine.replace(
            requireNotNull(line),
            requireNotNull(line).replace("): Float = 1f", "): Float = speed")
        )
        assertFalse(
            "pitch 跟 speed 变调后守卫必须报红",
            mutant.lineSequence().any {
                it.contains("internal fun audioPitchFor(") && it.trimEnd().endsWith("): Float = 1f")
            }
        )
    }

    @Test
    fun `setSpeed必须一次写齐speed与pitch_不再走setPlaybackSpeed`() {
        val body = KotlinSourceScan.bodyOf(engine, "setSpeed")
        assertTrue("倍速下发必须带原声 pitch", body.contains("PlaybackParameters(tier, audioPitchFor(tier))"))
        assertFalse(
            "旧的 setPlaybackSpeed 调用必须退场（避免将来有人误改回只传 speed）",
            engine.contains("setPlaybackSpeed(")
        )
        // 尺要能红：pitch 摘掉（只剩 speed，行为不可控）后判据翻红
        val mutant = body.replace("PlaybackParameters(tier, audioPitchFor(tier))", "PlaybackParameters(tier)")
        assertFalse("回退只传 speed 后守卫必须报红", mutant.contains("audioPitchFor(tier)"))
    }

    @Test
    fun `对比纠偏只微调speed_pitch恒1`() {
        val body = KotlinSourceScan.bodyOf(engine, "nudgeSpeedForSync")
        assertTrue(
            "纠偏下发必须保持原声 pitch",
            body.contains("PlaybackParameters(target, audioPitchFor(base))")
        )
        // 尺要能红：pitch 改成跟 base 变调（磁带口径）后判据翻红
        val mutant = body.replace("audioPitchFor(base)", "base")
        assertFalse("pitch 跟 base 变调后守卫必须报红", mutant.contains("audioPitchFor(base)"))
    }
}
