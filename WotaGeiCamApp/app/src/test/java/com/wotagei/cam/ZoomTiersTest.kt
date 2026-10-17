package com.wotagei.cam

import com.wotagei.cam.ui.widget.zoomLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 变焦刻度梯的取档规则（2026-10-17 快捷倍率 chip 删除后由 `PillSlider.ladder` 独家消费）：
 * 只保留**运行时变焦范围里存在**的产品档（0.5/1/1.5/2/2.5/3/4/5/6/8/10），
 * 范围来自 `CameraCharacteristics`，所以这里喂不同范围就能锁住行为，不写死机型数值。
 */
class ZoomTiersTest {

    @Test
    fun ultraWideCapablePhoneGetsHalfTier() {
        // 超广角端 0.6× 起步的机器：0.5× 不在范围内，不该出现（拖也拖不进去）
        val tiers = zoomLadder(0.6f, 10f)
        assertFalse(tiers.contains(0.5f))
        assertEquals(listOf(1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f, 8f, 10f), tiers)
    }

    @Test
    fun phoneWithTrueHalfXShowsIt() {
        assertEquals(listOf(0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 4f), zoomLadder(0.5f, 4f))
    }

    @Test
    fun digitalOnlyLensKeepsWhatFallsInside() {
        // 长焦端 2×–6×：只有 2/2.5/3/4/5/6 有意义
        assertEquals(listOf(2f, 2.5f, 3f, 4f, 5f, 6f), zoomLadder(2f, 6f))
    }

    @Test
    fun fixedLensHasOnlyOneTier() {
        // 定焦（范围退化 1..1）只剩 1×：梯只有 1 颗，PillSlider 会退回连续模式（禁拖），
        // 但梯本身必须如实只剩这一档，不得凭空冒出范围外的倍率。
        assertEquals(listOf(1f), zoomLadder(1f, 1f))
    }

    @Test
    fun fullLadderIsInsideAWideRangeLens() {
        assertEquals(
            listOf(0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f, 8f, 10f),
            zoomLadder(0.5f, 10f)
        )
    }

    @Test
    fun outOfRangeLensYieldsEmptyLadder() {
        assertTrue(zoomLadder(12f, 20f).isEmpty())
    }
}
