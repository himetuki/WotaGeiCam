package com.wotagei.cam.ui

import androidx.compose.runtime.mutableStateOf

/**
 * 可被旧闭包读到最新值的布尔持有者（Y56）。
 *
 * `pointerInput(Unit)` 的手势协程只捕获**首帧闭包**；直接捕获组合期 Boolean 会把
 * "首帧能力未知 ⇒ 默认可用"固化。这里闭包捕获的是持有者本身，组合每次把最新
 * focusUsable 写进来，手势执行时现读 [value]。
 */
internal class LatestBoolean(initial: Boolean) {
    private val state = mutableStateOf(initial)
    val value: Boolean get() = state.value

    fun update(next: Boolean) {
        if (state.value != next) state.value = next
    }
}
