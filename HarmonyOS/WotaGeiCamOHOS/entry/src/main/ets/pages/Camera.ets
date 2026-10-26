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
 * T22 健康收口——三格语义对照表（对齐上游 CodecRecorder/MrRecorder 的健康判定口径）：
 * | 上游信号 | OHOS 等价物 | 进判定 |
 * | MediaCodec onOutputFormatChanged（videoTrackAdded） | OH_AVCodecCallback onStreamChanged（native formatChanged_，getGpuHealth 第 4 字段 fmt） | 是（videoTrackAdded 实参 = fmt || samples>0，samples 兜底防 onStreamChanged 时序不触） |
 * | muxer started（muxStarted） | OH_AVMuxer_Start 成功（muxStarted_，第 2 字段） | 是 |
 * | 首个视频样本（firstVideoSample） | 成功写入 muxer 的样本数 >0（第 3 字段 samples） | 是 |
 * | —（无对应） | muxer AddTrack 成功（trackAdded，第 1 字段） | 否（OHOS 特有诊断位，仅 Diag 日志） |
 * 健康重录 attempts 为会话级计数（轮转重启健康窗不清零，防无限重录——与上游"每次起录清零"口径差异登记）；
 * 轮转后若巡检已停（上一段已健康）则重启健康窗；健康重录/GIVE_UP 丢段读 stop 摘要末段账
 * （lastPath/lastBytes/lastSamples），保留已完成前段（被丢零样本段在首段 sidecar segments 留 count=0 条目，无害登记）。
 *
 * 边界（登记）：J3a 起 hud_items 位掩码已接 UI——顶栏固定两行之下的「读数条」按掩码渲染
 * （换行档走 J1/J2 的 hudPerRowFor+hudRoomDp+hudRowGroups；条位置为临时落位，J3b 挪进 READOUT
 * zone 并换 planReadoutRow 的同行裁决）；拖拽布局编辑器（HudLayoutEditor）仍留 J3b。
 * PillPopup 为最小实现（无动效/Tokens/文字缩放层/SheetSpec）；tint 副轴/对焦弹层留白；视频-only。
 * **高级参数持久化已落（本批 I，对齐上游 ui/ParamsPersist.kt）**：24 键白名单（`ui/ParamsPersist.ets`
 * 纯逻辑 + `ui/ParamsStore.ets` 平台件，与 SettingsStore 同一个 `wota_camera_settings` store）——
 * 冷启 `bootstrap` 读完设置后 `ParamsPersist.restore` 灌总线；落盘点分两档——关键点立即
 * （`onPageHide`/`aboutToDisappear`/起录前/点按循环/关弹层），滑杆逐帧路径走 300ms 尾随节流
 * （`ui/ParamsFlushThrottle`；一次 flush 只 commit 一次）。会话影子只写与影子不同的字段。
 * 排除清单照搬上游：renderMode/sampleRate/audioEnabled/focusPoint/curve/arc_convert_mode。
 * 设置持久化走 ui/SettingsStore（键名已迁移到上游名：`default_fps`/`default_bitrate`/`default_size`）。
 * 加载顺序契约：不得页面顶层静态 import 'libentry.so'，XComponent onLoad 后 dynamic import 桥。
 *
 * T31 取景页可用性修复（真机 HUAWEI ALN-AL00 / 1260x2720 取证）：
 * - 沉浸式：WindowChrome.applyCamera（setWindowLayoutFullScreen + 透明系统栏 + 白色图标），
 *   XComponent 由 [0,124]..[1260,2629] 铺到全屏；HUD 按 AvoidArea(TYPE_SYSTEM|TYPE_CUTOUT) 留边；
 * - 胶囊标签走 ui/HudPill（@Component + @Prop）——原 @Builder 按值传 label 不随 @State 刷新，
 *   真机表现为顶栏正确而胶囊恒为「镜头:—/画幅:—」；
 * - Dock 改 2×4 两行主胶囊（镜头/画幅/帧率/变焦 + 快门/ISO/EV/WB），48vp、无横向 Scroll；
 *   闪光/效果/光弧/网格收进「更多」面板（ui/HudMoreSheet，复用 PillPopup 遮罩/量框）；
 * - 录制键 72vp 圆形白环 + 44vp 红点（录制中 24vp 红方块、收尾中禁用）；
 * - 网格确定性三行三列（2 横 2 竖 Divider），不再依赖百分比 position；
 * - 删除预览帧率死读数，只显录制帧率「Nfps」（复审 P3-2：去「录」前缀防 360vp 截字）。
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
import { ContentPoint, PreviewBox, PreviewFit } from '../core/PreviewFit';
import { AeMode, AfMode, ArcMergeDeadband, FrameEffect, HudItem, HudItems, PeakingColor, Size } from '../core/Constants';
import { CameraAbility, forcedShutterOutOfRange } from '../core/CameraAbility';
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
import { RecordTask } from '../record/RecordTask';
import { DeviceStatus } from '../camera/DeviceStatus';
import { BitratePolicy } from '../record/BitratePolicy';
import { VideoStore } from '../record/VideoStore';
import { CameraSettings, SettingsStore } from '../ui/SettingsStore';
import { ParamsPersist, ParamsPersistSession, ParamsPrefs } from '../ui/ParamsPersist';
import { ParamsStore } from '../ui/ParamsStore';
import { FlushScheduler, ParamsFlushThrottle } from '../ui/ParamsFlushThrottle';
import { LevelReading, LevelSensor } from '../camera/LevelSensor';
import { AttitudeMeter } from '../ui/AttitudeMeter';
import { RefLineOverlay } from '../ui/RefLineOverlay';
import { RefLine, RefLineType } from '../core/RefLine';
import { HUD_ROW_GAP_DP, hudPerRowFor, hudRoomDp } from '../core/HudMetrics';
import { HudEntry, HudLayoutTable, HudZone, hudRowGroups } from '../core/HudLayout';
import { UserSettings } from '../core/UserSettings';
import { FocusTapArbiter, FocusTapMath } from '../core/FocusTap';
import { TapPoint } from '../core/Constants';
import { HudSpec } from '../ui/HudSpec';
import { HudColor, HudShape, HudSpace, HudTouch, HudType, PageColor } from '../ui/HudTokens';
import { HudEntryButton, HudPill } from '../ui/HudPill';
import { HudMoreSheet } from '../ui/HudMoreSheet';
import { CurvePopup } from '../ui/CurvePopup';
import { SafeInsets, WindowChrome } from '../ui/WindowChrome';
import { evText, shutterText, wbShort } from '../ui/ParamFormat';
import { PillRect } from '../ui/PillPopup';
import {
  EvPillPopup, FocusPillPopup, IsoPillPopup, ShutterPillPopup, WbPillPopup, pillEditableDuringRecording
} from '../ui/ParamPills';
import { LensSlot } from '../core/LensSlot';

interface GlLibApi extends GlRecordLib {
  getInputSurfaceId: () => string;
  getNativeImageError: () => string;
  setDisplayGeometry: (sensorDeg: number, displayDeg: number, mirrored: boolean) => void;
  setDisplayFit: (fitX: number, fitY: number) => void;
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
/** 滑杆逐帧路径的落盘节流窗口：首请求立即落一次，之后每次动作完成后最多补一次 */
const PARAMS_FLUSH_WINDOW_MS: number = 300;

/** 节流器定时器注入（`ui/ParamsFlushThrottle` 是纯逻辑，真定时器只出现在页面层） */
class PageFlushScheduler implements FlushScheduler {
  arm(delayMs: number, fn: () => void): number {
    return setTimeout(fn, delayMs);
  }

