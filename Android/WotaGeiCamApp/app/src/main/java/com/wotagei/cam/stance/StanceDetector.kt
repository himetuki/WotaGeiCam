package com.wotagei.cam.stance

import com.wotagei.cam.diag.Diag

import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

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
    private var loaded = false
    private var inputW = 448
    private var inputH = 448
    private var inputFloat = true
    private var inputNchw = false
    private var inputBuf: ByteBuffer? = null
    private var outputBuf: Array<Array<FloatArray>>? = null
    private var loggedRunFailure = false

    /** 当前加速层（UI 显示；含回退标记）。 */
    var backendName: String = ""
        private set

    fun isLoaded(): Boolean = loaded

    fun isGpuActive(): Boolean = gpuActive

    /**
     * 加载模型并建 Interpreter。preferGpu 且 gpuFile 存在 → 先试 GPU delegate，
     * 白名单不通过/初始化失败 → 自动回退 CPU int8（返回 true 但 backendName 带回退标记）。
     * 张量布局不合规格（4 维输入 / float32 输出）→ 关掉已建资源并返回 false。
     */
    fun load(int8File: File, gpuFile: File?, preferGpu: Boolean): Boolean {
        close()
        loaded = false
        gpuActive = false
        loggedRunFailure = false
        // CPU 路线无论如何先备好（GPU 失败即回退）
        val cpuModel = loadModelBuffer(int8File)
        val cpuOpts = Interpreter.Options().setNumThreads(4)
        if (preferGpu && gpuFile != null) {
            try {
                val compat = org.tensorflow.lite.gpu.CompatibilityList()
                if (compat.isDelegateSupportedOnThisDevice) {
                    val d = GpuDelegate(compat.bestOptionsForThisDevice)
                    val gpuModel = loadModelBuffer(gpuFile)
                    val gpuOpts = Interpreter.Options().addDelegate(d)
                    // load 阶段即建 interpreter 验证（白名单外的机型在构造期抛异常→回退）
                    interpreter = Interpreter(gpuModel, gpuOpts)
                    delegate = d
                    gpuActive = true
                    backendName = "GPU"
                } else {
                    Diag.w(TAG, "GPU delegate 兼容列表不通过，走 CPU")
                }
            } catch (e: Throwable) {
                Diag.w(TAG, "GPU delegate 初始化失败，回退 CPU：${e.message}")
                runCatching { interpreter?.close() }
                interpreter = null
                runCatching { delegate?.close() }
                delegate = null
            }
        }
        if (!gpuActive) {
            interpreter = Interpreter(cpuModel, cpuOpts)
            // 回退标记：fallbackTip 依赖 backendName.contains("回退")——GPU 开着却回退必须让用户看见
            backendName = if (preferGpu) "CPU（GPU 白名单外/初始化失败，已自动回退）" else "CPU"
        }
        return try {
            bindTensorSpec()
            loaded = true
            true
        } catch (e: Exception) {
            Diag.w(TAG, "模型张量规格不支持：${e.message}")
            close()
            false
        }
    }

    /** 从**模型实际张量**取输入布局/尺寸与输出 dtype（不再硬编码 448/NHWC）。 */
    private fun bindTensorSpec() {
        val itp = interpreter ?: throw IllegalStateException("interpreter 未就绪")
        val inTensor = itp.getInputTensor(0)
        val (nchw, wh) = inputLayoutOf(inTensor.shape())
        val dtype = inTensor.dataType()
        require(dtype == DataType.FLOAT32 || dtype == DataType.UINT8 || dtype == DataType.INT8) {
            "输入 dtype 不支持：$dtype"
        }
        val outTensor = itp.getOutputTensor(0)
        require(outTensor.dataType() == DataType.FLOAT32) {
            "输出 dtype 必须 float32（当前 ${outTensor.dataType()}）；量化输出模型请重新导出为 float 输出"
        }
        require(outTensor.shape().size == 3) { "输出应为 [1,84,N]：${outTensor.shape().toList()}" }
        require(outTensor.shape()[1] >= 5) { "输出行数不足 5（缺 person 类分数行）：${outTensor.shape().toList()}" }
        inputNchw = nchw
        inputW = wh[0]
        inputH = wh[1]
        inputFloat = dtype == DataType.FLOAT32
        inputBuf = null
        outputBuf = null
        Diag.i(TAG, "模型张量：in=${inTensor.shape().toList()} $dtype layout=${if (nchw) "NCHW" else "NHWC"}" +
            " out=${outTensor.shape().toList()} ${outTensor.dataType()}")
    }

    override fun detect(rgba: IntArray, w: Int, h: Int, tsMs: Long): List<PersonBox> {
        val itp = interpreter ?: return emptyList()
        return try {
            val input = makeInput(rgba, w, h)
            val outShape = itp.getOutputTensor(0).shape()   // [1,84,4116]
            val nRows = outShape[1]
            val nCols = outShape[2]
            val output = outputBuf?.takeIf { it.size == nRows && it[0].size == nCols }
                ?: Array(1) { Array(nRows) { FloatArray(nCols) } }.also { outputBuf = it }
            itp.run(input, output)
            decode(output[0], nRows, nCols, w, h)
        } catch (e: Exception) {
            if (!loggedRunFailure) {
                loggedRunFailure = true
                Diag.w(TAG, "推理失败（后续同类错误不再重复打）：${e.message}")
            }
            emptyList()
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
     * 输出 rows[nRows][nCols] → conf 过滤 → NMS → 归一化 PersonBox（帧占比）。
     *
     * 坐标尺度**按输出实际值域自适应**（真机实测教训）：litert 导出 wrapper 的输出
     * **已归一化 0~1**（cx/cy/w/h 直接就是占比），而按 ultralytics 常规应是输入尺度
     * 像素（0~448）——旧实现按像素 ÷inputW 把 0~1 坐标又缩小 448 倍 = 框缩成一个点
     * =「根本没识别」（真机首测实锤）。解码前扫坐标 4 行的最大值：>2 视为像素尺度
     * （÷inputW），≤2 视为已归一化（直用）。
     */
    private fun decode(rows: Array<FloatArray>, nRows: Int, nCols: Int, w: Int, h: Int): List<PersonBox> {
        var coordMax = 0f
        for (r in 0 until 4) for (c in 0 until nCols step 16) coordMax = maxOf(coordMax, rows[r][c])
        val normalized = coordMax <= 2f
        val cands = ArrayList<FloatArray>(16)
        for (c in 0 until nCols) {
            val score = rows[4][c]     // rows[4] = 类 0（person）分数行
            if (score < CONF_THRESHOLD) continue
            cands.add(floatArrayOf(rows[0][c], rows[1][c], rows[2][c], rows[3][c], score))
        }
        val kept = nms(cands, IOU_THRESHOLD)
        val sx = if (normalized) 1f else w.toFloat() / inputW
        val sy = if (normalized) 1f else h.toFloat() / inputH
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
        inputBuf = null
        outputBuf = null
        loggedRunFailure = false
    }

    companion object {
        private const val TAG = "StanceDetector"
        const val CONF_THRESHOLD = 0.25f
        const val IOU_THRESHOLD = 0.45f

        /**
         * 输入张量 shape → (是否 NCHW, [宽, 高])。
         * 本工程导出的 YOLO11n 是 [1,3,448,448]（NCHW）；也接受 [1,448,448,3]（NHWC）。
         * 纯函数，JVM 单测钉死（2026-11-07 修 NHWC 误写）。
         */
        internal fun inputLayoutOf(shape: IntArray): Pair<Boolean, IntArray> {
            require(shape.size == 4) { "检测模型输入应为 4 维：${shape.toList()}" }
            val nchw = shape[1] == 3
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

        private fun loadModelBuffer(file: File): ByteBuffer {
            val ch = RandomAccessFile(file, "r").channel
            val buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
            ch.close()
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
