package com.wotagei.cam.ui.anim

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.wotagei.cam.camera.FrostCardTable
import com.wotagei.cam.ui.HudFrost
import com.wotagei.cam.ui.LocalPreviewCoords
import com.wotagei.cam.ui.design.HudInkLevel
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.WotaMotion
import com.wotagei.cam.ui.design.WotaStroke
import com.wotagei.cam.ui.design.frostScrimAlphaFor
import com.wotagei.cam.ui.viewLocalOriginInto
import com.wotagei.cam.ui.windowOriginInto
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
 * - 绘制层每帧 **Path 分配次数 0**：`Path`、`Stroke` 与亮缘那只 [ShaderBrush]（连同它复用的
 *   `android.graphics.Matrix`）在 [wotaPillHost] 的**组合期**各 `remember` 一次，
 *   描边宽度也在组合期用 LocalDensity 换算成 px。这里**不依赖** `drawWithCache` 的缓存语义——它的节点
 *   实现 `ObserverModifierNode`，`onObservedReadsChanged → invalidateDrawCache`，所以"外块只在尺寸变化时
 *   执行"不成立（审查 S3-4）；把对象提到组合期才是结构性保证。每帧只 `reset()` + 往同一个 Path 上追加
 *   moveTo/cubicTo/close（半圆用贝塞尔逼近，因此**不需要** `addArc(Rect)`，也就不会在绘制阶段构造
 *   Rect/Offset 一类对象），亮缘每帧只 `Shader.setLocalMatrix`（改的是既有 Matrix，零分配）。
 *   真机 profiler 的运行时计数仍列「未验」。
 *   底板轮廓那一层（[wotaDockShell]）同纪律：`Stroke` 组合期 remember，绘制期只往 `drawRoundRect` 传
 *   float 算出来的 `Offset`/`Size`/`CornerRadius`（`drawRoundRect` 的入参形态，允许清单内），
 *   **不构造 `Path`、不构造 `Rect`、不构造 `Shape`**。
 * - 绘制层读 [progress] 只发生在 draw / graphicsLayer 阶段：快照状态在绘制期被读只让该层重画，
 *   **不触发重组**，预览层不会被重排（08:79 帧率红线）。
 * - 只作用 translation / scale / alpha / path，**不动任何布局参数**：两颗控件的槽位宽度全程不变，
 *   底栏不因动画重排，快门也就不跳。底板的收拢同样只在 draw 阶段改**画出来的尺寸**，布局盒恒等于
 *   `Modifier.width(dockW)` 那一枚（[DockShell] 的注释写了为什么动布局就是动快门居中的不变量）。
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
fun chipTravelPxOf(plan: MergePlan, progress: Float, deltaPx: Float): Float =
    if (plan.travel) LiquidMerge.chipTravelPx(progress, deltaPx) else 0f

/**
 * 「那颗 → 本帧位移」的**唯一**写入算式（#71 第二批）。
 *
 * 纲（`键心 − 布局圆心`）也在函数内部现取，不留给调用点抄第二遍：`graphicsLayer.translationX`
 * 与连通体绘制层两边吃的必须是同一个数，否则那颗飞它的、颈连它的，两边各自漂移
 * （用户口径：不许重算第二份）。
 */
fun chipTravelForScene(scene: MergeScene, target: Int, plan: MergePlan, progress: Float): Float =
    chipTravelPxOf(plan, progress, scene.cx(MergeScene.RECORD) - scene.cx(target))

/**
 * 「布局圆心 → **视觉**圆心」桥（#71 第二批第 1 件，本批承重的证明，JVM 可测）。
 *
 * ## 为什么非这座桥不可
 * [MergeScene] 里那颗的矩形是 [mergeAnchor] 经 `onGloballyPositioned { positionInWindow() }` 回报的
 * **布局**矩形，而第一批给两颗加的位移走的是同一颗自己那层 `graphicsLayer.translationX`——
 * 布局回报读的是布局位置，不吃本节点这层的平移（§69 那族"锚点跟不上视觉位置"就是同一件事）。
 * 于是直接拿 `scene.cx(...)` 画颈，颈会连到那颗**离开之前的原位**，画面上那颗已经飞走了：
 * 观感就是"一条莫名其妙的线"。⚠ 这条前提本批只有静态推理 + 纯函数用例，**真机未复验**，
 * 复验手段是 #73 的取证钩子（`pin=0.7` 那一帧颈应当接在飞行中的那颗上，而不是留在原位）。
 *
 * ## 位移为什么不再乘 scale（本批顺带修掉的一条静缺陷）
 * Compose `GraphicsLayerScope` 的变换顺序是「先 translation，再绕 pivot 旋转/缩放」，
 * 所以**同一层**里 `translationX` 会被本层的 `scaleX` 乘掉：那颗按 `chipScale` 缩到 0.72，
 * 位移就只剩 72%，p=1 的实测落点差在键心外侧 137 × 0.28 ≈ 38px（19.2dp）。
 * 第一批的纯函数用例只测 `chipTravelPx` 的返回值，看不见这一层矩阵语义，所以全绿而屏幕上是错的。
 * 本批把**平移与缩放拆成两层**（外层只有 translationX，内层只有 alpha/scale，见 HudLayer 那两处）：
 * 外层平移落在内层缩放之外，量纲不被缩，`视觉圆心 = 布局圆心 + travel` 这条算式才真的成立——
 * 它同时是第一批「p=1 条目中心正好落到键心」那句话从纯函数层落到屏幕上的那一步。
 * ⚠ 同样是静态推理，真机未验（拆层之后那颗是否真落在键心，靠钩子 `pin=1` 一张图判）。
 *
 * 纵向不需要这座桥：底栏两颗的中心与键心同一条水平中线（用例 `linkCannotEscapeTheLayoutBox...` 钉着
 * 这个坐标前提），位移只有 translationX 一个写入方。
 */
