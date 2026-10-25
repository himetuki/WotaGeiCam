/**
 * DIAG 批（HM7-UI-02）：设置页诊断区块接线守卫——两开关（总开关/详细档置灰闸）、
 * 运行期立即生效、专用同步落盘、清除/导出动作与留白登记。
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

test('接线_诊断区块开关与运行期生效', () => {
  const st: string = sourceText('entry/src/main/ets/pages/Settings.ets');
  assertTrue(st.indexOf('Diag.setEnabled(on);') >= 0 && st.indexOf('Diag.setVerbose(on);') >= 0,
    '两开关必须运行期立即生效（上游 Diag.setEnabled 同口径）');
  assertTrue(st.indexOf('if (!on && this.settings.diagVerbose) {') >= 0,
    '总开关关闸必须级联关详细档');
  assertTrue(st.indexOf('.enabled(this.settings.diagEnabled)') >= 0,
    '详细档 Toggle 必须随总开关置灰（上游层级闸）');
  assertTrue(st.indexOf('SettingsStore.persistDiagFlags(ctx, this.settings.diagEnabled, this.settings.diagVerbose)') >= 0,
    '落盘必须走专用同步口（非通用 persist 批写）');
});

test('接线_清除与导出动作及留白登记', () => {
  const st: string = sourceText('entry/src/main/ets/pages/Settings.ets');
  assertTrue(st.indexOf('await Diag.clear();') >= 0 && st.indexOf('await Diag.export(ctx);') >= 0,
    '清除/导出必须消费 Diag 既有 API（不新造文件面）');
  assertTrue(st.indexOf("this.diagBusy = 'clear';") >= 0 && st.indexOf("this.diagBusy = 'export';") >= 0 &&
    st.indexOf(".enabled(this.diagBusy === '')") >= 0,
    '双动作必须互斥忙态（上游 busy 口径）');
  // DIAG-SHARE 批收口「公共目录/分享面板」留白：UI 注记改为「分享走系统面板，另存可自选位置」
  assertTrue(st.indexOf('分享走系统面板，另存可自选位置') >= 0,
    '分享/另存能力必须在 UI 注记（原留白已收口）');
  assertTrue(st.indexOf("公共 Documents 与分享面板为登记留白") < 0,
    '旧留白注记必须移除');
});
