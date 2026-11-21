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

    // ------------------------------------------------------------------
    // Y66：输出坐标归一到输入张量尺度（不是分析帧像素）
    // ------------------------------------------------------------------

    private fun outputRows(cx: Float, cy: Float, w: Float, h: Float, score: Float): Array<FloatArray> =
        Array(5) { FloatArray(2) }.also { rows ->
            // 两列都写同样坐标：第一列分数低被过滤，但坐标仍参与尺度探测（真实输出亦然）。
            for (c in 0..1) {
                rows[0][c] = cx
                rows[1][c] = cy
                rows[2][c] = w
                rows[3][c] = h
            }
            rows[4][1] = score
            rows[4][0] = 0.01f   // 低于阈值，必须被过滤
        }

    @Test
    fun decode_pixelScaleUsesModelInputNotFramePixels() {
        // 模型输出是 448×448 输入尺度像素；旧实现乘 w/inputW 留在帧像素域，
        // 消费者再按占比解释 → 框被放大到画面外。这里钉死 ÷448。
        val box = StanceDetector().decode(outputRows(224f, 224f, 112f, 224f, 0.9f), 2).single()
        assertEquals(0.5f, box.cx, 1e-6f)
        assertEquals(0.75f, box.by, 1e-6f)
        assertEquals(0.25f, box.w, 1e-6f)
        assertEquals(0.5f, box.h, 1e-6f)
    }

    @Test
    fun decode_alreadyNormalizedCoordsStayInUnitDomain() {
        val box = StanceDetector().decode(outputRows(0.5f, 0.5f, 0.25f, 0.5f, 0.9f), 2).single()
        assertEquals(0.5f, box.cx, 1e-6f)
        assertEquals(0.75f, box.by, 1e-6f)
        assertEquals(0.25f, box.w, 1e-6f)
        assertEquals(0.5f, box.h, 1e-6f)
    }

    // Y66/R16：清单显式声明坐标单位后，解码必须服从声明，而不是再猜值域。

    @Test
    fun decode_declaredNormalizedDoesNotDivideByInputSize() {
        val box = StanceDetector()
            .decode(outputRows(0.5f, 0.5f, 0.25f, 0.5f, 0.9f), 2, StanceModelPack.COORD_NORMALIZED)
            .single()
        assertEquals(0.5f, box.cx, 1e-6f)
        assertEquals(0.75f, box.by, 1e-6f)
        assertEquals(0.25f, box.w, 1e-6f)
        assertEquals(0.5f, box.h, 1e-6f)
    }

    @Test
    fun decode_declaredPixelNormalizesAgainstInputTensor() {
        // 显式 pixel 声明按输入张量 448 归一化，而不是分析帧像素；消费者拿到的始终是占比。
        val box = StanceDetector()
            .decode(outputRows(224f, 224f, 112f, 224f, 0.9f), 2, StanceModelPack.COORD_PIXEL)
            .single()
        assertEquals(0.5f, box.cx, 1e-6f)
        assertEquals(0.75f, box.by, 1e-6f)
        assertEquals(0.25f, box.w, 1e-6f)
        assertEquals(0.5f, box.h, 1e-6f)
    }

    @Test
    fun decode_rejectsUnknownDeclaredCoordinateUnit() {
        try {
            StanceDetector().decode(outputRows(224f, 224f, 112f, 224f, 0.9f), 2, "pixels")
            fail("未知坐标单位必须拒绝，不能静默按自适应处理")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("未知坐标单位"))
        }
    }
}
