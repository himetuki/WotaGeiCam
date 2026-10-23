/**
 * 相机默认设置持久化（@ohos.data.preferences）。
 *
 * 键名迁移（本批 I）：与上游 24 键白名单重叠的字段改由上游键名做唯一权威——
 * `fps→default_fps`、`bitrate→default_bitrate`、`sizeW/sizeH→default_size`；
 * `level_enabled`/`default_ref_lines` 本来就同名。**迁移读**：新键在册就用新键，否则回退旧键
 * （旧键保留不删，无害）；下一次 save 起只写新键，旧键从此不再更新。
 * 键常量复用 `ui/ParamsPersist.ets`（纯逻辑单一真源），不在这里另写一份。
 *
 * **行为偏离登记（本批不动）**：`arcMode`（光弧 MEND/DROP）与 `grid`（3×3 开关）留在本 store
 * 原地，不折进 24 键白名单、也不折进 `default_ref_lines` 位掩码。
 *
 * 其余：画幅/帧率/码率/光弧/网格/效果 + 滑杆吸附开关（slider_continuous，对齐上游 0dee86d
 * KEY_SLIDER_CONTINUOUS 语义：存「关掉吸附」的参数 id 集合编码串，缺键/坏串 = 空集 = 全部吸附）。
 * 读取失败/类型异常回默认值；保存失败记日志不抛。
 */
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { Size } from '../core/Constants';
import { Diag } from '../diag/Diag';
import { PARAMS_STORE_NAME } from './ParamsStore';
import {
  KEY_DEFAULT_BITRATE, KEY_DEFAULT_FPS, KEY_DEFAULT_REF_LINES, KEY_DEFAULT_SIZE, KEY_LEVEL_ENABLED,
  ParamsMigrate, formatSize
} from './ParamsPersist';
import { SliderKey, decodeContinuousSliders } from './SliderSnap';

const TAG: string = 'WotaSettings';
/** 滑杆吸附开关的 prefs 键（编码串见 ui/SliderSnap.ets；缺键 = 全部吸附） */
const KEY_SLIDER_CONTINUOUS: string = 'slider_continuous';
/** 取景辅助：水平仪/俯仰仪回正震动（`level_enabled` 已并入上游 24 键，见 ParamsPersist） */
const KEY_LEVEL_BUZZ: string = 'level_buzz_enabled';
// ---- 迁移读的旧键名（只在 load 回退路径出现；save 不再写它们）----
const LEGACY_KEY_SIZE_W: string = 'sizeW';
const LEGACY_KEY_SIZE_H: string = 'sizeH';
const LEGACY_KEY_FPS: string = 'fps';
const LEGACY_KEY_BITRATE: string = 'bitrate';

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

/** 新键是否在册才算数（迁移读要靠"缺失"区分新键从没写过；类型不符一律当缺失） */
function readIntIfPresent(store: preferences.Preferences, key: string): number | undefined {
  try {
    if (!store.hasSync(key)) {
      return undefined;
    }
    const raw: preferences.ValueType = store.getSync(key, 0);
    return typeof raw === 'number' && Number.isFinite(raw as number) ? raw as number : undefined;
  } catch (e) {
    return undefined;
  }
}

function readStringIfPresent(store: preferences.Preferences, key: string): string | undefined {
  try {
    if (!store.hasSync(key)) {
      return undefined;
    }
    const raw: preferences.ValueType = store.getSync(key, '');
    return typeof raw === 'string' ? raw as string : undefined;
  } catch (e) {
    return undefined;
  }
}

export class SettingsStore {
  static async load(ctx: common.Context): Promise<CameraSettings> {
    const s: CameraSettings = new CameraSettings();
    try {
      const store: preferences.Preferences = await preferences.getPreferences(ctx, PARAMS_STORE_NAME);
      // 迁移读：新键（上游名）优先，缺失才回退旧键；下一次 save 写新键
      const size: Size = ParamsMigrate.size(
        readStringIfPresent(store, KEY_DEFAULT_SIZE),
        readIntIfPresent(store, LEGACY_KEY_SIZE_W),
        readIntIfPresent(store, LEGACY_KEY_SIZE_H),
        s.sizeW, s.sizeH);
      s.sizeW = size.width;
      s.sizeH = size.height;
      s.fps = ParamsMigrate.int(readIntIfPresent(store, KEY_DEFAULT_FPS),
        readIntIfPresent(store, LEGACY_KEY_FPS), s.fps);
      s.bitrate = ParamsMigrate.int(readIntIfPresent(store, KEY_DEFAULT_BITRATE),
        readIntIfPresent(store, LEGACY_KEY_BITRATE), s.bitrate);
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
      const store: preferences.Preferences = await preferences.getPreferences(ctx, PARAMS_STORE_NAME);
      // 白名单重叠字段只写上游键名（旧键 sizeW/sizeH/fps/bitrate 不再更新，保留在盘上无害）
      await store.put(KEY_DEFAULT_FPS, s.fps);
      await store.put(KEY_DEFAULT_BITRATE, s.bitrate);
      await store.put(KEY_DEFAULT_SIZE, formatSize(new Size(s.sizeW, s.sizeH)));
      // 非白名单键原地保留（arcMode/grid 是登记的本侧行为偏离，不折进 24 键）
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
        preferences.getPreferencesSync(ctx, { name: PARAMS_STORE_NAME } as preferences.Options);
      return decodeContinuousSliders(store.getSync(KEY_SLIDER_CONTINUOUS, '') as string);
    } catch (e) {
      Diag.w(TAG, `read slider_continuous failed, all snapped: ${e}`);
      return new Set<SliderKey>();
    }
  }
}
