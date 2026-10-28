package com.wotagei.cam

import com.wotagei.cam.core.ColorCurve
import com.wotagei.cam.core.CurveEdit
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲线锚点手感（29 §三 F3，2026-10-28 落地）。
 *
 * 三条摩擦点的修法：① 命中改**像素域**（[CurveEdit.hitTestPx]，半径随面板缩放，
 * 20dp 下限 14dp）+ 命中区四边外扩 12dp（端点在画布四角、触点一半在外）；
 * ② 二次点按改「取消选中」；③ 删点改**长按 400ms + 震动**（已裁决 2026-10-27）。
 * moved 不变量（端点 X 恒定、中间点不穿邻居）是既有契约，回归钉住。
 */
class CurveAnchorHitTest {

    private val identity = CurveEdit.pointsOf(ColorCurve.IDENTITY)  // (0,0)-(1,1)

    // -------------------------------------------------- ① 像素域命中

    @Test
    fun `像素域命中_半径随参数走_椭圆域`() {
        // 左下端点 (0,0)：归一化半径 0.07（旧常数）下，x=0.06、y=0.06 处距离 ≈0.085 > 0.07 抓不到
        assertEquals(-1, CurveEdit.hitTest(identity, 0.06f, 0.06f))
        // 像素域换半径：画布 400px、半径 60px → rx=0.15，同一点可抓
        assertEquals(0, CurveEdit.hitTestPx(identity, 0.06f, 0.06f, 0.15f, 0.15f))
        // 椭圆域：x 在半径内、y 超出 → 抓不到（半径各轴独立换算，正方形画布二者相等）
        assertEquals(-1, CurveEdit.hitTestPx(identity, 0.10f, 0.20f, 0.15f, 0.05f))
        // 两点取最近
        assertEquals(
            1,
            CurveEdit.hitTestPx(identity, 0.92f, 0.92f, 0.15f, 0.15f)
        )
    }

    @Test
    fun `坏半径直接判未命中_不抛`() {
        assertEquals(-1, CurveEdit.hitTestPx(identity, 0.5f, 0.5f, 0f, 0.1f))
        assertEquals(-1, CurveEdit.hitTestPx(identity, 0.5f, 0.5f, 0.1f, -1f))
    }

    @Test
    fun `旧归一化入口委托像素域_行为不变`() {
        // 旧 HIT_RADIUS(0.07) 口径回归：端点在半径内可抓（0.04,0.04 距端点 ≈0.057）、超出不可抓
        assertEquals(0, CurveEdit.hitTest(identity, 0.04f, 0.04f))
        assertEquals(1, CurveEdit.hitTest(identity, 0.96f, 0.96f))
        assertEquals(-1, CurveEdit.hitTest(identity, 0.2f, 0.2f))
    }

    @Test
    fun `moved不变量回归_端点X恒定_中间点不穿邻居`() {
        val pts = listOf(
            ColorCurve.Point(0f, 0f),
            ColorCurve.Point(0.4f, 0.4f),
            ColorCurve.Point(1f, 1f)
        )
        val moved0 = CurveEdit.moved(pts, 0, 0.9f, 0.9f)
        assertEquals("端点 X 恒定", 0f, moved0[0].x, 1e-6f)
        assertEquals("端点只跟 Y", 0.9f, moved0[0].y, 1e-6f)
        val movedMid = CurveEdit.moved(pts, 1, 0.99f, 0.2f)
        assertEquals("中间点 X 被夹在邻居之间（MIN_DX 让位）", 1f - CurveEdit.MIN_DX, movedMid[1].x, 1e-6f)
    }

    // -------------------------------------------------- ② 手势接线守卫

    private val popup: String by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/dialog/CurvePopup.kt"))
    }

    @Test
    fun `手势层_长按删除_角部扩展_像素域半径_二次点按取消选中`() {
        assertTrue("删点必须走长按超时（29 §3.2-3）", popup.contains("withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis)"))
        assertTrue("命中必须走像素域 hitTestPx", popup.contains("CurveEdit.hitTestPx("))
        assertTrue("命中半径必须随面板缩放（20dp×panelScale，下限 14dp）", popup.contains("HitRadius.dp.toPx() * panelScale"))
        assertTrue("命中区必须外扩（角部扩展）", popup.contains("HitExtend.dp.toPx()"))
        assertTrue("二次点按必须改取消选中（不再删点）", popup.contains("secondTap && !moved -> selected = -1"))
        // 抓到点/插点/删点必须有震动
        assertTrue("必须接 LocalHapticFeedback", popup.contains("LocalHapticFeedback.current"))
        // 尺要能红：退回归一化 hitTest 后断言翻红
        val mutant = popup.replace("CurveEdit.hitTestPx(", "CurveEdit.hitTest(")
        assertTrue(mutant.contains("CurveEdit.hitTest(") && !mutant.contains("CurveEdit.hitTestPx("))
    }

    @Test
    fun `端点双层环视觉与选中放大`() {
        val canvas = KotlinSourceScan.bodyOf(popup, "CurveCanvas")
        assertTrue("端点必须画双层环（外环通道色 + 内芯背景色）", canvas.contains("drawCircle(WotaColor.bg, radius = r * 0.62f"))
        assertTrue("选中必须 1.2 倍放大", canvas.contains("dotSel * 1.2f"))
        assertTrue("端点必须略大与中间点区分", canvas.contains("endpoint -> dot * 1.15f"))
    }
}
