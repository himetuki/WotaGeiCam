/**
 * 移植编译图锚点（非业务代码）：pages/Index.ets 引用本模块，把 record 纯逻辑锚点七件套
 * 拉进 CompileArkTS 类型检查范围——未被任何入口引用的 .ets 不会
 * 被编译检查，assembleHap 会假绿（2026-10-20 record 批次基线构建实证）。
 * 录制引擎批次落地并真实消费这些模块后删除本文件与 Index.ets 的引用。
 */

export { MeasuredSrcFps, ArcRateProbe, ARC_KEEP_TOLERANCE_PERCENT, arcKeepToleranceNs, ArcKeepRule } from './ArcKeepRule';
export { DropMark, ArcDropSegment, ArcDropLog } from './ArcDropLog';
export { BitratePolicy, StorageEstimate } from './BitratePolicy';
export { AutoStopRules, CameraLostWatch } from './AutoStopRules';
export {
  RecordHealthWindow, HealthVerdict, RetryAction, VIDEO_WINDOW_MS, FULL_WINDOW_MS,
  MAX_RESTART_ATTEMPTS, MIN_OUTPUT_BYTES, defaultRecordHealthWindow, healthVerdict,
  retryActionOf, resolveOutputBytes, streamHealthVerdict
} from './RecordHealth';
export {
  FrameLedger, FrameCounterSnapshot, FrameLedgerReport, frameMerged, frameCentiFps, frameCentiText,
  FrameStageDrops, frameStageDrops, frameLedgerLine, frameSessionSummary
} from './FrameLedger';
export { PendingName } from './PendingName';
export { SeriesIds } from './SeriesIds';
export { ArcRepairPlan } from './ArcRepairPlan';
export { ArcOp, ArcOpKind, ArcRepairFlow } from './ArcRepairFlow';
export { ArcRepairMerge } from './ArcRepairMerge';
export { arcRepairOk, arcRepairKeep } from './ArcRepairVerdict';
