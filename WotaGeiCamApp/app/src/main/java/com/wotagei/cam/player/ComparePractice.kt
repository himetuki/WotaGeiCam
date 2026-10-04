package com.wotagei.cam.player

/**
 * 对比页「对着左片练」动线的进程内桥（用户 2026-10-04 定版方向 4）。
 *
 * 动线：对比页点「对着左片练」→ [armed] 置真并压栈一个新录制页 → 录制停止成功时录制页
 * 把新片 mediaId 记进 [pendingRightId] 并弹栈回对比页 → 对比页 ON_RESUME 消费
 * [pendingRightId] 填右槽。导航框架的 savedStateHandle 方案要把 NavController 穿透进
 * 两层页面签名，桥对象一行的事，不值得（同一进程、单用户、无并行竞态）。
 *
 * 自愈：用户没录就退、或录制失败时 [armed] 仍是真——对比页 ON_RESUME 见 [armed] 即先清
 * （再收货），保证不会把「上一次练习的尾巴」错配到下一次随便录的视频上。
 */
object ComparePractice {

    /** 对比页发起、录制页待消费的练习意图 */
    @Volatile
    var armed = false

    /** 录制页写、对比页读的新片 mediaId（pending uri 自带 id，commit 前已定） */
    @Volatile
    var pendingRightId: Long? = null

    /**
     * 录制页停止成功时调用：armed 为真才记账（普通录制的停止不进这条动线）。
     * @return true = 本条录制确属练习动线，调用方应弹栈回对比页
     */
    fun deliver(mediaId: Long): Boolean {
        if (!armed) return false
        armed = false
        pendingRightId = mediaId
        return true
    }
}
