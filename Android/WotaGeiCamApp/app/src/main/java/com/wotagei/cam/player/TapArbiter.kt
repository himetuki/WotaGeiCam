package com.wotagei.cam.player

/**
 * 画面单击 / 双击的仲裁（纯状态机，JVM 可单测）。
 *
 * 单击必须等满双击窗口才落地：否则第二指抬起来时显隐已经切过一次，
 * 「双击启停」会顺带把控件栏闪一下。等满窗口才切显隐，是这两个手势能共存的唯一办法。
 */
internal class TapArbiter(private val windowMs: Long) {

    enum class Decision {
        /** 这一击先当作单击，调用方挂起一个 windowMs 的定时器，到点再切显隐 */
        DeferredToggle,

        /** 这一击是双击的第二击：取消挂起的显隐，改判为启停 */
        PlayPause
    }

    /** 挂起中的单击到期时刻（ms）；0 = 没有挂起 */
    private var pendingDueMs = 0L

    fun onPointerUp(nowMs: Long): Decision {
        if (pendingDueMs != 0L && nowMs <= pendingDueMs) {
            pendingDueMs = 0L
            return Decision.PlayPause
        }
        pendingDueMs = nowMs + windowMs
        return Decision.DeferredToggle
    }

    /**
     * 挂起的单击定时器到点。返回 true 才真的切显隐——
     * 期间被双击取代（[onPointerUp] 已清零）或被别的动作取消时返回 false。
     */
    fun onToggleDue(nowMs: Long): Boolean {
        if (pendingDueMs == 0L || nowMs < pendingDueMs) return false
        pendingDueMs = 0L
        return true
    }

    fun cancelPending() {
        pendingDueMs = 0L
    }
}
