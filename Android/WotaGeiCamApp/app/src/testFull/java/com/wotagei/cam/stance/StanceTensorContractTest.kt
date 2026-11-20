package com.wotagei.cam.stance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * R16/Y67：装配期张量契约。随包资产用**真实文件**核对（名字 → 精确 I/O 规格），
 * 合成夹具只用来钉死拒绝路径；不构造 Interpreter。
 */
class StanceTensorContractTest {
    @get:Rule
    val temp = TemporaryFolder(File("build/test-tmp").apply { mkdirs() })

    private fun file(name: String, bytes: ByteArray): File =
        // 同一测试会为同一 register 名写多份夹具（量化输入/输出），每次用独立目录避免重名。
        temp.newFolder().let { File(it, name).also { f -> f.writeBytes(bytes) } }

    @Test
    fun shippedAssetsMatchExplicitContracts() {
        for (name in listOf("yolo11n_int8.tflite", "yolo11n_fp32.tflite")) {
            val contract = StanceTensorContract.validate(name, File("src/full/assets/model_pack", name))
            assertEquals(1, contract.inputs.size)
            assertEquals(1, contract.outputs.size)
            with(contract.inputs.single()) {
                assertEquals("FLOAT32", type)
                assertEquals(listOf(1, 3, 448, 448), shape)
            }
            with(contract.outputs.single()) {
                assertEquals("FLOAT32", type)
                assertEquals(listOf(1, 84, 4116), shape)
            }
        }
    }

    @Test
    fun syntheticValidContractIsAccepted() {
        val contract = StanceTensorContract.validate(
            "yolo11n_int8.tflite",
            file("yolo11n_int8.tflite", StanceTestFixtures.tflite()),
        )
        assertEquals(listOf(1, 3, 448, 448), contract.inputs.single().shape)
        assertEquals(listOf(1, 84, 4116), contract.outputs.single().shape)
        // fp16 资产只要求落在检测器支持域内（I/O 仍是 float32，内部半精度无所谓）。
        assertNotNull(
            StanceTensorContract.validate(
                "yolo11n_fp16.tflite",
                file("yolo11n_fp16.tflite", StanceTestFixtures.tflite(inputShape = intArrayOf(1, 640, 640, 3))),
            )
        )
    }

    @Test
    fun shippedShapeMismatchIsRejectedEvenWithValidFlatBuffer() {
        // SHA/结构都合法，但张量规格不是这个文件名承诺的资产 → 必须拒绝。
        assertThrows(IllegalArgumentException::class.java) {
            StanceTensorContract.validate(
                "yolo11n_int8.tflite",
                file("yolo11n_int8.tflite", StanceTestFixtures.tflite(inputShape = intArrayOf(1, 3, 320, 320))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StanceTensorContract.validate(
                "yolo11n_fp32.tflite",
                file("yolo11n_fp32.tflite", StanceTestFixtures.tflite(outputShape = intArrayOf(1, 84, 1000))),
            )
        }
    }

    @Test
    fun quantizedIoIsRejectedAtAssemblyTime() {
        // 检测器没有按 scale/zeroPoint 反量化：UINT8 输入/输出都不能静默放行。
        assertThrows(IllegalArgumentException::class.java) {
            StanceTensorContract.validate(
                "yolo11n_fp16.tflite",
                file("yolo11n_fp16.tflite", StanceTestFixtures.tflite(inputType = 3)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StanceTensorContract.validate(
                "yolo11n_fp16.tflite",
                file("yolo11n_fp16.tflite", StanceTestFixtures.tflite(outputType = 3)),
            )
        }
    }

    @Test
    fun malformedFlatBuffersAndUnknownNamesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            StanceTensorContract.validate(
                "yolo11n_int8.tflite",
                file("yolo11n_int8.tflite", "not-a-tflite-file".toByteArray()),
            )
        }
        val valid = StanceTestFixtures.tflite()
        assertThrows(IllegalArgumentException::class.java) {
            StanceTensorContract.validate("other.tflite", file("other.tflite", valid))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StanceTensorContract.validate(
                "yolo11n_int8.tflite",
                file("truncated.tflite", valid.copyOf(12)),
            )
        }
    }

    @Test
    fun unsupportedBatchAndAmbiguousChannelsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            StanceTensorContract.validate(
                "yolo11n_fp16.tflite",
                file("batch2.tflite", StanceTestFixtures.tflite(inputShape = intArrayOf(2, 3, 448, 448))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StanceTensorContract.validate(
                "yolo11n_fp16.tflite",
                file("ambig.tflite", StanceTestFixtures.tflite(inputShape = intArrayOf(1, 3, 448, 3))),
            )
        }
    }
}