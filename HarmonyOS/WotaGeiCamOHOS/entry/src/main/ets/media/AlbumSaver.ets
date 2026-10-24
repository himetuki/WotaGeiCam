/**
 * 存入系统相册（上游 VideoStore 的 MediaStore 入库在 OHOS 的对应物）。
 *
 * 平台差异（登记，调研报告 §2.3/§2.4）：
 * - 上游 IS_PENDING 两段式（insert pending → 写完置 0）在 OHOS **没有对应问题域**：
 *   本仓成片先落应用沙箱再整文件入库，不存在「半成品行对媒体库可见」的窗口；
 * - `showAssetsCreationDialog`（since 12，**无权限**）：系统保存确认框，用户点确认即入库
 *   ——与上游「入库不打扰」有差异，所以做成 Gallery 里**显式动作**而不是录制收尾自动入库；
 * - 返回已授权的媒体库 uri 列表；用户取消按失败处理（返回提示「已取消」）。
 */
import { common } from '@kit.AbilityKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { Diag } from '../diag/Diag';
import { VideoClip } from './MediaRepo';
import { extOf, titleOf } from './MimeMap';

const TAG: string = 'WotaAlbum';

export class AlbumSaver {
  /**
   * 把沙箱成片存入系统相册（系统确认框）。返回 '' = 已入库；非空 = 给用户看的提示（不抛）。
   * @param clips 要入库的片（组级动作扇出后应传整组成员）
   */
  static async saveToGallery(ctx: common.Context, clips: Array<VideoClip>): Promise<string> {
    if (clips.length === 0) {
      return '没有可保存的视频';
    }
    try {
      const helper: photoAccessHelper.PhotoAccessHelper = photoAccessHelper.getPhotoAccessHelper(ctx);
      const srcUris: Array<string> = [];
      const configs: Array<photoAccessHelper.PhotoCreationConfig> = [];
      for (const clip of clips) {
        srcUris.push(`file://${clip.path}`);
        // 字段名以 SDK d.ts 为准：fileNameExtension（不是 extension）+ photoType 必填
        configs.push({
          title: titleOf(clip.name),
          fileNameExtension: extOf(clip.name),
          photoType: photoAccessHelper.PhotoType.VIDEO
        } as photoAccessHelper.PhotoCreationConfig);
      }
      const saved: Array<string> = await helper.showAssetsCreationDialog(srcUris, configs);
      if (saved.length === 0) {
        return '已取消保存';
      }
      Diag.i(TAG, `saved ${saved.length} asset(s) to gallery`);
      return '';
    } catch (e) {
      // 用户取消在部分版本走 reject：与「失败」分开报，不吓人
      Diag.w(TAG, `save to gallery failed: ${e}`);
      return '保存失败，请稍后重试';
    }
  }
}
