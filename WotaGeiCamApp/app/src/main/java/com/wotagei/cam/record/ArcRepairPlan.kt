package com.wotagei.cam.record

/**
 * 光弧修复（用户需求第 7 项）的**抽帧计划**：把实测 [srcFps] fps 的素材抽成 [dstFps] fps。
 *
 * v2 口径（用户 2026-10-03 定版，本文件是它的唯一真源；v1 的"插帧"被判定为方向性错误废弃）：
 * - 强制 24/25fps 在无固定档设备上传感器实际跑更高帧率（如 30），素材里**多出**的帧要**抽掉**，
 *   输出只含保留帧——**绝不插帧**：抽帧再插帧帧数绕回原点，且混合帧会把运动中的光弧拆成
 *   两道半亮重影，原帧/混合帧逐帧交替正是用户看到的"来回闪动"；
 * - 保留集 = { ⌊j·srcFps/dstFps⌋ : j }（对 30→24 即"每 5 帧抽 1"，抽帧点分布均匀不扎堆）；
 * - 光弧是**加性亮度**（光轨在长曝光里叠出来的亮线），被抽帧的画面用**逐样本亮度取大**并入
 *   它前后两枚保留帧：暗场上取大即无损伤并集——被抽帧独有的弧段在两邻帧上都以**原亮度**
 *   重现，弧在抽帧点上不断裂，也不像 0.5 平均那样把弧压暗出亮度呼吸；
 * - 输出 PTS 取**均匀目标帧率步长**（第 j 帧 = j/ dstFps），容器是真 24/25fps；输出时长
 *   = 保留帧数/dstFps = ⌈N·D/S⌉/D ≈ N/S = 源时长，音频直拷同步不受影响。
 *
 * 抽帧节奏（保留集推导）是"计划"；被抽帧怎么分桶进前后帧是"行为"——两者由 [ArcRepairFlow]
 * 这座桥缝合（AGENTS 铁律：配置类纯函数必须配桥函数并测桥本身，否则删掉运行时判断分支测试仍绿）。
 */
data class ArcRepairPlan(val srcFps: Int, val dstFps: Int, val srcFrames: Int) {

    /** 输出（保留）帧数，进度换算与引擎循环都用它 */
    val dstFrames: Int = ArcRepairPlan.dstFrameCount(srcFrames, srcFps, dstFps)

    /** 总输出帧数（= [dstFrames]） */
    val totalFrames: Int get() = dstFrames

    companion object {

        /**
         * 输出帧数 = 保留帧数 = |{ j : ⌊j·S/D⌋ < N }| = ⌈N·D/S⌉。
         * - N ≤ 0 → 0；S ≤ D（源不比目标快，没有帧可抽）→ N（恒等直通，调用方应提前拦）
         * - 45@30→24 = 36（每 5 抽 1，抽 9 留 36）；时长账 36/24 = 45/30 严格恒等
         */
        fun dstFrameCount(srcFrames: Int, srcFps: Int, dstFps: Int): Int = when {
            srcFrames <= 0 -> 0
            srcFps <= 0 || dstFps <= 0 || srcFps <= dstFps -> srcFrames
            else -> ((srcFrames.toLong() * dstFps + srcFps - 1L) / srcFps).toInt()
        }

        /** 由源帧数与两档帧率造计划；负值帧数按 0 处理（后台线程不许因越界输入崩） */
        fun of(srcFrames: Int, srcFps: Int, dstFps: Int): ArcRepairPlan =
            ArcRepairPlan(srcFps, dstFps, if (srcFrames < 0) 0 else srcFrames)

        /**
         * 源第 [srcIndex] 帧是否保留：s 保留 ⇔ ⌊⌈s·D/S⌉·S/D⌋ == s（存在输出下标 j 映到它）。
         * 全程 Long 整数域，无浮点；负下标按不保留处理（不该出现，防御）。
         */
        fun isKept(srcIndex: Int, srcFps: Int, dstFps: Int): Boolean {
            if (srcIndex < 0) return false
            if (srcFps <= 0 || dstFps <= 0 || srcFps <= dstFps) return true
            val s = srcIndex.toLong()
            val bigS = srcFps.toLong()
            val bigD = dstFps.toLong()
            val j = (s * bigD + bigS - 1L) / bigS          // ⌈s·D/S⌉
            return j * bigS / bigD == s
        }

        /**
         * 总进度 0f..1f：已处理帧 / 总输出帧。
         * 总帧数 ≤ 0 时返回 0f；结果一律钳到 0..1（引擎在解出真实帧数前会用估算值，可能超界）。
         */
        fun progressOf(doneFrames: Int, totalFrames: Int): Float {
            if (totalFrames <= 0) return 0f
            return (doneFrames.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f)
        }
    }
}

