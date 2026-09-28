package com.wotagei.cam.record

import android.media.MediaFormat
import android.util.Size

/**
 * 一次录制的全部编码参数快照（04 文件 §2 契约，字段名与顺序不得改）。
 *
 * 约定：
 * - `width/height/fps` 由上层从设备能力求交后传入（02 文件 §4），本类不做任何机型数值硬编码；
 * - [bitrate] ≤ 0 表示「用户未自设」，引擎按 [BitratePolicy.resolve] 的三段式取自动表/兜底公式；
 * - [sampleRate] 必须先经 [AudioProbe.isSampleRateSupported] 探测（32/44.1/48k 逐档判断）；
 * - [captureRate] 与 [fps] 不相等时走「延时摄影」语义（`setCaptureRate`），v0.0.1 只留通道不开放 UI；
 * - [audioEnabled] = false（静音）或缺 `RECORD_AUDIO` 权限时，两条引擎都完全不初始化音频通路。
 */
data class RecordProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val captureRate: Float,
    val bitrate: Int,
    val codec: Int,
    val sampleRate: Int,
    val channels: Int = 2,
    val audioBitrate: Int = BitratePolicy.AUDIO_BITRATE,
    val audioEnabled: Boolean,
    val orientationHint: Int,
    val mirrored: Boolean,
    val useGpu: Boolean
) {
    companion object {
        /** 参数表 `codec` 取值：与 MediaRecorder.VideoEncoder / MediaCodec mime 双向映射 */
        const val CODEC_H264 = 0
        const val CODEC_HEVC = 1

        /** 录制时长短于此值按废片处理：删文件 + 删 pending 记录（04 文件 §5.4） */
        const val MIN_KEEP_MS = 1_000L
    }

    /** 相机输出面尺寸：DIRECT 模式会话参数、GPU 模式 viewport 参数 */
    val size: Size get() = Size(width, height)

    val hevc: Boolean get() = codec == CODEC_HEVC

    /** MediaCodec 路径的 mime（HEVC 能力由 [CodecRecorder] 用 CodecCapabilities 校验后回退 H264） */
    val videoMime: String get() = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

    /** 延时摄影：录得快、放得慢（captureRate = 实际采集帧率，fps = 容器声明帧率） */
    val timeLapse: Boolean get() = captureRate > 0f && captureRate.toInt() != fps

    /** 是否需要初始化音频通路（静音或无权限时上层把 audioEnabled 置 false，这里再做一次数值合法性判断） */
    val audioUsable: Boolean
        get() = audioEnabled && sampleRate > 0 && channels > 0

    /** 三段式落地后的编码器码率 */
    fun effectiveBitrate(): Int = BitratePolicy.resolve(bitrate, width, height, fps, hevc)
}

/**
 * 纯函数：容器里该写多少度（04 文件 §2）。输入全部来自 CameraCharacteristics 与显示状态，无机型常量。
 *
 * 两条渲染路径喂给编码器的都是**缓冲原样帧**（GPU 的编码 pass 只抵消 `SurfaceTexture` 矩阵自带的旋转、
 * 不做转正也不镜像，见 camera/GlRenderEngine.buildTexCoords；DIRECT 由 Camera2 直连编码器面，本来就是原样帧），
 * 所以「画面转正多少」这件事完全落在容器角上，两条路径同一个公式：
 * `sensorOrientation − 显示旋转`，即 `SENSOR_ORIENTATION` 减去当前显示旋转角（官方定义的转正角）。
 *
 * 前置镜头同理：`SENSOR_ORIENTATION` 的定义对前后摄一致，镜像只是预览侧的屏幕空间翻转，
 * 文件里存的是未镜像的原样帧，所以不需要另一套角度。
 * 真机 WIKO GAR-AN60 横屏（sensor=90、显示=90）录出 1920x1080 + 容器角 0，成片容器不带 rotation。
 *
 * @param sensorOrientation `SENSOR_ORIENTATION`
 * @param deviceDegrees     当前显示旋转（0/90/180/270）
 * @param front             前置镜头（不参与取值，保留形参以兼容既有调用与单测）
 * @param direct            是否 DIRECT（不使用 GPU）渲染模式（同上，不参与取值）
 */
fun recordOrientationHint(sensorOrientation: Int, deviceDegrees: Int, front: Boolean, direct: Boolean): Int =
    normalizeDegrees(sensorOrientation - deviceDegrees)

private fun normalizeDegrees(degrees: Int): Int = ((degrees % 360) + 360) % 360
