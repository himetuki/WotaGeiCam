/**
 * CPU 整像素选择（record/ArcRepairMerge.ets）单测——对拍上游
 * app/src/test/java/com/wotagei/cam/ArcRepairMergeParityTest.kt 的 CPU 面（对齐上游 38d712a）。
 *
 * 12 条：五色不造色、D=0 仍整像素、珊瑚粉突变锚、死区档 0/12/32、Δ 三分支、
 * 块内最大 Δ 过门/不过门、无符号 10 vs 200、最大 vs 平均、4×2 多块、2×4 多块、奇数宽高抛错。
 * 颜色用例**不近似**：输出 YUV 必须严格等于某一输入帧原值；RGB 期望值含手算锚
 * （珊瑚粉 255,109,158）。平台执行器留 T29。
 */
import { ArcMergeDeadband } from '../../entry/src/main/ets/core/Constants';
import { ArcRepairMerge } from '../../entry/src/main/ets/record/ArcRepairMerge';

/** RGB 三元组 */
class Rgb {
  readonly r: number;
  readonly g: number;
  readonly b: number;

  constructor(r: number, g: number, b: number) {
    this.r = r;
    this.g = g;
    this.b = b;
  }
}

/** YUV 三元组（BT.601 全范围） */
class Yuv {
  readonly y: number;
  readonly u: number;
  readonly v: number;

  constructor(y: number, u: number, v: number) {
    this.y = y;
    this.u = u;
    this.v = v;
  }

  same(other: Yuv): boolean {
    return this.y === other.y && this.u === other.u && this.v === other.v;
  }
}

/** roundToInt（四舍五入，half up；Kotlin roundToInt 口径） */
function roundToInt(x: number): number {
  return Math.floor(x + 0.5);
}

function clamp8(x: number): number {
  return Math.min(255, Math.max(0, x));
}

/** BT.601 全范围 RGB→YUV（只用于把颜色送进 CPU 的 YUV 域） */
function rgbToYuv(c: Rgb): Yuv {
  return new Yuv(
    clamp8(roundToInt(0.299 * c.r + 0.587 * c.g + 0.114 * c.b)),
    clamp8(roundToInt(-0.168736 * c.r - 0.331264 * c.g + 0.5 * c.b + 128)),
    clamp8(roundToInt(0.5 * c.r - 0.418688 * c.g - 0.081312 * c.b + 128))
  );
}

/** BT.601 全范围 YUV→RGB（与上行同系数；珊瑚粉锚靠它手算） */
function yuvToRgb(p: Yuv): Rgb {
  const u: number = p.u - 128;
  const v: number = p.v - 128;
  return new Rgb(
    clamp8(roundToInt(p.y + 1.402 * v)),
    clamp8(roundToInt(p.y - 0.344136 * u - 0.714136 * v)),
    clamp8(roundToInt(p.y + 1.772 * u))
  );
}

function sameRgb(a: Rgb, b: Rgb): boolean {
  return a.r === b.r && a.g === b.g && a.b === b.b;
}

/** 把一枚颜色铺满 2×2 块，调用生产算子；返回选择后的 YUV */
function cpuSelect(a: Rgb, b: Rgb, deadband: number): Yuv {
  const ya: Yuv = rgbToYuv(a);
  const yb: Yuv = rgbToYuv(b);
  const aY: Uint8Array = new Uint8Array([ya.y, ya.y, ya.y, ya.y]);
  const bY: Uint8Array = new Uint8Array([yb.y, yb.y, yb.y, yb.y]);
  const aU: Uint8Array = new Uint8Array([ya.u]);
  const bU: Uint8Array = new Uint8Array([yb.u]);
  const aV: Uint8Array = new Uint8Array([ya.v]);
  const bV: Uint8Array = new Uint8Array([yb.v]);
  ArcRepairMerge.selectInPlace(aY, aU, aV, bY, bU, bV, 2, 2, deadband);
  return new Yuv(aY[0], aU[0], aV[0]);
}

