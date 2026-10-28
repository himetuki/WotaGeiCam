/**
 * 色调参数持久化（`@kit.ArkData` preferences 平台适配层）。
 *
 * 分层纪律与 `ui/GlowStore.ets` 同款：键名与单串编解码全部收在 `core/Tone.ets`
 * （`toneEncode` / `toneDecode`，纯逻辑 + 纯测），本文件只做两件事：
 * 1. 同步读（`getPreferencesSync` + `getSync`）→ `toneDecode`，缺键/坏串/平台异常一律回
 *    `TONE_DEFAULT` 的新副本（`sanitize()` 产出，避免把共享常量引用交给调用方去改）；
 * 2. 同步写（`putSync` + `flushSync`），失败只 `Diag.w`，绝不抛——持久化坏了最多丢一次
 *    色调偏好，绝不影响取景与录制。
 *
 * store 名复用 `PARAMS_STORE_NAME`（与 ParamsStore/SettingsStore 同一份相机设置文件）：
 * 色调只是其中一列，不另开 store，避免「两套真源」。键 `tone_params` 独立于
 * 曲线编码串（安卓同样把 KEY_TONE 与 curves 分开，老用户曲线串不会被解坏）。
 */
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { TONE_DEFAULT, ToneParams, toneDecode, toneEncode } from '../core/Tone';
import { Diag } from '../diag/Diag';
import { PARAMS_STORE_NAME } from './ParamsStore';

const TAG: string = 'WotaToneStore';
/** 持久键（独立于曲线串；值为 core/Tone.toneEncode 的单串） */
const KEY_TONE_PARAMS: string = 'tone_params';

export class ToneStore {
  /** 同步读：缺键/坏串/读失败 → TONE_DEFAULT（副本，不与 core 常量共享可变状态） */
  static load(ctx: common.Context): ToneParams {
    try {
      const store: preferences.Preferences =
        preferences.getPreferencesSync(ctx, { name: PARAMS_STORE_NAME } as preferences.Options);
      const raw: preferences.ValueType = store.getSync(KEY_TONE_PARAMS, '');
      // toneDecode 对坏串整串回 TONE_DEFAULT；再 sanitize 一次拿新实例（幂等）
      return toneDecode(typeof raw === 'string' ? raw as string : '').sanitize();
    } catch (e) {
      Diag.w(TAG, `read tone_params failed, use default: ${e}`);
      return TONE_DEFAULT.sanitize();
    }
  }

  /** 同步写：编码串进内存后立即 flush（色调调参频率低，不做批写合并） */
  static save(ctx: common.Context, p: ToneParams): void {
    try {
      const store: preferences.Preferences =
        preferences.getPreferencesSync(ctx, { name: PARAMS_STORE_NAME } as preferences.Options);
      store.putSync(KEY_TONE_PARAMS, toneEncode(p));
      store.flushSync();
    } catch (e) {
      Diag.w(TAG, `write tone_params failed: ${e}`);
    }
  }
}
