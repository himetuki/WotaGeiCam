package com.wotagei.cam.ui

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow

/**
 * 霜矩形「在哪套坐标系里量」的统一换算（#84 步骤 2 之后的坐标系修正）。
 *
 * ## 缺陷与修法
 * 旧口径把 `positionInWindow()`（= `localToWindow`，会走过**祖先**各层 `graphicsLayer` 的
 * 平移/缩放）当作卡片位置，再拼上 `size`（布局尺寸，不含缩放）。分屏把整页套在
 * `CameraScreen` 那层 `graphicsLayer{ scaleX = scaleY = s }` 里时，卡片位置被缩成 s 倍而宽度不减
 * ⇒ 表里的矩形"位置缩了、宽度没缩"，GL 再把它画进同样会被合成器缩放的承载视图面 ⇒ 板与
 * Compose 描边错开且偏大。
 *
 * 修法：卡片矩形改在**承载视图局部坐标系**里量——`preview.localPositionOf(card, …)` 会把卡片与
 * 预览盒的**共同祖先变换抵消掉**（分屏那层缩放就在共同祖先里），于是分屏/窗口平移一律不影响结果；
 * 而 GL 画的正是这枚视图自己的 EGL 面，两边同一个空间。
 *
 * ## 诚实边界
 * - [viewLocalRectInto] 只在两枚坐标都量得到时给值（否则调用方回退窗口系）；
 * - 本文件的换算函数是**纯算术**，可以在 JVM 上直接测；唯一带 Android 依赖的是两个取
 *   [LayoutCoordinates] 的入口，它们只做"取四边"这一件事。
 */

/**
 * 跨坐标系读数失败是否已记过日志（进程级一次性标志）。
 *
 * **只记一次、不逐帧刷屏**：这些读点在每帧的布局/绘制回调里跑，而跨树/未 attached 的状态会一直
 * 持续，逐帧 `Log` 会把 logcat 冲垮、淹没真正有用的日志。用 `@Volatile` 布尔一次性置位是这里最省的
 * 写法（不必按 tag/时限维护节流状态机）；没选 `Log.isLoggable` 是因为默认档位可能整条不输出，
 * 反而把这条唯一诊断也丢了。
 */
@Volatile
private var frostHierarchyFailureLogged = false

/** 记一次「跨树 / 未 attached」读数失败并置位一次性标志；见 [frostHierarchyFailureLogged]。 */
private fun noteHierarchyFailure(where: String, e: RuntimeException) {
    if (frostHierarchyFailureLogged) return
    frostHierarchyFailureLogged = true
    Log.w("FrostSpace", "$where 坐标系换算失败，回退窗口系：${e.javaClass.simpleName}: ${e.message}")
}

/**
 * 表头 `HEADER_CARD_SPACE` 那一位的真源：承载预览盒的坐标登记了 ⇒ 视图局部系；没登记 ⇒ 窗口系。
 *
 * 单独做成函数而不是就地写 `preview != null`，是为了让"写侧这一位怎么来的"只有一条可被守卫钉住的
 * 出处（读侧按存储位还原，见 `FrostPlatePass.draw`）。
 */
fun frostSpaceIsViewLocal(previewProvided: Boolean): Boolean = previewProvided

/**
 * 卡片矩形（**承载视图局部坐标**四边，px）写进 `out[0..3] = left, top, right, bottom`。
 *
 * [card] 是注册点自己那枚节点的坐标，[preview] 是 `CameraScreen.previewStage` 里承载预览的那枚
 * `aspectRatio` 盒子的坐标。两者同在一个共同祖先（分屏 `graphicsLayer`）之内时，
 * [LayoutCoordinates.localPositionOf] 会把那层祖先变换抵消，量出来就是视图局部坐标。
 *
 * **约束**：[card] 与 [preview] 必须**同属一个 LayoutNode 树**——注册点不得落在 Popup/Dialog
 * 这类独立窗口里（那样两枚坐标不在同一层级，`localPositionOf` 会抛
 * `IllegalArgumentException("layouts are not part of the same hierarchy")`）。本函数把这一族
 * `RuntimeException`（跨树的 `IllegalArgumentException`、未 attached 链上的 NPE）连同"未 attached"
 * 一并收敛成 false，**只记一次日志**（[noteHierarchyFailure]），调用方回退窗口系/不写表，不让一次
 * app 级异常打断整帧布局。
 *
 * @return false = [card] 或 [preview] 未量到/未 attached/跨树（调用方据此**回退窗口系**）；
 *   矩形退化（零/负面积）也算 false
 */
