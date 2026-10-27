package com.wotagei.cam.core

/**
 * 站位指示线（2026-10-27 用户需求）：WOTA 艺多人排练时标出各站位用的等距竖线。
 *
 * # 几何口径
 * 线在预览**宽度比例**空间里居中对称分布：`lineXsFraction(count, spread)` 返回 n 个 0..1 的
 * x 比例——spread 是"整组线占预览宽的比例"，间距 = spread/(n-1) 随之等距。滑杆调 spread
 * （用户语义"调间距"：拉宽组 = 拉大间距，方向一致），count 调线数；两参任意组合都保证
 * 所有线落在 0..1 之内（不裁剪、不出画）——这是不用"裸间距"参数的原因：
 * 裸间距 × 8 条线会超出画幅（0.4 × 7 = 2.8 倍宽）。
 *
 * # 颜色
 * [MarkerLineColor] 六色固定色板（不做人机自选色：预览叠加需要的是"看得清的对比色"，
 * 固定色板已覆盖；自选色属额外功能，未要求）。
 *
 * # 不入成片
 * 渲染走 View 层 Compose Canvas（ui/widget/MarkerLineOverlay），与取景参考线同一通道——
 * 录制采集的是相机/GL 面，View 叠加**结构上**进不了编码器，无需任何开关防呆。
 */
object MarkerLines {

    const val MIN_COUNT = 0
    const val MAX_COUNT = 8
    const val MIN_SPREAD = 0.15f
    const val MAX_SPREAD = 1.00f

    /**
     * 出厂默认 = **0 条 = 关闭**（2026-10-27 二轮定版）：线的开/关只由线数驱动，没有独立
     * 开关，所以"默认关"只能靠默认线数为 0 表达——站位线是监看辅助，不给默认画面添东西；
     * 用户点浮层里的 + 从 1 条开始加。
     */
    const val DEFAULT_COUNT = 0
    const val DEFAULT_SPREAD = 0.60f

    fun clampCount(v: Int): Int = v.coerceIn(MIN_COUNT, MAX_COUNT)

    fun clampSpread(v: Float): Float = v.coerceIn(MIN_SPREAD, MAX_SPREAD)

    /**
     * 等距竖线的 x 比例（升序，全部落在 0..1）：居中对称，整组占 [spread] 比例的宽度。
     * count=1 恒居中（0.5）；count/spread 越界一律钳回合法域（滑杆外的脏值不炸渲染）。
     * **count = 0 返回空表 = 站位线关闭**（2026-10-27 交互定版：线的开/关只由线数驱动，
     * 不设独立开关——浮层控件被误关不会连累线消失，见 WotaParams.markerCount 的说明）。
     */
    fun lineXsFraction(count: Int, spread: Float): List<Float> {
        val n = clampCount(count)
        val w = clampSpread(spread)
        if (n <= 1) return if (n == 1) listOf(0.5f) else emptyList()
        return List(n) { i -> 0.5f - w / 2f + w * i / (n - 1) }
    }
}

/**
 * 站位线色板：[argb] 供 Compose `Color(long)` 直接构造；[labelRes] 供选色弹窗的无障碍文案。
 * 透明度由渲染层统一施加（预览叠加 0.55），色板只定义色相。
 */
enum class MarkerLineColor(val labelRes: Int, val argb: Long) {
    YELLOW(com.wotagei.cam.R.string.marker_color_yellow, 0xFFE6C700L),
    CYAN(com.wotagei.cam.R.string.marker_color_cyan, 0xFF35D6E0L),
    MAGENTA(com.wotagei.cam.R.string.marker_color_magenta, 0xFFE64AC8L),
    GREEN(com.wotagei.cam.R.string.marker_color_green, 0xFF4CE05AL),
    WHITE(com.wotagei.cam.R.string.marker_color_white, 0xFFF2F2F2L),
    ORANGE(com.wotagei.cam.R.string.marker_color_orange, 0xFFFF8A1EL);

    companion object {
        val ALL: List<MarkerLineColor> = values().toList()
        fun fromOrdinal(v: Int): MarkerLineColor = ALL.getOrElse(v) { YELLOW }
    }
}
