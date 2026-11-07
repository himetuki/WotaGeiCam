package com.wotagei.cam.stance

/**
 * 站位判定核心：类型与纯函数（规格真源）。
 *
 * 规格文档: docs/plan/32-无人值守AI站位提示方案.md（D1~D20）。
 * 本包与 PC 试验台 tools/stance-lab/wota_lab/judge/core.py 同规格；
 * PC 侧 Python 实现与其单测为规格锚，两侧单测用例互为镜像（同输入同期望）。
 * 所有坐标为识别区域的归一化占比（cx=框中心x, by=框底边y ∈ [0,1]），与像素无关。
 *
 * 方向语义（钉死，仅后置镜头播报、有人一律视作面向镜头）:
 *   LEFT    = 播报「往左」 = 需要画面右移 = 人在目标左侧（贴画面左缘）
 *   RIGHT   = 播报「往右」 = 需要画面左移
 *   FORWARD = 播报「往前」 = 需靠近镜头   = 脚（框底）悬离画面下缘 = 太远
 *   BACK    = 播报「往后」 = 需远离镜头   = 脚贴画面下缘 = 太近
 *   OK      = 完成域内
 *
 * 关键裁决索引:
 *   D8  距离过滤: 框高 ≤ 画面高 10% 一律路人（不论置信度）；多人路径另按群聚中位剔除
 *   D9  单人优先: 选主策略（conf×框高最大且 >10%）为主体，其余检出=背景忽略不报多人
 *   D10/D14 N 锁定: 确认后人数不符两向播报（缺人/多人），均不自动重锚，重锚仅手动
 *   D3  β 偏差率: 判定粗糙度旋钮（大=宽松/小=严格），规则路径=安全区缩放+置信度门限
 *   D19 标准站位 = YOLO 外框到画面边缘的距离占比（用脚不用头——上边距不判）
 */
object StanceSpec {
    /** 多人先验点位中心（§3.2；单人判定不用——单人走四边距安全区）。 */
    const val CX_TARGET = 0.5f
    /** 多人先验水平完成域半宽。 */
    const val CX_DOMAIN_HALF = 0.15f
    /** 多人先验纵向带（框底 y）。 */
    val BY_BAND = 0.65f to 0.85f

    /** D8: 框高 < 群聚中位 × 0.60 剔除。 */
    const val DIST_H_LOW = 0.60f
    /** D8: 框高 > 群聚中位 × 1.50 剔除。 */
    const val DIST_H_HIGH = 1.50f
    /** D8 十四稿: 框高 ≤ 画面高 10% = 路人，不论置信度排除。 */
    const val MIN_SUBJECT_H = 0.10f

    /** §2 自动模式人数稳定窗口（拍）。 */
    const val STABLE_WINDOW = 5
    /** §4 跨帧平滑窗口。 */
    const val SMOOTH_WINDOW = 3
    /** §4 窗口内一致拍数下限。 */
    const val SMOOTH_MIN = 2
    /** §4 完成态掉出放大系数。 */
    const val HYSTERESIS_K = 1.8f
    /** §5.1 同文本播报节流（毫秒）。 */
    const val SPEAK_INTERVAL_MS = 2000L

    const val BETA_DEFAULT = 0.3f
    const val BETA_MIN = 0.05f
    const val BETA_MAX = 0.95f

    /**
     * 单人标准站位（D19 十二稿口径）：**用脚不用头**——判定只用三个边距，
     * 上边距不判（跳舞时框顶随举手/跳跃剧烈变化）。安全区无先验标准，
     * 下列数值仅是可运行的占位，**以 PC 试验台"正确"标注归纳的域值覆盖**
     * （[loadCalibratedBands]，calibrate.py 产物 margins.json）。
     */
    val MARGIN_BANDS = linkedMapOf(
        "left" to (0.10f to 0.45f),    // 框左下角（≈左脚）→ 画面左缘
        "right" to (0.10f to 0.45f),   // 框右下角（≈右脚）→ 画面右缘
        "bottom" to (0.10f to 0.40f),  // 框底（脚踝地面锚点）→ 画面下缘
    )

