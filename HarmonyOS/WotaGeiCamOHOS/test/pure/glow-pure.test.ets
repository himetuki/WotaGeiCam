/**
 * Glow 纯函数全表（对齐上游 GlowParamsTest + GlowScaleTest + GlowStatusNoteTest，ed59285）。
 *
 * Glow（荧光棒光晕）是 scene layer（画面内容的一部分，两槽都带、该被录进去），不是监看叠加。
 * 本批只交付 core 纯函数层 + 全表用例（native Glow 链与胶囊 UI 随后续批）——与 H 批
 * DirectSink/FrameRateFallback 同款纪律：grep 不到接线方时不伪造接线。
 */
import {
  GLOW_ADD_MAX, GLOW_DEFAULT, GLOW_FREQ_MAX, GLOW_GAIN_MAX, GLOW_PRESET_A, GLOW_PRESET_B,
  GLOW_SOFT_MAX, GLOW_THRESHOLD_MAX, GLOW_WIDTH_MAX, GlowParams, GlowStatus,
  GLOW_FULL_MAX_PIXEL_RATE,
  decodeGlow, encodeGlow, fillGlowBlurOffset, glowRgb,
  glowScaleFor, glowStatusNote, glowRtSizeInto, glowRawLongestEdge
} from '../../entry/src/main/ets/core/Glow';
import { DENOISE_FULL_KERNEL_PIXEL_RATE } from '../../entry/src/main/ets/core/Denoise';

// ------------------------------------------------------------------ 参数模型与 sanitize

test('出厂默认是预设A且关闭', () => {
  const d: GlowParams = GLOW_DEFAULT;
  assertFalse(d.enabled, '出厂必须关（零代价口径）');
  assertTrue(GLOW_PRESET_A.sameAs(d), 'DEFAULT 与 PRESET_A 是同一份参数');
  assertEq(0.5, d.threshold);
  assertEq(0.05, d.soft);
  assertEq(0.02, d.width);
  assertEq(1.0, d.gain);
  assertEq(0.0, d.add);
  assertEq(0.0, d.freq);
  assertEq(0.6, d.blend);
  assertEq(0xFFFF3399, d.colorA, '品红');
  assertEq(0xFF33E6FF, d.colorB, '青');
});

test('预设B只改两端色与强度', () => {
  assertEq(0xFFFF730D, GLOW_PRESET_B.colorA, '橙');
  assertEq(0xFF33D940, GLOW_PRESET_B.colorB, '绿');
  assertEq(0.45, GLOW_PRESET_B.threshold);
  assertEq(0.55, GLOW_PRESET_B.blend);
  assertFalse(GLOW_PRESET_B.enabled, '预设 B 也必须是关的（预设≠自动开启）');
});

test('sanitize把每个维度钳进安全域且幂等', () => {
  const wild: GlowParams = new GlowParams({
    threshold: 9, soft: -1, width: 9, gain: 9, add: -3, freq: 99, blend: 1.5
  }).sanitize();
  assertEq(GLOW_THRESHOLD_MAX, wild.threshold);
  assertEq(0, wild.soft);
  assertEq(GLOW_WIDTH_MAX, wild.width);
  assertEq(GLOW_GAIN_MAX, wild.gain);
  assertEq(0, wild.add);
  assertEq(GLOW_FREQ_MAX, wild.freq);
  assertEq(1, wild.blend);
  const inRange: GlowParams = GLOW_PRESET_A;
  assertTrue(inRange.sameAs(inRange.sanitize()), '域内值 sanitize 不动');
  assertTrue(inRange.sameAs(inRange.sanitize().sanitize()), '幂等');
});

test('sanitize把NaN与Infinity回落到出厂值', () => {
  const bad: GlowParams = new GlowParams({
    threshold: NaN, soft: Infinity, width: NaN, gain: -Infinity, add: NaN, freq: Infinity, blend: NaN
  }).sanitize();
  assertEq(0.5, bad.threshold);
  assertEq(0.05, bad.soft);
  assertEq(0.02, bad.width);
  assertEq(1, bad.gain);
  assertEq(0, bad.add);
  assertEq(0, bad.freq);
  assertEq(0.6, bad.blend);
});

test('sanitize把颜色掩到32位', () => {
  assertEq(0xFFFFFFFF, new GlowParams({ colorA: -1 }).sanitize().colorA);
  assertEq(0xFFFF3399, new GlowParams({ colorA: 0x1FFFF3399 }).sanitize().colorA);
  assertEq(0xFF000000, new GlowParams({ colorB: 0xFF000000 }).sanitize().colorB);
});

// ------------------------------------------------------------------ 编码/解码

