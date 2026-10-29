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
 * Glow 的**片元注入账**（docs/plan/24 R3 §1.1 / §1.3，25 §11.1）——JVM 里唯一能盯住"合成顺序"
 * 的地方。这条链在真机上只有一个观感结论（"有光晕/没光晕"），而下面每一个错法都同样"看起来生效了"：
 *
 * - 关闭时把注入段留在源码里 ⇒ 每条 program 都白背 7 颗 uniform + 一条纹理取样（零代价破产）；
 * - 曲线/降噪的终写没被包住 ⇒ 光晕只作用在"没调色"的像素上，或者干脆不出现；
 * - 斑马纹的**条纹分支**被包住 ⇒ 条纹本身发光、判读阈值读错（红黑斜纹变成渐变红光）；
 * - 峰值的**描边色**被 Glow 染色 ⇒ 五档描边色互相串味，判读失真；
 * - 判读基准从 pre-Glow 的 `c` 换成 post-Glow ⇒ 开光晕时阈值口径漂移。
 *
 * 所以这里按源码文本断言：**注入点是哪一行**、**哪一行没被碰**、**锚点对不上必须返回 null**。
 * 真正的 GL 编译与像素观感归真机（T2/T7）。
 */
class GlowShaderTest {

    private fun countOf(source: String, needle: String): Int = source.split(needle).size - 1

    /** 所有 base 源（glow 关着时调用的就是这些，一个字节都不许变） */
    private fun allBaseSources(): Map<String, String> = mapOf(
        "PASS_THROUGH_FS" to Shaders.PASS_THROUGH_FS,
        "PASS_THROUGH_2D_FS" to Shaders.PASS_THROUGH_2D_FS,
        "CURVE_FS" to Shaders.CURVE_FS,
        "BILATERAL_FS" to Shaders.BILATERAL_FS,
        "BILATERAL_CURVE_FS" to Shaders.BILATERAL_CURVE_FS,
        "BILATERAL_LITE_FS" to Shaders.BILATERAL_LITE_FS,
        "BILATERAL_LITE_CURVE_FS" to Shaders.BILATERAL_LITE_CURVE_FS,
        "zebra(false)" to Shaders.zebraFragment(false),
        "zebra(true)" to Shaders.zebraFragment(true),
        "peaking(false)" to Shaders.peakingFragment(false),
        "peaking(true)" to Shaders.peakingFragment(true)
    )

    /** scene 支路（effect = NONE）的全部 base：直通 / 曲线 / 双边四档 */
    private fun sceneSources(): Map<String, String> = mapOf(
        "PASS_THROUGH_FS" to Shaders.PASS_THROUGH_FS,
        "CURVE_FS" to Shaders.CURVE_FS,
        "BILATERAL_FS" to Shaders.BILATERAL_FS,
        "BILATERAL_CURVE_FS" to Shaders.BILATERAL_CURVE_FS,
        "BILATERAL_LITE_FS" to Shaders.BILATERAL_LITE_FS,
        "BILATERAL_LITE_CURVE_FS" to Shaders.BILATERAL_LITE_CURVE_FS
    )

    private val glowTokens = listOf("applyGlow", "vUvGlow", "uGlowSrc", "uGlowGain", "uGlowAdd", "uGlowFreq", "uGlowBlend", "uGlowTint", "glowHash")

    // ------------------------------------------------------------------ glow=false：源码逐字不变

    @Test
    fun `所有base源本身不含任何Glow痕迹`() {
        // 关着时调用方根本不调 glowFragment ⇒ "逐字不变"等价于"base 常量里没有注入段"
        for ((name, src) in allBaseSources()) {
            for (token in glowTokens) {
                assertEquals("$name 里出现了 $token —— glow 关着时源码必须逐字不变（零代价）", 0, countOf(src, token))
            }
        }
    }

