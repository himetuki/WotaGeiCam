package com.wotagei.cam.camera

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * HUD 毛玻璃背板的**纯几何层**（docs/plan/14 §二 · #84 步骤 1 的"计划→行为"那一半）。
 *
 * 本文件不 import 任何 android 类，因此可以在 JVM 单测里逐条手算验证：真机上 GL 侧看不见摸不着，
 * 能静态证明的只有这一层（`HudFrostTest`）。离屏链本体在 [FrostBlurChain]，它只做两件事：
 * 按这里的尺寸建资源、按这里的位移跑两趟模糊。
 *
 * 坐标约定（全部 px）：
 * - **承载视图坐标**：原点 = [GlRenderEngine] 那条 EGL 窗口的左上角，Y 向下；
 *   [GlRenderEngine.contentRect] 就在这个系里（它的分母是 [DisplaySurfaceReceiver.onDisplaySurfaceChanged]
 *   回报的视图尺寸，不是整块屏幕）。
 * - **窗口坐标**：原点 = app 窗口左上角；HUD 卡片实测矩形（`HudLayer` 的 `positionInWindow`）在这个系里。
 *   承载视图嵌在按 `aspectRatio` 收过的盒子里、并非铺满整屏 ⇒ 两者相差一个视图原点，必须减掉才对得上。
 * - **纹理 UV**：u 向右、**v 向上**（GL 约定，v=0 是画面底边）；模糊 RT 用与上屏 pass 同一套纹理坐标
 *   建立，所以 RT 里那张图与屏幕上等比画面同朝向同内容，换算只管等比画面、不必再管机型矩阵。
 */

// region 渲染参数（常量集中在这一节；帧率不达标只动这里，不动链本体）

/**
 * 离屏 RT 的降采样倍数 N（RT 边长 = 等比画面边长 / N，向上取整）。
 *
 * 为什么是 4：
 * - **省下的是带宽不是算力**：三趟里有两趟是"每像素 7 次取样"的可分离高斯，像素量按 1/N² 缩，
 *   N=4 ⇒ RT 只有等比画面的 1/16，两趟合计的取样工作量 ≈ 等比画面像素 × 13/16 ≈ 0.8 倍的一次全屏绘制，
 *   相对上屏 pass 本身（1 次全屏 + 曲线/斑马/峰值的 1–5 次取样）是同量级偏小。
 * - **再小就糊成一片**：N=8 时本机竖屏 720×405 的等比画面只剩 90×51，而 Dock 卡片实测只有约
 *   108×60 px（54dp @ density 2）⇒ 一张卡片跨 13×8 纹素，与高核半径同量级，采到的几乎是同一个颜色，
 *   "模糊底层内容"退化成"给卡片垫一块均色"，方向感全丢。N=4 时同一条卡片跨 27×15 纹素，还看得见走向。
 * - **再大就白花钱**：N=2 ⇒ RT 像素量是 N=4 的 4 倍（720×405 → 360×203），模糊观感提升看不出来，
 *   因为最终还要被放大 N 倍显示（双线性放大本身就带一层盒式模糊）。
 *
 * 与帧率红线（docs/plan/13 §44 的「实际帧率 ÷ 设定帧率 ≥ 95%」）的关系：这段算术只用来**定档**，
 * 真正的达标与否由 #85 的三组对照（PLAIN / 模糊关 / 模糊开）实测差值说话；
 * 若差值把红线打穿，第一顺位是把 N 调到 6/8（成本随 1/N² 掉），而不是去砍卡片下的模糊半径。
 */
const val FROST_DOWNSCALE = 4

