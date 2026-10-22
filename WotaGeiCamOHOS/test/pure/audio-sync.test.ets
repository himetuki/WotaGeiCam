/**
 * 音频自动同步 DSP（player/AudioSync.ets）的纯函数单测——对拍上游
 * app/src/test/java/com/wotagei/cam/AudioSyncTest.kt（10 条，对齐上游 38d712a）。
 *
 * 信号设计要点：refine 的置信度 = 主峰/次峰（次峰取主峰 ±150ms 之外），所以精修用例必须用
 * **短突发**而不是连续正弦——连续音的互相关以音周期自相关，次峰全在保护窗内，置信度恒 ≈1，
 * 测不出"置信度随峰锐度变化"。
 *
 * 浮点期望值用上游容差（1e-6/1e-4/1e-3/1e-2），期望量一律手写字面量。
 */
import { AudioSync, RefineResult } from '../../entry/src/main/ets/player/AudioSync';

function assertClose(expected: number, actual: number, tol: number, msg: string): void {
  assertTrue(Math.abs(expected - actual) <= tol, `${msg}: expected ${expected}±${tol} got ${actual}`);
}

test('同源采样率直接拷贝 常量样保持', (): void => {
  const src: Float32Array = new Float32Array([0.5, 0.25, 1, 0.75]);
  const out: Float32Array = AudioSync.resample(src, 8000, 8000, 10);
  assertEq(4, out.length, '同源不改变帧数');
  assertClose(0.5, out[0], 1e-6, '样 0');
  assertClose(0.25, out[1], 1e-6, '样 1');
  assertClose(1, out[2], 1e-6, '样 2');
  assertClose(0.75, out[3], 1e-6, '样 3');
});

test('降采样长度减半 常量电平不漂', (): void => {
  const src: Float32Array = new Float32Array(48000);
  for (let i: number = 0; i < src.length; i++) {
    src[i] = 0.5;
  }
  const out: Float32Array = AudioSync.resample(src, 48000, 24000, 10);
  assertEq(24000, out.length, '1s@48k → 1s@24k');
  for (let i: number = 0; i < out.length; i++) {
    assertClose(0.5, out[i], 1e-6, `常量电平样 ${i}`);
  }
});

test('maxSeconds截断输出时长', (): void => {
  const oneSec: Float32Array = new Float32Array(48000);
  for (let i: number = 0; i < oneSec.length; i++) {
    oneSec[i] = 0.5;
  }
  const out: Float32Array = AudioSync.resample(oneSec, 48000, 24000, 1);
  assertEq(24000, out.length, '1s@48k 全量取 1s@24k');
  // 2s 源 + maxSeconds=1：目标帧数被 maxSeconds 封顶（不是按源帧数直出 48000）
  const twoSec: Float32Array = new Float32Array(96000);
  const out2: Float32Array = AudioSync.resample(twoSec, 48000, 24000, 1);
  assertEq(24000, out2.length, '2s 源截到 1s@24k');
});

test('包络 响段归一到1 静段归零', (): void => {
  // 2s @8k：前 1s 0.5 幅度、后 1s 静音 → 10ms 窗共 200 点。
  // 归一化按**包络最大值**（0.25）做：响段 0.25/0.25 = 1.0，静段低于均值钳 0
  const pcm: Float32Array = new Float32Array(16000);
  for (let i: number = 0; i < 8000; i++) {
    pcm[i] = 0.5;
  }
  const env: Float32Array = AudioSync.envelope(pcm);
  assertEq(200, env.length, '100 点/秒 × 2s');
  assertClose(1, env[50], 1e-4, '响段归一 1');
  assertClose(0, env[150], 1e-6, '静段归零');
});

test('全静音包络不除零 全零返回', (): void => {
  const env: Float32Array = AudioSync.envelope(new Float32Array(16000));
  assertEq(200, env.length, '仍按窗数出包络');
  for (let i: number = 0; i < env.length; i++) {
    assertClose(0, env[i], 1e-6, `静音包络 ${i}`);
  }
});

test('包络延迟3步 粗对齐返回正30ms', (): void => {
  // 口径：left(t) ≈ right(t + offset)，offset 正 = 右片晚响
  const envL: Float32Array = new Float32Array(300);
  for (let i: number = 100; i <= 130; i++) {
    envL[i] = 1;
  }
  const envR: Float32Array = new Float32Array(300);
  for (let i: number = 3; i < 300; i++) {
    envR[i] = envL[i - 3];
  }
  assertEq(30, AudioSync.coarseAlign(envL, envR), '右片晚 3 步 = +30ms');
});

test('右片早响 粗对齐返回负偏移', (): void => {
  const envR: Float32Array = new Float32Array(300);
  for (let i: number = 100; i <= 130; i++) {
    envR[i] = 1;
  }
  const envL: Float32Array = new Float32Array(300);
  for (let i: number = 3; i < 300; i++) {
    envL[i] = envR[i - 3];
  }
  assertEq(-30, AudioSync.coarseAlign(envL, envR), '左片晚 3 步 = -30ms');
});

test('fft逆变换往返 随机信号复原', (): void => {
  // 本实现的 1/n 折在调用点：逆变换后需除以 n 才复原
  const n: number = 1024;
  let seed: number = 7;
  const re: Float32Array = new Float32Array(n);
  for (let i: number = 0; i < n; i++) {
    seed = (seed * 1664525 + 1013904223) >>> 0;
    re[i] = seed / 4294967296 - 0.5;
  }
  const im: Float32Array = new Float32Array(n);
  const orig: Float32Array = new Float32Array(re);
  AudioSync.fft(re, im);
  AudioSync.inverseFft(re, im);
  for (let i: number = 0; i < n; i++) {
    assertClose(orig[i], re[i] / n, 1e-3, `实部往返 ${i}`);
    assertClose(0, im[i] / n, 1e-3, `虚部往返 ${i}`);
  }
});

test('常量信号的fft只有直流分量', (): void => {
  const n: number = 256;
  const re: Float32Array = new Float32Array(n);
  for (let i: number = 0; i < n; i++) {
    re[i] = 1;
  }
  const im: Float32Array = new Float32Array(n);
  AudioSync.fft(re, im);
  assertClose(256, re[0], 1e-2, '直流实部=n');
  assertClose(0, im[0], 1e-2, '直流虚部 0');
  for (let k: number = 1; k < n; k++) {
    assertClose(0, re[k], 1e-2, `bin ${k} 实部`);
    assertClose(0, im[k], 1e-2, `bin ${k} 虚部`);
  }
});

test('精修 短突发延迟5ms 返回正5毫秒且高置信', (): void => {
  const sr: number = 8000;
  const n: number = 4000; // 0.5s @8k
  const left: Float32Array = new Float32Array(n);
  for (let i: number = 400; i < 1200; i++) {
    left[i] = Math.sin(2 * Math.PI * 500 * i / sr) * 0.8;
  }
  const delaySamples: number = 40; // 5ms
  const right: Float32Array = new Float32Array(n);
  for (let i: number = delaySamples; i < n; i++) {
    right[i] = left[i - delaySamples];
  }
  const res: RefineResult = AudioSync.refine(left, right, 0);
  assertTrue(res.offsetMs >= 4 && res.offsetMs <= 6,
    `精修应落在 +5ms 附近（实际 ${res.offsetMs}）`);
  assertTrue(res.confidence > 2, `突发主峰应显著（conf=${res.confidence}）`);
});
