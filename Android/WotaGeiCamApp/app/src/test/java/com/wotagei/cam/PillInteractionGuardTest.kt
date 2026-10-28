package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import com.wotagei.cam.ui.PillKey
import com.wotagei.cam.ui.RECORDING_EDITABLE_PILLS
import com.wotagei.cam.ui.pillEditableDuringRecording
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-17 胶囊交互调整的源码/行为守卫：
 * 1. 读数与镜头胶囊**点按、长按都开弹窗**（点按不再循环改值）；
 * 2. 旧循环实现（`HudCycle.kt` / `nextLensKey`）删除干净——全仓主源码零残留；
 * 3. 录制中可调白名单恰为 ISO/EV/WB/ZOOM/FOCUS/MONITOR/GLOW（2026-10-27 B4 加入 MONITOR：
 *    监看叠加只改上屏槽，成片不变），其余弹窗控件走 `enabled = editable`；
 * 4. 对焦锁定录制中的条目判据是 `!recording || focusLocked`；
 * 5. 监看弹窗常驻「只上屏、不进成片」说明（B4 的前置提示，缺了就是静默改）。
 *
 * 每条判据都配「尺子能红」：拆掉接线/改掉白名单后同一把尺必须报红。
 */
class PillInteractionGuardTest {

    private val hud by lazy { codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayer.kt")) }
    private val pills by lazy { codeOnly(KotlinSourceScan.mainSourceText("ui/CameraPills.kt")) }
    private val screen by lazy { codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt")) }
    private val audioPanel by lazy { codeOnly(KotlinSourceScan.mainSourceText("ui/dialog/AudioSourcePanel.kt")) }
    private val btPanel by lazy { codeOnly(KotlinSourceScan.mainSourceText("ui/dialog/BtSpeakerPanel.kt")) }

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
            "RefLinePill", "MonitorPill", "FlashPill", "StabPill", "StoragePill", "GlowPill"
        )
        return readOnlyPills.filter { fn ->
            val body = runCatching { bodyOf(src, fn) }.getOrNull()
            body == null || occurrences(body, "enabled = editable").isEmpty()
        }.map { "$it 没有 enabled = editable 只读总闸" }
    }

    /**
     * 21 根滑杆必须全部接梯（A2 + 2026-10-17 并入门槛滑杆 + 2026-10-28 色调四条）：
     * 任一根漏传 `ladder` 就会退回连续滑杆，而纯函数测试仍全绿（审查 P3 的假绿口）。空 = 干净。
     */
    private fun ladderIssues(src: String): List<String> {
        val bad = ArrayList<String>()
        if (occurrences(src, "PillSlider(").size != 21) bad.add("PillSlider 调用点不是 21 处")
        // 快门面板另有 `val ladder = shutterLadder(...)`（梯的本地构造），参数字面量要减掉它才是调用点数
        val ladderArgs = occurrences(src, "ladder =").size - occurrences(src, "val ladder =").size
        if (ladderArgs != 21) bad.add("ladder = 参数不是 21 处（有滑杆退回连续模式）")
        val panels = listOf(
            "ShutterPill", "IsoPill", "EvPill", "WbPill", "ZoomPill", "FocusPill",
            "MonitorPill", "FpsPill", "GlowPill", "TonePill"
        )
        panels.forEach { fn ->
            val body = runCatching { bodyOf(src, fn) }.getOrNull()
            if (body == null || occurrences(body, "ladder =").isEmpty()) bad.add("$fn 没有 ladder")
        }
        return bad
    }

