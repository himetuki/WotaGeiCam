/**
 * 读数块与底栏 Dock 同一条横带的决策（J2 批，直译上游 `HudReadoutRowPlanTest.kt` 的
 * `planReadoutRow` 部分）。
 *
 * 上游同一文件里另有两条 `chipTierFor` / `WotaChipTier` 用例（#70 B 的胶囊档裁决），那两件属于
 * **UI 令牌层**（`ui/HudTokens.ets` / `ui/HudSpec.ets`），不在 `core/HudLayout.ets` 的移植面里，
 * 本批不搬（登记在 PORT_MAP 的 J3 消费清单）。
 *
 * ## 定版口径（用户 2026-09-29 12:20：「读数缩到一行两颗，横屏永远同行」）
 * 横屏 `sharesDockRow` 恒真、列数上限 2；横屏**不存在**"退回 Dock 上方"这一档；只有竖屏允许退回。
 * 横向装不下的解法是**少列**（两颗→一颗），不是放弃同行。
 *
 * ## 期望值先按文档口径写死，入参引令牌本体
 * 底盘基线 6dp、设计留白 8dp、间距令牌 8dp、底板常态宽 216dp、底栏带高 72dp；横屏安全区 766、
 * 竖屏 360。令牌一改这些字面量就会红，逼人来对账。
 */
import {
  DOCK_ROW_PER_ROW_CAP,
  HudAreaDp,
  ReadoutRowPlan,
  areaForRightDock,
  planReadoutRow,
  readoutRoomBesideDockDp,
  readoutStripWidthDp
} from '../../entry/src/main/ets/core/HudLayout';
import { HUD_BLOCK_PAD_DP } from '../../entry/src/main/ets/core/HudMetrics';

const DOCK_ROW_BASELINE: number = 6;
const EDGE_PAD: number = 8;
const DOCK_GAP: number = 8;
const DOCK_W: number = 216;
const DOCK_STRIP: number = 72;
const LAND_SAFE_W: number = 766;
const PORT_SAFE_W: number = 360;

function near(expected: number, actual: number, eps: number, msg: string): void {
  assertTrue(Math.abs(expected - actual) <= eps, `${msg}: expected ${expected} but got ${actual}`);
}

function plan(
  count: number, scale: number, safeW: number, dockW: number, landscape: boolean,
  dockStrip: number = DOCK_STRIP, readoutW: number = 0
): ReadoutRowPlan {
  return planReadoutRow(
    count, scale, safeW, dockW, dockStrip, DOCK_ROW_BASELINE, EDGE_PAD, DOCK_GAP, landscape, readoutW
  );
}

// ---------------------------------------------------------------- 横屏（定版：永远同行）

test('横屏永远同行_块宽再大也不改列数', () => {
  const p: ReadoutRowPlan = plan(3, 1, LAND_SAFE_W, DOCK_W, true, DOCK_STRIP, 294);
  assertTrue(p.sharesDockRow, '横屏必须同行（定版）');
  assertEq(2, p.perRow);
  // 可用内宽 247 = 766/2 − 216/2 − 8 − 8 − 6×2
  near(247, p.roomWidthDp, 0.001, '可用内宽');
  // 反证：块宽喂到 400 也不许改列数 / 撤同行
  const huge: ReadoutRowPlan = plan(3, 1, LAND_SAFE_W, DOCK_W, true, DOCK_STRIP, 400);
  assertTrue(huge.sharesDockRow);
  assertEq(2, huge.perRow);
});

test('横屏列数上限是定版的2颗_不是按剩余宽度算到3', () => {
  const p: ReadoutRowPlan = plan(7, 1, 1000, DOCK_W, true);
  assertTrue(p.sharesDockRow);
  assertEq(2, p.perRow);
  assertEq(2, DOCK_ROW_PER_ROW_CAP, '上限常量本身也要钉住');
  assertEq(2, plan(7, 1, LAND_SAFE_W, DOCK_W, true).perRow);
  // 120% + 底板 232：退成**一列**，但仍同行
  const big: ReadoutRowPlan = plan(3, 1.2, LAND_SAFE_W, 232, true);
  assertTrue(big.sharesDockRow, '120% 档横屏也不许退回 Dock 上方');
  assertEq(1, big.perRow);
  near(239, big.roomWidthDp, 0.001, '右半带 239dp');
});

test('同行档的容器让位是基线减块内边距_胶囊可见底边与底板齐平', () => {
  const p: ReadoutRowPlan = plan(3, 1, LAND_SAFE_W, DOCK_W, true);
  assertEq(DOCK_ROW_BASELINE, p.bottomAvoidDp + HUD_BLOCK_PAD_DP, '底边必须落在同一条线上');
  assertEq(0, p.bottomAvoidDp, '本机 6 − 6 = 0dp');
});

