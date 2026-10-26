package com.wotagei.cam

import com.wotagei.cam.core.AudioDenoise
import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.VideoDenoise
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.audioDenoiseNeedsCodec
import com.wotagei.cam.core.effectiveVideoDenoise
import com.wotagei.cam.core.videoDenoiseActive
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.ui.HudEntry
import com.wotagei.cam.ui.HudLayoutTable
import com.wotagei.cam.ui.HudZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 降噪（2026-10-26 用户需求：音频降噪 + 画面降噪，一枚胶囊、默认关、默认落底栏 Dock 左侧）的接线守卫。
 *
 * ## 为什么要有这一份
 * 这轮功能横跨五段：参数总线 → 相机请求/GL program → 录制引擎选型 → HUD 渲染/面板 → 持久化。
 * 每一段都能"编译过、单测绿、真机上什么都不发生"：
 * - 总线加了两个 flow，但没人把它下发给相机/GL（画面降噪静默失效）；
 * - 录制的音频降噪进了 [com.wotagei.cam.record.RecordProfile]，但环境音仍走 MediaRecorder
 *   （没有挂载点，NoiseSuppressor 永远挂不上）；
 * - 底栏那颗胶囊进了位置表却没进渲染槽（用户看不到、点不到——BOTTOM 那条带只渲染锁死的三格）。
 * 所以本文件把这三类"删掉中间一段"的错都钉成可执行的判据。
 *
 * ## 两层
 * ① **纯逻辑**：档位取值/退回规则、默认关（坏串也回关）、底栏默认位；
 * ② **源码接线**：判据写成吃 `String` 的纯函数，真身与合成突变体同吃一把尺（每条尺配"尺子自己能红"）。
 */
class DenoiseWiringGuardTest {

    private fun params() = WotaParams(CoroutineScope(Dispatchers.Unconfined))

    // ------------------------------------------------------------------ ① 纯逻辑

    @Test
    fun `两项降噪出厂都是关且坏串也回关`() {
        val p = params()
        assertEquals("音频降噪出厂必须关（产品口径）", AudioDenoise.OFF, p.audioDenoise.value)
        assertEquals("画面降噪出厂必须关（产品口径）", VideoDenoise.OFF, p.videoDenoise.value)
        // 落盘串是 key 字符串：不认识的值 / 缺键一律回关——"改了声音/像素"的开关不许猜
        assertEquals(AudioDenoise.OFF, AudioDenoise.fromKey(null))
        assertEquals(AudioDenoise.OFF, AudioDenoise.fromKey(""))
        assertEquals(AudioDenoise.OFF, AudioDenoise.fromKey("no_such_mode"))
        assertEquals(VideoDenoise.OFF, VideoDenoise.fromKey(null))
        assertEquals(VideoDenoise.OFF, VideoDenoise.fromKey("gpu_bilateral_v2"))
        // 已知 key 必须读得回来（否则"存进去读不出"= 每次进页都回关）
        for (m in AudioDenoise.ALL) assertEquals(m, AudioDenoise.fromKey(m.key))
        for (m in VideoDenoise.ALL) assertEquals(m, VideoDenoise.fromKey(m.key))
    }

    @Test
    fun `画面降噪在直连渲染下退回HAL而不是静默失效`() {
        // GPU 双边只在 GPU 渲染下成立；DIRECT 时不进 GL 管线 ⇒ 实际档退回 HAL 快速
        assertEquals(VideoDenoise.GPU_BILATERAL, effectiveVideoDenoise(VideoDenoise.GPU_BILATERAL, true))
        assertEquals(VideoDenoise.HAL_FAST, effectiveVideoDenoise(VideoDenoise.GPU_BILATERAL, false))
        // 其余档与渲染模式无关（HAL 档由相机请求下发，与 GL 无关）
        for (m in listOf(VideoDenoise.OFF, VideoDenoise.HAL_FAST, VideoDenoise.HAL_HIGH_QUALITY)) {
            assertEquals(m, effectiveVideoDenoise(m, true))
            assertEquals(m, effectiveVideoDenoise(m, false))
        }
        // "有没有改变出图"的判据：OFF 恒 false；GPU 双边退回 HAL 后仍是"有降噪"（不是装了个空开关）
        assertEquals(false, videoDenoiseActive(VideoDenoise.OFF, true))
        assertEquals(false, videoDenoiseActive(VideoDenoise.OFF, false))
        assertEquals(true, videoDenoiseActive(VideoDenoise.HAL_FAST, false))
        assertEquals(true, videoDenoiseActive(VideoDenoise.GPU_BILATERAL, true))
        assertEquals(
            "退回 HAL 后仍算有降噪——否则那颗胶囊的'生效中'标记会跟着渲染模式乱闪",
            true, videoDenoiseActive(VideoDenoise.GPU_BILATERAL, false)
        )
    }

