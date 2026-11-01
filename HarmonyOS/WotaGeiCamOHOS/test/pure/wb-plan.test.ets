/**
 * 手动白平衡走线决策 + 色温/色调→增益模型——对拍上游 WbFallbackPlanTest.kt +
 * RequestApplierTest 白平衡模型节（c0ea34a 订正版，f4aeb36 决策层）。
 *
 * 钉四件事：①中性锚点精确在 5500K（不是域中点 6000——上游"整体偏冷"的模型侧根因）；
 * ②绿通道满额（c0ea34a 订正"砍半 ⇒ 偏品红"）；③归一化（最大通道=1.0，只改色不改亮度）；
 * ④走线三分支（不处理 / HAL / GL 兜底+必须提示）。
 * 本侧平台裁定（WbPlan.ets 头注释）：GL 兜底的 native 面待平台批，本批交付纯层+全表用例。
 */
import {
  WbApply, gain255ToUnit, isIdentityGains, kelvinTintToRgb255, manualGains,
  normalizeMaxToUnit, wbApplyPlan
} from '../../entry/src/main/ets/core/WbPlan';
import { WotaTiers } from '../../entry/src/main/ets/core/Constants';

const TINT_RGB: number = 38;
const TINT_G: number = 26;

function close(a: number, b: number, eps: number = 1e-3): boolean {
  return Math.abs(a - b) < eps;
}

test('中性锚点精确在 5500K：三通道增益全等（恒等）', () => {
  const rgb: Array<number> = kelvinTintToRgb255(5500, 0);
  assertEq(0.549 * 255, rgb[0], 'R=中锚基准');
  assertEq(rgb[0], rgb[1], 'G 同');
  assertEq(rgb[0], rgb[2], 'B 同');
  const gains: Array<number> = manualGains(5500, 0);
  assertTrue(close(gains[0], gains[1]) && close(gains[1], gains[2]),
    '5500K 三通道增益全等（选了日光参考就不该有偏色）');
  // 恒等在归一化后成立（manualGains 是 1..3 域，三通道同值 ⇒ 归一化即全 1）
  const norm: Array<number> | undefined = normalizeMaxToUnit(gains);
  assertTrue(norm !== undefined && isIdentityGains(norm), '归一化后 = 恒等');
  const plan: WbApply = wbApplyPlan(true, false, 5500, 0);
  assertTrue(plan.gains !== undefined && isIdentityGains(plan.gains), '兜底走线同样恒等');
});

test('域中点 6000K 不再是中性：5500 订正后 6000 偏冷（B>R）', () => {
  const gains: Array<number> = manualGains(6000, 0);
  // 6000K 比中性更冷（光更蓝）⇒ 补偿方向 = 红增益高于蓝增益；旧模型把零偏色放 6000，
  // 钉死"6000 ≠ 三通道等值"防回退
  assertTrue(gains[0] > gains[2], '6000K 偏冷 ⇒ R 增益 > B 增益');
  assertFalse(close(gains[0], gains[2]), '6000 ≠ 中性');
});

test('色温轴两端：2000K 偏暖（R 主导）、10000K 偏冷（B 主导）', () => {
  const warm: Array<number> = manualGains(WotaTiers.KELVIN_MIN, 0);
  assertTrue(warm[0] < warm[1] && warm[1] < warm[2], '低色温 ⇒ 蓝>绿>红（暖）');
  const cold: Array<number> = manualGains(WotaTiers.KELVIN_MAX, 0);
  assertTrue(cold[2] < cold[1] && cold[1] < cold[0], '高色温 ⇒ 红>绿>蓝（冷）');
});

