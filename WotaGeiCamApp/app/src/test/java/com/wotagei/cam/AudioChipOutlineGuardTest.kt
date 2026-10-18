package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录制页「音频」chip 的**可点提示描边**守卫（2026-10-18 用户反馈：大部分用户不知道这颗可以点）。
 *
 * 判的不是颜色好不好看，而是**描边的落点**：它必须是这颗 chip 自己 modifier 链上的一层
 * （形参 `modifier` 之后、`clickable` 之前），于是 Dock 重排、编辑页拖动 / ghost 跟手、
 * 底栏滑入滑出时描边都只是这颗的绘制层，跟着控件一起走。用户点名不许的形态是
 * "另起一层 Canvas / drawBehind / Box 按绝对坐标描边"——那样控件一动，描边就留在原位。
 *
 * 颜色语义另有一条：常态淡蓝 `accentHint`（能点），内录激活才升到不透明 `WotaAccent`（正亮着）。
 * 两枚是不同语义层，合并成一层会让"激活高亮"失去独占信号。
 */
class AudioChipOutlineGuardTest {

    private fun chipBody(raw: String): String =
        KotlinSourceScan.flatten(bodyOf(codeOnly(raw), "AudioChip"))

    /**
     * 描边是否骑在 chip 自己的布局链上：`Row(` 之后、`.clickable(` 之前出现 `.border(`。
     * 链左外右内——描边排在 `clickable` 之后就不再包住命中区，也更容易被挪成独立覆盖层。
     */
    private fun outlineRidesChip(raw: String): Boolean {
        val body = chipBody(raw)
        val iRow = body.indexOf("Row(")
        val iOutline = body.indexOf(".border(")
        val iClick = body.indexOf(".clickable(")
        return iRow >= 0 && iOutline > iRow && iClick > iOutline
    }

    /**
     * 反向判据：描边不许靠屏幕/容器坐标另画一层（用户点名的禁区）。
     * 判裸词而不是带左括号的调用形：Compose 这几种都是尾随 lambda（`drawBehind { }`），
     * `drawBehind(` 这种写法在合法代码里根本不会出现，拿它当判据等于永远绿。
     */
    private fun usesAbsoluteOverlay(raw: String): Boolean =
        listOf("drawBehind", "drawWithContent", "Canvas(", "drawIntoCanvas")
            .any { chipBody(raw).contains(it) }

    private val real: String
        get() = KotlinSourceScan.mainSourceText("ui/widget/AudioChip.kt")

    @Test
    fun `描边挂在音频chip自己的布局链上`() {
        val body = chipBody(real)
        assertTrue(
            "`.border(...)` 必须排在 Row 的 modifier 链里、形参 modifier 之后（描边是这颗自己的绘制层，" +
                "Dock 重排/编辑页拖动时才会跟着走）",
            outlineRidesChip(real)
        )
        assertTrue(
            "描边必须排在 `.clickable(` 之前：排在之后就不再包住整颗命中区",
            body.indexOf(".border(") < body.indexOf(".clickable(")
        )
        assertFalse(
            "不许用 drawBehind/drawWithContent/Canvas 按坐标另画描边——那种描边控件一动就留在原位",
            usesAbsoluteOverlay(real)
        )
        assertTrue(
            "常态必须用淡蓝 accentHint、内录激活才升到不透明 WotaAccent（两层语义不许合并）",
            body.contains("if (captureActive) WotaAccent else WotaColor.accentHint")
        )
    }

    @Test
    fun `尺子自己能红`() {
        assertTrue("良品必须先真的绿，否则下面的红没有意义", outlineRidesChip(real))

        val noBorder = real.replace(".border(WotaStroke.hairline, outline, WotaShape.medium)", "")
        assertFalse("删掉描边后判据必须红", outlineRidesChip(noBorder))

        // 顺序退回错误形态：描边排到 clickable 之后 ⇒ 必红
        val afterClick = real
            .replace(".border(WotaStroke.hairline, outline, WotaShape.medium)", "@@BORDER@@")
            .replace(".clickable(onClick = onClick)", ".border(WotaStroke.hairline, outline, WotaShape.medium)")
            .replace("@@BORDER@@", ".clickable(onClick = onClick)")
        assertFalse("描边排到 clickable 之后时判据必须变红", outlineRidesChip(afterClick))

        // 覆盖层形态：函数体内另起一层按坐标绘制 ⇒ 反向判据必须红
        val overlay = real.replaceFirst("Row(", "Box(Modifier.drawBehind { })\n    Row(")
        assertTrue("必须真的插进 AudioChip 体内", overlay != real)
        assertTrue("drawBehind 形态出现时反向判据必须变红", usesAbsoluteOverlay(overlay))
    }
}
