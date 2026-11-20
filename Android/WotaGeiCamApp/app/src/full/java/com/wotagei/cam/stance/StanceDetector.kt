package com.wotagei.cam.stance

import com.wotagei.cam.diag.Diag

import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 站位检测器（M2b）：LiteRT(tflite) 封装，实现 [StanceAnalysis.FrameDetector]。
 *
 * 分层加速（D20/用户十八稿）：**CPU int8（XNNPACK）兜底 → GPU（fp32 模型+delegate）
 * 通用加速 → NPU 渐进**（LiteRT Next/QNN 机型白名单，M2+ 探索；当前 NPU 未接入，
 * UI 文案按"GPU（NPU 自动探测）"表述）。GPU delegate 白名单外机型 load 时抛异常
 * → **自动回退 CPU 并标记**（降级必提示）。
 *
 * 输入布局**从模型张量读**（2026-11-07 修正）：本工程随包的 Ultralytics→MLIR 导出是
 * **NCHW [1,3,448,448]**（不是 NHWC）——按 NHWC 写会把通道当像素交错，推理输出全错。
 * 两种布局都支持，由 [inputLayoutOf] 从 shape 判定；FLOAT32 → 像素 /255，UINT8/INT8 →
 * 原值字节。输出 [1,84,4116] 必须 FLOAT32（本工程两个模型均如此；其它类型在 load
 * 期明确失败，不留静默误判）。
 * 输出布局：每列 anchor = [cx,cy,w,h, cls0..79]（像素，输入尺度），person=类 0；
 * 解码 → conf 过滤 → NMS(IoU 0.45) → 归一化 [PersonBox]（帧占比，
 * 与 PC 端 ultralytics 归一化同义——端侧直接 resize 无 letterbox，纵横比小幅拉伸可接受，M2 真机验证）。
 * 线程假设：detect 由 [StanceAnalysis] 单线程串行调用；缓冲复用因此无需加锁。
 */
class StanceDetector : StanceAnalysis.FrameDetector, Closeable {
    private var interpreter: Interpreter? = null
    private var delegate: GpuDelegate? = null
    private var gpuActive = false
    @Volatile private var loaded = false
    private var inputW = 448
    private var inputH = 448
    private var inputFloat = true
    private var inputNchw = false

    /** 清单显式坐标单位（Y66）；null=旧包，decode 按输出值域自适应。 */
    private var coordUnit: String? = null
    private var inputBuf: ByteBuffer? = null
    private var outputBuf: Array<Array<FloatArray>>? = null
    private var loggedRunFailure = false
    private var cpuModel: ByteBuffer? = null
    private var gpuModel: ByteBuffer? = null
    private var cpuFallbackVerified = false
    private var terminalFailure: Exception? = null

    /** 运行期后端变化也必须告知宿主，不能把 GPU 错误伪装为正常空检测。 */
    var onBackendChanged: ((String, String?) -> Unit)? = null

    /** 当前加速层（UI 显示；含回退标记）。 */
    var backendName: String = ""
        private set

    /** GPU 回退原因（null=未回退/未尝试；UI 提示用——降级必提示）。 */
    var gpuFallbackReason: String? = null
        private set

    fun isLoaded(): Boolean = loaded

    fun isGpuActive(): Boolean = gpuActive

    /**
     * 加载模型并建 Interpreter。gpuMode 三态（r03，用户二十稿：白名单外 ≠ 跑不了）：
     * - **auto**：CompatibilityList 白名单裁决（现状）——不收录则回退 CPU；
     * - **force**：**跳过白名单直接建 delegate**（用户自担风险的实验通道——Maleoon
     *   等新 GPU 白名单未收录但有 GLES 3.1 compute 能力），建完跑一次 dummy 推理
     *   做运行期验证（非 NaN + shape 合理），失败回退 CPU 并提示原因；
     * - **off**：纯 CPU。
     * 张量布局不合规格（4 维输入 / float32 输出）→ 关掉已建资源并返回 false。
     */
    fun load(int8File: File, gpuFile: File?, gpuMode: String): Boolean =
        load(int8File, gpuFile, gpuMode, null)

