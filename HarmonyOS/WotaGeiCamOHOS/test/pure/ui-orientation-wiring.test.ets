/**
 * UO 批（界面方向/竖屏固定）：纯逻辑全表（core/UIOrientation.ets）+ 接线守卫。
 *
 * 契约数值 6/7 是 OHOS window.Orientation 的 API 契约常量（本机 SDK d.ts 核实：
 * AUTO_ROTATION_PORTRAIT=6、AUTO_ROTATION_LANDSCAPE=7），与上游「产出 ActivityInfo 编译期
 * Int」同构——手写字面量钉住，突变必红。
 *
 * 决策钉子：2026-10-28 起 DEFAULT = LANDSCAPE（用户裁决「横屏才是常用状态」；横屏 HUD
 * 已按 T32 提前批落地，竖屏保留 T31 布局可切）；此前口径（DEFAULT=PORTRAIT）作废。
 * 守卫钉住默认档与 SettingsStore 键名，防"顺手对齐上游"破坏 T31 布局。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { UIOrientation, UIOrientations } from '../../entry/src/main/ets/core/UIOrientation';

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

// ------------------------------------------------------------------ 纯逻辑全表

test('persistValue_两档往返', () => {
  assertEq('portrait', UIOrientations.persistValue(UIOrientation.PORTRAIT));
  assertEq('landscape', UIOrientations.persistValue(UIOrientation.LANDSCAPE));
  // 上游持久化串契约（改串 = 改存量配置语义）
  const all: string = UIOrientations.ALL.map((o: UIOrientation): string => UIOrientations.persistValue(o)).join(',');
  assertEq('portrait,landscape', all, '串序与枚举序一致');
});

test('fromPersistValue_大小写不敏感_坏串回默认', () => {
  assertEq(UIOrientation.PORTRAIT, UIOrientations.fromPersistValue('portrait'));
  assertEq(UIOrientation.PORTRAIT, UIOrientations.fromPersistValue('PORTRAIT'), '手写大写也认');
  assertEq(UIOrientation.LANDSCAPE, UIOrientations.fromPersistValue('landscape'));
  assertEq(UIOrientation.LANDSCAPE, UIOrientations.fromPersistValue('Landscape'), '混合大小写');
  // 坏串/缺失一律回默认（竖屏固定），不抛
  assertEq(UIOrientations.DEFAULT, UIOrientations.fromPersistValue('nonsense'));
  assertEq(UIOrientations.DEFAULT, UIOrientations.fromPersistValue(''));
  assertEq(UIOrientations.DEFAULT, UIOrientations.fromPersistValue(undefined));
  // 默认档钉子：本侧 PORTRAIT（偏离上游 LANDSCAPE——T31 竖屏取证设计，突变必红）
  assertEq(UIOrientation.LANDSCAPE, UIOrientations.DEFAULT, '2026-10-28 裁决：出厂默认横屏（横屏才是常用状态）');
});

test('契约数值_映射到window.Orientation的AUTO_ROTATION两档', () => {
  // 本机 SDK @ohos.window.d.ts：AUTO_ROTATION_PORTRAIT=6、AUTO_ROTATION_LANDSCAPE=7。
  // 必须是 AUTO_ROTATION_*（锁轴不锁向，同轴两朝向随重力翻转），不是 PORTRAIT=1/LANDSCAPE=2。
  assertEq(6, UIOrientations.preferredOrientationCodeOf(UIOrientation.PORTRAIT), '竖屏 → AUTO_ROTATION_PORTRAIT');
  assertEq(7, UIOrientations.preferredOrientationCodeOf(UIOrientation.LANDSCAPE), '横屏 → AUTO_ROTATION_LANDSCAPE');
});

// ------------------------------------------------------------------ 接线守卫

test('接线_纯模块零平台import_数值契约在core', () => {
  const core: string = sourceText('entry/src/main/ets/core/UIOrientation.ets');
  assertTrue(core.indexOf('@kit') < 0 && core.indexOf('@ohos') < 0,
    'core/UIOrientation 必须零平台 import（契约数值在 core，window 调用在 WindowChrome）');
  assertTrue(core.indexOf('? 6') >= 0 && core.indexOf(': 7') >= 0,
    '契约数值 6/7 必须在 core 层字面钉住（ACtivityInfo Int 同构）');
});

test('接线_持久化与EntryAbility冷启与设置页写窗', () => {
  const store: string = sourceText('entry/src/main/ets/ui/SettingsStore.ets');
  assertTrue(store.indexOf("KEY_UI_ORIENTATION: string = 'ui_orientation'") >= 0,
    '键必须逐字是 ui_orientation（上游 WotaSettings.KEY_UI_ORIENTATION）');
  assertTrue(store.indexOf('UIOrientations.fromPersistValue(oriRaw as string)') >= 0,
    'load 必须经 fromPersistValue 收口坏串');
  assertTrue(store.indexOf('store.put(KEY_UI_ORIENTATION, UIOrientations.persistValue(s.uiOrientation))') >= 0,
    'save 必须写 persistValue 串');
  assertTrue(store.indexOf('static uiOrientationSync(ctx: common.Context): UIOrientation') >= 0,
    'EntryAbility 冷启必须走同步读（loadContent 前落窗口方向）');

  const ability: string = sourceText('entry/src/main/ets/entryability/EntryAbility.ets');
  assertTrue(ability.indexOf('SettingsStore.uiOrientationSync(ctx)') >= 0 &&
    ability.indexOf('WindowChrome.applyOrientation') >= 0,
    '冷启必须按持久化档位落窗口方向');

  const settings: string = sourceText('entry/src/main/ets/pages/Settings.ets');
  assertTrue(settings.indexOf('uiOrientationGroup()') >= 0, '设置页必须有「界面方向」分组');
  assertTrue(settings.indexOf('WindowChrome.applyOrientation(win, o)') >= 0,
    '点选档位必须立刻经 WindowChrome 落当前窗口');
});
