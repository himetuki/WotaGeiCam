package com.wotagei.cam.player

import kotlin.math.abs

/**
 * 对比播放 PR 式双轨时间线内核（**纯 Kotlin，无任何 Android 依赖**，JVM 单测直接打）。
 *
 * # 时间线定义
 * T ≡ 左片时间轴的推广（可负可超尾）。符号约定与 [AudioSync] 类注释及对比页一致：
 * **rightPos = leftPos + offsetMs**。两段素材在 T 轴上的占位：
 * - 左轨素材段 [0, L)；
 * - 右轨素材段 [−off, R−off)；
 * - 时间线下限 Tmin = min(0, −off)，上限 Tmax = max(L, R−off)。
 *
 * T ∈ [0, L) 内恒等于左片时间——旧公式（t+offset、abWrapHook 的 a+offset、双条 offset 重算）
 * 全部原样保留，变的只是取值范围与编排。off=0 时 Tmin=0、Tmax=max(L,R)，进度条/读数
 * 观感与旧单左轴一致（回归红线）。
 *
 * # 三种素材域与交接
 * - 双素材域（T 同时落在两段内）：主钟=左引擎（T=leftLive），右软跟随；
 * - 右独播域（off>0 时 T<0 的 pre-roll；L<R−off 时 T≥L 的尾段）：主钟=右（T=rightPos−off），
 *   左 dormant（softPause(true)+黑层，黑层本体在对比页）；
 * - 左独播域（off<0 时 T<−off 的头段；R−off<L 时 T≥R−off 的尾段）：主钟=左，右 dormant。
 * **交接不 seek**：位置天然连续（另一轨一直在播或停在域边界），编排 tick 检测 T 跨域后
 * 唤醒/置 dormant；唯一发 seek 的是时间线末回绕（wrap 到 Tmin）。
 *
 * # 域判定为什么要黑层状态回灌
 * 纯位置区分不了「某轨停在域边界（park 在 0 / 素材尽头）」与「它真的在双素材域」——
 * park 点本身就落在覆盖判定的边界值上。所以 [DecideInput.blackLeft]/[blackRight]
 * 带上当前已施加的黑层位（编排器上一拍输出回灌，seekTimeline 落 park 时也直接写），
 * 在册独播域据此锁定主钟，直到 T 回到双覆盖区才交回双主钟。没有这一位，往 pre-roll
 * 或尾段 seek 后的第一拍会把停在边界的主钟误判成双素材域，纠偏 seek 会把画面拽回去。
 *
 * # 素材尽头的 park 落点用 durMs 而不是 durMs−1（刻意偏离任务书字面）
 * dur−1 仍在覆盖判定区间 [0, L) 内，park 在那里会被域判定读成"双素材域"，下一拍纠偏
 * 就把另一轨拽回来；park 在 durMs 上 ExoPlayer 直接进 STATE_ENDED 停末帧，位置读数
 * t=L 落在覆盖区外，与"素材已尽"的域判定自洽。头侧 park（T 早于素材起点）仍是 0，
 * 那里的边界歧义由黑层回灌位兜住。
 */
enum class CompareDomain {
    /** 左独播：主钟=左，右 dormant（右窗黑） */
    LEFT_SOLO,

    /** 右独播：主钟=右，左 dormant（左窗黑） */
    RIGHT_SOLO,

    /** 双素材域：主钟=左，右软跟随 */
    DUAL,

    /** 时间线末：两段素材都已尽 */
    TIMELINE_END
}

/** 时间线几何：由两段素材时长与偏移推出 Tmin/Tmax、覆盖判定与素材域落点（纯函数） */
data class TimelineGeometry(val leftDurMs: Long, val rightDurMs: Long, val offsetMs: Long) {

    /** 时间线下限：min(0, −off) */
    val tMin: Long get() = minOf(0L, -offsetMs)

    /** 时间线上限：max(L, R−off) */
    val tMax: Long get() = maxOf(leftDurMs, rightDurMs - offsetMs)

    /** T 是否落在左轨素材段 [0, L) */
    fun leftCovers(t: Long): Boolean = t >= 0L && t < leftDurMs

    /** T 是否落在右轨素材段 [−off, R−off) */
    fun rightCovers(t: Long): Boolean = t >= -offsetMs && t < rightDurMs - offsetMs

    /** T 处左引擎应处的素材位置：域内即 T；早于域头 park 到 0；越过域尾 park 到素材尽头（ENDED 停末帧） */
    fun leftTarget(t: Long): Long = when {
        leftCovers(t) -> t
        t < 0L -> 0L
        else -> leftDurMs
    }

