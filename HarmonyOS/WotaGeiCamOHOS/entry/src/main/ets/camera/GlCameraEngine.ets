/**
 * GL 相机会话引擎（GL-1）：PhotoSession + PreviewOutput → XComponent 的原生输出面
 * （OH_NativeImage 的 surfaceId 由 cpp 侧 getInputSurfaceId 提供）。
 *
 * 边界（登记）：
 * - 本引擎只负责把相机帧送进 native 面，渲染（external OES 采样/上屏）在 cpp/gl_render.cpp；
 * - 与 Index 的预览/录制链路互不影响（独立路由页、独立会话）；
 * - 方向/镜像校正留 GL-2（上游 buildTexCoords 未移植），本批可能出现方向未校正；
 * - 相机帧进 GL 走 PhotoSession(NORMAL_PHOTO) 的 PreviewOutput，不建 VideoOutput。
 */
import { camera } from '@kit.CameraKit';
import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { Diag } from '../diag/Diag';
import { Size, WotaTag } from '../core/Constants';
import { CameraAbility } from '../core/CameraAbility';
import { WotaParams } from '../core/WotaParams';
import { LensSlot, LensType, lensTypeName, maxVideoPixels, pickCapabilityDefault } from '../core/LensSlot';
import { VideoSizeCandidates, sizeCloseTo } from '../core/VideoSizePicker';
import { enumerateKitLenses, findDeviceById, pickDefaultLens } from './KitLens';
import { abilityRefineWithSession } from './KitAbility';
import { ApplyReport, State, applySnapshot, snapshotOf, stateOf } from './RequestApplier';
import { FrameLedger } from '../record/FrameLedger';
import { DeviceStatus } from './DeviceStatus';

/** 当前镜头方向信息（GL 页 setDisplayGeometry 的输入：前置镜像 + 传感器角度） */
export class GlLensInfo {
  front: boolean;
  sensorDeg: number;

  constructor(front: boolean, sensorDeg: number) {
    this.front = front;
    this.sensorDeg = sensorDeg;
  }
}

/**
 * 预览档选择：有 wantSize 先按容差命中；未命中优先 1080p；再退首档。
 * 默认 wantSize=1920×1080（与 Index 预览口径一致，GlPreview 两参 start 行为不变）。
 */
function previewProfileFor(profiles: Array<camera.Profile>,
  wantSize: Size = new Size(1920, 1080)): camera.Profile | undefined {
  if (profiles.length === 0) {
    return undefined;
  }
  const hit: camera.Profile | undefined = profiles.find((p: camera.Profile): boolean =>
    sizeCloseTo(new Size(p.size.width, p.size.height), wantSize, VideoSizeCandidates.TOLERANCE_PX));
  if (hit !== undefined) {
    return hit;
  }
  const fallback1080: Size = new Size(1920, 1080);
  const hit1080: camera.Profile | undefined = profiles.find((p: camera.Profile): boolean =>
    sizeCloseTo(new Size(p.size.width, p.size.height), fallback1080, VideoSizeCandidates.TOLERANCE_PX));
  return hit1080 ?? profiles[0];
}

/** AF 状态遥测的拍点间隔（上游 4457e4a 方案F）：1s 一拍，d 级（详细档才记） */
const FOCUS_TELEMETRY_MS: number = 1000;

/**
 * 档位帧率 → 预览区间：遍历会话声明过的区间，取「min ≤ fps ≤ max 且 max 最小」者
 * （max 最小 = HAL 可跑的上界最贴近用户档位，不把 60 档放成 [30,240]）。
 * 无匹配返回 undefined —— 本机预览流做不到这一档，不硬发。
 * KitPreview（开发者页）复用同一支，不另写一份选档规则。
 */
export function previewRangeFor(ranges: Array<camera.FrameRateRange>,
  fps: number): camera.FrameRateRange | undefined {
  let best: camera.FrameRateRange | undefined = undefined;
  for (const r of ranges) {
    if (r.min <= fps && fps <= r.max) {
      if (best === undefined || r.max < best.max) {
        best = r;
      }
    }
  }
  return best;
}

