package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceFile
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自动停录巡检与停止宽限的**接线守卫**（2026-10-12，OpenCamera 健壮性三件套）。
 *
 * 判据核 [AutoStopRules] 的纯函数语义由 [AutoStopRulesTest] 钉；这里钉的是录制页接线——
 * 判据再对，巡检没挂进 pollTask、触发没走 stopAsync、原因没进 RecordResult、文案没接，
 * 用户看到的就是「悄悄自己停了/悄悄废片」，与没有这条机制无异。
 *
 * 「尺子自己能红」：每条判据配突变体（对源码文本做替换再断言；替换不中 = 结构本就不在，
 * 断言同样翻红）。
 */
class AutoStopWiringGuardTest {

    private fun maskedMain(): String = codeOnly(mainSourceText("ui/CameraScreen.kt"))

    private fun body(rel: String, funName: String): String =
        flatten(bodyOf(codeOnly(mainSourceText(rel)), funName))

    // ------------------------------------------------------------------ 巡检接线

    @Test
    fun `pollTask每拍调maybeAutoStop_且巡检节流与短路在位`() {
        val masked = maskedMain()
        val pollWindow = masked.substringAfter("val pollTask", missingDelimiterValue = "")
            .substringBefore("private fun maybeAutoStop", missingDelimiterValue = "")
        assertTrue(
            "pollTask 必须每拍调 maybeAutoStop()——巡检不挂拍 = 自动停录整条机制空转",
            pollWindow.contains("maybeAutoStop()")
        )
        val check = body("ui/CameraScreen.kt", "maybeAutoStop")
        assertTrue("巡检必须有节流闸（CHECK_INTERVAL_MS），不许 200ms 全速查电量+StatFs", check.contains("CHECK_INTERVAL_MS"))
        assertTrue("巡检必须先查短路（autoStopReason != null return），触发后不许每拍重复置位", check.contains("autoStopReason != null"))
        // 突变自证：摘掉 pollTask 里的调用（带缩进整行删，避免误伤定义行）
        val mutated = masked.replace("maybeAutoStop()", "Unit.let {}")
        val mutatedWindow = mutated.substringAfter("val pollTask", "").substringBefore("private fun")
        assertTrue("摘掉 pollTask 的巡检调用后尺子必须红", !mutatedWindow.contains("maybeAutoStop"))
    }

    @Test
    fun `巡检命中必须置原因并走stopAsync_读数薄壳在位`() {
        val check = body("ui/CameraScreen.kt", "maybeAutoStop")
        assertTrue(
            "巡检命中必须走 stopAsync() 正常收尾（保 trailer/sidecar/入库），不许自造拆解路径",
            check.contains("stopAsync()")
        )
        assertTrue(
            "电量读数薄壳必须被巡检消费（readBatteryPct），不许判据核悬空",
            check.contains("readBatteryPct()")
        )
        // 突变自证：触发改成只置位不停（收尾路径被拆）
        val masked = maskedMain().replace("if (autoStopReason != null) stopAsync()", "if (autoStopReason != null) Unit")
        assertTrue(
            "巡检触发不走 stopAsync 后尺子必须红",
            !flatten(bodyOf(masked, "maybeAutoStop")).contains("stopAsync()")
        )
    }

    @Test
    fun `自动停录原因必须消费进RecordResult并接两条文案`() {
        val masked = maskedMain()
        assertTrue(
            "stopInternal 必须把 autoStopReason 带进 RecordResult（否则 UI 永远不知道为什么停）",
            masked.contains("out.copy(autoStopReason = reason)")
        )
        // 文案分派自 2026-10-16 起住在呈现计划纯函数里（ui/RecordResultNotice.kt 的 recordTextPlan，
        // CameraScreen 只做渲染）——同一条"资源必须接线"断言换真源文件，不是弱化
        val plan = mainSourceText("ui/RecordResultNotice.kt")
        assertTrue("电量停录文案必须接线", plan.contains("cam_record_saved_auto_battery"))
        assertTrue("存储停录文案必须接线", plan.contains("cam_record_saved_auto_storage"))
        // 突变自证：结果不带原因（UI 文案分支死掉）
        val mutated = masked.replace("out.copy(autoStopReason = reason)", "out")
        assertTrue("结果不带自动停录原因后尺子必须红", !mutated.contains("autoStopReason = reason)"))
    }

    @Test
    fun `beginSession必须复位巡检状态_上一场不许带进下一场`() {
        val begin = body("ui/CameraScreen.kt", "beginSession")
        assertTrue("beginSession 必须复位 autoStopReason（上一场触发原因带进下一场 = 文案错报）", begin.contains("autoStopReason = null"))
        assertTrue("beginSession 必须复位巡检节流时刻（复用旧时刻 = 新会话首轮巡检被跳过）", begin.contains("lastAutoStopCheckAt = 0L"))
    }

    // ------------------------------------------------------------------ 停止宽限接线

    @Test
    fun `stopAsync必须带宽限_判据走AutoStopRules单一真源`() {
        val masked = maskedMain()
        assertTrue("stopAsync 必须经 stopWithGrace（直调 stopInternal = 宽限被拆）", masked.contains("handler.post { stopWithGrace() }"))
        assertTrue("宽限判据必须取自 AutoStopRules.stopGraceDelayMs（阈值单源，不许页内另算）", masked.contains("AutoStopRules.stopGraceDelayMs"))
        val grace = body("ui/CameraScreen.kt", "stopWithGrace")
        assertTrue("宽限函数必须只在 START 态生效（STOPPING/ERROR 重入不该再等）", grace.contains("RecordStatus.START"))
        // 突变自证：宽限判据被替换成常 0（延迟分支死掉，回退直收）
        val mutated = maskedMain().replace("AutoStopRules.stopGraceDelayMs(rec.elapsedMs)", "0L")
        val mutatedGrace = flatten(bodyOf(mutated, "stopWithGrace"))
        assertTrue("宽限判据被拆后尺子必须红", !mutatedGrace.contains("stopGraceDelayMs"))
    }

