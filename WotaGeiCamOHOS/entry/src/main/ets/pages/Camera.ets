/**
 * 主录制页最小版（上游 CameraScreen.kt 的最小集合）：
 * 全屏 GL 预览 + 固定 HUD（顶栏两行读数/3×3 网格/底栏控制）+ 参数胶囊
 * （镜头/画幅/帧率/变焦/闪光/效果/光弧/网格点按循环；快门/ISO/EV/WB 走 PillPopup 就近浮层）
 * + GPU 录制（GlRecorder + AutoStop + 500ms 宽限 + 分段轮转 + sidecar/FrameLedger）。
 *
 * T21 高级参数批（对齐上游 38d712a）：WotaParams 参数总线 + 起镜后 applyAbility(refined) +
 * 起录前/弹层改动 engine.applyParams（RequestApplier 逐键报告）；顶栏第二行 快门/ISO/EV/WB 读数
 * （AE AUTO 时 ISO 显示 AUTO、EV 仅非 MANUAL 显示、wbShort 预设缩写+色温、能力缺失显示 -）。
 * 录制门控走 ParamPills.RECORDING_EDITABLE_PILLS（{ISO,EV,WB}；上游含 ZOOM/FOCUS——
 * 两颗胶囊本批未移植，差异登记 PORT_MAP §ui）。
 *
 * 边界（登记）：固定 HUD，不移植 HudLayout/HudLayoutEditor（READOUT 非可编排网格）；
 * PillPopup 为最小实现（无动效/Tokens/文字缩放层/SheetSpec）；高级参数不持久化（会话内有效，
 * 上游 ParamsPersist 留白）；tint 副轴/对焦弹层留白；视频-only；设置持久化走 ui/SettingsStore
 * （键独立于上游 ParamsPersist）。
 * 加载顺序契约：不得页面顶层静态 import 'libentry.so'，XComponent onLoad 后 dynamic import 桥。
 */
import { common } from '@kit.AbilityKit';
import { display, window } from '@kit.ArkUI';
import { batteryInfo } from '@kit.BasicServicesKit';
import { Diag } from '../diag/Diag';
import { camera } from '@kit.CameraKit';
import { GlCameraEngine, GlLensInfo } from '../camera/GlCameraEngine';
import { GlRecordLib, GlRecorder } from '../camera/GlRecorder';
import { requestCameraPermission } from '../camera/PermGate';
import { CurveStack } from '../core/CurveModel';
import { AeMode, ArcMergeDeadband, FrameEffect, PeakingColor, Size } from '../core/Constants';
import { CameraAbility } from '../core/CameraAbility';
import { ParamState, WotaParams } from '../core/WotaParams';
import { ApplyReport } from '../camera/RequestApplier';
import { AutoStopMonitor } from '../record/AutoStopMonitor';
import { AutoStopRules } from '../record/AutoStopRules';
import {
  FULL_WINDOW_MS, HealthVerdict, MAX_RESTART_ATTEMPTS, RecordHealthWindow, RetryAction,
  defaultRecordHealthWindow, healthVerdict, retryActionOf
} from '../record/RecordHealth';
import { ArcDropLog } from '../record/ArcDropLog';
import { WOTA_RECORD_TAG } from '../record/RecordResult';
import { DeviceStatus } from '../camera/DeviceStatus';
import { BitratePolicy } from '../record/BitratePolicy';
import { VideoStore } from '../record/VideoStore';
import { CameraSettings, SettingsStore } from '../ui/SettingsStore';
import { HudSpec } from '../ui/HudSpec';
import { evText, shutterText, wbShort } from '../ui/ParamFormat';
import { PillRect } from '../ui/PillPopup';
import {
  EvPillPopup, IsoPillPopup, ShutterPillPopup, WbPillPopup, pillEditableDuringRecording
} from '../ui/ParamPills';
import { LensSlot } from '../core/LensSlot';

interface GlLibApi extends GlRecordLib {
  getInputSurfaceId: () => string;
  getNativeImageError: () => string;
  setDisplayGeometry: (sensorDeg: number, displayDeg: number, mirrored: boolean) => void;
  setEffect: (effect: number) => void;
  setCurveEnabled: (on: boolean) => void;
  setZebraParams: (threshold: number, density: number) => void;
  setPeakingParams: (strength: number, colorIndex: number) => void;
  uploadCurveLut: (data: ArrayBuffer) => boolean;
  setFrostEnabled: (on: boolean) => void;
}

const POLL_INTERVAL_MS: number = 100;
const POLL_TIMEOUT_MS: number = 5000;
const RECORD_WIDTH_1080: number = 1920;
const RECORD_HEIGHT_1080: number = 1080;
const RECORD_WIDTH_4K: number = 3840;
const RECORD_HEIGHT_4K: number = 2160;
const ZOOM_LEVELS: Array<number> = [1, 2, 3];
const START_COOLDOWN_MS: number = 1000;

function autoReasonText(reason: string, saved: boolean): string {
  const suffix: string = saved ? '成片已保存' : '自动停录，但收尾/落盘可能失败';
  if (reason === AutoStopRules.REASON_BATTERY) {
    return `电量过低，${suffix}`;
  }
  if (reason === AutoStopRules.REASON_STORAGE) {
    return `存储不足，${suffix}`;
  }
  if (reason === AutoStopRules.REASON_CAMERA_LOST) {
    return saved ? '相机被收回，成片已保存（需重新进入本页恢复相机）'
      : '相机被收回，自动停录，但收尾/落盘可能失败（需重新进入本页恢复相机）';
  }
  return reason === '' ? '' : (saved ? '自动停录，成片已保存' : '自动停录，但收尾/落盘可能失败');
}

function formatElapsed(ms: number): string {
  const total: number = Math.max(0, Math.floor(ms / 1000));
  const m: number = Math.floor(total / 60);
  const s: number = total % 60;
  return `${m < 10 ? '0' + m : m}:${s < 10 ? '0' + s : s}`;
}

@Entry
@Component
struct CameraPage {
  @State message: string = '';
  @State loading: boolean = true;
  @State recording: boolean = false;
  @State stopPending: boolean = false;
  @State glFatal: string = '';
  @State permissionGranted: boolean = false;
  @State cameraStarted: boolean = false;
  @State lensLabel: string = '—';
  @State sizeLabel: string = '—';
  @State fpsLabel: string = '30';
  @State effectLabel: string = '直通';
  @State gridOn: boolean = true;
  @State elapsedMs: number = 0;
  @State batteryPct: number = -1;
  @State freeMb: number = -1;
  @State zoomLabel: string = '1x';
  @State flashOn: boolean = false;
  @State startingCamera: boolean = false;
  @State permissionRequesting: boolean = false;

