package com.wotagei.cam.stance

/**
 * 站位判定引擎：状态机 + 播报队列（纯 Kotlin，无 Android 依赖，JVM 单测钉死规格）。
 * 与 PC 试验台 judge/core.py 的 JudgementEngine 同规格、单测互为镜像。
 *
 * 生命周期（方案 §1/§2）: 预览态专属；「全员，完成」后不停监控（§5.1）；
 * 录制开始/切前置的关闭由宿主调 [reset]/停用（D13，引擎外裁决）。
 * N 确认后锁定：缺⇒「缺人」+缺位外廓、多⇒「多人」（D10/D14），重锚仅手动 [reset]。
 */
class JudgementEngine(
    mode: String = MODE_AUTO,
    nManual: Int = 1,
    beta: Float = StanceSpec.BETA_DEFAULT,
) {
    companion object {
        const val MODE_AUTO = "auto"
        const val MODE_MANUAL = "manual"
    }

    var mode: String = mode
        private set
    var nManual: Int = nManual.coerceIn(1, 5)
        private set
    var beta: Float = beta
        private set

    var nConfirmed: Int? = null
        private set

    /** 当前单人完成态（测试与 UI 观测用）。 */
    val isDone: Boolean get() = done

    private val countHist = ArrayDeque<Int>(StanceSpec.STABLE_WINDOW)
    private var pending: Verdict? = null
    private val pendingHits = ArrayDeque<Boolean>(StanceSpec.SMOOTH_WINDOW)
    private var done = false
    private var anchors: List<Float>? = null
    private var lastPerson: PersonBox? = null
    private var lastSpeakMs = Long.MIN_VALUE / 2
    private var lastSpeakText: String? = null
    private var allDoneReported = false
    private var lastEvents: MutableList<String>? = null

    /** β 变化 = 判定口径变化 → 重置平滑/滞回状态（PC 端同款修复：防"红框仍打钩"）。 */
    fun setBeta(beta: Float) {
        if (this.beta != beta) {
            pending = null
            pendingHits.clear()
            done = false
            allDoneReported = false
        }
        this.beta = beta
    }

    /** 模式/人数变更（引擎内重锚；UI 直接调，worker 语义由调用方保证单线程）。 */
    fun setMode(mode: String, nManual: Int) {
        this.mode = mode
        this.nManual = nManual.coerceIn(1, 5)
        reset()
    }

    /** 重锚/重置（D14：仅手动入口触发）。 */
    fun reset() {
        nConfirmed = null
        countHist.clear()
        pending = null
        pendingHits.clear()
        done = false
        anchors = null
        lastPerson = null
        lastSpeakText = null
        allDoneReported = false
    }

    /** 主入口：按拍喂入观测，产出播报文本与槽状态。 */
    fun observe(obs: FrameObs): EngineOutput {
        val (scale, confGate) = StanceSpec.deriveThresholds(beta)
        val gated = obs.boxes.filter { it.conf >= confGate }
        val events = mutableListOf<String>()
        lastEvents = events

        if (nConfirmed == null) {
            // 人数确认预览按目标形态：manual+1人 = 选主（D9）；manual 多人/auto = 多人
            // 距离过滤（数多人语义，与 PC 端同规格）
            val effective = if (mode == MODE_MANUAL && nManual == 1) selectPrimary(gated)
            else distanceFilter(gated)
            countHist.addLast(effective.size)
            if (countHist.size > StanceSpec.STABLE_WINDOW) countHist.removeFirst()
            nConfirmed = when {
                mode == MODE_MANUAL -> nManual
                else -> countConfirmedOf(countHist.toList())?.also {
                    events += "n_confirmed=$it"
                }
            }
        }
        val n = nConfirmed ?: return EngineOutput(nEffective = gated.size, events = events)

        // D8 分形态：单人选主（其余=背景忽略，不报多人）；多人中位距离过滤
        val (effective, extra) = if (n == 1) {
            val picked = selectPrimary(gated)
            picked.size to 0
        } else {
            val eff = distanceFilter(gated)
            eff.size to maxOf(0, eff.size - n)
        }
        val missing = maxOf(0, n - effective)

        // ---- 人数不符（D10/D14 两向对称；N 锁定不重锚）----
        if (missing > 0) {
            val slots = (1..n).map { SlotState(it, Verdict.MISSING, false) }
            val lines = throttled(obs.tsMs, Verdict.MISSING.speak)
            return EngineOutput(lines, slots, effective, n, 0, events)
        }
        val people = gated.sortedBy { it.cx }.take(n)
        if (extra > 0) {
            // 「多人」优先占节流窗（D14 循环照播）；判定照常，多余框被忽略
            val extraLine = throttled(obs.tsMs, Verdict.EXTRA.speak)
            val (slots, lines) = feedPersonSlots(obs, people, n, scale)
            return EngineOutput(extraLine + lines, slots, effective, n, extra, events)
        }
        val (slots, lines) = feedPersonSlots(obs, people, n, scale)
        return EngineOutput(lines, slots, effective, n, 0, events)
    }

    /** 有效人数 == N 的判定路径。返回 (slots, lines)。 */
    private fun feedPersonSlots(
        obs: FrameObs,
        people: List<PersonBox>,
        n: Int,
        scale: Float,
    ): Pair<List<SlotState>, List<String>> {
        val targets: List<Float> = if (n == 1) {
            listOf(StanceSpec.CX_TARGET)
        } else {
            if (anchors == null) {
                anchors = anchorSlots(people, n)
                lastEvents?.add("anchors_set")
            }
            anchors!!
        }
        val slots = ArrayList<SlotState>(n)
        val lines = ArrayList<String>()
        var allDone = true
        for ((i, person) in people.withIndex()) {
            lastPerson = person
            val v = if (n == 1) verdictForOne(person, scale)
            else verdictAt(person, targets[i], scale)
            val slotDone = smoothAndHysteresis(obs.tsMs, v, person, scale, if (n == 1) null else targets[i])
            slots.add(SlotState(i + 1, v, slotDone, person))
            if (!slotDone) {
                allDone = false
                lines += throttled(obs.tsMs, lineText(i + 1, v))
            }
        }
        if (allDone && n >= 1 && !allDoneReported) {
            lines += throttled(obs.tsMs, "全员，完成")
            allDoneReported = true
            // §5.1:「全员，完成」后不停监控——掉出后 allDoneReported 由平滑复位
        }
        return slots to lines
    }

    private fun verdictAt(person: PersonBox, target: Float, scale: Float): Verdict {
        val half = StanceSpec.CX_DOMAIN_HALF * scale
        val v = horizontal(person.cx, target, half)
        return if (v != Verdict.OK) v else vertical(person.by, scaledBand(scale))
    }

    private fun horizontal(cx: Float, target: Float, half: Float): Verdict = when {
        cx < target - half -> Verdict.LEFT
        cx > target + half -> Verdict.RIGHT
        else -> Verdict.OK
    }

    private fun vertical(by: Float, band: Pair<Float, Float>): Verdict = when {
        by < band.first -> Verdict.FORWARD
        by > band.second -> Verdict.BACK
        else -> Verdict.OK
    }

    /** 跨帧平滑 + 滞回（§4）。返回该号位当前是否处于完成态。 */
    private fun smoothAndHysteresis(
        tsMs: Long,
        v: Verdict,
        person: PersonBox,
        scale: Float,
        target: Float?,
    ): Boolean {
        @Suppress("UNUSED_VARIABLE") val isOk = v == Verdict.OK
        if (pending == null || v != pending) {
            pending = v
            pendingHits.clear()
        }
        pendingHits.addLast(v == Verdict.OK)
        if (pendingHits.size > StanceSpec.SMOOTH_WINDOW) pendingHits.removeFirst()
        val full = pendingHits.size == StanceSpec.SMOOTH_WINDOW
        val oks = pendingHits.count { it }
        val stableOk = full && oks >= StanceSpec.SMOOTH_MIN
        val stableBad = full && oks <= StanceSpec.SMOOTH_WINDOW - StanceSpec.SMOOTH_MIN

        if (!done) {
            if (stableOk) {
                done = true
                pendingHits.clear()
            }
            return done
        }
        // 完成态：歪出量 > 门限才掉出（滞回）。单人=四边距越界绝对量（微动 <5% 不掉出）；
        // 多人=中心域边界 × (K−1)。
        val gate = if (target == null) 0.05f * scale
        else StanceSpec.CX_DOMAIN_HALF * scale * (StanceSpec.HYSTERESIS_K - 1f)
        if (stableBad && outDistance(person, scale, target) > gate) {
            done = false
            pendingHits.clear()
            allDoneReported = false
        }
        return done
    }

    /** 播报节流: 同文本重复需间隔 SPEAK_INTERVAL_MS；文本变化=状态转换，立即播。 */
    private fun throttled(tsMs: Long, text: String): List<String> {
        return if (text != lastSpeakText || tsMs - lastSpeakMs >= StanceSpec.SPEAK_INTERVAL_MS) {
            lastSpeakMs = tsMs
            lastSpeakText = text
            listOf(text)
        } else {
            emptyList()
        }
    }

    private fun lineText(slot: Int, v: Verdict) = "${slot}号位，${v.speak}"
}