  disarm(handle: number): void {
    clearTimeout(handle);
  }
}

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
  @State effectLabel: string = '直通';
  /** RGB 曲线栈（CURVE 批；编辑弹窗与 GL LUT 上传的数据源） */
  @State curveStack: CurveStack = CurveStack.IDENTITY;
  @State curvePopupVisible: boolean = false;
  /** 曲线编辑入口一次性提示旗（会话级） */
  private curveHintShown: boolean = false;
  @State gridOn: boolean = true;
  /** 取景参考线位掩码（设置页多选；`gridOn` 额外把三分法网格并进来） */
  @State refMask: number = 0;
  /**
   * 取景读数条位掩码（上游 hud_items；设置页「取景读数」多选，onPageShow 读取）。
   * 0 = 整条不渲染；J3a 临时落位在固定两行之下，J3b 挪进 READOUT zone。
   */
  @State hudItemsMask: number = HudItems.DEFAULT_MASK;
  /** 读数条容器实测宽（vp）：首帧按屏幕宽兜底，onAreaChange 接管（J1 宽度账要实测宽） */
  @State hudStripWidthVp: number = 0;
  /** TFC 批：对焦标记（点按对焦的可视反馈；窗口 vp 坐标，800ms 自动隐） */
  @State focusMarkVisible: boolean = false;
  @State focusMarkX: number = 0;
  @State focusMarkY: number = 0;
  /** 水平仪/俯仰仪（上游需求 24/25「必须」）：读数已换算到显示方向、已折叠到 ±90 */
  @State levelOn: boolean = true;
  @State levelAvailable: boolean = true;
  @State levelRoll: number = 0;
  @State levelPitch: number = 0;
  @State levelIsLevel: boolean = false;
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
  /** 水平仪数据源（页面可见时才采样；隐藏/销毁必停，传感器常开很费电） */
  private levelSensor: LevelSensor | undefined = undefined;
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
  /** 高级参数总线（T21）：启动值由 ParamsPersist.restore 灌入（本批 I），录制页改动经会话影子落盘 */
  private params: WotaParams = new WotaParams();
  /** 参数持久化平台门面与会话（本批 I）：aboutToAppear 建、aboutToDisappear 清 */
  private persistPrefs: ParamsPrefs | undefined = undefined;
  private persister: ParamsPersistSession | undefined = undefined;
  /** 滑杆高频落盘节流（300ms 尾随）：首请求立即、窗口内合并、尾部补一次 */
  private flushThrottle: ParamsFlushThrottle | undefined = undefined;
  /** 最近一次会话期能力真值（起镜成功/开弹层时刷新；停镜置空） */
  private ability: CameraAbility | undefined = undefined;
  /** 读数条换行档的字体档输入（hudPerRowFor 的 fontScale；<1 档在该函数内部夹回 1） */
  private hudFontScale: number = 1;
  /** 窗口 vp 尺寸（FocusTapMath 归一化用；refreshHudReadoutMetrics 顺手刷新） */
  private winWvp: number = 0;
  private winHvp: number = 0;
  /**
   * WLB：画面在窗口内的矩形（letterbox 单一真源）——参考线 aspect、点按映射、native 上屏
   * 收缩因子三处都吃它，不许各算一遍。宽高比不可用/首帧未量到时 = 铺满（= 旧行为）。
   * 视口 = 窗口全幅即 GL 表面全幅（本侧沉浸式 setWindowLayoutFullScreen，模拟器实证
   * 根 Stack 377.14×816vp、native surface created 1320x2856 逐值吻合）。
   */
  private previewBox: PreviewBox = new PreviewBox(0, 0, 0, 0, 0);
  private focusMarkTimer: number = -1;
  /** FL 批：点按/双击/长按三击派发仲裁（单击=对焦、双击=锁定、长按=解锁重对焦） */
  private tapArbiter: FocusTapArbiter = new FocusTapArbiter(280);
  /** 锁定期首次轻触提示已给（每次上锁重置，对齐上游 lockedTapTipShown） */
  private lockedTapTipShown: boolean = false;
  /** hud_layout 位置表缓存（键 = 原文串；见 readoutTable()） */
  private hudTableCache: HudLayoutTable | undefined = undefined;
  private hudTableRaw: string = '';
  /** 当前打开的高级参数弹层（'' = 无）：快门/ISO/EV/WB */
  @State activePill: string = '';
  /** 弹层锚点（窗口 px；零矩形 = 未量到，PillPopup 内部走底贴屏回退） */
  private pillAnchor: PillRect = { left: 0, top: 0, right: 0, bottom: 0 };
  /** 「更多」面板锚点（pill_more 的窗口矩形） */
  private moreAnchor: PillRect = { left: 0, top: 0, right: 0, bottom: 0 };
  /** 顶栏第二行读数（参数流驱动，subscribe 里重算） */
  @State rowShutter: string = '-';
  @State rowIso: string = '-';
  @State rowEv: string = '-';
  @State rowWb: string = '-';
  /** 高级参数胶囊当前值标签（T31 起由 HudPill @Prop 消费，状态变即刷新） */
  @State pillShutterLabel: string = 'AUTO';
  @State pillIsoLabel: string = 'AUTO';
  @State pillEvLabel: string = '+0.0EV';
  @State pillWbLabel: string = '自动';
  /** T31 沉浸式安全区（vp）：顶部状态栏/挖孔、底部导航条 */
  @State safeTop: number = 0;
  @State safeBottom: number = 0;
  /** T31「更多」面板（闪光/效果/光弧/网格） */
  @State moreOpen: boolean = false;

