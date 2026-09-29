package com.wotagei.cam.ui.anim

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.WotaMotion
import com.wotagei.cam.ui.design.WotaStroke
import kotlin.math.hypot
import kotlin.math.pow

/**
 * 「水滴融入 / 细胞分裂」的几何层（docs/plan/13 第 3 条 + docs/plan/12 提示词 3/4）。
 *
 * 技术路径按计划第四节定死：**几何插值**画「两圆 + 两条公切贝塞尔」的连通体，腰宽是圆心距的函数，
 * 圆心距超过阈值时腰收为 0 并停止绘制。不走经典 metaball（模糊 + 高对比 alpha 阈值）：Android 的
 * `RenderEffect` 没有 color matrix，且 minSdk 29 / 主测机 API 30 上 `RenderEffect` 根本不可用。
 *
 * 本文件是**可复用组件**：B4「拖拽要有动感」要拿同一套连通体几何。那时两圆就是"拖起中的控件"与
 * "它的原位"，接口里的圆心与半径全部由外部喂实测值，不绑定底栏。
 *
 * 四条纪律（由结构与类型保证，不靠自觉）：
 * - 绘制层每帧 **Path 分配次数 0**：`Path` 与 `Stroke` 在 [wotaPillHost] 的**组合期**各 `remember` 一次，
 *   描边宽度也在组合期用 LocalDensity 换算成 px。这里**不依赖** `drawWithCache` 的缓存语义——它的节点
 *   实现 `ObserverModifierNode`，`onObservedReadsChanged → invalidateDrawCache`，所以"外块只在尺寸变化时
 *   执行"不成立（审查 S3-4）；把对象提到组合期才是结构性保证。每帧只 `reset()` + 往同一个 Path 上追加
 *   moveTo/cubicTo/close（半圆用贝塞尔逼近，因此**不需要** `addArc(Rect)`，也就不会在绘制阶段构造
 *   Rect/Offset 一类对象）。真机 profiler 的运行时计数仍列「未验」。
 * - 绘制层读 [progress] 只发生在 draw / graphicsLayer 阶段：快照状态在绘制期被读只让该层重画，
 *   **不触发重组**，预览层不会被重排（08:79 帧率红线）。
 * - 只作用 translation / scale / alpha / path，**不动任何布局参数**：两颗控件的槽位宽度全程不变，
 *   底栏不因动画重排，快门也就不跳。
 * - 实测位置由布局期回调写进 [MergeScene] 的普通 Float 字段（不是快照状态）：写它不重组、读它不重组。
 */

/**
 * 动效档 → 本效果的落地方案（纯函数，JVM 可测）。
 *
 * PLAIN 档必须是**直接切换**：`drawWaist = false` 让绘制层一条路径都不建（腰分支根本不进入），
 * `travel = false` 让控件本体零位移，`animated = false` 让调用点**不创建**动画状态、进度直接取 0/1。
 * 「确实消失而不是变快」的证据是下面那四条 plan→行为桥（[mergeProgressOf] / [chipTravelPxOf] /
 * [waistVisibleFor] / [linkAlphaFor]）：PLAIN 走哪条分支由它们决定，调用点不许各自 `if (plan.animated)` 散写。
 */
data class MergePlan(
    val animated: Boolean,
    val drawWaist: Boolean,
    val travel: Boolean,
    /**
     * 本效果的时长预算，只用来把「预算 = 令牌 [WotaMotion.COMMIT_MS]、PLAIN 档为 0」这件事带进用例。
     * **运行时无消费方**（实际时长由 `MotionSpec.float` 决定），照实写在这里（审查 S4-2 点名过）。
     */
    val durationMs: Int
)

fun mergePlanFor(mode: MotionMode): MergePlan = when (mode) {
    MotionMode.PLAIN -> MergePlan(animated = false, drawWaist = false, travel = false, durationMs = 0)
    // LIQUID 走弹簧（MotionSpec.float），没有固定时长；这里给同一预算作参考上限
    else -> MergePlan(animated = true, drawWaist = true, travel = true, durationMs = WotaMotion.COMMIT_MS)
}

/**
 * 「档位 → 进度取法」的唯一桥（审查 S4-1/S4-2 要的 plan→行为可测点）。
 *
 * 调用点按 [MergePlan.animated] 决定**有没有创建**动画状态，再把它的值（没创建就传 null）喂进来。
 * 本函数保证 PLAIN 的返回值只取决于 `recording`、与 `animatedValue` 无关：万一动画状态没删干净，
 * 进度也不会走进中间态。用例钉的就是这条——把 `if (plan.animated)` 删掉，PLAIN 的用例立刻红。
 */
