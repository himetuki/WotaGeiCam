package com.wotagei.cam

import com.wotagei.cam.camera.Shaders
import com.wotagei.cam.core.FrameEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 残影的**片元注入账**（R2 高光拖影，2026-11-05；docs/research/R2 §四）——与 [GlowShaderTest]
 * 同一把"源码文本断言"的尺。每一个错法在真机上同样只是"看起来生效了"：
 *
 * - 关闭时把注入段留在源码里 ⇒ 零代价破产；
 * - scene 终写没被包住 ⇒ 拖影只作用在"没调色"的像素上；
 * - 斑马纹的**条纹分支**被包住 ⇒ 判读条纹本身拖影；
 * - 峰值的**描边色**被拖影 ⇒ 五档描边色互相串味；
 * - Glow 已注入时的锚点形态没对上 ⇒ echoFragment 返回 null、整条链接失败（静默没有拖影）。
 */
class EchoShaderTest {

    private fun countOf(source: String, needle: String): Int = source.split(needle).size - 1

    private val echoTokens = listOf("applyEcho", "vUvEcho", "uEchoSrc", "uEchoMode", "uEchoGain")

    // ------------------------------------------------------------------ echo=false：源码逐字不变

    @Test
    fun `所有base源与glow产物本身不含任何残影痕迹`() {
        // 关着时调用方根本不调 echoFragment ⇒ "逐字不变"等价于"base 常量与 glow 产物里没有注入段"
        val sources = mapOf(
            "PASS_THROUGH_FS" to Shaders.PASS_THROUGH_FS,
            "CURVE_FS" to Shaders.CURVE_FS,
            "BILATERAL_FS" to Shaders.BILATERAL_FS,
            "BILATERAL_CURVE_FS" to Shaders.BILATERAL_CURVE_FS,
            "BILATERAL_LITE_FS" to Shaders.BILATERAL_LITE_FS,
            "BILATERAL_LITE_CURVE_FS" to Shaders.BILATERAL_LITE_CURVE_FS,
            "zebra(false)" to Shaders.zebraFragment(false),
            "peaking(false)" to Shaders.peakingFragment(false),
            "TEXTURE_VS" to Shaders.TEXTURE_VS,
            "TEXTURE_VS_GLOW" to Shaders.TEXTURE_VS_GLOW,
            "glow(PASS_THROUGH)" to Shaders.glowFragment(Shaders.PASS_THROUGH_FS, FrameEffect.NONE)!!,
            "glow(zebra)" to Shaders.glowFragment(Shaders.zebraFragment(true), FrameEffect.ZEBRA)!!,
            "glow(peaking)" to Shaders.glowFragment(Shaders.peakingFragment(true), FrameEffect.PEAKING)!!
        )
        for ((name, src) in sources) {
            for (token in echoTokens) {
                assertEquals("$name 里出现了 $token —— echo 关着时源码必须逐字不变（零代价）", 0, countOf(src, token))
            }
        }
    }

    @Test
    fun `顶点源分四版且各有各的varying`() {
        assertEquals("TEXTURE_VS 不许有 vUvEcho", 0, countOf(Shaders.TEXTURE_VS, "vUvEcho"))
        assertEquals("TEXTURE_VS_GLOW 不许有 vUvEcho", 0, countOf(Shaders.TEXTURE_VS_GLOW, "vUvEcho"))
        assertTrue(Shaders.TEXTURE_VS_ECHO.contains("varying vec2 vUvEcho;"))
        // vUvEcho 不许乘矩阵（乘了就是拖影相对画面差一次旋转/镜像，与 vUvGlow 同一条推导）
        for (src in listOf(Shaders.TEXTURE_VS_ECHO, Shaders.TEXTURE_VS_GLOW_ECHO)) {
            assertTrue(src.contains("vUvEcho = aTexCoord;"))
            assertFalse("vUvEcho 不许乘矩阵", src.contains("vUvEcho = (uTexMatrix"))
            assertTrue("必须共用同一套 vUv 口径", src.contains("vUv = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;"))
        }
        // 双开版两条 varying 都在（缺一条 = 链接失败）
        assertTrue(Shaders.TEXTURE_VS_GLOW_ECHO.contains("varying vec2 vUvGlow;"))
    }

    // ------------------------------------------------------------------ 累积趟（ECHO_ACCUM_FS）

