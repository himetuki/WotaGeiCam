package com.wotagei.cam

import com.wotagei.cam.ui.widget.diopterLadder
import com.wotagei.cam.ui.widget.evLadder
import com.wotagei.cam.ui.widget.isoLadder
import com.wotagei.cam.ui.widget.kelvinLadder
import com.wotagei.cam.ui.widget.nearestLadderIndex
import com.wotagei.cam.ui.widget.percentLadder
import com.wotagei.cam.ui.widget.shutterLadder
import com.wotagei.cam.ui.widget.tintLadder
import com.wotagei.cam.ui.widget.zoomLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刻度滑杆的梯子构造（2026-10-17 需求）：全部纯函数，JVM 直接锁行为。
 *
 * 每类各钉两件事：① 产品档位/步长；② 运行时范围过滤（越界档一律不出现）。
 * `nearestLadderIndex` 另锁拖动吸附边界（低于首档 / 高于末档 / 恰中点）。
 */
class SliderLadderTest {

    @Test
    fun `就近吸附：范围外贴端档、中点取低档`() {
        val ladder = listOf(10f, 20f, 30f)
        assertEquals("低于首档落在索引 0", 0, nearestLadderIndex(0f, ladder))
        assertEquals("高于末档落在末索引", 2, nearestLadderIndex(99f, ladder))
        assertEquals("贴近低档", 0, nearestLadderIndex(12f, ladder))
        assertEquals("贴近高档", 2, nearestLadderIndex(28f, ladder))
        assertEquals("恰中点取低档（向下吸附）", 0, nearestLadderIndex(15f, ladder))
        assertEquals("恰中点取低档（第二段）", 1, nearestLadderIndex(25f, ladder))
        assertEquals("空表返回 0（调用方以禁用兜底）", 0, nearestLadderIndex(5f, emptyList()))
    }

    @Test
    fun `ISO 三分之一档序列按设备范围过滤`() {
        // 产品序列首尾：50…12800
        val full = isoLadder(50, 12800)
        assertEquals(25, full.size)
        assertEquals(50f, full.first())
        assertEquals(12800f, full.last())
        assertTrue(full.contains(125f))
        assertTrue(full.contains(2500f))
        // 设备范围 100..16000：低于 100 的被滤掉，16000 不在序列里也不凭空生成
        assertEquals(listOf(100f, 125f, 160f, 200f), isoLadder(100, 700).take(4))
        assertFalse(isoLadder(100, 16000).contains(50f))
        assertEquals(12800f, isoLadder(100, 16000).last())
        assertEquals("范围落在档间也必须只留范围内档", listOf(160f, 200f), isoLadder(130, 210))
    }

    @Test
    fun `EV 每个整数补偿步一档`() {
        assertEquals(listOf(-2f, -1f, 0f, 1f, 2f), evLadder(-2, 2))
        assertEquals(25, evLadder(-12, 12).size)
        assertTrue(evLadder(3, 2).isEmpty())
    }

    @Test
    fun `色温 2000到10000共17档`() {
        val k = kelvinLadder(2000, 10000)
        assertEquals(17, k.size)
        assertEquals(2000f, k.first())
        assertEquals(10000f, k.last())
        assertTrue(k.all { it.toInt() % 500 == 0 })
        // 设备范围收窄：仍按 500 网格对齐（2100 起 → 2500 第一档）
        assertEquals(listOf(2500f, 3000f), kelvinLadder(2100, 3000))
    }

    @Test
    fun `色调 -50到50共21档`() {
        val t = tintLadder(-50, 50)
        assertEquals(21, t.size)
        assertEquals(-50f, t.first())
        assertEquals(50f, t.last())
        assertTrue(t.all { it.toInt() % 5 == 0 })
        assertEquals(listOf(-5f, 0f, 5f), tintLadder(-7, 7))
    }

    @Test
    fun `变焦梯按范围过滤且保留半点档`() {
        assertEquals(
            listOf(0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f, 8f, 10f),
            zoomLadder(0.5f, 10f)
        )
        assertTrue(zoomLadder(0.5f, 10f).contains(0.5f))
        assertEquals(listOf(2f, 2.5f, 3f, 4f, 5f, 6f), zoomLadder(2f, 6f))
        assertTrue(zoomLadder(12f, 20f).isEmpty())
    }

    @Test
    fun `屈光度 lo到hi等分10段共11点`() {
        val d = diopterLadder(0f, 10f)
        assertEquals(11, d.size)
        assertEquals(0f, d.first())
        assertEquals(10f, d.last())
        assertEquals("等距 1.0/段", 1f, d[1], 1e-6f)
        assertTrue("定焦镜头（hi<=lo）没有梯", diopterLadder(0f, 0f).isEmpty())
        assertTrue(diopterLadder(0f, -1f).isEmpty())
        // 下限不为 0 的镜头：梯必须以运行时 range.start 起，不能造出域外刻度
        val offset = diopterLadder(2f, 12f)
        assertEquals(2f, offset.first(), 1e-6f)
        assertEquals(12f, offset.last(), 1e-6f)
    }

    @Test
    fun `百分比梯按步长生成`() {
        val zebra = percentLadder(80, 100, 1)
        assertEquals(21, zebra.size)
        assertEquals(80f, zebra.first())
        assertEquals(100f, zebra.last())
        assertEquals(19, percentLadder(5, 95, 5).size)
        assertEquals(listOf(5f, 10f, 15f), percentLadder(5, 15, 5))
        // 步长越界/区间倒置返回空
        assertTrue(percentLadder(5, 95, 0).isEmpty())
        assertTrue(percentLadder(10, 9, 1).isEmpty())
        // 起点不对齐网格时向上取第一档（与滑块产品档一致）
        assertEquals(listOf(10f, 15f), percentLadder(7, 15, 5))
    }

    @Test
    fun `快门梯过滤范围并去重升序`() {
        val tiers = listOf(41_666_666f, 20_000_000f, 33_333_333f, 20_000_000f, 10_000_000f)
        val ladder = shutterLadder(tiers, 15_000_000f, 41_666_664f)
        assertEquals(listOf(20_000_000f, 33_333_333f, 41_666_666f), ladder)
        assertFalse("范围外的档不进梯", ladder.contains(10_000_000f))
        assertTrue(shutterLadder(emptyList(), 0f, 1f).isEmpty())
    }
}
