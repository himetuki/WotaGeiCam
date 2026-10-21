/**
 * CameraAbility 的 CameraKit 工厂（探测版适配层）——对应上游 core/CameraAbility.kt 的
 * from(cameraId, CameraCharacteristics)（对齐上游 38d712a），把 CameraOutputCapability +
 * CameraDevice 现算成 core/CameraAbility.ets 的能力快照。
 *
 * 本 SDK（6.1.1 / API 24）逐字段映射表（验收口径；「会话期」= 未开 CaptureSession 查不到）：
 *
 * | CameraAbility 字段 | OHOS 来源 / 取值 |
 * |---|---|
 * | hardwareLevel | 常量 1（Camera2 契约值 FULL）。OHOS 无硬件档位概念，按非 LEGACY 填充，isLegacy() 恒 false |
 * | sensorOrientation | device.cameraOrientation |
 * | activeArray | device.sensorPixelArraySize（API24+，[w,h]）→ Rect(0,0,w,h)；undefined → Rect(0,0,0,0) |
 * | jpegSizes | photoProfiles 的 size 去重、pixels 降序（与上游 JPEG 表同口径） |
 * | previewSizes | previewProfiles 的 size 去重、pixels→width 降序（上游 PRIVATE 单表；DIRECT 直显预览专用） |
 * | videoSizes | preview∪photo∪video 三表 size 去重、pixels→width→height 降序（上游 = PRIVATE∪JPEG 的等价并集） |
 * | fpsRanges | videoProfiles 的 frameRateRange 去重为 RangeI(min,max)、lo→hi 升序。注释：无 AE target fps 等价物，以视频档帧率范围近似 |
 * | highSpeedCapable/Sizes/Fps | videoProfiles 中 frameRateRange.max ≥ 120 的档（近似；阈值 120 登记理由：产品最高档 240、常规档 ≤60，分水岭与 WotaTiers.HIGH_SPEED_FPS=60 之上的「必须专用档」区间一致） |
 * | iso / exposureNs / evRange | RangeI.CLOSED（会话期查询，探测版置关 → 手动曝光走上游既有灰显路径）；evStep = 0 |
 * | afModes / awbModes / flashAvailable / maxRegionsAf / maxRegionsAe | 空集 / false / 0 / 0（会话期补，预览批登记） |
 * | minFocusDistanceDiopter | device.minimumFocusDistance ?? 0 |
 * | zoomRatioRange / maxDigitalZoom | undefined / 1（会话期 ZoomQuery 补，预览批登记） |
 * | oisAvailable / eisAvailable | false / false（StabilizationQuery 会话域，预览批登记） |
 * | availableSurfaceFormats | 三表出现过的 Profile.format 值集合 |
 *
 * 上游 from() 的排序/去重逻辑已落在 core 直译版消费侧，这里只做采集；
 * 逐项 tryOrNull 兜底风格与上游一致：单字段读取失败不拖垮整套能力快照。
 */
import { camera } from '@kit.CameraKit';
import { CameraAbility, CameraAbilityArgs, RangeI, RangeF } from '../core/CameraAbility';
import { Rect, Size } from '../core/Constants';
import { VideoSizeCandidates, sizeCloseTo } from '../core/VideoSizePicker';
import { focusModesToCam2, wbModesToAwb } from './ParamMap';

/** Camera2 INFO_SUPPORTED_HARDWARE_LEVEL_FULL 契约值：OHOS 无档位概念，按非 LEGACY 填充（LEGACY=2 见 core 侧判定） */
const HARDWARE_LEVEL_FULL: number = 1;

/** 高速档阈值（fps）：frameRateRange.max ≥ 120 的 videoProfile 记为高速档 */
const HIGH_SPEED_MIN_FPS: number = 120;

/** 上游 private inline tryOrNull 的模块级等价：读取失败返回 undefined，走各字段 fallback */
function tryOrNull<T>(block: () => T): T | undefined {
  try {
    return block();
  } catch (e) {
    return undefined;
  }
}

/** camera.Size（SDK 接口）→ core Size（契约类） */
function toSize(s: camera.Size): Size {
  return new Size(s.width, s.height);
}