fun mergeProgressOf(plan: MergePlan, recording: Boolean, animatedValue: Float?): Float =
    if (plan.animated) {
        animatedValue ?: (if (recording) 1f else 0f)
    } else {
        if (recording) 1f else 0f
    }

/** 「档位 → 本体位移」桥：PLAIN 一律零位移，调用点不各自写 travel 判断 */
fun chipTravelPxOf(plan: MergePlan, progress: Float, deltaPx: Float, clearancePx: Float): Float =
    if (plan.travel) LiquidMerge.chipTravelPx(progress, deltaPx, clearancePx) else 0f

/** 「档位 → 这一帧画不画腰」桥 */
fun waistVisibleFor(plan: MergePlan, progress: Float): Boolean =
    plan.drawWaist && progress > 0f && LiquidMerge.linkAlpha(progress) > 0.001f

/** 「档位 → 连通体不透明度」桥：[waistVisibleFor] 不成立就是 0，绘制层第一条就 return（不重复算一次腰宽） */
fun linkAlphaFor(plan: MergePlan, progress: Float): Float =
    if (waistVisibleFor(plan, progress)) LiquidMerge.linkAlpha(progress) else 0f

/** 连通体的形状参数：都是"算法自己的常数"，既不是机型数值也不是观感令牌 */
object LiquidMerge {

    /**
     * 腰断开阈值系数：圆心距 ≥ (rA + rB) × 本系数 时腰收为 0，连通体停止绘制。
     *
     * 取 0.7 的依据是底栏的真实几何（宽度账见 [MergeSlot]）：镜头那颗的圆心距 68.5dp、半径 15dp，
     * 录制键半径 25dp → 阈值 = (15+25)×0.7 = **28dp** ≈ 0.41 倍行程。于是分裂时先"拉出腰 → 变细 →
     * 在四成行程处掐断"，断开的液滴再自由飞回原位；融合时反过来（液滴离键 28dp 以内才长出腰，
     * 越近越粗，最后并成一体）。12 号包提示词 6 说的"融合感不够就调这个数"调的就是它。
     */
    const val CUT_FACTOR = 0.7f

    /** 腰宽收窄指数：>1 = 近处几乎不变粗（看着像一整滴），远处迅速收细（断得干脆） */
    const val WAIST_EXPONENT = 1.35f

    /** 腰半径低于这个像素值就当已断开：不画亚像素碎线，也避开贝塞尔自交抖动 */
    const val MIN_WAIST_PX = 0.6f

    /** 两圆心距小于这个像素值时不画（终点态重合，且此时整体淡出已经到 0） */
    const val MIN_DIST_PX = 1f

    /** 进度过这一点后连通体整体淡出：材料已进了录制键，不该在按钮里留一枚暗盘 */
    const val LINK_FADE_FROM = 0.72f

    /** 控件本体的淡出区间（归一化进度）：液滴从原位分离之后本体才开始消失 */
    const val CHIP_FADE_FROM = 0.18f
    const val CHIP_FADE_END = 0.9f

    /** 控件本体被"吸"时的最大缩小量 */
    const val CHIP_SHRINK = 0.28f

    /**
     * 本体位移占圆心距的比例（上限还要再被 [chipTravelPx] 的 clearance 夹一次）。
     * 取 0.16 而不是更大的值是因为底栏的真实余量很小：镜头那颗填满 63dp 槽位，它的近缘离录制键
     * 触摸盒只有 `G − R/2 + ...`≈12dp（120% 字体缩放时≈3.6dp），位移超过这个空隙就会**盖住录制键的
     * 命中区**——录制中点不停止录制是不可接受的，比动画好不好看重要得多。
     */
    const val TRAVEL_FRACTION = 0.16f

    /** 单段 90° 圆弧的三次贝塞尔逼近系数（标准值 0.5523）；两段拼半圆，误差 <0.02% 半径 */
    const val KAPPA = 0.5523f

    /**
     * 90° 圆弧的控制柄长度 = KAPPA × 半径。写成函数而不是在调用处 `KAPPA * r` 再乘一次半径，
     * 是为了让 JVM 用例能钉住它的**线性**关系（半径 2 倍 → 柄长 2 倍；写成 `r·(ŝ+h·q̂)` 就是 r² 倍，
     * 圆帽会炸开）。
     */
    fun capHandlePx(radiusPx: Float): Float = KAPPA * radiusPx

