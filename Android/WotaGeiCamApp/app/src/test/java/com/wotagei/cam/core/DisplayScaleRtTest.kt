package com.wotagei.cam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上屏离屏 RT 的**尺寸账**（docs/plan/24 R3 §2.2/§2.3，25 §11.5）。
 *
 * 三条纯函数、五个档位，全是 JVM 能钉死的东西；它们错在真机上的表现却都很"安静"：
 * - 目标尺寸算成 ceil ⇒ RT 比需要的大一格，白占显存（4K 下不明显，低端机上是真显存）；
 * - 128 阈值判反 ⇒ 小窗口上点得动 16 档，放大 blit 出来是一块马赛克；
 * - 竖屏忘换轴 ⇒ 只有横屏可用（竖屏全灰），用户以为功能坏了。
 */
class DisplayScaleRtTest {

    // ------------------------------------------------------------------ 各档

    @Test
    fun `各档按等比区除以除数且向下取整`() {
        val out = IntArray(2)
        // 1920×1080（横屏 16:9）：/2 /4 /8 /16
        assertTrue(resolveDisplayRtInto(out, 1920, 1080, 2))
        assertEquals(960, out[0])
        assertEquals(540, out[1])
        assertTrue(resolveDisplayRtInto(out, 1920, 1080, 4))
        assertEquals(480, out[0])
        assertEquals(270, out[1])
        assertTrue(resolveDisplayRtInto(out, 1920, 1080, 8))
        assertEquals(240, out[0])
        assertEquals(135, out[1])
        // 1080/16 = 67 < 128 ⇒ 16 档在 1080p 等比区上**不可用**
        assertFalse("1080p 等比区上 16 档必须判不可用（高 67px）", resolveDisplayRtInto(out, 1920, 1080, 16))
        // 除数 1 = 原片（引擎不会用它建 RT，但纯函数口径要自洽）
        assertTrue(resolveDisplayRtInto(out, 1920, 1080, 1))
        assertEquals(1920, out[0])
        assertEquals(1080, out[1])
        // floor 而不是 ceil：1919/2 = 959.5 → 959
        assertTrue(resolveDisplayRtInto(out, 1919, 1081, 2))
        assertEquals(959, out[0])
        assertEquals(540, out[1])
    }

    @Test
    fun `竖屏等比区同样各档可用`() {
        val out = IntArray(2)
        // 竖屏 1080×1920：/2 /4 /8 都可（短边 1080 够）
        assertTrue(resolveDisplayRtInto(out, 1080, 1920, 2))
        assertEquals(540, out[0])
        assertEquals(960, out[1])
        assertTrue(resolveDisplayRtInto(out, 1080, 1920, 8))
        assertEquals(135, out[0])
        assertEquals(240, out[1])
        assertFalse("竖屏 1080 短边 /16 = 67 ⇒ 不可用", resolveDisplayRtInto(out, 1080, 1920, 16))
    }

    // ------------------------------------------------------------------ 128 边界

    @Test
    fun `两维都到128才可用_边界两侧各点一次`() {
        val out = intArrayOf(-1, -1)
        // 256×256 /2 = 128×128 ⇒ 恰好可用
        assertTrue(resolveDisplayRtInto(out, 256, 256, 2))
        assertEquals(128, out[0])
        assertEquals(128, out[1])
        // 255×255 /2 = 127×127 ⇒ 差 1px 也不可用（且不许改写调用方数组）
        val before = out.copyOf()
        assertFalse(resolveDisplayRtInto(out, 255, 255, 2))
        assertTrue("不可用时不许写数组", out.contentEquals(before))
        // 一维够、另一维不够 ⇒ 不可用（不能只看长边）
        assertFalse(resolveDisplayRtInto(out, 1000, 255, 2))
        assertFalse(resolveDisplayRtInto(out, 255, 1000, 2))
        assertTrue(resolveDisplayRtInto(out, 1000, 256, 2))
        assertEquals(500, out[0])
        assertEquals(128, out[1])
    }

