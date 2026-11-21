package com.wotagei.cam

import com.wotagei.cam.core.CamPill
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.ui.HudEntry
import com.wotagei.cam.ui.HudZone
import com.wotagei.cam.ui.editorBottomBandEntries
import com.wotagei.cam.ui.editorVisibleEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑页底栏必须能看见/拖动 ECHO（Y58）。
 *
 * 可见集本身忽略掩码（既有口径），所以旧配置里被隐藏过的 ECHO 仍在 visible；
 * 这里钉的是「伴随条目集合」把 ECHO 纳进来、顺序与录制页一致，以及编辑器接线。
 */
class EditorEchoBandEntryTest {

    private val echo = HudEntry.of(CamPill.ECHO)
    private val denoise = HudEntry.of(CamPill.DENOISE)
    private val visible = editorVisibleEntries(levelEnabled = true)

    @Test
    fun `两颗都在底栏时按录制页伴随序排放`() {
        val entries = editorBottomBandEntries(visible) { HudZone.BOTTOM }
        assertEquals(listOf(echo, denoise), entries)
    }

    @Test
    fun `旧掩码配置下 ECHO 仍在编辑页可见集里`() {
        // 编辑页可见集不读 hud_pills/hud_items 掩码：用户隐藏过 ECHO 也不会被摘掉入口
        assertTrue("ECHO 必须在编辑页可见集里（否则旧配置无法恢复显示位）", echo in visible)
        assertEquals(
            listOf(echo, denoise),
            editorBottomBandEntries(visible) { HudZone.BOTTOM }
        )
    }

    @Test
    fun `挪出底栏的条目从伴随集合消失`() {
        assertEquals(
            listOf(echo),
            editorBottomBandEntries(visible) { if (it == denoise) HudZone.RIGHT else HudZone.BOTTOM }
        )
        assertEquals(
            listOf(denoise),
            editorBottomBandEntries(visible) { if (it == echo) HudZone.RIGHT else HudZone.BOTTOM }
        )
        assertTrue(
            editorBottomBandEntries(visible) { HudZone.LEFT }.isEmpty()
        )
    }

    @Test
    fun `编辑页 bandExtra 渲染集合走同一函数`() {
        val flat = KotlinSourceScan.flatten(
            KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayoutEditor.kt"))
        )
        assertTrue(
            "bandExtra 必须用 editorBottomBandEntries(visibleEntries) 取集合",
            flat.contains("editorBottomBandEntries(visibleEntries) { draft.sourceZoneOf(it) }")
        )
        assertTrue(
            "集合内逐颗走 HudBottomBandEntry（与录制页同一渲染入口、可拖）",
            flat.contains("bandEntries.forEach { bandEntry -> HudBottomBandEntry( entry = bandEntry, ctx = ctx, clicksAccepted = true ) }")
        )
        assertFalse(
            "旧的只渲染 DENOISE 形态不许回流",
            flat.contains("val denoiseEntry = HudEntry.of(CamPill.DENOISE)")
        )
    }
}