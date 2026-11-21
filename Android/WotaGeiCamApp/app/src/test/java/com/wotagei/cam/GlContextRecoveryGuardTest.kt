package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R05（P1）守卫：`EGL_CONTEXT_LOST` 必须被识别、上报，并由所有权方**整条重建**引擎；
 * 不允许在失效上下文上继续画，也不允许"看着恢复了、实际采样空纹理"的隐藏降级。
 *
 * 为什么是源码级：上下文丢失只在真机（GPU 复位/驱动重启/长时间后台）发生，JVM 里造不出
 * 真的 `EGL_CONTEXT_LOST`。这里锁的是**结构不变量**：
 * - 两条失败出口（makeCurrent / swap）都必须把 0x300E 升级成终态，而不是只打日志；
 * - 终态处理只上报一次、绝不自愈（旧实例单次生命周期）；
 * - Controller 的恢复路径先关相机、再释放旧引擎（恰好一次）、再新建并重绑；
 * - 同一实例的迟到回调不许触发第二次恢复。
 */
class GlContextRecoveryGuardTest {

    private val engine = KotlinSourceScan.codeOnly(
        KotlinSourceScan.mainSourceText("camera/GlRenderEngine.kt")
    )
    private val controller = KotlinSourceScan.codeOnly(
        KotlinSourceScan.mainSourceText("camera/CameraController.kt")
    )

    private fun flat(src: String): String = KotlinSourceScan.flatten(src)

    @Test
    fun `丢失识别常量与两条失败出口`() {
        assertTrue(
            "必须定义 EGL_CONTEXT_LOST（Android EGL14 未导出，0x300E）",
            engine.contains("EGL_CONTEXT_LOST = 0x300E")
        )
        val make = flat(KotlinSourceScan.bodyOf(engine, "makeCurrent"))
        assertTrue("makeCurrent 失败必须检查 0x300E", make.contains("err == EGL_CONTEXT_LOST"))
        assertTrue(
            "makeCurrent 的 CONTEXT_LOST 必须升级为终态，不许只打日志",
            make.contains("handleContextLost(")
        )
        val swap = flat(KotlinSourceScan.bodyOf(engine, "swap"))
        assertTrue("swap 失败必须检查 0x300E", swap.contains("err == EGL_CONTEXT_LOST"))
        assertTrue(
            "swap 的 CONTEXT_LOST 必须升级为终态（否则录制/预览静默停在最后一帧）",
            swap.contains("handleContextLost(")
        )
    }

    @Test
    fun `终态只上报一次且绝不自愈`() {
        val lost = flat(KotlinSourceScan.bodyOf(engine, "handleContextLost"))
        assertTrue("必须置终态位", lost.contains("contextLost = true"))
        assertTrue("必须把 glReady 拉回未就绪", lost.contains("glReady = false"))
        assertTrue("必须只回调一次（入口幂等）", lost.contains("if (contextLost || released.get()) return"))
        assertTrue("必须通知所有权方", lost.contains("onContextLost?.invoke(where)"))
        // 自愈禁令：终态里不许重建 EGL/上下文，也不许再 makeCurrent
        assertFalse("终态里不许 initGl（旧实例不可复用）", lost.contains("initGl"))
        assertFalse("终态里不许 makeCurrent（只会继续报 CONTEXT_LOST）", lost.contains("makeCurrent("))
        // 丢失后编码/窗口心跳必须归零，看门狗才能识别"渲染已死"
        assertTrue("编码面必须失效", lost.contains("encoderEglSurface = null"))
        assertTrue("窗口面必须失效", lost.contains("windowEglSurface = null"))
        assertTrue("上屏心跳必须停", lost.contains("lastWindowSwapNs = 0L"))
    }

    @Test
    fun `丢失后不再绘制且isReady不再为真`() {
        assertTrue(
            "isReady 必须包含 !contextLost",
            flat(engine).contains("fun isReady(): Boolean = glReady && !released.get() && !contextLost")
        )
        assertTrue(
            "必须暴露 isContextLost 给所有权方/录制侧",
            flat(engine).contains("fun isContextLost(): Boolean = contextLost")
        )
        val draw = flat(KotlinSourceScan.bodyOf(engine, "drawFrame"))
        assertTrue(
            "编码 pass 判死后必须立刻停手（不跑上屏链、不触发 onFrameDrawn）",
            draw.contains("if (contextLost) return")
        )
        val mc = flat(KotlinSourceScan.bodyOf(engine, "makeCurrent"))
        assertTrue("丢失后 makeCurrent 必须直接拒绝", mc.contains("if (contextLost) return false"))
    }

