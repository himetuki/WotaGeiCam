/**
 * 音画同步结果的持久化适配层——把 `player/TrackSync.ets` 的五键编解码接到
 * `@ohos.data.preferences` 上（上游 PlayerScreen 直接用 android SharedPreferences）。
 *
 * 分层纪律：**键名与 bool/number→string 的编解码全部收在 TrackSync（纯逻辑单一真源）**，
 * 本文件只提供 `SyncPrefs` 的最小实现（读一次水化进内存 map，写直接落 store + map），
 * 并把「打开失败 / 值不是 string」一律折成缺键（= 未同步），绝不让坏 prefs 变成假偏移。
 *
 * 本文件是平台件（import @ohos.data.preferences），不进 pure-test；纯逻辑用例见
 * test/pure/track-sync.test.ets（map 替身）。
 */
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { Diag } from '../diag/Diag';
import { Source, SyncPrefs, SyncSnapshot, TrackSync } from './TrackSync';

/** 与上游 TrackSync 的 prefs 文件同名口径（本侧独立 store，不挤进相机设置） */
const STORE_NAME: string = 'wota_track_sync';
const TAG: string = 'WotaSync';

/** 五键在某一分段上的完整键名（与 TrackSync 的 `sync_<字段>:<段key>` 逐字一致） */
export class SyncKeys {
  static all(key: string): Array<string> {
    return [
      TrackSync.doneKeyOf(key),
      TrackSync.offsetKeyOf(key),
      TrackSync.sourceKeyOf(key),
      TrackSync.confKeyOf(key),
      TrackSync.atKeyOf(key)
    ];
  }
}

/**
 * SyncPrefs 的内存实现：`getString` 走 map（缺失回默认值），`putString` 先写 map
 * 再异步落 store（写失败记日志，不回滚内存值——本轮 UI 读数仍按刚写的结果走）。
 */
export class MappedSyncPrefs implements SyncPrefs {
  private map: Map<string, string> = new Map<string, string>();
  private store: preferences.Preferences | undefined = undefined;

  constructor(store: preferences.Preferences | undefined) {
    this.store = store;
  }

  /** 水化：只取本段关心的键；类型不是 string 或读取抛错一律当缺键 */
  async hydrate(keys: Array<string>): Promise<void> {
    const store: preferences.Preferences | undefined = this.store;
    if (store === undefined) {
      return;
    }
    for (const k of keys) {
      try {
        const v: preferences.ValueType = await store.get(k, '');
        if (typeof v === 'string' && v !== '') {
          this.map.set(k, v as string);
        }
      } catch (e) {
        Diag.w(TAG, `读同步记录失败（按未同步处理）：${k}`);
      }
    }
  }

  getString(key: string, def: string): string {
    const v: string | undefined = this.map.get(key);
    return v === undefined ? def : v;
  }

  putString(key: string, value: string): void {
    this.map.set(key, value);
    const store: preferences.Preferences | undefined = this.store;
    if (store === undefined) {
      return;
    }
    store.put(key, value).then((): Promise<void> => store.flush())
      .catch((e: Object): void => {
        Diag.w(TAG, `写同步记录失败：${key}`, e as Error);
      });
  }
}

/** 平台侧读写门面（Player 页只调这两个方法） */
export class SyncPrefsStore {
  /** 读某段的同步快照；未校准 / 读失败 → undefined（UI 显示「未同步」） */
  static async read(ctx: common.Context, key: string): Promise<SyncSnapshot | undefined> {
    try {
      const store: preferences.Preferences = await preferences.getPreferences(ctx, STORE_NAME);
      const prefs: MappedSyncPrefs = new MappedSyncPrefs(store);
      await prefs.hydrate(SyncKeys.all(key));
      return TrackSync.cachedSyncOf(prefs, key);
    } catch (e) {
      Diag.w(TAG, `同步记录不可用（按未同步处理）：${key}`, e as Error);
      return undefined;
    }
  }

  /**
   * 写一次完整结果（手动「同步音轨」与录制后自动校准共用）。
   * [auto] = true 时走 writeAutoPrefs（已有 MANUAL 结果拒绝覆盖，返回 false）。
   */
  static async write(ctx: common.Context, key: string, offsetMs: number, source: Source,
    confidence: number, auto: boolean): Promise<boolean> {
    try {
      const store: preferences.Preferences = await preferences.getPreferences(ctx, STORE_NAME);
      const prefs: MappedSyncPrefs = new MappedSyncPrefs(store);
      await prefs.hydrate(SyncKeys.all(key)); // 先水化：writeAutoPrefs 的 MANUAL 复核要看到既有值
      let written: boolean = true;
      if (auto) {
        written = TrackSync.writeAutoPrefs(prefs, key, offsetMs, confidence, Date.now());
      } else {
        TrackSync.writeSyncPrefs(prefs, key, offsetMs, source, confidence, Date.now());
      }
      await store.flush();
      return written;
    } catch (e) {
      Diag.w(TAG, `写同步结果失败：${key}`, e as Error);
      return false;
    }
  }
}