/** 旧缺陷算子（突变体，非生产路径）：Y/U/V 逐平面取大。只为证明造色断言对算子敏感 */
function mutatePerPlaneMax(a: Rgb, b: Rgb): Yuv {
  const ya: Yuv = rgbToYuv(a);
  const yb: Yuv = rgbToYuv(b);
  return new Yuv(Math.max(ya.y, yb.y), Math.max(ya.u, yb.u), Math.max(ya.v, yb.v));
}

/** 用户点名的五组颜色（背景 a 在前、压上来的光 b 在后） */
function colorCases(): Array<Array<Rgb>> {
  return [
    [new Rgb(0, 0, 0), new Rgb(255, 140, 0)],   // 极橙
    [new Rgb(0, 0, 0), new Rgb(120, 220, 255)], // 冰蓝
    [new Rgb(0, 0, 0), new Rgb(40, 40, 220)],   // 深蓝紫
    [new Rgb(0, 0, 0), new Rgb(255, 180, 200)], // 樱粉
    [new Rgb(20, 20, 90), new Rgb(255, 140, 0)] // 橙叠偏蓝背景
  ];
}

function assertU8Eq(expected: Array<number>, actual: Uint8Array, msg: string): void {
  assertEq(expected.length, actual.length, `${msg} 长度`);
  for (let i: number = 0; i < expected.length; i++) {
    assertEq(expected[i], actual[i], `${msg} 第 ${i} 字节`);
  }
}

/** 独立参考：按 4:2:0 的 2×2 块逐块取 4 个 Y 的无符号最大 Δ，命中则整块搬运 */
function referenceBlockSelect(
  aY: Uint8Array, aU: Uint8Array, aV: Uint8Array,
  bY: Uint8Array, bU: Uint8Array, bV: Uint8Array,
  width: number, height: number, deadband: number
): Array<Uint8Array> {
  const y: Uint8Array = new Uint8Array(aY);
  const u: Uint8Array = new Uint8Array(aU);
  const v: Uint8Array = new Uint8Array(aV);
  const cw: number = width / 2;
  const ch: number = height / 2;
  for (let by: number = 0; by < ch; by++) {
    for (let bx: number = 0; bx < cw; bx++) {
      const idx: Array<number> = [
        bx * 2 + by * 2 * width, bx * 2 + 1 + by * 2 * width,
        bx * 2 + (by * 2 + 1) * width, bx * 2 + 1 + (by * 2 + 1) * width
      ];
      let delta: number = -255;
      for (const at of idx) {
        const d: number = bY[at] - aY[at];
        if (d > delta) {
          delta = d;
        }
      }
      if (delta > deadband) {
        for (const at of idx) {
          y[at] = bY[at];
        }
        const c: number = bx + by * cw;
        u[c] = bU[c];
        v[c] = bV[c];
      }
    }
  }
  return [y, u, v];
}

test('不造色 五种颜色输出严格等于某个输入像素', (): void => {
  for (const pair of colorCases()) {
    const a: Rgb = pair[0];
    const b: Rgb = pair[1];
    const ya: Yuv = rgbToYuv(a);
    const yb: Yuv = rgbToYuv(b);
    const cpu: Yuv = cpuSelect(a, b, ArcMergeDeadband.DEFAULT);
    assertTrue(cpu.same(ya) || cpu.same(yb),
      `CPU YUV 必须严格等于 a 或 b 的原值，实际 (${cpu.y},${cpu.u},${cpu.v})`);
    const out: Rgb = yuvToRgb(cpu);
    assertTrue(sameRgb(out, yuvToRgb(ya)) || sameRgb(out, yuvToRgb(yb)),
      `CPU 还原 RGB 必须严格等于同一输入帧（实际 ${out.r},${out.g},${out.b}）`);
  }
});