    @Test
    fun `顶点源分两版且关闭版没有vUvGlow`() {
        assertEquals("TEXTURE_VS 不许有 Glow 痕迹", 0, countOf(Shaders.TEXTURE_VS, "vUvGlow"))
        assertEquals("TEXTURE_VS 不许有 Glow 痕迹", 0, countOf(Shaders.TEXTURE_VS, "applyGlow"))
        // Glow 版：多一条 varying + 一次不乘矩阵的赋值
        assertTrue(Shaders.TEXTURE_VS_GLOW.contains("varying vec2 vUvGlow;"))
        assertTrue(Shaders.TEXTURE_VS_GLOW.contains("vUvGlow = aTexCoord;"))
        assertFalse(
            "vUvGlow 不许乘矩阵（乘了就是光晕相对画面差一次旋转/镜像）",
            Shaders.TEXTURE_VS_GLOW.contains("vUvGlow = (uTexMatrix")
        )
        // 两版必须共存同一套 vUv 口径（否则两路画面自己先错位）
        for (src in listOf(Shaders.TEXTURE_VS, Shaders.TEXTURE_VS_GLOW)) {
            assertTrue(src.contains("vUv = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;"))
            assertTrue(src.contains("gl_Position = aPosition;"))
        }
    }

    // ------------------------------------------------------------------ 拷贝趟（亮部提取）

    @Test
    fun `拷贝着色器做亮部提取而不是整帧模糊`() {
        // Glow 与霜的本质区别就在这一趟：霜要整幅模糊，Glow 只让亮部发光。
        // threshold/soft 是 B7 的两条滑杆——它们必须真的出现在拷贝着色器里，否则滑杆是死的。
        val src = Shaders.GLOW_COPY_FS
        assertTrue(
            "首条必须仍是 #extension（ES 要求扩展声明在最前）",
            src.trimStart().startsWith("#extension GL_OES_EGL_image_external : require")
        )
        assertTrue("只用 samplerExternalOES", src.contains("uniform samplerExternalOES uFrame;"))
        assertFalse(
            "拷贝趟不许出现 sampler2D（与 OES 混进同一 program 会直接链接失败）",
            src.contains("sampler2D")
        )
        assertTrue("精度取 mediump（与其它 OES 单趟一致）", src.contains("precision mediump float;"))
        assertTrue("必须做阈值切分", src.contains("smoothstep("))
        assertTrue("阈值 uniform", src.contains("uniform float uGlowThreshold;"))
        assertTrue("软边 uniform", src.contains("uniform float uGlowSoft;"))
        assertTrue(
            "软边必须朝 1.0 收敛（阈值 100% = 只给最亮像素加光晕，2026-10-29 用户口径）",
            src.contains("min(uGlowSoft, max(1.0 - uGlowThreshold, 0.0) + 0.0001)")
        )
        assertTrue(
            "过渡带下界 = threshold - soft（压缩后的软边）",
            src.contains("uGlowThreshold - soft,")
        )
        assertTrue(
            "过渡带上界必须 max(...) 兜底：smoothstep 要求 edge0 < edge1",
            src.contains("max(uGlowThreshold + soft, lo + 0.0001)")
        )
        assertTrue("亮度按 BT.601 权重", src.contains("dot(c, vec3(0.299, 0.587, 0.114))"))
        assertTrue(
            "亮部颜色必须按模式二选一：仅色盘颜色 / 像素自身颜色",
            src.contains("mix(c, uGlowTint, uGlowColorOnly)")
        )
        assertTrue("亮部保留、暗部压零：颜色 × t", src.contains("gl_FragColor = vec4(pick * t, 1.0);"))
        assertTrue("强制不透明", src.contains(", 1.0);"))
        assertFalse("Glow 的拷贝趟不许退化回霜的纯拷贝", src.contains("FROST"))
    }

    // ------------------------------------------------------------------ glow=true：scene 支路

