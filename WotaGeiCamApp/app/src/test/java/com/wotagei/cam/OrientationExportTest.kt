package com.wotagei.cam

import com.wotagei.cam.record.bakedRotationKeysToDrop
import com.wotagei.cam.record.bakedRotationNeedsStrip
import com.wotagei.cam.record.displayRotationOf
import com.wotagei.cam.record.exportOrientationHint
import com.wotagei.cam.record.matchDecision
import com.wotagei.cam.record.normalizeQuarter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方向导出的纯函数回归（30fps→24fps 光弧修复成片"180° 旋转"根因：旋转角按相反旋向读取）。
 * 期望值一律手写字面量，不调被测实现反推。
 */
class OrientationExportTest {

    @Test
    fun `exportOrientationHint 四档两约定全表`() {
        // muxerCcw = false：原样透传（本批口径，显示旋转角与容器值同约定）
        assertEquals(0, exportOrientationHint(0, false))
        assertEquals(90, exportOrientationHint(90, false))
        assertEquals(180, exportOrientationHint(180, false))
        assertEquals(270, exportOrientationHint(270, false))
        // muxerCcw = true：取补（θ 与 360−θ）
        assertEquals(0, exportOrientationHint(0, true))
        assertEquals(270, exportOrientationHint(90, true))
        assertEquals(180, exportOrientationHint(180, true))
        assertEquals(90, exportOrientationHint(270, true))
    }

    @Test
    fun `往返恒等`() {
        for (q in intArrayOf(0, 90, 180, 270)) {
            for (ccw in booleanArrayOf(false, true)) {
                assertEquals(q, displayRotationOf(exportOrientationHint(q, ccw), ccw))
            }
        }
    }

    @Test
    fun `反向约定下 90 与 270 互换恰差 180`() {
        val out = exportOrientationHint(90, true)
        assertEquals(270, out)
        assertEquals("反向约定把 90 转成 270，与 90 相差恰为 180", 180, (out - 90 + 360) % 360)
        // 原样透传约定下不产生这个差异（形态学对照：只有 90↔270 互换才看得出差 180）
        assertEquals(90, exportOrientationHint(90, false))
    }

    @Test
    fun `matchDecision 三情形`() {
        // retriever 可用 → 以它为准（即便与 extractor 冲突），这正是修 90↔270 互换的分支
        assertEquals(270, matchDecision(90, 270, retrieverAvailable = true))
        // retriever 不可用 → 降级到 extractor
        assertEquals(90, matchDecision(90, 270, retrieverAvailable = false))
        // 两源都无 → 0
        assertEquals(0, matchDecision(0, 0, retrieverAvailable = false))
        // 非整档也归一化到最近档
        assertEquals(90, matchDecision(0, 91, retrieverAvailable = true))
    }

    /**
     * `bakedRotationKeysToDrop` 是 `decoderFormatOf` 里"删哪些键"的唯一决策点。
     * 期望值手写字面量（键名写 "rotation-degrees"，不调 `MediaFormat.KEY_ROTATION` 反推）：
     * 只剔旋转键、其余（mime/width/csd-0 等）一个都不许动——多剔一个键解码器就可能建不出来。
     */
    @Test
    fun `bakedRotationKeysToDrop 只剔旋转键`() {
        assertEquals(
            listOf("rotation-degrees"),
            bakedRotationKeysToDrop(listOf("mime", "width", "rotation-degrees", "csd-0"))
        )
        assertEquals(
            listOf("rotation-degrees"),
            bakedRotationKeysToDrop(listOf("rotation-degrees"))
        )
        // 源里没有该键 → 空表（等价原样拷贝，无副作用）
        assertEquals(emptyList<String>(), bakedRotationKeysToDrop(listOf("mime", "width", "csd-0")))
        assertEquals(emptyList<String>(), bakedRotationKeysToDrop(emptyList()))
    }

    /**
     * `bakedRotationNeedsStrip` 是"计划→行为"桥：测的是**机制**（有输出面的解码器才把容器旋转烘进像素），
     * 不是"由定义直接推出的恒等式"。删掉 `surfaceOutput` 这一支或把非零判断写成恒真，本表都会红。
     */
    @Test
    fun `bakedRotationNeedsStrip 机制表`() {
        // 有输出面 + 非零旋转：平台把旋转烘进像素（2026-10-06 GPU 路成片差 180° 的这一格）
        assertTrue(bakedRotationNeedsStrip(180, surfaceOutput = true))
        assertTrue(bakedRotationNeedsStrip(-180, surfaceOutput = true))
        assertTrue(bakedRotationNeedsStrip(90, surfaceOutput = true))
        assertTrue(bakedRotationNeedsStrip(270, surfaceOutput = true))
        // 450 归一化到 90：仍非零
        assertTrue(bakedRotationNeedsStrip(450, surfaceOutput = true))
        // 有输出面 + 零旋转：无旋转可烘（剥不剥都不改变像素）
        assertFalse(bakedRotationNeedsStrip(0, surfaceOutput = true))
        assertFalse(bakedRotationNeedsStrip(360, surfaceOutput = true))
        // 无输出面（CPU/ByteBuffer 路）：旋转无处施加，本 bug 不在这一路（2026-10-05 真机对照）
        assertFalse(bakedRotationNeedsStrip(180, surfaceOutput = false))
        assertFalse(bakedRotationNeedsStrip(90, surfaceOutput = false))
        assertFalse(bakedRotationNeedsStrip(0, surfaceOutput = false))
    }

    @Test
    fun `normalizeQuarter 边界`() {
        assertEquals(270, normalizeQuarter(-90))
        assertEquals(90, normalizeQuarter(450))
        assertEquals(0, normalizeQuarter(361))
        assertEquals(0, normalizeQuarter(0))
        assertEquals(90, normalizeQuarter(45))   // 恰在两档中点，向上取档
        assertEquals(0, normalizeQuarter(359))   // 359 最近的档是 0(≡360)
        assertEquals(180, normalizeQuarter(-180))
    }
}
