/**
 * 对比播放的双轨时间线内核（直译上游 player/CompareTimeline.kt，对齐上游 38d712a）。
 *
 * 平台差异 / 本批边界：
 * - 上游 TimelineGeometry 的 tMin/tMax 是 Kotlin 计算属性，ArkTS 侧落成同名方法
 *   （tMin()/tMax()），避免属性访问器在调用侧产生"字段还是方法"的歧义；
 * - Long → number：时长/偏移均为毫秒整数，值域远小于 2^53；
 * - Kotlin sealed/data class → 普通 class + 构造函数一次性构造（data class copy() 不做等价物）；
 * - 本文件是纯逻辑（不 import 平台 Kit），T24 起含域判定/主钟/决策全量内核；
 *   页面编排接线（Compare.ets）留待 T25。
 *
 * # 时间线定义
 * T ≡ 左片时间轴的推广（可负可超尾）。符号约定：**rightPos = leftPos + offsetMs**。
 * - 左轨素材段 [0, L)；右轨素材段 [−off, R−off)；
 * - Tmin = min(0, −off)，Tmax = max(L, R−off)。
 *
 * # 三种素材域与交接
 * - 双素材域：主钟=左引擎（T=leftLive），右软跟随；
 * - 右独播域：主钟=右（T=rightPos−off），左 dormant（pause+黑层由编排器施加）；
 * - 左独播域：主钟=左，右 dormant。
 * **交接不 seek**：位置天然连续，编排 tick 检测 T 跨域后唤醒/置 dormant；唯一发 seek 的是
 * 时间线末回绕（wrap 到 Tmin）。
 *
 * # 域判定为什么需要黑层状态回灌
 * 纯位置区分不了「某轨停在域边界（park）」与「它真的在双素材域」——park 点本身落在覆盖判定
 * 的边界值上。DecideInput.blackLeft/blackRight 带上一拍已施加的黑层位（seekTimeline 落 park 时
 * 也直接写），在册独播域据此锁定主钟，直到 T 回到双覆盖区才交回双主钟。
 *
 * # 素材尽头 park 落点用 durMs 而不是 durMs−1（刻意偏离任务书字面）
 * dur−1 仍在覆盖判定区间 [0, L) 内，park 在那里会被域判定读成"双素材域"；park 在 durMs 上
 * 引擎进 ENDED 停末帧，位置读数 t=L 落在覆盖区外，与"素材已尽"的域判定自洽。
 */

/** 时间线几何快照（左片时长、右片时长、右片相对左片的时间偏移） */
export class TimelineGeometry {
  readonly leftDurMs: number;
  readonly rightDurMs: number;
  readonly offsetMs: number;

  constructor(leftDurMs: number, rightDurMs: number, offsetMs: number) {
    this.leftDurMs = Math.max(0, leftDurMs);
    this.rightDurMs = Math.max(0, rightDurMs);
    this.offsetMs = offsetMs;
  }

  /** 共同时间线起点：offset > 0 时左侧先于右侧（负轴），否则 0（-0 归一到 +0，对齐上游 Long 语义） */
  tMin(): number {
    return normZero(Math.min(0, -this.offsetMs));
  }

  /** 共同时间线终点：任一侧仍能出画面的最晚时刻 */
  tMax(): number {
    return Math.max(this.leftDurMs, this.rightDurMs - this.offsetMs);
  }

  /** 左片在共同时刻 t 是否仍有画面（右端开区间，避免停在最后一帧之外） */
  leftCovers(t: number): boolean {
    return t >= 0 && t < this.leftDurMs;
  }

  /** 右片在共同时刻 t 是否仍有画面 */
  rightCovers(t: number): boolean {
    return t >= -this.offsetMs && t < this.rightDurMs - this.offsetMs;
  }

  /** 左片在 t 的落点；超出两端时停在首帧/末帧（park） */
  leftTarget(t: number): number {
    if (this.leftCovers(t)) {
      return t;
    }
    return t < 0 ? 0 : this.leftDurMs;
  }

  /** 右片在 t 的落点；超出两端时停在首帧/末帧（park） */
  rightTarget(t: number): number {
    if (this.rightCovers(t)) {
      return t + this.offsetMs;
    }
    return t < -this.offsetMs ? 0 : this.rightDurMs;
  }
}