/**
 * RT 最长边上限（px）。**必须容得下本机横屏满幅**，否则降采样倍数会被悄悄改掉：
 * 本机面板 720×1600，横屏时等比画面最宽可到 1600 ⇒ 1600/N = 400 < 512（不触发钳制）；
 * 若按直觉写 256，横屏就会从 N=4 退化成 N≈6.25，卡片下的模糊比竖屏粗，而 #85 的三组对照
 * 前提是"两组只差一个开关、半径档位相同"——上限吃横屏就是毁掉这个前提，测出来的差值说不清是谁的功劳。
 *
 * 那这上限还剩什么用：留给"外接大屏 / 将来把承载视图做成整屏不裁切"兜底。等比画面被
 * [GlRenderEngine.updateContentRect] 钳在窗口内，本机窗口最大 1600×720 ⇒ 正常永远走不到；
 * 真到 4K 监看（3840×2160 → 960×540）才会被钳成 512×288 = 14.7 万像素，
 * 两趟合计约 206 万次取样/帧，仍小于该帧上屏 pass 本身的像素量。触发时按最长边等比缩，
 * **不会**把 RT 拉成与画面不同比例（比例错了卡片下的模糊就会歪）。
 */
const val FROST_RT_MAX_EDGE = 512

/**
 * 一维高斯权重（σ=3 的 7 抽头核，只存中心 + 3 对偏移，归一化到和为 1）。
 *
 * 数值来源：`exp(-k²/(2σ²)) / Σ`，σ=3 ⇒ 分母 5.706454，
 * `1/5.706454 = 0.17524`、`0.945959/5.706454 = 0.16577`、`0.800737/5.706454 = 0.14032`、
 * `0.606531/5.706454 = 0.10629`；和 = 0.17524 + 2×(0.16577+0.14032+0.10629) = 1.00000，
 * 所以模糊不会整体提亮或压暗（和偏离 1 就是色差，[HudFrostTest] 有这条断言）。
 *
 * 半径口径：±3 纹素 = ±3×N = **±12 屏幕 px**（N=4 时），再叠双线性放大的盒式模糊，
 * 视觉半径约 ±12–16px，够"毛玻璃"但不会把卡片背后糊成一整块纯色。
 * 想改半径就同时改这张表和 [Shaders.frostGaussianFragment] 的 `weightCount`（两处必须一起动，
 * 链本体按 `FROST_BLUR_WEIGHTS.size` 生成着色器数组长度，只改一处会链接失败并停用整条链）。
 */
val FROST_BLUR_WEIGHTS = floatArrayOf(0.17524f, 0.16577f, 0.14032f, 0.10629f)

// endregion

// region 几何载体（不可变；主线程读快照用，杜绝两个 @Volatile 之间的撕裂）

/** 一个矩形，px，Y 向下（屏幕/视图/窗口坐标都用它） */
data class FrostRectPx(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val widthPx: Float get() = right - left
    val heightPx: Float get() = bottom - top
}

/**
 * 模糊纹理上的一块采样窗口。
 *
 * 轴方向是 **GL 约定：u 向右、v 向上，v=0 是画面底边**。上屏那侧若要改用 Y 向下的取样语义
 * （Canvas / Compose 读回纹理），记得先 `t = 1 - v` 换算，别拿 vMin 当顶边用。
 */
data class FrostUvRect(val uMin: Float, val uMax: Float, val vMin: Float, val vMax: Float) {

    /**
     * 摊平成 TRIANGLE_STRIP 的四点纹理坐标（顺序与 [GlRenderEngine] 的顶点同构：
     * 左下、右下、左上、右上），写进调用方**已复用好的**数组 ⇒ 每帧零分配。
     */
    fun fillQuadTexCoords(out: FloatArray) {
        require(out.size >= 8) { "四点纹理坐标要 8 个 float，实际 ${out.size}" }
        out[0] = uMin; out[1] = vMin
        out[2] = uMax; out[3] = vMin
        out[4] = uMin; out[5] = vMax
        out[6] = uMax; out[7] = vMax
    }
}

/**
 * 卡片矩形 → 采样 UV 所需的全部几何（一次原子发布，见 [FrostSnapshot]）。
 *
 * @param contentRectInViewPx 等比画面在**承载视图**里的矩形（[GlRenderEngine.contentRect] 的语义）
 * @param viewOriginXInWindowPx 承载视图原点在 app 窗口里的 X（px）——卡片矩形是窗口坐标，不减就对不上
 * @param viewOriginYInWindowPx 同上，Y
 * @param texWidthPx 模糊 RT 宽（px），用于把采样窗口对齐到整纹素
 * @param texHeightPx 模糊 RT 高（px）
 */
