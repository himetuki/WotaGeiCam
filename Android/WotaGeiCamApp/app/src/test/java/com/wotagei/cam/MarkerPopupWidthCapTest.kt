package com.wotagei.cam

import com.wotagei.cam.core.popupBelowRight
import com.wotagei.cam.core.popupWidthCapPx
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 站位/线数浮层窄窗钳制（Y55 后续：70bb902 只钳了滑杆）。
 *
 * 纯算术桥：先按 [popupWidthCapPx] 收窄**内容**，再把收窄后的宽喂给 [popupBelowRight]——
 * 两条约束（左边距、右界）同时成立。控件层、选色卡、站位面板三类内容都用同一上限，
 * UI 接线用源码守卫钉住（含 FlowRow 换行与站位两变体的 modifier 贯通）。
 */
class MarkerPopupWidthCapTest {

    @Test
    fun `窄窗下收缩内容宽后右缘不越目标`() {
        // 模拟应用内分屏：窗口 360px 宽已经放不下 220px 内容 + 右 Dock 槽（右缘目标 192px）
        val rightLimit = 192
        val margin = 8
        val preferred = 220
        val cap = popupWidthCapPx(rightLimit, margin)
        assertEquals(184, cap)
        val width = minOf(preferred, cap)
        val (x, _) = popupBelowRight(
            rightLimitPx = rightLimit,
            anchorBottomPx = 120,
            popupWidthPx = width,
            popupHeightPx = 90,
            windowWidthPx = 360,
            windowHeightPx = 640,
            gapPx = 8,
            marginPx = margin
        )
        assertTrue("收缩后浮层右缘不得越过右缘目标", x + width <= rightLimit)
        // 贴右界而不是缩在左边：右缘正好对齐目标
        assertEquals(rightLimit, x + width)
    }

    @Test
    fun `不收窄的旧形态在同一约束下必然越界`() {
        // 这不是"期望行为"，是把缺陷条件钉成可复现的算术：固定 220 > 可用 184 时，
        // 左侧钳制保了 margin，右缘就必然越过 192。70bb902 之前三类内容都是这个形态。
        val (x, _) = popupBelowRight(
            rightLimitPx = 192,
            anchorBottomPx = 120,
            popupWidthPx = 220,
            popupHeightPx = 90,
            windowWidthPx = 360,
            windowHeightPx = 640,
            gapPx = 8,
            marginPx = 8
        )
        assertEquals(8, x)
        assertTrue("旧形态（固定宽）右缘越界——这正是 Y55", x + 220 > 192)
    }

    @Test
    fun `宽窗可容纳首选宽度时不缩放`() {
        val cap = popupWidthCapPx(rightLimitPx = 736, marginPx = 8)
        assertEquals(728, cap)
        assertEquals(220, minOf(220, cap))
    }

    @Test
    fun `右缘目标已不剩空间时上限为0`() {
        assertEquals(0, popupWidthCapPx(rightLimitPx = 8, marginPx = 8))
        assertEquals(0, popupWidthCapPx(rightLimitPx = 2, marginPx = 8))
    }

    @Test
    fun `三类内容按同一上限收缩后右缘都贴目标`() {
        // 控件行固有宽 ≈ 40+12+线数文本+12+40；选色卡 = 6*28+5*8+卡片内边距 16；站位面板 max 320。
        // 窄窗上限 184 时三者都超，收缩后右缘必须恰好落在右缘目标上（不是只钳滑杆）。
        val rightLimit = 192
        val margin = 8
        val cap = popupWidthCapPx(rightLimit, margin)
        listOf(134 to "控件行", 224 to "选色卡", 320 to "站位面板").forEach { (preferred, label) ->
            val width = minOf(preferred, cap)
            val (x, _) = popupBelowRight(
                rightLimitPx = rightLimit,
                anchorBottomPx = 120,
                popupWidthPx = width,
                popupHeightPx = 90,
                windowWidthPx = 360,
                windowHeightPx = 640,
                gapPx = 8,
                marginPx = margin
            )
            assertTrue("$label 收缩后右缘越界", x + width <= rightLimit)
            assertEquals("$label 应贴右缘目标而不是缩在左边", rightLimit, x + width)
        }
    }

    // ------------------------------------------------------------ 接线守卫

