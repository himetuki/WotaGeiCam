/**
 * 基于音频的自动同步 — DSP 纯函数层（直译上游 player/AudioSync.kt，对齐上游 38d712a）。
 *
 * 上游把「解码 + DSP + 结果模型」放在一个 object 里；本侧拆成两段：
 * - 本文件 = **纯逻辑 DSP**（不 import 任何平台 Kit）：重采样 / 包络 / 粗对齐 / radix-2 FFT /
 *   精修与置信度。输入是已经解码成单声道 PCM 的 Float32Array，输出偏移毫秒；
 * - 平台解码适配（MediaExtractor/MediaCodec → 8kHz PCM，含按容器轨序取第 N 条音轨）留待
 *   内录/自动对齐批（T25+），届时由 Kit 侧产 PCM 后调本文件。
 *
 * 偏移符号约定（与对比页一致）：**rightPos = leftPos + offsetMs**，即左视频第 0 毫秒对应
 * 右视频的 offsetMs。
 *
 * 浮点口径（登记）：
 * - Kotlin FloatArray → Float32Array（数组存储按 float32 舍入）；
 * - 蝶形/累加器中间量用 JS number（double）计算后写回 Float32Array（Kotlin 为 Float 逐次
 *   rounding），差异远小于测试容差 1e-3；不追求逐位一致；
 * - Kotlin Long 除法截断 → Math.trunc（本文件所有时间量均为非负或整毫秒）。
 */

/** 精修结果：偏移毫秒 + 置信度（主峰/次峰比） */
export class RefineResult {
  readonly offsetMs: number;
  readonly confidence: number;

  constructor(offsetMs: number, confidence: number) {
    this.offsetMs = offsetMs;
    this.confidence = confidence;
  }
}

/** 区间 [from, until)（内部辅助；上游 Kotlin Pair<Int,Int>） */
class Span {
  readonly from: number;
  readonly until: number;

  constructor(from: number, until: number) {
    this.from = from;
    this.until = until;
  }
}

export class AudioSync {
  /** 目标采样率：与上游 TrackSync「至少 1 秒 PCM」判据同口径的单一真源 */
  static readonly TARGET_SR: number = 8000;
  static readonly MAX_SECONDS: number = 30;
  static readonly ENV_WIN_MS: number = 10;
  static readonly SEARCH_MS: number = 10000;
  static readonly REFINE_MS: number = 50;
  static readonly PEAK_GUARD_MS: number = 150;
  /** 低置信度门槛（主峰/次峰比）：TrackSync 同口径复用 */
  static readonly MIN_CONFIDENCE: number = 1.5;

  /**
   * 线性插值重采样 + 截断到 maxSeconds。
   * 输出帧数 = 源帧数×dstRate/srcRate（整段重采样的目标帧数）再被 maxSeconds 封顶——
   * 不能拿 src.length（源帧数）与 dstRate·maxSeconds（目标帧数）直接取 min（单位混用）。
   */
  static resample(src: Float32Array, srcRate: number, dstRate: number, maxSeconds: number): Float32Array {
    const rate: number = srcRate <= 0 ? dstRate : srcRate;
    const byLength: number = Math.trunc(src.length * dstRate / rate);
    const bySeconds: number = dstRate * maxSeconds;
    const limitFrames: number = Math.max(0, Math.min(byLength, bySeconds));
    if (rate === dstRate) {
      return src.slice(0, limitFrames);
    }
    const ratio: number = rate / dstRate;
    const out: Float32Array = new Float32Array(limitFrames);
    for (let i: number = 0; i < limitFrames; i++) {
      const pos: number = i * ratio;
      const idx: number = Math.floor(pos);
      const frac: number = pos - idx;
      const a: number = idx >= 0 && idx < src.length ? src[idx] : 0;
      const b: number = idx + 1 >= 0 && idx + 1 < src.length ? src[idx + 1] : a;
      out[i] = a + (b - a) * frac;
    }
    return out;
  }

  /** 10ms 窗 RMS，去均值后归一化到 0..1，得到 100 点/秒的包络（8kHz 默认口径） */
  static envelope(pcm: Float32Array): Float32Array {
    return AudioSync.envelopeAt(pcm, AudioSync.TARGET_SR);
  }