data class FrostGeometry(
    val contentRectInViewPx: FrostRectPx,
    val viewOriginXInWindowPx: Float,
    val viewOriginYInWindowPx: Float,
    val texWidthPx: Int,
    val texHeightPx: Int
)

/**
 * 模糊 RT 与它对应的那块等比画面矩形（两者必须同帧，否则换算会错一整帧）。
 *
 * GL 线程算好后整体替换一枚新实例；主线程只读不换。[matches] 用来判"这帧和上帧等价、别再分配新快照"
 * ——形参刻意全是基础类型而不是 [FrostRectPx]，因为这条判断**每帧都跑**，
 * 传对象就得先造对象，那正是 :1057-1061 注释里骂过的每帧分配。
 * 它同时也是"翻转 A/B 不该引起尺寸抖动"的判据本体（[frostRtNeedsRebuild] 同样不看开关）。
 */
data class FrostSnapshot(
    val textureId: Int,
    val texWidthPx: Int,
    val texHeightPx: Int,
    val contentRectInViewPx: FrostRectPx
) {
    fun matches(
        textureId: Int,
        texWidthPx: Int,
        texHeightPx: Int,
        contentLeftPx: Float,
        contentTopPx: Float,
        contentRightPx: Float,
        contentBottomPx: Float
    ): Boolean =
        this.textureId == textureId &&
            this.texWidthPx == texWidthPx &&
            this.texHeightPx == texHeightPx &&
            this.contentRectInViewPx.left == contentLeftPx &&
            this.contentRectInViewPx.top == contentTopPx &&
            this.contentRectInViewPx.right == contentRightPx &&
            this.contentRectInViewPx.bottom == contentBottomPx
}

/** 当前这帧的模糊纹理（id + 尺寸）；链本体与引擎之间的一次性交接物 */
data class FrostRenderTarget(val textureId: Int, val widthPx: Int, val heightPx: Int)

// endregion

// region 纯函数

/**
 * 等比画面尺寸 → 离屏 RT 尺寸。
 *
 * 三条规则，逐条都有单测：
 * 1. 任一边 ≤0（预览尺寸回报为 0 帧、承载还没布局、会话没建好）⇒ 返回 false = **不建链**，
 *    绝不硬给一个 1×1 去蒙：那会让模糊区错位且不报错，比"不可用"更坏；
 * 2. 否则按 [FROST_DOWNSCALE] 降采样并**向上取整**（向下取整会在奇数边长时把最右/最下一列像素挤掉，
 *    卡片贴画面右缘时采样窗口少一个纹素）；
 * 3. 最长边过 [FROST_RT_MAX_EDGE] 时按最长边等比再缩，四舍五入且两边都保底 1。
 *
 * 结果写进调用方传进来的 `out[0]=宽 out[1]=高`，**不返回 Pair**：这条每帧都跑，
 * `Pair<Int, Int>` 就是一次对象分配 + 两个装箱（引擎里 `windowSize()` 那种返回 Pair 的接口
 * 只在主线程低频调用，热点路径上不许照抄）。:1057-1061 那段注释骂的就是这个。
 *
 * 注意这里**不**参照编码器面尺寸（`:341-343` 那条"必须与编码器逐像素相同"的契约只属于编码器
 * EGLSurface，离屏 RT 与它没有任何关系，故意让它独立按上屏方向收放）。
 */
fun frostRtSizeInto(out: IntArray, contentWidthPx: Int, contentHeightPx: Int): Boolean {
    if (out.size < 2) throw IllegalArgumentException("out 至少两个 int，实际 ${out.size}")
    if (contentWidthPx <= 0 || contentHeightPx <= 0) return false
    var w = ceilDiv(contentWidthPx, FROST_DOWNSCALE)
    var h = ceilDiv(contentHeightPx, FROST_DOWNSCALE)
    val longest = max(w, h)
    if (longest > FROST_RT_MAX_EDGE) {
        val k = FROST_RT_MAX_EDGE.toDouble() / longest
        w = max(1, (w * k).roundToInt())
        h = max(1, (h * k).roundToInt())
    }
    out[0] = w
    out[1] = h
    return true
}

