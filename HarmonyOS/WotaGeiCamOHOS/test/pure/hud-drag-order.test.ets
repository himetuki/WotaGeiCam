/**
 * HDG 批（2026-10-25）：取景读数顺序编辑器的拖拽落位——纯逻辑全表 + 接线守卫。
 *
 * 范围声明（见 core/HudDragOrder 头注）：上游 HudLayoutEditor 的**跨容器自由布局**在本侧
 * 没有渲染面（HUD 是 T31 取证后的固定结构，只有 READOUT 区是有序列表），故本批只落
 * 「同容器内拖拽换位」这半——跨容器拖拽仍是产品决策（重做取景 HUD）且必须真机验收。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { HudDragOrder } from '../../entry/src/main/ets/core/HudDragOrder';

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

test('落位_位移折算与夹取', () => {
  // 行高 50：向下拖 100 → 跨 2 行
  assertEq(HudDragOrder.targetIndex(0, 100, 50, 5), 2);
  assertEq(HudDragOrder.targetIndex(2, -100, 50, 5), 0, '向上拖');
  assertEq(HudDragOrder.targetIndex(0, -100, 50, 5), 0, '越上界夹首');
  assertEq(HudDragOrder.targetIndex(4, 100, 50, 5), 4, '越下界夹末');
  assertEq(HudDragOrder.targetIndex(2, 0, 50, 5), 2, '原地');
});

test('落位_半行死区不换位', () => {
  // 24 < 半行(25)：不动；26 ≥ 半行：换一格
  assertEq(HudDragOrder.targetIndex(1, 24, 50, 5), 1, '不足半行不换');
  assertEq(HudDragOrder.targetIndex(1, 26, 50, 5), 2, '过半行换一格');
  assertEq(HudDragOrder.targetIndex(1, -26, 50, 5), 0);
});

test('落位_异常输入不动作', () => {
  assertEq(HudDragOrder.targetIndex(0, 100, 0, 5), 0, '行高未量到 → 原下标（胜过按错行高乱跳）');
  assertEq(HudDragOrder.targetIndex(0, 100, -1, 5), 0);
  assertEq(HudDragOrder.targetIndex(0, Number.NaN, 50, 5), 0);
  assertEq(HudDragOrder.targetIndex(9, 100, 50, 5), 9, '起下标越界');
  assertEq(HudDragOrder.targetIndex(0, 100, 50, 0), 0, '空表');
});

test('换位_其余整体让位_长度不变', () => {
  const src: Array<string> = ['a', 'b', 'c', 'd'];
  assertEq(HudDragOrder.moveTo(src, 0, 2).join(','), 'b,c,a,d', '前移');
  assertEq(HudDragOrder.moveTo(src, 3, 1).join(','), 'a,d,b,c', '后移');
  assertEq(HudDragOrder.moveTo(src, 1, 1).join(','), 'a,b,c,d', '同下标不变');
  assertEq(src.join(','), 'a,b,c,d', '原数组不被改动（副本语义）');
  assertEq(HudDragOrder.moveTo(src, 9, 0).join(','), 'a,b,c,d', '越界返回副本');
});

test('换位_是否变化判据', () => {
  assertTrue(HudDragOrder.changed(1, 2));
  assertFalse(HudDragOrder.changed(2, 2), '没动不写盘');
  assertFalse(HudDragOrder.changed(-1, 2), '未拾起不算变化');
  assertFalse(HudDragOrder.changed(2, -1));
});

// ------------------------------------------------------------------ 接线守卫

test('接线_编辑器拖拽接线与单一真源', () => {
  const ed: string = sourceText('entry/src/main/ets/pages/HudEditor.ets');
  assertTrue(ed.indexOf('HudDragOrder.targetIndex(') >= 0, '落位算式走纯逻辑真源');
  assertTrue(ed.indexOf('HudDragOrder.moveTo(this.order, from, to)') >= 0, '换位走纯逻辑真源');
  assertTrue(ed.indexOf('HudDragOrder.changed(from, to)') >= 0, '没换位不写盘');
  assertTrue(ed.indexOf('this.writeOrder(') >= 0,
    '顺序改写仍走 withZoneOrder 同容器路径（不手写位运算/串拼接）');
  assertTrue(ed.indexOf('LongPressGesture({ repeat: false, duration: 400 })') >= 0, '长按拾起');
  assertTrue(ed.indexOf('PanGesture({ fingers: 1, direction: PanDirection.Vertical') >= 0,
    '纵向拖动跟手');
  assertTrue(ed.indexOf('PanDirection.Horizontal') < 0, '只认纵向（横向无落点）');
  // 与既有 ↑/↓ 并存（不是替换）
  assertTrue(ed.indexOf("Button('↑')") >= 0 && ed.indexOf("Button('↓')") >= 0, '按钮操作保留');
});

test('接线_范围声明在册_跨容器仍是产品决策', () => {
  const core: string = readFileSync(
    resolve(srcRoot(), 'entry/src/main/ets/core/HudDragOrder.ets'), 'utf8');
  assertTrue(core.indexOf('跨容器拖拽是') >= 0,
    '头注必须写明跨容器自由布局不在本批（无渲染面 + 需真机验收）');
  const ed: string = sourceText('entry/src/main/ets/pages/HudEditor.ets');
  assertTrue(ed.indexOf('writeOrder') >= 0);
});