  private static envelopeAt(pcm: Float32Array, sr: number): Float32Array {
    const win: number = Math.max(1, Math.trunc(sr * AudioSync.ENV_WIN_MS / 1000));
    const n: number = Math.trunc(pcm.length / win);
    if (n <= 0) {
      return new Float32Array(1);
    }
    const rms: Float32Array = new Float32Array(n);
    for (let k: number = 0; k < n; k++) {
      let sum: number = 0;
      const base: number = k * win;
      for (let j: number = 0; j < win; j++) {
        const v: number = pcm[base + j];
        sum += v * v;
      }
      rms[k] = Math.sqrt(sum / win);
    }
    let mean: number = 0;
    for (let k: number = 0; k < n; k++) {
      mean += rms[k];
    }
    mean /= n;
    let maxV: number = 0;
    for (let k: number = 0; k < n; k++) {
      const d: number = rms[k] - mean;
      rms[k] = d > 0 ? d : 0;
      if (rms[k] > maxV) {
        maxV = rms[k];
      }
    }
    if (maxV > 0) {
      for (let k: number = 0; k < n; k++) {
        rms[k] = rms[k] / maxV;
      }
    }
    return rms;
  }

  /**
   * 粗偏移（ms）：在 ±SEARCH_MS 内求归一化互相关最大。
   * 正偏移含义见文件头：left(t) ≈ right(t + offset)。
   */
  static coarseAlign(envL: Float32Array, envR: Float32Array): number {
    const stepMs: number = AudioSync.ENV_WIN_MS;
    const maxLag: number = Math.trunc(AudioSync.SEARCH_MS / stepMs);
    const pl: Float32Array = AudioSync.prefixEnergy(envL);
    const pr: Float32Array = AudioSync.prefixEnergy(envR);
    let bestLag: number = 0;
    let bestScore: number = -Number.MAX_VALUE;
    for (let lag: number = -maxLag; lag <= maxLag; lag++) {
      const span: Span | undefined = AudioSync.overlapRange(envL.length, envR.length, lag);
      if (span === undefined) {
        continue;
      }
      const r: number = AudioSync.correlate(envL, envR, span.from, span.until, lag);
      const eL: number = AudioSync.rangeEnergy(pl, span.from, span.until);
      const eR: number = AudioSync.rangeEnergy(pr, span.from + lag, span.until + lag);
      const denom: number = Math.sqrt(eL * eR);
      const score: number = denom > 1e-6 ? r / denom : 0;
      if (score > bestScore) {
        bestScore = score;
        bestLag = lag;
      }
    }
    return bestLag * stepMs;
  }

  private static correlate(a: Float32Array, b: Float32Array, from: number, until: number, lag: number): number {
    let sum: number = 0;
    let i: number = from;
    while (i < until) {
      sum += a[i] * b[i + lag];
      i++;
    }
    return sum;
  }