/**
 * 要不要重建 RT 与 FBO（[FrostBlurChain] 每帧就问这一句）。
 *
 * 判据只看尺寸，**与 A/B 开关无关** ⇒ 翻转开关不会引起重建/尺寸抖动（#85 对照测的两组共用同一批资源）。
 * 目标非法（≤0）时返回 false：此时链由 [frostRtSizeInto] 返回 false 那一侧直接跳过，
 * 不该在这里顺手把已建好的资源拆掉——拆了下一帧又要建，那才是真抖动。
 */
fun frostRtNeedsRebuild(
    currentWidthPx: Int,
    currentHeightPx: Int,
    targetWidthPx: Int,
    targetHeightPx: Int
): Boolean {
    if (targetWidthPx <= 0 || targetHeightPx <= 0) return false
    return currentWidthPx != targetWidthPx || currentHeightPx != targetHeightPx
}

/**
 * 可分离高斯某一趟的取样位移（归一化纹理坐标，写入调用方复用的数组 ⇒ 每帧零分配）。
 *
 * 横向 = `(1/w, 0)`，纵向 = `(0, 1/h)`；半径倍数由着色器里的 `uOffset * k` 承担。
 * 尺寸非法（≤0）时**不写**数组并返回 false，调用方据此跳过本趟，免得拿 (0,0) 位移糊出一张不糊的图。
 */
fun fillFrostBlurOffset(out: FloatArray, horizontal: Boolean, texWidthPx: Int, texHeightPx: Int): Boolean {
    if (out.size < 2 || texWidthPx <= 0 || texHeightPx <= 0) return false
    if (horizontal) {
        out[0] = 1f / texWidthPx
        out[1] = 0f
    } else {
        out[0] = 0f
        out[1] = 1f / texHeightPx
    }
    return true
}

/**
 * 卡片窗口矩形 → 模糊 RT 的采样窗口。
 *
 * 换算三步（[FROST_DOWNSCALE] 与机型矩阵都已经烘在 RT 的建法里，这里只剩一次仿射）：
 * 1. 窗口 px −−减视图原点−→ 承载视图 px；
 * 2. 与等比画面矩形求交：卡片压在信箱黑边上的那截**不参与**采样（黑边没有被模糊的资格），
 *    完全不相交返回 null = 上层退回普通 scrim；
 * 3. 归一化到画面内的 0..1 并把 Y 翻成 GL 的 v（`v = 1 - y/画面高`），再**向外**取整到整纹素
 *    （下界 floor、上界 ceil）。
 *
 * 为什么要对齐整纹素：卡片每帧可能亚像素移动，不对齐的话采样窗口在两个纹素之间来回滑，
 * 模糊图虽然糊、但**亮度重心会跟着抖**，观感是卡片背后有东西在游动。向外取整保证采样区恒覆盖卡片
 * （最多多吃一个纹素 = N 屏幕 px，模糊下不可见），向内取整则会裁掉卡片边缘那一丝内容。
 *
 * 数值口径：位移先乘纹理边长再除画面边长（`dx * texW / cw`）并用 Double 计算。
 * 顺序反了先除后乘，本机竖屏那种"整数倍"情形会拿到 17.999998 而不是 18（72/608 已经是近似值），
 * floor 之后就差一个纹素——错得很小，但正是这种错在真机上只能靠"卡片右缘少一列"才发现。
 *
 * @return null 表示这帧这块卡片没有可用的模糊（几何退化、零面积卡片、与画面不相交、RT 还没建好）
 */
