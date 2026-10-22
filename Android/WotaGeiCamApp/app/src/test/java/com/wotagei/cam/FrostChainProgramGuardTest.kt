package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「半初始化态下重复编 program 不许泄漏 GL 对象」的源码级守卫（迭代 9 · 批次 I）。
 *
 * 缺陷本体：blur program 链接失败会留下 `copyProgram != 0 && blurProgram == 0` 的半初始化态，
 * 此时 `prepare` 的「两个都非 0 才短路」拦不住再次进入 `buildPrograms`；旧版开头无条件重链
 * copyProgram 且不删旧件 ⇒ 每次翻一次霜开关漏一枚 GL program（buildPrograms 不置 broken，
 * `draw` 里的 failChain 也接不住这条显式翻转路）。修复：buildPrograms 开头先 deletePrograms。
 *
 * FrostBlurChain 全是 GLES 调用，JVM 无行为测试，按工程惯例落源码级守卫。
 */
class FrostChainProgramGuardTest {

    private val src by lazy { KotlinSourceScan.mainSourceText("camera/FrostBlurChain.kt") }
    private val masked by lazy { KotlinSourceScan.codeOnly(src) }

    @Test
    fun `buildPrograms 开头必须先清旧件再编`() {
        val body = KotlinSourceScan.bodyOf(masked, "buildPrograms")
        val delete = body.indexOf("deletePrograms()")
        val link = body.indexOf("linkProgram(")
        assertTrue("锚点丢失：buildPrograms 里没截到 linkProgram 调用", link >= 0)
        assertTrue(
            "buildPrograms 缺少开头的 deletePrograms()——blur 链接失败的半初始化态下每翻一次开关漏一枚 program",
            delete >= 0
        )
        assertTrue(
            "deletePrograms() 必须在第一次 linkProgram 之前（delete=$delete link=$link）：先删后编才是幂等重建",
            delete < link
        )
    }

    @Test
    fun `deletePrograms 必须把 uniform 位置缓存一并复位`() {
        // 复位不全的话，重建后的链会拿着已删 program 的位置下标去 setUniform
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(masked, "deletePrograms"))
        assertTrue("锚点丢失：没截到 program 删除", body.contains("GLES20.glDeleteProgram(copyProgram)"))
        listOf(
            "copyAPosition = -1", "copyATexCoord = -1", "copyUFrame = -1", "copyUTexMatrix = -1",
            "blurAPosition = -1", "blurATexCoord = -1", "blurUSrc = -1", "blurUOffset = -1", "blurUWeight = -1"
        ).forEach {
            assertTrue("deletePrograms 缺少 $it 的复位——重建后会拿旧位置写新 program", body.contains(it))
        }
    }
}