fun chipVisualCx(scene: MergeScene, target: Int, plan: MergePlan, progress: Float): Float =
    scene.cx(target) + chipTravelForScene(scene, target, plan, progress)

/**
 * 「布局内切圆半径 → **视觉**半径」桥：那颗同帧正在被 [LiquidMerge.chipScale] 缩，
 * 颈必须接到缩之后的实体上，否则颈的帽会比那颗本身还粗，从键里露出一圈没有来路的暗盘。
 */
fun chipVisualRadiusPx(scene: MergeScene, target: Int, progress: Float): Float =
    scene.radius(target) * LiquidMerge.chipScale(progress)

/** 那颗**视觉**圆心到键心的距离（本帧长不长得出颈就只看这个数；纵向差按实测矩形算，不假设 0） */
fun chipVisualDistToKeyPx(scene: MergeScene, target: Int, plan: MergePlan, progress: Float): Float {
    val dx = chipVisualCx(scene, target, plan, progress) - scene.cx(MergeScene.RECORD)
    val dy = scene.cy(target) - scene.cy(MergeScene.RECORD)
    return hypot(dx, dy)
}

/**
 * 「这一帧这颗到底长不长得出颈」（#71 第二批逐帧表的算式本体）。
 *
 * 三条都过才算可见：档位闸门（[waistVisibleFor]，PLAIN 恒 false）、两颗都真在树里（面积 > 0）、
 * 视觉圆心距落在断开阈值以内且腰宽过 [LiquidMerge.MIN_WAIST_PX]。
 * 注意阈值吃的是**视觉**半径（那颗在缩），所以 `cut` 本身也随进度轻微收窄。
 */
fun neckVisibleFor(plan: MergePlan, scene: MergeScene, target: Int, progress: Float): Boolean {
    if (!waistVisibleFor(plan, progress)) return false
    if (scene.area(target) <= 0f || scene.area(MergeScene.RECORD) <= 0f) return false
    return LiquidMerge.linkVisible(
        chipVisualDistToKeyPx(scene, target, plan, progress),
        chipVisualRadiusPx(scene, target, progress),
        scene.radius(MergeScene.RECORD)
    )
}

/** 「档位 → 这一帧画不画腰」桥 */
fun waistVisibleFor(plan: MergePlan, progress: Float): Boolean =
    plan.drawWaist && progress > 0f && LiquidMerge.linkAlpha(progress) > 0.001f

/** 「档位 → 连通体不透明度」桥：[waistVisibleFor] 不成立就是 0，绘制层第一条就 return（不重复算一次腰宽） */
fun linkAlphaFor(plan: MergePlan, progress: Float): Float =
    if (waistVisibleFor(plan, progress)) LiquidMerge.linkAlpha(progress) else 0f

/**
 * 「取证钩子 → 进度」：钩子只是 [mergeProgressOf] 这条桥**上游**的一个可选覆盖（#73 第 1 件）。
 *
 * 写成一条独立纯函数而不是在调用点散写 `pinned ?: anim?.value`，有两个原因：
 * 1. 调用点仍然只经 [mergeProgressOf] 这一条桥取进度，**默认路径逐字不变**（[pinned] = null 时结果
 *    与 `mergeProgressOf(plan, recording, animatedValue)` 完全相等，用例钉的就是这条）；
 * 2. 「钩子不许漏进 PLAIN 档」由 [mergeProgressOf] 自己的 `plan.animated` 分支保证，
 *    这里**不重复判断**——重复判断就会变成第二处真源，而 PLAIN 没有中间态那条不变量（S3-1）
 *    只在一条桥上成立过。
 */
fun mergeProgressWithHook(
    plan: MergePlan,
    recording: Boolean,
    animatedValue: Float?,
    pinned: Float?
): Float = mergeProgressOf(plan, recording, pinned ?: animatedValue)

/**
 * 「进度 → 这一颗还收不收点击」（#73 第 2 件：吸收期的命中权交接）。
 *
 * 判据取 **进度 > 0 就断**，不取"淡到看不见了才断"（`chipAlpha` 到 0 要 p ≥ 0.9），理由是：
 * - 入向（开始录制）：一有位移就不许这点那颗，#71 第一批把位移放开到全程 68.5dp 之后，
 *   半融那几帧两颗正压在录制键的命中区上，而"停止录制"是最高优先级手势；
 * - 出向（停止录制 → 分裂）：两颗从键心往外飞，头几百毫秒仍与键重叠，
 *   这时若按"目标态"放行点击，用户在键上那一下就会被**刚飞出去的那颗**吃掉（点第二次录制点不动）；
 * - PLAIN 档进度只有 0/1，于是这一条等价于"录制中不可点"，没有中间态可漏。
 *
 * ⚠ 返回值只用来决定**装不装那枚点击修饰符**（结构性断链），不是 `clickable(enabled = false)`：
 * 后者仍会在节点链上留一个 pointer-input 节点，命中顺序上到底吃不吃这一指取决于实现细节，
 * 而"不吃录制键那一指"是本批要的证据，不能压在一个未证的行为上。
 */
