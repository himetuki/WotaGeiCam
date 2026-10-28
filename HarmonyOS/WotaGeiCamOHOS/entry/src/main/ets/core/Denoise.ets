/**
 * 降噪参数模型与负载分档纯函数。直译自上游 core/Denoise.kt（8646b05/316516f）。
 *
 * # 本侧平台裁定（2026-10-28 追赶轮评估，登记 PORT_MAP）
 * - **音频降噪（AudioDenoise.SYSTEM）**：上游挂 Android NoiseSuppressor（采集链音效）。
 *   OHOS 的 `setAudioEffectMode` 是**输出端**（Renderer）音效 API，采集/录制输入链无
 *   应用侧降噪等价物；且本侧 GPU 录制链 native 无音频轨（T27-3 留白）。→ 枚举与判据保留
 *   作契约，**无下发面**（不摆没反应的开关）。
 * - **HAL 画面降噪（halNoiseReductionMode/noiseReductionModeToSend）**：Camera2
 *   NOISE_REDUCTION_MODE 专属——OHOS 相机 API 面无此键（d.ts 全文核过零命中），
 *   不移植这两个发值函数（移植了也永远没有消费方）。
 * - **GLSL 双边（GPU_BILATERAL）**：GPU 链特性，随真机批（帧率/观感必须实测；同 Glow 纪律）。
 *   本批交付枚举 + 生效判据 + 负载分档，**不落 UI、不落 native**。
 *
 * # 与录制引擎的关系（上游语义照搬）
 * AudioDenoise 非 OFF 时环境音必须走 AudioFeeder（Codec 引擎）才能挂降噪——引擎选型据此切
 * Codec 是产品语义下的必要代价。本侧判据保留（audioDenoiseNeedsCodec），消费方随 T27-3。
 */

/** 音频降噪档：OFF=关 / SYSTEM=系统降噪（OHOS 采集链无对应，见文件头裁定） */
export enum AudioDenoise {
  OFF = 0,
  SYSTEM = 1
}

/** 音频降噪档 → 持久化键（上游 key 契约：'off'/'system'） */
export function audioDenoiseKey(v: AudioDenoise): string {
  return v === AudioDenoise.SYSTEM ? 'system' : 'off';
}

/** 持久化键 → 档（坏值回 OFF，对齐上游 fromKey） */
export function audioDenoiseFromKey(raw: string | undefined | null): AudioDenoise {
  return raw === 'system' ? AudioDenoise.SYSTEM : AudioDenoise.OFF;
}

/** 画面降噪档：OFF / HAL 快速 / HAL 高质量 / GPU 双边（OHOS HAL 键无对应，GPU 随真机批） */
export enum VideoDenoise {
  OFF = 0,
  HAL_FAST = 1,
  HAL_HIGH_QUALITY = 2,
  GPU_BILATERAL = 3
}

/** 画面降噪档 → 持久化键（上游 key 契约：off/hal_fast/hal_hq/gpu_bilateral） */
export function videoDenoiseKey(v: VideoDenoise): string {
  switch (v) {
    case VideoDenoise.HAL_FAST: return 'hal_fast';
    case VideoDenoise.HAL_HIGH_QUALITY: return 'hal_hq';
    case VideoDenoise.GPU_BILATERAL: return 'gpu_bilateral';
    default: return 'off';
  }
}

/** 持久化键 → 档（坏值回 OFF，对齐上游 fromKey） */
export function videoDenoiseFromKey(raw: string | undefined | null): VideoDenoise {
  switch (raw) {
    case 'hal_fast': return VideoDenoise.HAL_FAST;
    case 'hal_hq': return VideoDenoise.HAL_HIGH_QUALITY;
    case 'gpu_bilateral': return VideoDenoise.GPU_BILATERAL;
    default: return VideoDenoise.OFF;
  }
}

/**
 * 画面降噪的**实际生效档**（纯函数，全表测）。两件事在这里定型，UI 与引擎共用同一支：
 * 1. DIRECT 渲染模式下 GL 管线不参与，GPU_BILATERAL 退回 HAL_FAST（不是静默失效：返回
 *    「实际档」，UI 据此显示「已退回 HAL 快速档」）；
 * 2. GPU_BILATERAL 只在 gpuMode=true 时成立。
 * OFF 恒原样返回。
 */
export function effectiveVideoDenoise(requested: VideoDenoise, gpuMode: boolean): VideoDenoise {
  return requested === VideoDenoise.GPU_BILATERAL && !gpuMode
    ? VideoDenoise.HAL_FAST : requested;
}

/** 是否需要把录制切到 Codec 引擎（环境音降噪的挂载前提）：音频降噪非 OFF 即需要 */
export function audioDenoiseNeedsCodec(audio: AudioDenoise): boolean {
  return audio !== AudioDenoise.OFF;
}

/**
 * 画面降噪**这一刻真的在降噪吗**（UI「生效中」标记/胶囊选中态的唯一判据）。
 * 三条口径少一条界面就说谎：① OFF ⇒ 没有；② GPU 双边在直连渲染下退回 HAL 快速档 ⇒ 仍算有
 * （否则胶囊选中态跟着渲染模式乱闪）；③ **成片恒按所选档降噪**（上游 2026-10-27 双槽改口）——
 * 监看叠加只上屏不会让成片失去降噪，旧版「monitorEffectOn=false 才降噪」的口径会让界面说谎。
 */
export function videoDenoiseActive(requested: VideoDenoise, gpuMode: boolean): boolean {
  return effectiveVideoDenoise(requested, gpuMode) !== VideoDenoise.OFF;
}

// GPU 双边的档位编号（0/1/2）：算档/ProgramKey.denoise 记档/挑片元着色器三处共用一套值。
// 散写魔数迟早对不上（0=关 / 1=完整 / 2=轻量）。

/** 无降噪（program 键的降噪维度取此值） */
export const DENOISE_LEVEL_OFF: number = 0;
/** 完整核（8 邻域、9 次采样） */
export const DENOISE_LEVEL_FULL: number = 1;
/** 轻量核（4 邻域、5 次采样；按负载自动降档，见 denoiseKernelLite） */
export const DENOISE_LEVEL_LITE: number = 2;

/** 完整核（9 次采样）的负载上限：1.5e8 像素/秒（1080p60 = 1.24e8 留在完整档）。
 *  上游与 Glow 分档共用同一魔数——单一真源在本文件（Glow.ets 自此导入）。 */
export const DENOISE_FULL_KERNEL_PIXEL_RATE: number = 150_000_000;

/**
 * 画面降噪的**负载分档**（纯函数）：编码面「像素 × 帧率」超过阈值 ⇒ 轻量核。4K30/1080p120/
 * 4K60/1080p240 自动落轻量档。为什么按像素率不按 GPU 型号：像素率是「每帧采样次数」的正比量，
 * 完全能从录制参数算出——不需要真机探测、也不需要维护机型名单（名单一发新机就过时）。
 * 语义边界（**自动降档 ≠ 关档**）：核变小、仍然降噪；用户选的档位、面板文案、持久化值都不变。
 * 录制开始后提示「已按本机负载自动用轻量降噪」——所有自动降档都要让用户看得见。
 */
export function denoiseKernelLite(widthPx: number, heightPx: number, fps: number): boolean {
  if (widthPx <= 0 || heightPx <= 0 || fps <= 0) {
    return false;
  }
  return widthPx * heightPx * fps > DENOISE_FULL_KERNEL_PIXEL_RATE;
}
