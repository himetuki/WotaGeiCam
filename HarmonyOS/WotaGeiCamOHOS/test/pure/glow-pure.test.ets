/**
 * Glow 纯函数全表（对齐上游 GlowParamsTest + GlowScaleTest + GlowStatusNoteTest，ed59285；
 * 2026-10-28/29 用户口径两步走后的模型：04d1c62 删预设两端色 → 0b9d275 色盘单色 →
 * 350c671 colorOnly/阈值域 60~100%——上游 GlowModelTest 终态）。
 *
 * Glow（荧光棒光晕）是 scene layer（画面内容的一部分，两槽都带、该被录进去），不是监看叠加。
 */
import {
  GLOW_ADD_MAX, GLOW_COLOR_DEFAULT, GLOW_DEFAULT, GLOW_FREQ_MAX, GLOW_GAIN_MAX,
  GLOW_SOFT_MAX, GLOW_THRESHOLD_MAX, GLOW_THRESHOLD_MIN, GLOW_WIDTH_MAX, GlowParams, GlowStatus,
  GLOW_FULL_MAX_PIXEL_RATE,
  decodeGlow, encodeGlow, fillGlowBlurOffset, glowArgbFromHsv, glowArgbFromWheel, glowRgb,
  glowHueSatOf, glowScaleFor, glowStatusNote, glowRtSizeInto, glowRawLongestEdge, glowWheelMarkerOf
} from '../../entry/src/main/ets/core/Glow';
import { DENOISE_FULL_KERNEL_PIXEL_RATE } from '../../entry/src/main/ets/core/Denoise';

// ------------------------------------------------------------------ 参数模型与 sanitize

test('出厂默认=白（跟随原色）且关闭，阈值域 0.6 起步', () => {
  const d: GlowParams = GLOW_DEFAULT;
  assertFalse(d.enabled, '出厂必须关（零代价口径）');
  assertEq(0.7, d.threshold, '出厂 70%（2026-10-29 用户口径 60~100% 的中档）');
  assertEq(0.05, d.soft);
  assertEq(0.02, d.width);
  assertEq(1.0, d.gain);
  assertEq(0.0, d.add);
  assertEq(0.0, d.freq);
  assertEq(0.6, d.blend);
  assertEq(GLOW_COLOR_DEFAULT, d.color, '白 = 不改色（跟随光棒自身颜色）');
  assertFalse(d.colorOnly, '出厂物理直觉口径：达到阈值的像素发自身颜色');
  assertEq(0.6, GLOW_THRESHOLD_MIN, '阈值域下限 60%');
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
  const inRange: GlowParams = GLOW_DEFAULT;
  assertTrue(inRange.sameAs(inRange.sanitize()), '域内值 sanitize 不动');
  assertTrue(inRange.sameAs(inRange.sanitize().sanitize()), '幂等');
  // 阈值低于新域下限：钳回 0.6（域 60~100%）
  assertEq(GLOW_THRESHOLD_MIN, new GlowParams({ threshold: 0.3 }).sanitize().threshold);
});

test('sanitize把NaN与Infinity回落到出厂值', () => {
  const bad: GlowParams = new GlowParams({
    threshold: NaN, soft: Infinity, width: NaN, gain: -Infinity, add: NaN, freq: Infinity, blend: NaN
  }).sanitize();
  assertEq(0.7, bad.threshold);
  assertEq(0.05, bad.soft);
  assertEq(0.02, bad.width);
  assertEq(1, bad.gain);
  assertEq(0, bad.add);
  assertEq(0, bad.freq);
  assertEq(0.6, bad.blend);
});

test('sanitize把颜色掩到32位且colorOnly透传', () => {
  assertEq(0xFFFFFFFF, new GlowParams({ color: -1 }).sanitize().color);
  assertEq(0xFFFF3399, new GlowParams({ color: 0x1FFFF3399 }).sanitize().color);
  assertTrue(new GlowParams({ color: 0xFF331122, colorOnly: true }).sanitize().colorOnly);
  assertFalse(new GlowParams({ colorOnly: true }).sanitize().colorOnly === true && false, '');
});

// ------------------------------------------------------------------ 编码/解码

test('编码解码往返逐字段一致（新格式 color/coloronly 键）', () => {
  const samples: Array<GlowParams> = [
    GLOW_DEFAULT,
    new GlowParams({ enabled: true, threshold: 0.85, blend: 0.83 }),
    new GlowParams({ enabled: true, color: 0xFF7340D9, colorOnly: true })
  ];
  for (const p of samples) {
    assertTrue(p.sanitize().sameAs(decodeGlow(encodeGlow(p))), 'encode→decode 必须逐字段往返');
  }
  const on: GlowParams = new GlowParams({ enabled: true });
  assertTrue(decodeGlow(encodeGlow(on)).enabled, '开着也要往返（enabled 是开关不是是否参与编码）');
});

