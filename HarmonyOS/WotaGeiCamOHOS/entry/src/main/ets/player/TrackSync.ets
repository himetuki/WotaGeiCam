/**
 * 双音轨偏移合成与结果分类（直译上游 player/TrackSync.kt 的纯函数区，对齐上游 38d712a）。
 *
 * 背景：内录片的容器里「环境轨 = 音频组 0、内录轨 = 音频组 1」。两轨各自按首样本归零后做
 * 互相关得到「内容差」，但两轨首样本的**容器时刻差**（feeder 启动记账那几毫秒）在归零时丢了，
 * 合成总偏移要把这层加回来。
 *
 * 偏移符号：**rightPos = leftPos + offsetMs**；正 = 内录晚响。导出选内录轨时按 -offset 平移 PTS。
 * 低置信度（< AudioSync.MIN_CONFIDENCE）归 0——按录制起点对齐，不给用户假精确的偏移。
 *
 * 纯逻辑（不 import 平台 Kit；只复用 AudioSync 的纯常量）；extractor/解码与持久化留 T27。
 */
import { AudioSync } from './AudioSync';

/** 同步失败的结果（上游 Result 的最小字段集；reasonCode 对应上游字符串资源语义） */
export class TrackSyncFail {
  readonly ok: boolean;
  readonly dualAudio: boolean;
  /** 'no-audio' = 没有音轨；'sync-failed' = 双轨在而读不出（请重试） */
  readonly reasonCode: string;

  constructor(ok: boolean, dualAudio: boolean, reasonCode: string) {
    this.ok = ok;
    this.dualAudio = dualAudio;
    this.reasonCode = reasonCode;
  }
}

export class TrackSync {
  /** 低置信度门槛的单一真源（上游复用 AudioSync.MIN_CONFIDENCE） */
  static readonly MIN_CONFIDENCE: number = AudioSync.MIN_CONFIDENCE;

  /**
   * 合成总偏移：互相关的「内容差」（各轨已按自身首样本归零）+ 两轨首样本的容器时刻差。
   * cap 0 时刻晚于 env 0 时刻 = 内录整体晚，直接相加。µs→ms 负值向零截断（Kotlin Long 整除语义）。
   */
  static combineOffsetMs(contentOffsetMs: number, capFirstPtsUs: number, envFirstPtsUs: number): number {
    return contentOffsetMs + Math.trunc((capFirstPtsUs - envFirstPtsUs) / 1000);
  }

  /** 低置信度 = 按录制起点对齐（0）；正常态原样保留 */
  static resolveOffsetMs(offsetMs: number, lowConfidence: boolean): number {
    return lowConfidence ? 0 : offsetMs;
  }

  /** 「已同步 +Xms」读数的符号格式：正带加号、负自带减号、零不带号 */
  static signedMs(offsetMs: number): string {
    return offsetMs > 0 ? `+${offsetMs}` : `${offsetMs}`;
  }

  /**
   * 失败分类：结构性双轨不足 → dualAudio=false + 「没有音轨」（文案如实）；
   * 双轨在而读不出（打不开/首样本读不到/解码失败）→ dualAudio=true + 「同步失败请重试」——
   * 探测已判双音轨的片上误报「没有音轨」是误导。
   */
  static failResult(structuralDualAudio: boolean): TrackSyncFail {
    if (structuralDualAudio) {
      return new TrackSyncFail(false, true, 'sync-failed');
    }
    return new TrackSyncFail(false, false, 'no-audio');
  }
}
