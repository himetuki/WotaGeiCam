package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceFile
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 录制结果「已保存」文案的**原因句 + 附注句叠加**接线守卫（P3-1/P3-2 收口）。
 *
 * 缺陷形态：旧写法是一条互斥 `when`（谁先命中谁独占整句）——
 * ① 相机被收回（CAMERA_LOST）分支排在降级/环境音附注之前，"相机丢失 + 收尾超时"这种真机
 * 常同场的组合里，"成片末尾可能不完整"的诊断被整句盖掉；
 * ② 任何附注都会盖掉 `uncommitted`（"相册登记未成功"——它是唯一提醒"这条成片可能暂时看不见"的话）。
 *
 * 修法（结构级）：原因句（why-stopped，时长只在这里带一次）与附注句（保存质量，带前置"；"的
 * 后缀、可任意并列）分开拼：`showTip(reasonText + noteText)`。判定语义（哪个标志出哪件事）
 * 逐字不变，变的只是"同时命中时谁也不盖谁"。
 *
 * 「尺子自己能红」：每条判据配突变体（对源码文本做替换再断言；替换不中 = 结构本就不在，
 * 断言同样翻红）。
 */
class SavedTextDispatchGuardTest {

    private fun screenMasked(): String = codeOnly(mainSourceText("ui/CameraScreen.kt"))

    /** 文案资源文件（`res/values/strings_camera.xml`）：与 DegradedKeepGuardTest 同一手法 */
    private fun stringsCamera(): File {
        var d: File? = mainSourceFile("ui/CameraScreen.kt").parentFile
        while (d != null) {
            val f = File(d, "res/values/strings_camera.xml")
            if (f.isFile) return f
            d = d.parentFile
        }
        throw AssertionError("找不到 res/values/strings_camera.xml（文案资源被挪走/改名，守卫失去输入）")
    }

    /** 原因句 when 块（`val reasonText = when` 到附注叠加器之前） */
    private fun reasonBlock(m: String): String =
        m.substringAfter("val reasonText = when", "").substringBefore("val noteText", "")

    /** 附注叠加器块（`val noteText = buildString` 到拼装出口之前） */
    private fun noteBlock(m: String): String =
        m.substringAfter("val noteText = buildString", "").substringBefore("showTip(reasonText + noteText)", "")

    /**
     * 判定本体（P3-1）：叠加拼装在位；原因句只认 `autoStopReason`（降级/环境音/登记/丢失都归
     * 附注族，回到互斥 when = 遮蔽复发）；相机丢失常同场的那两件（降级、环境音）必须是条件 append。
     */
    private fun composeHolds(m: String): Boolean {
        if (!m.contains("val reasonText = when (result.autoStopReason)")) return false
        if (!m.contains("showTip(reasonText + noteText)")) return false
        val reason = reasonBlock(m)
        val notes = noteBlock(m)
        if (reason.isEmpty() || notes.isEmpty()) return false
        if (!reason.contains("REASON_CAMERA_LOST")) return false
        // 原因句只许认 autoStopReason：降级/环境音/登记出现在原因句里 = 互斥遮蔽复发的形态
        if (reason.contains("degradedCode") || reason.contains("ambientLost")) return false
        if (reason.contains("uncommitted")) return false
        // 附注反过来也不许盖原因句
        if (notes.contains("autoStopReason")) return false
        // 附注必须是"条件 append"而非整句选择（两件同时命中各自出场）
        if (!notes.contains("if (result.degradedCode != null) append(")) return false
        if (!notes.contains("if (result.ambientLost) append(")) return false
        if (!notes.contains("app.getString(R.string.cam_record_saved_degraded)")) return false
        return notes.contains("app.getString(R.string.cam_record_saved_ambient_lost)")
    }

    @Test
    fun `原因句与附注句必须叠加_相机丢失不许再盖降级与环境音`() {
        val raw = mainSourceText("ui/CameraScreen.kt")
        assertTrue(
            "叠加结构（原因 when + 附注 buildString + showTip 拼装）缺一即红，逐条判据见 composeHolds",
            composeHolds(screenMasked())
        )
        // 突变自证 1：拼装退回只发原因句（附注整族丢）
        val m1 = raw.replace("showTip(reasonText + noteText)", "showTip(reasonText)")
        assertFalse("退回单发原因句后尺子必须红", composeHolds(codeOnly(m1)))
        // 突变自证 2：降级附注被摘（相机丢失同场的那件诊断丢）
        val m2 = raw.replace(
            "if (result.degradedCode != null) append(app.getString(R.string.cam_record_saved_degraded))",
            "Unit"
        )
        assertFalse("拆掉降级附注后尺子必须红", composeHolds(codeOnly(m2)))
        // 突变自证 3：降级判据挪回原因 when（互斥遮蔽复发的形态）
        val m3 = raw.replace(
            "else -> app.getString(R.string.cam_record_saved, dur)",
            "result.degradedCode != null -> app.getString(R.string.cam_record_saved_degraded)"
        )
        assertFalse("降级判据挪回原因句后尺子必须红", composeHolds(codeOnly(m3)))
    }

