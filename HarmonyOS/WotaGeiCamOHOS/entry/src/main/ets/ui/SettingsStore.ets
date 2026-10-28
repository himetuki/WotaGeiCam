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
 * KEY_SLIDER_CONTINUOUS 语义：存「关掉吸附」的参数 id 集合编码串，缺键/坏串 = 空集 = 全部吸附）
 * + 取景读数条位掩码（hud_items，对齐上游 WotaSettings.KEY_HUD_ITEMS：默认 DEFAULT_MASK=15，
 * 坏值经 HudItems.maskOrDefault 回默认，0 = 合法的「全部取消」）。
 * 读取失败/类型异常回默认值；保存失败记日志不抛。
 */
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { HudItems, Size, WotaTiers } from '../core/Constants';
import { UIOrientation, UIOrientations } from '../core/UIOrientation';
import { UserSettings } from '../core/UserSettings';
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
/** 取景读数条位掩码（上游 WotaSettings.KEY_HUD_ITEMS，逐字一致；不在 24 键白名单——纯 UI 设置，设置页直读直写） */
const KEY_HUD_ITEMS: string = 'hud_items';
/** HUD 位置表（上游 WotaSettings.KEY_HUD_LAYOUT，逐字一致；值为 HudLayoutTable.encode 的串） */
const KEY_HUD_LAYOUT: string = 'hud_layout';
/** 界面方向（上游 WotaSettings.KEY_UI_ORIENTATION，逐字一致；'portrait' | 'landscape'） */
const KEY_UI_ORIENTATION: string = 'ui_orientation';
/** 编排节拍（上游 WotaSettings.KEY_COMPARE_TICK_MS，逐字一致） */
const KEY_COMPARE_TICK_MS: string = 'compare_tick_ms';
/** 取景文本缩放（上游 WotaSettings.KEY_TEXT_SCALE_CAMERA，逐字一致；%） */
const KEY_TEXT_SCALE_CAMERA: string = 'text_scale_camera';
/** 诊断总开关（上游 WotaSettings.KEY_DIAG_ENABLED，逐字一致） */
const KEY_DIAG_ENABLED: string = 'diag_enabled';
/** 诊断详细档（上游 WotaSettings.KEY_DIAG_VERBOSE，逐字一致） */
const KEY_DIAG_VERBOSE: string = 'diag_verbose';
/** RGB 曲线栈持久串（上游 WotaSettings.KEY_CURVE_STACK，逐字一致；CURVE 批） */
const KEY_CURVE_STACK: string = 'curve_stack';
/**
 * 「用户显式选过镜头」持久化位 + 选中的槽位键（上游 0525fa2 LensPickPreference 语义对齐）：
 * false 期间冷启走能力协商默认（core/LensSlot.pickCapabilityDefault——后摄里最大视频档者，
 * 不认档位标签）；用户在镜头胶囊里切过一次后永久尊重其选择（冷启恢复该槽位键，
 * 设备不在了再回能力默认）。位丢失的最大代价只是退回能力默认（再选一次即恢复），可接受。
 */
