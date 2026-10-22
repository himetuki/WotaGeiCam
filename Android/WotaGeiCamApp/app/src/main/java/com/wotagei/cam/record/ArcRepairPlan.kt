package com.wotagei.cam.record

import com.wotagei.cam.core.ArcMergeDeadband

/**
 * 光弧修复（用户需求第 7 项）的**抽帧计划**：把实测 srcNum/srcDen（有理数源帧率，见
 * [ArcRateProbe.measuredSrcFps]）的素材抽成 [ArcRepairPlan.dstFps] fps。
 * M6-01（2026-10-12）：源帧率从 Int 换成有理数——整数口径把 29.97 整成 30，保留位次会漂一位
 * （NTSC 案：抽帧点 {4,9,…} → {5,10,…}），把 25.4 整成 25 会误判"无需抽帧"；整数源
 * （den=1）走旧 Int 签名委托，逐点行为零变化（有独立 oracle 扫描钉住）。
 *
 * v2 口径（用户 2026-10-03 定版，本文件是它的唯一真源；v1 的"插帧"被判定为方向性错误废弃）：
 * - 强制 24/25fps 在无固定档设备上传感器实际跑更高帧率（如 30），素材里**多出**的帧要**抽掉**，
 *   输出只含保留帧——**绝不插帧**：抽帧再插帧帧数绕回原点，且混合帧会把运动中的光弧拆成
 *   两道半亮重影，原帧/混合帧逐帧交替正是用户看到的"来回闪动"；
 * - 保留集 = { ⌊j·srcFps/dstFps⌋ : j }（对 30→24 即"每 5 帧抽 1"，抽帧点分布均匀不扎堆）；
 * - 光弧是**加性亮度**（光轨在长曝光里叠出来的亮线），被抽帧的画面用**整像素选择 + 差分
 *   死区**并入它前后两枚保留帧：`Δluma = luma(被抽帧) − luma(保留帧)`，`Δluma > D` 时整颗
 *   像素取被抽帧，否则保持保留帧。被抽帧独有的弧段在两邻帧上重现，弧在抽帧点上不断裂；
 *   D 默认 12（0..32 可调），是差分死区而非绝对亮度阈值，相同亮度的地面/受光不叠加。
 * - **不造色（2026-10-17 缺陷修复）**：CPU 路（本文件的 [ArcRepairMerge.selectInPlace]）按
 *   4:2:0 的 2×2 块整体决策，命中则 4Y+U+V 整块取自被抽帧、否则整块保持保留帧；GPU 路
 *   （`Shaders.ARC_MERGE_FS`）按 RGB 整像素同一规则选择。两路**同口径**，输出每个像素
 *   严格等于某一输入帧的原值。旧口径"CPU 按 YUV 逐平面取大 / GPU 按 RGB 逐通道取大"
 *   会把两个色相拼成原片不存在的颜色（橙+黑→珊瑚粉），已废弃；对账见
 *   `ArcRepairMergeParityTest`。
 * - **已知残差（真机待验）**：GPU 是逐像素整像素选择，但输出经 Surface 交给 H.264 编码器时
 *   默认 4:2:0；一个 2×2 块内可能同时含来自 a 与 b 的像素，编码器做色度抽样时会把两帧的
 *   色度平均 ⇒ **弧边缘仍可能出现 1~2px 的色度混色**（非全画面）。CPU 路按 2×2 块整体搬运，
 *   没有这一层。本轮不把 GPU 改成按块输出（luma 会变块状，得不偿失），按已知残差验收。
 * - **量纲近似（刻意，不是同量纲）**：CPU 用解码器原始 Y 平面（H.264 常见 limited range
 *   16..235、HD 常 BT.709），GPU 用 SurfaceTexture 解码后的 full-range RGB 再按 BT.601 权重
 *   算 luma。中性内容 `ΔGPU ≈ 1.164 × ΔY`，同一 D 在两条路上的**有效门槛约差 1.16 倍**；
 *   彩色内容还叠加矩阵差异。统一量纲要动解码路径，本轮保持近似并在真机验收里观察。
 * - **已知边界**：① 片头首枚保留帧之前的被抽帧无"前帧"可并，直接弃；② 尾部被抽帧只并入
 *   前邻帧（EOS 折叠）；③ 旧取大对非弧场景抬高亮度底、运动亮物出双影的问题由死区收敛，
 *   但高光运动物仍可能整像素过门——真机像素验证待做。
 * - 输出 PTS 取**均匀目标帧率步长**（第 j 帧 = j/ dstFps），容器是真 24/25fps；输出时长
 *   = 保留帧数/dstFps = ⌈N·D/S⌉/D ≈ N/S = 源时长，音频直拷同步不受影响。
 *
 * 抽帧节奏（保留集推导）是"计划"；被抽帧怎么分桶进前后帧是"行为"——两者由 [ArcRepairFlow]
 * 这座桥缝合（AGENTS 铁律：配置类纯函数必须配桥函数并测桥本身，否则删掉运行时判断分支测试仍绿）。
 */