/** profile 表 → 去重尺寸表（保出现顺序；排序由调用侧决定，与上游 sizesOf+distinct 分工一致） */
function distinctSizes(profiles: Array<camera.Profile>): Array<Size> {
  const seen: Set<string> = new Set<string>();
  const out: Array<Size> = [];
  for (const p of profiles) {
    const key: string = `${p.size.width}x${p.size.height}`;
    if (!seen.has(key)) {
      seen.add(key);
      out.push(toSize(p.size));
    }
  }
  return out;
}

function byPixelsDesc(a: Size, b: Size): number {
  return b.pixels - a.pixels;
}

function byPixelsWidthDesc(a: Size, b: Size): number {
  return (b.pixels - a.pixels) !== 0 ? b.pixels - a.pixels : b.width - a.width;
}

function byPixelsWidthHeightDesc(a: Size, b: Size): number {
  if (b.pixels - a.pixels !== 0) {
    return b.pixels - a.pixels;
  }
  if (b.width - a.width !== 0) {
    return b.width - a.width;
  }
  return b.height - a.height;
}

/** RangeI 去重收集器：按 "lo..hi" 键（RangeI 是引用相等，不能进 Set） */
class RangeCollector {
  private keys: Set<string> = new Set<string>();
  private ranges: Array<RangeI> = [];

  add(lo: number, hi: number): void {
    const key: string = `${lo}..${hi}`;
    if (!this.keys.has(key)) {
      this.keys.add(key);
      this.ranges.push(new RangeI(lo, hi));
    }
  }

  sorted(): Array<RangeI> {
    return this.ranges.slice().sort((a: RangeI, b: RangeI): number => a.lo - b.lo !== 0 ? a.lo - b.lo : a.hi - b.hi);
  }
}

/**
 * 探测版工厂：从 CameraDevice + CameraOutputCapability 收集能力快照。
 * 会话期字段（iso/曝光/ev/zoom/af/awb/闪光/区域上限/防抖）按 closed/空集退化，
 * 上游本就有这些退化路径（灰显/钳制），预览批开会话后补真值。
 */
