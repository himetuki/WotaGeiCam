/**
 * GL 相机试验页（GL-1/GL-2）：XComponent(SURFACE, libraryname='entry') 的原生面由 cpp 侧
 * OH_NativeImage 消费相机帧（external OES → 直通/曲线/斑马纹/峰值 单趟效果上屏）。
 *
 * 加载顺序（P2 契约）：**不在页面顶层静态 import 'libentry.so'**——静态 import 会让原生模块先于
 * XComponent 加载并以「无 OH_NATIVE_XCOMPONENT_OBJ 的 exports」完成 Init，surface 回调永不注册；
 * 改为 XComponent onLoad 之后再 dynamic import（此时 XComponent 的加载路径已完成回调注册）。
 *
 * GL-2 接线：display 旋转（rotation×90）+ 前置镜像 → setDisplayGeometry（buildTexCoords 烘 UV）；
 * CurveStack.bakeBytes（256×1 交错 RGB）→ uploadCurveLut；效果槽四态循环（直通/曲线/斑马/峰值）+
 * 曲线叠加（仅斑马/峰值）；斑马/峰值参数取上游默认（0.6/1 与 0.6/RED）。
 * GL-4 接线：录 GPU/停录（视频-only 最小切片）——固定 1920×1080@30、BitratePolicy.resolve 取码率，
 * 文件经 VideoStore 落 filesDir/videos，fd 归 ArkTS（GlRecorder），native 直写 MP4；编码 pass 不碰 frost。
 * 已知：identity LUT 下曲线模式视觉与直通相同（曲线编辑随 HUD 批）；音频/分段/抽帧/参数下发留待后续批。
 */
import { common } from '@kit.AbilityKit';
import { display } from '@kit.ArkUI';
import { GlCameraEngine, GlLensInfo } from '../camera/GlCameraEngine';
import { DeviceStatus } from '../camera/DeviceStatus';
import { GlRecordLib, GlRecorder } from '../camera/GlRecorder';
import { requestCameraPermission } from '../camera/PermGate';
import { CurveStack } from '../core/CurveModel';
import { ArcMergeDeadband, FrameEffect, PeakingColor } from '../core/Constants';
import { BitratePolicy } from '../record/BitratePolicy';
import { AutoStopMonitor } from '../record/AutoStopMonitor';
import { AutoStopRules } from '../record/AutoStopRules';

/** libentry.so 的动态加载桥视图（camera/GlLibBridge 的导出签名） */
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
// GPU 录制固定档（参数总线接线留待 HUD 批；登记 PORT_MAP）
const GPU_RECORD_WIDTH: number = 1920;
const GPU_RECORD_HEIGHT: number = 1080;
const GPU_RECORD_FPS: number = 30;
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

@Entry
@Component
struct GlPreview {
  @State message: string = 'waiting for GL surface...';
  @State effectLabel: string = '直通';
  @State curveOverlay: boolean = false;
  @State frostEnabled: boolean = false;
  @State recording: boolean = false;
  /** GL 致命态（display-context-lost）：录制按钮禁用直到页面重建（不做 EGL 原地重建，登记） */
  @State glFatal: string = '';
  /** 光弧转换档：0=关 / 1=补弧24（MEND）/ 2=抽帧24（DROP） */
  @State arcMode: number = 0;
  /** 手动停录宽限收尾中（按钮防重入） */
  @State stopPending: boolean = false;
  /** 分段测试阈值（8MB）开关 */
  @State segmentTestOn: boolean = false;
  private engine: GlCameraEngine = new GlCameraEngine();
  private recorder: GlRecorder = new GlRecorder();
  private pollTimer: number = -1;
  /** 录制期监视定时器：native 端写失败自中止（isGpuRecording=false）时页面据此收尾并显示原因 */
  private recordTimer: number = -1;
  private autoStopMonitor: AutoStopMonitor | undefined = undefined;
  private recordStartedAtMs: number = 0;
  private stopGraceTimer: number = -1;
  /** 首个停录原因（自动/宽限/native 并发时合并进最终文案） */
  private stopReason: string = '';
  /** 最近一次收尾摘要（防止迟到回调重复 stop/覆盖文案） */
  private lastStopSummary: string = '';
  private lastStopAtMs: number = 0;
  private glLib: GlLibApi | undefined = undefined;
  /** 页面销毁后取消所有异步链（轮询/权限/起镜 then 回调不得越过返回继续跑） */
  private disposed: boolean = false;
  /** 效果循环序号：0 直通 / 1 曲线 / 2 斑马纹 / 3 峰值 */
  private effectIndex: number = 0;