    /** 录制中只读必须覆盖 BT 页签（A4，审查 P1）：扫描/连接/音量/传输全部吃 editable。空 = 干净 */
    private fun btIssues(panel: String, audio: String): List<String> {
        val bad = ArrayList<String>()
        if (!audio.contains("BtSpeakerPanel(bt, editable)")) {
            bad.add("AudioSourcePanel 没有把 editable 传给 BtSpeakerPanel")
        }
        if (!panel.contains("editable: Boolean")) bad.add("BtSpeakerPanel 没有 editable 形参")
        if (panel.contains("editable: Boolean =")) bad.add("BtSpeakerPanel 的 editable 不许给默认值（漏传应编译不过）")
        // 逐控件点名：扫描/权限/去设置/DeviceRow 连接/prev/playPause/next/开定位（2026-10-20
        // pre-S 扫描门槛引导钮，与权限/蓝牙设置同族，录制中只读）恰 8 处（拆掉任一处必红）
        val gated = occurrences(panel, "enabled = editable").size
        if (gated != 8) bad.add("BtSpeakerPanel 吃 editable 的控件不是 8 处（实际 $gated）")
        if (!panel.contains("enabled = maxVolume > 0 && editable")) {
            bad.add("BtSpeakerPanel 音量滑杆没有吃 editable")
        }
        return bad
    }

    /** 取 [from] 到 [to] 之间的源码片（局部 val lambda 没有 fun 体，bodyOf 定位不到） */
    private fun sliceBetween(src: String, from: String, to: String): String {
        val a = src.indexOf(from)
        if (a < 0) return ""
        val b = src.indexOf(to, a)
        if (b < 0) return ""
        return flatten(src.substring(a, b))
    }

    /**
     * 对焦锁定录制门必须**现读**录制态（审查 P1）：`pointerInput(Unit)` 首帧闭包会把组合期
     * `recording` 固化，录制中双击仍能上锁。空 = 干净。
     */
    private fun focusGateIssues(src: String): List<String> {
        val block = sliceBetween(src, "val lockFocusAt", "val toggleFocusLock")
        if (block.isEmpty()) return listOf("lockFocusAt 区块定位失败（守卫失去输入，不许算通过）")
        val bad = ArrayList<String>()
        if (!block.contains("recordingNow")) {
            bad.add("lockFocusAt 录制门不吃 recordingNow：首帧闭包会固化录制态，录制中双击仍能上锁")
        }
        if (block.contains("if (recording)")) bad.add("lockFocusAt 仍直接读组合期 recording（首帧快照）")
        // 能力闸必须排在录制门之前：手动档/不支持 AUTO 时优先给更准确的那条说明（审查 P3）
        val gateAt = block.indexOf("focusLockUnsupportedText")
        val recAt = block.indexOf("recordingNow")
        if (gateAt < 0 || recAt < 0 || gateAt > recAt) bad.add("能力闸必须排在录制门之前（提示准确性）")
        return bad
    }

