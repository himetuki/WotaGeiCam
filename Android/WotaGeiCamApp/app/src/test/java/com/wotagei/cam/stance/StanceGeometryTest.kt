package com.wotagei.cam.stance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B14 叠加几何的纯函数契约：只认实测/枚举量，不做任何物理猜测。
 *
 * 方向约定与 [StanceAnalysis.rotateRgba] 一致（顺时针），因此每个用例都从
 * 「传感器坐标里的矩形」出发，分别验证：分析帧转正 → 共享裁剪 → 预览流 → 屏幕镜像。
 */
class StanceGeometryTest {

    private val eps = 1e-4f

    private fun assertRect(expected: NormRect, actual: NormRect, message: String) {
        assertEquals("$message.left", expected.left, actual.left, eps)
        assertEquals("$message.top", expected.top, actual.top, eps)
        assertEquals("$message.right", expected.right, actual.right, eps)
        assertEquals("$message.bottom", expected.bottom, actual.bottom, eps)
    }

    private fun geometry(
        analysisW: Int = 640,
        analysisH: Int = 480,
        analysisRotation: Int = 0,
        previewW: Int = 640,
        previewH: Int = 480,
        previewRotation: Int = 0,
        mirrored: Boolean = false,
        measuredCrop: Float? = 4f / 3f,
        activeArray: Float? = 4f / 3f,
    ): StanceOverlayGeometry = requireNotNull(
        StanceOverlayGeometry.create(
            analysisWidth = analysisW,
            analysisHeight = analysisH,
            analysisRotation = analysisRotation,
            preview = PreviewStreamGeometry(
                width = previewW,
                height = previewH,
                rotationDegrees = previewRotation,
                mirrored = mirrored,
                measuredCropAspect = measuredCrop,
                activeArrayAspect = activeArray,
            ),
        )
    )

    @Test
    fun `同画幅同裁剪是恒等映射`() {
        val g = geometry()
        val rect = NormRect(0.1f, 0.2f, 0.4f, 0.7f)
        assertRect(rect, g.mapAnalysisRect(rect), "identity")
        assertEquals(GeometryEvidence.MEASURED_CROP, g.evidence)
    }

    @Test
    fun `裁剪区4比3预览16比9时只保留裁剪区中间四分之三高度`() {
        // 真机常见档：预览/成片 16:9，SCALER_CROP_REGION 仍是 4:3（或反之）。
        // 摄像头文档口径：输出画幅比与裁剪区不同 ⇒ 居中裁掉多余部分（不拉伸、不加黑边）。
        val g = geometry(previewW = 1920, previewH = 1080, measuredCrop = 4f / 3f)
        // 裁剪区等比缩放：只有几何中心点保持不动，区间按比例外扩
        val center = g.mapAnalysisRect(NormRect(0.45f, 0.45f, 0.55f, 0.55f))
        assertEquals(0.5f, (center.top + center.bottom) / 2f, eps)
        assertEquals(0.5f, (center.left + center.right) / 2f, eps)
        // 裁剪区 v∈[0.4,0.6] 在 16:9 预览里被放大到 v∈[0.3667,0.6333]
        val k = (16f / 9f) / (4f / 3f)
        assertRect(
            NormRect(
                left = 0.4f,
                top = 0.5f + (0.4f - 0.5f) * k,
                right = 0.6f,
                bottom = 0.5f + (0.6f - 0.5f) * k,
            ),
            g.mapAnalysisRect(NormRect(0.4f, 0.4f, 0.6f, 0.6f)),
            "vertical-crop",
        )
        // 裁剪区顶端（v=0）落在预览画幅之外——旧实现直接乘预览高度会把框画进画面里
        val top = g.mapAnalysisRect(NormRect(0.4f, 0f, 0.6f, 0.05f))
        assertTrue("裁剪区顶端必须映射到预览内容之外：$top", top.top < 0f)
    }

    @Test
    fun `分析面16比9裁剪区4比3时先压回裁剪区再落在预览`() {
        // B14 原始故障形态：换挡后分析面还是 16:9，预览已是 4:3。
        val g = geometry(analysisW = 640, analysisH = 360, previewW = 640, previewH = 480, measuredCrop = 4f / 3f)
        val k = (4f / 3f) / (16f / 9f)  // = 0.75：分析面只覆盖裁剪区中间 75% 高度
        assertRect(
            NormRect(
                left = 0.25f,
                top = 0.5f + (0.25f - 0.5f) * k,
                right = 0.75f,
                bottom = 0.5f + (0.75f - 0.5f) * k,
            ),
            g.mapAnalysisRect(NormRect(0.25f, 0.25f, 0.75f, 0.75f)),
            "analysis-vs-crop",
        )
    }

    @Test
    fun `分析帧90度转正与预览转正相互抵消或回到传感器坐标`() {
        // rotateRgba 90° 顺时针：传感器 (x,y) → 转正 (h-1-y, x)。
        // 传感器左上角矩形在分析帧里位于右上角。
        val sensor = NormRect(0f, 0f, 0.1f, 0.2f)
        val display = NormRect(0.8f, 0f, 1f, 0.1f)
        // 预览不转正：输出是传感器坐标 → 分析帧 display 必须映射回 sensor
        val plainPreview = geometry(analysisRotation = 90, previewRotation = 0)
        assertRect(sensor, plainPreview.mapAnalysisRect(display), "q90-back-to-sensor")

        // 预览也转正 90°：分析转正与预览转正抵消，display 矩形保持 display 坐标
        val rotatedPreview = geometry(analysisRotation = 90, previewRotation = 90)
        assertRect(display, rotatedPreview.mapAnalysisRect(display), "q90-cancel")
    }

