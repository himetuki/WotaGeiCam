package com.wotagei.cam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val WotaColorScheme = darkColorScheme(
    primary = WotaAccent,
    onPrimary = WotaBg,
    secondary = WotaAccentDim,
    background = WotaBg,
    surface = WotaSurface,
    onBackground = WotaText,
    onSurface = WotaText,
    surfaceVariant = WotaSurfaceAlt,
    onSurfaceVariant = WotaTextDim,
    outline = WotaDivider,
    error = WotaRec
)

/** 取景器必须暗背景，故只提供深色主题（需求：纯简体中文、深色优先） */
@Composable
fun WotaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WotaColorScheme, typography = WotaTypography, content = content)
}
