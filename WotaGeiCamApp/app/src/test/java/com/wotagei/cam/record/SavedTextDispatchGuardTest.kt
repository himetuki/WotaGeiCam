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
 * 录制结果「已保存」文案的**原因句 + 附注句叠加**接线守卫（P3-1/P3-2 收口；2026-10-16
 * "录制结果如实化"后判定搬进纯函数计划）。
 *
 * 缺陷形态：旧写法是一条互斥 `when`（谁先命中谁独占整句）——
 * ① 相机被收回（CAMERA_LOST）分支排在降级/环境音附注之前，"相机丢失 + 收尾超时"这种真机
 * 常同场的组合里，"成片末尾可能不完整"的诊断被整句盖掉；
 * ② 任何附注都会盖掉 `uncommitted`（"相册登记未成功"——它是唯一提醒"这条成片可能暂时看不见"的话）。
 *
 * 修法与结构（2026-10-16 收口）：判定（原因句/附注键组合、混合结果走「已保存 N 段」）搬进
 * `ui/RecordResultNotice.kt` 的 [recordTextPlan] 纯函数（全表在 `ui/RecordTextPlanTest`），
 * `CameraScreen` 的出口只做资源渲染：`showTip(app.recordText(plan))`。本守卫钉两件事：
 * 1. **叠加结构在位**（两族分居原因块/附注块，谁也不盖谁；混合计数句必须在场）；
 * 2. **次序与占位符契约**（登记失败永远最前；无实参句零占位符、有实参句一一对应）。
 *
 * 「尺子自己能红」：每条判据配突变体（对源码文本做替换再断言；替换不中 = 结构本就不在，
 * 断言同样翻红）。
 */
class SavedTextDispatchGuardTest {

    private fun planRaw(): String = mainSourceText("ui/RecordResultNotice.kt")

    private fun planMasked(): String = codeOnly(planRaw())

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

    /** 原因句 when 块（`val reason = when` 到附注列表之前） */
    private fun reasonBlock(m: String): String =
        m.substringAfter("val reason = when", "").substringBefore("val notes", "")

    /** 附注列表块（`val notes = buildList` 到计划构造之前） */
    private fun noteBlock(m: String): String =
        m.substringAfter("val notes = buildList", "").substringBefore("return RecordTextPlan", "")

    /**
     * 判定本体（P3-1 + 混合如实化）：渲染侧叠加拼装在位；原因句只认 `autoStopReason` 与段数
     * （降级/环境音/登记都归附注族，回到互斥 when = 遮蔽复发）；混合结果（非干净成功且多段）
     * 的原因句必须是「已保存 N 段」；相机丢失常同场的那两件（降级、环境音）必须是条件 add。
     */
    private fun composeHolds(plan: String, screen: String): Boolean {
        // 渲染侧：计划在场 + 原因/附注经同一出口叠加（退回单发原因句 = 附注整族丢）
        if (!screen.contains("recordTextPlan(result)")) return false
        if (!screen.contains("showTip(app.recordText(plan))")) return false
        if (!plan.contains("Context.recordText(plan: RecordTextPlan)")) return false
        val reason = reasonBlock(plan)
        val notes = noteBlock(plan)
        if (reason.isEmpty() || notes.isEmpty()) return false
        if (!reason.contains("result.autoStopReason")) return false
        if (!reason.contains("REASON_CAMERA_LOST")) return false
        // 原因句只许认 autoStopReason 与段数：降级/环境音/登记出现在原因句里 = 互斥遮蔽复发的形态
        if (reason.contains("degradedCode") || reason.contains("ambientLost")) return false
        if (reason.contains("uncommitted")) return false
        // 混合如实化：非干净成功且多段 ⇒ 原因句走「已保存 N 段（单段沿用现有措辞）」
        if (!reason.contains("partial && result.parts.size > 1")) return false
        if (!reason.contains("R.string.cam_record_saved_parts")) return false
        // 附注反过来也不许盖原因句
        if (notes.contains("autoStopReason")) return false
        // 附注必须是"条件 add"而非整句选择（两件同时命中各自出场）
        if (!notes.contains("if (result.degradedCode != null) add(")) return false
        if (!notes.contains("if (result.ambientLost) add(")) return false
        if (!notes.contains("R.string.cam_record_saved_degraded")) return false
        if (!notes.contains("R.string.cam_record_saved_ambient_lost")) return false
        // 非干净成功的软附注必须经 partialSavedNote 接线，两枚资源住在它体内
        if (!notes.contains("partialSavedNote(")) return false
        val soft = plan.substringAfter("fun partialSavedNote", "").substringBefore("fun Context.recordText", "")
        if (!soft.contains("R.string.cam_record_saved_note_rotate")) return false
        return soft.contains("R.string.cam_record_saved_note_tail")
    }

