/**
 * HUD 读数块度量层（直译上游 `HudMetrics.kt` 的全部纯函数；用例对拍上游 `LiquidMergeTest`
 * 的 `hud*` 用例组 + `HudLayoutCodecTest` 的宽度账口径）。
 *
 * ## 这批用例存在的理由
 * 读数块的换行档与高度预测是「贴边当装得下」「裁字」「叠字」三次翻车（§58/§73/§70）的唯一算式。
 * 期望值全部**手写字面量**（按上游 KDoc 的宽度账手算），不引实现里的常量——
 * 谁把安全余量删掉、把块内边距改成 8、把胶囊预算 12 改成 10，这里必须红。
 *
 * 手算档（density 无关，全是 dp）：
 * · 一颗读数 100% = 39(3 字文本) + 24(左右内边距) + 5(主副间隔) + 22(副标签) = 90dp；120% = 108dp
 * · 一行 3 颗 100% = 3×90 + 2×6 = 282dp；加估算余量 24 = 306dp
 * · 可用宽 = 安全区实测宽 − 设计留白 8 − 块内边距 12：横屏 766 ⇒ 746、竖屏 360 ⇒ 340
 */
import {
  HUD_BLOCK_PAD_DP, HUD_ROW_GAP_DP, hudChipHeightDp, hudPerRowFor, hudRoomDp, hudStripHeightDp
} from '../../entry/src/main/ets/core/HudMetrics';

function near(expected: number, actual: number, eps: number, msg: string): void {
  assertTrue(Math.abs(expected - actual) <= eps, `${msg}: expected ${expected} but got ${actual}`);
}

// ------------------------------------------------------------------ 可用宽（S3-5：与调用点同一表达式）

test('可用宽扣掉设计留白与块内左右内边距', () => {
  // 766 − 8 − 6×2 = 746；360 − 8 − 12 = 340；240 − 8 − 12 = 220
  assertEq(746, hudRoomDp(766, 8));
  assertEq(340, hudRoomDp(360, 8));
  assertEq(220, hudRoomDp(240, 8));
  // 两条公开常量就是账里的两笔：块内边距 6、行距 6（都是上游 ParamsHud 的真源）
  assertEq(6, HUD_BLOCK_PAD_DP);
  assertEq(6, HUD_ROW_GAP_DP);
});

test('容器比安全区还窄时可用宽夹成 0_不许为负', () => {
  // 负可用宽会让 hudPerRowFor 走进"两颗也放不下"的分支，本身不炸，但语义是错的；
  // 这里钉住"宽度账永远非负"（突变：把 coerceAtLeast 删掉，本行给 −8）
  assertEq(0, hudRoomDp(12, 8));
  assertEq(0, hudRoomDp(0, 8));
});

// ------------------------------------------------------------------ 换行档 3 / 2 / 1 的分界

test('横屏_一行三颗加安全余量仍装得下', () => {
  const landscape: number = hudRoomDp(766, 8); // 746
  // 100% 三颗 282 + 24 = 306 ≤ 746；120% 三颗 336 + 24 = 360 ≤ 746
  assertEq(3, hudPerRowFor(3, 1, landscape));
  assertEq(3, hudPerRowFor(3, 1.2, landscape));
});

test('竖屏_115%起退到两颗_110%仍取三颗', () => {
  const portrait: number = hudRoomDp(360, 8); // 340
  // 100%：282 + 24 = 306 ≤ 340 → 3
  assertEq(3, hudPerRowFor(3, 1, portrait));
  // 110%：3×99 + 2×6 = 309，+24 = 333 ≤ 340 → 仍 3（旧写法多扣 34dp 只剩 314，这一档白退一级）
  assertEq(3, hudPerRowFor(3, 1.1, portrait));
  // 115%：3×103.5 + 12 = 322.5，+24 = 346.5 > 340 → 2
  assertEq(2, hudPerRowFor(3, 1.15, portrait));
  // 120%：3×108 + 12 = 336，+24 = 360 > 340 → 2
  assertEq(2, hudPerRowFor(3, 1.2, portrait));
});

