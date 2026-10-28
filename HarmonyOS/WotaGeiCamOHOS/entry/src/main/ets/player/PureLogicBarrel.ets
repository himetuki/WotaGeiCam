/**
 * 纯逻辑模块编译守卫（T25 P3-1，T26/T28 扩展）。
 *
 * hvigor 只编译从页面可达的文件——纯逻辑批新增的模块在没有页面消费之前不会进 abc，
 * 类型错误要到接线时才暴露。本汇总文件在 EntryAbility.onCreate 调用 signature()，
 * 把这些模块拉进 ArkTS program，让编译门覆盖它们（并顺带在启动日志留一行可核对签名）。
 *
 * T26 追加六个模块：CaptureStateMachine / CaptureSilence / AudioProbe / CaptureSources /
 * TrackSync / PcmDecode（内录纯逻辑降级版，平台面留 T27）。
 * T28 追加四个模块：ArcRepairPlan / ArcRepairFlow / ArcRepairMerge / ArcRepairVerdict
 * （离线光弧修复纯逻辑，执行器留 T29）。
 * T29 追加 record/ArcRepairSessionModel（native 串解析/速率/NO_NEED/去重/归因）。
 * 上游对齐批（0dee86d/e950328 纯逻辑层）追加五个模块：ui/SliderSnap（滑杆吸附）、
 * core/AudioAlign（DSP 自 AudioSync 迁出 + estimateOffsetMs）、record/AudioPreflightPolicy、
 * record/PreflightPcm、player/PostStopSyncPlan（平台接线面留 T27）。
 * 注意：本文件只做**引用**，不改变任何模块语义；不 import 平台 Kit。
 */
import { AudioSync } from './AudioSync';
import { AudioTrackKind, Side, selectionEffectOf } from './CompareAudio';
import { CompareHistory } from './CompareHistory';
import { comparePractice } from './ComparePractice';
import { CAPTURE_SILENCE_MIN_MS } from '../record/CaptureSilence';
import { CaptureState, captureStateLabel } from '../record/CaptureStateMachine';
import { captureAudioSources } from '../record/CaptureSources';
import { chunkBytes } from '../record/AudioProbe';
import { PcmEncoding } from './PcmDecode';
import { TrackSync } from './TrackSync';
import { ArcRateProbe } from '../record/ArcKeepRule';
import { ArcRepairPlan } from '../record/ArcRepairPlan';
import { ArcRepairFlow } from '../record/ArcRepairFlow';
import { ArcRepairMerge } from '../record/ArcRepairMerge';
import { arcRepairOk } from '../record/ArcRepairVerdict';
import {
  ArcProbeInfo, ArcRepairSessionModel, ArcRepairStatus
} from '../record/ArcRepairSessionModel';
import { AudioAlign } from '../core/AudioAlign';
import { AudioPreflightPolicy } from '../record/AudioPreflightPolicy';
import { PreflightMonoPcm } from '../record/PreflightPcm';
import { PostStopPlan, PostStopSyncPlan } from './PostStopSyncPlan';
import { SLIDER_KEYS, decodeContinuousSliders, effectiveLadder } from '../ui/SliderSnap';
import { CompareVersions, ReleaseJson, ReleaseNotes, UpdateConfig } from '../update/UpdateChecker';
import { UpdateDownloadPure } from '../update/UpdatePure';
import { Sha256 } from '../update/Sha256';
import { UserSettings } from '../core/UserSettings';
import { FocusTapMath } from '../core/FocusTap';
import { DirectDisplay } from '../core/DirectDisplay';
import { EncoderPts } from '../core/EncoderPts';
import { FrameRateFallback } from '../core/FrameRateFallback';
import { degradeSuppressed } from '../camera/GlRenderSuppression';
import { RangeI } from '../core/CameraAbility';
import { AfMode, Size, WotaTiers } from '../core/Constants';
import {
  PARAMS_KEYS, ParamsMigrate, PrefEnum, enumFrom, enumName, formatSize, parseSize
} from '../ui/ParamsPersist';
import { FlushScheduler, ParamsFlushThrottle } from '../ui/ParamsFlushThrottle';
import { hudPerRowFor, hudRoomDp, hudStripHeightDp } from '../core/HudMetrics';
import {
  GridBox, GridCell, HudAreaDp, HudEntry, HudLayoutTable, HudPointDp, HudPointPx, HudRectDp, HudZone,
  IntRange, ZonePlacement, clampStoredCell, clampZonePos, dropZoneOf, editorVisibleEntries, gridColsCapOf,
  zoneBandYRange
} from '../core/HudLayout';
import {
  HudPageCell, pageCellAtPointer, pageCellOffsetPx, pageCellPx, pageCellsBlocked, pageGridBoxOf,
  pageGridColCap, pageGridRowCap
} from '../core/HudPageGrid';
import { GLOW_DEFAULT, GlowParams, decodeGlow, encodeGlow, fillGlowBlurOffset, glowRgb,
  glowScaleFor, glowStatusNote } from '../core/Glow';
