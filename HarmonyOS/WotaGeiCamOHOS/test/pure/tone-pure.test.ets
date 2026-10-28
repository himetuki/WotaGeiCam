/**
 * 色调（Tone）纯逻辑全表——对拍主仓 Android `core/ToneParams.kt`（66f88e0）。
 *
 * 判据（29 §4.5 等价口径）：
 * ① 编码往返逐字段一致、坏串整串回默认；
 * ② 域钳（越界/NaN/Infinity）与黑白点反序输入被钳——有效黑点恒小于有效白点；
 * ③ **恒等参数 LUT 逐字节不变**（守卫钉）；非恒等时表必须变化且不修改入参；
 * ④ 传递函数单调（极值参数下 256 点逐通道非降）；端点 0/1 与电平/阴影方向对齐安卓。
 */
import {
  TONE_BLACK_MAX, TONE_DEFAULT, TONE_HIGHLIGHTS_MAX, TONE_HIGHLIGHT_GAIN, TONE_SHADOWS_MAX, TONE_WHITE_MAX,
  ToneParams, applyToneToLut, toneDecode, toneEncode
} from '../../entry/src/main/ets/core/Tone';

/** 768 字节交错 RGB 灰阶斜坡：R=G=B=i */
function rampLut(): Uint8Array {
  const lut: Uint8Array = new Uint8Array(768);
  for (let i: number = 0; i < 256; i++) {
    lut[3 * i] = i;
    lut[3 * i + 1] = i;
    lut[3 * i + 2] = i;
  }
  return lut;
}

function near(expected: number, actual: number, msg: string): void {
  assertTrue(Math.abs(expected - actual) <= 1e-6, `${msg}: expected ${expected} but got ${actual}`);
}

// ------------------------------------------------------------------ 参数模型与 sanitize

test('出厂默认四项全0且恒等', () => {
  assertEq(0, TONE_DEFAULT.black);
  assertEq(0, TONE_DEFAULT.white);
  assertEq(0, TONE_DEFAULT.shadows);
  assertEq(0, TONE_DEFAULT.highlights);
  assertTrue(TONE_DEFAULT.isIdentity(), '默认必须恒等（不烘焙的唯一依据）');
  assertTrue(TONE_DEFAULT.sanitize().sameAs(TONE_DEFAULT), '域内值 sanitize 不动');
});

test('sanitize把越界与NaN收进产品域', () => {
  const wild: ToneParams = new ToneParams({ black: 9, white: -9, shadows: 3 }).sanitize();
  assertEq(TONE_BLACK_MAX, wild.black);
  assertEq(-TONE_WHITE_MAX, wild.white);
  assertEq(TONE_SHADOWS_MAX, wild.shadows);
  const nan: ToneParams = new ToneParams({ black: NaN, white: Infinity, shadows: -Infinity }).sanitize();
  assertEq(0, nan.black, 'NaN 回 0（对齐安卓 sanitizeRange）');
  assertEq(TONE_WHITE_MAX, nan.white, '+Infinity 收到上限');
  assertEq(-TONE_SHADOWS_MAX, nan.shadows, '-Infinity 收到下限');
  assertTrue(wild.sameAs(wild.sanitize()), 'sanitize 幂等');
});

test('黑白反序输入被钳_有效黑点恒小于有效白点', () => {
  // 原始反序：黑点位置 0.9，白点位置 1-0.9=0.1（white 电平 0.9 把白点压到黑点之下）
  const rawBlack: number = 0.9;
  const rawWhiteLevel: number = 0.9;
  assertTrue(1 - rawWhiteLevel < rawBlack, '先证明输入确实是 white<black');
  const fixed: ToneParams = new ToneParams({ black: rawBlack, white: rawWhiteLevel }).sanitize();
  assertTrue(fixed.blackPoint() < fixed.whitePoint(), '反序输入必须被钳成有效黑点<有效白点');
  assertEq(TONE_BLACK_MAX, fixed.black);
  assertEq(TONE_WHITE_MAX, fixed.white);

  // 极端/反序/坏值遍历：钳制后恒有有效黑点<有效白点（分母不为零）
  const raws: Array<ToneParams> = [
    new ToneParams({ black: 9, white: 9 }),
    new ToneParams({ black: -9, white: -9 }),
    new ToneParams({ black: 9, white: -9 }),
    new ToneParams({ black: -9, white: 9 }),
    new ToneParams({ black: NaN, white: NaN }),
    new ToneParams({ black: TONE_BLACK_MAX, white: TONE_WHITE_MAX })
  ];
  for (const raw of raws) {
    const p: ToneParams = raw.sanitize();
    assertTrue(p.blackPoint() < p.whitePoint(), `钳后必须有序：black=${p.black} white=${p.white}`);
  }
});

// ------------------------------------------------------------------ 编码 / 解码

