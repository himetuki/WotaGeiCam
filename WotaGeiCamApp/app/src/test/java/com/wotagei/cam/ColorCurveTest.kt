package com.wotagei.cam

import com.wotagei.cam.core.ColorCurve
import com.wotagei.cam.core.CurveEdit
import com.wotagei.cam.core.CurvePreset
import com.wotagei.cam.core.CurveStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RGB 曲线纯模型单测（JVM，不需设备）。
 * GL 侧只是把这里烘焙出的表当纹理查，所以表的正确性与边界必须在纯函数层面锁死。
 */
class ColorCurveTest {

    private fun curve(vararg pts: Pair<Float, Float>) =
        ColorCurve(pts.map { ColorCurve.Point(it.first, it.second) })

    @Test
    fun `无控制点即直通`() {
        val c = ColorCurve.IDENTITY
        assertEquals(0f, c.evaluate(0f), 1e-6f)
        assertEquals(0.37f, c.evaluate(0.37f), 1e-6f)
        assertEquals(1f, c.evaluate(1f), 1e-6f)
        assertTrue(c.isIdentity())
    }

    @Test
    fun `单控制点即常量`() {
        val c = curve(0.5f to 0.2f)
        assertEquals(0.2f, c.evaluate(0f), 1e-6f)
        assertEquals(0.2f, c.evaluate(1f), 1e-6f)
    }

    @Test
    fun `两点之间线性插值`() {
        val c = curve(0f to 0f, 1f to 0.5f)
        assertEquals(0.25f, c.evaluate(0.5f), 1e-6f)
        assertEquals(0.5f, c.evaluate(1f), 1e-6f)
    }

    @Test
    fun `端点外钳制不外推`() {
        val c = curve(0.25f to 0.5f, 0.75f to 0.5f)
        // 首点之前取首点 y，末点之后取末点 y
        assertEquals(0.5f, c.evaluate(0f), 1e-6f)
        assertEquals(0.5f, c.evaluate(1f), 1e-6f)
    }

    @Test
    fun `越界控制点被钳回0到1`() {
        val c = curve(0f to -0.4f, 1f to 1.8f)
        c.table().forEach { v -> assertTrue("表值越界: $v", v in 0f..1f) }
    }

    @Test
    fun `单调控制点烘出非降表`() {
        val c = curve(0f to 0f, 0.33f to 0.4f, 0.66f to 0.7f, 1f to 1f)
        val t = c.table()
        for (i in 1 until t.size) {
            assertTrue("第 $i 档出现倒退：${t[i - 1]} -> ${t[i]}", t[i] >= t[i - 1] - 1e-6f)
        }
    }

    @Test
    fun `表索引与输入一一对应`() {
        val c = curve(0f to 0f, 1f to 1f)
        val t = c.table(5)
        assertEquals(0f, t[0], 1e-6f)
        assertEquals(0.25f, t[1], 1e-6f)
        assertEquals(1f, t[4], 1e-6f)
    }

    @Test
    fun `全直通时张量等于恒等表`() {
        // size=4 ⇒ 每段采样 v = 0, 1/3, 2/3, 1；R 段 [0..3]、G 段 [4..7]、B 段 [8..11]
        val baked = CurveStack().bake(4)
        assertTrue(CurveStack().isPassthrough)
        assertEquals(12, baked.size)
        assertEquals(0f, baked[0], 1e-6f)
        assertEquals(1f / 3f, baked[1], 1e-6f)
        assertEquals(1f, baked[3], 1e-6f)
        assertEquals(0f, baked[4], 1e-6f)
        assertEquals(2f / 3f, baked[6], 1e-6f)
        assertEquals(0f, baked[8], 1e-6f)
        assertEquals(1f, baked[11], 1e-6f)
    }

    @Test
    fun `先过白曲线再过本色曲线`() {
        // 白：压到一半；红：再乘 0.5 ⇒ 红通道 1.0 -> 0.5 -> 0.25
        val stack = CurveStack(
            master = curve(0f to 0f, 1f to 0.5f),
            red = curve(0f to 0f, 1f to 0.5f)
        )
        assertEquals(0.25f, stack.evaluate(ColorCurve.CHANNEL_RED, 1f), 1e-6f)
        // 绿通道没有本色曲线，只吃白曲线的结果
        assertEquals(0.5f, stack.evaluate(ColorCurve.CHANNEL_GREEN, 1f), 1e-6f)
        assertFalse(stack.isPassthrough)
    }