    /** 液滴终点半径占录制键半径的比例：小一圈才像"被吞进去"，而不是把按钮糊住 */
    const val BLOB_TARGET_RATIO = 0.5f

    /** 圆心距 → 腰半径：d=0 最粗（= min(rA, rB)），d→阈值 收到 0 */
    fun waistRadiusPx(distPx: Float, rA: Float, rB: Float, cutPx: Float): Float {
        if (rA <= 0f || rB <= 0f || cutPx <= 0f) return 0f
        if (distPx >= cutPx) return 0f
        val t = (distPx / cutPx).coerceIn(0f, 1f)
        val base = if (rA < rB) rA else rB
        return base * (1f - t).pow(WAIST_EXPONENT)
    }

    /** 断开阈值（圆心距）；任一半径为 0（那颗没显示）时返回 0 = 不画 */
    fun cutDistancePx(rA: Float, rB: Float): Float =
        if (rA <= 0f || rB <= 0f) 0f else (rA + rB) * CUT_FACTOR

    /** 这一段到底画不画：把阈值逻辑暴露出来，供用例直接判读，不埋在绘制代码里 */
    fun linkVisible(distPx: Float, rA: Float, rB: Float): Boolean =
        distPx >= MIN_DIST_PX && waistRadiusPx(distPx, rA, rB, cutDistancePx(rA, rB)) > MIN_WAIST_PX

    /**
     * 贝塞尔控制点的法向偏移：令 t=0.5 处宽度正好等于腰宽。
     * 对称布点时 B(0.5) = (rA + 6c + rB)/8 ⇒ c = (8·腰 − rA − rB)/6。
     * 腰很细时 c 变负 = 两侧曲线跨过中轴，与两圆的并集一起看就是"掐断前的细颈"。
     */
    fun controlOffsetPx(waistPx: Float, rA: Float, rB: Float): Float =
        (8f * waistPx - rA - rB) / 6f

    /** 连通体整体不透明度：到 [LINK_FADE_FROM] 之前一直是 1，之后线性到 0 */
    fun linkAlpha(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        if (p <= LINK_FADE_FROM) return 1f
        return ((1f - p) / (1f - LINK_FADE_FROM)).coerceIn(0f, 1f)
    }

    /** 控件本体不透明度：液滴刚分离就开始消失；终点必须是 0（吸干净） */
    fun chipAlpha(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        if (p <= CHIP_FADE_FROM) return 1f
        if (p >= CHIP_FADE_END) return 0f
        return (CHIP_FADE_END - p) / (CHIP_FADE_END - CHIP_FADE_FROM)
    }

    fun chipScale(progress: Float): Float = 1f - CHIP_SHRINK * progress.coerceIn(0f, 1f)

    /**
     * 本体被"吸"的位移（受 clearance 夹住，见下）。
     * **带符号**：入参是 `录制键中心 − 那颗中心`，左边的颗得往右移（正）、右边的颗往左移（负），
     * 取绝对值就会把镜头那颗反着推出去。
     *
     * `clearancePx` = 那颗的近缘与**录制键触摸盒**之间的实测空隙（调用方从布局矩形算）。位移被它夹住是
     * 硬要求：那颗淡出之后命中区还在（alpha = 0 仍可点），越过这个空隙就吃掉"停止录制"那一指。
     * 底栏真实数值：圆心距 68.5~83dp，而镜头那颗 63dp 宽填满槽位、近缘离触摸盒只剩 ≈12dp
     * （120% 字体缩放只剩 ≈3.6dp）——**clearance 才是主约束**，只按圆心距取比例一定越界
     * （本仓第一版就把圆心距当成 100dp 算过，实际只有 68.5dp，用例改回来后才发现余量根本不够）。
     */
    fun chipTravelPx(progress: Float, deltaPx: Float, clearancePx: Float): Float {
        val cap = if (clearancePx > 0f) clearancePx else 0f
        val want = deltaPx * TRAVEL_FRACTION
        val bounded = if (want > cap) cap else if (want < -cap) -cap else want
        return progress.coerceIn(0f, 1f) * bounded
    }

    /**
     * 那颗的近缘与录制键触摸盒之间还剩多少空隙（重叠或贴边返回 0）。
     * 两边都按**位移轴上的半宽**算，不按内切圆半径算——半径会把余量高估一倍以上。
     */
    fun clearancePx(chipCenter: Float, chipHalf: Float, recordCenter: Float, recordHalf: Float): Float {
        val gap = if (chipCenter <= recordCenter) {
            (recordCenter - recordHalf) - (chipCenter + chipHalf)
        } else {
            (chipCenter - chipHalf) - (recordCenter + recordHalf)
        }
        return if (gap > 0f) gap else 0f
    }