test('tint 副轴：+50 偏品红（R/B 抬、G 压）、-50 偏绿', () => {
  const magenta: Array<number> = kelvinTintToRgb255(5500, 50);
  assertTrue(magenta[0] > kelvinTintToRgb255(5500, 0)[0], 'R 抬');
  assertTrue(magenta[1] < kelvinTintToRgb255(5500, 0)[1], 'G 压');
  const green: Array<number> = kelvinTintToRgb255(5500, -50);
  assertTrue(green[1] > green[0] && green[1] > green[2], '负 tint 偏绿');
  // 满档张度：t=±1 时 R/B ±38、G ∓26（255 域）
  const base: number = 0.549 * 255;
  assertEq(base + TINT_RGB, magenta[0], 'R 满档 +38');
  assertEq(base - TINT_G, magenta[1], 'G 满档 -26');
});

test('gain255ToUnit：0→1.0、255→3.0 线性', () => {
  assertEq(1, gain255ToUnit(0));
  assertEq(3, gain255ToUnit(255));
  assertEq(2, gain255ToUnit(127.5));
});

test('归一化：最大通道=1.0（只改色不改亮度）', () => {
  const n: Array<number> | undefined = normalizeMaxToUnit([1.596, 2.004, 3.0]);
  assertTrue(n !== undefined);
  if (n !== undefined) {
    assertEq(1, n[2], '最大通道归一');
    assertTrue(close(n[0], 1.596 / 3.0));
    assertTrue(close(n[1], 2.004 / 3.0));
  }
});

test('归一化非法输入：undefined（调用方据此不做画面处理）', () => {
  assertTrue(normalizeMaxToUnit(undefined) === undefined);
  assertTrue(normalizeMaxToUnit([1, 2]) === undefined, '不足三通道');
  assertTrue(normalizeMaxToUnit([0, 0, 0]) === undefined, '全 0');
  assertTrue(normalizeMaxToUnit([-1, 2, 3]) === undefined, '负值');
  assertTrue(normalizeMaxToUnit([NaN, 2, 3]) === undefined, '非有限');
});

test('isIdentityGains：undefined 视恒等（别白跑一趟 LUT）、1e-4 容差', () => {
  assertTrue(isIdentityGains(undefined));
  assertTrue(isIdentityGains([1, 1, 1]));
  assertTrue(isIdentityGains([1 + 5e-5, 1, 1]), '容差内');
  assertFalse(isIdentityGains([1 + 1e-3, 1, 1]));
});

test('走线：非手动档 = 不处理', () => {
  const plan: WbApply = wbApplyPlan(false, false, 5500, 0);
  assertFalse(plan.byHal);
  assertFalse(plan.byGl);
  assertTrue(plan.gains === undefined);
  assertFalse(plan.noticeNeeded);
});

test('走线：HAL 声明支持 = 常规路径，无提示', () => {
  const plan: WbApply = wbApplyPlan(true, true, 3200, 0);
  assertTrue(plan.byHal);
  assertFalse(plan.byGl);
  assertTrue(plan.gains === undefined);
  assertFalse(plan.noticeNeeded);
});

test('走线：HAL 不支持 = GL 兜底 + 必须运行时提示（5500K 恒等增益）', () => {
  const plan: WbApply = wbApplyPlan(true, false, 5500, 0);
  assertFalse(plan.byHal, 'HAL 不采纳就不再发');
  assertTrue(plan.byGl, '画面层接管');
  assertTrue(plan.noticeNeeded, '「此设备未声明手动白平衡增益支持」必须提示');
  const gains: Array<number> | undefined = plan.gains;
  assertTrue(gains !== undefined && isIdentityGains(gains), '5500K 兜底 = 恒等（与自动白平衡一致）');
});

test('走线：兜底增益归一化且方向正确（3200K 偏暖）', () => {
  const plan: WbApply = wbApplyPlan(true, false, 3200, 0);
  const gains: Array<number> | undefined = plan.gains;
  assertTrue(gains !== undefined);
  if (gains !== undefined) {
    assertEq(1, Math.max(gains[0], gains[1], gains[2]), '最大通道恒 1.0');
    assertTrue(gains[0] < gains[2], '3200K 相对增益偏暖（R<B）');
    assertFalse(isIdentityGains(gains));
  }
});