test('极窄窗口退到一行一颗_宁可高也不裁字', () => {
  // 240dp 安全区 ⇒ 可用 220：两颗 120% 要 216 + 6 + 24 = 246 > 220 → 1
  assertEq(1, hudPerRowFor(3, 1.2, hudRoomDp(240, 8)));
  // 100% 两颗要 180 + 6 + 24 = 210 ≤ 220 → 2（边界两侧都点到）
  assertEq(2, hudPerRowFor(3, 1, hudRoomDp(240, 8)));
});

test('安全余量承重_直接喂窗口宽会多取一颗', () => {
  // 反面对照（S3-5 的成因留档）：346.5 ≤ 360，直接喂 screenWidthDp 就给 3；
  // 而块宽 334.5 + 留白 8 离 360 只剩 17.5dp，24dp 估算余量被吃光。
  // 突变自证：把 PER_ROW_MARGIN_DP 改成 0，上面的 115% 用例会变 3 而红在这里的对照组没变——
  // 两条一起才说明"余量是分档的唯一承重件"，不是顺手加的一个数。
  assertEq(3, hudPerRowFor(3, 1.15, 360));
});

test('换行档不超过条目数_空表给1', () => {
  const landscape: number = hudRoomDp(766, 8);
  assertEq(1, hudPerRowFor(1, 1, landscape));
  assertEq(2, hudPerRowFor(2, 1, landscape));
  // 一颗都没开时返回 1（chunked(1) 对空表不产生行，perRow 只需保证 ≥1 不炸）
  assertEq(1, hudPerRowFor(0, 1, landscape));
});

// ------------------------------------------------------------------ 胶囊高与高度预测

test('字体低于100%被夹回_胶囊高恒30dp', () => {
  assertEq(30, hudChipHeightDp(1));
  // 突变：把 18+12 改成 18+10（对齐 HDS 实高）⇒ 28，全网格重排，这条当场红（有意沿用旧档）
  assertEq(30, hudChipHeightDp(0.8));
  near(33.6, hudChipHeightDp(1.2), 1e-9, '120% 档胶囊高');
});

test('读数块高度预测取默认与满配两档', () => {
  // 3 颗一行三颗 = 1 行 → 12 + 30 = 42dp
  assertEq(42, hudStripHeightDp(3, 3, 1));
  // 满配 7 颗、一行三颗 = 3 行 → 12 + 3×30 + 2×6 = 114dp
  assertEq(114, hudStripHeightDp(7, 3, 1));
  // 满配 7 颗、竖屏 120% 一行两颗 = 4 行 → 12 + 4×33.6 + 3×6 = 164.4dp
  near(164.4, hudStripHeightDp(7, 2, 1.2), 1e-6, '120% 满配两列');
  // 读数全关时块高归 0：右 Dock 下边界那条缝要收回去
  assertEq(0, hudStripHeightDp(0, 3, 1));
});

test('高度预测_perRow非正按一列算', () => {
  // perRow=0 是"还没算出来"的退化输入，必须当 1 列：12 + 3×30 + 2×6 = 114
  assertEq(114, hudStripHeightDp(3, 0, 1));
  // 反面对照：按 3 列则是 42——两者必须不同（否则 perRow 夹子根本没生效）
  assertTrue(hudStripHeightDp(3, 0, 1) !== hudStripHeightDp(3, 3, 1));
});

test('高度预测随字体档与档位同步变化', () => {
  // 7 颗 100% 一行两颗 = 4 行 → 12 + 4×30 + 3×6 = 150；120% 同一档 164.4
  // 突变：把行距 6 从算式里删掉，120% 给 146.4，与 164.4 差 18dp，本条红
  assertEq(150, hudStripHeightDp(7, 2, 1));
  near(164.4, hudStripHeightDp(7, 2, 1.2), 1e-6, '同一档 120%');
  // 预测永远小于"每颗独占一行"的最坏档（4 行与 7 行不是一个量级，用来防把 rows 算成 itemCount）
  assertTrue(hudStripHeightDp(7, 2, 1) < hudStripHeightDp(7, 1, 1));
});
