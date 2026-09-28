package com.wotagei.cam.ui.theme

import androidx.compose.ui.graphics.Color
import com.wotagei.cam.ui.design.WotaColor

/**
 * 兼容旧引用名，值一律委托给 [WotaColor]。
 * 拆令牌层之前这里是自己写死的一整套，两套并存迟早漂移，所以只留一个真源。
 */
val WotaBg: Color = WotaColor.bg
val WotaSurface: Color = WotaColor.surface
val WotaSurfaceAlt: Color = WotaColor.layer
val WotaAccent: Color = WotaColor.accent
val WotaAccentDim: Color = WotaColor.accentDim
val WotaRec: Color = WotaColor.rec
val WotaWarn: Color = WotaColor.warn
val WotaText: Color = WotaColor.textHi
val WotaTextDim: Color = WotaColor.textLo
val WotaDivider: Color = WotaColor.outline
val RefLine: Color = WotaColor.refLine
val RefLineRed: Color = WotaColor.refLineRed
val AcrylicScrim: Color = WotaColor.scrim
val WotaHudScrim: Color = WotaColor.hudScrim
