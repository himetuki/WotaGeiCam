package com.wotagei.cam.core

import com.wotagei.cam.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Glow 胶囊**状态行选句**的纯函数账（docs/plan/24 R3 §1.6 的"自动变化必须可见"）。
 *
 * 六态全测：DIRECT、关、停用、夹取但 RT 为 0、夹取且 RT>0、正常分档。
 * 这几条之所以值得单开一个套件，是因为每一条错法在真机上都只是"少了一行小字"或"多了一行噪声"，
 * 而 JVM 侧只有把优先级写死才测得出来：
 * - **DIRECT 必须最先判**：那时 `GlowStatus` 的 available 恒 false，若先判掉"没数据"就永远说不出
 *   "需切到使用 GPU"；
 * - **关着必须什么都不报**：停用/夹取是上一段开启时的残留，效果关着还报就是噪声；
 * - **夹取要有 RT 真尺寸才算数**：rtW/rtH 为 0 时报"已夹到 0×0"是假信息。
 */
class GlowStatusNoteTest {

    private fun status(
        available: Boolean = true,
        enabled: Boolean = true,
        broken: Boolean = false,
        scale: Int = 2,
        inputW: Int = 1920,
        inputH: Int = 1080,
        fps: Int = 30,
        rtW: Int = 960,
        rtH: Int = 540,
        clamped: Boolean = false
    ) = GlowStatus(
        available = available,
        enabled = enabled,
        broken = broken,
        scale = scale,
        inputWidthPx = inputW,
        inputHeightPx = inputH,
        fps = fps,
        rtWidthPx = rtW,
        rtHeightPx = rtH,
        clamped = clamped
    )

    @Test
    fun `DIRECT 最先判_即使开关开着也说清原因`() {
        assertEquals(
            R.string.cam_glow_direct,
            glowStatusNote(status(enabled = true, available = false), enabled = true, gpuMode = false)
        )
        // DIRECT 下 engine 为 null 也一样（不能因为没状态就说不出话）
        assertEquals(R.string.cam_glow_direct, glowStatusNote(null, enabled = true, gpuMode = false))
        // 就算 broken 也先说 DIRECT（没有 GL 管线时"链坏了"是伪信息）
        assertEquals(
            R.string.cam_glow_direct,
            glowStatusNote(status(broken = true), enabled = true, gpuMode = false)
        )
    }

    @Test
    fun `关着什么都不显示_包括停用与夹取的残留`() {
        assertNull(glowStatusNote(status(), enabled = false, gpuMode = true))
        assertNull(glowStatusNote(status(broken = true), enabled = false, gpuMode = true))
        assertNull(glowStatusNote(status(clamped = true), enabled = false, gpuMode = true))
        assertNull(glowStatusNote(null, enabled = false, gpuMode = true))
    }

    @Test
    fun `停用优先于夹取与分档`() {
        assertEquals(
            R.string.cam_glow_broken,
            glowStatusNote(status(broken = true, clamped = true), enabled = true, gpuMode = true)
        )
    }

    @Test
    fun `夹取必须有真实 RT 尺寸才报`() {
        // clamped 真、RT 也在 ⇒ 报夹取
        assertEquals(
            R.string.cam_glow_clamped,
            glowStatusNote(status(clamped = true, rtW = 4096, rtH = 2304), enabled = true, gpuMode = true)
        )
        // clamped 还在但 RT 已拆（rtW/rtH=0）⇒ 退到分档，不报"已夹到 0×0"
        assertEquals(
            R.string.cam_glow_status_res_label,
            glowStatusNote(status(clamped = true, rtW = 0, rtH = 0), enabled = true, gpuMode = true)
        )
        assertEquals(
            R.string.cam_glow_status_auto,
            glowStatusNote(
                status(clamped = true, rtW = 0, rtH = 0, inputW = 0, inputH = 0),
                enabled = true, gpuMode = true
            )
        )
    }

    @Test
    fun `正常分档_已知输入尺寸报带分辨率的句式`() {
        assertEquals(
            R.string.cam_glow_status_res_label,
            glowStatusNote(status(scale = 4, inputW = 3840, inputH = 2160), enabled = true, gpuMode = true)
        )
        // 输入尺寸未知（还没出帧）⇒ 只说档位，不编造分辨率
        assertEquals(
            R.string.cam_glow_status_auto,
            glowStatusNote(status(inputW = 0, inputH = 0), enabled = true, gpuMode = true)
        )
    }

    @Test
    fun `引擎还没建好时不猜档位`() {
        assertNull(glowStatusNote(null, enabled = true, gpuMode = true))
    }
}
