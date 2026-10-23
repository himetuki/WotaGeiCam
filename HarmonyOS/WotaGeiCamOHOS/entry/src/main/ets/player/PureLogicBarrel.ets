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
}
