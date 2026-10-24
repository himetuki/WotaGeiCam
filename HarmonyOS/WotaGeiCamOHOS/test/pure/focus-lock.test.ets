/**
 * FL 批（对焦锁定）：三击派发（单击=对焦、双击=锁定、长按=解锁重对焦）+ 锁定门禁 +
 * RequestApplier FOCUS_MODE_LOCKED 接线守卫。纯逻辑部分复用 TFC 的 FocusTapMath 全表
 * （focus-tap.test.ets），本文件只测 FL 增量。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { AfMode } from '../../entry/src/main/ets/core/Constants';
import { FocusTapArbiter } from '../../entry/src/main/ets/core/FocusTap';
import { FocusTapMath } from '../../entry/src/main/ets/core/FocusTap';

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

// ------------------------------------------------------------------ 派发语义（仲裁态机）

test('派发_单击对焦_双击锁定_锁定期轻触双击不重焦', () => {
  const arb = new FocusTapArbiter(280);
  // 未锁：单击（false）→ focusTapAt；双击第二击（true）→ lockFocusAt
  assertFalse(arb.onTapUp(1000), '未锁单击');
  assertTrue(arb.onTapUp(1150), '双击第二击 → 锁定');
  // 已锁：轻触与双击都**不重焦**——仲裁照样工作（页面按 focusLocked 分流到提示），语义不因锁改变
  assertFalse(arb.onTapUp(2000), '锁定期轻触仍判单击（页面分流到提示）');
  assertTrue(arb.onTapUp(2100), '锁定期双击仍判双击（页面分流到提示）');
});

test('派发_长按重置双击序列_不误判', () => {
  const arb = new FocusTapArbiter(280);
  arb.onTapUp(1000);
  arb.reset(); // 长按解锁路径先 reset（长按本身不计入双击序列）
  assertFalse(arb.onTapUp(1100), 'reset 后的点击是新单击序列');
});

// ------------------------------------------------------------------ 锁定接线守卫

test('接线_WotaParams清锁唯一落点', () => {
  const wota: string = sourceText('entry/src/main/ets/core/WotaParams.ets');
  assertTrue(wota.indexOf('this.focusLocked.value = false;') >= 0 &&
    wota.indexOf('this.focusLockPoint.value = undefined;') >= 0,
    'applyAbility（相机整套重开）必须清锁+清锚点（上游 Q4：清锁唯一落点）');
  // 排除清单：focusPoint 瞬时态不持久化（上游 ParamsPersist 排除清单同源）
  const persist: string = sourceText('entry/src/main/ets/ui/ParamsPersist.ets');
  assertTrue(persist.indexOf('focus_locked') < 0 && persist.indexOf('focusLock') < 0,
    '锁定态不得入 24 键白名单（瞬时拍摄态，上游排除清单）');
});

test('接线_RequestApplier_LOCKED能力闸与锚点', () => {
  const applier: string = sourceText('entry/src/main/ets/camera/RequestApplier.ets');
  assertTrue(applier.indexOf('camera.FocusMode.FOCUS_MODE_LOCKED') >= 0,
    '锁定下发走 OHOS 原生 FOCUS_MODE_LOCKED（镜头停在合焦位不排恢复）');
  assertTrue(applier.indexOf('isFocusModeSupported(camera.FocusMode.FOCUS_MODE_LOCKED)') >= 0,
    '能力闸进闸现读（isFocusModeSupported 为 false → skipped，拒绝假开关）');
  assertTrue(applier.indexOf("'focusLock: LOCKED not supported'") >= 0, '不支持 → skipped 桶');
  assertTrue(applier.indexOf('focusLockPoint') >= 0, '锁定锚点随快照下发');
  // 锁定时 afMode 正常链让位：focusModeFor 仍在（未锁路径），但锁内不发 focusModeFor
  // 锁定分支与未锁分支的 focusMode 来源分流：锁定走 FOCUS_MODE_LOCKED，
  // 未锁走 focusModeFor——锁定分支体（两锚点之间）不许出现 focusModeFor
  const lockStart: number = applier.indexOf('if (p.focusLocked) {');
  // 锁定分支体到 `} else {` 为止（else = 未锁路径，focusModeFor 在那边是合法的）
  const lockEnd: number = applier.indexOf('} else {', lockStart);
  assertTrue(lockStart >= 0 && lockEnd > lockStart, '锚点存在');
  const lockBody: string = applier.substring(lockStart, lockEnd);
  assertTrue(lockBody.indexOf('focusModeFor') < 0, '锁定分支不得再走用户 afMode 链');
});

test('接线_相机页三击派发与胶囊条目', () => {
  const cam: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertTrue(cam.indexOf('this.tapArbiter.onTapUp(Date.now())') >= 0, '点按经仲裁');
  assertTrue(cam.indexOf('this.lockFocusAt(pt, windowX, windowY);') >= 0, '双击 → 锁定入口');
  assertTrue(cam.indexOf('对焦已锁定；要换焦点请长按画面或打开对焦胶囊解除') >= 0,
    '锁定期首次轻触给提示（上游 cam_focus_tap_locked_tip 同义）');
  assertTrue(cam.indexOf('手动对焦档本身即锁定，无需再锁') >= 0, 'MANUAL 门（上游同文案）');
  assertTrue(cam.indexOf('录制中不可锁定对焦') >= 0, '录制门（上游 cam_focus_lock_recording 同义）');
  assertTrue(cam.indexOf('LongPressGesture({ repeat: false, duration: 500 })') >= 0,
    '长按半秒手势（上游 0.5s 同量级）');

  const pills: string = sourceText('entry/src/main/ets/ui/ParamPills.ets');
  assertTrue(pills.indexOf("'锁定对焦'") >= 0 && pills.indexOf("'解除锁定'") >= 0,
    '对焦胶囊必须有锁定/解除条目（上游副控制）');
  assertTrue(pills.indexOf('this.onToggleLock();') >= 0,
    '胶囊条目必须经宿主页 onToggleLock 过闸（不许直写总线绕过门禁）');
});
