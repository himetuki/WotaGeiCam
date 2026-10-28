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
import { LensSlot } from '../core/LensSlot';
import { CameraAbility } from '../core/CameraAbility';
import { WotaParams } from '../core/WotaParams';
import { pickDirectPreviewSize, sizeCloseTo, VideoSizeCandidates } from '../core/VideoSizePicker';
import { enumerateKitLenses, findDeviceById, pickDefaultLens } from './KitLens';
import { abilityRefineWithSession } from './KitAbility';
import { previewRangeFor } from './GlCameraEngine';
import { ApplyReport, State, applySnapshot, snapshotOf, stateOf } from './RequestApplier';

/** 默认档：归档结果里第一个 WIDE（多后摄按焦距归档后广角稳定在 WIDE）；无 WIDE 则第一个非前摄。
 * 预览与录制两个引擎共用，实现落在 KitLens（镜头归档语义归属）。 */

/** 预览档反查：pickDirectPreviewSize 选中的 Size → 命中的 previewProfile（±TOLERANCE_PX） */
function profileForSize(profiles: Array<camera.Profile>, size: Size | undefined): camera.Profile | undefined {
  if (size === undefined) {
    return undefined;
  }
  return profiles.find((p: camera.Profile): boolean =>
    sizeCloseTo(new Size(p.size.width, p.size.height), size, VideoSizeCandidates.TOLERANCE_PX));
}

/**
 * 会话口径帧率代理：预览 profile 无 fps 字段，取**同一尺寸**视频档 frameRateRange.max 的最大值；
 * 无匹配档返回 0（applyParams 回退 params.fps 档位）。
 */
function sessionFpsMaxOf(capability: camera.CameraOutputCapability | undefined, size: Size): number {
  let maxFps: number = 0;
  const profiles: Array<camera.VideoProfile> = capability?.videoProfiles ?? [];
  for (const p of profiles) {
    if (!sizeCloseTo(new Size(p.size.width, p.size.height), size, VideoSizeCandidates.TOLERANCE_PX)) {
      continue;
    }
    if (p.frameRateRange !== undefined && p.frameRateRange.max > maxFps) {
      maxFps = p.frameRateRange.max;
    }
  }
  return maxFps;
}

/**
 * 预览引擎：一次 start = 一套会话（input + previewOutput + PhotoSession）；
 * stop 逐项收尾（stop→release/close），任何一步失败不抛、不阻塞后续清理。
 */
export class PreviewEngine {
  private session: camera.PhotoSession | undefined = undefined;
  private preview: camera.PreviewOutput | undefined = undefined;
  private input: camera.CameraInput | undefined = undefined;
  /** 会话期能力真值快照（applyParams 的 State 来源；随会话生命周期存续） */
  private refinedAbility: CameraAbility | undefined = undefined;
  /** 实际生效的预览尺寸（applyParams 的 size 口径；stop 清空） */
  private chosenSize: Size | undefined = undefined;
  /**
   * 会话口径帧率代理（applyParams 的 fps 口径；stop 清零）：
   * 预览 profile 无 fps 字段，取同尺寸视频档 frameRateRange.max 的最大值作实际会话口径。
   */
  private sessionFpsMax: number = 0;

