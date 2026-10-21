/**
 * 预览会话引擎（预览批核心）——上游 CameraController「默认开广角后摄 + 能力就位即开预览」
 * 语义的最小化（对齐上游 38d712a）：getCameraManager → 枚举镜头 → 默认档（不写死 id）→
 * pickDirectPreviewSize 选预览档 → PhotoSession(SURFACE 面) start。
 *
 * 平台差异（登记）：
 * - Android 的 repeat 请求（setRepeatingRequest）换 OHOS 会话 start()；预览面经
 *   XComponent(SURFACE).getXComponentSurfaceId() 注入 createPreviewOutput(profile, surfaceId)；
 * - 预览档挑选复用 core 直译版 pickDirectPreviewSize（GL/DIRECT 双路判断留待渲染批，
 *   本批一律走「预览表单表 + 录像最大档锚」的挑选规则，与 DIRECT 语义一致）；
 * - 切镜头 UI、曝光/对焦/变焦参数下发、GL 链路与录制均留待后续批——本批只做
 *   「开页 → 授权 → 出画面 → 关页收尾」；
 * - 会话期错误经 session.on('error') 落 Diag（tag 复用 WotaAbility，不新造）。
 */
import { camera } from '@kit.CameraKit';
import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { Diag } from '../diag/Diag';
import { Size, WotaTag } from '../core/Constants';
import { LensSlot, LensType } from '../core/LensSlot';
import { CameraAbility } from '../core/CameraAbility';
import { pickDirectPreviewSize, sizeCloseTo, VideoSizeCandidates } from '../core/VideoSizePicker';
import { enumerateKitLenses, findDeviceById } from './KitLens';
import { abilityRefineWithSession } from './KitAbility';

/** 默认档：归档结果里第一个 WIDE（多后摄按焦距归档后广角稳定在 WIDE）；无 WIDE 则第一个非前摄 */
function pickDefaultLens(slots: Array<LensSlot>): LensSlot | undefined {
  if (slots.length === 0) {
    return undefined;
  }
  const wide: LensSlot | undefined = slots.find((s: LensSlot): boolean => s.type === LensType.WIDE);
  if (wide !== undefined) {
    return wide;
  }
  const nonFront: LensSlot | undefined = slots.find((s: LensSlot): boolean => s.type !== LensType.FRONT);
  return nonFront ?? slots[0];
}

/** 预览档反查：pickDirectPreviewSize 选中的 Size → 命中的 previewProfile（±TOLERANCE_PX） */
function profileForSize(profiles: Array<camera.Profile>, size: Size | undefined): camera.Profile | undefined {
  if (size === undefined) {
    return undefined;
  }
  return profiles.find((p: camera.Profile): boolean =>
    sizeCloseTo(new Size(p.size.width, p.size.height), size, VideoSizeCandidates.TOLERANCE_PX));
}

/**
 * 预览引擎：一次 start = 一套会话（input + previewOutput + PhotoSession）；
 * stop 逐项收尾（stop→release/close），任何一步失败不抛、不阻塞后续清理。
 */
export class PreviewEngine {
  private session: camera.PhotoSession | undefined = undefined;
  private preview: camera.PreviewOutput | undefined = undefined;
  private input: camera.CameraInput | undefined = undefined;

