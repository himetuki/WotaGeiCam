/**
 * 站位指示线纯函数全表——对拍上游 MarkerLines.kt / MarkerLinesTest（2026-10-27 交互定版）：
 * 等距竖线 x 比例（居中对称、整组占 spread 比例宽、任意组合不出画）+ 域钳 +
 * 六色色板的序/名往返。
 */
import { MarkerLineColor, MarkerLines } from '../../entry/src/main/ets/core/MarkerLines';

test('线数 0 = 关闭（空表）；1 恒居中', () => {
  assertEq(0, MarkerLines.lineXsFraction(0, 0.6).length, '默认关');
  const one: Array<number> = MarkerLines.lineXsFraction(1, 0.6);
  assertEq(1, one.length);
  assertEq(0.5, one[0], '单线恒居中');
});

test('等距居中：count=2/3 spread=0.6', () => {
  const two: Array<number> = MarkerLines.lineXsFraction(2, 0.6);
  assertEq(0.2, two[0]);
  assertEq(0.8, two[1]);
  const three: Array<number> = MarkerLines.lineXsFraction(3, 0.6);
  assertEq(0.2, three[0]);
  assertEq(0.5, three[1]);
  assertEq(0.8, three[2]);
});

test('满宽 8 条：0 .. 1 全落画内不出画', () => {
  const xs: Array<number> = MarkerLines.lineXsFraction(8, 1.0);
  assertEq(8, xs.length);
  assertEq(0, xs[0]);
  assertEq(1, xs[7]);
  for (const x of xs) {
    assertTrue(x >= 0 && x <= 1, '任何 (count, spread) 组合都不出画');
  }
});

test('域钳：count 0..8、spread 0.15..1.0；NaN 回默认', () => {
  assertEq(0, MarkerLines.clampCount(-3));
  assertEq(8, MarkerLines.clampCount(99));
  assertEq(0.15, MarkerLines.clampSpread(0.01));
  assertEq(1.0, MarkerLines.clampSpread(9));
  assertEq(0.6, MarkerLines.clampSpread(NaN), '坏值回默认间距');
  assertEq(MarkerLines.MAX_COUNT, MarkerLines.lineXsFraction(99, 0.6).length, '脏 count 钳回 8');
});

test('色板：六色 + 名往返 + 坏值回黄', () => {
  assertEq(6, MarkerLineColor.ALL.length);
  assertEq('GREEN', MarkerLineColor.GREEN.enumName());
  assertEq(MarkerLineColor.GREEN.argb, MarkerLineColor.fromName('GREEN').argb, '持久化名往返');
  assertEq(MarkerLineColor.YELLOW.argb, MarkerLineColor.fromName('bogus').argb, '坏名回黄');
  assertEq(MarkerLineColor.YELLOW.argb, MarkerLineColor.fromOrdinal(99).argb, '越界序回黄');
  assertEq(MarkerLineColor.MAGENTA.argb, MarkerLineColor.fromOrdinal(2).argb);
  assertTrue(MarkerLineColor.WHITE.sameAs(MarkerLineColor.fromName('WHITE')));
});

test('接线：覆盖层/圆钮/持久化三链在位', () => {
  const { existsSync, readFileSync } = require('node:fs');
  const { resolve } = require('node:path');
  let dir: string = __dirname;
  for (let i = 0; i < 6; i++) {
    if (existsSync(resolve(dir, 'entry/src/main/ets/core/CameraAbility.ets'))) {
      break;
    }
    dir = resolve(dir, '..');
  }
  const page: string = readFileSync(resolve(dir, 'entry/src/main/ets/pages/Camera.ets'), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
  assertTrue(page.indexOf('MarkerLineOverlay({') >= 0, '覆盖层挂预览');
  assertTrue(page.indexOf('MARKER_BTN_ID') >= 0, '圆钮 id 在册（锚点实测）');
  assertTrue(page.indexOf('this.params.markerCount.value') >= 0, '线数流接线');
  assertTrue(page.indexOf('SliderChangeMode.End') >= 0, '间距松手落盘纪律');
});
