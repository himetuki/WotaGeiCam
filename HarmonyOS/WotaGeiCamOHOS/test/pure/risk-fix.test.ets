/**
 * RISK 批（2026-10-25）：同步上游 f941799「高置信风险扫描修复七项」的鸿蒙落地守卫。
 *
 * 逐项对照（鸿蒙面）：
 * ① update 请求目标安全门槛 —— 已由 update-channel-speed.test.ets 的新判据用例覆盖；
 * ② SHA 无期望值短路（不白读全量包体）；
 * ③ 测速预算兜底（墙钟超时收口）——既有实现，本守卫钉存在性；
 * ④ 补弧离屏资源在解绑/销毁两条路都释放（native）；
 * ⑤ 帧率兜底探测——鸿蒙无阻塞探测（吃 clip 的 fpsHint）→ 登记 N/A；
 * ⑥ 剪辑取消的 sidecar —— 鸿蒙取消即删成片，无救援模型 → 登记 N/A；
 * ⑦ 删片抹同步记录（TrackSync.clearFor）。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { Source, SyncPrefs, TrackSync } from '../../entry/src/main/ets/player/TrackSync';

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

// ------------------------------------------------------------------ ⑦ clearFor 纯逻辑

class MapPrefs implements SyncPrefs {
  map: Map<string, string> = new Map<string, string>();
  getString(key: string, def: string): string {
    const v: string | undefined = this.map.get(key);
    return v === undefined ? def : v;
  }
  putString(key: string, value: string): void {
    this.map.set(key, value);
  }
}

test('clearFor_五键全清_读侧回到未同步', () => {
  const prefs: MapPrefs = new MapPrefs();
  const key: string = '/v/VID_1.mp4';
  TrackSync.writeSyncPrefs(prefs, key, 42, Source.MANUAL, 1.9, 1000);
  assertTrue(TrackSync.cachedSyncOf(prefs, key) !== undefined, '写入后应可见');

  TrackSync.clearFor(prefs, key);
  assertTrue(TrackSync.cachedSyncOf(prefs, key) === undefined, '清除后必须回到未同步');
  for (const k of TrackSync.keyNamesOf(key)) {
    assertEq(prefs.getString(k, ''), '', `键必须清空：${k}`);
  }
});

test('clearFor_只影响本段_别段记录不动', () => {
  const prefs: MapPrefs = new MapPrefs();
  const a: string = '/v/a.mp4';
  const b: string = '/v/b.mp4';
  TrackSync.writeSyncPrefs(prefs, a, 30, Source.MANUAL, 1.9, 1000);
  TrackSync.writeSyncPrefs(prefs, b, -12, Source.MANUAL, 1.9, 1000);
  TrackSync.clearFor(prefs, a);
  assertTrue(TrackSync.cachedSyncOf(prefs, a) === undefined, 'a 已清');
  const bb = TrackSync.cachedSyncOf(prefs, b);
  assertTrue(bb !== undefined, 'b 不受影响');
  assertEq(bb === undefined ? 0 : bb.offsetMs, -12, 'b 偏移保持');
});

test('clearFor_键全集与读写口径同源', () => {
  const keys: Array<string> = TrackSync.keyNamesOf('k');
  assertEq(keys.length, 5, '五键');
  assertEq(keys[0], TrackSync.doneKeyOf('k'));
  assertEq(keys[4], TrackSync.atKeyOf('k'));
});

// ------------------------------------------------------------------ 接线守卫（②③④⑦）

test('接线_SHA无期望值短路', () => {
  const dl: string = sourceText('entry/src/main/ets/update/UpdateDownloader.ets');
  assertTrue(dl.indexOf('const actual: string = expectedSha === undefined\n          ? \'\' : UpdateDownloader.sha256HexOfFile(target);') >= 0,
    '无期望哈希必须短路，不读全量包体');
  assertTrue(dl.indexOf('Diag.w(TAG, `更新包读取失败，无法校验') >= 0,
    '读盘异常必须在 sha256HexOfFile 内折成空串（不穿成未捕获异常）');
});

test('接线_测速预算兜底存在', () => {
  const dl: string = sourceText('entry/src/main/ets/update/UpdateDownloader.ets');
  assertTrue(dl.indexOf('setTimeout((): void => {\n        finish(0);\n      }, UpdateDownloadPure.SPEED_TEST_BUDGET_MS);') >= 0,
    '长时间无数据必须靠墙钟预算收口');
  assertTrue(dl.indexOf('Date.now() > deadline') >= 0, '读循环同时查 deadline');
});

test('接线_补弧离屏资源两条释放路', () => {
  const gl: string = sourceText('entry/src/main/cpp/gl_render.cpp');
  // 解绑编码面 + 销毁引擎（销毁内部转调 Detach）都必须释放 g_arcMend
  const detach: number = gl.indexOf('void WotaGlDetachEncoder()');
  assertTrue(detach >= 0, '找不到 Detach（守卫失去输入）');
  const detachBody: string = gl.substring(detach, gl.indexOf('bool WotaGlReady()', detach));
  assertTrue(detachBody.indexOf('g_arcMend->Release();') >= 0 && detachBody.indexOf('g_arcMend.reset();') >= 0,
    '解绑必须释放补弧离屏资源（5 张编码尺寸纹理）');
  const destroy: number = gl.indexOf('void WotaGlDestroy()');
  assertTrue(destroy >= 0);
  const destroyBody: string = gl.substring(destroy, destroy + 3000);
  assertTrue(destroyBody.indexOf('WotaGlDetachEncoder();') >= 0,
    '销毁路径必须转调 Detach（兜「没走解绑就销毁」那条路）');
});

test('接线_诊断分享与另存（DIAG-SHARE）', () => {
  const diag: string = sourceText('entry/src/main/ets/diag/Diag.ets');
  assertTrue(diag.indexOf('static async exportForShare(context: common.Context)') >= 0,
    '分享件必须落缓存目录（对齐上游 exportForShare）');
  assertTrue(diag.indexOf('`${context.cacheDir}/share-out`') >= 0, '落点 cacheDir/share-out');
  assertTrue(diag.indexOf('listFileSync(dir)') >= 0, '先清上次分享件（一次性语义）');
  assertTrue(diag.indexOf('static reportText(context: common.Context): string') >= 0,
    '另存路径要能拿报告正文');
  const sh: string = sourceText('entry/src/main/ets/diag/DiagShare.ets');
  assertTrue(sh.indexOf('fileUri.getUriFromPath(exported.path)') >= 0,
    '沙箱 URI 经 fileUri 直进分享面板（不需 FileProvider 等价物）');
  assertTrue(sh.indexOf('new systemShare.SharedData(') >= 0 &&
    sh.indexOf('new systemShare.ShareController(shareData)') >= 0, '分享走系统面板');
  assertTrue(sh.indexOf('picker.DocumentViewPicker') >= 0 &&
    sh.indexOf('save(options)') >= 0, '另存走系统保存选择器（用户选址=公共目录零权限等价）');
  const st: string = sourceText('entry/src/main/ets/pages/Settings.ets');
  assertTrue(st.indexOf('DiagShare.share(') >= 0 && st.indexOf('DiagShare.saveAs(') >= 0,
    '设置页两钮必须接上');
});

test('接线_删片抹同步记录', () => {
  const repo: string = sourceText('entry/src/main/ets/media/MediaRepo.ets');
  const del: number = repo.indexOf('async deleteForever(');
  assertTrue(del >= 0, '找不到 deleteForever');
  const body: string = repo.substring(del, del + 4000);
  assertTrue(body.indexOf('SyncPrefsStore.clearFor(this.ctx, clip.path)') >= 0,
    '删片收尾必须抹掉该路径键的同步记录（防同名复现命中旧偏移）');
  const store: string = sourceText('entry/src/main/ets/player/SyncPrefsStore.ets');
  assertTrue(store.indexOf('static async clearFor(ctx: common.Context, key: string)') >= 0,
    '平台门面必须有 clearFor');
  assertTrue(store.indexOf('TrackSync.clearFor(prefs, key)') >= 0, '门面必须走纯逻辑单一真源');
});
