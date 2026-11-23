/**
 * 问题反馈入口守卫（2026-11-23）：「WotaGeiCam issues」腾讯文档收集表——
 * 链接字面量、横屏右列/竖屏单列两处挂载都夹在 diagGroup 与 aboutGroup 之间、
 * 浏览器打开（本应用自身不联网）。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

function srcRoot(): string {
  let dir: string = __dirname;
  for (let i: number = 0; i < 6; i++) {
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

test('接线_反馈表单链接与浏览器打开', () => {
  const st: string = sourceText('entry/src/main/ets/pages/Settings.ets');
  assertTrue(st.indexOf('FEEDBACK_FORM_URL: string = \'https://docs.qq.com/form/page/DT2ZCckViZHJEdmh5\'') >= 0,
    '反馈表单链接必须是腾讯文档收集表原链（改链须同步三端与守卫）');
  assertTrue(st.indexOf('UpdateInstaller.openInBrowser(ctx, FEEDBACK_FORM_URL)') >= 0,
    '必须走系统浏览器打开（本应用自身不联网）');
});

test('接线_反馈区两处挂载都夹在诊断日志与检查更新之间', () => {
  const st: string = sourceText('entry/src/main/ets/pages/Settings.ets');
  const positions: number[] = [];
  let i: number = st.indexOf('this.feedbackGroup()');
  while (i >= 0) {
    positions.push(i);
    i = st.indexOf('this.feedbackGroup()', i + 1);
  }
  assertTrue(positions.length === 2, `横屏右列与竖屏单列各挂一次，实为 ${positions.length}`);
  for (const p of positions) {
    const diag: number = st.lastIndexOf('this.diagGroup()', p);
    const about: number = st.indexOf('this.aboutGroup()', p);
    assertTrue(diag >= 0 && diag < p && about > p, 'feedbackGroup 必须夹在 diagGroup 与 aboutGroup 之间');
  }
});