/** FocusState → 短名（SCAN=0 / FOCUSED=1 / UNFOCUSED=2，d.ts 契约值） */
function FocusStateName(st: camera.FocusState): string {
  if (st === camera.FocusState.FOCUS_STATE_SCAN) {
    return 'SCAN';
  }
  if (st === camera.FocusState.FOCUS_STATE_FOCUSED) {
    return 'FOCUSED';
  }
  if (st === camera.FocusState.FOCUS_STATE_UNFOCUSED) {
    return 'UNFOCUSED';
  }
  return `${st}`;
}

export class GlCameraEngine {
  private session: camera.PhotoSession | undefined = undefined;
  private preview: camera.PreviewOutput | undefined = undefined;
  private input: camera.CameraInput | undefined = undefined;
  /** 起镜成功时落值的镜头方向信息（供页面 setDisplayGeometry；stop 清空） */
  private lensInfo: GlLensInfo | undefined = undefined;
  /** 设备态：AutoStop 的相机丢失检测按非 CLOSE → CLOSE 转移判定 */
  private status: DeviceStatus = DeviceStatus.CLOSE;
  private lostListener: (() => void) | undefined = undefined;
  /** 镜头缓存（stop 不清，供胶囊循环/设置页） */
  private slots: Array<LensSlot> = [];
  private currentSlot: LensSlot | undefined = undefined;
  private chosenSize: Size | undefined = undefined;
  private zoomRange: Array<number> = [1, 1];
  private previewSizes: Array<Size> = [];
  private lastZoom: number = 1;
  /** AF 遥测拍点节流（1s 一拍，见 start 内 focusStateChange 监听） */
  private lastFocusTelemetryMs: number = 0;

  /** 当前镜头方向信息（未起镜返回 undefined） */
  lastLensInfo(): GlLensInfo | undefined {
    return this.lensInfo;
  }

  deviceStatus(): DeviceStatus {
    return this.status;
  }

  /** 枚举镜头（缓存）；失败返回空表 */
  async listLenses(ctx: common.Context): Promise<Array<LensSlot>> {
    try {
      const mgr: camera.CameraManager = camera.getCameraManager(ctx);
      this.slots = enumerateKitLenses(mgr);
      return this.slots;
    } catch (e) {
      Diag.e(WotaTag.ABILITY, `gl camera list lenses failed: ${e}`);
      return [];
    }
  }

  currentSize(): Size | undefined {
    return this.chosenSize;
  }

  currentLens(): LensSlot | undefined {
    return this.currentSlot;
  }

  supportedPreviewSizes(): Array<Size> {
    return this.previewSizes;
  }

  /**
   * 当前会话预览支持的帧率（各区间上界去重升序；无会话/查询失败返回空表）。
   * 只作旁路读数/开发者页显示，不参与任何下发裁决。
   */
  supportedPreviewFrameRates(): Array<number> {
    const preview: camera.PreviewOutput | undefined = this.preview;
    if (preview === undefined) {
      return [];
    }
    try {
      const out: Array<number> = [];
      for (const r of preview.getSupportedFrameRates()) {
        if (out.indexOf(r.max) < 0) {
          out.push(r.max);
        }
      }
      out.sort((a: number, b: number): number => a - b);
      return out;
    } catch (e) {
      Diag.w(WotaTag.ABILITY, `gl preview supported fps query failed: ${e}`);
      return [];
    }
  }

  /** 活设置预览帧率（帧率弹层选中/起录前重下发）：无会话/无匹配区间/下发失败返回 false */
  applyPreviewFps(fps: number): boolean {
    const preview: camera.PreviewOutput | undefined = this.preview;
    if (preview === undefined) {
      return false;
    }
    return this.applyFpsTo(preview, fps);
  }