    @Test
    fun `蓝通道曲线不影响红绿`() {
        val stack = CurveStack(blue = curve(0f to 1f, 1f to 1f))
        assertEquals(1f, stack.evaluate(ColorCurve.CHANNEL_BLUE, 0.2f), 1e-6f)
        assertEquals(0.2f, stack.evaluate(ColorCurve.CHANNEL_RED, 0.2f), 1e-6f)
        assertEquals(0.2f, stack.evaluate(ColorCurve.CHANNEL_GREEN, 0.2f), 1e-6f)
    }

    @Test
    fun `字节纹理按 RGB 三段且量化到 0-255`() {
        val stack = CurveStack(
            red = curve(0f to 0f, 1f to 1f),
            green = curve(0f to 0.5f, 1f to 0.5f),
            blue = curve(0f to 1f, 1f to 1f)
        )
        val bytes = stack.bakeBytes(4)
        assertEquals(12, bytes.size)
        fun u(i: Int) = bytes[i].toInt() and 0xFF
        assertEquals(0, u(0))          // R 段起点
        assertEquals(255, u(3))        // R 段终点
        assertEquals(128, u(4))        // G 段恒 0.5
        assertEquals(128, u(7))
        assertEquals(255, u(8))        // B 段恒 1
        assertEquals(255, u(11))
    }

    @Test
    fun `张量按RGB三段连续存放`() {
        val size = 8
        val stack = CurveStack(
            red = curve(0f to 0.1f, 1f to 0.1f),
            green = curve(0f to 0.2f, 1f to 0.2f),
            blue = curve(0f to 0.3f, 1f to 0.3f)
        )
        val baked = stack.bake(size)
        assertEquals(size * 3, baked.size)
        assertEquals(0.1f, baked[0], 1e-6f)
        assertEquals(0.2f, baked[size], 1e-6f)
        assertEquals(0.3f, baked[2 * size], 1e-6f)
    }

    @Test
    fun `持久化串往返保持表不变`() {
        val stack = CurveStack(
            master = curve(0f to 0.1f, 0.5f to 0.55f, 1f to 0.9f),
            blue = curve(0f to 0.05f, 1f to 0.8f)
        )
        val back = CurveStack.decode(stack.encode())
        assertEquals(stack, back)
        // 量化到 3 位小数后仍等价：查表逐档比对，容差取半档 LSB
        val a = stack.bake()
        val b = back.bake()
        for (i in a.indices) assertEquals(a[i], b[i], 0.002f)
    }

    @Test
    fun `坏持久化串回落恒等`() {
        assertTrue(CurveStack.decode(null).isPassthrough)
        assertTrue(CurveStack.decode("").isPassthrough)
        assertTrue(CurveStack.decode(";;;").isPassthrough)
        assertTrue(CurveStack.decode("乱码").isPassthrough)
        assertTrue(CurveStack.decode("0.5:0.5").isPassthrough)          // 段数不足
        // 段内混入坏值：好点保留、坏点丢弃，不抛异常
        val partial = CurveStack.decode("0.000:0.000,zz:0.5,1.000:0.75;;;")
        assertFalse(partial.isPassthrough)
        assertEquals(2, partial.master.points.size)
        assertEquals(0.75f, partial.master.evaluate(1f), 1e-6f)
    }

    @Test
    fun `内置色调档烘出的表都在合法域且非直通`() {
        assertEquals("原图", CurvePreset.NONE.label)
        assertEquals(4, CurvePreset.values().size)
        assertTrue(CurvePreset.NONE.stack.isPassthrough)
        listOf(CurvePreset.FLAT, CurvePreset.CONTRAST, CurvePreset.TEAL_ORANGE).forEach { preset ->
            val bytes = preset.stack.bakeBytes()
            assertEquals(ColorCurve.TABLE_SIZE * 3, bytes.size)
            assertFalse("${preset.label} 应改变画面", preset.stack.isPassthrough)
            preset.stack.bake().forEach { v -> assertTrue("表值越界: $v", v in 0f..1f) }
        }
        // 灰调抬黑压白：端点必然偏离对角线
        val flat = CurvePreset.FLAT.stack
        assertTrue(flat.evaluate(ColorCurve.CHANNEL_GREEN, 0f) > 0f)
        assertTrue(flat.evaluate(ColorCurve.CHANNEL_GREEN, 1f) < 1f)
        // 青橙的阴影偏蓝、高光偏红：同输入下 B 抬 R 压
        val teal = CurvePreset.TEAL_ORANGE.stack
        assertTrue(
            teal.evaluate(ColorCurve.CHANNEL_BLUE, 0.1f) > teal.evaluate(ColorCurve.CHANNEL_RED, 0.1f)
        )
    }

