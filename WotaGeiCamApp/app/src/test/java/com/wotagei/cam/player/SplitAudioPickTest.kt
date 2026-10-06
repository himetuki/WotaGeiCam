package com.wotagei.cam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分屏右窗音频计划器 [splitAudioPlanOf] / 桥函数 [splitAudioEffectOf] 的纯函数单测。
 *
 * 与对比页 [CompareAudio] 的关键差异（本类要钉住的红线）：分屏只有**一台**引擎，
 * 计划里没有 mutedSide——「只播其一」由 Media3 override 天然保证，**不许**额外压音量
 * （误抄对比页的 applyOrderOf 会把唯一那台引擎压成静音 = 分屏彻底没声）。
 *
 * 期望值全部手写字面量，不调被测实现反推。突变即红的点（已实跑，见汇报）：
 * 把内录组下标写成 0 / 把「双音轨选内录」写成清 override → 本类转红。
 */
class SplitAudioPickTest {

    // ---------------------------------------------------------------- splitAudioPlanOf 全表（dual × pick）

    @Test
    fun `计划_双音轨选环境_清override且音量1`() {
        val p = splitAudioPlanOf(dualAudio = true, pick = AudioTrackKind.ENV)
        assertEquals(null, p.overrideGroupIndex)
        assertEquals(1f, p.volume, 0f)
    }

    @Test
    fun `计划_双音轨选内录_override组1且音量1`() {
        val p = splitAudioPlanOf(dualAudio = true, pick = AudioTrackKind.CAP)
        assertEquals(1, p.overrideGroupIndex)
        assertEquals(1f, p.volume, 0f)
    }

    @Test
    fun `计划_单音轨选环境_清override`() {
        val p = splitAudioPlanOf(dualAudio = false, pick = AudioTrackKind.ENV)
        assertEquals(null, p.overrideGroupIndex)
        assertEquals(1f, p.volume, 0f)
    }

    @Test
    fun `计划_单音轨请求内录_回落清override`() {
        // 非双音轨片即便请求内录也回落环境轨（不产生指向不存在组的 override，与 audioPlanOf 同口径）
        val p = splitAudioPlanOf(dualAudio = false, pick = AudioTrackKind.CAP)
        assertEquals(null, p.overrideGroupIndex)
        assertEquals(1f, p.volume, 0f)
    }

    // ---------------------------------------------------------------- 桥函数（测桥本身，不只看计划字段）

    @Test
    fun `桥_双音轨选内录_要override组1音量1`() {
        val e = splitAudioEffectOf(dualAudio = true, pick = AudioTrackKind.CAP)
        assertTrue(e.override)
        assertEquals(1, e.groupIndex)
        assertEquals(1f, e.volume, 0f)
    }

    @Test
    fun `桥_双音轨选环境_不要override且音量1`() {
        val e = splitAudioEffectOf(dualAudio = true, pick = AudioTrackKind.ENV)
        assertFalse(e.override)
        assertEquals(null, e.groupIndex)
        assertEquals(1f, e.volume, 0f)
    }

    @Test
    fun `桥_单音轨选内录_回落不要override`() {
        val e = splitAudioEffectOf(dualAudio = false, pick = AudioTrackKind.CAP)
        assertFalse(e.override)
        assertEquals(null, e.groupIndex)
        assertEquals(1f, e.volume, 0f)
    }

    // ---------------------------------------------------------------- 组下标单一映射

    @Test
    fun `映射_环境0内录1`() {
        assertEquals(0, audioGroupIndexOf(AudioTrackKind.ENV))
        assertEquals(1, audioGroupIndexOf(AudioTrackKind.CAP))
    }
}
