package com.wotagei.cam.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 上屏面重挂的纪元与限频（Y52 抽出的持有者边界）。
 *
 * 旧实现把 `surfaceEpoch` 和 600ms 限频的 `lastAt` 一起内联在组合里，收集器只按 ctrl 建键：
 * GPU↔DIRECT 切换后旧收集器仍活着，重挂请求会把计数/日志写进**旧持有者**，新建承载视图的
 * 纪元不推进（画面仍是黑的）。这里让每个 activeSink 拥有一份独立持有者：
 * - 计数与限频窗口都随 sink 重建，换面本身不会被上一面的限频吞掉；
 * - 旧 sink 的迟到请求最多只动旧持有者，界面读的是新持有者的纪元。
 */
internal class ReattachEpoch(private val now: () -> Long) {
    var epoch by mutableIntStateOf(0)
        private set
    private var lastAt: Long? = null

    /** @return true = 本次请求推进了纪元（未被 600ms 限频吞掉） */
    fun request(): Boolean {
        val at = now()
        val previous = lastAt
        if (previous != null && at - previous < 600L) return false
        lastAt = at
        epoch++
        return true
    }
}