  private engine: GlCameraEngine = new GlCameraEngine();
  private recorder: GlRecorder = new GlRecorder();
  private glLib: GlLibApi | undefined = undefined;
  private ctx: common.Context | undefined = undefined;
  private settings: CameraSettings = new CameraSettings();
  private settingsLoaded: boolean = false;
  private lenses: Array<LensSlot> = [];
  private lensKey: string = '';
  private effectIndex: number = 0;
  private zoomIndex: number = 0;
  private surfaceId: string = '';
  private pollTimer: number = -1;
  private statusTimer: number = -1;
  private recordTimer: number = -1;
  private autoStopMonitor: AutoStopMonitor | undefined = undefined;
  private disposed: boolean = false;
  /** 页面可见性：隐藏后 async 设置/权限/起镜链不得再拉起相机 */
  private pageVisible: boolean = true;
  private healthTimer: number = -1;
  private healthStartMs: number = 0;
  private healthAttempts: number = 0;
  private healthWindow: RecordHealthWindow = defaultRecordHealthWindow();
  private healthHealthy: boolean = false;
  private healthPausedByHide: boolean = false;
  private lastReadoutMs: number = 0;
  private recordStartedAtMs: number = 0;
  private stopGraceTimer: number = -1;
  private stopReason: string = '';
  private lastStopSummary: string = '';
  private lastStopAtMs: number = 0;
  /** 高级参数总线（T21）：会话内有效，不持久化（上游 ParamsPersist 留白） */
  private params: WotaParams = new WotaParams();
  /** 最近一次会话期能力真值（起镜成功/开弹层时刷新；停镜置空） */
  private ability: CameraAbility | undefined = undefined;
  /** 当前打开的高级参数弹层（'' = 无）：快门/ISO/EV/WB */
  @State activePill: string = '';
  /** 弹层锚点（窗口 px；零矩形 = 未量到，PillPopup 内部走底贴屏回退） */
  private pillAnchor: PillRect = { left: 0, top: 0, right: 0, bottom: 0 };
  /** 顶栏第二行读数（参数流驱动，subscribe 里重算） */
  @State rowShutter: string = '-';
  @State rowIso: string = '-';
  @State rowEv: string = '-';
  @State rowWb: string = '-';
  /** 高级参数胶囊当前值标签 */
  @State pillShutterLabel: string = 'AUTO';
  @State pillIsoLabel: string = 'AUTO';
  @State pillEvLabel: string = '+0.0EV';
  @State pillWbLabel: string = '自动';

  aboutToAppear(): void {
    this.disposed = false;
    this.pageVisible = true;
    this.ctx = this.getUIContext().getHostContext() as common.Context | undefined;
    this.bindParamsLabels();
    this.engine.setOnCameraLost((): void => {
      if (this.disposed) {
        return;
      }
      this.cameraStarted = false;
      this.resetCameraLabels();
      if (this.recording || this.stopPending) {
        this.requestStop(AutoStopRules.REASON_CAMERA_LOST);
        return;
      }
      this.message = '相机已断开';
    });
    try {
      display.on('change', (_id: number): void => {
        this.applyGeometry();
      });
    } catch (e) {
    }
    this.bootstrap();
  }

  onPageShow(): void {
    this.pageVisible = true;
    if (this.disposed) {
      return;
    }
    this.setKeepScreenOn(true);
    this.resumeHealthAfterShow();
    const ctx: common.Context | undefined = this.ctx;
    if (ctx === undefined) {
      return;
    }
    SettingsStore.load(ctx).then((s: CameraSettings): void => {
      if (this.disposed || !this.pageVisible || this.recording) {
        return;
      }
      const sizeChanged: boolean = s.sizeW !== this.settings.sizeW || s.sizeH !== this.settings.sizeH;
      this.settings = s;
      this.syncLabels();
      this.initNativeParams();
      if (this.recording) {
        return;
      }
      if (!this.permissionGranted) {
        this.ensureCameraPermission();
        return;
      }
      if (sizeChanged && this.cameraStarted) {
        this.restartCamera();
      } else if (!this.cameraStarted) {
        this.tryStartCamera();
      }
    });
  }

  onPageHide(): void {
    this.pageVisible = false;
    this.cancelTapTimers();
    if (this.recording && !this.healthHealthy && this.healthTimer >= 0) {
      this.healthPausedByHide = true;
    }
    this.stopHealthMonitor(); // 页面隐藏统一停健康巡检（录制中亦不后台读 native）
    if (!this.recording) {
      this.stopStatusTimer();
      this.stopHealthMonitor();
      this.stopPolling();
      this.engine.stop();
      this.cameraStarted = false;
      this.setKeepScreenOn(false);
    }
  }

  aboutToDisappear(): void {
    this.disposed = true;
    this.pageVisible = false;
    this.stopStatusTimer();
    this.stopRecordMonitor();
    this.stopAutoStopMonitor();
    this.stopHealthMonitor();
    if (this.stopGraceTimer >= 0) {
      clearTimeout(this.stopGraceTimer);
      this.stopGraceTimer = -1;
    }
    if (this.recorder.isRecording()) {
      this.finishRecord();
    }
    this.engine.stop();
    this.stopPolling();
    this.setKeepScreenOn(false);
    try {
      display.off('change');
    } catch (e) {
    }
  }

  private cancelTapTimers(): void {
    // 主页面最小版只有显式按钮，无单击/双击手势定时器；保留钩子便于后续 HUD 手势批。
  }

  private bootstrap(): void {
    const ctx: common.Context | undefined = this.ctx;
    if (ctx === undefined) {
      this.loading = false;
      this.message = 'no host context';
      return;
    }
    SettingsStore.load(ctx).then((s: CameraSettings): void => {
      if (this.disposed) {
        return;
      }
      this.settings = s;
      this.settingsLoaded = true;
      this.gridOn = s.grid;
      this.effectIndex = s.effect;
      this.syncLabels();
      if (!this.pageVisible) {
        return; // 隐藏页只落设置，不请求权限/起镜；onPageShow 补发
      }
      this.ensureCameraPermission();
    }).catch((e: Object): void => {
      this.loading = false;
      this.message = `读取设置失败: ${e}`;
    });
  }

  private syncLabels(): void {
    this.fpsLabel = `${this.settings.fps}`;
    this.sizeLabel = this.settings.sizeW >= 3840 ? '4K' : '1080p';
    const labels: Array<string> = ['直通', '曲线', '斑马', '峰值'];
    this.effectIndex = this.settings.effect;
    this.effectLabel = labels[this.effectIndex];
    this.gridOn = this.settings.grid;
  }

  private loadNativeModule(): void {
    import('../camera/GlLibBridge').then((mod: ESObject): void => {
      if (this.disposed) {
        return;
      }
      this.glLib = mod as GlLibApi;
      this.initNativeParams();
      this.startPolling();
    }).catch((e: Object): void => {
      this.loading = false;
      this.message = `native module load failed: ${e}`;
    });
  }