const KEY_LENS_USER_PICKED: string = 'lens_user_picked';
const KEY_LENS_KEY: string = 'lens_key';
/** 画面降噪开关（追赶轮；音频降噪在本平台无对应能力，见 core/Denoise.ets 头注 T27-3） */
const KEY_VIDEO_DENOISE: string = 'video_denoise';
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
  /** 取景读数条位掩码（上游 hud_items；默认上屏四档，0 = 读数条整条不渲染） */
  hudItems: number = HudItems.DEFAULT_MASK;
  /**
   * HUD 位置表原文（上游 hud_layout，`HudLayoutTable.encode` 的 v2 串；空串 = 默认表）。
   * 本侧 J3b 只消费 READOUT 容器的条目顺序；容器位置/格子留给后续（编辑器本体见
   * pages/HudEditor.ets，编解码单一真源在 core/HudLayout.ets）。
   */
  hudLayout: string = '';
  /**
   * 界面方向（上游 ui_orientation）。**默认偏离登记**：上游出厂 landscape（横屏优先），
   * 本侧 portrait（竖屏固定）——鸿蒙 HUD 是 T31 竖屏取证设计（core/UIOrientation.ets 头注）。
   */
  uiOrientation: UIOrientation = UIOrientations.DEFAULT;
  /** 对比页编排节拍（上游 compare_tick_ms；缺键 200，钳 100..1000） */
  compareTickMs: number = UserSettings.COMPARE_TICK_DEFAULT_MS;
  /** 取景文本缩放（上游 text_scale_camera；%，默认 100） */
  textScaleCameraPct: number = UserSettings.TEXT_SCALE_DEFAULT_PCT;
  /** 诊断总开关（上游 diag_enabled；默认关——隐私承诺照搬） */
  diagEnabled: boolean = false;
  /** 诊断详细档（上游 diag_verbose；默认关） */
  diagVerbose: boolean = false;
  /** RGB 曲线栈持久串（CurveStack.encode 格式 `x:y,…;×4`；空串 = 恒等。CURVE 批，上游 curve_stack） */
  curveStackRaw: string = '';
  /** 用户显式选过镜头（上游 0525fa2 LensPickPreference.isPicked；false = 冷启走能力协商默认） */
  lensUserPicked: boolean = false;
  /** 显式选中的镜头槽位键（LensSlot.key；仅 lensUserPicked=true 时消费） */
  lensKey: string = '';
  /**
   * 画面降噪开关（追赶轮，键 `video_denoise`）：开 = 按负载分档下 setDenoiseLevel(1/2)，
   * 关 = 0。**只有画面降噪**——音频降噪在本平台无对应能力（core/Denoise.ets 头注，T27-3 登记）。
   */
  videoDenoise: boolean = false;
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
      // 坏值（非有限数/负数）收口在 HudItems.maskOrDefault；类型不符（手改 prefs）按缺失回默认
      const hudRaw: preferences.ValueType = await store.get(KEY_HUD_ITEMS, s.hudItems);
      s.hudItems = typeof hudRaw === 'number' ? HudItems.maskOrDefault(hudRaw as number) : s.hudItems;
      // 坏串（类型不符）当缺失 → 默认表；串本身的收口在 HudLayoutTable.decode（坏段丢弃/整表回默认）
      const layoutRaw: preferences.ValueType = await store.get(KEY_HUD_LAYOUT, s.hudLayout);
      s.hudLayout = typeof layoutRaw === 'string' ? layoutRaw as string : '';
      // 坏串由 UIOrientations.fromPersistValue 收口（大小写不敏感回默认）
      const oriRaw: preferences.ValueType = await store.get(KEY_UI_ORIENTATION,
        UIOrientations.persistValue(s.uiOrientation));
      s.uiOrientation = typeof oriRaw === 'string'
        ? UIOrientations.fromPersistValue(oriRaw as string) : s.uiOrientation;
      const tickRaw: preferences.ValueType = await store.get(KEY_COMPARE_TICK_MS, s.compareTickMs);
      s.compareTickMs = typeof tickRaw === 'number'
        ? UserSettings.compareTickMsOf(tickRaw as number) : s.compareTickMs;
      const tsRaw: preferences.ValueType = await store.get(KEY_TEXT_SCALE_CAMERA, s.textScaleCameraPct);
      s.textScaleCameraPct = typeof tsRaw === 'number'
        ? UserSettings.textScalePctOf(tsRaw as number) : s.textScaleCameraPct;
      const deRaw: preferences.ValueType = await store.get(KEY_DIAG_ENABLED, s.diagEnabled);
      s.diagEnabled = typeof deRaw === 'boolean' ? deRaw as boolean : s.diagEnabled;
      const dvRaw: preferences.ValueType = await store.get(KEY_DIAG_VERBOSE, s.diagVerbose);
      s.diagVerbose = typeof dvRaw === 'boolean' ? dvRaw as boolean : s.diagVerbose;
      const csRaw: preferences.ValueType = await store.get(KEY_CURVE_STACK, s.curveStackRaw);
      s.curveStackRaw = typeof csRaw === 'string' ? csRaw as string : s.curveStackRaw;
      const lpRaw: preferences.ValueType = await store.get(KEY_LENS_USER_PICKED, s.lensUserPicked);
      s.lensUserPicked = typeof lpRaw === 'boolean' ? lpRaw as boolean : s.lensUserPicked;
      const lkRaw: preferences.ValueType = await store.get(KEY_LENS_KEY, s.lensKey);
      s.lensKey = typeof lkRaw === 'string' ? lkRaw as string : s.lensKey;
      const vdRaw: preferences.ValueType = await store.get(KEY_VIDEO_DENOISE, s.videoDenoise);
      s.videoDenoise = typeof vdRaw === 'boolean' ? vdRaw as boolean : s.videoDenoise;
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
    // 帧率档位与 WotaTiers.FPS（24/25/30/50/60/120/240）同源：高帧率档也是合法持久值，
    // 旧实现只放行 24/25/30，会把用户选过的高帧率静默洗回 30
    if (WotaTiers.FPS.indexOf(s.fps) < 0) {
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
      await store.put(KEY_HUD_ITEMS, s.hudItems);
      await store.put(KEY_HUD_LAYOUT, s.hudLayout);
      await store.put(KEY_UI_ORIENTATION, UIOrientations.persistValue(s.uiOrientation));
      await store.put(KEY_COMPARE_TICK_MS, s.compareTickMs);
      await store.put(KEY_TEXT_SCALE_CAMERA, s.textScaleCameraPct);
      await store.put(KEY_CURVE_STACK, s.curveStackRaw);
      await store.put(KEY_DIAG_ENABLED, s.diagEnabled);
      await store.put(KEY_DIAG_VERBOSE, s.diagVerbose);
      await store.put(KEY_LENS_USER_PICKED, s.lensUserPicked);
      await store.put(KEY_LENS_KEY, s.lensKey);
      await store.put(KEY_VIDEO_DENOISE, s.videoDenoise);
      await store.flush();
      return true;
    } catch (e) {
      Diag.w(TAG, `save settings failed: ${e}`);
      return false;
    }
  }

  /**
   * 冷启同步读界面方向（EntryAbility 在 loadContent 前落窗口方向；走 getPreferencesSync，
   * 与 sliderContinuousKeys 同款）。坏串/缺键回默认（竖屏固定）。
   */
  /** 冷启同步读编排节拍（Compare 页 aboutToAppear 用） */
  static compareTickMsSync(ctx: common.Context): number {
    try {
      const store: preferences.Preferences =
        preferences.getPreferencesSync(ctx, { name: PARAMS_STORE_NAME } as preferences.Options);
      const raw: preferences.ValueType = store.getSync(KEY_COMPARE_TICK_MS, UserSettings.COMPARE_TICK_DEFAULT_MS);
      return typeof raw === 'number'
        ? UserSettings.compareTickMsOf(raw as number) : UserSettings.COMPARE_TICK_DEFAULT_MS;
    } catch (e) {
      Diag.w(TAG, `read compare_tick_ms failed, use default: ${e}`);
      return UserSettings.COMPARE_TICK_DEFAULT_MS;
    }
  }

  /** 冷启同步读取景文本缩放（Camera 页 hudFontScale 用） */
  static textScaleCameraSync(ctx: common.Context): number {
    try {
      const store: preferences.Preferences =
        preferences.getPreferencesSync(ctx, { name: PARAMS_STORE_NAME } as preferences.Options);
      const raw: preferences.ValueType = store.getSync(KEY_TEXT_SCALE_CAMERA, UserSettings.TEXT_SCALE_DEFAULT_PCT);
      return typeof raw === 'number'
        ? UserSettings.textScalePctOf(raw as number) : UserSettings.TEXT_SCALE_DEFAULT_PCT;
    } catch (e) {
      Diag.w(TAG, `read text_scale_camera failed, use default: ${e}`);
      return UserSettings.TEXT_SCALE_DEFAULT_PCT;
    }
  }

  /** 冷启同步读诊断两开关（Index 页 install 用） */
  static diagPersistSync(ctx: common.Context): boolean[] {
    const out: boolean[] = [false, false];
    try {
      const store: preferences.Preferences =
        preferences.getPreferencesSync(ctx, { name: PARAMS_STORE_NAME } as preferences.Options);
      const en: preferences.ValueType = store.getSync(KEY_DIAG_ENABLED, false);
      const ve: preferences.ValueType = store.getSync(KEY_DIAG_VERBOSE, false);
      out[0] = typeof en === 'boolean' ? en as boolean : false;
      out[1] = typeof ve === 'boolean' ? ve as boolean : false;
    } catch (e) {
      Diag.w(TAG, `read diag persist failed, use default: ${e}`);
    }
    return out;
  }

  /** 诊断两开关持久化（Index 页开关按钮用；与 load/save 同 store 同键） */
  static persistDiagFlags(ctx: common.Context, enabled: boolean, verbose: boolean): void {
    try {
      const store: preferences.Preferences =
        preferences.getPreferencesSync(ctx, { name: PARAMS_STORE_NAME } as preferences.Options);
      store.putSync(KEY_DIAG_ENABLED, enabled);
      store.putSync(KEY_DIAG_VERBOSE, verbose);
      store.flushSync();
    } catch (e) {
      Diag.w(TAG, `persist diag flags failed: ${e}`);
    }
  }

  static uiOrientationSync(ctx: common.Context): UIOrientation {
    try {
      const store: preferences.Preferences =
        preferences.getPreferencesSync(ctx, { name: PARAMS_STORE_NAME } as preferences.Options);
      const raw: preferences.ValueType = store.getSync(KEY_UI_ORIENTATION, '');
      return typeof raw === 'string'
        ? UIOrientations.fromPersistValue(raw as string) : UIOrientations.DEFAULT;
    } catch (e) {
      Diag.w(TAG, `read ui_orientation failed, use default: ${e}`);
      return UIOrientations.DEFAULT;
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
