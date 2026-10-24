/**
 * 播放页 A-B 段的持久化纯逻辑（ABP 批，2026-10-24，直译上游 PlayerEngine.restoreAb/persistAb）。
 *
 * 上游把 AB 段按媒体 uri 记进 SharedPreferences（文件 `wota_player_ab`，键
 * `ab_a/ab_b/ab_loop:<uri>`，apply() 异步）：attach 时 restoreAb 水化，打点/循环切换/
 * 清除即写。本文件收键名、键编码与恢复收整三条纯算式（零平台 import）；preferences
 * 读写薄封装在 AbPersistStore（平台件，不进 pure-test）。
 */
import { AbRange } from './PlayerModel';

/** prefs 文件名（上游 PlayerEngine.PREFS 逐字；CompareHistory/SyncPrefsStore 各自的键并存同仓不同文件） */
export const AB_PREFS_NAME: string = 'wota_player_ab';

export const AB_KEY_A: string = 'ab_a';
export const AB_KEY_B: string = 'ab_b';
export const AB_KEY_LOOP: string = 'ab_loop';

/** 键编码：`$prefix:$媒体标识`（上游 `$prefix:$uri` 同构；本侧媒体标识 = 相对路径） */
export function abKeyOf(prefix: string, mediaPath: string): string {
  return `${prefix}:${mediaPath}`;
}

/**
 * 恢复收整（restoreAb 直译）：损坏/旧版残留的 A>B 数据按 markA 自身的修复口径收整
 * （B ≤ A 即清 B）——UI 写侧永远造不出 A>B（markB 强制 pos > A），读侧收整后进度条
 * 不会画出交叉刻度。loop 只在 A/B 合法时生效（loop && a≥0 && b>a）。
 */
export function sanitizeAb(aMs: number, bMs: number, loopEnabled: boolean): AbRange {
  let b: number = bMs;
  if (aMs >= 0 && b >= 0 && b <= aMs) {
    b = -1;
  }
  return new AbRange(aMs, b, loopEnabled && aMs >= 0 && b > aMs);
}

/** 是否有可水化的记录：三键全空 = 无记录（页面不灌引擎，免得一次无意义写回） */
export function hasAbRecord(aMs: number, bMs: number, loopEnabled: boolean): boolean {
  return aMs >= 0 || bMs >= 0 || loopEnabled;
}
