package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「RGB 曲线弹窗右缘等比缩放」的源码级守卫（用户 2026-10-25 需求）。
 *
 * 需求三条：① 右缘拖拽等比放大/缩小弹窗（曲线框与触点随 canvas 尺寸等比变大）；
 * ② 当前尺寸 = 默认最小值，**只许放大不许缩小**；③ 扇面「重置」同时把尺寸复位到默认最小。
 *
 * 守卫锁四处接线：缩放下限 coerceIn(1f、重置钮复位 panelScale、side 随 panelScale 缩放且
 * 不越可用区上限、节点/线宽随 scale 等比。谁把「只许放大」或「重置复位」优化掉，测试先红。
 */
class CurveResizeGuardTest {

    private val masked: String by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/dialog/CurvePopup.kt"))
    }

    @Test
    fun `缩放下限必须是1f即默认最小值`() {
        // 定位右缘把手的拖拽循环：coerceIn(1f, maxScale) 的 1f 就是「当前尺寸=最小值」的需求落点
        val at = masked.indexOf("val maxScale = if (baseSide > 0.dp)")
        assertTrue("找不到缩放把手（baseSide/maxSide 判据被挪走，守卫失去输入）", at >= 0)
        val body = KotlinSourceScan.flatten(masked.substring(at).substringBefore("change.consume()"))
        assertTrue(
            "缩放下限必须是 1f（coerceIn(1f, maxScale)）：低于默认最小值违背「当前尺寸=最小值」的需求",
            body.contains(".coerceIn(1f, maxScale)")
        )
    }

    @Test
    fun `扇面重置必须同时复位面板尺寸`() {
        val at = masked.indexOf("fun resetAll()")
        assertTrue("找不到 resetAll（守卫失去输入）", at >= 0)
        // onReset 是重置钮的接线处：必须同时做 resetAll 与 panelScale 复位
        val reset = masked.indexOf("onReset = {")
        assertTrue("找不到 onReset 接线（守卫失去输入）", reset >= 0)
        val block = masked.substring(reset).substringBefore("}")
        assertTrue(
            "重置钮必须复位 panelScale（用户需求③：重置也包括把弹窗大小复位到默认最小值）",
            block.contains("panelScale = 1f")
        )
        assertTrue("重置钮必须仍做四通道回恒等（resetAll）", block.contains("resetAll()"))
    }

    @Test
    fun `曲线框随panelScale缩放且不越可用区上限`() {
        val at = masked.indexOf("val baseSide = ")
        assertTrue("找不到 baseSide（默认最小值档，守卫失去输入）", at >= 0)
        val body = KotlinSourceScan.flatten(masked.substring(at).substringBefore("\n"))
        assertTrue(
            "side 必须随 panelScale 缩放（等比放大的落点）",
            masked.contains("val side = (baseSide * panelScale).coerceAtMost(maxSide)")
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