  private startPolling(): void {
    this.stopPolling();
    const startMs: number = Date.now();
    this.pollTimer = setInterval((): void => {
      if (this.disposed) {
        this.stopPolling();
        return;
      }
      const lib: GlLibApi | undefined = this.glLib;
      if (lib === undefined) {
        return;
      }
      let surfaceId: string = '';
      try {
        surfaceId = lib.getInputSurfaceId();
      } catch (e) {
        surfaceId = '';
      }
      if (surfaceId !== '') {
        this.stopPolling();
        this.surfaceId = surfaceId;
        this.tryStartCamera();
        return;
      }
      let nativeError: string = '';
      try {
        nativeError = lib.getNativeImageError();
      } catch (e) {
        nativeError = '';
      }
      if (nativeError !== '') {
        this.stopPolling();
        this.loading = false;
        this.message = `GL native image failed: ${nativeError}`;
        return;
      }
      if (Date.now() - startMs > POLL_TIMEOUT_MS) {
        this.stopPolling();
        this.loading = false;
        this.message = 'GL surface 未就绪（超时）';
      }
    }, POLL_INTERVAL_MS);
  }

  private stopPolling(): void {
    if (this.pollTimer >= 0) {
      clearInterval(this.pollTimer);
      this.pollTimer = -1;
    }
  }

  /** 权限请求唯一入口：disposed/pageVisible/permissionRequesting/settingsLoaded 四重门控 */
  private ensureCameraPermission(): void {
    const ctx: common.Context | undefined = this.ctx;
    if (this.disposed || !this.pageVisible || this.permissionRequesting ||
      this.permissionGranted || !this.settingsLoaded || ctx === undefined) {
      return;
    }
    this.permissionRequesting = true;
    requestCameraPermission(ctx).then((granted: boolean): void => {
      this.permissionRequesting = false;
      if (this.disposed) {
        return;
      }
      this.permissionGranted = granted;
      this.loading = false;
      if (!granted) {
        this.message = '相机权限被拒绝，点按重试';
        return;
      }
      if (!this.pageVisible) {
        return;
      }
      this.tryStartCamera();
    }).catch((e: Object): void => {
      this.permissionRequesting = false;
      this.loading = false;
      if (this.pageVisible) {
        this.message = `权限请求失败: ${e}`;
      }
    });
  }

  private tryStartCamera(): void {
    const ctx: common.Context | undefined = this.ctx;
    if (this.disposed || !this.pageVisible || !this.permissionGranted || !this.settingsLoaded ||
      !this.glLib || this.surfaceId === '' || this.startingCamera || this.cameraStarted) {
      return;
    }
    if (ctx === undefined) {
      return;
    }
    this.startingCamera = true;
    this.engine.listLenses(ctx).then((slots: Array<LensSlot>): Promise<string> => {
      this.lenses = slots;
      if (this.disposed || !this.pageVisible) {
        this.startingCamera = false;
        return Promise.resolve('cancelled');
      }
      const want: Size = new Size(this.settings.sizeW, this.settings.sizeH);
      return this.engine.start(ctx, this.surfaceId, this.lensKey, want, this.settings.fps);
    }).then((summary: string): void => {
      this.startingCamera = false;
      if (summary === 'cancelled') {
        return;
      }
      if (this.disposed || !this.pageVisible) {
        this.engine.stop(); // 起镜途中页面隐藏/销毁：不保留后台会话
        return;
      }
      if (summary.indexOf('gl camera started') !== 0) {
        this.cameraStarted = false;
        this.message = summary;
        return;
      }
      this.cameraStarted = true;
      this.loading = false;
      const lens: LensSlot | undefined = this.engine.currentLens();
      if (lens !== undefined) {
        this.lensKey = lens.key;
        this.lensLabel = this.lensName(lens);
      }
      const actual: Size | undefined = this.engine.currentSize();
      if (actual !== undefined) {
        this.sizeLabel = actual.width >= 3840 ? '4K' : '1080p';
        const requestedLarge: boolean = this.settings.sizeW >= 3840;
        const actualLarge: boolean = actual.width >= 3840;
        if (requestedLarge && !actualLarge) {
          this.message = '该镜头不支持 4K 预览，已回退 1080p';
          this.settings.sizeW = RECORD_WIDTH_1080;
          this.settings.sizeH = RECORD_HEIGHT_1080;
          this.persistSettings();
        }
      }
      const initialZoom: number = this.engine.currentZoom();
      this.zoomIndex = this.nearestZoomIndex(initialZoom);
      this.zoomLabel = `${initialZoom}x`;
      this.applyGeometry();
      this.initNativeParams();
      // T21：起镜成功后能力落档（参数总线按会话期能力重算 range 并钳制当前值）；
      // fps 先对齐设置档（applyAbility 以总线 fps 档位为快门上限基准）
      const refined: CameraAbility | undefined = this.engine.refinedAbility();
      if (refined !== undefined) {
        this.ability = refined;
        this.params.fps.value = ParamState.of(this.settings.fps);
        this.params.applyAbility(refined);
        this.syncParamLabels();
      }
      this.startStatusTimer();
      this.message = this.message === '' ? '取景就绪' : this.message;
    }).catch((e: Object): void => {
      this.startingCamera = false;
      this.cameraStarted = false;
      if (this.pageVisible) {
        this.message = `相机启动失败: ${e}`;
      }
    });
  }

  private restartCamera(): void {
    if (this.recording || this.startingCamera || !this.pageVisible) {
      return;
    }
    this.engine.stop().then((): void => {
      this.cameraStarted = false;
      this.tryStartCamera();
    });
  }

  private lensName(lens: LensSlot): string {
    switch (lens.type) {
      case 0: return '超广角';
      case 1: return '广角';
      case 2: return '长焦';
      case 3: return '超长焦';
      case 5: return '前置';
      default: return '镜头';
    }
  }

  private appendNotice(text: string): void {
    if (text === '') {
      return;
    }
    if (this.message === '') {
      this.message = text;
    } else if (this.message.indexOf(text) < 0) {
      this.message = `${this.message}（${text}）`;
    }
  }

  /** 参数流 → 读数/胶囊标签的同步（T21）：任何一次写值都会触发重算 */
  private bindParamsLabels(): void {
    const recompute = (): void => {
      this.syncParamLabels();
    };
    this.params.aeMode.subscribe(recompute);
    this.params.shutter.subscribe(recompute);
    this.params.iso.subscribe(recompute);
    this.params.ev.subscribe(recompute);
    this.params.wbMode.subscribe(recompute);
    this.params.kelvin.subscribe(recompute);
    this.syncParamLabels();
  }