  /**
   * 开预览。返回人读状态摘要（页面直接显示）；失败不抛——Diag 落错 + 返回 "preview failed: ..."。
   * @param surfaceId XComponent(SURFACE) 的 surfaceId（onLoad 时由 controller 取得）
   * @param viewW/viewH 承载视图像素宽高；未布局传 0（core 的 pickDirectPreviewSize 会退到最大档）
   * @param fps 可选预览帧率（>0 才下发；开发者页自检用，缺省不动）
   */
  async start(ctx: common.Context, surfaceId: string, viewW: number, viewH: number,
    fps?: number): Promise<string> {
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
      // 预览帧率（SDK 12+ 的 PreviewOutput.setFrameRate）：失败只 Diag.w，不阻塞出画面
      if (fps !== undefined && fps > 0) {
        this.applyFpsTo(preview, fps);
      }

      // 会话期能力真值化（zoom/ev/iso/af/awb/flash/exposure，见 KitAbility.abilityRefineWithSession）后 dump
      const refined: CameraAbility = abilityRefineWithSession(slot.ability, session);
      this.refinedAbility = refined;
      this.chosenSize = chosen;
      this.sessionFpsMax = sessionFpsMaxOf(capability, chosen);
      refined.dump(slot.key);
      Diag.i(WotaTag.ABILITY,
        `preview started: lens=${slot.key} profile=${chosen.width}x${chosen.height} of ${previewProfiles.length}`);
      return `preview started\nlens=${slot.key} eq=${slot.eqFocal}mm\nprofile=${chosen.width}x${chosen.height}`;
    } catch (e) {
      Diag.e(WotaTag.ABILITY, `preview failed: ${e}`);
      return `preview failed: ${e}`;
    }
  }

  /** 活设置预览帧率（开发者页自检）：无会话/无匹配区间/下发失败返回 false */
  applyPreviewFps(fps: number): boolean {
    const preview: camera.PreviewOutput | undefined = this.preview;
    if (preview === undefined) {
      return false;
    }
    return this.applyFpsTo(preview, fps);
  }

  /** 当前会话预览支持的帧率（各区间上界去重升序；无会话/查询失败返回空表） */
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
      Diag.w(WotaTag.ABILITY, `preview supported fps query failed: ${e}`);
      return [];
    }
  }

  private applyFpsTo(preview: camera.PreviewOutput, fps: number): boolean {
    if (!(fps > 0)) {
      return false;
    }
    try {
      const best: camera.FrameRateRange | undefined = previewRangeFor(preview.getSupportedFrameRates(), fps);
      if (best === undefined) {
        Diag.w(WotaTag.ABILITY, `preview fps ${fps} unsupported by declared ranges`);
        return false;
      }
      preview.setFrameRate(best.min, best.max);
      Diag.i(WotaTag.ABILITY, `preview fps ${fps} -> [${best.min},${best.max}]`);
      return true;
    } catch (e) {
      Diag.w(WotaTag.ABILITY, `preview setFrameRate ${fps} failed: ${e}`);
      return false;
    }
  }

  /**
   * 参数下发（HUD/自检入口）：按参数总线当前快照逐键 apply，返回报告摘要。
   * 会话未就绪时返回提示串（页面直接显示）；异常兜底不抛。
   */
  applyParams(params: WotaParams): string {
    try {
      if (this.session === undefined || this.refinedAbility === undefined) {
        return 'param apply skipped: no session';
      }
      // 会话实际口径：size 用本会话选中的预览尺寸，fps 用同尺寸视频档 max（预览 profile 无 fps 字段）
      const effectiveSize: Size = this.chosenSize ?? params.size.value;
      const effectiveFps: number = this.sessionFpsMax > 0 ? this.sessionFpsMax : params.fps.value.value;
      // highSpeed 判据与总线落档/下发钳制同源（core.needsHighSpeed：普通表先来、覆盖不了才看高速表），
      // 不再按「档位数值 > 30」猜——厂商把 50/60 放在普通表时不该被当成高速会话（上游 2026-10-26 口径）
      const highSpeed: boolean = this.refinedAbility.needsHighSpeed(effectiveSize, effectiveFps);
      const state: State = stateOf(
        this.refinedAbility, effectiveSize, highSpeed, this.refinedAbility.evStep);
      const report: ApplyReport = applySnapshot(this.session, state, snapshotOf(params, state));
      return report.summary();
    } catch (e) {
      Diag.e(WotaTag.ABILITY, `param apply failed: ${e}`);
      return `param apply failed: ${e}`;
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
    this.refinedAbility = undefined;
    this.chosenSize = undefined;
    this.sessionFpsMax = 0;
  }
}

function outputCapabilityOf(mgr: camera.CameraManager, device: camera.CameraDevice):
camera.CameraOutputCapability | undefined {
  try {
    // mode 版（无 mode 版自 API 11 废弃）；预览会话即 PhotoSession，按 NORMAL_PHOTO 采集
    return mgr.getSupportedOutputCapability(device, camera.SceneMode.NORMAL_PHOTO);
  } catch (e) {
    return undefined;
  }
}