    /**
     * [coordUnit] 来自模型清单（Y66）：显式声明后不再猜值域；null 保留旧包的 0~1/像素自适应。
     * 非法值在导入校验已拒绝，这里再挡一次，避免绕过 StanceModelPack 的调用方把错误单位带进热路径。
     */
    fun load(int8File: File, gpuFile: File?, gpuMode: String, coordUnit: String?): Boolean {
        require(
            coordUnit == null ||
                coordUnit == StanceModelPack.COORD_NORMALIZED ||
                coordUnit == StanceModelPack.COORD_PIXEL,
        ) { "未知坐标单位：$coordUnit" }
        close()
        loaded = false
        gpuActive = false
        gpuFallbackReason = null
        cpuFallbackVerified = false
        loggedRunFailure = false
        this.coordUnit = coordUnit
        // CPU 路线无论如何先备好（GPU 失败即回退）；运行期回退也复用这份缓冲。
        cpuModel = loadModelBuffer(int8File)
        val cpuOpts = Interpreter.Options().setNumThreads(4)
        if (gpuMode != MODE_OFF && gpuFile != null) {
            // Y73：auto 才查兼容列表；force 完全不依赖它（列表构造/查询抛异常也不能拦默认 delegate）。
            // 用 GpuDelegateFactory.Options（GpuDelegate.Options 已弃用）：bestOptionsForThisDevice 的
            // 具体对象原样交给 GpuDelegateFactory.Options 构造重载，加速语义不变。
            val autoOptions: org.tensorflow.lite.gpu.GpuDelegateFactory.Options? = if (gpuMode == MODE_FORCE) {
                null
            } else {
                try {
                    org.tensorflow.lite.gpu.CompatibilityList().use { compat ->
                        if (!compat.isDelegateSupportedOnThisDevice) {
                            gpuFallbackReason = "GPU 兼容列表不通过（该机型/环境白名单外），已自动回退 CPU"
                            Diag.w(TAG, "GPU delegate 兼容列表不通过，走 CPU")
                            null
                        } else {
                            compat.bestOptionsForThisDevice
                        }
                    }
                } catch (e: Throwable) {
                    gpuFallbackReason = "GPU 兼容列表查询失败（${e.message}），已自动回退 CPU"
                    Diag.w(TAG, "GPU 兼容列表查询失败：${e.message}")
                    null
                }
            }
            if (gpuMode == MODE_FORCE || autoOptions != null) {
                var candidate: GpuDelegate? = null
                try {
                    // force：跳过白名单（用户裁决 Maleoon 等新 GPU 有 GLES compute 能力，
                    // 白名单只是"已知能工作"的规则库，不是能力探测）
                    candidate = if (autoOptions != null) GpuDelegate(autoOptions) else GpuDelegate()
                    gpuModel = loadModelBuffer(gpuFile)
                    val gpuOpts = Interpreter.Options().addDelegate(candidate)
                    interpreter = Interpreter(requireNotNull(gpuModel), gpuOpts)
                    bindTensorSpec()
                    runtimeVerify(intp = interpreter!!)   // 运行期验证（force 路线的安全网）
                    delegate = candidate
                    candidate = null // 资源所有权转交给字段，后续统一由 close 管理
                    gpuActive = true
                    backendName = if (gpuMode == MODE_FORCE) "GPU（强制）" else "GPU"
                    Diag.i(TAG, "GPU delegate 就绪 mode=$gpuMode")
                } catch (e: Throwable) {
                    gpuFallbackReason = "GPU 初始化/运行期验证失败（${e.message}），已自动回退 CPU"
                    Diag.w(TAG, "GPU delegate 不可用，回退 CPU：${e.message}")
                    runCatching { interpreter?.close() }
                    interpreter = null
                    runCatching { delegate?.close() }
                    delegate = null
                    // Interpreter 构造/首验失败时 candidate 尚未转交字段，必须在这里回收。
                    runCatching { candidate?.close() }
                    candidate = null
                }
            }
        }
        if (!gpuActive) {
            interpreter = Interpreter(requireNotNull(cpuModel), cpuOpts)
            // 回退标记：fallbackTip 依赖 backendName.contains("回退")——GPU 开着却回退必须让用户看见
            backendName = if (gpuMode != MODE_OFF && gpuFile != null) "CPU（$gpuFallbackReason）" else "CPU"
        }
        return try {
            if (!gpuActive) bindTensorSpec()
            loaded = true
            true
        } catch (e: Exception) {
            Diag.w(TAG, "模型张量规格不支持：${e.message}")
            close()
            false
        }
    }

