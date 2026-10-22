package com.wotagei.cam

import com.wotagei.cam.camera.FROST_BLUR_WEIGHTS
import com.wotagei.cam.camera.Shaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 毛玻璃离屏链的**着色器侧账**（#84）。
 *
 * 这里没有 GL，只有"源码文本与参数表是否同源"这一件事，但它恰是真机上最难归因的一类故障：
 * `FrostBlurChain` 按 [FROST_BLUR_WEIGHTS] 的长度生成 `uniform float uWeight[N]` 并按同一张表
 * `glUniform1fv` 上传，**表与源不同步就是链接期数组越界或 uniform 数量不符**，
 * 表现是"模糊整条链静默停用、预览一切正常"——真机上看不出来，只能等到有人盯着卡片发现没霜。
 * 所以这里测的是那座"计划（表）→ 行为（源码）"的桥本身（AGENTS.md：删掉运行时判断分支测试仍全绿的，
 * 就是没测桥）。
 *
 * 另两条是本目录既有 `ShadersCurveTest` 已经在守的同族纪律：ES 1.00 写法（无 `#version`），
 * 以及 `samplerExternalOES` 与 `sampler2D` **不许混进同一条 program**（拷贝那条只能有前者，
 * 模糊那两条只能有后者）。
 */
class ShadersFrostTest {

    /** 出现次数（不是"是否包含"）——多写一次取样就少糊一次，包含判据测不出来 */
    private fun countOf(source: String, needle: String): Int =
        source.split(needle).size - 1

    private fun horizontalSource(highp: Boolean = true): String =
        Shaders.frostGaussianFragment(FROST_BLUR_WEIGHTS.size, highp)

    @Test
    fun gaussianTapCountIsGeneratedFromTheWeightTable() {
        val source = horizontalSource()
        // 可分离一趟 = 中心 1 次 + 每对偏移 2 次
        assertEquals(
            "取样次数必须由权重表长度推出",
            2 * FROST_BLUR_WEIGHTS.size - 1,
            countOf(source, "texture2D(uSrc,")
        )
        // 声明的数组长度必须就是表长，否则 glUniform1fv 会传越界
        assertTrue(source.contains("uniform float uWeight[${FROST_BLUR_WEIGHTS.size}];"))
    }

    @Test
    fun gaussianUsesOnlyConstantSubscriptsWithinTheTable() {
        val source = horizontalSource()
        // 只查 main() 里的**使用**下标：声明行 `uniform float uWeight[4];` 的那个 4 是数组长度，
        // 不是取样下标，混进来就会把"越界"看成正常值（本条第一次跑就是这么漏的，已修）
        val body = source.substringAfter("void main()")
        val indices = Regex("uWeight\\[(\\d+)]").findAll(body).map { it.groupValues[1].toInt() }.toList()
        val distinct = indices.distinct().sorted()
        // ES 1.00 只允许常量下标：这里逐个查出来的是字面量，且必须恰好覆盖 0..N-1，不多不少
        assertEquals((0 until FROST_BLUR_WEIGHTS.size).toList(), distinct)
        assertEquals("最大下标必须落在表内", FROST_BLUR_WEIGHTS.size - 1, distinct.last())
        // 每个权重恰好被引用一次：中心自己一次，其余每项各配一对 ±k 取样（成对共用一次乘法）。
        // 引用数少于表长 = 表里有一档白存着（半径被悄悄改小），多于 = 有取样没配权重
        assertEquals(FROST_BLUR_WEIGHTS.size, indices.size)
    }

    @Test
    fun gaussianSamplesBothDirectionsFromTheSameUniformOffset() {
        // 横/纵共用一枚 program，方向只由 uOffset 的分量决定：源码里必须成对出现 +uOffset / -uOffset
        val source = horizontalSource()
        assertEquals(FROST_BLUR_WEIGHTS.size - 1, countOf(source, "vUv + uOffset *"))
        assertEquals(FROST_BLUR_WEIGHTS.size - 1, countOf(source, "vUv - uOffset *"))
        assertTrue(source.contains("uniform vec2 uOffset;"))
    }

    @Test
    fun gaussianStaysEsOneAndHonoursThePrecisionFallback() {
        val highp = horizontalSource(highp = true)
        val mediump = horizontalSource(highp = false)
        // 无 #version：ES 1.00 写法在 ES2 与 ES3 上下文都能编（引擎优先申请 ES3、失败退 ES2）
        assertFalse(highp.contains("#version"))
        assertFalse(mediump.contains("#version"))
        assertTrue(highp.contains("precision highp float;"))
        assertTrue(mediump.contains("precision mediump float;"))
        assertFalse(highp.contains("precision mediump"))
    }

    @Test
    fun gaussianNeverTouchesAnExternalOesSampler() {
        val source = horizontalSource()
        // 模糊两趟只吃普通 2D：混进 samplerExternalOES 会链接失败，整条链静默停用
        assertFalse(source.contains("samplerExternalOES"))
        assertFalse(source.contains("GL_OES_EGL_image_external"))
        assertTrue(source.contains("uniform sampler2D uSrc;"))
        // 强制不透明：RT 的 alpha 若跟着相机帧走到 0，上屏那侧会看见全黑背板
        assertTrue(source.contains("gl_FragColor = vec4(sum, 1.0);"))
    }

    @Test
    fun copyPassIsTheOnlyNewFragmentThatSamplesTheOesFrame() {
        val copy = Shaders.FROST_COPY_FS
        // #extension 必须是首条指令（部分驱动硬要求），且 uFrame 只能是 samplerExternalOES
        assertTrue(copy.startsWith("#extension GL_OES_EGL_image_external : require"))
        assertTrue(copy.contains("uniform samplerExternalOES uFrame;"))
        assertFalse(copy.contains("sampler2D"))
        assertTrue(copy.contains("gl_FragColor = vec4(texture2D(uFrame, vUv).rgb, 1.0);"))
        // 拷贝趟骑在 TEXTURE_VS 上：与上屏 pass 共用同一套纹理坐标与缓冲矩阵，RT 才和屏幕同朝向
        assertTrue(Shaders.TEXTURE_VS.contains("uniform mat4 uTexMatrix;"))
        // 模糊趟骑在 FROST_QUAD_VS 上：那张图已经是普通 2D，再乘一次机型缓冲矩阵会被转两遍
        assertFalse(Shaders.FROST_QUAD_VS.contains("uTexMatrix"))
        assertTrue(Shaders.FROST_QUAD_VS.contains("vUv = aTexCoord;"))
    }

    @Test
    fun existingPassThroughShaderIsNotPollutedByTheFrostChain() {
        // M2 门禁「GPU 与 DIRECT 画面逐像素一致」的最后兜底就是这条直通 program，
        // 它一个字节都不该被新特性污染（污染了就没有"链接失败退回直通"这条路）
        val passThrough = Shaders.PASS_THROUGH_FS
        assertTrue(passThrough.startsWith("#extension GL_OES_EGL_image_external : require"))
        assertFalse(passThrough.contains("uWeight"))
        assertFalse(passThrough.contains("uOffset"))
        assertFalse(passThrough.contains("uSrc"))
        assertEquals(1, countOf(passThrough, "texture2D(uFrame, vUv)"))
    }
}
