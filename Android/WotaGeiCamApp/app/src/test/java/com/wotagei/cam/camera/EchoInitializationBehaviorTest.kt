package com.wotagei.cam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * U03：计划“历史初值为黑”→真实 EchoChain 分配/reset/draw→GL 存储定义域的行为桥。
 *
 * 首帧与换尺寸不预清 RT：真实 draw 采样 null 分配的未定义纹理时由边界立即报错。
 * reset 独立用例先上传定义过的非黑数据，以免首帧缺陷遮住清理缺陷。
 * “关闭重开/方向变化”只测试调用者已发出 reset 的链侧效果，不冒充 GlRenderEngine 接线验证。
 * 断言不是 shader 文本检查，也不是 GLES 像素/观感验证；边界编译或 linkage 错误不算有效 RED。
 */
class EchoInitializationBehaviorTest {
    @Test
    fun `首次公开draw在保持亮度档读取的历史必须已初始化`() = platform { p ->
        assertTrue(p.draw())
        assertEquals(1, p.samples.size)
        assertTrue(p.defined(p.resultTex))
    }

    @Test
    fun `首次公开draw在提亮拖尾档读取的历史必须已初始化`() = platform { p ->
        assertTrue(p.draw(trail = true))
        assertEquals(1, p.samples.size)
        assertTrue(p.defined(p.resultTex))
    }

    @Test
    fun `reset早于prepare与建RT也不能让首帧采未定义历史`() = platform { p ->
        p.reset()
        p.reset()
        assertTrue("早到的reset不得伪造已有RT", p.textures.isEmpty())
        assertTrue(p.draw())
        assertEquals(1, p.samples.size)
    }

    @Test
    fun `prepare后reset仍早于建RT时提亮首帧必须有黑历史`() = platform { p ->
        p.call("prepare")
        p.reset()
        assertTrue(p.textures.isEmpty())
        assertTrue(p.draw(trail = true))
        assertEquals(1, p.samples.size)
    }

    @Test
    fun `真实建RT在任何draw前必须把两张累积面都清成不透明黑`() = platform { p ->
        assertTrue(p.allocate())
        assertBothBlack(p)
        assertTrue("未draw不得伪造采样记录", p.samples.isEmpty())
        assertTrue(p.writes.isEmpty())
    }

    @Test
    fun `保持亮度档换尺寸后公开draw不得采旧存储或新未定义历史`() = platform { p ->
        resizeThroughDraw(p, trail = false)
    }

    @Test
    fun `提亮拖尾档换尺寸后公开draw不得采旧存储或新未定义历史`() = platform { p ->
        resizeThroughDraw(p, trail = true)
    }

    @Test
    fun `显式关闭重开所发reset必须清掉两张已定义非黑历史`() = platform { p ->
        seedExistingHistory(p)
        p.call("resetAfterBreak")
        p.call("prepare")
        p.reset()
        assertBothBlack(p)
        assertTrue(p.draw(trail = true))
    }

    @Test
    fun `方向变化所发reset清掉两张历史后旋转矩阵draw仍可读取`() = platform { p ->
        seedExistingHistory(p)
        p.reset()
        assertBothBlack(p)
        assertTrue(p.draw(rotated = true))
        assertEquals(1, p.samples.size)
    }

    @Test
    fun `reset遇开启的小scissor仍须完整清黑而非只清小块`() = platform { p ->
        seedExistingHistory(p)
        p.callerState(scissor = true)
        p.reset()
        assertBothBlack(p)
    }

    @Test
    fun `reset遇限制colorMask仍须清黑所有RGBA通道`() = platform { p ->
        seedExistingHistory(p)
        p.callerState(masked = true)
        p.reset()
        assertBothBlack(p)
    }

    @Test
    fun `reset同时遇scissor和colorMask限制仍须完整清黑两张面`() = platform { p ->
        seedExistingHistory(p)
        p.callerState(scissor = true, masked = true)
        p.reset()
        assertBothBlack(p)
    }

    @Test
    fun `reset完整清理后恢复调用者FBO视口裁剪写掩码清色和program`() = platform { p ->
        seedExistingHistory(p)
        p.callerState(scissor = true, masked = true)
        val before = p.state()
        p.reset()
        assertEquals("reset不得把清黑用的临时GL状态泄漏给调用者", before, p.state())
    }

