/**
 * 抽帧计划的流式执行桥（直译上游 record/ArcRepairPlan.kt 的 ArcRepairFlow/ArcOp，对齐上游
 * 38d712a）。补上 cpp/arc_flow.h 头注所引、但此前缺失的 ArkTS 锚点。
 *
 * 解码按源顺序逐帧到来，执行器每帧问一次 onSourceFrame、收尾问一次 onSourceEos，拿到的指令
 * 序列保证：保留帧恰好发一次、被抽帧只经 acc 并入其前后两枚保留帧、EOS 时尾巴上的被抽帧并进
 * 最后一枚保留帧。一帧滞留（pending）+ 一个累积面（acc）就是全部状态。
 *
 * 两个入口同一台机器：
 * - onSourceFrame（离线路）：保留与否由 ArcRepairPlan.isKept 按计划判；
 * - onClassification（在线路）：保留与否由引擎的实时节奏判（到达时刻 vs 应到时刻），录制侧
 *   没有"先知帧率"，计划公式用不上。
 *
 * ArkTS 落法：Kotlin sealed interface ArcOp → kind 判别字段 + 静态工厂（ACCUMULATE / FOLD /
 * HOLD / MERGE / EMIT 的载荷字段同表）；List\&lt;ArcOp\&gt; → Array\&lt;ArcOp\&gt;。
 *
 * 纯逻辑（不 import 平台 Kit）；执行器留 T29。
 */
import { ArcRepairPlan } from './ArcRepairPlan';

/** 指令种类 */
export enum ArcOpKind {
  /** 处理被抽帧：fresh 时 acc 整帧换成本帧，否则 acc = select(acc, cur) 继续累积 */
  ACCUMULATE = 'ACCUMULATE',
  /** pending = select(pending, acc)：累积补弧并进**前**保留帧 */
  FOLD = 'FOLD',
  /** pending = cur：首枚保留帧直接滞留待发 */
  HOLD = 'HOLD',
  /** pending = select(cur, acc)（useAcc = 自上一保留帧以来有被抽帧）：后保留帧带弧升级 */
  MERGE = 'MERGE',
  /** 把待发帧写进编码器（ptsUs = 均匀目标帧率步长的时间戳，微秒） */
  EMIT = 'EMIT'
}

/**
 * 单帧处置指令（计划→行为的桥）。载荷字段共用一张表：
 * fresh 仅 ACCUMULATE 有效，useAcc 仅 MERGE 有效，ptsUs 仅 EMIT 有效（其余为 false/0）。
 */
export class ArcOp {
  readonly kind: ArcOpKind;
  readonly fresh: boolean;
  readonly useAcc: boolean;
  readonly ptsUs: number;

  private constructor(kind: ArcOpKind, fresh: boolean, useAcc: boolean, ptsUs: number) {
    this.kind = kind;
    this.fresh = fresh;
    this.useAcc = useAcc;
    this.ptsUs = ptsUs;
  }

  static accumulateCur(fresh: boolean): ArcOp {
    return new ArcOp(ArcOpKind.ACCUMULATE, fresh, false, 0);
  }

  static foldAccIntoPending(): ArcOp {
    return new ArcOp(ArcOpKind.FOLD, false, false, 0);
  }

  static holdCurAsPending(): ArcOp {
    return new ArcOp(ArcOpKind.HOLD, false, false, 0);
  }

  static mergeCurAsPending(useAcc: boolean): ArcOp {
    return new ArcOp(ArcOpKind.MERGE, false, useAcc, 0);
  }

  static emitPending(ptsUs: number): ArcOp {
    return new ArcOp(ArcOpKind.EMIT, false, false, ptsUs);
  }
}

/** 抽帧计划的流式状态机（离线路与在线分类路共用） */
export class ArcRepairFlow {
  private readonly dstFps: number;
  private readonly plannedKeep: ((srcIndex: number) => boolean) | undefined;

