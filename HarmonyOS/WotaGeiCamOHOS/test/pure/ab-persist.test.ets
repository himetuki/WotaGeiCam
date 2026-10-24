/**
 * ABP 批（2026-10-24）：播放页 A-B 段持久化——纯逻辑直译上游 PlayerEngine.restoreAb
 * （键名逐字/键编码/损坏收整）+ 接线守卫（Player.ets 水化/回写点、引擎不覆盖闸门）。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { AbRange } from '../../entry/src/main/ets/player/PlayerModel';
import {
  AB_KEY_A, AB_KEY_B, AB_KEY_LOOP, AB_PREFS_NAME, abKeyOf, hasAbRecord, sanitizeAb
} from '../../entry/src/main/ets/player/AbPersist';

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

// ------------------------------------------------------------------ 键名/键编码（上游逐字）

test('键名_prefs与三键上游逐字', () => {
  assertEq(AB_PREFS_NAME, 'wota_player_ab');
  assertEq(AB_KEY_A, 'ab_a');
  assertEq(AB_KEY_B, 'ab_b');
  assertEq(AB_KEY_LOOP, 'ab_loop');
});

test('键编码_前缀冒号媒体标识', () => {
  assertEq(abKeyOf('ab_a', 'VID_20260101_000001_001.mp4'), 'ab_a:VID_20260101_000001_001.mp4');
  assertEq(abKeyOf('ab_loop', '/a/b'), 'ab_loop:/a/b');
});

// ------------------------------------------------------------------ 恢复收整（restoreAb 直译）

test('收整_全空_无效且无记录', () => {
  const ab: AbRange = sanitizeAb(-1, -1, false);
  assertFalse(ab.valid);
  assertFalse(ab.loopEnabled);
  assertFalse(hasAbRecord(-1, -1, false));
});

test('收整_只有A_B保持-1_算有记录', () => {
  const ab: AbRange = sanitizeAb(1000, -1, false);
  assertEq(ab.aMs, 1000);
  assertEq(ab.bMs, -1);
  assertFalse(ab.valid);
  assertTrue(hasAbRecord(1000, -1, false));
});

test('收整_合法AB_循环随记录', () => {
  const ab: AbRange = sanitizeAb(500, 2000, true);
  assertTrue(ab.valid);
  assertTrue(ab.loopEnabled);
});

test('收整_损坏A大于B_B清-1_循环失效', () => {
  const ab: AbRange = sanitizeAb(2000, 1000, true);
  assertEq(ab.aMs, 2000);
  assertEq(ab.bMs, -1);
  assertFalse(ab.valid);
  assertFalse(ab.loopEnabled);
});

test('收整_B恰等于A_同口径清B', () => {
  const ab: AbRange = sanitizeAb(1500, 1500, false);
  assertEq(ab.bMs, -1);
  assertFalse(ab.valid);
});

test('收整_循环开但范围缺失_循环钳回关_算有记录', () => {
  const ab: AbRange = sanitizeAb(-1, -1, true);
  assertFalse(ab.loopEnabled);
  assertTrue(hasAbRecord(-1, -1, true));
});

// ------------------------------------------------------------------ 接线守卫

test('接线_页面水化与回写点', () => {
  const page: string = sourceText('entry/src/main/ets/pages/Player.ets');
  // 全新进页（非 surface 重建）才水化；restoreSession 路径已带会话 AB
  assertTrue(page.indexOf('if (this.restoreAb === undefined) {') >= 0 &&
    page.indexOf('this.loadPersistedAb(clip, engine);') >= 0,
    'attach 成功后必须走 loadPersistedAb（restoreAb 未定义分支）');
  // 水化回调护栏：引擎换代/销毁/无记录即弃
  assertTrue(page.indexOf('this.engine !== engine || ab === undefined') >= 0,
    '水化回调必须护栏 引擎换代/销毁/无记录');
  assertTrue(page.indexOf('engine.applyPersistedAb(ab);') >= 0, '水化必须经引擎 applyPersistedAb');
  // 打点/循环/清除即写：markA、markB 成功支、toggle 成功支、clearAb 共 4 处
  const markBlock: string =
    page.slice(page.indexOf('private markA'), page.indexOf('private loadPersistedAb'));
  assertEq((markBlock.match(/this\.persistAb\(\);/g) ?? []).length, 4,
    'markA/markB成功/toggle成功/clearAb 四处即写（失败支不写）');
  assertTrue(page.indexOf('AbPersistStore.save(ctx, clip.path, this.engine.ab)') >= 0,
    '回写按媒体路径整段落盘（清除也写）');
});

test('接线_引擎不覆盖闸门与store薄封装', () => {
  const engine: string = sourceText('entry/src/main/ets/player/PlayerEngine.ets');
  assertTrue(engine.indexOf('applyPersistedAb(ab: AbRange): void {') >= 0,
    '引擎必须有 applyPersistedAb');
  assertTrue(engine.indexOf('this.released || this.ab.valid || this.ab.loopEnabled') >= 0,
    '水化不得覆盖会话已有 AB');
  const store: string = sourceText('entry/src/main/ets/player/AbPersistStore.ets');
  assertTrue(store.indexOf('getPreferences(ctx, AB_PREFS_NAME)') >= 0, 'store 走纯逻辑常量开仓');
  assertTrue(store.indexOf('sanitizeAb(a, b, loop)') >= 0, '读侧必须过 sanitizeAb 收整');
  assertTrue(store.indexOf('hasAbRecord(a, b, loop)') >= 0, '无记录返回 undefined（不灌引擎）');
  assertTrue(store.indexOf('abKeyOf(AB_KEY_A, mediaPath)') >= 0, '写键经纯逻辑键编码');
});