test('编码解码往返逐字段一致', () => {
  const samples: Array<ToneParams> = [
    TONE_DEFAULT,
    new ToneParams({ black: 0.1, white: 0.2, shadows: -0.5 }),
    new ToneParams({ black: -0.35, white: 0.35, shadows: 1 }),
    new ToneParams({ black: 0.07, white: 0.13, shadows: 0.37 })
  ];
  for (const p of samples) {
    const round: ToneParams = toneDecode(toneEncode(p));
    assertTrue(p.sanitize().sameAs(round), 'encode→decode 必须逐字段往返');
  }
});

test('坏串一律回出厂默认', () => {
  const bad: Array<string | undefined> = [
    undefined,
    '',
    '   ',
    'garbage',
    'black=0.1;white=0.2',
    'black=0.1;white=0.2;shadows=x',
    'black=0.1;white=0.2;shadows=',
    'black=1x;white=0;shadows=0'
  ];
  for (const raw of bad) {
    assertTrue(toneDecode(raw).sameAs(TONE_DEFAULT), `坏串必须整串回默认：${String(raw)}`);
  }
  // 未知键忽略且不影响已识别三项（前向兼容）
  const more: ToneParams = toneDecode(toneEncode(new ToneParams({ shadows: 0.3 })) + ';future=9');
  assertTrue(more.sameAs(new ToneParams({ shadows: 0.3 })), '未知键必须忽略');
  // 越界数值走 sanitize 收域（不整串回默认）
  const over: ToneParams = toneDecode('black=9;white=-9;shadows=5');
  assertEq(TONE_BLACK_MAX, over.black);
  assertEq(-TONE_WHITE_MAX, over.white);
  assertEq(TONE_SHADOWS_MAX, over.shadows);
});

// ------------------------------------------------------------------ LUT 烘焙

test('恒等参数LUT逐字节不变', () => {
  // 故意用非单调的任意字节表：恒等守卫必须连一个字节都不动
  const lut: Uint8Array = new Uint8Array(768);
  for (let i: number = 0; i < lut.length; i++) {
    lut[i] = (i * 37 + 11) & 0xFF;
  }
  const out: Uint8Array = applyToneToLut(lut, TONE_DEFAULT);
  assertEq(lut.length, out.length, '入出同长');
  for (let i: number = 0; i < lut.length; i++) {
    assertEq(lut[i], out[i], `恒等参数第 ${i} 字节必须一致`);
  }
  const explicit: Uint8Array =
    applyToneToLut(lut, new ToneParams({ black: 0, white: 0, shadows: 0 }));
  for (let i: number = 0; i < lut.length; i++) {
    assertEq(lut[i], explicit[i], `显式恒等第 ${i} 字节必须一致`);
  }
});

test('非恒等烘焙不修改入参且入出同长', () => {
  const lut: Uint8Array = rampLut();
  const before: Uint8Array = new Uint8Array(lut.length);
  before.set(lut);
  const out: Uint8Array = applyToneToLut(lut, new ToneParams({ black: 0.2, shadows: 0.5 }));
  assertEq(lut.length, out.length);
  for (let i: number = 0; i < lut.length; i++) {
    assertEq(before[i], lut[i], '入参必须保持原样');
  }
  assertFalse(out[0] === lut[0] && out[255 * 3] === lut[255 * 3], '非恒等必须改变表（端点方向见下一条）');
});

test('LUT逐通道单调_极值参数下仍非降', () => {
  const cases: Array<ToneParams> = [
    new ToneParams({ black: TONE_BLACK_MAX, white: TONE_WHITE_MAX, shadows: 1 }),
    new ToneParams({ black: TONE_BLACK_MAX, white: TONE_WHITE_MAX, shadows: -1 }),
    new ToneParams({ black: -TONE_BLACK_MAX, white: -TONE_WHITE_MAX, shadows: -1 }),
    new ToneParams({ black: 0.2, white: 0.1, shadows: 0 }),
    // 第四轴极值：阴影与高光反向叠加是导数下界 0.5 的最坏组合
    new ToneParams({ black: TONE_BLACK_MAX, white: TONE_WHITE_MAX, shadows: 1, highlights: -1 }),
    new ToneParams({ black: TONE_BLACK_MAX, white: TONE_WHITE_MAX, shadows: -1, highlights: 1 })
  ];
  for (const p of cases) {
    const out: Uint8Array = applyToneToLut(rampLut(), p);
    for (let c: number = 0; c < 3; c++) {
      for (let i: number = 0; i < 255; i++) {
        assertTrue(out[3 * (i + 1) + c] >= out[3 * i + c],
          `black=${p.black} white=${p.white} shadows=${p.shadows} 通道 ${c} 第 ${i}→${i + 1} 点下降`);
      }
    }
  }
});

