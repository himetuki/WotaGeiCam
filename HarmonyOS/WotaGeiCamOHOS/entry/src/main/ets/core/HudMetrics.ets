/**
 * 取景 HUD 的**纯度量层**：常驻读数块的换行档、可用宽、高度预测。
 *
 * 直译自上游 `ui/HudMetrics.kt`（#70 修复批次第 5 条从 `ui/anim/LiquidMerge.kt` 搬出来的那三条函数）。
 * 上游把这三条从动画文件搬出来，是因为 `ui/HudLayout.kt`（纯位置表算式）要反过来 import 一个带
 * `Path`/`Modifier`/`@Composable` 的动画文件——依赖方向倒了。本侧同样独立成文件：`core/HudLayout.ets`
 * 只需要 `hudChipHeightDp` 这一把尺子，不该把 UI 层拉进纯逻辑闭包。
 *
 * ## 本文件是纯逻辑
 * 零 `@ohos.`/`@kit.` import，也没有 dp 类型（单位全是 number 的 dp 值，由调用方从令牌取），
 * 所以换行档与高度预测全部能在 `test/pure` 里真跑（`hud-metrics.test.ets` 直译上游
 * `LiquidMergeTest` 的 `hud*` 用例组）。
 *
 * ## 与本侧 HUD 令牌的关系（登记）
 * `ui/HudTokens.ets` / `ui/HudSpec.ets` 是**本侧自有令牌**（上游 HDS 令牌的静态子集，T31 真机取证），
 * 与上游 `HudMetrics` 不是同一份真源：本侧令牌描述的是固定两行 HUD 的字阶/间距，
 * 这里描述的是**可编排读数块**的宽度账与格距预算（上游 `WotaType.chip`/`WotaChip` 的数字）。
 * 两者混用会让「读数块怎么换行」跟着固定 HUD 的间距令牌漂移，所以 dp 常量独立成表、不引 HudTokens。
 */

/**
 * 常驻读数的换行档。
 *
 * 一颗读数的宽度 = 左右内边距 12+12 + 文本下限 3 字 + 与副标签间隔 5 + 副标签，
 * 全部来自上游 `WotaChip` 与 `WotaType`，这里不散写观感值：100% 90dp、120% 108dp。
 * 整块宽度（含块内左右内边距 6+6，见 [hudRoomDp]）：
 * · 一行 3 颗：100% 294dp、120% 348dp（「感光度 AUTO / 白平衡 5500K / 变焦 1.0x」这一组就这个量级）
 * · 一行 2 颗：100% 198dp、120% 234dp
 *
 * 入参 roomWidthDp 必须是**调用点同一个表达式**算出的可用宽（[hudRoomDp]：安全区实测宽 − 设计留白 8
 * − 块内左右内边距 12），不许直接塞 screenWidthDp（审查 S3-5：B2 那一版用例传 352、调用点传 360/800，
 * 两边判的不是同一条不等式）。竖屏 360dp 上可用 = 340：100% 三颗要 282 + 24 = 306 → 取 3，
 * 110% 要 309 + 24 = 333 → 仍取 3（旧写法替挖孔多扣 34dp 只剩 314，这一档会白退一级），
 * 115% 要 322.5 + 24 = 346.5 → 退到 2。
 *
 * 字宽是算术估计（汉字按 1 em、拉丁按 0.6 em）不是量出来的，所以取档必须带安全余量
 * [PER_ROW_MARGIN_DP]；§58/§73/§70 三次翻车都死在这半成的余量上。取不到的档位退到下一档，
 * 这是"小屏优先"的算术。
 */
export function hudPerRowFor(chipCount: number, fontScale: number, roomWidthDp: number): number {
  if (chipCount <= 0) {
    return 1;
  }
  const scale: number = fontScale < 1 ? 1 : fontScale;
  const chip: number = (CHIP_MIN_TEXT_DP + CHIP_PADDING_DP + CHIP_GAP_DP + CHIP_SECONDARY_DP) * scale;
  const three: number = 3 * chip + 2 * HUD_ROW_GAP_DP;
  const two: number = 2 * chip + HUD_ROW_GAP_DP;
  if (three + PER_ROW_MARGIN_DP <= roomWidthDp) {
    return chipCount >= 3 ? 3 : chipCount;
  }
  return two + PER_ROW_MARGIN_DP <= roomWidthDp ? 2 : 1;
}

/**
 * 读数块的可用横向宽度 = **安全区实测宽** − 那枚设计留白 − 块自身左右内边距（[HUD_BLOCK_PAD_DP] 各一份）。
 *
 * 抽成函数只为了一个目的：调用点与用例喂 [hudPerRowFor] 的是**同一个表达式**（S3-5）。
 *
 * - safeWidthDp：调用方那层 `safeDrawingPadding()` 盒子的**实测宽**（本机横屏 766dp 而不是 800dp：
 *   挖孔让掉的那条边已经算在里面，所以这里不再按方向扣第二笔）。
 * - endPadDp：读数块原生对齐 `padding(end=)` 用的那枚**设计留白**（上游 `HudEdgePad` 8dp 令牌）。
 *   它不是避让量：不分方向、不分姿态；改了它这条宽度账跟着改（同源）。
 */
