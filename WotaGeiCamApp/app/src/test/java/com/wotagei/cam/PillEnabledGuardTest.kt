package com.wotagei.cam

import com.wotagei.cam.core.RangeI
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.ui.dialog.shutterItems
import com.wotagei.cam.ui.widget.shutterLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「不支持的档不许进快门梯」＋「灰显通道仍在」的守卫（2026-10-02 真机事故 → 2026-10-17 刻度化迁移）。
 *
 * 事故口径：判据层已把 1/24 判成 supported=false（本机 24fps 判非精确档、实际 fps 上界 30），
 * 但灰显没有执行力。快捷 chip 删除后，不支持的档**直接不进梯**（消失而非灰 chip），
 * 闸从「chip enabled + onPick 第二道守卫」变成「构建梯时就过滤 supported」。
 *
 * 本类钉三件事（全部可证伪）：
 * 1. **行为**：事故参数下 1/24 不在 supported 集合，因而也不在 `shutterLadder` 里；
 * 2. **同源**：强制档（1/24、1/25）的 `※` 标签仍来自 `shutterItems`（ShutterPill 用原始
 *    TierItem 反查标签，不自己拼）；
 * 3. **源码接线**：ShutterPill 必须 `filter { it.supported }` + `shutterLadder(`，且不得再有
 *    `PillChoices`（快捷 chip 已删）；PillChoices / PillToggles 的总闸仍把 opt.enabled 传进 WotaChip。
 */
class PillEnabledGuardTest {

    /** 与 ForcedShutterTest 同源：档位纳秒值现算 1e9/分母，不写第二份字面量 */
    private val ns24 = 1_000_000_000L / 24
    private val ns25 = 1_000_000_000L / 25

    @Test
    fun `事故参数下不支持档不得进快门梯`() {
        // 与真机一致：设备曝光上限只到 1/30（33ms），24fps 非精确档退成 [15,30] ⇒ 实际 fps 上界 30
        val dev = RangeI(4_000_000, 33_000_000)
        val all = shutterItems(dev, fpsHi = 30, approxMark = "※")
        val supported = all.filter { it.supported }
        assertFalse("30fps 帧周期容不下 1/24，必须被 supported 判据挡掉", supported.any { it.value == ns24.toInt() })
        assertFalse("1/25 在 30fps 下超出帧周期，同样不进梯", supported.any { it.value == ns25.toInt() })

        val ladder = shutterLadder(supported.map { it.value.toFloat() }, 4_000_000f, 41_666_666f)
        assertEquals("梯必须与 supported 集合等长（不额外塞档）", supported.size, ladder.size)
        assertFalse("1/24 不得出现在梯里", ladder.any { it == ns24.toFloat() })
        assertTrue("正对照：1/50 在梯里", ladder.any { it.toLong() == 1_000_000_000L / 50 })
    }

    @Test
    fun `强制档标签仍由shutterItems给出`() {
        // 实际生效 fps 上界 24（帧周期 41.67ms）：设备表外也要能选到 1/24，并打 ※
        val items = shutterItems(RangeI(4_000_000, 33_000_000), fpsHi = 24, approxMark = "※")
            .filter { it.supported }
        assertEquals("1/24※", items.first { it.value == ns24.toInt() }.label)
        assertEquals("1/25※", items.first { it.value == ns25.toInt() }.label)
        // 梯里必须真有这两档（由 1/24 帧周期决定的上限把它们纳入）
        val ladder = shutterLadder(items.map { it.value.toFloat() }, 4_000_000f, 41_666_666f)
        assertEquals(items.size, ladder.size)
    }

    /** ShutterPill 的源码接线（缺一段就是灰度/标签口径分叉） */
    private fun shutterWiringIssues(pills: String): List<String> {
        val body = KotlinSourceScan.flatten(bodyOf(pills, "ShutterPill"))
        val bad = ArrayList<String>()
        if (!body.contains("shutterItems(")) bad.add("ShutterPill 不再从 shutterItems 取档（※ 标签失去真源）")
        if (!body.contains(".filter { it.supported }")) bad.add("快门梯没过滤 supported（不支持档会进梯）")
        if (!body.contains("shutterLadder(")) bad.add("ShutterPill 没给滑杆传快门梯")
        if (body.contains("PillChoices(")) bad.add("ShutterPill 仍有档位 chip（快捷数值按钮应已删除）")
        if (!body.contains("?.label")) bad.add("读数标签没从 shutterItems 的 TierItem 反查（强制档 ※ 会丢）")
        return bad
    }

    private fun greyChannelIssues(popup: String): List<String> {
        val bad = ArrayList<String>()
        for (name in listOf("PillChoices", "PillToggles")) {
            val body = KotlinSourceScan.occurrences(bodyOf(popup, name), "opt.enabled && enabled")
            if (body.isEmpty()) {
                bad.add("$name 没把 opt.enabled 与整行 enabled 一起传给 WotaChip（灰显/只读失执行力）")
            }
        }
        return bad
    }

    @Test
    fun `快门梯接线与灰显通道在位`() {
        val pills = codeOnly(KotlinSourceScan.mainSourceText("ui/CameraPills.kt"))
        val popup = codeOnly(KotlinSourceScan.mainSourceText("ui/design/PillPopup.kt"))
        assertEquals("快门梯接线断点：\n${shutterWiringIssues(pills).joinToString("\n")}", emptyList<String>(), shutterWiringIssues(pills))
        assertEquals("灰显通道断点：\n${greyChannelIssues(popup).joinToString("\n")}", emptyList<String>(), greyChannelIssues(popup))
    }

    @Test
    fun `尺子自己能红`() {
        val pills = codeOnly(KotlinSourceScan.mainSourceText("ui/CameraPills.kt"))
        val popup = codeOnly(KotlinSourceScan.mainSourceText("ui/design/PillPopup.kt"))
        assertEquals("良品必须先真的绿", emptyList<String>(), shutterWiringIssues(pills))
        assertTrue(
            "去掉 supported 过滤必须红",
            shutterWiringIssues(pills.replace(".filter { it.supported }", "")).any { it.contains("supported") }
        )
        assertTrue(
            "把梯换成 PillChoices 必须红",
            shutterWiringIssues(pills.replace("ladder = ladder", "ladder = ladder; PillChoices(")).isNotEmpty()
        )
        assertTrue(
            "灰显总闸被拆必须红",
            greyChannelIssues(popup.replace("opt.enabled && enabled", "opt.enabled")).isNotEmpty()
        )
    }
}
