package com.wotagei.cam.stance

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * tflite 张量契约（R16/Y67）：导入/装配前只读 FlatBuffer 元数据，核对模型是否真的
 * 符合站位检测器支持的输入输出域。
 *
 * 随包两个资产（已用真实文件核对）：
 * - yolo11n_int8.tflite：输入 FLOAT32 [1,3,448,448]（NCHW），输出 FLOAT32 [1,84,4116]；
 * - yolo11n_fp32.tflite：输入 FLOAT32 [1,3,448,448]（NCHW），输出 FLOAT32 [1,84,4116]。
 * int8 模型只是**内部**量化，I/O 仍是 float32（文件名不代表 I/O dtype）。
 *
 * 通用导入契约（检测器真实能力边界）：
 * - 恰好 1 个输入、1 个输出；batch=1；输入 FLOAT32；通道维=3（NCHW 或 NHWC）；
 * - 输出 FLOAT32、3 维，行数 ≥5（rows[4] 是 person 分数行）；
 * - 单张量元素数不超过 [MAX_TENSOR_ELEMENTS]，防异常模型撑爆缓冲。
 * 暂不支持量化输入（UINT8/INT8/FLOAT16）——检测器没有按 scale/zeroPoint 反量化，
 * 与其运行期静默误判，不如装配期明确拒绝（Y67 能力协商）。
 *
 * 只读元数据 ≠ 可加载性：Interpreter 构造仍由 [StanceDetector.load] 真实验证；
 * 本契约负责挡住「SHA 全对但张量域完全不对」的包（R16）。
 */
internal object StanceTensorContract {
    const val TFL3_MAGIC_OFFSET = 4
    const val MAX_TENSOR_ELEMENTS = 64L * 1024 * 1024
    private const val MAX_VECTOR_ITEMS = 1_000_000
    private const val MAX_TENSORS = 100_000

    data class TensorSpec(val name: String, val type: String, val shape: List<Int>)
    data class Contract(val inputs: List<TensorSpec>, val outputs: List<TensorSpec>) {
        fun summary(): String =
            "in=${inputs.joinToString { "${it.type}${it.shape}" }} " +
                "out=${outputs.joinToString { "${it.type}${it.shape}" }}"
    }

    private data class Expected(
        val inputShape: List<Int>?,
        val outputShape: List<Int>?,
    )

    /** 随包资产的显式契约（文件名 → 精确张量规格）。 */
    private val SHIPPED = mapOf(
        "yolo11n_int8.tflite" to Expected(
            inputShape = listOf(1, 3, 448, 448),
            outputShape = listOf(1, 84, 4116),
        ),
        "yolo11n_fp32.tflite" to Expected(
            inputShape = listOf(1, 3, 448, 448),
            outputShape = listOf(1, 84, 4116),
        ),
        // 训练工具可能产出 fp16 变体；只接受与检测器兼容的 float32 I/O（内部 fp16 无所谓）。
        "yolo11n_fp16.tflite" to Expected(inputShape = null, outputShape = null),
    )

    /** 文件名是否属于站位模型组支持的 tflite 资产。 */
    fun isSupportedAsset(name: String): Boolean = name in SHIPPED

