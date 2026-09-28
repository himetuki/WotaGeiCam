package com.wotagei.cam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 换镜头时画幅档的落档规则（#50）。
 * 数值取自本机取证：广角给得到 1920x1080 16:9，超广角最高只到 1280x720 16:9，
 * 另有 1600x1200 这类 4:3 档 —— 旧实现按「像素量不超过上一档」挑，会先掉到 4:3、
 * 再在切回广角时停在低档回不去。
 */
class SizeFallbackTest {

    private val wide = listOf(
        Size(3264, 2448), Size(1920, 1440), Size(1920, 1080),
        Size(1920, 864), Size(1280, 720), Size(960, 720), Size(720, 720)
    )
    private val ultra = listOf(
        Size(1600, 1200), Size(1280, 720), Size(960, 540), Size(720, 720), Size(640, 480)
    )

    @Test
    fun `原样能给就原样给`() {
        assertEquals(Size(1920, 1080), resolveSizeFor(Size(1920, 1080), wide))
    }

    @Test
    fun `新镜头给不起时保住画幅比例而不是像素量`() {
        // 旧实现会挑 1600x1200（像素量更接近但不等比例），正确做法是留在 16:9 里最大的那档
        assertEquals(Size(1280, 720), resolveSizeFor(Size(1920, 1080), ultra))
    }

    @Test
    fun `来回切镜头不会单向棘轮到低档`() {
        val want = Size(1920, 1080)
        assertEquals(Size(1280, 720), resolveSizeFor(want, ultra))
        // 关键断言：切回广角要能恢复用户原本要的 1920x1080，而不是停在 720
        assertEquals(Size(1920, 1080), resolveSizeFor(want, wide))
        // 反复来回仍然稳定在用户要的那档（落档输入永远是 requestedSize，不是上一次的结果）
        var last = want
        repeat(3) {
            last = resolveSizeFor(want, ultra)!!
            last = resolveSizeFor(want, wide)!!
        }
        assertEquals(Size(1920, 1080), last)
    }

    @Test
    fun `没有同比例档时才退回比例无关的最近档`() {
        val only4to3 = listOf(Size(1600, 1200), Size(640, 480))
        assertEquals(Size(1600, 1200), resolveSizeFor(Size(1920, 1080), only4to3))
    }

    @Test
    fun `要的比所有档都小时取最接近的一档而非最小档`() {
        val big = listOf(Size(3840, 2160), Size(4096, 2160))
        assertEquals(Size(3840, 2160), resolveSizeFor(Size(1920, 1080), big))
    }

    @Test
    fun `空能力表返回空`() {
        assertNull(resolveSizeFor(Size(1920, 1080), emptyList()))
    }
}
