/**
 * ParamsPersist 的平台适配层（`@ohos.data.preferences`）。
 *
 * 分层纪律：**键名与 24 键编解码全部收在 `ui/ParamsPersist.ets`（纯逻辑单一真源）**，
 * 本文件只做两件事：
 * 1. 把 `preferences.Preferences` 包成 [ParamsPrefs]（同步读写，给 restore / session 用；
 *    读取类型不符/缺键回默认，写失败只 `Diag.w`，绝不抛；**put 只进内存，commit 才落盘**）；
 * 2. 提供同步门面 [ParamsStore.open]（`getPreferencesSync`，与 `SettingsStore` 同一个 store——
 *    一个字段只有一个权威键名，存同一份文件，避免"两套真源"）。
 *
 * 本文件是平台件（import `@kit.ArkData`），不进 pure-test；纯逻辑用例见
 * test/pure/params-persist.test.ets（map 替身）。
 */
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { Diag } from '../diag/Diag';
import { ParamsPrefs } from './ParamsPersist';

/** 与 `SettingsStore` 共用的相机设置 store（迁移后的 24 键与其余非白名单键同盘） */
export const PARAMS_STORE_NAME: string = 'wota_camera_settings';
const TAG: string = 'WotaParamsStore';

/**
 * [ParamsPrefs] 的 preferences 实现：读走 `getSync`（缺键/类型不符回默认）。
 * 写分两步，对齐上游 `commit()` 的批写口径：`putSync` 只进内存，`flushSync` 由
 * [commit] 单独做一次——一次 flush 无论改了几个字段都只落一次盘，避免滑杆逐帧同步 IO。
 * 任一写失败只记日志并返回 false，session 据此不推进影子。
 */
class PlatformParamsPrefs implements ParamsPrefs {
  private store: preferences.Preferences;

  constructor(store: preferences.Preferences) {
    this.store = store;
  }

  getInt(key: string, def: number): number {
    return Math.trunc(this.readNumber(key, def));
  }

  getFloat(key: string, def: number): number {
    return this.readNumber(key, def);
  }

  getBool(key: string, def: boolean): boolean {
    try {
      const raw: preferences.ValueType = this.store.getSync(key, def);
      return typeof raw === 'boolean' ? raw as boolean : def;
    } catch (e) {
      Diag.w(TAG, `read ${key} failed, use default`, e as Error);
      return def;
    }
  }

  getString(key: string, def: string): string {
    try {
      const raw: preferences.ValueType = this.store.getSync(key, def);
      return typeof raw === 'string' ? raw as string : def;
    } catch (e) {
      Diag.w(TAG, `read ${key} failed, use default`, e as Error);
      return def;
    }
  }

  putInt(key: string, value: number): boolean {
    return this.write(key, Math.trunc(value));
  }

  putBool(key: string, value: boolean): boolean {
    return this.write(key, value);
  }

  putFloat(key: string, value: number): boolean {
    return this.write(key, value);
  }

  putString(key: string, value: string): boolean {
    return this.write(key, value);
  }

  /** 一次 flush 的落盘点：把本批 putSync 一起同步落盘（上游 commit() 的 OHOS 等价物） */
  commit(): boolean {
    try {
      this.store.flushSync();
      return true;
    } catch (e) {
      Diag.w(TAG, 'commit (flushSync) failed', e as Error);
      return false;
    }
  }

  private readNumber(key: string, def: number): number {
    try {
      const raw: preferences.ValueType = this.store.getSync(key, def);
      if (typeof raw !== 'number' || !Number.isFinite(raw as number)) {
        return def;
      }
      return raw as number;
    } catch (e) {
      Diag.w(TAG, `read ${key} failed, use default`, e as Error);
      return def;
    }
  }

  private write(key: string, value: preferences.ValueType): boolean {
    try {
      this.store.putSync(key, value); // 只进内存；落盘由 session 在批末尾调一次 commit()
      return true;
    } catch (e) {
      Diag.w(TAG, `write ${key} failed`, e as Error);
      return false;
    }
  }
}

/** 平台侧同步门面（Camera 页 restore/flush 只经这里拿 ParamsPrefs） */
export class ParamsStore {
  /** 打开参数存储；打开失败返回 undefined（调用方按"不持久化"降级，绝不影响录制） */
  static open(ctx: common.Context): ParamsPrefs | undefined {
    try {
      const store: preferences.Preferences =
        preferences.getPreferencesSync(ctx, { name: PARAMS_STORE_NAME } as preferences.Options);
      return new PlatformParamsPrefs(store);
    } catch (e) {
      Diag.w(TAG, `open params store failed, persistence disabled: ${e}`);
      return undefined;
    }
  }
}
