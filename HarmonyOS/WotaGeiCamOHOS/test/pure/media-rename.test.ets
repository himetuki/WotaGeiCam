/**
 * RN 批（2026-10-25）：媒体改名——纯逻辑全表 + 迁移面接线守卫。
 *
 * 与上游的根因差异（见 core/RenameSpec 头注）：上游走 MediaStore DISPLAY_NAME、
 * `_ID` 不随名变；本侧 ID = 相对路径 FNV-1a，改名即换 ID，故必须成套迁移
 * （文件 / sidecar / 三表 / 同步键）。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { RenameSpec } from '../../entry/src/main/ets/core/RenameSpec';
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

// ------------------------------------------------------------------ 名字合法化

test('合法化_拒绝空与危险名', () => {
  assertEq(RenameSpec.sanitize(''), '');
  assertEq(RenameSpec.sanitize('   '), '');
  assertEq(RenameSpec.sanitize('.'), '');
  assertEq(RenameSpec.sanitize('..'), '');
  assertEq(RenameSpec.sanitize('a/b'), '', '路径分隔符必须拒（拼路径的这边不能裸用用户输入）');
  assertEq(RenameSpec.sanitize('a\\b'), '');
  assertEq(RenameSpec.sanitize('../evil'), '');
  assertEq(RenameSpec.sanitize('x'.repeat(101)), '', '超长拒');
  // 控制字符
  assertEq(RenameSpec.sanitize('a\u0001b'), '');
});

test('合法化_去首尾空白_保留常见字符', () => {
  assertEq(RenameSpec.sanitize('  我的片子  '), '我的片子');
  assertEq(RenameSpec.sanitize('take 01 - final'), 'take 01 - final');
  assertEq(RenameSpec.sanitize('a.b.c'), 'a.b.c');
  assertEq(RenameSpec.sanitize('x'.repeat(100)), 'x'.repeat(100), '恰好上限通过');
});

// ------------------------------------------------------------------ 后缀补齐（上游 ensureSuffix 逐字）

test('后缀_缺则补_已带不重复补_大小写不敏感', () => {
  assertEq(RenameSpec.ensureSuffix('我的片子', 'VID_1.mp4'), '我的片子.mp4');
  assertEq(RenameSpec.ensureSuffix('我的片子.mp4', 'VID_1.mp4'), '我的片子.mp4', '已带不重复');
  assertEq(RenameSpec.ensureSuffix('我的片子.MP4', 'VID_1.mp4'), '我的片子.MP4', '大小写不敏感');
  assertEq(RenameSpec.ensureSuffix('我的片子', 'noext'), '我的片子', '旧名无扩展名原样返回');
});

test('基名与扩展名_无扩展名与点开头', () => {
  assertEq(RenameSpec.stemOf('a.mp4'), 'a');
  assertEq(RenameSpec.extOf('a.mp4'), 'mp4');
  assertEq(RenameSpec.stemOf('noext'), 'noext');
  assertEq(RenameSpec.extOf('noext'), '');
  assertEq(RenameSpec.stemOf('.hidden'), '.hidden', '点开头视为无扩展名（dot>0 判据）');
});

// ------------------------------------------------------------------ 一次到位

test('resolve_合法化加补后缀_与原名相同则拒绝', () => {
  assertEq(RenameSpec.resolve('我的片子', 'VID_1.mp4'), '我的片子.mp4');
  assertEq(RenameSpec.resolve('  VID_1  ', 'VID_1.mp4'), '', '去掉空白后与原名相同 → 不动作');
  assertEq(RenameSpec.resolve('', 'VID_1.mp4'), '');
  assertEq(RenameSpec.resolve('a/b', 'VID_1.mp4'), '');
  assertEq(RenameSpec.resolve('VID_1.mp4', 'VID_1.mp4'), '', '逐字相同 → 不动作');
});

test('路径_同目录拼装', () => {
  assertEq(RenameSpec.newPathOf('/data/app/files/videos/VID_1.mp4', 'new.mp4'),
    '/data/app/files/videos/new.mp4');
  assertEq(RenameSpec.newPathOf('VID_1.mp4', 'new.mp4'), 'new.mp4', '无目录则原样');
  assertEq(RenameSpec.newRelativePathOf('videos/VID_1.mp4', 'new.mp4'), 'videos/new.mp4');
});

// ------------------------------------------------------------------ 同步键迁移

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

test('同步键_改名后随文件搬_旧键清空', () => {
  const prefs: MapPrefs = new MapPrefs();
  const oldKey: string = '/v/VID_1.mp4';
  const newKey: string = '/v/我的片子.mp4';
  TrackSync.writeSyncPrefs(prefs, oldKey, 42, Source.MANUAL, 1.9, 1000);

  TrackSync.renameFor(prefs, oldKey, newKey);
  const moved = TrackSync.cachedSyncOf(prefs, newKey);
  assertTrue(moved !== undefined, '新键必须能读到记录（不丢用户校准）');
  assertEq(moved === undefined ? 0 : moved.offsetMs, 42, '偏移随搬');
  assertTrue(TrackSync.cachedSyncOf(prefs, oldKey) === undefined, '旧键必须清空');
  for (const k of TrackSync.keyNamesOf(oldKey)) {
    assertEq(prefs.getString(k, ''), '', `旧键残留：${k}`);
  }
});

test('同步键_未校准段改名不留记录_同名键不动作', () => {
  const prefs: MapPrefs = new MapPrefs();
  TrackSync.renameFor(prefs, '/v/a.mp4', '/v/b.mp4');
  assertTrue(TrackSync.cachedSyncOf(prefs, '/v/b.mp4') === undefined, '无记录可搬');
  // same-key：不动
  TrackSync.writeSyncPrefs(prefs, '/v/c.mp4', 7, Source.MANUAL, 1.5, 1);
  TrackSync.renameFor(prefs, '/v/c.mp4', '/v/c.mp4');
  const same = TrackSync.cachedSyncOf(prefs, '/v/c.mp4');
  assertTrue(same !== undefined && same.offsetMs === 7, '同键改名不丢记录');
});

test('同步键_自动记录也随搬_来源保持', () => {
  const prefs: MapPrefs = new MapPrefs();
  TrackSync.writeSyncPrefs(prefs, 'old', -30, Source.AUTO, 2.4, 555);
  TrackSync.renameFor(prefs, 'old', 'new');
  const moved = TrackSync.cachedSyncOf(prefs, 'new');
  assertTrue(moved !== undefined);
  assertEq(moved === undefined ? '' : moved.source, Source.AUTO, '来源随搬（不被误降级为手动）');
});

// ------------------------------------------------------------------ 接线守卫

test('接线_仓库侧成套迁移', () => {
  const repo: string = sourceText('entry/src/main/ets/media/MediaRepo.ets');
  const at: number = repo.indexOf('async rename(clip: VideoClip, rawName: string)');
  assertTrue(at >= 0, 'MediaRepo.rename 必须存在');
  const body: string = repo.substring(at, at + 4200);
  assertTrue(body.indexOf('RenameSpec.resolve(rawName, clip.name)') >= 0, '名字合法化走单一真源');
  assertTrue(body.indexOf('fileIo.renameSync(clip.path, newPath)') >= 0, '磁盘文件改名');
  assertTrue(body.indexOf('ArcDropLog.renameFor(clip.path, newPath)') >= 0, 'sidecar 跟着搬');
  assertTrue(body.indexOf('db.renameMedia(clip.id, newId, clip.path, newPath,') >= 0, '三表迁移');
  assertTrue(body.indexOf('SyncPrefsStore.renameFor(this.ctx, clip.path, newPath)') >= 0,
    '同步键迁移');
  assertTrue(body.indexOf('revertRename') >= 0, '账迁移失败必须把文件改回去（不留半迁移态）');
  assertTrue(body.indexOf("fileIo.accessSync(newPath)") >= 0, '同名文件必须先拒');
  assertTrue(body.indexOf('getActiveRecordingPath()') >= 0, '录制中的文件必须拒');
});

test('接线_DB三表单事务迁移', () => {
  const db: string = sourceText('entry/src/main/ets/media/WotaDb.ets');
  const at: number = db.indexOf('async renameMedia(');
  assertTrue(at >= 0, 'renameMedia 必须存在');
  const body: string = db.substring(at, at + 1600);
  assertTrue(body.indexOf('UPDATE media_tag SET mediaId = ?, path = ?') >= 0, '标签表换键');
  assertTrue(body.indexOf('UPDATE trash_item SET mediaId = ?, uri = ?') >= 0, '回收站表换键');
  assertTrue(body.indexOf('UPDATE series_part SET mediaId = ?, uri = ?') >= 0, '系列表换键');
  assertTrue(body.indexOf('beginTransaction()') >= 0 && body.indexOf('commit()') >= 0 &&
    body.indexOf('rollBack()') >= 0, '三表必须单事务（中途失败不留半迁移账）');
});

test('接线_页面改名入口与弹层', () => {
  const gal: string = sourceText('entry/src/main/ets/pages/Gallery.ets');
  assertTrue(gal.indexOf("Button('改名')") >= 0, '卡片动作行必须有改名入口');
  assertTrue(gal.indexOf('this.openRenameDialog(clip)') >= 0);
  assertTrue(gal.indexOf("this.sheetKind = 'rename'") >= 0, '改名走 bindSheet 弹层（原生半模态）');
  assertTrue(gal.indexOf('await repo.rename(clip, input)') >= 0, '提交走仓库侧成套迁移');
  assertTrue(gal.indexOf("_a11y") < 0);
});