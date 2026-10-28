package com.wotagei.cam.core

import kotlin.math.abs

/**
 * 色调四滑条（高光/阴影/黑点/白点，29 §4.2 F4，2026-10-28）：**纯函数**，JVM 全测。
 *
 * 与曲线同级是 scene layer——烘进 LUT（预览与成片同一层）；DIRECT 无 GL 片元链不生效
 * （与曲线的灰化口径一致，不自动切 GPU）。零 GPU 成本：四个滑条烘成一条一维传递函数，
 * 在 bake 阶段与既有曲线组合 `out_channel(x) = channelCurve(masterCurve(tone(x)))`。
 *
 * 建议映射（保持**单调**，29 §4.2 原文）：
 * 1. 电平：`y = (x - black) / (1 - black - white)`，clamp 0..1；
 * 2. 阴影：`y += shadows · k · (1-y)²`；
 * 3. 高光：`y += highlights · k · y²`；
 * 4. 再 clamp 0..1。`k ≈ 0.25` 保证四项取极值时 256 点采样仍严格非降。
 */
data class ToneParams(
    /** 黑点 0..0.35（抬黑/压黑） */
    val black: Float = 0f,
    /** 白点 0..0.35（压白/提白） */
    val white: Float = 0f,
    /** 阴影 -1..1（负=压暗） */
    val shadows: Float = 0f,
    /** 高光 -1..1（负=压暗高光） */
    val highlights: Float = 0f
) {
    /** 四项全 0 = 恒等：跳过 LUT 烘焙的唯一合法依据（与 stack.isPassthrough 合取） */
    val isIdentity: Boolean
        get() = black == 0f && white == 0f && shadows == 0f && highlights == 0f

    /** 越界输入（滑杆直写/坏串）收进产品域；NaN 回 0 */
    fun sanitize(): ToneParams = ToneParams(
        black = sanitizeRange(black, BLACK_MAX),
        white = sanitizeRange(white, WHITE_MAX),
        shadows = sanitizeRange(shadows, 1f),
        highlights = sanitizeRange(highlights, 1f)
    )

    /** 一维传递函数：输入/输出都在 0..1（256 点烘焙逐点采样） */
    fun apply(x: Float): Float {
        val b = sanitizeRange(black, BLACK_MAX)
        val w = sanitizeRange(white, WHITE_MAX)
        // 电平：黑点抬/白点压，分母最低 1-0.7=0.3 不会除零
        var y = ((x - b) / (1f - b - w)).coerceIn(0f, 1f)
        if (shadows != 0f) {
            y += SHADOW_GAIN * sanitizeRange(shadows, 1f) * (1f - y) * (1f - y)
        }
        if (highlights != 0f) {
            y += HIGHLIGHT_GAIN * sanitizeRange(highlights, 1f) * y * y
        }
        return y.coerceIn(0f, 1f)
    }

    companion object {
        val IDENTITY = ToneParams()

        const val BLACK_MAX = 0.35f
        const val WHITE_MAX = 0.35f
        const val SHADOW_GAIN = 0.25f
        const val HIGHLIGHT_GAIN = 0.25f

        private fun sanitizeRange(v: Float, max: Float): Float =
            if (v.isNaN()) 0f else v.coerceIn(-max, max)

        /**
         * `black white shadows highlights` 空格分隔（浮点原样）；段数不对/含坏值回
         * [IDENTITY]——**刻意不进** CurveStack 的 encode/decode（那会改 KEY_CURVE_STACK 的
         * 持久格式，老用户串会解坏），独立键 KEY_TONE（29 §4.3）。
         */
        fun encode(p: ToneParams): String {
            val s = p.sanitize()
            return "${s.black} ${s.white} ${s.shadows} ${s.highlights}"
        }

        fun decode(raw: String?): ToneParams {
            if (raw.isNullOrBlank()) return IDENTITY
            val seg = raw.trim().split(" ").mapNotNull { it.toFloatOrNull() }
            if (seg.size != 4) return IDENTITY
            val p = ToneParams(seg[0], seg[1], seg[2], seg[3]).sanitize()
            return if (abs(p.black) + abs(p.white) + abs(p.shadows) + abs(p.highlights) == 0f) IDENTITY else p
        }
    }
}
