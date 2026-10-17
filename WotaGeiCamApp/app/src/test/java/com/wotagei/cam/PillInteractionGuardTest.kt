package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import com.wotagei.cam.ui.PillKey
import com.wotagei.cam.ui.RECORDING_EDITABLE_PILLS
import com.wotagei.cam.ui.pillEditableDuringRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-17 胶囊交互调整的源码/行为守卫：
 * 1. 读数与镜头胶囊**点按、长按都开弹窗**（点按不再循环改值）；
 * 2. 旧循环实现（`HudCycle.kt` / `nextLensKey`）删除干净——全仓主源码零残留；
 * 3. 录制中可调白名单恰为 ISO/EV/WB/ZOOM/FOCUS，其余弹窗控件走 `enabled = editable`；
 * 4. 对焦锁定录制中的条目判据是 `!recording || focusLocked`。
 *
 * 每条判据都配「尺子能红」：拆掉接线/改掉白名单后同一把尺必须报红。
 */
class PillInteractionGuardTest {

    private val hud by lazy { codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayer.kt")) }
    private val pills by lazy { codeOnly(KotlinSourceScan.mainSourceText("ui/CameraPills.kt")) }

    /** HudLayer 里点按/长按的接线问题；空 = 干净 */
    private fun hudTapIssues(src: String): List<String> {
        val bad = ArrayList<String>()
        val entry = flatten(bodyOf(src, "HudEntryItem"))
        if (!entry.contains("onClick = gatedClick(clicksAccepted) { ctx.onReadoutOpen(item) }")) {
            bad.add("读数条目 onClick 不是 onReadoutOpen（点按循环可能回潮）")
        }
        if (!entry.contains("onLongClick = gatedClick(clicksAccepted) { ctx.onReadoutOpen(item) }")) {
            bad.add("读数条目 onLongClick 不是 onReadoutOpen（长按开窗断裂）")
        }
        if (!entry.contains("onClick = gatedClick(clicksAccepted, ctx.onOpenLensPanel)")) {
            bad.add("镜头胶囊 onClick 不是 onOpenLensPanel（点按切镜可能回潮）")
        }
        if (!entry.contains("onLongClick = if (clicksAccepted) ctx.onOpenLensPanel else null")) {
            bad.add("镜头胶囊 onLongClick 不是 onOpenLensPanel（长按开窗断裂）")
        }
        return bad
    }

    /** 只读弹窗体：每个都必须有 editable 总闸；空 = 干净 */
    private fun readOnlyIssues(src: String): List<String> {
        val readOnlyPills = listOf(
            "SizePill", "FpsPill", "ShutterPill", "BitratePill", "LensPill",
            "RefLinePill", "MonitorPill", "FlashPill", "StabPill", "StoragePill"
        )
        return readOnlyPills.filter { fn ->
            val body = runCatching { bodyOf(src, fn) }.getOrNull()
            body == null || occurrences(body, "enabled = editable").isEmpty()
        }.map { "$it 没有 enabled = editable 只读总闸" }
    }

    @Test
    fun `读数与镜头点按长按都开弹窗`() {
        assertEquals("HudLayer 点按接线断点：\n${hudTapIssues(hud).joinToString("\n")}", emptyList<String>(), hudTapIssues(hud))
    }

    @Test
    fun `旧循环实现全仓主源码零残留`() {
        val forbidden = listOf(
            "onReadoutCycle", "onLensCycle", "hudCycleStep",
            "nextInCycle", "nextLensKey", "zoomPanelTiers", "zoomQuickTiers"
        )
        val hits = ArrayList<String>()
        KotlinSourceScan.walkMainSources().forEach { (path, text) ->
            val code = codeOnly(text)
            forbidden.filter { occurrences(code, it).isNotEmpty() }.forEach { token ->
                hits.add("$path 仍含 $token")
            }
        }
        assertEquals("删除不干净的残留：\n${hits.joinToString("\n")}", emptyList<String>(), hits)
    }

    @Test
    fun `录制中可调白名单恰为五项`() {
        assertEquals(
            "可调白名单必须恰为 ISO/EV/WB/ZOOM/FOCUS",
            setOf(PillKey.ISO, PillKey.EV, PillKey.WB, PillKey.ZOOM, PillKey.FOCUS),
            RECORDING_EDITABLE_PILLS
        )
        PillKey.values().forEach { key ->
            assertEquals(
                "$key 的录制可调判据与白名单不一致",
                key in RECORDING_EDITABLE_PILLS,
                pillEditableDuringRecording(key)
            )
        }
        // 只读/可调的代表项各点一次名（写死方向错一处即红）
        assertTrue(pillEditableDuringRecording(PillKey.WB))
        assertTrue(pillEditableDuringRecording(PillKey.FOCUS))
        assertFalse(pillEditableDuringRecording(PillKey.SHUTTER))
        assertFalse(pillEditableDuringRecording(PillKey.BITRATE))
        assertFalse(pillEditableDuringRecording(PillKey.BT))
    }

    @Test
    fun `只读弹窗都有总闸且对焦锁定录制判据在位`() {
        assertEquals("只读弹窗总闸缺件：\n${readOnlyIssues(pills).joinToString("\n")}", emptyList<String>(), readOnlyIssues(pills))
        val focus = flatten(bodyOf(pills, "FocusPill"))
        assertTrue(
            "FocusPill 锁定条目必须禁用录制中未锁的情形（!recording || focusLocked）",
            focus.contains("!recording || focusLocked")
        )
        assertTrue(
            "FocusPill 录制中未锁必须给说明",
            focus.contains("cam_focus_lock_recording")
        )
    }

    @Test
    fun `尺子自己能红`() {
        assertEquals("良品先绿", emptyList<String>(), hudTapIssues(hud))
        // 突变 1：读数长按改回别的回调 -> 必红
        assertTrue(
            "长按开窗被拆必须红",
            hudTapIssues(hud.replace("onLongClick = gatedClick(clicksAccepted) { ctx.onReadoutOpen(item) }", "onLongClick = null"))
                .any { it.contains("onLongClick") }
        )
        // 突变 2：镜头点按改回切镜 -> 必红
        assertTrue(
            "镜头点按开窗被拆必须红",
            hudTapIssues(hud.replace("onClick = gatedClick(clicksAccepted, ctx.onOpenLensPanel)", "onClick = gatedClick(clicksAccepted) { }"))
                .any { it.contains("onClick") }
        )
        // 突变 3：只读总闸被拆 -> 必红
        assertTrue(
            "只读总闸被拆必须红",
            readOnlyIssues(pills.replace("enabled = editable", "enabled = true")).isNotEmpty()
        )
    }
}
