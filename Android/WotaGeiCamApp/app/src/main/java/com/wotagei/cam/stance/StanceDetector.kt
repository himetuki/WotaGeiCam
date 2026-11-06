package com.wotagei.cam.stance

import android.content.Context
import android.util.Log
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
 * 输入 [1,448,448,3]：FLOAT32 → RGBA 像素 /255（RGB 序）；UINT8 → 直接 0~255。
 * 输出 [1,84,4116]：每列 anchor = [cx,cy,w,h, cls0..79]（像素，输入尺度），
 * person=类 0；解码 → conf 过滤 → NMS(IoU 0.45) → 归一化 [PersonBox]（帧占比，
 * 与 PC 端 ultralytics 归一化同义——端侧直接 resize 无 letterbox，纵横比小幅拉伸可接受，M2 真机验证）。
 * 线程假设：detect 由 [StanceAnalysis] 单线程串行调用。
 */
class StanceDetector : StanceAnalysis.FrameDetector, Closeable {
    private var interpreter: Interpreter? = null
    private var delegate: GpuDelegate? = null
    private var gpuActive = false
    private var loaded = false
    private var inputW = 448
    private var inputH = 448
    private var inputFloat = true

    /** 当前加速层（UI 显示；含回退标记）。 */
    var backendName: String = ""
        private set

    fun isLoaded(): Boolean = loaded

    fun isGpuActive(): Boolean = gpuActive

    /**
     * 加载模型并建 Interpreter。preferGpu 且 gpuFile 存在 → 先试 GPU delegate，
     * 白名单不通过/初始化失败 → 自动回退 CPU int8（返回 true 但 backendName 带回退标记）。
     */
    fun load(int8File: File, gpuFile: File?, preferGpu: Boolean): Boolean {
        close()
        loaded = false
        gpuActive = false
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
                    Log.w(TAG, "GPU delegate 兼容列表不通过，走 CPU")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "GPU delegate 初始化失败，回退 CPU：${e.message}")
                runCatching { interpreter?.close() }
                interpreter = null
                runCatching { delegate?.close() }
                delegate = null
            }
        }
        if (!gpuActive) {
            interpreter = Interpreter(cpuModel, cpuOpts)
            backendName = "CPU"
        }
        loaded = true
        return true
    }

    override fun detect(rgba: IntArray, w: Int, h: Int, tsMs: Long): List<PersonBox> {
        val itp = interpreter ?: return emptyList()
        return try {
            val input = makeInput(rgba, w, h)
            val outShape = itp.getOutputTensor(0).shape()   // [1,84,4116]
            val nRows = outShape[1]
            val nCols = outShape[2]
            val output = Array(1) { Array(nRows) { FloatArray(nCols) } }
            itp.run(input, output)
            decode(output[0], nRows, nCols, w, h)
        } catch (e: Exception) {
            Log.w(TAG, "推理失败：${e.message}")
            emptyList()
        }
    }

    private fun makeInput(rgba: IntArray, w: Int, h: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(inputW * inputH * 3 * (if (inputFloat) 4 else 1))
            .order(ByteOrder.nativeOrder())
        val sx = w.toFloat() / inputW
        val sy = h.toFloat() / inputH
        for (y in 0 until inputH) {
            val srcY = (y * sy).toInt().coerceIn(0, h - 1)
            for (x in 0 until inputW) {
                val px = rgba[srcY * w + (x * sx).toInt().coerceIn(0, w - 1)]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val bl = px and 0xFF
                if (inputFloat) {
                    buf.putFloat(r / 255f)
                    buf.putFloat(g / 255f)
                    buf.putFloat(bl / 255f)
                } else {
                    buf.put(r.toByte())
                    buf.put(g.toByte())
                    buf.put(bl.toByte())
                }
            }
        }
        buf.rewind()
        return buf
    }

    /** 输出 rows[nRows][nCols] → conf 过滤 → NMS → 归一化 PersonBox（帧占比）。 */
    private fun decode(rows: Array<FloatArray>, nRows: Int, nCols: Int, w: Int, h: Int): List<PersonBox> {
        val cands = ArrayList<FloatArray>(16)
        for (c in 0 until nCols) {
            val score = rows[4][c]     // rows[4] = 类 0（person）分数行
            if (score < CONF_THRESHOLD) continue
            cands.add(floatArrayOf(rows[0][c], rows[1][c], rows[2][c], rows[3][c], score))
        }
        val kept = nms(cands, IOU_THRESHOLD)
        val sx = w.toFloat() / inputW
        val sy = h.toFloat() / inputH
        return kept.map { a ->
            val cx = a[0] * sx
            val cy = a[1] * sy
            val bw = a[2] * sx
            val bh = a[3] * sy
            PersonBox(
                cx = cx / w,
                by = (cy + bh / 2) / h,
                w = bw / w,
                h = bh / h,
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
    }

    companion object {
        private const val TAG = "StanceDetector"
        const val CONF_THRESHOLD = 0.25f
        const val IOU_THRESHOLD = 0.45f

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