  /** 档位 → 声明区间 → setFrameRate（失败 Diag.w 不抛；成功返回 true） */
  private applyFpsTo(preview: camera.PreviewOutput, fps: number): boolean {
    if (!(fps > 0)) {
      return false;
    }
    try {
      const best: camera.FrameRateRange | undefined = previewRangeFor(preview.getSupportedFrameRates(), fps);
      if (best === undefined) {
        Diag.w(WotaTag.ABILITY, `gl preview fps ${fps} unsupported by declared ranges`);
        return false;
      }
      preview.setFrameRate(best.min, best.max);
      Diag.i(WotaTag.ABILITY, `gl preview fps ${fps} -> [${best.min},${best.max}]`);
      return true;
    } catch (e) {
      Diag.w(WotaTag.ABILITY, `gl preview setFrameRate ${fps} failed: ${e}`);
      return false;
    }
  }

  /** 最近一次 applyZoom 实际生效倍率（1 = 未设置/失败） */
  currentZoom(): number {
    return this.lastZoom;
  }

  /** 相机丢失（未录制时页面仅提示；录制中由 AutoStopMonitor 消费） */
  setOnCameraLost(listener: (() => void) | undefined): void {
    this.lostListener = listener;
  }

  /**
   * 把相机帧接到 native 输出面；返回人读摘要/错误串。
   * slotKey 缺省 = 能力协商默认（pickCapabilityDefault：后摄里最大视频档者，不认档位标签，
   * 上游 0525fa2）→ 退 pickDefaultLens（档位标签）；wantSize 缺省 = 1080p（GlPreview 两参调用行为不变）。
   */
  async start(ctx: common.Context, nativeSurfaceId: string, slotKey?: string,
    wantSize?: Size, fps?: number): Promise<string> {
    try {
      await this.stop();
      if (nativeSurfaceId === '') {
        return 'gl camera failed: no native surface';
      }
      const mgr: camera.CameraManager = camera.getCameraManager(ctx);
      if (this.slots.length === 0) {
        this.slots = enumerateKitLenses(mgr);
      }
      let slot: LensSlot | undefined = undefined;
      if (slotKey !== undefined && slotKey !== '') {
        slot = this.slots.find((s: LensSlot): boolean => s.key === slotKey);
      }
      if (slot === undefined) {
        slot = pickCapabilityDefault(this.slots) ?? pickDefaultLens(this.slots);
      }
      if (slot === undefined) {
        return 'gl camera failed: no camera';
      }
      // 打开的是哪一颗物理头必须能从日志对上：ability.dump 的 label 只是档位名（WIDE），
      // 同档位多颗/归档存疑时只有 key + 等效焦距 + 最大视频档能定位现场（上游 9b33272 取证口径）
      Diag.i(WotaTag.LENS,
        `open slot=${slot.key} type=${lensTypeName(slot.type)} eq=${slot.eqFocal}mm ` +
        `videoMax=${maxVideoPixels(slot.ability)} afModes=${Array.from(slot.ability.afModes).join(',')} ` +
        `minFocusD=${slot.ability.minFocusDistanceDiopter}`);
      const device: camera.CameraDevice | undefined = findDeviceById(mgr, slot.logicId);
      if (device === undefined) {
        return 'gl camera failed: device gone';
      }
      const capability: camera.CameraOutputCapability =
        mgr.getSupportedOutputCapability(device, camera.SceneMode.NORMAL_PHOTO);
      const profiles: Array<camera.Profile> = capability.previewProfiles ?? [];
      const profile: camera.Profile | undefined = previewProfileFor(profiles,
        wantSize === undefined ? new Size(1920, 1080) : wantSize);
      if (profile === undefined) {
        return 'gl camera failed: no preview profile';
      }
      const input: camera.CameraInput = mgr.createCameraInput(device);
      await input.open();
      this.input = input;
      const session: camera.PhotoSession = mgr.createSession<camera.PhotoSession>(camera.SceneMode.NORMAL_PHOTO);
      this.session = session;
      session.on('error', (err: BusinessError): void => {
        this.status = DeviceStatus.CLOSE;
        this.lensInfo = undefined;
        this.currentSlot = undefined;
        this.chosenSize = undefined;
        this.previewSizes = [];
        this.zoomRange = [1, 1];
        Diag.e(WotaTag.ABILITY, `gl camera session error: ${err.code} ${err.message}`);
        if (this.lostListener !== undefined) {
          this.lostListener();
        }
      });
      session.beginConfig();
      session.addInput(input);
      const preview: camera.PreviewOutput = mgr.createPreviewOutput(profile, nativeSurfaceId);
      this.preview = preview;
      session.addOutput(preview);
      await session.commitConfig();
      await session.start();
      // 预览帧率：SDK 12+ 的 PreviewOutput.setFrameRate 是真口（此前登记「预览无 setFrameRate」
      // 已过时）。失败只 Diag.w，不阻塞起镜——预览照常出画面，录制帧率仍在编码器侧。
      if (fps !== undefined && fps > 0) {
        this.applyFpsTo(preview, fps);
      }
      this.status = DeviceStatus.OPEN_SUCCEED;
      try {
        preview.on('frameStart', (err: BusinessError): void => {
          if (!err) {
            FrameLedger.onCameraStarted();
          }
        });
      } catch (e) {
        Diag.w(WotaTag.ABILITY, `gl camera frameStart listener failed: ${e}`);
      }
      // AF 状态遥测（上游 4457e4a 方案F 的 OHOS 对应面）：1s 一拍、d 级（详细档才记，
      // 遥测是排查工具不是常态记录）。弱光对焦差这类 3A 行为问题此前现场只有结果没有中间态，
      // "AF 卡在 SCAN"一眼可判。SDK 无 PhotoSession 的 AE 状态事件（上游 CONTROL_AE_STATE
      // 无 OHOS 对应，登记）——曝光归因仍靠下发报告 + 本行 AF 中间态。
      try {
        session.on('focusStateChange', (err: BusinessError, st: camera.FocusState): void => {
          if (err) {
            return;
          }
          const now: number = Date.now();
          if (now - this.lastFocusTelemetryMs < FOCUS_TELEMETRY_MS) {
            return;
          }
          this.lastFocusTelemetryMs = now;
          Diag.d(WotaTag.ABILITY, `AF遥测 state=${FocusStateName(st)}`);
        });
      } catch (e) {
        Diag.w(WotaTag.ABILITY, `gl camera focusStateChange listener failed: ${e}`);
      }
      this.previewSizes = [];
      for (const p of profiles) {
        this.previewSizes.push(new Size(p.size.width, p.size.height));
      }
      this.currentSlot = slot;
      this.chosenSize = new Size(profile.size.width, profile.size.height);
      try {
        const range: Array<number> = session.getZoomRatioRange();
        this.zoomRange = range.length >= 2 ? [range[0], range[1]] : [1, 1];
      } catch (e) {
        this.zoomRange = [1, 1];
      }
      this.lastZoom = 1;
      this.lensInfo = new GlLensInfo(slot.type === LensType.FRONT, slot.ability.sensorOrientation);
      Diag.i(WotaTag.ABILITY,
        `gl camera started lens=${slot.key} profile=${profile.size.width}x${profile.size.height}`);
      return `gl camera started\nlens=${slot.key}\nprofile=${profile.size.width}x${profile.size.height}`;
    } catch (e) {
      Diag.e(WotaTag.ABILITY, `gl camera failed: ${e}`);
      await this.stop();
      return `gl camera failed: ${e}`;
    }
  }