fun chipClicksAccepted(progress: Float): Boolean = progress <= 0f

/**
 * 「底板上的长按落点 → 这一指是不是归录制键」（#73 第 2 件第 3 条）。
 *
 * 底板那枚 `detectDragGesturesAfterLongPress` 挂在**父节点**上，`zIndex` 管不到父子之间：
 * 手指按在录制键上超过长按阈值时，父节点那个探测器会开始跟手，把键上那一指变成"整枚 Dock 换栏"。
 * 所以进入拖拽之前先问这一条：落点在录制键实测矩形内 → 底板**不接管**，那一指留给键。
 *
 * 坐标：入参是相对底板（绘制层宿主）左上角的本地坐标，[MergeScene] 存的是窗口坐标，
 * 两者同帧由布局期回报（[mergeAnchor]），所以减一下就对齐了（与 `cx/cy` 同一套换算）。
 * 录制键还没测到（面积 0，只有首帧那一次）时返回 false：退回今天的行为，宁可这次长按能换栏，
 * 也不要在没量到的时候凭空拒绝手势。
 */
fun dragOwnedByRecordKey(scene: MergeScene, localX: Float, localY: Float): Boolean {
    if (scene.area(MergeScene.RECORD) <= 0f) return false
    val left = scene.recordLeft - scene.canvasLeft
    val top = scene.recordTop - scene.canvasTop
    return localX >= left && localX <= left + scene.recordWidth &&
        localY >= top && localY <= top + scene.recordHeight
}

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

    /**
     * 亮缘（光感）径向渐变的**跨度**：渐变半径 = 录制键半径 × 本系数。
     *
     * 1.4 的依据是这一帧连通体真正露在外面的范围：那颗露在键圆之外的月牙最远到
     * `视觉圆心距 + 视觉半径`，而颈刚长出来那一刻它 ≈ `cut + rA ≈ 1.35 × rRec`
     * （阈值算式见 [cutDistancePx]），再往外就什么都没有了，亮出去只会给胶囊本体描一圈白边。
     */
    const val NECK_GLOW_SPAN = 1.4f

    /**
     * 亮缘峰值所在的**归一化半径**档（0 = 键心，1 = 跨度外缘）。
     *
     * 它是独立写死的字面量，故意**不**写成 `1f / NECK_GLOW_SPAN`：这样"峰值落在哪一档"与
     * "半径有多长"是两个能各自跑偏的数，用例 `neckGlowPeakSitsOnTheKeyCircle` 把两者乘回去
     * 必须等于键半径本身（±0.5%），任一处单独改动就红。写成派生式就是一条恒等式，
     * 什么退化都测不出（AGENTS「恒等式不算证明」）。
     * 0.714 = 1/1.4 ⇒ 峰值正好落在**键自身的圆周**上，也就是两圆相切/互穿的那圈接触线。
     */
    const val NECK_GLOW_PEAK_STOP = 0.714f

    /** 亮缘渐变的像素半径（每帧喂给 [NeckGlowBrush] 的 local matrix，不在绘制阶段新建对象） */
    fun neckGlowRadiusPx(recordRadiusPx: Float): Float = recordRadiusPx * NECK_GLOW_SPAN

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
     * 本体位移（#71 第一批第 2 件：**放开**，条目真的跟进去）。
     * **带符号**：入参是 `录制键中心 − 那颗中心`，左边的颗得往右移（正）、右边的颗往左移（负），
     * 取绝对值就会把镜头那颗反着推出去。
     *
     * ## 纲换成「条目中心到键心的这段距离」
     * `deltaPx` 本身就是位移上限：进度 1 ⇒ 条目中心**正好落到键心**（与 [blobX]/[blobY] 的终点同一处），
     * 进度夹在 0..1 ⇒ 不许穿过键心甩到另一边，也不许在反向过冲时冲出原位（LIQUID 弹簧两头都会过冲）。
     *
     * 旧版的上限是「那颗的近缘与录制键**触摸盒**之间的实测空隙 clearance」（`min(圆心距 × 0.16, clearance)`，
     * 底栏实测算出来只有 10.96dp / 13.28dp）。它就是"看起来只是原地淡化"的算术原因，本批按用户口径撤掉。
     * 撤它的前提**不是**"点不到了没关系"，而是 #73 已经落地的两层保证（本批一行都不许削弱）：
     * - [chipClicksAccepted]：进度 > 0 那两颗连 `clip + clickable` 都不 install，命中链在结构上断开，
     *   不是 `clickable(enabled = false)` 那种"节点还在只是不回调"，也**从不拿 alpha 当命中屏蔽**；
     * - `RecordZIndex`：录制键在绘制与命中两层都压在两颗之上（同层兄弟的证据见 HudLayer 那处注释）。
     *
     * ## 为什么放开之后不需要给条目做裁切（可静态核对的算式，用例 `releasedTravelKeepsChipsInsideShell`）
     * 以键心为原点，条目中心 = `d(1 − p)`、条目半宽 = `hw(1 − 0.28p)`（[chipScale] 同一条线性）、
     * 轮廓半宽 = `W0/2 − (W0 − W1)p/2`（[DockShell] 的线性收拢），三项对 p 都是**线性**的，
     * 于是「外缘在轮廓内」⇔ 两个端点都在内：
     * · p=0：`d + hw ≤ W0/2` ⇔ 底板内边距（镜头那颗 68.5 + 31.5 = 100 ≤ 108，缩略图 83 + 17 = 100 ≤ 108）；
     * · p=1：`0.72·hw ≤ W1/2 = 23` ⇔ 那颗缩到 0.72 之后的半宽不超过圆环半径（镜头 22.68、缩略图 12.24）。
     * 两条都成立 ⇒ 全程不越界，本批不给条目加 `clip`。**注意 p=1 那条是 chipScale 在承重**：
     * 把 scale 撤掉就是 31.5 > 23，那颗会露出圆环（用例把这条依赖钉住了，不许当理所当然）。
     */
    fun chipTravelPx(progress: Float, deltaPx: Float): Float =
        deltaPx * progress.coerceIn(0f, 1f)

    /**
     * 液滴半径：从那颗的原位半径过渡到录制键半径的一小半。
     *
     * ⚠ **#71 第二批之后绘制层不再消费它**（连通体的条目那一端改接那颗的**视觉**半径
     * [chipVisualRadiusPx]，也就是那颗自己缩之后的实体，否则颈的帽会比那颗还粗、从键里糊出一圈
     * 没有来路的暗盘）。留着是因为它是"液滴被吞进去时该缩多少"这条曲线的唯一记录，且 B4 的拖拽动感
     * 复用得到；它与 [chipScale] 那条不再是同一条曲线，**不许**再拿它当颈的端点半径。
     */
    fun blobRadius(progress: Float, rHome: Float, rRecord: Float): Float =
        rHome + (rRecord * BLOB_TARGET_RATIO - rHome) * progress.coerceIn(0f, 1f)

    /**
     * 液滴圆心：原位与录制键中心的线性插值（进度 0 = 原位，1 = 重合）。
     *
     * 位移纲放开成"整段圆心距"之后（[chipTravelPx]），这一条与 [chipVisualCx] 是**同一件事的两种写法**
     * （前者从插值出发、后者从"布局圆心 + 同源位移"出发）。绘制层只走后者那一条（位移与颈必须同源），
     * 这里保留并由用例 `visualCenterIsTheSamePointAsTheBlobPath` 把两条独立写法对撞：
     * 任一侧被单边改错（插值方向写反、位移纲换掉）当场红。
     */
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
 * 底板**可见轮廓**的收拢算式（#71 第一批第 1 件，docs/plan/13 §14.3 定版构造）。
 * 单位由调用方自洽（本对象只在同一单位里做加减），纯函数、JVM 可测。
 *
 * ## 是「长度缩减」，不是缩放
 * `graphicsLayer.scaleX/scaleY` 一次都不出现。216×60 的圆角矩形**不可能等比**变成圆（短轴缩到位时
 * 长轴还剩三倍），所以"缩放"这个念头本身就会把人逼进非等比的坑——而长度缩减根本不需要缩放。
 * 收拢只改**画出来的那两个轴**：
 * · 长轴 `w(p) = 起点 → 终点` 线性（起点 = 布局盒实测宽，100% 字体下由 [MergeSlot.dockWidthPx]
 *   算出 216dp；终点 = 录制键**可见圆环** recordRing，不是 50dp 的命中盒 recordTouch）；
 * · 短轴 `h(p) = 布局盒实测高（recordTouch + 2×DockInnerPadV = 60dp）→ 同一个圆环`，**也在收**；
 * · 半径 `cornerRadius ≡ min(w, h) / 2` ⇒ 全程是一枚合法**体育场形**，`w == h` 那一帧自动就是正圆。
 *   半径由短轴**推出来**，所以没有任何一帧是"圆角不匹配的钝角矩形"，也没有椭圆帧；描边是恒定宽度的
 *   `Stroke`，不跟着任何轴向压扁（第一版把它压到 0.23dp 的那个代价就是这么避免掉的）。
 *
 * ## 布局盒全程锁死（本文件唯一不许动的地方）
 * 起终点都由**布局盒实测尺寸**给出（[wotaDockShell] 里读 `DrawScope.size`），也就是
 * `Modifier.width(dockW)` + 内层三格排布 + [MergeSlot.dockWidthPx] 那一行都不许改。理由：动了布局
 * 就重排、快门就跳，而"录制键中心 ≡ 底板中心 ≡ 可视窗口水平中心"那条 `W/2` 不变量当场崩
 * （见 HudBottomZone KDoc 不变量②）。收拢只发生在 draw 阶段。
 *
 * ## 反方向
 * 细胞分裂就是 p 反向播，同一套算式，**不写第二份**。
 */
