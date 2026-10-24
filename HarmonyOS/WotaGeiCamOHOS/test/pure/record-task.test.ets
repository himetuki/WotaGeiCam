/**
 * 录制长时任务（RT 批）：闸状态机（纯逻辑）+ 接线守卫。
 * 平台件（RecordTask 的 backgroundTaskManager 调用）在 pure-test 里只有惰性桩。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { RecordTaskGate } from '../../entry/src/main/ets/record/RecordTaskGate';

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

// ------------------------------------------------------------------ 闸状态机（纯逻辑）

test('闸_起录迁移一次_重复起不迁移', () => {
  const gate: RecordTaskGate = new RecordTaskGate();
  assertTrue(gate.onStart(), '首次起录 → 迁移');
  assertFalse(gate.active === false, '运行中');
  assertFalse(gate.onStart(), '健康重录段再起 → 不迁移（不重复申请系统任务）');
  assertFalse(gate.onStart(), '第三次同样不迁移');
  assertTrue(gate.active, '仍是同一个任务会话');
});

test('闸_停录迁移一次_重复停与未起过的停都不迁移', () => {
  const gate: RecordTaskGate = new RecordTaskGate();
  assertFalse(gate.onStop(), '没起过就停 → 不迁移（不碰系统接口）');
  assertTrue(gate.onStart());
  assertTrue(gate.onStop(), '停录 → 迁移');
  assertFalse(gate.onStop(), '重复停（finishRecord 的多条终态路径汇流）→ 不迁移');
  assertFalse(gate.active);
  assertTrue(gate.onStart(), '下一轮起录重新可用');
});

test('闸_起停整环幂等', () => {
  const gate: RecordTaskGate = new RecordTaskGate();
  let transitions: number = 0;
  for (let i = 0; i < 3; i++) {
    if (gate.onStart()) {
      transitions++;
    }
    if (gate.onStart()) {
      transitions++;
    }
    if (gate.onStop()) {
      transitions++;
    }
    if (gate.onStop()) {
      transitions++;
    }
  }
  assertEq(6, transitions, '三轮起停只有 6 次迁移（每轮起 1 次 + 停 1 次）');
});

// ------------------------------------------------------------------ 接线守卫（源码扫描）

test('接线_平台件走长时任务与TASK_KEEPING', () => {
  const task: string = sourceText('entry/src/main/ets/record/RecordTask.ets');
  assertTrue(task.indexOf('@kit.BackgroundTasksKit') >= 0, '必须走 backgroundTaskManager（长时任务）');
  assertTrue(task.indexOf('BackgroundMode.TASK_KEEPING') >= 0,
    '视频-only 录制用 TASK_KEEPING（无「视频录制」专属档；T27-3 内录落地后切 AUDIO_RECORDING，登记）');
  assertTrue(task.indexOf('RecordTaskGate') >= 0, '启停必须经闸（重复调用不碰系统接口）');
  assertTrue(task.indexOf('wantAgent.getWantAgent') >= 0, '通知点击回 app 需要 wantAgent');
  assertTrue(task.indexOf('startForegroundService') < 0 && task.indexOf('ForegroundService') < 0,
    'OHOS 无前台 Service 对应物（不许仿写）');

  // Camera 只在起录成功与 finishRecord 收口两处碰 RecordTask；失败静默（不阻塞录制）
  const cam: string = sourceText('entry/src/main/ets/pages/Camera.ets');
  assertTrue(cam.indexOf('RecordTask.start(this.ctx)') >= 0, '起录成功后必须挂长时任务');
  assertTrue(cam.indexOf('RecordTask.stop(this.ctx)') >= 0, 'finishRecord 收口必须解长时任务');
  const manifest: string = readFileSync(resolve(srcRoot(), 'entry/src/main/module.json5'), 'utf8');
  assertTrue(manifest.indexOf('ohos.permission.KEEP_BACKGROUND_RUNNING') >= 0,
    '必须声明 KEEP_BACKGROUND_RUNNING（normal 级）');
});
