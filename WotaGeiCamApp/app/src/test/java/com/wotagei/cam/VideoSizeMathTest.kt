package com.wotagei.cam

import com.wotagei.cam.camera.directDisplayMatrix
import com.wotagei.cam.core.Size
import com.wotagei.cam.core.aspectOf
import com.wotagei.cam.core.pickDirectPreviewSize
import com.wotagei.cam.core.screenAspectOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DIRECT 直显与分辨率标注的**纯函数**覆盖（无 Android 依赖，JVM 直跑）。
 *
 * 边界说明：`directDisplayMatrix` 的旋转/镜像数值口径有真机证据（DIRECT 与 GPU 同角度），
 * 本文件只钉**退化输入**（零尺寸 → 恒等）与**无约定争议**的结构性质（镜像翻转 m00 符号），
 * 不按猜测的变换方向写数值期望——把猜测固化成用例比没有用例更糟。
 */
class VideoSizeMathTest {

    // region pickDirectPreviewSize（DIRECT 预览流选档）

    private val c169 = listOf(
        Size(1280, 720),
        Size(960, 540),
        Size(640, 360)
    )

    @Test
    fun `同比例且不小于视图的最小档`() {
        val pick = pickDirectPreviewSize(
            c169, Size(1280, 720), viewWidthPx = 800, viewHeightPx = 450
        )
        // 视图面积 360k：640x360(230k) 不够 → 960x540(518k) 是满足的最小档
        assertEquals(Size(960, 540), pick)
    }

    @Test
    fun `视图极小时取满足上限的最小档`() {
        val pick = pickDirectPreviewSize(
            c169, Size(1280, 720), viewWidthPx = 100, viewHeightPx = 100
        )
        assertEquals(Size(640, 360), pick)
    }

    @Test
    fun `候选全部小于视图时退最大档_靠等比适配黑边`() {
        val pick = pickDirectPreviewSize(
            c169, Size(1280, 720), viewWidthPx = 2560, viewHeightPx = 1440
        )
        assertEquals(Size(1280, 720), pick)
    }

    @Test
    fun `视图未布局时取最大档_不做不小于视图筛选`() {
        val pick = pickDirectPreviewSize(
            c169, Size(1280, 720), viewWidthPx = 0, viewHeightPx = 0
        )
        assertEquals(Size(1280, 720), pick)
    }

    @Test
    fun `无同比例档时退全部候选_仍受上限与视图规则约束`() {
        val pick = pickDirectPreviewSize(
            listOf(Size(640, 480)), Size(1280, 720), viewWidthPx = 100, viewHeightPx = 100
        )
        assertEquals(Size(640, 480), pick)
    }

    @Test
    fun `空候选与零尺寸录像返回null`() {
        assertNull(pickDirectPreviewSize(emptyList(), Size(1280, 720), 100, 100))
        assertNull(pickDirectPreviewSize(c169, Size(0, 0), 100, 100))
    }

    // endregion

    // region 比例标注与屏幕比（纯整数入参）

    @Test
    fun `screenAspect归一为不小于1_非法输入给0`() {
        assertEquals(2.0, screenAspectOf(800, 400), 1e-9)
        assertEquals(2.0, screenAspectOf(400, 800), 1e-9)   // 竖横屏归一：长边/短边
        assertEquals(1.0, screenAspectOf(720, 720), 1e-9)
        assertEquals(0.0, screenAspectOf(0, 720), 1e-9)
        assertEquals(0.0, screenAspectOf(-1, 720), 1e-9)
    }

    @Test
    fun `常用比例按容差命中_怪尺寸gcd约分`() {
        assertEquals("16:9", aspectOf(1920, 1080))
        assertEquals("4:3", aspectOf(1440, 1080))
        assertEquals("20:9", aspectOf(2000, 900))
        // 1920x1088（HAL 常见近似档）：比值 1.7647 与 16:9 差 0.0147 ≤ 0.02 → 命中 16:9
        assertEquals("16:9", aspectOf(1920, 1088))
        // 不在常用表里：gcd 约分兜底（2.1875 距最近的 21:9 也有 0.146，超出容差）
        assertEquals("35:16", aspectOf(700, 320))
        assertEquals("-", aspectOf(0, 100))
    }

    // endregion

    // region directDisplayMatrix 的退化与结构性质

    @Test
    fun `零尺寸输入给恒等矩阵_不除零`() {
        val m = directDisplayMatrix(0, 360, 640, 480, 90, false)
        assertTrue(m.contentEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)))
    }

    @Test
    fun `镜像只翻转屏幕横轴_m00与m01变号`() {
        val plain = directDisplayMatrix(800, 360, 640, 480, 0, false)
        val mirrored = directDisplayMatrix(800, 360, 640, 480, 0, true)
        // 旋转 0 时 s=0：镜像只作用在 m00/m02 两处横轴项；m00 必然变号、m11 不变
        assertEquals(-plain[0], mirrored[0], 1e-6f)
        assertEquals(plain[4], mirrored[4], 1e-6f)
        assertFalse(plain[0] < 0f)
        assertTrue(mirrored[0] < 0f)
    }

    // endregion
}
