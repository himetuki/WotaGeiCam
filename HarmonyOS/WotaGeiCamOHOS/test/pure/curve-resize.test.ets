/**
 * CURVE 批（2026-10-25）：RGB 曲线弹窗右缘等比缩放守卫——对齐上游
 * CurveResizeGuardTest 四例（eb956f5）的鸿蒙源码守卫 + CurveStack 编解码往返
 * （curve_stack 持久化格式钉住）。
 *
 * 需求三条：① 右缘拖拽等比缩放；② 当前尺寸 = 默认最小值（只增不减）；
 * ③ 扇面重置连尺寸一起复位。守卫锁四处接线，谁把「只增不减」或「重置复位」
 * 优化掉，测试先红。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { ColorCurve, CurvePoint, CurveStack } from '../../entry/src/main/ets/core/CurveModel';

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

// ------------------------------------------------------------------ 上游四例的鸿蒙守卫

test('缩放下限必须是1即默认最小值', () => {
  const popup: string = sourceText('entry/src/main/ets/ui/CurvePopup.ets');
  const at: number = popup.indexOf('maxScale: number = this.maxSide() / base');
  assertTrue(at >= 0, '找不到缩放把手（baseSide/maxSide 判据被挪走，守卫失去输入）');
  const body: string = popup.substring(at, popup.indexOf('drawCurve', at));
  assertTrue(body.indexOf('Math.max(1,') >= 0,
    '缩放下限必须是 1（默认最小值）：低于它违背「当前尺寸=最小值」的需求');
});

test('扇面重置必须同时复位面板尺寸', () => {
  const popup: string = sourceText('entry/src/main/ets/ui/CurvePopup.ets');
  const reset: number = popup.indexOf('private resetAllWithSize(): void {');
  assertTrue(reset >= 0, '找不到重置钮接线（守卫失去输入）');
  const block: string = popup.substring(reset, popup.indexOf('this.panelScale = 1;', reset) + 22);
  assertTrue(block.indexOf('this.resetAll()') >= 0, '重置钮必须仍做四通道回恒等（resetAll）');
  assertTrue(block.indexOf('this.panelScale = 1;') >= 0,
    '重置钮必须复位 panelScale（需求③：重置也包括把弹窗大小复位到默认最小值）');
});

test('曲线框随panelScale缩放且不越可用区上限', () => {
  const popup: string = sourceText('entry/src/main/ets/ui/CurvePopup.ets');
  assertTrue(popup.indexOf('this.baseSide() * this.panelScale') >= 0,
    'side 必须随 panelScale 缩放（等比放大的落点）');
  const sideAt: number = popup.indexOf('private side(): number {');
  assertTrue(sideAt >= 0, '找不到 side 算式');
  const sideBody: string = popup.substring(sideAt, popup.indexOf('}', popup.indexOf('panelScale', sideAt)));
  assertTrue(sideBody.indexOf('this.maxSide()') >= 0, '缩放上限必须夹在可用区 maxSide（放大越界出屏）');
  assertTrue(popup.indexOf('this.panelScale = Math.min(maxScale, Math.max(1,') >= 0,
    '把手增量必须 coerce 到 [1, maxScale]');
});

test('节点与线宽随scale等比', () => {
  const popup: string = sourceText('entry/src/main/ets/ui/CurvePopup.ets');
  // 节点半径 6.5/9 与线宽 1/1.5/2 全部乘 scale（放大的意义就是点与线一起大）
  assertTrue(popup.indexOf('ctx.lineWidth = 1 * scale') >= 0 &&
    popup.indexOf('ctx.lineWidth = 1.5 * scale') >= 0 &&
    popup.indexOf('ctx.lineWidth = 2 * scale') >= 0, '网格/y=x/折线线宽必须乘 scale');
  assertTrue(popup.indexOf('(i === this.selected ? 9 : 6.5) * scale') >= 0,
    '节点半径必须乘 scale');
});

// ------------------------------------------------------------------ CurveStack 编解码（curve_stack 持久化格式）

function mkPoint(x: number, y: number): CurvePoint {
  return new CurvePoint(x, y);
}

test('CurveStack编码四段x冒号y分号格式', () => {
  const stack: CurveStack = CurveStack.IDENTITY.withCurve(1, new ColorCurve([mkPoint(0.5, 0.6)]));
  const raw: string = stack.encode();
  assertEq(raw.split(';').length, 4, '四段格式（段序 = 通道槽位）');
  assertTrue(raw.split(';')[1].indexOf('0.500:0.600') >= 0, '段内 `x:y` 三位小数');
  assertTrue(raw.split(';')[0] === '', '恒等段编码为空串');
});

test('编解码往返_逐字段相等_坏串回恒等', () => {
  let stack: CurveStack = CurveStack.IDENTITY;
  stack = stack.withCurve(0, new ColorCurve([mkPoint(0.5, 0.6)]));
  stack = stack.withCurve(2, new ColorCurve([mkPoint(0.25, 0.75), mkPoint(0.75, 0.25)]));
  const back: CurveStack = CurveStack.decode(stack.encode());
  assertEq(back.curveOf(0).points.length, 1);
  assertEq(back.curveOf(0).points[0].x, 0.5);
  assertEq(back.curveOf(2).points.length, 2);
  assertEq(back.curveOf(2).points[1].y, 0.25);
  assertEq(back.curveOf(1).points.length, 0, '未编辑通道恒等');
  assertTrue(CurveStack.decode('bad;only').isPassthrough, '段数不对回恒等');
  assertTrue(CurveStack.decode(undefined).isPassthrough, '缺键回恒等');
});

// ------------------------------------------------------------------ 真机取证修正（2026-10-26）

test('隐藏档根层退出命中树', () => {
  const popup: string = sourceText('entry/src/main/ets/ui/CurvePopup.ets');
  assertTrue(popup.indexOf('.visibility(this.visible ? Visibility.Visible : Visibility.None)') >= 0,
    '根 Stack 必须按 visible 门控 visibility——全屏层常驻（Default 命中）会挡掉整页兄弟节点的触摸' +
    '（真机表现为录制页所有控件点不动）');
});
