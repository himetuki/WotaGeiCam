/**
 * libentry.so 的动态加载桥（GL-1 P2 顺序契约的另一半）：
 * 页面顶层**不得**静态 import 'libentry.so'——那会让原生模块先于 XComponent 加载并以
 * 「无 OH_NATIVE_XCOMPONENT_OBJ 的 exports」完成 Init，surface 回调永不注册。
 * 本模块只在 GlPreview 的 XComponent onLoad 之后被 dynamic import；.so 的静态导入
 * 发生在模块求值这一刻（此时 XComponent 已完成原生回调注册），且 rollup 能对静态 import
 * 做 .so → requireNapi 改写（直接在页面里 import('libentry.so') 的**动态**形式不经该改写，
 * 会触发 rollup "Unexpected token" —— 故用本桥承载静态导入）。
 */
import glLib from 'libentry.so';

// T27-1 平台探针三口（startAvScreenProbe/getAvScreenProbeStatus/cancelAvScreenProbe，
// 见 cpp/avscreen_probe.*）：native 实现注册在 napi_init 的 exports 表里
export function startAvScreenProbe(durationMs: number): string {
  return glLib.startAvScreenProbe(durationMs);
}

export function getAvScreenProbeStatus(): string {
  return glLib.getAvScreenProbeStatus();
}

export function cancelAvScreenProbe(): void {
  glLib.cancelAvScreenProbe();
}

export function getInputSurfaceId(): string {
  return glLib.getInputSurfaceId();
}

export function getNativeImageError(): string {
  return glLib.getNativeImageError();
}

export function setDisplayGeometry(sensorDeg: number, displayDeg: number, mirrored: boolean): void {
  glLib.setDisplayGeometry(sensorDeg, displayDeg, mirrored);
}

/** WLB：上屏 letterbox 等比收缩因子（1,1 = 铺满；编码面满幅不受影响） */
export function setDisplayFit(fitX: number, fitY: number): void {
  glLib.setDisplayFit(fitX, fitY);
}

export function setEffect(effect: number): void {
  glLib.setEffect(effect);
}

export function setCurveEnabled(on: boolean): void {
  glLib.setCurveEnabled(on);
}

export function setZebraParams(threshold: number, density: number): void {
  glLib.setZebraParams(threshold, density);
}

export function setPeakingParams(strength: number, colorIndex: number): void {
  glLib.setPeakingParams(strength, colorIndex);
}

/** 直方图采样开关（曲线弹窗可见时开；native 关侧停采清快照） */
export function setHistogramEnabled(on: boolean): void {
  glLib.setHistogramEnabled(on);
}

/** 直方图快照（3×64 计数 R/G/B 的 Int32Array）；无样本返回 undefined（未采/已清） */
export function getHistogramSnapshot(): Int32Array | undefined {
  const v: Int32Array | null = glLib.getHistogramSnapshot();
  return v ?? undefined;
}

export function uploadCurveLut(data: ArrayBuffer): boolean {
  return glLib.uploadCurveLut(data);
}

export function setFrostEnabled(on: boolean): void {
  glLib.setFrostEnabled(on);
}

// ---------------------------------------------------------------- 追赶轮：光晕三口 + 降噪一口
// 参数序是 native napi 注册表的字面契约（cpp/napi_init.cpp 同名口），调用点不得重排

export function setGlowParams(enabled: number, threshold: number, soft: number, width: number,
  gain: number, add: number, freq: number, blend: number, colorA: number, colorB: number): void {
  glLib.setGlowParams(enabled, threshold, soft, width, gain, add, freq, blend, colorA, colorB);
}

/** 'off' 或 'on:<broken>:<scale>:<inW>:<inH>:<fps>:<rtW>:<rtH>:<clamped>' */
export function getGlowStatus(): string {
  return glLib.getGlowStatus();
}

/** 0=关 / 1=完整核 / 2=轻量核（取值见 core/Denoise.ets 的 DENOISE_LEVEL_*） */
export function setDenoiseLevel(level: number): void {
  glLib.setDenoiseLevel(level);
}

// ---------------------------------------------------------------- GL-4 GPU 录制（视频-only 最小切片）

export function startGpuRecord(fd: number, width: number, height: number, fps: number, bitrate: number,
  arcMode: number, arcDstFps: number, deadband: number): string {
  return glLib.startGpuRecord(fd, width, height, fps, bitrate, arcMode, arcDstFps, deadband);
}

export function stopGpuRecord(): string {
  return glLib.stopGpuRecord();
}

export function isGpuRecording(): boolean {
  return glLib.isGpuRecording();
}

export function getGpuRecordError(): string {
  return glLib.getGpuRecordError();
}

// ---------------------------------------------------------------- GL-4 光弧链在线版

export function flushArcPending(): number {
  return glLib.flushArcPending();
}

export function drainArcDrops(): string {
  return glLib.drainArcDrops();
}

export function measuredArcSrcFps(): number {
  return glLib.measuredArcSrcFps();
}

export function getGpuFrameCounters(): string {
  return glLib.getGpuFrameCounters();
}

export function getGpuWrittenBytes(): number {
  return glLib.getGpuWrittenBytes();
}

export function getGpuHealth(): string {
  return glLib.getGpuHealth();
}

export function getArcEffectiveMode(): number {
  return glLib.getArcEffectiveMode();
}

// ---------------------------------------------------------------- T20 A/B 无损剪辑导出

export function startClipExport(inFd: number, inSize: number, outFd: number, startMs: number,
  endMs: number, audioOrdinal: number): string {
  return glLib.startClipExport(inFd, inSize, outFd, startMs, endMs, audioOrdinal);
}

export function getClipExportStatus(): string {
  return glLib.getClipExportStatus();
}

export function cancelClipExport(): void {
  glLib.cancelClipExport();
}

export function probeClipTracks(inFd: number, inSize: number): string {
  return glLib.probeClipTracks(inFd, inSize);
}

// ---------------------------------------------------------------- T29 离线光弧修复（CPU 执行器核心）

export function probeArcRepairSource(inFd: number, inSize: number): string {
  return glLib.probeArcRepairSource(inFd, inSize);
}

export function startArcRepair(inFd: number, inSize: number, outFd: number, dstFps: number,
  bitrate: number, deadband: number, srcNum: number, srcDen: number, srcFrames: number,
  dstFrames: number, rotation: number, durationMs: number): string {
  return glLib.startArcRepair(inFd, inSize, outFd, dstFps, bitrate, deadband, srcNum, srcDen,
    srcFrames, dstFrames, rotation, durationMs);
}

export function getArcRepairStatus(): string {
  return glLib.getArcRepairStatus();
}

export function cancelArcRepair(): void {
  glLib.cancelArcRepair();
}
