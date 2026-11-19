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
        assertTrue("页面还在才允许重开相机", rec.contains("if (previewWanted)"))
        assertTrue("恢复后必须请求重挂显示面", rec.contains("requestDisplayReattach()"))
        assertTrue("恢复结果不许静默", rec.contains("glRecoveryNoticeFlow.tryEmit("))
    }

    @Test
    fun `同一实例的迟到回调只恢复一次`() {
        val handler = flat(KotlinSourceScan.bodyOf(controller, "onGlContextLost"))
        assertTrue("必须按实例身份去重", handler.contains("if (glContextLossHandled === lost) return"))
        assertTrue("去重标记必须先落", handler.contains("glContextLossHandled = lost"))
        assertTrue("只对当前缓存实例生效", handler.contains("val lost = cachedGlSink ?: return"))
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
        assertTrue("主线程入口必须是 onGlContextLost", factory.contains("onGlContextLost(reason)"))
    }

    private fun count(src: String, needle: String): Int = src.split(needle).size - 1
}