test('端点与电平方向对齐安卓', () => {
  assertEq(0, TONE_DEFAULT.apply(0));
  assertEq(1, TONE_DEFAULT.apply(1));
  // 黑点抬黑：黑点以下剪到 0，中灰按 (x-b)/(1-b) 重映射（电平语义）
  const black: ToneParams = new ToneParams({ black: 0.2 });
  assertEq(0, black.apply(0.1));
  near(0.375, black.apply(0.5), 'black=0.2 在 x=0.5 的线性重映射');
  // 白点压白：白点以上钳到 1，中灰按 x/(1-w) 重映射
  const white: ToneParams = new ToneParams({ white: 0.2 });
  assertEq(1, white.apply(0.9));
  near(0.625, white.apply(0.5), 'white=0.2 在 x=0.5 的线性重映射');
  // 端点恒等：任何三项域内参数在 0/1 处都不外溢
  assertEq(0, new ToneParams({ black: 0.2, shadows: -1 }).apply(0));
  assertEq(1, new ToneParams({ white: 0.2, shadows: 1 }).apply(1));
});

test('阴影正抬暗部负压暗部', () => {
  const lift: ToneParams = new ToneParams({ shadows: 0.5 });
  const press: ToneParams = new ToneParams({ shadows: -0.5 });
  assertTrue(lift.apply(0.25) > 0.25, '正阴影必须抬暗部');
  assertTrue(press.apply(0.25) < 0.25, '负阴影必须压暗部');
  assertTrue(lift.apply(0.75) > 0.75, '正阴影在亮部也有抬升（(1-y)² 尾项）');
  near(1, lift.apply(1), '白端不受阴影影响');
});

test('LUT方向_抬黑剪暗_压白剪亮_阴影提暗', () => {
  const base: Uint8Array = rampLut();
  const crushed: Uint8Array = applyToneToLut(base, new ToneParams({ black: 0.2 }));
  assertEq(0, crushed[25 * 3], 'x≈0.098 < 黑点 0.2：R 通道剪到 0');
  assertTrue(crushed[130 * 3] < base[130 * 3], '黑点以上中灰相对变暗');
  const pressed: Uint8Array = applyToneToLut(base, new ToneParams({ white: 0.2 }));
  assertEq(255, pressed[230 * 3], 'x≈0.902 > 白点 0.8：R 通道钳到 255');
  assertEq(255, pressed[255 * 3], '白端保持 255');
  const lifted: Uint8Array = applyToneToLut(base, new ToneParams({ shadows: 0.5 }));
  assertTrue(lifted[25 * 3] > base[25 * 3], '正阴影把暗部字节抬高');
  assertTrue(lifted[0] > 0, '正阴影抬黑场（(1-y)² 尾项在 y=0 处仍生效）');
});

test('尾字节与非768长度防御', () => {
  assertEq(0, applyToneToLut(new Uint8Array(0), new ToneParams({ shadows: 1 })).length);
  const odd: Uint8Array = new Uint8Array([1, 2, 3, 4, 5, 6, 7]);
  const out: Uint8Array = applyToneToLut(odd, new ToneParams({ black: 0.2 }));
  assertEq(odd.length, out.length, '入出同长');
  assertEq(7, out[6], '尾部不足一组的字节原样保留');
  assertEq(0, out[0], '首组照常映射');
});

// ------------------------------------------------------------------ 第四轴：高光

test('高光编码往返与域钳', () => {
  const samples: Array<ToneParams> = [
    new ToneParams({ highlights: 0.42 }),
    new ToneParams({ black: 0.1, white: 0.2, shadows: -0.5, highlights: 0.75 }),
    new ToneParams({ highlights: -TONE_HIGHLIGHTS_MAX })
  ];
  for (const p of samples) {
    assertTrue(p.sanitize().sameAs(toneDecode(toneEncode(p))),
      '含高光的 encode→decode 必须逐字段往返');
  }
  const wild: ToneParams = new ToneParams({ highlights: 9 }).sanitize();
  assertEq(TONE_HIGHLIGHTS_MAX, wild.highlights);
  assertEq(-TONE_HIGHLIGHTS_MAX, new ToneParams({ highlights: -9 }).sanitize().highlights);
  assertEq(0, new ToneParams({ highlights: NaN }).sanitize().highlights, 'NaN 回 0');
  assertEq(TONE_HIGHLIGHTS_MAX, new ToneParams({ highlights: Infinity }).sanitize().highlights,
    '+Infinity 收到上限');
  assertEq(-TONE_HIGHLIGHTS_MAX, new ToneParams({ highlights: -Infinity }).sanitize().highlights,
    '-Infinity 收到下限');
  assertTrue(wild.sameAs(wild.sanitize()), 'sanitize 幂等');
  assertEq(-TONE_HIGHLIGHTS_MAX, toneDecode('black=0;white=0;shadows=0;highlights=-9').highlights,
    '串内越界高光走 sanitize 收域而非回默认');
});

