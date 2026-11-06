package com.wotagei.cam.stance

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 站位分析管线（M2a 帧源 + M2b 检测装配）：
 * [start] 创建 YUV ImageReader → 引擎 analysis 槽（[com.wotagei.cam.camera.CameraController.setAnalysisTarget]）
 * → 丢帧节流（≈3fps）→ YUV→RGBA → 预处理提亮（§6 可开关，默认关）→
 * [FrameDetector]（LiteRT 封装）→ [JudgementEngine.observe] → 结果回调（Main 线程）。
 *
 * 生命周期与挂/摘分工（M2c 宿主职责）：
 * - [start] 一次（线程+reader 常驻）；往引擎挂/摘走 controller.setAnalysisTarget(surface/null)
 *   （每次 = 会话重建，挂/摘各一次勿反复）；
 * - [pauseListening]/[resumeListening] 只停/恢复本管线回调（引擎槽不动，录制窗口用）；
 * - reader 所有权在引擎（关闭序列会单方面 close），本类回调与 [release] 须容忍二次关闭。
 * 线程模型：单条 HandlerThread("WotaStanceAnalysis") 串行（采集/转换/推理 3fps×30~100ms 无并发压力）。
 */
class StanceAnalysis(
    private val onResult: (EngineOutput) -> Unit,
) {
    /** 帧检测接口（M2b 的 LiteRT 封装实现）。 */
    fun interface FrameDetector {
        fun detect(rgba: IntArray, w: Int, h: Int, tsMs: Long): List<PersonBox>
    }

    private val started = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val listening = AtomicBoolean(false)
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastFrameMs = 0L

    /** 引擎与判定头（模型组装配；未就绪时帧被丢弃）。 */
    var engine = JudgementEngine(mode = JudgementEngine.MODE_AUTO, nManual = 1)
    var head: StanceHead? = null
    var detector: FrameDetector? = null

    /** §6 预处理提亮开关（默认关；只进检测输入，不进预览/成片）。 */
    @Volatile
    var brighten = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 创建 reader（YUV_420_888）与分析线程，返回供引擎 analysis 槽的 surface。
     * 幂等：重复调用返回既有 surface。引擎侧挂载由宿主调
     * controller.setAnalysisTarget(surface, reader)（reader 所有权转移给引擎）。
     */
    fun start(size: Size = DEFAULT_SIZE): Surface {
        if (started.compareAndSet(false, true)) {
            val t = HandlerThread("WotaStanceAnalysis").apply { start() }
            thread = t
            handler = Handler(t.looper)
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            reader!!.setOnImageAvailableListener({ r ->
                val image = try {
                    r.acquireLatestImage() ?: return@setOnImageAvailableListener
                } catch (_: Exception) {
                    return@setOnImageAvailableListener   // 引擎单方面关闭 reader 的窗口
                }
                processImage(image)
            }, handler)
        }
        return reader!!.surface
    }

    /** 供引擎 setAnalysisTarget(surface, reader) 的 reader 参数（所有权转移给引擎）。 */
    fun reader(): ImageReader? = reader

    private fun processImage(image: Image) {
        try {
            if (!listening.get()) return
            val now = System.currentTimeMillis()
            if (now - lastFrameMs < FRAME_INTERVAL_MS) return   // 丢帧节流 ≈3fps
            lastFrameMs = now
            val w = image.width
            val h = image.height
            val rgba = yuv420ToRgba(image) ?: return
            if (brighten) brightenRgba(rgba)
            val boxes = detector?.detect(rgba, w, h, now) ?: return
            val out = engine.observe(FrameObs(now, boxes))
            mainHandler.post { if (!released.get()) onResult(out) }
        } catch (_: Exception) {
            // 单帧失败不致断流（reader 被引擎单方面关闭时本回调可能再触发）
        } finally {
            runCatching { image.close() }
        }
    }

    /** 停本管线回调（引擎槽不动——录制窗口期用，停录后 [resumeListening]）。 */
    fun pauseListening() {
        listening.set(false)
    }

    /** 恢复回调（默认关闭——[start] 后需显式 resume 才开始吃帧）。 */
    fun resumeListening() {
        listening.set(true)
    }

    /** 彻底释放线程（reader 由引擎关闭序列兜底；二次关闭已在 runCatching 内容忍）。 */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        listening.set(false)
        handler?.post {
            runCatching { reader?.setOnImageAvailableListener(null, null) }
            runCatching { reader?.close() }
            reader = null
            thread?.quitSafely()
        }
    }

    companion object {
        const val FRAME_INTERVAL_MS = 333L   // ≈3fps（§6 推理节拍）
        val DEFAULT_SIZE = Size(640, 480)

        /**
         * 设备 YUV_420_888 输出尺寸选择：≤640 宽高的最大档（面积序）；
         * 列表为空（极罕见）回退 640x480。
         */
        fun pickYuvSize(characteristics: CameraCharacteristics): Size {
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: return DEFAULT_SIZE
            val capped = sizes.filter { it.width <= 640 && it.height <= 640 }
            if (capped.isEmpty()) return DEFAULT_SIZE
            return capped.maxBy { it.width.toLong() * it.height }
        }

        /**
         * YUV_420_888 三平面 → RGBA 像素数组（BT.601 全幅）。
         * 兼容 U/V 平面的 rowStride/pixelStride 变体（半分辨率共享/分离布局均覆盖）。
         * 纯函数（JVM 单测小矩阵钉死）。
         */
        fun yuv420ToRgba(image: Image): IntArray? {
            if (image.format != ImageFormat.YUV_420_888) return null
            val w = image.width
            val h = image.height
            val yP = image.planes[0]
            val uP = image.planes[1]
            val vP = image.planes[2]
            val yBuf = ByteArray(yP.buffer.remaining()).also { yP.buffer.get(it) }
            val uBuf = ByteArray(uP.buffer.remaining()).also { uP.buffer.get(it) }
            val vBuf = ByteArray(vP.buffer.remaining()).also { vP.buffer.get(it) }
            return yuv420ToRgba(
                yBuf, uBuf, vBuf, w, h,
                yP.rowStride, uP.rowStride, uP.pixelStride, vP.rowStride, vP.pixelStride,
            )
        }

        fun yuv420ToRgba(
            y: ByteArray, u: ByteArray, v: ByteArray,
            width: Int, height: Int,
            yRowStride: Int, uvRowStride: Int, uvPixStride: Int,
            vRowStride: Int = uvRowStride, vPixStride: Int = uvPixStride,
        ): IntArray {
            val out = IntArray(width * height)
            for (row in 0 until height) {
                val yRow = row * yRowStride
                val uvRow = row / 2 * uvRowStride
                val vRow = row / 2 * vRowStride
                for (col in 0 until width) {
                    val yy = y[yRow + col].toInt() and 0xFF
                    val uu = (u[uvRow + (col / 2) * uvPixStride].toInt() and 0xFF) - 128
                    val vv = (v[vRow + (col / 2) * vPixStride].toInt() and 0xFF) - 128
                    // BT.601 全幅
                    val r = (yy + 1.402f * vv).toInt().coerceIn(0, 255)
                    val g = (yy - 0.344f * uu - 0.714f * vv).toInt().coerceIn(0, 255)
                    val bl = (yy + 1.772f * uu).toInt().coerceIn(0, 255)
                    out[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                }
            }
            return out
        }

        /**
         * §6 预处理提亮（PC 端 convertScaleAbs(alpha=1.3, beta=30) 同规格，线性）。
         * 只进检测输入；原地改写。占位实现——夜间效果不达标再升 gamma/CLAHE。
         */
        fun brightenRgba(rgba: IntArray, alpha: Float = 1.3f, beta: Float = 30f) {
            for (i in rgba.indices) {
                val px = rgba[i]
                val r = (((px shr 16) and 0xFF) * alpha + beta).toInt().coerceIn(0, 255)
                val g = (((px shr 8) and 0xFF) * alpha + beta).toInt().coerceIn(0, 255)
                val bl = ((px and 0xFF) * alpha + beta).toInt().coerceIn(0, 255)
                rgba[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
    }
}