/**
 * 手动偏移归一化：offset > 0 表示右片晚于左片（右侧从 offset 处开始），
 * 允许范围 [-rightDur, leftDur]——再往两边拉只会让一侧整段停帧，没有信息增量。
 */
export function normalizeOffset(offsetMs: number, leftDurMs: number, rightDurMs: number): number {
  const left: number = Math.max(0, leftDurMs);
  const right: number = Math.max(0, rightDurMs);
  return normZero(Math.min(left, Math.max(-right, offsetMs)));
}

/** 左游标 leftPos 对应的右片落点（0..rightDur 夹紧；offset 已由调用方归一） */
export function rightTargetFor(leftPosMs: number, offsetMs: number, rightDurMs: number): number {
  const right: number = Math.max(0, rightDurMs);
  return normZero(Math.min(right, Math.max(0, leftPosMs + offsetMs)));
}

/** 共同时间线总长（tMax − tMin，恒 ≥ 0） */
export function timelineSpan(geometry: TimelineGeometry): number {
  return Math.max(0, geometry.tMax() - geometry.tMin());
}

/**
 * -0 归一到 +0：JS 的 Math.min/max 在 off=0 时会产出 -0（Math.min(0, -0) = -0），
 * Object.is/映射键等严格相等消费者会与 0 区分；上游 Kotlin Long 无 -0，数值语义以 +0 对齐
 * （2026-10-22 纯逻辑测试批发现并订正，登记）。
 */
function normZero(v: number): number {
  return v === 0 ? 0 : v;
}

/** 共同时间线终点（与 tMax 同值；供 UI 进度条取上限） */
export function timelineEnd(geometry: TimelineGeometry): number {
  return geometry.tMax();
}

// ---------------------------------------------------------------------------
// T24：域判定 / 主钟 / 一拍决策（上游 CompareTimeline.kt 全量内核）
// ---------------------------------------------------------------------------

/** 素材域（上游 Kotlin enum class CompareDomain） */
export enum CompareDomain {
  /** 左独播：主钟=左，右 dormant（右窗黑） */
  LEFT_SOLO = 'LEFT_SOLO',
  /** 右独播：主钟=右，左 dormant（左窗黑） */
  RIGHT_SOLO = 'RIGHT_SOLO',
  /** 双素材域：主钟=左，右软跟随 */
  DUAL = 'DUAL',
  /** 时间线末：两段素材都已尽 */
  TIMELINE_END = 'TIMELINE_END'
}

/**
 * decide 的输入快照（一拍一份；黑层位是上一拍输出/seekTimeline 写入的回灌）。
 * userPlaying = 用户期望播放（两引擎 isPlaying 并集）；paused 时主钟不动、纠偏照跑（与旧循环同口径）。
 */
export class DecideInput {
  readonly geo: TimelineGeometry;
  readonly leftLiveMs: number;
  readonly rightLiveMs: number;
  readonly userPlaying: boolean;
  /** AB 段循环激活（loopEnabled 且 A/B 合法）：回绕归 abWrapHook，decide 不抢 */
  readonly abLoopActive: boolean;
  /** 全程循环开关（时间线末 T≥Tmax 回绕；关=末帧停驻，重播走 setPlaying 的点播放路径） */
  readonly timelineLoop: boolean;
  /** 用户拖动中：压 seek/nudge/wrap，素材域 pause/黑层照做 */
  readonly dragging: boolean;
  /** 当前已施加的左窗黑层（左 dormant） */
  readonly blackLeft: boolean;
  /** 当前已施加的右窗黑层（右 dormant） */
  readonly blackRight: boolean;
  /** 硬 seek 阈值（对比页 HARD_RESYNC_MS，250）：漂出邻域才动播放位置 */
  readonly hardResyncMs: number;

  constructor(
    geo: TimelineGeometry,
    leftLiveMs: number,
    rightLiveMs: number,
    userPlaying: boolean,
    abLoopActive: boolean,
    timelineLoop: boolean,
    dragging: boolean,
    blackLeft: boolean,
    blackRight: boolean,
    hardResyncMs: number
  ) {
    this.geo = geo;
    this.leftLiveMs = leftLiveMs;
    this.rightLiveMs = rightLiveMs;
    this.userPlaying = userPlaying;
    this.abLoopActive = abLoopActive;
    this.timelineLoop = timelineLoop;
    this.dragging = dragging;
    this.blackLeft = blackLeft;
    this.blackRight = blackRight;
    this.hardResyncMs = hardResyncMs;
  }
}

