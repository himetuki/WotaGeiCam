package com.wotagei.cam.core

import com.wotagei.cam.core.ColorCurve
import com.wotagei.cam.core.CurveStack
import com.wotagei.cam.core.ToneParams
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 色调四滑条（29 §4.2 F4，2026-10-28）的纯函数全测 + LUT 烘焙等价性 + HUD/持久化接线守卫。
 *
 * 判据（29 §4.5）：① apply 单调（256 点非降）与端点；② 四项全 0 = isIdentity；
 * ③ **tone=0 时 bakeBytes 与改前逐字节一致**（守卫）；④ tone 非恒等时字节表必须变化；
 * ⑤ 复合串编解码坏串回恒等；⑥ 注册清单（PillKey/锚点表/PillHost/homeZone/默认掩码）源码级钉死。
 */
class ToneParamsTest {

    // -------------------------------------------------- ① 传递函数

    @Test
    fun `恒等判据_四项全0_非0不算`() {
        assertTrue(ToneParams.IDENTITY.isIdentity)
        assertTrue(ToneParams().isIdentity)
        assertFalse(ToneParams(black = 0.01f).isIdentity)
        assertFalse(ToneParams(highlights = -0.5f).isIdentity)
    }

    @Test
    fun `apply单调_256点非降_四项取极值也不降`() {
        for (tone in listOf(
            ToneParams(0.35f, 0.35f, 1f, 1f),
            ToneParams(0.35f, 0.35f, -1f, -1f),
            ToneParams(0f, 0f, 1f, -1f),
            ToneParams(0.2f, 0f, -1f, 1f)
        )) {
            var prev = -1f
            for (i in 0..255) {
                val y = tone.apply(i / 255f)
                assertTrue("tone=$tone 第 $i 点必须非降（$prev → $y）", y >= prev - 1e-6f)
                prev = y
            }
        }
    }

    @Test
    fun `apply端点_纯电平时0到0_1到1_正项抬负项压`() {
        val flat = ToneParams.IDENTITY
        assertEquals(0f, flat.apply(0f), 1e-6f)
        assertEquals(1f, flat.apply(1f), 1e-6f)
        // 阴影抬（+）：暗部被抬起来（0 点仍被电平钳在 0，但 1/4 点变亮）
        val lift = ToneParams(shadows = 1f)
        assertTrue(lift.apply(0.25f) > 0.25f)
        // 阴影压（−）：暗部更暗
        assertTrue(ToneParams(shadows = -1f).apply(0.25f) < 0.25f)
        // 高光抬（+）：亮部更亮
        assertTrue(ToneParams(highlights = 1f).apply(0.75f) > 0.75f)
        // 黑点抬黑（电平黑场抬升）：暗端被压到 0（剪裁阴影），中灰相对变暗——电平语义
        val liftedBlack = ToneParams(black = 0.2f)
        assertEquals(0f, liftedBlack.apply(0.1f), 1e-6f)
        assertEquals((0.5f - 0.2f) / 0.8f, liftedBlack.apply(0.5f), 1e-3f)
        // 白点压白（电平白场压低）：亮端被钳到 1（剪裁高光），中灰相对变亮
        val pressedWhite = ToneParams(white = 0.2f)
        assertEquals(1f, pressedWhite.apply(0.9f), 1e-6f)
        assertEquals(0.5f / 0.8f, pressedWhite.apply(0.5f), 1e-3f)
    }

    @Test
    fun `sanitize_越界与NaN收进产品域`() {
        val s = ToneParams(black = 0.9f, white = -2f, shadows = 3f, highlights = Float.NaN).sanitize()
        assertEquals(ToneParams.BLACK_MAX, s.black, 1e-6f)
        assertEquals(-ToneParams.WHITE_MAX, s.white, 1e-6f)
        assertEquals(1f, s.shadows, 1e-6f)
        assertEquals(0f, s.highlights, 1e-6f)
        // apply 对未 sanitize 的输入同样安全（内部再收一遍）
        assertTrue(ToneParams(black = 9f).apply(0.5f).isFinite())
    }

    // -------------------------------------------------- ② 烘焙等价性（29 §4.5-②）

    @Test
    fun `tone恒等时bakeBytes与改前逐字节一致`() {
        val stack = CurveStack.decode("0:0;0.5:0.7;1:1;0:0;0:0;0.5:0.6;1:1;0:0;0:0;0.5:0.55;1:1;0:0;0:0;0.5:0.65;1:1;0:0")
        val withDefault = stack.bakeBytes()
        val withIdentity = stack.bakeBytes(ToneParams.IDENTITY)
        assertTrue("默认参数与显式恒等必须同表", withDefault.contentEquals(withIdentity))
        // 与「改前口径」同源：bake(不带 tone) 的量化输出
        val legacy = ByteArray(stack.bake().size) { i -> ((stack.bake()[i] * 255f) + 0.5f).toInt().coerceIn(0, 255).toByte() }
        assertTrue("tone=0 必须与改前逐字节一致", withDefault.contentEquals(legacy))
    }

