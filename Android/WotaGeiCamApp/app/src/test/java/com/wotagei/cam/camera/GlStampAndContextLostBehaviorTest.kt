package com.wotagei.cam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * R05 / Y10 行为桩测：真实 GlRenderEngine 字节码 + 可编程 EGL 打戳返回值。
 *
 * - Y10：`eglPresentationTimeANDROID` 返回 false 时必须 stampFailCount+1，且不得推进
 *   `encoderLastPtsNs`/`encoderFrameIndex`（位次账不能宣称容器里有并不存在的网格戳）；
 * - R05：上下文丢失进入终态——只回调一次、isReady=false、丢失后 makeCurrent 直接拒绝。
 *   真机 EGL_CONTEXT_LOST 仍归设备验证；这里锁的是引擎对丢失的反应，不是驱动行为。
 */
class GlStampAndContextLostBehaviorTest {

    @get:Rule
    val temporary = TemporaryFolder(File("build/tmp/egl-stamp-test").apply { mkdirs() })

    private val classes by lazy { EglConfigPlatformFixture.compile(temporary.newFolder("platform")) }

    private fun withEngine(block: (EglConfigPlatformFixture) -> Unit) {
        EglConfigPlatformFixture(classes, requireNotNull(javaClass.classLoader!!)).use { p ->
            p.newEngine()
            p.setEngineField("eglDisplay", p.display())
            p.setEngineField("encoderFps", 30)
            p.setEngineField("encoderNtsc", false)
            p.setEngineField("encoderBaseNs", 0L)
            p.setEngineField("encoderLastPtsNs", 123L)
            p.setEngineField("encoderFrameIndex", 7)
            p.setEngineField("stampFailCount", 0)
            block(p)
        }
    }

    @Test
    fun `Y10 打戳返回false必须记失败且不推进位次账`() = withEngine { p ->
        p.script("presentOk", false)
        val surface = p.newSurface("encoder")
        val written = p.callEngine("stampEncoderPresentation", surface) as Boolean
        assertFalse("EGL 返回 false 必须判写戳失败", written)
        assertEquals("失败次数必须照实计", 1, p.engineField("stampFailCount"))
        assertEquals("失败帧不得推进最后一枚戳", 123L, p.engineField("encoderLastPtsNs"))
        assertEquals("失败帧不得推进位次", 7, p.engineField("encoderFrameIndex"))
        assertEquals("确实调用了 EGL 打戳", 1, p.number("presentCalls"))
    }

    @Test
    fun `Y10 打戳成功才推进位次账`() = withEngine { p ->
        p.script("presentOk", true)
        val surface = p.newSurface("encoder")
        val written = p.callEngine("stampEncoderPresentation", surface) as Boolean
        assertTrue("EGL 返回 true 必须判成功", written)
        assertEquals("成功帧不记失败", 0, p.engineField("stampFailCount"))
        assertEquals("成功帧推进位次", 8, p.engineField("encoderFrameIndex"))
        assertNotEquals("成功帧必须落下新戳", 123L, p.engineField("encoderLastPtsNs"))
    }

    @Test
    fun `R05 上下文丢失终态只回调一次且拒绝继续绘制`() = withEngine { p ->
        val calls = mutableListOf<String>()
        val callback: (String) -> Unit = { calls += it }
        p.setEngineField("onContextLost", callback)

        p.callEngine("handleContextLost", "makeCurrent=0x300E")
        assertTrue("必须进入丢失终态", p.callEngine("isContextLost") as Boolean)
        assertFalse("丢失后不再是就绪状态", p.callEngine("isReady") as Boolean)
        assertEquals("所有权方必须收到一次通知", listOf("makeCurrent=0x300E"), calls)
        assertEquals("编码面必须失效", null, p.engineField("encoderEglSurface"))
        assertEquals("窗口面必须失效", null, p.engineField("windowEglSurface"))

        // 迟到回调不得二次上报（旧实例单次生命周期）
        p.callEngine("handleContextLost", "late")
        assertEquals("迟到回调不得二次上报", 1, calls.size)

        // 丢失后的 makeCurrent 必须直接拒绝，不能在死上下文上继续画
        assertFalse(
            "丢失后 makeCurrent 必须返回 false",
            p.callEngine("makeCurrent", null) as Boolean
        )
    }
}