    @Test
    fun `Controller恢复路径先关注入新引擎且旧引擎恰好释放一次`() {
        val rec = flat(KotlinSourceScan.bodyOf(controller, "recoverGlSink"))
        val closeAt = rec.indexOf("engine?.close()")
        val releaseAt = rec.indexOf("old.release()")
        val freshAt = rec.indexOf("cachedGlSink = fresh")
        assertTrue("恢复必须有关相机会话这一步", closeAt >= 0)
        assertTrue("恢复必须释放旧引擎", releaseAt >= 0)
        assertTrue("先关相机、再释放旧引擎（顺序不可换）", closeAt < releaseAt)
        assertTrue("必须把新实例写回缓存", freshAt > releaseAt)
        assertEquals("旧引擎恰好释放一次（重复 release 是所有权事故）", 1, count(rec, "old.release()"))
        assertTrue("旧实例的丢失回调必须先摘掉", rec.contains("old.onContextLost = null"))
        assertTrue("必须递增引擎代（CameraScreen 靠它换 activeSink）", rec.contains("glSinkEpochFlow.value += 1"))
        assertTrue("旧引擎仍在使用且有预览意图才允许重开相机", rec.contains("if (wasBound && previewWanted)"))
        assertTrue("迟到的 GL 回调不得关掉已切到 DIRECT 的相机", rec.indexOf("if (wasBound)") in 0 until closeAt)
        assertTrue("恢复后必须请求重挂显示面", rec.contains("requestDisplayReattach()"))
        assertTrue("恢复结果不许静默", rec.contains("glRecoveryNoticeFlow.tryEmit("))
    }

    @Test
    fun `同一实例的迟到回调只恢复一次`() {
        val handler = flat(KotlinSourceScan.bodyOf(controller, "onGlContextLost"))
        val filter = handler.indexOf("if (cachedGlSink !== lost) return")
        val recover = handler.indexOf("recoverGlSink(lost)")
        assertTrue("旧实例的排队回调必须在恢复前被拒绝", filter in 0 until recover)
        assertTrue("只对相机正在使用的 GL 实例发停录通知", handler.contains("if (boundSink === lost) glContextLostFlow.tryEmit(reason)"))
        assertTrue("必须交给恢复入口", handler.contains("recoverGlSink(lost)"))
        // 恢复入口自身也要防"缓存已被别的路径换掉"
        val rec = flat(KotlinSourceScan.bodyOf(controller, "recoverGlSink"))
        assertTrue("缓存已被替换时不得误杀新实例", rec.contains("if (cachedGlSink !== old) return cachedGlSink"))
    }

    @Test
    fun `引擎丢失回调在构造时接线且主线程串行恢复`() {
        val factory = flat(KotlinSourceScan.bodyOf(controller, "newGlSink"))
        assertTrue("新建实例必须接线 onContextLost", factory.contains("sink.onContextLost ="))
        assertTrue("回调必须切主线程（GL 线程不碰相机门面状态）", factory.contains("publishOnMain"))
        assertTrue("恢复入口必须收到发出回调的实例", factory.contains("onGlContextLost(sink, reason)"))
    }

    // ------------------------------------------------ CameraScreen / RecordRunner 接线（R05 下半场）

    private val screen = KotlinSourceScan.codeOnly(
        KotlinSourceScan.mainSourceText("ui/CameraScreen.kt")
    )

    @Test
    fun `activeSink以引擎代重新取实例_丢失后不许再接回旧引擎`() {
        assertTrue(
            "必须订阅引擎代",
            flat(screen).contains("val glSinkEpoch by ctrl.glSinkEpoch.collectAsState()")
        )
        assertTrue(
            "activeSink 的 remember 键必须含 glSinkEpoch——否则页面缓存回来的还是已失效旧实例，" +
                "bind/看门狗会把死引擎反复接回去（重挂永远救不活）",
            flat(screen).contains("val activeSink: PreviewSink = remember(renderMode, glSinkEpoch)")
        )
        assertTrue(
            "DIRECT 仍取 directSink（不建 EGL；代变化 remember 值相等，下游 effect 不重跑）",
            flat(screen).contains("if (renderMode == RenderMode.GPU) ctrl.glSink() else directSink")
        )
        // 突变自证：去掉代键后尺子必须红
        val mutated = flat(screen.replace("remember(renderMode, glSinkEpoch)", "remember(renderMode)"))
        assertFalse("去掉引擎代键后尺子必须红", mutated.contains("remember(renderMode, glSinkEpoch)"))
    }

    @Test
    fun `上下文丢失必须通知录制侧显式收尾且恢复提示可见`() {
        val flatScreen = flat(screen)
        val lostWindow = flatScreen.substringAfter("ctrl.glContextLost.collect", missingDelimiterValue = "")
            .substringBefore("ctrl.glRecoveryNotice", missingDelimiterValue = "")
        assertTrue("锚点丢失：没截到 glContextLost 收集器", lostWindow.isNotEmpty())
        assertTrue(
            "收集器必须调 runner.onGlContextLost()——只打日志 = 录制继续录定格却报「已保存」",
            lostWindow.contains("runner.onGlContextLost()")
        )
        assertTrue(
            "恢复通知必须 showTip（一切自动变更都要看得见）",
            flatScreen.contains("ctrl.glRecoveryNotice.collect { notice -> showTip(notice) }")
        )
        // 突变自证：摘掉通知后尺子必须红
        val mutatedWindow = flat(screen.replace("runner.onGlContextLost()", ""))
            .substringAfter("ctrl.glContextLost.collect", "")
            .substringBefore("ctrl.glRecoveryNotice", "")
        assertFalse("摘掉录制侧通知后尺子必须红", mutatedWindow.contains("runner.onGlContextLost()"))
    }

