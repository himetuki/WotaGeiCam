package com.wotagei.cam.stance

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 检测输入布局单测（2026-11-07）：
 * 随包 yolo11n_*.tflite 经 flatbuffer 实读为 **NCHW [1,3,448,448]**，
 * 而旧实现按 NHWC 像素交错写——推理输出全错（模型几何成立但检测结果无意义）。
 * 本文件钉死"通道平面顺序"与"从张量 shape 推导布局"，防再写反。
 * testFull 源集（docs/plan/33：StanceDetector 在 src/full，lite 变体无此类）。
 */
class StanceInputLayoutTest {

    @Test
    fun layoutOf_nchwFromShippedShape() {
        val (nchw, wh) = StanceDetector.inputLayoutOf(intArrayOf(1, 3, 448, 448))
        assertTrue("shape[1]==3 判定为 NCHW", nchw)
        assertArrayEquals(intArrayOf(448, 448), wh)
    }

    @Test
    fun layoutOf_nhwcStillAccepted() {
        val (nchw, wh) = StanceDetector.inputLayoutOf(intArrayOf(1, 320, 256, 3))
        assertFalse(nchw)
        assertArrayEquals(intArrayOf(256, 320), wh)
    }

    @Test
    fun layoutOf_rejectsBadRank() {
        try {
            StanceDetector.inputLayoutOf(intArrayOf(1, 448, 448))
            fail("3 维输入应抛")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("4 维"))
        }
    }

    @Test
    fun fillInput_nchw_writesChannelPlanesNotInterleaved() {
        // 2x2 RGBA：左上红 / 右上绿 / 左下蓝 / 右下白
        val rgba = intArrayOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(),
            0xFF0000FF.toInt(), 0xFFFFFFFF.toInt(),
        )
        val buf = ByteBuffer.allocateDirect(2 * 2 * 3 * 4).order(ByteOrder.nativeOrder())
        StanceDetector.fillInput(buf, rgba, 2, 2, 2, 2, nchw = true, floatInput = true)
        val actual = FloatArray(12).also { buf.asFloatBuffer().get(it) }
        // 先 R 平面 [1,0,0,1]，再 G [0,1,0,1]，再 B [0,0,1,1]
        val expected = floatArrayOf(
            1f, 0f, 0f, 1f,
            0f, 1f, 0f, 1f,
            0f, 0f, 1f, 1f,
        )
        assertArrayEquals(expected, actual, 1e-6f)
        // 反证：交错布局的第 3 个值会是「绿像素的 B」=0；平面布局这里是「白像素的 R」=1
        assertEquals(1f, actual[3], 1e-6f)
    }

    @Test
    fun fillInput_nhwc_writesPixelInterleavedForUint8() {
        val rgba = intArrayOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(),
            0xFF0000FF.toInt(), 0xFFFFFFFF.toInt(),
        )
        val buf = ByteBuffer.allocateDirect(2 * 2 * 3)
        StanceDetector.fillInput(buf, rgba, 2, 2, 2, 2, nchw = false, floatInput = false)
        val actual = ByteArray(12).also { buf.get(it) }
        val expected = byteArrayOf(
            0xFF.toByte(), 0x00, 0x00, 0x00, 0xFF.toByte(), 0x00,
            0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        assertArrayEquals(expected, actual)
    }

    @Test
    fun fillInput_resizesNearestNeighbour() {
        // 4x4 每行同色（R 通道 = 所在列 ×40）→ 2x2 采样列 0/2
        val rgba = IntArray(16) { i ->
            val col = i % 4
            (0xFF shl 24) or ((col * 40) shl 16)
        }
        val buf = ByteBuffer.allocateDirect(2 * 2 * 3 * 4).order(ByteOrder.nativeOrder())
        StanceDetector.fillInput(buf, rgba, 4, 4, 2, 2, nchw = true, floatInput = true)
        val actual = FloatArray(12).also { buf.asFloatBuffer().get(it) }
        // R 平面（前 4 个）= 采到列 0 与列 2：0, 80/255, 0, 80/255
        assertEquals(0f, actual[0], 1e-6f)
        assertEquals(80f / 255f, actual[1], 1e-6f)
        assertEquals(0f, actual[2], 1e-6f)
        assertEquals(80f / 255f, actual[3], 1e-6f)
    }
}