  /** 快照当前参数到顶栏第二行/胶囊标签（AE AUTO 时 ISO 显示 AUTO、EV 仅非 MANUAL 显示、能力缺失 -） */
  private syncParamLabels(): void {
    const ae: AeMode = this.params.aeMode.value;
    const manual: boolean = ae === AeMode.MANUAL;
    const shutterRangeOk: boolean = this.params.shutter.value.range !== undefined;
    const isoRangeOk: boolean = this.params.iso.value.range !== undefined;
    const evRangeOk: boolean = this.params.ev.value.range !== undefined;
    const evStep: number = this.ability !== undefined && this.ability.evStep > 0 ? this.ability.evStep : 1;
    this.rowShutter = manual ? (shutterRangeOk ? shutterText(this.params.shutter.value.value) : '-') : 'AUTO';
    this.pillShutterLabel = this.rowShutter;
    this.rowIso = manual ? (isoRangeOk ? `${Math.round(this.params.iso.value.value)}` : '-') : 'AUTO';
    this.pillIsoLabel = this.rowIso;
    this.rowEv = manual ? '-' :
      (evRangeOk ? evText(this.params.ev.value.value, evStep) : '-');
    this.pillEvLabel = this.rowEv;
    this.rowWb = wbShort(this.params.wbMode.value, Math.round(this.params.kelvin.value.value));
    this.pillWbLabel = this.rowWb;
  }

  /**
   * 弹层改动下发（T21）：engine.applyParams 逐键报告；failed 项 Diag.w + 用户提示，
   * skipped 摘要剔除恒在的 tint（无 OHOS 等价，RequestApplier 每次自曝）后按需提示。
   */
  private applyParamsFromPills(): void {
    const report: ApplyReport = this.engine.applyParams(this.params);
    this.syncParamLabels();
    if (report.failed.length > 0) {
      Diag.w(WOTA_RECORD_TAG, `pill apply failed: ${report.failed.join('|')}`);
      this.appendNotice(`参数下发失败: ${report.failed.join(',').substring(0, 80)}`);
      return;
    }
    const notable: Array<string> = report.skipped.filter((s: string): boolean => s.indexOf('tint:') !== 0);
    if (notable.length > 0) {
      this.appendNotice(`部分参数未下发: ${notable.join(',').substring(0, 60)}`);
    }
  }

  /** 起录前兜底下发一次（T21）：ApplyReport failed 项 Diag.w（不起录，录制键另有门控） */
  private applyParamsBeforeRecord(): void {
    const report: ApplyReport = this.engine.applyParams(this.params);
    if (report.failed.length > 0) {
      Diag.w(WOTA_RECORD_TAG, `pre-record apply failed: ${report.failed.join('|')}`);
    }
  }

  /** 打开高级参数弹层：量取触发胶囊的窗口矩形做锚点；量不到走 PillPopup 底贴屏回退 */
  private openPill(name: string, componentId: string): void {
    this.pillAnchor = { left: 0, top: 0, right: 0, bottom: 0 };
    this.ability = this.cameraStarted ? this.engine.refinedAbility() : undefined;
    try {
      const info = this.getUIContext().getComponentUtils().getRectangleById(componentId);
      this.pillAnchor = {
        left: info.windowOffset.x,
        top: info.windowOffset.y,
        right: info.windowOffset.x + info.size.width,
        bottom: info.windowOffset.y + info.size.height
      };
    } catch (e) {
      // 锚点量取失败（id 失效/首帧未量到）：保持零矩形，PillPopup 回退底贴屏
    }
    this.activePill = name;
  }

  private closePill(): void {
    this.activePill = '';
  }

  private cycleLens(): void {
    if (this.recording || this.stopPending || this.startingCamera || this.lenses.length === 0) {
      return;
    }
    let idx: number = -1;
    for (let i = 0; i < this.lenses.length; i++) {
      if (this.lenses[i].key === this.lensKey) {
        idx = i;
        break;
      }
    }
    idx = (idx + 1) % this.lenses.length;
    this.lensKey = this.lenses[idx].key;
    this.lensLabel = this.lensName(this.lenses[idx]);
    this.engine.stop().then((): void => {
      this.cameraStarted = false;
      this.tryStartCamera(); // 失败由 tryStartCamera 消息 +「重试相机」承担
    });
  }

  private cycleSize(): void {
    if (this.recording || this.stopPending || this.startingCamera) {
      return;
    }
    const want4k: boolean = this.settings.sizeW < 3840;
    if (want4k) {
      let supported: boolean = false;
      for (const s of this.engine.supportedPreviewSizes()) {
        if (s.width >= 3840) {
          supported = true;
          break;
        }
      }
      if (!supported) {
        this.message = '该镜头不支持 4K 预览，保持 1080p';
        return;
      }
    }
    this.settings.sizeW = want4k ? RECORD_WIDTH_4K : RECORD_WIDTH_1080;
    this.settings.sizeH = want4k ? RECORD_HEIGHT_4K : RECORD_HEIGHT_1080;
    this.sizeLabel = want4k ? '4K' : '1080p';
    this.persistSettings();
    this.restartCamera();
  }

  private cycleFps(): void {
    if (this.recording || this.stopPending) {
      return;
    }
    this.settings.fps = this.settings.fps === 24 ? 25 : (this.settings.fps === 25 ? 30 : 24);
    this.fpsLabel = `${this.settings.fps}`;
    // T21：fps 档位换挡 → 参数总线重算（快门上限吃帧率档位）
    this.params.fps.value = ParamState.of(this.settings.fps);
    if (this.ability !== undefined) {
      this.params.applyAbility(this.ability);
    }
    this.syncParamLabels();
    this.persistSettings();
  }

  private nearestZoomIndex(actual: number): number {
    let best: number = 0;
    let bestDiff: number = Number.MAX_VALUE;
    for (let i = 0; i < ZOOM_LEVELS.length; i++) {
      const diff: number = Math.abs(ZOOM_LEVELS[i] - actual);
      if (diff < bestDiff) {
        bestDiff = diff;
        best = i;
      }
    }
    return best;
  }

  private cycleZoom(): void {
    if (!this.cameraStarted) {
      return;
    }
    this.zoomIndex = (this.zoomIndex + 1) % ZOOM_LEVELS.length;
    const target: number = ZOOM_LEVELS[this.zoomIndex];
    const actual: number = this.engine.applyZoom(target);
    if (actual <= 0) {
      this.zoomIndex = 0;
      this.zoomLabel = '1x';
      this.message = '该镜头不支持该变焦档';
      return;
    }
    this.zoomLabel = `${actual}x`;
    this.zoomIndex = this.nearestZoomIndex(actual);
    if (actual <= 1.01 && target > 1) {
      this.message = '该镜头不支持该变焦档（已钳回 1x）';
    }
  }

  private toggleFlash(): void {
    if (!this.cameraStarted) {
      return;
    }
    const next: boolean = !this.flashOn;
    const ok: boolean = this.engine.applyFlash(next
      ? camera.FlashMode.FLASH_MODE_ALWAYS_OPEN : camera.FlashMode.FLASH_MODE_CLOSE);
    if (ok) {
      this.flashOn = next;
    } else {
      this.message = '该镜头不支持闪光/常亮';
    }
  }

