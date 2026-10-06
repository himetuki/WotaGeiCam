package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录制编码输入面的**源码结构守卫**（2026-10-06 根因修复）：
 *
 * 真机缺陷：24※（arcConvert 非 null）恒走 [CodecRecorder]，其 GPU 路曾把
 * `MediaCodec.createPersistentInputSurface()` 的共享面交给 GL 当渲染目标——
 * `eglCreateWindowSurface` 在真机直接 EGL_BAD_ALLOC 0x3003（ArcRepair 时代实测过两轮的同款坑），
 * 编码器零输入 → 视频轨建不起来 → muxer 恒不 start → 停录报「录制失败」且无成片。
 * 普通帧率走 [MrRecorder]（MediaRecorder 自建的 per-codec 面 EGL 可挂），所以只在强制档炸。
 *
 * 这里钉三条红线，谁把共享面接回 GPU 路谁红：
 * - CodecRecorder GPU 路必须「configure 传 null + per-codec `createInputSurface()`」，
 *   persistent surface 只许留在 DIRECT（Camera2 当 producer）支路；
 * - 分段轮转换 per-codec 面必须同步重挂（onInputSurfaceRecreated）+ 释放旧面；
 * - UI 接线必须做「下发 GL + 等挂好」。
 */
class RecorderGpuSurfaceGuardTest {

    private fun maskedMain(rel: String): String =
        codeOnly(mainSourceText(rel))

    @Test
    fun `CodecRecorder 的 GPU 路必须用 per-codec 输入面`() {
        val src = maskedMain("record/CodecRecorder.kt")
        val prepare = bodyOf(src, "prepare")
        val gpu = bodyOf(src, "prepareGpuVideoEncoder")

        // prepare 按 useGpu 分叉：GPU 支路走专用建立函数
        assertTrue(
            "prepare 必须按 p.useGpu 分叉选择输入面建立方式",
            prepare.contains("if (p.useGpu)") && prepare.contains("prepareGpuVideoEncoder(mime, p)")
        )
        // GPU 建立：configure 传 null（只建不绑）+ per-codec createInputSurface
        assertTrue(
            "prepareGpuVideoEncoder 必须 configure(null) 后取 per-codec createInputSurface()",
            gpu.contains("createVideoEncoder(mime, p, null)") && gpu.contains("v.createInputSurface()")
        )
        // GPU 支路不得出现 persistent surface 的建立（它只许在 DIRECT 支路/ release 清理里）
        assertFalse(
            "GPU 输入面建立函数里不得触碰 createPersistentInputSurface（EGL_BAD_ALLOC 实测坑）",
            gpu.contains("createPersistentInputSurface")
        )
        assertTrue(
            "DIRECT 支路必须保留 persistent surface 复用（Camera2 当 producer 的既有口径）",
            prepare.contains("MediaCodec.createPersistentInputSurface()")
        )
    }

    @Test
    fun `分段轮转换面必须同步重挂并释放旧面`() {
        val src = maskedMain("record/CodecRecorder.kt")
        val next = bodyOf(src, "openNextSegment")

        assertTrue(
            "换段 GPU 支路必须重建 per-codec 面（configure(null) + createInputSurface）",
            next.contains("createVideoEncoder(resolveVideoMime(p), p, null)") &&
                next.contains("v.createInputSurface()")
        )
        assertTrue(
            "换段必须同步回调 onInputSurfaceRecreated（GL 挂好前新段不 start）",
            next.contains("onInputSurfaceRecreated?.invoke(newSurf)")
        )
        // 回调跑在泵线程 try/catch 之外：接线方抛异常必须就地折断（engineError + 关 t），
        // 不许烧 fd / 留幽灵 pending（P3-2）。字面量内容被遮蔽，锚点全取代码侧：
        // rebindOk 的折断分支 + ENGINE_ERROR 插值 + 就地关 t
        assertTrue(
            "重挂回调必须包 runCatching，失败置 REBIND 错误码并就地关 t",
            next.contains("val rebindOk = runCatching { onInputSurfaceRecreated?.invoke(newSurf) }") &&
                next.contains("if (!rebindOk)") &&
                next.contains("RecordError.ENGINE_ERROR") &&
                next.contains("t.close()")
        )
        assertTrue(
            "换段必须释放旧输入面（GL 解绑之后），否则 native Surface 泄漏",
            next.contains("old?.release()")
        )
    }

