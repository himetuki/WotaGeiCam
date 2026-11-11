package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 补充接线守卫；不是Compose触摸/重组的运行认证，实际界面仍需设备走查。 */
class UiStateCompatibilityWiringTest {

    @Test
    fun `对比长期手势调用最新启停回调而非捕获首次布尔值`() {
        val source = KotlinSourceScan.flatten(KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/CompareScreen.kt")))
        assertTrue("长期手势要经rememberUpdatedState读取本拍启停回调", source.contains("val onDoubleTap by rememberUpdatedState { setPlaying(!playing) }"))
        val pointer = source.substringAfter(".pointerInput(Unit)").substringBefore("Box(Modifier.weight(split)")
        assertTrue(pointer.contains("onDoubleTap()"))
        assertFalse(pointer.contains("setPlaying(!playing)"))
    }

    @Test
    fun `高速分析回执经过控制器状态和门面并显示降级提示`() {
        val controller = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("camera/CameraController.kt"))
        val bind = KotlinSourceScan.bodyOf(controller, "bind")
        assertTrue("真实引擎回执不能留在默认空lambda", bind.contains("onAnalysisSessionDecision ="))
        assertTrue("回执经可观察状态下发", bind.contains("analysisDecision ="))
        val screen = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(
            KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt")), "CameraScreen"
        ))
        assertTrue(screen.contains("stance.onAnalysisSessionDecision(ui.analysisDecision)"))
        assertTrue("降级提示不能只藏在未打开的弹层", screen.contains("LaunchedEffect(stanceState.fallbackTip)"))
        assertTrue(screen.contains("stanceState.fallbackTip?.let { showTip(it) }"))
    }

    @Test
    fun `白平衡菜单与提示观察同一renderMode`() {
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(
            KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraPills.kt")), "WbPill"
        ))
        assertTrue(body.contains("val renderMode by params.renderMode.observed()"))
        assertFalse("禁直接读取value让长期打开的面板错过渲染模式变化", body.contains("params.renderMode.value"))
        assertTrue(body.contains("renderMode == RenderMode.GPU"))
    }
}
