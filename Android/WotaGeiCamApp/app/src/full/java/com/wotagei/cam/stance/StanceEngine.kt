package com.wotagei.cam.stance

/**
 * 站位判定引擎：状态机 + 播报队列（纯 Kotlin，无 Android 依赖，JVM 单测钉死规格）。
 * 与 PC 试验台 judge/core.py 的 JudgementEngine 同规格、单测互为镜像。
 *
 * 生命周期（方案 §1/§2）: 预览态专属；「全员，完成」后不停监控（§5.1）；
 * 录制开始/切前置的关闭由宿主调 [reset]/停用（D13，引擎外裁决）。
 * N 确认后锁定：缺⇒「缺人」+缺位外廓、多⇒「多人」（D10/D14），重锚仅手动 [reset]。
 *
 * 多号位状态（R18/R19）：
 * - 平滑/滞回/完成态**按号位**保存，两个号位同拍不会互相清窗或把一次同拍当多次样本；
 * - 播报按轮次轮转：轮内相邻号位用 [lineIntervalMs]，真实轮末后的下一轮用
 *   [pollIntervalMs]；「全员，完成」被节流时保持待播，下一拍补报而不是丢弃；
 * - 本拍最后一句话带 [EngineOutput.roundEnded]，播报器据此在最后一个词播完后接「的说」。
 */