    /**
     * 判定本体（P3-2）：`uncommitted > 0` 的附注必须在场，且排在附注族最前——它是唯一"这条成片
     * 可能暂时看不见"的提醒，不许被任何并列附注盖掉。
     */
    private fun uncommittedFirstHolds(m: String): Boolean {
        val notes = noteBlock(m)
        if (!notes.contains("if (result.uncommitted > 0) append(app.getString(R.string.cam_record_saved_uncommitted))")) return false
        val at = notes.indexOf("R.string.cam_record_saved_uncommitted")
        val degraded = notes.indexOf("R.string.cam_record_saved_degraded)")
        val ambient = notes.indexOf("R.string.cam_record_saved_ambient_lost)")
        val loss = notes.indexOf("R.string.cam_record_saved_loss")
        if (degraded < 0 || ambient < 0 || loss < 0) return false
        return at < degraded && at < ambient && at < loss
    }

    @Test
    fun `未登记附注永远可见_排在附注族最前`() {
        val raw = mainSourceText("ui/CameraScreen.kt")
        assertTrue("uncommitted 附注必须在场且排在降级/环境音/丢失之前", uncommittedFirstHolds(screenMasked()))
        val line = "if (result.uncommitted > 0) append(app.getString(R.string.cam_record_saved_uncommitted))"
        // 突变自证 1：uncommitted 附注被摘（唯一提醒丢失）
        val m1 = raw.replace(line, "Unit")
        assertFalse("摘掉 uncommitted 附注后尺子必须红", uncommittedFirstHolds(codeOnly(m1)))
        // 突变自证 2：uncommitted 挪到附注族末尾（顺序设计被改）
        val m2 = raw.replace(line, "")
            .replace(
                "append(app.getString(R.string.cam_record_saved_loss, rotateStallLostSeconds(result.lostMs)))",
                "append(app.getString(R.string.cam_record_saved_loss, rotateStallLostSeconds(result.lostMs)))\n                    $line"
            )
        assertFalse("uncommitted 挪出最前后尺子必须红", uncommittedFirstHolds(codeOnly(m2)))
    }

    /**
     * 占位符-实参一一对应（P3-1 结构变更后的新风险面）：附注是 `append(app.getString(id))`
     * **无实参**消费的——单参 getString(id) 不做 String.format，资源里的占位符不会被替换，而是
     * **原样渲染成字面量**给用户看（不会抛异常；抛 IllegalArgumentException 的是"带实参而资源
     * 占位符不够"那一类）。所以三条无参附注必须零占位符；loss 附注恰好一个秒数占位符
     *（append 侧恰好一个实参，两边一一对应）。
     */
    @Test
    fun `附注占位符与实参一一对应`() {
        val xml = stringsCamera().readText(Charsets.UTF_8)
        fun valueOf(name: String): String {
            val tag = "name=\"$name\">"
            val at = xml.indexOf(tag)
            assertTrue("资源 $name 必须存在", at >= 0)
            return xml.substring(at + tag.length).substringBefore("</string>")
        }
        for (name in listOf(
            "cam_record_saved_uncommitted",
            "cam_record_saved_degraded",
            "cam_record_saved_ambient_lost"
        )) {
            assertFalse(
                "无实参附注 $name 不许带占位符（单参 getString 不格式化，% 会原样显示给用户）",
                valueOf(name).contains('%')
            )
        }
        assertEquals(
            "loss 附注恰好一个秒数占位符（append 侧恰好一个实参）",
            "；其中约 %1\$d 秒因换段受阻丢失",
            valueOf("cam_record_saved_loss")
        )
        // 原因句四条照旧各带一个时长占位符（丢 = 时长读不出来）
        for (name in listOf(
            "cam_record_saved",
            "cam_record_saved_auto_battery",
            "cam_record_saved_auto_storage",
            "cam_record_saved_auto_camera_lost"
        )) {
            assertTrue("原因句 $name 必须带 %1\$s：${valueOf(name)}", valueOf(name).contains("%1\$s"))
        }
    }
}
