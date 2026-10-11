package com.wotagei.cam.camera

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import com.wotagei.cam.source.KotlinSourceScan.regionOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * NTSC 非整数帧率档的网格步长（#2，2026-10-11）。
 *
 * 选型是**Int fps + 映射表**：链上 fps 仍是 Int（相机请求/参数总线/容器声明/UI 全不动），
 * 只有网格步长这一个算术点查表。不升 Double 是为了不为 3 个档改整个平台面。
 */
class NtscGridTest {

    private val sec = 1_000_000_000L
    private val base = 1_000_000_000L

    // ---- ① 映射表的精确值 ----

    @Test
    fun `三档步长是各自有理数的四舍五入`() {
        // 23.976 = 24000/1001、29.97 = 30000/1001、59.94 = 60000/1001
        assertEquals("24 档（实为 23.976）", 41_708_333L, ntscGridStepNs(24))
        assertEquals("30 档（实为 29.97）", 33_366_667L, ntscGridStepNs(30))
        assertEquals("60 档（实为 59.94）", 16_683_333L, ntscGridStepNs(60))
    }

    @Test
    fun `NTSC步长与整数步长必须真的不同`() {
        // 不钉这条就等于没接上：两者相等时整套映射是空转
        assertFalse("24 档两者不得相等", ntscGridStepNs(24) == sec / 24)
        assertFalse("30 档两者不得相等", ntscGridStepNs(30) == sec / 30)
        assertFalse("60 档两者不得相等", ntscGridStepNs(60) == sec / 60)
    }

    @Test
    fun `非映射档返回零`() {
        // 25/50 是 exact 档（1e9 整除，没有近似可言）；120/240 本工程不出现；其余同理
        assertEquals(0L, ntscGridStepNs(0))
        assertEquals(0L, ntscGridStepNs(-1))
        assertEquals(0L, ntscGridStepNs(1))
        assertEquals(0L, ntscGridStepNs(23))
        assertEquals(0L, ntscGridStepNs(25))
        assertEquals(0L, ntscGridStepNs(50))
        assertEquals(0L, ntscGridStepNs(120))
        assertEquals(0L, ntscGridStepNs(240))
        assertEquals(0L, ntscGridStepNs(1000))
    }

    @Test
    fun `25与50是exact档不进映射表（所以不映射是对的，不是漏档）`() {
        assertEquals("1e9 必须整除 25", 0L, sec % 25L)
        assertEquals("1e9 必须整除 50", 0L, sec % 50L)
        assertEquals("25 档本就不该查表", 0L, ntscGridStepNs(25))
        assertEquals("25 档继续走 1e9/fps，值必须精确", sec / 25, 40_000_000L)
    }

    @Test
    fun `1000帧累积误差不到1微秒`() {
        for ((fps, den) in listOf(24 to 24_000L, 30 to 30_000L, 60 to 60_000L)) {
            val trueNs = sec * 1001L * 1000L / den
            val got = 1000L * ntscGridStepNs(fps)
            assertTrue(
                "fps=$fps 的 1000 帧累积误差 ${abs(got - trueNs)}ns 超阈值（限 1000ns）",
                abs(got - trueNs) < 1_000L
            )
        }
    }

    /** 映射表存在的理由：按该步长喂源，PTS 网格永不产生空洞（相邻差恒等于步长） */
    @Test
    fun `按NTSC步长喂源时成片零空洞`() {
        val step = ntscGridStepNs(24)
        var last = 0L
        var gaps = 0L
        val diffs = HashSet<Long>()
        for (i in 0 until 1000) {
            val pts = encoderAdaptivePtsNs(base, base + i * step, step, last, i)
            if (i > 0) diffs.add(pts - last)
            gaps += encoderSlotGap(last, pts, 24)
            last = pts
        }
        assertEquals("NTSC 网格逐帧必须零空档", 0L, gaps)
        assertEquals("相邻差必须恒为该档步长（帧长均匀），实测 $diffs", setOf(step), diffs)
    }

    // ---- ② 接线守卫 ----

