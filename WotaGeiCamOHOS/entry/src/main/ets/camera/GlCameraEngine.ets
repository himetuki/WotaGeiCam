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
import { LensType } from '../core/LensSlot';
import { VideoSizeCandidates, sizeCloseTo } from '../core/VideoSizePicker';
import { enumerateKitLenses, findDeviceById, pickDefaultLens } from './KitLens';
import { FrameLedger } from '../record/FrameLedger';

/** 当前镜头方向信息（GL 页 setDisplayGeometry 的输入：前置镜像 + 传感器角度） */
export class GlLensInfo {
  front: boolean;
  sensorDeg: number;

  constructor(front: boolean, sensorDeg: number) {
    this.front = front;
    this.sensorDeg = sensorDeg;
  }
}

/** 预览档优先 1920×1080（与 Index 预览口径一致），未命中退首档 */
function previewProfileFor(profiles: Array<camera.Profile>): camera.Profile | undefined {
  if (profiles.length === 0) {
    return undefined;
  }
  const want: Size = new Size(1920, 1080);
  const hit: camera.Profile | undefined = profiles.find((p: camera.Profile): boolean =>
    sizeCloseTo(new Size(p.size.width, p.size.height), want, VideoSizeCandidates.TOLERANCE_PX));
  return hit ?? profiles[0];
}

export class GlCameraEngine {
  private session: camera.PhotoSession | undefined = undefined;
  private preview: camera.PreviewOutput | undefined = undefined;
  private input: camera.CameraInput | undefined = undefined;
  /** 起镜成功时落值的镜头方向信息（供页面 setDisplayGeometry；stop 清空） */
  private lensInfo: GlLensInfo | undefined = undefined;

  /** 当前镜头方向信息（未起镜返回 undefined） */
  lastLensInfo(): GlLensInfo | undefined {
    return this.lensInfo;
  }

  /** 把相机帧接到 native 输出面；返回人读摘要/错误串（页面直接显示） */
  async start(ctx: common.Context, nativeSurfaceId: string): Promise<string> {
    try {
      await this.stop();
      if (nativeSurfaceId === '') {
        return 'gl camera failed: no native surface';
      }
      const mgr: camera.CameraManager = camera.getCameraManager(ctx);
      const slots = enumerateKitLenses(mgr);
      const slot = pickDefaultLens(slots);
      if (slot === undefined) {
        return 'gl camera failed: no camera';
      }
      const device: camera.CameraDevice | undefined = findDeviceById(mgr, slot.logicId);
      if (device === undefined) {
        return 'gl camera failed: device gone';
      }
      const capability: camera.CameraOutputCapability =
        mgr.getSupportedOutputCapability(device, camera.SceneMode.NORMAL_PHOTO);
      const profile: camera.Profile | undefined = previewProfileFor(capability.previewProfiles ?? []);
      if (profile === undefined) {
        return 'gl camera failed: no preview profile';
      }
      const input: camera.CameraInput = mgr.createCameraInput(device);
      await input.open();
      this.input = input;
      const session: camera.PhotoSession = mgr.createSession<camera.PhotoSession>(camera.SceneMode.NORMAL_PHOTO);
      this.session = session;
      session.on('error', (err: BusinessError): void => {
        Diag.e(WotaTag.ABILITY, `gl camera session error: ${err.code} ${err.message}`);
      });
      session.beginConfig();
      session.addInput(input);
      const preview: camera.PreviewOutput = mgr.createPreviewOutput(profile, nativeSurfaceId);
      this.preview = preview;
      session.addOutput(preview);
      await session.commitConfig();
      await session.start();
      // 帧账相机到达格：commit/start 成功后才注册（此前 PreviewOutput 可能还没流）。
      // 线程声明（登记）：CameraKit 的 AsyncCallback 在本注册线程的事件循环派发；本引擎
      // 只从 ArkTS UI 主线程 start/stop，故 FrameLedger.cameraStarted 保持单线程累加语义。
      // 若未来在其他线程起停会话，需改为 native/atomic 或主线程转发（FrameLedger 头注释同）。
      try {
        preview.on('frameStart', (err: BusinessError): void => {
          if (!err) {
            FrameLedger.onCameraStarted();
          }
        });
      } catch (e) {
        Diag.w(WotaTag.ABILITY, `gl camera frameStart listener failed: ${e}`);
      }
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
  }
}