  aboutToAppear(): void {
    this.disposed = false;
    this.segmentTestOn = GlRecorder.getSegmentLimitBytes() === 8 * 1024 * 1024;
    // 相机丢失第二道兜底：录制中直接走自动停录；非录制态只提示
    this.engine.setOnCameraLost((): void => {
      if (this.disposed) {
        return;
      }
      if (this.recording || this.stopPending) {
        // 统一停止请求：起录 <500ms 也要走 STOP_GRACE_MS 宽限，reason 作为延迟收尾原因
        this.requestStop(AutoStopRules.REASON_CAMERA_LOST);
        return;
      }
      if (this.message.indexOf('相机被收回') >= 0 || this.message.indexOf('CAMERA_LOST') >= 0) {
        return; // 摘要已含相机丢失原因，不重复追加
      }
      this.message = this.message.indexOf('GPU record:') >= 0
        ? `${this.message}（相机已断开）` : '相机已断开';
    });
    try {
      // display.rotation 变化（转屏）→ 立即重算几何；off 不传 callback 表示移除本页全部监听
      display.on('change', (_displayId: number): void => {
        this.applyGeometry();
      });
    } catch (e) {
      // 监听失败不阻塞（仅转屏时不即时校正）
    }
  }

  aboutToDisappear(): void {
    this.disposed = true;
    // 顺序（契约）：先停录（GL 编码面仍可用、fd 归本类）→ 关相机会话 → 停轮询
    if (this.stopGraceTimer >= 0) {
      clearTimeout(this.stopGraceTimer);
      this.stopGraceTimer = -1;
    }
    this.stopAutoStopMonitor();
    if (this.recorder.isRecording()) {
      this.finishGpuRecord(); // 页面销毁不等宽限
    }
    this.stopRecordMonitor();
    // 8MB 测试阈值为进程级静态值：离开页面恢复生产默认
    GlRecorder.resetSegmentLimitBytes();
    this.engine.stop();
    this.stopPolling();
    try {
      display.off('change');
    } catch (e) {
    }
  }

  /** XComponent onLoad 之后再加载原生模块（见文件头「加载顺序」） */
  private loadNativeModule(): void {
    // 注：SDK 对 .so 导入恒报一条「napi module 未验证」提示（28014）。实测 // @ts-ignore
    // 会被 ArkTS 以 arkts-strict-typing-required（10605146）硬拒、构建红；// @ts-nocheck 会
    // 牺牲整文件类型检查，不可取——保留该提示为已知工具链项（登记 PORT_MAP）。
    // 动态 import 的是桥模块（camera/GlLibBridge）：.so 的静态导入在桥内，本页求值阶段
    // 不触碰原生模块；桥求值发生在 XComponent onLoad 之后（顺序契约见文件头）。
    import('../camera/GlLibBridge').then((mod: ESObject): void => {
      if (this.disposed) {
        return;
      }
      this.glLib = mod as GlLibApi;
      this.initNativeParams();
      this.startPolling();
    }).catch((e: Object): void => {
      this.message = `native module load failed: ${e}`;
    });
  }

  /** 曲线 LUT（identity 默认）+ 上游默认参数（斑马 0.6/1、峰值 0.6/RED） */
  private initNativeParams(): void {
    const lib: GlLibApi | undefined = this.glLib;
    if (lib === undefined) {
      return;
    }
    try {
      const lut: Uint8Array = new CurveStack().bakeBytes();
      // Uint8Array.buffer 是 ArrayBufferLike：显式拷贝成 ArrayBuffer（napi 契约 768B 交错 RGB）
      const ab: ArrayBuffer = new ArrayBuffer(lut.length);
      new Uint8Array(ab).set(lut);
      lib.uploadCurveLut(ab);
      lib.setZebraParams(0.6, 1);
      lib.setPeakingParams(0.6, PeakingColor.RED as number);
      lib.setEffect(FrameEffect.NONE as number);
      lib.setCurveEnabled(false);
    } catch (e) {
      this.message = `GL params init failed: ${e}`;
    }
  }