test('不造色 D为0仍只做整像素选择', (): void => {
  for (const pair of colorCases()) {
    const a: Rgb = pair[0];
    const b: Rgb = pair[1];
    const cpu: Yuv = cpuSelect(a, b, 0);
    assertTrue(cpu.same(rgbToYuv(a)) || cpu.same(rgbToYuv(b)),
      'D=0 仍必须落在输入帧上');
  }
});

test('尺子能红 逐平面取大把极橙与黑并成珊瑚粉', (): void => {
  const a: Rgb = new Rgb(0, 0, 0);
  const b: Rgb = new Rgb(255, 140, 0);
  // 生产算子：必须等于某一输入帧（不造色）
  const good: Yuv = cpuSelect(a, b, ArcMergeDeadband.DEFAULT);
  assertTrue(good.same(rgbToYuv(a)) || good.same(rgbToYuv(b)), '生产选择式严格落在输入帧上');
  // 突变回旧缺陷：三平面各取大，蓝通道凭空抬高
  const mutated: Yuv = mutatePerPlaneMax(a, b);
  assertFalse(mutated.same(rgbToYuv(a)) || mutated.same(rgbToYuv(b)),
    '逐平面取大必须造出输入帧没有的 YUV');
  const pink: Rgb = yuvToRgb(mutated);
  assertEq(255, pink.r, '珊瑚粉 R');
  assertEq(109, pink.g, '珊瑚粉 G');
  assertEq(158, pink.b, '珊瑚粉 B（蓝通道凭空 +158）');
});

test('死区档位钳在0到32', (): void => {
  assertEq(0, ArcMergeDeadband.clamp(-7), '负值归 0');
  assertEq(0, ArcMergeDeadband.clamp(0), '0 保持');
  assertEq(12, ArcMergeDeadband.DEFAULT, '默认 12');
  assertEq(32, ArcMergeDeadband.clamp(33), '超界归 32');
  assertEq(32, ArcMergeDeadband.clamp(32), '32 保持');
});

test('死区 亮度差不超过D保持保留帧 超过D才取被抽帧', (): void => {
  const a: Rgb = new Rgb(100, 100, 100);
  const below: Rgb = new Rgb(108, 108, 108);   // Δ=8 ≤ 12
  const boundary: Rgb = new Rgb(112, 112, 112); // Δ=12 == D：判据是 >，必须保持 a
  const above: Rgb = new Rgb(120, 120, 120);   // Δ=20 > 12
  assertTrue(cpuSelect(a, below, 12).same(rgbToYuv(a)), 'Δ<D 保持 a');
  assertTrue(cpuSelect(a, boundary, 12).same(rgbToYuv(a)), 'Δ==D 保持 a（> 不是 >=）');
  assertTrue(cpuSelect(a, above, 12).same(rgbToYuv(above)), 'Δ>D 整块取 b');
});

test('CPU 块内最大Δ过门时4Y与UV整块取自被抽帧', (): void => {
  const aY: Uint8Array = new Uint8Array([10, 10, 10, 10]);
  const aU: Uint8Array = new Uint8Array([50]);
  const aV: Uint8Array = new Uint8Array([60]);
  // 只有第一颗 Y 的 Δ=90 过门，其余 3 颗 Δ=2；块决策取最大值 ⇒ 整块 6 个样本全取 b
  const bY: Uint8Array = new Uint8Array([100, 12, 12, 12]);
  const bU: Uint8Array = new Uint8Array([200]);
  const bV: Uint8Array = new Uint8Array([210]);
  ArcRepairMerge.selectInPlace(aY, aU, aV, bY, bU, bV, 2, 2, 12);
  assertU8Eq([100, 12, 12, 12], aY, '4 个 Y 整块取 b');
  assertU8Eq([200], aU, 'U 与 Y 同取自 b');
  assertU8Eq([210], aV, 'V 与 Y 同取自 b');
});

