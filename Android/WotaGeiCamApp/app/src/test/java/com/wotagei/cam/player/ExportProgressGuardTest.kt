package com.wotagei.cam.player

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-10 用户报障的结构守卫：A/B 段导出（含后渲染）只有进度条、没有百分比读数，
 * 弹层一关更是什么都不剩——准备期像无响应。钉三件事：
 * - 两个导出弹层（剪辑/选轨）导出中必须有百分比文案与「准备中」不确定态；
 * - 弹层外必须有一行常驻百分比小字（剪辑 + 选轨各一处，与光弧修复同族）；
 * - 三条新文案必须真的登记进 strings_player.xml（丢了编译会红，但显式锁住防资源被挪走后守卫空转）。
 */
class ExportProgressGuardTest {

    private fun screen(): String = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/PlayerScreen.kt"))

    @Test
    fun `剪辑导出弹层必须有百分比与准备中态`() {
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(screen(), "ClipExportPopup"))
        assertTrue("导出中必须给百分比读数", body.contains("R.string.player_clip_percent"))
        assertTrue("准备期必须给不确定进度条（静止 0% 条最像无响应）", body.contains("LinearProgressIndicator()"))
        assertTrue("准备期必须有「正在准备导出」文案", body.contains("R.string.player_clip_preparing"))
        // 尺要能红：摘掉百分比行后判据翻红
        val mutant = body.replace("R.string.player_clip_percent", "R.string.player_clip_title")
        assertFalse("摘掉百分比后守卫必须报红", mutant.contains("R.string.player_clip_percent"))
    }

    @Test
    fun `选轨导出弹层同样必须有百分比与准备中态`() {
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(screen(), "TrackExportPopup"))
        assertTrue("选轨导出中必须给百分比读数", body.contains("R.string.player_clip_percent"))
        assertTrue("选轨准备期必须给不确定进度条", body.contains("LinearProgressIndicator()"))
        assertTrue("选轨准备期必须有准备中文案", body.contains("R.string.player_clip_preparing"))
    }

    @Test
    fun `弹层外必须有常驻导出百分比小字`() {
        val all = KotlinSourceScan.flatten(screen())
        assertTrue(
            "剪辑导出常驻小字必须挂在 clipRunning 上（弹层关了也能看到进度）",
            Regex("""if \(exportState\.clipRunning\) \{[^}]*player_clip_running""").containsMatchIn(all)
        )
        assertTrue(
            "选轨导出常驻小字必须挂在 trackRunning 上",
            Regex("""if \(exportState\.trackRunning\) \{[^}]*player_clip_running""").containsMatchIn(all)
        )
        // 尺要能红：常驻小字摘掉后判据翻红
        val mutant = all.replace("R.string.player_clip_running", "R.string.player_arc_running")
        assertFalse("摘掉常驻小字后守卫必须报红", mutant.contains("R.string.player_clip_running"))
    }

    @Test
    fun `导出起跑必须自动弹开进度弹层_不得再主动关弹层`() {
        // 2026-11-10 用户二次纠偏（根因本体）：onExport 当初第一行就把弹层关了——
        // 「导出中重开面板可看进度」实际成了「面板永远关着」，百分比/进度条根本没机会出现。
        // 钉三处：跑起来那一刻自动弹开（剪辑+选轨）；onExport 不再主动关弹层。
        val all = KotlinSourceScan.flatten(screen())
        assertTrue(
            "剪辑导出跑起来那一刻必须自动弹开面板",
            Regex("""LaunchedEffect\(exportState\.clipRunning\) \{\s*if \(exportState\.clipRunning\) clipMenu = true""")
                .containsMatchIn(all)
        )
        assertTrue(
            "选轨导出跑起来那一刻必须自动弹开面板",
            Regex("""LaunchedEffect\(exportState\.trackRunning\) \{\s*if \(exportState\.trackRunning\) trackExportMenu = true""")
                .containsMatchIn(all)
        )
        val clipHandler = all.substringAfter("onExport = {").substringBefore("onCancel = { exportHost.cancelClip")
        assertFalse("剪辑 onExport 不得再主动关弹层", clipHandler.contains("clipMenu = false"))
        val trackHandler = all
            .substringAfter("onCancel = { exportHost.cancelClip() }")
            .substringAfter("onExport = {")
            .substringBefore("onCancel = { exportHost.cancelTrack")
        assertFalse("选轨 onExport 不得再主动关弹层", trackHandler.contains("trackExportMenu = false"))
        // 尺要能红：自动弹开改成关（回退旧病）后判据翻红
        val mutant = all.replace("if (exportState.clipRunning) clipMenu = true", "if (exportState.clipRunning) clipMenu = false")
        assertFalse(
            "自动弹开回退成关后守卫必须报红",
            Regex("""LaunchedEffect\(exportState\.clipRunning\) \{\s*if \(exportState\.clipRunning\) clipMenu = true""")
                .containsMatchIn(mutant)
        )
    }

    @Test
    fun `三条新文案必须登记进strings_player`() {
        val res = stringsXml().readText(Charsets.UTF_8)
        for (name in listOf("player_clip_percent", "player_clip_preparing", "player_clip_running")) {
            assertTrue("strings_player.xml 必须登记 $name", res.contains(name))
        }
    }

    /** 从 PlayerScreen.kt 所在的 src/main/java 向上取同源的 res/values/strings_player.xml（文案落点） */
    private fun stringsXml(): File {
        var dir: File? = KotlinSourceScan.mainSourceFile("player/PlayerScreen.kt").parentFile
        while (dir != null && dir.name != "java") dir = dir.parentFile
        assertTrue("定位不到 src/main/java 目录（守卫失去输入）", dir != null)
        return File(File(dir!!.parentFile, "res"), "values/strings_player.xml")
    }
}
