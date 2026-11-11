package com.wotagei.cam.player

import com.wotagei.cam.record.ArcOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** B03：只替换 EGL/GLES 外部边界，打戳返回值、swap 分支与调用者成功账均运行生产字节码。 */
class OfflineGlCompatibilityBridgeTest {
    @Test
    fun `B03 Scene真实render遇EGL正常返回false不得swap`() = platform { p ->
        val gl = p.gl(scene = true)
        p.script("stampMode", 0)
        assertFalse("无异常不等于打戳成功", p.call(gl, "render", 33_333L) as Boolean)
        assertEquals(1, p.script("stampCalls"))
        assertEquals(0, p.script("swapCalls"))
        assertTrue(p.events.any { it.startsWith("warn:") && it.contains("打戳失败") })
    }

    @Test
    fun `B03 Scene真实render遇EGL抛错不得swap`() = platform { p ->
        val gl = p.gl(scene = true)
        p.script("stampMode", -1)
        assertFalse(p.call(gl, "render", 33_333L) as Boolean)
        assertEquals(1, p.script("stampCalls"))
        assertEquals(0, p.script("swapCalls"))
    }

    @Test
    fun `B03 Scene真实render成功时先按微秒转纳秒打戳再swap`() = platform { p ->
        assertTrue(p.call(p.gl(scene = true), "render", 33_333L) as Boolean)
        assertEquals(listOf("egl.stamp:33333000", "egl.swap"), eglEvents(p))
        assertEquals(1, p.script("swapCalls"))
    }

    @Test
    fun `B03 Scene打戳成功但swap失败仍不是成功帧`() = platform { p ->
        p.script("swapOk", false)
        assertFalse(p.call(p.gl(scene = true), "render", 33_333L) as Boolean)
        assertEquals(listOf("egl.stamp:33333000", "egl.swap"), eglEvents(p))
    }

    @Test
    fun `B03 Arc真实emit遇EGL正常返回false须计失败且不swap`() = platform { p ->
        val gl = p.gl(scene = false)
        p.script("stampMode", 0)
        assertFalse("无异常不等于打戳成功", p.call(gl, "emitPending", 41_666L) as Boolean)
        assertEquals(1, p.field(gl, "stampFailCount"))
        assertEquals(1, p.script("stampCalls"))
        assertEquals(0, p.script("swapCalls"))
    }

    @Test
    fun `B03 Arc真实emit遇EGL抛错须计失败且不swap`() = platform { p ->
        val gl = p.gl(scene = false)
        p.script("stampMode", -1)
        assertFalse(p.call(gl, "emitPending", 41_666L) as Boolean)
        assertEquals(1, p.field(gl, "stampFailCount"))
        assertEquals(1, p.script("stampCalls"))
        assertEquals(0, p.script("swapCalls"))
    }

    @Test
    fun `B03 Arc正常false和throw各计一次失败不漏计不重复`() = platform { p ->
        val gl = p.gl(scene = false)
        p.script("stampMode", 0)
        p.call(gl, "emitPending", 0L)
        p.script("stampMode", -1)
        p.call(gl, "emitPending", 41_666L)
        assertEquals("失败累计必须包含正常返回false", 2, p.field(gl, "stampFailCount"))
        assertEquals(0, p.script("swapCalls"))
    }

    @Test
    fun `B03 Arc成功时先叠会话基址打戳再swap且不记失败`() = platform { p ->
        val gl = p.gl(scene = false, basePtsNs = 7_000_000L)
        assertTrue(p.call(gl, "emitPending", 41_666L) as Boolean)
        assertEquals(listOf("egl.stamp:48666000", "egl.swap"), eglEvents(p))
        assertEquals(0, p.field(gl, "stampFailCount"))
    }

    @Test
    fun `B03 Arc打戳成功但swap失败仍不是成功帧`() = platform { p ->
        val gl = p.gl(scene = false)
        p.script("swapOk", false)
        assertFalse(p.call(gl, "emitPending", 41_666L) as Boolean)
        assertEquals(listOf("egl.stamp:41666000", "egl.swap"), eglEvents(p))
        assertEquals("swap失败不是stamp失败，不能污染观测计数", 0, p.field(gl, "stampFailCount"))
    }

    @Test
    fun `B03 Arc真实execGpu对正常false和throw均报GPU且不推进成功账`() {
        for (mode in listOf(0, -1)) platform { p ->
            val progress = mutableListOf<Float>()
            val session = p.arcSession(p.gl(scene = false)) { progress.add(it) }
            p.script("stampMode", mode)
            val thrown = try {
                p.call(session, "execGpu", listOf(ArcOp.EmitPending(41_666L)))
                null
            } catch (e: Exception) { e }
            assertEquals("必须是生产GPU失败，不能用桩NPE假红", "ArcFail", thrown?.javaClass?.simpleName)
            assertEquals("ARC_GPU", p.call(thrown!!, "getCode"))
            assertEquals("坏帧未发出去，不能计成功", 0, p.field(session, "outputsDone"))
            assertTrue(progress.isEmpty())
            assertEquals(0, p.script("swapCalls"))
        }
    }

    @Test
    fun `B03 Arc真实execGpu成功emit恰计一次排除永远失败的假绿`() = platform { p ->
        val progress = mutableListOf<Float>()
        val session = p.arcSession(p.gl(scene = false)) { progress.add(it) }
        p.call(session, "execGpu", listOf(ArcOp.EmitPending(41_666L)))
        assertEquals(1, p.field(session, "outputsDone"))
        assertEquals(1, progress.size)
        assertEquals(1, p.script("swapCalls"))
    }

    private fun eglEvents(p: ExportPlatformFixture): List<String> = p.events.filter { it.startsWith("egl.") }
    private fun platform(block: (ExportPlatformFixture) -> Unit) {
        ExportPlatformFixture(classes, javaClass.classLoader, realGl = true).use(block)
    }
    companion object {
        @ClassRule @JvmField
        val temporary = TemporaryFolder(File("build/tmp/offline-gl-compatibility-test").apply { mkdirs() })
        private val classes by lazy { ExportPlatformFixture.compile(temporary.newFolder("platform")) }
    }
}
