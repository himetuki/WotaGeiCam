package com.wotagei.cam.camera

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 水平仪/俯仰仪的角度换算（纯函数，无 Android 依赖，可在 JVM 直接单测）。
 *
 * 拆出来是因为 [LevelSensor] 需要 `SensorEvent` 才能驱动，导致「读数超出契约 ±90」这类
 * 纯数学缺陷当时没有任何可测接缝。
 */
object LevelMath {

    /**
     * 机头重力分量旋到显示帧：整数倍平面旋转。
     * 用 when 而不是 sin/cos，免浮点误差与逐事件建表。
     */
    fun displayVector(sx: Float, sy: Float, displayDegrees: Int): FloatArray =
        when (((displayDegrees % 360) + 360) % 360) {
            90 -> floatArrayOf(-sy, sx)
            180 -> floatArrayOf(-sx, -sy)
            270 -> floatArrayOf(sy, -sx)
            else -> floatArrayOf(sx, sy)
        }

    /**
     * 左右倾角（度），**保证落在 -90..90**，正值 = 屏幕右侧偏低。
     *
     * `atan2(-ux, uy)` 原始值域是 ±180：当重力落在显示帧下半区（uy<0，机位接近相对显示倒置）
     * 会读出 −95°、+170° 这类超出仪表刻度的值（真机竖屏实测 −97.9°）。
     * 水平仪表达的是「偏离水平多少」，超过 ±90 就折回另一半平面。
     */
    fun rollOf(ux: Float, uy: Float): Float {
        val raw = toDegrees(atan2(-ux, uy))
        return foldToHalfCircle(raw)
    }

    /** 镜头仰俯角（度）：0 = 光轴水平，正值 = 仰角 */
    fun pitchOf(sx: Float, sy: Float, sz: Float): Float {
        val planar = sqrt(sx * sx + sy * sy)
        return toDegrees(atan2(-sz, planar))
    }

    /** 把任意角度折到 (-90, 90]：每 180° 一个水平仪周期 */
    fun foldToHalfCircle(degrees: Float): Float {
        var d = degrees
        while (d > 90f) d -= 180f
        while (d <= -90f) d += 180f
        return d
    }

    fun toDegrees(radians: Float): Float = Math.toDegrees(radians.toDouble()).toFloat()
}
