/**
 * DIRECT 直显显示矩阵 + 直显预览选档（对齐上游 DirectRulesTest.kt 的 5 条矩阵用例与
 * 2 条预览选档用例；上游同文件的「录制方向角」「后摄档位」两条属于已移植函数，不在本批）。
 *
 * 每条用例存在的理由：矩阵算错时画面会被拉伸/偏出视口/镜像反到传感器侧，真机上一眼能看见，
 * 但纯逻辑层没有第二处可对拍——所以期望值全部手写字面量，不从实现反推。
 *
 * 移植登记：上游 `DirectSink` 平台胶水（TextureView/SurfaceTexture/Matrix/PreviewOrientation）
 * 本侧无等价物（ArkUI 只有 XComponent surface = GL 路径），只移植纯函数 directDisplayMatrix → DirectDisplay.matrix。
 */
import { DirectDisplay } from '../../entry/src/main/ets/core/DirectDisplay';
import { Size } from '../../entry/src/main/ets/core/Constants';
import { pickDirectPreviewSize } from '../../entry/src/main/ets/core/VideoSizePicker';

/** 行主序 3x3 作用于视图坐标（齐次 w=1） */
function apply(m: Array<number>, x: number, y: number): Array<number> {
  return [m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5]];
}

/** 上游 assertNear：位置容差 1e-3 */
function assertNear(expectedX: number, expectedY: number, actual: Array<number>): void {
  const ok: boolean = Math.abs(expectedX - actual[0]) < 1e-3 && Math.abs(expectedY - actual[1]) < 1e-3;
  assertTrue(ok, `期望 (${expectedX},${expectedY}) 实得 (${actual[0]},${actual[1]})`);
}

// ------------------------------------------------------------------ 矩阵（上游 5 条）

test('直显矩阵在零度旋转下等比居中并留信箱', () => {
  const m: Array<number> = DirectDisplay.matrix(100, 100, 200, 100, 0, false);
  // 2:1 的画面铺进 1:1 视图 → 100x50 居中，上下各 25px 黑边
  assertNear(0, 25, apply(m, 0, 0));
  assertNear(100, 75, apply(m, 100, 100));
});

test('直显矩阵顺时针九十度时画面左上角落到内容框右上', () => {
  const m: Array<number> = DirectDisplay.matrix(100, 100, 200, 100, 90, false);
  // 旋转后包围盒 100x200 → 等比 0.5 → 50x100 水平居中
  assertNear(75, 0, apply(m, 0, 0));
  assertNear(25, 100, apply(m, 100, 100));
});

test('直显矩阵镜像作用在屏幕横轴且与旋转可交换', () => {
  const flat: Array<number> = DirectDisplay.matrix(100, 100, 100, 100, 0, true);
  assertNear(100, 0, apply(flat, 0, 0));
  assertNear(0, 100, apply(flat, 100, 100));
  // 180 度 + 镜像 = 只沿纵轴镜像
  const both: Array<number> = DirectDisplay.matrix(100, 100, 100, 100, 180, true);
  assertNear(0, 100, apply(both, 0, 0));
  assertNear(100, 0, apply(both, 100, 100));
});

test('直显矩阵在非整百旋转角下按包围盒算黑边', () => {
  const m: Array<number> = DirectDisplay.matrix(100, 100, 200, 100, 45, false);
  const d: number = 0.7071;
  // 2:1 画面转 45° 的外接框 = (200+100)·sin45 见方，等比后正好铺满正方形视图
  const content: number = (200 + 100) * d;
  const scale: number = 100 / content;
  const corners: Array<Array<number>> = [apply(m, 0, 0), apply(m, 100, 0), apply(m, 0, 100), apply(m, 100, 100)];
  let minX: number = corners[0][0];
  let maxX: number = corners[0][0];
  let minY: number = corners[0][1];
  let maxY: number = corners[0][1];
  for (const p of corners) {
    minX = Math.min(minX, p[0]);
    maxX = Math.max(maxX, p[0]);
    minY = Math.min(minY, p[1]);
    maxY = Math.max(maxY, p[1]);
  }
  assertTrue(Math.abs((maxX - minX) - content * scale) < 0.6, '45° 后宽度必须等于包围盒等比宽度');
  assertTrue(Math.abs((maxY - minY) - content * scale) < 0.6, '45° 后高度必须等于包围盒等比高度');
  assertTrue(Math.abs((maxX + minX) / 2 - 50) < 0.6, '旋转后必须水平居中');
  assertTrue(Math.abs((maxY + minY) / 2 - 50) < 0.6, '旋转后必须垂直居中');
});