test('旧串（colorA/colorB 键、缺 color 键）迁移 = 白（跟随原色）', () => {
  // 旧版编码串：color/coloronly 键缺席 ⇒ color 回白、colorOnly 回 false，其余照读
  const legacy: string = 'enabled=1;threshold=0.7;soft=0.05;width=0.02;gain=1;add=0;freq=0;' +
    'blend=0.6;colorA=FFFF3399;colorB=FF33E6FF';
  const migrated: GlowParams = decodeGlow(legacy);
  assertTrue(migrated.enabled);
  assertEq(GLOW_COLOR_DEFAULT, migrated.color, '旧串缺 color 键 = 白（不猜近似色）');
  assertFalse(migrated.colorOnly);
});

test('坏串一律回出厂默认', () => {
  const d: GlowParams = GLOW_DEFAULT;
  assertTrue(d.sameAs(decodeGlow(undefined)));
  assertTrue(d.sameAs(decodeGlow('')));
  assertTrue(d.sameAs(decodeGlow('   ')));
  assertTrue(d.sameAs(decodeGlow('garbage')));
  assertTrue(d.sameAs(decodeGlow('enabled=1')), '缺键');
  const good: string = encodeGlow(GLOW_DEFAULT);
  assertTrue(d.sameAs(decodeGlow(good.replace('threshold=0.7', 'threshold=abc'))), '非数');
  assertTrue(d.sameAs(decodeGlow(good.replace('color=FFFFFF', 'color=ZZZZFF'))), '坏色值不许猜');
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
    decodeGlow(good.replace('threshold=0.7', 'threshold=9').replace('blend=0.6', 'blend=-2'));
  assertEq(GLOW_THRESHOLD_MAX, clamped.threshold);
  assertEq(0, clamped.blend);
});

test('编码前先钳域且颜色固定大写十六进制（color/coloronly 键）', () => {
  const encoded: string = encodeGlow(new GlowParams({ threshold: 9, color: 0xFF7340D9 }));
  assertTrue(encoded.indexOf('threshold=1') >= 0, '越界值必须先钳再写盘');
  assertTrue(encoded.indexOf('threshold=9') < 0, '编码串里不许出现越界写法');
  assertTrue(encoded.indexOf('color=FF7340D9') >= 0, '颜色大写十六进制（含 alpha 8 位）');
  assertTrue(encoded.indexOf('coloronly=0') >= 0, 'coloronly 键恒在册');
  assertTrue(encodeGlow(new GlowParams({ colorOnly: true })).indexOf('coloronly=1') >= 0);
});

test('参数里的开关默认关', () => {
  assertFalse(GLOW_DEFAULT.enabled);
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
  assertEq(1, glowRgb(0xFFFFFFFF)[0], '白 = 恒等 tint');
});

// ------------------------------------------------------------------ 色盘几何（350c671）

test('色盘圆心=白（饱和度0），半径=满饱和', () => {
  const center: Array<number> = glowArgbFromWheel(0, 0, 60);
  assertEq(0xFFFFFFFF, center, '圆心即白 = 跟随原色（双击归零的落点）');
  const edge: Array<number> = glowArgbFromWheel(60, 0, 60);
  assertEq(0xFFFF0000, edge, '+x 方向 = 红（0° 色相，明度 1）');
});

test('hue/sat 折算：半径夹满、四象限色相正确', () => {
  const c: Array<number> = glowHueSatOf(30, 0, 60);
  assertEq(0, c[0], '0° = 红');
  assertEq(0.5, c[1], '半半径 = 半饱和');
  const over: Array<number> = glowHueSatOf(0, 120, 60);
  assertEq(1, over[1], '越界钳满');
  assertEq(0.25, over[0], '+y = 90° = 黄绿象限');
  const r0: Array<number> = glowHueSatOf(5, 5, 0);
  assertEq(0, r0[0], 'radius 非法 = 圆心');
});

test('HSV→ARGB 六分支与灰轴', () => {
  assertEq(0xFFFF0000, glowArgbFromHsv(0, 1, 1), '红');
  assertEq(0xFF00FF00, glowArgbFromHsv(1 / 3, 1, 1), '绿');
  assertEq(0xFF0000FF, glowArgbFromHsv(2 / 3, 1, 1), '蓝');
  assertEq(0xFFFFFFFF, glowArgbFromHsv(0.9, 0, 1), 's=0 = 白（灰轴无色相）');
  assertEq(0xFF000000, glowArgbFromHsv(0.5, 1, 0), 'v=0 = 黑');
  assertEq(0xFF808080, glowArgbFromHsv(0.5, 0, 0.5), '中灰');
});

test('色盘落点：彩色有圈位、白/灰回圆心', () => {
  const red: Array<number> = glowWheelMarkerOf(0xFFFF0000);
  assertTrue(Math.abs(red[0] - 1) < 1e-6 && Math.abs(red[1]) < 1e-6, '红在 +x 半径');
  assertEq(1, red[2], '饱和度 1');
  const white: Array<number> = glowWheelMarkerOf(0xFFFFFFFF);
  assertEq(0, white[0], '白无色相 ⇒ 圆心');
  assertEq(0, white[1]);
  const gray: Array<number> = glowWheelMarkerOf(0xFF808080);
  assertEq(0, gray[2], '灰 = 饱和度 0');
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
