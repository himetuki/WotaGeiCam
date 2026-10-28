package com.wotagei.cam

import com.wotagei.cam.core.GlowParams
import com.wotagei.cam.core.VideoDenoise
import com.wotagei.cam.core.encodeGlow
import com.wotagei.cam.core.glowArgbFromHsv
import com.wotagei.cam.player.sceneExportBitrate
import com.wotagei.cam.player.sceneExportEffects
import com.wotagei.cam.player.sceneExportFps
import com.wotagei.cam.player.sceneExportPtsUs
import com.wotagei.cam.player.sceneExportShouldSubmit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「内置播放器内后渲染导出」的**决策层口径**（2026-10-28 用户需求）。
 *
 * 用户口径（原话）：「Glow 和降噪的『内置播放器内后渲染导出』功能若还没做，请你补上。
 * 另，RGB 曲线变动后是否已渲染进成片？不需要在内置播放器内做 RGB 曲线后渲染。」
 * ⇒ 这一层只认 **Glow + 画面降噪**；曲线**刻意不读**（录制期已烘进成片，再套一次是双倍）。
 *
 * 这里钉的是"读什么参数、编什么规格"，GL/编解码行为只有真机能证（见 SceneExportWiringGuardTest
 * 的接线判据与 deliverable 的抽帧比对）。
 */
class SceneExportPlanTest {

    private fun glowOn(color: Long = 0xFFFF_FFFFL): String =
        encodeGlow(GlowParams.DEFAULT.copy(enabled = true, color = color))

    @Test
    fun `未开光晕未开降噪时后渲染无意义`() {
        val e = sceneExportEffects(null, VideoDenoise.OFF)
        assertNull("缺键=出厂=关，不许凭空造出光晕", e.glow)
        assertFalse("降噪 OFF 不许应用双边", e.denoise)
        assertFalse("无效果时 UI 应禁用后渲染模式（active=false）", e.active)
    }

    @Test
    fun `坏串回落出厂关`() {
        val e = sceneExportEffects("enabled=1;threshold=abc", VideoDenoise.OFF)
        // decodeGlow 对坏值的口径是整串回默认（见 Glow.kt 的 decodeGlow 注释）
        assertNull(e.glow)
        assertFalse(e.active)
    }

    @Test
    fun `光晕开启即应用且带完整参数`() {
        val e = sceneExportEffects(glowOn(), VideoDenoise.OFF)
        assertNotNull(e.glow)
        assertTrue(e.glow!!.enabled)
        assertTrue(e.active)
        assertFalse("只开光晕时降噪仍为 false", e.denoise)
    }

    @Test
    fun `降噪任一档都按GPU双边处理`() {
        // 离线没有 HAL 的 ISP，HAL 两档在这里只能退化成同一条双边核（见 SceneExportEffects KDoc）
        for (d in listOf(VideoDenoise.HAL_FAST, VideoDenoise.HAL_HIGH_QUALITY, VideoDenoise.GPU_BILATERAL)) {
            val e = sceneExportEffects(null, d)
            assertTrue("$d 必须落成降噪开启", e.denoise)
            assertTrue("$d 单独存在时 active 必须为真", e.active)
        }
    }

    @Test
    fun `白色tint是恒等`() {
        val e = sceneExportEffects(glowOn(0xFFFF_FFFFL), VideoDenoise.OFF)
        val tint = e.glowTint
        assertEquals(1f, tint[0], 1e-6f)
        assertEquals(1f, tint[1], 1e-6f)
        assertEquals(1f, tint[2], 1e-6f)
    }

    @Test
    fun `色盘选色会进tint`() {
        // 色盘选红：hue=0、sat=1 → 红通道 1、蓝通道 0（光晕"整体偏该色调"）
        val red = glowArgbFromHsv(0f, 1f, 1f)
        val tint = sceneExportEffects(glowOn(red), VideoDenoise.OFF).glowTint
        assertTrue("红 tint 必须抬 r", tint[0] > 0.9f)
        assertTrue("红 tint 必须压 b", tint[2] < 0.1f)
    }

    @Test
    fun `帧率跟源且钳在15到60`() {
        assertEquals("读不出按 30", 30, sceneExportFps(0))
        assertEquals(15, sceneExportFps(15))
        assertEquals(60, sceneExportFps(60))
        assertEquals("超上限回 30（不自造未声明档）", 30, sceneExportFps(61))
        assertEquals("低于下限回 30", 30, sceneExportFps(14))
        assertEquals(30, sceneExportFps(-1))
    }

    @Test
    fun `码率跟源且异常回8M`() {
        assertEquals(8_000_000, sceneExportBitrate(0))
        assertEquals(8_000_000, sceneExportBitrate(999_999))
        assertEquals(1_000_000, sceneExportBitrate(1_000_000))
        assertEquals(20_000_000, sceneExportBitrate(20_000_000))
        assertEquals("超上限按兜底，不许把 4K 码率抄成天文数字", 8_000_000, sceneExportBitrate(200_000_001))
    }

    @Test
    fun `呈现时间戳减基准且不落负`() {
        assertEquals(0L, sceneExportPtsUs(1_500_000L, 1_500_000L))
        assertEquals(500_000L, sceneExportPtsUs(2_000_000L, 1_500_000L))
        assertEquals("基准前的帧钳 0，不写负戳", 0L, sceneExportPtsUs(1_000_000L, 1_500_000L))
    }

    @Test
    fun `区间判据左闭右开`() {
        assertFalse("入点前的关键帧只解码不提交", sceneExportShouldSubmit(999L, 1_000L, 5_000L))
        assertTrue("入点本身要提交", sceneExportShouldSubmit(1_000L, 1_000L, 5_000L))
        assertTrue(sceneExportShouldSubmit(4_999L, 1_000L, 5_000L))
        assertFalse("出点本身不提交（右开）", sceneExportShouldSubmit(5_000L, 1_000L, 5_000L))
        // endUs <= startUs（区间未知）= 不设右界，整段提交
        assertTrue(sceneExportShouldSubmit(9_999_999L, 1_000L, 1_000L))
    }
}