    /** T 处右引擎应处的素材位置：域内即 T+off；早于域头 park 到 0；越过域尾 park 到素材尽头 */
    fun rightTarget(t: Long): Long = when {
        rightCovers(t) -> t + offsetMs
        t < -offsetMs -> 0L
        else -> rightDurMs
    }
}

/** decide 的输入快照（一拍一份；黑层位是上一拍输出/seekTimeline 写入的回灌） */
data class DecideInput(
    val geo: TimelineGeometry,
    val leftLiveMs: Long,
    val rightLiveMs: Long,
    /** 用户期望播放（两引擎 isPlaying 并集）；paused 时主钟不动、纠偏照跑（与旧循环同口径） */
    val userPlaying: Boolean,
    /** AB 段循环激活（loopEnabled 且 A/B 合法）：回绕归 abWrapHook，decide 不抢 */
    val abLoopActive: Boolean,
    /** 全程循环开关（时间线末 T≥Tmax 回绕；关=末帧停驻，重播走 setPlaying 的点播放路径） */
    val timelineLoop: Boolean,
    /** 用户拖动中：压 seek/nudge/wrap，素材域 pause/黑层照做 */
    val dragging: Boolean,
    /** 当前已施加的左窗黑层（左 dormant） */
    val blackLeft: Boolean,
    /** 当前已施加的右窗黑层（右 dormant） */
    val blackRight: Boolean,
    /** 硬 seek 阈值（对比页 HARD_RESYNC_MS，250）：漂出邻域才动播放位置 */
    val hardResyncMs: Long
)

/**
 * decide 的输出指令：全为「该拍要做什么」，null/false=不动。
 * 应用顺序由编排器固定：先 pause/resume，再 seek，最后 nudge——resume 触发的 ENDED
 * 自动回零会被随后的 seek 覆盖，nudge 永远落在最终位置上。
 */
data class DecideCommands(
    val seekLeftMs: Long? = null,
    val seekRightMs: Long? = null,
    /** 断言 softPause(true)（幂等安全，重复下发无害） */
    val pauseLeft: Boolean = false,
    val pauseRight: Boolean = false,
    /** 断言 softPause(false)（只在独播域退出且用户要播时下发，不与 setPlaying 抢播放权） */
    val resumeLeft: Boolean = false,
    val resumeRight: Boolean = false,
    /** 黑层绝对态（编排器写回会话位，供下一拍域判定回灌） */
    val blackLeft: Boolean = false,
    val blackRight: Boolean = false,
    /** 右片软追赶：漂移量（正=右超前），由 nudgeSpeedForSync 折算 ±10% 速率 */
    val nudgeRightDriftMs: Long? = null
)

object CompareTimeline {

    /**
     * 素材域判定。黑层在册时以在册域为主钟，退出条件=主钟 T 回到双覆盖区；
     * 无在册域时按位置入口判定（pre-roll 入口、双覆盖、两向尾段），都不沾边即时间线末。
     * 头向独播段（off<0 头段）的 park 在素材 0、恰落右覆盖段**含边界**左缘上，
     * 由双覆盖按时间线位 tL 判两侧天然消歧（见分支内短注），无需单独入口网。
     */
    fun domainOf(
        geo: TimelineGeometry,
        leftLiveMs: Long,
        rightLiveMs: Long,
        blackLeft: Boolean,
        blackRight: Boolean
    ): CompareDomain {
        val tL = leftLiveMs
        val tR = rightLiveMs - geo.offsetMs
        // 时间线末：两段素材都已尽（位置在 park 到素材尽头的引擎上同样成立）。
        // 这个判定必须排在黑层在册分支之前——末态本来就要撤黑层
        if (tL >= geo.leftDurMs && tR >= geo.rightDurMs - geo.offsetMs) return CompareDomain.TIMELINE_END
        return when {
            blackLeft -> if (tR >= 0L && geo.leftCovers(tR) && geo.rightCovers(tR)) {
                CompareDomain.DUAL
            } else {
                CompareDomain.RIGHT_SOLO
            }
            blackRight -> if (geo.leftCovers(tL) && geo.rightCovers(tL)) {
                CompareDomain.DUAL
            } else {
                CompareDomain.LEFT_SOLO
            }
            // 右钟报出 T<0：pre-roll 入口（安全网；常规入口经 seekTimeline 直接写在册黑层）
            tR < 0L && geo.rightCovers(tR) -> CompareDomain.RIGHT_SOLO
            // 双覆盖以时间线位 tL 判两侧：off<0 头段（tL<|off|）时右未覆盖 → LEFT_SOLO，
            // park 在 0 的右轨不会被误判入域（tR = |off| 恰落右覆盖段含边界左缘的假象由此消解）
            geo.leftCovers(tL) && geo.rightCovers(tL) -> CompareDomain.DUAL
            geo.leftCovers(tL) -> CompareDomain.LEFT_SOLO
            geo.rightCovers(tR) && tR >= 0L -> CompareDomain.RIGHT_SOLO
            else -> CompareDomain.TIMELINE_END
        }
    }

