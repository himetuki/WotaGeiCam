/**
 * J3b 批：读数条**顺序**接进 `hud_layout` 位置表（真实 v2 编解码）的纯逻辑面 + 接线守卫。
 *
 * 设计性偏离（登记）：上游「编辑控件」页是自由拖拽换区/落格；本侧取景 HUD 是 T31 固定结构，
 * 编辑器只做 READOUT 容器内的顺序（`withZoneOrder`）与显隐（`hud_items`，J3a）。
 * 顺序的持久化走上游同一份 `hud_layout` 契约串——将来做完整编辑器时存量数据直接可用。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { HudItem } from '../../entry/src/main/ets/core/Constants';
import { HudEntry, HudLayoutTable, HudZone } from '../../entry/src/main/ets/core/HudLayout';
import { HudSpec } from '../../entry/src/main/ets/ui/HudSpec';

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

function idsOf(entries: Array<HudEntry>): string {
  return entries.map((e: HudEntry): string => e.id).join(',');
}

// ------------------------------------------------------------------ 顺序改写（withZoneOrder）

test('withZoneOrder_顺序经真编解码往返且不动其它容器', () => {
  const base: HudLayoutTable = HudLayoutTable.decode('');
  const reordered: Array<HudEntry> = [
    HudEntry.ofItem(HudItem.ISO), HudEntry.ofItem(HudItem.SHUTTER), HudEntry.ofItem(HudItem.FPS),
    HudEntry.ofItem(HudItem.BITRATE), HudEntry.ofItem(HudItem.EV), HudEntry.ofItem(HudItem.WB),
    HudEntry.ofItem(HudItem.ZOOM)
  ];
  const rewritten: HudLayoutTable = base.withZoneOrder(HudZone.READOUT, reordered);
  assertEq('H3,H0,H1,H2,H4,H5,H6', idsOf(rewritten.orderOf(HudZone.READOUT)), '内存态顺序');
  // 编解码往返：hud_layout 的 v2 串是真契约（与上游逐字节一致），顺序必须原样存下来
  const round: HudLayoutTable = HudLayoutTable.decode(rewritten.encode());
  assertEq('H3,H0,H1,H2,H4,H5,H6', idsOf(round.orderOf(HudZone.READOUT)), 'decode(encode(x)) 顺序');
  // 其它容器一行不碰（TOP 默认顺序 P11,P12 = SIZE/STORAGE；位置全默认）
  assertEq(idsOf(base.orderOf(HudZone.TOP)), idsOf(round.orderOf(HudZone.TOP)), 'TOP 顺序不变');
  assertEq(-1, round.posOf(HudZone.TOP).xDp, 'TOP 位置不变（哨兵）');
  assertEq(-1, round.posOf(HudZone.READOUT).xDp, 'READOUT 位置不变（哨兵）');
});

test('withZoneOrder_缺项补回尾部_重复丢弃_格子保留', () => {
  const base: HudLayoutTable = HudLayoutTable.decode('');
  // 传残缺且有重复的顺序：normalize 去重 [H2,H0]，缺失项按 HudEntry.ALL 声明序补到 home 区尾部
  const partial: Array<HudEntry> = [
    HudEntry.ofItem(HudItem.BITRATE), HudEntry.ofItem(HudItem.BITRATE),
    HudEntry.ofItem(HudItem.SHUTTER)
  ];
  const rewritten: HudLayoutTable = base.withZoneOrder(HudZone.READOUT, partial);
  assertEq('H2,H0,H1,H3,H4,H5,H6', idsOf(rewritten.orderOf(HudZone.READOUT)),
    '去重后 [H2,H0]，缺的 H1/H3/H4/H5/H6 按 ALL 序补尾');
  // 其它容器未被殃及（P 胶囊各归各位）
  assertEq(idsOf(base.orderOf(HudZone.LEFT)), idsOf(rewritten.orderOf(HudZone.LEFT)));
});

test('visibleOrderOf_过滤后顺序来自表而不是掩码枚举序', () => {
  const base: HudLayoutTable = HudLayoutTable.decode('');
  const reordered: HudLayoutTable = base.withZoneOrder(HudZone.READOUT, [
    HudEntry.ofItem(HudItem.ISO), HudEntry.ofItem(HudItem.WB), HudEntry.ofItem(HudItem.SHUTTER),
    HudEntry.ofItem(HudItem.FPS), HudEntry.ofItem(HudItem.BITRATE), HudEntry.ofItem(HudItem.ZOOM),
    HudEntry.ofItem(HudItem.EV)
  ]);
  assertEq('H3,H5,H0,H1,H2,H6,H4', idsOf(reordered.orderOf(HudZone.READOUT)), '表顺序（ISO 提到最前）');
  const visible: Set<string> = new Set<string>(['H0', 'H3']);
  const visibleOrder: Array<HudEntry> = reordered.visibleOrderOf(HudZone.READOUT, visible);
  // 表顺序 H3 在 H0 前：过滤输出 [H3,H0]——枚举声明序则是 [H0,H3]，两条路由此区分
  assertEq('H3,H0', idsOf(visibleOrder), '顺序跟表走而不是掩码枚举序');
  // 「隐藏再显示不回默认位置」的另一半：表的完整顺序不因过滤丢条目
  assertEq(7, reordered.orderOf(HudZone.READOUT).length, '完整顺序含被隐藏条目');
});

test('默认表顺序等于枚举声明序_J3a行为不回归', () => {
  // 空串/坏串 → 默认表；默认 READOUT 顺序 = HudItem 枚举声明序（J3a 的 typesOf 枚举序同义）
  assertEq('H0,H1,H2,H3,H4,H5,H6', idsOf(HudLayoutTable.decode('').orderOf(HudZone.READOUT)));
  assertEq('H0,H1,H2,H3,H4,H5,H6', idsOf(HudLayoutTable.decode('garbage').orderOf(HudZone.READOUT)),
    '坏串整表回默认（decode 内收口）');
});

// ------------------------------------------------------------------ 接线守卫（源码扫描）

test('接线_编辑页经withZoneOrder与encode写hud_layout', () => {
  const editor: string = sourceText('entry/src/main/ets/pages/HudEditor.ets');
  assertTrue(editor.indexOf('withZoneOrder(HudZone.READOUT, next)') >= 0,
    '顺序改写必须走 HudLayoutTable.withZoneOrder（不许手拼串/手改 order）');
  assertTrue(editor.indexOf('.encode()') >= 0,
    '落盘必须经 HudLayoutTable.encode（v2 契约串单一真源）');
  assertTrue(editor.indexOf('HudLayoutTable.decode(this.settings.hudLayout)') >= 0,
    '读回必须经 decode（坏串收口）');
  assertTrue(editor.indexOf('HudItems.toggle(this.settings.hudItems, item)') >= 0,
    '显隐仍走 hud_items 掩码（J3a 同源，不得另造第二套显隐）');

  const store: string = sourceText('entry/src/main/ets/ui/SettingsStore.ets');
  assertTrue(store.indexOf("KEY_HUD_LAYOUT: string = 'hud_layout'") >= 0,
    'SettingsStore 键必须逐字是 hud_layout（上游 WotaSettings.KEY_HUD_LAYOUT）');
  assertTrue(store.indexOf('store.put(KEY_HUD_LAYOUT, s.hudLayout)') >= 0, 'save 必须写回原文串');
});

test('接线_相机页顺序消费表_设置页提供入口', () => {
  const cam: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertTrue(cam.indexOf('.visibleOrderOf(HudZone.READOUT, visible)') >= 0,
    '读数条顺序必须来自位置表的 visibleOrderOf（掩码只管显隐）');
  assertTrue(cam.indexOf('HudLayoutTable.decode(raw)') >= 0,
    '相机页必须经 decode 读表（坏串/空串回默认表）');
  // 回归红线：J3a 的换行档与分组算式不许因顺序接线被换掉
  assertTrue(cam.indexOf('hudPerRowFor(order.length, this.hudFontScale,') >= 0,
    '换行档仍走 hudPerRowFor（喂可见条目数）');
  assertTrue(cam.indexOf('hudRowGroups(HudZone.READOUT, order, perRow)') >= 0,
    '分组仍走 hudRowGroups(READOUT)');

  const settings: string = sourceText('entry/src/main/ets/pages/Settings.ets');
  assertTrue(settings.indexOf("pushUrl({ url: 'pages/HudEditor' })") >= 0,
    '设置页「取景读数」分组必须有编辑页入口');

  const pages: string = readFileSync(resolve(srcRoot(),
    'entry/src/main/resources/base/profile/main_pages.json'), 'utf8');
  assertTrue(pages.indexOf('pages/HudEditor') >= 0, 'HudEditor 必须登记进 main_pages.json');
});