import { DisplayScale, displayRtNeedsRebuild, displayScaleReadoutLabel, nearestDisplayScale,
  resolveDisplayRtOrNull } from '../core/DisplayScaleRt';
import { AudioDenoise, VideoDenoise, audioDenoiseNeedsCodec, denoiseKernelLite,
  effectiveVideoDenoise, videoDenoiseActive } from '../core/Denoise';
import { HistogramMath } from '../core/HistogramMath';

/** 编译探针用空定时器（不排真定时器；只让节流器类型面进 abc） */
class NoopFlushScheduler implements FlushScheduler {
  arm(_delayMs: number, _fn: () => void): number {
    return -1;
  }

  disarm(_handle: number): void {
  }
}

/** 十四个纯逻辑模块的编译/启动签名（只读常量与纯函数，无副作用） */
export class PureLogicBarrel {
  /**
   * 返回一行签名：采样率/历史上限/左音量/右音量/练习桥 armed + 内录侧契约摘要 + 离线修复摘要。
   * 期望（默认态）：`sr=8000 max=12 vol=1/0 armed=false cap=未授权 src=2 sync=+120 chunk=64
   * silence=2000 pcm=S16LE arc=36/0/1`
   * （arc = dstFrameCount(45,30,24) / isKept(4,30,24) / arcRepairOk(true,true,1)）。
   */
  static signature(): string {
    const effect = selectionEffectOf(Side.LEFT, AudioTrackKind.ENV, true);
    const capLabel: string = captureStateLabel(CaptureState.idle());
    const firstCaptureSource: number = captureAudioSources()[0];
    const sync: string = TrackSync.signedMs(TrackSync.combineOffsetMs(120, 1000000, 1000000));
    const chunk: number = chunkBytes(0, 2);
    const dstFrames: number = ArcRepairPlan.dstFrameCount(45, 30, 1, 24);
    const keptBit: number = ArcRepairPlan.isKept(4, 30, 1, 24) ? 1 : 0;
    const okBit: number = arcRepairOk(true, true, 1) ? 1 : 0;
    // Flow/Merge 无默认态可打印：走一次零副作用探针，让编译门覆盖其类型面
    PureLogicBarrel.arcRepairCompileProbe();
    PureLogicBarrel.syncSnapCompileProbe();
    PureLogicBarrel.updateCompileProbe();
    PureLogicBarrel.userSettingsCompileProbe();
    PureLogicBarrel.focusTapCompileProbe();
    PureLogicBarrel.cameraPerfCompileProbe();
    PureLogicBarrel.paramsPersistCompileProbe();
    PureLogicBarrel.paramsFlushThrottleCompileProbe();
    PureLogicBarrel.hudLayoutCompileProbe();
    PureLogicBarrel.cameraCatchupCompileProbe();
    return `sr=${AudioSync.TARGET_SR} max=${CompareHistory.MAX_ENTRIES} ` +
      `vol=${effect.leftVolume}/${effect.rightVolume} armed=${comparePractice.armed} ` +
      `cap=${capLabel} src=${firstCaptureSource} sync=${sync} chunk=${chunk} ` +
      `silence=${CAPTURE_SILENCE_MIN_MS} pcm=${PcmEncoding.S16LE} ` +
      `arc=${dstFrames}/${keptBit}/${okBit}`;
  }