    @Test
    fun `累积趟实现双模式而不是整帧叠加`() {
        val src = Shaders.ECHO_ACCUM_FS
        assertTrue(
            "首条必须仍是 #extension（ES 要求扩展声明在最前）",
            src.trimStart().startsWith("#extension GL_OES_EGL_image_external : require")
        )
        assertTrue("相机帧用 samplerExternalOES", src.contains("uniform samplerExternalOES uFrame;"))
        assertTrue("历史面用 sampler2D", src.contains("uniform sampler2D uEchoPrev;"))
        // 双 UV 口径（2026-11-05 r44 真机缺陷回归）：OES 相机帧采矩阵变换的 vUv，prev 累积面
        // 已在 RT 空间必须采恒等的 vUvEcho；prev 若也乘矩阵 ⇒ 拖影相对真画面转置/对称。
        assertTrue("必须声明恒等 varying vUvEcho", src.contains("varying vec2 vUvEcho;"))
        assertTrue("相机帧采矩阵变换的 vUv", src.contains("vec3 c = texture2D(uFrame, vUv).rgb;"))
        assertTrue("prev 必须采恒等 vUvEcho 且先乘衰减", src.contains("vec3 prev = texture2D(uEchoPrev, vUvEcho).rgb * uEchoDecay;"))
        assertFalse("prev 不许采矩阵变换的 vUv（会转置/对称）", src.contains("texture2D(uEchoPrev, vUv)"))
        // 双模式（R2 §四.2）：保持亮度 = max（星轨式，背景零提亮）；提亮拖尾 = 高光阈值后加法
        assertTrue("保持亮度档必须是 max(c, prev)", src.contains("max(c, prev)"))
        assertTrue("提亮档必须做高光阈值切分", src.contains("smoothstep("))
        assertTrue("阈值 uniform", src.contains("uniform float uEchoThreshold;"))
        assertTrue("衰减 uniform", src.contains("uniform float uEchoDecay;"))
        assertTrue("模式分派 uniform", src.contains("uniform float uEchoMode;"))
        assertTrue("亮度按 BT.601 权重", src.contains("dot(c, vec3(0.299, 0.587, 0.114))"))
        assertTrue("强制不透明", src.contains(", 1.0);"))
    }

    // ------------------------------------------------------------------ echo=true：scene 支路（含 glow 已注入的形态）

    @Test
    fun `scene支路把终写整个包进applyEcho且包在glow外层`() {
        val sceneSources = mapOf(
            "PASS_THROUGH_FS" to Shaders.PASS_THROUGH_FS,
            "CURVE_FS" to Shaders.CURVE_FS,
            "BILATERAL_FS" to Shaders.BILATERAL_FS,
            "BILATERAL_CURVE_FS" to Shaders.BILATERAL_CURVE_FS
        )
        for ((name, src) in sceneSources) {
            for (glowOn in listOf(false, true)) {
                val base = if (glowOn) Shaders.glowFragment(src, FrameEffect.NONE)!! else src
                val echo = Shaders.echoFragment(base, FrameEffect.NONE, glowOn)
                assertNotNull("$name(glow=$glowOn) 的注入锚点必须命中", echo)
                val e = echo!!
                assertTrue("$name 缺少 applyEcho 调用", e.contains("vec4(applyEcho("))
                assertTrue("$name 缺少 vUvEcho", e.contains(", vUvEcho)"))
                assertTrue("$name 缺少 uEchoSrc 声明", e.contains("uniform sampler2D uEchoSrc;"))
                // 声明 + 一次调用 = 2 处；多一处说明有人塞了第二次取样
                assertEquals("$name 的 applyEcho 必须恰好一处调用", 2, countOf(e, "applyEcho"))
                assertTrue("$name 的注入段必须排在 void main 之前", e.indexOf("vec3 applyEcho") < e.indexOf("void main() {"))
                assertTrue(
                    "$name 的 #extension 必须仍是首条指令",
                    e.trimStart().startsWith("#extension GL_OES_EGL_image_external : require")
                )
            }
        }
        // 具体形态：echo 包在 glow 外层（applyEcho(applyGlow(...))——拖影作用于"已发光晕"的画面）
        assertTrue(
            Shaders.echoFragment(Shaders.glowFragment(Shaders.PASS_THROUGH_FS, FrameEffect.NONE)!!, FrameEffect.NONE, true)!!
                .contains("gl_FragColor = vec4(applyEcho(applyGlow(texture2D(uFrame, vUv).rgb, vUvGlow), vUvEcho), 1.0);")
        )
        assertTrue(
            "无 glow 时直接包原表达式",
            Shaders.echoFragment(Shaders.CURVE_FS, FrameEffect.NONE, false)!!
                .contains("gl_FragColor = vec4(applyEcho(applyCurve(c), vUvEcho), 1.0);")
        )
    }

    // ------------------------------------------------------------------ echo=true：ZEBRA / PEAKING

