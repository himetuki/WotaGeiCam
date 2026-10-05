package com.wotagei.cam

import com.wotagei.cam.player.nextCcwQuarter
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逆时针旋转四分之一圈（观看辅助）的纯函数 + 两处旋转钮接线的源码级守卫。
 *
 * 部分参考视频可能是竖屏的，转个角度看：分屏练习播放器与对比页右窗各一枚「逆时针旋转」钮，
 * 角度走 [nextCcwQuarter] 的四分之一圈计数（0→3→2→1→0，度数 = 计数 × 90）。
 * 纯函数部分测行为；接线部分锁源码——钮的 onClick 删掉 nextCcw 调用（钮点了不转）这类
 * 退化编译器不报错、运行时也只在点按后才可见，只能靠守卫拦。
 */
class RotateQuarterTest {

    // ---------------------------------------------------------------- 纯函数

    @Test
    fun `单步走向是逆时针_0到270再回0`() {
        // 逆时针 = 角度递减：0°→270°→180°→90°→0°
        assertEquals(3, nextCcwQuarter(0))
        assertEquals(2, nextCcwQuarter(3))
        assertEquals(1, nextCcwQuarter(2))
        assertEquals(0, nextCcwQuarter(1))
    }

    @Test
    fun `任意起点连按4次回原角`() {
        for (start in 0..3) {
            var q = start
            repeat(4) { q = nextCcwQuarter(q) }
            assertEquals("起点 $start 连按 4 次应回原角", start, q)
        }
    }

    @Test
    fun `计数恒在0到3_度数换算不越270`() {
        for (start in 0..3) {
            var q = start
            repeat(7) { // 多绕近两圈，每一步都要在域内
                q = nextCcwQuarter(q)
                assertTrue("计数出域：$q", q in 0..3)
                val deg = q * 90
                assertTrue("度数出域：$deg", deg in 0..270)
                assertEquals("度数应能整除90", 0, deg % 90)
            }
        }
    }

    @Test
    fun `负数与超界输入鲁棒_先折回等价角再走一步`() {
        // Kotlin 的 % 保留被除数符号，实现必须先 q%4 归一再走，否则负数入参会把负值漏给调用方
        assertEquals(3, nextCcwQuarter(-4)) // -4 ≡ 0 → 3
        assertEquals(2, nextCcwQuarter(-1)) // -1 ≡ 3 → 2
        assertEquals(2, nextCcwQuarter(-5)) // -5 ≡ 3 → 2
        assertEquals(1, nextCcwQuarter(-2)) // -2 ≡ 2 → 1
        assertEquals(3, nextCcwQuarter(4)) // 4 ≡ 0 → 3
        assertEquals(3, nextCcwQuarter(100)) // 100 ≡ 0 → 3
        assertEquals(2, nextCcwQuarter(7)) // 7 ≡ 3 → 2
        assertEquals(3, nextCcwQuarter(Int.MIN_VALUE)) // ≡ 0（先取模，不吃溢出）→ 3
    }

    // ---------------------------------------------------------------- 接线守卫

    /** 分屏练习播放器段（ui/CameraScreen.kt）：旋转钮必须真的把 nextCcw 接进 onClick */
    @Test
    fun `分屏练习播放器的旋转钮接线在位`() {
        val masked = KotlinSourceScan.codeOnly(
            KotlinSourceScan.mainSourceText("ui/CameraScreen.kt")
        )
        // SplitComparePlayer 全工程唯一（私有 composable，无重载），bodyOf 定位得到整个函数体
        val body = KotlinSourceScan.bodyOf(masked, "SplitComparePlayer")
        assertTrue(
            "分屏练习播放器缺双指缩放/平移挂载（pinchZoom）：旋转/缩放是这面播放器仅有的观看辅助",
            body.contains("pinchZoom(zoom, pan)")
        )
        assertTrue(
            "分屏练习播放器缺逆时针旋转钮（Icons.Outlined.RotateLeft）：竖屏参考片转不了角度",
            body.contains("Icons.Outlined.RotateLeft")
        )
        assertTrue(
            "分屏练习播放器缺旋转态到度数的换算（rotation = rotationQuarter * 90）：" +
                "计数不换算成度数，WotaPlayerSurface 收到的是 0/1/2/3 度",
            body.contains("rotation = rotationQuarter * 90")
        )
        assertTrue(
            "分屏练习播放器的旋转钮 onClick 没接 nextCcwQuarter：点按不转（删调用必红的突变点）",
            body.contains("nextCcwQuarter(rotationQuarter)")
        )
    }

    /** 对比页右窗段（player/CompareScreen.kt）：右窗旋转钮接线在位，左窗不得被波及 */
    @Test
    fun `对比页右窗的旋转钮接线在位_左窗不带旋转`() {
        val masked = KotlinSourceScan.codeOnly(
            KotlinSourceScan.mainSourceText("player/CompareScreen.kt")
        )
        // 双窗视频层在 CompareContent（CompareScreen 只是路由壳），锁它才是锁挂载点
        val body = KotlinSourceScan.bodyOf(masked, "CompareContent")
        // 右窗段 = 从右窗 surface 挂载点起到函数体尾（左窗段在它之前，天然不进切片）
        val atRight = body.indexOf("engine = rightEngine")
        assertTrue("对比页右窗 surface 挂载点不见了，守卫失去输入", atRight >= 0)
        val rightSlice = body.substring(atRight)
        assertTrue(
            "对比页右窗缺旋转态到度数的换算（rotation = rotateR * 90）",
            rightSlice.contains("rotation = rotateR * 90")
        )
        assertTrue(
            "对比页右窗的旋转钮 onClick 没接 nextCcwQuarter：点按不转（删调用必红的突变点）",
            rightSlice.contains("nextCcwQuarter(rotateR)")
        )
        // 左窗不加旋转：左窗那行 surface 调用（单行）不得出现 rotation 实参
        val leftLine = body.lineSequence().firstOrNull { "engine = leftEngine" in it }
        assertTrue("对比页左窗 surface 挂载点不见了，守卫失去输入", leftLine != null)
        assertFalse(
            "左窗不该带旋转（需求明说左窗不加）：${leftLine!!.trim()}",
            leftLine.contains("rotation")
        )
    }
}