  /** 变焦：钳到会话 zoomRange 并返回实际生效倍率；失败 -1 */
  applyZoom(ratio: number): number {
    const session: camera.PhotoSession | undefined = this.session;
    if (session === undefined) {
      return -1;
    }
    const lo: number = this.zoomRange.length >= 2 ? this.zoomRange[0] : 1;
    const hi: number = this.zoomRange.length >= 2 ? this.zoomRange[1] : 1;
    const target: number = Math.min(hi, Math.max(lo, ratio));
    try {
      session.setZoomRatio(target);
      this.lastZoom = target;
      return target;
    } catch (e) {
      Diag.w(WotaTag.ABILITY, `gl camera zoom ${target} failed: ${e}`);
      return -1;
    }
  }

  /** 闪光：CLOSE / ALWAYS_OPEN（TORCH 语义）；失败 false */
  applyFlash(mode: camera.FlashMode): boolean {
    const session: camera.PhotoSession | undefined = this.session;
    if (session === undefined) {
      return false;
    }
    try {
      session.setFlashMode(mode);
      return true;
    } catch (e) {
      Diag.w(WotaTag.ABILITY, `gl camera flash failed: ${e}`);
      return false;
    }
  }

  /**
   * 会话期能力真值（T21 高级参数批）：复用 KitAbility.abilityRefineWithSession
   * 补会话期字段（iso/曝光/ev/evStep/zoom/af/awb/flash/focusDistance/ois）。
   * 每次现算（会话域查询本身便宜，免缓存失效问题）；session 未启动返回 undefined。
   */
  refinedAbility(): CameraAbility | undefined {
    const session: camera.PhotoSession | undefined = this.session;
    const slot: LensSlot | undefined = this.currentSlot;
    if (session === undefined || slot === undefined) {
      return undefined;
    }
    try {
      return abilityRefineWithSession(slot.ability, session);
    } catch (e) {
      Diag.w(WotaTag.ABILITY, `gl camera refine ability failed: ${e}`);
      return undefined;
    }
  }

