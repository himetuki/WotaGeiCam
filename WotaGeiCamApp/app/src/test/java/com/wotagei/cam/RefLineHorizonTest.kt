package com.wotagei.cam

import com.wotagei.cam.ui.widget.horizonEndpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 水平线端点的**符号约定**钉子（与 `LevelSensor.roll` 的「正 = 屏幕右侧偏低」契约配对）：
 * 画布 Y 轴向下，右端取 `cy - dy` ⇒ roll 为正时右端点在中心**上方**（地平线向右抬起）。
 * 画反了地平线，取景的人看到的引导与仪表读数正好相反——这是本用例存在的理由。
 */
class RefLineHorizonTest {

    @Test
    fun `roll为零_水平线严格过心水平`() {
        val (a, b) = horizonEndpoints(cx = 100f, cy = 50f, half = 50f, rollDegrees = 0f)
        assertEquals(50f, a.y, 1e-4f)
        assertEquals(50f, b.y, 1e-4f)
        assertEquals(50f, a.x, 1e-4f)
        assertEquals(150f, b.x, 1e-4f)
    }

    @Test
    fun `正roll_右端抬起左端下沉`() {
        val (a, b) = horizonEndpoints(100f, 50f, 50f, rollDegrees = 10f)
        assertTrue("右端应在中心上方（正 roll = 右侧偏低）", b.y < 50f)
        assertTrue("左端应在中心下方", a.y > 50f)
    }

    @Test
    fun `负roll镜像_左右抬向对调`() {
        val (a, b) = horizonEndpoints(100f, 50f, 50f, rollDegrees = -10f)
        assertTrue(a.y < 50f)
        assertTrue(b.y > 50f)
    }

    @Test
    fun `过心不变量_两端点的中点恒为中心`() {
        for (roll in listOf(-95f, -45f, 0f, 30f, 89f)) {
            val (a, b) = horizonEndpoints(100f, 50f, 50f, roll)
            assertEquals("roll=$roll", 100f, (a.x + b.x) / 2f, 1e-4f)
            assertEquals("roll=$roll", 50f, (a.y + b.y) / 2f, 1e-4f)
        }
    }

    @Test
    fun `角度对称_端点关于水平轴镜像`() {
        val up = horizonEndpoints(100f, 50f, 50f, 10f)
        val down = horizonEndpoints(100f, 50f, 50f, -10f)
        // +10 与 −10 的端点关于过心水平轴（y = 2·cy 的一半）镜像：x 相同、y 对称
        assertEquals(up.first.x, down.first.x, 1e-4f)
        assertEquals(100f, up.first.y + down.first.y, 1e-3f)
        assertEquals(up.second.x, down.second.x, 1e-4f)
        assertEquals(100f, up.second.y + down.second.y, 1e-3f)
    }
}
