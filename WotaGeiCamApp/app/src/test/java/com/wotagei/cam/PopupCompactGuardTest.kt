package com.wotagei.cam

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.ui.design.PillAnchor
import com.wotagei.cam.ui.rightEdgeAnchorOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹窗三连改（2026-10-05 用户裁决）的结构守卫：
 *
 * 1. **透明化**：弹窗壳底 alpha 0.97 → 0.88，落点是 `WotaPillPopup`（PillPopup.kt）与
 *    `WotaMenuPopup`（Widgets.kt）两处共用面；RGB 曲线弹窗（CurvePopup）**豁免**——
 *    它自带独立容器与自己的 0.97，必须原样保留（用户原话「除了 RGB 曲线弹窗以外」）。
 * 2. **紧凑化**：weight 行一律 `fill = false`、行不 fillMaxWidth、定宽常量废除——
 *    「两三字符按钮拉成二十字符长」的根因就是 fill 撑满弹窗最大宽（400dp）。
 * 3. **右 Dock 弹窗贴右 + 锚点冻结**：`rightEdgeAnchorOf` 合成锚点的**行为桥**
 *    （合成矩形 → PillAnchor.place → 弹窗右缘 = 屏幕右缘 − margin）按 AGENTS「计划→行为」
 *    纪律实测，不靠源码字符串自证；CameraScreen 的冻结/贴右接线单独钉 token。
 *
 * 「尺子自己能红」段拿同一把尺打突变体，防删空判据照样绿。
 */
class PopupCompactGuardTest {

    private fun main(rel: String): String = codeOnly(KotlinSourceScan.mainSourceText(rel))

    private fun raw(rel: String): String = KotlinSourceScan.mainSourceText(rel)

    // --------------------------------------------------------------- 1. 透明化

    @Test
    fun `弹窗壳底透明度落在两处共用面且曲线弹窗豁免`() {
        val pill = main("ui/design/PillPopup.kt")
        val widgets = main("ui/design/Widgets.kt")
        val curve = main("ui/dialog/CurvePopup.kt")
        assertTrue(
            "WotaPillPopup 壳底必须是 0.88（2026-10-05 透明化）",
            pill.contains("WotaColor.surface.copy(alpha = 0.88f)")
        )
        assertTrue("PillPopup.kt 不许再留 0.97 底", !pill.contains("0.97f"))
        assertTrue(
            "WotaMenuPopup 与 PillPopup 同一轮透明化",
            widgets.contains("WotaColor.surface.copy(alpha = 0.88f)")
        )
        // 豁免清单：CurvePopup 独立容器保持 0.97，不许被顺手改掉
        assertTrue("CurvePopup 必须保持自带 0.97（豁免）", curve.contains("alpha = 0.97f"))
        assertTrue("CurvePopup 不在透明化范围", !curve.contains("0.88f"))
    }

    // --------------------------------------------------------------- 2. 紧凑化

    /** 豁免外全部 `WotaPillPopup` 调用点：文件 → 调用数（盘点表钉死，新增弹层须同轮登记） */
    private val popupCallSites = mapOf(
        "ui/CameraPills.kt" to 15,      // 录制页 15 颗参数胶囊
        "player/PlayerScreen.kt" to 5,  // 倍速 / 剪辑导出 / 光弧修复 / 音轨选择 / 选轨导出（内录批 4）
        "player/CompareScreen.kt" to 1, // 镜像三选
        "ui/dialog/AudioSourcePanel.kt" to 1 // 音源管理（内录 + 蓝牙页签）
    )

    /** 调用点计数：剥掉 import 行再数词（`WotaPillPopup(` 的括号会被词边界判据吃掉） */
    private fun callCount(rel: String): Int =
        KotlinSourceScan.occurrences(
            codeOnly(
                KotlinSourceScan.mainSourceText(rel).lineSequence()
                    .filterNot { it.trimStart().startsWith("import ") }
                    .joinToString("\n")
            ),
            "WotaPillPopup"
        ).size

    @Test
    fun `弹窗清单钉死为二十二枚调用点`() {
        popupCallSites.forEach { (rel, expected) ->
            assertEquals("$rel 的 WotaPillPopup 调用数变了：盘点表要同步", expected, callCount(rel))
        }
    }

    @Test
    fun `壳内行不再撑满最大宽`() {
        val pill = main("ui/design/PillPopup.kt")
        // 档位/多选行与其补位一律 fill=false（恰好 4 处）；
        // 裸 weight(1f)（fill=true 撑满）恰好 1 处 = PillSlider 的滑杆——它躺在
        // PILL_SLIDER_MAX_WIDTH 封顶的行里，fill=true 才有拖动行程
        assertEquals(
            "PillPopup.kt 的 weight(1f, fill = false) 必须恰 4 处（choices/toggles 两族）",
            4, KotlinSourceScan.occurrences(pill, "weight(1f, fill = false)").size
        )
        assertEquals(
            "裸 weight(1f)（fill=true 撑满）只许滑杆那 1 处",
            1, KotlinSourceScan.occurrences(pill, "weight(1f)").size
        )
        // 「收起」行不许再 fillMaxWidth（同一条：fill 反过来把弹窗钉死在最大宽）
        assertTrue(
            "WotaPillPopup 壳内不许有 fillMaxWidth",
            KotlinSourceScan.occurrences(bodyOf(pill, "WotaPillPopup"), "fillMaxWidth").isEmpty()
        )
        // 长说明句封宽：NoteText 只准在 220dp 内折行，不顶弹窗宽
        assertTrue(
            "CameraPills.NoteText 必须 widthIn(max = 220.dp)",
            bodyOf(main("ui/CameraPills.kt"), "NoteText").contains("widthIn(max = 220.dp)")
        )
    }

