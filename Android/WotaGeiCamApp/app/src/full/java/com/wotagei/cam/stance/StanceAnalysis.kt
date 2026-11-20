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
import com.wotagei.cam.diag.Diag
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    private val generation = AtomicLong(0L)
    @Volatile private var reader: ImageReader? = null
    /**
     * [reader] 的配置键（镜头+画幅，B14）：键变了必须重开一张——4:3→16:9 后沿用旧尺寸的面，
     * 叠框坐标按预览尺寸缩放就会整体偏移（设备对两路输出的裁剪不一定相同）。
     */
    @Volatile private var readerKey: String? = null
    /**
     * 已排队回收、尚未真正关闭的实例（Y09）。[start] 绝不复用它（关闭任务随时会把它关死），
     * [isSurfaceLive] 也按「不活」报——否则「已排队回收但面仍有效」会被交回新引擎。
     */
    @Volatile private var recycling: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastFrameMs = 0L

    /**
     * 分配代（Y08）：每次 [startAsync] +1；[releaseReader]/[release] 也 +1 使在途分配作废。
     * 创建/复用/回收判定与 [ImageReader.newInstance] 全部在与分析帧同一条 owner 线程串行执行，
     * 调用方（Main）只投递请求，不再同步准备 reader。
     */
    private val allocation = AtomicLong(0L)

    /** 引擎与判定头（模型组装配；未就绪时帧被丢弃）。 */
    var engine = JudgementEngine(mode = JudgementEngine.MODE_AUTO, nManual = 1)

    /** B15：仅分析 owner 线程使用，YUV/RGBA/旋转缓冲跨帧复用。 */
    private val scratch = FrameScratch()
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
    fun updateModel(
        newDetector: FrameDetector?,
        newHead: StanceHead?,
        marginsJson: String? = null,
        modelFingerprint: String? = null,
    ) {
        val h = ensureAnalysisThread()
        val apply: () -> Unit = {
            if (!released.get()) swapModel(newDetector, newHead, marginsJson, modelFingerprint)
            else (newDetector as? Closeable)?.let { runCatching { it.close() } }
        }
        if (Thread.currentThread() === thread) apply()
        else check(h.post(apply)) { "站位分析线程已停止" }
    }

    /** 模型 native 操作交给长期 owner；等待可取消，不能用超时后继续写入的 FutureTask。 */
    suspend fun onAnalysis(block: () -> Unit) = suspendCancellableCoroutine<Unit> { continuation ->
        val task = Runnable {
            if (!continuation.isActive) return@Runnable
            if (released.get()) {
                continuation.resumeWithException(CancellationException("站位分析管线已释放"))
                return@Runnable
            }
            try {
                block()
                continuation.resume(Unit)
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }
        synchronized(this) {
            if (released.get()) continuation.resumeWithException(CancellationException("站位分析管线已释放"))
            else if (!ensureAnalysisThread().post(task)) {
                continuation.resumeWithException(CancellationException("站位分析线程已停止"))
            }
        }
    }

    /** 当前判定语义指纹（只在这些值变化时重置引擎；纯后端切换必须保留判定状态，Y68）。 */
    private var marginsSnapshot: String? = null
    private var headSnapshot: String? = null
    private var modelSnapshot: String? = null

    private fun headSemantics(h: StanceHead?): String? =
        h?.let { "${it.coef.joinToString(",")}|${it.intercept}" }

    private fun swapModel(
        newDetector: FrameDetector?,
        newHead: StanceHead?,
        marginsJson: String?,
        modelFingerprint: String?,
    ) {
        // 安全区覆盖也在这里落：MARGIN_BANDS 是全局可变表，判定线程正在读它，
        // 不能让装配线程并发改（旧实现由主线程直接 loadCalibratedBands）。
        val marginsChanged = marginsJson != null && marginsJson != marginsSnapshot
        if (marginsJson != null) {
            StanceSpec.loadCalibratedBands(marginsJson)
            marginsSnapshot = marginsJson
        }
        val newHeadKey = headSemantics(newHead)
        // 首次装配 headSnapshot=null 不算“换头”，避免冷启就清掉用户刚恢复的状态。
        val headChanged = headSnapshot != null && newHeadKey != headSnapshot
        val modelChanged = modelFingerprint != null && modelSnapshot != null &&
            modelFingerprint != modelSnapshot
        val semanticsChanged = marginsChanged || headChanged || modelChanged

        generation.incrementAndGet()
        val old = detector
        detector = newDetector
        head = newHead
        engine.setHead(newHead)
        if (modelFingerprint != null) modelSnapshot = modelFingerprint
        headSnapshot = newHeadKey
        if (old !== newDetector) (old as? Closeable)?.let { runCatching { it.close() } }
        if (semanticsChanged) {
            // 新旧标定/判定头/检测模型不能混用旧观测：人数锁定、锚点、平滑/完成态全部重来。
            engine.reset()
            Diag.i(TAG, "判定语义变化，引擎状态已重置（margins=$marginsChanged head=$headChanged model=$modelChanged）")
        }
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
    private fun ensureAnalysisThread(): Handler = synchronized(this) {
        check(!released.get()) { "StanceAnalysis 已释放" }
        handler ?: HandlerThread("WotaStanceAnalysis").apply {
            start()
            thread = this
            handler = Handler(looper)
        }.let { requireNotNull(handler) }
    }

    /**
     * 请求一张可挂进相机会话的分析面（Y08：**分配归分析 owner 线程**）。
     *
     * 调用方（Main）只做一次投递；复用判定、旧面排队回收、[ImageReader.newInstance] 与
     * listener 绑定全部在 "WotaStanceAnalysis" 线程串行执行。成功结果经 [mainHandler] 交付在
     * **Main 线程**，交付前再验一次分配代/实例身份：过期请求不回调，刚创建但未交付的 reader
     * 仍归本管线（下次请求复用，或被 [releaseReader]/[release] 回收），不会被误关。
     *
     * [onReady] 在 Main 交付当前分配结果。失败时交付空面，解除调用方的在途标记，等下次门控重试。
     * 已释放或已被更新请求取代时，调用方会同时作废原意图，无须回调。
     */
    fun startAsync(
        size: Size = DEFAULT_SIZE,
        key: String? = null,
        onReady: (Surface?, ImageReader?) -> Unit,
    ) {
        val ticket = allocation.incrementAndGet()
        val h = try {
            ensureAnalysisThread()
        } catch (_: IllegalStateException) {
            deliver(null, ticket, onReady)
            return
        }
        if (!h.post { allocate(size, key, ticket, onReady) }) deliver(null, ticket, onReady)
    }

    /**
     * owner 线程上的分配决策：与 [processImage] 同一条串行队列，不会与在途帧的回收交错。
     * 任何一步失败都保持“没有新面”的诚实状态，不伪报可挂。
     */
    private fun allocate(size: Size, key: String?, ticket: Long, onReady: (Surface?, ImageReader?) -> Unit) {
        if (released.get() || ticket != allocation.get()) return
        val existing = reader
        // 复用判据（B14/Y09）：面仍有效 **且** 配置键（镜头+画幅）相同 **且** 没在排队回收。
        // 旧口径只看 isValid：新画幅会沿用按旧画幅开的面（叠框坐标乘预览尺寸就偏）；
        // 已排队回收的面也会被交回新引擎（关闭任务随即把它关死，新会话拿到僵尸面）。
        if (existing != null && existing.surface.isValid && existing !== recycling &&
            (key == null || key == readerKey)
        ) {
            deliver(existing, ticket, onReady)
            return
        }
        // 旧面按**确切实例**交给分析 owner 排队关闭（Y09），不在这里同步 close。
        // 已在回收队列里的实例不再入队第二次：releaseReader 刚排过队、startAsync 立刻换键时会走到这里，
        // 重复入队会让同一枚 reader 被关两次（验收口径是"恰好关闭一次"）。
        if (existing != null && existing !== recycling) recycle(existing)
        val h = checkNotNull(handler)
        val r = try {
            ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        } catch (e: Exception) {
            Diag.w(TAG, "分析面分配失败：${e.javaClass.simpleName}")
            deliver(null, ticket, onReady)
            return
        }
        val listeningReady = runCatching {
            r.setOnImageAvailableListener({ r2 ->
                val image = try {
                    r2.acquireLatestImage() ?: return@setOnImageAvailableListener
                } catch (_: Exception) {
                    return@setOnImageAvailableListener
                }
                processImage(image)
            }, h)
        }.isSuccess
        val adopted = synchronized(this) {
            if (!listeningReady || released.get() || ticket != allocation.get()) false
            else {
                reader = r
                readerKey = key
                true
            }
        }
        if (!adopted) runCatching { r.close() }
        deliver(r.takeIf { adopted }, ticket, onReady)
    }

    /**
     * 把成功分配的结果交付到 Main。交付与 controller 的挂/摘槽同线程（Main 串行），
     * 检查通过后不会再有 Main 侧动作插进“检查 → attachHook”之间；owner 侧仍可能因换键/释放
     * 把这一枚排队回收，所以实例身份与回收标记也要再验（Y09 僵尸面）。
     */
    private fun deliver(target: ImageReader?, ticket: Long, onReady: (Surface?, ImageReader?) -> Unit) {
        mainHandler.post {
            if (ticket != allocation.get()) return@post
            val ready = synchronized(this) {
                target?.takeIf { !released.get() && it === reader && it !== recycling }
            }
            val surface = ready?.let { runCatching { it.surface }.getOrNull() }?.takeIf { it.isValid }
            onReady(surface, ready.takeIf { surface != null })
        }
    }

    /** 当前分析面是否仍可挂进相机会话（引擎重建/离页后判活用）。 */
    fun isSurfaceLive(): Boolean {
        val r = reader
        return r != null && r !== recycling && r.surface.isValid
    }

    /**
     * 排队回收一枚**确切**的 reader（Y09）：关闭任务只持有 [target]，绝不重读可变字段。
     *
     * 旧实现闭包重读 `reader` 字段：排队期间 [start] 换上新实例时，旧任务会把**新**面关掉、
     * 旧实例反而泄漏。排队回收期间该实例被 [recycling] 标记，[start]/[isSurfaceLive] 都不再认它。
     */
    private fun recycle(target: ImageReader) {
        // 同一实例只入队一次：releaseReader 刚排过队、start 立刻换键（镜头/画幅变了）会再走到
        // 这里——重复入队会让同一枚 reader 被关两次，验收口径是「恰好关闭一次」。
        if (recycling === target) return
        recycling = target
        val close: () -> Unit = {
            runCatching { target.setOnImageAvailableListener(null, null) }
            runCatching { target.close() }
            synchronized(this) {
                if (recycling === target) recycling = null
                if (reader === target) reader = null
            }
        }
        val h = handler
        if (h != null && !released.get() && Thread.currentThread() !== thread) {
            if (!h.post(close)) {
                // 线程已停：回收承诺无法再兑现，撤销标记并按调用线程尽力关闭（只会关这一枚）
                synchronized(this) { if (recycling === target) recycling = null }
                runCatching { target.close() }
            }
        } else {
            close()
        }
    }

    /**
     * 引擎参数热更新（2026-11-07）：[JudgementEngine] 不是线程安全的，`observe()` 在本类
     * 分析线程跑；UI 线程直接 setBeta/setMode 会与 observe 并发读写 pendingHits/done/
     * countHist（偶发状态错乱或 ConcurrentModificationException 被单帧 catch 吞掉）。
     * 统一投到分析线程执行；管线未起（handler 为空）时直接应用，挂流前再同步一次。
     */
    fun updateEngineConfig(beta: Float, mode: String, n: Int, brighten: Boolean, lineIntervalMs: Long, pollIntervalMs: Long) {
        val apply = {
            val e = engine
            if (e.beta != beta) e.setBeta(beta)
            if (e.mode != mode || e.nManual != n) e.setMode(mode, n)
            e.setIntervals(lineIntervalMs, pollIntervalMs)
            this.brighten = brighten
        }
        val h = handler
        if (h != null && !released.get()) h.post(apply) else apply()
    }

    /** 供引擎 setAnalysisTarget(surface, reader) 的 reader 参数（所有权转移给引擎）。 */
    fun reader(): ImageReader? = reader

    private fun processImage(image: Image) {
        var imageClosed = false
        try {
            if (!listening.get()) return
            val frameGeneration = generation.get()
            val now = System.currentTimeMillis()
            if (now - lastFrameMs < FRAME_INTERVAL_MS) return   // 丢帧节流 ≈3fps
            lastFrameMs = now
            val w = image.width
            val h = image.height
            // B15：YUV 三平面先拷进 owner 线程复用缓冲，随后**立即归还 Image**，
            // 不再让 ImageReader 的 2 个槽位被整段推理占住（转换/旋转/推理都用副本）。
            val raw = (try {
                yuv420ToRgba(image, scratch)
            } finally {
                runCatching { image.close() }
                imageClosed = true
            }) ?: return
            // 传感器坐标 → 显示坐标（与预览同一"转正"语义；否则方向判定左右颠倒）
            val frame = rotateRgba(raw, w, h, rotationDegrees, scratch.rotated(w * h))
            if (brighten) brightenRgba(frame.pixels)
            val boxes = detector?.detect(frame.pixels, frame.width, frame.height, now) ?: return
            val out = engine.observe(FrameObs(now, boxes))
            mainHandler.post {
                if (!released.get() && listening.get() && generation.get() == frameGeneration) onResult(out)
            }
        } catch (_: Exception) {
            // 单帧失败不致断流（reader 被引擎单方面关闭时本回调可能再触发）
        } finally {
            if (!imageClosed) runCatching { image.close() }
        }
    }

    /** B15：把 Image 三平面拷进复用缓冲（不做转换；Image 可在返回后立即关闭）。 */
    private fun yuv420ToRgba(image: Image, scratch: FrameScratch): IntArray? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val w = image.width
        val h = image.height
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val ySize = yP.buffer.remaining()
        val uSize = uP.buffer.remaining()
        val vSize = vP.buffer.remaining()
        val (yBuf, uBuf, vBuf) = scratch.yuv(ySize, uSize, vSize)
        yP.buffer.get(yBuf, 0, ySize)
        uP.buffer.get(uBuf, 0, uSize)
        vP.buffer.get(vBuf, 0, vSize)
        return yuv420ToRgba(
            yBuf, uBuf, vBuf, w, h,
            yP.rowStride, uP.rowStride, uP.pixelStride, vP.rowStride, vP.pixelStride,
            scratch.rgba(w * h),
        )
    }

    /** 停本管线回调（引擎槽不动——录制窗口期用，停录后 [resumeListening]）。 */
    fun pauseListening() {
        listening.set(false)
        generation.incrementAndGet()
    }

    /** 恢复回调（默认关闭——[start] 后需显式 resume 才开始吃帧）。 */
    fun resumeListening() {
        listening.set(true)
    }

    /**
     * 只回收当前 reader，保留分析线程/模型（相机 Controller 销毁但功能意图仍在时用）。
     * 关闭排在分析 owner 线程：同队列会在途 processImage 先归还 Image，再封口 listener 并 close。
     */
    fun releaseReader() {
        // Y08/Y09 交界：作废在途分配——已经在路上的交付不得把即将回收的面挂回新会话。
        allocation.incrementAndGet()
        // Y09：只回收当前这一枚，按实例排队；关掉后再清字段（期间已换新实例则保留新的）
        reader?.let(::recycle)
    }

    /** 彻底释放线程并在同一 owner 线程关闭当前检测器。 */
    fun release() {
        synchronized(this) {
            if (!released.compareAndSet(false, true)) return
            listening.set(false)
            generation.incrementAndGet()
            allocation.incrementAndGet()   // 让在途分配在 owner 队列上自检作废，不再交付/创建
            val close: () -> Unit = {
                runCatching { reader?.setOnImageAvailableListener(null, null) }
                runCatching { reader?.close() }
                reader = null
                val old = detector
                detector = null
                head = null
                (old as? Closeable)?.let { runCatching { it.close() } }
                thread?.quitSafely()
            }
            val h = handler
            if (h != null && Thread.currentThread() !== thread) {
                if (!h.post(close)) close()
            } else {
                close()
            }
        }
    }

    companion object {
        private const val TAG = "StanceAnalysis"
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
        fun rotateRgba(
            src: IntArray,
            w: Int,
            h: Int,
            degrees: Int,
            reuse: IntArray? = null,
        ): RotatedFrame {
            val quarter = ((kotlin.math.round(degrees / 90f).toInt() % 4) + 4) % 4
            if (quarter == 0) return RotatedFrame(src, w, h)
            val swapped = quarter % 2 == 1
            val outW = if (swapped) h else w
            val outH = if (swapped) w else h
            val out = reuse?.takeIf { it.size >= w * h } ?: IntArray(w * h)
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
            reuse: IntArray? = null,
        ): IntArray {
            val out = reuse?.takeIf { it.size >= width * height } ?: IntArray(width * height)
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
