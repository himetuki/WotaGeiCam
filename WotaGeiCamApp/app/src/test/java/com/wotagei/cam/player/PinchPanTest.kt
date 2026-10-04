package com.wotagei.cam.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 双指平移夹取 [clampPan] 的判定表（JVM，不需设备）。
 * 需求口径：平移域=仅放大态，逐轴 ±视口×(s−1)/2——放大后画面恒盖住视口拖不出黑边；
 * 捏回 1x 的过程中平移随夹取连续归零（真断言：删掉运行时的夹取分支这组会红）。
 */
class PinchPanTest {

    /** ≤1x 平移域为 0：不管拖出多大的量都归零（自然禁用），视口未量出（0×0）同理不炸 */
    @Test
    fun `一倍及以下平移一律归零`() {
        val vp = Size(400f, 300f)
        assertEquals(Offset.Zero, clampPan(Offset(120f, -80f), 1f, vp))
        assertEquals(Offset.Zero, clampPan(Offset(120f, -80f), 0.5f, vp))
        assertEquals(Offset.Zero, clampPan(Offset(120f, -80f), 0.1f, vp))
        // 首帧视口还没量出来：half=0 走 half≤0 直接归零的分支，不许 coerceIn 域倒置抛异常
        assertEquals(Offset.Zero, clampPan(Offset(120f, -80f), 2f, Size.Zero))
    }

    /** 域内的平移逐轴原样保留，不做任何扰动 */
    @Test
    fun `域内平移原样保留`() {
        // s=2、视口 400×300 → 半域 200×150，取域内带符号的组合
        val p = Offset(199f, -150f)
        val r = clampPan(p, 2f, Size(400f, 300f))
        assertEquals(199f, r.x, 0f)
        assertEquals(-150f, r.y, 0f)
    }

    /** 越界的平移按域对称夹取：正负两侧对称、X/Y 各自独立 */
    @Test
    fun `越界平移对称夹取`() {
        // s=3、视口 200×200 → 半域 200×200
        val vp = Size(200f, 200f)
        val pos = clampPan(Offset(500f, 300f), 3f, vp)
        assertEquals(200f, pos.x, 0f)
        assertEquals(200f, pos.y, 0f)
        val neg = clampPan(Offset(-500f, -300f), 3f, vp)
        assertEquals(-200f, neg.x, 0f)
        assertEquals(-200f, neg.y, 0f)
        // 两轴域不同（矩形视口）时各夹各的：s=2、视口 400×200 → 半域 200×100
        val rect = clampPan(Offset(350f, -350f), 2f, Size(400f, 200f))
        assertEquals(200f, rect.x, 0f)
        assertEquals(-100f, rect.y, 0f)
    }

    /** 捏回 1x 的过程中平移随夹取连续归零：同一位移逐帧重夹，域收缩平移跟着收缩，到 1x 恰为零 */
    @Test
    fun `捏回一倍过程中平移连续归零`() {
        val vp = Size(400f, 400f)
        // 双指按住拖到 (150,150) 时停在 s=2：半域 200，域内不动
        val dragged = clampPan(Offset(150f, 150f), 2f, vp)
        assertEquals(150f, dragged.x, 0f)
        assertEquals(150f, dragged.y, 0f)
        // 保持手指不抬、只捏合：s=1.5 半域 100 → 100；s=1.1 半域 20 → 20；s=1.0 → 0
        // （1.1f−1f 在二进制下非精确 0.1，链式断言带 1e-3 容差；归零断言仍用精确等值）
        val s15 = clampPan(dragged, 1.5f, vp)
        assertEquals(100f, s15.x, 1e-3f)
        val s11 = clampPan(s15, 1.1f, vp)
        assertEquals(20f, s11.y, 1e-3f)
        val s10 = clampPan(s11, 1f, vp)
        assertEquals(Offset.Zero, s10)
    }

    /** 视口变化后陈旧 pan 以新视口重夹（P2，2026-10-04）：本工程 manifest 自持旋转，
     *  remember 的 pan 跨旋转存活——竖屏 720×1600、s=2 时 Y 域 ±800，拖到域边后转横屏
     *  1600×720，Y 域收窄到 ±360，重夹必须立即归位到 360，不得等下一次双指手势。
     *  （WotaPlayerSurface 绘制点的兜底重夹是 JVM 测不到的 graphicsLayer 行为，这条钉住
     *  它调用的同一纯函数在旋转语义下的行为。）
     */
    @Test
    fun `旋转后陈旧平移按新视口重夹`() {
        // 竖屏拖到 Y 域边缘：s=2、720×1600 → 半域 X 360 / Y 800
        val stale = clampPan(Offset(0f, 800f), 2f, Size(720f, 1600f))
        assertEquals(0f, stale.x, 0f)
        assertEquals(800f, stale.y, 0f)
        // 旋转：viewport 互换，同一 pan 态以新视口重夹 → Y 收进 ±360
        val rotated = clampPan(stale, 2f, Size(1600f, 720f))
        assertEquals(0f, rotated.x, 0f)
        assertEquals(360f, rotated.y, 0f)
    }
}
