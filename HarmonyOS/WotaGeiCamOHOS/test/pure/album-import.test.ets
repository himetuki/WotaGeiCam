/**
 * ALB 批（2026-10-25）：系统相册导入——命名去重纯逻辑全表 + 权限姿态与接线守卫。
 *
 * 形态说明（见 core/ImportNaming 头注）：上游 AllVideos 浏览全机视频（需读权限），
 * 本侧零媒体权限 + 沙箱模型不管理别人的文件，改用系统选择器（临时授权、零权限）导入。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { ImportNaming } from '../../entry/src/main/ets/core/ImportNaming';

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

test('URI_取尾段与百分号解码', () => {
  assertEq(ImportNaming.fileNameFromUri('file://media/Photo/12/VID_1.mp4'), 'VID_1.mp4');
  assertEq(ImportNaming.fileNameFromUri('file://media/Photo/12/VID_1.mp4?networkid=1'), 'VID_1.mp4');
  assertEq(ImportNaming.fileNameFromUri('file://media/Photo/12/%E6%88%91%E7%9A%84.mp4'), '我的.mp4');
  assertEq(ImportNaming.fileNameFromUri('VID_1.mp4'), 'VID_1.mp4', '无目录');
  assertEq(ImportNaming.fileNameFromUri('file://x/a.mp4#frag'), 'a.mp4');
  assertEq(ImportNaming.fileNameFromUri('file://x/%ZZ.mp4'), '%ZZ.mp4', '解码失败原样返回');
});

test('目标名_合法化与补扩展名', () => {
  assertEq(ImportNaming.preferredName('file://m/a.mp4'), 'a.mp4');
  assertEq(ImportNaming.preferredName('file://m/noext'), 'noext.mp4', '缺后缀补 mp4');
  assertEq(ImportNaming.preferredName('file://m/..'), '', '非法名（..）→ 空串，由调用方兜底');
  assertEq(ImportNaming.preferredName('file://m/'), '', '空尾段 → 空串');
});

test('兜底名_时间戳格式', () => {
  const n: string = ImportNaming.stampName(new Date(2026, 9, 25, 9, 8, 7).getTime());
  assertEq(n, 'IMP_20261025_090807.mp4');
});

test('撞名递增_基名与扩展名分别保留', () => {
  assertEq(ImportNaming.withOrdinal('a.mp4', 1), 'a.mp4', '首个不编号');
  assertEq(ImportNaming.withOrdinal('a.mp4', 2), 'a (2).mp4');
  assertEq(ImportNaming.withOrdinal('我的 片子.mp4', 3), '我的 片子 (3).mp4');
  assertEq(ImportNaming.withOrdinal('noext', 2), 'noext (2)', '无扩展名不加点');
});

test('去重_库内撞名递增_大小写不敏感', () => {
  assertEq(ImportNaming.uniqueName('a.mp4', []), 'a.mp4');
  assertEq(ImportNaming.uniqueName('a.mp4', ['a.mp4']), 'a (2).mp4');
  assertEq(ImportNaming.uniqueName('a.mp4', ['a.mp4', 'a (2).mp4']), 'a (3).mp4');
  assertEq(ImportNaming.uniqueName('a.mp4', ['A.MP4']), 'a (2).mp4',
    '大小写不敏感（部分文件系统会互相覆盖）');
  assertEq(ImportNaming.uniqueName('', ['x']), '', '空名直接失败');
});

test('去重_上限耗尽返回空串_不覆盖已有', () => {
  const taken: Array<string> = [];
  for (let i = 1; i <= ImportNaming.MAX_TRIES; i++) {
    taken.push(ImportNaming.withOrdinal('a.mp4', i));
  }
  assertEq(ImportNaming.uniqueName('a.mp4', taken), '', '用满即失败（绝不硬写覆盖）');
});

test('接线_零新增权限_选择器授权读', () => {
  // 应用权限清单：媒体导入**不得**引入读权限（MS 批红线「零新增权限」）
  const mod: string = sourceText('entry/src/main/module.json5');
  assertTrue(mod.indexOf('READ_IMAGEVIDEO') < 0 && mod.indexOf('WRITE_IMAGEVIDEO') < 0 &&
    mod.indexOf('READ_MEDIA') < 0, '不得声明媒体读权限（走选择器临时授权）');
  assertTrue(mod.indexOf('ohos.permission.CAMERA') >= 0, '相机权限在册（既有）');
  const imp: string = sourceText('entry/src/main/ets/media/AlbumImport.ets');
  assertTrue(imp.indexOf('photoAccessHelper.PhotoViewMIMETypes.VIDEO_TYPE') >= 0,
    '选择器必须过滤视频');
  assertTrue(imp.indexOf('maxSelectNumber = MAX_PICK') >= 0, '单次上限在册');
  assertTrue(imp.indexOf('fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY)') >= 0,
    '读走 fileIo 开 URI（d.ts：PhotoAsset.open 已废弃并指向 fileIo#open）');
  assertTrue(imp.indexOf('fileIo.unlinkSync(target)') >= 0,
    '失败必须清残留（0 字节幽灵条目防线）');
  assertTrue(imp.indexOf('ImportNaming.uniqueName(') >= 0, '命名去重走纯逻辑单一真源');
});

test('接线_媒体库导入入口', () => {
  const gal: string = sourceText('entry/src/main/ets/pages/Gallery.ets');
  assertTrue(gal.indexOf("Text('导入')") >= 0, '顶栏必须有导入入口');
  assertTrue(gal.indexOf('this.importFromAlbum()') >= 0);
  assertTrue(gal.indexOf('AlbumImport.pickAndImport(ctx, taken)') >= 0, '走导入件');
  assertTrue(gal.indexOf('await this.refreshAll(') >= 0, '导入后必须刷新列表');
});