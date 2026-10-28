/**
 * 主录制页最小版（上游 CameraScreen.kt 的最小集合）：
 * 全屏 GL 预览 + 固定 HUD（顶栏两行读数/3×3 网格/底栏控制）+ 参数胶囊
 * （镜头/画幅/帧率/变焦/闪光点按循环；效果/光弧/网格走设置页；快门/ISO/EV/WB/对焦/码率走 PillPopup 就近浮层）
 * + 光晕参数面板（native 渲染链待批）+ GPU 录制（GlRecorder + AutoStop + 500ms 宽限 + 分段轮转 + sidecar/FrameLedger）。
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
 *   2026-10-28 用户裁决（安卓侧无旧浮层面板）：闪光/码率改就近胶囊弹层，效果/光弧/网格/
 *   蓝牙由设置页「屏幕监看」组承载；右上中圆钮改开 HUD 位置编辑页；
 * - 录制键 72vp 圆形白环 + 44vp 红点（录制中 24vp 红方块、收尾中禁用）；
 * - 网格确定性三行三列（2 横 2 竖 Divider），不再依赖百分比 position；
 * - 删除预览帧率死读数，只显录制帧率「Nfps」（复审 P3-2：去「录」前缀防 360vp 截字）。
 *
 * Tone / 音量表 / 竖屏曲线入口（2026-10-28 接线批，依赖模块由并行代理提供）：
 * - 竖屏右上第三枚圆钮「曲」（id pill_curve）→ 既有 openCurveEditor（横屏左侧 float 的 RGB 曲线不动）；
 * - 横屏左侧 float「色调」芯片蓝色可用 → TonePanel（锚点 pill_tone）；提交 → ToneStore.save +
 *   applyCurve 重传；LUT 唯一合成口径 = CurveStack.bakeBytes → applyToneToLut → uploadCurveLut
 *   （applyCurve 与 applyEffect 的 effect=1 分支共用；显式搬进满长 ArrayBuffer，抹平 byteOffset）；
 * - 横屏右侧竖排（水平仪/对焦/闪光）水平仪下方加音量表：AudioLevelProbe 采样 0..1 → 百分比，
 *   启停唯一裁决 syncAudioProbe（pageVisible && cameraStarted && !recording && !stopPending）。
 */
import { common } from '@kit.AbilityKit';
import { display, mediaquery, window } from '@kit.ArkUI';
import { batteryInfo } from '@kit.BasicServicesKit';
import { Diag } from '../diag/Diag';
import { camera } from '@kit.CameraKit';
import { GlCameraEngine, GlLensInfo } from '../camera/GlCameraEngine';
import { GlRecordLib, GlRecorder } from '../camera/GlRecorder';
import { requestCameraPermission } from '../camera/PermGate';
import { CurveStack } from '../core/CurveModel';
import { ContentPoint, PreviewBox, PreviewFit } from '../core/PreviewFit';
import { AeMode, AfMode, ArcMergeDeadband, FrameEffect, HudItem, HudItems, PeakingColor, Size, WotaTiers } from '../core/Constants';
import { CameraAbility, FpsDecision, forcedShutterOutOfRange } from '../core/CameraAbility';
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
import { HudPill } from '../ui/HudPill';
import { CurvePopup } from '../ui/CurvePopup';
import { GlowPanel } from '../ui/GlowPanel';
import { GlowStore } from '../ui/GlowStore';
import { GLOW_DEFAULT, GlowNoteKey, GlowParams, GlowStatus, glowStatusNote } from '../core/Glow';
import { DENOISE_LEVEL_FULL, DENOISE_LEVEL_LITE, DENOISE_LEVEL_OFF, denoiseKernelLite } from '../core/Denoise';
import { ToneParams, TONE_DEFAULT, applyToneToLut } from '../core/Tone';
import { ToneStore } from '../ui/ToneStore';
import { TonePanel } from '../ui/TonePanel';
import { AudioLevelProbe } from '../ui/AudioLevelProbe';
import { SafeInsets, WindowChrome } from '../ui/WindowChrome';
import { evText, shutterText, wbShort } from '../ui/ParamFormat';
import { PillChoices, PillNote, PillOption, PillRect, WotaPillPopup } from '../ui/PillPopup';
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
  /** 直方图（曲线框背景，f31d18c）：弹窗可见才开采；快照 3×64 计数，无样本 undefined */
  setHistogramEnabled: (on: boolean) => void;
  getHistogramSnapshot: () => Int32Array | undefined;
  /** 光晕（追赶轮 Glow 接线）：参数十连发 + 运行期状态串（'off' | 'on:broken:scale:inW:inH:fps:rtW:rtH:clamped'） */
  setGlowParams: (enabled: number, threshold: number, soft: number, width: number, gain: number,
    add: number, freq: number, blend: number, colorA: number, colorB: number) => void;
  getGlowStatus: () => string;
  /** 画面降噪核档位：0 关 / 1 完整核 / 2 轻量核（台侧按负载分档，见 core/Denoise.ets） */
  setDenoiseLevel: (level: number) => void;
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
/** 2026-10-28 新增胶囊锚点 id（字面量单点定义；横竖分支互斥复用，避免重复 id 静态误报） */
const PILL_FLASH_ID: string = 'pill_flash';
const PILL_BITRATE_ID: string = 'pill_bitrate';
/** 帧率/降噪弹层锚点（竖屏 Dock 与横屏读数/芯片同一 id 契约） */
const PILL_FPS_ID: string = 'pill_fps';
const PILL_DENOISE_ID: string = 'pill_denoise';
/** 光晕运行期状态的轮询间隔（复用 200ms 状态定时器，按 500ms 节流读 native） */
const GLOW_STATUS_INTERVAL_MS: number = 500;
/** 竖屏右上第三枚圆钮（曲线编辑入口；回归修复——横屏左侧 float 已有 RGB 曲线，竖屏此前无入口） */
const PILL_CURVE_ID: string = 'pill_curve';

/**
 * native 状态串 → core.GlowStatus（纯数据；enabled 由用户开关给，native 只报链侧事实）。
 * `off` / 坏串 / 字段数不符返回 undefined（= 链没跑，不是「链坏了」）。
 */
function parseGlowStatus(raw: string, enabled: boolean): GlowStatus | undefined {
  if (typeof raw !== 'string' || raw.indexOf('on:') !== 0) {
    return undefined;
  }
  const parts: Array<string> = raw.substring(3).split(':');
  if (parts.length !== 8) {
    return undefined;
  }
  const nums: Array<number> = [];
  for (let i = 1; i < parts.length; i++) {
    const v: number = Number(parts[i]);
    if (!Number.isFinite(v)) {
      return undefined;
    }
    nums.push(v);
  }
  return new GlowStatus(true, enabled, parts[0] === '1', nums[0], nums[1], nums[2], nums[3],
    nums[4], nums[5], nums[6] === 1);
}

/** 五键 Glow 提示文案（core.glowStatusNote 的文案键 → 屏上中文；undefined = 不显示） */
function glowNoteTextOf(note: GlowNoteKey | undefined, st: GlowStatus | undefined): string {
  if (note === undefined) {
    return '';
  }
  if (note === 'cam_glow_direct') {
    return '当前为直连渲染，Glow 需 GPU 渲染才生效';
  }
  if (note === 'cam_glow_broken') {
    return 'Glow 渲染链已停用（连续报错）';
  }
  if (note === 'cam_glow_clamped' && st !== undefined) {
    return `已夹到 ${st.rtWidthPx}×${st.rtHeightPx}（机型纹理上限）`;
  }
  if (note === 'cam_glow_status_res_label' && st !== undefined) {
    return `渲染链 ${st.inputWidthPx}×${st.inputHeightPx}@${st.fps} · 1/${st.scale} 档`;
  }
  return 'Glow 自动分档';
}

/**
 * 横屏 HUD 局部色板/几何（T32 复刻安卓 r03 截图；HudTokens 不改）：
 * - 深灰面板 #CC2A2A2E 是截图里左侧芯片容器/入口圆钮/录制长条的底色，芯片再亮一档 #CC3A3A3E；
 * - 蓝色强调取截图里的 iOS 蓝 #FF0A84FF——HudColor.ACCENT 是录制粉红（#FF4D6A），
 *   PageColor.ACCENT_BLUE(#FF0A59F7) 偏深一档；按「亮度不够可加局部常量」口径只在本文件取亮。
 */