    @Test
    fun `readBatteryPct走粘性广播且读不出返回-1`() {
        val read = body("ui/CameraScreen.kt", "readBatteryPct")
        assertTrue("电量必须读 ACTION_BATTERY_CHANGED 粘性广播（无权限需求）", read.contains("ACTION_BATTERY_CHANGED"))
        assertTrue("读不出必须给 -1（判据侧按不停处理），不许给 0（0 会误停）", read.contains("-1"))
    }

    // ------------------------------------------------------------------ 相机设备丢失接线

    /**
     * 文案资源文件（`res/values/strings_camera.xml`）：主源码定位到模块目录后逐级上溯查找，
     * 与 RotateStallGuardTest 同一手法。找不到**必须抛**：守卫不能在没有输入的情况下算通过。
     */
    private fun stringsCamera(): File {
        var d: File? = mainSourceFile("ui/CameraScreen.kt").parentFile
        while (d != null) {
            val f = File(d, "res/values/strings_camera.xml")
            if (f.isFile) return f
            d = d.parentFile
        }
        throw AssertionError("找不到 res/values/strings_camera.xml（文案资源被挪走/改名，守卫失去输入）")
    }

    /**
     * 录制中相机设备被收回的观察钩子（2026-10-16 M1-01）：订阅设备态总线 → 转移折叠
     * （[CameraLostWatch]，采样计划的触发拍号由 AutoStopRulesTest 直断言）→ 命中调
     * `RecordRunner.onCameraLost` → 置独立原因 + 走既有 stopAsync 收尾 → 结果文案分派新资源。
     *
     * 判据再对，钩子没挂上（或挂了不调入口）这一整条机制就是空转——真机形态正是"画面定格
     * 却报已保存"。钩子本体按 `ctrl.state.collect` 到下一枚 LaunchedEffect 之间切片，
     * 防止断言被 import 行喂绿（AGENTS 点名的"声明行喂绿"）。
     */
    @Test
    fun `录制态设备丢失观察钩子必须接线到自动停录入口`() {
        val masked = maskedMain()
        assertTrue("必须存在设备态订阅 effect（LaunchedEffect(runner, ctrl)）", masked.contains("LaunchedEffect(runner, ctrl)"))
        val hook = masked.substringAfter("ctrl.state.collect", missingDelimiterValue = "")
            .substringBefore("LaunchedEffect(recStatus)", missingDelimiterValue = "")
        assertTrue("锚点丢失：切片没截到观察钩子（订阅被删/挪走）", hook.contains("onSample("))
        assertTrue(
            "转移折叠必须走 CameraLostWatch（快照/重复触发防护在它的折叠里）",
            masked.contains("remember { CameraLostWatch() }") && hook.contains("cameraLostWatch.onSample(")
        )
        assertTrue(
            "采样时的录制态必须现读录制控制器（status == START 才接受，非录制态的 CLOSE 归停止路径）",
            hook.contains("runner.status.value == RecordStatus.START")
        )
        assertTrue("命中必须调 runner.onCameraLost()", hook.contains("runner.onCameraLost()"))
        // 突变自证：摘掉入口调用（钩子照旧收采样但无人停录）
        val mutated = masked.replace("runner.onCameraLost()", "Unit")
        val mutatedHook = mutated.substringAfter("ctrl.state.collect", "").substringBefore("LaunchedEffect(recStatus)", "")
        assertTrue("摘掉 onCameraLost 调用后尺子必须红", !mutatedHook.contains("runner.onCameraLost()"))

        // 入口本体：置独立原因 + 走既有 stopAsync + 不许走 error 通道
        val lost = body("ui/CameraScreen.kt", "onCameraLost")
        assertTrue("入口必须置独立原因常量（复用自动停录文案分派）", lost.contains("AutoStopRules.REASON_CAMERA_LOST"))
        assertTrue("置原因后必须走既有 stopAsync 正常收尾（保 trailer/sidecar/入库）", lost.contains("stopAsync()"))
        assertFalse(
            "不许走 error 通道（failNow 会让 RecordResult.ok=false，用户以为整场白录）",
            lost.contains("failNow(")
        )
        // 文案：结果分派必须接新资源，资源必须存在且带时长占位符
        // （分派住在呈现计划纯函数里：ui/RecordResultNotice.kt，2026-10-16 起）
        assertTrue("已保存路径必须分派相机丢失文案", mainSourceText("ui/RecordResultNotice.kt").contains("cam_record_saved_auto_camera_lost"))
        val xml = stringsCamera().readText(Charsets.UTF_8)
        assertTrue(
            "资源 cam_record_saved_auto_camera_lost 必须存在",
            xml.contains("name=\"cam_record_saved_auto_camera_lost\"")
        )
        assertTrue(
            "资源文案与占位符逐字钉住（丢占位符 = 时长读不出来）",
            xml.contains(
                "name=\"cam_record_saved_auto_camera_lost\">相机被其它应用占用或被系统收回，录制已停止，已保存 %1\$s</string>"
            )
        )
    }
}