  /** 离线修复编译探针：空流 EOS 与 2×2 零块 merge（无副作用，产物丢弃） */
  private static arcRepairCompileProbe(): void {
    const flow: ArcRepairFlow = ArcRepairFlow.ofDstFps(24);
    flow.onSourceEos();
    const kept: number = flow.keptCount();
    // T29 会话模型：解析两个 native 串契约（零副作用），编译门覆盖其类型面
    const probeInfo: ArcProbeInfo = ArcRepairSessionModel.parseProbe(
      'ok:1920:1080:0:30:1000:8:33333:1:video/avc');
    const status: ArcRepairStatus = ArcRepairSessionModel.parseRepairStatus('idle');
    if (probeInfo.ok && status.kind === 'idle') {
      ArcRepairSessionModel.dedupeMinGapUs(ArcRateProbe.measuredOf(probeInfo.containerFps, 1));
    }
    const aY: Uint8Array = new Uint8Array(4);
    const bY: Uint8Array = new Uint8Array(4);
    const uvA: Uint8Array = new Uint8Array(1);
    const uvB: Uint8Array = new Uint8Array(1);
    // 2×2 合法输入不会抛；catch 只为消化 ArkTS 的「可能抛异常」提示（不新增构建告警）
    try {
      ArcRepairMerge.selectInPlace(aY, uvA, uvA, bY, uvB, uvB, 2, 2, 12);
    } catch (e) {
      // 编译探针：吞掉即可（生产调用方各自处理）
    }
  }

  /** 滑杆吸附 + 音频同步纯逻辑层的编译探针（零副作用，产物丢弃；不改变 signature 输出） */
  private static syncSnapCompileProbe(): void {
    const keyCount: number = SLIDER_KEYS.length;
    const badDecoded: number = decodeContinuousSliders('bad-id').size;
    const ladderNull: boolean = effectiveLadder([1, 2, 3], true) === null;
    const alignSr: number = AudioAlign.TARGET_SR;
    const preShiftUs: number = AudioPreflightPolicy.capPreShiftUs(-100, AudioPreflightPolicy.MIN_CONFIDENCE);
    const emptyPcm: PreflightMonoPcm = new PreflightMonoPcm(8000, 1, 0);
    const pcmFrames: number = emptyPcm.frameCount();
    const emptyPlan: PostStopPlan = PostStopSyncPlan.planFor([]);
    if (keyCount > 0 && badDecoded === 0 && ladderNull && alignSr > 0 &&
      preShiftUs > 0 && pcmFrames === 0 && emptyPlan !== undefined) {
      // 编译探针：条件恒真，仅为引用常量与函数（无副作用）
    }
  }

  /**
   * 更新器纯逻辑（update/UpdateChecker + UpdatePure + Sha256）的编译探针：
   * 覆盖 tag→versionCode、说明整形、JSON 提取、渠道排序、URL 门槛与 SHA-256 向量。
   */
  /** ST 批：设置数值档编译探针（节拍钳制/缩放档/倍率换算） */
  /** TFC 批：tap-to-focus 归一化/钳制编译探针 */
  private static focusTapCompileProbe(): void {
    const pt = FocusTapMath.pointFromWindowTap(660, 1428, 1320, 2856);
    const edge = FocusTapMath.pointFromWindowTap(-5, -5, 1320, 2856);
    if (pt !== undefined && edge !== undefined && pt.x > 0.4 && pt.x < 0.6 && edge.x > 0) {
      // 编译探针：条件恒真，仅为引用常量与函数（无副作用）
    }
  }

  private static userSettingsCompileProbe(): void {
    const tick: number = UserSettings.compareTickMsOf(99999);
    const pct: number = UserSettings.textScalePctOf(137);
    const factor: number = UserSettings.textScaleFactorOf(110);
    if (tick === 1000 && pct === 120 && factor > 1.19 && factor < 1.21) {
      // 编译探针：条件恒真，仅为引用常量与函数（无副作用）
    }
  }

