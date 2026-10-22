package com.wotagei.cam.player

/**
 * 对比页「对着左片练」动线的进程内桥（用户 2026-10-04 定版方向 4；同日增补参考片分屏跟随）。
 *
 * 动线：对比页点「对着左片练」→ [primeWith] 记下参考片并置 [armed]，压栈一个新录制页 →
 * 录制页**首次组合**时 [takeLeftId] 取走参考片：自动切进分屏对比模式、右半屏循环播放左片
 * （边看参考边跳）→ 录制停止成功时把新片 mediaId 记进 [pendingRightId] 并弹栈回对比页 →
 * 对比页 ON_RESUME 消费 [pendingRightId] 填右槽。导航框架的 savedStateHandle 方案要把
 * NavController 穿透进两层页面签名，桥对象一行的事，不值得（同一进程、单用户、无并行竞态）。
 *
 * 自愈：用户没录就退、或录制失败时 [armed] 仍是真——对比页 ON_RESUME 见 [armed] 即先清
 * （再收货），保证不会把「上一次练习的尾巴」错配到「下一次随便录的视频」上；
 * [pendingLeftId] 由录制页一次性取走，不依赖自愈。
 */
object ComparePractice {

    /** 对比页发起、录制页待消费的练习意图 */
    @Volatile
    var armed = false

    /** 录制页写、对比页读的新片 mediaId（pending uri 自带 id，commit 前已定） */
    @Volatile
    var pendingRightId: Long? = null

    /** 练习参考片（对比页左路视频）：录制页取走后自动进分屏循环播放 */
    @Volatile
    private var pendingLeftId: Long? = null

    /** 对比页发起练习：记参考片并武装意图（[armed] 同步置真） */
    fun primeWith(leftId: Long) {
        pendingLeftId = leftId
        armed = true
    }

    /**
     * 录制页首次组合时取走参考片 id（读后即清：练习模式只在"进录制页那一刻"成立，
     * 之后用户自己切分屏/退出分屏都不该被这条旧意图拉回）。
     */
    fun takeLeftId(): Long? {
        val id = pendingLeftId
        pendingLeftId = null
        return id
    }

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
