package com.wotagei.cam.core

import com.wotagei.cam.camera.CameraPlatformFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 物理子头 AF 模式回退到逻辑头（docs/plan/27，2026-10-28 落地）。
 *
 * 既测回退规则，也穿过真实 enumerateLenses 和 CameraAbility.from，确保物理路由改动不会丢掉
 * 逻辑头 AF 能力；不再把实现中的 continue 当成行为证据。
 */
class SubcameraAfFallbackTest {

    private val continuous = setOf(0, 1, 3)

    @Test
    fun `子头报全OFF时借逻辑头模式`() {
        assertEquals(continuous, effectiveAfModes(setOf(0), continuous))
        assertEquals(continuous, effectiveAfModes(emptySet(), continuous))
    }

    @Test
    fun `子头有有效模式或借不到时维持原文`() {
        val own = setOf(0, 1)
        assertEquals(own, effectiveAfModes(own, continuous))
        assertEquals(own, effectiveAfModes(own, null))
        assertEquals(setOf(0), effectiveAfModes(setOf(0), null))
        assertEquals(emptySet<Int>(), effectiveAfModes(emptySet(), null))
        assertEquals(setOf(0), effectiveAfModes(setOf(0), emptySet()))
        assertEquals(emptySet<Int>(), effectiveAfModes(emptySet(), emptySet()))
    }

    @Test
    fun `逻辑头模式必须原样借出不加不减`() {
        val weird = setOf(5)
        assertEquals(weird, effectiveAfModes(emptySet(), weird))
        assertEquals(setOf(3), effectiveAfModes(setOf(0), setOf(3)))
    }

    @Test
    fun `完整枚举的隐藏物理子头必须实际借用逻辑头AF`() = platform { p ->
        p.publicIds("0", "1")
        p.camera("0", logical = true, physicals = listOf("20"), af = intArrayOf(0, 1, 3))
        p.camera("1", front = true)
        p.camera("20", openable = false, af = intArrayOf(0))
        val slot = p.enumerate().firstOrNull { p.physical(it) == "20" }
        assertNotNull("完整枚举必须保留物理路由槽", slot)
        assertEquals(continuous, p.call(p.ability(slot!!), "getAfModes"))
    }

    private fun platform(block: (CameraPlatformFixture) -> Unit) {
        CameraPlatformFixture(classes, javaClass.classLoader).use(block)
    }

    companion object {
        @JvmField @ClassRule
        val temporary = TemporaryFolder(File("build/tmp/camera-compatibility-test").apply { mkdirs() })
        private val classes: File by lazy { CameraPlatformFixture.compile(temporary.newFolder("af-platform")) }
    }
}
