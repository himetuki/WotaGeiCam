/**
 * 监看叠加只上屏（对齐上游 46d8d91 StateOnlyEncoderGuardTest/MonitorEffectEncoderGuardTest 的
 * 结构守卫，OHOS 版）：双 program 槽——上屏槽（斑马/峰值/曲线）+ 编码槽（恒 NONE + 曲线）。
 *
 * 真身在 C++（gl_render.cpp），pure-test 只能读源码钉结构（与 clip-export-shift 同口径，
 * 防止「槽位加了但某条编码支路还在吃上屏槽」的静默失效）。尺子配突变自证：
 * 把 DrawEncoderPass 的编码槽选择换回 g_slotLocs、或 DrawScene 的 encodeSlot 分支删掉，
 * 下面的接线断言即红。
 *
 * 5035b09 附B/D（帧龄改段内漂移）N/A 登记：本侧 native 无「墙钟 − 相机 SOF 戳」的帧龄
 * 观测面（FrameLedger 只计帧数，NativeImage 戳只进光弧 keep-rule 且已有防倒退钳制）；
 * 附A/C（sidecar 走 MediaStore 兜底）同为 Android 面一一一一本侧沙箱直写无此通道。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/** 从转译产物目录向上找仓库根（含 entry/src/main/cpp/gl_render.cpp 的祖先目录） */
function srcRoot(): string {
  let dir: string = __dirname;
  for (let i = 0; i < 6; i++) {
    if (existsSync(resolve(dir, 'entry/src/main/cpp/gl_render.cpp'))) {
      return dir;
    }
    dir = resolve(dir, '..');
  }
  throw new Error('source root not found from ' + __dirname);
}

/** 去注释（块注释 + 整行行注释）：让接线扫描只看代码，注释里的关键词不算数 */
function codeOnly(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
}

function sourceText(rel: string): string {
  return codeOnly(readFileSync(resolve(srcRoot(), rel), 'utf8'));
}

test('编码槽存在且由SyncEffect同源驱动', () => {
  const cpp: string = sourceText('entry/src/main/cpp/gl_render.cpp');
  assertTrue(cpp.indexOf('ProgramLocs g_encSlotLocs;') >= 0, '编码槽 program 必须存在');
  assertTrue(cpp.indexOf('SyncEncodeSlot(wantCurve)') >= 0,
    '编码槽必须由 SyncEffect 的同一份 wantCurve 驱动（两槽同源同帧）');
  assertTrue(cpp.indexOf('BuildProgramLocs(WOTA_TEXTURE_VS, WOTA_CURVE_FS, g_encSlotLocs)') >= 0,
    '编码槽只允许曲线变体（WOTA_CURVE_FS）——没有 zebra/peaking 变体');
  assertFalse(cpp.indexOf('WotaZebraFragment') >= 0 &&
    cpp.indexOf('WotaZebraFragment(g_encSlotLocs)') >= 0, '编码槽不得出现斑马变体');
});

test('两条编码支路都吃编码槽', () => {
  const cpp: string = sourceText('entry/src/main/cpp/gl_render.cpp');
  // DrawEncoderPass 与 DrawEncoderMended 的槽选择必须指向 g_encSlotLocs
  const passSel = cpp.indexOf('g_encSlotLocs.program != 0 ? g_encSlotLocs : g_passLocs');
  assertTrue(passSel >= 0, '编码支路槽选择必须先取编码槽');
  assertTrue(cpp.split('g_encSlotLocs.program != 0 ? g_encSlotLocs : g_passLocs').length - 1 >= 2,
    'DrawEncoderPass 与 DrawEncoderMended 两条支路都必须改吃编码槽');
  assertTrue(cpp.indexOf('DrawScene(locs, g_encUvVbo, false, false, true)') >= 0,
    '编码绘制必须带 encodeSlot=true（斑马/峰值的 uniform 分支只属于上屏槽）');
  assertTrue(cpp.split('DrawScene(locs, g_encUvVbo, false, false, true)').length - 1 >= 2,
    '两条编码支路（直发 + MEND 离屏 cur）都要走编码口径');
});

test('编码槽随管线整体回收', () => {
  const cpp: string = sourceText('entry/src/main/cpp/gl_render.cpp');
  assertTrue(cpp.indexOf('DeleteProgramLocs(g_encSlotLocs);') >= 0,
    'DestroyGlObjects 必须回收编码槽（防 surface 重建后 stale program 残留）');
  assertTrue(cpp.indexOf('g_encSlotCurve = false;') >= 0,
    '编码槽曲线标志必须随回收复位');
});

test('上屏槽保持效果全集', () => {
  const cpp: string = sourceText('entry/src/main/cpp/gl_render.cpp');
  assertTrue(cpp.indexOf('g_slotEffect == kEffectZebra') >= 0, '上屏槽保留斑马');
  assertTrue(cpp.indexOf('g_slotEffect == kEffectPeaking') >= 0, '上屏槽保留峰值');
  assertTrue(cpp.indexOf('if (g_slotCurve) {') >= 0, '上屏槽保留曲线');
});

test('前摄方向修正_上屏与编码容器角都加180', () => {
  // 上游 9cc36c5 前摄容器角官方公式的本侧对应：前摄 sensorOrientation 参考系相反（镜像
  // 语义），转正公式一律 +180（g_mirrored 即 front 标志）；与矩阵族无关——2026-10-28
  // 模拟器实测横屏前摄差 180° 后补（后摄 sensor=90 / 前摄 sensor=270，横屏 display=270：
  // 旧公式后摄 180 正确、前摄 0 差 180）。
  const cpp: string = sourceText('entry/src/main/cpp/gl_render.cpp');
  assertTrue(cpp.indexOf('g_sensorDeg - g_displayDeg + g_stRotDeg +') >= 0 &&
    cpp.indexOf('(g_mirrored ? 180 : 0)') >= 0,
    '上屏 UV 复合角必须含前摄 +180');
  assertTrue(cpp.indexOf('WotaNormalizeDeg(g_sensorDeg - g_displayDeg + (g_mirrored ? 180 : 0))') >= 0,
    '编码容器角必须含前摄 +180（与上屏同一修法）');
  // 突变自证：两处任一回退为无 +180 公式即红
  assertFalse(cpp.indexOf('WotaNormalizeDeg(g_sensorDeg - g_displayDeg);') >= 0,
    '编码容器角不得回退为无修正公式');
});