    @Test
    fun `读数与镜头点按长按都开弹窗`() {        assertEquals("HudLayer 点按接线断点：\n${hudTapIssues(hud).joinToString("\n")}", emptyList<String>(), hudTapIssues(hud))
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
    fun `录制中可调白名单恰为八项`() {
        // 2026-10-27 B4：MONITOR 整组进白名单（只改 DISPLAY 槽，成片不变）
        // 2026-10-27 B7：GLOW 进白名单（scene layer：**会**改成片，但不触发重画、不产生重复帧）
        // 2026-10-28 F-E：TONE 按 Glow 同一先例进白名单（scene layer，29 §4.4-5 / §九-2）
        assertEquals(
            "可调白名单必须恰为 ISO/EV/WB/ZOOM/FOCUS/MONITOR/GLOW/TONE",
            setOf(
                PillKey.ISO, PillKey.EV, PillKey.WB, PillKey.ZOOM, PillKey.FOCUS,
                PillKey.MONITOR, PillKey.GLOW, PillKey.TONE
            ),
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
        assertTrue("监看叠加只上屏 ⇒ 录制中可调（25 号计划 B4）", pillEditableDuringRecording(PillKey.MONITOR))
        assertTrue("Glow 是 scene layer ⇒ 录制中可调（24 号计划 §1.6）", pillEditableDuringRecording(PillKey.GLOW))
        assertTrue("色调是 scene layer ⇒ 录制中可调（29 §九-2）", pillEditableDuringRecording(PillKey.TONE))
        assertFalse(pillEditableDuringRecording(PillKey.SHUTTER))
        assertFalse(pillEditableDuringRecording(PillKey.BITRATE))
        assertFalse(pillEditableDuringRecording(PillKey.BT))
    }

    @Test
    fun `监看弹窗常驻只上屏提示且录制中不再走只读提示`() {
        // 用户口径（25 §3.9.1）：MonitorPill 里常驻一句「监看叠加只上屏，不进成片」——
        // 录制中可调的前提是用户知道改了不改片；缺这句就是"静默改了却没说清"
        val monitor = flatten(bodyOf(pills, "MonitorPill"))
        assertTrue(
            "MonitorPill 缺少常驻提示 cam_monitor_preview_only",
            occurrences(monitor, "cam_monitor_preview_only").isNotEmpty()
        )
        // 字符串本身必须真的存在（引用拼错名字在遮蔽文本里看不出来）
        val strings = stringsCamera().readText(Charsets.UTF_8)
        assertTrue(
            "strings_camera.xml 缺 cam_monitor_preview_only",
            strings.contains("name=\"cam_monitor_preview_only\"") && strings.contains("监看叠加只上屏，不进成片")
        )
        // 只读总闸保留（对 MONITOR 自然不再触发），且必须排在常驻提示之后
        val previewAt = monitor.indexOf("cam_monitor_preview_only")
        val readOnlyAt = monitor.indexOf("cam_recording_readonly")
        assertTrue("MonitorPill 的只读总闸被删了（其它状态的弹窗靠它）", readOnlyAt >= 0)
        assertTrue("常驻提示必须排在只读提示之前（先讲清只上屏，再讲能不能改）", previewAt < readOnlyAt)
        // 突变自证：摘掉常驻提示必须被守卫抓到（`occurrences` 按词边界判，
        // 单纯把名字加个后缀（…_onlyX）不会被当成"还在"——那是 contains 的经典假绿）
        val mutated = pills.replace("cam_monitor_preview_only", "cam_monitor_preview_note")
        assertTrue(
            "摘掉常驻提示的突变必须被守卫抓到",
            occurrences(flatten(bodyOf(mutated, "MonitorPill")), "cam_monitor_preview_only").isEmpty()
        )
    }

    /** 文案资源文件（`res/values/strings_camera.xml`）：与 DegradedKeepGuardTest 同一手法 */
    private fun stringsCamera(): File {
        var d: File? = KotlinSourceScan.mainSourceFile("ui/CameraScreen.kt").parentFile
        while (d != null) {
            val f = File(d, "res/values/strings_camera.xml")
            if (f.isFile) return f
            d = d.parentFile
        }
        throw AssertionError("找不到 res/values/strings_camera.xml（文案资源被挪走/改名，守卫失去输入）")
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
    fun `十根滑杆都接刻度梯`() {
        assertEquals("滑杆梯接线缺件：\n${ladderIssues(pills).joinToString("\n")}", emptyList<String>(), ladderIssues(pills))
    }

    @Test
    fun `录制中 BT 页签也只读`() {
        assertEquals("BT 页签只读缺口：\n${btIssues(btPanel, audioPanel).joinToString("\n")}", emptyList<String>(), btIssues(btPanel, audioPanel))
    }

    @Test
    fun `对焦锁定录制门现读录制态`() {
        assertEquals("锁焦录制门问题：\n${focusGateIssues(screen).joinToString("\n")}", emptyList<String>(), focusGateIssues(screen))
        assertTrue(
            "CameraScreen 必须把录制态装进 rememberUpdatedState",
            screen.contains("val recordingNow by rememberUpdatedState(recording)")
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
        // 突变 4：任一根滑杆的梯被拆 -> 必红（防止删一处 ladder 退回连续滑杆而全绿）
        assertTrue(
            "滑杆梯被拆必须红",
            ladderIssues(pills.replace("ladder = isoLadder(lo, hi)", "ladderX = isoLadder(lo, hi)"))
                .isNotEmpty()
        )
        // 突变 5：BT 页签不再吃 editable -> 必红
        assertTrue(
            "BT 只读总闸被拆必须红",
            btIssues(btPanel.replace("enabled = editable", "enabled = true"), audioPanel).isNotEmpty()
        )
        // 突变 6：锁焦录制门改回首帧快照 -> 必红
        assertTrue(
            "锁焦录制门被拆必须红",
            focusGateIssues(screen.replace("recordingNow -> focusLockRecordingText", "recording -> focusLockRecordingText"))
                .isNotEmpty()
        )
    }
}
