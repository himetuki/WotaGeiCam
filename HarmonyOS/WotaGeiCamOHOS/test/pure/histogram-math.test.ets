/**
 * 直方图分箱与归一（对齐上游 HistogramMathTest.kt 5 例）+ 数据路/接线守卫。
 *
 * 数据路（上游 f31d18c 的 OHOS 对应物）：GL 线程 64×64 RGBA 读回 → native
 * BinHistogramRgba 近地分箱（与 core/HistogramMath.binRgba 同式镜像，契约两处出现）
 * → napi 出 192 int → 弹窗 150ms 轮询 → normalizedHeights 归一 → 画在网格/曲线之下。
 * 口径：**曲线输出后**的画面（当前 program 含曲线，"边调边看分布"）。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { HistogramMath } from '../../entry/src/main/ets/core/HistogramMath';

function srcRoot(): string {
  let dir: string = __dirname;
  for (let i = 0; i < 6; i++) {
    if (existsSync(resolve(dir, 'entry/src/main/ets/core/HistogramMath.ets'))) {
      return dir;
    }
    dir = resolve(dir, '..');
  }
  throw new Error('source root not found from ' + __dirname);
}

function codeOnly(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
}

function sourceText(rel: string): string {
  return codeOnly(readFileSync(resolve(srcRoot(), rel), 'utf8'));
}

/** 造一帧纯色 RGBA 缓冲 */
function solidFrame(r: number, g: number, b: number, px: number): ArrayBuffer {
  const buf: ArrayBuffer = new ArrayBuffer(px * 4);
  const bytes: Uint8Array = new Uint8Array(buf);
  for (let i = 0; i < px; i++) {
    bytes[i * 4] = r;
    bytes[i * 4 + 1] = g;
    bytes[i * 4 + 2] = b;
    bytes[i * 4 + 3] = 255;
  }
  return buf;
}

test('纯色帧落单档', () => {
  const counts: Array<number> = HistogramMath.binRgba(solidFrame(255, 0, 0, 16), 4, 4);
  assertEq(16, counts[63], 'R=255 → bin 63 全落');
  for (let i = 0; i < 64; i++) {
    if (i !== 63) {
      assertEq(0, counts[i]);
    }
  }
  assertEq(16, counts[64], 'G=0 落 bin 0（0>>2=0，直方图不计空间）');
  assertEq(16, counts[128], 'B=0 落 bin 0');
  for (let i = 1; i < 64; i++) {
    assertEq(0, counts[64 + i], `G bin ${i} 全零`);
    assertEq(0, counts[128 + i], `B bin ${i} 全零`);
  }
});

test('灰阶帧三通道均分', () => {
  // 灰阶扫描：R=G=B 同值 → 三通道直方图形状全等
  const px: number = 256;
  const buf: ArrayBuffer = new ArrayBuffer(px * 4);
  const bytes: Uint8Array = new Uint8Array(buf);
  for (let i = 0; i < px; i++) {
    const v: number = i * 4; // 0..252，覆盖 63 档
    bytes[i * 4] = v;
    bytes[i * 4 + 1] = v;
    bytes[i * 4 + 2] = v;
    bytes[i * 4 + 3] = 255;
  }
  const counts: Array<number> = HistogramMath.binRgba(buf, 16, 16);
  for (let i = 0; i < 64; i++) {
    assertEq(counts[i], counts[64 + i], `G 与 R 在 bin ${i} 相等`);
    assertEq(counts[i], counts[128 + i], `B 与 R 在 bin ${i} 相等`);
  }
});

test('缓冲不足不抛_返回全零', () => {
  const counts: Array<number> = HistogramMath.binRgba(new ArrayBuffer(4), 64, 64);
  assertEq(192, counts.length);
  let sum: number = 0;
  for (const c of counts) {
    sum += c;
  }
  assertEq(0, sum);
});