  /**
   * 精修：1kHz 带内（带通由低通近似）做 FFT 互相关，在粗偏移 ±REFINE_MS 内按 1ms 步进取峰。
   * 置信度 = 主峰 / 次峰（次峰取主峰 ±PEAK_GUARD_MS 之外、搜索窗内的最大值）。
   */
  static refine(pcmL: Float32Array, pcmR: Float32Array, coarseMs: number): RefineResult {
    const low: Float32Array = AudioSync.lowPass1k(pcmL);
    const high: Float32Array = AudioSync.lowPass1k(pcmR);
    const n: number = AudioSync.nextPowerOfTwo(low.length + high.length);
    const reL: Float32Array = new Float32Array(n);
    const imL: Float32Array = new Float32Array(n);
    const reR: Float32Array = new Float32Array(n);
    const imR: Float32Array = new Float32Array(n);
    for (let i: number = 0; i < low.length; i++) {
      reL[i] = low[i];
    }
    for (let i: number = 0; i < high.length; i++) {
      reR[i] = high[i];
    }
    AudioSync.meanRemove(reL, low.length);
    AudioSync.meanRemove(reR, high.length);

    AudioSync.fft(reL, imL);
    AudioSync.fft(reR, imR);
    // 互相关：conj(FFT(L)) * FFT(R)
    for (let i: number = 0; i < n; i++) {
      const ar: number = reL[i];
      const ai: number = imL[i];
      const br: number = reR[i];
      const bi: number = imR[i];
      // (ar - j·ai)(br + j·bi) = (ar·br + ai·bi) + j(ar·bi - ai·br)
      reL[i] = ar * br + ai * bi;
      imL[i] = ar * bi - ai * br;
    }
    AudioSync.inverseFft(reL, imL);

    const corr: Float32Array = new Float32Array(n);
    for (let i: number = 0; i < n; i++) {
      corr[i] = reL[i] / n;
    }
    const pl: Float32Array = AudioSync.prefixEnergy(low);
    const pr: Float32Array = AudioSync.prefixEnergy(high);
    const lo: number = Math.max(coarseMs - AudioSync.REFINE_MS, -AudioSync.SEARCH_MS);
    const hi: number = Math.min(coarseMs + AudioSync.REFINE_MS, AudioSync.SEARCH_MS);
    const maxLagSamples: number = Math.trunc(AudioSync.SEARCH_MS * AudioSync.TARGET_SR / 1000);

    // 候选：1ms 步进的 lag（样本数 = lag * SR/1000）
    const candLags: Array<number> = [];
    const candScores: Array<number> = [];
    let ms: number = lo;
    while (ms <= hi) {
      const lag: number = Math.trunc(ms * AudioSync.TARGET_SR / 1000);
      if (Math.abs(lag) <= maxLagSamples) {
        const s: number | undefined =
          AudioSync.score(corr, pl, pr, lag, low.length, high.length, n);
        if (s !== undefined) {
          candLags.push(lag);
          candScores.push(s);
        }
      }
      ms += 1;
    }
    if (candLags.length === 0) {
      return new RefineResult(coarseMs, 0);
    }

    let bestIdx: number = 0;
    for (let i: number = 1; i < candScores.length; i++) {
      if (candScores[i] > candScores[bestIdx]) {
        bestIdx = i;
      }
    }
    const bestLag: number = candLags[bestIdx];
    const bestScore: number = candScores[bestIdx];
    // 次峰：搜索窗内排除主峰 ±PEAK_GUARD_MS
    const guard: number = Math.trunc(AudioSync.PEAK_GUARD_MS * AudioSync.TARGET_SR / 1000);
    let second: number = -Number.MAX_VALUE;
    let scan: number = -AudioSync.SEARCH_MS;
    while (scan <= AudioSync.SEARCH_MS) {
      const lag: number = Math.trunc(scan * AudioSync.TARGET_SR / 1000);
      if (Math.abs(lag - bestLag) > guard && Math.abs(lag) <= maxLagSamples) {
        const s: number | undefined =
          AudioSync.score(corr, pl, pr, lag, low.length, high.length, n);
        if (s !== undefined && s > second) {
          second = s;
        }
      }
      scan += 4; // 4ms 粒度扫次峰，够精且省时间
    }
    let confidence: number = 0;
    if (second <= 0.02 || second <= 0) {
      confidence = 99;
    } else {
      confidence = Math.max(0, Math.min(99, bestScore / second));
    }
    return new RefineResult(Math.trunc(bestLag * 1000 / AudioSync.TARGET_SR), confidence);
  }

  /** 归一化互相关：直接取 FFT 相关在 lag 处的值，再按重叠窗能量归一 */
  private static score(
    corr: Float32Array,
    pl: Float32Array,
    pr: Float32Array,
    lag: number,
    lenL: number,
    lenR: number,
    n: number
  ): number | undefined {
    const span: Span | undefined = AudioSync.overlapRange(lenL, lenR, lag);
    if (span === undefined) {
      return undefined;
    }
    if (span.until - span.from <= 1) {
      return undefined;
    }
    const index: number = lag >= 0 ? lag : n + lag;
    if (index < 0 || index >= n) {
      return undefined;
    }
    const raw: number = corr[index];
    const eL: number = AudioSync.rangeEnergy(pl, span.from, span.until);
    const eR: number = AudioSync.rangeEnergy(pr, span.from + lag, span.until + lag);
    const denom: number = Math.sqrt(eL * eR);
    return denom > 1e-6 ? raw / denom : undefined;
  }

