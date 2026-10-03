package com.wotagei.cam

import com.wotagei.cam.core.ColorCurve
import com.wotagei.cam.core.CurveStack
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * bake 字节 → GL 纹理解释的桥测试：**这就是 2026-10-03 真机花图的 JVM 复刻桥**。
 *
 * GL 侧（`GlRenderEngine.ensureCurveTexture`）把 `bakeBytes` 的 768 字节按 256×1 GL_RGB
 * 交错纹理上传，shader（`Shaders.CURVE_DECL` 的 `applyCurve`）对同一 texel 取 .r/.g/.b
 * 当作对应通道的查表值。本测试按 **GL 的解释方式**解码字节——
 * texel[j].r = bytes[3j]、.g = bytes[3j+1]、.b = bytes[3j+2]——逐 texel 断言与
 * `CurveStack.evaluate` 的量化值一致。
 *
 * 布局契约曾断裂：bake 曾按平面三段（R‖G‖B）产出，GL 按交错解释 ⇒ GPU 执行的是
 * 三轮 0→255 锯齿乱表，真机表现为色阶断裂 + 边缘 RGB 描边的花图（取证
 * `.tmp/curve-corruption.md`，plan/17 前的记录）。这条桥是防回归锁：
 * 现状（改前）代码必红（锯齿），修复后转绿；任何一侧再单方面改布局也必红。
 *
 * ±1 LSB 容差的原因：GL_LINEAR 下 texel 中心在 (j+0.5)/256，而 LUT 输入按 j/255 采样，
 * 两个刻度错位不到半个 texel，8bit 量化后最坏差 1——这是刻度近似的固有误差，
 * 不是布局回归，所以断言允许 ±1。
 */
class CurveBakeGlBridgeTest {

    /**
     * 四条通道期望值互不相同（master 抬黑压白、R 抬、G 慢坡、B 压），
     * 任意的通道错位/交错错读都会在某个 texel 上撞出超出容差的偏差。
     */
    private val stack = CurveStack(
        master = ColorCurve(listOf(ColorCurve.Point(0f, 0.05f), ColorCurve.Point(1f, 0.95f))),
        red = ColorCurve(listOf(ColorCurve.Point(0f, 0f), ColorCurve.Point(0.5f, 0.6f), ColorCurve.Point(1f, 1f))),
        green = ColorCurve(listOf(ColorCurve.Point(0f, 0.2f), ColorCurve.Point(1f, 0.8f))),
        blue = ColorCurve(listOf(ColorCurve.Point(0f, 0.1f), ColorCurve.Point(0.5f, 0.35f), ColorCurve.Point(1f, 0.9f)))
    )

    @Test
    fun `bake字节按GL交错的解释逐texel等于evaluate量化值`() {
        val bytes = stack.bakeBytes()
        assertEquals(ColorCurve.TABLE_SIZE * 3, bytes.size)
        fun u(i: Int) = bytes[i].toInt() and 0xFF
        for (j in 0 until ColorCurve.TABLE_SIZE) {
            // bake 的采样刻度与 LUT 输入一致：texel j 对应输入 j/(TABLE_SIZE-1)
            val x = j / (ColorCurve.TABLE_SIZE - 1).toFloat()
            val base = 3 * j
            val msg = "texel $j"
            assertEquals(
                "$msg .r",
                quantized(stack.evaluate(ColorCurve.CHANNEL_RED, x)),
                u(base).toDouble(),
                1.0
            )
            assertEquals(
                "$msg .g",
                quantized(stack.evaluate(ColorCurve.CHANNEL_GREEN, x)),
                u(base + 1).toDouble(),
                1.0
            )
            assertEquals(
                "$msg .b",
                quantized(stack.evaluate(ColorCurve.CHANNEL_BLUE, x)),
                u(base + 2).toDouble(),
                1.0
            )
        }
    }

    /** 与 `bakeBytes` 同一量化式（v*255 + 0.5 截断、钳到 0..255），桥测试独立复算期望值 */
    private fun quantized(v: Float): Double =
        ((v * 255f) + 0.5f).toInt().coerceIn(0, 255).toDouble()
}
