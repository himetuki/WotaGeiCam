/**
 * J3a 批：`hud_items` 位掩码接到真实 UI 的**接线守卫 + 行为面**。
 *
 * 掩码编解码契约（typesOf/maskOf/DEFAULT_MASK 与上游同值）已在 `hud-layout.test.ets`
 * 的「编辑页可见集与 hud_items 掩码契约」里钉住，本文件不重复；这里测的是本批新落的三层：
 * 1. **行为面**：`HudItems.maskOrDefault` 坏值收口（SettingsStore 的解析抽成的纯函数）、
 *    `isOn`/`toggle` 多选往返（设置页 chip 的读写只经这两件，不许调用点手写 `mask ^ bit`）、
 *    掩码 → 读数条分组的桥（对拍上游 `HudDefaultIsoTest` 的「计划 → 行为」：掩码经
 *    `typesOf` + `hudRowGroups(READOUT)` 一路到格子序，期望值手写）。
 * 2. **接线守卫**（源码扫描，写法参照 `clip-export-shift.test.ets` 的 sourceText/codeOnly）：
 *    设置页分组确实经 HudItems 函数写掩码且键为 `hud_items`；相机页确实以 `HudItems.typesOf`
 *    驱动读数条并消费 J1/J2 的行规划函数（hudPerRowFor/hudRoomDp/hudRowGroups）；
 *    mask=0 时整条不渲染的分支存在；两页调用点没有手写位运算。
 * 3. **去重红线**：固定行数组（`HudSpec.HUD_ROW1/2`，纯常量可直接 import）里不再含
 *    shutter/iso/ev/wb/fps——同一读数不得既在固定行又在读数条出现两处。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { HudItem, HudItems } from '../../entry/src/main/ets/core/Constants';
import { HudEntry, HudZone, hudRowGroups } from '../../entry/src/main/ets/core/HudLayout';
import { hudPerRowFor, hudRoomDp } from '../../entry/src/main/ets/core/HudMetrics';
import { HudSpec } from '../../entry/src/main/ets/ui/HudSpec';

/** 从转译产物目录向上找仓库根（含 entry/src/main/ets/ui/HudSpec.ets 的祖先目录） */
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

/** 去注释（块注释 + 整行行注释）：让接线扫描只看代码，注释里的关键词不算数 */
function codeOnly(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
}

function sourceText(rel: string): string {
  return codeOnly(readFileSync(resolve(srcRoot(), rel), 'utf8'));
}

// ------------------------------------------------------------------ 行为面：坏值收口

test('坏掩码回默认_零是合法的全关不回默认', () => {
  // 非有限数 / 负数 → 出厂默认 15（SettingsStore.load 只在类型不符时按缺失处理，数值坏值走这里）
  assertEq(15, HudItems.maskOrDefault(NaN), 'NaN 回默认');
  assertEq(15, HudItems.maskOrDefault(-1), '负数回默认');
  assertEq(15, HudItems.maskOrDefault(-0.5), '负小数回默认');
  assertEq(15, HudItems.maskOrDefault(Infinity), '+Infinity 回默认');
  assertEq(15, HudItems.maskOrDefault(-Infinity), '-Infinity 回默认');
  // 0 是「全部取消」这一用户动作的合法存档：回默认会让它永远存不下来
  assertEq(0, HudItems.maskOrDefault(0), '0 保持全关（读数条整条不渲染）');
  // 正常值原样（小数向下取整对齐 prefs Int 语义）；高位未知 bit 前向兼容不裁
  assertEq(15, HudItems.maskOrDefault(15));
  assertEq(7, HudItems.maskOrDefault(7.9), '小数向下取整');
  assertEq(127, HudItems.maskOrDefault(127), '未知高位 bit 不裁（typesOf 只滤已知位）');
});

// ------------------------------------------------------------------ 行为面：多选编解码