    /** 液滴半径：从那颗的原位半径过渡到录制键半径的一小半 */
    fun blobRadius(progress: Float, rHome: Float, rRecord: Float): Float =
        rHome + (rRecord * BLOB_TARGET_RATIO - rHome) * progress.coerceIn(0f, 1f)

    /** 液滴圆心：原位与录制键中心的线性插值（进度 0 = 原位，1 = 重合） */
    fun blobX(progress: Float, homeX: Float, recordX: Float): Float =
        homeX + (recordX - homeX) * progress.coerceIn(0f, 1f)

    fun blobY(progress: Float, homeY: Float, recordY: Float): Float =
        homeY + (recordY - homeY) * progress.coerceIn(0f, 1f)
}

/**
 * 槽位宽度的算式（两个不变量同时成立的唯一写法，纯函数好测）：
 * 底板宽 = 2 × max(左槽实测宽, 右槽实测宽) + 录制键宽 + 2 × (条目间距 + 底板内边距)。
 * 左右两槽**等宽** ⇒ 录制键由 `Alignment.Center` 落在底板正中；底板再在可视窗口里居中，快门也就
 * 正落在可视水平中心（居中基准见 CameraScreen 底栏那层，S3-3）。两颗各自显示与否只改变 S 的大小、
 * 不改变"两槽等宽"，所以快门不跳；底板又只包住内容，不是贴底通栏条。
 */
object MergeSlot {

    /**
     * 可达档位只有两档（审查定版：缩略图**没有** `CamPill` 开关位，快门/缩略图/设置入口不可隐藏）：
     * · 镜头开 → S = max(34, 63) = 63（素材有/无都在同一枚 34dp 方框里画，槽宽不变）
     * · 镜头关 → S = max(34, 0) = 34
     *
     * @param leftPx 左槽内容实测宽（缩略图那颗）；恒为 34dp 那一档，传 0 只是**纯函数边界**（当前 UI 走不到）
     * @param rightPx 右槽内容实测宽（镜头那颗）；0 = 被 `CamPill.LENS` 关掉
     */
    fun slotWidthPx(leftPx: Float, rightPx: Float): Float =
        if (leftPx > rightPx) leftPx else rightPx

    /** 底板全宽（含内边距）。gap 与 pad 由调用方从令牌换算成 px，这里不出现 dp 字面量 */
    fun dockWidthPx(slotPx: Float, recordPx: Float, gapPx: Float, padPx: Float): Float =
        2f * slotPx + recordPx + 2f * gapPx + 2f * padPx
}

/**
 * 一次融合动画的现场：布局期写进来的实测矩形（**窗口**坐标 + 绘制层自己的窗口原点，普通 Float 字段）。
 *
 * 为什么绕一圈用窗口坐标相减，而不是直接 `positionInParent()`：绘制层挂在底板节点上，而底板带 `padding`
 * 与 `wotaCard` 的 clip，中间还夹着 PaddingModifier 这层布局节点——"parent" 到底是哪一层、原点是否被
 * 内边距挪过，跨版本不可依赖（§69/§70 那族"浮层甩到屏幕原点"的错就是坐标语义猜错造成的）。
 * 子节点的 `onGloballyPositioned` 一定在底板之前回调、底板一定在本帧布局收尾前回调，而读这些值是**绘制阶段**
 * （布局之后），所以同一帧内两者必然一致，不会用到半新的坐标。
 *
 * 故意不用快照状态：布局期写它不触发重组，绘制期读它也不触发；只有进度那条状态被绘制层读，
 * 才让这一层重画。
 */
class MergeScene {

    /** 绘制层宿主节点（那枚底板）自己的窗口原点与实测尺寸 */
    var canvasLeft = 0f
    var canvasTop = 0f
    var canvasWidth = 0f
    var canvasHeight = 0f

    var thumbLeft = 0f
    var thumbTop = 0f
    var thumbWidth = 0f
    var thumbHeight = 0f

    var lensLeft = 0f
    var lensTop = 0f
    var lensWidth = 0f
    var lensHeight = 0f

    var recordLeft = 0f
    var recordTop = 0f
    var recordWidth = 0f
    var recordHeight = 0f

