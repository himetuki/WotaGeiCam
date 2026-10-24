/**
 * T27-1 平台探针的**结果模型**（纯逻辑，零平台 import）。
 *
 * native 探针状态串契约（cpp/avscreen_probe.cpp）：
 * `idle` | `running:<elapsedMs>` | `cancelled`
 * | `ok:inner=<cb>/<bytes>:mic=<cb>/<bytes>:states=<s1,s2|- >:err=<code>:elapsedMs=<ms>`
 * | `failed:<step>[:<ret>[:err=<code>]]`
 *
 * 三条取证结论（PORT_MAP T27 前置）：
 * ① **隐私弹窗形态**：`failed:init`/`failed:start` 的返回码 + `err=` 错误码原样保留
 *   （真机取证时不解读——OHOS 错误码与用户拒绝路径的对应关系要拿真机日志对上才算数）；
 * ② **双源同采**：inner 与 mic 两类音频缓冲都 >0 才算 dual（T27-3 的可行性依据）；
 * ③ **节奏**：每源回调数/字节数 + elapsedMs（预校准要 800ms 双源，这里给实证样本）。
 */
export enum AvScreenProbeVerdict {
  /** 双源都出数据：T27-3 双轨可行 */
  DUAL_SOURCE = 'dual-source',
  /** 只出内录（或只出麦克风）：双源不可行，T27 只能单轨 */
  SINGLE_SOURCE = 'single-source',
  /** 起来了但两类都零回调（静音窗口/无声源）：不能下结论，需带声源复测 */
  SILENT_WINDOW = 'silent-window',
  /** 用户取消 */
  CANCELLED = 'cancelled',
  /** Init/Start 失败（隐私拒绝/权限/能力）：取证仍有效——失败形态本身就是结论 */
  BLOCKED = 'blocked',
  /** 还在跑/桥接失败：无结论 */
  RUNNING = 'running',
  FAILED = 'failed'
}

/** 一类音频源的回执账 */
export class AvSourceCounters {
  callbacks: number;
  bytes: number;

  constructor(callbacks: number, bytes: number) {
    this.callbacks = callbacks;
    this.bytes = bytes;
  }

  get hasData(): boolean {
    return this.callbacks > 0 && this.bytes > 0;
  }
}

/** 探针结论（一次取证的完整快照） */
export class AvScreenProbeInfo {
  verdict: AvScreenProbeVerdict;
  inner: AvSourceCounters;
  mic: AvSourceCounters;
  /** native 原样错误码（onError 最近一次；0=无） */
  lastError: number;
  /** OH_SCREEN_CAPTURE_STATE_* 原值序列（封顶 8；空数组=没收到状态回调） */
  states: Array<number>;
  elapsedMs: number;
  /** 失败机器码（failed: 后面的整段）或原生状态串（解读不丢原文） */
  raw: string;

  constructor(verdict: AvScreenProbeVerdict, inner: AvSourceCounters, mic: AvSourceCounters,
    lastError: number, states: Array<number>, elapsedMs: number, raw: string) {
    this.verdict = verdict;
    this.inner = inner;
    this.mic = mic;
    this.lastError = lastError;
    this.states = states;
    this.elapsedMs = elapsedMs;
    this.raw = raw;
  }

  static ofFail(machine: string): AvScreenProbeInfo {
    return new AvScreenProbeInfo(AvScreenProbeVerdict.FAILED,
      new AvSourceCounters(0, 0), new AvSourceCounters(0, 0), 0, [], 0, `failed:${machine}`);
  }

