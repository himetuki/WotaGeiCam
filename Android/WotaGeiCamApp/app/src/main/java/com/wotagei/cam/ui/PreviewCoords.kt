package com.wotagei.cam.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.LayoutCoordinates

/**
 * 承载预览的那枚 `aspectRatio` 盒子的 [LayoutCoordinates]（`CameraScreen.previewStage` 里登记）。
 *
 * 存在的理由：霜矩形表里卡片块的四边必须是**承载视图局部坐标**，而注册点拿到的
 * [LayoutCoordinates] 属于它自己那枚节点。要用 `preview.localPositionOf(card, …)` 把两者的共同祖先
 * 变换（分屏那层 `graphicsLayer{scale}`、页面平移…）抵消掉，注册点就得先摸到预览盒的坐标。
 *
 * 默认 null = 这一页没有可承载霜的预览面（编辑控件页）：注册点据此走旧的窗口系路径，观感不变。
 * 首帧坐标还没量到时也是 null，此时注册点**不写表**（沿用原来的 `coords == null → return`），
 * 免得写出一块按错坐标系算的板。
 */
val LocalPreviewCoords = staticCompositionLocalOf<LayoutCoordinates?> { null }