    /**
     * R16-CPU：确认「CPU int8 兜底」不仅文件存在，而且真的能装配。
     *
     * GPU 成功时 [load] 只构造 GPU Interpreter；CPU 模型仅整读进缓冲，尚未证明可构造。
     * 导入确认会删除上一套用户模型，因此删除前必须补这一次独立探针（用完立即关闭，不常驻）。
     * 已有 CPU Interpreter（GPU 未启用或已回退）则天然满足，不重复构造。
     * 仅由装配/确认路径调用，**不进入逐帧检测路径**；重复装配同一已确认包也不会再探针。
     */
    internal fun verifyCpuFallback(): Boolean {
        if (!loaded) return false
        if (!gpuActive) return interpreter != null
        if (cpuFallbackVerified) return true
        val model = cpuModel ?: return false
        var probe: Interpreter? = null
        return try {
            // duplicate 与 cpuModel 共享内容但 position 独立：探针不能推进运行期回退要用的缓冲。
            probe = Interpreter(model.duplicate(), Interpreter.Options().setNumThreads(1))
            require(probe.inputTensorCount == 1 && probe.outputTensorCount == 1) {
                "CPU 兜底模型输入/输出数量不符"
            }
            cpuFallbackVerified = true
            Diag.i(TAG, "CPU 兜底模型装配验证通过")
            true
        } catch (e: Throwable) {
            Diag.w(TAG, "CPU 兜底模型不可装配：${e.message}")
            false
        } finally {
            runCatching { probe?.close() }
        }
    }

    /**
     * 运行期验证（force 路线安全网）：dummy 推理——输出必须有限（非 NaN/Inf）且
     * person 分数行有界。白名单外机型常见故障模式=GL 编译失败抛异常（构造期已兜）
     * 或静默输出垃圾（这里兜）。
     */
    private fun runtimeVerify(intp: Interpreter) {
        val inTensor = intp.getInputTensor(0)
        val (nchw, wh) = inputLayoutOf(inTensor.shape())
        val (iw, ih) = wh
        val dtype = inTensor.dataType()
        require(dtype == DataType.FLOAT32) { "GPU 验证仅支持 float32 输入：$dtype" }
        val bytes = inTensor.shape().fold(1L) { acc, d -> acc * d } * 4
        require(bytes in 4..(StanceTensorContract.MAX_TENSOR_ELEMENTS * 4)) { "GPU 输入张量过大" }
        val dummy = java.nio.ByteBuffer.allocateDirect(bytes.toInt()).order(java.nio.ByteOrder.nativeOrder())
        repeat(bytes.toInt() / 4) { dummy.putFloat(0.5f) }
        dummy.rewind()
        val outShape = intp.getOutputTensor(0).shape()
        require(outShape.size == 3 && outShape[0] == 1 && outShape[1] >= 5 && outShape[2] > 0) {
            "GPU 输出形状不支持：${outShape.toList()}"
        }
        val outCount = outShape.fold(1L) { acc, d -> acc * d }
        require(outCount in 1..StanceTensorContract.MAX_TENSOR_ELEMENTS) { "GPU 输出张量过大" }
        val outBuf = java.nio.ByteBuffer.allocateDirect(outCount.toInt() * 4)
            .order(java.nio.ByteOrder.nativeOrder())
        intp.run(dummy, outBuf)
        outBuf.rewind()
        val outFloat = FloatArray(outCount.toInt()) { outBuf.float }
        // Y73：有限值不够——按输出规格验证坐标域与 person 分数范围。
        val cols = outShape[2]
        val coordLimit = maxOf(iw, ih).toFloat() * 4f
        for (r in 0 until 4) {
            for (c in 0 until cols) {
                val v = outFloat[r * cols + c]
                require(v.isFinite() && kotlin.math.abs(v) <= coordLimit) {
                    "GPU 输出坐标越域（row=$r col=$c value=$v）"
                }
            }
        }
        for (c in 0 until cols) {
            val score = outFloat[4 * cols + c]
            require(score.isFinite() && score in -0.01f..1.01f) {
                "GPU 输出 person 分数越域（col=$c value=$score）"
            }
        }
        Diag.i(TAG, "GPU 运行期验证通过（${if (nchw) "NCHW" else "NHWC"} ${iw}x$ih，输出 ${outShape.toList()} 坐标/分数在域）")
    }

