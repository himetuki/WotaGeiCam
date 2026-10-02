package com.wotagei.cam.ui

/**
 * 取景 HUD 的**纯度量层**：常驻读数块的换行档、可用宽、高度预测（任务 #70 修复批次第 5 条从这里搬进来）。
 *
 * ## 为什么单独一个文件
 * 这三条函数原来住在 `ui/anim/LiquidMerge.kt` 里，而 `ui/HudLayout.kt`（纯 Kotlin 的位置表与落位算式）
 * 要反过来 `import com.wotagei.cam.ui.anim.*` 才能读它们——依赖方向倒了：有 `Path` / `Modifier` /
 * `@Composable` 的动画文件成了纯算式的上游，`HudLayout.kt` 的文件头因此被改窄成"没有 Android/Compose
 * **类型**依赖"才说得通。搬到这里之后 `ui` 包内部同包直读，一条 import 都不需要，那句 KDoc 恢复原话。
 *
 * ## 本文件是纯 Kotlin
 * 没有 Android/Compose 依赖，也没有 `Dp`（单位全是 `Float`/`Int` 的 dp 值，由调用方从令牌取），
 * 所以换行档与高度预测全部能在 JVM 单测里真跑（`LiquidMergeTest` 的那一组 `hud*` 用例、
 * `HudReadoutRowPlanTest`），不需要 Robolectric。
 */

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
 * 避让已经由调用方的 safeDrawingPadding() 算进 [roomWidthDp] 的入参里，这里不再扣第二笔，见 #68。）
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
 * 一颗胶囊的**高**（dp）＝ `WotaType.chip` 的 lineHeight 18sp（随字体缩放）+ 纵向内边距预算 12。
 * 默认档 30dp、120% 档 33.6dp（与 `HudLayoutPairCellTest` 里实测那颗 60px / 71dp 的胶囊同量级）。
 *
 * ⚠ 预算与实高差 2dp（2026-10-02 起 `WotaChip` 对齐 HDS 件高 28dp：上下内边距 5+5，18+10 = 28）；
 * 这里**有意沿用旧档 12**——预测/格距按 30dp 保守多留 2dp 只会多让位、不会裁字，
 * 而格距与测试期望值都锁在这条算式上，改数就是全网格重排，那不是本轮的事。
 *
 * **两条算式共读它**（任务 #75 提出来这一条）：
 * - [hudStripHeightDp] 的读数块高度预测（原来这个数是它的局部变量）；
 * - `HudLayout.kt` 的 [gridRowPitchPx]——网格的纵向格距按"胶囊档 + 一道行距"推，与格子里住了几颗、
 *   谁最高**无关**，那一档 74dp 的姿态仪顶高行距正是 #75 要修的回归。
 * 两处各写一份就是第二份真源（S3-5 那一族），改了内边距而预测与格距不同步，裁字与叠字都会复发。
 */
fun hudChipHeightDp(fontScale: Float): Float {
    val scale = if (fontScale < 1f) 1f else fontScale
    return ChipLineHeightDp * scale + ChipVerticalPaddingDp
}

/**
 * 读数块高度的**预测初值**（审查 S3-6）：首帧实测之前先按「行数 × 一颗胶囊高 + 行距 + 块内上下边距」估，
 * 免得右竖 Dock 的下边界第一帧按 0 算、最低那颗落在读数块的位置上叠一帧。
 *
 * 一颗读数胶囊走 [hudChipHeightDp]（18sp 行高随字体缩放 + 纵向内边距预算 12）⇒ 默认 3 读数一行时
 * 12 + 30 = 42dp，与实测同量级。「AE 已锁定」那行提示不计入预测（它一出现
 * 下一帧实测就跟上），这是预测不是结论。
 */
fun hudStripHeightDp(itemCount: Int, perRow: Int, fontScale: Float): Float {
    if (itemCount <= 0) return 0f
    val columns = if (perRow < 1) 1 else perRow
    val rows = (itemCount + columns - 1) / columns
    val chip = hudChipHeightDp(fontScale)
    return 2f * HudBlockPadDp + rows * chip + (rows - 1) * HudRowGapDp
}

private const val ChipMinTextDp = 39f      // WotaType.chip 13sp × 3 字下限
private const val ChipPaddingDp = 24f      // WotaChip 左右内边距 12+12
private const val ChipGapDp = 5f           // WotaChip 主副标签之间
private const val ChipSecondaryDp = 22f    // WotaType.label 11sp × 2 汉字（「快门」「码率」）
private const val ChipLineHeightDp = 18f   // WotaType.chip 的 lineHeight
// WotaChip 实际纵向内边距是 5+5（HDS 件高 28dp），这里按旧档 12 保守多留 2dp：预测/格距不会裁字，
// 代价只是多让 2dp；30dp 这条预算被格距算式与一批测试期望值锁着，别顺手改成 10（见 hudChipHeightDp 注）
private const val ChipVerticalPaddingDp = 12f
const val HudRowGapDp = 6f               // ParamsHud 行内/行间的间隔档：那两处 spacedBy 用的就是它（S3-5 同源）

/**
 * ParamsHud 自己的内边距：横向进 [hudRoomDp]、纵向进 [hudStripHeightDp]。
 * **公开**是为了让 ParamsHud 那层 `padding(HudBlockPadDp.dp)` 与这两条算式同一个真源（S3-5：
 * 宽度账只要分两处写，迟早一边改了另一边没改，就又是"贴边当装得下"）。
 * #70 修复批次第 6 条还有一处消费方：读数块与底栏"可见底边齐平"的那笔减法（[planReadoutRow] 把共用的
 * 基线换算成容器让位时用的就是它），所以这笔账没有第二个数可写。
 */
const val HudBlockPadDp = 6f

/**
 * 取档时的安全余量：字宽是算术估计（汉字按 1 em、拉丁按 0.6 em）不是量出来的。
 * 块内边距已由 [hudRoomDp] 从可用宽里扣掉，这里不再重复计一次，24dp 是纯估算误差余量——
 * §58/§73/§70 三次都是死在这半成的余量上（裁字与叠字）。
 */
private const val PerRowMarginDp = 24f
