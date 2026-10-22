/**
 * 离线修复会话模型（纯逻辑）：错误码契约 + native 串解析 + 速率/NO_NEED/去重/帧数估算 +
 * 结果归因 + 用户口径文案。
 *
 * 对应上游 record/ArcRepairRunner.kt 的常量区与判定区（对齐上游 38d712a）；执行器与 native
 * 见 cpp/arc_repair.* 与 record/ArcRepairRunner.ets。
 *
 * 两个 native 串契约（与 cpp/arc_repair.cpp 逐字段对齐）：
 * - probe：`ok:<w>:<h>:<rotation>:<containerFps>:<durationMs>:<cleanCount>:<medianUs>:<audioCount>:<mime>`
 *   或 `err:<code>`；
 * - status：`idle` | `running:<pct>` | `done:<videoSamples>:<audioSamples>:<mux0/1>:<track0/1>:<stop0/1>`
 *   | `failed:<code>` | `cancelled`。
 *
 * 纯逻辑（不 import 平台 Kit）。
 */
import { ArcRateProbe, MeasuredSrcFps } from './ArcKeepRule';
import { ArcRepairPlan } from './ArcRepairPlan';
import { arcRepairKeep, arcRepairOk } from './ArcRepairVerdict';

/** 错误码（机器码；UI 只做映射，不解析自由文本） */
export class ArcRepairError {
  static readonly SOURCE: string = 'ARC_SOURCE';
  static readonly DECODER: string = 'ARC_DECODER';
  static readonly ENCODER: string = 'ARC_ENCODER';
  static readonly MUXER: string = 'ARC_MUXER';
  /** CPU 路线拿不到解码输出的 YUV（该解码器只支持 Surface 输出/布局未知） */
  static readonly CPU_YUV: string = 'ARC_CPU_YUV';
  /** CPU 路线没法把帧写进编码器输入缓冲 */
  static readonly CPU_FEED: string = 'ARC_CPU_FEED';
  static readonly NO_VIDEO: string = 'ARC_NO_VIDEO';
  static readonly CANCELLED: string = 'ARC_CANCELLED';
  static readonly TIMEOUT: string = 'ARC_TIMEOUT';
  static readonly ENGINE: string = 'ARC_ENGINE';
  /** 源实测帧率不高于目标：没有帧可抽（不是故障，UI 给专门提示） */
  static readonly NO_NEED: string = 'ARC_NO_NEED';
  /** 串解析失败（native 契约被破坏） */
  static readonly PROBE: string = 'ARC_PROBE';
}

/** 探测结果（parseProbe 产物；err 非空时其余字段无意义） */
export class ArcProbeInfo {
  readonly ok: boolean;
  readonly width: number;
  readonly height: number;
  /** 源容器/视频轨旋转角（0/90/180/270） */
  readonly rotation: number;
  /** 容器标称帧率（整数；仅作回退与日志对照） */
  readonly containerFps: number;
  readonly durationMs: number;
  /** 干净帧距样本数（滤噪后 ≤2M 样本扫描） */
  readonly cleanCount: number;
  /** 干净帧距中位数（µs） */
  readonly medianUs: number;
  readonly audioCount: number;
  readonly mime: string;
  readonly err: string;

  constructor(ok: boolean, width: number, height: number, rotation: number, containerFps: number,
    durationMs: number, cleanCount: number, medianUs: number, audioCount: number, mime: string,
    err: string) {
    this.ok = ok;
    this.width = width;
    this.height = height;
    this.rotation = rotation;
    this.containerFps = containerFps;
    this.durationMs = durationMs;
    this.cleanCount = cleanCount;
    this.medianUs = medianUs;
    this.audioCount = audioCount;
    this.mime = mime;
    this.err = err;
  }
}

/** 会话状态（parseRepairStatus 产物） */
export class ArcRepairStatus {
  readonly kind: string;
  readonly progress: number;
  readonly videoSamples: number;
  readonly audioSamples: number;
  readonly muxStarted: boolean;
  readonly trackAdded: boolean;
  readonly muxStopOk: boolean;
  readonly error: string;