    @Test
    fun `原因句与附注句必须叠加_相机丢失不许再盖降级与环境音`() {
        assertTrue(
            "叠加结构（计划纯函数 + 屏幕侧叠加渲染 + 两族分居）缺一即红，逐条判据见 composeHolds",
            composeHolds(planMasked(), screenMasked())
        )
        // 突变自证 1：屏幕侧退回只发原因句（附注整族丢）
        val screenRaw = mainSourceText("ui/CameraScreen.kt")
        val m1 = screenRaw.replace("showTip(app.recordText(plan))", "showTip(app.recordText(plan.reason))")
        assertFalse("退回单发原因句后尺子必须红", composeHolds(planMasked(), codeOnly(m1)))
        // 突变自证 2：降级附注被摘（相机丢失同场的那件诊断丢）
        val planRaw = planRaw()
        val m2 = planRaw.replace(
            "if (result.degradedCode != null) add(RecordTextLine(R.string.cam_record_saved_degraded))",
            "Unit"
        )
        assertFalse("拆掉降级附注后尺子必须红", composeHolds(codeOnly(m2), screenMasked()))
        // 突变自证 3：降级判据挪回原因句（互斥遮蔽复发的形态）
        val m3 = planRaw.replace(
            "else -> RecordTextLine(R.string.cam_record_saved, listOf(dur))",
            "result.degradedCode != null -> RecordTextLine(R.string.cam_record_saved_degraded, listOf(dur))"
        )
        assertFalse("降级判据挪回原因句后尺子必须红", composeHolds(codeOnly(m3), screenMasked()))
        // 突变自证 4：混合计数句整条摘掉（回到"混合结果报失败"的缺陷形态）
        val m4 = planRaw.replace(
            "partial && result.parts.size > 1 ->",
            "false ->"
        )
        assertFalse("拆掉混合计数句后尺子必须红", composeHolds(codeOnly(m4), screenMasked()))
    }

    /**
     * 判定本体（P3-2）：`uncommitted > 0` 的附注必须在场，且排在附注族最前——它是唯一"这条成片
     * 可能暂时看不见"的提醒，不许被任何并列附注盖掉。
     */
    private fun uncommittedFirstHolds(plan: String): Boolean {
        val notes = noteBlock(plan)
        if (!notes.contains("if (result.uncommitted > 0) add(RecordTextLine(R.string.cam_record_saved_uncommitted))")) return false
        val at = notes.indexOf("R.string.cam_record_saved_uncommitted")
        // 非干净成功的软附注经 partialSavedNote(result.error) 在场（两枚资源名住在它体内，
        // 不在附注块的字面里——按调用点判在场）
        val partial = notes.indexOf("partialSavedNote(")
        val degraded = notes.indexOf("R.string.cam_record_saved_degraded")
        val ambient = notes.indexOf("R.string.cam_record_saved_ambient_lost")
        val loss = notes.indexOf("R.string.cam_record_saved_loss")
        if (partial < 0 || degraded < 0 || ambient < 0 || loss < 0) return false
        return at < partial && at < degraded && at < ambient && at < loss
    }

    @Test
    fun `未登记附注永远可见_排在附注族最前`() {
        assertTrue("uncommitted 附注必须在场且排在其余附注之前", uncommittedFirstHolds(planMasked()))
        val planRaw = planRaw()
        val line = "if (result.uncommitted > 0) add(RecordTextLine(R.string.cam_record_saved_uncommitted))"
        // 突变自证 1：uncommitted 附注被摘（唯一提醒丢失）
        val m1 = planRaw.replace(line, "Unit")
        assertFalse("摘掉 uncommitted 附注后尺子必须红", uncommittedFirstHolds(codeOnly(m1)))
        // 突变自证 2：uncommitted 挪到附注族末尾（顺序设计被改）
        val m2 = planRaw.replace(line, "").replace(
            "add(RecordTextLine(R.string.cam_record_saved_loss, listOf(rotateStallLostSeconds(result.lostMs))))",
            "add(RecordTextLine(R.string.cam_record_saved_loss, listOf(rotateStallLostSeconds(result.lostMs))))\n        $line"
        )
        assertFalse("uncommitted 挪出最前后尺子必须红", uncommittedFirstHolds(codeOnly(m2)))
    }