    /** 从**模型实际张量**取输入布局/尺寸与输出 dtype（不再硬编码 448/NHWC）。 */
    private fun bindTensorSpec() {
        val itp = interpreter ?: throw IllegalStateException("interpreter 未就绪")
        require(itp.inputTensorCount == 1) { "检测模型必须恰有 1 个输入，实际 ${itp.inputTensorCount}" }
        require(itp.outputTensorCount == 1) { "检测模型必须恰有 1 个输出，实际 ${itp.outputTensorCount}" }
        val inTensor = itp.getInputTensor(0)
        val (nchw, wh) = inputLayoutOf(inTensor.shape())
        val dtype = inTensor.dataType()
        // Y67：检测器没有按 scale/zeroPoint 反量化，量化输入/半精度输入装配期拒绝。
        require(dtype == DataType.FLOAT32) {
            "输入 dtype=$dtype，暂不支持（需 float32；量化模型请导出 float I/O）"
        }
        val inputElems = inTensor.shape().fold(1L) { acc, d -> acc * d }
        require(inputElems in 1..StanceTensorContract.MAX_TENSOR_ELEMENTS) { "输入张量元素数越界" }
        val outTensor = itp.getOutputTensor(0)
        require(outTensor.dataType() == DataType.FLOAT32) {
            "输出 dtype 必须 float32（当前 ${outTensor.dataType()}）；量化输出模型请重新导出为 float 输出"
        }
        val outShape = outTensor.shape()
        require(outShape.size == 3 && outShape[0] == 1) {
            "输出应为 [1,行,列]（batch=1）：${outShape.toList()}"
        }
        require(outShape[1] >= 5) { "输出行数不足 5（缺 person 类分数行）：${outShape.toList()}" }
        require(outShape[2] > 0) { "输出列数非正：${outShape.toList()}" }
        val outElems = outShape.fold(1L) { acc, d -> acc * d }
        require(outElems in 1..StanceTensorContract.MAX_TENSOR_ELEMENTS) { "输出张量元素数越界" }
        inputNchw = nchw
        inputW = wh[0]
        inputH = wh[1]
        inputFloat = true
        inputBuf = null
        outputBuf = null
        Diag.i(TAG, "模型张量：in=${inTensor.shape().toList()} $dtype layout=${if (nchw) "NCHW" else "NHWC"}" +
            " out=${outShape.toList()} ${outTensor.dataType()}")
    }

    override fun detect(rgba: IntArray, w: Int, h: Int, tsMs: Long): List<PersonBox> {
        terminalFailure?.let { throw it }
        return try {
            detectWithCurrentBackend(rgba, w, h)
        } catch (e: Exception) {
            if (gpuActive) {
                try {
                    fallbackToCpu(e)
                    return detectWithCurrentBackend(rgba, w, h)
                } catch (cpuError: Exception) {
                    failBackend(cpuError)
                }
            }
            failBackend(e)
        }
    }

    /** 无可用后端的帧必须被分析层丢弃，不能以空框进入“缺人”判定。 */
    private fun failBackend(error: Exception): Nothing {
        close()
        terminalFailure = error
        backendName = "不可用"
        gpuFallbackReason = "站位推理不可用（${error.message}），已停止判定，请重新加载模型"
        logRunFailure(error)
        onBackendChanged?.invoke(backendName, gpuFallbackReason)
        throw error
    }

