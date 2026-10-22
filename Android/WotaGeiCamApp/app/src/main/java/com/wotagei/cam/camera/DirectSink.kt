package com.wotagei.cam.camera

import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.wotagei.cam.core.Size
import com.wotagei.cam.core.pickDirectPreviewSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 纯函数：DIRECT 承载视图（`TextureView`）的显示变换矩阵，行主序 3x3（视图像素坐标，Y 向下）。
 *
 * 语义：`TextureView` 默认把 `bufferWidth x bufferHeight` 的相机帧拉伸铺满整个视图矩形，
 * 本矩阵把它改成「按 [rotationDegrees] 顺时针旋转 + 等比居中（信箱黑边）」的结果，
 * 前置镜像作用在旋转之后的屏幕横轴上（自拍预览的左右反）。
 * 与 [GlRenderEngine] 的「纹理坐标旋转 + 等比居中 contentRect」等价，所以两种渲染模式同角度。
 *
 * 推导（列向量 p' = M·p）：
 * `px = X·(bw/vw) - bw/2`、`py = Y·(bh/vh) - bh/2`（视图 → 以中心为原点的缓冲像素系）；
 * `rx = c·px - s·py`、`ry = s·px + c·py`（屏幕坐标系 Y 向下，正角即视觉顺时针）；
 * 镜像在旋转之后乘，才是「屏幕左右反」而不是「传感器左右反」；最后等比缩放并平移到视图中心。
 */
fun directDisplayMatrix(
    viewWidth: Int,
    viewHeight: Int,
    bufferWidth: Int,
    bufferHeight: Int,
    rotationDegrees: Int,
    mirrored: Boolean
): FloatArray {
    val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) return identity
    val vw = viewWidth.toFloat()
    val vh = viewHeight.toFloat()
    val bw = bufferWidth.toFloat()
    val bh = bufferHeight.toFloat()
    val deg = ((rotationDegrees % 360) + 360) % 360
    val rad = Math.toRadians(deg.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    // 旋转后的包围盒：非 90 整倍数的 sensorOrientation（个别机型报 82/97 之类）也要能算出黑边
    val contentW = bw * abs(c) + bh * abs(s)
    val contentH = bw * abs(s) + bh * abs(c)
    if (contentW <= 0f || contentH <= 0f) return identity
    val k = minOf(vw / contentW, vh / contentH)
    val mx = if (mirrored) -1f else 1f
    val a = bw / vw
    val b = bh / vh
    val cx = vw / 2f
    val cy = vh / 2f
    return floatArrayOf(
        k * mx * c * a, -k * mx * s * b, k * mx * (s * bh - c * bw) / 2f + cx,
        k * s * a, k * c * b, -k * (c * bh + s * bw) / 2f + cy,
        0f, 0f, 1f
    )
}

/**
 * DIRECT（不使用 GPU）模式的预览 sink：相机帧直接落到承载视图的 `SurfaceTexture`，
 * 全程不建 EGL 上下文、不上着色器（需求「绕过 GPU，由应用直接调用传感器和编码器」）。
 *
 * 为什么承载视图必须是 `TextureView` 而不能是 `SurfaceView`：`SurfaceView` 的窗口面按传感器
 * 原始方向出图，应用侧没有任何变换通道（真机 DIRECT 与 GPU 差 90° 就是这么来的）；
 * `TextureView` 会把缓冲变换烘进上屏，再让 [directDisplayMatrix] 补 [PreviewOrientation.appliedDegrees]
 * 与镜像即可与 GPU 路径对齐 —— 施加角含「抵消缓冲变换自带的旋转」，所以 HAL 给什么矩阵都不影响方向。
 *
 * 预览流尺寸：不让 HAL 按视图尺寸自选（会被拉成视图比例、无黑边导致失真），
 * 而是用 [setStreamCandidates] 注入的 PRIVATE 能力表挑一条与录像同宽高比的档（见 [pickDirectPreviewSize]）。
 *
 * 线程：全部方法可能被相机工作线程与主线程交替调用，状态用 @Volatile 收；
 * 回调 [onReconfigure] 固定抛回主线程（`TextureView` 只能在主线程改）。
 */
class DirectSink : PreviewSink, DisplaySurfaceReceiver, FrostBlurProvider {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var display: Surface? = null

    @Volatile
    private var viewWidth = 0

    @Volatile
    private var viewHeight = 0

    /** 录像档尺寸（[PreviewSink.onSizeChanged] 的契约 = 相机帧尺寸，不是视图尺寸） */
    @Volatile
    private var frameWidth = 0

    @Volatile
    private var frameHeight = 0

    /** 传感器方向 + 显示角 + 镜像，与 GL 路径共用同一状态机，两模式方向因此逐度一致 */
    private val orientation = PreviewOrientation()

    @Volatile
    private var candidates: List<Size> = emptyList()

    @Volatile
    private var streamWidth = 0

    @Volatile
    private var streamHeight = 0

    /** 承载视图注册：缓冲尺寸或矩阵需要重算时回调（主线程） */
    @Volatile
    var onReconfigure: (() -> Unit)? = null

    /** 选中的输出流尺寸变化时回调（主线程）：会话结构签名跟着变，上层要催一次重建 */
    @Volatile
    var onStreamSizeChanged: ((Pair<Int, Int>) -> Unit)? = null

    override fun cameraTargets(): List<Surface> = listOfNotNull(display?.takeIf { it.isValid })

    override fun onSizeChanged(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        frameWidth = w
        frameHeight = h
        recomputeStreamSize()
    }