    @Test
    fun `Recorder 接口的重挂钩默认必须是无操作`() {
        val src = maskedMain("record/Recorder.kt")
        // MrRecorder / DIRECT 路面跨段不变：默认 getter 返回 null、setter 吞掉，
        // 接线挂在它们身上必须无害——这条保证 UI 侧可以无差别接线
        assertTrue(
            "onInputSurfaceRecreated 必须声明在 Recorder 接口且默认 getter 返回 null",
            src.contains("var onInputSurfaceRecreated: ((Any) -> Unit)?") &&
                src.contains("get() = null") &&
                src.contains("set(_) {}")
        )
    }

    @Test
    fun `UI 必须接线换段重挂并等 GL 挂好`() {
        val screen = maskedMain("ui/CameraScreen.kt")
        // 会话体自 2026-10-06 起抽成 beginSession（起录与自检重录共用同一套接线），
        // start 只负责可重入守卫 + 参数整束后转交；两处都要锁，抽取不许把接线丢掉
        val start = bodyOf(screen, "start")
        val begin = bodyOf(screen, "beginSession")
        assertTrue(
            "RecordRunner.start 必须转交 beginSession（起录与重录共用同一套会话接线）",
            start.contains("beginSession()")
        )
        assertTrue(
            "RecordRunner.beginSession 必须接 onInputSurfaceRecreated（下发 setOutputSurface + awaitEncoderSurface）",
            begin.contains("rec.onInputSurfaceRecreated = ") &&
                begin.contains("setOutputSurface(s, profile.width, profile.height, profile.fps)") &&
                begin.contains("awaitEncoderSurface()")
        )
        // 等面超时不许静默放行（P3-3，与 awaitPreview 口径对齐）；字面量被遮蔽，
        // 锚定「未挂载即 if + Log.w(TAG_UI…)」的代码形态
        val await = bodyOf(screen, "awaitEncoderSurface")
        assertTrue(
            "awaitEncoderSurface 超时必须留 Log.w（对齐 awaitPreview 口径）",
            await.contains("if (!arrived())") &&
                await.contains("Log.w(TAG_UI")
        )
        // 2026-10-07 换段窗口修复：轮转时旧绑定还在，isOutputSurfaceBound 首查恒真 =
        // 零等待 + 旧面未解绑先 release。重挂路径必须等「传入的新面」，首段路径保持旧谓词
        assertTrue(
            "awaitEncoderSurface 重挂路径必须等传入的新面（isEncoderSurfaceNative）；" +
                "改回 isOutputSurfaceBound = 换段等待名存实亡",
            await.contains("gl.isEncoderSurfaceNative(expected)")
        )
        assertTrue(
            "awaitEncoderSurface 首段路径必须保持既有谓词（无旧绑定，任意面已绑定语义正确）",
            await.contains("gl.isOutputSurfaceBound()")
        )
    }