    @Test
    fun `reset也恢复原本关闭的scissor及其潜在box与非黑清色`() = platform { p ->
        seedExistingHistory(p)
        p.callerState()
        val before = p.state()
        p.reset()
        assertEquals(before, p.state())
    }

    @Test
    fun `新建RT在scissor及colorMask限制下也须完整清黑`() = platform { p ->
        p.callerState(scissor = true, masked = true)
        assertTrue(p.allocate())
        assertBothBlack(p)
    }

    @Test
    fun `新建与换尺寸RT不得泄漏清理状态或丢调用者FBO`() = platform { p ->
        p.callerState(scissor = true, masked = true)
        val before = p.state()
        assertTrue(p.allocate())
        assertEquals(before, p.state())
        assertTrue(p.allocate(5, 9))
        assertEquals(before, p.state())
    }

    @Test
    fun `已定义历史允许真实连续draw并乒乓而非边界永远报错`() = platform { p ->
        seedExistingHistory(p)
        val ids = p.textures.toSet()
        repeat(3) {
            assertTrue(p.draw())
            assertEquals(p.writes.last(), p.resultTex)
            assertTrue(p.defined(p.resultTex))
        }
        assertEquals("同尺寸连续draw不应重建RT", ids, p.textures.toSet())
        assertEquals(3, p.samples.size)
        assertEquals(3, p.writes.size)
        assertEquals("真实draw必须交替写两张面", 2, p.writes.toSet().size)
        p.samples.zip(p.writes).forEach { (read, write) ->
            assertTrue("采样与写入不得构成feedback loop", read != write)
        }
    }

    @Test
    fun `已有RT的draw仍恢复原FBO视口裁剪和program`() = platform { p ->
        seedExistingHistory(p)
        p.callerState(scissor = true, masked = true)
        val before = p.state()
        assertTrue(p.draw())
        assertEquals(before, p.state())
    }

    @Test
    fun `release删除两张GL纹理和FBO并撤掉结果`() = platform { p ->
        seedExistingHistory(p)
        assertTrue(p.draw())
        p.call("release")
        assertTrue(p.textures.isEmpty())
        assertEquals(0, p.gl("probeFramebufferCount"))
        assertEquals(0, p.resultTex)
        p.call("release")
        assertTrue(p.textures.isEmpty())
    }

    @Test
    fun `非法尺寸早退不建RT不采样不冒充首帧初始化成功`() = platform { p ->
        assertFalse(p.draw(w = 0))
        assertEquals(0, p.resultTex)
        assertTrue(p.textures.isEmpty())
        assertTrue(p.samples.isEmpty())
    }

    @Test
    fun `边界自证null存储未定义且scissor及mask造成的局部clear不算全黑`() = platform { p ->
        val (texture, framebuffer) = rawTarget(p, 4, 3)
        assertFalse(p.defined(texture))
        assertFalse(p.black(texture))
        p.gl("glBindFramebuffer", 0x8D40, framebuffer)
        p.gl("glEnable", 0x0C11)
        p.gl("glScissor", 1, 1, 1, 1)
        p.gl("glColorMask", true, false, true, false)
        p.gl("glClearColor", 0f, 0f, 0f, 1f)
        p.gl("glClear", 0x4000)
        assertFalse("局部/部分通道清理不得谎报已定义", p.defined(texture))
        assertFalse(p.black(texture))
        p.gl("glDisable", 0x0C11)
        p.gl("glClear", 0x4000)
        assertFalse("只去掉scissor仍有通道未初始化", p.defined(texture))
        p.gl("glColorMask", true, true, true, true)
        // GL clear 不受 viewport 限制：避免 fixture 把合法整面clear误判成局部。
        p.gl("glViewport", 0, 0, 1, 1)
        p.gl("glClear", 0x4000)
        assertTrue(p.defined(texture))
        assertTrue(p.black(texture))
    }

