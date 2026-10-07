package com.wotagei.cam

import com.wotagei.cam.core.ColorCurve
import com.wotagei.cam.core.CurveEdit
import com.wotagei.cam.core.CurveStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // 交错布局（texel i 连续放 R/G/B 三分量）下，恒等表应是 out[3i..3i+2] = i,i,i（按 1/(size-1) 步进）。
        // 2026-10-03 真机花图缺陷：bake 字节布局从平面改交错（plan/17 前的取证 .tmp/curve-corruption.md），
        // 旧断言钉的是 GL 从未按此消费过的平面布局。
        // size=4 ⇒ 每段采样 v = 0, 1/3, 2/3, 1
        val baked = CurveStack().bake(4)
        assertTrue(CurveStack().isPassthrough)
        assertEquals(12, baked.size)
        for (i in 0 until 4) {
            val v = i / 3f
            assertEquals(v, baked[3 * i], 1e-6f)         // texel i .r
            assertEquals(v, baked[3 * i + 1], 1e-6f)     // texel i .g
            assertEquals(v, baked[3 * i + 2], 1e-6f)     // texel i .b
        }
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
    fun `字节纹理按RGB交错布局且量化到0-255`() {
        // 2026-10-03 真机花图缺陷：bake 字节布局从平面改交错（plan/17 前的取证 .tmp/curve-corruption.md），
        // 旧断言钉的是 GL 从未按此消费过的平面布局。
        val stack = CurveStack(
            red = curve(0f to 0f, 1f to 1f),
            green = curve(0f to 0.5f, 1f to 0.5f),
            blue = curve(0f to 1f, 1f to 1f)
        )
        val bytes = stack.bakeBytes(4)
        assertEquals(12, bytes.size)
        fun u(i: Int) = bytes[i].toInt() and 0xFF
        // 交错：texel i = (R[i], G[i], B[i]) = (i/3, 0.5, 1.0)，量化 (v*255+0.5) 截断
        // ⇒ 字节序列 [0,128,255, 85,128,255, 170,128,255, 255,128,255]
        val expected = listOf(
            0 to 128, 85 to 128, 170 to 128, 255 to 128   // (r, g) 逐 texel；b 恒 255 单独断
        )
        expected.forEachIndexed { i, (r, g) ->
            assertEquals("texel $i .r", r, u(3 * i))
            assertEquals("texel $i .g", g, u(3 * i + 1))
            assertEquals("texel $i .b", 255, u(3 * i + 2))
        }
    }

    @Test
    fun `张量按RGB交错布局存放`() {
        // 2026-10-03 真机花图缺陷：bake 字节布局从平面改交错（plan/17 前的取证 .tmp/curve-corruption.md），
        // 旧断言钉的是 GL 从未按此消费过的平面布局。
        val size = 8
        val stack = CurveStack(
            red = curve(0f to 0.1f, 1f to 0.1f),
            green = curve(0f to 0.2f, 1f to 0.2f),
            blue = curve(0f to 0.3f, 1f to 0.3f)
        )
        val baked = stack.bake(size)
        assertEquals(size * 3, baked.size)
        // 交错：每个 texel 三分量依次是 R=0.1、G=0.2、B=0.3
        assertEquals(0.1f, baked[0], 1e-6f)
        assertEquals(0.2f, baked[1], 1e-6f)
        assertEquals(0.3f, baked[2], 1e-6f)
        assertEquals(0.1f, baked[3 * (size - 1)], 1e-6f)
        assertEquals(0.2f, baked[3 * (size - 1) + 1], 1e-6f)
        assertEquals(0.3f, baked[3 * (size - 1) + 2], 1e-6f)
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
    fun `含NaN或Infinity记号的畸形点被丢弃且曲线仍可解`() {
        // 外部损坏的持久串：toFloatOrNull 接受 NaN/Infinity 记号，coerceIn 对 NaN 原样放行，
        // NaN 控制点会把 LUT 烘成全黑。好点保留、两个畸形点丢弃，曲线按剩余点求解
        val c = ColorCurve.decode("0.000:0.000,NaN:0.500,0.500:Infinity,1.000:1.000")
        assertEquals(2, c.points.size)
        assertEquals(0f, c.points[0].x, 1e-6f)
        assertEquals(0f, c.points[0].y, 1e-6f)
        assertEquals(1f, c.points[1].x, 1e-6f)
        assertEquals(1f, c.points[1].y, 1e-6f)
        // 剩余 (0,0)-(1,1)：中点插值回到恒等，全表有限且落在 0..1
        assertEquals(0.5f, c.evaluate(0.5f), 1e-6f)
        c.table().forEach { v -> assertTrue("表值必须有限: $v", v.isFinite() && v in 0f..1f) }
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
    fun `加点避让邻居_无缝时放弃而不落重复x`() {
        // 回归（2026-10-04 自检）：纵向远处（hitTest 够不着）横向贴着已有点的点按，
        // 旧实现会插出 x 重复的控制点——零宽线段跳变 + 该点 moved 时 lo>hi 永久卡死
        val pts = CurveEdit.pointsOf(curve(0f to 0f, 0.04f to 0.9f, 1f to 1f))
        val before = pts.toList()
        assertNull(CurveEdit.inserted(pts, 0.03f, 0.1f))   // 落在 0.04 点的 MIN_DX 邻域内：放弃
        assertEquals(before, pts)
        // 正常避让：0.48 < 0.5 ⇒ 插在 0 与 0.5 之间，钳到 0.5−MIN_DX，与两邻居都保距
        val base = CurveEdit.pointsOf(curve(0f to 0f, 0.5f to 0.5f, 1f to 1f))
        val (next, at) = CurveEdit.inserted(base, 0.48f, 0.1f)!!
        assertEquals(1, at)
        assertEquals(0.46f, next[1].x, 1e-6f)
        assertTrue(next[2].x - next[1].x >= CurveEdit.MIN_DX - 1e-6f)
        assertNull(CurveEdit.inserted(next, 0.48f, 0.1f))  // 0.46 与 0.5 之间缝宽恰 0：再点同处放弃
        assertNull(CurveEdit.inserted(next, 0.47f, 0.1f))  // 同缝：仍放弃
    }

    @Test
    fun `编辑器模糊_混合操作后不变量恒成立`() {
        // UI 同构流（插入前先 hitTest）随机打 800 次组合拳，钉住编辑器契约：
        // 点数 2..MAX、x 严格升序且相邻间距 ≥MIN_DX、端点 x 锁 0/1、y 全在 0..1。
        // 多种子各扫一遍扩覆盖面；种子固定 = 失败可复现（非加密用途，弱随机告警不适用）
        for (seed in listOf(42L, 1337L, 20261004L)) fuzzOne(seed)
    }

    /** 单种子的组合拳序列（从 [ColorCurve.IDENTITY] 出发，每步断言不变量） */
    private fun fuzzOne(seed: Long) {
        val rnd = java.util.Random(seed)
        var pts = CurveEdit.pointsOf(ColorCurve.IDENTITY)
        var selected = -1
        repeat(800) {
            when (rnd.nextInt(100)) {
                in 0 until 45 -> {                                  // 拖动（含端点纵移）
                    val idx = rnd.nextInt(pts.size)
                    pts = CurveEdit.moved(pts, idx, rnd.nextFloat(), rnd.nextFloat())
                    selected = idx
                }
                in 45 until 75 -> {                                 // 点空白新增（先命中后插入，同 UI）
                    val gx = rnd.nextFloat()
                    val gy = rnd.nextFloat()
                    if (CurveEdit.hitTest(pts, gx, gy) < 0) {
                        val added = CurveEdit.inserted(pts, gx, gy)
                        if (added != null) {
                            pts = added.first
                            selected = added.second
                        }
                    }
                }
                else -> {                                           // 删除选中点（端点由 removed 自守）
                    if (selected > 0 && selected < pts.size - 1) {
                        pts = CurveEdit.removed(pts, selected)
                    }
                    selected = -1
                }
            }
            // ---- 不变量
            assertTrue("点数下限", pts.size >= 2)
            assertTrue("点数上限", pts.size <= CurveEdit.MAX_POINTS)
            assertEquals("端点 x=0", 0f, pts.first().x, 1e-6f)
            assertEquals("端点 x=1", 1f, pts.last().x, 1e-6f)
            for (i in 0 until pts.size - 1) {
                val gap = pts[i + 1].x - pts[i].x
                assertTrue("相邻间距 ≥MIN_DX（#$i gap=$gap）", gap >= CurveEdit.MIN_DX - 1e-6f)
            }
            pts.forEach { p ->
                assertTrue("y 在 0..1（$p）", p.y in 0f..1f)
            }
        }
    }

    @Test
    fun `控制点到达上限后不再接受新点`() {
        var pts = CurveEdit.pointsOf(ColorCurve.IDENTITY)
        var guard = 0
        // 宽间距铺点（2026-10-04 起 inserted 避让邻居：贴着已有点的点按会被放弃，
        // 旧用例那套 0.01 步进的扎堆插法本身就落在被修掉的行为里）
        while (guard++ < 20) {
            val x = (0.21f + guard * 0.13f).coerceAtMost(0.96f)
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