export function abilityFromKit(
  cameraId: string,
  device: camera.CameraDevice,
  capability: camera.CameraOutputCapability
): CameraAbility {
  const previewProfiles: Array<camera.Profile> = tryOrNull((): Array<camera.Profile> => capability.previewProfiles) ?? [];
  const photoProfiles: Array<camera.Profile> = tryOrNull((): Array<camera.Profile> => capability.photoProfiles) ?? [];
  const videoProfiles: Array<camera.VideoProfile> =
    tryOrNull((): Array<camera.VideoProfile> => capability.videoProfiles) ?? [];

  const sensorOrientation: number = tryOrNull((): number => device.cameraOrientation) ?? 0;
  // @SuppressWarnings compatibility
  const minFocusDistanceDiopter: number = tryOrNull((): number | undefined => device.minimumFocusDistance) ?? 0;

  // sensorPixelArraySize: [w, h]（API24+ 可选；6.0(20) 设备运行期为 undefined → 空数组兜底）
  // @SuppressWarnings compatibility
  const pixelArray: Array<number> | undefined = tryOrNull((): Array<number> | undefined => device.sensorPixelArraySize);
  const activeArray: Rect = pixelArray !== undefined && pixelArray.length >= 2
    ? new Rect(0, 0, pixelArray[0], pixelArray[1])
    : new Rect(0, 0, 0, 0);

  const jpegSizes: Array<Size> = distinctSizes(photoProfiles).sort(byPixelsDesc);
  const previewSizes: Array<Size> = distinctSizes(previewProfiles).sort(byPixelsWidthDesc);
  const mergedProfiles: Array<camera.Profile> = previewProfiles.concat(photoProfiles, videoProfiles);
  const videoSizes: Array<Size> = distinctSizes(mergedProfiles).sort(byPixelsWidthHeightDesc);

  // fpsRanges：以视频档帧率范围近似 AE target fps（OHOS 无管理器层等价物，登记）
  const fpsCollector: RangeCollector = new RangeCollector();
  const highSpeedProfiles: Array<camera.VideoProfile> = [];
  for (const p of videoProfiles) {
    const range: camera.FrameRateRange | undefined = tryOrNull((): camera.FrameRateRange => p.frameRateRange);
    if (range === undefined) {
      continue;
    }
    fpsCollector.add(range.min, range.max);
    if (range.max >= HIGH_SPEED_MIN_FPS) {
      highSpeedProfiles.push(p);
    }
  }
  const fpsRanges: Array<RangeI> = fpsCollector.sorted();

  // 高速档：帧率上限 ≥120 的 videoProfile（近似；上游是 CONSTRAINED_HIGH_SPEED_VIDEO 能力位 + highSpeed* 表）
  const highSpeedCapable: boolean = highSpeedProfiles.length > 0;
  const highSpeedSizes: Array<Size> = distinctSizes(highSpeedProfiles);
  // Map 键是对象引用相等（core 侧口径），插入前按 sizeCloseTo 找既有桶
  const highSpeedFps: Map<Size, Array<RangeI>> = new Map<Size, Array<RangeI>>();
  if (highSpeedCapable) {
    for (const p of highSpeedProfiles) {
      const range: camera.FrameRateRange | undefined = tryOrNull((): camera.FrameRateRange => p.frameRateRange);
      if (range === undefined) {
        continue;
      }
      const size: Size = toSize(p.size);
      let bucket: Array<RangeI> | undefined = undefined;
      highSpeedFps.forEach((rs: Array<RangeI>, s: Size): void => {
        if (bucket === undefined && sizeCloseTo(s, size, VideoSizeCandidates.TOLERANCE_PX)) {
          bucket = rs;
        }
      });
      if (bucket === undefined) {
        bucket = [];
        highSpeedFps.set(size, bucket);
      }
      const exists: boolean = bucket.some((r: RangeI): boolean => r.lo === range.min && r.hi === range.max);
      if (!exists) {
        bucket.push(new RangeI(range.min, range.max));
      }
    }
  }

  const formatSet: Set<number> = new Set<number>();
  for (const p of mergedProfiles) {
    const f: camera.CameraFormat | undefined = tryOrNull((): camera.CameraFormat => p.format);
    if (f !== undefined) {
      formatSet.add(f as number);
    }
  }
  const availableSurfaceFormats: Array<number> = Array.from(formatSet);

  const args: CameraAbilityArgs = {
    hardwareLevel: HARDWARE_LEVEL_FULL,
    sensorOrientation: sensorOrientation,
    activeArray: activeArray,
    jpegSizes: jpegSizes,
    highSpeedSizes: highSpeedSizes,
    videoSizes: videoSizes,
    fpsRanges: fpsRanges,
    highSpeedFps: highSpeedFps,
    iso: RangeI.CLOSED,
    exposureNs: RangeI.CLOSED,
    evRange: RangeI.CLOSED,
    evStep: 0,
    afModes: new Set<number>(),
    awbModes: new Set<number>(),
    flashAvailable: false,
    maxRegionsAf: 0,
    maxRegionsAe: 0,
    minFocusDistanceDiopter: minFocusDistanceDiopter,
    zoomRatioRange: undefined,
    maxDigitalZoom: 1,
    oisAvailable: false,
    eisAvailable: false,
    highSpeedCapable: highSpeedCapable,
    availableSurfaceFormats: availableSurfaceFormats,
    previewSizes: previewSizes
  };
  return new CameraAbility(args);
}

/**
 * 35mm 等效焦距（探测版）：直接读 device.lensEquivalentFocalLength 首个值（API24+）。
 * 上游从传感器物理尺寸+焦距现算（core/CameraAbility.ets 的 eqFocalMm 纯函数保留可复用），
 * OHOS 平台已给等效值就不重复推算；缺失返回 0，排档兜底交给 KitLens 的「未知焦距排最后」。
 */
export function eqFocalMmOfKit(device: camera.CameraDevice): number {
  // @SuppressWarnings compatibility
  const list: Array<number> | undefined = tryOrNull((): Array<number> | undefined => device.lensEquivalentFocalLength);
  if (list === undefined || list.length === 0) {
    return 0;
  }
  return list[0];
}

