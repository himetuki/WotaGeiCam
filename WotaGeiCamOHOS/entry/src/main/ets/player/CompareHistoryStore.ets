/**
 * 对比历史的 preferences 适配层（T25）。
 *
 * 上游 CompareHistory 把 JSON 编解码与 SharedPreferences 读写放在同一 object；本侧纯核心
 * （player/CompareHistory.ets）已负责解析/去重/截断/编码，本文件只做平台侧薄封装：
 * store = `wota_player_ab`，key = CompareHistory.KEY_COMPARE_HISTORY（`compare_history`），
 * 每次 load/record/remove 都是 get → 纯核心 → put → flush。
 *
 * 失败语义（登记）：任何 preferences/IO 异常只 Diag.w，load 返回空表、record/remove 不抛——
 * 历史是便利性缓存，坏一次不该把对比页打挂。异步落盘：record/remove 调用方不等待
 * （与上游 apply() 异步同口径，本侧 flush 在 Promise 内完成）。
 *
 * 串行化（T25 审查修复 P3）：record/remove 走模块级 Promise 队列——快速连续换片时
 * 「get→改→put」不再交错（交错会以旧快照覆盖新写入而丢历史）。load 不排队，
 * 面板重载可能读到正在写的上一版，下一次刷新即收敛。
 */
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { Diag } from '../diag/Diag';
import { CompareHistory, CompareHistoryEntry } from './CompareHistory';

const STORE_NAME: string = 'wota_player_ab';
const TAG: string = 'WotaCompareHist';

/** 写队列尾：所有 record/remove 串行追加（初值已 resolve，首写直接执行） */
let writeChain: Promise<void> = Promise.resolve();

function enqueueWrite(task: () => Promise<void>): Promise<void> {
  const next: Promise<void> = writeChain.then((): Promise<void> => task())
    .catch((e: Object): void => {
      Diag.w(TAG, `history write chain failed: ${e}`);
    });
  writeChain = next;
  return next;
}

export class CompareHistoryStore {
  /** 读全部记录（队首最新）；失败返回空表 */
  static async load(ctx: common.Context): Promise<Array<CompareHistoryEntry>> {
    try {
      const store: preferences.Preferences = await preferences.getPreferences(ctx, STORE_NAME);
      const raw: preferences.ValueType =
        await store.get(CompareHistory.KEY_COMPARE_HISTORY, '');
      const text: string = typeof raw === 'string' ? raw as string : '';
      return CompareHistory.entriesOf(text);
    } catch (e) {
      Diag.w(TAG, `load history failed, empty: ${e}`);
      return [];
    }
  }

  /** 记一次对照（rightId 去重 + 插队首 + 截 12）；入队串行，失败只记日志不抛 */
  static record(ctx: common.Context, rightId: number, leftId: number): Promise<void> {
    return enqueueWrite((): Promise<void> => CompareHistoryStore.doRecord(ctx, rightId, leftId));
  }

  /** 按 rightId 删除一条；入队串行，失败只记日志不抛 */
  static remove(ctx: common.Context, rightId: number): Promise<void> {
    return enqueueWrite((): Promise<void> => CompareHistoryStore.doRemove(ctx, rightId));
  }

  private static async doRecord(ctx: common.Context, rightId: number, leftId: number): Promise<void> {
    try {
      const store: preferences.Preferences = await preferences.getPreferences(ctx, STORE_NAME);
      const raw: preferences.ValueType =
        await store.get(CompareHistory.KEY_COMPARE_HISTORY, '');
      const text: string = typeof raw === 'string' ? raw as string : '';
      const next: Array<CompareHistoryEntry> =
        CompareHistory.recordEntry(CompareHistory.entriesOf(text), rightId, leftId, Date.now());
      await store.put(CompareHistory.KEY_COMPARE_HISTORY, CompareHistory.encodeEntries(next));
      await store.flush();
    } catch (e) {
      Diag.w(TAG, `record history failed: ${e}`);
    }
  }

  private static async doRemove(ctx: common.Context, rightId: number): Promise<void> {
    try {
      const store: preferences.Preferences = await preferences.getPreferences(ctx, STORE_NAME);
      const raw: preferences.ValueType =
        await store.get(CompareHistory.KEY_COMPARE_HISTORY, '');
      const text: string = typeof raw === 'string' ? raw as string : '';
      const next: Array<CompareHistoryEntry> =
        CompareHistory.removeEntry(CompareHistory.entriesOf(text), rightId);
      await store.put(CompareHistory.KEY_COMPARE_HISTORY, CompareHistory.encodeEntries(next));
      await store.flush();
    } catch (e) {
      Diag.w(TAG, `remove history failed: ${e}`);
    }
  }
}
