package com.wotagei.cam.stance

/**
 * 预览流的实际几何（B14）：叠加映射只认这些量，不从视图矩形反推。
 *
 * - [width]/[height]：sink **实际使用**的传感器坐标缓冲尺寸。
 *   DIRECT = `SurfaceTexture` 的 `streamSize()`；GL = `onSizeChanged` 收到的相机帧尺寸。
 * - [rotationDegrees]：把该缓冲顺时针转正到屏幕的净角度（与 [StanceAnalysis.rotateRgba] 同语义）。
 *   两个 sink 的 ST 矩阵补偿不同，但净视觉方向同源 = `normalize(sensorOrientation - displayRotation)`。
 * - [mirrored]：前置镜头在**屏幕空间**的左右镜像（旋转之后施加）。
 * - [measuredCropAspect]：`CaptureResult.SCALER_CROP_REGION` 实测宽/高比。非高速会话的所有输出
 *   共享这一裁剪区；输出画幅与之不同时，按 Camera2 文档会**居中裁掉多余部分**（不是拉伸、不是黑边）。
 * - [activeArrayAspect]：`SENSOR_INFO_ACTIVE_ARRAY_SIZE` 枚举快照宽/高比。仅当实测尚未到达时
 *   作为显式回退（本应用缩放用 `CONTROL_ZOOM_RATIO`/保持有效区画幅的 cropBox，比例不变）。
 */
data class PreviewStreamGeometry(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val mirrored: Boolean,
    val measuredCropAspect: Float? = null,
    val activeArrayAspect: Float? = null,
)

/** 叠加几何的裁剪证据等级——输出到诊断/测试，避免把回退值当成实测事实。 */
enum class GeometryEvidence {
    /** `SCALER_CROP_REGION` 实测宽高比。 */
    MEASURED_CROP,

    /** 实测缺失，使用枚举 `SENSOR_INFO_ACTIVE_ARRAY_SIZE`（本应用裁剪保持该画幅比）。 */
    ENUMERATED_ACTIVE_ARRAY,

    /** 两者都缺失：把分析流本身当作完整裁剪区（仅在同画幅时严格成立，调用方可知情）。 */
    ANALYSIS_FALLBACK,
}

/** 归一化矩形（左上原点；坐标轴语义由使用处声明）。 */
data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * B14 叠加几何：检测模型输出（**已转正的分析帧**归一化坐标）→ 预览内容区归一化坐标。
 *
 * 链路（每一步都只用可测/枚举的几何量）：
 * 1. 分析帧已按 [analysisRotation] 转正；先反向旋转回传感器坐标；
 * 2. 分析流按自身画幅比**居中裁切**共享裁剪区 → 映射回裁剪区坐标；
 * 3. 裁剪区按预览流画幅比居中裁切 → 预览流坐标；
 * 4. 预览流按 [previewRotation] 转正、按 [previewMirrored] 做屏幕镜像；
 * 5. 预览内容区在 View 内等比居中（[previewContentRect]）。
 *
 * 典型真机场景：裁剪区 4:3、预览 16:9 ⇒ 预览只显示裁剪区中间 3/4 高度；分析流 4:3 覆盖整块，
 * 因此分析画面里靠上/靠下的号位在预览里会被裁掉——旧实现直接乘预览宽高，叠框必然偏移。
 */