test('编码解码往返逐字段一致', () => {
  const samples: Array<GlowParams> = [
    GLOW_DEFAULT, GLOW_PRESET_B,
    new GlowParams({ enabled: true, threshold: 0.37, blend: 0.83 })
  ];
  for (const p of samples) {
    assertTrue(p.sanitize().sameAs(decodeGlow(encodeGlow(p))), 'encode→decode 必须逐字段往返');
  }
  const on: GlowParams = new GlowParams({ enabled: true });
  assertTrue(decodeGlow(encodeGlow(on)).enabled, '开着也要往返（enabled 是开关不是是否参与编码）');
});

test('坏串一律回出厂默认', () => {
  const d: GlowParams = GLOW_DEFAULT;
  assertTrue(d.sameAs(decodeGlow(undefined)));
  assertTrue(d.sameAs(decodeGlow('')));
  assertTrue(d.sameAs(decodeGlow('   ')));
  assertTrue(d.sameAs(decodeGlow('garbage')));
  assertTrue(d.sameAs(decodeGlow('enabled=1')), '缺键');
  const good: string = encodeGlow(GLOW_DEFAULT);
  assertTrue(d.sameAs(decodeGlow(good.replace('threshold=0.5', 'threshold=abc'))), '非数');
  assertTrue(d.sameAs(decodeGlow(good.replace('colorA=FFFF3399', 'colorA=ZZZZ3399'))), '非十六进制');
  assertTrue(d.sameAs(decodeGlow(good.replace('enabled=0', 'enabled=2'))), 'enabled 非 0/1');
  assertTrue(d.sameAs(decodeGlow(good.replace('soft=0.05', 'soft='))), '空值');
  // "9x" 这类尾巴脏的串不收（对齐 Kotlin toFloatOrNull）
  assertTrue(d.sameAs(decodeGlow(good.replace('gain=1', 'gain=1x'))));
});

test('未知键忽略且越界值解码时被钳住', () => {
  const good: string = encodeGlow(GLOW_DEFAULT);
  assertTrue(GLOW_DEFAULT.sameAs(decodeGlow(good + ';futureParam=7')),
    '前向兼容：以后加参数时老版本读新串不许炸');
  const clamped: GlowParams =
    decodeGlow(good.replace('threshold=0.5', 'threshold=9').replace('blend=0.6', 'blend=-2'));
  assertEq(GLOW_THRESHOLD_MAX, clamped.threshold);
  assertEq(0, clamped.blend);
});

test('编码前先钳域且颜色固定八位大写十六进制', () => {
  const encoded: string = encodeGlow(new GlowParams({ threshold: 9, colorA: 0xFFFF3399 }));
  assertTrue(encoded.indexOf('threshold=1') >= 0, '越界值必须先钳再写盘');
  assertTrue(encoded.indexOf('threshold=9') < 0, '编码串里不许出现越界写法');
  assertTrue(encoded.indexOf('colorA=FFFF3399') >= 0, '颜色固定 8 位大写十六进制');
  assertTrue(encoded.indexOf('colorB=FF33E6FF') >= 0);
});

test('参数里的开关默认关且预设不改开关', () => {
  assertFalse(GLOW_PRESET_A.enabled);
  assertFalse(GLOW_PRESET_B.enabled);
  assertFalse(decodeGlow(encodeGlow(GLOW_DEFAULT)).enabled);
});

test('ARGB换算只取RGB且忽略alpha', () => {
  const magenta: Array<number> = glowRgb(0xFFFF3399);
  assertEq(3, magenta.length);
  assertEq(1.0, magenta[0]);
  assertEq(0.2, magenta[1]);
  assertEq(0.6, magenta[2]);
  const sameRgbOtherAlpha: Array<number> = glowRgb(0x00FF3399);
  assertEq(magenta[0], sameRgbOtherAlpha[0], 'alpha 不参与（输出恒不透明纪律）');
  assertEq(magenta[1], sameRgbOtherAlpha[1]);
  assertEq(magenta[2], sameRgbOtherAlpha[2]);
  assertEq(0, glowRgb(0xFF000000)[0]);
  assertEq(1, glowRgb(0xFFFFFFFF)[0]);
  const presetA: Array<number> = glowRgb(GLOW_PRESET_A.colorA);
  assertEq(1, presetA[0]);
  assertEq(51 / 255, glowRgb(GLOW_PRESET_A.colorB)[0]);
  assertEq(230 / 255, glowRgb(GLOW_PRESET_A.colorB)[1]);
  assertEq(1, glowRgb(GLOW_PRESET_A.colorB)[2]);
});

// ------------------------------------------------------------------ 分档与 RT 尺寸

test('分档按像素乘帧率落在三档', () => {
  assertEq(1, glowScaleFor(1280, 720, 30), '720p30 应走原尺寸链');
  assertEq(2, glowScaleFor(1920, 1080, 30), '1080p30 走半尺寸');
  assertEq(2, glowScaleFor(1920, 1080, 60), '1080p60 也走半尺寸（124M < 150M）');
  assertEq(2, glowScaleFor(2560, 1440, 30), '1440p30 走半尺寸（110.6M）');
  assertEq(4, glowScaleFor(3840, 2160, 30), '4K30 走四分之一（248.8M）');
  assertEq(4, glowScaleFor(1920, 1080, 120), '1080p120 走四分之一');
});

