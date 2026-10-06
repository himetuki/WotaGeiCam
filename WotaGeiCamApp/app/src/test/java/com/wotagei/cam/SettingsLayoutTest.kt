package com.wotagei.cam

import com.wotagei.cam.ui.SettingsBlock
import com.wotagei.cam.ui.blocksOf
import com.wotagei.cam.ui.columnOfBlock
import com.wotagei.cam.ui.layoutPlanOf
import com.wotagei.cam.ui.settingsColumnCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页分区真源（`ui/SettingsLayout.kt`）的 JVM 单测。
 *
 * 期望值**全部手写字面量**（不调被测实现反推）：这样把枚举声明顺序、左右栏归属、宽度门限
 * 任何一处改坏，这里都会红。桥函数 [layoutPlanOf] 另配「并集 / 无重复 / 相对顺序」三条真断言——
 * 由定义直接推出的恒等式测不出实现退化，必须拿"计划 → 行为"的桥来判。
 */
class SettingsLayoutTest {

    /** 改造前单列自上而下的视觉顺序（手写，作为独立真源） */
    private val expectedOrder = listOf(
        SettingsBlock.DEFAULT,
        SettingsBlock.ORIENTATION,
        SettingsBlock.MOTION,
        SettingsBlock.HUD,
        SettingsBlock.STORAGE,
        SettingsBlock.REFLINE,
        SettingsBlock.LEVEL,
        SettingsBlock.COMPARE,
        SettingsBlock.TEXT,
        SettingsBlock.PERM,
        SettingsBlock.ABOUT,
    )

    @Test
    fun `blocksOf恰好11项且顺序与改造前单列视觉顺序一致`() {
        assertEquals(expectedOrder, blocksOf())
        assertEquals(11, blocksOf().size)
    }

    @Test
    fun `columnOfBlock逐项点名`() {
        assertEquals(0, columnOfBlock(SettingsBlock.DEFAULT))
        assertEquals(0, columnOfBlock(SettingsBlock.ORIENTATION))
        assertEquals(0, columnOfBlock(SettingsBlock.MOTION))
        assertEquals(0, columnOfBlock(SettingsBlock.HUD))
        assertEquals(0, columnOfBlock(SettingsBlock.STORAGE))
        assertEquals(1, columnOfBlock(SettingsBlock.REFLINE))
        assertEquals(1, columnOfBlock(SettingsBlock.LEVEL))
        assertEquals(1, columnOfBlock(SettingsBlock.COMPARE))
        assertEquals(1, columnOfBlock(SettingsBlock.TEXT))
        assertEquals(1, columnOfBlock(SettingsBlock.PERM))
        assertEquals(1, columnOfBlock(SettingsBlock.ABOUT))
    }

    @Test
    fun `settingsColumnCount在门限两侧与两个真机宽度上取值`() {
        // 门限 600：599 单栏、600 两栏
        assertEquals(1, settingsColumnCount(599))
        assertEquals(2, settingsColumnCount(600))
        // 本机真实可用宽度（竖屏 360dp / 横屏 766dp）
        assertEquals(1, settingsColumnCount(360))
        assertEquals(2, settingsColumnCount(766))
    }

    @Test
    fun `单栏计划left为全表且right为空`() {
        val plan = layoutPlanOf(360)
        assertEquals(1, plan.columns)
        assertEquals(expectedOrder, plan.left)
        assertTrue("单栏时右栏必须为空", plan.right.isEmpty())
    }

    @Test
    fun `两栏计划并集等于全部区块且无重复`() {
        val plan = layoutPlanOf(766)
        assertEquals(2, plan.columns)
        val union = plan.left + plan.right
        // 无重复：并集条数必须正好是全部区块数（重一项会变 12）
        assertEquals(11, union.size)
        // 并集真断言：不只大小相同，元素集合也必须等于全表（漏一项会变 10 且集合不等）
        assertEquals(expectedOrder.toSet(), union.toSet())
    }

    @Test
    fun `两栏各自保持blocksOf的相对顺序`() {
        val plan = layoutPlanOf(766)
        // 各栏取子序列后必须与全表顺序一致（顺序错则 filter 出来的子序列与当前栏不等）
        assertEquals(expectedOrder.filter { it in plan.left.toSet() }, plan.left)
        assertEquals(expectedOrder.filter { it in plan.right.toSet() }, plan.right)
    }

    @Test
    fun `两栏分栏内容点名`() {
        val plan = layoutPlanOf(766)
        assertEquals(
            listOf(
                SettingsBlock.DEFAULT,
                SettingsBlock.ORIENTATION,
                SettingsBlock.MOTION,
                SettingsBlock.HUD,
                SettingsBlock.STORAGE,
            ),
            plan.left,
        )
        assertEquals(
            listOf(
                SettingsBlock.REFLINE,
                SettingsBlock.LEVEL,
                SettingsBlock.COMPARE,
                SettingsBlock.TEXT,
                SettingsBlock.PERM,
                SettingsBlock.ABOUT,
            ),
            plan.right,
        )
    }
}
