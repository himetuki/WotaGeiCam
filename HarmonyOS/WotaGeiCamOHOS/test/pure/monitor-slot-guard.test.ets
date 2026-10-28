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

test('方向常数v2_上屏与编码容器角同式_mirror取反', () => {
  // 2026-10-28 用户按模拟器实测标定（取代 FRONT180 的 v1）：OHOS 预览缓冲方向语义与
  // Camera2 SurfaceTexture 不同，Camera2 公式整体差一组常数。竖屏实测目标：后摄（270, 镜像）
  // ＝当前画面顺时针 90° 再左右镜像；前摄（270, 不镜像）＝再左右镜像一次。
  // 公式：deg = sensor − display + (front ? 0 : 180)、mirror = !front；横屏两镜头退化为
  // deg=0（webcam 原始帧横向，横屏窗口无需旋转，自洽）。
  const cpp: string = sourceText('entry/src/main/cpp/gl_render.cpp');
  assertTrue(cpp.indexOf('WotaNormalizeDeg(g_displayDeg + g_stRotDeg +') >= 0 &&
    cpp.indexOf('(g_mirrored ? 0 : 270)') >= 0,
    '上屏 UV 复合角必须是 v4 标定（display + 后摄0/前摄270——v3 前后各再 180）');
  assertTrue(cpp.indexOf('WotaNormalizeDeg(g_displayDeg + (g_mirrored ? 0 : 270))') >= 0,
    '编码容器角必须与上屏同式（v4）');
  assertFalse(cpp.indexOf('g_sensorDeg - g_displayDeg + g_stRotDeg') >= 0,
    'v1/v2 的 sensor−display 公式必须消失（突变自证）');
  const camera: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertTrue(camera.indexOf('lib.setDisplayGeometry(info.sensorDeg, this.displayDeg(), !info.front)') >= 0,
    'ArkTS 侧必须传 mirror = !front');
});