    @Test
    fun `tone非恒等时字节表必须变化且方向正确`() {
        val stack = CurveStack.IDENTITY
        val base = stack.bakeBytes()
        val crushed = stack.bakeBytes(ToneParams(black = 0.2f))
        assertFalse("抬黑（电平）必须改变字节表", crushed.contentEquals(base))
        // 电平黑场抬升：x=0.1 → tone 后 <0 → 剪到 0；x=0.298 → (0.298-0.2)/0.8=0.1225（量化 ≈31）
        val at01 = 26 * 3 // x≈0.1 的 R 通道
        val at03 = 76 * 3 // x≈0.3
        assertEquals("黑点以下剪到 0", 0, crushed[at01].toInt() and 0xFF)
        assertEquals(
            "黑点以上的暗段按电平线性重映射",
            31, crushed[at03].toInt() and 0xFF
        )
        // 恒等 stack + 恒等 tone ⇒ 恒等表（y=x）
        val identity = CurveStack.IDENTITY.bakeBytes()
        for (i in 0 until 256) {
            assertEquals("y=x 基准：第 $i 点", i.toByte(), identity[i * 3])
        }
    }

    @Test
    fun `色调作用在曲线之前_先tone后master后通道`() {
        // 同一条抬亮曲线（master 线性 ×2）：tone 抬黑后暗部先被抬、再被曲线放大
        val stack = CurveStack.decode("0:0;1:1;;") // 需要合法四段；改用直接构造
        val s = CurveStack(
            master = ColorCurve(listOf(ColorCurve.Point(0f, 0f), ColorCurve.Point(1f, 1f))),
            red = ColorCurve(listOf(ColorCurve.Point(0f, 0f), ColorCurve.Point(1f, 2f))),
            green = ColorCurve.IDENTITY,
            blue = ColorCurve.IDENTITY
        )
        // x=0.2、black=0.1：tone → (0.2-0.1)/0.9 ≈ 0.111，red 通道 ×2 → ≈0.222
        val y = s.evaluate(ColorCurve.CHANNEL_RED, 0.2f, ToneParams(black = 0.1f))
        assertEquals((0.2f - 0.1f) / 0.9f * 2f, y, 1e-3f)
        // 恒等 tone 时就是改前语义：×2
        assertEquals(0.4f, s.evaluate(ColorCurve.CHANNEL_RED, 0.2f), 1e-6f)
    }

    // -------------------------------------------------- ③ 编解码

    @Test
    fun `复合串往返_坏串回恒等`() {
        val p = ToneParams(black = 0.1f, white = 0.2f, shadows = -0.5f, highlights = 0.8f)
        assertEquals(p, ToneParams.decode(ToneParams.encode(p)))
        assertEquals(ToneParams.IDENTITY, ToneParams.decode(null))
        assertEquals(ToneParams.IDENTITY, ToneParams.decode(""))
        assertEquals(ToneParams.IDENTITY, ToneParams.decode("0.1 0.2"))
        assertEquals(ToneParams.IDENTITY, ToneParams.decode("a b c d"))
        assertEquals(ToneParams.IDENTITY, ToneParams.decode("0.1 0.2 0.3 0.4 0.5"))
        // 恒等串解回恒等（同一实例语义）
        assertEquals(ToneParams.IDENTITY, ToneParams.decode(ToneParams.encode(ToneParams.IDENTITY)))
        // 越界值经 sanitize 收域
        val over = ToneParams.decode("0.9 0.2 -3 5")
        assertEquals(ToneParams.BLACK_MAX, over.black, 1e-6f)
    }

    // -------------------------------------------------- ④ 接线守卫（29 §4.4-3 注册清单）