    /**
     * 布局期回报：入参是节点在窗口里的左上角与尺寸，全程零对象分配。
     * 未知槽位直接抛（审查 S4-5）：这里的 target 全是本文件的常量，第五个值只能是新增槽位时漏改分支，
     * 静默走录制键那一份就会让 B4 拿到"看起来合理"的错数据。
     */
    fun put(target: Int, left: Float, top: Float, width: Float, height: Float) {
        when (target) {
            THUMB -> { thumbLeft = left; thumbTop = top; thumbWidth = width; thumbHeight = height }
            LENS -> { lensLeft = left; lensTop = top; lensWidth = width; lensHeight = height }
            RECORD -> { recordLeft = left; recordTop = top; recordWidth = width; recordHeight = height }
            CANVAS -> { canvasLeft = left; canvasTop = top; canvasWidth = width; canvasHeight = height }
            else -> throw IllegalArgumentException("未知融合槽位：$target")
        }
    }

    /**
     * 该槽当前的矩形面积：0 = 那颗没组合（被 `CamPill` 关掉），绘制层据此不画腰也不留空壳。
     * CANVAS 走的是自己回报的实测尺寸，**不是**借用录制键那一份（S4-5 修的正是这条静默 fallback）。
     */
    fun area(target: Int): Float = when (target) {
        THUMB -> thumbWidth * thumbHeight
        LENS -> lensWidth * lensHeight
        RECORD -> recordWidth * recordHeight
        CANVAS -> canvasWidth * canvasHeight
        else -> throw IllegalArgumentException("未知融合槽位：$target")
    }

    /** 中心 X（换算到绘制层坐标系；每帧只是几次加减，不分配） */
    fun cx(target: Int): Float = rawLeft(target) + rawWidth(target) * 0.5f - canvasLeft

    fun cy(target: Int): Float = rawTop(target) + rawHeight(target) * 0.5f - canvasTop

    /** 内切圆半径：短边一半（胶囊 63×30 → 15dp；缩略图 34×34 → 17dp；录制键 50×50 → 25dp） */
    fun radius(target: Int): Float {
        val w = rawWidth(target)
        val h = rawHeight(target)
        return (if (w < h) w else h) * 0.5f
    }

    /**
     * 位移轴上的半宽（胶囊 63×30 → 31.5，不是内切圆半径 15）。
     * 算"那颗离录制键触摸盒还剩多少空隙"必须用它，用半径会把余量高估一倍以上。
     */
    fun halfWidth(target: Int): Float = rawWidth(target) * 0.5f

    private fun rawLeft(target: Int): Float = when (target) {
        THUMB -> thumbLeft
        LENS -> lensLeft
        CANVAS -> canvasLeft
        else -> recordLeft
    }

    private fun rawTop(target: Int): Float = when (target) {
        THUMB -> thumbTop
        LENS -> lensTop
        CANVAS -> canvasTop
        else -> recordTop
    }

    private fun rawWidth(target: Int): Float = when (target) {
        THUMB -> thumbWidth
        LENS -> lensWidth
        CANVAS -> canvasWidth
        else -> recordWidth
    }

    private fun rawHeight(target: Int): Float = when (target) {
        THUMB -> thumbHeight
        LENS -> lensHeight
        CANVAS -> canvasHeight
        else -> recordHeight
    }

    companion object {
        const val THUMB = 0
        const val LENS = 1
        const val RECORD = 2
        const val CANVAS = 3
    }
}

/** 把控件的实测矩形回报进 [scene]（窗口坐标，由绘制层减去宿主原点） */
fun Modifier.mergeAnchor(scene: MergeScene, target: Int): Modifier =
    onGloballyPositioned { coords ->
        val p = coords.positionInWindow()
        val s = coords.size
        scene.put(target, p.x, p.y, s.width.toFloat(), s.height.toFloat())
    }

/**
 * 连通体绘制层：画在**底板之上、两颗控件之下**（挂在那个容器的节点上：容器的 `wotaCard` 在链路更外侧
 * 所以先画，子节点后画 → 盖住腰的两端；容器自身的 clip 又把连通体拦在胶囊里，溢不出底栏）。
 *
 * 材料沿用 `wotaCard` 那一套令牌（[WotaColor.hudScrim] 填充 + [WotaColor.acrylicBorder] 描边，
 * 描边宽度 [WotaStroke.hairline] 与卡片边同一档），不加模糊、不加投影。
 *
 * 之所以是 `@Composable`：[Path] 与 [Stroke] 必须在**组合期** remember（审查 S3-4）。留在
 * `drawWithCache` 的外块里就仍受缓存语义支配（外块会因被观察的读取变化而重跑，"每帧零分配"当时只是
 * 断言）；提到组合期之后，每帧只 `reset()` 复用同一个对象，零分配由结构保证。
 *
 * @param plan 档位方案：画不画腰由 [waistVisibleFor] 决定，PLAIN 档一条路径都不描
 * @param progress 归一化进度（0 = 两颗在原位，1 = 已被录制键吸收）；只在绘制阶段读，不触发重组
 */