test('isIdentity与sameAs包含高光轴', () => {
  const onlyHigh: ToneParams = new ToneParams({ highlights: 0.01 });
  assertFalse(onlyHigh.isIdentity(), '仅高光非 0 就不是恒等');
  assertFalse(onlyHigh.sameAs(TONE_DEFAULT), 'sameAs 必须区分高光轴');
  assertTrue(onlyHigh.sameAs(new ToneParams({ highlights: 0.01 })));
  assertFalse(new ToneParams({ highlights: 0.01 }).sameAs(new ToneParams({ highlights: 0.02 })),
    '高光不同即不相等');
  // 仅高光也必须触发烘焙（不能走恒等提前返回）
  const base: Uint8Array = rampLut();
  const out: Uint8Array = applyToneToLut(base, new ToneParams({ highlights: 0.5 }));
  assertTrue(out[200 * 3] > base[200 * 3], '仅高光参数必须实际改变 LUT');
  assertEq(base[0], out[0], '正高光在 y=0 处增量为 0');
});

test('高光正抬亮部负压高光_暗部影响更小_阴影后应用', () => {
  const lift: ToneParams = new ToneParams({ highlights: 0.5 });
  const press: ToneParams = new ToneParams({ highlights: -0.5 });
  const darkX: number = 0.1;
  const brightX: number = 0.8;
  const darkDelta: number = lift.apply(darkX) - darkX;
  const brightDelta: number = lift.apply(brightX) - brightX;
  near(TONE_HIGHLIGHT_GAIN * 0.5 * darkX * darkX, darkDelta, '暗部提升 = 0.25·h·y²');
  near(TONE_HIGHLIGHT_GAIN * 0.5 * brightX * brightX, brightDelta, '亮部提升 = 0.25·h·y²');
  assertTrue(brightDelta > 0, '正高光必须抬亮部');
  assertTrue(darkDelta > 0, '正高光对暗部也有提升');
  assertTrue(darkDelta < brightDelta, '暗部影响必须小于亮部（y² 权重）');
  assertTrue(press.apply(brightX) < brightX, '负高光必须压暗高光');
  assertTrue(Math.abs(press.apply(darkX) - darkX) < Math.abs(press.apply(brightX) - brightX),
    '负高光同样是亮部影响更大');
  assertEq(0, lift.apply(0), '黑端不受高光影响');
  assertEq(1, lift.apply(1), '白端正高光钳在 1');
  near(0.875, press.apply(1), '白端负高光按 y² 压暗（与安卓同式）');
  assertEq(0, press.apply(0), '黑端负高光增量为 0');
  // 顺序：先阴影（改写 y）后高光（在改写后的 y 上算 y²），与安卓一致
  const both: ToneParams = new ToneParams({ shadows: -1, highlights: 1 });
  const yAfterShadows: number = 0.5 + 0.25 * (-1) * (1 - 0.5) * (1 - 0.5);
  near(yAfterShadows + TONE_HIGHLIGHT_GAIN * yAfterShadows * yAfterShadows, both.apply(0.5),
    '高光必须作用在阴影之后的 y 上');
  // LUT 方向：正高光抬亮部、不动黑场
  const base: Uint8Array = rampLut();
  const high: Uint8Array = applyToneToLut(base, lift);
  assertTrue(high[200 * 3] > base[200 * 3], '正高光把亮部字节抬高');
  assertEq(base[0], high[0], '黑场字节不变');
  const highPressed: Uint8Array = applyToneToLut(base, press);
  assertTrue(highPressed[200 * 3] < base[200 * 3], '负高光把亮部字节压低');
});

test('旧串缺highlights键按0兼容读入', () => {
  const legacy: string = 'black=0.1;white=0.2;shadows=-0.5';
  const p: ToneParams = toneDecode(legacy);
  assertTrue(p.sameAs(new ToneParams({ black: 0.1, white: 0.2, shadows: -0.5 })),
    '旧串缺键按 0 读入，其余字段照常');
  assertEq(0, p.highlights, '旧串高光语义就是 0');
  assertEq('black=0.1;white=0.2;shadows=-0.5;highlights=0', toneEncode(p),
    '再编码应补出显式 highlights 键');
  // 前向兼容只放宽「缺键」，键在但值坏仍是坏串，整串回默认
  assertTrue(toneDecode('black=0;white=0;shadows=0;highlights=').sameAs(TONE_DEFAULT),
    '空值仍算坏串');
  assertTrue(toneDecode('black=0;white=0;shadows=0;highlights=x').sameAs(TONE_DEFAULT),
    '非数仍算坏串');
  assertTrue(toneDecode('black=0;white=0;shadows=0;highlights=1x').sameAs(TONE_DEFAULT),
    '半数值仍算坏串');
});
