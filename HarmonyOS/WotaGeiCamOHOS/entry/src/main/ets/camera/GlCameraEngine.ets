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
import { AeMode, Size, WotaTag } from '../core/Constants';
import { CameraAbility } from '../core/CameraAbility';
import { ExposureCorrection, ReadoutLatch } from '../core/ExposureCorrection';
import { LowFpsHint, LowFpsWatch } from '../core/LowFpsWatch';
import { WotaParams } from '../core/WotaParams';
import { LensSlot, LensType, lensTypeName, maxVideoPixels, pickCapabilityDefault } from '../core/LensSlot';
import { VideoSizeCandidates, sizeCloseTo } from '../core/VideoSizePicker';
import { enumerateKitLenses, findDeviceById, pickDefaultLens } from './KitLens';
import { abilityRefineWithSession } from './KitAbility';
import {
  ApplyReport, ExposurePlan, Snapshot, State, applySnapshot, exposurePlan, readoutKeyOf,
  snapshotOf, stateOf
} from './RequestApplier';
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

/** 等号档探测的热身拍数：等号请求生效 + HAL 换 FD 需要几拍，这几拍不采（上游 docs/plan/31 §3） */
const EXPOSURE_PROBE_WARMUP_FRAMES: number = 6;

/** 实测帧率采样窗（HUD 读数行的 fps 段；与上游帧率守卫同一拍采样口径，1s 一更） */
const ACTUAL_FPS_SAMPLE_MS: number = 1000;

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

  /** 参数总线（attachParams 注入；读出探测与实测遥测回写的读写面） */
  private params: WotaParams | undefined = undefined;

  // ---- 等号档读出探测（2026-11-01 用户定版，上游 docs/plan/31 §3；r42 批B 移植）----
  // 预览态发等号请求（probeActive=true ⇒ exposurePlan 余量 0，量程本身），用帧到达间隔的
  // **中位数** − 请求周期折算实际拉长量 Δ，闩锁进总线（键 = 镜头|画幅|fps）。三条纪律：
  // 录制目标在场立即退出（等号不进录制）；换档先热身几拍（等号请求生效 + HAL 换挡那几拍不采）；
  // 超时进冷却（帧流不足时防死循环重试）。平台登记：上游帧到达吃 TotalCaptureResult 的
  // SENSOR_TIMESTAMP（ns）；OHOS frameStart 无时间戳载荷 ⇒ 墙钟 ms × 1e6 折算（1ms 粒度）。
  private recordingActive: boolean = false;
  private exposureProbeActive: boolean = false;
  private exposureProbeKey: string | undefined = undefined;
  private exposureProbeIntervals: Array<number> = [];
  private exposureProbeLastMs: number = 0;
  private exposureProbeFramesSeen: number = 0;
  private exposureProbeStartMs: number = 0;
  private exposureProbeCooldownUntilMs: number = 0;

  // ---- 运行时帧率坍塌守卫（上游 91e6353+36917e5）----
  // 计数在 frameStart 累加；采样由 fpsSamplerTimer 的**独立 1s 定时拍**驱动——不挂在帧回调上：
  // HAL 完全断流（零回调）时帧路径整体停摆，挂在帧上的采样也会跟着失声，守卫对
  // 「一颗帧都不来」的病态反而哑火（上游 2026-10-28 审查补盲区）。定时拍读计数即可把
  // 零到达如实喂成 0fps。取景/录制全程运行；设备不在 OPEN_SUCCEED 时采样函数自己清提示。
  private camFrameCount: number = 0;
  private camRateLastMs: number = 0;
  private lowFpsWatch: LowFpsWatch | undefined = undefined;
  private fpsSamplerTimer: number = -1;

  /** 当前镜头方向信息（未起镜返回 undefined） */
  lastLensInfo(): GlLensInfo | undefined {
    return this.lensInfo;
  }

  /** 参数总线注入（取景页 aboutToAppear 调一次；不注入则读出探测与实测遥测停用） */
  attachParams(params: WotaParams): void {
    this.params = params;
  }

  /**
   * 录制态告知（等号不进录制的执行面）：上游在 setExtraSurface(录制面到场) 时取消探测；
   * OHOS 侧录制不另挂相机输出（GlRecorder 吃 GL 流），由取景页在起录/收尾两处告知。
   * 起录即取消探测并 markDirty 等价重下发（余量回到正常档）。
   */
  setRecordingActive(active: boolean): void {
    if (active && !this.recordingActive) {
      this.cancelExposureProbe('录制目标在场');
    }
    this.recordingActive = active;
  }

  /** 参数总线当前帧率档位（exposurePlan/纠偏判定的 fps 真源，与上游 fpsValue() 同名同义） */
  private fpsValue(): number {
    const params: WotaParams | undefined = this.params;
    return params === undefined ? 0 : params.fps.value.value;
  }

  /**
   * 预览帧到达（frameStart 回调面）：计数 + 等号档读出探测。回调无 err 时才算有效帧。
   */
  private onPreviewFrame(): void {
    const params: WotaParams | undefined = this.params;
    if (params === undefined || this.status !== DeviceStatus.OPEN_SUCCEED) {
      return;
    }
    this.camFrameCount++;
    this.feedExposureProbe(Date.now());
  }

  /**
   * 一拍实测到帧率喂守卫（fpsSamplerTimer 1s 定时拍；上游 91e6353 sampleCameraRate 直译）。
   * 状态翻转才写总线（LowFpsHint.sameAs 去重）；会话没开成功时不判也不留旧提示——
   * 取景器都不在，"帧率低"无从谈起。换档（目标变了）换新守卫：旧账的宽限/连续/触发态
   * 对新档位全是噪音。actualFps 同拍回写（r42 HUD 读数行的 fps 段与守卫同源同一拍）。
   */
  private sampleCameraRate(): void {
    const params: WotaParams | undefined = this.params;
    const now: number = Date.now();
    const last: number = this.camRateLastMs;
    this.camRateLastMs = now;
    const count: number = this.camFrameCount;
    this.camFrameCount = 0;
    if (params === undefined) {
      return;
    }
    if (this.status !== DeviceStatus.OPEN_SUCCEED) {
      this.lowFpsWatch = undefined;
      if (params.lowFpsHint.value !== undefined) {
        params.lowFpsHint.value = undefined;
      }
      if (params.actualFps.value !== undefined) {
        params.actualFps.value = undefined;
      }
      return;
    }
    const target: number = params.fps.value.value;
    if (target <= 0) {
      return;
    }
    if (this.lowFpsWatch === undefined || !this.lowFpsWatch.targets(target)) {
      this.lowFpsWatch = new LowFpsWatch(target);
    }
    if (last === 0) {
      return; // 首拍只立基准（这段窗口跨了会话建立，速率不可信）
    }
    const measured: number = count * 1000 / Math.max(1, now - last);
    params.actualFps.value = measured;
    const alarm: boolean = this.lowFpsWatch.onSample(now, measured);
    const hint: LowFpsHint | undefined =
      alarm ? new LowFpsHint(target, Math.max(1, Math.round(measured))) : undefined;
    const prev: LowFpsHint | undefined = params.lowFpsHint.value;
    const unchanged: boolean = hint === undefined ? prev === undefined : hint.sameAs(prev);
    if (!unchanged) {
      Diag.i(WotaTag.ABILITY,
        `帧率守卫：target=${target}fps 实测=${measured.toFixed(1)}fps ⇒ ${alarm ? '提示' : '解除'}`);
      params.lowFpsHint.value = hint;
    }
  }

  /** 守卫采样定时拍（start 建链成功后开；stop 收口；幂等） */
  private startFpsSampler(): void {
    this.stopFpsSampler();
    this.fpsSamplerTimer = setInterval((): void => {
      this.sampleCameraRate();
    }, ACTUAL_FPS_SAMPLE_MS);
  }

  private stopFpsSampler(): void {
    if (this.fpsSamplerTimer >= 0) {
      clearInterval(this.fpsSamplerTimer);
      this.fpsSamplerTimer = -1;
    }
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
            this.onPreviewFrame();
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
      this.startFpsSampler(); // 帧率守卫独立采样拍（零回调盲区补丁，36917e5）
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
   * 等号档读出探测（上游 docs/plan/31 §3，2026-11-01 用户定版）：挂在 frameStart 每帧回执上。
   * 条件齐了才采集——手动 AE + 自动纠偏开 + 强制等号档 + 预览态（无录制目标）+ 本配置没测过；
   * 条件一破立刻退出探测态（等号请求不能漏进任何别的状态）。前 [EXPOSURE_PROBE_WARMUP_FRAMES]
   * 拍是换挡热身（等号请求生效 + HAL 换 FD 需要几拍），不采；采满 [ExposureCorrection.PROBE_SAMPLES]
   * 个间隔即折算中位数落闩锁，下一拍下发就带纠偏。
   * 平台差异（对上游 feedExposureProbe）：OHOS 无逐帧 repeating request——「下一拍下发就带
   * 纠偏」落在**重下发**上（probe 态翻转后的下一次 applyParams：探测开始/取消两处各触发一次，
   * 弹层改动/起录前的既有下发点也会带上最新快照）。
   */
  private feedExposureProbe(nowMs: number): void {
    const params: WotaParams | undefined = this.params;
    if (params === undefined) {
      return;
    }
    const key: string = readoutKeyOf(params);
    const shouldProbe: boolean = this.status === DeviceStatus.OPEN_SUCCEED &&
      !this.recordingActive &&
      params.aeMode.value === AeMode.MANUAL &&
      params.exposureCorrectionAuto.value &&
      ExposureCorrection.applicable(params.shutter.value.value, this.fpsValue()) &&
      (params.readoutLatch.value === undefined || params.readoutLatch.value.key !== key);
    if (!shouldProbe) {
      this.cancelExposureProbe('条件退出');
      return;
    }
    if (nowMs < this.exposureProbeCooldownUntilMs) {
      return;
    }
    if (this.exposureProbeKey !== key) {
      this.exposureProbeKey = key;
      this.exposureProbeIntervals = [];
      this.exposureProbeLastMs = 0;
      this.exposureProbeFramesSeen = 0;
      this.exposureProbeStartMs = nowMs;
      this.exposureProbeActive = true;
      this.applyParams(params); // markDirty 等价：下一拍起发等号请求（量程本身）；不重下发这一场还带着余量
      Diag.i(WotaTag.ABILITY, `等号档读出探测开始 key=${key}（预览态发等号请求；录制目标在场即中止）`);
    }
    this.exposureProbeFramesSeen++;
    if (this.exposureProbeFramesSeen <= EXPOSURE_PROBE_WARMUP_FRAMES) {
      return;
    }
    if (this.exposureProbeLastMs > 0 && nowMs > this.exposureProbeLastMs) {
      this.exposureProbeIntervals.push((nowMs - this.exposureProbeLastMs) * 1_000_000);
    }
    this.exposureProbeLastMs = nowMs;
    if (this.exposureProbeIntervals.length >= ExposureCorrection.PROBE_SAMPLES) {
      const delta: number = ExposureCorrection.probeDeltaNs(
        this.exposureProbeIntervals, ExposureCorrection.framePeriodNs(this.fpsValue()));
      params.readoutLatch.value = new ReadoutLatch(key, delta);
      Diag.i(WotaTag.ABILITY,
        `等号档读出探测完成 key=${key} Δ=${delta}ns（${(delta / 1_000_000).toFixed(2)}ms）` +
        `samples=${this.exposureProbeIntervals.length}`);
      this.cancelExposureProbe('探测完成');
    } else if (nowMs - this.exposureProbeStartMs > ExposureCorrection.PROBE_TIMEOUT_MS) {
      this.exposureProbeCooldownUntilMs = nowMs + ExposureCorrection.PROBE_COOLDOWN_MS;
      this.cancelExposureProbe(`超时（帧流不足），冷却 ${ExposureCorrection.PROBE_COOLDOWN_MS}ms 后重试`);
    }
  }

  /** 退出探测态并触发一次重下发（probeActive 翻回 false ⇒ exposurePlan 恢复正常余量） */
  private cancelExposureProbe(why: string): void {
    if (!this.exposureProbeActive && this.exposureProbeKey === undefined) {
      return;
    }
    this.exposureProbeActive = false;
    this.exposureProbeKey = undefined;
    this.exposureProbeIntervals = [];
    this.exposureProbeLastMs = 0;
    this.exposureProbeFramesSeen = 0;
    Diag.i(WotaTag.ABILITY, `等号档探测退出：${why}`);
    const params: WotaParams | undefined = this.params;
    if (params !== undefined) {
      this.applyParams(params);
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
      const snap: Snapshot = snapshotOf(params, state);
      // 等号档探测（上游 docs/plan/31）：probeActive 是引擎私有态，快照组装后回填
      snap.probeActive = this.exposureProbeActive;
      const report: ApplyReport = applySnapshot(session, state, snap);
      // 曝光下发的**真值**回写（上游 docs/plan/31，HUD 实测读数行）：手动档才有曝光可言
      // （自动档曝光键缺席 ⇒ undefined）；纠偏值 = 帧周期 − 实发，非等号档不适用 ⇒ undefined。
      // 单位登记：setExposureDuration 吃 µs（亚微秒钳 1），µs 取整后的值才是实际生效真值。
      const plan: ExposurePlan = exposurePlan(state, snap);
      const manual: boolean = params.aeMode.value === AeMode.MANUAL;
      if (manual) {
        const sentNs: number = Math.max(1, Math.round(plan.exposureNs / 1000)) * 1000;
        params.actualSentExposureNs.value = sentNs;
        params.actualCorrectionNs.value =
          ExposureCorrection.applicable(params.shutter.value.value, fps)
            ? Math.max(0, ExposureCorrection.framePeriodNs(fps) - sentNs)
            : undefined;
      } else {
        params.actualSentExposureNs.value = undefined;
        params.actualCorrectionNs.value = undefined;
      }
      return report;
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
    // 等号档探测与实测遥测一并清账（上游口径：deviceStatus 退出 OPEN_SUCCEED 时清空）
    this.stopFpsSampler();
    this.lowFpsWatch = undefined;
    this.camFrameCount = 0;
    this.camRateLastMs = 0;
    this.recordingActive = false;
    this.exposureProbeActive = false;
    this.exposureProbeKey = undefined;
    this.exposureProbeIntervals = [];
    this.exposureProbeLastMs = 0;
    this.exposureProbeFramesSeen = 0;
    this.exposureProbeCooldownUntilMs = 0;
    const params: WotaParams | undefined = this.params;
    if (params !== undefined) {
      params.actualSentExposureNs.value = undefined;
      params.actualCorrectionNs.value = undefined;
      params.actualFps.value = undefined;
      params.lowFpsHint.value = undefined;
    }
  }
}