// lint 的 ComposableModifierFactory 建议改用 `composed`，而 composed 早就是废弃 API（每次重组都新建
// 节点链，比 Composable 工厂更差）。审查 S3-4 要的正是"对象在组合期建"，所以按现状落地并就地标注理由
@SuppressLint("ComposableModifierFactory")
@Composable
fun Modifier.wotaPillHost(
    scene: MergeScene,
    plan: MergePlan,
    progress: () -> Float
): Modifier {
    val strokeWidthPx = with(LocalDensity.current) { WotaStroke.hairline.toPx() }
    val link = remember { Path() }
    val edge = remember(strokeWidthPx) { Stroke(width = strokeWidthPx, cap = StrokeCap.Round) }
    return this then Modifier.drawWithCache {
        onDrawBehind {
            val p = progress()
            val alpha = linkAlphaFor(plan, p)
            if (alpha <= 0f) return@onDrawBehind
            if (scene.area(MergeScene.RECORD) <= 0f) return@onDrawBehind
            val rcx = scene.cx(MergeScene.RECORD)
            val rcy = scene.cy(MergeScene.RECORD)
            val rRec = scene.radius(MergeScene.RECORD)
            link.reset()
            var drew = false
            if (scene.area(MergeScene.THUMB) > 0f) {
                drew = appendChipLink(
                    link, scene.cx(MergeScene.THUMB), scene.cy(MergeScene.THUMB), scene.radius(MergeScene.THUMB),
                    rcx, rcy, rRec, p
                )
            }
            if (scene.area(MergeScene.LENS) > 0f) {
                drew = appendChipLink(
                    link, scene.cx(MergeScene.LENS), scene.cy(MergeScene.LENS), scene.radius(MergeScene.LENS),
                    rcx, rcy, rRec, p
                ) || drew
            }
            if (!drew) return@onDrawBehind
            // 两枚液滴的连通体在同一个 Path 的同一个子路径族里，一次 drawPath → 一次混合，重叠处不叠暗缝
            drawPath(link, WotaColor.hudScrim, alpha = alpha)
            drawPath(link, WotaColor.acrylicBorder, alpha = alpha, style = edge)
        }
    }
}

/**
 * 一颗控件被吸收的整条连通体：`原位圆 ↔ 液滴` 与 `液滴 ↔ 录制键圆` 两段，追加进同一个 [Path]。
 * 每帧只在既有 Path 上追加线段，不新建对象。
 */
private fun appendChipLink(
    path: Path,
    homeX: Float,
    homeY: Float,
    rHome: Float,
    recordX: Float,
    recordY: Float,
    rRecord: Float,
    progress: Float
): Boolean {
    val bx = LiquidMerge.blobX(progress, homeX, recordX)
    val by = LiquidMerge.blobY(progress, homeY, recordY)
    val br = LiquidMerge.blobRadius(progress, rHome, rRecord)
    val a = path.appendLiquidLink(homeX, homeY, rHome, bx, by, br)
    val b = path.appendLiquidLink(bx, by, br, recordX, recordY, rRecord)
    return a || b
}

/**
 * 把「两圆 + 两条公切贝塞尔」的连通体追加到 [this]：单个闭合子路径，Winding 填充 → 一次混合。
 * （若改成"分别画两圆再画腰"，三笔半透明会互相叠出暗缝，正是这里要避开的。）
 *
 * 半圆帽用两段 90° 贝塞尔逼近，于是这条路径**只含 moveTo / cubicTo / close**：
 * 不需要 `addArc(Rect)`，绘制阶段也就不会构造任何 Rect/Offset。
 *
 * @return 有没有追加（圆心距过近、半径为 0、腰已断开时返回 false 且不写 path）
 */