  constructor(kind: string, progress: number, videoSamples: number, audioSamples: number,
    muxStarted: boolean, trackAdded: boolean, muxStopOk: boolean, error: string) {
    this.kind = kind;
    this.progress = progress;
    this.videoSamples = videoSamples;
    this.audioSamples = audioSamples;
    this.muxStarted = muxStarted;
    this.trackAdded = trackAdded;
    this.muxStopOk = muxStopOk;
    this.error = error;
  }
}

/** 会话结论（resolveRepairOutcome 产物）：keep 才允许把成片留在媒体库 */
export class ArcRepairOutcome {
  readonly ok: boolean;
  readonly keep: boolean;
  readonly error: string;

  constructor(ok: boolean, keep: boolean, error: string) {
    this.ok = ok;
    this.keep = keep;
    this.error = error;
  }
}

function toInt(text: string): number {
  const v: number = Number(text);
  return Number.isFinite(v) ? Math.trunc(v) : 0;
}

export class ArcRepairSessionModel {
  /**
   * 解析 native 探测串。字段数必须恰为 10（ok）或 2（err）；任何不符返回 ok=false + ARC_PROBE。
   * mime 里不含冒号（video/avc、video/hevc、video/av01 等），故按 `:` 直接切分安全。
   */
  static parseProbe(raw: string): ArcProbeInfo {
    const bad: ArcProbeInfo = new ArcProbeInfo(false, 0, 0, 0, 0, 0, 0, 0, 0, '', ArcRepairError.PROBE);
    if (raw.length === 0) {
      return bad;
    }
    const parts: Array<string> = raw.split(':');
    if (parts[0] === 'err') {
      if (parts.length !== 2) {
        return bad;
      }
      return new ArcProbeInfo(false, 0, 0, 0, 0, 0, 0, 0, 0, '', parts[1]);
    }
    if (parts[0] !== 'ok' || parts.length !== 10) {
      return bad;
    }
    return new ArcProbeInfo(true, toInt(parts[1]), toInt(parts[2]), toInt(parts[3]),
      toInt(parts[4]), toInt(parts[5]), toInt(parts[6]), toInt(parts[7]), toInt(parts[8]),
      parts[9], '');
  }

  /**
   * 实测源帧率（有理数）：干净样本 < MIN_DELTAS（8）时退回容器标称帧率（极短视频中位数不可信），
   * 否则中位帧距吸附已知档位（含 NTSC ÷1001 档）。
   */
  static measuredRateFromProbe(cleanCount: number, medianUs: number, containerFps: number): MeasuredSrcFps {
    if (cleanCount < ArcRateProbe.MIN_DELTAS || medianUs <= 0) {
      return ArcRateProbe.measuredOf(containerFps > 0 ? containerFps : 30, 1);
    }
    return ArcRateProbe.snapToKnownRate(medianUs);
  }

  /** 源不比目标快（没有帧可抽）→ true：任务以 NO_NEED 收场（不是故障） */
  static decideNoNeed(rate: MeasuredSrcFps, dstFps: number): boolean {
    if (dstFps <= 0) {
      return false;
    }
    return rate.atMostInt(dstFps);
  }

  /**
   * 按目标档造抽帧计划（探测→计划→面板守卫的**唯一**入口，T30 审查 P1）。
   * 探测结果只保存源侧事实（srcFrames/有理数速率）；目标档由面板选择，每次切换都用本函数重算，
   * 避免「探测只按 24 档算一次、面板换 25 档后仍用旧计划」的接线漏赋值。
   */
  static planForTarget(srcFrames: number, rate: MeasuredSrcFps, dstFps: number): ArcRepairPlan {
    return ArcRepairPlan.of(srcFrames, rate.num, rate.den, dstFps);
  }

  /** 该目标档是否需要修复（源帧率 > 目标）；false = 此档不可行（面板应禁用并提示） */
  static targetFeasible(rate: MeasuredSrcFps, dstFps: number): boolean {
    return !ArcRepairSessionModel.decideNoNeed(rate, dstFps);
  }

