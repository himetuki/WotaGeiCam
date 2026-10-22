package com.wotagei.cam

import com.wotagei.cam.ui.widget.snap
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 参数滑杆量化的单测（JVM）。
 *
 * 量化栅格必须锚在范围**下限**、量化结果必须回钳到上下限内。
 * 现状影响面不大（色温/斑马纹/ISO/色调的域本身就与步长对齐），真正会被撞到的是快门下限
 * ——HAL 常给 390625ns（1/2556s）这类非 1000 整数倍的值，锚在 0 时那个最低档永远拖不到。
 * 这条测试锁的是边界正确性，不是手感。
 */
class ParamSliderSnapTest {

    private fun s(v: Float, step: Float, lo: Float, hi: Float) = snap(v, step, lo, hi)

    @Test
    fun `栅格锚在下限所以下限本身拖得到`() {
        // 快门域：HAL 下限 390625ns、量化步长 1000ns。锚在 0 时 round(390.625)*1000=391000，
        // 最左位置永远给不出真实下限
        assertEquals(390_625f, s(390_625f, 1000f, 390_625f, 100_000_000f), 1e-2f)
        // 非对齐下限同理：2375/step50 时最低档要能取到 2375 而不是被顶到 2400
        assertEquals(2375f, s(2390f, 50f, 2375f, 7500f), 1e-4f)
        assertEquals(2425f, s(2420f, 50f, 2375f, 7500f), 1e-4f)
    }

    @Test
    fun `量化顶出上限时回钳到上限`() {
        // lo=0、hi=2399、step=50：2399 会先被四舍五入成 2400，超出本机范围
        assertEquals(2399f, s(2399f, 50f, 0f, 2399f), 1e-4f)
        assertEquals(6324f, s(6324f, 1f, 100f, 6324f), 1e-4f)
    }

    @Test
    fun `整除域内按格点取值`() {
        assertEquals(5500f, s(5520f, 50f, 2300f, 7500f), 1e-4f)
        assertEquals(88f, s(87.6f, 1f, 80f, 100f), 1e-4f)
        assertEquals(-34f, s(-33.7f, 1f, -100f, 100f), 1e-4f)
    }

    @Test
    fun `步长非正即不量化`() {
        assertEquals(1.234f, s(1.234f, 0f, 0f, 10f), 1e-6f)
        assertEquals(1.234f, s(1.234f, -1f, 0f, 10f), 1e-6f)
    }

    @Test
    fun `上下限重合的退化域不炸`() {
        assertEquals(100f, s(100f, 1f, 100f, 100f), 1e-4f)
    }

    @Test
    fun `量化结果幂等`() {
        var v = 2375f
        var guard = 0
        while (v < 7500f && guard++ < 200) {
            val once = s(v, 50f, 2375f, 7500f)
            assertEquals("对 $v 二次量化结果变了", once, s(once, 50f, 2375f, 7500f), 1e-4f)
            assertEquals("量化值越出范围", once, once.coerceIn(2375f, 7500f), 1e-4f)
            v += 37f
        }
    }
}
