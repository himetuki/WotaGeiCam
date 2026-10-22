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
import { Size, WotaTag, WotaTiers } from '../core/Constants';
import { CameraAbility } from '../core/CameraAbility';
import { WotaParams } from '../core/WotaParams';
import { LensSlot, LensType } from '../core/LensSlot';
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
  private previewFps: number = 0;

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

  /** 最近一次 applyZoom 实际生效倍率（1 = 未设置/失败） */
  currentZoom(): number {
    return this.lastZoom;
  }

  /** 实际预览帧率（0 = 未设置/平台拒绝；登记可能仍为默认 30fps） */
  currentPreviewFps(): number {
    return this.previewFps;
  }

  /** 相机丢失（未录制时页面仅提示；录制中由 AutoStopMonitor 消费） */
  setOnCameraLost(listener: (() => void) | undefined): void {
    this.lostListener = listener;
  }

  /**
   * 把相机帧接到 native 输出面；返回人读摘要/错误串。
   * slotKey 缺省 = pickDefaultLens；wantSize 缺省 = 1080p（GlPreview 两参调用行为不变）。
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
      if (slotKey !== undefined) {
        slot = this.slots.find((s: LensSlot): boolean => s.key === slotKey);
      }
      if (slot === undefined) {
        slot = pickDefaultLens(this.slots);
      }
      if (slot === undefined) {
        return 'gl camera failed: no camera';
      }
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
      // 登记偏离：PhotoSession 预览无 setFrameRate（该 API 属 VideoOutput），预览源可能仍为 30fps；
      // 录制帧率仅编码器侧生效，光弧抽帧按编码器输出节奏。previewFps 保持 0 = 未设置。
      this.previewFps = 0;
      if (fps !== undefined && fps > 0) {
        Diag.i(WotaTag.ABILITY,
          `gl camera preview fps set not supported (requested ${fps}), encoder-side only`);
      }
      await session.commitConfig();
      await session.start();
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
      const highSpeed: boolean = fps > WotaTiers.HIGH_SPEED_FPS;
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
    this.previewFps = 0;
    this.status = DeviceStatus.CLOSE;
  }
}