  /**
   * 开预览。返回人读状态摘要（页面直接显示）；失败不抛——Diag 落错 + 返回 "preview failed: ..."。
   * @param surfaceId XComponent(SURFACE) 的 surfaceId（onLoad 时由 controller 取得）
   * @param viewW/viewH 承载视图像素宽高；未布局传 0（core 的 pickDirectPreviewSize 会退到最大档）
   */
  async start(ctx: common.Context, surfaceId: string, viewW: number, viewH: number): Promise<string> {
    try {
      await this.stop(); // 重入安全：先收上一套
      if (surfaceId === '') {
        return 'preview failed: no surface';
      }
      const mgr: camera.CameraManager = camera.getCameraManager(ctx);
      const slots: Array<LensSlot> = enumerateKitLenses(mgr);
      const slot: LensSlot | undefined = pickDefaultLens(slots);
      if (slot === undefined) {
        Diag.w(WotaTag.ABILITY, 'preview: no camera device');
        return 'preview failed: no camera';
      }
      const device: camera.CameraDevice | undefined = findDeviceById(mgr, slot.logicId);
      if (device === undefined) {
        return `preview failed: device ${slot.logicId} gone`;
      }

      // 预览档：录像最大档（videoSizes[0]）作锚 + 预览表单表，core 规则挑选后反查 profile
      const capability: camera.CameraOutputCapability | undefined = outputCapabilityOf(mgr, device);
      const previewProfiles: Array<camera.Profile> = capability?.previewProfiles ?? [];
      const candidates: Array<Size> = previewProfiles.map((p: camera.Profile): Size =>
        new Size(p.size.width, p.size.height));
      const recordAnchor: Size | undefined = slot.ability.videoSizes[0];
      let chosen: Size | undefined = pickDirectPreviewSize(candidates, recordAnchor ?? new Size(0, 0), viewW, viewH);
      let profile: camera.Profile | undefined = profileForSize(previewProfiles, chosen);
      if (profile === undefined && previewProfiles.length > 0) {
        profile = previewProfiles[0]; // 兜底：挑选未命中时退预览表首档，宁可出画面不可黑屏
        chosen = new Size(profile.size.width, profile.size.height);
        Diag.w(WotaTag.ABILITY, `preview: profile pick fallback to ${chosen.width}x${chosen.height}`);
      }
      if (profile === undefined || chosen === undefined) {
        return `preview failed: no preview profile for ${slot.key}`;
      }

      const input: camera.CameraInput = mgr.createCameraInput(device);
      await input.open();
      const session: camera.PhotoSession = mgr.createSession<camera.PhotoSession>(camera.SceneMode.NORMAL_PHOTO);
      session.on('error', (err: BusinessError): void => {
        Diag.e(WotaTag.ABILITY, `session error: code=${err.code} ${err.message}`);
      });

      session.beginConfig();
      session.addInput(input);
      const preview: camera.PreviewOutput = mgr.createPreviewOutput(profile, surfaceId);
      session.addOutput(preview);
      await session.commitConfig();
      await session.start();

      this.input = input;
      this.preview = preview;
      this.session = session;

      // 会话期能力真值化（zoom/ev/flash/iso/ois，见 KitAbility.abilityRefineWithSession）后 dump
      const refined: CameraAbility = abilityRefineWithSession(slot.ability, session);
      refined.dump(slot.key);
      Diag.i(WotaTag.ABILITY,
        `preview started: lens=${slot.key} profile=${chosen.width}x${chosen.height} of ${previewProfiles.length}`);
      return `preview started\nlens=${slot.key} eq=${slot.eqFocal}mm\nprofile=${chosen.width}x${chosen.height}`;
    } catch (e) {
      Diag.e(WotaTag.ABILITY, `preview failed: ${e}`);
      return `preview failed: ${e}`;
    }
  }

  /** 关页收尾：stop → preview.release → input.close → 置空；逐项 try/catch，收尾不许抛 */
  async stop(): Promise<void> {
    try {
      if (this.session !== undefined) {
        await this.session.stop();
      }
    } catch (e) {
      Diag.w(WotaTag.ABILITY, `session stop: ${e}`);
    }
    try {
      if (this.session !== undefined) {
        await this.session.release();
      }
    } catch (e) {
      Diag.w(WotaTag.ABILITY, `session release: ${e}`);
    }
    try {
      if (this.preview !== undefined) {
        await this.preview.release();
      }
    } catch (e) {
      Diag.w(WotaTag.ABILITY, `preview release: ${e}`);
    }
    try {
      if (this.input !== undefined) {
        await this.input.close();
      }
    } catch (e) {
      Diag.w(WotaTag.ABILITY, `input close: ${e}`);
    }
    this.session = undefined;
    this.preview = undefined;
    this.input = undefined;
  }
}

function outputCapabilityOf(mgr: camera.CameraManager, device: camera.CameraDevice):
camera.CameraOutputCapability | undefined {
  try {
    return mgr.getSupportedOutputCapability(device);
  } catch (e) {
    return undefined;
  }
}