export function hudRoomDp(safeWidthDp: number, endPadDp: number): number {
  const room: number = safeWidthDp - endPadDp - 2 * HUD_BLOCK_PAD_DP;
  return room > 0 ? room : 0;
}

/**
 * 一颗胶囊的**高**（dp）＝ `WotaType.chip` 的 lineHeight 18sp（随字体缩放）+ 纵向内边距预算 12。
 * 默认档 30dp、120% 档 33.6dp。
 *
 * ⚠ 预算与实高差 2dp（2026-10-02 起上游 `WotaChip` 对齐 HDS 件高 28dp：上下内边距 5+5，18+10 = 28）；
 * 这里**有意沿用旧档 12**——预测/格距按 30dp 保守多留 2dp 只会多让位、不会裁字，
 * 而上游格距与测试期望值都锁在这条算式上，改数就是全网格重排。
 *
 * **两条算式共读它**：
 * - [hudStripHeightDp] 的读数块高度预测；
 * - J2 的 `gridRowPitchPx`（网格纵向格距按"胶囊档 + 一道行距"推，与格子里住了几颗、谁最高**无关**）。
 * 两处各写一份就是第二份真源（S3-5 那一族），改了内边距而预测与格距不同步，裁字与叠字都会复发。
 */
export function hudChipHeightDp(fontScale: number): number {
  const scale: number = fontScale < 1 ? 1 : fontScale;
  return CHIP_LINE_HEIGHT_DP * scale + CHIP_VERTICAL_PADDING_DP;
}

/**
 * 读数块高度的**预测初值**（审查 S3-6）：首帧实测之前先按「行数 × 一颗胶囊高 + 行距 + 块内上下边距」估，
 * 免得右竖 Dock 的下边界第一帧按 0 算、最低那颗落在读数块的位置上叠一帧。
 *
 * 一颗读数胶囊走 [hudChipHeightDp]（18sp 行高随字体缩放 + 纵向内边距预算 12）⇒ 默认 3 读数一行时
 * 12 + 30 = 42dp。「AE 已锁定」那行提示不计入预测（它一出现下一帧实测就跟上），这是预测不是结论。
 */
export function hudStripHeightDp(itemCount: number, perRow: number, fontScale: number): number {
  if (itemCount <= 0) {
    return 0;
  }
  const columns: number = perRow < 1 ? 1 : perRow;
  const rows: number = Math.ceil(itemCount / columns);
  const chip: number = hudChipHeightDp(fontScale);
  return 2 * HUD_BLOCK_PAD_DP + rows * chip + (rows - 1) * HUD_ROW_GAP_DP;
}

// 以下是上游 `HudMetrics.kt` 文件底那组私有常量，取值逐字照搬（宽度账的每一笔都有真源）：
const CHIP_MIN_TEXT_DP: number = 39;      // WotaType.chip 13sp × 3 字下限
const CHIP_PADDING_DP: number = 24;       // WotaChip 左右内边距 12+12
const CHIP_GAP_DP: number = 5;            // WotaChip 主副标签之间
const CHIP_SECONDARY_DP: number = 22;     // WotaType.label 11sp × 2 汉字（「快门」「码率」）
const CHIP_LINE_HEIGHT_DP: number = 18;   // WotaType.chip 的 lineHeight
// WotaChip 实际纵向内边距是 5+5（HDS 件高 28dp），这里按旧档 12 保守多留 2dp：预测/格距不会裁字，
// 代价只是多让 2dp；30dp 这条预算被格距算式与一批测试期望值锁着，别顺手改成 10（见 hudChipHeightDp 注）
const CHIP_VERTICAL_PADDING_DP: number = 12;

/** 行内/行间的间隔档：上游 ParamsHud 两处 spacedBy 用的就是它（S3-5 同源） */
export const HUD_ROW_GAP_DP: number = 6;

/**
 * 读数块自己的内边距：横向进 [hudRoomDp]、纵向进 [hudStripHeightDp]。
 * **公开**是为了让渲染层 `padding(HUD_BLOCK_PAD_DP)` 与这两条算式同一个真源（宽度账分两处写，
 * 迟早一边改了另一边没改，就又是"贴边当装得下"）。
 */
export const HUD_BLOCK_PAD_DP: number = 6;

/**
 * 取档时的安全余量：字宽是算术估计（汉字按 1 em、拉丁按 0.6 em）不是量出来的。
 * 块内边距已由 [hudRoomDp] 从可用宽里扣掉，这里不再重复计一次，24dp 是纯估算误差余量——
 * §58/§73/§70 三次都是死在这半成的余量上（裁字与叠字）。
 */
const PER_ROW_MARGIN_DP: number = 24;
