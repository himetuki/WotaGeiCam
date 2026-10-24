/**
 * 系统分享（上游 media/Share.kt 的 OHOS 对应物）：systemShare 系统分享面板。
 *
 * 与上游的差异（登记）：
 * - 上游 ACTION_SEND + FileProvider + FLAG_GRANT_READ；OHOS 分享面板自带目标应用授权，
 *   只需喂 `fileUri.getUriFromPath(沙箱路径)` 的 URI（无需 provider 白名单/手工授权位）；
 * - 单发/多发 = `SelectionMode.SINGLE / BATCH`（面板注释明确 File 类型 record 支持批量）；
 * - MIME 换成面板的类型语言 UTD（`utdOf`，见 media/MimeMap.ets）。
 *
 * 面板对沙箱 file:// URI 的目标应用可读性属真机待验项（调研报告 §2.6 第一条）：
 * 调用失败有日志与提示，不会静默。
 */
import { common } from '@kit.AbilityKit';
import { fileUri } from '@kit.CoreFileKit';
import { systemShare } from '@kit.ShareKit';
import { Diag } from '../diag/Diag';
import { VideoClip } from './MediaRepo';
import { utdOf } from './MimeMap';

const TAG: string = 'WotaShare';

export class WotaShare {
  /**
   * 拉起系统分享面板。返回 '' = 已拉起；非空 = 给用户看的失败原因（不抛）。
   * @param clips 要分享的片（组级动作扇出后应传整组成员）
   */
  static async share(ctx: common.UIAbilityContext, clips: Array<VideoClip>): Promise<string> {
    if (clips.length === 0) {
      return '没有可分享的视频';
    }
    try {
      const first: systemShare.SharedRecord = WotaShare.recordOf(clips[0]);
      const data: systemShare.SharedData = new systemShare.SharedData(first);
      for (let i = 1; i < clips.length; i++) {
        data.addRecord(WotaShare.recordOf(clips[i]));
      }
      const controller: systemShare.ShareController = new systemShare.ShareController(data);
      const options: systemShare.ShareControllerOptions = {
        selectionMode: clips.length > 1
          ? systemShare.SelectionMode.BATCH : systemShare.SelectionMode.SINGLE
      };
      await controller.show(ctx, options);
      return '';
    } catch (e) {
      Diag.e(TAG, `share failed: ${e}`);
      return '分享失败，请稍后重试';
    }
  }

  private static recordOf(clip: VideoClip): systemShare.SharedRecord {
    return {
      utd: utdOf(clip.name),
      uri: fileUri.getUriFromPath(clip.path),
      title: clip.name
    };
  }
}
