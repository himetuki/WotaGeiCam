package com.wotagei.cam.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 对比播放剪辑模型（[CompareAudio]）的纯函数单测。
 *
 * 三类断言：
 * - [CompareAudio.audioPlanOf] 全表（Side × 轨种类 × 双轨可用）；
 * - [CompareAudio.applyOrderOf] 的**命令顺序**（先静音另一侧 → 再设/清 override → 最后落音量）；
 * - **桥函数** [CompareAudio.selectionEffectOf]：计划经命令跑一遍后的可观察行为（左/右音量 + override 目标），
 *   期望值全部手写字面量，不调被测实现反推。
 *
 * 突变即红的点（已实跑，见汇报）：交换「先静音/后 override」顺序 → 顺序用例红；
 * 把 CAP 的 groupIndex 写成 0 → 计划/桥用例红；把未选中侧音量写 1f → 桥用例红。
 */
class CompareAudioTest {

    // ---------------------------------------------------------------- 轨序单一映射

    @Test
    fun `组下标映射_环境0内录1`() {
        // 与 TrackSync / 批 4 单播放页同口径：容器里环境轨=音频组 0、内录轨=音频组 1
        assertEquals(0, audioGroupIndexOf(AudioTrackKind.ENV))
        assertEquals(1, audioGroupIndexOf(AudioTrackKind.CAP))
    }

    // ---------------------------------------------------------------- audioPlanOf 全表

    @Test
    fun `计划_左片环境音_右侧静音且清override`() {
        val p = CompareAudio.audioPlanOf(audioSide = Side.LEFT, track = AudioTrackKind.ENV, sideHasDual = true)
        assertEquals(Side.LEFT, p.fullSide)
        assertEquals(Side.RIGHT, p.mutedSide)
        assertEquals(Side.LEFT, p.overrideSide)
        assertEquals(null, p.overrideGroupIndex)
    }

    @Test
    fun `计划_左片内录_左override组1`() {
        val p = CompareAudio.audioPlanOf(audioSide = Side.LEFT, track = AudioTrackKind.CAP, sideHasDual = true)
        assertEquals(Side.LEFT, p.fullSide)
        assertEquals(Side.RIGHT, p.mutedSide)
        assertEquals(Side.LEFT, p.overrideSide)
        assertEquals(1, p.overrideGroupIndex)
    }

    @Test
    fun `计划_右片环境音_左侧静音且清override`() {
        val p = CompareAudio.audioPlanOf(audioSide = Side.RIGHT, track = AudioTrackKind.ENV, sideHasDual = true)
        assertEquals(Side.RIGHT, p.fullSide)
        assertEquals(Side.LEFT, p.mutedSide)
        assertEquals(Side.RIGHT, p.overrideSide)
        assertEquals(null, p.overrideGroupIndex)
    }

    @Test
    fun `计划_右片内录_右override组1`() {
        val p = CompareAudio.audioPlanOf(audioSide = Side.RIGHT, track = AudioTrackKind.CAP, sideHasDual = true)
        assertEquals(Side.RIGHT, p.fullSide)
        assertEquals(Side.LEFT, p.mutedSide)
        assertEquals(Side.RIGHT, p.overrideSide)
        assertEquals(1, p.overrideGroupIndex)
    }

    @Test
    fun `计划_单音轨片请求内录_回落环境轨清override`() {
        // 非双音轨片即便请求 CAP 也回落 ENV（不产生指向不存在组的 override）
        val left = CompareAudio.audioPlanOf(audioSide = Side.LEFT, track = AudioTrackKind.CAP, sideHasDual = false)
        assertEquals(null, left.overrideGroupIndex)
        assertEquals(Side.LEFT, left.overrideSide)
        val right = CompareAudio.audioPlanOf(audioSide = Side.RIGHT, track = AudioTrackKind.CAP, sideHasDual = false)
        assertEquals(null, right.overrideGroupIndex)
        assertEquals(Side.RIGHT, right.overrideSide)
    }

