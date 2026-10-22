/**
 * PCM 解码核心（player/PcmDecode.ets）单测——对拍上游 player/AudioSync.kt 的 GrowableFloats
 * 与 PTS 落位（对齐上游 38d712a）。
 *
 * 7 条：PTS 落位与四舍五入 / 补零与门限 / PCM16 符号幅度 / PCM24 符号扩展 / F32LE 位模式 /
 * 立体声降单声道 / 多段 append。期望值一律手写字面量（浮点用容差）。
 *
 * 平台解码（AVDemuxer + AVCodec → 本核心）留 T27。
 */
import {
  PcmAccumulator, PcmEncoding, sampleIndexForPts
} from '../../entry/src/main/ets/player/PcmDecode';

function bytesOf(values: Array<number>): Uint8Array {
  const out: Uint8Array = new Uint8Array(values.length);
  for (let i: number = 0; i < values.length; i++) {
    out[i] = values[i];
  }
  return out;
}

function assertClose(expected: number, actual: number, tol: number, msg: string): void {
  assertTrue(Math.abs(expected - actual) <= tol, `${msg}: expected ${expected}±${tol} got ${actual}`);
}

test('PTS落位 相对首样本基准与四舍五入', (): void => {
  // 0.5s @8k 双声道：500000us × 8000 × 2 / 1e6 = 8000 交织样本
  assertEq(8000, sampleIndexForPts(1500000, 1000000, 8000, 2), 'PTS 相对基准');
  // 100us @8k 单声道 = 0.8 → 1（四舍五入）
  assertEq(1, sampleIndexForPts(1000100, 1000000, 8000, 1), '0.8 → 1');
  // 62us @8k = 0.496 → 0
  assertEq(0, sampleIndexForPts(1000062, 1000000, 8000, 1), '0.496 → 0');
  // 负相对时刻：-100000us @8k = -800（floor(-800+0.5) = -800）
  assertEq(-800, sampleIndexForPts(900000, 1000000, 8000, 1), '负相对时刻');
  // 声道 0 / 采样率 0 按 1 兜底：500000us × 1 × 1 / 1e6 = 0.5 → 1
  assertEq(1, sampleIndexForPts(1500000, 1000000, 0, 0), '非法参数兜底');
});

test('补零与门限 空洞补静音且小抖动跳过', (): void => {
  const acc: PcmAccumulator = new PcmAccumulator();
  acc.alignTo(5, 2);
  assertEq(5, acc.sampleCount(), 'gap=5 > 2 补 5 个零');
  assertEq(0, acc.at(0), '补零值');
  assertEq(0, acc.at(4), '补零末位');
  acc.alignTo(6, 3);
  assertEq(5, acc.sampleCount(), 'gap=1 ≤ 3 不补（亚毫秒抖动）');
  acc.alignTo(10, 2);
  assertEq(10, acc.sampleCount(), 'gap=5 再补');
  acc.alignTo(4, 2);
  assertEq(10, acc.sampleCount(), '回退下标不缩短缓冲');
});

test('PCM16 符号幅度', (): void => {
  const acc: PcmAccumulator = new PcmAccumulator();
  // 小端：0x4000=0.5 / 0xC000=-0.5 / 0x7FFF≈0.99997
  acc.appendPcm(bytesOf([0x00, 0x40, 0x00, 0xC0, 0xFF, 0x7F]), PcmEncoding.S16LE);
  assertEq(3, acc.sampleCount(), '三个样本');
  assertClose(0.5, acc.at(0), 1e-6, '0x4000');
  assertClose(-0.5, acc.at(1), 1e-6, '0xC000');
  assertClose(32767 / 32768, acc.at(2), 1e-6, '0x7FFF');
});

test('PCM24 符号扩展', (): void => {
  const acc: PcmAccumulator = new PcmAccumulator();
  // 0x400000=0.5 / 0xC00000=-0.5（符号扩展）/ 0x7FFFFF≈1
  acc.appendPcm(bytesOf([0x00, 0x00, 0x40, 0x00, 0x00, 0xC0, 0xFF, 0xFF, 0x7F]),
    PcmEncoding.S24LE_PACKED);
  assertEq(3, acc.sampleCount(), '三个样本');
  assertClose(0.5, acc.at(0), 1e-6, '0x400000');
  assertClose(-0.5, acc.at(1), 1e-6, '0xC00000 符号扩展');
  assertClose(8388607 / 8388608, acc.at(2), 1e-6, '0x7FFFFF');
});

test('F32LE 位模式重解释', (): void => {
  const acc: PcmAccumulator = new PcmAccumulator();
  // 0x3F000000=0.5（小端字节 00 00 00 3F）/ 0xBF000000=-0.5 / 0x3F800000=1.0
  acc.appendPcm(bytesOf([0x00, 0x00, 0x00, 0x3F, 0x00, 0x00, 0x00, 0xBF, 0x00, 0x00, 0x80, 0x3F]),
    PcmEncoding.F32LE);
  assertEq(3, acc.sampleCount(), '三个 float');
  assertEq(0.5, acc.at(0), '0x3F000000');
  assertEq(-0.5, acc.at(1), '0xBF000000');
  assertEq(1, acc.at(2), '0x3F800000');
});

test('立体声降单声道 声道均值', (): void => {
  const acc: PcmAccumulator = new PcmAccumulator();
  // F32：0.5/-0.5 → 0；1.0/0.5 → 0.75
  acc.appendPcm(bytesOf([
    0x00, 0x00, 0x00, 0x3F, 0x00, 0x00, 0x00, 0xBF,
    0x00, 0x00, 0x80, 0x3F, 0x00, 0x00, 0x00, 0x3F
  ]), PcmEncoding.F32LE);
  assertEq(4, acc.sampleCount(), '两帧四样本');
  const mono: Float32Array = acc.toMono(2);
  assertEq(2, mono.length, '两帧');
  assertEq(0, mono[0], '0.5 + (-0.5) = 0');
  assertClose(0.75, mono[1], 1e-6, '1.0 + 0.5 = 0.75');
  // 单声道直通（返回副本）
  const mono1: Float32Array = acc.toMono(1);
  assertEq(4, mono1.length, '单声道不降混');
});

test('多段append 与PTS落位联合', (): void => {
  const acc: PcmAccumulator = new PcmAccumulator();
  // 第一段 2 个样本（0.5 / 0.25）
  acc.appendPcm(bytesOf([0x00, 0x40, 0x00, 0x20]), PcmEncoding.S16LE);
  // 第二段 PTS 落在第 4 个样本：补 2 个零
  acc.alignTo(4, 0);
  assertEq(4, acc.sampleCount(), '补齐到 4');
  // 再追加 3 个样本（1.0 用 0x7FFF 近似）
  acc.appendPcm(bytesOf([0xFF, 0x7F, 0x00, 0xC0, 0x00, 0x00]), PcmEncoding.S16LE);
  assertEq(7, acc.sampleCount(), '多段合计 7 样本');
  assertClose(0.5, acc.at(0), 1e-6, '段 1 首样本');
  assertClose(0.25, acc.at(1), 1e-6, '段 1 次样本');
  assertEq(0, acc.at(2), '补零');
  assertEq(0, acc.at(3), '补零');
  assertClose(32767 / 32768, acc.at(4), 1e-6, '段 2 首样本');
  assertClose(-0.5, acc.at(5), 1e-6, '段 2 次样本');
  assertEq(0, acc.at(6), '段 2 末样本');
  assertEq(0, acc.at(7), '越界读 0');
});
