/**
 * 设置页数值档的**纯算式**（上游 WotaSettings 里 compareTickMs/textScale/galleryColumns
 * 三条的直译，零平台 import，可在 test/pure 钉住）。
 *
 * 口径（与上游逐字对齐）：
 * - 编排节拍：缺键 200，越界钳回 100..1000；设置页档位 [100, 200, 500, 1000]；
 * - 文本缩放：缺键 100，钳回 80..120（设置页五档 [80, 90, 100, 110, 120]），产出**倍率**
 *   （pct/100，Float）——Camera 页把它乘上系统 fontScale 喂 hudPerRowFor（J3a 宽度账）；
 * - 网格列数：**决策不迁**——本侧媒体库是列表（无网格），且 ID 架构/缩略图交互与列数绑定，
 *   留待网格画廊批（若有）。这里只保留档位判据供守卫。
 */
export class UserSettings {
  static readonly COMPARE_TICK_MIN_MS: number = 100;
  static readonly COMPARE_TICK_MAX_MS: number = 1000;
  /** 设置页档位（上游 COMPARE_TICK_TIERS） */
  static readonly COMPARE_TICK_TIERS: Array<number> = [100, 200, 500, 1000];
  static readonly COMPARE_TICK_DEFAULT_MS: number = 200;

  /** 文本缩放可选区间（%，上游 TEXT_SCALE_PCTS），100 = 工程默认排版 */
  static readonly TEXT_SCALE_PCTS: Array<number> = [80, 90, 100, 110, 120];
  static readonly TEXT_SCALE_DEFAULT_PCT: number = 100;

  /** 编排节拍：缺键 200，越界钳回 100..1000（非有限数同缺键） */
  static compareTickMsOf(raw: number | undefined): number {
    if (raw === undefined || !Number.isFinite(raw)) {
      return UserSettings.COMPARE_TICK_DEFAULT_MS;
    }
    const v: number = Math.trunc(raw);
    if (v < UserSettings.COMPARE_TICK_MIN_MS) {
      return UserSettings.COMPARE_TICK_MIN_MS;
    }
    if (v > UserSettings.COMPARE_TICK_MAX_MS) {
      return UserSettings.COMPARE_TICK_MAX_MS;
    }
    return v;
  }

  /** 文本缩放：缺键 100，钳回 80..120（产出整数 pct） */
  static textScalePctOf(raw: number | undefined): number {
    if (raw === undefined || !Number.isFinite(raw)) {
      return UserSettings.TEXT_SCALE_DEFAULT_PCT;
    }
    const v: number = Math.trunc(raw);
    if (v < UserSettings.TEXT_SCALE_PCTS[0]) {
      return UserSettings.TEXT_SCALE_PCTS[0];
    }
    if (v > UserSettings.TEXT_SCALE_PCTS[UserSettings.TEXT_SCALE_PCTS.length - 1]) {
      return UserSettings.TEXT_SCALE_PCTS[UserSettings.TEXT_SCALE_PCTS.length - 1];
    }
    return v;
  }

  /** 文本缩放 pct → 倍率（0.8..1.2；Camera 页乘系统 fontScale 喂 hudPerRowFor） */
  static textScaleFactorOf(pct: number): number {
    const p: number = UserSettings.textScalePctOf(pct);
    return p / 100;
  }
}
