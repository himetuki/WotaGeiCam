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
import { GlRecordLib, GlRecorder } from '../camera/GlRecorder';
import { requestCameraPermission } from '../camera/PermGate';
import { CurveStack } from '../core/CurveModel';
import { FrameEffect, PeakingColor } from '../core/Constants';
import { BitratePolicy } from '../record/BitratePolicy';

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

@Entry
@Component
struct GlPreview {
  @State message: string = 'waiting for GL surface...';
  @State effectLabel: string = '直通';
  @State curveOverlay: boolean = false;
  @State frostEnabled: boolean = false;
  @State recording: boolean = false;
  private engine: GlCameraEngine = new GlCameraEngine();
  private recorder: GlRecorder = new GlRecorder();
  private pollTimer: number = -1;
  /** 录制期监视定时器：native 端写失败自中止（isGpuRecording=false）时页面据此收尾并显示原因 */
  private recordTimer: number = -1;
  private glLib: GlLibApi | undefined = undefined;
  /** 页面销毁后取消所有异步链（轮询/权限/起镜 then 回调不得越过返回继续跑） */
  private disposed: boolean = false;
  /** 效果循环序号：0 直通 / 1 曲线 / 2 斑马纹 / 3 峰值 */
  private effectIndex: number = 0;

  aboutToAppear(): void {
    this.disposed = false;
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
    if (this.recorder.isRecording()) {
      this.finishGpuRecord();
    }
    this.stopRecordMonitor();
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

  private hostContext(): common.Context | undefined {
    return this.getUIContext().getHostContext() as common.Context | undefined;
  }

  /**
   * 录 GPU/停录（GL-4 最小切片）：前置 = 原生模块已加载、相机会话已起（native surface 就绪）、未在录制；
   * 尺寸/码率固定档，失败显示 native 机器码（页面不做重试编排）。
   */
  private toggleGpuRecord(): void {
    const lib: GlLibApi | undefined = this.glLib;
    if (lib === undefined) {
      this.message = 'GL module not ready';
      return;
    }
    if (this.recorder.isRecording()) {
      this.finishGpuRecord();
      return;
    }
    if (this.engine.lastLensInfo() === undefined) {
      this.message = 'GPU record: camera not ready (no native surface)';
      return;
    }
    // 起录前刷新显示几何：muxer 旋转 hint 读 native 的 sensor/display，快速转屏后不得用旧值/0
    this.applyGeometry();
    const ctx: common.Context | undefined = this.hostContext();
    if (ctx === undefined) {
      this.message = 'no host context';
      return;
    }
    const bitrate: number = BitratePolicy.resolve(0, GPU_RECORD_WIDTH, GPU_RECORD_HEIGHT, GPU_RECORD_FPS, false);
    const error: string = this.recorder.start(ctx, lib, GPU_RECORD_WIDTH, GPU_RECORD_HEIGHT,
      GPU_RECORD_FPS, bitrate);
    if (error !== '') {
      this.message = `GPU record failed: ${error}`;
      return;
    }
    this.recording = true;
    this.message = `GPU recording ${GPU_RECORD_WIDTH}x${GPU_RECORD_HEIGHT}@${GPU_RECORD_FPS} bitrate=${bitrate}`;
    this.startRecordMonitor();
  }

  /** 停录：native 摘要 + 落盘大小/时长（失败/中止原因含在摘要里） */
  private finishGpuRecord(): void {
    this.stopRecordMonitor();
    const summary: string = this.recorder.stop();
    this.recording = false;
    this.message = `GPU record: ${summary}`;
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
      const lib: GlLibApi | undefined = this.glLib;
      if (lib === undefined) {
        return;
      }
      let alive: boolean = false;
      try {
        alive = lib.isGpuRecording();
      } catch (e) {
        alive = false;
      }
      if (alive) {
        return;
      }
      let reason: string = '';
      try {
        reason = lib.getGpuRecordError();
      } catch (e) {
        reason = '';
      }
      this.finishGpuRecord();
      if (reason !== '') {
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
          Button(this.recording ? '停录' : '录 GPU')
            .fontSize(12)
            .onClick(() => {
              this.toggleGpuRecord();
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
