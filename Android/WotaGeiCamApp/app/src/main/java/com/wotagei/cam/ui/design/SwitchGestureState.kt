package com.wotagei.cam.ui.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * WotaSwitch 的一次拖动手势状态（Y57 抽出的可测边界）。
 *
 * 目标语义：
 * - 跨中点立刻翻转，但**手势本身不中断**——翻转只改本段手势自己的逻辑档，
 *   后续移动继续接受，离手落位与速度继承都还在；
 * - 取消/异常（父容器抢指针、页面切换）统一走 [endGesture]：pressing、dragX、
 *   dragVelocity 全部清空，并把拇指从当前位置落回本段手势的逻辑档；
 * - 没移动过 = 一次点击，松手翻转一次。
 *
 * 状态字段用 Compose State，组合侧直接读；判定逻辑纯内存，JVM 单测可直驱。
 */
internal sealed interface SwitchRelease {
    data class Click(val checked: Boolean) : SwitchRelease
    data class Drag(val at: Float, val target: Float, val initialVelocity: Float) : SwitchRelease
}

internal data class SwitchCancel(val at: Float, val target: Float)

internal class SwitchGestureState {
    var checked: Boolean = false
        private set
    var dragX by mutableStateOf<Float?>(null)
        private set
    var dragVelocity by mutableFloatStateOf(0f)
        private set
    var pressing by mutableStateOf(false)
        private set
    var moved: Boolean = false
        private set
    var handled: Boolean = false
        private set

    fun begin(initialChecked: Boolean) {
        pressing = true
        dragX = null
        dragVelocity = 0f
        checked = initialChecked
        moved = false
        handled = false
    }

    /** 第一次移动才取偏移；返回 true = 本段逻辑档刚翻转（需要回调），false = 已翻转，null = 未翻转 */
    fun move(at: Float, grab: Float, travelPx: Float, velocity: Float): Boolean? {
        moved = true
        dragVelocity = velocity
        val next = (at - grab).coerceIn(0f, travelPx)
        dragX = next
        val past = next > travelPx / 2f
        return if (past != checked) {
            checked = past
            past
        } else {
            null
        }
    }

    /** 正常松手：清掉拖动残余并给出落位参数；没移动过则翻转一次（点击语义） */
    fun finish(travelPx: Float, currentX: Float, springVelocity: Float): SwitchRelease {
        handled = true
        val released = dragX
        dragX = null
        if (!moved) {
            checked = !checked
            return SwitchRelease.Click(checked)
        }
        val at = released ?: currentX
        val v0 = if (abs(dragVelocity) > 1f) dragVelocity else springVelocity
        return SwitchRelease.Drag(at, if (checked) travelPx else 0f, v0)
    }

    /** 正常收尾、取消、异常都走这里：清空按压/拖动/速度；未正常收尾时给出回位参数 */
    fun endGesture(travelPx: Float): SwitchCancel? {
        pressing = false
        dragVelocity = 0f
        val at = dragX
        dragX = null
        return if (!handled && moved && at != null) {
            SwitchCancel(at, if (checked) travelPx else 0f)
        } else {
            null
        }
    }
}