/**
 * CPU 路线的**取大补弧**算子（与 GPU 路线 MAX_MERGE_FS 的 `max(., .)` 同口径）。
 *
 * **acc 就地更新**：`acc[i] = max(acc[i], src[i])`，按无符号字节比（`and 0xFF`，否则 0x80 以上
 * 会被当成负数把亮度比反）。逐样本独立、无跨格依赖，acc 与 src 允许是同一数组。
 * 逐平面调用：调用方按 Y/U/V 分别传入（UV 尺寸是 (w/2)*(h/2)，本对象不关心平面语义）。
 */
object ArcRepairMerge {

    /** `acc[i] = max(acc[i], src[i])`，共 [len] 个样本 */
    fun mergeMax(acc: ByteArray, src: ByteArray, len: Int) {
        for (i in 0 until len) {
            val a = acc[i].toInt() and 0xFF
            val b = src[i].toInt() and 0xFF
            if (b > a) acc[i] = src[i]
        }
    }
}

/**
 * 流式状态机产出的**单帧处置指令**（计划→行为的桥，见 [ArcRepairFlow]）。
 * 引擎（CPU 平面/GPU 纹理两条路）按指令驱动各自的等待原语，决策只在这里做一次。
 */
sealed interface ArcOp {
    /**
     * 处理被抽帧：[fresh] = true 时 acc 整帧换成本帧（自上一保留帧以来第一枚被抽帧，必须
     * **清掉上一轮残留**——acc 面是复用缓冲，带着旧内容取大会把上一次的补弧污染进来）；
     * false 时 acc = max(acc, cur) 继续累积。
     */
    data class AccumulateCur(val fresh: Boolean) : ArcOp

    /** pending = max(pending, acc)：累积补弧并进**前**保留帧 */
    data object FoldAccIntoPending : ArcOp

    /** pending = cur：首枚保留帧直接滞留待发 */
    data object HoldCurAsPending : ArcOp

    /**
     * pending = max(cur, acc)（[useAcc] = 自上一保留帧以来有被抽帧）：**后**保留帧带弧升级，
     * 成为新的待发帧。useAcc 由状态机在仍有 acc 语义的当下给出（执行方不自己判断）。
     */
    data class MergeCurAsPending(val useAcc: Boolean) : ArcOp

    /** 把待发帧写进编码器（[ptsUs] = 均匀目标帧率步长的时间戳，微秒） */
    data class EmitPending(val ptsUs: Long) : ArcOp
}

/**
 * 抽帧计划的**流式执行桥**（AGENTS：配置纯函数必须配桥并测桥）。
 *
 * 解码按源顺序逐帧到来，引擎每帧问一次 [onSourceFrame]、收尾问一次 [onSourceEos]，拿到的
 * 指令序列保证：保留帧恰好发一次、被抽帧只经 acc 并入其前后两枚保留帧、EOS 时尾巴上的
 * 被抽帧并进最后一枚保留帧。一帧滞留（pending）+ 一个累积面（acc）就是全部状态。
 *
 * @param stepUs 均匀 PTS 步长的分子基准：第 j 帧 PTS = j·1e6/dstFps（先乘后除不累积截断）
 */
class ArcRepairFlow(private val plan: ArcRepairPlan, private val dstFps: Int) {

    /** 已发出的保留帧数 = 下一个输出下标（进度与 PTS 都由它派生） */
    var keptCount: Int = 0
        private set

    /** 是否有一枚滞留待发的保留帧 */
    var hasPending: Boolean = false
        private set

    /** 自上一保留帧以来是否累积了被抽帧 */
    var hasAcc: Boolean = false
        private set

    fun onSourceFrame(srcIndex: Int): List<ArcOp> {
        if (!ArcRepairPlan.isKept(srcIndex, plan.srcFps, plan.dstFps)) {
            // 首枚保留帧之前的被抽帧没有"前帧"可并，直接弃（片头黑场语义不受损）
            if (!hasPending) return emptyList()
            val fresh = !hasAcc
            hasAcc = true
            return listOf(ArcOp.AccumulateCur(fresh))
        }
        if (!hasPending) {
            hasPending = true
            return listOf(ArcOp.HoldCurAsPending)
        }
        val ops = buildList {
            if (hasAcc) add(ArcOp.FoldAccIntoPending)
            add(ArcOp.EmitPending(keptCount.toLong() * 1_000_000L / dstFps))
            add(ArcOp.MergeCurAsPending(hasAcc))
        }
        keptCount++
        hasAcc = false
        return ops
    }

    /** 解码 EOS：尾巴上的累积补弧并进最后一枚待发帧后发出 */
    fun onSourceEos(): List<ArcOp> {
        if (!hasPending) return emptyList()
        val ops = buildList {
            if (hasAcc) add(ArcOp.FoldAccIntoPending)
            add(ArcOp.EmitPending(keptCount.toLong() * 1_000_000L / dstFps))
        }
        keptCount++
        hasAcc = false
        hasPending = false
        return ops
    }
}
