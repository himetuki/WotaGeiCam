package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.ui.design.pillSliderInteractive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B11：滑杆/档位控件的**禁用态消费**守卫（2026-11-19，06 文档 P3-2；TONE 空梯修复 1676a8a 的残余口径）。
 *
 * 两件事：
 * 1. `PillSlider` 在「梯存在但不足两点」时滑杆早就禁拖，读数却仍按外层 `enabled` 亮色——
 *    看着能调、拖了没反应。现在 Slider 的 `enabled` 与数值颜色**共用**一枚判据
 *    [pillSliderInteractive]，本类先打这枚纯函数，再用源码守卫钉住"颜色不许退回只读 enabled"；
 * 2. `TierPicker` 的外层 `enabled` 早先只折进 `supported`（灰化），`TierPill` 的
 *    `combinedClickable` 没有 `enabled` 消费者——灰着却能改值。现在 `enabled` 一路传到
 *    `combinedClickable(enabled = ...)`、灰化与文字暗阶，滑杆切换入口也一并禁用。
 *
 * 全部判据都配了突变自证：把修法退回去，守卫必须红。
 */
class PillSliderDisabledGuardTest {

    private val popupSrc by lazy { mainSourceText("ui/design/PillPopup.kt") }
    private val paramSrc by lazy { mainSourceText("ui/widget/ParamSlider.kt") }

    // ------------------------------------------------------------------ 纯函数：这一帧能不能拖

    @Test
    fun `空梯不足两点时滑杆不可交互`() {
        assertFalse(
            "梯 0 点（空梯）：禁用而不是静默退回连续滑杆",
            pillSliderInteractive(enabled = true, usableDomain = true, tickCount = 0, ladderOnly = true)
        )
        assertFalse(
            "梯 1 点（定焦只剩 1×）：同上，禁用",
            pillSliderInteractive(enabled = true, usableDomain = true, tickCount = 1, ladderOnly = true)
        )
        assertFalse(
            "梯为 null 但 ladderOnly=true（调用方明确要梯）：禁用",
            pillSliderInteractive(enabled = true, usableDomain = true, tickCount = null, ladderOnly = true)
        )
        assertTrue(
            "梯 ≥2 点：可拖（刻度空间）",
            pillSliderInteractive(enabled = true, usableDomain = true, tickCount = 2, ladderOnly = true)
        )
    }

    @Test
    fun `连续档与禁用档的口径`() {
        assertTrue(
            "设置页关掉吸附（ladderOnly=false、无梯）且域可用：连续滑杆可拖",
            pillSliderInteractive(enabled = true, usableDomain = true, tickCount = null, ladderOnly = false)
        )
        assertFalse(
            "域退化（hi<=lo）且无梯：禁用",
            pillSliderInteractive(enabled = true, usableDomain = false, tickCount = null, ladderOnly = false)
        )
        assertFalse(
            "调用方 enabled=false 时不管梯怎样都禁用",
            pillSliderInteractive(enabled = false, usableDomain = true, tickCount = 3, ladderOnly = true)
        )
        // 突变自证：只看 enabled && usable 的实现会把空梯判成可拖（这正是 B11 的错法）
        val mutant: (Boolean, Boolean, Int?, Boolean) -> Boolean = { e, u, _, _ -> e && u }
        assertTrue(
            "忽略梯/禁用位的突变体必须与真身不同，否则这组用例没有鉴别力",
            mutant(true, true, 0, true) != pillSliderInteractive(true, true, 0, true)
        )
    }

    // ------------------------------------------------------------------ 接线：颜色与 Slider 同源

    @Test
    fun `数值颜色必须与Slider共用同一枚禁用判据`() {
        val body = flatten(bodyOf(codeOnly(popupSrc), "PillSlider"))
        assertTrue(
            "PillSlider 必须先算出这一帧的交互态",
            body.contains("val interactive = pillSliderInteractive(enabled, usable, ticks?.size, ladderOnly)")
        )
        assertTrue("Slider 的 enabled 必须读这枚交互态", body.contains("enabled = interactive,"))
        assertTrue(
            "数值颜色必须读这枚交互态（空梯时灰显）",
            body.contains("color = if (interactive) WotaColor.textHi else WotaColor.textLo")
        )
        assertFalse(
            "数值颜色不许再只读外层 enabled（B11 的原错法）",
            body.contains("color = if (enabled) WotaColor.textHi")
        )
        // 突变自证：把颜色退回只读 enabled → 守卫必须红
        val mutant = popupSrc.replace(
            "color = if (interactive) WotaColor.textHi else WotaColor.textLo",
            "color = if (enabled) WotaColor.textHi else WotaColor.textLo"
        )
        assertTrue("突变体必须真的改到了源码", mutant != popupSrc)
        assertTrue(
            "颜色退回只读 enabled 时守卫必须变红",
            flatten(bodyOf(codeOnly(mutant), "PillSlider")).contains("color = if (enabled) WotaColor.textHi")
        )
    }

    @Test
    fun `TierPicker的外层禁用必须传到候选药丸`() {
        val picker = flatten(bodyOf(codeOnly(paramSrc), "TierPicker"))
        assertTrue(
            "TierPicker 必须把 enabled 原样传给候选药丸（不许再折进 supported）",
            picker.contains("supported = item.supported, enabled = enabled")
        )
        val pill = flatten(bodyOf(codeOnly(paramSrc), "TierPill"))
        assertTrue(
            "候选药丸的 combinedClickable 必须有 enabled 消费者（禁用时连点击都不发生）",
            pill.contains("combinedClickable( enabled = enabled,")
        )
        assertTrue(
            "灰化要同时看 supported 与 enabled",
            pill.contains("if (supported && enabled) 1f else 0.35f")
        )
        assertTrue(
            "禁用时文字退到暗阶，不许只靠 alpha",
            pill.contains("!enabled -> WotaColor.textLo")
        )
        val header = flatten(bodyOf(codeOnly(paramSrc), "ValueHeader"))
        assertTrue(
            "禁用时「药丸/滑杆」入口也不许切换",
            header.contains("IconButton(onClick = onToggleSlider, modifier = Modifier.size(28.dp), enabled = enabled)")
        )
        // 突变自证：把消费者删回默认 true → 守卫必须红
        val mutant = paramSrc.replace("enabled = enabled,", "enabled = true,")
        assertTrue("突变体必须真的改到了源码", mutant != paramSrc)
        val mutantPill = flatten(bodyOf(codeOnly(mutant), "TierPill"))
        assertFalse(
            "combinedClickable 退回无 enabled 消费者时守卫必须变红",
            mutantPill.contains("combinedClickable( enabled = enabled,")
        )
        assertFalse(
            "TierPicker 不再向下传 enabled 时守卫必须变红",
            flatten(bodyOf(codeOnly(mutant), "TierPicker")).contains("supported = item.supported, enabled = enabled")
        )
    }

    /** 与 [TierPill] 的 alpha 顺序守卫互补：本类只判"禁用有没有消费者"，顺序仍由 TierPillAlphaGuardTest 钉 */
    @Test
    fun `守卫输入必须来自真实源码`() {
        assertTrue("PillPopup.kt 必须能定位到 PillSlider", popupSrc.contains("fun PillSlider("))
        assertTrue("ParamSlider.kt 必须能定位到 TierPicker", paramSrc.contains("fun TierPicker("))
        assertTrue("ParamSlider.kt 必须能定位到 TierPill", paramSrc.contains("private fun TierPill("))
    }
}