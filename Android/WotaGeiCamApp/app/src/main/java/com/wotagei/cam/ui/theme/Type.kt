package com.wotagei.cam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 数字（快门/ISO/时长/角度）一律等宽，避免跳字
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)

/**
 * 每档都显式写 lineHeight：「文本高度」这个概念要能被缩放，行高不能留在平台默认值上。
 * 取值按 ≈1.4 倍字号，与设默认值前的实际排版基本一致。
 */
val WotaTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 21.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 13.sp, lineHeight = 18.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp, lineHeight = 14.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp
    )
)

/** 整屏文本高度缩放：字号与行高同比，1f 即 [WotaTypography] 原值 */
fun Typography.scaledBy(scale: Float): Typography {
    if (scale == 1f) return this
    fun TextStyle.s() = copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)
    return copy(
        titleLarge = titleLarge.s(),
        titleMedium = titleMedium.s(),
        bodyMedium = bodyMedium.s(),
        labelSmall = labelSmall.s(),
        labelMedium = labelMedium.s()
    )
}

/**
 * 给一屏内容套上「该屏自己的文本高度」。
 * 基准恒为 [WotaTypography]（不是环境里当前的 typography），所以嵌套包也不会把两屏的缩放乘起来——
 * 录制页 120% 时，抽屉里的 90% 仍是 90%，这才是「各屏独立」。
 * 只换 typography，colorScheme/shapes 原样透传，避免 MaterialTheme 用默认浅色方案覆盖工程色板。
 */
@Composable
fun TextScaleLayer(scale: Float, content: @Composable () -> Unit) {
    val scaled = remember(scale) { WotaTypography.scaledBy(scale) }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = scaled,
        shapes = MaterialTheme.shapes,
        content = content
    )
}
