/**
 * 相机默认设置持久化（@ohos.data.preferences；键独立于上游 ParamsPersist，登记差异）。
 * 只持久化最小设置：画幅/帧率/码率/光弧/网格/效果 + 滑杆吸附开关（slider_continuous，
 * 对齐上游 0dee86d KEY_SLIDER_CONTINUOUS 语义：存「关掉吸附」的参数 id 集合编码串，
 * 缺键/坏串 = 空集 = 全部吸附）；表情/曲线等复杂参数不持久化。
 * 读取失败/类型异常回默认值；保存失败记日志不抛。
 */
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { Diag } from '../diag/Diag';
import { SliderKey, decodeContinuousSliders } from './SliderSnap';

const STORE_NAME: string = 'wota_camera_settings';
const TAG: string = 'WotaSettings';
/** 滑杆吸附开关的 prefs 键（编码串见 ui/SliderSnap.ets；缺键 = 全部吸附） */
const KEY_SLIDER_CONTINUOUS: string = 'slider_continuous';
/** 取景辅助：水平仪/俯仰仪显示与回正震动（键名与上游 WotaSettings 逐字一致） */
const KEY_LEVEL_ENABLED: string = 'level_enabled';
const KEY_LEVEL_BUZZ: string = 'level_buzz_enabled';
/** 默认取景参考线位掩码（键名与上游 WotaSettings 逐字一致） */
const KEY_DEFAULT_REF_LINES: string = 'default_ref_lines';

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
  /** 关掉刻度吸附（改连续值）的滑杆集合编码串；空串 = 全部吸附 */
  sliderContinuous: string = '';
  /** 取景辅助：显示水平仪与俯仰仪（上游 level_enabled，默认开） */
  levelEnabled: boolean = true;
  /** 水平仪/俯仰仪过 0° 时震动（上游 level_buzz_enabled，默认开） */
  levelBuzz: boolean = true;
  /** 默认取景参考线位掩码（上游 default_ref_lines，11 档位或；0 = 不开任何参考线） */
  refLines: number = 0;
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
      // 坏串原样保留，读取口逐次 decode（空/坏 = 空集 = 全部吸附），与上游语义一致
      const sliderRaw: preferences.ValueType = await store.get(KEY_SLIDER_CONTINUOUS, s.sliderContinuous);
      s.sliderContinuous = typeof sliderRaw === 'string' ? sliderRaw as string : '';
      const levelRaw: preferences.ValueType = await store.get(KEY_LEVEL_ENABLED, s.levelEnabled);
      s.levelEnabled = typeof levelRaw === 'boolean' ? levelRaw as boolean : s.levelEnabled;
      const buzzRaw: preferences.ValueType = await store.get(KEY_LEVEL_BUZZ, s.levelBuzz);
      s.levelBuzz = typeof buzzRaw === 'boolean' ? buzzRaw as boolean : s.levelBuzz;
      const refRaw: preferences.ValueType = await store.get(KEY_DEFAULT_REF_LINES, s.refLines);
      s.refLines = typeof refRaw === 'number' && Number.isFinite(refRaw as number)
        ? Math.trunc(refRaw as number) : 0;
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
      await store.put(KEY_SLIDER_CONTINUOUS, s.sliderContinuous);
      await store.put(KEY_LEVEL_ENABLED, s.levelEnabled);
      await store.put(KEY_LEVEL_BUZZ, s.levelBuzz);
      await store.put(KEY_DEFAULT_REF_LINES, s.refLines);
      await store.flush();
      return true;
    } catch (e) {
      Diag.w(TAG, `save settings failed: ${e}`);
      return false;
    }
  }

  /**
   * 关掉刻度吸附（改连续值）的滑杆集合：弹窗打开时（PillSlider aboutToAppear）同步读，
   * 缺键/坏读 = 空集 = 全部吸附（现行为）。弹窗每次打开重建组件 = 重读，设置页改动
   * 下一次开窗即生效，不需要通知机制（上游 rememberSliderContinuous 同款语义）。
   */
  static sliderContinuousKeys(ctx: common.Context): Set<SliderKey> {
    try {
      const store: preferences.Preferences =
        preferences.getPreferencesSync(ctx, { name: STORE_NAME } as preferences.Options);
      return decodeContinuousSliders(store.getSync(KEY_SLIDER_CONTINUOUS, '') as string);
    } catch (e) {
      Diag.w(TAG, `read slider_continuous failed, all snapped: ${e}`);
      return new Set<SliderKey>();
    }
  }
}
