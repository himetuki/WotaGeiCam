/**
 * 诊断日志的分享/另存（DIAG-SHARE 批，2026-10-25）。
 *
 * 对齐上游 `SettingsScreen.shareLogFile` + `Diag.exportForShare`：
 * - **分享**：报告写缓存目录（`Diag.exportForShare`）再交系统分享面板（systemShare）——
 *   沙箱 URI 经 `fileUri.getUriFromPath` 直进面板，**不需要** FileProvider 等价物
 *   （面板自带目标应用授权，与 MS 批 `WotaShare` 同一套口径）。全程不联网。
 * - **另存**：报告文本交 `DocumentViewPicker.save`，用户自选位置——这是上游「写公共
 *   Documents」在 OHOS 沙箱模型下的零权限等价路径（用户显式选址，系统落位）。
 *
 * 分层纪律：报告生成与写盘点全在 `diag/Diag.ets`（单一真源），本文件只做系统面板接线。
 * 失败一律返回可读文案，绝不抛。
 */
import { common } from '@kit.AbilityKit';
import { fileIo, fileUri, picker } from '@kit.CoreFileKit';
import { systemShare } from '@kit.ShareKit';
import { Diag, ExportResult } from './Diag';

const TAG: string = 'WotaDiagShare';

/** 分享面板用的纯文本 UTD（诊断件是 txt；UTD 是字符串契约，见 MimeMap 同款口径） */
const TEXT_UTD: string = 'general.plain-text';

export class DiagShare {
  /**
   * 一键分享：导出到缓存目录 → 拉起系统分享面板。返回空串 = 成功，非空 = 失败文案
   * （成功不代表用户选好了目标应用——面板取消属正常路径）。
   */
  static async share(ctx: common.UIAbilityContext): Promise<string> {
    const exported: ExportResult | null = await Diag.exportForShare(ctx);
    if (exported === null) {
      return '日志导出失败';
    }
    try {
      const uri: string = fileUri.getUriFromPath(exported.path);
      const shareData: systemShare.SharedData = new systemShare.SharedData({
        utd: TEXT_UTD,
        uri: uri,
        title: 'WotaGeiCam 诊断日志',
        description: '应用日志（不含网络上传）'
      });
      const controller: systemShare.ShareController = new systemShare.ShareController(shareData);
      await controller.show(ctx, {
        previewMode: systemShare.SharePreviewMode.DETAIL,
        selectionMode: systemShare.SelectionMode.SINGLE
      });
      Diag.i(TAG, `日志分享面板已拉起 uri=${uri} bytes=${exported.bytes}`);
      return '';
    } catch (e) {
      Diag.w(TAG, `拉起分享面板失败: ${e}`);
      return `拉起分享面板失败：${e}`;
    }
  }

  /**
   * 另存为：报告文本交系统保存选择器（用户选位置）。返回空串 = 已保存，
   * 非空 = 失败文案；用户取消返回「已取消」。
   */
  static async saveAs(ctx: common.UIAbilityContext): Promise<string> {
    const text: string = Diag.reportText(ctx);
    if (text === '') {
      return '报告生成失败';
    }
    let uri: string = '';
    try {
      const options: picker.DocumentSaveOptions = new picker.DocumentSaveOptions();
      options.newFileNames = [Diag.exportName()];
      const savePicker: picker.DocumentViewPicker = new picker.DocumentViewPicker(ctx);
      const uris: Array<string> = await savePicker.save(options);
      if (uris.length === 0) {
        return '已取消';
      }
      uri = uris[0];
    } catch (e) {
      Diag.w(TAG, `保存选择器失败: ${e}`);
      return `保存选择器失败：${e}`;
    }
    try {
      const file = fileIo.openSync(uri, fileIo.OpenMode.READ_WRITE);
      try {
        fileIo.writeSync(file.fd, text);
      } finally {
        fileIo.closeSync(file);
      }
      Diag.i(TAG, `日志已另存 uri=${uri} bytes=${text.length}`);
      return '';
    } catch (e) {
      Diag.w(TAG, `写入选定位置失败: ${e}`);
      return `写入失败：${e}`;
    }
  }
}
