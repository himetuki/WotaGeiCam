/**
 * 内录授权链路状态机（直译上游 record/CaptureStateMachine.kt，对齐上游 38d712a）。
 *
 * 纯逻辑（不 import 平台 Kit）：状态 `Idle → Authorizing → Active / Failed → Revoked`
 * （Revoked / Failed / Active 均可重新授权）。「Revoked = 授权过的会话被撤销」承载环境音
 * 回退语义：见此态即回落麦克风采环境音。
 *
 * ArkTS 落法：
 * - Kotlin sealed class → kind 判别字段 + 静态工厂（Failed/EstablishFailed 的 reason 同携带）；
 * - Kotlin when 全函数 → if 链逐条对位（未列出的 (state,event) 组合原态返回，无异常路径）。
 *
 * T26 边界：本文件只落状态转移与短名；平台面（MediaProjection/AVScreenCapture 会话、
 * 隐私授权、前台服务）留 T27 真机批（见 PORT_MAP「T26 平台调研」）。
 */

/** 状态种类 */
export enum CaptureStateKind {
  /** 未授权、无会话 */
  IDLE = 'IDLE',
  /** 授权流程中：系统授权框已拉起，或同意后正在建链 */
  AUTHORIZING = 'AUTHORIZING',
  /** 会话在位，可建设备内录 */
  ACTIVE = 'ACTIVE',
  /** 建链失败（reason 供 UI 展示）；可重新授权 */
  FAILED = 'FAILED',
  /** 会话被撤销（用户停止投屏 / 系统收回）：据此回退环境音 */
  REVOKED = 'REVOKED'
}

/** 事件种类 */
export enum CaptureEventKind {
  /** 拉起系统授权框 */
  CONSENT_REQUESTED = 'CONSENT_REQUESTED',
  /** 用户在授权框取消/返回 */
  CONSENT_CANCELLED = 'CONSENT_CANCELLED',
  /** 建链全链成功 */
  ESTABLISHED = 'ESTABLISHED',
  /** 建链任一步失败 */
  ESTABLISH_FAILED = 'ESTABLISH_FAILED',
  /** onStop：用户停止投屏或系统撤销 */
  PROJECTION_STOPPED = 'PROJECTION_STOPPED',
  /** 主动关停 */
  SHUTDOWN = 'SHUTDOWN'
}

/** 状态（kind + reason；reason 仅 FAILED 有意义，其余为空串） */
export class CaptureState {
  readonly kind: CaptureStateKind;
  readonly reason: string;

  private constructor(kind: CaptureStateKind, reason: string) {
    this.kind = kind;
    this.reason = reason;
  }

  static idle(): CaptureState {
    return new CaptureState(CaptureStateKind.IDLE, '');
  }

  static authorizing(): CaptureState {
    return new CaptureState(CaptureStateKind.AUTHORIZING, '');
  }

  static active(): CaptureState {
    return new CaptureState(CaptureStateKind.ACTIVE, '');
  }

  static failed(reason: string): CaptureState {
    return new CaptureState(CaptureStateKind.FAILED, reason);
  }

  static revoked(): CaptureState {
    return new CaptureState(CaptureStateKind.REVOKED, '');
  }

  /** 同态判定（忽略 reason；测试与 UI 分支用） */
  isKind(kind: CaptureStateKind): boolean {
    return this.kind === kind;
  }
}

/** 事件（kind + reason；reason 仅 ESTABLISH_FAILED 有意义） */
export class CaptureEvent {
  readonly kind: CaptureEventKind;
  readonly reason: string;

  private constructor(kind: CaptureEventKind, reason: string) {
    this.kind = kind;
    this.reason = reason;
  }

  static consentRequested(): CaptureEvent {
    return new CaptureEvent(CaptureEventKind.CONSENT_REQUESTED, '');
  }

  static consentCancelled(): CaptureEvent {
    return new CaptureEvent(CaptureEventKind.CONSENT_CANCELLED, '');
  }

  static established(): CaptureEvent {
    return new CaptureEvent(CaptureEventKind.ESTABLISHED, '');
  }

  static establishFailed(reason: string): CaptureEvent {
    return new CaptureEvent(CaptureEventKind.ESTABLISH_FAILED, reason);
  }

