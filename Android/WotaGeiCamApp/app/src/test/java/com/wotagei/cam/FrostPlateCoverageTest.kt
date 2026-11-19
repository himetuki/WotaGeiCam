package com.wotagei.cam

import com.wotagei.cam.camera.FrostCardTable
import com.wotagei.cam.camera.FrostCardTable.SLOT_CAPACITY
import com.wotagei.cam.camera.FrostCardTable.TABLE_FLOATS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 逐槽覆盖位的**行为**测试（Y14，2026-11-19 评审）——与源码级的
 * [FrostPlateCoverageGuardTest] 互补，这里钉的是数据本身：
 *
 * - 压实（[FrostCardTable.tryReadInto]）会丢掉槽位身份，`slotsOut` 必须把**原始槽位号**带回，
 *   否则 GL 会把"第 0 块画了"记到槽 0 头上，而第 0 块其实来自槽 5；
 * - 覆盖位必须逐槽可问：完全落在信箱黑边上的板一块没画，它的本地纯色底必须留着；
 * - 整页 `platesDrawn` 只由掩码派生；撤报（`reportPlatesDrawn(false)` / 掩码 0）必须连逐槽位一起清，
 *   否则"板没了但某槽还说覆盖"会让那枚卡永久不画底；
 * - 非法/负数掩码不许被 `and 0xFF` 变成"全槽点亮"。
 *
 * 这张表是进程级 `object`，所以每条用例前后都 [FrostCardTable.resetForTests]（与
 * [FrostCardTableTest] 同一条纪律）。
 */
class FrostPlateCoverageTest {

    private val out = FloatArray(TABLE_FLOATS)
    private val slots = IntArray(SLOT_CAPACITY)

    @Before
    fun setUp() {
        FrostCardTable.resetForTests()
        out.fill(Float.NaN)
        slots.fill(-1)
    }

    @After
    fun tearDown() {
        FrostCardTable.resetForTests()
    }

    private fun card(slot: Int, left: Float = 10f): Boolean =
        FrostCardTable.writeCard(slot, left, 10f, left + 20f, 40f, radiusPx = -1f, alpha = 1f)

    @Test
    fun `压实之后原始槽位号必须可回读`() {
        val a = FrostCardTable.acquireSlot()
        val b = FrostCardTable.acquireSlot()
        val c = FrostCardTable.acquireSlot()
        assertTrue("三枚槽位都必须领到", a >= 0 && b >= 0 && c >= 0)
        assertTrue(card(a, 10f))
        assertTrue(card(b, 40f))
        assertTrue(card(c, 70f))
        // 释放中间那枚：压实后只剩两块，且第二块的原始槽号是 c 而不是 1
        FrostCardTable.releaseSlot(b)
        assertEquals(2, FrostCardTable.tryReadInto(out, slots))
        assertEquals("压实后第 0 块必须回报原始槽 a", a, slots[0])
        assertEquals("压实后第 1 块必须回报原始槽 c（被释放的 b 不许占位）", c, slots[1])
        // 表体本身仍按压实顺序排：第 1 块的左缘必须是 c 写进去的 70f
        val secondBase = FrostCardTable.HEADER_FLOATS + FrostCardTable.SLOT_FLOATS
        assertEquals(70f, out[secondBase + FrostCardTable.CARD_LEFT], 0f)
    }

    @Test
    fun `覆盖位逐槽且整页live由它派生`() {
        val a = FrostCardTable.acquireSlot()
        val b = FrostCardTable.acquireSlot()
        assertTrue(card(a))
        assertTrue(card(b))
        val both = (1 shl a) or (1 shl b)
        FrostCardTable.reportPlatesCovered(both)
        assertTrue("槽 a 有板", FrostCardTable.isPlateCovered(a))
        assertTrue("槽 b 有板", FrostCardTable.isPlateCovered(b))
        assertTrue("任一位 ⇒ 整页 live", FrostCardTable.isPlatesDrawn())
        assertEquals(both, FrostCardTable.coveredSlotMask())

        // 只报一块：另一块必须立刻失去让位资格（UI 要把它的纯色底画回来）
        FrostCardTable.reportPlatesCovered(1 shl b)
        assertFalse("没被回报的槽不许沾光", FrostCardTable.isPlateCovered(a))
        assertTrue(FrostCardTable.isPlateCovered(b))
        assertTrue("还剩一块 ⇒ 整页仍是 live", FrostCardTable.isPlatesDrawn())

        FrostCardTable.reportPlatesCovered(0)
        assertFalse(FrostCardTable.isPlateCovered(a))
        assertFalse(FrostCardTable.isPlateCovered(b))
        assertFalse("一块都没有 ⇒ 整页撤报", FrostCardTable.isPlatesDrawn())
        assertEquals(0, FrostCardTable.coveredSlotMask())
    }

    @Test
    fun `整页撤报必须连逐槽位一起清`() {
        val a = FrostCardTable.acquireSlot()
        assertTrue(card(a))
        FrostCardTable.reportPlatesCovered(1 shl a)
        assertTrue(FrostCardTable.isPlateCovered(a))
        FrostCardTable.reportPlatesDrawn(false)
        assertEquals("撤报之后一个 bit 都不许留", 0, FrostCardTable.coveredSlotMask())
        assertFalse(FrostCardTable.isPlateCovered(a))
        assertFalse(FrostCardTable.isPlatesDrawn())
    }

    @Test
    fun `负数或越界掩码不许点亮所有槽`() {
        // -1 and SLOT_MASK 会把 8 个槽全点亮；那是最坏的一种假 live（所有 fill 一起让位）
        FrostCardTable.reportPlatesCovered(-1)
        assertEquals(0, FrostCardTable.coveredSlotMask())
        assertFalse(FrostCardTable.isPlatesDrawn())
        // 超出容量的高位 bit 必须被截掉；槽位查询越界恒 false
        FrostCardTable.reportPlatesCovered(1 shl (SLOT_CAPACITY + 2))
        assertEquals(0, FrostCardTable.coveredSlotMask())
        assertFalse(FrostCardTable.isPlateCovered(-1))
        assertFalse(FrostCardTable.isPlateCovered(SLOT_CAPACITY))
    }

    @Test
    fun `slotsOut太短时只写放得下的前几项且不越界`() {
        val a = FrostCardTable.acquireSlot()
        val b = FrostCardTable.acquireSlot()
        assertTrue(card(a))
        assertTrue(card(b))
        val short = IntArray(1) { -1 }
        assertEquals(2, FrostCardTable.tryReadInto(out, short))
        assertEquals("第一项照常写入", a, short[0])
    }
}