    @Test
    fun `非法输入一律false且不写数组`() {
        val out = intArrayOf(-1, -1)
        assertFalse(resolveDisplayRtInto(out, 0, 1080, 2))
        assertFalse(resolveDisplayRtInto(out, 1920, 0, 2))
        assertFalse(resolveDisplayRtInto(out, -10, -10, 2))
        assertFalse(resolveDisplayRtInto(out, 1920, 1080, 0))
        assertFalse(resolveDisplayRtInto(out, 1920, 1080, -2))
        assertEquals(-1, out[0])
        assertEquals(-1, out[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `输出数组不足两位是编程错误`() {
        resolveDisplayRtInto(IntArray(1), 1920, 1080, 2)
    }

    // ------------------------------------------------------------------ Pair 版（UI 用）

    @Test
    fun `Pair版与数组版同源`() {
        assertEquals(960 to 540, resolveDisplayRtOrNull(1920, 1080, 2))
        assertEquals(480 to 270, resolveDisplayRtOrNull(1920, 1080, 4))
        assertNull(resolveDisplayRtOrNull(1920, 1080, 16))
        assertNull(resolveDisplayRtOrNull(255, 255, 2))
        assertNull(resolveDisplayRtOrNull(0, 1080, 2))
        assertNull(resolveDisplayRtOrNull(1920, 1080, 0))
        // 与数组版逐档同值（UI 灰显与引擎判定不许分叉）
        val out = IntArray(2)
        for (d in listOf(1, 2, 4, 8, 16)) {
            val p = resolveDisplayRtOrNull(1440, 1080, d)
            val ok = resolveDisplayRtInto(out, 1440, 1080, d)
            assertEquals("除数 $d 的两版可用性必须一致", ok, p != null)
            if (p != null) assertEquals(p, out[0] to out[1])
        }
    }

    // ------------------------------------------------------------------ 回落：最近可用档

    @Test
    fun `回落从下一档往下找可用档_ORIG兜底`() {
        // 1080p 等比区：16 不可用（67px）、8 可用（135px）⇒ 16 → 8
        assertEquals(DisplayScale.EIGHTH, nearestDisplayScale(1920, 1080, DisplayScale.SIXTEENTH))
        // 小窗口 1080×600：16=67/37 不行、8=135/75 不行、4=270/150 可以 ⇒ 4
        assertEquals(DisplayScale.QUARTER, nearestDisplayScale(1080, 600, DisplayScale.SIXTEENTH))
        // 再小 600×400：16/8/4 都不行（400/4=100<128）、2=300×200 可以 ⇒ 2
        assertEquals(DisplayScale.HALF, nearestDisplayScale(600, 400, DisplayScale.SIXTEENTH))
        // 全部不可用 ⇒ 原片兜底（300×200：/2 = 150×100，高仍 <128）
        assertEquals(DisplayScale.ORIG, nearestDisplayScale(300, 200, DisplayScale.SIXTEENTH))
        // 已经原片 ⇒ 原片（不许越界往上）
        assertEquals(DisplayScale.ORIG, nearestDisplayScale(1920, 1080, DisplayScale.ORIG))
        // 从 HALF 回落只走到原片（不从 HALF 跳回 ORIG 以外）
        assertEquals(DisplayScale.ORIG, nearestDisplayScale(400, 300, DisplayScale.HALF))
    }

    @Test
    fun `回落必须严格低于当前档_避免同档反复回落`() {
        // 关键性质（2026-10-27 P1）：即使当前档"尺寸上可用"，回落也必须再降一步 ——
        // 否则"纹理上限/FBO/program 级失败"会让 UI 写回同一个值、下一帧再回落（死循环）
        assertEquals(
            "16 档尺寸可用时仍要退到 8（回落是对失败的反应）",
            DisplayScale.EIGHTH, nearestDisplayScale(3840, 2160, DisplayScale.SIXTEENTH)
        )
        assertEquals(DisplayScale.QUARTER, nearestDisplayScale(3840, 2160, DisplayScale.EIGHTH))
        assertTrue(
            "任何档的回落结果除数都必须更小",
            listOf(
                DisplayScale.SIXTEENTH, DisplayScale.EIGHTH,
                DisplayScale.QUARTER, DisplayScale.HALF
            ).all { nearestDisplayScale(3840, 2160, it).divisor < it.divisor }
        )
    }

    @Test
    fun `回落时等比区没量到就直接原片`() {
        assertEquals(DisplayScale.ORIG, nearestDisplayScale(0, 0, DisplayScale.SIXTEENTH))
        assertEquals(DisplayScale.ORIG, nearestDisplayScale(1920, 0, DisplayScale.EIGHTH))
        assertEquals(DisplayScale.ORIG, nearestDisplayScale(-1, -1, DisplayScale.QUARTER))
        // 竖屏：1080×1920 /8 = 135×240 可用 ⇒ 16 回落到 8
        assertEquals(DisplayScale.EIGHTH, nearestDisplayScale(1080, 1920, DisplayScale.SIXTEENTH))
    }

    // ------------------------------------------------------------------ 读数标签

    @Test
    fun `读数档位文案与原片本地化标签同源`() {
        // 胶囊下方读数 = 档位标签（24 R3 钉子 3）：原片走本地化串，其余用除数字面量
        assertEquals("原片", displayScaleReadoutLabel(DisplayScale.ORIG, "原片"))
        assertEquals("2", displayScaleReadoutLabel(DisplayScale.HALF, "原片"))
        assertEquals("4", displayScaleReadoutLabel(DisplayScale.QUARTER, "原片"))
        assertEquals("8", displayScaleReadoutLabel(DisplayScale.EIGHTH, "原片"))
        assertEquals("16", displayScaleReadoutLabel(DisplayScale.SIXTEENTH, "原片"))
        // 原片标签由调用方给（UI 传 stringResource），纯函数不写死中文
        assertEquals("Orig", displayScaleReadoutLabel(DisplayScale.ORIG, "Orig"))
    }

    // ------------------------------------------------------------------ 重建判据

    @Test
    fun `尺寸变了才重建`() {
        assertFalse("同尺寸不重建", displayRtNeedsRebuild(960, 540, 960, 540))
        assertTrue("宽变", displayRtNeedsRebuild(960, 540, 480, 540))
        assertTrue("高变", displayRtNeedsRebuild(960, 540, 960, 270))
        // 目标非法（还没量到等比区）⇒ 不动已建资源（拆了下一帧又要建）
        assertFalse(displayRtNeedsRebuild(960, 540, 0, 540))
        assertFalse(displayRtNeedsRebuild(960, 540, 960, 0))
    }
}