    @Test
    fun `音频降噪非关时必须切到Codec引擎`() {
        // NoiseSuppressor 只能挂在自采 AudioRecord 上；MediaRecorder 没有挂载点 ⇒ 选型必须跟着走
        assertEquals(false, audioDenoiseNeedsCodec(AudioDenoise.OFF))
        assertEquals(true, audioDenoiseNeedsCodec(AudioDenoise.SYSTEM))
    }

    @Test
    fun `降噪胶囊默认落底栏最左且属于显隐列表`() {
        val denoise = HudEntry.of(CamPill.DENOISE)
        assertTrue("降噪必须在胶囊显隐列表（CamPill.ALL 就是那张表）", CamPill.DENOISE in CamPill.ALL)
        assertEquals("归属容器 = 底栏", HudZone.BOTTOM, CamPill.DENOISE.homeZoneOf())
        assertEquals(
            "底栏默认序必须【降噪 → 镜头】：用户在 Dock 左侧（其 ordinal 序在镜头之后，故顺序是显式写死的）",
            listOf(denoise, HudEntry.of(CamPill.LENS)),
            HudLayoutTable.default().orderOf(HudZone.BOTTOM)
        )
        // 位掩码：新位必须是**新加的一位**，不能挤占既有位（旧配置的 hud_pills 靠位序读回来）
        assertEquals(8192, CamPill.DENOISE.bit)
        assertTrue(CamPill.DENOISE.bit > CamPill.STORAGE.bit)
    }

    @Test
    fun `隐藏降噪不回默认位置`() {
        // 隐藏不改表（与其余条目同一条纪律）：再打开还在底栏最左
        val t = HudLayoutTable.default()
        val visible = HudEntry.ALL.toSet() - HudEntry.of(CamPill.DENOISE)
        assertTrue(
            "隐藏后不许出现在可见序列里",
            HudEntry.of(CamPill.DENOISE) !in t.visibleOrderOf(HudZone.BOTTOM, visible)
        )
        assertEquals(
            "重新打开必须回到底栏最左（表没被隐藏动作改过）",
            HudEntry.of(CamPill.DENOISE),
            t.visibleOrderOf(HudZone.BOTTOM, HudEntry.ALL.toSet()).first()
        )
    }

    // ------------------------------------------------------------------ ② 源码接线

    /**
     * 两页底栏容器的渲染槽检查。判据吃 `codeOnly` 之后的源码（注释与字符串已被遮蔽）——
     * 于是"把渲染代码注释掉"这种最像"做了"的假实现当场红。
     */
    private fun bandRenderIssues(sources: List<Pair<String, String>>): List<String> {
        val bad = ArrayList<String>()
        for ((label, src) in sources) {
            if (KotlinSourceScan.occurrences(src, "bandExtra =").isEmpty()) {
                bad.add("$label 的 HudZoneBox(BOTTOM) 没挂 bandExtra——降噪那颗在底栏就渲染不出来")
            }
            if (KotlinSourceScan.occurrences(src, "HudBottomBandEntry(").isEmpty()) {
                bad.add("$label 没走共用渲染入口 HudBottomBandEntry（档位/ghost/锚点会各写一份）")
            }
        }
        return bad
    }

    private fun hudSources() = listOf(
        "ui/CameraScreen.kt" to codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt")),
        "ui/HudLayoutEditor.kt" to codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayoutEditor.kt"))
    )

    @Test
    fun `两页底栏都挂了降噪那颗的渲染槽`() {
        val issues = bandRenderIssues(hudSources())
        assertEquals("底栏伴随条目的渲染问题：\n${issues.joinToString("\n")}", emptyList<String>(), issues)
    }

    @Test
    fun `尺子自己能红_摘掉渲染槽必报红`() {
        val clean = hudSources()
        assertEquals("真身必须绿", emptyList<String>(), bandRenderIssues(clean))
        val mutated = clean.map { (label, src) -> label to src.replace("bandExtra =", "bandExtraX =") }
        assertTrue("摘掉渲染槽的突变必须被守卫抓到", bandRenderIssues(mutated).isNotEmpty())
    }

    /**
     * 录制页的三条下发链：画面降噪 → GL、音频降噪 → RecordProfile、点击 → 就近面板。
     * 少任何一条都是"UI 上能选、实际什么都不发生"。
     */
    private fun cameraIssues(screen: String): List<String> {
        val bad = ArrayList<String>()
        if (KotlinSourceScan.occurrences(screen, "gl.setVideoDenoise").isEmpty()) {
            bad.add("CameraScreen 没把画面降噪下发给 GL 引擎（GPU 双边档等于没接）")
        }
        if (!screen.contains("effectiveVideoDenoise(videoDenoise")) {
            bad.add("下发前必须过 effectiveVideoDenoise —— DIRECT 退回 HAL 的唯一裁决点，各写一份就会分叉")
        }
        if (KotlinSourceScan.occurrences(screen, "audioDenoise = params.audioDenoise.value").isEmpty()) {
            bad.add("RecordProfile 没带上音频降噪档（录制侧永远拿不到用户选的档）")
        }
        for (needle in listOf(
            "onDenoiseClick = { pop = PillKey.DENOISE }",
            "denoiseLabel = stringResource(R.string.cam_pill_denoise)",
            "halNoiseReductionSupported ="
        )) {
            if (KotlinSourceScan.occurrences(screen, needle).isEmpty()) {
                bad.add("CameraScreen 缺少这一处接线：$needle")
            }
        }
        return bad
    }