  private static overlapRange(lenL: number, lenR: number, lag: number): Span | undefined {
    const from: number = Math.max(0, -lag);
    const until: number = Math.min(lenL, lenR - lag);
    if (until - from <= 1) {
      return undefined;
    }
    return new Span(from, until);
  }

  private static prefixEnergy(x: Float32Array): Float32Array {
    const p: Float32Array = new Float32Array(x.length + 1);
    let sum: number = 0;
    for (let i: number = 0; i < x.length; i++) {
      sum += x[i] * x[i];
      p[i + 1] = sum;
    }
    return p;
  }

  private static rangeEnergy(prefix: Float32Array, from: number, until: number): number {
    const a: number = Math.max(0, Math.min(prefix.length - 1, from));
    const b: number = Math.max(0, Math.min(prefix.length - 1, until));
    return b <= a ? 0 : prefix[b] - prefix[a];
  }

  /** ~1kHz 低通（8 点滑动平均，8kHz 下第一零点约 1kHz），抑制高频让对齐更稳 */
  private static lowPass1k(x: Float32Array): Float32Array {
    const win: number = 8;
    const out: Float32Array = new Float32Array(x.length);
    let acc: number = 0;
    for (let i: number = 0; i < x.length; i++) {
      acc += x[i];
      if (i >= win) {
        acc -= x[i - win];
      }
      out[i] = acc / Math.min(win, i + 1);
    }
    return out;
  }

  private static meanRemove(re: Float32Array, len: number): void {
    if (len <= 0) {
      return;
    }
    let sum: number = 0;
    for (let i: number = 0; i < len; i++) {
      sum += re[i];
    }
    const m: number = sum / len;
    for (let i: number = 0; i < len; i++) {
      re[i] -= m;
    }
  }

  private static nextPowerOfTwo(v: number): number {
    let n: number = 1;
    while (n < v) {
      n = n << 1;
    }
    return n;
  }

  /** 迭代 radix-2 FFT（in-place） */
  static fft(re: Float32Array, im: Float32Array): void {
    AudioSync.transform(re, im, false);
  }

  static inverseFft(re: Float32Array, im: Float32Array): void {
    AudioSync.transform(re, im, true);
  }

  private static transform(re: Float32Array, im: Float32Array, inverse: boolean): void {
    const n: number = re.length;
    if (n <= 1) {
      return;
    }
    // 位反转置换
    let j: number = 0;
    for (let i: number = 1; i < n; i++) {
      let bit: number = n >> 1;
      while ((j & bit) !== 0) {
        j = j ^ bit;
        bit = bit >> 1;
      }
      j = j | bit;
      if (i < j) {
        let t: number = re[i];
        re[i] = re[j];
        re[j] = t;
        t = im[i];
        im[i] = im[j];
        im[j] = t;
      }
    }
    let len: number = 2;
    while (len <= n) {
      const ang: number = (inverse ? 2 : -2) * Math.PI / len;
      const wRe: number = Math.cos(ang);
      const wIm: number = Math.sin(ang);
      let start: number = 0;
      while (start < n) {
        let curRe: number = 1;
        let curIm: number = 0;
        const half: number = len / 2;
        for (let k: number = 0; k < half; k++) {
          const a: number = start + k;
          const b: number = a + half;
          const xRe: number = re[b] * curRe - im[b] * curIm;
          const xIm: number = re[b] * curIm + im[b] * curRe;
          re[b] = re[a] - xRe;
          im[b] = im[a] - xIm;
          re[a] += xRe;
          im[a] += xIm;
          const nextRe: number = curRe * wRe - curIm * wIm;
          curIm = curRe * wIm + curIm * wRe;
          curRe = nextRe;
        }
        start += len;
      }
      len = len << 1;
    }
  }
}
