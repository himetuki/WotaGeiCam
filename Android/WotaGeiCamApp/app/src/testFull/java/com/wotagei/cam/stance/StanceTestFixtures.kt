package com.wotagei.cam.stance

/**
 * 纯 JVM 测试夹具：手工构造最小但**结构合法**的 tflite FlatBuffer（TFL3 + 1 subgraph +
 * 1 输入/1 输出 tensor），供模型组导入/安全测试使用。
 *
 * 为什么不是随便写一行假字节：R16/Y67 之后装配期会真读张量元数据，SHA 全对的“假模型”
 * 也必须过张量契约；用真实文件又会让单测背上十几 MB 资产。这里只写元数据（无权重），
 * 只服务 [StanceModelPack.validateDir]/[StanceTensorContract]，不交给 Interpreter。
 */
internal object StanceTestFixtures {
    fun tflite(
        inputShape: IntArray = intArrayOf(1, 3, 448, 448),
        outputShape: IntArray = intArrayOf(1, 84, 4116),
        inputName: String = "input",
        outputName: String = "output",
        inputType: Int? = null,
        outputType: Int? = null,
    ): ByteArray {
        // 固定前缀布局（所有偏移都相对各自字段位置，和 FlatBuffer 的 uoffset 语义一致）：
        // 0 root | 4 TFL3 | 8 Model.vt | 18 Model | 26 subgraphs vec | 34 SubGraph.vt |
        // 44 SubGraph | 60 tensors vec | 72 inputs vec | 80 outputs vec | 88 TensorA.vt | ...
        val modelVt = 8
        val model = 18
        val subgraphsVector = 26
        val subgraphVt = 34
        val subgraph = 44
        val tensorsVector = 60
        val inputsVector = 72
        val outputsVector = 80
        val aVt = 88
        val aTable = 100
        val aTableSize = if (inputType != null) 16 else 12
        val aShape = aTable + aTableSize
        val aName = aShape + 4 + 4 * inputShape.size
        val bVt = align4(aName + 4 + inputName.length)
        val bTable = bVt + 12
        val bTableSize = if (outputType != null) 16 else 12
        val bShape = bTable + bTableSize
        val bName = bShape + 4 + 4 * outputShape.size
        val total = align4(bName + 4 + outputName.length)
        val b = ByteArray(total)

        fun i32(pos: Int, value: Int) {
            b[pos] = value.toByte()
            b[pos + 1] = (value ushr 8).toByte()
            b[pos + 2] = (value ushr 16).toByte()
            b[pos + 3] = (value ushr 24).toByte()
        }

        fun u16(pos: Int, value: Int) {
            b[pos] = value.toByte()
            b[pos + 1] = (value ushr 8).toByte()
        }

        fun uoffset(fieldPos: Int, target: Int) = i32(fieldPos, target - fieldPos)

        fun vtable(vt: Int, table: Int, tableSize: Int, fields: IntArray) {
            u16(vt, 4 + fields.size * 2)
            u16(vt + 2, tableSize)
            fields.forEachIndexed { i, off -> u16(vt + 4 + i * 2, off) }
            i32(table, table - vt)   // soffset 指向 vtable
        }

        fun intVector(pos: Int, values: IntArray) {
            i32(pos, values.size)
            values.forEachIndexed { i, v -> i32(pos + 4 + i * 4, v) }
        }

        fun string(pos: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            i32(pos, bytes.size)
            bytes.copyInto(b, pos + 4)
        }

        i32(0, model)
        "TFL3".toByteArray(Charsets.US_ASCII).copyInto(b, 4)

        // Model: version=default, operator_codes 空, subgraphs=[subgraph]
        vtable(modelVt, model, tableSize = 8, fields = intArrayOf(0, 0, 4))
        uoffset(model + 4, subgraphsVector)
        i32(subgraphsVector, 1)
        uoffset(subgraphsVector + 4, subgraph)

        // SubGraph: tensors / inputs / outputs
        vtable(subgraphVt, subgraph, tableSize = 16, fields = intArrayOf(4, 8, 12))
        uoffset(subgraph + 4, tensorsVector)
        uoffset(subgraph + 8, inputsVector)
        uoffset(subgraph + 12, outputsVector)
        i32(tensorsVector, 2)
        uoffset(tensorsVector + 4, aTable)
        uoffset(tensorsVector + 8, bTable)
        intVector(inputsVector, intArrayOf(0))
        intVector(outputsVector, intArrayOf(1))

        // Tensor A/B：shape=field0, type=field1（省略 → FLOAT32）, buffer=field2（省略）, name=field3
        val aField3 = if (inputType != null) 12 else 8
        vtable(aVt, aTable, tableSize = aTableSize, fields = intArrayOf(4, if (inputType != null) 8 else 0, 0, aField3))
        uoffset(aTable + 4, aShape)
        uoffset(aTable + aField3, aName)
        inputType?.let { b[aTable + 8] = it.toByte() }
        intVector(aShape, inputShape)
        string(aName, inputName)

        val bField3 = if (outputType != null) 12 else 8
        vtable(bVt, bTable, tableSize = bTableSize, fields = intArrayOf(4, if (outputType != null) 8 else 0, 0, bField3))
        uoffset(bTable + 4, bShape)
        uoffset(bTable + bField3, bName)
        outputType?.let { b[bTable + 8] = it.toByte() }
        intVector(bShape, outputShape)
        string(bName, outputName)
        return b
    }

    private fun align4(value: Int): Int = (value + 3) and 3.inv()
}

/** 一份可直接喂给 [StanceModelPack.importStream] 的 zip 载荷（tflite 为结构合法元数据）。 */
internal fun stanceTestPayload(
    gpu: String = "yolo11n_fp32.tflite",
    margins: String = """{"bands":{"left":[0.1,0.4],"right":[0.1,0.4],"bottom":[0.0,0.2]}}""",
    head: String = """{"coef":[0,0,0,0,5],"intercept":-3}""",
    model: ByteArray = StanceTestFixtures.tflite(),
    gpuModel: ByteArray = model,
): LinkedHashMap<String, ByteArray> {
    val files = linkedMapOf(
        "head.json" to head.toByteArray(),
        "margins.json" to margins.toByteArray(),
        "yolo11n_int8.tflite" to model,
        gpu to gpuModel,
    )
    val hashes = files.entries.joinToString(",") { (name, bytes) ->
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        "\"$name\":\"$sha\""
    }
    files["pack.meta.json"] =
        """{"schema":1,"features":["cx","by","w","h","conf"],"files":{$hashes}}""".toByteArray()
    return files
}