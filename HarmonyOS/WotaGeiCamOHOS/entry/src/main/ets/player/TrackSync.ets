/**
 * 双音轨偏移合成与结果分类（直译上游 player/TrackSync.kt 的纯函数区，对齐上游 e950328；
 * e950328 追加 AUTO/MANUAL 来源、后校准落盘决策与 prefs 五键编解码）。
 *
 * 背景：内录片的容器里「环境轨 = 音频组 0、内录轨 = 音频组 1」。两轨各自按首样本归零后做
 * 互相关得到「内容差」，但两轨首样本的**容器时刻差**（feeder 启动记账那几毫秒）在归零时丢了，
 * 合成总偏移要把这层加回来。
 *
 * 偏移符号：**rightPos = leftPos + offsetMs**；正 = 内录晚响。导出选内录轨时按 -offset 平移 PTS。
 * 低置信度（< AudioSync.MIN_CONFIDENCE）归 0——按录制起点对齐，不给用户假精确的偏移。
 *
 * 【偏移来源（e950328）】AUTO = 录制停止后自动校准（可被手动覆盖）；MANUAL = 用户点
 * 「同步音轨」。AUTO 落盘受 autoDecision 约束：低置信/越界不写、已有 MANUAL 结果永不覆盖
 * （旧 sync_done 无 source 视为 MANUAL）；|offset| < 20ms 归零（与预校准同一听感门槛）。
 *
 * 【prefs 编解码（e950328）】五键 `sync_done/offset/source/conf/at`（key 前缀 + 段 key）。
 * 上游直接吃 android.content.SharedPreferences（typed get/put）；本侧用**最小注入接口**
 * SyncPrefs（仅 get/put string），生产侧由 @ohos.data.preferences 适配、测试可用纯 map 替——
 * 五键的编解码（bool/number → string → 回读）全部收在本文件，单一真源。
 *
 * 纯逻辑（不 import 平台 Kit；只复用 AudioSync 与 record/AudioPreflightPolicy 的纯常量）；
 * extractor/解码与真实 prefs 读写留 T27。
 */
import { AudioSync } from './AudioSync';
import { AudioPreflightPolicy } from '../record/AudioPreflightPolicy';

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

/** 偏移来源：AUTO = 录制停止后自动校准（可被手动覆盖）；MANUAL = 用户点「同步音轨」 */
export enum Source {
  AUTO = 'auto',
  MANUAL = 'manual'
}

/** 后校准落盘决策的四态（上游 sealed interface AutoDecision 的类化移植） */
export enum AutoDecisionKind {
  /** 已有手动结果：自动校准永不覆盖 */
  ManualProtected = 'ManualProtected',
  /** 置信度不足：不写 */
  LowConfidence = 'LowConfidence',
  /** 超出 ±MAX_AUTO_OFFSET_MS：只记日志，不写 */
  OutOfRange = 'OutOfRange',
  /** 写入该偏移（|offset| < 20ms 已归零） */
  Write = 'Write'
}

/** 决策值：kind + offsetMs（仅 Write 态有意义，其余恒 0） */
export class AutoDecision {
  readonly kind: AutoDecisionKind;
  readonly offsetMs: number;

  constructor(kind: AutoDecisionKind, offsetMs: number) {
    this.kind = kind;
    this.offsetMs = offsetMs;
  }

  static manualProtected(): AutoDecision {
    return new AutoDecision(AutoDecisionKind.ManualProtected, 0);
  }

  static lowConfidence(): AutoDecision {
    return new AutoDecision(AutoDecisionKind.LowConfidence, 0);
  }

  static outOfRange(): AutoDecision {
    return new AutoDecision(AutoDecisionKind.OutOfRange, 0);
  }

  static write(offsetMs: number): AutoDecision {
    return new AutoDecision(AutoDecisionKind.Write, offsetMs);
  }

  /** 值等价（上游 Kotlin data class equality 的对应面：kind + offsetMs） */
  sameAs(other: AutoDecision): boolean {
    return this.kind === other.kind && this.offsetMs === other.offsetMs;
  }
}

/** 一次原子读出的同步结果（offset/source/confidence 同源，UI 不会读到半新半旧） */
export class SyncSnapshot {
  readonly offsetMs: number;
  readonly source: Source;
  readonly confidence: number;

  constructor(offsetMs: number, source: Source, confidence: number) {
    this.offsetMs = offsetMs;
    this.source = source;
    this.confidence = confidence;
  }
}

/**
 * prefs 最小注入面（本侧对上游 android.content.SharedPreferences 的收窄）：
 * 只有 string 存取；生产由 @ohos.data.preferences 适配（T27），测试用纯 map 实现即可。
 */
export interface SyncPrefs {
  getString(key: string, def: string): string;
  putString(key: string, value: string): void;
}

export class TrackSync {
  /** 低置信度门槛的单一真源（上游复用 AudioSync.MIN_CONFIDENCE） */
  static readonly MIN_CONFIDENCE: number = AudioSync.MIN_CONFIDENCE;
  /** 后校准的最小可辨偏移：低于它落 0（与预校准同一听感门槛） */
  static readonly AUTO_MIN_OFFSET_MS: number = AudioPreflightPolicy.MIN_PRE_SHIFT_MS;

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