    @Test
    fun `录制页三条降噪下发链都在`() {
        val screen = codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt"))
        val issues = cameraIssues(screen)
        assertEquals("录制页降噪下发链问题：\n${issues.joinToString("\n")}", emptyList<String>(), issues)
    }

    @Test
    fun `尺子自己能红_摘掉GL下发必报红`() {
        val screen = codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt"))
        assertEquals("真身必须绿", emptyList<String>(), cameraIssues(screen))
        assertTrue(
            "抹掉 GL 下发的突变必须被守卫抓到",
            cameraIssues(screen.replace("gl.setVideoDenoise", "gl.setVideoDenoiseX")).isNotEmpty()
        )
    }

    @Test
    fun `相机请求只在HAL声明过的模式里下发且关档不写键`() {
        val ra = codeOnly(KotlinSourceScan.mainSourceText("camera/RequestApplier.kt"))
        assertTrue(
            "apply() 必须真的调画面降噪那一步",
            KotlinSourceScan.occurrences(ra, "applyNoiseReduction(builder").isNotEmpty()
        )
        val body = KotlinSourceScan.bodyOf(ra, "applyNoiseReduction")
        assertTrue(
            "未声明的能力不许下发（发一个非法请求轻则被忽略重则报错）",
            body.contains("noiseReductionModes")
        )
        assertTrue(body.contains("NOISE_REDUCTION_MODE_FAST"))
        assertTrue(body.contains("NOISE_REDUCTION_MODE_HIGH_QUALITY"))
        // OFF 那一支必须**早退**（不写键 = 不动 HAL 默认；写 OFF 会把厂商开着的降噪强关，属负优化）
        assertTrue("OFF 档必须 return 而不是写键", body.contains("else -> return"))
    }

    @Test
    fun `录制引擎选型把音频降噪算进去`() {
        val rec = codeOnly(KotlinSourceScan.mainSourceText("record/Recorder.kt"))
        val body = KotlinSourceScan.bodyOf(rec, "useCodecEngine")
        assertTrue(
            "音频降噪非关时必须切 Codec 引擎（MediaRecorder 没有 NoiseSuppressor 的挂载点）",
            body.contains("audioDenoiseNeedsCodec")
        )
    }

    @Test
    fun `效果器挂载与释放的次序不能反`() {
        val src = codeOnly(KotlinSourceScan.mainSourceText("record/AudioFeeder.kt"))
        val start = KotlinSourceScan.bodyOf(src, "start")
        val attach = start.indexOf("AudioDenoiser.attach")
        val recording = start.indexOf("startRecording")
        assertTrue("start() 里没挂效果器", attach >= 0)
        assertTrue("start() 里没起录", recording >= 0)
        assertTrue(
            "必须【先挂效果器再起录】：效果器跟 session 走，起录后再挂会丢掉开头一段",
            attach < recording
        )
        val stop = KotlinSourceScan.bodyOf(src, "stop")
        val release = stop.indexOf("denoiser.release()")
        val freeRec = stop.indexOf("rec?.release()")
        assertTrue("stop() 里没放效果器", release >= 0)
        assertTrue("stop() 里没释放 AudioRecord", freeRec >= 0)
        assertTrue(
            "必须【先放效果器再释放 AudioRecord】：效果器持着那条 session，AudioRecord 释放后再放会报错" +
                "（[pump] 线程收尾那记 rec.stop() 不算——AudioRecord 本体还在、session 仍有效）",
            release < freeRec
        )
    }

    @Test
    fun `GL双边的program选择只在两条成片支路上`() {
        val gl = codeOnly(KotlinSourceScan.mainSourceText("camera/GlRenderEngine.kt"))
        assertTrue("双边程序必须真的在着色器表里", gl.contains("BILATERAL_FS"))
        assertTrue("曲线+双边那条支路也要有", gl.contains("BILATERAL_CURVE_FS"))
        val body = KotlinSourceScan.bodyOf(gl, "buildProgram")
        assertTrue(
            "双边只作用于 effect=NONE（直通/曲线）——斑马纹与峰值对焦是监看叠加层，对它降噪等于对判读工具本身处理",
            body.contains("denoiseOn && effect == FrameEffect.NONE")
        )
        assertTrue("program 是否带降噪必须参与'要不要重建'的判据", body.contains("programDenoise"))
    }
}

/** [CamPill] 的归属容器就是它在 [HudEntry] 里的 homeZone（测试里只读这一处口径，不另抄一张表） */
private fun CamPill.homeZoneOf(): HudZone = HudEntry.of(this).homeZone