    @Test
    fun `编辑器补齐端点且恒等曲线判为直通`() {
        val pts = CurveEdit.pointsOf(ColorCurve.IDENTITY)
        assertEquals(2, pts.size)
        assertEquals(0f, pts.first().x, 1e-6f)
        assertEquals(1f, pts.last().x, 1e-6f)
        assertTrue(curveOfTest(pts).isIdentity())
        // 缺端点的持久化数据进编辑器要能补齐，且补的点沿用最近的 y（不改变曲线形状）
        val patched = CurveEdit.pointsOf(curve(0.5f to 0.3f))
        assertEquals(3, patched.size)
        assertEquals(0f, patched.first().x, 1e-6f)
        assertEquals(0.3f, patched.first().y, 1e-6f)
        assertEquals(1f, patched.last().x, 1e-6f)
        assertEquals(0.3f, CurveStack(master = curveOfTest(patched)).evaluate(ColorCurve.CHANNEL_RED, 1f), 1e-6f)
    }

    @Test
    fun `端点只能纵向移动中间点被邻居夹住`() {
        val pts = CurveEdit.pointsOf(curve(0f to 0f, 0.5f to 0.5f, 1f to 1f))
        val head = CurveEdit.moved(pts, 0, 0.9f, 0.2f)
        assertEquals(0f, head[0].x, 1e-6f)
        assertEquals(0.2f, head[0].y, 1e-6f)
        val mid = CurveEdit.moved(pts, 1, 0.99f, 0.9f)
        // 邻居在 0 与 1，减去 MIN_DX 即为可动上下限
        assertEquals(1f - CurveEdit.MIN_DX, mid[1].x, 1e-6f)
        assertEquals(0.9f, mid[1].y, 1e-6f)
        val low = CurveEdit.moved(pts, 1, -0.5f, -0.5f)
        assertEquals(CurveEdit.MIN_DX, low[1].x, 1e-6f)
        assertEquals(0f, low[1].y, 1e-6f)
    }

    @Test
    fun `加点按 x 插入并返回选中下标`() {
        val pts = CurveEdit.pointsOf(ColorCurve.IDENTITY)
        val (withMid, index) = CurveEdit.inserted(pts, 0.4f, 0.7f)!!
        assertEquals(1, index)
        assertEquals(3, withMid.size)
        assertEquals(0.4f, withMid[1].x, 1e-6f)
        val (again, second) = CurveEdit.inserted(withMid, 0.7f, 0.2f)!!
        assertEquals(2, second)
        assertEquals(0.7f, again[2].x, 1e-6f)
        // 贴边会被钳到 MIN_DX 内侧：不许插到端点上，否则中间点退化成端点、横向可动区被挤没
        assertEquals(
            CurveEdit.MIN_DX,
            CurveEdit.inserted(pts, 0.001f, 0.5f)!!.first[1].x,
            1e-6f
        )
        assertEquals(
            1f - CurveEdit.MIN_DX,
            CurveEdit.inserted(pts, 0.999f, 0.5f)!!.first[1].x,
            1e-6f
        )
    }

    @Test
    fun `控制点到达上限后不再接受新点`() {
        var pts = CurveEdit.pointsOf(ColorCurve.IDENTITY)
        var guard = 0
        while (guard++ < 20) {
            val x = 0.05f + guard * 0.01f
            val added = CurveEdit.inserted(pts, x, 0.5f) ?: break
            pts = added.first
        }
        assertEquals(CurveEdit.MAX_POINTS, pts.size)
        assertTrue(CurveEdit.inserted(pts, 0.5f, 0.5f) == null)
        // 到上限后拖拽已有控制点仍然有效
        assertEquals(CurveEdit.MAX_POINTS, CurveEdit.moved(pts, 2, 0.33f, 0.6f).size)
    }

    @Test
    fun `命中测试取最近点且端点可删性正确`() {
        val pts = CurveEdit.pointsOf(curve(0f to 0f, 0.5f to 0.6f, 1f to 1f))
        assertEquals(1, CurveEdit.hitTest(pts, 0.52f, 0.61f))
        assertEquals(0, CurveEdit.hitTest(pts, 0.01f, 0.01f))
        assertTrue(CurveEdit.hitTest(pts, 0.3f, 0.3f) < 0)
        assertEquals(pts, CurveEdit.removed(pts, 0))
        assertEquals(pts, CurveEdit.removed(pts, 2))
        assertEquals(2, CurveEdit.removed(pts, 1).size)
    }

    /** 与 UI 侧同一套判定：点集全落在 y=x 上即视为恒等 */
    private fun curveOfTest(points: List<ColorCurve.Point>): ColorCurve {
        val c = ColorCurve(points)
        return if (c.isIdentity()) ColorCurve.IDENTITY else c
    }
}
