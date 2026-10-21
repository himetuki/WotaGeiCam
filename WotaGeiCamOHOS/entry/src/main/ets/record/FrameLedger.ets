/**
 * 直译自上游 record/FrameLedger.kt（harmony 分支）。
 * 平台差异：
 * - AtomicLong → private static number 字段：原子性由 ArkTS 单线程事件循环承担；
 *   未来跨 worker 迁移时需改造（每 worker 各自账本或改 Sendable 计数器）；
 * - Kotlin Long 除法向零截断 → Math.trunc / Math.floor（值域非负，等价）；
 * - padStart(2, '0') → 手写两位补零（与 core/Constants.ets 的 pad2 同款式样）。
 */

/**
 * 录制帧账的跨组件计数器（2026-10-17 起，只观测、不参与任何判定）。
 *
 * 为什么要跨组件共享：这条链的帧从相机回调到 GL 回调/消费再到编码器 swap，分属不同线程与类，
 * 录制器在段收尾时才需要一张完整的账。上游计数器全是 AtomicLong（相机线程、GL 线程、
 * 泵线程各写各格），热路径零分配、零字符串拼接；只在段收尾 snapshot() 一次。
 * OHOS 侧先按单线程事件循环直译（见文件头平台差异），引擎接入时再评估线程归属。
 *
 * 口径警示：
 * - cameraStarted 数的是重复请求（预览/录制）的 onCaptureStarted 回调；会话内单发 capture
 *   刻意传 null 回调，不计；onCaptureStarted 含"帧开始曝光"语义，可能领先于实际出帧，
 *   故只作到达上界；
 * - glCallbacks 是 SurfaceTexture 的 onFrameAvailable；DIRECT 路的 GL 只做预览、不喂编码器，
 *   这两个数字在 DIRECT 下只描述预览链；
 * - encoderSwapOk 只数换到编码面的 eglSwapBuffers，窗口面不计；
 * - 计数不得进入 PTS 计算——防止"观测值顺手参与打戳"这类退化。
 */
export class FrameLedger {
  private static cameraStarted: number = 0;
  private static glCallbacks: number = 0;
  private static glConsumed: number = 0;
  private static encoderSwapOk: number = 0;
  private static encoderSwapFail: number = 0;
  private static arcDropped: number = 0;

  /** 相机 onCaptureStarted 帧到达（每拍一次自增，无分配） */
  static onCameraStarted(): void {
    FrameLedger.cameraStarted++;
  }

  /** SurfaceTexture 回调（相机线程→GL handler；newFrames 之外单独计数，不改其语义） */
  static onGlCallback(): void {
    FrameLedger.glCallbacks++;
  }

  /** updateTexImage() 成功一次（GL 线程） */
  static onGlConsumed(): void {
    FrameLedger.glConsumed++;
  }

  /** 编码面 eglSwapBuffers 结果（成功/失败分开计；窗口面 swap 不调用本函数） */
  static onEncoderSwap(ok: boolean): void {
    if (ok) {
      FrameLedger.encoderSwapOk++;
    } else {
      FrameLedger.encoderSwapFail++;
    }
  }

  /** 在线抽帧规则判掉一帧（每一枚被 ArcKeepRule 判 drop 的源帧 +1） */
  static onArcDrop(): void {
    FrameLedger.arcDropped++;
  }

  /**
   * 一次快照（六格逐个读，不是一组事务：只用于段收尾观测，跨线程的极短窗口可接受）。
   * 每次段收尾调用一次，分配一个快照对象，不在逐帧路径上。
   */
  static snapshot(): FrameCounterSnapshot {
    return new FrameCounterSnapshot(
      FrameLedger.cameraStarted,
      FrameLedger.glCallbacks,
      FrameLedger.glConsumed,
      FrameLedger.encoderSwapOk,
      FrameLedger.encoderSwapFail,
      FrameLedger.arcDropped
    );
  }
}

/** FrameLedger 的只读快照；字段口径见类注释 */
export class FrameCounterSnapshot {
  cameraStarted: number;
  glCallbacks: number;
  glConsumed: number;
  encoderSwapOk: number;
  encoderSwapFail: number;
  arcDropped: number;

  constructor(
    cameraStarted: number,
    glCallbacks: number,
    glConsumed: number,
    encoderSwapOk: number,
    encoderSwapFail: number,
    arcDropped: number
  ) {
    this.cameraStarted = cameraStarted;
    this.glCallbacks = glCallbacks;
    this.glConsumed = glConsumed;
    this.encoderSwapOk = encoderSwapOk;
    this.encoderSwapFail = encoderSwapFail;
    this.arcDropped = arcDropped;
  }
}

/**
 * 段收尾帧账（录制器把跨组件快照与段内本账合成一张，交给 frameLedgerLine 成文）。
 *
 * preMuxBuffered/preMuxDropped 是「muxer 启动前」的账：第 1 笔先按"被 release 的样本数"
 * 记 preMuxDropped；第 2 笔起前者=成功补写数、后者=缓冲溢出/未启动的丢失数。
 */
export class FrameLedgerReport {
  partIndex: number;
  gpu: boolean;
  durationMs: number;
  cameraFrames: number;
  glCallbacks: number;
  glConsumed: number;
  encoderSwaps: number;
  swapFails: number;
  muxSamples: number;
  preMuxBuffered: number;
  preMuxDropped: number;
  arcDrops: number;
  rotateLostMs: number;
  rotateFramesUpper: number;

