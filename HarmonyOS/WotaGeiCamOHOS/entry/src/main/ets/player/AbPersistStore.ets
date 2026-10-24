/**
 * 播放页 A-B 段的 preferences 适配层（ABP 批，2026-10-24）。
 *
 * 分层纪律：键名/键编码/恢复收整全在 player/AbPersist.ets（纯逻辑单一真源），本文件只做
 * get → sanitize → 灌引擎、引擎态 → put → flush 的平台薄封装。失败语义（登记）：
 * 任何 preferences/IO 异常只 Diag.w，load 返回 undefined（= 无记录）、save 不抛——
 * AB 段是便利性缓存，坏一次不该把播放页打挂（与 CompareHistoryStore 同口径）。
 *
 * 写侧语义与上游对齐：markB/toggleAbLoop 失败不写（状态未变）；clearAb 也写
 * （-1/-1/false 落盘 = 显式清除，下次进页不再水化旧段）。
 */
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { Diag } from '../diag/Diag';
import { AbRange } from './PlayerModel';
import {
  AB_KEY_A, AB_KEY_B, AB_KEY_LOOP, AB_PREFS_NAME, abKeyOf, hasAbRecord, sanitizeAb
} from './AbPersist';

const TAG: string = 'WotaAbPersist';

export class AbPersistStore {
  /** 读某片的持久化 AB；无记录/失败返回 undefined（页面不灌引擎） */
  static async load(ctx: common.Context, mediaPath: string): Promise<AbRange | undefined> {
    try {
      const store: preferences.Preferences = await preferences.getPreferences(ctx, AB_PREFS_NAME);
      const rawA: preferences.ValueType = await store.get(abKeyOf(AB_KEY_A, mediaPath), -1);
      const rawB: preferences.ValueType = await store.get(abKeyOf(AB_KEY_B, mediaPath), -1);
      const rawLoop: preferences.ValueType = await store.get(abKeyOf(AB_KEY_LOOP, mediaPath), false);
      const a: number = typeof rawA === 'number' ? rawA as number : -1;
      const b: number = typeof rawB === 'number' ? rawB as number : -1;
      const loop: boolean = typeof rawLoop === 'boolean' ? rawLoop as boolean : false;
      if (!hasAbRecord(a, b, loop)) {
        return undefined;
      }
      return sanitizeAb(a, b, loop);
    } catch (e) {
      Diag.w(TAG, `load ab failed, none: ${e}`);
      return undefined;
    }
  }

  /** 落某片的 AB（清除也写：三键空值落盘）；失败只记日志不抛 */
  static async save(ctx: common.Context, mediaPath: string, ab: AbRange): Promise<void> {
    try {
      const store: preferences.Preferences = await preferences.getPreferences(ctx, AB_PREFS_NAME);
      await store.put(abKeyOf(AB_KEY_A, mediaPath), ab.aMs);
      await store.put(abKeyOf(AB_KEY_B, mediaPath), ab.bMs);
      await store.put(abKeyOf(AB_KEY_LOOP, mediaPath), ab.loopEnabled);
      await store.flush();
    } catch (e) {
      Diag.w(TAG, `save ab failed: ${e}`);
    }
  }
}