/** 实际物理焦距（mm，排序用）：device.lensFocalLength（API24+）；缺失返回 0 */
export function focalMmOfKit(device: camera.CameraDevice): number {
  // @SuppressWarnings compatibility
  return tryOrNull((): number | undefined => device.lensFocalLength) ?? 0;
}

/**
 * 会话期能力补真值（预览批）：探测版按 closed/空集退化的字段，开会话后在会话域重新读取。
 * 复制 base 快照、仅替换下列项；逐项 try/catch——单项失败保 base 值（退化路径继续兜底）。
 *
 * | 字段 | 真值来源 |
 * |---|---|
 * | zoomRatioRange | session.getZoomRatioRange()（ZoomQuery）[min,max] → RangeF；不 ok() → undefined |
 * | evRange | session.getExposureBiasRange()（AutoExposureQuery）[min,max] 按 session.getExposureBiasStep() 换算成**档数**（round(EV/step)）→ RangeI，保持 core 契约（params.ev 是步数）；step≤0 → 保 base（登记） |
 * | evStep | session.getExposureBiasStep()（ManualExposureQuery）；≤0/不可读 → 保 base；下发侧 setExposureBias(steps × evStep) |
 * | flashAvailable | session.hasFlash()（FlashQuery） |
 * | iso | session.getSupportedIsoRange()（ManualIsoQuery）[min,max] → RangeI；manualExposurePossible 由此可能转真（exposureNs 已补） |
 * | exposureNs | session.getSupportedExposureDurationRange()（ManualExposureQuery）[min,max] **微秒** → ×1000 换算为 ns 存储（core 契约是 ns），hi>0 才替换 |
 * | afModes | focusModesToCam2(session)（ParamMap：逐档 session.isFocusModeSupported 探测后映射回 Camera2 契约值；连续档双发 {3,4}；空集 → 保 base） |
 * | awbModes | wbModesToAwb(session)（ParamMap：逐档 session.isWhiteBalanceModeSupported 探测后映射回 AWB_MODE 契约值；空集 → 保 base） |
 * | maxRegionsAf / maxRegionsAe | 恒 1：OHOS 单点对焦/测光，无区域数概念（近似登记；上游该值只用于门控区域写入与 dump） |
 * | focusDistanceSupported | session.isFocusDistanceSupported()（ManualFocusQuery，API24；OHOS 适配层扩展字段，非上游字段）——true 才可下发 setFocusDistance；undefined=旧设备/探测失败 → 下发侧 skipped（不是 failed） |
 * | oisAvailable | session.isOISModeSupported(camera.OISMode.AUTO)（PhotoSession extends OIS）——以 AUTO 档受支持作「有 OIS」的代理判定 |
 * | eisAvailable | isVideoStabilizationModeSupported 只在 VideoSession（StabilizationQuery）；PhotoSession 拿不到 → 保 base（false），VideoSession 版随录制批 |
 * | maxDigitalZoom | 恒 base=1：d.ts 无对应查询 API，上限语义由 zoomRatioRange[1] 承担（Android 的 SCALER_AVAILABLE_MAX_DIGITAL_ZOOM 无 OHOS 等价物） |
 *
 * 仍留白（登记）：tint（OHOS 无 RGB 增益接口）、逐帧 fps（预览档 profile 固定）、帧周期（同前）、
 * EIS 真值与 VideoSession 参数面（随录制批）。
 */