  /**
   * 解码帧去重门限（µs）：半个源帧周期（`500000·den/num` 整数除）。同一 PTS 重复解码（B 帧重排/
   * 容器抖动）间隔小于该门限的帧直接丢弃，不推进 prevPts、不进抽帧状态机。
   */
  static dedupeMinGapUs(rate: MeasuredSrcFps): number {
    if (rate.num <= 0) {
      return 0;
    }
    return Math.floor((500000 * rate.den) / rate.num);
  }

  /**
   * 源帧数估算（进度的分母）：时长 × 实测帧率，四舍五入到帧（`+500·den` 个微秒当半帧）。
   * 至少 1（时长未知时调用方另行按样本数兜底）。
   */
  static estimateSrcFrames(durationMs: number, rate: MeasuredSrcFps): number {
    if (durationMs <= 0 || rate.num <= 0) {
      return 0;
    }
    const frames: number = Math.floor((durationMs * rate.num + 500 * rate.den) / (1000 * rate.den));
    return Math.max(1, frames);
  }

  /** 解析状态串（契约见文件头）；未知形态归 failed:ARC_PROBE */
  static parseRepairStatus(raw: string): ArcRepairStatus {
    const parts: Array<string> = raw.length > 0 ? raw.split(':') : ['idle'];
    const head: string = parts[0];
    if (head === 'idle') {
      return new ArcRepairStatus('idle', 0, 0, 0, false, false, false, '');
    }
    if (head === 'running') {
      const pct: number = parts.length >= 2 ? toInt(parts[1]) : 0;
      return new ArcRepairStatus('running', Math.min(100, Math.max(0, pct)), 0, 0,
        false, false, false, '');
    }
    if (head === 'done') {
      if (parts.length !== 6) {
        return new ArcRepairStatus('failed', 0, 0, 0, false, false, false, ArcRepairError.PROBE);
      }
      return new ArcRepairStatus('done', 100, toInt(parts[1]), toInt(parts[2]),
        toInt(parts[3]) === 1, toInt(parts[4]) === 1, toInt(parts[5]) === 1, '');
    }
    if (head === 'failed') {
      const code: string = parts.length >= 2 ? parts[1] : ArcRepairError.PROBE;
      return new ArcRepairStatus('failed', 0, 0, 0, false, false, false, code);
    }
    if (head === 'cancelled') {
      return new ArcRepairStatus('cancelled', 0, 0, 0, false, false, false, ArcRepairError.CANCELLED);
    }
    return new ArcRepairStatus('failed', 0, 0, 0, false, false, false, ArcRepairError.PROBE);
  }

  /**
   * 结果归因：done 态先走 arcRepairOk（三格）再走 arcRepairKeep（stop 门槛）。
   * - 可保留 → ok=true/keep=true/error=''；
   * - 三格成立但 stop 抛 → `ARC_ENGINE:MUX_CLOSE`（moov 未写，坏容器不得入库）；
   * - **无轨或零样本（trackAdded=false 或 videoSamples<=0）→ `ARC_NO_VIDEO`**（上游同口径：
   *   「没有可用视频」比「引擎错误」如实；建轨成功但一帧未写也归此类）；
   * - 其余三格失败 → `ARC_ENGINE`（视频写样通道异常）。
   * failed/cancelled 原样透传错误码。
   */
  static resolveRepairOutcome(status: ArcRepairStatus): ArcRepairOutcome {
    if (status.kind === 'failed') {
      return new ArcRepairOutcome(false, false, status.error);
    }
    if (status.kind === 'cancelled') {
      return new ArcRepairOutcome(false, false, ArcRepairError.CANCELLED);
    }
    if (status.kind !== 'done') {
      return new ArcRepairOutcome(false, false, ArcRepairError.ENGINE);
    }
    const ok: boolean = arcRepairOk(status.muxStarted, status.trackAdded, status.videoSamples);
    if (arcRepairKeep(ok, status.muxStopOk)) {
      return new ArcRepairOutcome(true, true, '');
    }
    if (ok && !status.muxStopOk) {
      return new ArcRepairOutcome(false, false, `${ArcRepairError.ENGINE}:MUX_CLOSE`);
    }
    // 零样本（含建轨成功但一帧未写出）与无轨同归 ARC_NO_VIDEO（上游同口径）：
    // 「没有可用视频」比「引擎错误」更如实，UI 文案不同
    if (!status.trackAdded || status.videoSamples <= 0) {
      return new ArcRepairOutcome(false, false, ArcRepairError.NO_VIDEO);
    }
    return new ArcRepairOutcome(false, false, ArcRepairError.ENGINE);
  }

