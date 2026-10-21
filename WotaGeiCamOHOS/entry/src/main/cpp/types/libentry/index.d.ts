/**
 * libentry.so 的 ArkTS 类型声明（DevEco C++ 模板同款结构）。
 * getInputSurfaceId：相机 PreviewOutput 用的 native surfaceId；EGL/NativeImage 未就绪返回空串。
 * getNativeImageError：NativeImage 失败原因（空串 = 正常/尚未失败；create-failed / set-listener-failed / get-surface-id-failed）。
 */
export const getInputSurfaceId: () => string;
export const getNativeImageError: () => string;
export const setDisplayGeometry: (sensorDeg: number, displayDeg: number, mirrored: boolean) => void;
export const setEffect: (effect: number) => void;
export const setCurveEnabled: (on: boolean) => void;
export const setZebraParams: (threshold: number, density: number) => void;
export const setPeakingParams: (strength: number, colorIndex: number) => void;
export const uploadCurveLut: (data: ArrayBuffer) => boolean;
export const setFrostEnabled: (on: boolean) => void;
/**
 * GL-4 GPU 录制最小切片（视频-only、单段）：
 * - startGpuRecord：fd 由 ArkTS fileIo 打开并归其所有（native 不 close）；空串 = 成功，非空 = 机器码
 *   （busy/encoder-create/register-callback/format-create/format-key/configure/get-surface/prepare/
 *    muxer/track-format/track/muxer-rotation/muxer-start/start/encoder-surface）；
 * - stopGpuRecord：摘要 `ok samples=N eos=0/1` 或错误串（muxer-write/pts-set/muxer-missing/
 *   engine-error/surface-lost/encoder-pass-failed）；
 * - isGpuRecording：可继续送帧 = RECORDING 且无写失败（STARTING/writeFailed/STOPPING/IDLE 均为 false）；
 *   记录仍活跃但已写失败时，页面应据 false 收尾并读 getGpuRecordError；
 * - getGpuRecordError：最近一次录制错误（空串 = 无）。
 */
export const startGpuRecord: (fd: number, width: number, height: number, fps: number, bitrate: number) => string;
export const stopGpuRecord: () => string;
export const isGpuRecording: () => boolean;
export const getGpuRecordError: () => string;