const LAND_PANEL_BG: string = '#CC2A2A2E';
const LAND_CHIP_BG: string = '#CC3A3A3E';
const LAND_ACCENT_BLUE: string = '#FF0A84FF';
/** 屏 816×377vp（2856×1320px @3.5）下的贴边距与控件尺寸 */
const LAND_MARGIN: number = 12;
const LAND_ROUND: number = 36;
const LAND_ROUND_GAP: number = 8;
const LAND_SIDE_PANEL_W: number = 100;
/** 底部录制簇：左 32 + 图库 40 + 10 + 快门 72 + 10 + 镜头 64 + 右 8 = 236 宽（快门恰在簇中心）；
 *  72 快门 + 上下 8 = 88 高 */
const LAND_CLUSTER_W: number = 236;
const LAND_CLUSTER_H: number = 88;
/** 右下读数两行：快门 116 + 6 + 帧率 104 = 226（第二行 124 右对齐） */
const LAND_READOUT_W: number = 226;

/**
 * 横屏文本芯片（照 r03 截图：34vp 高、全圆角、白字 12fp）。
 * `dim` 只是半透明禁用观感，不置 enabled=false——未移植功能仍要能点出一句提示。
 */
@Component
struct LandTag {
  @Prop label: string = '';
  @Prop dim: boolean = false;
  @Prop accent: boolean = false;
  @Prop tagWidth: number = 84;
  @Prop tagHeight: number = 34;
  onTap: () => void = (): void => {};

  build() {
    Button(this.label)
      .type(ButtonType.Normal)
      .fontSize(HudType.PILL)
      .fontColor(HudColor.TEXT_PRIMARY)
      .fontWeight(FontWeight.Medium)
      .width(this.tagWidth)
      .height(this.tagHeight)
      .borderRadius(this.tagHeight / 2)
      .backgroundColor(this.accent ? LAND_ACCENT_BLUE : LAND_CHIP_BG)
      .opacity(this.dim ? 0.55 : 1)
      .padding({ left: 4, right: 4 })
      .labelStyle({ maxLines: 1, overflow: TextOverflow.Ellipsis })
      .onClick((): void => {
        this.onTap();
      })
  }
}

/** 横屏读数芯片（值白字 + 灰标签两段：AUTO 快门 / 24× 帧率 / AUTO 感光度 / n M 码率） */
@Component
struct LandReadout {
  @Prop primary: string = '';
  @Prop secondary: string = '';
  @Prop readoutWidth: number = 104;
  onTap: () => void = (): void => {};

  build() {
    Button() {
      Row({ space: 5 }) {
        Text(this.primary)
          .fontSize(HudType.VALUE)
          .fontColor(HudColor.TEXT_PRIMARY)
          .fontWeight(FontWeight.Medium)
          .maxLines(1)
        Text(this.secondary)
          .fontSize(HudType.LABEL)
          .fontColor(HudColor.TEXT_SECONDARY)
          .maxLines(1)
      }
    }
    .type(ButtonType.Normal)
    .width(this.readoutWidth)
    .height(34)
    .borderRadius(17)
    .backgroundColor(LAND_CHIP_BG)
    .padding({ left: 10, right: 10 })
    .onClick((): void => {
      this.onTap();
    })
  }
}