    private fun detectWithCurrentBackend(rgba: IntArray, w: Int, h: Int): List<PersonBox> {
        val itp = interpreter ?: return emptyList()
        val input = makeInput(rgba, w, h)
        val outShape = itp.getOutputTensor(0).shape()   // [1,84,4116]
        val nRows = outShape[1]
        val nCols = outShape[2]
        val output = outputBuf?.takeIf {
            it.size == 1 && it[0].size == nRows && it[0].firstOrNull()?.size == nCols
        } ?: Array(1) { Array(nRows) { FloatArray(nCols) } }.also { outputBuf = it }
        itp.run(input, output)
        return decode(output[0], nCols)
    }

    /** GPU 运行期失败不能以空框吞掉；关掉 GPU 后在同一分析线程重跑当前帧。 */
    private fun fallbackToCpu(error: Throwable) {
        val reason = "GPU 运行失败（${error.message}），已自动回退 CPU"
        gpuFallbackReason = reason
        runCatching { interpreter?.close() }
        interpreter = null
        runCatching { delegate?.close() }
        delegate = null
        gpuActive = false
        interpreter = Interpreter(requireNotNull(cpuModel), Interpreter.Options().setNumThreads(4))
        bindTensorSpec()
        backendName = "CPU（$reason）"
        onBackendChanged?.invoke(backendName, gpuFallbackReason)
        Diag.w(TAG, reason)
    }

    private fun logRunFailure(error: Throwable) {
        if (!loggedRunFailure) {
            loggedRunFailure = true
            Diag.w(TAG, "推理失败（后续同类错误不再重复打）：${error.message}")
        }
    }

    private fun makeInput(rgba: IntArray, w: Int, h: Int): ByteBuffer {
        val bytesPer = if (inputFloat) 4 else 1
        val need = inputW * inputH * 3 * bytesPer
        val buf = inputBuf?.takeIf { it.capacity() == need } ?: ByteBuffer.allocateDirect(need)
            .order(ByteOrder.nativeOrder()).also { inputBuf = it }
        fillInput(buf, rgba, w, h, inputW, inputH, inputNchw, inputFloat)
        return buf
    }

    /**
     * 输出 rows[5][nCols] → conf 过滤 → NMS → 归一化 PersonBox（帧占比）。
     *
     * 坐标尺度**按输出实际值域自适应**（真机实测教训）：litert 导出 wrapper 的输出
     * **已归一化 0~1**（cx/cy/w/h 直接就是占比），而按 ultralytics 常规应是输入尺度
     * 像素（0~448）——旧实现按像素 ÷inputW 把 0~1 坐标又缩小 448 倍 = 框缩成一个点
     * =「根本没识别」（真机首测实锤）。解码前扫坐标 4 行的最大值：>2 视为像素尺度
     * （÷inputW），≤2 视为已归一化（直用）。
     */
    internal fun decode(rows: Array<FloatArray>, nCols: Int): List<PersonBox> =
        decode(rows, nCols, coordUnit)

    /**
     * 输出 rows[5][nCols] → conf 过滤 → NMS → 归一化 PersonBox（帧占比）。
     *
     * 归一化方式由 [coordUnit] 决定（Y66/R16 清单显式声明）：
     * - `normalized`：cx/cy/w/h 已是 0~1，直用；
     * - `pixel`：相对**输入张量**宽高的像素（如 448×448），÷inputW/inputH；
     * - null（既有旧包缺字段）：按输出实际值域自适应——litert 导出 wrapper 实测输出
     *   已归一化 0~1，而 ultralytics 常规是输入尺度像素；>2 视为像素，避免旧包升级后失效。
     *
     * 像素分支必须除 inputW/inputH，不能乘分析帧 w/h：PersonBox 消费者按画面占比解释。
     */
    internal fun decode(rows: Array<FloatArray>, nCols: Int, coordUnit: String?): List<PersonBox> {
        require(
            coordUnit == null ||
                coordUnit == StanceModelPack.COORD_NORMALIZED ||
                coordUnit == StanceModelPack.COORD_PIXEL,
        ) { "未知坐标单位：$coordUnit" }
        val normalized = when (coordUnit) {
            StanceModelPack.COORD_NORMALIZED -> true
            StanceModelPack.COORD_PIXEL -> false
            else -> {
                var coordMax = 0f
                for (r in 0 until 4) for (c in 0 until nCols step 16) {
                    coordMax = maxOf(coordMax, rows[r][c])
                }
                coordMax <= 2f
            }
        }
        val cands = ArrayList<FloatArray>(16)
        for (c in 0 until nCols) {
            val score = rows[4][c]     // rows[4] = 类 0（person）分数行
            if (score < CONF_THRESHOLD) continue
            cands.add(floatArrayOf(rows[0][c], rows[1][c], rows[2][c], rows[3][c], score))
        }
        val kept = nms(cands, IOU_THRESHOLD)
        val sx = if (normalized) 1f else 1f / inputW
        val sy = if (normalized) 1f else 1f / inputH
        return kept.map { a ->
            val cx = a[0] * sx
            val cy = a[1] * sy
            val bw = a[2] * sx
            val bh = a[3] * sy
            PersonBox(
                cx = cx,
                by = cy + bh / 2,
                w = bw,
                h = bh,
                conf = a[4],
            )
        }
    }