object DockShell {

    /** 单轴收拢：起点是布局盒实测（不是 dp 字面量），终点是键形；进度夹 0..1（弹簧过冲不许把轮廓收成负尺寸） */
    fun sidePx(startPx: Float, endPx: Float, progress: Float): Float =
        startPx + (endPx - startPx) * progress.coerceIn(0f, 1f)

    /** 体育场半径 = 短轴一半；`w == h` 时它同时是长轴一半，那一帧就是正圆 */
    fun cornerRadiusPx(widthPx: Float, heightPx: Float): Float =
        if (widthPx < heightPx) widthPx * 0.5f else heightPx * 0.5f

    /** 居中偏移：收拢中的轮廓留在布局盒正中 ⇒ 轮廓中心 ≡ 键心 ≡ 可视水平中心（不变量②） */
    fun insetPx(boxPx: Float, sidePx: Float): Float = (boxPx - sidePx) * 0.5f
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
 * 底板的**可见轮廓**随进度从整枚布局盒收拢到录制键圆环（#71 第一批第 1 件，替代这里原来的
 * `Modifier.wotaCard(WotaShape.pill)`）。
 *
 * 材质一个都没新造：填充 [WotaColor.hudScrim]、描边 [WotaColor.acrylicBorder]、宽度
 * [WotaStroke.hairline]，与 [com.wotagei.cam.ui.design.wotaCard] 是同一套令牌；p=0 时
 * `w = 布局盒宽`、`h = 布局盒高`、`radius = min(w,h)/2 = 短轴一半`，画出来的形状与
 * `RoundedCornerShape(percent = 50)` 在同一个盒子上的形状**逐像素同形**（那枚 shape 的半径也是短轴一半），
 * 所以静止态没有观感变化，变的只是"轮廓跟着 p 一起缩"。
 *
 * ## #84 步骤 2 之后：fill 只在霜没在屏幕上时画
 * [com.wotagei.cam.ui.HudFrost.live] 为 true 时这一层不再自绘 fill，而是把**当前可见矩形**注册进
 * `camera/FrostCardTable`，由 GL 在预览 blit 之后贴「霜 + 圆角 + 底板色」（A2 混合路线，
 * docs/plan/14 §二）。描边、录制键、两颗条目一概不动。注册走绘制期而不是布局期，是因为可见轮廓每帧
 * 随 p 收拢而布局盒锁死；半径送「短边一半」的负数哨兵，与 [DockShell.cornerRadiusPx] 同一个数、
 * 两边不各写一份。live 为 false（开关关 / DIRECT / 离屏链停用）时这里就是**改前那一段代码**，逐字同观感。
 *
 * ## 为什么自绘而不是继续用 wotaCard
 * `wotaCard` 的填充是 `clip(shape).background(color)`、描边是 `border(width, color, shape)`，
 * 三件都吃**静态 Shape**；要按 p 改形状就得每帧造一枚新 Shape（`RoundedCornerShape(pct)` 走
 * `Shape.createOutline` 还要在每帧构造 `Path`/`Rect`），而把布局盒本身改小更是直接踩红线——
 * 布局一改就重排、快门跳、`W/2` 居中不变量崩（[DockShell] 那一条）。绘制期自绘一枚居中的圆角矩形
 * 是唯一同时满足"轮廓可变 + 布局盒锁死 + 描边恒定"的写法。
 *
 * ## 每帧成本（诚实记账）
 * [Stroke] 在**组合期** remember；绘制期只读 `progress()` 与 `size`，构造 `Offset`/`Size`/`CornerRadius`
 * 各两枚（填充一枚、描边内缩半线宽再一枚）给 `drawRoundRect` —— 这是该基元的入参形态，本仓允许清单内。
 * **不构造 Path、不构造 Rect、不构造 Shape**；一次收拢动画（750ms × 60fps）总共约 540 枚 8 字节量级的
 * 短命对象，落在 young-gen，量级与 `drawWithCache` 外块重跑那枚 lambda 同一档。
 * 真机 profiler 的运行时计数仍列「未验」。
 *
 * ## 反方向与档位
 * 反方向（细胞分裂）= p 反向播，同一套算式没有第二份；PLAIN 档由 [mergeProgressOf] 那条桥把 p 钉成
 * 0/1，于是底板直接是**起点形状**或**终点形状**，不存在中间帧（不需要在这里再判一次 plan）。
 *
 * @param collapsedSize 终点边长：录制键**可见圆环** [com.wotagei.cam.ui.design.WotaHit.recordRing]（46dp），
 *   不是 50dp 的命中盒 recordTouch——那是命中区不是形状。长轴与短轴收到同一个值 ⇒ 终点是正圆
 * @param progress 归一化进度；只在绘制阶段读，不触发重组
 */
@SuppressLint("ComposableModifierFactory")
@Composable
fun Modifier.wotaDockShell(collapsedSize: Dp, progress: () -> Float): Modifier {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { WotaStroke.hairline.toPx() }
    val endPx = with(density) { collapsedSize.toPx() }
    // 描边宽度在组合期换算并 remember（每帧不许新建 Stroke）；闭合的圆角矩形不需要端点帽
    val edge = remember(strokeWidthPx) { Stroke(width = strokeWidthPx) }
    // 霜（#84 步骤 2 · A2）：底板真在屏幕上时这一块不再自绘 fill，改为把**可见**矩形注册进矩形表，
    // 由 GL 在预览 blit 之后把「霜 + 圆角 + 底板色」贴在那里。注册走绘制期而不是布局期，理由只有一条：
    // 这枚底板的可见轮廓由 p 每帧收拢，而布局盒全程锁死（216×60），拿布局盒去贴板就会在 p>0 之后
    // 露出一大块"板比轮廓大"的玻璃。窗口原点由本节点自己在布局期量（`frostOriginPx`），
    // 与 drawWithCache 的 size 出自同一次布局，所以窗口坐标在这里是量得出的。
    // live 管"这一层 fill 让不让位"，intent 管"要不要注册进矩形表"。两者不能并成一个：
    // 用 live 去闸注册会启动死锁（卡片要 live 才写、live 要画过板才真、画板要卡片才有）。
    val live = HudFrost.live
    val intent = HudFrost.intent
    // 承载预览盒坐标（录制页由 CameraScreen provide）：非空 ⇒ 可见矩形在**视图局部坐标**里量，
    // 分屏那层祖先 graphicsLayer 缩放被 localPositionOf 抵消；为空（编辑控件页）⇒ 回退窗口系。
    val preview = LocalPreviewCoords.current
    val frostSlot = if (intent) remember(intent) { FrostCardTable.acquireSlot() } else -1
    // 比对缓存：left/top/w/h + **节点原点 x/y**（六元）。原点必须进比对——页面往返/沉浸切换
    // 会平移节点而未动局部几何，漏比它就是「GL 板留在旧位置与 fill 分离」
    // （2026-10-04 真机 56px 分离实证）。视图局部系口径下这个原点是**视图局部**原点：
    // 它随节点在页面里移动而变（底栏换栏拖拽照样被这条捕获），分屏那层祖先缩放则天然不进它。
    val frostLast = remember { FloatArray(6) }
    val frostOriginPx = remember { FloatArray(2) }
    val frostAlpha = frostScrimAlphaFor(HudInkLevel.SECONDARY)
    DisposableEffect(frostSlot) {
        onDispose {
            if (frostSlot >= 0) FrostCardTable.releaseSlot(frostSlot)
        }
    }
    return this then Modifier
        .onGloballyPositioned { coords ->
            // 节点原点：视图局部系优先（与注册矩形同一坐标系），预览盒未登记时回退旧窗口系。
            // 体内刻意不出现 positionInWindow（守卫锁着）：那条路一律走 FrostSpace 里的统一换算。
            if (preview != null) viewLocalOriginInto(frostOriginPx, coords, preview)
            else windowOriginInto(frostOriginPx, coords)
        }
        .drawWithCache {
        onDrawBehind {
            val p = progress().coerceIn(0f, 1f)
            // 起点取**布局盒实测**：这里不出现 216/60 这类 dp 字面量，宽度账只有一条真源
            val boxW = size.width
            val boxH = size.height
            val w = DockShell.sidePx(boxW, endPx, p)
            val h = DockShell.sidePx(boxH, endPx, p)
            val r = DockShell.cornerRadiusPx(w, h)
            val left = DockShell.insetPx(boxW, w)
            val top = DockShell.insetPx(boxH, h)
            if (intent && frostSlot >= 0) {
                // 静止态不写表（一次事务 = 先把已发布那份整表抄进 scratch，再换引用）：
                // 只有轮廓真的动了才重报。⚠ 比对必须**含节点原点**（frostOriginPx）：
                // left/top/w/h 是节点局部量，页面往返/沉浸切换时节点原点会平移而局部几何
                // 一字不变——只比局部量就会漏报，GL 板永久停在旧位置、与 Compose fill
                // 错开整整一个状态栏高度（2026-10-04 用户报「底栏两层分离」的根因，56px 实测）
                val ox = frostOriginPx[0]
                val oy = frostOriginPx[1]
                if (frostLast[0] != left || frostLast[1] != top || frostLast[2] != w || frostLast[3] != h ||
                    frostLast[4] != ox || frostLast[5] != oy
                ) {
                    frostLast[0] = left
                    frostLast[1] = top
                    frostLast[2] = w
                    frostLast[3] = h
                    frostLast[4] = ox
                    frostLast[5] = oy
                    HudFrost.refreshHeader()
                    FrostCardTable.writeCard(
                        slot = frostSlot,
                        leftPx = frostOriginPx[0] + left,
                        topPx = frostOriginPx[1] + top,
                        rightPx = frostOriginPx[0] + left + w,
                        bottomPx = frostOriginPx[1] + top + h,
                        // 全程体育场形 ⇒ 半径恒等于短边一半（与 [DockShell.cornerRadiusPx] 同一个数）；
                        // 送负数哨兵让 camera 侧按实测宽高现算，两边不各写一份"短边一半"
                        radiusPx = -1f,
                        alpha = frostAlpha
                    )
                }
            }
            // 填充铺满形状本身（= 旧的 clip(shape) + background 那一层）。
            // 霜在屏幕上时这一层让位：0xBF 的 fill 会把底下的霜全盖死，等于没做（docs/plan/14 §二）
            if (!live) {
                drawRoundRect(
                    color = WotaColor.hudScrim,
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(r, r)
                )
            }
            // 描边内缩半个线宽（= 旧的 border(width, color, shape) 那一层的语义：画在边界之内），
            // 于是 p=0 那一帧与换掉之前逐像素对齐，p=1 那一帧的 1dp 细边也**不会**探出 46dp 圆环
            // （它落在 22–23dp 处，正好压在录制键那枚 3dp 圆环下面，不会画出一圈多余的亮边）
            val half = strokeWidthPx * 0.5f
            val sr = if (r > half) r - half else 0f
            drawRoundRect(
                color = WotaColor.acrylicBorder,
                topLeft = Offset(left + half, top + half),
                size = Size(w - strokeWidthPx, h - strokeWidthPx),
                cornerRadius = CornerRadius(sr, sr),
                style = edge
            )
        }
    }
}

/**
 * 连通体绘制层：画在**底板轮廓之上、两颗控件之下**（挂在那个容器的节点上：底板轮廓在链路更外侧所以
 * 先画，子节点后画 → 盖住腰的两端）。
 *
 * 本批把原来的 `wotaCard`（第一环是 `clip`）换成了 [wotaDockShell]（纯绘制，没有 clip），所以这里
 * 少了一道"容器把腰拦在胶囊里"的保险。核账（#71 第二批改口径之后）：颈的两端是**那颗的视觉圆**
 * （圆心 [chipVisualCx]、半径 [chipVisualRadiusPx]）与**录制键圆**，条目那一端永远落在
 * 「原位圆心 → 键心」这条线段上，于是横向最远仍在原位那一头：68.5 + 15 = 83.5dp、83 + 17 = 100dp，
 * 都 < 布局盒半宽 108；纵向（中线到轮廓）最远 = max(那颗视觉半径, 键半径, 控制点法向偏移)
 * = 键半径 **25dp** ≤ 布局盒半高 30dp（偏移那一档 `c = (8·腰 − rA − rB)/6 ≤ (8·15 − 15 − 25)/6 ≈ 6.7dp`
 * 本来就比半径小，三次贝塞尔不会出自己的控制点凸包）⇒ **颈本来就出不了布局盒**，旧的 clip 是一条
 * 从未生效的保险；用例 `neckStaysInsideTheCollapsingShell` 把这笔账按**收拢中的轮廓**（不是布局盒）
 * 再核一遍并钉住。
 *
 * ## 颈的端点为什么一律走 [chipVisualCx]（本批唯一的坐标陷阱）
 * [MergeScene] 存的是**布局**矩形，`positionInWindow()` 不反映那颗自己那层 `graphicsLayer.translationX`。
 * 直接拿 `scene.cx(...)` 画颈，颈就钉在原位、那颗飞走了，观感是"一条莫名其妙的线"。
 * 位移与颈现在共用 [chipTravelForScene] 这一条算式（同源），绘制阶段读的是与那颗那层 `graphicsLayer`
 * **同一个** [progress] lambda（同一帧不可能两个值）。
 *
 * ## 光感（#71 第二批第 2 件）
 * 只有一条路：**轮廓上的亮缘**。当年这条是硬红线（"预览层之上不许实时背景模糊、不许投影"），
 * 而 minSdk 29 / 主测机 API 30 上 `RenderEffect` 根本不可用、也不引任何第三方。
 * ⚠ #84 已经把"不许模糊"那半条作废（docs/plan/14 §五）：底板现在是 GL 自绘的真·透光毛玻璃，
 * 见 [wotaDockShell] 那一段；但**连通体（颈）这一层仍然走旧材质**——它是随 p 变形的自由 Path，
 * 而 A2 的画板只吃"矩形 + 圆角"这一种形状，圆角之外的任何视觉细节都不在 GL 侧。
 * 亮缘因此照旧：在 [WotaColor.hudScrim] 填充 + [WotaColor.acrylicBorder] 细描边之外，
 * 同一张 Path 再描一遍 [NeckGlowBrush]：一枚径向亮缘，峰值档按
 * [LiquidMerge.neckGlowRadiusPx] × [LiquidMerge.NECK_GLOW_PEAK_STOP] 落在**录制键自身的圆周**
 * （两圆接触那一圈），向键心与跨度外缘线性衰减。笔刷与它的临时 `Matrix` 都在**组合期**各建一次
 * （与 [Path]/[Stroke] 同一条纪律），每帧只 `setLocalMatrix` ⇒ 绘制阶段零分配，
 * 也不构造 Path/Rect/Offset 之外的对象。两枚颗的颈共用同一张 Path 与同一支笔刷（径向以键心为极，
 * 左右对称），所以既不会出现"同一处叠两笔半透明"的暗缝，也不需要第二条腰公式。
 *
 * `linkAlpha`（[LINK_FADE_FROM] 起整体淡出）那条语义一字未改：亮缘与填充、描边吃同一支 alpha 一起收，
 * 底板描边（[wotaDockShell]）与徽标那些也没动。PLAIN 档由 [linkAlphaFor] 第一条就 return，
 * **一条路径都不描、一次 drawPath 都不发**（用例 `plainModeHasNoAnimationWindowAtAll` 盯的就是这个）。
 *
 * 材料沿用 `wotaCard` 那一套令牌（[WotaColor.hudScrim] 填充 + [WotaColor.acrylicBorder] 描边，
 * 描边宽度 [WotaStroke.hairline] 与卡片边同一档），亮缘两端也全是既有令牌（见 [NeckGlowBrush]）。
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
    // 亮缘笔刷与它的临时矩阵各在组合期建一次：每帧只 setLocalMatrix（绘制阶段零分配）
    val glow = remember { NeckGlowBrush(newNeckGlowGradient()) }
    val glowMatrix = remember { Matrix() }
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
                drew = appendChipLink(link, scene, MergeScene.THUMB, plan, p, rcx, rcy, rRec) || drew
            }
            if (scene.area(MergeScene.LENS) > 0f) {
                drew = appendChipLink(link, scene, MergeScene.LENS, plan, p, rcx, rcy, rRec) || drew
            }
            if (!drew) return@onDrawBehind
            // 两枚液滴的连通体在同一个 Path 的同一个子路径族里，一次 drawPath → 一次混合，重叠处不叠暗缝
            drawPath(link, WotaColor.hudScrim, alpha = alpha)
            drawPath(link, WotaColor.acrylicBorder, alpha = alpha, style = edge)
            // 光感：同一张轮廓再描一笔亮缘，峰值落在键自身圆周（接触圈）。宽度仍是 hairline 那一档，
            // "更亮"靠颜色档位（acrylicBorder 15% 白 → refLine 80% 白），不加宽、不加投影；
            // 也不给这一层加模糊——#84 的霜只做「矩形 + 圆角」的底板，自由 Path 的颈不在那条路上
            glow.place(rcx, rcy, LiquidMerge.neckGlowRadiusPx(rRec), glowMatrix)
            drawPath(link, glow, alpha = alpha, style = edge)
        }
    }
}

/**
 * 一颗控件被吸收的这一条颈：`那颗的**视觉**圆 ↔ 录制键圆`，追加进同一个 [Path]。
 *
 * **旧版这里是两段**（`原位圆 ↔ 液滴` + `液滴 ↔ 键`）。第二段吃的液滴圆心本来就等于那颗的视觉位置
 * （纲放开之后 `blobX` 与 [chipVisualCx] 是同一点），第一段却把端点钉在**布局**原位——那颗飞走之后，
 * 那一段就在原位留了一枚圆帽加一条尾巴，正是"颈和胶囊脱节、像一条莫名其妙的线"的成因。
 * 现在端点一律走桥，两段并成一段，不再有任何以布局圆心画的几何。
 * 每帧只在既有 Path 上追加线段，不新建对象。
 */
private fun appendChipLink(
    path: Path,
    scene: MergeScene,
    target: Int,
    plan: MergePlan,
    progress: Float,
    recordX: Float,
    recordY: Float,
    rRecord: Float
): Boolean = path.appendLiquidLink(
    chipVisualCx(scene, target, plan, progress),
    scene.cy(target),
    chipVisualRadiusPx(scene, target, progress),
    recordX,
    recordY,
    rRecord
)

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
 * 亮缘（光感）用的规范半径：这里只把渐变建成"圆心 (0,0)、半径 [NECK_GLOW_CANONICAL_RADIUS_PX]"
 * 的标准形状，每帧靠 `setLocalMatrix` 平移到实测键心并按比例缩放。
 * 取 100（不是 1）是为了让 Skia 求反矩阵时落在数量级正常的数上，缩放系数每帧只有 0.5~1.0 一档。
 */
private const val NECK_GLOW_CANONICAL_RADIUS_PX = 100f

/**
 * 亮缘渐变本身（组合期建一次）。四个数都是既有令牌，没有新造色值：
 * - 键心那一头 [WotaColor.acrylicBorder]（0x26FFFFFF，卡片边那一档 15% 白）：接触圈以内不许亮，
 *   否则键心里会出现一枚没有来路的白斑；
 * - 峰值 [WotaColor.refLine]（0xCCFFFFFF，参考线那一档 80% 白）——令牌里唯一"比卡片边更亮、
 *   又不是实色"的白，最贴水银将合未合那一口反光；`textHi` 是 95% 实白，描在连通体上会读成
 *   一根白线而不是一圈反光，所以取 refLine；
 * - 跨度外缘收到 acrylicBorder 的 **0 透明度**（同一色相、只是把 alpha 拉到 0），
 *   CLAMP 之外恒为透明，不会把亮缘拖到胶囊本体上。
 */
private fun newNeckGlowGradient(): RadialGradient = RadialGradient(
    0f, 0f, NECK_GLOW_CANONICAL_RADIUS_PX,
    intArrayOf(
        WotaColor.acrylicBorder.toArgb(),
        WotaColor.refLine.toArgb(),
        WotaColor.acrylicBorder.copy(alpha = 0f).toArgb()
    ),
    floatArrayOf(0f, LiquidMerge.NECK_GLOW_PEAK_STOP, 1f),
    Shader.TileMode.CLAMP
)

/**
 * [ShaderBrush] 外壳：把规范渐变按实测几何搬到本帧的接触圈上。
 *
 * `place()` 每帧只做三件事：`reset` + `postScale` + `postTranslate`（写进**同一枚** remember 住的
 * [Matrix]，再把局部矩阵塞回 shader），全程零分配 —— 与 [Path]/[Stroke] 那条"组合期建、绘制期复用"
 * 的纪律同一条。缩放放在平移**之前**（`post*` 是右乘，先缩放后平移才能得到 `k·p + c`）。
 */
private class NeckGlowBrush(private val radial: RadialGradient) : ShaderBrush() {

    /** foundation 1.5.4 的 `ShaderBrush` 是抽象类（不带参构造），自己把既有 shader 交出去：忽略 size，
     *  几何全在 [place] 写的 local matrix 上，所以每帧不会因为画布尺寸变化而重建 shader */
    override fun createShader(size: Size): Shader = radial

    /** @param cx/cy 键心（绘制层本地坐标）@param radiusPx 本帧的亮缘半径 = [LiquidMerge.neckGlowRadiusPx] */
    fun place(cx: Float, cy: Float, radiusPx: Float, into: Matrix) {
        val k = if (radiusPx > 0f) radiusPx / NECK_GLOW_CANONICAL_RADIUS_PX else 0f
        into.reset()
        into.postScale(k, k)
        into.postTranslate(cx, cy)
        radial.setLocalMatrix(into)
    }
}
