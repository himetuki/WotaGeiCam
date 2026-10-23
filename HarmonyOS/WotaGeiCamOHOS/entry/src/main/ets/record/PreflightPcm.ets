/**
 * 预校准用的单路 PCM 累加器（直译上游 record/PreflightPcm.kt 的 PreflightMonoPcm，
 * 对齐上游 e950328；纯逻辑，零平台 import）：
 * - 交织 PCM16 → 单声道 float（声道均值）；
 * - 以**两路共同的时间原点** basePtsUs 落位，PTS 空洞补零（两路 feeder 启动/读缓冲差异
 *   不再被"各自首包归零"吃掉）；
 * - 锁存是否见过非零样本（600ms 双静音早退判据）。
 *
 * 时间落位公式：`targetFrames = (pktPtsUs - basePtsUs) * sampleRate / 1_000_000`。
 * 包内容紧接在 target 之后写入；比目标短则补零，比目标长则顺延（不覆盖已有样本）。
 *
 * 字节序登记：PCM16 按小端解释（与 player/PcmDecode.ets 同口径）；上游 ByteArray → Uint8Array。
 * 浮点口径：Kotlin Float 累加器 → JS number（double）累加后写 Float32Array，峰值锁存同口径。
 */

/** 单路 PCM 累加器（交织 PCM16 → 单声道 float，共同 t0 落位） */
export class PreflightMonoPcm {
  private readonly sampleRate: number;
  private readonly basePtsUs: number;
  private readonly ch: number;
  private readonly frameBytes: number;
  private data: Float32Array = new Float32Array(1 << 14);
  private size: number = 0;
  private peak: number = 0;

  constructor(sampleRate: number, channels: number, basePtsUs: number) {
    this.sampleRate = sampleRate;
    this.basePtsUs = basePtsUs;
    this.ch = Math.max(1, channels);
    this.frameBytes = this.ch * 2;
  }

  /** 已落位的帧数 */
  frameCount(): number {
    return this.size;
  }

  /** 追加一包交织 PCM16（以两路共同 t0 落位；早于 t0 的包钳到 0，不带负前导） */
  append(pcm: Uint8Array, ptsUs: number): void {
    const target: number =
      Math.max(0, Math.trunc((ptsUs - this.basePtsUs) * this.sampleRate / 1000000));
    if (target > this.size) {
      const pad: number = target - this.size;
      this.ensure(pad);
      for (let i: number = 0; i < pad; i++) {
        this.data[this.size] = 0;
        this.size++;
      }
    }
    const frames: number = Math.floor(pcm.length / this.frameBytes);
    if (frames <= 0) {
      return;
    }
    this.ensure(frames);
    for (let f: number = 0; f < frames; f++) {
      let sum: number = 0;
      for (let c: number = 0; c < this.ch; c++) {
        const i: number = (f * this.ch + c) * 2;
        let v: number = pcm[i] | (pcm[i + 1] << 8);
        if ((v & 0x8000) !== 0) {
          v = v - 0x10000;
        }
        sum += v;
      }
      const sample: number = sum / (this.ch * 32768);
      this.data[this.size] = sample;
      this.size++;
      const a: number = Math.abs(sample);
      if (a > this.peak) {
        this.peak = a;
      }
    }
  }

  /** 已累计时长（ms；Kotlin Long 除法截断 → Math.trunc） */
  durationMs(): number {
    return Math.trunc(this.size * 1000 / Math.max(1, this.sampleRate));
  }

  /** 是否从未见过非零样本（峰值绝对值 ≤ 1e-6） */
  isSilent(): boolean {
    return this.peak <= 1e-6;
  }

  /** 导出已累计的单声道样本（副本） */
  toArray(): Float32Array {
    return this.data.slice(0, this.size);
  }

  private ensure(n: number): void {
    if (this.size + n <= this.data.length) {
      return;
    }
    let cap: number = this.data.length;
    while (cap < this.size + n) {
      cap *= 2;
    }
    const next: Float32Array = new Float32Array(cap);
    for (let i: number = 0; i < this.size; i++) {
      next[i] = this.data[i];
    }
    this.data = next;
  }
}
