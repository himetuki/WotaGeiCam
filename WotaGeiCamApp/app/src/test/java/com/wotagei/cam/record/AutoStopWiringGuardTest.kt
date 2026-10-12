package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue("电量停录文案必须接线", masked.contains("cam_record_saved_auto_battery"))
        assertTrue("存储停录文案必须接线", masked.contains("cam_record_saved_auto_storage"))
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
}