    /** 打戳/挂面两处的接线体检：返回违规清单，空 = 接线完整（真身与突变体同吃） */
    private fun ntscWiringViolations(gl: String): List<String> {
        val bad = ArrayList<String>()
        val r = regionOf(gl, "setOutputSurface")
        val sig = gl.substring(gl.lastIndexOf("fun setOutputSurface", r.start), r.start)
        if (!sig.contains("ntsc: Boolean = false")) {
            bad.add("setOutputSurface 的 ntsc 形参必须带默认值 false（既有调用点零改动）")
        }
        if (!Regex("@Volatile\\s+private var encoderNtsc").containsMatchIn(gl)) {
            bad.add("encoderNtsc 必须 @Volatile（控制线程写、GL 线程读）")
        }
        val set = flatten(bodyOf(gl, "setOutputSurface"))
        if (!set.contains("encoderNtsc = if (surface == null) false else ntsc")) {
            bad.add("解面必须把 encoderNtsc 清回 false（残留 true 会让下一段整数档落成非整数网格）")
        }
        val stamp = flatten(bodyOf(gl, "stampEncoderPresentation"))
        if (!stamp.contains("if (encoderNtsc) ntscGridStepNs(fps) else 0L")) {
            bad.add("NTSC 档必须查 ntscGridStepNs")
        }
        if (!stamp.contains("if (ntscStep > 0L) ntscStep else 1_000_000_000L / fps")) {
            bad.add("查表落空必须退回整数步长（把 0 当步长传下去会让整段的戳并成同一枚）")
        }
        if (Regex("ntscGridStepNs\\(\\s*\\d").containsMatchIn(stamp)) {
            bad.add("打戳处不许写死档位常数（必须走运行时的 encoderFps）")
        }
        return bad
    }

    @Test
    fun `NTSC接线完整`() {
        val bad = ntscWiringViolations(codeOnly(mainSourceText("camera/GlRenderEngine.kt")))
        assertEquals("NTSC 接线发现断点：\n${bad.joinToString("\n")}", 0, bad.size)
    }

    @Test
    fun `CameraScreen必须把fpsNtsc接进profile与两处挂面`() {
        val screen = codeOnly(mainSourceText("ui/CameraScreen.kt"))
        val build = flatten(bodyOf(screen, "buildProfile"))
        assertTrue("建 profile 处必须按 !fps.exact 填 NTSC 标志", build.contains("fpsNtsc = !params.fps.value.exact"))
        val begin = flatten(bodyOf(screen, "beginSession"))
        assertTrue(
            "首段挂面必须带上 NTSC 标志",
            begin.contains("setOutputSurface(surface, profile.width, profile.height, profile.fps, profile.fpsNtsc)")
        )
        assertTrue(
            "换段重挂必须带上 NTSC 标志",
            begin.contains("setOutputSurface(s, profile.width, profile.height, profile.fps, profile.fpsNtsc)")
        )
        assertFalse(
            "置零/解面路径必须用默认 false，不许把上一段的 NTSC 标志带过去",
            flatten(screen).contains("setOutputSurface(null, 0, 0, ")
        )
    }

    @Test
    fun `RecordProfile的fpsNtsc带默认值且离线修复链构造点未被波及`() {
        val profile = codeOnly(mainSourceText("record/RecordProfile.kt"))
        assertTrue("fpsNtsc 必须带默认值 false", profile.contains("val fpsNtsc: Boolean = false"))
        val arc = codeOnly(mainSourceText("record/ArcRepairRunner.kt"))
        assertFalse(
            "ArcRepairRunner 的 RecordProfile 构造走离线容器自己的 dstFps，不该被 NTSC 波及",
            arc.contains("fpsNtsc")
        )
    }

    @Test
    fun `容器帧率声明必须保持整数且取舍依据留在文件头`() {
        val rec = flatten(codeOnly(mainSourceText("record/CodecRecorder.kt")))
        assertEquals(
            "两处 KEY_FRAME_RATE 都必须仍写整数 p.fps",
            2, occurrences(rec, "setInteger(MediaFormat.KEY_FRAME_RATE, p.fps)").size
        )
        val raw = mainSourceText("record/CodecRecorder.kt")
        assertTrue(
            "『网格要真、声明可以粗』的取舍依据必须留在文件头（否则下一个人会以为漏改）",
            raw.contains("NTSC 帧率的容器声明取舍")
        )
    }

