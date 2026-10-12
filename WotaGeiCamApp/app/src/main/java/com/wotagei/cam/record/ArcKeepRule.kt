package com.wotagei.cam.record

/**
 * 源帧率**实测**（帧距中位数，纯函数 JVM 可测）：强制 24/25fps 档的容器 `KEY_FRAME_RATE`
 * 会标成目标值而传感器实际跑更高帧率，按容器值造抽帧计划会得出"无需抽帧"的假结论。
 * 中位数对重复/空样本免疫（帧距≈0 被过滤），这是"45 个有效包被数成 124 个样本"那次
 * 遍历教训的正确解法：不数个数，量间隔。
 */
object ArcRateProbe {

    /**
     * 抽帧处理成片的**名称前缀**（用户 2026-10-04 定版："XXftoXXf"，源在前目标在后），
     * 如 30fps 实测转 24fps → `30fto24f_`。光弧修复的成片与录制期转换的成片共用这一个构造，
     * 名字上可辨"这条片被本软件抽过帧"。
     */
    fun convertNamePrefix(srcFps: Int, dstFps: Int): String = "${srcFps}fto${dstFps}f_"

    /** 有效帧距样本少于这个数（极短视频）时中位数不可信，退回容器帧率 */
    const val MIN_DELTAS = 8

    /** 实测帧率上限（防畸变帧距把值算飞；产品最高档 240，留一倍余量） */
    const val MAX_MEASURED_FPS = 480

    /** 滤噪带（v1 同口径）：帧距 <1ms 是重复样本、>1s 是轨道断层 */
    private val CLEAN_RANGE = 1_001..1_000_000

    /**
     * 实测源帧率的**有理数**视图（num/den，已约分）：整数 fps 会把 30.0 与 29.97（30000/1001）
     * 压成同一个"30"，但抽帧计划的保留位次与"源不快于目标"的门槛判据都会因这 0.03fps 漂移
     * （M6-01：离线修复链非整数源分叉的根因）。探测核把真值以有理数交出，整数视图
     * （[roundedInt]）只配给日志/前缀这类只看量级的消费点。
     */
    data class MeasuredSrcFps(val num: Long, val den: Long) {

        /** 四舍五入取整并钳到 1..[MAX_MEASURED_FPS]（半上：⌊(2num+den)/(2den)⌋，无浮点） */
        fun roundedInt(): Int =
            ((num * 2 + den) / (den * 2)).coerceIn(1L, MAX_MEASURED_FPS.toLong()).toInt()

        /** 有理数版门槛判：源帧率 ≤ 整数 n ⇔ num ≤ n·den（整数取整口径会把 25.4 打成 25 误判） */
        fun atMostInt(n: Int): Boolean = num <= n.toLong() * den

        companion object {
            /**
             * 约分构造。非正数输入按 (0,1) 哨兵返回（防御，正常链路走不到：档位表全正、
             * 回退帧率 ≥1、原始折算 10⁶/d 恒正）。
             */
            fun of(num: Long, den: Long): MeasuredSrcFps {
                if (num <= 0L || den <= 0L) return MeasuredSrcFps(0L, 1L)
                val g = gcd(num, den)
                return if (g == 1L) MeasuredSrcFps(num, den) else MeasuredSrcFps(num / g, den / g)
            }

            /** 欧几里得最大公约数（两参恒正） */
            private fun gcd(a: Long, b: Long): Long {
                var x = a
                var y = b
                while (y != 0L) {
                    val t = x % y
                    x = y
                    y = t
                }
                return x
            }
        }
    }

    /** 已知档位快照表：exact 整数档在前、NTSC（÷1001）在后；各档 ±2µs 窗口互不重叠 */
    private val KNOWN_RATES = listOf(
        MeasuredSrcFps(24L, 1L), MeasuredSrcFps(25L, 1L), MeasuredSrcFps(30L, 1L),
        MeasuredSrcFps(50L, 1L), MeasuredSrcFps(60L, 1L), MeasuredSrcFps(120L, 1L),
        MeasuredSrcFps(240L, 1L),
        MeasuredSrcFps(24_000L, 1_001L), MeasuredSrcFps(30_000L, 1_001L),
        MeasuredSrcFps(60_000L, 1_001L),
    )