  /**
   * 后校准落盘决策（上游 e950328；autoRun 每一次写/不写的唯一分岔点）：
   * 手动保护 > 低置信 > 越界 > 20ms 归零 > 写原值。
   */
  static autoDecision(
    offsetMs: number,
    confidence: number,
    existing: Source | undefined,
    minConfidence: number = AudioPreflightPolicy.MIN_CONFIDENCE
  ): AutoDecision {
    if (existing === Source.MANUAL) {
      return AutoDecision.manualProtected();
    }
    if (confidence < minConfidence) {
      return AutoDecision.lowConfidence();
    }
    if (!AudioPreflightPolicy.autoOffsetWithinRange(offsetMs, confidence, minConfidence)) {
      return AutoDecision.outOfRange();
    }
    const value: number = Math.abs(offsetMs) < TrackSync.AUTO_MIN_OFFSET_MS ? 0 : offsetMs;
    return AutoDecision.write(value);
  }

  /** 旧记录来源解析（纯函数）：未 done → undefined；done 但无/未知 source → 老的手动记录 */
  static sourceOfStored(done: boolean, rawSource: string | undefined): Source | undefined {
    if (!done) {
      return undefined;
    }
    return rawSource === Source.AUTO ? Source.AUTO : Source.MANUAL;
  }

  /** prefs 五键名（与上游逐字相同：`sync_<字段>:<段key>`） */
  static doneKeyOf(key: string): string {
    return `sync_done:${key}`;
  }

  static offsetKeyOf(key: string): string {
    return `sync_offset:${key}`;
  }

  static sourceKeyOf(key: string): string {
    return `sync_source:${key}`;
  }

  static confKeyOf(key: string): string {
    return `sync_conf:${key}`;
  }

  static atKeyOf(key: string): string {
    return `sync_at:${key}`;
  }

  /** 五键全集（清除/搬运用；顺序与 read/write 一致） */
  static keyNamesOf(key: string): Array<string> {
    return [
      TrackSync.doneKeyOf(key),
      TrackSync.offsetKeyOf(key),
      TrackSync.sourceKeyOf(key),
      TrackSync.confKeyOf(key),
      TrackSync.atKeyOf(key)
    ];
  }

  /**
   * 抹掉某段的同步结果（RISK 批，对齐上游 f941799 的 TrackSync.clearFor）。
   *
   * 上游动机是 MediaStore 复用 `_ID` 后新片命中旧片的 done/offset；本侧键是**路径**，
   * 复用面更窄但仍存在（同一秒同序号的成片名可重现），且「删片后记录还在」本身是脏账。
   * SyncPrefs 只有 putString，故清成空串——读侧把 done!=='true' 一律视为未同步，
   * 与真删除语义等价（不引入删键 API）。
   */
  static clearFor(prefs: SyncPrefs, key: string): void {
    for (const k of TrackSync.keyNamesOf(key)) {
      prefs.putString(k, '');
    }
  }

  /** 读来源（bool/string 编码经五键 codec）：done 未置 → undefined */
  static sourceOfPrefs(prefs: SyncPrefs, key: string): Source | undefined {
    const done: boolean = prefs.getString(TrackSync.doneKeyOf(key), 'false') === 'true';
    return TrackSync.sourceOfStored(done, prefs.getString(TrackSync.sourceKeyOf(key), ''));
  }

  /** 一次原子读出快照（offset/source/confidence 同一份 prefs）；未校准返回 undefined */
  static cachedSyncOf(prefs: SyncPrefs, key: string): SyncSnapshot | undefined {
    const done: boolean = prefs.getString(TrackSync.doneKeyOf(key), 'false') === 'true';
    if (!done) {
      return undefined;
    }
    const source: Source | undefined = TrackSync.sourceOfPrefs(prefs, key);
    if (source === undefined) {
      return undefined;
    }
    const rawOffset: number = Number.parseInt(prefs.getString(TrackSync.offsetKeyOf(key), '0'));
    const rawConf: number = Number.parseFloat(prefs.getString(TrackSync.confKeyOf(key), '0'));
    const offset: number = Number.isFinite(rawOffset) ? rawOffset : 0;
    const conf: number = Number.isFinite(rawConf) ? rawConf : 0;
    return new SyncSnapshot(offset, source, conf);
  }

  /** 写一次完整同步结果（五键全量落盘；上游 writeSyncPrefs 的 string 编码版） */
  static writeSyncPrefs(
    prefs: SyncPrefs,
    key: string,
    offsetMs: number,
    source: Source,
    confidence: number,
    nowMs: number
  ): void {
    prefs.putString(TrackSync.doneKeyOf(key), 'true');
    prefs.putString(TrackSync.offsetKeyOf(key), `${offsetMs}`);
    prefs.putString(TrackSync.sourceKeyOf(key), source === Source.AUTO ? 'auto' : 'manual');
    prefs.putString(TrackSync.confKeyOf(key), `${confidence}`);
    prefs.putString(TrackSync.atKeyOf(key), `${nowMs}`);
  }

  /**
   * AUTO 写盘的行为桥：写前复核 source，已有 MANUAL 拒绝（TOCTOU 防线——AUTO 解码前读到的
   * existing 只是快速拒绝，只有落盘前这一眼才能保证 MANUAL 优先）。返回 false = 被手动结果拦下。
   */
  static writeAutoPrefs(
    prefs: SyncPrefs,
    key: string,
    offsetMs: number,
    confidence: number,
    nowMs: number
  ): boolean {
    if (TrackSync.sourceOfPrefs(prefs, key) === Source.MANUAL) {
      return false;
    }
    TrackSync.writeSyncPrefs(prefs, key, offsetMs, Source.AUTO, confidence, nowMs);
    return true;
  }
}