    // ---- ③ 突变自证 ----

    /** 映射表的本地复刻（可突变）；生产侧形态由「三档步长 / 非映射档」两条用例钉住 */
    private fun ntscStepLocal(fps: Int, wrong60: Boolean = false, wrong30: Boolean = false): Long {
        val den = if (fps == 24) 24_000L
        else if (fps == 30) if (wrong30) 24_000L else 30_000L
        else if (fps == 60) if (wrong60) 24_000L else 60_000L
        else return 0L
        return (sec * 1001L + den / 2) / den
    }

    @Test
    fun `尺子自己能红_映射表分母改坏必偏离`() {
        val fpsList = listOf(24, 30, 60, 25, 50, 120)
        val good = fpsList.map { ntscStepLocal(it) }
        assertEquals(
            "良品必须先真的绿（24/30/60 有值，25/50/120 为 0）",
            listOf(41_708_333L, 33_366_667L, 16_683_333L, 0L, 0L, 0L), good
        )
        val mutants = listOf(
            "60 档误用 24 档分母" to fpsList.map { ntscStepLocal(it, wrong60 = true) },
            "30 档误用 24 档分母" to fpsList.map { ntscStepLocal(it, wrong30 = true) }
        )
        mutants.forEach { (name, rows) ->
            val deviated = good.indices.filter { good[it] != rows[it] }
            assertTrue(
                "突变体「$name」必须偏离真值表（偏离 0 行 = 语义空操作，尺子什么也测不到）。实测偏离：$deviated",
                deviated.isNotEmpty()
            )
        }
        // 钉住偏离落在被改的那一档上：分母写错档必须**只**影响那一档，
        // 否则「整表都被换掉」也能让这条断言绿
        val m60 = fpsList.map { ntscStepLocal(it, wrong60 = true) }
        assertEquals("60 档分母改错只许偏离 60 那一行", listOf(2), good.indices.filter { good[it] != m60[it] })
    }

    @Test
    fun `尺子自己能红_接线被改坏必报红`() {
        val gl = codeOnly(mainSourceText("camera/GlRenderEngine.kt"))
        assertEquals("良品接线必须先绿", 0, ntscWiringViolations(gl).size)
        val mutants = listOf(
            Triple("打戳处不查表", gl.replace("if (encoderNtsc) ntscGridStepNs(fps) else 0L", "if (false) ntscGridStepNs(fps) else 0L"), true),
            Triple("查表落空不退整数档", gl.replace("if (ntscStep > 0L) ntscStep else", "if (ntscStep >= 0L) ntscStep else"), true),
            Triple("解面不清 NTSC", gl.replace("encoderNtsc = if (surface == null) false else ntsc", "encoderNtsc = ntsc"), true),
            Triple("ntsc 形参丢了默认值", gl.replace("frameRate: Int = 0, ntsc: Boolean = false", "frameRate: Int = 0"), true),
            Triple("打戳处写死档位常数", gl.replace("ntscGridStepNs(fps)", "ntscGridStepNs(24)"), true),
            // 对照组：只改注释/空白不许引发红（尺子不许对无害改动过敏）
            Triple("对照组：只改注释", gl.replace("private var encoderNtsc = false", "private var encoderNtsc = false // 注释"), false),
            Triple("对照组：只改空白", gl.replace("private fun stampEncoderPresentation", "private  fun  stampEncoderPresentation"), false)
        )
        mutants.forEach { (name, mutated, shouldBeRed) ->
            val red = ntscWiringViolations(mutated)
            if (shouldBeRed) {
                assertTrue("突变体「$name」必须报红（实际违规：${red.joinToString("；")}）", red.isNotEmpty())
            } else {
                assertEquals("对照组「$name」不该报红（实际违规：${red.joinToString("；")}）", 0, red.size)
            }
        }
    }
}
