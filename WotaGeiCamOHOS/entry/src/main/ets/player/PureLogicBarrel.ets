/**
 * 纯逻辑模块编译守卫（T25 P3-1，T26 扩展）。
 *
 * hvigor 只编译从页面可达的文件——纯逻辑批新增的模块在没有页面消费之前不会进 abc，
 * 类型错误要到接线时才暴露。本汇总文件在 EntryAbility.onCreate 调用 signature()，
 * 把这些模块拉进 ArkTS program，让编译门覆盖它们（并顺带在启动日志留一行可核对签名）。
 *
 * T26 追加六个模块：CaptureStateMachine / CaptureSilence / AudioProbe / CaptureSources /
 * TrackSync / PcmDecode（内录纯逻辑降级版，平台面留 T27）。
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

/** 十模块的编译/启动签名（只读常量与纯函数，无副作用） */
export class PureLogicBarrel {
  /**
   * 返回一行签名：采样率/历史上限/左音量/右音量/练习桥 armed + 内录侧契约摘要。
   * 期望（默认态）：`sr=8000 max=12 vol=1/0 armed=false cap=未授权 src=2 sync=+120 chunk=64 silence=2000 pcm=S16LE`。
   */
  static signature(): string {
    const effect = selectionEffectOf(Side.LEFT, AudioTrackKind.ENV, true);
    const capLabel: string = captureStateLabel(CaptureState.idle());
    const firstCaptureSource: number = captureAudioSources()[0];
    const sync: string = TrackSync.signedMs(TrackSync.combineOffsetMs(120, 1000000, 1000000));
    const chunk: number = chunkBytes(0, 2);
    return `sr=${AudioSync.TARGET_SR} max=${CompareHistory.MAX_ENTRIES} ` +
      `vol=${effect.leftVolume}/${effect.rightVolume} armed=${comparePractice.armed} ` +
      `cap=${capLabel} src=${firstCaptureSource} sync=${sync} chunk=${chunk} ` +
      `silence=${CAPTURE_SILENCE_MIN_MS} pcm=${PcmEncoding.S16LE}`;
  }
}
