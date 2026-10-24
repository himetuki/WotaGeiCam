/**
 * CT 批（自定义标签页）：CustomTags 派生纯算式全表 + 接线守卫。
 *
 * 派生口径（上游 MediaRepo.customTags）：media_tag 行 → 过滤系统保留 tag → 按 createdAt
 * 升序 → 去重。页签顺序 = 首次使用时间（稳定，不随刷新跳位）。
 * normalize 合法化（去空白/≤24 字/不含保留词）已有旧档覆盖（clip-rule/tag 相关），本文件
 * 钉派生与守卫的接线。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { CustomTagRow, CustomTags, TagSpace } from '../../entry/src/main/ets/media/MediaTag';

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

function row(tag: string, createdAt: number): CustomTagRow {
  return new CustomTagRow(tag, createdAt);
}

// ------------------------------------------------------------------ 派生全表

test('派生_按createdAt升序_系统tag过滤_去重', () => {
  const rows: Array<CustomTagRow> = [
    row('风景', 300), row('like', 100), row('人像', 100),
    row('风景', 200), row('trash', 400), row('美食', 150)
  ];
  const tags: Array<string> = CustomTags.ofRows(rows);
  // like/trash 系统保留过滤；风景重复去重（保留最早出现）；按 createdAt 升序：人像(100)→美食(150)→风景(300)
  assertEq('人像,美食,风景', tags.join(','), '排序+过滤+去重');
});

test('派生_空表返回空', () => {
  assertEq(0, CustomTags.ofRows([]).length);
});

// ------------------------------------------------------------------ 接线守卫

test('接线_Gallery消费派生页签与标签弹层', () => {
  const gallery: string = sourceText('entry/src/main/ets/pages/Gallery.ets');
  assertTrue(gallery.indexOf('this.customTags = await repo.customTags();') >= 0,
    'Gallery 必须从 repo 派生自定义标签');
  assertTrue(gallery.indexOf('this.refreshTabs();') >= 0, '页签表必须随自定义标签刷新');
  assertTrue(gallery.indexOf("GalleryTabs.clampedSelection(this.tabs, this.selectedTab") >= 0,
    '自定义页签缩后必须矫正选中态（上游 clampedSelection 调用点）');
  assertTrue(gallery.indexOf('this.openTagDialog(clip);') >= 0, '卡片必须有标签弹层入口');
  assertTrue(gallery.indexOf('this.addTagToClip(clip, input)') >= 0 &&
    gallery.indexOf('this.removeTagFromClip(tag);') >= 0,
    '弹层必须接加/移除两条变异');
  assertTrue(gallery.indexOf('TagSpace.isCustom(t)') >= 0,
    '弹层清单必须过滤系统保留 tag');
});

test('接线_三个上游键名逐字', () => {
  // 上游 WotaSettings/MediaActions 对应键与函数名（本侧等价面）
  const repo: string = sourceText('entry/src/main/ets/media/MediaRepo.ets');
  assertTrue(repo.indexOf('TagSpace.normalize(rawTag)') >= 0,
    'addTag 必须经 TagSpace.normalize 守卫（去空白/≤24 字/保留词）');
  assertTrue(repo.indexOf('db.insertTag(clip.id, clip.relativePath, tag, Date.now())') >= 0,
    'insertTag 必须带 createdAt（派生排序依据）');
  assertTrue(repo.indexOf('CustomTags.ofRows(ctRows)') >= 0,
    'customTags 必须经 CustomTags.ofRows 派生（不许手写过滤）');
});

// ------------------------------------------------------------------ HM7-1：半模态面板接线守卫

test('接线_半模态bindSheet消费SheetSpec纪律', () => {
  const gallery: string = sourceText('entry/src/main/ets/pages/Gallery.ets');
  // 半模态必须走鸿蒙原生 bindSheet（系统面板形态；不用安卓式自绘 Column 假面板）
  assertTrue(gallery.indexOf('.bindSheet(this.sheetKind !== \'none\', (): void => {') >= 0,
    '必须用原生 bindSheet（CustomBuilder + SheetOptions）');
  assertTrue(gallery.indexOf('height: SheetSize.MEDIUM') >= 0, '高度 = SheetSize.MEDIUM（屏半高档）');
  assertTrue(gallery.indexOf('dragBar: true') >= 0, 'dragBar 必须开（系统半模态规范）');
  assertTrue(gallery.indexOf('showClose: true') >= 0, 'showClose 必须开');
  // 深浅色：深色底显式（本 app 深色 only，EntryAbility 强制 COLOR_MODE_DARK）；
  // HM7-UI-02 令牌统一后走 PageColor.PANEL（值 = #263238，ui-tokens 守卫钉字面）。
  assertTrue(gallery.indexOf('backgroundColor: PageColor.PANEL') >= 0, '深色底显式声明（语义令牌）');
  // 无障碍：sheet 内可交互元素带 accessibilityText（读屏可念出段序号/标签名）
  assertTrue(gallery.indexOf('.accessibilityText(`第 ${idx + 1} 段，${part.name}') >= 0,
    '分段行必须带 accessibilityText（读屏念段名）');
  assertTrue(gallery.indexOf('.accessibilityText(`移除标签 ${tag}`)') >= 0,
    '移除按钮必须带 accessibilityText（读屏念标签名）');
  // 旧内嵌弹层已迁走：不得残留 60% 高度的自绘假面板
  assertTrue(gallery.indexOf(".height('60%')") < 0, '旧自绘 60% 面板必须移除（HM7-1 迁移）');
});