  private cycleEffect(): void {
    this.effectIndex = (this.effectIndex + 1) % 4;
    this.settings.effect = this.effectIndex;
    const labels: Array<string> = ['直通', '曲线', '斑马', '峰值'];
    this.effectLabel = labels[this.effectIndex];
    this.applyEffect();
    this.persistSettings();
  }

  private applyEffect(): void {
    const lib: GlLibApi | undefined = this.glLib;
    if (lib === undefined) {
      return;
    }
    try {
      if (this.effectIndex === 0) {
        lib.setEffect(0);
        lib.setCurveEnabled(false);
      } else if (this.effectIndex === 1) {
        lib.setEffect(0);
        lib.setCurveEnabled(true);
      } else if (this.effectIndex === 2) {
        lib.setEffect(FrameEffect.ZEBRA as number);
        lib.setCurveEnabled(false);
      } else {
        lib.setEffect(FrameEffect.PEAKING as number);
        lib.setCurveEnabled(false);
      }
    } catch (e) {
      this.message = `效果切换失败: ${e}`;
    }
  }

  private cycleArc(): void {
    if (this.recording || this.stopPending || this.settings.fps >= 30) {
      return;
    }
    this.settings.arcMode = this.settings.arcMode === 1 ? 2 : 1;
    this.persistSettings();
  }

  private toggleGrid(): void {
    this.gridOn = !this.gridOn;
    this.settings.grid = this.gridOn;
    this.persistSettings();
  }

  private persistSettings(): void {
    const ctx: common.Context | undefined = this.ctx;
    if (ctx === undefined) {
      return;
    }
    SettingsStore.save(ctx, this.settings).then((ok: boolean): void => {
      if (!ok) {
        this.message = '设置保存失败';
      }
    }).catch((e: Object): void => {
      this.message = `设置保存失败: ${e}`;
    });
  }

  private resetCameraLabels(): void {
    this.lensLabel = '—';
    this.sizeLabel = '—';
    this.zoomLabel = '1x';
    this.zoomIndex = 0;
    // 会话已失：能力/读数一并复位（能力缺失显示 -）
    this.ability = undefined;
    this.rowShutter = '-';
    this.rowIso = '-';
    this.rowEv = '-';
    this.rowWb = '-';
    this.pillShutterLabel = '-';
    this.pillIsoLabel = '-';
    this.pillEvLabel = '-';
    this.pillWbLabel = '-';
  }

  private applyGeometry(): void {
    const lib: GlLibApi | undefined = this.glLib;
    const info: GlLensInfo | undefined = this.engine.lastLensInfo();
    if (lib === undefined || info === undefined) {
      return;
    }
    try {
      lib.setDisplayGeometry(info.sensorDeg, this.displayDeg(), info.front);
    } catch (e) {
    }
  }

  private readoutText(key: string): string {
    if (key === 'lens') {
      return this.lensLabel;
    }
    if (key === 'size') {
      return this.sizeLabel;
    }
    if (key === 'fps') {
      const preview: number = this.engine.currentPreviewFps();
      return `预览${preview > 0 ? preview : '—'}fps/录${this.settings.fps}fps`;
    }
    if (key === 'battery') {
      return `🔋${this.batteryPct >= 0 ? this.batteryPct + '%' : '—'}`;
    }
    if (key === 'free') {
      return `${this.freeMb >= 0 ? this.freeMb + 'MB' : '—'}`;
    }
    return formatElapsed(this.elapsedMs);
  }

  /** 顶栏第二行读数（T21）：快门/ISO/EV/WB（值由 syncParamLabels 预算进 @State） */
  private readoutText2(key: string): string {
    if (key === 'shutter') {
      return this.rowShutter;
    }
    if (key === 'iso') {
      return this.rowIso;
    }
    if (key === 'ev') {
      return this.rowEv;
    }
    return this.rowWb;
  }

  private displayDeg(): number {
    try {
      return display.getDefaultDisplaySync().rotation * 90;
    } catch (e) {
      return 0;
    }
  }

  private initNativeParams(): void {
    const lib: GlLibApi | undefined = this.glLib;
    if (lib === undefined) {
      return;
    }
    try {
      const lut: Uint8Array = new CurveStack().bakeBytes();
      const ab: ArrayBuffer = new ArrayBuffer(lut.length);
      new Uint8Array(ab).set(lut);
      lib.uploadCurveLut(ab);
      lib.setZebraParams(0.6, 1);
      lib.setPeakingParams(0.6, PeakingColor.RED as number);
      this.applyEffect();
    } catch (e) {
    }
  }

  private setKeepScreenOn(on: boolean): void {
    const ctx: common.Context | undefined = this.ctx;
    if (ctx === undefined) {
      return;
    }
    window.getLastWindow(ctx).then((win: window.Window): void => {
      win.setWindowKeepScreenOn(on).catch((e: Object): void => {
      });
    }).catch((e: Object): void => {
    });
  }

  private startStatusTimer(): void {
    this.stopStatusTimer();
    this.statusTimer = setInterval((): void => {
      if (this.disposed) {
        return;
      }
      if (this.recording) {
        this.elapsedMs = Date.now() - this.recordStartedAtMs;
      }
      const now: number = Date.now();
      if (now - this.lastReadoutMs >= 2000) {
        this.lastReadoutMs = now;
        try {
          const soc: number = batteryInfo.batterySOC;
          this.batteryPct = Number.isFinite(soc) ? soc : -1;
        } catch (e) {
          this.batteryPct = -1;
        }
        const ctx: common.Context | undefined = this.ctx;
        if (ctx !== undefined) {
          this.freeMb = VideoStore.freeSpaceMb(ctx);
        }
      }
    }, 200);
  }

  private stopStatusTimer(): void {
    if (this.statusTimer >= 0) {
      clearInterval(this.statusTimer);
      this.statusTimer = -1;
    }
  }

  /** 失败恢复入口：重开相机会话（空镜头表也会重新枚举） */
  private retryCamera(): void {
    if (this.recording || this.stopPending || this.startingCamera || !this.pageVisible) {
      return;
    }
    this.engine.stop().then((): void => {
      this.cameraStarted = false;
      this.tryStartCamera();
    });
  }

