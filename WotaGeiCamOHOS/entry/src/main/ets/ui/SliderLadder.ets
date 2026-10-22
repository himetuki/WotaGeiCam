/**
 * 刻度滑杆的梯子构造——上游 ui/widget/SliderLadder.kt 纯函数族直译（对齐上游 38d712a）。
 *
 * - 输入范围一律来自运行时能力（ParamState.range / CameraAbility），本文件只负责
 *   「从范围内挑出产品档位」，不出现任何机型硬编码；
 * - 输出升序、已按 lo..hi 过滤，因此 nearestLadderIndex 与滑杆的索引空间
 *   （0..size-1）能直接对应；
 * - 纯函数，全部可静态对拍（上游有 SliderLadderTest，本侧锚点见 docs/PORT_MAP.md §ui）。
 *
 * 平台差异：Kotlin Float 档位在 ArkTS 用 number（双精度）承载——上游为 1/24 的
 * 41,666,666ns 做 Float 键会掉到 41,666,664（快门弹层注释），双精度下无损，
 * 「梯值反查原始 ns」的 Map 结构仍原样保留（语义对齐，精度问题不存在而已）。
 * 未移植（本批无消费方，登记）：deadbandLadder（光弧死区滑杆未进本批；
 * diopterLadder/percentLadder 已随纯逻辑测试批补齐，消费方仍留待对焦/斑马胶囊批）。
 */

/** 就近刻度索引：空表返回 0；恰好落在两档中点时取低档（与滑杆"向下吸附"的观感一致） */
export function nearestLadderIndex(value: number, ladder: Array<number>): number {
  if (ladder.length === 0) {
    return 0;
  }
  let best: number = 0;
  let bestDist: number = Math.abs(ladder[0] - value);
  for (let i = 1; i < ladder.length; i++) {
    const d: number = Math.abs(ladder[i] - value);
    if (d < bestDist) {
      best = i;
      bestDist = d;
    }
  }
  return best;
}

/** 1/3 档标准 ISO 序列（50…12800），按设备范围过滤 */
export function isoLadder(lo: number, hi: number): Array<number> {
  const tiers: Array<number> = [
    50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
    1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800
  ];
  return tiers.filter((t: number): boolean => t >= lo && t <= hi);
}

/** 曝光补偿：设备每个整数补偿步一档 */
export function evLadder(lo: number, hi: number): Array<number> {
  if (lo > hi) {
    return [];
  }
  const out: Array<number> = [];
  for (let v: number = lo; v <= hi; v++) {
    out.push(v);
  }
  return out;
}

/** 白平衡色温：全局 500K 网格（2000..10000 恰 17 档），按范围过滤后仍按 500 对齐 */
export function kelvinLadder(lo: number, hi: number): Array<number> {
  return steppedLadder(lo, hi, 500);
}

/** 白平衡色调：每 5 一档（-50..50 → 21 档）——本批 tint 副轴留白，函数先落语义 */
export function tintLadder(lo: number, hi: number): Array<number> {
  return steppedLadder(lo, hi, 5);
}

/** 变焦快捷倍率梯：沿用产品档位（含 1.5/2.5 便于构图），按运行时变焦范围过滤 */
export function zoomLadder(lo: number, hi: number): Array<number> {
  const tiers: Array<number> = [0.5, 1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10];
  return tiers.filter((t: number): boolean => t >= lo && t <= hi);
}

/**
 * 屈光度梯：lo..hi 等分 10 段共 11 点（上游 diopterLadder 直译，2026-10-22 随纯逻辑
 * 测试批补齐——对焦胶囊仍未进 UI 批，函数先落语义与单测锚点）。
 * 梯必须以运行时 range.lo 起、range.hi 止，不造域外刻度；定焦镜头（hi<=lo）没有梯。
 */
export function diopterLadder(lo: number, hi: number): Array<number> {
  if (hi <= lo) {
    return [];
  }
  const out: Array<number> = [];
  for (let i: number = 0; i <= 10; i++) {
    out.push(lo + (hi - lo) * i / 10);
  }
  return out;
}

/** 百分比梯（斑马阈值 80..100 步 1、峰值强度 5..95 步 5；上游 percentLadder 直译） */
export function percentLadder(lo: number, hi: number, step: number): Array<number> {
  return steppedLadder(lo, hi, step);
}

/**
 * 快门梯：调用方传入 shutterItems(...).filter(supported).map(value)，
 * 这里只做范围过滤、去重、升序（不支持的档直接不进梯，而不是灰显 chip）。
 */
export function shutterLadder(tiers: Array<number>, lo: number, hi: number): Array<number> {
  const inRange: Array<number> = tiers.filter((t: number): boolean => t >= lo && t <= hi);
  const out: Array<number> = [];
  for (const t of inRange) {
    if (out.indexOf(t) < 0) {
      out.push(t);
    }
  }
  return out.sort((a: number, b: number): number => a - b);
}

/** 以 step 为全局网格生成 lo..hi 内的档位（负数范围同样按网格向上对齐） */
function steppedLadder(lo: number, hi: number, step: number): Array<number> {
  if (step <= 0 || lo > hi) {
    return [];
  }
  const start: number = alignUp(lo, step);
  if (start > hi) {
    return [];
  }
  const out: Array<number> = [];
  let v: number = start;
  while (v <= hi) {
    out.push(v);
    v += step;
  }
  return out;
}

/**
 * 向上对齐到 step 的网格。Kotlin 的 Math.floorDiv 对负数向下取整；
 * JS 的 `/` 向零截断（Kotlin 的 `/` 同样向零截断），所以这里必须用 Math.floor
 * 复刻 floorDiv 语义，负数范围（如 tint -50..-46）才不会错档。
 */
function alignUp(v: number, step: number): number {
  return Math.floor((v + step - 1) / step) * step;
}
