/**
 * 移植编译图锚点（非业务代码）：pages/Index.ets 引用本模块，把 core 直译批次七个文件
 * （CameraAbility/LensSlot/VideoSizePicker/WotaParams/CurveModel/UIOrientation/diag/Diag）
 * 拉进 CompileArkTS 类型检查范围——未被任何入口引用的 .ets 不会参与编译检查，
 * assembleHap 会假绿（2026-10-20 record 批次基线构建实证；core 批次当时只有
 * Constants/WotaParams 的部分依赖路径经 record 锚点进入编译图，其余五个文件为假绿，
 * 本文件补齐）。相机预览批次（XComponent+会话）真实消费这些模块后删除本文件与
 * Index.ets 的引用（同 record/RecordAnchors.ets 口径）。
 */

export {
  RangeI, RangeF, CameraAbility, CameraAbilityArgs, FpsPick,
  forcedShutterOutOfRange, shutterCeilingNs, pickFpsRange,
  eqFocalMm, hFovDeg, adjustableFocus, hasAdjustableFocus
} from './CameraAbility';
export { LensType, LensSlot, lensTypeName, shouldHideLogicalHead, assignRearTiers, dumpLenses } from './LensSlot';
export {
  ReqSize, VideoSizeCandidates, SIZE_UNSUPPORTED_DURATION, screenAspectOf, aspectOf, sizeCloseTo,
  resolveSizeFor, VideoSizeOption, VideoSizeOptionArgs, VideoSizeTable, VideoSizeTableArgs,
  buildVideoSizeTable, DIRECT_PREVIEW_MAX_PIXELS, pickDirectPreviewSize, dumpVideoSizeTable
} from './VideoSizePicker';
export { FlowListener, StateFlow, NumRange, ParamState, WotaParams } from './WotaParams';
export { CurvePoint, ColorCurve, CurveStack, CurveInsertResult, CurveEdit } from './CurveModel';
export { UIOrientation, UIOrientations } from './UIOrientation';
export { Diag } from '../diag/Diag';