data class ArcRepairPlan(
    val srcNum: Long,
    val srcDen: Long,
    val dstFps: Int,
    val srcFrames: Int
) {

    /** 输出（保留）帧数，进度换算与引擎循环都用它 */
    val dstFrames: Int = ArcRepairPlan.dstFrameCount(srcFrames, srcNum, srcDen, dstFps)

    /** 总输出帧数（= [dstFrames]） */
    val totalFrames: Int get() = dstFrames

    companion object {

        /**
         * 输出帧数 = 保留帧数 = |{ j : ⌊j·S/D⌋ < N }| = ⌈N·D/S⌉（有理数版：S = [srcNum]/[srcDen]）。
         * - N ≤ 0 → 0；S ≤ D（源不比目标快，没有帧可抽）→ N（恒等直通，调用方应提前拦），
         *   判据 `num ≤ D·den` 整数域无浮点
         * - 45@30→24 = 36（每 5 抽 1，抽 9 留 36）；时长账 36/24 = 45/30 严格恒等
         * - 溢出账：`s·D·den ≤ 2e6(MAX_SRC_FRAMES)×25×10⁶ = 5e13 ≪ Long.MAX(9.2e18)`，
         *   `+ num − 1` 的一阶尾差（num ≤ 10⁶）同样无害
         */
        fun dstFrameCount(srcFrames: Int, srcNum: Long, srcDen: Long, dstFps: Int): Int = when {
            srcFrames <= 0 -> 0
            srcNum <= 0L || srcDen <= 0L || dstFps <= 0 ||
                srcNum <= dstFps.toLong() * srcDen -> srcFrames
            else -> ((srcFrames.toLong() * dstFps * srcDen + srcNum - 1L) / srcNum).toInt()
        }

        /** 旧整数签名（v2 口径，只作兼容外壳）：整数源零变化，见有理数版同目标档逐点扫描 */
        fun dstFrameCount(srcFrames: Int, srcFps: Int, dstFps: Int): Int =
            dstFrameCount(srcFrames, srcFps.toLong(), 1L, dstFps)

        /** 由源帧数与两档帧率（有理数源帧率）造计划；负值帧数按 0 处理（后台线程不许因越界输入崩） */
        fun of(srcFrames: Int, srcNum: Long, srcDen: Long, dstFps: Int): ArcRepairPlan =
            ArcRepairPlan(srcNum, srcDen, dstFps, if (srcFrames < 0) 0 else srcFrames)

        /** 旧整数签名（v2 口径，只作兼容外壳）：整数源零变化 */
        fun of(srcFrames: Int, srcFps: Int, dstFps: Int): ArcRepairPlan =
            of(srcFrames, srcFps.toLong(), 1L, dstFps)

        /**
         * 源第 [srcIndex] 帧是否保留：s 保留 ⇔ ⌊⌈s·D/S⌉·S/D⌋ == s（存在输出下标 j 映到它）；
         * S = [srcNum]/[srcDen] 有理数。全程 Long 整数域，无浮点；负下标按不保留处理（不该出现，防御）。
         * 溢出账同 [dstFrameCount]：`s·D·den ≤ 5e13`，`j·num ≤ s·D·den + num ≤ 5.1e13 ≪ Long.MAX`。
         */
        fun isKept(srcIndex: Int, srcNum: Long, srcDen: Long, dstFps: Int): Boolean {
            if (srcIndex < 0) return false
            if (srcNum <= 0L || srcDen <= 0L || dstFps <= 0 ||
                srcNum <= dstFps.toLong() * srcDen) return true
            val s = srcIndex.toLong()
            val bigD = dstFps.toLong()
            val j = (s * bigD * srcDen + srcNum - 1L) / srcNum     // ⌈s·D/S⌉
            return j * srcNum / (bigD * srcDen) == s
        }

        /** 旧整数签名（v2 口径，只作兼容外壳）：整数源零变化 */
        fun isKept(srcIndex: Int, srcFps: Int, dstFps: Int): Boolean =
            isKept(srcIndex, srcFps.toLong(), 1L, dstFps)

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
 * CPU 路线的**整像素选择**算子（4:2:0）；与 GPU 路 `Shaders.ARC_MERGE_FS` 同一规则。
 *
 * 判据：`Δluma = Y_b − Y_a`；块内取 4 个 Y 的最大 Δ（2~4px 细弧不被块平均吃掉），
 * `maxΔ > D`（**严格大于**）时整块（4 个 Y + 1 个 U + 1 个 V）取自 `b`，否则整块保持 `a`。
 * 死区 D 为 [ArcMergeDeadband] 归一的 0..32；**绝不 Y 逐点选、U/V 按块选**——跨帧拼合会造色。
 *
 * **量纲**：`Δ` 直接来自解码器原始 Y（limited range 16..235、HD 常 BT.709），GPU 路则是
 * full-range RGB 的 BT.601 luma；同一 D 在两路的有效门槛约差 1.16 倍，见 [ArcRepairPlan] 的
 * "量纲近似"。这是刻意的近似，不是同量纲。
 *
 * **a 就地更新**：输出写回 `aY/aU/aV`。a 与 b 必须是不同数组（四个真实调用点都是复用缓冲
 * 两两不同）；逐块独立、无跨格权重，整块搬运保证输出三元组严格等于某一帧原值。
 * 调用方负责保证 `width/height` 为偶数（CPU 路线在 `allocPlanes` 里显式报 `ARC_CPU_YUV`）。
 */
object ArcRepairMerge {

    /**
     * `a = select(a, b)`，按 2×2 块整体决策；共 `(width/2)*(height/2)` 个色度样本。
     * @throws IllegalArgumentException 宽高非正/非偶数或平面长度不足（防御性断言，生产路径先验过）
     */
    fun selectInPlace(
        aY: ByteArray, aU: ByteArray, aV: ByteArray,
        bY: ByteArray, bU: ByteArray, bV: ByteArray,
        width: Int, height: Int, deadband: Int
    ) {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "4:2:0 整像素选择要求正偶数宽高，实际 ${width}x$height"
        }
        val cw = width / 2
        val ch = height / 2
        require(aY.size >= width * height && bY.size >= width * height) { "Y 平面长度不足" }
        require(aU.size >= cw * ch && aV.size >= cw * ch) { "a 色度平面长度不足" }
        require(bU.size >= cw * ch && bV.size >= cw * ch) { "b 色度平面长度不足" }
        val d = ArcMergeDeadband.clamp(deadband)

        var c = 0
        var rowStart = 0
        for (by in 0 until ch) {
            var i = rowStart
            for (bx in 0 until cw) {
                val i1 = i + 1
                val i2 = i + width
                val i3 = i2 + 1
                val delta = maxOf(
                    (bY[i].toInt() and 0xFF) - (aY[i].toInt() and 0xFF),
                    (bY[i1].toInt() and 0xFF) - (aY[i1].toInt() and 0xFF),
                    (bY[i2].toInt() and 0xFF) - (aY[i2].toInt() and 0xFF),
                    (bY[i3].toInt() and 0xFF) - (aY[i3].toInt() and 0xFF)
                )
                if (delta > d) {
                    // 整块同帧搬运：4Y + U + V 一起取自 b，杜绝跨帧拼合造色
                    aY[i] = bY[i]
                    aY[i1] = bY[i1]
                    aY[i2] = bY[i2]
                    aY[i3] = bY[i3]
                    aU[c] = bU[c]
                    aV[c] = bV[c]
                }
                i += 2
                c++
            }
            rowStart += width * 2
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
     * **清掉上一轮残留**——acc 面是复用缓冲，带着旧内容参与选择会把上一次的补弧污染进来）；
     * false 时 acc = select(acc, cur) 继续累积（`Δluma > D` 严格大于时取 cur，否则保持 acc）。
     */
    data class AccumulateCur(val fresh: Boolean) : ArcOp

    /** pending = select(pending, acc)：累积补弧并进**前**保留帧 */
    data object FoldAccIntoPending : ArcOp

    /** pending = cur：首枚保留帧直接滞留待发 */
    data object HoldCurAsPending : ArcOp

    /**
     * pending = select(cur, acc)（[useAcc] = 自上一保留帧以来有被抽帧）：**后**保留帧带弧升级，
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
 * 两个入口同一台机器：
 * - [onSourceFrame]（离线路）：保留与否由 [ArcRepairPlan.isKept] 按计划判；
 * - [onClassification]（录制在线路）：保留与否由引擎的实时节奏判（到达时刻 vs 应到时刻），
 *   录制侧没有"先知帧率"，计划公式用不上。
 *
 * @param dstFps 均匀 PTS 步长的分母：第 j 帧 PTS = j·1e6/dstFps（先乘后除不累积截断）
 */
class ArcRepairFlow private constructor(
    private val dstFps: Int,
    private val plannedKeep: ((Int) -> Boolean)?
) {

    /** 离线路：按抽帧计划逐源帧问询（保留判定吃有理数真值，见 M6-01） */
    constructor(plan: ArcRepairPlan) : this(
        plan.dstFps,
        { s -> ArcRepairPlan.isKept(s, plan.srcNum, plan.srcDen, plan.dstFps) }
    )

    /** 录制在线路：dstFps 只喂 PTS，保留决策走 [onClassification] */
    constructor(dstFps: Int) : this(dstFps, null)

    /** 已发出的保留帧数 = 下一个输出下标（进度与 PTS 都由它派生） */
    var keptCount: Int = 0
        private set

    /** 是否有一枚滞留待发的保留帧 */
    var hasPending: Boolean = false
        private set

    /** 自上一保留帧以来是否累积了被抽帧 */
    var hasAcc: Boolean = false
        private set

    /** 离线路：按抽帧计划逐源帧问询 */
    fun onSourceFrame(srcIndex: Int): List<ArcOp> =
        onClassification(plannedKeep?.invoke(srcIndex) ?: true)

    fun onClassification(kept: Boolean): List<ArcOp> {
        if (!kept) {
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