  private static updateCompileProbe(): void {
    const vcode: number | undefined = CompareVersions.versionCodeOf('v0.0.10-r05');
    const newer: boolean = CompareVersions.isNewerThanVersionCode(1000000, 'v0.0.10-r05');
    const digest: string = Sha256.hexOf(new Uint8Array(0));
    const candidates: number = UpdateDownloadPure
      .downloadCandidates('https://x/y.hap', UpdateConfig.MIRROR_PREFIXES).length;
    const safe: boolean = UpdateDownloadPure.isSafeRemoteUrl('https://api.github.com/x');
    const notes: string | undefined = ReleaseNotes.forDisplay('## 标题\n- 条目');
    const tag: string | undefined = ReleaseJson.parseTag('{"tag_name":"v1.0.0"}');
    if (vcode !== undefined && newer && digest.length === 64 && candidates > 0 && safe &&
      notes !== undefined && tag !== undefined) {
      // 编译探针：条件恒真，仅为引用常量与函数（无副作用）
    }
  }

  /**
   * 相机性能三件纯逻辑的编译探针：DIRECT 显示矩阵 / fps 退档口径 / 降级抑制判定。
   * H 批登记——三者目前都还没有 ArkTS 侧调用方（DirectSink 平台胶水与 Camera2Engine 那套
   * 引擎结构在本侧不存在），靠本探针保证 hvigor 编译门覆盖其类型面，不改变 signature 输出。
   */
  private static cameraPerfCompileProbe(): void {
    const m: Array<number> = DirectDisplay.matrix(100, 100, 200, 100, 90, true);
    const fps: number = FrameRateFallback.ceilingFps([new RangeI(30, 30), new RangeI(60, 60)],
      WotaTiers.HIGH_SPEED_FPS);
    const suppressed: boolean = degradeSuppressed(false, false) && !degradeSuppressed(true, false);
    // PTS-GRID 批：编码器 PTS 网格纯算式（native 逐帧打戳接线随真机批）
    const step: number = EncoderPts.ntscGridStepNs(24) > 0
      ? EncoderPts.ntscGridStepNs(24) : EncoderPts.integerStepNs(24);
    const pts: number = EncoderPts.encoderAdaptivePtsNs(0, 1000, step, 0, 1);
    if (m.length === 9 && fps > 0 && !suppressed && pts >= 0) {
      // 编译探针：条件恒真，仅为引用常量与函数（无副作用）
    }
  }

  /**
   * 录制页参数持久化（I 批，ui/ParamsPersist）的编译探针：24 键表 / 迁移读 / 尺寸串 /
   * 枚举编解码。零副作用，不改变 signature 输出。
   */
  private static paramsPersistCompileProbe(): void {
    const keys: number = PARAMS_KEYS.length;
    const migrated: number = ParamsMigrate.int(24, undefined, 30);
    const parsed: Size | undefined = parseSize('1920x1080');
    const sizeText: string = parsed === undefined ? '' : formatSize(parsed);
    const afText: string = enumName(PrefEnum.AF_MODE, AfMode.MANUAL);
    const afBack: number = enumFrom(PrefEnum.AF_MODE, afText, AfMode.CONTINUOUS_VIDEO);
    if (keys === 24 && migrated === 24 && parsed !== undefined && sizeText === '1920x1080' &&
      afText === 'MANUAL' && afBack === AfMode.MANUAL) {
      // 编译探针：条件恒真，仅为引用常量与函数（无副作用）
    }
  }

  /** 落盘节流器编译探针：注入空定时器走一遍首请求/取消（产物丢弃，无副作用） */
  private static paramsFlushThrottleCompileProbe(): void {
    const throttle: ParamsFlushThrottle = new ParamsFlushThrottle(300, (): number => 0,
      new NoopFlushScheduler(), (): void => {
      });
    throttle.request();
    throttle.cancel();
  }