test('尺寸未就绪时直显矩阵退回单位阵', () => {
  const m: Array<number> = DirectDisplay.matrix(0, 0, 1920, 1080, 90, false);
  // 容差 1e-6：单位阵是退化出口，不能靠「近似等于」蒙混（尺寸就绪时 m[1] 必为 -0.5）
  assertTrue(Math.abs(m[0] - 1) < 1e-6, 'm[0] 必须是 1');
  assertTrue(Math.abs(m[1] - 0) < 1e-6, 'm[1] 必须是 0');
  assertTrue(Math.abs(m[2] - 0) < 1e-6, 'm[2] 必须是 0');
  assertTrue(Math.abs(m[4] - 1) < 1e-6, 'm[4] 必须是 1');
});

// ------------------------------------------------------------------ 直显预览选档（上游 2 条）
//
// 核对：本批之前 test/pure 无 pickDirectPreviewSize 覆盖（grep 无命中），故这两条为新增，
// 不是重复堆用例。

test('直显预览流只挑与录像同宽高比的档', () => {
  const candidates: Array<Size> = [
    new Size(4160, 3120), new Size(3264, 2448), new Size(2560, 1440), new Size(1920, 1080),
    new Size(1920, 864), new Size(1280, 720), new Size(1024, 768), new Size(800, 600),
    new Size(720, 720), new Size(960, 540), new Size(640, 360)
  ];
  // 16:9 录像：先在 16:9 支里按视图面积取最小够用档，2560x1440 超过 1080p 上限要跳过
  const a: Size | undefined = pickDirectPreviewSize(candidates, new Size(1920, 1080), 1260, 660);
  assertEq(1280, a?.width);
  assertEq(720, a?.height);
  // 视图很大时升到上限内最大档
  const b: Size | undefined = pickDirectPreviewSize(candidates, new Size(1920, 1080), 3000, 1700);
  assertEq(1920, b?.width);
  assertEq(1080, b?.height);
  // 4:3 录像：只取 4:3 支（4160x3120/3264x2448 超上限），不能拿 16:9 档拉伸
  const c: Size | undefined = pickDirectPreviewSize(candidates, new Size(3264, 2448), 1200, 900);
  assertEq(1024, c?.width);
  assertEq(768, c?.height);
  // 1:1 录像：同比例里最小够用
  const d: Size | undefined = pickDirectPreviewSize(candidates, new Size(720, 720), 400, 400);
  assertEq(720, d?.width);
  assertEq(720, d?.height);
});

test('直显预览流选档不越界不硬编码', () => {
  const candidates: Array<Size> = [new Size(1920, 1080), new Size(1280, 720)];
  // 视图未布局（面积 0）时不能一路退到最小档
  const a: Size | undefined = pickDirectPreviewSize(candidates, new Size(1280, 720), 0, 0);
  assertEq(1280, a?.width);
  assertEq(720, a?.height);
  // 能力表为空 → 交回上层兜底，不伪造尺寸
  assertNull(pickDirectPreviewSize([], new Size(1920, 1080), 100, 100));
  // 录像档本身小于上限时不得选到比录像更大的预览流
  const c: Size | undefined = pickDirectPreviewSize(
    [new Size(1920, 1080), new Size(1280, 720), new Size(640, 360)], new Size(640, 360), 300, 200);
  assertEq(640, c?.width);
  assertEq(360, c?.height);
  // 没有任何同比例档时退到全表最小够用档，而不是返回 undefined 让画面失控
  const d: Size | undefined = pickDirectPreviewSize(
    [new Size(1280, 720), new Size(640, 480)], new Size(1000, 1000), 200, 200);
  assertEq(640, d?.width);
  assertEq(480, d?.height);
});
