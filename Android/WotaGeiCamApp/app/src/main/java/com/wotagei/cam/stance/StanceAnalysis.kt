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
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 转正后的一帧（与输入同源，宽高可能已交换）。
 */
data class RotatedFrame(val pixels: IntArray, val width: Int, val height: Int)

/**
 * 站位分析管线（M2a 帧源 + M2b 检测装配）：
 * [start] 创建 YUV ImageReader → 引擎 analysis 槽（[com.wotagei.cam.camera.CameraController.setAnalysisTarget]）
 * → 丢帧节流（≈3fps）→ YUV→RGBA → 预处理提亮（§6 可开关，默认关）→
 * [FrameDetector]（LiteRT 封装）→ [JudgementEngine.observe] → 结果回调（Main 线程）。
 *
 * 生命周期与挂/摘分工（M2c 宿主职责）：
 * - [start] 创建/复用（线程+reader 常驻；surface 失效——引擎在关闭序列会单方面 close——
 *   则重建 reader 再返回新面）；往引擎挂/摘走 controller.setAnalysisTarget(surface/null)
 *   （每次 = 会话重建，挂/摘各一次勿反复）；
 * - [pauseListening]/[resumeListening] 只停/恢复本管线回调（引擎槽不动，录制窗口/离页用）；
 * - [updateModel] 热换检测器/判定头/安全区（分析线程内替换并关闭旧检测器，不重建线程）。
 * 线程模型：单条 HandlerThread("WotaStanceAnalysis") 串行（采集/转换/推理 3fps×30~100ms 无并发压力）。
 */