    @Test
    fun `预览流180度转正后矩形绕中心对折`() {
        val g = geometry(previewRotation = 180)
        assertRect(
            NormRect(0.7f, 0.6f, 0.9f, 0.8f),
            g.mapAnalysisRect(NormRect(0.1f, 0.2f, 0.3f, 0.4f)),
            "q180",
        )
    }

    @Test
    fun `前后置镜像只翻转屏幕空间的左右`() {
        val normal = geometry(mirrored = false)
        val mirrored = geometry(mirrored = true)
        val rect = NormRect(0.1f, 0.2f, 0.4f, 0.7f)
        val plain = normal.mapAnalysisRect(rect)
        val flipped = mirrored.mapAnalysisRect(rect)
        assertEquals(1f - plain.right, flipped.left, eps)
        assertEquals(1f - plain.left, flipped.right, eps)
        assertEquals(plain.top, flipped.top, eps)
        assertEquals(plain.bottom, flipped.bottom, eps)
        // 屏幕空间镜像：原点在中轴，不是传感器坐标镜像后再转正
        assertRect(NormRect(0.6f, 0.2f, 0.9f, 0.7f), flipped, "mirror")
    }

    @Test
    fun `奇数转正角交换显示宽高而内容矩形等比居中`() {
        val g = geometry(previewW = 640, previewH = 480, previewRotation = 90)
        assertEquals(4f / 3f, g.previewAspect, eps)
        assertEquals(3f / 4f, g.displayAspect, eps)

        // 画布恰好是 3:4：内容满幅
        assertRect(NormRect(0f, 0f, 1f, 1f), g.previewContentRect(300f, 400f), "exact")

        // 画布更宽（1:1）：按 3:4 内容居中，左右留黑边
        val square = g.previewContentRect(400f, 400f)
        assertEquals(0.125f, square.left, eps)
        assertEquals(0.875f, square.right, eps)
        assertEquals(0f, square.top, eps)
        assertEquals(1f, square.bottom, eps)

        // 画布更高（9:16）：按 3:4 内容居中，上下留黑边（内容占画布高度的 2/3）
        val tall = g.previewContentRect(300f, 600f)
        assertEquals(1f / 6f, tall.top, eps)
        assertEquals(5f / 6f, tall.bottom, eps)
        assertEquals(0f, tall.left, eps)
        assertEquals(1f, tall.right, eps)
    }

    @Test
    fun `裁剪证据按实测大于枚举大于分析流回退并如实标注`() {
        assertEquals(
            GeometryEvidence.MEASURED_CROP,
            geometry(measuredCrop = 1.5f, activeArray = 4f / 3f).evidence,
        )
        val enumerated = geometry(measuredCrop = null, activeArray = 4f / 3f)
        assertEquals(GeometryEvidence.ENUMERATED_ACTIVE_ARRAY, enumerated.evidence)
        assertEquals(4f / 3f, enumerated.cropAspect, eps)

        val fallback = geometry(measuredCrop = null, activeArray = null, analysisW = 640, analysisH = 360)
        assertEquals(GeometryEvidence.ANALYSIS_FALLBACK, fallback.evidence)
        assertEquals(16f / 9f, fallback.cropAspect, eps)
    }

    @Test
    fun `非法尺寸或非法裁剪比不产生几何`() {
        val valid = PreviewStreamGeometry(640, 480, 0, false, 4f / 3f, 4f / 3f)
        assertNull("预览尺寸 0", StanceOverlayGeometry.create(640, 480, 0, valid.copy(width = 0)))
        assertNull("预览高度 0", StanceOverlayGeometry.create(640, 480, 0, valid.copy(height = -1)))
        assertNull("分析尺寸 0", StanceOverlayGeometry.create(0, 480, 0, valid))
        assertNull("分析高度 0", StanceOverlayGeometry.create(640, 0, 0, valid))
    }

    @Test
    fun `零或负裁剪比被当作缺失而不是几何事实`() {
        // 实测 0/NaN 不能直接参与除法；create 必须退到枚举或分析流，并标注真实证据等级
        val g = requireNotNull(
            StanceOverlayGeometry.create(
                640, 480, 0,
                PreviewStreamGeometry(640, 480, 0, false, measuredCropAspect = Float.NaN, activeArrayAspect = 4f / 3f),
            )
        )
        assertEquals(GeometryEvidence.ENUMERATED_ACTIVE_ARRAY, g.evidence)
        val fallback = requireNotNull(
            StanceOverlayGeometry.create(
                640, 480, 0,
                PreviewStreamGeometry(640, 480, 0, false, measuredCropAspect = -1f, activeArrayAspect = 0f),
            )
        )
        assertEquals(GeometryEvidence.ANALYSIS_FALLBACK, fallback.evidence)
    }
}
