/**
 * 媒体批（MS-1/2/3）的**接线守卫**：分享/缩略图/存相册三条链真的接到 Gallery，
 * 且平台件里的类型/映射不散写（MimeMap 单一真源）。
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

test('接线_分享链_MimeMap单一真源与面板调用', () => {
  const share: string = sourceText('entry/src/main/ets/media/WotaShare.ets');
  assertTrue(share.indexOf("from '@kit.ShareKit'") >= 0, '分享必须走 systemShare 系统面板（@kit.ShareKit）');
  assertTrue(share.indexOf('utdOf(clip.name)') >= 0, 'UTD 必须来自 MimeMap.utdOf（不许平台件手写类型串）');
  assertTrue(share.indexOf('fileUri.getUriFromPath(clip.path)') >= 0,
    '沙箱路径 → URI 必须经 fileUri.getUriFromPath');
  assertTrue(share.indexOf('systemShare.SelectionMode.BATCH') >= 0 &&
    share.indexOf('systemShare.SelectionMode.SINGLE') >= 0,
    '单发/多发必须映射 SINGLE/BATCH');
  assertTrue(share.indexOf("'video/") < 0 && share.indexOf("'general.") < 0,
    '平台件里不许出现手写 MIME/UTD 字面量（MimeMap 单一真源）');

  const map: string = sourceText('entry/src/main/ets/media/MimeMap.ets');
  assertTrue(map.indexOf('@ohos') < 0 && map.indexOf('@kit') < 0, 'MimeMap 必须零平台 import（纯逻辑）');
});

test('接线_缩略图链_AVImageGenerator与缓存', () => {
  const thumb: string = sourceText('entry/src/main/ets/media/ThumbLoader.ets');
  assertTrue(thumb.indexOf('createAVImageGenerator()') >= 0, '必须走 AVImageGenerator（since 12，兼容面内）');
  assertTrue(thumb.indexOf('AV_IMAGE_QUERY_CLOSEST') >= 0, '取帧模式 = CLOSEST（上游 OPTION_CLOSEST 同义）');
  assertTrue(thumb.indexOf('Math.floor(durationMs / 2) * 1000') >= 0, '取中点帧（时长未知退 0 = 首帧）');
  assertTrue(thumb.indexOf('MAX_CONCURRENT: number = 2') >= 0, '并发限 2（上游 Semaphore(2)）');
  assertTrue(thumb.indexOf('ThumbLru') >= 0, '缓存必须走 ThumbLru（驱逐序有单测）');
  assertTrue(thumb.indexOf('closeSync(fd)') >= 0, 'fd 必须关（finally 收口）');

  const lru: string = sourceText('entry/src/main/ets/media/ThumbLru.ets');
  assertTrue(lru.indexOf('@ohos') < 0 && lru.indexOf('@kit') < 0, 'ThumbLru 必须零平台 import');
});

test('接线_存相册链_系统确认框', () => {
  const saver: string = sourceText('entry/src/main/ets/media/AlbumSaver.ets');
  assertTrue(saver.indexOf('showAssetsCreationDialog') >= 0,
    '入库必须走 showAssetsCreationDialog（since 12、无权限、用户确认形态）');
  assertTrue(saver.indexOf('titleOf(clip.name)') >= 0 && saver.indexOf('extOf(clip.name)') >= 0,
    'title/extension 必须来自 MimeMap（title 去扩展名防双后缀）');
  assertTrue(saver.indexOf('createAsset') < 0,
    '不得走 PhotoAccessHelper.createAsset（本 SDK 标注 since 22，超 6.0(20) 兼容面）');

  // 入库是显式动作：不挂在录制收尾自动入库（用户裁决的交互形态）
  const camera: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertTrue(camera.indexOf('AlbumSaver') < 0, '录制收尾不得自动入库（Gallery 显式动作，登记）');
});

test('接线_Gallery三条链都有入口且组级扇出', () => {
  const gallery: string = sourceText('entry/src/main/ets/pages/Gallery.ets');
  assertTrue(gallery.indexOf('this.shareClip(clip)') >= 0, '卡片动作行必须有「分享」');
  assertTrue(gallery.indexOf('this.saveClip(clip)') >= 0, '卡片动作行必须有「存入相册」');
  assertTrue(gallery.indexOf('GalleryThumb({') >= 0, '卡片必须用 GalleryThumb（缩略图 + 占位回退）');
  // 组级扇出：分享/存相册与收藏/回收站同一条纪律（expandSeriesTargets）
  assertTrue(gallery.indexOf('WotaShare.share(ctx as common.UIAbilityContext, targets)') >= 0,
    '分享必须吃扇出后的整组成员');
  assertTrue(gallery.indexOf('AlbumSaver.saveToGallery(ctx, targets)') >= 0,
    '存相册必须吃扇出后的整组成员');
  // 缩略图重载：@Prop path 变化必须挂 @Watch（aboutToAppear 不因 @Prop 更新重跑）
  assertTrue(gallery.indexOf("@Watch('onPathChange') path") >= 0,
    'GalleryThumb.path 必须挂 @Watch 重载（列表复用不串图）');

  // 零新增权限红线（分享/缩略图/入库全程无 requestPermissions 变更）
  const manifest: string = readFileSync(resolve(srcRoot(), 'entry/src/main/module.json5'), 'utf8');
  assertTrue(manifest.indexOf('READ_IMAGEVIDEO') < 0 && manifest.indexOf('WRITE_IMAGEVIDEO') < 0,
    '不得声明媒体库读写权限（沙箱 + 系统确认框路线零权限）');
});

// ------------------------------------------------------------------ GX 批（分段展开 + 折叠开关 + 重命名决策）

test('接线_折叠开关与会话态_长按组卡开分段清单', () => {
  const gallery: string = sourceText('entry/src/main/ets/pages/Gallery.ets');
  assertTrue(gallery.indexOf('galleryEntries(this.clips, this.seriesCollapsed)') >= 0,
    '列表必须消费折叠开关（上游 collapse 旋钮的会话态对应物）');
  assertTrue(gallery.indexOf('LongPressGesture') >= 0 &&
    gallery.indexOf('this.openSeriesSheet(entry)') >= 0,
    '组卡长按必须打开分段清单（上游 SeriesSheet 的对应交互）');
  assertTrue(gallery.indexOf('seriesSheetId: number | undefined') >= 0 &&
    gallery.indexOf('this.seriesSheetParts()') >= 0,
    '面板只钉 seriesId、按当前 clips 重解析（上游同款：不吃快照）');
  assertTrue(gallery.indexOf('this.openPlayer(part)') >= 0, '清单点按直接开该段播放页');
});

test('裁决_重命名已迁_改判自「不迁」并保留 id 事实', () => {
  // 历史：本仓媒体 ID = 相对路径 32 位 FNV-1a，改名即换 id → 曾裁决「重命名不迁」。
  // RN 批（2026-10-25）改判：不改 id 架构，而是把**改名做成成套迁移**（文件 → sidecar →
  // 三表事务 → 同步键），迁移面在册即可安全改名。id 仍是路径派生这一事实保留在册
  // （它是迁移必要性的依据，也是「改名后 CompareHistory 旧条目失效」取舍的来源）。
  const repo: string = sourceText('entry/src/main/ets/media/MediaRepo.ets');
  assertTrue(repo.indexOf('static ofRelativePath(rel: string): number') >= 0,
    'id = 相对路径哈希的实现面（MediaIds.ofRelativePath）');
  assertTrue(repo.indexOf('0x01000193') >= 0, '32 位 FNV-1a 质数（路径即身份的事实依据）');
  const gallery: string = sourceText('entry/src/main/ets/pages/Gallery.ets');
  assertTrue(gallery.indexOf("Button('改名')") >= 0,
    '改名入口已在册（RN 批落地，取代旧的「不许出现重命名入口」钉子）');
  assertTrue(repo.indexOf('await db.renameMedia(') >= 0, '入口存在的前提是迁移面在册');
});