  static projectionStopped(): CaptureEvent {
    return new CaptureEvent(CaptureEventKind.PROJECTION_STOPPED, '');
  }

  static shutdown(): CaptureEvent {
    return new CaptureEvent(CaptureEventKind.SHUTDOWN, '');
  }
}

/**
 * 状态转移表（全函数、无异常路径）：未列出的 (state, event) 组合一律原态返回。
 * - CONSENT_REQUESTED：Idle/Active/Failed/Revoked 都可发起（Active 发起 = 重复授权重建）；
 *   已在 Authorizing 时重复拉起无效。
 * - CONSENT_CANCELLED：只从 Authorizing 回滚到 Idle，其余态原样。
 * - ESTABLISHED / ESTABLISH_FAILED：只在 Authorizing 生效（乱序回执不凭空改态）。
 * - PROJECTION_STOPPED：Authorizing/Active/Failed 时都可能发生 → Revoked。
 * - SHUTDOWN：任意态归 Idle。
 */
export function transition(state: CaptureState, event: CaptureEvent): CaptureState {
  if (event.kind === CaptureEventKind.CONSENT_REQUESTED) {
    return state.kind === CaptureStateKind.AUTHORIZING ? state : CaptureState.authorizing();
  }
  if (event.kind === CaptureEventKind.CONSENT_CANCELLED) {
    return state.kind === CaptureStateKind.AUTHORIZING ? CaptureState.idle() : state;
  }
  if (event.kind === CaptureEventKind.ESTABLISHED) {
    return state.kind === CaptureStateKind.AUTHORIZING ? CaptureState.active() : state;
  }
  if (event.kind === CaptureEventKind.ESTABLISH_FAILED) {
    return state.kind === CaptureStateKind.AUTHORIZING
      ? CaptureState.failed(event.reason) : state;
  }
  if (event.kind === CaptureEventKind.PROJECTION_STOPPED) {
    if (state.kind === CaptureStateKind.AUTHORIZING ||
      state.kind === CaptureStateKind.ACTIVE ||
      state.kind === CaptureStateKind.FAILED) {
      return CaptureState.revoked();
    }
    return state;
  }
  // SHUTDOWN
  return CaptureState.idle();
}

/**
 * 状态短名（日志可读；上游 sealed toString 在 R8 后是混淆名，本侧固定字面量逐字对齐）。
 * 不写兜底分支的等价物：kind 为封闭枚举，新增成员须在此显式补映射。
 */
export function captureStateLabel(state: CaptureState): string {
  if (state.kind === CaptureStateKind.IDLE) {
    return '未授权';
  }
  if (state.kind === CaptureStateKind.AUTHORIZING) {
    return '授权中';
  }
  if (state.kind === CaptureStateKind.ACTIVE) {
    return '已激活';
  }
  if (state.kind === CaptureStateKind.FAILED) {
    return '失败';
  }
  if (state.kind === CaptureStateKind.REVOKED) {
    return '已撤销';
  }
  // 封闭枚举的新增成员若未在此补映射，必须显式失败而不是静默落到某一旧短名
  throw new Error(`unknown capture state kind: ${state.kind}`);
}

/** 事件短名（EstablishFailed 的 reason 码不进短名，详见日志原文） */
export function captureEventLabel(event: CaptureEvent): string {
  if (event.kind === CaptureEventKind.CONSENT_REQUESTED) {
    return '请求授权';
  }
  if (event.kind === CaptureEventKind.CONSENT_CANCELLED) {
    return '用户取消授权';
  }
  if (event.kind === CaptureEventKind.ESTABLISHED) {
    return '建链成功';
  }
  if (event.kind === CaptureEventKind.ESTABLISH_FAILED) {
    return '建链失败';
  }
  if (event.kind === CaptureEventKind.PROJECTION_STOPPED) {
    return '会话被撤销';
  }
  if (event.kind === CaptureEventKind.SHUTDOWN) {
    return '主动关停';
  }
  // 同 captureStateLabel：新增事件未补映射必须显式失败
  throw new Error(`unknown capture event kind: ${event.kind}`);
}
