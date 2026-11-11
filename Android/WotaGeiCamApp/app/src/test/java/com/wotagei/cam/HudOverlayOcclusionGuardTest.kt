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
    private val liquid: String by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/anim/LiquidMerge.kt"))
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
    fun `叠加层在画时HUD卡片必须压暗毛玻璃而不是黑掉`() {
        // 2026-11-10 二次反馈：10-28 的 97% 暖黑在左右黑边栏上等于"没有板"（残影/降噪变透明）。
        // 改为压暗的毛玻璃：75% hudScrim + 照常注册霜板（板还在，只是压暗；线只透 25%）。
        val card = KotlinSourceScan.bodyOf(widgets, "Modifier.wotaHudCard")
        assertTrue(
            "wotaHudCard 必须读 LocalPreviewOverlaysOn（否则霜板态下参考线画在卡片上）",
            card.contains("LocalPreviewOverlaysOn.current")
        )
        assertTrue(
            "叠加层在画时必须用 hudScrim 压暗毛玻璃（97% 暖黑在黑边栏上等于没板）",
            card.contains("WotaColor.hudScrim")
        )
        assertTrue(
            "自己出板的卡片叠加层在画时仍要注册霜板（板要还在，只是压暗）",
            card.contains("edged.background(WotaColor.hudScrim).then(hudFrostRectRegistrar(frost))")
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
        // 突变 2：压暗改回 97% 暖黑（黑边栏上等于没板） ⇒ 判据翻红
        val blackMutant = card.replace("WotaColor.hudScrim", "WotaColor.bg.copy(alpha = 0.97f)")
        assertFalse(
            "回退 97% 暖黑后守卫必须报红",
            blackMutant.contains("WotaColor.hudScrim")
        )
        // 突变 3：录制页不再提供开关（默认 false ⇒ 回到旧行为）
        val screenMutant = KotlinSourceScan.flatten(screen)
            .replace("LocalPreviewOverlaysOn provides (refLines != 0 || markerCount > 0)", "")
        assertFalse(
            "录制页摘掉 provide 后判据必须翻红",
            screenMutant.contains("LocalPreviewOverlaysOn provides (refLines != 0 || markerCount > 0)")
        )
    }

    @Test
    fun `底栏霜壳叠加层在画时压暗保留霜板`() {
        // 底栏 Dock 的底板不是 wotaHudCard，而是 LiquidMerge 的 wotaDockShell（绘制期自绘轮廓 +
        // 霜板注册）。2026-11-10 起：叠加层在画时不再摘板/黑掉，而是照常注册 + 75% 灰罩压暗。
        val shell = KotlinSourceScan.bodyOf(liquid, "Modifier.wotaDockShell")
        assertTrue("wotaDockShell 必须读 LocalPreviewOverlaysOn", shell.contains("LocalPreviewOverlaysOn.current"))
        assertTrue(
            "叠加层在画时霜板注册必须照常（板要还在，只是压暗）",
            shell.contains("if (intent && frostSlot >= 0) {")
        )
        assertTrue(
            "叠加层在画时底板必须用 hudScrim 压暗",
            shell.contains("WotaColor.hudScrim")
        )
        assertTrue(
            "实底分支必须在「!live || overlaysOn」下触发（霜开着也要压线）",
            shell.contains("if (!live || overlaysOn) {")
        )
        // 尺要能红：把注册重新按叠加层摘掉（回退 10-28 的摘板口径）后判据翻红
        val mutant = shell.replace(
            "if (intent && frostSlot >= 0) {",
            "if (intent && frostSlot >= 0 && !overlaysOn) {"
        )
        assertFalse(
            "回退摘板口径后守卫必须报红",
            mutant.contains("if (intent && frostSlot >= 0) {")
        )
    }

    @Test
    fun `竖Dock底板叠加层在画时同样压暗保留霜板`() {
        // 2026-11-10 用户报障（残影/降噪霜板消失）：竖 Dock 是第三条霜路径。修法与前两条统一：
        // 叠加层在画时照常注册 GL 霜板 + 铺 75% hudScrim 压暗，不再切哨兵/黑掉。
        val dock = KotlinSourceScan.bodyOf(hudLayer, "HudDockZone")
        assertTrue("竖Dock底板必须读 LocalPreviewOverlaysOn", dock.contains("LocalPreviewOverlaysOn.current"))
        assertTrue(
            "叠加层在画时竖Dock底板必须用 hudScrim 压暗",
            dock.contains("WotaColor.hudScrim")
        )
        assertTrue(
            "叠加层在画时 GL 板注册必须照常（板要还在，只是压暗）",
            dock.contains("hudFrostDockRegistrar(plateRadiusPx, frostVisible)")
        )
        assertTrue(
            "fill 让位必须排除叠加层在画（在画时铺灰罩压线）",
            dock.contains("HudFrost.live && frostVisible && !overlaysOn")
        )
        // 尺要能红：把注册重新按叠加层切哨兵后判据翻红
        val mutant = dock.replace(
            "hudFrostDockRegistrar(plateRadiusPx, frostVisible)",
            "hudFrostDockRegistrar(plateRadiusPx, frostVisible && !overlaysOn)"
        )
        assertFalse(
            "回退切哨兵口径后守卫必须报红",
            mutant.contains("hudFrostDockRegistrar(plateRadiusPx, frostVisible)")
        )
    }
}