class JudgementEngine(
    mode: String = MODE_AUTO,
    nManual: Int = 1,
    beta: Float = StanceSpec.BETA_DEFAULT,
    lineIntervalMs: Long = StanceSpec.LINE_INTERVAL_MS,
    pollIntervalMs: Long = StanceSpec.POLL_INTERVAL_MS,
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

    /** 轮询内间隔：同一轮内相邻两条播报的最小间隔（用户 r06 口径：每号位播报时间间隔）。 */
    var lineIntervalMs: Long = lineIntervalMs.coerceAtLeast(200)
        private set

    /** 轮询间间隔：上一轮播报结束到下一轮第一条播报的最小静默（用户 r06 口径）。 */
    var pollIntervalMs: Long = pollIntervalMs.coerceAtLeast(200)
        private set

    var nConfirmed: Int? = null
        private set

    /** 判定头（R17：单人实时入口必须经 head.judge；null=纯几何规则）。 */
    var head: StanceHead? = null
        private set

    /** 全部号位均已完成（UI/测试观测用）。 */
    val isDone: Boolean
        get() = memories.isNotEmpty() && memories.values.all { it.done }

    private val countHist = ArrayDeque<Int>(StanceSpec.STABLE_WINDOW)
    private val memories = HashMap<Int, SlotMemory>()
    private var anchors: List<Float>? = null
    private var lastPerson: PersonBox? = null
    private var allDoneReported = false
    private var lastEvents: MutableList<String>? = null

    // ---- 播报轮转（R19）：轮内用 lineInterval，轮末后下一轮用 pollInterval ----
    private var lastSpeakMs = Long.MIN_VALUE / 2
    private var lastSpeakText: String? = null
    private var roundOpen = false
    private val roundSpoken = HashSet<Int>()
    private var lastSpokenSlot = 0

    private class SlotMemory {
        var pending: Verdict? = null
        val pendingHits = ArrayDeque<Boolean>(StanceSpec.SMOOTH_WINDOW)
        var done = false
    }

    private data class PendingSpeech(val key: Int, val text: String)
    private data class BeatSpeech(val lines: List<String>, val roundEnded: Boolean)

    /** β 变化 = 判定口径变化 → 重置平滑/滞回状态（PC 端同款修复：防"红框仍打钩"）。 */
    fun setBeta(beta: Float) {
        if (this.beta != beta) {
            for (m in memories.values) {
                m.pending = null
                m.pendingHits.clear()
                m.done = false
            }
            allDoneReported = false
            roundOpen = false
            roundSpoken.clear()
            lastSpokenSlot = 0
        }
        this.beta = beta
    }

    /**
     * 热换判定头（R17/Y68）。这里只替换引用，**不重置判定状态**：
     * 是否重置由分析管线按「判定/标定语义是否变化」裁决（纯后端切换保留状态）。
     */
    fun setHead(head: StanceHead?) {
        this.head = head
    }

    /** 间隔参数热更新（不重置判定状态——只改播报节奏）。 */
    fun setIntervals(lineMs: Long, pollMs: Long) {
        lineIntervalMs = lineMs.coerceAtLeast(200)
        pollIntervalMs = pollMs.coerceAtLeast(200)
    }

    /** 模式/人数变更（引擎内重锚；UI 直接调，worker 语义由调用方保证单线程）。 */
    fun setMode(mode: String, nManual: Int) {
        this.mode = mode
        this.nManual = nManual.coerceIn(1, 5)
        reset()
    }

    /** 重锚/重置（D14：仅手动入口触发；Y68 的语义变化也复用此入口）。 */
    fun reset() {
        nConfirmed = null
        countHist.clear()
        memories.clear()
        anchors = null
        lastPerson = null
        lastSpeakText = null
        allDoneReported = false
        roundOpen = false
        roundSpoken.clear()
        lastSpokenSlot = 0
        // 播报时刻保留：重置判定不等于允许立刻插队发声；下一拍仍受轮间静默约束。
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

        // D8 分形态：单人选主（其余=背景忽略，不报多人）；多人中位距离过滤。
        // Y65：计数、排序、锚定、判定必须用**同一组**过滤后候选——旧实现分配时重新取
        // gated 最左 N 个，左侧远处路人会占 1 号位、真实练习者被排掉。
        val primary = if (n == 1) selectPrimary(gated) else emptyList()
        val effective = if (n == 1) primary else distanceFilter(gated)
        val missing = maxOf(0, n - effective.size)

        // ---- 人数不符（D10/D14 两向对称；N 锁定不重锚）----
        if (missing > 0) {
            val slots = (1..n).map { SlotState(it, Verdict.MISSING, false) }
            val beat = scheduleSpeech(obs.tsMs, listOf(PendingSpeech(0, Verdict.MISSING.speak)))
            return EngineOutput(beat.lines, slots, effective.size, n, 0, events, beat.roundEnded)
        }
        val people = if (n == 1) primary else effective.sortedBy { it.cx }.take(n)
        val extra = maxOf(0, effective.size - n)

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
        val due = ArrayList<PendingSpeech>(n + 1)
        if (extra > 0) due += PendingSpeech(0, Verdict.EXTRA.speak)
        var allDone = true
        for ((i, person) in people.withIndex()) {
            lastPerson = person
            val target = if (n == 1) null else targets[i]
            val v = if (n == 1) {
                // R17：判定头真正参与单人实时入口；头只放宽不收紧，几何方向由规则导出。
                head?.judge(person, beta, scale) ?: verdictForOne(person, scale)
            } else {
                verdictAt(person, targets[i], scale)
            }
            val slotDone = smoothAndHysteresis(i + 1, v, person, scale, target)
            slots.add(SlotState(i + 1, v, slotDone, person))
            if (!slotDone) {
                allDone = false
                due += PendingSpeech(i + 1, lineText(i + 1, v))
            }
        }
        if (allDone && n >= 1 && !allDoneReported) {
            // R19：完成句先入待播队列；只有真正发出去才标记已报，被节流则下一拍补报。
            due += PendingSpeech(0, "全员，完成")
        }
        val beat = scheduleSpeech(obs.tsMs, due)
        if (beat.lines.contains("全员，完成")) {
            allDoneReported = true
            // §5.1:「全员，完成」后不停监控——掉出后 allDoneReported 由平滑复位
        }
        return EngineOutput(beat.lines, slots, effective.size, n, extra, events, beat.roundEnded)
    }

    /**
     * 播报调度（R19）：每拍最多发一条；轮内相邻号位按 [lineIntervalMs] 轮转，
     * 一轮里所有待播号位都发完后轮末收口（roundEnded=true），下一轮首条再等 [pollIntervalMs]。
     * 待播集合每拍按当前判定重建；已在本轮发过的号位不会重复，避免 1 号位垄断。
     */
    private fun scheduleSpeech(tsMs: Long, due: List<PendingSpeech>): BeatSpeech {
        if (due.isEmpty()) {
            roundOpen = false
            roundSpoken.clear()
            return BeatSpeech(emptyList(), false)
        }
        val sinceLast = tsMs - lastSpeakMs
        if (!roundOpen) {
            if (sinceLast < pollIntervalMs) return BeatSpeech(emptyList(), false)
            roundOpen = true
            roundSpoken.clear()
            lastSpokenSlot = 0
        } else if (sinceLast < lineIntervalMs) {
            return BeatSpeech(emptyList(), false)
        }
        val next = nextUnspoken(due) ?: run {
            roundOpen = false
            roundSpoken.clear()
            return BeatSpeech(emptyList(), false)
        }
        lastSpeakMs = tsMs
        lastSpeakText = next.text
        lastSpokenSlot = next.key
        roundSpoken.add(next.key)
        val remaining = due.any { it.key !in roundSpoken }
        if (!remaining) {
            roundOpen = false
            roundSpoken.clear()
        }
        return BeatSpeech(listOf(next.text), !remaining)
    }

    private fun nextUnspoken(due: List<PendingSpeech>): PendingSpeech? {
        val candidates = due.filter { it.key !in roundSpoken }
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.key == 0 }?.let { return it }
        return candidates.filter { it.key > lastSpokenSlot }.minByOrNull { it.key }
            ?: candidates.minByOrNull { it.key }
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

    /** 跨帧平滑 + 滞回（§4），状态按号位隔离（R18）。返回该号位当前是否处于完成态。 */
    private fun smoothAndHysteresis(
        slot: Int,
        v: Verdict,
        person: PersonBox,
        scale: Float,
        target: Float?,
    ): Boolean {
        val mem = memories.getOrPut(slot) { SlotMemory() }
        if (mem.pending == null || v != mem.pending) {
            mem.pending = v
            mem.pendingHits.clear()
        }
        mem.pendingHits.addLast(v == Verdict.OK)
        if (mem.pendingHits.size > StanceSpec.SMOOTH_WINDOW) mem.pendingHits.removeFirst()
        val full = mem.pendingHits.size == StanceSpec.SMOOTH_WINDOW
        val oks = mem.pendingHits.count { it }
        val stableOk = full && oks >= StanceSpec.SMOOTH_MIN
        val stableBad = full && oks <= StanceSpec.SMOOTH_WINDOW - StanceSpec.SMOOTH_MIN

        if (!mem.done) {
            if (stableOk) {
                mem.done = true
                mem.pendingHits.clear()
            }
            return mem.done
        }
        // 完成态：歪出量 > 门限才掉出（滞回）。单人=四边距越界绝对量（微动 <5% 不掉出）；
        // 多人=中心域边界 × (K−1)。
        val gate = if (target == null) 0.05f * scale
        else StanceSpec.CX_DOMAIN_HALF * scale * (StanceSpec.HYSTERESIS_K - 1f)
        if (stableBad && outDistance(person, scale, target) > gate) {
            mem.done = false
            mem.pendingHits.clear()
            allDoneReported = false
        }
        return mem.done
    }

    private fun lineText(slot: Int, v: Verdict) = "${slot}号位，${v.speak}"
}