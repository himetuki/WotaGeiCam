package com.wotagei.cam.camera

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
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
 * - **组合根坐标**：`positionInWindow()` 量的就是这一层（ComposeView 在窗口里的位置 + 尺寸）。
 *   视图原点是**由根尺寸与视图尺寸反推**的（[frostViewOriginInto]，前提是 `CameraScreen.previewStage`
 *   那枚 `Box(contentAlignment = Center)`），根尺寸只有 View 侧知道、视图尺寸只有 GL 侧知道 ⇒
 *   两个数各自过一次 [FrostCardTable]，在 GL 侧汇合。
 * - **纹理 UV**：u 向右、**v 向上**（GL 约定，v=0 是画面底边）；模糊 RT 用与上屏 pass 同一套纹理坐标
 *   建立，所以 RT 里那张图与屏幕上等比画面同朝向同内容，换算只管等比画面、不必再管机型矩阵。
 *
 * #84 步骤 2 走的是 A2 混合路线：**GL 只出「模糊纹理 + 圆角裁切 + 底板色」这一块板**，描边、选中高亮、
 * 图标、文字仍由 Compose 叠在 TextureView 之上画。所以 `ui/` 与 `camera/` 之间只共享一张矩形表
 * （[FrostCardTable]），不共享任何视觉；片元里的圆角用 rounded-rect SDF 判掉（[frostRoundedRectSdfPx]
 * 是那条 GLSL 的 Kotlin 镜像，两边同一条算式，镜像有手算单测、GLSL 只有真机能证）。
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
    val out = FloatArray(4)
    val ok = frostUvInto(
        out = out,
        cardLeftPx = cardInWindowPx.left,
        cardTopPx = cardInWindowPx.top,
        cardRightPx = cardInWindowPx.right,
        cardBottomPx = cardInWindowPx.bottom,
        contentLeftPx = geometry.contentRectInViewPx.left,
        contentTopPx = geometry.contentRectInViewPx.top,
        contentRightPx = geometry.contentRectInViewPx.right,
        contentBottomPx = geometry.contentRectInViewPx.bottom,
        viewOriginXInWindowPx = geometry.viewOriginXInWindowPx,
        viewOriginYInWindowPx = geometry.viewOriginYInWindowPx,
        texWidthPx = geometry.texWidthPx,
        texHeightPx = geometry.texHeightPx
    )
    if (!ok) return null
    return FrostUvRect(out[0], out[1], out[2], out[3])
}

/**
 * [frostUvOfCard] 的**标量本体**（同一个算法，两个入口）。
 *
 * 为什么必须再开一条标量路：上屏那侧每帧要为**每一枚**卡片算一次 UV，走对象版就是每帧
 * 每枚卡片 2 次分配（一个入参 Rect + 一个返回 Rect）——正是 :1057-1061 那段注释骂过的东西。
 * 结果写进调用方复用的 `out[0..3] = uMin, uMax, vMin, vMax`。
 *
 * 口径、取整方向、Double 计算顺序全部与 [frostUvOfCard] 的文档逐字同一条（两者是同一份实现，
 * 不存在"UI 侧与 GL 侧各算一套"的分叉）。
 *
 * @return false = 这帧这块卡片没有可用模糊（几何退化、零面积、与画面不相交、RT 还没建好）
 */
