/**
 * 停止后自动校准的纯决策面（player/PostStopSyncPlan.ets）单测——对拍上游
 * player/PostStopSync.kt（e950328）的段筛选 / ≤4 段逐段 vs 首段+复制记「近似」策略。
 *
 * 上游 PostStopSyncGuardTest 是对 schedule/stopInternal 源码的接线扫描守卫（平台调度
 * taskpool/串行 Promise 链留 T27），不移植；这里测的是它守护的同一段策略的纯函数面。
 * 期望值一律手写字面量（MAX_EXACT_SEGMENTS=4）。
 */
import {
  PostStopPlan, PostStopSegment, PostStopSyncPlan
} from '../../entry/src/main/ets/player/PostStopSyncPlan';

function keys(n: number): Array<string> {
  const out: Array<string> = [];
  for (let i: number = 0; i < n; i++) {
    out.push(`seg${i}`);
  }
  return out;
}

test('段筛选_只留请求双音轨的段', (): void => {
  assertEq(0, PostStopSyncPlan.requestedKeys([]).length, '空列表不调度');
  const segments: Array<PostStopSegment> = [
    new PostStopSegment('seg0', true),
    new PostStopSegment('seg1', false),
    new PostStopSegment('seg2', true)
  ];
  const out: Array<string> = PostStopSyncPlan.requestedKeys(segments);
  assertEq(2, out.length, '两段请求了双音轨');
  assertEq('seg0', out[0], '保序 0');
  assertEq('seg2', out[1], '保序 2（跳过 seg1）');
  // 全部未请求 = 上游 requested.isEmpty 直接回
  assertEq(0, PostStopSyncPlan.requestedKeys([
    new PostStopSegment('a', false), new PostStopSegment('b', false)
  ]).length, '无请求段不调度');
});

test('实际双轨复核_谓词过滤单轨段', (): void => {
  // captureAudio 只是请求：内录启动失败/中途降级的段实际是单轨，不许被标 sync_done
  const out: Array<string> = PostStopSyncPlan.actualDualKeys(['seg0', 'seg1', 'seg2'],
    (key: string): boolean => key !== 'seg1');
  assertEq(2, out.length, '单轨段被复核剔除');
  assertEq('seg0', out[0], '保序 0');
  assertEq('seg2', out[1], '保序 2');
  // 复核抛异常的段按非双轨处理（上游 runCatching.getOrDefault(false)）
  const guarded: Array<string> = PostStopSyncPlan.actualDualKeys(['bad', 'ok'],
    (key: string): boolean => {
      if (key === 'bad') {
        throw new Error('countAudioTracks failed');
      }
      return true;
    });
  assertEq(1, guarded.length, '异常段剔除不连坐');
  assertEq('ok', guarded[0], '正常段保留');
});

test('四段以内_逐段精确不近似', (): void => {
  assertEq(4, PostStopSyncPlan.MAX_EXACT_SEGMENTS, '上限常量 4');
  for (let n: number = 1; n <= 4; n++) {
    const plan: PostStopPlan = PostStopSyncPlan.planFor(keys(n));
    assertFalse(plan.approximate, `${n} 段不近似`);
    assertEq(n, plan.measureKeys.length, `${n} 段全量实测`);
    assertEq(0, plan.copyKeys.length, `${n} 段无复制（每段独立容器起点差，复制会带误差）`);
  }
});

test('恰四段边界_逐段精确', (): void => {
  const plan: PostStopPlan = PostStopSyncPlan.planFor(keys(4));
  assertFalse(plan.approximate, '4 段 = 上限内仍逐段');
  assertEq('seg3', plan.measureKeys[3], '末段也在实测清单');
});

test('超四段_首段测量加复制记近似', (): void => {
  const plan: PostStopPlan = PostStopSyncPlan.planFor(keys(5));
  assertTrue(plan.approximate, '5 段退回首段近似');
  assertEq(1, plan.measureKeys.length, '只测首段');
  assertEq('seg0', plan.measureKeys[0], '首段');
  assertEq(4, plan.copyKeys.length, '其余 4 段复制');
  assertEq('seg1', plan.copyKeys[0], '复制段保序起 1');
  assertEq('seg4', plan.copyKeys[3], '复制段保序迄 4');
});

test('空计划_消费方直接收工', (): void => {
  const plan: PostStopPlan = PostStopSyncPlan.planFor([]);
  assertFalse(plan.approximate, '空计划不近似');
  assertEq(0, plan.measureKeys.length, '无实测段');
  assertEq(0, plan.copyKeys.length, '无复制段（单轨场不许标 sync_done）');
});