    private fun flat(rel: String): String = KotlinSourceScan.flatten(
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel))
    )

    /**
     * 按 flavor 源集读同名双实现（main → full → lite 的默认查找只给到 full）。
     *
     * 不复用 [KotlinSourceScan.allSourceRoots]：它在 Windows 上按 `/src/main/` 替换绝对路径
     * （`absolutePath` 是反斜杠），三枚根会退化成同一个 main 根——这是工具自身的边界，
     * 不是本测试要验证的东西。这里按 [KotlinSourceScan.mainSourceFile] 同一套基目录/上溯规则找。
     */
    private fun flavorSource(flavor: String, rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        var hops = 0
        while (dir != null && hops <= 5) {
            for (base in listOf(
                dir,
                File(dir, "app"),
                File(dir, "WotaGeiCamApp"),
                File(File(dir, "WotaGeiCamApp"), "app")
            )) {
                val f = File(base, "src/$flavor/java/com/wotagei/cam/$rel")
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile
            hops++
        }
        throw AssertionError("找不到 $flavor 源集的 $rel——守卫不能在没有输入的情况下算通过")
    }

    @Test
    fun `UI 接线用同一上限收窄内容`() {
        val flat = flat("ui/CameraScreen.kt")
        val tight = flat.replace(" ", "")
        // 上限本体：右缘目标与 MarkerPopupPosition 同源（锚点右缘与右 Dock 槽左缘取小），
        // 再让出左缘边距；三类内容都从这一处取值。
        assertTrue(
            "上限必须由 popupWidthCapPx(右缘目标, HudEdgePad) 算出",
            tight.contains("popupWidthCapPx(rightLimit,HudEdgePad.roundToPx()).toDp()")
        )
        assertTrue(
            "右缘目标必须与 MarkerPopupPosition 同源（锚点右缘与右 Dock 槽左缘取小）",
            tight.contains("minOf(a.right.roundToInt(),rightKeepOutPx?:Int.MAX_VALUE)")
        )
        assertTrue(
            "控件层整列必须按上限收缩，而不是只钳滑杆",
            tight.contains("Column(modifier=Modifier.widthIn(max=markerPopupMaxWidth)")
        )
        assertTrue(
            "间距滑杆 = min(220dp, 上限) 且填满内容列",
            tight.contains("Modifier.widthIn(max=minOf(220.dp,markerPopupMaxWidth)).fillMaxWidth()")
        )
        assertFalse(
            "滑杆不许再写死 220dp（窄窗会顶过右 Dock）",
            flat.contains("Modifier.width(minOf(220.dp, markerPopupMaxWidth))")
        )
        assertTrue(
            "选色卡的上限必须套在卡片外（含 padding 一起算）",
            tight.contains("Modifier.widthIn(max=markerPopupMaxWidth).wotaCard().padding(WotaSpace.s)")
        )
        assertTrue(
            "站位面板必须按上限收缩",
            tight.contains("stance.ControlsPopup(gpuAllowed=renderMode==RenderMode.GPU,modifier=Modifier.widthIn(max=stancePopupMaxWidth))")
        )
    }

    @Test
    fun `窄窗下控件行与选色格换行而不是溢出`() {
        val flat = flat("ui/CameraScreen.kt")
        val tight = flat.replace(" ", "")
        // +/线数/- 行：FlowRow 才能把线数/− 换到第二行；Row 只能撑宽越界。
        assertTrue(
            "加号线数减号行必须是居中 FlowRow",
            tight.contains(
                "FlowRow(verticalArrangement=Arrangement.spacedBy(WotaSpace.xs)," +
                    "horizontalArrangement=Arrangement.spacedBy(WotaSpace.m,Alignment.CenterHorizontally))"
            )
        )
        // 选色格是同一处上限的 FlowRow：上限外套 + 卡片/内边距 + 行内/行间间距缺一不可。
        assertTrue(
            "选色格必须是把上限套在卡片外的 FlowRow",
            tight.contains(
                "FlowRow(Modifier.widthIn(max=markerPopupMaxWidth).wotaCard().padding(WotaSpace.s),"
            ) && tight.contains(
                "verticalArrangement=Arrangement.spacedBy(WotaSpace.s))"
            )
        )
    }

    @Test
    fun `站位两变体都把上限 modifier 落到内容根`() {
        val full = KotlinSourceScan.flatten(
            KotlinSourceScan.codeOnly(flavorSource("full", "stance/StanceFeature.kt"))
        ).replace(" ", "")
        val lite = KotlinSourceScan.flatten(
            KotlinSourceScan.codeOnly(flavorSource("lite", "stance/StanceFeature.kt"))
        ).replace(" ", "")
        assertTrue(
            "full 变体 ControlsPopup 必须接收 modifier 并透传给 StancePopupContent",
            full.contains("funControlsPopup(gpuAllowed:Boolean,modifier:Modifier=Modifier)") &&
                full.contains("modifier=modifier,")
        )
        assertTrue(
            "lite 变体 ControlsPopup 必须接收 modifier 并套在内容列最外层",
            lite.contains("funControlsPopup(gpuAllowed:Boolean,modifier:Modifier=Modifier)") &&
                lite.contains("Column(modifier.widthIn(min=264.dp,max=320.dp)")
        )
    }
}