test('两个阈值的边界都在内侧', () => {
  assertEq(1, glowScaleFor(1000, 1000, 40));
  assertEq(2, glowScaleFor(1000, 1000, 41));
  assertEq(2, glowScaleFor(1000, 1000, 150));
  assertEq(4, glowScaleFor(1000, 1000, 151));
  assertEq(40_000_000, GLOW_FULL_MAX_PIXEL_RATE);
  assertEq(150_000_000, DENOISE_FULL_KERNEL_PIXEL_RATE, '与降噪 kernel 同一魔数，不造第二个阈值');
});

test('信息不全时分档取最高质量档', () => {
  assertEq(1, glowScaleFor(0, 0, 30));
  assertEq(1, glowScaleFor(1920, 0, 30));
  assertEq(1, glowScaleFor(1920, 1080, 0));
  assertEq(1, glowScaleFor(-1, -1, -1));
});

test('RT尺寸按档缩并保底一像素', () => {
  const out: Array<number> = [0, 0];
  assertTrue(glowRtSizeInto(out, 1920, 1080, 2, 4096));
  assertEq(960, out[0]);
  assertEq(540, out[1]);
  assertTrue(glowRtSizeInto(out, 1920, 1080, 4, 4096));
  assertEq(480, out[0]);
  assertEq(270, out[1]);
  assertTrue(glowRtSizeInto(out, 1920, 1080, 1, 4096));
  assertEq(1920, out[0]);
  assertEq(1080, out[1]);
  assertTrue(glowRtSizeInto(out, 2, 1, 4, 4096), '缩完小于 1 保底 1');
  assertEq(1, out[0]);
  assertEq(1, out[1]);
  assertTrue(glowRtSizeInto(out, 1, 1, 4, 4096));
  assertEq(1, out[0]);
  assertEq(1, out[1]);
});

test('超上限时按最长边等比夹取而不是逐维独立夹', () => {
  const out: Array<number> = [0, 0];
  assertTrue(glowRtSizeInto(out, 7680, 4320, 1, 4096));
  assertEq(4096, out[0], '最长边必须正好是上限');
  assertEq(2304, out[1]);
  const srcRatio: number = 7680 / 4320;
  const rtRatio: number = out[0] / out[1];
  assertTrue(Math.abs(srcRatio - rtRatio) < 0.01, '夹取后不许改变宽高比（椭圆缺陷）');
  assertTrue(glowRtSizeInto(out, 2160, 3840, 1, 2048));
  assertEq(1152, out[0]);
  assertEq(2048, out[1]);
});

test('夹取判据用夹取前的原始最长边', () => {
  assertEq(4096, glowRawLongestEdge(4096, 2160, 1));
  assertFalse(glowRawLongestEdge(4096, 2160, 1) > 4096, '正好等于上限不算夹取');
  const out: Array<number> = [0, 0];
  assertTrue(glowRtSizeInto(out, 4096, 2160, 1, 4096));
  assertEq(2160, out[1], '没夹的原样');
  assertEq(4097, glowRawLongestEdge(4097, 2160, 1), '4097 才夹');
  assertTrue(glowRawLongestEdge(4097, 2160, 1) > 4096, '超过一格必须判为夹取');
  assertTrue(glowRtSizeInto(out, 4097, 2160, 1, 4096));
  assertEq(4096, out[0]);
  assertEq(2159, out[1]);
  assertEq(4096, glowRawLongestEdge(8192, 2160, 2), 'scale 参与原始尺寸：@2 → 4096 不夹');
  assertEq(8192, glowRawLongestEdge(8192, 2160, 1), '@1 → 8192 夹');
  assertEq(0, glowRawLongestEdge(0, 2160, 1), '非法输入 → 0（调用方据此判没有夹）');
  assertEq(0, glowRawLongestEdge(4096, 2160, 0));
});

test('尺寸或档非法返回false且不改调用方数组', () => {
  const out: Array<number> = [-7, -7];
  assertFalse(glowRtSizeInto(out, 0, 1080, 1, 4096));
  assertFalse(glowRtSizeInto(out, 1920, 0, 1, 4096));
  assertFalse(glowRtSizeInto(out, 1920, 1080, 0, 4096));
  assertFalse(glowRtSizeInto(out, 1920, 1080, 1, 0));
  assertEq(-7, out[0], '非法输入不许写数组');
  assertEq(-7, out[1]);
});

