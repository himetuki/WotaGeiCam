/**
 * 纯 DSP 对齐核心（core/AudioAlign.ets）单测——对拍上游
 * app/src/test/java/com/wotagei/cam/AudioAlignTest.kt（对齐上游 e950328）。
 *
 * 信号用**非周期**短音突发（间隔伪随机，自研 LCG，与上游 kotlin.random 不同源但同设计）：
 * 连续正弦的自相关次峰全在主峰保护窗内，置信度测不出；等间隔突发又会在 ±周期 处产生真歧义。
 * 伪随机间隔两头都避开。期望值一律手写字面量（±2ms / conf>2.0）。
 *
 * 上游 `core纯度_不依赖android与player` 源码扫描守卫不移植（KotlinSourceScan 平台面）；
 * 纯度由本仓库 pure-test 架构保证（@ohos/@kit 桩外无平台依赖才可运行）。
 */
import { AudioAlign, OffsetEstimate } from '../../entry/src/main/ets/core/AudioAlign';

/** 线性同余发生器（确定性伪随机，代替 kotlin.random.Random(seed)） */
class Lcg {
  private state: number;

  constructor(seed: number) {
    this.state = seed >>> 0;
  }

  nextInt(bound: number): number {
    // state < 2^32、乘子 1664525：乘积 < 2^53，double 运算精确
    this.state = (this.state * 1664525 + 1013904223) % 4294967296;
    return Math.trunc((this.state / 4294967296) * bound);
  }
}

const SR: number = 8000;

function burstSignal(seconds: number, seed: number): Float32Array {
  const n: number = Math.trunc(seconds * SR);
  const out: Float32Array = new Float32Array(n);
  const rnd: Lcg = new Lcg(seed);
  let pos: number = Math.trunc(SR / 10) + rnd.nextInt(Math.trunc(SR / 10)); // 首突发 100..200ms
  while (pos < n - Math.trunc(SR / 5)) {
    const len: number = Math.trunc(SR / 20) + rnd.nextInt(Math.trunc(SR / 25)); // 50..90ms 响段
    const freq: number = 300 + rnd.nextInt(900);
    for (let j: number = 0; j < len; j++) {
      const i: number = pos + j;
      if (i >= n) {
        break;
      }
      out[i] = 0.8 * Math.sin(2 * Math.PI * freq * i / SR);
    }
    pos += len + Math.trunc(SR / 5) + rnd.nextInt(Math.trunc(SR / 5)); // 200..400ms 静音
  }
  return out;
}

/** 循环平移：shift>0 = 内容晚到（用环形避免首尾补零制造虚假边界峰） */
function shifted(src: Float32Array, shiftSamples: number): Float32Array {
  const n: number = src.length;
  const out: Float32Array = new Float32Array(n);
  for (let i: number = 0; i < n; i++) {
    let j: number = (i - shiftSamples) % n;
    if (j < 0) {
      j += n;
    }
    out[i] = src[j];
  }
  return out;
}

test('内录晚120毫秒_恢复误差不超过2毫秒', (): void => {
  const env: Float32Array = burstSignal(2.0, 11);
  const cap: Float32Array = shifted(env, SR * 120 / 1000);
  const est: OffsetEstimate | undefined = AudioAlign.estimateOffsetMs(env, cap, SR);
  assertTrue(est !== undefined, '有明确突发的双路信号必须给出估计');
  if (est === undefined) {
    return;
  }
  assertTrue(Math.abs(est.offsetMs - 120) <= 2, `偏移符号：cap 晚到 = 正（实际 ${est.offsetMs}）`);
  assertTrue(est.confidence > 2.0, `突发信号主峰应显著（conf=${est.confidence}）`);
});