    @Test
    fun `scene支路把终写整个包进applyGlow`() {
        for ((name, src) in sceneSources()) {
            val glow = Shaders.glowFragment(src, FrameEffect.NONE)
            assertNotNull("$name 的 scene 注入锚点必须命中", glow)
            val g = glow!!
            assertTrue("$name 缺少 applyGlow 调用", g.contains("vec4(applyGlow("))
            assertTrue("$name 缺少 vUvGlow", g.contains(", vUvGlow)"))
            assertTrue("$name 缺少 uGlowSrc 声明", g.contains("uniform sampler2D uGlowSrc;"))
            assertTrue("$name 缺少 applyGlow 函数", g.contains("vec3 applyGlow(vec3 c, vec2 uv) {"))
            // 声明 + 一次调用 = 2 处；多一处说明有人塞了第二次取样
            assertEquals("$name 的 applyGlow 必须恰好一处调用（另加一处声明）", 2, countOf(g, "applyGlow"))
            // 注入段必须落在 void main 之前（#extension 仍是首条指令、函数先声明后使用）
            assertTrue("$name 的注入段必须排在 void main 之前", g.indexOf("vec3 applyGlow") < g.indexOf("void main() {"))
            assertTrue(
                "$name 的 #extension 必须仍是首条指令",
                g.trimStart().startsWith("#extension GL_OES_EGL_image_external : require")
            )
        }
        // 两条具体形态（防止"包了别的东西"）
        assertTrue(
            Shaders.glowFragment(Shaders.PASS_THROUGH_FS, FrameEffect.NONE)!!
                .contains("gl_FragColor = vec4(applyGlow(texture2D(uFrame, vUv).rgb, vUvGlow), 1.0);")
        )
        assertTrue(
            Shaders.glowFragment(Shaders.CURVE_FS, FrameEffect.NONE)!!
                .contains("gl_FragColor = vec4(applyGlow(applyCurve(c), vUvGlow), 1.0);")
        )
        assertTrue(
            "降噪+曲线档：曲线是最后一步、必须整体被 Glow 包住（合成顺序 降噪→曲线→Glow）",
            Shaders.glowFragment(Shaders.BILATERAL_CURVE_FS, FrameEffect.NONE)!!
                .contains("gl_FragColor = vec4(applyGlow(applyCurve(bilateral(vUv)), vUvGlow), 1.0);")
        )
    }

    // ------------------------------------------------------------------ glow=true：ZEBRA

    @Test
    fun `斑马纹只包else分支且条纹不发光`() {
        val base = Shaders.zebraFragment(curveOn = true)
        val glow = Shaders.glowFragment(base, FrameEffect.ZEBRA)
        assertNotNull("zebra 的注入锚点必须命中", glow)
        val g = glow!!
        // 条纹分支逐字保留（它一旦被包住，红黑斜纹就变成会发光的红带）
        val stripeLine = "gl_FragColor = vec4(texture2D(uStripe, fract(vUv * uTile)).rgb, 1.0);"
        assertTrue("条纹分支必须原样保留（不许发光）", g.contains(stripeLine))
        assertTrue("else 分支必须被 Glow 包住", g.contains("gl_FragColor = vec4(applyGlow(c, vUvGlow), 1.0);"))
        // 恰好两处 applyGlow：一处声明、一处调用（若条纹也被包，会出现第三处）
        assertEquals("斑马纹只许包 else 分支（声明 + 一次调用 = 2）", 2, countOf(g, "applyGlow"))
        // 判读基准仍是 pre-Glow 的 c：luma 在注入之前算
        val lumaAt = g.indexOf("float luma = lumaOf(c);")
        assertTrue("luma 判定必须在场", lumaAt >= 0)
        assertTrue("luma 判定必须用 pre-Glow 的 c", g.contains("vec3 c = texture2D(uFrame, vUv).rgb;"))
        // 条纹分支里不许出现 applyGlow：取条纹那一行做局部断言
        val stripeAt = g.indexOf(stripeLine)
        val branch = g.substring(g.lastIndexOf("if (luma > uThreshold) {", stripeAt), g.indexOf("} else {", stripeAt))
        assertFalse("条纹分支里出现了 applyGlow", branch.contains("applyGlow"))
        // 曲线关/开两版都能注入
        assertNotNull(Shaders.glowFragment(Shaders.zebraFragment(false), FrameEffect.ZEBRA))
    }

    // ------------------------------------------------------------------ glow=true：PEAKING

