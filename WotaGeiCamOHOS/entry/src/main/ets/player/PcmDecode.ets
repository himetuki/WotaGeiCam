/**
 * PCM 解码核心（直译上游 player/AudioSync.kt 的 GrowableFloats 与 PTS 落位，对齐上游 38d712a）。
 *
 * 上游把「解码输出 → 交织 float 缓冲 → 按 PTS 补零落位 → 降混单声道」写在 AudioSync 的私有
 * GrowableFloats 里；本侧抽成可测纯核心：
 * - sampleIndexForPts：`roundToInt((pts - base) × rate × ch / 1e6)`（floor(x+0.5)）；
 * - PcmAccumulator.alignTo：流内空洞补静音（≤ minPadSamples 的取整抖动跳过）；
 * - appendPcm：S16LE / S24LE_PACKED / F32LE 逐字节解码（符号扩展 / 位模式重解释）；
 * - toMono：交织多声道取均值。
 *
 * 字节序登记：按小端解释（OHOS/Android PCM 与 IEEE754 内存布局均为小端；大端平台不在目标范围）。
 * 浮点口径：Kotlin 用 Float 存储，本侧 Float32Array 写入即按 float32 舍入；中间累加用 number。
 *
 * T26 边界：AVDemuxer + AVCodec 的音频解码与容器轨序选择留 T27；本文件只吃已解码字节。
 */

/** PCM 编码（数值无关；对应上游 AudioFormat.ENCODING_* 的三档） */
export enum PcmEncoding {
  S16LE = 'S16LE',
  S24LE_PACKED = 'S24LE_PACKED',
  F32LE = 'F32LE'
}

/**
 * PTS 落位样本下标（交织声道样本数，非帧号）：相对首样本基准，四舍五入（floor(x+0.5)）。
 * 上游口径：`((pts - baseUs) * rate * ch / 1_000_000.0).roundToInt()`。
 */
export function sampleIndexForPts(
  ptsUs: number,
  baseUs: number,
  sampleRate: number,
  channels: number
): number {
  const ch: number = Math.max(1, channels);
  const rate: number = Math.max(1, sampleRate);
  return Math.floor(((ptsUs - baseUs) * rate * ch) / 1_000_000 + 0.5);
}

/** 交织 PCM 可变长缓冲（Float32Array 存储；空洞补零，PTS 落位） */
export class PcmAccumulator {
  private data: Float32Array = new Float32Array(65536);
  private count: number = 0;

  /** 已写入的交织样本数 */
  sampleCount(): number {
    return this.count;
  }

  /** 读取第 i 个交织样本（越界返回 0；测试与诊断用） */
  at(index: number): number {
    if (index < 0 || index >= this.count) {
      return 0;
    }
    return this.data[index];
  }

  private ensure(extra: number): void {
    const need: number = this.count + extra;
    if (need <= this.data.length) {
      return;
    }
    let size: number = this.data.length;
    while (size < need) {
      size *= 2;
    }
    const next: Float32Array = new Float32Array(size);
    for (let i: number = 0; i < this.count; i++) {
      next[i] = this.data[i];
    }
    this.data = next;
  }

  /**
   * 按 PTS 落位：流内空洞补静音，让数组下标表示「相对首样本的时间」而不是解码顺序。
   * 亚毫秒的取整抖动跳过（gap ≤ minPadSamples 不补）——每帧按绝对 PTS 重新标定，抖动不累积。
   */
  alignTo(sampleIndex: number, minPadSamples: number): void {
    const gap: number = sampleIndex - this.count;
    if (gap <= minPadSamples) {
      return;
    }
    this.ensure(gap);
    for (let i: number = 0; i < gap; i++) {
      this.data[this.count] = 0;
      this.count++;
    }
  }

  /** 追加交织 PCM 字节（按 encoding 解码到 float） */
  appendPcm(bytes: Uint8Array, encoding: PcmEncoding): void {
    if (encoding === PcmEncoding.F32LE) {
      const n: number = Math.floor(bytes.length / 4);
      this.ensure(n);
      const buf: ArrayBuffer = new ArrayBuffer(4);
      const u8: Uint8Array = new Uint8Array(buf);
      const f32: Float32Array = new Float32Array(buf);
      for (let i: number = 0; i < n; i++) {
        u8[0] = bytes[i * 4];
        u8[1] = bytes[i * 4 + 1];
        u8[2] = bytes[i * 4 + 2];
        u8[3] = bytes[i * 4 + 3];
        this.data[this.count] = f32[0];
        this.count++;
      }
      return;
    }
    if (encoding === PcmEncoding.S24LE_PACKED) {
      const n: number = Math.floor(bytes.length / 3);
      this.ensure(n);
      for (let i: number = 0; i < n; i++) {
        let v: number = bytes[i * 3] | (bytes[i * 3 + 1] << 8) | (bytes[i * 3 + 2] << 16);
        if ((v & 0x800000) !== 0) {
          v = v - 0x1000000; // 24 位符号扩展到负值
        }
        this.data[this.count] = v / 8388608;
        this.count++;
      }
      return;
    }
    // S16LE（默认）
    const n: number = Math.floor(bytes.length / 2);
    this.ensure(n);
    for (let i: number = 0; i < n; i++) {
      let v: number = bytes[i * 2] | (bytes[i * 2 + 1] << 8);
      if ((v & 0x8000) !== 0) {
        v = v - 0x10000;
      }
      this.data[this.count] = v / 32768;
      this.count++;
    }
  }

  /** 交织多声道 → 单声道（均值）；channels ≤ 1 时返回已写样本的副本 */
  toMono(channels: number): Float32Array {
    const ch: number = Math.max(1, channels);
    if (ch === 1) {
      return this.data.slice(0, this.count);
    }
    const frames: number = Math.floor(this.count / ch);
    const out: Float32Array = new Float32Array(frames);
    for (let f: number = 0; f < frames; f++) {
      let sum: number = 0;
      for (let c: number = 0; c < ch; c++) {
        sum += this.data[f * ch + c];
      }
      out[f] = sum / ch;
    }
    return out;
  }
}