    /**
     * 中位帧距 [dUs] 吸附到已知档位：判据纯整数交叉相乘 `|d·num − den·10⁶| ≤ 2·num`
     * （⇔ |d − 该档周期| ≤ 2µs，容下解码 PTS 的量化抖动）；未命中退原始有理数 10⁶/d 约分
     * ——**没有"整成整数 fps"这步损耗**，29.97 就是 30000/1001。
     */
    fun snapToKnownRate(dUs: Long): MeasuredSrcFps {
        for (r in KNOWN_RATES) {
            val diff = dUs * r.num - 1_000_000L * r.den
            if (diff <= 2L * r.num && diff >= -2L * r.num) return r
        }
        return MeasuredSrcFps.of(1_000_000L, dUs)
    }

    /**
     * 由**原始**相邻样本帧距（微秒）测源帧率（有理数版）：先按 [CLEAN_RANGE] 滤噪，再取中位数
     * 吸附已知档位（[snapToKnownRate]）；有效样本不足退回 [fallbackNum]/[fallbackDen]。
     */
    fun measuredSrcFps(
        rawDeltasUs: List<Int>,
        fallbackNum: Long,
        fallbackDen: Long = 1L
    ): MeasuredSrcFps {
        val clean = rawDeltasUs.filter { it in CLEAN_RANGE }
        if (clean.size < MIN_DELTAS) return MeasuredSrcFps.of(fallbackNum, fallbackDen)
        return snapToKnownRate(clean.sorted()[clean.size / 2].toLong())
    }

    /**
     * 旧整数签名（v1 口径，只作兼容外壳）：样本不足时 [fallbackFps] **原样**返回（与 v1 逐字
     * 一致，不钳制）；有样本则走有理数核再取整数视图——所有旧调用点行为零变化。
     */
    fun measuredFps(rawDeltasUs: List<Int>, fallbackFps: Int): Int {
        val clean = rawDeltasUs.filter { it in CLEAN_RANGE }
        if (clean.size < MIN_DELTAS) return fallbackFps
        return measuredSrcFps(rawDeltasUs, fallbackFps.toLong()).roundedInt()
    }
}

/**
 * 录制在线路的**抽帧保留规则**（用户 2026-10-03 定版，2026-10-03 夜实现）。
 *
 * 录制侧没有"先知帧率"：传感器在无精确档范围里实际跑多快由 HAL 决定，所以这里不用
 * `⌊j·S/D⌋` 那套离线计划，改用**应到时刻**判定——输出第 k 帧的应到时刻 = 锚点 + k·帧间隔，
 * 相机帧到达时刻（SurfaceTexture 时间戳，单调）不早于它才保留为第 k 帧，否则丢弃。
 * 规则自追平（停滞后来帧不从落后账里连发）、自纠偏（传感器快慢漂移直接反映到丢弃分布），
 * 长期输出节奏收敛到恰为 D fps。
 *
 * 输出 PTS 不走本类：GL 编码支路的均匀帧序号时间戳（帧序号 × 1e9/fps）只在保留帧上推进，
 * 丢弃帧跳过 swap 即可，两套口径天然一致。源帧率实测见 [ArcRateProbe]。
 */
class ArcKeepRule(private val slotNs: Long) {
    /** 下一输出帧的应到时刻；[NOT_STARTED] = 还没见到首帧 */
    private var nextDueNs = NOT_STARTED

    /** 自上一保留帧以来累计丢弃数（供被抽帧位次 sidecar 记账） */
    private var pendingDrops = 0

    /**
     * 相机帧到达（[tsNs] = SurfaceTexture 时间戳）。
     * @return true = 保留为本输出帧（调用方推进输出帧序号）
     */
    fun onFrame(tsNs: Long): Boolean {
        if (slotNs <= 0L) return true
        if (nextDueNs == NOT_STARTED) {
            // 首帧保留并锚定节奏：下一帧的应到时刻 = 首帧时刻 + 帧间隔
            nextDueNs = tsNs + slotNs
            return true
        }
        return if (tsNs >= nextDueNs) {
            // 注意：保留帧**不**清 pendingDrops——"自上一保留帧以来丢了几个"要在发出那一帧时
            // 才被记账方 takeDrops() 取走，这里清了会把区间账抹成 0
            nextDueNs += slotNs
            if (nextDueNs <= tsNs) nextDueNs = tsNs + slotNs   // 停滞追平，不连发
            true
        } else {
            pendingDrops++
            false
        }
    }

    /** 取走"最近一次保留之前丢弃了几帧"（Emit 记账用；取走即清零） */
    fun takeDrops(): Int {
        val n = pendingDrops
        pendingDrops = 0
        return n
    }

    /** 重置（每次录制开始/编码面重挂时调用） */
    fun reset() {
        nextDueNs = NOT_STARTED
        pendingDrops = 0
    }

    private companion object {
        val NOT_STARTED = Long.MIN_VALUE
    }
}