  aboutToAppear(): void {
    this.disposed = false;
    this.pageVisible = true;
    this.ctx = this.getUIContext().getHostContext() as common.Context | undefined;
    // 入档会话：此刻冻结影子；冷启 restore 完成后由 bootstrap 调 resync 重新冻结（恢复值不算用户动作）
    const prefs: ParamsPrefs | undefined = this.ctx === undefined ? undefined : ParamsStore.open(this.ctx);
    this.persistPrefs = prefs;
    this.persister = prefs === undefined ? undefined : new ParamsPersistSession(prefs, this.params);
    // 滑杆逐帧回调走尾随节流；无会话时不建定时器（action 本就是空操作）
    this.flushThrottle = prefs === undefined ? undefined : new ParamsFlushThrottle(PARAMS_FLUSH_WINDOW_MS,
      (): number => Date.now(), new PageFlushScheduler(), (): void => {
        this.flushParamsDirect();
      });
    this.bindParamsLabels();
    this.initLevelSensor();
    this.applyImmersiveChrome();
    this.refreshHudReadoutMetrics();
    this.engine.setOnCameraLost((): void => {
      if (this.disposed) {
        return;
      }
      this.closePill(); // T22 P3：相机丢失/session error 处置统一收口弹层（锚点/能力快照已失效）
      this.cameraStarted = false;
      this.hideFocusMark();
      this.tapArbiter.reset();
      this.lockedTapTipShown = false;
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
        this.syncLevelDisplayDegrees();
        this.refreshHudReadoutMetrics();
      });
    } catch (e) {
    }
    this.bootstrap();
  }

  // ------------------------------------------------------------------ 水平仪/俯仰仪

  /** 建数据源（不启动）：回调只落 @State，判定仍全在 LevelMath/LevelSensor */
  private initLevelSensor(): void {
    if (this.levelSensor !== undefined) {
      return;
    }
    const sensor: LevelSensor = new LevelSensor((r: LevelReading): void => {
      if (this.disposed) {
        return;
      }
      this.levelRoll = r.roll;
      this.levelPitch = r.pitch;
      this.levelIsLevel = r.isLevel;
    });
    this.levelSensor = sensor;
    this.levelAvailable = sensor.available;
    this.syncLevelDisplayDegrees();
    sensor.buzzEnabled = this.settings.levelBuzz;
  }

  /**
   * 把当前显示旋转喂给传感器（roll 必须换算到显示帧：加速度计是机头坐标系，
   * 只在竖屏与屏幕系重合）。取不到显示信息时按 0 处理（等于不换算的旧行为，登记）。
   */
  private syncLevelDisplayDegrees(): void {
    const sensor: LevelSensor | undefined = this.levelSensor;
    if (sensor === undefined) {
      return;
    }
    try {
      sensor.setDisplayDegrees(display.getDefaultDisplaySync().rotation * 90);
    } catch (e) {
      sensor.setDisplayDegrees(0);
    }
  }

  /** 按设置与页面可见性启停采样（关掉显示或页面隐藏都停——传感器常开很费电） */
  private applyLevelSetting(): void {
    const sensor: LevelSensor | undefined = this.levelSensor;
    if (sensor === undefined) {
      return;
    }
    this.levelOn = this.settings.levelEnabled && sensor.available;
    sensor.buzzEnabled = this.settings.levelBuzz;
    if (this.levelOn && this.pageVisible && !this.disposed) {
      sensor.start();
    } else {
      sensor.stop();
    }
  }

  onPageShow(): void {
    this.pageVisible = true;
    if (this.disposed) {
      return;
    }
    this.setKeepScreenOn(true);
    this.applyImmersiveChrome();
    this.resumeHealthAfterShow();
    this.syncLevelDisplayDegrees();
    this.applyLevelSetting();
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
      this.refMask = s.refLines;
      this.hudItemsMask = s.hudItems;
      this.curveStack = CurveStack.decode(s.curveStackRaw);
      this.applyLevelSetting();
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
    // 退后台是关键点：取消未触发的尾随并立即落盘（旁路副作用，失败只记日志）
    this.flushParamsNow();
    this.cancelTapTimers();
    // 传感器只在可见时采样：后台常开很费电（上游 DisposableEffect 同义）
    if (this.levelSensor !== undefined) {
      this.levelSensor.stop();
    }
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
    this.hideFocusMark();
    this.pageVisible = false;
    // 离开录制页：取消节流定时器、补最后一次落盘，然后销毁会话（晚到回调不再写盘）
    this.flushParamsNow();
    this.flushThrottle = undefined;
    this.persister = undefined;
    this.persistPrefs = undefined;
    if (this.levelSensor !== undefined) {
      this.levelSensor.stop();
      this.levelSensor = undefined;
    }
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
      this.refMask = s.refLines;
      this.effectIndex = s.effect;
      // 冷启恢复（本批 I）：24 键白名单灌进参数总线（只写 value，range/enabled/exact 不动）；
      // 机型域钳制仍由起镜后的 applyAbility 收口，本处不绕开。恢复值不是用户动作，
      // 完成后 resync 影子，避免第一次 flush 把它们当改动写回。
      const prefs: ParamsPrefs | undefined = this.persistPrefs;
      if (prefs !== undefined) {
        ParamsPersist.restore(prefs, this.params);
        const persister: ParamsPersistSession | undefined = this.persister;
        if (persister !== undefined) {
          persister.resync();
        }
      }
      this.syncLabels();
      this.applyLevelSetting();
      if (!this.pageVisible) {
        return; // 隐藏页只落设置，不请求权限/起镜；onPageShow 补发
      }
      this.ensureCameraPermission();
    }).catch((e: Object): void => {
      this.loading = false;
      this.message = `读取设置失败: ${e}`;
    });
  }

  /**
   * T31 沉浸式窗口：全屏布局 + 透明系统栏（白色图标），并读安全区给 HUD 留边。
   * 失败只记日志并保持默认外观（退路：非全屏 + 黑底白图标）。
   */
  private applyImmersiveChrome(): void {
    const ctx: common.Context | undefined = this.ctx;
    if (ctx === undefined) {
      return;
    }
    WindowChrome.lastWindow(ctx).then((win): Promise<SafeInsets> =>
      WindowChrome.applyCamera(win)).then((insets: SafeInsets): void => {
      if (this.disposed) {
        return;
      }
      // 兜底下限：系统不报 avoidArea 时按状态栏/手势条常见高度留边，避免 HUD 压挖孔
      this.safeTop = Math.max(24, Math.round(insets.top));
      this.safeBottom = Math.max(12, Math.round(insets.bottom));
    }).catch((e: Object): void => {
      Diag.w(WOTA_RECORD_TAG, `immersive chrome failed: ${e}`);
    });
  }

  /** 「更多」面板：量锚点后开（锚点失败零矩形 → PillPopup 底贴屏回退） */
  private openMore(): void {
    this.moreAnchor = { left: 0, top: 0, right: 0, bottom: 0 };
    try {
      const info = this.getUIContext().getComponentUtils().getRectangleById('pill_more');
      this.moreAnchor = {
        left: info.windowOffset.x,
        top: info.windowOffset.y,
        right: info.windowOffset.x + info.size.width,
        bottom: info.windowOffset.y + info.size.height
      };
    } catch (e) {
      // 锚点量取失败：保持零矩形（回退底贴屏）
    }
    this.moreOpen = true;
  }

  private closeMore(): void {
    this.moreOpen = false;
  }

  private syncLabels(): void {
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
    // T22 P3：强制档（1/24、1/25）超出本机曝光范围时读数追加 ※（上游 CameraScreen.kt:1195-1203
    // 口径：MANUAL 且 ability.exposureNs 在册且 forcedShutterOutOfRange，与快门弹层/下发侧同源判据）
    const shutterNs: number = this.params.shutter.value.value;
    let shutterLabel: string = shutterRangeOk ? shutterText(shutterNs) : '-';
    if (manual && shutterRangeOk && this.ability !== undefined &&
      forcedShutterOutOfRange(shutterNs, this.ability.exposureNs)) {
      shutterLabel += '※';
    }
    this.rowShutter = manual ? shutterLabel : 'AUTO';
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
   * 弹层改动下发（T21）：engine.applyParams 逐键报告；failed 项 Diag.w + 用户提示。
   * T22 P3：skipped 摘要改 Diag.i（上游只进日志，不占用户提示位），仅 failed 留 appendNotice。
   */
  private applyParamsFromPills(): void {
    const report: ApplyReport = this.engine.applyParams(this.params);
    this.syncParamLabels();
    // 落盘（本批 I）：滑杆 onValueChange 每帧到达，走 300ms 尾随节流（首请求立即、尾部补一次），
    // 不在拖动期逐帧同步落盘。旁路副作用——写在错误提示之前，不改变既有下发时序。
    this.flushParamsDeferred();
    if (report.failed.length > 0) {
      Diag.w(WOTA_RECORD_TAG, `pill apply failed: ${report.failed.join('|')}`);
      this.appendNotice(`参数下发失败: ${report.failed.join(',').substring(0, 80)}`);
      return;
    }
    const notable: Array<string> = report.skipped.filter((s: string): boolean => s.indexOf('tint:') !== 0);
    if (notable.length > 0) {
      Diag.i(WOTA_RECORD_TAG, `pill apply skipped: ${notable.join('|')}`);
    }
  }

  /** 起录前兜底下发一次（T21）：ApplyReport failed 项 Diag.w（不起录，录制键另有门控） */
  private applyParamsBeforeRecord(): void {
    const report: ApplyReport = this.engine.applyParams(this.params);
    // 起录是关键点：可能紧接退后台/进程被杀，参数必须先落盘（立即通道，不受节流延后）
    this.flushParamsNow();
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
    const wasOpen: boolean = this.activePill !== '';
    this.activePill = '';
    if (wasOpen) {
      // 关弹窗是动作完成点（对齐上游收手/关弹层落盘）：把拖动期尚未尾随落盘的值立即落一次
      this.flushParamsNow();
    }
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
    // 画幅循环是参数动作完成点：SettingsStore 已写 default_size，这里再收一次总线侧的待落盘改动
    this.flushParamsNow();
    this.restartCamera();
  }

  private cycleFps(): void {
    if (this.recording || this.stopPending) {
      return;
    }
    this.settings.fps = this.settings.fps === 24 ? 25 : (this.settings.fps === 25 ? 30 : 24);
    // T21：fps 档位换挡 → 参数总线重算（快门上限吃帧率档位）
    this.params.fps.value = ParamState.of(this.settings.fps);
    if (this.ability !== undefined) {
      this.params.applyAbility(this.ability);
    }
    this.syncParamLabels();
    this.persistSettings();
    // 帧率循环同时改了总线 fps：立刻落盘（default_fps 与 SettingsStore 的新键同名同值）
    this.flushParamsNow();
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
    // 变焦循环是参数动作完成点（总线 zoom 未跟随 engine，flush 对 zoom 无差异；登记差异见 PORT_MAP）
    this.flushParamsNow();
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
      // 闪光开关是参数动作完成点（总线 flash 未跟随 engine，登记差异见 PORT_MAP）
      this.flushParamsNow();
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
    if (this.effectIndex === 1 && !this.curveHintShown) {
      this.curveHintShown = true;
      this.message = '长按「更多」面板的效果钮可编辑 RGB 曲线';
    }
    this.persistSettings();
  }

  /** CURVE：曲线栈变化 → 重传 LUT；曲线档位上同步开关（恒等曲线开着无视觉差） */
  private applyCurve(): void {
    const lib: GlLibApi | undefined = this.glLib;
    if (lib === undefined) {
      return;
    }
    try {
      lib.uploadCurveLut(this.curveStack.bakeBytes().buffer as ArrayBuffer);
      if (this.effectIndex === 1) {
        lib.setCurveEnabled(!this.curveStack.isPassthrough);
      }
    } catch (e) {
      this.message = `曲线上传失败: ${e}`;
    }
  }

  private openCurveEditor(): void {
    this.closePill();
    this.moreOpen = false;
    this.curvePopupVisible = true;
  }

  private onCurveCommit(stack: CurveStack, persist: boolean): void {
    this.curveStack = stack;
    this.applyCurve();
    if (persist) {
      this.settings.curveStackRaw = stack.encode();
      this.persistSettings();
    }
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
        lib.uploadCurveLut(this.curveStack.bakeBytes().buffer as ArrayBuffer);
        lib.setCurveEnabled(!this.curveStack.isPassthrough);
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

  /**
   * 关键点落盘（不可延后）：取消未触发的尾随，立刻 flush 一次。
   * 用在退后台、离开页、起录前、各点按循环、关弹层——这些点是「用户动作完成点」，
   * 之后可能直接被杀进程，不能被 300ms 节流吃掉。
   */
  private flushParamsNow(): void {
    const throttle: ParamsFlushThrottle | undefined = this.flushThrottle;
    if (throttle !== undefined) {
      throttle.cancel();
    }
    this.flushParamsDirect();
  }

  /**
   * 高频路径落盘（滑杆 `onValueChange` 逐帧）：300ms 尾随节流——首次请求立即落一次，
   * 窗口内的重复请求只在尾部补一次，避免拖动期间把同步 `flushSync` 打成每帧一次。
   */
  private flushParamsDeferred(): void {
    const throttle: ParamsFlushThrottle | undefined = this.flushThrottle;
    if (throttle === undefined) {
      this.flushParamsDirect(); // 会话未就绪：退回直接落盘（persister 为空时是空操作）
      return;
    }
    throttle.request();
  }

  /**
   * 参数落盘（本批 I）：会话影子只写与影子不同的字段，一次 flush 只 commit 一次。
   * 纯旁路副作用——失败只记日志，绝不改变下发时序/错误提示/录制流程。
   */
  private flushParamsDirect(): void {
    const persister: ParamsPersistSession | undefined = this.persister;
    if (persister === undefined) {
      return;
    }
    try {
      if (!persister.flush()) {
        Diag.w(WOTA_RECORD_TAG, 'params persist flush failed (next action retries)');
      }
    } catch (e) {
      Diag.w(WOTA_RECORD_TAG, `params persist flush threw: ${e}`);
    }
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
    this.applyPreviewFit();
  }

  /**
   * WLB：按「预览档尺寸 × 表面尺寸 × 旋转」算画面矩形，并下发 native 上屏收缩因子。
   * 旋转 = native 上屏 UV 旋转的同一复合角 normalize(sensorDeg − displayDeg)（gl_render
   * buildTexCoords 同式；缓冲矩阵分量 stRotDeg ArkTS 不可读，按 0 处理——登记）。竖屏典型
   * sensor=90/display=0 → 90 → 内容轴交换，矩形才是竖档；用 displayDeg 单侧会把画面压成横带
   * （2026-10-26 真机取证）。几何/尺寸/视口任一变化都重算。
   */
  private applyPreviewFit(): void {
    const size: Size | undefined = this.engine.currentSize();
    const info: GlLensInfo | undefined = this.engine.lastLensInfo();
    const raw: number = info === undefined ? this.displayDeg() : info.sensorDeg - this.displayDeg();
    const rotation: number = ((raw % 360) + 360) % 360;
    this.previewBox = PreviewFit.rectOf(this.winWvp, this.winHvp,
      size === undefined ? 0 : size.width, size === undefined ? 0 : size.height, rotation);
    const lib: GlLibApi | undefined = this.glLib;
    if (lib === undefined) {
      return;
    }
    try {
      lib.setDisplayFit(PreviewFit.ndcScaleX(this.previewBox, this.winWvp),
        PreviewFit.ndcScaleY(this.previewBox, this.winHvp));
    } catch (e) {
    }
  }

  /** 参考线/网格的内接宽高比（画面矩形不可用时 0 = 铺满，与旧行为一致） */
  private refLineAspect(): number {
    return this.previewBox.aspect;
  }

  private readoutText(key: string): string {
    if (key === 'lens') {
      return this.lensLabel;
    }
    if (key === 'size') {
      return this.sizeLabel;
    }
    if (key === 'fps') {
      // T31：删预览帧率死读数（GlCameraEngine 无 previewFps 真值）；
      // 只保留录制帧率，且不带「录」前缀——360vp 宽下 5 等分约 45.6vp，「录30fps」会被省略
      return `${this.settings.fps}fps`;
    }
    if (key === 'battery') {
      return this.batteryPct >= 0 ? `${this.batteryPct}%` : '—';
    }
    if (key === 'free') {
      if (this.freeMb < 0) {
        return '—';
      }
      return this.freeMb >= 1024 ? `${Math.floor(this.freeMb / 1024)}GB` : `${this.freeMb}MB`;
    }
    return formatElapsed(this.elapsedMs);
  }

  /** 顶栏第二行读数（T21）：快门/ISO/EV/WB（值由 syncParamLabels 预算进 @State）；
   * J3a 起固定行只留时长，这四个值改由 hud_items 读数条消费（同一真源，不另算一份） */
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

  /**
   * 读数条换行档的两枚运行时输入（J1 宽度账）：首帧按屏幕宽（px→vp，再扣左右安全边距）兜底，
   * 之后由读数条容器的 onAreaChange 接管——J1 审查口径：喂 hudRoomDp 的必须是
   * 调用点同一表达式算出的**实测**可用宽，不许直接塞 screenWidthDp。
   */
  private refreshHudReadoutMetrics(): void {
    try {
      const d: display.Display = display.getDefaultDisplaySync();
      const density: number = d.densityPixels > 0 ? d.densityPixels : 1;
      this.hudStripWidthVp = Math.max(0, d.width / density - 2 * HudSpec.SAFE_MARGIN);
      // TFC：窗口 vp 尺寸（tap-to-focus 归一化基准；旋转后 display.on('change') 会重刷）
      this.winWvp = Math.max(0, d.width / density);
      this.winHvp = Math.max(0, d.height / density);
      this.applyPreviewFit(); // WLB：视口变化（旋转/分屏/窗口尺寸）后重算画面矩形
    } catch (e) {
      // 量不到：保持现值（首帧兜底 0 → hudPerRowFor 退 1 颗一行，宁可高也不裁字）
    }
    // 字体缩放档：Display 上没有 fontScale（SDK 核实），读 UIAbility 的
    // Configuration.fontSizeScale（API 18+；未配置/读不到 → 1，<1 档由 hudPerRowFor 内部夹回 1）
    try {
      const ctx: common.UIAbilityContext =
        this.getUIContext().getHostContext() as common.UIAbilityContext;
      const fs: number | undefined = ctx.config.fontSizeScale;
      const sysScale: number = fs !== undefined && Number.isFinite(fs) && fs > 0 ? fs : 1;
      // ST 批：乘上用户「取景字体缩放」档（text_scale_camera，默认 100% = 1.0 不变）
      this.hudFontScale = sysScale * UserSettings.textScaleFactorOf(this.settings.textScaleCameraPct);
    } catch (e) {
      this.hudFontScale = 1;
    }
  }

  /**
   * 读数条一行一行的分组（J3a）：条目 = `HudItems.typesOf(mask)` 按枚举声明序（= 上游读数块顺序），
   * 分组走 J2 的 `hudRowGroups(HudZone.READOUT, …)`（上游读数块切行用的就是它）。
   *
   * 换行档选 `hudPerRowFor` 而不是 `planReadoutRow`（二选一的理由）：读数条目前**临时落位**在
   * 顶栏固定两行之下，没有「与底栏 Dock 同一条横带」的几何——planReadoutRow 的
   * sharesDockRow/bottomAvoid/底板宽那些裁决在这个落位下无从谈起；这里用的正是它竖屏退化档
   * （退回整排之上）的同一条整幅宽取档算式 hudPerRowFor(count, scale, hudRoomDp(实测宽, 端侧留白))。
   * J3b 把读数条挪进 READOUT zone 后换 planReadoutRow。
   */
  private hudReadoutRowGroups(): Array<Array<HudEntry>> {
    // J3b：条目顺序来自 hud_layout 位置表（编辑页上下移落的就是它）；hud_items 只管显隐。
    // 默认表（空串/坏串）的 READOUT 顺序 = HudItem 枚举声明序 ⇒ 与 J3a 行为逐字等价。
    const visible: Set<string> = new Set<string>();
    for (const item of HudItems.typesOf(this.hudItemsMask)) {
      visible.add(HudEntry.ofItem(item).id);
    }
    const order: Array<HudEntry> = this.readoutTable().visibleOrderOf(HudZone.READOUT, visible);
    const perRow: number = hudPerRowFor(order.length, this.hudFontScale,
      hudRoomDp(this.hudStripWidthVp, HudSpec.HUD_EDGE_PAD));
    return hudRowGroups(HudZone.READOUT, order, perRow);
  }

  /**
   * hud_layout 位置表（按原文串缓存：decode 每帧跑一遍纯浪费，原文没变就没有重解的必要；
   * onPageShow 重读设置后原文变化即自动失效）。坏串/空串 → 默认表（decode 内收口）。
   */
  private readoutTable(): HudLayoutTable {
    const raw: string = this.settings.hudLayout;
    if (this.hudTableCache === undefined || this.hudTableRaw !== raw) {
      this.hudTableCache = HudLayoutTable.decode(raw);
      this.hudTableRaw = raw;
    }
    return this.hudTableCache;
  }

  /** 读数条条目值：快门/ISO/EV/WB/帧率复用既有读数函数；码率/变焦本批新增（变焦与 zoomLabel 同源） */
  private readoutStripValue(item: HudItem): string {
    if (item === HudItem.BITRATE) {
      // 上游 `${bitrate / 1_000_000}M`（Kotlin Int 除法截断 → Math.trunc）；
      // 档位 0（自动）按 BitratePolicy 现解当前画幅/帧率的实录码率
      const bps: number = BitratePolicy.resolve(this.settings.bitrate, this.settings.sizeW,
        this.settings.sizeH, this.settings.fps, false);
      return `${Math.trunc(bps / 1_000_000)}M`;
    }
    if (item === HudItem.ZOOM) {
      return this.zoomLabel;
    }
    if (item === HudItem.FPS) {
      return this.readoutText('fps');
    }
    if (item === HudItem.SHUTTER) {
      return this.readoutText2('shutter');
    }
    if (item === HudItem.ISO) {
      return this.readoutText2('iso');
    }
    if (item === HudItem.EV) {
      return this.readoutText2('ev');
    }
    return this.readoutText2('wb');
  }

  /** 读数条条目标签（HudItems.label 单一真源；ordinal 越界的坏条目空串不渲染值） */
  private hudStripLabel(entry: HudEntry): string {
    const item: HudItem | undefined = entry.item;
    return item === undefined ? '' : HudItems.label(item);
  }

  private hudStripValue(entry: HudEntry): string {
    const item: HudItem | undefined = entry.item;
    return item === undefined ? '-' : this.readoutStripValue(item);
  }

  /**
   * TFC：点按对焦（预览区任意非控件位置）。归一化点写进参数总线 focusPoint，
   * 经既有 engine.applyParams → RequestApplier.setFocusPoint/setMeteringPoint 双驱动下发；
   * 同时给 800ms 的可视标记（无反馈用户无法确认对焦生效）。
   * 门控：取景未就绪/glFatal 时不动作（下发会失败）。
   */
  private onPreviewTap(windowX: number, windowY: number): void {
    if (!this.cameraStarted || this.glFatal !== '') {
      return;
    }
    const raw: TapPoint | undefined =
      FocusTapMath.pointFromWindowTap(windowX, windowY, this.winWvp, this.winHvp);
    if (raw === undefined) {
      return;
    }
    // WLB：窗口坐标 → 画面坐标（黑边上的点夹到最近边缘）；画面矩形不可用时与旧行为逐字相同
    const mapped: ContentPoint = PreviewFit.mapToContent(raw.x, raw.y, this.previewBox,
      this.winWvp, this.winHvp);
    const pt: TapPoint = new TapPoint(mapped.x, mapped.y);
    const isDouble: boolean = this.tapArbiter.onTapUp(Date.now());
    if (this.params.focusLocked.value) {
      // 锁定期轻触/双击不重焦（防误触字面要求）：首次轻触给一句提示
      this.showFocusMark(windowX, windowY);
      if (!this.lockedTapTipShown) {
        this.lockedTapTipShown = true;
        this.message = '对焦已锁定；要换焦点请长按画面或打开对焦胶囊解除';
      }
      return;
    }
    if (isDouble) {
      this.lockFocusAt(pt, windowX, windowY);
      return;
    }
    this.focusTapAt(pt, windowX, windowY);
  }

  /** 普通点焦：写 focusPoint → 既有 applyParams 下发 → 标记（上游 focusTapAt 同构） */
  private focusTapAt(pt: TapPoint, windowX: number, windowY: number): void {
    // StateFlow 同值去重会吞同点重触：先置 undefined 再写（上游 writeFocusPoint 同构）
    this.params.focusPoint.value = undefined;
    this.params.focusPoint.value = pt;
    this.applyParamsFromPills();
    // 诊断留痕：点按对焦的归一化坐标（真机取证/排查"点了没反应"的第一现场）
    Diag.i(WOTA_RECORD_TAG, `focus tap norm=(${pt.x.toFixed(3)},${pt.y.toFixed(3)})`);
    this.showFocusMark(windowX, windowY);
  }

  /**
   * FL：锁定入口（双击取景与胶囊条目共用）。**先写锚点再置锁定态**（引擎回执读到新锚点）。
   * 门（进闸现读，对齐上游 lockFocusAt 顺序）：手动档即锁定（不置锁）→ 录制中不锁 →
   * 已锁不重复。命中闸给一句说明、不置锁定态——拒绝假开关。
   */
  private lockFocusAt(pt: TapPoint, windowX: number, windowY: number): void {
    if (this.params.afMode.value === AfMode.MANUAL) {
      this.message = '手动对焦档本身即锁定，无需再锁';
      return;
    }
    if (this.recording) {
      this.message = '录制中不可锁定对焦';
      return;
    }
    this.params.focusLockPoint.value = pt;
    this.params.focusLocked.value = true;
    this.lockedTapTipShown = false;
    this.message = '对焦已锁定，轻触画面不会重新对焦';
    this.showFocusMark(windowX, windowY);
  }

  /**
   * FL：长按半秒 = 解锁（若已锁）；未锁定时空操作（上游同款）。
   * **平台偏离登记**：上游解锁后在长按点重新对焦（detectTapGestures 带坐标）；OHOS 的
   * LongPressGesture/GestureEvent **不含坐标字段**（SDK d.ts 核实）——本侧解锁后由用户的
   * 连续对焦档自动接管重对焦，坐标级重对焦不再单独下发。
   */
  private onPreviewLongPress(): void {
    if (this.params.focusLocked.value) {
      this.params.focusLocked.value = false;
      this.message = '对焦锁定已解除';
    }
    this.tapArbiter.reset();
  }

  /**
   * FL：胶囊「锁定对焦/解除锁定」条目（上游 toggleFocusLock 同构）：已锁 → 解锁；
   * 未锁 → 从最近点按处（focusPoint 总线值，无则画面中心）过闸锁定。
   */
  private toggleFocusLock(): void {
    if (this.params.focusLocked.value) {
      this.params.focusLocked.value = false;
      this.message = '对焦锁定已解除';
      return;
    }
    const last: TapPoint | undefined = this.params.focusPoint.value;
    const anchor: TapPoint = last !== undefined ? last : new TapPoint(0.5, 0.5);
    this.lockFocusAt(anchor, anchor.x * this.winWvp, anchor.y * this.winHvp);
  }

  /** 对焦标记：窗口 vp 坐标定位，800ms 自动隐（连点重置计时） */
  private showFocusMark(windowX: number, windowY: number): void {
    if (this.focusMarkTimer >= 0) {
      clearTimeout(this.focusMarkTimer);
      this.focusMarkTimer = -1;
    }
    this.focusMarkX = windowX;
    this.focusMarkY = windowY;
    this.focusMarkVisible = true;
    this.focusMarkTimer = setTimeout((): void => {
      this.focusMarkTimer = -1;
      this.focusMarkVisible = false;
    }, 1200);
  }

  private hideFocusMark(): void {
    if (this.focusMarkTimer >= 0) {
      clearTimeout(this.focusMarkTimer);
      this.focusMarkTimer = -1;
    }
    this.focusMarkVisible = false;
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
    // 长时任务（上游前台服务的对应物）：切后台续录；失败只记日志不阻塞录制
    if (this.ctx !== undefined) {
      RecordTask.start(this.ctx);
    }
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
    // 4 字段（track,mux,samples,fmt）为新序；容 3 字段旧序防御（fmt 缺席按 false）
    if (parts.length !== 4 && parts.length !== 3) {
      Diag.w(WOTA_RECORD_TAG, `gpu health parse failed: ${raw}`);
      return;
    }
    const trackAdded: boolean = parts[0] === '1'; // 诊断位：OHOS muxer AddTrack（不进判定，头注释对照表）
    const muxStarted: boolean = parts[1] === '1';
    const samples: number = Number(parts[2]);
    if (!Number.isFinite(samples)) {
      return;
    }
    const fmt: boolean = parts.length >= 4 && parts[3] === '1';
    let nativeError: string = '';
    try {
      nativeError = lib.getGpuRecordError();
    } catch (e) {
      nativeError = '';
    }
    const elapsed: number = Date.now() - this.healthStartMs;
    // videoTrackAdded 实参 = fmt || samples>0：onStreamChanged 是上游 format-changed 的等价真信号，
    // samples>0 兜底防个别 ROM 回调时序不触（此时编码器实际已在产出）
    const verdict: HealthVerdict = healthVerdict(samples > 0, muxStarted, fmt || samples > 0,
      nativeError, elapsed, this.healthWindow);
    if (verdict === HealthVerdict.HEALTHY) {
      if (!this.healthHealthy) {
        this.healthHealthy = true;
        Diag.i(WOTA_RECORD_TAG, `record health healthy samples=${samples} fmt=${fmt ? 1 : 0}` +
          ` muxTrack(diag)=${trackAdded ? 1 : 0} elapsed=${elapsed}ms`);
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
    // 重录段重置起算：停录宽限期（AutoStopRules.stopGraceDelayMs）与计时显示都吃它，
    // 不重置会把"重录后立即停录"误判为已录很久而跳过宽限
    this.recordStartedAtMs = Date.now();
    this.appendNotice(`录制未产出，自动重试 ${attempt}/${this.healthWindow.maxAttempts}`);
    this.startRecordMonitor();
    this.startHealthMonitor();
  }

  /**
   * 丢空段（T22 改读末段账）：优先解析 lastPath/lastBytes/lastSamples（GlRecorder.stop 摘要
   * 自 T22 起携带；多段会话里健康重录/GIVE_UP 丢的是**末段**——刚停的那枚，已完成前段保留）；
   * 旧摘要无末段账时回退 path/bytes/samples（单段口径两者同值）。
   * 仅当末段 samples===0 且 bytes===0 才删文件+sidecar；bytes<0 保留 triage；
   * 解析失败/有样本一律保留。被丢弃零样本段在首段 sidecar segments 留 count=0 条目（无害，登记 PORT_MAP）。
   */
  private discardEmptySegment(summary: string): void {
    let pathM: RegExpExecArray | null = new RegExp('lastPath=([^ ]+)').exec(summary);
    let bytesM: RegExpExecArray | null = new RegExp('lastBytes=(-?\\d+)').exec(summary);
    let samplesM: RegExpExecArray | null = new RegExp('lastSamples=(\\d+)').exec(summary);
    if (pathM === null || bytesM === null || samplesM === null) {
      pathM = new RegExp('path=([^ ]+)').exec(summary);
      bytesM = new RegExp('bytes=(-?\\d+)').exec(summary);
      samplesM = new RegExp('samples=(\\d+)').exec(summary);
    }
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
            // 仍在巡检：新段重开健康窗（避免旧段读数误判新段）
            this.healthStartMs = Date.now();
          } else if (this.pageVisible) {
            // 上一段已健康停巡检：新段重启健康窗（幂等——startHealthMonitor 先清旧定时器）。
            // healthAttempts 会话级不清零（防轮转循环里无限重录，登记头注释）
            this.startHealthMonitor();
          } else {
            // 隐藏页不后台读 native：置恢复标记，onPageShow 由 resumeHealthAfterShow 重启；
            // 健康窗仍从新段起点起算（healthStartMs 落轮转时刻，隐藏时长照计入窗口）
            this.healthHealthy = false;
            this.healthStartMs = Date.now();
            this.healthPausedByHide = true;
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
    // 长时任务收口（幂等）：所有停录终态都汇到 finishRecord，这里解一次即可
    if (this.ctx !== undefined) {
      RecordTask.stop(this.ctx);
    }
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

  // 3×3 网格改由 ui/RefLineOverlay（Canvas + 内接框算线）绘制：T31 的教训是「百分比 position
  // 定位在真机上不可靠」，而 Canvas 用画布实测尺寸算线，天然避开这一点（旧 Divider 版已删除）。

  /** Dock 胶囊文案（单一分配点；@State 值变化 → HudPill @Prop 刷新） */
  private dockLabel(key: string): string {
    if (key === 'lens') {
      return `镜头:${this.lensLabel}`;
    }
    if (key === 'size') {
      return `画幅:${this.sizeLabel}`;
    }
    if (key === 'fps') {
      const label: string = `${this.settings.fps}`;
      return `帧率:${label}`;
    }
    if (key === 'zoom') {
      return `变焦:${this.zoomLabel}`;
    }
    if (key === 'shutter') {
      return `快门:${this.pillShutterLabel}`;
    }
    if (key === 'iso') {
      return `ISO:${this.pillIsoLabel}`;
    }
    if (key === 'ev') {
      return `EV:${this.pillEvLabel}`;
    }
    if (key === 'focus') {
      return `对焦:${this.params.afMode.value === AfMode.MANUAL ? '手动' : '连续'}`;
    }
    return `WB:${this.pillWbLabel}`;
  }

  /**
   * Dock 胶囊门控：镜头/画幅/帧率录制中不可切；变焦恒可点；
   * 高级参数（快门/ISO/EV/WB）**录制中仍可开窗**——可编辑性由弹层内 editable
   * （ParamPills.RECORDING_EDITABLE_PILLS={ISO,EV,WB}）控制，快门录制中开只读 Note
   * （T31 复审 P3-3：不得整颗禁用而丢失只读信息）。
   */
  private dockEnabled(key: string): boolean {
    if (key === 'lens' || key === 'size' || key === 'fps') {
      return !this.recording && !this.stopPending;
    }
    if (key === 'zoom') {
      return true;
    }
    return this.cameraStarted;
  }

  /**
   * 实际生效的参考线掩码：设置页多选（`default_ref_lines`）+ 「HUD 网格」开关的三分法。
   * 用 RefLine.bitOf 取位值，不在调用点手写 `| 1`（位值是持久化契约，手写字面量最容易漂）。
   */
  private refLineMask(): number {
    const gridBit: number = RefLine.bitOf(RefLineType.GRID_3X3);
    return this.gridOn ? (this.refMask | gridBit) : this.refMask;
  }

  private onDockTap(key: string): void {
    if (key === 'lens') {
      this.cycleLens();
      return;
    }
    if (key === 'size') {
      this.cycleSize();
      return;
    }
    if (key === 'fps') {
      this.cycleFps();
      return;
    }
    if (key === 'zoom') {
      this.cycleZoom();
      return;
    }
    this.openPill(key, `pill_${key}`);
  }

  build() {
    Stack() {
      XComponent({ id: 'wotaCamera', type: XComponentType.SURFACE, libraryname: 'entry' })
      .accessibilityText('取景画面')
      .accessibilityDescription('点按对焦，长按锁定对焦')
        .onLoad((): void => {
          this.loadNativeModule();
        })
        .height('100%')
        .width('100%')

      // 取景参考线（11 档位掩码，设置页多选；`gridOn` 额外并入三分法网格）。
      // aspect 传 0 = 铺满整屏：本侧预览是 XComponent 全屏直出，未做 letterbox 几何（登记）。
      if (this.refLineMask() !== 0) {
        RefLineOverlay({
          lineMask: this.refLineMask(),
          aspect: this.refLineAspect(),
          rollDegrees: this.levelRoll,
          showLevelLine: this.levelOn && this.levelAvailable
        })
      }
      // T31 渐变遮罩：上/下两条压暗带，保证白字在任何画面上可读（不拦触摸）。
      // 用「满屏 Column + Blank 分隔」确定性布局，不用百分比 position（真机网格曾因此不可见）。
      Column() {
        Column()
          .width('100%')
          .height(HudSpec.TOP_SCRIM_HEIGHT + this.safeTop)
          .linearGradient({
            angle: 180,
            colors: [[HudColor.SCRIM_STRONG, 0], [HudColor.SCRIM_TRANSLUCENT, 0.55],
              [HudColor.SCRIM_CLEAR, 1]]
          })
        Blank()
        Column()
          .width('100%')
          .height(HudSpec.BOTTOM_SCRIM_HEIGHT + this.safeBottom)
          .linearGradient({
            angle: 0,
            colors: [[HudColor.SCRIM_STRONG, 0], [HudColor.SCRIM_TRANSLUCENT, 0.5],
              [HudColor.SCRIM_CLEAR, 1]]
          })
      }
      .width('100%')
      .height('100%')
      .hitTestBehavior(HitTestMode.None)

      // ---------- 姿态仪（水平仪/俯仰仪）：顶栏下方的右上角浮层，点按切换气泡/数值
      // 真机取证修正（2026-10-26）：对齐用 Stack 的 alignContent（Row 上 .align() 无效，浮层被
      // 页面 Stack 按 Center 落到屏幕中部）；包装层自身 hitTest None 不拦触摸，子层点按保留。
      if (this.levelOn && this.levelAvailable) {
        Stack({ alignContent: Alignment.TopEnd }) {
          AttitudeMeter({
            roll: this.levelRoll,
            pitch: this.levelPitch,
            isLevel: this.levelIsLevel
          })
        }
        .width('100%')
        .height('100%')
        .hitTestBehavior(HitTestMode.None)
        .padding({
          right: HudSpec.SAFE_MARGIN,
          top: this.safeTop + HudSpec.TOP_BAR_HEIGHT + HudSpace.M
        })
      }

      Column() {
        // ---------- 顶栏：固定两行读数（镜头/画幅/电量/空间 + 时长）+ 右侧 48vp 深色入口胶囊
        Row({ space: HudSpace.M }) {
          Column({ space: HudSpace.XS }) {
            // 两行读数直接内联（不用按值 @Builder：@Builder 值参数会在首帧冻结，不随 @State 刷新）
            Row({ space: HudSpace.S }) {
              ForEach(HudSpec.HUD_ROW1, (key: string): void => {
                Column({ space: 1 }) {
                  Text(HudSpec.rowLabelOf(key))
                    .fontSize(HudType.LABEL)
                    .fontColor(HudColor.TEXT_TERTIARY)
                    .maxLines(1)
                  Text(this.readoutText(key))
                    .fontSize(HudType.VALUE)
                    .fontColor(HudColor.TEXT_PRIMARY)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                }
                .layoutWeight(1)
                .alignItems(HorizontalAlign.Start)
              }, (key: string): string => key)
            }
            .width('100%')
            Row({ space: HudSpace.S }) {
              ForEach(HudSpec.HUD_ROW2, (key: string): void => {
                Column({ space: 1 }) {
                  Text(HudSpec.rowLabelOf(key))
                    .fontSize(HudType.LABEL)
                    .fontColor(HudColor.TEXT_TERTIARY)
                    .maxLines(1)
                  // J3a：快门/ISO/EV/WB 迁入下方读数条，固定行只留时长（readoutText 兜底分支）
                  Text(this.readoutText(key))
                    .fontSize(HudType.VALUE)
                    .fontColor(key === 'duration' && this.recording
                      ? HudColor.DANGER : HudColor.TEXT_PRIMARY)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                }
                .layoutWeight(1)
                .alignItems(HorizontalAlign.Start)
              }, (key: string): string => key)
            }
            .width('100%')
          }
          .layoutWeight(1)
          .alignItems(HorizontalAlign.Start)

          // VIS 批：右上入口改圆形描边单字钮（对齐安卓取景页右上双圆钮的信息架构；
          // 承载语言为鸿蒙描边圆钮 + 令牌色板）
          Column({ space: HudSpace.S }) {
            Text('设')
              .fontSize(HudType.VALUE)
              .fontColor(!this.recording && !this.stopPending ? HudColor.TEXT_PRIMARY : HudColor.TEXT_DISABLED)
              .width(40)
              .height(40)
              .borderRadius(20)
              .backgroundColor(HudColor.PILL_BG)
              .border({ width: 1, color: HudColor.DIVIDER })
              .textAlign(TextAlign.Center)
              .accessibilityText('设置')
              .onClick((): void => {
                if (!this.recording && !this.stopPending) {
                  this.getUIContext().getRouter().pushUrl({ url: 'pages/Settings' });
                }
              })
            Text('库')
              .fontSize(HudType.VALUE)
              .fontColor(!this.recording && !this.stopPending ? HudColor.TEXT_PRIMARY : HudColor.TEXT_DISABLED)
              .width(40)
              .height(40)
              .borderRadius(20)
              .backgroundColor(HudColor.PILL_BG)
              .border({ width: 1, color: HudColor.DIVIDER })
              .textAlign(TextAlign.Center)
              .accessibilityText('媒体库')
              .onClick((): void => {
                if (!this.recording && !this.stopPending) {
                  this.getUIContext().getRouter().pushUrl({ url: 'pages/Gallery' });
                }
              })
          }
          .width(76)
        }
        .width('100%')
        .height(HudSpec.TOP_BAR_HEIGHT + this.safeTop)
        .alignItems(VerticalAlign.Top)
        .padding({
          left: HudSpec.SAFE_MARGIN,
          right: HudSpec.SAFE_MARGIN,
          top: this.safeTop + HudSpace.S
        })

        // ---------- 读数条（J3a：hud_items 位掩码驱动；**临时落位**在固定两行之下，J3b 挪进 READOUT
        // zone 并换 planReadoutRow 的同行裁决）。条目/分组/换行档全在 hudReadoutRowGroups()（J1/J2 算式）；
        // 掩码 0（设置页全部取消）整条不渲染。默认档少显 EV/WB 是与上游 DEFAULT_MASK 对齐的故意行为。
        if (this.hudItemsMask !== 0) {
          Column() {
            Column({ space: HUD_ROW_GAP_DP }) {
              ForEach(this.hudReadoutRowGroups(), (row: Array<HudEntry>): void => {
                Row({ space: HUD_ROW_GAP_DP }) {
                  ForEach(row, (entry: HudEntry): void => {
                    Column({ space: 1 }) {
                      Text(this.hudStripLabel(entry))
                        .fontSize(HudType.LABEL)
                        .fontColor(HudColor.TEXT_TERTIARY)
                        .maxLines(1)
                      Text(this.hudStripValue(entry))
                        .fontSize(HudType.VALUE)
                        .fontColor(HudColor.TEXT_PRIMARY)
                        .maxLines(1)
                        .textOverflow({ overflow: TextOverflow.Ellipsis })
                    }
                    .layoutWeight(1)
                    .alignItems(HorizontalAlign.Start)
                  }, (entry: HudEntry): string => entry.id)
                }
                .width('100%')
              }, (row: Array<HudEntry>): string => {
                // 行 key = 行内条目 id 串（mask 或换行档变化 → 分组变化 → 重建行）
                let key: string = '';
                for (const entry of row) {
                  key += (key === '' ? '' : ',') + entry.id;
                }
                return key;
              })
            }
            .width('100%')
            .onAreaChange((_old: Area, area: Area): void => {
              // 实测宽接管首帧的屏幕宽兜底（J1 宽度账：可用宽必须与换行档同一份实测）
              const w: number = area.width as number;
              if (w > 0 && Math.abs(w - this.hudStripWidthVp) > 0.5) {
                this.hudStripWidthVp = w;
              }
            })
          }
          .width('100%')
          .padding({
            left: HudSpec.SAFE_MARGIN,
            right: HudSpec.SAFE_MARGIN,
            top: HudSpace.XS
          })
        }

        Blank()

        // ---------- 底栏：状态提示 + 两行主胶囊 + 更多/快门
        Column({ space: HudSpace.S }) {
          if (this.permissionGranted === false) {
            Button(this.permissionRequesting ? '请求权限中…' : '相机权限被拒绝，点按重试')
              .fontSize(HudType.VALUE)
              .fontColor(HudColor.TEXT_PRIMARY)
              .height(HudTouch.BUTTON_HEIGHT)
              .borderRadius(HudShape.PILL_RADIUS)
              .backgroundColor(HudColor.PILL_BG_ACTIVE)
              .enabled(!this.permissionRequesting)
              .onClick((): void => {
                this.ensureCameraPermission();
              })
          }
          if (this.message !== '') {
            Text(this.message)
              .fontSize(HudType.CAPTION)
              .fontColor(HudColor.WARN)
              .maxLines(2)
              .textAlign(TextAlign.Center)
              .width('100%')
          }
          if (!this.cameraStarted && this.permissionGranted && !this.loading) {
            Button(this.startingCamera ? '启动中…' : '重试相机')
              .fontSize(HudType.VALUE)
              .fontColor(HudColor.TEXT_PRIMARY)
              .height(HudTouch.BUTTON_HEIGHT)
              .borderRadius(HudShape.PILL_RADIUS)
              .backgroundColor(HudColor.PILL_BG_ACTIVE)
              .enabled(!this.startingCamera)
              .onClick((): void => {
                this.retryCamera();
              })
          }
          // 两行主胶囊（2×4，均分宽度、48vp；无横向 Scroll）。
          // HudPill 是 @Component+@Prop：父 @State 变即刷新（旧 @Builder 值参数会冻结）
          Row({ space: HudSpace.S }) {
            ForEach(HudSpec.DOCK_ROW1, (key: string): void => {
              // 外层 Row 拿 layoutWeight（4 等分）与锚点 id（量框定位弹层用）；内层胶囊铺满
              Row() {
                HudPill({
                  label: this.dockLabel(key),
                  pillEnabled: this.dockEnabled(key),
                  active: false,
                  onTap: (): void => {
                    this.onDockTap(key);
                  }
                })
              }
              .layoutWeight(1)
              .id(`pill_${key}`)
            }, (key: string): string => key)
          }
          .width('100%')
          Row({ space: HudSpace.S }) {
            ForEach(HudSpec.DOCK_ROW2, (key: string): void => {
              Row() {
                HudPill({
                  label: this.dockLabel(key),
                  pillEnabled: this.dockEnabled(key),
                  active: false,
                  onTap: (): void => {
                    this.onDockTap(key);
                  }
                })
              }
              .layoutWeight(1)
              .id(`pill_${key}`)
            }, (key: string): string => key)
          }
          .width('100%')

          // 更多 + 圆形快门（居中）+ 右侧对称占位（保证快门几何居中）
          Stack({ alignContent: Alignment.Center }) {
            Row() {
              Row() {
                HudEntryButton({
                  label: '更多',
                  entryEnabled: true,
                  onTap: (): void => {
                    this.openMore();
                  }
                })
              }
              .id('pill_more')
              Blank()
              // 右侧占位与「更多」同宽，快门保持屏幕中线
              Row().width(76)
            }
            .width('100%')

            Stack() {
              // 外环（72vp 白环）
              Column()
                .width(HudTouch.SHUTTER)
                .height(HudTouch.SHUTTER)
                .borderRadius(HudTouch.SHUTTER / 2)
                .border({ width: 3, color: HudColor.RECORD_RING })
                .backgroundColor(HudColor.SCRIM_TRANSLUCENT)
              // 内芯：72vp 圆外 44vp 红点；录制中 24vp 红方块；收尾中灰显
              Column()
                .width(this.recording ? HudTouch.SHUTTER_STOP : HudTouch.SHUTTER_INNER)
                .height(this.recording ? HudTouch.SHUTTER_STOP : HudTouch.SHUTTER_INNER)
                .borderRadius(this.recording ? HudShape.STOP_RADIUS : HudTouch.SHUTTER_INNER / 2)
                .backgroundColor(this.stopPending ? HudColor.TEXT_DISABLED
                  : (this.recording ? HudColor.RECORD_DOT_RECORDING : HudColor.RECORD_DOT))
            }
            .width(HudTouch.SHUTTER)
            .height(HudTouch.SHUTTER)
            .enabled(!this.stopPending && this.glFatal === '')
            .accessibilityText(this.recording ? '停止录制' : '开始录制')
            .onClick((): void => {
              this.toggleRecord();
            })

            // 收尾态文案走状态提示行（避免百分比定位叠加层）
          }
          .width('100%')
          .height(HudTouch.SHUTTER)
        }
        .width('100%')
        .padding({
          left: HudSpec.SAFE_MARGIN,
          right: HudSpec.SAFE_MARGIN,
          bottom: this.safeBottom + HudSpace.S
        })
      }
      .width('100%')
      .height('100%')
      .hitTestBehavior(HitTestMode.Transparent)
      // TFC：点按对焦。挂在 HUD 列（Transparent）上：空白区点击由列消费（onPreviewTap），
      // 按钮子节点各自消费（内层手势优先，不会双触发）；姿态仪浮层是下层兄弟，同理内层优先。
      // XComponent(SURFACE) 的 onClick 在模拟器/真机上均不触发（平台行为，登记）——不挂它。
      // 坐标用 windowX/windowY（窗口 vp），归一化/钳制在 core/FocusTapMath。
      .onClick((event: ClickEvent): void => {
        this.onPreviewTap(event.windowX, event.windowY);
      })
      .gesture(
        LongPressGesture({ repeat: false, duration: 500 })
          .onAction((_e: GestureEvent): void => {
            this.onPreviewLongPress();
          })
      )

      // TFC：对焦标记（瞬时可视反馈；hitTest None 不拦触摸，800ms 自动隐）
      if (this.focusMarkVisible) {
        Column()
          .width(56)
          .height(56)
          .borderRadius(28)
          .border({ width: 2, color: HudColor.ACCENT })
          .position({ x: this.focusMarkX - 28, y: this.focusMarkY - 28 })
          .hitTestBehavior(HitTestMode.None)
      }

      if (this.loading) {
        Column({ space: 8 }) {
          LoadingProgress()
            .width(36)
            .height(36)
          Text('启动相机…')
            .fontSize(12)
            .fontColor(PageColor.TEXT_DIM)
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
      }

      if (this.glFatal !== '') {
        Text(this.glFatal)
          .fontSize(12)
          .fontColor(HudColor.DANGER)
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
      // CURVE：RGB 曲线编辑弹窗（全屏捕获层；本侧预览恒 GL 渲染，gpuMode 恒 true）
      CurvePopup({
        visible: this.curvePopupVisible,
        stack: this.curveStack,
        gpuMode: true,
        onDismiss: (): void => {
          this.curvePopupVisible = false;
        },
        onCommit: (stack: CurveStack, persist: boolean): void => {
          this.onCurveCommit(stack, persist);
        }
      })

      // T31「更多」面板（闪光/效果/光弧/网格）：复用 PillPopup 遮罩 + 量框定位
      if (this.moreOpen) {
        HudMoreSheet({
          anchor: this.moreAnchor,
          flashOn: this.flashOn,
          effectLabel: this.effectLabel,
          arcVisible: this.settings.fps < 30,
          arcLabel: this.settings.arcMode === 1 ? '补弧' : '抽帧',
          arcEnabled: !this.recording && !this.stopPending,
          gridOn: this.gridOn,
          onToggleFlash: (): void => {
            this.toggleFlash();
          },
          onCycleEffect: (): void => {
            this.cycleEffect();
          },
          onEditCurve: (): void => {
            this.openCurveEditor();
          },
          onCycleArc: (): void => {
            this.cycleArc();
          },
          onToggleGrid: (): void => {
            this.toggleGrid();
          },
          onOpenBt: (): void => {
            // 先收面板再跳页（面板挂在相机页 Stack 里，带着跳会残留遮罩）
            this.moreOpen = false;
            this.getUIContext().getRouter().pushUrl({ url: 'pages/BtPanel' });
          },
          onClose: (): void => {
            this.closeMore();
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
      if (this.activePill === 'focus') {
        FocusPillPopup({
          anchor: this.pillAnchor,
          params: this.params,
          ability: this.ability,
          editable: !this.recording || pillEditableDuringRecording('focus'),
          onClose: (): void => {
            this.closePill();
          },
          onApply: (): void => {
            this.applyParamsFromPills();
          },
          onToggleLock: (): void => {
            this.toggleFocusLock();
          }
        })
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Color.Black)
  }
}
