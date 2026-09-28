package com.wotagei.cam.ui

import androidx.compose.runtime.Composable
import com.wotagei.cam.media.GalleryScreen

/**
 * `/gallery` 路由薄壳（06 文档 §1）：媒体库界面由 media 包提供，
 * 这里只把「打开播放页 / 进对比页 / 返回」三个动作接到导航上。
 */
@Composable
fun GalleryEntryScreen(
    onOpen: (Long) -> Unit,
    onCompare: (Long) -> Unit,
    onBack: () -> Unit
) {
    GalleryScreen(onOpen = onOpen, onCompare = onCompare, onBack = onBack)
}