test('isOn与toggle往返_开关不串位', () => {
  assertFalse(HudItems.isOn(0, HudItem.ZOOM), '空掩码全关');
  assertTrue(HudItems.isOn(15, HudItem.ISO), '默认档含感光度');
  // toggle 只动被点的那一位（设置页 chip 的唯一写入口）
  assertEq(8, HudItems.toggle(0, HudItem.ISO), '从全关开 ISO');
  assertEq(0, HudItems.toggle(8, HudItem.ISO), '再点一次关回去');
  assertEq(15, HudItems.toggle(31, HudItem.EV), '全 7 档关 EV 回默认档 15');
  assertEq(31, HudItems.toggle(15, HudItem.EV), '默认档 15 不含 EV，toggle 开它 → 31');
  // 往返恒等：任何掩码对任何一项开→关（或关→开）两次回到原值
  for (const item of HudItems.ALL) {
    const round: number = HudItems.toggle(HudItems.toggle(15, item), item);
    assertEq(15, round, `${HudItems.nameOf(item)} 双击往返`);
  }
});

// ------------------------------------------------------------------ 行为面：掩码 → 读数条分组桥
// （对拍上游 HudDefaultIsoTest：掩码经生产算式一路到「第几行第几列」，不在用例里手抄分组）

test('默认档读数条_枚举序三颗一行感光度第二行', () => {
  // ① 默认档 = 快门/帧率/码率/感光度（枚举序）；竖屏 360 实测宽的可用宽 340 → 换行档 3
  const defaults: Array<HudItem> = HudItems.typesOf(HudItems.DEFAULT_MASK);
  assertEq('SHUTTER,FPS,BITRATE,ISO', defaults.map((t: HudItem): string => HudItems.nameOf(t)).join(','),
    '默认四档枚举序');
  const perRow: number = hudPerRowFor(defaults.length, 1, hudRoomDp(360, HudSpec.HUD_EDGE_PAD));
  assertEq(3, perRow, '竖屏 360 → 3 颗一行');
  // ② 经 J2 的 hudRowGroups(READOUT) 切行：前三颗一行、感光度独占第二行
  const order: Array<HudEntry> = defaults.map((t: HudItem): HudEntry => HudEntry.ofItem(t));
  const groups: Array<Array<HudEntry>> = hudRowGroups(HudZone.READOUT, order, perRow);
  assertEq(2, groups.length, '四颗 3/行 → 2 行');
  assertEq('H0,H1,H2', groups[0].map((e: HudEntry): string => e.id).join(','), '第一行 快门/帧率/码率');
  assertEq('H3', groups[1].map((e: HudEntry): string => e.id).join(','), '第二行只有感光度');
  // ③ 老用户那一侧只钉「不改人设置」：存过的三档掩码解出来不补感光度
  const saved: number = HudItems.maskOf([HudItem.SHUTTER, HudItem.FPS, HudItem.BITRATE]);
  assertEq(7, saved);
  assertFalse(HudItems.isOn(saved, HudItem.ISO), '已存掩码的老用户不被补 ISO');
  assertEq(3, HudItems.typesOf(saved).length);
});

test('全七档_换行档不足整数的行不许有半颗', () => {
  const all: Array<HudItem> = HudItems.typesOf(127);
  assertEq(7, all.length, '全 7 档');
  const perRow: number = hudPerRowFor(all.length, 1, hudRoomDp(360, HudSpec.HUD_EDGE_PAD));
  assertEq(3, perRow);
  const groups: Array<Array<HudEntry>> = hudRowGroups(
    HudZone.READOUT, all.map((t: HudItem): HudEntry => HudEntry.ofItem(t)), perRow);
  assertEq(3, groups.length, '7 颗 3/行 → 3 行');
  assertEq(3, groups[0].length);
  assertEq(3, groups[1].length);
  assertEq(1, groups[2].length, '尾行只 1 颗（3+3+1，不造半颗）');
  // 极窄可用宽退 1 颗一行：7 行 × 1 颗，宁高不裁
  const narrow: number = hudPerRowFor(all.length, 1, hudRoomDp(60, HudSpec.HUD_EDGE_PAD));
  assertEq(1, narrow);
  assertEq(7, hudRowGroups(HudZone.READOUT,
    all.map((t: HudItem): HudEntry => HudEntry.ofItem(t)), narrow).length);
});

// ------------------------------------------------------------------ 去重红线（纯常量直读）

