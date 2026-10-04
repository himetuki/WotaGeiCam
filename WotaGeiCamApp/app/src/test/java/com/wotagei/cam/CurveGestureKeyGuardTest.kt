package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「曲线画布手势层的重启 key 必须含通道」的源码级守卫（用户 2026-10-04 真机复现）。
 *
 * 缺陷史：59931ab 把画布手势层 `pointerInput(channel, gpuMode)` 的 key "优化"成只剩 gpuMode，
 * 理由是"换通道瞬间的一次按压会被重启吞掉"。但手势闭包捕获的 points/selected/commit 都绑定在
 * `remember(channel)` 的 state 实例上——channel 变了而监听不重启，拖动会写进旧通道的旧 state：
 * **总线被写（画面变色）而画布不动（显示绑的是新 state）**，正是用户报的「能调、画面也变、
 * 但 RGB 曲线没有变化」。JVM 摸不到、lint 不报、adb 验证时若不切通道也复现不了。
 *
 * 守卫就是一条：画布手势层的 key 必须带 channel。以后谁再想"优化"掉它，测试先红。
 */
class CurveGestureKeyGuardTest {

    private val masked: String by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/dialog/CurvePopup.kt"))
    }

    @Test
    fun `画布手势层的重启key必须含channel`() {
        // 定位画布手势层：matchParentSize 之后紧跟的 pointerInput（全文件只有画布这一处双参 key
        // 带 gpuMode；扇钮/捕获层/吞噬层分别是 fanTap/Unit/Unit）
        val at = masked.indexOf(".pointerInput(channel, gpuMode)")
        assertTrue(
            "画布手势层的 pointerInput(channel, gpuMode) 不见了：key 被去掉 channel 会让换通道后" +
                "的拖动写进旧通道的旧 state（画面变色、画布不动）——这是 2026-10-04 的真机缺陷，不许复发",
            at >= 0
        )
        // 反向锚点：不许存在"只有 gpuMode 的 pointerInput"（即旧缺陷形态）
        val loneGpu = masked.indexOf(".pointerInput(gpuMode)")
        assertTrue(
            "发现 pointerInput(gpuMode) 单参形态（第 $loneGpu 字符起）：这就是把 channel 从重启 key " +
                "里去掉的缺陷写法",
            loneGpu < 0
        )
    }
}