  /** 一行读数（开发者页 message 直接用） */
  summary(): string {
    if (this.verdict === AvScreenProbeVerdict.DUAL_SOURCE) {
      return `双源 ✓ 内录 ${this.inner.callbacks} 次/${this.inner.bytes}B · 麦克风 ` +
        `${this.mic.callbacks} 次/${this.mic.bytes}B（${this.elapsedMs}ms）`;
    }
    if (this.verdict === AvScreenProbeVerdict.SINGLE_SOURCE) {
      const which: string = this.inner.hasData ? '仅内录' : '仅麦克风';
      return `${which}：内录 ${this.inner.callbacks}/${this.inner.bytes}B · 麦克风 ` +
        `${this.mic.callbacks}/${this.mic.bytes}B（${this.elapsedMs}ms）——双源不可行，T27 单轨`;
    }
    if (this.verdict === AvScreenProbeVerdict.SILENT_WINDOW) {
      return `窗口内无音频回调（${this.elapsedMs}ms）——带声源（放歌/说话）复测再下结论`;
    }
    if (this.verdict === AvScreenProbeVerdict.CANCELLED) {
      return '已取消';
    }
    if (this.verdict === AvScreenProbeVerdict.BLOCKED) {
      return `被拒/失败（取证有效）：${this.raw}`;
    }
    return this.raw;
  }
}

/** 解析的单一真源（native 串 → 结论；串还在跑/不可解返回 undefined 由轮询继续） */
export class AvScreenProbeModel {
  static parse(status: string): AvScreenProbeInfo | undefined {
    if (status === 'idle' || status.startsWith('running:')) {
      return undefined;
    }
    if (status === 'cancelled') {
      return new AvScreenProbeInfo(AvScreenProbeVerdict.CANCELLED,
        new AvSourceCounters(0, 0), new AvSourceCounters(0, 0), 0, [], 0, status);
    }
    if (status.startsWith('failed:')) {
      // Init/Start 失败：取证结论本身（隐私弹窗拒绝路径的错误码形态）
      return new AvScreenProbeInfo(AvScreenProbeVerdict.BLOCKED,
        new AvSourceCounters(0, 0), new AvSourceCounters(0, 0), 0, [], 0, status);
    }
    if (!status.startsWith('ok:')) {
      return AvScreenProbeInfo.ofFail(status === '' ? 'empty' : 'unknown');
    }
    const fields: Map<string, string> = new Map<string, string>();
    for (const seg of status.split(':')) {
      const eq: number = seg.indexOf('=');
      if (eq > 0) {
        fields.set(seg.substring(0, eq), seg.substring(eq + 1));
      }
    }
    const inner: AvSourceCounters = AvScreenProbeModel.countersOf(fields.get('inner'));
    const mic: AvSourceCounters = AvScreenProbeModel.countersOf(fields.get('mic'));
    const lastError: number = AvScreenProbeModel.intOf(fields.get('err'), 0);
    const elapsedMs: number = AvScreenProbeModel.intOf(fields.get('elapsedMs'), 0);
    const states: Array<number> = [];
    const rawStates: string | undefined = fields.get('states');
    if (rawStates !== undefined && rawStates !== '-') {
      for (const tok of rawStates.split(',')) {
        const v: number = Number.parseInt(tok, 10);
        if (Number.isFinite(v)) {
          states.push(v);
        }
      }
    }
    let verdict: AvScreenProbeVerdict;
    if (inner.hasData && mic.hasData) {
      verdict = AvScreenProbeVerdict.DUAL_SOURCE;
    } else if (inner.hasData || mic.hasData) {
      verdict = AvScreenProbeVerdict.SINGLE_SOURCE;
    } else {
      verdict = AvScreenProbeVerdict.SILENT_WINDOW;
    }
    return new AvScreenProbeInfo(verdict, inner, mic, lastError, states, elapsedMs, status);
  }

  private static countersOf(raw: string | undefined): AvSourceCounters {
    if (raw === undefined) {
      return new AvSourceCounters(0, 0);
    }
    const parts: Array<string> = raw.split('/');
    const cb: number = parts.length > 0 ? (Number.parseInt(parts[0], 10) || 0) : 0;
    const bytes: number = parts.length > 1 ? (Number.parseInt(parts[1], 10) || 0) : 0;
    return new AvSourceCounters(cb, bytes);
  }

  private static intOf(raw: string | undefined, def: number): number {
    if (raw === undefined) {
      return def;
    }
    const v: number = Number.parseInt(raw, 10);
    return Number.isFinite(v) ? v : def;
  }
}