    @Test
    fun `边界自证通用2D采样拒绝未定义数据而清理后同一次draw可成功`() = platform { p ->
        val (source, sourceFbo) = rawTarget(p, 4, 3)
        val (target, targetFbo) = rawTarget(p, 4, 3)
        val program = p.gl("probeLink", "attribute vec4 p;", "uniform sampler2D uInput;") as Int
        val location = p.gl("glGetUniformLocation", program, "uInput") as Int
        p.gl("glUseProgram", program)
        p.gl("glUniform1i", location, 2)
        p.gl("glActiveTexture", 0x84C0 + 2)
        p.gl("glBindTexture", 0x0DE1, source)
        p.gl("glBindFramebuffer", 0x8D40, targetFbo)
        p.gl("glViewport", 0, 0, 4, 3)
        val rejected = try {
            p.gl("glDrawArrays", 5, 0, 4)
            null
        } catch (e: AssertionError) { e }
        assertTrue("必须因未定义采样被拒绝，不能用缺方法或linkage错误假红",
            rejected?.message?.contains("首次采样未初始化") == true)
        assertTrue("坏采样不能先写目标", p.writes.isEmpty())
        p.gl("glBindFramebuffer", 0x8D40, sourceFbo)
        p.gl("glClearColor", 0f, 0f, 0f, 1f)
        p.gl("glClear", 0x4000)
        p.gl("glBindFramebuffer", 0x8D40, targetFbo)
        p.gl("glDrawArrays", 5, 0, 4)
        assertTrue(p.defined(target))
        assertEquals(listOf(target), p.writes)
    }

    private fun resizeThroughDraw(p: EchoGlBoundaryFixture, trail: Boolean) {
        seedExistingHistory(p)
        assertTrue(p.draw(trail = trail))
        val previous = p.textures.toSet()
        val samplesBefore = p.samples.size
        assertTrue(p.draw(w = 5, h = 9, trail = trail))
        assertEquals(2, p.textures.size)
        assertTrue("换尺寸必须释放旧纹理", p.textures.none { it in previous })
        p.textures.forEach { assertEquals(listOf(5, 9), p.size(it)) }
        assertEquals(samplesBefore + 1, p.samples.size)
        assertTrue("换尺寸后采样必须来自新RT", p.samples.last() in p.textures)
        assertTrue(p.defined(p.resultTex))
    }

    private fun seedExistingHistory(p: EchoGlBoundaryFixture) {
        assertTrue(p.allocate())
        assertEquals(2, p.textures.size)
        p.uploadDefinedHistory()
        p.textures.forEach {
            assertTrue("对照前提必须是已定义数据", p.defined(it))
            assertFalse("清理前必须确实非黑，不能靠初始黑假绿", p.black(it))
        }
    }

    private fun assertBothBlack(p: EchoGlBoundaryFixture) {
        assertEquals("必须有两张累积面，空集合all不能假绿", 2, p.textures.size)
        p.textures.forEach { id ->
            assertTrue("纹理$id 必须全域全RGBA已定义", p.defined(id))
            assertTrue("纹理$id 必须是完整不透明黑，不能只清一角或部分通道", p.black(id))
        }
    }

    /** 仅供边界自证的通用 GL 资源，不实现任何 Echo 决策。 */
    private fun rawTarget(p: EchoGlBoundaryFixture, w: Int, h: Int): Pair<Int, Int> {
        val texture = IntArray(1)
        val framebuffer = IntArray(1)
        p.gl("glGenTextures", 1, texture, 0)
        p.gl("glBindTexture", 0x0DE1, texture[0])
        p.gl("glTexImage2D", 0x0DE1, 0, 0x1908, w, h, 0, 0x1908, 0x1401, null)
        p.gl("glGenFramebuffers", 1, framebuffer, 0)
        p.gl("glBindFramebuffer", 0x8D40, framebuffer[0])
        p.gl("glFramebufferTexture2D", 0x8D40, 0x8CE0, 0x0DE1, texture[0], 0)
        assertEquals(0x8CD5, p.gl("glCheckFramebufferStatus", 0x8D40))
        return texture[0] to framebuffer[0]
    }

    private fun platform(block: (EchoGlBoundaryFixture) -> Unit) {
        EchoGlBoundaryFixture(classes, javaClass.classLoader).use(block)
    }

    companion object {
        @ClassRule @JvmField
        val temporary = TemporaryFolder(File("build/tmp/echo-u03-behavior-test").apply { mkdirs() })
        private val classes: File by lazy { EchoGlBoundaryFixture.compile(temporary.newFolder("gles-boundary")) }
    }
}
