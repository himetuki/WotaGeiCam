/**
 * SP 批（半模态尺寸纪律）：SheetSpec 纯算式全表（上游 SheetSpec.kt 直译）+ 接线守卫。
 *
 * 上游真机实测锚点：横屏 1600x720 density 2 → sheetMaxHeight ≈278dp（手写期望钉住——
 * 窗口/配置把挖孔列扣掉后 screenHeightDp 只报 ≈326，短边 90% 不绑定，`326 − 48` 先到；
 * 278 < 最小高 320 = 有意的退化）。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { SheetSpec } from '../../entry/src/main/ets/core/SheetSpec';

function srcRoot(): string {
  let dir: string = __dirname;
  for (let i = 0; i < 6; i++) {
    if (existsSync(resolve(dir, 'entry/src/main/ets/ui/HudSpec.ets'))) {
      return dir;
    }
    dir = resolve(dir, '..');
  }
  throw new Error('source root not found from ' + __dirname);
}

function codeOnly(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
}

function sourceText(rel: string): string {
  return codeOnly(readFileSync(resolve(srcRoot(), rel), 'utf8'));
}

// ------------------------------------------------------------------ 常量（上游逐字）

test('常量_上游逐字', () => {
  assertEq(480, SheetSpec.SHEET_MAX_WIDTH, '宽 480vp 上限');
  assertEq(320, SheetSpec.SHEET_MIN_HEIGHT, '最小高 320vp');
  assertEq(0.9, SheetSpec.SHEET_MAX_SHORT_RATIO, '最大高=短边 90%');
  assertEq(8, SheetSpec.SHEET_SIGNAL_GAP, '距信号栏 8vp');
  assertEq(12, SheetSpec.SHEET_INNER_PADDING, '弹窗内间距 12vp');
  assertEq(600, SheetSpec.SHEET_CENTER_BREAKPOINT, '>600vp 短边转居中');
  assertEq(48, SheetSpec.PANEL_TOP_KEEP, '顶栏兜底预留 48vp');
});

// ------------------------------------------------------------------ sheetMaxHeight（上游真机实测锚点）

test('最大高_横屏真机场景约278dp（挖孔列扣掉后326-48）', () => {
  // 上游真机实测：横屏 1600x720 density 2，screenHeightDp 只报 ≈326，
  // 短边 90%（293）不绑定，326 − 48 兜底先到 → ≈278。
  // 本函数吃 (shortSide, windowHeight, topInset)——横屏时短边=窗口高=326（同一 config 值）。
  const h: number = SheetSpec.sheetMaxHeight(326, 326, 0);
  assertTrue(Math.abs(h - 278) < 1, `期望 ≈278 实得 ${h}（上游真机实测锚点）`);
});

test('最大高_竖屏场景短边90%绑定', () => {
  // 竖屏 360x780：短边 360，90% = 324；窗口高 780 − 顶避让 56 = 724 不绑定 → 324
  const h: number = SheetSpec.sheetMaxHeight(360, 780, 56);
  assertEq(324, h, '竖屏短边 90% 绑定');
});

test('最大高_顶避让取max信号栏与兜底', () => {
  // 短边 400 → byRatio = 360；窗口高 500：
  // inset 0 → byWindow 452 > 360（ratio 绑定）；inset 100 → topKeep=108，byWindow=392 仍 > 360；
  // inset 300 → topKeep=308，byWindow=192 < 360（inset 接管）
  assertEq(360, SheetSpec.sheetMaxHeight(400, 500, 0), 'inset 0 → 兜底 48');
  assertEq(360, SheetSpec.sheetMaxHeight(400, 500, 100), 'inset 100 → 兜底 48');
  assertEq(192, SheetSpec.sheetMaxHeight(400, 500, 300), 'inset 300 → byWindow 192');
});

test('最大高_退化到0不下探负值', () => {
  // 窗口高小于顶避让：max(0, …) 兜底（上游 coerceAtLeast(0.dp) 同义）
  assertEq(0, SheetSpec.sheetMaxHeight(400, 30, 48), '窗口高 30 不足以支撑面板');
});

// ------------------------------------------------------------------ 断点判定（上游 2026-10-02 裁决）

test('断点_短边600以上居中_手机横竖屏底贴屏', () => {
  assertTrue(SheetSpec.centeredForShortSide(601), '平板/折叠屏展开态居中');
  assertTrue(SheetSpec.centeredForShortSide(792), '平板短边');
  assertFalse(SheetSpec.centeredForShortSide(600), '恰 600 不居中（> 严格大于）');
  assertFalse(SheetSpec.centeredForShortSide(360), '手机竖屏');
  assertFalse(SheetSpec.centeredForShortSide(326), '手机横屏（短边=高）');
});

// ------------------------------------------------------------------ 接线守卫

test('接线_纯模块零平台import_消费方登记', () => {
  const core: string = sourceText('entry/src/main/ets/core/SheetSpec.ets');
  assertTrue(core.indexOf('@kit') < 0 && core.indexOf('@ohos') < 0,
    'SheetSpec 必须零平台 import（inset 由调用方运行时读入）');
  // 本侧暂无半模态面板消费方（Gallery 分段清单是内嵌 Column）——纯逻辑先行，
  // 半模态面板批落地时消费；守卫只要求文件本体可测（上方全表已证）。
  assertTrue(core.indexOf('SHEET_MAX_WIDTH') >= 0 && core.indexOf('SHEET_CENTER_BREAKPOINT') >= 0,
    '纪律常量必须在 core 层单一真源');
});