  /** 已发出的保留帧数 = 下一个输出下标（进度与 PTS 都由它派生；只读出口 keptCount()） */
  private keptCount_: number = 0;
  /** 是否有一枚滞留待发的保留帧（只读出口 hasPending()） */
  private hasPending_: boolean = false;
  /** 自上一保留帧以来是否累积了被抽帧（只读出口 hasAcc()） */
  private hasAcc_: boolean = false;

  /** dstFps 非法（≤0）直接抛错：PTS 步长分母为 0 会产出全 0 时间戳，静默降级比失败更坏 */
  private constructor(dstFps: number, plannedKeep: ((srcIndex: number) => boolean) | undefined) {
    if (!(dstFps > 0)) {
      throw new Error(`ArcRepairFlow dstFps 必须为正，实际 ${dstFps}`);
    }
    this.dstFps = dstFps;
    this.plannedKeep = plannedKeep;
  }

  /** 已发出的保留帧数（只读） */
  keptCount(): number {
    return this.keptCount_;
  }

  /** 是否有一枚滞留待发的保留帧（只读） */
  hasPending(): boolean {
    return this.hasPending_;
  }

  /** 自上一保留帧以来是否累积了被抽帧（只读） */
  hasAcc(): boolean {
    return this.hasAcc_;
  }

  /** 离线路：按抽帧计划逐源帧问询（保留判定吃有理数真值） */
  static ofPlan(plan: ArcRepairPlan): ArcRepairFlow {
    return new ArcRepairFlow(plan.dstFps, (srcIndex: number): boolean =>
      ArcRepairPlan.isKept(srcIndex, plan.srcNum, plan.srcDen, plan.dstFps));
  }

  /** 录制在线路：dstFps 只喂 PTS，保留决策走 onClassification */
  static ofDstFps(dstFps: number): ArcRepairFlow {
    return new ArcRepairFlow(dstFps, undefined);
  }

  /** 离线路：按抽帧计划逐源帧问询 */
  onSourceFrame(srcIndex: number): Array<ArcOp> {
    const keepFn: ((srcIndex: number) => boolean) | undefined = this.plannedKeep;
    const kept: boolean = keepFn === undefined ? true : keepFn(srcIndex);
    return this.onClassification(kept);
  }

  /** 在线路：由引擎给出本帧是否保留 */
  onClassification(kept: boolean): Array<ArcOp> {
    const ops: Array<ArcOp> = [];
    if (!kept) {
      // 首枚保留帧之前的被抽帧没有"前帧"可并，直接弃（片头黑场语义不受损）
      if (!this.hasPending_) {
        return ops;
      }
      const fresh: boolean = !this.hasAcc_;
      this.hasAcc_ = true;
      ops.push(ArcOp.accumulateCur(fresh));
      return ops;
    }
    if (!this.hasPending_) {
      this.hasPending_ = true;
      ops.push(ArcOp.holdCurAsPending());
      return ops;
    }
    if (this.hasAcc_) {
      ops.push(ArcOp.foldAccIntoPending());
    }
    ops.push(ArcOp.emitPending(this.ptsOf(this.keptCount_)));
    ops.push(ArcOp.mergeCurAsPending(this.hasAcc_));
    this.keptCount_++;
    this.hasAcc_ = false;
    return ops;
  }

  /** 解码 EOS：尾巴上的累积补弧并进最后一枚待发帧后发出 */
  onSourceEos(): Array<ArcOp> {
    const ops: Array<ArcOp> = [];
    if (!this.hasPending_) {
      return ops;
    }
    if (this.hasAcc_) {
      ops.push(ArcOp.foldAccIntoPending());
    }
    ops.push(ArcOp.emitPending(this.ptsOf(this.keptCount_)));
    this.keptCount_++;
    this.hasAcc_ = false;
    this.hasPending_ = false;
    return ops;
  }

  /** 输出第 j 帧的均匀 PTS（先乘后除不累积截断） */
  private ptsOf(j: number): number {
    return Math.floor(j * 1000000 / this.dstFps);
  }
}
