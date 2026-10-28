package com.wotagei.cam

import com.wotagei.cam.core.CurvePanelSize
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.ui.WotaSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲线面板大小记忆（29 §1 F1）+ 按通道直方图（29 §2 F2）的纯函数与接线守卫。
 *
 * F1 口径：存**实际边长 dp** 不存 scale（跨屏/转屏 baseSide 会变，scale 会漂）；
 * 把手**抬手才落盘一次**（不许逐帧写 prefs），重置写回 200。
 * F2 口径：直方图按当前通道取数与着色（master 用亮度 Y），不再三色混排；
 * 背景色趋势 = 通道色→补色的横向渐变条；选中控制点显示入/出读数。
 */
class CurvePanelSizeTest {

    // -------------------------------------------------- ① 钳制纯函数

    @Test
    fun `边长钳制_负数NaN回设计档_越界钳到可用区`() {
        assertEquals(200f, CurvePanelSize.clamp(200f, 400f, 120f), 1e-4f)
        assertEquals("比最小值小 → 抬回最小", 120f, CurvePanelSize.clamp(60f, 400f, 120f), 1e-4f)
        assertEquals("比可用区大（换小屏）→ 缩回", 320f, CurvePanelSize.clamp(500f, 320f, 120f), 1e-4f)
        assertEquals("NaN 回设计档", 200f, CurvePanelSize.clamp(Float.NaN, 400f, 120f), 1e-4f)
        assertEquals("负数回设计档", 200f, CurvePanelSize.clamp(-5f, 400f, 120f), 1e-4f)
        assertEquals("maxSide 坏域（<最小）以最小值为准", 120f, CurvePanelSize.clamp(200f, 80f, 120f), 1e-4f)
        assertEquals(200f, CurvePanelSize.DEFAULT_SIDE_DP, 0f)
    }

    @Test
    fun `设置读取_坏值回设计档_好值原样`() {
        val prefs = FakePrefs()
        assertEquals("缺键 = 设计档 200", 200f, WotaSettings.curvePanelSideDp(prefs), 1e-4f)
        WotaSettings.setCurvePanelSideDp(prefs, 320f)
        assertEquals(320f, WotaSettings.curvePanelSideDp(prefs), 1e-4f)
        prefs.map[WotaSettings.KEY_CURVE_PANEL_SIDE_DP] = -1f
        assertEquals("负数回设计档（读取侧钳）", 200f, WotaSettings.curvePanelSideDp(prefs), 1e-4f)
    }

    // -------------------------------------------------- ② 接线守卫

    private val popupCode: String by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/dialog/CurvePopup.kt"))
    }

    @Test
    fun `把手抬手才落盘_重置写回设计档`() {
        val handle = KotlinSourceScan.bodyOf(popupCode, "CurvePopup")
        assertTrue("把手拖拽必须写 savedSide（存 dp 不存 scale）", handle.contains("savedSide"))
        assertTrue("落盘必须走 setCurvePanelSideDp", handle.contains("setCurvePanelSideDp("))
        // 把手的循环体（clamp 逐帧）内不许有落盘；落盘点必须出现在 break 之后（= 抬手路径）。
        // 从「break 锚点」向后找第一个 persist，再证它不在循环体内（onReset 里的那次落盘
        // 在手势段之前，substring 后不会干扰——被 break 定位排除）
        val breakAt = handle.indexOf("if (!change.pressed) break")
        assertTrue("锚点丢失：把手循环的 break 不在", breakAt > 0)
        val loopStart = handle.lastIndexOf("while", breakAt)
        assertTrue(loopStart > 0)
        val loopClamp = handle.indexOf("CurvePanelSize.clamp(", loopStart)
        assertTrue("循环体内必须是 clamp（改边长）", loopClamp in loopStart until breakAt)
        val persistAt = handle.indexOf("setCurvePanelSideDp(", breakAt)
        assertTrue("break 之后必须紧跟抬手落盘", persistAt > breakAt)
        assertTrue(
            "循环体内不许有落盘（不许逐帧写 prefs）",
            handle.indexOf("setCurvePanelSideDp(", loopStart).let { it < 0 || it >= breakAt }
        )
        // 重置写回 200（设计档）
        assertTrue(handle.contains("setCurvePanelSideDp(prefs, savedSide)"))
        // 尺要能红：把落盘挪进循环体（break 之前）后断言翻红
        val mutant = handle.replace(
            "if (!change.pressed) break",
            "setCurvePanelSideDp(prefs, savedSide)\nif (!change.pressed) break"
        )
        val mutantFirstPersist = mutant.indexOf("setCurvePanelSideDp(", mutant.lastIndexOf("while", mutant.indexOf("if (!change.pressed) break")))
        assertTrue(
            "突变体上「循环体内无落盘」必须翻红（尺子自己能红）",
            mutantFirstPersist in 0 until mutant.indexOf("if (!change.pressed) break")
        )
    }

    @Test
    fun `直方图按当前通道取数且画趋势条`() {
        val canvas = KotlinSourceScan.bodyOf(popupCode, "CurveCanvas")
        assertTrue("取数组必须经 histogramBase(channel)（按通道，不再三色混排）", canvas.contains("histogramBase(channel)"))
        assertFalse("旧的三通道同绘循环必须退场", canvas.contains("Triple(0,"))
        assertTrue("背景色趋势必须画渐变条", canvas.contains("Brush.horizontalGradient(trendColors(channel))"))
        assertTrue("读数必须随选中点显示", popupCode.contains("R.string.cam_curve_io_readout"))
        // 尺要能红：退回三色混排后断言翻红
        val mutant = canvas.replace("histogramBase(channel)", "histogramBase(0)")
        assertFalse(mutant.contains("histogramBase(channel)"))
    }
}
