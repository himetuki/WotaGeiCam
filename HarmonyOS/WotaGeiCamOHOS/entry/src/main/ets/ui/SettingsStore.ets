/**
 * 相机默认设置持久化（@ohos.data.preferences；键独立于上游 ParamsPersist，登记差异）。
 * 只持久化 6 项最小设置：画幅/帧率/码率/光弧/网格/效果；表情/曲线等复杂参数不持久化。
 * 读取失败/类型异常回默认值；保存失败记日志不抛。
 */
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { Diag } from '../diag/Diag';

const STORE_NAME: string = 'wota_camera_settings';
const TAG: string = 'WotaSettings';

export class CameraSettings {
  sizeW: number = 1920;
  sizeH: number = 1080;
  fps: number = 30;
  /** 0 = 自动（BitratePolicy.resolve） */
  bitrate: number = 0;
  /** 1=MEND / 2=DROP（仅 24/25fps 生效） */
  arcMode: number = 1;
  grid: boolean = true;
  /** 0直通/1曲线/2斑马/3峰值 */
  effect: number = 0;
}

function asInt(value: number, fallback: number): number {
  return Number.isFinite(value) ? Math.floor(value) : fallback;
}

export class SettingsStore {
  static async load(ctx: common.Context): Promise<CameraSettings> {
    const s: CameraSettings = new CameraSettings();
    try {
      const store: preferences.Preferences = await preferences.getPreferences(ctx, STORE_NAME);
      s.sizeW = asInt(await store.get('sizeW', s.sizeW) as number, s.sizeW);
      s.sizeH = asInt(await store.get('sizeH', s.sizeH) as number, s.sizeH);
      s.fps = asInt(await store.get('fps', s.fps) as number, s.fps);
      s.bitrate = asInt(await store.get('bitrate', s.bitrate) as number, s.bitrate);
      s.arcMode = asInt(await store.get('arcMode', s.arcMode) as number, s.arcMode);
      const gridRaw: preferences.ValueType = await store.get('grid', s.grid);
      s.grid = typeof gridRaw === 'boolean' ? gridRaw as boolean : s.grid;
      s.effect = asInt(await store.get('effect', s.effect) as number, s.effect);
    } catch (e) {
      Diag.w(TAG, `load settings failed, use defaults: ${e}`);
      return new CameraSettings();
    }
    const sizeOk: boolean = (s.sizeW === 1920 && s.sizeH === 1080) ||
      (s.sizeW === 3840 && s.sizeH === 2160);
    if (!sizeOk) {
      s.sizeW = 1920;
      s.sizeH = 1080;
    }
    const bitrateOk: boolean = s.bitrate === 0 || s.bitrate === 5_000_000 || s.bitrate === 10_000_000 ||
      s.bitrate === 20_000_000 || s.bitrate === 35_000_000 || s.bitrate === 50_000_000;
    if (!bitrateOk) {
      s.bitrate = 0;
    }
    if (s.fps !== 24 && s.fps !== 25 && s.fps !== 30) {
      s.fps = 30;
    }
    if (s.arcMode !== 1 && s.arcMode !== 2) {
      s.arcMode = 1;
    }
    if (s.effect < 0 || s.effect > 3) {
      s.effect = 0;
    }
    return s;
  }

  static async save(ctx: common.Context, s: CameraSettings): Promise<boolean> {
    try {
      const store: preferences.Preferences = await preferences.getPreferences(ctx, STORE_NAME);
      await store.put('sizeW', s.sizeW);
      await store.put('sizeH', s.sizeH);
      await store.put('fps', s.fps);
      await store.put('bitrate', s.bitrate);
      await store.put('arcMode', s.arcMode);
      await store.put('grid', s.grid);
      await store.put('effect', s.effect);
      await store.flush();
      return true;
    } catch (e) {
      Diag.w(TAG, `save settings failed: ${e}`);
      return false;
    }
  }
}
