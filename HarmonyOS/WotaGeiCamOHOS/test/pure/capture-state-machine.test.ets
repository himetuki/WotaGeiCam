/**
 * 内录授权状态机（record/CaptureStateMachine.ets）全转移表单测——对拍上游
 * app/src/test/java/com/wotagei/cam/record/CaptureStateMachineTest.kt（14 条转移，对齐上游 38d712a）
 * + 状态/事件短名逐字两条（上游 CaptureTraceLabelTest 的映射面）。
 *
 * 红/绿纪律：把 transition 任一分支改写（取消回 Authorizing、撤销改 Failed、重复拉起改态），
 * 对应用例即红。期望值一律手写字面量。
 */
import {
  CaptureEvent, CaptureEventKind, CaptureState, CaptureStateKind, captureEventLabel, captureStateLabel,
  transition
} from '../../entry/src/main/ets/record/CaptureStateMachine';

/** 从 Idle 依次投递事件（上游 run(vararg events) 等价物） */
function run(events: Array<CaptureEvent>): CaptureState {
  let state: CaptureState = CaptureState.idle();
  for (const e of events) {
    state = transition(state, e);
  }
  return state;
}

test('正常链路 Idle拉起授权建链到Active', (): void => {
  const s: CaptureState = run([CaptureEvent.consentRequested(), CaptureEvent.established()]);
  assertEq(CaptureStateKind.ACTIVE, s.kind, 'Idle→Authorizing→Active');
});

test('建立失败 Authorizing转Failed并带原因', (): void => {
  const s: CaptureState = run([CaptureEvent.consentRequested(), CaptureEvent.establishFailed('boom')]);
  assertEq(CaptureStateKind.FAILED, s.kind, '进 Failed');
  assertEq('boom', s.reason, 'reason 透传');
});

test('授权取消回滚 Authorizing取消回Idle', (): void => {
  const s: CaptureState = run([CaptureEvent.consentRequested(), CaptureEvent.consentCancelled()]);
  assertEq(CaptureStateKind.IDLE, s.kind, '回 Idle');
});

test('取消在非Authorizing态不生效 原态保持', (): void => {
  assertEq(CaptureStateKind.IDLE, transition(CaptureState.idle(), CaptureEvent.consentCancelled()).kind, 'Idle');
  assertEq(CaptureStateKind.ACTIVE, transition(CaptureState.active(), CaptureEvent.consentCancelled()).kind, 'Active');
  assertEq(CaptureStateKind.REVOKED, transition(CaptureState.revoked(), CaptureEvent.consentCancelled()).kind, 'Revoked');
});

test('Active被撤销 转Revoked 据此回环境音', (): void => {
  const s: CaptureState = run([CaptureEvent.consentRequested(), CaptureEvent.established(),
    CaptureEvent.projectionStopped()]);
  assertEq(CaptureStateKind.REVOKED, s.kind, 'Active→Revoked');
});

test('建链中被撤销 也进Revoked 回环境音', (): void => {
  const s: CaptureState = run([CaptureEvent.consentRequested(), CaptureEvent.projectionStopped()]);
  assertEq(CaptureStateKind.REVOKED, s.kind, 'Authorizing→Revoked');
});

test('Failed态被撤销 进Revoked', (): void => {
  const s: CaptureState = run([CaptureEvent.consentRequested(), CaptureEvent.establishFailed('x'),
    CaptureEvent.projectionStopped()]);
  assertEq(CaptureStateKind.REVOKED, s.kind, 'Failed→Revoked');
});

test('Idle或Revoked态收到ProjectionStopped 原态保持', (): void => {
  assertEq(CaptureStateKind.IDLE, transition(CaptureState.idle(), CaptureEvent.projectionStopped()).kind, 'Idle');
  assertEq(CaptureStateKind.REVOKED, transition(CaptureState.revoked(), CaptureEvent.projectionStopped()).kind, 'Revoked');
});

test('Active下重复授权 回Authorizing并可重建到Active', (): void => {
  const s: CaptureState = run([CaptureEvent.consentRequested(), CaptureEvent.established(),
    CaptureEvent.consentRequested(), CaptureEvent.established()]);
  assertEq(CaptureStateKind.ACTIVE, s.kind, '重复授权仍到 Active');
});

