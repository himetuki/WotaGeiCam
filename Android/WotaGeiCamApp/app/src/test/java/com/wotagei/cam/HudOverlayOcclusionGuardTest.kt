package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.ui.cellRowSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-28 三项真机反馈的结构守卫：
 *
 * ① 参考线圆钮占位过大（用户点名「四周过多空白、调小省空间」）：38dp→30dp、高 76px→60px，
 *    竖 Dock 里从 2 档降到 1 档，整条左 Dock 省出 1 档（顺带把 TONE 满配的 64px 溢出清掉）；
 * ② 参考线/站位线压在 HUD 控件上：开霜时 HUD 卡片把实底让位给 **GL 霜板**（预览图层），
 *    而参考线是 Compose 叠加层 ⇒ 天然画在霜板之上、看起来压在底 Dock 卡片上。
 *    修法：叠加层在画时 HUD 卡片改回不透明实底（[com.wotagei.cam.ui.design.LocalPreviewOverlaysOn]）。
 *
 * 两条都按「真身 + 突变体同吃一把尺」写：判据是吃源码文本的纯函数，配突变自证。
 */
class HudOverlayOcclusionGuardTest {

    private val hudLayer: String by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayer.kt"))
    }
    private val widgets: String by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/design/Widgets.kt"))
    }
    private val screen: String by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt"))
    }

    // -------------------------------------------------- ① 参考线圆钮收小

    @Test
    fun `参考线圆钮必须30dp_竖Dock里只占1档`() {
        val entry = KotlinSourceScan.bodyOf(hudLayer, "HudEntryItem")
        val refBranch = entry.substringAfter("CamPill.REFLINE -> WotaIconButton(")
            .substringBefore("CamPill.MONITOR ->")
        for (token in listOf("size = 30.dp", "glyph = 18.dp")) {
            assertTrue("参考线圆钮必须收小（用户 2026-10-28 口径）：缺 $token", refBranch.contains(token))
        }
        // 行为桥：60px 的实测高在 68px 档位里只吃 1 档（收小前 76px 吃 2 档）
        assertEquals("60px 必须只占 1 档", 1, cellRowSpan(60, 68))
        assertEquals("收小前 76px 会占 2 档（回退即重新吃满两档）", 2, cellRowSpan(76, 68))
        // 尺要能红：把 size 改回默认（删掉这一行）后判据翻红
        val mutant = refBranch.replace("size = 30.dp", "")
        assertFalse(mutant.contains("size = 30.dp"))
    }

    // -------------------------------------------------- ② 叠加层不许压在 HUD 上

    @Test
    fun `叠加层在画时HUD卡片必须不透明挡在上面`() {
        // 扩展函数：定位名必须带接收者（KotlinSourceScan 的 regionsOf 认 `fun <name>(`）
        val card = KotlinSourceScan.bodyOf(widgets, "Modifier.wotaHudCard")
        assertTrue(
            "wotaHudCard 必须读 LocalPreviewOverlaysOn（否则霜板态下参考线画在卡片上）",
            card.contains("LocalPreviewOverlaysOn.current")
        )
        assertTrue(
            "叠加层在画时必须给不透明实底（97% 暖黑）",
            card.contains("WotaColor.bg.copy(alpha = 0.97f)")
        )
        assertTrue(
            "录制页必须按「参考线或站位线在画」提供这个开关",
            KotlinSourceScan.flatten(screen)
                .contains("LocalPreviewOverlaysOn provides (refLines != 0 || markerCount > 0)")
        )
        // 突变 1：优先级判断摘掉 ⇒ 霜板路径又会让位
        val mutant = card.replace(
            "if (LocalPreviewOverlaysOn.current) {", "if (false) {"
        )
        assertFalse(
            "摘掉叠加层分支后判据必须翻红",
            mutant.contains("if (LocalPreviewOverlaysOn.current) {")
        )
        // 突变 2：录制页不再提供开关（默认 false ⇒ 回到旧行为）
        val screenMutant = KotlinSourceScan.flatten(screen)
            .replace("LocalPreviewOverlaysOn provides (refLines != 0 || markerCount > 0)", "")
        assertFalse(
            "录制页摘掉 provide 后判据必须翻红",
            screenMutant.contains("LocalPreviewOverlaysOn provides (refLines != 0 || markerCount > 0)")
        )
    }
}
