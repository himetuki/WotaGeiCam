/**
 * libentry.so 的 ArkTS 类型声明（DevEco C++ 模板同款结构）。
 * getInputSurfaceId：相机 PreviewOutput 用的 native surfaceId；EGL/NativeImage 未就绪返回空串。
 * getNativeImageError：NativeImage 失败原因（空串 = 正常/尚未失败；create-failed / set-listener-failed / get-surface-id-failed）。
 */
export const getInputSurfaceId: () => string;
export const getNativeImageError: () => string;
