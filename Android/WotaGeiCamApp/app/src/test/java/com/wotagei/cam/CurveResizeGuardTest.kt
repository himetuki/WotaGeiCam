package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「RGB 曲线弹窗右缘等比缩放」的源码级守卫（用户 2026-10-25 需求 + 29 §1.2 大小记忆修订）。
 *
 * 需求三条：① 右缘拖拽等比放大/缩小弹窗（曲线框与触点随 canvas 尺寸等比变大）；
 * ② 下限 = 默认最小尺寸（29 起：持久边长经 CurvePanelSize.clamp 钳到 [最小, 可用区]）；
 * ③ 扇面「重置」同时把尺寸复位到设计档 200 并落盘。
 *
 * 守卫锁四处接线：savedSide 的组合期钳、重置钮复位并落盘、side 不越可用区上限、
 * 节点/线宽随 scale 等比。谁把钳制或「重置复位」优化掉，测试先红。
 */
class CurveResizeGuardTest {

    private val masked: String by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/dialog/CurvePopup.kt"))
    }

    @Test
    fun `缩放下限是最小尺寸_经组合期钳制`() {
        // 29 §1.2 起持久的是边长 dp：钳制统一收在 CurvePanelSize.clamp（min = 最小尺寸，
        // max = 可用区），把手只写 savedSide 不再自带 coerceIn(1f, maxScale)
        val at = masked.indexOf("val side = com.wotagei.cam.core.CurvePanelSize")
        assertTrue("找不到组合期钳制（守卫失去输入）", at >= 0)
        val body = KotlinSourceScan.flatten(masked.substring(at).substringBefore(".dp"))
        assertTrue(
            "边长必须经 CurvePanelSize.clamp（下限=最小尺寸，上限=可用区）",
            body.contains("clamp(savedSide, maxSide.value, MinCurveSide.toFloat())")
        )
        // 把手拖拽 1:1 换算仍在（startSide + Δpx/density）
        assertTrue("把手必须保持 1:1 换算（startSide + Δpx/density）", masked.contains("startSide + (change.position.x - startX) / pxPerDp"))
    }

    @Test
    fun `扇面重置必须同时复位面板尺寸并落盘`() {
        val at = masked.indexOf("fun resetAll()")
        assertTrue("找不到 resetAll（守卫失去输入）", at >= 0)
        // onReset 是重置钮的接线处：必须同时做 resetAll 与边长复位 + 落盘
        val reset = masked.indexOf("onReset = {")
        assertTrue("找不到 onReset 接线（守卫失去输入）", reset >= 0)
        val block = masked.substring(reset).substringBefore("}")
        assertTrue(
            "重置钮必须把边长复位到设计档（29 §1.2：重置写回 200）",
            block.contains("savedSide = com.wotagei.cam.core.CurvePanelSize.DEFAULT_SIDE_DP")
        )
        assertTrue("复位必须落盘（冷启保持）", block.contains("setCurvePanelSideDp(prefs, savedSide)"))
        assertTrue("重置钮必须仍做四通道回恒等（resetAll）", block.contains("resetAll()"))
    }

    @Test
    fun `曲线框随持久边长缩放且不越可用区上限`() {
        val at = masked.indexOf("val baseSide = ")
        assertTrue("找不到 baseSide（默认最小值档，守卫失去输入）", at >= 0)
        val body = KotlinSourceScan.flatten(masked.substring(at).substringBefore("\n"))
        assertTrue(
            "side 必须由持久边长钳出（29 §1.2：存 dp，clamp 到 [最小, 可用区]）",
            masked.contains("val side = com.wotagei.cam.core.CurvePanelSize")
        )
        assertTrue(
            "缩放上限必须夹在可用区 maxSide（放大越界出屏）",
            body.contains("maxSide")
        )
    }

    @Test
    fun `节点与线宽必须随scale等比放大`() {
        val canvas = KotlinSourceScan.flatten(
            KotlinSourceScan.mainSourceText("ui/dialog/CurvePopup.kt")
                .substringAfter("private fun CurveCanvas(")
                .substringBefore("private companion")
        )
        assertTrue(
            "节点半径必须乘 scale（放大后点不放大，方便手指大的人调节就落空）",
            canvas.contains("6.5.dp.toPx() * scale") && canvas.contains("9.dp.toPx() * scale")
        )
        assertTrue(
            "曲线线宽必须乘 scale（等比呈现）",
            canvas.contains("Stroke(width = 2.dp.toPx() * scale)")
        )
    }
}