    /**
     * 验一个 tflite 文件的张量契约。失败抛 IllegalArgumentException（含具体原因），
     * 供 [StanceModelPack.validateDir] 把包判为无效。
     */
    fun validate(name: String, file: File): Contract {
        val expected = SHIPPED[name] ?: throw IllegalArgumentException("未登记的模型文件：$name")
        require(file.isFile && file.length() > 8) { "$name 不是有效 tflite（文件为空/过短）" }
        val bytes = file.readBytes()
        val actual = read(bytes, name)
        require(actual.inputs.size == 1) { "$name 必须恰有 1 个输入，实际 ${actual.inputs.size}" }
        require(actual.outputs.size == 1) { "$name 必须恰有 1 个输出，实际 ${actual.outputs.size}" }
        val input = actual.inputs.single()
        val output = actual.outputs.single()

        require(input.type == "FLOAT32") {
            "$name 输入 dtype=${input.type}，暂不支持量化/半精度输入（需 float32 三通道）"
        }
        require(input.shape.size == 4 && input.shape[0] == 1) {
            "$name 输入必须为 4 维 batch=1：${input.shape}"
        }
        // 与 StanceDetector.inputLayoutOf 同口径：dim1/dim3 都是 3 时无法判定 NCHW/NHWC。
        val nchw = input.shape[1] == 3
        val nhwc = input.shape[3] == 3
        require(nchw != nhwc) {
            "$name 输入通道必须为 3 且布局唯一（NCHW/NHWC）：${input.shape}"
        }
        require(input.shape.all { it > 0 }) { "$name 输入含非正维度：${input.shape}" }
        require(input.shape[2].toLong() * input.shape[3].toLong() <= MAX_TENSOR_ELEMENTS) {
            "$name 输入尺寸过大：${input.shape}"
        }

        require(output.type == "FLOAT32") {
            "$name 输出 dtype=${output.type}，检测器只接收 float32 输出"
        }
        require(output.shape.size == 3 && output.shape[0] == 1) {
            "$name 输出必须为 [1,行,列]：${output.shape}"
        }
        require(output.shape[1] >= 5) {
            "$name 输出行数不足 5（rows[4] 为 person 分数行）：${output.shape}"
        }
        require(output.shape[2] > 0) { "$name 输出列数非正：${output.shape}" }
        require(
            output.shape[1].toLong() * output.shape[2].toLong() <= MAX_TENSOR_ELEMENTS,
        ) { "$name 输出尺寸过大：${output.shape}" }

        // 随包资产按名字钉死精确规格；通用包只要求落在检测器支持域内。
        expected.inputShape?.let {
            require(input.shape == it) { "$name 输入张量应为 $it，实际 ${input.shape}" }
        }
        expected.outputShape?.let {
            require(output.shape == it) { "$name 输出张量应为 $it，实际 ${output.shape}" }
        }
        return actual
    }

    /** 只读 FlatBuffer 元数据；不构造 Interpreter，不加载权重。 */
    private fun read(bytes: ByteArray, name: String): Contract {
        require(bytes.size >= 8) { "$name 过短，不是 tflite" }
        require(
            bytes[TFL3_MAGIC_OFFSET] == 'T'.code.toByte() &&
                bytes[TFL3_MAGIC_OFFSET + 1] == 'F'.code.toByte() &&
                bytes[TFL3_MAGIC_OFFSET + 2] == 'L'.code.toByte() &&
                bytes[TFL3_MAGIC_OFFSET + 3] == '3'.code.toByte(),
        ) { "$name 缺 TFL3 标识，不是标准 tflite" }
        val fb = Flat(bytes, name)
        val model = fb.root()
        val (sgStart, sgCount) = fb.vector(model, 2) ?: throw IllegalArgumentException("$name 缺 subgraphs")
        require(sgCount == 1) { "$name 必须只有 1 个 subgraph，实际 $sgCount" }
        val sg = fb.deref(sgStart)
        val (tStart, tCount) = fb.vector(sg, 0) ?: throw IllegalArgumentException("$name 缺 tensors")
        require(tCount in 1..MAX_TENSORS) { "$name 张量数量异常：$tCount" }
        val inputs = fb.intVector(sg, 1)
        val outputs = fb.intVector(sg, 2)
        require(inputs.isNotEmpty()) { "$name 缺输入张量" }
        require(outputs.isNotEmpty()) { "$name 缺输出张量" }
        val tensorSpecs = HashMap<Int, TensorSpec>(tCount)
        fun spec(idx: Int): TensorSpec {
            require(idx in 0 until tCount) { "$name 引用了不存在的张量 $idx/$tCount" }
            tensorSpecs[idx]?.let { return it }
            val tp = tStart + 4 * idx
            val table = fb.deref(tp)
            val shape = fb.intVector(table, 0)
            val type = fb.typeName(table)
            val tensorName = fb.string(table, 3)
            var count = 1L
            for (d in shape) {
                require(d > 0) { "$name 张量 $idx 含非正维度：$shape" }
                count *= d
                require(count <= MAX_TENSOR_ELEMENTS) { "$name 张量 $idx 元素数过大：$shape" }
            }
            return TensorSpec(tensorName, type, shape).also { tensorSpecs[idx] = it }
        }
        return Contract(inputs.map(::spec), outputs.map(::spec))
    }