  /**
   * 错误码 → 用户文案（面板/日志共用）。覆盖全部 ARC_* 码 + `ENGINE:MUX_CLOSE` / `ENGINE:MUX`
   * + start 的 busy/args/worker；未知码兜底带原码（绝不吞成空串）。
   */
  static describeRepairError(code: string): string {
    if (code === ArcRepairError.NO_NEED) {
      return '源帧率不高于目标帧率，无需修复';
    }
    if (code === ArcRepairError.SOURCE) {
      return '读取源视频失败（缺失/损坏/无时长）';
    }
    if (code === ArcRepairError.DECODER) {
      return '视频解码失败';
    }
    if (code === ArcRepairError.ENCODER) {
      return '视频编码失败';
    }
    if (code === ArcRepairError.MUXER) {
      return '封装写盘失败（存储可能已满）';
    }
    if (code === ArcRepairError.CPU_YUV) {
      return '解码输出格式不受支持（无法取得可处理的 YUV）';
    }
    if (code === ArcRepairError.CPU_FEED) {
      return '视频帧写入编码器失败';
    }
    if (code === ArcRepairError.NO_VIDEO) {
      return '源没有可用的视频帧';
    }
    if (code === ArcRepairError.CANCELLED) {
      return '已取消，半成品已删除';
    }
    if (code === ArcRepairError.TIMEOUT) {
      return '修复超时，已中止并清理半成品';
    }
    if (code === ArcRepairError.PROBE) {
      return '探测结果异常（内部契约错误）';
    }
    if (code === `${ArcRepairError.ENGINE}:MUX_CLOSE`) {
      return '成片封装未收尾（moov 未写），已丢弃';
    }
    if (code === `${ArcRepairError.ENGINE}:MUX`) {
      return '视频样本写入封装失败';
    }
    if (code === ArcRepairError.ENGINE) {
      return '修复引擎内部错误';
    }
    if (code === 'busy') {
      return '已有修复任务在进行';
    }
    if (code === 'args') {
      return '修复参数非法';
    }
    if (code === 'worker') {
      return '修复线程启动失败';
    }
    return `修复失败（${code}）`;
  }

  /**
   * 音轨口径说明（三态）：源无音轨 → 空串（无需提示）；有音轨且写出样本 → 成功注记；
   * 有音轨但零样本（逐轨降级/不支持的编码）→ 明确告知「成片无音频」。
   *
   * 口径（T30 审查 P3）：状态串保持 6 字段不变，**逐轨成功数只在 native done 日志**
   * （`audio lanes ok=M/N`）；因此多音轨时这里只说「至少 1 条已直拷」，不宣称全部成功。
   */
  static audioLaneNote(audioCount: number, audioSamples: number): string {
    if (audioCount <= 0) {
      return '';
    }
    if (audioSamples > 0) {
      return audioCount === 1
        ? `源 1 条音轨已直拷（${audioSamples} 个样本）`
        : `源 ${audioCount} 条音轨中至少 1 条已直拷（共 ${audioSamples} 个样本；逐轨见日志）`;
    }
    return `源 ${audioCount} 条音轨无法直拷（编码不受支持/零样本），成片将无音频`;
  }

  /** 用户口径说明（成片说明/日志共用；与上游 note 同义） */
  static repairNote(srcFps: number, srcNum: number, srcDen: number, containerFps: number,
    dstFps: number, deadband: number): string {
    return `源实测≈${srcFps}fps（真值 ${srcNum}/${srcDen}，容器标 ${containerFps}）→ ` +
      `目标 ${dstFps}fps，被抽帧画面已按整像素选择（差分死区 D=${deadband}）并入前后帧；不改原片`;
  }
}
