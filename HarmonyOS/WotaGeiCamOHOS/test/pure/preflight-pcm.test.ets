/**
 * 预校准 PCM 累加器（record/PreflightPcm.ets 的 PreflightMonoPcm，纯类）单测——对拍上游
 * app/src/test/java/com/wotagei/cam/record/PreflightPcmTest.kt（对齐上游 e950328）。
 *
 * 行为面：共同 t0 落位（PTS 空洞补零、早于 t0 的包不带负前导）、48k 双声道 PCM16 → 单声道
 * → 重采样 8k、非零锁存（600ms 双静音早退判据的数据基础）。期望值一律手写字面量。
 */
import { AudioAlign } from '../../entry/src/main/ets/core/AudioAlign';
import { PreflightMonoPcm } from '../../entry/src/main/ets/record/PreflightPcm';

function assertClose(expected: number, actual: number, tol: number, msg: string): void {
  assertTrue(Math.abs(expected - actual) <= tol, `${msg}: expected ${expected}±${tol} got ${actual}`);
}

function stereoPcm16(frames: number, left: number, right: number): Uint8Array {
  const out: Uint8Array = new Uint8Array(frames * 4);
  for (let f: number = 0; f < frames; f++) {
    const i: number = f * 4;
    out[i] = left & 0xFF;
    out[i + 1] = (left >> 8) & 0xFF;
    out[i + 2] = right & 0xFF;
    out[i + 3] = (right >> 8) & 0xFF;
  }
  return out;
}

function monoPcm16(frames: number, value: number): Uint8Array {
  const out: Uint8Array = new Uint8Array(frames * 2);
  for (let f: number = 0; f < frames; f++) {
    out[f * 2] = value & 0xFF;
    out[f * 2 + 1] = (value >> 8) & 0xFF;
  }
  return out;
}

test('48k双声道转8k单声道_常量电平不漂', (): void => {
  const pcm: PreflightMonoPcm = new PreflightMonoPcm(48000, 2, 0);
  pcm.append(stereoPcm16(4800, 16384, 16384), 0);
  assertEq(4800, pcm.frameCount(), '4800 帧全部落位');
  const mono: Float32Array = pcm.toArray();
  const out: Float32Array = AudioAlign.resample(mono, 48000, 8000, 8);
  assertEq(800, out.length, '1s@48k → 1s@8k');
  for (let i: number = 0; i < out.length; i++) {
    assertClose(0.5, out[i], 1e-3, `常量电平样 ${i}`);
  }
});

test('PTS空洞补零_共同t0保留前导', (): void => {
  const base: number = 1000000;
  const pcm: PreflightMonoPcm = new PreflightMonoPcm(48000, 2, base);
  pcm.append(stereoPcm16(10, 16384, 16384), base);
  // 20ms 后第二包：48k 下应在第 960 帧开始，10..959 必须是补的静音
  pcm.append(stereoPcm16(10, 16384, 16384), base + 20000);
  const samples: Float32Array = pcm.toArray();
  assertEq(970, samples.length, '10 + 950 补零 + 10');
  for (let i: number = 10; i < 960; i++) {
    assertClose(0, samples[i], 1e-6, `空洞 ${i} 必须补零`);
  }
  assertTrue(Math.abs(samples[960] - 0.5) < 1e-3, '第二包必须落在 960');
});

test('早于共同t0的包不带负前导', (): void => {
  const base: number = 5000000;
  const pcm: PreflightMonoPcm = new PreflightMonoPcm(48000, 2, base);
  pcm.append(stereoPcm16(10, 1000, 1000), base - 100000);
  assertEq(10, pcm.frameCount(), '负 PTS 包从 0 开始，不许有负下标/前导');
  assertFalse(pcm.isSilent(), '1000/32768 ≈ 0.03 已是非零');
});

test('非零检测_全零静音与任一非零', (): void => {
  const pcm: PreflightMonoPcm = new PreflightMonoPcm(8000, 1, 0);
  pcm.append(new Uint8Array(1600), 0);
  assertTrue(pcm.isSilent(), '全零必须是静音');
  assertEq(800, pcm.frameCount(), '1600 字节 = 800 帧@单声道');
  pcm.append(monoPcm16(1, 1000), 200000);
  assertFalse(pcm.isSilent(), '见过非零后不再是静音');
  // 200ms@8k → target 1600：800..1599 补零 + 1 帧实样 = 1601
  assertEq(1601, pcm.frameCount(), '第二包按共同 t0 落到 1600 再写 1 帧');
});