    @Test
    fun `斑马纹只包else分支且条纹不拖影`() {
        for (glowOn in listOf(false, true)) {
            val base = Shaders.zebraFragment(curveOn = true)
            val prepared = if (glowOn) Shaders.glowFragment(base, FrameEffect.ZEBRA)!! else base
            val echo = Shaders.echoFragment(prepared, FrameEffect.ZEBRA, glowOn)
            assertNotNull("zebra(glow=$glowOn) 的注入锚点必须命中", echo)
            val e = echo!!
            val stripeLine = "gl_FragColor = vec4(texture2D(uStripe, fract(vUv * uTile)).rgb, 1.0);"
            assertTrue("条纹分支必须原样保留（不许拖影）", e.contains(stripeLine))
            assertEquals("斑马纹只许包 else 分支（声明 + 一次调用 = 2）", 2, countOf(e, "applyEcho"))
            val expectedElse = if (glowOn) {
                "gl_FragColor = vec4(applyEcho(applyGlow(c, vUvGlow), vUvEcho), 1.0);"
            } else {
                "gl_FragColor = vec4(applyEcho(c, vUvEcho), 1.0);"
            }
            assertTrue("else 分支必须被包成 $expectedElse", e.contains(expectedElse))
            // 判读基准仍是注入前的 c
            assertTrue("luma 判定必须用注入前的 c", e.contains("vec3 c = texture2D(uFrame, vUv).rgb;"))
        }
    }

    @Test
    fun `峰值只包底色且描边色不被拖影`() {
        for (glowOn in listOf(false, true)) {
            val base = Shaders.peakingFragment(curveOn = true)
            val prepared = if (glowOn) Shaders.glowFragment(base, FrameEffect.PEAKING)!! else base
            val echo = Shaders.echoFragment(prepared, FrameEffect.PEAKING, glowOn)
            assertNotNull("peaking(glow=$glowOn) 的注入锚点必须命中", echo)
            val e = echo!!
            val expected = if (glowOn) {
                "mix(applyEcho(applyGlow(c, vUvGlow), vUvEcho), uColor, paint)"
            } else {
                "mix(applyEcho(c, vUvEcho), uColor, paint)"
            }
            assertTrue("底色必须按形态包住", e.contains(expected))
            assertEquals("峰值只许一处 applyEcho 调用（声明 + 调用 = 2）", 2, countOf(e, "applyEcho"))
            assertTrue("边缘判定原样（pre-echo）", e.contains("float paint = smoothstep(uStrength, uStrength + 0.08, edge);"))
        }
    }

    // ------------------------------------------------------------------ 注入段内容

    @Test
    fun `注入段的算式与R2双模式一致`() {
        val e = Shaders.echoFragment(Shaders.PASS_THROUGH_FS, FrameEffect.NONE, false)!!
        // 保持亮度 = max(c, echo)：星轨叠加法，静态背景完全不动（R2 §四.2 模式二）
        assertTrue("保持亮度档必须是 max(c, e)", e.contains("return max(c, e);"))
        // 提亮拖尾 = c + e·gain 后 clamp（R2 §四.2 模式一；黑纹理兜底时两种模式都是恒等）
        assertTrue("提亮档必须是加法叠回并钳 1", e.contains("return min(c + e * uEchoGain, vec3(1.0));"))
        for (uniform in listOf("uEchoSrc", "uEchoMode", "uEchoGain")) {
            assertTrue("缺 uniform $uniform", e.contains(uniform))
        }
        assertEquals("注入段不许出现两次", 1, countOf(e, "vec3 applyEcho(vec3 c, vec2 uv) {"))
    }

    // ------------------------------------------------------------------ 锚点对不上 → null

    @Test
    fun `锚点对不上必须返回null而不是原串`() {
        // 效果与 base 不匹配（两个形态都没有对应锚点）
        assertNull("直通源里没有斑马纹锚点", Shaders.echoFragment(Shaders.PASS_THROUGH_FS, FrameEffect.ZEBRA, false))
        assertNull("直通源里没有峰值锚点", Shaders.echoFragment(Shaders.PASS_THROUGH_FS, FrameEffect.PEAKING, false))
        // glowOn 声明与 base 实际形态不符：锚点串对不上必须 null（这正是 glowOn 形参存在的意义）
        assertNull(
            "glowOn=false 却拿 glow 已注入的斑马源（锚串对不上）",
            Shaders.echoFragment(Shaders.glowFragment(Shaders.zebraFragment(true), FrameEffect.ZEBRA)!!, FrameEffect.ZEBRA, false)
        )
        assertNull(
            "glowOn=true 却拿未注入 glow 的峰值源（锚串对不上）",
            Shaders.echoFragment(Shaders.peakingFragment(false), FrameEffect.PEAKING, true)
        )
        // 没有 main
        assertNull("找不到 void main 时必须返回 null", Shaders.echoFragment("precision mediump float;", FrameEffect.NONE, false))
        // 终写出现两次：不许猜哪一个是终写
        val twoOuts = """
            precision mediump float;
            varying vec2 vUv;
            void main() {
                gl_FragColor = vec4(0.0, 1.0);
                gl_FragColor = vec4(1.0, 1.0);
            }
        """.trimIndent()
        assertNull("终写不唯一时必须返回 null", Shaders.echoFragment(twoOuts, FrameEffect.NONE, false))
    }
}
