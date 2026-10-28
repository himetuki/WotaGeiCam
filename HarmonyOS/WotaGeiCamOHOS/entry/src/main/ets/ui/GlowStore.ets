/**
 * Glow 参数持久化（`@kit.ArkData` preferences 平台适配层）。
 *
 * 分层纪律与 `ui/ParamsStore.ets` 同款：键名与单串编解码全部收在 `core/Glow.ets`
 * （`encodeGlow` / `decodeGlow`，纯逻辑、已有全表用例），本文件只做两件事：
 * 1. 同步读（`getPreferencesSync` + `getSync`）→ `decodeGlow`，缺键/坏串/平台异常一律回
 *    `GLOW_DEFAULT` 的新副本（`sanitize()` 产出，避免把共享常量引用交给调用方去改）；
 * 2. 同步写（`putSync` + `flushSync`），失败只 `Diag.w`，绝不抛——持久化坏了最多丢一次
 *    Glow 偏好，绝不影响取景与录制。
 *
 * store 名复用 `PARAMS_STORE_NAME`（与 ParamsStore/SettingsStore 同一份相机设置文件）：
 * Glow 只是其中一列，不另开 store，避免「两套真源」。键 `glow_params` 与上游
 * `WotaSettings.KEY_GLOW` 逐字一致。
 */
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { GLOW_DEFAULT, GlowParams, decodeGlow, encodeGlow } from '../core/Glow';
import { Diag } from '../diag/Diag';
import { PARAMS_STORE_NAME } from './ParamsStore';

const TAG: string = 'WotaGlowStore';
/** 持久键（上游 WotaSettings.KEY_GLOW 逐字一致；值为 core/Glow.encodeGlow 的单串） */
const KEY_GLOW_PARAMS: string = 'glow_params';

export class GlowStore {
  /** 同步读：缺键/坏串/读失败 → GLOW_DEFAULT（副本，不与 core 常量共享可变状态） */
  static load(ctx: common.Context): GlowParams {
    try {
      const store: preferences.Preferences =
        preferences.getPreferencesSync(ctx, { name: PARAMS_STORE_NAME } as preferences.Options);
      const raw: preferences.ValueType = store.getSync(KEY_GLOW_PARAMS, '');
      // decodeGlow 对坏串整串回 GLOW_DEFAULT；再 sanitize 一次拿新实例（幂等）
      return decodeGlow(typeof raw === 'string' ? raw as string : '').sanitize();
    } catch (e) {
      Diag.w(TAG, `read glow_params failed, use default: ${e}`);
      return GLOW_DEFAULT.sanitize();
    }
  }

  /** 同步写：编码串进内存后立即 flush（Glow 调参频率低，不做批写合并） */
  static save(ctx: common.Context, p: GlowParams): void {
    try {
      const store: preferences.Preferences =
        preferences.getPreferencesSync(ctx, { name: PARAMS_STORE_NAME } as preferences.Options);
      store.putSync(KEY_GLOW_PARAMS, encodeGlow(p));
      store.flushSync();
    } catch (e) {
      Diag.w(TAG, `write glow_params failed: ${e}`);
    }
  }
}
