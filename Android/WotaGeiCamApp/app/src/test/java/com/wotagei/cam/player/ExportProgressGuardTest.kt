package com.wotagei.cam.player

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 两类导出保留准备期、百分比和常驻反馈。任务身份及回程仲裁另由 ExportTasksTest 覆盖。 */
class ExportProgressGuardTest {
    private fun screen(): String = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/PlayerScreen.kt"))
    private fun body(name: String): String = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(screen(), name))

    @Test
    fun `两个弹层共用准备态与百分比且显示原任务来源`() {
        for (popup in listOf("ClipExportPopup", "TrackExportPopup")) {
            val popupBody = body(popup)
            assertTrue("$popup 必须把任务快照交给进度组件", popupBody.contains("ExportProgressContent(export, onCancel)"))
            assertTrue("$popup 必须显示实际导出源片", popupBody.contains("ExportSourceLabel(export.request)"))
        }
        val progress = body("ExportProgressContent")
        assertTrue(progress.contains("LinearProgressIndicator(modifier = progressWidth)"))
        assertTrue(progress.contains("R.string.player_clip_preparing"))
        assertTrue(progress.contains("R.string.player_clip_percent"))
        assertTrue(progress.contains("if (!task.cancelling)"))
    }

    @Test
    fun `两类常驻进度独立于弹层继续显示`() {
        val all = KotlinSourceScan.flatten(screen())
        for (task in listOf("clipExport", "trackExport")) {
            val line = Regex("""if \($task != null\) \{\s*ExportProgressLine\(\s*task = $task,""")
            assertTrue("$task 运行时始终给进度读数", line.containsMatchIn(all))
        }
        val line = body("ExportProgressLine")
        assertTrue(line.contains("R.string.player_clip_running"))
        assertTrue(line.contains("R.string.player_track_running"))
        assertTrue(line.contains("task.request.sourceUri == currentSourceUri"))
    }

    @Test
    fun `只用一个窗口选择并等待锚点就绪`() {
        val all = KotlinSourceScan.flatten(screen())
        assertTrue(all.contains("exportState.latestFor(sourceUri)"))
        assertTrue(all.contains("LaunchedEffect(latestExport)"))
        assertTrue(all.contains("latestExport.taskId > lastAutoOpened"))
        assertTrue(all.contains("exportMenu = latestExport.kind"))
        assertTrue(all.contains("val clipMenu = exportMenu == ExportKind.CLIP"))
        assertTrue(all.contains("clipMenu && clipAnchor != IntRect.Zero"))
        assertTrue(all.contains("exportMenu == ExportKind.TRACK && trackAnchor != IntRect.Zero"))
        for (submit in listOf("startClip", "startTrack")) {
            val handler = all.substringAfter("onExport = {").let {
                if (submit == "startClip") it else it.substringAfter("onExport = {")
            }.substringBefore("onCancel =")
            assertTrue("必须真正派发 $submit", handler.contains("exportHost.$submit("))
            assertFalse("派发时保留进度窗口", handler.contains("exportMenu = null"))
        }
    }

    @Test
    fun `完整展示后按任务确认消息`() {
        val all = KotlinSourceScan.flatten(screen())
        assertTrue(all.contains("exportState.results.firstOrNull()"))
        assertTrue(all.contains("repeatOnLifecycle(Lifecycle.State.RESUMED)"))
        assertTrue(all.contains("delay(2600) exportHost.acknowledge(item.request.taskId)"))
        assertFalse(all.contains("exportHost.clearResult()"))
    }

    @Test
    fun `必要文案已登记`() {
        val java = generateSequence(KotlinSourceScan.mainSourceFile("player/PlayerScreen.kt").parentFile) {
            it.parentFile
        }.first { it.name == "java" }
        val res = File(java.parentFile, "res/values/strings_player.xml").readText()
        for (name in listOf("player_clip_percent", "player_clip_preparing", "player_clip_running", "player_track_running")) {
            assertTrue("strings_player.xml 必须登记 $name", res.contains(name))
        }
    }
}
