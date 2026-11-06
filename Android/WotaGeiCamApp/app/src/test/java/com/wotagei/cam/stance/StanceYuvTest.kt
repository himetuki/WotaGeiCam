package com.wotagei.cam.stance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** YUV→RGBA 与提亮的纯函数单测（小矩阵钉死；M2a 帧源规格）。 */
class StanceYuvTest {

    /** 2x2 半分辨率 UV 的最小 YUV_420 布局（rowStride=width、pixelStride=1）。 */
    private fun yuv2x2(
        y: IntArray, u: IntArray, v: IntArray,
    ): IntArray = StanceAnalysis.yuv420ToRgba(
        y.map { it.toByte() }.toByteArray(),
        u.map { it.toByte() }.toByteArray(),
        v.map { it.toByte() }.toByteArray(),
        2, 2, 2, 1, 1,
    )

    @Test
    fun yuv_greyLevels() {
        // 灰阶：U=V=128（±0）→ R=G=B=Y
        val out = yuv2x2(
            y = intArrayOf(16, 128, 235, 200),
            u = intArrayOf(128, 128),
            v = intArrayOf(128, 128),
        )
        assertEquals(0xFF101010.toInt(), out[0])          // Y=16 → 0x10
        assertEquals(0xFF808080.toInt(), out[1])          // Y=128
        assertEquals(0xFFEBEBEB.toInt(), out[2])          // Y=235
        assertEquals(0xFFC8C8C8.toInt(), out[3])          // Y=200
    }

    @Test
    fun yuv_color() {
        // BT.601：Y=81 U=90 V=240 → R=81+1.402×112≈238（红主导）；
        // G/B = 81-0.344×(-38)-0.714×112 与 81+1.772×(-38) ≈ 13~14（f32 结合序 ULP 差 ±1，
        // 对检测零影响——断言钉通道方向不钉 ULP）
        val red = yuv2x2(
            y = intArrayOf(81, 81, 81, 81),
            u = intArrayOf(90, 90),
            v = intArrayOf(240, 240),
        )
        val px = red[0]
        assertEquals(238, (px shr 16) and 0xFF)
        assertTrue(((px shr 8) and 0xFF) <= 16)
        assertTrue((px and 0xFF) <= 16)
    }

    @Test
    fun brighten_clampsAndLinear() {
        // alpha=1.3 beta=30：0 → 30；255 → clamp(361.5)=255；100 → 160
        val rgba = intArrayOf(
            (0xFF shl 24) or (0 shl 16) or (0 shl 8) or 0,
            (0xFF shl 24) or (255 shl 16) or (255 shl 8) or 255,
            (0xFF shl 24) or (100 shl 16) or (100 shl 8) or 100,
        )
        StanceAnalysis.brightenRgba(rgba)
        assertEquals(30, rgba[0] and 0xFF)
        assertEquals(255, (rgba[1] shr 16) and 0xFF)
        assertEquals(160, (rgba[2] shr 8) and 0xFF)
    }
    // pickYuvSize 依赖 CameraCharacteristics、DEFAULT_SIZE 是 android.util.Size——
    // 两者 JVM 不可测（工程无 Robolectric），逻辑以常量兜底（任何设备均有 640x480 可用），
    // 真机冒烟覆盖。
}