    /** 当前时间线时刻：双素材域与左独播域跟随左钟，右独播域跟随右钟，时间线末取 Tmax */
    fun resolveT(
        geo: TimelineGeometry,
        leftLiveMs: Long,
        rightLiveMs: Long,
        blackLeft: Boolean,
        blackRight: Boolean
    ): Long = when (domainOf(geo, leftLiveMs, rightLiveMs, blackLeft, blackRight)) {
        CompareDomain.DUAL, CompareDomain.LEFT_SOLO -> leftLiveMs
        CompareDomain.RIGHT_SOLO -> rightLiveMs - geo.offsetMs
        CompareDomain.TIMELINE_END -> geo.tMax
    }

    /**
     * 一拍决策（纯函数）。职责：
     * - 素材域 pause/黑层（dormant 轨每拍重断言，幂等）；
     * - 双素材域纠偏（公式与旧循环逐字一致：漂出 [hardResyncMs] 硬 seek 回邻域，
     *   邻域内交 nudgeSpeedForSync 软追赶）；
     * - 独播域交接唤醒（不 seek，位置天然连续）；
     * - 时间线末回绕（全程循环开+在播）或末帧停驻（循环关；点播放重播由 setPlaying 承担）。
     * 旧纠偏循环的 `ended ||`、`wrapped &&`、`desired > rd-1 continue` 三分支在此被
     * 素材域判定取代：右轨 ENDED ⟹ 不再覆盖 ⟹ 根本不进双素材域分支。
     */
    fun decide(input: DecideInput): DecideCommands {
        val g = input.geo
        return when (domainOf(g, input.leftLiveMs, input.rightLiveMs, input.blackLeft, input.blackRight)) {
            CompareDomain.DUAL -> {
                val tL = input.leftLiveMs
                var c = DecideCommands()
                // 交接唤醒：上一拍还在独播域（黑层在册）、这一拍回到双覆盖区——
                // 只在用户要播时唤醒，用户暂停时保持安静（播放权归 setPlaying）
                if (input.blackLeft && input.userPlaying) c = c.copy(resumeLeft = true)
                if (input.blackRight && input.userPlaying) c = c.copy(resumeRight = true)
                if (!input.dragging) {
                    // 纠偏（旧公式逐字保留）：drift = 右实际位置 − 期望位置（T+off）
                    val desired = tL + g.offsetMs
                    val drift = input.rightLiveMs - desired
                    c = if (abs(drift) > input.hardResyncMs) {
                        c.copy(seekRightMs = desired)
                    } else {
                        c.copy(nudgeRightDriftMs = drift)
                    }
                }
                c
            }
            // 独播域：dormant 侧每拍重断言暂停（tick 兜底），主钟侧播放权归 setPlaying
            CompareDomain.RIGHT_SOLO -> DecideCommands(blackLeft = true, pauseLeft = true)
            CompareDomain.LEFT_SOLO -> DecideCommands(blackRight = true, pauseRight = true)
            CompareDomain.TIMELINE_END ->
                if (input.userPlaying && !input.dragging && input.timelineLoop && !input.abLoopActive) {
                    // 全程循环开：到尾即统一回绕 Tmin（AB 段循环激活时回绕归 abWrapHook，这里不抢）。
                    // 回绕材料感知：pause/resume/黑层只落在 Tmin 处**无素材**的一侧（park 侧直接
                    // 带住，decide 知道哪侧 park），消除"resume 后以播放态停在 park 位空走 ≤1 tick
                    // 才被下一拍 pause"的未静窗口（审查修复轮 1 P3）；off=0 时两侧都有素材，
                    // 与旧行为（双 resume、零 pause/黑层）逐位一致
                    val t = g.tMin
                    val leftAtT = g.leftCovers(t)
                    val rightAtT = g.rightCovers(t)
                    DecideCommands(
                        seekLeftMs = g.leftTarget(t),
                        seekRightMs = g.rightTarget(t),
                        resumeLeft = leftAtT,
                        resumeRight = rightAtT,
                        pauseLeft = !leftAtT,
                        pauseRight = !rightAtT,
                        blackLeft = !leftAtT,
                        blackRight = !rightAtT
                    )
                } else {
                    // 末帧停驻：两轨素材都尽，不盖黑层、不纠偏；循环关时的重播
                    // 由 setPlaying（用户点播放这个动作）走 seekTimeline(Tmin)+起播
                    DecideCommands()
                }
        }
    }
}