fun Path.appendLiquidLink(
    ax: Float,
    ay: Float,
    ar: Float,
    bx: Float,
    by: Float,
    br: Float
): Boolean {
    if (ar <= 0f || br <= 0f) return false
    val dx = bx - ax
    val dy = by - ay
    val dist = hypot(dx, dy)
    if (dist < LiquidMerge.MIN_DIST_PX) return false
    val waist = LiquidMerge.waistRadiusPx(dist, ar, br, LiquidMerge.cutDistancePx(ar, br))
    if (waist <= LiquidMerge.MIN_WAIST_PX) return false
    // 单位方向 u 与法向 p（屏幕坐标 y 向下，p = u 转 90°）
    val ux = dx / dist
    val uy = dy / dist
    val px = -uy
    val py = ux
    val c = LiquidMerge.controlOffsetPx(waist, ar, br)
    val x1 = ax + dx / 3f
    val y1 = ay + dy / 3f
    val x2 = ax + dx * 2f / 3f
    val y2 = ay + dy * 2f / 3f
    // 上侧公切贝塞尔：A 的 +p 切点 → B 的 +p 切点
    moveTo(ax + px * ar, ay + py * ar)
    cubicTo(x1 + px * c, y1 + py * c, x2 + px * c, y2 + py * c, bx + px * br, by + py * br)
    // B 的半圆帽：+p → +u → −p（绕过背离 A 那一侧）
    appendHalfCap(bx, by, br, px, py)
    // 下侧公切贝塞尔：B 的 −p 切点 → A 的 −p 切点
    cubicTo(x2 - px * c, y2 - py * c, x1 - px * c, y1 - py * c, ax - px * ar, ay - py * ar)
    // A 的半圆帽：−p → −u → +p（回到起点）
    appendHalfCap(ax, ay, ar, -px, -py)
    close()
    return true
}

/**
 * 追加半圆（两段 90° 贝塞尔）。入参是圆心、半径与**起点单位向量** s。
 *
 * 递推只用加减与乘法：每转 90° 的单位向量取 `(sy, −sx)`，而 90° 弧在该点的切向恰好也等于它；
 * 控制柄长度 [capHandlePx] 是**半径的一次函数**（0.5523 × r），所以这里写成 `r·ŝ + h·q̂` 两项相加，
 * 不能写成 `r·(ŝ + h·q̂)`——那会把 h 再乘一次半径，圆帽直接炸成巨大线圈。
 * 没有三角函数，没有对象分配。
 */
private fun Path.appendHalfCap(cx: Float, cy: Float, r: Float, sx: Float, sy: Float) {
    val h = LiquidMerge.capHandlePx(r)
    // 第一段：s → q = (sy, −sx)
    val qx = sy
    val qy = -sx
    cubicTo(
        cx + r * sx + h * qx, cy + r * sy + h * qy,
        cx + r * qx + h * sx, cy + r * qy + h * sy,
        cx + r * qx, cy + r * qy
    )
    // 第二段：q → n = (qy, −qx) = −s
    val nx = qy
    val ny = -qx
    cubicTo(
        cx + r * qx + h * nx, cy + r * qy + h * ny,
        cx + r * nx + h * qx, cy + r * ny + h * qy,
        cx + r * nx, cy + r * ny
    )
}

/**
 * 常驻读数的换行档（六项第 4 条搬到录制键右侧之后）。
 *
 * 一颗读数的宽度 = 左右内边距 12+12 + 文本下限 3 字 + 与副标签间隔 5 + 副标签，
 * 全部来自 `WotaChip` 与 `WotaType`，这里不散写观感值：100% 90dp、120% 108dp。
 * 整块宽度（含块内左右内边距 6+6，见 [hudRoomDp]）：
 * · 一行 3 颗：100% 294dp、120% 348dp（「感光度 AUTO / 白平衡 5500K / 变焦 1.0x」这一组就这个量级）
 * · 一行 2 颗：100% 198dp、120% 234dp
 *
 * 入参 [roomWidthDp] 必须是**调用点同一个表达式**算出的可用宽（[hudRoomDp]：安全区实测宽 − 设计留白 8
 * − 块内左右内边距 12），不许直接塞 `screenWidthDp`（审查 S3-5：B2 那一版用例传 352、调用点传 360/800，
 * 两边判的不是同一条不等式）。竖屏 360dp 上可用 = 340：100% 三颗要 282 + 24 = 306 → 取 3，
 * 110% 要 309 + 24 = 333 → 仍取 3（旧写法替挖孔多扣 34dp 只剩 314，这一档会白退一级），
 * 115% 要 322.5 + 24 = 346.5 → 退到 2。
 * （旧写法扣的那笔 34dp 右缘避让来自"可视右缘 1532"那条伪事实，竖屏侧边根本没有不可视带；
 * 避让已经由调用方的 safeDrawingPadding() 算进 [hudRoomDp] 的入参里，这里不再扣第二笔，见 #68。）
 * 字宽是算术估计（汉字按 1 em、拉丁按 0.6 em）不是量出来的，所以取档必须带安全余量 [PerRowMarginDp]；
 * §58/§73/§70 三次翻车都死在这半成的余量上。取不到的档位退到下一档，这是"小屏优先"的算术。
 */