    // ---------------------------------------------------------------- 命令顺序

    @Test
    fun `命令顺序_先静音另一侧_再设override_最后落音量`() {
        val cmds = CompareAudio.applyOrderOf(CompareAudio.audioPlanOf(audioSide = Side.LEFT, track = AudioTrackKind.CAP, sideHasDual = true))
        assertEquals(
            listOf(
                EngineCommand.SetVolume(Side.RIGHT, 0f),
                EngineCommand.SetAudioOverride(Side.LEFT, 1),
                EngineCommand.SetVolume(Side.LEFT, 1f)
            ),
            cmds
        )
    }

    @Test
    fun `命令顺序_环境音时第二条是清override`() {
        val cmds = CompareAudio.applyOrderOf(CompareAudio.audioPlanOf(audioSide = Side.RIGHT, track = AudioTrackKind.ENV, sideHasDual = true))
        assertEquals(
            listOf(
                EngineCommand.SetVolume(Side.LEFT, 0f),
                EngineCommand.ClearAudioOverride(Side.RIGHT),
                EngineCommand.SetVolume(Side.RIGHT, 1f)
            ),
            cmds
        )
    }

    // ---------------------------------------------------------------- 桥函数 selectionEffectOf

    @Test
    fun `桥_左片环境音_左1右0清左override`() {
        val e = CompareAudio.selectionEffectOf(audioSide = Side.LEFT, track = AudioTrackKind.ENV, sideHasDual = true)
        assertEquals(1f, e.leftVolume, 0f)
        assertEquals(0f, e.rightVolume, 0f)
        assertEquals(OverrideTarget(Side.LEFT, null), e.override)
    }

    @Test
    fun `桥_左片内录_左1右0左override组1`() {
        val e = CompareAudio.selectionEffectOf(audioSide = Side.LEFT, track = AudioTrackKind.CAP, sideHasDual = true)
        assertEquals(1f, e.leftVolume, 0f)
        assertEquals(0f, e.rightVolume, 0f)
        assertEquals(OverrideTarget(Side.LEFT, 1), e.override)
    }

    @Test
    fun `桥_左片请求内录但单音轨_回落清左override`() {
        val e = CompareAudio.selectionEffectOf(audioSide = Side.LEFT, track = AudioTrackKind.CAP, sideHasDual = false)
        assertEquals(1f, e.leftVolume, 0f)
        assertEquals(0f, e.rightVolume, 0f)
        assertEquals(OverrideTarget(Side.LEFT, null), e.override)
    }

    @Test
    fun `桥_右片环境音_左0右1清右override`() {
        val e = CompareAudio.selectionEffectOf(audioSide = Side.RIGHT, track = AudioTrackKind.ENV, sideHasDual = true)
        assertEquals(0f, e.leftVolume, 0f)
        assertEquals(1f, e.rightVolume, 0f)
        assertEquals(OverrideTarget(Side.RIGHT, null), e.override)
    }

    @Test
    fun `桥_右片内录_左0右1右override组1`() {
        val e = CompareAudio.selectionEffectOf(audioSide = Side.RIGHT, track = AudioTrackKind.CAP, sideHasDual = true)
        assertEquals(0f, e.leftVolume, 0f)
        assertEquals(1f, e.rightVolume, 0f)
        assertEquals(OverrideTarget(Side.RIGHT, 1), e.override)
    }

    @Test
    fun `桥_右片请求内录但单音轨_回落清右override`() {
        val e = CompareAudio.selectionEffectOf(audioSide = Side.RIGHT, track = AudioTrackKind.CAP, sideHasDual = false)
        assertEquals(0f, e.leftVolume, 0f)
        assertEquals(1f, e.rightVolume, 0f)
        assertEquals(OverrideTarget(Side.RIGHT, null), e.override)
    }
}