    override fun close() {
        runCatching { interpreter?.close() }
        interpreter = null
        runCatching { delegate?.close() }
        delegate = null
        gpuActive = false
        loaded = false
        coordUnit = null
        inputBuf = null
        outputBuf = null
        // 模型缓冲是整读进 direct buffer 的（见 [loadModelBuffer]），这里只放开引用：
        // 文件句柄在加载时即已关闭，模型组目录可随时删/改名，不依赖任何解除映射的反射。
        cpuModel = null
        gpuModel = null
        cpuFallbackVerified = false
        terminalFailure = null
        loggedRunFailure = false
    }

    companion object {
        private const val TAG = "StanceDetector"
        const val CONF_THRESHOLD = 0.25f
        const val IOU_THRESHOLD = 0.45f

        /** 模型文件整读上限：fp32 YOLO11n 约 11MB，留足余量但挡住异常大文件。 */
        internal const val MAX_MODEL_BYTES = 64L * 1024 * 1024

        /** GPU 模式三态（r03）：auto=白名单裁决 / force=跳白名单+运行期验证 / off=纯 CPU。 */
        const val MODE_AUTO = "auto"
        const val MODE_FORCE = "force"
        const val MODE_OFF = "off"

        /**
         * 输入张量 shape → (是否 NCHW, [宽, 高])。
         * 本工程导出的 YOLO11n 是 [1,3,448,448]（NCHW）；也接受 [1,448,448,3]（NHWC）。
         * 纯函数，JVM 单测钉死（2026-11-07 修 NHWC 误写）。
         */
        internal fun inputLayoutOf(shape: IntArray): Pair<Boolean, IntArray> {
            require(shape.size == 4) { "检测模型输入应为 4 维：${shape.toList()}" }
            // Y67：批大小必须为 1，通道必须且只能是 NCHW 的 dim1 或 NHWC 的 dim3。
            require(shape[0] == 1) { "检测模型输入 batch 必须为 1：${shape.toList()}" }
            val nchw = shape[1] == 3
            val nhwc = shape[3] == 3
            require(nchw != nhwc) { "检测模型输入通道维歧义（需 NCHW 或 NHWC 三通道）：${shape.toList()}" }
            val w = if (nchw) shape[3] else shape[2]
            val h = if (nchw) shape[2] else shape[1]
            require(w > 0 && h > 0) { "检测模型输入尺寸非法：${shape.toList()}" }
            return nchw to intArrayOf(w, h)
        }

        /**
         * 把 RGBA 帧按目标尺寸最近邻缩放写进模型输入缓冲（NCHW 平面序 / NHWC 像素交错）。
         * 纯函数（Buffer 进、Buffer 出），JVM 单测小矩阵钉死通道顺序——**这次修复的回归锚**：
         * NCHW 必须先写完整个 R 平面再 G、B，写成交错就是把颜色当坐标喂模型。
         */
        internal fun fillInput(
            out: ByteBuffer, rgba: IntArray, w: Int, h: Int,
            inputW: Int, inputH: Int, nchw: Boolean, floatInput: Boolean,
        ) {
            out.clear()
            val sx = w.toFloat() / inputW
            val sy = h.toFloat() / inputH
            if (nchw) {
                for (channel in 0 until 3) {
                    val shift = 16 - channel * 8   // R=16 / G=8 / B=0
                    for (y in 0 until inputH) {
                        val srcY = (y * sy).toInt().coerceIn(0, h - 1)
                        val row = srcY * w
                        for (x in 0 until inputW) {
                            val v = (rgba[row + (x * sx).toInt().coerceIn(0, w - 1)] shr shift) and 0xFF
                            if (floatInput) out.putFloat(v / 255f) else out.put(v.toByte())
                        }
                    }
                }
            } else {
                for (y in 0 until inputH) {
                    val srcY = (y * sy).toInt().coerceIn(0, h - 1)
                    val row = srcY * w
                    for (x in 0 until inputW) {
                        val px = rgba[row + (x * sx).toInt().coerceIn(0, w - 1)]
                        val r = (px shr 16) and 0xFF
                        val g = (px shr 8) and 0xFF
                        val bl = px and 0xFF
                        if (floatInput) {
                            out.putFloat(r / 255f)
                            out.putFloat(g / 255f)
                            out.putFloat(bl / 255f)
                        } else {
                            out.put(r.toByte())
                            out.put(g.toByte())
                            out.put(bl.toByte())
                        }
                    }
                }
            }
            out.rewind()
        }

        /**
         * 模型文件 → 常驻 direct buffer（R16）。
         *
         * **不用 FileChannel.map**：MappedByteBuffer 会一直占着文件句柄——Windows JVM 上
         * 模型组目录的删/改名直接失败，导入回滚被误判成「原模型组待恢复」；Android 侧
         * 公开的 SharedMemory.unmap 只承诺回收自身 map 的缓冲，FileChannel.map 的缓冲
         * 没有公开契约（API 27 以下更无解除通道），不能拿隐藏实现当迁移保证。
         *
         * 改为一次性整读（仅加载期一次 ≤ [MAX_MODEL_BYTES] 的拷贝，读完立即关文件）：
         * 模型组可随时删/改名，推理热路径仍是零拷贝（[ByteBuffer.allocateDirect] 本就被
         * Interpreter 直接使用）。
         */
        internal fun loadModelBuffer(file: File): ByteBuffer {
            require(file.isFile) { "模型文件不存在：${file.name}" }
            val size = file.length()
            require(size in 1..MAX_MODEL_BYTES) { "模型文件大小非法（${file.name}）：$size" }
            val buf = ByteBuffer.allocateDirect(size.toInt())
            FileInputStream(file).use { input ->
                val ch = input.channel
                while (buf.hasRemaining()) {
                    if (ch.read(buf) < 0) break
                }
            }
            require(!buf.hasRemaining()) { "模型文件读取不完整：${file.name}" }
            buf.rewind()
            return buf
        }

        /** 贪心 NMS（IoU 0.45）：纯函数，JVM 单测钉死。 */
        fun nms(boxes: List<FloatArray>, iouThr: Float): List<FloatArray> {
            val sorted = boxes.sortedByDescending { it[4] }
            val kept = ArrayList<FloatArray>()
            for (b in sorted) {
                if (kept.none { iou(b, it) > iouThr }) kept.add(b)
            }
            return kept
        }

        /** a/b = [cx,cy,w,h,…]（输入尺度像素）。纯函数。 */
        fun iou(a: FloatArray, b: FloatArray): Float {
            val ax1 = a[0] - a[2] / 2
            val ay1 = a[1] - a[3] / 2
            val ax2 = a[0] + a[2] / 2
            val ay2 = a[1] + a[3] / 2
            val bx1 = b[0] - b[2] / 2
            val by1 = b[1] - b[3] / 2
            val bx2 = b[0] + b[2] / 2
            val by2 = b[1] + b[3] / 2
            val ix = maxOf(0f, minOf(ax2, bx2) - maxOf(ax1, bx1))
            val iy = maxOf(0f, minOf(ay2, by2) - maxOf(ay1, by1))
            val inter = ix * iy
            val union = a[2] * a[3] + b[2] * b[3] - inter
            return if (union <= 0f) 0f else inter / union
        }
    }
}