test('同一条带只把底栏右缘以右给读数块_极窄夹0', () => {
  near(247, readoutRoomBesideDockDp(LAND_SAFE_W, DOCK_W, EDGE_PAD, DOCK_GAP), 0.001, '底板 216');
  near(139, readoutRoomBesideDockDp(LAND_SAFE_W, DOCK_W * 2, EDGE_PAD, DOCK_GAP), 0.001, '底板变宽则等量变窄');
  near(0, readoutRoomBesideDockDp(LAND_SAFE_W, 900, EDGE_PAD, DOCK_GAP), 0.001, '吃掉右半带时夹到 0');
});

// ---------------------------------------------------------------- 竖屏（唯一允许退回的姿态）

test('竖屏退回整排之上_列数回到整幅宽的旧档', () => {
  const p: ReadoutRowPlan = plan(3, 1, PORT_SAFE_W, DOCK_W, false);
  assertFalse(p.sharesDockRow, '竖屏右半带装不下两颗，必须退化');
  assertEq(DOCK_STRIP, p.bottomAvoidDp);
  near(340, p.roomWidthDp, 0.001, '整幅可用宽');
  assertEq(3, p.perRow);
});

test('陈旧块宽下一帧自动恢复_退化档夹成min(旁边档,整幅档)', () => {
  const stale: ReadoutRowPlan = plan(3, 1, 700, DOCK_W, false, DOCK_STRIP, 294);
  assertFalse(stale.sharesDockRow, '估宽说能排两颗、实测越线 ⇒ 先退');
  assertEq(2, stale.perRow, '退化档必须夹到两颗');
  const recovered: ReadoutRowPlan = plan(3, 1, 700, DOCK_W, false, DOCK_STRIP, 198);
  assertTrue(recovered.sharesDockRow, '窄下来之后自动回到同一行');
  assertEq(2, recovered.perRow);
  assertEq(0, recovered.bottomAvoidDp);
});

test('实测块宽越过那条带就退_量不到0与临界相等都放行', () => {
  near(226, readoutStripWidthDp(700, DOCK_W, EDGE_PAD, DOCK_GAP), 0.001, '700/2 − 216/2 − 8 − 8');
  near(56, readoutStripWidthDp(PORT_SAFE_W, DOCK_W, EDGE_PAD, DOCK_GAP), 0.001, '360/2 − 108 − 16');
  const lied: ReadoutRowPlan = plan(3, 1, 700, DOCK_W, false, DOCK_STRIP, 250);
  assertFalse(lied.sharesDockRow, '实测越线还不退就会压住底栏底板');
  assertEq(DOCK_STRIP, lied.bottomAvoidDp);
  const honest: ReadoutRowPlan = plan(3, 1, 700, DOCK_W, false, DOCK_STRIP, 220);
  assertTrue(honest.sharesDockRow);
  assertEq(2, honest.perRow);
  assertTrue(plan(3, 1, 700, DOCK_W, false, DOCK_STRIP, 226).sharesDockRow, '临界相等仍算装得下');
  assertTrue(plan(3, 1, 700, DOCK_W, false, DOCK_STRIP, 0).sharesDockRow, '首帧量不到必须放行');
});

test('读数颗数不许把行推到Dock上_单颗也留在同一行', () => {
  const p: ReadoutRowPlan = plan(7, 1, LAND_SAFE_W, DOCK_W, true);
  assertTrue(p.sharesDockRow);
  assertEq(2, p.perRow);
  const one: ReadoutRowPlan = plan(1, 1, LAND_SAFE_W, DOCK_W, true);
  assertTrue(one.sharesDockRow, '单颗也必须留在同一行');
  assertEq(0, one.bottomAvoidDp);
  assertEq(2, one.perRow);
});

test('右Dock让位_同行取大_退化档串联', () => {
  const land: HudAreaDp = new HudAreaDp(LAND_SAFE_W, 360, 44, DOCK_STRIP);
  assertEq(DOCK_STRIP, areaForRightDock(land, 42, 0).bottomAvoidDp, '读数块矮于底栏那排 ⇒ 缝收回去');
  assertEq(100, areaForRightDock(land, 100, 0).bottomAvoidDp, '读数块更高 ⇒ 按它自己的顶');
  assertEq(114, areaForRightDock(land, 42, DOCK_STRIP).bottomAvoidDp, '退化档与 #70 之前逐字相同');
  assertEq(land.bottomAvoidDp, areaForRightDock(land, 0, 0).bottomAvoidDp);
  assertEq(DOCK_STRIP, areaForRightDock(land, -50, 0).bottomAvoidDp);
});
