/**
 * SHA-256（FIPS 180-4）的纯 ArkTS 实现——供更新包端到端校验用，**零平台 import**。
 *
 * 为什么不用 @kit.CryptoArchitectureKit：更新校验的分支与判据要能在 test/pure 里用
 * NIST 标准向量钉住（上游 UpdateSha256Test 同款），平台摘要 API 在 pure-test 里只有惰性桩。
 * 实现按块流式吃数据（update）以支持大文件分块读盘，不把整个包读进内存。
 */

/** 32 位循环右移 */
function rotr(x: number, n: number): number {
  return ((x >>> n) | (x << (32 - n))) >>> 0;
}

/** SHA-256 轮常量（前 64 个素数立方根小数部分前 32 位） */
const K: Array<number> = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
];

/** 摘要状态（可复用同一实例多次 update，最后 digestHex 一次） */
export class Sha256 {
  private h0: number = 0x6a09e667;
  private h1: number = 0xbb67ae85;
  private h2: number = 0x3c6ef372;
  private h3: number = 0xa54ff53a;
  private h4: number = 0x510e527f;
  private h5: number = 0x9b05688c;
  private h6: number = 0x1f83d9ab;
  private h7: number = 0x5be0cd19;

  /** 未满 64 字节的尾巴 */
  private tail: Uint8Array = new Uint8Array(64);
  private tailLen: number = 0;
  /** 累计字节数（比特长度按 ×8 现算，避免 32 位溢出） */
  private totalBytes: number = 0;
  private finished: boolean = false;

  /** 追加一段数据（可多次调用；内部按 64 字节块压缩） */
  update(data: Uint8Array): void {
    if (this.finished) {
      throw new Error('Sha256: digest 之后不可再 update');
    }
    this.totalBytes += data.length;
    let i: number = 0;
    if (this.tailLen > 0) {
      const need: number = 64 - this.tailLen;
      const take: number = Math.min(need, data.length);
      this.tail.set(data.subarray(0, take), this.tailLen);
      this.tailLen += take;
      i = take;
      if (this.tailLen === 64) {
        this.compress(this.tail, 0);
        this.tailLen = 0;
      }
    }
    while (i + 64 <= data.length) {
      this.compress(data, i);
      i += 64;
    }
    if (i < data.length) {
      this.tail.set(data.subarray(i), 0);
      this.tailLen = data.length - i;
    }
  }

  /** 取摘要（小写 hex）；调用后实例封口 */
  digestHex(): string {
    if (!this.finished) {
      this.finish();
    }
    const words: Array<number> = [
      this.h0, this.h1, this.h2, this.h3, this.h4, this.h5, this.h6, this.h7
    ];
    let out: string = '';
    for (const w of words) {
      out += Sha256.byteHex((w >>> 24) & 0xff);
      out += Sha256.byteHex((w >>> 16) & 0xff);
      out += Sha256.byteHex((w >>> 8) & 0xff);
      out += Sha256.byteHex(w & 0xff);
    }
    return out;
  }

  /** 一次性摘要（小数据；大文件用实例 update 分块） */
  static hexOf(data: Uint8Array): string {
    const h: Sha256 = new Sha256();
    h.update(data);
    return h.digestHex();
  }

  private static byteHex(b: number): string {
    const s: string = b.toString(16);
    return s.length === 1 ? '0' + s : s;
  }

  /** 填充：0x80 + 若干 0 + 64 位大端比特长度（this.totalBytes*8，按 hi/lo 两段写） */
  private finish(): void {
    const bitHi: number = Math.floor(this.totalBytes / 536870912); // floor(bytes / 2^29)
    const bitLo: number = (this.totalBytes % 536870912) * 8;
    // 最坏形态：tailLen=63 → 0x80 后补到 120，再写 8 字节长度 = 128（两个块）
    const pad: Uint8Array = new Uint8Array(128);
    pad.set(this.tail.subarray(0, this.tailLen), 0);
    let len: number = this.tailLen;
    pad[len] = 0x80;
    len++;
    // 凑到 56 (mod 64)：不足则补零，正好留出 8 字节长度
    const zerosTo56: number = len <= 56 ? 56 - len : 120 - len;
    len += zerosTo56;
    pad[len] = (bitHi >>> 24) & 0xff;
    pad[len + 1] = (bitHi >>> 16) & 0xff;
    pad[len + 2] = (bitHi >>> 8) & 0xff;
    pad[len + 3] = bitHi & 0xff;
    pad[len + 4] = (bitLo >>> 24) & 0xff;
    pad[len + 5] = (bitLo >>> 16) & 0xff;
    pad[len + 6] = (bitLo >>> 8) & 0xff;
    pad[len + 7] = bitLo & 0xff;
    const total: number = len + 8;
    for (let off = 0; off < total; off += 64) {
      this.compress(pad, off);
    }
    this.tailLen = 0;
    this.finished = true;
  }

  private compress(block: Uint8Array, off: number): void {
    const w: Uint32Array = new Uint32Array(64);
    for (let i = 0; i < 16; i++) {
      const j: number = off + i * 4;
      w[i] = (((block[j] << 24) | (block[j + 1] << 16) | (block[j + 2] << 8) | block[j + 3]) >>> 0);
    }
    for (let i = 16; i < 64; i++) {
      const x: number = w[i - 15];
      const y: number = w[i - 2];
      const s0: number = (rotr(x, 7) ^ rotr(x, 18) ^ (x >>> 3)) >>> 0;
      const s1: number = (rotr(y, 17) ^ rotr(y, 19) ^ (y >>> 10)) >>> 0;
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }
    let a: number = this.h0;
    let b: number = this.h1;
    let c: number = this.h2;
    let d: number = this.h3;
    let e: number = this.h4;
    let f: number = this.h5;
    let g: number = this.h6;
    let h: number = this.h7;
    for (let i = 0; i < 64; i++) {
      const S1: number = (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) >>> 0;
      const ch: number = ((e & f) ^ (~e & g)) >>> 0;
      const t1: number = (h + S1 + ch + K[i] + w[i]) >>> 0;
      const S0: number = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) >>> 0;
      const maj: number = ((a & b) ^ (a & c) ^ (b & c)) >>> 0;
      const t2: number = (S0 + maj) >>> 0;
      h = g;
      g = f;
      f = e;
      e = (d + t1) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (t1 + t2) >>> 0;
    }
    this.h0 = (this.h0 + a) >>> 0;
    this.h1 = (this.h1 + b) >>> 0;
    this.h2 = (this.h2 + c) >>> 0;
    this.h3 = (this.h3 + d) >>> 0;
    this.h4 = (this.h4 + e) >>> 0;
    this.h5 = (this.h5 + f) >>> 0;
    this.h6 = (this.h6 + g) >>> 0;
    this.h7 = (this.h7 + h) >>> 0;
  }
}
