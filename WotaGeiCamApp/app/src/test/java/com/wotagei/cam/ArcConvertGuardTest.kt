package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录制期转换链的**源码结构守卫**（v2 抽帧+补弧，2026-10-03 定版）。
 *
 * 行为有 `ArcRecordConvertTest`/`ArcRepairPlanTest` 钉着，这里钉**结构红线**——
 * 这些线一旦被"顺手重构"破坏，行为测试未必红（架构烂掉不改变当次输出）：
 * - 取大画笔（`Shaders.ARC_MERGE_FS`）全工程**唯一**，离线引擎与录制引擎**共用**同一份；
 *   引擎侧（GlRenderEngine）不许直接摸画笔，必须经 ArcMendPass——两处各内联一份 max 迟早各改各的；
 * - v1 插帧词汇（`blendWeight`/`FrameSource`/`ARC_REPAIR_FS`）**不得复活**：插帧是被用户
 *   点名的方向性错误（抽帧再插帧帧数绕回原点、0.5 混合即"来回闪动"），谁加回来谁红。
 */
class ArcConvertGuardTest {

    private fun maskedMain(rel: String): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel))

    @Test
    fun `取大画笔全工程唯一且双引擎共用`() {
        // 定义恰一次（KDoc 已被遮蔽，计入的都是代码引用）
        val shaders = maskedMain("camera/Shaders.kt")
        assertEquals(
            "ARC_MERGE_FS 必须只在 Shaders.kt 定义一次",
            1,
            KotlinSourceScan.occurrences(shaders, "ARC_MERGE_FS").size
        )
        // 离线引擎与录制引擎都走这一支，不许各内联一份 max
        assertTrue(
            "离线引擎 ArcRepairGl 应引用 ARC_MERGE_FS",
            maskedMain("record/ArcRepairGl.kt").contains("ARC_MERGE_FS")
        )
        assertTrue(
            "录制引擎补弧通道 ArcMendPass 应引用 ARC_MERGE_FS",
            maskedMain("camera/ArcMendPass.kt").contains("ARC_MERGE_FS")
        )
        // GlRenderEngine 自己不许摸画笔：它的补弧绘制只许经 ArcMendPass 走
        assertFalse(
            "GlRenderEngine 不许直接引用 ARC_MERGE_FS（取大绘制只许在 ArcMendPass 里）",
            maskedMain("camera/GlRenderEngine.kt").contains("ARC_MERGE_FS")
        )
    }

    @Test
    fun `插帧词汇不得复活`() {
        val rels = listOf(
            "record/ArcRepairPlan.kt",
            "record/ArcRepairRunner.kt",
            "record/ArcRepairGl.kt",
            "camera/Shaders.kt",
            "camera/GlRenderEngine.kt",
            "camera/ArcMendPass.kt",
            "ui/CameraScreen.kt",
            "ui/dialog/CurvePopup.kt"
        )
        rels.forEach { rel ->
            val text = maskedMain(rel)
            listOf("blendWeight", "FrameSource", "ARC_REPAIR_FS").forEach { word ->
                assertEquals(
                    "$rel 出现插帧词汇 $word（v2 已废弃插帧：抽帧再插帧帧数绕回原点，0.5 混合即来回闪动）",
                    0,
                    KotlinSourceScan.occurrences(text, word).size
                )
            }
        }
    }
}
