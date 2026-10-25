/**
 * VIS 批（2026-10-25）：WIKO 真机安卓版界面走查后的视觉对齐守卫——
 * 仿照安卓版信息架构、以鸿蒙视觉语言（令牌胶囊/Tabs 指示条/描边圆钮）承载。
 * 参考截图：baseline/wiko-ref/（本地资产，不入库）。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

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

test('媒体库页签_Tabs形态_指示条与强调字', () => {
  const gal: string = sourceText('entry/src/main/ets/pages/Gallery.ets');
  assertTrue(gal.indexOf('PageColor.ACCENT_BLUE : PageColor.TEXT_FAINT') >= 0,
    '选中页签强调字（未选弱化）');
  assertTrue(gal.indexOf('.backgroundColor(this.selectedTab === index ? PageColor.ACCENT_BLUE : Color.Transparent)') >= 0,
    '下划线指示条随选中切换');
  assertTrue(gal.indexOf(".backgroundColor(this.selectedTab === index ? '#2E7D32'") < 0,
    '旧绿底页签按钮必须移除');
});

test('媒体库顶栏_×圆钮与工具胶囊', () => {
  const gal: string = sourceText('entry/src/main/ets/pages/Gallery.ets');
  assertTrue(gal.indexOf(".width(36)\n          .height(36)\n          .borderRadius(18)\n          .backgroundColor(PageColor.CHIP)") >= 0,
    '关闭必须为圆形钮（对齐安卓右上圆钮）');
  assertTrue(gal.indexOf(".borderRadius(15)\n            .backgroundColor(PageColor.CHIP)") >= 0,
    '顶栏工具必须胶囊化（CHIP 令牌）');
  assertTrue(gal.indexOf("Button('返回')") < 0 && gal.indexOf("Button('刷新')") < 0,
    '裸文本按钮必须移除');
});

test('取景页_右上双圆钮', () => {
  const cam: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertTrue(cam.indexOf(".width(40)\n              .height(40)\n              .borderRadius(20)\n              .backgroundColor(HudColor.PILL_BG)\n              .border({ width: 1, color: HudColor.DIVIDER })") >= 0,
    '设置/媒体库入口必须为 40vp 描边圆钮（对齐安卓右上双圆钮）');
  const round: number = cam.split('.borderRadius(20)').length - 1;
  assertTrue(round >= 2, '双圆钮成对存在');
});

test('更多面板_曲线编辑可见入口', () => {
  const sheet: string = sourceText('entry/src/main/ets/ui/HudMoreSheet.ets');
  assertTrue(sheet.indexOf("Text('编辑')") >= 0 &&
    sheet.indexOf('this.onEditCurve();') >= 2,
    '曲线编辑必须有可见点按入口（点按直开与长按并存）');
});