  private toggleRecord(): void {
    if (this.stopPending) {
      return;
    }
    if (this.glFatal !== '') {
      this.message = this.glFatal;
      return;
    }
    const lib: GlLibApi | undefined = this.glLib;
    const ctx: common.Context | undefined = this.ctx;
    if (lib === undefined || ctx === undefined) {
      return;
    }
    if (this.recorder.isRecording()) {
      this.requestStop();
      return;
    }
    if (this.lastStopAtMs > 0 && Date.now() - this.lastStopAtMs < START_COOLDOWN_MS) {
      this.appendNotice('刚停录，请稍后再录');
      return;
    }
    if (!this.cameraStarted || this.engine.deviceStatus() !== DeviceStatus.OPEN_SUCCEED) {
      this.message = '相机未就绪，无法录制';
      return;
    }
    const actual: Size | undefined = this.engine.currentSize();
    if (actual === undefined) {
      this.message = '无可用画幅';
      return;
    }
    const w: number = actual.width;
    const h: number = actual.height;
    const fps: number = this.settings.fps;
    const bitrate: number = BitratePolicy.resolve(this.settings.bitrate, w, h, fps, false);
    const arcMode: number = fps < 30 ? this.settings.arcMode : 0;
    this.applyParamsBeforeRecord(); // T21：起录前把参数总线快照下发一遍（failed 项 Diag.w）
    const error: string = this.recorder.start(ctx, lib, w, h, fps, bitrate, arcMode, fps,
      ArcMergeDeadband.deadband);
    if (error !== '') {
      this.message = `录制启动失败: ${error}`;
      return;
    }
    this.recording = true;
    this.stopPending = false;
    this.stopReason = '';
    this.lastStopSummary = '';
    this.lastStopAtMs = 0;
    this.recordStartedAtMs = Date.now();
    this.elapsedMs = 0;
    this.message = `录制中 ${w}x${h}@${fps} arc=${arcMode === 0 ? 'off' : (arcMode === 1 ? 'mend' : 'drop')}`;
    this.startRecordMonitor();
    this.startAutoStopMonitor(ctx);
    this.healthAttempts = 0;
    this.healthPausedByHide = false;
    this.startHealthMonitor();
  }

  /** 页面恢复时续跑未完成的健康巡检（保留 healthStartMs，elapsed 继续累计） */
  private resumeHealthAfterShow(): void {
    if (!this.healthPausedByHide) {
      return;
    }
    this.healthPausedByHide = false;
    if (!this.recording || this.healthHealthy || this.stopPending || this.healthTimer >= 0) {
      return;
    }
    this.healthTimer = setInterval((): void => {
      this.checkHealth();
    }, 500);
  }

  private startHealthMonitor(): void {
    this.stopHealthMonitor();
    this.healthStartMs = Date.now();
    this.healthHealthy = false;
    this.healthTimer = setInterval((): void => {
      this.checkHealth();
    }, 500);
  }

  private stopHealthMonitor(): void {
    if (this.healthTimer >= 0) {
      clearInterval(this.healthTimer);
      this.healthTimer = -1;
    }
  }

  private checkHealth(): void {
    if (this.disposed || !this.recording || this.stopPending) {
      return;
    }
    const lib: GlLibApi | undefined = this.glLib;
    if (lib === undefined) {
      return;
    }
    let raw: string = '';
    try {
      raw = lib.getGpuHealth();
    } catch (e) {
      return;
    }
    const parts: Array<string> = raw.split(',');
    if (parts.length !== 3) {
      Diag.w(WOTA_RECORD_TAG, `gpu health parse failed: ${raw}`);
      return;
    }
    const trackAdded: boolean = parts[0] === '1';
    const muxStarted: boolean = parts[1] === '1';
    const samples: number = Number(parts[2]);
    if (!Number.isFinite(samples)) {
      return;
    }
    let nativeError: string = '';
    try {
      nativeError = lib.getGpuRecordError();
    } catch (e) {
      nativeError = '';
    }
    const elapsed: number = Date.now() - this.healthStartMs;
    const verdict: HealthVerdict = healthVerdict(samples > 0, muxStarted, trackAdded,
      nativeError, elapsed, this.healthWindow);
    if (verdict === HealthVerdict.HEALTHY) {
      if (!this.healthHealthy) {
        this.healthHealthy = true;
        Diag.i(WOTA_RECORD_TAG, `record health healthy samples=${samples} elapsed=${elapsed}ms`);
      }
      this.stopHealthMonitor();
      return;
    }
    if (verdict !== HealthVerdict.UNHEALTHY) {
      return;
    }
    this.stopHealthMonitor();
    if (nativeError !== '') {
      this.finishRecord();
      this.appendNotice(`录制异常停止: ${nativeError}`);
      return;
    }
    const action: RetryAction = retryActionOf(this.healthAttempts, this.healthWindow, verdict);
    if (action === RetryAction.RESTART) {
      this.healthAttempts++;
      this.restartSegmentAfterHealthFailure(this.healthAttempts);
      return;
    }
    const summary: string = this.finishRecord();
    this.discardEmptySegment(summary);
    this.appendNotice(`录制启动失败：${Math.floor(FULL_WINDOW_MS / 1000)} 秒内无视频样本` +
      `（已重试 ${MAX_RESTART_ATTEMPTS} 次）`);
  }

  private restartSegmentAfterHealthFailure(attempt: number): void {
    const lib: GlLibApi | undefined = this.glLib;
    const ctx: common.Context | undefined = this.ctx;
    const actual: Size | undefined = this.engine.currentSize();
    if (lib === undefined || ctx === undefined || actual === undefined) {
      this.finishRecord();
      return;
    }
    this.stopRecordMonitor();
    const summary: string = this.recorder.stop();
    this.discardEmptySegment(summary);
    const fps: number = this.settings.fps;
    const bitrate: number = BitratePolicy.resolve(this.settings.bitrate, actual.width, actual.height, fps, false);
    const arcMode: number = fps < 30 ? this.settings.arcMode : 0;
    this.applyParamsBeforeRecord(); // 自动重录也是起录：同一条下发兜底
    const error: string = this.recorder.start(ctx, lib, actual.width, actual.height, fps, bitrate,
      arcMode, fps, ArcMergeDeadband.deadband);
    if (error !== '') {
      this.finishRecord();
      this.appendNotice(`自动重录失败: ${error}`);
      return;
    }
    this.appendNotice(`录制未产出，自动重试 ${attempt}/${this.healthWindow.maxAttempts}`);
    this.startRecordMonitor();
    this.startHealthMonitor();
  }

  /** 仅当 samples=0 且 bytes===0 才删空段；bytes<0 保留 triage；解析失败/有样本一律保留 */
  private discardEmptySegment(summary: string): void {
    const pathM: RegExpExecArray | null = new RegExp('path=([^ ]+)').exec(summary);
    const bytesM: RegExpExecArray | null = new RegExp('bytes=(-?\\d+)').exec(summary);
    const samplesM: RegExpExecArray | null = new RegExp('samples=(\\d+)').exec(summary);
    if (pathM === null || bytesM === null || samplesM === null) {
      return;
    }
    const path: string = pathM[1];
    const bytes: number = Number(bytesM[1]);
    const samples: number = Number(samplesM[1]);
    if (path === '' || samples > 0) {
      return;
    }
    if (bytes < 0) {
      Diag.w(WOTA_RECORD_TAG, `segment bytes unobservable, keep for triage: ${path}`);
      return;
    }
    if (bytes > 0) {
      return;
    }
    VideoStore.discard(path);
    ArcDropLog.deleteFor(path);
    Diag.w(WOTA_RECORD_TAG, `empty segment discarded: ${path} bytes=${bytes} samples=${samples}`);
  }