export function abilityRefineWithSession(base: CameraAbility, session: camera.PhotoSession): CameraAbility {
  let zoomRatioRange: RangeF | undefined = undefined;
  const zoomArr: Array<number> | undefined = tryOrNull((): Array<number> => session.getZoomRatioRange());
  if (zoomArr !== undefined && zoomArr.length >= 2) {
    const zr: RangeF = new RangeF(zoomArr[0], zoomArr[1]);
    zoomRatioRange = zr.ok() ? zr : undefined;
  }

  // EV：OHOS 直接给 EV 数值；按步长换算回 core 的档数口径（params.ev 是步数）
  // @SuppressWarnings compatibility
  const rawEvStep: number | undefined = tryOrNull((): number => session.getExposureBiasStep());
  // step≤0 / 不可读 → 回退 base（不拿 1 猜步长）
  const evStep: number = rawEvStep !== undefined && rawEvStep > 0 ? rawEvStep : base.evStep;
  const evArr: Array<number> | undefined = tryOrNull((): Array<number> => session.getExposureBiasRange());
  const evRange: RangeI = evArr !== undefined && evArr.length >= 2 && evStep > 0
    ? new RangeI(Math.round(evArr[0] / evStep), Math.round(evArr[1] / evStep))
    : base.evRange;

  // @SuppressWarnings compatibility
  const isoArr: Array<number> | undefined = tryOrNull((): Array<number> => session.getSupportedIsoRange());
  const iso: RangeI = isoArr !== undefined && isoArr.length >= 2
    ? new RangeI(isoArr[0], isoArr[1])
    : base.iso;

  // 手动曝光时长范围：OHOS 单位是**微秒**（d.ts getSupportedExposureDurationRange/setExposureDuration
  // 均注明 microseconds），core 契约 exposureNs 是纳秒 → ×1000 存储，下发侧 /1000 转回。
  // OHOS 无 AE OFF，MANUAL 由 LOCKED + 显式时长/ISO 实现（d.ts：setIso 仅 EXPOSURE_MODE_LOCKED 生效，
  // 故不用 EXPOSURE_MODE_MANUAL——它会令 setIso 失效，登记）。
  // @SuppressWarnings compatibility
  const expArr: Array<number> | undefined =
    tryOrNull((): Array<number> => session.getSupportedExposureDurationRange());
  const exposureNs: RangeI = expArr !== undefined && expArr.length >= 2 && expArr[1] > 0
    ? new RangeI(expArr[0] * 1000, expArr[1] * 1000)
    : base.exposureNs;

  const probedAf: Set<number> = focusModesToCam2(session);
  const afModes: Set<number> = probedAf.size > 0 ? probedAf : base.afModes;
  const probedAwb: Set<number> = wbModesToAwb(session);
  const awbModes: Set<number> = probedAwb.size > 0 ? probedAwb : base.awbModes;

  // 手动对焦距离能力（API24 ManualFocusQuery）：undefined = 旧设备/探测失败 → 下发侧 skipped（不是 failed）
  // @SuppressWarnings compatibility
  const focusDistanceProbed: boolean | undefined =
    tryOrNull((): boolean => session.isFocusDistanceSupported());
  const focusDistanceSupported: boolean | undefined =
    focusDistanceProbed !== undefined ? focusDistanceProbed : base.focusDistanceSupported;

  const flashAvailable: boolean = tryOrNull((): boolean => session.hasFlash()) ?? base.flashAvailable;
  // @SuppressWarnings compatibility
  const oisAvailable: boolean =
    tryOrNull((): boolean => session.isOISModeSupported(camera.OISMode.AUTO)) ?? base.oisAvailable;

  const args: CameraAbilityArgs = {
    hardwareLevel: base.hardwareLevel,
    sensorOrientation: base.sensorOrientation,
    activeArray: base.activeArray,
    jpegSizes: base.jpegSizes,
    highSpeedSizes: base.highSpeedSizes,
    videoSizes: base.videoSizes,
    fpsRanges: base.fpsRanges,
    highSpeedFps: base.highSpeedFps,
    iso: iso,
    exposureNs: exposureNs,
    evRange: evRange,
    evStep: evStep,
    afModes: afModes,
    awbModes: awbModes,
    flashAvailable: flashAvailable,
    maxRegionsAf: 1,
    maxRegionsAe: 1,
    minFocusDistanceDiopter: base.minFocusDistanceDiopter,
    zoomRatioRange: zoomRatioRange,
    maxDigitalZoom: base.maxDigitalZoom,
    oisAvailable: oisAvailable,
    eisAvailable: base.eisAvailable,
    highSpeedCapable: base.highSpeedCapable,
    availableSurfaceFormats: base.availableSurfaceFormats,
    previewSizes: base.previewSizes,
    focusDistanceSupported: focusDistanceSupported
  };
  return new CameraAbility(args);
}