    /**
     * 契约原义：`SENSOR_ORIENTATION` + 前置镜像。引擎开机时会写一次裸传感器方向，
     * 转正角在这里现算（[setDisplayDegrees] / [onTextureMatrix] 之后都会自动重算），UI 侧不必再覆盖。
     */
    override fun setOrientation(sensorOrientation: Int, mirrored: Boolean) {
        orientation.setSensorOrientation(sensorOrientation, mirrored)
        notifyReconfigure()
    }

    /** 当前显示旋转（0/90/180/270）；页面不锁方向，四个朝向都要跟着转 */
    override fun setDisplayDegrees(degrees: Int) {
        val before = orientation.appliedDegrees()
        orientation.setDisplayDegrees(degrees)
        if (before == orientation.appliedDegrees()) return
        notifyReconfigure()
    }

    override fun onDisplaySurfaceChanged(surface: Surface?, widthPx: Int, heightPx: Int) {
        display = surface
        viewWidth = widthPx.coerceAtLeast(0)
        viewHeight = heightPx.coerceAtLeast(0)
        recomputeStreamSize()
    }

    /** 该镜头 PRIVATE 表（来自 `CameraAbility.previewSizes`）；换镜头整套换挡时由 UI 重新注入 */
    fun setStreamCandidates(sizes: List<Size>) {
        if (sizes == candidates) return
        candidates = sizes
        recomputeStreamSize()
    }

    /** 应写进 `SurfaceTexture.setDefaultBufferSize` 的尺寸；0x0 = 能力表或录像档还没到齐 */
    fun streamSize(): Pair<Int, Int> = streamWidth to streamHeight

    /**
     * 承载视图当前应施加的显示变换。
     *
     * `TextureView` 上屏时已经乘过缓冲变换矩阵，所以本矩阵作用在「矩阵之后」的视图空间：
     * 施加角取 [PreviewOrientation.appliedDegrees]（残余角 + 矩阵自带旋转，正好把矩阵抵消回教科书约定），
     * 源宽高在矩阵换了轴时（90/270）必须对调，否则信箱黑边按错比例算。
     */
    fun newDisplayMatrix(): Matrix {
        val swapped = orientation.stSwapsSource()
        return Matrix().apply {
            setValues(
                directDisplayMatrix(
                    viewWidth, viewHeight,
                    if (swapped) streamHeight else streamWidth,
                    if (swapped) streamWidth else streamHeight,
                    orientation.appliedDegrees(), orientation.mirrored
                )
            )
        }
    }

    /**
     * 承载视图每帧读到的缓冲变换矩阵（`SurfaceTexture.getTransformMatrix`）。
     * 这是本模式唯一的方向「机型可观测量」：矩阵自带多少旋转，就只能由它自己说。
     * @return 解析出的旋转分量发生变化时返回 true（调用方据此重设一次显示矩阵）
     */
    fun onTextureMatrix(matrix: FloatArray): Boolean = orientation.setStMatrix(matrix)

    /**
     * 输出流尺寸进会话结构签名：直显面的尺寸变了 = 会话形状变了，必须重建
     * （GL 路径自己管缓冲，用 [PreviewSink] 的默认空实现）。
     */
    override fun structuralToken(): String = if (streamWidth > 0) "direct=${streamWidth}x$streamHeight" else ""

    // region 毛玻璃接缝（#84）：DIRECT 下的回答恒为「不可用」

    /**
     * 毛玻璃接缝在 DIRECT 下的实现：**开关接受但无效，查询永远回答"不可用"**。
     *
     * 这不是待补也不是偷懒：DIRECT 的相机帧根本不进 GL（`Camera2Engine` 把预览面直接挂进 session），
     * 全工程那唯一一枚 EGL 上下文压根没被创建，也就没有可糊的纹理。UI 侧因此可以**无条件**调
     * [setFrostBlurEnabled] 而不必先判断渲染模式——拿到的 false 就是「退成普通 scrim」的信号
     * （docs/plan/14 §二 的优雅降级，与 curve/zebra 在 DIRECT 下不生效同族）。
     * 开关状态刻意不存：存了就只能回答"用户想开"，仍然要接一句"但没有"，徒增一处不一致。
     */
    override fun setFrostBlurEnabled(enabled: Boolean) = Unit

    override fun isFrostBlurEnabled(): Boolean = false

    override fun isFrostBlurAvailable(): Boolean = false

    override fun frostRenderTarget(): FrostRenderTarget? = null

    override fun frostGeometry(
        viewOriginXInWindowPx: Float,
        viewOriginYInWindowPx: Float
    ): FrostGeometry? = null

    // endregion

    private fun recomputeStreamSize() {
        if (frameWidth <= 0 || frameHeight <= 0) {
            notifyReconfigure()
            return
        }
        val picked = pickDirectPreviewSize(
            candidates = candidates,
            record = Size(frameWidth, frameHeight),
            viewWidthPx = viewWidth,
            viewHeightPx = viewHeight
        )
        val w = picked?.width ?: frameWidth
        val h = picked?.height ?: frameHeight
        if (w != streamWidth || h != streamHeight) {
            streamWidth = w
            streamHeight = h
            notifyStreamSizeChanged(w to h)
        }
        notifyReconfigure()
    }

    private fun notifyReconfigure() {
        val block = onReconfigure ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun notifyStreamSizeChanged(size: Pair<Int, Int>) {
        val block = onStreamSizeChanged ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) block(size) else mainHandler.post { block(size) }
    }
}
