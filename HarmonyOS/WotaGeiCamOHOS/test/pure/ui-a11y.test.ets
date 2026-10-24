/**
 * HM7-UI-02 无障碍批：图形化/无文本交互控件的无障碍语义接线守卫。
 * 文本按钮（Button('文案')）读屏原生可读，不在此列；此处钉的是
 * XComponent 播放面（手势语义）、Slider（进度语义）、图形快门、Toggle（旁挂标签）。
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

test('取景页_快门与画面语义', () => {
  const cam: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertTrue(cam.indexOf("this.recording ? '停止录制' : '开始录制'") >= 0,
    '图形快门必须随录制态切换读屏文本');
  assertTrue(cam.indexOf(".accessibilityText('取景画面')") >= 0 &&
    cam.indexOf("'点按对焦，长按锁定对焦'") >= 0,
    '取景面必须带手势语义描述');
});

test('播放页_画面与进度语义', () => {
  const pl: string = sourceText('entry/src/main/ets/pages/Player.ets');
  assertTrue(pl.indexOf(".accessibilityText('播放画面')") >= 0 &&
    pl.indexOf("'单击显示或隐藏控制，双击播放或暂停，双指缩放平移'") >= 0,
    '播放面必须带手势语义描述');
  assertTrue(pl.indexOf(".accessibilityText('播放进度')") >= 0, '进度条必须带语义');
});

test('对比页_双面与时间线语义', () => {
  const cp: string = sourceText('entry/src/main/ets/pages/Compare.ets');
  assertTrue(cp.indexOf(".accessibilityText('左片画面')") >= 0 &&
    cp.indexOf(".accessibilityText('右片画面')") >= 0, '双画面必须可区分');
  assertTrue(cp.indexOf(".accessibilityText('对比时间线进度')") >= 0, '时间线必须带语义');
});

test('媒体库_全屏面_折叠开关_缩略语义', () => {
  const gal: string = sourceText('entry/src/main/ets/pages/Gallery.ets');
  assertTrue(gal.indexOf(".accessibilityText('全屏浏览画面')") >= 0 &&
    gal.indexOf("'左右滑动切换视频，控制见底部控制条'") >= 0,
    '全屏面必须带滑动语义');
  assertTrue(gal.indexOf(".accessibilityText('分段合并')") >= 0,
    '分段合并 Toggle 旁挂标签必须补语义');
  assertTrue(gal.indexOf('打开播放页：${entry.title()}') >= 0,
    '时间线缩略必须带动作与片名语义');
});