/**
 * decide 的输出指令：全为「该拍要做什么」，undefined/false=不动。
 * 应用顺序由编排器固定：先 pause/resume，再 seek，最后 nudge——resume 触发的 ENDED
 * 自动回零会被随后的 seek 覆盖，nudge 永远落在最终位置上。
 */
export class DecideCommands {
  readonly seekLeftMs: number | undefined;
  readonly seekRightMs: number | undefined;
  /** 断言 softPause(true)（幂等安全，重复下发无害） */
  readonly pauseLeft: boolean;
  readonly pauseRight: boolean;
  /** 断言 softPause(false)（只在独播域退出且用户要播时下发，不与 setPlaying 抢播放权） */
  readonly resumeLeft: boolean;
  readonly resumeRight: boolean;
  /** 黑层绝对态（编排器写回会话位，供下一拍域判定回灌） */
  readonly blackLeft: boolean;
  readonly blackRight: boolean;
  /** 右片软追赶：漂移量（正=右超前），由 nudgeSpeedForSync 折算 ±10% 速率 */
  readonly nudgeRightDriftMs: number | undefined;

  constructor(
    seekLeftMs: number | undefined,
    seekRightMs: number | undefined,
    pauseLeft: boolean,
    pauseRight: boolean,
    resumeLeft: boolean,
    resumeRight: boolean,
    blackLeft: boolean,
    blackRight: boolean,
    nudgeRightDriftMs: number | undefined
  ) {
    this.seekLeftMs = seekLeftMs;
    this.seekRightMs = seekRightMs;
    this.pauseLeft = pauseLeft;
    this.pauseRight = pauseRight;
    this.resumeLeft = resumeLeft;
    this.resumeRight = resumeRight;
    this.blackLeft = blackLeft;
    this.blackRight = blackRight;
    this.nudgeRightDriftMs = nudgeRightDriftMs;
  }

  /** 全静止命令（上游 DecideCommands() 默认构造） */
  static idle(): DecideCommands {
    return new DecideCommands(undefined, undefined, false, false, false, false, false, false, undefined);
  }
}

/**
 * 素材域判定。黑层在册时以在册域为主钟，退出条件=主钟 T 回到双覆盖区；
 * 无在册域时按位置入口判定（pre-roll 入口、双覆盖、两向尾段），都不沾边即时间线末。
 * 头向独播段（off<0 头段）的 park 在素材 0、恰落右覆盖段**含边界**左缘上，
 * 由双覆盖按时间线位 tL 判两侧天然消歧，无需单独入口网。
 */
export function domainOf(
  geo: TimelineGeometry,
  leftLiveMs: number,
  rightLiveMs: number,
  blackLeft: boolean,
  blackRight: boolean
): CompareDomain {
  const tL: number = leftLiveMs;
  const tR: number = rightLiveMs - geo.offsetMs;
  // 时间线末：两段素材都已尽（位置在 park 到素材尽头的引擎上同样成立）。
  // 这个判定必须排在黑层在册分支之前——末态本来就要撤黑层
  if (tL >= geo.leftDurMs && tR >= geo.rightDurMs - geo.offsetMs) {
    return CompareDomain.TIMELINE_END;
  }
  if (blackLeft) {
    if (geo.leftCovers(tR) && geo.rightCovers(tR)) {
      return CompareDomain.DUAL;
    }
    return CompareDomain.RIGHT_SOLO;
  }
  if (blackRight) {
    if (geo.leftCovers(tL) && geo.rightCovers(tL)) {
      return CompareDomain.DUAL;
    }
    return CompareDomain.LEFT_SOLO;
  }
  // 右钟报出 T<0：pre-roll 入口（安全网；常规入口经 seekTimeline 直接写在册黑层）
  if (tR < 0 && geo.rightCovers(tR)) {
    return CompareDomain.RIGHT_SOLO;
  }
  // 双覆盖以时间线位 tL 判两侧：off<0 头段（tL<|off|）时右未覆盖 → LEFT_SOLO，
  // park 在 0 的右轨不会被误判入域（tR = |off| 恰落右覆盖段含边界左缘的假象由此消解）
  if (geo.leftCovers(tL) && geo.rightCovers(tL)) {
    return CompareDomain.DUAL;
  }
  if (geo.leftCovers(tL)) {
    return CompareDomain.LEFT_SOLO;
  }
  if (geo.rightCovers(tR) && tR >= 0) {
    return CompareDomain.RIGHT_SOLO;
  }
  return CompareDomain.TIMELINE_END;
}