  /**
   * 参数下发（T21）：按参数总线当前快照逐键 apply（RequestApplier.applySnapshot）。
   * 会话停止/能力不可得时返回空报告（applied/skipped/failed 全空）不崩溃——
   * 页面在停镜后的残留回调（弹层改动/停录收尾）仍可安全调用。
   * 口径与 KitPreview.applyParams 同：size 用本会话选中预览档，fps 用参数总线档位。
   */
  applyParams(params: WotaParams): ApplyReport {
    const empty: ApplyReport = new ApplyReport();
    const session: camera.PhotoSession | undefined = this.session;
    const ability: CameraAbility | undefined = this.refinedAbility();
    if (session === undefined || ability === undefined) {
      return empty;
    }
    try {
      const size: Size = this.chosenSize ?? params.size.value;
      const fps: number = params.fps.value.value;
      // highSpeed 判据与总线落档/下发钳制同源（core.needsHighSpeed：普通表先来、覆盖不了才看
      // 高速表），不按「档位数值 > 30」猜（上游 2026-10-26 口径，与 KitPreview.applyParams 一致）
      const highSpeed: boolean = ability.needsHighSpeed(size, fps);
      const state: State = stateOf(ability, size, highSpeed, ability.evStep);
      return applySnapshot(session, state, snapshotOf(params, state));
    } catch (e) {
      Diag.e(WotaTag.ABILITY, `gl camera applyParams failed: ${e}`);
      return empty;
    }
  }

  /** 关会话（逐项 try/catch，收尾不抛；顺序与建链相反） */
  async stop(): Promise<void> {
    try {
      if (this.session !== undefined) {
        await this.session.stop();
      }
    } catch (e) {
      Diag.w(WotaTag.ABILITY, `gl session stop: ${e}`);
    }
    try {
      if (this.session !== undefined) {
        await this.session.release();
      }
    } catch (e) {
      Diag.w(WotaTag.ABILITY, `gl session release: ${e}`);
    }
    try {
      if (this.preview !== undefined) {
        this.preview.off('frameStart');
      }
    } catch (e) {
    }
    try {
      if (this.preview !== undefined) {
        await this.preview.release();
      }
    } catch (e) {
      Diag.w(WotaTag.ABILITY, `gl preview release: ${e}`);
    }
    try {
      if (this.input !== undefined) {
        await this.input.close();
      }
    } catch (e) {
      Diag.w(WotaTag.ABILITY, `gl input close: ${e}`);
    }
    this.session = undefined;
    this.preview = undefined;
    this.input = undefined;
    this.lensInfo = undefined;
    this.currentSlot = undefined;
    this.chosenSize = undefined;
    this.zoomRange = [1, 1];
    this.previewSizes = [];
    this.lastZoom = 1;
    this.status = DeviceStatus.CLOSE;
  }
}
