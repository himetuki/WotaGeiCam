/**
 * 直译自上游 camera/Camera2Engine.kt 顶部的 DeviceStatus 枚举（harmony 分支）。
 * 独立成文件供 record/AutoStopRules 等纯逻辑引用；相机引擎本体（Camera2Engine）
 * 由 OHOS 相机适配层另行移植。
 */
export enum DeviceStatus {
  CLOSE,
  OPEN_ING,
  OPEN_SUCCEED,
  OPEN_FAILED_HIGH_FPS,
  OPEN_FAILED_IN_USED,
  OPEN_FAILED_INNER_ERROR,
  OPEN_FAILED_PERMISSION
}
