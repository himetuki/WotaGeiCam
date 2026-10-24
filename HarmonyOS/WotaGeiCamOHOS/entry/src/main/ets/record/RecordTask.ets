/**
 * 录制长时任务（上游 `record/CaptureFgService.kt` 的 OHOS 对应物）。
 *
 * 平台差异（登记，调研报告 §三）：
 * - OHOS 无前台 Service；对应形态 = **长时任务（continuous task）+ 系统代发通知**
 *   （`backgroundTaskManager.startBackgroundRunning`，since 9/12）；
 * - BackgroundMode **没有「视频录制」专属档**：视频-only 录制用 `TASK_KEEPING`（通用保活）；
 *   T27-3 内录（带麦克风）落地后应切 `AUDIO_RECORDING`（系统按模式做行为核验，
 *   现在冒领 AUDIO_RECORDING 会被 9800005「连续任务校验失败」打回）；
 * - 权限 `ohos.permission.KEEP_BACKGROUND_RUNNING`（normal，只声明不弹窗）；
 *   通知开关是硬前置（9800006）：用户关了应用通知则长时任务起不来——**失败只记日志**，
 *   绝不阻塞录制本身（前台录制不需要长时任务，退化的只是「切后台继续录」）；
 * - wantAgent 指回本 Ability：系统通知点击回到取景页（对应上游通知回 app）。
 */
// WantAgent 是模块的具名类型导出（非命名空间成员），kit 里也按具名导入
import { common, WantAgent, wantAgent } from '@kit.AbilityKit';
import { backgroundTaskManager } from '@kit.BackgroundTasksKit';
import { Diag } from '../diag/Diag';
import { RecordTaskGate } from './RecordTaskGate';

const TAG: string = 'WotaRecordTask';

/** EntryAbility 名（wantAgent 指回取景页；主入口就是它，见 AGENT.md） */
const ENTRY_ABILITY: string = 'EntryAbility';

export class RecordTask {
  /** 会话级闸：重复起/停不再碰系统接口（健康重录段不重复申请） */
  private static readonly gate: RecordTaskGate = new RecordTaskGate();

  /** 起录后调用（幂等；失败只记日志，绝不影响录制） */
  static start(ctx: common.Context): void {
    if (!RecordTask.gate.onStart()) {
      return;
    }
    RecordTask.request(ctx, true);
  }

  /** 停录收口（幂等；finishRecord 的所有终态路径都汇到这里） */
  static stop(ctx: common.Context): void {
    if (!RecordTask.gate.onStop()) {
      return;
    }
    RecordTask.request(ctx, false);
  }

  private static request(ctx: common.Context, start: boolean): void {
    try {
      const uiCtx: common.UIAbilityContext = ctx as common.UIAbilityContext;
      const bundleName: string = uiCtx.abilityInfo.bundleName;
      const info: wantAgent.WantAgentInfo = {
        wants: [{ bundleName: bundleName, abilityName: ENTRY_ABILITY }],
        operationType: wantAgent.OperationType.START_ABILITY,
        requestCode: 0
      };
      wantAgent.getWantAgent(info).then((agent: WantAgent): Promise<void> => {
        if (start) {
          return backgroundTaskManager.startBackgroundRunning(uiCtx,
            backgroundTaskManager.BackgroundMode.TASK_KEEPING, agent);
        }
        return backgroundTaskManager.stopBackgroundRunning(uiCtx);
      }).then((): void => {
        Diag.i(TAG, start ? 'continuous task started' : 'continuous task stopped');
      }).catch((e: Object): void => {
        // 9800005 校验失败 / 9800006 通知关闭 / 801：都只降级「切后台续录」，不碰录制
        Diag.w(TAG, `continuous task ${start ? 'start' : 'stop'} failed`, e as Error);
      });
    } catch (e) {
      Diag.w(TAG, `continuous task request failed`, e as Error);
    }
  }
}
