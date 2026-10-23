/**
 * 「滑杆吸附 / 连续值」的纯逻辑层——上游 ui/widget/SliderSnap.kt 直译（对齐上游 0dee86d）。
 *
 * 2026-10-21 用户反馈：变焦只能落 0.5/1/1.5… 的刻度，想调 1.2×、1.7× 调不到 ⇒
 * 设置页逐个参数关掉刻度吸附、改用连续值。
 *
 * 只放**没有梯**的参数：曝光补偿（整数 EV）、斑马纹阈值（整数 %）、并入门槛（整数 D）
 * 的取值域本身就是逐格整数，连续模式与梯完全等价，放进来只会让人以为开关有作用。
 * 零平台 import（不出现任何 @ohos. 或 @kit. 模块）：纯逻辑供 test/pure 直引。
 */

/**
 * 取值域比梯更细的滑杆参数。id 是持久化契约（写入 SettingsStore 的 slider_continuous 键），
 * **与上游逐字一致，只许追加、不许改名**。
 */
export enum SliderKey {
  SHUTTER = 'shutter',
  ISO = 'iso',
  KELVIN = 'kelvin',
  TINT = 'tint',
  ZOOM = 'zoom',
  DIOPTER = 'diopter',
  PEAKING = 'peaking'
}

/**
 * 全部滑杆键（按枚举声明顺序手写钉死，等价 Kotlin 的 SliderKey.entries）：
 * 编码按这个顺序输出，同一集合恒得到同一串（便于比对与人工排查 prefs）。
 */
export const SLIDER_KEYS: Array<SliderKey> = [
  SliderKey.SHUTTER,
  SliderKey.ISO,
  SliderKey.KELVIN,
  SliderKey.TINT,
  SliderKey.ZOOM,
  SliderKey.DIOPTER,
  SliderKey.PEAKING
];

/**
 * 连续值集合 → prefs 字符串（纯函数）：按声明顺序、逗号分隔，
 * 同一集合恒得到同一串（便于比对与人工排查）。
 */
export function encodeContinuousSliders(keys: Set<SliderKey>): string {
  const out: Array<string> = [];
  for (const key of SLIDER_KEYS) {
    if (keys.has(key)) {
      out.push(key as string);
    }
  }
  return out.join(',');
}

/**
 * prefs 字符串 → 连续值集合：空/坏串 → 空集（= 全部吸附，缺键回退即现行为），
 * 未知 id 忽略（版本回退不炸）、容忍逗号两侧空格（人工改 prefs 的常见形态）。
 */
export function decodeContinuousSliders(raw: string | undefined): Set<SliderKey> {
  const out: Set<SliderKey> = new Set<SliderKey>();
  if (raw === undefined || raw.trim() === '') {
    return out;
  }
  const byId: Map<string, SliderKey> = new Map<string, SliderKey>();
  for (const key of SLIDER_KEYS) {
    byId.set(key as string, key);
  }
  const parts: Array<string> = raw.split(',');
  for (const part of parts) {
    const hit: SliderKey | undefined = byId.get(part.trim());
    if (hit !== undefined) {
      out.add(hit);
    }
  }
  return out;
}

/**
 * 桥函数（纯）：吸附开关 × 梯 → PillSlider 实际使用的梯。
 * `continuous = true`（用户在设置页关掉了该参数的吸附）→ null，滑杆退回 `lo..hi` 连续区间；
 * 否则**原样返回同一引用**（梯为空/单点时由 PillSlider 判为不可用并禁用，语义不变）。
 *
 * 单独成函数是为了让「配置 → 行为」这句判断能被单测直接打：把开关接进 UI 却漏掉这里，
 * 或者反过来（读了开关却没用），都能用突变体抓出来。
 */
export function effectiveLadder(ladder: Array<number>, continuous: boolean): Array<number> | null {
  return continuous ? null : ladder;
}