    /** FlatBuffers 最小只读访问器：所有偏移都做边界检查。 */
    private class Flat(private val b: ByteArray, private val name: String) {
        private val bb: ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        private val limit = b.size

        private fun need(pos: Long, len: Long) {
            require(pos >= 0 && len >= 0 && pos + len <= limit) { "$name 元数据越界（$pos+$len/$limit）" }
        }

        private fun u8(pos: Int): Int {
            need(pos.toLong(), 1)
            return b[pos].toInt() and 0xFF
        }

        private fun u16(pos: Int): Int {
            need(pos.toLong(), 2)
            return bb.getShort(pos).toInt() and 0xFFFF
        }

        private fun i32(pos: Int): Int {
            need(pos.toLong(), 4)
            return bb.getInt(pos)
        }

        private fun u32(pos: Int): Long {
            need(pos.toLong(), 4)
            return i32(pos).toLong() and 0xFFFF_FFFFL
        }

        fun root(): Int = deref(0)

        fun deref(uoffPos: Int): Int {
            val off = u32(uoffPos)
            val target = uoffPos + off
            need(target, 4)
            require(target <= Int.MAX_VALUE) { "$name 元数据偏移溢出" }
            return target.toInt()
        }

        /** 返回 (vector 数据起点, 元素数)；字段不存在返回 null。[elemSize] 区分 int 向量与字符串字节向量。 */
        fun vector(table: Int, idx: Int, elemSize: Int = 4): Pair<Int, Int>? {
            val p = field(table, idx) ?: return null
            val start = deref(p)
            val n = i32(start)
            require(n in 0..MAX_VECTOR_ITEMS) { "$name 向量长度异常：$n" }
            val data = start + 4L
            need(data, elemSize.toLong() * n)
            return data.toInt() to n
        }

        fun intVector(table: Int, idx: Int): List<Int> {
            val (start, n) = vector(table, idx) ?: return emptyList()
            return List(n) { i32(start + 4 * it) }
        }

        fun string(table: Int, idx: Int): String {
            // FlatBuffer string 的 vector 元素是单字节，不能按 int 向量的 4 字节步长做越界检查。
            val (start, n) = vector(table, idx, elemSize = 1) ?: return ""
            return String(b, start, n, Charsets.UTF_8)
        }

        fun typeName(tensor: Int): String {
            val type = field(tensor, 1)?.let { u8(it) } ?: 0
            return when (type) {
                0 -> "FLOAT32"
                1 -> "FLOAT16"
                2 -> "INT32"
                3 -> "UINT8"
                4 -> "INT64"
                5 -> "STRING"
                6 -> "BOOL"
                7 -> "INT16"
                8 -> "COMPLEX64"
                9 -> "INT8"
                10 -> "FLOAT64"
                else -> "TYPE_$type"
            }
        }

        /** FlatBuffer table 字段：vtable 在 table 前方，字段偏移相对 table 起点。 */
        private fun field(table: Int, idx: Int): Int? {
            need(table.toLong(), 4)
            val vtable = table.toLong() - i32(table)
            require(vtable >= 0 && vtable + 4 <= limit) { "$name vtable 越界" }
            val vt = vtable.toInt()
            val vtSize = u16(vt)
            require(vtSize >= 4) { "$name vtable 非法" }
            if (4 + idx * 2 + 2 > vtSize) return null
            val fieldPos = vt + 4 + idx * 2
            need(fieldPos.toLong(), 2)
            val off = u16(fieldPos)
            return if (off == 0) null else {
                val p = table.toLong() + off
                need(p, 1)
                p.toInt()
            }
        }
    }
}