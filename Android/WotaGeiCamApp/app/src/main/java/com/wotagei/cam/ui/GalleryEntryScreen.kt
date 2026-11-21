package com.wotagei.cam.ui

import androidx.compose.runtime.Composable
import com.wotagei.cam.media.GalleryScreen
import com.wotagei.cam.media.VideoClip

/**
 * `/gallery` 路由薄壳（06 文档 §1）：媒体库界面由 media 包提供，
 * 这里只把「打开播放页 / 进对比页 / 返回」三个动作接到导航上。
 *
 * 打开/对比交出**整条行**（Y27）：行 URI 是跨卷唯一的身份，路由侧据此按具体卷加载。
 */
@Composable
fun GalleryEntryScreen(
    onOpen: (VideoClip) -> Unit,
    onCompare: (VideoClip) -> Unit,
    onBack: () -> Unit
) {
    GalleryScreen(onOpen = onOpen, onCompare = onCompare, onBack = onBack)
}
