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

export function getInputSurfaceId(): string {
  return glLib.getInputSurfaceId();
}

export function getNativeImageError(): string {
  return glLib.getNativeImageError();
}

export function setDisplayGeometry(sensorDeg: number, displayDeg: number, mirrored: boolean): void {
  glLib.setDisplayGeometry(sensorDeg, displayDeg, mirrored);
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

export function uploadCurveLut(data: ArrayBuffer): boolean {
  return glLib.uploadCurveLut(data);
}

export function setFrostEnabled(on: boolean): void {
  glLib.setFrostEnabled(on);
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

export function getArcEffectiveMode(): number {
  return glLib.getArcEffectiveMode();
}