test('CPU 块内最大Δ不超D时整块保持保留帧', (): void => {
  const aY: Uint8Array = new Uint8Array([10, 10, 10, 10]);
  const aU: Uint8Array = new Uint8Array([50]);
  const aV: Uint8Array = new Uint8Array([60]);
  const bY: Uint8Array = new Uint8Array([20, 21, 20, 21]); // 最大 Δ=11 ≤ 12
  const bU: Uint8Array = new Uint8Array([200]);
  const bV: Uint8Array = new Uint8Array([210]);
  ArcRepairMerge.selectInPlace(aY, aU, aV, bY, bU, bV, 2, 2, 12);
  assertU8Eq([10, 10, 10, 10], aY, 'Y 保持 a');
  assertU8Eq([50], aU, 'U 保持 a');
  assertU8Eq([60], aV, 'V 保持 a');
});

test('CPU 无符号比较 10与200必须按200更大处理', (): void => {
  const aY: Uint8Array = new Uint8Array([10, 10, 10, 10]);
  const bY: Uint8Array = new Uint8Array([200, 200, 200, 200]);
  const aU: Uint8Array = new Uint8Array([50]);
  const aV: Uint8Array = new Uint8Array([60]);
  const bU: Uint8Array = new Uint8Array([210]);
  const bV: Uint8Array = new Uint8Array([220]);
  ArcRepairMerge.selectInPlace(aY, aU, aV, bY, bU, bV, 2, 2, 12);
  // 有符号比较下 200=-56，Δ=-66 永远不过门；Uint8Array 读取天然无符号，必须过门
  assertU8Eq([200, 200, 200, 200], aY, '无符号 Δ=190 必须过门');
  assertU8Eq([210], aU, 'U 跟随');
  assertU8Eq([220], aV, 'V 跟随');
});

test('CPU 块内最大Δ过门 平均值不过门时仍整块取被抽帧', (): void => {
  const aY: Uint8Array = new Uint8Array([10, 10, 10, 10]);
  const aU: Uint8Array = new Uint8Array([50]);
  const aV: Uint8Array = new Uint8Array([60]);
  // Δ 分布 (20,0,0,0)：最大 20>12 过门，平均 5≤12 不过门 ⇒ 改成平均判定必红
  const bY: Uint8Array = new Uint8Array([30, 10, 10, 10]);
  const bU: Uint8Array = new Uint8Array([210]);
  const bV: Uint8Array = new Uint8Array([220]);
  ArcRepairMerge.selectInPlace(aY, aU, aV, bY, bU, bV, 2, 2, 12);
  assertU8Eq([30, 10, 10, 10], aY, '块内最大值才是判据（平均会把细弧吃掉）');
  assertU8Eq([210], aU, 'U 跟随');
  assertU8Eq([220], aV, 'V 跟随');
});

test('CPU 多块索引与独立参考逐块一致 4x2', (): void => {
  // 4×2：横向 2 块（行跨度 = width），块 0 过门、块 1 不过门
  const aY: Uint8Array = new Uint8Array([10, 10, 200, 200, 10, 10, 200, 200]);
  const bY: Uint8Array = new Uint8Array([30, 30, 100, 100, 30, 30, 100, 100]);
  const aU: Uint8Array = new Uint8Array([50, 60]);
  const bU: Uint8Array = new Uint8Array([210, 220]);
  const aV: Uint8Array = new Uint8Array([70, 80]);
  const bV: Uint8Array = new Uint8Array([230, 240]);
  const expected: Array<Uint8Array> =
    referenceBlockSelect(aY, aU, aV, bY, bU, bV, 4, 2, 12);
  ArcRepairMerge.selectInPlace(aY, aU, aV, bY, bU, bV, 4, 2, 12);
  assertU8Eq(Array.from(expected[0]), aY, '4×2 Y 与逐块参考一致');
  assertU8Eq(Array.from(expected[1]), aU, '4×2 U 按块跟随');
  assertU8Eq(Array.from(expected[2]), aV, '4×2 V 按块跟随');
});

