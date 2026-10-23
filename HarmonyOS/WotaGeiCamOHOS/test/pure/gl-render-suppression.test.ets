/**
 * GPU 路换段重挂窗口的抑制判定（对齐上游 SegmentRotateSuppressGuardTest.kt 的纯函数面 2 条）。
 *
 * 缺陷背景：分段轮转时旧编码面已死而 GL 还绑着它，窗口内相机帧照常驱动 drawFrame，
 * 死面上 swap 必败 → `degradeArcConvert` 静默清账降级（DROP→直通把换段点之后的成片降成
 * 1.25× 慢放；MEND→DROP 清掉位次账）。修复用两个抑制窗口（停止序列 / 换段重挂），
 * 任一为真即抑制——`degradeSuppressed` 就是这条判定本身。
 *
 * 为什么必须测桥本身：判据若散落内联，删掉抑制分支用例仍全绿，测不出退化。
 *
 * 跳过（登记，不伪造接线）：上游同文件另 4 条 @Test 是结构扫描守卫——扫描
 * `GlRenderEngine.degradeArcConvert` 的调用顺序、`segmentRotating` 的 @Volatile/mark/clear/
 * isEncoderSurfaceNative，以及 `CameraScreen.beginSession` 重挂回调里 mark/setOutputSurface/
 * await/clear 的位置序。鸿蒙侧这些分支在 native `cpp/gl_render.cpp` 的 C++ 代码里，
 * 不在 ArkTS 可扫描面，也没有 `GlRenderEngine`/`CameraScreen` 的对应结构，故不翻译。
 */
import { degradeSuppressed } from '../../entry/src/main/ets/camera/GlRenderSuppression';

test('degradeSuppressed 全表_任一窗口为真即抑制', () => {
  // 期望值手写（析取真值表），不许由实现推导
  assertEq(false, degradeSuppressed(false, false), '两窗口都关：不抑制（真失败照旧降级）');
  assertEq(true, degradeSuppressed(true, false), '停止窗口单独为真：抑制');
  assertEq(true, degradeSuppressed(false, true), '换段窗口单独为真：抑制');
  assertEq(true, degradeSuppressed(true, true), '两窗口同时为真：抑制');
});

test('尺子自己能红_析取被突变成合取时全表必报偏离', () => {
  // 突变体（|| → &&）：单独换段窗口漏抑制——正是本缺陷的回归形态
  const mutant = (a: boolean, b: boolean): boolean => a && b;
  const truthTable: Array<Array<boolean>> = [
    [false, false, false],
    [true, false, true],
    [false, true, true],
    [true, true, true]
  ];
  let deviated: number = 0;
  for (const row of truthTable) {
    if (mutant(row[0], row[1]) !== row[2]) {
      deviated++;
    }
  }
  assertTrue(deviated > 0,
    '合取突变体必须至少在一行上偏离析取真值表（否则全表测不出该突变，尺子失效）');
  // 泄漏点必须在「换段窗口单独为真」那一行：合取在这里答 false，析取答 true
  assertEq(false, mutant(false, true));
});