/** 当前时间线时刻：双素材域与左独播域跟随左钟，右独播域跟随右钟，时间线末取 Tmax */
export function resolveT(
  geo: TimelineGeometry,
  leftLiveMs: number,
  rightLiveMs: number,
  blackLeft: boolean,
  blackRight: boolean
): number {
  const domain: CompareDomain = domainOf(geo, leftLiveMs, rightLiveMs, blackLeft, blackRight);
  if (domain === CompareDomain.RIGHT_SOLO) {
    return normZero(rightLiveMs - geo.offsetMs);
  }
  if (domain === CompareDomain.TIMELINE_END) {
    return geo.tMax();
  }
  // DUAL / LEFT_SOLO 跟随左钟
  return normZero(leftLiveMs);
}

/**
 * 一拍决策（纯函数）。职责：
 * - 素材域 pause/黑层（dormant 轨每拍重断言，幂等）；
 * - 双素材域纠偏（公式与旧循环逐字一致：漂出 hardResyncMs 硬 seek 回邻域，
 *   邻域内交 nudgeSpeedForSync 软追赶）；
 * - 独播域交接唤醒（不 seek，位置天然连续）；
 * - 时间线末回绕（全程循环开+在播）或末帧停驻（循环关；点播放重播由 setPlaying 承担）。
 */
export function decide(input: DecideInput): DecideCommands {
  const g: TimelineGeometry = input.geo;
  const domain: CompareDomain =
    domainOf(g, input.leftLiveMs, input.rightLiveMs, input.blackLeft, input.blackRight);
  if (domain === CompareDomain.DUAL) {
    const tL: number = input.leftLiveMs;
    // 交接唤醒：上一拍还在独播域（黑层在册）、这一拍回到双覆盖区——
    // 只在用户要播时唤醒，用户暂停时保持安静（播放权归 setPlaying）
    const resumeLeft: boolean = input.blackLeft && input.userPlaying;
    const resumeRight: boolean = input.blackRight && input.userPlaying;
    let seekRight: number | undefined = undefined;
    let nudge: number | undefined = undefined;
    if (!input.dragging) {
      // 纠偏（旧公式逐字保留）：drift = 右实际位置 − 期望位置（T+off）
      const desired: number = normZero(tL + g.offsetMs);
      const drift: number = normZero(input.rightLiveMs - desired);
      if (Math.abs(drift) > input.hardResyncMs) {
        seekRight = desired;
      } else {
        nudge = drift;
      }
    }
    return new DecideCommands(undefined, seekRight, false, false, resumeLeft, resumeRight,
      false, false, nudge);
  }
  // 独播域：dormant 侧每拍重断言暂停（tick 兜底），主钟侧播放权归 setPlaying
  if (domain === CompareDomain.RIGHT_SOLO) {
    return new DecideCommands(undefined, undefined, true, false, false, false, true, false, undefined);
  }
  if (domain === CompareDomain.LEFT_SOLO) {
    return new DecideCommands(undefined, undefined, false, true, false, false, false, true, undefined);
  }
  // TIMELINE_END
  if (input.userPlaying && !input.dragging && input.timelineLoop && !input.abLoopActive) {
    // 全程循环开：到尾即统一回绕 Tmin（AB 段循环激活时回绕归 abWrapHook，这里不抢）。
    // 回绕材料感知：pause/resume/黑层只落在 Tmin 处**无素材**的一侧（park 侧直接带住），
    // 消除"resume 后以播放态停在 park 位空走 ≤1 tick 才被下一拍 pause"的未静窗口；
    // off=0 时两侧都有素材，与旧行为（双 resume、零 pause/黑层）逐位一致
    const t: number = g.tMin();
    const leftAtT: boolean = g.leftCovers(t);
    const rightAtT: boolean = g.rightCovers(t);
    return new DecideCommands(
      g.leftTarget(t),
      g.rightTarget(t),
      !leftAtT,
      !rightAtT,
      leftAtT,
      rightAtT,
      !leftAtT,
      !rightAtT,
      undefined
    );
  }
  // 末帧停驻：两轨素材都尽，不盖黑层、不纠偏；循环关时的重播
  // 由 setPlaying（用户点播放这个动作）走 seekTimeline(Tmin)+起播
  return DecideCommands.idle();
}