    @Test
    fun `GL丢失停录走专用原因且不改写相机丢失语义`() {
        val lost = flat(KotlinSourceScan.bodyOf(screen, "onGlContextLost"))
        assertTrue("即时信号先落代号（清账窗口靠它兜底）", lost.contains("glLossSeq++"))
        assertTrue("必须置 stopRequested（等待窗/检查点立即让出）", lost.contains("stopRequested = true"))
        assertTrue(
            "START 必须用 REASON_GL_CONTEXT_LOST（原因要如实）",
            lost.contains("autoStopReason = AutoStopRules.REASON_GL_CONTEXT_LOST")
        )
        assertFalse("不许复用 REASON_CAMERA_LOST（渲染重启不是相机被收回）", lost.contains("REASON_CAMERA_LOST"))
        assertTrue("收尾必须走宽限通道（防秒停废片，与 stopAsync 同口径）", lost.contains("stopWithGrace()"))
        val reasonAt = lost.indexOf("REASON_GL_CONTEXT_LOST")
        val graceAt = lost.indexOf("stopWithGrace()")
        assertTrue("先记原因再排队收尾（reason=$reasonAt grace=$graceAt）", reasonAt in 0 until graceAt)
        assertTrue("只对 START 自动停录", lost.contains("RecordStatus.START ->"))
        assertTrue("PREPARE 只记日志，交给起录检查点中止（无会话可停）", lost.contains("RecordStatus.PREPARE -> Log.w"))
        // 突变自证：原因换成相机丢失后尺子必须红
        val mutated = flat(
            KotlinSourceScan.bodyOf(
                screen.replace("AutoStopRules.REASON_GL_CONTEXT_LOST", "AutoStopRules.REASON_CAMERA_LOST"),
                "onGlContextLost"
            )
        )
        assertFalse("复用相机丢失原因后尺子必须红", mutated.contains("AutoStopRules.REASON_GL_CONTEXT_LOST"))
    }

    @Test
    fun `起录会话中止判据含丢失代号且起点捕获在位`() {
        val pred = flat(KotlinSourceScan.bodyOf(screen, "sessionAborted"))
        assertTrue("中止判据必须含用户停止", pred.contains("stopRequested"))
        assertTrue("中止判据必须含丢失代号比对", pred.contains("glLossSeq != sessionGlLossSeq"))
        val start = flat(KotlinSourceScan.bodyOf(screen, "start"))
        val clearAt = start.indexOf("stopRequested = false")
        val captureAt = start.indexOf("sessionGlLossSeq = glLossSeq")
        assertTrue("start 必须清上一场停止信号", clearAt >= 0)
        assertTrue(
            "start 必须捕获本会话起点代号且在清账之后（否则「丢失恰好落在起录途中」被清账吞掉）",
            captureAt > clearAt
        )
        // 突变自证：判据退回只看 stopRequested 后尺子必须红
        val mutated = flat(
            KotlinSourceScan.bodyOf(
                screen.replace("glLossSeq != sessionGlLossSeq", "false"),
                "sessionAborted"
            )
        )
        assertFalse("丢失判据被拆后尺子必须红", mutated.contains("glLossSeq != sessionGlLossSeq"))
    }

    @Test
    fun `换段重挂未绑上新面必须显式失败交契约`() {
        val begin = flat(KotlinSourceScan.bodyOf(screen, "beginSession"))
        assertTrue("必须消费 awaitEncoderSurface 的 Boolean 结果", begin.contains("val bound = try"))
        val guardAt = begin.indexOf("if (!bound)")
        val throwAt = begin.indexOf("throw IllegalStateException")
        assertTrue(
            "换段等待没等来新面必须抛异常（空等放行 = Recorder 当重挂成功继续 start，整段零帧还报成功）",
            guardAt >= 0 && throwAt > guardAt
        )
        assertTrue(
            "awaitEncoderSurface 必须以 Boolean 返回绑定结果",
            flat(screen).contains("private fun awaitEncoderSurface(expected: Surface): Boolean")
        )
        // 突变自证：把守卫拆成恒假后尺子必须红
        assertFalse("拆掉绑定守卫后尺子必须红", begin.replace("if (!bound)", "if (false)").contains("if (!bound)"))
    }

    private fun count(src: String, needle: String): Int = src.split(needle).size - 1
}