fun frostUvOfCard(cardInWindowPx: FrostRectPx, geometry: FrostGeometry): FrostUvRect? {
    if (geometry.texWidthPx <= 0 || geometry.texHeightPx <= 0) return null
    val content = geometry.contentRectInViewPx
    val cw = (content.right - content.left).toDouble()
    val ch = (content.bottom - content.top).toDouble()
    if (cw <= 0.0 || ch <= 0.0) return null

    val ox = geometry.viewOriginXInWindowPx.toDouble()
    val oy = geometry.viewOriginYInWindowPx.toDouble()
    val left = cardInWindowPx.left - ox
    val right = cardInWindowPx.right - ox
    val top = cardInWindowPx.top - oy
    val bottom = cardInWindowPx.bottom - oy
    if (right <= left || bottom <= top) return null

    val clampedLeft = max(left, content.left.toDouble())
    val clampedRight = min(right, content.right.toDouble())
    val clampedTop = max(top, content.top.toDouble())
    val clampedBottom = min(bottom, content.bottom.toDouble())
    if (clampedRight <= clampedLeft || clampedBottom <= clampedTop) return null

    val tw = geometry.texWidthPx.toDouble()
    val th = geometry.texHeightPx.toDouble()
    val leftTexel = floor((clampedLeft - content.left) * tw / cw)
    val rightTexel = ceil((clampedRight - content.left) * tw / cw)
    // v 向上：画面顶边对应 th，所以用「画面高 − y」而不是 y
    val bottomTexel = floor((ch - (clampedBottom - content.top)) * th / ch)
    val topTexel = ceil((ch - (clampedTop - content.top)) * th / ch)

    val uMin = (leftTexel / tw).coerceIn(0.0, 1.0).toFloat()
    val uMax = (rightTexel / tw).coerceIn(0.0, 1.0).toFloat()
    val vMin = (bottomTexel / th).coerceIn(0.0, 1.0).toFloat()
    val vMax = (topTexel / th).coerceIn(0.0, 1.0).toFloat()
    if (uMax <= uMin || vMax <= vMin) return null
    return FrostUvRect(uMin, uMax, vMin, vMax)
}

private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

/**
 * 等比居中（信箱）矩形：**从 [GlRenderEngine.updateContentRect] 里抽出来的那一半算术**，
 * 两边共用这一个真源。
 *
 * 为什么值得抽出来：RT 尺寸与卡片 → UV 的换算全都假设「模糊纹理覆盖的就是这块矩形」，
 * 而这块矩形由一次 `roundToInt` 决定。留在引擎里它在 JVM 测不到，我就只能给单测**编**一组基线数字
 * ——编出来的期望值一旦与被抽走的那段算术不一致，测试照样全绿，这是本项目栽过的"期望值恰好等于
 * 错误实现的输出"的另一种形态。抽出来之后，竖屏/横屏两条基线是这条函数**算出来的**，不是写出来的。
 *
 * 口径必须与引擎逐字一致，所以这里刻意用 Float 而不是 Double 算比例：
 * `scale = min(面宽/内容宽, 面高/内容高)` → 边长按 `roundToInt` 并保底 1 → 剩下的余量左右/上下平分。
 * 余量是奇数时**左比右多 1、上比下多 1**（整数除法向零取整），那 1px 由卡片换算的纹素对齐吸收。
 *
 * 退化口径与引擎一致：任一侧尺寸非法 ⇒ 等比区 = 整个面（等于不做信箱，画面比例交给承载视图自己）。
 *
 * @param surfaceWidthPx 承载面（= 那条 EGL 窗口面 = 承载视图）宽
 * @param surfaceHeightPx 承载面高
 * @param contentWidthPx 内容（按 [GlRenderEngine] 的包围盒公式算出的旋转后画面）宽
 * @param contentHeightPx 内容高
 */
fun letterboxContentRectInPx(
    surfaceWidthPx: Int,
    surfaceHeightPx: Int,
    contentWidthPx: Float,
    contentHeightPx: Float
): FrostRectPx {
    val surfaceW = surfaceWidthPx.coerceAtLeast(0).toFloat()
    val surfaceH = surfaceHeightPx.coerceAtLeast(0).toFloat()
    if (surfaceWidthPx <= 0 || surfaceHeightPx <= 0) return FrostRectPx(0f, 0f, surfaceW, surfaceH)
    if (contentWidthPx <= 0f || contentHeightPx <= 0f) {
        return FrostRectPx(0f, 0f, surfaceW, surfaceH)
    }
    val scale = min(surfaceWidthPx / contentWidthPx, surfaceHeightPx / contentHeightPx)
    if (scale <= 0f) return FrostRectPx(0f, 0f, surfaceW, surfaceH)
    val drawW = (contentWidthPx * scale).roundToInt().coerceAtLeast(1)
    val drawH = (contentHeightPx * scale).roundToInt().coerceAtLeast(1)
    val left = (surfaceWidthPx - drawW) / 2
    val top = (surfaceHeightPx - drawH) / 2
    return FrostRectPx(left.toFloat(), top.toFloat(), (left + drawW).toFloat(), (top + drawH).toFloat())
}

