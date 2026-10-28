/**
 * 上屏离屏 RT 的尺寸纯函数（上游 docs/plan/24 R3 §2.2/§2.3、25 §11.5）。
 * 直译自上游 core/DisplayScaleRt.kt + WotaParams.kt 的 DisplayScale 枚举（1e61e15）。
 *
 * # 本侧登记（平台偏离，与 Glow 同款拆分纪律）
 * OHOS 预览链目前**没有中间取景器 RT**（效果直接画在上屏 surface；唯一离屏是毛玻璃专属）。
 * 等价落法只有两条：降相机流尺寸（会连成片一起降——违反「成片不变」红线，不可取）或新建
 * 「效果 → 缩小 RT → 放大 blit 上屏」链（GPU 新路径，帧率/观感必须真机取证）。本批只交付
 * 纯函数层 + 全表用例（供后续真机批直接消费），不落 UI、不落 native——不做摆设开关。
 * 「预览：<档位值>」读数（上游 58464fc）依赖档位面，随同一批。
 */

/** 上屏 RT 任一维的最小可用边（R1 手算档经验下限，24 §2.2）：不足的档在 UI 灰显不静默改小 */
export const MIN_DISPLAY_RT_EDGE: number = 128;

/** 上屏降档位：ORIG 恒兜底；除数是 2 的幂 */
export class DisplayScale {
  static readonly ORIG: DisplayScale = new DisplayScale(1);
  static readonly HALF: DisplayScale = new DisplayScale(2);
  static readonly QUARTER: DisplayScale = new DisplayScale(4);
  static readonly EIGHTH: DisplayScale = new DisplayScale(8);
  static readonly SIXTEENTH: DisplayScale = new DisplayScale(16);

  static readonly ALL: Array<DisplayScale> =
    [DisplayScale.ORIG, DisplayScale.HALF, DisplayScale.QUARTER, DisplayScale.EIGHTH,
      DisplayScale.SIXTEENTH];

  readonly divisor: number;

  constructor(divisor: number) {
    this.divisor = divisor;
  }

  static fromDivisor(divisor: number): DisplayScale {
    for (const s of DisplayScale.ALL) {
      if (s.divisor === divisor) {
        return s;
      }
    }
    return DisplayScale.ORIG;
  }
}

/**
 * 等比区尺寸 + 档 → 上屏 RT 尺寸，写进 out[0]=宽 out[1]=高（热点路径，不返回数组）。
 * 返回 false = 这一款此刻不可用（尺寸/除数非法，或任一维 < 128）；此时**不写** out——
 * 三处共用（每帧引擎/UI 可用性/钳位），false 各自做同一个决定（灰显/回原片路），
 * 给「1×1 兜底」会让三条路各自猜一个值最终分叉（24 §〇.1-4 禁止）。
 * floor：上屏 RT 只许比目标小（多出来的部分由放大 blit 补齐；ceil 会白占显存）。
 */
export function resolveDisplayRtInto(out: Array<number>, contentWidthPx: number,
  contentHeightPx: number, divisor: number): boolean {
  if (out.length < 2) {
    throw new Error('out 至少两个 int，实际 ' + out.length);
  }
  if (contentWidthPx <= 0 || contentHeightPx <= 0 || divisor <= 0) {
    return false;
  }
  const w: number = Math.floor(contentWidthPx / divisor);
  const h: number = Math.floor(contentHeightPx / divisor);
  if (w < MIN_DISPLAY_RT_EDGE || h < MIN_DISPLAY_RT_EDGE) {
    return false;
  }
  out[0] = w;
  out[1] = h;
  return true;
}

/** resolveDisplayRtInto 的数组版（UI 用：只在选择/状态行时调一次，不在帧路径上）。undefined = 该档不可用 */
export function resolveDisplayRtOrNull(contentWidthPx: number, contentHeightPx: number,
  divisor: number): Array<number> | undefined {
  const out: Array<number> = [0, 0];
  if (!resolveDisplayRtInto(out, contentWidthPx, contentHeightPx, divisor)) {
    return undefined;
  }
  return out;
}

/**
 * 回落时该落到哪一款（24 R3 §2.3）：从 current 的**下一档（除数减半）**开始往下找第一个
 * 尺寸可用的档，ORIG 永远兜底。
 *
 * # 为什么从「下一档」而不是 current 自己开始
 * 本函数只在**回落**时被调用（current 刚刚失败）。若把 current 也算进来，当失败原因不是
 * 「等比区太小」（超纹理上限 / FBO 不完整 / blit program 编不出）时，尺寸判定会说 current 可用
 * ⇒ 返回 current ⇒ UI 写回同一个值 ⇒ 下一帧再失败再回落：**无限循环**。除数减半保证每一轮
 * 都真的往「更少降档」的方向走一步（16→8→4→2→ORIG）。
 * 等比区实测尺寸 ≤0（还没量到）⇒ 直接 ORIG。
 */
export function nearestDisplayScale(contentWidthPx: number, contentHeightPx: number,
  current: DisplayScale): DisplayScale {
  if (contentWidthPx <= 0 || contentHeightPx <= 0) {
    return DisplayScale.ORIG;
  }
  let divisor: number = Math.floor(current.divisor / 2);
  while (divisor > DisplayScale.ORIG.divisor) {
    if (resolveDisplayRtOrNull(contentWidthPx, contentHeightPx, divisor) !== undefined) {
      return DisplayScale.fromDivisor(divisor);
    }
    divisor = Math.floor(divisor / 2);
  }
  return DisplayScale.ORIG;
}

/**
 * 胶囊下方读数（24 R3 钉子 3）的档位文案：原片用本地化标签，其余用除数字面量。
 * 抽成纯函数是为了让「读数 = 档位标签」这条口径被单测钉住——UI 只负责套「预览：%s」。
 */
export function displayScaleReadoutLabel(scale: DisplayScale, origLabel: string): string {
  return scale === DisplayScale.ORIG ? origLabel : `${scale.divisor}`;
}

/**
 * 要不要重建上屏 RT（DisplayPostPass.begin 每帧问一句）。
 * 判据只看尺寸（= 等比区或档位变了），**与开关无关**：同一档下窗口尺寸不变就不重建。
 * 目标非法（≤0）时返回 false：begin 由 resolveDisplayRtInto 的 false 侧直接跳过，
 * 不该在这里顺手拆已建资源（拆了下一帧又要建 = 真抖动）。
 */
export function displayRtNeedsRebuild(currentWidthPx: number, currentHeightPx: number,
  targetWidthPx: number, targetHeightPx: number): boolean {
  if (targetWidthPx <= 0 || targetHeightPx <= 0) {
    return false;
  }
  return currentWidthPx !== targetWidthPx || currentHeightPx !== targetHeightPx;
}