    /**
     * 占位符-实参一一对应：附注的无实参句走单参 `getString(id)`——单参不做 String.format，
     * 资源里的占位符不会被替换而是**原样渲染**成字面量（不会抛异常；抛 IllegalArgumentException
     * 的是"带实参而资源占位符不够"那一类）。所以无实参句必须零占位符、有实参句必须与实参个数
     * 一一对应（`cam_record_saved_parts` 两个、`cam_record_saved_loss` 一个、四条原因句各一个）。
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
            "cam_record_saved_ambient_lost",
            // 混合如实化的两条软附注：无实参消费（附注是 add(RecordTextLine(id)) 形态）
            "cam_record_saved_note_tail",
            "cam_record_saved_note_rotate"
        )) {
            assertFalse(
                "无实参附注 $name 不许带占位符（单参 getString 不格式化，% 会原样显示给用户）",
                valueOf(name).contains('%')
            )
        }
        assertEquals(
            "loss 附注恰好一个秒数占位符（add 侧恰好一个实参）",
            "；其中约 %1\$d 秒因换段受阻丢失",
            valueOf("cam_record_saved_loss")
        )
        assertEquals(
            "混合计数句恰好两个占位符（段数 + 时长，与 listOf(parts.size, dur) 一一对应）；" +
                "措辞必须含「已保存 %1\$d 段」——单段不走本条（「已保存 1 段」是口语倒退）",
            "已保存 %1\$d 段，时长 %2\$s",
            valueOf("cam_record_saved_parts")
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

    /**
     * 「含已保存分段」分支的**接线钉**（2026-10-16 录制结果如实化）：相册刷新/分段归组/结果文案
     * 全挂在同一条件上——`recordTextPlan(result) != null`（纯函数语义 = parts 非空或 ok）。
     * 旧实现 `if (result.ok)` 把失败/混合/join 超时路径下的已 commit 片段挡在相册刷新之外
     *（用户要退出重进才看见；"失败/混合结果不刷新相册"正是本轮三合一的第二条）。
     */
    @Test
    fun `相册刷新必须认已保存分段而不是只认ok`() {
        val collect = screenMasked().substringAfter("runner.result.collect")
            .substringBefore("runner.clearResult()")
        assertTrue("锚点丢失：collect 切片为空", collect.isNotEmpty())
        assertTrue(
            "结果分派必须走呈现计划（plan 非 null = 含已保存分段）",
            collect.contains("val plan = recordTextPlan(result)")
        )
        val invalidateAt = collect.indexOf("repo.invalidate()")
        val okAt = collect.indexOf("if (result.ok)")
        assertTrue("凡含已保存分段必须刷新相册", invalidateAt >= 0)
        assertTrue("ok 判定必须排在刷新之后（只有练习交付与失败清桥态认 ok）", okAt > invalidateAt)
        assertTrue(
            "分段归组也必须排在刷新之后、同一分支内（混合结果的分段同样要归组）",
            collect.indexOf("SeriesStore.record(") > invalidateAt
        )
        // 突变自证：把刷新挪回 ok 分支（旧缺陷形态：失败/混合路径不刷新相册）——
        // 真身不变量是"刷新排在 ok 判定之前"，突变后不变量翻假（守卫生效）
        val raw = mainSourceText("ui/CameraScreen.kt")
        val mutated = raw.replace("repo.invalidate()", "if (result.ok) { repo.invalidate() }")
        assertTrue("突变体必须真的改了源码", mutated != raw)
        val mutatedCollect = codeOnly(mutated).substringAfter("runner.result.collect")
            .substringBefore("runner.clearResult()")
        val mOkAt = mutatedCollect.indexOf("if (result.ok)")
        val mInvalidateAt = mutatedCollect.indexOf("repo.invalidate()")
        assertTrue(
            "刷新退回只认 ok 后不变量必须翻假（ok 判定先于刷新出现）",
            mOkAt >= 0 && mOkAt < mInvalidateAt
        )
    }
}