test('输出数组不足两位是编程错误', () => {
  let threw: boolean = false;
  try {
    glowRtSizeInto([0], 1920, 1080, 2, 4096);
  } catch (e) {
    threw = true;
  }
  assertTrue(threw, 'out 长度 <2 必须抛（编程错误不许静默）');
});

// ------------------------------------------------------------------ 模糊偏移（各向同性）

test('模糊偏移以短边为单位且横纵各向同性', () => {
  const out: Array<number> = [0, 0];
  assertTrue(fillGlowBlurOffset(out, 2000, 1000, 0.02, true));
  const horizontalUv: number = out[0];
  assertEq(0.01, horizontalUv, '横屏 2000×1000：短边是 1000，0.02×(1000/2000)');
  assertEq(0, out[1]);
  assertTrue(fillGlowBlurOffset(out, 2000, 1000, 0.02, false));
  const verticalUv: number = out[1];
  assertEq(0, out[0]);
  assertEq(0.02, verticalUv);
  assertTrue(Math.abs(20 - horizontalUv * 2000) < 0.001, '换算回像素两边都是 20px');
  assertTrue(Math.abs(20 - verticalUv * 1000) < 0.001);
});

test('竖屏RT的偏移也各向同性', () => {
  const out: Array<number> = [0, 0];
  assertTrue(fillGlowBlurOffset(out, 1000, 2000, 0.02, true));
  const horizontalUv: number = out[0];
  assertEq(0.02, horizontalUv);
  assertTrue(fillGlowBlurOffset(out, 1000, 2000, 0.02, false));
  const verticalUv: number = out[1];
  assertEq(0.01, verticalUv);
  assertTrue(Math.abs(20 - horizontalUv * 1000) < 0.001, '短边 1000：两边都是 20px');
});

test('方形RT两维比例都是1', () => {
  const out: Array<number> = [0, 0];
  assertTrue(fillGlowBlurOffset(out, 512, 512, 0.05, true));
  assertEq(0.05, out[0]);
  assertTrue(fillGlowBlurOffset(out, 512, 512, 0.05, false));
  assertEq(0.05, out[1]);
});

test('偏移非法输入返回false', () => {
  const out: Array<number> = [-1, -1];
  assertFalse(fillGlowBlurOffset(out, 0, 1000, 0.02, true));
  assertFalse(fillGlowBlurOffset(out, 1000, 0, 0.02, true));
  assertFalse(fillGlowBlurOffset(out, 1000, 1000, NaN, true));
  assertFalse(fillGlowBlurOffset(out, 1000, 1000, -0.1, true));
  assertEq(-1, out[0], '非法输入不许写数组');
});

// ------------------------------------------------------------------ 状态行优先级

function status(broken: boolean = false, clamped: boolean = false,
  rtW: number = 960, rtH: number = 540, inW: number = 1920, inH: number = 1080): GlowStatus {
  return new GlowStatus(true, true, broken, 2, inW, inH, 30, rtW, rtH, clamped);
}

test('DIRECT最先判_即使开关开着也说清原因', () => {
  assertEq('cam_glow_direct', glowStatusNote(undefined, false, false));
  assertEq('cam_glow_direct', glowStatusNote(undefined, true, false), 'DIRECT 先于关着');
  assertEq('cam_glow_direct', glowStatusNote(status(), true, false));
});

test('关着什么都不显示_包括停用与夹取的残留', () => {
  assertEq(undefined, glowStatusNote(status(), false, true));
  assertEq(undefined, glowStatusNote(status(true), false, true), '停用残留也是噪声');
  assertEq(undefined, glowStatusNote(status(false, true), false, true), '夹取残留也是噪声');
  assertEq(undefined, glowStatusNote(undefined, false, true));
});

test('停用优先于夹取与分档', () => {
  assertEq('cam_glow_broken', glowStatusNote(status(true, true), true, true));
  assertEq('cam_glow_broken', glowStatusNote(status(true), true, true));
});

test('夹取必须有真实RT尺寸才报', () => {
  assertEq('cam_glow_clamped', glowStatusNote(status(false, true, 4096, 2304), true, true));
  // rt 为 0 时不能报一个假尺寸——落到下一优先级（已知输入 → 分档句；未知 → 自动句）
  assertEq('cam_glow_status_res_label', glowStatusNote(status(false, true, 0, 0), true, true));
  assertEq('cam_glow_status_auto', glowStatusNote(status(false, true, 0, 0, 0, 0), true, true));
});

test('正常分档_已知输入尺寸报带分辨率的句式', () => {
  assertEq('cam_glow_status_res_label', glowStatusNote(status(), true, true));
  assertEq('cam_glow_status_auto', glowStatusNote(status(false, false, 0, 0, 0, 0), true, true),
    '未知输入尺寸报自动句式');
});

test('引擎还没建好时不猜档位', () => {
  assertEq(undefined, glowStatusNote(undefined, true, true));
});
