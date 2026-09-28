package com.wotagei.cam.camera

import android.graphics.Bitmap

/**
 * 程序化生成斑马纹条纹贴图（对应  red_sparse / red_default / red_dense 三档，本项目不带 PNG 资源）。
 *
 * 瓦片是 45° 斜纹：沿 (x+y) 方向周期为 `TILE_PX / PERIODS_PER_TILE`，周期整除边长，
 * 因此 UV 平铺到边界天然无缝，配合 `GL_REPEAT` 使用。
 * 三档密度先用条纹占空比（线宽/周期）拉开观感差异，屏幕上的实际疏密再由 [tileRepeat] 的平铺倍数叠加，
 * 二者合起来等价于  里「三张贴图 + quadDimension」的组合，但省掉资源文件。
 */
internal object ZebraPattern {

    /** 瓦片边长（px）：只影响生成耗时与条纹锐度，不占包体 */
    const val TILE_PX = 64

    /** 每张瓦片内的条纹周期数（决定平铺无缝） */
    const val PERIODS_PER_TILE = 2

    /** 密度档 1/2/3 → 着色器平铺倍数 4/8/16（03 文档 §3.5） */
    private val TILE_REPEAT = floatArrayOf(4f, 8f, 16f)

    /** 密度档 1/2/3 → 条纹占空比（稀疏细线 → 稠密粗线） */
    private val DUTY_CYCLE = floatArrayOf(0.25f, 0.35f, 0.50f)

    /** 条纹色与 res/values/colors.xml 的 wota_refline_red 同值；空隙取纯黑 */
    private val stripeColor = 0xFFE53935.toInt()
    private val gapColor = 0xFF000000.toInt()

    /** 越界钳制：UI/持久化传来的档位不一定在 1..3 */
    fun sanitize(density: Int): Int = density.coerceIn(1, 3)

    /** 该密度档对应的 UV 平铺倍数 */
    fun tileRepeat(density: Int): Float = TILE_REPEAT[sanitize(density) - 1]

    /** 生成不透明 ARGB_8888 瓦片，可直接 `GLUtils.texImage2D` 上传（须在 GL 线程调用侧持有） */
    fun tileBitmap(density: Int): Bitmap {
        val idx = sanitize(density) - 1
        val period = TILE_PX / PERIODS_PER_TILE
        val lineWidth = (period * DUTY_CYCLE[idx]).toInt().coerceAtLeast(1)
        val pixels = IntArray(TILE_PX * TILE_PX)
        for (y in 0 until TILE_PX) {
            for (x in 0 until TILE_PX) {
                pixels[y * TILE_PX + x] = if ((x + y) % period < lineWidth) stripeColor else gapColor
            }
        }
        return Bitmap.createBitmap(pixels, TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
    }
}
