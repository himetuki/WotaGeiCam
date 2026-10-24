/**
 * G3a 批（2026-10-24）：媒体库三列表形态纯逻辑（GalleryForms）+ 接线守卫。
 *
 * 直译上游 MediaTag.ListMode（默认 Grid）/ MediaRepo.groupTimeline（今天/昨天/
 * N 月 N 日/跨年补年）/ WotaSettings.galleryColumns（档位 [2,3,4,5] 缺 2）。
 * 日期用例全部经本地时区构造（new Date(y, m, d)）自洽，不依赖测试机时区。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { VideoClip } from '../../entry/src/main/ets/media/MediaRepo';
import {
  DEFAULT_LIST_MODE, GALLERY_COLUMN_TIERS, LIST_FULLSCREEN, LIST_GRID, LIST_TIMELINE, TimelineGroup,
  daysFromCivil, epochDayOf, galleryColumnsOf, groupTimeline
} from '../../entry/src/main/ets/media/GalleryForms';

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

function clip(name: string, addedSec: number): VideoClip {
  return new VideoClip(1, `/${name}.mp4`, `${name}.mp4`, '', addedSec, 1000, 5000, 1920, 1080,
    'video/mp4', new Set<string>(), false);
}

// ------------------------------------------------------------------ 形态与列数

test('形态_默认网格_上游逐字', () => {
  assertEq(DEFAULT_LIST_MODE, LIST_GRID);
  assertEq(LIST_GRID, 'grid');
  assertEq(LIST_TIMELINE, 'timeline');
});

test('列数_档位表与缺省校验', () => {
  assertEq(GALLERY_COLUMN_TIERS.join(','), '2,3,4,5');
  assertEq(galleryColumnsOf(2), 2);
  assertEq(galleryColumnsOf(5), 5);
  assertEq(galleryColumnsOf(1), 2, '档位外回 2（上游逐字）');
  assertEq(galleryColumnsOf(6), 2);
  assertEq(galleryColumnsOf(0), 2);
});

// ------------------------------------------------------------------ epochDay 纯算式

test('civil_纪元与已知锚点', () => {
  assertEq(daysFromCivil(1970, 1, 1), 0);
  assertEq(daysFromCivil(2000, 3, 1), 11017, 'Hinnant 文章锚点');
  assertEq(daysFromCivil(2026, 10, 24), 20750);
});

test('epochDay_本地日历日自洽', () => {
  const ms: number = new Date(2026, 9, 24, 23, 30).getTime();
  assertEq(epochDayOf(ms), 20750, '本地 23:30 仍属当日（不做 UTC 折算）');
});

// ------------------------------------------------------------------ 分组（groupTimeline 直译）

test('分组_今天昨天同年跨年四类文案', () => {
  const today: number = new Date(2026, 9, 24, 12).getTime();
  const sec: (d: Date) => number = (d: Date): number => Math.floor(d.getTime() / 1000);
  const clips: Array<VideoClip> = [
    clip('a', sec(new Date(2026, 9, 24, 8))),
    clip('b', sec(new Date(2026, 9, 23, 20))),
    clip('c', sec(new Date(2026, 8, 1))),
    clip('d', sec(new Date(2025, 11, 31)))
  ];
  const groups: Array<TimelineGroup> = groupTimeline(clips, today);
  assertEq(groups.length, 4, '四天四组');
  assertEq(groups[0].label, '今天');
  assertEq(groups[1].label, '昨天');
  assertEq(groups[2].label, '9 月 1 日');
  assertEq(groups[3].label, '2025 年 12 月 31 日', '跨年补年');
  assertEq(groups[0].clips.length, 1);
  assertEq(groups[0].clips[0].name, 'a.mp4', '输入序即组内序');
});

test('分组_同日合组_空表返回空', () => {
  const today: number = new Date(2026, 9, 24, 12).getTime();
  const s1: number = Math.floor(new Date(2026, 9, 24, 8).getTime() / 1000);
  const s2: number = Math.floor(new Date(2026, 9, 24, 22).getTime() / 1000);
  const groups: Array<TimelineGroup> =
    groupTimeline([clip('a', s1), clip('b', s2)], today);
  assertEq(groups.length, 1, '同日合组');
  assertEq(groups[0].clips.length, 2);
  assertEq(groupTimeline([], today).length, 0, '空表空组');
});

// ------------------------------------------------------------------ 接线守卫

test('接线_页面双形态与列数持久化', () => {
  const page: string = sourceText('entry/src/main/ets/pages/Gallery.ets');
  assertTrue(page.indexOf("@State listMode: string = DEFAULT_LIST_MODE") >= 0,
    '形态态必须默认网格（上游逐字）');
  assertTrue(page.indexOf('this.gridColumns = galleryColumnsOf(prefs.getInt(GALLERY_COLUMNS_KEY, 2))') >= 0,
    '列数必须经纯逻辑校验读取');
  assertTrue(page.indexOf('prefs.putInt(GALLERY_COLUMNS_KEY, next)') >= 0 &&
    page.indexOf('prefs.commit()') >= 0, '列数循环必须落盘');
  assertTrue(page.indexOf('.columnsTemplate(this.gridColumnsTemplate())') >= 0,
    '网格形态必须消费列数模板');
  assertTrue(page.indexOf('ForEach(this.timelineDays(), (day: TimelineDay)') >= 0,
    '时间线形态必须吃日分组');
  assertTrue(page.indexOf('this.toggleSeriesExpand(entry.seriesId)') >= 0 &&
    page.indexOf('entry.parts.slice(1)') >= 0,
    '时间线系列行必须内联展开（首段不重复出行）');
});

test('形态_全屏常量上游同序', () => {
  assertEq(LIST_FULLSCREEN, 'fullscreen', 'ListMode{Timeline,Grid,Fullscreen} 第三态');
});

test('接线_全屏滑动单引擎换代与拆解', () => {
  const page: string = sourceText('entry/src/main/ets/pages/Gallery.ets');
  // 仅当前页挂 XComponent（Swiper 预渲染邻页不挂，全局恒一枚表面）
  assertTrue(page.indexOf('if (idx === this.fullscreenIndex) {') >= 0 &&
    page.indexOf('XComponent({ type: XComponentType.SURFACE, controller: this.fsXc })') >= 0,
    'XComponent 必须被 当前页判定 门控');
  // 翻页 = 在途作废 + 引擎换代（release 后拒绝复活的引擎不复用）
  assertTrue(page.indexOf('this.fsAttachSeq++;') >= 0 &&
    page.indexOf('this.fsEngine.release();') >= 0 &&
    page.indexOf('this.fsEngine = new PlayerEngine();') >= 0,
    '翻页/拆解必须换代引擎');
  assertTrue(page.indexOf('.index(this.fullscreenIndex)') >= 0 &&
    page.indexOf('this.onFullscreenSwipe(idx);') >= 0, 'Swiper 受控 index + onChange');
  assertTrue(page.indexOf('engine.playPause(); // 新引擎恒不在播') >= 0,
    '挂载成功即起播（上游 softPause(false)）');
  // 离开全屏/页面销毁必须拆引擎停钟
  assertTrue(page.indexOf('private teardownFullscreen(): void {') >= 0 &&
    page.indexOf('this.teardownFullscreen();') >= 1, '拆解方法存在且被调用');
  const dispose: string = page.slice(page.indexOf('aboutToDisappear'));
  assertTrue(dispose.indexOf('this.teardownFullscreen();') >= 0,
    'aboutToDisappear 必须拆全屏（引擎/定时器不漏过页）');
  assertTrue(page.indexOf('Math.min(this.fullscreenIndex, this.clips.length - 1)') >= 0,
    '越界钳末位（回收站动作后列表变短）');
});

test('接线_动作菜单进bindSheet且动作即收', () => {
  const page: string = sourceText('entry/src/main/ets/pages/Gallery.ets');
  assertTrue(page.indexOf("this.sheetKind = 'menu';") >= 0, '菜单走 bindSheet（menu 分支）');
  assertTrue(page.indexOf('this.actionRow(this.menuClip)') >= 0,
    '菜单分支复用 actionRow（动作面零重复实现）');
  // 菜单打开时动作即收面板：7 个动作方法各一行收口
  assertEq((page.match(/if \(this\.sheetKind === 'menu'\) \{/g) ?? []).length, 9,
    '7 个动作方法 + builder 分支 + sheetTitle 的 menu 判定行');
  assertTrue(page.indexOf('.accessibilityText(\'操作菜单\')') >= 0,
    '网格 [⋯] 必须带读屏文本');
});