test('内录早120毫秒_恢复误差不超过2毫秒且为负', (): void => {
  const env: Float32Array = burstSignal(2.0, 29);
  const cap: Float32Array = shifted(env, -SR * 120 / 1000);
  const est: OffsetEstimate | undefined = AudioAlign.estimateOffsetMs(env, cap, SR);
  assertTrue(est !== undefined, '有明确突发的双路信号必须给出估计');
  if (est === undefined) {
    return;
  }
  assertTrue(Math.abs(est.offsetMs + 120) <= 2, `偏移符号：cap 早到 = 负（实际 ${est.offsetMs}）`);
  assertTrue(est.confidence > 2.0, `conf=${est.confidence}`);
});

test('符号方向_正负60毫秒各自过零轴', (): void => {
  const env: Float32Array = burstSignal(1.5, 7);
  const late: OffsetEstimate | undefined =
    AudioAlign.estimateOffsetMs(env, shifted(env, SR * 60 / 1000), SR);
  const early: OffsetEstimate | undefined =
    AudioAlign.estimateOffsetMs(env, shifted(env, -SR * 60 / 1000), SR);
  assertTrue(late !== undefined, '晚到必须给出估计');
  assertTrue(early !== undefined, '早到必须给出估计');
  if (late === undefined || early === undefined) {
    return;
  }
  assertTrue(Math.abs(late.offsetMs - 60) <= 2, `晚到应为正（实际 ${late.offsetMs}）`);
  assertTrue(Math.abs(early.offsetMs + 60) <= 2, `早到应为负（实际 ${early.offsetMs}）`);
});

test('双路静音_任一路静音返回null', (): void => {
  const env: Float32Array = burstSignal(1.5, 3);
  assertTrue(AudioAlign.estimateOffsetMs(env, new Float32Array(env.length), SR) === undefined,
    '右路静音不给估计');
  assertTrue(AudioAlign.estimateOffsetMs(new Float32Array(env.length), new Float32Array(env.length), SR) === undefined,
    '双路全静音不给估计');
});

test('时长不足200毫秒_不硬凑偏移返回null', (): void => {
  // 100ms@8k = 800 样本 < 200ms 下限 1600 样本（红线 1）
  const tiny: Float32Array = new Float32Array(SR / 10);
  for (let i: number = 0; i < tiny.length; i++) {
    tiny[i] = 0.5;
  }
  assertTrue(AudioAlign.estimateOffsetMs(tiny, tiny, SR) === undefined, '过短输入不给估计');
});

test('置信度低于注入阈值_返回null', (): void => {
  // 红线 3：同一对信号在默认门槛下可估，注入超高门槛必须整体拒绝
  const env: Float32Array = burstSignal(1.5, 11);
  const cap: Float32Array = shifted(env, SR * 60 / 1000);
  const ok: OffsetEstimate | undefined = AudioAlign.estimateOffsetMs(env, cap, SR);
  assertTrue(ok !== undefined, '默认门槛下应给出估计');
  assertTrue(AudioAlign.estimateOffsetMs(env, cap, SR, 10000, 50, 1e6) === undefined,
    'minConfidence=1e6 必须拒绝一切结果');
});

test('独立信号互不相关_默认门槛返回null', (): void => {
  // 两路独立 burst（不同种子）互相关主/次峰比到不了 1.5，默认门槛必须拒绝
  const env: Float32Array = burstSignal(1.5, 11);
  const cap: Float32Array = burstSignal(1.5, 29);
  assertTrue(AudioAlign.estimateOffsetMs(env, cap, SR) === undefined,
    '不相关双路不许产出偏移');
});

test('非法参数_采样率或窗口非正返回null', (): void => {
  const env: Float32Array = burstSignal(1.0, 11);
  const cap: Float32Array = shifted(env, SR * 60 / 1000);
  assertTrue(AudioAlign.estimateOffsetMs(env, cap, 0) === undefined, '采样率 0 拒绝');
  assertTrue(AudioAlign.estimateOffsetMs(env, cap, -8000) === undefined, '负采样率拒绝');
  assertTrue(AudioAlign.estimateOffsetMs(env, cap, SR, 0) === undefined, '搜索窗 0 拒绝');
  assertTrue(AudioAlign.estimateOffsetMs(env, cap, SR, 10000, 0) === undefined, '精修窗 0 拒绝');
});