    @Test
    fun `CodecRecorder 的自检三格必须在泵循环里落地`() {
        val src = maskedMain("record/CodecRecorder.kt")
        val seg = bodyOf(src, "runSegment")
        // 段起始清三格：换段后新编码器/muxer 从零起算，上一段真值不能冒充本段健康
        assertTrue(
            "runSegment 段起始必须清自检三格（videoTrackAdded/muxStarted/firstVideoSample）",
            seg.contains("healthVideoTrackAdded = false") &&
                seg.contains("healthMuxStarted = false") &&
                seg.contains("healthFirstVideoSample = false")
        )
        // 段起始同处复位本段写入字节账（segWrittenBytes）：漏了它，换段后 health() 会拿上一段的
        // 累计字节冒充本段"在写"，产出活性判据被喂绿。删掉这行必须红。
        assertTrue(
            "runSegment 段起始必须把 segWrittenBytes 归零（与三格同处复位）",
            seg.contains("segWrittenBytes = 0L")
        )
        // 建轨（FORMAT_CHANGED 后）与首样本（writeSample > 0 后）两个写点必须在 runSegment 内
        assertTrue(
            "runSegment 必须在 addTrack 之后置 healthVideoTrackAdded",
            seg.contains("videoTrack = mx.addTrack(v.getOutputFormat())") &&
                seg.contains("healthVideoTrackAdded = true")
        )
        assertTrue(
            "runSegment 必须在 writeSample 返回 >0 后置 healthFirstVideoSample",
            seg.contains("if (wrote > 0)") && seg.contains("healthFirstVideoSample = true")
        )
        // 第二格在启动闸内：mx.start() 成功后置位（谁把写点挪出启动闸，muxer 没真起也报健康）
        val mux = bodyOf(src, "startMuxerIfReady")
        assertTrue(
            "startMuxerIfReady 必须在 mx.start() 成功后置 healthMuxStarted",
            mux.contains("mx.start()") && mux.contains("healthMuxStarted = true")
        )
    }

    @Test
    fun `ArcKeepRule 在 30fps 源到 24fps 的抽帧节奏正确`() {
        // 根因排查时的逐帧验算固化：slotNs = 1e9/24，源帧距 = 1e9/30。
        // 首帧锚定，此后 ts >= nextDue 才保留——稳态每 5 帧丢 1 帧（保留率 0.8 = 24/30），
        // 若判定逻辑退化成恒 false（如 PTS 单位/域错配），30 帧必须全丢、守卫当场红
        val rule = ArcKeepRule(slotNs = 1_000_000_000L / 24)
        val frameStep = 1_000_000_000L / 30
        var t = 846_010_000_000_000L // 任意纳秒纪元锚（SurfaceTexture 同域量级）
        var kept = 0
        var dropped = 0
        repeat(60) {
            if (rule.onFrame(t)) {
                kept++
                rule.takeDrops() // 保留帧取走区间账，模拟 Emit 记账口径
            } else {
                dropped++
            }
            t += frameStep
        }
        // 60 帧 @30fps = 2.0s，24fps 应到 48 帧（允许边界 ±2：首帧锚点与槽对齐的取整抖动）
        assertTrue("60 帧源应保留 46..50 帧（实测 $kept）", kept in 46..50)
        assertTrue("60 帧源应丢弃 10..14 帧（实测 $dropped）", dropped in 10..14)
        assertEquals("保留+丢弃必须收完全部源帧", 60, kept + dropped)
    }

    @Test
    fun `ArcKeepRule 首帧必保留且停滞不连发`() {
        val rule = ArcKeepRule(slotNs = 41_666_666L)
        // 首帧：NOT_STARTED 锚定，必须保留（DROP/MEND 两模式的首帧都要能落到编码器）
        assertTrue("首帧必须保留", rule.onFrame(100_000_000_000L))
        // 停滞 1s 后来一帧：不得从落后账里连发（应到时刻被追平到当前帧之后）
        assertTrue("停滞后来帧应保留", rule.onFrame(101_000_000_000L))
        // 同一时刻戳的重复分类（stateOnly 重画等脏路径）必须判丢：时间没推进不应再保留
        assertFalse("时间未推进的重复帧必须判丢", rule.onFrame(101_000_000_000L))
        // slotNs=0 的直通语义：规则关闭时恒保留
        assertTrue("slotNs=0 恒保留", ArcKeepRule(0L).onFrame(0L))
    }
}