  private startAutoStopMonitor(ctx: common.Context): void {
    this.stopAutoStopMonitor();
    this.autoStopMonitor = new AutoStopMonitor(ctx,
      (): boolean => this.recording && !this.stopPending,
      () => this.engine.deviceStatus(),
      (reason: string): void => {
        this.requestStop(reason);
      });
    this.autoStopMonitor.start();
  }

  private stopAutoStopMonitor(): void {
    if (this.autoStopMonitor !== undefined) {
      this.autoStopMonitor.stop();
      this.autoStopMonitor = undefined;
    }
  }

  private startRecordMonitor(): void {
    this.stopRecordMonitor();
    this.recordTimer = setInterval((): void => {
      if (this.disposed || !this.recording) {
        this.stopRecordMonitor();
        return;
      }
      if (this.stopPending) {
        return;
      }
      const lib: GlLibApi | undefined = this.glLib;
      const ctx: common.Context | undefined = this.ctx;
      if (lib === undefined || ctx === undefined) {
        return;
      }
      let alive: boolean = false;
      try {
        alive = lib.isGpuRecording();
      } catch (e) {
        alive = false;
      }
      if (alive) {
        const rotateResult: string = this.recorder.maybeRotate(ctx, lib);
        if (rotateResult.indexOf('error:') === 0) {
          this.finishRecord();
          this.appendNotice(`分段轮转失败，已停止，保留已完成段：${rotateResult}`);
          return;
        }
        if (rotateResult.indexOf('rotated:') === 0) {
          this.message = `录制中 分段 ${rotateResult.substring('rotated:'.length)}`;
          if (this.healthTimer >= 0) {
            this.healthStartMs = Date.now(); // 新段重新计健康窗，避免旧段读数误判
          }
        }
        try {
          alive = lib.isGpuRecording();
        } catch (e) {
          alive = false;
        }
        if (alive) {
          return;
        }
      }
      let reason: string = '';
      try {
        reason = lib.getGpuRecordError();
      } catch (e) {
        reason = '';
      }
      const summary: string = this.finishRecord();
      if (reason.indexOf('display-context-lost') >= 0 ||
        summary.indexOf('display-context-lost') >= 0) {
        this.glFatal = 'GL 上下文丢失，录制已中止；请返回后重新进入本页';
        this.message = this.glFatal;
      } else if (reason !== '') {
        this.message = `${this.message} (${reason})`;
      }
    }, 500);
  }

  private stopRecordMonitor(): void {
    if (this.recordTimer >= 0) {
      clearInterval(this.recordTimer);
      this.recordTimer = -1;
    }
  }

  private requestStop(reason: string = ''): void {
    if (this.stopPending) {
      if (reason !== '') {
        this.stopReason = this.stopReason === '' ? reason : this.stopReason;
      }
      return;
    }
    if (!this.recording) {
      return;
    }
    if (reason !== '') {
      this.stopReason = this.stopReason === '' ? reason : this.stopReason;
    }
    const delay: number = AutoStopRules.stopGraceDelayMs(Date.now() - this.recordStartedAtMs);
    if (delay > 0) {
      this.stopPending = true;
      this.stopHealthMonitor();
      this.stopAutoStopMonitor();
      this.message = '录制: 收尾中…';
      this.stopGraceTimer = setTimeout((): void => {
        this.stopGraceTimer = -1;
        this.finishRecord();
      }, delay);
      return;
    }
    this.finishRecord(reason);
  }

  private finishRecord(autoReason: string = ''): string {
    if (!this.recording && this.lastStopSummary !== '') {
      return this.lastStopSummary;
    }
    if (this.stopGraceTimer >= 0) {
      clearTimeout(this.stopGraceTimer);
      this.stopGraceTimer = -1;
    }
    this.stopAutoStopMonitor();
    this.stopRecordMonitor();
    this.stopHealthMonitor();
    this.healthPausedByHide = false;
    const summary: string = this.recorder.stop();
    this.recording = false;
    this.stopPending = false;
    this.elapsedMs = 0;
    this.recordStartedAtMs = 0;
    if (autoReason !== '') {
      this.stopReason = this.stopReason === '' ? autoReason : this.stopReason;
    }
    const reason: string = this.stopReason;
    this.stopReason = '';
    const saved: boolean = summary.indexOf('status=ok') >= 0;
    const reasonText: string = autoReasonText(reason, saved);
    this.message = `录制: ${summary}${reasonText === '' ? '' : `（${reasonText}）`}`;
    this.lastStopSummary = summary;
    this.lastStopAtMs = Date.now();
    if (this.engine.deviceStatus() !== DeviceStatus.OPEN_SUCCEED) {
      this.cameraStarted = false;
      this.resetCameraLabels();
    }
    return summary;
  }

  @Builder
  private pill(label: string, onTap: () => void, enabled: boolean, componentId: string = '') {
    Button(label)
      .fontSize(11)
      .height(30)
      .enabled(enabled)
      .backgroundColor('#4D000000')
      .id(componentId)
      .onClick((): void => {
        onTap();
      })
  }

  @Builder
  private gridOverlay() {
    Stack() {
      ForEach(HudSpec.gridFractions(), (f: number): void => {
        Divider()
          .width('100%')
          .strokeWidth(1)
          .color('#55FFFFFF')
          .position({ x: 0, y: `${f * 100}%` })
        Divider()
          .vertical(true)
          .height('100%')
          .strokeWidth(1)
          .color('#55FFFFFF')
          .position({ x: `${f * 100}%`, y: 0 })
      }, (f: number): string => `${f}`)
      Text('+')
        .fontSize(18)
        .fontColor('#55FFFFFF')
    }
    .width('100%')
    .height('100%')
    .hitTestBehavior(HitTestMode.None)
  }

