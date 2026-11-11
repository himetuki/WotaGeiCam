package com.wotagei.cam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ArcMendPass 资源边界行为的 RED 测试。
 *
 * 测试只调用真实 ArcMendPass 字节码，GLES/Diag 由独立边界桩提供；不执行真实 GL 像素，
 * 也不复制生产 ensure 或资源释放算法。当前生产行为在不完整 FBO 返回路径上预期失败，
 * 主审运行后应保留该失败作为下一生产修复窗口的依据。
 */
class ArcMendResourceBehaviorTest {

    @Test
    fun `第二个目标FBO不完整时本轮所有句柄都应回收且重复失败不应积存`() = platform { pass ->
        pass.failFramebufferAt(2)
        assertFalse("第二个目标不完整时 ensure 必须失败", pass.ensure(8, 6))

        val firstRound = pass.targetResources
        assertEquals(5, firstRound.textures.size)
        assertEquals(5, firstRound.framebuffers.size)
        assertTrue("失败目标仍应拿到非零纹理句柄", firstRound.textures[1] != 0)
        assertTrue("失败目标仍应拿到非零 FBO 句柄", firstRound.framebuffers[1] != 0)
        assertEquals(INCOMPLETE, pass.framebufferStatuses[1])

        pass.release()
        pass.release()

        pass.failFramebufferAt(2)
        assertFalse("再次遇到不完整 FBO 时 ensure 必须失败", pass.ensure(8, 6))
        val allGenerated = pass.generatedTextures + pass.generatedFramebuffers
        pass.release()
        pass.release()

        allGenerated.forEach { id ->
            assertEquals("句柄$id 应恰好删除一次", 1, pass.deleteCount(id))
        }
        assertTrue("不完整 FBO 的纹理和 FBO 不能留在存活集合", pass.live.isEmpty)
    }

    @Test
    fun `相同尺寸ensure复用同一组五对目标而不重建`() = platform { pass ->
        assertTrue(pass.ensure(8, 6))
        val first = pass.targetResources
        val generatedTextures = pass.generatedTextures.size
        val generatedFramebuffers = pass.generatedFramebuffers.size

        assertTrue(pass.ensure(8, 6))
        assertEquals("同尺寸不得重建纹理", first.textures, pass.targetResources.textures)
        assertEquals("同尺寸不得重建 FBO", first.framebuffers, pass.targetResources.framebuffers)
        assertEquals(generatedTextures, pass.generatedTextures.size)
        assertEquals(generatedFramebuffers, pass.generatedFramebuffers.size)
        assertEquals(5, pass.live.textures.size)
        assertEquals(5, pass.live.framebuffers.size)

        pass.release()
        pass.release()
        (first.textures + first.framebuffers).forEach { id ->
            assertEquals("复用目标$id 最终应恰好删除一次", 1, pass.deleteCount(id))
        }
        assertTrue(pass.live.isEmpty)
    }

    @Test
    fun `换尺寸先释放旧目标再只保留新尺寸五对目标`() = platform { pass ->
        assertTrue(pass.ensure(8, 6))
        val old = pass.targetResources

        assertTrue(pass.ensure(10, 6))
        val fresh = pass.targetResources
        assertEquals(5, fresh.textures.size)
        assertEquals(5, fresh.framebuffers.size)
        assertTrue(old.textures.none { it in pass.live.textures })
        assertTrue(old.framebuffers.none { it in pass.live.framebuffers })
        assertTrue(old.textures.none { it in fresh.textures })
        assertTrue(old.framebuffers.none { it in fresh.framebuffers })
        (old.textures + old.framebuffers).forEach { id ->
            assertEquals("旧目标$id 换尺寸时应恰好删除一次", 1, pass.deleteCount(id))
        }
        assertEquals(List(5) { listOf(10, 6) }, fresh.textures.map { pass.size(it) })

        pass.release()
        pass.release()
        (fresh.textures + fresh.framebuffers).forEach { id ->
            assertEquals("新目标$id 最终应恰好删除一次", 1, pass.deleteCount(id))
        }
        assertTrue(pass.live.isEmpty)
    }

    @Test
    fun `program链接失败也应回收已经生成的五对目标且重复release幂等`() = platform { pass ->
        pass.failProgramLinkAt(1)
        assertFalse("program 链接失败时 ensure 必须失败", pass.ensure(8, 6))

        val targets = pass.targetResources
        assertEquals(5, targets.textures.size)
        assertEquals(5, targets.framebuffers.size)
        pass.release()
        pass.release()

        (targets.textures + targets.framebuffers).forEach { id ->
            assertEquals("program 失败后的目标$id 应恰好删除一次", 1, pass.deleteCount(id))
        }
        assertTrue("program 失败后不得留下任何 GL 资源", pass.live.isEmpty)
    }

    private fun platform(block: (ArcMendGlBoundaryFixture) -> Unit) {
        val pass = ArcMendGlBoundaryFixture(classes, javaClass.classLoader)
        try {
            block(pass)
        } finally {
            pass.close()
        }
    }

    companion object {
        private const val INCOMPLETE = 0x8CD6

        @ClassRule
        @JvmField
        val temporary = TemporaryFolder(File("build/tmp/arc-mend-resource-behavior-test").apply { mkdirs() })

        private val classes: File by lazy {
            ArcMendGlBoundaryFixture.compile(temporary.newFolder("gles-boundary"))
        }
    }
}
