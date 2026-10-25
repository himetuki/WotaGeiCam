/**
 * 从系统相册**导入**视频到应用库（ALB 批，2026-10-25）。
 *
 * 上游对应面是 `MediaScope.AllVideos`（浏览全机视频）。本侧取等价且合规的形态——见
 * `core/ImportNaming` 头注：零媒体权限 + 沙箱模型下不管理别人的文件，改用**系统相册选择器**
 * （用户显式选择 = 临时授权读，零权限声明）把选中的视频**拷进应用库**，导入后即为本应用
 * 正常成片（改名/标签/系列/分享/同步全部既有能力自动可用）。
 *
 * 分层：命名与去重全在 `core/ImportNaming`（纯逻辑单一真源），本文件只做
 * 「拉起选择器 → 逐个拷贝 → 回报结果」。失败一律折算成计数与文案，绝不抛。
 */
import { common } from '@kit.AbilityKit';
import { fileIo, picker } from '@kit.CoreFileKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { Diag } from '../diag/Diag';
import { VideoStore } from '../record/VideoStore';
import { ImportNaming } from '../core/ImportNaming';

const TAG: string = 'WotaAlbumImport';

/** 单次导入的选择上限（避免一次拉进过多文件把库撑爆；用户可多次导入） */
const MAX_PICK: number = 20;

export class ImportResult {
  /** 成功导入条数 */
  imported: number;
  /** 失败条数（拷贝失败/命名耗尽/被跳过） */
  failed: number;
  /** 用户取消（未选任何文件） */
  cancelled: boolean;
  /** 失败原因摘要（最多列两条；空串 = 无失败） */
  detail: string;

  constructor(imported: number, failed: number, cancelled: boolean, detail: string) {
    this.imported = imported;
    this.failed = failed;
    this.cancelled = cancelled;
    this.detail = detail;
  }
}

export class AlbumImport {
  /**
   * 拉起系统相册选择器（只显示视频）并把选中项拷进 `filesDir/videos`。
   * `taken` = 库内现有文件名集合（去重依据，由调用方从当前列表算好传入，避免这里再扫一次盘）。
   */
  static async pickAndImport(ctx: common.Context, taken: Array<string>): Promise<ImportResult> {
    let uris: Array<string> = [];
    try {
      const options: photoAccessHelper.PhotoSelectOptions = new photoAccessHelper.PhotoSelectOptions();
      options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.VIDEO_TYPE;
      options.maxSelectNumber = MAX_PICK;
      const viewPicker: photoAccessHelper.PhotoViewPicker = new photoAccessHelper.PhotoViewPicker();
      const result: photoAccessHelper.PhotoSelectResult = await viewPicker.select(options);
      uris = result.photoUris ?? [];
    } catch (e) {
      Diag.w(TAG, `相册选择器失败: ${e}`);
      return new ImportResult(0, 0, false, `打开相册失败：${e}`);
    }
    if (uris.length === 0) {
      return new ImportResult(0, 0, true, '');
    }
    const used: Array<string> = taken.slice();
    let imported: number = 0;
    let failed: number = 0;
    let detail: string = '';
    for (const uri of uris) {
      const preferred: string = ImportNaming.preferredName(uri);
      const fallback: string = ImportNaming.stampName(Date.now());
      const name: string = ImportNaming.uniqueName(
        preferred === '' ? fallback : preferred, used);
      if (name === '') {
        failed++;
        detail = detail === '' ? '名字冲突过多，已跳过' : detail;
        continue;
      }
      const target: string = VideoStore.pathFor(ctx, name);
      const err: string = AlbumImport.copyOne(uri, target);
      if (err === '') {
        imported++;
        used.push(name);
      } else {
        failed++;
        if (detail === '') {
          detail = err;
        }
        Diag.w(TAG, `导入失败 ${uri} → ${name}: ${err}`);
      }
    }
    Diag.i(TAG, `相册导入完成 imported=${imported} failed=${failed} total=${uris.length}`);
    return new ImportResult(imported, failed, false, detail);
  }

  /** 单个拷贝：选择器 URI 仅在本次会话内临时可读，用 fd 打开后同步读干写新文件 */
  private static copyOne(uri: string, target: string): string {
    let src: fileIo.File | undefined = undefined;
    let dst: fileIo.File | undefined = undefined;
    try {
      src = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
      dst = fileIo.openSync(target,
        fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      const buf: ArrayBuffer = new ArrayBuffer(256 * 1024);
      while (true) {
        const n: number = fileIo.readSync(src.fd, buf);
        if (n <= 0) {
          break;
        }
        fileIo.writeSync(dst.fd, buf.slice(0, n));
      }
      return '';
    } catch (e) {
      // 半成品必须清掉：残留文件会在下次刷新时以 0 字节幽灵条目出现
      try {
        fileIo.unlinkSync(target);
      } catch (cleanup) {
        Diag.w(TAG, `导入失败后清理残留失败 ${target}: ${cleanup}`);
      }
      return `拷贝失败：${e}`;
    } finally {
      if (src !== undefined) {
        try {
          fileIo.closeSync(src);
        } catch (e) {
        }
      }
      if (dst !== undefined) {
        try {
          fileIo.closeSync(dst);
        } catch (e) {
        }
      }
    }
  }
}