test('CPU 多块索引与独立参考逐块一致 2x4', (): void => {
  // 2×4：纵向 2 块（块步进 = width*2），块 0 过门、块 1 不过门
  const aY: Uint8Array = new Uint8Array([10, 10, 10, 10, 200, 200, 200, 200]);
  const bY: Uint8Array = new Uint8Array([30, 30, 30, 30, 100, 100, 100, 100]);
  const aU: Uint8Array = new Uint8Array([50, 60]);
  const bU: Uint8Array = new Uint8Array([210, 220]);
  const aV: Uint8Array = new Uint8Array([70, 80]);
  const bV: Uint8Array = new Uint8Array([230, 240]);
  const expected: Array<Uint8Array> =
    referenceBlockSelect(aY, aU, aV, bY, bU, bV, 2, 4, 12);
  ArcRepairMerge.selectInPlace(aY, aU, aV, bY, bU, bV, 2, 4, 12);
  assertU8Eq(Array.from(expected[0]), aY, '2×4 Y 与逐块参考一致');
  assertU8Eq(Array.from(expected[1]), aU, '2×4 U 按块跟随');
  assertU8Eq(Array.from(expected[2]), aV, '2×4 V 按块跟随');
});

test('CPU 奇数宽高明确拒绝而不是静默截断', (): void => {
  const y: Uint8Array = new Uint8Array(3);
  const uv: Uint8Array = new Uint8Array(1);
  let threw: boolean = false;
  try {
    ArcRepairMerge.selectInPlace(y, uv, uv, y, uv, uv, 3, 1, 12);
  } catch (e) {
    threw = true;
  }
  assertTrue(threw, '奇数宽高必须抛错');
  // 平面不足同样抛错（防御性断言）
  let threwShort: boolean = false;
  try {
    ArcRepairMerge.selectInPlace(new Uint8Array(3), new Uint8Array(1), new Uint8Array(1),
      new Uint8Array(4), new Uint8Array(1), new Uint8Array(1), 2, 2, 12);
  } catch (e) {
    threwShort = true;
  }
  assertTrue(threwShort, 'Y 平面不足必须抛错');
});

test('尺子能红 GPU逐通道取大把偏蓝背景与橙拼成中间色', (): void => {
  // T28 审查补：CPU 侧已钉「逐平面取大造色」，GPU 侧需同样一条反向断言（上游同款）。
  // GPU 口径离线复刻：luma 差分整像素选择（与 shaders.h 的 ARC_MERGE_FS 同式）。
  const bg: Rgb = new Rgb(20, 20, 90);
  const arc: Rgb = new Rgb(255, 140, 0);
  const la: number = 0.299 * bg.r + 0.587 * bg.g + 0.114 * bg.b;
  const lb: number = 0.299 * arc.r + 0.587 * arc.g + 0.114 * arc.b;
  const deadband: number = ArcMergeDeadband.DEFAULT;
  const good: Rgb = (lb - la > deadband) ? arc : bg;
  assertTrue(sameRgb(good, bg) || sameRgb(good, arc), '生产选择式必须落在输入像素上');
  // 突变回旧 GPU 口径：RGB 逐通道取大 → (255,140,90)，既不是背景也不是橙
  const mutated: Rgb = new Rgb(Math.max(bg.r, arc.r), Math.max(bg.g, arc.g), Math.max(bg.b, arc.b));
  assertEq(255, mutated.r, '逐通道取大 R');
  assertEq(140, mutated.g, '逐通道取大 G');
  assertEq(90, mutated.b, '逐通道取大 B（蓝通道被背景抬高 +90）');
  assertFalse(sameRgb(mutated, bg) || sameRgb(mutated, arc),
    '逐通道取大必须造出输入像素没有的中间色');
  // 珊瑚粉锚同样不落在输入上（CPU 逐平面取大产物），两条路都不许造色
  const pink: Rgb = new Rgb(255, 109, 158);
  assertFalse(sameRgb(pink, bg) || sameRgb(pink, arc), '珊瑚粉不是任一输入颜色');
});