  constructor(
    partIndex: number,
    gpu: boolean,
    durationMs: number,
    cameraFrames: number,
    glCallbacks: number,
    glConsumed: number,
    encoderSwaps: number,
    swapFails: number,
    muxSamples: number,
    preMuxBuffered: number,
    preMuxDropped: number,
    arcDrops: number,
    rotateLostMs: number,
    rotateFramesUpper: number
  ) {
    this.partIndex = partIndex;
    this.gpu = gpu;
    this.durationMs = durationMs;
    this.cameraFrames = cameraFrames;
    this.glCallbacks = glCallbacks;
    this.glConsumed = glConsumed;
    this.encoderSwaps = encoderSwaps;
    this.swapFails = swapFails;
    this.muxSamples = muxSamples;
    this.preMuxBuffered = preMuxBuffered;
    this.preMuxDropped = preMuxDropped;
    this.arcDrops = arcDrops;
    this.rotateLostMs = rotateLostMs;
    this.rotateFramesUpper = rotateFramesUpper;
  }
}

/** 在线合帧数 = SurfaceTexture 回调数 − updateTexImage 次数（负数按 0 夹紧，快照非事务） */
export function frameMerged(callbacks: number, consumed: number): number {
  return Math.max(0, callbacks - consumed);
}

/** 厘帧/秒（整数，避免浮点格式化踩 locale）；时长非正 ⇒ 0 */
export function frameCentiFps(frames: number, durationMs: number): number {
  if (frames <= 0 || durationMs <= 0) {
    return 0;
  }
  return Math.floor(frames * 100_000 / durationMs);
}

/** 厘帧/秒的两位小数文本（纯整数拼装） */
export function frameCentiText(centi: number): string {
  const frac: number = Math.abs(centi % 100);
  const fracText: string = frac < 10 ? `0${frac}` : `${frac}`;
  return `${Math.trunc(centi / 100)}.${fracText}`;
}

/**
 * 逐级差值（"相机给了多少 → GL 收了多少 → swap 多少 → muxer 多少"的桥）：
 * 每段只回答"这一级比上一级少了多少"，负数按 0 夹紧（快照非事务 / DIRECT 路 GL 只做预览）。
 */
export class FrameStageDrops {
  cameraToGl: number;
  glToSwap: number;
  swapToMux: number;

  constructor(cameraToGl: number, glToSwap: number, swapToMux: number) {
    this.cameraToGl = cameraToGl;
    this.glToSwap = glToSwap;
    this.swapToMux = swapToMux;
  }
}

export function frameStageDrops(report: FrameLedgerReport): FrameStageDrops {
  return new FrameStageDrops(
    Math.max(0, report.cameraFrames - report.glCallbacks),
    Math.max(0, report.glConsumed - report.encoderSwaps),
    Math.max(0, report.encoderSwaps - report.muxSamples)
  );
}

/**
 * 段收尾独立一行帧账（中文，字段名清晰；不并入既有 stampObsLine 与段尾行，保持向后可读）。
 *
 * 这是一座"计划→行为"桥：调用方把 FrameLedgerReport 原样交进来，成文只做拼装与四级差值，
 * 所以测试可以断言"每个数字真的落在那一行里"，不会出现"删掉一个字段测试仍全绿"的假绿。
 */
export function frameLedgerLine(report: FrameLedgerReport): string {
  const merged: number = frameMerged(report.glCallbacks, report.glConsumed);
  const stage: FrameStageDrops = frameStageDrops(report);
  const cameraFps: string = frameCentiText(frameCentiFps(report.cameraFrames, report.durationMs));
  const muxFps: string = frameCentiText(frameCentiFps(report.muxSamples, report.durationMs));
  const path: string = report.gpu ? 'GPU' : 'DIRECT(GL仅预览)';
  // DIRECT 路没有编码 swap（相机面直连编码器），"GL→swap / swap→mux" 两级差没有语义：
  // 打印数字会被读成"丢了这么多帧"，这里显式写不适用。
  const stageText: string = report.gpu
    ? `逐级差=相机→GL ${stage.cameraToGl}/GL→swap ${stage.glToSwap}/swap→mux ${stage.swapToMux}`
    : `逐级差=相机→GL ${stage.cameraToGl}/GL→swap 不适用(DIRECT无编码swap)/swap→mux 不适用`;
  return `帧账 part=${report.partIndex} 路径=${path} 墙钟=${report.durationMs}ms ` +
    `相机到达=${report.cameraFrames} GL回调=${report.glCallbacks} GL消费=${report.glConsumed} ` +
    `GL合帧=${merged} 编码swap=${report.encoderSwaps} swap失败=${report.swapFails} ` +
    `muxer视频=${report.muxSamples} 起录补写=${report.preMuxBuffered} 起录丢弃=${report.preMuxDropped} ` +
    `有意抽帧=${report.arcDrops} ` +
    `换段丢=${report.rotateLostMs}ms/${report.rotateFramesUpper}帧(上界) ` +
    stageText + ' ' +
    `实测相机=${cameraFps}fps 成片=${muxFps}fps`;
}

/**
 * 会话收尾的段清单摘要（与 `.drops.json` 的 segments 同源：段序号 / 全局首帧 / 段内帧数 /
 * 该段之前换段腿丢的 ms）。只做拼装，供诊断导出正文使用；空表返回空串（旧行为零变化）。
 */
export function frameSessionSummary(videoSamples: Array<number>, lostMs: Array<number>): string {
  if (videoSamples.length === 0) {
    return '';
  }
  let sb: string = `会话段清单(与.drops.json同源) 段数=${videoSamples.length}`;
  let first: number = 0;
  for (let i = 0; i < videoSamples.length; i++) {
    const count: number = videoSamples[i];
    const lost: number = i < lostMs.length ? lostMs[i] : 0; // 对应 getOrNull(i) ?: 0L
    sb += ` [段${i} first=${first} count=${count} lost=${lost}ms]`;
    first += count;
  }
  return sb;
}