    private fun mainSource(rel: String): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel))

    @Test
    fun `注册清单_胶囊键锚点宿主与白名单都在`() {
        val pills = mainSource("ui/CameraPills.kt")
        assertTrue("PillKey 必须含 TONE", pills.contains("DENOISE, GLOW, TONE }"))
        assertTrue("录制中可调白名单必须含 TONE（按 Glow 先例）", pills.contains("PillKey.GLOW, PillKey.TONE"))
        assertTrue("锚点写入方必须登记 TONE", pills.contains("PillKey.TONE to "))
        assertTrue("PillHost 必须有 TONE 分支", pills.contains("PillKey.TONE -> TonePill("))
        // 面板必须四条滑杆 + 进成片说明 + DIRECT 提示 + 归零
        val panel = KotlinSourceScan.bodyOf(pills, "TonePill")
        for (needle in listOf(
            "cam_tone_black", "cam_tone_white", "cam_tone_shadows", "cam_tone_highlights",
            "cam_tone_note", "cam_tone_direct", "cam_tone_reset"
        )) {
            assertTrue("TONE 面板缺 $needle", panel.contains(needle))
        }
        // 真机取证回归（WIKO 12:05，r03）：四条滑杆曾传 ladder = emptyList() ⇒ PillSlider 判
        // 「非 null 但不足 2 点」整根禁用（三根 SeekBar enabled=false）。必须传真梯：
        // 电平走 toneLevelLadder（0.01 网格）、阴影/高光走 percentLadder（±100 步 5）
        assertFalse("TONE 滑杆不许传空梯（会被判成假连续而整根禁用）", panel.contains("ladder = emptyList()"))
        assertEquals(
            "电平两根必须用 toneLevelLadder",
            2, Regex("toneLevelLadder\\(").findAll(panel).count()
        )
        assertEquals(
            "阴影/高光必须用 percentLadder(-100, 100, 5)",
            2, Regex("percentLadder\\(-100, 100, 5\\)").findAll(panel).count()
        )
        // 尺要能红：摘掉白名单里的 TONE 后翻红
        val mutant = pills.replace("PillKey.MONITOR, PillKey.GLOW, PillKey.TONE", "PillKey.MONITOR, PillKey.GLOW")
        assertFalse(mutant.contains("PillKey.TONE)"))
    }

    @Test
    fun `homeZone与默认序_左Dock第6组_默认掩码不含TONE`() {
        val layout = mainSource("ui/HudLayout.kt")
        assertTrue("HudEntry.homeZone 必须把 TONE 归左 Dock", layout.contains("CamPill.TONE -> HudZone.LEFT"))
        assertTrue(
            "左 Dock 默认序必须以 TONE 收尾（GLOW 之后）",
            layout.contains("HudEntry.of(CamPill.TONE)")
        )
        val constants = mainSource("core/Constants.kt")
        assertTrue("DEFAULT_MASK 必须减去 TONE 位（默认关）", constants.contains("and TONE.bit.inv()"))
        assertTrue("MASK_SCHEMA 必须 +1 到 3", constants.contains("const val MASK_SCHEMA = 3"))
        // TONE 不许登记进自动补位表（val 表：整行原文钉住）
        assertTrue(
            "自动补位表必须只有降噪与 Glow",
            constants.contains("mapOf(1 to DENOISE.bit, 2 to GLOW.bit)")
        )
    }

    @Test
    fun `引擎与总线_setCurve收口跳过判据`() {
        val gl = mainSource("camera/GlRenderEngine.kt")
        // 签名在 bodyOf 之外（body 只取花括号体内），签名判据钉整文件
        assertTrue(
            "setCurve 必须接收 tone（默认恒等，旧调用点零改动）",
            gl.contains("tone: com.wotagei.cam.core.ToneParams = com.wotagei.cam.core.ToneParams.IDENTITY")
        )
        // 2026-10-28：烘焙收口到 bakeCurveLut（曲线/色调/画面算法白平衡共用一张 LUT），
        // 判据与烘焙都挪到那里；setCurve 只记输入并触发重烘
        val body = KotlinSourceScan.bodyOf(gl, "bakeCurveLut")
        assertTrue(
            "跳过判据必须是「曲线恒等 或 tone 非恒等 或 白平衡非恒等」才烘焙（只看一侧会静默丢色调/白平衡）",
            body.contains("!it.isPassthrough || !tone.isIdentity || wb != null")
        )
        assertTrue("烘焙必须带 tone", body.contains("bakeBytes(tone"))
        val setter = KotlinSourceScan.bodyOf(gl, "setCurve")
        assertTrue("setCurve 必须记住 tone 供重烘", setter.contains("lastCurveTone = tone"))
        assertTrue("setCurve 必须触发重烘", setter.contains("bakeCurveLut()"))
        // 总线必须有 tone 流；录制页长键必须把它下发
        val params = mainSource("core/WotaParams.kt")
        assertTrue(params.contains("val tone = MutableStateFlow(ToneParams.IDENTITY)"))
        val screen = mainSource("ui/CameraScreen.kt")
        assertTrue("录制页必须把 tone 传给 setCurve", screen.contains("gl.setCurve(curveStack, toneParams)"))
    }
}
