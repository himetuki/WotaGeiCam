/**
 * 播放页 chrome 状态矩阵真值表——对拍上游 PlayerChromeTest.kt（e7b7bf2，docs/plan/29 §6.3）
 * + 页面接线守卫（收起落盘/浮动返回/拉出钮三点在位）。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { PlayerChrome, playerChromeOf } from '../../entry/src/main/ets/player/PlayerChrome';

test('真值表：控件可见+未收起 = 顶栏+dock，无浮动返回无拉出', () => {
  const c: PlayerChrome = playerChromeOf(true, false);
  assertTrue(c.topBar, '完整顶栏');
  assertTrue(c.dock);
  assertFalse(c.floatingBack, '顶栏在场 ⇒ 无浮动返回');
  assertFalse(c.pullOut);
});

test('真值表：控件可见+已收起 = 无顶栏、有 dock、有浮动返回、有拉出', () => {
  const c: PlayerChrome = playerChromeOf(true, true);
  assertFalse(c.topBar, '收起后完整顶栏不渲染');
  assertTrue(c.dock, '收起顶栏不动 dock');
  assertTrue(c.floatingBack, '浮动返回是唯一常驻出口');
  assertTrue(c.pullOut, '拉出钮只在「收起且 dock 可见」');
});

test('真值表：控件隐藏 = 全部隐，唯浮动返回恒在', () => {
  const c: PlayerChrome = playerChromeOf(false, false);
  assertFalse(c.topBar);
  assertFalse(c.dock);
  assertTrue(c.floatingBack);
  assertFalse(c.pullOut, '拉出跟着 dock 一起走');
  const c2: PlayerChrome = playerChromeOf(false, true);
  assertTrue(c2.floatingBack, '收起+控件隐藏同款');
  assertFalse(c2.pullOut);
});

test('接线：收起落盘/拉出展开/浮动返回/进页读四点在位', () => {
  let dir: string = __dirname;
  for (let i = 0; i < 6; i++) {
    if (existsSync(resolve(dir, 'entry/src/main/ets/core/CameraAbility.ets'))) {
      break;
    }
    dir = resolve(dir, '..');
  }
  const page: string = (readFileSync(resolve(dir, 'entry/src/main/ets/pages/Player.ets'), 'utf8'))
    .replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
  assertTrue(page.indexOf('playerChromeOf(this.controlsVisible, this.topBarCollapsed)') >= 0,
    'chrome 矩阵唯一出口被页面消费');
  assertTrue(page.indexOf('PlayerChromeStore.writeCollapsed(cctx, true)') >= 0,
    '收起即落盘（冷启保持）');
  assertTrue(page.indexOf('PlayerChromeStore.writeCollapsed(pctx, false)') >= 0,
    '拉出展开即落盘');
  assertTrue(page.indexOf('chrome2.floatingBack') >= 0 || page.indexOf('.floatingBack') >= 0,
    '浮动返回由矩阵驱动');
  assertTrue(page.indexOf('PlayerChromeStore.readCollapsed') >= 0, '进页读收起态');
  assertTrue(page.indexOf("'player_top_bar_collapsed'") < 0, '键名常量在 store 不在页面');
});