/** 设置入口的齿轮（Canvas 画，不赌系统符号字体有没有 ⚙）：8 齿 + 外环 + 内芯 */
@Component
struct LandGearIcon {
  private ctxSettings: RenderingContextSettings = new RenderingContextSettings(true);
  private canvas: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.ctxSettings);

  private draw(): void {
    const ctx: CanvasRenderingContext2D = this.canvas;
    const w: number = ctx.width;
    const h: number = ctx.height;
    if (!(w > 0) || !(h > 0)) {
      return;
    }
    const cx: number = w / 2;
    const cy: number = h / 2;
    const r: number = Math.min(w, h) * 0.26;
    ctx.clearRect(0, 0, w, h);
    ctx.strokeStyle = HudColor.TEXT_PRIMARY;
    ctx.fillStyle = HudColor.TEXT_PRIMARY;
    // 8 齿：短粗矩形从环外 0.8 咬到 r+3.4（细长射线会读成「亮度/太阳」）
    ctx.save();
    ctx.translate(cx, cy);
    for (let i = 0; i < 8; i++) {
      ctx.save();
      ctx.rotate(i * Math.PI / 4);
      ctx.fillRect(-1.4, -(r + 3.4), 2.8, 4.2);
      ctx.restore();
    }
    ctx.restore();
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(cx, cy, r, 0, Math.PI * 2);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(cx, cy, r * 0.42, 0, Math.PI * 2);
    ctx.fill();
  }

  build() {
    Canvas(this.canvas)
      .width(20)
      .height(20)
      .onReady((): void => {
        this.draw();
      })
  }
}

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
  /** RGB 曲线栈（CURVE 批；编辑弹窗与 GL LUT 上传的数据源） */
  @State curveStack: CurveStack = CurveStack.IDENTITY;
  @State curvePopupVisible: boolean = false;
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
  /** 画面降噪开关（settings.videoDenoise 的镜像 @State：横屏芯片选中态要刷新） */
  @State denoiseOn: boolean = false;
  /** engine 报闪光不支持后的禁用观感（仍可点出一次「该镜头不支持闪光/常亮」提示） */
  @State flashUnsupported: boolean = false;
  /** 光晕参数（GlowPanel 提交 → GlowStore 落盘 + native setGlowParams 下发） */
  @State glowParams: GlowParams = GLOW_DEFAULT;
  /** 光晕运行期状态（500ms 一拍读 getGlowStatus；五键中文由 core.glowStatusNote 裁决） */
  @State glowStatusText: string = '';
  /** 渲染链真在跑（getGlowStatus 非 off 且 !broken）：GlowPanel 底部注记的唯一判据 */
  @State glowChainActive: boolean = false;
  @State glowOpen: boolean = false;
  /** 色调参数（TonePanel 提交 → ToneStore 落盘；LUT 合成在 applyCurve 唯一出口） */
  @State toneParams: ToneParams = TONE_DEFAULT;
  @State toneSel: boolean = false;
  /** 音量表读数（0..100）与「是否已采样」：探针启动后首回调前/停止后 UI 显 — */
  @State audioLevelPct: number = 0;
  @State audioLevelSampled: boolean = false;
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
  /** 色调面板锚点（pill_tone 的窗口矩形；同 glowAnchor 的就近浮层模式） */
  private toneAnchor: PillRect = { left: 0, top: 0, right: 0, bottom: 0 };
  /** 音量探针裁决态（syncAudioProbe 幂等的真源：只在 want 与它不同时 start/stop） */
  private audioProbeOn: boolean = false;
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
  /** 光晕状态读取拍点节流（状态定时器 200ms 跑，native 口按 500ms 读） */
  private lastGlowStatusMs: number = 0;
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
  /** 横竖屏分支（T32 提前批）：横屏=右侧竖排 Dock，竖屏=底部横排（T31 布局） */
  @State isLandscape: boolean = false;
  /**
   * 方向监听（mediaquery）：display.on('change') 在模拟器/部分 ROM 上不随窗口方向触发
   * （2026-10-28 模拟器取证：窗口已 2856×1320 而 display 仍报 1320×2856 → 横屏 HUD 不出现）。
   * 窗口矩形才是真值，mediaquery 负责给「何时重读」的扳机。
   */
  private orientationListener: mediaquery.MediaQueryListener | undefined = undefined;
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
  /** 当前打开的高级参数弹层（'' = 无）：快门/ISO/EV/WB/对焦/码率 */
  @State activePill: string = '';
  /** 弹层锚点（窗口 px；零矩形 = 未量到，PillPopup 内部走底贴屏回退） */
  private pillAnchor: PillRect = { left: 0, top: 0, right: 0, bottom: 0 };
  /** 光晕面板锚点（pill_glow 的窗口矩形） */
  private glowAnchor: PillRect = { left: 0, top: 0, right: 0, bottom: 0 };
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
  /** 横屏帧率芯片文案（pickFps 改的是 settings.fps，非 @State：不落成 State 芯片不会刷新） */
  @State landFpsLabel: string = '30×';
  /** T31 沉浸式安全区（vp）：顶部状态栏/挖孔、底部导航条 */
  @State safeTop: number = 0;
  @State safeBottom: number = 0;
  /** 竖屏 Dock 第二行：既有 5 颗 + 末尾闪光（6 颗 layoutWeight 均分；2026-10-28） */
  private dockRow2Keys: Array<string> = HudSpec.DOCK_ROW2.concat(['flash']);

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
    this.initOrientationListener();
    this.engine.setOnCameraLost((): void => {
      if (this.disposed) {
        return;
      }
      this.closePill(); // T22 P3：相机丢失/session error 处置统一收口弹层（锚点/能力快照已失效）
      this.cameraStarted = false;
      this.syncAudioProbe();
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
        this.syncViewportFromWindow();
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
    this.syncViewportFromWindow();
    this.applyLevelSetting();
    this.syncAudioProbe();
    const ctx: common.Context | undefined = this.ctx;
    if (ctx === undefined) {
      return;
    }
    // 光晕/色调参数与设置页同源回读（面板提交即存；本处覆盖冷启/设置页往返）
    this.glowParams = GlowStore.load(ctx);
    // 回读后立即下发一次（页面往返不改光晕值也要把 native 侧对齐到盘上真值）
    this.applyGlowParams(this.glowParams);
    this.refreshGlowStatus();
    this.toneParams = ToneStore.load(ctx);
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
      // 设置页「屏幕监看」改的是 settings.effect：返回取景页即重下发，取景效果即时生效
      this.applyEffect();
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
    // 面板不跨页留存（返回取景页重新按需打开）
    this.glowOpen = false;
    this.toneSel = false;
    // 退后台是关键点：取消未触发的尾随并立即落盘（旁路副作用，失败只记日志）
    this.flushParamsNow();
    this.cancelTapTimers();
    // 传感器只在可见时采样：后台常开很费电（上游 DisposableEffect 同义）
    if (this.levelSensor !== undefined) {
      this.levelSensor.stop();
    }
    this.syncAudioProbe();
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
    // 销毁即停探针（disposed 已在 want 里收口，这里给幂等兜底）
    this.syncAudioProbe();
    // 离开录制页：取消节流定时器、补最后一次落盘，然后销毁会话（晚到回调不再写盘）
    this.flushParamsNow();
    this.flushThrottle = undefined;
    this.persister = undefined;
    this.persistPrefs = undefined;
    if (this.levelSensor !== undefined) {
      this.levelSensor.stop();
      this.levelSensor = undefined;
    }
    if (this.orientationListener !== undefined) {
      try {
        this.orientationListener.off('change');
      } catch (e) {
        // 监听已随页面销毁：忽略
      }
      this.orientationListener = undefined;
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
      this.glowParams = GlowStore.load(ctx);
      // 冷启回读后下发一次（native 尚未装载时是空操作，装载后 initNativeParams 再补一次）
      this.applyGlowParams(this.glowParams);
      this.toneParams = ToneStore.load(ctx);
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

  private syncLabels(): void {
    this.sizeLabel = this.settings.sizeW >= 3840 ? '4K' : '1080p';
    this.landFpsLabel = `${this.settings.fps}×`;
    this.effectIndex = this.settings.effect;
    this.gridOn = this.settings.grid;
    this.denoiseOn = this.settings.videoDenoise;
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
      // 冷启默认镜头：用户显式选过（lens_user_picked 位）恢复其槽位键（设备不在了引擎再回退）；
      // 从没显式选过 = 能力协商默认（引擎侧 pickCapabilityDefault——后摄里最大视频档者，
      // 不认档位标签，上游 0525fa2）
      const startKey: string | undefined =
        this.settings.lensUserPicked && this.settings.lensKey !== '' ? this.settings.lensKey : undefined;
      return this.engine.start(ctx, this.surfaceId, startKey, want, this.settings.fps);
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
      // 起镜成功是音量表的第一启点（首启时 onPageShow 早于起镜完成，必须在真值变化处再裁决一次）
      this.syncAudioProbe();
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
        // 能力表能提前判闪光不可用：胶囊先转禁用观感（点按仍给一句「不支持」提示）
        this.flashUnsupported = !refined.flashAvailable;
        if (this.flashUnsupported) {
          this.flashOn = false;
        }
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
    // 横屏帧率芯片与 settings.fps 同源（pickFps → syncParamLabels 这一跳负责刷新）
    this.landFpsLabel = `${this.settings.fps}×`;
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
    // 起录是关键点：光晕/降噪的真值也在这条兜底链上重下发一次（native 侧可能被重链接清过）
    this.applyGlowParams(this.glowParams);
    this.applyDenoiseLevel();
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

  /** 光晕面板：量取左侧「光晕」芯片的窗口矩形做锚点；量不到走 PillPopup 底贴屏回退 */
  private openGlow(): void {
    this.glowAnchor = { left: 0, top: 0, right: 0, bottom: 0 };
    try {
      const info = this.getUIContext().getComponentUtils().getRectangleById(this.pillAnchorId('glow'));
      this.glowAnchor = {
        left: info.windowOffset.x,
        top: info.windowOffset.y,
        right: info.windowOffset.x + info.size.width,
        bottom: info.windowOffset.y + info.size.height
      };
    } catch (e) {
      // 锚点量取失败（id 失效/首帧未量到）：保持零矩形，PillPopup 回退底贴屏
    }
    this.glowOpen = true;
    // 开面板先读一拍状态（500ms 定时器之外，避免首帧空行）
    this.refreshGlowStatus();
  }

  private closeGlow(): void {
    this.glowOpen = false;
  }

  /** 色调面板：量取左侧「色调」芯片的窗口矩形做锚点；量不到走 PillPopup 底贴屏回退 */
  private openTone(): void {
    this.toneAnchor = { left: 0, top: 0, right: 0, bottom: 0 };
    try {
      const info = this.getUIContext().getComponentUtils().getRectangleById(this.pillAnchorId('tone'));
      this.toneAnchor = {
        left: info.windowOffset.x,
        top: info.windowOffset.y,
        right: info.windowOffset.x + info.size.width,
        bottom: info.windowOffset.y + info.size.height
      };
    } catch (e) {
      // 锚点量取失败（id 失效/首帧未量到）：保持零矩形，面板回退底贴屏
    }
    this.toneSel = true;
  }

  private closeTone(): void {
    this.toneSel = false;
  }

  /**
   * 音量探针启停唯一裁决（幂等）：取景可见 + 相机已起 + 未录制/未收尾才采样（录制要独占麦克风）。
   * onPageShow/onPageHide/起镜/开始录制/停录/销毁各点只调它，不各自直接 start/stop。
   * 探针内部的建路/读失败全吞（增益功能，绝不阻塞取景/录制），页面只做读数换算。
   */
  private syncAudioProbe(): void {
    const want: boolean = this.pageVisible && this.cameraStarted && !this.recording &&
      !this.stopPending && !this.disposed;
    if (want === this.audioProbeOn) {
      return;
    }
    this.audioProbeOn = want;
    if (!want) {
      AudioLevelProbe.stop();
      this.audioLevelSampled = false;
      this.audioLevelPct = 0;
      return;
    }
    this.audioLevelSampled = false; // 首回调前显 —
    AudioLevelProbe.start((level: number): void => {
      if (this.disposed || !this.audioProbeOn) {
        return; // stop 后残留在途回调丢弃
      }
      const clamped: number = Number.isFinite(level) ? Math.max(0, Math.min(1, level)) : 0;
      this.audioLevelPct = Math.round(clamped * 100);
      this.audioLevelSampled = true;
    });
  }

  /** 音量表读数：未采样（探针未启动/首回调未到）显 — */
  private audioLevelText(): string {
    return this.audioLevelSampled ? `${this.audioLevelPct}%` : '—';
  }

  /** 码率弹层档位：自动 + 上游五档（值即 settings.bitrate 的原始 bps） */
  private bitrateOptions(): Array<PillOption> {
    const out: Array<PillOption> = [new PillOption(0, '自动', true)];
    for (const bps of WotaTiers.BITRATES) {
      out.push(new PillOption(bps, `${bps / 1_000_000}M`, true));
    }
    return out;
  }

  /** 码率胶囊标签（值口径与读数条同一真源：自动档显示按画幅/帧率现解的实际码率） */
  private bitratePillLabel(): string {
    return `码率:${this.readoutStripValue(HudItem.BITRATE)}`;
  }

  // ------------------------------------------------------------------ 追赶轮：帧率/光晕/降噪接线

  /**
   * 帧率档位决议的**唯一引用点**：可用性/※ 只走 ability.decideFps(录像画幅, 档位)。
   * 会话能力未就绪 = undefined（整排禁选，不按「数值 > 30」猜高速）。
   */
  private fpsDecisionOf(tier: number): FpsDecision | undefined {
    const ability: CameraAbility | undefined = this.ability;
    if (ability === undefined) {
      return undefined;
    }
    return ability.decideFps(new Size(this.settings.sizeW, this.settings.sizeH), tier);
  }

  /** 帧率弹层档位：WotaTiers.FPS 全表；decideFps undefined → 禁用，highSpeed → ※ 后缀 */
  private fpsOptions(): Array<PillOption> {
    const out: Array<PillOption> = [];
    for (const tier of WotaTiers.FPS) {
      const decision: FpsDecision | undefined = this.fpsDecisionOf(tier);
      out.push(new PillOption(tier, decision !== undefined && decision.highSpeed ? `${tier}※` : `${tier}`,
        decision !== undefined));
    }
    return out;
  }

  /** 帧率选中：settings + 总线 + 能力重算 + 落盘 + 预览活设置（弹层唯一写入口） */
  private pickFps(fps: number): void {
    if (this.recording || this.stopPending) {
      return;
    }
    this.settings.fps = fps;
    this.params.fps.value = ParamState.of(fps);
    if (this.ability !== undefined) {
      this.params.applyAbility(this.ability);
    }
    this.syncParamLabels();
    this.persistSettings();
    this.flushParamsNow();
    this.engine.applyPreviewFps(fps);
    this.closePill();
  }

  /** 光晕参数下发（GlowParams 字段 → native 十参，顺序逐字；enabled 转 0/1） */
  private applyGlowParams(p: GlowParams): void {
    const lib: GlLibApi | undefined = this.glLib;
    if (lib === undefined) {
      return;
    }
    const s: GlowParams = p.sanitize();
    try {
      lib.setGlowParams(s.enabled ? 1 : 0, s.threshold, s.soft, s.width, s.gain, s.add, s.freq,
        s.blend, s.colorA, s.colorB);
    } catch (e) {
      Diag.w(WOTA_RECORD_TAG, `setGlowParams failed: ${e}`);
    }
  }

  /** 光晕运行期状态（状态定时器 500ms 节流调用；面板打开/提交后补一拍） */
  private refreshGlowStatus(): void {
    const lib: GlLibApi | undefined = this.glLib;
    let raw: string = '';
    if (lib !== undefined) {
      try {
        raw = lib.getGlowStatus();
      } catch (e) {
        raw = '';
      }
    }
    const st: GlowStatus | undefined = parseGlowStatus(raw, this.glowParams.enabled);
    const text: string = glowNoteTextOf(glowStatusNote(st, this.glowParams.enabled, true), st);
    const active: boolean = st !== undefined && !st.broken;
    // 只在真变化时写 @State：500ms 一拍若每拍都赋值会白刷整页 HUD
    if (this.glowStatusText !== text) {
      this.glowStatusText = text;
    }
    if (this.glowChainActive !== active) {
      this.glowChainActive = active;
    }
  }

  /** 降噪核档位（native 唯一取值点）：关 = 0；开 = 按画幅×帧率分档 轻量 2 / 完整 1 */
  private denoiseLevelNow(): number {
    if (!this.settings.videoDenoise) {
      return DENOISE_LEVEL_OFF;
    }
    return denoiseKernelLite(this.settings.sizeW, this.settings.sizeH, this.settings.fps)
      ? DENOISE_LEVEL_LITE : DENOISE_LEVEL_FULL;
  }

  /** 降噪下发（起镜成功/起录前/弹层选中同式重下发；失败只 Diag.w 不阻塞链路） */
  private applyDenoiseLevel(): void {
    const lib: GlLibApi | undefined = this.glLib;
    if (lib === undefined) {
      return;
    }
    try {
      lib.setDenoiseLevel(this.denoiseLevelNow());
    } catch (e) {
      Diag.w(WOTA_RECORD_TAG, `setDenoiseLevel failed: ${e}`);
    }
  }

  /** 降噪选中：开关 + 落盘 + 立即下发（弹层唯一写入口；音画两路口径见面板小字） */
  private pickDenoise(on: boolean): void {
    this.settings.videoDenoise = on;
    this.denoiseOn = on;
    this.persistSettings();
    this.applyDenoiseLevel();
    this.closePill();
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
    // 显式切换即永久关闭能力协商默认（上游 0525fa2）：持久化「选过」位与槽位键，
    // 冷启后尊重用户选择；位丢失的最大代价只是退回能力默认（再选一次即恢复）
    if (!this.settings.lensUserPicked || this.settings.lensKey !== this.lensKey) {
      this.settings.lensUserPicked = true;
      this.settings.lensKey = this.lensKey;
      this.persistSettings();
    }
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
    if (this.flashUnsupported) {
      // 能力表/上一次下发已判不支持：保持禁用观感，只给一句提示（不假装已切常亮）
      this.message = '该镜头不支持闪光/常亮';
      return;
    }
    const next: boolean = !this.flashOn;
    const ok: boolean = this.engine.applyFlash(next
      ? camera.FlashMode.FLASH_MODE_ALWAYS_OPEN : camera.FlashMode.FLASH_MODE_CLOSE);
    if (ok) {
      this.flashOn = next;
      this.flashUnsupported = false;
      // 闪光开关是参数动作完成点（总线 flash 未跟随 engine，登记差异见 PORT_MAP）
      this.flushParamsNow();
    } else {
      // engine 报不支持：胶囊转禁用观感，提示保留（下一次进镜头/重试起镜会重新判定）
      this.flashUnsupported = true;
      this.message = '该镜头不支持闪光/常亮';
    }
  }

  private flashLabel(): string {
    return this.flashOn ? '闪光:常亮' : '闪光:关';
  }

  /** CURVE：曲线栈变化 → 重传 LUT；曲线档位上同步开关（恒等曲线开着无视觉差） */
  private applyCurve(): void {
    const lib: GlLibApi | undefined = this.glLib;
    if (lib === undefined) {
      return;
    }
    try {
      // LUT 合成顺序：曲线栈烘焙（768 字节交错 RGB）→ 色调（入出同长）→ 上传。
      // 上传走新 ArrayBuffer（同 initNativeParams 的形态）：显式抹平 byteOffset，只给满长 768。
      const baked: Uint8Array = this.curveStack.bakeBytes();
      const finalLut: Uint8Array = applyToneToLut(baked, this.toneParams);
      const ab: ArrayBuffer = new ArrayBuffer(finalLut.length);
      new Uint8Array(ab).set(finalLut);
      lib.uploadCurveLut(ab);
      // 曲线独立层（安卓同口径）：非恒等即生效，与效果档（斑马/峰值）互不排斥
      lib.setCurveEnabled(!this.curveStack.isPassthrough);
    } catch (e) {
      this.message = `曲线上传失败: ${e}`;
    }
  }

  private openCurveEditor(): void {
    this.closePill();
    this.curvePopupVisible = true;
    // 直方图采样只在弹窗可见时进行（native 250ms 一拍；关弹窗停采清快照，省电不留旧数据）
    if (this.glLib !== undefined) {
      this.glLib.setHistogramEnabled(true);
    }
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
      // 曲线是**独立层**（安卓同口径）：合成 LUT 与开关不再跟效果档绑定——非恒等即生效，
      // 可与斑马/峰值叠加（native 的 zebra/peaking program 本身带曲线变体）。
      const baked: Uint8Array = this.curveStack.bakeBytes();
      const finalLut: Uint8Array = applyToneToLut(baked, this.toneParams);
      const ab: ArrayBuffer = new ArrayBuffer(finalLut.length);
      new Uint8Array(ab).set(finalLut);
      lib.uploadCurveLut(ab);
      if (this.effectIndex === 2) {
        lib.setEffect(FrameEffect.ZEBRA as number);
      } else if (this.effectIndex === 3) {
        lib.setEffect(FrameEffect.PEAKING as number);
      } else {
        // 直通档（0）与遗留「曲线」档（1）的效果槽都恒 NONE——曲线由上面的独立开关承担
        lib.setEffect(0);
      }
      lib.setCurveEnabled(!this.curveStack.isPassthrough);
    } catch (e) {
      this.message = `效果切换失败: ${e}`;
    }
  }

  /**
   * 参考线快切（右上第一圆钮，安卓位=外框图标）：全开（11 档 2047）/ 全关。
   * 与设置页「取景参考线」多选同源（settings.refLines），快切即互斥切换。
   */
  private toggleRefLines(): void {
    const close: boolean = this.settings.refLines !== 0;
    this.settings.refLines = close ? 0 : RefLine.fullMask();
    this.refMask = this.settings.refLines;
    this.persistSettings();
    this.message = close ? '参考线:关' : '参考线:全开';
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
      // 方向常数 v2（见 cpp/gl_render.cpp RebuildTexCoords 头注）：mirror = !front
      // （后摄镜像、前摄不镜像——模拟器实测标定的 OHOS 缓冲语义）。
      lib.setDisplayGeometry(info.sensorDeg, this.displayDeg(), !info.front);
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
      this.isLandscape = this.winWvp > this.winHvp;
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
   * 窗口矩形 → 横竖屏/vp 视口的权威校对（display 在模拟器上会继续报物理方向：
   * 2026-10-28 取证窗口已 2856×1320 而 display 仍 1320×2856，导致 isLandscape 卡在 false）。
   * 只改本页 @State（isLandscape 变化 → build 切分支），窗口尺寸本身不在这里动。
   */
  private syncViewportFromWindow(): void {
    const ctx: common.Context | undefined = this.ctx;
    if (ctx === undefined) {
      return;
    }
    window.getLastWindow(ctx).then((win: window.Window): void => {
      if (this.disposed) {
        return;
      }
      try {
        const rect: window.Rect = win.getWindowProperties().windowRect;
        const d: display.Display = display.getDefaultDisplaySync();
        const density: number = d.densityPixels > 0 ? d.densityPixels : 1;
        const w: number = rect.width / density;
        const h: number = rect.height / density;
        if (!(w > 0) || !(h > 0)) {
          return;
        }
        this.winWvp = w;
        this.winHvp = h;
        const landscape: boolean = w > h;
        this.isLandscape = landscape;
        this.applyPreviewFit();
      } catch (e) {
        Diag.w(WOTA_RECORD_TAG, `viewport read failed: ${e}`);
      }
    }).catch((e: Object): void => {
      Diag.w(WOTA_RECORD_TAG, `viewport window failed: ${e}`);
    });
  }

  /**
   * 方向监听：mediaquery 给扳机、窗口矩形给真值（display.on('change') 在模拟器上不随窗口方向触发）。
   * listener.matches 可先同步落一次 @State，避免首帧走错分支再闪一下。
   */
  private initOrientationListener(): void {
    if (this.orientationListener !== undefined) {
      return;
    }
    try {
      const listener: mediaquery.MediaQueryListener =
        this.getUIContext().getMediaQuery().matchMediaSync('(orientation: landscape)');
      listener.on('change', (_result: mediaquery.MediaQueryResult): void => {
        if (!this.disposed) {
          this.syncViewportFromWindow();
        }
      });
      this.orientationListener = listener;
      this.isLandscape = listener.matches;
      this.syncViewportFromWindow();
    } catch (e) {
      Diag.w(WOTA_RECORD_TAG, `orientation listener failed: ${e}`);
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
      // 追赶轮：native 侧参数复位点一并把光晕/降噪的真值推过去（起镜成功与 native 装载后
      // 都会走到这里；两条链路各自失败只记日志，不影响 LUT/效果）
      this.applyGlowParams(this.glowParams);
      this.applyDenoiseLevel();
      this.refreshGlowStatus();
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
    this.lastGlowStatusMs = 0;
    this.statusTimer = setInterval((): void => {
      if (this.disposed) {
        return;
      }
      if (this.recording) {
        this.elapsedMs = Date.now() - this.recordStartedAtMs;
      }
      const now: number = Date.now();
      // 光晕运行期状态：复用本定时器，500ms 节流读 getGlowStatus（面板注记/提示行消费）
      if (now - this.lastGlowStatusMs >= GLOW_STATUS_INTERVAL_MS) {
        this.lastGlowStatusMs = now;
        this.refreshGlowStatus();
      }
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
    // 录制要独占麦克风与探针冲突：起录即停采样（读数回 —）
    this.syncAudioProbe();
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
      this.syncAudioProbe();
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
    // 停录终态：磁带让出后探针可恢复采样（页面不可见/相机已失时 want 为 false，不会误启）
    this.syncAudioProbe();
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
    if (key === 'flash') {
      return this.flashLabel();
    }
    if (key === 'bitrate') {
      return this.bitratePillLabel();
    }
    return `WB:${this.pillWbLabel}`;
  }

  /**
   * Dock 胶囊门控：镜头/画幅/帧率录制中不可切；变焦恒可点；
   * 高级参数（快门/ISO/EV/WB）**录制中仍可开窗**——可编辑性由弹层内 editable
   * （ParamPills.RECORDING_EDITABLE_PILLS={ISO,EV,WB}）控制，快门录制中开只读 Note
   * （T31 复审 P3-3：不得整颗禁用而丢失只读信息）。
   * 闪光不受支持时仍保持可点（点按只给一句提示，诚实的禁用观感由外层透明度承担）。
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
      this.openPill('fps', PILL_FPS_ID);
      return;
    }
    if (key === 'zoom') {
      this.cycleZoom();
      return;
    }
    if (key === 'flash') {
      this.toggleFlash();
      return;
    }
    if (key === 'bitrate') {
      this.openPill('bitrate', PILL_BITRATE_ID);
      return;
    }
    this.openPill(key, `pill_${key}`);
  }

  // ------------------------------------------------------------------ 横屏 HUD 几何/入口

  /** 横屏视口宽（display 量不到时按 816vp 兜底；rotation 后由 display.on('change') 刷新） */
  private landW(): number {
    return this.winWvp > 0 ? this.winWvp : 816;
  }

  private landH(): number {
    return this.winHvp > 0 ? this.winHvp : 377;
  }

  /** 贴边基线：安全区 + 4/8vp（安全区由 WindowChrome 兜底 ≥24/12）。
   *  横屏系统栏是透明沉浸式（模拟器仍报 39vp 的 TYPE_SYSTEM 顶条），HUD 贴边不压状态栏本体：
   *  顶部按 24 封顶（挖孔在横屏位于左侧，左右留白属 WindowChrome T32 存量缺口）。 */
  private landTop(): number {
    return Math.min(this.safeTop, 24) + HudSpace.XS;
  }

  private landBottom(): number {
    return this.safeBottom + HudSpace.M;
  }

  /** 左侧浮 chip 面板顶：让开「分辨率胶囊 + 预览：原片」（截图里面板顶边 ≈ 屏高 28%） */
  private landSidePanelY(): number {
    return this.landTop() + 84;
  }

  /** 右上三圆钮行左端（右对齐：3×36 + 2×8 = 124） */
  private landRoundRowX(): number {
    return this.landW() - LAND_MARGIN - LAND_ROUND * 3 - LAND_ROUND_GAP * 2;
  }

  /**
   * 锚点 id 出口（openPill/openGlow 的 getRectangleById 吃同一串）。
   * 横竖两分支互斥但静态分析都会扫：动态产出避免「重复组件 id」误报（竖屏 ForEach 同法）。
   */
  private pillAnchorId(key: string): string {
    return `pill_${key}`;
  }

  /** 安全框内缩（照 r03 截图 letterbox 白框比例：左右 22%、上 14.5%、下 21%） */
  private landFrameSide(): number {
    return Math.round(this.landW() * 0.22);
  }

  private landFrameTop(): number {
    return Math.round(this.landH() * 0.145);
  }

  private landFrameBottom(): number {
    return Math.round(this.landH() * 0.21);
  }

  /** 左上分辨率读数：当前画幅 + 化简宽高比（1920x1080 → 16:9） */
  private resolutionLabel(): string {
    const w: number = this.settings.sizeW > 0 ? this.settings.sizeW : RECORD_WIDTH_1080;
    const h: number = this.settings.sizeH > 0 ? this.settings.sizeH : RECORD_HEIGHT_1080;
    let a: number = w;
    let b: number = h;
    while (b !== 0) {
      const t: number = a % b;
      a = b;
      b = t;
    }
    const g: number = a > 0 ? a : 1;
    return `${w}x${h} ${w / g}:${h / g}`;
  }

  /** 横屏图库入口（录制/收尾中不可离开本页，与竖屏右上「库」钮同一门控） */
  private openGalleryPage(): void {
    if (this.recording || this.stopPending) {
      return;
    }
    this.getUIContext().getRouter().pushUrl({ url: 'pages/Gallery' });
  }

  /** 横屏设置入口（口径同竖屏「设」钮） */
  private openSettingsPage(): void {
    if (this.recording || this.stopPending) {
      return;
    }
    this.getUIContext().getRouter().pushUrl({ url: 'pages/Settings' });
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
          .height((this.isLandscape ? 96 : HudSpec.TOP_SCRIM_HEIGHT) + this.safeTop)
          .linearGradient({
            angle: 180,
            colors: [[HudColor.SCRIM_STRONG, 0], [HudColor.SCRIM_TRANSLUCENT, 0.55],
              [HudColor.SCRIM_CLEAR, 1]]
          })
        Blank()
        Column()
          .width('100%')
          // 横屏屏高只有 377vp：底遮罩按 128 收（T31 竖屏 232 不裁）
          .height((this.isLandscape ? 128 : HudSpec.BOTTOM_SCRIM_HEIGHT) + this.safeBottom)
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
        // 横屏姿态仪收进右侧「水平仪 + 对焦」竖排容器（见下方横屏 HUD），这里让位
        .visibility(this.isLandscape ? Visibility.None : Visibility.Visible)
      }

      // 竖屏 T31 列整段包进方向闸（块内缩进保持原样：vis-align 测试按源码缩进钉右上双圆钮，不许重排）
      if (!this.isLandscape) {
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
          // 承载语言为鸿蒙描边圆钮 + 令牌色板）。
          // 回归修复：竖屏此前没有曲线入口（横屏左侧 float 有 RGB 曲线）——右起第三枚「曲」补上，
          // 横排三枚 40vp（3×40 + 2×6 = 132）不压顶栏两行读数，录制中也可开（曲线/LUT 上传与录制无冲突）。
          Row({ space: HudSpace.S }) {
            Text('曲')
              .fontSize(HudType.VALUE)
              .fontColor(HudColor.TEXT_PRIMARY)
              .width(40)
              .height(40)
              .borderRadius(20)
              .backgroundColor(HudColor.PILL_BG)
              .border({ width: 1, color: HudColor.DIVIDER })
              .textAlign(TextAlign.Center)
              .accessibilityText('RGB 曲线')
              .id(PILL_CURVE_ID)
              .onClick((): void => {
                this.openCurveEditor();
              })
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
          .width(132)
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

        // ---------- 底栏：状态提示 + 两行主胶囊 + 码率/快门
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
          if (this.isLandscape) {
            // 横屏 Dock 改为根 Stack 底部右侧浮层（见下方「横屏 Dock 浮层」）：
            // 不参与 HUD 纵向流式布局——首版流式实测被顶部读数挤到屏外（快门出屏）。
          } else {
            // 竖屏（T31 布局 + 2026-10-28 闪光入行）：第一行 4 颗、第二行 6 颗（均分宽度、48vp；无横向 Scroll）。
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
              ForEach(this.dockRow2Keys, (key: string): void => {
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
                // 闪光不被 engine 支持时保留禁用观感（仍可点出一句提示）
                .opacity(key === 'flash' && this.flashUnsupported ? 0.55 : 1)
                .id(`pill_${key}`)
              }, (key: string): string => key)
            }
            .width('100%')

            // 圆形快门（居中）；左端码率胶囊（替代已移除的旧入口）、右侧对称占位
            Stack({ alignContent: Alignment.Center }) {
              Row() {
                Row() {
                  HudPill({
                    label: this.bitratePillLabel(),
                    pillEnabled: this.cameraStarted,
                    active: false,
                    onTap: (): void => {
                      this.openPill('bitrate', PILL_BITRATE_ID);
                    }
                  })
                }
                .width(76)
                .id(PILL_BITRATE_ID)
                Blank()
                // 右侧占位与左侧码率胶囊同宽，快门保持屏幕中线
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
      }

      // ---------- 横屏 HUD（T32 复刻安卓 r03 截图，逐项）
      // 全屏 Stack 统一承载点按对焦/长按解锁（Transparent + 子钮内层消费，口径同竖屏列）；
      // 所有控件 .position 贴边浮动，中间画面裸露；屏 816×377vp（2856×1320px @3.5）。
      if (this.isLandscape) {
        Stack({ alignContent: Alignment.TopStart }) {
          // 7. 白色细线安全框（1.5vp，内缩一圈；不参与触摸，压在所有控件之下；
          //    底边被录制簇/右下读数盖住 = 截图里白框从底部控件背后穿过的观感）
          Stack() {
            Column()
              .width('100%')
              .height('100%')
              .border({ width: 1.5, color: HudColor.TEXT_PRIMARY })
              .opacity(0.85)
          }
          .width('100%')
          .height('100%')
          .padding({
            left: this.landFrameSide(),
            right: this.landFrameSide(),
            top: this.landFrameTop(),
            bottom: this.landFrameBottom()
          })
          .hitTestBehavior(HitTestMode.None)

          // 1. 左上：分辨率胶囊（1920x1080 16:9，点按循环画幅）+ 下方红字「预览：原片」
          Column({ space: HudSpace.XS }) {
            Button() {
              Text(this.resolutionLabel())
                .fontSize(HudType.VALUE)
                .fontColor(HudColor.TEXT_PRIMARY)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
            }
            .type(ButtonType.Normal)
            .width(150)
            .height(30)
            .borderRadius(15)
            .backgroundColor(LAND_PANEL_BG)
            .padding({ left: 12, right: 12 })
            .accessibilityText('画幅，点按切换')
            .id(this.pillAnchorId('size'))
            .onClick((): void => {
              this.cycleSize();
            })

            Text('预览：原片')
              .fontSize(HudType.LABEL)
              .fontColor(HudColor.RECORD_DOT)
              .maxLines(1)
              .margin({ left: HudSpace.XS })
          }
          .alignItems(HorizontalAlign.Start)
          .position({ x: LAND_MARGIN, y: this.landTop() })

          // 2. 右上：三颗圆形深色按钮（参考线快切 / HUD 位置编辑 / 设置）
          //    功能错位修复（2026-10-28）：第一钮原误接「网格」，与左侧圆形网格钮重复；
          //    按安卓位语义（外框图标）改「参考线」全开/关快切，网格只归左侧圆钮。
          Row({ space: LAND_ROUND_GAP }) {
            Button() {
              Column({ space: 4 }) {
                Column()
                  .width(20)
                  .height(13)
                  .border({ width: 1.5, color: HudColor.TEXT_PRIMARY })
                  .borderRadius(1)
              }
            }
            .type(ButtonType.Normal)
            .width(LAND_ROUND)
            .height(LAND_ROUND)
            .borderRadius(LAND_ROUND / 2)
            // 颜色吃 @State refMask（settings 对象字段变更不触发重渲染，2026-10-28 实测：
            // 切换已落盘 2047 但按钮不蓝）
            .backgroundColor(this.refMask !== 0 ? LAND_ACCENT_BLUE : LAND_PANEL_BG)
            .padding(0)
            .accessibilityText('参考线')
            .onClick((): void => {
              this.toggleRefLines();
            })

            // 中钮 = HUD 位置编辑页（安卓侧无旧面板，2026-10-28 用户裁决）
            Row() {
              Button() {
                Row({ space: 3 }) {
                  Column().width(4).height(4).borderRadius(2).backgroundColor(HudColor.TEXT_PRIMARY)
                  Column().width(4).height(4).borderRadius(2).backgroundColor(HudColor.TEXT_PRIMARY)
                  Column().width(4).height(4).borderRadius(2).backgroundColor(HudColor.TEXT_PRIMARY)
                }
              }
              .type(ButtonType.Normal)
              .width(LAND_ROUND)
              .height(LAND_ROUND)
              .borderRadius(LAND_ROUND / 2)
              .backgroundColor(LAND_PANEL_BG)
              .padding(0)
              .accessibilityText('编辑 HUD 位置')
              .onClick((): void => {
                this.getUIContext().getRouter().pushUrl({ url: 'pages/HudEditor' });
              })
            }

            Button() {
              LandGearIcon()
            }
            .type(ButtonType.Normal)
            .width(LAND_ROUND)
            .height(LAND_ROUND)
            .borderRadius(LAND_ROUND / 2)
            .backgroundColor(LAND_PANEL_BG)
            .padding(0)
            .accessibilityText('设置')
            .onClick((): void => {
              this.openSettingsPage();
            })
          }
          .position({ x: this.landRoundRowX(), y: this.landTop() })

          // 4. 右侧竖排：水平仪气泡（复用 AttitudeMeter）+ 音量表 + 下方「对焦」蓝色钮（现有对焦弹层）
          Column({ space: HudSpace.S }) {
            if (this.levelOn && this.levelAvailable) {
              AttitudeMeter({
                roll: this.levelRoll,
                pitch: this.levelPitch,
                isLevel: this.levelIsLevel
              })
            }
            // 音量表（水平仪下方）：探针只在取景且未录制时采样；未采样显 —
            // 图标用 4 根细柱拼出 ▮▮▯ 电平符号（不走字形：▮/▯ 的字宽随字体漂，52vp 内宽会溢出）
            Row({ space: 4 }) {
              Row({ space: 2 }) {
                Column().width(3).height(12).borderRadius(1.5).backgroundColor(HudColor.TEXT_TERTIARY)
                Column().width(3).height(9).borderRadius(1.5).backgroundColor(HudColor.TEXT_TERTIARY)
                Column().width(3).height(6).borderRadius(1.5).backgroundColor(HudColor.TEXT_TERTIARY)
                Column().width(3).height(4).borderRadius(1.5).backgroundColor(HudColor.TEXT_TERTIARY)
              }
              .height(12)
              .alignItems(VerticalAlign.Bottom)
              Text(this.audioLevelText())
                .fontSize(HudType.LABEL)
                .fontColor(HudColor.TEXT_PRIMARY)
                .maxLines(1)
                .layoutWeight(1)
                .textAlign(TextAlign.Center)
                .accessibilityText('音量表')
            }
            .width('100%')
            .height(20)
            .alignItems(VerticalAlign.Center)
            Row() {
              Button('对焦')
                .type(ButtonType.Normal)
                .fontSize(HudType.VALUE)
                .fontColor(HudColor.TEXT_PRIMARY)
                .fontWeight(FontWeight.Medium)
                .width('100%')
                .height(36)
                .borderRadius(18)
                .backgroundColor(LAND_ACCENT_BLUE)
                // 容器内宽只有 52vp：默认按钮左右内边距会把两字挤成省略号
                .padding({ left: 2, right: 2 })
                .labelStyle({ maxLines: 1, overflow: TextOverflow.Ellipsis })
                .onClick((): void => {
                  this.openPill('focus', 'pill_focus');
                })
            }
            .width('100%')
            .id(this.pillAnchorId('focus'))

            // 闪光胶囊（对焦下方，2026-10-28）：关/常亮循环；不支持时半透明禁用观感并给提示
            Row() {
              Button(this.flashLabel())
                .type(ButtonType.Normal)
                .fontSize(HudType.LABEL)
                .fontColor(HudColor.TEXT_PRIMARY)
                .fontWeight(FontWeight.Medium)
                .width('100%')
                .height(36)
                .borderRadius(18)
                .backgroundColor(LAND_CHIP_BG)
                // 52vp 内宽必须零内边距：5 字标签（闪光:常亮）才能完整显示
                .padding({ left: 0, right: 0 })
                .labelStyle({ maxLines: 1, overflow: TextOverflow.Ellipsis })
                .opacity(this.flashUnsupported || !this.cameraStarted ? 0.55 : 1)
                .accessibilityText(`闪光，当前${this.flashOn ? '常亮' : '关闭'}`)
                .onClick((): void => {
                  this.toggleFlash();
                })
            }
            .width('100%')
            .id(PILL_FLASH_ID)
          }
          .width(68)
          .padding(HudSpace.S)
          .borderRadius(HudShape.PILL_RADIUS)
          .backgroundColor(LAND_PANEL_BG)
          .position({ x: this.landW() - LAND_MARGIN - 68, y: this.landTop() + LAND_ROUND + LAND_ROUND_GAP })

          // 3. 左侧竖排 float 芯片组：蓝色圆形网格钮 + RGB 曲线 + 光晕 + 色调
          Column({ space: 10 }) {
            Button() {
              Column({ space: 3 }) {
                Row({ space: 3 }) {
                  Column().width(6).height(6).borderRadius(1.5).backgroundColor(HudColor.TEXT_PRIMARY)
                  Column().width(6).height(6).borderRadius(1.5).backgroundColor(HudColor.TEXT_PRIMARY)
                }
                Row({ space: 3 }) {
                  Column().width(6).height(6).borderRadius(1.5).backgroundColor(HudColor.TEXT_PRIMARY)
                  Column().width(6).height(6).borderRadius(1.5).backgroundColor(HudColor.TEXT_PRIMARY)
                }
              }
            }
            .width(40)
            .height(40)
            .borderRadius(20)
            .backgroundColor(this.gridOn ? LAND_ACCENT_BLUE : LAND_CHIP_BG)
            .padding(0)
            .accessibilityText('网格')
            .onClick((): void => {
              this.toggleGrid();
            })

            LandTag({
              label: 'RGB 曲线',
              tagWidth: 84,
              onTap: (): void => {
                this.openCurveEditor();
              }
            })
            // 光晕面板（参数可调、持久化；native 渲染链待批）；色调面板（本批接实：提交即落盘 + LUT 合成）
            Row() {
              LandTag({
                label: '光晕',
                accent: true,
                tagWidth: 62,
                onTap: (): void => {
                  this.openGlow();
                }
              })
            }
            .id(this.pillAnchorId('glow'))
            Row() {
              LandTag({
                label: '色调',
                accent: true,
                tagWidth: 62,
                onTap: (): void => {
                  this.openTone();
                }
              })
            }
            .id(this.pillAnchorId('tone'))
          }
          .width(LAND_SIDE_PANEL_W)
          .padding({ top: 12, bottom: 12, left: 8, right: 8 })
          .alignItems(HorizontalAlign.Center)
          .borderRadius(HudShape.PILL_RADIUS)
          .backgroundColor(LAND_PANEL_BG)
          .position({ x: LAND_MARGIN, y: this.landSidePanelY() })

          // 6a. 左下：降噪芯片（点按开「关闭 / 画面降噪」弹层，开启态蓝色）+ 深色「码率」（档位弹层）
          Row({ space: 6 }) {
            Row() {
              LandTag({
                label: '降噪',
                accent: this.denoiseOn,
                tagWidth: 64,
                onTap: (): void => {
                  this.openPill('denoise', PILL_DENOISE_ID);
                }
              })
            }
            .id(PILL_DENOISE_ID)
            Row() {
              LandReadout({
                primary: this.readoutStripValue(HudItem.BITRATE),
                secondary: '码率',
                readoutWidth: 104,
                onTap: (): void => {
                  this.openPill('bitrate', PILL_BITRATE_ID);
                }
              })
            }
            .id(PILL_BITRATE_ID)
          }
          .position({ x: LAND_MARGIN, y: this.landH() - this.landBottom() - 34 })

          // 6b. 右下：三枚读数胶囊（AUTO 快门 / 24× 帧率 / AUTO 感光度）——两行右对齐
          Column({ space: 6 }) {
            Row({ space: 6 }) {
              Row() {
                LandReadout({
                  primary: this.rowShutter,
                  secondary: '快门',
                  readoutWidth: 116,
                  onTap: (): void => {
                    this.openPill('shutter', 'pill_shutter');
                  }
                })
              }
              .id(this.pillAnchorId('shutter'))
              Row() {
                LandReadout({
                  primary: this.landFpsLabel,
                  secondary: '帧率',
                  readoutWidth: 104,
                  onTap: (): void => {
                    this.openPill('fps', this.pillAnchorId('fps'));
                  }
                })
              }
              .id(this.pillAnchorId('fps'))
            }
            Row() {
              LandReadout({
                primary: this.rowIso,
                secondary: '感光度',
                readoutWidth: 124,
                onTap: (): void => {
                  this.openPill('iso', 'pill_iso');
                }
              })
            }
            .id(this.pillAnchorId('iso'))
          }
          .alignItems(HorizontalAlign.End)
          .position({
            x: this.landW() - LAND_MARGIN - LAND_READOUT_W,
            y: this.landH() - this.landBottom() - 74
          })

          // 5. 底部中央录制簇：图库入口 + 72vp 快门（现有 toggleRecord）+ 当前镜头胶囊（cycleLens）
          Row({ space: 10 }) {
            Button() {
              Stack({ alignContent: Alignment.TopStart }) {
                Column()
                  .width(20)
                  .height(15)
                  .borderRadius(3)
                  .border({ width: 1.5, color: HudColor.TEXT_PRIMARY })
                Column()
                  .width(5)
                  .height(5)
                  .borderRadius(2.5)
                  .backgroundColor(HudColor.TEXT_PRIMARY)
                  .margin({ left: 3.5, top: 3 })
                Column()
                  .width(12)
                  .height(1.5)
                  .backgroundColor(HudColor.TEXT_PRIMARY)
                  .margin({ left: 4, top: 10.5 })
              }
              .width(20)
              .height(15)
            }
            .type(ButtonType.Normal)
            .width(40)
            .height(40)
            .borderRadius(20)
            .backgroundColor(LAND_CHIP_BG)
            .padding(0)
            .accessibilityText('媒体库')
            .onClick((): void => {
              this.openGalleryPage();
            })

            Stack() {
              Column()
                .width(HudTouch.SHUTTER)
                .height(HudTouch.SHUTTER)
                .borderRadius(HudTouch.SHUTTER / 2)
                .border({ width: 3, color: HudColor.RECORD_RING })
                .backgroundColor(HudColor.SCRIM_TRANSLUCENT)
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

            Row() {
              LandTag({
                label: this.lensLabel,
                tagWidth: 64,
                tagHeight: 40,
                onTap: (): void => {
                  this.cycleLens();
                }
              })
            }
            .id(this.pillAnchorId('lens'))
          }
          .height(LAND_CLUSTER_H)
          // 左宽右窄的空隙让 72 快门落在长条中心（截图：快门是整条的中心，图库偏左、镜头偏右）
          .padding({ left: 32, right: 8 })
          .borderRadius(LAND_CLUSTER_H / 2)
          .backgroundColor(LAND_PANEL_BG)
          .position({
            x: (this.landW() - LAND_CLUSTER_W) / 2,
            y: this.landH() - this.landBottom() - LAND_CLUSTER_H
          })

          // 状态提示/权限/重试：屏顶中央（不压任何控件；横屏不渲染 T31 底部状态列）
          Column({ space: HudSpace.S }) {
            if (this.message !== '') {
              Text(this.message)
                .fontSize(HudType.CAPTION)
                .fontColor(HudColor.WARN)
                .maxLines(2)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .textAlign(TextAlign.Center)
                .width('100%')
            }
            if (this.permissionGranted === false) {
              Button(this.permissionRequesting ? '请求权限中…' : '相机权限被拒绝，点按重试')
                .fontSize(HudType.CAPTION)
                .fontColor(HudColor.TEXT_PRIMARY)
                .height(32)
                .borderRadius(16)
                .backgroundColor(HudColor.PILL_BG_ACTIVE)
                .padding({ left: 12, right: 12 })
                .enabled(!this.permissionRequesting)
                .onClick((): void => {
                  this.ensureCameraPermission();
                })
            }
            if (!this.cameraStarted && this.permissionGranted && !this.loading) {
              Button(this.startingCamera ? '启动中…' : '重试相机')
                .fontSize(HudType.CAPTION)
                .fontColor(HudColor.TEXT_PRIMARY)
                .height(32)
                .borderRadius(16)
                .backgroundColor(HudColor.PILL_BG_ACTIVE)
                .padding({ left: 12, right: 12 })
                .enabled(!this.startingCamera)
                .onClick((): void => {
                  this.retryCamera();
                })
            }
          }
          .width(360)
          .alignItems(HorizontalAlign.Center)
          .position({ x: (this.landW() - 360) / 2, y: this.landTop() + 44 })

          // 锚点 id 保留（截图版横屏无 EV/WB/变焦入口：逻辑/弹层原样在，id 挂透明占位供量框回退）
          Row().width(1).height(1).opacity(0).hitTestBehavior(HitTestMode.None).id(this.pillAnchorId('ev'))
          Row().width(1).height(1).opacity(0).hitTestBehavior(HitTestMode.None).id(this.pillAnchorId('wb'))
          Row().width(1).height(1).opacity(0).hitTestBehavior(HitTestMode.None).id(this.pillAnchorId('zoom'))
        }
        .width('100%')
        .height('100%')
        .hitTestBehavior(HitTestMode.Transparent)
        // TFC：点按对焦/长按解锁（与竖屏列同一收口；子钮各自消费不双触发）
        .onClick((event: ClickEvent): void => {
          this.onPreviewTap(event.windowX, event.windowY);
        })
        .gesture(
          LongPressGesture({ repeat: false, duration: 500 })
            .onAction((_e: GestureEvent): void => {
              this.onPreviewLongPress();
            })
        )
      }

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
        histogramProvider: (): Int32Array | undefined => {
          return this.glLib !== undefined ? this.glLib.getHistogramSnapshot() : undefined;
        },
        onDismiss: (): void => {
          this.curvePopupVisible = false;
          if (this.glLib !== undefined) {
            this.glLib.setHistogramEnabled(false);
          }
        },
        onCommit: (stack: CurveStack, persist: boolean): void => {
          this.onCurveCommit(stack, persist);
        }
      })

      // 码率真实弹层（2026-10-28：安卓侧无旧面板 → 码率胶囊就地开档位）
      if (this.activePill === 'bitrate') {
        WotaPillPopup({
          anchor: this.pillAnchor,
          panelTitle: '码率',
          onDismiss: (): void => {
            this.closePill();
          }
        }) {
          if (this.recording || this.stopPending) {
            PillNote({ noteText: '录制中不可调整，停止后可改' })
          }
          PillChoices({
            options: this.bitrateOptions(),
            selected: this.settings.bitrate,
            perRow: 3,
            active: !this.recording && !this.stopPending,
            onPick: (opt: PillOption): void => {
              this.settings.bitrate = opt.value;
              this.persistSettings();
              this.closePill();
            }
          })
        }
      }
      // 帧率真实弹层（追赶轮）：原 cycleFps 循环改档 → 全表档位；可用性只走 ability.decideFps
      // （undefined 禁选、highSpeed 加 ※），选中即改设置 + 总线 + 落盘 + 预览活设置。
      if (this.activePill === 'fps') {
        WotaPillPopup({
          anchor: this.pillAnchor,
          panelTitle: '帧率',
          onDismiss: (): void => {
            this.closePill();
          }
        }) {
          if (this.recording || this.stopPending) {
            PillNote({ noteText: '录制中不可调整，停止后可改' })
          }
          PillChoices({
            options: this.fpsOptions(),
            selected: this.settings.fps,
            perRow: 4,
            active: !this.recording && !this.stopPending,
            onPick: (opt: PillOption): void => {
              this.pickFps(opt.value);
            }
          })
          PillNote({ noteText: '※ 高帧率走高速会话，实际速率由 HAL 在声明区间内决定' })
        }
      }
      // 降噪真实弹层（追赶轮）：关闭 / 画面降噪；音频降噪在本平台无对应能力（core/Denoise 头注）
      if (this.activePill === 'denoise') {
        WotaPillPopup({
          anchor: this.pillAnchor,
          panelTitle: '降噪',
          onDismiss: (): void => {
            this.closePill();
          }
        }) {
          if (this.recording || this.stopPending) {
            PillNote({ noteText: '录制中不可调整，停止后可改' })
          }
          PillChoices({
            options: [
              new PillOption(0, '关闭', true),
              new PillOption(1, '画面降噪', true)
            ],
            selected: this.denoiseOn ? 1 : 0,
            perRow: 2,
            active: !this.recording && !this.stopPending,
            onPick: (opt: PillOption): void => {
              this.pickDenoise(opt.value === 1);
            }
          })
          PillNote({ noteText: '仅画面降噪；音频降噪在本平台无对应能力（T27-3 登记）' })
        }
      }
      // 光晕参数面板：提交即落盘 + setGlowParams 下发；状态行吃 getGlowStatus 真值（500ms 刷新）
      if (this.glowOpen) {
        GlowPanel({
          anchor: this.glowAnchor,
          glow: this.glowParams,
          statusText: this.glowStatusText,
          chainActive: this.glowChainActive,
          onClose: (): void => {
            this.closeGlow();
          },
          onCommit: (p: GlowParams): void => {
            this.glowParams = p;
            const ctx: common.Context | undefined = this.ctx;
            if (ctx !== undefined) {
              GlowStore.save(ctx, p);
            }
            this.applyGlowParams(p);
            this.refreshGlowStatus();
          }
        })
      }
      // 色调面板（TonePanel）：提交即落盘并重合成 LUT（曲线烘焙 → 色调 → uploadCurveLut）
      if (this.toneSel) {
        TonePanel({
          anchor: this.toneAnchor,
          tone: this.toneParams,
          onClose: (): void => {
            this.closeTone();
          },
          onCommit: (p: ToneParams): void => {
            this.toneParams = p;
            const ctx: common.Context | undefined = this.ctx;
            if (ctx !== undefined) {
              ToneStore.save(ctx, p);
            }
            this.applyCurve();
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
          },
          onManualSelected: (): void => {
            // 切手动对焦的一次性提示（上游 4457e4a 方案G 最小形态）：峰值还没开才提，
            // 已开则不打扰（effect 3=峰值，labels=['直通','曲线','斑马','峰值']）
            if (this.settings.effect !== 3) {
              this.message = '手动对焦建议开「峰值」辅助（设置页）';
            }
          }
        })
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Color.Black)
  }
}