  build() {
    Stack() {
      XComponent({ id: 'wotaCamera', type: XComponentType.SURFACE, libraryname: 'entry' })
        .onLoad((): void => {
          this.loadNativeModule();
        })
        .height('100%')
        .width('100%')

      if (this.gridOn) {
        this.gridOverlay()
      }

      Column() {
        Row({ space: 8 }) {
          Button('设置')
            .fontSize(12)
            .enabled(!this.recording && !this.stopPending)
            .onClick((): void => {
              this.getUIContext().getRouter().pushUrl({ url: 'pages/Settings' });
            })
          Button('媒体库')
            .fontSize(12)
            .enabled(!this.recording && !this.stopPending)
            .onClick((): void => {
              this.getUIContext().getRouter().pushUrl({ url: 'pages/Gallery' });
            })
          Column({ space: 2 }) {
            Row({ space: 6 }) {
              ForEach(HudSpec.HUD_READOUTS, (key: string): void => {
                Text(this.readoutText(key))
                  .fontSize(10)
                  .fontColor(key === 'duration' && this.recording ? '#FF5252' : Color.White)
                  .maxLines(1)
              }, (key: string): string => key)
            }
            // T21 顶栏第二行：快门/ISO/EV/WB（对齐上游 READOUT 可见项；固定两行为登记偏离）
            Row({ space: 6 }) {
              ForEach(HudSpec.HUD_READOUTS_ROW2, (key: string): void => {
                Text(this.readoutText2(key))
                  .fontSize(10)
                  .fontColor(Color.White)
                  .maxLines(1)
              }, (key: string): string => key)
            }
          }
          .layoutWeight(1)
        }
        .width('100%')
        .height(HudSpec.TOP_BAR_HEIGHT)
        .padding({ left: HudSpec.SAFE_MARGIN, right: HudSpec.SAFE_MARGIN })

        Blank()

        Column({ space: 6 }) {
          if (this.permissionGranted === false) {
            Button(this.permissionRequesting ? '请求权限中…' : '相机权限被拒绝，点按重试')
              .fontSize(12)
              .enabled(!this.permissionRequesting)
              .onClick((): void => {
                this.ensureCameraPermission();
              })
          }
          if (this.message !== '') {
            Text(this.message)
              .fontSize(11)
              .fontColor('#FFCC80')
              .maxLines(2)
              .textAlign(TextAlign.Center)
              .width('100%')
          }
          if (!this.cameraStarted && this.permissionGranted && !this.loading) {
            Button(this.startingCamera ? '启动中…' : '重试相机')
              .fontSize(11)
              .enabled(!this.startingCamera)
              .onClick((): void => {
                this.retryCamera();
              })
          }
          Scroll() {
            Row({ space: 6 }) {
              this.pill(`镜头:${this.lensLabel}`, (): void => {
                this.cycleLens();
              }, !this.recording && !this.stopPending)
              this.pill(`画幅:${this.sizeLabel}`, (): void => {
                this.cycleSize();
              }, !this.recording && !this.stopPending)
              this.pill(`帧率:${this.fpsLabel}`, (): void => {
                this.cycleFps();
              }, !this.recording && !this.stopPending)
              // T21 高级参数胶囊：点按开 PillPopup 就近浮层（门控见 ParamPills.RECORDING_EDITABLE_PILLS）
              this.pill(`快门:${this.pillShutterLabel}`, (): void => {
                this.openPill('shutter', 'pill_shutter');
              }, this.cameraStarted, 'pill_shutter')
              this.pill(`ISO:${this.pillIsoLabel}`, (): void => {
                this.openPill('iso', 'pill_iso');
              }, this.cameraStarted, 'pill_iso')
              this.pill(`EV:${this.pillEvLabel}`, (): void => {
                this.openPill('ev', 'pill_ev');
              }, this.cameraStarted, 'pill_ev')
              this.pill(`WB:${this.pillWbLabel}`, (): void => {
                this.openPill('wb', 'pill_wb');
              }, this.cameraStarted, 'pill_wb')
              this.pill(`变焦:${this.zoomLabel}`, (): void => {
                this.cycleZoom();
              }, true)
              this.pill(`闪光:${this.flashOn ? '常亮' : '关'}`, (): void => {
                this.toggleFlash();
              }, true)
              this.pill(`效果:${this.effectLabel}`, (): void => {
                this.cycleEffect();
              }, true)
              if (this.settings.fps < 30) {
                this.pill(`光弧:${this.settings.arcMode === 1 ? '补弧' : '抽帧'}`, (): void => {
                  this.cycleArc();
                }, !this.recording && !this.stopPending)
              } else {
                this.pill('光弧:—', (): void => {
                }, false)
              }
              this.pill(`网格:${this.gridOn ? '开' : '关'}`, (): void => {
                this.toggleGrid();
              }, true)
            }
          }
          .scrollable(ScrollDirection.Horizontal)
          .scrollBar(BarState.Off)
          .height(HudSpec.PILL_ROW_HEIGHT)
          .width('100%')

          Row({ space: 10 }) {
            Button(this.stopPending ? '收尾中…' : (this.recording ? '停录' : '录制'))
              .fontSize(16)
              .height(52)
              .layoutWeight(1)
              .backgroundColor(this.recording ? '#B71C1C' : '#2E7D32')
              .enabled(!this.stopPending && this.glFatal === '')
              .onClick((): void => {
                this.toggleRecord();
              })
          }
          .width('100%')
        }
        .width('100%')
        .padding({ left: HudSpec.SAFE_MARGIN, right: HudSpec.SAFE_MARGIN, bottom: HudSpec.SAFE_MARGIN })
        .height(HudSpec.BOTTOM_BAR_HEIGHT)
      }
      .width('100%')
      .height('100%')
      .hitTestBehavior(HitTestMode.Transparent)

      if (this.loading) {
        Column({ space: 8 }) {
          LoadingProgress()
            .width(36)
            .height(36)
          Text('启动相机…')
            .fontSize(12)
            .fontColor('#AAAAAA')
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
      }

      if (this.glFatal !== '') {
        Text(this.glFatal)
          .fontSize(12)
          .fontColor('#FF8A80')
          .maxLines(3)
          .padding(12)
          .backgroundColor('#66000000')
      }

      // T21 高级参数就近浮层（最顶层：遮罩拦点按，量框后定位面板）
      if (this.activePill === 'shutter') {
        ShutterPillPopup({
          anchor: this.pillAnchor,
          params: this.params,
          ability: this.ability,
          editable: !this.recording || pillEditableDuringRecording('shutter'),
          onClose: (): void => {
            this.closePill();
          },
          onApply: (): void => {
            this.applyParamsFromPills();
          }
        })
      }
      if (this.activePill === 'iso') {
        IsoPillPopup({
          anchor: this.pillAnchor,
          params: this.params,
          ability: this.ability,
          editable: !this.recording || pillEditableDuringRecording('iso'),
          onClose: (): void => {
            this.closePill();
          },
          onApply: (): void => {
            this.applyParamsFromPills();
          }
        })
      }
      if (this.activePill === 'ev') {
        EvPillPopup({
          anchor: this.pillAnchor,
          params: this.params,
          ability: this.ability,
          editable: !this.recording || pillEditableDuringRecording('ev'),
          onClose: (): void => {
            this.closePill();
          },
          onApply: (): void => {
            this.applyParamsFromPills();
          }
        })
      }
      if (this.activePill === 'wb') {
        WbPillPopup({
          anchor: this.pillAnchor,
          params: this.params,
          ability: this.ability,
          editable: !this.recording || pillEditableDuringRecording('wb'),
          onClose: (): void => {
            this.closePill();
          },
          onApply: (): void => {
            this.applyParamsFromPills();
          }
        })
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Color.Black)
  }
}
