/**
 * 对比播放的手动时间线最小版（直译上游 player/CompareTimeline.kt 的 TimelineGeometry 子集）。
 *
 * 平台差异 / 本批边界：
 * - 上游 TimelineGeometry 的 tMin/tMax 是 Kotlin 计算属性，ArkTS 侧落成同名方法
 *   （tMin()/tMax()），避免属性访问器在调用侧产生"字段还是方法"的歧义；
 * - 本批只做手动偏移的几何换算，不含上游的 DecideCommands / AudioSync / 自动对齐：
 *   播放器每侧各自由用户手动 seek，偏移只回答"左游标 t 应把右侧停在哪"；
 * - Long → number：时长/偏移均为毫秒整数，值域远小于 2^53。
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