  /** onLoad 时 surface 已创建，但 cpp 侧 NativeImage 可能还没建好 → 轮询 surfaceId */
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
        this.message = `native surface: ${surfaceId}`;
        this.startCamera(surfaceId);
        return;
      }
      // NativeImage 明确失败（create/listener/surfaceId）→ 立即结束轮询并显示原因
      let nativeError: string = '';
      try {
        nativeError = lib.getNativeImageError();
      } catch (e) {
        nativeError = '';
      }
      if (nativeError !== '') {
        this.stopPolling();
        this.message = `GL native image failed: ${nativeError}`;
        return;
      }
      if (Date.now() - startMs > POLL_TIMEOUT_MS) {
        this.stopPolling();
        this.message = 'GL surface not created (timeout: no native image yet)';
      }
    }, POLL_INTERVAL_MS);
  }

  private stopPolling(): void {
    if (this.pollTimer >= 0) {
      clearInterval(this.pollTimer);
      this.pollTimer = -1;
    }
  }

  private startCamera(nativeSurfaceId: string): void {
    const ctx: common.Context | undefined =
      this.getUIContext().getHostContext() as common.Context | undefined;
    if (ctx === undefined) {
      this.message = 'no host context';
      return;
    }
    requestCameraPermission(ctx).then((granted: boolean): void => {
      if (this.disposed) {
        return;
      }
      if (!granted) {
        this.message = 'camera permission denied';
        return;
      }
      this.engine.start(ctx, nativeSurfaceId).then((summary: string): void => {
        if (this.disposed) {
          // 起镜完成时页面已销毁：立即收会话，避免相机被离场页面占用
          this.engine.stop();
          return;
        }
        this.message = summary;
        this.applyGeometry();
      });
    });
  }

  /** 当前显示旋转（display.rotation 是 0..3 枚举，×90 得度） */
  private displayDeg(): number {
    try {
      return display.getDefaultDisplaySync().rotation * 90;
    } catch (e) {
      return 0;
    }
  }

  /** 方向几何：sensorOrientation / 显示旋转 / 前置镜像 → cpp buildTexCoords */
  private applyGeometry(): void {
    const lib: GlLibApi | undefined = this.glLib;
    const info: GlLensInfo | undefined = this.engine.lastLensInfo();
    if (lib === undefined || info === undefined) {
      return;
    }
    try {
      lib.setDisplayGeometry(info.sensorDeg, this.displayDeg(), info.front);
    } catch (e) {
      this.message = `GL geometry failed: ${e}`;
    }
  }

  /** 效果循环：直通 → 曲线 → 斑马纹 → 峰值 → 直通（进斑马/峰值时曲线叠加复位为关） */
  private cycleEffect(): void {
    const lib: GlLibApi | undefined = this.glLib;
    if (lib === undefined) {
      this.message = 'GL module not ready';
      return;
    }
    this.effectIndex = (this.effectIndex + 1) % 4;
    if (this.effectIndex === 2 || this.effectIndex === 3) {
      this.curveOverlay = false;
    }
    let effect: number = FrameEffect.NONE as number;
    let curve: boolean = false;
    if (this.effectIndex === 1) {
      effect = FrameEffect.NONE as number;
      curve = true;
    } else if (this.effectIndex === 2) {
      effect = FrameEffect.ZEBRA as number;
      curve = this.curveOverlay;
    } else if (this.effectIndex === 3) {
      effect = FrameEffect.PEAKING as number;
      curve = this.curveOverlay;
    }
    try {
      lib.setEffect(effect);
      lib.setCurveEnabled(curve);
    } catch (e) {
      this.message = `GL effect failed: ${e}`;
      return;
    }
    const labels: Array<string> = ['直通', '曲线', '斑马纹', '峰值'];
    this.effectLabel = labels[this.effectIndex];
    this.message = `effect: ${this.effectLabel}${curve ? ' + curve' : ''}`;
  }

  /** 曲线叠加：只对斑马纹/峰值生效（曲线模式本身恒开，直通下无意义） */
  private toggleCurveOverlay(): void {
    const lib: GlLibApi | undefined = this.glLib;
    if (lib === undefined) {
      this.message = 'GL module not ready';
      return;
    }
    if (this.effectIndex !== 2 && this.effectIndex !== 3) {
      this.message = 'curve overlay applies to zebra/peaking only';
      return;
    }
    this.curveOverlay = !this.curveOverlay;
    try {
      lib.setCurveEnabled(this.curveOverlay);
    } catch (e) {
      this.message = `GL curve overlay failed: ${e}`;
      return;
    }
    this.message = `effect: ${this.effectLabel}${this.curveOverlay ? ' + curve' : ''}`;
  }

  /** 毛玻璃开关（GL-3）：全屏高斯链与监视效果互斥一帧（关闭即回效果态） */
  private toggleFrost(): void {
    const lib: GlLibApi | undefined = this.glLib;
    if (lib === undefined) {
      this.message = 'GL module not ready';
      return;
    }
    const next: boolean = !this.frostEnabled;
    try {
      lib.setFrostEnabled(next);
    } catch (e) {
      this.message = `GL frost failed: ${e}`;
      return;
    }
    this.frostEnabled = next;
    this.message = `frost: ${next ? 'on' : 'off'}`;
  }

  /** 光弧三态：关 → 补弧24 → 抽帧24 → 关（录制中禁用） */
  private cycleArcMode(): void {
    if (this.recording) {
      return;
    }
    this.arcMode = (this.arcMode + 1) % 3;
    this.message = `转换: ${this.arcLabel()}`;
  }

  /** 8MB 分段测试阈值开关（仅测试页；生产默认 3.5GiB） */
  private toggleSegmentTest(): void {
    if (this.recording) {
      return;
    }
    if (this.segmentTestOn) {
      GlRecorder.resetSegmentLimitBytes();
      this.segmentTestOn = false;
      this.message = '分段测试: 关（默认 3.5GiB）';
    } else {
      GlRecorder.setSegmentLimitBytesForTest(8 * 1024 * 1024);
      this.segmentTestOn = true;
      this.message = '分段测试: 8MB';
    }
  }

  private arcLabel(): string {
    if (this.arcMode === 1) {
      return '补弧24';
    }
    if (this.arcMode === 2) {
      return '抽帧24';
    }
    return '关';
  }

  private hostContext(): common.Context | undefined {
    return this.getUIContext().getHostContext() as common.Context | undefined;
  }

  /**
   * 录 GPU/停录（GL-4 最小切片）：前置 = 原生模块已加载、相机会话已起（native surface 就绪）、未在录制；
   * 尺寸/码率固定档，失败显示 native 机器码（页面不做重试编排）。
   */
  private toggleGpuRecord(): void {
    if (this.stopPending) {
      return; // 宽限收尾中忽略再次点击
    }
    const lib: GlLibApi | undefined = this.glLib;
    if (this.glFatal !== '') {
      this.message = this.glFatal;
      return;
    }
    if (lib === undefined) {
      this.message = 'GL module not ready';
      return;
    }
    if (this.recorder.isRecording()) {
      this.requestManualStop();
      return;
    }
    if (this.lastStopAtMs > 0 && Date.now() - this.lastStopAtMs < START_COOLDOWN_MS) {
      // 停录后到达的迟到点击：识别为已停录，追加提示、不覆盖摘要
      if (this.message.indexOf('刚停录') < 0) {
        this.message = this.message === '' ? '刚停录，请稍后再录'
          : `${this.message}（刚停录，请稍后再录）`;
      }
      return;
    }
    if (this.engine.lastLensInfo() === undefined ||
      this.engine.deviceStatus() !== DeviceStatus.OPEN_SUCCEED) {
      this.message = '相机未就绪/已断开：请返回后重新进入本页恢复相机';
      return;
    }
    // 起录前刷新显示几何：muxer 旋转 hint 读 native 的 sensor/display，快速转屏后不得用旧值/0
    this.applyGeometry();
    const ctx: common.Context | undefined = this.hostContext();
    if (ctx === undefined) {
      this.message = 'no host context';
      return;
    }
    // 光弧档固定 24fps（25fps 由参数支持但本批无页面入口）；弧关保持 30fps
    const recordFps: number = this.arcMode !== 0 ? 24 : GPU_RECORD_FPS;
    const bitrate: number = BitratePolicy.resolve(0, GPU_RECORD_WIDTH, GPU_RECORD_HEIGHT, recordFps, false);
    const error: string = this.recorder.start(ctx, lib, GPU_RECORD_WIDTH, GPU_RECORD_HEIGHT,
      recordFps, bitrate, this.arcMode, recordFps, ArcMergeDeadband.deadband);
    if (error !== '') {
      this.message = `GPU record failed: ${error}`;
      return;
    }
    this.recording = true;
    this.stopPending = false;
    this.stopReason = '';
    this.lastStopSummary = '';
    this.recordStartedAtMs = Date.now();
    const arcName: string = this.arcMode === 0 ? 'off' : (this.arcMode === 1 ? 'mend' : 'drop');
    this.message = `GPU recording ${GPU_RECORD_WIDTH}x${GPU_RECORD_HEIGHT}@${recordFps}` +
      ` bitrate=${bitrate} arc=${arcName}`;
    this.startRecordMonitor();
    this.startAutoStopMonitor(ctx);
  }

  private startAutoStopMonitor(ctx: common.Context): void {
    this.stopAutoStopMonitor();
    this.autoStopMonitor = new AutoStopMonitor(ctx,
      (): boolean => this.recording && !this.stopPending,
      () => this.engine.deviceStatus(),
      (reason: string): void => {
        this.onAutoStop(reason);
      });
    this.autoStopMonitor.start();
  }

  private stopAutoStopMonitor(): void {
    if (this.autoStopMonitor !== undefined) {
      this.autoStopMonitor.stop();
      this.autoStopMonitor = undefined;
    }
  }

  private onAutoStop(reason: string): void {
    if (this.disposed || !this.recording) {
      return;
    }
    this.requestStop(reason);
  }

  /** 统一停止请求：带原因、尊重 STOP_GRACE_MS，首个原因保留 */
  private requestStop(reason: string = ''): void {
    if (this.stopPending) {
      if (reason !== '') {
        this.stopReason = this.stopReason === '' ? reason : this.stopReason;
      }
      return;
    }
    if (!this.recording) {
      if (reason === '') {
        this.message = '已停录';
      } else if (reason === AutoStopRules.REASON_CAMERA_LOST) {
        this.message = '已停录（相机已断开）';
      }
      return;
    }
    if (reason !== '') {
      this.stopReason = this.stopReason === '' ? reason : this.stopReason;
    }
    const delay: number = AutoStopRules.stopGraceDelayMs(Date.now() - this.recordStartedAtMs);
    if (delay > 0) {
      this.stopPending = true;
      this.stopAutoStopMonitor();
      this.message = 'GPU record: 收尾中…';
      this.stopGraceTimer = setTimeout((): void => {
        this.stopGraceTimer = -1;
        this.finishGpuRecord(); // stopReason 已记录（含宽限期间新到的 lost）
      }, delay);
      return;
    }
    this.finishGpuRecord(reason);
  }

  /** 手动停录入口（按钮）：无原因，统一走宽限 */
  private requestManualStop(): void {
    this.requestStop();
  }


  /** 停录：native 摘要 + 落盘大小/时长；autoReason 非空时附“已保存”文案 */
  private finishGpuRecord(autoReason: string = ''): string {
    if (!this.recording && this.lastStopSummary !== '') {
      return this.lastStopSummary; // 迟到回调不得重复 stop/覆盖摘要
    }
    if (this.stopGraceTimer >= 0) {
      clearTimeout(this.stopGraceTimer);
      this.stopGraceTimer = -1;
    }
    this.stopAutoStopMonitor();
    this.stopRecordMonitor();
    const summary: string = this.recorder.stop();
    this.recording = false;
    this.stopPending = false;
    this.recordStartedAtMs = 0;
    if (autoReason !== '') {
      this.stopReason = this.stopReason === '' ? autoReason : this.stopReason;
    }
    const reason: string = this.stopReason;
    this.stopReason = '';
    const saved: boolean = summary.indexOf('status=ok') >= 0;
    const reasonText: string = autoReasonText(reason, saved);
    this.message = `GPU record: ${summary}${reasonText === '' ? '' : `（${reasonText}）`}`;
    this.lastStopSummary = summary;
    this.lastStopAtMs = Date.now();
    return summary;
  }

  /**
   * 录制期监视（500ms）：消费 native 真实的 isGpuRecording/getGpuRecordError。
   * muxer 写失败/编码 pass 中止会使 native 侧自行置为不可录制；此处收尾停录并显示机器码。
   */
  private startRecordMonitor(): void {
    this.stopRecordMonitor();
    this.recordTimer = setInterval((): void => {
      if (this.disposed || !this.recording) {
        this.stopRecordMonitor();
        return;
      }
      if (this.stopPending) {
        return; // 宽限收尾中不再轮转
      }
      const lib: GlLibApi | undefined = this.glLib;
      const ctx: common.Context | undefined = this.hostContext();
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
        // 先确认 native 仍存活再轮转；native 已失败时走下方停止路径，不得静默开新段
        const rotateResult: string = this.recorder.maybeRotate(ctx, lib);
        if (rotateResult.indexOf('error:') === 0) {
          const base: string = this.message;
          this.finishGpuRecord();
          this.message = `${base}（分段轮转失败，已停止，保留已完成段：${rotateResult}）`;
          return;
        }
        if (rotateResult.indexOf('rotated:') === 0) {
          this.message = `GPU recording 分段 ${rotateResult.substring('rotated:'.length)}`;
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
      const summary: string = this.finishGpuRecord();
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

  build() {
    Stack() {
      // libraryname 与 cpp/CMakeLists.txt 的 add_library(entry ...) 及 napi nm_modname 三者一致
      XComponent({ id: 'wotaGlCamera', type: XComponentType.SURFACE, libraryname: 'entry' })
        .onLoad(() => {
          this.loadNativeModule();
        })
        .height('100%')
        .width('100%')

      Column({ space: 8 }) {
        Text(this.message)
          .fontSize(12)
          .fontColor(Color.White)
          .maxLines(3)
          .textAlign(TextAlign.Center)
        Row({ space: 8 }) {
          Button(`效果: ${this.effectLabel}`)
            .fontSize(12)
            .onClick(() => {
              this.cycleEffect();
            })
          Button(`曲线叠加: ${this.curveOverlay ? '开' : '关'}`)
            .fontSize(12)
            .enabled(this.effectIndex === 2 || this.effectIndex === 3)
            .onClick(() => {
              this.toggleCurveOverlay();
            })
          Button(`毛玻璃: ${this.frostEnabled ? '开' : '关'}`)
            .fontSize(12)
            .onClick(() => {
              this.toggleFrost();
            })
          Button('返回')
            .fontSize(12)
            .onClick(() => {
              // 先置 disposed 再返回：轮询/权限/起镜回调立即失效（aboutToDisappear 再兜底收尾）
              this.disposed = true;
              this.getUIContext().getRouter().back();
            })
        }
        Row({ space: 8 }) {
          Button(this.stopPending ? '收尾中…' : (this.recording ? '停录' : '录 GPU'))
            .fontSize(12)
            .enabled(!this.stopPending && this.glFatal === '')
            .onClick(() => {
              this.toggleGpuRecord();
            })
          Button(`转换: ${this.arcLabel()}`)
            .fontSize(12)
            .enabled(!this.recording)
            .onClick(() => {
              this.cycleArcMode();
            })
          Button(`分段测试:${this.segmentTestOn ? '8MB' : '关'}`)
            .fontSize(12)
            .enabled(!this.recording)
            .onClick(() => {
              this.toggleSegmentTest();
            })
          Button('媒体库')
            .fontSize(12)
            .enabled(!this.recording)
            .onClick(() => {
              this.getUIContext().getRouter().pushUrl({ url: 'pages/Gallery' });
            })
        }
      }
      .alignItems(HorizontalAlign.Center)
      .width('100%')
      .padding(16)
    }
    .height('100%')
    .width('100%')
    .backgroundColor(Color.Black)
  }
}