fun frostUvInto(
    out: FloatArray,
    cardLeftPx: Float,
    cardTopPx: Float,
    cardRightPx: Float,
    cardBottomPx: Float,
    contentLeftPx: Float,
    contentTopPx: Float,
    contentRightPx: Float,
    contentBottomPx: Float,
    viewOriginXInWindowPx: Float,
    viewOriginYInWindowPx: Float,
    texWidthPx: Int,
    texHeightPx: Int
): Boolean {
    if (out.size < 4) throw IllegalArgumentException("UV 要 4 个 float，实际 ${out.size}")
    if (texWidthPx <= 0 || texHeightPx <= 0) return false
    val cw = (contentRightPx - contentLeftPx).toDouble()
    val ch = (contentBottomPx - contentTopPx).toDouble()
    if (cw <= 0.0 || ch <= 0.0) return false

    val ox = viewOriginXInWindowPx.toDouble()
    val oy = viewOriginYInWindowPx.toDouble()
    val left = cardLeftPx.toDouble() - ox
    val right = cardRightPx.toDouble() - ox
    val top = cardTopPx.toDouble() - oy
    val bottom = cardBottomPx.toDouble() - oy
    if (right <= left || bottom <= top) return false

    val clampedLeft = max(left, contentLeftPx.toDouble())
    val clampedRight = min(right, contentRightPx.toDouble())
    val clampedTop = max(top, contentTopPx.toDouble())
    val clampedBottom = min(bottom, contentBottomPx.toDouble())
    if (clampedRight <= clampedLeft || clampedBottom <= clampedTop) return false

    val tw = texWidthPx.toDouble()
    val th = texHeightPx.toDouble()
    val leftTexel = floor((clampedLeft - contentLeftPx) * tw / cw)
    val rightTexel = ceil((clampedRight - contentLeftPx) * tw / cw)
    // v 向上：画面顶边对应 th，所以用「画面高 − y」而不是 y
    val bottomTexel = floor((ch - (clampedBottom - contentTopPx)) * th / ch)
    val topTexel = ceil((ch - (clampedTop - contentTopPx)) * th / ch)

    val uMin = (leftTexel / tw).coerceIn(0.0, 1.0).toFloat()
    val uMax = (rightTexel / tw).coerceIn(0.0, 1.0).toFloat()
    val vMin = (bottomTexel / th).coerceIn(0.0, 1.0).toFloat()
    val vMax = (topTexel / th).coerceIn(0.0, 1.0).toFloat()
    if (uMax <= uMin || vMax <= vMin) return false
    out[0] = uMin
    out[1] = uMax
    out[2] = vMin
    out[3] = vMax
    return true
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

// -------------------------------------------------- 上屏画板用的几何（#84 步骤 2，A2 路线）

/**
 * 承载视图原点在 app 窗口里的位置（px，Y 向下），写进 `out[0]=X out[1]=Y`。
 *
 * ⚠ **这是整条接线上唯一的推断量**，说清楚它推的是什么：
 * `CameraScreen.previewStage` 是 `Box(Modifier.fillMaxSize(), contentAlignment = Center)`，
 * 里面再套一枚 `aspectRatio` 盒子，`CameraSurface` 以 `matchParentSize()` 填满那枚盒子
 * ⇒ 承载视图 = 「组合根里居中的一块」。于是
 * `origin = 根在窗口里的左上角 + (根尺寸 − 视图尺寸) / 2`。
 * 根尺寸只有 View 侧量得到（`LocalView`），视图尺寸只有 GL 侧知道（`onDisplaySurfaceChanged` 的回报），
 * 两个数各过一次 [FrostCardTable] 在这里汇合。
 *
 * 返回 false 的场景全部是"前提不成立"，上层据此**不画板**（比拿一个错原点画出去诚实）：
 * 根或视图尺寸非法、视图比根还大（居中这个前提已经崩了，八成是布局改了没同步这里）。
 */
fun frostViewOriginInto(
    out: FloatArray,
    rootLeftPx: Float,
    rootTopPx: Float,
    rootWidthPx: Float,
    rootHeightPx: Float,
    viewWidthPx: Int,
    viewHeightPx: Int
): Boolean {
    if (out.size < 2) throw IllegalArgumentException("视图原点要 2 个 float，实际 ${out.size}")
    if (rootWidthPx <= 0f || rootHeightPx <= 0f) return false
    if (viewWidthPx <= 0 || viewHeightPx <= 0) return false
    if (rootWidthPx < viewWidthPx || rootHeightPx < viewHeightPx) return false
    out[0] = rootLeftPx + (rootWidthPx - viewWidthPx) / 2f
    out[1] = rootTopPx + (rootHeightPx - viewHeightPx) / 2f
    return true
}

/**
 * 卡片窗口矩形 → 上屏画板要的 6 个数，写进调用方复用的 `out`：
 * `0=NDC 中心X 1=NDC 中心Y 2=NDC 半宽 3=NDC 半高 4=半宽px 5=半高px`。
 *
 * NDC 是「整块窗口面 = 承载视图」的裁剪空间（x 向右、y **向上**），所以 Y 要翻一次：
 * `ndcY = 1 − 2·cy/视图高`。半宽直接是 `宽px / 视图宽`（整个 NDC 跨度 2 摊在视图宽上，
 * 半宽 = 宽/(2·视图宽)·2 = 宽/视图宽），别写成 `宽 / (2·视图宽)`——那会把每块板缩成一半大。
 * 第 4/5 项是给片元算圆角 SDF 用的**像素**半轴：SDF 的过渡带要按屏幕像素给宽度，
 * 用 NDC 宽度的话一块横板与一块竖板的圆角过渡带会差出宽高比那么多。
 *
 * 退化（视图尺寸非法、零面积、反序矩形）返回 false 且**不写** out。
 */
fun frostPlateRectInto(
    out: FloatArray,
    cardLeftPx: Float,
    cardTopPx: Float,
    cardRightPx: Float,
    cardBottomPx: Float,
    viewOriginXPx: Float,
    viewOriginYPx: Float,
    viewWidthPx: Int,
    viewHeightPx: Int
): Boolean {
    if (out.size < 6) throw IllegalArgumentException("画板矩形要 6 个 float，实际 ${out.size}")
    if (viewWidthPx <= 0 || viewHeightPx <= 0) return false
    val vw = viewWidthPx.toFloat()
    val vh = viewHeightPx.toFloat()
    val left = cardLeftPx - viewOriginXPx
    val right = cardRightPx - viewOriginXPx
    val top = cardTopPx - viewOriginYPx
    val bottom = cardBottomPx - viewOriginYPx
    val wPx = right - left
    val hPx = bottom - top
    if (wPx <= 0f || hPx <= 0f) return false
    out[0] = (left + right) / 2f / vw * 2f - 1f
    out[1] = 1f - (top + bottom) / 2f / vh * 2f
    out[2] = wPx / vw
    out[3] = hPx / vh
    out[4] = wPx / 2f
    out[5] = hPx / 2f
    return true
}

/**
 * 把「圆角档位」落成这一枚卡片上的实际半径（px）。
 *
 * 负数 = 胶囊/圆形（`RoundedCornerShape(percent = 50)` 与 `CircleShape` 的半径就是短边一半，
 * 调用方量不到自己的尺寸所以只能送哨兵过来），非负 = 定值（14dp/18dp 那种令牌档）。
 * 无论走哪条，最后都夹进 `[0, 短边一半]`：
 * - 半径 0 → 直角板（SDF 退化公式自己会算对，不需要分支）；
 * - 半径 ≥ 短边一半 → 夹成短边一半（胶囊就是这个写法，不是特例）；
 * - 矩形退化成长条（短边 → 0）→ 半径跟着 → 0，不会出现"半径比板还厚"导致 SDF 把整块板判在外面
 *   （那是 `half - r` 变负之后 `length(max(q,0))` 算出正数的来源，板会凭空消失）。
 */
fun frostResolveRadiusPx(widthPx: Float, heightPx: Float, radiusPx: Float): Float {
    if (widthPx <= 0f || heightPx <= 0f) return 0f
    val halfShort = min(widthPx, heightPx) / 2f
    val wanted = if (radiusPx < 0f) halfShort else radiusPx
    return if (wanted < 0f) 0f else if (wanted > halfShort) halfShort else wanted
}

/**
 * rounded-rect 的**带符号距离**（px，板内为负、板外为正，0 就是轮廓）。
 * 与 [Shaders] 的 `frostPlateFragment` 里那三行是同一条算式，这里留一份能跑在 JVM 上的镜像：
 * 片元在真机上看不见摸不着，能静态证明的只有这条镜像 + 一条"GLSL 源码里必须有这三行"的字符串断言。
 *
 * 半径先过 [frostResolveRadiusPx] 夹一遍（与上屏那侧同一个函数，两边不会分叉）。
 */
fun frostRoundedRectSdfPx(localXpx: Float, localYpx: Float, halfWidthPx: Float, halfHeightPx: Float, radiusPx: Float): Float {
    val r = frostResolveRadiusPx(halfWidthPx * 2f, halfHeightPx * 2f, radiusPx)
    val qx = abs(localXpx) - (halfWidthPx - r)
    val qy = abs(localYpx) - (halfHeightPx - r)
    val outside = hypot(max(qx, 0f), max(qy, 0f))
    return outside + min(max(qx, qy), 0f) - r
}

/** [frostRoundedRectSdfPx] 的覆盖度（0=完全在外、1=完全在内），过渡带宽 `aaPx`，与片元那条同一条 */
fun frostCoverOfSdf(signedDistancePx: Float, aaPx: Float): Float {
    val half = if (aaPx <= 0f) 0.5f else aaPx
    val t = ((signedDistancePx + half) / (2f * half)).coerceIn(0f, 1f)
    return 1f - t * t * (3f - 2f * t)
}

// endregion

// region 矩形表（ui/ 写 · GL 读的霜底板表，#84 步骤 2）

/**
 * 「哪几枚卡片要霜、各自在窗口里的矩形、圆角半径、该档透光 alpha」这张表的**唯一落点**。
 *
 * 为什么是一张全局表而不是 `ui → provider` 的一次调用：A2 路线下 `ui/` 与 `camera/` 之间只共享这张表，
 * 不共享视觉；而持有引擎那枚实例的是 `CameraScreen`（`remember(renderMode) { GlRenderEngine() }`），
 * 底板矩形却只有 HUD 各节点自己在布局期量得到。让 GL 每帧来读这张表，`ui/` 就不必拿到引擎引用，
 * 引擎也不必认识 `ui/`。
 *
 * ## 每帧零分配
 * 表体是**构造期一次性分配的**两块 `FloatArray`（`data` 与读方无关，GL 侧的副本数组由调用方自带），
 * 写方是"取槽 → 写 7 个 float → 收口"，没有任何 List/Rect/Pair 出入。槽位从预填好的空闲栈里拿，
 * 用完还回去（`ArrayDeque` 的两个数组在 init 就分配好，运行期不再长）。
 *
 * ## 不撕裂（一次读要么整张新表、要么整张旧表）
 * 全局 `epoch` 当**序号锁**（seqlock）用：写方 `epoch++`（奇数=正在写）→ 改数据 → `epoch++`（偶数=稳定）；
 * 读方「读 epoch → 若奇数则重试」→ 拷数据 → 「再读 epoch，与第一次不等就重试」（最多 [READ_RETRY_LIMIT] 次）。
 * 三个关键点：
 * - 单字 float/int 写本身不撕裂（JVM 保证 32 位值的原子写；`epoch` 是 **Long 且 @Volatile**，也不给撕裂机会）；
 * - 数据写在两次 volatile 写**之间**，所以"看见了某个 epoch"就等于看见了它之前的全部写（release/acquire）；
 * - 拷到一半被写方插进来时，读方**不会**用那半份数据：第二次 epoch 不等 ⇒ 丢弃重来或沿用上一帧的副本。
 * 于是「新表头配旧矩形」这种组合在读方一侧根本不可能被用出去（读到的要么是完整一代，要么是上一代）。
 *
 * ## 谁在什么线程上写
 * - `ui/` 主线程：[writeHeader] / [writeCard] / [acquireSlot] / [releaseSlot] / [setUiEnabled]；
 *   各枚底板在自己的布局回调里写自己那一格，所以一格一次事务；写之前 HUD 会顺手刷一次表头，
 *   于是"视图原点与卡片矩形出自同一次布局"在实践里成立（epoch 保证的是不撕裂，不是同一次布局）。
 * - GL 线程：[tryReadInto]（读）与 [reportPlatesDrawn]（回报"这帧真画了板"）。
 * - `ui/` 主线程读：[isPlatesDrawn] / [hasUiEnabled]。
 *
 * 这张表**不是**容器归属的真源——控件在哪枚容器里仍由 `ui/CameraPills.kt` 的 `pillAnchorWriters`
 * 与 `HudLayer` 的位置表说了算；这里只记"这一格当前有这么一块板"，槽位随布局节点生死。
 */
object FrostCardTable {

    /** 表头 float 数：根左上 X/Y、根宽、根高、底板色 R/G/B、UI 开关意图（1f/0f） */
    const val HEADER_FLOATS = 8

    /** 一枚卡片的 float 数：窗口 px 的四边、圆角半径（负数=短边一半）、底板色不透明度、在场位 */
    const val SLOT_FLOATS = 7

    /**
     * 槽位上限。录制页同时在场的底板（手算，与 `ui/HudLayer` 那几处注册点一一对）：
     * 顶栏元信息胶囊 1 + 顶栏录制那颗 1 + 左竖 Dock 1 + 右竖 Dock 1 + 底栏底板 1 = 5 枚注册板，
     * 其余条目都"吃父板的玻璃"不占槽（见 `HudFrostCard.asPlate`）。留到 8 是编辑页那一轮并存的余量；
     * 满了不静默丢弃：[acquireSlot] 返回 -1，注册点据此**保持旧观感**（fill 不让位），
     * 而不是画一块半块板。
     */
    const val SLOT_CAPACITY = 8

    /** 读方重试上限：3 次还撞车就沿用上一帧副本（宁可慢一帧也不要脏数据） */
    const val READ_RETRY_LIMIT = 3

    /** 整张表的 float 数（读方的副本数组按这个长度预分配） */
    const val TABLE_FLOATS = HEADER_FLOATS + SLOT_CAPACITY * SLOT_FLOATS

    /** 表内偏移：卡片块从 HEADER_FLOATS 起，第 i 格在 HEADER_FLOATS + i * SLOT_FLOATS */
    const val CARD_LEFT = 0
    const val CARD_TOP = 1
    const val CARD_RIGHT = 2
    const val CARD_BOTTOM = 3
    const val CARD_RADIUS = 4
    const val CARD_ALPHA = 5
    const val CARD_PRESENT = 6

    private val data = FloatArray(TABLE_FLOATS)

    /** 空闲槽栈（init 就填满，运行期只有 add/remove，不再长） */
    private val freeSlots = ArrayDeque<Int>(SLOT_CAPACITY).apply {
        for (i in SLOT_CAPACITY - 1 downTo 0) addLast(i)
    }

    @Volatile
    private var epoch = 0L

    @Volatile
    private var platesDrawn = false

    /** 取一个槽位（主线程，布局期）。满了返回 -1，调用方据此退回旧观感 */
    fun acquireSlot(): Int = synchronized(freeSlots) { freeSlots.removeLastOrNull() ?: -1 }

    /** 还槽（主线程，节点离开组合时）：先把在场位清掉，否则 GL 会画一块已经不存在的板 */
    fun releaseSlot(slot: Int) {
        if (slot < 0 || slot >= SLOT_CAPACITY) return
        writeSlotAt(slot, present = false)
        synchronized(freeSlots) { if (!freeSlots.contains(slot)) freeSlots.addLast(slot) }
    }

    /**
     * 写一整块板的矩形（主线程）。`radiusPx < 0` = 短边一半那一档（胶囊/圆形），
     * 由 GL 侧的 [frostResolveRadiusPx] 现场夹；`alpha` 是底板色的不透明度（透光 = 1 − alpha）。
     *
     * 四边反序或零面积一律当"这帧没有这块板"（在场位写 0），与 [frostUvInto] 的退化口径一致。
     */
    fun writeCard(
        slot: Int,
        leftPx: Float,
        topPx: Float,
        rightPx: Float,
        bottomPx: Float,
        radiusPx: Float,
        alpha: Float
    ): Boolean {
        if (slot < 0 || slot >= SLOT_CAPACITY) return false
        if (rightPx <= leftPx || bottomPx <= topPx) {
            writeSlotAt(slot, present = false)
            return false
        }
        val e = beginWrite()
        val base = HEADER_FLOATS + slot * SLOT_FLOATS
        data[base + CARD_LEFT] = leftPx
        data[base + CARD_TOP] = topPx
        data[base + CARD_RIGHT] = rightPx
        data[base + CARD_BOTTOM] = bottomPx
        data[base + CARD_RADIUS] = radiusPx
        data[base + CARD_ALPHA] = alpha.coerceIn(0f, 1f)
        data[base + CARD_PRESENT] = 1f
        endWrite(e)
        return true
    }

    /** 写表头（主线程）：组合根矩形 + 底板色 + UI 开关意图。与卡片同一把锁，一次事务 */
    fun writeHeader(
        rootLeftPx: Float,
        rootTopPx: Float,
        rootWidthPx: Float,
        rootHeightPx: Float,
        tintRed: Float,
        tintGreen: Float,
        tintBlue: Float,
        uiEnabled: Boolean
    ) {
        val e = beginWrite()
        data[0] = rootLeftPx
        data[1] = rootTopPx
        data[2] = rootWidthPx
        data[3] = rootHeightPx
        data[4] = tintRed
        data[5] = tintGreen
        data[6] = tintBlue
        data[7] = if (uiEnabled) 1f else 0f
        endWrite(e)
    }

    /** 只改开关意图（设置页翻转时立刻可见，不必等一次布局）；表头其余字段照抄原值 */
    fun setUiEnabled(enabled: Boolean) {
        val e = beginWrite()
        data[7] = if (enabled) 1f else 0f
        endWrite(e)
    }

    /** 读方：UI 侧的开关意图（GL 线程每帧问一次） */
    fun hasUiEnabled(): Boolean {
        val e = epoch
        if (e and 1L != 0L) return false
        return data[7] != 0f
    }

    /**
     * 读方：把整张表拷进调用方复用的 `out`（长度必须 ≥ [TABLE_FLOATS]）。
     *
     * 返回 ≥0 = 有效卡片数，第 i 块在 `out[HEADER_FLOATS + i * SLOT_FLOATS]`（**压实**过：只在场的槽位
     * 才占一块，读方不用跳过空槽）；返回 -1 = 撞车到重试上限之外，或 `out` 太短 ⇒
     * 调用方沿用上一帧的副本（慢一帧，但不脏）。表头恒在 `out[0 until HEADER_FLOATS]`。
     */
    fun tryReadInto(out: FloatArray): Int {
        if (out.size < TABLE_FLOATS) return -1
        var attempt = 0
        while (attempt < READ_RETRY_LIMIT) {
            attempt++
            val before = epoch
            if (before and 1L != 0L) continue
            var written = 0
            for (i in 0 until HEADER_FLOATS) out[i] = data[i]
            for (slot in 0 until SLOT_CAPACITY) {
                val base = HEADER_FLOATS + slot * SLOT_FLOATS
                if (data[base + CARD_PRESENT] == 0f) continue
                val dst = HEADER_FLOATS + written * SLOT_FLOATS
                for (k in 0 until SLOT_FLOATS) out[dst + k] = data[base + k]
                written++
            }
            if (epoch == before) return written
        }
        return -1
    }

    /** GL 线程回报：这一帧到底画没画板（唯一一条 GL→UI 的反向通道，单布尔不给撕裂机会） */
    fun reportPlatesDrawn(drawn: Boolean) {
        if (platesDrawn != drawn) platesDrawn = drawn
    }

    /** UI 侧读：GL 现在真在画板吗（false = 底板保持旧的纯色 fill，观感与接霜前逐字相同） */
    fun isPlatesDrawn(): Boolean = platesDrawn

    // 诊断/测试用：当前占用槽数（不是渲染路径，允许加锁）
    internal fun usedSlotCount(): Int = synchronized(freeSlots) { SLOT_CAPACITY - freeSlots.size }

    private fun writeSlotAt(slot: Int, present: Boolean) {
        val e = beginWrite()
        data[HEADER_FLOATS + slot * SLOT_FLOATS + CARD_PRESENT] = if (present) 1f else 0f
        endWrite(e)
    }

    private fun beginWrite(): Long {
        val e = epoch + 1L
        epoch = e
        return e
    }

    private fun endWrite(startedAt: Long) {
        epoch = startedAt + 1L
    }
}

// endregion

// region 接缝

/**
 * 毛玻璃背板能力（#84）。与 [DisplaySurfaceReceiver] 同族：都是对 `PreviewSink` 的必要补充，
 * 不是改名——UI 侧只认这五条，配置真源与开关决策留在 `ui/`，本层只提供入口。
 *
 * 两种渲染模式的实现分别是：
 * - [GlRenderEngine]：真离屏链 + 上屏画板，开关生效在下一次 `postGl`；
 * - [DirectSink]：DIRECT 完全旁路 GL（`Camera2Engine` 直连会话），**永远**回答"不可用"，
 *   UI 据此退成普通 scrim。这不是偷懒，是这条路的物理上限。
 *
 * ⚠ A2 混合路线定下来之后，这五条里**没有一条是渲染通路**：画板在 GL 里画，`ui/` 交给画板的唯一
 * 数据是 [FrostCardTable] 那张矩形表。这里保留的意义只剩两点——
 * ① [isFrostBlurAvailable] 一类查询是**取证/工装**入口（#85 的对照组要用它判"这帧到底有没有霜"）；
 * ② [setFrostBlurEnabled] 是**同一枚门的直注入口**（见它的注释：生产开关走设置页 → 矩形表，
 * 这里注进来的意图会被下一帧的表位覆盖，想长期开就去设置页打开）。
 * 别把它当成"UI 会调的接口"再往回接一条渲染通路，那是 A 路线，已经因为拿不到纹理句柄被否了。
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
     * - 重复设同一个值是无操作（不会重编译着色器、不会重建 RT）；
     * - **生产真源是设置页写进 [FrostCardTable] 的那一位**：引擎每帧把表位搬进这里落的门，
     *   所以直接调本函数的意图活不过下一帧（工装请改表位，或让 UI 不写表）。
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
     * （全工程只有这一条上下文，`eglCreateContext` 的 share 实参是 `EGL_NO_CONTEXT`）——
     * 这句话就是 A2 路线的全部理由：`ui/` 拿不到它，所以画板必须由 GL 自己画。
     */
    fun frostRenderTarget(): FrostRenderTarget?

    /**
     * 卡片矩形换算所需的那份几何（**取证/工装入口**，A2 之后上屏那侧不再调它——
     * 换算与画板都发生在 GL 线程，见 [FrostCardTable] 与 [frostUvInto]）。
     *
     * @param viewOriginXInWindowPx 承载视图（`TextureView`）在 app 窗口里的原点 X（px）——
     *   它嵌在按 `aspectRatio` 收过的盒子里，不是铺满整屏，UI 侧只有视图自己知道这个偏移
     *   （实测口径见 [frostViewOriginInto]：由组合根尺寸与 GL 回报的视图尺寸反推）
     * @param viewOriginYInWindowPx 同上，Y
     * @return 不可用时 null（等价于 [isFrostBlurAvailable] 为 false）
     */
    fun frostGeometry(viewOriginXInWindowPx: Float, viewOriginYInWindowPx: Float): FrostGeometry?
}

// endregion
