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
 *
 * GL-4 光弧链在线版（本批新增）：
 * - startGpuRecord 追加 arcMode（0=off/1=MEND/2=DROP）、arcDstFps、deadband（0..32）；
 * - flushArcPending：停录前同步冲刷 MEND 滞留帧（1 = 发出末帧）；
 * - drainArcDrops：被抽帧位次紧凑串 "k,n;k,n"（取走即清）；
 * - measuredArcSrcFps：实测源帧率（0 = 样本不足）；
 * - getGpuFrameCounters："glCallbacks,glConsumed,swapOk,swapFail,arcDropped"（不清零，调用方做差）；
 * - getArcEffectiveMode：实际运行档（MEND 失败降 DROP 后为 2）。
 */
export const startGpuRecord: (fd: number, width: number, height: number, fps: number, bitrate: number,
  arcMode: number, arcDstFps: number, deadband: number) => string;
export const stopGpuRecord: () => string;
export const isGpuRecording: () => boolean;
export const getGpuRecordError: () => string;
export const flushArcPending: () => number;
export const drainArcDrops: () => string;
export const measuredArcSrcFps: () => number;
export const getGpuFrameCounters: () => string;
/** 已成功写入 muxer 的字节累计（轮转触发量；Stop 后 0） */
export const getGpuWrittenBytes: () => number;
/** 健康三格："trackAdded,muxStarted,samples"（1/0/整数） */
export const getGpuHealth: () => string;
export const getArcEffectiveMode: () => number;

/**
 * T20 A/B 无损剪辑导出（remux）：fd 由 ArkTS 打开并归其所有（native 不 close）。
 * startClipExport：空串 = 已入队；busy（已有导出）/args/worker。
 * getClipExportStatus：idle | running:<pct> | done:<basePtsUs>:<videoSamples>:<audioSamples>[:audio=skipped]
 *   | failed:<code> | cancelled。failed 机器码：source/track-format/no-video/no-sync/muxer/add-track/
 *   rotation/muxer-start/read/sample-too-large/write/empty-range。
 * probeClipTracks：只读 "videoCount,audioCount" 或 "err:<code>"。
 */
export const startClipExport: (inFd: number, inSize: number, outFd: number, startMs: number,
  endMs: number, audioOrdinal: number) => string;
export const getClipExportStatus: () => string;
export const cancelClipExport: () => void;
export const probeClipTracks: (inFd: number, inSize: number) => string;
