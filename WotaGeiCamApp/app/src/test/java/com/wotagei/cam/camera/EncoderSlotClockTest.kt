package com.wotagei.cam.camera

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧呈现时间戳口径（真实时钟落网格）——2026-10-08 真机缺陷的回归守卫。
 *
 * 真机事实（秒表对照一轮 31.85s 的真实录制）：视频轨合计 34.21s（每段恒 ~180 帧 / 7.45s），
 * 音频轨合计 30.01s（音频戳取自真实 nanoTime ⇒ 它才是真实时长）⇒ 成片被匀速拉长 8~14%、
 * 与音轨逐渐错位。原因：戳按"帧序号 × 1e9/fps"走，隐含"源实到帧率 = 目标帧率"，而半精确档
 * （24※）下源实到 26~31 帧/秒。本文件把新口径钉住：**网格位置取自真实时钟**。
 */
class EncoderSlotClockTest {

    private val sec = 1_000_000_000L
    private val base = 1_000_000_000L
    private val fps24Step = 1_000_000_000L / 24

    // ---- ① 纯函数表 ----

    @Test
    fun `本段首帧落在基准上（slot 0）`() {
        assertEquals(base, encoderSlotPtsNs(base, base, 24, 0L))
        assertEquals(base, encoderSlotPtsNs(base, base + 1L, 24, 0L))
    }

    @Test
    fun `满一格取下一格`() {
        assertEquals(base + fps24Step, encoderSlotPtsNs(base, base + fps24Step, 24, 0L))
        assertEquals(base + 2 * fps24Step, encoderSlotPtsNs(base, base + 2 * fps24Step, 24, 0L))
    }

    @Test
    fun `不到一格不进格（抖动被网格吸收，容器帧长仍齐）`() {
        val jittery = base + fps24Step / 3
        assertEquals(base, encoderSlotPtsNs(base, jittery, 24, 0L))
    }

    @Test
    fun `源帧率高于目标时同格多帧：时间戳相等而不是被推进`() {
        val first = encoderSlotPtsNs(base, base, 24, 0L)
        // 同一格内再来一帧（48fps 实到时的情形）
        val second = encoderSlotPtsNs(base, base + 5_000_000L, 24, first)
        assertEquals("同格第二帧必须与第一帧同戳（推进就等于把源帧率当成目标帧率、时间轴又被拉长）", first, second)
    }

    @Test
    fun `源端漏帧时网格拉出空档（真实缺口如实反映，不压缩）`() {
        val pts = encoderSlotPtsNs(base, base + 3 * fps24Step, 24, base)
        assertEquals(base + 3 * fps24Step, pts)
    }

    @Test
    fun `时钟回退时绝不小于上一枚（容器对同轨时间戳倒退直接判失败）`() {
        val last = base + 5 * fps24Step
        assertEquals(last, encoderSlotPtsNs(base, base + fps24Step, 24, last))
        assertEquals(last, encoderSlotPtsNs(base, base - sec, 24, last))
    }

    @Test
    fun `时钟早于基准时退回基准`() {
        assertEquals(base, encoderSlotPtsNs(base, base - sec, 24, 0L))
    }

    @Test
    fun `帧率不可用时不改戳（原样返回上一枚）`() {
        assertEquals(123L, encoderSlotPtsNs(base, base + sec, 0, 123L))
        assertEquals(123L, encoderSlotPtsNs(base, base + sec, -1, 123L))
    }

    @Test
    fun `25fps 同样成立（另一条必需档位）`() {
        val step = 1_000_000_000L / 25
        assertEquals(base + step, encoderSlotPtsNs(base, base + step, 25, 0L))
        assertEquals(base, encoderSlotPtsNs(base, base + step - 1L, 25, 0L))
    }

    /**
     * 本缺陷的**回归本体**：源实到 31 帧/秒、目标 24 帧/秒，持续 10 秒——
     * 新口径的时间轴必须 ≈10 秒（旧口径 310 帧 × 1/24 ≈ 12.9 秒，正是真机那 8~14% 的拉长）。
     */
    @Test
    fun `源帧率高于目标时时间轴仍等于真实时长`() {
        var last = 0L
        var n = 0
        while (n < 310) {
            val now = base + n * sec / 31
            last = encoderSlotPtsNs(base, now, 24, last)
            n++
        }
        val span = last - base
        val oldSpan = 310 * fps24Step // 旧口径（帧序号 × 网格）
        assertTrue("新口径时间轴应 ≈10s，实际 ${span / 1e9}s", span in 9_900_000_000L..10_010_000_000L)
        assertTrue("旧口径应明显更长（${oldSpan / 1e9}s），否则本用例没测到拉长", oldSpan > 12_000_000_000L)
    }

    @Test
    fun `帧率越高时长越准（48fps 源、24fps 目标，10 秒仍是 10 秒）`() {
        var last = 0L
        var n = 0
        while (n < 480) {
            last = encoderSlotPtsNs(base, base + n * sec / 48, 24, last)
            n++
        }
        assertTrue("48fps 源也必须是 10s 量级，实际 ${(last - base) / 1e9}s", (last - base) in 9_900_000_000L..10_010_000_000L)
    }

    // ---- ② 接线守卫：stampEncoderPresentation 必须走新口径 ----

    @Test
    fun `呈现时间戳必须走真实时钟落网格`() {
        val gl = codeOnly(mainSourceText("camera/GlRenderEngine.kt"))
        val body = bodyOf(gl, "stampEncoderPresentation")
        assertTrue("必须调 encoderSlotPtsNs 桥", body.contains("encoderSlotPtsNs("))
        assertTrue("必须传真实时钟", body.contains("System.nanoTime()"))
        assertTrue("必须更新'最后一枚戳'账（防回退要它）", body.contains("encoderLastPtsNs ="))
        assertFalse(
            "不许退回'帧序号 × 网格'的旧口径（那会把源帧率当成目标帧率、时间轴被拉长）",
            body.contains("encoderFrameIndex *")
        )
        // 既有守卫的锚点（FrostEncoderGuardTest）不能被这轮改动碰掉
        assertTrue("锚点：EGL 呈现时间戳写入", body.contains("eglPresentationTimeANDROID(display, encoder, ptsNs)"))
        assertTrue("锚点：帧号自增", body.contains("encoderFrameIndex++"))
    }

    @Test
    fun `每段必须复位最后一枚戳的账`() {
        val gl = codeOnly(mainSourceText("camera/GlRenderEngine.kt"))
        // 定义 + 段起点复位（与 encoderBaseNs 同一处）
        val n = gl.split("encoderLastPtsNs").size - 1
        assertTrue("encoderLastPtsNs 至少要有定义 + 段起点复位 + 打戳处读写，实际 $n 处", n >= 3)
        assertTrue(
            "段起点必须与 encoderBaseNs 一起复位（否则上一段的戳会把新段第一帧顶到旧网格）",
            gl.contains("encoderBaseNs = System.nanoTime()\n        encoderLastPtsNs = 0L") ||
                gl.contains("encoderLastPtsNs = 0L")
        )
    }
}