fun viewLocalRectInto(out: FloatArray, card: LayoutCoordinates?, preview: LayoutCoordinates?): Boolean {
    if (out.size < 4) throw IllegalArgumentException("视图局部矩形要 4 个 float，实际 ${out.size}")
    if (card == null || preview == null) return false
    // 未 attached 的链上 localPositionOf 有 NPE 分支；跨树抛 IllegalArgumentException——两者都是
    // RuntimeException。**只收敛这一族，不用 runCatching**：后者连 OOM/断言错误这类 Throwable 也一起
    // 吞，会把"跨树"以外的真故障静默降级成窗口系（本函数在每帧布局回调里跑，掩盖一次就可能掩盖很久）。
    if (!card.isAttached || !preview.isAttached) return false
    val topLeft = try {
        preview.localPositionOf(card, Offset.Zero)
    } catch (e: RuntimeException) {
        noteHierarchyFailure("viewLocalRectInto.topLeft", e)
        return false
    }
    val bottomRight = try {
        preview.localPositionOf(card, Offset(card.size.width.toFloat(), card.size.height.toFloat()))
    } catch (e: RuntimeException) {
        noteHierarchyFailure("viewLocalRectInto.bottomRight", e)
        return false
    }
    if (bottomRight.x <= topLeft.x || bottomRight.y <= topLeft.y) return false
    out[0] = topLeft.x
    out[1] = topLeft.y
    out[2] = bottomRight.x
    out[3] = bottomRight.y
    return true
}

/**
 * [viewLocalRectInto] 的**原点版**：底栏壳的可见轮廓每帧随收拢进度变，宽高只有绘制期才知道，
 * 布局期只能先要到原点，宽高到 `drawWithCache` 里再加。
 *
 * 约束与异常处理同 [viewLocalRectInto]：两枚坐标必须同属一个 LayoutNode 树（注册点不得落在
 * Popup/Dialog 独立窗口），未 attached / 跨树的失败一并收敛成 false。
 *
 * @return false = [card] 或 [preview] 未量到/未 attached/跨树（调用方回退窗口系）
 */
fun viewLocalOriginInto(out: FloatArray, card: LayoutCoordinates?, preview: LayoutCoordinates?): Boolean {
    if (out.size < 2) throw IllegalArgumentException("视图局部原点要 2 个 float，实际 ${out.size}")
    if (card == null || preview == null) return false
    if (!card.isAttached || !preview.isAttached) return false
    // 同 [viewLocalRectInto]：只收敛 RuntimeException 一族（跨树 IllegalArgumentException / 未 attached
    // NPE），失败只记一次日志、回退窗口系
    val topLeft = try {
        preview.localPositionOf(card, Offset.Zero)
    } catch (e: RuntimeException) {
        noteHierarchyFailure("viewLocalOriginInto.topLeft", e)
        return false
    }
    out[0] = topLeft.x
    out[1] = topLeft.y
    return true
}

/**
 * 窗口系回退（承载预览盒未登记：编辑控件页没有分屏，卡片矩形仍按 app 窗口坐标量）：
 * 四边写进 `out[0..3]`。
 */
fun windowRectInto(out: FloatArray, card: LayoutCoordinates?): Boolean {
    if (out.size < 4) throw IllegalArgumentException("窗口矩形要 4 个 float，实际 ${out.size}")
    if (card == null) return false
    val p = card.positionInWindow()
    val s = card.size
    out[0] = p.x
    out[1] = p.y
    out[2] = p.x + s.width
    out[3] = p.y + s.height
    return true
}

/** [windowRectInto] 的原点版（底栏壳回退路径用）。 */
fun windowOriginInto(out: FloatArray, card: LayoutCoordinates?): Boolean {
    if (out.size < 2) throw IllegalArgumentException("窗口原点要 2 个 float，实际 ${out.size}")
    if (card == null) return false
    val p = card.positionInWindow()
    out[0] = p.x
    out[1] = p.y
    return true
}

/**
 * 「祖先那层缩放的窗口读数」→「视图局部矩形」的**桥**（分屏恒等性测试用）。
 *
 * 模型：页面坐标 `p` 经祖先那层 `graphicsLayer{scale}` 映到窗口坐标 `S(p) = o + (p − o)·s`
 * （o = 变换原点，s = 缩放）。传入的 [windowX]/[windowY] 就是**卡片布局原点经 S 之后的窗口位置**
 * （= 旧口径 `positionInWindow()` 的读数，这正是缺陷的来源），[cardW]/[cardH] 是不含缩放的布局尺寸。
 * 本函数做的是把 S 反解回**未缩放的视图局部坐标**：
 * `local = o + (window − o)/s`。
 *
 * 断言"视图局部矩形经合成器后逐边落回卡片可视矩形"的两步由调用方完成：本桥负责第一步（反解），
 * 测试再把返回的四边过一遍 S（合成器那一步）。
 *
 * 诚实边界：这里假设承载视图铺满页面、其局部原点与页面原点重合——本桥只核**缩放账**；
 * 预览面在页面里的那点平移由 [viewLocalRectInto] 的 `localPositionOf` 天然抵消，不进这座桥。
 * [splitScale] 非正时按 1 处理（没有可逆的缩放，退化成恒等）。
 */
fun frostWindowToViewLocal(
    windowX: Float,
    windowY: Float,
    cardW: Float,
    cardH: Float,
    splitScale: Float,
    splitOriginX: Float,
    splitOriginY: Float
): FloatArray {
    val s = if (splitScale <= 0f) 1f else splitScale
    val left = splitOriginX + (windowX - splitOriginX) / s
    val top = splitOriginY + (windowY - splitOriginY) / s
    return floatArrayOf(left, top, left + cardW, top + cardH)
}