test('固定行不再含迁入读数条的键_保留键不动', () => {
  const fixed: Array<string> = HudSpec.HUD_ROW1.concat(HudSpec.HUD_ROW2);
  for (const key of ['shutter', 'iso', 'ev', 'wb', 'fps']) {
    assertTrue(fixed.indexOf(key) < 0, `固定行不得再含 ${key}（已迁 hud_items 读数条，同一读数两处即重复）`);
  }
  for (const key of ['lens', 'size', 'battery', 'free', 'duration']) {
    assertTrue(fixed.indexOf(key) >= 0, `固定行保留 ${key}（非 HudItem 集，不动）`);
  }
});

// ------------------------------------------------------------------ 接线守卫（源码扫描）

test('接线_设置页经HudItems函数写掩码_键为hud_items', () => {
  const store: string = sourceText('entry/src/main/ets/ui/SettingsStore.ets');
  assertTrue(store.indexOf("KEY_HUD_ITEMS: string = 'hud_items'") >= 0,
    'SettingsStore 的键常量必须逐字是 hud_items（上游 WotaSettings.KEY_HUD_ITEMS）');
  assertTrue(store.indexOf('HudItems.DEFAULT_MASK') >= 0, '缺键时的默认值必须取 HudItems.DEFAULT_MASK');
  assertTrue(store.indexOf('HudItems.maskOrDefault(hudRaw as number)') >= 0,
    '读值必须经 HudItems.maskOrDefault 收口坏值（非有限数/负数回默认）');
  assertTrue(store.indexOf('store.put(KEY_HUD_ITEMS, s.hudItems)') >= 0, 'save 必须写回 hud_items');

  const page: string = sourceText('entry/src/main/ets/pages/Settings.ets');
  assertTrue(page.indexOf('HudItems.toggle(this.settings.hudItems, item)') >= 0,
    'chip 点按必须经 HudItems.toggle 写掩码（不许手写 mask ^ bit）');
  assertTrue(page.indexOf('HudItems.isOn(this.settings.hudItems, item)') >= 0,
    'chip 选中态必须经 HudItems.isOn 判位');
  assertTrue(page.indexOf(' ^ ') < 0, '设置页不得出现手写异或位运算');
});

test('接线_相机页typesOf驱动读数条并消费J1J2行规划', () => {
  const cam: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertTrue(cam.indexOf('HudItems.typesOf(this.hudItemsMask)') >= 0,
    '读数条条目必须由 HudItems.typesOf(掩码) 驱动（枚举声明序）');
  assertTrue(cam.indexOf('hudPerRowFor(items.length, this.hudFontScale,') >= 0,
    '换行档必须走 J1 的 hudPerRowFor');
  assertTrue(cam.indexOf('hudRoomDp(this.hudStripWidthVp, HudSpec.HUD_EDGE_PAD)') >= 0,
    '可用宽必须经 hudRoomDp 同一表达式喂入（endPad 用 HudSpec.HUD_EDGE_PAD 同源）');
  assertTrue(cam.indexOf('hudRowGroups(HudZone.READOUT, order, perRow)') >= 0,
    '分组必须走 J2 的 hudRowGroups(READOUT)（与上游读数块同一条算式）');
  // 迁入读数条后读数不得出现两处：快门/ISO/EV/WB 的值仍只从 readoutText2 取（同一真源）
  assertTrue(cam.indexOf("this.readoutText2('shutter')") >= 0 &&
    cam.indexOf("this.readoutText2('wb')") >= 0, '读数条值必须复用既有读数函数');
});

test('接线_mask为零读数条整条不渲染的分支存在', () => {
  const cam: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertTrue(cam.indexOf('if (this.hudItemsMask !== 0)') >= 0,
    '掩码 0（设置页全部取消）必须有整条不渲染的分支');
  const store: string = sourceText('entry/src/main/ets/ui/SettingsStore.ets');
  // 0 存得下来是这条分支的前提：maskOrDefault 不许把 0 洗回默认（行为面用例已钉，这里钉链路两端）
  assertTrue(store.indexOf('hudItems: number = HudItems.DEFAULT_MASK') >= 0,
    '缺键/坏键回默认与 0 合法全关必须是两个分支（默认值只兜底缺键）');
});
