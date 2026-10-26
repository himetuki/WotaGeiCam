/**
 * WLB 批（2026-10-25）：取景预览 letterbox 几何全表 + 三处消费一致性守卫。
 *
 * 背景：native 上屏原是**全屏四边形**（UV 只做旋转/镜像），预览档却取录制尺寸，
 * 宽高比不一致时画面被拉伸铺满。本批按画面宽高比做等比收缩（黑边只在上屏），
 * 并把参考线/网格与点按对焦统一到同一份画面矩形。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { PreviewBox, PreviewFit } from '../../entry/src/main/ets/core/PreviewFit';

function srcRoot(): string {
  let dir: string = __dirname;
  for (let i = 0; i < 6; i++) {
    if (existsSync(resolve(dir, 'entry/src/main/ets/ui/HudSpec.ets'))) {
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

// ------------------------------------------------------------------ 取向（与 native UV 旋转同源）

test('取向_90与270互换宽高_0与180不换', () => {
  assertTrue(PreviewFit.swapsAxes(90));
  assertTrue(PreviewFit.swapsAxes(270));
  assertFalse(PreviewFit.swapsAxes(0));
  assertFalse(PreviewFit.swapsAxes(180));
  assertTrue(PreviewFit.swapsAxes(-90), '负角等价（-90 ≡ 270）');
  assertTrue(PreviewFit.swapsAxes(450), '超界先折回');
});

test('画面比例_含旋转取向', () => {
  // 1920×1080 横版画面：不旋转 = 16/9；转 90° 后显示为 1080×1920 = 9/16
  assertEq(PreviewFit.aspectOf(1920, 1080, 0), 1920 / 1080);
  assertEq(PreviewFit.aspectOf(1920, 1080, 90), 1080 / 1920);
  assertEq(PreviewFit.aspectOf(0, 1080, 0), 0, '尺寸不可用 → 0');
});

// ------------------------------------------------------------------ 矩形（内接 + 居中）

test('矩形_屏幕比画面更窄_上下留黑', () => {
  // 竖屏 1260×2720 放 16:9 画面（不旋转）：顶宽，高 = 1260/(16/9) = 708.75，上下各留 1005.625
  const box: PreviewBox = PreviewFit.rectOf(1260, 2720, 1920, 1080, 0);
  assertEq(box.x, 0);
  assertEq(box.w, 1260);
  assertTrue(Math.abs(box.h - 708.75) < 0.01, `画面高应内接，实得 ${box.h}`);
  assertTrue(Math.abs(box.y - (2720 - 708.75) / 2) < 0.01, '垂直居中');
  assertTrue(box.h < 2720, '上下必须留黑（旧实现是拉满）');
});

test('矩形_屏幕比画面更宽_两侧留黑', () => {
  // 横屏 2720×1260 放 16:9：顶高，宽 = 1260×16/9 = 2240，两侧各留 240
  const box: PreviewBox = PreviewFit.rectOf(2720, 1260, 1920, 1080, 0);
  assertEq(box.y, 0);
  assertEq(box.h, 1260);
  assertTrue(Math.abs(box.w - 2240) < 0.01);
  assertTrue(Math.abs(box.x - 240) < 0.01, '水平居中');
});

test('矩形_比例一致时铺满_不可用时铺满', () => {
  const same: PreviewBox = PreviewFit.rectOf(1600, 900, 1920, 1080, 0);
  assertEq(same.x, 0);
  assertEq(same.y, 0);
  assertEq(same.w, 1600);
  assertEq(same.h, 900);
  // 尺寸/视口不可用 → 铺满（= 旧行为，调用方零改动兼容）
  const unknown: PreviewBox = PreviewFit.rectOf(600, 800, 0, 0, 0);
  assertEq(unknown.w, 600);
  assertEq(unknown.h, 800);
  assertEq(unknown.aspect, 0, 'aspect=0 让参考线退回铺满');
  const noViewport: PreviewBox = PreviewFit.rectOf(0, 0, 1920, 1080, 0);
  assertEq(noViewport.w, 0);
});

test('矩形_旋转后取向参与内接', () => {
  // 1920×1080 画面转 90° → 显示比例 9:16，在 1260×2720 屏上：屏比 0.463 < 0.5625 → 顶宽
  const box: PreviewBox = PreviewFit.rectOf(1260, 2720, 1920, 1080, 90);
  assertEq(box.w, 1260);
  assertTrue(Math.abs(box.h - 1260 / (1080 / 1920)) < 0.01, '高 = 宽 / (9/16)');
  assertTrue(Math.abs(box.aspect - 1080 / 1920) < 1e-9);
});

// ------------------------------------------------------------------ NDC 收缩因子

test('NDC因子_等于画面占视口的比例_满幅为1', () => {
  const box: PreviewBox = PreviewFit.rectOf(1260, 2720, 1920, 1080, 0);
  assertEq(PreviewFit.ndcScaleX(box, 1260), 1, '顶宽 → x 不缩');
  assertTrue(Math.abs(PreviewFit.ndcScaleY(box, 2720) - 708.75 / 2720) < 1e-9, 'y 按占比收缩');
  assertTrue(PreviewFit.ndcScaleX(new PreviewBox(0, 0, 0, 0, 0), 1260) === 1, '不可用 → 1（铺满）');
  assertEq(PreviewFit.ndcScaleY(new PreviewBox(0, 0, 100, 100, 1), 0), 1, '视口不可用 → 1');
});

// ------------------------------------------------------------------ 点按映射

test('点按_映射进画面_黑边夹到边缘', () => {
  const box: PreviewBox = PreviewFit.rectOf(1260, 2720, 1920, 1080, 0);
  // 画面中心 (630, 1360) → 画面内 (0.5, 0.5)
  const center = PreviewFit.mapToContent(0.5, 0.5, box, 1260, 2720);
  assertTrue(Math.abs(center.x - 0.5) < 1e-6);
  assertTrue(Math.abs(center.y - 0.5) < 1e-6);
  // 顶黑边（y=0）→ 夹到画面顶边 y=0
  const top = PreviewFit.mapToContent(0.5, 0, box, 1260, 2720);
  assertTrue(Math.abs(top.y) < 1e-6, '黑边点夹到最近边缘（不是无响应）');
  // 画面顶边正下 10% 处
  const y10 = PreviewFit.mapToContent(0.5, (box.y + box.h * 0.1) / 2720, box, 1260, 2720);
  assertTrue(Math.abs(y10.y - 0.1) < 1e-6, '画面内线性映射');
});

test('点按_矩形不可用时与旧行为逐字相同', () => {
  const none: PreviewBox = new PreviewBox(0, 0, 0, 0, 0);
  const p = PreviewFit.mapToContent(0.25, 0.75, none, 1260, 2720);
  assertEq(p.x, 0.25);
  assertEq(p.y, 0.75);
});

// ------------------------------------------------------------------ 三处消费一致性守卫

test('接线_上屏收缩_native侧uFit', () => {
  const sh: string = sourceText('entry/src/main/cpp/shaders.h');
  assertTrue(sh.indexOf('uniform vec2 uFit;') >= 0, '两个四边形 VS 必须有 uFit');
  assertEq((sh.match(/gl_Position = vec4\(aPosition\.xy \* uFit, aPosition\.zw\);/g) ?? []).length, 2,
    'TEXTURE_VS 与 FROST_QUAD_VS 都乘 uFit');
  const gl: string = sourceText('entry/src/main/cpp/gl_render.cpp');
  assertTrue(gl.indexOf('float g_fitX = 1.0f;') >= 0 && gl.indexOf('float g_fitY = 1.0f;') >= 0,
    '默认必须 1,1（未下发时与旧行为逐字相同）');
  assertTrue(gl.indexOf('void WotaGlSetDisplayFit(float fitX, float fitY)') >= 0, 'setter 存在');
  // 编码面恒满幅：DrawScene 的 applyFit 默认 false
  assertTrue(gl.indexOf('void DrawScene(const ProgramLocs& locs, GLuint uvVbo, bool vaoOk, bool applyFit = false)') >= 0 &&
    gl.indexOf('applyFit ? g_fitX : 1.0f') >= 0, '编码路径必须恒传 1,1（成片不带黑边）');
  // 上屏两路都吃 g_fit
  assertEq((gl.match(/glUniform2f\(locs\.uFit, g_fitX, g_fitY\)/g) ?? []).length, 1,
    '常规上屏吃 g_fit');
  assertTrue(gl.indexOf('glUniform2f(g_frostDisplay.uFit, g_fitX, g_fitY)') >= 0,
    '毛玻璃上屏同样吃 g_fit');
  assertEq((gl.match(/glUniform2f\(g_frost(Blur|Copy)\.uFit, 1\.0f, 1\.0f\)/g) ?? []).length, 3,
    '毛玻璃中间 RT 三趟恒等（RT 尺寸=表面尺寸）');
  assertTrue(gl.indexOf('g_fitX = 1.0f;\n    g_fitY = 1.0f;') >= 0, '会话复位必须回满幅');
});

test('接线_三处共用同一份画面矩形', () => {
  const cam: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertTrue(cam.indexOf('lib.setDisplayFit(PreviewFit.ndcScaleX(this.previewBox, this.winWvp),') >= 0,
    '上屏收缩吃 previewBox');
  assertTrue(cam.indexOf('aspect: this.refLineAspect(),') >= 0, '参考线吃同一矩形');
  assertTrue(cam.indexOf('PreviewFit.mapToContent(raw.x, raw.y, this.previewBox,') >= 0,
    '点按映射吃同一矩形');
  assertTrue(cam.indexOf('this.applyPreviewFit();\n      }') >= 0 ||
    cam.indexOf('this.applyPreviewFit();') >= 0, '视口/几何变化必须重算');
  // d.ts 与桥同步
  const dts: string = sourceText('entry/src/main/cpp/types/libentry/index.d.ts');
  assertTrue(dts.indexOf('setDisplayFit: (fitX: number, fitY: number) => void') >= 0, 'd.ts 声明在册');
  const bridge: string = sourceText('entry/src/main/ets/camera/GlLibBridge.ets');
  assertTrue(bridge.indexOf('glLib.setDisplayFit(fitX, fitY);') >= 0, '桥转发面在册');
});

// ------------------------------------------------------------------ 真机取证修正（2026-10-26）

test('接线_画面矩形旋转用复合角', () => {
  const cam: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertTrue(cam.indexOf('info.sensorDeg - this.displayDeg()') >= 0,
    '旋转必须与 native UV 旋转同式（sensor − display）；displayDeg 单侧在竖屏=0，画面被压成横带（真机取证）');
  assertTrue(cam.indexOf('((raw % 360) + 360) % 360') >= 0,
    '复合角必须归一到 [0,360)，swapsAxes 才认 90/270（前置镜像会出负角）');
  assertEq(cam.indexOf('surfWvp'), -1, '视口必须是窗口全幅（沉浸式全屏 = GL 表面全幅，模拟器实证），不得另测表面');
});

test('接线_姿态仪浮层Stack对齐且不拦触摸', () => {
  const cam: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertTrue(cam.indexOf('Stack({ alignContent: Alignment.TopEnd })') >= 0,
    '姿态仪包装层必须用 Stack 的 alignContent（Row 上 .align() 无效，浮层被页面 Stack 居中到屏幕中部）');
  assertEq(cam.indexOf('.align(Alignment.TopEnd)'), -1, 'Row/Column 上的 .align() 是无效调用，不得回归');
  assertTrue(cam.indexOf('.hitTestBehavior(HitTestMode.None)') >= 0, '全屏包装层自身必须退出命中树');
});