/**
 * HM7-UI-02 令牌统一批：PageColor 页面语义色板（值 = 迁移前在用字面量）+
 * 页面迁移守卫（四页零残留字面量、消费令牌、notation 归一）。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { HudColor, PageColor } from '../../entry/src/main/ets/ui/HudTokens';

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

test('PageColor_语义值迁移前逐字', () => {
  assertEq(PageColor.CARD, '#1F1F1F');
  assertEq(PageColor.PANEL, '#263238');
  assertEq(PageColor.CHIP, '#333333');
  assertEq(PageColor.TEXT_SECONDARY, '#B0BEC5');
  assertEq(PageColor.TEXT_DIM, '#AAAAAA');
  assertEq(PageColor.TEXT_FAINT, '#78909C');
  assertEq(PageColor.TEXT_HINT, '#90A4AE');
  assertEq(PageColor.LIKED, '#FF3D71');
  assertEq(PageColor.OVERLAY, '#AA000000');
  assertEq(PageColor.OVERLAY_SOFT, '#99000000');
  assertEq(PageColor.PANEL_COMPARE, '#F0202020');
});

test('notation归一_WARN_DANGER值等', () => {
  // 迁移前页面字面量 #FFCC80/#FF8A80 与 HUD 令牌值等（8 位补 FF alpha，视觉恒等）
  assertEq(HudColor.WARN, '#FFFFCC80');
  assertEq(HudColor.DANGER, '#FFFF8A80');
});

test('页面_零残留字面量_消费令牌', () => {
  const pages: Array<string> = [
    'entry/src/main/ets/pages/Gallery.ets',
    'entry/src/main/ets/pages/Player.ets',
    'entry/src/main/ets/pages/Compare.ets'
  ];
  const banned: Array<string> = [
    '#1F1F1F', '#263238', '#333333', '#B0BEC5', '#AAAAAA', '#78909C', '#90A4AE',
    '#FF3D71', '#AA000000', '#99000000', '#F0202020', '#FFCC80', '#FF8A80', '#FFFFFF'
  ];
  for (const rel of pages) {
    const code: string = sourceText(rel);
    for (const b of banned) {
      assertEq(code.indexOf(b), -1, `${rel} 不得残留字面量 ${b}`);
    }
    assertTrue(code.indexOf('PageColor.') >= 0, `${rel} 必须消费 PageColor`);
    assertTrue(code.indexOf("from '../ui/HudTokens'") >= 0, `${rel} 必须从令牌真源 import`);
  }
  // Camera 仅 2 处偏离也已迁移（HUD 页不得出现页级字面量弱化文本/危险色）
  const cam: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertEq(cam.indexOf('#AAAAAA'), -1, 'Camera 不得残留 #AAAAAA');
  assertEq(cam.indexOf('#FF8A80'), -1, 'Camera 不得残留 #FF8A80');
});
