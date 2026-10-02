package com.wotagei.cam.record

/**
 * 光弧修复（用户需求第 7 项）的**插帧计划**：把源 [srcFrames] 帧插成 [dstFrames] 帧。
 *
 * v1 口径（主代理定，本文件是它的唯一真源）：
 * - 抽帧后的视频 PTS 是**均匀**的，容器里读不出"哪一帧被抽掉了"，所以"检测 PTS 间隙"这条路走不通；
 *   正解是**帧插值**——把 N fps 插成 2N fps，每对相邻原帧之间重建一帧；
 * - 光弧是**加性亮度**（光轨在长曝光里叠出来的亮线），所以重建帧取「相邻两原帧的加权混合」
 *   在物理上成立，而且比真光流稳（真光流在大面积暗场上极易把光弧 warp 断，反而更花）；
 * - 权重取 **居中（0.5）**：重建帧落在两原帧时间轴的正中，对称重建最不容易在光弧相邻段之间
 *   产生亮度台阶；若偏向任一侧，后续再插帧时会累积成亮度呼吸；
 * - 末帧**不插**（最后没有"下一帧"可混），所以输出 2N−1 帧。
 *
 * v2 预留：把"混合"换成真光流 warp 只需替换 [FrameSource.Blend] 的消费端实现，
 * 计划本身不必改（[FrameSource.Blend] 已经同时给出左原帧下标与权重，warp 还需一路光流场，
 * 那是 v2 在引擎里额外产出的中间量，不是本纯逻辑类的职责）。
 */
data class ArcRepairPlan(val srcFrames: Int, val dstFrames: Int) {

    /** 总输出帧数（= [dstFrames]），进度换算与引擎循环都用它 */
    val totalFrames: Int get() = dstFrames

    companion object {

        /**
         * 输出帧数：**2N−1**（每对相邻原帧插 1 帧、末帧不插）。
         * - N ≤ 0 → 0（没有可处理的帧）
         * - N == 1 → 1（只有末帧，没有相邻对）
         */
        fun dstFrameCount(srcFrames: Int): Int = when {
            srcFrames <= 0 -> 0
            srcFrames == 1 -> 1
            else -> 2 * srcFrames - 1
        }

        /** 由源帧数造计划；负值按 0 处理（后台线程不许因越界输入崩） */
        fun of(srcFrames: Int): ArcRepairPlan {
            val n = if (srcFrames < 0) 0 else srcFrames
            return ArcRepairPlan(n, dstFrameCount(n))
        }

        /**
         * 输出第 [i] 帧的来源（`0 ≤ i < dstFrames`）：
         * - 偶数位 `2k` → 原帧 k；
         * - 奇数位 `2k+1` → 原帧 k 与 k+1 的混合。
         *
         * 因为 dstFrames = 2N−1 恒为奇数，最后一位 2N−2 是偶数 ⇒ **末帧必是原帧**，末帧不插自动成立。
         *
         * 越界输入不抛异常（后台线程的"绝不崩"纪律）：负数按第 0 位处理，超出上界按奇偶公式照算，
         * 调用方（引擎循环）保证只喂合法下标。
         */
        fun sourceAt(i: Int): FrameSource {
            if (i <= 0) return FrameSource.Original(0)
            return if (i % 2 == 0) FrameSource.Original(i / 2)
            else FrameSource.Blend(i / 2, blendWeight())
        }

        /**
         * 混合权重（前一帧所占比例）。v1 定版 **0.5**：重建帧在两原帧时间轴正中，
         * 对称重建对加性光弧最稳（见类注释），且 0.5 在整数域可实现为 (a + b) / 2 的等价权重。
         */
        fun blendWeight(): Float = 0.5f

        /** 原帧 k 在输出里的下标（引擎写均匀 PTS 时用它） */
        fun originalOutIndex(srcIndex: Int): Int = 2 * srcIndex

        /** 「原帧 [leftSrcIndex] 与它下一帧」的混合帧在输出里的下标；末帧没有混合帧，调用方保证 leftSrcIndex < N−1 */
        fun blendOutIndex(leftSrcIndex: Int): Int = 2 * leftSrcIndex + 1

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
 * CPU 路线的两帧平面混合（与 GPU 路线 `ARC_REPAIR_FS` 里的 `mix(., ., 0.5)` 同口径）。
 *
 * **权重只认 [ArcRepairPlan.blendWeight] 这一个真源**（v1 定版 0.5）。之所以用整数 `(a+b+1) shr 1`
 * 而不是浮点乘法，是因为 CPU 路线逐样本跑：1920x1080 一帧要算 300 万个 Y 样本，浮点纯属浪费；
 * 而 `(a + b + 1) shr 1` 就是 0.5 的四舍五入整数实现，与 GPU 的 `(a+b)*0.5` 落在同一档。
 *
 * 逐平面调用：调用方按 Y/U/V 分别传入（UV 尺寸是 (w/2)*(h/2)，本对象不关心平面语义）。
 */
object ArcRepairBlend {

    /**
     * `out[i] = round((a[i] + b[i]) * 0.5)`，共 [len] 个样本。
     *
     * `out` 允许与 `a`/`b` 是同一数组（就地可用）：每格只读本格、只写本格，无跨格依赖。
     * 入参按无符号字节处理（`and 0xFF`），否则 0x80 以上会被当成负数把亮度压暗。
     */
    fun average(a: ByteArray, b: ByteArray, out: ByteArray, len: Int) {
        for (i in 0 until len) {
            out[i] = (((a[i].toInt() and 0xFF) + (b[i].toInt() and 0xFF) + 1) shr 1).toByte()
        }
    }
}

/**
 * 输出帧的来源。[Original] = 原样送编码器的原帧；[Blend] = 由左原帧与其下一帧重建的插入帧。
 */
sealed interface FrameSource {
    /** 源第 [index] 帧原样输出 */
    data class Original(val index: Int) : FrameSource

    /**
     * 源第 [index] 帧与第 [index+1] 帧按 [weight] 混合（[weight] = **前一帧**的权重，
     * 当前帧权重恒为 `1 − weight`）。v1 的 [weight] 一律 0.5。
     */
    data class Blend(val index: Int, val weight: Float) : FrameSource
}