    @Test
    fun `播放侧定宽常量废除且光弧弹窗分组重设计`() {
        val player = main("player/PlayerScreen.kt")
        assertTrue("倍速/光弧/剪辑的定宽常量必须废干净", !player.contains("POPUP_PANEL_WIDTH"))
        val arc = bodyOf(player, "ArcRepairPopup")
        listOf(
            "player_arc_target",       // 设置组标题（目标帧率）
            "player_arc_start_group",  // 动作组标题（开始修复）
            "OutlinedButton(",         // 描边动作钮（CPU）
            "ButtonDefaults.buttonColors(", // 填充动作钮（GPU）——按钮样式与文本行区分
            "player_arc_gpu_note",     // 小注「速度快·推荐」
            "player_arc_cpu_note"      // 小注「兼容模式」
        ).forEach { token ->
            assertTrue("ArcRepairPopup 缺分组重设计要件：$token", arc.contains(token))
        }
        // 音源页签行与转换档行同为 weight(fill=false) 族
        assertTrue(
            "AudioSourcePanel 页签行必须 weight(fill=false)",
            main("ui/dialog/AudioSourcePanel.kt").contains("Modifier.weight(1f, fill = false)")
        )
    }

    @Test
    fun `剪辑导出体内禁fillMaxWidth进度条定宽`() {
        // 修复轮 1/3 P1：「导出中」的进度条 fillMaxWidth 会把 200dp 弹层顶回 400dp 上限、
        // place() 重居中横向跳位——紧凑化根因的漏网行，钉死
        val clip = bodyOf(main("player/PlayerScreen.kt"), "ClipExportPopup")
        assertTrue(
            "ClipExportPopup 体内禁 fillMaxWidth（导出中宽度反弹+横向跳位）",
            KotlinSourceScan.occurrences(clip, "fillMaxWidth").isEmpty()
        )
        assertTrue(
            "进度条必须定宽（CLIP_POPUP_PROGRESS_WIDTH）",
            clip.contains("CLIP_POPUP_PROGRESS_WIDTH")
        )
    }

    // --------------------------------------------------------------- 3. 贴右 + 冻结

    @Test
    fun `贴右合成锚点过 place 后弹窗右缘贴屏幕右缘减边距`() {
        val area = IntSize(720, 1600)
        // 窄弹窗（120dp）：原锚点下居中放得下（不会被 clamp 到右缘），才能区分「贴右」与「居中」——
        // 宽弹窗在旧逻辑下本来就贴右（clamp 兜底），拿它做桥测不出退化
        val popup = IntSize(120, 300)
        val gap = 8
        val margin = 10
        val anchor = IntRect(600, 500, 660, 560) // 右 Dock 里的某颗控件
        val at = PillAnchor.place(rightEdgeAnchorOf(anchor, area.width), popup, area, gap, margin)
        assertEquals("弹窗右缘必须贴「屏幕右缘 − margin」", area.width - margin, at.x + popup.width)
        assertEquals("纵向仍按锚点上方走（合成锚点沿用原 top）", anchor.top - gap - popup.height, at.y)
    }

    @Test
    fun `CameraScreen 冻结与贴右接线在场`() {
        val screen = main("ui/CameraScreen.kt")
        assertTrue("锚点快照冻结（anchorSnap）缺位", screen.contains("anchorSnap"))
        assertTrue("右 Dock 贴右换算（rightEdgeAnchorOf）缺位", screen.contains("rightEdgeAnchorOf("))
        // 判据的真身：右 Dock 判定读的是右 Dock 卡片实测矩形，不是几何阈值拍脑袋
        assertTrue(
            "右 Dock 判定必须查 zoneRects[HudZone.RIGHT]",
            screen.contains("zoneRects[HudZone.RIGHT]")
        )
    }

    // --------------------------------------------------------------- 尺子自己能红

    @Test
    fun `尺子自己能红`() {
        val pill = main("ui/design/PillPopup.kt")
        // 透明化被改回 0.97 ⇒ 必红
        assertTrue(
            "透明化回退必须红",
            !pill.replace("0.88f", "0.97f").contains("alpha = 0.88f")
        )
        // weight fill=false 被删 ⇒ fill=false 计数清零、裸 weight 涨到 5，两条判据都爆
        val mutated = pill.replace(", fill = false", "")
        assertTrue(
            "紧凑化回退必须红（fill=false 清零）",
            KotlinSourceScan.occurrences(mutated, "weight(1f, fill = false)").size != 4
        )
        // 进度条定宽被改回 fillMaxWidth ⇒ 剪辑导出判据必红（P1 突变自证）
        val clipMutated = main("player/PlayerScreen.kt")
            .replace("width(CLIP_POPUP_PROGRESS_WIDTH)", "fillMaxWidth()")
        val clipBody = bodyOf(clipMutated, "ClipExportPopup")
        assertTrue(
            "进度条回退 fillMaxWidth 必须红",
            KotlinSourceScan.occurrences(clipBody, "fillMaxWidth").isNotEmpty() ||
                !clipBody.contains("CLIP_POPUP_PROGRESS_WIDTH")
        )
        // 贴右换算被改成原样透传 ⇒ 行为桥必红：窄弹窗在原锚点下居中（x=570），
        // 过合成锚点才贴右缘（x=590）——两条路径可区分
        val passedThrough = PillAnchor.place(
            IntRect(600, 500, 660, 560), IntSize(120, 300), IntSize(720, 1600), 8, 10
        )
        assertTrue(
            "贴右行为桥必须能区分透传与贴右",
            passedThrough.x + 120 != 720 - 10
        )
    }
}
