/**
 * libentry.so 的 ArkTS 类型声明（DevEco C++ 模板同款结构）。
 * getInputSurfaceId：相机 PreviewOutput 用的 native surfaceId；EGL/NativeImage 未就绪返回空串。
 * getNativeImageError：NativeImage 失败原因（空串 = 正常/尚未失败；create-failed / set-listener-failed / get-surface-id-failed）。
 */
export const getInputSurfaceId: () => string;
/** YUV 帧源（设备仅 YUV_420_SP 时）：画幅登记 + 取原始缓冲自转 RGBA；返回相机面 surfaceId，空串=不可用 */
export const enableYuvFeed: (width: number, height: number) => string;
/** YUV 帧源诊断（mode/acq/fail/err/fed/ffail/ferr/fmt/stride/size/wh）；Diag 导出定位用 */
export const getYuvDiag: () => string;
export const getNativeImageError: () => string;
export const setDisplayGeometry: (sensorDeg: number, displayDeg: number, mirrored: boolean) => void;
/** WLB：上屏 letterbox 等比收缩因子（NDC 半域，1,1 = 铺满；编码面恒满幅） */
export const setDisplayFit: (fitX: number, fitY: number) => void;
export const setEffect: (effect: number) => void;
export const setCurveEnabled: (on: boolean) => void;
export const setZebraParams: (threshold: number, density: number) => void;
export const setPeakingParams: (strength: number, colorIndex: number) => void;
/** 直方图采样开关（RGB 曲线框背景，f31d18c）：曲线弹窗可见时开；关 = 停采清快照 */
export const setHistogramEnabled: (on: boolean) => void;
/** 直方图快照（3×64 计数 R/G/B 的 Int32Array）；无样本 null。归一在 ArkTS core/HistogramMath */
export const getHistogramSnapshot: () => Int32Array | null;
export const uploadCurveLut: (data: ArrayBuffer) => boolean;
export const setFrostEnabled: (on: boolean) => void;
/**
 * Glow 光晕（2026-10-28 批，上游 core/Glow.kt + camera/GlowBlurChain.kt）：
 * - setGlowParams：enabled 0/1；threshold 0.6..1.0（2026-10-29 口径 60~100%）、soft 0..0.5、
 *   width 0..0.2（RT 短边单位）、gain 0..4、add 0..1、freq 0..64、blend 0..1；
 *   tint=ARGB（白 0xFFFFFFFF = 不改色，跟随原色）、colorOnly 0/1（1 = 仅色盘颜色）。
 *   native 侧越域钳制、NaN/Inf 回落出厂值（与上游 sanitize 同域）；打开动作给停用链一次重试。
 * - getGlowStatus：`off`，或 `on:<broken0/1>:<scale>:<inW>:<inH>:<fps>:<rtW>:<rtH>:<clamped0/1>`；
 *   scale = 1/2/4（像素率 ≤40M/≤150M/其余），fps 未知按 30，rt 未建时两位为 0。
 * - setDenoiseLevel：0=关 / 1=完整核（9 采样）/ 2=轻量核（5 采样）；0 时槽键归零、下一帧回收变体。
 */
export const setGlowParams: (enabled: number, threshold: number, soft: number, width: number,
  gain: number, add: number, freq: number, blend: number, tint: number, colorOnly: number) => void;
export const getGlowStatus: () => string;
/** 录制音轨 PCM 喂入（S16LE 交织 48k 立体声）；''=已接收，非空=拒绝原因 */
export const pushAudioPcm: (pcm: ArrayBuffer) => string;
export const setDenoiseLevel: (level: number) => void;
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
 * audioShiftUs：选中音轨的同步平移（µs，内录轨 = −offset、环境轨 0）；预扫与拷贝循环同吃它。
 * 第 7 位 audioShiftUs 可选（native 兼容老 6 参调用点，缺省按 0）。
 */
export const startClipExport: (inFd: number, inSize: number, outFd: number, startMs: number,
  endMs: number, audioOrdinal: number, audioShiftUs?: number) => string;
export const getClipExportStatus: () => string;
/** 后渲染导出（决策口径对拍 player/SceneExportPlan.ets；glowSpec = encodeGlow 单串；取消是独立终态 cancel:） */
export const startSceneExport: (inFd: number, inSize: number, outFd: number, startMs: number,
  endMs: number, audioOrdinal: number, glowSpec: string, denoiseOn: number) => string;
export const getSceneExportStatus: () => string;
export const cancelSceneExport: () => void;
export const cancelClipExport: () => void;
export const probeClipTracks: (inFd: number, inSize: number) => string;

/**
 * T29 离线光弧修复（CPU 执行器核心）：fd 由 ArkTS 打开并归其所有（native 不 close）。
 * probeArcRepairSource：
 *   `ok:<w>:<h>:<rotation>:<containerFps>:<durationMs>:<cleanCount>:<medianUs>:<audioCount>:<mime>`
 *   或 `err:<code>`（ARC_SOURCE/ARC_NO_VIDEO/...）。
 * startArcRepair：空串 = 已入队；busy/args/worker = 立即失败。srcNum/srcDen 为有理数源帧率，
 *   srcFrames/dstFrames 由 ArkTS 纯逻辑算好传入；rotation 为源容器旋转（native 侧归一后写 muxer）。
 *   第 13 位 capOffsetMs 可选（native 兼容老 12 参调用点，缺省按 0）。
 * getArcRepairStatus：
 *   `idle` | `running:<pct>` | `done:<videoSamples>:<audioSamples>:<mux0/1>:<track0/1>:<stop0/1>`
 *   | `failed:<code>` | `cancelled`。T30 起 audioSamples = 全部音轨成功写入 muxer 的样本合计
 *   （无音轨源为 0；逐轨降级时只计成功 lane；串仍是 6 段）。
 * cancelArcRepair：置取消位（worker 在下一个样本边界收尾）。
 * capOffsetMs：播放页缓存的音轨同步偏移（ms，正 = 内录晚），只作用于双音轨第 2 条 lane。
 */
export const probeArcRepairSource: (inFd: number, inSize: number) => string;
export const startArcRepair: (inFd: number, inSize: number, outFd: number, dstFps: number,
  bitrate: number, deadband: number, srcNum: number, srcDen: number, srcFrames: number,
  dstFrames: number, rotation: number, durationMs: number, capOffsetMs?: number) => string;
export const getArcRepairStatus: () => string;
export const cancelArcRepair: () => void;

/**
 * T27-1 内录平台探针三口（cpp/avscreen_probe.*，P27 批；napi_init exports 表注册）：
 * - startAvScreenProbe：durationMs 缺省 2500（native 兼容 0/1 参调用）；返回状态串见
 *   getAvScreenProbeStatus；
 * - getAvScreenProbeStatus：idle | running:<elapsedMs> | ok:<字段> | failed:<code> | cancelled；
 * - cancelAvScreenProbe：置取消位。
 */
export const startAvScreenProbe: (durationMs?: number) => string;
export const getAvScreenProbeStatus: () => string;
export const cancelAvScreenProbe: () => void;