class StanceAnalysis(
    private val onResult: (EngineOutput) -> Unit,
) {
    /** 帧检测接口（M2b 的 LiteRT 封装实现）。 */
    fun interface FrameDetector {
        fun detect(rgba: IntArray, w: Int, h: Int, tsMs: Long): List<PersonBox>
    }

    private val released = AtomicBoolean(false)
    private val listening = AtomicBoolean(false)
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastFrameMs = 0L

    /** 引擎与判定头（模型组装配；未就绪时帧被丢弃）。 */
    var engine = JudgementEngine(mode = JudgementEngine.MODE_AUTO, nManual = 1)
    var head: StanceHead? = null
        private set
    var detector: FrameDetector? = null
        private set

    /**
     * 热换模型（2026-11-07）：用户导入模型组 / 切换 GPU 加速时**复用同一条管线**
     * （不重建 HandlerThread / ImageReader / 引擎会话），在分析线程内替换并关闭旧检测器。
     * 旧实现每次 applyPack 都 new 一个 StanceAnalysis——旧线程、旧 reader、旧 Interpreter /
     * GpuDelegate 全部悬空不关（泄漏），且引擎槽仍指着旧 reader ⇒ 新模型永远不生效。
     */
    fun updateModel(newDetector: FrameDetector?, newHead: StanceHead?, marginsJson: String? = null) {
        val swap = {
            // 安全区覆盖也在这里落：MARGIN_BANDS 是全局可变表，判定线程正在读它，
            // 不能让装配线程并发改（旧实现由主线程直接 loadCalibratedBands）。
            if (marginsJson != null) StanceSpec.loadCalibratedBands(marginsJson)
            val old = detector
            detector = newDetector
            head = newHead
            if (old !== newDetector) (old as? Closeable)?.let { runCatching { it.close() } }
        }
        val h = handler
        if (h != null && !released.get()) h.post(swap) else swap()
    }

    /** §6 预处理提亮开关（默认关；只进检测输入，不进预览/成片）。 */
    @Volatile
    var brighten = false

    /**
     * 分析帧转正角（2026-11-07）：Camera2 的 YUV 缓冲永远在**传感器坐标系**，
     * 竖持手机时相对预览是横过来的——不转就把"上/下"当"左/右"判，叠加框也跟着躺倒。
     * 值 = normalize(sensorOrientation − displayRotation)，由宿主（CameraScreen）喂入。
     * 非整 90 的机型按最近 90 取整（个别机型报 82/97）。
     */
    @Volatile
    var rotationDegrees = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 创建/复用 reader（YUV_420_888）与分析线程，返回供引擎 analysis 槽的 surface。
     * 幂等：surface 仍有效时重复调用返回既有面。
     * **失效重建（2026-11-07）**：引擎关闭序列会单方面 close reader（离页 unbind / 引擎重建），
     * 旧实现只认 `started` 标志 → 回场把一张已关闭的 Surface 挂回会话（建会话
     * `Surface abandoned`），站位从此静默失效。现在按 `surface.isValid` 判活并重建。
     * reader 所有权仍转移给引擎；本类只保证"再给我一张能用的面"。
     */
    fun start(size: Size = DEFAULT_SIZE): Surface {
        check(!released.get()) { "StanceAnalysis 已释放，不能再 start" }
        val existing = reader
        if (existing != null && existing.surface.isValid) return existing.surface
        if (existing != null) {
            runCatching { existing.setOnImageAvailableListener(null, null) }
            runCatching { existing.close() }
            reader = null
        }
        if (thread == null) {
            val t = HandlerThread("WotaStanceAnalysis").apply { start() }
            thread = t
            handler = Handler(t.looper)
        }
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ r2 ->
            val image = try {
                r2.acquireLatestImage() ?: return@setOnImageAvailableListener
            } catch (_: Exception) {
                return@setOnImageAvailableListener   // 引擎单方面关闭 reader 的窗口
            }
            processImage(image)
        }, handler)
        reader = r
        return r.surface
    }

    /** 当前分析面是否仍可挂进相机会话（引擎重建/离页后判活用）。 */
    fun isSurfaceLive(): Boolean = reader?.surface?.isValid == true

    /**
     * 引擎参数热更新（2026-11-07）：[JudgementEngine] 不是线程安全的，`observe()` 在本类
     * 分析线程跑；UI 线程直接 setBeta/setMode 会与 observe 并发读写 pendingHits/done/
     * countHist（偶发状态错乱或 ConcurrentModificationException 被单帧 catch 吞掉）。
     * 统一投到分析线程执行；管线未起（handler 为空）时直接应用，挂流前再同步一次。
     */
    fun updateEngineConfig(beta: Float, mode: String, n: Int, brighten: Boolean) {
        val apply = {
            val e = engine
            if (e.beta != beta) e.setBeta(beta)
            if (e.mode != mode || e.nManual != n) e.setMode(mode, n)
            this.brighten = brighten
        }
        val h = handler
        if (h != null && !released.get()) h.post(apply) else apply()
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
            val raw = yuv420ToRgba(image) ?: return
            // 传感器坐标 → 显示坐标（与预览同一"转正"语义；否则方向判定左右颠倒）
            val frame = rotateRgba(raw, w, h, rotationDegrees)
            if (brighten) brightenRgba(frame.pixels)
            val boxes = detector?.detect(frame.pixels, frame.width, frame.height, now) ?: return
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
        fun pickYuvSize(characteristics: CameraCharacteristics): Size =
            pickYuvSize(characteristics, targetAspect = null)

        /**
         * 带画幅比的尺寸选择（2026-11-07）：分析帧与预览/成片画幅不同会把归一化坐标
         * 映射到不同 FOV——有人明明居中，叠加框却偏。优先在 ≤640 档里找与 [targetAspect]
         * （传感器坐标，宽/高）相差 ≤8% 的尺寸，再取其中最大面积；没有匹配才退回最大面积。
         */
        fun pickYuvSize(characteristics: CameraCharacteristics, targetAspect: Float?): Size {
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return DEFAULT_SIZE
            val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return DEFAULT_SIZE
            val dims = sizes.map { it.width to it.height }
            val pick = pickDims(dims, targetAspect) ?: return DEFAULT_SIZE
            return sizes.firstOrNull { it.width == pick.first && it.height == pick.second }
                ?: DEFAULT_SIZE
        }

        /**
         * 尺寸选择纯函数（JVM 单测可测）：≤640 档内优先画幅比匹配（±8%），
         * 同匹配档取最大面积；无匹配则取最大面积档。null = 无可用尺寸。
         */
        internal fun pickDims(sizes: List<Pair<Int, Int>>, targetAspect: Float?): Pair<Int, Int>? {
            val capped = sizes.filter { it.first > 0 && it.second > 0 && it.first <= 640 && it.second <= 640 }
            if (capped.isEmpty()) return null
            if (targetAspect != null && targetAspect > 0f) {
                val matching = capped.filter {
                    val aspect = it.first.toFloat() / it.second
                    kotlin.math.abs(aspect - targetAspect) <= targetAspect * 0.08f
                }
                if (matching.isNotEmpty()) return matching.maxBy { it.first.toLong() * it.second }
            }
            return capped.maxBy { it.first.toLong() * it.second }
        }

        /**
         * RGBA 帧转正（2026-11-07，纯函数）：按顺时针 [degrees] 旋转，90/270 交换宽高。
         * 非整 90 的传感器角（个别机型 82/97）按最近 90 取整——宁可偏 8°，也不能把画面躺倒。
         */
        fun rotateRgba(src: IntArray, w: Int, h: Int, degrees: Int): RotatedFrame {
            val quarter = ((kotlin.math.round(degrees / 90f).toInt() % 4) + 4) % 4
            if (quarter == 0) return RotatedFrame(src, w, h)
            val swapped = quarter % 2 == 1
            val outW = if (swapped) h else w
            val outH = if (swapped) w else h
            val out = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val px = src[y * w + x]
                    val (dx, dy) = when (quarter) {
                        1 -> (h - 1 - y) to x          // 90° 顺时针
                        2 -> (w - 1 - x) to (h - 1 - y) // 180°
                        else -> y to (w - 1 - x)        // 270° 顺时针（= 90° 逆时针）
                    }
                    out[dy * outW + dx] = px
                }
            }
            return RotatedFrame(out, outW, outH)
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
