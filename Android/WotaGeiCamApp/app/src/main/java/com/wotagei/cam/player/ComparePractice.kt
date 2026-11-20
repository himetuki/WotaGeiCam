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

    /**
     * 发起练习的那枚对比页导航条目 id（`NavBackStackEntry.id`，Y51）。
     * 回程时先核对它是否仍在返回栈上：过期回调（发起页已被别的动线弹掉）不动作，
     * 免得「定向回对比页」落在一枚已失效的身份上。
     */
    @Volatile
    var armedOrigin: String? = null

    /**
     * 对比页发起练习：记参考片并武装意图（[armed] 同步置真）。
     * @param originEntryId 发起页的导航条目 id；测试/旧调用点可不传（null = 不校验发起者）
     */
    fun primeWith(leftId: Long, originEntryId: String? = null) {
        pendingLeftId = leftId
        armedOrigin = originEntryId
        armed = true
    }

    /** 回程核对用：取走发起者身份（读后即清，同一意图只结算一次） */
    fun takeOrigin(): String? {
        val id = armedOrigin
        armedOrigin = null
        return id
    }

    /**
     * 对比页 ON_RESUME 自愈：丢弃整条过期意图（含发起者身份）。
     * 与逐字段手清相比，这里多清 [armedOrigin]，否则旧发起页 id 会留给下一次回程核对。
     */
    fun disarm() {
        armed = false
        pendingRightId = null
        armedOrigin = null
        pendingLeftId = null
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

/** 练习录成后的回程动作（Y51）：裸弹栈只作定向弹栈的保底，且必须还有下层页可退 */
enum class PracticeReturn { TO_COMPARE, POP_ONE, STAY }

/**
 * 练习录成回程决策（纯函数，Y51）。
 *
 * 黑屏不变量：普通返回 `NavController.popBackStack` 有
 * 「起始目的地不弹」守卫，练习回程是程序化导航、不走那条守卫，所以这里自己判：
 * - [originAlive] = false：发起练习的对比页已不在返回栈上（过期回调）→ 不动作；
 * - 对比页在栈上 → 定向弹回对比页；
 * - 对比页不在栈上 → 只有 [canPopOne]（栈上除当前页外还有下层页）才普通弹一层，
 *   否则宁可停在原页也不把返回栈清空（清空 = NavHost 无内容 = 整窗黑）。
 *
 * @param compareInStack 对比页路线是否仍在返回栈上
 * @param originAlive 发起者身份是否仍有效（未记录发起者时调用方传 true）
 * @param canPopOne 当前栈顶之下是否还有页（`previousBackStackEntry != null`）
 */
fun practiceReturnPlan(compareInStack: Boolean, originAlive: Boolean, canPopOne: Boolean): PracticeReturn =
    when {
        !originAlive -> PracticeReturn.STAY
        compareInStack -> PracticeReturn.TO_COMPARE
        canPopOne -> PracticeReturn.POP_ONE
        else -> PracticeReturn.STAY
    }