  /**
   * HUD 可编排布局地基（J1）的编译探针：度量层换行档/高度预测、位置表编解码往返、
   * 安全区钳制与落区、整页页格数学。零副作用（默认表 encode→decode 产物丢弃），
   * 不改变 signature 输出；本批没有 UI 消费方（J2 网格几何、J3 编辑器 UI）。
   */
  private static hudLayoutCompileProbe(): void {
    const room: number = hudRoomDp(360, 8);
    const perRow: number = hudPerRowFor(3, 1, room);
    const strip: number = hudStripHeightDp(3, perRow, 1);
    const table: HudLayoutTable = HudLayoutTable.decode(HudLayoutTable.default().encode());
    const area: HudAreaDp = new HudAreaDp(766, 360, 44, 72);
    const placed: ZonePlacement = clampZonePos(HudZone.RIGHT, 9999, 9999, 94, 202, area, 0);
    const band: IntRange = zoneBandYRange(HudZone.RIGHT, area, 0);
    const stored: GridCell = clampStoredCell(HudZone.LEFT, new GridCell(9, 99));
    const visible: Set<string> = editorVisibleEntries(true);
    const cellPx: number = pageCellPx(1, 2);
    const box: GridBox = pageGridBoxOf(1532, 720, cellPx);
    const page: HudPageCell = pageCellAtPointer(100, 100, 0, 0, cellPx);
    const offset: HudPointPx = pageCellOffsetPx(page, cellPx);
    const blocked: Set<string> = pageCellsBlocked(new Map<string, Array<HudEntry>>(), null,
      new Set<string>(), (entry: HudEntry): number => entry.ordinal, cellPx);
    const card: HudRectDp | undefined = undefined;
    const landed: HudZone = dropZoneOf(new HudPointDp(0, 0), HudZone.RIGHT, card, band, HudZone.READOUT);
    const caps: number = pageGridColCap() + pageGridRowCap() + gridColsCapOf(HudZone.READOUT);
    if (room > 0 && perRow > 0 && strip > 0 && table.orderOf(HudZone.TOP).length > 0 &&
      placed.xDp >= 0 && band.first <= band.last && stored.col >= 0 && visible.size > 0 &&
      cellPx > 0 && box.cols > 0 && offset.x >= 0 && blocked.size >= 0 && landed !== undefined &&
      caps > 0) {
      // 编译探针：条件恒真，仅为引用常量与函数（无副作用）
    }
  }

  /**
   * 追赶轮纯逻辑模块（Glow/DisplayScale/降噪/HistogramMath）的编译探针：参数模型、
   * 编解码往返、分档与几何、负载分档、直方图分箱归一。零副作用（产物丢弃）；
   * Glow/DisplayScale 本侧暂无平台消费方（native 链随真机批），由本探针保住编译门。
   */
  private static cameraCatchupCompileProbe(): void {
    const glow: GlowParams = decodeGlow(encodeGlow(GLOW_DEFAULT));
    const rgb: Array<number> = glowRgb(glow.colorA);
    const scale: number = glowScaleFor(1920, 1080, 30);
    const blurOffset: Array<number> = [0, 0];
    fillGlowBlurOffset(blurOffset, 960, 540, 0.02, true);
    const note = glowStatusNote(undefined, true, true);
    const rt: Array<number> | undefined = resolveDisplayRtOrNull(1080, 1920, 2);
    const fallbackScale: DisplayScale = nearestDisplayScale(1080, 1920, DisplayScale.SIXTEENTH);
    const readout: string = displayScaleReadoutLabel(DisplayScale.ORIG, '原片');
    const rebuild: boolean = displayRtNeedsRebuild(540, 960, 540, 960);
    const videoActive: boolean = videoDenoiseActive(
      effectiveVideoDenoise(VideoDenoise.GPU_BILATERAL, false), false);
    const codec: boolean = audioDenoiseNeedsCodec(AudioDenoise.SYSTEM);
    const lite: boolean = denoiseKernelLite(3840, 2160, 30);
    const hist: Array<number> = HistogramMath.normalizedHeights(
      HistogramMath.binRgba(new ArrayBuffer(64 * 64 * 4), 64, 64));
    if (glow.threshold >= 0 && rgb.length === 3 && scale > 0 && blurOffset[0] >= 0 &&
      note !== undefined && rt !== undefined && fallbackScale.divisor > 0 && readout !== '' &&
      !rebuild && !videoActive && !codec && lite && hist.length === 192) {
      // 编译探针：条件恒真，仅为引用常量与函数（无副作用）
    }
  }
}