data class StanceOverlayGeometry(
    val analysisWidth: Int,
    val analysisHeight: Int,
    val analysisRotation: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val previewRotation: Int,
    val previewMirrored: Boolean,
    val cropAspect: Float,
    val evidence: GeometryEvidence,
) {
    init {
        require(analysisWidth > 0 && analysisHeight > 0 && previewWidth > 0 && previewHeight > 0) {
            "叠加几何必须使用实际像素尺寸"
        }
        require(cropAspect.isFinite() && cropAspect > 0f) { "裁剪宽高比无效" }
    }

    val analysisAspect: Float get() = analysisWidth.toFloat() / analysisHeight
    val previewAspect: Float get() = previewWidth.toFloat() / previewHeight

    /** 预览内容在屏幕上的宽高比（90/270 转正会交换宽高）。 */
    val displayAspect: Float
        get() = if (quarter(previewRotation) % 2 == 1) 1f / previewAspect else previewAspect

    /** 模型输出矩形（转正分析帧归一化坐标）→ 预览内容区归一化坐标。 */
    fun mapAnalysisRect(rect: NormRect): NormRect {
        val sensor = rotateRectBack(rect, quarter(analysisRotation))
        val crop = streamToCrop(sensor, analysisAspect, cropAspect)
        val stream = cropToStream(crop, cropAspect, previewAspect)
        val display = rotateRect(stream, quarter(previewRotation))
        return if (previewMirrored) {
            NormRect(1f - display.right, display.top, 1f - display.left, display.bottom)
        } else {
            display
        }
    }

    /**
     * 预览内容在画布内的等比居中矩形（信箱黑边）。画布通常已被 `aspectRatio(displayAspect)`
     * 约束成满幅；仍按实际画布比例算，尺寸取整/宿主差异时也不会错位。
     */
    fun previewContentRect(canvasWidth: Float, canvasHeight: Float): NormRect {
        if (canvasWidth <= 0f || canvasHeight <= 0f) return NormRect(0f, 0f, 1f, 1f)
        val canvasAspect = canvasWidth / canvasHeight
        val content = displayAspect
        return if (content > canvasAspect) {
            val h = canvasAspect / content
            NormRect(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
        } else {
            val w = content / canvasAspect
            NormRect((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
        }
    }

    companion object {
        /**
         * 只接受实际像素尺寸；裁剪比按 实测 > 枚举 active-array > 分析流 的顺序选取，
         * 并用 [evidence] 如实标注来源。任何尺寸无效返回 null（宁可不画，不猜）。
         */
        fun create(
            analysisWidth: Int,
            analysisHeight: Int,
            analysisRotation: Int,
            preview: PreviewStreamGeometry,
        ): StanceOverlayGeometry? {
            if (analysisWidth <= 0 || analysisHeight <= 0 ||
                preview.width <= 0 || preview.height <= 0
            ) return null
            val measured = preview.measuredCropAspect?.takeIf { it.isFinite() && it > 0f }
            val enumerated = preview.activeArrayAspect?.takeIf { it.isFinite() && it > 0f }
            val evidence = when {
                measured != null -> GeometryEvidence.MEASURED_CROP
                enumerated != null -> GeometryEvidence.ENUMERATED_ACTIVE_ARRAY
                else -> GeometryEvidence.ANALYSIS_FALLBACK
            }
            val crop = measured ?: enumerated
                ?: (analysisWidth.toFloat() / analysisHeight)
            return StanceOverlayGeometry(
                analysisWidth = analysisWidth,
                analysisHeight = analysisHeight,
                analysisRotation = analysisRotation,
                previewWidth = preview.width,
                previewHeight = preview.height,
                previewRotation = preview.rotationDegrees,
                previewMirrored = preview.mirrored,
                cropAspect = crop,
                evidence = evidence,
            )
        }

        private fun quarter(degrees: Int): Int =
            ((Math.round(degrees / 90f).toInt() % 4) + 4) % 4

        /**
         * 分析帧已转正坐标 → 传感器坐标（[StanceAnalysis.rotateRgba] 的逆）：
         * q=90 顺时针时传感器左上会落到转正帧右上，逆变换即 `(u,v) = (v', 1-u')`。
         */
        private fun rotateRectBack(rect: NormRect, q: Int): NormRect = when (q) {
            1 -> NormRect(rect.top, 1f - rect.right, rect.bottom, 1f - rect.left)
            2 -> NormRect(1f - rect.right, 1f - rect.bottom, 1f - rect.left, 1f - rect.top)
            3 -> NormRect(1f - rect.bottom, rect.left, 1f - rect.top, rect.right)
            else -> rect
        }

        /** 传感器坐标 → 屏幕转正坐标（顺时针 q）。 */
        private fun rotateRect(rect: NormRect, q: Int): NormRect = when (q) {
            1 -> NormRect(1f - rect.bottom, rect.left, 1f - rect.top, rect.right)
            2 -> NormRect(1f - rect.right, 1f - rect.bottom, 1f - rect.left, 1f - rect.top)
            3 -> NormRect(rect.top, 1f - rect.right, rect.bottom, 1f - rect.left)
            else -> rect
        }

        /** 流坐标 → 共享裁剪区：流画幅比裁剪区窄时水平居中裁、宽时垂直居中裁。 */
        private fun streamToCrop(rect: NormRect, streamAspect: Float, cropAspect: Float): NormRect =
            if (cropAspect > streamAspect) {
                val k = streamAspect / cropAspect
                NormRect(
                    0.5f + (rect.left - 0.5f) * k, rect.top,
                    0.5f + (rect.right - 0.5f) * k, rect.bottom,
                )
            } else {
                val k = cropAspect / streamAspect
                NormRect(
                    rect.left, 0.5f + (rect.top - 0.5f) * k,
                    rect.right, 0.5f + (rect.bottom - 0.5f) * k,
                )
            }

        /** 共享裁剪区 → 流坐标（[streamToCrop] 的逆；越界值保留，交给画布裁剪）。 */
        private fun cropToStream(rect: NormRect, cropAspect: Float, streamAspect: Float): NormRect =
            if (cropAspect > streamAspect) {
                val k = cropAspect / streamAspect
                NormRect(
                    0.5f + (rect.left - 0.5f) * k, rect.top,
                    0.5f + (rect.right - 0.5f) * k, rect.bottom,
                )
            } else {
                val k = streamAspect / cropAspect
                NormRect(
                    rect.left, 0.5f + (rect.top - 0.5f) * k,
                    rect.right, 0.5f + (rect.bottom - 0.5f) * k,
                )
            }
    }
}
