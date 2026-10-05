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

    @Test
    fun `MEND 返回极性取反与 flush 折叠步在位`() {
        // 与上条同理锁函数体：整文件 contains 会被 drawEncoderMended 的声明/KDoc 自身满足，
        // 锁住 drawFrame 体内才测得出调用点的极性
        val engine = maskedMain("camera/GlRenderEngine.kt")
        val frameBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, "drawFrame"))
        assertTrue(
            "drawFrame 的 MEND 支路必须取反 drawEncoderMended 返回值（契约：true=已降级需直通补发、" +
                "false=已处理。直赋 skip 会把已处理帧当跳过再直通一遍——MEND 抽帧整条失效、成片时长" +
                "膨胀音画错位；把降级帧当跳过则丢掉直通补发）",
            frameBody.contains("skip = !drawEncoderMended(")
        )
        assertFalse(
            "drawFrame 不许把 drawEncoderMended 返回值直赋 skip（极性反 = 每源帧双编一帧，见上条）",
            frameBody.contains("skip = drawEncoderMended(")
        )
        assertTrue(
            "转换账（保留判定/位次/实测帧率）必须只在新相机帧上进（无新帧的状态重画拿旧时间戳" +
                "重复分类 = 幻记丢弃、源帧双计数、实测前缀偏高）",
            frameBody.contains("if (hasFrame)")
        )
        assertTrue(
            "转换模式下无新帧的重画必须跳过编码 pass（往均匀节奏塞无账重复帧 = 位次账错位）",
            frameBody.contains("skip = true")
        )
        val flushBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, "flushArcPending"))
        assertTrue(
            "flushArcPending 发 pending 前必须先折叠 acc（onSourceEos 契约 = fold?→emit；漏 fold " +
                "= 停录前最后积累的补弧光整段丢在 acc 面）",
            flushBody.contains("foldAccIntoPending()")
        )
    }

    @Test
    fun `DROP 位次账与帧号下界在位`() {
        // 记账配对在 GL 侧、JVM 不可测：锁三处记账点的函数体形态，删行/改形即红
        val engine = maskedMain("camera/GlRenderEngine.kt")
        val frameBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, "drawFrame"))
        assertTrue(
            "DROP 保留帧直通发出后必须落位次账（与 MEND emit 同一 (输出位次, 丢弃数) 口径；" +
                "漏记则 DROP 的 sidecar drops 恒空，被抽帧位次立档落空）",
            frameBody.contains(
                "arcDrops += (encoderFrameIndex - 1).coerceAtLeast(0) to arcRule.takeDrops()"
            )
        )
        assertTrue(
            "DROP 记账必须以编码面在位为前提（面已被摘时 drawEncoderPass 是空操作，" +
                "记账会写入从未发出的假位次）",
            frameBody.contains("encoderEglSurface != null")
        )
        val mendBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, "drawEncoderMended"))
        assertTrue(
            "MEND emit 位次账帧号必须夹下界（frameRate=0 直注时 stamp 不推进序号，裸 -1 出负位次）",
            mendBody.contains("coerceAtLeast(0)")
        )
        val flushBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, "flushArcPending"))
        assertTrue(
            "flush 位次账帧号必须夹下界（同上）",
            flushBody.contains("coerceAtLeast(0)")
        )
    }

    @Test
    fun `补弧降级两段阀在位`() {
        // 2026-10-04 裁决：补弧失败降「仅抽帧不补弧」，不再全速直通（直通当第一响应 =
        // 降级点后成片段落慢放、音画渐进漂移到秒级）。锁 degradeArcConvert 函数体：
        // 删掉两段链改回「arcConvert = null」旧形态必红。
        val engine = maskedMain("camera/GlRenderEngine.kt")
        val degradeBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, "degradeArcConvert"))
        assertTrue(
            "MEND 失败必须经 setArcConvert 降 DROP（仅抽帧不补弧；直改 arcConvert 字段 = 绕过 " +
                "slotNs 接线/状态机/位次账的按档重建，节奏锚点残留）",
            degradeBody.contains("setArcConvert(ArcConvertMode.DROP")
        )
        assertTrue(
            "阀必须保留 DROP→直通的二段保底分支（失败源于 makeCurrent/编码面这类公共部件时抽帧也救不了）",
            degradeBody.contains("ArcConvertMode.DROP ->")
        )
        assertFalse(
            "阀体内不许直接把 arcConvert 置 null（全速直通当第一响应 = 降级点后按 dstFps 均匀盖 " +
                "PTS 照跑，成片段落慢放、音画漂移）",
            degradeBody.contains("arcConvert = null")
        )
        // 二段触发点锁在 drawFrame 体内：没有它，DROP→直通分支是死代码
        val frameBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, "drawFrame"))
        assertTrue(
            "drawFrame 必须检测 DROP 直通编码面帧中掉链并触发二段降级（无触发点则保底分支永远走不到）",
            frameBody.contains("degradeArcConvert(")
        )
        assertTrue(
            "掉链判定必须以「帧前在位 → 帧后为 null」为据（只看当前 null 会把面本来就未挂的帧误判成失败）",
            frameBody.contains("encoderBound && encoderEglSurface == null")
        )
    }

    @Test
    fun `sidecar段清单注入在位`() {
        // 2026-10-04 裁决：sidecar 加 segments 段清单（{segment,first,count}），排查者凭
        // first/count 把每段对上跨段全局位次。全链四处各锁一段函数体，删任何一处即红：
        val recorder = maskedMain("record/CodecRecorder.kt")
        val segBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(recorder, "runSegment"))
        assertTrue(
            "视频写样本处必须计数（segVideoWritten 缺 = 段清单恒 0，first/count 对不上位次）",
            segBody.contains("if (wrote > 0) segVideoWritten++")
        )
        val pumpBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(recorder, "pumpLoop"))
        assertTrue(
            "段闭必须落账归零（不落账 = 账索引与 partIndex 错位，段清单整体偏移）",
            pumpBody.contains("segSampleCounts.add(segVideoWritten)") &&
                pumpBody.contains("segVideoWritten = 0")
        )
        val buildBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(recorder, "buildResult"))
        assertTrue(
            "stop 结果必须带逐段样本数（segVideoSamples 缺 = CameraScreen 拿不到账，sidecar 退回无 segments）",
            buildBody.contains("segVideoSamples = segSampleCounts.toList()")
        )
        val stopBody = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(maskedMain("ui/CameraScreen.kt"), "stopInternal"))
        assertTrue(
            "sidecar 写入必须注入段清单（segments 缺 = 排查者仍对不上段与位次）",
            stopBody.contains("ArcDropSegment(") && stopBody.contains("segments = segs")
        )
    }
}