/**
 * 高斯核在**屏幕空间**的等效取样半径（px）：半径纹素数 × 降采样倍数。
 *
 * 这是"底层内容到底糊了多开"的唯一可核对数字，#84 验收要拿它和卡片尺寸对表：
 * 一张 108×60 px（54dp @ density 2）的 Dock 卡片必须明显大于 2×半径，否则整块背板采到的
 * 是同一个颜色，看不出底层走向，"毛玻璃"就名不副实。
 */
fun frostScreenRadiusPx(): Int = (FROST_BLUR_WEIGHTS.size - 1) * FROST_DOWNSCALE

// endregion

// region 接缝

/**
 * 毛玻璃背板能力（#84）。与 [DisplaySurfaceReceiver] 同族：都是对 `PreviewSink` 的必要补充，
 * 不是改名——UI 侧只认这五条，配置真源与开关决策留在 `ui/`，本层只提供入口。
 *
 * 两种渲染模式的实现分别是：
 * - [GlRenderEngine]：真离屏链，开关生效在下一次 `postGl`；
 * - [DirectSink]：DIRECT 完全旁路 GL（`Camera2Engine` 直连会话），**永远**回答"不可用"，
 *   UI 据此退成普通 scrim。这不是偷懒，是这条路的物理上限。
 */
interface FrostBlurProvider {

    /**
     * A/B 开关，**默认关**。可在不重启进程的情况下反复翻转（#85 的「模糊关 vs 模糊开」对照要它）。
     *
     * 语义边界（接线时按这几条理解，别当渲染帧信号用）：
     * - 线程安全，任意线程可调；只写一枚 @Volatile 意图 + 一次 `postGl`，不等 GL 结果；
     * - 生效后模糊**在下一个相机帧**产出（不是立刻重画一帧）——这样翻转不会多塞一帧重复帧给编码器，
     *   录出来的东西与开关无关（docs/plan/14 §二 定版口径）；
     * - 关掉时立即从 [isFrostBlurAvailable] 变成 false（不等下一帧），但资源保留不删，
     *   再打开不重建、不闪；
     * - 重复设同一个值是无操作（不会重编译着色器、不会重建 RT）。
     */
    fun setFrostBlurEnabled(enabled: Boolean)

    /** 开关意图（不表示"真的糊出来了"，那要看 [isFrostBlurAvailable]） */
    fun isFrostBlurEnabled(): Boolean

    /**
     * 现在能不能拿到模糊纹理：DIRECT 模式、相机还没出帧、离屏链建不起来（驱动/显存）时都是 false。
     * false 是**正常状态**而不是错误，上层画普通 scrim 即可。
     */
    fun isFrostBlurAvailable(): Boolean

    /**
     * 模糊纹理句柄与尺寸；不可用时返回 null。
     *
     * 拿到的是一枚普通 `GL_TEXTURE_2D`（RGBA8，GL_LINEAR + CLAMP_TO_EDGE），内容 = **已按屏幕朝向摆正、
     * 已糊化**的等比画面，采样口径见 [frostUvOfCard]。只能在同一枚 EGL 上下文、同一条 GL 线程上采样
     * （全工程只有这一条上下文，`eglCreateContext` 的 share 实参是 `EGL_NO_CONTEXT`）。
     */
    fun frostRenderTarget(): FrostRenderTarget?

    /**
     * 卡片矩形换算所需的那份几何。
     *
     * @param viewOriginXInWindowPx 承载视图（`TextureView`）在 app 窗口里的原点 X（px）——
     *   它嵌在按 `aspectRatio` 收过的盒子里，不是铺满整屏，UI 侧只有视图自己知道这个偏移
     * @param viewOriginYInWindowPx 同上，Y
     * @return 不可用时 null（等价于 [isFrostBlurAvailable] 为 false）
     */
    fun frostGeometry(viewOriginXInWindowPx: Float, viewOriginYInWindowPx: Float): FrostGeometry?
}

// endregion
