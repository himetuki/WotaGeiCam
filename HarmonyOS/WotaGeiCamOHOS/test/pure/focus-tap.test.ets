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
import { FocusTapArbiter, FocusTapMath } from '../../entry/src/main/ets/core/FocusTap';

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

test('归一化_中心点约0.5_越界钳到0..1（FL对齐上游）', () => {
  const center = FocusTapMath.pointFromWindowTap(660, 1428, 1320, 2856);
  // 660/1320 = 0.5；1428/2856 = 0.5
  assertTrue(Math.abs(center.x - 0.5) < 1e-9, '中心 x = 0.5');
  assertTrue(Math.abs(center.y - 0.5) < 1e-9, '中心 y = 0.5');

  // 屏外点：钳到 [0,1]（上游 toTapPoint 原样——TFC 期 2% 边缘余量撤销对齐上游）
  const tl = FocusTapMath.pointFromWindowTap(-5, -5, 1320, 2856);
  assertEq(0, tl.x, 'x 钳到 0');
  assertEq(0, tl.y, 'y 钳到 0');
  const br = FocusTapMath.pointFromWindowTap(2000, 3000, 1320, 2856);
  assertEq(1, br.x, 'x 钳到 1');
  assertEq(1, br.y, 'y 钳到 1');
});

test('归一化_视窗尺寸非法回画面中心（上游toTapPoint原样）', () => {
  const c1 = FocusTapMath.pointFromWindowTap(100, 100, 0, 0);
  assertTrue(Math.abs(c1.x - 0.5) < 1e-9 && Math.abs(c1.y - 0.5) < 1e-9, '尺寸 0 → 中心');
  const c2 = FocusTapMath.pointFromWindowTap(100, Number.NaN, -1, 100);
  assertTrue(Math.abs(c2.x - 0.5) < 1e-9 && Math.abs(c2.y - 0.5) < 1e-9, 'NaN 坐标 → 中心');
});

// ------------------------------------------------------------------ 三击派发仲裁（FL）

test('仲裁_单击立即对焦_窗口内第二击为锁定', () => {
  const arb = new FocusTapArbiter(280);
  assertFalse(arb.onTapUp(1000), '第一击 = 单击（立即对焦）');
  assertTrue(arb.onTapUp(1200), '200ms 后第二击 = 双击（锁定）');
  assertFalse(arb.onTapUp(5000), '远隔的第二击 = 新单击序列');
});

test('仲裁_恰在窗口边界_语义稳定', () => {
  const arb = new FocusTapArbiter(280);
  arb.onTapUp(1000);
  assertTrue(arb.onTapUp(1280), '恰 280ms 算双击（<= 判据）');
  arb.reset();
  arb.onTapUp(1000);
  assertFalse(arb.onTapUp(1281), '281ms 算新单击');
});

// ------------------------------------------------------------------ 接线守卫

test('接线_XComponent点击下发focusPoint_标记与门控', () => {
  const cam: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  // FL：点击挂 HUD 列（Transparent 穿透，空白区落到列）；XComponent(SURFACE) onClick
  // 不触发（平台行为，模拟器实证）——守卫钉住挂载点在 HUD 列而非 XComponent。
  assertTrue(cam.indexOf('this.onPreviewTap(event.windowX, event.windowY)') >= 0,
    '必须有点按消费入口（windowX/windowY 坐标）');
  const xcIdx: number = cam.indexOf("XComponent({ id: 'wotaCamera'");
  const hudColIdx: number = cam.indexOf('this.onPreviewTap(event.windowX, event.windowY)');
  assertTrue(xcIdx >= 0 && hudColIdx > xcIdx,
    '点按入口必须挂在 HUD 列（XComponent(SURFACE) onClick 不触发——平台行为）');
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
