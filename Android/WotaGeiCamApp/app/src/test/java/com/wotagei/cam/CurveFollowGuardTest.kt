package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「曲线拖动帧必须直贴、morph 补间只留给换通道/重置」的源码级守卫（用户 2026-10-25 报
 * 「曲线变化与节点呈现不跟手」）。
 *
 * 缺陷史：CurveCanvas 的 morph 补间用「单帧最大偏移 >0.08f」判跳变——快拖的单帧差轻松超过
 * 0.08，被判成跳变后 `morph.snapTo(0f)` + `animateTo`，而拖动每帧都在产生新 target、
 * LaunchedEffect 每帧重启、动画每帧归零：**曲线被钉在起跳位置追不上手指**，节点画的是
 * 本地 points 即时跟手——节点与曲线肉眼分离。修复 = 画布手势进行中（canvasGesture=true）
 * 传 animateJump=false，直贴分支无条件 snap；morph 只服务换通道/重置的整条跳变。
 *
 * 守卫锁三处接线 + 一处突变自证，谁把「拖动帧直贴」优化掉，测试先红。
 */
class CurveFollowGuardTest {

    private val masked: String by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/dialog/CurvePopup.kt"))
    }

    @Test
    fun `画布手势进行中必须置canvasGesture且回落`() {
        // 定位画布手势层（与 CurveGestureKeyGuardTest 同一锚点：全文件唯一带 gpuMode 的多参 key，
        // 29 §3.2-1 起为三参 channel/gpuMode/panelScale）
        val at = masked.indexOf(".pointerInput(channel, gpuMode, panelScale)")
        assertTrue("画布手势层不见了（锚点失效，守卫失去输入）", at >= 0)
        val body = masked.substring(at)
        assertTrue(
            "画布手势认领控制点后必须置 canvasGesture = true（拖动帧直贴的开关）",
            body.contains("canvasGesture = true")
        )
        assertTrue(
            "画布手势结束必须置 canvasGesture = false（否则换通道/重置的 morph 永远不回来）",
            body.contains("canvasGesture = false")
        )
    }

    @Test
    fun `CurveCanvas必须接animateJump且调用点传非拖动态`() {
        assertTrue(
            "CurveCanvas 缺 animateJump 参数：拖动帧没有直贴开关",
            masked.contains("animateJump: Boolean")
        )
        assertTrue(
            "CurveCanvas 调用点必须传 animateJump = !canvasGesture（手势中禁补间）",
            masked.contains("animateJump = !canvasGesture")
        )
    }

    @Test
    fun `非动画态必须先于跳变判据直贴`() {
        // 直贴分支必须排在 0.08 跳变判据之前：判据先行的话快拖帧先进 morph，等于没修
        val effectAt = masked.indexOf("LaunchedEffect(target)")
        assertTrue("找不到 LaunchedEffect(target)（morph 效果被挪走，守卫失去输入）", effectAt >= 0)
        val effect = KotlinSourceScan.flatten(masked.substring(effectAt).substringBefore("\n    }"))
        val snap = effect.indexOf("if (!animateJump)")
        val jump = effect.indexOf("0.08f")
        assertTrue("直贴分支（if (!animateJump)）不见了：快拖帧会再进 morph", snap >= 0)
        assertTrue(
            "直贴分支必须先于 0.08f 跳变判据（判据先行 = 快拖帧先被判成跳变进 morph）",
            snap in 0 until jump
        )
    }

    @Test
    fun `突变自证：摘掉直贴分支后守卫必须红`() {
        val mutant = masked.replace("if (!animateJump) {", "if (false) {")
        assertFalse("对照突变体必须真的改动", mutant == masked)
        val effectAt = mutant.indexOf("LaunchedEffect(target)")
        val effect = KotlinSourceScan.flatten(mutant.substring(effectAt).substringBefore("\n    }"))
        val snap = effect.indexOf("if (!animateJump)")
        val jump = effect.indexOf("0.08f")
        assertFalse("突变体上原判据必须红（否则证明不了什么）", snap in 0 until jump)
    }
}