test('各通道按各自最大归一_偏色不压扁小通道', () => {
  // R 集中一档（峰值 10）、G 均匀分布（峰值 1）——共用最大值会把 G 压成 0.1
  const counts: Array<number> = new Array<number>(192).fill(0);
  counts[10] = 10; // R 峰值
  for (let i = 0; i < 64; i++) {
    counts[64 + i] = 1; // G 均匀
  }
  const h: Array<number> = HistogramMath.normalizedHeights(counts);
  assertEq(1.0, h[10], 'R 峰位归一到 1');
  assertEq(1.0, h[64 + 20], 'G 峰位（均匀档）也归一到 1——形状保留，不被 R 的峰压扁');
});

test('全零计数归一返回全零', () => {
  const h: Array<number> = HistogramMath.normalizedHeights(new Array<number>(192).fill(0));
  let sum: number = 0;
  for (const v of h) {
    sum += v;
  }
  assertEq(0, sum);
});

// ---------------------------------------------------------------- 接线守卫（C++/UI 源码扫描）

test('接线_native采样与分箱同式镜像', () => {
  const cpp: string = sourceText('entry/src/main/cpp/gl_render.cpp');
  assertTrue(cpp.indexOf('out[rgba[o] >> 2]++;') >= 0 &&
    cpp.indexOf('out[kHistBins + (rgba[o + 1] >> 2)]++;') >= 0,
    'native BinHistogramRgba 必须与 core binRgba 同式（bin = v >> 2 三通道）');
  assertTrue(cpp.indexOf('constexpr int64_t kHistogramPeriodMs = 250;') >= 0, '250ms 一拍');
  assertTrue(cpp.indexOf('DrawScene(locs, g_uvVbo, g_useVao, false, false);') >= 0,
    '采样必须经 DrawScene 走当前 program（曲线输出后口径，encodeSlot=false）');
  assertTrue(cpp.indexOf('MaybeSampleHistogram(locs);') >= 0, '上屏路径必须挂采样点');
  assertTrue(cpp.indexOf('glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE') >= 0,
    '迷你 RT 必须有完整性出口');
  assertTrue(cpp.indexOf('if (g_histFbo != 0) {') >= 0 &&
    cpp.indexOf('glDeleteFramebuffers(1, &g_histFbo);') >= 0,
    'DestroyGlObjects 必须回收直方图 RT（先于 EGL 拆除，5742c0f 口径）');
  assertTrue(cpp.indexOf('g_histSnapshot.clear();') >= 0, '关开关/回收必须清快照');
});

test('接线_napi与桥与页面开关', () => {
  const napi: string = sourceText('entry/src/main/cpp/napi_init.cpp');
  assertTrue(napi.indexOf('{"setHistogramEnabled", nullptr, SetHistogramEnabled') >= 0 &&
    napi.indexOf('{"getHistogramSnapshot", nullptr, GetHistogramSnapshot') >= 0,
    'napi exports 表必须有两口');
  const bridge: string = sourceText('entry/src/main/ets/camera/GlLibBridge.ets');
  assertTrue(bridge.indexOf('setHistogramEnabled') >= 0 && bridge.indexOf('getHistogramSnapshot') >= 0,
    'GlLibBridge 必须有两口转发');
  const camera: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertTrue(camera.indexOf('this.glLib.setHistogramEnabled(true);') >= 0,
    '开曲线弹窗必须开采样');
  assertTrue(camera.indexOf('setHistogramEnabled(false);') >= 0,
    '关弹窗必须停采清快照');
  assertTrue(camera.indexOf('histogramProvider') >= 0, 'CurvePopup 必须拿到快照源');
});

test('接线_弹窗150ms轮询与0x30背景', () => {
  const popup: string = sourceText('entry/src/main/ets/ui/CurvePopup.ets');
  assertTrue(popup.indexOf('HistogramMath.normalizedHeights') >= 0,
    '归一必须走 core 纯函数（不许 UI 里另写一份）');
  assertTrue(popup.indexOf("'#30FF0000', '#3000FF00', '#300000FF'") >= 0,
    '三通道半透明 0x30（上游口径）');
  assertTrue(popup.indexOf('}, 150);') >= 0, '150ms 轮询节奏');
  assertTrue(popup.indexOf('@Watch(\'onVisibleChanged\') visible') >= 0,
    'visible 翻转必须启停轮询');
});