    /** β → (域缩放, 置信度门限)。β 大 = 域放宽 + 门限低 = 判定粗糙（宽松）。 */
    fun deriveThresholds(beta: Float): Pair<Float, Float> {
        val b = beta.coerceIn(BETA_MIN, BETA_MAX)
        val t = (b - BETA_MIN) / (BETA_MAX - BETA_MIN)
        return lerp(0.70f, 1.50f, t) to lerp(0.45f, 0.15f, t)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /**
     * 加载标定产物（calibrate.py 的 margins.json 文本）覆盖占位先验。
     * 文件不存在/结构非法 → 保持先验并返回 false（端侧与 PC 端 load_calibrated_bands 同规格）。
     */
    fun loadCalibratedBands(marginsJson: String?): Boolean {
        if (marginsJson.isNullOrBlank()) return false
        return try {
            @Suppress("UNCHECKED_CAST")
            val bands = (StanceJson.parse(marginsJson) as Map<String, Any?>)["bands"] as? Map<String, Any?>
                ?: return false
            val parsed = linkedMapOf<String, Pair<Float, Float>>()
            for (side in MARGIN_BANDS.keys) {
                val arr = bands[side] as? List<*> ?: return false
                val lo = (arr[0] as Double).toFloat()
                val hi = (arr[1] as Double).toFloat()
                if (!(lo in 0f..1f && hi in 0f..1f && lo < hi)) return false
                parsed[side] = lo to hi
            }
            parsed.forEach { (k, v) -> MARGIN_BANDS[k] = v }
            true
        } catch (_: Exception) {
            false
        }
    }
}

/** 归一化人员检测框。 */
data class PersonBox(
    val cx: Float,
    val by: Float,
    val w: Float,
    val h: Float,
    val conf: Float,
)

/** 一拍观测。tsMs=毫秒时间戳。 */
data class FrameObs(
    val tsMs: Long,
    val boxes: List<PersonBox>,
)

/** 判定输出词位（播报文案与 PC 端逐字一致）。 */
enum class Verdict(val speak: String) {
    OK("完成"),
    LEFT("往左"),
    RIGHT("往右"),
    FORWARD("往前"),
    BACK("往后"),
    MISSING("缺人"),
    EXTRA("多人"),
}

/** 单号位对外状态。 */
data class SlotState(
    val slot: Int,
    val verdict: Verdict,
    val done: Boolean,
    val person: PersonBox? = null,
)

/** 一拍的对外输出。lines=本轮应播报文本（有序）；events=诊断事件。 */
data class EngineOutput(
    val lines: List<String> = emptyList(),
    val slots: List<SlotState> = emptyList(),
    val nEffective: Int = 0,
    val nConfirmed: Int? = null,
    val extra: Int = 0,
    val events: List<String> = emptyList(),
)

/** 外框四边（D19：left/right/bottom 三边距参与判定；top 仅记录不判）。 */
fun boxMargins(box: PersonBox): Map<String, Float> = mapOf(
    "left" to (box.cx - box.w / 2),
    "right" to (1f - (box.cx + box.w / 2)),
    "bottom" to (1f - box.by),
    "top" to (box.by - box.h),
)

/**
 * D8（多人形态）: 先滤路人（框高 ≤10% 画面高），再按群聚中位剔除离群者。
 * 仅多人路径使用——单人走 [selectPrimary]（1 主体+1 远路人时中位数落在两者之间
 * 会把主体与路人一起剔除，中位距离过滤在无群聚时失效）。
 */
fun distanceFilter(boxes: List<PersonBox>): List<PersonBox> {
    val pass = boxes.filter { it.h > StanceSpec.MIN_SUBJECT_H }
    if (pass.size <= 1) return pass
    val heights = pass.map { it.h }.sorted()
    val n = heights.size
    val median = if (n % 2 == 1) heights[n / 2] else (heights[n / 2 - 1] + heights[n / 2]) / 2f
    val lo = median * StanceSpec.DIST_H_LOW
    val hi = median * StanceSpec.DIST_H_HIGH
    return pass.filter { it.h in lo..hi }
}

/**
 * D8（单人形态）: 选主——先滤路人，再取 conf×框高最高者为主体；其余检出视为
 * 背景忽略且**不触发「多人」**。全部是路人 → 空表（= 主体不在画面，走「缺人」）。
 */
fun selectPrimary(boxes: List<PersonBox>): List<PersonBox> {
    val candidates = boxes.filter { it.h > StanceSpec.MIN_SUBJECT_H }
    if (candidates.isEmpty()) return emptyList()
    return listOf(candidates.maxBy { it.conf * it.h })
}

/** §2 自动模式人数确认: 连续 STABLE_WINDOW 拍一致才确认；未达成一致返回末值。 */
fun countConfirmedOf(history: List<Int>): Int? {
    if (history.size < StanceSpec.STABLE_WINDOW) return null
    val window = history.takeLast(StanceSpec.STABLE_WINDOW)
    return if (window.all { it == window[0] }) window[0] else window.last()
}

private fun horizontalVerdict(cx: Float, target: Float, half: Float): Verdict = when {
    cx < target - half -> Verdict.LEFT
    cx > target + half -> Verdict.RIGHT
    else -> Verdict.OK
}

private fun verticalVerdict(by: Float, band: Pair<Float, Float>): Verdict = when {
    by < band.first -> Verdict.FORWARD
    by > band.second -> Verdict.BACK
    else -> Verdict.OK
}

internal fun scaledBand(scale: Float): Pair<Float, Float> {
    val span = StanceSpec.BY_BAND.second - StanceSpec.BY_BAND.first
    return (StanceSpec.BY_BAND.first - span * (scale - 1) / 2) to
        (StanceSpec.BY_BAND.second + span * (scale - 1) / 2)
}

/**
 * 单人规则五分类（D19 四边距口径）：判定序 左→右→下，先命中先报（横优先——
 * 横向出画是痛点主诉）。domainScale=β 派生的安全区缩放（1.0=标定域原样）。
 */
fun verdictForOne(box: PersonBox, domainScale: Float = 1f): Verdict {
    val m = boxMargins(box)
    val s = domainScale
    for ((side, near) in listOf("left" to Verdict.LEFT, "right" to Verdict.RIGHT)) {
        val (lo, _) = StanceSpec.MARGIN_BANDS.getValue(side)
        if (m.getValue(side) < lo / s) return near
    }
    val (loB, hiB) = StanceSpec.MARGIN_BANDS.getValue("bottom")
    val bottom = m.getValue("bottom")
    if (bottom < loB / s) return Verdict.BACK      // 脚贴画面底 = 太近
    if (bottom > hiB * s) return Verdict.FORWARD   // 脚悬离画面底 = 太远
    return Verdict.OK
}

/**
 * 四边距越界量最大的边 → 播报方向（与 [verdictForOne] 同口径；头路径
 * "判歪但几何在安全区内"时不使用——几何 OK 就 OK，头只放宽不收紧）。
 */
fun worstMarginDirection(box: PersonBox, domainScale: Float = 1f): Verdict {
    val m = boxMargins(box)
    val s = domainScale
    val (loL, hiL) = StanceSpec.MARGIN_BANDS.getValue("left")
    val (loR, hiR) = StanceSpec.MARGIN_BANDS.getValue("right")
    val (loB, hiB) = StanceSpec.MARGIN_BANDS.getValue("bottom")
    val candidates = listOf(
        (loL / s - m.getValue("left")) to Verdict.LEFT,     // 贴左
        (m.getValue("left") - hiL * s) to Verdict.RIGHT,    // 左距过大 = 偏右
        (loR / s - m.getValue("right")) to Verdict.RIGHT,   // 贴右
        (m.getValue("right") - hiR * s) to Verdict.LEFT,    // 右距过大 = 偏左
        (loB / s - m.getValue("bottom")) to Verdict.BACK,   // 脚贴底 = 太近
        (m.getValue("bottom") - hiB * s) to Verdict.FORWARD,// 脚悬空 = 太远
    )
    val (over, verdict) = candidates.maxBy { it.first }
    return if (over > 0f) verdict else Verdict.OK
}

/** 歪出量（占比，滞回与平滑用）：单人=四边距越界最大者；多人=中心域越界。 */
fun outDistance(box: PersonBox, domainScale: Float, target: Float? = null): Float {
    val s = domainScale
    if (target == null) {
        val m = boxMargins(box)
        var worst = 0f
        for ((side, band) in StanceSpec.MARGIN_BANDS) {
            val v = m.getValue(side)
            worst = maxOf(worst, band.first - v, v - band.second)
        }
        return worst
    }
    val half = StanceSpec.CX_DOMAIN_HALF * s
    val dx = maxOf(0f, kotlin.math.abs(box.cx - target) - half)
    val band = scaledBand(s)
    val dy = maxOf(0f, band.first - box.by, box.by - band.second)
    return maxOf(dx, dy)
}

/** §3.2 多人先验点位: 两端实际位置为锚、中间等距内插（升序 x）。n=1 → 画面中心。 */
fun anchorSlots(boxes: List<PersonBox>, n: Int): List<Float> {
    if (n <= 1) return listOf(StanceSpec.CX_TARGET)
    val xs = boxes.map { it.cx }.sorted().take(n)
    if (xs.size < 2) {
        return List(n) { i -> StanceSpec.CX_TARGET + (i - (n - 1) / 2.0).toFloat() * 0.2f }
    }
    val lo = xs.first()
    val hi = xs.last()
    return List(n) { i -> lo + (hi - lo) * i / (n - 1) }
}
