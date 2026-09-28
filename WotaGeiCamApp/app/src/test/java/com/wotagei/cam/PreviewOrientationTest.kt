package com.wotagei.cam.camera

import com.wotagei.cam.record.recordOrientationHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方向链路回归：传感器方向 / 显示旋转 / 缓冲变换矩阵三路输入，
 * 断言「预览看到的转正角」==「成片播放出来的转正角」== 残余角，四种显示旋转 × 前后摄 × 两种渲染模式全等。
 *
 * 机型基准取自真机 WIKO GAR-AN60（后摄 sensorOrientation=90）实测：
 * 它的 `SurfaceTexture.getTransformMatrix` 给的是「转置型」矩阵（等价于再多顺时针转 90°），
 * 据此推得横屏成片应为 1920x1080 + 容器无 rotation，即下面的期望值。
 */
class PreviewOrientationTest {

    /** 教科书约定矩阵：`(s,t) → (s, 1-t)`，只把 GL 的 t 轴翻回图像行序，列主序 4x4 */
    private val canonicalSt = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1f, 0f, 1f
    )

    /** GAR-AN60 真机抓到的矩阵：`(s,t) → (1-t, 1-s)`，自带顺时针 270°（等价于多转 90°） */
    private val garAn60St = floatArrayOf(
        0f, -1f, 0f, 0f,
        -1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f,
        1f, 1f, 0f, 1f
    )

    /** 纯旋转矩阵（不含 GL 行序翻转）：八种标准朝向之外的异常输入，必须安全退化 */
    private val pureRotationSt = floatArrayOf(
        0f, 1f, 0f, 0f,
        -1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f,
        1f, 0f, 0f, 1f
    )

    private fun orientationWith(sensor: Int, display: Int, front: Boolean, st: FloatArray): PreviewOrientation =
        PreviewOrientation().apply {
            setSensorOrientation(sensor, mirrored = front)
            setDisplayDegrees(display)
            setStMatrix(st)
        }

    // region 残余角：会话重开不被覆写

    @Test
    fun `横屏锁定下残余角为 0 且会话重开不被覆写`() {
        val o = PreviewOrientation()
        o.setSensorOrientation(90, mirrored = false)   // 引擎开机写入
        o.setDisplayDegrees(90)                        // UI 写入显示角
        assertEquals(0, o.residualDegrees())

        // 停止录制 / 错误恢复 / 换镜头都会重开会话并再写一次裸 sensorOrientation
        repeat(3) { o.setSensorOrientation(90, mirrored = false) }
        assertEquals("会话重开后残余角必须仍是 0，否则预览顺时针转 90°", 0, o.residualDegrees())
    }

    @Test
    fun `显示角晚于传感器方向到达也能收敛`() {
        val o = PreviewOrientation()
        o.setSensorOrientation(90, mirrored = false)
        assertEquals("显示角未知时先按竖屏算", 90, o.residualDegrees())
        o.setDisplayDegrees(90)
        assertEquals(0, o.residualDegrees())
    }

    @Test
    fun `四种显示旋转与反向横屏`() {
        val o = PreviewOrientation()
        o.setSensorOrientation(90, mirrored = false)
        o.setDisplayDegrees(0)
        assertEquals(90, o.residualDegrees())
        o.setDisplayDegrees(180)
        assertEquals(270, o.residualDegrees())
        // 反向横屏与正向横屏差 180°
        o.setDisplayDegrees(270)
        assertEquals(180, o.residualDegrees())
    }

    @Test
    fun `前摄镜像位与残余角互不干扰`() {
        val o = PreviewOrientation()
        o.setSensorOrientation(270, mirrored = true)
        o.setDisplayDegrees(90)
        assertEquals(180, o.residualDegrees())
        assertEquals(true, o.mirrored)
        o.setDisplayDegrees(270)
        assertEquals(0, o.residualDegrees())
        assertEquals(true, o.mirrored)
    }

    @Test
    fun `非 90 整倍数的传感器方向被归一化`() {
        val o = PreviewOrientation()
        o.setSensorOrientation(-90, mirrored = false)
        assertEquals(270, o.residualDegrees())
        o.setSensorOrientation(450, mirrored = false)
        o.setDisplayDegrees(90)
        assertEquals(0, o.residualDegrees())
    }

    // endregion
    // region 缓冲矩阵：旋转分量只能运行时读

    @Test
    fun `教科书矩阵解析出不含旋转分量`() {
        assertEquals(0, PreviewOrientation.stRotationFromMatrix(canonicalSt))
    }

    @Test
    fun `真机转置型矩阵解析出自带 270 度`() {
        assertEquals(270, PreviewOrientation.stRotationFromMatrix(garAn60St))
    }

    @Test
    fun `矩阵未就绪时返回未知且不覆盖已解析结果`() {
        assertEquals(-1, PreviewOrientation.stRotationFromMatrix(FloatArray(16)))
        val o = orientationWith(sensor = 90, display = 90, front = false, st = garAn60St)
        assertEquals(270, o.stRotationDegrees)
        // 首帧之前拿不到矩阵：保持上一次结果，不能退回 0 让画面又歪一次
        assertFalse(o.setStMatrix(FloatArray(16)))
        assertEquals(270, o.stRotationDegrees)
    }

    @Test
    fun `解析不出标准朝向时安全退化为教科书约定`() {
        assertEquals(0, PreviewOrientation.stRotationFromMatrix(pureRotationSt))
        val o = orientationWith(sensor = 90, display = 90, front = false, st = pureRotationSt)
        assertEquals(0, o.stRotationDegrees)
        assertEquals(0, o.appliedDegrees())
    }

    @Test
    fun `矩阵换了轴才需要对调直显源的宽高`() {
        assertFalse(orientationWith(90, 90, false, canonicalSt).stSwapsSource())
        assertTrue(orientationWith(90, 90, false, garAn60St).stSwapsSource())
    }

    @Test
    fun `施加角等于残余角加矩阵分量`() {
        // 真机横屏：残余 0 + 矩阵 270 —— 正是「歪 90° 要补 270°」的实测结论
        assertEquals(270, orientationWith(90, 90, false, garAn60St).appliedDegrees())
        // 同一姿态在教科书约定机型上：矩阵不含旋转，施加角就等于残余角
        assertEquals(0, orientationWith(90, 90, false, canonicalSt).appliedDegrees())
        assertEquals(90, orientationWith(90, 0, false, canonicalSt).appliedDegrees())
        // 真机竖屏：残余 90 + 矩阵 270 = 360 → 0（矩阵那 90° 正好把竖屏要补的角抵消掉）
        assertEquals(0, orientationWith(90, 0, false, garAn60St).appliedDegrees())
    }

    // endregion
    // region 端到端一致性：预览 == 成片播放

    /**
     * GL pass 的净视觉转正角：纹理坐标先按 [applied] 旋，再被缓冲矩阵旋 (360 - k)。
     * 与 `GlRenderEngine.buildTexCoords` + `uTexMatrix` 的复合顺序同一条推导（真机四角逐点核对过）。
     */
    private fun glVisualDeg(stK: Int, applied: Int): Int = PreviewOrientation.normalize(360 - stK + applied)

    /** DIRECT：TextureView 内部已乘同一个矩阵，`setTransform` 再在视图空间旋 applied，复合结果同式 */
    private fun directVisualDeg(stK: Int, applied: Int): Int = glVisualDeg(stK, applied)

    /** 成片播放出来的转正角 = 编码进去的画面自带角 + 容器 orientationHint */
    private fun playbackDeg(contentDeg: Int, hint: Int): Int =
        PreviewOrientation.normalize(contentDeg + hint)

    @Test
    fun `两种渲染模式下预览与成片的转正角逐度相同`() {
        val sensors = intArrayOf(90, 270)
        val displays = intArrayOf(0, 90, 180, 270)
        for (st in listOf(canonicalSt, garAn60St)) {
            for (sensor in sensors) {
                for (front in booleanArrayOf(false, true)) {
                    for (display in displays) {
                        val o = orientationWith(sensor, display, front, st)
                        val residual = o.residualDegrees()
                        val previewGl = glVisualDeg(o.stRotationDegrees, o.appliedDegrees())
                        val previewDirect = directVisualDeg(o.stRotationDegrees, o.appliedDegrees())
                        // GPU 编码 pass 只抵消矩阵分量：送进编码器的恒为缓冲原样帧（自带 0 度）
                        val gpuContent = glVisualDeg(o.stRotationDegrees, o.stRotationDegrees)
                        val hintGpu = recordOrientationHint(sensor, display, front, direct = false)
                        val hintDirect = recordOrientationHint(sensor, display, front, direct = true)
                        val playGpu = playbackDeg(gpuContent, hintGpu)
                        // DIRECT 由 Camera2 直连编码器面，不经过任何缓冲变换
                        val playDirect = playbackDeg(0, hintDirect)
                        val tag = "sensor=$sensor display=$display front=$front stK=$st"
                        assertEquals("$tag 预览两模式必须同角", previewGl, previewDirect)
                        assertEquals("$tag GPU 编码帧应为缓冲原样", 0, gpuContent)
                        assertEquals("$tag 成片播放必须与预览同角", residual, playGpu)
                        assertEquals("$tag DIRECT 成片播放必须与预览同角", residual, playDirect)
                        assertEquals("$tag 预览转正角就是残余角", residual, previewGl)
                    }
                }
            }
        }
    }

    @Test
    fun `真机横屏姿态下成片与参照 App 约定一致`() {
        // GAR-AN60 横屏：1920x1080 原样帧 + 容器无 rotation
        val o = orientationWith(90, 90, front = false, st = garAn60St)
        assertEquals(0, o.residualDegrees())
        assertEquals(0, recordOrientationHint(90, 90, front = false, direct = false))
        assertEquals(0, recordOrientationHint(90, 90, front = false, direct = true))
        // 竖屏持机：容器记 90，播放器据此转成竖画幅，两条路径同值
        assertEquals(90, recordOrientationHint(90, 0, front = false, direct = false))
        assertEquals(90, recordOrientationHint(90, 0, front = false, direct = true))
    }

    // endregion
}
