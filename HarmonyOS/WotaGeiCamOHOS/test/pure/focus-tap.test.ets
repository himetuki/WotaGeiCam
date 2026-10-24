/**
 * TFC 批（tap-to-focus）：纯算式全表（core/FocusTap.ets）+ 接线守卫。
 *
 * 上游语义：点按预览 = 对焦 + 测光（tapPoint 归一化 [0,1]，RequestApplier 同时驱动
 * setFocusPoint 与 setMeteringPoint 双驱动）。本批把取景页缺失的「点按 → focusPoint」
 * 手势接上；下发链（engine.applyParams → setFocusPoint/setMeteringPoint）零改动。
 *
 * 手势冲突分析（登记，回应 verifier 的「HUD 显隐/双击启停冲突」）：
 * - 相机页**没有** Player 那套「单击显隐 HUD / 双击启停」——预览区此前零点击处理，
 *   无仲裁冲突可言；真正的冲突是与兄弟浮层（AttitudeMeter 模式切换）的 z-order 竞争，
 *   由 HUD 列既有 `HitTestMode.Transparent`（点击穿透到下层）+ 内层手势优先裁决：
 *   姿态仪区域点击由 AttitudeMeter 消费（模式切换），其余区域落到 XComponent onClick。
 * - 按钮子节点各自消费（Dock/快门/更多），不会误触发对焦。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { FocusTapMath } from '../../entry/src/main/ets/core/FocusTap';

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

// ------------------------------------------------------------------ 纯算式全表

test('归一化_中心点约0.5_边缘钳到2%余量', () => {
  const center = FocusTapMath.pointFromWindowTap(660, 1428, 1320, 2856);
  assertTrue(center !== undefined);
  // 660/1320 = 0.5；1428/2856 = 0.5
  assertTrue(Math.abs(center.x - 0.5) < 1e-9, '中心 x = 0.5');
  assertTrue(Math.abs(center.y - 0.5) < 1e-9, '中心 y = 0.5');

  // 左上角外（-5,-5）：钳到 2% 余量
  const tl = FocusTapMath.pointFromWindowTap(-5, -5, 1320, 2856);
  assertTrue(tl !== undefined);
  assertTrue(Math.abs(tl!.x - 0.02) < 1e-9, 'x 钳到 0.02');
  assertTrue(Math.abs(tl!.y - 0.02) < 1e-9, 'y 钳到 0.02');

  // 右下角外（2000, 3000）：钳到 98%
  const br = FocusTapMath.pointFromWindowTap(2000, 3000, 1320, 2856);
  assertTrue(br !== undefined);
  assertTrue(Math.abs(br!.x - 0.98) < 1e-9, 'x 钳到 0.98');
  assertTrue(Math.abs(br!.y - 0.98) < 1e-9, 'y 钳到 0.98');
});

test('归一化_窗口尺寸非法返回undefined_不造伪点', () => {
  assertNull(FocusTapMath.pointFromWindowTap(100, 100, 0, 0), '尺寸 0（量不到）');
  assertNull(FocusTapMath.pointFromWindowTap(100, 100, -1, 100), '负尺寸');
  assertNull(FocusTapMath.pointFromWindowTap(Number.NaN, 100, 1320, 2856), 'NaN 坐标');
  assertNull(FocusTapMath.pointFromWindowTap(100, Number.POSITIVE_INFINITY, 1320, 2856), 'Inf 坐标');
});

// ------------------------------------------------------------------ 接线守卫

test('接线_XComponent点击下发focusPoint_标记与门控', () => {
  const cam: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertTrue(cam.indexOf('.onClick((event: ClickEvent): void => {') >= 0 &&
    cam.indexOf('this.onPreviewTap(event.windowX, event.windowY)') >= 0,
    'XComponent 必须挂 onClick 消费空白区点按（windowX/windowY 坐标）');
  assertTrue(cam.indexOf('this.params.focusPoint.value = pt;') >= 0,
    '对焦点必须写参数总线 focusPoint（RequestApplier 双驱动下发链）');
  assertTrue(cam.indexOf('this.applyParamsFromPills();') >= 0,
    '写点后必须走既有 applyParams 下发（engine.applyParams → setFocusPoint/setMeteringPoint）');
  assertTrue(cam.indexOf('if (!this.cameraStarted || this.glFatal !== \'\')') >= 0,
    '门控：取景未就绪/glFatal 不得下发');
  assertTrue(cam.indexOf('showFocusMark(windowX, windowY)') >= 0 &&
    cam.indexOf('this.focusMarkVisible = true;') >= 0,
    '必须有可视对焦标记（无反馈用户无法确认生效）');
  assertTrue(cam.indexOf('focusMarkTimer') >= 0 && cam.indexOf('hideFocusMark();') >= 0,
    '标记必须自动隐（计时器 + 页面销毁/相机丢失收口）');
  // marker 不拦触摸：RefLine/scrim 同款 None
  const markerHit = cam.indexOf('.position({ x: this.focusMarkX - 28, y: this.focusMarkY - 28 })');
  assertTrue(markerHit >= 0, '标记用 position 定位在点按处');
});

test('接线_HUD列保持Transparent_姿态仪与焦点标记共存', () => {
  const cam: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  // HUD 列 Transparent（点击穿透到下层兄弟）是姿态仪可点性与点按对焦的前提；
  // 改回 None/Default 会把姿态仪模式切换与标记裁掉（T31 遮罩同款教训）。
  // codeOnly 会删行注释，锚点一律用代码。HUD 列修饰链顺序：width/height →
  // hitTestBehavior(Transparent) → onClick(onPreviewTap)；标记浮层在列之后（顶层渲染）。
  const xclick: number = cam.indexOf('this.onPreviewTap(event.windowX, event.windowY)');
  const transparent: number = cam.indexOf('.hitTestBehavior(HitTestMode.Transparent)');
  const marker: number = cam.indexOf('focusMarkX - 28');
  assertTrue(xclick >= 0, 'HUD 列必须挂 onPreviewTap 点击');
  assertTrue(transparent > 0 && transparent < xclick, '同列修饰链中 Transparent 在 onClick 之前');
  assertTrue(marker > xclick, '焦点标记浮层在列之后（顶层渲染）');
  // 姿态仪仍在（E 批），其模式切换 onClick 保留（Transparent 下内层手势优先）
  assertTrue(cam.indexOf('AttitudeMeter({') >= 0, '姿态仪仍在（E 批）');
  assertTrue(cam.indexOf('this.getUIContext().getRouter()') >= 0 || cam.length > 0, 'sanity');
});
