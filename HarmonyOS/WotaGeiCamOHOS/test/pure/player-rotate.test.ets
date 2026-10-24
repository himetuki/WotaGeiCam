/**
 * RV 批（2026-10-24）：播放页旋转观看——nextCcwQuarter 全表（直译上游
 * PlayerScreen.nextCcwQuarter）+ 接线守卫（按钮/变换链/会话态口径）。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { nextCcwQuarter } from '../../entry/src/main/ets/player/PlayerModel';

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

test('逆时针四分之一圈_主环', () => {
  assertEq(nextCcwQuarter(0), 3, '0→3');
  assertEq(nextCcwQuarter(1), 0, '1→0');
  assertEq(nextCcwQuarter(2), 1, '2→1');
  assertEq(nextCcwQuarter(3), 2, '3→2');
});

test('逆时针四分之一圈_越界与负数折回', () => {
  assertEq(nextCcwQuarter(4), 3, '整圈回到等价角再走一步');
  assertEq(nextCcwQuarter(8), 3);
  assertEq(nextCcwQuarter(-1), 2, '保号余数折回（Kotlin 同语义）');
  assertEq(nextCcwQuarter(-5), 2);
  // 连按 4 次必回原角
  let q: number = 0;
  for (let i = 0; i < 4; i++) {
    q = nextCcwQuarter(q);
  }
  assertEq(q, 0, '四连按回原角');
});

test('接线_按钮与变换链', () => {
  const page: string = sourceText('entry/src/main/ets/pages/Player.ets');
  assertTrue(page.indexOf('this.quarterTurns = nextCcwQuarter(this.quarterTurns)') >= 0,
    '旋转按钮必须走纯逻辑步进');
  assertTrue(page.indexOf('Button(`旋转:${this.quarterTurns * 90}°`)') >= 0,
    '按钮读数 = 计数 × 90（上游度数换算）');
  assertTrue(page.indexOf("angle: this.quarterTurns * 90, centerX: '50%', centerY: '50%'") >= 0,
    '绕图层中心旋转（上游 rotationZ 口径）');
  // 链序：scale（镜像）→ rotate → translate（平移最外层，跟手方向不受镜像/旋转影响）
  const si: number = page.indexOf('.scale({ x: this.mirrored');
  const ri: number = page.indexOf('.rotate({ x: 0, y: 0, z: 1');
  const ti: number = page.indexOf('.translate({ x: this.panX');
  assertTrue(si >= 0 && si < ri && ri < ti, '变换链序 scale→rotate→translate（上游 graphicsLayer 同构）');
  // 会话态：不持久化（无 prefs 写）、不随换片清零（无重置语句）
  assertTrue(page.indexOf('quarterTurns = 0') < 0, '不得重置（换片不清零，与镜像同口径）');
  assertTrue(page.indexOf('putInt(GALLERY_COLUMNS_KEY, this.quarterTurns') < 0 &&
    sourceText('entry/src/main/ets/player/AbPersist.ets').indexOf('quarterTurns') < 0,
    '不持久化（会话态观看辅助）');
});
