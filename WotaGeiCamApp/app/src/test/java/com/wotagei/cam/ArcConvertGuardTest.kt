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

    @Test
    fun `录制侧转换接线在位`() {
        val screen = maskedMain("ui/CameraScreen.kt")
        assertTrue(
            "录制启动必须把转换模式写进编码面（setArcConvert 接线缺失 = 抽帧静默失效）",
            screen.contains("setArcConvert(")
        )
        assertTrue(
            "DIRECT 撞上转换必须自动切 GPU 并提示（cam_arc_switch_gpu 守卫缺失 = 出 30fps 假 24 文件）",
            screen.contains("cam_arc_switch_gpu")
        )
        assertTrue(
            "停止录制必须写被抽帧位次 sidecar（ArcDropLog.writeTo 缺失 = 位次记录需求失效）",
            screen.contains("ArcDropLog.writeTo(")
        )
        assertTrue(
            "停录前必须冲刷 MEND 滞留帧（flushArcPending 缺失 = 每段尾帧必丢）",
            screen.contains("flushArcPending(")
        )
        val recorder = maskedMain("record/Recorder.kt")
        assertTrue(
            "useCodecEngine 必须感知 arcConvert（MediaRecorder 无法拒帧，转换会话漏进 MR = 抽帧全废）",
            recorder.contains("arcConvert")
        )
    }

    @Test
    fun `档位接线与改名回查在位`() {
        // 判定必须锁函数体（bodyOf）：整文件 contains 会被字段声明行
        //（arcRule = ArcKeepRule(slotNs = 0L)）与辅助函数定义行自身满足——
        // 删掉修复行后测试照样绿，那正是守卫要杜绝的假绿
        val engine = maskedMain("camera/GlRenderEngine.kt")
        val setBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, "setArcConvert"))
        assertTrue(
            "setArcConvert 必须在体内重建 ArcKeepRule 接入档位（slotNs 只构造不赋值 = 恒 0 = " +
                "onFrame 恒真 = 抽帧整条失效而 PTS 照 dstFps 盖，成片时长被压缩）",
            setBody.contains("ArcKeepRule(slotNs = if")
        )
        assertTrue(
            "GL 实测源帧率必须四舍五入（截断把 29.97 打成 29，与修复路中位数四舍五入的前缀分叉）",
            engine.contains("+ spanNs / 2) / spanNs")
        )
        val screen = maskedMain("ui/CameraScreen.kt")
        assertFalse(
            "setArcConvert 不得按「本段有转换」条件下发（引擎侧模式账不随停录自清，" +
                "关掉转换后的录制会被上段残留模式暗改且无 sidecar 无前缀）",
            screen.contains("if (profile.arcConvert != null)")
        )
        val stopBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(screen, "stopInternal"))
        assertTrue(
            "改名后必须在收尾体内回查真实 DISPLAY_NAME（MediaStore 撞名自行加序号仍报成功，" +
                "拼装名与 sidecar 落点会分叉；只定义不调用等于没修）",
            stopBody.contains("queryDisplayName(uri)")
        )
    }
}