test('Revoked重新授权 可再走完整链路到Active', (): void => {
  const s: CaptureState = run([CaptureEvent.consentRequested(), CaptureEvent.established(),
    CaptureEvent.projectionStopped(), CaptureEvent.consentRequested(), CaptureEvent.established()]);
  assertEq(CaptureStateKind.ACTIVE, s.kind, 'Revoked 可恢复');
});

test('Failed重新授权 可恢复到Active', (): void => {
  const s: CaptureState = run([CaptureEvent.consentRequested(), CaptureEvent.establishFailed('x'),
    CaptureEvent.consentRequested(), CaptureEvent.established()]);
  assertEq(CaptureStateKind.ACTIVE, s.kind, 'Failed 可恢复');
});

test('Authorizing中重复拉起授权框 保持Authorizing', (): void => {
  const s: CaptureState = transition(CaptureState.authorizing(), CaptureEvent.consentRequested());
  assertEq(CaptureStateKind.AUTHORIZING, s.kind, '重复拉起不改态');
});

test('乱序回执 Established与EstablishFailed只在Authorizing生效', (): void => {
  assertEq(CaptureStateKind.ACTIVE, transition(CaptureState.active(), CaptureEvent.established()).kind, 'Active 收 Established 原态');
  assertEq(CaptureStateKind.ACTIVE, transition(CaptureState.active(), CaptureEvent.establishFailed('late')).kind, 'Active 收 Failed 原态');
  assertEq(CaptureStateKind.IDLE, transition(CaptureState.idle(), CaptureEvent.established()).kind, 'Idle 收 Established 原态');
  const late: CaptureState = transition(CaptureState.revoked(), CaptureEvent.establishFailed('late'));
  assertEq(CaptureStateKind.REVOKED, late.kind, 'Revoked 收 Failed 原态');
  assertEq('', late.reason, '脏回执的 reason 不进态');
});

test('关停归Idle 任意态幂等', (): void => {
  const states: Array<CaptureState> = [
    CaptureState.idle(), CaptureState.authorizing(), CaptureState.active(),
    CaptureState.failed('x'), CaptureState.revoked()
  ];
  for (const st of states) {
    assertEq(CaptureStateKind.IDLE, transition(st, CaptureEvent.shutdown()).kind,
      `从 ${st.kind} 关停必须归 Idle`);
  }
  assertEq(CaptureStateKind.IDLE, transition(CaptureState.idle(), CaptureEvent.shutdown()).kind,
    'Idle 再关停仍 Idle');
});

test('状态短名逐字', (): void => {
  // 审查修复 P3：枚举成员数断言——新增成员而忘补映射时，标签函数会 throw（不再有 default 静默兜底）
  assertEq(5, Object.keys(CaptureStateKind).length, '状态枚举 5 成员');
  assertEq('未授权', captureStateLabel(CaptureState.idle()), 'Idle');
  assertEq('授权中', captureStateLabel(CaptureState.authorizing()), 'Authorizing');
  assertEq('已激活', captureStateLabel(CaptureState.active()), 'Active');
  assertEq('失败', captureStateLabel(CaptureState.failed('boom')), 'Failed（reason 不进短名）');
  assertEq('已撤销', captureStateLabel(CaptureState.revoked()), 'Revoked');
});

test('事件短名逐字', (): void => {
  assertEq(6, Object.keys(CaptureEventKind).length, '事件枚举 6 成员');
  assertEq('请求授权', captureEventLabel(CaptureEvent.consentRequested()), 'ConsentRequested');
  assertEq('用户取消授权', captureEventLabel(CaptureEvent.consentCancelled()), 'ConsentCancelled');
  assertEq('建链成功', captureEventLabel(CaptureEvent.established()), 'Established');
  assertEq('建链失败', captureEventLabel(CaptureEvent.establishFailed('boom')), 'EstablishFailed');
  assertEq('会话被撤销', captureEventLabel(CaptureEvent.projectionStopped()), 'ProjectionStopped');
  assertEq('主动关停', captureEventLabel(CaptureEvent.shutdown()), 'Shutdown');
  assertEq(CaptureEventKind.ESTABLISH_FAILED, CaptureEvent.establishFailed('x').kind, '事件 kind 判别');
});