    @Test
    fun `峰值只包底色且描边色不被染色`() {
        for (curveOn in listOf(false, true)) {
            val glow = Shaders.glowFragment(Shaders.peakingFragment(curveOn), FrameEffect.PEAKING)
            assertNotNull("peaking(curve=$curveOn) 的注入锚点必须命中", glow)
            val g = glow!!
            assertTrue("底色调色必须被 Glow 包住", g.contains("mix(applyGlow(c, vUvGlow), uColor, paint)"))
            assertFalse("旧的 mix(c, uColor, paint) 不许残留", g.contains("mix(c, uColor, paint)"))
            assertTrue("描边色 uColor 原样参与 mix", g.contains(", uColor, paint)"))
            assertEquals("峰值只许一处 applyGlow 调用（声明 + 调用 = 2）", 2, countOf(g, "applyGlow"))
            // 边缘判定用 pre-Glow 的 c（四个邻域取样原样）
            assertTrue(g.contains("float left = lumaOf(texture2D(uFrame, vUv - vec2(uTexel.x, 0.0)).rgb);"))
            assertTrue(g.contains("float paint = smoothstep(uStrength, uStrength + 0.08, edge);"))
        }
    }

    // ------------------------------------------------------------------ 注入段内容

    @Test
    fun `注入段的算式与24号计划一致`() {
        val g = Shaders.glowFragment(Shaders.PASS_THROUGH_FS, FrameEffect.NONE)!!
        assertTrue(
            "叠加式（不是 mix(c, glow, blend)）：在 c 上加光晕",
            g.contains("return c + glow * uGlowTint * (uGlowGain + uGlowAdd) * (uGlowBlend * n);")
        )
        // 2026-10-28 用户口径：**同色光晕**——不许再出现色带/hue mix（那是"给光棒换色"）
        assertFalse("光晕不许做色带 mix（用户：要光棒自身颜色的光晕）", g.contains("mix(uGlowColorA"))
        assertFalse(g.contains("uGlowColorA"))
        assertFalse(g.contains("uGlowColorB"))
        assertTrue(
            "频率 0 时噪声退化成 1（不做无谓的 hash）",
            g.contains("float n = mix(1.0, glowHash(uv * uGlowFreq), step(0.0001, uGlowFreq));")
        )
        for (uniform in listOf("uGlowGain", "uGlowAdd", "uGlowFreq", "uGlowBlend", "uGlowTint", "uGlowSrc")) {
            assertTrue("缺 uniform $uniform", g.contains(uniform))
        }
        // 声明段只出现一次（重复声明在 GLSL 里是编译错误）
        assertEquals("注入段不许出现两次", 1, countOf(g, "vec3 applyGlow(vec3 c, vec2 uv) {"))
        assertEquals("glowHash 只声明一次", 1, countOf(g, "float glowHash(vec2 p) {"))
    }

    // ------------------------------------------------------------------ 锚点对不上 → null

    @Test
    fun `锚点对不上必须返回null而不是原串`() {
        // 效果与 base 不匹配
        assertNull("直通源里没有斑马纹锚点", Shaders.glowFragment(Shaders.PASS_THROUGH_FS, FrameEffect.ZEBRA))
        assertNull("直通源里没有峰值锚点", Shaders.glowFragment(Shaders.PASS_THROUGH_FS, FrameEffect.PEAKING))
        assertNull("斑马源里没有峰值锚点", Shaders.glowFragment(Shaders.zebraFragment(false), FrameEffect.PEAKING))
        assertNull("峰值源里没有斑马锚点", Shaders.glowFragment(Shaders.peakingFragment(false), FrameEffect.ZEBRA))
        // 没有 main
        assertNull("找不到 void main 时必须返回 null", Shaders.glowFragment("precision mediump float;", FrameEffect.NONE))
        // scene 终写出现两次（未来有人加了第二个输出口）：不许猜哪一个是终写
        val twoOuts = """
            precision mediump float;
            varying vec2 vUv;
            void main() {
                gl_FragColor = vec4(0.0, 1.0);
                gl_FragColor = vec4(1.0, 1.0);
            }
        """.trimIndent()
        assertNull("终写不唯一时必须返回 null", Shaders.glowFragment(twoOuts, FrameEffect.NONE))
        // 锚点缺失但 base 看起来像 scene：同样 null
        assertNull(
            "没有 gl_FragColor 终写时必须返回 null",
            Shaders.glowFragment("precision mediump float;\nvoid main() {\n    float x = 1.0;\n}", FrameEffect.NONE)
        )
    }
}