fun hudPerRowFor(chipCount: Int, fontScale: Float, roomWidthDp: Float): Int {
    if (chipCount <= 0) return 1
    val scale = if (fontScale < 1f) 1f else fontScale
    val chip = (ChipMinTextDp + ChipPaddingDp + ChipGapDp + ChipSecondaryDp) * scale
    val three = 3f * chip + 2f * HudRowGapDp
    val two = 2f * chip + HudRowGapDp
    if (three + PerRowMarginDp <= roomWidthDp) return if (chipCount >= 3) 3 else chipCount
    return if (two + PerRowMarginDp <= roomWidthDp) 2 else 1
}

/**
 * 读数块的可用横向宽度 = **安全区实测宽** − 那枚设计留白 − 块自身左右内边距（[HudBlockPadDp] 各一份）。
 *
 * 抽成函数只为了一个目的：调用点与用例喂 [hudPerRowFor] 的是**同一个表达式**（S3-5）。
 *
 * - [safeWidthDp]：调用方那层 `safeDrawingPadding()` 盒子的**实测宽**（本机横屏 766dp 而不是 800dp：
 *   挖孔让掉的那条边已经算在里面了，所以这里不再按方向扣第二笔，见 docs/plan/13 §九·补 / 任务 #68）。
 * - [endPadDp]：读数块原生对齐 `padding(end=)` 用的那枚**设计留白**，两页都传 `HudEdgePad`（8dp 令牌，
 *   与左竖 Dock 的起始边同一枚）。它不是避让量：不分方向、不分姿态；改了它这条宽度账跟着改（同源）。
 */
fun hudRoomDp(safeWidthDp: Float, endPadDp: Float): Float =
    (safeWidthDp - endPadDp - 2f * HudBlockPadDp).coerceAtLeast(0f)

/**
 * 读数块高度的**预测初值**（审查 S3-6）：首帧实测之前先按「行数 × 一颗胶囊高 + 行距 + 块内上下边距」估，
 * 免得右竖 Dock 的下边界第一帧按 0 算、最低那颗落在读数块的位置上叠一帧。
 *
 * 一颗读数胶囊 = [WotaType.chip] 的 lineHeight 18sp（随字体缩放）+ `WotaChip` 上下内边距 6+6
 * ⇒ 默认 3 读数一行时 12 + 30 = 42dp，与实测同量级。「AE 已锁定」那行提示不计入预测（它一出现
 * 下一帧实测就跟上），这是预测不是结论。
 */
fun hudStripHeightDp(itemCount: Int, perRow: Int, fontScale: Float): Float {
    if (itemCount <= 0) return 0f
    val columns = if (perRow < 1) 1 else perRow
    val rows = (itemCount + columns - 1) / columns
    val scale = if (fontScale < 1f) 1f else fontScale
    val chip = ChipLineHeightDp * scale + ChipVerticalPaddingDp
    return 2f * HudBlockPadDp + rows * chip + (rows - 1) * HudRowGapDp
}

private const val ChipMinTextDp = 39f      // WotaType.chip 13sp × 3 字下限
private const val ChipPaddingDp = 24f      // WotaChip 左右内边距 12+12
private const val ChipGapDp = 5f           // WotaChip 主副标签之间
private const val ChipSecondaryDp = 22f    // WotaType.label 11sp × 2 汉字（「快门」「码率」）
private const val ChipLineHeightDp = 18f   // WotaType.chip 的 lineHeight
private const val ChipVerticalPaddingDp = 12f // WotaChip 上下内边距 6+6
const val HudRowGapDp = 6f               // ParamsHud 行内/行间的间隔档：那两处 spacedBy 用的就是它（S3-5 同源）

/**
 * ParamsHud 自己的内边距：横向进 [hudRoomDp]、纵向进 [hudStripHeightDp]。
 * **公开**是为了让 ParamsHud 那层 `padding(HudBlockPadDp.dp)` 与这两条算式同一个真源（S3-5：
 * 宽度账只要分两处写，迟早一边改了另一边没改，就又是"贴边当装得下"）。
 */
const val HudBlockPadDp = 6f

/**
 * 取档时的安全余量：字宽是算术估计（汉字按 1 em、拉丁按 0.6 em）不是量出来的。
 * 块内边距已由 [hudRoomDp] 从可用宽里扣掉，这里不再重复计一次，24dp 是纯估算误差余量——
 * §58/§73/§70 三次都是死在这半成的余量上（裁字与叠字）。
 */
private const val PerRowMarginDp = 24f
